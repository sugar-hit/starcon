package com.starcon.channel;

import static com.starcon.channel.BrandCredentialModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 16.4.2.2 授权凭证。
 *
 * <p>蓝图：「集中存储与核验授权凭证文件：真伪验证记录、文件版本、签署方信息，支持一键核验。
 * 输入：凭证上传；核验操作。输出：凭证档案 + 核验记录。
 * 关系：上游 = 16.4.2.1；下游 = 16.4.1.2。<b>凭证需保留原始文件与元数据，争议时是唯一证据。</b>」
 *
 * <p>🔴 端点清单：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明：真伪怎么判、结论口径、边界与上下游。
 *   <li>{@code GET /overview} —— 概览（凭证数 / 有效 / 临期 / 过期 / 已撤销 / 核验通过 / 未通过 / 从未核验）。
 *   <li>{@code GET /credentials} —— 凭证档案列表（**真分页 + 真筛选**：关键词 / 状态 / 到期状态 / 核验状态 / 类型 / 授权）。
 *   <li>{@code POST /credentials} —— 登记凭证档案。
 *   <li>{@code GET /credentials/{id}} —— 单条凭证（档案 + 签署方 + 文件版本 + 最近的核验记录）。
 *   <li>{@code PUT /credentials/{id}} —— 修改凭证档案（**不**碰文件版本与核验记录）。
 *   <li>{@code POST /credentials/{id}/revoke} —— 撤销凭证（**必须写明理由**，撤销是终态）。
 *   <li>{@code POST /credentials/{id}/signatories} / {@code DELETE .../signatories/{sid}} —— 签署方。
 *   <li>{@code GET /credentials/{id}/documents} —— 文件版本清单（只追加）。
 *   <li>{@code POST /credentials/{id}/documents} —— 上传凭证文件（JSON base64 / multipart）= **新版本**。
 *   <li>{@code GET /documents/{id}} / {@code GET /documents/{id}/file} —— 文件元数据 / 文件本体（二进制）。
 *   <li>{@code POST /credentials/{id}/verify} —— 🔴 **一键核验**（落一条不可改写的核验记录）。
 *   <li>{@code POST /credentials/verify-batch} —— 批量核验（一次最多 50 个）。
 *   <li>{@code GET /verifications} —— 核验记录历史（**真分页**）。
 *   <li>{@code GET /authorizations} —— 上游 16.4.2.1 的授权档案候选（**同库只读**）。
 * </ul>
 *
 * <p>🔴 <b>刻意没有 DELETE 凭证</b>：凭证是「争议时是唯一证据」的那份东西，
 * 合规台账不做物理删除。撤销走 {@code POST /credentials/{id}/revoke}。
 * 文件版本与核验记录**只追加**，DB 层有触发器钉住。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 4.1.1.1 listing 列表、
 * 16.2.2.1 合规预检、16.2.2.2 认证与资质、16.4.2.1 品牌授权同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/brand-credentials")
public class BrandCredentialController {

  private final AccessService access;
  private final BrandCredentialService service;

  public BrandCredentialController(AccessService access, BrandCredentialService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /** 能力声明：真伪怎么判、结论口径、边界与上下游。 */
  @GetMapping("/capabilities")
  public BrandCredentialCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 概览 ----

  /** 概览（页面顶部的卡片）。 */
  @GetMapping("/overview")
  public BrandCredentialOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.overview());
  }

  // ---- 凭证档案 ----

  /** 凭证档案列表（真分页 + 真筛选）。 */
  @GetMapping("/credentials")
  public BrandCredentialPage credentials(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String expiryState,
      @RequestParam(required = false) String verificationState,
      @RequestParam(required = false) String credentialType,
      @RequestParam(required = false) String authorizationId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.credentials(
                q, status, expiryState, verificationState, credentialType, authorizationId, page, size));
  }

  /** 登记一份授权凭证。 */
  @PostMapping("/credentials")
  public BrandCredentialDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BrandCredentialRegistrationInput input) {
    return call(auth, "channel.operation.create", a -> service.create(input));
  }

  /** 单条凭证（档案 + 签署方 + 文件版本 + 最近的核验记录）。跨租户与不存在返回逐字相同的 404。 */
  @GetMapping("/credentials/{id}")
  public BrandCredentialDetail credential(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.credential(id));
  }

  /** 修改一份凭证（文件版本与核验记录不受影响 —— 那是追加式凭据）。 */
  @PutMapping("/credentials/{id}")
  public BrandCredentialDetail update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody BrandCredentialRegistrationInput input) {
    return call(auth, "channel.operation.update", a -> service.update(id, input));
  }

  /** 撤销一份凭证（必须写明理由；撤销是终态，不提供「恢复」）。 */
  @PostMapping("/credentials/{id}/revoke")
  public BrandCredentialDetail revoke(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody BrandCredentialRevokeInput input) {
    return call(auth, "channel.operation.update", a -> service.revoke(id, input.reason()));
  }

  // ---- 签署方 ----

  /** 签署方清单。 */
  @GetMapping("/credentials/{id}/signatories")
  public List<BrandCredentialSignatoryRow> signatories(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.signatories(id));
  }

  /** 登记一个签署方。 */
  @PostMapping("/credentials/{id}/signatories")
  public BrandCredentialDetail addSignatory(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody BrandCredentialSignatoryInput input) {
    return call(auth, "channel.operation.create", a -> service.addSignatory(id, input));
  }

  /** 移除一个签署方（凭证本身、文件版本与核验记录都不受影响）。 */
  @DeleteMapping("/credentials/{id}/signatories/{signatoryId}")
  public BrandCredentialDetail removeSignatory(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @PathVariable String signatoryId) {
    return call(auth, "channel.operation.delete", a -> service.removeSignatory(id, signatoryId));
  }

  // ---- 凭证文件版本 ----

  /** 凭证文件版本清单（新版本在前）。 */
  @GetMapping("/credentials/{id}/documents")
  public List<BrandCredentialDocumentRow> documents(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.documents(id));
  }

  /**
   * 上传凭证文件（JSON 形状：文件本体走 base64）。
   *
   * <p>浏览器同源代理只转发 JSON，所以页面走这一条；直连客户端可以用同一路径的 multipart 形状。
   */
  @PostMapping(path = "/credentials/{id}/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
  public BrandCredentialDocumentRow uploadDocument(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody BrandCredentialDocumentInput input) {
    return call(auth, "channel.operation.create", a -> service.uploadDocument(id, input, a.userId()));
  }

  /** 上传凭证文件（multipart 形状：直连调用走这一条，与 JSON 形状落到同一处存储与元数据）。 */
  @PostMapping(
      path = "/credentials/{id}/documents",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public BrandCredentialDocumentRow uploadDocumentMultipart(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestPart("file") MultipartFile file,
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
                note,
                a.userId());
          } catch (java.io.IOException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "凭证文件读取失败：" + e.getMessage());
          }
        });
  }

  /** 凭证文件元数据。 */
  @GetMapping("/documents/{documentId}")
  public BrandCredentialDocumentRow document(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String documentId) {
    return call(auth, "channel.operation.read", a -> service.document(documentId));
  }

  /**
   * 凭证文件本体（二进制）。
   *
   * <p>⚠️ 浏览器同源代理<b>不透传二进制</b>：这条是给直连调用方（归档任务 / 争议取证）用的，
   * 页面上的文件清单显示元数据与摘要，不靠它下载。
   */
  @GetMapping("/documents/{documentId}/file")
  public byte[] documentFile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String documentId) {
    return call(auth, "channel.operation.read", a -> service.documentContent(documentId));
  }

  // ---- 核验 ----

  /**
   * 🔴 一键核验：把文件从受管存储里读回来**重算 sha256**，与登记摘要逐字比对。
   *
   * <p>核验会落一条**不可改写**的核验记录（追加式凭据）。
   */
  @PostMapping("/credentials/{id}/verify")
  public BrandCredentialVerificationResult verify(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.create", a -> service.verify(id, a.userId()));
  }

  /** 批量核验（一次最多 50 个，逐条落凭据）。 */
  @PostMapping("/credentials/verify-batch")
  public BrandCredentialVerificationBatchResult verifyBatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BrandCredentialVerificationBatchInput input) {
    return call(
        auth, "channel.operation.create", a -> service.verifyBatch(input.credentialIds(), a.userId()));
  }

  /** 核验记录历史（真分页）。 */
  @GetMapping("/verifications")
  public BrandCredentialVerificationPage verifications(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String credentialId,
      @RequestParam(required = false) String conclusion,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth, "channel.operation.read", a -> service.verifications(credentialId, conclusion, page, size));
  }

  // ---- 上游候选（16.4.2.1，同库只读） ----

  /** 上游 16.4.2.1 的授权档案候选（同库只读）。 */
  @GetMapping("/authorizations")
  public BrandCredentialAuthorizationOptionPage authorizations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword) {
    return call(auth, "channel.operation.read", a -> service.authorizationOptions(keyword));
  }
}
