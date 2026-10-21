package com.starcon.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 5.4.2.1 平台价格同步的跨服务端口装配（channel 服务的 02 队列）。
 *
 * <p><b>为什么单独一个装配类</b>：{@link PlatformPriceSyncPort} 有两个实现 ——
 * 真的走 HTTP 的 {@link PlatformPriceSyncPort.HttpPlatformPriceSyncPort}，
 * 与「上游没配」的 {@link PlatformPriceSyncPort.UnwiredPlatformPriceSyncPort}。
 * 选哪一个取决于 {@code CHANNEL_URL} 有没有配，而这件事是**部署事实**，不是代码分支。
 *
 * <p><b>🔴 没配地址时必须走 Unwired，而不是走一个「假装投递成功」的实现</b>：
 * 假装成功会让页面上显示「已回写平台」，而平台侧什么也没发生 ——
 * 那正是蓝图那句「不能假设回写即成功」最典型的违反方式。
 * {@link PlatformPriceSyncPort.UnwiredPlatformPriceSyncPort} 因此**显式抛错**，
 * 由服务层翻译成「本次没发出去」并落进差异台账。
 */
@Configuration
public class PlatformPriceSyncConfiguration {

  /**
   * 02 队列端口。
   *
   * @param url channel 服务地址。留空 ⇒ 用 Unwired 实现（显式报「没发出去」）。
   */
  @Bean
  public PlatformPriceSyncPort platformPriceSyncPort(@Value("${CHANNEL_URL:}") String url) {
    if (url == null || url.isBlank()) {
      return new PlatformPriceSyncPort.UnwiredPlatformPriceSyncPort();
    }
    return new PlatformPriceSyncPort.HttpPlatformPriceSyncPort(url);
  }
}
