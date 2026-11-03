package com.starcon.platform;

import static com.starcon.platform.ImportExportModels.*;

import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

/**
 * 18.1.3.1「导入导出任务」的 HTTP 端点。
 *
 * <p>命名空间是 {@code /platform/io}：与 18.1.2.1 的 {@code /platform/webhooks}、
 * 18.1.2.2 的 {@code /platform/deliveries}、18.1.1.3 的 {@code /platform/call-monitor}
 * 刻意不共用前缀 —— 那是**别的模块的端点**。
 *
 * <p><b>七个环节各一个端点，顺序即依赖</b>：
 *
 * <pre>
 *   能力声明   GET  /platform/io/capabilities
 *   数据域目录 GET  /platform/io/domains
 *   概览       GET  /platform/io/overview
 *   发起导出   POST /platform/io/tasks                       （已受理，异步生成）
 *   发起导入   POST /platform/io/tasks/import                （multipart，已受理）
 *   任务列表   GET  /platform/io/tasks
 *   任务详情   GET  /platform/io/tasks/{id}                  （进度跟踪）
 *   结果下载   GET  /platform/io/tasks/{id}/file             （带下载有效期）
 *   失败行     GET  /platform/io/tasks/{id}/errors           （逐行逐列）
 *   失败行报告 GET  /platform/io/tasks/{id}/error-report     （CSV 文件）
 *   作废       POST /platform/io/tasks/{id}/cancel
 *   模板列表   GET  /platform/io/templates
 *   模板详情   GET  /platform/io/templates/{id}
 *   模板下载   GET  /platform/io/templates/{id}/file
 *   过期清理   POST /platform/io/expire
 * </pre>
 *
 * <p>🔴 <b>POST 返回「已受理」而不是「已完成」</b>：蓝图写死大文件异步。
 * 前端<b>绝不能</b>在 POST 返回后显示「导出完成」—— 任务行上的行数与文件是同一次执行写出来的，
 * 不是这次响应的承诺，要轮询任务详情才拿得到。
 *
 * <p>🔴 <b>租户只来自已认证成员关系</b>：本控制器没有任何一个参数是 tenantId，
 * 一律取 {@code AccessService.Access.tenantId()}。客户端传 {@code X-Tenant-Id} 不会被读。
 *
 * <p>权限码 {@code platform.io.read / .create / .execute / .download}：
 * 「看任务」「发起任务」「处置任务」「把文件拿走」是四件事 ——
 * 文件要**离开本系统**（进用户本地文件），所以下载是独立授权的。
 */
@RestController
@RequestMapping("/platform")
public class ImportExportController {

  private final AccessService access;
  private final ImportExportService service;
  private final ImportExportExecutor executor;

  public ImportExportController(
      AccessService access, ImportExportService service, ImportExportExecutor executor) {
    this.access = access;
    this.service = service;
    this.executor = executor;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再把工作交给服务层。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "platform.io." + permission);
    return access.run(actor, "platform.io." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明与概览

  /** 能力声明：哪些真接通、缺什么、归谁。 */
  @GetMapping("/io/capabilities")
  public ImportExportCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> service.capabilities());
  }

  /** 概览：几个数。 */
  @GetMapping("/io/overview")
  public ImportExportOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> service.overview());
  }

  /**
   * 数据域目录（**两个方向各一行**）。
   *
   * <p>🔴 没接通的那几行**照样出现在目录里**并标 {@code available=false} ——
   * 混进空态等于告诉运营「这个域已经能导了」。
   */
  @GetMapping("/io/domains")
  public java.util.List<ImportExportDomainRow> domains(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> service.domains());
  }

  // ================================================================ 任务

  /** 任务列表（真分页 + 真筛选）。 */
  @GetMapping("/io/tasks")
  public ImportExportTaskPage tasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String direction,
      @RequestParam(required = false) String domainCode,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth, "read", actor -> service.tasks(direction, domainCode, status, page, size));
  }

  /** 任务详情（**进度跟踪**：行数、状态、失败行数都在这里）。 */
  @GetMapping("/io/tasks/{id}")
  public ImportExportTaskRow task(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> service.task(id));
  }

  /**
   * 发起一个**导出**任务：已受理，文件在后台生成。
   *
   * <p>🔴 该数据域的导出**没接通**时如实答 <b>409</b> 并写明缺什么、归谁 ——
   * 绝不返回一条「已受理」然后永远不产出文件。
   */
  @PostMapping("/io/tasks")
  public ImportExportTaskRow createExport(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ImportExportCreateInput input) {
    return call(
        auth,
        "create",
        actor -> {
          var task =
              service.createExport(input.domainCode(), scopeOf(input), actor);
          executor.submit(task.id(), auth, actor);
          return task;
        });
  }

  /**
   * 发起一个**导入**任务（<b>base64 JSON</b> 形态）：上传文件，已受理。
   *
   * <p>🔴 **浏览器走这一支**：同源代理只转发 JSON，multipart 过不去。
   * 文件名与内容都在请求里 —— 那两样是「导进去的到底是哪份文件」的凭据。
   */
  @PostMapping(value = "/io/tasks/import", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ImportExportTaskRow createImportJson(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ImportExportImportInput input) {
    return call(
        auth,
        "create",
        actor -> {
          byte[] content;
          try {
            content = Base64.getDecoder().decode(input.contentBase64() == null ? "" : input.contentBase64());
          } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "文件内容不是合法的 Base64");
          }
          var task =
              service.createImport(
                  input.domainCode(), input.filename(), content, scopeOf(input.scope()), actor);
          executor.submit(task.id(), auth, actor);
          return task;
        });
  }

  /**
   * 发起一个**导入**任务：上传文件，已受理（<b>multipart 形态</b>）。
   *
   * <p>用 {@code multipart/form-data} 而不是把 CSV 当 JSON 字符串塞进 body：
   * 运营上传的就是文件，走文件通道才能带上真实文件名与大小，
   * 而这两样是「导进去的到底是哪份文件」的凭据。
   *
   * <p>⚠️ <b>这一支只对直连 API 的客户端可用</b>：浏览器的同源代理只转发 JSON，
   * 浏览器走 {@link #createImportJson}。
   */
  @PostMapping(value = "/io/tasks/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ImportExportTaskRow createImport(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String domainCode,
      @RequestPart("file") MultipartFile file,
      @RequestParam(required = false) String scope) {
    return call(
        auth,
        "create",
        actor -> {
          byte[] content;
          try {
            content = file.getBytes();
          } catch (IOException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "文件读取失败，请重新上传");
          }
          var task =
              service.createImport(
                  domainCode, file.getOriginalFilename(), content, scopeOf(scope), actor);
          executor.submit(task.id(), auth, actor);
          return task;
        });
  }

  /** 作废一个任务（导错范围时第一件事就是让它不能再下载）。 */
  @PostMapping("/io/tasks/{id}/cancel")
  public ImportExportTaskRow cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "execute", actor -> service.cancel(id, actor));
  }

  /** 失败行明细（**逐行逐列**：哪一行、哪一列、原始值、为什么）。 */
  @GetMapping("/io/tasks/{id}/errors")
  public ImportExportRowErrorPage errors(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String severity,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "read", actor -> service.rowErrors(id, severity, page, size));
  }

  /**
   * 下载导出结果 / 导入回执（CSV）。
   *
   * <p>有效期由服务端判：过期回 <b>410 Gone</b>，不靠前端禁用按钮。
   */
  @GetMapping(value = "/io/tasks/{id}/file", produces = "text/csv")
  public ResponseEntity<byte[]> file(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    var content =
        call(auth, "download", actor -> service.download(id, FileRole.RESULT));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, content.contentType())
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + content.filename() + "\"")
        .body(content.content());
  }

  /** 下载失败行报告（CSV）。 */
  @GetMapping(value = "/io/tasks/{id}/error-report", produces = "text/csv")
  public ResponseEntity<byte[]> errorReport(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    var content =
        call(auth, "download", actor -> service.download(id, FileRole.ERROR_REPORT));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, content.contentType())
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + content.filename() + "\"")
        .body(content.content());
  }

  // ================================================================ 模板

  /** 模板列表（真分页 + 真筛选）。 */
  @GetMapping("/io/templates")
  public ImportExportTemplatePage templates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String domainCode,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> service.templates(domainCode, page, size));
  }

  /** 模板详情（列定义 + 示例行）。 */
  @GetMapping("/io/templates/{id}")
  public ImportExportTemplateDetail template(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> service.template(id));
  }

  /**
   * 下载模板 CSV（列头一行 + 示例行）。
   *
   * <p>用<b>普通链接下载</b>而不是 {@code api()}：它是 CSV 而非 JSON。
   * 租户上下文由 Authorization 头解析，下载链接本身不带也不接受任何租户参数。
   */
  @GetMapping(value = "/io/templates/{id}/file", produces = "text/csv")
  public ResponseEntity<byte[]> templateFile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    var content = call(auth, "download", actor -> service.templateFile(id));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, content.contentType())
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + content.filename() + "\"")
        .body(content.content());
  }

  // ================================================================ 维护

  /**
   * 清理过期任务与文件（**外部调度**触发的一次入口）。
   *
   * <p>🔴 **任务记录保留**（它是审计事实：谁在什么时候导出了什么），
   * 只让**文件**不再可下载 —— 蓝图要的是「避免存储无限增长」，不是「抹掉历史」。
   */
  @PostMapping("/io/expire")
  public Map<String, Object> expire(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "execute",
        actor -> Map.of("expiredTasks", service.expireDue(), "failedTasks", 0));
  }

  // ================================================================ 范围解析

  /** 把范围参数解析成 map（**空值不参与筛选**）。 */
  private static Map<String, Object> scopeOf(ImportExportCreateInput input) {
    return input.scope() == null ? Map.of() : input.scope();
  }

  /** 把范围参数解析成 map（入参里已经是对象）。 */
  private static Map<String, Object> scopeOf(Map<String, Object> scope) {
    return scope == null ? Map.of() : scope;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> scopeOf(String raw) {
    if (raw == null || raw.isBlank()) return Map.of();
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper()
          .readValue(raw, Map.class);
    } catch (IOException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "范围参数不是合法的 JSON 对象");
    }
  }
}
