package com.starcon.channel;

import static com.starcon.channel.LogisticsPickupSignoffModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * 10.3.2.2 揽收与签收确认。
 *
 * <p>蓝图：「确认承运商揽收（避免超时未揽收被平台判未发货）与签收（触发售后窗口关闭
 * 与评价邀请时机）」，输入「承运商揽收/签收事件；SLA 时限」，输出「状态更新 + 提醒任务」，
 * 并加粗「<b>揽收状态直接影响平台履约指标，需主动监控而非等轨迹同步</b>」。
 *
 * <p>🔴 本类的全部要害是那句「主动监控而非等轨迹同步」。它意味着：
 *
 * <ol>
 *   <li><b>确认单不能等轨迹同步顺带产生</b>。轨迹同步（10.3.1.1 的
 *       {@code POST /subjects/sync}）只管运单主体，揽收确认单必须由本模块自己
 *       主动扫（{@code POST /reconcile}）—— 等轨迹同步「顺便」建单，等于
 *       「没人同步轨迹就没人盯揽收」，而这正是平台判我们未发货的那条路。</li>
 *   <li><b>超时判定是读时算出来的</b>。{@code pickup_state='OVERDUE'} 由巡检按
 *       {@code now()} 与时限快照比对得出，存一个静态结论会让「现在超时了吗」
 *       这个问题答错（同 V212 的 ETA_STATUS_EXPR 口径）。</li>
 *   <li><b>时限是快照</b>。{@code pickup_deadline_at} 建单时算好就定住 ——
 *       事后改 SLA 规则不能让一笔已经延误的货变成准点。</li>
 * </ol>
 *
 * <p>数据来源纪律（AGENTS.md 模块边界纪律）：
 *
 * <ul>
 *   <li><b>责任起点</b>来自 10.3.2.1 交运批次：同库只读 {@code handover_batch} /
 *       {@code handover_manifest_item}（V129）。**逐件**粒度 —— 交接清单才是
 *       「哪一件何时交给了谁」的凭据。</li>
 *   <li><b>承运商事件</b>来自 10.3.1.1 轨迹账本：同库只读
 *       {@code logistics_tracking_subject} / {@code logistics_tracking_event}（V212）。
 *       本类<b>一条 SQL 都不碰 trade / warehouse 的库</b>。</li>
 *   <li><b>下游</b>（9.4.2.1 回传、15 客服索评）<b>不跨服务调用</b>：
 *       本模块只产出待通知记录并写明下游状态，不假装下游已接线。</li>
 * </ul>
 */
@Service
public class LogisticsPickupSignoffService {

  // ================================================================
  // 常量：枚举取值与标签
  // ================================================================

  /** 确认单状态（V217 的 CHECK 就是这五档）。 */
  static final List<String> CASE_STATUSES =
      List.of("PENDING_PICKUP", "PICKED_UP", "PICKUP_OVERDUE", "DELIVERED", "VOID");

  /** 揽收状态。 */
  static final List<String> PICKUP_STATES = List.of("PENDING", "CONFIRMED", "OVERDUE");

  /** 签收状态。 */
  static final List<String> DELIVERY_STATES = List.of("PENDING", "CONFIRMED");

  /** 提醒类型。 */
  static final List<String> REMINDER_KINDS =
      List.of("PICKUP_DUE_SOON", "PICKUP_OVERDUE", "DELIVERY_CONFIRMED");

  /** 提醒状态。 */
  static final List<String> REMINDER_STATUSES =
      List.of("PENDING", "ACKNOWLEDGED", "RESOLVED", "CANCELLED");

  /** 待通知类型。 */
  static final List<String> NOTIFICATION_KINDS =
      List.of(
          "PLATFORM_FULFILLMENT_WRITEBACK",
          "REVIEW_INVITATION",
          "AFTER_SALES_WINDOW_CLOSE");

  /** 待通知（下游）状态。 */
  static final List<String> NOTIFICATION_STATUSES =
      List.of("PENDING", "READY", "CONSUMED", "FAILED", "CANCELLED");

  /** 缺口码。 */
  static final List<String> GAP_CODES =
      List.of(
          "NO_WAYBILL",
          "TRACKING_SUBJECT_MISSING",
          "SUBJECT_AMBIGUOUS",
          "BATCH_VOID",
          "SLA_UNCONFIGURED");

  /** 缺口优先级：数字越小越「根本」，同一件只能报一个，取最根本的那个。 */
  private static int gapPriority(String code) {
    return switch (text(code)) {
      case "NO_WAYBILL" -> 1;
      case "TRACKING_SUBJECT_MISSING" -> 2;
      case "SUBJECT_AMBIGUOUS" -> 3;
      case "BATCH_VOID" -> 4;
      case "SLA_UNCONFIGURED" -> 5;
      default -> 9;
    };
  }

  /** 数量统一 HALF_UP 到 6 位小数，与 DB 的 numeric(19,6) 对齐（禁浮点）。 */
  private static final int QUANTITY_SCALE = 6;

  /** 一小时 = 3 600 000 000 微秒。numeric(19,6) 的小时精度换到微秒不丢。 */
  private static final BigDecimal MICROS_PER_HOUR = BigDecimal.valueOf(3_600_000_000L);

  // ================================================================
  // 依赖
  // ================================================================

  private final DSLContext sql;
  private final ObjectMapper json;

  public LogisticsPickupSignoffService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String escapeLike(String raw) {
    return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static BigDecimal scale(BigDecimal value) {
    return value == null ? null : value.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
  }

  /** 小时 → 时长（走微秒，保留 numeric(19,6) 的精度，不用浮点）。 */
  private static Duration hoursToDuration(BigDecimal hours) {
    BigDecimal micros =
        hours.multiply(MICROS_PER_HOUR).setScale(0, RoundingMode.HALF_UP);
    long m = micros.longValueExact();
    return Duration.ofSeconds(m / 1_000_000L).plusNanos((m % 1_000_000L) * 1000L);
  }

  /** 时长 → 小时（BigDecimal，保留 6 位小数）。 */
  private static BigDecimal durationHours(Duration duration) {
    BigDecimal seconds =
        BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(), 9));
    return seconds.divide(BigDecimal.valueOf(3600), QUANTITY_SCALE, RoundingMode.HALF_UP);
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 在进入事务时绑定）。 */
  private UUID currentTenant() {
    Record row = sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')");
    String raw = row == null ? null : row.get(0, String.class);
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

  /** 跨租户与不存在返回<b>同一个 404 且理由逐字相同</b>（照抄 10.1.1.2 / 10.3.1.1 同款口径）。 */
  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "揽收签收确认单不存在");
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  public LogisticsPickupSignoffCapabilities capabilities() {
    List<LogisticsPickupSignoffCapabilityDimension> dimensions =
        List.of(
            new LogisticsPickupSignoffCapabilityDimension(
                "pickup_confirmation",
                "🔴 承运商揽收确认（超时未揽收 = 平台判我们未发货）",
                true,
                "本服务 V217 logistics_pickup_signoff_case + 同库只读 V212 轨迹事件账本",
                "",
                "承运商推来 PICKED_UP 节点即确认；没有节点就没有确认，不猜"),
            new LogisticsPickupSignoffCapabilityDimension(
                "delivery_confirmation",
                "🔴 承运商签收确认（触发售后窗口关闭与评价邀请时机）",
                true,
                "本服务 V217 logistics_pickup_signoff_case.delivery_* + V212 DELIVERED 节点",
                "",
                "签收时刻是售后窗口与评价邀请的计时起点"),
            new LogisticsPickupSignoffCapabilityDimension(
                "sla_deadline",
                "🔴 揽收时限（SLA）与超时判定",
                true,
                "本服务 V217 logistics_pickup_signoff_sla_rule + 确认单上的时限快照",
                "",
                "时限是**建单时的快照**：事后改规则不会把已经延误的货变成准点；"
                    + "没有配规则的件如实报 SLA_UNCONFIGURED，不用默认值冒充"),
            new LogisticsPickupSignoffCapabilityDimension(
                "active_monitoring",
                "🔴 主动监控（不等轨迹同步才看揽收）",
                true,
                "本服务 POST /channel/logistics-pickup-signoff/reconcile",
                "",
                "巡检按 now() 判超时并生成提醒；不自带调度器（多副本要做主节点选举，"
                    + "那是平台能力不是领域能力），由外部调度触发"),
            new LogisticsPickupSignoffCapabilityDimension(
                "reminder_tasks",
                "提醒任务（即将超时 / 已超时 / 已签收）",
                true,
                "本服务 V217 logistics_pickup_signoff_reminder",
                "",
                "同一件 + 同一类 + 同一责任窗口只提醒一次（DB 唯一约束）"),
            new LogisticsPickupSignoffCapabilityDimension(
                "manual_attestation",
                "人工确认（承运商通道未接通时的凭据登记）",
                true,
                "本服务 POST /channel/logistics-pickup-signoff/cases/{id}/attest",
                "",
                "人工确认带凭据号与时刻，且**不覆盖**承运商事件的证据来源标记"),
            new LogisticsPickupSignoffCapabilityDimension(
                "platform_writeback",
                "平台履约回传（揽收状态回传平台）",
                false,
                "9.4.2.1 回传状态已在 **trade** 交付，但 channel 与它之间**没有接线**",
                "9.4.2.1",
                "本模块只产出待通知记录（downstream_module=9.4.2.1，状态 READY 待消费），"
                    + "**不假装已回传**；跨服务接线不在本车道范围内"),
            new LogisticsPickupSignoffCapabilityDimension(
                "review_invitation",
                "评价邀请（Request a Review）",
                false,
                "15.2.2.1 索评规则 / 15.2.2.2 未开发",
                "15.2.2.1",
                "本模块只算**时机**（签收 + 延迟）并落待通知记录，邀请本身归 15"),
            new LogisticsPickupSignoffCapabilityDimension(
                "after_sales_window",
                "售后窗口关闭",
                false,
                "15 客服未开发",
                "15",
                "本模块只算窗口到期时刻并落待通知记录，关窗动作归 15"),
            new LogisticsPickupSignoffCapabilityDimension(
                "carrier_adapter",
                "承运商出网通道（主动拉取揽收/签收）",
                false,
                "10.1.1.1 承运商档案与规则已交付，连接与凭据未交付",
                "10.1.1.1",
                "没有通道时，揽收确认只能来自承运商**推送**或人工确认；"
                    + "本模块**刻意不放假 vendor 实现**"),
            new LogisticsPickupSignoffCapabilityDimension(
                "parcel_level_pickup",
                "逐件（逐箱）揽收状态",
                true,
                "本服务 V217 确认单一行 = 一个交运件（10.3.2.1 交接清单的一行）",
                "",
                "逐件粒度才有追责落点：纠纷问的是「哪一箱没被揽收」"),
            new LogisticsPickupSignoffCapabilityDimension(
                "exception_disposition",
                "异常件处置（联系承运商 / 理赔 / 补发 / 改址）",
                false,
                "10.3.1.2 异常件未交付",
                "10.3.1.2",
                "本模块只**标记**承运商报的异常节点，不提供任何处置入口"),
            new LogisticsPickupSignoffCapabilityDimension(
                "freight_cost_settlement",
                "运费成本归集与结算",
                false,
                "12 财务未交付；本模块也不产生任何金额",
                "12",
                "本模块只处理时间与状态，**不涉及任何金额** —— 自造一个运费列会造出第二套成本口径"));
    return new LogisticsPickupSignoffCapabilities(
        dimensions,
        CASE_STATUSES,
        PICKUP_STATES,
        DELIVERY_STATES,
        REMINDER_KINDS,
        REMINDER_STATUSES,
        NOTIFICATION_KINDS,
        NOTIFICATION_STATUSES,
        GAP_CODES,
        "责任起点来自 10.3.2.1 交运批次（同库只读，逐件），"
            + "承运商事件来自 10.3.1.1 轨迹账本（同库只读）；"
            + "本模块一条 SQL 都不碰 trade / warehouse 的库；"
            + "下游 9.4.2.1 与 15 只落待通知记录，**不假装已回传**。",
        "🔴 揽收状态要**主动监控**：巡检按 now() 判超时并生成提醒，"
            + "不等轨迹同步顺带建单；"
            + "时限是**建单时的快照**，事后改规则不会把已经延误的货变成准点；"
            + "运单号对不上时如实报缺口，**不猜**；"
            + "本页**不涉及任何金额**（成本归 12 财务）。");
  }

  // ==================================================================
  // 内部视图
  // ==================================================================

  /** 一个交运件（交接清单的一行）与其批次的上下文。 */
  private record ManifestView(
      UUID manifestItemId,
      UUID batchId,
      String batchNo,
      String batchStatus,
      String carrierCode,
      int parcelNo,
      String parcelRef,
      String waybillNo,
      OffsetDateTime handoverAt,
      OffsetDateTime dispatchedAt,
      BigDecimal grossWeightKg) {}

  /** 轨迹主体匹配结果。 */
  private record SubjectView(
      UUID id, int matchCount, UUID channelId, String salesOrderNo, String destinationCountry) {}

  /** SLA 规则命中结果。 */
  private record SlaView(
      UUID id,
      BigDecimal pickupSlaHours,
      BigDecimal reminderLeadHours,
      BigDecimal reviewInviteDelayHours,
      BigDecimal afterSalesWindowHours) {}

  // ==================================================================
  // 写：同步确认单（幂等投影）
  // ==================================================================

  /**
   * 从 10.3.2.1 的交接清单投影出确认单。
   *
   * <p><b>只有已登记交接的批次才建单</b>（DISPATCH_PENDING / HANDED_OVER）：
   * 批次还是 DRAFT 意味着**责任还没交出去**，此时判「超时未揽收」是无中生有。
   * 批次登记交接之后再同步一次，确认单自然出现。
   *
   * <p>幂等：重复调用只刷新快照，<b>不碰</b>已推进的确认状态与时刻。
   */
  public PickupSignoffSyncResult sync(PickupSignoffSyncInput input) {
    String carrier = text(input.carrierCode());
    String batchNo = text(input.batchNo());
    int p = Math.max(input.page() == null ? 0 : input.page(), 0);
    int s = input.size() == null ? 50 : Math.min(Math.max(input.size(), 1), 200);

    List<ManifestView> items = scanManifest(carrier, batchNo, p, s);
    long total = countManifest(carrier, batchNo);

    int created = 0;
    int updated = 0;
    int skipped = 0;
    List<String> notes = new ArrayList<>();
    for (ManifestView item : items) {
      String outcome = upsertCase(item, notes);
      if ("CREATED".equals(outcome)) {
        created++;
      } else if ("UPDATED".equals(outcome)) {
        updated++;
      } else {
        skipped++;
      }
    }
    return new PickupSignoffSyncResult(items.size(), created, updated, skipped, total, notes);
  }

  /** 扫交接清单（**只取已登记交接的批次**）。 */
  private List<ManifestView> scanManifest(String carrier, String batchNo, int page, int size) {
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    // ⚠️ `(? = '' or b.carrier_code = ?)` 这类写法里，同一个筛选值出现**两次**，
    //    绑定也要给两次 —— 少给一个就是「未设定参数值 6 的内容」。
    binds.add(carrier);
    binds.add(carrier);
    binds.add(batchNo);
    binds.add(batchNo);
    binds.add(size);
    binds.add(page * size);
    var rows =
        sql.fetch(
            "select m.id, m.batch_id, b.batch_no, b.status, b.carrier_code, m.parcel_no,"
                + " m.parcel_ref, m.waybill_no, m.handover_at, b.dispatched_at, m.gross_weight_kg"
                + " from handover_manifest_item m"
                + " join handover_batch b on b.tenant_id = m.tenant_id and b.id = m.batch_id"
                + " where m.tenant_id = ?"
                + "   and b.status in ('DISPATCH_PENDING','HANDED_OVER')"
                + "   and (? = '' or b.carrier_code = ?)"
                + "   and (? = '' or b.batch_no = ?)"
                + " order by m.handover_at desc, m.id"
                + " limit ? offset ?",
            binds.toArray());
    List<ManifestView> out = new ArrayList<>();
    for (Record r : rows) {
      out.add(
          new ManifestView(
              r.get("id", UUID.class),
              r.get("batch_id", UUID.class),
              r.get("batch_no", String.class),
              r.get("status", String.class),
              r.get("carrier_code", String.class),
              r.get("parcel_no", Integer.class) == null ? 0 : r.get("parcel_no", Integer.class),
              r.get("parcel_ref", String.class),
              r.get("waybill_no", String.class),
              r.get("handover_at", OffsetDateTime.class),
              r.get("dispatched_at", OffsetDateTime.class),
              r.get("gross_weight_kg", BigDecimal.class)));
    }
    return out;
  }

  private long countManifest(String carrier, String batchNo) {
    return sql.fetchOne(
            "select count(*)"
                + " from handover_manifest_item m"
                + " join handover_batch b on b.tenant_id = m.tenant_id and b.id = m.batch_id"
                + " where m.tenant_id = ?"
                + "   and b.status in ('DISPATCH_PENDING','HANDED_OVER')"
                + "   and (? = '' or b.carrier_code = ?)"
                + "   and (? = '' or b.batch_no = ?)",
            currentTenant(), carrier, carrier, batchNo, batchNo)
        .get(0, Long.class);
  }

  /**
   * 幂等写入一个确认单。
   *
   * <p>ON CONFLICT DO UPDATE **只刷新身份与快照**，
   * 刻意<b>不碰</b>揽收/签收的确认状态与时刻 —— 同步一次就把确认历史抹了，
   * 等于把「这票已经被揽收了」这件事擦掉。
   */
  private String upsertCase(ManifestView item, List<String> notes) {
    UUID tenant = currentTenant();

    // 1) 轨迹主体匹配（运单号对齐）
    SubjectView subject = resolveSubject(item.waybillNo());

    // 2) SLA 规则命中（渠道级优先于承运商级）
    SlaView sla = resolveSla(item.carrierCode(), subject.channelId());

    // 3) 已有确认单（决定时限快照与缺口优先级）
    Record existing =
        sql.fetchOne(
            "select id, case_no, pickup_confirmed_at, pickup_deadline_at, gap_code"
                + " from logistics_pickup_signoff_case"
                + " where tenant_id = ? and source_manifest_item_id = ?",
            tenant, item.manifestItemId());

    // 4) 时限快照：**只补空缺**，已定的截止时刻不被后来的规则改写
    BigDecimal slaHours = sla == null ? null : sla.pickupSlaHours();
    BigDecimal leadHours = sla == null ? null : sla.reminderLeadHours();
    OffsetDateTime deadline = null;
    if (slaHours != null) {
      deadline =
          existing != null && existing.get("pickup_deadline_at", OffsetDateTime.class) != null
              ? existing.get("pickup_deadline_at", OffsetDateTime.class)
              : item.handoverAt().plus(hoursToDuration(slaHours));
    }

    // 5) 缺口码：取最根本的那一个；已确认揽收的件不再报缺口
    boolean confirmed = existing != null && existing.get("pickup_confirmed_at", OffsetDateTime.class) != null;
    String gapCode = confirmed ? "" : computeGap(item, subject, sla, deadline);
    String gapReason = confirmed ? "" : gapReason(gapCode);

    boolean isNew = existing == null;
    // 🔴 case_no 是 NOT NULL 列：ON CONFLICT 的**更新路径**同样要过 NOT NULL 检查
    //    （PostgreSQL 在冲突仲裁**之前**就做约束检查），所以更新时必须带上原单号，
    //    绑 null 会直接 23502 而不是走 do update。
    String caseNo =
        isNew
            ? nextCaseNo(item.handoverAt())
            : text(existing.get("case_no", String.class));

    List<Object> binds = new ArrayList<>();
    binds.add(Ids.next());
    binds.add(tenant);
    binds.add(caseNo);
    binds.add(item.manifestItemId());
    binds.add(item.batchId());
    binds.add(item.batchNo());
    binds.add(item.batchStatus());
    binds.add(subject.id());
    binds.add(subject.matchCount());
    binds.add(text(item.waybillNo()));
    binds.add(item.parcelNo());
    binds.add(text(item.parcelRef()));
    binds.add(item.carrierCode());
    binds.add(subject.channelId());
    binds.add(subject.salesOrderNo());
    binds.add(subject.destinationCountry());
    binds.add(item.grossWeightKg() == null ? BigDecimal.ZERO : scale(item.grossWeightKg()));
    binds.add(item.handoverAt());
    binds.add(item.dispatchedAt());
    binds.add(sla == null ? null : sla.id());
    binds.add(scale(slaHours));
    binds.add(scale(leadHours));
    binds.add(deadline);
    binds.add(gapCode);
    binds.add(gapReason);

    var inserted =
        sql.fetchOne(
            "insert into logistics_pickup_signoff_case ("
                + " id,tenant_id,case_no,source_manifest_item_id,source_batch_id,source_batch_no,"
                + " source_batch_status,source_subject_id,subject_match_count,master_waybill_no,"
                + " parcel_no,parcel_ref,carrier_code,channel_id,sales_order_no,destination_country,"
                + " gross_weight_kg,handover_at,dispatched_at,sla_rule_id,pickup_sla_hours,"
                + " reminder_lead_hours,pickup_deadline_at,pickup_state,delivery_state,status,"
                + " gap_code,gap_reason,created_at,updated_at)"
                + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?,"
                + " ?::timestamptz,'PENDING','PENDING','PENDING_PICKUP',?,?,now(),now())"
                + " on conflict (tenant_id, source_manifest_item_id) do update set"
                + " source_batch_id=excluded.source_batch_id,"
                + " source_batch_no=excluded.source_batch_no,"
                + " source_batch_status=excluded.source_batch_status,"
                + " source_subject_id=excluded.source_subject_id,"
                + " subject_match_count=excluded.subject_match_count,"
                + " master_waybill_no=excluded.master_waybill_no,"
                + " parcel_no=excluded.parcel_no,"
                + " parcel_ref=excluded.parcel_ref,"
                + " carrier_code=excluded.carrier_code,"
                + " channel_id=excluded.channel_id,"
                + " sales_order_no=excluded.sales_order_no,"
                + " destination_country=excluded.destination_country,"
                + " gross_weight_kg=excluded.gross_weight_kg,"
                + " handover_at=excluded.handover_at,"
                + " dispatched_at=excluded.dispatched_at,"
                // 🔴 时限是**快照**：已存在的截止时刻不被后来的规则改写
                + " sla_rule_id=coalesce(logistics_pickup_signoff_case.sla_rule_id, excluded.sla_rule_id),"
                + " pickup_sla_hours=coalesce(logistics_pickup_signoff_case.pickup_sla_hours,"
                + "   excluded.pickup_sla_hours),"
                + " reminder_lead_hours=coalesce(logistics_pickup_signoff_case.reminder_lead_hours,"
                + "   excluded.reminder_lead_hours),"
                + " pickup_deadline_at=coalesce(logistics_pickup_signoff_case.pickup_deadline_at,"
                + "   excluded.pickup_deadline_at),"
                + " gap_code=excluded.gap_code,"
                + " gap_reason=excluded.gap_reason,"
                + " version=logistics_pickup_signoff_case.version+1,"
                + " updated_at=now()"
                + " returning id",
            binds.toArray());

    UUID caseId = inserted == null ? null : inserted.get("id", UUID.class);
    if (caseId == null) {
      notes.add("确认单写入失败：" + text(item.waybillNo()));
      return "SKIPPED";
    }
    if (isNew) {
      appendEvent(
          caseId,
          "CASE_CREATED",
          "",
          "PENDING_PICKUP",
          "已登记交接，待承运商揽收：批次 "
              + item.batchNo()
              + " 第 "
              + item.parcelNo()
              + " 件，交运时刻 "
              + item.handoverAt()
              + (deadline == null ? "（未配时限规则）" : "，揽收时限 " + slaHours + " 小时"),
          null,
          "");
      return "CREATED";
    }
    return "UPDATED";
  }

  /** 确认单号：PSO-yyyyMMdd-000001。 */
  private String nextCaseNo(OffsetDateTime handoverAt) {
    Long n = sql.fetchOne("select nextval('logistics_pickup_signoff_case_no_seq')").get(0, Long.class);
    return String.format(
        Locale.ROOT,
        "PSO-%s%06d",
        handoverAt.atZoneSameInstant(ZoneOffset.UTC).toLocalDate().toString().replace("-", ""), n);
  }

  /**
   * 轨迹主体匹配：按运单号对齐（**同库同服务的逻辑引用**）。
   *
   * <p>🔴 命中多个时如实记 {@code SUBJECT_AMBIGUOUS}：V212 的
   * {@code master_waybill_no} 上没有唯一约束，猜错一件等于把别的货标成已揽收，
   * 而「已揽收」是要回传平台履约指标的。
   */
  private SubjectView resolveSubject(String waybillNo) {
    String waybill = text(waybillNo);
    if (waybill.isEmpty()) {
      return new SubjectView(null, 0, null, "", "");
    }
    int count =
        sql.fetchOne(
                "select count(*) from logistics_tracking_subject"
                    + " where tenant_id = ? and master_waybill_no = ?",
                currentTenant(), waybill)
            .get(0, Integer.class);
    if (count == 0) {
      return new SubjectView(null, 0, null, "", "");
    }
    if (count > 1) {
      return new SubjectView(null, count, null, "", "");
    }
    Record first =
        sql.fetchOne(
            "select id, channel_id, sales_order_no, destination_country"
                + " from logistics_tracking_subject"
                + " where tenant_id = ? and master_waybill_no = ?"
                + " order by updated_at desc, id limit 1",
            currentTenant(), waybill);
    if (first == null) {
      return new SubjectView(null, 0, null, "", "");
    }
    return new SubjectView(
        first.get("id", UUID.class),
        1,
        first.get("channel_id", UUID.class),
        text(first.get("sales_order_no", String.class)),
        text(first.get("destination_country", String.class)));
  }

  /**
   * SLA 规则命中：<b>渠道级优先于承运商级</b>（取最具体的一条，不叠加）。
   *
   * <p>没有命中时返回 null —— **不用默认值冒充**：没有配规则的件如实报
   * {@code SLA_UNCONFIGURED}，而不是悄悄按 24 小时算一个「看起来有依据」的时限。
   */
  private SlaView resolveSla(String carrierCode, UUID channelId) {
    var rows =
        sql.fetch(
            "select id, pickup_sla_hours, reminder_lead_hours, review_invite_delay_hours,"
                + " after_sales_window_hours"
                + " from logistics_pickup_signoff_sla_rule"
                + " where tenant_id = ? and carrier_code = ? and status = 'ACTIVE'"
                + "   and (channel_id is null or channel_id = ?)"
                + " order by case when channel_id is null then 0 else 1 end desc"
                + " limit 1",
            currentTenant(), carrierCode, channelId);
    if (rows.isEmpty()) {
      return null;
    }
    Record r = rows.get(0);
    return new SlaView(
        r.get("id", UUID.class),
        r.get("pickup_sla_hours", BigDecimal.class),
        r.get("reminder_lead_hours", BigDecimal.class),
        r.get("review_invite_delay_hours", BigDecimal.class),
        r.get("after_sales_window_hours", BigDecimal.class));
  }

  /** 缺口码：取最根本的那一个。 */
  private String computeGap(
      ManifestView item, SubjectView subject, SlaView sla, OffsetDateTime deadline) {
    if (text(item.waybillNo()).isEmpty()) {
      return "NO_WAYBILL";
    }
    if (subject.matchCount() > 1) {
      return "SUBJECT_AMBIGUOUS";
    }
    if (subject.matchCount() == 0) {
      return "TRACKING_SUBJECT_MISSING";
    }
    // 🔴 判据是「有没有时限快照」，**不是**「现在有没有生效的规则」：
    // 规则后来被停用不影响已经建好的单 —— 事后改档期不能让一笔已经延误的货
    // 变成「没配规则所以不算超时」。
    if (deadline == null) {
      return "SLA_UNCONFIGURED";
    }
    return "";
  }

  private static String gapReason(String code) {
    return switch (text(code)) {
      case "NO_WAYBILL" -> "交运件没有运单号（10.2.1.2 运单申请未交付），无法与承运商事件对齐";
      case "TRACKING_SUBJECT_MISSING" -> "上游 10.3.1.1 还没有同步到这一票的轨迹主体";
      case "SUBJECT_AMBIGUOUS" -> "同一运单号命中多个轨迹主体，无法确定是哪一票，不自动确认";
      case "BATCH_VOID" -> "交运批次已作废，揽收责任从未成立";
      case "SLA_UNCONFIGURED" -> "该承运商没有配置揽收时限规则，算不出超时";
      default -> "";
    };
  }

  // ==================================================================
  // 写：巡检（主动监控）
  // ==================================================================

  /**
   * 巡检：补齐确认单 → 推进揽收/签收状态 → 判超时 → 生成提醒与下游待通知。
   *
   * <p>🔴 这是蓝图加粗句「需主动监控而非等轨迹同步」的落点。
   * 它<b>不自带调度器</b>：多副本下自带定时器要做主节点选举与分布式锁，
   * 那是平台能力不是领域能力；外部调度触发一次入口反而天然单次、可重试、可审计。
   */
  public PickupSignoffReconcileResult reconcile(PickupSignoffReconcileInput input) {
    String carrier = text(input.carrierCode());
    String batchNo = text(input.batchNo());
    int p = Math.max(input.page() == null ? 0 : input.page(), 0);
    int s = input.size() == null ? 50 : Math.min(Math.max(input.size(), 1), 200);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    List<String> notes = new ArrayList<>();
    int created = 0;
    // 先补齐确认单（没单的件巡检不到 —— 「没人建单就没人盯」正是要避免的）
    List<ManifestView> items = scanManifest(carrier, batchNo, p, s);
    for (ManifestView item : items) {
      String outcome = upsertCase(item, notes);
      if ("CREATED".equals(outcome)) {
        created++;
      }
    }

    int pickupConfirmed = 0;
    int deliveryConfirmed = 0;
    int overdueDetected = 0;
    int dueSoonDetected = 0;
    int remindersCreated = 0;
    int notificationsCreated = 0;
    int exceptionsMarked = 0;
    int voided = 0;

    for (UUID caseId : scanCaseIds(carrier, batchNo, p, s)) {
      CaseAdvance advance = advanceCase(caseId, now, notes);
      pickupConfirmed += advance.pickupConfirmed();
      deliveryConfirmed += advance.deliveryConfirmed();
      overdueDetected += advance.overdue();
      dueSoonDetected += advance.dueSoon();
      remindersCreated += advance.reminders();
      notificationsCreated += advance.notifications();
      exceptionsMarked += advance.exceptions();
      voided += advance.voided();
    }

    // 待通知到时机：PENDING → READY（**下游没接线时就停在这里，不写「已回传」**）
    int ready =
        sql.execute(
            "update logistics_pickup_signoff_notification"
                + " set downstream_status='READY', updated_at=now()"
                + " where tenant_id = ? and downstream_status = 'PENDING'"
                + "   and due_at is not null and due_at <= ?::timestamptz",
            currentTenant(), now);

    return new PickupSignoffReconcileResult(
        items.size(),
        created,
        pickupConfirmed,
        deliveryConfirmed,
        overdueDetected,
        dueSoonDetected,
        remindersCreated,
        notificationsCreated + ready,
        exceptionsMarked,
        voided,
        notes);
  }

  /** 一轮巡检里逐单的推进结果。 */
  private record CaseAdvance(
      int pickupConfirmed,
      int deliveryConfirmed,
      int overdue,
      int dueSoon,
      int reminders,
      int notifications,
      int exceptions,
      int voided) {}

  private List<UUID> scanCaseIds(String carrier, String batchNo, int page, int size) {
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    binds.add(carrier);
    binds.add(carrier);
    binds.add(batchNo);
    binds.add(batchNo);
    binds.add(size);
    binds.add(page * size);
    var rows =
        sql.fetch(
            "select id from logistics_pickup_signoff_case"
                + " where tenant_id = ?"
                + "   and (? = '' or carrier_code = ?)"
                + "   and (? = '' or source_batch_no = ?)"
                + " order by handover_at desc, id limit ? offset ?",
            binds.toArray());
    List<UUID> out = new ArrayList<>();
    for (Record r : rows) {
      out.add(r.get("id", UUID.class));
    }
    return out;
  }

  /**
   * 推进一个确认单：确认揽收 / 确认签收 / 判超时 / 标异常 / 生成提醒与待通知。
   */
  private CaseAdvance advanceCase(UUID caseId, OffsetDateTime now, List<String> notes) {
    Record c =
        sql.fetchOne(
            "select id, case_no, source_subject_id, subject_match_count, master_waybill_no,"
                + " carrier_code, parcel_no, source_batch_no, source_batch_status, handover_at,"
                + " channel_id, pickup_state, pickup_confirmed_at, pickup_deadline_at,"
                + " pickup_sla_hours, reminder_lead_hours, delivery_state, delivery_confirmed_at,"
                + " status, exception_flag, sla_rule_id"
                + " from logistics_pickup_signoff_case where tenant_id = ? and id = ?",
            currentTenant(), caseId);
    if (c == null) {
      return new CaseAdvance(0, 0, 0, 0, 0, 0, 0, 0);
    }

    int pickupConfirmed = 0;
    int deliveryConfirmed = 0;
    int overdue = 0;
    int dueSoon = 0;
    int reminders = 0;
    int notifications = 0;
    int exceptions = 0;
    int voided = 0;

    String status = c.get("status", String.class);
    String pickupState = c.get("pickup_state", String.class);
    String deliveryState = c.get("delivery_state", String.class);
    OffsetDateTime pickupConfirmedAt = c.get("pickup_confirmed_at", OffsetDateTime.class);
    OffsetDateTime deliveryConfirmedAt = c.get("delivery_confirmed_at", OffsetDateTime.class);
    OffsetDateTime deadline = c.get("pickup_deadline_at", OffsetDateTime.class);
    UUID subjectId = c.get("source_subject_id", UUID.class);
    int matchCount = c.get("subject_match_count", Integer.class) == null
        ? 0
        : c.get("subject_match_count", Integer.class);

    // ---- 1) 批次作废 ⇒ 责任从未成立（但已确认揽收/签收的事实不撤销）----
    if ("CANCELLED".equals(c.get("source_batch_status", String.class))
        && !"VOID".equals(status)
        && !"PICKED_UP".equals(status)
        && !"DELIVERED".equals(status)) {
      sql.execute(
          "update logistics_pickup_signoff_case set status='VOID', gap_code='BATCH_VOID',"
              + " gap_reason=?, version=version+1, updated_at=now() where tenant_id=? and id=?",
          gapReason("BATCH_VOID"), currentTenant(), caseId);
      appendEvent(
          caseId,
          "VOIDED",
          status,
          "VOID",
          "交运批次已作废，揽收责任从未成立",
          null,
          "");
      voided++;
      return new CaseAdvance(0, 0, 0, 0, 0, 0, 0, voided);
    }
    if ("VOID".equals(status)) {
      return new CaseAdvance(0, 0, 0, 0, 0, 0, 0, 0);
    }

    // ---- 2) 重新对齐轨迹主体（上游后来同步到了，上一轮的缺口就该消掉）----
    if (subjectId == null && matchCount == 0) {
      SubjectView subject = resolveSubject(c.get("master_waybill_no", String.class));
      if (subject.matchCount() == 1) {
        sql.execute(
            "update logistics_pickup_signoff_case set source_subject_id=?, subject_match_count=1,"
                + " channel_id=?, sales_order_no=?, destination_country=?,"
                + " version=version+1, updated_at=now() where tenant_id=? and id=?",
            subject.id(),
            subject.channelId(),
            subject.salesOrderNo(),
            subject.destinationCountry(),
            currentTenant(),
            caseId);
        subjectId = subject.id();
        matchCount = 1;
      } else if (subject.matchCount() > 1) {
        sql.execute(
            "update logistics_pickup_signoff_case set subject_match_count=?,"
                + " gap_code='SUBJECT_AMBIGUOUS', gap_reason=?, version=version+1, updated_at=now()"
                + " where tenant_id=? and id=?",
            subject.matchCount(), gapReason("SUBJECT_AMBIGUOUS"), currentTenant(), caseId);
        matchCount = subject.matchCount();
      }
    }

    // ---- 3) 补时限快照（规则后来才配上的情况）----
    if (deadline == null && !"CONFIRMED".equals(pickupState)) {
      SlaView sla = resolveSla(c.get("carrier_code", String.class), c.get("channel_id", UUID.class));
      if (sla != null) {
        OffsetDateTime newDeadline =
            c.get("handover_at", OffsetDateTime.class).plus(hoursToDuration(sla.pickupSlaHours()));
        sql.execute(
            "update logistics_pickup_signoff_case set sla_rule_id=?, pickup_sla_hours=?,"
                + " reminder_lead_hours=?, pickup_deadline_at=?::timestamptz,"
                + " gap_code='', gap_reason='', version=version+1, updated_at=now()"
                + " where tenant_id=? and id=?",
            sla.id(),
            scale(sla.pickupSlaHours()),
            scale(sla.reminderLeadHours()),
            newDeadline,
            currentTenant(),
            caseId);
        deadline = newDeadline;
        appendEvent(
            caseId,
            "SLA_APPLIED",
            status,
            status,
            "补上揽收时限快照："
                + sla.pickupSlaHours()
                + " 小时，截止 "
                + newDeadline,
            null,
            "");
      }
    }

    // ---- 4) 承运商事件：揽收 / 签收 / 异常 ----
    Record pickupEvent = null;
    Record deliveryEvent = null;
    Record exceptionEvent = null;
    if (subjectId != null && matchCount == 1) {
      pickupEvent = firstEvent(subjectId, "PICKED_UP");
      deliveryEvent = firstEvent(subjectId, "DELIVERED");
      exceptionEvent = lastEvent(subjectId, "EXCEPTION");
    }

    if (pickupEvent != null && !"CONFIRMED".equals(pickupState)) {
      OffsetDateTime at = pickupEvent.get("occurred_at", OffsetDateTime.class);
      String ref = pickupEvent.get("carrier_event_id", String.class);
      sql.execute(
          "update logistics_pickup_signoff_case set pickup_state='CONFIRMED', pickup_confirmed_at=?::timestamptz,"
              + " pickup_event_id=?, pickup_evidence_source='CARRIER_EVENT', pickup_evidence_ref=?,"
              + " status=case when delivery_state='CONFIRMED' then 'DELIVERED' else 'PICKED_UP' end,"
              + " overdue_hours=NULL, gap_code='', gap_reason='',"
              + " version=version+1, updated_at=now() where tenant_id=? and id=?",
          at, pickupEvent.get("id", UUID.class), ref, currentTenant(), caseId);
      appendEvent(
          caseId,
          "PICKUP_CONFIRMED",
          status,
          deliveryEvent != null ? "DELIVERED" : "PICKED_UP",
          "承运商已揽收（事件 " + ref + "，发生时刻 " + at + "）",
          null,
          ref);
      pickupConfirmed = 1;
      pickupState = "CONFIRMED";
      pickupConfirmedAt = at;
      status = deliveryEvent != null ? "DELIVERED" : "PICKED_UP";
      notes.add("已确认揽收：" + c.get("master_waybill_no", String.class));
      // 已揽收 ⇒ 「还没揽收」的提醒已过时，作废掉（留痕，不物理删）
      cancelPendingReminders(caseId, "PICKUP_DUE_SOON", "PICKUP_OVERDUE");
    }

    if (deliveryEvent != null && !"CONFIRMED".equals(deliveryState)) {
      OffsetDateTime at = deliveryEvent.get("occurred_at", OffsetDateTime.class);
      String ref = deliveryEvent.get("carrier_event_id", String.class);
      sql.execute(
          "update logistics_pickup_signoff_case set delivery_state='CONFIRMED',"
              + " delivery_confirmed_at=?::timestamptz, delivery_event_id=?,"
              + " delivery_evidence_source='CARRIER_EVENT', delivery_evidence_ref=?,"
              + " status='DELIVERED', overdue_hours=NULL,"
              + " version=version+1, updated_at=now() where tenant_id=? and id=?",
          at, deliveryEvent.get("id", UUID.class), ref, currentTenant(), caseId);
      appendEvent(
          caseId,
          "DELIVERY_CONFIRMED",
          status,
          "DELIVERED",
          "承运商已签收（事件 " + ref + "，发生时刻 " + at + "）",
          null,
          ref);
      deliveryConfirmed = 1;
      deliveryState = "CONFIRMED";
      deliveryConfirmedAt = at;
      status = "DELIVERED";
      notes.add("已确认签收：" + c.get("master_waybill_no", String.class));
    }

    // ---- 5) 异常标记（只标记，处置归 10.3.1.2）----
    if (exceptionEvent != null && !Boolean.TRUE.equals(c.get("exception_flag", Boolean.class))) {
      sql.execute(
          "update logistics_pickup_signoff_case set exception_flag=true, exception_code=?,"
              + " exception_note=?, exception_at=?::timestamptz, version=version+1, updated_at=now()"
              + " where tenant_id=? and id=?",
          exceptionEvent.get("exception_code", String.class),
          exceptionEvent.get("exception_note", String.class),
          exceptionEvent.get("occurred_at", OffsetDateTime.class),
          currentTenant(),
          caseId);
      appendEvent(
          caseId,
          "EXCEPTION_MARKED",
          status,
          status,
          "承运商报了异常节点："
              + exceptionEvent.get("exception_code", String.class)
              + "（处置归 10.3.1.2，本模块只标记）",
          null,
          "");
      exceptions = 1;
    }

    // ---- 6) 超时判定（**读时**，取决于现在几点）----
    // 🔴 只在**状态跃迁**时留事件：每轮巡检都 append 一条 PICKUP_OVERDUE
    //    会把时间线刷成一片，而「已超时」这个事实只发生过一次。
    //    overdue_hours 仍然每轮刷新 —— 它是「到巡检为止晚了多久」的读数。
    if (!"CONFIRMED".equals(pickupState)
        && !"CONFIRMED".equals(deliveryState)
        && deadline != null
        && now.isAfter(deadline)) {
      BigDecimal hours = durationHours(Duration.between(deadline, now));
      boolean transition = !"OVERDUE".equals(pickupState);
      sql.execute(
          "update logistics_pickup_signoff_case set pickup_state='OVERDUE', status='PICKUP_OVERDUE',"
              + " overdue_hours=?, version=version+1, updated_at=now() where tenant_id=? and id=?",
          hours, currentTenant(), caseId);
      if (transition) {
        appendEvent(
            caseId,
            "PICKUP_OVERDUE",
            status,
            "PICKUP_OVERDUE",
            "已过揽收时限（截止 " + deadline + "，超时 " + hours + " 小时）",
            null,
            "");
        overdue = 1;
      }
      pickupState = "OVERDUE";
      status = "PICKUP_OVERDUE";
    }

    // ---- 7) 提醒任务 ----
    if (!"CONFIRMED".equals(pickupState)
        && !"CONFIRMED".equals(deliveryState)
        && deadline != null
        && !now.isAfter(deadline)) {
      BigDecimal lead = c.get("reminder_lead_hours", BigDecimal.class);
      BigDecimal effectiveLead = lead == null ? BigDecimal.ZERO : lead;
      OffsetDateTime dueSoonAt = deadline.minus(hoursToDuration(effectiveLead));
      if (!now.isBefore(dueSoonAt)) {
        int created =
            insertReminder(
                caseId,
                "PICKUP_DUE_SOON",
                caseId + ":PICKUP_DUE_SOON:" + deadline,
                "即将超时未揽收：" + c.get("master_waybill_no", String.class),
                "交运时刻 "
                    + c.get("handover_at", OffsetDateTime.class)
                    + "，揽收时限截止 "
                    + deadline
                    + "（提前 "
                    + effectiveLead
                    + " 小时提醒）",
                deadline);
        reminders += created;
        if (created > 0) {
          appendEvent(
              caseId,
              "PICKUP_DUE_SOON",
              status,
              status,
              "即将超时：揽收时限截止 " + deadline,
              null,
              "");
          dueSoon = 1;
        }
      }
    }
    if ("OVERDUE".equals(pickupState)) {
      reminders +=
          insertReminder(
              caseId,
              "PICKUP_OVERDUE",
              caseId + ":PICKUP_OVERDUE:" + deadline,
              "超时未揽收：" + c.get("master_waybill_no", String.class),
              "已过揽收时限（截止 " + deadline + "），平台可能判我们未发货",
              now);
    }
    if (deliveryConfirmed == 1) {
      reminders +=
          insertReminder(
              caseId,
              "DELIVERY_CONFIRMED",
              caseId + ":DELIVERY_CONFIRMED:" + deliveryConfirmedAt,
              "已签收，待跟进评价邀请与售后窗口：" + c.get("master_waybill_no", String.class),
              "签收时刻 " + deliveryConfirmedAt,
              deliveryConfirmedAt);
      // 已确认 ⇒ 那些「还没揽收」的提醒已经过时，作废掉（留痕，不物理删）
      cancelPendingReminders(caseId, "PICKUP_DUE_SOON", "PICKUP_OVERDUE");
    }

    // ---- 8) 下游待通知 ----
    if (pickupConfirmed == 1) {
      notifications +=
          insertNotification(
              caseId,
              "PLATFORM_FULFILLMENT_WRITEBACK",
              "9.4.2.1",
              caseId + ":WRITEBACK:" + pickupConfirmedAt,
              "揽收已确认，待回传平台履约指标",
              pickupConfirmedAt,
              c);
    }
    if (deliveryConfirmed == 1) {
      SlaView sla = resolveSla(c.get("carrier_code", String.class), c.get("channel_id", UUID.class));
      BigDecimal reviewDelay =
          sla == null || sla.reviewInviteDelayHours() == null
              ? BigDecimal.ZERO
              : sla.reviewInviteDelayHours();
      BigDecimal windowHours =
          sla == null || sla.afterSalesWindowHours() == null
              ? BigDecimal.valueOf(168)
              : sla.afterSalesWindowHours();
      notifications +=
          insertNotification(
              caseId,
              "REVIEW_INVITATION",
              "15.2.2.1",
              caseId + ":REVIEW:" + deliveryConfirmedAt,
              "签收已确认，到时机后邀请评价",
              deliveryConfirmedAt.plus(hoursToDuration(reviewDelay)),
              c);
      notifications +=
          insertNotification(
              caseId,
              "AFTER_SALES_WINDOW_CLOSE",
              "15",
              caseId + ":WINDOW:" + deliveryConfirmedAt,
              "签收已确认，售后窗口到期后关闭",
              deliveryConfirmedAt.plus(hoursToDuration(windowHours)),
              c);
    }

    sql.execute(
        "update logistics_pickup_signoff_case set last_reconciled_at=?::timestamptz,"
            + " updated_at=now() where tenant_id=? and id=?",
        now, currentTenant(), caseId);
    return new CaseAdvance(
        pickupConfirmed, deliveryConfirmed, overdue, dueSoon, reminders, notifications, exceptions, 0);
  }

  /** 最早的一条某类节点事件。 */
  private Record firstEvent(UUID subjectId, String nodeType) {
    return sql.fetchOne(
        "select id, carrier_event_id, occurred_at from logistics_tracking_event"
            + " where tenant_id = ? and subject_id = ? and node_type = ?"
            + " order by occurred_at asc, received_at asc, id limit 1",
        currentTenant(), subjectId, nodeType);
  }

  /** 最新的一条某类节点事件。 */
  private Record lastEvent(UUID subjectId, String nodeType) {
    return sql.fetchOne(
        "select id, carrier_event_id, occurred_at, exception_code, exception_note"
            + " from logistics_tracking_event"
            + " where tenant_id = ? and subject_id = ? and node_type = ?"
            + " order by occurred_at desc, received_at desc, id limit 1",
        currentTenant(), subjectId, nodeType);
  }

  // ==================================================================
  // 写：人工确认
  // ==================================================================

  /**
   * 人工确认揽收 / 签收。
   *
   * <p>承运商出网通道未交付（10.1.1.1）时，揽收/签收只能这样进来：
   * <b>有凭据号、有时刻、有主体</b>。它**不覆盖**承运商事件的证据来源标记 ——
   * 人工确认与承运商推送在库上是两个不同的来源，混成一个就分不清「谁说的」。
   */
  public PickupSignoffCaseDetail attest(UUID caseId, PickupSignoffAttestInput input, UUID membershipId) {
    Record c =
        sql.fetchOne(
            "select id, case_no, status, pickup_state, delivery_state, pickup_confirmed_at,"
                + " delivery_confirmed_at, master_waybill_no, carrier_code, parcel_no,"
                + " source_batch_no, handover_at, channel_id"
                + " from logistics_pickup_signoff_case where tenant_id = ? and id = ?",
            currentTenant(), caseId);
    if (c == null) {
      throw notFound();
    }
    String status = c.get("status", String.class);
    if ("VOID".equals(status)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "已作废的确认单不能人工确认");
    }
    String kind = text(input.kind()).toUpperCase(Locale.ROOT);
    String ref = text(input.evidenceRef());
    OffsetDateTime at = input.occurredAt();
    String detail = text(input.note());

    if ("PICKUP".equals(kind)) {
      if (c.get("pickup_confirmed_at", OffsetDateTime.class) != null) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "这一件已确认揽收，不能重复确认");
      }
      sql.execute(
          "update logistics_pickup_signoff_case set pickup_state='CONFIRMED',"
              + " pickup_confirmed_at=?::timestamptz, pickup_evidence_source='MANUAL_ATTESTATION',"
              + " pickup_evidence_ref=?, status=case when delivery_state='CONFIRMED' then 'DELIVERED'"
              + " else 'PICKED_UP' end, overdue_hours=NULL, gap_code='', gap_reason='',"
              + " version=version+1, updated_at=now() where tenant_id=? and id=?",
          at, ref, currentTenant(), caseId);
      appendEvent(
          caseId,
          "ATTESTED",
          status,
          "PICKED_UP",
          "人工确认揽收（凭据 " + ref + "，时刻 " + at + (detail.isEmpty() ? "" : "：" + detail) + "）",
          membershipId,
          "");
    } else if ("DELIVERY".equals(kind)) {
      if (c.get("delivery_confirmed_at", OffsetDateTime.class) != null) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "这一件已确认签收，不能重复确认");
      }
      sql.execute(
          "update logistics_pickup_signoff_case set delivery_state='CONFIRMED',"
              + " delivery_confirmed_at=?::timestamptz, delivery_evidence_source='MANUAL_ATTESTATION',"
              + " delivery_evidence_ref=?, status='DELIVERED', overdue_hours=NULL,"
              + " version=version+1, updated_at=now() where tenant_id=? and id=?",
          at, ref, currentTenant(), caseId);
      appendEvent(
          caseId,
          "ATTESTED",
          status,
          "DELIVERED",
          "人工确认签收（凭据 " + ref + "，时刻 " + at + (detail.isEmpty() ? "" : "：" + detail) + "）",
          membershipId,
          "");
    } else {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "确认类型只能是 PICKUP 或 DELIVERY");
    }
    // 人工确认同样要产出下游待通知 —— 否则「人工确认的签收」永远等不到评价邀请。
    if ("PICKUP".equals(kind)) {
      insertNotification(
          caseId,
          "PLATFORM_FULFILLMENT_WRITEBACK",
          "9.4.2.1",
          caseId + ":WRITEBACK:" + at,
          "揽收已确认（人工），待回传平台履约指标",
          at,
          c);
    } else {
      SlaView sla = resolveSla(c.get("carrier_code", String.class), c.get("channel_id", UUID.class));
      BigDecimal reviewDelay =
          sla == null || sla.reviewInviteDelayHours() == null
              ? BigDecimal.ZERO
              : sla.reviewInviteDelayHours();
      BigDecimal windowHours =
          sla == null || sla.afterSalesWindowHours() == null
              ? BigDecimal.valueOf(168)
              : sla.afterSalesWindowHours();
      insertNotification(
          caseId,
          "REVIEW_INVITATION",
          "15.2.2.1",
          caseId + ":REVIEW:" + at,
          "签收已确认（人工），到时机后邀请评价",
          at.plus(hoursToDuration(reviewDelay)),
          c);
      insertNotification(
          caseId,
          "AFTER_SALES_WINDOW_CLOSE",
          "15",
          caseId + ":WINDOW:" + at,
          "签收已确认（人工），售后窗口到期后关闭",
          at.plus(hoursToDuration(windowHours)),
          c);
    }
    return caseDetail(caseId);
  }

  // ==================================================================
  // 写：提醒处置
  // ==================================================================

  /** 认领提醒（PENDING → ACKNOWLEDGED）。 */
  public PickupSignoffReminderRow acknowledgeReminder(
      UUID reminderId, PickupSignoffReminderActionInput input, UUID membershipId) {
    Record r =
        sql.fetchOne(
            "select id, status from logistics_pickup_signoff_reminder where tenant_id = ? and id = ?",
            currentTenant(), reminderId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "提醒任务不存在");
    }
    if (!"PENDING".equals(r.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只有待处理的提醒可以认领，当前状态：" + r.get("status", String.class));
    }
    sql.execute(
        "update logistics_pickup_signoff_reminder set status='ACKNOWLEDGED',"
            + " acknowledged_at=?::timestamptz, acknowledged_by_membership_id=?,"
            + " assigned_to_membership_id=?, updated_at=now() where tenant_id=? and id=?",
        OffsetDateTime.now(ZoneOffset.UTC), membershipId, membershipId, currentTenant(), reminderId);
    return reminderRow(reminderId);
  }

  /** 处置完成（ACKNOWLEDGED / PENDING → RESOLVED）。 */
  public PickupSignoffReminderRow resolveReminder(
      UUID reminderId, PickupSignoffReminderActionInput input, UUID membershipId) {
    Record r =
        sql.fetchOne(
            "select id, status from logistics_pickup_signoff_reminder where tenant_id = ? and id = ?",
            currentTenant(), reminderId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "提醒任务不存在");
    }
    String status = r.get("status", String.class);
    if ("RESOLVED".equals(status) || "CANCELLED".equals(status)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "已结束的提醒不能重复处置");
    }
    sql.execute(
        "update logistics_pickup_signoff_reminder set status='RESOLVED', resolved_at=?::timestamptz,"
            + " resolution_note=?, updated_at=now() where tenant_id=? and id=?",
        OffsetDateTime.now(ZoneOffset.UTC), text(input.note()), currentTenant(), reminderId);
    return reminderRow(reminderId);
  }

  // ==================================================================
  // 写：下游待通知消费登记
  // ==================================================================

  /**
   * 登记下游消费结果。
   *
   * <p>🔴 本模块**不跨服务调用**下游（9.4.2.1 已在 trade 交付但 channel 未接线；
   * 15.2.2.1 索评未开发）。所以这个端点登记的是「**下游取走了没有**」，
   * 不是「本模块回传出去了」—— 记录谁消费、什么时候消费、失败原因是什么。
   */
  public PickupSignoffNotificationRow consumeNotification(
      UUID notificationId, PickupSignoffNotificationConsumeInput input, UUID membershipId) {
    Record n =
        sql.fetchOne(
            "select id, downstream_status, downstream_module from logistics_pickup_signoff_notification"
                + " where tenant_id = ? and id = ?",
            currentTenant(), notificationId);
    if (n == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "下游待通知记录不存在");
    }
    String status = n.get("downstream_status", String.class);
    if ("CONSUMED".equals(status) || "CANCELLED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "已结束的待通知不能重复登记，当前状态：" + status);
    }
    String result = text(input.result()).toUpperCase(Locale.ROOT);
    boolean ok = "CONSUMED".equals(result);
    if (ok) {
      sql.execute(
          "update logistics_pickup_signoff_notification set downstream_status='CONSUMED',"
              + " consumed_at=?::timestamptz, consumed_by_membership_id=?,"
              + " attempt_count=attempt_count+1, last_error='', updated_at=now()"
              + " where tenant_id=? and id=?",
          OffsetDateTime.now(ZoneOffset.UTC), membershipId, currentTenant(), notificationId);
    } else {
      String error = text(input.note());
      if (error.isEmpty()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消费失败必须写明失败原因");
      }
      sql.execute(
          "update logistics_pickup_signoff_notification set downstream_status='FAILED',"
              + " attempt_count=attempt_count+1, last_error=?, updated_at=now()"
              + " where tenant_id=? and id=?",
          error, currentTenant(), notificationId);
    }
    return notificationRow(notificationId);
  }

  // ==================================================================
  // 写：时限规则
  // ==================================================================

  /** 登记时限规则（承运商级；带 channelId 时是渠道级，优先于承运商级）。 */
  public PickupSignoffSlaRuleRow registerSlaRule(PickupSignoffSlaRuleInput input) {
    String carrier = text(input.carrierCode());
    if (carrier.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "承运商代码不能为空");
    }
    if (input.pickupSlaHours() == null || input.pickupSlaHours().signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "揽收时限必须大于 0");
    }
    String status = text(input.status()).isEmpty() ? "ACTIVE" : text(input.status()).toUpperCase(Locale.ROOT);
    if (!List.of("ACTIVE", "DISABLED").contains(status)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "规则状态只能是 ACTIVE 或 DISABLED");
    }
    BigDecimal lead = input.reminderLeadHours() == null ? BigDecimal.valueOf(4) : input.reminderLeadHours();
    BigDecimal review =
        input.reviewInviteDelayHours() == null ? BigDecimal.ZERO : input.reviewInviteDelayHours();
    BigDecimal window =
        input.afterSalesWindowHours() == null ? BigDecimal.valueOf(168) : input.afterSalesWindowHours();

    // 🔴 承运商级与渠道级是**两个**部分唯一索引，命中判定必须分两次查：
    //    一条 SQL 里写 `(? is null) or channel_id = ?` 时，PostgreSQL 无法推断
    //    那个未绑定参数的类型（`could not determine data type of parameter`）。
    Record existing;
    if (input.channelId() == null) {
      existing =
          sql.fetchOne(
              "select id from logistics_pickup_signoff_sla_rule"
                  + " where tenant_id = ? and carrier_code = ? and channel_id is null",
              currentTenant(), carrier);
    } else {
      existing =
          sql.fetchOne(
              "select id from logistics_pickup_signoff_sla_rule"
                  + " where tenant_id = ? and carrier_code = ? and channel_id = ?",
              currentTenant(), carrier, input.channelId());
    }
    UUID id;
    if (existing == null) {
      var row =
          sql.fetchOne(
              "insert into logistics_pickup_signoff_sla_rule ("
                  + " id,tenant_id,carrier_code,channel_id,pickup_sla_hours,reminder_lead_hours,"
                  + " review_invite_delay_hours,after_sales_window_hours,status,note,version,"
                  + " created_at,updated_at)"
                  + " values (?,?,?,?,?,?,?,?,?,?,0,now(),now()) returning id",
              Ids.next(),
              currentTenant(),
              carrier,
              input.channelId(),
              scale(input.pickupSlaHours()),
              scale(lead),
              scale(review),
              scale(window),
              status,
              text(input.note()));
      id = row == null ? null : row.get("id", UUID.class);
      if (id == null) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "该承运商（渠道）已有一条时限规则，请直接改它而不是重复登记");
      }
    } else {
      id = existing.get("id", UUID.class);
      sql.execute(
          "update logistics_pickup_signoff_sla_rule set pickup_sla_hours=?,"
              + " reminder_lead_hours=?, review_invite_delay_hours=?, after_sales_window_hours=?,"
              + " status=?, note=?, version=version+1, updated_at=now()"
              + " where tenant_id = ? and id = ?",
          scale(input.pickupSlaHours()),
          scale(lead),
          scale(review),
          scale(window),
          status,
          text(input.note()),
          currentTenant(),
          id);
    }
    return slaRuleRow(id);
  }

  // ==================================================================
  // 读：确认单清单与汇总
  // ==================================================================

  private static final String CASE_COLUMNS =
      "c.id,c.case_no,c.source_manifest_item_id,c.source_batch_id,c.source_batch_no,"
          + "c.source_batch_status,c.source_subject_id,c.subject_match_count,c.master_waybill_no,"
          + "c.parcel_no,c.parcel_ref,c.carrier_code,c.channel_id,c.sales_order_no,"
          + "c.destination_country,c.gross_weight_kg,c.handover_at,c.dispatched_at,c.sla_rule_id,"
          + "c.pickup_sla_hours,c.reminder_lead_hours,c.pickup_deadline_at,c.pickup_state,"
          + "c.pickup_confirmed_at,c.pickup_evidence_source,c.pickup_evidence_ref,c.delivery_state,"
          + "c.delivery_confirmed_at,c.delivery_evidence_source,c.delivery_evidence_ref,"
          + "c.exception_flag,c.exception_code,c.exception_note,c.exception_at,c.overdue_hours,"
          + "c.status,c.gap_code,c.gap_reason,c.last_reconciled_at,c.created_at,c.updated_at";

  private static final String CASE_FROM = " from logistics_pickup_signoff_case c";

  /** 确认单清单：真分页 + 真筛选，汇总走独立查询不受 limit/offset 影响。 */
  public PickupSignoffCasePage cases(
      String carrierCode,
      String status,
      String pickupState,
      String deliveryState,
      String batchNo,
      String gapCode,
      String keyword,
      boolean overdueOnly,
      boolean exceptionOnly,
      String sort,
      int page,
      int size) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, 200);

    List<Object> filters = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1=1");
    String carrier = text(carrierCode);
    if (!carrier.isEmpty()) {
      where.append(" and c.carrier_code = ?");
      filters.add(carrier);
    }
    String st = text(status);
    if (!st.isEmpty()) {
      where.append(" and c.status = ?");
      filters.add(st);
    }
    String ps = text(pickupState);
    if (!ps.isEmpty()) {
      where.append(" and c.pickup_state = ?");
      filters.add(ps);
    }
    String ds = text(deliveryState);
    if (!ds.isEmpty()) {
      where.append(" and c.delivery_state = ?");
      filters.add(ds);
    }
    String bn = text(batchNo);
    if (!bn.isEmpty()) {
      where.append(" and c.source_batch_no = ?");
      filters.add(bn);
    }
    String gc = text(gapCode);
    if (!gc.isEmpty()) {
      where.append(" and c.gap_code = ?");
      filters.add(gc);
    }
    if (overdueOnly) {
      where.append(" and c.pickup_state = 'OVERDUE'");
    }
    if (exceptionOnly) {
      where.append(" and c.exception_flag");
    }
    String kw = text(keyword);
    if (!kw.isEmpty()) {
      where.append(
          " and (c.master_waybill_no ilike ? or c.sales_order_no ilike ?"
              + " or c.parcel_ref ilike ? or c.case_no ilike ? or c.source_batch_no ilike ?)");
      String like = "%" + escapeLike(kw) + "%";
      filters.add(like);
      filters.add(like);
      filters.add(like);
      filters.add(like);
      filters.add(like);
    }

    long total =
        sql.fetchOne("select count(*)" + CASE_FROM + where, filters.toArray()).get(0, Long.class);

    List<Object> binds = new ArrayList<>(filters);
    binds.add(s);
    binds.add(p * s);
    var rows =
        sql.fetch(
            "select " + CASE_COLUMNS + CASE_FROM + where
                + " order by " + caseOrderBy(sort) + " limit ? offset ?",
            binds.toArray());

    List<PickupSignoffCaseRow> items = new ArrayList<>();
    for (Record r : rows) {
      items.add(toCaseRow(r));
    }
    return new PickupSignoffCasePage(
        items, p, s, total, (int) Math.ceil(total / (double) s), summary(where.toString(), filters));
  }

  /** 概览：<b>全量口径</b>，不受列表筛选与分页影响。 */
  public PickupSignoffSummary overview() {
    return summary("", List.of());
  }

  private PickupSignoffSummary summary(String whereSql, List<Object> binds) {
    var counts =
        sql.fetchOne(
            "select count(*) as total_count,"
                + " count(*) filter (where status = 'PENDING_PICKUP') as pending_pickup_count,"
                + " count(*) filter (where status = 'PICKED_UP') as picked_up_count,"
                + " count(*) filter (where status = 'PICKUP_OVERDUE') as overdue_count,"
                + " count(*) filter (where status = 'DELIVERED') as delivered_count,"
                + " count(*) filter (where status = 'VOID') as void_count,"
                + " count(*) filter (where exception_flag) as exception_count,"
                + " count(*) filter (where gap_code = 'NO_WAYBILL') as no_waybill_count,"
                + " count(*) filter (where gap_code = 'TRACKING_SUBJECT_MISSING') as subject_missing_count,"
                + " count(*) filter (where gap_code = 'SUBJECT_AMBIGUOUS') as subject_ambiguous_count,"
                + " count(*) filter (where gap_code = 'SLA_UNCONFIGURED') as sla_unconfigured_count"
                + " from logistics_pickup_signoff_case c" + whereSql,
            binds.toArray());
    long reminderPending =
        sql.fetchOne(
                "select count(*) from logistics_pickup_signoff_reminder"
                    + " where tenant_id = ? and status = 'PENDING'",
                currentTenant())
            .get(0, Long.class);
    long notificationReady =
        sql.fetchOne(
                "select count(*) from logistics_pickup_signoff_notification"
                    + " where tenant_id = ? and downstream_status = 'READY'",
                currentTenant())
            .get(0, Long.class);
    long notificationPending =
        sql.fetchOne(
                "select count(*) from logistics_pickup_signoff_notification"
                    + " where tenant_id = ? and downstream_status = 'PENDING'",
                currentTenant())
            .get(0, Long.class);

    List<PickupSignoffCarrierBucket> byCarrier = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select carrier_code, count(*) as case_count,"
                + " count(*) filter (where pickup_state = 'OVERDUE') as overdue_count"
                + " from logistics_pickup_signoff_case c" + whereSql
                + " group by carrier_code order by case_count desc, carrier_code",
            binds.toArray())) {
      byCarrier.add(
          new PickupSignoffCarrierBucket(
              r.get("carrier_code", String.class),
              r.get("case_count", Long.class),
              r.get("overdue_count", Long.class)));
    }

    List<PickupSignoffStatusBucket> byStatus = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select status, count(*) as case_count from logistics_pickup_signoff_case c" + whereSql
                + " group by status order by case_count desc, status",
            binds.toArray())) {
      byStatus.add(
          new PickupSignoffStatusBucket(
              r.get("status", String.class), r.get("case_count", Long.class)));
    }

    return new PickupSignoffSummary(
        counts.get("total_count", Long.class),
        counts.get("pending_pickup_count", Long.class),
        counts.get("picked_up_count", Long.class),
        counts.get("overdue_count", Long.class),
        counts.get("delivered_count", Long.class),
        counts.get("void_count", Long.class),
        counts.get("exception_count", Long.class),
        counts.get("no_waybill_count", Long.class),
        counts.get("subject_missing_count", Long.class),
        counts.get("subject_ambiguous_count", Long.class),
        counts.get("sla_unconfigured_count", Long.class),
        reminderPending,
        notificationReady,
        notificationPending,
        byCarrier,
        byStatus);
  }

  private static String caseOrderBy(String sort) {
    return switch (text(sort).toUpperCase(Locale.ROOT)) {
      case "HANDOVER_AT_ASC" -> "c.handover_at asc nulls last, c.id";
      case "DEADLINE_ASC" -> "c.pickup_deadline_at asc nulls last, c.handover_at desc";
      case "CREATED_AT_DESC" -> "c.created_at desc, c.id";
      case "STATUS_ASC" -> "c.status asc, c.handover_at desc";
      default -> "c.handover_at desc nulls last, c.id";
    };
  }

  private PickupSignoffCaseRow toCaseRow(Record r) {
    String pickupState = r.get("pickup_state", String.class);
    String deliveryState = r.get("delivery_state", String.class);
    String status = r.get("status", String.class);
    String gap = r.get("gap_code", String.class);
    return new PickupSignoffCaseRow(
        r.get("id", UUID.class),
        r.get("case_no", String.class),
        r.get("source_manifest_item_id", UUID.class),
        r.get("source_batch_id", UUID.class),
        r.get("source_batch_no", String.class),
        r.get("source_batch_status", String.class),
        r.get("source_subject_id", UUID.class),
        r.get("subject_match_count", Integer.class) == null
            ? 0
            : r.get("subject_match_count", Integer.class),
        r.get("master_waybill_no", String.class),
        r.get("parcel_no", Integer.class) == null ? 0 : r.get("parcel_no", Integer.class),
        r.get("parcel_ref", String.class),
        r.get("carrier_code", String.class),
        r.get("channel_id", UUID.class),
        r.get("sales_order_no", String.class),
        r.get("destination_country", String.class),
        r.get("gross_weight_kg", BigDecimal.class),
        r.get("handover_at", OffsetDateTime.class),
        r.get("dispatched_at", OffsetDateTime.class),
        r.get("sla_rule_id", UUID.class),
        r.get("pickup_sla_hours", BigDecimal.class),
        r.get("reminder_lead_hours", BigDecimal.class),
        r.get("pickup_deadline_at", OffsetDateTime.class),
        pickupState,
        pickupStateLabel(pickupState),
        r.get("pickup_confirmed_at", OffsetDateTime.class),
        r.get("pickup_evidence_source", String.class),
        r.get("pickup_evidence_ref", String.class),
        deliveryState,
        deliveryStateLabel(deliveryState),
        r.get("delivery_confirmed_at", OffsetDateTime.class),
        r.get("delivery_evidence_source", String.class),
        r.get("delivery_evidence_ref", String.class),
        r.get("exception_flag", Boolean.class) != null && r.get("exception_flag", Boolean.class),
        r.get("exception_code", String.class),
        r.get("exception_note", String.class),
        r.get("exception_at", OffsetDateTime.class),
        r.get("overdue_hours", BigDecimal.class),
        status,
        statusLabel(status),
        gap,
        gapLabel(gap),
        r.get("gap_reason", String.class),
        r.get("last_reconciled_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ==================================================================
  // 读：确认单详情
  // ==================================================================

  public PickupSignoffCaseDetail caseDetail(UUID caseId) {
    Record r =
        sql.fetchOne(
            "select " + CASE_COLUMNS + CASE_FROM + " where c.tenant_id = ? and c.id = ?",
            currentTenant(), caseId);
    if (r == null) {
      throw notFound();
    }
    PickupSignoffCaseRow row = toCaseRow(r);

    List<PickupSignoffEventRow> timeline = new ArrayList<>();
    for (Record e :
        sql.fetch(
            "select id, case_id, event_kind, from_status, to_status, detail, carrier_event_id,"
                + " actor_membership_id, occurred_at"
                + " from logistics_pickup_signoff_event"
                + " where tenant_id = ? and case_id = ? order by occurred_at desc, id desc",
            currentTenant(), caseId)) {
      timeline.add(
          new PickupSignoffEventRow(
              e.get("id", UUID.class),
              e.get("case_id", UUID.class),
              e.get("event_kind", String.class),
              eventKindLabel(e.get("event_kind", String.class)),
              e.get("from_status", String.class),
              e.get("to_status", String.class),
              e.get("detail", String.class),
              e.get("carrier_event_id", String.class),
              e.get("actor_membership_id", UUID.class),
              e.get("occurred_at", OffsetDateTime.class)));
    }

    List<PickupSignoffReminderRow> reminders = new ArrayList<>();
    for (Record m :
        sql.fetch(
            "select id, case_id, reminder_kind, status, title, detail, due_at,"
                + " assigned_to_membership_id, acknowledged_at, acknowledged_by_membership_id,"
                + " resolved_at, resolution_note, created_at"
                + " from logistics_pickup_signoff_reminder"
                + " where tenant_id = ? and case_id = ? order by created_at desc, id desc",
            currentTenant(), caseId)) {
      reminders.add(toReminderRow(m, row.caseNo(), row.masterWaybillNo(), row.carrierCode()));
    }

    List<PickupSignoffNotificationRow> notifications = new ArrayList<>();
    for (Record n :
        sql.fetch(
            "select id, case_id, notification_kind, downstream_module, downstream_status, due_at,"
                + " payload, attempt_count, last_error, consumed_at, consumed_by_membership_id,"
                + " created_at"
                + " from logistics_pickup_signoff_notification"
                + " where tenant_id = ? and case_id = ? order by created_at desc, id desc",
            currentTenant(), caseId)) {
      notifications.add(
          toNotificationRow(n, row.caseNo(), row.masterWaybillNo(), row.carrierCode()));
    }

    List<String> boundaryNotes =
        List.of(
            "责任起点来自 10.3.2.1 交运批次的**逐件**交接时刻，不是批次的粗粒度时刻；"
                + "批次还是 DRAFT（未登记交接）时**不建单** —— 责任还没交出去，"
                + "此时判「超时未揽收」是无中生有。",
            "揽收 / 签收证据来自 10.3.1.1 的承运商事件账本（append-only，按承运商事件 ID 去重）；"
                + "运单号对不上时如实报缺口，**不猜**。",
            "时限是**建单时的快照**：事后改 SLA 规则不会把一笔已经延误的货变成准点。",
            "下游 9.4.2.1（trade，未接线）与 15 客服（未开发）在本页只是**待通知记录**，"
                + "状态到 READY 为止 —— **不假装已回传**。");

    return new PickupSignoffCaseDetail(row, timeline, reminders, notifications, boundaryNotes);
  }

  // ==================================================================
  // 读：提醒 / 待通知 / 事件流
  // ==================================================================

  /** 提醒任务列表：真分页 + 真筛选。 */
  public PickupSignoffReminderPage reminders(
      String carrierCode, String reminderKind, String status, String batchNo, int page, int size) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, 200);
    List<Object> filters = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1=1");
    filters.add(currentTenant());
    where.append(" and r.tenant_id = ?");

    String carrier = text(carrierCode);
    if (!carrier.isEmpty()) {
      where.append(" and c.carrier_code = ?");
      filters.add(carrier);
    }
    String kind = text(reminderKind);
    if (!kind.isEmpty()) {
      where.append(" and r.reminder_kind = ?");
      filters.add(kind);
    }
    String st = text(status);
    if (!st.isEmpty()) {
      where.append(" and r.status = ?");
      filters.add(st);
    }
    String bn = text(batchNo);
    if (!bn.isEmpty()) {
      where.append(" and c.source_batch_no = ?");
      filters.add(bn);
    }

    long total =
        sql.fetchOne(
                "select count(*) from logistics_pickup_signoff_reminder r"
                    + " join logistics_pickup_signoff_case c"
                    + "   on c.tenant_id = r.tenant_id and c.id = r.case_id"
                    + where,
                filters.toArray())
            .get(0, Long.class);

    List<Object> binds = new ArrayList<>(filters);
    binds.add(s);
    binds.add(p * s);
    var rows =
        sql.fetch(
            "select r.id, r.case_id, r.reminder_kind, r.status, r.title, r.detail, r.due_at,"
                + " r.assigned_to_membership_id, r.acknowledged_at, r.acknowledged_by_membership_id,"
                + " r.resolved_at, r.resolution_note, r.created_at, c.case_no, c.master_waybill_no,"
                + " c.carrier_code"
                + " from logistics_pickup_signoff_reminder r"
                + " join logistics_pickup_signoff_case c"
                + "   on c.tenant_id = r.tenant_id and c.id = r.case_id"
                + where
                + " order by r.created_at desc, r.id desc limit ? offset ?",
            binds.toArray());
    List<PickupSignoffReminderRow> items = new ArrayList<>();
    for (Record r : rows) {
      items.add(
          toReminderRow(
              r,
              r.get("case_no", String.class),
              r.get("master_waybill_no", String.class),
              r.get("carrier_code", String.class)));
    }
    return new PickupSignoffReminderPage(items, p, s, total, (int) Math.ceil(total / (double) s));
  }

  private PickupSignoffReminderRow toReminderRow(
      Record r, String caseNo, String waybillNo, String carrierCode) {
    String kind = r.get("reminder_kind", String.class);
    String status = r.get("status", String.class);
    return new PickupSignoffReminderRow(
        r.get("id", UUID.class),
        r.get("case_id", UUID.class),
        caseNo,
        waybillNo,
        carrierCode,
        kind,
        reminderKindLabel(kind),
        status,
        reminderStatusLabel(status),
        r.get("title", String.class),
        r.get("detail", String.class),
        r.get("due_at", OffsetDateTime.class),
        r.get("assigned_to_membership_id", UUID.class),
        r.get("acknowledged_at", OffsetDateTime.class),
        r.get("acknowledged_by_membership_id", UUID.class),
        r.get("resolved_at", OffsetDateTime.class),
        r.get("resolution_note", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private PickupSignoffReminderRow reminderRow(UUID reminderId) {
    Record r =
        sql.fetchOne(
            "select r.id, r.case_id, r.reminder_kind, r.status, r.title, r.detail, r.due_at,"
                + " r.assigned_to_membership_id, r.acknowledged_at, r.acknowledged_by_membership_id,"
                + " r.resolved_at, r.resolution_note, r.created_at, c.case_no, c.master_waybill_no,"
                + " c.carrier_code"
                + " from logistics_pickup_signoff_reminder r"
                + " join logistics_pickup_signoff_case c"
                + "   on c.tenant_id = r.tenant_id and c.id = r.case_id"
                + " where r.tenant_id = ? and r.id = ?",
            currentTenant(), reminderId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "提醒任务不存在");
    }
    return toReminderRow(
        r, r.get("case_no", String.class), r.get("master_waybill_no", String.class),
        r.get("carrier_code", String.class));
  }

  /** 下游待通知列表：真分页 + 真筛选。 */
  public PickupSignoffNotificationPage notifications(
      String carrierCode, String notificationKind, String downstreamStatus, String batchNo, int page,
      int size) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, 200);
    List<Object> filters = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1=1");
    filters.add(currentTenant());
    where.append(" and n.tenant_id = ?");

    String carrier = text(carrierCode);
    if (!carrier.isEmpty()) {
      where.append(" and c.carrier_code = ?");
      filters.add(carrier);
    }
    String kind = text(notificationKind);
    if (!kind.isEmpty()) {
      where.append(" and n.notification_kind = ?");
      filters.add(kind);
    }
    String st = text(downstreamStatus);
    if (!st.isEmpty()) {
      where.append(" and n.downstream_status = ?");
      filters.add(st);
    }
    String bn = text(batchNo);
    if (!bn.isEmpty()) {
      where.append(" and c.source_batch_no = ?");
      filters.add(bn);
    }

    long total =
        sql.fetchOne(
                "select count(*) from logistics_pickup_signoff_notification n"
                    + " join logistics_pickup_signoff_case c"
                    + "   on c.tenant_id = n.tenant_id and c.id = n.case_id"
                    + where,
                filters.toArray())
            .get(0, Long.class);

    List<Object> binds = new ArrayList<>(filters);
    binds.add(s);
    binds.add(p * s);
    var rows =
        sql.fetch(
            "select n.id, n.case_id, n.notification_kind, n.downstream_module, n.downstream_status,"
                + " n.due_at, n.payload, n.attempt_count, n.last_error, n.consumed_at,"
                + " n.consumed_by_membership_id, n.created_at, c.case_no, c.master_waybill_no,"
                + " c.carrier_code"
                + " from logistics_pickup_signoff_notification n"
                + " join logistics_pickup_signoff_case c"
                + "   on c.tenant_id = n.tenant_id and c.id = n.case_id"
                + where
                + " order by n.created_at desc, n.id desc limit ? offset ?",
            binds.toArray());
    List<PickupSignoffNotificationRow> items = new ArrayList<>();
    for (Record r : rows) {
      items.add(
          toNotificationRow(
              r, r.get("case_no", String.class), r.get("master_waybill_no", String.class),
              r.get("carrier_code", String.class)));
    }
    return new PickupSignoffNotificationPage(
        items, p, s, total, (int) Math.ceil(total / (double) s));
  }

  private PickupSignoffNotificationRow toNotificationRow(
      Record r, String caseNo, String waybillNo, String carrierCode) {
    String kind = r.get("notification_kind", String.class);
    String status = r.get("downstream_status", String.class);
    return new PickupSignoffNotificationRow(
        r.get("id", UUID.class),
        r.get("case_id", UUID.class),
        caseNo,
        waybillNo,
        carrierCode,
        kind,
        notificationKindLabel(kind),
        r.get("downstream_module", String.class),
        status,
        notificationStatusLabel(status),
        r.get("due_at", OffsetDateTime.class),
        payloadText(r.get("payload", String.class)),
        r.get("attempt_count", Integer.class) == null ? 0 : r.get("attempt_count", Integer.class),
        r.get("last_error", String.class),
        r.get("consumed_at", OffsetDateTime.class),
        r.get("consumed_by_membership_id", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private PickupSignoffNotificationRow notificationRow(UUID notificationId) {
    Record r =
        sql.fetchOne(
            "select n.id, n.case_id, n.notification_kind, n.downstream_module, n.downstream_status,"
                + " n.due_at, n.payload, n.attempt_count, n.last_error, n.consumed_at,"
                + " n.consumed_by_membership_id, n.created_at, c.case_no, c.master_waybill_no,"
                + " c.carrier_code"
                + " from logistics_pickup_signoff_notification n"
                + " join logistics_pickup_signoff_case c"
                + "   on c.tenant_id = n.tenant_id and c.id = n.case_id"
                + " where n.tenant_id = ? and n.id = ?",
            currentTenant(), notificationId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "下游待通知记录不存在");
    }
    return toNotificationRow(
        r, r.get("case_no", String.class), r.get("master_waybill_no", String.class),
        r.get("carrier_code", String.class));
  }

  /** 确认事件流：真分页 + 真筛选。 */
  public PickupSignoffEventPage events(String eventKind, String status, String batchNo, int page,
      int size) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, 200);
    List<Object> filters = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1=1");
    filters.add(currentTenant());
    where.append(" and e.tenant_id = ?");

    String kind = text(eventKind);
    if (!kind.isEmpty()) {
      where.append(" and e.event_kind = ?");
      filters.add(kind);
    }
    String st = text(status);
    if (!st.isEmpty()) {
      where.append(" and e.to_status = ?");
      filters.add(st);
    }
    String bn = text(batchNo);
    if (!bn.isEmpty()) {
      where.append(" and c.source_batch_no = ?");
      filters.add(bn);
    }

    long total =
        sql.fetchOne(
                "select count(*) from logistics_pickup_signoff_event e"
                    + " join logistics_pickup_signoff_case c"
                    + "   on c.tenant_id = e.tenant_id and c.id = e.case_id"
                    + where,
                filters.toArray())
            .get(0, Long.class);

    List<Object> binds = new ArrayList<>(filters);
    binds.add(s);
    binds.add(p * s);
    var rows =
        sql.fetch(
            "select e.id, e.case_id, e.event_kind, e.from_status, e.to_status, e.detail,"
                + " e.carrier_event_id, e.actor_membership_id, e.occurred_at"
                + " from logistics_pickup_signoff_event e"
                + " join logistics_pickup_signoff_case c"
                + "   on c.tenant_id = e.tenant_id and c.id = e.case_id"
                + where
                + " order by e.occurred_at desc, e.id desc limit ? offset ?",
            binds.toArray());
    List<PickupSignoffEventRow> items = new ArrayList<>();
    for (Record r : rows) {
      items.add(
          new PickupSignoffEventRow(
              r.get("id", UUID.class),
              r.get("case_id", UUID.class),
              r.get("event_kind", String.class),
              eventKindLabel(r.get("event_kind", String.class)),
              r.get("from_status", String.class),
              r.get("to_status", String.class),
              r.get("detail", String.class),
              r.get("carrier_event_id", String.class),
              r.get("actor_membership_id", UUID.class),
              r.get("occurred_at", OffsetDateTime.class)));
    }
    return new PickupSignoffEventPage(items, p, s, total, (int) Math.ceil(total / (double) s));
  }

  // ==================================================================
  // 读：时限规则
  // ==================================================================

  /** 时限规则列表：真分页 + 真筛选。 */
  public PickupSignoffSlaRulePage slaRules(String carrierCode, String status, int page, int size) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, 200);
    List<Object> filters = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1=1");
    filters.add(currentTenant());
    where.append(" and r.tenant_id = ?");
    String carrier = text(carrierCode);
    if (!carrier.isEmpty()) {
      where.append(" and r.carrier_code = ?");
      filters.add(carrier);
    }
    String st = text(status);
    if (!st.isEmpty()) {
      where.append(" and r.status = ?");
      filters.add(st);
    }

    long total =
        sql.fetchOne("select count(*) from logistics_pickup_signoff_sla_rule r" + where,
                filters.toArray())
            .get(0, Long.class);

    List<Object> binds = new ArrayList<>(filters);
    binds.add(s);
    binds.add(p * s);
    var rows =
        sql.fetch(
            "select r.id, r.carrier_code, r.channel_id, r.pickup_sla_hours, r.reminder_lead_hours,"
                + " r.review_invite_delay_hours, r.after_sales_window_hours, r.status, r.note,"
                + " r.version, r.created_at, r.updated_at, ch.channel_code, ch.channel_name"
                + " from logistics_pickup_signoff_sla_rule r"
                + " left join logistics_channel_config ch"
                + "   on ch.tenant_id = r.tenant_id and ch.id = r.channel_id"
                + where
                + " order by r.carrier_code asc, r.id limit ? offset ?",
            binds.toArray());
    List<PickupSignoffSlaRuleRow> items = new ArrayList<>();
    for (Record r : rows) {
      items.add(toSlaRuleRow(r));
    }
    return new PickupSignoffSlaRulePage(items, p, s, total, (int) Math.ceil(total / (double) s));
  }

  private PickupSignoffSlaRuleRow toSlaRuleRow(Record r) {
    String status = r.get("status", String.class);
    return new PickupSignoffSlaRuleRow(
        r.get("id", UUID.class),
        r.get("carrier_code", String.class),
        r.get("channel_id", UUID.class),
        r.get("channel_code", String.class),
        r.get("channel_name", String.class),
        r.get("pickup_sla_hours", BigDecimal.class),
        r.get("reminder_lead_hours", BigDecimal.class),
        r.get("review_invite_delay_hours", BigDecimal.class),
        r.get("after_sales_window_hours", BigDecimal.class),
        status,
        status.equals("ACTIVE") ? "生效中" : "已停用",
        r.get("note", String.class),
        r.get("version", Integer.class) == null ? 0 : r.get("version", Integer.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private PickupSignoffSlaRuleRow slaRuleRow(UUID id) {
    Record r =
        sql.fetchOne(
            "select r.id, r.carrier_code, r.channel_id, r.pickup_sla_hours, r.reminder_lead_hours,"
                + " r.review_invite_delay_hours, r.after_sales_window_hours, r.status, r.note,"
                + " r.version, r.created_at, r.updated_at, ch.channel_code, ch.channel_name"
                + " from logistics_pickup_signoff_sla_rule r"
                + " left join logistics_channel_config ch"
                + "   on ch.tenant_id = r.tenant_id and ch.id = r.channel_id"
                + " where r.tenant_id = ? and r.id = ?",
            currentTenant(), id);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "时限规则不存在");
    }
    return toSlaRuleRow(r);
  }

  // ==================================================================
  // 内部写入助手
  // ==================================================================

  /** 追加确认事件（append-only 表，插入即可）。 */
  private void appendEvent(
      UUID caseId,
      String kind,
      String from,
      String to,
      String detail,
      UUID membershipId,
      String carrierEventId) {
    sql.execute(
        "insert into logistics_pickup_signoff_event (id,tenant_id,case_id,event_kind,from_status,"
            + " to_status,detail,carrier_event_id,actor_membership_id,occurred_at)"
            + " values (?,?,?,?,?,?,?,?,?,now())",
        Ids.next(),
        currentTenant(),
        caseId,
        kind,
        from,
        to,
        detail,
        carrierEventId,
        membershipId);
  }

  /**
   * 落一条提醒任务。
   *
   * <p>🔴 去重落在 DB 唯一约束 {@code UNIQUE (tenant_id, dedupe_key)} 上：
   * 重跑巡检不会把提醒刷成一片，绕过服务层直接写同一条同样撞 23505。
   *
   * @return 1 = 新建，0 = 已存在（本次没有重复提醒）
   */
  private int insertReminder(
      UUID caseId,
      String kind,
      String dedupeKey,
      String title,
      String detail,
      OffsetDateTime dueAt) {
    var row =
        sql.fetchOne(
            "insert into logistics_pickup_signoff_reminder (id,tenant_id,case_id,reminder_kind,"
                + " dedupe_key,status,title,detail,due_at,created_at,updated_at)"
                + " values (?,?,?,?,?,'PENDING',?,?,?::timestamptz,now(),now())"
                + " on conflict (tenant_id,dedupe_key) do nothing returning id",
            Ids.next(),
            currentTenant(),
            caseId,
            kind,
            dedupeKey,
            title,
            detail,
            dueAt);
    return row == null ? 0 : 1;
  }

  /**
   * 作废某几类**待处理**提醒。
   *
   * <p>用 {@code status='CANCELLED'} 而不是物理删：提醒是「当时该做什么」的留痕，
   * 删掉之后没人说得清「当时有没有人收到过这个提醒」。
   */
  private void cancelPendingReminders(UUID caseId, String... kinds) {
    if (kinds.length == 0) {
      return;
    }
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    binds.add(caseId);
    StringBuilder placeholders = new StringBuilder();
    for (String kind : kinds) {
      placeholders.append(placeholders.length() == 0 ? "?" : ",?");
      binds.add(kind);
    }
    sql.execute(
        "update logistics_pickup_signoff_reminder set status='CANCELLED', updated_at=now()"
            + " where tenant_id = ? and case_id = ? and status = 'PENDING'"
            + "   and reminder_kind in (" + placeholders + ")",
        binds.toArray());
  }

  /**
   * 落一条下游待通知记录。
   *
   * <p>载荷里的数量用**定点数字符串**（不退化成浮点），与 9.4.2.1 的 payload 口径一致。
   */
  private int insertNotification(
      UUID caseId,
      String kind,
      String downstreamModule,
      String dedupeKey,
      String title,
      OffsetDateTime dueAt,
      Record caseRow) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("caseId", caseId.toString());
    payload.put("caseNo", text(caseRow.get("case_no", String.class)));
    payload.put("masterWaybillNo", text(caseRow.get("master_waybill_no", String.class)));
    payload.put("carrierCode", text(caseRow.get("carrier_code", String.class)));
    payload.put("parcelNo", caseRow.get("parcel_no", Integer.class));
    payload.put("sourceBatchNo", text(caseRow.get("source_batch_no", String.class)));
    payload.put("handoverAt", String.valueOf(caseRow.get("handover_at", OffsetDateTime.class)));
    payload.put("downstreamModule", downstreamModule);
    payload.put("notificationKind", kind);
    String payloadJson;
    try {
      payloadJson = json.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("待通知载荷序列化失败", e);
    }
    var row =
        sql.fetchOne(
            "insert into logistics_pickup_signoff_notification (id,tenant_id,case_id,"
                + " notification_kind,downstream_module,downstream_status,due_at,payload,"
                + " attempt_count,dedupe_key,created_at,updated_at)"
                + " values (?,?,?,?,?,'PENDING',?::timestamptz,?::jsonb,0,?,now(),now())"
                + " on conflict (tenant_id,dedupe_key) do nothing returning id",
            Ids.next(),
            currentTenant(),
            caseId,
            kind,
            downstreamModule,
            dueAt,
            payloadJson,
            dedupeKey);
    return row == null ? 0 : 1;
  }

  /** jsonb 取回成字符串（契约里 payload 是 string，前端自己 JSON.parse）。 */
  private static String payloadText(String raw) {
    return raw == null ? "{}" : raw;
  }

  // ==================================================================
  // 标签
  // ==================================================================

  static String statusLabel(String status) {
    return switch (status == null ? "" : status) {
      case "PENDING_PICKUP" -> "已交运待揽收";
      case "PICKED_UP" -> "已揽收 · 在途";
      case "PICKUP_OVERDUE" -> "超时未揽收";
      case "DELIVERED" -> "已签收";
      case "VOID" -> "已作废";
      default -> status == null ? "" : status;
    };
  }

  static String pickupStateLabel(String state) {
    return switch (state == null ? "" : state) {
      case "PENDING" -> "未确认揽收";
      case "CONFIRMED" -> "已确认揽收";
      case "OVERDUE" -> "已超时未揽收";
      default -> state == null ? "" : state;
    };
  }

  static String deliveryStateLabel(String state) {
    return switch (state == null ? "" : state) {
      case "PENDING" -> "未确认签收";
      case "CONFIRMED" -> "已确认签收";
      default -> state == null ? "" : state;
    };
  }

  static String reminderKindLabel(String kind) {
    return switch (kind == null ? "" : kind) {
      case "PICKUP_DUE_SOON" -> "即将超时未揽收";
      case "PICKUP_OVERDUE" -> "超时未揽收";
      case "DELIVERY_CONFIRMED" -> "已签收待跟进";
      default -> kind == null ? "" : kind;
    };
  }

  static String reminderStatusLabel(String status) {
    return switch (status == null ? "" : status) {
      case "PENDING" -> "待处理";
      case "ACKNOWLEDGED" -> "已认领";
      case "RESOLVED" -> "已处置";
      case "CANCELLED" -> "已失效";
      default -> status == null ? "" : status;
    };
  }

  static String notificationKindLabel(String kind) {
    return switch (kind == null ? "" : kind) {
      case "PLATFORM_FULFILLMENT_WRITEBACK" -> "平台履约回传";
      case "REVIEW_INVITATION" -> "评价邀请";
      case "AFTER_SALES_WINDOW_CLOSE" -> "售后窗口关闭";
      default -> kind == null ? "" : kind;
    };
  }

  static String notificationStatusLabel(String status) {
    return switch (status == null ? "" : status) {
      case "PENDING" -> "未到时机";
      case "READY" -> "到时机 · 待下游消费";
      case "CONSUMED" -> "下游已消费";
      case "FAILED" -> "消费失败";
      case "CANCELLED" -> "已失效";
      default -> status == null ? "" : status;
    };
  }

  static String eventKindLabel(String kind) {
    return switch (kind == null ? "" : kind) {
      case "CASE_CREATED" -> "建单";
      case "SLA_APPLIED" -> "补上时限";
      case "PICKUP_CONFIRMED" -> "确认揽收";
      case "PICKUP_DUE_SOON" -> "即将超时";
      case "PICKUP_OVERDUE" -> "超时未揽收";
      case "DELIVERY_CONFIRMED" -> "确认签收";
      case "EXCEPTION_MARKED" -> "标记异常";
      case "VOIDED" -> "作废";
      case "ATTESTED" -> "人工确认";
      default -> kind == null ? "" : kind;
    };
  }

  static String gapLabel(String code) {
    return switch (code == null ? "" : code) {
      case "" -> "";
      case "NO_WAYBILL" -> "缺运单号";
      case "TRACKING_SUBJECT_MISSING" -> "缺轨迹主体";
      case "SUBJECT_AMBIGUOUS" -> "运单号不唯一";
      case "BATCH_VOID" -> "批次已作废";
      case "SLA_UNCONFIGURED" -> "未配时限规则";
      default -> code;
    };
  }
}
