package com.starcon.crm;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * {@link PlatformMessageSendPort} 的**当前实现**：逐平台如实答「未接入」。
 *
 * <p>为什么要有这么一个「什么都不做」的实现，而不是干脆不写这个端口：
 * 蓝图 15.1.1.2 的输出句明确包含「平台回执」，
 * 而**端口先立起来**才能让「这一块缺什么、缺在谁身上」变成一条**可查的事实**
 * （能力声明里的 platform_message_api 维度 + 投递入口的拒绝），
 * 而不是一句写在文档里的说明。
 *
 * <p>⚠️ 本实现**刻意不返回成功回执**：它对每个平台都答「未接入 + 为什么 + 归谁」，
 * 投递时 {@code connected=false}、{@code sent=false}、失败原因写明缺的是什么。
 * 适配器交付之后，只需要换掉这一个实现类。
 */
@Service
public class UnavailablePlatformMessageSendPort implements PlatformMessageSendPort {

  /**
   * 未接入的**归属模块**：2 = 渠道与店铺（平台适配器都在 channel 服务的 vendor 包下）。
   *
   * <p>不是 15 —— 15 是客户与客服卷，而平台适配器按蓝图第 2 卷归 channel。
   */
  static final String OWNER_MODULE = "2";

  @Override
  public List<PlatformSendAvailability> availability(List<String> platformCodes) {
    List<PlatformSendAvailability> out = new ArrayList<>();
    if (platformCodes == null) {
      return out;
    }
    for (String code : platformCodes) {
      if (code == null || code.isBlank()) {
        continue;
      }
      out.add(new PlatformSendAvailability(code, false, reason(code), OWNER_MODULE));
    }
    return out;
  }

  @Override
  public PlatformSendOutcome send(PlatformSendRequest request, String bearer) {
    // ⚠️ 这里**不**返回一条「已发送」了事：一条成功的假回执会让「消息已经发给买家了」
    // 在数据上看起来发生过，而现场一条真消息都没发出去 ——
    // 蓝图加粗句「发送失败要保留原消息状态」防的正是这件事。
    String platform = request == null ? "" : request.platformCode();
    return new PlatformSendOutcome(
        false, false, "", "", reason(platform), OWNER_MODULE);
  }

  /** 逐平台的原因文案：说清「缺什么、归谁、现在怎么办」。 */
  private String reason(String platformCode) {
    return "平台「"
        + platformCode
        + "」的买家站内信**发送**适配器**未接入**："
        + "channel 服务的 vendor 包下当前只有 OAuth 适配器，没有站内信发送适配器，"
        + "所以平台侧的消息发送接口还没有通道。"
        + "在此之前只能走**人工交接**（transport=MANUAL_HANDOFF）："
        + "运营在平台侧实际发出后回本服务登记结果与平台回执，"
        + "发送记录、失败原因与重试留痕都已经可用。";
  }
}
