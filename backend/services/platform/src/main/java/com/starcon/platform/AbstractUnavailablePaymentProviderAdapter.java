package com.starcon.platform;

import com.starcon.platform.PaymentFundsModels.PaymentAttemptOutcome;
import java.util.List;

/**
 * {@link PaymentProviderPort} 的**当前实现基类**：逐家如实答「未接入」。
 *
 * <p>与 platform 的 {@link UnavailableFinanceSoftwarePushPort}、
 * {@link UnavailableAiTextGenerationPort}、crm 的 {@code UnavailablePlatformMessageSendPort}
 * <b>完全同构</b>：端口先立起来，实现逐能力答「未接入 + 缺什么 + 归谁」，
 * <b>不返回任何成功回执</b>。
 *
 * <p>🔴 <b>本实现一次数据都不写</b>：不落成功流水、不落服务商流水号、不落任何
 * 「钱已经付出去了」的痕迹。服务层拿到 {@link PaymentAttemptOutcome#NOT_CONNECTED} 之后
 * 如实记进操作台账（状态 = NOT_CONNECTED），**不会**记成成功。
 *
 * <p>⚠️ <b>这不代表本模块是半成品</b>：需要外部适配器的只有「对某个服务商的实际调用」这一段。
 * 与服务商有没有直连通道，不影响下面这些**本系统内部能力**的真实生效：
 * 服务商配置（密钥**引用**）、幂等键与操作台账、查证兜底的状态机、
 * 资金流水台账、余额导入与查询、手续费明细导入与查询、汇率观测（catalog 汇率事实）。
 * 运营现在就能用「导出 / 导入服务商账单 → 记进本系统台账」这条完整路径。
 *
 * <p><b>子类只需给出「我是谁」与「为什么没接通」</b>；接上一个真实适配器时，
 * 覆盖对应方法即可（协议细节由适配器自己负责，本基类不猜任何服务商的接口形状）。
 */
abstract class AbstractUnavailablePaymentProviderAdapter implements PaymentProviderPort {

  /**
   * 未接入的**归属模块**：<b>18.2.2</b>（支付与资金）。
   *
   * <p>为什么不是 18.2.2.1（就是本模块）：支付服务商适配器（PayPal / Stripe / PingPong /
   * 连连 / 万里汇 的对接）不是某一格 L4 独有的，它是 18.2 卷「财务与资金」下
   * 「对外接外部资金通道」这件事的**外部通道**。
   * 蓝图里没有单独列一个「支付服务商接入」的 L4 模块，所以这里如实写成**卷级归属**（18.2）。
   */
  static final String OWNER_MODULE = "18.2.2";

  @Override
  public boolean connected() {
    return false;
  }

  /**
   * 未接通的原因（**逐服务商**）：说清「缺什么、归谁、现在怎么办」。
   *
   * <p>子类覆盖它以给出**逐服务商的**文案；基类给出通用的兜底文案。
   */
  @Override
  public String unavailableReason() {
    return "「"
        + providerLabel()
        + "」的**直连适配器未接入**：本仓目前没有任何真实的支付服务商适配器"
        + "（backend / services / tools 下不存在 paypal / stripe / pingpong / lianlian / worldfirst "
        + "之类的客户端），也没有任何真实的服务商凭据与网络通道，"
        + "所以「直接跟"
        + providerLabel()
        + "说话」这一段没有通道。"
        + "该能力归 **18.2.2 支付与资金** 卷（外部资金通道是 18.2 各模块共用的外部通道）。"
        + "在此之前可以走**账单导入**这条路："
        + "服务商配置（只存密钥**引用**）、资金流水台账、余额导入与查询、"
        + "手续费明细导入与查询、汇率观测（catalog 汇率事实）"
        + "都是真实生效的 —— 把服务商的账单 / 对账单导进来，"
        + "资金流水与手续费就在本系统里可查了。";
  }

  @Override
  public BalanceResult queryBalance(ProviderContext context, BalanceQuery query) {
    return new BalanceResult(
        PaymentAttemptOutcome.NOT_CONNECTED, null, null, null, "", unavailableReason());
  }

  @Override
  public ReceiptResult registerReceipt(ProviderContext context, ReceiptCommand command) {
    return new ReceiptResult(PaymentAttemptOutcome.NOT_CONNECTED, "", null, null, unavailableReason());
  }

  @Override
  public PaymentResult executePayment(ProviderContext context, PaymentCommand command) {
    return new PaymentResult(PaymentAttemptOutcome.NOT_CONNECTED, "", null, null, unavailableReason());
  }

  @Override
  public RateResult quoteRate(ProviderContext context, RateCommand command) {
    return new RateResult(
        PaymentAttemptOutcome.NOT_CONNECTED, null, false, null, null, null, "", unavailableReason());
  }

  @Override
  public FeeResult fetchFeeDetails(ProviderContext context, FeeQuery query) {
    return new FeeResult(PaymentAttemptOutcome.NOT_CONNECTED, List.of(), unavailableReason());
  }

  @Override
  public PaymentResult queryPayment(ProviderContext context, String idempotencyKey) {
    return new PaymentResult(PaymentAttemptOutcome.NOT_CONNECTED, "", null, null, unavailableReason());
  }
}
