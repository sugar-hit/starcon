package com.starcon.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 5.5.1.1 价格历史的**销量端口装配**（读 9 订单履约 / trade 服务的销量事实）。
 *
 * <p><b>为什么单独一个装配类</b>：{@link SalesFactPort} 有两个实现 ——
 * 真的走 HTTP 的 {@link SalesFactPort.HttpSalesFactPort}，
 * 与「上游没配」的 {@link SalesFactPort.UnwiredSalesFactPort}。
 * 选哪一个取决于 {@code TRADE_URL} 有没有配，而这件事是**部署事实**，不是代码分支。
 *
 * <p>🔴 <b>没配地址时必须走 Unwired，而不是走一个「返回 0」的实现</b>：
 * 销量是 0 会让页面显示「降价后一件没卖出去」——那是一个**凭空造出来的结论**，
 * 比「销量维度未接线」危险得多。
 * {@link SalesFactPort.UnwiredSalesFactPort} 因此**显式抛错**，由服务层翻成 503。
 */
@Configuration
public class SalesFactConfiguration {

  /**
   * 销量事实端口。
   *
   * @param url trade 服务地址。留空 ⇒ 用 Unwired 实现（显式报「读不到」）。
   */
  @Bean
  public SalesFactPort salesFactPort(@Value("${TRADE_URL:}") String url) {
    if (url == null || url.isBlank()) {
      return new SalesFactPort.UnwiredSalesFactPort();
    }
    return new SalesFactPort.HttpSalesFactPort(url);
  }
}
