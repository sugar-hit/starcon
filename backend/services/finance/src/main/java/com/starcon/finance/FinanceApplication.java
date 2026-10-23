package com.starcon.finance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 12 财务核算服务（蓝图 12 卷「后端归属」：finance 是应收应付、收付款、对账、成本、总账、发票税务的**唯一**所有者）。
 *
 * <p>服务引导与第一条业务模块 12.1.1.1 应收单管理（迁移 V338）同时落地。卷 12 的其余 31 个模块 （12.1.1.2 账龄 / 12.1.1.3 坏账核销 / 12.2.x
 * 收付款 / 12.3.x 结算对账 / 12.4.x 成本 / 12.5.x 总账 / 12.6.x 税务发票 / 12.7.x
 * 资金）当前全部「未开发」，它们各自会在后续车道里带自己的迁移与端点。
 *
 * <p>跨服务一律走授权 API / event：订单与发货确认属 trade、店铺与平台属 channel、商品属 catalog、 本服务只存逻辑 ID 与必要快照（与 trade V1 /
 * warehouse V1 / crm V99 同一纪律）， 不建跨库外键、跨库视图或任何读上游库的 SQL。
 *
 * <p>⚠️ 卷 12 的边界写死「**不自己定会计政策**」：本服务不替用户决定成本算法（先进先出 / 移动加权 / 批次）， 也不替用户决定「什么业务确认为应收」——
 * 应收确认政策是一份**租户自己维护的配置** （{@code finance_receivable_policy}），不是由订单状态推导出来的。
 */
@SpringBootApplication
public class FinanceApplication {
  public static void main(String[] args) {
    SpringApplication.run(FinanceApplication.class, args);
  }
}
