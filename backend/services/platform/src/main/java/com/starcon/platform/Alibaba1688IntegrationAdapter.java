package com.starcon.platform;

/**
 * 「1688 开放平台」的**未接入适配器**（18.2.3.1 其他对接）。
 *
 * <p>1688 开放平台属于「1688 / 淘宝货源平台」这一类外部系统。本仓目前**没有**它的真实适配器、
 * 真实凭据与真实网络通道，所以本类逐条如实答「未接入」，<b>不写成功记录</b>
 * （见 {@link AbstractUnavailableExternalIntegrationAdapter} 的纪律说明）。
 *
 * <p>🔴 本类**不猜**该外部系统的接口形状（路径 / 字段名 / 签名方式）：
 * 没有真实对接文档时编一个出来，比留空更危险 —— 它会让后来接适配器的人以为
 * 协议已经确认过了。接入时覆盖本类的方法即可。
 *
 * <p>🔴 本适配器**只管这一类外部系统**：它没有能力碰别的连接的记录
 * —— 「一个外部服务故障不应影响其他集成」在代码结构上也是成立的。
 */
class Alibaba1688IntegrationAdapter extends AbstractUnavailableExternalIntegrationAdapter {

  @Override
  public String adapterCode() {
    return "ALIBABA_1688";
  }

  @Override
  public String category() {
    return "SUPPLY_SOURCE";
  }

  @Override
  public String displayName() {
    return "1688 开放平台";
  }

  @Override
  public String unavailableReason() {
    return "「1688 开放平台」的**直连适配器未接入**："
        + "本仓目前没有任何真实的外部系统客户端"
        + "（backend / services / tools 下不存在 1688 开放平台（open / 阿里巴巴开放平台）之类的货源清单客户端），也没有任何真实的 1688 开放平台 凭据与网络通道，"
        + "所以「1688 的**货源 / 商品清单**同步」这一段没有通道。"
        + "该能力归 **18.2.3 其他对接** 卷（外部通道是 18.2 各模块共用的外部通道）。"
        + "在此之前可以走**本地台账**这条路："
        + "对接连接配置（只存密钥**引用**）、同步任务配置、"
        + "每个对接**独立**的健康状态、同步记录台账、"
        + "以及「外部侧已完成」的同步人工登记都是真实生效的。";
  }
}
