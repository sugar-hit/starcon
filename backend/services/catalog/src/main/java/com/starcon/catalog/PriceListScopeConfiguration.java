package com.starcon.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 5.1.1.1 价目表的适用范围目录端口装配（法人 = identity，店铺 = channel）。
 *
 * <p><b>为什么单独一个装配类</b>：{@link PriceListScopePort} 有两个实现 ——
 * 真的走 HTTP 的 {@link PriceListScopePort.HttpPriceListScopePort}，
 * 与「上游地址一个都没配」的 {@link PriceListScopePort.UnwiredPriceListScopePort}。
 * 选哪一个取决于 {@code IDENTITY_URL} / {@code CHANNEL_URL} 有没有配，
 * 而这件事是**部署事实**，不是代码分支。
 *
 * <p><b>🔴 没配地址时必须走 Unwired，而不是走一个「返回空列表」的实现</b>：
 * 空列表会让页面的「适用范围」选择器变成空的，而用户看不出是配置问题 ——
 * 「这个租户没有法人 / 没有店铺」与「我们没接上游」是两件事。
 * {@link PriceListScopePort.UnwiredPriceListScopePort} 因此**显式抛错**，由服务层翻成 503。
 *
 * <p><b>只配了一侧</b>时走 {@link PriceListScopePort.HttpPriceListScopePort}：
 * 它对没配的那一侧单独报「未配置」，另一侧照常可用 ——
 * 两个上游各自的可用性是**两件事**，不该因为一个没配就把另一个也废掉。
 */
@Configuration
public class PriceListScopeConfiguration {

  /**
   * 适用范围目录端口。
   *
   * @param identityUrl identity 服务地址（法人档案，17.1.2.2）
   * @param channelUrl channel 服务地址（店铺与站点，2.3.1.1 / 2.1.1.1）
   */
  @Bean
  public PriceListScopePort priceListScopePort(
      @Value("${IDENTITY_URL:}") String identityUrl, @Value("${CHANNEL_URL:}") String channelUrl) {
    boolean identityMissing = identityUrl == null || identityUrl.isBlank();
    boolean channelMissing = channelUrl == null || channelUrl.isBlank();
    if (identityMissing && channelMissing) {
      return new PriceListScopePort.UnwiredPriceListScopePort();
    }
    return new PriceListScopePort.HttpPriceListScopePort(identityUrl, channelUrl);
  }
}
