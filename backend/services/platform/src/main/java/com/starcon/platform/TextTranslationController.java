package com.starcon.platform;

import static com.starcon.platform.TextTranslationModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 18.4.2.1「文本与翻译」的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /platform/text}</b>：与 18.1 的 {@code /platform/apps} 同服务、不同前缀，
 * 两者的东西完全不是一回事（一个是「谁可以接入我们」，一个是「文案怎么翻」）。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>权限码 {@code platform.text.read / .create / .update / .confirm / .publish / .reject}</b>：
 * 「人工确认」「发布」「驳回」是三个**独立**的码 —— 蓝图加粗句「翻译结果必须人工确认后发布」
 * 说的是一道门槛，能改文案的人不该顺手把「已确认」也点了。
 */
@RestController
@RequestMapping("/platform/text")
public class TextTranslationController {

  private final AccessService access;
  private final TextTranslationService texts;

  public TextTranslationController(AccessService access, TextTranslationService texts) {
    this.access = access;
    this.texts = texts;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "platform.text." + permission);
    return access.run(actor, "platform.text." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明与概览

  /** 能力声明：哪些真接通、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public TextCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> texts.capabilities());
  }

  /** 概览：几个数。 */
  @GetMapping("/overview")
  public TextOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> texts.overview());
  }

  // ================================================================ 术语表

  /** 术语表列表（真筛选）。 */
  @GetMapping("/glossary")
  public List<TextGlossaryRow> glossary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String termType,
      @RequestParam(required = false) String locale,
      @RequestParam(required = false) Boolean enabled) {
    return call(auth, "read", actor -> texts.glossary(termType, locale, enabled));
  }

  /** 新增或修改一条术语。 */
  @PostMapping("/glossary")
  public TextGlossaryRow saveGlossary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody TextGlossaryInput input) {
    return call(auth, "update", actor -> texts.saveGlossary(input, actor));
  }

  /** 删除一条术语。 */
  @DeleteMapping("/glossary/{id}")
  public void deleteGlossary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(auth, "update", actor -> {
      texts.deleteGlossary(id, actor);
      return null;
    });
  }

  // ================================================================ 违禁词

  /** 违禁词列表（真筛选）。 */
  @GetMapping("/banned-terms")
  public List<TextBannedTermRow> bannedTerms(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String locale,
      @RequestParam(required = false) Boolean enabled) {
    return call(auth, "read", actor -> texts.bannedTerms(category, severity, locale, enabled));
  }

  /** 新增或修改一条违禁词。 */
  @PostMapping("/banned-terms")
  public TextBannedTermRow saveBannedTerm(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody TextBannedTermInput input) {
    return call(auth, "update", actor -> texts.saveBannedTerm(input, actor));
  }

  /** 删除一条违禁词。 */
  @DeleteMapping("/banned-terms/{id}")
  public void deleteBannedTerm(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(auth, "update", actor -> {
      texts.deleteBannedTerm(id, actor);
      return null;
    });
  }

  // ================================================================ 长度与字符集规则

  /** 规则列表。 */
  @GetMapping("/rules")
  public List<TextRuleRow> rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String contentType,
      @RequestParam(required = false) String locale) {
    return call(auth, "read", actor -> texts.rules(contentType, locale));
  }

  /** 新增或修改一条规则。 */
  @PostMapping("/rules")
  public TextRuleRow saveRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody TextRuleInput input) {
    return call(auth, "update", actor -> texts.saveRule(input, actor));
  }

  /** 删除一条规则。 */
  @DeleteMapping("/rules/{id}")
  public void deleteRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(auth, "update", actor -> {
      texts.deleteRule(id, actor);
      return null;
    });
  }

  // ================================================================ 客服回复模板

  /** 模板列表。 */
  @GetMapping("/templates")
  public List<TextTemplateRow> templates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String contentType,
      @RequestParam(required = false) String locale,
      @RequestParam(required = false) Boolean enabled) {
    return call(auth, "read", actor -> texts.templates(contentType, locale, enabled));
  }

  /**
   * 变量绑定：把模板渲染成一封**可发出去**的回复。
   *
   * <p>变量取值走查询串（键是封闭词表里的变量名）；没给值的变量会进 {@code missing}，
   * 而不是悄悄留空。
   */
  @GetMapping("/templates/{templateId}/binding")
  public TextTemplateBinding bindTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId,
      @RequestParam Map<String, String> values) {
    return call(auth, "read", actor -> texts.bindTemplate(templateId, values));
  }

  /** 新增或修改一个模板。 */
  @PostMapping("/templates")
  public TextTemplateRow saveTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody TextTemplateInput input) {
    return call(auth, "update", actor -> texts.saveTemplate(input, actor));
  }

  /** 删除一个模板。 */
  @DeleteMapping("/templates/{id}")
  public void deleteTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(auth, "update", actor -> {
      texts.deleteTemplate(id, actor);
      return null;
    });
  }

  // ================================================================ 任务

  /** 任务列表（真分页 + 真筛选）。 */
  @GetMapping("/tasks")
  public TextTaskPage tasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String contentType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String targetLocale,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "read",
        actor -> texts.tasks(keyword, contentType, status, targetLocale, page, size));
  }

  /** 建一个翻译任务。 */
  @PostMapping("/tasks")
  public TextTaskRow createTask(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody TextTaskInput input) {
    return call(auth, "create", actor -> texts.createTask(input, actor));
  }

  /** 任务详情（任务 + 当前草稿 + 历史版本 + 检查结论 + 规避建议 + 置信度）。 */
  @GetMapping("/tasks/{taskId}")
  public TextTaskDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID taskId) {
    return call(auth, "read", actor -> texts.detail(taskId));
  }

  /**
   * 走 AI 通道生成 / 翻译。
   *
   * <p>🔴 **本仓当前 AI 供应商未接入** ⇒ 这里返回 <b>409</b> 且<b>一个字都不写</b>。
   * 适配器交付之后，这个端点立刻变成真的。
   */
  @PostMapping("/tasks/{taskId}/generate")
  public TextGenerationOutcome generate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID taskId) {
    return call(auth, "create", actor -> texts.generate(taskId, actor, auth));
  }

  /**
   * 人工录入 / 编辑译文（**可人工编辑**）。
   *
   * <p>每次保存追加一个新版本，并重跑确定性检查、重算置信度。已确认的译文被改动后确认作废。
   */
  @PostMapping("/tasks/{taskId}/drafts")
  public TextTaskDetail saveDraft(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID taskId,
      @Valid @RequestBody TextDraftInput input) {
    return call(auth, "update", actor -> texts.saveDraft(taskId, input, actor));
  }

  /** 人工确认（**发布的硬门槛**）。 */
  @PostMapping("/tasks/{taskId}/confirm")
  public TextTaskDetail confirm(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID taskId) {
    return call(auth, "confirm", actor -> texts.confirm(taskId, actor));
  }

  /** 发布（必须已人工确认，且不允许带 FAIL 的检查结论）。 */
  @PostMapping("/tasks/{taskId}/publish")
  public TextTaskDetail publish(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID taskId) {
    return call(auth, "publish", actor -> texts.publish(taskId, actor));
  }

  /** 驳回（终态）。 */
  @PostMapping("/tasks/{taskId}/reject")
  public TextTaskDetail reject(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID taskId,
      @RequestParam(required = false) String reason) {
    return call(auth, "reject", actor -> texts.reject(taskId, reason, actor));
  }

  /**
   * 已发布的文案（**下游可取的那一份**）。
   *
   * <p>🔴 只返回 {@code status='PUBLISHED'} 的行 —— 未确认的译文取不走。
   */
  @GetMapping("/published")
  public List<TextTaskRow> published(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String contentType,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> texts.published(contentType, page, size));
  }

  // ================================================================ 上游（跨服务只读）

  /** 可选目标语言（来自 3.4.3 多语言；取不到一律 503）。 */
  @GetMapping("/locales")
  public TextLocaleView locales(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> texts.locales(auth));
  }

  /** 可拿来翻译的上游源文本（来自 3.4.3 多语言 / 4.2.1.1 编辑器）。 */
  @GetMapping("/source-candidates")
  public TextSourceCandidateView sourceCandidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String refType,
      @RequestParam UUID refId) {
    return call(auth, "read", actor -> texts.sourceCandidates(refType, refId, auth));
  }
}
