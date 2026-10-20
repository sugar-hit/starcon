package com.starcon.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 5.2.1.1 成本构成的跨服务端口装配。
 *
 * <p><b>为什么单独一个装配类</b>：{@link CostBasisPort} 有两个实现 ——
 * 真的走 HTTP 的 {@link CostBasisPort.HttpCostBasisPort}，与
 * 「上游没配」的 {@link CostBasisPort.UnwiredCostBasisPort}。选哪一个取决于
 * {@code WAREHOUSE_URL} 有没有配，而这件事是**部署事实**，不是代码分支。
 *
 * <p><b>🔴 没配地址时必须走 Unwired，而不是走一个「返回空集合」的实现</b>：
 * 头程分摊与关税这两个分项的唯一来源就是 warehouse 的授权只读端点
 * （AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图，所以本库里没有它们的副本）。
 * 地址没配就等于**一个都读不到** —— 返回空集合会让页面显示「头程分摊 0.00、关税 0.00」，
 * 那是在骗人：真实情况是「我们没接上游」，而这两笔在到岸成本里通常不是小数目。
 * {@link CostBasisPort.UnwiredCostBasisPort} 因此**显式抛错**，由服务层翻成 503，
 * 理由写「读不到上游」而不是「没有数据」。
 */
@Configuration
public class CostBasisConfiguration {

  /**
   * 成本基础端口。
   *
   * @param url warehouse 服务地址。留空 ⇒ 用 Unwired 实现（显式报「读不到」）。
   */
  @Bean
  public CostBasisPort costBasisPort(@Value("${WAREHOUSE_URL:}") String url) {
    if (url == null || url.isBlank()) {
      return new CostBasisPort.UnwiredCostBasisPort();
    }
    return new CostBasisPort.HttpCostBasisPort(url);
  }
}
