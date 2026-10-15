package com.starcon.trade;

import static com.starcon.trade.WaybillModels.*;

import com.starcon.trade.CarrierLabelTemplatePort.LabelTemplateView;
import com.starcon.trade.CarrierWaybillPort.DeclarationLine;
import com.starcon.trade.CarrierWaybillPort.Resolution;
import com.starcon.trade.CarrierWaybillPort.WaybillOutcome;
import com.starcon.trade.CarrierWaybillPort.WaybillSubmission;
import com.starcon.trade.CustomsAttributePort.CustomsAttributeView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 10.2.1.2 运单申请。
 *
 * <p><b>七条实现纪律</b>（照抄 9.4.1.1 的写法，不另起风格）：
 *
 * <ol>
 *   <li><b>幂等键是输入契约</b>：蓝图输入句第四项写的是 {@code idempotency_key}。
 *       DB 层 {@code UNIQUE(tenant_id, idempotency_key)} 钉死；重复提交返回
 *       <b>原来那一张</b>并带 {@code idempotentReplay=true}，不是造第二张。</li>
 *   <li><b>限流排队是持久化队列</b>，不是同步阻塞循环。一次提交全部入队，
 *       之后由 {@link #dispatch} 按渠道窗口<b>逐条</b>放行；蓝图那句
 *       「批量申请要按渠道限流排队，否则触发接口限流影响整批」两半都做到了：
 *       入队不阻塞、窗口按承运商算。</li>
 *   <li><b>一次请求不许跨承运商</b>（照抄 9.4.1.1 已验证的做法）：
 *       A 家与 B 家的配额互不相通，混在一次请求里没法算每一票等哪个窗口。</li>
 *   <li><b>不造运单号</b>：{@code ISSUED} 只能由承运商适配器<b>真实返回</b>时进入。
 *       10.1 未交付时适配器解析不出来，这一票就 FAILED 并写明「渠道未接通」，
 *       而不是发一个编出来的号。DB 层 CHECK 与触发器各钉一道。</li>
 *   <li><b>申报明细必须自洽</b>：DB CHECK 逐行验 {@code 单价 × 数量 = 申报额}，
 *       触发器验头尾合计一致。没有明细的申报单承运商与海关都会拒收（10.5：扣货与罚款）。</li>
 *   <li><b>跨服务只走授权 API</b>：申报走 3.7.1.1（catalog）、面单模板走 8.3.4.2
 *       （warehouse），形态与 {@link PackingQueryPort} 逐字同构；取不到就 503，
 *       绝不返回空申报冒充「不用报关」。</li>
 *   <li><b>时间绑定</b>：{@code ?::timestamptz} 写在 values 的对应位置上，
 *       全程 {@code OffsetDateTime}，不用 {@code Timestamp.valueOf(LocalDateTime)}。</li>
 * </ol>
 */
@Service
public class WaybillApplicationService {

  /** 单页最大行数（与 9.4.1.1 同一上限）。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 单次批量最多几个包裹 —— 一次拉爆会把请求线程占死。 */
  static final int MAX_BATCH = 100;

  /** 单次放行最多几条 —— 放行是逐条调承运商的，必须有上限。 */
  static final int MAX_DISPATCH = 50;

  /** 申报明细行数上限（防止一个包裹塞一千行明细把面单撑爆）。 */
  static final int MAX_DECLARATION_ITEMS = 50;

  private static final String REQUEST_COLUMNS =
      "r.id,r.request_no,r.idempotency_key,r.pending_unit_id,r.pending_parcel_id,r.outbound_order_no,"
          + "r.sales_order_no,r.warehouse_ref,r.carrier_code,r.carrier_channel,r.ship_from_name,"
          + "r.ship_from_country,r.ship_to_name,r.ship_to_country,r.ship_to_address_line,r.ship_to_city,"
          + "r.ship_to_postal_code,r.gross_weight_kg,r.length_mm,r.width_mm,r.height_mm,"
          + "r.declared_currency,r.declared_value_total,r.customs_source,r.label_template_ref,r.status,"
          + "r.waybill_no,r.label_format,r.label_uri,r.failure_code,r.failure_reason,r.cancel_reason,"
          + "r.attempt_count,r.retry_count,r.not_before,r.issued_at,r.created_at";

  private static final String QUEUE_COLUMNS =
      "q.id,q.request_id,q.carrier_code,q.status,q.scheduled_at,q.dispatched_at,q.attempt_count,"
          + "q.reschedule_count,q.last_failure_reason";

  private static final String ATTEMPT_COLUMNS =
      "a.id,a.request_id,a.attempt_no,a.outcome,a.failure_reason,a.carrier_request_ref,"
          + "a.waybill_no,a.attempted_at";

  private static final String EVENT_COLUMNS =
      "e.id,e.request_id,e.queue_entry_id,e.event_kind,e.from_status,e.to_status,e.detail,e.occurred_at";

  private static final List<String> CHANNEL_LABELS =
      List.of(
          "PLATFORM_INTEGRATED",
          "THIRD_PARTY",
          "SELF_PICKUP",
          "POSTAL",
          "FREIGHT_FORWARDER");

  private final DSLContext sql;
  private final CarrierAdapterRegistry adapters;
  private final CustomsAttributePort customs;
  private final CarrierLabelTemplatePort labels;

  public WaybillApplicationService(
      DSLContext sql,
      CarrierAdapterRegistry adapters,
      CustomsAttributePort customs,
      CarrierLabelTemplatePort labels) {
    this.sql = sql;
    this.adapters = adapters;
    this.customs = customs;
    this.labels = labels;
  }

  /**
   * 限流窗口的**可解释**时刻。
   *
   * <p>刻意做成纯函数并接收 {@code base}（入队基准时刻）而不是直接 {@code now()}：
   * 队列要回答的是「第 k 条该等哪一个窗口」，这个答案必须能从
   * 「基准时刻 + 序号 + 每窗口上限」复算出来。写成 {@code now() + 随机抖动}
   * 就没法解释「为什么这一条等了 3 分钟」。
   *
   * <p>⚠️ 不引入随机抖动：抖动会把「这一条排第几」变成猜的，
   * 而大促期现场要靠队序列位回答「还有多久轮到我」。
   */
  private static OffsetDateTime scheduledFor(OffsetDateTime base, int slot, int perWindow, int windowSeconds) {
    long windowIndex = Math.floorDiv(slot, perWindow);
    return base.plusSeconds((long) windowIndex * windowSeconds);
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：<b>逐条</b>说清哪个维度真能用、哪个没有上游。
   *
   * <p>本方法存在的理由是 10.1 整卷未交付：一个「什么都能做」的运单申请页面
   * 会让运营在大促期点下去才发现拿不到单号，而那正是承运商接口最不该被打的时候。
   */
  public WaybillCapabilities capabilities() {
    var connected = adapters.connectedAdapterKeys();
    return new WaybillCapabilities(
        List.of(
            new WaybillCapabilityDimension(
                "PENDING_PARCEL",
                "待发货包裹（实测重量与尺寸）",
                true,
                "同服务同库读 9.4.1.1 的 shipment_pending_parcel / shipment_pending_unit"
                    + "（V124）。**只读**：V124 由 9.4.1.1 单独写入，本模块不改它的任何一张表",
                null,
                "批量打单的对象是包裹，所以本模块也逐包裹一张申请；"
                    + "按出库单申请会让第二个包裹拿不到自己的面单"),
            new WaybillCapabilityDimension(
                "CUSTOMS_DECLARATION",
                "申报信息（HS 编码与申报要素）",
                true,
                "3.7.1.1 的授权只读接口 GET /catalog/customs-attributes/skus/{skuId}，"
                    + "经 CustomsAttributePort 调用；**不直连 catalog 库、不建跨库视图**",
                null,
                "只接受 declarationReady=true 的报关属性：草稿态的 HS 编码承运商与海关都会拒收"),
            new WaybillCapabilityDimension(
                "LABEL_TEMPLATE",
                "面单模板（8.3.4.2）",
                true,
                "8.3.4.2 的授权只读接口 GET /warehouse/inventory/printing/templates，"
                    + "经 CarrierLabelTemplatePort 调用",
                null,
                "本模块只记 template_ref，**不渲染 ZPL、不建第二个打印引擎**"),
            new WaybillCapabilityDimension(
                "CARRIER_CONNECTION",
                "承运商连接与渠道配置",
                false,
                "10.1 物流渠道全部未交付：10.1.1.1 承运商列表 / 10.1.1.2 渠道配置 / "
                    + "10.1.1.3 时效与限制 / 10.1.2.1 运费计算器 / 10.1.2.2 渠道比价",
                "10.1.1.1",
                "没有任何渠道真正接通，因此**申请会入队并按窗口排队，但提交时如实失败并写明"
                    + "「渠道未接通」**；本模块绝不生成一个编出来的运单号。"
                    + "承运商登记（代码 / 渠道 / 限流口径）仍可维护，接通后由 10.1 喂进来即可"),
            new WaybillCapabilityDimension(
                "CARRIER_RATE_LIMIT_CONFIG",
                "渠道接口限流配额",
                false,
                "10.1.1.3 时效与限制未交付（承运商侧的配额与限制是它的职责）",
                "10.1.1.3",
                "本模块用 logistics_carrier 上可登记的限流口径排队，"
                    + "未登记时用迁移内置默认值（waybill_channel_rate_limit 视图，单一真相）；"
                    + "配额数字待 10.1.1.3 接通后由它喂入"),
            new WaybillCapabilityDimension(
                "WAYBILL_CANCEL",
                "运单取消与改址",
                false,
                "10.2.1.3 未交付",
                "10.2.1.3",
                "本模块只允许作废**还没出号**的申请；已出号的要等 10.2.1.3 的取消"),
            new WaybillCapabilityDimension(
                "TRACKING",
                "运单轨迹",
                false,
                "10.3.1.1 未交付",
                "10.3.1.1",
                "本模块只管「号有没有拿到」，轨迹与异常件不归这里"),
            new WaybillCapabilityDimension(
                "RATE_PREDICTION",
                "运费与时效预估",
                false,
                "10.1.2.1 运费计算器 / 10.1.2.2 渠道比价未交付",
                "10.1.2.1",
                "本模块不预估运费，也不比价；申请前看不到价格是已知缺口，不是本模块假装能算")),
        Arrays.stream(WaybillRequestStatus.values()).map(Enum::name).toList(),
        Arrays.stream(WaybillQueueStatus.values()).map(Enum::name).toList(),
        Arrays.stream(WaybillAttemptOutcome.values()).map(o -> o.name() + ":" + o.displayName).toList(),
        CHANNEL_LABELS,
        "限流按承运商分别排队：同一承运商在一个窗口内最多 rate_limit_per_window 次提交，"
            + "超出部分按窗口顺延；未登记的承运商用迁移内置默认值，不按「无限流」处理",
        "幂等键 UNIQUE(tenant_id, idempotency_key) 由 DB 层钉死："
            + "重复提交同一次申请返回原来那一张并标 idempotentReplay，不会产生第二张运单",
        "本模块只到「拿到运单号」：承运商拒收 / 限流 / 不可达都留失败原因并可重试，"
            + "但**不产生发货事实**（那属于 8.3.4.1），也不改 9.4.1.1 的单元状态");
  }

  // ================================================================ 承运商登记

  public WaybillCarrierPage carriers(
      String carrierCode, Boolean connected, String sort, int page, int size) {
    int safePage = pageNumber(page);
    int safeSize = pageSize(size);
    List<Object> params = new ArrayList<>();
    // RLS 已在本事务内按 app.current_tenant 过滤，所以读查询不带 tenant_id 谓词
    // （与 9.4.1.1 的 "where id = ?" 同一口径）；再拼一次是重复口径。
    StringBuilder where = new StringBuilder("");
    if (notBlank(carrierCode)) {
      where.append(" where lower(code) like lower(?)");
      params.add("%" + carrierCode.trim() + "%");
    }
    if (connected != null) {
      where.append(where.length() == 0 ? " where connected = ?" : " and connected = ?");
      params.add(connected);
    }
    String order = sortOrder(sort, "registered_at", "code");
    long total =
        sql.fetchOne("select count(*) from logistics_carrier" + where, params.toArray())
            .get(0, Long.class);

    var rows =
        sql.fetch(
            "select id, code, display_name, carrier_channel, adapter_key, connected,"
                + " unavailable_reason, rate_limit_per_window, rate_window_seconds,"
                + " max_retry_attempts, remark"
                + " from logistics_carrier"
                + where
                + " order by "
                + order
                + " limit ? offset ?",
            bindings(params, safeSize, offsetOf(page, safeSize)));

    var connectedKeys = adapters.connectedAdapterKeys();
    var items = new ArrayList<WaybillCarrierRow>();
    for (Record r : rows) {
      var key = r.get("adapter_key", String.class);
      var code = r.get("code", String.class);
      var isConnected = r.get("connected", Boolean.class);
      var adapterAvailable = connectedKeys.contains(key);
      items.add(
          new WaybillCarrierRow(
              r.get("id", UUID.class),
              code,
              r.get("display_name", String.class),
              r.get("carrier_channel", String.class),
              channelLabel(r.get("carrier_channel", String.class)),
              key,
              isConnected,
              r.get("unavailable_reason", String.class),
              r.get("rate_limit_per_window", Integer.class),
              r.get("rate_window_seconds", Integer.class),
              r.get("max_retry_attempts", Integer.class),
              r.get("remark", String.class),
              adapterAvailable,
              submitBlockReason(isConnected, adapterAvailable, r.get("unavailable_reason", String.class))));
    }
    return new WaybillCarrierPage(items, safePage, safeSize, total, pages(total, safeSize));
  }

  /** 「能不能下单」是本模块最高频的判断，所以界面上要有一句**可执行**的原因。 */
  private String submitBlockReason(boolean declaredConnected, boolean adapterAvailable, String declaredReason) {
    if (!declaredConnected) {
      return notBlank(declaredReason) ? declaredReason : "该承运商尚未接通（10.1.1.1 未交付）";
    }
    if (!adapterAvailable) {
      return "已登记为接通，但本服务没有对应的 vendor 适配器实现"
          + "（10.1 物流渠道未交付，承运商连接归 channel 服务）";
    }
    return "";
  }

  @Transactional
  public WaybillCarrierRow registerCarrier(WaybillCarrierInput input, UUID membershipId) {
    var code = input.code().trim();
    var channel = input.carrierChannel().trim().toUpperCase(Locale.ROOT);
    if (!CHANNEL_LABELS.contains(channel)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "承运渠道只能是 " + String.join(" / ", CHANNEL_LABELS));
    }
    // ⚠️ 登记 ≠ 接通：默认一律 connected=false，调用方必须显式给 unavailableReason。
    //   一个「登记即接通」的默认值会让界面显示「已接通」而实际提交必失败，
    //   那正是本模块最不该出现的一种显示与事实不一致。
    var unavailable =
        input.unavailableReason() == null || input.unavailableReason().isBlank()
            ? "10.1 物流渠道未交付（10.1.1.1 承运商列表 / 10.1.1.2 渠道配置 / 10.1.1.3 时效与限制）"
                + "，登记不等同于接通：承运商连接与凭据由 channel 服务的 10.1 持有"
            : input.unavailableReason().trim();
    var remark = input.remark() == null ? "" : input.remark().trim();

    var existing =
        sql.fetchOne(
            "select id from logistics_carrier where code = ?", code);
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    if (existing != null) {
      sql.execute(
          "update logistics_carrier set display_name = ?, carrier_channel = ?, adapter_key = ?,"
              + " unavailable_reason = ?, remark = ?,"
              + " rate_limit_per_window = coalesce(?, rate_limit_per_window),"
              + " rate_window_seconds = coalesce(?, rate_window_seconds),"
              + " max_retry_attempts = coalesce(?, max_retry_attempts),"
              + " registered_at = ?::timestamptz"
              + " where code = ?",
          bindAll(
              input.displayName().trim(),
              channel,
              input.adapterKey().trim(),
              unavailable,
              remark,
              input.rateLimitPerWindow(),
              input.rateWindowSeconds(),
              input.maxRetryAttempts(),
              now,
              code));
      return oneCarrier(existing.get("id", UUID.class), code);
    }
    var id = UUID.randomUUID();
    sql.execute(
        "insert into logistics_carrier"
            + " (id, tenant_id, code, display_name, carrier_channel, adapter_key, connected,"
            + "  unavailable_reason, rate_limit_per_window, rate_window_seconds,"
            + "  retry_backoff_base_seconds, max_retry_attempts, remark,"
            + "  registered_by_membership_id, registered_at)"
            + " values (?, ?, ?, ?, ?, ?, ?, false, ?,"
            + " coalesce(?, 20), coalesce(?, 60), 60, coalesce(?, 3), ?, ?, ?)",
        bindAll(
            id,
            currentTenant(),
            code,
            input.displayName().trim(),
            channel,
            input.adapterKey().trim(),
            unavailable,
            input.rateLimitPerWindow(),
            input.rateWindowSeconds(),
            input.maxRetryAttempts(),
            remark,
            membershipId,
            now));
    return oneCarrier(id, code);
  }

  private WaybillCarrierRow oneCarrier(UUID id, String code) {
    var resolution = adapters.resolve(code, adapterKeyOf(id));
    var r =
        sql.fetchOne(
                "select id, code, display_name, carrier_channel, adapter_key, connected,"
                    + " unavailable_reason, rate_limit_per_window, rate_window_seconds,"
                    + " max_retry_attempts, remark from logistics_carrier"
                    + " where id = ?",
                id)
            ;
    var available = resolution instanceof Resolution.Connected;
    var isConnected = r.get("connected", Boolean.class);
    return new WaybillCarrierRow(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("display_name", String.class),
        r.get("carrier_channel", String.class),
        channelLabel(r.get("carrier_channel", String.class)),
        r.get("adapter_key", String.class),
        isConnected,
        r.get("unavailable_reason", String.class),
        r.get("rate_limit_per_window", Integer.class),
        r.get("rate_window_seconds", Integer.class),
        r.get("max_retry_attempts", Integer.class),
        r.get("remark", String.class),
        available,
        submitBlockReason(isConnected, available, r.get("unavailable_reason", String.class)));
  }

  private String adapterKeyOf(UUID carrierId) {
    return sql.fetchOne(
            "select adapter_key from logistics_carrier where id = ?",
            carrierId)
        .get("adapter_key", String.class);
  }

  // ================================================================ 提交申请

  /**
   * 提交一条运单申请（逐包裹）。
   *
   * <p><b>幂等在前、校验在后</b>：重复提交同一次申请时**直接返回原来那一张**，
   * 不重新走申报核对 —— 否则第二次提交会因为「已经有一张有效申请」被自己拒掉，
   * 而调用方看到的应该是「幂等命中」，不是错误。
   */
  @Transactional
  public WaybillRequestRow request(WaybillRequestInput input, UUID membershipId, String bearer) {
    var replay =
        sql.fetchOne(
            "select " + REQUEST_COLUMNS + " from logistics_waybill_request r"
                + " where r.idempotency_key = ?",
            input.idempotencyKey());
    // fetchOne 对单表多列直接返回 Record（不是 Result），这里已是 Record
    if (replay != null) {
      return requestRow(replay, true);
    }

    var parcel = pendingParcel(input.pendingParcelId());
    var carrier = carrierOf(input.carrierCode());
    var declarations = declarations(input.customsItems(), input.shipToCountry(), bearer);

    var total =
        declarations.stream()
            .map(d -> d.declaredValue())
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(4, RoundingMode.HALF_UP);

    var id = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    // 单号由 DB 发（并发提交不撞号）
    var requestNo =
        sql.fetchOne(
                "select 'WAR-' || to_char(now() at time zone 'UTC', 'YYYYMMDD') || '-' ||"
                    + " lpad(nextval('waybill_request_no_seq')::text, 6, '0') as no")
            .get("no", String.class);

    // ① 先落头（申报合计 0）→ ② 落明细 → ③ 回填合计。
    // 这个顺序是 V128 的 guard_waybill_declaration_total 触发器规定的：
    // 一个自称 120 美元却无明细支撑的申报额会被 DB 当场拒。
    sql.execute(
        "insert into logistics_waybill_request"
            + " (id, tenant_id, request_no, idempotency_key, pending_unit_id, pending_parcel_id,"
            + "  outbound_order_no, sales_order_no, warehouse_ref, carrier_code, carrier_channel,"
            + "  ship_from_name, ship_from_country, ship_to_name, ship_to_country, ship_to_address_line,"
            + "  ship_to_city, ship_to_postal_code, gross_weight_kg, length_mm, width_mm, height_mm,"
            + "  declared_currency, declared_value_total, customs_source, label_template_ref, status,"
            + "  requested_by_membership_id, created_at, updated_at)"
            + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
            + " ?, 0, ?, ?, 'QUEUED', ?, ?::timestamptz, ?::timestamptz)",
        bindAll(
            id,
            currentTenant(),
            requestNo,
            input.idempotencyKey().trim(),
            parcel.unitId(),
            parcel.id(),
            parcel.outboundOrderNo(),
            parcel.salesOrderNo(),
            parcel.warehouseRef(),
            carrier.code(),
            carrier.channel(),
            input.shipFromName().trim(),
            input.shipFromCountry().trim().toUpperCase(Locale.ROOT),
            input.shipToName().trim(),
            input.shipToCountry().trim().toUpperCase(Locale.ROOT),
            trim(input.shipToAddressLine()),
            trim(input.shipToCity()),
            trim(input.shipToPostalCode()),
            parcel.grossWeightKg(),
            parcel.lengthMm(),
            parcel.widthMm(),
            parcel.heightMm(),
            input.declaredCurrency().trim().toUpperCase(Locale.ROOT),
            "3.7.1.1",
            trim(input.labelTemplateRef()),
            membershipId,
            now,
            now));

    insertDeclarations(id, declarations);
    sql.execute(
        "update logistics_waybill_request set declared_value_total = ?"
            + " where id = ?",
        bindAll(total, id));

    enqueue(id, carrier, now);
    event(id, null, "REQUESTED", "", "QUEUED", "已提交运单申请 " + requestNo, membershipId, now);
    event(id, null, "QUEUED", "QUEUED", "QUEUED", "已按渠道 " + carrier.code() + " 的限流窗口入队", membershipId, now);
    return requestRowById(id);
  }

  /**
   * 批量申请（**一次请求不许跨承运商**）。
   *
   * <p>逐条处理、逐条返回原因：批量里一条失败不能把整批吞掉。蓝图输出第二条要的是
   * 「失败原因」，而大促期批量里「三件成、两件败」是常态。
   */
  @Transactional
  public WaybillBatchResult batch(
      WaybillBatchRequestInput input, UUID membershipId, String bearer) {
    var parcelIds = input.pendingParcelIds();
    if (parcelIds.size() > MAX_BATCH) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "单次批量最多 " + MAX_BATCH + " 个包裹（一次拉爆会把请求线程占死）");
    }
    var carrier = carrierOf(input.carrierCode());
    var entries = new ArrayList<WaybillBatchEntryResult>();
    var notes = new ArrayList<String>();
    int submitted = 0, skipped = 0, failed = 0;

    for (int i = 0; i < parcelIds.size(); i++) {
      var parcelId = parcelIds.get(i);
      // 幂等键 = 前缀 + 包裹 id：批量里一个包裹重复出现只会命中同一条申请，
      // 而不会造出第二张。这正是蓝图把 idempotency_key 写进输入句的原因。
      var key = input.idempotencyKeyPrefix().trim() + ":" + parcelId;
      // ⚠️ 必须在**提交前**判幂等：提交后再查，这一条一定已经存在，
      // 于是「重复提交」永远显示成首次提交。批量重试全靠这一列告诉现场
      // 「这三条里有两条上轮就建过了，不要再等一次」。
      var existed =
          sql.fetchOne(
                  "select 1 from logistics_waybill_request where idempotency_key = ?", key)
              != null;
      try {
        var single =
            new WaybillRequestInput(
                key,
                parcelId,
                carrier.code(),
                input.shipFromName(),
                input.shipFromCountry(),
                input.shipToName(),
                input.shipToCountry(),
                input.shipToAddressLine(),
                input.shipToCity(),
                input.shipToPostalCode(),
                input.declaredCurrency(),
                input.labelTemplateRef(),
                expandDeclarations(input.customsItems(), parcelId));
        var row = request(single, membershipId, bearer);
        entries.add(
            new WaybillBatchEntryResult(
                parcelId,
                "包裹 #" + safeParcelNo(parcelId),
                row.requestNo(),
                row.id(),
                row.waybillNo(),
                true,
                existed,
                ""));
        submitted++;
      } catch (ResponseStatusException e) {
        // 逐条失败要留**可读**原因而不是异常堆栈：现场要据此判断重试哪几条。
        //
        // ⚠️ 409 不是一个状态而是**三件不同的事**，混成一个计数会骗现场：
        //   * 重复提交（同一包裹已有有效申请 / 幂等命中）→ **跳过**：换个包裹重提即可；
        //   * 申报口径对不上（HS 编码 / 原产地 / 报关属性未审核）→ **失败**：
        //     改申报信息才能过，原样重提一千次也是同样的结果，
        //     显示成「跳过」会让现场以为「这批里它已经办好了」；
        //   * 其它冲突 → 失败。
        var reason = e.getReason() == null ? "提交失败" : e.getReason();
        if (duplicateSubmission(e)) {
          skipped++;
        } else {
          failed++;
        }
        entries.add(
            new WaybillBatchEntryResult(
                parcelId, "包裹 #" + safeParcelNo(parcelId), "", null, "", false, existed, reason));
      }
    }
    notes.add(
        "批量申请逐条入队，提交数 "
            + submitted
            + "、跳过 "
            + skipped
            + "（已有有效申请或申请已存在）、失败 "
            + failed);
    notes.add(
        "全部按承运商 "
            + carrier.code()
            + " 的限流窗口排队；一次批量不许跨承运商（各家配额互不相通）");
    return new WaybillBatchResult(carrier.code(), submitted, skipped, failed, entries, notes);
  }

  /**
   * 这一条 409 是不是「重复提交」。
   *
   * <p>判据是原因原文里的两条已知措辞（分别由「同包裹已有有效申请」的 DB 唯一索引
   * 与本模块的申报核对抛出），而不是把所有 409 一律当跳过 ——
   * 「申报口径对不上」同样是 409，把它算成跳过的后果是现场以为这批已经办好了。
   */
  private static boolean duplicateSubmission(ResponseStatusException e) {
    if (e.getStatusCode() != HttpStatus.CONFLICT) {
      return false;
    }
    var reason = e.getReason();
    return reason != null && (reason.contains("重复") || reason.contains("已有有效申请"));
  }

  /** 批量申报明细 → 单包裹明细（逐件复制或整批共用，由 perParcel 显式决定）。 */
  /**
   * 取某个包裹自己的申报明细。
   *
   * <p><b>逐件过滤而不是整批复制</b>：面单逐件打印，件 B 印的是件 B 的口径。
   * 整批复制会让件 B 的面单印着件 A 的 HS 编码 —— 承运商照错的申报放行、
   * 海关按错的口径查验，扣货与罚款是蓝图 10.5 明写的后果。
   *
   * <p>某个包裹一条明细都没有时**当场拒**并点名是哪个包裹：
   * 空明细的申报单承运商与海关都会拒收，而等到放行时才发现只会浪费一次接口配额。
   */
  private List<WaybillCustomsItemInput> expandDeclarations(
      List<WaybillBatchCustomsInput> items, UUID parcelId) {
    var out = new ArrayList<WaybillCustomsItemInput>();
    for (var item : items) {
      if (!item.pendingParcelId().equals(parcelId)) {
        continue;
      }
      out.add(
          new WaybillCustomsItemInput(
              item.skuId(),
              item.skuRef(),
              item.productName(),
              item.quantity(),
              item.unitCode(),
              item.unitPrice(),
              item.hsCode(),
              item.originCountry(),
              item.destinationVersionId()));
    }
    if (out.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "包裹 "
              + parcelId
              + " 没有任何申报明细：面单逐件打印，每一件都要有自己的申报口径，"
              + "空明细的申报单承运商与海关都会拒收");
    }
    return out;
  }

  // ================================================================ 队列与放行

  /** 入队：按承运商的窗口序号算出一个**可解释**的放行时刻。 */
  private void enqueue(UUID requestId, Carrier carrier, OffsetDateTime now) {
    var slot = sql.fetchOne(
            "select count(*) from logistics_waybill_queue_entry"
                + " where carrier_code = ?"
                + " and status in ('WAITING','READY','DISPATCHING')",
            carrier.code())
        .get(0, Integer.class);
    var scheduled = scheduledFor(now, slot, carrier.perWindow(), carrier.windowSeconds());
    sql.execute(
        "insert into logistics_waybill_queue_entry"
            + " (id, tenant_id, request_id, carrier_code, status, scheduled_at, created_at, updated_at)"
            + " values (?, ?, ?, ?, 'WAITING', ?::timestamptz, ?::timestamptz, ?::timestamptz)",
        bindAll(
            UUID.randomUUID(),
            currentTenant(),
            requestId,
            carrier.code(),
            scheduled,
            now,
            now));
  }

  /**
   * 按渠道窗口<b>逐条</b>放行。
   *
   * <p>不是同步阻塞循环：取一批到期的条目，逐条提交，每条独立落
   * 尝试记录与状态。一条限流不拖住后面所有条。
   */
  @Transactional
  public WaybillDispatchResult dispatch(WaybillDispatchInput input, String bearer) {
    var carrier = carrierOf(input.carrierCode());
    var max = input.maxDispatches() == null ? MAX_DISPATCH : Math.min(input.maxDispatches(), MAX_DISPATCH);
    var now = OffsetDateTime.now(ZoneOffset.UTC);

    // 取件：**只取到期**的（scheduled_at <= now），且同一个渠道内不取别人正在提交的。
    // FOR UPDATE skip locked 让并发放行互不踩踏（两场作业同时放行不会拿到同一条）。
    var due =
        sql.fetch(
            "select " + QUEUE_COLUMNS + ", r.request_no, r.not_before"
                + " from logistics_waybill_queue_entry q"
                + " join logistics_waybill_request r on r.tenant_id = q.tenant_id"
                + "   and r.id = q.request_id"
                + " where q.carrier_code = ?"
                + " and q.status in ('WAITING','READY') and q.scheduled_at <= ?::timestamptz"
                + " and r.status = 'QUEUED'"
                + " and (r.not_before is null or r.not_before <= ?::timestamptz)"
                + " order by q.scheduled_at, q.created_at"
                + " limit ? for update of q skip locked",
            bindAll(carrier.code(), now, now, max));

    // 还在窗口外没轮到的那几条，**始终**要报出来：批量里「发了 1 条、还有 3 条在排」
    // 是运营判断要不要加窗口的依据，只在 due 为空时才报会让界面显示「没有剩余」。
    int skipped = countNotYetDue(carrier.code(), now);
    var entries = new ArrayList<WaybillDispatchEntryResult>();
    int succeeded = 0, rateLimited = 0, failed = 0;

    for (Record entry : due) {
      var queueId = entry.get("id", UUID.class);
      var requestId = entry.get("request_id", UUID.class);
      var requestNo = entry.get("request_no", String.class);
      var attemptNo = countAttempts(requestId) + 1;

      sql.execute(
          "update logistics_waybill_queue_entry set status = 'DISPATCHING',"
              + " dispatched_at = ?::timestamptz,"
              + " attempt_count = attempt_count + 1, updated_at = ?::timestamptz"
              + " where id = ?",
          bindAll(now, now, queueId));
      sql.execute(
          "update logistics_waybill_request set status = 'SUBMITTING', attempt_count = ?,"
              + " updated_at = ?::timestamptz where id = ?",
          bindAll(attemptNo, now, requestId));
      event(requestId, queueId, "DISPATCHED", "QUEUED", "SUBMITTING", "已取出并提交 " + carrier.code(), null, now);

      var outcome = submitToCarrier(requestId, carrier, bearer);
      var result = settle(requestId, queueId, attemptNo, outcome, carrier, now, null);
      entries.add(result);
      switch (result.outcome()) {
        case SUCCEEDED -> succeeded++;
        case RATE_LIMITED -> rateLimited++;
        default -> failed++;
      }
    }

    var notes = new ArrayList<String>();
    if (adapters.resolve(carrier.code(), carrier.adapterKey()) instanceof Resolution.Unavailable u) {
      notes.add(u.reason());
    }
    notes.add(
        "本渠道每 "
            + carrier.windowSeconds()
            + " 秒最多 "
            + carrier.perWindow()
            + " 次提交，超出的条目按窗口顺延而不是并发硬打（蓝图：批量申请要按渠道限流排队）");
    return new WaybillDispatchResult(
        carrier.code(),
        due.size(),
        succeeded,
        rateLimited,
        failed,
        skipped,
        entries,
        notes);
  }

  private int countNotYetDue(String carrierCode, OffsetDateTime now) {
    return sql.fetchOne(
            "select count(*) from logistics_waybill_queue_entry q"
                + " join logistics_waybill_request r on r.tenant_id = q.tenant_id and r.id = q.request_id"
                + " where q.carrier_code = ?"
                + " and q.status in ('WAITING','READY')"
                + " and (q.scheduled_at > ?::timestamptz or r.not_before > ?::timestamptz)",
            bindAll(carrierCode, now, now))
        .get(0, Integer.class);
  }

  /**
   * 调承运商。
   *
   * <p>解析不出适配器时**如实返回 ADAPTER_MISSING**，不抛异常也不编造成功：
   * 10.1 未交付就是这个答案，让这一票失败并写明 ownerModule，
   * 比在接口上炸 500 更能让运营看懂「为什么全部没出号」。
   */
  private WaybillOutcome submitToCarrier(UUID requestId, Carrier carrier, String bearer) {
    var resolution = adapters.resolve(carrier.code(), carrier.adapterKey());
    if (resolution instanceof Resolution.Unavailable u) {
      return WaybillOutcome.adapterMissing(u.reason());
    }
    var port = ((Resolution.Connected) resolution).port();
    var row =
        sql.fetchOne(
            "select " + REQUEST_COLUMNS + " from logistics_waybill_request r where r.id = ?",
            requestId);
    var declarations = new ArrayList<DeclarationLine>();
    for (Record item :
        sql.fetch(
            "select sku_id, sku_ref, product_name, quantity, unit_code, unit_price, declared_value,"
                + " hs_code, origin_country, destination_version_id"
                + " from logistics_customs_declaration_item"
                + " where request_id = ? order by sku_ref",
            requestId)) {
      declarations.add(
          new DeclarationLine(
              item.get("sku_id", UUID.class),
              item.get("sku_ref", String.class),
              item.get("product_name", String.class),
              item.get("quantity", BigDecimal.class),
              item.get("unit_code", String.class),
              item.get("unit_price", BigDecimal.class),
              item.get("declared_value", BigDecimal.class),
              item.get("hs_code", String.class),
              item.get("origin_country", String.class),
              item.get("destination_version_id", UUID.class)));
    }
    var submission =
        new WaybillSubmission(
            row.get("id", UUID.class),
            row.get("request_no", String.class),
            row.get("idempotency_key", String.class),
            carrier.code(),
            carrier.channel(),
            row.get("ship_from_name", String.class),
            row.get("ship_from_country", String.class),
            row.get("ship_to_name", String.class),
            row.get("ship_to_country", String.class),
            row.get("ship_to_address_line", String.class),
            row.get("ship_to_city", String.class),
            row.get("ship_to_postal_code", String.class),
            row.get("gross_weight_kg", BigDecimal.class),
            row.get("length_mm", BigDecimal.class),
            row.get("width_mm", BigDecimal.class),
            row.get("height_mm", BigDecimal.class),
            row.get("declared_currency", String.class),
            row.get("declared_value_total", BigDecimal.class),
            row.get("label_template_ref", String.class),
            declarations);
    try {
      var outcome = port.submit(submission);
      if (outcome == null) {
        return WaybillOutcome.unavailable("承运商适配器没有返回结果");
      }
      return outcome;
    } catch (RuntimeException e) {
      // 承运商侧异常如实落成 CARRIER_UNAVAILABLE + 原因原文（截断到 400 列宽），
      // 不吞掉也不把堆栈写进 failure_reason。
      var message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      return WaybillOutcome.unavailable(clip(message));
    }
  }

  /**
   * 落一次尝试的结果。
   *
   * <p>三条分支的差别是本模块最贵的一个判断：
   * <ul>
   *   <li><b>成功</b> → 申请置 ISSUED 并写单号（唯一能写单号的地方）；</li>
   *   <li><b>可重试失败</b>（限流 / 不可达）→ 置 FAILED 但排下一次窗口（退避）；</li>
   *   <li><b>不可重试失败</b>（拒收 / 未接通）→ 置 FAILED 且不再自动排队。</li>
   * </ul>
   * 全部落一条 {@code logistics_waybill_attempt}，失败原因逐字保留。
   */
  private WaybillDispatchEntryResult settle(
      UUID requestId,
      UUID queueId,
      int attemptNo,
      WaybillOutcome outcome,
      Carrier carrier,
      OffsetDateTime now,
      UUID membershipId) {
    var requestNo =
        sql.fetchOne(
                "select request_no from logistics_waybill_request"
                    + " where id = ?",
                requestId)
            .get("request_no", String.class);

    sql.execute(
        "insert into logistics_waybill_attempt"
            + " (id, tenant_id, request_id, attempt_no, outcome, failure_reason,"
            + "  carrier_request_ref, waybill_no, attempted_at)"
            + " values (?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz)",
        bindAll(
            UUID.randomUUID(),
            currentTenant(),
            requestId,
            attemptNo,
            outcome.outcome().name(),
            clip(outcome.failureReason()),
            clip(outcome.carrierRequestRef()),
            outcome.waybillNo(),
            now));

    if (outcome.outcome() == WaybillAttemptOutcome.SUCCEEDED) {
      sql.execute(
          "update logistics_waybill_request set status = 'ISSUED', waybill_no = ?,"
              + " label_format = ?, label_uri = ?, issued_at = ?::timestamptz, updated_at = ?::timestamptz,"
              + " not_before = null where id = ?",
          bindAll(
              outcome.waybillNo(),
              trim(outcome.labelFormat()),
              trim(outcome.labelUri()),
              now,
              now,
              requestId));
      sql.execute(
          "update logistics_waybill_queue_entry set status = 'DONE', updated_at = ?::timestamptz,"
              + " last_failure_reason = '' where id = ?",
          bindAll(now, queueId));
      event(requestId, queueId, "ISSUED", "SUBMITTING", "ISSUED", "承运商已签发运单号 " + outcome.waybillNo(), membershipId, now);
      return new WaybillDispatchEntryResult(
          requestId, requestNo, WaybillAttemptOutcome.SUCCEEDED, "已出号", outcome.waybillNo(), "", null);
    }

    var retryable = outcome.outcome().retryable && carrier.maxRetryAttempts() > 1;
    var retryCount =
        sql.fetchOne(
                "select retry_count from logistics_waybill_request"
                    + " where id = ?",
                requestId)
            .get("retry_count", Integer.class);
    OffsetDateTime notBefore = null;
    String note;
    if (retryable && retryCount + 1 < carrier.maxRetryAttempts()) {
      // 退避 = base × 2^(已失败次数)。刻意从队列登记的 base 起步而不是散落的常量，
      // 因为「等多久」是运营要看的数字，必须能从承运商登记里读出来。
      var base = carrier.backoffBaseSeconds();
      long backoff = (long) base * (1L << Math.min(retryCount, 10));
      notBefore = now.plusSeconds(backoff);
      sql.execute(
          "update logistics_waybill_request set status = 'FAILED', failure_code = ?,"
              + " failure_reason = ?, failed_at = ?::timestamptz,"
              + " not_before = ?::timestamptz, retry_count = ?, updated_at = ?::timestamptz"
              + " where id = ?",
          bindAll(
              outcome.outcome().name(),
              clip(outcome.failureReason()),
              now,
              notBefore,
              retryCount + 1,
              now,
              requestId));
      sql.execute(
          "update logistics_waybill_queue_entry set status = 'WAITING', scheduled_at = ?::timestamptz,"
              + " dispatched_at = null, reschedule_count = reschedule_count + 1,"
              + " last_failure_reason = ?, updated_at = ?::timestamptz"
              + " where id = ?",
          bindAll(notBefore, clip(outcome.failureReason()), now, queueId));
      event(requestId, queueId, "RETRY_SCHEDULED", "SUBMITTING", "FAILED",
          outcome.outcome().displayName + "，第 " + (retryCount + 1) + " 次退避后重试：" + clip(outcome.failureReason()), membershipId, now);
      note = "可重试失败，已排入下一次窗口（" + notBefore + "）";
    } else {
      sql.execute(
          "update logistics_waybill_request set status = 'FAILED', failure_code = ?,"
              + " failure_reason = ?, failed_at = ?::timestamptz, updated_at = ?::timestamptz"
              + " where id = ?",
          bindAll(outcome.outcome().name(), clip(outcome.failureReason()), now, now, requestId));
      sql.execute(
          "update logistics_waybill_queue_entry set status = 'FAILED', last_failure_reason = ?,"
              + " updated_at = ?::timestamptz where id = ?",
          bindAll(clip(outcome.failureReason()), now, queueId));
      event(requestId, queueId, "FAILED", "SUBMITTING", "FAILED",
          outcome.outcome().displayName + "：" + clip(outcome.failureReason())
              + (retryable ? "（已达重试上限 " + carrier.maxRetryAttempts() + " 次）" : "（不可重试）"), membershipId, now);
      note = retryable ? "已达重试上限，不再自动排队" : "不可重试，需要改单后重新申请";
    }
    return new WaybillDispatchEntryResult(
        requestId,
        requestNo,
        outcome.outcome(),
        outcome.outcome().displayName,
        "",
        clip(outcome.failureReason()),
        notBefore);
  }

  // ================================================================ 重试与作废

  /** 显式重试：仍受幂等键约束（重试**不**新建申请，是同一条继续走）。 */
  @Transactional
  public WaybillRetryResult retry(UUID requestId, WaybillRetryInput input, UUID membershipId) {
    var r =
        sql.fetchOne(
                "select request_no, status, retry_count, failure_reason, carrier_code"
                    + " from logistics_waybill_request where id = ?",
                requestId)
            ;
    var status = WaybillRequestStatus.valueOf(r.get("status", String.class));
    if (status == WaybillRequestStatus.ISSUED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "运单申请 " + r.get("request_no", String.class) + " 已出号，不可重试");
    }
    if (status == WaybillRequestStatus.CANCELLED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "运单申请已作废，请重新提交一条新的申请");
    }
    var carrier = carrierOf(r.get("carrier_code", String.class));
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var reason = input == null || input.reason() == null ? "" : input.reason().trim();
    var backoff = (long) carrier.backoffBaseSeconds();
    var notBefore = now.plusSeconds(backoff);

    sql.execute(
        "update logistics_waybill_request set status = 'QUEUED', not_before = ?::timestamptz,"
            + " retry_count = retry_count + 1, failure_reason = ?, failure_code = '',"
            + " updated_at = ?::timestamptz where id = ?",
        bindAll(notBefore, reason.isEmpty() ? r.get("failure_reason", String.class) : reason, now, requestId));
    sql.execute(
        "update logistics_waybill_queue_entry set status = 'WAITING', scheduled_at = ?::timestamptz,"
            + " dispatched_at = null, reschedule_count = reschedule_count + 1,"
            + " updated_at = ?::timestamptz"
            + " where request_id = ?",
        bindAll(notBefore, now, requestId));
    event(requestId, null, "RETRY_SCHEDULED", "FAILED", "QUEUED",
        reason.isEmpty() ? "人工重试" : "人工重试：" + reason, membershipId, now);

    return new WaybillRetryResult(
        requestId,
        r.get("request_no", String.class),
        true,
        notBefore,
        r.get("retry_count", Integer.class) + 1,
        r.get("failure_reason", String.class),
        "重试沿用同一条申请（同一条 idempotency_key），不会产生第二张运单");
  }

  /** 作废：只允许作废**还没出号**的申请。 */
  @Transactional
  public WaybillRequestRow cancel(UUID requestId, WaybillCancelInput input, UUID membershipId) {
    var r =
        sql.fetchOne(
                "select request_no, status from logistics_waybill_request"
                    + " where id = ?",
                requestId)
            ;
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "运单申请不存在");
    }
    var status = WaybillRequestStatus.valueOf(r.get("status", String.class));
    if (status == WaybillRequestStatus.ISSUED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "运单申请 "
              + r.get("request_no", String.class)
              + " 已出号（运单号 "
              + sql.fetchOne(
                      "select waybill_no from logistics_waybill_request"
                          + " where id = ?",
                      requestId)
                  .get("waybill_no", String.class)
              + "），取消运单属于 10.2.1.3，本模块不代做");
    }
    if (status == WaybillRequestStatus.CANCELLED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "运单申请已作废");
    }
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update logistics_waybill_request set status = 'CANCELLED', cancel_reason = ?,"
            + " not_before = null, updated_at = ?::timestamptz where id = ?",
        bindAll(input.reason().trim(), now, requestId));
    sql.execute(
        "update logistics_waybill_queue_entry set status = 'DONE', updated_at = ?::timestamptz"
            + " where request_id = ? and status in ('WAITING','READY')",
        bindAll(now, requestId));
    event(requestId, null, "CANCELLED", status.name(), "CANCELLED", input.reason().trim(), membershipId, now);
    return requestRowById(requestId);
  }

  // ================================================================ 读

  public WaybillRequestPage requests(
      UUID carrierId, String status, String keyword, String sort, int page, int size) {
    int safePage = pageNumber(page);
    int safeSize = pageSize(size);
    List<Object> params = new ArrayList<>();
    StringBuilder where = new StringBuilder("");
    if (carrierId != null) {
      where.append(where.length() == 0
          ? " where r.carrier_code = (select code from logistics_carrier where id = ?)"
          : " and r.carrier_code = (select code from logistics_carrier where id = ?)");
      params.add(carrierId);
    }
    if (notBlank(status)) {
      where.append(where.length() == 0 ? " where r.status = ?" : " and r.status = ?");
      params.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(keyword)) {
      where.append(where.length() == 0
            ? " where (r.request_no ilike ? or r.outbound_order_no ilike ? or r.sales_order_no ilike ? or r.waybill_no ilike ?)"
            : " and (r.request_no ilike ? or r.outbound_order_no ilike ? or r.sales_order_no ilike ? or r.waybill_no ilike ?)");
      var like = "%" + keyword.trim() + "%";
      params.add(like);
      params.add(like);
      params.add(like);
      params.add(like);
    }
    var order = sortOrder(sort, "created_at", "request_no");
    long total =
        sql.fetchOne("select count(*) from logistics_waybill_request r" + where, params.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select " + REQUEST_COLUMNS + " from logistics_waybill_request r" + where + " order by r." + order
                + " limit ? offset ?",
            bindings(params, safeSize, offsetOf(page, safeSize)));
    var items = new ArrayList<WaybillRequestRow>();
    for (Record r : rows) {
      items.add(requestRow(r, false));
    }
    return new WaybillRequestPage(items, safePage, safeSize, total, pages(total, safeSize));
  }

  public WaybillRequestDetail request(UUID requestId) {
    var r =
        sql.fetchOne(
            "select " + REQUEST_COLUMNS + " from logistics_waybill_request r where r.id = ?",
            requestId);
    // 不存在与跨租户返回**同一个** 404：RLS 已经把 B 的行挡掉了，
    // 这里不能因为「查得到但属于别人」而回一个 403/404 的差别 ——
    // 那等于给「拿 B 的 id 探测存在性」留了一个信息面。
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "运单申请不存在");
    }
    var row = requestRow(r, false);

    var customsItems = new ArrayList<WaybillCustomsItemRow>();
    for (Record item :
        sql.fetch(
            "select id, sku_id, sku_ref, product_name, quantity, unit_code, unit_price,"
                + " declared_value, hs_code, origin_country, destination_version_id"
                + " from logistics_customs_declaration_item"
                + " where request_id = ? order by sku_ref",
            requestId)) {
      customsItems.add(
          new WaybillCustomsItemRow(
              item.get("id", UUID.class),
              item.get("sku_id", UUID.class),
              item.get("sku_ref", String.class),
              item.get("product_name", String.class),
              item.get("quantity", BigDecimal.class),
              item.get("unit_code", String.class),
              item.get("unit_price", BigDecimal.class),
              item.get("declared_value", BigDecimal.class),
              item.get("hs_code", String.class),
              item.get("origin_country", String.class),
              item.get("destination_version_id", UUID.class)));
    }

    var queue = new ArrayList<WaybillQueueRow>();
    var position = 0;
    for (Record q :
        sql.fetch(
            "select " + QUEUE_COLUMNS + ", r.request_no"
                + " from logistics_waybill_queue_entry q"
                + " join logistics_waybill_request r on r.tenant_id = q.tenant_id and r.id = q.request_id"
                + " where q.request_id = ?"
                + " order by q.scheduled_at",
            requestId)) {
      queue.add(
          new WaybillQueueRow(
              q.get("id", UUID.class),
              q.get("request_id", UUID.class),
              q.get("request_no", String.class),
              q.get("carrier_code", String.class),
              WaybillQueueStatus.valueOf(q.get("status", String.class)),
              q.get("status", String.class),
              q.get("scheduled_at", OffsetDateTime.class),
              q.get("dispatched_at", OffsetDateTime.class),
              q.get("attempt_count", Integer.class),
              q.get("reschedule_count", Integer.class),
              q.get("last_failure_reason", String.class),
              ++position));
    }

    var attempts = new ArrayList<WaybillAttemptRow>();
    for (Record a :
        sql.fetch(
            "select " + ATTEMPT_COLUMNS + ", r.request_no from logistics_waybill_attempt a"
                + " join logistics_waybill_request r on r.tenant_id = a.tenant_id and r.id = a.request_id"
                + " where a.request_id = ? order by a.attempt_no",
            requestId)) {
      var outcome = WaybillAttemptOutcome.valueOf(a.get("outcome", String.class));
      attempts.add(
          new WaybillAttemptRow(
              a.get("id", UUID.class),
              a.get("request_id", UUID.class),
              a.get("request_no", String.class),
              a.get("attempt_no", Integer.class),
              outcome,
              outcome.displayName,
              outcome.retryable,
              a.get("failure_reason", String.class),
              a.get("carrier_request_ref", String.class),
              a.get("waybill_no", String.class),
              a.get("attempted_at", OffsetDateTime.class)));
    }

    var timeline = new ArrayList<WaybillEventRow>();
    for (Record e :
        sql.fetch(
            "select " + EVENT_COLUMNS + " from logistics_waybill_event e"
                + " where e.request_id = ? order by e.occurred_at",
            requestId)) {
      timeline.add(
          new WaybillEventRow(
              e.get("id", UUID.class),
              e.get("request_id", UUID.class),
              e.get("queue_entry_id", UUID.class),
              e.get("event_kind", String.class),
              e.get("from_status", String.class),
              e.get("to_status", String.class),
              e.get("detail", String.class),
              e.get("occurred_at", OffsetDateTime.class)));
    }

    return new WaybillRequestDetail(
        row,
        customsItems,
        queue,
        attempts,
        timeline,
        completionCriteria(row, customsItems.size()),
        List.of(
            "本模块只到「拿到运单号」：不产生发货事实（属 8.3.4.1），不改 9.4.1.1 的单元状态",
            "运单取消与改址属 10.2.1.3（未交付），已出号的运单本模块只读",
            "轨迹与异常件属 10.3（未交付），本模块不出轨迹",
            "承运商连接与限流配额归 10.1（未交付），未接通时提交会如实失败并写明原因"));
  }

  /** 承运商队列视图（看得见「谁在排第几位」—— 排队必须是可见事实）。 */
  public List<WaybillQueueRow> queue(String carrierCode) {
    var out = new ArrayList<WaybillQueueRow>();
    var position = 0;
    for (Record q :
        sql.fetch(
            "select " + QUEUE_COLUMNS + ", r.request_no from logistics_waybill_queue_entry q"
                + " join logistics_waybill_request r on r.tenant_id = q.tenant_id and r.id = q.request_id"
                + " where q.carrier_code = ?"
                + " order by q.scheduled_at, q.created_at",
            carrierCode)) {
      out.add(
          new WaybillQueueRow(
              q.get("id", UUID.class),
              q.get("request_id", UUID.class),
              q.get("request_no", String.class),
              q.get("carrier_code", String.class),
              WaybillQueueStatus.valueOf(q.get("status", String.class)),
              q.get("status", String.class),
              q.get("scheduled_at", OffsetDateTime.class),
              q.get("dispatched_at", OffsetDateTime.class),
              q.get("attempt_count", Integer.class),
              q.get("reschedule_count", Integer.class),
              q.get("last_failure_reason", String.class),
              ++position));
    }
    return out;
  }

  private int countAttempts(UUID requestId) {
    return sql.fetchOne(
            "select count(*) from logistics_waybill_attempt"
                + " where request_id = ?",
            requestId)
        .get(0, Integer.class);
  }

  // ================================================================ 组装

  /** 读一条申请的头（供服务内部与测试取当前状态用）。与 {@link #request(UUID)} 的详情形状分开。 */
  WaybillRequestRow requestSummary(UUID id) {
    return requestRowById(id);
  }

  private WaybillRequestRow requestRowById(UUID id) {
    return requestRow(
        sql.fetchOne("select " + REQUEST_COLUMNS + " from logistics_waybill_request r"
                + " where r.id = ?", id),
        false);
  }

  private WaybillRequestRow requestRow(Record r, boolean replay) {
    var id = r.get("id", UUID.class);
    var status = WaybillRequestStatus.valueOf(r.get("status", String.class));
    var itemCount =
        sql.fetchOne(
                "select count(*) from logistics_customs_declaration_item"
                    + " where request_id = ?",
                id)
            .get(0, Integer.class);
    var statusLabel = status.displayName + (replay ? "（幂等命中：这次提交返回的是原来那一张）" : "");
    return new WaybillRequestRow(
        id,
        r.get("request_no", String.class),
        r.get("idempotency_key", String.class),
        r.get("pending_unit_id", UUID.class),
        r.get("pending_parcel_id", UUID.class),
        r.get("outbound_order_no", String.class),
        r.get("sales_order_no", String.class),
        r.get("warehouse_ref", String.class),
        r.get("carrier_code", String.class),
        r.get("carrier_channel", String.class),
        channelLabel(r.get("carrier_channel", String.class)),
        r.get("ship_from_name", String.class),
        r.get("ship_from_country", String.class),
        r.get("ship_to_name", String.class),
        r.get("ship_to_country", String.class),
        r.get("ship_to_address_line", String.class),
        r.get("ship_to_city", String.class),
        r.get("ship_to_postal_code", String.class),
        r.get("gross_weight_kg", BigDecimal.class),
        r.get("length_mm", BigDecimal.class),
        r.get("width_mm", BigDecimal.class),
        r.get("height_mm", BigDecimal.class),
        r.get("declared_currency", String.class),
        r.get("declared_value_total", BigDecimal.class),
        itemCount,
        r.get("customs_source", String.class),
        r.get("label_template_ref", String.class),
        status,
        statusLabel,
        r.get("waybill_no", String.class),
        r.get("label_format", String.class),
        r.get("label_uri", String.class),
        r.get("failure_code", String.class),
        r.get("failure_reason", String.class),
        r.get("cancel_reason", String.class),
        r.get("attempt_count", Integer.class),
        r.get("retry_count", Integer.class),
        r.get("not_before", OffsetDateTime.class),
        r.get("issued_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class),
        nextAction(status, r.get("waybill_no", String.class)));
  }

  private String nextAction(WaybillRequestStatus status, String waybillNo) {
    return switch (status) {
      case QUEUED -> "在渠道限流队列中等待放行";
      case SUBMITTING -> "正在向承运商提交";
      case ISSUED -> "已拿到运单号 " + (notBlank(waybillNo) ? waybillNo : "") + "，下一步打印面单（8.3.4.2）";
      case FAILED -> "提交失败：可重试的已排入下一次窗口，不可重试的需改单后重新申请";
      case CANCELLED -> "已作废，不产生运单";
    };
  }

  private List<String> completionCriteria(WaybillRequestRow row, int declarationItems) {
    var out = new ArrayList<String>();
    out.add(
        row.status() == WaybillRequestStatus.ISSUED
            ? "已拿到承运商运单号，满足「申请成功」"
            : "尚未拿到运单号（当前 "
                + row.statusLabel()
                + "），本模块的最远态就是 ISSUED");
    out.add("申报明细 " + declarationItems + " 条（空明细的申报单承运商与海关都会拒收）");
    out.add("幂等键 " + row.idempotencyKey() + "（重复提交不会产生第二张运单）");
    return out;
  }

  // ================================================================ 待发货包裹（只读 V124）

  /** 待发货包裹读数（V124，9.4.1.1 的唯一写入者；本模块只读）。 */
  record PendingParcel(
      UUID id,
      UUID unitId,
      String outboundOrderNo,
      String salesOrderNo,
      String warehouseRef,
      String carrierChannel,
      String parcelNo,
      BigDecimal grossWeightKg,
      BigDecimal lengthMm,
      BigDecimal widthMm,
      BigDecimal heightMm) {}

  private PendingParcel pendingParcel(UUID parcelId) {
    var r =
        sql.fetchOne(
                "select p.id, p.unit_id, p.parcel_no, p.gross_weight_kg, p.length_mm, p.width_mm,"
                    + " p.height_mm, u.outbound_order_no, u.sales_order_no, u.warehouse_ref,"
                    + " u.carrier_channel, u.status"
                    + " from shipment_pending_parcel p"
                    + " join shipment_pending_unit u on u.tenant_id = p.tenant_id and u.id = p.unit_id"
                    + " where p.id = ?",
                parcelId)
            ;
    if (r == null) {
      // 跨租户与不存在返回**同一个** 404，不给「拿 B 的 id 探测存在性」留信息面
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "待发货包裹不存在");
    }
    var status = r.get("status", String.class);
    if ("CANCELLED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "该待发货单元已作废，不能再申请运单号");
    }
    return new PendingParcel(
        r.get("id", UUID.class),
        r.get("unit_id", UUID.class),
        r.get("outbound_order_no", String.class),
        r.get("sales_order_no", String.class),
        r.get("warehouse_ref", String.class),
        r.get("carrier_channel", String.class),
        String.valueOf(r.get("parcel_no", Integer.class)),
        r.get("gross_weight_kg", BigDecimal.class),
        r.get("length_mm", BigDecimal.class),
        r.get("width_mm", BigDecimal.class),
        r.get("height_mm", BigDecimal.class));
  }

  // ================================================================ 承运商（限流口径）

  /** 承运商 + 它的限流口径（走 waybill_channel_rate_limit 视图 = 单一真相）。 */
  record Carrier(
      UUID id,
      String code,
      String channel,
      String adapterKey,
      int perWindow,
      int windowSeconds,
      int backoffBaseSeconds,
      int maxRetryAttempts) {}

  private Carrier carrierOf(String code) {
    // 走视图而不是直接读表：限流口径的默认值只在视图里 coalesce 一次（单一真相）。
    // 视图本身不带 RLS 策略（策略在基表上），所以这里**显式**按当前租户过滤 ——
    // 与直接查基表不同，这一层不会自动生效，漏了就会跨租户读到别人的承运商。
    var r =
        sql.fetchOne(
                "select carrier_id, carrier_code, carrier_channel, adapter_key, rate_limit_per_window,"
                    + " rate_window_seconds, retry_backoff_base_seconds, max_retry_attempts"
                    + " from waybill_channel_rate_limit"
                    + " where tenant_id = ? and carrier_code = ?",
                currentTenant(),
                code == null ? "" : code.trim())
            ;
    if (r == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "承运商 "
              + code
              + " 未登记：请先在承运商登记里建一条（10.1 物流渠道未交付，"
              + "承运商与渠道目前只能在本模块本地登记）");
    }
    return new Carrier(
        r.get("carrier_id", UUID.class),
        r.get("carrier_code", String.class),
        r.get("carrier_channel", String.class),
        r.get("adapter_key", String.class),
        r.get("rate_limit_per_window", Integer.class),
        r.get("rate_window_seconds", Integer.class),
        r.get("retry_backoff_base_seconds", Integer.class),
        r.get("max_retry_attempts", Integer.class));
  }

  // ================================================================ 申报

  /** 核对申报明细与 3.7 的口径，任一条对不上就当场拒并说明原因。 */
  private List<DeclarationLine> declarations(
      List<WaybillCustomsItemInput> items, String destination, String bearer) {
    if (items.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "申报明细不能为空：卷 10 边界写明「包裹必须有明细项，不能只有重量和运单号」，"
              + "只报一个总额的单子承运商与海关都会拒收");
    }
    if (items.size() > MAX_DECLARATION_ITEMS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "单个包裹的申报明细最多 " + MAX_DECLARATION_ITEMS + " 条");
    }
    var seen = new LinkedHashMap<UUID, WaybillCustomsItemInput>();
    var lines = new ArrayList<DeclarationLine>();
    for (var item : items) {
      var existing = seen.putIfAbsent(item.skuId(), item);
      if (existing != null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "SKU " + item.skuRef() + " 在同一个包裹里申报了两次（重复申报会让数量翻倍）");
      }
      var quantity = item.quantity().setScale(6, RoundingMode.HALF_UP);
      var price = item.unitPrice().setScale(4, RoundingMode.HALF_UP);
      var value = price.multiply(quantity).setScale(4, RoundingMode.HALF_UP);
      var line =
          new DeclarationLine(
              item.skuId(),
              item.skuRef().trim(),
              item.productName().trim(),
              quantity,
              item.unitCode().trim(),
              price,
              value,
              item.hsCode().trim(),
              item.originCountry() == null ? "" : item.originCountry().trim().toUpperCase(Locale.ROOT),
              item.destinationVersionId());

      // 与 3.7.1.1 核对：草稿态 / HS 不一致 / 原产地不一致都当场拒。
      // 取不到就 503（端口自己抛），绝不返回空申报冒充「不用报关」。
      CustomsAttributeView profile = customs.profile(line.skuId(), bearer);
      var mismatch = customs.mismatch(line, destination, profile);
      if (mismatch != null) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, mismatch);
      }
      lines.add(line);
    }
    return lines;
  }

  private void insertDeclarations(UUID requestId, List<DeclarationLine> lines) {
    for (var line : lines) {
      sql.execute(
          "insert into logistics_customs_declaration_item"
              + " (id, tenant_id, request_id, sku_id, sku_ref, product_name, quantity, unit_code,"
              + "  unit_price, declared_value, hs_code, origin_country, destination_version_id,"
              + "  customs_attribute_id)"
              + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, null)",
          bindAll(
              UUID.randomUUID(),
              currentTenant(),
              requestId,
              line.skuId(),
              line.skuRef(),
              line.productName(),
              line.quantity(),
              line.unitCode(),
              line.unitPrice(),
              line.declaredValue(),
              line.hsCode(),
              line.originCountry(),
              line.destinationVersionId()));
    }
  }

  // ================================================================ 事件

  private void event(
      UUID requestId,
      UUID queueEntryId,
      String kind,
      String from,
      String to,
      String detail,
      UUID membershipId,
      OffsetDateTime at) {
    sql.execute(
        "insert into logistics_waybill_event"
            + " (id, tenant_id, request_id, queue_entry_id, event_kind, from_status, to_status,"
            + "  detail, actor_membership_id, occurred_at)"
            + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz)",
        bindAll(
            UUID.randomUUID(),
            currentTenant(),
            requestId,
            queueEntryId,
            kind,
            from,
            to,
            clip(detail),
            membershipId,
            at));
  }

  // ================================================================ 小工具

  private static String channelLabel(String channel) {
    if (channel == null) {
      return "";
    }
    return switch (channel) {
      case "PLATFORM_INTEGRATED" -> "平台面单";
      case "THIRD_PARTY" -> "第三方物流";
      case "SELF_PICKUP" -> "自提";
      case "POSTAL" -> "邮政";
      case "FREIGHT_FORWARDER" -> "货代";
      default -> channel;
    };
  }

  private static int pageSize(int size) {
    if (size <= 0) return 20;
    return Math.min(size, MAX_PAGE_SIZE);
  }

  /**
   * 页码归一化。
   *
   * <p>⚠️ 负页码会让 {@code offset = (page-1)*size} 变负，PostgreSQL 直接报
   * {@code OFFSET must not be negative} —— 而异常处理器把 DB 异常统一映射成 409
   * 「请刷新后检查输入」，于是「页码写错」在界面上显示成「数据冲突」，
   * 现场会刷新十次都找不到原因。所以这里钳到 0：页码是**导航**参数，
   * 写错了退回第一页，而不是把一个输入笔误变成一个看起来很严重的错误。
   */
  private static int pageNumber(int page) {
    // 负页码归一到 0。注意 offset 用的是**原始** page 计算：
    // page=0 时 offset 本来就是 0，所以 offset 也要跟着钳，否则 (0-1)*size 是负数。
    return Math.max(page, 0);
  }

  /** 页码 → OFFSET。负页码钳到 0（PostgreSQL 拒绝负 OFFSET）。 */
  private static int offsetOf(int page, int size) {
    return Math.max(page, 0) * size;
  }

  private static long pages(long total, int size) {
    return (total + size - 1) / size;
  }

  /** 排序白名单查表，值不进 SQL 字符串拼接。 */
  private static String sortOrder(String sort, String fallback, String tieBreaker) {
    String column =
        switch (sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT)) {
          case "createdat", "created_at" -> "created_at";
          case "requestno", "request_no" -> "request_no";
          case "registeredat", "registered_at" -> "registered_at";
          case "code" -> "code";
          default -> fallback;
        };
    return column + " desc, " + tieBreaker + " desc";
  }

  /** 当前 RLS 租户。由 backend/libraries/tenancy 在事务内设置，客户端头改不动它。 */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid as t")
        .get("t", UUID.class);
  }

  /**
   * 包裹序号（供批量结果逐条可读）。包裹不存在时返回 "-" 而不是抛异常 ——
   * 批量结果里这一条本来就会带着真正的失败原因出现，不必在这里先炸一次。
   */
  private String safeParcelNo(UUID parcelId) {
    var found =
        sql.fetchOne("select parcel_no from shipment_pending_parcel where id = ?", parcelId);
    return found == null ? "-" : String.valueOf(found.get("parcel_no", Integer.class));
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }

  private static String trim(String s) {
    return s == null ? "" : s.trim();
  }

  /** 失败原因列宽 400：截断而不是让一次超长异常把 INSERT 撑爆。 */
  private static String clip(String s) {
    if (s == null) {
      return "";
    }
    return s.length() <= 400 ? s : s.substring(0, 400);
  }

  private static Object[] bindings(List<Object> params, Object... tail) {
    var all = new ArrayList<Object>(params);
    all.addAll(Arrays.asList(tail));
    return all.toArray();
  }

  private static Object[] bindAll(Object... values) {
    return values;
  }
}
