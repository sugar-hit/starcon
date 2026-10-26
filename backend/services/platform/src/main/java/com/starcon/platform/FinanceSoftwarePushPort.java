package com.starcon.platform;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * **直连推送凭证到外部财务软件**的端口（金蝶 / 用友 / SAP / Odoo）。
 *
 * <p>蓝图 18.2.1.1 的职责写明「对接第三方财务软件：凭证推送……」，输出写明「推送结果」。
 * 本端口把这一段**显式立起来**，让「缺什么、缺在谁身上」变成一条**可查的事实**
 * （能力声明里的那一维 + 推送端点的拒绝），而不是一句写在注释里的说明。
 *
 * <p>⚠️ **本仓当前没有真实的财务软件适配器**
 * （{@code backend / services / tools} 下不存在 kingdee / yonyou / sap / odoo 之类的客户端），
 * 所以当前实现是 {@link UnavailableFinanceSoftwarePushPort}：**逐厂商如实答「未接入」，
 * 一次数据都不写**。
 *
 * <p>🔴 **端口先立起来，不代表这一段是半成品**：与外部软件有没有直连通道，
 * 不影响下面三件**本系统内部能力**的真做 ——
 * 科目映射、主数据映射、凭证文件导出、余额核对差异。
 * 「业财一体化的常见做法是本系统管业务账、外部软件管法定账，
 * 两边通过凭证文件与科目映射衔接」—— 衔接的**载体就是文件与映射**，它们今天就能用。
 *
 * <p>适配器交付之后，只需要换掉 {@link UnavailableFinanceSoftwarePushPort} 这一个实现类。
 */
public interface FinanceSoftwarePushPort {

  /** 一次推送请求（**要推哪些凭证**）。 */
  record PushRequest(
      UUID accountId,
      String vendor,
      String bookCode,
      String currency,
      List<String> voucherNos,
      LocalDate dateFrom,
      LocalDate dateTo) {}

  /**
   * 一次推送的结果。
   *
   * 🔴 **当前实现的 {@code connected} 与 {@code pushed} 恒为 {@code false}** ——
   * 它**不**返回一条「已推送」的回执。一条假的成功回执会让「凭证已经推给金蝶了」
   * 在数据上看起来发生过，而现场一个字都没推出去 ——
   * 财务会拿着一张**不存在的**法定凭证去做账。
   */
  record PushOutcome(
      boolean connected,
      boolean pushed,
      String externalReference,
      String reason,
      String ownerModule) {}

  /** 某个厂商的通道可用性。 */
  record Availability(
      String vendor, boolean connected, String reason, String ownerModule) {}

  /** 逐厂商答「通道通不通」。 */
  List<Availability> availability();

  /**
   * 推一批凭证。
   *
   * <p>⚠️ 当前实现在**任何情况下都不写数据**。
   */
  PushOutcome push(PushRequest request, String bearer);
}
