package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import com.starcon.tenancy.Ids;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.1.3.2 组合唯一性校验：同一 SPU 下规格值组合的重复检测、矩阵生成前的预校验、 通过后生成**待建 SKU 草稿**（不是 SKU 本身）。
 *
 * <p>三条纪律贯穿本类：
 *
 * <ol>
 *   <li><b>唯一性由 DB 保证，本类只负责友好提示</b>。蓝图把这条写成硬要求： 「唯一性约束在数据库层也要有，应用层校验只用于友好提示，不能替代 DB 约束」。 所以 {@code
 *       spu_variant_combination} 的 UNIQUE(tenant_id,spu_id,signature) 是本体，
 *       本类做的每一次比对（提交批次内、既有草稿占用、既有真实 SKU）都只是**提前**说破它。 绕过本类直连写库照样撞索引——这是集成测试单独断言的一条。
 *   <li><b>冲突必须结构化，不能压成一个布尔值</b>。运营看到"有重复"没法干活： 他要知道是同一次提交里自己撞自己、撞上一次生成的草稿，还是撞了历史 SKU；
 *       要知道是哪几个组合、出现在第几条。所以 {@link CombinationConflict} 带 {@code reasonCode / signature /
 *       occurrenceIndexes / existingSkuId / existingDraftId}。
 *   <li><b>输入的两半都要查</b>。蓝图写的是「待创建/修改的组合集合」与「已存在的 SKU 组合」—— 后一半指真实 {@code sku} 行。本类实时按轴值解析既有 SKU
 *       的组合（不依赖任何回填）， 解析不出轴值的老数据再退到签名文本逐字比对，两条路都不猜。
 * </ol>
 *
 * <p>权限口径：读 {@code catalog.attribute.read}，写 {@code catalog.attribute.update}。 与 3.1.3.1
 * 同一口径——组合的唯一性作用域是 SPU 的规格结构，按属性域授权。
 */
@Service
public class VariantCombinationService {
  private final DSLContext sql;

  public VariantCombinationService(DSLContext sql) {
    this.sql = sql;
  }

  // ============================================================ 基准：SPU 的规格结构

  /**
   * 该 SPU 绑定的轴与取值——预校验的**基准**。
   *
   * <p>停用轴与停用取值仍在列表里（要留着让历史组合能解析）， 但校验时会被单独拦下：它们只读不写。
   */
  private List<BoundAxis> boundAxes(UUID spuId) {
    var axes =
        sql
            .fetch(
                "select b.spec_axis_id,b.position,a.code,a.name,a.enabled from spu_spec_axis b"
                    + " join spec_axis a on a.tenant_id=b.tenant_id and a.id=b.spec_axis_id"
                    + " where b.spu_id=? order by b.position,a.code",
                spuId)
            .map(
                r ->
                    new BoundAxis(
                        r.get("spec_axis_id", UUID.class),
                        r.get("code", String.class),
                        r.get("name", String.class),
                        r.get("position", Integer.class),
                        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
                        List.of()))
            .stream()
            .toList();
    if (axes.isEmpty()) return axes;
    var optionsByAxis =
        sql
            .fetch(
                "select o.spec_axis_id,o.value,o.position,o.enabled from spec_axis_option o"
                    + " join spu_spec_axis b on b.tenant_id=o.tenant_id and b.spec_axis_id=o.spec_axis_id"
                    + " where b.spu_id=? order by o.spec_axis_id,o.position,o.value",
                spuId)
            .stream()
            .collect(
                java.util.stream.Collectors.groupingBy(
                    r -> r.get("spec_axis_id", UUID.class),
                    java.util.stream.Collectors.mapping(
                        r ->
                            new AxisOption(
                                r.get("value", String.class),
                                r.get("position", Integer.class),
                                Boolean.TRUE.equals(r.get("enabled", Boolean.class))),
                        java.util.stream.Collectors.toList())));
    List<BoundAxis> result = new ArrayList<>();
    for (BoundAxis axis : axes)
      result.add(
          new BoundAxis(
              axis.specAxisId(),
              axis.code(),
              axis.name(),
              axis.position(),
              axis.enabled(),
              optionsByAxis.getOrDefault(axis.specAxisId(), List.of())));
    return result;
  }

  private UUID requireSpu(UUID spuId) {
    var row = sql.fetchOne("select id,code,name from spu where id=?", spuId);
    if (row == null) throw CatalogService.missing();
    return row.get("id", UUID.class);
  }

  // ============================================================ 已存在的组合

  /**
   * 这个 SPU 已有的组合清单，两个来源合并：
   *
   * <ul>
   *   <li><b>草稿占用</b>（{@code spu_variant_combination}）：上一批草稿已经占住位。
   *   <li><b>已存在的真实 SKU</b>：按 {@code attribute_value} 上的 variant_axis 取值实时解析。
   *       解析不出轴值的历史数据退到签名文本逐字比对，标 {@code source=SKU_SIGNATURE}—— 不猜它对应哪个轴，界面上要说清"只比到签名文本"。
   * </ul>
   *
   * <p>刻意**不把读到的 SKU 回写进占用表**：读路径不做写，回填会留下"回填那一刻之前" 这个漏检窗口，而实时解析没有这个窗口。
   */
  public List<ExistingCombination> combinations(UUID spuId) {
    requireSpu(spuId);
    List<ExistingCombination> result = new ArrayList<>();

    // 来源一：矩阵草稿占用（含它的轴值明细）
    var reservedRows =
        sql
            .fetch(
                "select c.id,c.signature,c.draft_id from spu_variant_combination c"
                    + " where c.spu_id=? order by c.created_at,c.id",
                spuId)
            .stream()
            .toList();
    if (!reservedRows.isEmpty()) {
      var reservedValues =
          sql
              .fetch(
                  "select v.combination_id,v.spec_axis_id,v.axis_code,a.name,v.option_value,v.position"
                      + " from spu_variant_combination_value v"
                      + " join spu_variant_combination c"
                      + "   on c.tenant_id=v.tenant_id and c.id=v.combination_id"
                      + " left join spec_axis a on a.tenant_id=v.tenant_id and a.code=v.axis_code"
                      + " where c.spu_id=? order by v.combination_id,v.axis_code",
                  spuId)
              .stream()
              .collect(
                  java.util.stream.Collectors.groupingBy(
                      r -> r.get("combination_id", UUID.class),
                      java.util.stream.Collectors.mapping(
                          this::toCombinationValue, java.util.stream.Collectors.toList())));
      for (Record r : reservedRows) {
        UUID id = r.get("id", UUID.class);
        result.add(
            new ExistingCombination(
                r.get("signature", String.class),
                reservedValues.getOrDefault(id, List.of()),
                "RESERVED",
                id,
                null,
                null,
                r.get("draft_id", UUID.class)));
      }
    }

    // 来源二：已存在的真实 SKU，按轴值解析组合。
    var skus =
        sql
            .fetch(
                "select id,code,variant_signature from sku where spu_id=? order by created_at,id",
                spuId)
            .stream()
            .toList();
    if (!skus.isEmpty()) {
      var axisNames =
          sql
              .fetch(
                  "select distinct d.code,d.name from attribute_definition d"
                      + " join spu_spec_axis b on b.tenant_id=d.tenant_id and b.attribute_definition_id=d.id"
                      + " where b.spu_id=?",
                  spuId)
              .stream()
              .collect(
                  java.util.stream.Collectors.toMap(
                      r -> r.get("code", String.class),
                      r -> r.get("name", String.class),
                      (a, b) -> a));
      var skuValues =
          sql
              .fetch(
                  "select v.sku_id,d.code,i.option_value,i.ordinal from attribute_value v"
                      + " join attribute_definition d"
                      + "   on d.tenant_id=v.tenant_id and d.id=v.attribute_id and d.variant_axis"
                      + " join attribute_value_item i"
                      + "   on i.tenant_id=v.tenant_id and i.value_id=v.id and i.attribute_id=d.id"
                      + " where v.spu_id=? and v.sku_id is not null and v.state='SET'"
                      + "   and i.option_value is not null"
                      + " order by v.sku_id,d.code,i.ordinal",
                  spuId)
              .stream()
              .collect(
                  java.util.stream.Collectors.groupingBy(
                      r -> r.get("sku_id", UUID.class),
                      java.util.stream.Collectors.mapping(
                          r ->
                              new CombinationRules.Part(
                                  r.get("code", String.class), r.get("option_value", String.class)),
                          java.util.stream.Collectors.toList())));
      for (Record r : skus) {
        UUID skuId = r.get("id", UUID.class);
        List<CombinationRules.Part> parts = skuValues.getOrDefault(skuId, List.of());
        List<CombinationValue> values = new ArrayList<>();
        int position = 0;
        for (CombinationRules.Part part : parts)
          values.add(
              new CombinationValue(
                  null,
                  part.axisCode(),
                  axisNames.getOrDefault(part.axisCode(), part.axisCode()),
                  part.optionValue(),
                  position++));
        // 解析得出轴值：签名由轴值重算，精确可比。
        // 解析不出：只用签名文本本身做身份，source 标清楚，别让人以为比对过轴值。
        var signature = CombinationRules.signature(parts, Set.of());
        String text =
            signature.signature() != null
                ? signature.signature()
                : r.get("variant_signature", String.class);
        result.add(
            new ExistingCombination(
                text,
                values,
                parts.isEmpty() ? "SKU_SIGNATURE" : "SKU",
                null,
                skuId,
                r.get("code", String.class),
                null));
      }
    }
    return result;
  }

  /** 台账明细 → 组合轴值。轴名从 spec_axis 现取，轴被删时退回轴编码（不留空洞）。 */
  private CombinationValue toCombinationValue(Record r) {
    String axisCode = r.get("axis_code", String.class);
    String axisName = r.get("name", String.class);
    return new CombinationValue(
        r.get("spec_axis_id", UUID.class),
        axisCode,
        axisName == null ? axisCode : axisName,
        r.get("option_value", String.class),
        r.get("position", Integer.class));
  }

  // ============================================================ 预校验

  /**
   * 生成 SKU 矩阵前的预校验：不写任何东西，只把冲突清单与合法组合算出来。
   *
   * <p>蓝图要求「在生成 SKU 矩阵前阻止非法组合」，这一层就是"阻止"发生的地方： 组合不完整、混用别的 SPU 的规格轴、取值不属于该轴、取值已停用、同一批里重复、
   * 撞既有草稿、撞既有真实 SKU —— 七类各自一个 {@code reasonCode}。
   */
  public CombinationCheckResult check(UUID spuId, CombinationCheckInput input) {
    requireSpu(spuId);
    return validate(spuId, input.combinations());
  }

  /** 校验的同一份实现：check 与 generateDraft 走同一个口径，不允许两边判得不一样。 */
  private CombinationCheckResult validate(UUID spuId, List<CombinationInput> submitted) {
    List<BoundAxis> axes = boundAxes(spuId);
    var byId = new LinkedHashMap<UUID, BoundAxis>();
    var byCode = new LinkedHashMap<String, BoundAxis>();
    for (BoundAxis axis : axes) {
      byId.put(axis.specAxisId(), axis);
      byCode.put(axis.code(), axis);
    }
    Set<String> boundCodes = new LinkedHashSet<>(byCode.keySet());

    List<CombinationConflict> conflicts = new ArrayList<>();
    List<String> notes = new ArrayList<>();

    if (axes.isEmpty()) {
      conflicts.add(
          conflict(
              "NO_AXIS_BOUND",
              "SPU_NO_AXIS",
              "该 SPU 还没有绑定任何规格轴，无法生成组合：请先在规格轴定义里绑定轴并登记取值",
              null,
              List.of(),
              List.of(),
              null,
              null,
              null));
      return new CombinationCheckResult(
          false, spuId, axes, submitted.size(), 0, 1, conflicts, List.of(), notes);
    }

    // 已存在的组合：草稿占用 + 真实 SKU。
    List<ExistingCombination> existing = combinations(spuId);
    var reservedBySignature = new LinkedHashMap<String, ExistingCombination>();
    var skuBySignature = new LinkedHashMap<String, ExistingCombination>();
    for (ExistingCombination row : existing) {
      if (row.signature() == null || row.signature().isBlank()) continue;
      if ("RESERVED".equals(row.source())) reservedBySignature.putIfAbsent(row.signature(), row);
      else skuBySignature.putIfAbsent(row.signature(), row);
    }

    // ---- 逐条校验结构
    var normalized = new ArrayList<List<CombinationValue>>(); // 已解析的轴值
    var submittedCodes = new ArrayList<Set<String>>(); // 提交里出现过的轴（不论取值是否合法）
    var signatures = new ArrayList<String>();
    for (int index = 0; index < submitted.size(); index++) {
      CombinationInput combination = submitted.get(index);
      List<CombinationValueInput> values =
          combination == null || combination.values() == null ? List.of() : combination.values();

      // 同一组合里重复的轴：先按 id 找一次（用户可能把同一条轴填了两遍）。
      var seenAxisIds = new LinkedHashSet<UUID>();
      var duplicatedAxisIds = new LinkedHashSet<UUID>();
      for (CombinationValueInput value : values)
        if (value.specAxisId() != null && !seenAxisIds.add(value.specAxisId()))
          duplicatedAxisIds.add(value.specAxisId());

      if (values.isEmpty()) {
        conflicts.add(
            conflict(
                "COMBINATION_EMPTY",
                "组合不完整",
                "第 " + (index + 1) + " 条组合没有任何规格取值：一个 SKU 至少要有一个规格取值",
                null,
                List.of(),
                List.of(index),
                null,
                null,
                null));
        normalized.add(List.of());
        submittedCodes.add(Set.of());
        signatures.add(null);
        continue;
      }
      if (values.size() > CombinationRules.MAX_COMBINATION_AXES) {
        conflicts.add(
            conflict(
                "TOO_MANY_AXES",
                "组合轴数超限",
                "第 "
                    + (index + 1)
                    + " 条组合有 "
                    + values.size()
                    + " 条规格轴，超过单组合上限 "
                    + CombinationRules.MAX_COMBINATION_AXES,
                null,
                List.of(),
                List.of(index),
                null,
                null,
                null));
      }

      List<CombinationValue> resolved = new ArrayList<>();
      boolean structuralProblem = false;
      Set<String> presentCodes = new LinkedHashSet<>();
      var codesById = new LinkedHashMap<UUID, String>();

      for (CombinationValueInput value : values) {
        UUID axisId = value.specAxisId();
        String raw = value.value() == null ? "" : value.value().trim();

        if (axisId == null) {
          structuralProblem = true;
          conflicts.add(
              conflict(
                  "AXIS_MISSING",
                  "规格轴缺失",
                  "第 " + (index + 1) + " 条组合里有一项没有指定规格轴",
                  null,
                  List.of(),
                  List.of(index),
                  null,
                  null,
                  null));
          continue;
        }
        BoundAxis axis = byId.get(axisId);
        if (axis == null) {
          // 混用别的 SPU 的规格轴：最容易被"从另一个商品复制组合"带进来。
          structuralProblem = true;
          var foreign = sql.fetchOne("select code,name,enabled from spec_axis where id=?", axisId);
          String label = foreign == null ? axisId.toString() : foreign.get("code", String.class);
          conflicts.add(
              conflict(
                  "UNKNOWN_AXIS",
                  "规格轴不属于该 SPU",
                  "第 "
                      + (index + 1)
                      + " 条组合里的规格轴「"
                      + label
                      + "」没有绑定到这个 SPU："
                      + "组合只能由本 SPU 已绑定的规格轴组成，请勿跨 SPU 混用规格轴",
                  null,
                  List.of(),
                  List.of(index),
                  null,
                  null,
                  null));
          continue;
        }
        codesById.put(axisId, axis.code());
        presentCodes.add(axis.code());

        if (duplicatedAxisIds.contains(axisId)) {
          structuralProblem = true;
          conflicts.add(
              conflict(
                  "DUPLICATE_AXIS",
                  "同一组合内规格轴重复",
                  "第 " + (index + 1) + " 条组合里规格轴「" + axis.name() + "」出现了多次：" + "一条规格轴在一个组合里只能取一个值",
                  null,
                  List.of(),
                  List.of(index),
                  null,
                  null,
                  null));
          continue;
        }
        if (!axis.enabled()) {
          structuralProblem = true;
          conflicts.add(
              conflict(
                  "AXIS_DISABLED",
                  "规格轴已停用",
                  "第 " + (index + 1) + " 条组合用了已停用的规格轴「" + axis.name() + "」：停用只挡新建，历史组合照常可读",
                  null,
                  List.of(),
                  List.of(index),
                  null,
                  null,
                  null));
          continue;
        }
        String valueProblem = SpecAxisRules.valueProblem(raw);
        if (valueProblem != null) {
          structuralProblem = true;
          conflicts.add(
              conflict(
                  "VALUE_INVALID",
                  "取值不合法",
                  "第 " + (index + 1) + " 条组合里规格轴「" + axis.name() + "」的取值有问题：" + valueProblem,
                  null,
                  List.of(),
                  List.of(index),
                  null,
                  null,
                  null));
          continue;
        }
        var option =
            axis.options().stream().filter(o -> o.value().equals(raw)).findFirst().orElse(null);
        if (option == null) {
          structuralProblem = true;
          conflicts.add(
              conflict(
                  "OPTION_UNKNOWN",
                  "取值不属于该规格轴",
                  "第 "
                      + (index + 1)
                      + " 条组合里规格轴「"
                      + axis.name()
                      + "」没有取值「"
                      + raw
                      + "」：取值必须来自该轴已登记的选项（候选："
                      + candidateText(axis)
                      + "）",
                  null,
                  List.of(),
                  List.of(index),
                  null,
                  null,
                  null));
          continue;
        }
        if (!option.enabled()) {
          structuralProblem = true;
          conflicts.add(
              conflict(
                  "OPTION_DISABLED",
                  "取值已停用",
                  "第 "
                      + (index + 1)
                      + " 条组合里规格轴「"
                      + axis.name()
                      + "」的取值「"
                      + raw
                      + "」已停用：停用只挡新建，历史组合照常可读",
                  null,
                  List.of(),
                  List.of(index),
                  null,
                  null,
                  null));
          continue;
        }
        resolved.add(
            new CombinationValue(
                axis.specAxisId(), axis.code(), axis.name(), option.value(), option.position()));
      }

      normalized.add(resolved);
      submittedCodes.add(new LinkedHashSet<>(presentCodes));
      var signature =
          CombinationRules.signature(
              resolved.stream()
                  .map(v -> new CombinationRules.Part(v.axisCode(), v.value()))
                  .toList(),
              CombinationRules.duplicateAxisCodes(new ArrayList<>(codesById.values())));
      if (signature.problem() != null && !structuralProblem) {
        conflicts.add(
            conflict(
                "COMBINATION_INCOMPLETE",
                "组合不完整",
                "第 " + (index + 1) + " 条组合无法构成唯一组合：" + signature.problem(),
                null,
                resolved,
                List.of(index),
                null,
                null,
                null));
        structuralProblem = true;
      }
      signatures.add(structuralProblem ? null : signature.signature());
    }

    // ---- 缺轴：必须把本 SPU 每条已绑定的轴都取到值
    //
    // 这里刻意**不**用"签名是否算得出来"当前置条件。只填颜色、漏了尺码的组合照样能算出一个
    // 短签名（"COLOR=红"），若拿它当门槛，它就会一路混进 planned——而"红"到底指红/大还是红/中
    // 取决于谁漏填了 SIZE，那正是唯一性要拦的东西。缺轴一律作废签名。
    for (int index = 0; index < normalized.size(); index++) {
      Set<String> present = submittedCodes.get(index);
      var missing = CombinationRules.missingAxisCodes(boundCodes, present);
      if (missing.isEmpty()) continue;
      var foreign = CombinationRules.foreignAxisCodes(boundCodes, present);
      conflicts.add(
          conflict(
              "INCOMPLETE_COMBINATION",
              "组合不完整",
              "第 "
                  + (index + 1)
                  + " 条组合缺少已绑定规格轴的取值："
                  + String.join("、", missing)
                  + "。本 SPU 已绑定 "
                  + boundCodes.size()
                  + " 条轴，组合必须每条轴各取一个值"
                  + (foreign.isEmpty() ? "" : "；另外混入了不属于本 SPU 的轴：" + String.join("、", foreign)),
              null,
              normalized.get(index),
              List.of(index),
              null,
              null,
              null));
      signatures.set(index, null);
    }

    // ---- 同一批里重复
    var duplicates = CombinationRules.duplicates(signatures);
    for (var entry : duplicates.entrySet()) {
      List<Integer> indexes = entry.getValue();
      conflicts.add(
          conflict(
              "DUPLICATE_IN_BATCH",
              "本次提交内重复",
              "组合 "
                  + entry.getKey()
                  + " 在本次提交中出现了 "
                  + indexes.size()
                  + " 次（第 "
                  + indexes.stream().map(i -> String.valueOf(i + 1)).toList()
                  + " 条）："
                  + "同一个 SPU 下不允许出现重复的规格值组合，请合并后再提交",
              entry.getKey(),
              normalized.get(indexes.getFirst()),
              indexes,
              null,
              null,
              null));
    }

    // ---- 与既有组合比对（草稿占用 + 真实 SKU）
    var seen = new LinkedHashSet<String>();
    List<PlannedCombination> planned = new ArrayList<>();
    for (int index = 0; index < signatures.size(); index++) {
      String signature = signatures.get(index);
      if (signature == null) continue;
      // 批次内重复的那些不进入 planned：它们已经是冲突，不是可用组合。
      if (duplicates.containsKey(signature)) continue;
      if (!seen.add(signature)) continue;

      var reserved = reservedBySignature.get(signature);
      if (reserved != null) {
        conflicts.add(
            conflict(
                "ALREADY_RESERVED",
                "组合已被矩阵草稿占用",
                "组合 " + signature + " 已被矩阵草稿占用，不能重复生成：" + "请先作废那份草稿，或从组合里去掉这些取值",
                signature,
                normalized.get(index),
                List.of(index),
                reserved.combinationId(),
                null,
                reserved.draftId()));
        continue;
      }
      var sku = skuBySignature.get(signature);
      if (sku != null) {
        String why =
            "SKU_SIGNATURE".equals(sku.source())
                ? "该 SKU 没有可解析的规格轴取值，只能按签名文本逐字比对命中"
                : "按规格轴取值逐条比对命中";
        conflicts.add(
            conflict(
                "ALREADY_SKU",
                "组合与已存在的 SKU 相同",
                "组合 " + signature + " 与已存在的 SKU「" + sku.skuCode() + "」相同（" + why + "），不能重复生成",
                signature,
                normalized.get(index),
                List.of(index),
                null,
                sku.skuId(),
                null));
        continue;
      }
      planned.add(new PlannedCombination(signature, normalized.get(index)));
    }

    return new CombinationCheckResult(
        conflicts.isEmpty(),
        spuId,
        axes,
        submitted.size(),
        planned.size(),
        conflicts.size(),
        conflicts,
        planned,
        notes);
  }

  private String candidateText(BoundAxis axis) {
    String joined =
        axis.options().stream()
            .map(AxisOption::value)
            .limit(8)
            .collect(java.util.stream.Collectors.joining("、"));
    return axis.options().size() > 8 ? joined + "…" : (joined.isEmpty() ? "（该轴还没有任何取值）" : joined);
  }

  private static CombinationConflict conflict(
      String reasonCode,
      String reason,
      String message,
      String signature,
      List<CombinationValue> values,
      List<Integer> indexes,
      UUID existingCombinationId,
      UUID existingSkuId,
      UUID existingDraftId) {
    return new CombinationConflict(
        reasonCode,
        reason,
        message,
        signature,
        values.isEmpty() ? null : values,
        indexes,
        existingCombinationId,
        existingSkuId,
        existingDraftId);
  }

  // ============================================================ 生成待建 SKU 草稿

  /**
   * 校验通过则生成**待建 SKU 草稿**：组合在 {@code spu_variant_combination} 里占住位， 草稿行与明细留在 {@code
   * sku_matrix_draft(_item)}。**不写 {@code sku} 表**—— 真正落 SKU 是下游 3.1.2.1 的职责（它还要按 V7 的编码方案分配 code 与
   * barcode）。
   *
   * <p>任一条冲突都不生成整份草稿（全有或全无）。理由：草稿是"可以照着建 SKU"的交付物， 半份草稿会让下游不知道该不该建；宁可把完整冲突清单退回让人改。
   */
  public SkuMatrixDraft generateDraft(
      AccessService.Access actor, UUID spuId, VariantDraftInput input) {
    requireSpu(spuId);
    String mode =
        input.mode() == null ? "" : input.mode().trim().toUpperCase(java.util.Locale.ROOT);
    CatalogService.check(
        mode.equals("MATRIX") || mode.equals("EXPLICIT"),
        "生成模式只能是 MATRIX（按各轴启用取值展开矩阵）或 EXPLICIT（按提交的组合）");

    List<CombinationInput> combinations = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    if (mode.equals("MATRIX")) {
      combinations.addAll(expandMatrix(spuId, notes));
      notes.add("矩阵模式：按各轴启用取值展开，共 " + combinations.size() + " 条组合待校验。");
    } else {
      var submitted =
          input.combinations() == null ? List.<CombinationInput>of() : input.combinations();
      CatalogService.check(!submitted.isEmpty(), "EXPLICIT 模式必须提交至少一个组合");
      combinations.addAll(submitted);
    }

    var check = validate(spuId, combinations);
    if (!check.valid()) throw new CombinationConflictException(check);

    OffsetDateTime occurredAt =
        input.occurredAt() == null || input.occurredAt().isBlank()
            ? OffsetDateTime.now()
            : OffsetDateTime.parse(input.occurredAt().trim());
    String note =
        input.notes() == null || input.notes().isBlank()
            ? String.join(" ", notes)
            : input.notes().trim();

    UUID draftId = Ids.next();
    try {
      sql.execute(
          "insert into sku_matrix_draft(id,tenant_id,spu_id,mode,status,axis_count,combination_count,"
              + "notes,actor_id,occurred_at) values(?,?,?,?,'PENDING',?,?,?,?,?::timestamptz)",
          draftId,
          actor.tenantId(),
          spuId,
          mode,
          check.axes().size(),
          check.planned().size(),
          note,
          actor.userId(),
          occurredAt);
    } catch (DataAccessException e) {
      throw conflict("矩阵草稿写入失败，请刷新后重试");
    }

    int position = 0;
    for (PlannedCombination planned : check.planned()) {
      UUID combinationId = Ids.next();
      try {
        // 这里就是蓝图那句"唯一性约束在数据库层也要有"的落点：
        // 本类已查过一遍，但真约束在唯一索引上——并发或绕过应用层的重复在这里被挡。
        sql.execute(
            "insert into spu_variant_combination(id,tenant_id,spu_id,signature,axis_count,draft_id,"
                + "actor_id,occurred_at) values(?,?,?,?,?,?,?,?::timestamptz)",
            combinationId,
            actor.tenantId(),
            spuId,
            planned.signature(),
            planned.values().size(),
            draftId,
            actor.userId(),
            occurredAt);
        for (CombinationValue value : planned.values())
          sql.execute(
              "insert into spu_variant_combination_value(tenant_id,combination_id,spec_axis_id,"
                  + "axis_code,option_value,position) values(?,?,?,?,?,?)",
              actor.tenantId(),
              combinationId,
              value.specAxisId(),
              value.axisCode(),
              value.value(),
              value.position());
      } catch (DataAccessException e) {
        throw conflict(
            "组合「"
                + planned.signature()
                + "」写入被数据库唯一约束拒绝：它已存在（可能是并发提交或历史 SKU 占用了同一组合）。"
                + uniquenessHint(e));
      }
      sql.execute(
          "insert into sku_matrix_draft_item(tenant_id,draft_id,combination_id,position) values(?,?,?,?)",
          actor.tenantId(),
          draftId,
          combinationId,
          position++);
    }

    audit(actor, draftId, "SkuMatrixDraftGenerated");
    return draft(spuId, draftId);
  }

  /**
   * 矩阵模式展开：各轴启用取值的笛卡尔积。
   *
   * <p>空矩阵与超限矩阵都在这里判掉：一条轴没有启用取值时矩阵是空的（不该按 1 假装有值）， 超 {@link
   * CombinationRules#MAX_DRAFT_COMBINATIONS} 时报**真实行数**再拒绝， 不做静默截断——截断出来的草稿会让人以为矩阵就这么多。
   */
  private List<CombinationInput> expandMatrix(UUID spuId, List<String> notes) {
    List<BoundAxis> axes = boundAxes(spuId);
    List<List<String>> optionLists = new ArrayList<>();
    List<UUID> axisIds = new ArrayList<>();
    for (BoundAxis axis : axes) {
      if (!axis.enabled()) {
        CatalogService.check(false, "规格轴「" + axis.name() + "」已停用，不能生成矩阵：停用只挡新建，历史组合照常可读");
      }
      var enabled =
          axis.options().stream().filter(AxisOption::enabled).map(AxisOption::value).toList();
      CatalogService.check(
          !enabled.isEmpty(),
          "规格轴「" + axis.name() + "」没有任何启用中的取值，矩阵展开为 0 行：" + "请先为每条已绑定的轴登记至少一个启用取值");
      axisIds.add(axis.specAxisId());
      optionLists.add(enabled);
    }
    CatalogService.check(
        axes.size() <= CombinationRules.MAX_COMBINATION_AXES,
        "矩阵展开需要 " + axes.size() + " 条规格轴，超过单组合上限 " + CombinationRules.MAX_COMBINATION_AXES);
    long size = CombinationRules.matrixSize(optionLists);
    List<List<String>> expanded;
    try {
      expanded = CombinationRules.expand(optionLists, CombinationRules.MAX_DRAFT_COMBINATIONS);
    } catch (CombinationRules.MatrixTooLarge e) {
      throw conflict(
          "矩阵规模过大：按当前规格展开共 "
              + e.actual()
              + " 条组合，超过单次草稿上限 "
              + e.cap()
              + "。请减少启用的取值或规格轴后重试（可选做法：停用用不到的取值）。");
    }
    notes.add("矩阵规模 " + size + " 行。");
    List<CombinationInput> result = new ArrayList<>();
    for (List<String> row : expanded) {
      List<CombinationValueInput> values = new ArrayList<>();
      for (int i = 0; i < row.size(); i++)
        values.add(new CombinationValueInput(axisIds.get(i), row.get(i)));
      result.add(new CombinationInput(values));
    }
    return result;
  }

  private String uniquenessHint(DataAccessException e) {
    String message = e.getMessage() == null ? "" : e.getMessage();
    if (message.contains("已存在签名相同的 SKU")) return "冲突对象是已存在的真实 SKU。";
    if (message.contains("spu_variant_combination_tenant_id_spu_id_signature_key")
        || message.contains("duplicate key")) return "冲突对象是同一 SPU 下已存在的组合占用。";
    return "";
  }

  // ============================================================ 草稿读取与作废

  public List<SkuMatrixDraftSummary> drafts(UUID spuId) {
    requireSpu(spuId);
    return sql
        .fetch(
            "select d.id,d.spu_id,d.mode,d.status,d.axis_count,d.combination_count,d.notes,"
                + "d.occurred_at,d.created_at,d.discarded_at from sku_matrix_draft d"
                + " where d.spu_id=? order by d.created_at desc,d.id desc",
            spuId)
        .map(
            r ->
                new SkuMatrixDraftSummary(
                    r.get("id", UUID.class),
                    r.get("spu_id", UUID.class),
                    r.get("mode", String.class),
                    r.get("status", String.class),
                    r.get("axis_count", Integer.class),
                    r.get("combination_count", Integer.class),
                    r.get("notes", String.class),
                    instant(r.get("occurred_at", OffsetDateTime.class)),
                    instant(r.get("created_at", OffsetDateTime.class)),
                    instant(r.get("discarded_at", OffsetDateTime.class))))
        .stream()
        .toList();
  }

  public SkuMatrixDraft draft(UUID spuId, UUID draftId) {
    var row =
        sql.fetchOne(
            "select d.id,d.spu_id,d.mode,d.status,d.axis_count,d.combination_count,d.notes,"
                + "d.occurred_at,d.created_at,d.discarded_at,p.code,p.name"
                + " from sku_matrix_draft d join spu p on p.tenant_id=d.tenant_id and p.id=d.spu_id"
                + " where d.id=? and d.spu_id=?",
            draftId,
            spuId);
    if (row == null) throw CatalogService.missing();
    var combinations =
        sql
            .fetch(
                "select c.signature,v.spec_axis_id,v.axis_code,a.name as axis_name,"
                    + "v.option_value,v.position from sku_matrix_draft_item i"
                    + " join spu_variant_combination c"
                    + "   on c.tenant_id=i.tenant_id and c.id=i.combination_id"
                    + " left join spu_variant_combination_value v"
                    + "   on v.tenant_id=c.tenant_id and v.combination_id=c.id"
                    + " left join spec_axis a on a.tenant_id=c.tenant_id and a.code=v.axis_code"
                    + " where i.draft_id=? order by i.position,v.axis_code",
                draftId)
            .stream()
            .collect(
                java.util.stream.Collectors.groupingBy(
                    r -> r.get("signature", String.class),
                    java.util.stream.Collectors.mapping(
                        r ->
                            new CombinationValue(
                                r.get("spec_axis_id", UUID.class),
                                r.get("axis_code", String.class),
                                r.get("axis_name", String.class),
                                r.get("option_value", String.class),
                                r.get("position", Integer.class)),
                        java.util.stream.Collectors.toList())));
    // groupingBy 的 key 会丢掉插入顺序，这里按草稿项的 position 复原读到的顺序。
    var ordered =
        sql
            .fetch(
                "select c.signature from sku_matrix_draft_item i"
                    + " join spu_variant_combination c"
                    + "   on c.tenant_id=i.tenant_id and c.id=i.combination_id"
                    + " where i.draft_id=? order by i.position",
                draftId)
            .stream()
            .map(r -> r.get("signature", String.class))
            .toList();
    List<PlannedCombination> planned = new ArrayList<>();
    for (String signature : ordered)
      planned.add(new PlannedCombination(signature, combinations.get(signature)));
    return new SkuMatrixDraft(
        row.get("id", UUID.class),
        row.get("spu_id", UUID.class),
        row.get("code", String.class),
        row.get("name", String.class),
        row.get("mode", String.class),
        row.get("status", String.class),
        row.get("axis_count", Integer.class),
        row.get("combination_count", Integer.class),
        row.get("notes", String.class),
        planned,
        instant(row.get("occurred_at", OffsetDateTime.class)),
        instant(row.get("created_at", OffsetDateTime.class)),
        instant(row.get("discarded_at", OffsetDateTime.class)));
  }

  /**
   * 作废一份草稿并释放它占住的组合。
   *
   * <p>释放是**物理删除**占用行：留一行"已释放"会继续占着唯一索引， 让同一个组合永远无法再次生成。草稿行本身保留作留痕（status=DISCARDED）。
   */
  public SkuMatrixDraftDiscarded discardDraft(
      AccessService.Access actor, UUID spuId, UUID draftId) {
    requireSpu(spuId);
    var row =
        sql.fetchOne("select status from sku_matrix_draft where id=? and spu_id=?", draftId, spuId);
    if (row == null) throw CatalogService.missing();
    CatalogService.check(
        "PENDING".equals(row.get("status", String.class)), "这份草稿已经作废过了，请刷新后查看最新状态");
    long released =
        sql.fetchOne("select count(*) from spu_variant_combination where draft_id=?", draftId)
            .get(0, Long.class);
    sql.execute("delete from sku_matrix_draft_item where draft_id=?", draftId);
    sql.execute("delete from spu_variant_combination where draft_id=?", draftId);
    sql.execute(
        "update sku_matrix_draft set status='DISCARDED',discarded_at=now() where id=?", draftId);
    audit(actor, draftId, "SkuMatrixDraftDiscarded");
    return new SkuMatrixDraftDiscarded(draftId, (int) released);
  }

  // ============================================================ 面板与概览

  /**
   * 单 SPU 的组合校验面板。
   *
   * <p>{@code conflictMatrixCount} / {@code availableMatrixCount} 是本面板的重点：
   * 「矩阵里有多少组合已经被占」决定了还能不能再生成一批草稿。只给一个矩阵行数 （3.1.3.1 的口径）会让人以为 24 行就都能生成。
   */
  public VariantCombinationPanel panel(UUID spuId) {
    var spu = sql.fetchOne("select id,code,name from spu where id=?", spuId);
    if (spu == null) throw CatalogService.missing();
    List<BoundAxis> axes = boundAxes(spuId);
    List<ExistingCombination> existing = combinations(spuId);

    long reserved = existing.stream().filter(row -> "RESERVED".equals(row.source())).count();
    long existingSkus = existing.stream().filter(row -> "SKU".equals(row.source())).count();

    var optionLists =
        axes.stream()
            .map(
                axis ->
                    (List<String>)
                        axis.options().stream()
                            .filter(AxisOption::enabled)
                            .map(AxisOption::value)
                            .toList())
            .toList();
    long matrixRows = axes.isEmpty() ? 0 : CombinationRules.matrixSize(optionLists);
    boolean tooLarge = matrixRows > CombinationRules.MAX_DRAFT_COMBINATIONS;

    // 矩阵里已被占用的组合数 = 既有组合的签名落在当前矩阵里的个数。
    Set<String> matrixSignatures = new LinkedHashSet<>();
    if (axes.size() <= CombinationRules.MAX_COMBINATION_AXES && !tooLarge) {
      try {
        for (List<String> row :
            CombinationRules.expand(optionLists, CombinationRules.MAX_DRAFT_COMBINATIONS)) {
          // 按下标配对，不能用 row.indexOf(value)：两条轴可能有同一个取值（"红/红"），
          // 用值去找下标会把第二列错配到第一列的轴上，算出的签名是假的。
          List<CombinationRules.Part> parts = new ArrayList<>();
          for (int i = 0; i < row.size(); i++)
            parts.add(new CombinationRules.Part(axes.get(i).code(), row.get(i)));
          var signature = CombinationRules.signature(parts, Set.of());
          if (signature.signature() != null) matrixSignatures.add(signature.signature());
        }
      } catch (CombinationRules.MatrixTooLarge e) {
        tooLarge = true;
      }
    }
    long occupied =
        existing.stream()
            .filter(row -> row.signature() != null && matrixSignatures.contains(row.signature()))
            .count();

    String note =
        axes.isEmpty()
            ? "该 SPU 还没有绑定任何规格轴，矩阵为空，组合校验无从谈起。请先到规格轴定义绑定轴并登记取值。"
            : matrixRows == 0
                ? "已绑定规格轴，但至少一条轴没有任何启用取值，矩阵展开为 0 行。请先补齐每条轴的启用取值。"
                : tooLarge
                    ? "矩阵规模 "
                        + matrixRows
                        + " 行，超过单次草稿上限 "
                        + CombinationRules.MAX_DRAFT_COMBINATIONS
                        + "：请停用用不到的取值或减少规格轴后再生成草稿。"
                    : "矩阵共 "
                        + matrixRows
                        + " 条组合，其中 "
                        + occupied
                        + " 条已被占用（草稿占用或已存在 SKU），还可生成 "
                        + (matrixRows - occupied)
                        + " 条。生成草稿时会逐条与既有组合比对，任一条冲突则整份草稿不生成。";

    return new VariantCombinationPanel(
        spu.get("id", UUID.class),
        spu.get("code", String.class),
        spu.get("name", String.class),
        axes,
        matrixRows,
        reserved,
        existingSkus,
        occupied,
        Math.max(0, matrixRows - occupied),
        tooLarge,
        CombinationRules.MAX_DRAFT_COMBINATIONS,
        existing,
        drafts(spuId),
        note);
  }

  /** 组合唯一性概览。计数全部来自真实行，与 3.1.3.1 概览同一口径。 */
  public VariantCombinationOverview overview() {
    long axisSpus =
        sql.fetchOne("select count(distinct spu_id) from spu_spec_axis").get(0, Long.class);
    long reserved = sql.fetchOne("select count(*) from spu_variant_combination").get(0, Long.class);
    var drafts =
        sql.fetchOne(
            "select count(*) as all_drafts,count(*) filter (where status='PENDING') as pending,"
                + "count(*) filter (where status='DISCARDED') as discarded from sku_matrix_draft");
    long blocked =
        sql.fetchOne("select count(distinct spu_id) from sku where btrim(variant_signature)<>''")
            .get(0, Long.class);
    // 只有"绑了规格轴"又"已有 SKU"的 SPU，生成草稿时才会真的逐条比对——
    // 有 SKU 但没绑轴的 SPU 矩阵本来就是空的，两个计数分开才是真实的门禁情况。
    long blockedMatrix =
        sql.fetchOne(
                "select count(distinct k.spu_id) from sku k"
                    + " join spu_spec_axis b on b.tenant_id=k.tenant_id and b.spu_id=k.spu_id"
                    + " where btrim(k.variant_signature)<>''")
            .get(0, Long.class);
    var top =
        sql
            .fetch(
                "select p.id,p.code,p.name,count(distinct k.id) as sku_count from sku k"
                    + " join spu p on p.tenant_id=k.tenant_id and p.id=k.spu_id"
                    + " group by p.id,p.code,p.name order by sku_count desc,p.code limit 8")
            .map(
                r ->
                    new CombinationBlockage(
                        r.get("id", UUID.class),
                        r.get("code", String.class),
                        r.get("name", String.class),
                        r.get("sku_count", Long.class)))
            .stream()
            .toList();
    return new VariantCombinationOverview(
        axisSpus,
        reserved,
        drafts.get("all_drafts", Long.class),
        drafts.get("pending", Long.class),
        drafts.get("discarded", Long.class),
        blocked,
        blockedMatrix,
        top,
        blocked == 0
            ? "当前还没有任何已存在的 SKU 组合，草稿生成不会与历史 SKU 冲突。"
            : blocked
                + " 个 SPU 已有 SKU 组合（其中 "
                + blockedMatrix
                + " 个绑了规格轴，生成矩阵草稿时会逐条比对）："
                + "重复的组合会被拦下并列入冲突清单，比对按规格轴取值进行，解析不出取值的老数据退到签名文本比对。");
  }

  // ============================================================ 工具

  private void audit(AccessService.Access actor, UUID resourceId, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(),
        actor.tenantId(),
        actor.userId(),
        resourceId,
        action);
  }

  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  /**
   * 组合校验未通过时抛出，携带**完整结构化冲突清单**。
   *
   * <p>不用 400 也不压成一句提示语：运营要按清单逐条改，一句"存在重复组合"没法干活。 {@link ApiErrorHandler} 把它渲染成带 {@code conflicts}
   * 属性的 409 ProblemDetail。
   */
  public static final class CombinationConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient CombinationCheckResult result;

    public CombinationConflictException(CombinationCheckResult result) {
      super("组合校验未通过：存在 " + result.conflictCount() + " 条冲突，草稿未生成");
      this.result = result;
    }

    public CombinationCheckResult result() {
      return result;
    }
  }
}
