package com.starcon.trade;

import static com.starcon.trade.ShipmentWritebackModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
 * 9.4.2.1 平台回传。
 *
 * <p><b>七条实现纪律</b>（照抄同服务相邻模块的写法，不另起风格）：
 *
 * <ol>
 *   <li><b>三态就是三态</b>：成功（CONFIRMED）/ 失败（REJECTED）/ <b>结果未知</b>
 *       （PENDING_VERIFICATION）。未知<b>既不能当失败重发</b>（平台可能已经执行，
 *       重发 = 平台侧重复发货），<b>也不能当成功</b>（平台压根没收到，迟发判定成立
 *       而无人知晓）。未知要走「先查证再决定重发」，由 DB 触发器
 *       {@code guard_shipment_writeback_state} 钉死，不是靠应用层自觉。</li>
 *   <li><b>幂等键由服务端构造</b>：{@code ship-update:<recordId>:r<revision>}，
 *       入参<b>刻意不接受</b> idempotencyKey —— 自拟的键等于允许两次
 *       「不同键、同一次发货」，把幂等从根上绕开。</li>
 *   <li><b>人工修正换键、不改键</b>：修正新建一版（revision + 1、键随之变化），
 *       原任务转 SUPERSEDED 留作证据。沿用旧键的话 channel 会把这次修正当重复
 *       静默丢弃，现场看到的是「我改了内容，平台还是老的」。</li>
 *   <li><b>重试有次数也有退避</b>：attempt_count / max_attempts 落库，
 *       next_attempt_at 排下一次的时刻，指数退避（{@link #BACKOFF_BASE} 起）。
 *       批量重试带 limit 上限：大促期一次「全量重试」把几百条压向平台，
 *       平台限流后全部变成结果未知 —— 那正是本模块要处理的最坏情形。</li>
 *   <li><b>不伪造发货事实</b>：运单号 / 承运商 / 发货时间全部取同服务 9.4.1.2 的
 *       {@code shipment_record} 冻结值（它自己只来自 8.3.4.1 已确认交接），
 *       要改就<b>走修正</b>并留下 revision 与原因，不就地覆盖。</li>
 *   <li><b>时间绑定</b>：{@code ?::timestamptz} 写在 values 的对应位置上，
 *       全程 {@code OffsetDateTime}；时钟走注入的 {@link Clock}，
 *       测试把时钟钉死后，退避节奏与时刻断言都不依赖墙上时钟。</li>
 *   <li><b>回传状态同时喂给 9.4.1.2</b>：同服务 9.4.1.2 的
 *       {@code shipment_record.platform_callback_status} 是为「9.4.2.1 未交付」
 *       留的登记位（V127 文件头第二节），本模块把<b>真实</b>回传进展写进去 ——
 *       先落 {@code shipment_record_callback} 凭据行再更新快照，写序被 V127 的
 *       {@code guard_shipment_record_snapshots} 触发器钉死。</li>
 * </ol>
 */
@Service
public class ShipmentWritebackService {

  /** 单页最大行数（与 9.4.1.1 / 9.4.1.2 / 9.6.1.2 同一上限）。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 批量重试单次上限 —— 一次全量重试会把请求线程与平台限流一起打爆。 */
  static final int MAX_BATCH_RETRY = 200;

  /** 建任务单次最多处理多少张发货记录。 */
  static final int MAX_CREATE_RECORDS = 500;

  /** 退避基数：第 1 次尝试后等 60s，之后 120s / 240s … 最长封顶 24h。 */
  static final Duration BACKOFF_BASE = Duration.ofSeconds(60);

  /** 退避封顶：再多的重试也不该排到明天。 */
  static final Duration BACKOFF_CAP = Duration.ofHours(24);

  /** 幂等键前缀：与同服务两个兄弟表（V119 / V144）的命名空间无交集（它们不存键）。 */
  static final String IDEMPOTENCY_PREFIX = "ship-update:";

  private static final String TASK_COLUMNS =
      "t.id,t.task_no,t.source_record_id,t.source_record_no,t.sales_order_no,"
          + "t.platform_code,t.shop_code,t.shop_id,t.platform_order_ref,t.carrier_code,"
          + "t.master_waybill_no,t.shipped_at,t.idempotency_key,t.status,t.verification,"
          + "t.verified_at,t.attempt_count,t.max_attempts,t.next_attempt_at,t.last_error,"
          + "t.submitted_at,"
          + "t.channel_operation_id,t.platform_request_id,t.revision,t.correction_of,"
          + "t.created_at,t.updated_at,"
          // 真计数：逐箱运单号按箱走，界面上要显示箱数而不是发货票数。
          + "(select count(*) from shipment_record_parcel p where p.tenant_id = t.tenant_id"
          + " and p.record_id = t.source_record_id) as parcel_count";

  private static final Map<WritebackGroupBy, String> GROUP_SQL =
      Map.of(
          WritebackGroupBy.STATUS, "t.status",
          WritebackGroupBy.PLATFORM, "t.platform_code",
          WritebackGroupBy.CARRIER, "t.carrier_code",
          WritebackGroupBy.VERIFICATION, "t.verification");

  private static final String RETRY_GATE_UNKNOWN = "结果未知";
  private static final String RETRY_GATE_AT_CAP = "已达重试上限";
  private static final String RETRY_GATE_BACKOFF = "退避未到点";
  private static final String RETRY_GATE_TERMINAL = "终态不可重发";

  private final DSLContext sql;
  private final ShipmentWritebackPort platform;
  private final Clock clock;

  /**
   * 发给平台的 payload 专用序列化器，<b>刻意不用注入的那个</b>。
   *
   * <p>两个理由，都不是洁癖：<ul>
   *   <li>payload 落库是 {@code jsonb}，读回来（{@code payloadJsonOf}）时
   *       PostgreSQL 已经把它规范化成紧凑形态。写入时若带缩进空格，
   *       「写进去的」与「读出来的」就是两个字符串，payload 指纹失去意义；</li>
   *   <li>投递内容不该随应用级 Jackson 配置（缩进 / 键序）漂移 ——
   *       那是发给平台的字节，不是给人看的。</li>
   * </ul>
   */
  private static final ObjectMapper PAYLOAD_JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .disable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
          .build();

  public ShipmentWritebackService(
      DSLContext sql, ShipmentWritebackPort platform, Clock clock) {
    this.sql = sql;
    this.platform = platform;
    this.clock = clock;
  }

  /** 当前时刻。走注入的 {@link Clock} 而不是 {@code OffsetDateTime.now()}，测试可钉死。 */
  private OffsetDateTime now() {
    return OffsetDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
  }

  // ================================================================ 能力声明

  /** 逐条如实报出**谁真读得到**：发货事实来自同服务 9.4.1.2、投递与查证经 2.5.1.1 /
   *  2.5.1.2 的授权 HTTP 端点、店铺目录（2.3）<b>读不到</b>所以 shopId 由调用方给。 */
  public ShipmentWritebackCapabilities capabilities() {
    return new ShipmentWritebackCapabilities(
        List.of(
            new ShipmentWritebackCapabilityDimension(
                "SHIPMENT_FACT",
                "要回传的发货信息（运单号、承运商、发货时间、逐箱运单号）",
                true,
                "同服务 9.4.1.2 的 shipment_record / shipment_record_parcel"
                    + "（trade 库内只读 join + 同库 FK；该表自身只来自 8.3.4.1 已确认交接）",
                null,
                "本模块不重新登记发货事实：要改就走人工修正并留下 revision 与原因"),
            new ShipmentWritebackCapabilityDimension(
                "QUEUE_DELIVERY",
                "把回传动作投进 2 队列并跟踪平台侧确认状态",
                true,
                "2.5.1.1 的 platform_operation（channel 服务 V2）经 ShipmentWritebackPort 调 "
                    + "POST /platform-operations 与 /start、/succeed、/fail、/unknown；"
                    + "不建跨库视图、不跨服务 SQL、不建跨库 FK",
                null,
                "队列不可达时如实 503，绝不把「没发出去」记成「平台已受理」"),
            new ShipmentWritebackCapabilityDimension(
                "UNKNOWN_VERIFICATION",
                "结果未知的查证（三态里的第三态，先查证再重发）",
                true,
                "2.5.1.2 的 POST /platform-operations/{id}/verify（channel 服务），"
                    + "结论三选一 CONFIRMED_APPLIED / CONFIRMED_NOT_APPLIED / STILL_UNKNOWN",
                null,
                "只有 CONFIRMED_NOT_APPLIED 才允许重发；STILL_UNKNOWN 保持未知并继续禁止重发，"
                    + "这条边由 DB 触发器钉死而非应用层自觉"),
            new ShipmentWritebackCapabilityDimension(
                "SHOP_ID_LOOKUP",
                "店铺标识（channel platform_operation.shop_id 是带 FK 的 uuid）",
                false,
                "2.3 店铺目录在 channel 服务的库内；AGENTS.md 禁止跨服务 SQL / 跨库视图 / 跨库 FK，"
                    + "本服务查不到 shop_code → shop_id 的映射",
                "2.3 店铺 / 2.5 平台对接",
                "shopId 由调用方指定（UI 从店铺选择器带入），本模块不校验其存在性："
                    + "传错时 channel 侧 FK 顶回来，本模块落成 REJECTED + 真实错误，不静默、不编造店铺"),
            new ShipmentWritebackCapabilityDimension(
                "CONSOLIDATION_WRITEUP",
                "合单的物流号回传（蓝图上游之一 9.3.1.2）",
                true,
                "同服务 9.3.1.2 的 order_consolidation_writeback_task（V144，只读引用）",
                null,
                "两者不互相代发：9.3.1.2 只在本地登记合单的物流号回传，本模块只负责"
                    + "发货信息（运单号 / 承运商 / 发货时间）的投递。命名空间与幂等键均隔离，"
                    + "三个写回族（V119 改单取消 / V144 合单 / 本模块）互不覆盖")),
        List.of("PENDING", "SUBMITTED", "CONFIRMED", "REJECTED", "PENDING_VERIFICATION",
            "SKIPPED", "SUPERSEDED"),
        Arrays.stream(WritebackVerification.values()).map(Enum::name).toList(),
        Arrays.stream(WritebackGroupBy.values()).map(g -> g.name() + ":" + g.displayName).toList(),
        "idempotency_key = " + IDEMPOTENCY_PREFIX + "<source_record_id>:r<revision>，"
            + "服务端构造、调用方不可自拟（自拟即可绕开幂等）；"
            + "人工修正 revision + 1 故键必变（沿用旧键会被 channel 当重复静默丢弃）",
        "成功 = CONFIRMED（必须有平台侧凭据）；失败 = REJECTED（可补偿重试）；"
            + "结果未知 = PENDING_VERIFICATION（不是失败）—— 必须先查证，"
            + "只有 CONFIRMED_NOT_APPLIED 才允许重新提交，"
            + "STILL_UNKNOWN 保持未知且继续禁止重发",
        "shopId 由**调用方指定**：2.3 店铺目录在 channel 库内，跨服务 SQL / 跨库视图 / 跨库 FK"
            + "都被 AGENTS.md 禁止，本服务查不到 shop_code → shop_id 映射。"
            + "传错时由 channel 侧 FK 顶回来，本模块落成 REJECTED + 真实错误，"
            + "**不静默、不编造一个店铺**",
        "下游 = 2.5.1.1 队列投递 + 2.5.1.2 未知核验；上游 = 8.3.4.1 经 9.4.1.2 投影的发货事实；"
            + "同服务 9.4.1.2 的 shipment_record.platform_callback_status 由本模块写入真实进展");
  }

  // ================================================================ 建任务

  /**
   * 从发货记录建回传任务（幂等）。
   *
   * <p>幂等有三道：同键撞 UNIQUE；「同一张发货记录只允许一个未结清版本」
   * （partial unique index，见 V147）；重复调用复用既有任务而不是产生第二次平台动作 ——
   * 后者会让平台侧出现两条发货记录，而平台会照单全收地算两次迟发。
   */
  @Transactional
  public ShipmentWritebackCreateResult create(
      ShipmentWritebackCreateInput input, UUID membershipId) {
    OffsetDateTime at = now();
    if (input.recordIds().size() > MAX_CREATE_RECORDS) {
      throw badRequest("单次最多建 " + MAX_CREATE_RECORDS + " 个回传任务，请分批提交");
    }

    int created = 0;
    int reused = 0;
    int skipped = 0;
    int unresolved = 0;
    List<String> notes = new ArrayList<>();

    for (UUID recordId : input.recordIds()) {
      ShipmentSource source = loadSource(recordId);
      if (source == null) {
        unresolved++;
        notes.add("发货记录不存在或不属于本租户，已跳过：" + recordId);
        continue;
      }
      if (WritebackShipmentTerminal.VOID.equals(source.status())) {
        skipped++;
        notes.add("发货记录已作废（VOID），不建回传任务：" + source.recordNo());
        continue;
      }
      String openStatus =
          textOf(
              "select status from shipment_writeback_task where source_record_id = ?"
                  + " and status not in ('CONFIRMED','SKIPPED','SUPERSEDED') limit 1",
              recordId);
      if (openStatus != null) {
        reused++;
        notes.add("该发货记录已有未结清的回传任务（" + openStatus + "），复用而非新建：" + source.recordNo());
        continue;
      }

      String key = idempotencyKey(recordId, 1);
      if (uuidOf("select id from shipment_writeback_task where idempotency_key = ?", key) != null) {
        reused++;
        notes.add("幂等键命中既有任务，复用而非新建：" + key);
        continue;
      }

      String payload = payloadJson(buildPayload(source, input.platformOrderRef(), null, null, null));
      UUID taskId = UUID.randomUUID();
      sql.execute(
          "insert into shipment_writeback_task (id,tenant_id,task_no,source_record_id,"
              + " source_record_no,sales_order_no,platform_code,shop_code,shop_id,"
              + " platform_order_ref,carrier_code,master_waybill_no,shipped_at,idempotency_key,"
              + " payload,payload_checksum,status,next_attempt_at,created_by_membership_id,"
              + " created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,cast(? as jsonb),?,"
              + " 'PENDING',?::timestamptz,?,?::timestamptz,?::timestamptz)",
          taskId,
          currentTenant(),
          nextTaskNo(at),
          recordId,
          source.recordNo(),
          source.salesOrderNo(),
          input.platformCode(),
          source.shopRef(),
          input.shopId(),
          nullSafe(input.platformOrderRef(), ""),
          source.carrierCode(),
          source.masterWaybillNo(),
          source.shippedAt(),
          key,
          payload,
          checksum(payload),
          at,
          membershipId,
          at,
          at);
      writeEvent(
          taskId,
          "TASK_CREATED",
          null,
          WritebackStatus.PENDING,
          "由发货记录 " + source.recordNo() + " 建回传任务（幂等键 " + key
              + "，已排下一次尝试 " + at + "）",
          null,
          membershipId);
      publishCallback(taskId, recordId, WritebackStatus.PENDING, nullSafe(input.note(), ""), membershipId, at);
      created++;
    }
    // 一条都看不见 ⇒ 不是「部分跳过」而是「你递的这些记录不属于你」，
    // 报 200 会让伪造租户头的调用方以为写成功了（notes 里那句只是提示，不是拒绝）。
    // 部分可见仍走逐条汇报 —— 批量场景下个别记录失效不该让整批失败。
    if (created == 0 && reused == 0 && skipped == 0 && unresolved > 0) {
      throw notFound("发货记录不存在或不属于本租户：" + String.join("、", notes));
    }
    return new ShipmentWritebackCreateResult(created, reused, skipped, unresolved, notes, at);
  }

  // ================================================================ 看板 / 清单 / 明细

  /** 分组看板 —— 真 {@code GROUP BY}，不是把一页数据在 Java 里数。 */
  public ShipmentWritebackBoard board(
      String status, String platformCode, String carrierCode, WritebackGroupBy groupBy) {
    WritebackGroupBy by = groupBy == null ? WritebackGroupBy.STATUS : groupBy;
    String expression = GROUP_SQL.get(by);
    Filters filters = filters(status, platformCode, carrierCode, null, null);
    String where = filters.clause.isEmpty() ? "" : " where " + filters.clause;

    List<ShipmentWritebackGroup> groups = new ArrayList<>();
    sql.fetch(
            "select " + expression + " as g, count(*) as tasks,"
                + " count(*) filter (where t.status = 'PENDING_VERIFICATION') as unknowns,"
                + " count(*) filter (where t.status = 'REJECTED') as rejects"
                + " from shipment_writeback_task t" + where
                + " group by " + expression + " order by tasks desc, g asc",
            filters.params.toArray())
        .forEach(
            r -> {
              String key = r.get(0, String.class);
              groups.add(
                  new ShipmentWritebackGroup(
                      key == null ? "" : key,
                      groupLabel(by, key),
                      count(r, 1),
                      count(r, 2),
                      count(r, 3)));
            });

    return new ShipmentWritebackBoard(
        by.name() + ":" + by.displayName,
        groups,
        summary(filters),
        List.of(
            "分组值取自全量回传任务的 GROUP BY，与列表分页无关",
            "PENDING_VERIFICATION（结果未知）是独立一桶 —— 它既不算成功也不算失败，"
                + "更不能被当成失败直接重发",
            "SUPERSEDED / SKIPPED / CONFIRMED 仍留在分组里 —— 删掉等于把"
                + "「当时报了什么、谁改的」这段证据抹掉"));
  }

  /** 任务清单（真分页 + 真筛选 + 排序白名单；汇总不受分页影响）。 */
  public ShipmentWritebackTaskPage tasks(
      String status,
      String platformCode,
      String carrierCode,
      String verification,
      String keyword,
      String sort,
      int page,
      int size) {
    int limit = pageSize(size);
    Filters filters = filters(status, platformCode, carrierCode, verification, keyword);
    String where = filters.clause.isEmpty() ? "" : " where " + filters.clause;

    long total =
        countOf("select count(*) from shipment_writeback_task t" + where, filters.params.toArray());

    List<ShipmentWritebackTaskRow> items = new ArrayList<>();
    sql.fetch(
            "select " + TASK_COLUMNS + " from shipment_writeback_task t" + where
                + " order by " + orderBy(sort) + " limit ? offset ?",
            concat(filters.params, limit, (long) page * limit))
        .forEach(r -> items.add(taskRow(r)));

    return new ShipmentWritebackTaskPage(
        items, page, limit, total, (int) Math.ceil((double) total / limit), summary(filters));
  }

  /** 任务明细：单头 + 冻结 payload + 尝试凭据 + 查证记录 + 时间线 + 边界声明。 */
  public ShipmentWritebackTaskDetail task(String id) {
    UUID taskId = requireTask(id);
    String payload =
        textOf("select payload::text from shipment_writeback_task where id = ?", taskId);

    List<ShipmentWritebackAttemptRow> attempts = new ArrayList<>();
    sql.fetch(
            "select id,attempt_no,idempotency_key,channel_operation_id,platform_request_id,"
                + " outcome,response_ref,error_message,next_attempt_at,started_at,finished_at"
                + " from shipment_writeback_attempt where task_id = ? order by attempt_no desc",
            taskId)
        .forEach(
            r -> {
              WritebackAttemptOutcome outcome =
                  WritebackAttemptOutcome.valueOf(r.get("outcome", String.class));
              attempts.add(
                  new ShipmentWritebackAttemptRow(
                      r.get("id", UUID.class),
                      r.get("attempt_no", Integer.class),
                      r.get("idempotency_key", String.class),
                      r.get("channel_operation_id", UUID.class),
                      r.get("platform_request_id", String.class),
                      outcome,
                      outcome.displayName,
                      r.get("response_ref", String.class),
                      r.get("error_message", String.class),
                      r.get("next_attempt_at", OffsetDateTime.class),
                      r.get("started_at", OffsetDateTime.class),
                      r.get("finished_at", OffsetDateTime.class)));
            });

    List<ShipmentWritebackVerificationRow> verifications = new ArrayList<>();
    sql.fetch(
            "select id,attempt_no,conclusion,source,evidence,queried_at"
                + " from shipment_writeback_verification where task_id = ?"
                + " order by queried_at desc, created_at desc",
            taskId)
        .forEach(
            r -> {
              WritebackVerificationConclusion conclusion =
                  WritebackVerificationConclusion.valueOf(r.get("conclusion", String.class));
              WritebackVerificationSource source =
                  WritebackVerificationSource.valueOf(r.get("source", String.class));
              verifications.add(
                  new ShipmentWritebackVerificationRow(
                      r.get("id", UUID.class),
                      r.get("attempt_no", Integer.class),
                      conclusion,
                      conclusion.displayName,
                      source,
                      source.displayName,
                      r.get("evidence", String.class),
                      r.get("queried_at", OffsetDateTime.class)));
            });

    return new ShipmentWritebackTaskDetail(
        taskRow(loadTask(taskId)),
        nullSafe(payload, "{}"),
        attempts,
        verifications,
        timeline(taskId, 200),
        List.of(
            "payload 是发出那一刻冻结的内容：事后改发货记录不会让「当时到底报了什么」"
                + "变得无法复盘（平台申诉要的就是这一份）",
            "「重发是否放行」= 状态可重试 且 未达重试上限 且 退避已到点；"
                + "结果未知态一律不放行，必须先查证",
            "幂等键不可就地改写（V147 触发器）—— 修正请新建一版，原任务保留作证据",
            "本模块只投递与跟踪；平台侧真实发货动作由 channel 的 platform_operation 执行"));
  }

  /** 事件流（append-only，真分页；刻意没有任何写形状与 DELETE）。 */
  public ShipmentWritebackEventPage events(String eventKind, UUID taskId, int page, int size) {
    int limit = pageSize(size);
    List<Object> params = new ArrayList<>();
    List<String> clauses = new ArrayList<>();
    if (eventKind != null && !eventKind.isBlank()) {
      clauses.add("event_kind = ?");
      params.add(eventKind);
    }
    if (taskId != null) {
      clauses.add("task_id = ?");
      params.add(taskId);
    }
    String where = clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);

    long total = countOf("select count(*) from shipment_writeback_event" + where, params.toArray());

    List<ShipmentWritebackEventRow> items = new ArrayList<>();
    sql.fetch(
            "select id,event_kind,from_status,to_status,detail,attempt_no,occurred_at"
                + " from shipment_writeback_event" + where
                + " order by occurred_at desc, created_at desc, id desc limit ? offset ?",
            concat(params, limit, (long) page * limit))
        .forEach(
            r ->
                items.add(
                    new ShipmentWritebackEventRow(
                        r.get("id", UUID.class),
                        r.get("event_kind", String.class),
                        r.get("from_status", String.class),
                        r.get("to_status", String.class),
                        r.get("detail", String.class),
                        r.get("attempt_no", Integer.class),
                        r.get("occurred_at", OffsetDateTime.class))));

    return new ShipmentWritebackEventPage(
        items, page, limit, total, (int) Math.ceil((double) total / limit));
  }

  // ================================================================ 投递（经 2 队列）

  /**
   * 提交投递：入 2 队列 + 标记开始发送。
   *
   * <p>先入队拿 {@code platform_operation.id} 再落 {@code SUBMITTED}：顺序反过来会
   * 出现「已提交」但队列里根本没有那条动作的窗口，而那时 V127 的
   * {@code shipment_writeback_submitted_needs_queue_ref} 与 9.4.1.2 的快照门都会拒 ——
   * 两道闸门都指向同一个正确的顺序。
   *
   * <p>入队用<b>同一版</b>的幂等键：channel 侧键命中会返回既有任务 id，
   * 所以重复点「提交」不会产生第二次平台动作。
   *
   * <p>刻意<b>不在此刻</b>落 {@code shipment_writeback_attempt} 行：该表是
   * 「一次尝试的<b>最终</b>结果」的 append-only 凭据，而此刻还没有结果。
   * 投递动作本身的凭据是 task 的 channel_operation_id + submitted_at + attempt_count。
   */
  @Transactional
  public ShipmentWritebackTaskDetail submit(
      String id, ShipmentWritebackSubmitInput input, UUID membershipId, String bearer) {
    UUID taskId = requireTask(id);
    TaskState state = loadState(taskId);
    requireResendAllowed(state, "提交");
    return resubmit(
        taskId, state, input == null ? "" : nullSafe(input.note(), ""), membershipId, bearer);
  }

  /** 重新投递一条已放行的任务（首次提交、单条重试、批量重试共用的同一实现）。 */
  private ShipmentWritebackTaskDetail resubmit(
      UUID taskId, TaskState state, String note, UUID membershipId, String bearer) {
    OffsetDateTime at = now();
    UUID operationId =
        platform.enqueue(
            state.shopId(), null, "SHIP_UPDATE", state.idempotencyKey(), payloadJsonOf(taskId),
            bearer);
    platform.start(operationId, bearer);

    int attemptNo = state.attemptCount() + 1;
    sql.execute(
        "update shipment_writeback_task set status = 'SUBMITTED', channel_operation_id = ?,"
            + " submitted_at = coalesce(submitted_at,?::timestamptz), next_attempt_at = null,"
            + " attempt_count = ?, last_error = '', settled_at = null,"
            + " updated_at = ?::timestamptz, version = version + 1"
            + " where id = ?",
        operationId, at, attemptNo, at, taskId);
    writeEvent(
        taskId,
        "SUBMITTED",
        state.status(),
        WritebackStatus.SUBMITTED,
        (note.isEmpty() ? "" : note + "；") + "第 " + attemptNo + " 次投递已入 2 队列"
            + "（platform_operation " + operationId + "，幂等键 " + state.idempotencyKey()
            + "，等待平台回执）",
        attemptNo,
        membershipId);
    publishCallback(taskId, state.sourceRecordId(), WritebackStatus.SUBMITTED, state.idempotencyKey(), membershipId, at);
    return task(taskId.toString());
  }

  // ================================================================ 登记平台结果（三态）

  /**
   * 登记平台侧结果。
   *
   * <p>入参<b>只允许</b> CONFIRMED / REJECTED / PENDING_VERIFICATION 三个值。
   * 「待回传」不是平台结果，「决定不回传」是人为决定 —— 两者混进同一个入口后，
   * 事后就分不清「平台说不行」与「我们决定不发」。
   *
   * <p>登记结果未知时同时把队列侧也标成 UNKNOWN：channel 侧同样区分
   * UNKNOWN 与 FAILED，因为「平台可能已经执行」这个事实必须落到两本账上，
   * 否则将来只有一边记着未知、另一边记着失败，重发时照样会出事。
   *
   * <p>刻意<b>不排下一次尝试</b>：未知还没被判定为失败，排了就等于默认可以重发。
   */
  @Transactional
  public ShipmentWritebackTaskDetail registerOutcome(
      String id, ShipmentWritebackOutcomeInput input, UUID membershipId, String bearer) {
    UUID taskId = requireTask(id);
    WritebackStatus target = input.status();
    if (target != WritebackStatus.CONFIRMED
        && target != WritebackStatus.REJECTED
        && target != WritebackStatus.PENDING_VERIFICATION) {
      throw badRequest(
          "这里只接受平台侧结果三态（平台已受理 / 平台拒绝 / 结果未知），收到的是 " + target
              + "：「待回传」不是平台结果，「决定不回传」也不该从这条路径进来");
    }
    TaskState state = loadState(taskId);
    if (state.status() != WritebackStatus.SUBMITTED) {
      throw badRequest(
          "只有已提交平台（SUBMITTED）的任务才能登记平台结果，当前是 " + state.status());
    }
    if (target == WritebackStatus.PENDING_VERIFICATION
        && (input.errorMessage() == null || input.errorMessage().isBlank())) {
      throw badRequest(
          "登记结果未知必须写明「为什么不知道」（超时？连接断了？）："
              + "这两种情况的查证方式不同，空着等于把「未知」变成一句没有信息的话");
    }
    if (target == WritebackStatus.REJECTED
        && (input.errorMessage() == null || input.errorMessage().isBlank())) {
      // 不是为了本模块好看：9.4.1.2 的 REJECTED 登记同样强制带原因，
      // 空着会被 V127 的 CHECK 顶回来，错误信息会指向 DB 而不是这条请求。
      throw badRequest("登记平台拒绝必须写明原因：平台说不行而不带原因，客服与开发都无从下手");
    }
    if (target == WritebackStatus.CONFIRMED
        && (input.platformRequestId() == null || input.platformRequestId().isBlank())
        && state.channelOperationId() == null) {
      throw badRequest(
          "登记平台已受理必须带回执凭据（平台请求号或队列动作号），否则「已回传」是无内容的一句谎");
    }

    OffsetDateTime at = now();
    int attemptNo = state.attemptCount();

    // 队列侧同样落一笔：UNKNOWN 与 FAILED 在 channel 侧是两个状态。
    if (state.channelOperationId() != null) {
      switch (target) {
        case CONFIRMED ->
            platform.succeed(state.channelOperationId(), input.platformRequestId(), bearer);
        case REJECTED ->
            platform.fail(
                state.channelOperationId(),
                nullSafe(input.errorMessage(), "平台拒绝（未附原因）"),
                bearer);
        default ->
            platform.markUnknown(
                state.channelOperationId(),
                nullSafe(input.errorMessage(), "结果未知（未附原因）"),
                bearer);
      }
    }

    String error = nullSafe(input.errorMessage(), "");

    if (target == WritebackStatus.PENDING_VERIFICATION) {
      sql.execute(
          "update shipment_writeback_task set status = 'PENDING_VERIFICATION', last_error = ?,"
              + " next_attempt_at = null, updated_at = ?::timestamptz, version = version + 1"
              + " where id = ?",
          error, at, taskId);
      writeEvent(
          taskId,
          "OUTCOME_UNKNOWN",
          state.status(),
          WritebackStatus.PENDING_VERIFICATION,
          "平台结果未知（第 " + attemptNo + " 次尝试）：" + error
              + "。未排下一次尝试 —— 必须先查证（只有 CONFIRMED_NOT_APPLIED 才允许重发）",
          attemptNo,
          membershipId);
      publishCallback(taskId, state.sourceRecordId(), WritebackStatus.PENDING_VERIFICATION, error, membershipId, at);
      return task(taskId.toString());
    }

    if (target == WritebackStatus.REJECTED) {
      OffsetDateTime next = at.plus(backoff(attemptNo));
      sql.execute(
          "update shipment_writeback_task set status = 'REJECTED', last_error = ?,"
              + " platform_request_id = ?, settled_at = ?::timestamptz, next_attempt_at = ?::timestamptz,"
              + " updated_at = ?::timestamptz, version = version + 1 where id = ?",
          error,
          nullSafe(input.platformRequestId(), state.platformRequestId()),
          at,
          next,
          at,
          taskId);
      settleAttempt(taskId, attemptNo, WritebackAttemptOutcome.REJECTED,
          nullSafe(input.platformRequestId(), state.platformRequestId()), error, next,
          state.submittedAt(), membershipId, at);
      writeEvent(
          taskId,
          "OUTCOME_REJECTED",
          state.status(),
          WritebackStatus.REJECTED,
          "平台拒绝（第 " + attemptNo + " 次尝试）：" + error + "。已排第 " + (attemptNo + 1)
              + " 次尝试于 " + next + "（指数退避）",
          attemptNo,
          membershipId);
      publishCallback(taskId, state.sourceRecordId(), WritebackStatus.REJECTED, error, membershipId, at);
      return task(taskId.toString());
    }

    sql.execute(
        "update shipment_writeback_task set status = 'CONFIRMED', last_error = '',"
            + " platform_request_id = ?, settled_at = ?::timestamptz, next_attempt_at = null,"
            + " updated_at = ?::timestamptz, version = version + 1 where id = ?",
        nullSafe(input.platformRequestId(), state.platformRequestId()),
        at,
        at,
        taskId);
    settleAttempt(taskId, attemptNo, WritebackAttemptOutcome.CONFIRMED,
        nullSafe(input.platformRequestId(), state.platformRequestId()), "", null,
        state.submittedAt(), membershipId, at);
    writeEvent(
        taskId,
        "OUTCOME_CONFIRMED",
        state.status(),
        WritebackStatus.CONFIRMED,
        "平台已受理（第 " + attemptNo + " 次尝试），回执凭据 "
            + nullSafe(input.platformRequestId(),
                "(沿用队列动作号 " + state.channelOperationId() + ")"),
        attemptNo,
        membershipId);
    publishCallback(taskId, state.sourceRecordId(), WritebackStatus.CONFIRMED,
        nullSafe(input.note(), "平台已受理"), membershipId, at);
    return task(taskId.toString());
  }

  // ================================================================ 查证（未知态的前置闸门）

  /**
   * 登记查证结论 —— 未知态<b>必须</b>先走这条路径才谈得上重发。
   *
   * <p>结论三选一与 2.5.1.2 逐字对齐；经 channel 查证时会把结论转给
   * {@code POST /platform-operations/{id}/verify}，两本账同时落定。
   *
   * <p>落定后的处置刻意区分开：
   * <ul>
   *   <li>{@code CONFIRMED_APPLIED} → 转 CONFIRMED（平台确实已应用，<b>不得重发</b>）；</li>
   *   <li>{@code CONFIRMED_NOT_APPLIED} → 转 REJECTED 并排下一次尝试
   *       （平台确实没应用，这时重发才是安全的）；</li>
   *   <li>{@code STILL_UNKNOWN} → 保持 PENDING_VERIFICATION 且<b>仍不排下一次尝试</b>
   *       （继续等下一次查证，不是「先重发着看看」）。</li>
   * </ul>
   */
  @Transactional
  public ShipmentWritebackTaskDetail verify(
      String id, ShipmentWritebackVerifyInput input, UUID membershipId, String bearer) {
    UUID taskId = requireTask(id);
    TaskState state = loadState(taskId);
    if (state.status() != WritebackStatus.PENDING_VERIFICATION) {
      throw badRequest(
          "只有结果未知（PENDING_VERIFICATION）的任务需要查证，当前是 " + state.status());
    }
    if (input.source() == WritebackVerificationSource.MANUAL_ATTESTATION
        && (input.evidence() == null || input.evidence().isBlank())) {
      throw badRequest(
          "人工查证必须写明依据（平台侧回查到的请求号 / 截图号 / 平台当前发货状态）："
              + "「人工确认平台已收到」没有主体与凭据时，等于没人说的话");
    }

    OffsetDateTime at = now();
    int attemptNo = state.attemptCount() > 0 ? state.attemptCount() : 1;

    if (input.source() == WritebackVerificationSource.CHANNEL_VERIFY) {
      if (state.channelOperationId() == null) {
        throw badRequest("这条任务还没有队列动作号，无从查证；请先提交投递");
      }
      platform.verify(state.channelOperationId(), input.conclusion().name(), bearer);
    }

    sql.execute(
        "insert into shipment_writeback_verification (id,tenant_id,task_id,attempt_no,"
            + " conclusion,source,evidence,queried_at,queried_by_membership_id,created_at)"
            + " values (?,?,?,?,?,?,?,?::timestamptz,?,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        taskId,
        attemptNo,
        input.conclusion().name(),
        input.source().name(),
        nullSafe(input.evidence(), ""),
        at,
        input.source() == WritebackVerificationSource.MANUAL_ATTESTATION ? membershipId : null,
        at);

    if (input.conclusion() == WritebackVerificationConclusion.STILL_UNKNOWN) {
      sql.execute(
          "update shipment_writeback_task set verification = 'STILL_UNKNOWN',"
              + " verified_at = ?::timestamptz, updated_at = ?::timestamptz, version = version + 1"
              + " where id = ?",
          at, at, taskId);
      writeEvent(
          taskId,
          "VERIFICATION_STILL_UNKNOWN",
          state.status(),
          WritebackStatus.PENDING_VERIFICATION,
          "查证后仍不确定（依据：" + nullSafe(input.evidence(), "(未附)")
              + "）。保持未知且不排下一次尝试 —— 先重发着看看正是平台侧重复发货的成因",
          attemptNo,
          membershipId);
      return task(taskId.toString());
    }

    if (input.conclusion() == WritebackVerificationConclusion.CONFIRMED_APPLIED) {
      sql.execute(
          "update shipment_writeback_task set status = 'CONFIRMED',"
              + " verification = 'CONFIRMED_APPLIED', verified_at = ?::timestamptz,"
              + " settled_at = ?::timestamptz, last_error = '', next_attempt_at = null,"
              + " updated_at = ?::timestamptz, version = version + 1 where id = ?",
          at, at, at, taskId);
      settleAttempt(taskId, attemptNo, WritebackAttemptOutcome.CONFIRMED,
          state.platformRequestId(), "查证确认平台已应用", null, state.submittedAt(), membershipId, at);
      writeEvent(
          taskId,
          "VERIFICATION_CONFIRMED_APPLIED",
          state.status(),
          WritebackStatus.CONFIRMED,
          "查证确认平台已应用（依据：" + nullSafe(input.evidence(), "(未附)")
              + "）⇒ 不得重发，重发会造成平台侧重复发货",
          attemptNo,
          membershipId);
      publishCallback(taskId, state.sourceRecordId(), WritebackStatus.CONFIRMED, "查证确认已应用", membershipId, at);
      return task(taskId.toString());
    }

    OffsetDateTime next = at.plus(backoff(attemptNo));
    sql.execute(
        "update shipment_writeback_task set status = 'REJECTED',"
            + " verification = 'CONFIRMED_NOT_APPLIED', verified_at = ?::timestamptz,"
            + " settled_at = ?::timestamptz, next_attempt_at = ?::timestamptz,"
            + " last_error = '查证确认平台未应用，可安全重发',"
            + " updated_at = ?::timestamptz, version = version + 1 where id = ?",
        at, at, next, at, taskId);
    settleAttempt(taskId, attemptNo, WritebackAttemptOutcome.REJECTED,
        state.platformRequestId(), "查证确认平台未应用，可安全重发", next,
        state.submittedAt(), membershipId, at);
    writeEvent(
        taskId,
        "VERIFICATION_CONFIRMED_NOT_APPLIED",
        WritebackStatus.PENDING_VERIFICATION,
        WritebackStatus.REJECTED,
        "查证确认平台未应用（依据：" + nullSafe(input.evidence(), "(未附)")
            + "）⇒ 重发是安全的，已排第 " + (attemptNo + 1) + " 次尝试于 " + next,
        attemptNo,
        membershipId);
    publishCallback(taskId, state.sourceRecordId(), WritebackStatus.REJECTED, "查证确认平台未应用，可安全重发",
        membershipId, at);
    return task(taskId.toString());
  }

  // ================================================================ 重试

  /** 单条重试。放行判据与批量重试<b>同一段代码</b>（{@link #resendGate}），不各写一套。 */
  @Transactional
  public ShipmentWritebackTaskDetail retry(
      String id, ShipmentWritebackRetryInput input, UUID membershipId, String bearer) {
    UUID taskId = requireTask(id);
    TaskState state = loadState(taskId);
    String blocked = resendGate(state, now());
    if (blocked != null) {
      throw badRequest("重试被拒：" + blocked);
    }
    return resubmit(taskId, state, input == null ? "" : nullSafe(input.note(), ""), membershipId,
        bearer);
  }

  /**
   * 批量重试 —— 真 SQL 取候选集，不是在 Java 里过滤一页。
   *
   * <p>候选集就是「{@code status IN ('PENDING','REJECTED')} 且未达重试上限」的集合
   * （V147 的 partial index {@code shipment_writeback_task_retryable} 就是这个谓词的形状）。
   * 逐条过一遍放行闸门，被拦下的<b>逐条说清拦在哪一道</b> ——
   * 「批量重试跑了 50 条」而不说清哪几条没跑，是事后追责最费时间的一种输出。
   *
   * <p>退避未到点<b>不算失败</b>：如实改排一次而不是跳过，否则一次批量重试会把
   * 整片退避中的任务提前放出去，退避节奏形同虚设。
   */
  @Transactional
  public ShipmentWritebackBatchRetryResult batchRetry(
      ShipmentWritebackBatchRetryInput input, UUID membershipId, String bearer) {
    OffsetDateTime at = now();
    int limit = Math.min(input.limit(), MAX_BATCH_RETRY);

    Filters filters = filters(null, input.platformCode(), input.carrierCode(), null, null);
    // 候选集刻意**包含** PENDING_VERIFICATION：它永远不会被放行（见 resendGate），
    // 但必须被取出来报成 blockedUnknown ——「批量重试跑了 50 条」却把若干条
    // 「结果未知·待查证」悄悄漏掉，等于把最该人工看一眼的那批藏起来了。
    // 重试上限只作用于真正可重发的两态：未知态的处置是查证，不是重发。
    String where =
        (filters.clause.isEmpty() ? "" : filters.clause + " and ")
            + "((status in ('PENDING','REJECTED') and attempt_count < max_attempts)"
            + " or status = 'PENDING_VERIFICATION')";

    List<UUID> ids = new ArrayList<>();
    sql.fetch(
            "select id from shipment_writeback_task t where " + where
                + " order by next_attempt_at asc nulls first, created_at asc limit ?",
            concat(filters.params, limit))
        .forEach(r -> ids.add(r.get("id", UUID.class)));

    int submitted = 0;
    int rescheduled = 0;
    int blockedUnknown = 0;
    int blockedAtCap = 0;
    int blockedTerminal = 0;
    List<ShipmentWritebackTaskRow> retried = new ArrayList<>();
    List<String> blocked = new ArrayList<>();

    for (UUID taskId : ids) {
      TaskState state = loadState(taskId);
      String gate = resendGate(state, at);
      if (gate == null) {
        try {
          retried.add(
              resubmit(taskId, state, nullSafe(input.note(), ""), membershipId, bearer).task());
          submitted++;
        } catch (ResponseStatusException e) {
          // 单条失败（队列侧不可达等）不吞掉：如实报出来，继续处理其余任务。
          blocked.add(state.taskNo() + "：" + e.getReason());
        }
        continue;
      }
      if (gate.startsWith(RETRY_GATE_BACKOFF)) {
        OffsetDateTime next = state.nextAttemptAt();
        sql.execute(
            "update shipment_writeback_task set next_attempt_at = ?::timestamptz,"
                + " updated_at = ?::timestamptz, version = version + 1 where id = ?",
            next, at, taskId);
        rescheduled++;
        continue;
      }
      if (gate.startsWith(RETRY_GATE_AT_CAP)) {
        blockedAtCap++;
      } else if (gate.startsWith(RETRY_GATE_UNKNOWN)) {
        blockedUnknown++;
      } else {
        blockedTerminal++;
      }
      blocked.add(state.taskNo() + "：" + gate);
    }

    return new ShipmentWritebackBatchRetryResult(
        submitted, rescheduled, blockedUnknown, blockedAtCap, 0, blockedTerminal, retried,
        blocked, at);
  }

  // ================================================================ 人工修正

  /**
   * 人工修正：改回传内容并重新发起。
   *
   * <p>刻意<b>新建一版</b>而不是改原任务：原任务转 SUPERSEDED（或保持 SKIPPED 作证据），
   * 新版 revision + 1、<b>幂等键随之变化</b>。沿用旧键的话 channel 侧会按幂等
   * 复用既有队列任务、把这次修正当重复<b>静默丢弃</b>，现场看到的是
   * 「我改了内容，平台还是老的」—— 那正是 V147 里那个
   * 「idempotency_key 不可就地改写」触发器要挡的事。
   *
   * <p>{@code SUBMITTED}（已提交、结果未回）<b>刻意拒绝</b>修正：那一次动作已经在
   * 平台侧路上，先登记结果或查证结论再修正，否则等于在结果未明时又发起一次动作。
   */
  @Transactional
  public ShipmentWritebackTaskDetail correct(
      String id, ShipmentWritebackCorrectionInput input, UUID membershipId) {
    UUID taskId = requireTask(id);
    TaskState state = loadState(taskId);
    if (state.status() == WritebackStatus.CONFIRMED) {
      throw badRequest("该任务已被平台受理（CONFIRMED），内容不可再修正：平台侧已经应用了这份内容");
    }
    if (state.status() == WritebackStatus.SUPERSEDED) {
      throw badRequest("该任务已被修正版取代（SUPERSEDED），请对当前有效版本操作");
    }
    if (state.status() == WritebackStatus.SUBMITTED) {
      throw badRequest(
          "该任务已提交平台但结果未回（SUBMITTED）：先登记平台结果或查证结论再修正，"
              + "否则等于在结果未明时又发起一次平台动作");
    }

    OffsetDateTime at = now();
    ShipmentSource source = loadSource(state.sourceRecordId());
    if (source == null) {
      throw notFound("这条任务对应的发货记录已不存在：" + state.sourceRecordId());
    }

    int revision = state.revision() + 1;
    String key = idempotencyKey(state.sourceRecordId(), revision);
    String payload =
        payloadJson(
            buildPayload(
                source,
                input.platformOrderRef() == null ? state.platformOrderRef()
                    : input.platformOrderRef(),
                input.carrierCode(),
                input.masterWaybillNo(),
                input.shippedAt(),
                input.parcels()));

    UUID newTaskId = UUID.randomUUID();
    // 先把上一版结清，再插新版：partial unique index
    // shipment_writeback_task_open_per_record 限定「同一张发货记录同时只允许一个
    // 未结清版本」，先插新版会被这条索引顶回来（duplicate key）。
    // SKIPPED 是终态证据，刻意不转 SUPERSEDED：它回答的是「当时为什么决定不回传」，
    // 改写它等于把那个决定从台账上抹掉。修正版照样指向它作上一版。
    if (!state.status().terminal()) {
      sql.execute(
          "update shipment_writeback_task set status = 'SUPERSEDED', next_attempt_at = null,"
              + " settled_at = ?::timestamptz, updated_at = ?::timestamptz, version = version + 1"
              + " where id = ?",
          at, at, taskId);
      writeEvent(
          taskId,
          "SUPERSEDED",
          state.status(),
          WritebackStatus.SUPERSEDED,
          "已被修正版 r" + revision + "（新幂等键 " + key + "）取代。原因：" + input.reason(),
          null,
          membershipId);
    }

    sql.execute(
        "insert into shipment_writeback_task (id,tenant_id,task_no,source_record_id,"
            + " source_record_no,sales_order_no,platform_code,shop_code,shop_id,"
            + " platform_order_ref,carrier_code,master_waybill_no,shipped_at,idempotency_key,"
            + " payload,payload_checksum,status,next_attempt_at,revision,correction_of,"
            + " correction_reason,corrected_by_membership_id,created_by_membership_id,"
            + " max_attempts,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,cast(? as jsonb),?,"
            + " 'PENDING',?::timestamptz,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        newTaskId,
        currentTenant(),
        nextTaskNo(at),
        state.sourceRecordId(),
        source.recordNo(),
        source.salesOrderNo(),
        state.platformCode(),
        source.shopRef(),
        state.shopId(),
        input.platformOrderRef() == null ? state.platformOrderRef() : input.platformOrderRef(),
        input.carrierCode() == null ? source.carrierCode() : input.carrierCode(),
        input.masterWaybillNo() == null ? source.masterWaybillNo() : input.masterWaybillNo(),
        input.shippedAt() == null ? source.shippedAt() : input.shippedAt(),
        key,
        payload,
        checksum(payload),
        at,
        revision,
        taskId,
        input.reason(),
        membershipId,
        membershipId,
        state.maxAttempts(),
        at,
        at);

    writeEvent(
        newTaskId,
        "TASK_CORRECTED",
        null,
        WritebackStatus.PENDING,
        "由 r" + state.revision() + " 人工修正而来（原因：" + input.reason() + "；幂等键 " + key
            + "，与上一版不同，否则平台侧会当重复丢弃这次修正）",
        null,
        membershipId);
    publishCallback(newTaskId, state.sourceRecordId(), WritebackStatus.PENDING, "人工修正 r" + revision,
        membershipId, at);
    return task(newTaskId.toString());
  }

  // ================================================================ 闸门与放行判据

  /**
   * 放行闸门 —— 返回 {@code null} 表示放行，否则返回<b>拦在哪一道</b>的中文原因。
   *
   * <p>刻意把这个判据抽成<b>一处</b>并让提交、单条重试、批量重试共用：两处各写一套的
   * 必然结果是其中一处漏掉「未知要先查证」这条，而那正是会导致平台侧重复发货的那一条。
   */
  private String resendGate(TaskState state, OffsetDateTime at) {
    if (state.status().unverified()) {
      // 已查证过但结论仍是未知，与「压根还没查证」要分开说：
      // 前者已经付过一次查证成本，下一步是换一个查证途径，而不是再来一次同样的查询。
      if (state.verification() == WritebackVerification.STILL_UNKNOWN) {
        return RETRY_GATE_UNKNOWN
            + "（上一次查证结论仍是 STILL_UNKNOWN）：先出查证结论再谈重发";
      }
      return RETRY_GATE_UNKNOWN
          + "（PENDING_VERIFICATION）：必须先查证，只有 CONFIRMED_NOT_APPLIED 才允许重发 —— "
          + "未知直接重发就是平台侧重复发货";
    }
    if (state.status().terminal()) {
      return RETRY_GATE_TERMINAL + "（" + state.status().displayName + "）：已定论，改内容请走人工修正";
    }
    if (!state.status().retryable()) {
      return RETRY_GATE_TERMINAL + "（" + state.status().displayName + "）：当前状态不进入重试排程";
    }
    if (state.attemptCount() >= state.maxAttempts()) {
      return RETRY_GATE_AT_CAP + " " + state.maxAttempts()
          + " 次：继续重发只会重复撞同一个失败，人工修正或人工核实平台侧才是出路";
    }
    if (state.verification() == WritebackVerification.STILL_UNKNOWN) {
      return RETRY_GATE_UNKNOWN + "（上一次查证结论仍是 STILL_UNKNOWN）：先出查证结论再谈重发";
    }
    OffsetDateTime next = state.nextAttemptAt();
    if (next != null && next.isAfter(at)) {
      return RETRY_GATE_BACKOFF + "：下一次可发时刻是 " + next + "，当前 " + at;
    }
    return null;
  }

  private void requireResendAllowed(TaskState state, String action) {
    String blocked = resendGate(state, now());
    if (blocked != null) {
      throw badRequest(action + "被拒：" + blocked);
    }
  }

  /** 指数退避：第 1 次尝试后 60s，第 2 次 120s … 封顶 24h。 */
  static Duration backoff(int attemptCount) {
    int exponent = Math.max(0, Math.min(attemptCount - 1, 20));
    Duration delay;
    try {
      delay = BACKOFF_BASE.multipliedBy(1L << exponent);
    } catch (ArithmeticException e) {
      return BACKOFF_CAP;
    }
    return delay.compareTo(BACKOFF_CAP) > 0 ? BACKOFF_CAP : delay;
  }

  // ================================================================ 与 9.4.1.2 的接缝

  /**
   * 把真实回传进展写进同服务 9.4.1.2 的登记位。
   *
   * <p>9.4.1.2 的 {@code platform_callback_status} 是为「9.4.2.1 未交付」留的位置
   * （V127 文件头第二节），现在本模块交付了，它就该变成真实数据而不是人工登记。
   *
   * <p><b>写序被 V127 的触发器钉死</b>：必须先落
   * {@code shipment_record_callback} 凭据行、再更新 {@code shipment_record} 快照，
   * 顺序反了会被 {@code guard_shipment_record_snapshots} 拒掉。
   *
   * <p>映射表（V127 的取值域没有 UNKNOWN —— 这是事实汇报，不是把未知压成别的）：
   * <ul>
   *   <li>PENDING → PENDING；SUBMITTED → SUBMITTED；</li>
   *   <li>CONFIRMED → ACKNOWLEDGED（带回执凭据）；REJECTED → REJECTED（带原因）；</li>
   *   <li>PENDING_VERIFICATION → SUBMITTED + 说明「结果未知·待查证」
   *       ——「已提交平台」这个事实是真的，未知性由本模块的独立状态承担；</li>
   *   <li>SKIPPED → SKIPPED（带原因）；</li>
   *   <li>SUPERSEDED <b>不写</b>：它是本地记账态、不是平台结果，
   *       新版任务落地时已把快照更新为 PENDING。</li>
   * </ul>
   */
  private void publishCallback(
      UUID taskId, UUID recordId, WritebackStatus status, String note, UUID membershipId,
      OffsetDateTime at) {
    String mapped =
        switch (status) {
          case PENDING -> "PENDING";
          case SUBMITTED -> "SUBMITTED";
          case CONFIRMED -> "ACKNOWLEDGED";
          case REJECTED -> "REJECTED";
          // 「已提交平台」这个事实是真的，未知性由本模块的独立状态承担。
          case PENDING_VERIFICATION -> "SUBMITTED";
          case SKIPPED -> "SKIPPED";
          case SUPERSEDED -> null;
        };
    if (mapped == null) return;

    String key = textOf("select idempotency_key from shipment_writeback_task where id = ?", taskId);
    String text =
        status == WritebackStatus.PENDING_VERIFICATION
            ? "结果未知·待查证（9.4.2.1 状态 " + status.name() + "）：" + nullSafe(note, "")
            : nullSafe(note, "");

    sql.execute(
        "insert into shipment_record_callback (id,tenant_id,record_id,status,source,request_ref,"
            + " response_ref,note,registered_at,registered_by_membership_id)"
            + " values (?,?,?,?, 'CHANNEL_SERVICE',?,?,?,?::timestamptz,?)",
        UUID.randomUUID(),
        currentTenant(),
        recordId,
        mapped,
        nullSafe(key, ""),
        // ACKNOWLEDGED 在 V127 里强制带 response_ref：回执凭据落在那里才有据可查。
        "ACKNOWLEDGED".equals(mapped) ? nullSafe(key, "") : "",
        text,
        at,
        membershipId);

    sql.execute(
        "update shipment_record set platform_callback_status = ?,"
            + " platform_callback_source = 'CHANNEL_SERVICE',"
            + " platform_callback_registered_at = ?::timestamptz,"
            + " platform_callback_by_membership_id = ?, platform_callback_ref = ?,"
            + " platform_callback_note = ?, updated_at = ?::timestamptz where id = ?",
        mapped, at, membershipId, nullSafe(key, ""), text, at, recordId);
  }

  // ================================================================ 内部工具

  /** 发货事实快照（只读自同服务 9.4.1.2）。 */
  private record ShipmentSource(
      UUID id,
      String recordNo,
      String salesOrderNo,
      String shopRef,
      String carrierCode,
      String carrierName,
      String masterWaybillNo,
      OffsetDateTime shippedAt,
      String totalQuantity,
      String totalGrossWeightG,
      int parcelCount,
      String status) {}

  private record TaskState(
      UUID id,
      String taskNo,
      UUID sourceRecordId,
      String platformCode,
      String platformOrderRef,
      UUID shopId,
      String idempotencyKey,
      WritebackStatus status,
      WritebackVerification verification,
      int attemptCount,
      int maxAttempts,
      OffsetDateTime nextAttemptAt,
      UUID channelOperationId,
      String platformRequestId,
      OffsetDateTime submittedAt,
      int revision) {}

  /** 9.4.1.2 的发货记录终态（与 ShipmentRecordStatus 同词汇）。 */
  private static final class WritebackShipmentTerminal {
    static final String VOID = "VOID";

    private WritebackShipmentTerminal() {}
  }

  private ShipmentSource loadSource(UUID recordId) {
    Record r =
        sql.fetchOne(
            "select r.id,r.record_no,r.sales_order_no,r.shop_ref,r.carrier_code,r.carrier_name,"
                + " r.master_waybill_no,r.shipped_at,r.total_quantity,r.total_gross_weight_g,"
                + " r.parcel_count,r.status from shipment_record r where r.id = ?",
            recordId);
    if (r == null) return null;
    return new ShipmentSource(
        r.get("id", UUID.class),
        nullSafe(r.get("record_no", String.class), ""),
        nullSafe(r.get("sales_order_no", String.class), ""),
        nullSafe(r.get("shop_ref", String.class), ""),
        nullSafe(r.get("carrier_code", String.class), ""),
        nullSafe(r.get("carrier_name", String.class), ""),
        nullSafe(r.get("master_waybill_no", String.class), ""),
        r.get("shipped_at", OffsetDateTime.class),
        nullSafe(r.get("total_quantity", String.class), "0"),
        nullSafe(r.get("total_gross_weight_g", String.class), "0"),
        r.get("parcel_count", Integer.class) == null ? 0 : r.get("parcel_count", Integer.class),
        nullSafe(r.get("status", String.class), ""));
  }

  private Map<String, Object> buildPayload(
      ShipmentSource source,
      String platformOrderRef,
      String carrierCodeOverride,
      String waybillOverride,
      OffsetDateTime shippedAtOverride,
      List<ParcelWaybillInput> parcelOverride) {
    List<Map<String, Object>> parcels = new ArrayList<>();
    if (parcelOverride != null && !parcelOverride.isEmpty()) {
      for (ParcelWaybillInput p : parcelOverride) {
        parcels.add(
            Map.<String, Object>of(
                "parcelNo", p.parcelNo(), "waybillNo", nullSafe(p.waybillNo(), "")));
      }
    } else {
      sql.fetch(
              "select parcel_no, waybill_no from shipment_record_parcel"
                  + " where record_id = ? order by parcel_no",
              source.id())
          .forEach(
              r -> {
                String waybill = nullSafe(r.get("waybill_no", String.class), "");
                parcels.add(
                    Map.<String, Object>of(
                        "parcelNo", r.get("parcel_no", Integer.class),
                        // 逐箱号为空表示沿用主运单号 —— 如实标出来，别编一个号上去。
                        "waybillNo", waybill,
                        "inheritsMasterWaybill", waybill.isEmpty()));
              });
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("operation", "SHIP_UPDATE");
    payload.put("sourceRecordNo", source.recordNo());
    payload.put("salesOrderNo", source.salesOrderNo());
    payload.put("platformOrderRef", nullSafe(platformOrderRef, ""));
    payload.put("carrierCode", nullSafe(carrierCodeOverride, source.carrierCode()));
    payload.put("carrierName", source.carrierName());
    payload.put("masterWaybillNo", nullSafe(waybillOverride, source.masterWaybillNo()));
    payload.put(
        "shippedAt",
        String.valueOf(shippedAtOverride == null ? source.shippedAt() : shippedAtOverride));
    // 数量与重量刻意写成字符串：它们在库里是 numeric(19,6) / numeric(19,3)，
    // 经 JSON 数字过一道浮点就可能掉精度（AGENTS.md：禁止浮点记账）。
    payload.put("totalQuantity", source.totalQuantity());
    payload.put("totalGrossWeightG", source.totalGrossWeightG());
    payload.put("parcelCount", source.parcelCount());
    payload.put("parcels", parcels);
    return payload;
  }

  private Map<String, Object> buildPayload(
      ShipmentSource source,
      String platformOrderRef,
      String carrierCodeOverride,
      String waybillOverride,
      OffsetDateTime shippedAtOverride) {
    return buildPayload(
        source, platformOrderRef, carrierCodeOverride, waybillOverride, shippedAtOverride, null);
  }

  private String payloadJson(Map<String, Object> payload) {
    try {
      return PAYLOAD_JSON.writeValueAsString(payload);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private String payloadJsonOf(UUID taskId) {
    String value = textOf("select payload::text from shipment_writeback_task where id = ?", taskId);
    if (value == null || value.isBlank()) {
      throw badRequest("回传任务的 payload 缺失，无法投递：" + taskId);
    }
    return value;
  }

  /** payload 指纹：让人工修正的「改了什么」有个可比的凭据。 */
  private String checksum(String payloadJson) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
          .substring(0, 64);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * 落一条「尝试已定局」的 append-only 凭据行。
   *
   * <p>{@code startedAt} 取任务自己的 {@code submitted_at}（= 本次尝试投递的时刻），
   * 刻意不用「现在」：结果登记往往晚于投递，用写入时刻冒充会让
   * 「平台响应有多慢」永远算不出来。
   */
  private void settleAttempt(
      UUID taskId,
      int attemptNo,
      WritebackAttemptOutcome outcome,
      String platformRequestId,
      String errorMessage,
      OffsetDateTime nextAttemptAt,
      OffsetDateTime startedAt,
      UUID membershipId,
      OffsetDateTime finishedAt) {
    // 用 INSERT ... SELECT 而不是「先查再插」：幂等键与队列动作号必须取自任务当前行，
    // 分成两条语句就有一个「查完到插完之间被改掉」的窗口。
    sql.execute(
        "insert into shipment_writeback_attempt (id,tenant_id,task_id,attempt_no,idempotency_key,"
            + " channel_operation_id,platform_request_id,outcome,response_ref,error_message,"
            + " next_attempt_at,started_at,finished_at,actor_membership_id,created_at)"
            + " select ?,?,t.id,?,t.idempotency_key,t.channel_operation_id,?,?,?,?,?::timestamptz,"
            + " ?::timestamptz,?::timestamptz,?,?::timestamptz"
            + " from shipment_writeback_task t where t.id = ?",
        UUID.randomUUID(),
        currentTenant(),
        attemptNo,
        nullSafe(platformRequestId, ""),
        outcome.name(),
        nullSafe(platformRequestId, ""),
        nullSafe(errorMessage, ""),
        nextAttemptAt,
        startedAt == null ? finishedAt : startedAt,
        finishedAt,
        membershipId,
        finishedAt,
        taskId);
  }

  private void writeEvent(
      UUID taskId,
      String kind,
      WritebackStatus fromStatus,
      WritebackStatus toStatus,
      String detail,
      Integer attemptNo,
      UUID actorMembershipId) {
    sql.execute(
        "insert into shipment_writeback_event (id,tenant_id,task_id,event_kind,from_status,"
            + " to_status,detail,attempt_no,actor_membership_id,occurred_at)"
            + " values (?,?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        taskId,
        kind,
        fromStatus == null ? "" : fromStatus.name(),
        toStatus == null ? "" : toStatus.name(),
        nullSafe(detail, ""),
        attemptNo,
        actorMembershipId,
        now());
  }

  private List<ShipmentWritebackEventRow> timeline(UUID taskId, int limit) {
    List<ShipmentWritebackEventRow> rows = new ArrayList<>();
    sql.fetch(
            "select id,event_kind,from_status,to_status,detail,attempt_no,occurred_at"
                + " from shipment_writeback_event where task_id = ?"
                + " order by occurred_at desc, created_at desc, id desc limit ?",
            taskId, limit)
        .forEach(
            r ->
                rows.add(
                    new ShipmentWritebackEventRow(
                        r.get("id", UUID.class),
                        r.get("event_kind", String.class),
                        r.get("from_status", String.class),
                        r.get("to_status", String.class),
                        r.get("detail", String.class),
                        r.get("attempt_no", Integer.class),
                        r.get("occurred_at", OffsetDateTime.class))));
    return rows;
  }

  private UUID requireTask(String id) {
    UUID taskId = parseUuid(id, "回传任务不存在：" + id);
    if (countOf("select count(*) from shipment_writeback_task where id = ?", taskId) == 0) {
      throw notFound("回传任务不存在：" + id);
    }
    return taskId;
  }

  private Record loadTask(UUID taskId) {
    Record r =
        sql.fetchOne(
            "select " + TASK_COLUMNS + " from shipment_writeback_task t where t.id = ?", taskId);
    if (r == null) throw notFound("回传任务不存在：" + taskId);
    return r;
  }

  private TaskState loadState(UUID taskId) {
    Record r = loadTask(taskId);
    return new TaskState(
        r.get("id", UUID.class),
        nullSafe(r.get("task_no", String.class), ""),
        r.get("source_record_id", UUID.class),
        nullSafe(r.get("platform_code", String.class), ""),
        nullSafe(r.get("platform_order_ref", String.class), ""),
        r.get("shop_id", UUID.class),
        nullSafe(r.get("idempotency_key", String.class), ""),
        WritebackStatus.valueOf(r.get("status", String.class)),
        WritebackVerification.valueOf(r.get("verification", String.class)),
        r.get("attempt_count", Integer.class) == null ? 0 : r.get("attempt_count", Integer.class),
        r.get("max_attempts", Integer.class) == null ? 5 : r.get("max_attempts", Integer.class),
        r.get("next_attempt_at", OffsetDateTime.class),
        r.get("channel_operation_id", UUID.class),
        nullSafe(r.get("platform_request_id", String.class), ""),
        r.get("submitted_at", OffsetDateTime.class),
        r.get("revision", Integer.class) == null ? 1 : r.get("revision", Integer.class));
  }

  private ShipmentWritebackTaskRow taskRow(Record r) {
    WritebackStatus status = WritebackStatus.valueOf(r.get("status", String.class));
    WritebackVerification verification =
        WritebackVerification.valueOf(r.get("verification", String.class));
    int attemptCount = intOf(r, "attempt_count", 0);
    int maxAttempts = intOf(r, "max_attempts", 5);
    OffsetDateTime next = r.get("next_attempt_at", OffsetDateTime.class);

    boolean allowed =
        status.retryable()
            && attemptCount < maxAttempts
            && verification != WritebackVerification.STILL_UNKNOWN
            && (next == null || !next.isAfter(now()));

    return new ShipmentWritebackTaskRow(
        r.get("id", UUID.class),
        nullSafe(r.get("task_no", String.class), ""),
        r.get("source_record_id", UUID.class),
        nullSafe(r.get("source_record_no", String.class), ""),
        nullSafe(r.get("sales_order_no", String.class), ""),
        nullSafe(r.get("platform_code", String.class), ""),
        nullSafe(r.get("shop_code", String.class), ""),
        r.get("shop_id", UUID.class),
        nullSafe(r.get("platform_order_ref", String.class), ""),
        nullSafe(r.get("carrier_code", String.class), ""),
        nullSafe(r.get("master_waybill_no", String.class), ""),
        r.get("shipped_at", OffsetDateTime.class),
        nullSafe(r.get("idempotency_key", String.class), ""),
        status,
        status.displayName,
        verification,
        verification.displayName,
        r.get("verified_at", OffsetDateTime.class),
        attemptCount,
        maxAttempts,
        next,
        nullSafe(r.get("last_error", String.class), ""),
        r.get("channel_operation_id", UUID.class),
        nullSafe(r.get("platform_request_id", String.class), ""),
        intOf(r, "revision", 1),
        r.get("correction_of", UUID.class),
        parcelCount(r),
        allowed,
        allowed ? "" : blockedReason(status, verification, attemptCount, maxAttempts, next),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private String blockedReason(
      WritebackStatus status,
      WritebackVerification verification,
      int attemptCount,
      int maxAttempts,
      OffsetDateTime next) {
    if (status.unverified() || verification == WritebackVerification.STILL_UNKNOWN) {
      return "结果未知：必须先查证，只有 CONFIRMED_NOT_APPLIED 才允许重发";
    }
    if (status.terminal()) return "终态：" + status.displayName + "，改内容请走人工修正";
    if (attemptCount >= maxAttempts) return "已达重试上限 " + maxAttempts + " 次";
    if (next != null && next.isAfter(now())) return "退避未到点，下一次可发 " + next;
    return "";
  }

  private ShipmentWritebackSummary summary(Filters filters) {
    OffsetDateTime at = now();
    String where = filters.clause.isEmpty() ? "" : " where " + filters.clause;
    // 绑定参数的顺序跟着 SQL 文本走：两个 ?::timestamptz 出现在 SELECT 列表里，
    // 位置在 WHERE 之前，所以 at 必须排在 filters.params 前面。
    List<Object> params = new ArrayList<>();
    params.add(at);
    params.add(at);
    params.addAll(filters.params);
    Record r =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where t.status = 'PENDING') as pending,"
                + " count(*) filter (where t.status = 'SUBMITTED') as submitted,"
                + " count(*) filter (where t.status = 'CONFIRMED') as confirmed,"
                + " count(*) filter (where t.status = 'REJECTED') as rejected,"
                + " count(*) filter (where t.status = 'PENDING_VERIFICATION') as unknown,"
                + " count(*) filter (where t.status = 'SKIPPED') as skipped,"
                + " count(*) filter (where t.status = 'SUPERSEDED') as superseded,"
                + " count(*) filter (where t.status in ('PENDING','REJECTED')"
                + "   and t.attempt_count < t.max_attempts"
                + "   and (t.next_attempt_at is null or t.next_attempt_at <= ?::timestamptz)) as due,"
                + " count(*) filter (where t.status = 'PENDING_VERIFICATION'"
                + "   and t.verification <> 'STILL_UNKNOWN') as unverified,"
                // resendAllowed = due **且**上一次查证没留下 STILL_UNKNOWN：
                // 未知结论挂着时哪怕状态已落回可重试，也不能重发。
                + " count(*) filter (where t.status in ('PENDING','REJECTED')"
                + "   and t.attempt_count < t.max_attempts"
                + "   and t.verification <> 'STILL_UNKNOWN'"
                + "   and (t.next_attempt_at is null or t.next_attempt_at <= ?::timestamptz)) as resend,"
                + " count(*) filter (where t.status in ('PENDING','REJECTED')"
                + "   and t.attempt_count >= t.max_attempts) as atcap,"
                + " coalesce(sum(t.attempt_count),0) as attempts"
                + " from shipment_writeback_task t" + where,
            concat(params));
    return new ShipmentWritebackSummary(
        count(r, 0),
        count(r, 1),
        count(r, 2),
        count(r, 3),
        count(r, 4),
        count(r, 5),
        count(r, 6),
        count(r, 7),
        count(r, 8),
        count(r, 9),
        count(r, 10),
        count(r, 11),
        numberOf(r, "attempts"));
  }

  private String groupLabel(WritebackGroupBy by, String key) {
    if (key == null || key.isBlank()) return "未指定";
    return switch (by) {
      case STATUS -> WritebackStatus.valueOf(key).displayName;
      case PLATFORM -> key;
      case CARRIER -> key;
      case VERIFICATION -> WritebackVerification.valueOf(key).displayName;
    };
  }

  /** 排序白名单：值不进 SQL 拼接。 */
  private String orderBy(String sort) {
    if (sort == null) return "t.created_at desc";
    try {
      return switch (WritebackSort.valueOf(sort)) {
        case CREATED_AT_DESC -> "t.created_at desc";
        case CREATED_AT_ASC -> "t.created_at asc";
        case SHIPPED_AT_DESC -> "t.shipped_at desc nulls last, t.created_at desc";
        case ATTEMPT_COUNT_DESC -> "t.attempt_count desc, t.created_at desc";
        case NEXT_ATTEMPT_AT_ASC -> "t.next_attempt_at asc nulls first, t.created_at desc";
      };
    } catch (IllegalArgumentException e) {
      return "t.created_at desc";
    }
  }

  private Filters filters(
      String status, String platformCode, String carrierCode, String verification, String keyword) {
    List<String> clauses = new ArrayList<>();
    List<Object> params = new ArrayList<>();
    if (status != null && !status.isBlank()) {
      clauses.add("t.status = ?");
      params.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (platformCode != null && !platformCode.isBlank()) {
      clauses.add("t.platform_code = ?");
      params.add(platformCode.trim());
    }
    if (carrierCode != null && !carrierCode.isBlank()) {
      clauses.add("t.carrier_code = ?");
      params.add(carrierCode.trim());
    }
    if (verification != null && !verification.isBlank()) {
      clauses.add("t.verification = ?");
      params.add(verification.trim().toUpperCase(Locale.ROOT));
    }
    if (keyword != null && !keyword.isBlank()) {
      String like = "%" + keyword.trim() + "%";
      clauses.add(
          "(t.task_no like ? or t.master_waybill_no like ? or t.platform_order_ref like ?"
              + " or t.sales_order_no like ? or t.source_record_no like ? or t.idempotency_key like ?)");
      params.add(like);
      params.add(like);
      params.add(like);
      params.add(like);
      params.add(like);
      params.add(like);
    }
    return new Filters(String.join(" and ", clauses), params);
  }

  private record Filters(String clause, List<Object> params) {}

  static String idempotencyKey(UUID recordId, int revision) {
    return IDEMPOTENCY_PREFIX + recordId + ":r" + revision;
  }

  private int pageSize(int size) {
    if (size <= 0) return 50;
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private long countOf(String text, Object... binds) {
    Record record = binds.length == 0 ? sql.fetchOne(text) : sql.fetchOne(text, binds);
    if (record == null) return 0L;
    Number value = (Number) record.get(0);
    return value == null ? 0L : value.longValue();
  }

  private static long count(Record r, int index) {
    Number value = r == null ? null : (Number) r.get(index);
    return value == null ? 0L : value.longValue();
  }

  private static int intOf(Record r, String column, int fallback) {
    Number value = r == null ? null : (Number) r.get(column);
    return value == null ? fallback : value.intValue();
  }

  private static int numberOf(Record r, String column) {
    Number value = r == null ? null : (Number) r.get(column);
    return value == null ? 0 : value.intValue();
  }

  private static BigDecimal parcelCount(Record r) {
    Number value = r == null ? null : (Number) r.get("parcel_count");
    return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
  }

  /** 当前 RLS 租户。由 backend/libraries/tenancy 在事务内设置，客户端头改不动它。 */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid as t")
        .get("t", UUID.class);
  }

  private UUID uuidOf(String text, Object... binds) {
    Record record = sql.fetchOne(text, binds);
    return record == null ? null : record.get(0, UUID.class);
  }

  private String textOf(String text, Object... binds) {
    Record record = sql.fetchOne(text, binds);
    return record == null ? null : record.get(0, String.class);
  }

  private String nextTaskNo(OffsetDateTime at) {
    Long n = sql.fetchOne("select nextval('shipment_writeback_task_no_seq')").get(0, Long.class);
    return String.format(
        Locale.ROOT, "PB-%s%06d", at.toLocalDate().toString().replace("-", ""), n);
  }

  private static String nullSafe(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static Object[] concat(List<Object> base, Object... tail) {
    Object[] all = new Object[base.size() + tail.length];
    for (int i = 0; i < base.size(); i++) all[i] = base.get(i);
    System.arraycopy(tail, 0, all, base.size(), tail.length);
    return all;
  }

  private static UUID parseUuid(String value, String message) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw notFound(message);
    }
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
