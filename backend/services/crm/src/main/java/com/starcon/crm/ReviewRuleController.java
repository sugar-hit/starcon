package com.starcon.crm;

import static com.starcon.crm.ReviewRuleModels.*;

import com.starcon.crm.ReviewRuleModels.CrmReviewAssignmentInput;
import com.starcon.crm.ReviewRuleModels.CrmReviewAssignmentPage;
import com.starcon.crm.ReviewRuleModels.CrmReviewAssignmentRow;
import com.starcon.crm.ReviewRuleModels.CrmReviewEffectComparison;
import com.starcon.crm.ReviewRuleModels.CrmReviewHolidayInput;
import com.starcon.crm.ReviewRuleModels.CrmReviewHolidayPage;
import com.starcon.crm.ReviewRuleModels.CrmReviewHolidayRow;
import com.starcon.crm.ReviewRuleModels.CrmReviewRuleCapabilities;
import com.starcon.crm.ReviewRuleModels.CrmReviewRuleConfigInput;
import com.starcon.crm.ReviewRuleModels.CrmReviewRulePage;
import com.starcon.crm.ReviewRuleModels.CrmReviewRuleRow;
import com.starcon.crm.ReviewRuleModels.CrmReviewRuleShopView;
import com.starcon.crm.ReviewRuleModels.CrmReviewTemplateInput;
import com.starcon.crm.ReviewRuleModels.CrmReviewTemplatePage;
import com.starcon.crm.ReviewRuleModels.CrmReviewTemplateRow;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 15.2.2.2 索评规则。
 *
 * <p>命名空间 {@code /crm/review-rules}：前端入口同名（页面 {@code /crm/review-rules}），
 * 浏览器只经 {@code /api/crm} 代理访问；后端路径按<b>服务</b>命名空间走，
 * 与 catalog（/catalog/...）、trade（/trade/...）同一纪律。
 *
 * <p>⚠️ 与同 L3 锚点下的兄弟模块 {@code /crm/review-requests}（15.2.2.1 索评）
 * **刻意不共用命名空间**：
 * 那是「这一位买家、这一张已签收的单子，要不要向他发起一次索评」，
 * 本模块是「策略怎么配、什么时候问、问哪个话术臂」。两者一个管执行、一个管配置，
 * **不共用任何一张表**（V220 / V228 各自建表，零 ALTER 既有表）。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/crm/review-rules")
public class ReviewRuleController {

  private final AccessService access;
  private final ReviewRuleService rules;

  public ReviewRuleController(AccessService access, ReviewRuleService rules) {
    this.access = access;
    this.rules = rules;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "crm.rule." + permission);
    return access.run(actor, "crm.rule." + permission, () -> work.apply(actor));
  }

  /** 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。 */
  @GetMapping("/capabilities")
  public CrmReviewRuleCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> rules.capabilities());
  }

  /** 店铺目录（来自上游 channel 的真实清单，不是前端写死的常量）。 */
  @GetMapping("/shops")
  public List<CrmReviewRuleShopView> shops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> rules.shops(auth));
  }

  // ================================================================ 规则配置

  /** 索评规则列表（真分页 + 真筛选）。 */
  @GetMapping
  public CrmReviewRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String logisticsMode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        a -> rules.rulePage(shopId, logisticsMode, status, keyword, page, size));
  }

  /** 单条索评规则。 */
  @GetMapping("/{ruleId}")
  public CrmReviewRuleRow rule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId) {
    return call(auth, "read", a -> rules.rule(ruleId));
  }

  /** 新建索评规则。 */
  @PostMapping
  public CrmReviewRuleRow createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmReviewRuleConfigInput input) {
    return call(auth, "update", a -> rules.createRule(input, a.userId(), auth));
  }

  /** 更新索评规则。 */
  @PutMapping("/{ruleId}")
  public CrmReviewRuleRow updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @Valid @RequestBody CrmReviewRuleConfigInput input) {
    return call(auth, "update", a -> rules.updateRule(ruleId, input, a.userId(), auth));
  }

  /**
   * 删除索评规则。
   *
   * <p>已被生效策略落地引用过的规则<b>删不掉</b>（DB 触发器会拒）——
   * 删掉之后效果对比里那一段历史会凭空消失，结论被改写而不是少了几条。
   */
  @DeleteMapping("/{ruleId}")
  public void deleteRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId) {
    call(
        auth,
        "update",
        a -> {
          rules.deleteRule(ruleId);
          return null;
        });
  }

  // ================================================================ 话术模板

  /** 话术模板列表（含变体，真分页 + 真筛选）。 */
  @GetMapping("/templates")
  public CrmReviewTemplatePage templates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String locale,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> rules.templatePage(keyword, locale, page, size));
  }

  /** 单个话术模板。 */
  @GetMapping("/templates/{templateId}")
  public CrmReviewTemplateRow template(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId) {
    return call(auth, "read", a -> rules.template(templateId));
  }

  /** 新建话术模板（含变体）。 */
  @PostMapping("/templates")
  public CrmReviewTemplateRow createTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmReviewTemplateInput input) {
    return call(auth, "update", a -> rules.createTemplate(input, a.userId()));
  }

  /** 更新话术模板（变体按变体码对齐：多的删、少的增、同的改）。 */
  @PutMapping("/templates/{templateId}")
  public CrmReviewTemplateRow updateTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId,
      @Valid @RequestBody CrmReviewTemplateInput input) {
    return call(auth, "update", a -> rules.updateTemplate(templateId, input, a.userId()));
  }

  /** 删除话术模板（下面还挂着变体或被规则引用的删不掉）。 */
  @DeleteMapping("/templates/{templateId}")
  public void deleteTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId) {
    call(
        auth,
        "update",
        a -> {
          rules.deleteTemplate(templateId);
          return null;
        });
  }

  // ================================================================ 节假日

  /** 节假日列表（真分页 + 真筛选）。 */
  @GetMapping("/holidays")
  public CrmReviewHolidayPage holidays(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String year,
      @RequestParam(required = false) String region,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> rules.holidayPage(year, region, kind, page, size));
  }

  /** 新建节假日。 */
  @PostMapping("/holidays")
  public CrmReviewHolidayRow createHoliday(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmReviewHolidayInput input) {
    return call(auth, "update", a -> rules.createHoliday(input, a.userId()));
  }

  /** 更新节假日。 */
  @PutMapping("/holidays/{holidayId}")
  public CrmReviewHolidayRow updateHoliday(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID holidayId,
      @Valid @RequestBody CrmReviewHolidayInput input) {
    return call(auth, "update", a -> rules.updateHoliday(holidayId, input, a.userId()));
  }

  /** 删除节假日。 */
  @DeleteMapping("/holidays/{holidayId}")
  public void deleteHoliday(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID holidayId) {
    call(
        auth,
        "update",
        a -> {
          rules.deleteHoliday(holidayId);
          return null;
        });
  }

  // ================================================================ 生效策略与效果对比

  /**
   * 生效策略（蓝图输出句的第一项）。
   *
   * <p>规则由服务端**解析**得出，运营不指定规则 —— 指定规则就绕过了「生效策略」这件事本身。
   *
   * @param at 按哪个**签收时刻**判定（留空 = 现在）
   * @param seedKey A/B 分桶种子（留空 = 只给权重分布，不给具体变体）
   */
  @GetMapping("/effective")
  public CrmReviewEffectivePolicy effective(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String logisticsMode,
      @RequestParam(required = false) String at,
      @RequestParam(required = false) String seedKey) {
    return call(auth, "read", a -> rules.effective(shopId, logisticsMode, at, seedKey));
  }

  /**
   * 可被应用策略的索评请求（上游 15.2.2.1 的索评任务，**同库只读**）。
   *
   * <p>它们的状态原样带出来 —— 被频次规则拒绝的请求也列出来并标明状态，
   * 因为「为什么这条不能分配话术」是运营需要看到的答案，
   * 而不是在列表里悄悄消失一条。
   */
  @GetMapping("/candidates")
  public CrmReviewRuleCandidatePage candidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> rules.candidates(shopId, status, page, size));
  }

  /**
   * 把当前生效策略应用到一条索评请求上（15.2.2.1 的索评任务）。
   *
   * <p>这是效果对比唯一的归属事实来源：没有它，回收率与平均星级就无从按 A / B 拆开。
   */
  @PostMapping("/assignments")
  public CrmReviewAssignmentRow applyPolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmReviewAssignmentInput input) {
    return call(auth, "update", a -> rules.applyPolicy(input, a.userId(), auth));
  }

  /** 生效策略落地记录列表（真分页 + 真筛选）。 */
  @GetMapping("/assignments")
  public CrmReviewAssignmentPage assignments(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) UUID ruleId,
      @RequestParam(required = false) UUID variantId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "read", a -> rules.assignmentPage(shopId, ruleId, variantId, page, size));
  }

  /** 单条落地记录。 */
  @GetMapping("/assignments/{assignmentId}")
  public CrmReviewAssignmentRow assignment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID assignmentId) {
    return call(auth, "read", a -> rules.assignment(assignmentId));
  }

  /** 效果对比（蓝图输出句的第二项）。 */
  @GetMapping("/effect-comparison")
  public CrmReviewEffectComparison effectComparison(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) Integer days,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) UUID ruleId) {
    return call(auth, "read", a -> rules.effectComparison(days, shopId, ruleId));
  }
}
