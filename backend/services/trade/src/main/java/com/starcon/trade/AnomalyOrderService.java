package com.starcon.trade;

import static com.starcon.trade.AnomalyOrderModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 1.1.1.2 异常订单队列 —— **只读投影**。
 *
 * <p>卷 01 的边界（蓝图第一行）：「不产生新的业务事实，只聚合各系统的投影」、
 * 「<b>不做</b>：任何写业务数据的操作。工作台只读 + 跳转」。
 *
 * ⇒ 本类<b>没有任何写方法</b>，<b>没有新建任何表</b>（因此<b>不占用 V327</b>），
 * 全部读 09 / 11 已经在本服务里落下的事实表。
 *
 * <h2>七类异常的分工</h2>
 *
 * <p>本服务（trade）负责**订单侧的四类**信号 + 订单摘要：
 *
 * <ul>
 *   <li>平台超时未回传发货 ← {@code shipment_writeback_task} + {@code shipment_record}</li>
 *   <li>地址不可达 ← {@code order_address_check.delivery_status='NOT_DELIVERABLE'}</li>
 *   <li>买家留言待确认 ← {@code platform_order.normalized_status='PENDING_CONFIRMATION'}</li>
 *   <li>地址欺诈疑似 ← {@code order_risk_assessment_component} 的 {@code ADDRESS_CONCENTRATION} 维</li>
 *   <li>重复下单 ← {@code order_risk_assessment_component} 的 {@code ORDER_FREQUENCY} 维</li>
 * </ul>
 *
 * <p>另两类的事实归属在别的服务，<b>由前端聚合层</b>去读它们的授权只读端点后合并
 * （AGENTS.md 模块边界纪律：禁止跨服务 SQL / FK / 共享视图）：
 *
 * <ul>
 *   <li>黑名单买家 ← channel 16.5.1.1 {@code GET /channel/buyer-blacklist/flags}</li>
 *   <li>库存不足卡单 ← warehouse 07 {@code GET /warehouse/inventory/sellable/skus}</li>
 * </ul>
 *
 * <h2>为什么不用「一张异常表」把判定结果物化出来</h2>
 *
 * <p>物化表要有人写、有人保鲜、有人对账，而卷 01 明写「不产生新的业务事实」。
 * 这些判定<b>全部</b>能在已有事实表上**当场算出来**，所以本模块选择<b>现场算</b>：
 * 判定口径变了不需要等一次同步，页面上的数字与归属系统的口径天然一致。
 *
 * <h2>停留时长怎么算</h2>
 *
 * <p><b>停留时长 = 从「这张单进入当前异常状态」到现在的时长</b>，
 * 不是一个笼统的「订单创建至今」。每类异常的起点锚点各不相同：
 *
 * <ul>
 *   <li>平台超时未回传发货 → 回传任务的创建时刻</li>
 *   <li>地址不可达 → 地址校验的判定基准时刻（{@code assessed_reference_at}）</li>
 *   <li>买家留言待确认 → 平台单的下单时刻（{@code platform_placed_at}）</li>
 *   <li>地址欺诈疑似 / 重复下单 → 风控评估的评估基准时刻（{@code evaluated_reference_at}）</li>
 * </ul>
 *
 * <p>一张单命中多类时，清单行的停留时长取<b>最长</b>那一条（= 它卡得最久的那件事），
 * 逐条凭据在明细页各给各的起点与时长。
 */
@Service
public class AnomalyOrderService {

  /** 单页最大行数。异常清单是要给人看的队列，不是导出。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 平台超时未回传发货：回传任务超过这个小时数还没定论就算异常。 */
  static final int PLATFORM_WRITEBACK_TIMEOUT_HOURS = 24;

  /** 买家留言待确认：平台单在这个小时数内还没确认就算异常。 */
  static final int BUYER_MESSAGE_TIMEOUT_HOURS = 24;

  /** 默认停留时长下限（小时）。 */
  static final int DEFAULT_MIN_DWELL_HOURS = 1;

  /** 排序白名单：非法排序键报 400，而不是静默按默认序返回。 */
  static final Set<String> SORTS = Set.of("dwell", "amount", "orderNo", "submittedAt");

  private static final String TENANT =
      " nullif(current_setting('app.current_tenant',true),'')::uuid";

  /**
   * 公共 CTE 前缀。
   *
   * <p>⚠️ 两个超时阈值**以字面量内联**（它们是本类里的 {@code int} 常量，不是用户输入），
   * 这样同一个前缀可以被「清单 / 汇总 / 按店铺分组 / 选项」四条查询共用，
   * 而不必重复四遍 join 与过滤 —— 几处口径一旦写岔，页面上的数字就会自相矛盾。
   */
  private static final String CTE =
      """
      with addr as (
        select distinct on (c.order_id)
               c.order_id, c.id, c.order_no, c.delivery_status, c.risk_level, c.cod_flagged,
               c.assessed_reference_at, c.shipping_country_code, c.item_count, c.flagged_count,
               c.blocked_count, c.warn_count, c.critical_count, c.policy_name, c.policy_version
          from order_address_check c
         where c.tenant_id = """
          + TENANT
          + """
         order by c.order_id, c.assessed_reference_at desc, c.id desc
      ),
      plat as (
        select p.sales_order_id, p.platform_code, p.shop_code, p.external_order_no,
               p.platform_status, p.normalized_status, p.platform_placed_at, p.platform_updated_at
          from platform_order p
         where p.tenant_id = """
          + TENANT
          + """
      ),
      wb as (
        select w.id, w.task_no, w.platform_code, w.shop_code, w.status, w.verification,
               w.attempt_count, w.max_attempts, w.last_error, w.created_at, w.updated_at,
               w.sales_order_no, r.sales_order_id, r.shop_ref
          from shipment_writeback_task w
          join shipment_record r on r.tenant_id = w.tenant_id and r.id = w.source_record_id
         where w.tenant_id = """
          + TENANT
          + """
      ),
      risk as (
        select distinct on (a.order_id)
               a.id, a.order_id, a.order_no, a.risk_score, a.recommended_action,
               a.manual_review_required, a.evaluation_status, a.final_action,
               a.evaluated_reference_at, a.rule_name, a.rule_version, a.created_at
          from order_risk_assessment a
         where a.tenant_id = """
          + TENANT
          + """
         order by a.order_id, a.evaluated_reference_at desc, a.id desc
      ),
      risk_comp as (
        select c.assessment_id, c.dimension, c.status, c.raw_value, c.normalized_score,
               c.contribution, c.signal_source, c.explanation
          from order_risk_assessment_component c
         where c.tenant_id = """
          + TENANT
          + """
      ),
      sig as (
        select w.sales_order_id as order_id, 'PLATFORM_SHIPMENT_TIMEOUT'::text as dimension,
               w.created_at as since_at, 'HIGH'::text as severity
          from wb w
         where w.sales_order_id is not null
           and w.status in ('PENDING','SUBMITTED','PENDING_VERIFICATION','REJECTED')
           and w.created_at <= now() - make_interval(hours => """
          + PLATFORM_WRITEBACK_TIMEOUT_HOURS
          + """
)
        union all
        select a.order_id, 'ADDRESS_UNDELIVERABLE'::text, a.assessed_reference_at, 'HIGH'::text
          from addr a
         where a.delivery_status = 'NOT_DELIVERABLE'
        union all
        select p.sales_order_id, 'BUYER_MESSAGE_PENDING'::text, p.platform_placed_at, 'MEDIUM'::text
          from plat p
         where p.normalized_status = 'PENDING_CONFIRMATION'
           and p.platform_placed_at is not null
           and p.platform_placed_at <= now() - make_interval(hours => """
          + BUYER_MESSAGE_TIMEOUT_HOURS
          + """
)
        union all
        select r.order_id, 'DUPLICATE_ORDER'::text, r.evaluated_reference_at, 'MEDIUM'::text
          from risk r
          join risk_comp c on c.assessment_id = r.id and c.dimension = 'ORDER_FREQUENCY'
         where c.status = 'SCORED' and c.contribution > 0
        union all
        select r.order_id, 'ADDRESS_FRAUD_SUSPECTED'::text, r.evaluated_reference_at, 'HIGH'::text
          from risk r
          join risk_comp c on c.assessment_id = r.id and c.dimension = 'ADDRESS_CONCENTRATION'
         where c.status = 'SCORED' and c.contribution > 0
      ),
      agg as (
        select s.order_id,
               array_agg(distinct s.dimension order by s.dimension) as dimensions,
               string_agg(distinct s.dimension, ',' order by s.dimension) as dimension_text,
               min(s.since_at) as oldest_since,
               count(*) as signal_count,
               max(case s.severity when 'CRITICAL' then 4 when 'HIGH' then 3
                                  when 'MEDIUM' then 2 else 1 end) as severity_rank
          from sig s
         group by s.order_id
      )
      """;

  private final DSLContext sql;

  public AnomalyOrderService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：七类异常**哪几类真接通**、哪几类在别的服务里、哪些动作**没有**归属入口。
   *
   * <p>🔴 蓝图里提到的 {@code platform_performance_metric}（平台绩效指标）在本仓
   * **没有任何表、没有任何模块持有** —— 它属于 1.1.2.3 履约健康，不属于本模块的七类异常。
   * 本模块<b>不造</b>这张表，也不把「平台超时」与「平台绩效指标」混为一谈：
   * 前者有真实事实来源（{@code shipment_writeback_task}），后者没有。
   */
  public AnomalyOrderCapabilities capabilities() {
    List<AnomalyOrderCapability> dimensions =
        List.of(
            new AnomalyOrderCapability(
                AnomalyType.PLATFORM_SHIPMENT_TIMEOUT.name(),
                AnomalyType.PLATFORM_SHIPMENT_TIMEOUT.label(),
                true,
                "09 订单履约（trade）",
                "shipment_writeback_task + shipment_record",
                "TRADE",
                ""),
            new AnomalyOrderCapability(
                AnomalyType.ADDRESS_UNDELIVERABLE.name(),
                AnomalyType.ADDRESS_UNDELIVERABLE.label(),
                true,
                "09 订单履约（trade）",
                "order_address_check.delivery_status = 'NOT_DELIVERABLE'",
                "TRADE",
                ""),
            new AnomalyOrderCapability(
                AnomalyType.BUYER_MESSAGE_PENDING.name(),
                AnomalyType.BUYER_MESSAGE_PENDING.label(),
                true,
                "09 订单履约（trade）",
                "platform_order.normalized_status = 'PENDING_CONFIRMATION'",
                "TRADE",
                ""),
            new AnomalyOrderCapability(
                AnomalyType.BLACKLIST_BUYER.name(),
                AnomalyType.BLACKLIST_BUYER.label(),
                true,
                "16 合规与风控（channel）",
                "GET /channel/buyer-blacklist/flags",
                "FRONTEND",
                ""),
            new AnomalyOrderCapability(
                AnomalyType.INSUFFICIENT_STOCK.name(),
                AnomalyType.INSUFFICIENT_STOCK.label(),
                true,
                "07 库存（warehouse）",
                "GET /warehouse/inventory/sellable/skus",
                "FRONTEND",
                ""),
            new AnomalyOrderCapability(
                AnomalyType.ADDRESS_FRAUD_SUSPECTED.name(),
                AnomalyType.ADDRESS_FRAUD_SUSPECTED.label(),
                true,
                "09 订单履约（trade）",
                "order_risk_assessment_component.dimension = 'ADDRESS_CONCENTRATION'",
                "TRADE",
                ""),
            new AnomalyOrderCapability(
                AnomalyType.DUPLICATE_ORDER.name(),
                AnomalyType.DUPLICATE_ORDER.label(),
                true,
                "09 订单履约（trade）",
                "order_risk_assessment_component.dimension = 'ORDER_FREQUENCY'",
                "TRADE",
                ""));

    List<AnomalyOrderCrossSource> cross =
        List.of(
            new AnomalyOrderCrossSource(
                AnomalyType.BLACKLIST_BUYER.name(),
                AnomalyType.BLACKLIST_BUYER.label(),
                "16 合规与风控（channel）",
                "GET /channel/buyer-blacklist/flags?buyerKey={buyerKey}",
                "buyer_key —— 收件人三件套的 md5，与 channel 16.5.1.1 逐字同式"),
            new AnomalyOrderCrossSource(
                AnomalyType.INSUFFICIENT_STOCK.name(),
                AnomalyType.INSUFFICIENT_STOCK.label(),
                "07 库存（warehouse）",
                "GET /warehouse/inventory/sellable/skus?skuId={skuId}",
                "sku_id —— sales_order_line.sku_id 与 stock_bucket.sku_id 同一个逻辑商品 id"));

    List<String> notConnected =
        List.of(
            "listing 映射状态：蓝图「共享数据」点名了 listing，"
                + "但 listing 归 02 渠道所有（channel 的 listing 表），"
                + "本服务不跨库读它 ⇒ 清单里的「店铺」取自 platform_order.shop_code / "
                + "shipment_writeback_task.shop_code（发货侧业务键），不是渠道的 listing 映射结论。",
            "平台绩效指标（platform_performance_metric）：本仓没有这张表，也没有模块持有它 ⇒ "
                + "本模块的「平台超时未回传发货」用的是发货回传任务的真实状态，"
                + "与平台侧 ODR / 迟发率无关。");

    List<AnomalyOrderAction> actions =
        List.of(
            new AnomalyOrderAction(
                AnomalyActionCode.OPEN_ORDER.name(),
                AnomalyActionCode.OPEN_ORDER.label(),
                AnomalyActionCode.OPEN_ORDER.ownerModule(),
                true,
                ""),
            new AnomalyOrderAction(
                AnomalyActionCode.ESCALATE_MANUAL.name(),
                AnomalyActionCode.ESCALATE_MANUAL.label(),
                AnomalyActionCode.ESCALATE_MANUAL.ownerModule(),
                true,
                ""),
            new AnomalyOrderAction(
                AnomalyActionCode.TRANSFER_AFTERSALES.name(),
                AnomalyActionCode.TRANSFER_AFTERSALES.label(),
                AnomalyActionCode.TRANSFER_AFTERSALES.ownerModule(),
                true,
                ""),
            new AnomalyOrderAction(
                AnomalyActionCode.QUERY_PLATFORM.name(),
                AnomalyActionCode.QUERY_PLATFORM.label(),
                AnomalyActionCode.QUERY_PLATFORM.ownerModule(),
                true,
                ""),
            new AnomalyOrderAction(
                AnomalyActionCode.OPEN_BLACKLIST.name(),
                AnomalyActionCode.OPEN_BLACKLIST.label(),
                AnomalyActionCode.OPEN_BLACKLIST.ownerModule(),
                true,
                ""),
            new AnomalyOrderAction(
                AnomalyActionCode.OPEN_INVENTORY.name(),
                AnomalyActionCode.OPEN_INVENTORY.label(),
                AnomalyActionCode.OPEN_INVENTORY.ownerModule(),
                true,
                ""),
            // 🔴 唯一一个**归属系统没有写端点**的动作：如实说清楚，不假装能一键忽略。
            new AnomalyOrderAction(
                AnomalyActionCode.MARK_IGNORED.name(),
                AnomalyActionCode.MARK_IGNORED.label(),
                AnomalyActionCode.MARK_IGNORED.ownerModule(),
                false,
                ignoredImpact()));

    return new AnomalyOrderCapabilities(
        dimensions,
        cross,
        notConnected,
        actions,
        PLATFORM_WRITEBACK_TIMEOUT_HOURS,
        BUYER_MESSAGE_TIMEOUT_HOURS,
        now());
  }

  // ================================================================ 选项

  /** 筛选项：异常类型、店铺、订单来源、停留时长档位。 */
  public AnomalyOrderOptions options() {
    List<AnomalyOrderTypeOption> types =
        java.util.Arrays.stream(AnomalyType.values())
            .map(t -> new AnomalyOrderTypeOption(t.name(), t.label(), t.ownerService()))
            .toList();

    List<String> shops =
        sql.fetch(
                CTE
                    + " select distinct coalesce((select p.shop_code from plat p where p.sales_order_id = o.id), "
                    + "                                  (select w.shop_code from wb w where w.sales_order_id = o.id), '') as shop "
                    + "   from agg a join sales_order o on o.id = a.order_id "
                    + "  where coalesce((select p.shop_code from plat p where p.sales_order_id = o.id), "
                    + "                   (select w.shop_code from wb w where w.sales_order_id = o.id), '') <> '' "
                    + "  order by 1")
            .stream()
            .map(r -> r.get(0, String.class))
            .toList();

    List<String> sources =
        sql.fetch(
                CTE
                    + " select distinct o.order_source from agg a join sales_order o on o.id = a.order_id order by 1")
            .stream()
            .map(r -> r.get(0, String.class))
            .toList();

    List<AnomalyOrderDwellPreset> presets =
        List.of(
            new AnomalyOrderDwellPreset("ALL", "不限", 0),
            new AnomalyOrderDwellPreset("H1", "1 小时以上", 1),
            new AnomalyOrderDwellPreset("H6", "6 小时以上", 6),
            new AnomalyOrderDwellPreset("H24", "1 天以上", 24),
            new AnomalyOrderDwellPreset("H72", "3 天以上", 72),
            new AnomalyOrderDwellPreset("H168", "7 天以上", 168));

    return new AnomalyOrderOptions(types, shops, sources, presets, DEFAULT_MIN_DWELL_HOURS, now());
  }

  // ================================================================ 清单

  /**
   * 异常订单清单：真分页 + 真筛选 + 真排序 + 汇总 + 数据截至时间。
   *
   * @param anomalyType 异常类型筛选（**只对本服务侧五类有效**；跨服务两类由前端合并后过滤，
   *     因为本服务读不到 channel / warehouse 的事实）
   * @param minDwellHours 停留时长下限（小时）
   */
  public AnomalyOrderPage page(
      String anomalyType,
      String shopCode,
      String keyword,
      String orderSource,
      Integer minDwellHours,
      String sort,
      int page,
      int size) {
    int pageSize = pageSize(size);
    int pageNo = Math.max(0, page);
    int dwell = minDwellHours == null ? 0 : Math.max(0, minDwellHours);
    String sortKey = notBlank(sort) ? sort.trim() : "dwell";
    if (!SORTS.contains(sortKey)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "排序键只能是 " + String.join(" / ", SORTS.stream().sorted().toList()));
    }

    Filter filter = filter(anomalyType, shopCode, keyword, orderSource, dwell);

    String orderBy =
        switch (sortKey) {
          case "amount" -> " order by o.grand_total_amount desc nulls last, a.oldest_since asc, o.id desc";
          case "orderNo" -> " order by o.order_no asc, o.id desc";
          case "submittedAt" -> " order by o.submitted_at desc nulls last, a.oldest_since asc, o.id desc";
          // 「卡得最久的排最前」：停留时长 = now − oldest_since，时长越长 oldest_since 越小。
          default -> " order by a.oldest_since asc nulls first, o.id desc";
        };

    var rows =
        sql.fetch(
            CTE
                + " select o.id, o.order_no, o.order_source, o.status, o.currency, o.grand_total_amount,"
                + "        o.submitted_at, a.dimension_text, a.oldest_since, a.severity_rank,"
                + "        coalesce((select p.shop_code from plat p where p.sales_order_id = o.id), "
                + "                 (select w.shop_code from wb w where w.sales_order_id = o.id), '') as shop_code,"
                + "        coalesce((select p.platform_code from plat p where p.sales_order_id = o.id), '') as platform_code"
                + "   from agg a join sales_order o on o.id = a.order_id"
                + filter.sql
                + orderBy
                + " limit ? offset ?",
            filter.append(pageSize, (long) pageNo * pageSize));

    long total =
        sql.fetchOne(
                CTE
                    + " select count(*) c from agg a join sales_order o on o.id = a.order_id"
                    + filter.sql,
                filter.binds().toArray())
            .get("c", Long.class);

    List<UUID> orderIds = rows.stream().map(r -> r.get("id", UUID.class)).toList();
    Map<UUID, List<AnomalyOrderSkuNeed>> skuNeeds = skuNeedsOf(orderIds);
    Map<UUID, Integer> lineCounts = lineCountsOf(orderIds);
    Map<UUID, BigDecimal> quantities = quantitiesOf(orderIds);
    Map<UUID, String> buyers = buyerKeysOf(orderIds);

    List<AnomalyOrderListRow> items = new ArrayList<>();
    for (var r : rows) {
      UUID orderId = r.get("id", UUID.class);
      List<String> dimensions = dimensionsOf(r.get("dimension_text", String.class));
      items.add(
          new AnomalyOrderListRow(
              orderId,
              r.get("order_no", String.class),
              r.get("order_source", String.class),
              r.get("shop_code", String.class),
              r.get("platform_code", String.class),
              r.get("currency", String.class),
              money(r.get("grand_total_amount", BigDecimal.class)),
              r.get("status", String.class),
              r.get("submitted_at", OffsetDateTime.class),
              dwellSeconds(r.get("oldest_since", OffsetDateTime.class)),
              r.get("oldest_since", OffsetDateTime.class),
              dimensions,
              dimensions.stream().map(AnomalyOrderService::labelOf).toList(),
              severityOf(r.get("severity_rank", Integer.class)),
              actionsFor(dimensions),
              buyers.getOrDefault(orderId, ""),
              skuNeeds.getOrDefault(orderId, List.of()),
              lineCounts.getOrDefault(orderId, 0),
              quantities.get(orderId)));    }

    AnomalyOrderSummary summary = summary(filter);

    return new AnomalyOrderPage(
        items, pageNo, pageSize, total, ceil(total, pageSize), summary, now());
  }

  // ================================================================ 明细

  /**
   * 订单明细：清单行 + 订单行 + 费用 + 状态留痕 + 逐条异常凭据 + 建议动作。
   *
   * <p>跨租户在 RLS 下查不到 → 404（不是「空对象」：「没查过」与「查过了没问题」是两件事）。
   */
  public AnomalyOrderDetail detail(UUID orderId) {
    var head =
        sql.fetchOne(
            "select id, order_no, order_source, status, currency, grand_total_amount, submitted_at,"
                + "       external_order_no, payment_method"
                + "  from sales_order where tenant_id = "
                + TENANT
                + " and id = ?",
            orderId);
    if (head == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在或不属于本租户：" + orderId);
    }

    List<AnomalyOrderLine> lines =
        sql.fetch(
                "select line_no, sku_id, sku_code, sku_name, spec_snapshot, quantity, unit_price, line_amount"
                    + "  from sales_order_line where tenant_id = "
                    + TENANT
                    + " and order_id = ? order by line_no",
                orderId)
            .map(
                r ->
                    new AnomalyOrderLine(
                        r.get("line_no", Integer.class),
                        r.get("sku_id", UUID.class),
                        r.get("sku_code", String.class),
                        r.get("sku_name", String.class),
                        r.get("spec_snapshot", String.class),
                        quantity(r.get("quantity", BigDecimal.class)),
                        money(r.get("unit_price", BigDecimal.class)),
                        money(r.get("line_amount", BigDecimal.class))));

    List<AnomalyOrderCharge> charges =
        sql.fetch(
                "select charge_type, label, amount, charge_kind, note"
                    + "  from order_charge where tenant_id = "
                    + TENANT
                    + " and order_id = ? order by charge_type, id",
                orderId)
            .map(
                r ->
                    new AnomalyOrderCharge(
                        r.get("charge_type", String.class),
                        r.get("label", String.class),
                        money(r.get("amount", BigDecimal.class)),
                        r.get("charge_kind", String.class),
                        r.get("note", String.class)));

    List<AnomalyOrderStatusEvent> history =
        sql.fetch(
                "select event_type, from_status, to_status, reason, occurred_at"
                    + "  from sales_order_status_history where tenant_id = "
                    + TENANT
                    + " and order_id = ? order by occurred_at, id",
                orderId)
            .map(
                r ->
                    new AnomalyOrderStatusEvent(
                        r.get("event_type", String.class),
                        r.get("from_status", String.class),
                        r.get("to_status", String.class),
                        r.get("reason", String.class),
                        r.get("occurred_at", OffsetDateTime.class)));

    var buyer =
        sql.fetchOne(
            "select recipient_name, recipient_phone, recipient_email, country_code, address_line1,"
                + "       city, province, postcode,"
                + "       md5(concat_ws(E'\\x1f', recipient_name, recipient_phone, recipient_email)) as buyer_key"
                + "  from order_address_snapshot where tenant_id = "
                + TENANT
                + " and order_id = ? and address_role = 'SHIPPING'",
            orderId);

    var shop =
        sql.fetchOne(
            CTE
                + " select coalesce((select p.shop_code from plat p where p.sales_order_id = o.id), "
                + "                 (select w.shop_code from wb w where w.sales_order_id = o.id), '') as shop_code,"
                + "        coalesce((select p.platform_code from plat p where p.sales_order_id = o.id), '') as platform_code"
                + "   from sales_order o where o.id = ?",
            orderId);

    List<AnomalyOrderEvidence> evidence = evidenceOf(orderId);

    List<String> dimensions = evidence.stream().map(AnomalyOrderEvidence::dimension).toList();
    List<AnomalyOrderAction> actions =
        actionsFor(dimensions).stream()
            .map(
                code -> {
                  AnomalyActionCode c = AnomalyActionCode.valueOf(code);
                  boolean available = c != AnomalyActionCode.MARK_IGNORED;
                  return new AnomalyOrderAction(
                      c.name(),
                      c.label(),
                      c.ownerModule(),
                      available,
                      available ? "" : ignoredImpact());
                })
            .toList();

    AnomalyOrderListRow row =
        new AnomalyOrderListRow(
            orderId,
            head.get("order_no", String.class),
            head.get("order_source", String.class),
            shop == null ? "" : nz(shop.get("shop_code", String.class)),
            shop == null ? "" : nz(shop.get("platform_code", String.class)),
            head.get("currency", String.class),
            money(head.get("grand_total_amount", BigDecimal.class)),
            head.get("status", String.class),
            head.get("submitted_at", OffsetDateTime.class),
            evidence.stream().mapToLong(AnomalyOrderEvidence::dwellSeconds).max().orElse(0L),
            evidence.stream()
                .min(Comparator.comparing(AnomalyOrderEvidence::since, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(AnomalyOrderEvidence::since)
                .orElse(null),
            dimensions,
            dimensions.stream().map(AnomalyOrderService::labelOf).toList(),
            evidence.stream()
                .map(e -> severityRank(e.severity()))
                .max(Comparator.naturalOrder())
                .map(AnomalyOrderService::severityOf)
                .orElse(AnomalySeverity.LOW.name()),
            actionsFor(dimensions),
            buyer == null ? "" : nz(buyer.get("buyer_key", String.class)),
            lines.stream()
                .map(
                    line ->
                        new AnomalyOrderSkuNeed(
                            line.skuId(), line.skuCode(), line.quantity()))
                .distinct()
                .toList(),
            lines.size(),
            lines.stream().map(AnomalyOrderLine::quantity).reduce(BigDecimal.ZERO, BigDecimal::add));

    return new AnomalyOrderDetail(
        row,
        lines,
        charges,
        history,
        evidence,
        actions,
        buyer == null ? "" : nz(buyer.get("recipient_name", String.class)),
        buyer == null ? "" : nz(buyer.get("recipient_phone", String.class)),
        buyer == null ? "" : nz(buyer.get("recipient_email", String.class)),
        buyer == null ? "" : nz(buyer.get("country_code", String.class)),
        buyer == null ? "" : shippingAddress(buyer),
        now());
  }

  // ================================================================ 凭据

  /** 逐条异常凭据：每一条都带起点、停留时长、人话摘要与明细事实。 */
  private List<AnomalyOrderEvidence> evidenceOf(UUID orderId) {
    List<AnomalyOrderEvidence> out = new ArrayList<>();

    // 1. 平台超时未回传发货
    for (var r :
        sql.fetch(
                "select w.task_no, w.platform_code, w.shop_code, w.status, w.verification,"
                    + "       w.attempt_count, w.max_attempts, w.last_error, w.created_at, w.updated_at,"
                    + "       w.sales_order_no"
                    + "  from shipment_writeback_task w"
                    + "  join shipment_record r on r.tenant_id = w.tenant_id and r.id = w.source_record_id"
                    + " where w.tenant_id = "
                    + TENANT
                    + "   and r.sales_order_id = ?"
                    + "   and w.status in ('PENDING','SUBMITTED','PENDING_VERIFICATION','REJECTED')"
                    + "   and w.created_at <= now() - make_interval(hours => "
                    + PLATFORM_WRITEBACK_TIMEOUT_HOURS
                    + ")"
                    + " order by w.created_at",
                orderId)) {
      out.add(
          new AnomalyOrderEvidence(
              AnomalyType.PLATFORM_SHIPMENT_TIMEOUT.name(),
              AnomalyType.PLATFORM_SHIPMENT_TIMEOUT.label(),
              "HIGH",
              r.get("created_at", OffsetDateTime.class),
              dwellSeconds(r.get("created_at", OffsetDateTime.class)),
              "平台侧迟迟没有确认这张单的发货回传",
              List.of(
                  "回传任务 " + nz(r.get("task_no", String.class)),
                  "平台 " + nz(r.get("platform_code", String.class))
                      + " · 店铺 " + nz(r.get("shop_code", String.class)),
                  "任务状态 " + nz(r.get("status", String.class))
                      + " · 查证结论 " + nz(r.get("verification", String.class)),
                  "已尝试 " + nz(r.get("attempt_count", Integer.class))
                      + " / 上限 " + nz(r.get("max_attempts", Integer.class)) + " 次",
                  lastError(r.get("last_error", String.class)))));
    }

    // 2. 地址不可达
    var addr =
        sql.fetchOne(
            "select id, order_no, delivery_status, risk_level, cod_flagged, assessed_reference_at,"
                + "       shipping_country_code, item_count, flagged_count, blocked_count, warn_count,"
                + "       critical_count, policy_name, policy_version"
                + "  from order_address_check where tenant_id = "
                + TENANT
                + " and order_id = ? order by assessed_reference_at desc, id desc limit 1",
            orderId);
    if (addr != null && "NOT_DELIVERABLE".equals(addr.get("delivery_status", String.class))) {
      out.add(
          new AnomalyOrderEvidence(
              AnomalyType.ADDRESS_UNDELIVERABLE.name(),
              AnomalyType.ADDRESS_UNDELIVERABLE.label(),
              "HIGH",
              addr.get("assessed_reference_at", OffsetDateTime.class),
              dwellSeconds(addr.get("assessed_reference_at", OffsetDateTime.class)),
              "收货地址被判为不可投递",
              List.of(
                  "校验结论 " + nz(addr.get("delivery_status", String.class))
                      + " · 风险等级 " + nz(addr.get("risk_level", String.class)),
                  "收货国 " + nz(addr.get("shipping_country_code", String.class)),
                  "命中 " + nz(addr.get("flagged_count", Integer.class))
                      + " / " + nz(addr.get("item_count", Integer.class))
                      + " 项（BLOCK "
                      + nz(addr.get("blocked_count", Integer.class))
                      + " · WARN "
                      + nz(addr.get("warn_count", Integer.class))
                      + " · CRITICAL "
                      + nz(addr.get("critical_count", Integer.class))
                      + "）",
                  "货到付款 " + nz(addr.get("cod_flagged", Boolean.class)),
                  "判定策略 " + nz(addr.get("policy_name", String.class))
                      + " v" + nz(addr.get("policy_version", Long.class)))));
    }

    // 3. 买家留言待确认
    var plat =
        sql.fetchOne(
            "select platform_code, shop_code, external_order_no, platform_status, normalized_status,"
                + "       platform_placed_at, platform_updated_at,"
                + "       (platform_placed_at is not null"
                + "        and platform_placed_at <= now() - make_interval(hours => "
                + BUYER_MESSAGE_TIMEOUT_HOURS
                + ")) as stuck"
                + "  from platform_order where tenant_id = "
                + TENANT
                + " and sales_order_id = ?",
            orderId);
    if (plat != null
        && "PENDING_CONFIRMATION".equals(plat.get("normalized_status", String.class))
        && Boolean.TRUE.equals(plat.get("stuck", Boolean.class))) {
      out.add(
          new AnomalyOrderEvidence(
              AnomalyType.BUYER_MESSAGE_PENDING.name(),
              AnomalyType.BUYER_MESSAGE_PENDING.label(),
              "MEDIUM",
              plat.get("platform_placed_at", OffsetDateTime.class),
              dwellSeconds(plat.get("platform_placed_at", OffsetDateTime.class)),
              "平台单还没确认，买家的留言 / 待确认事项一直挂着",
              List.of(
                  "平台 " + nz(plat.get("platform_code", String.class))
                      + " · 店铺 " + nz(plat.get("shop_code", String.class)),
                  "平台单号 " + nz(plat.get("external_order_no", String.class)),
                  "平台原始状态 " + nz(plat.get("platform_status", String.class))
                      + " · 归一状态 " + nz(plat.get("normalized_status", String.class)),
                  "平台下单时刻 " + nz(plat.get("platform_placed_at", OffsetDateTime.class)))));
    }

    // 4 / 5. 风控评估的两个维度
    var risk =
        sql.fetchOne(
            "select id, order_no, risk_score, recommended_action, manual_review_required,"
                + "       evaluation_status, final_action, evaluated_reference_at, rule_name, rule_version"
                + "  from order_risk_assessment where tenant_id = "
                + TENANT
                + " and order_id = ? order by evaluated_reference_at desc, id desc limit 1",
            orderId);
    if (risk != null) {
      for (var r :
          sql.fetch(
                  "select dimension, status, raw_value, normalized_score, contribution, signal_source,"
                      + "       explanation"
                      + "  from order_risk_assessment_component where tenant_id = "
                      + TENANT
                      + " and assessment_id = ? and dimension in ('ORDER_FREQUENCY','ADDRESS_CONCENTRATION')"
                      + " order by dimension",
                  risk.get("id", UUID.class))) {
        String dimension = r.get("dimension", String.class);
        if (!"SCORED".equals(r.get("status", String.class))) continue;
        BigDecimal contribution = r.get("contribution", BigDecimal.class);
        if (contribution == null || contribution.signum() <= 0) continue;
        boolean address = "ADDRESS_CONCENTRATION".equals(dimension);
        AnomalyType type = address ? AnomalyType.ADDRESS_FRAUD_SUSPECTED : AnomalyType.DUPLICATE_ORDER;
        out.add(
            new AnomalyOrderEvidence(
                type.name(),
                type.label(),
                address ? "HIGH" : "MEDIUM",
                risk.get("evaluated_reference_at", OffsetDateTime.class),
                dwellSeconds(risk.get("evaluated_reference_at", OffsetDateTime.class)),
                address
                    ? "同一收货地址反复下单，疑似地址欺诈"
                    : "同一买家的下单频率异常，疑似重复下单",
                List.of(
                    "评估 " + nz(risk.get("order_no", String.class))
                        + " · 风险分 " + nz(risk.get("risk_score", BigDecimal.class)),
                    "风控建议 " + nz(risk.get("recommended_action", String.class))
                        + " · 是否需人工复核 "
                        + nz(risk.get("manual_review_required", Boolean.class))
                        + " · 最终动作 " + nz(risk.get("final_action", String.class)),
                    "该维度原始值 " + nz(r.get("raw_value", BigDecimal.class))
                        + " · 归一分 " + nz(r.get("normalized_score", BigDecimal.class))
                        + " · 贡献 " + nz(contribution),
                    "信号来源 " + nz(r.get("signal_source", String.class)),
                    nz(r.get("explanation", String.class)))));
      }
    }

    return out;
  }

  // ================================================================ 内部

  /** 一份筛选条件（SQL 片段 + 位置参数）。每条查询各持一份，避免参数串位。 */
  private record Filter(String sql, List<Object> binds) {
    Object[] append(Object... more) {
      List<Object> all = new ArrayList<>(binds);
      all.addAll(List.of(more));
      return all.toArray();
    }
  }

  /** 筛选条件拼装（清单 / 汇总共用同一份，保证口径一致）。 */
  private Filter filter(
      String anomalyType, String shopCode, String keyword, String orderSource, int dwell) {
    List<Object> binds = new ArrayList<>();
    StringBuilder w = new StringBuilder(" where 1=1 ");
    if (notBlank(anomalyType)) {
      String type = anomalyType.trim().toUpperCase(Locale.ROOT);
      boolean known =
          java.util.Arrays.stream(AnomalyType.values()).anyMatch(t -> t.name().equals(type));
      if (!known) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "异常类型不存在：" + type);
      }
      w.append(" and exists (select 1 from unnest(a.dimensions) d where d = ?)");
      binds.add(type);
    }
    if (notBlank(shopCode)) {
      w.append(
          " and coalesce((select p.shop_code from plat p where p.sales_order_id = o.id),"
              + "              (select w.shop_code from wb w where w.sales_order_id = o.id), '') = ?");
      binds.add(shopCode.trim());
    }
    if (notBlank(keyword)) {
      w.append(" and (o.order_no ilike ? or o.external_order_no ilike ?)");
      String k = "%" + keyword.trim() + "%";
      binds.add(k);
      binds.add(k);
    }
    if (notBlank(orderSource)) {
      w.append(" and o.order_source = ?");
      binds.add(orderSource.trim().toUpperCase(Locale.ROOT));
    }
    if (dwell > 0) {
      w.append(" and now() - a.oldest_since >= make_interval(hours => " + dwell + ")");
    }
    return new Filter(w.toString(), binds);
  }

  /**
   * 本服务侧五类信号的合计。
   *
   * <p>🔴 <b>不含</b>跨服务两类（黑名单 / 库存）—— 那两类的事实不在本服务，
   * 由前端合并层统计。<b>跨服务两类在本接口里恒为 0</b>，不是「没有这种异常」。
   */
  private AnomalyOrderSummary summary(Filter filter) {
    var row =
        sql.fetchOne(
            CTE
                + " select count(*) as total,"
                + "        count(*) filter (where exists (select 1 from unnest(a.dimensions) d where d = 'PLATFORM_SHIPMENT_TIMEOUT')) as platform_timeout,"
                + "        count(*) filter (where exists (select 1 from unnest(a.dimensions) d where d = 'ADDRESS_UNDELIVERABLE')) as address_bad,"
                + "        count(*) filter (where exists (select 1 from unnest(a.dimensions) d where d = 'BUYER_MESSAGE_PENDING')) as message_pending,"
                + "        count(*) filter (where exists (select 1 from unnest(a.dimensions) d where d = 'ADDRESS_FRAUD_SUSPECTED')) as fraud,"
                + "        count(*) filter (where exists (select 1 from unnest(a.dimensions) d where d = 'DUPLICATE_ORDER')) as duplicate,"
                + "        coalesce(max(extract(epoch from (now() - a.oldest_since))), 0)::bigint as oldest"
                + "   from agg a join sales_order o on o.id = a.order_id"
                + filter.sql,
            filter.binds().toArray());
    long total = row.get("total", Long.class);
    Map<String, Long> byShop = new LinkedHashMap<>();
    for (var r :
        sql.fetch(
            CTE
                + " select coalesce((select p.shop_code from plat p where p.sales_order_id = o.id),"
                + "                 (select w.shop_code from wb w where w.sales_order_id = o.id), '') as shop,"
                + "        count(*) as c"
                + "   from agg a join sales_order o on o.id = a.order_id"
                + filter.sql
                + "  group by 1 order by 1",
            filter.binds().toArray())) {
      byShop.put(r.get("shop", String.class), r.get("c", Long.class));
    }
    return new AnomalyOrderSummary(
        total,
        row.get("platform_timeout", Long.class),
        row.get("address_bad", Long.class),
        row.get("message_pending", Long.class),
        0,
        0,
        row.get("fraud", Long.class),
        row.get("duplicate", Long.class),
        row.get("oldest", Long.class),
        byShop);
  }

  /** 订单行上的 SKU 与所需数量（前端用它连 warehouse 的可售量）。 */
  private Map<UUID, List<AnomalyOrderSkuNeed>> skuNeedsOf(List<UUID> orderIds) {
    Map<UUID, List<AnomalyOrderSkuNeed>> out = new LinkedHashMap<>();
    for (var r : lineRows(orderIds)) {
      out.computeIfAbsent(r.get("order_id", UUID.class), k -> new ArrayList<>())
          .add(
              new AnomalyOrderSkuNeed(
                  r.get("sku_id", UUID.class),
                  r.get("sku_code", String.class),
                  quantity(r.get("quantity", BigDecimal.class))));
    }
    return out;
  }

  /** 每张单的订单行条数。 */
  private Map<UUID, Integer> lineCountsOf(List<UUID> orderIds) {
    Map<UUID, Integer> out = new LinkedHashMap<>();
    for (var r : lineRows(orderIds)) {
      out.merge(r.get("order_id", UUID.class), 1, Integer::sum);
    }
    return out;
  }

  /** 每张单的订单行数量合计。 */
  private Map<UUID, BigDecimal> quantitiesOf(List<UUID> orderIds) {
    Map<UUID, BigDecimal> out = new LinkedHashMap<>();
    for (var r : lineRows(orderIds)) {
      out.merge(
          r.get("order_id", UUID.class),
          r.get("quantity", BigDecimal.class),
          (a, b) -> a.add(b));
    }
    return out;
  }

  private List<org.jooq.Record> lineRows(List<UUID> orderIds) {
    if (orderIds.isEmpty()) return List.of();
    String in = orderIds.stream().map(x -> "?").collect(Collectors.joining(","));
    return sql.fetch(
        "select order_id, sku_id, sku_code, quantity from sales_order_line where tenant_id = "
            + TENANT
            + " and order_id in ("
            + in
            + ")",
        orderIds.toArray());
  }

  /** 买家身份键（收件人三件套的 md5，与 channel 16.5.1.1 **逐字同式**）。 */
  private Map<UUID, String> buyerKeysOf(List<UUID> orderIds) {
    Map<UUID, String> out = new LinkedHashMap<>();
    if (orderIds.isEmpty()) return out;
    String in = orderIds.stream().map(x -> "?").collect(Collectors.joining(","));
    for (var r :
        sql.fetch(
            "select order_id, md5(concat_ws(E'\\x1f', recipient_name, recipient_phone, recipient_email)) as buyer_key"
                + "  from order_address_snapshot where tenant_id = "
                + TENANT
                + " and address_role = 'SHIPPING' and order_id in ("
                + in
                + ")",
            orderIds.toArray())) {
      out.put(r.get("order_id", UUID.class), r.get("buyer_key", String.class));
    }
    return out;
  }

  /** 异常类型 → 建议动作（**只给归属系统的入口**，工作台自己不写业务数据）。 */
  static List<String> actionsFor(List<String> dimensions) {
    Set<String> codes = new LinkedHashSet<>();
    for (String d : dimensions) {
      switch (d) {
        case "PLATFORM_SHIPMENT_TIMEOUT" -> {
          codes.add(AnomalyActionCode.QUERY_PLATFORM.name());
          codes.add(AnomalyActionCode.ESCALATE_MANUAL.name());
        }
        case "BUYER_MESSAGE_PENDING" -> {
          codes.add(AnomalyActionCode.QUERY_PLATFORM.name());
          codes.add(AnomalyActionCode.ESCALATE_MANUAL.name());
        }
        case "ADDRESS_UNDELIVERABLE" -> {
          codes.add(AnomalyActionCode.OPEN_ORDER.name());
          codes.add(AnomalyActionCode.ESCALATE_MANUAL.name());
        }
        case "ADDRESS_FRAUD_SUSPECTED" -> {
          codes.add(AnomalyActionCode.OPEN_ORDER.name());
          codes.add(AnomalyActionCode.ESCALATE_MANUAL.name());
        }
        case "DUPLICATE_ORDER" -> {
          codes.add(AnomalyActionCode.OPEN_ORDER.name());
          codes.add(AnomalyActionCode.ESCALATE_MANUAL.name());
        }
        case "BLACKLIST_BUYER" -> {
          codes.add(AnomalyActionCode.OPEN_BLACKLIST.name());
          codes.add(AnomalyActionCode.ESCALATE_MANUAL.name());
        }
        case "INSUFFICIENT_STOCK" -> {
          codes.add(AnomalyActionCode.OPEN_INVENTORY.name());
          codes.add(AnomalyActionCode.ESCALATE_MANUAL.name());
        }
        default -> codes.add(AnomalyActionCode.OPEN_ORDER.name());
      }
    }
    return List.copyOf(codes);
  }

  static String labelOf(String dimension) {
    try {
      return AnomalyType.valueOf(dimension).label();
    } catch (IllegalArgumentException e) {
      return dimension;
    }
  }

  static String severityOf(Integer rank) {
    if (rank == null) return AnomalySeverity.LOW.name();
    return switch (rank) {
      case 4 -> AnomalySeverity.CRITICAL.name();
      case 3 -> AnomalySeverity.HIGH.name();
      case 2 -> AnomalySeverity.MEDIUM.name();
      default -> AnomalySeverity.LOW.name();
    };
  }

  static int severityRank(String severity) {
    if (severity == null) return 1;
    return switch (severity) {
      case "CRITICAL" -> 4;
      case "HIGH" -> 3;
      case "MEDIUM" -> 2;
      default -> 1;
    };
  }

  private static List<String> dimensionsOf(String dimensionText) {
    if (dimensionText == null || dimensionText.isBlank()) return List.of();
    List<String> out = new ArrayList<>();
    for (String part : dimensionText.split(",")) {
      if (!part.isBlank()) out.add(part.trim());
    }
    return out;
  }

  private static String shippingAddress(org.jooq.Record r) {
    List<String> parts = new ArrayList<>();
    for (String key : List.of("address_line1", "city", "province", "postcode")) {
      String v = nz(r.get(key, String.class));
      if (!v.isBlank()) parts.add(v);
    }
    return String.join(" ", parts);
  }

  private static String lastError(String error) {
    String v = nz(error);
    return v.isBlank() ? "最近一次尝试没有留下错误信息" : "最近一次错误：" + v;
  }

  private static String ignoredImpact() {
    return "归属系统当前没有「忽略一条异常」的写端点"
        + "（09 异常拦截的 order_intercept_disposition 只覆盖异常拦截的判定结论），"
        + "而卷 01 明确「工作台不写业务数据」⇒ 本轮无法一键落地，只能转到归属系统人工处理。";
  }

  private static long dwellSeconds(OffsetDateTime since) {
    if (since == null) return 0L;
    return Math.max(0L, Duration.between(since, OffsetDateTime.now(ZoneOffset.UTC)).getSeconds());
  }

  private static int pageSize(int requested) {
    return Math.min(MAX_PAGE_SIZE, Math.max(1, requested <= 0 ? 50 : requested));
  }

  private static int ceil(long total, int size) {
    return (int) Math.ceil(total / (double) size);
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String nz(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  /** 金额刻度：4 位，HALF_UP（与 DB 的 numeric(19,4) 对齐）。 */
  private static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
  }

  /** 数量刻度：6 位（与 DB 的 numeric(19,6) 对齐）。 */
  private static BigDecimal quantity(BigDecimal value) {
    return value == null ? null : value.setScale(6, RoundingMode.HALF_UP);
  }

  /** 数据截至时间：取数据库时钟（与 SQL 里的 now() 同一口径）。 */
  private String now() {
    return sql.fetchOne("select now() as n").get("n", OffsetDateTime.class).toString();
  }
}
