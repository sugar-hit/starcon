package com.starcon.crm;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * {@link PlatformMessagePort} 的**当前实现**：逐平台如实答「未接入」。
 *
 * <p>为什么要有这么一个「什么都不做」的实现，而不是干脆不写这个端口：
 * 蓝图 15.1.1.1 的输入句明确包含「平台消息同步（webhook + 拉取）」，
 * 而**端口先立起来**才能让「这一块缺什么、缺在谁身上」变成一条**可查的事实**
 * （capabilities 里的 platform_message_sync 维度 + 同步入口的 409 响应），
 * 而不是一句写在文档里的说明。
 *
 * <p>⚠️ 本实现**刻意不返回任何消息**，也**刻意不编造平台接口**：
 * 它对每个平台都答「未接入 + 为什么 + 归谁」，拉取时 {@code fetchedCount=0}、
 * {@code messages} 为空、{@code connected=false}。
 * 适配器交付之后，只需要换掉这一个实现类。
 */
@Service
public class UnavailablePlatformMessagePort implements PlatformMessagePort {

  /**
   * 未接入的**归属模块**：2 = 渠道与店铺（平台适配器都在 channel 服务的 vendor 包下）。
   *
   * <p>不是 15 —— 15 是客户与客服卷，而平台适配器按蓝图第 2 卷归 channel。
   */
  static final String OWNER_MODULE = "2";

  @Override
  public List<PlatformMessageAvailability> availability(List<String> platformCodes) {
    List<PlatformMessageAvailability> out = new ArrayList<>();
    if (platformCodes == null) {
      return out;
    }
    for (String code : platformCodes) {
      if (code == null || code.isBlank()) {
        continue;
      }
      out.add(
          new PlatformMessageAvailability(
              code, false, reason(code), OWNER_MODULE));
    }
    return out;
  }

  @Override
  public PlatformMessageFetch fetch(
      String platformCode, UUID shopId, String since, String bearer) {
    // ⚠️ 这里**不返回一份空列表**了事：空列表在调用方眼里就是「这个平台没有消息」。
    // 本实现明确说「连不上 / 没接入」，由服务层据此把入口变成 409。
    return new PlatformMessageFetch(false, 0, List.of(), reason(platformCode), OWNER_MODULE);
  }

  /** 逐平台的原因文案：说清「缺什么、归谁、现在怎么办」。 */
  private String reason(String platformCode) {
    return "平台「"
        + platformCode
        + "」的买家站内信适配器**未接入**："
        + "channel 服务的 vendor 包下当前只有 OAuth 适配器，没有站内信适配器，"
        + "所以 webhook 推送与拉取都还没有通道。"
        + "在此之前消息只能**人工登记**（POST /crm/message-inbox），"
        + "收件箱的列表 / 详情 / 筛选 / 批量处理都已经可用。";
  }
}
