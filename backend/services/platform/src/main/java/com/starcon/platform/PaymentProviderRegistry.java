package com.starcon.platform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 支付服务商适配器的**注册表**：服务商代码 → 适配器实现。
 *
 * <p>🔴 <b>接入某个服务商时只需要注册一个 {@link PaymentProviderPort} bean</b>，
 * 不必改本类：构造函数注入的 {@code overrides} 列表里出现的适配器
 * 会**覆盖**内置的「未接入」实现（按服务商代码匹配）。
 *
 * <p>⚠️ <b>内置的五个实现都是「未接入」</b>（本仓没有任何真实服务商凭据与网络通道），
 * 它们逐家如实答「未接入 + 缺什么 + 归谁」，<b>一次数据都不写</b>。
 * 这不是「假装接通」—— 恰恰相反：把「没接通」变成一条**可查的事实**。
 *
 * <p><b>为什么用「注册表」而不是直接注入五个实现</b>：直接注入会让服务层
 * 出现五个并列的参数（并带来「再加一个服务商要改构造器」的问题）；
 * 注册表把「服务商代码」变成唯一的选择入口 ——
 * 服务层拿到的永远是「按配置里的代码找到的那个适配器」，
 * 配错了代码就在这里报出来，而不是悄悄用别的服务商的通道。
 */
@Component
public class PaymentProviderRegistry {

  private final Map<String, PaymentProviderPort> adapters;

  public PaymentProviderRegistry(List<PaymentProviderPort> overrides) {
    var map = new LinkedHashMap<String, PaymentProviderPort>();
    // 内置的五个「未接入」实现（逐家如实答「未接入」）。
    map.put("PAYPAL", new PayPalProviderAdapter());
    map.put("STRIPE", new StripeProviderAdapter());
    map.put("PINGPONG", new PingPongProviderAdapter());
    map.put("LIANLIAN", new LianLianProviderAdapter());
    map.put("WORLDFIRST", new WorldFirstProviderAdapter());
    // 外部注册的适配器覆盖内置实现（接入一个服务商 = 注册一个 bean）。
    for (var adapter : overrides) {
      if (adapter == null || adapter.providerCode() == null) continue;
      map.put(adapter.providerCode().trim().toUpperCase(Locale.ROOT), adapter);
    }
    this.adapters = java.util.Collections.unmodifiableMap(map);
  }

  /** 按服务商代码取适配器；取不到返回 {@code null}（服务层给一句能直接展示的话）。 */
  public PaymentProviderPort find(String providerCode) {
    if (providerCode == null || providerCode.isBlank()) return null;
    return adapters.get(providerCode.trim().toUpperCase(Locale.ROOT));
  }

  /** 全部适配器（按服务商代码排序）。 */
  public List<PaymentProviderPort> all() {
    return adapters.values().stream()
        .sorted((a, b) -> a.providerCode().compareTo(b.providerCode()))
        .toList();
  }
}
