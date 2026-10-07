package com.starcon.catalog;

import static com.starcon.catalog.ImportValidationModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 3.8.1.2 校验报告的端点。
 *
 * <p><b>全部挂在 {@code import-jobs/{id}/report 之下</b>，而不是另起一个 {@code validation-reports}
 * 前缀：报告不是独立资源，它是<b>某一个导入任务那一轮预校验结论</b>的视图。
 * 挂在任务下面之后，「这份报告属于哪个任务」这件事在 URL 上就自明，
 * 租户隔离也只需要守住一条父路径（父任务读不到，子报告自然读不到）。
 *
 * <pre>
 *   GET  /catalog/import-jobs/{id}/report                  报告总览（读时物化）
 *   POST /catalog/import-jobs/{id}/report                  重新生成报告
 *   GET  /catalog/import-jobs/{id}/report/issues           错误明细表（分页 + 三种筛选）
 *   GET  /catalog/import-jobs/{id}/report/exports          导出登记列表
 *   POST /catalog/import-jobs/{id}/report/exports          发起一次导出（登记）
 *   GET  /catalog/import-jobs/{id}/report/exports/{eid}/file   下载导出的 CSV
 * </pre>
 *
 * <p><b>权限沿用 {@code catalog.spu.read} / {@code catalog.spu.update}</b>，与 3.8.1.1 同一口径：
 * 报告读的是导入结果、写的是导入任务自己的派生数据，不引入新词表
 * （新词表要改 identity 服务的迁移，属于另一个服务的写入所有权）。
 * 生成与导出算写动作（它们真的落库），所以走 {@code spu.update}。
 *
 * <p><b>租户只来自已认证成员关系</b>：本控制器没有任何参数是 tenantId，
 * 一律取 {@code AccessService.Access.tenantId()}。客户端传 {@code X-Tenant-Id} 不会被读，
 * 带伪造头访问别人的任务得到的是 404（父任务不可见 → 子报告不可见）。
 */
@RestController
@RequestMapping("/catalog")
public class ImportValidationController {
  private final AccessService access;
  private final ImportValidationService reports;

  public ImportValidationController(AccessService access, ImportValidationService reports) {
    this.access = access;
    this.reports = reports;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 报告总览：四段行数 + 按蓝图五类分的统计 + 按字段列分的统计。
   *
   * <p><b>第一次 GET 顺带生成报告</b>（返回 200 而不是 404，客户端不用先吃一个错误再重试）；
   * 之后每次 GET 返回的都是<b>同一份快照</b>，带 {@code stale} 标记说明它是否已经过期。
   * 客户端因此只需要处理一种情况：拿到报告，并读 {@code stale} 决定要不要提示重新生成。
   */
  @GetMapping("/import-jobs/{id}/report")
  public ImportValidationSummary report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> reports.summary(a.tenantId(), id, a.userId()));
  }

  /**
   * 重新生成报告（改了映射或补了规则之后刷新）。
   *
   * <p>显式刷新是「我知道结论变了」的确认动作 —— 与 GET 的自动物化分开，
   * 页面上是一个按钮而不是每次打开都悄悄重算。
   */
  @PostMapping("/import-jobs/{id}/report")
  public ImportValidationReportRow regenerate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth, @PathVariable UUID id) {
    return call(auth, "spu.update", a -> reports.regenerate(a.tenantId(), id, a.userId()));
  }

  /**
   * 错误明细表（蓝图的「错误明细表」输出）。
   *
   * <p>三种筛选与三种用户意图一一对应：
   * {@code severity}（只要必须修正的 / 只要可跳过的）、
   * {@code category}（按蓝图五类批量改）、{@code rowNo}（定位到某一行）。
   * 三者可任意组合，因为「只改必填缺失里的第 47 行」是最常见的一种查法。
   */
  @GetMapping("/import-jobs/{id}/report/issues")
  public ImportValidationIssuePage issues(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) Integer rowNo,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "spu.read",
        a -> reports.issues(a.tenantId(), id, a.userId(), severity, category, rowNo, page, size));
  }

  /** 导出登记列表：这批数据导过什么、基于哪一轮结论、谁导的。 */
  @GetMapping("/import-jobs/{id}/report/exports")
  public ImportValidationExportPage exports(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth, @PathVariable UUID id) {
    return call(auth, "spu.read", a -> reports.exports(a.tenantId(), id, a.userId()));
  }

  /**
   * 发起一次导出（登记，返回登记行而不是字节）。
   *
   * <p>与 3.1.2.1 的 {@code /catalog/skus/export}、3.8.1.1 的模板下载同一处理方式：
   * <b>导出文件走普通链接下载</b>，而不是把 CSV 塞进 JSON 响应。
   * 分两步是为了让「导出了什么」成为一条可查的事实（登记行带内容哈希与生成时刻），
   * 而不是一次拿完就消失的副作用。
   */
  @PostMapping("/import-jobs/{id}/report/exports")
  public ImportValidationExportRow createExport(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ImportValidationExportInput input) {
    return call(auth, "spu.update", a -> reports.createExport(a.tenantId(), id, input, a.userId()));
  }

  /**
   * 下载导出文件（CSV，带 UTF-8 BOM）。
   *
   * <p>字节由报告现算，库里不存 blob。下载链接不带也不接受任何租户参数 ——
   * 租户上下文由 Authorization 头解析，与 3.8.1.1 的模板下载完全同一处理方式。
   */
  @GetMapping(value = "/import-jobs/{id}/report/exports/{exportId}/file", produces = "text/csv")
  public ResponseEntity<byte[]> exportFile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id, @PathVariable UUID exportId) {
    var actor = access.require(auth, "catalog.spu.read");
    var file =
        access.run(actor, "catalog.spu.read", () -> reports.exportFile(actor.tenantId(), id, exportId, actor.userId()));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.fileName() + "\"")
        .body(file.content());
  }
}