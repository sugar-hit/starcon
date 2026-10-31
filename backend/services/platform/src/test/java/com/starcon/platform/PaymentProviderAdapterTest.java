package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;

import com.starcon.platform.PaymentFundsModels.PaymentAttemptOutcome;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 18.2.2.1 的**服务商适配层单元测试**：五个服务商的「未接入」实现逐家如实答。
 *
 * <p>🔴 <b>本测试不发任何网络请求</b>：它钉住的是「未接入 ⇒ 如实答 + 一次数据都不写」，
 * 而不是「能不能连通」（本仓没有任何真实凭据与网络通道）。
 *
 * <p>⚠️ 这条断言**不能**省：适配器一旦被改成「默认成功」，
 * 服务层就会把「钱付出去了」记成事实 —— 而现场一个字都没发出去。
 */
class PaymentProviderAdapterTest {

  static final List<PaymentProviderPort> ADAPTERS =
      List.of(
          new PayPalProviderAdapter(),
          new StripeProviderAdapter(),
          new PingPongProviderAdapter(),
          new LianLianProviderAdapter(),
          new WorldFirstProviderAdapter());

  static final PaymentProviderPort.PaymentCommand COMMAND =
      new PaymentProviderPort.PaymentCommand(
          "idem-1", "PO-1", "USD", new java.math.BigDecimal("100.00"), null, null, null,
          "供应商", "ACCT-1", "SUPPLIER", "");

  @Test
  void 五个服务商逐个都如实答未接入() {
    assertThat(ADAPTERS).hasSize(5);
    var codes = ADAPTERS.stream().map(PaymentProviderPort::providerCode).sorted().toList();
    assertThat(codes)
        .as("适配器必须覆盖蓝图点名的五个服务商")
        .containsExactly("LIANLIAN", "PAYPAL", "PINGPONG", "STRIPE", "WORLDFIRST");
    for (var adapter : ADAPTERS) {
      assertThat(adapter.connected()).as(adapter.providerCode() + " 必须答未接入").isFalse();
      assertThat(adapter.unavailableReason())
          .as(adapter.providerCode() + " 必须写明缺什么")
          .contains("适配器未接入")
          .contains("账单导入");
      assertThat(adapter.unavailableReason())
          .as(adapter.providerCode() + " 的理由必须点名自己")
          .contains(adapter.providerLabel());
    }
  }

  @Test
  void 未接入时五件职责全部答未接入且不返回任何成功值() {
    var context =
        new PaymentProviderPort.ProviderContext(
            "PAYPAL", "https://example.invalid", "secret://x", 15, 3, "bearer");
    for (var adapter : ADAPTERS) {
      assertThat(adapter.executePayment(context, COMMAND).outcome())
          .as(adapter.providerCode() + " 付款执行")
          .isEqualTo(PaymentAttemptOutcome.NOT_CONNECTED);
      assertThat(adapter.executePayment(context, COMMAND).providerSerialNo())
          .as(adapter.providerCode() + " 绝不能返回服务商流水号")
          .isEmpty();
      assertThat(adapter.executePayment(context, COMMAND).paidAmount()).isNull();

      assertThat(adapter.queryPayment(context, "idem-1").outcome())
          .as(adapter.providerCode() + " 查证")
          .isEqualTo(PaymentAttemptOutcome.NOT_CONNECTED);
      assertThat(adapter.registerReceipt(context, new PaymentProviderPort.ReceiptCommand(
              "idem-1", "RC-1", "USD", new java.math.BigDecimal("1.00"), null, null, null,
              "客户", "", "", "")).outcome())
          .as(adapter.providerCode() + " 收款登记")
          .isEqualTo(PaymentAttemptOutcome.NOT_CONNECTED);
      assertThat(adapter.queryBalance(context, new PaymentProviderPort.BalanceQuery("USD")).outcome())
          .as(adapter.providerCode() + " 余额查询")
          .isEqualTo(PaymentAttemptOutcome.NOT_CONNECTED);
      assertThat(adapter.quoteRate(
              context, new PaymentProviderPort.RateCommand("USD", "CNY", null)).outcome())
          .as(adapter.providerCode() + " 汇率报价")
          .isEqualTo(PaymentAttemptOutcome.NOT_CONNECTED);
      var fees = adapter.fetchFeeDetails(context, new PaymentProviderPort.FeeQuery("USD", null, null));
      assertThat(fees.outcome()).as(adapter.providerCode() + " 手续费明细").isEqualTo(PaymentAttemptOutcome.NOT_CONNECTED);
      assertThat(fees.fees()).as(adapter.providerCode() + " 绝不能返回假的手续费行").isEmpty();
    }
  }

  @Test
  void 注册表按服务商代码解析且五个服务商都在() {
    var registry = new PaymentProviderRegistry(List.of());
    for (var adapter : ADAPTERS) {
      var found = registry.find(adapter.providerCode());
      assertThat(found).as("注册表必须能按代码找到 " + adapter.providerCode()).isNotNull();
      assertThat(found.providerCode()).isEqualTo(adapter.providerCode());
      assertThat(found.connected()).isFalse();
    }
    assertThat(registry.find("NOT_A_PROVIDER")).isNull();
    assertThat(registry.all()).hasSize(5);
  }

  @Test
  void 注册表让外部适配器覆盖内置的未接入实现() {
    var replacement =
        new PaymentProviderPort() {
          @Override
          public String providerCode() {
            return "PAYPAL";
          }

          @Override
          public String providerLabel() {
            return "PayPal（已接入）";
          }

          @Override
          public boolean connected() {
            return true;
          }

          @Override
          public String unavailableReason() {
            return "";
          }

          @Override
          public PaymentProviderPort.BalanceResult queryBalance(
              PaymentProviderPort.ProviderContext c, PaymentProviderPort.BalanceQuery q) {
            return null;
          }

          @Override
          public PaymentProviderPort.ReceiptResult registerReceipt(
              PaymentProviderPort.ProviderContext c, PaymentProviderPort.ReceiptCommand q) {
            return null;
          }

          @Override
          public PaymentProviderPort.PaymentResult executePayment(
              PaymentProviderPort.ProviderContext c, PaymentProviderPort.PaymentCommand q) {
            return null;
          }

          @Override
          public PaymentProviderPort.RateResult quoteRate(
              PaymentProviderPort.ProviderContext c, PaymentProviderPort.RateCommand q) {
            return null;
          }

          @Override
          public PaymentProviderPort.FeeResult fetchFeeDetails(
              PaymentProviderPort.ProviderContext c, PaymentProviderPort.FeeQuery q) {
            return null;
          }

          @Override
          public PaymentProviderPort.PaymentResult queryPayment(
              PaymentProviderPort.ProviderContext c, String idempotencyKey) {
            return null;
          }
        };
    var registry = new PaymentProviderRegistry(List.of(replacement));
    assertThat(registry.find("PAYPAL").connected()).as("外部适配器必须覆盖内置实现").isTrue();
    assertThat(registry.find("STRIPE").connected()).as("没覆盖的服务商仍答未接入").isFalse();
  }
}
