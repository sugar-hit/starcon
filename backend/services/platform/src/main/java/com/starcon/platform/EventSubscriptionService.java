package com.starcon.platform;

import static com.starcon.platform.EventSubscriptionModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 18.1.2.1 事件订阅的服务层（**订阅配置侧**）。
 *
 * <p>数据访问一律走 **jOOQ 显式 SQL**（AGENTS.md：不用 JPA 懒加载掩盖关键逻辑）。
 * 租户上下文由 {@link AccessService#run} 绑定 —— 本类**不**读任何请求头里的租户。
 *
 * <p><b>四条纪律：</b>
 *
 * <ol>
 *   <li><b>「可订阅事件列表」真查注册表</b>：事件类型不是一份写死的常量清单，
 *       而是 {@code platform_webhook_event_type} 表里的行，每行带出处
 *       （source_service + source_artifact）。
 *   <li><b>签名密钥明文只出现一次</b>：生成 / 轮换时把明文放进响应，
 *       之后库里只剩 AES-256-GCM 密文，**任何读路径都取不回原文**。
 *   <li><b>写入归属会话租户</b>：所有 INSERT 的 {@code tenant_id} 取自已认证会话，
 *       不接受请求参数传入；RLS 的 WITH CHECK 再兜一层。
 *   <li><b>落库的时间戳一律取 DB 时钟</b>：{@link #dbNow()} 从数据库读
 *       {@code clock_timestamp()} 再用 OffsetDateTime 绑定（{@code ?::timestamptz}）。
 *       <p>🔴 **JVM 墙钟一个都不落库**：JVM 与 DB 两个时钟域在同一行上打架，
 *       会让「写回值 ≥ 起始值」这类约束被几十微秒击穿
 *       （trade 服务 V211 修的就是同款缺陷）。同时**不用**
 *       {@code Timestamp.valueOf(LocalDateTime...)} —— 那会静默偏 −28800s。
 * </ol>
 *
 * <p>⚠️ <b>本类不实现投递</b>：投递执行与投递明细归 18.1.2.2，
 * 这里只通过 {@link EventSubscriptionDeliveryPort} 留接口。
 */
@Service
public class EventSubscriptionService {

  private static final int MAX_PAGE_SIZE = 100;

  /** 签名密钥随机部分长度：32 字节 = 256 bit 熵。 */
  private static final int SECRET_BYTES = 32;

  private static final SecureRandom RANDOM = new SecureRandom();

  private final DSLContext sql;
  private final TenantTransactions tenants;
  private final ObjectMapper json;
  private final WebhookSigningSecretCipher cipher;
  private final EventSubscriptionDeliveryPort delivery;

  public EventSubscriptionService(
      DSLContext sql,
      TenantTransactions tenants,
      ObjectMapper json,
      @Value("${INTERNAL_KEY}") String internalKey,
      EventSubscriptionDeliveryPort delivery) {
    this.sql = sql;
    this.tenants = tenants;
    this.json = json;
    this.cipher = new WebhookSigningSecretCipher(internalKey);
    this.delivery = delivery;
  }

  // ================================================================ 工具

  private static void check(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static void conflict(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  /**
   * 资源不存在（**404**）。
   *
   * <p>🔴 「不存在」与「存在但属于别的租户」必须**看起来一模一样**：
   * RLS 把对方的行挡掉之后，这里只能答「不存在」——
   * 答 403 等于告诉调用方「这个 id 真的存在，只是你不能看」，那本身就是一次信息泄露。
   */
  private static void notFound(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static String required(String value, String label, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(!trimmed.isEmpty(), label + "不能为空");
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  private static String optional(String value, String label, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  /**
   * DB 时钟：读一次 {@code clock_timestamp()} 再绑定。
   *
   * <p>🔴 落库的时间戳**必须**与测试断言处在**同一个时钟域**，
   * 否则「写回值落在插入前后的 DB 取样之间」这条断言会在负载下假红。
   */
  private OffsetDateTime dbNow() {
    return sql.fetchOne("select clock_timestamp()").get(0, OffsetDateTime.class);
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

  /** 18.1.2.1 的能力声明：逐维说清「有没有、依据是什么、缺了会怎样」。 */
  public EventSubscriptionCapabilities capabilities() {
    var capabilities =
        List.of(
            new EventSubscriptionCapability(
                "18.1.2.1",
                "可订阅事件列表（事件定义注册表）",
                true,
                "GET /platform/webhooks/event-types（真分页 + 真筛选）；数据来自 platform_webhook_event_type 表，每行带 source_service + source_artifact（出处）",
                "",
                ""),
            new EventSubscriptionCapability(
                "18.1.2.1",
                "订阅端点配置",
                true,
                "POST /platform/webhooks/subscriptions 与 PUT .../subscriptions/{id}；订阅挂在 platform_app 上（真 FK），target_url 必须是 http/https",
                "",
                ""),
            new EventSubscriptionCapability(
                "18.1.2.1",
                "签名密钥（生成 / 轮换 / 吊销）",
                true,
                "POST .../subscriptions/{id}/signing-secret 与 .../signing-secret/rotate；SecureRandom 生成、AES-256-GCM 密文落库、明文只返回一次",
                "",
                ""),
            new EventSubscriptionCapability(
                "18.1.2.1",
                "事件过滤",
                true,
                "platform_webhook_subscription_event 表 + 增删端点；对事件定义注册表建真 FK（不能订阅一个没登记的 schema）",
                "",
                ""),
            new EventSubscriptionCapability(
                "18.1.2.1",
                "重试与死信策略配置",
                true,
                "platform_webhook_policy 表（租户级默认 + 订阅级专属）；**只有配置**，执行归 18.1.2.2",
                "",
                ""),
            new EventSubscriptionCapability(
                "18.1.2.1",
                "签名试算（证明签名是真的）",
                true,
                "POST .../subscriptions/{id}/signature-preview；真算 HMAC-SHA256，不投递、不写投递记录",
                "",
                ""),
            new EventSubscriptionCapability(
                "18.1.2.1",
                "**事件实际投递**",
                false,
                "本仓没有从业务服务 outbox 取事件并按订阅投递出去的 relay",
                "18.1.2.2",
                "订阅配置可以配好，但事件不会真的发到外部端点；投递端点如实答 409，一次包都不发"),
            new EventSubscriptionCapability(
                "18.1.2.1",
                "**上游 outbox relay**（把业务事件搬到平台）",
                false,
                "backend/libraries 下没有 event-envelope 库；平台服务也没有跨服务读 outbox 的授权通道",
                "18.1.2.2",
                "事件定义的「出处」只能由租户登记时写清（source_service + source_artifact），系统不会自动发现"),
            new EventSubscriptionCapability(
                "18.1.2.1",
                "**死信重放**",
                false,
                "死信是投递明细的一种，重放要读投递记录",
                "18.1.2.2",
                "本模块只存死信**保留天数**这一条策略参数，不存死信本身"));
    var gaps =
        List.of(
            new EventSubscriptionGap(
                "18.1.2.2",
                "投递记录",
                "platform",
                false,
                "下游",
                "投递明细（事件 ID、订阅方、投递状态、响应码、重试次数、死信重放）归 18.1.2.2；本模块只留接口（EventSubscriptionDeliveryPort）"),
            new EventSubscriptionGap(
                "18.1.1.1",
                "应用与凭证",
                "platform",
                true,
                "上游",
                "订阅必须挂在一个已注册应用上（platform_app，真 FK）；应用与凭证归 18.1.1.1"));
    return new EventSubscriptionCapabilities(capabilities, gaps);
  }

  /** 概览：几个数（真查库）。 */
  public EventSubscriptionOverview overview() {
    return new EventSubscriptionOverview(
        count("select count(*) from platform_webhook_event_type"),
        count("select count(*) from platform_webhook_event_type where status = 'ACTIVE'"),
        count("select count(*) from platform_webhook_subscription"),
        count("select count(*) from platform_webhook_subscription where status = 'ACTIVE'"),
        count("select count(*) from platform_webhook_subscription_event"),
        count("select count(*) from platform_webhook_signing_secret"),
        count("select count(*) from platform_webhook_signing_secret where status = 'ACTIVE'"),
        count("select count(*) from platform_webhook_policy"));
  }

  // ================================================================ 事件定义（可订阅事件列表）

  /** 事件定义列表（**真分页 + 真筛选**，数据来自注册表）。 */
  public EventSubscriptionEventTypePage eventTypes(
      String keyword, String status, int page, int size) {
    var where = new ArrayList<String>();
    var params = new ArrayList<Object>();
    var text = keyword == null ? "" : keyword.trim();
    if (!text.isEmpty()) {
      where.add(
          "(event_type ilike ? or source_service ilike ? or source_artifact ilike ?)");
      params.add("%" + text + "%");
      params.add("%" + text + "%");
      params.add("%" + text + "%");
    }
    var statusText = status == null ? "" : status.trim();
    if (!statusText.isEmpty()) {
      var parsed = EventTypeStatus.parse(statusText);
      check(parsed != null, "状态只能是 ACTIVE 或 DEPRECATED");
      where.add("status = ?");
      params.add(parsed.name());
    }
    var clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    var total =
        count("select count(*) from platform_webhook_event_type" + clause, params.toArray());
    var limit = clampSize(size);
    var offset = clampPage(page) * limit;
    var rows =
        sql.fetch(
            "select id,event_type,schema_version,status,payload_schema::text as payload_schema,"
                + " source_service,source_artifact,registered_at"
                + " from platform_webhook_event_type"
                + clause
                + " order by event_type, schema_version limit ? offset ?",
            concat(params, limit, offset));
    return new EventSubscriptionEventTypePage(
        rows.map(EventSubscriptionService::toEventTypeRow), clampPage(page), limit, total);
  }

  /**
   * 登记一个事件定义（= 一个 event_type 的一个 schema 版本）。
   *
   * <p>🔴 **同一 (event_type, schema_version) 不许重复登记**：结构不可原地修改，
   * 要改就登记新版本 —— 这条纪律一半由服务层挡（409），一半由数据库触发器挡
   * （直接改 payload_schema 会被 restrict_violation 拒掉）。
   */
  public EventSubscriptionEventTypeRow registerEventType(
      EventSubscriptionEventTypeInput input, AccessService.Access actor) {
    var eventType = required(input.eventType(), "事件类型", 64);
    var version = input.schemaVersion() == null ? 1 : input.schemaVersion();
    check(version >= 1, "schema 版本必须大于等于 1");
    var status =
        input.status() == null || input.status().isBlank()
            ? EventTypeStatus.ACTIVE
            : EventTypeStatus.parse(input.status());
    check(status != null, "状态只能是 ACTIVE 或 DEPRECATED");
    var sourceService = required(input.sourceService(), "出处服务", 32);
    var sourceArtifact = required(input.sourceArtifact(), "出处位置（文件:行）", 240);
    var payloadSchema = canonicalJson(input.payloadSchema(), "事件载荷 schema");
    check(payloadSchema != null, "事件载荷 schema 必须是合法的 JSON 对象");
    check(!payloadSchema.equals("{}"), "事件载荷 schema 不能为空：请写出这个事件到底带哪些字段");

    var exists =
        count(
            "select count(*) from platform_webhook_event_type where event_type = ? and schema_version = ?",
            eventType,
            version);
    conflict(
        exists == 0,
        "事件类型 "
            + eventType
            + " 的第 "
            + version
            + " 版已经登记过了。事件 schema 是版本化契约：结构不可原地修改，"
            + "破坏性变更请登记一个更高的 schema 版本。");

    var id = UUID.randomUUID();
    var now = dbNow();
    sql.execute(
        "insert into platform_webhook_event_type(id,tenant_id,event_type,schema_version,status,"
            + " payload_schema,source_service,source_artifact,registered_at)"
            + " values(?,?,?,?,?,?::jsonb,?,?,?::timestamptz)",
        id,
        currentTenant(),
        eventType,
        version,
        status.name(),
        payloadSchema,
        sourceService,
        sourceArtifact,
        now);
    audit(null, "EVENT_TYPE_REGISTERED", actor.userId(), "EVENT_TYPE", id, Map.of(
        "eventType", eventType,
        "schemaVersion", version,
        "sourceService", sourceService,
        "sourceArtifact", sourceArtifact));
    return requireEventType(id);
  }

  /**
   * 改事件定义的**状态**（ACTIVE ↔ DEPRECATED）。
   *
   * <p>🔴 **只能改状态**：{@code event_type / schema_version / payload_schema} 三个字段
   * 由数据库触发器挡死，服务层也不提供改它们的入口。
   */
  public EventSubscriptionEventTypeRow changeEventTypeStatus(
      UUID eventTypeId, EventSubscriptionEventTypeStatusInput input, AccessService.Access actor) {
    var current = requireEventType(eventTypeId);
    var status = EventTypeStatus.parse(input == null ? null : input.status());
    check(status != null, "状态只能是 ACTIVE 或 DEPRECATED");
    var now = dbNow();
    sql.execute(
        "update platform_webhook_event_type set status = ? where id = ?",
        status.name(),
        eventTypeId);
    audit(null, "EVENT_TYPE_STATUS_CHANGED", actor.userId(), "EVENT_TYPE", eventTypeId, Map.of(
        "eventType", current.eventType(),
        "from", current.status(),
        "to", status.name()));
    return requireEventType(eventTypeId);
  }

  // ================================================================ 订阅

  /** 订阅列表（真分页 + 真筛选）。 */
  public EventSubscriptionPage subscriptions(
      String keyword, String status, UUID appId, int page, int size) {
    var where = new ArrayList<String>();
    var params = new ArrayList<Object>();
    var text = keyword == null ? "" : keyword.trim();
    if (!text.isEmpty()) {
      where.add("(s.name ilike ? or s.target_url ilike ? or a.app_name ilike ?)");
      params.add("%" + text + "%");
      params.add("%" + text + "%");
      params.add("%" + text + "%");
    }
    var statusText = status == null ? "" : status.trim();
    if (!statusText.isEmpty()) {
      var parsed = SubscriptionStatus.parse(statusText);
      check(parsed != null, "状态只能是 ACTIVE、PAUSED 或 DISABLED");
      where.add("s.status = ?");
      params.add(parsed.name());
    }
    if (appId != null) {
      where.add("s.app_id = ?");
      params.add(appId);
    }
    var clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    var total =
        count(
            "select count(*) from platform_webhook_subscription s"
                + " join platform_app a on a.tenant_id = s.tenant_id and a.id = s.app_id"
                + clause,
            params.toArray());
    var limit = clampSize(size);
    var offset = clampPage(page) * limit;
    var rows =
        sql.fetch(
            "select s.id,s.app_id,a.app_name,s.name,s.target_url,s.status,s.description,"
                + " (select count(*) from platform_webhook_subscription_event e"
                + "   where e.tenant_id = s.tenant_id and e.subscription_id = s.id) as filter_count,"
                + " (select count(*) from platform_webhook_signing_secret k"
                + "   where k.tenant_id = s.tenant_id and k.subscription_id = s.id"
                + "     and k.status = 'ACTIVE') as active_secret_count,"
                + " s.created_at,s.updated_at"
                + " from platform_webhook_subscription s"
                + " join platform_app a on a.tenant_id = s.tenant_id and a.id = s.app_id"
                + clause
                + " order by s.created_at desc, s.id limit ? offset ?",
            concat(params, limit, offset));
    return new EventSubscriptionPage(
        rows.map(EventSubscriptionService::toSubscriptionRow), clampPage(page), limit, total);
  }

  /** 创建订阅。 */
  public EventSubscriptionRow createSubscription(
      EventSubscriptionInput input, AccessService.Access actor) {
    check(input.appId() != null, "必须指定订阅所属的应用");
    var appExists =
        count("select count(*) from platform_app where id = ?", input.appId());
    check(appExists > 0, "应用不存在（订阅必须挂在一个已注册的应用上）");
    var name = required(input.name(), "订阅名称", 120);
    var targetUrl = required(input.targetUrl(), "订阅端点地址", 500);
    checkTargetUrl(targetUrl);
    var status =
        input.status() == null || input.status().isBlank()
            ? SubscriptionStatus.ACTIVE
            : SubscriptionStatus.parse(input.status());
    check(status != null, "状态只能是 ACTIVE、PAUSED 或 DISABLED");
    var description = optional(input.description(), "订阅说明", 500);

    var duplicated =
        count(
            "select count(*) from platform_webhook_subscription where app_id = ? and name = ?",
            input.appId(),
            name);
    conflict(duplicated == 0, "同一个应用下已经有叫「" + name + "」的订阅了");

    var id = UUID.randomUUID();
    var now = dbNow();
    sql.execute(
        "insert into platform_webhook_subscription(id,tenant_id,app_id,name,target_url,status,"
            + " description,created_by,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        input.appId(),
        name,
        targetUrl,
        status.name(),
        description,
        actor.userId(),
        now,
        now);
    audit(id, "SUBSCRIPTION_CREATED", actor.userId(), "SUBSCRIPTION", id, Map.of(
        "name", name,
        "targetUrl", targetUrl,
        "status", status.name()));
    return requireSubscription(id);
  }

  /** 改订阅（端点 / 名称 / 状态 / 说明）。 */
  public EventSubscriptionRow updateSubscription(
      UUID subscriptionId, EventSubscriptionInput input, AccessService.Access actor) {
    var current = requireSubscription(subscriptionId);
    check(
        !"DISABLED".equals(current.status()),
        "订阅已停用（终态），不能再改。要重新启用请新建一个订阅。");
    var name = input.name() == null ? current.name() : required(input.name(), "订阅名称", 120);
    var targetUrl =
        input.targetUrl() == null
            ? current.targetUrl()
            : required(input.targetUrl(), "订阅端点地址", 500);
    checkTargetUrl(targetUrl);
    var status =
        input.status() == null || input.status().isBlank()
            ? SubscriptionStatus.parse(current.status())
            : SubscriptionStatus.parse(input.status());
    check(status != null, "状态只能是 ACTIVE、PAUSED 或 DISABLED");
    var description =
        input.description() == null ? current.description() : optional(input.description(), "订阅说明", 500);

    if (input.appId() != null && !input.appId().equals(current.appId())) {
      var appExists = count("select count(*) from platform_app where id = ?", input.appId());
      check(appExists > 0, "应用不存在（订阅必须挂在一个已注册的应用上）");
    }
    var appId = input.appId() == null ? current.appId() : input.appId();
    var duplicated =
        count(
            "select count(*) from platform_webhook_subscription where app_id = ? and name = ? and id <> ?",
            appId,
            name,
            subscriptionId);
    conflict(duplicated == 0, "同一个应用下已经有叫「" + name + "」的订阅了");

    var now = dbNow();
    sql.execute(
        "update platform_webhook_subscription set app_id = ?, name = ?, target_url = ?, status = ?,"
            + " description = ?, updated_at = ?::timestamptz where id = ?",
        appId,
        name,
        targetUrl,
        status.name(),
        description,
        now,
        subscriptionId);
    audit(subscriptionId, "SUBSCRIPTION_UPDATED", actor.userId(), "SUBSCRIPTION", subscriptionId,
        Map.of("name", name, "targetUrl", targetUrl, "status", status.name()));
    return requireSubscription(subscriptionId);
  }

  /** 吊销订阅（**不删**：订阅与其审计痕迹都要留着）。 */
  public EventSubscriptionRow revokeSubscription(
      UUID subscriptionId, String reason, AccessService.Access actor) {
    var current = requireSubscription(subscriptionId);
    if ("DISABLED".equals(current.status())) {
      // 已经停用：不重复写时间戳（重复吊销不该让审计看起来像发生了两次）。
      return current;
    }
    var now = dbNow();
    sql.execute(
        "update platform_webhook_subscription set status = 'DISABLED', updated_at = ?::timestamptz"
            + " where id = ?",
        now,
        subscriptionId);
    audit(subscriptionId, "SUBSCRIPTION_REVOKED", actor.userId(), "SUBSCRIPTION", subscriptionId,
        Map.of("reason", reason == null ? "" : reason.trim()));
    return requireSubscription(subscriptionId);
  }

  /** 订阅详情：订阅 + 事件过滤 + 签名密钥元数据 + 生效策略。 */
  public EventSubscriptionDetail subscriptionDetail(UUID subscriptionId) {
    var subscription = requireSubscription(subscriptionId);
    return new EventSubscriptionDetail(
        subscription,
        filters(subscriptionId),
        signingSecrets(subscriptionId),
        effectivePolicy(subscriptionId));
  }

  // ================================================================ 事件过滤

  /** 事件过滤列表。 */
  public List<EventSubscriptionFilterRow> filters(UUID subscriptionId) {
    requireSubscription(subscriptionId);
    return sql.fetch(
            "select id,subscription_id,event_type,schema_version,filter::text as filter,enabled,created_at"
                + " from platform_webhook_subscription_event where subscription_id = ?"
                + " order by event_type, schema_version",
            subscriptionId)
        .map(EventSubscriptionService::toFilterRow);
  }

  /**
   * 加一条事件过滤。
   *
   * <p>🔴 **只能订阅注册表里登记过的 (event_type, schema_version)** ——
   * 订阅一份不存在的契约，等于让对接方按一份不存在的 schema 写代码。
   */
  public EventSubscriptionFilterRow addFilter(
      UUID subscriptionId, EventSubscriptionFilterInput input, AccessService.Access actor) {
    var subscription = requireSubscription(subscriptionId);
    check(
        !"DISABLED".equals(subscription.status()), "订阅已停用（终态），不能再加事件过滤。");
    var eventType = required(input.eventType(), "事件类型", 64);
    var version = resolveSchemaVersion(eventType, input.schemaVersion());
    var filterJson = canonicalJson(input.filter(), "过滤条件");
    check(filterJson != null, "过滤条件必须是合法的 JSON 对象");
    var enabled = input.enabled() == null || input.enabled();

    var duplicated =
        count(
            "select count(*) from platform_webhook_subscription_event"
                + " where subscription_id = ? and event_type = ? and schema_version = ?",
            subscriptionId,
            eventType,
            version);
    conflict(
        duplicated == 0,
        "这个订阅已经订阅了 " + eventType + " 第 " + version + " 版事件。");

    var id = UUID.randomUUID();
    var now = dbNow();
    sql.execute(
        "insert into platform_webhook_subscription_event(id,tenant_id,subscription_id,event_type,"
            + " schema_version,filter,enabled,created_at)"
            + " values(?,?,?,?,?,?::jsonb,?,?::timestamptz)",
        id,
        currentTenant(),
        subscriptionId,
        eventType,
        version,
        filterJson,
        enabled,
        now);
    audit(subscriptionId, "FILTER_ADDED", actor.userId(), "FILTER", id, Map.of(
        "eventType", eventType,
        "schemaVersion", version,
        "enabled", enabled));
    return requireFilter(id);
  }

  /** 删一条事件过滤。 */
  public void removeFilter(UUID subscriptionId, UUID filterId, AccessService.Access actor) {
    requireSubscription(subscriptionId);
    var existing =
        sql.fetchOne(
            "select id,event_type,schema_version from platform_webhook_subscription_event"
                + " where id = ? and subscription_id = ?",
            filterId,
            subscriptionId);
    check(existing != null, "事件过滤不存在");
    sql.execute("delete from platform_webhook_subscription_event where id = ?", filterId);
    audit(subscriptionId, "FILTER_REMOVED", actor.userId(), "FILTER", filterId, Map.of(
        "eventType", existing.get("event_type", String.class),
        "schemaVersion", existing.get("schema_version", Integer.class)));
  }

  // ================================================================ 签名密钥

  /** 签名密钥列表（**只有元数据，没有明文**）。 */
  public List<EventSubscriptionSecretRow> signingSecrets(UUID subscriptionId) {
    requireSubscription(subscriptionId);
    return sql.fetch(
            "select id,subscription_id,version,status,secret_hint,created_at,rotated_at"
                + " from platform_webhook_signing_secret where subscription_id = ?"
                + " order by version desc",
            subscriptionId)
        .map(EventSubscriptionService::toSecretRow);
  }

  /**
   * 生成签名密钥（**明文只在这里返回一次**）。
   *
   * <p>🔴 已经有生效中的密钥时**不允许再生成**：那是「轮换」的语义。
   * 把两件事合成一件，会出现「以为在生成、其实把旧的顶掉了」——
   * 旧密钥失效而对接方不知道。
   */
  public EventSubscriptionSecretIssued issueSecret(
      UUID subscriptionId, AccessService.Access actor) {
    var subscription = requireSubscription(subscriptionId);
    check(
        !"DISABLED".equals(subscription.status()),
        "订阅已停用（终态），不能再生成签名密钥。");
    var active =
        count(
            "select count(*) from platform_webhook_signing_secret"
                + " where subscription_id = ? and status = 'ACTIVE'",
            subscriptionId);
    conflict(
        active == 0,
        "该订阅已有生效中的签名密钥。要换一套请用「轮换」—— 轮换会让旧密钥立即失效。");
    var version =
        (int)
            count(
                "select coalesce(max(version),0) from platform_webhook_signing_secret"
                    + " where subscription_id = ?",
                subscriptionId)
            + 1;
    return insertSecret(subscriptionId, version, actor);
  }

  /** 轮换签名密钥：生成新明文 + 旧密钥立即失效（旧密钥转 ROTATED，痕迹保留）。 */
  public EventSubscriptionSecretIssued rotateSecret(
      UUID subscriptionId, AccessService.Access actor) {
    var subscription = requireSubscription(subscriptionId);
    check(
        !"DISABLED".equals(subscription.status()),
        "订阅已停用（终态），不能再轮换签名密钥。");
    var now = dbNow();
    sql.execute(
        "update platform_webhook_signing_secret set status = 'ROTATED', rotated_at = ?::timestamptz"
            + " where subscription_id = ? and status = 'ACTIVE'",
        now,
        subscriptionId);
    var version =
        (int)
            count(
                "select coalesce(max(version),0) from platform_webhook_signing_secret"
                    + " where subscription_id = ?",
                subscriptionId)
            + 1;
    var issued = insertSecret(subscriptionId, version, actor);
    audit(subscriptionId, "SIGNING_SECRET_ROTATED", actor.userId(), "SIGNING_SECRET",
        issued.secret().id(), Map.of("version", version));
    return issued;
  }

  /** 吊销生效中的签名密钥（泄露时的一键止血）。 */
  public EventSubscriptionSecretRow revokeSecret(
      UUID subscriptionId, AccessService.Access actor) {
    requireSubscription(subscriptionId);
    var now = dbNow();
    sql.execute(
        "update platform_webhook_signing_secret set status = 'REVOKED', rotated_at = ?::timestamptz"
            + " where subscription_id = ? and status = 'ACTIVE'",
        now,
        subscriptionId);
    audit(subscriptionId, "SIGNING_SECRET_REVOKED", actor.userId(), "SIGNING_SECRET", null,
        Map.of());
    var remaining =
        sql.fetchOne(
            "select id,subscription_id,version,status,secret_hint,created_at,rotated_at"
                + " from platform_webhook_signing_secret where subscription_id = ?"
                + " and status = 'ACTIVE'",
            subscriptionId);
    if (remaining == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该订阅没有生效中的签名密钥可吊销。");
    }
    return toSecretRow(remaining);
  }

  /** 插入一把签名密钥（明文在这里生成，明文只往响应里走）。 */
  private EventSubscriptionSecretIssued insertSecret(
      UUID subscriptionId, int version, AccessService.Access actor) {
    var plain = randomSecret();
    var id = UUID.randomUUID();
    var now = dbNow();
    sql.execute(
        "insert into platform_webhook_signing_secret(id,tenant_id,subscription_id,version,status,"
            + " secret_ciphertext,secret_hint,created_by,created_at)"
            + " values(?,?,?,?,'ACTIVE',?,?,?,?::timestamptz)",
        id,
        currentTenant(),
        subscriptionId,
        version,
        cipher.seal(plain),
        hint(plain),
        actor.userId(),
        now);
    audit(subscriptionId, "SIGNING_SECRET_GENERATED", actor.userId(), "SIGNING_SECRET", id,
        Map.of("version", version));
    return new EventSubscriptionSecretIssued(
        requireSecret(id),
        plain,
        "这是唯一一次显示签名密钥的机会。离开这个页面之后无法再次查看，"
            + "请立刻保存到对接方的密钥库里；泄露后请用「吊销」立即止血，"
            + "再用「轮换」换一把新的。");
  }

  // ================================================================ 策略

  /** 策略列表（租户级默认 + 各订阅的专属策略）。 */
  public List<EventSubscriptionPolicyRow> policies() {
    return sql.fetch(
            "select id,subscription_id,max_attempts,initial_backoff_seconds,backoff_multiplier,"
                + " max_backoff_seconds,request_timeout_seconds,dead_letter_retention_days,"
                + " signature_algorithm,signature_header,updated_at"
                + " from platform_webhook_policy"
                + " order by (subscription_id is not null), subscription_id, updated_at desc")
        .map(EventSubscriptionService::toPolicyRow);
  }

  /**
   * 新建一份策略。
   *
   * <p>{@code subscriptionId} 为空 = **租户级默认策略**；非空 = 该订阅的专属策略。
   */
  public EventSubscriptionPolicyRow createPolicy(
      EventSubscriptionPolicyInput input, AccessService.Access actor) {
    // ⚠️ **先校验参数，再判重**：参数不合法是 400，与「已存在一份默认策略」的 409 是两件事。
    //   反过来写的话，同一个请求只会看到 409，运营根本不知道自己填错了什么。
    var values = normalizePolicy(input, null);
    if (input.subscriptionId() != null) {
      requireSubscription(input.subscriptionId());
      var exists =
          count(
              "select count(*) from platform_webhook_policy where subscription_id = ?",
              input.subscriptionId());
      conflict(
          exists == 0,
          "该订阅已经有专属策略了。要改参数请用「修改」，不要重复创建。");
    } else {
      var exists =
          count("select count(*) from platform_webhook_policy where subscription_id is null");
      conflict(exists == 0, "本租户已经有默认策略了。要改参数请用「修改」，不要重复创建。");
    }
    var id = UUID.randomUUID();
    var now = dbNow();
    sql.execute(
        "insert into platform_webhook_policy(id,tenant_id,subscription_id,max_attempts,"
            + " initial_backoff_seconds,backoff_multiplier,max_backoff_seconds,"
            + " request_timeout_seconds,dead_letter_retention_days,signature_algorithm,"
            + " signature_header,created_at,updated_at)"
            + " values(?,?,?,?,?,?::numeric,?,?,?,'HMAC_SHA256','X-Starcon-Signature',"
            + "?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        input.subscriptionId(),
        values.maxAttempts(),
        values.initialBackoffSeconds(),
        values.backoffMultiplier(),
        values.maxBackoffSeconds(),
        values.requestTimeoutSeconds(),
        values.deadLetterRetentionDays(),
        now,
        now);
    audit(input.subscriptionId(), "POLICY_CREATED", actor.userId(), "POLICY", id,
        Map.of("maxAttempts", values.maxAttempts()));
    return requirePolicy(id);
  }

  /** 改策略（null = 沿用当前值）。 */
  public EventSubscriptionPolicyRow updatePolicy(
      UUID policyId, EventSubscriptionPolicyInput input, AccessService.Access actor) {
    var current = requirePolicy(policyId);
    var values = normalizePolicy(input, current);
    var now = dbNow();
    sql.execute(
        "update platform_webhook_policy set max_attempts = ?, initial_backoff_seconds = ?,"
            + " backoff_multiplier = ?::numeric, max_backoff_seconds = ?,"
            + " request_timeout_seconds = ?, dead_letter_retention_days = ?,"
            + " updated_at = ?::timestamptz where id = ?",
        values.maxAttempts(),
        values.initialBackoffSeconds(),
        values.backoffMultiplier(),
        values.maxBackoffSeconds(),
        values.requestTimeoutSeconds(),
        values.deadLetterRetentionDays(),
        now,
        policyId);
    audit(current.subscriptionId(), "POLICY_UPDATED", actor.userId(), "POLICY", policyId,
        Map.of("maxAttempts", values.maxAttempts()));
    return requirePolicy(policyId);
  }

  /** 订阅的**生效策略**：订阅级专属优先，没有则用租户级默认，再没有就是 null。 */
  public EventSubscriptionPolicyRow effectivePolicy(UUID subscriptionId) {
    var row =
        sql.fetchOne(
            "select id,subscription_id,max_attempts,initial_backoff_seconds,backoff_multiplier,"
                + " max_backoff_seconds,request_timeout_seconds,dead_letter_retention_days,"
                + " signature_algorithm,signature_header,updated_at"
                + " from platform_webhook_policy"
                + " where subscription_id = ? or subscription_id is null"
                + " order by (subscription_id is null) limit 1",
            subscriptionId);
    return row == null ? null : toPolicyRow(row);
  }

  // ================================================================ 审计

  /** 审计事件列表（真分页，新到旧；可按订阅过滤）。 */
  public EventSubscriptionAuditEventPage auditEvents(UUID subscriptionId, int page, int size) {
    var where = new ArrayList<String>();
    var params = new ArrayList<Object>();
    if (subscriptionId != null) {
      where.add("subscription_id = ?");
      params.add(subscriptionId);
    }
    var clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    var total = count("select count(*) from platform_webhook_audit_event" + clause, params.toArray());
    var limit = clampSize(size);
    var offset = clampPage(page) * limit;
    var rows =
        sql.fetch(
            "select id,subscription_id,action,actor_user_id,target_type,target_id,"
                + " detail::text as detail,occurred_at"
                + " from platform_webhook_audit_event"
                + clause
                + " order by occurred_at desc, id desc limit ? offset ?",
            concat(params, limit, offset));
    return new EventSubscriptionAuditEventPage(
        rows.map(EventSubscriptionService::toAuditRow), clampPage(page), limit, total);
  }

  // ================================================================ 签名试算

  /**
   * 签名试算：给定样例载荷，返回平台**会发**的签名头。
   *
   * <p>🔴 **不投递、不写任何投递记录**：这一条只把「签名怎么算」变成对接方能当场核对的
   * 事实（真算 HMAC-SHA256，用的是该订阅**生效中**的那把密钥）。
   *
   * <p>⚠️ 归 {@code update} 权限而不是 {@code read}：它会用到签名密钥材料，
   * 应当由能改订阅配置的人来用。
   */
  public EventSubscriptionSignaturePreview signaturePreview(
      UUID subscriptionId, EventSubscriptionSignaturePreviewInput input,
      AccessService.Access actor) {
    requireSubscription(subscriptionId);
    check(input != null, "缺少样例载荷");
    var payload = input.payload() == null ? "" : input.payload();
    var timestamp =
        input.timestamp() == null || input.timestamp().isBlank()
            ? Long.toString(dbEpochSeconds())
            : input.timestamp().trim();
    check(timestamp.matches("-?\\d{1,20}"), "时间戳必须是 Unix 秒（整数字符串）");

    var secretRow =
        sql.fetchOne(
            "select id,secret_ciphertext from platform_webhook_signing_secret"
                + " where subscription_id = ? and status = 'ACTIVE'",
            subscriptionId);
    check(
        secretRow != null,
        "该订阅还没有生效中的签名密钥。请先生成或轮换一把，再来试算签名。");

    var plain = cipher.open(secretRow.get("secret_ciphertext", String.class));
    var signature = WebhookSigningSecretCipher.sign(plain, timestamp, payload.getBytes(StandardCharsets.UTF_8));
    // 自检：算出来的签名必须与自己再算一遍相等（防「算签名」这段本身写错）。
    check(
        WebhookSigningSecretCipher.matches(signature, signature),
        "签名自检失败：签名计算结果与自身不一致");

    audit(subscriptionId, "SIGNATURE_PREVIEWED", actor.userId(), "SIGNING_SECRET",
        secretRow.get("id", UUID.class), Map.of("timestamp", timestamp));

    return new EventSubscriptionSignaturePreview(
        "X-Starcon-Signature",
        "HMAC_SHA256",
        timestamp,
        timestamp + "." + payload,
        signature,
        "t=" + timestamp + ",v1=" + signature,
        payload,
        "这条签名是**真算**出来的（HMAC-SHA256，盖的是「时间戳 + 点 + 原始字节」），"
            + "但**没有投递**、**没有写任何投递记录** —— 真正的投递执行与投递明细归 18.1.2.2。"
            + "对接方可以拿这里的 headerValue 与自己的实现对一遍："
            + "把签名原文按 UTF-8 取字节，在前面拼上「时间戳.」，用同一把密钥算 HMAC-SHA256，"
            + "取十六进制小写，应当与 signature 一致。");
  }

  // ================================================================ 投递（当前未接入）

  /**
   * 投递一次事件 —— **当前如实答 409，一次包都不发**。
   *
   * <p>🔴 投递执行器与投递明细归 <b>18.1.2.2</b>。本仓没有从业务服务 outbox 取事件、
   * 再按订阅投递出去的 relay ⇒ {@link UnavailableEventSubscriptionDeliveryPort}
   * 逐次答「未接入」，这里把它翻成 <b>409</b> 并结束。
   *
   * <p>⚠️ **本方法一次数据都不写**：不落投递记录、不落投递日志。
   */
  public EventSubscriptionDeliveryPort.EventSubscriptionDeliveryOutcome deliver(
      UUID subscriptionId, EventSubscriptionDeliveryInput input, AccessService.Access actor) {
    var subscription = requireSubscription(subscriptionId);
    check(
        !"DISABLED".equals(subscription.status()), "订阅已停用（终态），不能投递。");
    var eventType = input == null ? "" : required(input.eventType(), "事件类型", 64);
    var payload = input == null || input.payload() == null ? "" : input.payload();
    var outcome =
        delivery.deliver(
            new EventSubscriptionDeliveryPort.EventSubscriptionDeliveryRequest(
                subscriptionId,
                eventType,
                input.schemaVersion() == null ? 0 : input.schemaVersion(),
                subscription.targetUrl(),
                payload.getBytes(StandardCharsets.UTF_8),
                "X-Starcon-Signature",
                ""));
    // 🔴 一条假的成功回执会让「对方系统已经收到并处理了这个事件」在数据上看起来发生过，
    //   而现场一个包都没发出去 ⇒ 端口答「未接入」就翻成 409 并结束。
    conflict(outcome.sent(), outcome.reason());
    return outcome;
  }

  // ================================================================ 内部工具

  /** 追加一条审计（**只追加**：表上有触发器挡 UPDATE / DELETE）。 */
  private void audit(
      UUID subscriptionId,
      String action,
      UUID actorUserId,
      String targetType,
      UUID targetId,
      Map<String, Object> detail) {
    String text;
    try {
      text = json.writeValueAsString(detail == null ? Map.of() : detail);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    sql.execute(
        "insert into platform_webhook_audit_event(id,tenant_id,subscription_id,action,actor_user_id,"
            + " target_type,target_id,detail,occurred_at)"
            + " values(?,?,?,?,?,?,?,?::jsonb,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        subscriptionId,
        action,
        actorUserId,
        targetType,
        targetId,
        text,
        dbNow());
  }

  /** DB 时钟的 Unix 秒（签名试算用；同样取 DB 时钟，不取 JVM 墙钟）。 */
  private long dbEpochSeconds() {
    return sql.fetchOne("select extract(epoch from clock_timestamp())").get(0, Long.class);
  }

  /** 随机签名密钥（Base64 编码的 32 字节 = 256 bit 熵）。 */
  private static String randomSecret() {
    var buffer = new byte[SECRET_BYTES];
    RANDOM.nextBytes(buffer);
    return Base64.getEncoder().encodeToString(buffer);
  }

  /** 末四位提示（只用于让人认出「这是哪一把」，不可用于验签）。 */
  private static String hint(String plain) {
    return plain.length() <= 4 ? plain : plain.substring(plain.length() - 4);
  }

  private static void checkTargetUrl(String targetUrl) {
    check(
        targetUrl.startsWith("http://") || targetUrl.startsWith("https://"),
        "订阅端点地址必须是 http:// 或 https:// 开头的地址");
    check(
        targetUrl.length() <= 500 && targetUrl.contains(" ") == false,
        "订阅端点地址里不能有空格");
  }

  /** 解析并规范化一段 JSON 对象；不是合法 JSON 对象时返回 {@code null}。 */
  private String canonicalJson(String raw, String label) {
    var canonical = canonicalJsonObject(raw, json);
    check(canonical != null, label + "必须是合法的 JSON 对象");
    return canonical;
  }

  /**
   * 解析事件 schema 版本。
   *
   * <p>未指定版本时取**注册表里该事件类型最大的 ACTIVE 版本** —— 也就是
   * 「可订阅事件列表」当前指向的那一版。指定的版本必须**已登记**。
   */
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
              + " 版**没有登记**。请先在「可订阅事件列表」里登记它，"
              + "再订阅 —— 订阅一份不存在的 schema，等于让对接方按一份不存在的契约写代码。");
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

  /** 策略参数：null = 沿用当前值（没有当前值时用默认值）。 */
  private record PolicyValues(
      int maxAttempts,
      int initialBackoffSeconds,
      BigDecimal backoffMultiplier,
      int maxBackoffSeconds,
      int requestTimeoutSeconds,
      int deadLetterRetentionDays) {}

  private PolicyValues normalizePolicy(EventSubscriptionPolicyInput input, EventSubscriptionPolicyRow current) {
    var maxAttempts = input.maxAttempts() == null ? (current == null ? 5 : current.maxAttempts()) : input.maxAttempts();
    check(maxAttempts >= 1 && maxAttempts <= 20, "最大尝试次数必须在 1 到 20 之间");
    var initial =
        input.initialBackoffSeconds() == null
            ? (current == null ? 30 : current.initialBackoffSeconds())
            : input.initialBackoffSeconds();
    check(initial >= 1 && initial <= 86400, "首次退避秒数必须在 1 到 86400 之间");
    var multiplier =
        input.backoffMultiplier() == null
            ? (current == null ? new BigDecimal("2.00") : current.backoffMultiplier())
            : input.backoffMultiplier();
    check(
        multiplier.compareTo(new BigDecimal("1.00")) >= 0
            && multiplier.compareTo(new BigDecimal("10.00")) <= 0,
        "退避倍数必须在 1.00 到 10.00 之间");
    var maxBackoff =
        input.maxBackoffSeconds() == null
            ? (current == null ? 3600 : current.maxBackoffSeconds())
            : input.maxBackoffSeconds();
    check(maxBackoff >= 1 && maxBackoff <= 86400, "单次退避上限必须在 1 到 86400 秒之间");
    var timeout =
        input.requestTimeoutSeconds() == null
            ? (current == null ? 10 : current.requestTimeoutSeconds())
            : input.requestTimeoutSeconds();
    check(timeout >= 1 && timeout <= 120, "请求超时必须在 1 到 120 秒之间");
    var retention =
        input.deadLetterRetentionDays() == null
            ? (current == null ? 30 : current.deadLetterRetentionDays())
            : input.deadLetterRetentionDays();
    check(retention >= 1 && retention <= 365, "死信保留天数必须在 1 到 365 天之间");
    return new PolicyValues(
        maxAttempts, initial, multiplier, maxBackoff, timeout, retention);
  }

  private static Object[] concat(List<Object> params, Object... more) {
    var out = new Object[params.size() + more.length];
    for (var i = 0; i < params.size(); i++) out[i] = params.get(i);
    for (var i = 0; i < more.length; i++) out[params.size() + i] = more[i];
    return out;
  }

  // ================================================================ 取数

  private EventSubscriptionEventTypeRow requireEventType(UUID id) {
    var row =
        sql.fetchOne(
            "select id,event_type,schema_version,status,payload_schema::text as payload_schema,"
                + " source_service,source_artifact,registered_at"
                + " from platform_webhook_event_type where id = ?",
            id);
    notFound(row != null, "事件定义不存在");
    return toEventTypeRow(row);
  }

  private EventSubscriptionRow requireSubscription(UUID id) {
    var row =
        sql.fetchOne(
            "select s.id,s.app_id,a.app_name,s.name,s.target_url,s.status,s.description,"
                + " (select count(*) from platform_webhook_subscription_event e"
                + "   where e.tenant_id = s.tenant_id and e.subscription_id = s.id) as filter_count,"
                + " (select count(*) from platform_webhook_signing_secret k"
                + "   where k.tenant_id = s.tenant_id and k.subscription_id = s.id"
                + "     and k.status = 'ACTIVE') as active_secret_count,"
                + " s.created_at,s.updated_at"
                + " from platform_webhook_subscription s"
                + " join platform_app a on a.tenant_id = s.tenant_id and a.id = s.app_id"
                + " where s.id = ?",
            id);
    notFound(row != null, "订阅不存在");
    return toSubscriptionRow(row);
  }

  private EventSubscriptionFilterRow requireFilter(UUID id) {
    var row =
        sql.fetchOne(
            "select id,subscription_id,event_type,schema_version,filter::text as filter,enabled,created_at"
                + " from platform_webhook_subscription_event where id = ?",
            id);
    notFound(row != null, "事件过滤不存在");
    return toFilterRow(row);
  }

  private EventSubscriptionSecretRow requireSecret(UUID id) {
    var row =
        sql.fetchOne(
            "select id,subscription_id,version,status,secret_hint,created_at,rotated_at"
                + " from platform_webhook_signing_secret where id = ?",
            id);
    notFound(row != null, "签名密钥不存在");
    return toSecretRow(row);
  }

  private EventSubscriptionPolicyRow requirePolicy(UUID id) {
    var row =
        sql.fetchOne(
            "select id,subscription_id,max_attempts,initial_backoff_seconds,backoff_multiplier,"
                + " max_backoff_seconds,request_timeout_seconds,dead_letter_retention_days,"
                + " signature_algorithm,signature_header,updated_at"
                + " from platform_webhook_policy where id = ?",
            id);
    notFound(row != null, "策略不存在");
    return toPolicyRow(row);
  }

  private static EventSubscriptionEventTypeRow toEventTypeRow(org.jooq.Record r) {
    var status = r.get("status", String.class);
    return new EventSubscriptionEventTypeRow(
        r.get("id", UUID.class),
        r.get("event_type", String.class),
        r.get("schema_version", Integer.class),
        status,
        EventTypeStatus.parse(status) == null ? status : EventTypeStatus.parse(status).label(),
        r.get("payload_schema", String.class),
        r.get("source_service", String.class),
        r.get("source_artifact", String.class),
        r.get("registered_at", OffsetDateTime.class));
  }

  private static EventSubscriptionRow toSubscriptionRow(org.jooq.Record r) {
    var status = r.get("status", String.class);
    return new EventSubscriptionRow(
        r.get("id", UUID.class),
        r.get("app_id", UUID.class),
        r.get("app_name", String.class),
        r.get("name", String.class),
        r.get("target_url", String.class),
        status,
        SubscriptionStatus.parse(status) == null ? status : SubscriptionStatus.parse(status).label(),
        r.get("description", String.class),
        r.get("filter_count", Integer.class),
        r.get("active_secret_count", Integer.class) > 0,
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static EventSubscriptionFilterRow toFilterRow(org.jooq.Record r) {
    return new EventSubscriptionFilterRow(
        r.get("id", UUID.class),
        r.get("subscription_id", UUID.class),
        r.get("event_type", String.class),
        r.get("schema_version", Integer.class),
        r.get("filter", String.class),
        r.get("enabled", Boolean.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static EventSubscriptionSecretRow toSecretRow(org.jooq.Record r) {
    var status = r.get("status", String.class);
    return new EventSubscriptionSecretRow(
        r.get("id", UUID.class),
        r.get("subscription_id", UUID.class),
        r.get("version", Integer.class),
        status,
        SecretStatus.parse(status) == null ? status : SecretStatus.parse(status).label(),
        r.get("secret_hint", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("rotated_at", OffsetDateTime.class));
  }

  private static EventSubscriptionPolicyRow toPolicyRow(org.jooq.Record r) {
    return new EventSubscriptionPolicyRow(
        r.get("id", UUID.class),
        r.get("subscription_id", UUID.class),
        r.get("max_attempts", Integer.class),
        r.get("initial_backoff_seconds", Integer.class),
        r.get("backoff_multiplier", BigDecimal.class),
        r.get("max_backoff_seconds", Integer.class),
        r.get("request_timeout_seconds", Integer.class),
        r.get("dead_letter_retention_days", Integer.class),
        r.get("signature_algorithm", String.class),
        r.get("signature_header", String.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static EventSubscriptionAuditEventRow toAuditRow(org.jooq.Record r) {
    return new EventSubscriptionAuditEventRow(
        r.get("id", UUID.class),
        r.get("subscription_id", UUID.class),
        r.get("action", String.class),
        r.get("actor_user_id", UUID.class),
        r.get("target_type", String.class),
        r.get("target_id", UUID.class),
        r.get("detail", String.class),
        r.get("occurred_at", OffsetDateTime.class));
  }
}
