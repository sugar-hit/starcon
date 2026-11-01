package com.starcon.platform;

import static com.starcon.platform.AiEffectEvaluationModels.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.util.UUID;
import java.util.function.Function;
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
 * 18.4.3.2「效果评估」的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /platform/ai/effects}</b>：与同批兄弟模块
 * （{@code /platform/ai/forecast} / {@code /platform/ai/usage} / {@code /platform/ai/templates}）
 * <b>第二 token 不同</b> ⇒ 前端的 api-proxy 白名单天然互斥。
 *
 * <p>租户<b>不</b>来自任何请求头：每个端点先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code platform.ai-effect.read / .record / .update}：
 * 「录一条采纳 / 一条有用性反馈」与「跑一次回测」是<b>独立的 record 码</b> ——
 * 它们会写事实台账（采纳记录是采纳率的**分子**），门槛要真的拦得住人；
 * 「维护阈值 / 生成建议 / 记录对建议的决策」是<b>独立的 update 码</b> ——
 * 采纳一条「建议下线」是把「这个功能要下线」落到纸面，它离「真的下线」只差一个开关。
 *
 * <p>🔴 <b>请求体里没有 tenantId / userId / membershipId</b>：归属一律由服务端从
 * <b>已认证会话</b>解析（Jackson 全局 fail-on-unknown-properties ⇒ 客户端自称的字段直接 400）。
 *
 * <p>🔴 <b>本模块没有 /disable 端点</b>：蓝图说「识别低价值功能并下线」，但真正下线涉及
 * 18.4.3.1 的功能开关 —— 本仓<b>没有</b>按能力粒度的 AI 功能开关 ⇒ 本模块只出
 * <b>低价值功能清单 + 建议</b>。放行一个不存在的下线动作比不做更糟。
 */
@RestController
@RequestMapping("/platform/ai/effects")
public class AiEffectEvaluationController {

  private final AccessService access;
  private final AiEffectEvaluationService effects;

  public AiEffectEvaluationController(AccessService access, AiEffectEvaluationService effects) {
    this.access = access;
    this.effects = effects;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "platform.ai-effect." + permission);
    return access.run(actor, "platform.ai-effect." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：哪些真接通、哪些还没交付、归谁。
   *
   * <p>⚠️ 「助手答案有用性反馈」这一维**如实答不可用**：18.4.1.1 对话助手已熔断未交付 ⇒
   * 上游不存在助手答案与助手使用数据 ⇒ 本模块只提供反馈登记的**载体**，
   * <b>绝不伪造助手的使用/反馈数据</b>。
   */
  @GetMapping("/capabilities")
  @Operation(
      summary = "能力声明",
      description =
          "逐维说清「有没有、依据是什么、缺了归谁」。"
              + "⚠️ **助手答案有用性反馈如实答不可用**（18.4.1.1 已熔断未交付）—— "
              + "本模块只登记反馈的载体，不伪造助手的使用/反馈数据。")
  public AiEffectCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> effects.capabilities());
  }

  // ================================================================ 效果总览

  /** 效果总览：采纳率 / 预测回测误差 / 有用性反馈，三块都带**分子分母与口径**。 */
  @GetMapping("/overview")
  @Operation(
      summary = "效果总览",
      description =
          "窗口 = [from, to)；三块指标各自带**分子、分母、比率与口径来源**。"
              + "🔴 **分母为 0 时比率是 null**（不是 0、不是 Infinity）。")
  public AiEffectOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false)
          @Parameter(description = "起始时刻（ISO-8601，含）") String from,
      @RequestParam(required = false)
          @Parameter(description = "结束时刻（ISO-8601，不含）") String to) {
    return call(auth, "read", actor -> effects.overview(from, to));
  }

  // ================================================================ 采纳记录

  /** 采纳记录台账（真分页 + 真筛选）。 */
  @GetMapping("/adoptions")
  @Operation(
      summary = "采纳记录台账",
      description =
          "一次人工处置一行：**直接采用 / 修改后采用 / 拒绝** 三态。"
              + "事实不可改，但填错了可以撤回（只允许 ACTIVE → WITHDRAWN）。")
  public AiAdoptionPage adoptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String capabilityCode,
      @RequestParam(required = false) String featureCode,
      @RequestParam(required = false) String decision,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "read",
        actor ->
            effects.adoptions(capabilityCode, featureCode, decision, from, to, page, size));
  }

  /** 录入一次人工处置（直接采用 / 修改后采用 / 拒绝）。 */
  @PostMapping("/adoptions")
  @Operation(
      summary = "录入一次人工处置",
      description =
          "主体成员由服务端从已认证会话解析（请求体不接受 membershipId）。"
              + "🔴 **「修改后采用」也算被采纳**（蓝图原文：直接采用 + 人工修改后采用）—— "
              + "只记一个布尔值会把「改了才用」误判成「没用」。")
  public AiAdoptionRow recordAdoption(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AiAdoptionInput input) {
    return call(auth, "record", actor -> effects.recordAdoption(input, actor));
  }

  /** 撤回一条采纳记录（事实不可改，但**填错了要能纠正**）。 */
  @PostMapping("/adoptions/{id}/withdraw")
  @Operation(
      summary = "撤回一条采纳记录",
      description =
          "受控状态机：ACTIVE → WITHDRAWN。撤回后该行不再计入采纳率的分子 —— "
              + "一条填错的采纳记录会让采纳率**永远偏**，而采纳率是本模块唯一的诚实指标。")
  public AiAdoptionRow withdrawAdoption(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody(required = false) AiWithdrawInput input) {
    return call(auth, "record", actor -> effects.withdrawAdoption(id, input, actor));
  }

  // ================================================================ 采纳率报表

  /** 按功能的采纳率报表（真分页 + 真筛选）。 */
  @GetMapping("/adoption-report")
  @Operation(
      summary = "采纳率报表",
      description =
          "粒度 = 能力 × 子功能。**分子**：本模块的采纳条数（直接采用 + 修改后采用，剔除已撤回）；"
              + "**分母**：18.4.3.1 用量台账里的 AI 调用次数（本模块只读）。"
              + "🔴 分母为 0 ⇒ 比率是 **null**；样本不足（调用次数 < 门槛）⇒ 判定「数据不足」，"
              + "**不下低价值结论**。")
  public AiAdoptionReportPage adoptionReport(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String capabilityCode,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> effects.adoptionReport(capabilityCode, from, to, page, size));
  }

  // ================================================================ 有用性反馈

  /** 有用性反馈台账（真分页 + 真筛选）。 */
  @GetMapping("/feedback")
  @Operation(
      summary = "有用性反馈台账",
      description =
          "一次「这个答案有用吗」的反馈一行：**有用 / 部分有用 / 无用** 三档 + 受控理由码。")
  public AiFeedbackPage feedbacks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String capabilityCode,
      @RequestParam(required = false) String featureCode,
      @RequestParam(required = false) String usefulness,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "read",
        actor -> effects.feedbacks(capabilityCode, featureCode, usefulness, from, to, page, size));
  }

  /**
   * 登记一次有用性反馈。
   *
   * <p>⚠️ 助手答案这一维上游未交付（18.4.1.1 已熔断）：本端点**照样接受**登记
   * （它是反馈的**载体**），但聚合时助手维度会如实显示「暂无反馈」。
   */
  @PostMapping("/feedback")
  @Operation(
      summary = "登记一次有用性反馈",
      description =
          "主体成员由服务端从已认证会话解析（请求体不接受 membershipId）。"
              + "理由码是**受控词表**（可聚合），补充说明是自由文本（**不参与统计口径**）。"
              + "⚠️ 助手答案维度上游未交付 ⇒ 聚合里该维度恒为「暂无反馈」，本模块不伪造助手数据。")
  public AiFeedbackRow recordFeedback(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AiFeedbackInput input) {
    return call(auth, "record", actor -> effects.recordFeedback(input, actor));
  }

  /** 撤回一条有用性反馈。 */
  @PostMapping("/feedback/{id}/withdraw")
  @Operation(
      summary = "撤回一条有用性反馈",
      description = "受控状态机：ACTIVE → WITHDRAWN。撤回后该行不再计入有用性聚合。")
  public AiFeedbackRow withdrawFeedback(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody(required = false) AiWithdrawInput input) {
    return call(auth, "record", actor -> effects.withdrawFeedback(id, input, actor));
  }

  // ================================================================ 预测准确度回测

  /** 回测结果台账（真分页 + 真筛选）。 */
  @GetMapping("/backtests")
  @Operation(
      summary = "回测结果台账",
      description =
          "一条预测结果一行：预测值 + 置信区间、**实际值**（真实销量求和）、"
              + "绝对误差、相对误差、是否落在区间内。")
  public AiBacktestPage backtests(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String runId,
      @RequestParam(required = false) String skuCode,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> effects.backtests(runId, skuCode, status, page, size));
  }

  /**
   * 跑一次预测准确度回测。
   *
   * <p>🔴 <b>实际值来自真实销量</b>（18.4.2.2 的销量历史台账），
   * <b>绝不拿预测值自己当实际值</b>；一条销量都没有的 SKU 如实记
   * {@code NO_ACTUAL_SOURCE}（<b>不记 0</b>）。
   */
  @PostMapping("/backtests/run")
  @Operation(
      summary = "跑一次预测准确度回测",
      description =
          "runId 留空 = 取最近一次已完成的预测运行。"
              + "回测是**计算快照**（真实销量是后补的）⇒ 重跑会刷新同一行。"
              + "🔴 **只读上游**：18.4.2.2 的预测结果表与销量历史表一个字都不写。")
  public AiBacktestRunResult runBacktest(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody(required = false) AiBacktestRunInput input) {
    return call(auth, "record", actor -> effects.runBacktest(input));
  }

  // ================================================================ 评估阈值

  /** 当前生效的评估阈值（**租户级一行**）。 */
  @GetMapping("/thresholds")
  @Operation(
      summary = "评估阈值",
      description =
          "「什么算低价值」是**业务口径** ⇒ 归租户维护（一个租户只有一行）。"
              + "⚠️ 库里没登记时返回**系统默认阈值**并标 `configured=false` —— 不谎称是租户定的。")
  public AiEffectThresholdRow thresholds(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> effects.thresholds());
  }

  /** 登记 / 修改评估阈值。 */
  @PutMapping("/thresholds")
  @Operation(
      summary = "登记 / 修改评估阈值",
      description =
          "留空的字段沿用当前值。判定「样本不足」的门槛很重要 —— "
              + "**不拿 3 次调用判死刑**。")
  public AiEffectThresholdRow saveThreshold(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AiEffectThresholdInput input) {
    return call(auth, "update", actor -> effects.saveThreshold(input));
  }

  // ================================================================ 改进建议

  /** 改进建议清单（真分页 + 真筛选）。 */
  @GetMapping("/suggestions")
  @Operation(
      summary = "改进建议清单",
      description =
          "每条建议都带齐四件事：针对哪个功能、当前指标是多少、建议做什么、依据是什么。"
              + "🔴 **本模块只出建议，不执行下线** —— 真正下线需人工执行。")
  public AiSuggestionPage suggestions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String suggestionType,
      @RequestParam(required = false) String capabilityCode,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "read",
        actor -> effects.suggestions(status, suggestionType, capabilityCode, page, size));
  }

  /** 生成改进建议（重新生成 = 刷新同一条，不堆重复行）。 */
  @PostMapping("/suggestions/generate")
  @Operation(
      summary = "生成改进建议",
      description =
          "按采纳率报表与阈值生成建议：低价值 ⇒ 建议下线；样本不足 ⇒ 数据缺口"
              + "（**不是**低价值）。条件不再成立的旧建议会被自动关闭。"
              + "🔴 **只出建议清单**，不执行任何下线动作。")
  public AiSuggestionGenerateResult generateSuggestions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody(required = false) AiSuggestionGenerateInput input) {
    return call(auth, "update", actor -> effects.generateSuggestions(input));
  }

  /**
   * 记录对一条建议的人工决策（**只记录决定，不执行下线**）。
   *
   * <p>🔴 采纳一条「建议下线」是把「这个功能要下线」落到纸面 ——
   * 它离「真的下线」只差一个开关。
   */
  @PostMapping("/suggestions/{id}/decision")
  @Operation(
      summary = "记录对一条建议的决策",
      description =
          "只记录决定（采纳 / 驳回 + 说明），**不执行下线** —— "
              + "本仓没有按能力粒度的 AI 功能开关，下线动作需人工执行。")
  public AiSuggestionRow decideSuggestion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam String decision,
      @RequestBody(required = false) AiSuggestionDecisionInput input) {
    var status = SuggestionStatus.parse(decision);
    check(status != null, "决策只能是 ACCEPTED（采纳建议）/ REJECTED（驳回建议）");
    return call(auth, "update", actor -> effects.decideSuggestion(id, status, input, actor));
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, message);
    }
  }
}
