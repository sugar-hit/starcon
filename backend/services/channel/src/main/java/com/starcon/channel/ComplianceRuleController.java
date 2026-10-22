package com.starcon.channel;

import static com.starcon.channel.ComplianceRuleModels.*;

import java.util.List;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 16.2.1.1 规则库。
 *
 * <p>蓝图：「管理合规规则库：规则类型（违禁词、极限词、侵权词、认证要求、禁限售、标签要求、材质要求）、
 * 严重度（阻断/警告/提示）、适用平台与站点、生效时间与版本。输入：规则定义与词库上传；版本更新；适用范围配置。
 * 输出：规则集 + 生效版本。关系：上游 = 平台政策同步 + 人工维护；下游 = 16.2.1.2 执行、4.2.3.2 预检。
 * <b>规则版本化是关键</b>：政策变化时历史商品的判定结果要可解释，不能因为规则库更新就说过去的商品当时违规。」
 *
 * <p>🔴 端点清单：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明：值域、版本化口径、未交付下游逐条列出。
 *   <li>{@code GET /shops} —— 店铺范围清单（**真分页**，来自本服务自己的店铺目录）。
 *   <li>{@code GET /rules} —— 规则集（**真分页 + 真筛选**）。
 *   <li>{@code POST /rules} —— 建规则（**只建身份**；按规则编码**幂等**）。
 *   <li>{@code GET /rules/{id}} —— 规则详情（含全部版本）。
 *   <li>{@code PUT /rules/{id}} —— 改规则的<b>生命周期</b>（停用 / 重新启用）。
 *   <li>{@code GET /rules/{id}/versions} —— 版本清单（**真分页**）。
 *   <li>{@code POST /rules/{id}/versions} —— 🔴 <b>新增一个版本</b>（规则改动的唯一入口）。
 *   <li>{@code GET /rules/{id}/versions/effective} —— 🔴 <b>某一时刻生效的是哪一版</b>（可解释性）。
 *   <li>{@code GET /rules/{id}/versions/{versionId}} —— 版本详情（词条 + 适用范围）。
 *   <li>{@code GET /rules/{id}/versions/{versionId}/terms} —— 词条清单（**真分页**）。
 *   <li>{@code POST /rules/{id}/versions/{versionId}/terms} —— 追加词条（**批量**）。
 *   <li>{@code POST /rules/{id}/versions/{versionId}/terms/upload} —— 🔴 <b>词库上传</b>（整份词库一次落库）。
 *   <li>{@code GET /rules/{id}/versions/{versionId}/scopes} —— 适用范围清单（**真分页**）。
 *   <li>{@code POST /rules/{id}/versions/{versionId}/scopes} —— 追加适用范围（**批量**）。
 *   <li>{@code GET /effective} —— 🔴 <b>生效规则集</b>（规则集 + 生效版本）。
 * </ul>
 *
 * <p>🔴 <b>刻意没有的形状</b>：没有「改词」「改严重度」的端点（改规则 = 新增一个版本）、
 * 没有删除端点（版本 / 词条 / 适用范围都不做物理删除）、没有拦截 / 放行端点
 * —— 16.2.1.2 拦截执行<b>未交付</b>，放行一个不存在的入口比 404 更糟。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（读 / 录 / 改），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/compliance-rules")
public class ComplianceRuleController {

  private final AccessService access;
  private final ComplianceRuleService service;

  public ComplianceRuleController(AccessService access, ComplianceRuleService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /** 能力声明：值域、版本化口径，以及**未交付的下游逐条列出**（available=false + ownerModule + 影响）。 */
  @GetMapping("/capabilities")
  public ComplianceRuleCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 店铺范围 ----

  /** 店铺范围清单（真分页 + 真统计；数据来自本服务自己的店铺目录）。 */
  @GetMapping("/shops")
  public ComplianceRuleShopPage shops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.shops(platformCode, keyword, page, size));
  }

  // ---- 规则集 ----

  /** 规则集（真分页 + 真筛选）。 */
  @GetMapping("/rules")
  public ComplianceRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String ruleType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.rules(ruleType, status, platformCode, keyword, page, size));
  }

  /** 建规则（只建身份；按规则编码幂等）。 */
  @PostMapping("/rules")
  public ComplianceRuleCreation createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ComplianceRuleCreateInput input) {
    return call(auth, "channel.operation.create", a -> service.createRule(input));
  }

  /** 规则详情（含全部版本）。 */
  @GetMapping("/rules/{id}")
  public ComplianceRuleDetail rule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.rule(id));
  }

  /** 改规则的生命周期（停用 / 重新启用）；标题与说明是版本属性，改它们 = 新增版本。 */
  @PutMapping("/rules/{id}")
  public ComplianceRule updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody ComplianceRuleUpdateInput input) {
    return call(auth, "channel.operation.update", a -> service.updateRule(id, input));
  }

  // ---- 版本 ----

  /** 版本清单（真分页）。 */
  @GetMapping("/rules/{id}/versions")
  public ComplianceRuleVersionPage versions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.versions(id, page, size));
  }

  /** 🔴 新增一个版本 —— 规则改动的唯一入口（改词 / 改严重度都走这里）。 */
  @PostMapping("/rules/{id}/versions")
  public ComplianceRuleVersionDetail createVersion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody ComplianceRuleVersionInput input) {
    return call(auth, "channel.operation.create", a -> service.createVersion(id, input));
  }

  /** 🔴 某一时刻生效的是哪一版（连同它当时的词条与适用范围）。 */
  @GetMapping("/rules/{id}/versions/effective")
  public ComplianceRuleEffectiveVersion effectiveVersion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false) String at) {
    return call(auth, "channel.operation.read", a -> service.effectiveVersion(id, at));
  }

  /** 版本详情（词条 + 适用范围）。 */
  @GetMapping("/rules/{id}/versions/{versionId}")
  public ComplianceRuleVersionDetail version(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @PathVariable String versionId) {
    return call(auth, "channel.operation.read", a -> service.versionDetail(id, versionId));
  }

  // ---- 词条 ----

  /** 词条清单（真分页）。 */
  @GetMapping("/rules/{id}/versions/{versionId}/terms")
  public ComplianceRuleTermPage terms(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @PathVariable String versionId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.terms(id, versionId, page, size));
  }

  /** 追加词条（批量；一条规则多个词条）。 */
  @PostMapping("/rules/{id}/versions/{versionId}/terms")
  public ComplianceRuleTermBatchResult addTerms(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @PathVariable String versionId,
      @RequestBody ComplianceRuleTermsInput input) {
    return call(auth, "channel.operation.create", a -> service.addTerms(id, versionId, input));
  }

  /** 🔴 词库上传（整份词库一次落库，每行一个词）。 */
  @PostMapping("/rules/{id}/versions/{versionId}/terms/upload")
  public ComplianceRuleTermBatchResult uploadTerms(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @PathVariable String versionId,
      @RequestBody ComplianceRuleTermsUploadInput input) {
    return call(auth, "channel.operation.create", a -> service.uploadTerms(id, versionId, input));
  }

  // ---- 适用范围 ----

  /** 适用范围清单（真分页）。 */
  @GetMapping("/rules/{id}/versions/{versionId}/scopes")
  public ComplianceRuleScopePage scopes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @PathVariable String versionId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.scopes(id, versionId, page, size));
  }

  /** 追加适用范围（批量；适用平台 + 站点，可再收窄到具体店铺）。 */
  @PostMapping("/rules/{id}/versions/{versionId}/scopes")
  public ComplianceRuleScopeBatchResult addScopes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @PathVariable String versionId,
      @RequestBody ComplianceRuleScopesInput input) {
    return call(auth, "channel.operation.create", a -> service.addScopes(id, versionId, input));
  }

  // ---- 生效规则集 ----

  /** 🔴 生效规则集（规则集 + 生效版本；解析时刻可指定）。 */
  @GetMapping("/effective")
  public ComplianceRuleEffectiveSet effective(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String at,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String shopId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.effective(at, platformCode, shopId, page, size));
  }
}
