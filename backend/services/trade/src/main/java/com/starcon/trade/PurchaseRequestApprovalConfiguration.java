package com.starcon.trade;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 6.2.2.1 采购申请的**审批端口装配**（读写 17 组织的 identity 服务）。
 *
 * <p>与 {@link PurchasePlanWarehousePort}（读 warehouse）、
 * {@link SupplierPartyQueryPort}（读 catalog）同一手法：
 * {@link PurchaseRequestApprovalPort} 有两个实现 ——
 * 真走 HTTP 的 {@link PurchaseRequestApprovalPort.HttpPurchaseRequestApprovalPort}，
 * 与「上游没配」的 {@link PurchaseRequestApprovalPort.UnwiredPurchaseRequestApprovalPort}。
 * 选哪一个取决于 {@code IDENTITY_URL} 有没有配，而这件事是**部署事实**，不是代码分支。
 *
 * <p><b>🔴 没配地址时必须走 Unwired，而不是走一个「默认放行」的实现</b>：
 * 默认放行会让「审批这一环被悄悄跳过」——而蓝图那句「走审批」正是为了防这个。
 * {@link PurchaseRequestApprovalPort.UnwiredPurchaseRequestApprovalPort} 因此**显式抛错**，
 * 由服务层翻成 503：申请单停在「待审」并如实标出「审批服务不可达」，**绝不**自动跳到已批。
 */
@Configuration
public class PurchaseRequestApprovalConfiguration {

  /**
   * 审批端口。
   *
   * <p>{@code INTERNAL_KEY} 是服务间授权密钥，与本服务调 identity 的 {@code /internal/access}
   * 用的是同一份配置（{@link AccessService} 也注入它）—— 它<b>从配置注入，绝不写死在代码里</b>。
   */
  @Bean
  public PurchaseRequestApprovalPort purchaseRequestApprovalPort(
      @Value("${IDENTITY_URL:}") String url, @Value("${INTERNAL_KEY:}") String internalKey) {
    if (url == null || url.isBlank()) {
      return new PurchaseRequestApprovalPort.UnwiredPurchaseRequestApprovalPort();
    }
    return new PurchaseRequestApprovalPort.HttpPurchaseRequestApprovalPort(url, internalKey);
  }
}
