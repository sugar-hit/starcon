package com.starcon.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 13.6.2.1 导出与订阅的**服务层**（蓝图 13.6.2.1）。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 * **职责**：报表导出（Excel/CSV/PDF）与定时订阅推送（邮件/企业 IM），支持大文件异步导出。<br>
 * **输入**：导出请求；订阅配置。<br>
 * **输出**：文件下载链接；订阅投递记录。<br>
 * **关系**：上游 = 13.6.1.1；下游 = 1.3.2.2 推送。<b>大导出走异步任务并给出有效期</b>，
 * 同步导出大文件会拖垮服务。
 * </blockquote>
 *
 * <p>🔴 **口径唯一：不另起一套指标计算逻辑**。取数与口径全部走上游 13.6.1.1 的
 * {@link ReportingCustomReportService}（{@link ReportingExportTableBuilder} 只是把它
 * 的结果铺成表）⇒ 导出的内容与用户在报表页面上看到的**逐格一致**。
 *
 * <p>🔴 **大导出走异步任务**：{@link #create} 只受理并返回「排队中」，
 * 文件由 {@link ReportingExportExecutor} 在请求之外生成；
 * 同步出口 {@link #sync} 只允许小结果集，超过阈值**明确拒绝**而不是把服务拖垮。
 *
 * <p>🔴 **文件有效期是业务参数**：{@code expires_at} 在任务成功那一刻由服务层写入
 * （{@code REPORTING_EXPORT_TTL_HOURS}），不是 DB 常量。过期后下载端点回 410 Gone。
 */
@Service
public class ReportingExportService {
  private static final Logger log = LoggerFactory.getLogger(ReportingExportService.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 任务清单的单页上限。 */
  private static final int MAX_PAGE_SIZE = 100;

  /** 不可用时的逐字相同文案（不泄露「任务存在但你没权限」）。 */
  private static final String NOT_FOUND_MESSAGE = "导出任务不存在或无权访问";

  private final DSLContext sql;
  private final TenantTransactions tenants;
  private final ReportingCustomReportService reports;
  private final ReportingExportTableBuilder tables;
  private final ReportingExportStorage storage;
  private final ReportingExportExecutor executor;
  private final Clock clock;
  private final ReportingExportSettings settings;

  public ReportingExportService(
      DSLContext sql,
      TenantTransactions tenants,
      ReportingCustomReportService reports,
      ReportingExportTableBuilder tables,
      ReportingExportStorage storage,
      ReportingExportExecutor executor,
      Clock clock,
      ReportingExportSettings settings) {
    this.sql = sql;
    this.tenants = tenants;
    this.reports = reports;
    this.tables = tables;
    this.storage = storage;
    this.executor = executor;
    this.clock = clock;
    this.settings = settings;
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================

  /** 导出能力声明（可导出字段 / 排序 / 筛选 / 格式 / 阈值）。 */
  public ReportingExportModels.ExportSubscriptionSourcesResponse sources(
      UUID tenantId, UUID actorUserId, String reportId) {
    var formats = new ArrayList<ReportingExportModels.ExportSubscriptionFormatInfo>();
    for (var format : ReportingExportCatalog.ExportFormat.values()) {
      formats.add(
          new ReportingExportModels.ExportSubscriptionFormatInfo(
              format.code(),
              format.label(),
              format.extension(),
              format.contentType(),
              true,
              ""));
    }
    var columns = new ArrayList<ReportingExportModels.ExportSubscriptionColumnInfo>();
    var notes = new ArrayList<>(ReportingExportCatalog.NOTES);
    var code = "";
    var name = "";
    if (reportId != null && !reportId.isBlank()) {
      var view = reports.get(tenantId, actorUserId, uuid(reportId, "报表 id"));
      code = view.reportCode();
      name = view.displayName();
      for (var column : tables.resolveColumns(view)) {
        columns.add(
            new ReportingExportModels.ExportSubscriptionColumnInfo(
                column.key(), column.label(), column.kind(), column.note()));
      }
      notes.add(
          "🔴 **本报表的度量与维度就是可导出的全部内容** —— 导出**不开放任意 SQL**，"
              + "也不允许导出报表之外的任何数据。");
    }
    return new ReportingExportModels.ExportSubscriptionSourcesResponse(
        formats,
        columns,
        ReportingExportCatalog.sorts(),
        ReportingExportCatalog.filterOperators(),
        settings.syncRowThreshold(),
        settings.maxRows(),
        settings.downloadTtlHours(),
        reportId == null || reportId.isBlank() ? null : reportId,
        code,
        name,
        notes);
  }

  // ==========================================================================
  // 受理（异步）
  // ==========================================================================

  /**
   * 受理一次导出：落任务 + 落源快照，然后交给执行器。
   *
   * <p><b>返回即「已受理」，不是「已完成」</b>：文件在请求之外生成，
   * 行数与文件要轮询任务详情才拿得到。
   */
  public ReportingExportModels.ExportSubscriptionJobView create(
      UUID tenantId, UUID actorUserId, ReportingExportModels.ExportSubscriptionJobCreateRequest request) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
    }
    var format = whitelistFormat(request.format());
    var reportId = uuid(request.reportId(), "报表 id");
    // 🔴 可见性闸门在**上游**（个人报表只有创建者能看）；取不到 ⇒ 与「不存在」逐字相同的 404。
    var view = reports.get(tenantId, actorUserId, reportId);

    var selected = tables.resolveSelection(request.fields(), tables.resolveColumns(view));

    var id = Ids.next();
    var sourceId = Ids.next();
    var headers = new ArrayList<String>();
    var keys = new ArrayList<String>();
    for (var column : selected) {
      keys.add(column.key());
      headers.add(column.label());
    }
    var filterSnapshot = new LinkedHashMap<String, Object>();
    for (var filter : view.filters()) {
      filterSnapshot.put(
          filter.dimensionCode() + " " + filter.operator(),
          filter.valueText()
              + (filter.valueText2() == null || filter.valueText2().isEmpty()
                  ? ""
                  : " ~ " + filter.valueText2()));
    }

    tenants.run(
        tenantId,
        () -> {
          sql.execute(
              "insert into reporting_export_job"
                  + " (id,tenant_id,actor_id,status,row_count,error,requested_at,created_at,updated_at)"
                  + " values (?,?,?,'PENDING',0,'',now(),now(),now())",
              id,
              tenantId,
              actorUserId);
          sql.execute(
              "insert into reporting_export_job_source"
                  + " (id,tenant_id,job_id,report_id,format,selected_fields,field_headers,"
                  + " filter_snapshot,window_from,window_to,created_at)"
                  + " values (?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::date,?::date,now())",
              sourceId,
              tenantId,
              id,
              reportId,
              format.code(),
              json(keys),
              json(headers),
              json(filterSnapshot),
              request.from(),
              request.to());
          return null;
        });

    executor.accept(tenantId, actorUserId, id);
    return job(tenantId, id);
  }

  // ==========================================================================
  // 同步（小结果集）
  // ==========================================================================

  /**
   * 同步导出（**只允许小结果集**）。
   *
   * <p>🔴 **超过阈值明确拒绝**（409）：蓝图写死「大导出走异步任务并给出有效期，
   * 同步导出大文件会拖垮服务」。这里不是「截断成前 N 行」（那样用户会拿到一份
   * 看起来完整、其实少了一半数据的文件），而是**拒绝并说清该走哪条路**。
   */
  public SyncExport sync(
      UUID tenantId,
      UUID actorUserId,
      ReportingExportModels.ExportSubscriptionJobCreateRequest request,
      ReportingExportRenderer renderer) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
    }
    var format = whitelistFormat(request.format());
    var reportId = uuid(request.reportId(), "报表 id");
    var view = reports.get(tenantId, actorUserId, reportId);
    var selected = tables.resolveSelection(request.fields(), tables.resolveColumns(view));

    var built =
        tables.build(
            tenantId,
            actorUserId,
            view,
            selected,
            request.from(),
            request.to(),
            settings.syncRowThreshold(),
            true);
    var rendered = renderer.render(format, built.table(), tableTitle(view), built.notes());
    return new SyncExport(
        fileName(view.reportCode(), format, LocalDate.now(clock)),
        format.contentType(),
        built.table().rows().size(),
        rendered.content(),
        rendered.warnings());
  }

  // ==========================================================================
  // 清单 / 详情
  // ==========================================================================

  /** 任务清单（真分页 + 真搜索 + 真筛选）。 */
  public ReportingExportModels.ExportSubscriptionJobPage jobs(
      UUID tenantId, String status, String format, String keyword, int page, int size) {
    return tenants.run(
        tenantId,
        () -> {
          var effectiveSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
          var effectivePage = Math.max(page, 0);
          var where = new ArrayList<String>();
          var params = new ArrayList<Object>();
          params.add(tenantId);
          if (status != null && !status.isBlank()) {
            where.add("j.status = ?");
            params.add(status.trim().toUpperCase(Locale.ROOT));
          }
          if (format != null && !format.isBlank()) {
            where.add("s.format = ?");
            params.add(format.trim().toUpperCase(Locale.ROOT));
          }
          if (keyword != null && !keyword.isBlank()) {
            where.add("(r.report_code ilike ? or r.display_name ilike ?)");
            var like = "%" + keyword.trim() + "%";
            params.add(like);
            params.add(like);
          }
          var clause = where.isEmpty() ? "" : " and " + String.join(" and ", where);
          var total =
              sql.fetchOne(
                  "select count(*) from reporting_export_job j"
                      + " join reporting_export_job_source s on s.tenant_id = j.tenant_id and s.job_id = j.id"
                      + " join reporting_custom_report r on r.tenant_id = j.tenant_id and r.id = s.report_id"
                      + " where j.tenant_id = ?"
                      + clause,
                  params.toArray());
          var rows =
              sql.fetch(
                  SELECT_JOB_WITH_SOURCE
                      + " where j.tenant_id = ?"
                      + clause
                      + " order by j.requested_at desc, j.id desc"
                      + " limit ? offset ?",
                  append(params, effectiveSize, effectivePage * effectiveSize));
          var items = new ArrayList<ReportingExportModels.ExportSubscriptionJobView>();
          for (var row : rows) {
            items.add(toJobView(row));
          }
          return new ReportingExportModels.ExportSubscriptionJobPage(
              items,
              total == null ? 0L : total.get(0, Long.class),
              effectivePage,
              effectiveSize,
              status == null ? "" : status.trim(),
              format == null ? "" : format.trim(),
              keyword == null ? "" : keyword.trim());
        });
  }

  /** 任务详情。 */
  public ReportingExportModels.ExportSubscriptionJobView job(UUID tenantId, UUID jobId) {
    return tenants.run(tenantId, () -> toJobView(findRow(tenantId, jobId)));
  }

  // ==========================================================================
  // 下载
  // ==========================================================================

  /**
   * 取导出文件的字节。
   *
   * <p>🔴 **三段式（短事务读 / 独立短事务写 / 事务外抛错）**：
   * 「过期 ⇒ 推进到 EXPIRED + 清理文件」这个写动作必须**先提交**，
   * 然后才回 410。如果把写和抛错放在同一个事务里，异常会把那次更新**一起回滚** ——
   * 结果是「每次下载都判定它过期，但状态永远停在 SUCCEEDED、文件也永远不清」。
   */
  public ExportFile file(UUID tenantId, UUID jobId) {
    // ── 阶段 1：短事务读 ──
    var state = tenants.run(tenantId, () -> readFileState(tenantId, jobId));

    if (state.expired()) {
      // ── 阶段 2：独立短事务推进到 EXPIRED 并删掉存储对象（**先提交**） ──
      tenants.run(
          tenantId,
          () -> {
            expire(tenantId, state);
            return null;
          });
      // ── 阶段 3：**在事务外**抛错（否则异常会回滚阶段 2 的写） ──
      throw new ResponseStatusException(
          HttpStatus.GONE,
          "导出文件已过下载有效期（"
              + state.expiresAt().toInstant()
              + "），已清理；请重新发起一次导出。");
    }
    if ("EXPIRED".equals(state.status())) {
      throw new ResponseStatusException(
          HttpStatus.GONE, "导出文件已过下载有效期并被清理；请重新发起一次导出。");
    }
    if ("CANCELLED".equals(state.status())) {
      throw new ResponseStatusException(HttpStatus.GONE, "这份导出已被作废，不再提供下载。");
    }
    if (!"SUCCEEDED".equals(state.status())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "导出尚未完成（当前状态：" + statusLabel(state.status()) + "），请稍候再下载。");
    }
    if (!storage.exists(state.storageKey())) {
      // 记录说成功、存储里却没有：文件被外部清理了。推进到 EXPIRED，让它不再冒充成功。
      tenants.run(
          tenantId,
          () -> {
            expire(tenantId, state);
            return null;
          });
      throw new ResponseStatusException(
          HttpStatus.GONE, "导出文件已不在存储中（可能被部署侧清理），请重新发起一次导出。");
    }
    byte[] content;
    try {
      content = storage.read(state.storageKey());
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "读取导出文件失败，请联系管理员");
    }
    return new ExportFile(state.fileName(), state.contentType(), content.length, content);
  }

  /** 下载前读到的状态（一次短事务里读齐）。 */
  record FileState(
      UUID jobId,
      String status,
      String fileName,
      String contentType,
      String storageKey,
      OffsetDateTime expiresAt,
      boolean expired) {}

  /** 读下载状态（**短事务**，不抛错 —— 抛错留给事务外的阶段 3）。 */
  private FileState readFileState(UUID tenantId, UUID jobId) {
    var row = findRow(tenantId, jobId);
    var status = row.get("status", String.class);
    var expiresAt = row.get("expires_at", OffsetDateTime.class);
    var expired =
        "SUCCEEDED".equals(status)
            && expiresAt != null
            && expiresAt.isBefore(OffsetDateTime.now(clock));
    return new FileState(
        jobId,
        status,
        row.get("file_name", String.class),
        ReportingExportCatalog.ExportFormat.of(row.get("format", String.class)).contentType(),
        row.get("storage_key", String.class),
        expiresAt,
        expired);
  }

  // ==========================================================================
  // 作废
  // ==========================================================================

  /** 作废一份导出（还没跑完的直接取消；已生成文件的也允许作废）。 */
  public ReportingExportModels.ExportSubscriptionJobView cancel(UUID tenantId, UUID jobId) {
    return tenants.run(
        tenantId,
        () -> {
          var row = findRow(tenantId, jobId);
          var status = row.get("status", String.class);
          if (Set.of("FAILED", "CANCELLED", "EXPIRED").contains(status)) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "该导出已处于终态（" + statusLabel(status) + "），无法再作废。");
          }
          sql.execute(
              "update reporting_export_job set status='CANCELLED',storage_key=null"
                  + " where tenant_id=? and id=? and status=?",
              tenantId,
              jobId,
              status);
          var key = row.get("storage_key", String.class);
          if (key != null) {
            try {
              storage.delete(key);
            } catch (IOException e) {
              log.warn("作废导出文件删除失败：{}", key, e);
            }
          }
          return toJobView(findRow(tenantId, jobId));
        });
  }

  // ==========================================================================
  // 内部工具
  // ==========================================================================

  /** 任务行的公共 SELECT（任务 + 源 + 报表）。 */
  static final String SELECT_JOB_WITH_SOURCE =
      "select j.*, s.report_id, s.format, s.selected_fields, s.field_headers,"
          + " r.report_code, r.display_name"
          + " from reporting_export_job j"
          + " join reporting_export_job_source s on s.tenant_id = j.tenant_id and s.job_id = j.id"
          + " join reporting_custom_report r on r.tenant_id = j.tenant_id and r.id = s.report_id";

  /** 下载到的文件。 */
  public record ExportFile(String fileName, String contentType, int byteSize, byte[] content) {}

  /**
   * 同步导出的结果（**原始字节**，不是 JSON —— 走 JSON 会被 base64 编码成一大串文本）。
   */
  public record SyncExport(
      String fileName,
      String contentType,
      /** 实际写了多少行数据。 */
      int rowCount,
      byte[] content,
      List<String> warnings) {}

  /** 任务行（带源与报表）。 */
  private org.jooq.Record findRow(UUID tenantId, UUID jobId) {
    var row =
        sql.fetchOne(
            SELECT_JOB_WITH_SOURCE + " where j.tenant_id = ? and j.id = ?", tenantId, jobId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
    }
    return row;
  }

  /** 行 → 视图。 */
  private ReportingExportModels.ExportSubscriptionJobView toJobView(org.jooq.Record row) {
    var status = row.get("status", String.class);
    var expiresAt = row.get("expires_at", OffsetDateTime.class);
    var expired = expiresAt != null && expiresAt.isBefore(OffsetDateTime.now(clock));
    var downloadable = "SUCCEEDED".equals(status) && !expired;
    var reportId = row.get("report_id", UUID.class);
    return new ReportingExportModels.ExportSubscriptionJobView(
        row.get("id", UUID.class).toString(),
        reportId == null ? "" : reportId.toString(),
        row.get("report_code", String.class),
        row.get("display_name", String.class),
        row.get("format", String.class),
        status,
        statusLabel(status),
        readStrings(row, "selected_fields"),
        readStrings(row, "field_headers"),
        row.get("row_count", Integer.class),
        row.get("file_name", String.class),
        row.get("byte_size", BigDecimal.class),
        row.get("content_sha256", String.class),
        row.get("actor_id", UUID.class).toString(),
        row.get("requested_at", OffsetDateTime.class),
        row.get("started_at", OffsetDateTime.class),
        row.get("finished_at", OffsetDateTime.class),
        expiresAt,
        downloadable,
        downloadNote(status, expired),
        row.get("error", String.class));
  }

  /** 下载状态说明。 */
  static String downloadNote(String status, boolean expired) {
    if (expired) return "已过下载有效期";
    return switch (status) {
      case "PENDING" -> "排队中，尚未开始";
      case "RUNNING" -> "正在生成文件";
      case "FAILED" -> "导出失败";
      case "CANCELLED" -> "已被作废";
      case "EXPIRED" -> "已过下载有效期并被清理";
      default -> "当前不可下载";
    };
  }

  /** 状态标签。 */
  static String statusLabel(String status) {
    return switch (status) {
      case "PENDING" -> "排队中";
      case "RUNNING" -> "生成中";
      case "SUCCEEDED" -> "已完成";
      case "FAILED" -> "失败";
      case "CANCELLED" -> "已作废";
      case "EXPIRED" -> "已过期";
      default -> status;
    };
  }

  /** 把一条已过期或已丢文件的 SUCCEEDED 记录推进到 EXPIRED，并删掉存储对象。 */
  private void expire(UUID tenantId, FileState state) {
    var moved =
        sql.fetchOne(
            "update reporting_export_job set status='EXPIRED'"
                + " where tenant_id=? and id=? and status='SUCCEEDED' returning id",
            tenantId,
            state.jobId());
    if (moved == null) return;
    var key = state.storageKey();
    if (key == null) return;
    try {
      storage.delete(key);
    } catch (IOException e) {
      log.warn("过期导出文件删除失败：{}", key, e);
    }
  }

  /** 表标题（PDF 与文件名用）。 */
  static String tableTitle(ReportingCustomReportModels.CustomReportView view) {
    return view.reportCode() + " · " + view.displayName();
  }

  /** 文件名（**不含路径分隔符**：文件名里带 '/' 会让某些浏览器把下载拆成两个路径）。 */
  static String fileName(String reportCode, ReportingExportCatalog.ExportFormat format, LocalDate day) {
    var safeCode = reportCode == null ? "report" : reportCode.replaceAll("[^A-Za-z0-9_.-]", "_");
    return String.format(
        "%s-%04d%02d%02d%s",
        safeCode, day.getYear(), day.getMonthValue(), day.getDayOfMonth(), format.extension());
  }

  /** uuid 解析（格式不对 ⇒ 400，不是 404）。 */
  static UUID uuid(String raw, String label) {
    try {
      return UUID.fromString(raw);
    } catch (RuntimeException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, label + "格式不正确（收到的是「" + raw + "」）");
    }
  }

  /** 格式白名单（取不到 ⇒ 400，不静默回落）。 */
  private ReportingExportCatalog.ExportFormat whitelistFormat(String code) {
    try {
      return ReportingExportCatalog.ExportFormat.of(code);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /** JSON 序列化（快照用）。 */
  static String json(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("导出快照序列化失败", e);
    }
  }

  /** JSON 数组反序列化。 */
  static List<String> readStrings(org.jooq.Record row, String column) {
    var raw = row.get(column, String.class);
    if (raw == null || raw.isBlank()) return List.of();
    try {
      return MAPPER.readValue(raw, List.class);
    } catch (IOException e) {
      throw new IllegalStateException("导出快照解析失败：" + column, e);
    }
  }

  /** 参数拼装。 */
  static Object[] append(List<Object> params, Object... extra) {
    var out = new ArrayList<Object>(params);
    out.addAll(List.of(extra));
    return out.toArray();
  }

  /** 供执行器复用：读取一次执行所需的全部快照。 */
  static ExportSnapshot load(
      DSLContext sql, TenantTransactions tenants, UUID tenantId, UUID jobId) {
    return tenants.run(
        tenantId,
        () -> {
          var row =
              sql.fetchOne(
                  "select j.id, j.actor_id, j.status, s.report_id, s.format, s.selected_fields,"
                      + " s.field_headers, s.window_from, s.window_to"
                      + " from reporting_export_job j"
                      + " join reporting_export_job_source s on s.tenant_id = j.tenant_id and s.job_id = j.id"
                      + " where j.tenant_id = ? and j.id = ?",
                  tenantId,
                  jobId);
          if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
          }
          return new ExportSnapshot(
              row.get("id", UUID.class),
              row.get("actor_id", UUID.class),
              row.get("report_id", UUID.class),
              row.get("format", String.class),
              readStrings(row, "selected_fields"),
              readStrings(row, "field_headers"),
              row.get("window_from", LocalDate.class),
              row.get("window_to", LocalDate.class));
        });
  }

  /** 执行器需要的快照。 */
  record ExportSnapshot(
      UUID jobId,
      UUID actorUserId,
      UUID reportId,
      String format,
      List<String> selectedFields,
      List<String> fieldHeaders,
      LocalDate windowFrom,
      LocalDate windowTo) {}
}
