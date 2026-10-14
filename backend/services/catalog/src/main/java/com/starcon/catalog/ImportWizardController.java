package com.starcon.catalog;

import static com.starcon.catalog.ImportWizardModels.*;

import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 3.8.1.1 导入向导的端点。
 *
 * <p>路由挂在 {@code /catalog/import-*} 之下，与 3.1.2.1 的 {@code /catalog/skus}、 3.1.2.3
 * 的编码条码工作区<b>并列</b>。台账里 3.8.1.1 的 L3 锚点是 {@code —}（没有独立列表页）， 所以这里没有「导入列表」页面路由，入口是商品域下的独立向导页。
 *
 * <p><b>五个环节各一个端点，顺序即依赖</b>：
 *
 * <pre>
 *   1 下载模板   GET  /catalog/import-templates/{id}/file
 *   2 上传文件   POST /catalog/import-jobs              （multipart）
 *   3 字段映射   PUT  /catalog/import-jobs/{id}/mapping
 *   4 预校验     POST /catalog/import-jobs/{id}/prevalidate
 *   5 执行       POST /catalog/import-jobs/{id}/execute
 * </pre>
 *
 * <p><b>第 4、5 步是「已受理」而不是「已完成」</b>：蓝图写死「导入是异步任务，大文件不阻塞页面」， 所以这两个 POST 推进任务状态就返回，真实结果由页面轮询任务详情拿。
 * 前端**绝不能**在 POST 返回后显示「导入完成」—— 任务行上的 applied/failed/skipped 是库里可查的事实，不是这次响应的承诺。
 *
 * <p><b>权限沿用 {@code catalog.spu.read} / {@code catalog.spu.update}</b>： 导入写的就是商品主数据，挂在已有词表上（与
 * 3.5.2.1 / 3.5.2.2 同一口径）， 新词表要改 identity 服务的迁移，属于另一个服务的写入所有权。
 *
 * <p><b>租户只来自已认证成员关系</b>：本控制器没有任何一个参数是 tenantId， 一律取 {@code AccessService.Access.tenantId()}。客户端传
 * {@code X-Tenant-Id} 不会被读。
 */
@RestController
@RequestMapping("/catalog")
public class ImportWizardController {
  private final AccessService access;
  private final ImportTemplateService templates;
  private final ImportJobService jobs;

  public ImportWizardController(
      AccessService access, ImportTemplateService templates, ImportJobService jobs) {
    this.access = access;
    this.templates = templates;
    this.jobs = jobs;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  // ================================================================ 1 下载模板

  /** 模板字典列表（分页 + 按目标对象/关键词过滤）。 */
  @GetMapping("/import-templates")
  public ImportTemplatePage templates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String targetEntity,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth, "spu.read", a -> templates.templates(a.tenantId(), targetEntity, q, page, size));
  }

  /** 模板详情（含列定义）。 */
  @GetMapping("/import-templates/{id}")
  public ImportTemplateDetail template(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> templates.detail(a.tenantId(), id));
  }

  /**
   * 新建模板。
   *
   * <p>列不传时套标准列（{@code ImportRules.defaultColumns}）， 所以「先建一个能用的 SPU 模板」只需要给 code / name /
   * targetEntity。
   */
  @PostMapping("/import-templates")
  public ImportTemplateDetail createTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ImportTemplateInput input) {
    return call(auth, "spu.update", a -> templates.create(a.tenantId(), input));
  }

  /** 改模板的名称 / 备注 / 启用状态（**不可改列**：见 {@code ImportTemplateService.update}）。 */
  @PutMapping("/import-templates/{id}")
  public ImportTemplateDetail updateTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ImportTemplateInput input) {
    return call(auth, "spu.update", a -> templates.update(a.tenantId(), id, input));
  }

  @DeleteMapping("/import-templates/{id}")
  public Map<String, Object> deleteTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth,
        "spu.update",
        a -> {
          templates.delete(a.tenantId(), id);
          return Map.of("deleted", true);
        });
  }

  /**
   * 下载模板 CSV：列头一行 + 示例行。
   *
   * <p>用 <b>普通链接下载</b>而不是 {@code api()}：它是 CSV 而非 JSON， 与 3.1.2.1 的 {@code /catalog/skus/export}
   * 同一处理方式。 租户上下文由 Authorization 头解析，下载链接本身不带也不接受任何租户参数。
   */
  @GetMapping(value = "/import-templates/{id}/file", produces = "text/csv")
  public ResponseEntity<byte[]> templateFile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    var actor = access.require(auth, "catalog.spu.read");
    var file = access.run(actor, "catalog.spu.read", () -> templates.csv(actor.tenantId(), id));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.fileName() + "\"")
        .body(file.content());
  }

  // ================================================================ 3 字段映射配置

  @GetMapping("/import-field-mappings")
  public ImportFieldMappingPage mappings(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID templateId) {
    return call(auth, "spu.read", a -> templates.mappings(a.tenantId(), templateId));
  }

  @PostMapping("/import-field-mappings")
  public ImportFieldMappingRow saveMapping(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ImportFieldMappingInput input) {
    return call(auth, "spu.update", a -> templates.saveMapping(a.tenantId(), input));
  }

  @DeleteMapping("/import-field-mappings/{id}")
  public Map<String, Object> deleteMapping(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth,
        "spu.update",
        a -> {
          templates.deleteMapping(a.tenantId(), id);
          return Map.of("deleted", true);
        });
  }

  // ================================================================ 2 上传文件

  /**
   * 上传文件：解析 → 建任务 → 逐行落库。返回「已受理」。
   *
   * <p>用 {@code multipart/form-data} 而不是把 CSV 当 JSON 字符串塞进 body： 运营上传的就是文件，走文件通道才能带上真实文件名与大小，
   * 而这两样是「导进去的到底是哪份文件」的凭据。
   *
   * <p><b>409 的含义</b>：同一租户已有同类导入任务在进行中（DB 部分唯一索引裁决）。 错误信息里点名当前在跑的那个任务的文件名与状态，运营能直接去取消它。
   */
  @PostMapping(value = "/import-jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ImportUploadSummary upload(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID templateId,
      @RequestParam String importMode,
      @RequestPart("file") MultipartFile file,
      @RequestParam(required = false) String mapping) {
    return call(
        auth,
        "spu.update",
        a -> {
          byte[] content;
          try {
            content = file.getBytes();
          } catch (IOException e) {
            throw ImportJobService.notFound("文件读取失败，请重新上传");
          }
          return jobs.upload(
              a.tenantId(),
              templateId,
              importMode,
              parseMapping(mapping),
              file.getOriginalFilename(),
              content);
        });
  }

  /** 改本次任务的映射（可选同时存成可复用配置）。 */
  @PutMapping("/import-jobs/{id}/mapping")
  public ImportJobRow mapping(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ImportMappingInput input) {
    return call(auth, "spu.update", a -> jobs.setMapping(a.tenantId(), id, input));
  }

  // ================================================================ 4 预校验 / 5 执行

  /**
   * 请求预校验：已受理，结果轮询任务详情。
   *
   * <p>重跑（已预校验的任务再跑一次）是允许的，且会先清掉上一轮的行结论 —— 页面上不能同时挂着两轮互相矛盾的结论。
   */
  @PostMapping("/import-jobs/{id}/prevalidate")
  public ImportJobRow prevalidate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.update", a -> jobs.requestPrevalidate(a, id));
  }

  /** 请求执行：已受理，结果轮询任务详情。 */
  @PostMapping("/import-jobs/{id}/execute")
  public ImportJobRow execute(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.update", a -> jobs.requestExecute(a, id));
  }

  /**
   * 取消任务，释放互斥槽位。
   *
   * <p>蓝图要求「同一租户同时只允许一个同类导入任务」——而没有取消， 一个卡住的任务会把该目标对象的所有后续导入全部挡在门外。 只允许取消还没开跑的任务（PENDING /
   * PREVALIDATED），正在导入的请等它跑完。
   */
  @PostMapping("/import-jobs/{id}/cancel")
  public ImportJobRow cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.update", a -> jobs.cancel(a.tenantId(), id));
  }

  // ================================================================ 结果统计

  @GetMapping("/import-jobs")
  public ImportJobPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String targetEntity,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "spu.read", a -> jobs.jobs(a.tenantId(), targetEntity, status, page, size));
  }

  @GetMapping("/import-jobs/{id}")
  public ImportJobRow job(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> jobs.job(a.tenantId(), id));
  }

  /** 逐行结果（分页 + 按 outcome 过滤）。 */
  @GetMapping("/import-jobs/{id}/rows")
  public ImportJobRowPage rows(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String outcome,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "spu.read", a -> jobs.rows(a.tenantId(), id, outcome, page, size));
  }

  /**
   * 逐行错误明细（预校验交付物，可按 severity 过滤）。
   *
   * <p>3.8.1.2 的校验报告读这一份数据。
   */
  @GetMapping("/import-jobs/{id}/errors")
  public ImportRowErrorPage errors(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String severity,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "spu.read", a -> jobs.errors(a.tenantId(), id, severity, page, size));
  }

  /**
   * 解析可选的 mapping 查询参数（形如 {@code spu_code:商品编码,spu_name:名称}）。
   *
   * <p>上传时允许不带映射 —— 服务层会按列名自动猜一套。 这里只负责把「猜」的那部分留给服务层，显式传入的才按用户的意思解析。
   */
  private static Map<String, String> parseMapping(String raw) {
    if (raw == null || raw.isBlank()) return Map.of();
    Map<String, String> out = new java.util.LinkedHashMap<>();
    for (String pair : raw.split(",")) {
      int i = pair.indexOf(':');
      if (i <= 0) continue;
      String key = pair.substring(0, i).trim();
      String value = pair.substring(i + 1).trim();
      if (!key.isEmpty() && !value.isEmpty()) out.put(key, value);
    }
    return out;
  }
}
