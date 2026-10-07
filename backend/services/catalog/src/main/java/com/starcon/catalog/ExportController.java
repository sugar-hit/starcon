package com.starcon.catalog;

import static com.starcon.catalog.ExportModels.*;

import jakarta.validation.Valid;
import java.util.Map;
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
 * 3.8.2.1 导出的端点。
 *
 * <p>路由挂在 {@code /catalog/export-*} 之下，与 3.1.2.1 的 {@code /catalog/skus}、
 * 3.8.1.1 的 {@code /catalog/import-*} **并列**：导出不是某一个列表页的功能，
 * 它服务的是「所有列表页按当前条件导出」，所以它有自己的资源前缀与自己的记录页。
 *
 * <pre>
 *   能力声明   GET  /catalog/export-sources
 *   发起导出   POST /catalog/export-jobs            （已受理，异步生成）
 *   记录列表   GET  /catalog/export-jobs
 *   记录详情   GET  /catalog/export-jobs/{id}
 *   下载文件   GET  /catalog/export-jobs/{id}/file  （带下载有效期）
 *   作废       POST /catalog/export-jobs/{id}/cancel
 * </pre>
 *
 * <p><b>POST 返回「已受理」而不是「已完成」</b>：蓝图写死大文件异步导出。
 * 前端**绝不能**在 POST 返回后显示「导出完成」—— 记录上的 rowCount 与文件是
 * 同一次执行写出来的，不是这次响应的承诺，要轮询记录详情才拿得到。
 *
 * <p><b>租户只来自已认证成员关系</b>：本控制器没有任何一个参数是 tenantId，
 * 一律取 {@code AccessService.Access.tenantId()}。客户端传 {@code X-Tenant-Id} 不会被读。
 *
 * <p><b>权限按数据源各自的读权限判</b>：导出的就是列表数据，所以用列表页的权限词
 * （{@code catalog.spu.read} / {@code catalog.sku.read}），不新造一套「导出权限」。
 * 不给数据源权限的人，连它的导出记录都读不到。
 */
@RestController
@RequestMapping("/catalog")
public class ExportController {
  private final AccessService access;
  private final ExportJobService jobs;

  public ExportController(AccessService access, ExportJobService jobs) {
    this.access = access;
    this.jobs = jobs;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 可导出数据源与它们的字段 / 排序 / 筛选词表。
   *
   * <p>用 {@code catalog.spu.read} 取：导出服务的是整个商品域的主数据列表，
   * 字段选择器要列出全部数据源，而逐数据源的权限在发起与下载时才判。
   */
  @GetMapping("/export-sources")
  public ExportSourcePage sources(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> jobs.sourcePage());
  }

  /** 发起导出：已受理，文件在后台生成。 */
  @PostMapping("/export-jobs")
  public ExportJobRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ExportJobInput input) {
    return call(auth, "spu.read", a -> jobs.create(a, input));
  }

  /** 导出记录列表（条件、字段、行数、时间、操作人都在行上）。 */
  @GetMapping("/export-jobs")
  public ExportJobPage jobs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String sourceKey,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "spu.read", a -> jobs.jobs(a.tenantId(), sourceKey, status, page, size));
  }

  @GetMapping("/export-jobs/{id}")
  public ExportJobRow job(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> jobs.job(a.tenantId(), id));
  }

  /**
   * 下载导出文件（CSV）。
   *
   * <p>用<b>普通链接下载</b>而不是 {@code api()}：它是 CSV 而非 JSON，与
   * 3.1.2.1 的 {@code /catalog/skus/export}、3.8.1.1 的模板下载同一处理方式。
   * 租户上下文由 Authorization 头解析，下载链接本身不带也不接受任何租户参数。
   *
   * <p>有效期由服务端判：过期回 410 Gone 并清理文件，不靠前端禁用按钮。
   */
  @GetMapping(value = "/export-jobs/{id}/file", produces = "text/csv")
  public ResponseEntity<byte[]> file(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    var actor = access.require(auth, "catalog.spu.read");
    var file = access.run(actor, "catalog.spu.read", () -> jobs.file(actor, id));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + file.fileName() + "\"")
        .body(file.content());
  }

  /** 作废一份导出（含 PII 的导出导错范围时，第一件事就是让它不能再下载）。 */
  @PostMapping("/export-jobs/{id}/cancel")
  public ExportJobRow cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.update", a -> jobs.cancel(a, id));
  }

  /** 供健康检查与概览用：本租户可导出的数据源键。 */
  @GetMapping("/export-capabilities")
  public Map<String, Object> capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "spu.read",
        a -> Map.of("sources", jobs.sourcePage().items().stream().map(ExportSourceInfo::key).toList()));
  }
}