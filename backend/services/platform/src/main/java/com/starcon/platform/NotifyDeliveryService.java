package com.starcon.platform;

import static com.starcon.platform.NotifyDeliveryModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 18.5.2.1「投递记录」的服务层。
 *
 * <p>数据访问一律走 <b>jOOQ 显式 SQL</b>（AGENTS.md：不用 JPA 懒加载掩盖关键逻辑）。
 * 租户上下文由 {@link AccessService#run} 绑定 —— 本类<b>不</b>读任何请求头里的租户。
 *
 * <p><b>七条纪律：</b>
 *
 * <ol>
 *   <li><b>🔴 退订先查后登记</b>：登记投递时逐个收件人先查退订表；命中退订的收件人
 *       <b>不进投递尝试</b>，而是写一行 {@code platform_notify_unsubscribe_block}。
 *   <li><b>🔴 被拦下的收件人进统计</b>：{@code blocked_count} 落在投递记录上，
 *       拦截数也进租户级统计 ⇒ 「因为退订少发了几百条」是<b>看得见</b>的数字。
 *   <li><b>🔴 重试也要重查退订</b>：第一次投递之后收件人才退订的，重试必须被拦下 ——
 *       否则「退订必须被尊重并生效」就只在第一次成立。
 *   <li><b>🔴 触达率的分母不含被拦下的人</b>：分母是「实际投递的人数」，
 *       分子是「触达成功的人数」；分母为 0 时记 0，不记 1、不记 null。
 *   <li><b>🔴 结果是三态不是布尔</b>：{@code DELIVERED / FAILED / PENDING} 分开记 ——
 *       「已受理未出结果」与「尝试过但失败」在布尔位上都是 false。
 *   <li><b>🔴 模板版本是快照</b>：投递记录同时记下 {@code template_version_id} 与
 *       {@code template_key}，模板后来改版也不影响这行记录的事实。
 *   <li><b>数量与比率一律 BigDecimal</b>：库里 {@code NUMERIC(19,6)} / {@code NUMERIC(7,4)}，
 *       本类<b>一个 double 都没有</b>。
 * </ol>
 */
@Service
public class NotifyDeliveryService {

  /** 比率小数位（与库里 {@code NUMERIC(7,4)} 一致）。 */
  static final int RATE_SCALE = 4;

  /** 一次登记最多允许的收件人数（防呆；不限制就可能被一个超大列表拖垮）。 */
  static final int MAX_RECIPIENTS = 5000;

  /** 退订原因的最大长度（与库里的 varchar(500) 对齐）。 */
  static final int MAX_REASON = 500;

  private final DSLContext sql;

  public NotifyDeliveryService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 工具

  private static void check(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  /** 状态冲突（409）：不是「参数不对」，是「当前状态不允许」。 */
  private static void conflict(String message) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  /** 找不到（404）。 */
  private static void notFound(String message) {
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static String required(String value, String label, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(!trimmed.isEmpty(), label + "不能为空");
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  private static String optional(String value, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(trimmed.length() <= max, "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  /** 当前会话绑定的租户（**不**来自请求头）。 */
  private UUID currentTenant() {
    var raw =
        sql.fetchOne("select current_setting('app.current_tenant', true) as t").get(0, String.class);
    check(raw != null && !raw.isBlank(), "缺少可信租户上下文");
    return UUID.fromString(raw);
  }

  /** **DB 时钟**（与落库的时间列同一个钟域）。 */
  private OffsetDateTime dbNow() {
    return sql.fetchOne("select clock_timestamp()").get(0, OffsetDateTime.class);
  }

  /** 比率（分母为 0 时记 0）。 */
  private static BigDecimal rate(BigDecimal numerator, BigDecimal denominator) {
    if (denominator == null || denominator.signum() == 0) return BigDecimal.ZERO.setScale(RATE_SCALE);
    return numerator.divide(denominator, RATE_SCALE, RoundingMode.HALF_UP);
  }

  /** 数量（库里是 {@code NUMERIC(19,6)}，这里补齐到 6 位小数）。 */
  private static BigDecimal count(long raw) {
    return BigDecimal.valueOf(raw).setScale(6, RoundingMode.UNNECESSARY);
  }

  // ================================================================ 能力声明

  /**
   * 18.5.2.1 的能力声明：逐维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 <b>投递执行本身如实答「未交付」</b> —— 真正往渠道发消息的是
   * 1.3.2.2「外部渠道推送」与 15.5.2「触达」（都是<b>下游</b>模块）。
   * 本模块负责<b>登记投递结果、尊重退订、统计触达率</b>，
   * **不冒充**一个不存在的投递执行器。
   */
  public NotifyDeliveryCapabilities capabilities() {
    return new NotifyDeliveryCapabilities(
        List.of(
            new NotifyDeliveryCapability(
                "18.5.2.1",
                "投递记录（模板 / 收件范围 / 渠道 / 状态 / 失败原因）",
                true,
                "platform_notify_delivery_record 表 + /platform/notifications/deliveries 端点；"
                    + "模板走 18.5.1.1 的 platform_notify_template（真 FK）；"
                    + "模板版本是**快照**（template_version_id + template_key 都记下来）",
                "",
                "投递记录的**事实**归本模块；**投递动作**归下游 1.3.2.2 / 15.5.2"),
            new NotifyDeliveryCapability(
                "18.5.2.1",
                "投递日志（逐次尝试）",
                true,
                "platform_notify_delivery_record_attempt 表；attempt_no 在本次投递内全局递增；"
                    + "结果三态 DELIVERED / FAILED / PENDING；status_code = 0 表示没拿到响应",
                "",
                "「已受理未出结果」与「尝试过但失败」必须分开 —— 否则触达率的分母不可信"),
            new NotifyDeliveryCapability(
                "18.5.2.1",
                "🔴 退订登记（先查后登记）",
                true,
                "platform_notify_unsubscribe 表；生效中的退订由**部分唯一索引**兜底"
                    + "（同一收件人 × 渠道 × 范围只许一条）；撤销退订用 revoked_at 软撤销，历史区间留着",
                "",
                "登记退订时**先查**：已生效的直接返回原记录，不产生第二行"),
            new NotifyDeliveryCapability(
                "18.5.2.1",
                "🔴 退订拦截（进统计，不静默丢弃）",
                true,
                "platform_notify_unsubscribe_block 表；命中退订的收件人**不进投递尝试**，"
                    + "改写一行拦截留痕；blocked_count 落在投递记录上并进租户级统计",
                "",
                "拦截行是**证据**：它回答「为什么这个收件人没收到」"),
            new NotifyDeliveryCapability(
                "18.5.2.1",
                "统计（触达率 / 退订率 / 拦截数）",
                true,
                "GET /platform/notifications/deliveries/statistics；"
                    + "触达率 = 已送达 / 实际投递（分母不含被拦下的）；"
                    + "退订率 = 已退订收件人 / 本租户投递过的收件人；拦截数 = 拦截留痕行数",
                "",
                "比率一律 NUMERIC(7,4)，**不用浮点**"),
            new NotifyDeliveryCapability(
                "18.5.2.1",
                "重试请求",
                true,
                "POST /platform/notifications/deliveries/{id}/retry；"
                    + "可指定收件人（为空 = 全部失败 / 待出结果的）；"
                    + "🔴 重试**会重查退订** —— 投递之后才退订的，重试必须被拦下",
                "",
                "重试只登记事实；真正重投由下游渠道执行器做"),
            new NotifyDeliveryCapability(
                "18.5.2.1",
                "投递执行器（真的把消息发出去）",
                false,
                "本模块**不执行投递**：只登记结果。真正的渠道发送是下游 1.3.2.2「外部渠道推送」"
                    + "与 15.5.2「触达」",
                "1.3.2.2 / 15.5.2",
                "本模块的端点是**登记**入口：调用方拿着渠道执行器的真实结果来登记"),
            new NotifyDeliveryCapability(
                "18.5.2.1",
                "上游通知模板",
                true,
                "platform_notify_template（18.5.1.1 已交付）；"
                    + "未指定模板版本时按 activation 表解析**当前生效的那一版**",
                "",
                "模板历史版本不可篡改（18.5.1.1 的纪律），本模块只读它")));
  }

  // ================================================================ 投递记录：读

  /** 投递记录列表（真筛选 + 真关键词 + **全序**排序）。 */
  public List<NotifyDeliveryRow> deliveryRows(
      UUID templateId,
      String channel,
      String status,
      String scopeType,
      String keyword,
      OffsetDateTime from,
      OffsetDateTime to,
      int limit,
      int offset) {

    var tenant = currentTenant();
    var where = new StringBuilder(" where tenant_id = ?");
    var args = new ArrayList<Object>();
    args.add(tenant);

    if (templateId != null) {
      where.append(" and template_id = ?");
      args.add(templateId);
    }
    if (channel != null && !channel.isBlank()) {
      where.append(" and channel = ?");
      args.add(channel.trim().toUpperCase());
    }
    if (status != null && !status.isBlank()) {
      where.append(" and status = ?");
      args.add(status.trim().toUpperCase());
    }
    if (scopeType != null && !scopeType.isBlank()) {
      where.append(" and scope_type = ?");
      args.add(scopeType.trim().toUpperCase());
    }
    if (keyword != null && !keyword.isBlank()) {
      var kw = "%" + keyword.trim() + "%";
      where.append(" and (template_key like ? or scope_ref like ?)");
      args.add(kw);
      args.add(kw);
    }
    if (from != null) {
      where.append(" and created_at >= ?::timestamptz");
      args.add(from);
    }
    if (to != null) {
      where.append(" and created_at <= ?::timestamptz");
      args.add(to);
    }

    var sqlText =
        """
        select id, template_id, template_key, scope_type, scope_ref, channel, status,
               recipient_count, blocked_count, targeted_count, delivered_count, failed_count,
               attempt_count, failure_reason, delivery_rate,
               next_retry_at, first_attempt_at, last_attempt_at, delivered_at,
               created_at, updated_at
          from platform_notify_delivery_record
        """
            + where
            + " order by created_at desc, id desc limit ? offset ?";
    args.add(Math.max(1, Math.min(limit, 500)));
    args.add(Math.max(0, offset));

    return sql.fetch(sqlText, args.toArray()).map(this::deliveryRow);
  }

  /** 一条投递记录（含逐次尝试 + 拦截留痕）。 */
  public NotifyDeliveryDetail deliveryDetail(UUID id) {
    var row = findDeliveryRow(id);
    var tenant = currentTenant();
    var attempts =
        sql.fetch(
                """
                select id, delivery_id, attempt_no, recipient_ref, channel, status_code,
                       outcome, attempt_source, duration_ms, failure_reason, occurred_at
                  from platform_notify_delivery_record_attempt
                 where tenant_id = ? and delivery_id = ?
                 order by attempt_no asc
                """,
                tenant,
                id)
            .map(
                r ->
                    new NotifyAttemptRow(
                        r.get(0, UUID.class),
                        r.get(1, UUID.class),
                        r.get(2, Integer.class),
                        r.get(3, String.class),
                        r.get(4, String.class),
                        r.get(5, Integer.class),
                        r.get(6, String.class),
                        r.get(7, String.class),
                        r.get(8, Long.class),
                        r.get(9, String.class),
                        r.get(10, OffsetDateTime.class)));
    var blocks =
        sql.fetch(
                """
                select id, delivery_id, unsubscribe_id, recipient_ref, channel,
                       unsubscribe_scope_type, unsubscribe_scope_key, blocked_at
                  from platform_notify_unsubscribe_block
                 where tenant_id = ? and delivery_id = ?
                 order by blocked_at asc, id asc
                """,
                tenant,
                id)
            .map(
                r ->
                    new NotifyBlockRow(
                        r.get(0, UUID.class),
                        r.get(1, UUID.class),
                        r.get(2, UUID.class),
                        r.get(3, String.class),
                        r.get(4, String.class),
                        r.get(5, String.class),
                        r.get(6, String.class),
                        r.get(7, OffsetDateTime.class)));
    return new NotifyDeliveryDetail(row, attempts, blocks);
  }

  private NotifyDeliveryRow findDeliveryRow(UUID id) {
    var tenant = currentTenant();
    var found =
        sql.fetch(
            """
            select id, template_id, template_key, scope_type, scope_ref, channel, status,
                   recipient_count, blocked_count, targeted_count, delivered_count, failed_count,
                   attempt_count, failure_reason, delivery_rate,
                   next_retry_at, first_attempt_at, last_attempt_at, delivered_at,
                   created_at, updated_at
              from platform_notify_delivery_record
             where tenant_id = ? and id = ?
            """,
            tenant,
            id);
    if (found.isEmpty()) notFound("投递记录不存在");
    return deliveryRow(found.get(0));
  }

  private NotifyDeliveryRow deliveryRow(org.jooq.Record r) {
    var recipientCount = r.get(8, BigDecimal.class);
    var blockedCount = r.get(9, BigDecimal.class);
    var targetedCount = r.get(10, BigDecimal.class);
    var deliveredCount = r.get(11, BigDecimal.class);
    var failedCount = r.get(12, BigDecimal.class);
    // 待出结果的人数 = 实际投递 − 已送达 − 失败（推导值，不另存一列）。
    var pendingCount = targetedCount.subtract(deliveredCount).subtract(failedCount);
    if (pendingCount.signum() < 0) pendingCount = BigDecimal.ZERO.setScale(6);
    return new NotifyDeliveryRow(
        r.get(0, UUID.class),
        r.get(1, UUID.class),
        r.get(2, String.class),
        r.get(3, String.class),
        r.get(4, String.class),
        r.get(5, String.class),
        r.get(6, String.class),
        recipientCount,
        blockedCount,
        targetedCount,
        deliveredCount,
        failedCount,
        pendingCount,
        r.get(13, Integer.class),
        r.get(14, String.class),
        r.get(15, BigDecimal.class),
        r.get(16, OffsetDateTime.class),
        r.get(17, OffsetDateTime.class),
        r.get(18, OffsetDateTime.class),
        r.get(19, OffsetDateTime.class),
        r.get(20, OffsetDateTime.class),
        r.get(21, OffsetDateTime.class));
  }

  // ================================================================ 投递记录：写

  /**
   * 🔴 登记一次投递（唯一写入口）。
   *
   * <p>流程：查模板 → 解析模板版本 → <b>逐个收件人先查退订</b> →
   * 命中的写拦截留痕、其余写投递尝试 → 重算聚合（触达率 / 状态 / 时间线）。
   */
  public NotifyDeliveryDetail recordDelivery(NotifyDeliveryInput input) {
    var tenant = currentTenant();
    var now = dbNow();

    var channel = NotifyChannel.parse(input.channel());
    check(channel != null, "渠道只能是 EMAIL / SMS / PUSH / IN_APP / WEBHOOK");
    var scopeType = NotifyScopeType.parse(input.scopeType());
    check(scopeType != null, "收件范围类型只能是 SINGLE / SEGMENT / SUBSCRIBERS");
    var scopeRef = optional(input.scopeRef(), 200);
    if (scopeType == NotifyScopeType.SINGLE) {
      check(!scopeRef.isEmpty(), "收件范围为 SINGLE 时必须给出收件人标识");
    }

    check(
        input.recipients() != null && !input.recipients().isEmpty(),
        "至少要给一个收件人：没有收件人的投递记录没有意义");
    check(
        input.recipients().size() <= MAX_RECIPIENTS,
        "一次登记的收件人不能超过 " + MAX_RECIPIENTS + " 个");

    // 模板必须真实存在（真 FK 之外再挡一层，给一句能看懂的话）。
    var template = findTemplate(input.templateId());
    var templateKey = template.get("template_key");
    var scene = template.get("scene");

    // 模板版本：没指定就按 activation 表解析**当前生效的那一版**。
    var versionId = input.templateVersionId();
    if (versionId == null) {
      versionId =
          sql.fetchOne(
                  """
                  select version_id from platform_notify_template_activation
                   where tenant_id = ? and template_id = ? and deactivated_at is null
                   limit 1
                  """,
                  tenant,
                  input.templateId())
              .get(0, UUID.class);
    }

    var deliveryId = UUID.randomUUID();
    sql.execute(
        """
        insert into platform_notify_delivery_record(
          id, tenant_id, template_id, template_version_id, template_key,
          scope_type, scope_ref, channel, status,
          recipient_count, blocked_count, targeted_count, delivered_count, failed_count,
          attempt_count, failure_reason, delivery_rate,
          next_retry_at, first_attempt_at, last_attempt_at, delivered_at,
          created_at, updated_at)
        values(?,?,?,?,?,?,?,?,'PENDING',0,0,0,0,0,0,'',0,null,null,null,null,?::timestamptz,?::timestamptz)
        """,
        deliveryId,
        tenant,
        input.templateId(),
        versionId,
        templateKey,
        scopeType.name(),
        scopeRef,
        channel.name(),
        now,
        now);

    // 逐个收件人：先查退订，命中 ⇒ 拦截留痕（不进尝试）；未命中 ⇒ 写尝试。
    var attemptNo = 0;
    var seen = new LinkedHashSet<String>();
    for (var recipient : input.recipients()) {
      var ref = required(recipient.ref(), "收件人标识", 200);
      if (!seen.add(ref)) continue; // 同一收件人重复出现只算一次
      var outcome = NotifyAttemptOutcome.parse(recipient.outcome());
      if (recipient.outcome() != null && !recipient.outcome().isBlank()) {
        check(outcome != null, "投递结果只能是 DELIVERED / FAILED / PENDING");
      }
      if (outcome == null) outcome = NotifyAttemptOutcome.PENDING;

      var hit = findUnsubscribe(ref, channel.name(), templateKey, scene);
      if (hit != null) {
        insertBlock(deliveryId, hit, ref, channel.name(), now);
        continue;
      }

      attemptNo++;
      var statusCode = recipient.statusCode() == null ? 0 : recipient.statusCode();
      if (outcome == NotifyAttemptOutcome.DELIVERED && statusCode != 0) {
        check(
            statusCode >= 200 && statusCode < 300,
            "标记为已送达时渠道返回码只能是 2xx（或 0 表示无 HTTP 码）");
      }
      if (outcome == NotifyAttemptOutcome.FAILED && statusCode != 0) {
        check(
            statusCode < 200 || statusCode > 299,
            "标记为失败时渠道返回码不能是 2xx");
      }
      insertAttempt(
          deliveryId,
          attemptNo,
          ref,
          channel.name(),
          statusCode,
          outcome,
          NotifyAttemptSource.INITIAL,
          recipient.durationMs() == null ? 0L : recipient.durationMs(),
          optional(recipient.failureReason(), 1000),
          now);
    }

    recompute(deliveryId, null);
    return deliveryDetail(deliveryId);
  }

  /**
   * 🔴 发起一次重试。
   *
   * <p>没点名收件人时，重试全部「失败 / 待出结果」的收件人。
   * <b>重试会重查退订</b>：第一次投递之后才退订的收件人，重试必须被拦下。
   */
  public NotifyDeliveryDetail retry(UUID deliveryId, NotifyRetryInput input) {
    var tenant = currentTenant();
    var now = dbNow();
    findDeliveryRow(deliveryId); // 不存在 ⇒ 404

    var channel = currentChannel(deliveryId);
    var templateKey = currentTemplateKey(deliveryId);
    var scene = currentTemplateScene(deliveryId);

    var targets = new LinkedHashSet<String>();
    if (input.recipients() != null && !input.recipients().isEmpty()) {
      for (var raw : input.recipients()) {
        var ref = required(raw, "收件人标识", 200);
        check(
            knownRecipient(deliveryId, ref),
            "收件人 " + ref + " 不在这条投递记录里");
        targets.add(ref);
      }
    } else {
      for (var ref : retryableRecipients(deliveryId)) targets.add(ref);
    }
    check(!targets.isEmpty(), "没有可重试的收件人（全部已送达或已被退订拦截）");

    var outcomeKnown = input.outcomeKnown() != null && input.outcomeKnown();
    var outcome = NotifyAttemptOutcome.PENDING;
    if (outcomeKnown) {
      var success = input.success() != null && input.success();
      outcome = success ? NotifyAttemptOutcome.DELIVERED : NotifyAttemptOutcome.FAILED;
    }
    var statusCode = input.statusCode() == null ? 0 : input.statusCode();
    if (outcome == NotifyAttemptOutcome.DELIVERED && statusCode != 0) {
      check(
          statusCode >= 200 && statusCode < 300,
          "标记为已送达时渠道返回码只能是 2xx（或 0 表示无 HTTP 码）");
    }
    if (outcome == NotifyAttemptOutcome.FAILED && statusCode != 0) {
      check(statusCode < 200 || statusCode > 299, "标记为失败时渠道返回码不能是 2xx");
    }
    var durationMs = input.durationMs() == null ? 0L : input.durationMs();
    var failureReason = optional(input.failureReason(), 1000);

    var attemptNo = currentMaxAttemptNo(deliveryId);
    var blocked = 0;
    for (var ref : targets) {
      var hit = findUnsubscribe(ref, channel, templateKey, scene);
      if (hit != null) {
        // 🔴 重试也要尊重退订：这一次不投了，记拦截留痕。
        insertBlock(deliveryId, hit, ref, channel, now);
        blocked++;
        continue;
      }
      attemptNo++;
      insertAttempt(
          deliveryId,
          attemptNo,
          ref,
          channel,
          statusCode,
          outcome,
          NotifyAttemptSource.RETRY,
          durationMs,
          failureReason,
          now);
    }
    if (attemptNo == currentMaxAttemptNo(deliveryId)) {
      // 一个都没投出去（全部被退订拦下）—— 不是错误，但要让调用方知道。
      conflict("本次重试的收件人全部命中退订，已拦截，未发出任何通知");
    }

    recompute(deliveryId, outcomeKnown ? null : input.nextRetryAt());
    return deliveryDetail(deliveryId);
  }

  // ================================================================ 退订

  /** 退订列表（真筛选）。 */
  public List<NotifyUnsubscribeRow> unsubscribeRows(
      String recipientRef, String channel, String scopeType, Boolean active, int limit, int offset) {
    var tenant = currentTenant();
    var where = new StringBuilder(" where tenant_id = ?");
    var args = new ArrayList<Object>();
    args.add(tenant);
    if (recipientRef != null && !recipientRef.isBlank()) {
      where.append(" and recipient_ref like ?");
      args.add("%" + recipientRef.trim() + "%");
    }
    if (channel != null && !channel.isBlank()) {
      where.append(" and channel = ?");
      args.add(channel.trim().toUpperCase());
    }
    if (scopeType != null && !scopeType.isBlank()) {
      where.append(" and scope_type = ?");
      args.add(scopeType.trim().toUpperCase());
    }
    if (active != null) {
      where.append(active ? " and revoked_at is null" : " and revoked_at is not null");
    }
    var sqlText =
        """
        select id, recipient_ref, channel, scope_type, scope_key, template_id,
               reason, source, unsubscribed_at, revoked_at, created_at
          from platform_notify_unsubscribe
        """
            + where
            + " order by created_at desc, id desc limit ? offset ?";
    args.add(Math.max(1, Math.min(limit, 500)));
    args.add(Math.max(0, offset));
    return sql.fetch(sqlText, args.toArray()).map(this::unsubscribeRow);
  }

  /**
   * 🔴 登记一条退订（**先查后登记**）。
   *
   * <p>已生效的同一条退订 ⇒ 直接返回原记录，**不产生第二行**。
   * 曾撤销过 ⇒ 落成**新行**（历史区间留着）。
   */
  public NotifyUnsubscribeRow registerUnsubscribe(NotifyUnsubscribeInput input) {
    var tenant = currentTenant();
    var now = dbNow();

    var recipientRef = required(input.recipientRef(), "收件人标识", 200);
    var channel = required(input.channel(), "渠道", 24).toUpperCase();
    check(
        NotifyChannel.parse(channel) != null || channel.equals("ALL"),
        "渠道只能是 EMAIL / SMS / PUSH / IN_APP / WEBHOOK / ALL");
    var scopeType = NotifyUnsubscribeScopeType.parse(input.scopeType());
    check(scopeType != null, "退订范围类型只能是 TEMPLATE / SCENE / GLOBAL");

    var scopeKey = "*";
    UUID templateId = null;
    if (scopeType == NotifyUnsubscribeScopeType.TEMPLATE) {
      check(input.templateId() != null, "退订范围为 TEMPLATE 时必须给出模板");
      var template = findTemplate(input.templateId());
      scopeKey = template.get("template_key");
      templateId = input.templateId();
    } else if (scopeType == NotifyUnsubscribeScopeType.SCENE) {
      scopeKey = required(input.scopeKey(), "退订的场景标识", 64);
    }

    // 🔴 先查：命中生效中的退订 ⇒ 直接返回，不重复登记。
    var existing =
        sql.fetchOne(
            """
            select id, recipient_ref, channel, scope_type, scope_key, template_id,
                   reason, source, unsubscribed_at, revoked_at, created_at
              from platform_notify_unsubscribe
             where tenant_id = ? and recipient_ref = ? and channel = ?
               and scope_type = ? and scope_key = ? and revoked_at is null
             limit 1
            """,
            tenant,
            recipientRef,
            channel,
            scopeType.name(),
            scopeKey);
    if (existing != null) return unsubscribeRow(existing);

    var reason = optional(input.reason(), MAX_REASON);
    var source = NotifyUnsubscribeSource.parse(input.source());
    if (input.source() != null && !input.source().isBlank()) {
      check(source != null, "退订来源只能是 USER_REQUEST / COMPLAINT / ADMIN");
    }
    if (source == null) source = NotifyUnsubscribeSource.USER_REQUEST;

    var id = UUID.randomUUID();
    try {
      sql.execute(
          """
          insert into platform_notify_unsubscribe(
            id, tenant_id, recipient_ref, channel, scope_type, scope_key, template_id,
            reason, source, unsubscribed_at, revoked_at, created_at)
          values(?,?,?,?,?,?,?,?,?,?::timestamptz,null,?::timestamptz)
          """,
          id,
          tenant,
          recipientRef,
          channel,
          scopeType.name(),
          scopeKey,
          templateId,
          reason,
          source.name(),
          now,
          now);
    } catch (org.jooq.exception.DataAccessException e) {
      // 🔴 并发兜底：部分唯一索引挡住了第二条生效中的退订 ⇒ 返回已存在的那条。
      var raced =
          sql.fetchOne(
              """
              select id, recipient_ref, channel, scope_type, scope_key, template_id,
                     reason, source, unsubscribed_at, revoked_at, created_at
                from platform_notify_unsubscribe
               where tenant_id = ? and recipient_ref = ? and channel = ?
                 and scope_type = ? and scope_key = ? and revoked_at is null
               limit 1
              """,
              tenant,
              recipientRef,
              channel,
              scopeType.name(),
              scopeKey);
      if (raced != null) return unsubscribeRow(raced);
      throw e;
    }
    return unsubscribeRow(
        sql.fetchOne(
                """
                select id, recipient_ref, channel, scope_type, scope_key, template_id,
                       reason, source, unsubscribed_at, revoked_at, created_at
                  from platform_notify_unsubscribe
                 where tenant_id = ? and id = ?
                """,
                tenant,
                id));
  }

  /** 撤销退订（软撤销：历史区间留着，只是不再命中拦截）。 */
  public NotifyUnsubscribeRow revokeUnsubscribe(UUID id) {
    var tenant = currentTenant();
    var now = dbNow();
    var updated =
        sql.fetch(
            """
            update platform_notify_unsubscribe
               set revoked_at = ?::timestamptz
             where tenant_id = ? and id = ? and revoked_at is null
            returning id
            """,
            now,
            tenant,
            id);
    if (updated.isEmpty()) {
      var exists =
          sql.fetchOne(
              "select id from platform_notify_unsubscribe where tenant_id = ? and id = ?",
              tenant,
              id);
      if (exists == null) notFound("退订记录不存在");
      conflict("这条退订已经被撤销过了");
    }
    return unsubscribeRow(
        sql.fetchOne(
            """
            select id, recipient_ref, channel, scope_type, scope_key, template_id,
                   reason, source, unsubscribed_at, revoked_at, created_at
              from platform_notify_unsubscribe
             where tenant_id = ? and id = ?
            """,
            tenant,
            id));
  }

  /** 拦截留痕列表。 */
  public List<NotifyBlockRow> blockRows(String recipientRef, UUID deliveryId, int limit, int offset) {
    var tenant = currentTenant();
    var where = new StringBuilder(" where tenant_id = ?");
    var args = new ArrayList<Object>();
    args.add(tenant);
    if (recipientRef != null && !recipientRef.isBlank()) {
      where.append(" and recipient_ref like ?");
      args.add("%" + recipientRef.trim() + "%");
    }
    if (deliveryId != null) {
      where.append(" and delivery_id = ?");
      args.add(deliveryId);
    }
    var sqlText =
        """
        select id, delivery_id, unsubscribe_id, recipient_ref, channel,
               unsubscribe_scope_type, unsubscribe_scope_key, blocked_at
          from platform_notify_unsubscribe_block
        """
            + where
            + " order by blocked_at desc, id desc limit ? offset ?";
    args.add(Math.max(1, Math.min(limit, 500)));
    args.add(Math.max(0, offset));
    return sql.fetch(sqlText, args.toArray())
        .map(
            r ->
                new NotifyBlockRow(
                    r.get(0, UUID.class),
                    r.get(1, UUID.class),
                    r.get(2, UUID.class),
                    r.get(3, String.class),
                    r.get(4, String.class),
                    r.get(5, String.class),
                    r.get(6, String.class),
                    r.get(7, OffsetDateTime.class)));
  }

  // ================================================================ 统计

  /**
   * 🔴 投递统计（触达率 / 退订率 / 拦截数）。
   *
   * <p>触达率 = 已送达人数 / 实际投递人数（分母**不含**被退订拦下的）；
   * 退订率 = 已退订收件人数 / 本租户投递过的收件人数；拦截数 = 拦截留痕行数。
   */
  public NotifyDeliveryStatistics statistics() {
    var tenant = currentTenant();
    var now = dbNow();

    var totals =
        sql.fetchOne(
            """
            select count(*) as deliveries,
                   coalesce(sum(recipient_count),0) as recipients,
                   coalesce(sum(blocked_count),0) as blocked,
                   coalesce(sum(targeted_count),0) as targeted,
                   coalesce(sum(delivered_count),0) as delivered,
                   coalesce(sum(failed_count),0) as failed
              from platform_notify_delivery_record
             where tenant_id = ?
            """,
            tenant);

    var distinctRecipients =
        sql.fetchOne(
                """
                select count(*) from (
                  select recipient_ref from platform_notify_delivery_record_attempt
                   where tenant_id = ?
                  union
                  select recipient_ref from platform_notify_unsubscribe_block
                   where tenant_id = ?
                ) u
                """,
                tenant,
                tenant)
            .get(0, Long.class);

    var unsubscribedRecipients =
        sql.fetchOne(
                """
                select count(*) from (
                  select distinct recipient_ref from platform_notify_unsubscribe
                   where tenant_id = ? and revoked_at is null
                ) u
                """,
                tenant)
            .get(0, Long.class);

    var blockedAttempts =
        sql.fetchOne(
                "select count(*) from platform_notify_unsubscribe_block where tenant_id = ?",
                tenant)
            .get(0, Long.class);

    var deliveries = count(totals.get("deliveries", Long.class));
    var recipients = count(totals.get("recipients", BigDecimal.class).longValue());
    var blocked = count(totals.get("blocked", BigDecimal.class).longValue());
    var targeted = count(totals.get("targeted", BigDecimal.class).longValue());
    var delivered = count(totals.get("delivered", BigDecimal.class).longValue());
    var failed = count(totals.get("failed", BigDecimal.class).longValue());
    var distinct = count(distinctRecipients);
    var unsubscribed = count(unsubscribedRecipients);

    return new NotifyDeliveryStatistics(
        deliveries,
        recipients,
        blocked,
        targeted,
        delivered,
        failed,
        rate(delivered, targeted),
        rate(unsubscribed, distinct),
        distinct,
        unsubscribed,
        count(blockedAttempts),
        now);
  }

  // ================================================================ 内部：查询与写入

  private Map<String, String> findTemplate(UUID templateId) {
    var tenant = currentTenant();
    var found =
        sql.fetch(
            "select template_key, scene from platform_notify_template where tenant_id = ? and id = ?",
            tenant,
            templateId);
    if (found.isEmpty()) notFound("通知模板不存在");
    var out = new LinkedHashMap<String, String>();
    out.put("template_key", found.get(0).get(0, String.class));
    out.put("scene", found.get(0).get(1, String.class));
    return out;
  }

  /**
   * 查这个收件人**当前**是否命中退订。
   *
   * <p>命中规则：渠道相等（或退订登记的是 ALL），
   * 且范围是 GLOBAL / 该模板（TEMPLATE）/ 该场景（SCENE）。
   *
   * @return 命中的退订登记；没命中返回 {@code null}
   */
  private org.jooq.Record findUnsubscribe(
      String recipientRef, String channel, String templateKey, String scene) {
    var tenant = currentTenant();
    return sql.fetchOne(
        """
        select id, channel, scope_type, scope_key
          from platform_notify_unsubscribe
         where tenant_id = ? and recipient_ref = ? and revoked_at is null
           and (channel = ? or channel = 'ALL')
           and (scope_type = 'GLOBAL'
                or (scope_type = 'TEMPLATE' and scope_key = ?)
                or (scope_type = 'SCENE' and scope_key = ?))
         order by case scope_type when 'TEMPLATE' then 0 when 'SCENE' then 1 else 2 end,
                  unsubscribed_at desc
         limit 1
        """,
        tenant,
        recipientRef,
        channel,
        templateKey,
        scene);
  }

  private void insertBlock(
      UUID deliveryId, org.jooq.Record unsubscribe, String recipientRef, String channel, OffsetDateTime now) {
    var tenant = currentTenant();
    var unsubscribeId = unsubscribe.get(0, UUID.class);
    // 同一个收件人在同一次投递里最多记一次拦截（重试不重复计）。
    var already =
        sql.fetchOne(
            """
            select id from platform_notify_unsubscribe_block
             where tenant_id = ? and delivery_id = ? and recipient_ref = ?
            """,
            tenant,
            deliveryId,
            recipientRef);
    if (already != null) return;
    sql.execute(
        """
        insert into platform_notify_unsubscribe_block(
          id, tenant_id, delivery_id, unsubscribe_id, recipient_ref, channel,
          unsubscribe_scope_type, unsubscribe_scope_key, blocked_at, created_at)
        values(?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)
        """,
        UUID.randomUUID(),
        tenant,
        deliveryId,
        unsubscribeId,
        recipientRef,
        channel,
        unsubscribe.get(2, String.class),
        unsubscribe.get(3, String.class),
        now,
        now);
  }

  private void insertAttempt(
      UUID deliveryId,
      int attemptNo,
      String recipientRef,
      String channel,
      int statusCode,
      NotifyAttemptOutcome outcome,
      NotifyAttemptSource source,
      long durationMs,
      String failureReason,
      OffsetDateTime now) {
    var tenant = currentTenant();
    sql.execute(
        """
        insert into platform_notify_delivery_record_attempt(
          id, tenant_id, delivery_id, attempt_no, recipient_ref, channel,
          status_code, outcome, attempt_source, duration_ms, failure_reason, occurred_at)
        values(?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)
        """,
        UUID.randomUUID(),
        tenant,
        deliveryId,
        attemptNo,
        recipientRef,
        channel,
        statusCode,
        outcome.name(),
        source.name(),
        durationMs,
        failureReason,
        now);
  }

  /** 本次投递里出现过的收件人（尝试 ∪ 拦截）。 */
  private boolean knownRecipient(UUID deliveryId, String recipientRef) {
    var tenant = currentTenant();
    var found =
        sql.fetchOne(
            """
            select 1 from platform_notify_delivery_record_attempt
             where tenant_id = ? and delivery_id = ? and recipient_ref = ?
            union all
            select 1 from platform_notify_unsubscribe_block
             where tenant_id = ? and delivery_id = ? and recipient_ref = ?
            limit 1
            """,
            tenant,
            deliveryId,
            recipientRef,
            tenant,
            deliveryId,
            recipientRef);
    return found != null;
  }

  /** 没有点名时的默认重试对象：最近一次结果是「失败 / 待出结果」的收件人。 */
  private List<String> retryableRecipients(UUID deliveryId) {
    var tenant = currentTenant();
    return sql.fetch(
            """
            with attempts as (
              select distinct on (a.recipient_ref) a.recipient_ref, a.outcome
                from platform_notify_delivery_record_attempt a
               where a.tenant_id = ? and a.delivery_id = ?
               order by a.recipient_ref, a.attempt_no desc
            )
            select recipient_ref from attempts where outcome <> 'DELIVERED'
            """,
            tenant,
            deliveryId)
        .map(r -> r.get(0, String.class));
  }

  private int currentMaxAttemptNo(UUID deliveryId) {
    var tenant = currentTenant();
    var value =
        sql.fetchOne(
                """
                select coalesce(max(attempt_no),0) from platform_notify_delivery_record_attempt
                 where tenant_id = ? and delivery_id = ?
                """,
                tenant,
                deliveryId)
            .get(0, Integer.class);
    return value == null ? 0 : value;
  }

  private String currentChannel(UUID deliveryId) {
    var tenant = currentTenant();
    return sql.fetchOne(
            "select channel from platform_notify_delivery_record where tenant_id = ? and id = ?",
            tenant,
            deliveryId)
        .get(0, String.class);
  }

  private String currentTemplateKey(UUID deliveryId) {
    var tenant = currentTenant();
    return sql.fetchOne(
            "select template_key from platform_notify_delivery_record where tenant_id = ? and id = ?",
            tenant,
            deliveryId)
        .get(0, String.class);
  }

  private String currentTemplateScene(UUID deliveryId) {
    var tenant = currentTenant();
    return sql.fetchOne(
            """
            select t.scene from platform_notify_delivery_record d
              join platform_notify_template t on t.tenant_id = d.tenant_id and t.id = d.template_id
             where d.tenant_id = ? and d.id = ?
            """,
            tenant,
            deliveryId)
        .get(0, String.class);
  }

  /**
   * 🔴 重算聚合并写回投递记录。
   *
   * <p>「最新一次结果」用 {@code DISTINCT ON (recipient_ref) … ORDER BY attempt_no DESC} 取 ——
   * 每个收件人只算**最后一次**尝试的结果；被退订拦下的收件人不计入送达 / 失败。
   */
  private void recompute(UUID deliveryId, OffsetDateTime nextRetryAt) {
    var tenant = currentTenant();
    var agg =
        sql.fetchOne(
            """
            with attempts as (
              select distinct on (a.recipient_ref) a.recipient_ref, a.outcome
                from platform_notify_delivery_record_attempt a
               where a.tenant_id = ? and a.delivery_id = ?
               order by a.recipient_ref, a.attempt_no desc
            ), blocked as (
              select distinct b.recipient_ref
                from platform_notify_unsubscribe_block b
               where b.tenant_id = ? and b.delivery_id = ?
            )
            select (select count(*) from (select recipient_ref from attempts
                                          union select recipient_ref from blocked) u) as recipients,
                   (select count(*) from blocked) as blocked,
                   coalesce((select count(*) from attempts a
                              where a.outcome = 'DELIVERED'
                                and not exists (select 1 from blocked b where b.recipient_ref = a.recipient_ref)),0) as delivered,
                   coalesce((select count(*) from attempts a
                              where a.outcome = 'FAILED'
                                and not exists (select 1 from blocked b where b.recipient_ref = a.recipient_ref)),0) as failed
            """,
            tenant,
            deliveryId,
            tenant,
            deliveryId);

    var recipients = count(agg.get("recipients", Long.class));
    var blocked = count(agg.get("blocked", Long.class));
    var delivered = count(agg.get("delivered", Long.class));
    var failed = count(agg.get("failed", Long.class));
    var targeted = recipients.subtract(blocked);
    if (targeted.signum() < 0) targeted = BigDecimal.ZERO.setScale(6);
    var pending = targeted.subtract(delivered).subtract(failed);
    if (pending.signum() < 0) pending = BigDecimal.ZERO.setScale(6);

    var attemptCount = currentMaxAttemptNo(deliveryId);
    var deliveryRate = rate(delivered, targeted);

    String status;
    if (targeted.signum() == 0) {
      status = blocked.signum() > 0 ? NotifyDeliveryStatus.BLOCKED.name() : NotifyDeliveryStatus.PENDING.name();
    } else if (pending.signum() > 0) {
      status = NotifyDeliveryStatus.PENDING.name();
    } else if (delivered.compareTo(targeted) == 0) {
      status = NotifyDeliveryStatus.DELIVERED.name();
    } else if (delivered.signum() == 0) {
      status = NotifyDeliveryStatus.FAILED.name();
    } else {
      status = NotifyDeliveryStatus.PARTIAL.name();
    }

    var failureReason = latestFailureReason(deliveryId);
    var timeline =
        sql.fetchOne(
            """
            select min(occurred_at), max(occurred_at),
                   max(case when outcome = 'DELIVERED' then occurred_at end)
              from platform_notify_delivery_record_attempt
             where tenant_id = ? and delivery_id = ?
            """,
            tenant,
            deliveryId);

    sql.execute(
        """
        update platform_notify_delivery_record
           set status = ?, recipient_count = ?, blocked_count = ?, targeted_count = ?,
               delivered_count = ?, failed_count = ?, attempt_count = ?,
               failure_reason = ?, delivery_rate = ?,
               next_retry_at = ?::timestamptz,
               first_attempt_at = ?::timestamptz,
               last_attempt_at = ?::timestamptz,
               delivered_at = ?::timestamptz,
               updated_at = ?::timestamptz
         where tenant_id = ? and id = ?
        """,
        status,
        recipients,
        blocked,
        targeted,
        delivered,
        failed,
        attemptCount,
        failureReason,
        deliveryRate,
        nextRetryAt,
        timeline.get(0, OffsetDateTime.class),
        timeline.get(1, OffsetDateTime.class),
        timeline.get(2, OffsetDateTime.class),
        dbNow(),
        tenant,
        deliveryId);
  }

  /** 最近一次失败的说明（没有失败则空串）。 */
  private String latestFailureReason(UUID deliveryId) {
    var tenant = currentTenant();
    var found =
        sql.fetchOne(
            """
            select failure_reason from platform_notify_delivery_record_attempt
             where tenant_id = ? and delivery_id = ? and outcome = 'FAILED'
               and btrim(failure_reason) <> ''
             order by attempt_no desc limit 1
            """,
            tenant,
            deliveryId);
    return found == null ? "" : found.get(0, String.class);
  }

  private NotifyUnsubscribeRow unsubscribeRow(org.jooq.Record r) {
    return new NotifyUnsubscribeRow(
        r.get(0, UUID.class),
        r.get(1, String.class),
        r.get(2, String.class),
        r.get(3, String.class),
        r.get(4, String.class),
        r.get(5, UUID.class),
        r.get(6, String.class),
        r.get(7, String.class),
        r.get(8, OffsetDateTime.class),
        r.get(9, OffsetDateTime.class),
        r.get(10, OffsetDateTime.class));
  }
}
