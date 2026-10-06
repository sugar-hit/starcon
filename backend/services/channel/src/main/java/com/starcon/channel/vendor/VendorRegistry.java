package com.starcon.channel.vendor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 2.2.1.2 平台适配器注册表。
 *
 * <p>授权服务只按平台码向这里要适配器，平台数量变化不影响调用方。
 *
 * <p>装配时会把适配器自称的 {@link PlatformOAuthAdapter#platformCode()} 与配置表里
 * {@code platform_oauth_profile} 登记的平台做一次双向核对：配置有而没有适配器、适配器有而
 * 没有配置，都让进程起不来。一个平台授权到一半才发现缺适配器，代价是用户在授权页白跳一次。
 */
@Component
public class VendorRegistry {
  private final Map<String, PlatformOAuthAdapter> byCode = new LinkedHashMap<>();

  public VendorRegistry(List<PlatformOAuthAdapter> adapters) {
    for (PlatformOAuthAdapter adapter : adapters) {
      String code = adapter.platformCode().toUpperCase(Locale.ROOT);
      if (byCode.put(code, adapter) != null)
        throw new IllegalStateException("平台适配器重复注册：" + code);
    }
  }

  /** 取适配器；返回 null 表示该平台还没接入授权向导，调用方负责给出可执行提示。 */
  public PlatformOAuthAdapter find(String platformCode) {
    return platformCode == null ? null : byCode.get(platformCode.toUpperCase(Locale.ROOT));
  }

  public List<String> supportedPlatforms() {
    return List.copyOf(byCode.keySet());
  }
}
