package com.starcon.warehouse;

import static com.starcon.warehouse.ConsignmentStockModels.*;

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
 * 7.7.2.2 平台托管与全托管备货：备货单 → 平台仓收发回执 → 托管备货台账 → 平台结算回执。
 *
 * <p>蓝图原话：「处理平台托管（全托管）下的"备货"语义：发给平台的货进入平台仓但**所有权与结算规则随模式变化**，需与自有仓库存分账；支持备货单、平台仓收发对账、按件/按日结算口径标记。」 输入「店铺经营模式（2.3.2.1）；备货单与发运数据；平台仓库存与结算回执。」 输出「托管备货台账（备货量/平台在库/平台已售/结算未结）；与自有库存的对账结果；结算口径标记供 12 使用。」
 *
 * <p><b>本模块唯一不可让步的口径：备货 ≠ 自有库存。</b>蓝图把它写成加粗句：「全托管下"备货"不等于"自有库存"，平台仓在库数是平台回执的观测值，不得并入自有库存余额」。它写在 V244 的三道数据库不变量里，不是这里的自觉：
 *
 * <ol>
 *   <li>{@code consignment_stock_order_line.counts_as_owned_asset} 恒 false（CHECK 写死）；
 *   <li>{@code inventory_entry} 上的触发器 {@code guard_consignment_stock_entry_not_added}
 *       —— 凡 {@code ref_type='CONSIGNMENT_STOCK'} 的库存分录，{@code on_hand_delta} 只许 ≤ 0
 *       （货从我方仓出库是负数，本就该有）、{@code reserved_delta} 必须 = 0。
 *       **平台在库数在写入那一刻就进不了自有库存账本**，绕过服务层直连 SQL 也一样；
 *   <li>明细表里**没有第二个数量来源**：平台在库 / 平台已售 / 结算未结全部由平台回执推导，不落库。
 * </ol>
 *
 * <p><b>四个口径分得清</b>：备货量（我方发出）、平台在库（平台入库回执 − 出库回执）、平台已售（出库回执）、结算未结（已售但尚未按平台口径结算）。「备货量 − 平台入库」是**在途未确认**，不是差异损失；差异本身另有一个有符号的量与四种口径之一。
 *
 * <p><b>经营模式（2.3.2.1）只读</b>：属 channel 服务，本模块经 {@link ChannelShopModePort} 读 {@code GET /channel/shops/{id}}，把当时取值快照到备货单上；不建影子表，要查店铺现在的模式仍然读 channel。快照是「建单当时的事实」，不是第二个权威。上游不可达 → 503；上游正常但没有这家店 → {@code known=false}。
 *
 * <p><b>备货预报（10.4.2.2）在 channel，禁止跨服务 SQL</b>：经 {@link ChannelForecastPort} 只读取数，读到的 {@code forecastId} 只是定位键，本库不建外键、不建影子表。上游不可达 → 503；上游正常但没有这张预报 → {@code known=false}（无记录 ≠ 没有备货需求）。
 *
 * <p><b>结算口径（按件 / 按日）真的算出不同的结果</b>：按件 = 已售件数 − 已结算件数；按日 = 落在尚未结算的日期区间内的售出件数。蓝图那句「结算按平台规则（可能按件或按日）而非我方发货确认」因此可执行 —— 发货只能推出备货量，推不出已售多少件。
 *
 * <p><b>时间绑定纪律</b>：写入 {@code timestamptz} 列一律用 {@link OffsetDateTime} 绑定，insert 语句把 {@code ?::timestamptz} 放在最前面的 {@code t} CTE 里（cast 不落在绑定序列末位）；不用 {@code Timestamp.valueOf} —— 后者按 JVM 本地时区解释裸本地时间，在东八区会静默偏 −28800s。
 */
@Service
public class ConsignmentStockService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 单张备货单最多收多少明细行。 */
  public static final int MAX_LINES = 500;

  /** 审计时间线单次返回上限。 */
  static final int MAX_AUDIT_ROWS = 500;

  /** numeric(19,6) 口径下的零。 */
  private static final BigDecimal ZERO = new BigDecimal("0.000000");

  /** numeric(19,4) 口径下的零（金额）。 */
  private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.0000");

  /** 本模块在自有库存账本里的 ref_type（用于分账校验与 DB 红线）。 */
  static final String REF_TYPE = "CONSIGNMENT_STOCK";

  /** 口径原文：四个口径各自是什么。 */
  private static final String MEASURE_NOTE =
      "备货量＝我方发给平台的量；平台在库＝平台入库回执累计 − 平台出库回执累计；"
          + "平台已售＝平台出库回执累计；结算未结＝已售但尚未按平台口径结算的量。"
          + "备货量与平台在库之差是**在途未确认**，不是差异损失 —— 四个数不合并成一个「库存量」。";

  /** 口径原文：备货 ≠ 自有库存。 */
  private static final String SEPARATION_NOTE =
      "全托管下「备货」不等于「自有库存」：平台仓在库数是平台回执的观测值，不得并入自有库存余额。"
          + "本模块**从不写** inventory_balance / stock_bucket，自有库存那一侧只读；"
          + "并且 V244 在 inventory_entry 上挂了触发器，平台托管的单据只许从自有库存**出库**（负数），"
          + "不许把平台在库数加进我方账本或占我方预占 —— 绕开服务层直连 SQL 也一样被挡。";

  /** 口径原文：结算按平台规则。 */
  private static final String SETTLEMENT_NOTE =
      "结算按平台规则（可能按件或按日）而非我方发货确认：发货只能推出备货量，推不出已售多少件，"
          + "所以结算未结只由平台出库回执与平台结算回执算出。按件口径下未结＝已售件数 − 已结算件数；"
          + "按日口径下未结＝落在尚未结算的日期区间内的售出件数。口径离开草稿态即冻结。";

  /** 口径原文：经营模式只读。 */
  private static final String OPERATING_MODE_NOTE =
      "店铺经营模式属 channel（2.3.2.1），本模块**只读**：建单时经授权只读接口取一次并快照到备货单，"
          + "那是「建单当时的事实」；要查店铺现在的模式仍然读 channel，本服务不建影子表。"
          + "经营模式在本模块里真的起作用：自发货模式下不存在「发给平台仓备货」这回事，直接被拒。";

  /** 口径原文：备货预报在 channel。 */
  private static final String FORECAST_NOTE =
      "备货预报（10.4.2.2）在 channel 服务，本模块经授权只读接口取数（跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
          + "读到的预报单标识只是定位键，本库不建外键、不建影子表。上游不可达一律 503 报「读不到」，"
          + "上游正常但没有这张预报时如实报「无记录」—— 无记录 ≠ 没有备货需求。";

  /** 口径原文：分账校验查的是什么。 */
  private static final String SEGREGATION_CHECK_NOTE =
      "分账校验查的是**自有库存账本本身**：把 ref_type='CONSIGNMENT_STOCK' 的库存分录里"
          + "正数在手增量累加（那正是「平台在库被并进自有库存余额」的痕迹）。正常恒为 0；"
          + "不为 0 说明有绕过服务层的写入把平台货加进了我方账本 —— 那正是蓝图加粗句禁止的事。";

  /** 未接入的上游/下游环节（capabilities 里显式报出，而不是从 404 或空数组里让人猜）。 */
  private static final List<PlatformConsignmentUnavailableCapability> UNAVAILABLE =
      List.of(
          new PlatformConsignmentUnavailableCapability(
              "12.3.1 结算与应付",
              "12.3.1",
              "finance",
              "尚未交付。本模块只出**结算口径标记**与结算未结量（这是 12 结算要的数据），"
                  + "不替 12 记应付、不建跨服务 FK、不查对方的表。",
              "12 交付后按本模块的结算口径标记与未结量读回，逐笔对账"),
          new PlatformConsignmentUnavailableCapability(
              "13.4.1.1 多模式利润报表",
              "13.4.1.1",
              "analytics",
              "尚未交付。本模块按经营模式（2.3.2.1）分组给出备货量/平台在库/平台已售/结算未结，"
                  + "但不出利润数字。",
              "13 交付后按经营模式分组读本模块的托管备货台账"),
          new PlatformConsignmentUnavailableCapability(
              "平台仓库存自动采集（平台侧收发回执自动接入）",
              "10.4.2.2",
              "channel",
              "平台仓的收发回执目前靠人工/运营登记（source_system=MANUAL）；"
                  + "平台侧自动采集能力尚未交付，所以平台在库数是**观测值**，不是我方推算值。",
              "平台侧采集交付后改由平台推送回执（source_system=PLATFORM_FEED），本表即为留痕"),
          new PlatformConsignmentUnavailableCapability(
              "9.3.1.1 订单履约（平台已售事实的自动推送）",
              "9.3.1.1",
              "trade",
              "尚未交付。平台已售目前只能从平台出库回执登记得到；"
                  + "订单侧交付后由它推平台回执，届时本模块仍是那份事实的唯一出处。",
              "9 交付后由订单侧推平台收发回执，本表即为观测留痕"));

  /**
   * 逐行口径与合计的公共 CTE 前缀。
   *
   * <p>五个 CTE 各吃一个 {@code ?}（都是 tenant_id），顺序固定：l → rec → st → unsettled_day → own。
   * 之所以把「按日口径下的未结量」单列一个 CTE：按件口径与按日口径的未结量**算法不同**，
   * 两套都算出来再按备货单的口径挑一个，避免把两种口径揉成一个数。
   */
  private static final String MEASURE_CTES =
      "with l as ("
          + " select id as line_id, order_id, sku_id, sku_code, sku_name, stocked_quantity, unit, note"
          + " from consignment_stock_order_line where tenant_id = ?"
          + "), rec as ("
          + " select order_line_id,"
          + " coalesce(sum(case when receipt_type='INBOUND' then quantity else 0 end),0) as received,"
          + " coalesce(sum(case when receipt_type='OUTBOUND' then quantity else 0 end),0) as sold"
          + " from consignment_stock_receipt where tenant_id = ? group by order_line_id"
          + "), st as ("
          + " select order_line_id, coalesce(sum(settled_quantity),0) as settled"
          + " from consignment_stock_settlement_receipt where tenant_id = ? group by order_line_id"
          + "), unsettled_day as ("
          + " select r.order_line_id, coalesce(sum(r.quantity),0) as unsettled"
          + " from consignment_stock_receipt r"
          + " where r.tenant_id = ? and r.receipt_type='OUTBOUND'"
          + " and not exists (select 1 from consignment_stock_settlement_receipt s"
          + "                where s.tenant_id = r.tenant_id and s.order_line_id = r.order_line_id"
          + "                  and s.settlement_basis = 'PER_DAY'"
          + "                  and cast(r.occurred_at at time zone 'UTC' as date)"
          + "                      between s.period_start and s.period_end)"
          + " group by r.order_line_id"
          + "), own as ("
          + " select sku_id, coalesce(sum(on_hand),0) as oh, coalesce(sum(reserved),0) as rv"
          + " from inventory_balance where tenant_id = ? group by sku_id"
          + "), lm as ("
          + " select l.order_id, l.line_id, l.sku_id, l.sku_code, l.sku_name,"
          + "        l.stocked_quantity, l.unit, l.note,"
          + "        coalesce(rec.received,0) as received,"
          + "        coalesce(rec.sold,0) as sold,"
          + "        coalesce(st.settled,0) as settled,"
          + "        coalesce(own.oh,0) as own_on_hand,"
          + "        coalesce(own.rv,0) as own_reserved,"
          + "        coalesce(ud.unsettled,0) as unsettled_day"
          + " from l"
          + " left join rec on rec.order_line_id = l.line_id"
          + " left join st on st.order_line_id = l.line_id"
          + " left join unsettled_day ud on ud.order_line_id = l.line_id"
          + " left join own on own.sku_id = l.sku_id"
          + ")";

  /** 备货单列表/详情的公共投影。 */
  private static final String ORDER_PROJECTION =
      " o.id, o.order_code, o.shop_id, o.shop_code, o.shop_name, o.operating_mode,"
          + " o.platform_warehouse_code, o.platform_warehouse_name, o.legal_entity_id, o.currency,"
          + " o.settlement_basis, o.status, o.forecast_id, o.forecast_no, o.note,"
          + " o.created_by_user_id, o.created_at, o.shipped_at, o.platform_received_at, o.closed_at,"
          + " coalesce(agg.line_count,0) as line_count,"
          + " coalesce(agg.total_stocked,0) as total_stocked_quantity,"
          + " coalesce(agg.total_received,0) as platform_received_quantity,"
          + " coalesce(agg.total_sold,0) as platform_sold_quantity,"
          + " coalesce(agg.total_received,0) - coalesce(agg.total_sold,0) as platform_on_hand_quantity,"
          + " coalesce(agg.total_unsettled,0) as unsettled_quantity,"
          + " coalesce(agg.total_in_transit,0) as total_in_transit_quantity,"
          + " coalesce(agg.total_own_on_hand,0) as total_own_on_hand_quantity,"
          + " case when coalesce(agg.total_sold,0)=0 and coalesce(agg.total_settled,0)=0"
          + "      then 'NOT_STARTED'"
          + "      when coalesce(agg.total_unsettled,0) > 0 then 'PENDING'"
          + "      else 'SETTLED' end as settlement_state";

  /** 备货单列表/详情的公共聚合 CTE（在 MEASURE_CTES 之后追加）。 */
  private static final String ORDER_AGG =
      ", agg as ("
          + " select lm.order_id, count(*) as line_count,"
          + " sum(lm.stocked_quantity) as total_stocked,"
          + " sum(lm.received) as total_received,"
          + " sum(lm.sold) as total_sold,"
          + " sum(lm.settled) as total_settled,"
          + " sum(case when o.settlement_basis = 'PER_DAY' then lm.unsettled_day"
          + "          else greatest(lm.sold - lm.settled, 0) end) as total_unsettled,"
          + " sum(greatest(lm.stocked_quantity - lm.received, 0)) as total_in_transit,"
          + " sum(lm.own_on_hand) as total_own_on_hand"
          + " from lm join consignment_stock_order o on o.tenant_id = ? and o.id = lm.order_id"
          + " group by lm.order_id"
          + ")";

  private final DSLContext sql;
  private final ChannelShopModePort shopModes;
  private final ChannelForecastPort forecasts;

  public ConsignmentStockService(
      DSLContext sql, ChannelShopModePort shopModes, ChannelForecastPort forecasts) {
    this.sql = sql;
    this.shopModes = shopModes;
    this.forecasts = forecasts;
  }

  // ============================================================ 能力

  /** 平台托管备货能力说明：前端据此渲染选项与提示，并把未接入的环节讲清楚。 */
  public PlatformConsignmentCapabilities capabilities() {
    var modes =
        List.of(PlatformConsignmentOperatingMode.values()).stream()
            .map(m -> new PlatformConsignmentOption(m.name(), m.label(), m.description()))
            .toList();
    var bases =
        List.of(PlatformConsignmentSettlementBasis.values()).stream()
            .map(b -> new PlatformConsignmentOption(b.name(), b.label(), b.description()))
            .toList();
    var receiptTypes =
        List.of(PlatformConsignmentReceiptType.values()).stream()
            .map(t -> new PlatformConsignmentOption(t.name(), t.label(), t.description()))
            .toList();
    var diffBases =
        List.of(PlatformConsignmentDifferenceBasis.values()).stream()
            .map(d -> new PlatformConsignmentOption(d.name(), d.label(), d.description()))
            .toList();
    var statuses =
        List.of(PlatformConsignmentOrderStatus.values()).stream()
            .map(s -> new PlatformConsignmentOption(s.name(), s.label(), ""))
            .toList();
    var unavailable = new ArrayList<>(UNAVAILABLE);
    return new PlatformConsignmentCapabilities(
        modes,
        bases,
        receiptTypes,
        diffBases,
        statuses,
        List.of(
            PlatformConsignmentOperatingMode.FULL_CONSIGNMENT.name(),
            PlatformConsignmentOperatingMode.SEMI_CONSIGNMENT.name()),
        unavailable,
        MEASURE_NOTE,
        SEPARATION_NOTE,
        SETTLEMENT_NOTE,
        OPERATING_MODE_NOTE,
        FORECAST_NOTE,
        SEGREGATION_CHECK_NOTE);
  }

  /** 店铺经营模式读出口（2.3.2.1，只读）。上游读不到 → 503；没有这家店 → known=false。 */
  public PlatformConsignmentShopMode shopMode(UUID shopId, String bearer) {
    var mode = shopModes.operatingMode(shopId, bearer);
    return new PlatformConsignmentShopMode(
        mode.shopId(),
        mode.shopCode(),
        mode.shopName(),
        mode.known() ? mode.operatingMode() : null,
        mode.known() ? mode.operatingModeLabel() : null,
        mode.known(),
        mode.supportsConsignmentStock(),
        mode.note());
  }

  /** 备货预报预填预览（10.4.2.2，只读）。 */
  public PlatformConsignmentForecastPreview forecastPreview(UUID forecastId, String bearer) {
    var view = forecasts.forecast(forecastId, bearer);
    if (!view.known()) {
      return new PlatformConsignmentForecastPreview(
          forecastId, null, false, null, null, null, List.of(), view.note());
    }
    var mapped = new ArrayList<PlatformConsignmentForecastLine>();
    BigDecimal total = ZERO;
    for (var line : view.lines()) {
      total = total.add(qty(line.quantity()));
      var sku = findSkuByCode(line.skuCode());
      mapped.add(
          new PlatformConsignmentForecastLine(
              line.skuCode(),
              qty(line.quantity()),
              line.unit() == null || line.unit().isBlank() ? "PCS" : line.unit(),
              sku == null ? null : sku.get("id", UUID.class),
              sku == null ? null : sku.get("name", String.class),
              sku != null));
    }
    return new PlatformConsignmentForecastPreview(
        view.forecastId(),
        view.forecastNo(),
        true,
        view.status(),
        view.expectedArrivalAt() == null ? null : view.expectedArrivalAt().toString(),
        view.totalQuantity() == null ? total : view.totalQuantity(),
        mapped,
        view.note());
  }

  // ============================================================ 备货单

  /**
   * 建平台托管备货单。
   *
   * <p>建单前**必须**先经只读端口核对店铺经营模式（2.3.2.1）：自发货模式下不存在「发给平台仓备货」这回事，直接被拒。
   */
  @Transactional
  public PlatformConsignmentOrderDetail createOrder(
      PlatformConsignmentOrderInput input, String bearer, UUID actorId) {
    var tenant = tenant();
    if (input.shopId() == null) {
      throw bad("必须指定店铺 shopId：经营模式（2.3.2.1）决定这份备货单能不能建。");
    }
    var mode = shopModes.operatingMode(input.shopId(), bearer);
    if (!mode.known()) {
      throw notFoundByMessage(
          "读不到这家店铺的经营模式（2.3.2.1）："
              + mode.note()
              + "。经营模式未知时不能建平台托管备货单 —— 把自发货店铺当成全托管会建出一张根本不存在的账。");
    }
    if (!mode.supportsConsignmentStock()) {
      throw bad(
          "店铺的经营模式是「"
              + mode.operatingModeLabel()
              + "」，不能建平台托管备货单："
              + describeMode(mode.operatingMode())
              + "。平台托管备货只适用于全托管与半托管。");
    }
    String basis =
        requireEnum(input.settlementBasis(), PlatformConsignmentSettlementBasis.class, "结算口径");
    requireText(input.platformWarehouseCode(), "平台仓编码 platformWarehouseCode");
    requireRef(input.legalEntityId(), "法人 legalEntityId");
    String currency = requireCurrency(input.currency());

    // 备货行：显式给的优先；没给就按 10.4.2.2 备货预报预填。
    var lines = new ArrayList<PlatformConsignmentLineInput>();
    UUID forecastId = input.forecastId();
    String forecastNo = "";
    if (input.lines() == null || input.lines().isEmpty()) {
      if (forecastId == null) {
        throw bad("备货单至少要有一行明细：可以显式给 SKU，也可以给 forecastId 按 10.4.2.2 备货预报预填。");
      }
      var view = forecasts.forecast(forecastId, bearer);
      if (!view.known()) {
        throw notFoundByMessage(
            "读不到这张备货预报（10.4.2.2）：" + view.note() + "。无记录 ≠ 没有备货需求，别用空单糊过去。");
      }
      forecastNo = view.forecastNo() == null ? "" : view.forecastNo();
      var unmapped = new ArrayList<String>();
      for (var line : view.lines()) {
        var sku = findSkuByCode(line.skuCode());
        if (sku == null) {
          unmapped.add(line.skuCode());
          continue;
        }
        lines.add(
            new PlatformConsignmentLineInput(
                sku.get("id", UUID.class),
                qty(line.quantity()),
                line.unit(),
                "由备货预报 " + forecastNo + " 预填"));
      }
      if (!unmapped.isEmpty()) {
        throw bad(
            "备货预报里的这些 SKU 在本服务的 SKU 登记里找不到："
                + String.join("、", unmapped)
                + "。本服务只登记 catalog 的 SKU（sku_ref），先登记再来建单 —— 凭空造一个 SKU 会让备货量挂在一个不存在的商品上。");
      }
      if (lines.isEmpty()) {
        throw bad("备货预报 " + forecastNo + " 没有明细行，无法据此建备货单。");
      }
    } else {
      lines.addAll(input.lines());
      if (forecastId != null) {
        var view = forecasts.forecast(forecastId, bearer);
        if (!view.known()) {
          throw notFoundByMessage("读不到这张备货预报（10.4.2.2）：" + view.note());
        }
        forecastNo = view.forecastNo() == null ? "" : view.forecastNo();
      }
    }
    if (lines.size() > MAX_LINES) {
      throw bad("单张备货单的明细行数超过上限 " + MAX_LINES + " 行，请分批建立。");
    }

    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into consignment_stock_order(id,tenant_id,order_code,shop_id,shop_code,shop_name,"
            + "operating_mode,platform_warehouse_code,platform_warehouse_name,legal_entity_id,currency,"
            + "settlement_basis,status,forecast_id,forecast_no,note,created_by_user_id) "
            + "values(?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?,?,?,?)",
        id,
        tenant,
        nextCode(),
        input.shopId(),
        nz(mode.shopCode()),
        nz(mode.shopName()),
        mode.operatingMode(),
        input.platformWarehouseCode().trim(),
        nz(input.platformWarehouseName()),
        input.legalEntityId(),
        currency,
        basis,
        forecastId,
        forecastNo,
        nz(input.note()),
        actorId);

    for (var line : lines) {
      insertLine(tenant, id, line);
    }
    audit(tenant, id, "ORDER_CREATED", "建立平台托管备货单 " + lines.size() + " 行", actorId);
    return detail(id);
  }

  /** 备货单列表（真分页；合计全部由明细与回执聚合，不另存列）。 */
  public PlatformConsignmentOrderPage orders(
      String status, UUID shopId, String basis, String keyword, int pageNo, int size) {
    int limit = Math.min(MAX_PAGE_SIZE, Math.max(1, size <= 0 ? 20 : size));
    int offset = Math.max(0, pageNo) * limit;
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("o.tenant_id = ?");
    binds.add(tenant());
    if (notBlank(status)) {
      where.add("o.status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (shopId != null) {
      where.add("o.shop_id = ?");
      binds.add(shopId);
    }
    if (notBlank(basis)) {
      where.add("o.settlement_basis = ?");
      binds.add(basis.trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(keyword)) {
      where.add(
          "(o.order_code ilike ? or o.shop_code ilike ? or o.shop_name ilike ?"
              + " or o.platform_warehouse_code ilike ? or o.forecast_no ilike ?)");
      String k = "%" + keyword.trim() + "%";
      binds.add(k);
      binds.add(k);
      binds.add(k);
      binds.add(k);
      binds.add(k);
    }
    String sqlWhere = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from consignment_stock_order o" + sqlWhere, binds.toArray())
            .get(0, Long.class);

    var tenant = tenant();
    var rows =
        sql.fetch(
            MEASURE_CTES
                + ORDER_AGG
                + " select "
                + ORDER_PROJECTION
                + " from consignment_stock_order o left join agg on agg.order_id = o.id"
                + sqlWhere
                + " order by o.created_at desc, o.id limit ? offset ?",
            prepend(tenant, binds, limit, (long) offset).toArray());
    return new PlatformConsignmentOrderPage(
        rows.stream().map(ConsignmentStockService::order).toList(), total, Math.max(0, pageNo), limit);
  }

  /** 备货单详情 = 头部 + 明细 + 台账 + 对账 + 审计。跨租户 → 404。 */
  public PlatformConsignmentOrderDetail orderDetail(UUID orderId) {
    return detail(orderId);
  }

  /** 给草稿备货单补一个 SKU。 */
  @Transactional
  public PlatformConsignmentOrderDetail addLine(
      UUID orderId, PlatformConsignmentAddLineInput input, UUID actorId) {
    var tenant = tenant();
    var head = requireOrder(tenant, orderId);
    if (!"DRAFT".equals(head.get("status", String.class))) {
      throw conflict(
          "只有草稿备货单能加行；当前状态 "
              + head.get("status", String.class)
              + "。备货量发出后就不能改了 —— 改了台账上的备货量、平台在库量与结算未结量会全部对不上。");
    }
    insertLine(
        tenant,
        orderId,
        new PlatformConsignmentLineInput(
            input.skuId(), input.stockedQuantity(), input.unit(), input.note()));
    audit(tenant, orderId, "LINE_ADDED", "新增备货明细行", actorId);
    return detail(orderId);
  }

  /** 发货：DRAFT → SHIPPED。备货量在此刻生效（成为台账里的「备货量」口径）。 */
  @Transactional
  public PlatformConsignmentOrder ship(UUID orderId, PlatformConsignmentNoteInput input, UUID actorId) {
    return transition(orderId, "DRAFT", "SHIPPED", "ORDER_SHIPPED", input, actorId, "shipped_at");
  }

  /**
   * 平台确认收货：SHIPPED → RECEIVED。
   *
   * <p>必须**先有平台入库回执**才允许确认收货：平台入库回执是「平台收到了」这件事的唯一凭据，
   * 没有它就确认收货，等于用我方的发货去替平台回执背书。
   */
  @Transactional
  public PlatformConsignmentOrder confirmReceipt(
      UUID orderId, PlatformConsignmentNoteInput input, UUID actorId) {
    var tenant = tenant();
    var head = requireOrder(tenant, orderId);
    if (!"SHIPPED".equals(head.get("status", String.class))) {
      throw conflict(
          "只有已发出的备货单能确认平台收货；当前状态 " + head.get("status", String.class) + "。");
    }
    Long inbound =
        sql.fetchOne(
                "select count(*) from consignment_stock_receipt where tenant_id = ? and order_id = ?"
                    + " and receipt_type='INBOUND'",
                tenant,
                orderId)
            .get(0, Long.class);
    if (inbound == null || inbound == 0) {
      throw conflict(
          "还没有任何平台入库回执，不能确认平台已收货：平台仓在库数是**平台回执的观测值**，"
              + "我方发货确认不了平台收没收到。先登记平台入库回执。");
    }
    return transition(orderId, "SHIPPED", "RECEIVED", "PLATFORM_RECEIVED", input, actorId,
        "platform_received_at");
  }

  /** 关闭备货单：RECEIVED → CLOSED。结算未结不为 0 时不允许关闭。 */
  @Transactional
  public PlatformConsignmentOrder close(UUID orderId, PlatformConsignmentNoteInput input, UUID actorId) {
    var tenant = tenant();
    var head = requireOrder(tenant, orderId);
    if (!"RECEIVED".equals(head.get("status", String.class))) {
      throw conflict(
          "只有平台已确认收货的备货单能关闭；当前状态 " + head.get("status", String.class) + "。");
    }
    var unsettled = unsettledOf(tenant, orderId);
    if (unsettled.signum() > 0) {
      throw conflict(
          "结算未结还有 "
              + unsettled.stripTrailingZeros().toPlainString()
              + "，不能关闭备货单：结清之前关掉单据，这笔未结就没有单据可追了。"
              + "先登记平台结算回执。");
    }
    return transition(orderId, "RECEIVED", "CLOSED", "ORDER_CLOSED", input, actorId, "closed_at");
  }

  /** 作废备货单：DRAFT / SHIPPED / RECEIVED → CANCELLED。终态不可复活。 */
  @Transactional
  public PlatformConsignmentOrder cancel(
      UUID orderId, PlatformConsignmentNoteInput input, UUID actorId) {
    var tenant = tenant();
    var head = requireOrder(tenant, orderId);
    String status = head.get("status", String.class);
    if (!List.of("DRAFT", "SHIPPED", "RECEIVED").contains(status)) {
      throw conflict("当前状态 " + status + " 不能作废（终态不可复活）。");
    }
    return transition(orderId, status, "CANCELLED", "ORDER_CANCELLED", input, actorId, "cancelled_at");
  }

  // ============================================================ 平台仓回执

  /**
   * 登记平台仓收发回执。
   *
   * <p>平台在库与平台已售两个口径**只**来自这张表：平台回执是观测值，我方发货推不出平台收没收到、更推不出卖了多少。
   */
  @Transactional
  public PlatformConsignmentReceipt recordReceipt(
      UUID orderId, PlatformConsignmentReceiptInput input, UUID actorId) {
    var tenant = tenant();
    var head = requireOrder(tenant, orderId);
    String status = head.get("status", String.class);
    if (!List.of("SHIPPED", "RECEIVED").contains(status)) {
      throw conflict(
          "只有已发出的备货单能登记平台回执；当前状态 "
              + status
              + "。货还没发出去，平台仓不可能有收发回执。");
    }
    requireRef(input.orderLineId(), "备货明细行 orderLineId");
    requireText(input.platformReceiptRef(), "平台回执号 platformReceiptRef");
    String type =
        requireEnum(input.receiptType(), PlatformConsignmentReceiptType.class, "回执类型");
    BigDecimal quantity = requirePositive(input.quantity(), "回执数量");
    if (input.occurredAt() == null) {
      throw bad("必须提供回执发生时刻 occurredAt：平台仓的收发时间是业务事实，登记时刻不是。");
    }
    String source =
        notBlank(input.sourceSystem())
            ? requireEnum(input.sourceSystem(), SourceSystem.class, "回执来源")
            : "MANUAL";
    var line = requireLine(tenant, orderId, input.orderLineId());
    UUID id = UUID.randomUUID();
    try {
      sql.execute(
          "with t as (select ?::timestamptz as at) "
              + "insert into consignment_stock_receipt(id,tenant_id,order_id,order_line_id,receipt_type,"
              + "platform_receipt_ref,quantity,occurred_at,source_system,note,recorded_by_user_id) "
              + "select ?,?,?,?,?,?,?,t.at,?,?,? from t",
          input.occurredAt(),
          id,
          tenant,
          orderId,
          input.orderLineId(),
          type,
          input.platformReceiptRef().trim(),
          quantity,
          source,
          nz(input.note()),
          actorId);
    } catch (org.springframework.dao.DataAccessException e) {
      throw conflict(dbMessage(e, "平台回执登记失败"));
    }
    audit(
        tenant,
        orderId,
        "RECEIPT_RECORDED",
        "登记平台"
            + (type.equals("INBOUND") ? "入库" : "出库")
            + "回执 "
            + input.platformReceiptRef().trim()
            + "（SKU "
            + line.get("sku_code", String.class)
            + "，"
            + quantity.stripTrailingZeros().toPlainString()
            + "）",
        actorId);
    return receipt(findReceipt(tenant, id));
  }

  /** 平台仓回执列表（真分页）。 */
  public PlatformConsignmentReceiptPage receipts(
      UUID orderId, String receiptType, int pageNo, int size) {
    var tenant = tenant();
    requireOrder(tenant, orderId);
    int limit = Math.min(MAX_PAGE_SIZE, Math.max(1, size <= 0 ? 20 : size));
    int offset = Math.max(0, pageNo) * limit;
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("r.tenant_id = ? and r.order_id = ?");
    binds.add(tenant);
    binds.add(orderId);
    if (notBlank(receiptType)) {
      where.add("r.receipt_type = ?");
      binds.add(receiptType.trim().toUpperCase(Locale.ROOT));
    }
    String sqlWhere = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from consignment_stock_receipt r" + sqlWhere, binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select r.id, r.order_id, r.order_line_id, l.sku_code, r.receipt_type, r.platform_receipt_ref,"
                + " r.quantity, r.occurred_at, r.source_system, r.note, r.created_at"
                + " from consignment_stock_receipt r"
                + " join consignment_stock_order_line l on l.tenant_id = r.tenant_id and l.id = r.order_line_id"
                + sqlWhere
                + " order by r.occurred_at desc, r.id limit ? offset ?",
            append(binds, limit, (long) offset).toArray());
    return new PlatformConsignmentReceiptPage(
        rows.stream().map(ConsignmentStockService::receipt).toList(),
        total,
        Math.max(0, pageNo),
        limit);
  }

  // ============================================================ 平台结算回执

  /**
   * 登记平台结算回执。
   *
   * <p>口径随回执走（平台合同规则不是我方自选），但必须与备货单上的口径一致 —— 不一致就说明有人拿错了单据。
   */
  @Transactional
  public PlatformConsignmentSettlementReceipt recordSettlementReceipt(
      UUID orderId, PlatformConsignmentSettlementReceiptInput input, UUID actorId) {
    var tenant = tenant();
    var head = requireOrder(tenant, orderId);
    String status = head.get("status", String.class);
    if (!List.of("SHIPPED", "RECEIVED").contains(status)) {
      throw conflict(
          "只有已发出的备货单能登记平台结算回执；当前状态 " + status + "。货还没发，平台结不了。");
    }
    requireRef(input.orderLineId(), "备货明细行 orderLineId");
    String orderBasis = head.get("settlement_basis", String.class);
    String basis =
        requireEnum(input.settlementBasis(), PlatformConsignmentSettlementBasis.class, "结算口径");
    if (!basis.equals(orderBasis)) {
      throw bad(
          "结算回执的口径（"
              + basis
              + "）与备货单的口径（"
              + orderBasis
              + "）不一致：本备货单的结算未结是按「"
              + basisLabel(orderBasis)
              + "」算的，拿另一个口径的回执来结会让未结量出现两个答案。");
    }
    if (input.periodStart() == null || input.periodEnd() == null) {
      throw bad("必须提供结算区间 periodStart / periodEnd：按日口径下结算未结就是按这个区间算的。");
    }
    if (input.periodEnd().isBefore(input.periodStart())) {
      throw bad("结算区间结束日不能早于起始日。");
    }
    BigDecimal settled = requireNonNegative(input.settledQuantity(), "已结算数量");
    String currency = requireCurrency(input.currency());
    requireText(input.platformRef(), "平台结算回执号 platformRef");
    if (input.receivedAt() == null) {
      throw bad("必须提供回执接收时刻 receivedAt。");
    }
    BigDecimal amount =
        input.settledAmount() == null ? null : input.settledAmount().setScale(4, RoundingMode.HALF_UP);
    if (amount != null && amount.signum() < 0) {
      throw bad("结算金额不能为负。");
    }
    var line = requireLine(tenant, orderId, input.orderLineId());
    UUID id = UUID.randomUUID();
    try {
      sql.execute(
          "with t as (select ?::timestamptz as at) "
              + "insert into consignment_stock_settlement_receipt(id,tenant_id,order_id,order_line_id,"
              + "settlement_basis,period_start,period_end,settled_quantity,settled_amount,currency,"
              + "platform_ref,received_at,source_system,note,recorded_by_user_id) "
              + "select ?,?,?,?,?,?,?,?,?,?,?,t.at,?,?,? from t",
          input.receivedAt(),
          id,
          tenant,
          orderId,
          input.orderLineId(),
          basis,
          input.periodStart(),
          input.periodEnd(),
          settled,
          amount,
          currency,
          input.platformRef().trim(),
          notBlank(input.sourceSystem())
              ? input.sourceSystem().trim().toUpperCase(Locale.ROOT)
              : "MANUAL",
          nz(input.note()),
          actorId);
    } catch (org.springframework.dao.DataAccessException e) {
      throw conflict(dbMessage(e, "平台结算回执登记失败"));
    }
    audit(
        tenant,
        orderId,
        "SETTLEMENT_RECEIPT_RECORDED",
        "登记平台结算回执 "
            + input.platformRef().trim()
            + "（SKU "
            + line.get("sku_code", String.class)
            + "，"
            + settled.stripTrailingZeros().toPlainString()
            + "，口径 "
            + basisLabel(basis)
            + "）",
        actorId);
    return settlementReceipt(findSettlementReceipt(tenant, id));
  }

  /** 平台结算回执列表（真分页）。 */
  public PlatformConsignmentSettlementReceiptPage settlementReceipts(UUID orderId, int pageNo, int size) {
    var tenant = tenant();
    requireOrder(tenant, orderId);
    int limit = Math.min(MAX_PAGE_SIZE, Math.max(1, size <= 0 ? 20 : size));
    int offset = Math.max(0, pageNo) * limit;
    var where = " where sr.tenant_id = ? and sr.order_id = ?";
    long total =
        sql.fetchOne(
                "select count(*) from consignment_stock_settlement_receipt sr" + where, tenant, orderId)
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select sr.id, sr.order_id, sr.order_line_id, l.sku_code, sr.settlement_basis,"
                + " sr.period_start, sr.period_end, sr.settled_quantity, sr.settled_amount, sr.currency,"
                + " sr.platform_ref, sr.received_at, sr.source_system, sr.note, sr.created_at"
                + " from consignment_stock_settlement_receipt sr"
                + " join consignment_stock_order_line l on l.tenant_id = sr.tenant_id and l.id = sr.order_line_id"
                + where
                + " order by sr.period_start desc, sr.id limit ? offset ?",
        tenant,
        orderId,
        limit,
        (long) offset);
    return new PlatformConsignmentSettlementReceiptPage(
        rows.stream().map(ConsignmentStockService::settlementReceipt).toList(),
        total,
        Math.max(0, pageNo),
        limit);
  }

  // ============================================================ 台账 / 对账 / 口径

  /** 托管备货台账：备货量 / 平台在库 / 平台已售 / 结算未结，四个口径分列不合并。 */
  public PlatformConsignmentLedger ledger(UUID orderId) {
    var tenant = tenant();
    var head = requireOrder(tenant, orderId);
    String basis = head.get("settlement_basis", String.class);
    var measures = measures(tenant, orderId);
    BigDecimal totalStocked = ZERO;
    BigDecimal totalReceived = ZERO;
    BigDecimal totalSold = ZERO;
    BigDecimal totalOnHand = ZERO;
    BigDecimal totalInTransit = ZERO;
    BigDecimal totalSettled = ZERO;
    BigDecimal totalUnsettled = ZERO;
    BigDecimal totalOwn = ZERO;
    var rows = new ArrayList<PlatformConsignmentLedgerRow>();
    for (var m : measures) {
      BigDecimal stocked = qty(m.get("stocked_quantity", BigDecimal.class));
      BigDecimal received = qty(m.get("received", BigDecimal.class));
      BigDecimal sold = qty(m.get("sold", BigDecimal.class));
      BigDecimal settled = qty(m.get("settled", BigDecimal.class));
      BigDecimal unsettled = unsettledOf(basis, sold, settled, qty(m.get("unsettled_day", BigDecimal.class)));
      BigDecimal onHand = received.subtract(sold);
      BigDecimal inTransit = stocked.subtract(received);
      if (inTransit.signum() < 0) inTransit = ZERO;
      BigDecimal own = qty(m.get("own_on_hand", BigDecimal.class));
      totalStocked = totalStocked.add(stocked);
      totalReceived = totalReceived.add(received);
      totalSold = totalSold.add(sold);
      totalOnHand = totalOnHand.add(onHand);
      totalInTransit = totalInTransit.add(inTransit);
      totalSettled = totalSettled.add(settled);
      totalUnsettled = totalUnsettled.add(unsettled);
      totalOwn = totalOwn.add(own);
      rows.add(
          new PlatformConsignmentLedgerRow(
              m.get("line_id", UUID.class),
              m.get("sku_id", UUID.class),
              m.get("sku_code", String.class),
              m.get("sku_name", String.class),
              stocked,
              received,
              sold,
              onHand,
              inTransit,
              settled,
              unsettled,
              own,
              qty(m.get("own_reserved", BigDecimal.class)),
              false,
              basis,
              basisLabel(basis),
              settlementState(sold, settled, unsettled).name(),
              settlementStateLabel(settlementState(sold, settled, unsettled))));
    }
    return new PlatformConsignmentLedger(
        orderId,
        head.get("order_code", String.class),
        head.get("shop_code", String.class),
        head.get("shop_name", String.class),
        head.get("operating_mode", String.class),
        modeLabel(head.get("operating_mode", String.class)),
        basis,
        basisLabel(basis),
        totalStocked,
        totalReceived,
        totalSold,
        totalOnHand,
        totalInTransit,
        totalSettled,
        totalUnsettled,
        totalOwn,
        rows,
        MEASURE_NOTE,
        SEPARATION_NOTE);
  }

  /**
   * 与自有库存的**对账结果**。
   *
   * <p>差异有量、有口径：差异量 = 平台入库回执累计 − 备货量（有符号），口径四选一（对平 / 在途未确认 / 平台多收 / 平台出库超过入库）。
   * 自有库存那一侧给的是**同 SKU 在自有库存里的全部在手量**（非按单分摊），并附上分账校验的结论。
   */
  public PlatformConsignmentReconciliation reconciliation(UUID orderId) {
    var tenant = tenant();
    var head = requireOrder(tenant, orderId);
    String basis = head.get("settlement_basis", String.class);
    var measures = measures(tenant, orderId);
    var rows = new ArrayList<PlatformConsignmentReconciliationRow>();
    BigDecimal totalStocked = ZERO;
    BigDecimal totalReceived = ZERO;
    BigDecimal totalSold = ZERO;
    BigDecimal totalOnHand = ZERO;
    BigDecimal totalInTransit = ZERO;
    BigDecimal totalUnsettled = ZERO;
    BigDecimal totalOwn = ZERO;
    BigDecimal totalOwnReserved = ZERO;
    for (var m : measures) {
      BigDecimal stocked = qty(m.get("stocked_quantity", BigDecimal.class));
      BigDecimal received = qty(m.get("received", BigDecimal.class));
      BigDecimal sold = qty(m.get("sold", BigDecimal.class));
      BigDecimal settled = qty(m.get("settled", BigDecimal.class));
      BigDecimal unsettled = unsettledOf(basis, sold, settled, qty(m.get("unsettled_day", BigDecimal.class)));
      BigDecimal onHand = received.subtract(sold);
      BigDecimal inTransit = stocked.subtract(received);
      if (inTransit.signum() < 0) inTransit = ZERO;
      BigDecimal own = qty(m.get("own_on_hand", BigDecimal.class));
      BigDecimal ownReserved = qty(m.get("own_reserved", BigDecimal.class));
      BigDecimal difference = received.subtract(stocked);
      var diffBasis = differenceBasis(received, stocked, sold);
      totalStocked = totalStocked.add(stocked);
      totalReceived = totalReceived.add(received);
      totalSold = totalSold.add(sold);
      totalOnHand = totalOnHand.add(onHand);
      totalInTransit = totalInTransit.add(inTransit);
      totalUnsettled = totalUnsettled.add(unsettled);
      totalOwn = totalOwn.add(own);
      totalOwnReserved = totalOwnReserved.add(ownReserved);
      rows.add(
          new PlatformConsignmentReconciliationRow(
              m.get("line_id", UUID.class),
              m.get("sku_id", UUID.class),
              m.get("sku_code", String.class),
              m.get("sku_name", String.class),
              stocked,
              received,
              sold,
              onHand,
              inTransit,
              settled,
              unsettled,
              own,
              ownReserved,
              difference,
              diffBasis.name(),
              diffBasis.label(),
              diffBasis.description()));
    }
    BigDecimal leak = segregationLeak(tenant);
    var status =
        leak.signum() > 0
            ? PlatformConsignmentSegregationStatus.VIOLATION
            : PlatformConsignmentSegregationStatus.SEPARATED;
    return new PlatformConsignmentReconciliation(
        orderId,
        head.get("order_code", String.class),
        head.get("shop_code", String.class),
        head.get("operating_mode", String.class),
        modeLabel(head.get("operating_mode", String.class)),
        basis,
        basisLabel(basis),
        totalStocked,
        totalReceived,
        totalSold,
        totalOnHand,
        totalInTransit,
        totalUnsettled,
        totalOwn,
        totalOwnReserved,
        leak,
        status.name(),
        status.label(),
        rows,
        "差异量 = 平台入库回执累计 − 备货量（有符号：正数 = 平台多收，负数 = 在途未确认）。",
        SEGREGATION_CHECK_NOTE);
  }

  /**
   * 结算口径标记（供 12.3.1 结算使用）。
   *
   * <p>{@code unsettledKnown=false} 时 {@code unsettledQuantity} 是 {@code null}（**不是 0**）：
   * 平台一件回执都没给的时候，「没给回执」不等于「没有未结」。
   */
  public PlatformConsignmentSettlementBasisView settlementBasis(UUID orderId) {
    var tenant = tenant();
    var head = requireOrder(tenant, orderId);
    String basis = head.get("settlement_basis", String.class);
    BigDecimal sold =
        qty(
            sql.fetchOne(
                    "select coalesce(sum(quantity),0) from consignment_stock_receipt"
                        + " where tenant_id = ? and order_id = ? and receipt_type='OUTBOUND'",
                    tenant,
                    orderId)
                .get(0, BigDecimal.class));
    BigDecimal settled =
        qty(
            sql.fetchOne(
                    "select coalesce(sum(settled_quantity),0) from consignment_stock_settlement_receipt"
                        + " where tenant_id = ? and order_id = ?",
                    tenant,
                    orderId)
                .get(0, BigDecimal.class));
    boolean known = sold.signum() != 0 || settled.signum() != 0;
    BigDecimal unsettled =
        known ? unsettledOf(tenant, orderId) : null;
    var periods = new ArrayList<PlatformConsignmentSettledPeriod>();
    for (var r :
        sql.fetch(
            "select period_start, period_end, settled_quantity, settlement_basis"
                + " from consignment_stock_settlement_receipt"
                + " where tenant_id = ? and order_id = ? order by period_start, period_end",
            tenant,
            orderId)) {
      periods.add(
          new PlatformConsignmentSettledPeriod(
              r.get("period_start", LocalDate.class),
              r.get("period_end", LocalDate.class),
              qty(r.get("settled_quantity", BigDecimal.class)),
              r.get("settlement_basis", String.class)));
    }
    return new PlatformConsignmentSettlementBasisView(
        orderId,
        head.get("order_code", String.class),
        basis,
        basisLabel(basis),
        basisRule(basis),
        sold,
        settled,
        unsettled,
        known,
        settlementState(sold, settled, unsettled == null ? ZERO : unsettled).name(),
        settlementStateLabel(settlementState(sold, settled, unsettled == null ? ZERO : unsettled)),
        periods,
        SETTLEMENT_NOTE);
  }

  /** 审计时间线（append-only）。 */
  public List<PlatformConsignmentAuditEntry> auditTrail(UUID orderId, Integer limit) {
    var tenant = tenant();
    requireOrder(tenant, orderId);
    int max = Math.min(MAX_AUDIT_ROWS, limit == null || limit <= 0 ? 100 : limit);
    return sql.fetch(
            "select id, event_type, detail, actor_user_id, occurred_at from consignment_stock_audit"
                + " where tenant_id = ? and order_id = ? order by occurred_at desc, id limit ?",
            tenant,
            orderId,
            max)
        .stream()
        .map(
            r ->
                new PlatformConsignmentAuditEntry(
                    r.get("id", UUID.class),
                    r.get("event_type", String.class),
                    r.get("detail", String.class),
                    r.get("actor_user_id", UUID.class),
                    r.get("occurred_at", OffsetDateTime.class)))
        .toList();
  }

  // ============================================================ 内部

  private PlatformConsignmentOrderDetail detail(UUID orderId) {
    var tenant = tenant();
    requireOrder(tenant, orderId);
    var order = order(orderHead(tenant, orderId));
    var lines = new ArrayList<PlatformConsignmentOrderLine>();
    for (var m : measures(tenant, orderId)) {
      BigDecimal stocked = qty(m.get("stocked_quantity", BigDecimal.class));
      BigDecimal received = qty(m.get("received", BigDecimal.class));
      BigDecimal sold = qty(m.get("sold", BigDecimal.class));
      BigDecimal settled = qty(m.get("settled", BigDecimal.class));
      BigDecimal unsettled =
          unsettledOf(
              order.settlementBasis(), sold, settled, qty(m.get("unsettled_day", BigDecimal.class)));
      BigDecimal onHand = received.subtract(sold);
      BigDecimal inTransit = stocked.subtract(received);
      if (inTransit.signum() < 0) inTransit = ZERO;
      var state = settlementState(sold, settled, unsettled);
      lines.add(
          new PlatformConsignmentOrderLine(
              m.get("line_id", UUID.class),
              orderId,
              m.get("sku_id", UUID.class),
              m.get("sku_code", String.class),
              m.get("sku_name", String.class),
              stocked,
              m.get("unit", String.class),
              false,
              received,
              sold,
              onHand,
              inTransit,
              settled,
              unsettled,
              state.name(),
              settlementStateLabel(state),
              nz(m.get("note", String.class))));
    }
    return new PlatformConsignmentOrderDetail(
        order, lines, ledger(orderId), reconciliation(orderId), auditTrail(orderId, 100));
  }

  /** 逐行口径（一条 SQL 拿全，不做 N+1）。 */
  private List<org.jooq.Record> measures(UUID tenant, UUID orderId) {
    return sql.fetch(
        MEASURE_CTES
            + " select lm.line_id, lm.order_id, lm.sku_id, lm.sku_code, lm.sku_name,"
            + " lm.stocked_quantity, lm.unit, lm.note, lm.received, lm.sold, lm.settled,"
            + " lm.own_on_hand, lm.own_reserved, lm.unsettled_day"
            + " from lm where lm.order_id = ? order by lm.line_id",
        tenant,
        tenant,
        tenant,
        tenant,
        tenant,
        orderId);
  }

  private void insertLine(UUID tenant, UUID orderId, PlatformConsignmentLineInput line) {
    requireRef(line.skuId(), "SKU skuId");
    BigDecimal quantity = requirePositive(line.stockedQuantity(), "备货量");
    var sku = sql.fetchOne("select id, code, name from sku_ref where tenant_id = ? and id = ?", tenant, line.skuId());
    if (sku == null) {
      throw bad("SKU " + line.skuId() + " 在本租户的 SKU 登记里不存在。");
    }
    try {
      sql.execute(
          "insert into consignment_stock_order_line(id,tenant_id,order_id,sku_id,sku_code,sku_name,"
              + "stocked_quantity,unit,note) values(?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID(),
          tenant,
          orderId,
          line.skuId(),
          sku.get("code", String.class),
          sku.get("name", String.class),
          quantity,
          notBlank(line.unit()) ? line.unit().trim() : "PCS",
          nz(line.note()));
    } catch (org.springframework.dao.DataAccessException e) {
      throw conflict(dbMessage(e, "SKU 不可用或该备货单已含同一 SKU"));
    }
  }

  private PlatformConsignmentOrder transition(
      UUID orderId,
      String from,
      String to,
      String event,
      PlatformConsignmentNoteInput input,
      UUID actorId,
      String timeColumn) {
    var tenant = tenant();
    var head = requireOrder(tenant, orderId);
    if (!from.equals(head.get("status", String.class))) {
      throw conflict(
          "备货单当前状态是 " + head.get("status", String.class) + "，不能执行这个动作（期望 " + from + "）。");
    }
    sql.execute(
        "update consignment_stock_order set status=?, "
            + timeColumn
            + "=coalesce("
            + timeColumn
            + ",?::timestamptz), note=coalesce(nullif(?,''),note), updated_at=?::timestamptz"
            + " where tenant_id=? and id=?",
        to,
        OffsetDateTime.now(),
        note(input),
        OffsetDateTime.now(),
        tenant,
        orderId);
    audit(tenant, orderId, event, "状态 " + from + " → " + to, actorId);
    return order(orderHead(tenant, orderId));
  }

  /**
   * 备货单头（**带聚合列**的投影）。
   *
   * <p>{@link #requireOrder} 取的是 {@code select *} 的原始行，里面没有 line_count / 合计列 ——
   * 单据头要对外返回时必须走这里，否则会把「没查聚合」当成「聚合是 0」。
   */
  private org.jooq.Record orderHead(UUID tenant, UUID orderId) {
    var row =
        sql.fetchOne(
            MEASURE_CTES
                + ORDER_AGG
                + " select "
                + ORDER_PROJECTION
                + " from consignment_stock_order o left join agg on agg.order_id = o.id"
                + " where o.tenant_id = ? and o.id = ?",
            tenant,
            tenant,
            tenant,
            tenant,
            tenant,
            tenant,
            tenant,
            orderId);
    if (row == null) {
      throw notFound("备货单", orderId);
    }
    return row;
  }

  private org.jooq.Record requireOrder(UUID tenant, UUID orderId) {
    var row =
        sql.fetchOne("select * from consignment_stock_order where tenant_id = ? and id = ?", tenant, orderId);
    if (row == null) {
      throw notFound("备货单", orderId);
    }
    return row;
  }

  private org.jooq.Record requireLine(UUID tenant, UUID orderId, UUID lineId) {
    var row =
        sql.fetchOne(
            "select * from consignment_stock_order_line where tenant_id = ? and order_id = ? and id = ?",
            tenant,
            orderId,
            lineId);
    if (row == null) {
      throw notFound("备货明细行", lineId);
    }
    return row;
  }

  /** 回执读回（带 SKU 编码，用于返回值）。 */
  private org.jooq.Record findReceipt(UUID tenant, UUID receiptId) {
    var row =
        sql.fetchOne(
            "select r.id, r.order_id, r.order_line_id, l.sku_code, r.receipt_type,"
                + " r.platform_receipt_ref, r.quantity, r.occurred_at, r.source_system, r.note, r.created_at"
                + " from consignment_stock_receipt r"
                + " join consignment_stock_order_line l on l.tenant_id = r.tenant_id and l.id = r.order_line_id"
                + " where r.tenant_id = ? and r.id = ?",
            tenant,
            receiptId);
    if (row == null) {
      throw notFound("平台回执", receiptId);
    }
    return row;
  }

  /** 结算回执读回（带 SKU 编码）。 */
  private org.jooq.Record findSettlementReceipt(UUID tenant, UUID receiptId) {
    var row =
        sql.fetchOne(
            "select sr.id, sr.order_id, sr.order_line_id, l.sku_code, sr.settlement_basis,"
                + " sr.period_start, sr.period_end, sr.settled_quantity, sr.settled_amount, sr.currency,"
                + " sr.platform_ref, sr.received_at, sr.source_system, sr.note, sr.created_at"
                + " from consignment_stock_settlement_receipt sr"
                + " join consignment_stock_order_line l on l.tenant_id = sr.tenant_id and l.id = sr.order_line_id"
                + " where sr.tenant_id = ? and sr.id = ?",
            tenant,
            receiptId);
    if (row == null) {
      throw notFound("平台结算回执", receiptId);
    }
    return row;
  }

  private org.jooq.Record findSkuByCode(String code) {
    if (!notBlank(code)) return null;
    return sql.fetchOne("select id, code, name from sku_ref where tenant_id = ? and code = ?", tenant(), code.trim());
  }

  /** 结算未结合计（按备货单口径）。 */
  private BigDecimal unsettledOf(UUID tenant, UUID orderId) {
    var head = requireOrder(tenant, orderId);
    String basis = head.get("settlement_basis", String.class);
    BigDecimal sold =
        qty(
            sql.fetchOne(
                    "select coalesce(sum(quantity),0) from consignment_stock_receipt"
                        + " where tenant_id = ? and order_id = ? and receipt_type='OUTBOUND'",
                    tenant,
                    orderId)
                .get(0, BigDecimal.class));
    BigDecimal settled =
        qty(
            sql.fetchOne(
                    "select coalesce(sum(settled_quantity),0) from consignment_stock_settlement_receipt"
                        + " where tenant_id = ? and order_id = ?",
                    tenant,
                    orderId)
                .get(0, BigDecimal.class));
    BigDecimal unsettledDay = ZERO;
    if ("PER_DAY".equals(basis)) {
      unsettledDay =
          qty(
              sql.fetchOne(
                      "select coalesce(sum(r.quantity),0) from consignment_stock_receipt r"
                          + " where r.tenant_id = ? and r.order_id = ? and r.receipt_type='OUTBOUND'"
                          + " and not exists (select 1 from consignment_stock_settlement_receipt s"
                          + "   where s.tenant_id = r.tenant_id and s.order_line_id = r.order_line_id"
                          + "     and s.settlement_basis='PER_DAY'"
                          + "     and cast(r.occurred_at at time zone 'UTC' as date)"
                          + "         between s.period_start and s.period_end)",
                      tenant,
                      orderId)
                  .get(0, BigDecimal.class));
    }
    return unsettledOf(basis, sold, settled, unsettledDay);
  }

  /** 两种口径下的未结量（真的算出不同结果）。 */
  private static BigDecimal unsettledOf(
      String basis, BigDecimal sold, BigDecimal settled, BigDecimal unsettledDay) {
    if ("PER_DAY".equals(basis)) {
      return unsettledDay == null ? ZERO : unsettledDay;
    }
    BigDecimal diff = sold.subtract(settled == null ? ZERO : settled);
    return diff.signum() < 0 ? ZERO : diff;
  }

  /** 分账校验：自有库存账本里与平台托管相关的**正数**在手增量。恒为 0。 */
  private BigDecimal segregationLeak(UUID tenant) {
    return qty(
        sql.fetchOne(
                "select coalesce(sum(e.on_hand_delta),0) from inventory_entry e"
                    + " join inventory_transaction t on t.tenant_id = e.tenant_id and t.id = e.transaction_id"
                    + " where e.tenant_id = ? and t.ref_type = ? and e.on_hand_delta > 0",
                tenant,
                REF_TYPE)
            .get(0, BigDecimal.class));
  }

  private static PlatformConsignmentDifferenceBasis differenceBasis(
      BigDecimal received, BigDecimal stocked, BigDecimal sold) {
    if (sold.compareTo(received) > 0) {
      return PlatformConsignmentDifferenceBasis.NEGATIVE_ON_HAND;
    }
    if (received.compareTo(stocked) > 0) {
      return PlatformConsignmentDifferenceBasis.OVER_RECEIVED;
    }
    if (received.compareTo(stocked) < 0) {
      return PlatformConsignmentDifferenceBasis.IN_TRANSIT;
    }
    return PlatformConsignmentDifferenceBasis.MATCHED;
  }

  private static PlatformConsignmentSettlementState settlementState(
      BigDecimal sold, BigDecimal settled, BigDecimal unsettled) {
    BigDecimal s = sold == null ? ZERO : sold;
    BigDecimal t = settled == null ? ZERO : settled;
    BigDecimal u = unsettled == null ? ZERO : unsettled;
    if (s.signum() == 0 && t.signum() == 0) {
      return PlatformConsignmentSettlementState.NOT_STARTED;
    }
    return u.signum() > 0
        ? PlatformConsignmentSettlementState.PENDING
        : PlatformConsignmentSettlementState.SETTLED;
  }

  private static PlatformConsignmentOrder order(org.jooq.Record r) {
    return new PlatformConsignmentOrder(
        r.get("id", UUID.class),
        r.get("order_code", String.class),
        r.get("shop_id", UUID.class),
        r.get("shop_code", String.class),
        r.get("shop_name", String.class),
        r.get("operating_mode", String.class),
        modeLabel(r.get("operating_mode", String.class)),
        r.get("platform_warehouse_code", String.class),
        r.get("platform_warehouse_name", String.class),
        r.get("legal_entity_id", UUID.class),
        r.get("currency", String.class),
        r.get("settlement_basis", String.class),
        basisLabel(r.get("settlement_basis", String.class)),
        r.get("status", String.class),
        statusLabel(r.get("status", String.class)),
        r.get("forecast_id", UUID.class),
        nz(r.get("forecast_no", String.class)),
        r.get("line_count", Long.class),
        qty(r.get("total_stocked_quantity", BigDecimal.class)),
        qty(r.get("platform_received_quantity", BigDecimal.class)),
        qty(r.get("platform_sold_quantity", BigDecimal.class)),
        qty(r.get("platform_on_hand_quantity", BigDecimal.class)),
        qty(r.get("unsettled_quantity", BigDecimal.class)),
        r.get("settlement_state", String.class),
        settlementStateLabel(stateOf(r.get("settlement_state", String.class))),
        r.get("note", String.class),
        r.get("created_by_user_id", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("shipped_at", OffsetDateTime.class),
        r.get("platform_received_at", OffsetDateTime.class),
        r.get("closed_at", OffsetDateTime.class));
  }

  private static PlatformConsignmentReceipt receipt(org.jooq.Record r) {
    return new PlatformConsignmentReceipt(
        r.get("id", UUID.class),
        r.get("order_id", UUID.class),
        r.get("order_line_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("receipt_type", String.class),
        receiptTypeLabel(r.get("receipt_type", String.class)),
        r.get("platform_receipt_ref", String.class),
        qty(r.get("quantity", BigDecimal.class)),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("source_system", String.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static PlatformConsignmentSettlementReceipt settlementReceipt(org.jooq.Record r) {
    return new PlatformConsignmentSettlementReceipt(
        r.get("id", UUID.class),
        r.get("order_id", UUID.class),
        r.get("order_line_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("settlement_basis", String.class),
        basisLabel(r.get("settlement_basis", String.class)),
        r.get("period_start", LocalDate.class),
        r.get("period_end", LocalDate.class),
        qty(r.get("settled_quantity", BigDecimal.class)),
        r.get("settled_amount", BigDecimal.class),
        r.get("currency", String.class),
        r.get("platform_ref", String.class),
        r.get("received_at", OffsetDateTime.class),
        r.get("source_system", String.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private void audit(UUID tenant, UUID orderId, String event, String detail, UUID actorId) {
    sql.execute(
        "insert into consignment_stock_audit(id,tenant_id,order_id,event_type,detail,actor_user_id)"
            + " values(?,?,?,?,?,?)",
        UUID.randomUUID(),
        tenant,
        orderId,
        event,
        detail,
        actorId);
  }

  // ============================================================ 工具

  /** 回执来源词表（与 DB CHECK 一致）。 */
  public enum SourceSystem {
    MANUAL,
    PLATFORM_FEED
  }

  private UUID tenant() {
    return sql.fetchOne("select current_setting('app.current_tenant',true)::uuid as t")
        .get("t", UUID.class);
  }

  /** 单号 = CSP-当天日期-六位序号。序号与日期分两步取，不在一条 SQL 里拼。 */
  private String nextCode() {
    long seq =
        sql.fetchOne("select nextval('consignment_stock_order_code_seq')").get(0, Long.class);
    var today = sql.fetchOne("select current_date").get(0, LocalDate.class);
    return "CSP-" + today.toString().replace("-", "") + "-" + String.format("%06d", seq);
  }

  private static String modeLabel(String name) {
    if (name == null) return "";
    for (var m : PlatformConsignmentOperatingMode.values()) {
      if (m.name().equals(name)) return m.label();
    }
    return name;
  }

  private static String describeMode(String name) {
    for (var m : PlatformConsignmentOperatingMode.values()) {
      if (m.name().equals(name)) return m.description();
    }
    return "该模式不属于平台托管";
  }

  private static String basisLabel(String name) {
    if (name == null) return "";
    for (var b : PlatformConsignmentSettlementBasis.values()) {
      if (b.name().equals(name)) return b.label();
    }
    return name;
  }

  private static String basisRule(String name) {
    for (var b : PlatformConsignmentSettlementBasis.values()) {
      if (b.name().equals(name)) return b.description();
    }
    return "";
  }

  private static String receiptTypeLabel(String name) {
    if (name == null) return "";
    for (var t : PlatformConsignmentReceiptType.values()) {
      if (t.name().equals(name)) return t.label();
    }
    return name;
  }

  private static String statusLabel(String name) {
    if (name == null) return "";
    for (var s : PlatformConsignmentOrderStatus.values()) {
      if (s.name().equals(name)) return s.label();
    }
    return name;
  }

  private static PlatformConsignmentSettlementState stateOf(String name) {
    if (name == null) return PlatformConsignmentSettlementState.NOT_STARTED;
    for (var s : PlatformConsignmentSettlementState.values()) {
      if (s.name().equals(name)) return s;
    }
    return PlatformConsignmentSettlementState.NOT_STARTED;
  }

  private static String settlementStateLabel(PlatformConsignmentSettlementState state) {
    return state.label();
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    binds.addAll(List.of(more));
    return binds;
  }

  /**
   * CTE 的租户绑定在前，筛选绑定在后。
   *
   * <p>这里补 **6** 个租户绑定：MEASURE_CTES 五个（l / rec / st / unsettled_day / own）+
   * ORDER_AGG 的 {@code agg} join 一个。外层 {@code where o.tenant_id = ?} 的那个绑定
   * **已经在筛选绑定列表的第一位**（{@link #orders} 里先 {@code binds.add(tenant())}），
   * 这里再补一次就会整体错位 —— 错位后的报错是「LIMIT 的参数必须是 bigint 而不是 uuid」，
   * 完全指不到「多绑了一个租户」。
   */
  private static List<Object> prepend(UUID tenant, List<Object> binds, Object... more) {
    var out = new ArrayList<Object>();
    for (int i = 0; i < 6; i++) {
      out.add(tenant);
    }
    out.addAll(binds);
    out.addAll(List.of(more));
    return out;
  }

  private static boolean notBlank(String v) {
    return v != null && !v.isBlank();
  }

  private static String nz(String v) {
    return v == null ? "" : v;
  }

  private static String note(PlatformConsignmentNoteInput input) {
    return input == null || input.note() == null ? "" : input.note().trim();
  }

  /** numeric(19,6) 口径：数量列都是 6 位，读回来也必须是 6 位。 */
  private static BigDecimal qty(BigDecimal v) {
    return v == null ? ZERO : v.setScale(6, RoundingMode.HALF_UP);
  }

  private static BigDecimal requirePositive(BigDecimal v, String what) {
    if (v == null || v.signum() <= 0) {
      throw bad(what + "必须大于 0。");
    }
    return qty(v);
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
      throw bad("币种必须是三位大写字母（如 USD / CNY），收到：" + value + "。结算没有币种就是一个无意义的数字。");
    }
    return v;
  }

  /** 把数据库约束的原始报错翻成人话（SQLSTATE 23503/23514/23505）。 */
  private static String dbMessage(Exception e, String fallback) {
    for (Throwable c = e; c != null; c = c.getCause()) {
      var m = c.getMessage();
      if (m == null) continue;
      if (m.contains("consignment_stock_receipt_bounds")
          || m.contains("不能卖出比收到的更多的货")
          || m.contains("不能卖出平台没收到的货")) {
        return "平台出库累计不能超过入库累计：不能卖出平台还没收到的货。";
      }
      if (m.contains("consignment_stock_settlement_bounds") || m.contains("没卖出去的不许结")) {
        return "结算累计不能超过平台已售：没卖出去的不许结。";
      }
      if (m.contains("consignment_stock_basis_frozen")
          || m.contains("结算口径（按件/按日）不可再改")) {
        return "备货单已离开草稿态，结算口径（按件/按日）不可再改。";
      }
      if (m.contains("consignment_stock_order_state")) {
        return "备货单状态不允许这个流转。";
      }
      if (m.contains("consignment_stock_entry_not_added")
          || m.contains("不得并入自有库存余额")) {
        return "平台托管备货的单据不允许把货加进自有库存账本：平台在库数不得并入自有库存余额。";
      }
      if (m.contains("duplicate key") || m.contains("unique")) {
        return "这条记录已经存在了（幂等锚点重复）：同一平台回执号不会被登记两次。";
      }
      if (m.contains("sku_ref")) return "SKU 在本租户的 SKU 登记里不存在。";
      if (m.contains("legal_entity_ref")) return "法人在本租户的法人登记里不存在。";
    }
    return fallback;
  }

  private static ResponseStatusException notFound(String what, UUID id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what + "不存在或不属于当前租户：" + id);
  }

  private static ResponseStatusException notFoundByMessage(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
