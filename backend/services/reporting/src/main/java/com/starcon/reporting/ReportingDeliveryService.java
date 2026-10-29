package com.starcon.reporting;

import com.starcon.tenancy.TenantTransactions;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 13.6.2.1 的**投递服务层**：把 13.6.1.1 登记的「待投递记录」真正送出去。
 *
 * <p>🔴 **上游 13.6.1.1 留下的缺口在这里收口**：它只把到期的订阅落成
 * {@code reporting_custom_report_delivery} 的 PENDING 记录（**待投递**），
 * 并在能力声明里标 {@code deliveryAvailable=false}。本模块补上**实际投递**：
 *
 * <ol>
 *   <li>取一批 PENDING 记录（**只取 PENDING** —— 已投递 / 已失败 / 已取消的不重发）；</li>
 *   <li>按订阅计划的通道（EMAIL / IM）选传输端口；</li>
 *   <li>跑报表 ⇒ 渲染成 CSV 附件 + 正文摘要；</li>
 *   <li>真的送出去 —— <b>传输层回报成功才写 DELIVERED</b>；</li>
 *   <li>失败就写 FAILED 并**写明原因**（没配置通道也是失败，不是「已发送」）。</li>
 * </ol>
 *
 * <p>🔴 **绝不允许在没发出去的情况下把记录标成 DELIVERED**。
 * 一个显示「已投递」而用户根本没收到邮件的台账，比没有投递功能更糟 ——
 * 运维会以为链路是通的，而真实情况是邮件根本没出去。
 *
 * <p>⚠️ **本服务不装 {@code @Scheduled}**（与上游 13.6.1.1、channel / identity 同一纪律：
 * 多副本下自带定时器要做主节点选举与分布式锁）⇒ 投递由**外部调度**触发
 * {@code POST /reporting/custom-report/deliveries/dispatch}。
 */
@Service
public class ReportingDeliveryService {
  private static final Logger log = LoggerFactory.getLogger(ReportingDeliveryService.class);

  /** 一轮最多处理几条（避免一轮把整个队列拖住）。 */
  private static final int MAX_BATCH = 50;

  /** 一次投递最多带多少行（超过 ⇒ 投递失败并写明原因，不静默截断）。 */
  private static final int MAX_DELIVERY_ROWS = 100_000;

  /** 不可用时的逐字相同文案。 */
  private static final String NOT_FOUND_MESSAGE = "投递记录不存在或无权访问";

  private final DSLContext sql;
  private final TenantTransactions tenants;
  private final ReportingCustomReportService reports;
  private final ReportingExportTableBuilder tables;
  private final ReportingExportRenderer renderer;
  private final SmtpReportingDeliveryTransport smtp;
  private final WebhookReportingDeliveryTransport im;
  private final Clock clock;

  public ReportingDeliveryService(
      DSLContext sql,
      TenantTransactions tenants,
      ReportingCustomReportService reports,
      ReportingExportTableBuilder tables,
      ReportingExportRenderer renderer,
      SmtpReportingDeliveryTransport smtp,
      WebhookReportingDeliveryTransport im,
      Clock clock) {
    this.sql = sql;
    this.tenants = tenants;
    this.reports = reports;
    this.tables = tables;
    this.renderer = renderer;
    this.smtp = smtp;
    this.im = im;
    this.clock = clock;
  }

  // ==========================================================================
  // 单条详情
  // ==========================================================================

  /** 一条投递记录的详情（上游刻意没放行的那条形状）。 */
  public ReportingExportModels.ExportSubscriptionDeliveryView detail(UUID tenantId, UUID deliveryId) {
    return tenants.run(tenantId, () -> toDetail(findRow(tenantId, deliveryId)));
  }

  // ==========================================================================
  // 投递
  // ==========================================================================

  /**
   * 跑一轮投递：把队列里的 PENDING 记录真的送出去。
   *
   * @param limit 本轮最多处理几条（空 ⇒ 默认 {@link #MAX_BATCH}）
   */
  public ReportingExportModels.ExportSubscriptionDispatchResult dispatch(UUID tenantId, Integer limit) {
    var batch = Math.min(Math.max(limit == null ? MAX_BATCH : limit, 1), MAX_BATCH);
    // ── 阶段 1：取待投递的 id（**短事务**） ──
    var pendingIds =
        tenants.run(
            tenantId,
            () ->
                sql.fetch(
                    "select d.id from reporting_custom_report_delivery d"
                        + " where d.tenant_id = ? and d.status = 'PENDING'"
                        + " order by d.scheduled_for asc, d.created_at asc"
                        + " limit ?",
                    tenantId,
                    batch));
    var delivered = 0;
    var failed = 0;
    var rows = new ArrayList<ReportingExportModels.ExportSubscriptionDeliveryView>();
    for (var pendingRow : pendingIds) {
      var deliveryId = pendingRow.get(0, UUID.class);
      var outcome = deliver(tenantId, deliveryId);
      if (outcome.sent()) {
        delivered++;
      } else {
        failed++;
      }
      rows.add(detail(tenantId, deliveryId));
    }
    return new ReportingExportModels.ExportSubscriptionDispatchResult(
        pendingIds.size(),
        delivered,
        failed,
        rows,
        "本轮尝试投递 "
            + pendingIds.size()
            + " 条：成功 "
            + delivered
            + " 条，失败 "
            + failed
            + " 条。🔴 **失败的都是真失败**（没配置通道 / 连不上 / 被拒收），"
            + "台账里写的是 FAILED 而不是「已投递」。");
  }

  /** 一条投递的三个阶段：短事务读 → **不持连接**的网络发送 → 短事务写。 */
  private record Prepared(
      ReportingDeliveryTransport transport,
      ReportingDeliveryTransport.Message message,
      String error) {}

  /**
   * 投一条。返回这次投递的结果（**sent=false ⇒ 记录被写成 FAILED**）。
   *
   * <p>🔴 **网络发送期间不持有数据库连接**：连接池被占住时整个服务的吞吐都会掉，
   * 而一次 SMTP 投递可能要等几十秒。三段式（短事务读 / 发送 / 短事务写）是这里的纪律。
   */
  private ReportingDeliveryTransport.Outcome deliver(UUID tenantId, UUID deliveryId) {
    // ── 阶段 1：短事务读（取通道 / 目标 / 报表数据，组装消息） ──
    var prepared = tenants.run(tenantId, () -> prepare(tenantId, deliveryId));
    if (prepared.error() != null) {
      var reason = prepared.error();
      return tenants.run(
          tenantId,
          () -> {
            fail(tenantId, deliveryId, reason);
            return ReportingDeliveryTransport.Outcome.failed(reason);
          });
    }

    // ── 阶段 2：真的送出去（**不持有数据库连接**） ──
    ReportingDeliveryTransport.Outcome sent;
    try {
      sent = prepared.transport().send(prepared.message());
    } catch (Exception e) {
      var reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      log.warn("投递记录 {} 投递失败：{}", deliveryId, reason);
      sent = ReportingDeliveryTransport.Outcome.failed(reason);
    }
    final ReportingDeliveryTransport.Outcome outcome = sent;

    // ── 阶段 3：短事务写状态 ──
    tenants.run(
        tenantId,
        () -> {
          if (outcome.sent()) {
            succeed(tenantId, deliveryId);
          } else {
            fail(tenantId, deliveryId, outcome.detail());
          }
          return null;
        });
    return outcome;
  }

  /** 阶段 1：读出投递所需的一切，并组装消息。 */
  private Prepared prepare(UUID tenantId, UUID deliveryId) {
    var row = findRow(tenantId, deliveryId);
    var channel = row.get("channel", String.class);
    var target = row.get("target", String.class);
    var reportId = row.get("report_id", UUID.class);
    var ownerUserId = row.get("owner_user_id", UUID.class);

    if (channel == null || channel.isBlank()) {
      return new Prepared(null, null, "这条投递记录没有通道（订阅计划已被删？）—— 无法投递。");
    }
    if (target == null || target.isBlank()) {
      return new Prepared(null, null, "订阅计划没有收件目标 —— 无法投递。");
    }
    var transport = "EMAIL".equals(channel) ? smtp : "IM".equals(channel) ? im : null;
    if (transport == null) {
      return new Prepared(null, null, "未知的投递通道「" + channel + "」—— 只支持 EMAIL 与 IM。");
    }
    if (!transport.configured()) {
      // 🔴 没配置通道 ⇒ **没发出去** ⇒ 记 FAILED 并写明缺什么。
      var reason =
          transport instanceof SmtpReportingDeliveryTransport
              ? ((SmtpReportingDeliveryTransport) transport).unconfiguredReason()
              : ((WebhookReportingDeliveryTransport) transport).unconfiguredReason();
      return new Prepared(null, null, reason);
    }
    try {
      // 🔴 用**报表的创建者**作为取数的身份：个人报表只有创建者能看，
      // 而订阅计划本来就是他建的。
      var view = reports.get(tenantId, ownerUserId, reportId);
      var selected = tables.resolveSelection(List.of(), tables.resolveColumns(view));
      var built =
          tables.build(
              tenantId,
              ownerUserId,
              view,
              selected,
              view.windowFrom(),
              view.windowTo(),
              MAX_DELIVERY_ROWS,
              false);
      var csv =
          renderer.render(
              ReportingExportCatalog.ExportFormat.CSV,
              built.table(),
              ReportingExportService.tableTitle(view),
              built.notes());
      return new Prepared(transport, buildMessage(channel, target, view, built, csv.content()), null);
    } catch (RuntimeException e) {
      var reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      log.warn("投递记录 {} 组装消息失败：{}", deliveryId, reason);
      return new Prepared(null, null, reason);
    }
  }

  /** 拼一条消息（正文 + CSV 附件 + 表格行）。 */
  private ReportingDeliveryTransport.Message buildMessage(
      String channel,
      String target,
      ReportingCustomReportModels.CustomReportView view,
      ReportingExportTableBuilder.BuiltTable built,
      byte[] csv) {
    var body = new StringBuilder();
    body.append("报表订阅推送\n");
    body.append("报表：").append(view.reportCode()).append(" · ").append(view.displayName()).append("\n");
    body.append("时间窗口：")
        .append(view.windowFrom() == null ? "报表默认" : view.windowFrom())
        .append(" ~ ")
        .append(view.windowTo() == null ? "报表默认" : view.windowTo())
        .append("\n");
    body.append("数据行数：").append(built.table().rows().size()).append("\n");
    body.append("\n口径说明：\n");
    for (var note : built.notes()) {
      body.append("· ").append(note).append("\n");
    }
    var rows = new ArrayList<List<String>>();
    rows.add(built.table().headers());
    for (var row : built.table().rows()) {
      var texts = new ArrayList<String>();
      for (var cell : row) texts.add(cell.text());
      rows.add(texts);
    }
    return new ReportingDeliveryTransport.Message(
        target,
        "【starcon 报表】" + view.reportCode() + " · " + view.displayName(),
        body.toString(),
        ReportingExportService.fileName(
            view.reportCode(), ReportingExportCatalog.ExportFormat.CSV, LocalDate.now(clock)),
        csv,
        rows);
  }

  // ==========================================================================
  // 状态写入
  // ==========================================================================

  /** 投递成功：写 DELIVERED（**只有传输层回报成功才会走到这里**）。 */
  private void succeed(UUID tenantId, UUID deliveryId) {
    sql.execute(
        "update reporting_custom_report_delivery"
            + " set status='DELIVERED', attempt_count=attempt_count+1, last_error='', updated_at=now()"
            + " where tenant_id=? and id=? and status='PENDING'",
        tenantId,
        deliveryId);
  }

  /** 投递失败：写 FAILED 并**写明原因**（**在租户事务内调用**）。 */
  private ReportingDeliveryTransport.Outcome fail(UUID tenantId, UUID deliveryId, String reason) {
    var text = reason == null || reason.isBlank() ? "投递失败（原因未知）" : reason;
    sql.execute(
        "update reporting_custom_report_delivery"
            + " set status='FAILED', attempt_count=attempt_count+1, last_error=?, updated_at=now()"
            + " where tenant_id=? and id=? and status='PENDING'",
        text.length() > 500 ? text.substring(0, 500) : text,
        tenantId,
        deliveryId);
    return ReportingDeliveryTransport.Outcome.failed(text);
  }

  // ==========================================================================
  // 行读取
  // ==========================================================================

  /** 投递记录（含订阅计划与报表）。 */
  private org.jooq.Record findRow(UUID tenantId, UUID deliveryId) {
    var row =
        sql.fetchOne(
            "select d.id, d.report_id, d.subscription_id, d.scheduled_for, d.status,"
                + " d.attempt_count, d.last_error, d.created_at, d.updated_at,"
                + " s.channel, s.target, r.report_code, r.display_name, r.owner_user_id"
                + " from reporting_custom_report_delivery d"
                + " left join reporting_custom_report_subscription s"
                + "   on s.tenant_id = d.tenant_id and s.id = d.subscription_id"
                + " join reporting_custom_report r"
                + "   on r.tenant_id = d.tenant_id and r.id = d.report_id"
                + " where d.tenant_id = ? and d.id = ?",
            tenantId,
            deliveryId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
    }
    return row;
  }

  /** 行 → 视图。 */
  private ReportingExportModels.ExportSubscriptionDeliveryView toDetail(org.jooq.Record row) {
    var status = row.get("status", String.class);
    var channel = row.get("channel", String.class);
    var target = row.get("target", String.class);
    return new ReportingExportModels.ExportSubscriptionDeliveryView(
        row.get("id", UUID.class).toString(),
        row.get("report_id", UUID.class).toString(),
        row.get("report_code", String.class),
        row.get("display_name", String.class),
        row.get("subscription_id", UUID.class).toString(),
        channel == null ? "" : channel,
        target == null ? "" : target,
        row.get("scheduled_for", java.time.OffsetDateTime.class),
        status,
        statusLabel(status),
        row.get("attempt_count", Integer.class),
        row.get("last_error", String.class),
        row.get("created_at", java.time.OffsetDateTime.class),
        row.get("updated_at", java.time.OffsetDateTime.class),
        deliveryNote(status));
  }

  /** 投递状态标签。 */
  static String statusLabel(String status) {
    if (status == null) return "";
    return switch (status.toUpperCase(Locale.ROOT)) {
      case "PENDING" -> "待投递";
      case "DELIVERED" -> "已投递";
      case "FAILED" -> "投递失败";
      case "CANCELLED" -> "已取消";
      default -> status;
    };
  }

  /** 投递状态说明（**「待投递」不是「已发送」**）。 */
  static String deliveryNote(String status) {
    if (status == null) return "";
    return switch (status.toUpperCase(Locale.ROOT)) {
      case "PENDING" -> "已排队，等待外部调度触发投递（POST /deliveries/dispatch）。";
      case "DELIVERED" -> "传输层回报已成功送达。";
      case "FAILED" -> "投递失败，原因见「最近一次失败原因」。";
      case "CANCELLED" -> "这条投递已被取消。";
      default -> "";
    };
  }
}
