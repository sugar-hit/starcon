package com.starcon.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 5.3.1.1 利润测算器的跨服务端口装配（读 2.1.1.1 平台目录）。
 *
 * <p><b>为什么单独一个装配类</b>：{@link ChannelPlatformPort} 有两个实现 ——
 * 真的走 HTTP 的 {@link ChannelPlatformPort.HttpChannelPlatformPort}，
 * 与「上游没配」的 {@link ChannelPlatformPort.UnwiredChannelPlatformPort}。
 * 选哪一个取决于 {@code CHANNEL_URL} 有没有配，而这件事是**部署事实**，不是代码分支。
 *
 * <p><b>🔴 没配地址时必须走 Unwired，而不是走一个「返回空列表」的实现</b>：
 * 空列表会让页面的「目标店铺」选择器变成空的，而用户看不出是配置问题 ——
 * 「这个平台没有站点」与「我们没接上游」是两件事。
 * {@link ChannelPlatformPort.UnwiredChannelPlatformPort} 因此**显式抛错**，由服务层翻成 503。
 */
@Configuration
public class ChannelPlatformConfiguration {

  /**
   * 平台目录端口。
   *
   * @param url channel 服务地址。留空 ⇒ 用 Unwired 实现（显式报「读不到」）。
   */
  @Bean
  public ChannelPlatformPort channelPlatformPort(@Value("${CHANNEL_URL:}") String url) {
    if (url == null || url.isBlank()) {
      return new ChannelPlatformPort.UnwiredChannelPlatformPort();
    }
    return new ChannelPlatformPort.HttpChannelPlatformPort(url);
  }
}
