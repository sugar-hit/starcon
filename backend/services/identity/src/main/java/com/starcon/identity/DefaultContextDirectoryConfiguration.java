package com.starcon.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 1.4.2.2 默认店铺与仓库 —— 跨服务只读端口的**装配**。
 *
 * <p><b>为什么单独一个装配类</b>：两个目录端口各有两个实现 ——
 * 真的走 HTTP 的实现，与「上游没配」的实现。选哪一个取决于 {@code CHANNEL_URL} /
 * {@code WAREHOUSE_URL} 有没有配，而这件事是<b>部署事实</b>，不是代码分支。
 *
 * <p><b>🔴 没配地址时必须走 Unwired，而不是走一个「返回空列表」的实现</b>：
 * 空列表会让「默认店铺 / 默认仓库」的选择器变成空的，而用户看不出是配置问题 ——
 * 「这个租户没有店铺」与「我们没接上游」是两件事。
 * Unwired 实现因此<b>显式报错</b>，由服务层翻成「该维度读不到」。
 */
@Configuration
public class DefaultContextDirectoryConfiguration {

  /**
   * 店铺目录端口（读 channel 的 2.3.1.1 店铺列表）。
   *
   * @param url channel 服务地址。留空 ⇒ 用 Unwired 实现（显式报「读不到」）。
   */
  @Bean
  public DefaultContextShopDirectoryPort defaultContextShopDirectoryPort(
      @Value("${CHANNEL_URL:}") String url) {
    if (url == null || url.isBlank()) {
      return new DefaultContextShopDirectoryPort.UnwiredDefaultContextShopDirectoryPort();
    }
    return new DefaultContextShopDirectoryPort.HttpDefaultContextShopDirectoryPort(url);
  }

  /**
   * 仓库目录端口（读 warehouse 的 8.1.1.1 仓库档案列表）。
   *
   * @param url warehouse 服务地址。留空 ⇒ 用 Unwired 实现（显式报「读不到」）。
   */
  @Bean
  public DefaultContextWarehouseDirectoryPort defaultContextWarehouseDirectoryPort(
      @Value("${WAREHOUSE_URL:}") String url) {
    if (url == null || url.isBlank()) {
      return new DefaultContextWarehouseDirectoryPort.UnwiredDefaultContextWarehouseDirectoryPort();
    }
    return new DefaultContextWarehouseDirectoryPort.HttpDefaultContextWarehouseDirectoryPort(url);
  }
}
