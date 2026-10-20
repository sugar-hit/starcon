package com.starcon.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 5.3.2.2 价格规则的跨服务端口装配（库龄）。
 *
 * <p><b>为什么单独一个装配类</b>：{@link InventoryAgingPort} 有两个实现 ——
 * 真的走 HTTP 的 {@link InventoryAgingPort.HttpInventoryAgingPort}，与
 * 「上游没配」的 {@link InventoryAgingPort.UnwiredInventoryAgingPort}。
 * 选哪一个取决于 {@code WAREHOUSE_URL} 有没有配，而这件事是**部署事实**，不是代码分支。
 *
 * <p><b>🔴 没配地址时必须走 Unwired，而不是走一个「返回空集合」的实现</b>：
 * 库龄是「库龄超期」触发条件的唯一来源（AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图，
 * 所以本库里没有它的副本）。地址没配就等于**一个都读不到** —— 返回空集合会让页面显示
 * 「库龄 0 天 ⇒ 没超期」，那是在骗人：真实情况是「我们没接上游」。
 * {@link InventoryAgingPort.UnwiredInventoryAgingPort} 因此**显式抛错**，由服务层翻成 503，
 * 理由写「读不到上游」而不是「没有数据」。
 */
@Configuration
public class InventoryAgingConfiguration {

  /**
   * 库龄端口。
   *
   * @param url warehouse 服务地址。留空 ⇒ 用 Unwired 实现（显式报「读不到」）。
   */
  @Bean
  public InventoryAgingPort inventoryAgingPort(@Value("${WAREHOUSE_URL:}") String url) {
    if (url == null || url.isBlank()) {
      return new InventoryAgingPort.UnwiredInventoryAgingPort();
    }
    return new InventoryAgingPort.HttpInventoryAgingPort(url);
  }
}
