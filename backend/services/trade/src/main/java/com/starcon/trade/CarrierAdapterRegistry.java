package com.starcon.trade;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 承运商适配器注册表：把 {@code adapter_key} 解析成可调用的 vendor 实现。
 *
 * <p>Spring 注入 {@link CarrierWaybillPort} 的<b>全部</b>实现并按 {@code adapterKey}
 * 建索引。这样 10.1 接通之后新增一个 vendor 包，本模块的表与服务层<b>一行都不用改</b>
 * —— AGENTS.md 要求「每个 vendor 独立包、只暴露统一接口」，扩展点就落在这里。
 *
 * <p><b>没有实现时如实返回 unavailable</b>，而不是给一个默认实现：
 * 此刻 10.1 全部未交付，系统里一个 vendor 实现都没有是<b>真实状态</b>。
 * 若这里给个「返回成功但运单号是拼出来的」兜底实现，
 * 一次大促会凭空出几千张不存在的运单，承运商那边一张都没有。
 */
@Service
public class CarrierAdapterRegistry {

  private final java.util.Map<String, CarrierWaybillPort> byAdapterKey;

  public CarrierAdapterRegistry(List<CarrierWaybillPort> ports) {
    this.byAdapterKey =
        ports.stream()
            .collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    CarrierWaybillPort::adapterKey, p -> p));
  }

  /** 已经接通的 vendor 键（capabilities 里逐条如实列出，不藏）。 */
  public java.util.Set<String> connectedAdapterKeys() {
    return byAdapterKey.keySet();
  }

  /**
   * 解析适配器。
   *
   * <p>{@code carrierCode} 也参与判断：同一个 {@code adapter_key} 理论上可以服务多个
   * 承运商代码，但**没有实现时两条路径都返回 unavailable**，
   * ownerModule 写死 10.1.1.1（承运商列表与连接是同一批未交付模块）。
   */
  public CarrierWaybillPort.Resolution resolve(String carrierCode, String adapterKey) {
    var port = byAdapterKey.get(adapterKey);
    if (port == null) {
      return unavailable(carrierCode, adapterKey);
    }
    // 注册表只证明「这个 vendor 包在不在」；**能不能用这一票**由 vendor 自己判定
    // （凭证过期、渠道停用、灰度开关都是 vendor 的知识）。
    // 所以这里把判定交回给 port，而不是见到实现就一律 Connected ——
    // 否则「已注册但此刻不可用」会被显示成能下单。
    var resolution = port.resolve(carrierCode, adapterKey);
    return resolution == null ? unavailable(carrierCode, adapterKey) : resolution;
  }

  private CarrierWaybillPort.Resolution unavailable(String carrierCode, String adapterKey) {
    return new CarrierWaybillPort.Resolution.Unavailable(
        adapterKey,
        "10.1.1.1",
        "承运商 "
            + carrierCode
            + "（适配器键 "
            + adapterKey
            + "）尚未接通：10.1 物流渠道全部未交付（10.1.1.1 承运商列表 / 10.1.1.2 渠道配置 /"
            + " 10.1.1.3 时效与限制 / 10.1.2.1 运费计算器 / 10.1.2.2 渠道比价），"
            + "蓝图卷 10 写明「channel 拥有物流商连接与轨迹」，"
            + "本模块因此**不会凭空生成一个运单号**");
  }
}
