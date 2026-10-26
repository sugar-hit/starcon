package com.starcon.platform;

import com.starcon.tenancy.TenantTransactions;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 租户上下文接线。
 *
 * <p>与 trade 的 {@code TradeTenancyConfiguration}、crm 的 {@code CrmTenancyConfiguration}、
 * finance 的 {@code FinanceTenancyConfiguration}、reporting 的 {@code ReportingTenancyConfiguration}
 * <b>完全同构</b>：同一个 {@link TenantTransactions} 类、同一个事务局部 {@code app.current_tenant} GUC，
 * 差别只在服务名。
 *
 * <p>为什么既有服务（identity / catalog / channel / warehouse）在控制器构造函数里 {@code new
 * TenantTransactions(...)}，而 trade / crm / finance / reporting / platform 把它声明成 Bean：
 * 集成测试要 {@code @Autowired} 它来**直接**跑 AGENTS.md 要求的三条隔离断言 （两租户互不可见 /
 * 伪造租户上下文无效 / 连接池归还后不串租户），而测试不走控制器。声明成 Bean 让测试与生产用<b>同一个
 * 绑定实现</b>，不需要在测试里另写一份「简化版」—— 租户绑定一旦出现第二个实现就得分叉审计。
 *
 * <p>租户从哪来、伪造 {@code X-Tenant-Id} 头为什么无效，都不在本类解决： 头到租户的解析由
 * {@link AccessService} 向 identity 确认已认证成员关系后完成， 不信任客户端自称。
 */
@Configuration
public class PlatformTenancyConfiguration {

  @Bean
  public TenantTransactions tenantTransactions(DSLContext sql, PlatformTransactionManager manager) {
    return new TenantTransactions(sql, new TransactionTemplate(manager));
  }
}
