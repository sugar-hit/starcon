package com.starcon.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 5.2.1.3 成本分摊的跨服务端口装配。
 *
 * <p><b>为什么单独一个装配类</b>：{@link CostAllocationPort} 有两个实现 ——
 * 真的走 HTTP 的 {@link CostAllocationPort.HttpCostAllocationPort}，
 * 与「上游没配」的 {@link CostAllocationPort.UnwiredCostAllocationPort}。
 * 选哪一个取决于 {@code WAREHOUSE_URL} 有没有配，而这件事是**部署事实**，不是代码分支。
 *
 * <p><b>🔴 没配地址时必须走 Unwired，而不是走一个「返回空列表」的实现</b>：
 * 可分摊货件的唯一来源就是 warehouse 的授权只读端点
 * （AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图，所以本库里没有它们的副本）。
 * 地址没配就等于**一个都读不到** —— 返回空列表会让页面显示「没有可分摊的货件」，
 * 那是在骗人：真实情况是「我们没接上游」。{@code UnwiredCostAllocationPort}
 * 因此**显式抛错**，由服务层翻成 503，理由写「读不到上游」而不是「没有数据」。
 *
 * <p>本装配与 5.2.1.1 的 {@link CostBasisConfiguration} 是**两条独立的线**：
 * 两个端口回答的是不同粒度的问题（按 SKU 聚合 vs 按单据逐行），
 * 强行合成一个端口会让 5.2.1.1 的既有行为被动变形。
 */
@Configuration
public class CostAllocationConfiguration {

  /**
   * 成本分摊的上游端口。
   *
   * @param url warehouse 服务地址。留空 ⇒ 用 Unwired 实现（显式报「读不到」）。
   */
  @Bean
  public CostAllocationPort costAllocationPort(@Value("${WAREHOUSE_URL:}") String url) {
    if (url == null || url.isBlank()) {
      return new CostAllocationPort.UnwiredCostAllocationPort();
    }
    return new CostAllocationPort.HttpCostAllocationPort(url);
  }
}
