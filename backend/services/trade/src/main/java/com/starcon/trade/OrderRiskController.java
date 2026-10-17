package com.starcon.trade;

import static com.starcon.trade.OrderRiskModels.*;

import jakarta.validation.Valid;
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
 * 9.2.2.2 风控校验。
 *
 * <p>命名空间 {@code /trade/orders/risk-validation}：与 9.2.1.x 的审单、
 * 9.3.x 的履约编排分属不同命名空间 —— 「算出风险分」与「决定发不发」是两件事，
 * 权限与日志都该分得开。
 *
 * <p>权限码用 {@code trade.orders.read} / {@code trade.orders.update}：登记风险信号、
 * 触发评估、落人工复核结论都直接改变一单的发货走向，不是读权限能带过去的动作。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>刻意不提供的能力</b>：没有任何端点能只凭分数改写「最终动作」。
 * 改 {@code final_action} 的唯一入口是 {@code POST /assessments/{id}/review}，
 * 它要求一条已认证的人工成员身份 —— 蓝图加粗句「风控分数不能单独作为拒绝依据，
 * 需人工复核，避免误伤正常复购客户」在接口层就已经成立。
 */
@RestController
@RequestMapping("/trade/orders/risk-validation")
public class OrderRiskController {

  private final AccessService access;
  private final OrderRiskService risk;

  public OrderRiskController(AccessService access, OrderRiskService risk) {
    this.access = access;
    this.risk = risk;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  // ============================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：蓝图的上游是 15 客户与 16 合规，而这两卷在集成头
   * 分别是「24 个 L4 模块只交付了 1 个」与「0 个交付」，且分属别的服务的独立库。
   * **哪几维真接通、哪几维靠租户登记，必须由接口自己说**，
   * 而不是让界面从空数组或 404 里猜哪一环没通。
   */
  @GetMapping("/capabilities")
  public RiskCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "orders.read", a -> risk.capabilities());
  }

  /** 全量概览（不受筛选与分页影响）。 */
  @GetMapping("/overview")
  public Summary overview(@RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "orders.read", a -> risk.overview());
  }

  // ============================================================ 风控规则

  /** 风控规则读取。没登记过就是 404 —— 不是一份带默认值的规则。 */
  @GetMapping("/rule")
  public RiskRule rule(@RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "orders.read", a -> risk.rule());
  }

  /** 登记 / 更新风控规则（upsert，一租户一条）。五维权重合计必须恰好 1.0000。 */
  @PutMapping("/rule")
  public RiskRule saveRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody RiskRuleInput input) {
    return call(auth, "orders.update", a -> risk.saveRule(input, a.membershipId()));
  }

  // ============================================================ 风险信号

  /** 信号列表（真分页 + 真搜索）。 */
  @GetMapping("/signals")
  public RiskSignalPage signals(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String signalCode,
      @RequestParam(required = false) SignalSource signalSource,
      @RequestParam(required = false) String subjectKey,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "orders.read",
        a ->
            risk.pageSignals(
                new SignalQuery(keyword, signalCode, signalSource, subjectKey, page, size)));
  }

  /**
   * 登记一条风险信号。
   *
   * <p>这是 15 客户 / 16 合规未交付期间的**唯一数据入口**：它不是占位，
   * 是真实参与打分的业务数据。换上游接口到位后由同步器按同一形状写入，
   * 只需把 signalSource 改成 MODULE_*，表结构与打分逻辑都不动。
   */
  @PostMapping("/signals")
  public RiskSignal saveSignal(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody RiskSignalInput input) {
    return call(auth, "orders.update", a -> risk.saveSignal(input));
  }

  // ============================================================ 评估

  /** 评估列表（真分页 + 真搜索），带不受分页影响的汇总。 */
  @GetMapping("/assessments")
  public RiskAssessmentPage assessments(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String recommendedAction,
      @RequestParam(required = false) String evaluationStatus,
      @RequestParam(required = false, defaultValue = "false") Boolean pendingReviewOnly,
      @RequestParam(required = false) String buyerFingerprint,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "orders.read",
        a ->
            risk.pageAssessments(
                new AssessmentQuery(
                    keyword,
                    recommendedAction,
                    evaluationStatus,
                    pendingReviewOnly,
                    buyerFingerprint,
                    page,
                    size)));
  }

  /**
   * 对一张订单跑一次风控评估。
   *
   * <p>评估基准时刻取**订单的提交时刻**而不是当前时间：同一张单重复评估结果恒定。
   */
  @PostMapping("/assessments")
  public AssessmentSaved assess(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AssessmentInput input) {
    return call(auth, "orders.update", a -> risk.assess(input, a.membershipId()));
  }

  /** 评估详情：头 + 五行组件 + 人工复核留痕。跨租户在 RLS 下查不到 → 404。 */
  @GetMapping("/assessments/{assessmentId}")
  public RiskDetail assessment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID assessmentId) {
    return call(auth, "orders.read", a -> risk.assessment(assessmentId));
  }

  // ============================================================ 人工复核

  /**
   * 提交人工复核 —— **本模块唯一能产生「拒发」的入口**。
   *
   * <p>蓝图加粗句「风控分数不能单独作为拒绝依据，需人工复核，避免误伤正常复购客户」
   * 的接口级落点：分数再高也只产出 recommendedAction（建议），
   * 真正的 final_action（最终动作）只能由这一行产生，且需要已认证的人工身份。
   */
  @PostMapping("/assessments/{assessmentId}/review")
  public ReviewSaved review(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID assessmentId,
      @Valid @RequestBody ReviewInput input) {
    return call(auth, "orders.update", a -> risk.review(assessmentId, input, a.membershipId()));
  }
}
