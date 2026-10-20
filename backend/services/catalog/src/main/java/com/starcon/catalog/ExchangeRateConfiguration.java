package com.starcon.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 5.2.2.1 汇率查询的跨边界端口装配。
 *
 * <p>两个端口的性质不同，装配方式也不同：
 *
 * <ul>
 *   <li>{@link ExternalRateSourcePort} —— 外部汇率源（中行 / 央行 / 平台结算），
 *       地址**存在租户自己的库里**（{@code exchange_rate_source.endpoint}），
 *       所以端口本身不需要部署期配置，用一个不带基址的实现即可。
 *   <li>{@link FunctionalCurrencyPort} —— identity 服务的法人本位币（17.1.2.2），
 *       基址是部署期事实（{@code IDENTITY_URL}），没配就用 {@link FunctionalCurrencyPort.UnwiredFunctionalCurrencyPort}。
 * </ul>
 *
 * <p><b>🔴 没配地址时必须走 Unwired，而不是走一个「返回空集合」的实现</b>：
 * 本位币的唯一来源是 identity 的法人档案（AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图，
 * 所以本库里没有它的副本）。地址没配就等于**一个都读不到** ——
 * 返回空集合会让「各币种对各本位币的汇率」变成一个空面板，
 * 看不出是「没配法人」还是「identity 挂了」。Unwired 实现因此**显式抛错**，由服务层翻成 503。
 */
@Configuration
public class ExchangeRateConfiguration {

  /**
   * 外部汇率源端口。
   *
   * <p>没有 Unwired 变体：外部源地址是**租户级数据**而不是部署配置，
   * 没配地址时请求会在服务层被明确拒绝（「该汇率来源没有配置外部地址」），
   * 而不是发出一个必然失败的请求。
   */
  @Bean
  public ExternalRateSourcePort externalRateSourcePort() {
    return new ExternalRateSourcePort.HttpExternalRateSourcePort();
  }

  /**
   * 本位币端口。
   *
   * @param identityUrl identity 服务地址。留空 ⇒ 用 Unwired 实现（显式报「读不到」）。
   */
  @Bean
  public FunctionalCurrencyPort functionalCurrencyPort(@Value("${IDENTITY_URL:}") String url) {
    if (url == null || url.isBlank()) {
      return new FunctionalCurrencyPort.UnwiredFunctionalCurrencyPort();
    }
    return new FunctionalCurrencyPort.HttpFunctionalCurrencyPort(url);
  }
}
