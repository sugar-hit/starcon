package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.trade.SourceAlternativeModels.SourceAlternativeCreateRunInput;
import com.starcon.trade.SourceAlternativeModels.SourceAlternativeDecisionInput;
import com.starcon.trade.SourceAlternativeModels.SourceAlternativePolicyInput;
import com.starcon.trade.SourceAlternativeModels.SourceAlternativePurchaseRequestInput;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 6.1.2.2 同款货源推荐的集成测试。
 *
 * <p>跑的是**真实 PostgreSQL 16 + Testcontainers**，上游事实（货源池 / 评级 / 采购历史）
 * 直接种进它们自己的表。
 *
 * <p>本模块最要紧的三件事，测试里逐条钉住：
 *
 * <ol>
 *   <li><b>同款判定给置信度</b>：确证（人工关联）= 1.0000；历史供应商 = 0.5000 起；
 *       疑似同款 = 0.3000 / 0.4000。**没有证据的货源不进候选**。</li>
 *   <li><b>弱匹配必须人工确认才能生成采购申请</b>（未确认 ⇒ 拒绝；确认 ⇒ 真的写单）。</li>
 *   <li><b>快照价只是参考</b>：生成的申请行**不填预估单价**，参考价区间写进备注。</li>
 * </ol>
 */
class SourceAlternativeIntegrationTest extends SourceAlternativeFixture {

  @Autowired SourceAlternativeService alternatives;

  @BeforeEach
  void clean() throws Exception {
    truncateAll();
  }

  private static final OffsetDateTime COLLECTED_AT =
      OffsetDateTime.of(2026, 10, 21, 12, 34, 56, 0, ZoneOffset.ofHours(8));

  /** 货源池 + 评级 + 采购历史（租户 A 的完整场景）。 */
  private void seedFullScenario(UUID tenant) throws Exception {
    seedCatalogSku(skuA, "SKU-AAA", "加厚棉质手套");
    seedCatalogSku(skuB, "SKU-BBB", "别的商品");

    // 1) 确证：已人工确认关联到 skuA 的货源（供应商 A）。
    UUID stallA = seedStall(tenant, "STALL-A", "ALIBABA_1688", "SHOP-A", "档口 A", supplierA);
    UUID itemA = seedItem(tenant, stallA, "ITEM-A");
    seedSnapshot(
        tenant, itemA, 1, "加厚棉质手套 冬季款", "L", "10.0000", "15.0000", "CNY", "100",
        "https://img.example/item-a.jpg", true, COLLECTED_AT);
    seedSkuLink(tenant, itemA, skuA, "SKU-AAA", "加厚棉质手套");

    // 2) 历史供应商：供应商 B 买过 skuA，但没有 SKU 关联 ⇒ 强线索，仍需人工确认。
    UUID stallB = seedStall(tenant, "STALL-B", "YIWU_GOU", "SHOP-B", "档口 B", supplierB);
    UUID itemB = seedItem(tenant, stallB, "ITEM-B");
    seedSnapshot(
        tenant, itemB, 1, "加厚棉质手套 冬季款", "L", "8.0000", "12.0000", "CNY", "50",
        "https://img.example/item-b.jpg", true, COLLECTED_AT);
    seedPurchaseOrder(
        tenant, "PO-HIST-1", supplierB, "SUP-B", "供应商 B", skuA, "SKU-AAA", "100", "9",
        LocalDate.of(2026, 10, 30), OffsetDateTime.of(2026, 10, 1, 8, 0, 0, 0, ZoneOffset.UTC));
    seedPurchaseOrder(
        tenant, "PO-HIST-2", supplierB, "SUP-B", "供应商 B", skuA, "SKU-AAA", "200", "9",
        LocalDate.of(2026, 10, 25), OffsetDateTime.of(2026, 10, 5, 8, 0, 0, 0, ZoneOffset.UTC));

    // 3) 疑似同款：标题与规格与已关联货源完全一致，但没有任何人工关联。
    UUID stallC = seedStall(tenant, "STALL-C", "ALIBABA_1688", "SHOP-C", "档口 C", supplierA);
    UUID itemC = seedItem(tenant, stallC, "ITEM-C");
    seedSnapshot(
        tenant, itemC, 1, "加厚棉质手套 冬季款", "L", "9.0000", "14.0000", "CNY", "20",
        "https://img.example/item-c.jpg", true, COLLECTED_AT);

    // 4) 无任何证据的货源：既没关联、不是历史供应商、标题也不同 ⇒ 不是候选。
    UUID stallD = seedStall(tenant, "STALL-D", "ALIBABA_1688", "SHOP-D", "档口 D", supplierA);
    UUID itemD = seedItem(tenant, stallD, "ITEM-D");
    seedSnapshot(
        tenant, itemD, 1, "完全不同的商品 塑料外壳", "XL", "3.0000", "4.0000", "CNY", "10",
        "https://img.example/item-d.jpg", true, COLLECTED_AT);

    // 5) 下架的货源：同款但已下架 ⇒ 不作为候选（没有可采购的事实）。
    UUID stallE = seedStall(tenant, "STALL-E", "ALIBABA_1688", "SHOP-E", "档口 E", supplierA);
    UUID itemE = seedItem(tenant, stallE, "ITEM-E");
    seedSnapshot(
        tenant, itemE, 1, "加厚棉质手套 冬季款", "L", "6.0000", "7.0000", "CNY", "10",
        "https://img.example/item-e.jpg", false, COLLECTED_AT);

    // 评级：供应商 A 客观分 80（无人工调整）；供应商 B 客观分 70 + 人工 5 = 75（**单独标记**）。
    seedRating(
        tenant, supplierA, "SUP-A", "供应商 A", "80.0000", "0.0000", "80.0000", "A", false,
        LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
    seedRating(
        tenant, supplierB, "SUP-B", "供应商 B", "70.0000", "5.0000", "75.0000", "B", true,
        LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
  }

  private SourceAlternativeModels.SourceAlternativeRunDetail runNow(UUID tenant) {
    return asWrite(
        "Bearer tenant-a",
        () ->
            alternatives.createRun(
                new SourceAlternativeCreateRunInput(skuA, "STOCKOUT", new BigDecimal("500"), "缺货了"),
                userA,
                "Bearer tenant-a"));
  }

  // ==========================================================================
  // 同款判定与置信度
  // ==========================================================================

  @Test
  void 三种证据分别给出不同置信度且没有证据的货源不进候选() throws Exception {
    seedFullScenario(tenantA);
    var detail = runNow(tenantA);

    assertThat(detail.candidates()).hasSize(3);
    var byType =
        detail.candidates().stream()
            .collect(java.util.stream.Collectors.toMap(c -> c.matchType(), c -> c));

    // 确证：人工关联过的 SKU，置信度 1.0000，**不需要**再人工确认。
    var confirmed = byType.get("SKU_LINK");
    assertThat(confirmed).isNotNull();
    assertThat(confirmed.confidence()).isEqualByComparingTo("1.0000");
    assertThat(confirmed.requiresManualConfirmation()).isFalse();
    assertThat(confirmed.matchBasis()).contains("人工确认");

    // 强线索：历史采购过 2 笔 ⇒ 0.5000 + 0.0500 = 0.5500。
    var history = byType.get("PURCHASE_HISTORY");
    assertThat(history).isNotNull();
    assertThat(history.confidence()).isEqualByComparingTo("0.5500");
    assertThat(history.requiresManualConfirmation()).isTrue();
    assertThat(history.historyOrderCount()).isEqualTo(2);
    assertThat(history.matchBasis()).contains("历史采购过");

    // 弱线索：标题与规格完全一致 ⇒ 0.4000。
    var weak = byType.get("TITLE_SPEC_MATCH");
    assertThat(weak).isNotNull();
    assertThat(weak.confidence()).isEqualByComparingTo("0.4000");
    assertThat(weak.requiresManualConfirmation()).isTrue();
    assertThat(weak.matchBasis()).contains("未经人工确认");

    // 无任何证据的货源与下架货源**不在候选里**。
    assertThat(detail.candidates()).extracting(c -> c.title()).doesNotContain("完全不同的商品 塑料外壳");
    assertThat(detail.candidates()).allMatch(c -> c.onSale());
  }

  @Test
  void 评级客观分与最终分并排给出且人工调整单独标记() throws Exception {
    seedFullScenario(tenantA);
    var detail = runNow(tenantA);
    var history =
        detail.candidates().stream()
            .filter(c -> "PURCHASE_HISTORY".equals(c.matchType()))
            .findFirst()
            .orElseThrow();

    assertThat(history.ratingObjectiveScore()).isEqualByComparingTo("70.0000");
    assertThat(history.ratingFinalScore()).isEqualByComparingTo("75.0000");
    assertThat(history.ratingGrade()).isEqualTo("B");
    assertThat(history.ratingManuallyAdjusted()).isTrue();

    var confirmed =
        detail.candidates().stream()
            .filter(c -> "SKU_LINK".equals(c.matchType()))
            .findFirst()
            .orElseThrow();
    assertThat(confirmed.ratingObjectiveScore()).isEqualByComparingTo("80.0000");
    assertThat(confirmed.ratingManuallyAdjusted()).isFalse();
  }

  @Test
  void 历史交期取自采购单而不是编出来的() throws Exception {
    seedFullScenario(tenantA);
    var detail = runNow(tenantA);
    var history =
        detail.candidates().stream()
            .filter(c -> "PURCHASE_HISTORY".equals(c.matchType()))
            .findFirst()
            .orElseThrow();
    // PO-HIST-1：10-01 建单 → 10-30 承诺 = 29 天；PO-HIST-2：10-05 → 10-25 = 20 天；平均 24.5 天。
    assertThat(history.leadTimeDays()).isEqualByComparingTo("24.50");
    // 没有历史交期的候选是**没有**，不是 0 天。
    var weak =
        detail.candidates().stream()
            .filter(c -> "TITLE_SPEC_MATCH".equals(c.matchType()))
            .findFirst()
            .orElseThrow();
    assertThat(weak.leadTimeDays()).isNull();
  }

  @Test
  void 取的是最新一版快照的价格且采集时刻逐字相等() throws Exception {
    seedCatalogSku(skuA, "SKU-AAA", "加厚棉质手套");
    UUID stall = seedStall(tenantA, "STALL-V", "ALIBABA_1688", "SHOP-V", "档口 V", supplierA);
    UUID item = seedItem(tenantA, stall, "ITEM-V");
    seedSnapshot(
        tenantA, item, 1, "加厚棉质手套 冬季款", "L", "10.0000", "15.0000", "CNY", "100", "",
        true, COLLECTED_AT);
    seedSnapshot(
        tenantA, item, 2, "加厚棉质手套 冬季款", "L", "20.0000", "25.0000", "CNY", "100", "",
        true, COLLECTED_AT);
    seedSkuLink(tenantA, item, skuA, "SKU-AAA", "加厚棉质手套");

    var detail = runNow(tenantA);
    assertThat(detail.candidates()).hasSize(1);
    var candidate = detail.candidates().get(0);
    // 最新一版（snapshot_seq=2）的价，不是第一版。
    assertThat(candidate.priceMin()).isEqualByComparingTo("20.0000");
    assertThat(candidate.priceMax()).isEqualByComparingTo("25.0000");
    // 🔴 采集时刻**逐字相等**（同一刻，不做时区偏移）。
    assertThat(candidate.collectedAt()).isNotNull();
    assertThat(candidate.collectedAt().toInstant()).isEqualTo(COLLECTED_AT.toInstant());
  }

  // ==========================================================================
  // 价格对比
  // ==========================================================================

  @Test
  void 价格对比按币种分组且最低价唯一() throws Exception {
    seedFullScenario(tenantA);
    var detail = runNow(tenantA);
    assertThat(detail.priceComparison()).hasSize(1);
    var comparison = detail.priceComparison().get(0);
    assertThat(comparison.currency()).isEqualTo("CNY");
    assertThat(comparison.lowestPrice()).isEqualByComparingTo("8.0000");
    assertThat(comparison.highestPrice()).isEqualByComparingTo("10.0000");
    assertThat(comparison.candidateCount()).isEqualTo(3);
    // 最低价只有一条。
    var lowest =
        detail.candidates().stream().filter(c -> c.lowestPrice()).findFirst().orElseThrow();
    assertThat(detail.candidates()).filteredOn(c -> c.lowestPrice()).hasSize(1);
    assertThat(lowest.priceMin()).isEqualByComparingTo("8.0000");
  }

  // ==========================================================================
  // 人工判定与一键生成采购申请
  // ==========================================================================

  @Test
  void 弱匹配未确认时不能生成采购申请() throws Exception {
    seedFullScenario(tenantA);
    var detail = runNow(tenantA);
    var weak =
        detail.candidates().stream()
            .filter(c -> c.requiresManualConfirmation())
            .findFirst()
            .orElseThrow();

    assertThatThrownBy(
            () ->
                asWrite(
                    "Bearer tenant-a",
                    () ->
                        alternatives.createPurchaseRequest(
                            weak.id(),
                            new SourceAlternativePurchaseRequestInput(
                                new BigDecimal("100"), warehouseId, legalEntityId, ""),
                            userA,
                            "Bearer tenant-a")))
        .hasMessageContaining("必须先人工确认");

    // 一张单都没建出来。
    assertThat(as("Bearer tenant-a", () -> countRequests(tenantA))).isZero();
  }

  @Test
  void 确认之后真的写出了采购申请且供应商落到行上() throws Exception {
    seedFullScenario(tenantA);
    var detail = runNow(tenantA);
    var weak =
        detail.candidates().stream()
            .filter(c -> c.requiresManualConfirmation())
            .findFirst()
            .orElseThrow();

    var decision =
        asWrite(
            "Bearer tenant-a",
            () ->
                alternatives.decide(
                    weak.id(), new SourceAlternativeDecisionInput("CONFIRMED", "人工核对过图，确认同款"),
                    userA));
    assertThat(decision.decisionType()).isEqualTo("CONFIRMED");

    var result =
        asWrite(
            "Bearer tenant-a",
            () ->
                alternatives.createPurchaseRequest(
                    weak.id(),
                    new SourceAlternativePurchaseRequestInput(
                        new BigDecimal("100"), warehouseId, legalEntityId, ""),
                    userA,
                    "Bearer tenant-a"));

    assertThat(result.purchaseRequestNo()).startsWith("PR-");
    // 真的落库了。
    assertThat(as("Bearer tenant-a", () -> countRequests(tenantA))).isEqualTo(1);
    var row =
        as(
            "Bearer tenant-a",
            () ->
                sql.fetchOne(
                    "select source_type, status, currency_code, estimated_amount, note,"
                        + " target_warehouse_id, legal_entity_id from purchase_request"
                        + " where tenant_id = ?::uuid and id = ?::uuid",
                    tenantA,
                    result.purchaseRequestId()));
    assertThat(row.get("source_type", String.class)).isEqualTo("SUGGESTION");
    assertThat(row.get("status", String.class)).isEqualTo("DRAFT");
    assertThat(row.get("target_warehouse_id", UUID.class)).isEqualTo(warehouseId);
    assertThat(row.get("legal_entity_id", UUID.class)).isEqualTo(legalEntityId);
    // 🔴 快照价只是参考 ⇒ **不填预估单价** ⇒ 金额为空。
    assertThat(row.get("estimated_amount", BigDecimal.class)).isNull();
    // 备注里写清来源与「以采购单为准」。
    assertThat(row.get("note", String.class)).contains("同款货源推荐");
    assertThat(row.get("note", String.class)).contains("实际下单金额以后续采购单为准");

    // 行：SKU 是目标 SKU，供应商是候选的供应商，**没有预估单价**。
    var line =
        as(
            "Bearer tenant-a",
            () ->
                sql.fetchOne(
                    "select sku_id, sku_code, requested_quantity, unit_price, supplier_id,"
                        + " supplier_name, note from purchase_request_line"
                        + " where tenant_id = ?::uuid and request_id = ?::uuid",
                    tenantA,
                    result.purchaseRequestId()));
    assertThat(line.get("sku_id", UUID.class)).isEqualTo(skuA);
    assertThat(line.get("sku_code", String.class)).isEqualTo("SKU-AAA");
    assertThat(line.get("requested_quantity", BigDecimal.class)).isEqualByComparingTo("100");
    assertThat(line.get("unit_price", BigDecimal.class)).isNull();
    assertThat(line.get("supplier_id", UUID.class)).isNotNull();
    assertThat(line.get("supplier_name", String.class)).isNotBlank();
    assertThat(line.get("note", String.class)).contains("货源快照价区间");

    // 判定留痕里记着这次生成的采购申请号。
    var requestDecision =
        as(
            "Bearer tenant-a",
            () ->
                sql.fetchOne(
                    "select decision_type, purchase_request_no from source_alternative_decision"
                        + " where tenant_id = ?::uuid and candidate_id = ?::uuid"
                        + " and decision_type='REQUESTED'",
                    tenantA,
                    weak.id()));
    assertThat(requestDecision.get("purchase_request_no", String.class))
        .isEqualTo(result.purchaseRequestNo());

    // 批次状态变成 REQUESTED。
    var run = as("Bearer tenant-a", () -> alternatives.run(detail.run().id()));
    assertThat(run.run().status()).isEqualTo("REQUESTED");
  }

  @Test
  void 数量低于起订量被拒() throws Exception {
    seedFullScenario(tenantA);
    var detail = runNow(tenantA);
    var candidate =
        detail.candidates().stream()
            .filter(c -> "SKU_LINK".equals(c.matchType()))
            .findFirst()
            .orElseThrow();
    assertThat(candidate.moq()).isEqualByComparingTo("100");

    assertThatThrownBy(
            () ->
                asWrite(
                    "Bearer tenant-a",
                    () ->
                        alternatives.createPurchaseRequest(
                            candidate.id(),
                            new SourceAlternativePurchaseRequestInput(
                                new BigDecimal("50"), warehouseId, legalEntityId, ""),
                            userA,
                            "Bearer tenant-a")))
        .hasMessageContaining("起订量");
    assertThat(as("Bearer tenant-a", () -> countRequests(tenantA))).isZero();
  }

  @Test
  void 没有供应商的货源不能生成采购申请() throws Exception {
    seedCatalogSku(skuA, "SKU-AAA", "加厚棉质手套");
    UUID stall = seedStall(tenantA, "STALL-NO-SUP", "ALIBABA_1688", "SHOP-NS", "没有供应商的档口", null);
    UUID item = seedItem(tenantA, stall, "ITEM-NS");
    seedSnapshot(
        tenantA, item, 1, "加厚棉质手套 冬季款", "L", "10.0000", "15.0000", "CNY", "10", "",
        true, COLLECTED_AT);
    seedSkuLink(tenantA, item, skuA, "SKU-AAA", "加厚棉质手套");

    var detail = runNow(tenantA);
    var candidate = detail.candidates().get(0);
    assertThat(candidate.supplierId()).isNull();
    assertThatThrownBy(
            () ->
                asWrite(
                    "Bearer tenant-a",
                    () ->
                        alternatives.createPurchaseRequest(
                            candidate.id(),
                            new SourceAlternativePurchaseRequestInput(
                                new BigDecimal("100"), warehouseId, legalEntityId, ""),
                            userA,
                            "Bearer tenant-a")))
        .hasMessageContaining("没有关联供应商");
  }

  @Test
  void 否决必须写理由且同一个判定不能重复() throws Exception {
    seedFullScenario(tenantA);
    var detail = runNow(tenantA);
    var weak =
        detail.candidates().stream()
            .filter(c -> c.requiresManualConfirmation())
            .findFirst()
            .orElseThrow();

    assertThatThrownBy(
            () ->
                asWrite(
                    "Bearer tenant-a",
                    () ->
                        alternatives.decide(
                            weak.id(), new SourceAlternativeDecisionInput("REJECTED", "  "), userA)))
        .hasMessageContaining("否决必须写理由");

    asWrite(
        "Bearer tenant-a",
        () ->
            alternatives.decide(
                weak.id(), new SourceAlternativeDecisionInput("REJECTED", "规格对不上"), userA));
    assertThatThrownBy(
            () ->
                asWrite(
                    "Bearer tenant-a",
                    () ->
                        alternatives.decide(
                            weak.id(),
                            new SourceAlternativeDecisionInput("REJECTED", "再否决一次"),
                            userA)))
        .hasMessageContaining("已经有");
  }

  // ==========================================================================
  // 只追加与规则
  // ==========================================================================

  @Test
  void 候选快照与判定事件直接改库被触发器挡下() throws Exception {
    seedFullScenario(tenantA);
    var detail = runNow(tenantA);
    var candidate = detail.candidates().get(0);

    assertThatThrownBy(
            () ->
                asWrite(
                    "Bearer tenant-a",
                    () ->
                        sql.execute(
                            "update source_alternative_candidate set confidence = 0.9999"
                                + " where tenant_id = ?::uuid and id = ?::uuid",
                            tenantA,
                            candidate.id())))
        .hasMessageContaining("不可改写");
    assertThatThrownBy(
            () ->
                asWrite(
                    "Bearer tenant-a",
                    () ->
                        sql.execute(
                            "delete from source_alternative_candidate where tenant_id = ?::uuid"
                                + " and id = ?::uuid",
                            tenantA,
                            candidate.id())))
        .hasMessageContaining("不可改写");
    // 判定事件同样只追加（先留一条判定，再试着改它）。
    asWrite(
        "Bearer tenant-a",
        () ->
            alternatives.decide(
                candidate.id(),
                new SourceAlternativeDecisionInput("CONFIRMED", "确认同款"),
                userA));
    assertThatThrownBy(
            () ->
                asWrite(
                    "Bearer tenant-a",
                    () ->
                        sql.execute(
                            "update source_alternative_decision set reason = '改一下'"
                                + " where tenant_id = ?::uuid",
                            tenantA)))
        .hasMessageContaining("不可改写");
    assertThatThrownBy(
            () ->
                asWrite(
                    "Bearer tenant-a",
                    () ->
                        sql.execute("delete from source_alternative_decision where tenant_id = ?::uuid",
                            tenantA)))
        .hasMessageContaining("不可改写");
  }

  @Test
  void 规则权重和必须等于一且改完立即生效() throws Exception {
    seedFullScenario(tenantA);
    // 权重和不是 1 ⇒ 拒绝。
    assertThatThrownBy(
            () ->
                asWrite(
                    "Bearer tenant-a",
                    () ->
                        alternatives.savePolicy(
                            new SourceAlternativePolicyInput(
                                new BigDecimal("0.3000"),
                                new BigDecimal("0.9000"),
                                true,
                                50,
                                new BigDecimal("0.5000"),
                                new BigDecimal("0.2500"),
                                new BigDecimal("0.2000"),
                                new BigDecimal("0.2500")))))
        .hasMessageContaining("权重之和必须精确等于 1");

    // 提高弱匹配门槛 ⇒ 疑似同款（0.4000）被挡在候选之外。
    var saved =
        asWrite(
            "Bearer tenant-a",
            () ->
                alternatives.savePolicy(
                    new SourceAlternativePolicyInput(
                        new BigDecimal("0.5000"),
                        new BigDecimal("0.9000"),
                        true,
                        50,
                        new BigDecimal("0.3000"),
                        new BigDecimal("0.2500"),
                        new BigDecimal("0.2000"),
                        new BigDecimal("0.2500"))));
    assertThat(saved.configured()).isTrue();
    assertThat(saved.weakMatchMinConfidence()).isEqualByComparingTo("0.5000");

    var detail = runNow(tenantA);
    assertThat(detail.candidates()).extracting(c -> c.matchType()).doesNotContain("TITLE_SPEC_MATCH");
    assertThat(detail.candidates()).extracting(c -> c.matchType()).contains("SKU_LINK");
  }

  // ==========================================================================
  // 预览 / 列表 / 概览 / HTTP 契约
  // ==========================================================================

  @Test
  void 实时预览不落库且与落库口径一致() throws Exception {
    seedFullScenario(tenantA);
    var preview = as("Bearer tenant-a", () -> alternatives.preview(skuA, "Bearer tenant-a"));
    assertThat(preview).hasSize(3);
    assertThat(as("Bearer tenant-a", () -> countRuns(tenantA))).isZero();

    var detail = runNow(tenantA);
    assertThat(detail.candidates()).hasSize(preview.size());
    assertThat(detail.candidates().get(0).confidence())
        .isEqualByComparingTo(preview.get(0).confidence());
  }

  @Test
  void 批次列表真分页三页合起来恰好不重不漏() throws Exception {
    seedFullScenario(tenantA);
    for (int i = 0; i < 7; i++) {
      runNow(tenantA);
    }
    var page1 = as("Bearer tenant-a", () -> alternatives.pageRuns(null, null, null, null, 0, 3));
    var page2 = as("Bearer tenant-a", () -> alternatives.pageRuns(null, null, null, null, 1, 3));
    var page3 = as("Bearer tenant-a", () -> alternatives.pageRuns(null, null, null, null, 2, 3));
    assertThat(page1.total()).isEqualTo(7);
    assertThat(page1.items()).hasSize(3);
    assertThat(page2.items()).hasSize(3);
    assertThat(page3.items()).hasSize(1);
    var ids = new java.util.ArrayList<UUID>();
    page1.items().forEach(r -> ids.add(r.id()));
    page2.items().forEach(r -> ids.add(r.id()));
    page3.items().forEach(r -> ids.add(r.id()));
    assertThat(ids).hasSize(7).doesNotHaveDuplicates();
  }

  @Test
  void 概览统计的是本租户的数据() throws Exception {
    seedFullScenario(tenantA);
    runNow(tenantA);
    var overview = as("Bearer tenant-a", () -> alternatives.overview());
    assertThat(overview.runCount()).isEqualTo(1);
    assertThat(overview.openRunCount()).isEqualTo(1);
    assertThat(overview.candidateCount()).isEqualTo(3);
    assertThat(overview.weakCandidateCount()).isEqualTo(2);
  }

  @Test
  void 能力声明明说图片相似度未交付() {
    var caps = as("Bearer tenant-a", () -> alternatives.capabilities());
    var image = caps.capabilities().stream()
        .filter(c -> c.moduleId().contains("图片"))
        .findFirst()
        .orElseThrow();
    assertThat(image.available()).isFalse();
    assertThat(image.impact()).contains("不做");
    assertThat(caps.notes()).anyMatch(n -> n.contains("置信度"));
  }

  @Test
  void HTTP契约401与403与404() throws Exception {
    seedFullScenario(tenantA);
    // 没登录 ⇒ 401。
    http.perform(get("/trade/source-alternatives/runs")).andExpect(status().isUnauthorized());
    // 只读身份调写端点 ⇒ 403。
    http.perform(post("/trade/source-alternatives/runs")
            .header("Authorization", "Bearer read-only")
            .contentType("application/json")
            .content("{\"skuId\":\"" + skuA + "\",\"reason\":\"STOCKOUT\"}"))
        .andExpect(status().isForbidden());
    // 批次不存在 ⇒ 404。
    http.perform(get("/trade/source-alternatives/runs/" + UUID.randomUUID())
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
  }

  @Test
  void 四张表都开了强制行级安全() throws Exception {
    for (String table :
        List.of(
            "source_alternative_policy",
            "source_alternative_run",
            "source_alternative_candidate",
            "source_alternative_decision")) {
      boolean forced =
          as(
                  "Bearer tenant-a",
                  () ->
                      sql.fetchOne(
                              "select relforcerowsecurity from pg_class where relname = ?::varchar",
                              table)
                          .get(0, Boolean.class))
              .booleanValue();
      assertThat(forced).as(table + " 必须 FORCE ROW LEVEL SECURITY").isTrue();
    }
  }

  private long countRequests(UUID tenant) {
    return sql.fetchOne("select count(*) from purchase_request where tenant_id = ?::uuid", tenant)
        .get(0, Long.class);
  }

  private long countRuns(UUID tenant) {
    return sql.fetchOne("select count(*) from source_alternative_run where tenant_id = ?::uuid", tenant)
        .get(0, Long.class);
  }
}
