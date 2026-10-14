package com.starcon.trade;

import static com.starcon.trade.WarehouseAllocationModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 9.3.2.1 分仓规则：配置、试算与执行仓库分配。
 *
 * <p><b>五条实现纪律</b>（照抄 9.3.2.2 的写法，不另起风格）：
 *
 * <ol>
 *   <li><b>判定只有一处</b>：{@link #simulate} 是唯一的判定实现，
 *       {@link #allocate} 落库时也调它 —— 页面预览与实际落库结论不一致时，
 *       运营看到的和系统做的是两件事，这是最不能容忍的一种不一致。
 *   <li><b>候选落库而不是查询时联算</b>：库存会被补上、运费估算会被改、
 *       规则权重会被调，联算会让「当时为什么分了 A 仓」永远答不出来。
 *   <li><b>金额只有一处口径</b>：结算一律 {@code setScale(2, HALF_UP)}，
 *       DB 侧 {@code round(x,2)} 复核（照抄 10.5.1.3）。
 *   <li><b>时间绑定</b>：{@code ?::timestamptz} 写在 values 的对应位置上，
 *       cast 不出现在绑定序列末位。
 *   <li><b>不用 JPA 懒加载</b>：全部 jOOQ 显式 SQL。
 * </ol>
 *
 * <p><b>判定顺序与 9.3.2.2 自洽</b>：「资格 → 库存 → 成本」在��里展开成
 * <b>资格（启用 / 规则排除 / <b>货权硬闸</b>）→ 库存（有记录且够）→ 成本（有运费与时效估算）
 * → 打分排序（库存 / 成本 / 时效 / <b>货权</b> / 地区 五维加权）</b>。
 * 货权进第 1 层做闸、进第 4 层做权重：与「资质进第 1 层、库存进第 2 层、成本进第 3 层」
 * 是同一种纪律 —— 不合规的先出局，合规的再比好。
 *
 * <p><b>货权为什么是闸而不只是分</b>：蓝图加粗句「从 A 法人的仓发货意味着收入记在 A 法人，
 * 不能只看运费便宜」。「收入记在哪个法人」是<b>记账正确性</b>，不是可权衡项 ——
 * 所以它先是资格闸（{@code require_legal_entity_match}），再是排序权重
 * （{@code weight_legal_entity}）。即便开了硬闸，同属订单法人的多个仓之间仍靠权重排序：
 * 「都合法」不等于「一样好」。
 */
@Service
public class WarehouseAllocationService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 数量刻度：与 DB 的 numeric(19,6) 对齐。 */
  private static final int QTY_SCALE = 6;

  /** 金额刻度：与 DB 的 numeric(19,4) 对齐。 */
  private static final int MONEY_SCALE = 4;

  /** 结算口径：统一 HALF_UP 到分。 */
  private static final int SETTLE_SCALE = 2;

  /** 得分刻度：与 DB 的 numeric(9,4) 对齐。 */
  private static final int SCORE_SCALE = 4;

  private static final BigDecimal ZERO_QTY = new BigDecimal("0.000000");
  private static final BigDecimal ZERO_MONEY = new BigDecimal("0.0000");
  private static final BigDecimal ONE = BigDecimal.ONE;

  private final DSLContext sql;

  public WarehouseAllocationService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上游可用性。
   *
   * <p>{@code crossServiceReadAvailable=false} 与 {@code reservesUpstreamStock=false}
   * 都是如实报出：7.1.1.3 在 warehouse 的数据库里，按 AGENTS.md 禁止跨库读取；
   * 下游 9.3.3.1 / 8 出库单尚未交付，本模块只产出分配结果，**不预占上游库存** ——
   * 现在假装预占，等下游落地时会出现「两边都以为占了同一批货」。
   */
  public WarehouseAllocationCapabilities capabilities() {
    return new WarehouseAllocationCapabilities(
        List.of(
            new WarehouseAllocationCapabilities.WarehouseAllocationDimension(
                "WAREHOUSE_STOCK",
                "各仓可用量",
                true,
                "7.1.1.3 可用量（warehouse 服务）→ 本服务自维护估算表",
                null),
            new WarehouseAllocationCapabilities.WarehouseAllocationDimension(
                "SHIPPING_COST",
                "运费与时效估算",
                true,
                "10 运费试算（**未开发**）→ 本服务租户维护估算表",
                "10 运费试算尚未交付，运费与时效由租户在本模块登记；"
                    + "本模块只在**同币种（USD）**下比较各仓，不做跨币种换算"),
            new WarehouseAllocationCapabilities.WarehouseAllocationDimension(
                "LEGAL_ENTITY",
                "货权归属（法人）",
                true,
                "2.1.1.1 法人档案 → 本服务按仓登记 legal_entity_code",
                null),
            new WarehouseAllocationCapabilities.WarehouseAllocationDimension(
                "ORDER_DESTINATION",
                "订单收货信息（分配对象）",
                false,
                "9.1.1.2 订单详情（trade 服务，未开发）",
                "订单表尚未交付，因此本模块只存 orderRef / orderLineRef 逻辑引用并把"
                    + "收货地与订单货权主体作为**快照列**抄进决策行，不校验订单存在性；"
                    + "订单落地后由那条车道补引用或外键"),
            new WarehouseAllocationCapabilities.WarehouseAllocationDimension(
                "OUTBOUND_HANDOFF",
                "下发到履约请求 / 出库单",
                false,
                "9.3.3.1 履约批次、8 出库单（均未开发）",
                "分配可确认，但尚未下发；且本模块**不预占 warehouse 库存**，"
                    + "预占要调 warehouse 的授权 API，属下游车道职责")),
        List.of(WarehouseSelectionPolicy.values()),
        List.of(WarehouseFallbackPolicy.values()),
        List.of(WarehouseLegalEntityPolicy.values()),
        List.of(WarehouseHoldReason.values()),
        false,
        false,
        "7.1.1.3 可用量与 2.1.1.1 法人档案分属 warehouse / party 的独立数据库，"
            + "按模块边界纪律不得跨库读取；本模块把各仓可用量与法人登记为租户可维护的真实表，"
            + "并用 stock_source 如实标注来源。10 运费试算未开发，运费与时效由租户维护。"
            + "将来同步器按同一形状往这三张表写并把来源改成 MODULE_* 即可，"
            + "本模块的表结构与判定逻辑不需要改动。",
        "货权（legal_entity）参与判定有两层：require_legal_entity_match 是**资格闸**，"
            + "法人不符的仓直接不可行且由 DB 触发器钉在候选写入路径上；"
            + "weight_legal_entity 是**排序权重**，同法人内部仍按它排序。"
            + "两者都不启用时货权不参与判定 —— 那是租户显式的选择，不是本模块的默认值。",
        "无法分配时本模块落一条 SUSPENDED 分配 + 一条挂起记录，逐条列出每个仓被排除的原因，"
            + "**绝不静默回落**到某个默认仓：挂起是终态，DB 侧用延迟约束触发器钉死。");
  }

  // ================================================================ 规则配置

  /** 规则列表（真分页，total 与分页无关）。 */
  public WarehouseAllocationRulePage pageRules(WarehouseAllocationRuleQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (notBlank(query.ruleCode())) {
      where.add("rule_code = ?");
      binds.add(query.ruleCode().trim());
    }
    if (notBlank(query.selectionPolicy())) {
      where.add("selection_policy = ?");
      binds.add(selectionPolicy(query.selectionPolicy()).name());
    }
    if (notBlank(query.fallbackPolicy())) {
      where.add("fallback_policy = ?");
      binds.add(fallbackPolicy(query.fallbackPolicy()).name());
    }
    if (Boolean.TRUE.equals(query.effectiveOnly())) {
      where.add("effective_from <= now() and (effective_to is null or effective_to > now())");
    }
    if (notBlank(query.keyword())) {
      where.add("(rule_code ilike ? or rule_name ilike ? or notes ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      for (int i = 0; i < 3; i++) {
        binds.add(k);
      }
    }
    String sqlWhere = whereClause(where);
    long total =
        sql.fetchOne(
                "select count(*) c from fulfillment_warehouse_allocation_rule" + sqlWhere,
                binds.toArray())
            .get("c", Long.class);
    List<WarehouseAllocationRuleRow> items =
        sql.fetch(
                "select " + RULE_COLUMNS + " from fulfillment_warehouse_allocation_rule"
                    + sqlWhere
                    + " order by priority, id limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(WarehouseAllocationService::ruleRow);
    return new WarehouseAllocationRulePage(items, total, pageNo, size);
  }

  /** 维护一条分仓规则。权重合计必须为 1.0000 —— 否则同样输入在不同租户口径下不可比。 */
  @Transactional
  public WarehouseAllocationRuleSaved saveRule(WarehouseAllocationRuleInput input) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    WarehouseSelectionPolicy selection = selectionPolicy(input.selectionPolicy());
    WarehouseFallbackPolicy fallback =
        notBlank(input.fallbackPolicy())
            ? fallbackPolicy(input.fallbackPolicy())
            : WarehouseFallbackPolicy.SUSPEND;
    WarehouseLegalEntityPolicy lePolicy =
        notBlank(input.legalEntityPolicy())
            ? legalEntityPolicy(input.legalEntityPolicy())
            : WarehouseLegalEntityPolicy.NEUTRAL;

    BigDecimal ws = weight(input.weightStock(), "weightStock");
    BigDecimal wc = weight(input.weightCost(), "weightCost");
    BigDecimal wl = weight(input.weightLeadTime(), "weightLeadTime");
    BigDecimal wle =
        weight(
            input.weightLegalEntity() == null ? BigDecimal.ZERO : input.weightLegalEntity(),
            "weightLegalEntity");
    BigDecimal wr =
        weight(input.weightRegion() == null ? BigDecimal.ZERO : input.weightRegion(), "weightRegion");
    BigDecimal sum = ws.add(wc).add(wl).add(wle).add(wr).setScale(4, RoundingMode.HALF_UP);
    if (sum.compareTo(ONE.setScale(4)) != 0) {
      throw bad(
          "五个维度权重合计必须等于 1.0000，当前是 " + sum
              + "：权重不是随便给的大数，合计不为 1 时同样输入在不同租户口径下不可比，"
              + "也说不清到底是哪一维更重要");
    }
    String preferred = nz(input.preferredLegalEntityCode());
    if (lePolicy == WarehouseLegalEntityPolicy.PREFERRED_FIRST && preferred.isBlank()) {
      throw bad("货权口径选了「指定法人优先」，必须给出目标法人 code，否则该维度无从打分");
    }
    boolean requireMatch =
        input.requireLegalEntityMatch() == null || input.requireLegalEntityMatch();
    BigDecimal maxLead = input.maxLeadTimeDays();
    if (maxLead != null && maxLead.signum() < 0) {
      throw bad("期望时效不能为负");
    }
    String country =
        notBlank(input.scopeDestinationCountryCode())
            ? input.scopeDestinationCountryCode().trim().toUpperCase(Locale.ROOT)
            : "*";
    if (!country.equals("*") && country.length() != 2) {
      throw bad("适用范围的目的国必须是两位国家码，或用 * 表示不限");
    }
    OffsetDateTime from = input.effectiveFrom() == null ? OffsetDateTime.now() : input.effectiveFrom();
    OffsetDateTime to = input.effectiveTo();
    if (to != null && !to.isAfter(from)) {
      throw bad("失效时间必须晚于生效时间");
    }
    int priority = input.priority();
    if (priority < 0) {
      throw bad("规则优先级不能为负（数字小 = 优先级高）");
    }

    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    var existing =
        sql.fetchOne(
            "select id from fulfillment_warehouse_allocation_rule where rule_code = ?",
            input.ruleCode().trim());
    // 优先级冲突要在这里报出来：DB 唯一约束报的原文是「duplicate key value」，
    // 读不出「是 priority 撞了还是 rule_code 撞了」。
    var clash =
        sql.fetchOne(
            "select rule_code from fulfillment_warehouse_allocation_rule where priority = ?",
            priority);
    boolean created = existing == null;
    if (created && clash != null) {
      throw bad(
          "优先级 " + priority + " 已被规则「" + clash.get(0, String.class)
              + "」占用：两条规则同优先级时「谁生效」取决于查询顺序，"
              + "同样输入在不同时候会算出不同结果");
    }
    if (created) {
      sql.execute(
          "insert into fulfillment_warehouse_allocation_rule"
              + "(id,tenant_id,rule_code,rule_name,priority,scope_platform_code,"
              + "scope_destination_country_code,selection_policy,fallback_policy,"
              + "weight_stock,weight_cost,weight_lead_time,weight_legal_entity,weight_region,"
              + "require_legal_entity_match,legal_entity_policy,preferred_legal_entity_code,"
              + "max_lead_time_days,currency_code,excluded_warehouse_refs,"
              + "effective_from,effective_to,notes,created_by_membership_id,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'USD',?,?::timestamptz,"
              + "?::timestamptz,?,?,?::timestamptz,?::timestamptz)",
          id, currentTenant(), input.ruleCode().trim(), nz(input.ruleName()), priority,
          blankTo(input.scopePlatformCode(), "*"), country, selection.name(), fallback.name(),
          ws, wc, wl, wle, wr, requireMatch, lePolicy.name(), preferred, maxLead,
          nz(input.excludedWarehouseRefs()), from, to, nz(input.notes()), null, now, now);
    } else {
      id = existing.get(0, UUID.class);
      sql.execute(
          "update fulfillment_warehouse_allocation_rule set rule_name = ?, priority = ?,"
              + " scope_platform_code = ?, scope_destination_country_code = ?,"
              + " selection_policy = ?, fallback_policy = ?, weight_stock = ?, weight_cost = ?,"
              + " weight_lead_time = ?, weight_legal_entity = ?, weight_region = ?,"
              + " require_legal_entity_match = ?, legal_entity_policy = ?,"
              + " preferred_legal_entity_code = ?, max_lead_time_days = ?,"
              + " excluded_warehouse_refs = ?, effective_from = ?::timestamptz,"
              + " effective_to = ?::timestamptz, notes = ?, version = version + 1,"
              + " updated_at = ?::timestamptz where id = ?",
          nz(input.ruleName()), priority, blankTo(input.scopePlatformCode(), "*"), country,
          selection.name(), fallback.name(), ws, wc, wl, wle, wr, requireMatch, lePolicy.name(),
          preferred, maxLead, nz(input.excludedWarehouseRefs()), from, to, nz(input.notes()),
          now, id);
    }
    return new WarehouseAllocationRuleSaved(
        id, input.ruleCode().trim(), priority,
        created ? "规则已创建" : "规则已更新（权重合计 " + sum + "）");
  }

  // ================================================================ 仓库档案

  /** 仓库档案列表（真分页）。 */
  public WarehouseAllocationWarehousePage pageWarehouses(WarehouseAllocationWarehouseQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (notBlank(query.legalEntityCode())) {
      where.add("legal_entity_code = ?");
      binds.add(query.legalEntityCode().trim());
    }
    if (notBlank(query.regionCode())) {
      where.add("region_code = ?");
      binds.add(query.regionCode().trim());
    }
    if (Boolean.TRUE.equals(query.activeOnly())) {
      where.add("active");
    }
    if (notBlank(query.keyword())) {
      where.add("(warehouse_ref ilike ? or warehouse_name ilike ? or legal_entity_code ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      for (int i = 0; i < 3; i++) {
        binds.add(k);
      }
    }
    String sqlWhere = whereClause(where);
    long total =
        sql.fetchOne(
                "select count(*) c from fulfillment_warehouse_allocation_warehouse" + sqlWhere,
                binds.toArray())
            .get("c", Long.class);
    List<WarehouseAllocationWarehouseRow> items =
        sql.fetch(
                "select " + WAREHOUSE_COLUMNS + " from fulfillment_warehouse_allocation_warehouse"
                    + sqlWhere
                    + " order by legal_entity_code, default_priority, warehouse_ref"
                    + " limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(WarehouseAllocationService::warehouseRow);
    return new WarehouseAllocationWarehousePage(items, total, pageNo, size);
  }

  /** 维护一个发货仓的档案。货权必填 —— 不知道货记在谁名下时不允许建档。 */
  @Transactional
  public WarehouseAllocationWarehouseSaved saveWarehouse(WarehouseAllocationWarehouseInput input) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    String ref = input.warehouseRef().trim();
    String entity = input.legalEntityCode().trim();
    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    var existing =
        sql.fetchOne(
            "select id from fulfillment_warehouse_allocation_warehouse where warehouse_ref = ?", ref);
    boolean created = existing == null;
    if (created) {
      sql.execute(
          "insert into fulfillment_warehouse_allocation_warehouse"
              + "(id,tenant_id,warehouse_ref,warehouse_name,legal_entity_code,region_code,"
              + "default_priority,active,notes,created_by_membership_id,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
          id, currentTenant(), ref, nz(input.warehouseName()), entity,
          blankTo(input.regionCode(), "*"),
          input.defaultPriority() == null ? 100 : input.defaultPriority(),
          input.active() == null || input.active(), nz(input.notes()), null, now, now);
    } else {
      id = existing.get(0, UUID.class);
      sql.execute(
          "update fulfillment_warehouse_allocation_warehouse set warehouse_name = ?,"
              + " legal_entity_code = ?, region_code = ?, default_priority = ?, active = ?,"
              + " notes = ?, updated_at = ?::timestamptz where id = ?",
          nz(input.warehouseName()), entity, blankTo(input.regionCode(), "*"),
          input.defaultPriority() == null ? 100 : input.defaultPriority(),
          input.active() == null || input.active(), nz(input.notes()), now, id);
    }
    return new WarehouseAllocationWarehouseSaved(
        id, ref, entity, (created ? "仓库档案已创建" : "仓库档案已更新") + "，货权法人 " + entity);
  }

  // ================================================================ 库存估算

  /** 库存估算列表（真分页）。 */
  public WarehouseAllocationStockPage pageStockEstimates(WarehouseAllocationStockQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (notBlank(query.warehouseRef())) {
      where.add("e.warehouse_ref = ?");
      binds.add(query.warehouseRef().trim());
    }
    if (notBlank(query.skuRef())) {
      where.add("e.sku_ref = ?");
      binds.add(query.skuRef().trim());
    }
    if (notBlank(query.stockSource())) {
      where.add("e.stock_source = ?");
      binds.add(query.stockSource().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.keyword())) {
      where.add("(e.warehouse_ref ilike ? or e.sku_ref ilike ? or e.source_reference ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      for (int i = 0; i < 3; i++) {
        binds.add(k);
      }
    }
    String sqlWhere = whereClause(where);
    String from = " from fulfillment_warehouse_allocation_stock_estimate e";
    long total =
        sql.fetchOne("select count(*) c" + from + sqlWhere, binds.toArray()).get("c", Long.class);
    List<WarehouseAllocationStockRow> items =
        sql.fetch(
                "select e.id,e.warehouse_ref,e.sku_ref,e.available_qty,e.collected_at,"
                    + "e.stock_source,e.source_reference,e.notes,e.created_at"
                    + from
                    + sqlWhere
                    + " order by e.sku_ref, e.warehouse_ref limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(WarehouseAllocationService::stockRow);
    return new WarehouseAllocationStockPage(items, total, pageNo, size);
  }

  /** 登记 / 更新一个仓的可用量估算（UPSERT：同一仓同一 SKU 只有一条当前值）。 */
  @Transactional
  public WarehouseAllocationStockSaved saveStockEstimate(
      WarehouseAllocationStockInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    String ref = input.warehouseRef().trim();
    String sku = input.skuRef().trim();
    requireWarehouse(ref);
    if (input.availableQty().signum() < 0) {
      throw bad("可用量不能为负");
    }
    String source =
        notBlank(input.stockSource())
            ? input.stockSource().trim().toUpperCase(Locale.ROOT)
            : "TENANT_MAINTAINED";
    if (!source.equals("TENANT_MAINTAINED") && !source.equals("MODULE_7_1_1_3")) {
      throw bad("stockSource 只能是 MODULE_7_1_1_3 或 TENANT_MAINTAINED");
    }
    String sourceRef = nz(input.sourceReference());
    if (source.equals("MODULE_7_1_1_3") && sourceRef.isBlank()) {
      throw bad(
          "来源标为 MODULE_7_1_1_3 时必须给出处单号：7.1.1.3 是同步来的快照，"
              + "没有出处的库存快照等于凭空捏一个数去决定从哪个仓发货");
    }
    BigDecimal qty = qty(input.availableQty());
    UUID id = UUID.randomUUID();
    var existing =
        sql.fetchOne(
            "select id from fulfillment_warehouse_allocation_stock_estimate"
                + " where warehouse_ref = ? and sku_ref = ?",
            ref, sku);
    boolean created = existing == null;
    if (created) {
      sql.execute(
          "insert into fulfillment_warehouse_allocation_stock_estimate"
              + "(id,tenant_id,warehouse_ref,sku_ref,available_qty,collected_at,stock_source,"
              + "source_reference,notes,created_by_membership_id,created_at)"
              + " values (?,?,?,?,?,?::timestamptz,?,?,?,?,?::timestamptz)",
          id, currentTenant(), ref, sku, qty, input.collectedAt(), source, sourceRef,
          nz(input.notes()), actorId, OffsetDateTime.now());
    } else {
      id = existing.get(0, UUID.class);
      sql.execute(
          "update fulfillment_warehouse_allocation_stock_estimate set available_qty = ?,"
              + " collected_at = ?::timestamptz, stock_source = ?, source_reference = ?,"
              + " notes = ? where id = ?",
          qty, input.collectedAt(), source, sourceRef, nz(input.notes()), id);
    }
    return new WarehouseAllocationStockSaved(
        id, ref, sku, qty, created ? "可用量估算已登记" : "可用量估算已更新");
  }

  // ================================================================ 运费与时效估算

  /** 运费估算列表（真分页）。 */
  public WarehouseAllocationShippingPage pageShippingEstimates(
      WarehouseAllocationShippingQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (notBlank(query.warehouseRef())) {
      where.add("warehouse_ref = ?");
      binds.add(query.warehouseRef().trim());
    }
    if (notBlank(query.skuRef())) {
      where.add("sku_ref = ?");
      binds.add(query.skuRef().trim());
    }
    if (notBlank(query.destinationCountryCode())) {
      where.add("destination_country_code = ?");
      binds.add(query.destinationCountryCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.costSource())) {
      where.add("cost_source = ?");
      binds.add(query.costSource().trim().toUpperCase(Locale.ROOT));
    }
    if (Boolean.TRUE.equals(query.effectiveOnly())) {
      where.add("effective_from <= now() and (effective_to is null or effective_to > now())");
    }
    String sqlWhere = whereClause(where);
    long total =
        sql.fetchOne(
                "select count(*) c from fulfillment_warehouse_allocation_shipping_estimate"
                    + sqlWhere,
                binds.toArray())
            .get("c", Long.class);
    List<WarehouseAllocationShippingRow> items =
        sql.fetch(
                "select " + SHIPPING_COLUMNS
                    + " from fulfillment_warehouse_allocation_shipping_estimate"
                    + sqlWhere
                    + " order by sku_ref, destination_country_code, warehouse_ref"
                    + " limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(WarehouseAllocationService::shippingRow);
    return new WarehouseAllocationShippingPage(items, total, pageNo, size);
  }

  /** 维护一条运费与时效估算。 */
  @Transactional
  public WarehouseAllocationShippingSaved saveShippingEstimate(
      WarehouseAllocationShippingInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    String ref = input.warehouseRef().trim();
    String sku = input.skuRef().trim();
    requireWarehouse(ref);
    String country = input.destinationCountryCode().trim().toUpperCase(Locale.ROOT);
    if (country.length() != 2) {
      throw bad("目的国必须是两位国家码");
    }
    if (input.shippingCost().signum() < 0) {
      throw bad("运费不能为负");
    }
    if (input.leadTimeDays() != null && input.leadTimeDays().signum() < 0) {
      throw bad("预计时效不能为负");
    }
    String source =
        notBlank(input.costSource())
            ? input.costSource().trim().toUpperCase(Locale.ROOT)
            : "TENANT_MAINTAINED";
    if (!source.equals("TENANT_MAINTAINED") && !source.equals("MODULE_10_1_2_1")) {
      throw bad("costSource 只能是 MODULE_10_1_2_1 或 TENANT_MAINTAINED");
    }
    String sourceRef = nz(input.sourceReference());
    if (source.equals("MODULE_10_1_2_1") && sourceRef.isBlank()) {
      throw bad(
          "来源标为 MODULE_10_1_2_1 时必须给出处单号：10 运费试算尚未交付，"
              + "标成模块来源却没有出处，等于伪造一个上游");
    }
    BigDecimal cost = money(input.shippingCost());
    BigDecimal lead =
        input.leadTimeDays() == null ? null : input.leadTimeDays().setScale(2, RoundingMode.HALF_UP);
    OffsetDateTime from =
        input.effectiveFrom() == null ? OffsetDateTime.now() : input.effectiveFrom();
    OffsetDateTime to = input.effectiveTo();
    if (to != null && !to.isAfter(from)) {
      throw bad("失效时间必须晚于生效时间");
    }
    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    var existing =
        sql.fetchOne(
            "select id from fulfillment_warehouse_allocation_shipping_estimate"
                + " where warehouse_ref = ? and sku_ref = ? and destination_country_code = ?",
            ref, sku, country);
    boolean created = existing == null;
    if (created) {
      sql.execute(
          "insert into fulfillment_warehouse_allocation_shipping_estimate"
              + "(id,tenant_id,warehouse_ref,sku_ref,destination_country_code,shipping_cost,"
              + "lead_time_days,currency_code,cost_source,source_reference,effective_from,"
              + "effective_to,notes,version,created_by_membership_id,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,'USD',?,?,?::timestamptz,?::timestamptz,?,0,?,"
              + "?::timestamptz,?::timestamptz)",
          id, currentTenant(), ref, sku, country, cost, lead, source, sourceRef, from, to,
          nz(input.notes()), actorId, now, now);
    } else {
      id = existing.get(0, UUID.class);
      sql.execute(
          "update fulfillment_warehouse_allocation_shipping_estimate set shipping_cost = ?,"
              + " lead_time_days = ?, cost_source = ?, source_reference = ?,"
              + " effective_from = ?::timestamptz, effective_to = ?::timestamptz, notes = ?,"
              + " version = version + 1, updated_at = ?::timestamptz where id = ?",
          cost, lead, source, sourceRef, from, to, nz(input.notes()), now, id);
    }
    return new WarehouseAllocationShippingSaved(
        id, ref, country, cost, created ? "运费估算已登记" : "运费估算已更新");
  }

  // ================================================================ 试算（只读）

  /**
   * 只读试算：给一组输入，算出「应该从哪个仓发、为什么、每个仓分别如何」。
   *
   * <p><b>不落库</b>：页面用它做预演。执行分配走 {@link #allocate}，那里也调本方法 ——
   * 预览与落库结论必须同源，否则运营看到的和系统做的是两件事。
   */
  public WarehouseAllocationSimulation simulate(WarehouseAllocationSimulationRequest request) {
    if (request == null) {
      throw bad("请求体不能为空");
    }
    return judge(
        request.skuRef(), request.requestedQty(), request.destinationCountryCode(),
        request.destinationRegionCode(), request.orderLegalEntityCode(), request.platformCode(),
        request.ruleCode(), request.at());
  }

  /**
   * 判定主体 —— 全模块**唯一**的判定实现。
   *
   * <p>{@link #simulate} 与 {@link #allocate} 都调它，保证同源。
   */
  private WarehouseAllocationSimulation judge(
      String skuRef,
      BigDecimal requestedQty,
      String destinationCountryCode,
      String destinationRegionCode,
      String orderLegalEntityCode,
      String platformCode,
      String ruleCode,
      OffsetDateTime at) {
    if (requestedQty == null || requestedQty.signum() <= 0) {
      throw bad("请求数量必须大于 0");
    }
    String sku = skuRef.trim();
    String country = destinationCountryCode.trim().toUpperCase(Locale.ROOT);
    if (country.length() != 2) {
      throw bad("目的国必须是两位国家码");
    }
    String region = blankTo(destinationRegionCode, "*");
    String orderEntity = orderLegalEntityCode.trim();
    BigDecimal requested = qty(requestedQty);
    OffsetDateTime now = at == null ? OffsetDateTime.now() : at;

    Rule rule = resolveRule(ruleCode, platformCode, country, now);

    // 仓库档案是候选全集。**空档案集直接报错而不是挂起**：
    // 没有仓可枚举时「挂起并列出每个仓被排除的原因」无从谈起，
    // 那是一个配置错误，不是业务上的「发不出去」。DB 的
    // CHECK(excluded_warehouse_count>0) 就是这条纪律的落点。
    List<WarehouseProfile> profiles = warehouseProfiles();
    if (profiles.isEmpty()) {
      throw bad(
          "本租户还没有任何仓库档案，无法执行分仓：先到「仓库档案」登记发货仓与它的货权法人，"
              + "再登记可用量与运费估算");
    }

    Set<String> excludedByRule = parseExclusions(rule);
    boolean crossAllowed = rule.fallbackPolicy == WarehouseFallbackPolicy.CROSS_LEGAL_ENTITY;
    boolean legalGate = rule.requireLegalEntityMatch && !crossAllowed;

    List<String> warnings = new ArrayList<>();
    List<Candidate> candidates = new ArrayList<>();
    for (WarehouseProfile profile : profiles) {
      candidates.add(
          buildCandidate(
              profile, rule, sku, requested, country, region, orderEntity, excludedByRule,
              legalGate, now, warnings));
    }

    // ---- 打分（只对可行候选；不可行候选各维记 0，"没测过"在求和里不该消失）
    List<Candidate> feasible = new ArrayList<>();
    for (Candidate c : candidates) {
      if (c.feasible()) {
        feasible.add(c);
      }
    }
    BigDecimal minCost = null;
    BigDecimal minLead = null;
    for (Candidate c : feasible) {
      minCost = smaller(minCost, c.shippingCost());
      minLead = smaller(minLead, c.leadTimeDays());
    }
    for (Candidate c : candidates) {
      c.score(minCost, minLead, rule, region, orderEntity, requested);
    }

    // ---- 选仓：主排序键由 selection_policy 定，分数与仓优先级做确定性 tiebreak。
    //     最后一定落到 warehouse_ref 字典序：同样输入必须在任何时候给出同一个结论。
    feasible.sort(
        Comparator.comparing((Candidate c) -> c.primaryKey(rule.selectionPolicy))
            .thenComparing(c -> c.totalScore().negate())
            .thenComparingInt(c -> c.profile().defaultPriority())
            .thenComparing(c -> c.profile().warehouseRef()));

    if (feasible.isEmpty()) {
      WarehouseHoldReason holdReason = aggregateHoldReason(candidates);
      String detail = exclusionDetail(candidates);
      if (candidates.stream().allMatch(c -> c.blockCode().equals(
          WarehouseCandidateBlockReason.LEGAL_ENTITY_MISMATCH.name()))) {
        warnings.add(
            "全部候选仓的货权法人都与订单主体「" + orderEntity
                + "」不符：从别的法人的仓发货意味着收入记在那个法人名下。"
                + "确实要跨法人发货时，请把规则的兜底策略显式设为 CROSS_LEGAL_ENTITY");
      }
      return new WarehouseAllocationSimulation(
          "", sku, requested, country, region, orderEntity, rule.ruleCode(),
          rule.selectionPolicy, rule.fallbackPolicy, rule.legalEntityPolicy,
          rule.requireLegalEntityMatch, rule.weightStock(), rule.weightCost(),
          rule.weightLeadTime(), rule.weightLegalEntity(), rule.weightRegion(), 0,
          null, null, false, null, null, null, null,
          WarehouseAllocationReasonCode.NO_ELIGIBLE_WAREHOUSE,
          "无可行仓，已挂起。" + (detail.isEmpty() ? "" : "被排除的候选：" + detail),
          holdReason, rows(candidates, null, requested, null), warnings, now);
    }

    Candidate chosen = feasible.get(0);
    BigDecimal allocatable =
        chosen.availableQty().compareTo(requested) >= 0 ? requested : chosen.availableQty();
    boolean partial = allocatable.compareTo(requested) < 0;
    if (partial && rule.fallbackPolicy != WarehouseFallbackPolicy.ALLOW_PARTIAL) {
      // 走到这里说明该仓被 INSUFFICIENT_STOCK 挡住了，不该出现在可行集里。
      // 兜底策略不是 ALLOW_PARTIAL 时，库存不足就是不可行 —— 不做隐式放宽。
      throw bad(
          "判定内部不一致：仓「" + chosen.profile().warehouseRef() + "」可用量 "
              + chosen.availableQty() + " 不足 " + requested
              + " 却进了可行集。兜底策略为 ALLOW_PARTIAL 时才允许部分分配");
    }
    boolean cross = !chosen.profile().legalEntityCode().equals(orderEntity);
    if (cross && legalGate) {
      // legalGate = require_legal_entity_match && 兜底未放开。
      // 规则里把 requireLegalEntityMatch 关掉**本身就是**一种「允许跨法人」的显式选择，
      // 所以只有闸门仍开着时才该报 —— 否则「不要求货权一致」这条配置会形同虚设。
      // DB 侧 guard_warehouse_allocation_candidate_legal_entity 用同一把尺子。
      throw bad(
          "选中的仓「" + chosen.profile().warehouseRef() + "」货权法人是「"
              + chosen.profile().legalEntityCode() + "」，与订单主体「" + orderEntity
              + "」不符，且本规则要求货权一致");
    }
    if (cross) {
      warnings.add(
          "本次分配跨法人发货：收入将记在法人「" + chosen.profile().legalEntityCode()
              + "」名下，而不是订单主体「" + orderEntity + "」");
    }

    WarehouseAllocationReasonCode reasonCode =
        partial
            ? WarehouseAllocationReasonCode.PARTIAL_ALLOCATION
            : switch (rule.selectionPolicy) {
              case LOWEST_COST -> WarehouseAllocationReasonCode.LOWEST_COST;
              case CLOSEST_LEAD_TIME -> WarehouseAllocationReasonCode.CLOSEST_LEAD_TIME;
              case HIGHEST_SCORE -> WarehouseAllocationReasonCode.SCORE_BASED;
            };
    return new WarehouseAllocationSimulation(
        "", sku, requested, country, region, orderEntity, rule.ruleCode(), rule.selectionPolicy,
        rule.fallbackPolicy, rule.legalEntityPolicy, rule.requireLegalEntityMatch, rule.weightStock(),
        rule.weightCost(), rule.weightLeadTime(), rule.weightLegalEntity(), rule.weightRegion(),
        feasible.size(), chosen.profile().warehouseRef(), chosen.profile().legalEntityCode(), cross,
        allocatable, settle(chosen.shippingCost()), chosen.leadTimeDays(), chosen.totalScore(),
        reasonCode, reasonOf(chosen, rule, requested, cross, feasible.size()), null,
        rows(candidates, chosen, requested, allocatable), warnings, now);
  }

  // ================================================================ 执行分配

  /**
   * 执行一次分仓分配：落决策 + 逐仓候选 + （挂起或差额）留痕 + 事件。
   *
   * <p><b>写入顺序</b>：头 → 候选 → 挂起 → 事件。头先落是因为候选与挂起都要对它建外键；
   * 而延迟约束触发器在**提交时**回表读当前行，所以「头先落、候选后补」这个顺序
   * 不会被中途的半成品状态误判 —— 换成即时触发器就会在写候选之前先报「没有候选依据」。
   *
   * <p><b>挂起不是错误</b>：无法分配时本方法正常返回一条 SUSPENDED 分配，
   * 而不是抛 400。把「这单发不出去」当成请求失败，调用方就分不清
   * 「系统坏了」与「业务上真的没仓可发」—— 前者要重试，后者要人介入。
   */
  @Transactional
  public WarehouseAllocationDecisionSaved allocate(
      WarehouseAllocationDecisionRequest request, UUID actorId) {
    if (request == null) {
      throw bad("请求体不能为空");
    }
    OffsetDateTime now = request.at() == null ? OffsetDateTime.now() : request.at();
    WarehouseAllocationSimulation simulation =
        judge(
            request.skuRef(), request.requestedQty(), request.destinationCountryCode(),
            request.destinationRegionCode(), request.orderLegalEntityCode(),
            request.platformCode(), request.ruleCode(), now);

    Rule rule = resolveRule(request.ruleCode(), request.platformCode(),
        request.destinationCountryCode().trim().toUpperCase(Locale.ROOT), now);
    String country = request.destinationCountryCode().trim().toUpperCase(Locale.ROOT);
    String region = blankTo(request.destinationRegionCode(), "*");
    String orderEntity = request.orderLegalEntityCode().trim();
    String chosenRef = nz(simulation.chosenWarehouseRef());
    String chosenEntity = nz(simulation.chosenLegalEntityCode());
    boolean suspended = simulation.chosenWarehouseRef() == null;
    WarehouseAllocationStatus status =
        suspended
            ? WarehouseAllocationStatus.SUSPENDED
            : (simulation.allocatedQty().compareTo(simulation.requestedQty()) < 0
                ? WarehouseAllocationStatus.PARTIAL
                : WarehouseAllocationStatus.ALLOCATED);
    String reason =
        notBlank(request.reason())
            ? request.reason().trim()
            : (suspended
                ? simulation.reason()
                : simulation.reason() + (status == WarehouseAllocationStatus.PARTIAL
                    ? "；按 ALLOW_PARTIAL 只分出 " + simulation.allocatedQty() + "，"
                        + "尚缺 " + simulation.requestedQty().subtract(simulation.allocatedQty())
                    : ""));

    UUID decisionId = UUID.randomUUID();
    sql.execute(
        DECISION_INSERT,
        decisionId, currentTenant(), request.orderRef().trim(), nz(request.orderLineRef()),
        request.skuRef().trim(), simulation.requestedQty(), rule.id(), rule.selectionPolicy.name(),
        rule.fallbackPolicy.name(), rule.legalEntityPolicy.name(), rule.requireLegalEntityMatch,
        country, region, orderEntity, status.name(), chosenRef.isEmpty() ? null : chosenRef,
        chosenEntity.isEmpty() ? null : chosenEntity, simulation.crossLegalEntity(),
        simulation.allocatedQty(), simulation.unitShippingCost(), simulation.leadTimeDays(),
        simulation.chosenTotalScore(), "USD", simulation.reasonCode().name(), reason, now, actorId);

    // 候选行：把每个仓当时的库存、运费、货权与五维得分抄进来。
    // 不可行的仓也要落 —— 「为什么没选它」是本模块最核心的一问。
    List<WarehouseAllocationCandidateRow> rows = simulation.candidates();
    UUID holdCandidateId = null;
    for (WarehouseAllocationCandidateRow row : rows) {
      UUID stockId = stockEstimateId(row.warehouseRef(), request.skuRef().trim());
      UUID shippingId =
          shippingEstimateId(row.warehouseRef(), request.skuRef().trim(), country, now);
      UUID candidateId = UUID.randomUUID();
      sql.execute(
          "insert into fulfillment_warehouse_allocation_candidate"
              + "(id,tenant_id,decision_id,warehouse_ref,legal_entity_code,region_code,"
              + "warehouse_priority,available_qty,unit_shipping_cost,lead_time_days,"
              + "stock_estimate_id,shipping_estimate_id,score_stock,score_cost,score_lead_time,"
              + "score_legal_entity,score_region,total_score,legal_entity_match,feasible,"
              + "infeasible_reason_code,infeasible_reason,selected)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          candidateId, currentTenant(), decisionId, row.warehouseRef(), row.legalEntityCode(),
          row.regionCode(), row.warehousePriority(), row.availableQty(), row.shippingCost(),
          row.leadTimeDays(), stockId, shippingId, row.scoreStock(), row.scoreCost(),
          row.scoreLeadTime(), row.scoreLegalEntity(), row.scoreRegion(), row.totalScore(),
          row.legalEntityMatch(), row.feasible(), nz(row.infeasibleReasonCode()),
          nz(row.infeasibleReason()), row.chosen());
      // 差额挂起要指到「选中的是哪个仓的那条候选」，让界面能从差额直接跳回依据。
      if (row.chosen()) {
        holdCandidateId = candidateId;
      }
    }

    // 挂起 / 差额：蓝图输出的第二项。没有它的分配提交不了（DB 延迟约束触发器会挡）。
    UUID holdId = null;
    WarehouseHoldReason holdReason = null;
    if (suspended) {
      holdId = UUID.randomUUID();
      holdReason = simulation.holdReason();
      String detail = exclusionDetailFromRows(rows);
      sql.execute(
          "insert into fulfillment_warehouse_allocation_hold"
              + "(id,tenant_id,decision_id,hold_reason,reason,exclusion_detail,"
              + "excluded_warehouse_count,hold_candidate_id,shortfall_qty,held_at,hold_source,"
              + "created_by_membership_id,created_at)"
              + " values (?,?,?,?,?,?,?,?,NULL,?::timestamptz,'SYSTEM',?,?::timestamptz)",
          holdId, currentTenant(), decisionId, holdReason.name(), reason, detail,
          (int) rows.stream().filter(r -> !r.feasible()).count(), null, now, actorId, now);
    } else if (status == WarehouseAllocationStatus.PARTIAL) {
      holdId = UUID.randomUUID();
      holdReason = WarehouseHoldReason.PARTIAL_SHORTFALL;
      BigDecimal shortfall = simulation.requestedQty().subtract(simulation.allocatedQty());
      sql.execute(
          "insert into fulfillment_warehouse_allocation_hold"
              + "(id,tenant_id,decision_id,hold_reason,reason,exclusion_detail,"
              + "excluded_warehouse_count,hold_candidate_id,shortfall_qty,held_at,hold_source,"
              + "created_by_membership_id,created_at)"
              + " values (?,?,?,'PARTIAL_SHORTFALL',?,?,?,?,?,?::timestamptz,'SYSTEM',?,"
              + "?::timestamptz)",
          holdId, currentTenant(), decisionId,
          "按 ALLOW_PARTIAL 从仓「" + chosenRef + "」分出 " + simulation.allocatedQty()
              + "，尚缺 " + shortfall + "，差额已挂起等待补货或改派",
          "选中仓可用量 " + simulation.allocatedQty() + " 小于需求 "
              + simulation.requestedQty() + "；差额由本记录承载，不显示为已全部分配",
          1, holdCandidateId, shortfall, now, actorId, now);
    }

    sql.execute(
        "insert into fulfillment_warehouse_allocation_event"
            + "(id,tenant_id,decision_id,event_kind,from_status,to_status,detail,actor_id,occurred_at)"
            + " values (?,?,?,?,'',?,?,?,?::timestamptz)",
        UUID.randomUUID(), currentTenant(), decisionId,
        suspended ? "SUSPENDED" : (status == WarehouseAllocationStatus.PARTIAL ? "PARTIAL" : "ALLOCATED"),
        status.name(), reason, actorId, now);

    return new WarehouseAllocationDecisionSaved(
        decisionId, request.orderRef().trim(), status, simulation.reasonCode(),
        chosenRef.isEmpty() ? null : chosenRef, chosenEntity.isEmpty() ? null : chosenEntity,
        simulation.crossLegalEntity(), simulation.allocatedQty(), holdId, holdReason, rows.size(),
        suspended
            ? "无可行仓，已挂起并逐条记录每个仓被排除的原因（" + holdReason.label() + "）"
            : (status == WarehouseAllocationStatus.PARTIAL
                ? "已部分分配 " + simulation.allocatedQty() + " / " + simulation.requestedQty()
                    + "，差额已挂起"
                : "已分配到仓「" + chosenRef + "」" + (simulation.crossLegalEntity()
                    ? "（跨法人：收入记在「" + chosenEntity + "」名下）"
                    : "（货权法人与订单主体一致）")));
  }

  // ================================================================ 分配读

  /** 分配列表（真分页；四组汇总额外单独查，不受分页影响）。 */
  public WarehouseAllocationDecisionPage pageDecisions(WarehouseAllocationDecisionQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    decisionConditions(query, where, binds);
    String sqlWhere = whereClause(where);
    long total =
        sql.fetchOne(
                "select count(*) c from fulfillment_warehouse_allocation_decision" + sqlWhere,
                binds.toArray())
            .get("c", Long.class);
    List<WarehouseAllocationDecisionRow> items =
        sql.fetch(
                "select " + DECISION_COLUMNS + " from fulfillment_warehouse_allocation_decision"
                    + sqlWhere
                    + " order by decided_at desc, id limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(WarehouseAllocationService::decisionRow);

    // 汇总与筛选条件一致但**不受分页影响**：看板上的数字不能因为翻页而变。
    long allocated =
        countWhere(sqlWhere, "status in ('ALLOCATED','CONFIRMED','RELEASED')", binds);
    long suspended = countWhere(sqlWhere, "status = 'SUSPENDED'", binds);
    long cross = countWhere(sqlWhere, "cross_legal_entity", binds);
    long partial = countWhere(sqlWhere, "status = 'PARTIAL'", binds);
    return new WarehouseAllocationDecisionPage(
        items, total, pageNo, size, allocated, suspended, cross, partial);
  }

  /**
   * 在**同一组筛选条件**上追加一个固定条件再计数。
   *
   * <p>拼接时必须分「有没有 where」两种情况：直接 {@code sqlWhere + " and ..."} 在
   * 无筛选条件时会拼出 {@code from t and cross_legal_entity} —— 一条语法错误 SQL，
   * 报错还指向一个完全无关的地方（9.3.2.2 踩过同类坑）。
   */
  private long countWhere(String sqlWhere, String extraCondition, List<Object> binds) {
    String tail = sqlWhere.isEmpty() ? " where " : " and ";
    return sql.fetchOne(
            "select count(*) c from fulfillment_warehouse_allocation_decision" + sqlWhere + tail
                + extraCondition,
            binds.toArray())
        .get("c", Long.class);
  }

  /**
   * 分配筛选条件（列表与四组汇总共用 —— 抽出来是因为五处条件必须永远一致：
   * 汇总少一个条件，看板上的数字就会与列表筛出来的条数对不上，而这种错位极难排查）。
   */
  private static void decisionConditions(
      WarehouseAllocationDecisionQuery query, List<String> where, List<Object> binds) {
    if (notBlank(query.orderRef())) {
      where.add("order_ref = ?");
      binds.add(query.orderRef().trim());
    }
    if (notBlank(query.skuRef())) {
      where.add("sku_ref = ?");
      binds.add(query.skuRef().trim());
    }
    if (notBlank(query.chosenWarehouseRef())) {
      where.add("chosen_warehouse_ref = ?");
      binds.add(query.chosenWarehouseRef().trim());
    }
    if (notBlank(query.chosenLegalEntityCode())) {
      where.add("chosen_legal_entity_code = ?");
      binds.add(query.chosenLegalEntityCode().trim());
    }
    if (notBlank(query.status())) {
      where.add("status = ?");
      binds.add(allocationStatus(query.status()).name());
    }
    if (notBlank(query.reasonCode())) {
      where.add("reason_code = ?");
      binds.add(reasonCode(query.reasonCode()).name());
    }
    if (query.crossLegalEntity() != null) {
      where.add(query.crossLegalEntity() ? "cross_legal_entity" : "not cross_legal_entity");
    }
    if (notBlank(query.keyword())) {
      where.add("(order_ref ilike ? or order_line_ref ilike ? or sku_ref ilike ?"
          + " or chosen_warehouse_ref ilike ? or reason ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      for (int i = 0; i < 5; i++) {
        binds.add(k);
      }
    }
  }

  /** 分配详情：决策 + 候选 + 挂起 + 事件流。跨租户在 RLS 下查不到 → 404。 */
  public WarehouseAllocationDecisionDetail decision(UUID decisionId) {
    var head =
        sql.fetchOne(
            "select " + DECISION_COLUMNS + " from fulfillment_warehouse_allocation_decision"
                + " where id = ?",
            decisionId);
    if (head == null) {
      throw notFound("分配 " + decisionId + " 不存在");
    }
    List<WarehouseAllocationCandidateRow> candidates =
        candidatesOf(decisionId, nz(head.get("chosen_warehouse_ref", String.class)));
    List<WarehouseAllocationHoldRow> holds = holdsOf(decisionId);
    List<WarehouseAllocationEventRow> events =
        sql.fetch(
                "select id,event_kind,from_status,to_status,detail,actor_id,occurred_at"
                    + " from fulfillment_warehouse_allocation_event"
                    + " where decision_id = ? order by occurred_at, id",
                decisionId)
            .map(WarehouseAllocationService::eventRow);
    return new WarehouseAllocationDecisionDetail(
        decisionRow(head), candidates, holds, events,
        "候选行是分配当时各仓的库存、运费、货权与五维得分快照："
            + "库存会被补上、运费估算会被改、规则权重会被调，历史候选不会被回算。");
  }

  private List<WarehouseAllocationCandidateRow> candidatesOf(
      UUID decisionId, String chosenWarehouseRef) {
    return sql.fetch(
            "select warehouse_ref,legal_entity_code,region_code,warehouse_priority,available_qty,"
                + "unit_shipping_cost,lead_time_days,score_stock,score_cost,score_lead_time,"
                + "score_legal_entity,score_region,total_score,legal_entity_match,feasible,"
                + "infeasible_reason_code,infeasible_reason,selected"
                + " from fulfillment_warehouse_allocation_candidate"
                + " where decision_id = ? order by selected desc, total_score desc, warehouse_ref",
            decisionId)
        .map(
            r -> {
              String ref = r.get("warehouse_ref", String.class);
              boolean chosen = ref.equals(chosenWarehouseRef);
              BigDecimal alloc =
                  chosen
                      ? sql.fetchOne(
                              "select allocated_qty from fulfillment_warehouse_allocation_decision"
                                  + " where id = ?",
                              decisionId)
                          .get("allocated_qty", BigDecimal.class)
                      : null;
              return new WarehouseAllocationCandidateRow(
                  ref, warehouseName(ref), r.get("legal_entity_code", String.class),
                  r.get("region_code", String.class), r.get("warehouse_priority", Integer.class),
                  r.get("available_qty", BigDecimal.class),
                  r.get("unit_shipping_cost", BigDecimal.class),
                  r.get("lead_time_days", BigDecimal.class), r.get("score_stock", BigDecimal.class),
                  r.get("score_cost", BigDecimal.class),
                  r.get("score_lead_time", BigDecimal.class),
                  r.get("score_legal_entity", BigDecimal.class),
                  r.get("score_region", BigDecimal.class), r.get("total_score", BigDecimal.class),
                  r.get("legal_entity_match", Boolean.class), r.get("feasible", Boolean.class),
                  nz(r.get("infeasible_reason_code", String.class)),
                  nz(r.get("infeasible_reason", String.class)), chosen, alloc);
            });
  }

  /** 推进分配状态（ALLOCATED|PARTIAL → CONFIRMED → RELEASED）。挂起与已下发是终态。 */
  @Transactional
  public WarehouseAllocationDecisionSaved advanceStatus(
      UUID decisionId, WarehouseAllocationStatusInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    WarehouseAllocationStatus target = allocationStatus(input.status());
    if (target != WarehouseAllocationStatus.CONFIRMED
        && target != WarehouseAllocationStatus.RELEASED) {
      throw bad("状态只能推进到 CONFIRMED 或 RELEASED");
    }
    var head =
        sql.fetchOne(
            "select order_ref,status,reason_code,reason,chosen_warehouse_ref,"
                + "chosen_legal_entity_code,cross_legal_entity,allocated_qty"
                + " from fulfillment_warehouse_allocation_decision where id = ?",
            decisionId);
    if (head == null) {
      throw notFound("分配 " + decisionId + " 不存在");
    }
    String current = head.get("status", String.class);
    if (WarehouseAllocationStatus.SUSPENDED.name().equals(current)
        || WarehouseAllocationStatus.RELEASED.name().equals(current)) {
      throw bad("分配当前状态「" + current + "」是终态，不允许再改状态：挂起的分配要重跑一次分配，"
          + "已下发的分配已经被下游取走");
    }
    String module = nz(input.releasedToModule());
    if (target == WarehouseAllocationStatus.RELEASED
        && !module.equals("MODULE_9_3_3_1") && !module.equals("MODULE_8_OUTBOUND")) {
      throw bad("下发给哪个模块必须显式说明（MODULE_9_3_3_1 履约批次 / MODULE_8_OUTBOUND 出库单），"
          + "收到「" + module + "」");
    }
    if (target == WarehouseAllocationStatus.RELEASED
        && !WarehouseAllocationStatus.CONFIRMED.name().equals(current)) {
      throw bad("分配当前状态是「" + current + "」，必须先确认才能下发："
          + "未确认就把货放给下游，下游无法判断这批货是不是已经被人改过主意");
    }
    // 状态推进走 SECURITY DEFINER 窄函数：决策表对 trade_app 已 REVOKE UPDATE/DELETE，
    // append-only 才是真的（见迁移第 9.9 节）。
    sql.execute(
        "select advance_warehouse_allocation_status(?::uuid, ?, ?::uuid, ?)",
        decisionId, target.name(), actorId, module);
    sql.execute(
        "insert into fulfillment_warehouse_allocation_event"
            + "(id,tenant_id,decision_id,event_kind,from_status,to_status,detail,actor_id,occurred_at)"
            + " values (?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(), currentTenant(), decisionId, target.name(), current, target.name(),
        target == WarehouseAllocationStatus.CONFIRMED
            ? "分配已确认"
            : "分配已下发给 " + module + "（本模块不预占上游库存，预占由下游走 warehouse 授权 API）",
        actorId, OffsetDateTime.now());
    return new WarehouseAllocationDecisionSaved(
        decisionId, head.get("order_ref", String.class), target,
        reasonCode(head.get("reason_code", String.class)), head.get("chosen_warehouse_ref", String.class),
        head.get("chosen_legal_entity_code", String.class), head.get("cross_legal_entity", Boolean.class),
        head.get("allocated_qty", BigDecimal.class), null, null, 0,
        target == WarehouseAllocationStatus.CONFIRMED ? "分配已确认" : "分配已下发");
  }

  // ================================================================ 挂起清单

  /**
   * 挂起清单（真分页）。这是「哪几单发不出去」与「该去补什么」的查询入口。
   *
   * <p>每一行都带整体挂起原因与**全量**的逐仓排除清单 ——
   * 运营从这里能直接看出是货权不符、缺库存估算、还是运费没配。
   */
  public WarehouseAllocationHoldPage pageHolds(WarehouseAllocationHoldQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (notBlank(query.orderRef())) {
      where.add("d.order_ref = ?");
      binds.add(query.orderRef().trim());
    }
    if (query.decisionId() != null) {
      where.add("h.decision_id = ?");
      binds.add(query.decisionId());
    }
    if (notBlank(query.holdReason())) {
      where.add("h.hold_reason = ?");
      binds.add(holdReason(query.holdReason()).name());
    }
    if (notBlank(query.keyword())) {
      where.add("(d.order_ref ilike ? or h.reason ilike ? or h.exclusion_detail ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      for (int i = 0; i < 3; i++) {
        binds.add(k);
      }
    }
    String sqlWhere = whereClause(where);
    String from =
        " from fulfillment_warehouse_allocation_hold h"
            + " join fulfillment_warehouse_allocation_decision d"
            + " on d.tenant_id = h.tenant_id and d.id = h.decision_id";
    long total = sql.fetchOne("select count(*) c" + from + sqlWhere, binds.toArray()).get("c", Long.class);
    List<WarehouseAllocationHoldRow> items =
        sql.fetch(
                "select h.id,h.decision_id,d.order_ref,h.hold_reason,h.reason,h.exclusion_detail,"
                    + "h.excluded_warehouse_count,h.hold_candidate_id,h.shortfall_qty,h.held_at,"
                    + "h.hold_source"
                    + from
                    + sqlWhere
                    + " order by h.held_at desc, h.id limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(WarehouseAllocationService::holdRow);
    return new WarehouseAllocationHoldPage(items, total, pageNo, size);
  }

  private List<WarehouseAllocationHoldRow> holdsOf(UUID decisionId) {
    return sql.fetch(
            "select h.id,h.decision_id,d.order_ref,h.hold_reason,h.reason,h.exclusion_detail,"
                + "h.excluded_warehouse_count,h.hold_candidate_id,h.shortfall_qty,h.held_at,"
                + "h.hold_source"
                + " from fulfillment_warehouse_allocation_hold h"
                + " join fulfillment_warehouse_allocation_decision d"
                + " on d.tenant_id = h.tenant_id and d.id = h.decision_id"
                + " where h.decision_id = ? order by h.held_at, h.id",
            decisionId)
        .map(WarehouseAllocationService::holdRow);
  }

  // ================================================================ 判定内部结构

  /** 规则快照（判定期间只用这一份，不回表重读，避免并发改规则导致前后不一致）。 */
  private record Rule(
      UUID id,
      String ruleCode,
      WarehouseSelectionPolicy selectionPolicy,
      WarehouseFallbackPolicy fallbackPolicy,
      WarehouseLegalEntityPolicy legalEntityPolicy,
      boolean requireLegalEntityMatch,
      String preferredLegalEntityCode,
      BigDecimal weightStock,
      BigDecimal weightCost,
      BigDecimal weightLeadTime,
      BigDecimal weightLegalEntity,
      BigDecimal weightRegion,
      BigDecimal maxLeadTimeDays,
      String excludedWarehouseRefs) {}

  /** 仓库档案快照（**货权归属在这里**，候选的法人与区域一律取自它，不在候选行里另填）。 */
  private record WarehouseProfile(
      String warehouseRef,
      String warehouseName,
      String legalEntityCode,
      String regionCode,
      int defaultPriority,
      boolean active) {}

  /** 一个候选仓的完整评估结果。 */
  private final class Candidate {
    private final WarehouseProfile profile;
    private final BigDecimal availableQty;
    private final BigDecimal shippingCost;
    private final BigDecimal leadTimeDays;
    private final UUID stockId;
    private final UUID shippingId;
    private final String blockCode;
    private final String blockReason;
    private final boolean legalEntityMatch;
    private BigDecimal scoreStock = BigDecimal.ZERO;
    private BigDecimal scoreCost = BigDecimal.ZERO;
    private BigDecimal scoreLeadTime = BigDecimal.ZERO;
    private BigDecimal scoreLegalEntity = BigDecimal.ZERO;
    private BigDecimal scoreRegion = BigDecimal.ZERO;
    private BigDecimal totalScore = BigDecimal.ZERO;

    Candidate(
        WarehouseProfile profile,
        BigDecimal availableQty,
        BigDecimal shippingCost,
        BigDecimal leadTimeDays,
        UUID stockId,
        UUID shippingId,
        String blockCode,
        String blockReason,
        boolean legalEntityMatch) {
      this.profile = profile;
      this.availableQty = availableQty;
      this.shippingCost = shippingCost;
      this.leadTimeDays = leadTimeDays;
      this.stockId = stockId;
      this.shippingId = shippingId;
      this.blockCode = blockCode;
      this.blockReason = blockReason;
      this.legalEntityMatch = legalEntityMatch;
    }

    boolean feasible() {
      return blockCode.isEmpty();
    }

    String blockCode() {
      return blockCode;
    }

    String blockReason() {
      return blockReason;
    }

    BigDecimal scoreStock() {
      return scoreStock;
    }

    BigDecimal scoreCost() {
      return scoreCost;
    }

    BigDecimal scoreLeadTime() {
      return scoreLeadTime;
    }

    BigDecimal scoreLegalEntity() {
      return scoreLegalEntity;
    }

    BigDecimal scoreRegion() {
      return scoreRegion;
    }

    WarehouseProfile profile() {
      return profile;
    }

    BigDecimal availableQty() {
      return availableQty;
    }

    BigDecimal shippingCost() {
      return shippingCost;
    }

    BigDecimal leadTimeDays() {
      return leadTimeDays;
    }

    BigDecimal totalScore() {
      return totalScore;
    }

    /** 主排序键：随 selection_policy 变，其余情况一律用 0 占位（由 thenComparing 兜底）。 */
    BigDecimal primaryKey(WarehouseSelectionPolicy policy) {
      BigDecimal value =
          switch (policy) {
            case LOWEST_COST -> shippingCost;
            case CLOSEST_LEAD_TIME -> leadTimeDays;
            // HIGHEST_SCORE 用负分：升序比较下负分大的（即分小的）排后面。
            case HIGHEST_SCORE -> totalScore == null ? BigDecimal.ZERO : totalScore.negate();
          };
      return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * 五维打分。
     *
     * <p>归一化口径（都要能向运营解释，所以刻意不用「拍脑袋的整数」）：
     * <ul>
     *   <li>库存 = min(1, 可用量 / 需求量)；
     *   <li>成本 = 本次最低运费 / 本仓运费（最便宜的那个拿满分，其余按比例）；
     *   <li>时效 = 本次最短时效 / 本仓时效；时效未知记 0（不是满分，也不是 NaN）；
     *   <li>货权按 legal_entity_policy（见 buildCandidate 的说明）；
     *   <li>地区 = 与买家地区一致 1.0，同国不同区 0.5。
     * </ul>
     */
    void score(
        BigDecimal minCost,
        BigDecimal minLead,
        Rule rule,
        String orderRegion,
        String orderEntity,
        BigDecimal requested) {
      if (!feasible()) {
        return;
      }
      // 库存 = min(1, 可用量 / 需求量)。需求量才是「够不够」的分母 ——
      // 拿可用量自己比自己会永远得满分，让库存维度彻底失效。
      scoreStock =
          availableQty.compareTo(requested) >= 0
              ? BigDecimal.ONE.setScale(SCORE_SCALE)
              : availableQty.divide(requested, SCORE_SCALE, RoundingMode.HALF_UP);
      scoreCost =
          minCost == null || minCost.signum() == 0
              ? BigDecimal.ONE.setScale(SCORE_SCALE)
              : scoreOf(minCost, shippingCost);
      scoreLeadTime =
          minLead == null || leadTimeDays == null || leadTimeDays.signum() == 0
              ? BigDecimal.ZERO.setScale(SCORE_SCALE)
              : scoreOf(minLead, leadTimeDays);
      scoreLegalEntity =
          switch (rule.legalEntityPolicy) {
            case NEUTRAL -> BigDecimal.ONE.setScale(SCORE_SCALE);
            case ORDER_OWNER_FIRST ->
                profile.legalEntityCode().equals(orderEntity)
                    ? BigDecimal.ONE.setScale(SCORE_SCALE)
                    : BigDecimal.ZERO.setScale(SCORE_SCALE);
            // 指定法人优先：不匹配的仓拿 0.4 而不是 0 —— 运营显式指定了偏好法人，
            // 但没说过「非它一律不可发」，给 0 会让偏好变成硬性排他。
            case PREFERRED_FIRST ->
                profile.legalEntityCode().equals(rule.preferredLegalEntityCode())
                    ? BigDecimal.ONE.setScale(SCORE_SCALE)
                    : new BigDecimal("0.4000");
          };
      scoreRegion =
          profile.regionCode().equals(orderRegion)
              ? BigDecimal.ONE.setScale(SCORE_SCALE)
              : new BigDecimal("0.5000");
      totalScore =
          rule.weightStock()
              .multiply(scoreStock)
              .add(rule.weightCost().multiply(scoreCost))
              .add(rule.weightLeadTime().multiply(scoreLeadTime))
              .add(rule.weightLegalEntity().multiply(scoreLegalEntity))
              .add(rule.weightRegion().multiply(scoreRegion))
              .setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    }

    /** 库存得分已在上方按 min(1, 可用/需求) 算出，这里不再需要比例辅助函数。 */

    /** 「相对最优的比例」型得分：best / value，上限 1。 */
    private BigDecimal scoreOf(BigDecimal best, BigDecimal value) {
      if (value == null || value.signum() <= 0) {
        return BigDecimal.ONE.setScale(SCORE_SCALE);
      }
      BigDecimal ratio = best.divide(value, SCORE_SCALE + 2, RoundingMode.HALF_UP);
      return ratio.min(BigDecimal.ONE.setScale(SCORE_SCALE)).setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    }

    WarehouseAllocationCandidateRow toRow(boolean chosen, BigDecimal requested, BigDecimal allocated) {
      return new WarehouseAllocationCandidateRow(
          profile.warehouseRef(),
          profile.warehouseName(),
          profile.legalEntityCode(),
          profile.regionCode(),
          profile.defaultPriority(),
          availableQty,
          shippingCost,
          leadTimeDays,
          scoreStock,
          scoreCost,
          scoreLeadTime,
          scoreLegalEntity,
          scoreRegion,
          totalScore,
          legalEntityMatch,
          feasible(),
          blockCode,
          blockReason,
          chosen,
          chosen ? allocated : null);
    }
  }

  /** 构造一个候选仓，并按「资格 → 库存 → 成本」的顺序逐层判定。 */
  private Candidate buildCandidate(
      WarehouseProfile profile,
      Rule rule,
      String sku,
      BigDecimal requested,
      String country,
      String orderRegion,
      String orderEntity,
      Set<String> excludedByRule,
      boolean legalGate,
      OffsetDateTime at,
      List<String> warnings) {
    boolean entityMatch = profile.legalEntityCode().equals(orderEntity);
    String block = "";
    String reason = "";

    // ---- 第 1 层：资格（启用 / 规则排除 / 货权硬闸）
    if (!profile.active()) {
      block = WarehouseCandidateBlockReason.WAREHOUSE_INACTIVE.name();
      reason = "仓「" + profile.warehouseName() + "」已停用";
    } else if (excludedByRule.contains(profile.warehouseRef().toUpperCase(Locale.ROOT))) {
      block = WarehouseCandidateBlockReason.WAREHOUSE_EXCLUDED.name();
      reason = "仓「" + profile.warehouseName() + "」在本规则的排除清单里";
    } else if (legalGate && !entityMatch) {
      block = WarehouseCandidateBlockReason.LEGAL_ENTITY_MISMATCH.name();
      reason = "仓「" + profile.warehouseName() + "」货权法人是「" + profile.legalEntityCode()
          + "」，与订单货权主体「" + orderEntity + "」不符：从别的法人的仓发货意味着收入记在那个法人名下";
    }

    // ---- 第 2 层：库存
    Stock latest = latestStock(profile.warehouseRef(), sku, at);
    if (block.isEmpty()) {
      if (latest.id() == null) {
        block = WarehouseCandidateBlockReason.NO_STOCK_RECORD.name();
        reason = latest.future()
            ? "仓「" + profile.warehouseName() + "」的库存估算采集于 " + latest.collectedAt()
                + "，晚于决策时刻：那是未来数据，拿不到当前值不等于有货"
            : "仓「" + profile.warehouseName() + "」没有库存估算：0 与「没测过」在决策上不等价";
      } else if (latest.availableQty().signum() == 0) {
        block = WarehouseCandidateBlockReason.STOCK_ZERO.name();
        reason = "仓「" + profile.warehouseName() + "」可用量为 0";
      } else if (latest.availableQty().compareTo(requested) < 0
          && rule.fallbackPolicy != WarehouseFallbackPolicy.ALLOW_PARTIAL) {
        block = WarehouseCandidateBlockReason.INSUFFICIENT_STOCK.name();
        reason = "仓「" + profile.warehouseName() + "」可用量 " + latest.availableQty()
            + " 不足需求 " + requested;
      }
    }

    // ---- 第 3 层：成本（运费与时效估算）
    Shipping shipping = effectiveShipping(profile.warehouseRef(), sku, country, at);
    if (block.isEmpty() && shipping.id() == null) {
      block = WarehouseCandidateBlockReason.NO_SHIPPING_ESTIMATE.name();
      reason = "仓「" + profile.warehouseName() + "」没有发往 " + country
          + " 的运费与时效估算：没有成本口径就不能参与分仓比较";
    }
    if (block.isEmpty() && rule.maxLeadTimeDays() != null && shipping.leadTimeDays() != null
        && shipping.leadTimeDays().compareTo(rule.maxLeadTimeDays()) > 0) {
      block = WarehouseCandidateBlockReason.LEAD_TIME_EXCEEDED.name();
      reason = "仓「" + profile.warehouseName() + "」时效 " + shipping.leadTimeDays()
          + " 天超出本规则的时效承诺 " + rule.maxLeadTimeDays() + " 天";
    }
    if (block.isEmpty() && shipping.leadTimeDays() == null) {
      warnings.add(
          "仓「" + profile.warehouseName() + "」的时效未知，本模块按 0 分计入（不当 0 天，"
              + "也不当最优）");
    }
    if (block.isEmpty() && !entityMatch) {
      warnings.add(
          "仓「" + profile.warehouseName() + "」货权法人「" + profile.legalEntityCode()
              + "」与订单主体「" + orderEntity
              + "」不符：本次按规则 CROSS_LEGAL_ENTITY 放行，收入将记在该法人名下");
    }
    if (orderRegion.equals("*")) {
      warnings.add("订单未带买家地区，地区维度按「与 * 之外都不匹配」记 0.5 分");
    }

    return new Candidate(
        profile,
        latest.availableQty(),
        shipping.cost(),
        shipping.leadTimeDays(),
        latest.id(),
        shipping.id(),
        block,
        reason,
        entityMatch);
  }

  // ================================================================ 数据访问小工具

  /** 命中规则：显式指定 ruleCode 时按它取，否则按适用范围 + 优先级（数字小者优先）。 */
  private Rule resolveRule(
      String ruleCode, String platformCode, String country, OffsetDateTime at) {
    Record row;
    if (notBlank(ruleCode)) {
      row =
          sql.fetchOne(
              "select " + RULE_COLUMNS + " from fulfillment_warehouse_allocation_rule"
                  + " where rule_code = ?",
              ruleCode.trim());
      if (row == null) {
        throw bad("规则「" + ruleCode.trim() + "」不存在");
      }
    } else {
      String platform = blankTo(platformCode, "*");
      row =
          sql.fetchOne(
              "select " + RULE_COLUMNS + " from fulfillment_warehouse_allocation_rule"
                  + " where (scope_platform_code = '*' or scope_platform_code = ?)"
                  + "   and (scope_destination_country_code = '*'"
                  + "        or scope_destination_country_code = ?)"
                  + "   and effective_from <= ?::timestamptz"
                  + "   and (effective_to is null or effective_to > ?::timestamptz)"
                  + " order by priority limit 1",
              platform, country, at, at);
      if (row == null) {
        throw bad(
            "没有命中任何生效的分仓规则（平台 " + platform + " / 目的国 " + country
                + "）：先到「分仓规则」配一条，规则不存在时执行分配等于凭空决定从哪个仓发");
      }
    }
    OffsetDateTime from = row.get("effective_from", OffsetDateTime.class);
    OffsetDateTime to = row.get("effective_to", OffsetDateTime.class);
    if (from.isAfter(at) || (to != null && !to.isAfter(at))) {
      throw bad("规则「" + row.get("rule_code", String.class) + "」在 " + at + " 这一刻尚未生效或已失效");
    }
    return new Rule(
        row.get("id", UUID.class),
        row.get("rule_code", String.class),
        selectionPolicy(row.get("selection_policy", String.class)),
        fallbackPolicy(row.get("fallback_policy", String.class)),
        legalEntityPolicy(row.get("legal_entity_policy", String.class)),
        row.get("require_legal_entity_match", Boolean.class),
        nz(row.get("preferred_legal_entity_code", String.class)),
        row.get("weight_stock", BigDecimal.class),
        row.get("weight_cost", BigDecimal.class),
        row.get("weight_lead_time", BigDecimal.class),
        row.get("weight_legal_entity", BigDecimal.class),
        row.get("weight_region", BigDecimal.class),
        row.get("max_lead_time_days", BigDecimal.class),
        nz(row.get("excluded_warehouse_refs", String.class)));
  }

  /** 规则里的排除清单。**去空白 + 大写去重**：手打的清单里多个空格与空项很常见，
   *  不去重会让「排除清单里没写的那个仓」被误伤。 */
  private static Set<String> parseExclusions(Rule rule) {
    Set<String> excluded = new LinkedHashSet<>();
    if (rule.excludedWarehouseRefs.isBlank()) {
      return excluded;
    }
    for (String raw : rule.excludedWarehouseRefs.split(",")) {
      String ref = raw.trim();
      if (!ref.isEmpty()) {
        excluded.add(ref.toUpperCase(Locale.ROOT));
      }
    }
    return excluded;
  }

  private List<WarehouseProfile> warehouseProfiles() {
    return sql.fetch(
            "select warehouse_ref,warehouse_name,legal_entity_code,region_code,default_priority,"
                + "active from fulfillment_warehouse_allocation_warehouse")
        .map(
            r ->
                new WarehouseProfile(
                    r.get("warehouse_ref", String.class), r.get("warehouse_name", String.class),
                    r.get("legal_entity_code", String.class), r.get("region_code", String.class),
                    r.get("default_priority", Integer.class), r.get("active", Boolean.class)));
  }

  private void requireWarehouse(String ref) {
    var row =
        sql.fetchOne(
            "select warehouse_ref from fulfillment_warehouse_allocation_warehouse"
                + " where warehouse_ref = ?",
            ref);
    if (row == null) {
      throw bad(
          "仓库「" + ref + "」不在档案里：货权归属是仓库的属性，没有档案就没有货权可依据，"
              + "先建档再登记库存或运费");
    }
  }

  private record Stock(UUID id, BigDecimal availableQty, OffsetDateTime collectedAt, boolean future) {}

  private Stock latestStock(String warehouseRef, String sku, OffsetDateTime at) {
    Record row =
        sql.fetchOne(
            "select id,available_qty,collected_at"
                + " from fulfillment_warehouse_allocation_stock_estimate"
                + " where warehouse_ref = ? and sku_ref = ?",
            warehouseRef, sku);
    if (row == null) {
      return new Stock(null, null, null, false);
    }
    OffsetDateTime collected = row.get("collected_at", OffsetDateTime.class);
    // 采集时间在**决策时刻之后**的快照不能用：那是未来数据，
    // 用它判「够不够」等于让决策依赖尚未发生的事实。
    // 比较基准是判定的 at（而不是 now）—— 回放历史决策时 now 会把当时的库存全判成未来。
    if (collected.isAfter(at)) {
      return new Stock(null, null, collected, true);
    }
    return new Stock(row.get("id", UUID.class), row.get("available_qty", BigDecimal.class),
        collected, false);
  }

  private record Shipping(UUID id, BigDecimal cost, BigDecimal leadTimeDays) {}

  private Shipping effectiveShipping(String warehouseRef, String sku, String country,
      OffsetDateTime at) {
    Record row =
        sql.fetchOne(
            "select id,shipping_cost,lead_time_days"
                + " from fulfillment_warehouse_allocation_shipping_estimate"
                + " where warehouse_ref = ? and sku_ref = ? and destination_country_code = ?"
                + "   and effective_from <= ?::timestamptz"
                + "   and (effective_to is null or effective_to > ?::timestamptz)"
                + " order by effective_from desc limit 1",
            warehouseRef, sku, country, at, at);
    if (row == null) {
      return new Shipping(null, null, null);
    }
    return new Shipping(
        row.get("id", UUID.class), row.get("shipping_cost", BigDecimal.class),
        row.get("lead_time_days", BigDecimal.class));
  }

  private UUID stockEstimateId(String warehouseRef, String sku) {
    var row =
        sql.fetchOne(
            "select id from fulfillment_warehouse_allocation_stock_estimate"
                + " where warehouse_ref = ? and sku_ref = ?",
            warehouseRef, sku);
    return row == null ? null : row.get(0, UUID.class);
  }

  private UUID shippingEstimateId(
      String warehouseRef, String sku, String country, OffsetDateTime at) {
    var row =
        sql.fetchOne(
            "select id from fulfillment_warehouse_allocation_shipping_estimate"
                + " where warehouse_ref = ? and sku_ref = ? and destination_country_code = ?"
                + "   and effective_from <= ?::timestamptz"
                + "   and (effective_to is null or effective_to > ?::timestamptz)"
                + " order by effective_from desc limit 1",
            warehouseRef, sku, country, at, at);
    return row == null ? null : row.get(0, UUID.class);
  }

  private String warehouseName(String warehouseRef) {
    return sql.fetchOne(
            "select warehouse_name from fulfillment_warehouse_allocation_warehouse"
                + " where warehouse_ref = ?",
            warehouseRef)
        .get(0, String.class);
  }

  // ================================================================ 文案与聚合

  /**
   * 「为什么选了 A 仓」—— 含货权维度的贡献。
   *
   * <p>刻意把每一维的「得分×权重」都写出来：运营追问「为什么不是 B 仓」时，
   * 能直接照这句话去比，而不是反过来问系统「你到底怎么算的」。
   */
  private String reasonOf(
      Candidate chosen, Rule rule, BigDecimal requested, boolean cross, int feasibleCount) {
    StringBuilder sb = new StringBuilder();
    sb.append("按「").append(rule.selectionPolicy.label()).append("」策略（规则 ")
        .append(rule.ruleCode()).append("）在 ").append(feasibleCount).append(" 个可行仓中选定 ")
        .append(chosen.profile().warehouseRef()).append("：");
    sb.append("可用量 ").append(chosen.availableQty()).append(" 覆盖需求 ").append(requested)
        .append("（库存 ").append(chosen.scoreStock()).append("×权重 ")
        .append(rule.weightStock()).append("）");
    sb.append("；运费 ").append(chosen.shippingCost()).append("（成本 ").append(chosen.scoreCost())
        .append("×权重 ").append(rule.weightCost()).append("）");
    sb.append("；时效 ").append(chosen.leadTimeDays() == null ? "未知" : chosen.leadTimeDays())
        .append(" 天（时效 ").append(chosen.scoreLeadTime()).append("×权重 ")
        .append(rule.weightLeadTime()).append("）");
    sb.append("；货权法人 ").append(chosen.profile().legalEntityCode())
        .append(chosen.legalEntityMatch ? "（与订单主体一致" : "（与订单主体不符")
        .append("，货权得分 ").append(chosen.scoreLegalEntity()).append("×权重 ")
        .append(rule.weightLegalEntity()).append("）");
    sb.append("；区域 ").append(chosen.profile().regionCode()).append("（地区 ")
        .append(chosen.scoreRegion()).append("×权重 ").append(rule.weightRegion()).append("）");
    sb.append("；综合分 ").append(chosen.totalScore()).append("。");
    if (cross) {
      sb.append("注意：本次为跨法人发货，收入将记在「").append(chosen.profile().legalEntityCode())
          .append("」名下。");
    }
    return sb.toString();
  }

  /** 整体挂起原因：按「全体一致的排除原因」归纳，否则归到笼统的无可行仓。 */
  private WarehouseHoldReason aggregateHoldReason(List<Candidate> candidates) {
    List<String> codes =
        candidates.stream().map(Candidate::blockCode).distinct().sorted().toList();
    if (codes.size() == 1) {
      String only = codes.get(0);
      if (only.equals(WarehouseCandidateBlockReason.LEGAL_ENTITY_MISMATCH.name())) {
        return WarehouseHoldReason.BLOCKED_BY_LEGAL_ENTITY;
      }
      if (only.equals(WarehouseCandidateBlockReason.WAREHOUSE_EXCLUDED.name())
          || only.equals(WarehouseCandidateBlockReason.WAREHOUSE_INACTIVE.name())) {
        return WarehouseHoldReason.BLOCKED_BY_RULE;
      }
      if (only.equals(WarehouseCandidateBlockReason.NO_STOCK_RECORD.name())) {
        return WarehouseHoldReason.NO_STOCK_RECORD;
      }
      if (only.equals(WarehouseCandidateBlockReason.NO_SHIPPING_ESTIMATE.name())) {
        return WarehouseHoldReason.NO_SHIPPING_ESTIMATE;
      }
      if (only.equals(WarehouseCandidateBlockReason.STOCK_ZERO.name())
          || only.equals(WarehouseCandidateBlockReason.INSUFFICIENT_STOCK.name())
          || only.equals(WarehouseCandidateBlockReason.LEAD_TIME_EXCEEDED.name())) {
        return WarehouseHoldReason.INSUFFICIENT_STOCK;
      }
    }
    return WarehouseHoldReason.NO_ELIGIBLE_WAREHOUSE;
  }

  /** 逐仓排除清单：「仓A=无库存估算；仓B=货权法人不符」。 */
  private String exclusionDetail(List<Candidate> candidates) {
    StringBuilder sb = new StringBuilder();
    for (Candidate c : candidates) {
      if (c.feasible()) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append("；");
      }
      sb.append(c.profile().warehouseRef()).append("=").append(c.blockReason());
    }
    return sb.toString();
  }

  private String exclusionDetailFromRows(List<WarehouseAllocationCandidateRow> rows) {
    StringBuilder sb = new StringBuilder();
    for (WarehouseAllocationCandidateRow r : rows) {
      if (r.feasible()) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append("；");
      }
      sb.append(r.warehouseRef()).append("=").append(r.infeasibleReason());
    }
    return sb.toString();
  }

  private List<WarehouseAllocationCandidateRow> rows(
      List<Candidate> candidates, Candidate chosen, BigDecimal requested, BigDecimal allocated) {
    List<WarehouseAllocationCandidateRow> out = new ArrayList<>();
    for (Candidate c : candidates) {
      boolean isChosen = chosen != null && c == chosen;
      out.add(c.toRow(isChosen, requested, isChosen ? allocated : null));
    }
    return out;
  }

  // ================================================================ 决策写入语句

  private static final String DECISION_COLUMNS =
      "id,order_ref,order_line_ref,sku_ref,requested_qty,destination_country_code,"
          + "destination_region_code,order_legal_entity_code,chosen_warehouse_ref,"
          + "chosen_legal_entity_code,cross_legal_entity,allocated_qty,unit_shipping_cost,"
          + "lead_time_days,chosen_total_score,currency_code,selection_policy,fallback_policy,"
          + "legal_entity_policy,reason_code,reason,status,decided_at,confirmed_at,released_at,"
          + "created_at";

  /** 列清单与需 cast 的列。cast 由列名推导，不手写等长数组 —— 手写的那份曾经差一位。 */
  private static final String[] DECISION_COLUMNS_IN = {
    "id", "tenant_id", "order_ref", "order_line_ref", "sku_ref", "requested_qty", "rule_id",
    "selection_policy", "fallback_policy", "legal_entity_policy", "require_legal_entity_match",
    "destination_country_code", "destination_region_code", "order_legal_entity_code", "status",
    "chosen_warehouse_ref", "chosen_legal_entity_code", "cross_legal_entity", "allocated_qty",
    "unit_shipping_cost", "lead_time_days", "chosen_total_score", "currency_code",
    "reason_code", "reason", "decided_at", "created_by_membership_id"
  };

  /** 需要 {@code ?::timestamptz} 的列（其余是普通绑定）。 */
  private static final Set<String> DECISION_TS_COLUMNS = Set.of("decided_at");

  private static final String DECISION_INSERT = decisionInsert();

  private static String decisionInsert() {
    StringBuilder columns = new StringBuilder();
    StringBuilder values = new StringBuilder();
    for (int i = 0; i < DECISION_COLUMNS_IN.length; i++) {
      if (i > 0) {
        columns.append(',');
        values.append(',');
      }
      columns.append(DECISION_COLUMNS_IN[i]);
      values.append('?').append(DECISION_TS_COLUMNS.contains(DECISION_COLUMNS_IN[i]) ? "::timestamptz" : "");
    }
    return "insert into fulfillment_warehouse_allocation_decision (" + columns + ") values ("
        + values + ")";
  }

  // ================================================================ 映射与工具

  private static final String RULE_COLUMNS =
      "id,rule_code,rule_name,priority,scope_platform_code,scope_destination_country_code,"
          + "selection_policy,fallback_policy,weight_stock,weight_cost,weight_lead_time,"
          + "weight_legal_entity,weight_region,require_legal_entity_match,legal_entity_policy,"
          + "preferred_legal_entity_code,max_lead_time_days,currency_code,excluded_warehouse_refs,"
          + "effective_from,effective_to,notes,created_at,updated_at";

  private static final String WAREHOUSE_COLUMNS =
      "id,warehouse_ref,warehouse_name,legal_entity_code,region_code,default_priority,active,"
          + "notes,created_at,updated_at";

  private static final String SHIPPING_COLUMNS =
      "id,warehouse_ref,sku_ref,destination_country_code,shipping_cost,lead_time_days,"
          + "currency_code,cost_source,source_reference,effective_from,effective_to,notes,"
          + "created_at,updated_at";

  private static WarehouseAllocationRuleRow ruleRow(Record r) {
    return new WarehouseAllocationRuleRow(
        r.get("id", UUID.class), r.get("rule_code", String.class),
        r.get("rule_name", String.class), r.get("priority", Integer.class),
        r.get("scope_platform_code", String.class),
        r.get("scope_destination_country_code", String.class),
        selectionPolicy(r.get("selection_policy", String.class)),
        fallbackPolicy(r.get("fallback_policy", String.class)),
        r.get("weight_stock", BigDecimal.class), r.get("weight_cost", BigDecimal.class),
        r.get("weight_lead_time", BigDecimal.class), r.get("weight_legal_entity", BigDecimal.class),
        r.get("weight_region", BigDecimal.class),
        r.get("require_legal_entity_match", Boolean.class),
        legalEntityPolicy(r.get("legal_entity_policy", String.class)),
        r.get("preferred_legal_entity_code", String.class),
        r.get("max_lead_time_days", BigDecimal.class), r.get("currency_code", String.class),
        r.get("excluded_warehouse_refs", String.class), r.get("effective_from", OffsetDateTime.class),
        r.get("effective_to", OffsetDateTime.class), r.get("notes", String.class),
        r.get("created_at", OffsetDateTime.class), r.get("updated_at", OffsetDateTime.class));
  }

  private static WarehouseAllocationWarehouseRow warehouseRow(Record r) {
    return new WarehouseAllocationWarehouseRow(
        r.get("id", UUID.class), r.get("warehouse_ref", String.class),
        r.get("warehouse_name", String.class), r.get("legal_entity_code", String.class),
        r.get("region_code", String.class), r.get("default_priority", Integer.class),
        r.get("active", Boolean.class), r.get("notes", String.class),
        r.get("created_at", OffsetDateTime.class), r.get("updated_at", OffsetDateTime.class));
  }

  private static WarehouseAllocationStockRow stockRow(Record r) {
    return new WarehouseAllocationStockRow(
        r.get("id", UUID.class), r.get("warehouse_ref", String.class),
        r.get("sku_ref", String.class), r.get("available_qty", BigDecimal.class),
        r.get("collected_at", OffsetDateTime.class), r.get("stock_source", String.class),
        r.get("source_reference", String.class), r.get("notes", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static WarehouseAllocationShippingRow shippingRow(Record r) {
    return new WarehouseAllocationShippingRow(
        r.get("id", UUID.class), r.get("warehouse_ref", String.class),
        r.get("sku_ref", String.class), r.get("destination_country_code", String.class),
        r.get("shipping_cost", BigDecimal.class), r.get("lead_time_days", BigDecimal.class),
        r.get("currency_code", String.class), r.get("cost_source", String.class),
        r.get("source_reference", String.class), r.get("effective_from", OffsetDateTime.class),
        r.get("effective_to", OffsetDateTime.class), r.get("notes", String.class),
        r.get("created_at", OffsetDateTime.class), r.get("updated_at", OffsetDateTime.class));
  }

  private static WarehouseAllocationDecisionRow decisionRow(Record r) {
    return new WarehouseAllocationDecisionRow(
        r.get("id", UUID.class), r.get("order_ref", String.class),
        r.get("order_line_ref", String.class), r.get("sku_ref", String.class),
        r.get("requested_qty", BigDecimal.class),
        r.get("destination_country_code", String.class),
        r.get("destination_region_code", String.class),
        r.get("order_legal_entity_code", String.class),
        r.get("chosen_warehouse_ref", String.class),
        r.get("chosen_legal_entity_code", String.class),
        r.get("cross_legal_entity", Boolean.class), r.get("allocated_qty", BigDecimal.class),
        r.get("unit_shipping_cost", BigDecimal.class), r.get("lead_time_days", BigDecimal.class),
        r.get("chosen_total_score", BigDecimal.class), r.get("currency_code", String.class),
        selectionPolicy(r.get("selection_policy", String.class)),
        fallbackPolicy(r.get("fallback_policy", String.class)),
        legalEntityPolicy(r.get("legal_entity_policy", String.class)),
        reasonCode(r.get("reason_code", String.class)), r.get("reason", String.class),
        allocationStatus(r.get("status", String.class)), r.get("decided_at", OffsetDateTime.class),
        r.get("confirmed_at", OffsetDateTime.class), r.get("released_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static WarehouseAllocationHoldRow holdRow(Record r) {
    return new WarehouseAllocationHoldRow(
        r.get("id", UUID.class), r.get("decision_id", UUID.class),
        r.get("order_ref", String.class), holdReason(r.get("hold_reason", String.class)),
        r.get("reason", String.class), r.get("exclusion_detail", String.class),
        r.get("excluded_warehouse_count", Integer.class),
        r.get("hold_candidate_id", UUID.class), r.get("shortfall_qty", BigDecimal.class),
        r.get("held_at", OffsetDateTime.class), r.get("hold_source", String.class));
  }

  private static WarehouseAllocationEventRow eventRow(Record r) {
    return new WarehouseAllocationEventRow(
        r.get("id", UUID.class), r.get("event_kind", String.class),
        r.get("from_status", String.class), r.get("to_status", String.class),
        r.get("detail", String.class), r.get("actor_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class));
  }

  private static int pageSize(int requested) {
    return Math.min(MAX_PAGE_SIZE, Math.max(1, requested <= 0 ? 50 : requested));
  }

  private static Object[] append(List<Object> binds, Object... more) {
    List<Object> all = new ArrayList<>(binds);
    all.addAll(List.of(more));
    return all.toArray();
  }

  private static String whereClause(List<String> where) {
    return where.isEmpty() ? "" : " where " + String.join(" and ", where);
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static String blankTo(String value, String fallback) {
    return notBlank(value) ? value.trim() : fallback;
  }

  private static BigDecimal smaller(BigDecimal current, BigDecimal candidate) {
    if (candidate == null) {
      return current;
    }
    return current == null || candidate.compareTo(current) < 0 ? candidate : current;
  }

  /** 数量刻度对齐 numeric(19,6)。 */
  private static BigDecimal qty(BigDecimal value) {
    return value == null ? null : value.setScale(QTY_SCALE, RoundingMode.HALF_UP);
  }

  /** 金额刻度对齐 numeric(19,4)。 */
  private static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /** 结算口径：统一 HALF_UP 到分（DB 侧 round(x,2) 复核，见迁移第 6 节）。 */
  private static BigDecimal settle(BigDecimal value) {
    return value == null ? null : money(value).setScale(SETTLE_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal weight(BigDecimal value, String field) {
    if (value == null) {
      throw bad(field + " 不能为空：五个维度的权重合计必须为 1.0000");
    }
    BigDecimal scaled = value.setScale(4, RoundingMode.HALF_UP);
    if (scaled.signum() < 0 || scaled.compareTo(ONE) > 0) {
      throw bad(field + " 必须在 0 到 1 之间，收到 " + value);
    }
    return scaled;
  }

  private static WarehouseSelectionPolicy selectionPolicy(String value) {
    try {
      return WarehouseSelectionPolicy.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的选仓策略「" + value + "」：只能是 HIGHEST_SCORE / LOWEST_COST / CLOSEST_LEAD_TIME");
    }
  }

  private static WarehouseFallbackPolicy fallbackPolicy(String value) {
    try {
      return WarehouseFallbackPolicy.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的兜底策略「" + value + "」：只能是 SUSPEND / ALLOW_PARTIAL / CROSS_LEGAL_ENTITY");
    }
  }

  private static WarehouseLegalEntityPolicy legalEntityPolicy(String value) {
    try {
      return WarehouseLegalEntityPolicy.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的货权口径「" + value + "」：只能是 NEUTRAL / ORDER_OWNER_FIRST / PREFERRED_FIRST");
    }
  }

  private static WarehouseAllocationStatus allocationStatus(String value) {
    try {
      return WarehouseAllocationStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的分配状态「" + value + "」：只能是 ALLOCATED / PARTIAL / SUSPENDED / CONFIRMED / RELEASED");
    }
  }

  private static WarehouseAllocationReasonCode reasonCode(String value) {
    return WarehouseAllocationReasonCode.valueOf(value.trim().toUpperCase(Locale.ROOT));
  }

  private static WarehouseHoldReason holdReason(String value) {
    try {
      return WarehouseHoldReason.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的挂起原因「" + value + "」");
    }
  }

  /**
   * 当前 RLS 租户。由 backend/libraries/tenancy 在事务内设置，客户端头改不动它。
   *
   * <p>表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义
   * GUC **复位成空串**而不是「回到未设置」，直接 cast 会报 uuid 语法错。
   */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }
}