package com.starcon.platform;

import static com.starcon.platform.NotificationTemplateModels.*;

import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 18.5.1.1「模板管理」的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /platform/ai/templates}</b>：与 18.4.2.1 的 {@code /platform/text}
 * 同服务、不同前缀 —— 那边管的是「AI 翻译链路的产物（客服话术草稿）」，这边管的是
 * 「**通知与消息模板**：按场景 × 语言组织、版本化、可按时刻解析」。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>权限码 {@code platform.template.read / .create / .update / .delete}</b>：
 * 「删模板」是**归档 + 停用**（不物理删版本），所以它的码叫 delete 而不是 destroy ——
 * 名字要让人一眼看出它**不删历史**。
 */
@RestController
@RequestMapping("/platform/ai/templates")
public class NotificationTemplateController {

  private final AccessService access;
  private final NotificationTemplateService templates;

  public NotificationTemplateController(AccessService access, NotificationTemplateService templates) {
    this.access = access;
    this.templates = templates;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "platform.template." + permission);
    return access.run(actor, "platform.template." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明与概览

  /** 能力声明：场景词表、语言写法、变量约定、边界（哪些真接通、哪些还没交付、归谁）。 */
  @GetMapping("/capabilities")
  public NotifyTemplateCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> templates.capabilities());
  }

  /** 概览：几个数。 */
  @GetMapping("/overview")
  public NotifyTemplateOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> templates.overview());
  }

  // ================================================================ 模板

  /** 模板列表（**真分页 + 真筛选**：场景 / 语言 / 启用状态 / 归档 / 关键词）。 */
  @GetMapping
  public NotifyTemplatePage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String scene,
      @RequestParam(required = false) String language,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(defaultValue = "ACTIVE") String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth, "read", actor -> templates.list(keyword, scene, language, enabled, status, page, size));
  }

  /** 新建模板（**同时**落下第 1 版内容；不自动启用）。 */
  @PostMapping
  public NotifyTemplateRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody NotifyTemplateInput input) {
    return call(auth, "create", actor -> templates.create(input, actor));
  }

  /** 模板详情（主记录 + 版本列表 + 当前生效版本）。 */
  @GetMapping("/{templateId}")
  public NotifyTemplateDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId) {
    return call(auth, "read", actor -> templates.detail(templateId));
  }

  /** 改模板的名称与说明（**内容改不了** —— 内容只能新增版本）。 */
  @PostMapping("/{templateId}/metadata")
  public NotifyTemplateRow updateMetadata(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId,
      @Valid @RequestBody NotifyTemplateMetadataInput input) {
    return call(auth, "update", actor -> templates.updateMetadata(templateId, input, actor));
  }

  /**
   * 删除模板 = **停用 + 归档**（**不物理删**）。
   *
   * <p>🔴 版本行与启用历史原样留着：已经发出的通知要能按「当时的内容」解释清楚。
   */
  @DeleteMapping("/{templateId}")
  public NotifyTemplateRow archive(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId) {
    return call(auth, "delete", actor -> templates.archive(templateId, actor));
  }

  // ================================================================ 版本

  /** 版本列表（真分页）。 */
  @GetMapping("/{templateId}/versions")
  public NotifyTemplateVersionPage versions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> templates.versions(templateId, page, size));
  }

  /** 版本详情。 */
  @GetMapping("/{templateId}/versions/{versionId}")
  public NotifyTemplateVersionDetail versionDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId,
      @PathVariable UUID versionId) {
    return call(auth, "read", actor -> templates.versionDetail(templateId, versionId));
  }

  /**
   * 新增版本（= 编辑）。
   *
   * <p>🔴 **只追加**：老版本一个字节都不动。模板当前有生效版本时，新版本接管。
   */
  @PostMapping("/{templateId}/versions")
  public NotifyTemplateVersionDetail appendVersion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId,
      @Valid @RequestBody NotifyTemplateVersionInput input) {
    return call(auth, "update", actor -> templates.appendVersion(templateId, input, actor));
  }

  /** 启用某个版本（旧版本区间收口，**不改版本内容**）。 */
  @PostMapping("/{templateId}/versions/{versionId}/enable")
  public NotifyTemplateDetail enableVersion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId,
      @PathVariable UUID versionId) {
    return call(auth, "update", actor -> templates.enableVersion(templateId, versionId, actor));
  }

  /** 停用某个版本（只收口生效区间，**版本行不动**）。 */
  @PostMapping("/{templateId}/versions/{versionId}/disable")
  public NotifyTemplateDetail disableVersion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId,
      @PathVariable UUID versionId) {
    return call(auth, "update", actor -> templates.disableVersion(templateId, versionId, actor));
  }

  // ================================================================ 渲染与解析

  /**
   * 渲染预览（**不落库**）。
   *
   * <p>🔴 渲染失败（缺变量 / 多余变量 / 未知变量 / 语法错误 / 类型不符）⇒ **400**，
   * 响应体里是点名的问题清单；正文**不返回**（绝不返回空串，也绝不返回残留
   * {@code {{xxx}}} 的半成品）。
   */
  @PostMapping("/render-preview")
  public ResponseEntity<NotifyTemplateRenderResult> renderPreview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody NotifyTemplateRenderInput input) {
    var result = call(auth, "read", actor -> templates.renderPreview(input));
    return result.ok() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
  }

  /**
   * **按时刻**解析当时生效的那一版。
   *
   * <p>🔴 下游要引用的是这里的「版本 id + 内容快照」，不是「当前版本」——
   * 后来改模板篡改不了已经发出的通知。
   */
  @GetMapping("/resolve")
  public NotifyTemplateResolved resolve(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String templateKey,
      @RequestParam String scene,
      @RequestParam String language,
      @RequestParam(required = false) String at) {
    var moment = parseMoment(at);
    return call(
        auth, "read", actor -> templates.resolve(templateKey, scene, language, moment));
  }

  // ================================================================ 上游（3.4.2 文案资产）

  /**
   * 上游文案资产（catalog 服务 3.4.2 标题库 / 描述与卖点库）。
   *
   * <p>⚠️ 上游读不到 ⇒ **503**（消息里写明「是读不到，不是没有数据」），不返回空集合。
   */
  @GetMapping("/upstream/content-assets")
  public NotifyTemplateContentAssetView contentAssets(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> templates.contentAssets(auth));
  }

  // ================================================================ 内部

  /** 解析「那一刻」；格式不对要报 400 并说清怎么写，而不是抛 500。 */
  private static OffsetDateTime parseMoment(String raw) {
    if (raw == null || raw.isBlank()) return OffsetDateTime.now(java.time.ZoneOffset.UTC);
    try {
      return OffsetDateTime.parse(raw.trim());
    } catch (DateTimeParseException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "时刻要用 ISO-8601 带时区偏移的写法（如 2026-11-02T10:00:00+08:00）");
    }
  }
}
