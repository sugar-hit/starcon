package com.starcon.trade;

import static com.starcon.trade.ManualOrderModels.SalesOrderAddressRole;
import static com.starcon.trade.ManualOrderModels.SalesOrderCustomerType;
import static com.starcon.trade.ManualOrderModels.SalesOrderPaymentMethod;
import static com.starcon.trade.ManualOrderModels.SalesOrderSource;
import static com.starcon.trade.ManualOrderModels.SalesOrderStatus;
import static com.starcon.trade.PlatformOrderSyncModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
 * 9.1.2.1 平台订单同步。
 *
 * <p>蓝图原话：「接收并落库平台订单（<b>webhook 实时 + 轮询补拉</b>），做字段解析、状态归一、
 * 金额与税费还原、地址快照，形成统一订单模型」，输入「平台订单事件/接口返回；平台枚举映射
 * （2.1.2.1）；{@code idempotency_key}（平台订单号 + 来源范围）」，输出「销售订单 + 行 + 费用 +
 * 地址快照 + 组件展开（按映射版本）」，关系「上游 = 2 渠道与店铺；下游 = 9.2 审单、9.3 履约、
 * 13 销量。<b>同一外部订单号在同一来源范围内唯一，重复事件走 inbox 去重，绝不产生第二张订单</b>」。
 *
 * <p><b>六条纪律</b>（每条都对应一个可被集成测试抓住的失败模式）：
 *
 * <ol>
 *   <li><b>写读同链</b>：平台单落 V99 的 {@code sales_order} 簇（{@code order_source} 取平台
 *       来源值、{@code manual_kind} 留空），因此 9.1.1.1 订单列表 / 9.1.1.2 订单详情
 *       <b>不需要改一行查询</b>就能读出来。手工单与平台单共用同一条履约链路。
 *   <li><b>幂等双闸</b>：闸 1 = inbox 的 {@code UNIQUE(tenant_id, event_key)}（同一份事件重复
 *       投递）；闸 2 = 平台单的 {@code UNIQUE(tenant_id, platform_code, external_order_no)} 与
 *       {@code UNIQUE(tenant_id, idempotency_key)}（同一外部单号在同一来源范围内唯一）。
 *       两道闸都在 DB 层，<b>并发</b>重复事件由 PostgreSQL 的 {@code ON CONFLICT} 挡住
 *       ——「一个成功、一个判重复」，绝不产生第二张订单。
 *   <li><b>状态归一不猜</b>：平台状态查不到 2.1.2.1 的映射时<b>拒收</b>该事件并写异常明细，
 *       绝不猜一个内部状态码 —— 猜出来的状态会污染订单状态机，而订单状态机是审单与履约的
 *       唯一口径。组件类型是唯一允许兜底（归 OTHER）的，因为它只影响展示归类。
 *   <li><b>金额禁浮点</b>：全程 {@link BigDecimal} + {@code NUMERIC}，金额刻度 4 位、
 *       数量刻度 6 位。平台报的应付总额与本侧还原结果<b>逐值比对</b>（容差 0.01），
 *       差超过一分钱就不是舍入而是口径不一致 —— 那时拒收并记异常，不静默吸收。
 *   <li><b>快照写一次</b>：V99 收回了运行角色对订单行 / 地址 / 费用的 DELETE 权，
 *       「整体替换订单内容」在本服务没有合法路径 ⇒ 平台后来改了内容时<b>不改写</b>，
 *       记 {@code ORDER_CONTENT_DIVERGED} / {@code ORDER_SNAPSHOT_FROZEN} 异常，
 *       交人工走 9.1.1.3 订单变更与取消。
 *   <li><b>租户只来自已认证成员关系</b>：租户由 {@code app.current_tenant} 在事务内绑定，
 *       客户端的 {@code X-Tenant-Id} 头进不来也改不动它；六张业务表全部 ENABLE + FORCE RLS。
 * </ol>
 *
 * <p><b>webhook 与轮询走同一条落库路径</b>（{@link #ingest}）：两者只在「有没有时间窗」与
 * 「事件类型缺省值」上有差别，其余逐字相同 —— 两套逻辑各写一遍迟早会分叉。
 */
@Service
public class PlatformOrderSyncService {

  /** 数量刻度：与 DB 的 {@code numeric(19,6)} 逐字对齐。 */
  private static final int QTY_SCALE = 6;

  /** 金额刻度：与 DB 的 {@code numeric(19,4)} 逐字对齐。 */
  private static final int MONEY_SCALE = 4;

  private static final int MAX_PAGE_SIZE = 200;

  /** 平台应付总额与本侧还原结果的容差：0.01（一分钱）。 */
  private static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.01");

  private final DSLContext sql;
  private final ObjectMapper json;
  private final PlatformEnumMappingClient enumMappings;

  public PlatformOrderSyncService(DSLContext sql, ObjectMapper json, PlatformEnumMappingClient enumMappings) {
    this.sql = sql;
    this.json = json;
    this.enumMappings = enumMappings;
  }

  // ================================================================ 内部结构

  /** 一行已按 DB 口径算好的商品行。 */
  private record PreparedLine(
      int lineNo,
      PlatformOrderSyncLineInput in,
      BigDecimal quantity,
      BigDecimal unitPrice,
      BigDecimal lineDiscount,
      BigDecimal lineTax) {}

  /** 订单头六项汇总，公式与 V99 {@code guard_sales_order_totals} 逐条一致。 */
  private record Totals(
      BigDecimal subtotal,
      BigDecimal discount,
      BigDecimal tax,
      BigDecimal shipping,
      BigDecimal other,
      BigDecimal grandTotal) {}

  /** 一次映射命中：内部值 + 它属于哪一版映射 + 采集时刻。 */
  private record MappingHit(String internalValue, String version, OffsetDateTime capturedAt) {}

  /** 批次计数（服务层累加，落库时一次写回）。 */
  private static final class Counters {
    int received;
    int created;
    int updated;
    int duplicate;
    int failed;
    String mappingVersion = "";
    OffsetDateTime mappingCapturedAt;

    void noteVersion(String version, OffsetDateTime capturedAt) {
      if (version == null || version.isBlank() || capturedAt == null) {
        return;
      }
      if (mappingVersion.isBlank() || capturedAt.isAfter(mappingCapturedAt)) {
        mappingVersion = version;
        mappingCapturedAt = capturedAt;
      }
    }
  }

  /**
   * 事件被拒收：带机器可判定的错误码与现场，服务层统一落成异常明细。
   *
   * <p>刻意用普通异常而不是直接返回：拒收的判定散落在归一 / 金额还原 / 落库三处，
   * 统一抛出来才能保证「事件行、异常明细、批次计数」三处**同时**定案 ——
   * 漏掉任何一处都会让「这一批到底发生了什么」出现第二个答案。
   */
  private static final class Rejection extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final PlatformOrderErrorCode code;
    private final String detail;

    Rejection(PlatformOrderErrorCode code, String message, String detail) {
      super(message);
      this.code = code;
      this.detail = detail == null ? "{}" : detail;
    }
  }

  /**
   * 并发重复：另一条事务刚刚建出了**同一张**平台单。
   *
   * <p>刻意与 {@link Rejection} 分开：并发重复不是「这一单有问题」，而是
   * 「这一单已经有人建好了」—— 判成拒收会让运维以为平台那边出了问题。
   */
  private static final class DuplicateSignal extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final UUID orderId;
    private final UUID salesOrderId;
    private final String orderNo;

    DuplicateSignal(UUID orderId, UUID salesOrderId, String orderNo) {
      super("并发重复：同一张平台单已被另一条事务建出");
      this.orderId = orderId;
      this.salesOrderId = salesOrderId;
      this.orderNo = orderNo;
    }
  }

  // ================================================================ 能力声明

  /**
   * 能力与上下游可用性。
   *
   * <p>把「哪些输入真有依据」「下游有没有接上」显式报出来，而不是让调用方从 404 或空数组里
   * 去猜哪一环没通。
   */
  public PlatformOrderSyncCapabilities capabilities() {
    return new PlatformOrderSyncCapabilities(
        List.of(SalesOrderSource.values()),
        List.of(PlatformOrderStatus.values()),
        List.of(PlatformOrderEventType.values()),
        List.of(PlatformOrderTrigger.values()),
        List.of(PlatformOrderEnumKind.values()),
        List.of(PlatformOrderMappingSource.values()),
        List.of(PlatformOrderErrorCode.values()),
        List.of(
            "平台状态 → 统一状态：按 2.1.2.1 的映射副本归一，**查不到映射就拒收**（不猜）",
            "统一状态 → 单据状态：CONFIRMED / IN_FULFILLMENT / COMPLETED → SUBMITTED（进 9.2 审单队列）；"
                + "PENDING_CONFIRMATION / ON_HOLD / CANCELLED → DRAFT（还没确认，不进队列）",
            "平台单的订单内容**写一次**：后来改了内容不改写（9.1.1.3 订单变更与取消的职责）"),
        true,
        "2.1.2.1 平台枚举映射在 **channel 服务的独立库**（starcon_channel），AGENTS.md 禁止跨服务 SQL / "
            + "跨库 FK / 共享视图 ⇒ 本模块经 channel 的授权 API（GET /channel/platforms/{code}/enums）"
            + "拉取生效映射并存成本模块的副本（platform_order_enum_mapping），不建跨库引用。",
        true,
        "统一订单模型复用 9.1.2.2 手工单的那张 sales_order：平台单写同一张表，只有 order_source 不同"
            + "（PLATFORM / AMAZON / SHOPIFY / EBAY / TIKTOK_SHOP / ALIBABA_1688），manual_kind 留空。"
            + "9.1.1.1 订单列表与 9.1.1.2 订单详情读的就是这张表，**不需要改一行查询**。",
        "平台状态按 2.1.2.1 的映射副本归一；**映射缺失不落兜底**（组件类型除外，它只影响展示归类）。",
        "平台原价 / 折后价 / 优惠 / 税 / 运费按统一口径还原，与 V99 的 guard_sales_order_totals 同一条公式；"
            + "平台报的应付总额与还原结果逐值比对（容差 0.01），超过一分钱即拒收并记 AMOUNT_MISMATCH。",
        List.of(
            new PlatformOrderSyncDownstreamGap(
                "9.2.1.1",
                "待审队列",
                "trade",
                false,
                "9.2 审单与异常",
                "审单未交付：平台单落 SUBMITTED 即进审单队列，但队列展示、批量通过/驳回与规则命中都还没有出口。"),
            new PlatformOrderSyncDownstreamGap(
                "9.3.1.1",
                "履约请求",
                "trade",
                false,
                "9.3 履约编排",
                "履约请求未交付：平台单已进统一订单模型，但还没有进拣货与发货编排。"),
            new PlatformOrderSyncDownstreamGap(
                "13",
                "销量",
                "finance",
                false,
                "13 销量与利润",
                "销量未交付：平台单已落库，但销量归集与利润口径还没有消费方。"),
            new PlatformOrderSyncDownstreamGap(
                "9.1.1.3",
                "订单变更与取消",
                "trade",
                true,
                "9.1 订单视图",
                "已交付：平台侧的数量调整 / 地址修改 / 取消由 9.1.1.3 处理，本模块只把这类事实记成异常明细。"
                    + "（本模块的订单内容**写一次**，所以它必须由 9.1.1.3 接手。）")));
  }

  // ================================================================ 受理

  /** webhook 实时接入：一批平台事件走同一条落库路径。 */
  @Transactional
  public PlatformOrderSyncResult webhook(PlatformOrderSyncWebhookInput input, AccessService.Access actor) {
    return ingest(
        PlatformOrderTrigger.WEBHOOK,
        input.platformCode(),
        input.shopCode(),
        null,
        null,
        input.events(),
        actor);
  }

  /** 轮询补拉：按来源 + 时间窗把平台接口返回的订单补进来（与 webhook 同一条路径）。 */
  @Transactional
  public PlatformOrderSyncResult poll(PlatformOrderSyncPollInput input, AccessService.Access actor) {
    return ingest(
        PlatformOrderTrigger.POLL,
        input.platformCode(),
        input.shopCode(),
        input.windowStart(),
        input.windowEnd(),
        input.orders(),
        actor);
  }

  /**
   * 落库主流程。webhook 与轮询**共用**它 —— 两套逻辑各写一遍迟早分叉。
   *
   * <p>一批 = 一个事务：批次行、逐条 inbox 行、订单行、组件行、异常明细全在同一个事务里，
   * 「批次计数」因此永远是这一批的真实结论，不会出现「批次说落地了、订单却不在」。
   */
  private PlatformOrderSyncResult ingest(
      PlatformOrderTrigger trigger,
      String platformCodeRaw,
      String shopCodeRaw,
      OffsetDateTime windowStart,
      OffsetDateTime windowEnd,
      List<PlatformOrderSyncEventInput> events,
      AccessService.Access actor) {
    UUID tenantId = currentTenant();
    String platformCode = platformCodeRaw.trim().toUpperCase(Locale.ROOT);
    String shopCode = shopCodeRaw.trim();
    UUID batchId = UUID.randomUUID();
    OffsetDateTime startedAt = OffsetDateTime.now();
    sql.execute(
        "insert into platform_order_sync_batch(id,tenant_id,platform_code,shop_code,trigger_type,"
            + "window_start_at,window_end_at,status,started_at,created_at,updated_at)"
            + " values(?,?,?,?,?,?::timestamptz,?::timestamptz,'RUNNING',?::timestamptz,now(),now())",
        batchId,
        tenantId,
        platformCode,
        shopCode,
        trigger.name(),
        windowStart,
        windowEnd,
        startedAt);

    Counters counters = new Counters();
    List<PlatformOrderSyncEventResult> results = new ArrayList<>();
    for (PlatformOrderSyncEventInput event : events) {
      results.add(applyEvent(tenantId, batchId, platformCode, shopCode, trigger, event, actor, counters));
    }

    OffsetDateTime finishedAt = OffsetDateTime.now();
    String status = counters.failed > 0
        ? (counters.created + counters.updated > 0 ? "PARTIAL" : "FAILED")
        : "SUCCEEDED";
    sql.execute(
        "update platform_order_sync_batch set status = ?, received_count = ?, created_count = ?,"
            + " updated_count = ?, duplicate_count = ?, failed_count = ?, mapping_version = ?,"
            + " finished_at = ?::timestamptz, updated_at = now() where id = ?",
        status,
        counters.received,
        counters.created,
        counters.updated,
        counters.duplicate,
        counters.failed,
        counters.mappingVersion,
        finishedAt,
        batchId);

    return new PlatformOrderSyncResult(
        batchId,
        platformCode,
        shopCode,
        trigger.name(),
        status,
        counters.received,
        counters.created,
        counters.updated,
        counters.duplicate,
        counters.failed,
        counters.mappingVersion,
        startedAt,
        finishedAt,
        results);
  }

  /**
   * 受理一条平台订单事件。
   *
   * <p>顺序刻意是「<b>先占 inbox，再判归一，再落库</b>」：
   *
   * <ol>
   *   <li>先插 inbox 行（闸 1）。插不进去 = 同一份事件已经受理过 ⇒ 判重复，**不动订单**。
   *       这一步放在最前面，是因为它是唯一能在**并发**下真正生效的闸
   *       （服务层的先查后写有竞态，PostgreSQL 的 {@code ON CONFLICT} 没有）。
   *   <li>归一平台状态（查不到映射就拒收）。
   *   <li>还原金额并与平台报的应付总额比对（对不上就拒收）。
   *   <li>落库或推进（新建 / 状态推进 / 重复 / 内容被改过）。
   * </ol>
   */
  private PlatformOrderSyncEventResult applyEvent(
      UUID tenantId,
      UUID batchId,
      String platformCode,
      String shopCode,
      PlatformOrderTrigger trigger,
      PlatformOrderSyncEventInput event,
      AccessService.Access actor,
      Counters counters) {
    counters.received++;
    PlatformOrderEventType eventType =
        event.eventType() != null
            ? event.eventType()
            : (trigger == PlatformOrderTrigger.POLL
                ? PlatformOrderEventType.ORDER_UPDATED
                : PlatformOrderEventType.ORDER_CREATED);
    String externalOrderNo = event.externalOrderNo().trim();
    String idempotencyKey = platformCode + "|" + externalOrderNo;
    String payloadJson = canonicalJson(event);
    String payloadHash = sha256(payloadJson);
    String eventKey = sha256(idempotencyKey + "|" + eventType.name() + "|" + payloadHash);

    // ---- 闸 1：inbox 去重 ----------------------------------------------------
    UUID eventId = UUID.randomUUID();
    int inserted =
        sql.execute(
            "insert into platform_order_sync_event(id,tenant_id,batch_id,platform_code,shop_code,"
                + "external_order_no,idempotency_key,event_key,event_type,platform_status,"
                + "normalized_status,payload_hash,event_payload,outcome,platform_event_version,"
                + "platform_updated_at,received_at,created_at)"
                + " values(?,?,?,?,?,?,?,?,?,?,'',?,?::jsonb,'RECEIVED',?,?::timestamptz,"
                + "?::timestamptz,now())"
                + " on conflict (tenant_id,event_key) do nothing",
            eventId,
            tenantId,
            batchId,
            platformCode,
            shopCode,
            externalOrderNo,
            idempotencyKey,
            eventKey,
            eventType.name(),
            event.platformStatus().trim(),
            payloadHash,
            payloadJson,
            event.platformEventVersion() == null ? 0L : event.platformEventVersion(),
            event.platformUpdatedAt(),
            OffsetDateTime.now());
    if (inserted == 0) {
      counters.duplicate++;
      Record known = findOrder(platformCode, externalOrderNo);
      return new PlatformOrderSyncEventResult(
          externalOrderNo,
          eventType.name(),
          PlatformOrderEventOutcome.DUPLICATE.name(),
          known == null ? "" : known.get("normalized_status", String.class),
          known == null ? null : known.get("id", UUID.class),
          known == null ? null : known.get("sales_order_id", UUID.class),
          known == null ? "" : known.get("order_no", String.class),
          "",
          "重复事件：该事件内容已受理过（inbox 去重），不产生第二张订单");
    }

    // ---- 归一 / 还原 / 落库 --------------------------------------------------
    try {
      MappingHit statusHit =
          findMapping(platformCode, PlatformOrderEnumKind.ORDER_STATUS, event.platformStatus().trim());
      if (statusHit == null) {
        throw new Rejection(
            PlatformOrderErrorCode.UNMAPPED_PLATFORM_STATUS,
            "平台状态「"
                + event.platformStatus().trim()
                + "」没有登记映射，本模块不猜内部状态码：请先在 2.1.2.1 平台枚举映射里登记它"
                + "（或在本模块的映射副本里租户自维护一条）",
            detail("platformStatus", event.platformStatus().trim(), "platformCode", platformCode));
      }
      counters.noteVersion(statusHit.version(), statusHit.capturedAt());
      PlatformOrderStatus normalized = parseStatus(statusHit.internalValue());

      List<PreparedLine> lines = prepare(event);
      Totals totals = totals(lines, event);
      if (event.platformGrandTotalAmount() != null) {
        BigDecimal diff = totals.grandTotal().subtract(money(event.platformGrandTotalAmount())).abs();
        if (diff.compareTo(AMOUNT_TOLERANCE) > 0) {
          throw new Rejection(
              PlatformOrderErrorCode.AMOUNT_MISMATCH,
              "金额还原对不上：平台报的应付总额 "
                  + money(event.platformGrandTotalAmount())
                  + " 与本侧还原结果 "
                  + totals.grandTotal()
                  + " 差 "
                  + diff
                  + "（容差 0.01）—— 超过一分钱就不是舍入而是口径不一致，本模块不静默吸收",
              detail(
                  "platformGrandTotalAmount", money(event.platformGrandTotalAmount()),
                  "restoredGrandTotalAmount", totals.grandTotal(),
                  "difference", diff));
        }
      }

      String fingerprint = fingerprint(event, lines);
      String fingerprintVersion = counters.mappingVersion;

      Record existing = findOrder(platformCode, externalOrderNo);
      UUID orderId;
      UUID salesOrderId;
      String orderNo;
      String outcome;
      if (existing == null) {
        Created created =
            createOrder(
                tenantId, platformCode, shopCode, event, normalized, lines, totals, fingerprint,
                fingerprintVersion, actor, eventId);
        orderId = created.orderId();
        salesOrderId = created.salesOrderId();
        orderNo = created.orderNo();
        outcome = PlatformOrderEventOutcome.APPLIED.name();
        counters.created++;
      } else {
        orderId = existing.get("id", UUID.class);
        salesOrderId = existing.get("sales_order_id", UUID.class);
        orderNo = existing.get("order_no", String.class);
        if (!existing.get("shop_code", String.class).equals(shopCode)) {
          throw new Rejection(
              PlatformOrderErrorCode.EXTERNAL_ORDER_NO_COLLISION,
              "外部订单号「"
                  + externalOrderNo
                  + "」已在本平台存在（来自店铺 "
                  + existing.get("shop_code", String.class)
                  + "），当前来源是店铺 "
                  + shopCode
                  + "：统一订单模型的外部订单号按平台唯一，本模块不默默判成「重复」—— "
                  + "请核对两家店铺的单号是否真的撞了",
              detail(
                  "externalOrderNo", externalOrderNo,
                  "existingShopCode", existing.get("shop_code", String.class),
                  "incomingShopCode", shopCode));
        }
        if (isOlder(event, existing)) {
          counters.duplicate++;
          outcome = PlatformOrderEventOutcome.DUPLICATE.name();
        } else if (!fingerprint.equals(existing.get("content_fingerprint", String.class))) {
          String documentStatus = documentStatusOf(salesOrderId);
          boolean frozen = SalesOrderStatus.SUBMITTED.name().equals(documentStatus);
          throw new Rejection(
              frozen
                  ? PlatformOrderErrorCode.ORDER_SNAPSHOT_FROZEN
                  : PlatformOrderErrorCode.ORDER_CONTENT_DIVERGED,
              frozen
                  ? "订单「"
                      + orderNo
                      + "」已提交进审单队列，平台侧后来改了内容（行 / 金额 / 地址）："
                      + "订单上的商品与地址是**下单当时**的快照，改不动 —— 请走 9.1.1.3 订单变更与取消"
                  : "订单「"
                      + orderNo
                      + "」落地之后平台侧又改了内容（行 / 金额 / 地址）："
                      + "统一订单模型的内容是**写一次**的快照，本模块不改写 —— "
                      + "请走 9.1.1.3 订单变更与取消",
              detail("externalOrderNo", externalOrderNo, "documentStatus", documentStatus));
        } else if (!normalized.name().equals(existing.get("normalized_status", String.class))) {
          updateOrder(
              tenantId, existing, event, normalized, totals, fingerprint, fingerprintVersion, eventId);
          outcome = PlatformOrderEventOutcome.APPLIED.name();
          counters.updated++;
        } else {
          counters.duplicate++;
          outcome = PlatformOrderEventOutcome.DUPLICATE.name();
        }
      }

      finalizeEvent(eventId, outcome, normalized.name(), orderId, salesOrderId, "", "");
      return new PlatformOrderSyncEventResult(
          externalOrderNo,
          eventType.name(),
          outcome,
          normalized.name(),
          orderId,
          salesOrderId,
          orderNo,
          "",
          "");
    } catch (DuplicateSignal duplicate) {
      counters.duplicate++;
      finalizeEvent(
          eventId,
          PlatformOrderEventOutcome.DUPLICATE.name(),
          "",
          duplicate.orderId,
          duplicate.salesOrderId,
          "",
          "");
      return new PlatformOrderSyncEventResult(
          externalOrderNo,
          eventType.name(),
          PlatformOrderEventOutcome.DUPLICATE.name(),
          "",
          duplicate.orderId,
          duplicate.salesOrderId,
          duplicate.orderNo,
          "",
          "重复事件：同一张平台单已由另一条事务建出，不产生第二张订单");
    } catch (Rejection rejection) {
      counters.failed++;
      writeException(tenantId, batchId, eventId, platformCode, shopCode, event.externalOrderNo().trim(),
          rejection.code, rejection.getMessage(), rejection.detail);
      finalizeEvent(eventId, PlatformOrderEventOutcome.REJECTED.name(), "", null, null,
          rejection.code.name(), rejection.getMessage());
      return new PlatformOrderSyncEventResult(
          event.externalOrderNo().trim(),
          eventType.name(),
          PlatformOrderEventOutcome.REJECTED.name(),
          "",
          null,
          null,
          "",
          rejection.code.name(),
          rejection.getMessage());
    }
  }

  // ================================================================ 落库

  /** 新建平台单：统一销售订单 + 行 + 地址 + 费用 + 客户快照 + 平台投影 + 组件展开。 */
  private Created createOrder(
      UUID tenantId,
      String platformCode,
      String shopCode,
      PlatformOrderSyncEventInput event,
      PlatformOrderStatus normalized,
      List<PreparedLine> lines,
      Totals totals,
      String fingerprint,
      String mappingVersion,
      AccessService.Access actor,
      UUID eventId) {
    UUID salesOrderId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    String externalOrderNo = event.externalOrderNo().trim();
    String orderNo = orderNo(platformCode, shopCode, externalOrderNo);
    SalesOrderSource source = orderSource(platformCode);
    SalesOrderStatus documentStatus = documentStatus(normalized);
    SalesOrderPaymentMethod payment = paymentMethod(event);
    int termsDays = termsDays(payment, event.paymentTermsDays());
    if (payment == SalesOrderPaymentMethod.CREDIT_TERM && termsDays <= 0) {
      throw new Rejection(
          PlatformOrderErrorCode.INVALID_PAYLOAD,
          "支付方式是「账期」却没有账期天数：统一订单模型的 CHECK 规定「账期 ⇔ 账期天数 > 0」必须自洽，"
              + "请补上账期天数或改用其它支付方式",
          detail("paymentMethod", payment.name()));
    }
    OffsetDateTime now = OffsetDateTime.now();

    int inserted =
        sql.execute(
            "insert into sales_order(id,tenant_id,order_no,order_source,manual_kind,external_order_no,"
                + "currency,payment_method,payment_terms_days,status,submitted_at,subtotal_amount,"
                + "discount_amount,tax_amount,shipping_amount,other_amount,grand_total_amount,note,version,"
                + "created_at,updated_at)"
                + " values(?,?,?,?,'',?,?,?,?,?,?::timestamptz,?,?,?,?,?,?,?,0,now(),now())"
                + " on conflict do nothing",
            salesOrderId,
            tenantId,
            orderNo,
            source.name(),
            externalOrderNo,
            event.currency().trim(),
            payment.name(),
            termsDays,
            documentStatus.name(),
            documentStatus == SalesOrderStatus.SUBMITTED ? now : null,
            totals.subtotal(),
            totals.discount(),
            totals.tax(),
            totals.shipping(),
            totals.other(),
            totals.grandTotal(),
            "平台订单同步落地（平台 " + platformCode + " / 店铺 " + shopCode + "）");
    if (inserted == 0) {
      // 统一订单模型的外部订单号唯一口径（租户, order_source, 外部订单号）挡住了。
      // 分两种情况：
      //   * 同一家店铺的同一张单 ⇒ 并发重复（另一条事务刚建好）⇒ 判重复，不算失败；
      //   * 另一家店铺（或另一个未登记平台）的同号单 ⇒ 撞号 ⇒ 拒收并记异常。
      // 默默判成「重复」会让运营以为那家店铺的单已经同步过了 —— 真相是它从没进来过。
      Record twin = findOrder(platformCode, externalOrderNo);
      if (twin != null && shopCode.equals(twin.get("shop_code", String.class))) {
        throw new DuplicateSignal(
            twin.get("id", UUID.class),
            twin.get("sales_order_id", UUID.class),
            twin.get("order_no", String.class));
      }
      throw new Rejection(
          PlatformOrderErrorCode.EXTERNAL_ORDER_NO_COLLISION,
          "外部订单号「"
              + externalOrderNo
              + "」在本平台已存在（统一订单模型的外部订单号按平台唯一）："
              + "本单来自店铺 "
              + shopCode
              + "，请核对是否与另一家店铺的同号单相撞",
          detail("externalOrderNo", externalOrderNo, "shopCode", shopCode, "platformCode", platformCode));
    }

    writePartySnapshot(tenantId, salesOrderId, platformCode, shopCode, event);
    writeLines(tenantId, salesOrderId, lines);
    writeAddresses(tenantId, salesOrderId, event);
    writeCharges(tenantId, salesOrderId, event, totals);
    writeStatusEvent(
        tenantId,
        salesOrderId,
        "CREATED",
        "",
        documentStatus.name(),
        "平台订单同步落地（平台 " + platformCode + " / 店铺 " + shopCode + "）",
        actor.membershipId(),
        now);

    sql.execute(
        "insert into platform_order(id,tenant_id,platform_code,shop_code,external_order_no,"
            + "idempotency_key,sales_order_id,platform_status,normalized_status,platform_event_version,"
            + "platform_placed_at,platform_updated_at,currency,platform_original_amount,"
            + "platform_discount_amount,platform_tax_amount,platform_shipping_amount,"
            + "platform_grand_total_amount,restored_grand_total_amount,mapping_version,"
            + "content_fingerprint,last_event_id,synced_at,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?,?,?,?,?,?,?,?,?::timestamptz,"
            + "now(),now())",
        orderId,
        tenantId,
        platformCode,
        shopCode,
        externalOrderNo,
        platformCode + "|" + externalOrderNo,
        salesOrderId,
        event.platformStatus().trim(),
        normalized.name(),
        event.platformEventVersion() == null ? 0L : event.platformEventVersion(),
        event.platformPlacedAt(),
        event.platformUpdatedAt(),
        event.currency().trim(),
        totals.subtotal(),
        totals.discount(),
        totals.tax(),
        totals.shipping(),
        event.platformGrandTotalAmount() == null ? totals.grandTotal() : money(event.platformGrandTotalAmount()),
        totals.grandTotal(),
        mappingVersion,
        fingerprint,
        eventId,
        now);

    writeComponents(tenantId, orderId, salesOrderId, platformCode, lines, mappingVersion);
    return new Created(orderId, salesOrderId, orderNo);
  }

  /** 推进既有平台单：更新平台投影；单据仍是草稿且状态该进审单队列时，把它提交上去。 */
  private void updateOrder(
      UUID tenantId,
      Record existing,
      PlatformOrderSyncEventInput event,
      PlatformOrderStatus normalized,
      Totals totals,
      String fingerprint,
      String mappingVersion,
      UUID eventId) {
    UUID orderId = existing.get("id", UUID.class);
    UUID salesOrderId = existing.get("sales_order_id", UUID.class);
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update platform_order set platform_status = ?, normalized_status = ?, platform_event_version = ?,"
            + " platform_placed_at = coalesce(?::timestamptz, platform_placed_at),"
            + " platform_updated_at = coalesce(?::timestamptz, platform_updated_at),"
            + " platform_original_amount = ?, platform_discount_amount = ?, platform_tax_amount = ?,"
            + " platform_shipping_amount = ?, platform_grand_total_amount = ?,"
            + " restored_grand_total_amount = ?, mapping_version = ?, content_fingerprint = ?,"
            + " last_event_id = ?, synced_at = ?::timestamptz, updated_at = now() where id = ?",
        event.platformStatus().trim(),
        normalized.name(),
        event.platformEventVersion() == null ? 0L : event.platformEventVersion(),
        event.platformPlacedAt(),
        event.platformUpdatedAt(),
        totals.subtotal(),
        totals.discount(),
        totals.tax(),
        totals.shipping(),
        event.platformGrandTotalAmount() == null ? totals.grandTotal() : money(event.platformGrandTotalAmount()),
        totals.grandTotal(),
        mappingVersion,
        fingerprint,
        eventId,
        now,
        orderId);

    String currentDocumentStatus = documentStatusOf(salesOrderId);
    SalesOrderStatus wanted = documentStatus(normalized);
    if (SalesOrderStatus.DRAFT.name().equals(currentDocumentStatus)
        && wanted == SalesOrderStatus.SUBMITTED) {
      sql.execute(
          "update sales_order set status = 'SUBMITTED', submitted_at = ?::timestamptz,"
              + " version = version + 1, updated_at = now() where id = ?",
          now,
          salesOrderId);
      writeStatusEvent(
          tenantId,
          salesOrderId,
          "SUBMITTED",
          SalesOrderStatus.DRAFT.name(),
          SalesOrderStatus.SUBMITTED.name(),
          "平台订单状态推进到「" + normalized.label() + "」，进 9.2 审单队列",
          null,
          now);
    }
  }

  private void writePartySnapshot(
      UUID tenantId,
      UUID salesOrderId,
      String platformCode,
      String shopCode,
      PlatformOrderSyncEventInput event) {
    sql.execute(
        "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
            + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
            + " values(?,?,?,?,?,?,?,'',now())",
        UUID.randomUUID(),
        tenantId,
        salesOrderId,
        buyerPartyId(platformCode, shopCode, event.buyerId().trim()),
        event.buyerId().trim(),
        text(event.buyerName()).isEmpty() ? event.buyerId().trim() : text(event.buyerName()),
        buyerType(event).name());
  }

  private void writeLines(UUID tenantId, UUID salesOrderId, List<PreparedLine> lines) {
    for (PreparedLine p : lines) {
      UUID lineId = UUID.randomUUID();
      PlatformOrderSyncLineInput in = p.in();
      sql.execute(
          "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
              + "spec_snapshot,sku_mapping_version,quantity,unit_price,discount_amount,tax_amount,"
              + "line_amount,note,created_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())",
          lineId,
          tenantId,
          salesOrderId,
          p.lineNo(),
          in.skuId(),
          in.skuCode().trim(),
          in.skuName().trim(),
          text(in.specSnapshot()),
          text(in.skuMappingVersion()),
          p.quantity(),
          p.unitPrice(),
          p.lineDiscount(),
          p.lineTax(),
          p.unitPrice().multiply(p.quantity()).setScale(MONEY_SCALE, RoundingMode.HALF_UP),
          text(in.note()));
      if (in.attributes() == null) {
        continue;
      }
      for (PlatformOrderSyncAttributeInput a : in.attributes()) {
        sql.execute(
            "insert into order_line_attribute_snapshot(id,tenant_id,order_id,line_id,"
                + "attribute_name,attribute_value,attribute_unit,source_version,created_at)"
                + " values(?,?,?,?,?,?,?,?,now())",
            UUID.randomUUID(),
            tenantId,
            salesOrderId,
            lineId,
            a.attributeName().trim(),
            text(a.attributeValue()),
            text(a.attributeUnit()),
            text(a.sourceVersion()));
      }
    }
  }

  private void writeAddresses(UUID tenantId, UUID salesOrderId, PlatformOrderSyncEventInput event) {
    writeAddress(tenantId, salesOrderId, SalesOrderAddressRole.SHIPPING, event.shippingAddress());
    if (event.billingAddress() != null) {
      writeAddress(tenantId, salesOrderId, SalesOrderAddressRole.BILLING, event.billingAddress());
    }
  }

  private void writeAddress(
      UUID tenantId, UUID salesOrderId, SalesOrderAddressRole role, PlatformOrderSyncAddressInput a) {
    sql.execute(
        "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
            + "recipient_phone,recipient_email,company_name,country_code,province,city,district,"
            + "postcode,address_line1,address_line2,tax_id,note,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())",
        UUID.randomUUID(),
        tenantId,
        salesOrderId,
        role.name(),
        a.recipientName().trim(),
        text(a.recipientPhone()),
        text(a.recipientEmail()),
        text(a.companyName()),
        a.countryCode(),
        text(a.province()),
        text(a.city()),
        text(a.district()),
        text(a.postcode()),
        a.addressLine1().trim(),
        text(a.addressLine2()),
        text(a.taxId()),
        text(a.note()));
  }

  private void writeCharges(
      UUID tenantId, UUID salesOrderId, PlatformOrderSyncEventInput event, Totals totals) {
    if (money(event.shippingAmount()).signum() > 0) {
      writeCharge(tenantId, salesOrderId, "SHIPPING", "平台运费", money(event.shippingAmount()));
    }
    if (money(event.discountAmount()).signum() > 0) {
      writeCharge(tenantId, salesOrderId, "DISCOUNT", "平台整单优惠", money(event.discountAmount()).negate());
    }
    if (money(event.taxAmount()).signum() > 0) {
      writeCharge(tenantId, salesOrderId, "TAX", "平台整单税费", money(event.taxAmount()));
    }
    if (money(event.otherAmount()).signum() > 0) {
      writeCharge(tenantId, salesOrderId, "OTHER", "平台其它费用", money(event.otherAmount()));
    }
  }

  private void writeCharge(UUID tenantId, UUID salesOrderId, String type, String label, BigDecimal amount) {
    sql.execute(
        "insert into order_charge(id,tenant_id,order_id,charge_type,label,amount,charge_kind,note,created_at)"
            + " values(?,?,?,?,?,?,'ORDER','',now())",
        UUID.randomUUID(),
        tenantId,
        salesOrderId,
        type,
        label,
        amount);
  }

  private void writeStatusEvent(
      UUID tenantId,
      UUID salesOrderId,
      String eventType,
      String fromStatus,
      String toStatus,
      String reason,
      UUID actorId,
      OffsetDateTime occurredAt) {
    sql.execute(
        "insert into sales_order_status_history(id,tenant_id,order_id,event_type,from_status,"
            + "to_status,reason,actor_id,occurred_at,created_at)"
            + " values(?,?,?,?,?,?,?,?,?::timestamptz,now())",
        UUID.randomUUID(),
        tenantId,
        salesOrderId,
        eventType,
        fromStatus,
        toStatus,
        reason,
        actorId == null ? UUID.nameUUIDFromBytes("starcon:platform-sync".getBytes(StandardCharsets.UTF_8)) : actorId,
        occurredAt);
  }

  /**
   * 组件展开（按映射版本）。
   *
   * <p>有显式组件的行 → 一行一个组件（{@code component_source=COMPONENT}，按平台报的次序发号）；
   * 没有显式组件的行 → 落它自己（{@code component_source=LINE}）。
   * 两种情况下「这张单由哪些东西组成」都有唯一答案。
   */
  private void writeComponents(
      UUID tenantId,
      UUID orderId,
      UUID salesOrderId,
      String platformCode,
      List<PreparedLine> lines,
      String mappingVersion) {
    for (PreparedLine p : lines) {
      PlatformOrderSyncLineInput in = p.in();
      List<PlatformOrderSyncComponentInput> components =
          in.components() == null ? List.of() : in.components();
      if (components.isEmpty()) {
        sql.execute(
            "insert into platform_order_component(id,tenant_id,order_id,sales_order_id,line_no,"
                + "component_source,component_seq,component_code,component_name,component_type,"
                + "external_type,sku_id,sku_code,quantity,unit_amount,mapping_version,created_at)"
                + " values(?,?,?,?,?,'LINE',1,?,?,'ITEM','',?,?,?,?,?,now())",
            UUID.randomUUID(),
            tenantId,
            orderId,
            salesOrderId,
            p.lineNo(),
            in.skuCode().trim(),
            in.skuName().trim(),
            in.skuId(),
            in.skuCode().trim(),
            p.quantity(),
            p.unitPrice(),
            mappingVersion);
        continue;
      }
      int seq = 0;
      for (PlatformOrderSyncComponentInput c : components) {
        seq++;
        String externalType = text(c.componentType());
        String internalType = componentType(platformCode, externalType);
        sql.execute(
            "insert into platform_order_component(id,tenant_id,order_id,sales_order_id,line_no,"
                + "component_source,component_seq,component_code,component_name,component_type,"
                + "external_type,sku_id,sku_code,quantity,unit_amount,mapping_version,created_at)"
                + " values(?,?,?,?,?,'COMPONENT',?,?,?,?,?,?::uuid,?,?,?,?,now())",
            UUID.randomUUID(),
            tenantId,
            orderId,
            salesOrderId,
            p.lineNo(),
            seq,
            c.componentCode().trim(),
            text(c.componentName()),
            internalType,
            externalType,
            c.skuId(),
            text(c.skuCode()),
            c.quantity().setScale(QTY_SCALE, RoundingMode.HALF_UP),
            money(c.unitAmount()),
            mappingVersion);
      }
    }
  }

  /** 组件类型归一：查不到映射时归 OTHER（只影响展示归类，猜错不污染订单状态机）。 */
  private String componentType(String platformCode, String externalType) {
    if (externalType.isEmpty()) {
      return "OTHER";
    }
    MappingHit hit = findMapping(platformCode, PlatformOrderEnumKind.COMPONENT_TYPE, externalType);
    if (hit == null) {
      return "OTHER";
    }
    String value = hit.internalValue().trim();
    for (PlatformOrderComponentType candidate : PlatformOrderComponentType.values()) {
      if (candidate.name().equals(value)) {
        return candidate.name();
      }
    }
    return "OTHER";
  }

  private void writeException(
      UUID tenantId,
      UUID batchId,
      UUID eventId,
      String platformCode,
      String shopCode,
      String externalOrderNo,
      PlatformOrderErrorCode code,
      String message,
      String detail) {
    sql.execute(
        "insert into platform_order_sync_exception(id,tenant_id,batch_id,event_id,platform_code,"
            + "shop_code,external_order_no,error_code,error_message,detail,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,?::jsonb,now())",
        UUID.randomUUID(),
        tenantId,
        batchId,
        eventId,
        platformCode,
        shopCode,
        externalOrderNo,
        code.name(),
        message,
        detail);
  }

  /** 定案：inbox 的受理结论只能写一次（V207 的触发器钉死）。 */
  private void finalizeEvent(
      UUID eventId,
      String outcome,
      String normalizedStatus,
      UUID orderId,
      UUID salesOrderId,
      String errorCode,
      String errorMessage) {
    sql.execute(
        "update platform_order_sync_event set outcome = ?, normalized_status = ?, order_id = ?,"
            + " sales_order_id = ?, error_code = ?, error_message = ?, processed_at = ?::timestamptz"
            + " where id = ?",
        outcome,
        normalizedStatus == null ? "" : normalizedStatus,
        orderId,
        salesOrderId,
        errorCode == null ? "" : errorCode,
        errorMessage == null ? "" : errorMessage,
        OffsetDateTime.now(),
        eventId);
  }

  // ================================================================ 读

  /** 同步批次列表：真分页 + 与分页无关的全量汇总。 */
  public PlatformOrderSyncBatchPage pageBatches(PlatformOrderSyncBatchQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    if (isSet(query.platformCode())) {
      where.add("platform_code = ?");
      binds.add(query.platformCode().trim().toUpperCase(Locale.ROOT));
    }
    if (isSet(query.shopCode())) {
      where.add("shop_code = ?");
      binds.add(query.shopCode().trim());
    }
    if (isSet(query.status())) {
      where.add("status = ?");
      binds.add(query.status().trim().toUpperCase(Locale.ROOT));
    }
    if (isSet(query.triggerType())) {
      where.add("trigger_type = ?");
      binds.add(query.triggerType().trim().toUpperCase(Locale.ROOT));
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from platform_order_sync_batch" + clause, binds.toArray())
            .get(0, Long.class);
    Record summary =
        sql.fetchOne(
            "select count(*) filter (where trigger_type='WEBHOOK') as webhook,"
                + " count(*) filter (where trigger_type='POLL') as poll,"
                + " coalesce(sum(failed_count),0) as failed,"
                + " coalesce(sum(duplicate_count),0) as duplicate"
                + " from platform_order_sync_batch");
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(size);
    pageBinds.add(page * size);
    List<PlatformOrderSyncBatchRow> rows =
        sql.fetch(
                "select id,platform_code,shop_code,trigger_type,status,received_count,created_count,"
                    + "updated_count,duplicate_count,failed_count,mapping_version,window_start_at,"
                    + "window_end_at,started_at,finished_at,created_at"
                    + " from platform_order_sync_batch"
                    + clause
                    + " order by created_at desc, id desc limit ? offset ?",
                pageBinds.toArray())
            .map(PlatformOrderSyncService::batchRow);
    return new PlatformOrderSyncBatchPage(
        rows,
        page,
        size,
        total,
        summary.get("webhook", Long.class),
        summary.get("poll", Long.class),
        summary.get("failed", Long.class),
        summary.get("duplicate", Long.class));
  }

  /** 批次详情：批次 + 逐条受理结果 + 异常明细。跨租户在 RLS 下查不到 → 404。 */
  public PlatformOrderSyncBatchDetail batch(UUID batchId) {
    Record row =
        sql.fetchOne(
            "select id,platform_code,shop_code,trigger_type,status,received_count,created_count,"
                + "updated_count,duplicate_count,failed_count,mapping_version,window_start_at,"
                + "window_end_at,started_at,finished_at,created_at"
                + " from platform_order_sync_batch where id = ?",
            batchId);
    if (row == null) {
      throw notFound("同步批次 " + batchId + " 不存在或无权查看");
    }
    List<PlatformOrderSyncEventRow> events =
        sql.fetch(
                "select id,external_order_no,event_type,outcome,platform_status,normalized_status,"
                    + "error_code,error_message,platform_event_version,platform_updated_at,received_at,"
                    + "processed_at from platform_order_sync_event where batch_id = ?"
                    + " order by received_at, id",
                batchId)
            .map(PlatformOrderSyncService::eventRow);
    List<PlatformOrderSyncExceptionRow> exceptions =
        sql.fetch(
                "select id,batch_id,platform_code,shop_code,external_order_no,error_code,"
                    + "error_message,created_at from platform_order_sync_exception where batch_id = ?"
                    + " order by created_at, id",
                batchId)
            .map(PlatformOrderSyncService::exceptionRow);
    return new PlatformOrderSyncBatchDetail(batchRow(row), events, exceptions);
  }

  /** 平台单列表：真分页 + 与分页无关的全量汇总。 */
  public PlatformOrderSyncOrderPage pageOrders(PlatformOrderSyncOrderQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    if (isSet(query.platformCode())) {
      where.add("p.platform_code = ?");
      binds.add(query.platformCode().trim().toUpperCase(Locale.ROOT));
    }
    if (isSet(query.shopCode())) {
      where.add("p.shop_code = ?");
      binds.add(query.shopCode().trim());
    }
    if (isSet(query.normalizedStatus())) {
      where.add("p.normalized_status = ?");
      binds.add(query.normalizedStatus().trim().toUpperCase(Locale.ROOT));
    }
    if (isSet(query.keyword())) {
      where.add("(p.external_order_no ilike ? or o.order_no ilike ?)");
      String pattern = "%" + query.keyword().trim() + "%";
      binds.add(pattern);
      binds.add(pattern);
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    String from =
        " from platform_order p join sales_order o"
            + " on o.tenant_id = p.tenant_id and o.id = p.sales_order_id";
    long total = sql.fetchOne("select count(*)" + from + clause, binds.toArray()).get(0, Long.class);
    Record summary =
        sql.fetchOne(
            "select count(*) filter (where normalized_status='PENDING_CONFIRMATION') as pending,"
                + " count(*) filter (where normalized_status='CONFIRMED') as confirmed,"
                + " count(*) filter (where normalized_status='IN_FULFILLMENT') as in_fulfillment,"
                + " count(*) filter (where normalized_status='COMPLETED') as completed,"
                + " count(*) filter (where normalized_status='CANCELLED') as cancelled"
                + " from platform_order");
    long duplicateEvents =
        sql.fetchOne("select count(*) from platform_order_sync_event where outcome = 'DUPLICATE'")
            .get(0, Long.class);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(size);
    pageBinds.add(page * size);
    List<PlatformOrderSyncOrderRow> rows =
        sql.fetch(
                "select p.id,p.sales_order_id,o.order_no,p.platform_code,p.shop_code,p.external_order_no,"
                    + "o.order_source,o.status,p.platform_status,p.normalized_status,p.currency,"
                    + "p.platform_original_amount,p.platform_discount_amount,p.platform_tax_amount,"
                    + "p.platform_shipping_amount,p.platform_grand_total_amount,"
                    + "p.restored_grand_total_amount,p.mapping_version,p.platform_placed_at,"
                    + "p.platform_updated_at,p.synced_at"
                    + from
                    + clause
                    + " order by p.synced_at desc, p.id desc limit ? offset ?",
                pageBinds.toArray())
            .map(PlatformOrderSyncService::orderRow);
    return new PlatformOrderSyncOrderPage(
        rows,
        page,
        size,
        total,
        summary.get("pending", Long.class),
        summary.get("confirmed", Long.class),
        summary.get("in_fulfillment", Long.class),
        summary.get("completed", Long.class),
        summary.get("cancelled", Long.class),
        duplicateEvents);
  }

  /** 平台单详情：投影 + 组件展开 + 受理记录。 */
  public PlatformOrderSyncOrderDetail order(UUID orderId) {
    Record row =
        sql.fetchOne(
            "select p.id,p.sales_order_id,o.order_no,p.platform_code,p.shop_code,p.external_order_no,"
                + "o.order_source,o.status,p.platform_status,p.normalized_status,p.currency,"
                + "p.platform_original_amount,p.platform_discount_amount,p.platform_tax_amount,"
                + "p.platform_shipping_amount,p.platform_grand_total_amount,"
                + "p.restored_grand_total_amount,p.mapping_version,p.platform_placed_at,"
                + "p.platform_updated_at,p.synced_at"
                + " from platform_order p join sales_order o"
                + " on o.tenant_id = p.tenant_id and o.id = p.sales_order_id where p.id = ?",
            orderId);
    if (row == null) {
      throw notFound("平台订单 " + orderId + " 不存在或无权查看");
    }
    List<PlatformOrderSyncComponentRow> components =
        sql.fetch(
                "select id,line_no,component_source,component_seq,component_code,component_name,"
                    + "component_type,external_type,sku_code,quantity,unit_amount,mapping_version"
                    + " from platform_order_component where order_id = ?"
                    + " order by line_no, component_source, component_seq",
                orderId)
            .map(
                r ->
                    new PlatformOrderSyncComponentRow(
                        r.get("id", UUID.class),
                        r.get("line_no", Integer.class),
                        r.get("component_source", String.class),
                        r.get("component_seq", Integer.class),
                        r.get("component_code", String.class),
                        r.get("component_name", String.class),
                        r.get("component_type", String.class),
                        r.get("external_type", String.class),
                        r.get("sku_code", String.class),
                        r.get("quantity", BigDecimal.class),
                        r.get("unit_amount", BigDecimal.class),
                        r.get("mapping_version", String.class)));
    String idempotencyKey =
        row.get("platform_code", String.class) + "|" + row.get("external_order_no", String.class);
    List<PlatformOrderSyncEventRow> events =
        sql.fetch(
                "select id,external_order_no,event_type,outcome,platform_status,normalized_status,"
                    + "error_code,error_message,platform_event_version,platform_updated_at,received_at,"
                    + "processed_at from platform_order_sync_event where idempotency_key = ?"
                    + " order by received_at, id",
                idempotencyKey)
            .map(PlatformOrderSyncService::eventRow);
    return new PlatformOrderSyncOrderDetail(orderRow(row), components, events);
  }

  /** 异常明细列表：真分页 + 按错误码的汇总。 */
  public PlatformOrderSyncExceptionPage pageExceptions(PlatformOrderSyncExceptionQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    if (isSet(query.platformCode())) {
      where.add("platform_code = ?");
      binds.add(query.platformCode().trim().toUpperCase(Locale.ROOT));
    }
    if (isSet(query.errorCode())) {
      where.add("error_code = ?");
      binds.add(query.errorCode().trim().toUpperCase(Locale.ROOT));
    }
    if (isSet(query.externalOrderNo())) {
      where.add("external_order_no = ?");
      binds.add(query.externalOrderNo().trim());
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from platform_order_sync_exception" + clause, binds.toArray())
            .get(0, Long.class);
    Record summary =
        sql.fetchOne(
            "select count(*) filter (where error_code='UNMAPPED_PLATFORM_STATUS') as unmapped,"
                + " count(*) filter (where error_code='AMOUNT_MISMATCH') as mismatch,"
                + " count(*) filter (where error_code in"
                + " ('ORDER_SNAPSHOT_FROZEN','ORDER_CONTENT_DIVERGED')) as frozen"
                + " from platform_order_sync_exception");
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(size);
    pageBinds.add(page * size);
    List<PlatformOrderSyncExceptionRow> rows =
        sql.fetch(
                "select id,batch_id,platform_code,shop_code,external_order_no,error_code,"
                    + "error_message,created_at from platform_order_sync_exception"
                    + clause
                    + " order by created_at desc, id desc limit ? offset ?",
                pageBinds.toArray())
            .map(PlatformOrderSyncService::exceptionRow);
    return new PlatformOrderSyncExceptionPage(
        rows,
        page,
        size,
        total,
        summary.get("unmapped", Long.class),
        summary.get("mismatch", Long.class),
        summary.get("frozen", Long.class));
  }

  /** 映射副本列表：真分页 + 当前生效版本数 + 全部版本号。 */
  public PlatformOrderSyncMappingPage pageMappings(PlatformOrderSyncMappingQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    if (isSet(query.platformCode())) {
      where.add("platform_code = ?");
      binds.add(query.platformCode().trim().toUpperCase(Locale.ROOT));
    }
    if (isSet(query.enumKind())) {
      where.add("enum_kind = ?");
      binds.add(query.enumKind().trim().toUpperCase(Locale.ROOT));
    }
    if (isSet(query.mappingSource())) {
      where.add("mapping_source = ?");
      binds.add(query.mappingSource().trim().toUpperCase(Locale.ROOT));
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from platform_order_enum_mapping" + clause, binds.toArray())
            .get(0, Long.class);
    long active =
        sql.fetchOne("select count(*) from platform_order_enum_mapping where superseded_at is null")
            .get(0, Long.class);
    List<String> versions =
        sql.fetch("select distinct mapping_version from platform_order_enum_mapping order by mapping_version")
            .getValues("mapping_version", String.class);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(size);
    pageBinds.add(page * size);
    List<PlatformOrderSyncMappingRow> rows =
        sql.fetch(
                "select id,platform_code,enum_kind,external_value,internal_value,mapping_source,"
                    + "source_reference,mapping_version,captured_at,superseded_at"
                    + " from platform_order_enum_mapping"
                    + clause
                    + " order by platform_code, enum_kind, external_value, captured_at desc, id desc"
                    + " limit ? offset ?",
                pageBinds.toArray())
            .map(
                r ->
                    new PlatformOrderSyncMappingRow(
                        r.get("id", UUID.class),
                        r.get("platform_code", String.class),
                        r.get("enum_kind", String.class),
                        r.get("external_value", String.class),
                        r.get("internal_value", String.class),
                        r.get("mapping_source", String.class),
                        r.get("source_reference", String.class),
                        r.get("mapping_version", String.class),
                        r.get("captured_at", OffsetDateTime.class),
                        r.get("superseded_at", OffsetDateTime.class)));
    return new PlatformOrderSyncMappingPage(rows, page, size, total, active, versions);
  }

  // ================================================================ 映射副本写入

  /** 租户自维护映射副本（平台默认码表尚未覆盖到的新平台状态词走这里）。 */
  @Transactional
  public PlatformOrderSyncMappingRow registerMapping(PlatformOrderSyncMappingInput input) {
    String platformCode = input.platformCode().trim().toUpperCase(Locale.ROOT);
    String externalValue = input.externalValue().trim();
    String internalValue = input.internalValue().trim();
    String version = nextMappingVersion();
    OffsetDateTime now = OffsetDateTime.now();
    UUID mappingId = UUID.randomUUID();
    sql.execute(
        "update platform_order_enum_mapping set superseded_at = ?::timestamptz, updated_at = now()"
            + " where platform_code = ? and enum_kind = ? and external_value = ?"
            + " and mapping_source = 'TENANT_MAINTAINED' and superseded_at is null",
        now,
        platformCode,
        input.enumKind().name(),
        externalValue);
    sql.execute(
        "insert into platform_order_enum_mapping(id,tenant_id,platform_code,enum_kind,external_value,"
            + "internal_value,mapping_source,source_reference,mapping_version,captured_at,created_at,"
            + "updated_at) values(?,?,?,?,?,?,'TENANT_MAINTAINED','',?,?::timestamptz,now(),now())",
        mappingId,
        currentTenant(),
        platformCode,
        input.enumKind().name(),
        externalValue,
        internalValue,
        version,
        now);
    return new PlatformOrderSyncMappingRow(
        mappingId,
        platformCode,
        input.enumKind().name(),
        externalValue,
        internalValue,
        PlatformOrderMappingSource.TENANT_MAINTAINED.name(),
        "",
        version,
        now,
        null);
  }

  /**
   * 从 2.1.2.1（channel 服务）同步映射副本。
   *
   * <p><b>跨服务边界</b>：2.1.2.1 的映射表在 channel 的独立库，AGENTS.md 禁止跨服务 SQL ⇒
   * 这里经 channel 的**授权 API**读生效映射（平台默认 + 本租户覆盖）。
   * 转发的是<b>调用方自己的</b> Authorization bearer，租户由 channel 侧按已认证成员关系解析。
   */
  @Transactional
  public PlatformOrderSyncMappingSyncResult syncMappings(
      PlatformOrderSyncMappingSyncInput input, String bearer) {
    String platformCode = input.platformCode().trim().toUpperCase(Locale.ROOT);
    List<PlatformEnumMappingClient.EnumMappingView> views =
        enumMappings.fetch(bearer, platformCode, input.enumKind().name());
    String version = nextMappingVersion();
    OffsetDateTime now = OffsetDateTime.now();
    String reference =
        "channel:GET /channel/platforms/"
            + platformCode
            + "/enums?kind="
            + input.enumKind().name();
    int imported = 0;
    int superseded = 0;
    for (PlatformEnumMappingClient.EnumMappingView view : views) {
      String externalValue = view.externalValue() == null ? "" : view.externalValue().trim();
      String internalValue = view.internalValue() == null ? "" : view.internalValue().trim();
      if (externalValue.isEmpty() || internalValue.isEmpty()) {
        continue;
      }
      String rowReference = reference + "#" + (view.source() == null ? "PLATFORM" : view.source());
      superseded +=
          sql.execute(
              "update platform_order_enum_mapping set superseded_at = ?::timestamptz, updated_at = now()"
                  + " where platform_code = ? and enum_kind = ? and external_value = ?"
                  + " and mapping_source = 'MODULE_2_ENUM_MAPPING' and superseded_at is null",
              now,
              platformCode,
              input.enumKind().name(),
              externalValue);
      sql.execute(
          "insert into platform_order_enum_mapping(id,tenant_id,platform_code,enum_kind,external_value,"
              + "internal_value,mapping_source,source_reference,mapping_version,captured_at,created_at,"
              + "updated_at) values(?,?,?,?,?,?,'MODULE_2_ENUM_MAPPING',?,?,?::timestamptz,now(),now())",
          UUID.randomUUID(),
          currentTenant(),
          platformCode,
          input.enumKind().name(),
          externalValue,
          internalValue,
          rowReference,
          version,
          now);
      imported++;
    }
    long active =
        sql.fetchOne(
                "select count(*) from platform_order_enum_mapping"
                    + " where platform_code = ? and enum_kind = ? and superseded_at is null",
                platformCode,
                input.enumKind().name())
            .get(0, Long.class);
    return new PlatformOrderSyncMappingSyncResult(
        platformCode,
        input.enumKind().name(),
        version,
        imported,
        superseded,
        active,
        "MODULE_2_ENUM_MAPPING",
        imported == 0
            ? "2.1.2.1 当前没有返回该平台的这类映射：请确认 channel 侧已登记，且本租户没有覆盖掉它"
            : "已从 2.1.2.1 同步 " + imported + " 条映射（版本 " + version + "）");
  }

  // ================================================================ 计算

  /**
   * 行预处理：定行号、算行小计、算行折扣与行税。
   *
   * <p>行小计口径 {@code round(unitPrice × quantity, 4)} 与 DB 的
   * {@code sales_order_line_amount_matches} CHECK 同一条。
   *
   * <p>行折扣：平台报「减了多少」用 {@code discountAmount}，报「减完剩多少」
   * 用 {@code discountedUnitPrice}（服务端折算），两者都给以 {@code discountAmount} 为准。
   */
  private List<PreparedLine> prepare(PlatformOrderSyncEventInput event) {
    List<PreparedLine> out = new ArrayList<>();
    int no = 0;
    for (PlatformOrderSyncLineInput in : event.lines()) {
      no++;
      BigDecimal quantity = in.quantity().setScale(QTY_SCALE, RoundingMode.HALF_UP);
      BigDecimal unitPrice = in.unitPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
      BigDecimal discount;
      if (in.discountAmount() != null) {
        discount = money(in.discountAmount()).negate();
      } else if (in.discountedUnitPrice() != null) {
        BigDecimal original = unitPrice.multiply(quantity);
        BigDecimal discounted = in.discountedUnitPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP).multiply(quantity);
        // 行的折扣恒为**负数**（V99 sales_order_line_discount_is_non_positive）：
        // 折后价比原价高 ⇒ 差值为正 ⇒ 折后价高于原价的拒收检查靠 signum() > 0 命中。
        discount = discounted.subtract(original).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
      } else {
        discount = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
      }
      if (discount.signum() > 0) {
        throw new Rejection(
            PlatformOrderErrorCode.INVALID_PAYLOAD,
            "第 " + no + " 行的折后价高于原价（差额 " + discount + "）："
                + "统一订单模型的行折扣恒为负数，正折扣会让「减钱」变成「加钱」，本模块拒收而不是抹平",
            detail("lineNo", no, "discount", discount));
      }
      out.add(
          new PreparedLine(
              no,
              in,
              quantity,
              unitPrice,
              discount,
              money(in.taxAmount())));
    }
    return out;
  }

  /**
   * 订单头汇总。公式与 V99 {@code guard_sales_order_totals} 逐条一致：
   *
   * <pre>
   *   小计 = Σ 行小计
   *   折扣 = −Σ 行折扣（负）− Σ DISCOUNT 费用（负）→ 取正
   *   税额 = Σ 行税 + Σ TAX 费用
   *   运费 = Σ SHIPPING 费用
   *   其他 = Σ PACKAGING / OTHER 费用
   *   合计 = 小计 − 折扣 + 税额 + 运费 + 其他
   * </pre>
   */
  private Totals totals(List<PreparedLine> lines, PlatformOrderSyncEventInput event) {
    BigDecimal subtotal = BigDecimal.ZERO;
    BigDecimal lineDiscount = BigDecimal.ZERO;
    BigDecimal lineTax = BigDecimal.ZERO;
    for (PreparedLine p : lines) {
      subtotal = subtotal.add(p.unitPrice().multiply(p.quantity()).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
      lineDiscount = lineDiscount.add(p.lineDiscount());
      lineTax = lineTax.add(p.lineTax());
    }
    BigDecimal shipping = money(event.shippingAmount());
    BigDecimal orderDiscount = money(event.discountAmount());
    BigDecimal orderTax = money(event.taxAmount());
    BigDecimal other = money(event.otherAmount());
    BigDecimal discount = lineDiscount.negate().add(orderDiscount);
    BigDecimal tax = lineTax.add(orderTax);
    BigDecimal grand = subtotal.subtract(discount).add(tax).add(shipping).add(other);
    return new Totals(
        scale(subtotal), scale(discount), scale(tax), scale(shipping), scale(other), scale(grand));
  }

  /**
   * 内容指纹：订单内容（币种 / 支付方式 / 买家 / 地址 / 行 / 整单费用）的 sha256。
   *
   * <p>刻意用**归一后的值**（去空白、金额定点到 4 位、数量定点到 6 位）而不是原始报文：
   * 平台同一份内容换个 JSON 写法（多一个空格、字段顺序不同）不该被判成「内容变了」。
   */
  private String fingerprint(PlatformOrderSyncEventInput event, List<PreparedLine> lines) {
    StringBuilder sb = new StringBuilder();
    sb.append(event.currency().trim()).append('|');
    sb.append(paymentMethod(event).name()).append(':');
    sb.append(termsDays(paymentMethod(event), event.paymentTermsDays())).append('|');
    sb.append(event.buyerId().trim()).append('|').append(text(event.buyerName())).append('|');
    sb.append(buyerType(event).name()).append('|');
    appendAddress(sb, event.shippingAddress());
    sb.append('|');
    if (event.billingAddress() != null) {
      appendAddress(sb, event.billingAddress());
    }
    sb.append('|');
    for (PreparedLine p : lines) {
      PlatformOrderSyncLineInput in = p.in();
      sb.append(p.lineNo())
          .append(':')
          .append(in.skuId())
          .append(':')
          .append(in.skuCode().trim())
          .append(':')
          .append(in.skuName().trim())
          .append(':')
          .append(p.quantity())
          .append(':')
          .append(p.unitPrice())
          .append(':')
          .append(p.lineDiscount())
          .append(':')
          .append(p.lineTax())
          .append(';');
    }
    sb.append('|')
        .append(money(event.discountAmount()))
        .append(':')
        .append(money(event.taxAmount()))
        .append(':')
        .append(money(event.shippingAmount()))
        .append(':')
        .append(money(event.otherAmount()));
    return sha256(sb.toString());
  }

  private void appendAddress(StringBuilder sb, PlatformOrderSyncAddressInput a) {
    sb.append(a.recipientName().trim())
        .append('|')
        .append(text(a.recipientPhone()))
        .append('|')
        .append(text(a.recipientEmail()))
        .append('|')
        .append(text(a.companyName()))
        .append('|')
        .append(a.countryCode())
        .append('|')
        .append(text(a.province()))
        .append('|')
        .append(text(a.city()))
        .append('|')
        .append(text(a.district()))
        .append('|')
        .append(text(a.postcode()))
        .append('|')
        .append(a.addressLine1().trim())
        .append('|')
        .append(text(a.addressLine2()))
        .append('|')
        .append(text(a.taxId()));
  }

  // ================================================================ 工具

  /** 平台单投影 + 我方单号（按平台 + 外部单号找）。 */
  private Record findOrder(String platformCode, String externalOrderNo) {
    return sql.fetchOne(
        "select p.id,p.sales_order_id,p.shop_code,p.platform_status,p.normalized_status,"
            + "p.platform_event_version,p.platform_updated_at,p.content_fingerprint,p.mapping_version,"
            + "o.order_no,o.status"
            + " from platform_order p join sales_order o"
            + " on o.tenant_id = p.tenant_id and o.id = p.sales_order_id"
            + " where p.platform_code = ? and p.external_order_no = ?",
        platformCode,
        externalOrderNo);
  }

  /** 映射命中：租户自维护优先于 2.1.2.1 同步来的（同一外部值两行并存时的口径）。 */
  private MappingHit findMapping(String platformCode, PlatformOrderEnumKind kind, String externalValue) {
    Record row =
        sql.fetchOne(
            "select internal_value, mapping_version, captured_at from platform_order_enum_mapping"
                + " where platform_code = ? and enum_kind = ? and external_value = ?"
                + " and superseded_at is null"
                + " order by case mapping_source when 'TENANT_MAINTAINED' then 0 else 1 end,"
                + " captured_at desc, id desc limit 1",
            platformCode,
            kind.name(),
            externalValue);
    return row == null
        ? null
        : new MappingHit(
            row.get("internal_value", String.class),
            row.get("mapping_version", String.class),
            row.get("captured_at", OffsetDateTime.class));
  }

  /** 事件是否**严格更旧**（版本先比，再比平台更新时刻）。更旧的事件不推进状态。 */
  private boolean isOlder(PlatformOrderSyncEventInput event, Record existing) {
    long incoming = event.platformEventVersion() == null ? 0L : event.platformEventVersion();
    long stored = existing.get("platform_event_version", Long.class) == null
        ? 0L
        : existing.get("platform_event_version", Long.class);
    if (incoming != stored) {
      return incoming < stored;
    }
    OffsetDateTime incomingAt = event.platformUpdatedAt();
    OffsetDateTime storedAt = existing.get("platform_updated_at", OffsetDateTime.class);
    return incomingAt != null && storedAt != null && incomingAt.isBefore(storedAt);
  }

  /** 统一状态 → 单据状态：进审单队列的走 SUBMITTED，其余留在 DRAFT。 */
  private SalesOrderStatus documentStatus(PlatformOrderStatus normalized) {
    return switch (normalized) {
      case CONFIRMED, IN_FULFILLMENT, COMPLETED -> SalesOrderStatus.SUBMITTED;
      case PENDING_CONFIRMATION, ON_HOLD, CANCELLED -> SalesOrderStatus.DRAFT;
    };
  }

  /** 平台码 → 统一订单模型的来源标记（由服务端推导，调用方不许指定）。 */
  private SalesOrderSource orderSource(String platformCode) {
    return switch (platformCode) {
      case "AMAZON" -> SalesOrderSource.AMAZON;
      case "SHOPIFY" -> SalesOrderSource.SHOPIFY;
      case "EBAY" -> SalesOrderSource.EBAY;
      case "TIKTOK_SHOP" -> SalesOrderSource.TIKTOK_SHOP;
      case "ALIBABA_1688" -> SalesOrderSource.ALIBABA_1688;
      default -> SalesOrderSource.PLATFORM;
    };
  }

  private PlatformOrderStatus parseStatus(String internalValue) {
    try {
      return PlatformOrderStatus.valueOf(internalValue.trim());
    } catch (IllegalArgumentException e) {
      throw new Rejection(
          PlatformOrderErrorCode.UNMAPPED_PLATFORM_STATUS,
          "映射里的内部状态「" + internalValue + "」不是本模块的状态词表（"
              + java.util.Arrays.stream(PlatformOrderStatus.values()).map(PlatformOrderStatus::name).toList()
              + "）：本模块不猜状态码，请修正映射",
          detail("internalValue", internalValue));
    }
  }

  private SalesOrderPaymentMethod paymentMethod(PlatformOrderSyncEventInput event) {
    return event.paymentMethod() == null ? SalesOrderPaymentMethod.PREPAID : event.paymentMethod();
  }

  private SalesOrderCustomerType buyerType(PlatformOrderSyncEventInput event) {
    return event.buyerType() == null ? SalesOrderCustomerType.INDIVIDUAL : event.buyerType();
  }

  /** 账期与非账期必须自洽：非账期不许带天数（与 V99 的同名 CHECK 同一口径）。 */
  private int termsDays(SalesOrderPaymentMethod method, Integer days) {
    int d = days == null ? 0 : days;
    return method == SalesOrderPaymentMethod.CREDIT_TERM ? d : 0;
  }

  /** 我方单号：由平台 + 店铺 + 外部单号**确定性派生** —— 同一张外部单永远得到同一个我方单号。 */
  private String orderNo(String platformCode, String shopCode, String externalOrderNo) {
    String digest = sha256(platformCode + "|" + shopCode + "|" + externalOrderNo).substring(0, 12);
    String prefix = platformCode.length() > 20 ? platformCode.substring(0, 20) : platformCode;
    return ("PO-" + prefix + "-" + digest).toUpperCase(Locale.ROOT);
  }

  /**
   * 平台买家的**平台侧标识**（不是 catalog 的 party id）。
   *
   * <p>由「平台 + 店铺 + 平台买家号」确定性派生：同一个买家在同一家店铺永远是同一个 id，
   * 跨店铺则各算各的（平台买家号只在店铺内有意义）。
   */
  private UUID buyerPartyId(String platformCode, String shopCode, String buyerId) {
    return UUID.nameUUIDFromBytes(
        ("starcon:platform-buyer:" + platformCode + "|" + shopCode + "|" + buyerId)
            .getBytes(StandardCharsets.UTF_8));
  }

  /** 映射版本发号：全局单调递增（版本号只用于「按哪一版对上的号」，不按租户连续）。 */
  private String nextMappingVersion() {
    Long seq = sql.fetchOne("select nextval('platform_order_mapping_version_seq')").get(0, Long.class);
    return String.format("v%08d", seq == null ? 0L : seq);
  }

  private String documentStatusOf(UUID salesOrderId) {
    Record row = sql.fetchOne("select status from sales_order where id = ?", salesOrderId);
    return row == null ? "" : row.get("status", String.class);
  }

  /** 租户来自事务内的绑定值，**不是**任何请求头。 */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private String canonicalJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "平台事件无法序列化：" + e.getMessage());
    }
  }

  private static String sha256(String text) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(64);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String detail(Object... pairs) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      map.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    try {
      return new ObjectMapper().writeValueAsString(map);
    } catch (Exception e) {
      return "{}";
    }
  }

  private static BigDecimal money(BigDecimal value) {
    return value == null
        ? BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal scale(BigDecimal value) {
    return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /** 可空文本统一落成 {@code ''}：V99 的这些列都是 NOT NULL DEFAULT ''，不是 NULL。 */
  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static boolean isSet(String value) {
    return value != null && !value.isBlank();
  }

  private static PlatformOrderSyncBatchRow batchRow(Record r) {
    return new PlatformOrderSyncBatchRow(
        r.get("id", UUID.class),
        r.get("platform_code", String.class),
        r.get("shop_code", String.class),
        r.get("trigger_type", String.class),
        r.get("status", String.class),
        r.get("received_count", Integer.class),
        r.get("created_count", Integer.class),
        r.get("updated_count", Integer.class),
        r.get("duplicate_count", Integer.class),
        r.get("failed_count", Integer.class),
        r.get("mapping_version", String.class),
        r.get("window_start_at", OffsetDateTime.class),
        r.get("window_end_at", OffsetDateTime.class),
        r.get("started_at", OffsetDateTime.class),
        r.get("finished_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static PlatformOrderSyncEventRow eventRow(Record r) {
    return new PlatformOrderSyncEventRow(
        r.get("id", UUID.class),
        r.get("external_order_no", String.class),
        r.get("event_type", String.class),
        r.get("outcome", String.class),
        r.get("platform_status", String.class),
        r.get("normalized_status", String.class),
        r.get("error_code", String.class),
        r.get("error_message", String.class),
        r.get("platform_event_version", Long.class) == null
            ? 0L
            : r.get("platform_event_version", Long.class),
        r.get("platform_updated_at", OffsetDateTime.class),
        r.get("received_at", OffsetDateTime.class),
        r.get("processed_at", OffsetDateTime.class));
  }

  private static PlatformOrderSyncExceptionRow exceptionRow(Record r) {
    return new PlatformOrderSyncExceptionRow(
        r.get("id", UUID.class),
        r.get("batch_id", UUID.class),
        r.get("platform_code", String.class),
        r.get("shop_code", String.class),
        r.get("external_order_no", String.class),
        r.get("error_code", String.class),
        r.get("error_message", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static PlatformOrderSyncOrderRow orderRow(Record r) {
    return new PlatformOrderSyncOrderRow(
        r.get("id", UUID.class),
        r.get("sales_order_id", UUID.class),
        r.get("order_no", String.class),
        r.get("platform_code", String.class),
        r.get("shop_code", String.class),
        r.get("external_order_no", String.class),
        r.get("order_source", String.class),
        r.get("status", String.class),
        r.get("platform_status", String.class),
        r.get("normalized_status", String.class),
        r.get("currency", String.class),
        r.get("platform_original_amount", BigDecimal.class),
        r.get("platform_discount_amount", BigDecimal.class),
        r.get("platform_tax_amount", BigDecimal.class),
        r.get("platform_shipping_amount", BigDecimal.class),
        r.get("platform_grand_total_amount", BigDecimal.class),
        r.get("restored_grand_total_amount", BigDecimal.class),
        r.get("mapping_version", String.class),
        r.get("platform_placed_at", OffsetDateTime.class),
        r.get("platform_updated_at", OffsetDateTime.class),
        r.get("synced_at", OffsetDateTime.class));
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  /** 新建结果。 */
  private record Created(UUID orderId, UUID salesOrderId, String orderNo) {}
}
