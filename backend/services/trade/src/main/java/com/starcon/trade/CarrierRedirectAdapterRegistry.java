package com.starcon.trade;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 改址 / 取消的承运商适配器注册表：把 {@code adapter_key} 解析成可调用的 vendor 实现。
 *
 * <p>与 10.2.1.2 的 {@link CarrierAdapterRegistry} <b>刻意分开</b>：那张表索引的是
 * 「要号」能力（{@link CarrierWaybillPort}），本表索引的是「改址 / 撤销已有号」能力
 * （{@link CarrierRedirectPort}）。两者在现实里是<b>独立的可接通风</b>：
 * 一个渠道完全可能「能出号、不能改址」，把它们合进一张表会让界面上
 * 出现「能出号 ⇒ 也能改址」的错误推断。
 *
 * <p><b>没有实现时如实返回 unavailable</b>，而不是给一个默认实现：
 * 此刻 10.1 全部未交付，系统里一个 vendor 实现都没有是<b>真实状态</b>。
 * 若这里给个「返回已受理」的兜底实现，一次改址就会凭空变成「改址成功」，
 * 而货还在原地址那条路上 —— 买家以为改好了，客服以为处理完了，
 * 这是本模块最贵的一种错。
 */
@Service
public class CarrierRedirectAdapterRegistry {

  private final java.util.Map<String, CarrierRedirectPort> byAdapterKey;

  public CarrierRedirectAdapterRegistry(List<CarrierRedirectPort> ports) {
    this.byAdapterKey =
        ports.stream()
            .collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    CarrierRedirectPort::adapterKey, p -> p));
  }

  /** 已经接通的 vendor 键（capabilities 里逐条如实列出，不藏）。 */
  public java.util.Set<String> connectedAdapterKeys() {
    return byAdapterKey.keySet();
  }

  /**
   * 解析适配器。
   *
   * <p>{@code carrierCode} 也参与判断：同一个 {@code adapter_key} 理论上可以服务多个
   * 承运商代码，但<b>没有实现时两条路径都返回 unavailable</b>，
   * ownerModule 写死 10.1.1.1（承运商列表与连接是同一批未交付模块）。
   */
  public CarrierRedirectPort.Resolution resolve(String carrierCode, String adapterKey) {
    var port = byAdapterKey.get(adapterKey);
    if (port == null) {
      return unavailable(carrierCode, adapterKey);
    }
    // 注册表只证明「这个 vendor 包在不在」；**能不能用这一票**由 vendor 自己判定
    // （凭证过期、渠道停用、灰度开关都是 vendor 的知识）。
    // 所以这里把判定交回给 port，而不是见到实现就一律 Connected ——
    // 否则「已注册但此刻不可用」会被显示成能改址。
    var resolution = port.resolve(carrierCode, adapterKey);
    return resolution == null ? unavailable(carrierCode, adapterKey) : resolution;
  }

  private CarrierRedirectPort.Resolution unavailable(String carrierCode, String adapterKey) {
    return new CarrierRedirectPort.Resolution.Unavailable(
        adapterKey,
        "10.1.1.1",
        "承运商 "
            + carrierCode
            + "（适配器键 "
            + adapterKey
            + "）的改址 / 取消通道尚未接通：10.1 物流渠道全部未交付"
            + "（10.1.1.1 承运商列表 / 10.1.1.2 渠道配置 / 10.1.1.3 时效与限制 /"
            + " 10.1.2.1 运费计算器 / 10.1.2.2 渠道比价），"
            + "蓝图卷 10 写明「channel 拥有物流商连接与轨迹」，"
            + "本模块因此**不会凭空受理一次改址或取消**");
  }
}
