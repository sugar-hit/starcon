package com.starcon.identity;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * {@link ExternalChannelDeliveryPort} 的**当前实现**：如实答「未接通」。
 *
 * <p>与 platform 的 {@code UnavailableFinanceSoftwarePushPort}、
 * crm 的 {@code UnavailablePlatformEmailPort} **完全同构**：
 * 端口先立起来，实现逐能力答「未接通 + 缺什么 + 归谁」，<b>不返回任何成功回执</b>。
 *
 * <p>🔴 <b>本实现一次消息都不发</b>：不建 Webhook 客户端、不发邮件、
 * 不留「已投给企业微信」的痕迹。服务层拿到 {@code delivered=false} 之后
 * 把它翻成一条**真实的失败投递记录**（失败码 + 失败原因 + 重试排期 + 告警）。
 *
 * <p>⚠️ <b>这不代表本模块是半成品</b>：需要外部通道的只有「真的把消息送出去」这一段。
 * 与外部通道有没有接通，不影响下面几件**本系统内部能力**的真实生效：
 * 渠道登记（只落 secret manager 引用）、偏好过滤、候选清单（真实命中）、
 * 投递记录与重试与告警（**真事实**，一条假成功都没有）。
 */
@Service
public class UnavailableExternalChannelDeliveryPort implements ExternalChannelDeliveryPort {

  /**
   * 未接通的**归属模块**：<b>18 系统</b>（卷级归属）。
   *
   * <p>为什么是卷级而不是某个 L4：企业微信 / 钉钉 / 飞书 / 邮件的**外部通道适配器**
   * 不是某一格 L4 独有的 —— 18.5 通知模板下的各格要发消息时缺的是同一类通道，
   * 蓝图里也没有单独列一个「外部 IM 接入」的 L4 模块。
   * 与 {@code UnavailableFinanceSoftwarePushPort} 写「18.2」同一套做法。
   */
  static final String OWNER_MODULE = "18 集成与自动化（卷级归属：企业微信 / 钉钉 / 飞书 / 邮件的外部通道适配器）";

  /** 失败码：投递通道未接通。 */
  static final String FAILURE_CODE = "DELIVERY_CHANNEL_UNCONNECTED";

  @Override
  public List<Availability> availability() {
    var out = new ArrayList<Availability>();
    for (var code : ExternalPushRules.CHANNEL_CODES) {
      out.add(new Availability(code, false, reason(code), OWNER_MODULE));
    }
    return out;
  }

  @Override
  public DeliveryOutcome deliver(DeliveryRequest request) {
    // ⚠️ 这里**不**返回一条「已投递」了事：一条假的成功回执会让「这条通知已经到企业微信了」
    // 在数据上看起来发生过，而现场一个字都没发出去。收件人不会收到任何提醒，
    // 而运营会以为「提醒已经发出去了」。
    String channel = request == null || request.channelCode() == null ? "" : request.channelCode();
    return new DeliveryOutcome(false, FAILURE_CODE, reason(channel));
  }

  /** 逐渠道的原因文案：说清「缺什么、归谁、现在怎么办」。 */
  private String reason(String channelCode) {
    String label =
        switch (channelCode) {
          case "WECHAT_WORK" -> "企业微信";
          case "DINGTALK" -> "钉钉";
          case "FEISHU" -> "飞书";
          case "EMAIL" -> "邮件";
          default -> "外部渠道";
        };
    return "「"
        + label
        + "」的**投递通道**未接入：本仓目前没有任何真实的企业微信 / 钉钉 / 飞书 / 邮件客户端，"
        + "所以「把消息真的送出去」这一段没有通道。"
        + "该能力归 **18 集成与自动化** 卷（外部通道适配器是 18 各模块共用的外部边界）。"
        + "在此之前本模块能真做的是：把通知按**收件人的偏好**筛好（1.3.1.2）、"
        + "按**真实命中**挑出候选（1.3.2.1）、按渠道登记**凭据引用**（凭据不落库）、"
        + "并把每一次「尝试投递 → 通道未接通」**如实**落成投递记录（失败码 + 重试排期 + 告警）。";
  }
}
