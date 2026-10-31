package com.starcon.platform;

/**
 * 「连连国际」的**未接入适配器**（18.2.2.1 支付与资金）。
 *
 * <p>跨境收款与付款服务商。本仓目前**没有**这个服务商的真实适配器、真实凭据与真实网络通道，
 * 所以本类逐条如实答「未接入」，<b>一次数据都不写</b>（见
 * {@link AbstractUnavailablePaymentProviderAdapter} 的纪律说明）。
 *
 * <p>🔴 本类**不猜**该服务商的接口形状（路径 / 字段名 / 签名方式）：
 * 没有真实对接文档时编一个出来，比留空更危险 —— 它会让后来接适配器的人以为
 * 协议已经确认过了。接入时覆盖本类的方法即可。
 */
class LianLianProviderAdapter extends AbstractUnavailablePaymentProviderAdapter {

  @Override
  public String providerCode() {
    return "LIANLIAN";
  }

  @Override
  public String providerLabel() {
    return "连连国际";
  }

  @Override
  public String unavailableReason() {
    return "「连连国际」的**直连适配器未接入**："
        + "本仓目前没有任何真实的 LIANLIAN 客户端"
        + "（backend / services / tools 下不存在 paypal / stripe / pingpong / lianlian / worldfirst "
        + "之类的适配器），也没有任何真实的 连连国际 凭据与网络通道，"
        + "所以「余额查询 / 收款登记 / 付款执行 / 汇率报价 / 手续费明细同步」"
        + "这五件**对 连连国际 的直连**都没有通道。"
        + "该能力归 **18.2.2 支付与资金** 卷（外部资金通道是 18.2 各模块共用的外部通道）。"
        + "在此之前可以走**账单导入**这条路："
        + "服务商配置（只存密钥**引用**）、资金流水台账、余额导入与查询、"
        + "手续费明细导入与查询、汇率观测（catalog 汇率事实）都是真实生效的。";
  }
}
