package com.starcon.platform;

import static com.starcon.platform.DeliveryRecordModels.*;
import static com.starcon.platform.EventSubscriptionModels.EventSubscriptionPolicyRow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 18.1.2.2「投递记录」的服务层。
 *
 * <p>蓝图 18-integration-and-automation.md 18.1.2.2 原文：
 *
 * <blockquote>
 *
 * 职责：记录事件投递明细：事件 ID、订阅方、投递状态、响应码、重试次数；提供手动重投与死信重放。
 * 输入：投递记录；重放请求。 输出：投递日志 + 重放任务。
 * 关系：上游 = 18.1.2.1；下游 = 外部端点。
 * <b>接收方应按 event_id 幂等去重</b>——投递保证是"至少一次"，不是"恰好一次"。
 *
 * </blockquote>
 *
 * <p>🔴 <b>本类补上的是 18.1.2.1 刻意留空的那一格</b>：
 * {@code UnavailableEventSubscriptionDeliveryPort} 如实答「未接入」、端点恒 409，
 * 理由写得很清楚 ——「投递执行器与投递日志同属那一格（18.1.2.2）」。
 * 本类就是那一格：<b>真的把包发出去</b>（{@link WebhookDeliveryExecutor}）、
 * <b>真的把响应码记下来</b>（{@code platform_delivery_attempt}）。
 *
 * <p><b>七条纪律：</b>
 *
 * <ol>
 *   <li><b>数据访问一律走 jOOQ 显式 SQL</b>（AGENTS.md：不用 JPA 懒加载掩盖关键逻辑）。
 *       租户上下文由 {@link TenantTransactions} 绑定 —— 本类**不**读任何请求头里的租户。
 *   <li><b>记录先落库，包后发出去</b>：投递记录在发包**之前**就提交了。
 *       <p>为什么：投递记录是「这件事发生过」的证据。如果先发包再落库，
 *       中间崩一次 ⇒ 对方收到了、我们没有记录 ⇒ 「投没投出去」变成一个答不出来的问题。
 *       <p>反过来：记录先落 ⇒ 发包前崩了，留下一条 PENDING 的记录，
 *       重试扫描会把它捡起来（<b>至少一次</b>）。
 *   <li><b>落库时间戳一律取 DB 时钟</b>：{@link #dbNow()} 从数据库读 {@code clock_timestamp()}
 *       再用 OffsetDateTime 绑定（{@code ?::timestamptz}）。
 *       <p>🔴 <b>JVM 墙钟一个都不落库</b>：JVM 与 DB 两个时钟域在同一行上打架，
 *       会让「写回值 ≥ 起始值」这类约束被几十微秒击穿
 *       （trade 服务 V211 修的就是同款缺陷）。同时**不用**
 *       {@code Timestamp.valueOf(LocalDateTime...)} —— 那会静默偏 −28800s。
 *   <li><b>响应码 0 是合法值</b>：它表示「**根本没拿到响应**」（连接被拒 / 超时 / 没发出去）。
 *       把它翻成 200 或 -1 都是在编造事实。
 *   <li><b>重试次数有全局上限</b>：{@code attempt_count} 数的是**全部**尝试（含人工重投），
 *       与策略里的 {@code max_attempts} 比。用满即转死信。
 *   <li><b>写入归属会话租户</b>：所有 INSERT 的 {@code tenant_id} 取自已认证会话，
 *       不接受请求参数传入；RLS 的 WITH CHECK 再兜一层。
 *   <li><b>「不存在」与「存在但属于别的租户」看起来一模一样</b>：RLS 把对方的行挡掉之后，
 *       这里只能答「不存在」—— 答 403 等于告诉调用方「这个 id 真的存在」，那本身就是泄露。
 * </ol>
 *
 * <p><b>⚠️ 本服务不装 {@code @Scheduled}</b>：多副本下自带定时器要做主节点选举与分布式锁，
 * 那是平台能力不是领域能力（与 channel V212 / V317 / identity 外部推送同一纪律）。
 * 自动重试由**外部调度**（K8s CronJob）调 {@code POST /platform/deliveries/retry-due} 触发，
 * 天然单次、可重试、可审计。
 */
@Service
public class DeliveryRecordService {

  private static final int MAX_PAGE_SIZE = 100;

  /** 默认签名头名（与 18.1.2.1 策略的默认值一致）。 */
  private static final String DEFAULT_SIGNATURE_HEADER = "X-Starcon-Signature";

  /**
   * 没有任何策略时的兜底参数。
   *
   * <p>🔴 与 {@code platform_webhook_policy} 的列默认值**逐项一致** ——
   * 「库里没配策略」不是「不重试」，而是「按默认策略重试」。
   */
  private static final EventSubscriptionPolicyRow DEFAULT_POLICY =
      new EventSubscriptionPolicyRow(
          null, null, 5, 30, new BigDecimal("2.00"), 3600, 10, 30, "HMAC_SHA256",
          DEFAULT_SIGNATURE_HEADER, null);

  private final DSLContext sql;
  private final TenantTransactions tenants;
  private final ObjectMapper json;
  private final WebhookSigningSecretCipher cipher;
  private final WebhookDeliveryExecutor executor;

  public DeliveryRecordService(
      DSLContext sql,
      TenantTransactions tenants,
      ObjectMapper json,
      @Value("${INTERNAL_KEY}") String internalKey) {
    this.sql = sql;
    this.tenants = tenants;
    this.json = json;
    this.cipher = new WebhookSigningSecretCipher(internalKey);
    this.executor = new WebhookDeliveryExecutor();
  }

  // ================================================================ 工具

  private static void check(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static void conflict(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  /** 资源不存在（**404**）：存在但属于别的租户也一样答 404（见类注释纪律 7）。 */
  private static void notFound(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static String required(String value, String label, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(!trimmed.isEmpty(), label + "不能为空");
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  private static String optional(String value, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(trimmed.length() <= max, "内容长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  /** DB 时钟：读一次 {@code clock_timestamp()} 再绑定（与 18.1.2.1 同一口径）。 */
  private OffsetDateTime dbNow() {
    return sql.fetchOne("select clock_timestamp()").get(0, OffsetDateTime.class);
  }

  /** DB 时钟的 Unix 秒（签名时间戳；同样取 DB 时钟，不取 JVM 墙钟）。 */
  private long dbEpochSeconds() {
    return sql.fetchOne("select extract(epoch from clock_timestamp())").get(0, Long.class);
  }

  /** 事务局部 GUC：租户由 AccessService 绑定，这里读的是**同一个**绑定。 */
  private UUID currentTenant() {
    var raw =
        sql.fetchOne("select current_setting('app.current_tenant', true) as t").get(0, String.class);
    check(raw != null && !raw.isBlank(), "缺少可信租户上下文");
    return UUID.fromString(raw);
  }

  private long count(String sqlText, Object... params) {
    return sql.fetchOne(sqlText, params).get(0, Long.class);
  }

  private int clampSize(int size) {
    return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
  }

  private int clampPage(int page) {
    return Math.max(page, 0);
  }

  // ================================================================ 能力声明

  /** 18.1.2.2 的能力声明：逐维说清「有没有、依据是什么、缺了会怎样」。 */
  public DeliveryRecordCapabilities capabilities() {
    var capabilities =
        List.of(
            new DeliveryRecordCapability(
                "18.1.2.2",
                "投递明细记录",
                true,
                "GET /platform/deliveries（真分页 + 真筛选）；platform_delivery_record 表，一行 = 一个"
                    + "「事件 × 订阅方」，带事件 ID、订阅方、投递状态、响应码、重试次数",
                "",
                ""),
            new DeliveryRecordCapability(
                "18.1.2.2",
                "**事件实际投递**（真实 HTTP POST，带签名头）",
                true,
                "POST /platform/deliveries/publish；WebhookDeliveryExecutor 用 java.net.http.HttpClient"
                    + " 真的 POST 到订阅的 target_url，头值 t=<时间戳>,v1=<HMAC-SHA256>；"
                    + "**不跟随重定向**，响应码如实记下（3xx 不算送达）",
                "",
                ""),
            new DeliveryRecordCapability(
                "18.1.2.2",
                "投递日志",
                true,
                "GET /platform/deliveries/{id}/attempts；platform_delivery_attempt 表，"
                    + "**每一次尝试一行**（响应码、耗时、错误、响应片段）",
                "",
                ""),
            new DeliveryRecordCapability(
                "18.1.2.2",
                "自动重试（按 18.1.2.1 的策略退避）",
                true,
                "POST /platform/deliveries/retry-due（**外部调度触发**的一次入口）；"
                    + "到期口径 next_retry_at <= clock_timestamp()；退避 = initial × multiplier^(n-1)，"
                    + "上限 max_backoff_seconds",
                "",
                ""),
            new DeliveryRecordCapability(
                "18.1.2.2",
                "死信与死信重放",
                true,
                "platform_delivery_dead_letter 表（重试耗尽才产生）；"
                    + "POST /platform/deliveries/dead-letters/{id}/replay 重放并留痕",
                "",
                ""),
            new DeliveryRecordCapability(
                "18.1.2.2",
                "手动重投",
                true,
                "POST /platform/deliveries/{id}/replay；重发**同一份报文字节**（body_bytes），"
                    + "事件 ID 不变 ⇒ 接收方按 event_id 幂等去重即可",
                "",
                ""),
            new DeliveryRecordCapability(
                "18.1.2.2",
                "重放任务",
                true,
                "platform_delivery_replay_task 表；GET /platform/deliveries/replay-tasks（真分页）",
                "",
                ""),
            new DeliveryRecordCapability(
                "18.1.2.2",
                "**上游 outbox relay**（把业务事件搬到平台）",
                false,
                "backend/libraries 下没有 event-envelope 库；平台服务也没有跨服务读 outbox 的授权通道",
                "（跨服务事件总线，不在本模块范围）",
                "事件只能由 POST /platform/deliveries/publish **手工发布**进本模块；"
                    + "业务服务自动发出的事件暂不会自动走到这里"),
            new DeliveryRecordCapability(
                "18.1.2.2",
                "**事件过滤条件求值**（JSON Schema 全量语义）",
                false,
                "18.1.2.1 存下的是 jsonb 过滤条件，本模块求值的是它的一个**明确子集**："
                    + "键 = 载荷里的点号路径，值 = 标量（精确相等）或标量数组（成员之一）",
                "（JSON Schema 全量求值器）",
                "超出该子集的过滤条件按「不匹配」处理 —— 宁可少投，不要把不该投的事件投出去"));
    var gaps =
        List.of(
            new DeliveryRecordGap(
                "18.1.2.1",
                "事件订阅（订阅配置侧）",
                "platform",
                true,
                "上游",
                "订阅端点、事件过滤、签名密钥、重试与死信策略都由 18.1.2.1 提供；"
                    + "本模块读它们，不重复配置"),
            new DeliveryRecordGap(
                "18.1.2.2",
                "投递执行与投递明细",
                "platform",
                true,
                "本模块",
                "投递执行器 + 投递明细 + 重试 + 死信 + 重放都在这里（原 18.1.2.1 端口的未接入部分）"));
    return new DeliveryRecordCapabilities(capabilities, gaps);
  }

  /** 概览：几个数（真查库）。 */
  public DeliveryRecordOverview overview(UUID tenantId) {
    return tenants.run(
        tenantId,
        () ->
            new DeliveryRecordOverview(
                count("select count(*) from platform_delivery_record"),
                count("select count(*) from platform_delivery_record where status = 'PENDING'"),
                count("select count(*) from platform_delivery_record where status = 'DELIVERED'"),
                count("select count(*) from platform_delivery_record where status = 'FAILED'"),
                count("select count(*) from platform_delivery_record where status = 'DEAD_LETTER'"),
                count("select count(*) from platform_delivery_attempt"),
                count("select count(*) from platform_delivery_replay_task"),
                count(
                    "select count(*) from platform_delivery_dead_letter where status = 'PENDING'")));
  }

  // ================================================================ 投递记录（读）

  /** 投递记录列表（**真分页 + 真筛选**）。 */
  public DeliveryRecordPage deliveries(
      UUID tenantId, String keyword, String status, UUID subscriptionId, String eventType,
      UUID eventId, int page, int size) {
    return tenants.run(tenantId, () -> deliveriesInTenant(keyword, status, subscriptionId, eventType, eventId, page, size));
  }

  private DeliveryRecordPage deliveriesInTenant(
      String keyword, String status, UUID subscriptionId, String eventType, UUID eventId, int page, int size) {
    var where = new ArrayList<String>();
    var params = new ArrayList<Object>();
    var text = keyword == null ? "" : keyword.trim();
    if (!text.isEmpty()) {
      where.add(
          "(d.event_type ilike ? or d.target_url ilike ? or s.name ilike ? or d.event_id::text ilike ?)");
      params.add("%" + text + "%");
      params.add("%" + text + "%");
      params.add("%" + text + "%");
      params.add("%" + text + "%");
    }
    var statusText = status == null ? "" : status.trim();
    if (!statusText.isEmpty()) {
      var parsed = DeliveryStatus.parse(statusText);
      check(parsed != null, "状态只能是 PENDING / DELIVERED / FAILED / DEAD_LETTER");
      where.add("d.status = ?");
      params.add(parsed.name());
    }
    if (subscriptionId != null) {
      where.add("d.subscription_id = ?");
      params.add(subscriptionId);
    }
    var eventTypeText = eventType == null ? "" : eventType.trim();
    if (!eventTypeText.isEmpty()) {
      where.add("d.event_type = ?");
      params.add(eventTypeText);
    }
    if (eventId != null) {
      where.add("d.event_id = ?");
      params.add(eventId);
    }
    var clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    var total =
        count(
            "select count(*) from platform_delivery_record d"
                + " join platform_webhook_subscription s on s.tenant_id = d.tenant_id and s.id = d.subscription_id"
                + clause,
            params.toArray());
    var limit = clampSize(size);
    var offset = clampPage(page) * limit;
    var rows =
        sql.fetch(
            "select d.id,d.event_id,d.event_type,d.schema_version,d.subscription_id,s.name,"
                + " d.app_id,d.target_url,d.status,d.status_code,d.attempt_count,d.replay_count,"
                + " d.last_error,d.next_retry_at,d.first_attempt_at,d.last_attempt_at,d.delivered_at,"
                + " d.dead_lettered_at,d.created_at"
                + " from platform_delivery_record d"
                + " join platform_webhook_subscription s on s.tenant_id = d.tenant_id and s.id = d.subscription_id"
                + clause
                + " order by d.created_at desc, d.id desc limit ? offset ?",
            concat(params, limit, offset));
    return new DeliveryRecordPage(
        rows.map(DeliveryRecordService::toDeliveryRow), clampPage(page), limit, total);
  }

  /** 投递详情：记录 + 投递日志 + 死信 + 重放任务。 */
  public DeliveryRecordDetail deliveryDetail(UUID tenantId, UUID deliveryId) {
    return tenants.run(
        tenantId,
        () -> {
          var delivery = requireDelivery(deliveryId);
          return new DeliveryRecordDetail(
              delivery,
              attemptsOf(deliveryId),
              deadLetterOf(deliveryId),
              replayTasksOf(deliveryId));
        });
  }

  /** 投递日志（每一次尝试一行）。 */
  public List<DeliveryRecordAttemptRow> attempts(UUID tenantId, UUID deliveryId) {
    return tenants.run(
        tenantId,
        () -> {
          requireDelivery(deliveryId);
          return attemptsOf(deliveryId);
        });
  }

  // ================================================================ 死信与重放任务（读）

  /** 死信列表（真分页 + 真筛选）。 */
  public DeliveryRecordDeadLetterPage deadLetters(
      UUID tenantId, String status, int page, int size) {
    return tenants.run(
        tenantId,
        () -> {
          var where = new ArrayList<String>();
          var params = new ArrayList<Object>();
          var statusText = status == null ? "" : status.trim();
          if (!statusText.isEmpty()) {
            var parsed = DeadLetterStatus.parse(statusText);
            check(parsed != null, "状态只能是 PENDING / REPLAYED / EXPIRED");
            where.add("l.status = ?");
            params.add(parsed.name());
          }
          var clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
          var total =
              count(
                  "select count(*) from platform_delivery_dead_letter l"
                      + " join platform_delivery_record d on d.tenant_id = l.tenant_id and d.id = l.delivery_id"
                      + clause,
                  params.toArray());
          var limit = clampSize(size);
          var offset = clampPage(page) * limit;
          var rows =
              sql.fetch(
                  "select l.id,l.delivery_id,d.event_id,d.event_type,s.name,d.target_url,l.reason,"
                      + " l.failed_attempt_no,l.status,l.expires_at,l.replayed_at,l.replay_task_id,"
                      + " l.created_at"
                      + " from platform_delivery_dead_letter l"
                      + " join platform_delivery_record d on d.tenant_id = l.tenant_id and d.id = l.delivery_id"
                      + " join platform_webhook_subscription s on s.tenant_id = d.tenant_id and s.id = d.subscription_id"
                      + clause
                      + " order by l.created_at desc, l.id desc limit ? offset ?",
                  concat(params, limit, offset));
          return new DeliveryRecordDeadLetterPage(
              rows.map(DeliveryRecordService::toDeadLetterRow), clampPage(page), limit, total);
        });
  }

  /** 重放任务列表（真分页）。 */
  public DeliveryRecordReplayTaskPage replayTasks(UUID tenantId, int page, int size) {
    return tenants.run(
        tenantId,
        () -> {
          var limit = clampSize(size);
          var offset = clampPage(page) * limit;
          var total =
              count(
                  "select count(*) from platform_delivery_replay_task t"
                      + " join platform_delivery_record d on d.tenant_id = t.tenant_id and d.id = t.delivery_id");
          var rows =
              sql.fetch(
                  "select t.id,t.delivery_id,d.event_id,d.event_type,s.name,t.trigger_type,t.status,"
                      + " t.result_status_code,t.result_message,t.reason,t.requested_by,t.created_at,"
                      + " t.finished_at"
                      + " from platform_delivery_replay_task t"
                      + " join platform_delivery_record d on d.tenant_id = t.tenant_id and d.id = t.delivery_id"
                      + " join platform_webhook_subscription s on s.tenant_id = d.tenant_id and s.id = d.subscription_id"
                      + " order by t.created_at desc, t.id desc limit ? offset ?",
                  limit,
                  offset);
          return new DeliveryRecordReplayTaskPage(
              rows.map(DeliveryRecordService::toReplayTaskRow), clampPage(page), limit, total);
        });
  }

  // ================================================================ 发布（真实投递）

  /**
   * 发布一个事件：**真的**投给每一个匹配的订阅。
   *
   * <p>🔴 **幂等**：同一个 {@code eventId} 对同一个订阅**只有一条投递记录**。
   * 再发一次同一个事件 ⇒ 已存在的那条**原样跳过**（不重复发包），
   * 由重试扫描负责把没投成功的那条补上。
   */
  public DeliveryRecordPublishResult publish(
      UUID tenantId, DeliveryRecordPublishInput input, AccessService.Access actor) {
    check(input != null, "缺少事件内容");
    var eventType = required(input.eventType(), "事件类型", 64);
    var payloadText = canonicalJson(input.payload(), "事件载荷");
    check(payloadText != null, "事件载荷必须是合法的 JSON 对象");
    JsonNode payload;
    try {
      payload = json.readTree(payloadText);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "事件载荷必须是合法的 JSON 对象");
    }
    UUID eventId;
    if (input.eventId() == null || input.eventId().isBlank()) {
      eventId = UUID.randomUUID();
    } else {
      var trimmed = input.eventId().trim();
      try {
        eventId = UUID.fromString(trimmed);
      } catch (IllegalArgumentException e) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "事件 ID 必须是合法的 UUID");
      }
    }

    // === 阶段一（事务）：解析匹配、落投递记录 ===
    var plan =
        tenants.run(
            tenantId,
            () -> planPublish(tenantId, eventId, eventType, input.schemaVersion(), payload, payloadText));

    // === 阶段二（无事务）：真的把包发出去 ===
    //   🔴 发包**不在**数据库事务里：连接不该被一个最长 120 秒的 HTTP 调用占住。
    var failures = new ArrayList<DeliveryRecordFailure>();
    for (var item : plan.items()) {
      var outcome = attemptDelivery(tenantId, item.deliveryId(), AttemptSource.INITIAL, null);
      if (outcome == null) {
        // 投递记录在发包前就找不到了（理论上不会发生）：如实记一笔失败，不编造成功。
        failures.add(
            new DeliveryRecordFailure(item.deliveryId(), item.subscriptionId(), "投递记录不存在", 0));
      }
    }

    // === 阶段三（事务）：回读最终状态 ===
    //   🔴 必须在**租户事务里**读：RLS 只认 app.current_tenant，
    //   事务外读就是「没有租户上下文」⇒ 一行都看不见（会误报成 404）。
    var finalRows =
        tenants.run(
            tenantId,
            () -> {
              var rows = new ArrayList<DeliveryRecordRow>();
              for (var item : plan.items()) {
                var row = requireDeliveryRow(item.deliveryId());
                rows.add(row);
                if (!"DELIVERED".equals(row.status())) {
                  failures.add(
                      new DeliveryRecordFailure(
                          item.deliveryId(), item.subscriptionId(), row.lastError(), row.statusCode()));
                }
              }
              return rows;
            });

    return new DeliveryRecordPublishResult(
        eventId,
        eventType,
        plan.schemaVersion(),
        plan.matchedCount(),
        finalRows,
        plan.skipped(),
        failures);
  }

  /** 发布计划：匹配订阅 + 建投递记录（**在事务里**）。 */
  private PublishPlan planPublish(
      UUID tenantId,
      UUID eventId,
      String eventType,
      Integer requestedVersion,
      JsonNode payload,
      String payloadText) {
    var version = resolveSchemaVersion(eventType, requestedVersion);
    var now = dbNow();
    var occurredAt = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    var bodyBytes = buildBody(eventId, eventType, version, payload, occurredAt);

    // 匹配的订阅（事件类型 + 版本 + 过滤条件 + 订阅生效）。
    var candidates =
        sql.fetch(
            "select s.id,s.app_id,s.name,s.target_url,s.status,e.filter::text as filter"
                + " from platform_webhook_subscription s"
                + " join platform_webhook_subscription_event e"
                + "   on e.tenant_id = s.tenant_id and e.subscription_id = s.id"
                + " where e.event_type = ? and e.schema_version = ? and e.enabled = true"
                + " order by s.name, s.id",
            eventType,
            version);

    var items = new ArrayList<PublishItem>();
    var skipped = new ArrayList<DeliveryRecordSkip>();
    var matched = 0;
    for (var candidate : candidates) {
      var subscriptionId = candidate.get("id", UUID.class);
      var subscriptionName = candidate.get("name", String.class);
      var status = candidate.get("status", String.class);
      if (!"ACTIVE".equals(status)) {
        skipped.add(
            new DeliveryRecordSkip(
                subscriptionId,
                subscriptionName,
                "ACTIVE".equals(status) ? "" : "订阅当前是 " + status + "（不投递）"));
        continue;
      }
      var filterText = candidate.get("filter", String.class);
      if (!filterMatches(filterText, payload)) {
        skipped.add(
            new DeliveryRecordSkip(subscriptionId, subscriptionName, "事件过滤条件不匹配"));
        continue;
      }
      matched++;

      // 幂等：同一事件对同一订阅只登记一条投递记录。
      var existing =
          sql.fetchOne(
              "select id from platform_delivery_record where event_id = ? and subscription_id = ?",
              eventId,
              subscriptionId);
      if (existing != null) {
        skipped.add(
            new DeliveryRecordSkip(
                subscriptionId,
                subscriptionName,
                "同一事件已投递给该订阅（幂等跳过）"));
        continue;
      }

      var deliveryId =
          insertDeliveryRecord(
              eventId,
              eventType,
              version,
              subscriptionId,
              candidate.get("app_id", UUID.class),
              candidate.get("target_url", String.class),
              payloadText,
              bodyBytes,
              now);
      items.add(new PublishItem(deliveryId, subscriptionId, subscriptionName));
    }
    return new PublishPlan(version, matched, items, skipped);
  }

  /**
   * 落一条投递记录（**新建**，状态 PENDING、还没发过包）。
   *
   * <p>🔴 **记录先落库，包后发**：这条纪律的落点就在这里 ——
   * 投递记录是「这件事发生过」的证据，先发包再落库，中间崩一次 ⇒
   * 对方收到了、我们没有记录，「投没投出去」变成一个答不出来的问题。
   *
   * @param bodyBytes **实际发出去的报文字节**（Base64 落库）：签名盖的是原始字节，
   *     重放时若重新序列化，字节序/空格可能变 ⇒ 验签对不上
   */
  private UUID insertDeliveryRecord(
      UUID eventId,
      String eventType,
      int version,
      UUID subscriptionId,
      UUID appId,
      String targetUrl,
      String payloadText,
      byte[] bodyBytes,
      OffsetDateTime now) {
    var deliveryId = UUID.randomUUID();
    sql.execute(
        "insert into platform_delivery_record(id,tenant_id,event_id,event_type,schema_version,"
            + " subscription_id,app_id,target_url,status,status_code,attempt_count,replay_count,"
            + " event_payload,body_bytes,last_error,next_retry_at,first_attempt_at,last_attempt_at,"
            + " delivered_at,dead_lettered_at,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,'PENDING',0,0,0,?::jsonb,?,'',null,null,null,null,null,"
            + " ?::timestamptz,?::timestamptz)",
        deliveryId,
        currentTenant(),
        eventId,
        eventType,
        version,
        subscriptionId,
        appId,
        targetUrl,
        payloadText,
        Base64.getEncoder().encodeToString(bodyBytes),
        now,
        now);
    return deliveryId;
  }

  /**
   * 「投一次」——**对着一个已存在的订阅**投一个事件。
   *
   * <p>这是 18.1.2.1 的 {@code POST /platform/webhooks/subscriptions/{id}/deliver}
   * 背后的实现（{@link WebhookDeliveryPort} 调它）。
   * 与 {@code POST /platform/deliveries/publish} 的区别只有一个：
   * <b>订阅是指定，不是按事件类型匹配出来的</b>。
   * 其余完全同一条路 —— 同一个报文信封、同一段签名代码、同一套重试与死信策略、
   * 同一张投递记录表。
   *
   * <p>🔴 <b>事件 ID</b>：载荷里带了 {@code event_id} 且是合法 UUID 就用它
   * （这样调用方能自己控制幂等键）；否则平台生成一个。
   */
  public DeliveryRecordDirectResult deliverToSubscription(
      UUID tenantId,
      UUID subscriptionId,
      String eventType,
      int schemaVersion,
      byte[] payloadBytes) {
    check(payloadBytes != null, "缺少事件载荷");
    var payloadText = canonicalJson(new String(payloadBytes, StandardCharsets.UTF_8), "事件载荷");
    check(payloadText != null, "事件载荷必须是合法的 JSON 对象");
    JsonNode payload;
    try {
      payload = json.readTree(payloadText);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "事件载荷必须是合法的 JSON 对象");
    }

    var plan =
        tenants.run(
            tenantId,
            () -> planDirect(subscriptionId, eventType, schemaVersion, payload, payloadText));
    if (plan.skipped()) {
      return new DeliveryRecordDirectResult(false, false, 0, plan.skipReason(), null, true);
    }

    var outcome = attemptDelivery(tenantId, plan.deliveryId(), AttemptSource.INITIAL, null);
    if (outcome == null) {
      return new DeliveryRecordDirectResult(false, false, 0, "投递记录不存在", null, false);
    }
    return new DeliveryRecordDirectResult(
        outcome.connected(),
        outcome.sent(),
        outcome.statusCode(),
        outcome.reason(),
        plan.deliveryId(),
        false);
  }

  /** 「投一次」的计划：校验订阅 + 解析版本 + 落投递记录（**在事务里**）。 */
  private DirectPlan planDirect(
      UUID subscriptionId,
      String eventType,
      int requestedVersion,
      JsonNode payload,
      String payloadText) {
    var subscription =
        sql.fetchOne(
            "select id,app_id,target_url,status from platform_webhook_subscription where id = ?",
            subscriptionId);
    notFound(subscription != null, "订阅不存在");
    var status = subscription.get("status", String.class);
    conflict("ACTIVE".equals(status), "订阅当前是 " + status + "（不投递）。");

    // schema 版本：未指定（<= 0）时取注册表里该事件类型最大的 ACTIVE 版本。
    var version = resolveSchemaVersion(eventType, requestedVersion <= 0 ? null : requestedVersion);

    // ⚠️ **事件过滤对所有投递路径一视同仁**：订阅方声明了「只要 sku=X 的事件」，
    //   那么「按订阅投递」这条路径也不能绕过它 —— 否则同一个订阅在两条路径上
    //   收到的东西不一样，那条声明就等于没写。
    //   （没有为这一版 schema 声明过滤 ⇒ 不做过滤，与 publish 路径同口径。）
    var filterRow =
        sql.fetchOne(
            "select filter::text as filter from platform_webhook_subscription_event"
                + " where subscription_id = ? and event_type = ? and schema_version = ? and enabled = true",
            subscriptionId,
            eventType,
            version);
    if (filterRow != null && !filterMatches(filterRow.get("filter", String.class), payload)) {
      return DirectPlan.skip("事件过滤条件不匹配");
    }

    var eventId = resolveEventId(payload);
    var now = dbNow();
    var occurredAt = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    var bodyBytes = buildBody(eventId, eventType, version, payload, occurredAt);

    // 幂等：同一事件对同一订阅**只有一条**投递记录（表上有 UNIQUE(tenant_id, event_id, subscription_id)）。
    var existing =
        sql.fetchOne(
            "select id from platform_delivery_record where event_id = ? and subscription_id = ?",
            eventId,
            subscriptionId);
    if (existing != null) {
      return DirectPlan.skip("同一事件已投递给该订阅（幂等跳过，不重复发包）");
    }

    var deliveryId =
        insertDeliveryRecord(
            eventId,
            eventType,
            version,
            subscriptionId,
            subscription.get("app_id", UUID.class),
            subscription.get("target_url", String.class),
            payloadText,
            bodyBytes,
            now);
    return DirectPlan.of(deliveryId);
  }

  /** 事件 ID：载荷里带了 {@code event_id} 且是合法 UUID 就用它，否则平台生成。 */
  private static UUID resolveEventId(JsonNode payload) {
    var node = payload == null ? null : payload.get("event_id");
    if (node != null && node.isTextual()) {
      var raw = node.asText().trim();
      try {
        return UUID.fromString(raw);
      } catch (IllegalArgumentException e) {
        // ⚠️ 不是合法 UUID ⇒ 平台自己生成：**不编造一个假的 ID**给对方去重。
      }
    }
    return UUID.randomUUID();
  }

  /** 「投一次」的计划结果。 */
  private record DirectPlan(UUID deliveryId, boolean skipped, String skipReason) {

    static DirectPlan of(UUID deliveryId) {
      return new DirectPlan(deliveryId, false, "");
    }

    static DirectPlan skip(String reason) {
      return new DirectPlan(null, true, reason);
    }
  }

  private record PublishPlan(
      int schemaVersion,
      int matchedCount,
      List<PublishItem> items,
      List<DeliveryRecordSkip> skipped) {}

  private record PublishItem(UUID deliveryId, UUID subscriptionId, String subscriptionName) {}

  // ================================================================ 投递尝试（真实发包）

  /**
   * 投一次：<b>记录先在</b>，包<b>后发</b>，结果<b>再落</b>。
   *
   * <p>三个阶段的边界就是纪律 2 的落点：
   * 阶段一在事务里（读投递记录 + 解析策略 + 解密签名密钥），
   * 阶段二**不在**事务里（HTTP 调用），
   * 阶段三在事务里（写尝试 + 更新投递状态 + 必要时转死信）。
   */
  private DeliveryRecordAttemptOutcome attemptDelivery(
      UUID tenantId, UUID deliveryId, AttemptSource source, UUID replayTaskId) {
    var plan = tenants.run(tenantId, () -> planAttempt(deliveryId));
    if (plan == null) return null;

    WebhookDeliveryExecutor.ExecutionResult result;
    if (plan.permanentFailureReason() != null) {
      // 🔴 **永久性失败时一个包都不发**：没有生效中的签名密钥 ⇒ 我们**签不了名**，
      //   而发一个没有签名的包出去，比不发更糟 ——
      //   对方的验签会失败，而对方分不清「这个事件没带签名」与「这个事件是伪造的」。
      result =
          new WebhookDeliveryExecutor.ExecutionResult(
              false, false, 0, plan.permanentFailureReason(), 0, "");
    } else {
      result =
          executor.send(
              plan.targetUrl(),
              plan.bodyBytes(),
              plan.signatureHeader(),
              plan.signatureValue(),
              plan.policy().requestTimeoutSeconds(),
              Map.of(
                  "X-Starcon-Event-Id", plan.eventId().toString(),
                  "X-Starcon-Event-Type", plan.eventType()));
    }

    return tenants.run(
        tenantId,
        () -> {
          recordOutcome(plan, source, replayTaskId, result);
          return new DeliveryRecordAttemptOutcome(
              result.connected(),
              result.sent(),
              result.succeeded(),
              result.statusCode(),
              result.reason(),
              plan.attemptNo());
        });
  }

  /**
   * 生效的重试与死信策略（**订阅级优先，回退租户级**）。
   *
   * <p>⚠️ 口径与 18.1.2.1 的 {@code EventSubscriptionService.effectivePolicy} **逐字一致**
   *   （同一个 {@code platform_webhook_policy} 表、同样的 where 与 order by）。
   *
   * <p>🔴 **刻意在这里自己查，而不是注入 18.1.2.1 的服务类**：18.1.2.2 只需要**读**它配的策略，
   *   不需要它的服务；而一旦注入，两个服务类会形成
   *   「18.1.2.1 服务 → 投递端口 → 18.1.2.2 服务 → 18.1.2.1 服务」的**构造器循环依赖**
   *   （Spring Boot 2.6 起默认拒绝循环依赖 ⇒ 整个应用上下文起不来）。
   *   同服务内读同一张表不违反模块边界纪律（跨服务才不许读对方的表）。
   *
   * @return 生效的策略；一条都没有时返回 {@code null}（调用方落到默认策略）
   */
  private EventSubscriptionPolicyRow effectivePolicy(UUID subscriptionId) {
    var row =
        sql.fetchOne(
            "select id,subscription_id,max_attempts,initial_backoff_seconds,backoff_multiplier,"
                + " max_backoff_seconds,request_timeout_seconds,dead_letter_retention_days,"
                + " signature_algorithm,signature_header,updated_at"
                + " from platform_webhook_policy"
                + " where subscription_id = ? or subscription_id is null"
                + " order by (subscription_id is null) limit 1",
            subscriptionId);
    if (row == null) return null;
    return new EventSubscriptionPolicyRow(
        row.get("id", UUID.class),
        row.get("subscription_id", UUID.class),
        row.get("max_attempts", Integer.class),
        row.get("initial_backoff_seconds", Integer.class),
        row.get("backoff_multiplier", BigDecimal.class),
        row.get("max_backoff_seconds", Integer.class),
        row.get("request_timeout_seconds", Integer.class),
        row.get("dead_letter_retention_days", Integer.class),
        row.get("signature_algorithm", String.class),
        row.get("signature_header", String.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  /** 一次尝试的计划：目标、字节、签名、策略、序号。 */
  private AttemptPlan planAttempt(UUID deliveryId) {
    var row =
        sql.fetchOne(
            "select d.id,d.event_id,d.event_type,d.subscription_id,d.target_url,d.body_bytes,"
                + " d.attempt_count,d.event_payload::text as event_payload"
                + " from platform_delivery_record d where d.id = ?",
            deliveryId);
    if (row == null) return null;

    var subscriptionId = row.get("subscription_id", UUID.class);
    var effective = effectivePolicy(subscriptionId);
    if (effective == null) effective = DEFAULT_POLICY;

    var secretRow =
        sql.fetchOne(
            "select secret_ciphertext from platform_webhook_signing_secret"
                + " where subscription_id = ? and status = 'ACTIVE'",
            subscriptionId);
    if (secretRow == null) {
      // 没有生效中的签名密钥 ⇒ **永久性失败**（不是网络问题，重试也不会好）。
      return AttemptPlan.permanentFailure(
          row.get("id", UUID.class),
          row.get("event_id", UUID.class),
          row.get("event_type", String.class),
          row.get("target_url", String.class),
          row.get("body_bytes", String.class),
          row.get("attempt_count", Integer.class) + 1,
          effective,
          "该订阅没有生效中的签名密钥，无法签名投递。请先生成或轮换签名密钥。");
    }

    var plain = cipher.open(secretRow.get("secret_ciphertext", String.class));
    var bodyBytes = Base64.getDecoder().decode(row.get("body_bytes", String.class));
    var timestamp = Long.toString(dbEpochSeconds());
    var signature = WebhookSigningSecretCipher.sign(plain, timestamp, bodyBytes);
    // 自检：算出来的签名必须与自己再算一遍相等（防「算签名」这段本身写错）。
    check(
        WebhookSigningSecretCipher.matches(signature, signature),
        "签名自检失败：签名计算结果与自身不一致");
    return AttemptPlan.ready(
        row.get("id", UUID.class),
        row.get("event_id", UUID.class),
        row.get("event_type", String.class),
        row.get("target_url", String.class),
        bodyBytes,
        row.get("attempt_count", Integer.class) + 1,
        effective,
        timestamp,
        effective.signatureHeader(),
        "t=" + timestamp + ",v1=" + signature);
  }

  private record AttemptPlan(
      UUID deliveryId,
      UUID eventId,
      String eventType,
      String targetUrl,
      byte[] bodyBytes,
      int attemptNo,
      EventSubscriptionPolicyRow policy,
      String signatureHeader,
      String signatureValue,
      String permanentFailureReason) {

    static AttemptPlan ready(
        UUID deliveryId,
        UUID eventId,
        String eventType,
        String targetUrl,
        byte[] bodyBytes,
        int attemptNo,
        EventSubscriptionPolicyRow policy,
        String timestamp,
        String signatureHeader,
        String signatureValue) {
      return new AttemptPlan(
          deliveryId,
          eventId,
          eventType,
          targetUrl,
          bodyBytes,
          attemptNo,
          policy,
          signatureHeader,
          signatureValue,
          null);
    }

    static AttemptPlan permanentFailure(
        UUID deliveryId,
        UUID eventId,
        String eventType,
        String targetUrl,
        String bodyBytes,
        int attemptNo,
        EventSubscriptionPolicyRow policy,
        String reason) {
      return new AttemptPlan(
          deliveryId,
          eventId,
          eventType,
          targetUrl,
          Base64.getDecoder().decode(bodyBytes),
          attemptNo,
          policy,
          null,
          null,
          reason);
    }
  }

  /**
   * 一次尝试的结果。
   *
   * <p>🔴 {@code connected} / {@code sent} / {@code succeeded} 三者**不能互相替代**：
   * 连不上（connected=false）⇒ 一个包都没发（sent=false）；
   * 发出去但对方回 500（sent=true）⇒ 也不算送达（succeeded=false）。
   */
  private record DeliveryRecordAttemptOutcome(
      boolean connected,
      boolean sent,
      boolean succeeded,
      int statusCode,
      String reason,
      int attemptNo) {}

  /**
   * 把一次尝试的结果**落库**：写投递日志 + 更新投递记录 + 必要时转死信。
   *
   * <p>🔴 重试预算按 {@code attempt_count}（全部尝试）与 {@code max_attempts} 比：
   * 用满即转死信。人工重投**也**计入 attempt_count —— 它确实是一次尝试。
   */
  private void recordOutcome(
      AttemptPlan plan, AttemptSource source, UUID replayTaskId, WebhookDeliveryExecutor.ExecutionResult result) {
    var now = dbNow();
    var attemptId = UUID.randomUUID();
    var succeeded = result.succeeded();
    var reason = plan.permanentFailureReason() != null ? plan.permanentFailureReason() : result.reason();

    sql.execute(
        "insert into platform_delivery_attempt(id,tenant_id,delivery_id,attempt_no,status_code,"
            + " success,attempt_source,duration_ms,error,response_snippet,replay_task_id,occurred_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
        attemptId,
        currentTenant(),
        plan.deliveryId(),
        plan.attemptNo(),
        result.statusCode(),
        succeeded,
        source.name(),
        result.durationMs(),
        reason == null ? "" : reason,
        result.responseSnippet() == null ? "" : result.responseSnippet(),
        replayTaskId,
        now);

    if (succeeded) {
      sql.execute(
          "update platform_delivery_record set status = 'DELIVERED', status_code = ?, attempt_count = ?,"
              + " last_error = '', next_retry_at = null, first_attempt_at = coalesce(first_attempt_at, ?::timestamptz),"
              + " last_attempt_at = ?::timestamptz, delivered_at = coalesce(delivered_at, ?::timestamptz),"
              + " updated_at = ?::timestamptz where id = ?",
          result.statusCode(),
          plan.attemptNo(),
          now,
          now,
          now,
          now,
          plan.deliveryId());
      return;
    }

    // 失败：永久性错误不重试；其余按策略退避重排；用满次数转死信。
    var permanent = plan.permanentFailureReason() != null;
    var exhausted = !permanent && plan.attemptNo() >= plan.policy().maxAttempts();
    if (permanent) {
      sql.execute(
          "update platform_delivery_record set status = 'FAILED', status_code = ?, attempt_count = ?,"
              + " last_error = ?, next_retry_at = null, first_attempt_at = coalesce(first_attempt_at, ?::timestamptz),"
              + " last_attempt_at = ?::timestamptz, updated_at = ?::timestamptz where id = ?",
          result.statusCode(),
          plan.attemptNo(),
          reason,
          now,
          now,
          now,
          plan.deliveryId());
      return;
    }
    if (exhausted) {
      sql.execute(
          "update platform_delivery_record set status = 'DEAD_LETTER', status_code = ?, attempt_count = ?,"
              + " last_error = ?, next_retry_at = null, first_attempt_at = coalesce(first_attempt_at, ?::timestamptz),"
              + " last_attempt_at = ?::timestamptz, dead_lettered_at = ?::timestamptz, updated_at = ?::timestamptz"
              + " where id = ?",
          result.statusCode(),
          plan.attemptNo(),
          reason,
          now,
          now,
          now,
          now,
          plan.deliveryId());
      upsertDeadLetter(plan, reason, now);
      return;
    }
    var backoff = backoffSeconds(plan.policy(), plan.attemptNo());
    sql.execute(
        "update platform_delivery_record set status = 'PENDING', status_code = ?, attempt_count = ?,"
            + " last_error = ?, next_retry_at = ?::timestamptz + ?::integer * interval '1 second',"
            + " first_attempt_at = coalesce(first_attempt_at, ?::timestamptz), last_attempt_at = ?::timestamptz,"
            + " updated_at = ?::timestamptz where id = ?",
        result.statusCode(),
        plan.attemptNo(),
        reason,
        now,
        backoff,
        now,
        now,
        now,
        plan.deliveryId());
  }

  /**
   * 死信 upsert。
   *
   * <p>🔴 一个投递**最多一条**死信（表上有 UNIQUE(tenant_id, delivery_id)）：
   * 死信重放后再次耗尽时，**改回** PENDING 而不是插第二条 ——
   * 否则死信箱里会出现同一个投递的多条死信，「还剩几条要重放」就答不对了。
   */
  private void upsertDeadLetter(AttemptPlan plan, String reason, OffsetDateTime now) {
    sql.execute(
        "insert into platform_delivery_dead_letter(id,tenant_id,delivery_id,reason,failed_attempt_no,"
            + " status,expires_at,replayed_at,replay_task_id,created_at)"
            + " values(?,?,?,?,?,'PENDING',?::timestamptz + ?::integer * interval '1 day',null,null,?::timestamptz)"
            + " on conflict (tenant_id, delivery_id) do update set"
            + " reason = excluded.reason, failed_attempt_no = excluded.failed_attempt_no, status = 'PENDING',"
            + " expires_at = excluded.expires_at, replayed_at = null, replay_task_id = null",
        UUID.randomUUID(),
        currentTenant(),
        plan.deliveryId(),
        reason == null ? "" : reason,
        plan.attemptNo(),
        now,
        plan.policy().deadLetterRetentionDays(),
        now);
  }

  /** 退避秒数：initial × multiplier^(n-1)，上限 max_backoff（**用 BigDecimal，禁浮点**）。 */
  static int backoffSeconds(EventSubscriptionPolicyRow policy, int attemptNo) {
    var multiplier = policy.backoffMultiplier();
    if (multiplier == null) multiplier = new BigDecimal("2.00");
    var exponent = Math.max(0, attemptNo - 1);
    var raw =
        new BigDecimal(policy.initialBackoffSeconds())
            .multiply(multiplier.pow(exponent))
            .setScale(0, RoundingMode.HALF_UP);
    var capped = raw.min(BigDecimal.valueOf(policy.maxBackoffSeconds()));
    return Math.max(1, capped.intValue());
  }

  // ================================================================ 手动重投 / 死信重放

  /** 手动重投：重发**同一份报文字节**，事件 ID 不变。 */
  public DeliveryRecordReplayResult replay(
      UUID tenantId, UUID deliveryId, DeliveryRecordReplayInput input, AccessService.Access actor) {
    var reason = optional(input == null ? null : input.reason(), 500);
    var taskId = UUID.randomUUID();
    tenants.run(
        tenantId,
        () -> {
          requireDelivery(deliveryId);
          sql.execute(
              "insert into platform_delivery_replay_task(id,tenant_id,delivery_id,trigger_type,status,"
                  + " result_status_code,result_message,reason,requested_by,created_at,finished_at)"
                  + " values(?,?,?,'MANUAL','PENDING',0,'',?,?,?::timestamptz,null)",
              taskId,
              currentTenant(),
              deliveryId,
              reason,
              actor.userId(),
              dbNow());
          return null;
        });

    var outcome = attemptDelivery(tenantId, deliveryId, AttemptSource.MANUAL, taskId);
    var succeeded = outcome != null && outcome.succeeded();

    return tenants.run(
        tenantId,
        () -> {
          var now = dbNow();
          sql.execute(
              "update platform_delivery_replay_task set status = ?, result_status_code = ?,"
                  + " result_message = ?, finished_at = ?::timestamptz where id = ?",
              succeeded ? "SUCCEEDED" : "FAILED",
              outcome == null ? 0 : outcome.statusCode(),
              outcome == null ? "投递记录不存在" : outcome.reason(),
              now,
              taskId);
          sql.execute(
              "update platform_delivery_record set replay_count = replay_count + 1, updated_at = ?::timestamptz"
                  + " where id = ?",
              now,
              deliveryId);
          var task = requireReplayTask(taskId);
          var delivery = requireDelivery(deliveryId);
          var attempts = attemptsOf(deliveryId);
          var last = attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
          return new DeliveryRecordReplayResult(task, delivery, last);
        });
  }

  /** 死信重放：把一条死信重新投一次（事件 ID 不变 ⇒ 接收方按 event_id 幂等去重）。 */
  public DeliveryRecordReplayResult replayDeadLetter(
      UUID tenantId, UUID deadLetterId, DeliveryRecordReplayInput input, AccessService.Access actor) {
    var reason = optional(input == null ? null : input.reason(), 500);
    var deliveryId =
        tenants.run(
            tenantId,
            () -> {
              var row =
                  sql.fetchOne(
                      "select delivery_id,status from platform_delivery_dead_letter where id = ?",
                      deadLetterId);
              notFound(row != null, "死信不存在");
              var status = row.get("status", String.class);
              conflict(
                  "PENDING".equals(status),
                  "这条死信已经" + ("REPLAYED".equals(status) ? "重放过" : "过期") + "，不能重复重放。");
              return row.get("delivery_id", UUID.class);
            });

    var taskId = UUID.randomUUID();
    tenants.run(
        tenantId,
        () -> {
          sql.execute(
              "insert into platform_delivery_replay_task(id,tenant_id,delivery_id,trigger_type,status,"
                  + " result_status_code,result_message,reason,requested_by,created_at,finished_at)"
                  + " values(?,?,?,'DEAD_LETTER','PENDING',0,'',?,?,?::timestamptz,null)",
              taskId,
              currentTenant(),
              deliveryId,
              reason,
              actor.userId(),
              dbNow());
          return null;
        });

    var outcome = attemptDelivery(tenantId, deliveryId, AttemptSource.DEAD_LETTER_REPLAY, taskId);
    var succeeded = outcome != null && outcome.succeeded();

    return tenants.run(
        tenantId,
        () -> {
          var now = dbNow();
          sql.execute(
              "update platform_delivery_replay_task set status = ?, result_status_code = ?,"
                  + " result_message = ?, finished_at = ?::timestamptz where id = ?",
              succeeded ? "SUCCEEDED" : "FAILED",
              outcome == null ? 0 : outcome.statusCode(),
              outcome == null ? "投递记录不存在" : outcome.reason(),
              now,
              taskId);
          sql.execute(
              "update platform_delivery_dead_letter set status = 'REPLAYED', replayed_at = ?::timestamptz,"
                  + " replay_task_id = ? where id = ?",
              now,
              taskId,
              deadLetterId);
          sql.execute(
              "update platform_delivery_record set replay_count = replay_count + 1, updated_at = ?::timestamptz"
                  + " where id = ?",
              now,
              deliveryId);
          var task = requireReplayTask(taskId);
          var delivery = requireDelivery(deliveryId);
          var attempts = attemptsOf(deliveryId);
          var last = attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
          return new DeliveryRecordReplayResult(task, delivery, last);
        });
  }

  // ================================================================ 到期重试扫描（外部调度触发）

  /**
   * 到期重试扫描（**外部调度**触发的一次入口）。
   *
   * <p>🔴 <b>本服务不装 @Scheduled</b>：多副本下自带定时器要做主节点选举与分布式锁，
   * 那是平台能力不是领域能力（与 channel V212 / V317 / identity 外部推送同一纪律）。
   * 外部调度（K8s CronJob）按间隔调一次本入口，天然单次、可重试、可审计。
   *
   * <p>⚠️ **逐租户**：租户来自已认证会话（不是客户端头）⇒ RLS 照常生效。
   */
  public DeliveryRecordRetrySweep retryDue(UUID tenantId, int limit) {
    var size = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
    var due =
        tenants.run(
            tenantId,
            () ->
                sql.fetch(
                    "select id from platform_delivery_record"
                        + " where status = 'PENDING' and next_retry_at is not null"
                        + " and next_retry_at <= clock_timestamp()"
                        + " order by next_retry_at, id limit ?",
                    size)
                    .map(r -> r.get("id", UUID.class)));

    int retried = 0;
    int delivered = 0;
    int failed = 0;
    int deadLettered = 0;
    for (var deliveryId : due) {
      var outcome = attemptDelivery(tenantId, deliveryId, AttemptSource.RETRY, null);
      if (outcome == null) continue;
      retried++;
      if (outcome.succeeded()) {
        delivered++;
      } else {
        failed++;
        var status = tenants.run(tenantId, () -> deliveryStatus(deliveryId));
        if ("DEAD_LETTER".equals(status)) deadLettered++;
      }
    }
    return new DeliveryRecordRetrySweep(due.size(), retried, delivered, failed, deadLettered);
  }

  // ================================================================ 内部工具

  /** 事件 schema 版本：未指定时取注册表里该事件类型最大的 ACTIVE 版本（与 18.1.2.1 同一口径）。 */
  private int resolveSchemaVersion(String eventType, Integer requested) {
    if (requested != null) {
      var exists =
          count(
              "select count(*) from platform_webhook_event_type"
                  + " where event_type = ? and schema_version = ?",
              eventType,
              requested);
      check(
          exists > 0,
          "事件类型 "
              + eventType
              + " 的第 "
              + requested
              + " 版**没有登记**。请先在「可订阅事件列表」里登记它，再发布 ——"
              + "发布一份不存在的 schema，等于让对接方按一份不存在的契约写代码。");
      return requested;
    }
    var row =
        sql.fetchOne(
            "select max(schema_version) from platform_webhook_event_type"
                + " where event_type = ? and status = 'ACTIVE'",
            eventType);
    var latest = row == null ? null : row.get(0, Integer.class);
    check(
        latest != null,
        "没有找到事件类型 " + eventType + " 的可用版本。请先在「可订阅事件列表」里登记它。");
    return latest;
  }

  /**
   * 事件过滤条件求值。
   *
   * <p><b>语义（本模块写死的子集，18.1.2.1 只存不算）</b>：
   *
   * <ul>
   *   <li>空对象 {@code {}} ⇒ **不过滤**（该事件的全部实例都收）。
   *   <li>键 = 载荷里的**点号路径**（如 {@code "sku.code"}）；值 =
   *       <b>标量</b>（精确相等）或<b>标量数组</b>（成员之一）。
   *   <li>载荷里**没有**这个路径 ⇒ **不匹配**（订阅方要的东西不在事件里）。
   *   <li>值是对象或对象数组（超出子集）⇒ **不匹配**，并且订阅被跳过并写明原因 ——
   *       宁可少投，也不要把不该投的事件投出去。
   * </ul>
   */
  boolean filterMatches(String filterText, JsonNode payload) {
    if (filterText == null || filterText.isBlank()) return true;
    JsonNode filter;
    try {
      filter = json.readTree(filterText);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return false;
    }
    if (filter == null || !filter.isObject() || filter.isEmpty()) return true;
    var fields = filter.fields();
    while (fields.hasNext()) {
      var entry = fields.next();
      var actual = resolvePath(payload, entry.getKey());
      if (actual == null || actual.isMissingNode()) return false;
      var expected = entry.getValue();
      if (expected.isArray()) {
        var matched = false;
        for (var item : expected) {
          if (scalarEquals(actual, item)) {
            matched = true;
            break;
          }
        }
        if (!matched) return false;
      } else if (expected.isValueNode()) {
        if (!scalarEquals(actual, expected)) return false;
      } else {
        // 超出子集（对象 / 数组-of-对象）⇒ 不匹配。
        return false;
      }
    }
    return true;
  }

  /** 按点号路径取值（找不到返回 missing node）。 */
  private static JsonNode resolvePath(JsonNode payload, String path) {
    var node = payload;
    for (var segment : path.split("\\.")) {
      if (node == null || !node.isObject()) return null;
      node = node.get(segment);
    }
    return node;
  }

  /** 标量相等（按规范文本比：字符串取文本，数字去尾零，布尔取 true/false）。 */
  private static boolean scalarEquals(JsonNode actual, JsonNode expected) {
    return scalarText(actual).equals(scalarText(expected));
  }

  private static String scalarText(JsonNode node) {
    if (node == null || node.isNull()) return "null";
    if (node.isBoolean()) return Boolean.toString(node.booleanValue());
    if (node.isNumber()) return node.decimalValue().stripTrailingZeros().toPlainString();
    return node.asText();
  }

  /** 事件载荷规范化：必须是合法的 JSON 对象。 */
  private String canonicalJson(String raw, String label) {
    var canonical = canonicalJsonObject(raw, json);
    check(canonical != null, label + "必须是合法的 JSON 对象");
    return canonical;
  }

  /**
   * 构造**实际发出去的报文字节**。
   *
   * <p>🔴 报文是一个**信封**：
   *
   * <pre>
   * {"event_id":"…","event_type":"…","schema_version":1,"occurred_at":"…","data":{…}}
   * </pre>
   *
   * 理由：接收方要按 {@code event_id} 幂等去重 ⇒ 事件 ID 必须在**报文里**
   * （不能只放在库里），否则对方根本不知道该按什么去重。
   */
  private byte[] buildBody(
      UUID eventId, String eventType, int schemaVersion, JsonNode data, String occurredAt) {
    var envelope = new LinkedHashMap<String, Object>();
    envelope.put("event_id", eventId.toString());
    envelope.put("event_type", eventType);
    envelope.put("schema_version", schemaVersion);
    envelope.put("occurred_at", occurredAt);
    envelope.put("data", data);
    try {
      return json.writeValueAsBytes(envelope);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("事件报文序列化失败", e);
    }
  }

  /** 取一条投递记录（不存在 ⇒ 404）。 */
  private DeliveryRecordRow requireDelivery(UUID deliveryId) {
    return requireDeliveryRow(deliveryId);
  }

  private DeliveryRecordRow requireDeliveryRow(UUID deliveryId) {
    var row =
        sql.fetchOne(
            "select d.id,d.event_id,d.event_type,d.schema_version,d.subscription_id,s.name,"
                + " d.app_id,d.target_url,d.status,d.status_code,d.attempt_count,d.replay_count,"
                + " d.last_error,d.next_retry_at,d.first_attempt_at,d.last_attempt_at,d.delivered_at,"
                + " d.dead_lettered_at,d.created_at"
                + " from platform_delivery_record d"
                + " join platform_webhook_subscription s on s.tenant_id = d.tenant_id and s.id = d.subscription_id"
                + " where d.id = ?",
            deliveryId);
    notFound(row != null, "投递记录不存在");
    return toDeliveryRow(row);
  }

  private String deliveryStatus(UUID deliveryId) {
    var row = sql.fetchOne("select status from platform_delivery_record where id = ?", deliveryId);
    return row == null ? null : row.get(0, String.class);
  }

  private List<DeliveryRecordAttemptRow> attemptsOf(UUID deliveryId) {
    return sql.fetch(
            "select id,delivery_id,attempt_no,status_code,success,attempt_source,duration_ms,"
                + " error,response_snippet,replay_task_id,occurred_at"
                + " from platform_delivery_attempt where delivery_id = ?"
                + " order by attempt_no",
            deliveryId)
        .map(DeliveryRecordService::toAttemptRow);
  }

  private DeliveryRecordDeadLetterRow deadLetterOf(UUID deliveryId) {
    var row =
        sql.fetchOne(
            "select l.id,l.delivery_id,d.event_id,d.event_type,s.name,d.target_url,l.reason,"
                + " l.failed_attempt_no,l.status,l.expires_at,l.replayed_at,l.replay_task_id,l.created_at"
                + " from platform_delivery_dead_letter l"
                + " join platform_delivery_record d on d.tenant_id = l.tenant_id and d.id = l.delivery_id"
                + " join platform_webhook_subscription s on s.tenant_id = d.tenant_id and s.id = d.subscription_id"
                + " where l.delivery_id = ?",
            deliveryId);
    return row == null ? null : toDeadLetterRow(row);
  }

  private List<DeliveryRecordReplayTaskRow> replayTasksOf(UUID deliveryId) {
    return sql.fetch(
            "select t.id,t.delivery_id,d.event_id,d.event_type,s.name,t.trigger_type,t.status,"
                + " t.result_status_code,t.result_message,t.reason,t.requested_by,t.created_at,t.finished_at"
                + " from platform_delivery_replay_task t"
                + " join platform_delivery_record d on d.tenant_id = t.tenant_id and d.id = t.delivery_id"
                + " join platform_webhook_subscription s on s.tenant_id = d.tenant_id and s.id = d.subscription_id"
                + " where t.delivery_id = ?"
                + " order by t.created_at desc",
            deliveryId)
        .map(DeliveryRecordService::toReplayTaskRow);
  }

  private DeliveryRecordReplayTaskRow requireReplayTask(UUID taskId) {
    var row =
        sql.fetchOne(
            "select t.id,t.delivery_id,d.event_id,d.event_type,s.name,t.trigger_type,t.status,"
                + " t.result_status_code,t.result_message,t.reason,t.requested_by,t.created_at,t.finished_at"
                + " from platform_delivery_replay_task t"
                + " join platform_delivery_record d on d.tenant_id = t.tenant_id and d.id = t.delivery_id"
                + " join platform_webhook_subscription s on s.tenant_id = d.tenant_id and s.id = d.subscription_id"
                + " where t.id = ?",
            taskId);
    notFound(row != null, "重放任务不存在");
    return toReplayTaskRow(row);
  }

  // ================================================================ 行映射

  private static DeliveryRecordRow toDeliveryRow(org.jooq.Record row) {
    return new DeliveryRecordRow(
        row.get("id", UUID.class),
        row.get("event_id", UUID.class),
        row.get("event_type", String.class),
        row.get("schema_version", Integer.class),
        row.get("subscription_id", UUID.class),
        row.get("name", String.class),
        row.get("app_id", UUID.class),
        row.get("target_url", String.class),
        row.get("status", String.class),
        statusLabel(row.get("status", String.class)),
        row.get("status_code", Integer.class),
        row.get("attempt_count", Integer.class),
        row.get("replay_count", Integer.class),
        row.get("last_error", String.class),
        row.get("next_retry_at", OffsetDateTime.class),
        row.get("first_attempt_at", OffsetDateTime.class),
        row.get("last_attempt_at", OffsetDateTime.class),
        row.get("delivered_at", OffsetDateTime.class),
        row.get("dead_lettered_at", OffsetDateTime.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private static DeliveryRecordAttemptRow toAttemptRow(org.jooq.Record row) {
    var source = row.get("attempt_source", String.class);
    return new DeliveryRecordAttemptRow(
        row.get("id", UUID.class),
        row.get("delivery_id", UUID.class),
        row.get("attempt_no", Integer.class),
        row.get("status_code", Integer.class),
        row.get("success", Boolean.class),
        source,
        attemptSourceLabel(source),
        row.get("duration_ms", Long.class),
        row.get("error", String.class),
        row.get("response_snippet", String.class),
        row.get("replay_task_id", UUID.class),
        row.get("occurred_at", OffsetDateTime.class));
  }

  private static DeliveryRecordDeadLetterRow toDeadLetterRow(org.jooq.Record row) {
    var status = row.get("status", String.class);
    return new DeliveryRecordDeadLetterRow(
        row.get("id", UUID.class),
        row.get("delivery_id", UUID.class),
        row.get("event_id", UUID.class),
        row.get("event_type", String.class),
        row.get("name", String.class),
        row.get("target_url", String.class),
        row.get("reason", String.class),
        row.get("failed_attempt_no", Integer.class),
        status,
        deadLetterStatusLabel(status),
        row.get("expires_at", OffsetDateTime.class),
        row.get("replayed_at", OffsetDateTime.class),
        row.get("replay_task_id", UUID.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private static DeliveryRecordReplayTaskRow toReplayTaskRow(org.jooq.Record row) {
    var trigger = row.get("trigger_type", String.class);
    var status = row.get("status", String.class);
    return new DeliveryRecordReplayTaskRow(
        row.get("id", UUID.class),
        row.get("delivery_id", UUID.class),
        row.get("event_id", UUID.class),
        row.get("event_type", String.class),
        row.get("name", String.class),
        trigger,
        triggerTypeLabel(trigger),
        status,
        replayStatusLabel(status),
        row.get("result_status_code", Integer.class),
        row.get("result_message", String.class),
        row.get("reason", String.class),
        row.get("requested_by", UUID.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("finished_at", OffsetDateTime.class));
  }

  private static String statusLabel(String status) {
    var parsed = DeliveryStatus.parse(status);
    return parsed == null ? status : parsed.label();
  }

  private static String attemptSourceLabel(String source) {
    var parsed = AttemptSource.parse(source);
    return parsed == null ? source : parsed.label();
  }

  private static String deadLetterStatusLabel(String status) {
    var parsed = DeadLetterStatus.parse(status);
    return parsed == null ? status : parsed.label();
  }

  private static String replayStatusLabel(String status) {
    var parsed = ReplayStatus.parse(status);
    return parsed == null ? status : parsed.label();
  }

  private static String triggerTypeLabel(String trigger) {
    return "DEAD_LETTER".equals(trigger) ? "死信重放" : "手动重投";
  }

  /** 把可变参数拼成数组（jOOQ 的可变参数不能直接接 {@code List<Object>}）。 */
  private static Object[] concat(List<Object> params, Object... tail) {
    var all = new ArrayList<Object>(params.size() + tail.length);
    all.addAll(params);
    all.addAll(List.of(tail));
    return all.toArray();
  }
}
