package com.starcon.platform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 外部系统适配器的**注册表**：适配器代码 → 适配器实现。
 *
 * <p>🔴 <b>接入某个外部系统时只需要注册一个 {@link ExternalIntegrationPort} bean</b>，
 * 不必改本类：构造函数注入的 {@code overrides} 列表里出现的适配器
 * 会**覆盖**内置的「未接入」实现（按适配器代码匹配）。
 *
 * <p>⚠️ <b>内置的六个实现都是「未接入」</b>（本仓没有任何真实外部系统凭据与网络通道），
 * 它们逐个如实答「未接入 + 缺什么 + 归谁」，<b>一次成功记录都不写</b>。
 * 这不是「假装接通」—— 恰恰相反：把「没接通」变成一条**可查的事实**。
 *
 * <p><b>为什么用「注册表」而不是直接注入六个实现</b>：直接注入会让服务层
 * 出现六个并列的参数（并带来「再加一个外部系统要改构造器」的问题）；
 * 注册表把「适配器代码」变成唯一的选择入口 ——
 * 服务层拿到的永远是「按配置里的代码找到的那个适配器」，
 * 配错了代码就在这里报出来，而不是悄悄用别的外部系统的通道。
 *
 * <p>🔴 <b>注册表只按代码分发，不做任何健康聚合</b>：
 * 适配器之间**互相看不见** —— 「一个外部服务故障不应影响其他集成」
 * 在这一层是结构性的，不是靠约定。
 */
@Component
public class ExternalIntegrationRegistry {

  private final Map<String, ExternalIntegrationPort> adapters;

  public ExternalIntegrationRegistry(List<ExternalIntegrationPort> overrides) {
    var map = new LinkedHashMap<String, ExternalIntegrationPort>();
    // 内置的六个「未接入」实现（逐个如实答「未接入」）。
    map.put("ALIBABA_1688", new Alibaba1688IntegrationAdapter());
    map.put("TAOBAO", new TaobaoIntegrationAdapter());
    map.put("OVERSEAS_WAREHOUSE", new OverseasWarehouseIntegrationAdapter());
    map.put("LOGISTICS_CARRIER", new LogisticsCarrierIntegrationAdapter());
    map.put("CERTIFICATION_AGGREGATOR", new CertificationAggregatorIntegrationAdapter());
    map.put("SELF_HOSTED_BI", new SelfHostedBiIntegrationAdapter());
    // 外部注册的适配器覆盖内置实现（接入一个外部系统 = 注册一个 bean）。
    for (var adapter : overrides) {
      if (adapter == null || adapter.adapterCode() == null) continue;
      map.put(adapter.adapterCode().trim().toUpperCase(Locale.ROOT), adapter);
    }
    this.adapters = java.util.Collections.unmodifiableMap(map);
  }

  /** 按适配器代码取适配器；取不到返回 {@code null}（服务层给一句能直接展示的话）。 */
  public ExternalIntegrationPort find(String adapterCode) {
    if (adapterCode == null || adapterCode.isBlank()) return null;
    return adapters.get(adapterCode.trim().toUpperCase(Locale.ROOT));
  }

  /** 全部适配器（按适配器代码排序）。 */
  public List<ExternalIntegrationPort> all() {
    return adapters.values().stream()
        .sorted((a, b) -> a.adapterCode().compareTo(b.adapterCode()))
        .toList();
  }
}
