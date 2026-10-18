package com.starcon.trade;

import static com.starcon.trade.RefundApplicationModels.*;

import java.math.BigDecimal;
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
 * 11.3.1.1 退款申请。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「发起退款：选择退款方式（原路/平台/余额）、金额（可部分）、
 * 原因，校验累计退款不超过可退余额，提交审批后交财务执行。」
 * 输出：「退款申请单（待审/已批/执行中/成功/失败）+ 可退余额更新。」
 * 加粗句：「<b>可退余额校验必须在服务端做</b>——重复提交退款是资损事故的高发点。」
 *
 * <p><b>命名空间独立于 {@code /trade/refunds}</b>：那是 11.3.1.2 退款状态的命名空间
 * （执行、查证、派发、对账差异），本模块是<b>申请与审批</b>的那一半。
 * 塞进 {@code /trade/refunds/refund-request} 会让「申请」看起来是「执行记录」的一个子资源，
 * 而两者的写入所有者不同（见 V166 第五节），混在一个命名空间里迟早有人代写对方那张表。
 *
 * <p><b>权限码沿用</b> {@code trade.aftersales.read} / {@code trade.aftersales.write}：
 * 本模块与售后单、退货授权单是同一业务面。一个「能看售后单但不能发起退款」的权限位
 * 在售后场景里没有任何业务含义，而申请单带着<b>原收款流水号</b>与<b>平台退款单号</b>。
 *
 * <p><b>刻意不提供</b>：{@code DELETE /{id}} —— 退款审批是资金凭据，
 * 删掉一行等于抹掉「这笔钱为什么退、谁批的」这段证据。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} / {@link #write} 先向 identity 确认已认证成员关系，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 本模块的端点大多是<b>按 id 直达</b>的 URL —— 拿别的租户的申请 ID 来读，
 * 得到的是与「不存在」<b>完全相同</b>的错误（不告诉调用方「这个 ID 存在但不属于你」）。
 */
@RestController
@RequestMapping("/trade/refund-applications")
public class RefundApplicationController {

  private static final String READ = "aftersales.read";
  private static final String WRITE = "aftersales.write";

  private final AccessService access;
  private final RefundApplicationService refunds;

  public RefundApplicationController(AccessService access, RefundApplicationService refunds) {
    this.access = access;
    this.refunds = refunds;
  }

  /** 统一读入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + READ);
    return access.run(actor, "trade." + READ, () -> work.apply(actor));
  }

  /** 写入口：需要 {@code trade.aftersales.write}。租户来源与读入口完全一致。 */
  private <T> T write(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + WRITE);
    return access.run(actor, "trade." + WRITE, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：职责句的五个区块逐块报可用性，下游逐个报 Delivered=false 与归属模块号、
   * 本模块**刻意不做**的事、以及「可退余额必须在服务端算」与「超时不等于失败」两条口径。
   *
   * <p>路径刻意是 {@code capabilities}（租户级静态事实），不带 id 段。
   */
  @GetMapping("/capabilities")
  public RefundCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> refunds.capabilities());
  }

  // ================================================================ 可退余额校验

  /**
   * 可退余额校验（蓝图加粗句的直接落点）。
   *
   * <p>五个占用桶逐个单列；<b>可退余额允许为负且不截断成 0</b> ——
   * 「超了 50 块」与「正好退光了」在界面上必须分得开。
   */
  @GetMapping("/refundable-balance")
  public RefundBalance refundableBalance(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID caseId) {
    return call(auth, actor -> refunds.balance(caseId));
  }

  // ================================================================ 审批规则

  /** 读本币种的审批规则。<b>没有配置时 requiresDualApproval 恒为 true</b>（fail-closed）。 */
  @GetMapping("/approval-policy")
  public RefundPolicyView approvalPolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String currency,
      @RequestParam(required = false) BigDecimal amount) {
    return call(auth, actor -> refunds.policy(currency, amount));
  }

  /** 维护审批规则（租户可维护的真表，不是代码里的 if）。 */
  @PutMapping("/approval-policy")
  public RefundPolicyView upsertApprovalPolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody RefundPolicyUpsert request) {
    return write(auth, actor -> refunds.upsertPolicy(request, actor.userId()));
  }

  // ================================================================ 列表 / 详情

  /** 列表：真分页 + 多维筛选 + 排序白名单 + 全量汇总。 */
  @GetMapping
  public RefundPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String method,
      @RequestParam(required = false) String reason,
      @RequestParam(required = false) UUID caseId,
      @RequestParam(required = false) Boolean pendingReviewOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort) {
    return call(
        auth,
        actor ->
            refunds.list(
                new RefundListQuery(
                    page, size, status, method, reason, caseId, pendingReviewOnly, keyword, sort)));
  }

  /** 筛选项词表：来自真实数据的 DISTINCT + 计数，不是写死的常量数组。 */
  @GetMapping("/filters")
  public RefundApplicationFilterOptions filters(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> refunds.filters());
  }

  /** 单张申请单全景。**不存在的 id 与不属于本租户的 id 返回同一条错误** —— 见类注释。 */
  @GetMapping("/{id}")
  public RefundDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, actor -> refunds.detail(id));
  }

  // ================================================================ 写

  /**
   * 发起退款申请（草稿）。
   *
   * <p><b>建单不是提交</b>：建单只登记方式 / 金额 / 原因，提交审批是 {@code /submit} 的事 ——
   * 两者合成一个动作会让「谁提的、什么时候提的」在留痕里消失。
   * 金额在<b>建单这一刻</b>就过一次可退余额校验，且在<b>提交那一刻</b>再过一次。
   */
  @PostMapping
  public RefundDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody RefundCreate request) {
    return write(
        auth,
        // ⚠️ createdBy 走 actor 而不是请求体：请求体里没有这个字段，
        //   身份只认已认证的成员关系。
        actor -> refunds.create(request, actor.userId()));
  }

  /** 改草稿要素。**只有 DRAFT 可改**：已提交的申请是一份待审凭据，审批人看到的必须是同一个数字。 */
  @PutMapping("/{id}")
  public RefundDetail update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody RefundUpdate request) {
    return write(auth, actor -> refunds.update(id, request));
  }

  /** 提交审批：在提交这一刻<b>重新</b>算一次可退余额（页面打开到提交之间额度可能已被占走）。 */
  @PostMapping("/{id}/submit")
  public RefundDetail submit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody RefundSubmit request) {
    return write(auth, actor -> refunds.submit(id, request, actor.userId()));
  }

  /**
   * 审批：同意 / 驳回（初审与复审同一个入口）。
   *
   * <p>落到「已批（单签）」还是「待复审」由<b>审批规则</b>决定，调用方不能指定；
   * 驳回必须说明理由。
   */
  @PostMapping("/{id}/decide")
  public RefundDetail decide(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody RefundDecision request) {
    return write(auth, actor -> refunds.decide(id, request, actor.userId()));
  }

  /**
   * 交财务执行（蓝图「提交审批后交财务执行」）。
   *
   * <p><b>必须指明一条已存在的退款执行记录</b>（11.3.1.2 / V159）：本模块只引用，不创建。
   * 真实付款动作属 12.2.2.2，12 财务整卷未交付。
   */
  @PostMapping("/{id}/dispatch")
  public RefundDetail dispatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody RefundDispatch request) {
    return write(auth, actor -> refunds.dispatch(id, request, actor.userId()));
  }

  /**
   * 登记执行结果：成功 / 失败。
   *
   * <p>关联的执行记录没定论时**不许**在这里宣布成功或失败 ——
   * 外部退款超时不等于失败，重复发起退款会造成实质资损。
   */
  @PostMapping("/{id}/execution-outcome")
  public RefundDetail recordOutcome(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody RefundExecutionOutcome request) {
    return write(auth, actor -> refunds.recordOutcome(id, request, actor.userId()));
  }

  /** 人工撤销：草稿 / 待审 / 已批都可以撤（钱还没出去）。已派发的钱在途上，撤不掉。 */
  @PostMapping("/{id}/cancel")
  public RefundDetail cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody RefundCancel request) {
    return write(auth, actor -> refunds.cancel(id, request, actor.userId()));
  }
}
