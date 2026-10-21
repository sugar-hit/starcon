package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.trade.SourceAlternativeModels.SourceAlternativeCreateRunInput;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 6.1.2.2 同款货源推荐的<b>多租户三断言</b>。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且<b>实跑在本模块自己的四张表上</b>
 * （source_alternative_policy / source_alternative_run / source_alternative_candidate /
 * source_alternative_decision），<b>不是</b>占位探针表。
 *
 * <p><b>本模块尤其不能省隔离</b>：候选里同时有<b>供应商名、价格区间、历史交期、评级分、
 * 内部 SKU 编码与名称</b>，以及人工确认与否 —— 一个租户能看到另一个租户「哪几家供应商在供货、
 * 报价多少、谁在拖交期、我们把哪个 SKU 换成了别家的货」，拿到的是<b>对方的供应链底牌</b>；
 * 端点又大多是<b>按 id 直达</b>的 URL，猜到一个 id 就能读，不需要翻列表。
 *
 * <p><b>连接池固定为 1 条连接</b>（见底座）：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 */
class SourceAlternativeTenancyIntegrationTest extends SourceAlternativeFixture {

  @Autowired SourceAlternativeService alternatives;

  @BeforeEach
  void clean() throws Exception {
    truncateAll();
  }

  private static final OffsetDateTime COLLECTED_AT =
      OffsetDateTime.of(2026, 10, 21, 12, 34, 56, 0, ZoneOffset.ofHours(8));

  /** 给一个租户种一条「SKU-AAA + 供应商 A」的场景。 */
  private void seedScenario(UUID tenant, UUID supplier, UUID sku, String code, String name)
      throws Exception {
    seedCatalogSku(sku, code, name);
    UUID stall = seedStall(tenant, "STALL-" + code, "ALIBABA_1688", "SHOP-" + code, "档口 " + code, supplier);
    UUID item = seedItem(tenant, stall, "ITEM-" + code);
    seedSnapshot(
        tenant, item, 1, name + " 冬季款", "L", "10.0000", "15.0000", "CNY", "10", "", true,
        COLLECTED_AT);
    seedSkuLink(tenant, item, sku, code, name);
  }

  private SourceAlternativeModels.SourceAlternativeRunDetail run(UUID tenant, String bearer, UUID skuId) {
    return asWrite(
        bearer,
        () -> alternatives.createRun(new SourceAlternativeCreateRunInput(skuId, "STOCKOUT", null, ""),
            UUID.randomUUID(), bearer));
  }

  // ==========================================================================
  // 断言一：两租户互不可见
  // ==========================================================================

  @Test
  void 两租户互不可见() throws Exception {
    seedScenario(tenantA, supplierA, skuA, "SKU-AAA", "A 租户的商品");
    seedScenario(tenantB, supplierB, skuB, "SKU-BBB", "B 租户的商品");

    var runA = run(tenantA, "Bearer tenant-a", skuA);
    var runB = run(tenantB, "Bearer tenant-b", skuB);

    var runsA = as("Bearer tenant-a", () -> alternatives.pageRuns(null, null, null, null, 0, 20));
    var runsB = as("Bearer tenant-b", () -> alternatives.pageRuns(null, null, null, null, 0, 20));
    assertThat(runsA.total()).isEqualTo(1);
    assertThat(runsB.total()).isEqualTo(1);
    assertThat(runsA.items().get(0).skuCode()).isEqualTo("SKU-AAA");
    assertThat(runsB.items().get(0).skuCode()).isEqualTo("SKU-BBB");

    // 候选互不可见：A 看不到 B 的供应商名与价格。
    var detailA = as("Bearer tenant-a", () -> alternatives.run(runA.run().id()));
    var detailB = as("Bearer tenant-b", () -> alternatives.run(runB.run().id()));
    assertThat(detailA.run().skuCode()).isEqualTo("SKU-AAA");
    assertThat(detailA.candidates().get(0).supplierName()).isNotEqualTo(detailB.candidates().get(0).supplierName());
    assertThat(detailA.candidates().get(0).supplierName()).doesNotContain("B 租户");

    // ⚠️ **按 id 直达也不能读到别人的**：A 拿着 B 的批次 id 读详情 ⇒ 404。
    assertThatThrownBy(() -> as("Bearer tenant-a", () -> alternatives.run(runB.run().id())))
        .hasMessageContaining("推荐批次不存在");

    // 概览同样互不可见。
    assertThat(as("Bearer tenant-a", () -> alternatives.overview()).runCount()).isEqualTo(1);
    assertThat(as("Bearer tenant-b", () -> alternatives.overview()).runCount()).isEqualTo(1);

    // HTTP 层同样如此。
    http.perform(get("/trade/source-alternatives/runs").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].skuCode").value("SKU-AAA"));
    http.perform(get("/trade/source-alternatives/runs").header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].skuCode").value("SKU-BBB"));
  }

  // ==========================================================================
  // 断言二：伪造 tenant header 无效
  // ==========================================================================

  @Test
  void 伪造租户头无效() throws Exception {
    seedScenario(tenantA, supplierA, skuA, "SKU-AAA", "A 租户的商品");
    seedScenario(tenantB, supplierB, skuB, "SKU-BBB", "B 租户的商品");
    run(tenantA, "Bearer tenant-a", skuA);
    run(tenantB, "Bearer tenant-b", skuB);

    // 带着 **B 的令牌** + **伪造的 A 的 X-Tenant-Id**：读到的必须是 B 的数据。
    String list =
        http.perform(get("/trade/source-alternatives/runs")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", tenantA.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(list).contains("SKU-BBB").doesNotContain("SKU-AAA");

    // 反过来：带着 A 的令牌 + 伪造的 B 的头，读到的仍然是 A 的数据。
    String list2 =
        http.perform(get("/trade/source-alternatives/runs")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", tenantB.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(list2).contains("SKU-AAA").doesNotContain("SKU-BBB");

    // 直接在 RLS 上验证：绑定 A 的租户之后，B 的行在库里**读不出来**。
    assertThat(
            as(
                    "Bearer tenant-a",
                    () ->
                        sql.fetchOne(
                                "select count(*) from source_alternative_run where tenant_id=?::uuid",
                                tenantB)
                            .get(0, Long.class)))
        .as("RLS 之下看不到别的租户的推荐批次")
        .isZero();
    assertThat(
            as(
                    "Bearer tenant-a",
                    () ->
                        sql.fetchOne(
                                "select count(*) from source_alternative_candidate where tenant_id=?::uuid",
                                tenantB)
                            .get(0, Long.class)))
        .as("RLS 之下看不到别的租户的候选")
        .isZero();

    // ⚠️ **即使不带 tenant_id 谓词**也读不到别人的行（证明是真隔离，不是应用层过滤）。
    assertThat(
            as(
                    "Bearer tenant-a",
                    () ->
                        sql.fetchOne("select count(*) from source_alternative_run").get(0, Long.class)))
        .as("不带租户谓词也读不到别人的行")
        .isEqualTo(1L);
  }

  // ==========================================================================
  // 断言三：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 连接池归还后不串租户() throws Exception {
    seedScenario(tenantA, supplierA, skuA, "SKU-AAA", "A 租户的商品");
    seedScenario(tenantB, supplierB, skuB, "SKU-BBB", "B 租户的商品");
    var runA = run(tenantA, "Bearer tenant-a", skuA);
    var runB = run(tenantB, "Bearer tenant-b", skuB);

    // 交替借还：同一张连接反复以两个租户的身份读，读到的永远是**当前绑定**的租户。
    for (int i = 0; i < 3; i++) {
      assertThat(as("Bearer tenant-a", () -> alternatives.pageRuns(null, null, null, null, 0, 20)).items().get(0).skuCode())
          .isEqualTo("SKU-AAA");
      assertThat(as("Bearer tenant-b", () -> alternatives.pageRuns(null, null, null, null, 0, 20)).items().get(0).skuCode())
          .isEqualTo("SKU-BBB");
      // 归还之后再读另一个租户的行：仍然读不到。
      assertThat(as("Bearer tenant-a", () -> alternatives.pageRuns(null, null, null, null, 0, 20)).items())
          .extracting(r -> r.skuCode())
          .containsExactly("SKU-AAA");
      assertThat(as("Bearer tenant-b", () -> alternatives.pageRuns(null, null, null, null, 0, 20)).items())
          .extracting(r -> r.skuCode())
          .containsExactly("SKU-BBB");
    }

    // 写也在交替中完成：两个租户的判定互不污染。
    var candidateA = as("Bearer tenant-a", () -> alternatives.run(runA.run().id())).candidates().get(0);
    var candidateB = as("Bearer tenant-b", () -> alternatives.run(runB.run().id())).candidates().get(0);
    asWrite(
        "Bearer tenant-a",
        () ->
            alternatives.decide(
                candidateA.id(),
                new SourceAlternativeModels.SourceAlternativeDecisionInput("CONFIRMED", "A 租户确认"),
                userA));
    asWrite(
        "Bearer tenant-b",
        () ->
            alternatives.decide(
                candidateB.id(),
                new SourceAlternativeModels.SourceAlternativeDecisionInput("REJECTED", "B 租户否决"),
                userB));

    var detailA = as("Bearer tenant-a", () -> alternatives.run(runA.run().id()));
    var detailB = as("Bearer tenant-b", () -> alternatives.run(runB.run().id()));
    assertThat(detailA.decisions()).hasSize(1);
    assertThat(detailA.decisions().get(0).reason()).contains("A 租户");
    assertThat(detailB.decisions()).hasSize(1);
    assertThat(detailB.decisions().get(0).reason()).contains("B 租户");

    // 策略也是租户级的：A 改了规则，B 的不受影响。
    asWrite(
        "Bearer tenant-a",
        () ->
            alternatives.savePolicy(
                new SourceAlternativeModels.SourceAlternativePolicyInput(
                    new BigDecimal("0.5000"),
                    new BigDecimal("0.9000"),
                    true,
                    10,
                    new BigDecimal("0.3000"),
                    new BigDecimal("0.2500"),
                    new BigDecimal("0.2000"),
                    new BigDecimal("0.2500"))));
    assertThat(as("Bearer tenant-a", () -> alternatives.policy()).weakMatchMinConfidence())
        .isEqualByComparingTo("0.5000");
    assertThat(as("Bearer tenant-b", () -> alternatives.policy()).weakMatchMinConfidence())
        .isEqualByComparingTo("0.3000");
  }
}
