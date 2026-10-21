package com.starcon.trade;

import static com.starcon.trade.ReplenishmentSuggestionModels.*;
import static com.starcon.trade.ReplenishmentSuggestionRules.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.2.1.2 补货建议。
 *
 * <p>蓝图原话：「计算建议补货量：目标库存覆盖天数 × 日均销量 −（可用 + 在途），并结合 MOQ、
 * 箱规、采购起订额做取整修正，给出建议供应商与到货时间。」输出「补货建议（建议量、取整说明、
 * 预计到货日、预计费用）；支持接受/调整/忽略」。
 *
 * <p><b>本模块只做一件事：把「该补多少」变成一条有凭据、有算式、可处置的记录。</b>
 *
 * <p><b>九条实现纪律</b>：
 *
 * <ol>
 *   <li><b>时钟用数据库的</b>：评估基准日取 {@code (current_timestamp at time zone 'UTC')::date}，
 *       计算时刻取 {@code clock_timestamp()}。日期口径统一 UTC。</li>
 *   <li><b>复用 6.2.1.1 的输入</b>：日均销量读 {@code stockout_sales_velocity}（**只读**，
 *       那张表的写入所有权是 6.2.1.1）；可用量与在途经 {@link StockoutInventoryPort} 读
 *       warehouse 的授权只读接口。本模块**不重算**可售量，也不重查在途。</li>
 *   <li><b>「答不出来」与「不需要补」不折叠</b>：{@code NO_SALES_DATA} / {@code NO_QUOTE} /
 *       {@code NO_BOX_SPEC} 三种「答不出来」与 {@code HOLD}「不需要补」是四个不同的状态。
 *       把「没登记数据」折叠成「库存充足」是最容易犯也最贵的错。</li>
 *   <li><b>在途只扣「覆盖窗口内能到」的那部分</b>：没有预计到达日的在途不计入 —— 它救不了
 *       确定的那一天。在途合计照实给，界面上分开显示，让用户看得见「扣了哪些」。</li>
 *   <li><b>箱规量不到就是量不到</b>：catalog 没登记换算比例时箱规是 null（= 答不出来），
 *       绝不猜成 1 —— 猜成 1 会把订量算少一箱（1 箱 = 12 件时差 12 倍）且页面看不出异常。</li>
 *   <li><b>跨币种绝不比价</b>：12 财务才有汇率表。所以供应商择优不把价格当跨供应商排序键，
 *       采购起订额也只在币种相同时生效。</li>
 *   <li><b>预计到货日的推导依据必须说清</b>：供应商交期（catalog 报价）+ 生产周期（采购侧配置）
 *       —— <b>10 头程时效未交付</b>（warehouse 只有逐票 ETA，不是可用时效基准），这一点写在
 *       {@code arrival_basis} 里，不藏。</li>
 *   <li><b>结论落库成批次</b>：一次计算 = 一份批次 + 逐行建议 + 逐项拆解 + 处置留痕。</li>
 *   <li><b>建议必须展示计算过程</b>：拆解表逐行记下算式里的每一项，界面点开详情就能看到
 *       「这个数是怎么来的」—— 蓝图加粗句「用户不信任的预测不会被采纳」。</li>
 * </ol>
 */
@Service
public class ReplenishmentSuggestionService {

  /** 数量刻度：与 DB 的 numeric(19,6) 对齐。 */
  private static final int QUANTITY_SCALE = 6;

  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);

  /**
   * 纳入补货口径的在途类型（与 6.2.1.1 同一套）。
   *
   * <p>调拨 / 头程 / 采购三类都是「往我方仓里补货」的路。刻意<b>排除</b> {@code FBA_OUTBOUND}：
   * 那是「从我方仓发往平台仓」，方向相反 —— 把它算成补货会让缺口被一个<b>正在离开</b>的量掩盖。
   */
  static final List<String> REPLENISH_TRANSIT_KINDS = StockoutRiskService.REPLENISH_TRANSIT_KINDS;

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  /**
   * 单次计算最多逐个查多少个 SKU 的商品主数据（分类 + 单位换算 + 报价）。
   *
   * <p>每次计算都要逐个 SKU 调 catalog 的授权只读接口，SKU 数无上限时会把上游打挂。
   * 超出就<b>如实报出截断</b>（批次 truncated=true + 说明），而不是静默少算一批。
   */
  static final int MAX_CATALOG_LOOKUPS = 200;

  private final DSLContext sql;
  private final StockoutInventoryPort inventory;
  private final ReplenishmentCatalogPort catalog;

  public ReplenishmentSuggestionService(
      DSLContext sql, StockoutInventoryPort inventory, ReplenishmentCatalogPort catalog) {
    this.sql = sql;
    this.inventory = inventory;
    this.catalog = catalog;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>输入分属四个服务 / 模块：日均销量在 6.2.1.1、可用量与在途在 warehouse、
   * MOQ / 箱规 / 交期 / 价格在 catalog、头程时效在 10。哪一个接通了必须由接口自己报出。
   */
  public ReplenishmentCapabilities capabilities() {
    return new ReplenishmentCapabilities(
        true,
        "6.2.1.1 缺货与断货风险（trade 服务，集成头已交付）",
        "日均销量读 6.2.1.1 的销售速度登记（stockout_sales_velocity）——本模块只读，不改它一个字。"
            + "登记本身带统计窗口与生效期，所以「3 月 1 日那天用的日均销是多少」答得回来；"
            + "本模块的「近期增长率」由当前生效版本与上一个已收口版本相比得出。",
        true,
        true,
        true,
        "MOQ、采购单位、供应商交期、币种与阶梯价取自 catalog 3.6.2.1 的有效报价列表。"
            + "刻意**不传 supplierId**：供应商还没选出来之前不能先让 catalog 在某一个供应商内部选一条"
            + "（优先级只在同一个供应商内部可比，跨供应商比优先级是错的）。",
        true,
        "箱规取自 catalog 3.1.2.4 的单位换算：1 个采购单位含多少基础单位（role='PURCHASE' 的 quantity_per_base）。"
            + "量不到时是 null（= 答不出来），绝不猜成 1。",
        false,
        "6.1.1.3 供应商评级**未交付** ⇒ 本模块**不按评级择优**，改用可解释的顺序："
            + "① 交期短优先 ② 起订门槛低优先（折算到基础单位后比较）③ 同币种内单价低优先（跨币种绝不比价）"
            + "④ 供应商名升序（确定性 tie-break）。",
        false,
        "10 头程时效**未交付**：warehouse 的 10.4.1.1 / 10.4.1.2 给的是**逐票**的发运单与在途 ETA，"
            + "不是可用于新单预计到货日的时效基准（逐票 ETA 不能代表下一单）。"
            + "所以预计到货日用「采购备货周期（供应商交期 + 生产周期）」推导，并在依据里写明这一条。",
        false,
        "6.2.2.1 采购申请**未交付** ⇒ 本模块只产出建议与处置留痕，不代它建申请单。",
        "覆盖天数目标 = 按 SKU 配置 > 按分类配置 > 租户默认配置 > 计算时传入的兜底值。"
            + "生效期区间语义 [effective_from, effective_to)；同一作用范围同时只允许一条未收口的配置。",
        "净需求量 = max(0, 覆盖天数 × 日均销量 − 有效可用量 − 覆盖窗口内可到达在途量)。"
            + "有效可用量取 warehouse 7.1.1.3 的可售量（sellable），本模块不重算；"
            + "在途只计 ETA 落在 [评估日, 评估日+覆盖天数] 内的腿（没有 ETA 的救不了确定的那一天）。",
        "取整三步：① 整箱/整件 = ceil(净需求量 ÷ 箱规)（向上取整，向下会不够）；"
            + "② MOQ = 采购单位数不足时抬到 MOQ；③ 采购起订额 = 预计费用不足时抬到刚好达标"
            + "（只在币种相同且取得到适用阶梯价时生效，跨币种不比较金额）。"
            + "每一步都写进「取整说明」，用户看得见订量为什么比净需求量大。",
        "预计到货日 = 评估日 + 供应商交期（catalog 报价 lead_time_days）+ 生产周期（采购侧配置，"
            + "未维护时按 0 天计并在依据里写明）。**10 头程时效未交付**，不参与推导。",
        "预计费用 = 建议采购单位数 × 适用阶梯单价（开口阶梯：取 minQuantity ≤ 数量 里最大的一档；"
            + "数量低于第一档则取不到价，预计费用为 null 而不是 0）。币种取报价的币种，跨币种不相加。",
        "本模块的四类结论互不折叠：REPLENISH 真建议 / HOLD 不需要补 / NO_SALES_DATA 缺日均销量 / "
            + "NO_QUOTE 缺有效报价 / NO_BOX_SPEC 缺箱规。只有 REPLENISH 才能接受 / 调整 / 忽略。");
  }

  // ================================================================ 覆盖天数配置

  /**
   * 登记一条覆盖天数目标（版本化：同一作用范围已有未收口的配置时，新配置把旧的那条<b>收口</b>）。
   *
   * <p>覆盖不是覆盖 —— 改配置会让「当时按哪个覆盖天数算的」永远查不回来，而那正是本模块的可追溯性承诺。
   */
  @Transactional
  public ReplenishmentCoveragePolicyRow upsertCoveragePolicy(
      ReplenishmentCoveragePolicyInput input, UUID actorId) {
    if (input.effectiveTo() != null && input.effectiveTo().isBefore(input.effectiveFrom())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "失效日不能早于生效起点");
    }
    if (input.scope() == ReplenishmentCoverageScope.SKU && input.skuId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "按 SKU 配置时必须给出 skuId");
    }
    if (input.scope() == ReplenishmentCoverageScope.CATEGORY
        && (input.categoryCode() == null || input.categoryCode().isBlank())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "按分类配置时必须给出 categoryCode");
    }
    if (input.scope() == ReplenishmentCoverageScope.DEFAULT
        && (input.skuId() != null || (input.categoryCode() != null && !input.categoryCode().isBlank()))) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "租户默认配置不能带 skuId 或 categoryCode —— 那会变成另一层作用域");
    }
    UUID tenantId = currentTenant();
    // 同一作用范围已有未收口的配置 → 先收口旧的（同一事务里，失败会一起回滚）。
    var open =
        sql.fetchOne(
            "select id, effective_from from replenishment_coverage_policy"
                + " where tenant_id = ?::uuid and scope = ?::varchar and effective_to is null"
                + "   and (case when scope = 'SKU' then sku_id = ?::uuid"
                + "         when scope = 'CATEGORY' then category_code = ?::varchar"
                + "         else true end)",
            tenantId,
            input.scope().name(),
            input.skuId(),
            input.categoryCode() == null ? null : input.categoryCode().trim());
    if (open != null) {
      LocalDate previousFrom = open.get("effective_from", LocalDate.class);
      if (!input.effectiveFrom().isAfter(previousFrom)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "同一作用范围已有一条从 "
                + previousFrom
                + " 起生效的覆盖天数配置。新配置的生效起点必须晚于它 —— 否则同一时间点会有两个覆盖天数，「按哪个算」就没有唯一答案了");
      }
      sql.execute(
          "update replenishment_coverage_policy set effective_to = ?::date"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          input.effectiveFrom(),
          tenantId,
          open.get("id", UUID.class));
    }
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into replenishment_coverage_policy(id,tenant_id,scope,sku_id,category_code,"
            + " coverage_days,effective_from,effective_to,note,created_by,created_at)"
            + " values (?::uuid,?::uuid,?::varchar,?::uuid,?::varchar,?::int,?::date,?::date,?,?::uuid,?::timestamptz)",
        id,
        tenantId,
        input.scope().name(),
        input.skuId(),
        input.categoryCode() == null ? null : input.categoryCode().trim(),
        input.coverageDays(),
        input.effectiveFrom(),
        input.effectiveTo(),
        input.note() == null ? "" : input.note(),
        actorId,
        OffsetDateTime.now(ZoneOffset.UTC));
    return coveragePolicyRow(id);
  }

  /** 覆盖天数配置列表（真分页 + 真筛选）。 */
  public ReplenishmentCoveragePolicyPage pageCoveragePolicies(
      String scope, UUID skuId, String categoryCode, boolean effectiveOnly, int page, int size) {
    int pageNo = Math.max(0, page);
    int pageSize = pageSize(size);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?::uuid");
    binds.add(currentTenant());
    if (scope != null && !scope.isBlank()) {
      where.add("scope = ?::varchar");
      binds.add(scope.trim());
    }
    if (skuId != null) {
      where.add("sku_id = ?::uuid");
      binds.add(skuId);
    }
    if (categoryCode != null && !categoryCode.isBlank()) {
      where.add("category_code = ?::varchar");
      binds.add(categoryCode.trim());
    }
    if (effectiveOnly) {
      where.add("(effective_to is null or effective_to > current_date)");
    }
    String clause = String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from replenishment_coverage_policy where " + clause, binds.toArray())
            .get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            "select id,scope,sku_id,category_code,coverage_days,effective_from,effective_to,note,created_at"
                + " from replenishment_coverage_policy where "
                + clause
                + " order by scope, effective_from desc, id desc limit ? offset ?",
            append(binds, pageSize, (long) pageNo * pageSize).toArray());
    List<ReplenishmentCoveragePolicyRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(coveragePolicyItem(row));
    }
    return new ReplenishmentCoveragePolicyPage(items, total, pageNo, pageSize);
  }

  private ReplenishmentCoveragePolicyRow coveragePolicyRow(UUID id) {
    var row =
        sql.fetchOne(
            "select id,scope,sku_id,category_code,coverage_days,effective_from,effective_to,note,created_at"
                + " from replenishment_coverage_policy where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "覆盖天数配置不存在");
    }
    return coveragePolicyItem(row);
  }

  private static ReplenishmentCoveragePolicyRow coveragePolicyItem(Record row) {
    LocalDate effectiveTo = row.get("effective_to", LocalDate.class);
    String scope = row.get("scope", String.class);
    return new ReplenishmentCoveragePolicyRow(
        row.get("id", UUID.class),
        scope,
        coverageScopeLabel(scope),
        row.get("sku_id", UUID.class),
        row.get("category_code", String.class),
        row.get("coverage_days", Integer.class),
        row.get("effective_from", LocalDate.class),
        effectiveTo,
        row.get("note", String.class),
        row.get("created_at", OffsetDateTime.class),
        effectiveTo == null);
  }

  // ================================================================ 采购起订额 / 生产周期配置

  /** 登记一条采购起订额 / 生产周期配置（同样版本化：新配置收口旧配置）。 */
  @Transactional
  public ReplenishmentOrderPolicyRow upsertOrderPolicy(
      ReplenishmentOrderPolicyInput input, UUID actorId) {
    if (input.effectiveTo() != null && input.effectiveTo().isBefore(input.effectiveFrom())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "失效日不能早于生效起点");
    }
    if ((input.supplierId() == null) != (input.supplierRoleId() == null)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "供应商主体 id 与供应商角色 id 必须成对给出 —— 只给一个就分不清说的是哪个供应商");
    }
    if ((input.minOrderAmount() == null) != (input.currency() == null)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "采购起订额与它的币种必须成对给出 —— 只有金额没有币种的门槛是没法比对的");
    }
    if (input.currency() != null && !input.currency().matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是 ISO 4217 三位大写码");
    }
    UUID tenantId = currentTenant();
    var open =
        sql.fetchOne(
            "select id, effective_from from replenishment_order_policy"
                + " where tenant_id = ?::uuid and effective_to is null"
                + "   and (case when supplier_id is null then supplier_id is null"
                + "         else supplier_id = ?::uuid and supplier_role_id = ?::uuid end)",
            tenantId,
            input.supplierId(),
            input.supplierRoleId());
    if (open != null) {
      LocalDate previousFrom = open.get("effective_from", LocalDate.class);
      if (!input.effectiveFrom().isAfter(previousFrom)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "同一供应商已有一条从 "
                + previousFrom
                + " 起生效的采购配置。新配置的生效起点必须晚于它 —— 否则同一时间点会有两个起订额");
      }
      sql.execute(
          "update replenishment_order_policy set effective_to = ?::date"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          input.effectiveFrom(),
          tenantId,
          open.get("id", UUID.class));
    }
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into replenishment_order_policy(id,tenant_id,supplier_id,supplier_role_id,"
            + " min_order_amount,currency,production_lead_time_days,effective_from,effective_to,note,created_by,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::uuid,?::numeric,?::varchar,?::int,?::date,?::date,?,?::uuid,?::timestamptz)",
        id,
        tenantId,
        input.supplierId(),
        input.supplierRoleId(),
        input.minOrderAmount(),
        input.currency(),
        input.productionLeadTimeDays(),
        input.effectiveFrom(),
        input.effectiveTo(),
        input.note() == null ? "" : input.note(),
        actorId,
        OffsetDateTime.now(ZoneOffset.UTC));
    return orderPolicyRow(id);
  }

  /** 采购起订额 / 生产周期配置列表（真分页 + 真筛选）。 */
  public ReplenishmentOrderPolicyPage pageOrderPolicies(
      UUID supplierId, boolean effectiveOnly, int page, int size) {
    int pageNo = Math.max(0, page);
    int pageSize = pageSize(size);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?::uuid");
    binds.add(currentTenant());
    if (supplierId != null) {
      where.add("supplier_id = ?::uuid");
      binds.add(supplierId);
    }
    if (effectiveOnly) {
      where.add("(effective_to is null or effective_to > current_date)");
    }
    String clause = String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from replenishment_order_policy where " + clause, binds.toArray())
            .get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            "select id,supplier_id,supplier_role_id,min_order_amount,currency,"
                + " production_lead_time_days,effective_from,effective_to,note,created_at"
                + " from replenishment_order_policy where "
                + clause
                + " order by supplier_id nulls first, effective_from desc, id desc limit ? offset ?",
            append(binds, pageSize, (long) pageNo * pageSize).toArray());
    List<ReplenishmentOrderPolicyRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(orderPolicyItem(row));
    }
    return new ReplenishmentOrderPolicyPage(items, total, pageNo, pageSize);
  }

  private ReplenishmentOrderPolicyRow orderPolicyRow(UUID id) {
    var row =
        sql.fetchOne(
            "select id,supplier_id,supplier_role_id,min_order_amount,currency,"
                + " production_lead_time_days,effective_from,effective_to,note,created_at"
                + " from replenishment_order_policy where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "采购起订额配置不存在");
    }
    return orderPolicyItem(row);
  }

  private ReplenishmentOrderPolicyRow orderPolicyItem(Record row) {
    LocalDate effectiveTo = row.get("effective_to", LocalDate.class);
    return new ReplenishmentOrderPolicyRow(
        row.get("id", UUID.class),
        row.get("supplier_id", UUID.class),
        row.get("supplier_role_id", UUID.class),
        "",
        row.get("min_order_amount", BigDecimal.class),
        row.get("currency", String.class),
        row.get("production_lead_time_days", Integer.class),
        row.get("effective_from", LocalDate.class),
        effectiveTo,
        row.get("note", String.class),
        row.get("created_at", OffsetDateTime.class),
        effectiveTo == null);
  }

  // ================================================================ 计算（一次 = 一份批次）

  /**
   * 计算一次补货建议并落库。
   *
   * <p><b>SKU 全集</b> = 有可售量记录的 SKU ∪ 有销售速度登记的 SKU（与 6.2.1.1 同一口径）。
   * 「没登记销售速度」的 SKU <b>仍然进清单</b>（{@code NO_SALES_DATA} 档）—— 把它踢出去等于让
   * 数据缺口隐形，而缺口一旦隐形就永远没人补。
   */
  @Transactional
  public ReplenishmentComputeResult compute(
      ReplenishmentComputeInput input, UUID actorId, String bearer) {
    int defaultCoverageDays = input.defaultCoverageDays();

    // ---- 数据库时钟（不用 Java 时钟；日期口径统一 UTC） ----
    var clock =
        sql.fetchOne(
            "select (current_timestamp at time zone 'UTC')::date::text as d, clock_timestamp() as ts");
    LocalDate asOfDate =
        input.asOfDate() != null ? input.asOfDate() : LocalDate.parse(clock.get("d", String.class));
    OffsetDateTime computedAt = clock.get("ts", OffsetDateTime.class);

    // ---- 配置（覆盖天数 / 采购起订额 / 生产周期） ----
    CoveragePolicies coverage = coveragePolicies(asOfDate);
    OrderPolicies orderPolicies = orderPolicies(asOfDate);

    // ---- 上游读数（warehouse 授权只读接口 + 6.2.1.1 的销售速度登记） ----
    var sellable = inventory.sellable(bearer);
    var transit = inventory.transitLegs(REPLENISH_TRANSIT_KINDS, bearer);
    Map<UUID, VelocityFact> velocities = effectiveVelocities(asOfDate);
    Map<UUID, BigDecimal> previousVelocities = previousVelocities(asOfDate);

    Map<UUID, StockoutInventoryPort.SellableRow> stock = new LinkedHashMap<>();
    for (var row : sellable.rows()) {
      if (row.skuId() != null) {
        stock.put(row.skuId(), row);
      }
    }
    var transitBySku = groupTransit(transit.rows());

    List<UUID> universe = new ArrayList<>(stock.keySet());
    for (UUID skuId : velocities.keySet()) {
      if (!stock.containsKey(skuId)) {
        universe.add(skuId);
      }
    }

    // ---- 逐 SKU 计算 ----
    int lookupBudget = MAX_CATALOG_LOOKUPS;
    boolean lookupTruncated = false;
    List<Draft> drafts = new ArrayList<>();
    for (UUID skuId : universe) {
      Draft draft =
          draft(
              skuId,
              stock.get(skuId),
              velocities.get(skuId),
              previousVelocities.get(skuId),
              transitBySku.get(skuId),
              asOfDate,
              defaultCoverageDays,
              coverage,
              orderPolicies,
              bearer,
              lookupBudget);
      if (draft.catalogLookups > 0) {
        lookupBudget -= draft.catalogLookups;
        if (lookupBudget <= 0) {
          lookupTruncated = true;
        }
      }
      drafts.add(draft);
    }

    // ---- 排序（建议类别 → 净需求量 → SKU 编码） ----
    drafts.sort(
        Comparator.comparingInt((Draft d) -> d.advice.rank())
            .thenComparing(
                d -> d.rawRequired == null ? BigDecimal.valueOf(Long.MAX_VALUE) : d.rawRequired)
            .thenComparing(d -> d.skuCode));

    // ---- 落库 ----
    UUID tenantId = currentTenant();
    UUID batchId = UUID.randomUUID();
    String batchNo =
        "RS-"
            + asOfDate.toString().replace("-", "")
            + "-"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    int replenishCount = 0;
    int holdCount = 0;
    int noSalesDataCount = 0;
    int noQuoteCount = 0;
    for (Draft draft : drafts) {
      switch (draft.advice) {
        case REPLENISH -> replenishCount++;
        case HOLD -> holdCount++;
        case NO_SALES_DATA -> noSalesDataCount++;
        default -> noQuoteCount++;
      }
    }
    boolean truncated = sellable.truncated() || transit.truncated() || lookupTruncated;
    List<String> notes = new ArrayList<>();
    if (sellable.truncated() || transit.truncated()) {
      notes.add("上游分页被页数上限截断，本次清单**不完整**");
    }
    if (lookupTruncated) {
      notes.add(
          "商品主数据查询次数已达上限（"
              + MAX_CATALOG_LOOKUPS
              + " 次），超出部分的 SKU 未取到报价与箱规 ⇒ 本批次**不完整**");
    }
    if (noSalesDataCount > 0) {
      notes.add(
          "有 " + noSalesDataCount + " 个 SKU 未登记销售速度，已列入清单但判不出目标库存与净需求 —— 请去 6.2.1.1 补登记");
    }
    if (noQuoteCount > 0) {
      notes.add("有 " + noQuoteCount + " 个 SKU 缺有效报价或缺箱规，给不出可下单的建议量");
    }
    String note = notes.isEmpty() ? "" : String.join("；", notes);

    sql.execute(
        "insert into replenishment_suggestion_batch(id,tenant_id,batch_no,as_of_date,computed_at,"
            + " default_coverage_days,item_count,replenish_count,hold_count,no_sales_data_count,"
            + " no_quote_count,truncated,created_by,note)"
            + " values (?::uuid,?::uuid,?,?::date,?::timestamptz,?::int,?::int,?::int,?::int,?::int,?::int,?::boolean,?::uuid,?)",
        batchId,
        tenantId,
        batchNo,
        asOfDate,
        computedAt,
        defaultCoverageDays,
        drafts.size(),
        replenishCount,
        holdCount,
        noSalesDataCount,
        noQuoteCount,
        truncated,
        actorId,
        note);

    int rank = 0;
    for (Draft draft : drafts) {
      UUID suggestionId = UUID.randomUUID();
      sql.execute(
          "insert into replenishment_suggestion(id,tenant_id,batch_id,sku_id,sku_code,sku_name,"
              + " daily_sales_quantity,growth_rate,previous_daily_sales_quantity,velocity_window_start,"
              + " velocity_window_end,velocity_source_kind,coverage_days,coverage_source,coverage_category_code,"
              + " target_stock_quantity,available_quantity,in_transit_quantity,in_transit_arriving_quantity,"
              + " raw_required_quantity,moq_quantity,purchase_unit_code,box_spec_quantity,"
              + " suggested_purchase_quantity,suggested_quantity,rounding_adjustment_quantity,rounding_note,"
              + " min_order_amount,currency,unit_price,estimated_cost,supplier_id,supplier_name,supplier_role_id,"
              + " supplier_priority,supplier_selection_basis,lead_time_days,production_lead_time_days,"
              + " expected_arrival_date,arrival_basis,advice,advice_basis,status,sort_rank,created_at)"
              + " values (?::uuid,?::uuid,?::uuid,?::uuid,?,?,?::numeric,?::numeric,?::numeric,?::date,?::date,?::varchar,"
              + " ?::int,?::varchar,?::varchar,?::numeric,?::numeric,?::numeric,?::numeric,?::numeric,?::numeric,?::varchar,"
              + " ?::numeric,?::numeric,?::numeric,?::numeric,?,?::numeric,?::varchar,?::numeric,?::numeric,"
              + " ?::uuid,?,?::uuid,?::int,?,?::int,?::int,?::date,?,?::varchar,?,?::varchar,?::int,?::timestamptz)",
          suggestionId,
          tenantId,
          batchId,
          draft.skuId,
          draft.skuCode,
          draft.skuName,
          draft.dailySales,
          draft.growthRate,
          draft.previousDailySales,
          draft.velocityWindowStart,
          draft.velocityWindowEnd,
          draft.velocitySourceKind,
          draft.coverageDays,
          draft.coverageSource,
          draft.coverageCategoryCode,
          draft.targetStock,
          draft.available,
          draft.inTransitTotal,
          draft.inTransitArriving,
          draft.rawRequired,
          draft.moq,
          draft.purchaseUnitCode,
          draft.boxSpec,
          draft.suggestedPurchaseUnits,
          draft.suggestedQuantity,
          draft.roundingAdjustment,
          draft.roundingNote,
          draft.minOrderAmount,
          draft.currency,
          draft.unitPrice,
          draft.estimatedCost,
          draft.supplierId,
          draft.supplierName,
          draft.supplierRoleId,
          draft.supplierPriority,
          draft.supplierSelectionBasis,
          draft.leadTimeDays,
          draft.productionLeadTimeDays,
          draft.expectedArrivalDate,
          draft.arrivalBasis,
          draft.advice.name(),
          draft.adviceBasis,
          "SUGGESTED",
          rank,
          computedAt);
      int order = 0;
      for (Step step : draft.steps) {
        sql.execute(
            "insert into replenishment_suggestion_step(id,tenant_id,suggestion_id,step_order,step_kind,"
                + " quantity,basis,source_ref)"
                + " values (?::uuid,?::uuid,?::uuid,?::int,?::varchar,?::numeric,?,?)",
            UUID.randomUUID(),
            tenantId,
            suggestionId,
            ++order,
            step.kind().name(),
            step.quantity(),
            step.basis(),
            step.sourceRef());
      }
      rank++;
    }
    return new ReplenishmentComputeResult(
        batchId,
        batchNo,
        asOfDate,
        computedAt,
        defaultCoverageDays,
        drafts.size(),
        replenishCount,
        holdCount,
        noSalesDataCount,
        noQuoteCount,
        truncated,
        truncated ? note : "",
        input.note() == null ? "" : input.note());
  }

  // ================================================================ 逐 SKU 的计算

  /**
   * 逐 SKU 的计算（纯函数式，输入全是已取好的事实）。
   *
   * <p>把「需要调上游」的步骤单独数出来（{@code catalogLookups}），超出上限时由调用方如实报出截断。
   */
  private Draft draft(
      UUID skuId,
      StockoutInventoryPort.SellableRow sellableRow,
      VelocityFact velocity,
      BigDecimal previousDaily,
      List<StockoutInventoryPort.TransitLegRow> legs,
      LocalDate asOfDate,
      int defaultCoverageDays,
      CoveragePolicies coverage,
      OrderPolicies orderPolicies,
      String bearer,
      int lookupBudget) {

    BigDecimal available = sellableRow == null ? ZERO : q(sellableRow.sellable());
    String skuCode =
        sellableRow != null && sellableRow.skuCode() != null
            ? sellableRow.skuCode()
            : (velocity == null ? skuId.toString() : velocity.skuCode());
    String skuName =
        sellableRow != null && sellableRow.skuName() != null
            ? sellableRow.skuName()
            : (velocity == null ? "" : velocity.skuName());

    BigDecimal dailySales = velocity == null ? null : q(velocity.dailySalesQuantity());
    BigDecimal previous = previousDaily == null ? null : q(previousDaily);
    BigDecimal growthRate = growthRate(dailySales, previous);

    // ---- 在途：合计 / 覆盖窗口内可到达 ----
    // 覆盖窗口 = [评估日, 评估日 + 覆盖天数]。窗口长度依赖覆盖天数，所以先定覆盖天数。
    int coverageDays = defaultCoverageDays;
    String coverageSource = "DEFAULT";
    String coverageCategoryCode = null;
    String coverageBasis = "覆盖天数 " + defaultCoverageDays + " 天（来源：计算时传入的兜底值）";

    var skuPolicy = coverage.bySku().get(skuId);
    if (skuPolicy != null) {
      coverageDays = skuPolicy.coverageDays();
      coverageSource = "SKU";
      coverageBasis = "覆盖天数 " + coverageDays + " 天（来源：按 SKU 配置的覆盖天数目标）";
    }

    List<Step> steps = new ArrayList<>();
    steps.add(
        new Step(
            0,
            ReplenishmentStep.DAILY_SALES,
            dailySales,
            velocity == null
                ? "没有销售速度登记 —— 日均销量是「无数据」，不是 0"
                : "日均销量（来源 6.2.1.1 销售速度登记 / "
                    + velocity.sourceKind()
                    + "，统计窗口 "
                    + velocity.windowStart()
                    + " ~ "
                    + velocity.windowEnd()
                    + "）",
            "6.2.1.1 销售速度登记 stockout_sales_velocity"));
    steps.add(
        new Step(
            0,
            ReplenishmentStep.GROWTH_RATE,
            growthRate,
            previous == null
                ? "没有上一个已收口的销售速度版本 —— 近期增长率算不出来（不是 0）"
                : "近期增长率 = (当前日均 "
                    + plain(dailySales)
                    + " − 上一版本日均 "
                    + plain(previous)
                    + ") ÷ "
                    + plain(previous),
            "6.2.1.1 销售速度登记 stockout_sales_velocity"));
    steps.add(
        new Step(0, ReplenishmentStep.COVERAGE_DAYS, BigDecimal.valueOf(coverageDays), coverageBasis,
            "trade 覆盖天数目标配置 replenishment_coverage_policy"));
    steps.add(
        new Step(
            0,
            ReplenishmentStep.AVAILABLE,
            available,
            "有效可用量 = warehouse 7.1.1.3 的可售量（sellable），本模块不重算",
            "warehouse 7.1.1.3 可用量"));

    // ---- 目标库存与净需求 ----
    BigDecimal targetStock = dailySales == null ? null : q(dailySales.multiply(BigDecimal.valueOf(coverageDays)));
    BigDecimal rawRequired = null;
    if (dailySales != null) {
      BigDecimal transitTotal = ZERO;
      BigDecimal transitArriving = ZERO;
      LocalDate horizonEnd = asOfDate.plusDays(coverageDays);
      for (var leg : legs == null ? List.<StockoutInventoryPort.TransitLegRow>of() : legs) {
        BigDecimal quantity = q(leg.outstandingQuantity());
        transitTotal = transitTotal.add(quantity);
        if (leg.expectedArriveAt() == null) {
          continue;
        }
        LocalDate etaDate = leg.expectedArriveAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        if (!etaDate.isBefore(asOfDate) && !etaDate.isAfter(horizonEnd)) {
          transitArriving = transitArriving.add(quantity);
        }
      }
      rawRequired = q(targetStock.subtract(available).subtract(transitArriving).max(BigDecimal.ZERO));

      steps.add(
          new Step(
              0,
              ReplenishmentStep.TARGET_STOCK,
              targetStock,
              "目标库存量 = 日均销量 × 覆盖天数 " + coverageDays + " 天",
              "本模块计算"));
      steps.add(
          new Step(
              0,
              ReplenishmentStep.IN_TRANSIT_TOTAL,
              transitTotal,
              "在途合计（调拨 / 头程 / 采购三类；不含发往平台仓的出库腿）",
              "warehouse 7.5.1.2 在途"));
      steps.add(
          new Step(
              0,
              ReplenishmentStep.IN_TRANSIT_ARRIVING,
              transitArriving,
              "其中预计到达日落在 [" + asOfDate + ", " + horizonEnd + "] 内的在途量（只有这部分计入缺口）",
              "warehouse 7.5.1.2 在途"));
      steps.add(
          new Step(
              0,
              ReplenishmentStep.RAW_REQUIRED,
              rawRequired,
              "净需求量 = max(0, 目标库存量 − 有效可用量 − 覆盖窗口内可到达在途量)",
              "本模块计算"));
      return finishDraft(
          skuId,
          skuCode,
          skuName,
          dailySales,
          previous,
          growthRate,
          velocity,
          coverageDays,
          coverageSource,
          coverageCategoryCode,
          coverageBasis,
          targetStock,
          available,
          transitTotal,
          transitArriving,
          rawRequired,
          steps,
          asOfDate,
          orderPolicies,
          bearer,
          lookupBudget);
    }

    // ---- 没有销售速度登记：答不出来 ----
    steps.add(
        new Step(
            0,
            ReplenishmentStep.TARGET_STOCK,
            null,
            "没有日均销量 ⇒ 目标库存量算不出来（不是 0）",
            "本模块计算"));
    steps.add(
        new Step(
            0,
            ReplenishmentStep.RAW_REQUIRED,
            null,
            "没有日均销量 ⇒ 净需求量算不出来（不是 0）",
            "本模块计算"));
    Draft d = Draft.base(
        skuId,
        skuCode,
        skuName,
        dailySales,
        previous,
        growthRate,
        velocity,
        coverageDays,
        coverageSource,
        coverageCategoryCode);
    d.available = available;
    // 没有日均销量 ⇒ 目标库存量**答不出来**（null），不是 0。
    d.targetStock = null;
    d.steps.addAll(steps);
    d.advice = ReplenishmentAdvice.NO_SALES_DATA;
    d.adviceBasis =
        "未登记销售速度：算不出日均销量、目标库存量与净需求量。要判断这个 SKU 要补多少，先去 6.2.1.1 登记它的销售速度";
    return d;
  }

  /**
   * 净需求 &gt; 0 的那一步：取报价、箱规、做取整、算到货日与费用。
   *
   * <p>这是唯一会去调 catalog 的分支：净需求为 0 或答不出来时**不需要**供应商与箱规，
   * 少一次上游往返，页面也不会显示一堆用不上的供应商信息。
   */
  private Draft finishDraft(
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal dailySales,
      BigDecimal previous,
      BigDecimal growthRate,
      VelocityFact velocity,
      int coverageDays,
      String coverageSource,
      String coverageCategoryCode,
      String coverageBasis,
      BigDecimal targetStock,
      BigDecimal available,
      BigDecimal transitTotal,
      BigDecimal transitArriving,
      BigDecimal rawRequired,
      List<Step> steps,
      LocalDate asOfDate,
      OrderPolicies orderPolicies,
      String bearer,
      int lookupBudget) {

    if (rawRequired != null && rawRequired.signum() == 0) {
      steps.add(
          new Step(
              0,
              ReplenishmentStep.ROUNDED_QUANTITY,
              null,
              "库存（含覆盖窗口内可到达的在途）已覆盖目标库存量，不需要补货",
              "本模块计算"));
      Draft d = Draft.base(
          skuId,
          skuCode,
          skuName,
          dailySales,
          previous,
          growthRate,
          velocity,
          coverageDays,
          coverageSource,
          coverageCategoryCode);
      d.targetStock = targetStock;
      d.available = available;
      d.inTransitTotal = transitTotal;
      d.inTransitArriving = transitArriving;
      d.rawRequired = rawRequired;
      d.steps.addAll(steps);
      d.advice = ReplenishmentAdvice.HOLD;
      d.adviceBasis =
          "净需求量为 0：目标库存 "
              + plain(targetStock)
              + " 已被有效可用量 "
              + plain(available)
              + " 与覆盖窗口内可到达在途 "
              + plain(transitArriving)
              + " 覆盖，本次不需要补货";
      return d;
    }

    if (lookupBudget <= 0) {
      steps.add(
          new Step(
              0,
              ReplenishmentStep.ROUNDED_QUANTITY,
              null,
              "本次计算的商品主数据查询次数已达上限，未取到报价与箱规 ⇒ 给不出可下单的建议量",
              "本模块计算"));
      Draft d = Draft.base(
          skuId,
          skuCode,
          skuName,
          dailySales,
          previous,
          growthRate,
          velocity,
          coverageDays,
          coverageSource,
          coverageCategoryCode);
      d.targetStock = targetStock;
      d.available = available;
      d.inTransitTotal = transitTotal;
      d.inTransitArriving = transitArriving;
      d.rawRequired = rawRequired;
      d.steps.addAll(steps);
      d.advice = ReplenishmentAdvice.NO_QUOTE;
      d.adviceBasis =
          "净需求量为 "
              + plain(rawRequired)
              + "，但本次计算的商品主数据查询次数已达上限（"
              + MAX_CATALOG_LOOKUPS
              + " 次），未取到报价与箱规 ⇒ 本批次不完整，请分批计算或调高上限";
      return d;
    }

    // ---- 取报价（3.6.2.1 有效报价列表） ----
    var quoteResult = catalog.quotes(skuId, asOfDate, bearer);
    int lookups = 1;
    if (quoteResult.candidates().isEmpty()) {
      steps.add(
          new Step(
              0,
              ReplenishmentStep.ROUNDED_QUANTITY,
              null,
              "该 SKU 没有有效报价 ⇒ 取不到 MOQ / 采购单位 / 交期 / 价格，给不出可下单的建议量",
              "catalog 3.6.2.1 有效报价列表"));
      Draft d = Draft.base(
          skuId,
          skuCode,
          skuName,
          dailySales,
          previous,
          growthRate,
          velocity,
          coverageDays,
          coverageSource,
          coverageCategoryCode);
      d.targetStock = targetStock;
      d.available = available;
      d.inTransitTotal = transitTotal;
      d.inTransitArriving = transitArriving;
      d.rawRequired = rawRequired;
      d.steps.addAll(steps);
      d.advice = ReplenishmentAdvice.NO_QUOTE;
      d.adviceBasis =
          "净需求量为 "
              + plain(rawRequired)
              + "，但该 SKU 没有有效报价"
              + (quoteResult.ambiguityProblem() == null ? "" : "（" + quoteResult.ambiguityProblem() + "）")
              + " ⇒ 取不到 MOQ / 采购单位 / 交期 / 价格，给不出可下单的建议量";
      d.catalogLookups = lookups;
      return d;
    }

    // ---- 取 SKU 详情（分类 + 单位换算 = 箱规） ----
    var skuView = catalog.sku(skuId, bearer);
    lookups++;

    // ---- 择优：交期短 → 起订门槛低 → 同币种内单价低 → 供应商名 ----
    var chosen = selectSupplier(quoteResult.candidates(), skuView);
    String supplierSelectionBasis = supplierSelectionBasis(chosen, quoteResult);

    // ---- 箱规 ----
    BigDecimal boxSpec = boxSpecOf(skuView, chosen.purchaseUnitCode());
    steps.add(
        new Step(
            0,
            ReplenishmentStep.BOX_SPEC,
            boxSpec,
            boxSpec == null
                ? "箱规量不到：catalog 3.1.2.4 没登记采购单位「"
                    + chosen.purchaseUnitCode()
                    + "」相对基础单位的换算比例 ⇒ 需求量换算不成可下单的件数（不猜成 1）"
                : "箱规 = 1 个采购单位「"
                    + chosen.purchaseUnitCode()
                    + "」含 "
                    + plain(boxSpec)
                    + " 个基础单位（catalog 3.1.2.4 单位换算）",
            "catalog 3.1.2.4 单位换算 sku_unit_conversion"));

    if (boxSpec == null) {
      steps.add(
          new Step(
              0,
              ReplenishmentStep.ROUNDED_QUANTITY,
              null,
              "箱规量不到 ⇒ 无法把净需求量换算成可下单的件数，不做取整",
              "本模块计算"));
      Draft d = Draft.base(
          skuId,
          skuCode,
          skuName,
          dailySales,
          previous,
          growthRate,
          velocity,
          coverageDays,
          coverageSource,
          coverageCategoryCode);
      d.targetStock = targetStock;
      d.available = available;
      d.inTransitTotal = transitTotal;
      d.inTransitArriving = transitArriving;
      d.rawRequired = rawRequired;
      d.moq = chosen.moqQuantity();
      d.purchaseUnitCode = chosen.purchaseUnitCode();
      d.currency = chosen.currency();
      d.supplierId = chosen.supplierId();
      d.supplierName = chosen.supplierName();
      d.supplierRoleId = chosen.supplierRoleId();
      d.supplierPriority = chosen.priority();
      d.supplierSelectionBasis = supplierSelectionBasis;
      d.leadTimeDays = chosen.leadTimeDays();
      d.steps.addAll(steps);
      d.advice = ReplenishmentAdvice.NO_BOX_SPEC;
      d.adviceBasis =
          "净需求量为 "
              + plain(rawRequired)
              + "，但箱规量不到（catalog 没登记采购单位「"
              + chosen.purchaseUnitCode()
              + "」相对基础单位的换算比例）⇒ 给不出可下单的建议量";
      d.catalogLookups = lookups;
      return d;
    }

    // ---- 采购起订额 / 生产周期（按供应商取；没有供应商配置就用租户默认） ----
    var orderPolicy = orderPolicies.forSupplier(chosen.supplierId());
    BigDecimal minOrderAmount = orderPolicy == null ? null : orderPolicy.minOrderAmount();
    String minOrderCurrency = orderPolicy == null ? null : orderPolicy.currency();
    Integer productionDays = orderPolicy == null ? null : orderPolicy.productionLeadTimeDays();

    // ---- 取整三步 ----
    var rounding =
        round(
            rawRequired,
            boxSpec,
            chosen.moqQuantity(),
            minOrderAmount,
            chosen.currency(),
            minOrderCurrency,
            chosen.tiers());

    steps.add(
        new Step(
            0,
            ReplenishmentStep.MOQ,
            chosen.moqQuantity(),
            "起订量 MOQ = " + plain(chosen.moqQuantity()) + "（采购单位，catalog 3.6.2.1 报价）",
            "catalog 3.6.2.1 供应商报价"));
    steps.add(
        new Step(
            0,
            ReplenishmentStep.MIN_ORDER_AMOUNT,
            minOrderAmount,
            minOrderAmount == null
                ? "没有配置采购起订额 ⇒ 本步不参与取整"
                : "采购起订额 = "
                    + plain(minOrderAmount)
                    + " "
                    + (minOrderCurrency == null ? "" : minOrderCurrency)
                    + (rounding.minOrderSkipReason() == null ? "" : "（" + rounding.minOrderSkipReason() + "）"),
            "trade 采购起订额配置 replenishment_order_policy"));
    steps.add(
        new Step(
            0,
            ReplenishmentStep.ROUNDED_QUANTITY,
            rounding.suggestedQuantity(),
            roundingNote(rawRequired, rounding, chosen, boxSpec),
            "本模块计算"));
    steps.add(
        new Step(
            0,
            ReplenishmentStep.UNIT_PRICE,
            rounding.unitPrice(),
            rounding.unitPrice() == null
                ? "该采购单位数在报价的阶梯里没有适用档（低于最低档起订量）⇒ 取不到单价"
                : "适用阶梯单价 = " + plain(rounding.unitPrice()) + " " + chosen.currency() + " / 采购单位",
            "catalog 3.6.2.1 供应商报价阶梯价"));
    steps.add(
        new Step(
            0,
            ReplenishmentStep.ESTIMATED_COST,
            rounding.estimatedCost(),
            rounding.estimatedCost() == null
                ? "取不到适用阶梯价 ⇒ 算不出预计费用（不是 0）"
                : "预计费用 = " + plain(rounding.purchaseUnits()) + " 采购单位 × "
                    + plain(rounding.unitPrice()) + " " + chosen.currency() + " = "
                    + plain(rounding.estimatedCost()) + " " + chosen.currency(),
            "本模块计算"));

    // ---- 预计到货日 ----
    int leadTime = chosen.leadTimeDays();
    int production = productionDays == null ? 0 : productionDays;
    LocalDate arrivalDate = asOfDate.plusDays((long) leadTime + production);
    String arrivalBasis =
        "预计到货日 = 评估日 "
            + asOfDate
            + " + 供应商交期 "
            + leadTime
            + " 天（catalog 3.6.2.1 报价）"
            + " + 生产周期 "
            + production
            + " 天（"
            + (productionDays == null ? "采购侧未维护，按 0 天计" : "采购侧配置 replenishment_order_policy")
            + "）"
            + " = "
            + arrivalDate
            + "。**10 头程时效未交付**（warehouse 只有逐票 ETA，不是可用时效基准），不参与推导";
    steps.add(
        new Step(0, ReplenishmentStep.SUPPLIER_LEAD_TIME, BigDecimal.valueOf(leadTime),
            "供应商交期 = " + leadTime + " 天（catalog 3.6.2.1 报价 lead_time_days）",
            "catalog 3.6.2.1 供应商报价"));
    steps.add(
        new Step(0, ReplenishmentStep.PRODUCTION_LEAD_TIME,
            productionDays == null ? null : BigDecimal.valueOf(productionDays),
            productionDays == null
                ? "生产周期未维护 ⇒ 按 0 天计（不是「不需要生产」，是「没维护」）"
                : "生产周期 = " + productionDays + " 天（采购侧配置 replenishment_order_policy）",
            "trade 采购起订额配置 replenishment_order_policy"));
    steps.add(
        new Step(0, ReplenishmentStep.ARRIVAL_DATE, null, arrivalBasis, "本模块计算"));

    String adviceBasis =
        "净需求量为 "
            + plain(rawRequired)
            + "（目标库存 "
            + plain(targetStock)
            + " − 可用 "
            + plain(available)
            + " − 覆盖窗口内可到达在途 "
            + plain(transitArriving)
            + "），取整后建议 "
            + plain(rounding.suggestedQuantity())
            + "（"
            + plain(rounding.purchaseUnits())
            + " 采购单位），供应商 "
            + chosen.supplierName()
            + "，预计到货 "
            + arrivalDate;

    Draft d =
        Draft.base(
            skuId,
            skuCode,
            skuName,
            dailySales,
            previous,
            growthRate,
            velocity,
            coverageDays,
            coverageSource,
            coverageCategoryCode);
    d.targetStock = targetStock;
    d.available = available;
    d.inTransitTotal = transitTotal;
    d.inTransitArriving = transitArriving;
    d.rawRequired = rawRequired;
    d.moq = chosen.moqQuantity();
    d.purchaseUnitCode = chosen.purchaseUnitCode();
    d.boxSpec = boxSpec;
    d.suggestedPurchaseUnits = rounding.purchaseUnits();
    d.suggestedQuantity = rounding.suggestedQuantity();
    d.roundingAdjustment = rounding.adjustment();
    d.roundingNote = roundingNote(rawRequired, rounding, chosen, boxSpec);
    d.minOrderAmount = minOrderAmount;
    d.currency = chosen.currency();
    d.unitPrice = rounding.unitPrice();
    d.estimatedCost = rounding.estimatedCost();
    d.supplierId = chosen.supplierId();
    d.supplierName = chosen.supplierName();
    d.supplierRoleId = chosen.supplierRoleId();
    d.supplierPriority = chosen.priority();
    d.supplierSelectionBasis = supplierSelectionBasis;
    d.leadTimeDays = leadTime;
    d.productionLeadTimeDays = productionDays;
    d.expectedArrivalDate = arrivalDate;
    d.arrivalBasis = arrivalBasis;
    d.steps.addAll(steps);
    d.advice = ReplenishmentAdvice.REPLENISH;
    d.adviceBasis = adviceBasis;
    d.catalogLookups = lookups;
    return d;
  }

  // ================================================================ 供应商择优

  /**
   * 择优：① 交期短优先 ② 起订门槛低优先（折算到基础单位后比较）③ 同币种内单价低优先 ④ 供应商名升序。
   *
   * <p>③ 之所以要「同币种内」：12 财务才有汇率表，跨币种比价会算出一个**看起来对、实际错**的数。
   */
  private ReplenishmentCatalogPort.QuoteView selectSupplier(
      List<ReplenishmentCatalogPort.QuoteView> candidates,
      ReplenishmentCatalogPort.SkuView skuView) {
    List<ReplenishmentCatalogPort.QuoteView> sorted = new ArrayList<>(candidates);
    Comparator<ReplenishmentCatalogPort.QuoteView> byLeadThenMoqThenName =
        Comparator.comparingInt((ReplenishmentCatalogPort.QuoteView q) -> q.leadTimeDays())
            .thenComparing(
                q -> moqInBaseUnits(q, skuView),
                Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(q -> q.supplierName() == null ? "" : q.supplierName())
            .thenComparing(q -> q.supplierRoleId().toString());
    sorted.sort(byLeadThenMoqThenName);
    // 交期与起订门槛都相同的候选之间，若币种一致则比单价（跨币种不比）。
    for (int i = 0; i < sorted.size(); i++) {
      int j = i + 1;
      while (j < sorted.size()
          && sorted.get(j).leadTimeDays() == sorted.get(i).leadTimeDays()
          && java.util.Objects.equals(
              moqInBaseUnits(sorted.get(j), skuView), moqInBaseUnits(sorted.get(i), skuView))) {
        j++;
      }
      if (j - i > 1) {
        List<ReplenishmentCatalogPort.QuoteView> group = new ArrayList<>(sorted.subList(i, j));
        String currency = group.get(0).currency();
        boolean sameCurrency = group.stream().allMatch(q -> java.util.Objects.equals(q.currency(), currency));
        if (sameCurrency && currency != null) {
          group.sort(
              Comparator.comparing(
                      (ReplenishmentCatalogPort.QuoteView q) ->
                          applicableTierPrice(q.tiers(), q.moqQuantity()),
                      Comparator.nullsLast(Comparator.naturalOrder()))
                  .thenComparing(q -> q.supplierName() == null ? "" : q.supplierName()));
          for (int k = 0; k < group.size(); k++) {
            sorted.set(i + k, group.get(k));
          }
        }
      }
      i = j - 1;
    }
    return sorted.get(0);
  }

  /** MOQ 折算到基础单位（不同供应商的采购单位可能不同，直接比数字没有意义）。 */
  private BigDecimal moqInBaseUnits(
      ReplenishmentCatalogPort.QuoteView quote, ReplenishmentCatalogPort.SkuView skuView) {
    if (quote.moqQuantity() == null) {
      return null;
    }
    BigDecimal boxSpec = boxSpecOf(skuView, quote.purchaseUnitCode());
    if (boxSpec == null) {
      return null;
    }
    return q(quote.moqQuantity().multiply(boxSpec));
  }

  /** 择优依据的原文（写进建议，页面上直接显示）。 */
  private static String supplierSelectionBasis(
      ReplenishmentCatalogPort.QuoteView chosen, ReplenishmentCatalogPort.QuoteResult result) {
    StringBuilder sb = new StringBuilder();
    sb.append("在 ")
        .append(result.candidates().size())
        .append(" 条有效报价里择优：交期短优先 → 起订门槛低优先 → 同币种内单价低优先 → 供应商名升序。");
    sb.append("选中「").append(chosen.supplierName()).append("」");
    sb.append("（交期 ").append(chosen.leadTimeDays()).append(" 天");
    sb.append("，起订量 ").append(plain(chosen.moqQuantity())).append(" ");
    sb.append(chosen.purchaseUnitCode() == null ? "" : chosen.purchaseUnitCode());
    sb.append("，币种 ").append(chosen.currency() == null ? "未填" : chosen.currency()).append("）。");
    sb.append("6.1.1.3 供应商评级未交付 ⇒ 不按评级择优；跨币种不比价（12 财务才有汇率表）。");
    if (result.ambiguous()) {
      sb.append("⚠ 上游存在同一优先级并列的有效报价（")
          .append(result.ambiguityProblem() == null ? "" : result.ambiguityProblem())
          .append("），本次按上面的顺序择优，请人工复核。");
    }
    return sb.toString();
  }

  // ================================================================ 批次与清单（读）

  /** 批次列表（真分页）。 */
  public ReplenishmentBatchPage pageBatches(int page, int size) {
    int pageNo = Math.max(0, page);
    int pageSize = pageSize(size);
    long total =
        sql.fetchOne("select count(*) from replenishment_suggestion_batch where tenant_id = ?::uuid", currentTenant())
            .get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            "select b.id,b.batch_no,b.as_of_date,b.computed_at,b.default_coverage_days,b.item_count,"
                + " b.replenish_count,b.hold_count,b.no_sales_data_count,b.no_quote_count,"
                + " (select count(*) from replenishment_suggestion s"
                + "   where s.tenant_id = b.tenant_id and s.batch_id = b.id"
                + "     and s.status <> 'SUGGESTED') as decided_count,"
                + " b.truncated,b.note,b.created_at"
                + " from replenishment_suggestion_batch b where b.tenant_id = ?::uuid"
                + " order by b.computed_at desc, b.id desc limit ? offset ?",
            currentTenant(),
            pageSize,
            (long) pageNo * pageSize);
    List<ReplenishmentBatchRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(batchRow(row));
    }
    return new ReplenishmentBatchPage(items, total, pageNo, pageSize);
  }

  /** 建议清单（真分页 + 真筛选 + 按建议类别排序）。 */
  public ReplenishmentSuggestionPage pageSuggestions(ReplenishmentSuggestionQuery query) {
    int pageNo = Math.max(0, query.page());
    int pageSize = pageSize(query.size());
    UUID batchId = query.batchId() == null ? latestBatchId() : query.batchId();
    if (batchId == null) {
      return new ReplenishmentSuggestionPage(
          List.of(), 0, pageNo, pageSize, null, null, null, 0, "");
    }
    var batch =
        sql.fetchOne(
            "select batch_no,as_of_date,default_coverage_days from replenishment_suggestion_batch"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            batchId);
    if (batch == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "补货建议批次不存在");
    }
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("s.tenant_id = ?::uuid");
    binds.add(currentTenant());
    where.add("s.batch_id = ?::uuid");
    binds.add(batchId);
    if (query.advice() != null && !query.advice().isBlank()) {
      where.add("s.advice = ?::varchar");
      binds.add(query.advice().trim());
    }
    if (query.status() != null && !query.status().isBlank()) {
      where.add("s.status = ?::varchar");
      binds.add(query.status().trim());
    }
    if (query.skuCode() != null && !query.skuCode().isBlank()) {
      where.add("s.sku_code ilike ?");
      binds.add("%" + query.skuCode().trim() + "%");
    }
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.add("(s.sku_code ilike ? or s.sku_name ilike ? or s.supplier_name ilike ?)");
      binds.add("%" + query.keyword().trim() + "%");
      binds.add("%" + query.keyword().trim() + "%");
      binds.add("%" + query.keyword().trim() + "%");
    }
    if (Boolean.TRUE.equals(query.onlyActionable())) {
      where.add("s.advice = 'REPLENISH'");
    }
    String clause = String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from replenishment_suggestion s where " + clause, binds.toArray())
            .get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            "select s.id,s.batch_id,s.sku_id,s.sku_code,s.sku_name,s.daily_sales_quantity,"
                + " s.growth_rate,s.previous_daily_sales_quantity,s.velocity_window_start,"
                + " s.velocity_window_end,s.velocity_source_kind,s.coverage_days,s.coverage_source,"
                + " s.coverage_category_code,s.target_stock_quantity,s.available_quantity,"
                + " s.in_transit_quantity,s.in_transit_arriving_quantity,s.raw_required_quantity,"
                + " s.moq_quantity,s.purchase_unit_code,s.box_spec_quantity,"
                + " s.suggested_purchase_quantity,s.suggested_quantity,s.rounding_adjustment_quantity,"
                + " s.rounding_note,s.min_order_amount,s.currency,s.unit_price,s.estimated_cost,"
                + " s.supplier_id,s.supplier_name,s.supplier_role_id,s.supplier_priority,"
                + " s.supplier_selection_basis,s.lead_time_days,s.production_lead_time_days,"
                + " s.expected_arrival_date,s.arrival_basis,s.advice,s.advice_basis,s.status,"
                + " s.decided_quantity,s.decision_by,s.decision_at,s.decision_note,s.sort_rank"
                + " from replenishment_suggestion s where "
                + clause
                + " order by s.sort_rank, s.sku_code limit ? offset ?",
            append(binds, pageSize, (long) pageNo * pageSize).toArray());
    List<ReplenishmentSuggestionRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(suggestionRow(row, batch.get("batch_no", String.class)));
    }
    String note =
        items.isEmpty()
            ? "这一页没有符合条件的建议 —— 可能是筛选条件太窄，也可能是真的不需要补货"
            : "";
    return new ReplenishmentSuggestionPage(
        items,
        total,
        pageNo,
        pageSize,
        batchId,
        batch.get("batch_no", String.class),
        batch.get("as_of_date", LocalDate.class),
        batch.get("default_coverage_days", Integer.class),
        note);
  }

  /** 建议详情：结论 + 计算过程 + 处置留痕。 */
  public ReplenishmentSuggestionDetail suggestionDetail(UUID suggestionId) {
    var row =
        sql.fetchOne(
            "select id,batch_id,sku_id,sku_code,sku_name,daily_sales_quantity,growth_rate,"
                + " previous_daily_sales_quantity,velocity_window_start,velocity_window_end,"
                + " velocity_source_kind,coverage_days,coverage_source,coverage_category_code,"
                + " target_stock_quantity,available_quantity,in_transit_quantity,"
                + " in_transit_arriving_quantity,raw_required_quantity,moq_quantity,purchase_unit_code,"
                + " box_spec_quantity,suggested_purchase_quantity,suggested_quantity,"
                + " rounding_adjustment_quantity,rounding_note,min_order_amount,currency,unit_price,"
                + " estimated_cost,supplier_id,supplier_name,supplier_role_id,supplier_priority,"
                + " supplier_selection_basis,lead_time_days,production_lead_time_days,"
                + " expected_arrival_date,arrival_basis,advice,advice_basis,status,decided_quantity,"
                + " decision_by,decision_at,decision_note,sort_rank"
                + " from replenishment_suggestion where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            suggestionId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "补货建议不存在");
    }
    var batch =
        sql.fetchOne(
            "select batch_no from replenishment_suggestion_batch where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            row.get("batch_id", UUID.class));
    List<ReplenishmentStepRow> steps = new ArrayList<>();
    for (Record step :
        sql.fetch(
            "select step_order,step_kind,quantity,basis,source_ref from replenishment_suggestion_step"
                + " where tenant_id = ?::uuid and suggestion_id = ?::uuid order by step_order",
            currentTenant(),
            suggestionId)) {
      steps.add(
          new ReplenishmentStepRow(
              step.get("step_order", Integer.class),
              step.get("step_kind", String.class),
              stepLabel(step.get("step_kind", String.class)),
              step.get("quantity", BigDecimal.class),
              step.get("basis", String.class),
              step.get("source_ref", String.class)));
    }
    List<ReplenishmentDecisionRow> decisions = new ArrayList<>();
    for (Record decision :
        sql.fetch(
            "select id,action,quantity,note,decided_by,decided_at from replenishment_suggestion_decision"
                + " where tenant_id = ?::uuid and suggestion_id = ?::uuid order by decided_at, id",
            currentTenant(),
            suggestionId)) {
      decisions.add(
          new ReplenishmentDecisionRow(
              decision.get("id", UUID.class),
              decision.get("action", String.class),
              decisionLabel(decision.get("action", String.class)),
              decision.get("quantity", BigDecimal.class),
              decision.get("note", String.class),
              decision.get("decided_by", UUID.class),
              decision.get("decided_at", OffsetDateTime.class)));
    }
    return new ReplenishmentSuggestionDetail(
        suggestionRow(row, batch == null ? null : batch.get("batch_no", String.class)), steps, decisions);
  }

  // ================================================================ 处置（接受 / 调整 / 忽略）

  /**
   * 处置一条建议。
   *
   * <p>三种处置真的落库：建议行更新处置状态与处置人 / 处置时间 / 处置说明，
   * 同时追加一条<b>不可改写的处置留痕</b>。
   *
   * <p>只有 {@code REPLENISH} 能处置：{@code HOLD} 不需要补，{@code NO_SALES_DATA} /
   * {@code NO_QUOTE} / {@code NO_BOX_SPEC} 答不出来 —— 给「答不出来」的行点「忽略」
   * 会把一个数据缺口伪装成一次业务决定。
   */
  @Transactional
  public ReplenishmentSuggestionRow decide(
      UUID suggestionId, ReplenishmentDecisionInput input, UUID actorId) {
    var row =
        sql.fetchOne(
            "select id,sku_code,advice,status,suggested_quantity,decided_quantity from replenishment_suggestion"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            suggestionId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "补货建议不存在");
    }
    String advice = row.get("advice", String.class);
    if (!ReplenishmentAdvice.REPLENISH.name().equals(advice)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "这条建议是「"
              + adviceLabel(advice)
              + "」，不是可处置的建议 —— 只有「建议补货」才能接受 / 调整 / 忽略");
    }
    BigDecimal suggested = row.get("suggested_quantity", BigDecimal.class);
    BigDecimal decided;
    String status;
    switch (input.action()) {
      case ACCEPT -> {
        decided = input.quantity() == null ? suggested : input.quantity();
        if (decided == null || decided.signum() <= 0) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "接受的数量必须大于 0");
        }
        status = "ACCEPTED";
      }
      case ADJUST -> {
        decided = input.quantity();
        if (decided == null || decided.signum() <= 0) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "调整时必须给出调整后的数量（基础单位）—— 没有数量的「调整」是没法下单的");
        }
        status = "ADJUSTED";
      }
      case IGNORE -> {
        decided = null;
        status = "IGNORED";
      }
      default ->
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "未知的处置动作：" + input.action());
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update replenishment_suggestion set status = ?::varchar, decided_quantity = ?::numeric,"
            + " decision_by = ?::uuid, decision_at = ?::timestamptz, decision_note = ?::varchar"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        status,
        decided,
        actorId,
        now,
        input.note().trim(),
        currentTenant(),
        suggestionId);
    sql.execute(
        "insert into replenishment_suggestion_decision(id,tenant_id,suggestion_id,action,quantity,note,"
            + " decided_by,decided_at,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::numeric,?,?::uuid,?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        suggestionId,
        input.action().name(),
        decided,
        input.note().trim(),
        actorId,
        now,
        now);
    // ⚠️ 刻意**不**去 UPDATE 批次的「已处置条数」：批次是 append-only 的留痕表，
    // 任何 UPDATE 都会被触发器挡下（23514）。处置计数改为**读时派生**。
    return suggestionDetail(suggestionId).suggestion();
  }

  // ================================================================ 内部工具

  /** 最近一次批次。 */
  private UUID latestBatchId() {
    var row =
        sql.fetchOne(
            "select id from replenishment_suggestion_batch where tenant_id = ?::uuid"
                + " order by computed_at desc, id desc limit 1",
            currentTenant());
    return row == null ? null : row.get(0, UUID.class);
  }

  /** 生效的覆盖天数配置（三种作用域）。 */
  private CoveragePolicies coveragePolicies(LocalDate asOfDate) {
    Map<UUID, CoveragePolicy> bySku = new LinkedHashMap<>();
    Map<String, CoveragePolicy> byCategory = new LinkedHashMap<>();
    CoveragePolicy byDefault = null;
    for (Record row :
        sql.fetch(
            "select scope,sku_id,category_code,coverage_days from replenishment_coverage_policy"
                + " where tenant_id = ?::uuid and effective_from <= ?::date"
                + "   and (effective_to is null or effective_to > ?::date)",
            currentTenant(),
            asOfDate,
            asOfDate)) {
      var policy =
          new CoveragePolicy(
              row.get("scope", String.class),
              row.get("sku_id", UUID.class),
              row.get("category_code", String.class),
              row.get("coverage_days", Integer.class));
      switch (policy.scope()) {
        case "SKU" -> bySku.put(policy.skuId(), policy);
        case "CATEGORY" -> byCategory.put(policy.categoryCode(), policy);
        default -> byDefault = policy;
      }
    }
    return new CoveragePolicies(bySku, byCategory, byDefault);
  }

  /** 生效的采购起订额 / 生产周期配置。 */
  private OrderPolicies orderPolicies(LocalDate asOfDate) {
    Map<UUID, OrderPolicy> bySupplier = new LinkedHashMap<>();
    OrderPolicy byDefault = null;
    for (Record row :
        sql.fetch(
            "select supplier_id,supplier_role_id,min_order_amount,currency,production_lead_time_days"
                + " from replenishment_order_policy where tenant_id = ?::uuid"
                + "   and effective_from <= ?::date and (effective_to is null or effective_to > ?::date)",
            currentTenant(),
            asOfDate,
            asOfDate)) {
      var policy =
          new OrderPolicy(
              row.get("supplier_id", UUID.class),
              row.get("supplier_role_id", UUID.class),
              row.get("min_order_amount", BigDecimal.class),
              row.get("currency", String.class),
              row.get("production_lead_time_days", Integer.class));
      if (policy.supplierId() == null) {
        byDefault = policy;
      } else {
        bySupplier.put(policy.supplierId(), policy);
      }
    }
    return new OrderPolicies(bySupplier, byDefault);
  }

  /** 评估日生效的销售速度登记（6.2.1.1 的表，只读）。 */
  private Map<UUID, VelocityFact> effectiveVelocities(LocalDate asOfDate) {
    List<Record> rows =
        sql.fetch(
            "select distinct on (sku_id) sku_id,sku_code,sku_name,daily_sales_quantity,"
                + " window_start,window_end,effective_from,source_kind,source_ref"
                + " from stockout_sales_velocity"
                + " where tenant_id = ?::uuid and effective_from <= ?::date"
                + "   and (effective_to is null or effective_to > ?::date)"
                + " order by sku_id, effective_from desc, created_at desc",
            currentTenant(),
            asOfDate,
            asOfDate);
    Map<UUID, VelocityFact> result = new LinkedHashMap<>();
    for (Record row : rows) {
      result.put(
          row.get("sku_id", UUID.class),
          new VelocityFact(
              row.get("sku_code", String.class),
              row.get("sku_name", String.class),
              row.get("daily_sales_quantity", BigDecimal.class),
              row.get("window_start", LocalDate.class),
              row.get("window_end", LocalDate.class),
              row.get("source_kind", String.class),
              row.get("source_ref", String.class)));
    }
    return result;
  }

  /** 评估日之前**上一个已收口**的销售速度版本（用于算近期增长率）。 */
  private Map<UUID, BigDecimal> previousVelocities(LocalDate asOfDate) {
    List<Record> rows =
        sql.fetch(
            "select distinct on (sku_id) sku_id,daily_sales_quantity from stockout_sales_velocity"
                + " where tenant_id = ?::uuid and effective_from <= ?::date"
                + "   and effective_to is not null and effective_to <= ?::date"
                + " order by sku_id, effective_to desc, created_at desc",
            currentTenant(),
            asOfDate,
            asOfDate);
    Map<UUID, BigDecimal> result = new LinkedHashMap<>();
    for (Record row : rows) {
      result.put(row.get("sku_id", UUID.class), row.get("daily_sales_quantity", BigDecimal.class));
    }
    return result;
  }

  /** 在途腿按 SKU 分组（只留下仍在途、量大于 0 的腿）。 */
  private Map<UUID, List<StockoutInventoryPort.TransitLegRow>> groupTransit(
      List<StockoutInventoryPort.TransitLegRow> legs) {
    Map<UUID, List<StockoutInventoryPort.TransitLegRow>> result = new LinkedHashMap<>();
    for (var leg : legs) {
      if (leg.skuId() == null || leg.outstandingQuantity() == null) {
        continue;
      }
      if (leg.outstandingQuantity().compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      result.computeIfAbsent(leg.skuId(), k -> new ArrayList<>()).add(leg);
    }
    return result;
  }

  /** 箱规：1 个采购单位含多少基础单位。量不到就是 null（绝不猜成 1）。 */
  static BigDecimal boxSpecOf(
      ReplenishmentCatalogPort.SkuView skuView, String purchaseUnitCode) {
    if (purchaseUnitCode == null || purchaseUnitCode.isBlank()) {
      return null;
    }
    if (skuView.baseUnit() != null && skuView.baseUnit().equals(purchaseUnitCode)) {
      // 采购单位就是基础单位 ⇒ 1 个采购单位 = 1 个基础单位（这不是「猜」，是定义）。
      return BigDecimal.ONE;
    }
    BigDecimal purchaseRole = null;
    BigDecimal anyRole = null;
    if (skuView.unitConversions() != null) {
      for (var conversion : skuView.unitConversions()) {
        if (conversion == null || conversion.unitCode() == null) {
          continue;
        }
        if (!conversion.unitCode().equals(purchaseUnitCode)) {
          continue;
        }
        if (!conversion.current()) {
          continue;
        }
        if (anyRole == null) {
          anyRole = conversion.quantityPerBase();
        }
        if ("PURCHASE".equals(conversion.role()) && purchaseRole == null) {
          purchaseRole = conversion.quantityPerBase();
        }
      }
    }
    BigDecimal spec = purchaseRole != null ? purchaseRole : anyRole;
    return spec == null ? null : q(spec);
  }

  /** 增长率：上一版本为 0 时算不出来（不能除以 0）。 */
  static BigDecimal growthRate(BigDecimal current, BigDecimal previous) {
    if (current == null || previous == null) {
      return null;
    }
    if (previous.signum() == 0) {
      return null;
    }
    return q(current.subtract(previous).divide(previous, QUANTITY_SCALE, RoundingMode.HALF_UP));
  }

  /** 取整说明（人读的一句话，三步各说清做了什么）。 */
  private static String roundingNote(
      BigDecimal rawRequired,
      Rounding rounding,
      ReplenishmentCatalogPort.QuoteView chosen,
      BigDecimal boxSpec) {
    StringBuilder sb = new StringBuilder();
    sb.append("净需求 ").append(plain(rawRequired)).append(" ");
    sb.append("箱规 ").append(plain(boxSpec)).append(" 件/箱 ⇒ 需 ")
        .append(plain(rawRequired.divide(boxSpec, 6, RoundingMode.HALF_UP)))
        .append(" 箱，向上取整为 ")
        .append(plain(rounding.purchaseUnits()))
        .append(" 箱");
    if (rounding.boxRaised() != null && rounding.boxRaised().signum() > 0) {
      sb.append("（整箱多出 ").append(plain(rounding.boxRaised())).append(" 箱）");
    }
    if (chosen.moqQuantity() != null) {
      if (rounding.moqRaised() != null && rounding.moqRaised().signum() > 0) {
        sb.append("；低于起订量 MOQ ").append(plain(chosen.moqQuantity())).append(" 箱，抬到 MOQ");
      } else {
        sb.append("；已满足起订量 MOQ ").append(plain(chosen.moqQuantity())).append(" 箱");
      }
    }
    if (rounding.minOrderAmount() != null) {
      if (rounding.minOrderApplied()) {
        sb.append("；预计费用低于采购起订额 ").append(plain(rounding.minOrderAmount()))
            .append("，按单价抬量后达标");
      } else if (rounding.minOrderSkipReason() != null) {
        sb.append("；").append(rounding.minOrderSkipReason());
      } else {
        sb.append("；预计费用已满足采购起订额 ").append(plain(rounding.minOrderAmount()));
      }
    }
    sb.append("；最终建议 ").append(plain(rounding.suggestedQuantity())).append(" 件（")
        .append(plain(rounding.purchaseUnits())).append(" 箱）");
    return sb.toString();
  }

  private ReplenishmentBatchRow batchRow(Record row) {
    return new ReplenishmentBatchRow(
        row.get("id", UUID.class),
        row.get("batch_no", String.class),
        row.get("as_of_date", LocalDate.class),
        row.get("computed_at", OffsetDateTime.class),
        row.get("default_coverage_days", Integer.class),
        row.get("item_count", Integer.class),
        row.get("replenish_count", Integer.class),
        row.get("hold_count", Integer.class),
        row.get("no_sales_data_count", Integer.class),
        row.get("no_quote_count", Integer.class),
        row.get("decided_count", Integer.class),
        row.get("truncated", Boolean.class),
        row.get("note", String.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private ReplenishmentSuggestionRow suggestionRow(Record row, String batchNo) {
    String advice = row.get("advice", String.class);
    String status = row.get("status", String.class);
    String coverageSource = row.get("coverage_source", String.class);
    return new ReplenishmentSuggestionRow(
        row.get("id", UUID.class),
        row.get("batch_id", UUID.class),
        batchNo,
        row.get("sku_id", UUID.class),
        row.get("sku_code", String.class),
        row.get("sku_name", String.class),
        row.get("daily_sales_quantity", BigDecimal.class),
        row.get("growth_rate", BigDecimal.class),
        row.get("previous_daily_sales_quantity", BigDecimal.class),
        row.get("velocity_window_start", LocalDate.class),
        row.get("velocity_window_end", LocalDate.class),
        row.get("velocity_source_kind", String.class),
        row.get("coverage_days", Integer.class),
        coverageSource,
        coverageScopeLabel(coverageSource),
        row.get("coverage_category_code", String.class),
        row.get("target_stock_quantity", BigDecimal.class),
        row.get("available_quantity", BigDecimal.class),
        row.get("in_transit_quantity", BigDecimal.class),
        row.get("in_transit_arriving_quantity", BigDecimal.class),
        row.get("raw_required_quantity", BigDecimal.class),
        row.get("moq_quantity", BigDecimal.class),
        row.get("purchase_unit_code", String.class),
        row.get("box_spec_quantity", BigDecimal.class),
        row.get("suggested_purchase_quantity", BigDecimal.class),
        row.get("suggested_quantity", BigDecimal.class),
        row.get("rounding_adjustment_quantity", BigDecimal.class),
        row.get("rounding_note", String.class),
        row.get("min_order_amount", BigDecimal.class),
        row.get("currency", String.class),
        row.get("unit_price", BigDecimal.class),
        row.get("estimated_cost", BigDecimal.class),
        row.get("supplier_id", UUID.class),
        row.get("supplier_name", String.class),
        row.get("supplier_role_id", UUID.class),
        row.get("supplier_priority", Integer.class),
        row.get("supplier_selection_basis", String.class),
        row.get("lead_time_days", Integer.class),
        row.get("production_lead_time_days", Integer.class),
        row.get("expected_arrival_date", LocalDate.class),
        row.get("arrival_basis", String.class),
        advice,
        adviceLabel(advice),
        row.get("advice_basis", String.class),
        status,
        statusLabel(status),
        row.get("decided_quantity", BigDecimal.class),
        row.get("decision_by", UUID.class),
        row.get("decision_at", OffsetDateTime.class),
        row.get("decision_note", String.class),
        row.get("sort_rank", Integer.class));
  }

  private static String adviceLabel(String advice) {
    for (var value : ReplenishmentAdvice.values()) {
      if (value.name().equals(advice)) {
        return value.label();
      }
    }
    return advice;
  }

  private static String statusLabel(String status) {
    for (var value : ReplenishmentStatus.values()) {
      if (value.name().equals(status)) {
        return value.label();
      }
    }
    return status;
  }

  private static String decisionLabel(String action) {
    for (var value : ReplenishmentDecisionAction.values()) {
      if (value.name().equals(action)) {
        return value.label();
      }
    }
    return action;
  }

  private static String coverageScopeLabel(String scope) {
    for (var value : ReplenishmentCoverageScope.values()) {
      if (value.name().equals(scope)) {
        return value.label();
      }
    }
    return scope;
  }

  private static String stepLabel(String kind) {
    for (var value : ReplenishmentStep.values()) {
      if (value.name().equals(kind)) {
        return value.label();
      }
    }
    return kind;
  }

  /**
   * 当前租户（来自数据库里绑定的 {@code app.current_tenant}）。
   *
   * <p>刻意<b>不</b>从请求头或参数取租户：写入的 {@code tenant_id} 必须与 RLS 绑定的租户是同一个。
   */
  private UUID currentTenant() {
    var row =
        sql.fetchOne("select nullif(current_setting('app.current_tenant', true), '')::uuid as t");
    UUID tenant = row == null ? null : row.get("t", UUID.class);
    if (tenant == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    return tenant;
  }

  private static int pageSize(int size) {
    if (size <= 0) {
      return 50;
    }
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private static List<Object> append(List<Object> binds, Object... extra) {
    List<Object> all = new ArrayList<>(binds);
    all.addAll(List.of(extra));
    return all;
  }

  /** 数量口径：null 一律当 0，刻度对齐 numeric(19,6)。 */
  private static BigDecimal q(BigDecimal value) {
    return value == null ? ZERO : value.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
  }

  // ------------------------------------------------------------ 内部结构

  /** 销售速度事实（6.2.1.1 的登记）。 */
  private record VelocityFact(
      String skuCode,
      String skuName,
      BigDecimal dailySalesQuantity,
      LocalDate windowStart,
      LocalDate windowEnd,
      String sourceKind,
      String sourceRef) {}

  /** 覆盖天数配置的一层。 */
  private record CoveragePolicy(
      String scope, UUID skuId, String categoryCode, int coverageDays) {}

  /** 三层覆盖天数配置。 */
  private record CoveragePolicies(
      Map<UUID, CoveragePolicy> bySku,
      Map<String, CoveragePolicy> byCategory,
      CoveragePolicy byDefault) {}

  /** 采购起订额 / 生产周期配置的一层。 */
  private record OrderPolicy(
      UUID supplierId,
      UUID supplierRoleId,
      BigDecimal minOrderAmount,
      String currency,
      Integer productionLeadTimeDays) {}

  /** 采购起订额 / 生产周期配置（按供应商 + 租户默认）。 */
  private record OrderPolicies(Map<UUID, OrderPolicy> bySupplier, OrderPolicy byDefault) {
    OrderPolicy forSupplier(UUID supplierId) {
      if (supplierId != null) {
        OrderPolicy policy = bySupplier.get(supplierId);
        if (policy != null) {
          return policy;
        }
      }
      return byDefault;
    }
  }

  /** 计算过程的一步。 */
  private record Step(
      int order, ReplenishmentStep kind, BigDecimal quantity, String basis, String sourceRef) {}

  /**
   * 逐 SKU 的结论。
   *
   * <p>刻意做成<b>具名字段的类</b>而不是 record：它有 40 个字段，用位置参数构造时少一个、错一个
   * 编译器只会说「实参列表长度不同」，而这里少一个字段的后果是**一条建议算错却全绿**。
   * 具名字段让每个赋值都自带它的名字。
   */
  @SuppressWarnings("java:S107")
  private static final class Draft {
    UUID skuId;
    String skuCode = "";
    String skuName = "";
    BigDecimal dailySales;
    BigDecimal previousDailySales;
    BigDecimal growthRate;
    LocalDate velocityWindowStart;
    LocalDate velocityWindowEnd;
    String velocitySourceKind;
    int coverageDays;
    String coverageSource = "DEFAULT";
    String coverageCategoryCode;
    BigDecimal targetStock = ZERO;
    BigDecimal available = ZERO;
    BigDecimal inTransitTotal = ZERO;
    BigDecimal inTransitArriving = ZERO;
    BigDecimal rawRequired;
    BigDecimal moq;
    String purchaseUnitCode;
    BigDecimal boxSpec;
    BigDecimal suggestedPurchaseUnits;
    BigDecimal suggestedQuantity;
    BigDecimal roundingAdjustment;
    String roundingNote = "";
    BigDecimal minOrderAmount;
    String currency;
    BigDecimal unitPrice;
    BigDecimal estimatedCost;
    UUID supplierId;
    String supplierName = "";
    UUID supplierRoleId;
    Integer supplierPriority;
    String supplierSelectionBasis = "";
    Integer leadTimeDays;
    Integer productionLeadTimeDays;
    LocalDate expectedArrivalDate;
    String arrivalBasis = "";
    ReplenishmentAdvice advice;
    String adviceBasis = "";
    final List<Step> steps = new ArrayList<>();
    int catalogLookups;

    /** 建一个只带公共字段的骨架（每个分支随后补自己的结论）。 */
    static Draft base(
        UUID skuId,
        String skuCode,
        String skuName,
        BigDecimal dailySales,
        BigDecimal previousDailySales,
        BigDecimal growthRate,
        VelocityFact velocity,
        int coverageDays,
        String coverageSource,
        String coverageCategoryCode) {
      Draft d = new Draft();
      d.skuId = skuId;
      d.skuCode = skuCode;
      d.skuName = skuName;
      d.dailySales = dailySales;
      d.previousDailySales = previousDailySales;
      d.growthRate = growthRate;
      d.velocityWindowStart = velocity == null ? null : velocity.windowStart();
      d.velocityWindowEnd = velocity == null ? null : velocity.windowEnd();
      d.velocitySourceKind = velocity == null ? null : velocity.sourceKind();
      d.coverageDays = coverageDays;
      d.coverageSource = coverageSource;
      d.coverageCategoryCode = coverageCategoryCode;
      return d;
    }
  }
}
