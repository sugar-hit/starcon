package com.starcon.channel;

import static com.starcon.channel.ProductCertificationModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 16.2.2.2 认证与资质。
 *
 * <p>蓝图：「管理产品认证与资质（CE、FCC、RoHS、PSE、FDA、CCC 等）：认证编号、有效期、适用 SKU、
 * 对应类目要求，支持上传扫描件与到期提醒。输入：认证录入；文件上传；有效期。输出：认证档案 +
 * 到期提醒 + 覆盖情况统计（哪些 SKU 缺认证）。关系：上游 = 6.1.2.3 供应商资质；下游 = 16.2.2.1 预检、
 * 4 刊登。<b>认证缺失是 listing 被抑制与下架的高频原因</b>，且属于"上架时不管、出事才补"的典型滞后项。」
 *
 * <p>🔴 端点清单与蓝图的三条输出一一对应：
 *
 * <ul>
 *   <li><b>认证档案</b> —— {@code /certifications}（真分页）、{@code /certifications/{id}}（详情，
 *       含适用 SKU / 对应类目 / 扫描件）、{@code POST /certifications}（登记）、
 *       {@code PUT /certifications/{id}}（修改，行级乐观锁）、{@code POST /certifications/{id}/revoke}（撤销）。
 *   <li><b>到期提醒</b> —— {@code /expiry-reminders}（临近到期 / 已过期，按剩余天数升序）。
 *   <li><b>覆盖情况统计</b> —— {@code /coverage}（哪些 SKU 缺认证，按 SKU / 类目 / 类型三个口径）、
 *       {@code /coverage/skus/{skuId}}（单个 SKU 明细）。
 * </ul>
 *
 * <p>🔴 支撑这三样输出的还有：{@code /types}（认证类型受控词表，租户可维护）、
 * {@code /policy}（租户级「即将过期」窗口）、{@code /requirements}（要求侧登记）、
 * {@code /sku-candidates} 与 {@code /category-candidates}（catalog 的只读候选）、
 * {@code /certifications/{id}/documents}（扫描件上传）。
 *
 * <p>🔴 <b>扫描件上传有两个形状，同一个路径</b>：JSON（base64 本体，浏览器同源代理走这条，
 * 因为代理只转发 JSON 且请求体有上限）与 multipart（直连调用走这条）。两条都落到同一份
 * 受管存储与同一张元数据表 —— 不是两套实现。
 *
 * <p>🔴 <b>权限码复用</b> identity 已有的 {@code channel.operation.*}（与 4.1.1.1 listing 列表、
 * 16.2.2.1 合规预检同族），identity 服务零改动。读用 read，写用 create / update / delete。
 *
 * <p>🔴 与 16.2.2.1 合规预检<b>不共用前缀</b>：那边是 {@code /channel/compliance-precheck}，
 * 本控制器是 {@code /channel/product-certifications}。
 */
@RestController
@RequestMapping("/channel/product-certifications")
public class ProductCertificationController {

  private final AccessService access;
  private final ProductCertificationService service;

  public ProductCertificationController(AccessService access, ProductCertificationService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：到期状态口径、覆盖统计口径、词表口径、上下游与边界。
   *
   * <p>前端据此把「读不到」显示成「读不到」而不是空列表，把「量不到」显示成未知而不是 0。
   */
  @GetMapping("/capabilities")
  public ProductCertificationCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 概览 ----

  /** 概览（档案数 / 有效 / 即将过期 / 已过期 / 已撤销 / 覆盖与缺口）。 */
  @GetMapping("/overview")
  public CertificationOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.overview());
  }

  // ---- 认证类型词表 ----

  /** 生效词表（内置 ∪ 租户，租户行优先）。 */
  @GetMapping("/types")
  public java.util.List<CertificationTypeRow> types(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.types());
  }

  /** 新增 / 覆盖租户字典行（内置类型也能改名与停用）。 */
  @PutMapping("/types/{code}")
  public CertificationTypeRow upsertType(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String code,
      @Valid @RequestBody CertificationTypeInput input) {
    return call(auth, "channel.operation.update", a -> service.upsertType(code, input));
  }

  /** 删除租户字典行（回到内置默认；仍被引用时 409）。 */
  @DeleteMapping("/types/{code}")
  public java.util.Map<String, Boolean> deleteType(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String code) {
    return call(
        auth,
        "channel.operation.delete",
        a -> {
          service.deleteType(code);
          return java.util.Map.of("deleted", true);
        });
  }

  // ---- 租户级策略 ----

  /** 租户级「即将过期」窗口。 */
  @GetMapping("/policy")
  public CertificationPolicyView policy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.policy());
  }

  /** 改租户级「即将过期」窗口。 */
  @PutMapping("/policy")
  public CertificationPolicyView updatePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CertificationPolicyInput input) {
    return call(
        auth, "channel.operation.update", a -> service.updatePolicy(input, a.userId()));
  }

  // ---- 认证档案 ----

  /** 认证档案台账（真分页；可按类型 / 状态 / 到期状态 / 关键词 / SKU / 类目筛）。 */
  @GetMapping("/certifications")
  public CertificationPage certifications(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String certificationTypeCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String expiryState,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) UUID categoryId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.certifications(
                certificationTypeCode, status, expiryState, q,
                skuId == null ? null : skuId.toString(),
                categoryId == null ? null : categoryId.toString(),
                page,
                size));
  }

  /** 登记一张认证。 */
  @PostMapping("/certifications")
  public CertificationDetail createCertification(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ProductCertificationInput input) {
    return call(auth, "channel.operation.create", a -> service.createCertification(input));
  }

  /** 认证档案详情（含适用 SKU / 对应类目 / 扫描件）。跨租户与不存在返回逐字相同的 404。 */
  @GetMapping("/certifications/{id}")
  public CertificationDetail certification(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.certification(id));
  }

  /** 修改一张认证（行级乐观锁，版本不符 409）。 */
  @PutMapping("/certifications/{id}")
  public CertificationDetail updateCertification(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody ProductCertificationInput input) {
    return call(auth, "channel.operation.update", a -> service.updateCertification(id, input));
  }

  /** 撤销一张认证（必须写明理由；撤销与过期是两件事）。 */
  @PostMapping("/certifications/{id}/revoke")
  public CertificationDetail revokeCertification(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody CertificationRevokeInput input) {
    return call(auth, "channel.operation.update", a -> service.revokeCertification(id, input));
  }

  // ---- 适用 SKU ----

  /** 给认证挂一个适用 SKU。 */
  @PostMapping("/certifications/{id}/skus")
  public CertificationSkuBinding attachSku(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody CertificationSkuInput input) {
    return call(auth, "channel.operation.create", a -> service.attachSku(id, input, auth));
  }

  /** 解挂一个适用 SKU。 */
  @DeleteMapping("/certifications/{id}/skus/{skuId}")
  public java.util.Map<String, Boolean> detachSku(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @PathVariable String skuId) {
    return call(
        auth,
        "channel.operation.delete",
        a -> {
          service.detachSku(id, skuId);
          return java.util.Map.of("deleted", true);
        });
  }

  // ---- 对应类目要求 ----

  /** 给认证挂一个对应类目要求。 */
  @PostMapping("/certifications/{id}/categories")
  public CertificationCategoryBinding attachCategory(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody CertificationCategoryInput input) {
    return call(auth, "channel.operation.create", a -> service.attachCategory(id, input));
  }

  /** 解挂一个对应类目要求。 */
  @DeleteMapping("/certifications/{id}/categories/{categoryId}")
  public java.util.Map<String, Boolean> detachCategory(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @PathVariable String categoryId) {
    return call(
        auth,
        "channel.operation.delete",
        a -> {
          service.detachCategory(id, categoryId);
          return java.util.Map.of("deleted", true);
        });
  }

  // ---- 扫描件 ----

  /** 扫描件清单。 */
  @GetMapping("/certifications/{id}/documents")
  public java.util.List<CertificationDocumentRow> documents(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.certification(id).documents());
  }

  /**
   * 上传扫描件（JSON 形状：文件本体走 base64）。
   *
   * <p>浏览器同源代理只转发 JSON，所以页面走这一条；直连客户端可以用同一路径的 multipart 形状。
   */
  @PostMapping(path = "/certifications/{id}/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
  public CertificationDocumentRow uploadDocument(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody CertificationDocumentInput input) {
    return call(
        auth, "channel.operation.create", a -> service.uploadDocument(id, input, a.userId()));
  }

  /** 上传扫描件（multipart 形状：直连调用走这一条，与 JSON 形状落到同一处存储与元数据）。 */
  @PostMapping(
      path = "/certifications/{id}/documents",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public CertificationDocumentRow uploadDocumentMultipart(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestPart("file") MultipartFile file,
      @RequestParam(defaultValue = "ORIGINAL") String documentKind,
      @RequestParam(required = false) String note) {
    return call(
        auth,
        "channel.operation.create",
        a -> {
          try {
            return service.uploadDocument(
                id,
                file.getBytes(),
                file.getOriginalFilename(),
                file.getContentType(),
                documentKind,
                note,
                a.userId());
          } catch (java.io.IOException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "扫描件读取失败：" + e.getMessage());
          }
        });
  }

  /** 扫描件元数据。 */
  @GetMapping("/documents/{documentId}")
  public CertificationDocumentRow document(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String documentId) {
    return call(auth, "channel.operation.read", a -> service.document(documentId));
  }

  /**
   * 读回扫描件本体（真的从受管存储里读）。
   *
   * <p>🔴 浏览器同源代理只把 JSON / CSV / ZPL / HTML 原样透传，**二进制不在白名单里** ——
   * 所以页面上不靠这条路径下载文件；这条是给直连调用方（运维脚本、归档任务）用的。
   */
  @GetMapping("/documents/{documentId}/file")
  public ResponseEntity<byte[]> documentFile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String documentId) {
    return call(
        auth,
        "channel.operation.read",
        a -> {
          CertificationDocumentRow row = service.document(documentId);
          byte[] content = service.documentContent(documentId);
          String name = row.documentName() == null || row.documentName().isBlank()
              ? "document"
              : row.documentName();
          return ResponseEntity.ok()
              .header(
                  HttpHeaders.CONTENT_DISPOSITION,
                  "attachment; filename=\"" + name.replace("\"", "") + "\"")
              .contentType(MediaType.APPLICATION_OCTET_STREAM)
              .body(content);
        });
  }

  // ---- 要求侧登记 ----

  /** 要求侧登记清单（真分页）。 */
  @GetMapping("/requirements")
  public RequirementPage requirements(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String certificationTypeCode,
      @RequestParam(required = false) UUID categoryId,
      @RequestParam(required = false) String q,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.requirements(
                skuId == null ? null : skuId.toString(),
                certificationTypeCode,
                categoryId == null ? null : categoryId.toString(),
                q,
                page,
                size));
  }

  /** 登记一条 SKU 的认证要求。 */
  @PostMapping("/requirements")
  public RequirementRow createRequirement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody RequirementInput input) {
    return call(auth, "channel.operation.create", a -> service.createRequirement(input, auth));
  }

  /** 撤销一条要求登记。 */
  @DeleteMapping("/requirements/{id}")
  public java.util.Map<String, Boolean> deleteRequirement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(
        auth,
        "channel.operation.delete",
        a -> {
          service.deleteRequirement(id);
          return java.util.Map.of("deleted", true);
        });
  }

  // ---- 覆盖情况统计 ----

  /** 覆盖情况统计（哪些 SKU 缺认证；按 SKU / 类目 / 类型三个口径）。 */
  @GetMapping("/coverage")
  public CoverageReport coverage(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.coverage());
  }

  /** 单个 SKU 的覆盖详情。 */
  @GetMapping("/coverage/skus/{skuId}")
  public CoverageSkuDetail coverageForSku(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String skuId) {
    return call(auth, "channel.operation.read", a -> service.coverageForSku(skuId));
  }

  // ---- 到期提醒 ----

  /** 到期提醒（临近到期 / 已过期，按剩余天数升序）。 */
  @GetMapping("/expiry-reminders")
  public ExpiryReminderPage expiryReminders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.expiryReminders(page, size));
  }

  // ---- 上游候选（只读） ----

  /**
   * catalog 的 SKU 候选。
   *
   * <p>🔴 catalog 不可达 ⇒ **503 + 原因**，**绝不**返回空列表：空列表会被读成「这个租户没有 SKU」。
   */
  @GetMapping("/sku-candidates")
  public CandidatePage<ProductCertificationSkuCandidate> skuCandidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String q,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.skuCandidates(q, page, size, auth));
  }

  /**
   * catalog 的内部类目候选。
   *
   * <p>🔴 catalog 不可达 ⇒ **503 + 原因**。
   */
  @GetMapping("/category-candidates")
  public CandidatePage<CategoryCandidate> categoryCandidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String treeId) {
    return call(auth, "channel.operation.read", a -> service.categoryCandidates(treeId, auth));
  }
}
