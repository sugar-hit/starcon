package com.starcon.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 13.6.2.1「导出与订阅」的**导出**端点。
 *
 * <p>命名空间**沿用上游 13.6.1.1 的 {@code /reporting/custom-report}** ——
 * 导出的是「自定义报表的结果」，另开一个与 custom-report 平级的命名空间会让
 * 「哪个模块的哪个端点」变得说不清。
 *
 * <pre>
 *   能力声明   GET  /reporting/custom-report/export/sources
 *   发起导出   POST /reporting/custom-report/export/jobs        （已受理，异步生成）
 *   任务清单   GET  /reporting/custom-report/export/jobs
 *   任务详情   GET  /reporting/custom-report/export/jobs/{id}
 *   下载文件   GET  /reporting/custom-report/export/jobs/{id}/file  （带下载有效期）
 *   作废       POST /reporting/custom-report/export/jobs/{id}/cancel
 *   同步导出   POST /reporting/custom-report/export/sync         （**只允许小结果集**）
 * </pre>
 *
 * <p>🔴 **POST 受理返回的是「排队中」，不是「已完成」**：蓝图写死大导出走异步任务，
 * 前端**绝不能**在受理返回后就显示「导出完成」—— 行数与文件要轮询任务详情才拿得到。
 *
 * <p>🔴 **租户不来自任何请求头**：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/reporting/custom-report")
@Tag(name = "13.6.2.1 导出与订阅")
public class ReportingExportController {

  private final AccessService access;
  private final ReportingExportService exports;
  private final ReportingExportRenderer renderer;

  public ReportingExportController(
      AccessService access, ReportingExportService exports, ReportingExportRenderer renderer) {
    this.access = access;
    this.exports = exports;
    this.renderer = renderer;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  @GetMapping("/export/sources")
  @Operation(
      summary = "导出能力声明：可用格式、可导出字段、排序、筛选与阈值",
      description =
          "🔴 **大导出走异步任务并给出有效期**：syncRowThreshold 是同步导出的行数上限，"
              + "超过它同步端点会明确拒绝（409），请改用异步任务。"
              + "传 reportId 会额外返回这份报表自己的可导出列（**只有它的维度与度量**）。")
  public ReportingExportModels.ExportSubscriptionSourcesResponse sources(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String reportId) {
    return call(
        auth,
        "reporting.custom-report.read",
        actor -> exports.sources(actor.tenantId(), actor.userId(), reportId));
  }

  // ================================================================ 受理（异步）

  @PostMapping("/export/jobs")
  @Operation(
      summary = "发起一次异步导出（已受理，文件在后台生成）",
      description =
          "🔴 **返回即「已受理」，不是「已完成」** —— 大文件异步导出是蓝图原话。"
              + "文件与行数要轮询任务详情才拿得到。")
  public ReportingExportModels.ExportSubscriptionJobView create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ReportingExportModels.ExportSubscriptionJobCreateRequest request) {
    return call(
        auth,
        "reporting.custom-report.write",
        actor -> exports.create(actor.tenantId(), actor.userId(), request));
  }

  @GetMapping("/export/jobs")
  @Operation(summary = "导出任务清单（真分页 + 真搜索 + 真筛选）")
  public ReportingExportModels.ExportSubscriptionJobPage jobs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String format,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "reporting.custom-report.read",
        actor -> exports.jobs(actor.tenantId(), status, format, keyword, page, size));
  }

  @GetMapping("/export/jobs/{jobId}")
  @Operation(
      summary = "导出任务详情（含状态、下载有效期、下载就绪标记）",
      description =
          "🔴 **下载就绪标记（downloadable）由服务端算** —— 前端不自己判断过期，"
              + "过期后下载端点会回 410 Gone。")
  public ReportingExportModels.ExportSubscriptionJobView job(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String jobId) {
    return call(
        auth,
        "reporting.custom-report.read",
        actor -> exports.job(actor.tenantId(), uuid(jobId, "导出任务 id")));
  }

  @GetMapping("/export/jobs/{jobId}/file")
  @Operation(
      summary = "下载导出文件（CSV / XLSX / PDF，带下载有效期）",
      description =
          "🔴 **有效期由服务端判**：过期回 410 Gone 并把状态推进到 EXPIRED、清理文件 —— "
              + "「有效期过了文件还在」等于有效期没生效。"
              + "⚠️ **这个端点刻意不包在 access.run 的事务里** —— 过期时要在回 410 之前"
              + "先把「推进到 EXPIRED + 删文件」提交掉；包在事务里那次写会被异常一起回滚。")
  public ResponseEntity<byte[]> file(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String jobId) {
    var actor = access.require(auth, "reporting.custom-report.read");
    // ⚠️ 不套 access.run：见上面的说明（过期推进必须先提交）。
    var file = exports.file(actor.tenantId(), uuid(jobId, "导出任务 id"));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, file.contentType())
        .header(
            HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.fileName() + "\"")
        .body(file.content());
  }

  @PostMapping("/export/jobs/{jobId}/cancel")
  @Operation(summary = "作废一份导出（已生成文件的也不允许再下载）")
  public ReportingExportModels.ExportSubscriptionJobView cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String jobId) {
    return call(
        auth,
        "reporting.custom-report.write",
        actor -> exports.cancel(actor.tenantId(), uuid(jobId, "导出任务 id")));
  }

  @PostMapping("/export/sync")
  @Operation(
      summary = "同步导出（**只允许小结果集**）",
      description =
          "🔴 超过 syncRowThreshold 行会回 **409** 并说清「请改用异步任务」—— "
              + "**不截断**（截断出来的文件看上去完整，其实少了一半数据）。")
  public ResponseEntity<byte[]> sync(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ReportingExportModels.ExportSubscriptionJobCreateRequest request) {
    var actor = access.require(auth, "reporting.custom-report.write");
    var response =
        access.run(
            actor,
            "reporting.custom-report.write",
            () -> exports.sync(actor.tenantId(), actor.userId(), request, renderer));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, response.contentType())
        .header(
            HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + response.fileName() + "\"")
        .body(response.content());
  }

  // ================================================================ 参数解析

  /** uuid 路径参数（格式不对 ⇒ 400，不是 404 —— 那是**请求**坏了，不是资源不存在）。 */
  private UUID uuid(String raw, String label) {
    try {
      return UUID.fromString(raw);
    } catch (RuntimeException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, label + "格式不正确（收到的是「" + raw + "」）");
    }
  }
}
