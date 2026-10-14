package com.starcon.warehouse;

import static com.starcon.warehouse.SafetyStockModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 7.2.2.2 安全库存：阈值分档、生效值解析、日均销量登记、预警评估生成、预警流转与补货建议。
 *
 * <p>蓝图原话：「按 SKU/分类/仓库配置安全库存阈值与预警级别，低于阈值时触发预警并可关联自动生成
 * 补货建议。输入：阈值配置；当前可用量投影；日均销量。」
 *
 * <p><b>三条实现纪律</b>（与 7.2.2.1 同一套，不另起风格）：
 *
 * <ul>
 *   <li><b>区间 + 不可变</b>：阈值内容写定后不可改（V114 触发器），改阈值 = 收口旧档 + 开新档。
 *   <li><b>时间绑定</b>：{@code ?::timestamptz} 集中在最前面的 {@code win} CTE 里，
 *       后面全是普通绑定，cast 永远不在绑定序列末位（与 7.1.2.2 同一条纪律）。
 *   <li><b>数量一律 numeric + BigDecimal</b>，比较一律 {@code compareTo} 不用 {@code equals}
 *       （后者把 0 与 0.0000 判成不等）。
 * </ul>
 *
 * <p><b>可用量为什么聚合 {@code inventory_balance} 而不是 {@code stock_bucket}</b>：与 7.2.2.1
 * 池容量同一口径 —— V1 把 balance 定为「权威可用量」，而「还没拆到库位」的 balance 行是合法状态，
 * 只聚合 bucket 会把它们漏成 0。两处口径必须一致，否则会出现「7.2.2.1 说有货、7.2.2.2 报警」。
 *
 * <p><b>算不出来就留 null</b>：日均销量未登记时 {@code daysOfCover} 与 {@code suggestedQuantity}
 * 恒为 null 并附上原因。拿 0 兜底会被读成「永远不会断货」与「不用补货」。
 */
@Service
public class SafetyStockService {

  /** 单页最大行数：配置与预警都是可分页的，但量级远小于流水，仍设上限防一次拉爆 JVM。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 一次最多评估多少个 SKU（与 7.2.2.1 / 7.1.1.3 的 SKU 上限同一套口径与理由）。 */
  public static final int MAX_SKUS = 500;

  /** 数量零，scale 与 numeric(19,6) 对齐，避免同一列里混着 scale 不一样的 0。 */
  private static final BigDecimal QTY_ZERO = new BigDecimal("0.000000");

  /** 目标库存换算时的单位换算系数：日均销量 × 覆盖天数。 */
  private static final BigDecimal ONE = BigDecimal.ONE;

  /** IN 列表占位符，每个都带 {@code ::uuid}：jOOQ 按 varchar 送 UUID，单元素列表 PG 反推不出类型。 */
  private static String placeholders(int n) {
    return "(" + String.join(",", java.util.Collections.nCopies(n, "?::uuid")) + ")";
  }

  /** 生效时刻绑定。两个 {@code ?::timestamptz} 刻意放在整条语句最前面，理由见类注释。 */
  private static final String WIN = "with win as (select ?::timestamptz as at)";

  /** 分档的固定 SELECT 列。 */
  private static final String COLUMNS =
      "p.id,p.scope_type,p.category_id,p.sku_id,p.sku_label,p.threshold_quantity,p.alert_severity,"
          + "p.reorder_coverage_days,p.auto_suggest_replenishment,p.effective_from,p.effective_to,"
          + "p.note,p.created_by_membership_id,p.created_at";

  /** 生效判定谓词 —— 「生效」在本模块只有这一个定义。 */
  private static final String LIVE_AT =
      "(p.effective_from <= win.at and (p.effective_to is null or p.effective_to > win.at))";

  /**
   * 预警默认排序：按**提级后**的严重度降序，再按缺口量、再按检测时刻。
   *
   * <p>这段 SQL 与 {@link #effectiveSeverity} 是同一套规则的两次实现，缺一不可：
   * 排序在 SQL 里做（否则要全量取回再排），提级在 Java 里做（要填进响应体）。
   * 平台断货一律提到与上一级同级：CRITICAL/WARNING → 0（与 CRITICAL 同级），INFO → 1。
   */
  private static final String ALERT_ORDER =
      " order by case when a.alert_type = 'PLATFORM_STOCKOUT_RISK'"
          + "   then case when a.severity in ('CRITICAL','WARNING') then 0 else 1 end"
          + "   else case a.severity when 'CRITICAL' then 0 when 'WARNING' then 1 else 2 end end,"
          + " a.shortfall_quantity desc, a.detected_at desc, a.id";

  /**
   * 分档写入的列与每列的绑定 cast，**由同一个数组生成 SQL**（与 7.2.2.1 同一理由：
   * 列顺序与 cast 顺序各写一遍时，错开一位的报错完全指向不了真正的原因）。
   */
  private static final String[] POLICY_COLUMNS = {
    "id", "tenant_id", "scope_type", "category_id", "sku_id", "sku_label", "threshold_quantity",
    "alert_severity", "reorder_coverage_days", "auto_suggest_replenishment", "effective_from",
    "note", "created_by_membership_id"
  };

  /** 与 {@link #POLICY_COLUMNS} 一一对应：空串 = 普通绑定，其余是 PG 侧的 cast。 */
  private static final String[] POLICY_CASTS = {
    "", "", "", "", "", "", "::numeric", "", "::numeric", "", "::timestamptz", "", ""
  };

  private static String policyInsert() {
    if (POLICY_CASTS.length != POLICY_COLUMNS.length) {
      throw new IllegalStateException(
          "分档写入的列与 cast 数量不一致：" + POLICY_COLUMNS.length + " vs " + POLICY_CASTS.length);
    }
    StringJoiner columns = new StringJoiner(",");
    StringJoiner values = new StringJoiner(",");
    for (int i = 0; i < POLICY_COLUMNS.length; i++) {
      columns.add(POLICY_COLUMNS[i]);
      values.add("?" + POLICY_CASTS[i]);
    }
    return "insert into safety_stock_policy (" + columns + ") values (" + values + ")";
  }

  private final DSLContext sql;

  public SafetyStockService(DSLContext sql) {
    this.sql = sql;
  }

  // ============================================================ 能力声明

  /**
   * 安全库存能力清单 —— 以及四条必须**如实讲清楚**的边界。
   *
   * <p>三个 {@code *Available} 布尔位让「哪一环没通」变成一个可读字段，而不是让人从
   * 404 或空数组里猜。本模块没有一环是通的：销量要登记、分类展开要调用方给、6.2.1.2 未交付、
   * 工作台预警未交付 —— 四件事全部如实报出，而不是假装它们是自动的。
   */
  public SafetyStockCapabilities capabilities() {
    List<SafetyStockCapabilityOption> scopes = new ArrayList<>();
    for (SafetyStockScopeType s : SafetyStockScopeType.values()) {
      scopes.add(
          new SafetyStockCapabilityOption(
              s.name(), s.label(), true,
              switch (s) {
                case DEFAULT -> "兜底档。全租户一条，没有它就没有任何 SKU 受安全库存保护 —— "
                    + "「0 条预警」这时指的是压根没人受保护，不是库存健康。";
                case CATEGORY -> "按分类覆盖。分类属 catalog 服务，这里只存逻辑 ID 与可读名快照，不建外键。"
                    + "评估时分类要展开成 SKU，而展开在本服务做不了（见 categoryExpansionNote）。";
                case SKU -> "按 SKU 覆盖。SKU 属 catalog，同上。最具体，优先级最高。";
              }));
    }
    List<SafetyStockCapabilityOption> severities = new ArrayList<>();
    for (AlertSeverity s : AlertSeverity.values()) {
      severities.add(
          new SafetyStockCapabilityOption(
              s.name(), s.label(), true,
              switch (s) {
                case CRITICAL -> "严重。必须当天处置。";
                case WARNING -> "警告。需要排进补货计划。";
                case INFO -> "提示。仅作观察与提前布点。";
              }
              + " 注意：平台断货风险会自动提一级（INFO → 警告、警告 → 严重），"
              + "因为蓝图写明「FBA 断货造成的损失远大于自有仓」。"));
    }
    List<SafetyStockCapabilityOption> types = new ArrayList<>();
    for (SafetyStockAlertType t : SafetyStockAlertType.values()) {
      types.add(
          new SafetyStockCapabilityOption(
              t.name(), t.label(), true,
              t.platformRisk()
                  ? "平台仓（FBA）断货。由挂仓时标注的 warehouseKind=PLATFORM_FBA 派生，调用方不能自选；"
                      + "处置路径是紧急调仓/空运，与自有仓补货完全不同。排序与展示一律按上一级严重度。"
                  : "自有仓耗尽。常规补货即可，损失量级小于平台断货。"));
    }
    List<SafetyStockCapabilityOption> statuses = new ArrayList<>();
    for (SafetyStockAlertStatus s : SafetyStockAlertStatus.values()) {
      statuses.add(
          new SafetyStockCapabilityOption(
              s.name(), s.label(), true,
              switch (s) {
                case OPEN -> "待处理。尚未有人认领。";
                case ACKNOWLEDGED -> "已认领。有人在处理，但还没处置完成。";
                case RESOLVED -> "已处置。单向终态，不允许复活（否则工作台会反复把已闭环的事推回来）。";
              }));
    }
    List<SafetyStockCapabilityOption> kinds = new ArrayList<>();
    for (var kind : List.of("OWN", "PLATFORM_FBA")) {
      boolean fba = "PLATFORM_FBA".equals(kind);
      kinds.add(
          new SafetyStockCapabilityOption(
              kind, fba ? "平台仓（FBA）" : "自有仓", true,
              fba
                  ? "平台仓观测值。产出「平台断货风险」预警，并按上一级严重度处理。"
                  : "自有仓。产出「自有仓耗尽」预警，走常规补货。"));
    }
    List<SafetyStockCapabilityOption> suggestionStatuses = new ArrayList<>();
    for (SafetyStockSuggestionStatus s : SafetyStockSuggestionStatus.values()) {
      suggestionStatuses.add(
          new SafetyStockCapabilityOption(
              s.name(), s.label(), true,
              switch (s) {
                case PROPOSED -> "待处理。尚未采纳也尚未忽略。";
                case ACKNOWLEDGED -> "已采纳。等 6.2.1.2 交付后由它回写采购单号。";
                case DISMISSED -> "已忽略。忽略必须留理由，否则下次评估会再生成同一条幽灵建议。";
              }));
    }
    return new SafetyStockCapabilities(
        scopes,
        severities,
        types,
        statuses,
        kinds,
        suggestionStatuses,
        false,
        false,
        false,
        false,
        "日均销量**无法自动派生**：库存流水（inventory_transaction）没有渠道维度，一个仓发出去的货"
            + "拆不到「哪个渠道卖掉的」；而 13 销量域尚未交付，没有可查的销量事实。所以日均销量由 "
            + "POST /warehouse/inventory/safety-stock/sales **登记**进来，它是本服务唯一的销量真相来源。"
            + "同一来源同一 SKU 的登记区间不得重叠（DB 触发器挡住），否则日均销量会虚高、"
            + "补货建议量偏大。13 交付后应由其回写，本模块不臆造销量。",
        "分类 → SKU 的展开在 catalog 服务，本服务跨库读不到（AGENTS.md 禁止跨服务 SQL）。"
            + "因此分类档与兜底档在**评估**时需要调用方随请求提供 SKU 清单及其分类上下文"
            + "（POST /evaluate 的 skus 字段）。清单为空时只扫 SKU 档，其余档被计入 "
            + "policiesNotScannable 并如实报出，不会静默跳过让你以为「扫过了没风险」。",
        "6.2.1.2 补货建议尚未交付，且属 06 采购域（跨服务、独立数据库）。本模块生成并落库补货建议"
            + "（safety_stock_replenishment_suggestion），externalPurchaseReference 留空，"
            + "等它交付后由它回写采购单号 —— 本模块不建外键、不建视图、不跨库写。"
            + "没登记销量的 SKU 不给建议量（留 null 并写明原因），拿 0 会被读成「不用补货」。",
        "1 工作台预警尚未交付，因此预警目前**只在本服务可查**"
            + "（GET /warehouse/inventory/safety-stock/alerts）。工作台交付后应由它来读本服务，"
            + "本模块不代它实现推送。",
        "预警类型由仓的 warehouseKind 派生，不是可选标签；一档阈值挂的仓必须同 kind"
            + "（DB 延迟约束触发器），FBA 与自有仓因此永远是两个独立的池子。"
            + "平台断货风险在展示与排序上一律按上一级严重度处理 —— 这是「FBA 断货造成的损失远大于"
            + "自有仓」这句话的落点：损失更大的一方必须真的排在前面，而不只是写在注释里。",
        "预警的读数快照（可用量/阈值/缺口/日均销量/可售天数）写定后不可修改（DB 触发器），"
            + "状态只能 OPEN → 已认领 → 已处置 单向推进。要重算请重新评估，会生成新的预警。");
  }

  // ============================================================ 阈值分档列表与详情

  /** 阈值分档列表（真分页，total 与分页无关）。 */
  public SafetyStockPolicyPage page(SafetyStockQuery query) {
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size() <= 0 ? 50 : query.size()));
    int pageNo = Math.max(0, query.page());
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    conditions(query, where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from safety_stock_policy p" + sqlWhere, binds.toArray())
            .get(0, Long.class);

    var rows =
        sql.fetch(
            "select " + COLUMNS + " from safety_stock_policy p" + sqlWhere + order(query)
                + " limit ? offset ?",
            append(binds, size, (long) pageNo * size));

    return new SafetyStockPolicyPage(
        rows.stream().map(SafetyStockService::policyRow).toList(), total, pageNo, size);
  }

  private void conditions(SafetyStockQuery query, List<String> where, List<Object> binds) {
    if (notBlank(query.scopeType())) {
      where.add("p.scope_type = ?");
      binds.add(query.scopeType().trim().toUpperCase(Locale.ROOT));
    }
    if (query.categoryId() != null) {
      where.add("p.category_id = ?");
      binds.add(query.categoryId());
    }
    if (query.skuId() != null) {
      where.add("p.sku_id = ?");
      binds.add(query.skuId());
    }
    if (notBlank(query.alertSeverity())) {
      where.add("p.alert_severity = ?");
      binds.add(query.alertSeverity().trim().toUpperCase(Locale.ROOT));
    }
    // 按仓过滤要过挂仓表：仓挂在阈值档上而不是阈值档的主键上。
    if (query.warehouseId() != null) {
      where.add("exists (select 1 from safety_stock_policy_warehouse pw"
          + " where pw.tenant_id = p.tenant_id and pw.policy_id = p.id and pw.warehouse_id = ?)");
      binds.add(query.warehouseId());
    }
    if (query.effectiveOnly()) {
      // 「此刻仍在生效」由服务端用 now() 判定，不接受调用方传时刻：
      // 否则「看未来某一刻谁生效」会变成一个能试错的入口，而它在这里没有业务含义。
      where.add("(p.effective_from <= now() and (p.effective_to is null or p.effective_to > now()))");
    }
    if (notBlank(query.keyword())) {
      where.add("(p.sku_label ilike ? or p.note ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      binds.add(k);
      binds.add(k);
    }
  }

  private String order(SafetyStockQuery query) {
    // 决胜键恒定追加 p.id：同排序值时翻页不会错位或重复。
    return query.effectiveOnly()
        ? " order by p.scope_type, p.effective_from desc, p.id"
        : " order by p.effective_from desc, p.id";
  }

  /** 阈值详情 = 分档 + 它吃哪几个仓。跨租户在 RLS 下查不到 → 404。 */
  public SafetyStockPolicyDetail detail(UUID id) {
    var r = sql.fetchOne("select " + COLUMNS + " from safety_stock_policy p where p.id = ?", id);
    if (r == null) {
      throw notFound(id);
    }
    return new SafetyStockPolicyDetail(policyRow(r), warehouses(id));
  }

  private List<SafetyStockWarehouseLink> warehouses(UUID policyId) {
    return sql.fetch(
            "select lw.policy_id,lw.warehouse_id,w.code as warehouse_code,w.name as warehouse_name,"
                + "lw.warehouse_kind from safety_stock_policy_warehouse lw"
                + " join warehouse w on w.tenant_id = lw.tenant_id and w.id = lw.warehouse_id"
                + " where lw.policy_id = ? order by w.code",
            policyId)
        .map(
            r -> {
              boolean platform = "PLATFORM_FBA".equals(r.get("warehouse_kind", String.class));
              return new SafetyStockWarehouseLink(
                  r.get("policy_id", UUID.class),
                  r.get("warehouse_id", UUID.class),
                  r.get("warehouse_code", String.class),
                  r.get("warehouse_name", String.class),
                  r.get("warehouse_kind", String.class),
                  platform ? "平台仓（FBA）" : "自有仓",
                  platform);
            });
  }

  // ============================================================ 阈值写入

  /**
   * 新建一档阈值。{@code supersedeId} 非空时先收口那一档的区间再开新档（改阈值的标准动作）。
   *
   * <p>跨服务边界：SKU 与分类在 catalog 服务，本服务不建外键也不查它的表，只存逻辑 ID 与
   * 写入时的可读名快照（V114 文件头）。
   *
   * <p>挂仓必须同 kind —— 混挂由 DB 的延迟约束触发器当场拒绝，错误信息里带上了为什么要拒绝。
   * 这里先做一次应用层同 kind 校验只是为了给出更准的报错；DB 那道才是保证。
   */
  @Transactional
  public SafetyStockPolicySaved save(
      UUID supersedeId, SafetyStockPolicyInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    SafetyStockScopeType scope = scope(input.scopeType());
    AlertSeverity severity = severity(input.alertSeverity());

    UUID categoryId = input.categoryId();
    UUID skuId = input.skuId();
    if (scope == SafetyStockScopeType.DEFAULT) {
      categoryId = null;
      skuId = null;
    } else if (scope == SafetyStockScopeType.CATEGORY && categoryId == null) {
      throw bad(scope.label() + "档必须给 categoryId：它是跨服务定位键"
          + "（分类在 catalog 服务，本服务不建外键也不查它的表），少一个就会有一档永远命中不了的阈值");
    } else if (scope == SafetyStockScopeType.SKU && skuId == null) {
      throw bad(scope.label() + "档必须给 skuId：它是跨服务定位键"
          + "（SKU 在 catalog 服务，本服务不建外键也不查它的表），少一个就会有一档永远命中不了的阈值");
    } else if (scope == SafetyStockScopeType.SKU) {
      categoryId = null;
    } else if (scope == SafetyStockScopeType.CATEGORY) {
      skuId = null;
    }

    BigDecimal threshold = requireThreshold(input.thresholdQuantity());
    BigDecimal coverageDays = input.reorderCoverageDays();
    if (coverageDays != null && coverageDays.signum() < 0) {
      throw bad("补货覆盖天数不能为负数，收到 " + coverageDays.toPlainString());
    }
    if (input.autoSuggestReplenishment() && coverageDays == null) {
      throw bad("打开了「预警时自动生成补货建议」就必须给 reorderCoverageDays："
          + "否则「自动生成建议」这句承诺没有可计算的量，界面上会得到一条永远算不出数量的建议");
    }

    Map<UUID, String> wanted = bindings(input.warehouses());
    if (wanted.isEmpty()) {
      throw bad("至少要挂一个仓库：安全库存是按仓判定的，"
          + "一个都不挂的话这一档永远不会产生任何预警");
    }
    verifySingleKind(wanted);
    verifyWarehouses(wanted.keySet());

    OffsetDateTime effectiveFrom =
        input.effectiveFrom() == null ? OffsetDateTime.now() : input.effectiveFrom();

    // 换版：先把该槽位仍在生效的那一档收口，再开新档。顺序不能反 ——
    // 先插新档会被区间重叠触发器当场拒绝（那是正确的失败，但表达不出「顺带收口」这个意图）。
    UUID superseded = null;
    if (supersedeId != null) {
      var target = sql.fetchOne("select " + COLUMNS + " from safety_stock_policy p where p.id = ?", supersedeId);
      if (target == null) {
        throw notFound(supersedeId);
      }
      if (target.get("effective_to", OffsetDateTime.class) != null) {
        throw bad("这一档的区间已被收口（生效至 " + target.get("effective_to", OffsetDateTime.class)
            + "），不能作为换版对象：要换的是当前正在生效的那一档");
      }
      if (!sameSlot(target, scope, categoryId, skuId)) {
        throw bad("换版必须落在同一个范围槽位内（原档 "
            + SafetyStockScopeType.valueOf(target.get("scope_type", String.class)).label()
            + "，新档 " + scope.label() + "）：跨槽位改档会让原档被收口后无处继承，"
            + "等于悄悄作废了一个阈值");
      }
      if (!effectiveFrom.isAfter(target.get("effective_from", OffsetDateTime.class))) {
        throw bad("新档的生效起点 " + effectiveFrom + " 必须晚于原档起点 "
            + target.get("effective_from", OffsetDateTime.class) + "：区间不前进就等于原地改阈值");
      }
      sql.execute("update safety_stock_policy set effective_to = ?::timestamptz where id = ?",
          effectiveFrom, supersedeId);
      superseded = supersedeId;
    }

    UUID id = UUID.randomUUID();
    sql.execute(
        policyInsert(),
        id,
        tenant(),
        scope.name(),
        categoryId,
        skuId,
        input.skuLabel() == null ? "" : input.skuLabel().trim(),
        threshold,
        severity.name(),
        coverageDays,
        input.autoSuggestReplenishment(),
        effectiveFrom,
        input.note() == null ? "" : input.note().trim(),
        actorId);

    for (Map.Entry<UUID, String> entry : wanted.entrySet()) {
      sql.execute(
          "insert into safety_stock_policy_warehouse (tenant_id,policy_id,warehouse_id,warehouse_kind) "
              + "values (?,?,?,?)",
          tenant(), id, entry.getKey(), entry.getValue());
    }

    return new SafetyStockPolicySaved(
        id, threshold, severity.name(), wanted.size(), effectiveFrom, null, superseded,
        superseded == null
            ? "已新建" + scope.label() + "阈值：" + threshold.toPlainString() + " 件，预警级别 "
                + severity.label() + "，挂 " + wanted.size() + " 个仓（"
                + (wanted.values().iterator().next().equals("PLATFORM_FBA") ? "平台仓" : "自有仓")
                + "）。该档按区间生效，历史各档原样保留。"
            : "已换版：原档的区间收到 " + effectiveFrom + " 为止，新档自 " + effectiveFrom + " 起生效。"
                + "两档并存，历史那一档仍是「当时那条预警依据长什么样」的唯一凭据。");
  }

  /**
   * 作废一档阈值（收口它的生效区间）。
   *
   * <p>刻意**不**提供 DELETE：作废是审计事件，档位行必须永久保留（V114 已 REVOKE DELETE
   * + 不可变触发器双重锁死）。要回看当时阈值时查得到，才叫可追溯。
   */
  @Transactional
  public SafetyStockPolicySaved retire(UUID id, OffsetDateTime at) {
    var r = sql.fetchOne("select " + COLUMNS + " from safety_stock_policy p where p.id = ?", id);
    if (r == null) {
      throw notFound(id);
    }
    var existingTo = r.get("effective_to", OffsetDateTime.class);
    if (existingTo != null) {
      throw bad("这一档已被作废过（生效至 " + existingTo + "），重复作废不会产生新的收口时刻");
    }
    var from = r.get("effective_from", OffsetDateTime.class);
    OffsetDateTime moment = at == null ? OffsetDateTime.now() : at;
    if (!moment.isAfter(from)) {
      throw bad("作废时刻 " + moment + " 必须晚于该档的生效起点 " + from
          + "：否则这一档从未真正生效过");
    }
    sql.execute("update safety_stock_policy set effective_to = ?::timestamptz where id = ?", moment, id);
    return new SafetyStockPolicySaved(
        id,
        decimal(r, "threshold_quantity"),
        r.get("alert_severity", String.class),
        countWarehouses(id),
        from,
        moment,
        null,
        "已作废：本档自 " + moment + " 起不再生效。行本身保留，需要回看当时阈值时查得到。"
            + "若此刻没有别的在效档，解析会如实报「未配置」而不是静默按阈值 0 处理。");
  }

  // ============================================================ 生效值解析

  /**
   * 解析某一时刻真正生效的阈值。
   *
   * <p>优先级 SKU &gt; CATEGORY &gt; DEFAULT，命中即止，这样「回退到兜底档」是一个
   * **如实报出来的状态**（{@code usedFallback}）而不是猜测。
   *
   * <p>三个分支各写一条 SQL，而不是一条 {@code (? is null or ...)}：后者既让 PG 反推不出
   * 参数类型，又要把 cast 放到末位绑定 —— 正是 7.1.2.2 踩过的那条纪律。
   */
  public SafetyStockPolicyEffective effective(
      SafetyStockScopeType scope, UUID categoryId, UUID skuId, OffsetDateTime at) {
    OffsetDateTime moment = at == null ? OffsetDateTime.now() : at;
    SafetyStockScopeType requested = scope == null ? SafetyStockScopeType.SKU : scope;

    Record hit;
    SafetyStockScopeType hitLevel;
    if (requested == SafetyStockScopeType.SKU) {
      if (skuId == null) {
        throw bad("按 SKU 解析必须给 skuId");
      }
      hit = fetchAt("p.sku_id = ?", skuId, moment);
      hitLevel = SafetyStockScopeType.SKU;
      if (hit == null && categoryId != null) {
        // 继续下探到分类档：优先级是 SKU > CATEGORY > DEFAULT，只查 SKU 档再直接跳兜底
        // 会让中间的分类档形同虚设。分类档要靠 categoryId —— 分类 → SKU 的展开在 catalog
        // 服务、本服务跨库读不到，所以「按 SKU 解析并允许回退到分类档」要求调用方一并给出分类。
        hit = fetchAt("p.category_id = ?", categoryId, moment);
        hitLevel = SafetyStockScopeType.CATEGORY;
      }
    } else if (requested == SafetyStockScopeType.CATEGORY) {
      if (categoryId == null) {
        throw bad("按分类解析必须给 categoryId");
      }
      hit = fetchAt("p.category_id = ?", categoryId, moment);
      hitLevel = SafetyStockScopeType.CATEGORY;
    } else {
      hit = fetchAt("p.scope_type = 'DEFAULT'", null, moment);
      hitLevel = SafetyStockScopeType.DEFAULT;
    }

    if (hit != null) {
      return new SafetyStockPolicyEffective(
          policyRow(hit), hitLevel, false, moment, "命中" + hitLevel.label() + "档");
    }
    // 回退到兜底档：如实报出 usedFallback，不静默按 0 处理。
    var fallback = fetchAt("p.scope_type = 'DEFAULT'", null, moment);
    if (fallback != null) {
      return new SafetyStockPolicyEffective(
          policyRow(fallback), SafetyStockScopeType.DEFAULT, true, moment,
          "没有命中的" + requested.label() + "档，已回退到全租户兜底档（usedFallback=true）");
    }
    return new SafetyStockPolicyEffective(
        null, SafetyStockScopeType.DEFAULT, false, moment,
        "连兜底档都没配：这个 SKU 在 " + moment + " 时刻**不受任何安全库存保护**，"
            + "「0 条预警」此时指的是压根没人受保护，不是库存健康");
  }

  /**
   * 在 {@code predicate} 给定的那一档里，取某时刻仍在生效的最新一行。
   *
   * <p>每个范围槽位至多一档 open（V114 §7.5 的延迟约束跨仓守住），所以这一行是唯一的。
   * 三个分支各写一条 SQL 而不是一条 {@code (? is null or ...)}：后者既让 PG 反推不出参数类型，
   * 又要把 cast 放到绑定序列末位 —— 正是 7.1.2.2 踩过的那条纪律。
   */
  private Record fetchAt(String predicate, UUID key, OffsetDateTime at) {
    return sql.fetchOne(
        WIN + " select " + COLUMNS + " from safety_stock_policy p, win where " + predicate
            + " and " + LIVE_AT + " order by p.effective_from desc limit 1",
        key == null ? new Object[] {at} : new Object[] {at, key});
  }

  // ============================================================ 日均销量登记

  /**
   * 登记一条 SKU 日均销量。
   *
   * <p>日均销量是本模块推算可售天数与补货建议量的唯一输入，而它**无法自动派生**
   * （13 销量域未交付，库存流水没有渠道维度）—— 照 7.8.1.1 的先例登记进来，
   * {@code /capabilities} 会如实报出这件事而不是假装它是从订单算出来的。
   *
   * <p>同一来源同一 SKU 的区间重叠由 DB 触发器拒绝：两段都算进去日均销量会虚高、
   * 建议量偏大，最终变成一张采购单，而界面上看不出异常。
   */
  @Transactional
  public SafetyStockSalesSaved recordSales(SafetyStockSalesInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    if (input.periodEnd().isBefore(input.periodStart())) {
      throw bad("销量区间的结束日 " + input.periodEnd() + " 不能早于开始日 " + input.periodStart());
    }
    BigDecimal units = requireUnits(input.unitsSold());
    long days = days(input.periodStart(), input.periodEnd());
    BigDecimal dailyAverage = average(units, days);

    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into safety_stock_sales (id,tenant_id,source_system,sku_id,sku_label,"
            + "period_start,period_end,units_sold,recorded_by_membership_id) "
            + "values (?,?,?,?,?,?,?,?,?)",
        id, tenant(), input.sourceSystem().trim(), input.skuId(),
        input.skuLabel() == null ? "" : input.skuLabel().trim(),
        input.periodStart(), input.periodEnd(), units, actorId);

    return new SafetyStockSalesSaved(
        id, input.skuId(), input.periodStart(), input.periodEnd(), units, dailyAverage, days,
        "已登记 " + input.periodStart() + " ~ " + input.periodEnd() + " 共 " + days + " 天、"
            + units.toPlainString() + " 件，日均 " + dailyAverage.toPlainString()
            + " 件。这是本服务唯一的销量真相来源（13 销量域未交付，库存流水没有渠道维度）。");
  }

  /** 销量登记列表（真分页，聚合到 SKU 粒度 —— 日均销量的使用单位就是 SKU）。 */
  public SafetyStockSalesPage sales(UUID skuId, String keyword, int page, int size) {
    int rows = Math.min(MAX_PAGE_SIZE, Math.max(1, size <= 0 ? 50 : size));
    int pageNo = Math.max(0, page);
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (skuId != null) {
      where.add("s.sku_id = ?");
      binds.add(skuId);
    }
    if (notBlank(keyword)) {
      where.add("(s.sku_label ilike ? or s.source_system ilike ?)");
      String k = "%" + keyword.trim() + "%";
      binds.add(k);
      binds.add(k);
    }
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(distinct sku_id) from safety_stock_sales s" + sqlWhere, binds.toArray())
            .get(0, Long.class);

    var rowsOut =
        sql.fetch(
            "select s.sku_id, max(s.sku_label) as sku_label, sum(s.units_sold) as units_sold,"
                + " sum((s.period_end - s.period_start + 1)) as covered_days,"
                + " min(s.period_start) as first_start, max(s.period_end) as last_end, count(*) as records"
                + " from safety_stock_sales s" + sqlWhere
                + " group by s.sku_id order by max(s.period_end) desc, s.sku_id"
                + " limit ? offset ?",
            append(binds, rows, (long) pageNo * rows));

    return new SafetyStockSalesPage(
        rowsOut.stream().map(SafetyStockService::salesRow).toList(), total, pageNo, rows);
  }

  private static SafetyStockSalesRow salesRow(Record r) {
    BigDecimal units = decimal(r, "units_sold");
    // sum((period_end - period_start + 1)) 的结果类型是 **numeric**（不是 bigint）：
    // 按 Long 去取会拿到 null，而 null.longValue() 会 NPE。这里统一走 BigDecimal。
    BigDecimal covered = decimal(r, "covered_days");
    return new SafetyStockSalesRow(
        r.get("sku_id", UUID.class),
        r.get("sku_label", String.class),
        units,
        average(units, covered.longValue()),
        r.get("first_start", LocalDate.class),
        r.get("last_end", LocalDate.class),
        covered.longValue(),
        r.get("records", Long.class));
  }

  // ============================================================ 评估

  /**
   * 发起一次评估：扫在效阈值 → 比可用量 → 生成预警 → 关联补货建议。
   *
   * <p>一次评估落三样东西，缺一不可：
   *
   * <ul>
   *   <li><b>评估批次</b>（append-only）：扫了多少、缺了什么。零结果的评估也必须留痕 ——
   *       只留预警行的话「扫了 12 档、0 条预警」和「压根没扫」会长得一模一样。
   *   <li><b>预警行</b>：检测那一刻的读数快照，写定后不可改。
   *   <li><b>补货建议</b>：仅当该档开了自动建议。
   * </ul>
   *
   * <p><b>分类档与兜底档需要 SKU 清单</b>：分类 → SKU 的展开在 catalog 服务，本服务跨库读不到，
   * 所以清单由调用方随请求带上分类上下文。清单为空时这两档计入 {@code policiesNotScannable}
   * 并如实报出，**不静默跳过** —— 那会让「没扫」显示成「扫了没风险」。
   */
  @Transactional
  public SafetyStockEvaluationRow evaluate(SafetyStockEvaluationRequest request, UUID actorId) {
    if (request == null) {
      request = new SafetyStockEvaluationRequest(List.of(), null, null);
    }
    List<SafetyStockEvaluationRequest.SafetyStockEvaluationSkuContext> contexts = request.skus() == null ? List.of() : request.skus();
    if (contexts.size() > MAX_SKUS) {
      throw bad("一次最多评估 " + MAX_SKUS + " 个 SKU，收到 " + contexts.size()
          + " 个：超过上限会把这一批的预警全部挤到下一次评估之外");
    }
    OffsetDateTime at = OffsetDateTime.now();

    // ---- 1. 取在效阈值 + 它挂的仓（一条 SQL 拿全，win CTE 统一时间基准）
    var liveRows = sql.fetch(
        WIN + " select p.id,p.scope_type,p.category_id,p.sku_id,p.sku_label,p.threshold_quantity,"
            + "p.alert_severity,p.reorder_coverage_days,p.auto_suggest_replenishment,p.effective_from,"
            + "pw.warehouse_id,pw.warehouse_kind,w.code as warehouse_code,w.name as warehouse_name"
            + " from safety_stock_policy p"
            + " join safety_stock_policy_warehouse pw on pw.tenant_id = p.tenant_id and pw.policy_id = p.id"
            + " join warehouse w on w.tenant_id = pw.tenant_id and w.id = pw.warehouse_id,"
            + " win where " + LIVE_AT
            + (request.warehouseId() == null ? "" : " and pw.warehouse_id = ?")
            + " order by p.scope_type, p.id, pw.warehouse_id",
        request.warehouseId() == null ? new Object[] {at} : new Object[] {at, request.warehouseId()});

    if (liveRows.isEmpty()) {
      // 没有在效阈值：如实返回 0，并把原因写进 note，而不是回一个「0 条预警，一切正常」。
      var empty = insertEvaluation(actorId, at, 0, 0, 0, 0, 0, 0, 0, 0,
          request.warehouseId(),
          "当前租户没有任何仍在生效的安全库存阈值（"
              + (request.warehouseId() == null ? "全部仓" : "该仓")
              + "）：本次评估没有可比对的基准，0 条预警**不代表库存健康**，"
              + "而是压根没有阈值在生效。先去配置阈值再评估。");
      return empty;
    }

    // ---- 2. 解析每个 (仓, SKU) 命中的那一档：SKU > CATEGORY > DEFAULT
    //
    // 调用方没给 SKU 清单时**仍要扫 SKU 档**：SKU 档自己就带 sku_id，不需要任何外部展开，
    // 只因「要个清单」而把它排除掉，会让最基本的配置形同虚设。分类档/兜底档则相反 ——
    // 它们必须靠清单才能落到具体 SKU 上，扫不了就是扫不了，如实计入 notScannable。
    boolean callerGaveSkus = !contexts.isEmpty();
    List<SafetyStockEvaluationRequest.SafetyStockEvaluationSkuContext> scanContexts = contexts;
    if (!callerGaveSkus) {
      var derived = new LinkedHashSet<UUID>();
      for (Record r : liveRows) {
        if ("SKU".equals(r.get("scope_type", String.class))) {
          derived.add(r.get("sku_id", UUID.class));
        }
      }
      scanContexts =
          derived.stream().map(id -> new SafetyStockEvaluationRequest.SafetyStockEvaluationSkuContext(id, null)).toList();
    }

    var bySku = new HashMap<UUID, SafetyStockEvaluationRequest.SafetyStockEvaluationSkuContext>();
    for (var c : scanContexts) {
      bySku.put(c.skuId(), c);
    }
    // 解析表：(warehouseId, skuId) → 命中的阈值档。同一 (仓,SKU) 只留优先级最高的一档。
    var resolved = new HashMap<PairKey, LivePolicy>();
    // 按 policy id 去重计数：liveRows 是 (档 × 仓) 的笛卡尔行，直接数行会把
    // 一个挂 3 个仓的档算成 3 档「没扫」，报出来的数就没法看了。
    Set<UUID> notScannablePolicies = new LinkedHashSet<>();
    Set<UUID> policiesSeen = new LinkedHashSet<>();
    for (Record r : liveRows) {
      SafetyStockScopeType st = SafetyStockScopeType.valueOf(r.get("scope_type", String.class));
      policiesSeen.add(r.get("id", UUID.class));
      var policy = LivePolicy.of(r);
      if (st != SafetyStockScopeType.SKU && !callerGaveSkus) {
        // 分类档/兜底档没有 SKU 清单就无法展开成具体对象：计入「没扫」而不是静默跳过。
        notScannablePolicies.add(policy.id());
        continue;
      }
      for (var c : scanContexts) {
        if (!covers(st, policy, c)) {
          continue;
        }
        UUID warehouseId = r.get("warehouse_id", UUID.class);
        var key = new PairKey(warehouseId, c.skuId());
        LivePolicy current = resolved.get(key);
        if (current == null || st.priority() < current.scope().priority()) {
          resolved.put(key, policy.withWarehouse(
              warehouseId, r.get("warehouse_code", String.class),
              r.get("warehouse_name", String.class), r.get("warehouse_kind", String.class)));
        }
      }
    }
    long policyCount = policiesSeen.size();
    long notScannable = notScannablePolicies.size();

    if (resolved.isEmpty()) {
      var none = insertEvaluation(actorId, at, policyCount, notScannable, 0, 0, 0, 0, 0, 0,
          request.warehouseId(),
          "在效阈值共 " + policyCount + " 档，但没有一个能落到本次给定的 SKU 清单上："
              + (notScannable > 0
                  ? "其中 " + notScannable + " 档是分类档/兜底档，而本次请求没带 SKU 清单（"
                      + "分类 → SKU 的展开在 catalog 服务，跨服务不可读）。"
                      : "本次的 SKU 清单与任何在效阈值都不匹配。")
              + "请在请求里带上 skus（含分类上下文）后重试。");
      return none;
    }

    // ---- 3. 可用量投影（SELLABLE 口径，与 7.2.2.1 池容量同源）
    Set<UUID> skuIds = new LinkedHashSet<>();
    Set<UUID> warehouseIds = new LinkedHashSet<>();
    resolved.keySet().forEach(k -> {
      skuIds.add(k.skuId());
      warehouseIds.add(k.warehouseId());
    });
    var available = availableByWarehouseSku(skuIds, warehouseIds);

    // ---- 4. 日均销量（登记事实；没登记就是 null，不拿 0 兜底）
    var sales = dailyAverageBySku(skuIds);

    // ---- 5. 逐对判定 → 生成预警（+ 建议）
    UUID evaluationId = UUID.randomUUID();
    long opened = 0;
    long suppressed = 0;
    long suggestions = 0;
    long withoutSales = 0;

    // 排序：先按提级严重度、同级按缺口大者优先，保证生成顺序与展示顺序一致（便于逐条断言）。
    var candidates = new ArrayList<AlertDraft>();
    for (var entry : resolved.entrySet()) {
      LivePolicy p = entry.getValue();
      BigDecimal have = available.getOrDefault(entry.getKey(), QTY_ZERO);
      BigDecimal threshold = p.threshold();
      if (have.compareTo(threshold) >= 0) {
        suppressed++;
        continue;
      }
      BigDecimal shortfall = threshold.subtract(have);
      BigDecimal dailyAvg = sales.get(entry.getKey().skuId());
      if (dailyAvg == null) {
        withoutSales++;
      }
      SafetyStockAlertType type = p.platform() ? SafetyStockAlertType.PLATFORM_STOCKOUT_RISK
          : SafetyStockAlertType.OWN_WAREHOUSE_DEPLETION;
      BigDecimal cover = dailyAvg != null && dailyAvg.signum() > 0
          ? have.divide(dailyAvg, 4, RoundingMode.HALF_UP) : null;
      BigDecimal suggested = null;
      String unquantified = "";
      if (p.autoSuggest()) {
        if (dailyAvg == null) {
          unquantified = "该 SKU 尚未登记销量，日均销量与可售天数都算不出来，因此不给建议量";
        } else if (p.coverageDays() == null) {
          unquantified = "该阈值档没有配补货覆盖天数，无法推算目标库存";
        } else {
          BigDecimal target = threshold.add(
              dailyAvg.multiply(p.coverageDays()).setScale(6, RoundingMode.HALF_UP));
          BigDecimal gap = target.subtract(have);
          suggested = gap.signum() > 0 ? gap : QTY_ZERO;
        }
      }
      candidates.add(new AlertDraft(
          evaluationId, p, entry.getKey(), type, have, threshold, shortfall,
          dailyAvg, cover, suggested, unquantified, at));
    }
    candidates.sort(
        Comparator.comparingInt((AlertDraft d) -> effectiveSeverity(d).rank())
            .thenComparing(AlertDraft::shortfall, Comparator.reverseOrder())
            .thenComparing(AlertDraft::detectedAt, Comparator.reverseOrder()));

    // 计数在插入之前一次算清：预警的 evaluation_id 是指向评估批次的外键，
    // **批次必须先落库**，否则每一条预警插入都会撞外键。而评估批次是 append-only
    // （不能插完再改计数），所以顺序只能是「先算 → 先落批次 → 再落预警与建议」。
    opened = candidates.size();
    suggestions = candidates.stream().filter(d -> d.policy().autoSuggest()).count();

    String note = notBlank(request.note()) ? request.note().trim()
        : "扫描在效阈值 " + policyCount + " 档，命中 (仓 × SKU) " + resolved.size() + " 对；"
            + "低于阈值 " + opened + " 对，未触发 " + suppressed + " 对。"
            + (withoutSales > 0
                ? "其中 " + withoutSales + " 个 SKU 尚未登记销量，可售天数与建议量算不出来，已如实留空。"
                : "")
            + (notScannable > 0
                ? "另有 " + notScannable + " 档分类档/兜底档因未带 SKU 清单未能评估（见 policiesNotScannable）。"
                : "");

    var evaluation = finishEvaluation(evaluationId, actorId, at, policyCount, notScannable,
        warehouseIds.size(), resolved.size(), withoutSales, opened, suppressed, suggestions,
        request.warehouseId(), note);

    for (AlertDraft d : candidates) {
      UUID alertId = UUID.randomUUID();
      // sku_id 一律取 key 上的那个：**只有 SKU 档的 policy.sku_id 非空**，
      // 分类档/兜底档的 sku_id 是 NULL，用它写库会落一条没有 SKU 的预警。
      sql.execute(
          "insert into safety_stock_alert (id,tenant_id,evaluation_id,policy_id,warehouse_id,sku_id,"
              + "sku_label,alert_type,severity,available_quantity,threshold_quantity,shortfall_quantity,"
              + "daily_average_sales,days_of_cover,detected_at,status,policy_scope_type,effective_from,"
              + "review_note) values (?,?,?,?,?,?,?,?,?,?::numeric,?::numeric,?::numeric,?::numeric,"
              + "?::numeric,?::timestamptz,'OPEN',?,?::timestamptz,?)",
          alertId, tenant(), d.evaluationId(), d.policy().id(), d.key().warehouseId(),
          d.key().skuId(), d.policy().skuLabel(), d.type().name(), d.policy().severity().name(),
          d.available(), d.threshold(), d.shortfall(),
          d.dailyAverage(), d.daysOfCover(), at,
          d.policy().scope().name(), d.policy().effectiveFrom(), reviewNote(d));
      opened++;

      if (d.policy().autoSuggest()) {
        sql.execute(
            "insert into safety_stock_replenishment_suggestion (id,tenant_id,alert_id,policy_id,"
                + "warehouse_id,sku_id,sku_label,suggested_quantity,daily_average_sales,coverage_days,"
                + "target_stock_quantity,unquantified_reason,status) "
                + "values (?,?,?,?,?,?,?,?::numeric,?::numeric,?::numeric,?::numeric,?,'PROPOSED')",
            UUID.randomUUID(), tenant(), alertId, d.policy().id(), d.key().warehouseId(),
            d.key().skuId(), d.policy().skuLabel(), d.suggested(), d.dailyAverage(),
            d.policy().coverageDays(), targetStock(d), d.unquantifiedReason());
      }
    }

    return evaluation;
  }

  private SafetyStockEvaluationRow insertEvaluation(
      UUID actorId, OffsetDateTime at, long policyCount, long notScannable, long warehouses,
      long skus, long withoutSales, long opened, long suppressed, long suggestions,
      UUID warehouseId, String note) {
    return finishEvaluation(UUID.randomUUID(), actorId, at, policyCount, notScannable, warehouses,
        skus, withoutSales, opened, suppressed, suggestions, warehouseId, note);
  }

  private SafetyStockEvaluationRow finishEvaluation(
      UUID id, UUID actorId, OffsetDateTime at, long policyCount, long notScannable,
      long warehouses, long skus, long withoutSales, long opened, long suppressed, long suggestions,
      UUID warehouseId, String note) {
    sql.execute(
        "insert into safety_stock_evaluation (id,tenant_id,triggered_by_membership_id,evaluated_at,"
            + "policies_evaluated,policies_not_scannable,warehouses_evaluated,skus_evaluated,"
            + "skus_without_sales,alerts_opened,alerts_suppressed,suggestions_created,scope_warehouse_id,"
            + "note) values (?,?,?,?::timestamptz,?,?,?,?,?,?,?,?,?,?)",
        id, tenant(), actorId, at, policyCount, notScannable, warehouses, skus, withoutSales,
        opened, suppressed, suggestions, warehouseId, note);
    return new SafetyStockEvaluationRow(
        id, at, actorId, policyCount, notScannable, warehouses, skus, withoutSales, opened,
        suppressed, suggestions, warehouseId, note);
  }

  /** 评估批次列表（真分页）。零结果的评估也在列表里 —— 「扫了但没风险」是一条要能回答的事实。 */
  public SafetyStockEvaluationPage evaluations(int page, int size) {
    int rows = Math.min(MAX_PAGE_SIZE, Math.max(1, size <= 0 ? 50 : size));
    int pageNo = Math.max(0, page);
    long total =
        sql.fetchOne("select count(*) from safety_stock_evaluation").get(0, Long.class);
    var rowsOut =
        sql.fetch(
            "select e.id,e.evaluated_at,e.triggered_by_membership_id,e.policies_evaluated,"
                + "e.policies_not_scannable,e.warehouses_evaluated,e.skus_evaluated,"
                + "e.skus_without_sales,e.alerts_opened,e.alerts_suppressed,e.suggestions_created,"
                + "e.scope_warehouse_id,e.note from safety_stock_evaluation e"
                + " order by e.evaluated_at desc, e.id limit ? offset ?",
            rows, (long) pageNo * rows);
    return new SafetyStockEvaluationPage(
        rowsOut.stream().map(
                r ->
                    new SafetyStockEvaluationRow(
                        r.get("id", UUID.class),
                        r.get("evaluated_at", OffsetDateTime.class),
                        r.get("triggered_by_membership_id", UUID.class),
                        r.get("policies_evaluated", Long.class),
                        r.get("policies_not_scannable", Long.class),
                        r.get("warehouses_evaluated", Long.class),
                        r.get("skus_evaluated", Long.class),
                        r.get("skus_without_sales", Long.class),
                        r.get("alerts_opened", Long.class),
                        r.get("alerts_suppressed", Long.class),
                        r.get("suggestions_created", Long.class),
                        r.get("scope_warehouse_id", UUID.class),
                        r.get("note", String.class)))
            .toList(),
        total, pageNo, rows);
  }

  // ============================================================ 预警查询与流转

  /** 预警列表（真分页，默认按提级后的严重度降序 —— 最该处理的排最前）。 */
  public SafetyStockAlertPage alerts(SafetyStockAlertQuery query) {
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size() <= 0 ? 50 : query.size()));
    int pageNo = Math.max(0, query.page());
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    alertConditions(query, where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from safety_stock_alert a" + sqlWhere, binds.toArray())
            .get(0, Long.class);

    var rows =
        sql.fetch(
            "select a.id,a.evaluation_id,a.policy_id,a.policy_scope_type,a.warehouse_id,"
                + "w.code as warehouse_code,w.name as warehouse_name,a.sku_id,s.code as sku_code,"
                + "coalesce(nullif(a.sku_label,''), s.name) as sku_label,a.alert_type,a.severity,"
                + "a.available_quantity,a.threshold_quantity,a.shortfall_quantity,"
                + "a.daily_average_sales,a.days_of_cover,a.status,a.detected_at,a.acknowledged_at,"
                + "a.resolved_at,a.review_note,"
                + "(select sg.suggested_quantity from safety_stock_replenishment_suggestion sg"
                + "  where sg.alert_id = a.id) as suggested_quantity"
                + " from safety_stock_alert a"
                + " join warehouse w on w.tenant_id = a.tenant_id and w.id = a.warehouse_id"
                + " left join sku_ref s on s.tenant_id = a.tenant_id and s.id = a.sku_id"
                + sqlWhere + ALERT_ORDER + " limit ? offset ?",
            append(binds, size, (long) pageNo * size));

    return new SafetyStockAlertPage(
        rows.stream().map(SafetyStockService::alertRow).toList(), total, pageNo, size);
  }

  private void alertConditions(SafetyStockAlertQuery query, List<String> where, List<Object> binds) {
    if (query.warehouseId() != null) {
      where.add("a.warehouse_id = ?");
      binds.add(query.warehouseId());
    }
    if (query.skuId() != null) {
      where.add("a.sku_id = ?");
      binds.add(query.skuId());
    }
    if (notBlank(query.alertType())) {
      where.add("a.alert_type = ?");
      binds.add(query.alertType().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.severity())) {
      where.add("a.severity = ?");
      binds.add(query.severity().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.status())) {
      where.add("a.status = ?");
      binds.add(query.status().trim().toUpperCase(Locale.ROOT));
    } else {
      // 默认不展示已处置的：预警工作台关心的是还没闭环的事。
      where.add("a.status <> 'RESOLVED'");
    }
    if (query.policyId() != null) {
      where.add("a.policy_id = ?");
      binds.add(query.policyId());
    }
    if (query.evaluationId() != null) {
      where.add("a.evaluation_id = ?");
      binds.add(query.evaluationId());
    }
    if (query.platformRiskOnly()) {
      where.add("a.alert_type = 'PLATFORM_STOCKOUT_RISK'");
    }
    if (notBlank(query.keyword())) {
      where.add("(coalesce(nullif(a.sku_label,''), s.name) ilike ? or w.code ilike ? or w.name ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      binds.add(k);
      binds.add(k);
      binds.add(k);
    }
  }

  /** 预警详情（含它关联的补货建议）。跨租户在 RLS 下查不到 → 404，不泄露存在性。 */
  public SafetyStockAlertDetail alert(UUID id) {
    var r = fetchAlert(id);
    var suggestion = suggestionOf(id);
    return new SafetyStockAlertDetail(alertRow(r), suggestion);
  }

  private Record fetchAlert(UUID id) {
    var r =
        sql.fetchOne(
            "select a.id,a.evaluation_id,a.policy_id,a.policy_scope_type,a.warehouse_id,"
                + "w.code as warehouse_code,w.name as warehouse_name,a.sku_id,s.code as sku_code,"
                + "coalesce(nullif(a.sku_label,''), s.name) as sku_label,a.alert_type,a.severity,"
                + "a.available_quantity,a.threshold_quantity,a.shortfall_quantity,"
                + "a.daily_average_sales,a.days_of_cover,a.status,a.detected_at,a.acknowledged_at,"
                + "a.resolved_at,a.review_note,"
                + "(select sg.suggested_quantity from safety_stock_replenishment_suggestion sg"
                + "  where sg.alert_id = a.id) as suggested_quantity"
                + " from safety_stock_alert a"
                + " join warehouse w on w.tenant_id = a.tenant_id and w.id = a.warehouse_id"
                + " left join sku_ref s on s.tenant_id = a.tenant_id and s.id = a.sku_id"
                + " where a.id = ?",
            id);
    if (r == null) {
      throw notFoundAlert(id);
    }
    return r;
  }

  /** 认领一条预警（OPEN → 已认领）。 */
  @Transactional
  public SafetyStockAlertActionResult acknowledge(UUID id, UUID actorId) {
    var r = fetchAlert(id);
    String status = r.get("status", String.class);
    if (!"OPEN".equals(status)) {
      throw bad("预警当前是「" + status + "」，只有待处理的预警可以认领；"
          + "状态只能 OPEN → 已认领 → 已处置 单向推进（DB 触发器也会挡）");
    }
    OffsetDateTime at = OffsetDateTime.now();
    sql.execute(
        "update safety_stock_alert set status = 'ACKNOWLEDGED', acknowledged_at = ?::timestamptz,"
            + " acknowledged_by_membership_id = ? where id = ?",
        at, actorId, id);
    return new SafetyStockAlertActionResult(
        id, SafetyStockAlertStatus.ACKNOWLEDGED, at,
        "已认领。预警读数快照原样保留 —— 它记录的是检测那一刻的事实。");
  }

  /** 处置完成一条预警（已认领 → 已处置）。 */
  @Transactional
  public SafetyStockAlertActionResult resolve(UUID id) {
    var r = fetchAlert(id);
    String status = r.get("status", String.class);
    if (!"ACKNOWLEDGED".equals(status)) {
      throw bad("预警当前是「" + status + "」，只有已认领的预警可以标记为已处置；"
          + "状态只能 OPEN → 已认领 → 已处置 单向推进");
    }
    OffsetDateTime at = OffsetDateTime.now();
    if (at.isBefore(r.get("detected_at", OffsetDateTime.class))) {
      throw bad("处置时间早于预警检测时刻");
    }
    sql.execute(
        "update safety_stock_alert set status = 'RESOLVED', resolved_at = ?::timestamptz where id = ?",
        at, id);
    return new SafetyStockAlertActionResult(
        id, SafetyStockAlertStatus.RESOLVED, at,
        "已处置。已处置的预警不允许复活（否则工作台会反复把已闭环的事推回来）；"
            + "库存后来补上了也不会改写这条预警的读数 —— 要看新的水位请重新评估。");
  }

  // ============================================================ 补货建议

  /** 补货建议列表（真分页）。 */
  public SafetyStockSuggestionPage suggestions(SafetyStockSuggestionQuery query) {
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size() <= 0 ? 50 : query.size()));
    int pageNo = Math.max(0, query.page());
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (query.warehouseId() != null) {
      where.add("g.warehouse_id = ?");
      binds.add(query.warehouseId());
    }
    if (query.skuId() != null) {
      where.add("g.sku_id = ?");
      binds.add(query.skuId());
    }
    if (notBlank(query.status())) {
      where.add("g.status = ?");
      binds.add(query.status().trim().toUpperCase(Locale.ROOT));
    }
    // 「只看算不出量的」：这些是最容易被忽略的一批（拿 0 兜底会变成"不用补"）。
    if (query.unquantifiedOnly()) {
      where.add("g.suggested_quantity is null");
    }
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne(
                "select count(*) from safety_stock_replenishment_suggestion g" + sqlWhere,
                binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            SUGGESTION_SELECT + sqlWhere + " order by g.created_at desc, g.id limit ? offset ?",
            append(binds, size, (long) pageNo * size));
    return new SafetyStockSuggestionPage(
        rows.stream().map(SafetyStockService::suggestionRow).toList(), total, pageNo, size);
  }

  private static final String SUGGESTION_SELECT =
      "select g.id,g.alert_id,g.policy_id,g.warehouse_id,w.code as warehouse_code,g.sku_id,"
          + "s.code as sku_code,coalesce(nullif(g.sku_label,''), s.name) as sku_label,"
          + "g.suggested_quantity,g.daily_average_sales,g.coverage_days,g.target_stock_quantity,"
          + "g.unquantified_reason,g.external_purchase_reference,g.status,g.created_at,"
          + "g.dismissed_at,g.dismissal_reason"
          + " from safety_stock_replenishment_suggestion g"
          + " join warehouse w on w.tenant_id = g.tenant_id and w.id = g.warehouse_id"
          + " left join sku_ref s on s.tenant_id = g.tenant_id and s.id = g.sku_id";

  private SafetyStockSuggestionRow suggestionOf(UUID alertId) {
    var r =
        sql.fetchOne(
            SUGGESTION_SELECT + " where g.alert_id = ?",
            alertId);
    return r == null ? null : suggestionRow(r);
  }

  /** 采纳一条补货建议（PROPOSED → 已采纳）。6.2.1.2 未交付，采购单号留空。 */
  @Transactional
  public SafetyStockSuggestionAction acknowledgeSuggestion(UUID id) {
    var current = fetchSuggestion(id);
    if (!"PROPOSED".equals(current.get("status", String.class))) {
      throw bad("建议当前是「" + current.get("status", String.class) + "」，只有待处理的建议可以采纳；"
          + "状态只能 PROPOSED → 已采纳/已忽略 单向推进");
    }
    sql.execute(
        "update safety_stock_replenishment_suggestion set status = 'ACKNOWLEDGED' where id = ?", id);
    return new SafetyStockSuggestionAction(
        id, SafetyStockSuggestionStatus.ACKNOWLEDGED, null,
        "已采纳。6.2.1.2 补货建议尚未交付（属 06 采购域、跨服务独立库），"
            + "因此本模块只落库建议、不跨库写采购单；externalPurchaseReference 留空，"
            + "等它交付后由它回写单号。");
  }

  /** 忽略一条补货建议（PROPOSED → 已忽略）。忽略必须留理由，否则会反复生成幽灵建议。 */
  @Transactional
  public SafetyStockSuggestionAction dismissSuggestion(UUID id, String reason, UUID actorId) {
    var current = fetchSuggestion(id);
    if (!"PROPOSED".equals(current.get("status", String.class))) {
      throw bad("建议当前是「" + current.get("status", String.class)
          + "」，只有待处理的建议可以忽略；状态只能单向推进");
    }
    if (!notBlank(reason)) {
      throw bad("忽略建议必须写理由：不写理由的话下次评估还会生成同一条，"
          + "运营会看到一条永远消不掉的幽灵建议");
    }
    sql.execute(
        "update safety_stock_replenishment_suggestion set status = 'DISMISSED',"
            + " dismissed_at = ?::timestamptz, dismissed_by_membership_id = ?, dismissal_reason = ?"
            + " where id = ?",
        OffsetDateTime.now(), actorId, reason.trim(), id);
    return new SafetyStockSuggestionAction(
        id, SafetyStockSuggestionStatus.DISMISSED, reason.trim(),
        "已忽略。忽略理由与操作人已留档；重新评估不会复活这条建议"
            + "（每条预警只对应一条建议，且预警本身不允许复活）。");
  }

  private Record fetchSuggestion(UUID id) {
    var r = sql.fetchOne("select id,status,alert_id from safety_stock_replenishment_suggestion where id = ?", id);
    if (r == null) {
      throw notFoundSuggestion(id);
    }
    return r;
  }

  // ============================================================ 取数与换算

  /** 可用量投影：按 (仓, SKU) 聚合 SELLABLE 口径的 balance.available（与 7.2.2.1 池容量同源）。 */
  private Map<PairKey, BigDecimal> availableByWarehouseSku(Set<UUID> skuIds, Set<UUID> warehouseIds) {
    var result = new HashMap<PairKey, BigDecimal>();
    if (skuIds.isEmpty() || warehouseIds.isEmpty()) {
      return result;
    }
    String in = placeholders(skuIds.size());
    var binds = new ArrayList<Object>();
    binds.add(tenant());
    binds.addAll(skuIds);
    // 绑定顺序必须与 SQL 里的占位符顺序逐一对应：tenant → sku 列表 → warehouse 列表。
    // 少加这一段会让 warehouse 的 IN 列表拿不到参数，而 jOOQ 报的是
    // 「未设定参数值 3 的内容」，完全指向不了真正的原因。
    binds.addAll(warehouseIds);
    for (var r :
        sql.fetch(
            "select b.warehouse_id, b.sku_id, sum(b.available) as available from inventory_balance b"
                + " where b.tenant_id = ? and b.sku_id in " + in
                + "   and b.warehouse_id in " + placeholders(warehouseIds.size())
                + "   and exists (select 1 from inventory_quality_status q"
                + "     where q.tenant_id = b.tenant_id and q.id = b.quality_status_id"
                + "       and q.role = 'SELLABLE')"
                + " group by b.warehouse_id, b.sku_id",
            binds.toArray())) {
      result.put(
          new PairKey(r.get("warehouse_id", UUID.class), r.get("sku_id", UUID.class)),
          r.get("available", BigDecimal.class));
    }
    return result;
  }

  /**
   * 日均销量：Σunits / Σ天数。
   *
   * <p>没有登记行的 SKU 返回 {@code null}（而不是 0）：0 会被读成「永远卖不动、永远不会断货」，
   * 而真实状态是「没人告诉我们卖了多少」。
   */
  private Map<UUID, BigDecimal> dailyAverageBySku(Set<UUID> skuIds) {
    var result = new HashMap<UUID, BigDecimal>();
    if (skuIds.isEmpty()) {
      return result;
    }
    String in = placeholders(skuIds.size());
    var binds = new ArrayList<Object>();
    binds.add(tenant());
    binds.addAll(skuIds);
    for (var r :
        sql.fetch(
            "select s.sku_id, sum(s.units_sold) as units_sold,"
                + " sum((s.period_end - s.period_start + 1)) as covered_days"
                + " from safety_stock_sales s where s.tenant_id = ? and s.sku_id in " + in
                + " group by s.sku_id",
            binds.toArray())) {
      result.put(
          r.get("sku_id", UUID.class),
          average(
              r.get("units_sold", BigDecimal.class),
              decimal(r, "covered_days").longValue()));
    }
    return result;
  }

  /** 该档是否覆盖这个 SKU（按 scope 判定）。 */
  private static boolean covers(SafetyStockScopeType scope, LivePolicy policy, SafetyStockEvaluationRequest.SafetyStockEvaluationSkuContext c) {
    return switch (scope) {
      case SKU -> Objects.equals(policy.skuId(), c.skuId());
      case CATEGORY -> c.categoryId() != null && Objects.equals(policy.categoryId(), c.categoryId());
      case DEFAULT -> true;
    };
  }

  /**
   * 提级后的严重度 —— 蓝图那句「FBA 断货造成的损失远大于自有仓」的落点。
   *
   * <p>平台断货一律提一级。与 {@link #ALERT_ORDER} 里的 SQL 是同一套规则的两次实现。
   */
  static AlertSeverity effectiveSeverity(SafetyStockAlertType type, AlertSeverity severity) {
    return type.platformRisk() ? severity.escalate() : severity;
  }

  private static AlertSeverity effectiveSeverity(AlertDraft d) {
    return effectiveSeverity(d.type(), d.policy().severity());
  }

  private static String reviewNote(AlertDraft d) {
    var notes = new ArrayList<String>();
    if (d.type().platformRisk()) {
      notes.add("平台仓断货风险：已按上一级严重度处理（配置为「" + d.policy().severity().label()
          + "」），因为 FBA 断货造成的损失远大于自有仓");
    }
    if (d.dailyAverage() == null) {
      notes.add("该 SKU 尚未登记销量，可售天数算不出来（未登记 ≠ 卖不动，这里不拿 0 兜底）");
    } else if (d.daysOfCover() == null) {
      notes.add("日均销量为 0，可售天数无意义（除以零），因此留空而不是写成 0 天");
    }
    if (d.suggested() == null && d.policy().autoSuggest()) {
      notes.add(notBlank(d.unquantifiedReason())
          ? d.unquantifiedReason() : "未能推算建议量，原因见补货建议");
    }
    return String.join("；", notes);
  }

  private static BigDecimal targetStock(AlertDraft d) {
    if (d.suggested() == null || d.dailyAverage() == null || d.policy().coverageDays() == null) {
      return null;
    }
    return d.threshold().add(
        d.dailyAverage().multiply(d.policy().coverageDays()).setScale(6, RoundingMode.HALF_UP));
  }

  // ============================================================ 行映射

  private static SafetyStockPolicyRow policyRow(Record r) {
    return new SafetyStockPolicyRow(
        r.get("id", UUID.class),
        SafetyStockScopeType.valueOf(r.get("scope_type", String.class)),
        r.get("category_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_label", String.class),
        decimal(r, "threshold_quantity"),
        AlertSeverity.valueOf(r.get("alert_severity", String.class)),
        r.get("reorder_coverage_days", BigDecimal.class),
        r.get("auto_suggest_replenishment", Boolean.class),
        r.get("effective_from", OffsetDateTime.class),
        r.get("effective_to", OffsetDateTime.class),
        r.get("note", String.class),
        r.get("created_by_membership_id", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static SafetyStockAlertRow alertRow(Record r) {
    SafetyStockAlertType type = SafetyStockAlertType.valueOf(r.get("alert_type", String.class));
    AlertSeverity severity = AlertSeverity.valueOf(r.get("severity", String.class));
    return new SafetyStockAlertRow(
        r.get("id", UUID.class),
        r.get("evaluation_id", UUID.class),
        r.get("policy_id", UUID.class),
        SafetyStockScopeType.valueOf(r.get("policy_scope_type", String.class)),
        r.get("warehouse_id", UUID.class),
        r.get("warehouse_code", String.class),
        r.get("warehouse_name", String.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_label", String.class),
        type,
        severity,
        effectiveSeverity(type, severity),
        decimal(r, "available_quantity"),
        decimal(r, "threshold_quantity"),
        decimal(r, "shortfall_quantity"),
        r.get("daily_average_sales", BigDecimal.class),
        r.get("days_of_cover", BigDecimal.class),
        r.get("suggested_quantity", BigDecimal.class),
        SafetyStockAlertStatus.valueOf(r.get("status", String.class)),
        r.get("detected_at", OffsetDateTime.class),
        r.get("acknowledged_at", OffsetDateTime.class),
        r.get("resolved_at", OffsetDateTime.class),
        r.get("review_note", String.class));
  }

  private static SafetyStockSuggestionRow suggestionRow(Record r) {
    return new SafetyStockSuggestionRow(
        r.get("id", UUID.class),
        r.get("alert_id", UUID.class),
        r.get("policy_id", UUID.class),
        r.get("warehouse_id", UUID.class),
        r.get("warehouse_code", String.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_label", String.class),
        r.get("suggested_quantity", BigDecimal.class),
        r.get("daily_average_sales", BigDecimal.class),
        r.get("coverage_days", BigDecimal.class),
        r.get("target_stock_quantity", BigDecimal.class),
        r.get("unquantified_reason", String.class),
        r.get("external_purchase_reference", String.class),
        SafetyStockSuggestionStatus.valueOf(r.get("status", String.class)),
        r.get("created_at", OffsetDateTime.class),
        r.get("dismissed_at", OffsetDateTime.class),
        r.get("dismissal_reason", String.class));
  }

  // ============================================================ 校验与工具

  private Map<UUID, String> bindings(List<SafetyStockPolicyInput.SafetyStockWarehouseBinding> input) {
    var wanted = new LinkedHashMap<UUID, String>();
    if (input == null) {
      return wanted;
    }
    for (var b : input) {
      if (b == null || b.warehouseId() == null) {
        continue;
      }
      String kind = b.warehouseKind() == null ? "" : b.warehouseKind().trim().toUpperCase(Locale.ROOT);
      if (!kind.equals("OWN") && !kind.equals("PLATFORM_FBA")) {
        throw bad("挂仓的 warehouseKind 只能是 OWN（自有仓）或 PLATFORM_FBA（平台仓），收到「"
            + b.warehouseKind() + "」：这一列决定该档产出的是「自有仓耗尽」还是「平台断货风险」，"
            + "填别的值就分不出这两件事");
      }
      String previous = wanted.put(b.warehouseId(), kind);
      if (previous != null && !previous.equals(kind)) {
        throw bad("同一个仓 " + b.warehouseId() + " 被声明成了两种 kind（" + previous + " 与 " + kind
            + "）");
      }
    }
    return wanted;
  }

  /**
   * 应用层同 kind 校验。
   *
   * <p>只为了给出更准的报错；DB 的延迟约束触发器（V114 §7.4）才是保证。这里挡不住的场景
   * （分多次请求挂仓）由那一条兜住，错误信息里同样写明了为什么不允许混挂。
   */
  private static void verifySingleKind(Map<UUID, String> wanted) {
    Set<String> kinds = new HashSet<>(wanted.values());
    if (kinds.size() > 1) {
      throw bad("同一档阈值不能同时挂自有仓与平台仓（读到 " + String.join(",", kinds)
          + "）：FBA 与自有仓是两个独立的池子，混在一档里求和会得到一个「还剩 N 件」"
          + "而实际上其中一个仓早已归零的数，那样就分不出「平台断货风险」与「自有仓耗尽」——请拆成两档");
    }
  }

  /** 挂的仓必须属于本租户且真实存在（跨租户在 RLS 下查不到 → 报错而不是静默跳过）。 */
  private void verifyWarehouses(Set<UUID> ids) {
    if (ids.isEmpty()) {
      return;
    }
    String in = placeholders(ids.size());
    var binds = new ArrayList<Object>();
    binds.add(tenant());
    binds.addAll(ids);
    var found =
        sql.fetch("select id from warehouse where tenant_id = ? and id in " + in, binds.toArray())
            .stream()
            .map(r -> r.get("id", UUID.class))
            .collect(java.util.stream.Collectors.toSet());
    if (found.size() != ids.size()) {
      var missing = new ArrayList<>(ids);
      missing.removeAll(found);
      throw bad("这些仓库不存在或不属于当前租户：" + missing
          + "（跨租户的仓在 RLS 下查不到，这是刻意的不泄露，不是数据缺失）");
    }
  }

  private long countWarehouses(UUID policyId) {
    return sql.fetchOne(
            "select count(*) from safety_stock_policy_warehouse where policy_id = ?", policyId)
        .get(0, Long.class);
  }

  private static boolean sameSlot(Record target, SafetyStockScopeType scope, UUID categoryId, UUID skuId) {
    return target.get("scope_type", String.class).equals(scope.name())
        && Objects.equals(target.get("category_id", UUID.class), categoryId)
        && Objects.equals(target.get("sku_id", UUID.class), skuId);
  }

  private static BigDecimal requireThreshold(BigDecimal v) {
    BigDecimal t = v == null ? QTY_ZERO : v;
    if (t.signum() < 0) {
      throw bad("安全库存阈值不能为负数，收到 " + t.toPlainString() + "：负阈值会让「有货」也报警");
    }
    return t;
  }

  private static BigDecimal requireUnits(BigDecimal v) {
    if (v == null) {
      throw bad("销量不能为空");
    }
    if (v.signum() < 0) {
      throw bad("销量不能为负数，收到 " + v.toPlainString());
    }
    return v;
  }

  private static BigDecimal average(BigDecimal units, long days) {
    if (days <= 0) {
      return QTY_ZERO;
    }
    return units.divide(BigDecimal.valueOf(days), 6, RoundingMode.HALF_UP);
  }

  private static long days(LocalDate from, LocalDate to) {
    return ChronoUnit.DAYS.between(from, to) + 1;
  }

  private SafetyStockScopeType scope(String raw) {
    try {
      return SafetyStockScopeType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的阈值范围「" + raw + "」，只能是 DEFAULT / CATEGORY / SKU");
    }
  }

  private AlertSeverity severity(String raw) {
    try {
      return AlertSeverity.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的预警级别「" + raw + "」，只能是 CRITICAL / WARNING / INFO");
    }
  }

  private UUID tenant() {
    return sql.fetchOne("select current_setting('app.current_tenant',true)::uuid as t")
        .get("t", UUID.class);
  }

  private static Object[] append(List<Object> binds, Object... more) {
    var all = new ArrayList<>(binds);
    all.addAll(java.util.Arrays.asList(more));
    return all.toArray();
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  private static BigDecimal decimal(Record r, String field) {
    var v = r.get(field, BigDecimal.class);
    return v == null ? QTY_ZERO : v;
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(UUID id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "安全库存阈值不存在或不属于当前租户：" + id);
  }

  private static ResponseStatusException notFoundAlert(UUID id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "安全库存预警不存在或不属于当前租户：" + id);
  }

  private static ResponseStatusException notFoundSuggestion(UUID id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "补货建议不存在或不属于当前租户：" + id);
  }

  // ============================================================ 内部结构

  /** (仓, SKU) 二元键 —— 评估的最小比较单位。 */
  private record PairKey(UUID warehouseId, UUID skuId) {}

  /** 在效阈值档的一次求值结果（已带上它命中的那个仓）。 */
  private record LivePolicy(
      UUID id,
      SafetyStockScopeType scope,
      UUID categoryId,
      UUID skuId,
      String skuLabel,
      BigDecimal threshold,
      AlertSeverity severity,
      BigDecimal coverageDays,
      boolean autoSuggest,
      OffsetDateTime effectiveFrom,
      UUID warehouseId,
      String warehouseCode,
      String warehouseName,
      String warehouseKind) {

    static LivePolicy of(Record r) {
      return new LivePolicy(
          r.get("id", UUID.class),
          SafetyStockScopeType.valueOf(r.get("scope_type", String.class)),
          r.get("category_id", UUID.class),
          r.get("sku_id", UUID.class),
          nullToEmpty(r.get("sku_label", String.class)),
          r.get("threshold_quantity", BigDecimal.class),
          AlertSeverity.valueOf(r.get("alert_severity", String.class)),
          r.get("reorder_coverage_days", BigDecimal.class),
          r.get("auto_suggest_replenishment", Boolean.class),
          r.get("effective_from", OffsetDateTime.class),
          null, null, null, null);
    }

    LivePolicy withWarehouse(UUID warehouseId, String code, String name, String kind) {
      return new LivePolicy(id, scope, categoryId, skuId, skuLabel, threshold, severity, coverageDays,
          autoSuggest, effectiveFrom, warehouseId, code, name, kind);
    }

    boolean platform() {
      return "PLATFORM_FBA".equals(warehouseKind);
    }
  }

  /** 一次待写入的预警（尚未拿到自增 id，插入时生成）。 */
  private record AlertDraft(
      UUID evaluationId,
      LivePolicy policy,
      PairKey key,
      SafetyStockAlertType type,
      BigDecimal available,
      BigDecimal threshold,
      BigDecimal shortfall,
      BigDecimal dailyAverage,
      BigDecimal daysOfCover,
      BigDecimal suggested,
      String unquantifiedReason,
      OffsetDateTime detectedAt) {}
}
