package com.starcon.channel;

import static com.starcon.channel.ListingStatusSyncModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.sql.Timestamp;
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
import org.springframework.web.server.ResponseStatusException;

/**
 * 4.3.2.2 平台状态回流。
 *
 * <p>蓝图：「接收并展示平台侧的商品状态变化：审核通过/驳回、下架、限流、被抑制（suppressed）、类目审核、价格异常通知，并支持一键处理（如按驳回原因批量修正重发）。输入：平台事件/webhook；轮询补充。输出：状态变更记录 + 需处理队列。关系：上游 = 02 同步；下游 = 4.1.1.3、4.3.1.3。<b>回流是平台状态的权威来源</b>，内部状态与之冲突时以平台为准并告警。」
 *
 * <p>🔴 本类的四个核心不变量，每一个都有集成测试逐条钉住：
 *
 * <ol>
 *   <li><b>🔴🔴 冲突判定是真实计算出来的</b> —— {@link #detectConflict} 把「内部 listing.status」
 *       与「平台权威状态」两边的**真实取值**映射成可售性口径再比对，结论落在事件的
 *       {@code conflict_detected} / {@code internal_status} / {@code platform_status} /
 *       {@code resolution} 上。<b>不是</b>一句「已告警」的文案。
 *   <li><b>🔴🔴 有冲突必有告警</b> —— 告警行由 **DB 触发器**落（V199 的
 *       {@code guard_listing_status_event_conflict_alert}）：平台事件一落库，若判定出冲突，
 *       触发器**就地插一条 CONFLICT_ALERT 队列行**。服务层漏写不了 ⇒ 页面不会安静地
 *       显示「没有告警」而真相是内部状态与平台状态正相反。
 *   <li><b>🔴 以平台为准，但不抢别人的写入所有权</b> —— {@code resolution='PLATFORM_WINS'}
 *       表示**以平台状态为权威**，本模块**不改** {@code listing.status}：内部生命周期联动归
 *       4.1.1.3（未交付，能力表里 available=false + ownerModule）。本模块**只记录**。
 *   <li><b>🔴 一键处理走 2.5.1.1 的既有队列</b> —— 修正重发写成 V2 {@code platform_operation}
 *       （UNIQUE(tenant_id, idempotency_key)），幂等键由服务端从队列项 id 派生。
 *       <b>不新建平行任务表</b>，重复点「一键处理」也**不会**产生第二个平台任务。
 * </ol>
 *
 * <p>🔴 时间口径：本类<b>不做任何「相对 N 分钟」的判断</b>；「同步延迟」只由
 * {@code occurred_at}（平台事件发生时刻）与 {@code received_at}（入库时刻）的**真实差值**得出。
 */
@Service
public class ListingStatusSyncService {

  // ---- 内部可售性口径（listing.status 七态 → 是不是「在售」） ----
  private static final String SELLABLE = "SELLABLE";
  private static final String NOT_SELLABLE = "NOT_SELLABLE";
  /** 内部在等平台裁决（UNDER_REVIEW / REJECTED）—— **没有明确主张**，不算冲突。 */
  private static final String PENDING = "PENDING";

  /** 平台侧口径：受限（限流 / 类目审核 / 价格异常）—— 仍在售，只是加限制。 */
  private static final String RESTRICTED = "RESTRICTED";

  private final DSLContext sql;
  private final ObjectMapper json;

  public ListingStatusSyncService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ==================================================================
  // 小工具（照抄 ContentWritebackService 的同名写法）
  // ==================================================================

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(String value) {
    String trimmed = text(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
  }

  private static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(String.valueOf(raw));
  }

  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value == null ? Map.of() : value);
    } catch (Exception e) {
      return "{}";
    }
  }

  private String jsonText(Object raw) {
    if (raw == null) {
      return "{}";
    }
    String value = raw instanceof org.jooq.JSONB j ? j.data() : String.valueOf(raw);
    if (value == null || value.isBlank() || "{}".equals(value)) {
      return "{}";
    }
    try {
      return json.writeValueAsString(json.readTree(value));
    } catch (Exception e) {
      return value;
    }
  }

  private static <T> List<T> safe(List<T> values) {
    return values == null ? List.of() : values;
  }

  private static int clampSize(int size) {
    if (size <= 0) {
      return 50;
    }
    return Math.min(size, 200);
  }

  private static UUID parseId(String raw) {
    try {
      return UUID.fromString(text(raw));
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id 格式不正确");
    }
  }

  private static String note(String raw) {
    String v = text(raw);
    return v.length() > 500 ? v.substring(0, 500) : v;
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 🔴 逐维度能力声明。
   *
   * <p>本模块在本仓能**真实交付**的是「平台状态的接收与入库 + 冲突判定与告警 + 需处理队列 +
   * 一键处理（按驳回原因批量修正重发，经 02 队列）」。
   *
   * <p>它<b>不能</b>在本批真实交付的有五段，逐条在下面声明而不是假装具备：真实平台 webhook
   * 推送（02 适配层）、轮询调度器本身（02 同步运行时）、内部生命周期联动（4.1.1.3）、
   * 发布任务视图（4.3.1.3）、合规拦截升级判定（16.2.1.2）。
   */
  public ListingStatusCapabilities capabilities() {
    return new ListingStatusCapabilities(
        List.of(
            new ListingStatusCapabilityDimension(
                "platform_event_ingest",
                "🔴 接收平台状态事件并入库：审核通过/驳回、下架、限流、被抑制、类目审核、价格异常通知；"
                    + "事件从 WEBHOOK / POLL / API 三条通道进来都记下通道本身",
                true, "listing_status_event（V171 建表，V199 扩展）+ POST /events 端点", "",
                "通道记下来之后，「同步延迟」才答得出来 —— 延迟 = 入库时刻 − 平台事件发生时刻"),
            new ListingStatusCapabilityDimension(
                "status_change_log",
                "🔴 状态变更记录：真分页 + 按店铺/平台/类型/时间/冲突筛选；平台事件与内部人工改状态"
                    + "分列（source=PLATFORM / MANUAL）",
                true, "listing_status_event（V171 表 + V199 新增列）", "",
                "4.1.1.1 的「近 7 日状态变化」与 4.1.1.2 的状态轨迹会**同时**看到平台回流那几条 —— "
                    + "它们本来就是状态变化，V171 的 source 取值域早就为它预留了 'PLATFORM'"),
            new ListingStatusCapabilityDimension(
                "conflict_detection",
                "🔴 内部状态与平台状态**冲突判定**：双方对「当前是否在售」的主张相反才算冲突。"
                    + "内部在等平台裁决（UNDER_REVIEW/REJECTED）不算冲突（那是时序差）；"
                    + "平台受限（限流/类目审核/价格异常）不算冲突（商品仍在售）",
                true, "listing_status_event.conflict_detected / internal_status / platform_status"
                    + " / resolution（V199）", "",
                "判定口径是**业务规则**，所以只有服务端算一套：前端拿到的就是结论，"
                    + "自己再实现一遍必然随版本漂移，而「哪一边说了算」错一次就是运营误操作"),
            new ListingStatusCapabilityDimension(
                "conflict_alert",
                "🔴 告警记录：有冲突必有 CONFLICT_ALERT 队列行 —— 由 DB 触发器落，"
                    + "**服务层漏写不了**（漏记比多记更难发现）",
                true, "listing_status_action_queue.kind='CONFLICT_ALERT'"
                    + " + V199 guard_listing_status_event_conflict_alert 触发器", "",
                "页面不会安静地显示「没有告警」而真相是内部状态与平台状态正相反 —— "
                    + "那种漏报比误报更难发现"),
            new ListingStatusCapabilityDimension(
                "action_queue",
                "🔴 需处理队列：可一键修正重发（驳回/下架/被抑制）、需人工处理（限流/类目审核/价格异常）、"
                    + "冲突告警三类；驳回原因冗余一份并由触发器钉住与事件逐字相同",
                true, "listing_status_action_queue（V199 新表）", "",
                "「按驳回原因筛选」走队列表自己的索引，不必每次回表 join 事件表"),
            new ListingStatusCapabilityDimension(
                "one_click_republish",
                "🔴 一键处理：按驳回原因（可再按事件类型/店铺/显式点名）筛出待办并批量发起修正重发，"
                    + "走 2.5.1.1 的 platform_operation 既有队列，幂等键唯一",
                true, "listing_status_action_queue.operation_id → V2 platform_operation", "",
                "重复点「一键处理」不会产生第二个平台任务（同一个队列项永远是同一个幂等键）；"
                    + "已派发过的会被跳过并**逐条写明原因**，不静默丢弃"),
            new ListingStatusCapabilityDimension(
                "real_platform_webhook",
                "🔴 真实平台 webhook 推送（平台侧主动推给我们）",
                false, "—", "02 平台适配器（backend/services/channel/vendor/*）",
                "本模块只做**回流入库与呈现**这一段。平台侧的真实推送通道归 02 适配器："
                    + "channel 服务里已有平台适配层，本模块**不另建**一条 webhook 通道 —— "
                    + "两个入口会让同一件事有两个投递路径"),
            new ListingStatusCapabilityDimension(
                "polling_scheduler",
                "🔴 轮询补充（定时去平台捞状态变化的那个调度器）",
                false, "—", "02 同步（channel sync 运行时）",
                "本模块的入库端点**接受** ingestChannel='POLL' 的事件（通道是记下来的），"
                    + "但**没有**那个定时调度器 —— 它归 02 同步运行时。平台限流与店铺安全都由"
                    + "02 的配额统一裁决，本模块不自建轮询"),
            new ListingStatusCapabilityDimension(
                "lifecycle_sync",
                "🔴 内部生命周期状态联动（平台说「已下架」时改内部 listing.status）",
                false, "—", "4.1.1.3 生命周期与下架",
                "**状态变更记录本身照常落库并展示** —— 不因为联动没做就不记录。"
                    + "但内部 listing.status 由 4.1.1.3 的状态机拥有，本模块**不抢它的写入所有权**："
                    + "改了就等于两个模块同时写一个事实源。本模块以「以平台为准」的方式"
                    + "把冲突记下来并告警，联动由 4.1.1.3 交付后接上"),
            new ListingStatusCapabilityDimension(
                "publish_task_view",
                "🔴 修正重发的**下游任务页面**（发布步骤时间线 / 可重试的步骤）",
                false, "—", "4.3.1.3 发布任务",
                "本模块负责**发起**（入 02 队列）并展示**回执**（队列任务 id 与其状态），"
                    + "不负责发布任务页面 —— 那是另一个模块的视图"),
            new ListingStatusCapabilityDimension(
                "compliance_escalation",
                "🔴 价格异常通知 / 被抑制是否升级成**合规拦截**",
                false, "—", "16.2.1.2 拦截执行",
                "本模块把「价格异常通知」「被抑制」作为**平台通知**如实记录与展示，"
                    + "但「要不要升级成拦截」是合规判定，归 16.2.1.2 —— "
                    + "本模块**不擅自**把一条通知变成一个拦截动作"),
            new ListingStatusCapabilityDimension(
                "platform_authority",
                "🔴 「回流是平台状态的权威来源」：平台状态是权威，内部状态与之冲突时**以平台为准**并告警",
                true, "listing_status_event.resolution='PLATFORM_WINS' + CONFLICT_ALERT 队列行", "",
                "权威性体现在**冲突时的处置口径**上：平台状态被当作事实，内部状态不因冲突而被"
                    + "改写（那是 4.1.1.3 的活），而是**记下来 + 告警**，让人来决定要不要联动")),
        EVENT_TYPES,
        INGEST_CHANNELS,
        QUEUE_KINDS,
        QUEUE_STATES,
        "冲突 = 双方对「当前是否在售」的**主张相反**。内部可售性：ON_SALE → 在售；"
            + "UNDER_REVIEW / REJECTED → 在等平台裁决（**没有明确主张，不算冲突**）；"
            + "DRAFT / DELISTED_BY_US / DELISTED_BY_PLATFORM / BLOCKED → 不在售。"
            + "平台可售性：APPROVED → 在售；REJECTED / DELISTED / SUPPRESSED → 不在售；"
            + "THROTTLED / CATEGORY_REVIEW / PRICE_ALERT → 受限（**仍在售，不算冲突**）。"
            + "判定结论由服务端算好后下发（conflictDetected / internalStatus / platformStatus / "
            + "resolution），**前端原样显示，不要自己重算**。",
        "以平台为准（resolution='PLATFORM_WINS'）的意思是：**平台状态被当作事实来源**，"
            + "内部状态不因冲突而被改写 —— 改内部生命周期状态是 4.1.1.3 的写入所有权（未交付）。"
            + "本模块做的是：把冲突**记下来**（内部状态 + 平台状态逐字）、**告警**"
            + "（DB 触发器落的 CONFLICT_ALERT 队列行）、并把该商品推进需处理队列。",
        "上游 = 02 同步（平台适配器把平台状态推过来 / 轮询补捞）—— 真实 webhook 通道与轮询调度器"
            + "**都不在本模块**（见能力表）；下游 = 4.1.1.3 生命周期与下架（内部状态联动，未交付）、"
            + "4.3.1.3 发布任务（修正重发的下游任务视图，未交付）。"
            + "「一键处理」发起的修正重发走 2.5.1.1 的 platform_operation 既有队列，"
            + "本模块**不建平行任务表**。本模块**无任何金额列**：价格异常只是一条平台通知，"
            + "判定价格是否真的异常归 05 定价。");
  }

  // ==================================================================
  // 🔴🔴 冲突判定（纯函数 · 可单测）
  // ==================================================================

  /** 内部 listing.status → 可售性口径。 */
  static String internalSellability(String status) {
    return switch (status == null ? "" : status) {
      case "ON_SALE" -> SELLABLE;
      // 🔴 在等平台裁决：内部没有对「是否在售」下明确主张 ⇒ 不算冲突
      case "UNDER_REVIEW", "REJECTED" -> PENDING;
      default -> NOT_SELLABLE;
    };
  }

  /** 平台事件类型 → 可售性口径。 */
  static String platformSellability(String eventType) {
    return switch (eventType == null ? "" : eventType) {
      case "APPROVED" -> SELLABLE;
      case "REJECTED", "DELISTED", "SUPPRESSED" -> NOT_SELLABLE;
      // 🔴 受限：商品**仍在售**，平台只是加了限制 ⇒ 与内部「在售」不矛盾
      case "THROTTLED", "CATEGORY_REVIEW", "PRICE_ALERT" -> RESTRICTED;
      default -> PENDING;
    };
  }

  /** 一次冲突判定的结论。 */
  record ConflictOutcome(boolean detected, String note, String resolution) {}

  /**
   * 🔴🔴 冲突判定。
   *
   * <p>只有「双方对当前是否在售的主张相反」才算冲突：
   *
   * <ul>
   *   <li>内部在售 + 平台不在售 ⇒ 冲突（漏单：平台把商品下架了而内部不知情）
   *   <li>内部不在售 + 平台在售 ⇒ 冲突（平台还在卖，系统却以为已下架 / 封禁 / 还是草稿）
   *   <li>内部在等平台裁决（PENDING）⇒ **不算**（那是时序差，不是事实矛盾）
   *   <li>平台受限（RESTRICTED）⇒ **不算**（商品仍在售）
   * </ul>
   *
   * <p>结论里的说明把两边状态**逐字**写出来（"内部 ON_SALE，平台 DELISTED"），
   * 这样告警记录本身就能回答"到底哪里矛盾"，不必再回查。
   */
  static ConflictOutcome detectConflict(String internalStatus, String eventType) {
    String internal = internalSellability(internalStatus);
    String platform = platformSellability(eventType);
    boolean detected =
        (SELLABLE.equals(internal) && NOT_SELLABLE.equals(platform))
            || (NOT_SELLABLE.equals(internal) && SELLABLE.equals(platform));
    if (!detected) {
      return new ConflictOutcome(false, "", "NOT_REQUIRED");
    }
    String note =
        "内部状态 "
            + (internalStatus == null ? "" : internalStatus)
            + "，平台状态 "
            + (eventType == null ? "" : eventType)
            + "：双方对「当前是否在售」的主张相反，以平台为准";
    return new ConflictOutcome(true, note, "PLATFORM_WINS");
  }

  // ==================================================================
  // 🔴🔴 回流入库
  // ==================================================================

  /**
   * 🔴🔴 接收一条平台状态事件。
   *
   * <p>四件事，逐条都有理由：
   *
   * <ol>
   *   <li><b>定位商品</b>：给 listingId 就用它；给 (shopCode, externalItemId) 就按那对定位。
   *       两者都定位不到 ⇒ 422（**不新建一个商品** —— 平台说的事我们连挂在谁身上都不知道）。
   *   <li><b>算冲突</b>：拿内部 listing.status 与平台事件类型过一遍 {@link #detectConflict}。
   *       结论（连同内部状态快照、平台状态原文）一起落库。
   *   <li><b>落事件</b>：写 {@code listing_status_event}（source='PLATFORM'）。
   *       **冲突告警行由 DB 触发器落**（见 V199），服务层不重复插。
   *   <li><b>落待办</b>：驳回/下架/被抑制 ⇒ 一条 REPUBLISH（可一键修正重发）；
   *       限流/类目审核/价格异常 ⇒ 一条 REVIEW（需人工处理）；审核通过 ⇒ 不产生待办。
   * </ol>
   */
  public ListingStatusEventRow ingest(IngestStatusEventRequest request, UUID actorId) {
    UUID tenant = currentTenant();

    String eventType = text(request.eventType()).toUpperCase(Locale.ROOT);
    if (!EVENT_TYPES.contains(eventType)) {
      throw unprocessable(
          "未知的事件类型「"
              + text(request.eventType())
              + "」。平台状态回流只认这七种："
              + String.join(" / ", EVENT_TYPES)
              + "（拼错一个词就是平台侧的一个新状态，猜一个最像的去执行比报错危险得多）");
    }
    String platformStatus = text(request.platformStatus());
    if (platformStatus.isEmpty()) {
      throw unprocessable("必须给出平台的权威状态原文（platformStatus）—— 平台状态回流的价值全在它");
    }
    String ingestChannel = text(request.ingestChannel()).toUpperCase(Locale.ROOT);
    if (ingestChannel.isEmpty()) {
      ingestChannel = "API";
    }
    if (!INGEST_CHANNELS.contains(ingestChannel)) {
      throw unprocessable(
          "未知的事件通道「"
              + text(request.ingestChannel())
              + "」。只认这三条："
              + String.join(" / ", INGEST_CHANNELS));
    }
    String rejectionReason = text(request.rejectionReason());
    if ("REJECTED".equals(eventType) && rejectionReason.isEmpty()) {
      throw unprocessable("驳回事件必须带驳回原因 —— 「按驳回原因批量修正重发」全靠它");
    }

    Record listing = resolveListing(tenant, request);
    if (listing == null) {
      throw unprocessable(
          "定位不到商品：请给 listingId，或给 (店铺编码 + 平台商品 ID)。"
              + "平台说了什么我们连挂在谁身上都不知道的话，这条记录没有意义");
    }
    UUID listingId = listing.get("id", UUID.class);
    String internalStatus = nullToEmpty(listing.get("status", String.class));

    // ---- 🔴 冲突判定（真实计算，不是文案）
    ConflictOutcome conflict = detectConflict(internalStatus, eventType);

    // ---- 上一个平台状态（from_status）：本 listing 的上一条平台事件
    Object previous =
        sql.fetchValue(
            "select to_status from listing_status_event where tenant_id=? and listing_id=? "
                + "and source='PLATFORM' order by occurred_at desc, received_at desc, id desc limit 1",
            tenant, listingId);
    String fromStatus = previous == null ? null : String.valueOf(previous);

    UUID eventId = Ids.next();
    OffsetDateTime occurredAt =
        request.occurredAt() == null ? OffsetDateTime.now() : request.occurredAt();
    sql.execute(
        """
        insert into listing_status_event
          (id,tenant_id,listing_id,from_status,to_status,source,note,occurred_at,
           event_type,platform_status,internal_status,conflict_detected,conflict_note,resolution,
           rejection_reason,detail,received_at,ingest_channel,ingested_by)
        values (?,?,?,?,?,'PLATFORM',?,?::timestamptz,?,?,?,?,?,?,?,?::jsonb,now(),?,?)
        """,
        eventId, tenant, listingId, fromStatus, platformStatus, note(request.note()),
        Timestamp.from(occurredAt.toInstant()),
        eventType, platformStatus, conflict.detected() ? internalStatus : "",
        conflict.detected(), conflict.note(), conflict.resolution(),
        rejectionReason, writeJson(safeMap(request.detail())), ingestChannel, actorId);

    // ---- 待办：可一键修正重发 / 需人工处理（冲突告警由 DB 触发器落）
    String queueKind = queueKindFor(eventType);
    if (queueKind != null) {
      sql.execute(
          """
          insert into listing_status_action_queue
            (id,tenant_id,event_id,listing_id,kind,state,event_type,rejection_reason,note,actor_id,created_at,updated_at)
          values (?,?,?,?,?,'OPEN',?,?,?,?,now(),now())
          """,
          Ids.next(), tenant, eventId, listingId, queueKind, eventType, rejectionReason,
          queueNote(eventType, rejectionReason), actorId);
    }

    return loadEvent(tenant, eventId);
  }

  private static Map<String, String> safeMap(Map<String, String> raw) {
    return raw == null ? Map.of() : raw;
  }

  /** 事件类型 → 需处理队列的项类型；审核通过不产生待办（没有要处理的事）。 */
  private static String queueKindFor(String eventType) {
    return switch (eventType) {
      case "REJECTED", "DELISTED", "SUPPRESSED" -> "REPUBLISH";
      case "THROTTLED", "CATEGORY_REVIEW", "PRICE_ALERT" -> "REVIEW";
      default -> null;
    };
  }

  private static String queueNote(String eventType, String rejectionReason) {
    return switch (eventType) {
      case "REJECTED" -> "平台驳回，可按驳回原因批量修正重发：" + rejectionReason;
      case "DELISTED" -> "平台已下架，需要重新上架";
      case "SUPPRESSED" -> "平台已抑制该商品，需要修正后重新提交";
      case "THROTTLED" -> "平台限流，需要人工检查内容与投放";
      case "CATEGORY_REVIEW" -> "平台在审类目，需要人工确认类目归属";
      case "PRICE_ALERT" -> "平台报了价格异常，需人工核对（判定归 05 定价）";
      default -> "";
    };
  }

  /** 定位商品：给 listingId 就用它；否则按 (店铺编码, 平台商品 ID) 定位。 */
  private Record resolveListing(UUID tenant, IngestStatusEventRequest request) {
    StringBuilder sqlText =
        new StringBuilder(
            """
            select l.id,l.shop_id,l.marketplace_id,l.status,l.external_item_id,l.title,
                   s.code as shop_code,p.code as platform_code
            from listing l
            join shop s on s.tenant_id=l.tenant_id and s.id=l.shop_id
            join platform p on p.id=s.platform_id
            where l.tenant_id=?
            """);
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);

    if (request.listingId() != null) {
      sqlText.append(" and l.id=?");
      binds.add(request.listingId());
    } else {
      String shopCode = blankToNull(request.shopCode());
      String externalItemId = blankToNull(request.externalItemId());
      if (shopCode == null || externalItemId == null) {
        throw unprocessable("请给 listingId，或给 (店铺编码 shopCode + 平台商品 ID externalItemId)");
      }
      sqlText.append(" and s.code=? and l.external_item_id=?");
      binds.add(shopCode);
      binds.add(externalItemId);
    }
    return sql.fetchOne(sqlText.toString(), binds.toArray());
  }

  // ==================================================================
  // 状态变更记录（真分页 + 筛选）
  // ==================================================================

  /**
   * 状态变更记录列表。
   *
   * <p>🔴 默认 {@code source='PLATFORM'}：本模块记的是**平台回流**那几条。
   * {@code source=ALL} 才把内部人工改状态（V171 触发器写的 MANUAL）那几条也带出来 ——
   * 两者都是"状态变化"，但混在一起看会问"我改的状态怎么跑到平台回流里来了"。
   */
  public ListingStatusEventPage events(
      String source, String eventType, String shopCode, String platformCode, Boolean conflict,
      int page, int size) {
    UUID tenant = currentTenant();
    StringBuilder where = new StringBuilder(" where e.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);

    String src = blankToNull(source);
    if (src != null && !"ALL".equalsIgnoreCase(src)) {
      where.append(" and e.source=?");
      binds.add(src.toUpperCase(Locale.ROOT));
    }
    String type = blankToNull(eventType);
    if (type != null) {
      where.append(" and e.event_type=?");
      binds.add(type.toUpperCase(Locale.ROOT));
    }
    String shop = blankToNull(shopCode);
    if (shop != null) {
      where.append(" and s.code=?");
      binds.add(shop);
    }
    String platform = blankToNull(platformCode);
    if (platform != null) {
      where.append(" and p.code=?");
      binds.add(platform);
    }
    if (conflict != null) {
      where.append(conflict ? " and e.conflict_detected" : " and not e.conflict_detected");
    }

    long total = count(EVENT_FROM + where.toString(), binds.toArray());
    int lim = clampSize(size);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(lim);
    pageBinds.add(Math.max(0, page) * lim);
    List<ListingStatusEventRow> items =
        sql.fetch(
                EVENT_FROM + where.toString() + " order by e.occurred_at desc, e.received_at desc, e.id desc "
                    + "limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(this::eventRow)
            .toList();
    return new ListingStatusEventPage(items, Math.max(0, page), lim, total);
  }

  private static final String EVENT_FROM =
      """
      select e.id,e.listing_id,l.external_item_id,l.title,s.code as shop_code,p.code as platform_code,
             e.event_type,e.source,e.ingest_channel,e.from_status,e.to_status,e.platform_status,
             e.internal_status,e.conflict_detected,e.conflict_note,e.resolution,e.rejection_reason,
             e.detail,e.note,e.occurred_at,e.received_at
      from listing_status_event e
      join listing l on l.tenant_id=e.tenant_id and l.id=e.listing_id
      join shop s on s.tenant_id=e.tenant_id and s.id=l.shop_id
      join platform p on p.id=s.platform_id
      """;

  private long count(String from, Object[] binds) {
    Record r = sql.fetchOne("select count(*) as n from (" + from + ") t", binds);
    Long n = r == null ? null : r.get("n", Long.class);
    return n == null ? 0 : n;
  }

  private ListingStatusEventRow eventRow(Record r) {
    return new ListingStatusEventRow(
        r.get("id", UUID.class),
        r.get("listing_id", UUID.class),
        r.get("external_item_id", String.class),
        r.get("title", String.class),
        r.get("shop_code", String.class),
        r.get("platform_code", String.class),
        r.get("event_type", String.class),
        r.get("source", String.class),
        r.get("ingest_channel", String.class),
        nullToEmpty(r.get("from_status", String.class)),
        nullToEmpty(r.get("to_status", String.class)),
        nullToEmpty(r.get("platform_status", String.class)),
        nullToEmpty(r.get("internal_status", String.class)),
        Boolean.TRUE.equals(r.get("conflict_detected", Boolean.class)),
        nullToEmpty(r.get("conflict_note", String.class)),
        r.get("resolution", String.class),
        nullToEmpty(r.get("rejection_reason", String.class)),
        jsonText(r.get("detail", Object.class)),
        nullToEmpty(r.get("note", String.class)),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("received_at", OffsetDateTime.class));
  }

  /** 单条状态变更记录。 */
  public ListingStatusEventRow event(String eventId) {
    UUID tenant = currentTenant();
    UUID id = parseId(eventId);
    return loadEvent(tenant, id);
  }

  private ListingStatusEventRow loadEvent(UUID tenant, UUID eventId) {
    Record r = sql.fetchOne(EVENT_FROM + " where e.tenant_id=? and e.id=?", tenant, eventId);
    if (r == null) {
      throw notFound();
    }
    return eventRow(r);
  }

  /** 某个商品的状态变更记录（回流视角）。 */
  public ListingStatusEventPage listingHistory(
      String listingId, String source, int page, int size) {
    UUID tenant = currentTenant();
    UUID id = parseId(listingId);
    if (sql.fetchOne("select 1 from listing where tenant_id=? and id=?", tenant, id) == null) {
      throw notFound();
    }
    StringBuilder where = new StringBuilder(" where e.tenant_id=? and e.listing_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    binds.add(id);
    String src = blankToNull(source);
    if (src != null && !"ALL".equalsIgnoreCase(src)) {
      where.append(" and e.source=?");
      binds.add(src.toUpperCase(Locale.ROOT));
    }
    long total = count(EVENT_FROM + where.toString(), binds.toArray());
    int lim = clampSize(size);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(lim);
    pageBinds.add(Math.max(0, page) * lim);
    List<ListingStatusEventRow> items =
        sql.fetch(
                EVENT_FROM + where.toString() + " order by e.occurred_at desc, e.received_at desc, e.id desc "
                    + "limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(this::eventRow)
            .toList();
    return new ListingStatusEventPage(items, Math.max(0, page), lim, total);
  }

  // ==================================================================
  // 需处理队列 + 冲突告警
  // ==================================================================

  /**
   * 需处理队列（真分页 + 筛选）。
   *
   * <p>🔴 筛选口径：{@code kind}（REPUBLISH / REVIEW / CONFLICT_ALERT）、
   * {@code state}、{@code eventType}、{@code rejectionReason}（🔴 一键处理按它筛）、
   * {@code shopCode}。
   */
  public ListingStatusQueuePage queue(
      String kind, String state, String eventType, String rejectionReason, String shopCode,
      int page, int size) {
    UUID tenant = currentTenant();
    StringBuilder where = new StringBuilder(" where q.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);

    String k = blankToNull(kind);
    if (k != null) {
      where.append(" and q.kind=?");
      binds.add(k.toUpperCase(Locale.ROOT));
    }
    String st = blankToNull(state);
    if (st != null) {
      where.append(" and q.state=?");
      binds.add(st.toUpperCase(Locale.ROOT));
    }
    String type = blankToNull(eventType);
    if (type != null) {
      where.append(" and q.event_type=?");
      binds.add(type.toUpperCase(Locale.ROOT));
    }
    String reason = blankToNull(rejectionReason);
    if (reason != null) {
      where.append(" and q.rejection_reason=?");
      binds.add(reason);
    }
    String shop = blankToNull(shopCode);
    if (shop != null) {
      where.append(" and s.code=?");
      binds.add(shop);
    }

    long total = count(QUEUE_FROM + where.toString(), binds.toArray());
    int lim = clampSize(size);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(lim);
    pageBinds.add(Math.max(0, page) * lim);
    List<ListingStatusQueueRow> items =
        sql.fetch(
                QUEUE_FROM + where.toString() + " order by q.created_at desc, q.id desc limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(this::queueRow)
            .toList();
    return new ListingStatusQueuePage(items, Math.max(0, page), lim, total);
  }

  /** 冲突与告警区：只取 CONFLICT_ALERT 行（以平台为准的告警）。 */
  public ListingStatusQueuePage conflicts(int page, int size) {
    return queue("CONFLICT_ALERT", null, null, null, null, page, size);
  }

  private static final String QUEUE_FROM =
      """
      select q.id,q.event_id,q.listing_id,l.shop_id,l.marketplace_id,l.external_item_id,l.title,
             s.code as shop_code,p.code as platform_code,q.event_type,q.kind,q.state,
             q.rejection_reason,q.operation_id,q.idempotency_key,q.note,q.created_at,q.updated_at,q.completed_at,
             o.status as operation_status,o.verification as operation_verification
      from listing_status_action_queue q
      join listing l on l.tenant_id=q.tenant_id and l.id=q.listing_id
      join shop s on s.tenant_id=q.tenant_id and s.id=l.shop_id
      join platform p on p.id=s.platform_id
      left join platform_operation o on o.tenant_id=q.tenant_id and o.id=q.operation_id
      """;

  private ListingStatusQueueRow queueRow(Record r) {
    return new ListingStatusQueueRow(
        r.get("id", UUID.class),
        r.get("event_id", UUID.class),
        r.get("listing_id", UUID.class),
        r.get("external_item_id", String.class),
        r.get("title", String.class),
        r.get("shop_code", String.class),
        r.get("platform_code", String.class),
        r.get("event_type", String.class),
        r.get("kind", String.class),
        r.get("state", String.class),
        nullToEmpty(r.get("rejection_reason", String.class)),
        r.get("operation_id", UUID.class),
        nullToEmpty(r.get("idempotency_key", String.class)),
        nullToEmpty(r.get("operation_status", String.class)),
        nullToEmpty(r.get("operation_verification", String.class)),
        nullToEmpty(r.get("note", String.class)),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class),
        r.get("completed_at", OffsetDateTime.class));
  }

  /** 单条队列项。 */
  public ListingStatusQueueRow queueItem(String queueId) {
    UUID tenant = currentTenant();
    UUID id = parseId(queueId);
    Record r = sql.fetchOne(QUEUE_FROM + " where q.tenant_id=? and q.id=?", tenant, id);
    if (r == null) {
      throw notFound();
    }
    return queueRow(r);
  }

  // ==================================================================
  // 🔴🔴 一键处理：按驳回原因批量修正重发（经 2.5.1.1 的 platform_operation）
  // ==================================================================

  /**
   * 🔴🔴 一键处理。
   *
   * <p>逐条不变量：
   *
   * <ol>
   *   <li><b>只处理 REPUBLISH 类</b>：限流 / 类目审核 / 价格异常那几条是「需人工处理」，
   *       盲目重发解决不了限流。冲突告警行也不是待办，是事实告警。
   *   <li><b>幂等键由服务端派生</b>（{@code LSR-<队列项 id>}），**不接受调用方自拟** ——
   *       自拟就能两次「不同键、同一个目标」，恰好绕开幂等。重复点「一键处理」
   *       **不会**产生第二个平台任务。
   *   <li><b>已派发过的跳过并写明原因</b>，不静默丢弃 —— 静默丢弃会让运营以为全发出去了。
   *   <li><b>写 02 队列而不是自己发平台</b>：发起即在途（DISPATCHED），
   *       平台侧结果由 02 队列回报；本模块**不伪造平台返回**。
   * </ol>
   */
  public RepublishReceipt republish(RepublishRequest request, UUID actorId) {
    UUID tenant = currentTenant();
    int limit = request.limit() == null ? 50 : request.limit();
    if (limit < 1 || limit > 500) {
      throw unprocessable("数量上限必须在 1~500 之间");
    }

    // 🔴 基础筛选**不含 state**：state 条件在下面两条查询上**分别**加 ——
    //   可发起的（OPEN / ACKNOWLEDGED）与已处理过的（其余）各查一次。
    // 写成一条再补一句 "and state not in (...)" 会得到自相矛盾的条件（恒空），
    // 已处理过的那批就永远查不出来 ⇒ 静默丢弃，正是这里要防的。
    StringBuilder where = new StringBuilder(" where q.tenant_id=? and q.kind='REPUBLISH'");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);

    String reason = blankToNull(request.rejectionReason());
    if (reason != null) {
      where.append(" and q.rejection_reason=?");
      binds.add(reason);
    }
    String shop = blankToNull(request.shopCode());
    if (shop != null) {
      where.append(" and s.code=?");
      binds.add(shop);
    }
    List<String> types = normalizeEventTypes(request.eventTypes());
    if (!types.isEmpty()) {
      where.append(" and q.event_type in (");
      for (int i = 0; i < types.size(); i++) {
        where.append(i == 0 ? "?" : ",?");
        binds.add(types.get(i));
      }
      where.append(')');
    }
    List<UUID> explicit = safe(request.queueItemIds());
    if (!explicit.isEmpty()) {
      where.append(" and q.id in (");
      for (int i = 0; i < explicit.size(); i++) {
        where.append(i == 0 ? "?" : ",?");
        binds.add(explicit.get(i));
      }
      where.append(')');
    }

    // 🔴🔴 两条查询，**分开**是有原因的：
    //   candidates  = 还能发起的（OPEN / ACKNOWLEDGED）—— 这次真的会对它们发起修正重发
    //   handled     = 同样命中筛选条件、但**已经处理过**的 —— 它们要**逐条写明为什么没发**
    // 如果只查 candidates，已派发过的那些会被**静默滤掉**：运营按「标题过长」筛完点一键处理，
    // 回执说"命中 0 条、发起 0 条"，而真相是"早就发过了" —— 那等于把"没做"说成了"没找到"。
    List<Record> candidates =
        sql.fetch(
            QUEUE_FROM + where.toString() + " and q.state in ('OPEN','ACKNOWLEDGED') "
                + "order by q.created_at desc, q.id desc limit ?",
            appendBinds(binds, limit));

    String handledWhere = where.toString() + " and q.state not in ('OPEN','ACKNOWLEDGED')";
    long handledTotal = count(QUEUE_FROM + handledWhere, binds.toArray());
    List<Record> handled =
        sql.fetch(
            QUEUE_FROM + handledWhere + " order by q.created_at desc, q.id desc limit ?",
            appendBinds(binds, 200));

    List<RepublishReceiptItem> dispatched = new ArrayList<>();
    List<String> rejected = new ArrayList<>();

    for (Record row : handled) {
      rejected.add(
          row.get("id", UUID.class)
              + "：状态为 "
              + row.get("state", String.class)
              + "，不再重复发起修正重发（幂等键 LSR-<队列项 id> 不变，平台侧不会看到第二个任务）");
    }

    for (Record row : candidates) {
      UUID queueId = row.get("id", UUID.class);
      // 🔴 行锁：并发点两次「一键处理」时，两个请求在这里排队，第二个会看到已派发并跳过
      Record locked =
          sql.fetchOne(
              "select id,state,operation_id,idempotency_key from listing_status_action_queue "
                  + "where tenant_id=? and id=? for update",
              tenant, queueId);
      if (locked == null) {
        rejected.add(queueId + "：队列项已不存在");
        continue;
      }
      String state = locked.get("state", String.class);
      if ("DISPATCHED".equals(state) || "RESOLVED".equals(state) || "CANCELLED".equals(state)) {
        rejected.add(queueId + "：状态为 " + state + "，不再重复发起修正重发");
        continue;
      }
      UUID operationId = enqueueRepublish(tenant, row, locked, actorId);
      // 🔴 回执里的队列状态**读回来**而不是写死 PENDING：并发 / 重复点击时这条可能早就被发过了
      Record operation =
          sql.fetchOne(
              "select status,verification from platform_operation where tenant_id=? and id=?",
              tenant, operationId);
      dispatched.add(
          new RepublishReceiptItem(
              queueId,
              row.get("event_id", UUID.class),
              row.get("listing_id", UUID.class),
              row.get("external_item_id", String.class),
              row.get("shop_code", String.class),
              row.get("event_type", String.class),
              nullToEmpty(row.get("rejection_reason", String.class)),
              operationId,
              "LSR-" + queueId,
              operation == null ? "PENDING" : nullToEmpty(operation.get("status", String.class)),
              operation == null ? "NOT_REQUIRED" : nullToEmpty(operation.get("verification", String.class))));
    }

    String note =
        "修正重发已入 02 队列（2.5.1.1 platform_operation，幂等键 LSR-<队列项 id>）；"
            + "平台侧结果由队列回报，本模块不伪造平台返回";
    if (handled.size() < handledTotal) {
      note += "。另有 " + handledTotal + " 条已处理过的命中筛选条件，这里只列出前 " + handled.size() + " 条";
    }
    return new RepublishReceipt(
        candidates.size() + (int) handledTotal,
        dispatched.size(),
        rejected.size(),
        dispatched,
        rejected,
        note);
  }

  private static Object[] appendBinds(List<Object> binds, int limit) {
    List<Object> out = new ArrayList<>(binds);
    out.add(limit);
    return out.toArray();
  }

  /** 事件类型筛选归一：不在取值域里的**逐个点名**拒绝（不静默忽略）。 */
  private List<String> normalizeEventTypes(List<String> raw) {
    List<String> out = new ArrayList<>();
    List<String> bad = new ArrayList<>();
    for (String value : safe(raw)) {
      String type = text(value).toUpperCase(Locale.ROOT);
      if (type.isEmpty()) {
        continue;
      }
      if (EVENT_TYPES.contains(type)) {
        if (!out.contains(type)) {
          out.add(type);
        }
      } else {
        bad.add(type);
      }
    }
    if (!bad.isEmpty()) {
      throw unprocessable(
          "未知的事件类型：" + String.join("、", bad) + "。只认这七种：" + String.join(" / ", EVENT_TYPES));
    }
    return out;
  }

  /**
   * 🔴 写 02 队列任务（V2 {@code platform_operation}）并把队列项推进到在途。
   *
   * <p>{@code operation='PUBLISH'}：修正重发就是"把这个商品重新发上去"，与刊登发布走同一条
   * 平台写通道，所以**复用既有取值域** —— 不改 V2 的 CHECK（那是 2.5.1.1 的对外契约）。
   * 方向靠 payload 里的 {@code statusRepublish=true} 区分，与 4.3.2.1 用
   * {@code contentWriteback=true} 区分方向同一手法。
   *
   * <p>幂等键 {@code LSR-<队列项 id>} 由**服务端**派生：同一个队列项永远是同一个键 ⇒
   * 重复点「一键处理」不会在平台侧产生两个任务。插入走
   * {@code on conflict (tenant_id, idempotency_key) do nothing}，因此并发也不会插出两条。
   */
  private UUID enqueueRepublish(UUID tenant, Record row, Record locked, UUID actorId) {
    UUID queueId = locked.get("id", UUID.class);
    String idempotencyKey = "LSR-" + queueId;
    UUID shopId = row.get("shop_id", UUID.class);
    UUID listingId = row.get("listing_id", UUID.class);
    UUID eventId = row.get("event_id", UUID.class);

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("statusRepublish", true);
    payload.put("listingStatusEventId", eventId.toString());
    payload.put("listingId", listingId.toString());
    payload.put("externalItemId", nullToEmpty(row.get("external_item_id", String.class)));
    payload.put("shopCode", nullToEmpty(row.get("shop_code", String.class)));
    payload.put("eventType", nullToEmpty(row.get("event_type", String.class)));
    payload.put("rejectionReason", nullToEmpty(row.get("rejection_reason", String.class)));

    OffsetDateTime now = OffsetDateTime.now();
    UUID proposed = Ids.next();
    org.jooq.Result<Record> inserted =
        sql.fetch(
            """
            insert into platform_operation
              (id,tenant_id,shop_id,marketplace_id,operation,idempotency_key,payload,status,
               platform_request_id,verification,verified_at,attempts,last_error,occurred_at,updated_at)
            values (?,?,?,?,'PUBLISH',?,?::jsonb,'PENDING','','NOT_REQUIRED',null,1,'',?,?)
            on conflict (tenant_id, idempotency_key) do nothing
            returning id
            """,
            proposed, tenant, shopId, row.get("marketplace_id", UUID.class), idempotencyKey,
            writeJson(payload), Timestamp.from(now.toInstant()), Timestamp.from(now.toInstant()));

    UUID operationId = proposed;
    if (inserted.isEmpty()) {
      // 幂等键已存在（并发或重复点击）⇒ 复用**同一个**队列任务，不新开一个
      Object existing =
          sql.fetchValue(
              "select id from platform_operation where tenant_id=? and idempotency_key=?",
              tenant, idempotencyKey);
      operationId = existing == null ? proposed : UUID.fromString(String.valueOf(existing));
    } else {
      operationId = inserted.get(0).get("id", UUID.class);
    }

    sql.execute(
        """
        update listing_status_action_queue
        set state='DISPATCHED', operation_id=?, idempotency_key=?, actor_id=?, updated_at=now()
        where tenant_id=? and id=?
        """,
        operationId, idempotencyKey, actorId, tenant, queueId);
    return operationId;
  }

  // ==================================================================
  // 队列项的人工动作
  // ==================================================================

  /** 人工确认（看到了，先记一笔）。OPEN → ACKNOWLEDGED。 */
  public ListingStatusQueueRow acknowledge(String queueId, String noteText, UUID actorId) {
    UUID tenant = currentTenant();
    UUID id = parseId(queueId);
    Record current = loadQueue(tenant, id);
    String state = current.get("state", String.class);
    if ("ACKNOWLEDGED".equals(state)) {
      return queueItem(queueId);
    }
    if (!"OPEN".equals(state)) {
      throw unprocessable("只有待处理（OPEN）的队列项可确认，当前是 " + state);
    }
    sql.execute(
        "update listing_status_action_queue set state='ACKNOWLEDGED', note=?, actor_id=?, updated_at=now() "
            + "where tenant_id=? and id=? and state='OPEN'",
        note(noteText), actorId, tenant, id);
    return queueItem(queueId);
  }

  /** 人工处理完成。OPEN / ACKNOWLEDGED / DISPATCHED → RESOLVED。 */
  public ListingStatusQueueRow resolve(String queueId, String noteText, UUID actorId) {
    UUID tenant = currentTenant();
    UUID id = parseId(queueId);
    Record current = loadQueue(tenant, id);
    String state = current.get("state", String.class);
    if ("RESOLVED".equals(state)) {
      return queueItem(queueId);
    }
    if ("CANCELLED".equals(state)) {
      throw unprocessable("已作废的队列项不能标记为处理完成");
    }
    sql.execute(
        "update listing_status_action_queue set state='RESOLVED', completed_at=now(), note=?, actor_id=?, "
            + "updated_at=now() where tenant_id=? and id=?",
        note(noteText), actorId, tenant, id);
    return queueItem(queueId);
  }

  private Record loadQueue(UUID tenant, UUID id) {
    Record r =
        sql.fetchOne(
            "select id,state,operation_id from listing_status_action_queue where tenant_id=? and id=?",
            tenant, id);
    if (r == null) {
      throw notFound();
    }
    return r;
  }
}
