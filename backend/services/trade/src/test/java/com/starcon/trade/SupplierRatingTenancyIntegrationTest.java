package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.trade.SupplierRatingModels.SupplierRatingPeriodType;
import com.starcon.trade.SupplierRatingModels.SupplierRatingQuery;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 6.1.1.3 供应商评级的<b>多租户三断言</b>。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且<b>实跑在本模块自己的四张表上</b>
 * （supplier_rating_result / supplier_rating_adjustment / supplier_rating_change /
 * supplier_rating_weight_policy），<b>不是</b>占位探针表 —— 探针表能证明 RLS 开着，
 * 证明不了<b>本模块的查询本身</b>没漏租户谓词。
 *
 * <p><b>本模块尤其不能省隔离</b>：评级里同时有<b>供应商名、交期达成率、质量合格率、
 * 响应时长、价格竞争力、售后配合度</b>，以及人工调整的<b>理由</b> ——
 * 一个租户能看到另一个租户「哪几家供应商在拖交期、谁的质量最差、我们给谁做过人工扶持
 * 以及扶持的理由」，拿到的是<b>对方的供应链底牌与内部决策理由</b>；
 * 端点又大多是<b>按 id 直达</b>的 URL，猜到一个 id 就能读，不需要翻列表。
 *
 * <p><b>连接池固定为 1 条连接</b>（见底座）：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>每轮读写都新建实体</b>：否则测的是幂等而不是隔离 —— 复用上一轮的记录，
 * 第二轮断言通过只能说明「同一条记录没变」。
 */
class SupplierRatingTenancyIntegrationTest extends SupplierRatingFixture {

  @Autowired SupplierRatingService ratings;

  @BeforeEach
  void clean() throws Exception {
    truncateAll();
  }

  private OffsetDateTime at(int dayOffset, int hour) {
    return monthStart().plusDays(dayOffset).atTime(hour, 0).atOffset(ZoneOffset.UTC);
  }

  private SupplierRatingQuery query() {
    return new SupplierRatingQuery(
        SupplierRatingPeriodType.MONTHLY, monthStart(), null, null, null, "finalScore", true, 0, 20);
  }

  /** 种一家供应商的评级事实（质检单 ⇒ 质量维度可算 ⇒ 有分数可排）。 */
  private void seedSupplier(UUID tenant, UUID supplier, String code, String name) throws Exception {
    UUID template = seedTemplate(tenant);
    // 采购单带供应商编码（质检单只有名称快照，没有编码列）。
    seedOrder(
        tenant, "PO-" + code, supplier, code, name, UUID.randomUUID(), "SKU-" + code, "100", "10",
        "USD", today().plusDays(5), at(1, 9));
    seedInspection(
        tenant, template, supplier, name, UUID.randomUUID(), "SKU-Q-" + code, "100", "100", "0",
        at(3, 10));
  }

  // ==========================================================================
  // 断言一：两租户互不可见
  // ==========================================================================

  @Test
  void 两租户互不可见() throws Exception {
    seedSupplier(tenantA, supplierA, "SUP-A", "供应商 A");
    seedSupplier(tenantB, supplierB, "SUP-B", "供应商 B");

    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));
    asWrite("Bearer tenant-b", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-b"));

    var rowsA = as("Bearer tenant-a", () -> ratings.pageRatings(query()));
    assertThat(rowsA.total()).isEqualTo(1);
    assertThat(rowsA.items().get(0).supplierCode()).isEqualTo("SUP-A");
    assertThat(rowsA.items().get(0).supplierName()).contains("供应商 A");
    assertThat(rowsA.items().get(0).supplierName()).doesNotContain("供应商 B");

    var rowsB = as("Bearer tenant-b", () -> ratings.pageRatings(query()));
    assertThat(rowsB.total()).isEqualTo(1);
    assertThat(rowsB.items().get(0).supplierCode()).isEqualTo("SUP-B");
    assertThat(rowsB.items().get(0).supplierName()).doesNotContain("供应商 A");

    // 概览与优先级同样互不可见。
    var overviewA = as("Bearer tenant-a", () -> ratings.overview(SupplierRatingPeriodType.MONTHLY, monthStart()));
    var overviewB = as("Bearer tenant-b", () -> ratings.overview(SupplierRatingPeriodType.MONTHLY, monthStart()));
    assertThat(overviewA.ratedSupplierCount()).isEqualTo(1);
    assertThat(overviewB.ratedSupplierCount()).isEqualTo(1);

    var prioritiesA = as("Bearer tenant-a", () -> ratings.priorities(SupplierRatingPeriodType.MONTHLY, monthStart()));
    assertThat(prioritiesA.items()).hasSize(1);
    assertThat(prioritiesA.items().get(0).supplierCode()).isEqualTo("SUP-A");

    // 变动历史互不可见。
    var changesA = as("Bearer tenant-a", () -> ratings.changes(null, null, null, 0, 50));
    assertThat(changesA.items()).extracting(c -> c.supplierCode()).doesNotContain("SUP-B");

    // ⚠️ **按 id 直达也不能读到别人的**：A 拿着 B 的结果 id 读详情 ⇒ 404。
    var rowB = as("Bearer tenant-b", () -> ratings.pageRatings(query())).items().get(0);
    assertThatThrownBy(() -> as("Bearer tenant-a", () -> ratings.rating(rowB.id())))
        .hasMessageContaining("评级结果不存在");

    // HTTP 层同样如此。
    http.perform(get("/trade/supplier-ratings/ratings").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].supplierCode").value("SUP-A"));
    http.perform(get("/trade/supplier-ratings/ratings").header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].supplierCode").value("SUP-B"));
  }

  // ==========================================================================
  // 断言二：伪造 tenant header 无效
  // ==========================================================================

  @Test
  void 伪造租户头无效() throws Exception {
    seedSupplier(tenantA, supplierA, "SUP-A", "供应商 A");
    seedSupplier(tenantB, supplierB, "SUP-B", "供应商 B");
    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));
    asWrite("Bearer tenant-b", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-b"));

    // 带着 **B 的令牌** + **伪造的 A 的 X-Tenant-Id**：读到的必须是 B 的数据。
    String list =
        http.perform(get("/trade/supplier-ratings/ratings")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", tenantA.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(list).contains("SUP-B").doesNotContain("SUP-A");

    // 反过来：带着 A 的令牌 + 伪造的 B 的头，读到的仍然是 A 的数据。
    String list2 =
        http.perform(get("/trade/supplier-ratings/ratings")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", tenantB.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(list2).contains("SUP-A").doesNotContain("SUP-B");

    // 直接在 RLS 上验证：绑定 A 的租户之后，B 的行在库里**读不出来**。
    assertThat(
            as(
                    "Bearer tenant-a",
                    () ->
                        sql.fetchOne(
                                "select count(*) from supplier_rating_result where tenant_id=?::uuid",
                                tenantB)
                            .get(0, Long.class)))
        .as("RLS 之下看不到别的租户的评级行")
        .isZero();
    assertThat(
            as(
                    "Bearer tenant-a",
                    () ->
                        sql.fetchOne(
                                "select count(*) from supplier_rating_change where tenant_id=?::uuid",
                                tenantB)
                            .get(0, Long.class)))
        .as("RLS 之下看不到别的租户的变动历史")
        .isZero();
    assertThat(
            as(
                    "Bearer tenant-a",
                    () ->
                        sql.fetchOne(
                                "select count(*) from supplier_rating_weight_policy where tenant_id=?::uuid",
                                tenantB)
                            .get(0, Long.class)))
        .as("RLS 之下看不到别的租户的权重配置")
        .isZero();

    // ⚠️ **即使不带 tenant_id 谓词**也读不到别人的行（证明是真隔离，不是应用层过滤）。
    assertThat(
            as(
                    "Bearer tenant-a",
                    () ->
                        sql.fetchOne("select count(*) from supplier_rating_result").get(0, Long.class)))
        .as("不带租户谓词也读不到别人的行")
        .isEqualTo(1L);
  }

  // ==========================================================================
  // 断言三：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 连接池归还后不串租户() throws Exception {
    seedSupplier(tenantA, supplierA, "SUP-A", "供应商 A");
    seedSupplier(tenantB, supplierB, "SUP-B", "供应商 B");
    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));
    asWrite("Bearer tenant-b", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-b"));

    // 交替借还：同一张连接反复以两个租户的身份读，读到的永远是**当前绑定**的租户。
    for (int i = 0; i < 3; i++) {
      assertThat(as("Bearer tenant-a", () -> ratings.pageRatings(query())).items().get(0).supplierCode())
          .isEqualTo("SUP-A");
      assertThat(as("Bearer tenant-b", () -> ratings.pageRatings(query())).items().get(0).supplierCode())
          .isEqualTo("SUP-B");
      // 归还之后再读另一个租户的行：仍然读不到。
      assertThat(as("Bearer tenant-a", () -> ratings.pageRatings(query())).items())
          .extracting(r -> r.supplierCode())
          .containsExactly("SUP-A");
      assertThat(as("Bearer tenant-b", () -> ratings.pageRatings(query())).items())
          .extracting(r -> r.supplierCode())
          .containsExactly("SUP-B");
    }

    // 写也在交替中完成：两个租户的评级互不污染。
    var rowA = as("Bearer tenant-a", () -> ratings.pageRatings(query())).items().get(0);
    asWrite(
        "Bearer tenant-a",
        () ->
            ratings.adjust(
                rowA.id(),
                new com.starcon.trade.SupplierRatingModels.SupplierRatingAdjustmentInput(
                    new java.math.BigDecimal("5"), "A 租户的扶持理由"),
                userA));
    var rowB = as("Bearer tenant-b", () -> ratings.pageRatings(query())).items().get(0);
    asWrite(
        "Bearer tenant-b",
        () ->
            ratings.adjust(
                rowB.id(),
                new com.starcon.trade.SupplierRatingModels.SupplierRatingAdjustmentInput(
                    new java.math.BigDecimal("-5"), "B 租户的复核理由"),
                userB));

    var detailA = as("Bearer tenant-a", () -> ratings.rating(rowA.id()));
    assertThat(detailA.adjustments()).hasSize(1);
    assertThat(detailA.adjustments().get(0).reason()).contains("A 租户");
    var detailB = as("Bearer tenant-b", () -> ratings.rating(rowB.id()));
    assertThat(detailB.adjustments()).hasSize(1);
    assertThat(detailB.adjustments().get(0).reason()).contains("B 租户");

    // 权重配置也是租户级的：A 改了配置，B 的不受影响。
    asWrite(
        "Bearer tenant-a",
        () ->
            ratings.putPolicy(
                new com.starcon.trade.SupplierRatingModels.SupplierRatingPolicyInput(
                    SupplierRatingPeriodType.MONTHLY,
                    new java.math.BigDecimal("0.40"),
                    new java.math.BigDecimal("0.30"),
                    new java.math.BigDecimal("0.10"),
                    new java.math.BigDecimal("0.10"),
                    new java.math.BigDecimal("0.10"),
                    48, null, null, null, "A 的口径")));
    var policyA = as("Bearer tenant-a", () -> ratings.policy(SupplierRatingPeriodType.MONTHLY));
    var policyB = as("Bearer tenant-b", () -> ratings.policy(SupplierRatingPeriodType.MONTHLY));
    assertThat(policyA.configured()).isTrue();
    assertThat(policyA.deliveryWeight()).isEqualByComparingTo("0.40");
    assertThat(policyB.configured()).isFalse();
    assertThat(policyB.deliveryWeight()).isEqualByComparingTo("0.30");
  }
}
