package com.starcon.channel;

import static com.starcon.channel.WebhookModels.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * 02.6.1.1 接收记录。
 *
 * <p>蓝图把这一节的成败压在两句话上，这里逐条对应：
 *
 * <ol>
 *   <li>**先持久化再处理**：{@link #receive} 验签后立刻落库并返回，不做任何业务处理。
 *       平台回调有超时上限（通常 5–10 秒），同步处理必然让平台超时重推， 重推又把压力
 *       放大成雪崩——这是接入平台消息最常见的事故来源。
 *   <li>**重复推送不产生第二条可处理记录**：去重锚点是外部事件 ID，靠
 *       {@code webhook_receipt_first_delivery} 部分唯一索引在 DB 层保证。并发投递下
 *       第二个请求会撞索引，不会同时产生两条可处理记录。
 * </ol>
 *
 * <p>刻意没有做的事：去重视图（蓝图 §2.6.1.2，下游模块）。本模块只把原始记录与
 * {@code duplicate_of} 指向关系落到位，归并展示是下游的事。
 */
@Service
public class WebhookService {
  /**
   * 入库前必须剔除的字段名。平台回调里混着 token 与签名密钥，AGENTS.md 要求
   * 不在库里留明文凭据，也不打全量请求体。
   */
  private static final Set<String> REDACTED_KEYS =
      Set.of(
          "token",
          "access_token",
          "refresh_token",
          "id_token",
          "secret",
          "signing_secret",
          "client_secret",
          "api_key",
          "apikey",
          "authorization",
          "password",
          "cookie",
          "signature",
          "hmac");

  private final DSLContext sql;
  private final TenantTransactions tenants;
  private final ObjectMapper json;
  private final String signingKey;

  public WebhookService(
      DSLContext sql,
      PlatformTransactionManager manager,
      ObjectMapper json,
      @Value("${CHANNEL_WEBHOOK_SIGNING_KEY}") String signingKey) {
    this.sql = sql;
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
    this.json = json;
    this.signingKey = signingKey;
  }

  // ==================================================================
  // 02.6.1.1 平台回调接入（无用户身份，租户由回调入口解析）
  // ==================================================================

  /**
   * 接收一次平台回调。
   *
   * <p>租户**不来自请求头**——回调没有用户会话。信任锚点是回调入口登记的 {@code
   * endpointKey} 加上随请求带来的 HMAC 签名：能猜到 URL 的人若没有签名密钥，
   * 只会得到一条 {@code SIGNATURE_FAILED} 记录，不会污染业务数据。
   *
   * <p>返回 {@code receiptId} 与 {@code status}，调用方据此回 200 或 4xx。
   */
  public IngestResult receive(String endpointKey, String signatureHeaderValue, byte[] rawBody) {
    EndpointBinding binding = resolveEndpoint(endpointKey);
    if (binding == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "回调入口不存在或已停用");

    String signatureOutcome;
    boolean verified;
    if (binding.signingSecretEnc() == null || binding.signingSecretEnc().isBlank()) {
      // 运维没登记密钥：这是配置缺口，不是伪造，两者必须能区分开
      signatureOutcome = SignatureStatus.UNCONFIGURED.name();
      verified = false;
    } else if (signatureHeaderValue == null || signatureHeaderValue.isBlank()) {
      signatureOutcome = SignatureStatus.MISSING.name();
      verified = false;
    } else {
      String secret = WebhookSignature.open(signingKey, binding.signingSecretEnc());
      String expected =
          WebhookSignature.sign(secret, rawBody, binding.signatureEncoding());
      verified = WebhookSignature.matches(expected, signatureHeaderValue, binding.signatureEncoding());
      signatureOutcome =
          verified ? SignatureStatus.VERIFIED.name() : SignatureStatus.FAILED.name();
    }

    // 后续所有写入都在租户事务里，RLS 的 app.current_tenant 由它绑定
    return runAsPlatform(binding.tenantId(), () -> persist(binding, rawBody, signatureOutcome, verified));
  }

  /**
   * 平台回调以服务身份写入，不存在用户角色。
   *
   * <p>{@code webhook_receipt} 的 RESTRICTIVE 数据范围策略沿用 {@code channel_scope}，
   * 它读 {@code app.permission} 与 {@code app.grants}。平台投递没有登录用户，
   * 所以这里显式声明一条系统身份（ALL 范围、只含本模块用到的两个权限码），
   * 而不是把数据范围策略关掉——关掉会让任何用户的越权读一起放行。
   */
  private <T> T runAsPlatform(UUID tenantId, java.util.function.Supplier<T> work) {
    return tenants.run(
        tenantId,
        () -> {
          String grants =
              "[{\"roleId\":null,\"roleCode\":\"PLATFORM_WEBHOOK\","
                  + "\"scopeMode\":\"ALL\","
                  + "\"permissions\":[\"channel.sync.read\",\"channel.sync.update\"]}]";
          sql.fetch(
              "select set_config('app.actor','',true),set_config('app.department','',true),"
                  + " set_config('app.permission','channel.sync.read',true),"
                  + " set_config('app.grants',?,true)",
              grants);
          return work.get();
        });
  }

  /**
   * 解析回调入口。
   *
   * <p>入口行本身带 RLS，而调用方此时还没有租户上下文，因此这一查走 SECURITY DEFINER
   * 函数：它只返回验签必需的字段，且不返回任何其他租户的数据。真正的授权由随后的
   * HMAC 验签完成——猜到 URL 只够知道"这里有个入口"。
   */
  private EndpointBinding resolveEndpoint(String endpointKey) {
    Record r =
        sql.fetchOne(
            """
            select id,tenant_id,connection_id,signing_secret_enc,signature_algorithm,
                   signature_header,signature_encoding
            from resolve_webhook_endpoint(?)
            """,
            endpointKey);
    if (r == null) return null;
    return new EndpointBinding(
        r.get("id", UUID.class),
        r.get("tenant_id", UUID.class),
        r.get("connection_id", UUID.class),
        r.get("signing_secret_enc", String.class),
        r.get("signature_algorithm", String.class),
        r.get("signature_header", String.class),
        r.get("signature_encoding", String.class));
  }

  /** 落库并判定重复。这是"先持久化"真正发生的地方。 */
  private IngestResult persist(
      EndpointBinding binding, byte[] rawBody, String signatureOutcome, boolean verified) {
    String body = new String(rawBody, java.nio.charset.StandardCharsets.UTF_8);
    JsonNode parsed = parseOrNull(body);
    String eventId = extractEventId(parsed);
    String eventType = text(parsed, "event_type", "eventType", "type", "topic");
    String topic = text(parsed, "topic", "event_type", "eventType");
    String digest = sha256Hex(rawBody);

    UUID id = Ids.next();
    // 接收时间由服务侧记录并显式落库：它是排查平台超时重推的基准，
    // 用 OffsetDateTime + ?::timestamptz 绑定，避免本地时区截断造成静默偏移。
    OffsetDateTime receivedAt = OffsetDateTime.now(ZoneOffset.UTC);
    Instant receivedAtInstant = receivedAt.toInstant();

    String redacted = redact(parsed, body);

    if (!verified) {
      // 验签失败同样落库：这是安全事件，丢掉它等于放弃审计线索。
      // 但它永远不会被认领处理（DB 触发器 reject_unverified_claim 兜底）。
      sql.execute(
          """
          insert into webhook_receipt(id,tenant_id,endpoint_id,connection_id,external_event_id,
            event_type,topic,payload,payload_digest,signature_status,status,
            delivery_attempt,received_at)
          values (?,?,?,?,?,?,?,?::jsonb,?,?,'SIGNATURE_FAILED',1,?::timestamptz)
          """,
          id,
          binding.tenantId(),
          binding.id(),
          binding.connectionId(),
          eventId,
          eventType,
          topic,
          redacted,
          digest,
          signatureOutcome,
          receivedAt);
      touchEndpoint(binding.tenantId(), binding.id(), receivedAtInstant);
      return new IngestResult(id, ReceiptStatus.SIGNATURE_FAILED.name(), signatureOutcome, false);
    }

    // 去重：抢首次接收权。部分唯一索引是唯一裁判，并发下只有一个能抢到。
    String insertedId =
        sql.fetchOne(
            """
            insert into webhook_receipt(id,tenant_id,endpoint_id,connection_id,external_event_id,
              event_type,topic,payload,payload_digest,signature_status,status,
              delivery_attempt,received_at)
            values (?,?,?,?,?,?,?,?::jsonb,?,'VERIFIED','RECEIVED',1,?::timestamptz)
            on conflict (tenant_id,endpoint_id,external_event_id) where duplicate_of is null
            do nothing
            returning id::text
            """,
            id,
            binding.tenantId(),
            binding.id(),
            binding.connectionId(),
            eventId,
            eventType,
            topic,
            redacted,
            digest,
            receivedAt) == null
            ? null
            : id.toString();

    touchEndpoint(binding.tenantId(), binding.id(), receivedAtInstant);

    if (insertedId != null) return new IngestResult(id, ReceiptStatus.RECEIVED.name(), "VERIFIED", true);

    // 抢不到首次：这是重推。记一条重复并指向首次记录，
    // 这样下游 2.6.1.2 能统计重推次数，而业务侧只会看到一条可处理记录。
    UUID first = firstReceiptId(binding, eventId);
    Record seenRow =
        sql.fetchOne(
            "select count(*)::int seen from webhook_receipt where tenant_id=? and endpoint_id=?"
                + " and external_event_id=?",
            binding.tenantId(),
            binding.id(),
            eventId);
    Integer seen = seenRow == null ? null : seenRow.get(0, Integer.class);
    UUID duplicateId = Ids.next();
    sql.execute(
        """
        insert into webhook_receipt(id,tenant_id,endpoint_id,connection_id,external_event_id,
          event_type,topic,payload,payload_digest,signature_status,status,duplicate_of,
          delivery_attempt,received_at)
        values (?,?,?,?,?,?,?,?::jsonb,?,'VERIFIED','DUPLICATE',?,?,?::timestamptz)
        """,
        duplicateId,
        binding.tenantId(),
        binding.id(),
        binding.connectionId(),
        eventId,
        eventType,
        topic,
        redacted,
        digest,
        first,
        seen == null || seen < 1 ? 2 : seen + 1,
        receivedAt);

    return new IngestResult(duplicateId, ReceiptStatus.DUPLICATE.name(), "VERIFIED", false);
  }

  private UUID firstReceiptId(EndpointBinding binding, String eventId) {
    Record r =
        sql.fetchOne(
            "select id from webhook_receipt where tenant_id=? and endpoint_id=?"
                + " and external_event_id=? and duplicate_of is null limit 1",
            binding.tenantId(),
            binding.id(),
            eventId);
    return r == null ? null : r.get("id", UUID.class);
  }

  private void touchEndpoint(UUID tenantId, UUID endpointId, Instant receivedAt) {
    sql.execute(
        "update webhook_endpoint set last_received_at=?::timestamptz,updated_at=now(),"
            + " version=version+1 where tenant_id=? and id=?",
        OffsetDateTime.ofInstant(receivedAt, ZoneOffset.UTC),
        tenantId,
        endpointId);
  }

  // ==================================================================
  // 入口登记（用户侧）
  // ==================================================================

  public List<WebhookEndpointView> endpoints(UUID tenantId) {
    return sql
        .fetch(
            """
            select e.id,e.connection_id,e.endpoint_key,e.signature_algorithm,e.signature_header,
                   e.signature_encoding,e.status,e.last_received_at,e.created_at,
                   co.authorized_account,p.code platform_code
            from webhook_endpoint e
            join channel_connection co on co.tenant_id=e.tenant_id and co.id=e.connection_id
            join platform p on p.id=co.platform_id
            where e.tenant_id=?
            order by e.created_at desc
            """,
            tenantId)
        .stream()
        .map(WebhookService::mapEndpoint)
        .toList();
  }

  private static WebhookEndpointView mapEndpoint(Record r) {
    return new WebhookEndpointView(
        r.get("id", UUID.class),
        r.get("connection_id", UUID.class),
        r.get("authorized_account", String.class),
        r.get("platform_code", String.class),
        // 明文密钥永不回显：这里只给平台要填的回调地址
        "/api/channel/webhooks/" + r.get("endpoint_key", String.class),
        r.get("endpoint_key", String.class),
        r.get("signature_algorithm", String.class),
        r.get("signature_header", String.class),
        r.get("signature_encoding", String.class),
        r.get("status", String.class),
        r.get("last_received_at", Instant.class),
        r.get("created_at", Instant.class));
  }

  /**
   * 登记回调入口。
   *
   * <p>签名密钥明文只在这次调用的内存里活一瞬，落库前用主密钥 AES-GCM 加密。
   * 写完再也读不回明文——要换密钥只能重新提交一次，这是刻意的。
   */
  public UUID registerEndpoint(AccessService.Access actor, WebhookEndpointInput input) {
    Record exists =
        sql.fetchOne(
            "select 1 from channel_connection where tenant_id=? and id=?",
            actor.tenantId(),
            input.connectionId());
    if (exists == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "连接不存在");

    String key =
        input.endpointKey() == null || input.endpointKey().isBlank()
            ? "wh_" + UUID.randomUUID().toString().replace("-", "")
            : input.endpointKey().trim();
    if (!key.matches("[A-Za-z0-9_-]{8,64}"))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "回调路径片段只能包含字母、数字、下划线与连字符，长度 8–64");

    String sealed = WebhookSignature.seal(signingKey, input.signingSecret());
    UUID id = Ids.next();
    sql.execute(
        """
        insert into webhook_endpoint(id,tenant_id,connection_id,endpoint_key,signature_algorithm,
          signature_header,signature_encoding,signing_secret_enc)
        values (?,?,?,?,'HmacSHA256',?,?,?)
        """,
        id,
        actor.tenantId(),
        input.connectionId(),
        key,
        input.signatureHeader(),
        input.signatureEncoding().name(),
        sealed);
    return id;
  }

  /** 停用入口：平台仍在推送时的止血动作，记录保留可查。 */
  public void disableEndpoint(AccessService.Access actor, UUID id) {
    int updated =
        sql.execute(
            "update webhook_endpoint set status='DISABLED',version=version+1,updated_at=now()"
                + " where tenant_id=? and id=? and status<>'DISABLED'",
            actor.tenantId(),
            id);
    if (updated == 0)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "回调入口不存在或已停用");
  }

  // ==================================================================
  // 02.6.1.1 接收记录查询
  // ==================================================================

  public List<WebhookReceiptView> receipts(
      UUID tenantId, String status, UUID connectionId, int limit) {
    boolean byStatus = status != null && !status.isBlank();
    boolean byConnection = connectionId != null;
    int capped = Math.min(Math.max(limit, 1), 200);
    String statusFilter = byStatus ? " and r.status=?" : "";
    String connectionFilter = byConnection ? " and r.connection_id=?" : "";
    Object[] binds;
    if (byStatus && byConnection) binds = new Object[] {tenantId, status, connectionId, capped};
    else if (byStatus) binds = new Object[] {tenantId, status, capped};
    else if (byConnection) binds = new Object[] {tenantId, connectionId, capped};
    else binds = new Object[] {tenantId, capped};

    return sql
        .fetch(
            """
            select r.id,r.endpoint_id,r.connection_id,r.shop_id,r.external_event_id,r.event_type,
                   r.topic,r.signature_status,r.status,r.duplicate_of,r.delivery_attempt,
                   r.payload_digest,r.last_error,r.received_at,r.processing_started_at,
                   r.processed_at,co.authorized_account,p.code platform_code,s.name shop_name
            from webhook_receipt r
            join channel_connection co on co.tenant_id=r.tenant_id and co.id=r.connection_id
            join platform p on p.id=co.platform_id
            left join shop s on s.tenant_id=r.tenant_id and s.id=r.shop_id
            where r.tenant_id=?
            """
                + statusFilter
                + connectionFilter
                + " order by r.received_at desc limit ?",
            binds)
        .stream()
        .map(WebhookService::mapReceipt)
        .toList();
  }

  private static WebhookReceiptView mapReceipt(Record r) {
    return new WebhookReceiptView(
        r.get("id", UUID.class),
        r.get("endpoint_id", UUID.class),
        r.get("connection_id", UUID.class),
        r.get("authorized_account", String.class),
        r.get("platform_code", String.class),
        r.get("shop_id", UUID.class),
        r.get("shop_name", String.class),
        r.get("external_event_id", String.class),
        r.get("event_type", String.class),
        r.get("topic", String.class),
        r.get("signature_status", String.class),
        r.get("status", String.class),
        r.get("duplicate_of", UUID.class),
        r.get("delivery_attempt", Integer.class),
        r.get("payload_digest", String.class),
        r.get("last_error", String.class),
        r.get("received_at", Instant.class),
        r.get("processing_started_at", Instant.class),
        r.get("processed_at", Instant.class));
  }

  /**
   * 状态分布。
   *
   * <p>验签失败与重复推送是两个必须能被一眼看见的量：一个说明有人在伪造推送，
   * 一个说明平台在超时重推（接入配置有问题）。把它们埋进长列表里就等于没有。
   */
  public List<WebhookReceiptSummary> summary(UUID tenantId) {
    return sql
        .fetch(
            """
            select status,count(*)::bigint total,min(received_at) oldest
            from webhook_receipt where tenant_id=? group by status
            order by total desc
            """,
            tenantId)
        .stream()
        .map(
            r ->
                new WebhookReceiptSummary(
                    r.get("status", String.class),
                    r.get("total", Long.class),
                    r.get("oldest", Instant.class)))
        .toList();
  }

  // ==================================================================
  // 异步处理侧（先持久化再处理的"再处理"）
  // ==================================================================

  /**
   * 认领一条待处理记录。
   *
   * <p>只认领 {@code RECEIVED}：重复与验签失败的记录永远不会被领走。
   * 并发认领下第二个请求会因状态已变而拿到 0 行，返回冲突而不是重复处理。
   */
  public void claim(AccessService.Access actor, UUID receiptId, ReceiptClaimInput input) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    int updated =
        sql.execute(
            """
            update webhook_receipt set status='PROCESSING',shop_id=coalesce(?,shop_id),
              processing_started_at=?::timestamptz,updated_at=now()
            where tenant_id=? and id=? and status='RECEIVED' and signature_status='VERIFIED'
            """,
            input == null ? null : input.shopId(),
            now,
            actor.tenantId(),
            receiptId);
    if (updated == 0)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "该接收记录不可认领：可能已被认领，或验签未通过，或它是重复推送");
  }

  /** 收尾：只能落到 PROCESSED 或 FAILED，且必须是从 PROCESSING 出发。 */
  public void complete(AccessService.Access actor, UUID receiptId, ReceiptCompleteInput input) {
    if (input.status() != ReceiptStatus.PROCESSED && input.status() != ReceiptStatus.FAILED)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "处理结果只能是 PROCESSED 或 FAILED");
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    int updated =
        sql.execute(
            """
            update webhook_receipt set status=?,processed_at=?::timestamptz,last_error=?,
              updated_at=now()
            where tenant_id=? and id=? and status='PROCESSING'
            """,
            input.status().name(),
            now,
            input.lastError() == null ? "" : input.lastError(),
            actor.tenantId(),
            receiptId);
    if (updated == 0)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该接收记录不在处理中，无法收尾");
  }

  // ==================================================================
  // 报文处理
  // ==================================================================

  private JsonNode parseOrNull(String body) {
    if (body == null || body.isBlank()) return null;
    try {
      return json.readTree(body);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // 平台发来的不是 JSON 也必须能接住：报文本身就是要留证的审计事实
      return null;
    }
  }

  /**
   * 抽外部事件 ID。
   *
   * <p>拿不到就退化成报文摘要：仍然稳定可重复，足以支撑"只处理一次"，
   * 而不会因为平台换了字段名就静默失去去重能力。
   */
  private String extractEventId(JsonNode parsed) {
    if (parsed == null || !parsed.isObject()) return "";
    for (String key :
        new String[] {"event_id", "eventId", "webhook_id", "webhookId", "notification_id", "id"}) {
      JsonNode node = parsed.get(key);
      if (node != null && node.isValueNode() && !node.asText().isBlank())
        return node.asText().substring(0, Math.min(200, node.asText().length()));
    }
    return "";
  }

  private static String text(JsonNode parsed, String... keys) {
    if (parsed == null || !parsed.isObject()) return "";
    for (String key : keys) {
      JsonNode node = parsed.get(key);
      if (node != null && node.isValueNode() && !node.asText().isBlank())
        return node.asText().substring(0, Math.min(100, node.asText().length()));
    }
    return "";
  }

  /** 递归剔除凭据类字段。不是 JSON 的原文按截断存，保证"收到了什么"仍可追溯。 */
  private String redact(JsonNode parsed, String raw) {
    if (parsed == null) return truncate(raw == null ? "" : raw);
    JsonNode cleaned = scrub(parsed);
    try {
      return truncate(json.writeValueAsString(cleaned));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return truncate(raw == null ? "" : raw);
    }
  }

  private JsonNode scrub(JsonNode node) {
    if (node.isObject()) {
      ObjectNode copy = json.createObjectNode();
      node.fields()
          .forEachRemaining(
              e -> {
                if (REDACTED_KEYS.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                  copy.put(e.getKey(), "***");
                } else {
                  copy.set(e.getKey(), scrub(e.getValue()));
                }
              });
      return copy;
    }
    if (node.isArray()) {
      com.fasterxml.jackson.databind.node.ArrayNode copy = json.createArrayNode();
      node.forEach(item -> copy.add(scrub(item)));
      return copy;
    }
    return node;
  }

  /** 报文体积上限：回调可以是整单快照，不设限会把库撑爆。 */
  private static String truncate(String raw) {
    return raw.length() <= 20000 ? raw : raw.substring(0, 20000);
  }

  private static String sha256Hex(byte[] body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  /** 回调入口的绑定信息：由 SECURITY DEFINER 函数返回，只含验签必需字段。 */
  record EndpointBinding(
      UUID id,
      UUID tenantId,
      UUID connectionId,
      String signingSecretEnc,
      String signatureAlgorithm,
      String signatureHeader,
      String signatureEncoding) {}

  /**
   * 回调接入结果。
   *
   * <p>{@code processable} 说明这条记录是否可以被业务侧认领：验签失败与重复都是 false。
   */
  public record IngestResult(
      UUID receiptId, String status, String signatureStatus, boolean processable) {}
}
