package com.starcon.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 读取 **12.7 资金账户** 与 **12.2 收付款**（finance 服务，端口 8087）的**跨服务只读端口**。
 *
 * <p>蓝图 18.2.2.1 的关系写明「<b>上游 = 12.2 收付款、12.7 资金账户</b>」——
 * 资金账户与付款单**本身就住在** finance 的库里
 * （{@code finance_fund_account} / {@code finance_payment_order}）。
 * 本端口只读它，不抄它。
 *
 * <p>⚠️ **本服务不建资金账户表、不抄余额历史**（与 platform 的 {@code FinanceVoucherPort}、
 * {@code CatalogTranslationPort} 同一纪律）：资金账户是会计事实，抄一份就会漂移 ——
 * finance 那边改了余额，本服务这边还留着旧的，对账会与真实账对不上。
 *
 * <p>⚠️ <b>只转发调用方自己的 bearer，不传租户号</b>：上游仍按已认证成员关系解析租户并强制 RLS。
 * 租户隔离因此是<b>两道</b>（本服务 RLS + 上游 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>⚠️ <b>取不到就 503</b>，不返回空集合冒充「上游没有资金账户」。
 *
 * <p>🔴 <b>只读、只认对象形状的端点**：本端口只调**返回 JSON 对象**的端点
 * （列表端点返回的是 page 对象），不调返回裸数组的端点 ——
 * {@link PlatformUpstreamClient} 只按 {@code Class<T>} 反序列化，
 * 裸数组会被 Jackson 拆成 {@code List<Object>}，字段全丢。
 */
@Service
public class FinanceFundPort {

  private final PlatformUpstreamClient client;

  public FinanceFundPort(@Value("${FINANCE_URL:http://127.0.0.1:8087}") String url) {
    this.client = PlatformUpstreamClient.to("finance 服务", url);
  }

  // ================================================================ 12.7 资金账户

  /** 上游一个资金账户（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FundAccountRow(
      UUID id,
      String accountNo,
      String accountName,
      String accountType,
      String accountTypeLabel,
      String currency,
      BigDecimal currentBalance,
      OffsetDateTime currentBalanceAt,
      String status,
      String statusLabel,
      String note) {}

  /** 上游一页资金账户。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FundAccountPage(List<FundAccountRow> items, int page, int size, long total) {}

  /**
   * 拉一页资金账户（12.7）。
   *
   * @param currency 币种筛选（可空）
   * @param keyword 关键词（可空）
   */
  public FundAccountPage fundAccounts(
      String currency, String keyword, int page, int size, String bearer) {
    return client.get(
        uriBuilder -> {
          uriBuilder.path("/finance/fund-accounts").queryParam("page", Math.max(page, 0));
          uriBuilder.queryParam("size", Math.min(Math.max(size, 1), 100));
          if (currency != null && !currency.isBlank()) uriBuilder.queryParam("currency", currency);
          if (keyword != null && !keyword.isBlank()) uriBuilder.queryParam("keyword", keyword);
          return uriBuilder;
        },
        bearer,
        FundAccountPage.class);
  }

  // ================================================================ 12.2 收付款

  /** 上游一张付款单（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PaymentOrderRow(
      UUID id,
      String orderNo,
      String counterpartyType,
      String counterpartyName,
      String currency,
      BigDecimal amount,
      String baseCurrency,
      BigDecimal exchangeRate,
      BigDecimal baseAmount,
      String paymentMethod,
      String paymentAccountName,
      String status,
      String approvalStatus) {}

  /** 上游一页付款单。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PaymentOrderPage(
      List<PaymentOrderRow> items, int page, int size, long total) {}

  /**
   * 拉一页付款单（12.2）。
   *
   * @param status 付款单状态（可空）
   * @param currency 币种（可空）
   */
  public PaymentOrderPage paymentOrders(
      String status, String currency, String keyword, int page, int size, String bearer) {
    return client.get(
        uriBuilder -> {
          uriBuilder.path("/finance/payment-orders").queryParam("page", Math.max(page, 0));
          uriBuilder.queryParam("size", Math.min(Math.max(size, 1), 100));
          if (status != null && !status.isBlank()) uriBuilder.queryParam("status", status);
          if (currency != null && !currency.isBlank()) uriBuilder.queryParam("currency", currency);
          if (keyword != null && !keyword.isBlank()) uriBuilder.queryParam("keyword", keyword);
          return uriBuilder;
        },
        bearer,
        PaymentOrderPage.class);
  }
}
