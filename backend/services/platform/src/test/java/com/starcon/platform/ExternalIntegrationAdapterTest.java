package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;

import com.starcon.platform.ExternalIntegrationPort.ExternalSyncCommand;
import com.starcon.platform.ExternalIntegrationPort.ExternalSyncContext;
import com.starcon.platform.ExternalIntegrationPort.ExternalSyncOutcome;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 18.2.3.1「其他对接」的**适配器**单元测试：六个外部系统**逐个**如实答「未接入」。
 *
 * <p>🔴 本测试盯住的是「**没有假成功**」这一条：
 * 每个适配器都必须返回 {@code NOT_CONNECTED}、**零条**处理记录、
 * {@code providerNotified=false}，并且**不带**任何外部系统侧的单号。
 *
 * <p>🔴 另一条是「**逐个**如实答」：六个适配器各自的未接入理由必须点名它自己
 * （「1688 开放平台」与「淘宝开放平台」不是同一句话），且都写明「缺在谁身上」。
 */
class ExternalIntegrationAdapterTest {

  static final List<ExternalIntegrationPort> ADAPTERS =
      List.of(
          new Alibaba1688IntegrationAdapter(),
          new TaobaoIntegrationAdapter(),
          new OverseasWarehouseIntegrationAdapter(),
          new LogisticsCarrierIntegrationAdapter(),
          new CertificationAggregatorIntegrationAdapter(),
          new SelfHostedBiIntegrationAdapter());

  @Test
  void 六个适配器逐个如实答未接入() {
    assertThat(ADAPTERS).hasSize(6);
    for (var adapter : ADAPTERS) {
      assertThat(adapter.connected()).as(adapter.adapterCode() + " 不得声称已接通").isFalse();
      assertThat(adapter.unavailableReason())
          .as(adapter.adapterCode() + " 的未接入理由不能为空")
          .isNotBlank();
      assertThat(adapter.unavailableReason())
          .as(adapter.adapterCode() + " 的未接入理由必须点名它自己")
          .contains(adapter.displayName());
      assertThat(adapter.unavailableReason())
          .as(adapter.adapterCode() + " 的未接入理由必须写明缺在谁身上")
          .contains("18.2.3");
    }
  }

  @Test
  void 适配器代码与类别一一对应且不重复() {
    var codes = ADAPTERS.stream().map(ExternalIntegrationPort::adapterCode).toList();
    assertThat(codes).doesNotHaveDuplicates();
    assertThat(codes)
        .containsExactlyInAnyOrder(
            "ALIBABA_1688",
            "TAOBAO",
            "OVERSEAS_WAREHOUSE",
            "LOGISTICS_CARRIER",
            "CERTIFICATION_AGGREGATOR",
            "SELF_HOSTED_BI");
    assertThat(ADAPTERS.stream().map(ExternalIntegrationPort::category).toList())
        .containsExactlyInAnyOrder(
            "SUPPLY_SOURCE",
            "SUPPLY_SOURCE",
            "OVERSEAS_WAREHOUSE",
            "LOGISTICS_CARRIER",
            "CERTIFICATION_AGGREGATOR",
            "SELF_HOSTED_BI");
  }

  @Test
  void 未接入的适配器不返回任何成功回执() {
    for (var adapter : ADAPTERS) {
      var outcome =
          adapter.executeSync(
              new ExternalSyncContext(
                  adapter.adapterCode(),
                  adapter.category(),
                  "https://example.invalid/api",
                  "secret://external/" + adapter.adapterCode().toLowerCase() + "/main",
                  15,
                  3,
                  "session"),
              new ExternalSyncCommand("CATALOG_SYNC", "PULL", "SHOP-1", ""));
      assertThat(outcome.status())
          .as(adapter.adapterCode() + " 不得返回任何非 NOT_CONNECTED 的结论")
          .isEqualTo("NOT_CONNECTED");
      assertThat(outcome.recordsTotal()).isEqualByComparingTo(BigDecimal.ZERO);
      assertThat(outcome.recordsSucceeded()).isEqualByComparingTo(BigDecimal.ZERO);
      assertThat(outcome.recordsFailed()).isEqualByComparingTo(BigDecimal.ZERO);
      assertThat(outcome.providerNotified())
          .as(adapter.adapterCode() + " 未接入 ⇒ 没有与外部系统核对过")
          .isFalse();
      assertThat(outcome.externalRef())
          .as(adapter.adapterCode() + " 未接入 ⇒ 不得凭空造一个外部系统侧的单号")
          .isEmpty();
      assertThat(outcome.failureClass()).isEqualTo("NOT_CONFIGURED");
      assertThat(outcome.failureReason()).isNotBlank();
      assertThat(outcome.providerNotice()).isNotBlank();
    }
  }

  @Test
  void 注册表按代码分发且内置实现全部未接入() {
    var registry = new ExternalIntegrationRegistry(List.of());
    assertThat(registry.all()).hasSize(6);
    for (var adapter : registry.all()) {
      assertThat(registry.find(adapter.adapterCode())).isNotNull();
      assertThat(registry.find(adapter.adapterCode()).connected()).isFalse();
    }
    assertThat(registry.find("NOT_A_REAL_ADAPTER")).isNull();
    assertThat(registry.find(null)).isNull();
    assertThat(registry.find("  ")).isNull();
  }
}
