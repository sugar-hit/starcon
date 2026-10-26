package com.starcon.identity;

import static com.starcon.identity.ExternalPushModels.ExternalChannel;
import static com.starcon.identity.ExternalPushModels.ExternalChannelInput;
import static com.starcon.identity.ExternalPushModels.ExternalChannelList;
import static com.starcon.identity.ExternalPushModels.ExternalChannelOption;
import static com.starcon.identity.ExternalPushModels.ExternalDelivery;
import static com.starcon.identity.ExternalPushModels.ExternalDeliveryList;
import static com.starcon.identity.ExternalPushModels.ExternalPushCapabilities;
import static com.starcon.identity.ExternalPushModels.ExternalPushCandidate;
import static com.starcon.identity.ExternalPushModels.ExternalPushCandidates;
import static com.starcon.identity.ExternalPushModels.ExternalPushCapabilityDimension;
import static com.starcon.identity.ExternalPushModels.ExternalPushRequest;
import static com.starcon.identity.ExternalPushModels.ExternalPushResult;
import static com.starcon.identity.ExternalPushModels.ExternalRetrySweep;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 1.3.2.2 外部渠道推送 —— 渠道登记、候选选取、投递与重试。
 *
 * <p>蓝图原文：「把选定的通知推送到企业 IM（企业微信/钉钉/飞书）或邮件，
 * 支持卡片化消息与点击跳转。」「**输出**：外部渠道投递记录（成功/失败/重试）；
 * 失败自动重试并告警。」
 *
 * <p>🔴 <b>本模块最大的纪律是「不撒谎」。</b>
 *
 * <ul>
 *   <li><b>凭据不落库</b>：渠道只存 secret manager 的**引用串**，
 *       {@code https://…} 这类地址本体在服务层与数据库层各被拒一道；</li>
 *   <li><b>投递结果如实</b>：外部通道未接通 ⇒ 每次尝试都**真的失败**，
 *       落成一条带失败码、失败原因、重试排期与告警的投递记录 ——
 *       绝不返回一条「已投递」了事；</li>
 *   <li><b>模板管理不在本模块</b>（18.5.1.1 未交付）⇒ 能力声明里如实标
 *       {@code templateAvailable=false}，**不造一个模板表来顶替**。</li>
 * </ul>
 *
 * <p><b>租户与成员都来自已认证会话</b>：两者都不是请求参数，更不是请求头。
 * 伪造 {@code X-Tenant-Id} 头改不动 {@code app.current_tenant}。
 *
 * <p><b>上游</b>：1.3.1.2 通知偏好（渠道过滤 + 免打扰时段，走
 * {@link NotificationPreferenceService#resolve}）与 1.3.2.1 预警命中
 * （候选清单，走 {@code alert_rule_hit} 的**只读**查询）。
 *
 * <p><b>下游</b>：外部 IM / 邮件 —— 走 {@link ExternalChannelDeliveryPort}，
 * 当前实现 {@link UnavailableExternalChannelDeliveryPort} 如实答「未接通」。
 */
@Service
public class ExternalPushService {

  /** secret manager（凭据引用解析）的归属模块。 */
  public static final String SECRET_MANAGER_OWNER_MODULE = UnavailableSecretReferencePort.OWNER_MODULE;

  /** 外部投递通道的归属模块。 */
  public static final String DELIVERY_OWNER_MODULE =
      UnavailableExternalChannelDeliveryPort.OWNER_MODULE;

  /** 消息模板管理的归属模块（18.5.1.1，未交付）。 */
  public static final String TEMPLATE_OWNER_MODULE = "18.5.1.1 模板管理（support 服务）";

  /** secret manager 未接通时的说明。 */
  public static final String SECRET_MANAGER_NOTE =
      "secret manager **未接通**：本仓没有 secret manager 服务，"
          + "所以渠道上登记的引用串**解析不出凭据**。库里只落引用串，凭据本体既不落库、"
          + "也不会被伪造 —— 投递会如实失败并记下失败原因（SECRETS_UNRESOLVED）。";

  /** 外部投递通道未接通时的说明。 */
  public static final String DELIVERY_NOTE =
      "外部投递通道 **未接通**：本仓没有任何真实的企业微信 / 钉钉 / 飞书 / 邮件客户端。"
          + "每一次投递尝试都会**真的失败**，并落成一条带失败码（DELIVERY_CHANNEL_UNCONNECTED）、"
          + "失败原因、重试排期与告警的投递记录 —— 绝不返回「已投递」。";

  /** 模板管理未交付时的说明。 */
  public static final String TEMPLATE_NOTE =
      "消息模板管理（18.5.1.1）**未交付**：本模块**不造模板表**。"
          + "投递记录里存的是**发出当时的内容快照**，没有「模板版本」这个概念 —— "
          + "模板一旦交付，快照会变成「模板版本 + 渲染结果」两列，历史仍然解释得清。";

  /** 失败码：secret manager 未接通，引用解析不出凭据。 */
  public static final String FAILURE_SECRETS_UNRESOLVED = "SECRETS_UNRESOLVED";

  /** 失败码：投递通道未接通。 */
  public static final String FAILURE_CHANNEL_UNCONNECTED = "DELIVERY_CHANNEL_UNCONNECTED";

  /** 失败码：渠道已停用或已被删除。 */
  public static final String FAILURE_CHANNEL_DISABLED = "CHANNEL_DISABLED";

  /** 渠道 id 段的列清单（读一行用）。 */
  private static final String CHANNEL_COLUMNS =
      "id, tenant_id, channel_code, display_name, secret_ref, card_enabled, link_enabled, status,"
          + " created_by, created_at, updated_at";

  /** 投递记录 id 段的列清单。 */
  private static final String DELIVERY_COLUMNS =
      "id, channel_id, channel_code, channel_name, recipient_membership_id, source_kind,"
          + " source_ref, notification_type, notification_ref, title, body, link_url, card_style,"
          + " status, attempt_count, max_attempts, failure_code, failure_message, next_attempt_at,"
          + " first_attempt_at, last_attempt_at, delivered_at, alert_state, alerted_at,"
          + " alert_message, created_at";

  private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

  private final DSLContext sql;
  private final ExternalChannelDeliveryPort delivery;
  private final SecretReferencePort secrets;
  private final NotificationPreferenceService preferences;

  public ExternalPushService(
      DSLContext sql,
      ExternalChannelDeliveryPort delivery,
      SecretReferencePort secrets,
      NotificationPreferenceService preferences) {
    this.sql = sql;
    this.delivery = delivery;
    this.secrets = secrets;
    this.preferences = preferences;
  }

  // ==========================================================================
  // 一、能力声明
  // ==========================================================================

  /** 能力声明：渠道词表 + 三个「通没接通」+ 逐条边界。 */
  public ExternalPushCapabilities capabilities() {
    Map<String, ExternalChannelDeliveryPort.Availability> deliveryAvail =
        new LinkedHashMap<>();
    for (ExternalChannelDeliveryPort.Availability availability : delivery.availability()) {
      deliveryAvail.put(availability.channelCode(), availability);
    }
    Map<String, SecretReferencePort.Availability> secretAvail = new LinkedHashMap<>();
    for (SecretReferencePort.Availability availability : secrets.availability()) {
      secretAvail.put(availability.channelCode(), availability);
    }

    List<ExternalChannelOption> options = new ArrayList<>();
    boolean deliveryAvailable = false;
    for (String code : ExternalPushRules.CHANNEL_CODES) {
      ExternalChannelDeliveryPort.Availability availability = deliveryAvail.get(code);
      boolean connected = availability != null && availability.connected();
      if (connected) deliveryAvailable = true;
      options.add(
          new ExternalChannelOption(
              code,
              channelLabel(code),
              channelNote(code),
              connected,
              connected
                  ? null
                  : (availability == null ? "通道状态未知" : availability.reason())));
    }
    boolean secretAvailable = secrets.available();

    List<ExternalPushCapabilityDimension> dimensions =
        List.of(
            new ExternalPushCapabilityDimension(
                "channelRegistration",
                true,
                "1.3.2.2（identity 服务）",
                "渠道登记真的落 PostgreSQL：渠道码、显示名、卡片与跳转开关、启停都是真数据。",
                List.of()),
            new ExternalPushCapabilityDimension(
                "credentialStorage",
                true,
                "1.3.2.2（identity 服务）",
                "库里**只有 secret manager 引用串**：https:// 开头的地址本体在服务层与数据库层各被拒一道，"
                    + "凭据落库这件事被堵死在写入路径上。",
                List.of()),
            new ExternalPushCapabilityDimension(
                "secretResolution",
                secretAvailable,
                SECRET_MANAGER_OWNER_MODULE,
                "引用解析不出凭据 ⇒ 投递会在解析这一步就失败（SECRETS_UNRESOLVED），"
                    + "不会走到真正的 HTTP 调用。",
                List.of("secret manager 服务（K8s External Secrets / Vault）")),
            new ExternalPushCapabilityDimension(
                "externalDelivery",
                deliveryAvailable,
                DELIVERY_OWNER_MODULE,
                "外部 IM / 邮件通道未接通 ⇒ 每一次投递尝试都真的失败，并如实落成投递记录。",
                List.of("企业微信 / 钉钉 / 飞书 / 邮件的外部通道适配器")),
            new ExternalPushCapabilityDimension(
                "deliveryRecords",
                true,
                "1.3.2.2（identity 服务）",
                "投递记录真的落库：成功 / 失败 / 重试 / 推迟 / 告警各自有独立状态与时刻，"
                    + "失败重试与免打扰到期共用同一条「到期扫描」。",
                List.of()),
            new ExternalPushCapabilityDimension(
                "messageTemplates",
                false,
                TEMPLATE_OWNER_MODULE,
                "模板管理未交付 ⇒ 投递记录里存的是**内容快照**，没有「模板版本」这个概念。",
                List.of("18.5.1.1 模板管理")),
            new ExternalPushCapabilityDimension(
                "templateDeliveryStats",
                false,
                "18.5.2.1 投递记录（support 服务）",
                "模板维度的触达率与退订统计未交付；本模块只记**本租户自己的渠道投递尝试事实**。",
                List.of("18.5.2.1 投递记录（统计侧）")));

    List<String> boundaries =
        List.of(
            "只做「外部渠道推送」这一格：消息模板管理（18.5.1.1）与模板维度的投递统计（18.5.2.1）都不在本模块。",
            "凭据不落库：渠道只存 secret manager 引用串；本仓没有 secret manager，引用解析不了，投递会如实失败。",
            "外部 IM / 邮件通道未接通：投递结果**如实为失败**，绝不返回假成功。",
            "自动重试由**外部调度**触发（本服务不装 @Scheduled）：多副本下自带定时器要做主节点选举与分布式锁。",
            "候选清单只列 1.3.2.1 的**真命中**（resolution='HIT'）；UNDECIDABLE 不是命中，不进候选。",
            "NOTIFICATION 来源的候选不在后端清单里：通知内容是各域的只读投影，由前端聚合层取走后提交推送。",
            "规则命中不带通知类型 ⇒ 推送时由操作人选定（受控词表，1.3.1.1 那一份）。",
            "请求体不接受 tenantId / userId：租户来自已认证的成员关系，X-Tenant-Id 头无效。");

    return new ExternalPushCapabilities(
        "1.3.2.2",
        options,
        secretAvailable,
        SECRET_MANAGER_OWNER_MODULE,
        SECRET_MANAGER_NOTE,
        deliveryAvailable,
        DELIVERY_OWNER_MODULE,
        DELIVERY_NOTE,
        false,
        TEMPLATE_OWNER_MODULE,
        TEMPLATE_NOTE,
        ExternalPushRules.CARD_STYLES,
        dimensions,
        boundaries);
  }

  // ==========================================================================
  // 二、渠道登记
  // ==========================================================================

  /** 渠道清单（租户级）。 */
  public ExternalChannelList listChannels(UUID tenantId) {
    List<ExternalChannel> channels = new ArrayList<>();
    for (Record record :
        sql.fetch(
            "select " + CHANNEL_COLUMNS + " from external_push_channel"
                + " where tenant_id = ?::uuid order by channel_code",
            tenantId)) {
      channels.add(toChannel(record));
    }
    int enabled = 0;
    for (ExternalChannel channel : channels) {
      if ("ENABLED".equals(channel.status())) enabled++;
    }
    return new ExternalChannelList(channels, enabled, channels.size());
  }

  /** 登记一个外部渠道（**只落 secret manager 引用**）。 */
  public ExternalChannel createChannel(
      UUID tenantId, UUID membershipId, ExternalChannelInput input) {
    if (input == null) {
      throw badRequest("请求体不能为空");
    }
    String channelCode = ExternalPushRules.normalizeChannelCode(input.channelCode());
    String displayName = ExternalPushRules.normalizeDisplayName(input.displayName());
    String secretRef = ExternalPushRules.normalizeSecretRef(input.secretRef());
    boolean cardEnabled = input.cardEnabled() == null ? true : input.cardEnabled();
    boolean linkEnabled = input.linkEnabled() == null ? true : input.linkEnabled();
    String status =
        input.status() == null ? "ENABLED" : ExternalPushRules.normalizeChannelStatus(input.status());

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    UUID id = UUID.randomUUID();
    try {
      Record row =
          sql.fetchOne(
              "insert into external_push_channel"
                  + "(id,tenant_id,channel_code,display_name,secret_ref,card_enabled,link_enabled,"
                  + "status,created_by,created_at,updated_at)"
                  + " values (?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::boolean,?::boolean,"
                  + "?::varchar,?::uuid,?::timestamptz,?::timestamptz)"
                  + " returning "
                  + CHANNEL_COLUMNS,
              id,
              tenantId,
              channelCode,
              displayName,
              secretRef,
              cardEnabled,
              linkEnabled,
              status,
              membershipId,
              now,
              now);
      return toChannel(row);
    } catch (RuntimeException e) {
      throw translateUniqueViolation(e, "这个租户已经登记过「" + channelLabel(channelCode) + "」渠道了");
    }
  }

  /** 改一个渠道（**逐字段覆盖**：给了的覆盖，没给的保持）。 */
  public ExternalChannel updateChannel(
      UUID tenantId, UUID membershipId, UUID id, ExternalChannelInput input) {
    if (input == null) {
      throw badRequest("请求体不能为空");
    }
    ChannelRow current = requireChannelRow(tenantId, id);
    String channelCode =
        input.channelCode() == null
            ? current.channelCode()
            : ExternalPushRules.normalizeChannelCode(input.channelCode());
    String displayName =
        input.displayName() == null
            ? current.displayName()
            : ExternalPushRules.normalizeDisplayName(input.displayName());
    String secretRef =
        input.secretRef() == null
            ? current.secretRef()
            : ExternalPushRules.normalizeSecretRef(input.secretRef());
    boolean cardEnabled = input.cardEnabled() == null ? current.cardEnabled() : input.cardEnabled();
    boolean linkEnabled = input.linkEnabled() == null ? current.linkEnabled() : input.linkEnabled();
    String status =
        input.status() == null
            ? current.status()
            : ExternalPushRules.normalizeChannelStatus(input.status());

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    try {
      Record row =
          sql.fetchOne(
              "update external_push_channel set channel_code = ?::varchar, display_name = ?::varchar,"
                  + " secret_ref = ?::varchar, card_enabled = ?::boolean, link_enabled = ?::boolean,"
                  + " status = ?::varchar, updated_at = ?::timestamptz"
                  + " where tenant_id = ?::uuid and id = ?::uuid"
                  + " returning "
                  + CHANNEL_COLUMNS,
              channelCode,
              displayName,
              secretRef,
              cardEnabled,
              linkEnabled,
              status,
              now,
              tenantId,
              id);
      return toChannel(row);
    } catch (RuntimeException e) {
      throw translateUniqueViolation(e, "这个租户已经登记过「" + channelLabel(channelCode) + "」渠道了");
    }
  }

  /**
   * 删一个渠道。
   *
   * <p>🔴 <b>投递历史留着</b>：删渠道不改历史行（历史里有渠道码与渠道名的快照），
   * 所以「上个月投过几次」不会随删渠道一起消失。
   */
  public void deleteChannel(UUID tenantId, UUID id) {
    requireChannelRow(tenantId, id);
    sql.execute(
        "delete from external_push_channel where tenant_id = ?::uuid and id = ?::uuid", tenantId, id);
  }

  // ==========================================================================
  // 三、候选清单（1.3.2.1 的真命中）
  // ==========================================================================

  /**
   * 可推送的候选清单。
   *
   * <p>🔴 <b>只列真命中</b>：{@code alert_rule_hit.resolution='HIT'} 才是命中；
   * {@code UNDECIDABLE} 是「算不出来」，它**不是**命中，推送它没有意义。
   *
   * <p>标题与正文由**服务端按命中事实生成**（规则名 / 度量名 / 观测值 / 阈值），
   * 不是调用方编的 —— 候选清单里的东西必须能在库里对上。
   */
  public ExternalPushCandidates candidates(UUID tenantId, int limit, int offset) {
    int size = ExternalPushRules.clampLimit(limit);
    int from = ExternalPushRules.clampOffset(offset);
    List<ExternalPushCandidate> candidates = new ArrayList<>();
    for (Record record :
        sql.fetch(
            "select id, rule_code, object_type, object_ref, metric_code, observed_value, threshold,"
                + " comparison, change_basis, time_window, timezone, reason, hit_at"
                + " from alert_rule_hit"
                + " where tenant_id = ?::uuid and resolution = 'HIT'"
                + " order by hit_at desc, id desc limit ?::integer offset ?::integer",
            tenantId,
            size,
            from)) {
      candidates.add(toCandidate(record));
    }
    int total =
        sql.fetchOne(
                "select count(*) from alert_rule_hit where tenant_id = ?::uuid and resolution = 'HIT'",
                tenantId)
            .get(0, Integer.class);
    return new ExternalPushCandidates(
        candidates,
        total,
        "候选来自 1.3.2.1 预警规则的真实命中记录（只列 resolution='HIT' 的真命中）。"
            + "NOTIFICATION 来源的候选不在这里：通知内容是各域的只读投影（1.3.1.1 明确「通知是投影」），"
            + "由前端聚合层取走后在这里提交推送。规则命中**不带通知类型**，推送时由操作人选定。");
  }

  // ==========================================================================
  // 四、推送
  // ==========================================================================

  /**
   * 发起一次推送（**一个目标渠道一条投递记录**）。
   *
   * <p>流程：校验 → 解析收件人的偏好（1.3.1.2）→ 逐渠道尝试 → 落投递记录。
   *
   * <p>🔴 <b>偏好过滤是真过滤</b>：收件人没订阅这个渠道 ⇒ 400 拒掉（不落记录）；
   * 落在免打扰时段里 ⇒ 落一条 DEFERRED 记录并把下次尝试排在时段结束那一刻。
   */
  public ExternalPushResult push(UUID tenantId, UUID membershipId, ExternalPushRequest input) {
    if (input == null) {
      throw badRequest("请求体不能为空");
    }
    String sourceKind = ExternalPushRules.normalizeSourceKind(input.sourceKind());
    String notificationType = ExternalPushRules.normalizeNotificationType(input.notificationType());
    String cardStyle = ExternalPushRules.normalizeCardStyle(input.cardStyle());
    List<UUID> channelIds = ExternalPushRules.normalizeChannelIds(input.channelIds());
    String linkUrl = ExternalPushRules.normalizeLinkUrl(input.linkUrl());

    UUID recipientId = input.recipientMembershipId();
    if (recipientId == null) {
      throw badRequest("必须指定收件人：推送要按收件人的通知偏好过滤（1.3.1.2）");
    }
    requireActiveMember(tenantId, recipientId);

    // ---- 消息内容（按来源分别解析）----
    ResolvedSource source =
        "ALERT_HIT".equals(sourceKind)
            ? resolveAlertHitSource(tenantId, input, linkUrl)
            : resolveNotificationSource(input, linkUrl);

    // ---- 偏好过滤（1.3.1.2 的生效值）----
    NotificationPreferenceModels.NotificationPreferenceResolution resolution =
        preferences.resolve(tenantId, recipientId, notificationType);
    List<String> allowed = resolution.channels();

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    List<ExternalDelivery> deliveries = new ArrayList<>();
    int deferred = 0;
    int failed = 0;

    for (UUID channelId : channelIds) {
      ChannelRow channel = requireChannelRow(tenantId, channelId);
      String preferenceChannel = preferenceChannelOf(channel.channelCode());
      if (!allowed.contains(preferenceChannel)) {
        throw badRequest(
            "收件人对「"
                + resolution.label()
                + "」这一类通知没有订阅「"
                + channelLabel(channel.channelCode())
                + "」渠道（生效渠道："
                + String.join(" / ", allowed)
                + "）—— 这是收件人的偏好，不该被绕过。");
      }
      if ("DISABLED".equals(channel.status())) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "「" + channel.displayName() + "」渠道已停用：请先在渠道清单里启用它再推送。");
      }

      // 卡片化与点击跳转是**渠道属性**：渠道不支持卡片就退化成纯文本。
      String effectiveStyle = channel.cardEnabled() ? cardStyle : "TEXT";
      String effectiveLink = channel.linkEnabled() ? source.linkUrl() : null;

      // 免打扰时段只压制外部渠道（站内渠道本模块不走）。
      boolean suppressed =
          resolution.quietNow() && resolution.suppressedChannels().contains(preferenceChannel);
      if (suppressed) {
        OffsetDateTime resumeAt = quietWindowEnd(resolution.quietWindows(), resolution.timezone(), now);
        deliveries.add(
            insertDelivery(
                tenantId,
                channel,
                recipientId,
                source,
                effectiveStyle,
                effectiveLink,
                "DEFERRED",
                0,
                ExternalPushRules.DEFAULT_MAX_ATTEMPTS,
                null,
                null,
                resumeAt,
                null,
                null,
                null,
                "NONE",
                null,
                null,
                now));
        deferred++;
        continue;
      }

      AttemptOutcome attempt =
          attemptDelivery(
              channel,
              recipientId,
              source,
              effectiveStyle,
              effectiveLink,
              now,
              0,
              ExternalPushRules.DEFAULT_MAX_ATTEMPTS);
      deliveries.add(
          insertDelivery(
              tenantId,
              channel,
              recipientId,
              source,
              effectiveStyle,
              effectiveLink,
              attempt.status(),
              attempt.attemptCount(),
              attempt.maxAttempts(),
              attempt.failureCode(),
              attempt.failureMessage(),
              attempt.nextAttemptAt(),
              now,
              attempt.deliveredAt(),
              now,
              attempt.alertState(),
              attempt.alertedAt(),
              attempt.alertMessage(),
              now));
      if (!attempt.delivered()) failed++;
    }

    int accepted = deliveries.size() - deferred;
    return new ExternalPushResult(deliveries, accepted, deferred, failed);
  }

  /** 手工重试一条投递记录（**人工再给一次机会**）。 */
  public ExternalDelivery retry(UUID tenantId, UUID id) {
    DeliveryRow current = requireDeliveryRow(tenantId, id);
    if ("DELIVERED".equals(current.status())) {
      throw badRequest("这条投递已经成功了，重试没有意义");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    ChannelRow channel = loadChannelRow(tenantId, current.channelId());
    if (channel == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "这条投递对应的渠道已被删除，无法重试");
    }
    if ("DISABLED".equals(channel.status())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "「" + channel.displayName() + "」渠道已停用：请先启用它再重试");
    }

    // 免打扰时段对**人工重试**一样有效 —— 那是收件人的偏好，不该被任何入口绕过。
    NotificationPreferenceModels.NotificationPreferenceResolution resolution =
        preferences.resolve(tenantId, current.recipientMembershipId(), current.notificationType());
    String preferenceChannel = preferenceChannelOf(current.channelCode());
    if (resolution.quietNow() && resolution.suppressedChannels().contains(preferenceChannel)) {
      OffsetDateTime resumeAt = quietWindowEnd(resolution.quietWindows(), resolution.timezone(), now);
      sql.execute(
          "update external_push_delivery set status = 'DEFERRED', next_attempt_at = ?::timestamptz,"
              + " updated_at = ?::timestamptz where tenant_id = ?::uuid and id = ?::uuid",
          resumeAt,
          now,
          tenantId,
          id);
      return requireDeliveryRow(tenantId, id).toModel();
    }

    // 人工重试 = 再给一次尝试机会（max_attempts 跟着 attempt_count 走，保证不撞 CHECK）。
    int maxAttempts = Math.max(current.maxAttempts(), current.attemptCount() + 1);
    AttemptOutcome attempt =
        attemptDelivery(
            channel,
            current.recipientMembershipId(),
            sourceOf(current),
            current.cardStyle(),
            current.linkUrl(),
            now,
            current.attemptCount(),
            maxAttempts);
    sql.execute(
        "update external_push_delivery set status = ?::varchar, attempt_count = ?::integer,"
            + " max_attempts = ?::integer, failure_code = ?::varchar, failure_message = ?::varchar,"
            + " next_attempt_at = ?::timestamptz, first_attempt_at = coalesce(first_attempt_at, ?::timestamptz),"
            + " last_attempt_at = ?::timestamptz, delivered_at = ?::timestamptz,"
            + " alert_state = ?::varchar, alerted_at = ?::timestamptz, alert_message = ?::varchar,"
            + " updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        attempt.status(),
        attempt.attemptCount(),
        attempt.maxAttempts(),
        attempt.failureCode(),
        attempt.failureMessage(),
        attempt.nextAttemptAt(),
        now,
        now,
        attempt.deliveredAt(),
        attempt.alertState(),
        attempt.alertedAt(),
        attempt.alertMessage(),
        now,
        tenantId,
        id);
    return requireDeliveryRow(tenantId, id).toModel();
  }

  /**
   * 到期扫描（**自动重试**的入口，由外部调度调用）。
   *
   * <p>🔴 <b>本服务不装 @Scheduled</b>：多副本下自带定时器要做主节点选举与分布式锁，
   * 那是平台能力不是领域能力。外部调度（K8s CronJob）按间隔调一次本入口，
   * 天然单次、可重试、可审计（与本仓 10.4.2.2 巡检同一套纪律）。
   *
   * <p>失败重试与免打扰到期**共用这一个口径**（{@code next_attempt_at <= now()}）：
   * 一个是「该再试一次」，一个是「该去投了」，都是「到点了就动」。
   */
  public ExternalRetrySweep retryDue(UUID tenantId, int limit) {
    int size = ExternalPushRules.clampLimit(limit);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    int scanned = 0;
    int retried = 0;
    int delivered = 0;
    int failed = 0;
    int deferred = 0;

    for (DeliveryRow row : dueDeliveries(tenantId, now, size)) {
      scanned++;
      ChannelRow channel = loadChannelRow(tenantId, row.channelId());
      if (channel == null) {
        // 渠道被删了 ⇒ 没法再投。如实落成「重试耗尽 + 告警」，不假装还在排队。
        failExhausted(tenantId, row, FAILURE_CHANNEL_DISABLED, "渠道已被删除，无法再投递", now);
        failed++;
        continue;
      }
      if ("DISABLED".equals(channel.status())) {
        failExhausted(tenantId, row, FAILURE_CHANNEL_DISABLED, "渠道已停用", now);
        failed++;
        continue;
      }
      if ("DEFERRED".equals(row.status())) {
        // 免打扰还没结束 ⇒ 继续推迟（把下一次排到下一个时段结束的那一刻）。
        OffsetDateTime resumeAt = row.nextAttemptAt();
        if (resumeAt == null || !resumeAt.isAfter(now)) {
          resumeAt = now.plus(Duration.ofMinutes(5));
        }
        sql.execute(
            "update external_push_delivery set next_attempt_at = ?::timestamptz, updated_at = ?::timestamptz"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            resumeAt,
            now,
            tenantId,
            row.id());
        deferred++;
        continue;
      }

      AttemptOutcome attempt =
          attemptDelivery(
              channel,
              row.recipientMembershipId(),
              sourceOf(row),
              row.cardStyle(),
              row.linkUrl(),
              now,
              row.attemptCount(),
              row.maxAttempts());
      retried++;
      if (attempt.delivered()) {
        delivered++;
      } else {
        failed++;
      }
      sql.execute(
          "update external_push_delivery set status = ?::varchar, attempt_count = ?::integer,"
              + " failure_code = ?::varchar, failure_message = ?::varchar,"
              + " next_attempt_at = ?::timestamptz, first_attempt_at = coalesce(first_attempt_at, ?::timestamptz),"
              + " last_attempt_at = ?::timestamptz, delivered_at = ?::timestamptz,"
              + " alert_state = ?::varchar, alerted_at = ?::timestamptz, alert_message = ?::varchar,"
              + " updated_at = ?::timestamptz"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          attempt.status(),
          attempt.attemptCount(),
          attempt.failureCode(),
          attempt.failureMessage(),
          attempt.nextAttemptAt(),
          now,
          now,
          attempt.deliveredAt(),
          attempt.alertState(),
          attempt.alertedAt(),
          attempt.alertMessage(),
          now,
          tenantId,
          row.id());
    }
    return new ExternalRetrySweep(scanned, retried, delivered, failed, deferred);
  }

  // ==========================================================================
  // 五、投递记录清单
  // ==========================================================================

  /** 投递记录的筛选条件（全部可选；空 = 不筛）。 */
  public record DeliveryFilter(
      String status,
      UUID channelId,
      String sourceKind,
      String notificationType,
      UUID recipientMembershipId,
      String alertState) {}

  /** 投递记录清单（**真分页 + 真筛选**，total 是筛选后的总数）。 */
  public ExternalDeliveryList deliveries(
      UUID tenantId, DeliveryFilter filter, int limit, int offset) {
    int size = ExternalPushRules.clampLimit(limit);
    int from = ExternalPushRules.clampOffset(offset);
    StringBuilder where = new StringBuilder(" where tenant_id = ?::uuid");
    List<Object> bindings = new ArrayList<>();
    bindings.add(tenantId);
    if (filter != null) {
      if (filter.status() != null && !filter.status().isBlank()) {
        where.append(" and status = ?::varchar");
        bindings.add(ExternalPushRules.normalizeDeliveryStatus(filter.status()));
      }
      if (filter.channelId() != null) {
        where.append(" and channel_id = ?::uuid");
        bindings.add(filter.channelId());
      }
      if (filter.sourceKind() != null && !filter.sourceKind().isBlank()) {
        where.append(" and source_kind = ?::varchar");
        bindings.add(ExternalPushRules.normalizeSourceKind(filter.sourceKind()));
      }
      if (filter.notificationType() != null && !filter.notificationType().isBlank()) {
        where.append(" and notification_type = ?::varchar");
        bindings.add(ExternalPushRules.normalizeNotificationType(filter.notificationType()));
      }
      if (filter.recipientMembershipId() != null) {
        where.append(" and recipient_membership_id = ?::uuid");
        bindings.add(filter.recipientMembershipId());
      }
      if (filter.alertState() != null && !filter.alertState().isBlank()) {
        where.append(" and alert_state = ?::varchar");
        bindings.add(ExternalPushRules.normalizeAlertState(filter.alertState()));
      }
    }
    List<ExternalDelivery> deliveries = new ArrayList<>();
    for (Record record :
        sql.fetch(
            "select " + DELIVERY_COLUMNS + " from external_push_delivery" + where
                + " order by created_at desc, id desc limit ?::integer offset ?::integer",
            bindingsWithPaging(bindings, size, from))) {
      deliveries.add(toDelivery(record));
    }
    int total =
        sql.fetchOne(
                "select count(*) from external_push_delivery" + where, bindings.toArray())
            .get(0, Integer.class);
    return new ExternalDeliveryList(deliveries, total, size, from);
  }

  /** 追加分页绑定（**顺序必须与 SQL 里的占位符顺序一致**：limit 在 offset 之前）。 */
  private static Object[] bindingsWithPaging(List<Object> bindings, int limit, int offset) {
    List<Object> all = new ArrayList<>(bindings);
    all.add(limit);
    all.add(offset);
    return all.toArray();
  }

  /** 确认一条告警（**只改告警状态，不改投递结果**）。 */
  public ExternalDelivery acknowledgeAlert(UUID tenantId, UUID id) {
    DeliveryRow current = requireDeliveryRow(tenantId, id);
    if ("NONE".equals(current.alertState())) {
      throw badRequest("这条投递没有待确认的告警");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update external_push_delivery set alert_state = 'ACKNOWLEDGED', updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        now,
        tenantId,
        id);
    return requireDeliveryRow(tenantId, id).toModel();
  }

  // ==========================================================================
  // 六、内部：形状
  // ==========================================================================

  /** 库里的一行（渠道）。 */
  private record ChannelRow(
      UUID id,
      UUID tenantId,
      String channelCode,
      String displayName,
      String secretRef,
      boolean cardEnabled,
      boolean linkEnabled,
      String status,
      UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 库里的一行（投递记录）。 */
  private record DeliveryRow(
      UUID id,
      UUID channelId,
      String channelCode,
      String channelName,
      UUID recipientMembershipId,
      String sourceKind,
      String sourceRef,
      String notificationType,
      String notificationRef,
      String title,
      String body,
      String linkUrl,
      String cardStyle,
      String status,
      int attemptCount,
      int maxAttempts,
      String failureCode,
      String failureMessage,
      OffsetDateTime nextAttemptAt,
      OffsetDateTime firstAttemptAt,
      OffsetDateTime lastAttemptAt,
      OffsetDateTime deliveredAt,
      String alertState,
      OffsetDateTime alertedAt,
      String alertMessage,
      OffsetDateTime createdAt) {

    ExternalDelivery toModel() {
      return new ExternalDelivery(
          id,
          channelId,
          channelCode,
          channelName,
          recipientMembershipId,
          sourceKind,
          sourceRef,
          notificationType,
          notificationRef,
          title,
          body,
          linkUrl,
          cardStyle,
          status,
          attemptCount,
          maxAttempts,
          failureCode,
          failureMessage,
          nextAttemptAt,
          firstAttemptAt,
          lastAttemptAt,
          deliveredAt,
          alertState,
          alertedAt,
          alertMessage,
          createdAt);
    }
  }

  /** 已解析的推送来源（标题 / 正文 / 跳转目标都定了）。 */
  private record ResolvedSource(
      String sourceKind,
      String sourceRef,
      String notificationType,
      String notificationRef,
      String title,
      String body,
      String linkUrl) {}

  /** 一次尝试的结果（**还没落库**）。 */
  private record AttemptOutcome(
      boolean delivered,
      String status,
      int attemptCount,
      int maxAttempts,
      String failureCode,
      String failureMessage,
      OffsetDateTime nextAttemptAt,
      OffsetDateTime deliveredAt,
      String alertState,
      OffsetDateTime alertedAt,
      String alertMessage) {}

  // ==========================================================================
  // 七、内部：来源解析
  // ==========================================================================

  /**
   * ALERT_HIT 来源：**标题与正文由服务端按命中事实生成**。
   *
   * <p>调用方只能指定跳转目标 —— 标题正文由服务端算，
   * 这样「推出去的那句话」永远能在命中记录里对上。
   */
  private ResolvedSource resolveAlertHitSource(
      UUID tenantId, ExternalPushRequest input, String linkUrl) {
    if (input.alertHitId() == null) {
      throw badRequest("来源是 ALERT_HIT 时必须给出 alertHitId（1.3.2.1 的命中记录）");
    }
    Record hit =
        sql.fetchOne(
            "select id, rule_code, object_type, object_ref, metric_code, observed_value, threshold,"
                + " comparison, change_basis, time_window, timezone, resolution, reason, hit_at"
                + " from alert_rule_hit where tenant_id = ?::uuid and id = ?::uuid",
            tenantId,
            input.alertHitId());
    if (hit == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "命中记录不存在");
    }
    if (!"HIT".equals(hit.get("resolution", String.class))) {
      throw badRequest(
          "这条命中记录是「算不出来」（UNDECIDABLE），不是命中 —— 推一条算不出结论的通知没有意义。");
    }
    String ruleCode = hit.get("rule_code", String.class);
    String objectType = hit.get("object_type", String.class);
    String objectRef = hit.get("object_ref", String.class);
    BigDecimal observed = hit.get("observed_value", BigDecimal.class);
    BigDecimal threshold = hit.get("threshold", BigDecimal.class);
    String comparison = hit.get("comparison", String.class);

    String notificationRef = hit.get("id", UUID.class).toString();
    return new ResolvedSource(
        "ALERT_HIT",
        notificationRef,
        input.notificationType().trim(),
        notificationRef,
        ruleDisplayName(ruleCode) + " 命中",
        describeHit(ruleCode, objectType, objectRef, hit.get("metric_code", String.class), observed, threshold, comparison),
        linkUrl);
  }

  /** NOTIFICATION 来源：调用方给内容（通知正文是各域的只读投影，服务端拿不到）。 */
  private ResolvedSource resolveNotificationSource(ExternalPushRequest input, String linkUrl) {
    if (input.notificationRef() == null || input.notificationRef().isBlank()) {
      throw badRequest("来源是 NOTIFICATION 时必须给出 notificationRef（上游主键）");
    }
    String notificationRef = ExternalPushRules.normalizeNotificationRef(input.notificationRef());
    String title = ExternalPushRules.normalizeTitle(input.title());
    String body = ExternalPushRules.normalizeBody(input.body());
    return new ResolvedSource(
        "NOTIFICATION",
        notificationRef,
        input.notificationType().trim(),
        notificationRef,
        title,
        body,
        linkUrl);
  }

  /** 把一行投递记录还原成「已解析的来源」。 */
  private ResolvedSource sourceOf(DeliveryRow row) {
    return new ResolvedSource(
        row.sourceKind(),
        row.sourceRef(),
        row.notificationType(),
        row.notificationRef(),
        row.title(),
        row.body(),
        row.linkUrl());
  }

  /** 命中的一句话描述（候选清单与推送正文共用同一套措辞）。 */
  private static String describeHit(
      String ruleCode,
      String objectType,
      String objectRef,
      String metricCode,
      BigDecimal observed,
      BigDecimal threshold,
      String comparison) {
    StringBuilder body = new StringBuilder();
    body.append("规则 ").append(ruleCode);
    if (objectType != null) {
      body.append(" · 对象 ").append(objectType);
      if (objectRef != null) body.append('/').append(objectRef);
    }
    body.append(" · ").append(metricDisplayName(metricCode)).append(' ');
    body.append(observed == null ? "?" : observed.stripTrailingZeros().toPlainString());
    body.append("（阈值 ");
    body.append(threshold == null ? "?" : threshold.stripTrailingZeros().toPlainString());
    body.append(' ').append(comparison).append('）');
    return body.toString();
  }

  // ==========================================================================
  // 八、内部：投递尝试
  // ==========================================================================

  /**
   * 投一次（**结果如实**）。
   *
   * <p>🔴 <b>本方法不写库</b>：它只负责「试一次、算出一个结果」。
   * 写库由调用方决定（首次推送 = 插一行；重试 = 更新同一行）——
   * 重试若也插一行，一条通知投 3 次就会变成 3 行，「触达率」这类统计从一开始就错。
   *
   * @param priorAttempts 已经试过几次（0 = 首次）
   * @param maxAttempts 最大尝试次数（含首次）—— **按这一行的预算判耗尽**，
   *     不能用常量：人工重试会把预算调大，用常量判会让「多给的那一次」立刻算耗尽。
   */
  private AttemptOutcome attemptDelivery(
      ChannelRow channel,
      UUID recipientId,
      ResolvedSource source,
      String cardStyle,
      String linkUrl,
      OffsetDateTime now,
      int priorAttempts,
      int maxAttempts) {
    String failureCode = null;
    String failureMessage = null;
    boolean delivered = false;

    // 🔴 先解引用：secret manager 没接通 ⇒ 这一步就失败，**不会**走到真正的 HTTP 调用。
    SecretReferencePort.Resolution resolution = secrets.resolve(channel.secretRef());
    if (!resolution.resolved()) {
      failureCode = FAILURE_SECRETS_UNRESOLVED;
      failureMessage = resolution.reason();
    } else {
      ExternalChannelDeliveryPort.DeliveryOutcome outcome =
          delivery.deliver(
              new ExternalChannelDeliveryPort.DeliveryRequest(
                  channel.channelCode(),
                  channel.secretRef(),
                  cardStyle,
                  source.title(),
                  source.body(),
                  linkUrl,
                  source.notificationType(),
                  source.notificationRef(),
                  recipientId == null ? null : recipientId.toString()));
      delivered = outcome.delivered();
      failureCode = outcome.failureCode();
      failureMessage = outcome.failureMessage();
    }

    int attemptCount = priorAttempts + 1;
    OffsetDateTime deliveredAt = null;
    OffsetDateTime nextAttemptAt = null;
    String status;
    String alertState = "NONE";
    OffsetDateTime alertedAt = null;
    String alertMessage = null;

    if (delivered) {
      status = "DELIVERED";
      deliveredAt = now;
    } else if (attemptCount >= maxAttempts) {
      status = "FAILED_EXHAUSTED";
      alertState = "RAISED";
      alertedAt = now;
      alertMessage =
          "投递失败且重试已耗尽：" + (failureMessage == null ? "未知原因" : failureMessage);
    } else {
      status = "FAILED";
      nextAttemptAt = ExternalPushRules.nextAttemptAt(now, attemptCount);
    }

    return new AttemptOutcome(
        delivered,
        status,
        attemptCount,
        maxAttempts,
        failureCode,
        failureMessage,
        nextAttemptAt,
        deliveredAt,
        alertState,
        alertedAt,
        alertMessage);
  }

  /** 落一条投递记录。 */
  private ExternalDelivery insertDelivery(
      UUID tenantId,
      ChannelRow channel,
      UUID recipientId,
      ResolvedSource source,
      String cardStyle,
      String linkUrl,
      String status,
      int attemptCount,
      int maxAttempts,
      String failureCode,
      String failureMessage,
      OffsetDateTime nextAttemptAt,
      OffsetDateTime firstAttemptAt,
      OffsetDateTime deliveredAt,
      OffsetDateTime lastAttemptAt,
      String alertState,
      OffsetDateTime alertedAt,
      String alertMessage,
      OffsetDateTime now) {
    UUID id = UUID.randomUUID();
    Record row =
        sql.fetchOne(
            "insert into external_push_delivery"
                + "(id,tenant_id,channel_id,channel_code,channel_name,recipient_membership_id,"
                + "source_kind,source_ref,notification_type,notification_ref,title,body,link_url,"
                + "card_style,status,attempt_count,max_attempts,failure_code,failure_message,"
                + "next_attempt_at,first_attempt_at,last_attempt_at,delivered_at,alert_state,"
                + "alerted_at,alert_message,created_at,updated_at)"
                + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::uuid,?::varchar,?::varchar,"
                + "?::varchar,?::varchar,?::varchar,?::varchar,?::varchar,?::varchar,?::varchar,?::integer,"
                + "?::integer,?::varchar,?::varchar,?::timestamptz,?::timestamptz,?::timestamptz,?::timestamptz,"
                + "?::varchar,?::timestamptz,?::varchar,?::timestamptz,?::timestamptz)"
                + " returning "
                + DELIVERY_COLUMNS,
            id,
            tenantId,
            channel.id(),
            channel.channelCode(),
            channel.displayName(),
            recipientId,
            source.sourceKind(),
            source.sourceRef(),
            source.notificationType(),
            source.notificationRef(),
            source.title(),
            source.body(),
            linkUrl,
            cardStyle,
            status,
            attemptCount,
            maxAttempts,
            failureCode,
            failureMessage,
            nextAttemptAt,
            firstAttemptAt,
            lastAttemptAt,
            deliveredAt,
            alertState,
            alertedAt,
            alertMessage,
            now,
            now);
    return toDelivery(row);
  }

  // ==========================================================================
  // 九、内部：读写
  // ==========================================================================

  /** 读一行渠道（租户内）。 */
  private ChannelRow loadChannelRow(UUID tenantId, UUID id) {
    Record record =
        sql.fetchOne(
            "select " + CHANNEL_COLUMNS + " from external_push_channel"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            tenantId,
            id);
    return record == null ? null : toChannelRow(record);
  }

  /** 读一行渠道，找不到抛 404。 */
  private ChannelRow requireChannelRow(UUID tenantId, UUID id) {
    ChannelRow row = loadChannelRow(tenantId, id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "外部渠道不存在");
    }
    return row;
  }

  /** 读一行投递记录（租户内）。 */
  private DeliveryRow requireDeliveryRow(UUID tenantId, UUID id) {
    Record record =
        sql.fetchOne(
            "select " + DELIVERY_COLUMNS + " from external_push_delivery"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            tenantId,
            id);
    if (record == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "投递记录不存在");
    }
    return toDeliveryRow(record);
  }

  /** 到期的投递记录（**真扫描**，不是拿个 limit 截断前 N 行）。 */
  private List<DeliveryRow> dueDeliveries(UUID tenantId, OffsetDateTime now, int limit) {
    List<DeliveryRow> rows = new ArrayList<>();
    for (Record record :
        sql.fetch(
            "select " + DELIVERY_COLUMNS + " from external_push_delivery"
                + " where tenant_id = ?::uuid and next_attempt_at is not null"
                + " and next_attempt_at <= ?::timestamptz"
                + " order by next_attempt_at asc limit ?::integer",
            tenantId,
            now,
            limit)) {
      rows.add(toDeliveryRow(record));
    }
    return rows;
  }

  /** 收件人必须是本租户的**在职**成员。 */
  private void requireActiveMember(UUID tenantId, UUID membershipId) {
    Record row =
        sql.fetchOne(
            "select id from membership where tenant_id = ?::uuid and id = ?::uuid and status = 'ACTIVE'",
            tenantId,
            membershipId);
    if (row == null) {
      throw badRequest("收件人不是本租户的在职成员");
    }
  }

  // ==========================================================================
  // 十、内部：映射
  // ==========================================================================

  private ChannelRow toChannelRow(Record record) {
    return new ChannelRow(
        record.get("id", UUID.class),
        record.get("tenant_id", UUID.class),
        record.get("channel_code", String.class),
        record.get("display_name", String.class),
        record.get("secret_ref", String.class),
        record.get("card_enabled", Boolean.class),
        record.get("link_enabled", Boolean.class),
        record.get("status", String.class),
        record.get("created_by", UUID.class),
        record.get("created_at", OffsetDateTime.class),
        record.get("updated_at", OffsetDateTime.class));
  }

  private ExternalChannel toChannel(Record record) {
    ChannelRow row = toChannelRow(record);
    boolean resolvable = secrets.available();
    return new ExternalChannel(
        row.id(),
        row.channelCode(),
        row.displayName(),
        row.secretRef(),
        row.cardEnabled(),
        row.linkEnabled(),
        row.status(),
        resolvable,
        resolvable ? null : SECRET_MANAGER_NOTE,
        row.createdBy(),
        row.createdAt(),
        row.updatedAt());
  }

  private DeliveryRow toDeliveryRow(Record record) {
    return new DeliveryRow(
        record.get("id", UUID.class),
        record.get("channel_id", UUID.class),
        record.get("channel_code", String.class),
        record.get("channel_name", String.class),
        record.get("recipient_membership_id", UUID.class),
        record.get("source_kind", String.class),
        record.get("source_ref", String.class),
        record.get("notification_type", String.class),
        record.get("notification_ref", String.class),
        record.get("title", String.class),
        record.get("body", String.class),
        record.get("link_url", String.class),
        record.get("card_style", String.class),
        record.get("status", String.class),
        record.get("attempt_count", Integer.class),
        record.get("max_attempts", Integer.class),
        record.get("failure_code", String.class),
        record.get("failure_message", String.class),
        record.get("next_attempt_at", OffsetDateTime.class),
        record.get("first_attempt_at", OffsetDateTime.class),
        record.get("last_attempt_at", OffsetDateTime.class),
        record.get("delivered_at", OffsetDateTime.class),
        record.get("alert_state", String.class),
        record.get("alerted_at", OffsetDateTime.class),
        record.get("alert_message", String.class),
        record.get("created_at", OffsetDateTime.class));
  }

  private ExternalDelivery toDelivery(Record record) {
    return toDeliveryRow(record).toModel();
  }

  private ExternalPushCandidate toCandidate(Record record) {
    String ruleCode = record.get("rule_code", String.class);
    String objectType = record.get("object_type", String.class);
    String objectRef = record.get("object_ref", String.class);
    BigDecimal observed = record.get("observed_value", BigDecimal.class);
    BigDecimal threshold = record.get("threshold", BigDecimal.class);
    String comparison = record.get("comparison", String.class);
    UUID id = record.get("id", UUID.class);

    return new ExternalPushCandidate(
        "ALERT_HIT",
        id.toString(),
        null,
        id.toString(),
        ruleDisplayName(ruleCode) + " 命中",
        describeHit(
            ruleCode,
            objectType,
            objectRef,
            record.get("metric_code", String.class),
            observed,
            threshold,
            comparison),
        null,
        objectType,
        objectRef,
        record.get("hit_at", OffsetDateTime.class));
  }

  // ==========================================================================
  // 十一、内部：文案与工具
  // ==========================================================================

  /** 渠道码 → 中文名。 */
  public static String channelLabel(String code) {
    return switch (code == null ? "" : code) {
      case "WECHAT_WORK" -> "企业微信";
      case "DINGTALK" -> "钉钉";
      case "FEISHU" -> "飞书";
      case "EMAIL" -> "邮件";
      default -> code;
    };
  }

  /** 渠道码 → 这个渠道能干什么。 */
  private static String channelNote(String code) {
    return switch (code == null ? "" : code) {
      case "WECHAT_WORK" -> "企业微信群机器人（卡片消息 + 点击跳回处理页）";
      case "DINGTALK" -> "钉钉群机器人（卡片消息 + 点击跳回处理页）";
      case "FEISHU" -> "飞书群机器人（卡片消息 + 点击跳回处理页）";
      case "EMAIL" -> "邮件（主题 + 正文 + 跳回处理页的链接）";
      default -> code;
    };
  }

  /** 外部渠道码 → 1.3.1.2 的**偏好渠道码**（IM 三家共用 IM，邮件用 EMAIL）。 */
  public static String preferenceChannelOf(String channelCode) {
    return "EMAIL".equals(channelCode) ? "EMAIL" : "IM";
  }

  /** 规则码 → 规则名（预置规则用目录里的名字，自定义规则回落到规则码本身）。 */
  private static String ruleDisplayName(String ruleCode) {
    if (ruleCode == null) return "预警规则";
    AlertRuleCatalog.PresetRule preset = AlertRuleCatalog.preset(ruleCode);
    return preset != null ? preset.displayName() : ruleCode;
  }

  /** 度量码 → 度量名（复用 1.3.2.1 的指标词表，别处引用不允许重算）。 */
  private static String metricDisplayName(String metricCode) {
    AlertRuleCatalog.MetricDefinition metric = AlertRuleCatalog.metric(metricCode);
    return metric != null ? metric.displayName() : metricCode;
  }

  /**
   * 免打扰时段的**结束时刻**（收件人当地的墙上时间换算成绝对时刻）。
   *
   * <p>🔴 <b>按当地墙上时间算，不按 UTC 算</b>：免打扰时段是「每天 22:00–08:00」
   * 这种**当地**语义，存成 UTC 会在跨时区用户身上差几小时。
   *
   * <p>🔴 <b>与星期无关</b>：这里只做「当地某日的某个时刻 → 绝对时刻」的换算，
   * 没有任何按周 / 按自然周的桶 —— 所以同一段代码在周一到周日的行为完全一致。
   */
  private OffsetDateTime quietWindowEnd(List<String> windows, String timezone, OffsetDateTime now) {
    if (windows == null || windows.isEmpty()) return null;
    ZoneId zone;
    try {
      zone = ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone);
    } catch (RuntimeException e) {
      return null;
    }
    LocalDate date = now.atZoneSameInstant(zone).toLocalDate();
    OffsetDateTime best = null;
    for (String window : windows) {
      int dash = window.indexOf('-');
      if (dash <= 0) continue;
      LocalTime end;
      try {
        end = LocalTime.parse(window.substring(dash + 1), HH_MM);
      } catch (RuntimeException e) {
        continue;
      }
      ZonedDateTime candidate = ZonedDateTime.of(date, end, zone);
      if (!candidate.toOffsetDateTime().isAfter(now)) {
        candidate = ZonedDateTime.of(date.plusDays(1), end, zone);
      }
      OffsetDateTime offset = candidate.toOffsetDateTime();
      if (best == null || offset.isBefore(best)) best = offset;
    }
    return best;
  }

  /** 重试次数耗尽 ⇒ 落成终态并升起告警。 */
  private void failExhausted(
      UUID tenantId, DeliveryRow row, String failureCode, String message, OffsetDateTime now) {
    sql.execute(
        "update external_push_delivery set status = 'FAILED_EXHAUSTED', failure_code = ?::varchar,"
            + " failure_message = ?::varchar, next_attempt_at = null, alert_state = 'RAISED',"
            + " alerted_at = ?::timestamptz, alert_message = ?::varchar, updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        failureCode,
        message,
        now,
        message,
        now,
        tenantId,
        row.id());
  }

  /** 唯一约束冲突 ⇒ 翻成人话（渠道码已被本租户占用）。 */
  private static RuntimeException translateUniqueViolation(RuntimeException e, String message) {
    String text = e.getMessage() == null ? "" : e.getMessage();
    if (text.contains("duplicate key") || text.contains("23505")) {
      return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
    return e;
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
