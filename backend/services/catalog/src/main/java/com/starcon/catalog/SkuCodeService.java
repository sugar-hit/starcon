package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import com.starcon.tenancy.Ids;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.1.2.3 编码与条码：内部 SKU 编码（手工输入 / 按规则自动生成 / 批量补齐）与外部条码。
 *
 * <p>三条纪律贯穿本类：
 *
 * <ol>
 *   <li><b>编码唯一性是命门</b>。展开出来的编码在写入前逐条查重，冲突的行进拒绝清单并带原因，
 *       而不是让 DB 唯一索引抛一个 500。批量操作里"改了一部分、剩下一部分被拒"是正常结果，
 *       拒绝清单就是交付物。
 *   <li><b>不覆盖手工编码</b>。规则只负责「尚无编码」的 SKU；已存在的编码要么跳过，
 *       要么由操作人显式走手工输入改写。批量补齐把手工编码当既有事实，不当垃圾。
 *   <li><b>本模块不碰 seller_sku</b>。店铺的 seller_sku 是平台侧属性，映射由 04 刊登维护，
 *       混进内部编码会让"内部唯一"这条约束被平台编号污染。
 * </ol>
 *
 * <p>序号分配用 {@code sku_code_rule_seq} 一行的原子自增完成（{@code ON CONFLICT DO UPDATE ...
 * RETURNING}），并发的两批生成不会拿到同一段号。
 */
@Service
public class SkuCodeService {
  /** 批量扫描「尚无编码」的 SKU 时的上限，防止一条规则扫穿整个租户。 */
  private static final int MAX_BACKFILL_SCAN = 5000;

  /** 留痕回读的条数。 */
  private static final int HISTORY_LIMIT = 50;

  private static final int RECENT_BATCHES = 10;

  private final DSLContext sql;
  private final AccessService access;

  public SkuCodeService(DSLContext sql, AccessService access) {
    this.sql = sql;
    this.access = access;
  }

  // ============================================================ 编码规则

  /** 规则列表。停用的规则也在列里——否则运营会以为规则凭空消失了。 */
  public List<SkuCodeRule> rules() {
    var counters =
        sql.fetch("select rule_id,next_value from sku_code_rule_seq")
            .stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    r -> r.get("rule_id", UUID.class), r -> r.get("next_value", Long.class)));
    return sql.fetch(
            "select r.id,r.code,r.name,r.description,r.scope,r.prefix,r.suffix,r.seq_width,"
                + "r.start_from,r.enabled,r.version,r.updated_at,r.spu_id,p.code as spu_code"
                + " from sku_code_rule r"
                + " left join spu p on p.tenant_id=r.tenant_id and p.id=r.spu_id"
                + " order by r.enabled desc,r.code,r.id")
        .map(r -> toRule(r, counters.get(r.get("id", UUID.class))))
        .stream()
        .toList();
  }

  private SkuCodeRule toRule(Record r, Long next) {
    String prefix = r.get("prefix", String.class);
    String suffix = r.get("suffix", String.class);
    int width = r.get("seq_width", Integer.class);
    long startFrom = r.get("start_from", Long.class);
    long nextValue = next == null ? startFrom : next;
    String sample = SkuCodePattern.render(prefix, nextValue, width, suffix);
    String scope = r.get("scope", String.class);
    return new SkuCodeRule(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("description", String.class),
        scope,
        "SPU".equals(scope) ? "限定商品族" : "全部 SKU",
        r.get("spu_id", UUID.class),
        r.get("spu_code", String.class),
        prefix,
        suffix,
        width,
        startFrom,
        nextValue,
        SkuCodePattern.lengthProblem(prefix, width, suffix) == null ? sample : null,
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        r.get("version", Long.class),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  /**
   * 新建或更新编码规则（id 为空即新建）。
   *
   * <p>前后缀的字符集与展开长度都在**保存时**校验：等批量生成 500 个 SKU 才发现编码超长，
   * 那时已经写进去多少无从追溯。
   */
  public SkuCodeRuleSaved saveRule(AccessService.Access actor, UUID id, SkuCodeRuleInput input) {
    String code = CatalogService.code(input.code());
    CatalogService.check(!input.name().trim().isEmpty(), "规则名称不能为空");
    String scope = input.scope().trim().toUpperCase(Locale.ROOT);
    CatalogService.check(Set.of("ALL", "SPU").contains(scope), "适用范围只能是 ALL（全部 SKU）或 SPU（限定商品族）");
    UUID spuId = input.spuId();
    if ("SPU".equals(scope)) {
      CatalogService.check(spuId != null, "限定商品族的规则必须指定 SPU");
      if (sql.fetchOne("select id from spu where id=?", spuId) == null) throw CatalogService.missing();
    } else spuId = null;
    CatalogService.check(input.seqWidth() >= 1 && input.seqWidth() <= 12, "序号宽度需在 1~12 之间");
    CatalogService.check(input.startFrom() >= 1, "起始序号必须大于等于 1");

    String prefix = SkuCodePattern.normalizePart(input.prefix(), "编码前缀");
    String suffix = SkuCodePattern.normalizePart(input.suffix(), "编码后缀");
    String tooLong = SkuCodePattern.lengthProblem(prefix, input.seqWidth(), suffix);
    CatalogService.check(tooLong == null, tooLong);

    if (id == null) {
      CatalogService.check(
          sql.fetchOne("select id from sku_code_rule where code=?", code) == null, "编码规则已存在：" + code);
      CatalogService.check(scopeFree(spuId, null), scopeConflict(spuId));
      UUID newId = Ids.next();
      try {
        sql.execute(
            "insert into sku_code_rule(id,tenant_id,code,name,description,scope,spu_id,prefix,suffix,"
                + "seq_width,start_from,enabled) values(?,?,?,?,?,?,?,?,?,?,?,?)",
            newId,
            actor.tenantId(),
            code,
            input.name().trim(),
            input.description(),
            scope,
            spuId,
            prefix,
            suffix,
            input.seqWidth(),
            input.startFrom(),
            input.enabled());
      } catch (DataAccessException e) {
        // 唯一索引是并发下的最终防线：两个请求同时通过上面的存在性检查时由它兜住。
        throw conflict("编码规则已被并发创建或占用，请刷新后重试");
      }
      sql.execute(
          "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
          Ids.next(),
          actor.tenantId(),
          actor.userId(),
          newId,
          "SkuCodeRuleSaved");
      return new SkuCodeRuleSaved(
          newId, 0, SkuCodePattern.render(prefix, input.startFrom(), input.seqWidth(), suffix));
    }

    if (!scopeFree(spuId, id)) throw conflict(scopeConflict(spuId));
    int updated;
    try {
      updated =
          sql.execute(
              "update sku_code_rule set code=?,name=?,description=?,scope=?,spu_id=?,prefix=?,suffix=?,"
                  + "seq_width=?,start_from=?,enabled=?,version=version+1,updated_at=now()"
                  + " where id=? and version=?",
              code,
              input.name().trim(),
              input.description(),
              scope,
              spuId,
              prefix,
              suffix,
              input.seqWidth(),
              input.startFrom(),
              input.enabled(),
              id,
              input.version());
    } catch (DataAccessException e) {
      throw conflict("编码规则已存在或适用范围被占用，请刷新后重试");
    }
    if (updated != 1)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "编码规则已被他人修改，请刷新后重试");
    long version =
        sql.fetchOne("select version from sku_code_rule where id=?", id).get("version", Long.class);
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(),
        actor.tenantId(),
        actor.userId(),
        id,
        "SkuCodeRuleSaved");
    var row = sql.fetchOne("select next_value from sku_code_rule_seq where rule_id=?", id);
    long next = row == null ? input.startFrom() : row.get("next_value", Long.class);
    return new SkuCodeRuleSaved(
        id, version, SkuCodePattern.render(prefix, next, input.seqWidth(), suffix));
  }

  /** 同一 (scope, spu) 下只能有一条规则，避免同一 SPU 命中两条规则导致编号不可预期。 */
  private boolean scopeFree(UUID spuId, UUID excludeId) {
    if (spuId == null) return true;
    var row = sql.fetchOne("select id from sku_code_rule where scope='SPU' and spu_id=?", spuId);
    return row == null || row.get("id", UUID.class).equals(excludeId);
  }

  private static String scopeConflict(UUID spuId) {
    return "该商品族已有一条限定规则，一个商品族只能有一条规则，否则生成的编码无法预期";
  }

  // ============================================================ 概览

  /**
   * 概览：编码覆盖率、条码 scheme 分布、最近批次。
   *
   * <p>{@code uncodedSkus} 数的是"没有编码的 SKU"。内部编码存在 sku.code 上，
   * 所以空串即未编码——这是唯一一处"空"有业务含义的地方，页面上必须与"编码为 0"区分开。
   */
  public SkuCodeOverview overview() {
    var totals =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where btrim(s.code)='') as uncoded"
                + " from sku s");
    long total = totals.get("total", Long.class);
    long uncoded = totals.get("uncoded", Long.class);
    var rules =
        sql.fetchOne(
            "select count(*) as all_rules,count(*) filter (where enabled) as enabled_rules"
                + " from sku_code_rule");
    var schemeCounts =
        sql.fetch(
                "select scheme,count(*) as n from sku_identifier group by scheme order by count(*) desc,scheme")
            .map(
                r ->
                    new SkuIdentifierSchemeCount(
                        r.get("scheme", String.class),
                        BarcodeScheme.label(r.get("scheme", String.class)),
                        r.get("n", Long.class)))
            .stream()
            .toList();
    long identifierCount = schemeCounts.stream().mapToLong(SkuIdentifierSchemeCount::count).sum();
    var recent =
        sql.fetch(
                "select id,action,status,requested_count,applied_count,skipped_count,created_at"
                    + " from sku_code_batch order by created_at desc,id desc limit " + RECENT_BATCHES)
            .map(
                r ->
                    new SkuCodeBatchSummary(
                        r.get("id", UUID.class),
                        r.get("action", String.class),
                        r.get("status", String.class),
                        r.get("requested_count", Integer.class),
                        r.get("applied_count", Integer.class),
                        r.get("skipped_count", Integer.class),
                        rejected(r.get("id", UUID.class)),
                        instant(r.get("created_at", OffsetDateTime.class))))
            .stream()
            .toList();
    return new SkuCodeOverview(
        total,
        uncoded,
        identifierCount,
        rules.get("all_rules", Long.class),
        rules.get("enabled_rules", Long.class),
        schemeCounts,
        recent,
        "店铺的 seller_sku 是平台侧属性，由 04 刊登维护与映射，不属于内部编码，也不会出现在这里。"
            + "外部商品条码（EAN/UPC/GTIN）才是本模块管理的对象。");
  }

  private int rejected(UUID batchId) {
    Integer n =
        sql.fetchOne(
                "select count(*) from sku_code_batch_item where batch_id=? and outcome='REJECTED'",
                batchId)
            .get(0, Integer.class);
    return n == null ? 0 : n;
  }

  // ============================================================ 单 SKU 面板

  /**
   * 单个 SKU 的编码 + 条码 + 编码变更留痕。
   *
   * <p>{@code internalSource} / {@code internalRuleCode} 取自**最近一次**编码分配留痕：
   * 运营要能一眼看出当前编码是手工敲的还是哪条规则生成的，否则看到一批不合规编码无从下手。
   */
  public SkuCodePanel panel(UUID skuId) {
    var row =
        sql.fetchOne(
            "select s.id,s.code,s.name,s.version,p.code as spu_code from sku s"
                + " join spu p on p.tenant_id=s.tenant_id and p.id=s.spu_id where s.id=?",
            skuId);
    if (row == null) throw CatalogService.missing();
    var history = assignments(skuId);
    var latest = history.isEmpty() ? null : history.get(0);
    return new SkuCodePanel(
        row.get("id", UUID.class),
        row.get("code", String.class),
        row.get("name", String.class),
        row.get("spu_code", String.class),
        row.get("code", String.class),
        latest == null ? null : latest.source(),
        latest == null ? null : latest.ruleCode(),
        row.get("version", Long.class),
        identifiers(skuId),
        history);
  }

  private List<SkuIdentifier> identifiers(UUID skuId) {
    return sql.fetch(
            "select id,scheme,value,is_primary,source,occurred_at,created_at from sku_identifier"
                + " where sku_id=? order by scheme,is_primary desc,created_at desc,id desc",
            skuId)
        .map(
            r ->
                new SkuIdentifier(
                    r.get("id", UUID.class),
                    r.get("scheme", String.class),
                    BarcodeScheme.label(r.get("scheme", String.class)),
                    r.get("value", String.class),
                    Boolean.TRUE.equals(r.get("is_primary", Boolean.class)),
                    r.get("source", String.class),
                    instant(r.get("occurred_at", OffsetDateTime.class)),
                    instant(r.get("created_at", OffsetDateTime.class))))
        .stream()
        .toList();
  }

  private List<SkuCodeAssignment> assignments(UUID skuId) {
    return sql.fetch(
            "select a.id,a.from_code,a.to_code,a.source,a.actor_id,a.occurred_at,a.created_at,r.code as rule_code"
                + " from sku_code_assignment a"
                + " left join sku_code_rule r on r.tenant_id=a.tenant_id and r.id=a.rule_id"
                + " where a.sku_id=? order by a.occurred_at desc,a.created_at desc,a.id desc limit "
                + HISTORY_LIMIT,
            skuId)
        .map(
            r ->
                new SkuCodeAssignment(
                    r.get("id", UUID.class),
                    r.get("from_code", String.class),
                    r.get("to_code", String.class),
                    r.get("source", String.class),
                    sourceLabel(r.get("source", String.class)),
                    r.get("rule_code", String.class),
                    r.get("actor_id", UUID.class),
                    instant(r.get("occurred_at", OffsetDateTime.class)),
                    instant(r.get("created_at", OffsetDateTime.class))))
        .stream()
        .toList();
  }

  private static String sourceLabel(String source) {
    return switch (source) {
      case "MANUAL" -> "手工输入";
      case "RULE" -> "规则生成";
      case "BACKFILL" -> "批量补齐";
      default -> source;
    };
  }

  // ============================================================ 手工输入编码

  /**
   * 手工设置内部编码（覆盖现有编码）。
   *
   * <p>这是唯一允许覆盖既有编码的路径：批量补齐与规则生成都只填「尚无编码」的 SKU。
   * 覆盖会留痕（from_code → to_code）并发布事件，下游 04 刊登据此同步平台编码。
   */
  public SkuCodeSaved setCode(AccessService.Access actor, UUID skuId, SkuCodeInput input) {
    var row = sql.fetchOne("select version,code from sku where id=?", skuId);
    if (row == null) throw CatalogService.missing();
    String code = CatalogService.code(input.code());
    String before = row.get("code", String.class);
    if (!code.equals(before)) {
      var clash = sql.fetchOne("select id from sku where code=?", code);
      CatalogService.check(
          clash == null, "编码 " + code + " 已被其他 SKU 使用，内部编码在租户内必须唯一");
    }
    int updated =
        sql.execute(
            "update sku set code=?,version=version+1,updated_at=now() where id=? and version=?",
            code,
            skuId,
            input.version());
    if (updated != 1)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "SKU 已被他人修改，请刷新后重试");
    if (!code.equals(before))
      sql.execute(
          "insert into sku_code_assignment(id,tenant_id,sku_id,from_code,to_code,source,actor_id,occurred_at)"
              + " values(?,?,?,?,?,'MANUAL',?,?::timestamptz)",
          Ids.next(),
          actor.tenantId(),
          skuId,
          before,
          code,
          actor.userId(),
          utcNow());
    long version = sql.fetchOne("select version from sku where id=?", skuId).get("version", Long.class);
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(),
        actor.tenantId(),
        actor.userId(),
        skuId,
        "SkuCodeManualSaved");
    return new SkuCodeSaved(skuId, before, code, version);
  }

  // ============================================================ 外部条码

  /**
   * 录入一个外部条码（手工或扫码）。
   *
   * <p>scheme+value 全租户唯一：这条唯一性是 08 拣货扫码能唯一确定一个 SKU 的前提。
   * 单条录入的冲突直接 409 并说明冲突方是谁；批量导入里的同样冲突进拒绝清单。
   */
  public SkuIdentifierSaved addIdentifier(
      AccessService.Access actor, UUID skuId, SkuIdentifierInput input) {
    if (sql.fetchOne("select id from sku where id=?", skuId) == null) throw CatalogService.missing();
    String problem = BarcodeScheme.problem(input.scheme(), input.value());
    if (problem != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problem);
    String scheme = BarcodeScheme.normalize(input.scheme());
    String value = input.value().trim();
    String source = input.source() == null || input.source().isBlank() ? "MANUAL" : input.source().trim().toUpperCase(Locale.ROOT);
    CatalogService.check(Set.of("MANUAL", "SCAN").contains(source), "条码来源只能是 MANUAL 或 SCAN");

    var clash = sql.fetchOne("select sku_id from sku_identifier where scheme=? and value=?", scheme, value);
    if (clash != null)
      throw conflict(
          "条码 " + value + "（" + BarcodeScheme.label(scheme) + "）已被另一个 SKU 使用；"
              + "同一 scheme 下条码值全租户唯一，这是拣货扫码能唯一定位 SKU 的前提");

    if (input.isPrimary())
      // 主条码在同一 SKU 同一 scheme 下至多一个：先降级旧的，DB 上的部分唯一索引也会兜底。
      sql.execute(
          "update sku_identifier set is_primary=false,updated_at=now()"
              + " where sku_id=? and scheme=? and is_primary",
          skuId,
          scheme);
    UUID id = Ids.next();
    try {
      sql.execute(
          "insert into sku_identifier(id,tenant_id,sku_id,scheme,value,is_primary,source,occurred_at)"
              + " values(?,?,?,?,?,?,?,?::timestamptz)",
          id,
          actor.tenantId(),
          skuId,
          scheme,
          value,
          input.isPrimary(),
          source,
          utcNow());
    } catch (DataAccessException e) {
      throw conflict("条码 " + value + " 已被占用，请刷新后重试");
    }
    // sku.barcode 的同步由 V7 的触发器完成，避免应用层双写漏调导致条码不一致（会真的打错货）。
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(),
        actor.tenantId(),
        actor.userId(),
        skuId,
        "SkuIdentifierAdded");
    return new SkuIdentifierSaved(id, scheme, value, input.isPrimary());
  }

  /** 删除一个条码。删掉主条码时 sku.barcode 由触发器清空。 */
  public void deleteIdentifier(AccessService.Access actor, UUID skuId, UUID identifierId) {
    var row =
        sql.fetchOne(
            "select id from sku_identifier where id=? and sku_id=?", identifierId, skuId);
    if (row == null) throw CatalogService.missing();
    sql.execute("delete from sku_identifier where id=?", identifierId);
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(),
        actor.tenantId(),
        actor.userId(),
        skuId,
        "SkuIdentifierRemoved");
  }

  // ============================================================ 批量补齐 / 自动生成

  /**
   * 按规则自动生成（GENERATE）或批量补齐（BACKFILL）内部编码。
   *
   * <p>GENERATE 处理操作人显式提交的 skuIds，只填**空编码**、绝不覆盖既有编码；
   * BACKFILL 由规则适用范围扫出**尚未按本规则赋码**的 SKU 批量规范化（允许覆盖旧编码，
   * 但逐行留 from_code，且必须预览先行）。两者共用序号分配、查重、留痕与事件。
   *
   * <p>「已有编码」在 GENERATE 里是 SKIPPED 而非 REJECTED——不是错误，只是这一行不属于它该动的范围。
   */
  public SkuCodeBatchResult batch(AccessService.Access actor, SkuCodeBatchInput input) {
    String action = input.action().trim().toUpperCase(Locale.ROOT);
    CatalogService.check(Set.of("GENERATE", "BACKFILL").contains(action), "批量动作只能是 GENERATE 或 BACKFILL");
    var ruleRow =
        sql.fetchOne(
            "select id,code,scope,spu_id,prefix,suffix,seq_width,start_from,enabled from sku_code_rule where id=?",
            input.ruleId());
    if (ruleRow == null) throw CatalogService.missing();
    String ruleCode = ruleRow.get("code", String.class);
    CatalogService.check(
        Boolean.TRUE.equals(ruleRow.get("enabled", Boolean.class)), "编码规则已停用，无法生成：" + ruleCode);
    String prefix = ruleRow.get("prefix", String.class);
    String suffix = ruleRow.get("suffix", String.class);
    int width = ruleRow.get("seq_width", Integer.class);
    UUID ruleSpu = ruleRow.get("spu_id", UUID.class);
    OffsetDateTime occurredAt = instant(input.occurredAt());

    var candidates = new ArrayList<Record>();
    var items = new ArrayList<SkuCodeBatchItem>();
    // 与 items 平行：**可以落库**的 sku_id。看不见的 SKU 不能写进明细行
    // （sku_code_batch_item 的 data_scope 策略会拒绝，而且存别人的 ID 进自己的表本就不该做），
    // 但拒绝/跳过的原因必须照常回给操作人，所以响应里仍带原始 ID，落库时置空。
    var persistIds = new ArrayList<UUID>();
    if ("GENERATE".equals(action)) {
      CatalogService.check(
          input.skuIds() != null && !input.skuIds().isEmpty(), "请先选择要生成编码的 SKU");
      CatalogService.check(
          new HashSet<>(input.skuIds()).size() == input.skuIds().size(), "SKU 不能重复提交");
      for (UUID id : input.skuIds()) {
        var row = sql.fetchOne("select id,code,spu_id from sku where id=?", id);
        // 取不到即「不存在或没有访问权限」，不区分两者，避免用差别探测别人的 SKU。
        if (row == null) continue;
        if (ruleSpu != null && !ruleSpu.equals(row.get("spu_id", UUID.class))) continue;
        candidates.add(row);
      }
      // 看不见 / 不在规则范围内的提交项也要回一条原因，否则 requested 与 items 对不上，
      // 操作人会以为那几行被静默吞掉了。落库时 sku_id 置空（见 persistIds 的说明）。
      var visible = new HashSet<UUID>();
      for (var row : candidates) visible.add(row.get("id", UUID.class));
      int submittedRow = 0;
      for (UUID id : input.skuIds()) {
        submittedRow++;
        if (visible.contains(id)) continue;
        items.add(
            new SkuCodeBatchItem(
                submittedRow,
                id,
                "",
                "SKIPPED",
                ruleSpu != null ? "记录不存在、没有访问权限，或不属于本规则适用的商品族"
                                : "记录不存在或没有访问权限",
                null,
                null));
        persistIds.add(null);
      }
    } else {
      // 补齐的目标 = 规则适用范围内、**尚未按本规则赋码**的 SKU（编码为空，或最近一次留痕
      // 来自别的规则/手工）。
      //
      // 为什么不是"只填空编码"：sku 上有 UNIQUE(tenant_id,code)（V1），空编码每租户最多一行，
      // 那样"批量"补齐最多只能命中一行，名不副实。真实的编码治理场景是"一批历史/手工编码
      // 统一规范化到公司规则"，因此目标集合按"没按本规则编过码"来取。
      // 这也是本动作与 GENERATE 的分界：GENERATE 只填空编码、绝不覆盖既有编码；
      // BACKFILL 是显式的规范化动作，允许覆盖，但必须预览先行且逐行留 from_code。
      var rows =
          sql.fetch(
              "select s.id,s.code,s.spu_id from sku s"
                  + " left join lateral (select a.rule_id from sku_code_assignment a"
                  + "   where a.sku_id=s.id order by a.created_at desc,a.id desc limit 1) la on true"
                  + " where (la.rule_id is null or la.rule_id<>?)"
                  + (ruleSpu != null ? " and s.spu_id=?" : "")
                  + " order by s.created_at,s.id limit " + MAX_BACKFILL_SCAN,
              ruleSpu == null ? new Object[] {input.ruleId()} : new Object[] {input.ruleId(), ruleSpu});
      candidates.addAll(rows);
      CatalogService.check(
          candidates.size() < MAX_BACKFILL_SCAN,
          "待补齐的 SKU 超过 " + MAX_BACKFILL_SCAN + " 个，请先用限定到商品族的规则（scope=SPU）缩小范围");
    }

    UUID batchId = Ids.next();
    int rowNo = 0;
    int matched = 0;
    // 先规划全部行再落库：号段一次分配，不因中途拒绝而漏号。
    long[] block = allocate(ruleRow.get("id", UUID.class), actor, ruleRow.get("start_from", Long.class), candidates.size(), input.dryRun());
    long sequence = block[0];
    for (var row : candidates) {
      rowNo++;
      matched++;
      UUID skuId = row.get("id", UUID.class);
      persistIds.add(skuId);
      String before = row.get("code", String.class);
      boolean hasCode = before != null && !before.isBlank();
      if ("GENERATE".equals(action) && hasCode) {
        // GENERATE 只填空编码：已有编码（含手工与旧规则）一律跳过，
        // 要重编号请显式走 BACKFILL 或逐条手工输入。
        items.add(new SkuCodeBatchItem(rowNo, skuId, before, "SKIPPED", "已有编码 " + before + "，规则不覆盖手工编码；如需重编号请用批量补齐", before, null));
        continue;
      }
      String code = SkuCodePattern.render(prefix, sequence++, width, suffix);
      String problem = SkuCodePattern.codeProblem(code);
      if (problem != null) {
        items.add(new SkuCodeBatchItem(rowNo, skuId, null, "REJECTED", problem, before, code));
        continue;
      }
      var clash = sql.fetchOne("select id from sku where code=?", code);
      if (clash != null) {
        items.add(
            new SkuCodeBatchItem(
                rowNo, skuId, null, "REJECTED", "编码 " + code + " 已被其他 SKU 使用", before, code));
        continue;
      }
      items.add(new SkuCodeBatchItem(rowNo, skuId, null, "APPLIED", "按规则 " + ruleCode + " 生成", before, code));
    }

    int requested = "GENERATE".equals(action) ? input.skuIds().size() : matched;
    int applied = (int) items.stream().filter(i -> i.outcome().equals("APPLIED")).count();
    int skipped = (int) items.stream().filter(i -> i.outcome().equals("SKIPPED")).count();
    int rejected = (int) items.stream().filter(i -> i.outcome().equals("REJECTED")).count();
    sql.execute(
        "insert into sku_code_batch(id,tenant_id,action,status,requested_count,matched_count,"
            + "applied_count,skipped_count,actor_id,occurred_at)"
            + " values(?,?,?,?,?,?,?,?,?,?::timestamptz)",
        batchId,
        actor.tenantId(),
        action,
        input.dryRun() ? "PREVIEW" : "APPLIED",
        requested,
        matched,
        applied,
        skipped,
        actor.userId(),
        occurredAt);
    // 逐行留痕：被拒的行也必须落，运营要知道**哪一行**为什么被拒。
    for (int i = 0; i < items.size(); i++) {
      var item = items.get(i);
      sql.execute(
          "insert into sku_code_batch_item(tenant_id,batch_id,sku_id,row_no,outcome,reason,code_before,code_after)"
              + " values(?,?,?,?,?,?,?,?)",
          actor.tenantId(),
          batchId,
          i < persistIds.size() ? persistIds.get(i) : null,
          item.rowNo(),
          item.outcome(),
          item.reason(),
          item.codeBefore(),
          item.codeAfter());
    }

    if (input.dryRun())
      // 预览不消耗号段（allocate 也未写），只落"看过影响范围"这条痕。
      return new SkuCodeBatchResult(
          batchId, action, ruleCode, true, requested, matched, applied, skipped, rejected, items);

    var done = new ArrayList<SkuCodeBatchItem>();
    for (var item : items) {
      if (!item.outcome().equals("APPLIED")) continue;
      // 逐条 CAS：GENERATE 要求目标仍为空编码（不覆盖别人刚写进去的）；
      // BACKFILL 是显式规范化，允许覆盖既有编码，但仍以读到的旧值为条件避免并发踩踏。
      int n =
          "GENERATE".equals(action)
              ? sql.execute(
                  "update sku set code=?,version=version+1,updated_at=now() where id=? and btrim(code)=''",
                  item.codeAfter(),
                  item.skuId())
              : sql.execute(
                  "update sku set code=?,version=version+1,updated_at=now() where id=? and code=?",
                  item.codeAfter(),
                  item.skuId(),
                  item.codeBefore() == null ? "" : item.codeBefore());
      if (n != 1) {
        String note = "编码已被其他操作写入，本行未覆盖";
        sql.execute(
            "update sku_code_batch_item set outcome='SKIPPED',reason=? where tenant_id=? and batch_id=? and row_no=?",
            note,
            actor.tenantId(),
            batchId,
            item.rowNo());
        continue;
      }
      sql.execute(
          "insert into sku_code_assignment(id,tenant_id,sku_id,from_code,to_code,source,rule_id,batch_id,"
              + "actor_id,occurred_at) values(?,?,?,?,?,?,?,?,?,?::timestamptz)",
          Ids.next(),
          actor.tenantId(),
          item.skuId(),
          item.codeBefore(),
          item.codeAfter(),
          "BACKFILL".equals(action) ? "BACKFILL" : "RULE",
          input.ruleId(),
          batchId,
          actor.userId(),
          occurredAt);
      sql.execute(
          "insert into outbox_event(id,tenant_id,aggregate_id,aggregate_version,event_type,payload)"
              + " select ?,tenant_id,id,version,'SkuCodeAssigned',"
              + " jsonb_build_object('skuId',id,'code',code,'ruleCode',?::text,'batchId',?::text)"
              + " from sku where id=?",
          Ids.next(),
          ruleCode,
          batchId.toString(),
          item.skuId());
      done.add(item);
    }
    applied = done.size();
    sql.execute(
        "update sku_code_batch set applied_count=?,skipped_count=skipped_count+? where tenant_id=? and id=?",
        applied,
        items.size() - applied - skipped,
        actor.tenantId(),
        batchId);
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(),
        actor.tenantId(),
        actor.userId(),
        batchId,
        "SkuCodeBatchApplied");
    var merged = new ArrayList<SkuCodeBatchItem>(done);
    for (var item : items) if (!item.outcome().equals("APPLIED")) merged.add(item);
    return new SkuCodeBatchResult(
        batchId,
        action,
        ruleCode,
        false,
        requested,
        matched,
        applied,
        items.size() - applied,
        rejected,
        merged);
  }

  /**
   * 原子分配 {@code count} 个连续序号，返回号段起点。
   *
   * <p>{@code next_value} 的语义是「下一个尚未发出的序号」。一次发号 count 个之后
   * next_value 前进 count，于是刚发出去的那段是 {@code [next_value - count, next_value - 1]}，
   * 起点就是 {@code next_value - count}。
   *
   * <p>首次分配走 INSERT 分支写入 {@code start_from + count}，与后续的 UPDATE 分支语义一致：
   * 规则 start_from=1 的第一次发号拿到 1，而不是 2。
   *
   * <p>{@code ON CONFLICT DO UPDATE ... RETURNING} 在一条语句里完成「读—加—写」，
   * 并发的两批生成不会拿到同一段号——这正是不能用「先查 next_value 再 update」的原因。
   * 预览（dryRun）只读不写，不消耗号段。
   */
  private long[] allocate(UUID ruleId, AccessService.Access actor, long startFrom, int count, boolean dryRun) {
    if (count <= 0) return new long[] {startFrom};
    if (dryRun) {
      var row = sql.fetchOne("select next_value from sku_code_rule_seq where rule_id=?", ruleId);
      long current = row == null ? startFrom : row.get("next_value", Long.class);
      return new long[] {current};
    }
    long next =
        sql.fetchOne(
                "insert into sku_code_rule_seq(tenant_id,rule_id,next_value) values(?,?,?::bigint)"
                    + " on conflict (tenant_id,rule_id) do update"
                    + " set next_value=sku_code_rule_seq.next_value+?::bigint"
                    + " returning next_value",
                actor.tenantId(),
                ruleId,
                startFrom + count,
                count)
            .get("next_value", Long.class);
    return new long[] {next - count};
  }

  /** 读回一个批次的完整明细（含拒绝清单），供前端事后追查。 */
  public SkuCodeBatchResult batchResult(UUID batchId) {
    var head = sql.fetchOne("select id,action,status,requested_count,matched_count,applied_count,skipped_count from sku_code_batch where id=?", batchId);
    if (head == null) throw CatalogService.missing();
    var ruleCodes =
        sql.fetch(
                "select distinct r.code from sku_code_assignment a join sku_code_rule r"
                    + " on r.tenant_id=a.tenant_id and r.id=a.rule_id where a.batch_id=?",
                batchId)
            .stream()
            .map(r -> r.get("code", String.class))
            .toList();
    var items =
        sql.fetch(
                "select i.row_no,i.sku_id,s.code as sku_code,i.outcome,i.reason,i.code_before,i.code_after"
                    + " from sku_code_batch_item i left join sku s on s.tenant_id=i.tenant_id and s.id=i.sku_id"
                    + " where i.batch_id=? order by i.row_no",
                batchId)
            .map(
                r ->
                    new SkuCodeBatchItem(
                        r.get("row_no", Integer.class),
                        r.get("sku_id", UUID.class),
                        r.get("sku_code", String.class),
                        r.get("outcome", String.class),
                        r.get("reason", String.class),
                        r.get("code_before", String.class),
                        r.get("code_after", String.class)))
            .stream()
            .toList();
    int rejected = (int) items.stream().filter(i -> i.outcome().equals("REJECTED")).count();
    return new SkuCodeBatchResult(
        head.get("id", UUID.class),
        head.get("action", String.class),
        ruleCodes.isEmpty() ? null : ruleCodes.get(0),
        "PREVIEW".equals(head.get("status", String.class)),
        head.get("requested_count", Integer.class),
        head.get("matched_count", Integer.class),
        head.get("applied_count", Integer.class),
        head.get("skipped_count", Integer.class),
        rejected,
        items);
  }

  // ============================================================ 条码批量导入

  /**
   * 条码批量导入，逐行给出接受/拒绝结果。
   *
   * <p>拒绝原因分类（对应「唯一性冲突」这条输入）：
   * SKU 不可见、scheme 不支持、条码格式/校验位错、**已被其他 SKU 占用**、
   * 同批次内重复、该 SKU 同 scheme 已有主条码。每一条都带可执行的中文原因。
   *
   * <p>逐行独立处理，不因一行失败而回滚整批——这是"冲突拒绝清单"这条交付物的意义。
   */
  public SkuIdentifierImportResult importIdentifiers(
      AccessService.Access actor, SkuIdentifierImportInput input) {
    CatalogService.check(!input.rows().isEmpty(), "请至少提交一行条码");
    UUID batchId = Ids.next();
    OffsetDateTime occurredAt = instant(input.occurredAt());
    var seen = new HashSet<String>();
    var visibleSkus = new HashMap<UUID, Boolean>();
    var items = new ArrayList<SkuIdentifierImportItem>();
    // 与 items 平行：可以落库的 sku_id。看不见 / 不存在的 SKU 在明细行里置空——
    // data_scope 策略不允许写指向不可见 SKU 的行（RLS 会直接拒绝整条插入），
    // 而且把别人的 ID 存进自己的表本就不该做。响应里仍回提交者给的 ID，
    // 操作人才知道是哪一行要改。
    var persistIds = new ArrayList<UUID>();
    int rowNo = 0;
    int applied = 0;

    for (var row : input.rows()) {
      rowNo++;
      String scheme = BarcodeScheme.normalize(row.scheme());
      String value = row.value() == null ? "" : row.value().trim();
      // 条码格式就错时连 SKU 都不用查：这一行必然被拒，没必要碰库。
      String problem = BarcodeScheme.problem(scheme, value);
      if (problem != null) {
        items.add(new SkuIdentifierImportItem(rowNo, row.skuId(), scheme, value, "REJECTED", problem));
        persistIds.add(null);
        continue;
      }
      // SKU 可见性缓存：同一批里重复查同一行会放大到 N+1。
      boolean exists =
          visibleSkus.computeIfAbsent(
              row.skuId(),
              id -> sql.fetchOne("select id from sku where id=?", id) != null);
      if (!exists) {
        items.add(new SkuIdentifierImportItem(rowNo, row.skuId(), scheme, value, "REJECTED", "记录不存在或没有访问权限"));
        continue;
      }
      if (!seen.add(scheme + " " + value)) {
        items.add(new SkuIdentifierImportItem(rowNo, row.skuId(), scheme, value, "REJECTED", "本批次内该 scheme+条码值重复"));
        continue;
      }
      var clash = sql.fetchOne("select sku_id from sku_identifier where scheme=? and value=?", scheme, value);
      if (clash != null) {
        items.add(new SkuIdentifierImportItem(rowNo, row.skuId(), scheme, value, "REJECTED", "条码 " + value + " 已被其他 SKU 使用"));
        continue;
      }
      if (row.isPrimary()) {
        var primary =
            sql.fetchOne(
                "select value from sku_identifier where sku_id=? and scheme=? and is_primary",
                row.skuId(),
                scheme);
        if (primary != null) {
          items.add(
              new SkuIdentifierImportItem(
                  rowNo, row.skuId(), scheme, value, "REJECTED",
                  "该 SKU 的 " + BarcodeScheme.label(scheme) + " 已有主条码 " + primary.get("value", String.class)
                      + "；如需替换请先删除原主条码"));
          continue;
        }
      }
      items.add(new SkuIdentifierImportItem(rowNo, row.skuId(), scheme, value, "APPLIED", "导入"));
      if (input.dryRun()) {
        applied++;
        continue;
      }
      try {
        sql.execute(
            "insert into sku_identifier(id,tenant_id,sku_id,scheme,value,is_primary,source,occurred_at)"
                + " values(?,?,?,?,?,?,'IMPORT',?::timestamptz)",
            Ids.next(),
            actor.tenantId(),
            row.skuId(),
            scheme,
            value,
            row.isPrimary(),
            occurredAt);
        applied++;
      } catch (DataAccessException e) {
        // 并发写入兜底：唯一索引挡住后这一行改判为拒绝，其余行继续。
        items.set(items.size() - 1, new SkuIdentifierImportItem(rowNo, row.skuId(), scheme, value, "REJECTED", "条码 " + value + " 已被并发占用"));
        applied--;
      }
    }

    int rejected = items.size() - applied;
    // 导入的批次头复用 sku_code_batch（action=IMPORT），让「最近批次」列表覆盖编码与条码两类操作。
    sql.execute(
        "insert into sku_code_batch(id,tenant_id,action,status,requested_count,matched_count,"
            + "applied_count,skipped_count,actor_id,occurred_at)"
            + " values(?,?,'IMPORT',?,?,?,?,?,?,?::timestamptz)",
        batchId,
        actor.tenantId(),
        input.dryRun() ? "PREVIEW" : "APPLIED",
        items.size(),
        items.size() - rejected,
        applied,
        0,
        actor.userId(),
        occurredAt);
    // 逐行留痕（含被拒行），这样事后用 batchResult 读回时拒绝清单仍然完整，
    // 不必依赖「当时那次响应还在浏览器里」。
    for (int i = 0; i < items.size(); i++) {
      var item = items.get(i);
      sql.execute(
          "insert into sku_code_batch_item(tenant_id,batch_id,sku_id,row_no,outcome,reason,scheme,value)"
              + " values(?,?,?,?,?,?,?,?)",
          actor.tenantId(),
          batchId,
          i < persistIds.size() ? persistIds.get(i) : null,
          item.rowNo(),
          item.outcome(),
          item.reason(),
          item.scheme(),
          item.value());
    }
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(),
        actor.tenantId(),
        actor.userId(),
        batchId,
        "SkuIdentifierImported");
    return new SkuIdentifierImportResult(batchId, input.dryRun(), items.size(), applied, rejected, items);
  }

  // ============================================================ 工具

  /** 业务时间：前端传 ISO-8601，留空取当前；与系统写入时间分列存储。 */
  private static OffsetDateTime instant(String value) {
    return value == null || value.isBlank()
        ? OffsetDateTime.now(ZoneOffset.UTC)
        : OffsetDateTime.ofInstant(Instant.parse(value), ZoneOffset.UTC);
  }

  private static OffsetDateTime utcNow() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}