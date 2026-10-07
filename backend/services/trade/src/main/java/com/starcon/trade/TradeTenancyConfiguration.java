package com.starcon.trade;

import com.starcon.tenancy.TenantTransactions;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 租户上下文接线。
 *
 * <p>其余服务（identity / catalog / channel / warehouse）都是在控制器构造函数里
 * {@code new TenantTransactions(sql, new TransactionTemplate(manager))**，因为它们每个都有端点，
 * 构造函数就是天然的持有者。trade 目前**没有任何端点**（卷 09 未开发），若照抄那种写法，
 * 就没有任何地方持有它 —— 租户隔离这一层会在第一条业务车道落地时被重新发明一遍。
 *
 * <p>因此这里把它声明成 Bean：与既有服务**同一个类、同一个绑定语义**，
 * 差别只在持有位置（配置类而不是控制器），第一条 9.x 车道直接构造注入即可。
 *
 * <p>租户从哪来、伪造 {@code X-Tenant-Id} 头为什么无效，都不在本类解决：
 * 头到租户的解析要等第一条端点车道引入 identity 的会话授权后才有意义，
 * 届时与其它服务同样只信任已认证的成员关系。
 */
@Configuration
public class TradeTenancyConfiguration {

  @Bean
  public TenantTransactions tenantTransactions(
      DSLContext sql, PlatformTransactionManager manager) {
    return new TenantTransactions(sql, new TransactionTemplate(manager));
  }
}