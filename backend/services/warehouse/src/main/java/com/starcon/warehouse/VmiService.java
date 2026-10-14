package com.starcon.warehouse;

import static com.starcon.warehouse.VmiModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 7.7.2.1 VMI 供应商管理库存：VMI 协议 → 水位监控 → 补货信号 → 售出事实 → 结算对账单。
 *
 * <p>蓝图原话：「支持供应商管理库存（VMI）：货权属供应商、我方享有可销售权，按约定补货或由系统触发
 * 供应商备货；记录安全水位与补货信号，售出后与供应商对账结算。」
 *
 * <p><b>本模块唯一不可让步的口径：可售 ≠ 自有。</b>蓝图把它写成加粗约束
 * （「可售量要计入可售额，货值不能计入自有资产，两者必须分开统计」），
 * 07-inventory.md:24 也把它列为硬约束。它写在 V115 的四道数据库不变量里，不是这里的自觉：
 *
 * <ol>
 *   <li>{@code vmi_agreement_line.counts_as_owned_asset} 恒 false（CHECK 写死）；
 *   <li>协议行引用的 {@code stock_owner_id}（7.7.1.1 的货主档案）**不许**是
 *       {@code counts_as_owned_asset=true} 的那一种 —— 触发器 {@code vmi_line_owner_not_owned} 拒绝，
 *       反向的 {@code vmi_line_owner_still_not_owned} 也挡住「事后把货主改成 SELF」；
 *   <li>明细表里**没有第二个金额列**，能算出来的只有「可售额」；
 *   <li>{@code ownedAssetValue} 恒为 {@code null}：数据库里就不存在「VMI 自有货值」这个数。
 *       如实报 null 而不是 0 —— 给 0 会被读成「这些货值 0 元」，那是谎报。
 * </ol>
 *
 * <p><b>「可售」这一侧是真的</b>：VMI 货在 {@code stock_bucket} 里占着真实在手与可用量，
 * 按 7.1.1.3 的口径（SELLABLE 质量状态）参与可售额统计。取数顺序是
 * <b>先查我方账本（WAREHOUSE_BUCKET），账本没有再回落租户登记的观测</b> ——
 * VMI 货常常物理上就在供应商自己的仓，那种情况下我方压根没有桶，
 * 这不是数据缺失而是一种真实经营形态，所以才有 {@code vmi_stock_snapshot} 且来源必填。
 * 每行都带 {@code stockSource}，前端照实显示「这行是系统算的还是租户报上来的」。
 *
 * <p><b>货权复用 7.7.1.1（V67）已交付的 {@code stock_owner}</b>，本模块不 ALTER
 * {@code stock_bucket} / {@code inventory_balance}，也不复制货主权威列。
 *
 * <p><b>上游缺口显式报出而不是猜</b>：13 销量 / 9 订单未交付 → 售出事实自落地 {@code vmi_sale}
 * （以 {@code (协议行, saleRef)} 幂等并标 {@code sourceSystem}）；
 * 6 采购 / 12 结算未交付 → 补货信号与对账单只出本服务内单据并留
 * {@code source_system + reference_id} 定位键，不跨库写、不建跨服务 FK、不查对方表。
 * 未接入的每一环都在 {@code /capabilities} 的 {@code unavailable} 里点名模块与原因。
 *
 * <p><b>结算口径</b>：数量 numeric(19,6)、结算价 numeric(19,4)、明细金额 numeric(24,4)
 * 生成列；**单据级应付 HALF_UP 到分 numeric(24,2)**，由 DB 触发器按
 * {@code round(Σ明细金额, 2)} 重算，与服务层 {@code setScale(2, HALF_UP)} 同口径。
 *
 * <p><b>时间绑定纪律</b>：{@code ?::timestamptz} 一律出现在语句最前面的 {@code t} CTE 里，
 * 后面全是普通绑定，cast 永远不在绑定序列末位。写入用 {@link OffsetDateTime} 绑定，
 * 不走 {@code Timestamp.valueOf} —— 后者按 JVM 本地时区解释裸本地时间，在东八区会静默偏 −28800s。
 */
@Service
public class VmiService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 单张协议最多收多少明细行。 */
  public static final int MAX_LINES = 2000;

  /** 单张对账单最多收多少条售出事实。 */
  public static final int MAX_SETTLEMENT_LINES = 5000;

  /** 审计时间线单次返回上限。 */
  static final int MAX_AUDIT_ROWS = 500;

  /** 覆盖率统计窗口（天）。 */
  static final int COVERAGE_WINDOW_DAYS = 30;

  /** numeric(19,6) 口径下的零。 */
  private static final BigDecimal ZERO = new BigDecimal("0.000000");

  /** numeric(19,4) 口径下的零（结算价）。 */
  private static final BigDecimal ZERO_PRICE = new BigDecimal("0.0000");

  /** 口径原文：可售这一侧。 */
  private static final String SELLABLE_NOTE =
      "VMI 的在库量按 SELLABLE 质量状态计入可售额：这批货我方有可销售权，可以上架、可以预占、可以卖。";

  /** 口径原文：自有资产这一侧。 */
  private static final String OWNED_ASSET_NOTE =
      "VMI 货权属供应商（countsAsOwnedAsset 恒 false，由数据库 CHECK 与货主校验双重钉死），"
          + "货值不计入我方自有资产：VMI 的可售额里有这批货，我方资产负债表里没有。";

  /** 口径原文：两个数字为什么必须分开。 */
  private static final String SEPARATION_NOTE =
      "「可售」回答「我能不能卖」，「自有」回答「这批货是谁的」。VMI 我方能卖但货不是我的，"
          + "所以可售额计入、自有资产不计入，两者必须分开统计 —— 合并成一个「库存价值」"
          + "就会把供应商的货算成我们的资产。";

  private static final String SALES_FACT_NOTE =
      "售出事实由本服务记（9 订单 / 13 销量尚未交付）。以 (协议行, saleRef) 幂等，"
          + "重复推送不会卖两次、也就不会对账两次；两者交付后由它们推这张表，届时本表仍是那份事实的唯一出处。";

  private static final String AVAILABLE_QUANTITY_NOTE =
      "在库可售量取数顺序：先查我方账本（7.1.1.3 的 stock_bucket 按货主聚合 SELLABLE 可用量），"
          + "账本里没有这个 SKU×仓的桶时，回落到租户登记的 vmi_stock_snapshot —— "
          + "VMI 货常常物理上在供应商自己的仓，那种情况下我方压根没有桶。每行都带 stockSource，"
          + "不会把租户报上来的数当成系统算出来的数。";

  private static final String SIGNAL_HANDOFF_NOTE =
      "补货信号是本服务内的事实。signal_target='SUPPLIER' 时推给供应商；"
          + "signal_target='PROCUREMENT' 时交给 6.2.1.2 备货 —— 6 采购尚未交付，"
          + "本模块只写 source_system + reference_id 交接键，不跨库写它的表、不建跨服务 FK。";

  private static final String SETTLEMENT_HANDOFF_NOTE =
      "结算对账单是本服务内的事实，12 结算尚未交付。本模块只写 source_system + reference_id "
          + "定位键，12 交付后按该键读回；本模块不替 12 写应付、不建跨服务 FK、不查对方的表。";

  /** 未接入的上游/下游环节（capabilities 里显式报出，而不是从 404 或空数组里让人猜）。 */
  private static final List<VmiUnavailableCapability> UNAVAILABLE =
      List.of(
          new VmiUnavailableCapability(
              "13 销售分析（实时销量）",
              "13.1.1.1",
              "analytics",
              "尚未交付。本模块的销量口径只来自本服务自落地的 vmi_sale 售出事实，"
                  + "不是完整的多平台销量；日均销量口径因此只在自落地事实覆盖的范围内成立。",
              "两者交付后由 13 推 vmi_sale，或由本模块改读 13 的授权 API"),
          new VmiUnavailableCapability(
              "6.2.1.2 采购备货",
              "6.2.1.2",
              "trade",
              "尚未交付。补货信号只在本服务内出单并写 source_system + reference_id 交接键，"
                  + "不代 6 采购建备货单。",
              "6 采购交付后按 (source_system, reference_id) 读回信号单"),
          new VmiUnavailableCapability(
              "12 结算 / 应付",
              "12.1.2.1",
              "finance",
              "尚未交付。VMI 对账单只在本服务内出单并写交接键，不代 12 写应付。",
              "12 交付后按 (source_system, reference_id) 读回对账单"),
          new VmiUnavailableCapability(
              "9 订单履约（售出事实推送）",
              "9.3.1.1",
              "trade",
              "尚未交付。售出事实目前由本服务自落地 vmi_sale，接单需人工或运营接口登记。",
              "9 交付后由订单侧推 vmi_sale，本表即为那份事实的唯一出处"),
          new VmiUnavailableCapability(
              "供应商侧库存自动采集",
              "3.6.2",
              "catalog",
              "供应商档案与对接能力尚未交付。供应商仓的在库量目前靠 vmi_stock_snapshot 登记，"
                  + "来源必须显式标 TENANT_MAINTAINED 或 VENDOR_FEED，不会假装是系统算出来的。",
              "供应商对接交付后改由 VENDOR_FEED 自动采集，本表即为观测留痕"));

  private final DSLContext sql;

  public VmiService(DSLContext sql) {
    this.sql = sql;
  }

  // ============================================================ 能力

  /** VMI 能力说明：前端据此渲染选项与提示，并把未接入的环节讲清楚。 */
  public VmiCapabilities capabilities() {
    var modes = List.of(VmiReplenishmentMode.values()).stream()
        .map(m -> new VmiOption(m.name(), m.label(), m.description()))
        .toList();
    var targets = List.of(VmiSignalTarget.values()).stream()
        .map(t -> new VmiOption(t.name(), t.label(), t.description()))
        .toList();
    var cycles = List.of(VmiSettlementCycle.values()).stream()
        .map(c -> new VmiOption(c.name(), c.label(), c.description()))
        .toList();
    var reasons = List.of(VmiSignalTriggerReason.values()).stream()
        .map(r -> new VmiOption(r.name(), r.label(), r.description()))
        .toList();
    var sources = List.of(VmiStockSource.values()).stream()
        .map(s -> new VmiOption(s.name(), s.label(), s.description()))
        .toList();
    return new VmiCapabilities(
        modes,
        targets,
        cycles,
        reasons,
        sources,
        List.of("DRAFT", "ACTIVE", "SUSPENDED", "CLOSED"),
        List.of("OPEN", "ACKNOWLEDGED", "DISPATCHED", "FULFILLED", "CANCELLED"),
        UNAVAILABLE,
        SELLABLE_NOTE,
        OWNED_ASSET_NOTE,
        SEPARATION_NOTE,
        false,
        SALES_FACT_NOTE,
        AVAILABLE_QUANTITY_NOTE,
        SIGNAL_HANDOFF_NOTE,
        SETTLEMENT_HANDOFF_NOTE);
  }

  // ============================================================ 协议

  public VmiAgreementDetail createAgreement(VmiAgreementInput input, UUID actorId) {
    var tenant = tenant();
    if (input.lines() == null || input.lines().isEmpty()) {
      throw bad("VMI 协议必须至少带一行明细：没有安全水位与结算价的协议无法监控也无法对账。");
    }
    if (input.lines().size() > MAX_LINES) {
      throw bad("单张协议的明细行数超过上限 " + MAX_LINES + " 行，请分批建立。");
    }
    String cycle = requireEnum(input.settlementCycle(), VmiSettlementCycle.class, "结算周期");
    String mode = requireEnum(input.replenishmentMode(), VmiReplenishmentMode.class, "补货模式");
    String target = "VENDOR_OWN".equals(mode)
        ? "SUPPLIER"
        : requireEnum(
            input.signalTarget() == null ? "SUPPLIER" : input.signalTarget(),
            VmiSignalTarget.class,
            "信号去向");
    String currency = requireCurrency(input.currency());
    if (input.supplierPartyId() == null) {
      throw bad("必须指定 VMI 货的货主（供应商）supplierPartyId：VMI 的货属供应商，不是本租户。");
    }
    requireText(input.supplierCode(), "供应商编码");
    requireText(input.supplierName(), "供应商名称");
    if (input.startDate() == null) {
      throw bad("必须指定协议生效起始日 startDate。");
    }
    if (input.endDate() != null && input.endDate().isBefore(input.startDate())) {
      throw bad("协议结束日不能早于起始日。");
    }
    requireRef(input.legalEntityId(), "法人 legalEntityId");

    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into vmi_agreement(id,tenant_id,agreement_code,supplier_party_id,supplier_code,"
            + "supplier_name,legal_entity_id,currency,settlement_cycle,replenishment_mode,"
            + "signal_target,start_date,end_date,status,note,created_by_user_id) "
            + "values(?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?,?)",
        id, tenant, nextCode("VMI"), input.supplierPartyId(), input.supplierCode().trim(),
        input.supplierName().trim(), input.legalEntityId(), currency, cycle, mode, target,
        input.startDate(), input.endDate(), nz(input.note()), actorId);

    for (var line : input.lines()) {
      insertLine(tenant, id, line);
    }
    audit(tenant, id, "AGREEMENT_CREATED", "建立 VMI 协议 " + input.lines().size() + " 行", actorId);
    return detail(id);
  }

  /** 协议列表（真分页；行数与合计由明细聚合，不另存列）。 */
  public VmiAgreementPage agreements(
      String status, String replenishmentMode, String keyword, int pageNo, int size) {
    int limit = Math.min(MAX_PAGE_SIZE, Math.max(1, size <= 0 ? 20 : size));
    int offset = Math.max(0, pageNo) * limit;
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("a.tenant_id = ?");
    binds.add(tenant());
    if (notBlank(status)) {
      where.add("a.status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(replenishmentMode)) {
      where.add("a.replenishment_mode = ?");
      binds.add(replenishmentMode.trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(keyword)) {
      where.add("(a.agreement_code ilike ? or a.supplier_code ilike ? or a.supplier_name ilike ? or a.note ilike ?)");
      String k = "%" + keyword.trim() + "%";
      binds.add(k);
      binds.add(k);
      binds.add(k);
      binds.add(k);
    }
    String sqlWhere = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from vmi_agreement a" + sqlWhere, binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            AGREEMENT_COLUMNS + " from " + AGREEMENT_FROM + sqlWhere
                + " order by a.created_at desc, a.id limit ? offset ?",
            append(binds, limit, (long) offset).toArray());
    return new VmiAgreementPage(
        rows.stream().map(VmiService::agreement).toList(), total, Math.max(0, pageNo), limit);
  }

  /** 协议详情 = 头部 + 全部明细（含水位与分账）+ 审计时间线。跨租户 → 404。 */
  public VmiAgreementDetail agreement(UUID agreementId) {
    return detail(agreementId);
  }

  /** 给已存在的协议补一个 SKU（水位、倍数、结算价）。已在跑的协议不能中途改价。 */
  @Transactional
  public VmiAgreementDetail addLine(UUID agreementId, VmiAddLineInput input, UUID actorId) {
    var tenant = tenant();
    var head = requireHead(tenant, agreementId);
    if (!"DRAFT".equals(head.get("status", String.class))) {
      throw conflict("只有草稿协议能加行；当前状态 " + head.get("status", String.class)
          + "。已在跑的协议要改水位或结算价请新建协议，否则中途改价会让已出对账单与协议对不上。");
    }
    insertLine(
        tenant, agreementId,
        new VmiLineInput(
            input.skuId(), input.warehouseId(), input.stockOwnerId(), input.safetyLevel(),
            input.replenishmentMultiple(), input.settlementPrice(), input.suppliedQuantity(),
            input.onHandQuantity()));
    audit(tenant, agreementId, "LINE_ADDED", "新增 VMI 明细行", actorId);
    return detail(agreementId);
  }

  /** 启用协议：DRAFT → ACTIVE。水位巡检、售出、对账都要求协议已启用。 */
  @Transactional
  public VmiAgreement activate(UUID agreementId, VmiNoteInput input, UUID actorId) {
    return transition(agreementId, "DRAFT", "ACTIVE", "AGREEMENT_ACTIVATED", input, actorId);
  }

  /** 暂停协议：ACTIVE → SUSPENDED。暂停后不得再记售出（供应商可能已停止供货）。 */
  @Transactional
  public VmiAgreement suspend(UUID agreementId, VmiNoteInput input, UUID actorId) {
    return transition(agreementId, "ACTIVE", "SUSPENDED", "AGREEMENT_SUSPENDED", input, actorId);
  }

  /** 关闭协议：任何在跑状态 → CLOSED。关闭后全部明细行被 DB 冻结，数量不能再动。 */
  @Transactional
  public VmiAgreement close(UUID agreementId, VmiNoteInput input, UUID actorId) {
    var tenant = tenant();
    requireHead(tenant, agreementId);
    OffsetDateTime now = OffsetDateTime.now();
    int changed =
        sql.execute(
            "update vmi_agreement set status='CLOSED', closed_at=?::timestamptz,"
                + " closed_by_user_id=?, note=coalesce(nullif(?,''),note), updated_at=?::timestamptz"
                + " where tenant_id=? and id=? and status in ('ACTIVE','SUSPENDED')",
            now, actorId, note(input), now, tenant, agreementId);
    if (changed == 0) {
      throw conflict("只有生效中或已暂停的协议能关闭（草稿请直接删掉重建）。");
    }
    audit(tenant, agreementId, "AGREEMENT_CLOSED", "关闭协议，全部明细行冻结", actorId);
    return single(agreementId);
  }

  private VmiAgreement transition(
      UUID agreementId, String from, String to, String event, VmiNoteInput input, UUID actorId) {
    var tenant = tenant();
    requireHead(tenant, agreementId);
    OffsetDateTime now = OffsetDateTime.now();
    // 启用时刻与状态同时落，保证 CHECK(status<>'ACTIVE') OR activated_at IS NOT NULL 成立。
    sql.execute(
        "update vmi_agreement set status=?, activated_at=coalesce(activated_at,?::timestamptz),"
            + " activated_by_user_id=coalesce(activated_by_user_id,?::uuid),"
            + " note=coalesce(nullif(?,''),note), updated_at=?::timestamptz"
            + " where tenant_id=? and id=? and status=?",
        to, now, actorId, note(input), now, tenant, agreementId, from);
    audit(tenant, agreementId, event, "协议状态 " + from + " → " + to, actorId);
    return single(agreementId);
  }

  private VmiAgreement single(UUID agreementId) {
    var tenant = tenant();
    var row =
        sql.fetchOne(AGREEMENT_COLUMNS + " from " + AGREEMENT_FROM + " where a.tenant_id=? and a.id=?",
            tenant, agreementId);
    if (row == null) {
      throw notFound("VMI 协议", agreementId);
    }
    return agreement(row);
  }

  // ============================================================ 水位监控

  /**
   * VMI 水位监控（蓝图输出「VMI 水位监控与补货信号」的前半句）。
   *
   * <p>两段口径**并排返回、绝不相加**：{@code ownershipSplit} 是 VMI 货（可售额计入、
   * 自有货值恒 null），{@code selfOwnedSplit} 是同一批 SKU 在我方自有货主下的量。
   * 「分开统计」要能被人验证，只报 VMI 的数，读者无从判断我们有没有把 VMI 的量混进自有。
   */
  public VmiWaterLevelMonitor waterLevelMonitor(UUID agreementId) {
    var tenant = tenant();
    var head = requireHead(tenant, agreementId);
    var lines = lineRows(tenant, agreementId);

    long healthy = 0, belowBuffer = 0, belowSafety = 0, stockout = 0, missing = 0;
    BigDecimal safety = ZERO, current = ZERO, projected = ZERO;
    for (var l : lines) {
      var stock = currentStock(tenant, l);
      var calc = waterLevel(l, stock.quantity());
      switch (calc.status()) {
        case "HEALTHY" -> healthy++;
        case "BELOW_BUFFER" -> belowBuffer++;
        case "BELOW_SAFETY_LEVEL" -> belowSafety++;
        default -> stockout++;
      }
      if (stock.source() == null) {
        missing++;
      }
      safety = safety.add(scale6(l.get("safety_level", BigDecimal.class)));
      current = current.add(scale6(stock.quantity()));
      projected = projected.add(calc.projected());
    }
    var openSignals =
        sql.fetchOne(
            "select count(*) from vmi_replenishment_signal s"
                + " join vmi_agreement_line l on l.tenant_id = s.tenant_id and l.id = s.agreement_line_id"
                + " where l.agreement_id = ? and s.tenant_id = ? and s.status in ('OPEN','ACKNOWLEDGED','DISPATCHED')",
            agreementId, tenant)
            .get(0, Long.class);
    var unsettled =
        sql.fetchOne(
            "select coalesce(sum(sa.sold_quantity),0) as qty,"
                + " coalesce(sum(sa.sold_quantity * li.settlement_price),0) as amt"
                + " from vmi_sale sa"
                + " join vmi_agreement_line li on li.tenant_id = sa.tenant_id and li.id = sa.agreement_line_id"
                + " where li.agreement_id = ? and sa.tenant_id = ?"
                + " and not exists (select 1 from vmi_settlement_line sl"
                + "                 where sl.tenant_id = sa.tenant_id and sl.sale_id = sa.id)",
            agreementId, tenant);

    var selfOwned = selfOwnedSplit(tenant, lines);
    BigDecimal sellableValue = ZERO;
    for (var l : lines) {
      var stock = currentStock(tenant, l);
      sellableValue =
          sellableValue.add(
              price(
                  scale6(stock.quantity())
                      .multiply(price(l.get("settlement_price", BigDecimal.class)))));
    }
    return new VmiWaterLevelMonitor(
        agreementId,
        head.get("agreement_code", String.class),
        head.get("supplier_name", String.class),
        head.get("currency", String.class),
        head.get("replenishment_mode", String.class),
        label(VmiReplenishmentMode.values(), head.get("replenishment_mode", String.class)),
        head.get("signal_target", String.class),
        label(VmiSignalTarget.values(), head.get("signal_target", String.class)),
        head.get("status", String.class),
        lines.size(),
        healthy,
        belowBuffer,
        belowSafety,
        stockout,
        missing,
        scale6(safety),
        scale6(current),
        scale6(projected),
        openSignals,
        new VmiOwnershipSplit(
            scale6(current), price(sellableValue), ZERO, null, false,
            OWNED_ASSET_NOTE, SEPARATION_NOTE),
        selfOwned,
        lines.stream().map(l -> line(tenant, l, true)).toList());
  }

  /**
   * 我方自有货的对照口径：同一批 SKU×仓 在 SELF 货主下的可售量。
   *
   * <p>只统计 SELLABLE 质量状态的 bucket 可用量 —— 与 VMI 侧取的是同一个口径，
   * 否则「并排对比」会因为口径不同而看起来 VMI 更少。
   */
  private VmiSelfOwnedSplit selfOwnedSplit(UUID tenant, List<org.jooq.Record> lines) {
    if (lines.isEmpty()) {
      return new VmiSelfOwnedSplit(ZERO, 0, "协议下还没有明细行，自有货口径为空。");
    }
    var pairs = new ArrayList<Object>();
    var ors = new ArrayList<String>();
    for (var l : lines) {
      ors.add("(k.sku_id = ? and k.warehouse_id = ?)");
      pairs.add(l.get("sku_id", UUID.class));
      pairs.add(l.get("warehouse_id", UUID.class));
    }
    var binds = new ArrayList<Object>();
    binds.add(tenant);
    binds.addAll(pairs);
    var row =
        sql.fetchOne(
            "select coalesce(sum(k.available),0) as qty, count(*) as cnt"
                + " from stock_bucket k"
                + " join inventory_quality_status q on q.tenant_id = k.tenant_id"
                + "   and q.id = k.quality_status_id and q.role = 'SELLABLE'"
                + " join stock_owner o on o.tenant_id = k.tenant_id and o.id = k.stock_owner_id"
                + " where k.tenant_id = ? and o.owner_kind = 'SELF'"
                + " and (" + String.join(" or ", ors) + ")",
            binds.toArray());
    return new VmiSelfOwnedSplit(
        scale6(row.get("qty", BigDecimal.class)),
        row.get("cnt", Long.class),
        "同一批 SKU×仓 在我方自有货主（SELF）下的可售量，与 VMI 的可售量**并排展示、绝不相加**："
            + "两者的货主不同，相加就等于把供应商的货算成我们的资产。");
  }

  // ============================================================ 补货信号

  /**
   * 按协议批量出补货信号（蓝图输出「补货信号（推给供应商或 6.2.1.2 备货）」）。
   *
   * <p>每行的去留都在结果里说清楚（{@code skippedReasons}）：已有一条未了结信号、水位健康、
   * 协议是「供应商自行补货」三种情况都会跳过并给出原因 —— 跳过原因不报出来的话，
   * 「我出了 0 张信号」会被读成「系统没发现问题」。
   */
  @Transactional
  public VmiSignalBatchResult generateSignals(UUID agreementId, VmiSignalInput input, UUID actorId) {
    var tenant = tenant();
    var head = requireHead(tenant, agreementId);
    if (!"ACTIVE".equals(head.get("status", String.class))) {
      throw conflict("只有生效中的协议能出补货信号；当前状态 " + head.get("status", String.class) + "。");
    }
    var mode = head.get("replenishment_mode", String.class);
    var target = head.get("signal_target", String.class);
    var lines = lineRows(tenant, agreementId);
    var wanted =
        input == null || input.lineIds() == null || input.lineIds().isEmpty()
            ? null
            : input.lineIds();

    var created = new ArrayList<VmiSignal>();
    var reasons = new ArrayList<String>();
    long skippedOpen = 0, skippedHealthy = 0;
    for (var l : lines) {
      if (wanted != null && !wanted.contains(l.get("id", UUID.class))) {
        continue;
      }
      if ("VENDOR_OWN".equals(mode)) {
        reasons.add(
            "SKU " + l.get("sku_code", String.class)
                + "：协议是「供应商自行补货」，本系统只监控水位、不出信号。");
        continue;
      }
      var open =
          sql.fetchOne(
              "select signal_code from vmi_replenishment_signal"
                  + " where tenant_id=? and agreement_line_id=?"
                  + " and status in ('OPEN','ACKNOWLEDGED','DISPATCHED')",
              tenant, l.get("id", UUID.class));
      if (open != null) {
        skippedOpen++;
        reasons.add(
            "SKU " + l.get("sku_code", String.class) + "：已有一条未了结的信号 "
                + open.get("signal_code", String.class) + "，不重复出单。");
        continue;
      }
      var stock = currentStock(tenant, l);
      var calc = waterLevel(l, stock.quantity());
      if (calc.status().equals("HEALTHY")) {
        skippedHealthy++;
        reasons.add(
            "SKU " + l.get("sku_code", String.class) + "：水位健康（当前 "
                + scale6(stock.quantity()).toPlainString() + " ≥ 缓冲线 "
                + calc.bufferLine().toPlainString() + "），无需补货。");
        continue;
      }
      UUID id = UUID.randomUUID();
      sql.execute(
          "insert into vmi_replenishment_signal(id,tenant_id,signal_code,agreement_line_id,"
              + "trigger_reason,current_sellable_quantity,safety_level,replenishment_multiple,"
              + "suggested_quantity,signal_target,status,source_system,note,created_by_user_id)"
              + " values(?,?,?,?,?,?,?,?,?,?,'OPEN','WAREHOUSE',?,?)",
          id, tenant, nextCode("VRS"), l.get("id", UUID.class), calc.status(),
          scale6(stock.quantity()), scale6(l.get("safety_level", BigDecimal.class)),
          scale6(l.get("replenishment_multiple", BigDecimal.class)), calc.projected(), target,
          nz(input == null ? null : input.note()), actorId);
      created.add(signal(tenant, id));
      audit(tenant, agreementId, "SIGNAL_GENERATED",
          "出补货信号 " + calc.status() + "（当前 " + scale6(stock.quantity()).toPlainString()
              + "，水位 " + scale6(l.get("safety_level", BigDecimal.class)).toPlainString()
              + "，建议 " + calc.projected().toPlainString() + "）",
          actorId);
    }
    long skippedVendorOwn = lines.stream()
        .filter(l -> wanted == null || wanted.contains(l.get("id", UUID.class)))
        .count() - created.size() - skippedOpen - skippedHealthy;
    return new VmiSignalBatchResult(
        agreementId, created.size(), skippedOpen, skippedHealthy, Math.max(0, skippedVendorOwn),
        created, reasons);
  }

  /** 信号受理确认：OPEN → ACKNOWLEDGED。 */
  @Transactional
  public VmiSignal acknowledgeSignal(UUID signalId, VmiNoteInput input, UUID actorId) {
    return moveSignal(signalId, "OPEN", "ACKNOWLEDGED", "SIGNAL_ACKNOWLEDGED", input, actorId);
  }

  /** 信号下发（推给供应商 / 交给 6 采购）：ACKNOWLEDGED → DISPATCHED。 */
  @Transactional
  public VmiSignal dispatchSignal(UUID signalId, VmiNoteInput input, UUID actorId) {
    var tenant = tenant();
    requireSignal(tenant, signalId);
    OffsetDateTime now = OffsetDateTime.now();
    // 下游 6 采购 / 供应商尚未交付：这里只写交接定位键，不代它们建单。
    sql.execute(
        "update vmi_replenishment_signal set status='DISPATCHED', dispatched_at=?::timestamptz,"
            + " reference_id=coalesce(nullif(reference_id,''),?), source_system=?::varchar,"
            + " note=coalesce(nullif(?,''),note), updated_at=?::timestamptz"
            + " where tenant_id=? and id=? and status='ACKNOWLEDGED'",
        now, signalId.toString(), "WAREHOUSE", note(input), now, tenant, signalId);
    audit(tenant, agreementIdOfSignal(tenant, signalId), "SIGNAL_DISPATCHED",
        "补货信号已下发", actorId);
    return signal(tenant, signalId);
  }

  /**
   * 信号登记到货：DISPATCHED → FULFILLED，并把到货数累加进协议行的累计供货。
   *
   * <p>这一步才真正改变「供应商送来了多少」—— 也只有它能让累计供货追上已售，
   * 否则「已售 > 累计供货」的守恒 CHECK 会挡住继续售出。
   */
  @Transactional
  public VmiSignal fulfilSignal(UUID signalId, VmiSignalFulfilInput input, UUID actorId) {
    var tenant = tenant();
    var row = requireSignal(tenant, signalId);
    BigDecimal qty = requirePositive(input.fulfilledQuantity(), "到货数量");
    UUID lineId = row.get("agreement_line_id", UUID.class);
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update vmi_agreement_line set supplied_quantity = supplied_quantity + ?,"
            + " version = version + 1, updated_at = ?::timestamptz where tenant_id=? and id=?",
        qty, now, tenant, lineId);
    sql.execute(
        "update vmi_replenishment_signal set status='FULFILLED', fulfilled_at=?::timestamptz,"
            + " fulfilled_quantity=?, note=coalesce(nullif(?,''),note), updated_at=?::timestamptz"
            + " where tenant_id=? and id=? and status='DISPATCHED'",
        now, qty, note(input), now, tenant, signalId);
    audit(tenant, agreementIdOfSignal(tenant, signalId), "SIGNAL_FULFILLED",
        "补货到货 " + qty.toPlainString(), actorId);
    return signal(tenant, signalId);
  }

  /** 作废信号：OPEN / ACKNOWLEDGED / DISPATCHED → CANCELLED。 */
  @Transactional
  public VmiSignal cancelSignal(UUID signalId, VmiNoteInput input, UUID actorId) {
    return moveSignal(signalId, null, "CANCELLED", "SIGNAL_CANCELLED", input, actorId);
  }

  private VmiSignal moveSignal(
      UUID signalId, String from, String to, String event, VmiNoteInput input, UUID actorId) {
    var tenant = tenant();
    requireSignal(tenant, signalId);
    OffsetDateTime now = OffsetDateTime.now();
    int changed =
        sql.execute(
            "update vmi_replenishment_signal set status=?,"
                + " acknowledged_at=case when ?='ACKNOWLEDGED' then ?::timestamptz else acknowledged_at end,"
                + " note=coalesce(nullif(?,''),note),"
                + " cancelled_at=case when ?='CANCELLED' then ?::timestamptz else cancelled_at end,"
                + " updated_at=?::timestamptz"
                + " where tenant_id=? and id=?" + (from == null ? "" : " and status='" + from + "'"),
            to, to, now, nz(input == null ? null : input.note()), to, now, now, tenant, signalId);
    if (changed == 0) {
      throw conflict(
          "信号当前状态不允许这个流转" + (from == null ? "" : "（要求 " + from + "）")
              + "，可能已被处理或已到货。");
    }
    audit(tenant, agreementIdOfSignal(tenant, signalId), event, "信号状态 → " + to, actorId);
    return signal(tenant, signalId);
  }

  /** 补货信号列表（真分页，可跨协议按状态筛）。 */
  public VmiSignalPage signals(
      UUID agreementId, String status, String signalTarget, int pageNo, int size) {
    int limit = Math.min(MAX_PAGE_SIZE, Math.max(1, size <= 0 ? 20 : size));
    int offset = Math.max(0, pageNo) * limit;
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("s.tenant_id = ?");
    binds.add(tenant());
    if (agreementId != null) {
      where.add("s.agreement_line_id in (select id from vmi_agreement_line"
          + " where tenant_id = ? and agreement_id = ?)");
      binds.add(tenant());
      binds.add(agreementId);
    }
    if (notBlank(status)) {
      where.add("s.status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(signalTarget)) {
      where.add("s.signal_target = ?");
      binds.add(signalTarget.trim().toUpperCase(Locale.ROOT));
    }
    String sqlWhere = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from vmi_replenishment_signal s" + sqlWhere, binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            SIGNAL_COLUMNS + " from " + SIGNAL_FROM + sqlWhere
                + " order by s.created_at desc, s.id limit ? offset ?",
            append(binds, limit, (long) offset).toArray());
    return new VmiSignalPage(
        rows.stream().map(VmiService::signalRow).toList(), total, Math.max(0, pageNo), limit);
  }

  // ============================================================ 在库观测

  /**
   * 登记在库观测 —— VMI 货物理上在供应商自己仓、我方账本没有桶时，**唯一**的落点。
   *
   * <p>来源（{@code stockSource}）必填且受数据库词表约束，观测时刻也必填：
   * 「这个数是什么时候的、哪来的」是水位判断的前提，
   * 缺了它就会把租户报上来的数当成系统算出来的数。
   */
  @Transactional
  public VmiSnapshot recordSnapshot(
      UUID agreementId, VmiSnapshotInput input, UUID actorId) {
    var tenant = tenant();
    requireHead(tenant, agreementId);
    var line = requireLine(tenant, agreementId, input.agreementLineId());
    String source =
        requireEnum(input.stockSource(), VmiStockSource.class, "在库量来源 stockSource");
    if ("WAREHOUSE_BUCKET".equals(source)) {
      throw bad(
          "WAREHOUSE_BUCKET 是系统从 7.1.1.3 账本算出来的口径，不接受手工登记："
              + "手工往里写一个数会让「系统算的」与「人报的」混在一起，来源标注就失去意义了。");
    }
    if (input.observedAt() == null) {
      throw bad("必须指定观测时刻 observedAt：这个数是什么时候的，是水位判断的前提。");
    }
    BigDecimal qty = scale6(requireNonNegative(input.observedQuantity(), "观测在库量"));
    BigDecimal coverage =
        input.coverageDays() == null ? null : scale6(requireNonNegative(input.coverageDays(), "可售天数"));
    UUID id = UUID.randomUUID();
    // t CTE 让 ?::timestamptz 成为第 1 个绑定，cast 不落在绑定序列末位。
    sql.execute(
        "with t as (select ?::timestamptz as at) "
            + "insert into vmi_stock_snapshot(id,tenant_id,agreement_line_id,stock_source,"
            + "source_system,observed_at,observed_quantity,coverage_days,note,recorded_by_user_id)"
            + " select ?,?,?,?,?,t.at,?,?,?,? from t",
        input.observedAt(), id, tenant, line.get("id", UUID.class), source,
        notBlank(input.sourceSystem()) ? input.sourceSystem().trim() : "MANUAL",
        qty, coverage, nz(input.note()), actorId);
    // 观测同时更新协议行的「在库量」：它是 WATERHOUSE_BUCKET 缺失时的口径来源。
    sql.execute(
        "update vmi_agreement_line set on_hand_quantity = ?,"
            + " version = version + 1, updated_at = ?::timestamptz where tenant_id=? and id=?",
        qty, OffsetDateTime.now(), tenant, line.get("id", UUID.class));
    audit(tenant, agreementId, "SNAPSHOT_RECORDED",
        "登记在库观测 " + qty.toPlainString() + "（来源 " + source + "）", actorId);
    return snapshot(tenant, id);
  }

  /** 在库观测列表（真分页，按协议行筛）。 */
  public VmiSnapshotPage snapshots(UUID agreementId, UUID lineId, int pageNo, int size) {
    var tenant = tenant();
    requireHead(tenant, agreementId);
    int limit = Math.min(MAX_PAGE_SIZE, Math.max(1, size <= 0 ? 20 : size));
    int offset = Math.max(0, pageNo) * limit;
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("n.tenant_id = ? and n.agreement_line_id in"
        + " (select id from vmi_agreement_line where tenant_id = ? and agreement_id = ?)");
    binds.add(tenant);
    binds.add(tenant);
    binds.add(agreementId);
    if (lineId != null) {
      where.add("n.agreement_line_id = ?");
      binds.add(lineId);
    }
    String sqlWhere = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from vmi_stock_snapshot n" + sqlWhere, binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select n.id, n.agreement_line_id, n.stock_source, n.source_system, n.observed_quantity,"
                + " n.coverage_days, n.observed_at, n.note, n.recorded_by_user_id, n.created_at,"
                + " k.code as sku_code, k.name as sku_name"
                + " from vmi_stock_snapshot n"
                + " join vmi_agreement_line l on l.tenant_id = n.tenant_id and l.id = n.agreement_line_id"
                + " join sku_ref k on k.tenant_id = l.tenant_id and k.id = l.sku_id" + sqlWhere
                + " order by n.observed_at desc, n.id desc limit ? offset ?",
            append(binds, limit, (long) offset).toArray());
    return new VmiSnapshotPage(
        rows.stream().map(VmiService::snapshotRow).toList(), total, Math.max(0, pageNo), limit);
  }

  // ============================================================ 售出事实

  /**
   * 记一条售出事实 —— 这是「售出后才与供应商对账结算」的唯一来源。
   *
   * <p>三道防线：
   *
   * <ol>
   *   <li>协议必须已启用（ACTIVE）：草稿/暂停/关闭的协议不产生销售；
   *   <li>本次售出量不得超过累计供货：数据库 {@code CHECK(sold <= supplied)} 之外再挡一次，
   *       好让人看得懂 —— 在卖供应商没送到的货之前不能先记销售，那会凭空生成一笔应付；
   *   <li>{@code (agreement_line_id, sale_ref)} 唯一：同一外部订单行重复推送撞 409。
   * </ol>
   */
  @Transactional
  public VmiSale recordSale(UUID agreementId, UUID lineId, VmiSaleInput input, UUID actorId) {
    var tenant = tenant();
    var head = requireHead(tenant, agreementId);
    if (!"ACTIVE".equals(head.get("status", String.class))) {
      throw conflict("只有生效中的协议能记售出；当前状态 " + head.get("status", String.class) + "。");
    }
    var line = requireLine(tenant, agreementId, lineId);
    BigDecimal qty = requirePositive(input.soldQuantity(), "售出数量");
    requireText(input.saleRef(), "销售事实引用 saleRef");
    if (input.occurredAt() == null) {
      throw bad("必须指定售出时刻 occurredAt：对账按实际售出归期，缺了它就无法归期。");
    }
    if (input.salePrice() != null && input.salePrice().signum() < 0) {
      throw bad("售价不能为负。");
    }
    String currency = head.get("currency", String.class);
    BigDecimal supplied = line.get("supplied_quantity", BigDecimal.class);
    if (qty.compareTo(supplied) > 0) {
      throw conflict("本次售出 " + qty.toPlainString() + " 超过该行累计供货 "
          + supplied.toPlainString() + "，已拒绝。VMI 货主是供应商：在供应商还没把货送过来之前"
          + "不能先记销售 —— 那会凭空生成一笔应付。");
    }
    UUID id = UUID.randomUUID();
    String source = notBlank(input.sourceSystem()) ? input.sourceSystem().trim() : "MANUAL";
    BigDecimal price = input.salePrice() == null ? null : price(input.salePrice());
    // t CTE 让 ?::timestamptz 成为第 1 个绑定，cast 不落在绑定序列末位。
    try {
      sql.execute(
          "with t as (select ?::timestamptz as at) "
              + "insert into vmi_sale(id,tenant_id,agreement_line_id,sold_quantity,sale_ref,"
              + "source_system,sale_price,currency,occurred_at,recorded_by_user_id,created_at) "
              + "select ?,?,?,?,?,?,?,?,t.at,?,t.at from t",
          input.occurredAt(), id, tenant, lineId, qty, input.saleRef().trim(), source,
          price, currency, actorId);
    } catch (org.springframework.dao.DataAccessException e) {
      throw conflict("这条销售事实已经记过（同一协议行 + saleRef=" + input.saleRef().trim()
          + " 唯一）。重复推送不会重复对账。");
    }
    try {
      sql.execute(
          "update vmi_agreement_line set sold_quantity = sold_quantity + ?,"
              + " version = version + 1, updated_at = ?::timestamptz where tenant_id = ? and id = ?",
          qty, OffsetDateTime.now(), tenant, lineId);
    } catch (org.springframework.dao.DataAccessException e) {
      // 上面的服务层比较只看**本次增量**，累计口径由数据库的 CHECK 兜底
      // （sold_quantity <= supplied_quantity）。这里把数据库的拒绝翻成人话，
      // 否则调用方只会看到一个 500，而真正的原因是货还没送到。
      throw conflict("累计已售超过该行累计供货 " + supplied.toPlainString()
          + "，已拒绝。VMI 货主是供应商：在供应商还没把货送过来之前不能先记销售 —— "
          + "那会凭空生成一笔应付。");
    }
    audit(tenant, agreementId, "SALE_RECORDED",
        "记录售出 " + qty.toPlainString() + "（ref " + input.saleRef().trim() + "）", actorId);
    return sale(tenant, id);
  }

  /** 售出事实列表（真分页；{@code unsettleableOnly=true} 只看还没进对账单的）。 */
  public VmiSalePage sales(UUID agreementId, Boolean unsettleableOnly, int pageNo, int size) {
    var tenant = tenant();
    requireHead(tenant, agreementId);
    int limit = Math.min(MAX_PAGE_SIZE, Math.max(1, size <= 0 ? 20 : size));
    int offset = Math.max(0, pageNo) * limit;
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("l.agreement_id = ?");
    binds.add(agreementId);
    where.add("l.tenant_id = ?");
    binds.add(tenant);
    if (Boolean.TRUE.equals(unsettleableOnly)) {
      where.add("not exists (select 1 from vmi_settlement_line sl"
          + " where sl.tenant_id = s.tenant_id and sl.sale_id = s.id)");
    }
    String sqlWhere = " where " + String.join(" and ", where);
    long total = sql.fetchOne("select count(*) from" + SALE_FROM + sqlWhere, binds.toArray())
        .get(0, Long.class);
    var rows =
        sql.fetch(
            SALE_COLUMNS + " from" + SALE_FROM + sqlWhere
                + " order by s.occurred_at desc, s.id limit ? offset ?",
            append(binds, limit, (long) offset).toArray());
    return new VmiSalePage(
        rows.stream().map(VmiService::saleRow).toList(), total, Math.max(0, pageNo), limit);
  }

  // ============================================================ 结算对账单

  /** 按协议的结算周期算出当前默认对账区间（WEEKLY / SEMI_MONTHLY / MONTHLY 是真算的）。 */
  public VmiSettlementWindow settlementWindow(UUID agreementId) {
    var tenant = tenant();
    var head = requireHead(tenant, agreementId);
    String cycle = head.get("settlement_cycle", String.class);
    var today = sql.fetchOne("select current_date").get(0, LocalDate.class);
    var start = today;
    var end = today;
    switch (cycle) {
      case "WEEKLY" -> {
        start = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        end = start.plusDays(6);
      }
      case "SEMI_MONTHLY" -> {
        if (today.getDayOfMonth() <= 15) {
          start = today.withDayOfMonth(1);
          end = start.withDayOfMonth(15);
        } else {
          start = today.withDayOfMonth(16);
          end = start.withDayOfMonth(today.lengthOfMonth());
        }
      }
      case "MONTHLY" -> {
        start = today.withDayOfMonth(1);
        end = today.withDayOfMonth(today.lengthOfMonth());
      }
      default -> end = today.plusDays(1);
    }
    LocalDate todayValue = today;
    return new VmiSettlementWindow(agreementId, cycle, label(VmiSettlementCycle.values(), cycle),
        start, end, todayValue);
  }

  /**
   * 出 VMI 结算对账单：把区间内**已售但还没进任何对账单**的售出事实收成一张交给 12。
   *
   * <p>区间内没有未对账售出事实时显式拒绝 —— 没有售出就没有应付，
   * 不该靠一张空单来「显得处理过了」。
   */
  @Transactional
  public VmiSettlementDetail createSettlement(
      UUID agreementId, VmiSettlementInput input, UUID actorId) {
    var tenant = tenant();
    var head = requireHead(tenant, agreementId);
    if (!"ACTIVE".equals(head.get("status", String.class))) {
      throw conflict("只有生效中的协议能出对账单；当前状态 " + head.get("status", String.class) + "。");
    }
    if (input.periodStart() == null || input.periodEnd() == null) {
      throw bad("必须指定对账区间 periodStart / periodEnd。");
    }
    if (!input.periodEnd().isAfter(input.periodStart())) {
      throw bad("对账区间结束日必须晚于起始日。");
    }
    var pending =
        sql.fetch(
            "select sa.id, sa.agreement_line_id, sa.sold_quantity, l.settlement_price"
                + " from vmi_sale sa"
                + " join vmi_agreement_line l on l.tenant_id = sa.tenant_id"
                + "   and l.id = sa.agreement_line_id"
                + " where l.agreement_id = ? and sa.tenant_id = ?"
                + " and (sa.occurred_at at time zone 'UTC')::date >= ?::date"
                + " and (sa.occurred_at at time zone 'UTC')::date <= ?::date"
                + " and not exists (select 1 from vmi_settlement_line sl"
                + "                 where sl.tenant_id = sa.tenant_id and sl.sale_id = sa.id)"
                + " order by sa.occurred_at, sa.id limit ?",
            agreementId, tenant, input.periodStart(), input.periodEnd(), MAX_SETTLEMENT_LINES + 1);
    if (pending.isEmpty()) {
      throw conflict("该区间内没有已售出但尚未对账的销售事实，不生成对账单。"
          + "VMI 是售出才与供应商结算：没卖出去就没有应付，出一张空单会让双方对账时多一份噪音。");
    }
    if (pending.size() > MAX_SETTLEMENT_LINES) {
      throw conflict("该区间待对账的售出事实超过单张上限 " + MAX_SETTLEMENT_LINES
          + " 条，请缩小区间后分批出单。");
    }
    UUID id = UUID.randomUUID();
    String currency = head.get("currency", String.class);
    sql.execute(
        "insert into vmi_settlement(id,tenant_id,settlement_code,agreement_id,period_start,"
            + "period_end,currency,status,note,created_by_user_id) "
            + "values(?,?,?,?,?,?,?,'OPEN',?,?)",
        id, tenant, nextCode("VST"), agreementId, input.periodStart(), input.periodEnd(),
        currency, nz(input.note()), actorId);
    for (var r : pending) {
      // 结算价快照取协议行的 numeric(19,4)：协议价后来改了，已出对账单的金额也不能跟着变。
      sql.execute(
          "insert into vmi_settlement_line(id,tenant_id,settlement_id,agreement_line_id,sale_id,"
              + "quantity,unit_price) values(?,?,?,?,?,?,?)",
          UUID.randomUUID(), tenant, id, r.get("agreement_line_id", UUID.class),
          r.get("id", UUID.class), r.get("sold_quantity", BigDecimal.class),
          r.get("settlement_price", BigDecimal.class));
    }
    audit(tenant, agreementId, "SETTLEMENT_CREATED",
        "出 VMI 对账单，收 " + pending.size() + " 条售出事实", actorId);
    return settlementDetail(id);
  }

  /** 对账单列表（真分页；合计由数据库触发器保证 == round(Σ明细金额, 2)）。 */
  public VmiSettlementPage settlements(UUID agreementId, String status, int pageNo, int size) {
    int limit = Math.min(MAX_PAGE_SIZE, Math.max(1, size <= 0 ? 20 : size));
    int offset = Math.max(0, pageNo) * limit;
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("s.tenant_id = ?");
    binds.add(tenant());
    if (agreementId != null) {
      where.add("s.agreement_id = ?");
      binds.add(agreementId);
    }
    if (notBlank(status)) {
      where.add("s.status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    String sqlWhere = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from vmi_settlement s" + sqlWhere, binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            SETTLEMENT_COLUMNS + " from " + SETTLEMENT_FROM + sqlWhere
                + " order by s.created_at desc, s.id limit ? offset ?",
            append(binds, limit, (long) offset).toArray());
    return new VmiSettlementPage(
        rows.stream().map(VmiService::settlement).toList(), total, Math.max(0, pageNo), limit);
  }

  /** 对账单详情：每行都指回触发它的那条售出事实，「应付从哪来」一眼可追。跨租户 → 404。 */
  public VmiSettlementDetail settlementDetail(UUID settlementId) {
    var tenant = tenant();
    var head =
        sql.fetchOne(
            SETTLEMENT_COLUMNS + " from " + SETTLEMENT_FROM + " where s.tenant_id=? and s.id=?",
            tenant, settlementId);
    if (head == null) {
      throw notFound("VMI 对账单", settlementId);
    }
    var lines =
        sql.fetch(
            "select sl.id, sl.agreement_line_id, sl.sale_id, sl.quantity, sl.unit_price, sl.amount,"
                + " k.code as sku_code, k.name as sku_name, sa.sale_ref, sa.occurred_at as sold_at"
                + " from vmi_settlement_line sl"
                + " join vmi_agreement_line l on l.tenant_id = sl.tenant_id"
                + "   and l.id = sl.agreement_line_id"
                + " join sku_ref k on k.tenant_id = l.tenant_id and k.id = l.sku_id"
                + " join vmi_sale sa on sa.tenant_id = sl.tenant_id and sa.id = sl.sale_id"
                + " where sl.tenant_id = ? and sl.settlement_id = ? order by sa.occurred_at, sl.id",
            tenant, settlementId);
    return new VmiSettlementDetail(
        settlement(head),
        lines.stream()
            .map(
                r ->
                    new VmiSettlementLine(
                        r.get("id", UUID.class),
                        r.get("agreement_line_id", UUID.class),
                        r.get("sku_code", String.class),
                        r.get("sku_name", String.class),
                        r.get("sale_id", UUID.class),
                        r.get("sale_ref", String.class),
                        scale6(r.get("quantity", BigDecimal.class)),
                        price(r.get("unit_price", BigDecimal.class)),
                        price(r.get("amount", BigDecimal.class)),
                        r.get("sold_at", OffsetDateTime.class)))
            .toList());
  }

  /** 确认对账单 → 应付正式交 12 结算（写 reference_id 定位键）。确认后单据被数据库冻结。 */
  @Transactional
  public VmiSettlement confirmSettlement(UUID settlementId, VmiNoteInput input, UUID actorId) {
    var tenant = tenant();
    var head =
        sql.fetchOne("select agreement_id, settlement_code, status, line_count from vmi_settlement"
            + " where tenant_id=? and id=?", tenant, settlementId);
    if (head == null) {
      throw notFound("VMI 对账单", settlementId);
    }
    if (!"OPEN".equals(head.get("status", String.class))) {
      throw conflict("对账单当前状态 " + head.get("status", String.class) + "，只有待确认的单据能确认。");
    }
    if (head.get("line_count", Integer.class) == 0) {
      throw conflict("对账单 " + head.get("settlement_code", String.class)
          + " 没有任何明细，不允许确认：没有售出就没有应付。");
    }
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update vmi_settlement set status='CONFIRMED', confirmed_at=?::timestamptz,"
            + " confirmed_by_user_id=?, reference_id=coalesce(nullif(reference_id,''),?),"
            + " source_system='WAREHOUSE', note=coalesce(nullif(?,''),note), updated_at=?::timestamptz"
            + " where tenant_id=? and id=? and status='OPEN'",
        now, actorId, settlementId.toString(), note(input), now, tenant, settlementId);
    audit(tenant, head.get("agreement_id", UUID.class), "SETTLEMENT_CONFIRMED",
        "确认对账单 " + head.get("settlement_code", String.class) + "，应付交 12 结算", actorId);
    return settlementDetail(settlementId).settlement();
  }

  // ============================================================ 审计

  /** 审计时间线（append-only）。 */
  public List<VmiAuditEntry> auditTrail(UUID agreementId, Integer limit) {
    var tenant = tenant();
    requireHead(tenant, agreementId);
    int n = Math.min(MAX_AUDIT_ROWS, Math.max(1, limit == null || limit <= 0 ? 200 : limit));
    return sql.fetch(
            "select id, event_type, detail, actor_user_id, occurred_at from vmi_audit"
                + " where tenant_id = ? and agreement_id = ? order by occurred_at desc, id desc limit ?",
            tenant, agreementId, n)
        .stream()
        .map(
            r ->
                new VmiAuditEntry(
                    r.get("id", UUID.class),
                    r.get("event_type", String.class),
                    r.get("detail", String.class),
                    r.get("actor_user_id", UUID.class),
                    r.get("occurred_at", OffsetDateTime.class)))
        .toList();
  }

  // ============================================================ 内部

  private VmiAgreementDetail detail(UUID agreementId) {
    var tenant = tenant();
    var head =
        sql.fetchOne(AGREEMENT_COLUMNS + " from " + AGREEMENT_FROM + " where a.tenant_id=? and a.id=?",
            tenant, agreementId);
    if (head == null) {
      throw notFound("VMI 协议", agreementId);
    }
    var lines =
        lineRows(tenant, agreementId).stream().map(l -> line(tenant, l, true)).toList();
    return new VmiAgreementDetail(agreement(head), lines, auditTrail(agreementId, 100));
  }

  private List<org.jooq.Record> lineRows(UUID tenant, UUID agreementId) {
    return sql.fetch(LINE_COLUMNS + " from " + LINE_FROM + " where l.tenant_id=? and l.agreement_id=?"
        + " order by k.code, l.id", tenant, agreementId);
  }

  private void insertLine(UUID tenant, UUID agreementId, VmiLineInput line) {
    if (line.skuId() == null || line.warehouseId() == null) {
      throw bad("明细行必须同时指定 skuId 与 warehouseId。");
    }
    if (line.stockOwnerId() == null) {
      throw bad("必须指定明细行的货主 stockOwnerId：VMI 的货权属供应商，"
          + "货主档案是「可售≠自有」这条口径的落点（7.7.1.1 货主与所有权）。");
    }
    BigDecimal safety = scale6(requireNonNegative(line.safetyLevel(), "安全水位 safetyLevel"));
    BigDecimal multiple =
        scale6(
            line.replenishmentMultiple() == null
                ? BigDecimal.ONE
                : requireNonNegative(line.replenishmentMultiple(), "补货倍数 replenishmentMultiple"));
    BigDecimal price = price(line.settlementPrice());
    BigDecimal supplied = scale6(requireNonNegative(line.suppliedQuantity(), "累计供货数量"));
    BigDecimal onHand = scale6(requireNonNegative(line.onHandQuantity(), "在库数量"));
    if (supplied.signum() == 0 && onHand.signum() > 0) {
      throw bad("在库数量 " + onHand.toPlainString()
          + " 不能大于累计供货 0：货主是供应商，在供应商还没供货之前不能有在库量 —— "
          + "那会凭空造出一批不属于任何人的货。");
    }
    try {
      sql.execute(
          "insert into vmi_agreement_line(id,tenant_id,agreement_id,sku_id,warehouse_id,"
              + "stock_owner_id,safety_level,replenishment_multiple,settlement_price,"
              + "stock_owner_type,counts_as_owned_asset,supplied_quantity,on_hand_quantity)"
              + " values(?,?,?,?,?,?,?,?,?,'SUPPLIER',false,?,?)",
          UUID.randomUUID(), tenant, agreementId, line.skuId(), line.warehouseId(),
          line.stockOwnerId(), safety, multiple, price, supplied, onHand);
    } catch (org.springframework.dao.DataAccessException e) {
      throw conflict(dbMessage(e, "货主、SKU 或仓库无效"));
    }
  }

  private void audit(UUID tenant, UUID agreementId, String event, String detail, UUID actorId) {
    sql.execute(
        "insert into vmi_audit(id,tenant_id,agreement_id,event_type,detail,actor_user_id)"
            + " values(?,?,?,?,?,?)",
        UUID.randomUUID(), tenant, agreementId, event, detail, actorId);
  }

  private org.jooq.Record requireHead(UUID tenant, UUID agreementId) {
    var head =
        sql.fetchOne("select id, agreement_code, status, currency, supplier_name, legal_entity_id,"
            + " settlement_cycle, replenishment_mode, signal_target"
            + " from vmi_agreement where tenant_id=? and id=?", tenant, agreementId);
    if (head == null) {
      throw notFound("VMI 协议", agreementId);
    }
    return head;
  }

  private org.jooq.Record requireLine(UUID tenant, UUID agreementId, UUID lineId) {
    var line =
        sql.fetchOne(
            "select id, supplied_quantity from vmi_agreement_line"
                + " where tenant_id=? and agreement_id=? and id=?",
            tenant, agreementId, lineId);
    if (line == null) {
      throw notFound("VMI 协议明细行", lineId);
    }
    return line;
  }

  private org.jooq.Record requireSignal(UUID tenant, UUID signalId) {
    var row =
        sql.fetchOne("select id, agreement_line_id, status, signal_code from vmi_replenishment_signal"
            + " where tenant_id=? and id=?", tenant, signalId);
    if (row == null) {
      throw notFound("VMI 补货信号", signalId);
    }
    return row;
  }

  private UUID agreementIdOfSignal(UUID tenant, UUID signalId) {
    return sql.fetchOne(
            "select l.agreement_id from vmi_replenishment_signal s"
                + " join vmi_agreement_line l on l.tenant_id = s.tenant_id and l.id = s.agreement_line_id"
                + " where s.tenant_id=? and s.id=?", tenant, signalId)
        .get("agreement_id", UUID.class);
  }

  // -------------------------------------------------- 水位与在库量（核心口径）

  /** 一行的当前可售量与它的来源。{@code source == null} 表示两边都查不到，如实报缺口。 */
  private record StockReading(BigDecimal quantity, String source, OffsetDateTime observedAt) {}

  /**
   * 取一行的当前可售量。
   *
   * <p>顺序是硬编码的且不可调换：<b>先查我方账本</b>（7.1.1.3 的 stock_bucket 按货主聚合
   * SELLABLE 可用量 —— 同服务同库，不是跨服务读），账本里没有这个 SKU×仓 的桶时
   * <b>才回落</b>租户登记的观测。VMI 货常常物理上在供应商自己的仓，
   * 那种情况下我方压根没有桶，这是真实经营形态而不是数据缺失。
   */
  private StockReading currentStock(UUID tenant, org.jooq.Record line) {
    UUID skuId = line.get("sku_id", UUID.class);
    UUID warehouseId = line.get("warehouse_id", UUID.class);
    UUID ownerId = line.get("stock_owner_id", UUID.class);
    var row =
        sql.fetchOne(
            "select coalesce(sum(k.available),0) as qty, count(*) as cnt,"
                + " max(k.last_movement_at) as moved_at"
                + " from stock_bucket k"
                + " join inventory_quality_status q on q.tenant_id = k.tenant_id"
                + "   and q.id = k.quality_status_id and q.role = 'SELLABLE'"
                + " where k.tenant_id = ? and k.stock_owner_id = ? and k.sku_id = ? and k.warehouse_id = ?",
            tenant, ownerId, skuId, warehouseId);
    long bucketCount = row.get("cnt", Long.class);
    if (bucketCount > 0) {
      return new StockReading(scale6(row.get("qty", BigDecimal.class)), "WAREHOUSE_BUCKET", null);
    }
    var snap =
        sql.fetchOne(
            "select observed_quantity, stock_source, observed_at from vmi_stock_snapshot"
                + " where tenant_id=? and agreement_line_id=?"
                + " order by observed_at desc, id desc limit 1",
            tenant, line.get("id", UUID.class));
    if (snap != null) {
      return new StockReading(
          scale6(snap.get("observed_quantity", BigDecimal.class)),
          snap.get("stock_source", String.class),
          snap.get("observed_at", OffsetDateTime.class));
    }
    // 两边都查不到：如实报 0 + 无来源，而不是悄悄拿「累计供货 − 已售」凑一个数出来。
    return new StockReading(ZERO, null, null);
  }

  /** 水位判定结果。{@code triggerReason == null} 表示水位健康、无需补货。 */
  private record WaterCalc(
      String status, BigDecimal safetyGap, BigDecimal bufferLine, BigDecimal projected,
      String triggerReason) {}

  /**
   * 水位判定与建议补货量。
   *
   * <p>缓冲线 = 安全水位 ×（1 + 补货倍数）：倍数是「一次补多少份」，所以水位之上还留
   * 同样比例的余量。四档状态分开而不是笼统的「库存低」——
   * 断货、要跌破水位、余量不足三者的紧急度与处置完全不同。
   */
  private WaterCalc waterLevel(org.jooq.Record line, BigDecimal current) {
    BigDecimal safety = scale6(line.get("safety_level", BigDecimal.class));
    BigDecimal multiple = scale6(line.get("replenishment_multiple", BigDecimal.class));
    BigDecimal buffer = scale6(safety.multiply(BigDecimal.ONE.add(multiple)));
    BigDecimal gap = scale6(safety.subtract(current));
    // 建议补货量 = ceil(max(0, 水位 − 当前可售) × 倍数)，向上取整到最小计量单位。
    BigDecimal shortfall = gap.max(BigDecimal.ZERO);
    BigDecimal projected =
        shortfall.multiply(multiple).setScale(6, RoundingMode.CEILING);
    String status;
    if (current.signum() <= 0) {
      status = "STOCKOUT";
    } else if (current.compareTo(safety) < 0) {
      status = "BELOW_SAFETY_LEVEL";
    } else if (current.compareTo(buffer) < 0) {
      status = "BELOW_BUFFER";
    } else {
      status = "HEALTHY";
    }
    // 倍数为 0 时「补到水位」的建议量是 0，但水位不足本身仍要报出来 —— 只是不出信号。
    BigDecimal signalQty = projected.max(new BigDecimal("0.000001"));
    return new WaterCalc(status, gap, buffer, "HEALTHY".equals(status) ? ZERO : signalQty,
        "HEALTHY".equals(status) ? null : status);
  }

  private VmiLine line(UUID tenant, org.jooq.Record l, boolean withSignals) {
    UUID lineId = l.get("id", UUID.class);
    var stock = currentStock(tenant, l);
    var calc = waterLevel(l, stock.quantity());
    BigDecimal sold30d = soldInWindow(tenant, lineId, COVERAGE_WINDOW_DAYS);
    BigDecimal coverageDays = null;
    if (sold30d.signum() > 0) {
      BigDecimal perDay = sold30d.divide(BigDecimal.valueOf(COVERAGE_WINDOW_DAYS), 6, RoundingMode.HALF_UP);
      if (perDay.signum() > 0) {
        // 覆盖率到「天」取整；没有销量时留 null（没销量 ≠ 0 天覆盖）。
        coverageDays = stock.quantity().divide(perDay, 0, RoundingMode.FLOOR).setScale(0, RoundingMode.FLOOR);
      }
    }
    var unsettled =
        sql.fetchOne(
            "select coalesce(sum(sa.sold_quantity),0) as qty,"
                + " coalesce(sum(sa.sold_quantity * li.settlement_price),0) as amt"
                + " from vmi_sale sa"
                + " join vmi_agreement_line li on li.tenant_id = sa.tenant_id"
                + "   and li.id = sa.agreement_line_id"
                + " where li.id = ? and sa.tenant_id = ?"
                + " and not exists (select 1 from vmi_settlement_line sl"
                + "                 where sl.tenant_id = sa.tenant_id and sl.sale_id = sa.id)",
            lineId, tenant);
    var openSignal =
        sql.fetchOne(
            "select signal_code from vmi_replenishment_signal"
                + " where tenant_id=? and agreement_line_id=?"
                + " and status in ('OPEN','ACKNOWLEDGED','DISPATCHED') limit 1",
            tenant, lineId);
    BigDecimal sellableValue =
        price(scale6(stock.quantity()).multiply(price(l.get("settlement_price", BigDecimal.class))));
    String source = stock.source();
    return new VmiLine(
        lineId,
        l.get("agreement_id", UUID.class),
        l.get("sku_id", UUID.class),
        l.get("sku_code", String.class),
        l.get("sku_name", String.class),
        l.get("warehouse_id", UUID.class),
        l.get("warehouse_code", String.class),
        l.get("stock_owner_id", UUID.class),
        l.get("stock_owner_code", String.class),
        l.get("owner_kind", String.class),
        scale6(l.get("safety_level", BigDecimal.class)),
        scale6(l.get("replenishment_multiple", BigDecimal.class)),
        price(l.get("settlement_price", BigDecimal.class)),
        scale6(l.get("supplied_quantity", BigDecimal.class)),
        scale6(l.get("sold_quantity", BigDecimal.class)),
        scale6(l.get("withdrawn_quantity", BigDecimal.class)),
        scale6(l.get("on_hand_quantity", BigDecimal.class)),
        scale6(stock.quantity()),
        source == null ? "MISSING" : source,
        source == null ? "查不到来源" : label(VmiStockSource.values(), source),
        stock.observedAt(),
        calc.status(),
        label(VmiWaterLevelStatus.values(), calc.status()),
        calc.safetyGap(),
        calc.bufferLine(),
        calc.projected(),
        scale6(sold30d),
        coverageDays,
        scale6(unsettled.get("qty", BigDecimal.class)),
        price(unsettled.get("amt", BigDecimal.class)),
        openSignal == null ? null : openSignal.get("signal_code", String.class),
        new VmiOwnershipSplit(
            scale6(stock.quantity()), sellableValue, ZERO, null, false,
            OWNED_ASSET_NOTE, SEPARATION_NOTE));
  }

  private BigDecimal soldInWindow(UUID tenant, UUID lineId, int days) {
    return scale6(
        sql.fetchOne(
                "select coalesce(sum(sold_quantity),0) as qty from vmi_sale"
                    + " where tenant_id=? and agreement_line_id=?"
                    + " and occurred_at >= now() - make_interval(days => ?)",
                tenant, lineId, days)
            .get("qty", BigDecimal.class));
  }

  // ============================================================ SQL 片段

  private static final String AGREEMENT_COLUMNS =
      "select a.id, a.agreement_code, a.supplier_party_id, a.supplier_code, a.supplier_name,"
          + " a.legal_entity_id, a.currency, a.settlement_cycle, a.replenishment_mode,"
          + " a.signal_target, a.start_date, a.end_date, a.status, a.note, a.created_by_user_id,"
          + " a.created_at, a.activated_at, a.closed_at, le.code as legal_entity_code,"
          + " coalesce(agg.line_count,0) as line_count, coalesce(agg.safety,0) as total_safety,"
          + " coalesce(agg.supplied,0) as total_supplied, coalesce(agg.sold,0) as total_sold,"
          + " coalesce(un.qty,0) as unsettleable_qty,"
          + " coalesce(st.amt,0) as settled_amount, coalesce(un.amt,0) as unsettleable_amount";

  private static final String AGREEMENT_FROM =
      " vmi_agreement a"
          + " join legal_entity_ref le on le.tenant_id = a.tenant_id and le.id = a.legal_entity_id"
          + " left join lateral (select count(*) as line_count,"
          + "   coalesce(sum(l.safety_level),0) as safety,"
          + "   coalesce(sum(l.supplied_quantity),0) as supplied,"
          + "   coalesce(sum(l.sold_quantity),0) as sold"
          + "   from vmi_agreement_line l"
          + "   where l.tenant_id = a.tenant_id and l.agreement_id = a.id) agg on true"
          + " left join lateral (select coalesce(sum(s.total_amount),0) as amt"
          + "   from vmi_settlement s where s.tenant_id = a.tenant_id"
          + "   and s.agreement_id = a.id and s.status = 'CONFIRMED') st on true"
          + " left join lateral (select coalesce(sum(sa.sold_quantity * li.settlement_price),0) as amt,"
          + "   coalesce(sum(sa.sold_quantity),0) as qty"
          + "   from vmi_sale sa"
          + "   join vmi_agreement_line li on li.tenant_id = sa.tenant_id"
          + "     and li.id = sa.agreement_line_id"
          + "   where li.agreement_id = a.id and sa.tenant_id = a.tenant_id"
          + "   and not exists (select 1 from vmi_settlement_line sl"
          + "     where sl.tenant_id = sa.tenant_id and sl.sale_id = sa.id)) un on true";

  private static final String LINE_COLUMNS =
      "select l.id, l.agreement_id, l.sku_id, l.warehouse_id, l.stock_owner_id,"
          + " l.safety_level, l.replenishment_multiple, l.settlement_price, l.supplied_quantity,"
          + " l.sold_quantity, l.withdrawn_quantity, l.on_hand_quantity,"
          + " k.code as sku_code, k.name as sku_name, w.code as warehouse_code,"
          + " o.code as stock_owner_code, o.owner_kind";

  private static final String LINE_FROM =
      " vmi_agreement_line l"
          + " join sku_ref k on k.tenant_id = l.tenant_id and k.id = l.sku_id"
          + " join warehouse w on w.tenant_id = l.tenant_id and w.id = l.warehouse_id"
          + " join stock_owner o on o.tenant_id = l.tenant_id and o.id = l.stock_owner_id";

  private static final String SIGNAL_COLUMNS =
      "select s.id, s.signal_code, s.agreement_line_id, s.trigger_reason,"
          + " s.current_sellable_quantity, s.safety_level, s.replenishment_multiple,"
          + " s.suggested_quantity, s.signal_target, s.status, s.source_system, s.reference_id,"
          + " s.created_at, s.acknowledged_at, s.dispatched_at, s.fulfilled_at,"
          + " s.fulfilled_quantity, s.cancelled_at, s.note, s.created_by_user_id,"
          + " k.code as sku_code, k.name as sku_name, w.code as warehouse_code,"
          + " l.agreement_id, a.agreement_code, a.supplier_name";

  private static final String SIGNAL_FROM =
      " vmi_replenishment_signal s"
          + " join vmi_agreement_line l on l.tenant_id = s.tenant_id and l.id = s.agreement_line_id"
          + " join vmi_agreement a on a.tenant_id = l.tenant_id and a.id = l.agreement_id"
          + " join sku_ref k on k.tenant_id = l.tenant_id and k.id = l.sku_id"
          + " join warehouse w on w.tenant_id = l.tenant_id and w.id = l.warehouse_id";

  private static final String SALE_COLUMNS =
      "select s.id, s.agreement_line_id, s.sold_quantity, s.sale_price, s.currency, s.sale_ref,"
          + " s.source_system, s.occurred_at, s.recorded_by_user_id, s.created_at,"
          + " k.code as sku_code, k.name as sku_name, sl.settlement_id, st.settlement_code";

  private static final String SALE_FROM =
      " vmi_sale s"
          + " join vmi_agreement_line l on l.tenant_id = s.tenant_id and l.id = s.agreement_line_id"
          + " join sku_ref k on k.tenant_id = l.tenant_id and k.id = l.sku_id"
          + " left join vmi_settlement_line sl on sl.tenant_id = s.tenant_id and sl.sale_id = s.id"
          + " left join vmi_settlement st on st.tenant_id = sl.tenant_id and st.id = sl.settlement_id";

  private static final String SETTLEMENT_COLUMNS =
      "select s.id, s.settlement_code, s.agreement_id, s.period_start, s.period_end, s.currency,"
          + " s.status, s.line_count, s.total_quantity, s.total_amount, s.source_system,"
          + " s.reference_id, s.triggered_at, s.confirmed_at, s.note, s.created_by_user_id,"
          + " s.created_at, a.agreement_code, a.supplier_name";

  private static final String SETTLEMENT_FROM =
      " vmi_settlement s join vmi_agreement a"
          + " on a.tenant_id = s.tenant_id and a.id = s.agreement_id";

  // ============================================================ 行映射

  private static long lineCount(Long v) {
    return v == null ? 0L : v;
  }

  private static VmiAgreement agreement(org.jooq.Record r) {
    String cycle = r.get("settlement_cycle", String.class);
    String mode = r.get("replenishment_mode", String.class);
    String target = r.get("signal_target", String.class);
    String status = r.get("status", String.class);
    return new VmiAgreement(
        r.get("id", UUID.class),
        r.get("agreement_code", String.class),
        r.get("supplier_party_id", UUID.class),
        r.get("supplier_code", String.class),
        r.get("supplier_name", String.class),
        r.get("legal_entity_id", UUID.class),
        r.get("legal_entity_code", String.class),
        r.get("currency", String.class),
        cycle,
        label(VmiSettlementCycle.values(), cycle),
        mode,
        label(VmiReplenishmentMode.values(), mode),
        target,
        label(VmiSignalTarget.values(), target),
        r.get("start_date", LocalDate.class),
        r.get("end_date", LocalDate.class),
        status,
        label(VmiAgreementStatus.values(), status),
        lineCount(r.get("line_count", Long.class)),
        scale6(r.get("total_safety", BigDecimal.class)),
        scale6(r.get("total_supplied", BigDecimal.class)),
        scale6(r.get("total_sold", BigDecimal.class)),
        scale6(r.get("unsettleable_qty", BigDecimal.class)),
        price(r.get("unsettleable_amount", BigDecimal.class)),
        price(r.get("settled_amount", BigDecimal.class)),
        r.get("note", String.class),
        r.get("created_by_user_id", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("activated_at", OffsetDateTime.class),
        r.get("closed_at", OffsetDateTime.class));
  }

  private VmiSignal signal(UUID tenant, UUID signalId) {
    return signalRow(
        sql.fetchOne(
            SIGNAL_COLUMNS + " from " + SIGNAL_FROM + " where s.tenant_id=? and s.id=?",
            tenant, signalId));
  }

  private static VmiSignal signalRow(org.jooq.Record r) {
    String reason = r.get("trigger_reason", String.class);
    String target = r.get("signal_target", String.class);
    String status = r.get("status", String.class);
    return new VmiSignal(
        r.get("id", UUID.class),
        r.get("signal_code", String.class),
        r.get("agreement_line_id", UUID.class),
        r.get("agreement_id", UUID.class),
        r.get("agreement_code", String.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("warehouse_code", String.class),
        r.get("supplier_name", String.class),
        reason,
        label(VmiSignalTriggerReason.values(), reason),
        scale6(r.get("current_sellable_quantity", BigDecimal.class)),
        scale6(r.get("safety_level", BigDecimal.class)),
        scale6(r.get("replenishment_multiple", BigDecimal.class)),
        scale6(r.get("suggested_quantity", BigDecimal.class)),
        target,
        label(VmiSignalTarget.values(), target),
        status,
        label(VmiSignalStatus.values(), status),
        r.get("source_system", String.class),
        r.get("reference_id", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("acknowledged_at", OffsetDateTime.class),
        r.get("dispatched_at", OffsetDateTime.class),
        r.get("fulfilled_at", OffsetDateTime.class),
        r.get("fulfilled_quantity", BigDecimal.class) == null
            ? null
            : scale6(r.get("fulfilled_quantity", BigDecimal.class)),
        r.get("cancelled_at", OffsetDateTime.class),
        r.get("note", String.class),
        r.get("created_by_user_id", UUID.class));
  }

  private VmiSale sale(UUID tenant, UUID saleId) {
    return saleRow(
        sql.fetchOne(SALE_COLUMNS + " from" + SALE_FROM + " where s.tenant_id=? and s.id=?",
            tenant, saleId));
  }

  private static VmiSale saleRow(org.jooq.Record r) {
    return new VmiSale(
        r.get("id", UUID.class),
        r.get("agreement_line_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        scale6(r.get("sold_quantity", BigDecimal.class)),
        r.get("sale_price", BigDecimal.class) == null
            ? null
            : price(r.get("sale_price", BigDecimal.class)),
        r.get("currency", String.class),
        r.get("sale_ref", String.class),
        r.get("source_system", String.class),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("settlement_id", UUID.class) == null,
        r.get("settlement_id", UUID.class),
        r.get("settlement_code", String.class),
        r.get("recorded_by_user_id", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private VmiSnapshot snapshot(UUID tenant, UUID snapshotId) {
    return snapshotRow(
        sql.fetchOne(
            "select n.id, n.agreement_line_id, n.stock_source, n.source_system, n.observed_quantity,"
                + " n.coverage_days, n.observed_at, n.note, n.recorded_by_user_id, n.created_at,"
                + " k.code as sku_code, k.name as sku_name"
                + " from vmi_stock_snapshot n"
                + " join vmi_agreement_line l on l.tenant_id = n.tenant_id and l.id = n.agreement_line_id"
                + " join sku_ref k on k.tenant_id = l.tenant_id and k.id = l.sku_id"
                + " where n.tenant_id = ? and n.id = ?",
            tenant, snapshotId));
  }

  private static VmiSnapshot snapshotRow(org.jooq.Record r) {
    String source = r.get("stock_source", String.class);
    return new VmiSnapshot(
        r.get("id", UUID.class),
        r.get("agreement_line_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        source,
        label(VmiStockSource.values(), source),
        r.get("source_system", String.class),
        scale6(r.get("observed_quantity", BigDecimal.class)),
        r.get("coverage_days", BigDecimal.class) == null
            ? null
            : scale6(r.get("coverage_days", BigDecimal.class)),
        r.get("observed_at", OffsetDateTime.class),
        r.get("note", String.class),
        r.get("recorded_by_user_id", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static VmiSettlement settlement(org.jooq.Record r) {
    String status = r.get("status", String.class);
    return new VmiSettlement(
        r.get("id", UUID.class),
        r.get("settlement_code", String.class),
        r.get("agreement_id", UUID.class),
        r.get("agreement_code", String.class),
        r.get("supplier_name", String.class),
        r.get("period_start", LocalDate.class),
        r.get("period_end", LocalDate.class),
        r.get("currency", String.class),
        status,
        label(VmiSettlementStatus.values(), status),
        r.get("line_count", Integer.class),
        scale6(r.get("total_quantity", BigDecimal.class)),
        // 表头应付是 HALF_UP 到分的 numeric(24,2)，与明细的 4 位刻意不同。
        r.get("total_amount", BigDecimal.class).setScale(2, RoundingMode.HALF_UP),
        r.get("source_system", String.class),
        r.get("reference_id", String.class),
        r.get("triggered_at", OffsetDateTime.class),
        r.get("confirmed_at", OffsetDateTime.class),
        r.get("note", String.class),
        r.get("created_by_user_id", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  // ============================================================ 工具

  private UUID tenant() {
    return sql.fetchOne("select current_setting('app.current_tenant',true)::uuid as t").get("t", UUID.class);
  }

  /**
   * 单号 = 前缀-当天日期-六位序号。序号与日期分两步取，不在一条 SQL 里拼：
   * 纯文本语句里的 {@code ::text} 这类表达式 jOOQ 解析器吃不准，拆开就没有歧义。
   */
  private String nextCode(String prefix) {
    long seq =
        switch (prefix) {
          case "VMI" -> sql.fetchOne("select nextval('vmi_agreement_code_seq')").get(0, Long.class);
          case "VST" -> sql.fetchOne("select nextval('vmi_settlement_code_seq')").get(0, Long.class);
          default -> sql.fetchOne("select nextval('vmi_signal_code_seq')").get(0, Long.class);
        };
    var today = sql.fetchOne("select current_date").get(0, LocalDate.class);
    return prefix + "-" + today.toString().replace("-", "") + "-" + String.format("%06d", seq);
  }

  private static String label(Enum<?>[] values, String name) {
    for (var v : values) {
      if (v.name().equals(name)) {
        if (v instanceof VmiAgreementStatus s) return s.label();
        if (v instanceof VmiReplenishmentMode m) return m.label();
        if (v instanceof VmiSignalTarget t) return t.label();
        if (v instanceof VmiSettlementCycle c) return c.label();
        if (v instanceof VmiSignalTriggerReason r) return r.label();
        if (v instanceof VmiSignalStatus s) return s.label();
        if (v instanceof VmiSettlementStatus s) return s.label();
        if (v instanceof VmiWaterLevelStatus w) return w.label();
        if (v instanceof VmiStockSource s) return s.label();
      }
    }
    return name;
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    binds.addAll(List.of(more));
    return binds;
  }

  private static boolean notBlank(String v) {
    return v != null && !v.isBlank();
  }

  private static String nz(String v) {
    return v == null ? "" : v;
  }

  private static String note(VmiNoteInput input) {
    return input == null || input.note() == null ? "" : input.note().trim();
  }

  /** {@link VmiSignalFulfilInput} 也带 note，口径与 {@link VmiNoteInput} 完全一致。 */
  private static String note(VmiSignalFulfilInput input) {
    return input == null || input.note() == null ? "" : input.note().trim();
  }

  private static BigDecimal scale6(BigDecimal v) {
    return v == null ? ZERO : v.setScale(6, RoundingMode.HALF_UP);
  }

  /** numeric(19,4) 口径：结算价与金额列都是 4 位，读回来也必须是 4 位。 */
  private static BigDecimal price(BigDecimal v) {
    return v == null ? ZERO_PRICE : v.setScale(4, RoundingMode.HALF_UP);
  }

  private static BigDecimal requirePositive(BigDecimal v, String what) {
    if (v == null || v.signum() <= 0) {
      throw bad(what + "必须大于 0。");
    }
    return scale6(v);
  }

  private static BigDecimal requireNonNegative(BigDecimal v, String what) {
    if (v == null || v.signum() < 0) {
      throw bad(what + "不能为空且不能为负。");
    }
    return v;
  }

  private static void requireText(String v, String what) {
    if (!notBlank(v)) {
      throw bad(what + "不能为空。");
    }
  }

  private static void requireRef(UUID v, String what) {
    if (v == null) {
      throw bad(what + "不能为空。");
    }
  }

  private static String requireEnum(String value, Class<? extends Enum<?>> type, String what) {
    if (notBlank(value)) {
      for (var c : type.getEnumConstants()) {
        if (c.name().equals(value.trim().toUpperCase(Locale.ROOT))) return c.name();
      }
    }
    throw bad(what + "只能是：" + List.of(type.getEnumConstants()) + "，收到：" + value);
  }

  private static String requireCurrency(String value) {
    String v = notBlank(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    if (!v.matches("^[A-Z]{3}$")) {
      throw bad("币种必须是三位大写字母（如 USD / CNY），收到：" + value
          + "。结算价没有币种就是一个无意义的数字。");
    }
    return v;
  }

  /** 把数据库约束的原始报错翻成人话（SQLSTATE 23503/23514/23505）。 */
  private static String dbMessage(Exception e, String fallback) {
    for (Throwable c = e; c != null; c = c.getCause()) {
      var m = c.getMessage();
      if (m == null) continue;
      if (m.contains("vmi_line_owner_not_owned")) return "货主不允许：VMI 的货主不能是「计入自有资产」的那一类。";
      if (m.contains("counts_as_owned_asset")) return "货主不允许：VMI 货属供应商，不能计入自有资产。";
      if (m.contains("vmi_agreement_line_agreement_line_id_key")
          || m.contains("duplicate key")) {
        return "同一协议下该 SKU 与仓库已存在一行（水位与建议补货量只应有一个口径）。";
      }
      if (m.contains("stock_owner")) return "货主档案不存在或已停用。";
      if (m.contains("sku_ref") || m.contains("warehouse")) return "SKU 或仓库在本租户下不存在。";
    }
    return fallback;
  }

  private static ResponseStatusException notFound(String what, UUID id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what + "不存在或不属于当前租户：" + id);
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}