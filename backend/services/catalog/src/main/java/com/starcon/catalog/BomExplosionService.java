package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.5.2.2 BOM 展开：按 BOM 版本与需求量展开出需要采购/备货的组件清单。
 *
 * <p>蓝图原文：「职责：按 BOM 版本与需求量展开出需要采购/备货的组件清单与数量，
 * 支持多层展开和按仓库过滤。」「输出：多级展开的组件清单（含需求量、当前可用、在途、缺口）。」
 * 「下游 = 06 采购单（缺口转采购）、07 补货。**展开结果是建议不是事实**，
 * 实际采购由 06 生成单据后成立。」
 *
 * <p><b>与 3.5.2.1 的分工</b>（V25 文件头末节 + V26 第零节）：3.5.2.1 只做<b>单层</b>精确需求量（规则 R7），
 * 多级递归、子 BOM 判定、按仓库过滤、缺口计算全部归本类。本类**不复制** R6/R7：
 * 需求量一律转发 {@link BomRules#requiredQuantity}，损耗率一律是百分数（{@code 5} = 5%）。
 *
 * <p><b>本类钉死的三条语义</b>（V26 文件头第一/二/四节，与 {@link BomExplosionRules} 同口径）：
 *
 * <ol>
 *   <li><b>E1 逐层结转</b>：顶层行用成品数量，子层用<b>父行的缺口</b>而不是父行的需求量 ——
 *       半成品已有库存与在途时只展开缺的那部分。按需求量展开会为「根本不生产的那几件半成品」
 *       多算一层料，而多算出来的料会变成一张没人能解释为什么不买的采购单。
 *   <li><b>E2 缺口</b>：{@code max(0, required − available − inTransit)}。在途可抵扣是因为
 *       货权已归本租户（已经买了只是没到），抵扣它是为了不重复下单；下限截 0 是因为
 *       「多出来多少」对采购决策无用，富余量由 surplus 单独给出。
 *   <li><b>E4 循环拒绝 / 深度截断</b>：循环引用<b>直接 409 拒绝并逐个点名绕回路径</b>，
 *       不截断 —— 绕回自己意味着需求量在数学上无界，任何有限截断都是错的小数，
 *       而它会被当成建议下成真实的单。深度上限则<b>截断但显式标明</b>（status=TRUNCATED）。
 * </ol>
 *
 * <p><b>为什么是「逐层」而不是「递归」</b>（实现上的关键选择）：
 * 判定某个子装配要不要继续展开，需要先知道<b>它自己的缺口</b>，而缺口要等 warehouse
 * 返回可用与在途。所以按层推进、每层一次批量可用量查询 —— 若改成「先把树走完再取数」，
 * 就会对那些已被库存完全覆盖的半成品也递归下去，产生一串 0 需求的噪声行，
 * 甚至在**不该展开的区域**里撞上循环引用而误报。
 *
 * <p><b>租户隔离</b>：展开的<b>每一层</b>都是一条带 {@code tenant_id=?} 的显式 SQL，
 * 且 {@code bom} / {@code bom_version} / {@code bom_component} / {@code sku} 全部 FORCE RLS ——
 * 展开树里没有任何一层能绕过隔离。
 *
 * <p>权限口径：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}，沿用 3.5.2.1 的词表。
 */
@Service
public class BomExplosionService {
  /** 单页最大行数（运行记录列表页）。 */
  static final int MAX_PAGE_SIZE = 100;

  /** 展开明细的硬上限：递归失控或数据异常时的兜底，宁可报错也不要产出一棵核不动的树。 */
  static final int MAX_LINES = 2000;

  private final DSLContext sql;
  private final InventoryAvailabilityService availability;
  private final BomVersionService boms;

  public BomExplosionService(
      DSLContext sql, InventoryAvailabilityService availability, BomVersionService boms) {
    this.sql = sql;
    this.availability = availability;
    this.boms = boms;
  }

  /** 待展开的一层任务：某个 BOM 版本、按某个驱动量（成品数量或父行缺口）展开。 */
  private record Work(
      UUID versionId,
      String versionNo,
      String bomCode,
      BigDecimal driver,
      String pathPrefix,
      UUID parentLineId,
      List<UUID> ancestorSkus,
      List<String> ancestorCodes) {}

  /** 一层里已经算到「子 BOM 判定」但还没算缺口的行。 */
  private record Pending(
      UUID lineId,
      UUID parentLineId,
      String path,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID componentId,
      UUID sourceVersionId,
      String sourceVersionNo,
      String sourceBomCode,
      BigDecimal unitQuantity,
      BigDecimal scrapRate,
      BigDecimal required,
      boolean isSubAssembly,
      BomExplosionRules.BomCandidate subBom,
      boolean ambiguousSubBom,
      UUID subVersionId,
      String subVersionNo,
      String role,
      String note,
      List<UUID> ancestorSkus,
      List<String> ancestorCodes) {}

  // ================================================================ 展开

  /**
   * 展开一次并把结果冻结成 run + line 两张表。
   *
   * <p><b>为什么落库</b>：展开结果是「建议」，而建议会变成真实的采购单。
   * 06 的单子引用的是**这一次展开的 id**，不是「现在的 BOM × 现在的库存」——
   * 后者随时会变，单子发出去之后就无法解释「当时为什么按这个数下单」。
   * 冻结的是建议（规则、版本、仓库过滤、as_of 都在行里），**不是库存事实**：
   * 库存本身仍然是 warehouse 的，catalog 一份副本都没有。
   */
  public BomExplosionRun explode(
      AccessService.Access actor, String bearer, BomExplosionInput input) {
    UUID tenantId = actor.tenantId();
    int maxDepth = BomExplosionRules.resolveMaxDepth(input.maxDepth());
    check(BomExplosionRules.maxDepthProblem(input.maxDepth()) == null,
        BomExplosionRules.maxDepthProblem(input.maxDepth()));

    BigDecimal outputQuantity = input.outputQuantity();
    check(BomRules.outputQuantityProblem(outputQuantity) == null,
        BomRules.outputQuantityProblem(outputQuantity));

    // as_of 是**业务时间**：它决定子 BOM 取哪一版，必须与结果一起冻结，
    // 事后重算会得到另一个答案（子 BOM 可能已经换了版本）。
    Instant asOf =
        BomRules.parseInstant(input.asOf(), "展开基准时刻") == null
            ? Instant.now().truncatedTo(ChronoUnit.MILLIS)
            : BomRules.parseInstant(input.asOf(), "展开基准时刻");

    Root root = resolveRoot(tenantId, input, asOf);

    // 逐层推进：每层一次组件查询 + 一次子 BOM 判定 + 一次批量可用量。
    List<BomExplosionLine> lines = new ArrayList<>();
    boolean truncatedAny = false;
    int reachedDepth = 0;
    List<Work> current =
        List.of(
            new Work(
                root.versionId(),
                root.versionNo(),
                root.bomCode(),
                outputQuantity,
                "",
                null,
                List.of(root.outputSkuId()),
                List.of(root.outputSkuCode())));

    for (int depth = 1; depth <= maxDepth && !current.isEmpty(); depth++) {
      reachedDepth = depth;
      List<Pending> pending = buildLevel(tenantId, current, depth, root, asOf);
      if (pending.isEmpty()) break;
      Map<UUID, Stock> stock = applyAvailability(pending, bearer, input.warehouseId());
      List<Work> next = new ArrayList<>();
      for (Pending p : pending) {
        BigDecimal available = stock.get(p.lineId()).available();
        BigDecimal inTransit = stock.get(p.lineId()).inTransit();
        BigDecimal gap = BomExplosionRules.gap(p.required(), available, inTransit);
        boolean wantsExpand = p.isSubAssembly() && p.subVersionId() != null;
        if (wantsExpand && BomExplosionRules.covered(p.required(), available, inTransit)) {
          // 缺口为 0（已被库存与在途覆盖）就不往下展开：再展开只会产出一串 0 需求的噪声行。
          lines.add(
              line(depth, p, available, inTransit, false, true, false,
                  "可用 + 在途已覆盖本行需求，无需向下展开"));
          continue;
        }
        if (wantsExpand && depth >= maxDepth) {
          // 深度上限：截断但**显式标明**（静默截断是未完成）。
          truncatedAny = true;
          lines.add(
              line(depth, p, available, inTransit, false, false, true,
                  "已展开到第 " + maxDepth + " 层上限，这个组件仍是另一个 BOM 的成品（"
                      + p.subBom().code() + " 的 " + p.subVersionNo()
                      + "），它的下级没有展开 —— 这份清单不完整"));
          continue;
        }
        boolean expand = wantsExpand;
        if (expand) {
          next.add(
              new Work(
                  p.subVersionId(),
                  p.subVersionNo(),
                  p.subBom().code(),
                  gap,
                  p.path(),
                  p.lineId(),
                  append(p.ancestorSkus(), p.skuId()),
                  append(p.ancestorCodes(), p.skuCode())));
        }
        lines.add(
            line(depth, p, available, inTransit, expand, false, false,
                expand
                    ? "已按 " + p.subBom().code() + " 的 " + p.subVersionNo()
                        + " 继续展开（子级用量按本行缺口 " + plain(gap) + " 递推）"
                    : p.note()));
      }
      check(lines.size() <= MAX_LINES,
          "展开结果超过 " + MAX_LINES + " 行，已中止：这不是一棵人能核对的物料清单，"
              + "多半是某个组件的用量被填成了极小的数（于是每一层都要展开出几十个 SKU）。"
              + "请检查单位用量，或减小展开深度上限");
      current = next;
    }

    List<BomExplosionLine> ordered = sortByPath(lines);
    // 采购对象口径：只统计**不再向下展开**的行。中间层的量与它的下级不是相加关系
    // ——「缺 3 个半成品」与「下级缺 300 个面料」是两个采购对象，不是一个。
    List<BomExplosionLine> purchase = ordered.stream().filter(l -> !l.expanded()).toList();
    BigDecimal totalRequired = purchase.stream().map(BomExplosionLine::requiredQuantity)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalGap = purchase.stream().map(BomExplosionLine::gap)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    String status = truncatedAny ? "TRUNCATED" : "COMPLETED";
    UUID runId = Ids.next();
    OffsetDateTime asOfDb = utcOf(asOf);
    String note = BomRules.normalize(input.note());
    sql.execute(
        "insert into bom_explosion_run(id,tenant_id,bom_id,version_id,version_no,output_sku_id,"
            + "output_quantity,warehouse_id,as_of,max_depth,reached_depth,status,line_count,"
            + "purchase_line_count,total_required,total_gap,requested_by,note)"
            + " values(?,?,?,?,?,?,?,?,?::timestamptz,?,?,?,?,?,?,?,?,?)",
        runId, tenantId, root.bomId(), root.versionId(), root.versionNo(), root.outputSkuId(),
        outputQuantity, input.warehouseId(), asOfDb, maxDepth, reachedDepth, status,
        ordered.size(), purchase.size(), totalRequired, totalGap, actor.userId(), note);
    for (BomExplosionLine l : ordered) {
      sql.execute(
          "insert into bom_explosion_line(id,tenant_id,run_id,parent_line_id,depth,path,sku_id,"
              + "component_id,source_version_id,source_version_no,source_bom_code,unit_quantity,"
              + "scrap_rate,required_quantity,available,in_transit,gap,is_sub_assembly,sub_bom_id,"
              + "sub_bom_code,sub_version_id,sub_version_no,ambiguous_sub_bom,expanded,covered,"
              + "truncated,role,note)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          l.id(), tenantId, runId, l.parentLineId(), l.depth(), l.path(), l.skuId(),
          l.componentId(), l.sourceVersionId(), l.sourceVersionNo(), l.sourceBomCode(),
          l.unitQuantity(), l.scrapRate(), l.requiredQuantity(), l.available(), l.inTransit(),
          l.gap(), l.isSubAssembly(), l.subBomId(), l.subBomCode(), l.subVersionId(),
          l.subVersionNo(), l.ambiguousSubBom(), l.expanded(), l.covered(), l.truncated(),
          l.role(), l.note());
    }
    audit(actor, runId, "BomExploded");
    return view(tenantId, runId);
  }

  // ================================================================ 根节点解析

  private record Root(
      UUID bomId,
      String bomCode,
      UUID versionId,
      String versionNo,
      String versionName,
      String versionStatus,
      UUID outputSkuId,
      String outputSkuCode,
      String outputSkuName) {}

  /**
   * 解析展开的根版本。
   *
   * <p>给 {@code versionId} 就是「按这一版展开」（可含草稿：试算是纯计算不是事实）；
   * 给 {@code bomId} 就是「按 {@code asOf} 这一刻生效的那一版」（规则 R4 的时刻查询），
   * 与 06 采购建议、08 加工单取版本的形状一致。两个都给时以 versionId 为准（它更精确：
   * bomId 只是用来反查版本），但必须校验两者指向同一个 BOM ——
   * 不校验的话「展开 v2 却显示成另一个 BOM 的编码」会让人以为算错了。
   */
  private Root resolveRoot(UUID tenantId, BomExplosionInput input, Instant asOf) {
    check(input.versionId() != null || input.bomId() != null,
        "请指定要展开的 BOM 版本，或指定 BOM 让系统按展开基准时刻取生效版本："
            + "两个都不给的话无从判断「展开哪一份物料清单」");
    UUID versionId = input.versionId();
    if (versionId == null) {
      Record effective =
          sql.fetchOne(
              "select v.id from bom_version v where v.tenant_id=? and v.bom_id=? and v.status='PUBLISHED'"
                  + " and v.effective_from<=?::timestamptz"
                  + " and (v.effective_to is null or ?::timestamptz<v.effective_to)"
                  + " order by v.effective_from desc limit 1",
              tenantId, input.bomId(), utcOf(asOf), utcOf(asOf));
      if (effective == null) {
        throw notFound(
            "在 " + asOf + " 这一刻这个 BOM 没有生效版本：可能只有草稿，或已发布版本的区间都还没到。"
                + "只有已发布版本参与生效判定（规则 R3）—— 按草稿领料、据草稿下单都不是事实。"
                + "要按草稿试算请直接指定版本号");
      }
      versionId = effective.get("id", UUID.class);
    }
    Record row =
        sql.fetchOne(
            "select v.bom_id,v.version_no,v.name as version_name,v.status,"
                + "b.code as bom_code,b.output_sku_id,s.code as output_sku_code,"
                + "s.name as output_sku_name"
                + " from bom_version v"
                + " join bom b on b.tenant_id=v.tenant_id and b.id=v.bom_id"
                + " join sku s on s.tenant_id=b.tenant_id and s.id=b.output_sku_id"
                + " where v.tenant_id=? and v.id=?",
            tenantId, versionId);
    if (row == null) throw notFound("BOM 版本不存在");
    UUID bomId = row.get("bom_id", UUID.class);
    if (input.bomId() != null && !input.bomId().equals(bomId)) {
      throw bad(
          "指定的版本不属于指定的 BOM：版本「" + row.get("version_no", String.class)
              + "」属于 BOM " + row.get("bom_code", String.class)
              + "。两个都传时以版本为准，但它们必须指向同一个 BOM —— 否则页面上显示的 BOM 编码"
              + "与实际展开的那一版不是一回事，缺口的归属就无从判断");
    }
    return new Root(
        bomId,
        row.get("bom_code", String.class),
        versionId,
        row.get("version_no", String.class),
        row.get("version_name", String.class),
        row.get("status", String.class),
        row.get("output_sku_id", UUID.class),
        row.get("output_sku_code", String.class),
        row.get("output_sku_name", String.class));
  }

  // ================================================================ 一层的构建

  /**
   * 构建一层的待算行：读组件 → 判定子 BOM → 取子版本生效版 → 算出需求量。
   *
   * <p>每层最多 4 条 SQL（组件 / 子 BOM 候选 / 子版本生效 / 可用量），不随组件数增长 ——
   * 逐条查的话一层 50 个组件就是 150 次往返。
   */
  private List<Pending> buildLevel(
      UUID tenantId, List<Work> works, int depth, Root root, Instant asOf) {
    Set<UUID> versionIds = new LinkedHashSet<>();
    for (Work w : works) versionIds.add(w.versionId());
    var components =
        sql.fetch(
            "select c.version_id,c.id,c.sku_id,c.unit_quantity,c.scrap_rate,c.role,"
                + "s.code as sku_code,s.name as sku_name"
                + " from bom_component c join sku s on s.tenant_id=c.tenant_id and s.id=c.sku_id"
                + " where c.tenant_id=? and c.version_id in " + placeholders(versionIds.size())
                + " order by c.version_id,s.code,c.id",
            concat(List.of(tenantId), new ArrayList<>(versionIds)).toArray());

    // 组件 SKU → 是不是子装配 + 取哪个子 BOM（规则 E3 第 1、2 步）。
    // bom 表对 (tenant_id, output_sku_id) 没有唯一约束，所以这里可能返回多个候选。
    Map<UUID, List<BomExplosionRules.BomCandidate>> candidatesBySku = subBomCandidates(tenantId, components);
    Set<UUID> chosenBomIds = new LinkedHashSet<>();
    Map<UUID, BomExplosionRules.BomCandidate> chosenBySku = new LinkedHashMap<>();
    Map<UUID, Boolean> ambiguousBySku = new LinkedHashMap<>();
    for (Record r : components) {
      UUID skuId = r.get("sku_id", UUID.class);
      var candidates = candidatesBySku.getOrDefault(skuId, List.of());
      BomExplosionRules.pickSubBom(candidates)
          .ifPresent(
              picked -> {
                chosenBySku.put(skuId, picked.getKey());
                ambiguousBySku.put(skuId, picked.getValue());
                chosenBomIds.add(picked.getKey().id());
              });
    }
    // 子版本生效判定（规则 R4 + R3：只有已发布版本参与），一次问齐所有候选子 BOM。
    Map<UUID, Record> effectiveSub = effectiveSubVersions(tenantId, chosenBomIds, asOf);

    List<Pending> out = new ArrayList<>();
    for (Work work : works) {
      var rows = components.stream().filter(r -> r.get("version_id", UUID.class).equals(work.versionId()))
          .toList();
      int ordinal = 0;
      for (Record r : rows) {
        ordinal++;
        UUID skuId = r.get("sku_id", UUID.class);
        String skuCode = r.get("sku_code", String.class);
        String path = work.pathPrefix().isEmpty() ? String.valueOf(ordinal)
            : work.pathPrefix() + "." + ordinal;

        // 循环引用：当前路径上（成品 SKU 链）出现过的组件 SKU，说明 BOM 图绕回了自己。
        // 直接拒绝而不是截断 —— 见类注释 E4。
        if (work.ancestorSkus().contains(skuId)) {
          throw cycle(root, work, skuCode, skuId);
        }

        BigDecimal unitQuantity = r.get("unit_quantity", BigDecimal.class);
        BigDecimal scrapRate = r.get("scrap_rate", BigDecimal.class);
        // 规则 R7 逐层递推：驱动量在顶层是成品数量，在子层是父行的缺口。
        BigDecimal required = BomExplosionRules.requiredQuantity(work.driver(), unitQuantity, scrapRate);

        var candidate = chosenBySku.get(skuId);
        boolean isSub = candidate != null;
        boolean ambiguous = Boolean.TRUE.equals(ambiguousBySku.get(skuId));
        UUID subVersionId = null;
        String subVersionNo = null;
        String note;
        if (!isSub) {
          note = "叶子件：按采购/备货件处理";
        } else {
          var version = isSub ? effectiveSub.get(candidate.id()) : null;
          if (version == null) {
            // 取不到生效版本 → 按叶子件处理，并说清为什么（规则 E3 第 3 步）。
            note = "这个组件本身是另一个 BOM 的成品（" + candidate.code()
                + "），但在 " + asOf + " 这一刻没有生效版本，所以按采购件处理 —— "
                + "只有已发布版本参与生效判定（规则 R3），按草稿领料、据草稿下单都不是事实";
          } else {
            subVersionId = version.get("id", UUID.class);
            subVersionNo = version.get("version_no", String.class);
            note = ambiguous
                ? "这个成品 SKU 被 " + candidatesBySku.get(skuId).size()
                    + " 个 BOM 同时声明为产出（" + joinCodes(candidatesBySku.get(skuId))
                    + "），本次按编码序取第一个（" + candidate.code() + "）展开。"
                    + "请检查是不是有重复的 BOM 定义"
                : "子装配：按 " + candidate.code() + " 的 " + subVersionNo + " 继续展开";
          }
        }
        out.add(
            new Pending(
                Ids.next(), work.parentLineId(), path, skuId, skuCode,
                r.get("sku_name", String.class), r.get("id", UUID.class), work.versionId(),
                work.versionNo(), work.bomCode(), unitQuantity, scrapRate, required, isSub,
                candidate, ambiguous, subVersionId, subVersionNo, r.get("role", String.class),
                note, work.ancestorSkus(), work.ancestorCodes()));
      }
    }
    return out;
  }

  /** 一次问齐「哪些组件 SKU 是某个 BOM 的成品」以及候选子 BOM。 */
  private Map<UUID, List<BomExplosionRules.BomCandidate>> subBomCandidates(
      UUID tenantId, List<Record> components) {
    List<UUID> skuIds = components.stream().map(r -> r.get("sku_id", UUID.class)).distinct().toList();
    if (skuIds.isEmpty()) return Map.of();
    var rows =
        sql.fetch(
            "select output_sku_id,id,code from bom where tenant_id=? and output_sku_id in "
                + placeholders(skuIds.size())
                + " order by output_sku_id,code,id",
            concat(List.of(tenantId), skuIds).toArray());
    Map<UUID, List<BomExplosionRules.BomCandidate>> bySku = new LinkedHashMap<>();
    for (Record r : rows) {
      bySku
          .computeIfAbsent(r.get("output_sku_id", UUID.class), k -> new ArrayList<>())
          .add(new BomExplosionRules.BomCandidate(r.get("id", UUID.class), r.get("code", String.class)));
    }
    return bySku;
  }

  /**
   * 一次问齐「这些子 BOM 在 as_of 这一刻生效的是哪一版」。
   *
   * <p>半开区间判定与 V25 的触发器同一段 SQL 形状（{@code from <= at < (to is null or at < to)}），
   * 只取已发布版本（规则 R3）。规则 R2 保证同一 BOM 至多命中一行。
   */
  private Map<UUID, Record> effectiveSubVersions(UUID tenantId, Set<UUID> bomIds, Instant asOf) {
    if (bomIds.isEmpty()) return Map.of();
    List<UUID> ids = new ArrayList<>(bomIds);
    var rows =
        sql.fetch(
            "select bom_id,id,version_no from bom_version where tenant_id=? and status='PUBLISHED'"
                + " and bom_id in " + placeholders(ids.size())
                + " and effective_from<=?::timestamptz"
                + " and (effective_to is null or ?::timestamptz<effective_to)"
                + " order by bom_id,effective_from desc,id",
            concat(concat(List.of(tenantId), ids), List.of(utcOf(asOf), utcOf(asOf))).toArray());
    Map<UUID, Record> first = new LinkedHashMap<>();
    for (Record r : rows) {
      first.putIfAbsent(r.get("bom_id", UUID.class), r);
    }
    return first;
  }

  // ================================================================ 可用量与缺口

  /**
   * 取这一层的可用量与在途（规则 E2 的两个来源）。
   *
   * <p><b>跨服务</b>：可用与在途是 warehouse 的事实，catalog 既不 JOIN 也不复制，
   * 通过授权端点按 SKU 集合批量取。整层一次调用（不是每行一次）。
   *
   * <p>拿不到时**整个展开失败**而不是把可用当成 0：把「查不到」当成 0 会让每个组件
   * 都显示缺货，进而生成一张全是虚假缺口的采购单。
   *
   * <p>返回按行 id 索引的不可变结果 —— <b>不做成字段</b>：本类是 Spring 单例，
   * 把一次展开的中间态挂在字段上会让并发请求互相覆盖，甚至把 A 租户的可用量
   * 算进 B 租户的缺口。
   */
  private Map<UUID, Stock> applyAvailability(
      List<Pending> pending, String bearer, UUID warehouseId) {
    List<UUID> skuIds = pending.stream().map(Pending::skuId).distinct().toList();
    if (skuIds.isEmpty()) return Map.of();
    InventoryAvailabilityPort.AvailabilityResult result =
        availability.availability(bearer, skuIds, warehouseId);
    Map<UUID, InventoryAvailabilityPort.AvailabilityRow> bySku = new LinkedHashMap<>();
    for (InventoryAvailabilityPort.AvailabilityRow row : result.rows()) {
      bySku.put(row.skuId(), row);
    }
    Map<UUID, Stock> out = new LinkedHashMap<>();
    for (Pending p : pending) {
      var row = bySku.get(p.skuId());
      // warehouse 端保证「请求的每一个 SKU 都回一行、没有库存行的给 0」。
      // 这里仍显式兜一层：缺席就当 0，而不是抛 NPE 让整次展开 500。
      BigDecimal available = row == null ? null : row.available();
      BigDecimal inTransit = row == null ? null : row.inTransit();
      if (available == null) available = BigDecimal.ZERO;
      if (inTransit == null) inTransit = BigDecimal.ZERO;
      if (available.signum() < 0) available = BigDecimal.ZERO;
      if (inTransit.signum() < 0) inTransit = BigDecimal.ZERO;
      out.put(p.lineId(), new Stock(available, inTransit));
    }
    return out;
  }

  /** 某一行的可用与在途。 */
  private record Stock(BigDecimal available, BigDecimal inTransit) {}

  private BomExplosionLine line(
      int depth,
      Pending p,
      BigDecimal available,
      BigDecimal inTransit,
      boolean expanded,
      boolean forceCovered,
      boolean truncated,
      String note) {
    BigDecimal gap = BomExplosionRules.gap(p.required(), available, inTransit);
    boolean isCovered = forceCovered || BomExplosionRules.covered(p.required(), available, inTransit);
    return new BomExplosionLine(
        p.lineId(), p.parentLineId(), depth, p.path(), p.skuId(), p.skuCode(),
        p.skuName(), p.componentId(), p.sourceVersionId(), p.sourceVersionNo(), p.sourceBomCode(),
        p.unitQuantity(), p.scrapRate(), p.required(), available, inTransit, gap,
        BomExplosionRules.surplus(p.required(), available, inTransit), p.isSubAssembly(),
        p.subBom() == null ? null : p.subBom().id(),
        p.subBom() == null ? null : p.subBom().code(), p.subVersionId(), p.subVersionNo(),
        p.ambiguousSubBom(), expanded, isCovered, truncated, p.role(), note);
  }

  // ================================================================ 循环引用

  /**
   * 循环引用：整个展开被拒绝（409），错误信息逐个点名绕回路径。
   *
   * <p>判定键是<b>当前路径上出现过的成品 SKU</b>：子 BOM 由 {@code output_sku_id} 唯一确定，
   * 所以「同一个 SKU 在自己的祖先链上再次出现」等价于「BOM 图上绕回了自己」。
   *
   * <p><b>为什么拒绝而不是截断</b>：绕回自己意味着这批料的需求量在数学上无界，
   * 任何有限的截断结果都是一个错的小数，而它会被当成采购建议下成真实的单。
   * 静默死循环更是直接挂住服务 —— 两者都比报错坏。
   *
   * <p><b>这一层约束数据库做不到</b>：bom_component 只引用 SKU 不引用 BOM（V25 的刻意选择），
   * 只有真正展开时才知道绕回，所以纵深防线在服务层。
   */
  private static ResponseStatusException cycle(Root root, Work work, String skuCode, UUID skuId) {
    List<String> chain = new ArrayList<>(work.ancestorCodes());
    chain.add(skuCode);
    List<UUID> ids = new ArrayList<>(work.ancestorSkus());
    ids.add(skuId);
    return conflict(
        "BOM 循环引用，已拒绝展开（未生成任何结果）："
            + String.join(" → ", chain)
            + "。路径 "
            + (work.pathPrefix().isEmpty() ? "（成品版本的直接组件）" : work.pathPrefix())
            + " 上的组件「"
            + skuCode
            + "」又是祖先链上的某个成品，BOM 图绕回了自己 —— 这批料的需求量在数学上无界，"
            + "任何有限的截断结果都是错的小数，而它会被当成采购建议下成真实的单。"
            + "请改掉这条组件引用（BOM " + root.bomCode() + " 的 " + root.versionNo()
            + "，祖先链：" + String.join(" → ", work.ancestorCodes())
            + "）。注意：这条约束在数据库层做不到，因为组件行只引用 SKU 不引用 BOM，"
            + "只有真正展开时才知道绕回");
  }

  // ================================================================ 读回

  /** 读回一次展开（含完整展开树）。 */
  public BomExplosionRun run(UUID id) {
    return view(currentTenantId(), id);
  }

  /** 展开运行记录列表：分页 + 按 BOM / 成品 SKU 过滤。只带摘要，不带 lines。 */
  public BomExplosionRunPage runs(BomExplosionRunQuery query) {
    UUID tenantId = currentTenantId();
    int page = Math.max(0, query.page());
    int size = query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
    StringBuilder where = new StringBuilder(" where r.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenantId);
    if (query.bomId() != null) {
      where.append(" and r.bom_id=?");
      binds.add(query.bomId());
    }
    if (query.skuId() != null) {
      where.append(" and r.output_sku_id=?");
      binds.add(query.skuId());
    }
    String from =
        " from bom_explosion_run r join bom b on b.tenant_id=r.tenant_id and b.id=r.bom_id"
            + " join sku s on s.tenant_id=r.tenant_id and s.id=r.output_sku_id";
    long total = sql.fetchOne("select count(*)" + from + where, binds.toArray()).get(0, Long.class);
    if (total == 0) return new BomExplosionRunPage(List.of(), page, size, 0, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(size);
    pageBinds.add((long) page * size);
    var rows =
        sql.fetch(
            "select r.id,r.bom_id,b.code as bom_code,r.version_id,r.version_no,r.output_sku_id,"
                + "s.code as output_sku_code,r.output_quantity,r.warehouse_id,r.as_of,r.status,"
                + "r.reached_depth,r.line_count,r.purchase_line_count,r.total_gap,r.created_at"
                + from + where
                + " order by r.created_at desc,r.id desc limit ? offset ?",
            pageBinds.toArray());
    List<BomExplosionRunRow> items = new ArrayList<>(rows.size());
    for (Record r : rows) {
      String status = r.get("status", String.class);
      items.add(
          new BomExplosionRunRow(
              r.get("id", UUID.class), r.get("bom_id", UUID.class), r.get("bom_code", String.class),
              r.get("version_id", UUID.class), r.get("version_no", String.class),
              r.get("output_sku_id", UUID.class), r.get("output_sku_code", String.class),
              r.get("output_quantity", BigDecimal.class), r.get("warehouse_id", UUID.class),
              r.get("as_of", OffsetDateTime.class) == null ? null
                  : r.get("as_of", OffsetDateTime.class).toInstant().toString(),
              status, "TRUNCATED".equals(status), r.get("reached_depth", Integer.class),
              r.get("line_count", Integer.class), r.get("purchase_line_count", Integer.class),
              r.get("total_gap", BigDecimal.class),
              r.get("created_at", OffsetDateTime.class).toInstant().toString()));
    }
    return new BomExplosionRunPage(items, page, size, total, (int) Math.ceil(total / (double) size));
  }

  private BomExplosionRun view(UUID tenantId, UUID id) {
    Record r =
        sql.fetchOne(
            "select r.id,r.bom_id,b.code as bom_code,r.version_id,r.version_no,v.status as version_status,"
                + "v.name as version_name,r.output_sku_id,s.code as output_sku_code,"
                + "s.name as output_sku_name,r.output_quantity,r.warehouse_id,r.as_of,r.max_depth,"
                + "r.reached_depth,r.status,r.line_count,r.purchase_line_count,r.total_required,"
                + "r.total_gap,r.requested_by,r.note"
                + " from bom_explosion_run r"
                + " join bom b on b.tenant_id=r.tenant_id and b.id=r.bom_id"
                + " join bom_version v on v.tenant_id=r.tenant_id and v.id=r.version_id"
                + " join sku s on s.tenant_id=r.tenant_id and s.id=r.output_sku_id"
                + " where r.tenant_id=? and r.id=?",
            tenantId, id);
    if (r == null) throw notFound("展开记录不存在");
    var lineRows =
        sql.fetch(
            "select l.id,l.parent_line_id,l.depth,l.path,l.sku_id,s.code as sku_code,"
                + "s.name as sku_name,l.component_id,l.source_version_id,l.source_version_no,"
                + "l.source_bom_code,l.unit_quantity,l.scrap_rate,l.required_quantity,l.available,"
                + "l.in_transit,l.gap,l.is_sub_assembly,l.sub_bom_id,l.sub_bom_code,"
                + "l.sub_version_id,l.sub_version_no,l.ambiguous_sub_bom,l.expanded,l.covered,"
                + "l.truncated,l.role,l.note"
                + " from bom_explosion_line l join sku s on s.tenant_id=l.tenant_id and s.id=l.sku_id"
                + " where l.tenant_id=? and l.run_id=? order by l.path",
            tenantId, id);
    List<BomExplosionLine> lines = new ArrayList<>(lineRows.size());
    for (Record x : lineRows) {
      BigDecimal required = x.get("required_quantity", BigDecimal.class);
      BigDecimal available = x.get("available", BigDecimal.class);
      BigDecimal inTransit = x.get("in_transit", BigDecimal.class);
      lines.add(
          new BomExplosionLine(
              x.get("id", UUID.class), x.get("parent_line_id", UUID.class),
              x.get("depth", Integer.class), x.get("path", String.class),
              x.get("sku_id", UUID.class), x.get("sku_code", String.class),
              x.get("sku_name", String.class), x.get("component_id", UUID.class),
              x.get("source_version_id", UUID.class), x.get("source_version_no", String.class),
              x.get("source_bom_code", String.class), x.get("unit_quantity", BigDecimal.class),
              x.get("scrap_rate", BigDecimal.class), required, available, inTransit,
              x.get("gap", BigDecimal.class),
              BomExplosionRules.surplus(required, available, inTransit),
              Boolean.TRUE.equals(x.get("is_sub_assembly", Boolean.class)),
              x.get("sub_bom_id", UUID.class), x.get("sub_bom_code", String.class),
              x.get("sub_version_id", UUID.class), x.get("sub_version_no", String.class),
              Boolean.TRUE.equals(x.get("ambiguous_sub_bom", Boolean.class)),
              Boolean.TRUE.equals(x.get("expanded", Boolean.class)),
              Boolean.TRUE.equals(x.get("covered", Boolean.class)),
              Boolean.TRUE.equals(x.get("truncated", Boolean.class)), x.get("role", String.class),
              x.get("note", String.class)));
    }
    String status = r.get("status", String.class);
    boolean truncated = "TRUNCATED".equals(status);
    OffsetDateTime asOf = r.get("as_of", OffsetDateTime.class);
    List<BomExplosionLine> sorted = sortByPath(lines);
    return new BomExplosionRun(
        r.get("id", UUID.class), r.get("bom_id", UUID.class), r.get("bom_code", String.class),
        r.get("version_id", UUID.class), r.get("version_no", String.class),
        r.get("version_status", String.class), r.get("version_name", String.class),
        r.get("output_sku_id", UUID.class), r.get("output_sku_code", String.class),
        r.get("output_sku_name", String.class), r.get("output_quantity", BigDecimal.class),
        r.get("warehouse_id", UUID.class), asOf == null ? null : asOf.toInstant().toString(),
        r.get("max_depth", Integer.class), r.get("reached_depth", Integer.class), status,
        truncated, r.get("line_count", Integer.class),
        r.get("purchase_line_count", Integer.class), r.get("total_required", BigDecimal.class),
        r.get("total_gap", BigDecimal.class), r.get("requested_by", UUID.class),
        r.get("note", String.class), sorted,
        calculationNote(r, asOf, truncated, status));
  }

  private static String calculationNote(Record r, OffsetDateTime asOf, boolean truncated, String status) {
    return "需求量按「单位用量 × 驱动量 × (1 + 损耗率/100)」逐层递推，保留 6 位小数"
        + "**不取整**（规则 R7，损耗率是百分数：5 = 5%）。"
        + "顶层行的驱动量是成品数量，子层行的驱动量是**父行的缺口** —— "
        + "半成品已有库存与在途时只展开缺的那部分，否则会为不生产的半成品多算一层料。"
        + "缺口 = max(0, 需求量 − 可用 − 在途)：在途货的货权已归本租户（已经买了只是没到），"
        + "抵扣它是为了不把同一批料重复下单；"
        + (r.get("warehouse_id", UUID.class) == null
            ? "本次不限仓库（在途按全租户合计）。"
            : "本次按仓库过滤：在途取的是发往该仓的货，发出去只会让来源仓变少。")
        + "可用与在途来自 warehouse 服务的授权端点，catalog 不存任何库存副本。"
        + "展开基准时刻 " + (asOf == null ? "—" : asOf.toInstant())
        + " 决定了每一层子 BOM 取哪一版（只有已发布版本参与，规则 R3）。"
        + "合计只统计**不再向下展开**的行：中间层的量与它的下级不是相加关系。"
        + (truncated
            ? "**本次结果不完整**：命中了展开深度上限，还有下级没有展开（状态 " + status + "）。"
            : "");
  }

  // ================================================================ 辅助

  private static String joinCodes(List<BomExplosionRules.BomCandidate> candidates) {
    List<String> codes = new ArrayList<>();
    for (var c : candidates) codes.add(c.code());
    return String.join("、", codes);
  }

  private static <T> List<T> append(List<T> list, T value) {
    List<T> out = new ArrayList<>(list);
    out.add(value);
    return out;
  }

  /**
   * 按物化路径排序。
   *
   * <p>不能直接按字符串排：{@code "10"} 在字符串序里排在 {@code "2"} 前面，
   * 那样展开树在页面上会变成 1, 10, 11, 2…，父子顺序全乱。
   * 逐段按数值比较才是路径的真正顺序。
   */
  private static final java.util.Comparator<List<Integer>> PATH_KEY_ORDER =
      BomExplosionService::comparePathKeys;

  private static List<BomExplosionLine> sortByPath(List<BomExplosionLine> lines) {
    List<BomExplosionLine> sorted = new ArrayList<>(lines);
    sorted.sort(java.util.Comparator.comparing(BomExplosionService::pathKey, PATH_KEY_ORDER));
    return sorted;
  }

  /** 逐段按数值比：{@code List} 没有 compareTo，所以自己写逐段比较。 */
  private static int comparePathKeys(List<Integer> left, List<Integer> right) {
    int shared = Math.min(left.size(), right.size());
    for (int i = 0; i < shared; i++) {
      int c = Integer.compare(left.get(i), right.get(i));
      if (c != 0) return c;
    }
    return Integer.compare(left.size(), right.size());
  }

  private static List<Integer> pathKey(BomExplosionLine line) {
    List<Integer> key = new ArrayList<>();
    for (String segment : line.path().split("\\.")) {
      try {
        key.add(Integer.parseInt(segment));
      } catch (NumberFormatException e) {
        // 不该发生（path 由本服务生成）。放一个超大值让它排到最后而不是抛异常中断整次展开。
        key.add(Integer.MAX_VALUE);
      }
    }
    return key;
  }

  private static String placeholders(int n) {
    return "(" + String.join(",", java.util.Collections.nCopies(n, "?::uuid")) + ")";
  }

  private static List<Object> concat(List<Object> head, List<?> tail) {
    List<Object> all = new ArrayList<>(head);
    all.addAll(tail);
    return all;
  }

  private static String plain(BigDecimal value) {
    return value == null ? "0" : value.stripTrailingZeros().toPlainString();
  }

  private static void check(boolean ok, String message) {
    if (!ok) throw MediaRules.bad(message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static OffsetDateTime utcOf(Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  UUID currentTenantId() {
    return boms.currentTenantId();
  }

  void audit(AccessService.Access actor, UUID resourceId, String action) {
    boms.audit(actor, resourceId, action);
  }
}
