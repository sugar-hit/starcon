package com.starcon.trade;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 6.2.1.3 采购计划锁库端口的装配。
 *
 * <p><b>为什么单独一个装配类</b>：{@link PurchasePlanLockPort} 有两个实现 ——
 * 真的走 HTTP 的 {@link PurchasePlanLockPort.HttpPurchasePlanLockPort}，
 * 与「上游没配」的 {@link PurchasePlanLockPort.UnwiredPurchasePlanLockPort}。
 * 选哪一个取决于 {@code WAREHOUSE_URL} 有没有配，而这件事是**部署事实**，不是代码分支。
 *
 * <p><b>🔴 没配地址时必须走 Unwired，而不是走一个「假装锁上了」的实现</b>：
 * 锁库的物理事实是 warehouse 的库存预占。地址没配就等于**一把货都锁不住** ——
 * 而如果这时候仍然返回「锁定成功」，本服务就会在自己的库里写下一批「已锁定」的计划行，
 * 仓库里却一把货都没占住。那正是蓝图加粗句要挡的「只发不收」的前身：
 * 采购员拿着一张「已锁定」的计划单去下单，货却早被别的单子订走了。
 *
 * <p>所以未接线时的行为是**显式失败**（503 + 「未接线」原文），
 * 由服务层如实记成「锁定失败」并写进锁定留痕。
 */
@Configuration
public class PurchasePlanLockConfiguration {

  /**
   * 锁库写端口。
   *
   * @param url warehouse 服务地址。留空 ⇒ 用 Unwired 实现（显式报「未接线」）。
   */
  @Bean
  public PurchasePlanLockPort purchasePlanLockPort(@Value("${WAREHOUSE_URL:}") String url) {
    if (url == null || url.isBlank()) {
      return new PurchasePlanLockPort.UnwiredPurchasePlanLockPort();
    }
    return new PurchasePlanLockPort.HttpPurchasePlanLockPort(url);
  }
}
