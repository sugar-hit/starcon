package com.starcon.channel.vendor;

import org.springframework.stereotype.Component;

/**
 * 14.5.1.2 建联与样品的<b>平台官方私信端口 · 未接入实现</b>。
 *
 * <p>🔴 对所有平台一律回答「未接入」，并且：
 *
 * <ul>
 *   <li><b>不返回任何达人</b>（本端口的形状里根本没有「取达人」这一项）；
 *   <li><b>不编造平台接口</b>（没有 URL、没有 client、没有 token）；
 *   <li><b>不返回硬编码列表</b>（连「可发名单」这种形状都没有）；
 *   <li><b>不抛异常</b> —— 未接入是一个<b>已知状态</b>，不是故障，
 *       抛异常会让上层把它当成偶发错误去重试（重试一个没接入的接口毫无意义）。</li>
 * </ul>
 *
 * <p>与 {@link UnavailableInfluencerDataPort}（14.5.1.1 的达人数据同步端口）同一手法：
 * 把「平台接口那一半」显式建模为未接入，让服务层据此**不写任何数据**，
 * 而不是塞假数据或空壳接口。
 *
 * <p>🔴 本类同时是「风控红线」的技术落点：蓝图说「建联必须走官方接口，
 * 批量私信是平台风控重点，第三方工具易导致账号风险」—— 所以这里<b>没有</b>
 * 任何第三方群发通道的实现位，也没有批量发送的入口。
 */
@Component
public class UnavailableInfluencerOutreachPort implements InfluencerOutreachPort {

  /** 未接入的人话原因（界面直接显示这一句）。 */
  static final String REASON =
      "平台达人私信官方接口未接入：channel 的平台适配层目前只有 OAuth 授权适配器，"
          + "没有任何平台的达人私信接口。建联必须走官方接口（批量私信是平台风控重点），"
          + "所以本模块**不接第三方群发通道**。请在平台官方后台发送后，"
          + "用「登记平台回执」把已发送的事实录进来。";

  @Override
  public Availability availability(String platformCode) {
    return new Availability(false, "NOT_INTEGRATED", REASON);
  }

  @Override
  public SendResult send(SendRequest request) {
    // 🔴 未接入 ⇒ 不发送、**不落库**、不返回任何假的消息 id。
    return new SendResult(false, REASON, "");
  }
}
