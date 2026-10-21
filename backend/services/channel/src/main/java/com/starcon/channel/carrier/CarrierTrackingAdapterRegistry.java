package com.starcon.channel.carrier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 10.3.1.1 承运商轨迹适配器注册表。
 *
 * <p>与 trade 侧 {@code CarrierWaybillPort} 的 {@code Resolution} 是同一套形状：
 * 对「有没有 vendor 实现」只有两种答案（接通了 / 没接通），**不给第三种
 * 「先当作有、真到调用时才炸」**。
 *
 * <p>当前<b>没有</b>已接通的 vendor 实现 —— 这是如实反映而不是缺口：
 * 10.1.1.1 承运商列表 / 10.1.1.2 渠道配置已交付，但它们交付的是
 * <b>档案与规则</b>，不是<b>出网的连接与凭据</b>；凭据与连接归 secret manager
 * 与 10.1 的连接配置（AGENTS.md「安全」一节）。
 *
 * <p>所以 {@link #resolve} 在没有实现时如实返回
 * {@link Resolution.Unavailable}，调用方据此把那一轮拉取落 {@code NO_CHANNEL}
 * 并写明理由，而不是发一批编出来的轨迹出去。
 */
@Service
public class CarrierTrackingAdapterRegistry {

  private final Map<String, CarrierTrackingAdapter> adapters = new LinkedHashMap<>();

  public CarrierTrackingAdapterRegistry(List<CarrierTrackingAdapter> implementations) {
    for (CarrierTrackingAdapter adapter : implementations) {
      adapters.put(adapter.carrierCode(), adapter);
    }
  }

  /** 解析结果。sealed：只有「有」与「没有」两种。 */
  public sealed interface Resolution {
    record Connected(CarrierTrackingAdapter adapter) implements Resolution {}

    /** 没有 vendor 实现：如实说没有，并带上 ownerModule 与影响。 */
    record Unavailable(String carrierCode, String ownerModule, String reason) implements Resolution {}
  }

  public Resolution resolve(String carrierCode) {
    CarrierTrackingAdapter adapter = adapters.get(carrierCode);
    if (adapter == null) {
      return new Resolution.Unavailable(
          carrierCode,
          "10.1.1.1",
          "承运商 "
              + carrierCode
              + " 没有已接通的轨迹通道（档案与渠道配置已交付，出网连接与凭据未交付）");
    }
    return new Resolution.Connected(adapter);
  }

  /** 已接通的承运商代码（capabilities 里如实列出「谁接通了」）。 */
  public List<String> connectedCarriers() {
    return List.copyOf(adapters.keySet());
  }
}
