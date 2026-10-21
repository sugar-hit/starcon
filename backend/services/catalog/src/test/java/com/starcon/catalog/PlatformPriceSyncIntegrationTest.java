package com.starcon.catalog;

import static com.starcon.catalog.PlatformPriceSyncModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.catalog.PlatformPriceSyncModels.PlatformPriceReceiptRequest;
import com.starcon.catalog.PlatformPriceSyncModels.PlatformPriceRuleInput;
import com.starcon.catalog.PlatformPriceSyncModels.PlatformPriceRuleType;
import com.starcon.catalog.PlatformPriceSyncModels.PlatformPriceSyncOutcome;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.4.2.1 平台价格同步的**功能类**集成测试。
 *
 * <p>本模块存在的全部理由是蓝图那句加粗句：「**平台可能拒绝或调整价格**（如低于最低价触发），
 * 差异必须显性反馈，不能假设回写即成功。」 所以测试围绕三件事：
 *
 * <ol>
 *   <li><b>差异是事实</b>：期望价、平台承接价、平台实际价、差额都落库、可查、可算；</li>
 *   <li><b>拒绝是真的被拦下</b>：最低价触发时不发平台调用，但原因显性回报给上游；</li>
 *   <li><b>幂等是真的</b>：同一把幂等键重复投递不产生第二条回写、第二个 02 队列任务。</li>
 * </ol>
 *
 * <p>全部跑在真实 PostgreSQL 16 容器上（RLS 强制开启），渠道那跳是替身。
 */
class PlatformPriceSyncIntegrationTest extends PlatformPriceSyncFixture {

  @BeforeEach
  void reset() {
    resetQueue();
    EVALUATION.set(Evaluation.EXEMPT);
  }

  // ================================================================ 能力声明与规则

  @Test
  void 能力声明把三种规则类型与上下游队列说清楚() {
    var caps = tx(actor, () -> sync.capabilities());
    assertThat(caps.module()).isEqualTo("5.4.2.1");
    assertThat(caps.ruleTypes()).containsExactly("MIN_PRICE", "AUTO_COMPARE", "MULTI_SITE_LINK");
    assertThat(caps.ruleTypeSemantics()).hasSize(3);
    assertThat(caps.outcomes()).contains("PENDING", "ACCEPTED", "ADJUSTED", "REJECTED");
    // 队列归属写明是复用 channel 的 02 队列，而不是自造第二套。
    assertThat(caps.queueService()).isEqualTo("channel");
    assertThat(caps.queueResource()).contains("platform_operation");
    assertThat(caps.queueOperation()).isEqualTo("UPDATE_PRICE");
    // 上游的写入所有权写明是 5.4.1.1 的。
    assertThat(caps.upstreamWriteOwner()).contains("5.4.1.1");
  }

  @Test
  void 建规则缺参数一律被拒() {
    // 最低价缺币种。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        sync.createRule(
                            new PlatformPriceRuleInput(
                                PlatformPriceRuleType.MIN_PRICE, "AMAZON", "US", null, null, null,
                                new BigDecimal("50"), null, null, null, null, null, null, null, ""))))
        .isInstanceOf(ResponseStatusException.class);
    // 自动比价缺对比平台。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        sync.createRule(
                            new PlatformPriceRuleInput(
                                PlatformPriceRuleType.AUTO_COMPARE, "AMAZON", "US", null, null, null,
                                null, null, "", "US", new BigDecimal("0.05"), null, null, null, ""))))
        .isInstanceOf(ResponseStatusException.class);
    // 多站点联动缺参照站点。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        sync.createRule(
                            new PlatformPriceRuleInput(
                                PlatformPriceRuleType.MULTI_SITE_LINK, "AMAZON", "US", null, null, null,
                                null, null, null, null, null, "", new BigDecimal("0.9"),
                                new BigDecimal("1.1"), ""))))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 规则列表真筛选真分页() {
    minPriceRule("AMAZON", "US", "50", "USD");
    minPriceRule("SHOPIFY", "US", "60", "USD");
    autoCompareRule("AMAZON", "US", "SHOPIFY", "US", "0.05");

    var all = tx(actor, () -> sync.listRules(null, null, null, "ACTIVE", null, 0, 50));
    assertThat(all.totalElements()).isEqualTo(3L);

    var byType = tx(actor, () -> sync.listRules("MIN_PRICE", null, null, "ACTIVE", null, 0, 50));
    assertThat(byType.totalElements()).isEqualTo(2L);
    assertThat(byType.items()).allMatch(r -> r.ruleType() == PlatformPriceRuleType.MIN_PRICE);

    var byPlatform = tx(actor, () -> sync.listRules(null, "SHOPIFY", null, "ACTIVE", null, 0, 50));
    assertThat(byPlatform.totalElements()).isEqualTo(1L);

    // 真分页：第 2 页与第 1 页不重叠，总数不变。
    var page0 = tx(actor, () -> sync.listRules(null, null, null, "ACTIVE", null, 0, 2));
    var page1 = tx(actor, () -> sync.listRules(null, null, null, "ACTIVE", null, 1, 2));
    assertThat(page0.items()).hasSize(2);
    assertThat(page1.items()).hasSize(1);
    assertThat(page0.totalPages()).isEqualTo(2);
    assertThat(page0.items()).doesNotContainAnyElementsOf(page1.items());
  }

  @Test
  void 同一作用域同类型只能有一条规则() {
    minPriceRule("AMAZON", "US", "50", "USD");
    assertThatThrownBy(() -> minPriceRule("AMAZON", "US", "60", "USD"))
        .isInstanceOf(ResponseStatusException.class);
  }

  // ================================================================ 同步：接受路径

  @Test
  void 同步消费待回写事实并投递到02队列() {
    UUID sku = observedSku("PPS");
    var fact = upstreamFact(sku, "120");

    var run = sync.sync(actor, new PlatformPriceSyncRequest(null, null, sku, null), bearer(actor));

    assertThat(run.scanned()).isEqualTo(1);
    assertThat(run.dispatched()).isEqualTo(1);
    assertThat(run.rejected()).isZero();
    assertThat(run.items()).hasSize(1);

    // 02 队列真的收到一次投递（不是"假装派发"）。
    assertThat(enqueueCount.get()).isEqualTo(1);
    var payload = enqueued.get(0);
    assertThat(payload.get("operation")).isEqualTo("UPDATE_PRICE");
    assertThat(payload.get("idempotencyKey")).isEqualTo(fact.idempotencyKey());
    assertThat(payload.get("shopId")).isEqualTo(SHOP_ID.toString());
    assertThat(payload.get("payload").toString()).contains("\"price\":120.0000");

    // 结果落在 PENDING（**不是**成功 —— 没收到平台回执就不许说成功）。
    var record = run.items().get(0);
    assertThat(record.outcome()).isEqualTo(PlatformPriceSyncOutcome.PENDING);
    assertThat(record.expectedPrice()).isEqualByComparingTo("120.0000");
    assertThat(record.actualPrice()).isNull();
    assertThat(record.priceDelta()).isNull();
    assertThat(record.platformOperationId()).isNotNull();

    // 差异台账：规则判定没有差异 ⇒ MATCHED。
    var diffs = tx(actor, () -> sync.listDiffs(null, null, null, sku, null, 0, 20));
    assertThat(diffs.totalElements()).isEqualTo(1L);
    assertThat(diffs.items().get(0).outcome().name()).isEqualTo("MATCHED");
    assertThat(diffs.items().get(0).source().name()).isEqualTo("RULE_EVALUATION");
  }

  @Test
  void 同一幂等键重复投递不产生第二条回写() {
    UUID sku = observedSku("PPS");
    upstreamFact(sku, "120");

    var first = sync.sync(actor, new PlatformPriceSyncRequest(null, null, sku, null), bearer(actor));
    assertThat(first.dispatched()).isEqualTo(1);
    assertThat(enqueueCount.get()).isEqualTo(1);

    // 第二轮：上游事实还没被回执掉，仍是 PENDING ⇒ 会被再看见一次。
    var second = sync.sync(actor, new PlatformPriceSyncRequest(null, null, sku, null), bearer(actor));
    assertThat(second.scanned()).isEqualTo(1);
    // 🔴 关键断言：**没有第二条回写、没有第二个 02 队列任务**。
    assertThat(second.skipped()).isEqualTo(1);
    assertThat(second.dispatched()).isZero();
    assertThat(enqueueCount.get()).isEqualTo(1);

    var records = tx(actor, () -> sync.listRecords(null, null, null, sku, 0, 50));
    assertThat(records.totalElements()).isEqualTo(1L);
  }

  // ================================================================ 同步：最低价触发（拒绝）

  @Test
  void 最低价触发被拦下且原因显性反馈给上游() {
    UUID sku = observedSku("PPS");
    var fact = upstreamFact(sku, "80");
    minPriceRule("AMAZON", "US", "100", "USD");

    var run = sync.sync(actor, new PlatformPriceSyncRequest(null, null, sku, null), bearer(actor));

    assertThat(run.rejected()).isEqualTo(1);
    // 🔴 平台根本不收这个价 ⇒ 一次平台调用都不该发出去。
    assertThat(enqueueCount.get()).isZero();

    var record = run.items().get(0);
    assertThat(record.outcome()).isEqualTo(PlatformPriceSyncOutcome.REJECTED);
    assertThat(record.actualPrice()).isNull();
    assertThat(record.priceDelta()).isNull();
    assertThat(record.ruleReason()).contains("最低价");
    assertThat(record.ruleReason()).contains("100");

    // 差异台账里有被拒这一条（可查、可算）。
    var diffs = tx(actor, () -> sync.listDiffs(null, null, null, sku, null, 0, 20));
    assertThat(diffs.totalElements()).isEqualTo(1L);
    assertThat(diffs.items().get(0).outcome().name()).isEqualTo("REJECTED");
    assertThat(diffs.items().get(0).reasonCode()).isEqualTo("BELOW_MIN_PRICE");

    // 上游事实被结掉（5.4.1.1 的 ack 端点被调用了）。
    var writebacks = tx(actor, () -> adjustments.listWritebacks(null, sku, "AMAZON", "US", 0, 20));
    assertThat(writebacks.items().get(0).status()).isEqualTo("ACKNOWLEDGED");
    assertThat(writebacks.items().get(0).ackNote()).contains("拒收");
    assertThat(writebacks.items().get(0).id()).isEqualTo(fact.writebackId());
  }

  @Test
  void 最低价规则币种不同不参与判定也不折算() {
    UUID sku = observedSku("PPS");
    upstreamFact(sku, "80");
    // 规则是 EUR，本次回写是 USD ⇒ 不可比（绝不按 1:1 折算）。
    minPriceRule("AMAZON", "US", "100", "EUR");

    var run = sync.sync(actor, new PlatformPriceSyncRequest(null, null, sku, null), bearer(actor));

    // 不判成拒收（判不出来 ≠ 判成通过，也不等于判成拒收）。
    assertThat(run.rejected()).isZero();
    assertThat(run.dispatched()).isEqualTo(1);
    var record = run.items().get(0);
    assertThat(record.outcome()).isEqualTo(PlatformPriceSyncOutcome.PENDING);
    // 但**为什么没参与**必须看得见。
    assertThat(record.ruleReason()).contains("币种");
  }

  // ================================================================ 同步：自动比价 / 多站点联动（改价）

  @Test
  void 自动比价会被平台压价且差异显性记录() {
    UUID sku = observedSku("PPS");
    // 对比平台（SHOPIFY/US）的平台在售价 100 USD。
    priceObservation(actor, sku, "SHOPIFY", "US", "USD", null, "100", OffsetDateTime.now());
    upstreamFact(sku, "120");
    autoCompareRule("AMAZON", "US", "SHOPIFY", "US", "0.05");

    var run = sync.sync(actor, new PlatformPriceSyncRequest(null, null, sku, null), bearer(actor));

    var record = run.items().get(0);
    assertThat(record.ruleOutcome().name()).isEqualTo("ADJUSTED");
    // 平台承接价 = 对比价 100（期望 120，平台会压到 100）。
    assertThat(record.predictedPrice()).isEqualByComparingTo("100.0000");
    assertThat(record.expectedPrice()).isEqualByComparingTo("120.0000");

    // 差异台账：差额 -20 显性可查。
    var diffs = tx(actor, () -> sync.listDiffs(null, null, null, sku, null, 0, 20));
    assertThat(diffs.totalElements()).isEqualTo(1L);
    var diff = diffs.items().get(0);
    assertThat(diff.outcome().name()).isEqualTo("ADJUSTED");
    assertThat(diff.actualPrice()).isEqualByComparingTo("100.0000");
    assertThat(diff.priceDelta()).isEqualByComparingTo("-20.0000");
    assertThat(diff.deviationRatio()).isEqualByComparingTo("0.166667");
  }

  @Test
  void 多站点联动越界会被平台拉回带边界() {
    UUID sku = observedSku("PPS");
    // 参照站点 AMAZON/DE 的平台在售价 100 USD ⇒ 联动带 [90, 110]。
    priceObservation(actor, sku, "AMAZON", "DE", "USD", null, "100", OffsetDateTime.now());
    upstreamFact(sku, "120");
    multiSiteLinkRule("AMAZON", "US", "DE", "0.9", "1.1");

    var run = sync.sync(actor, new PlatformPriceSyncRequest(null, null, sku, null), bearer(actor));

    var record = run.items().get(0);
    assertThat(record.ruleOutcome().name()).isEqualTo("ADJUSTED");
    // 120 越出上限 110 ⇒ 平台拉到 110。
    assertThat(record.predictedPrice()).isEqualByComparingTo("110.0000");

    var diffs = tx(actor, () -> sync.listDiffs(null, null, null, sku, null, 0, 20));
    assertThat(diffs.items().get(0).priceDelta()).isEqualByComparingTo("-10.0000");
  }

  @Test
  void 取不到对比价时规则不参与判定且写明原因() {
    UUID sku = observedSku("PPS");
    upstreamFact(sku, "120");
    // 对比平台没有任何价格观测。
    autoCompareRule("AMAZON", "US", "SHOPIFY", "US", "0.05");

    var run = sync.sync(actor, new PlatformPriceSyncRequest(null, null, sku, null), bearer(actor));

    var record = run.items().get(0);
    assertThat(record.ruleOutcome().name()).isEqualTo("ACCEPTED");
    assertThat(record.ruleReason()).contains("对比价");
  }

  // ================================================================ 平台回执

  @Test
  void 平台按期望价收下则实际价等于期望价且上游事实结掉() {
    UUID sku = observedSku("PPS");
    upstreamFact(sku, "120");
    var run = sync.sync(actor, new PlatformPriceSyncRequest(null, null, sku, null), bearer(actor));
    UUID recordId = run.items().get(0).id();

    var detail =
        write(
            actor,
            () ->
                sync.receipt(
                    recordId,
                    new PlatformPriceReceiptRequest(
                        PlatformPriceSyncOutcome.ACCEPTED, new BigDecimal("120.0000"), "REQ-1", "平台已受理"),
                    bearer(actor)));

    assertThat(detail.record().outcome()).isEqualTo(PlatformPriceSyncOutcome.ACCEPTED);
    assertThat(detail.record().actualPrice()).isEqualByComparingTo("120.0000");
    assertThat(detail.record().priceDelta()).isEqualByComparingTo("0.0000");
    assertThat(detail.record().platformRequestId()).isEqualTo("REQ-1");

    // 差异台账：两条（规则判定 + 平台回执），只追加。
    assertThat(detail.diffs()).hasSize(2);
    assertThat(detail.diffs().get(1).source().name()).isEqualTo("PLATFORM_RECEIPT");
    assertThat(detail.diffs().get(1).outcome().name()).isEqualTo("MATCHED");

    // 02 队列收到成功回执。
    assertThat(completed).anyMatch(p -> p.endsWith("/succeed"));

    // 上游事实结掉。
    var writebacks = tx(actor, () -> adjustments.listWritebacks(null, sku, "AMAZON", "US", 0, 20));
    assertThat(writebacks.items().get(0).status()).isEqualTo("ACKNOWLEDGED");
    assertThat(writebacks.items().get(0).ackNote()).contains("120");
  }

  @Test
  void 平台改价则实际价与差额显性可查() {
    UUID sku = observedSku("PPS");
    upstreamFact(sku, "120");
    var run = sync.sync(actor, new PlatformPriceSyncRequest(null, null, sku, null), bearer(actor));
    UUID recordId = run.items().get(0).id();

    var detail =
        write(
            actor,
            () ->
                sync.receipt(
                    recordId,
                    new PlatformPriceReceiptRequest(
                        PlatformPriceSyncOutcome.ADJUSTED, new BigDecimal("118.0000"), "REQ-2", "平台压价"),
                    bearer(actor)));

    assertThat(detail.record().outcome()).isEqualTo(PlatformPriceSyncOutcome.ADJUSTED);
    assertThat(detail.record().actualPrice()).isEqualByComparingTo("118.0000");
    assertThat(detail.record().priceDelta()).isEqualByComparingTo("-2.0000");

    var diffs = tx(actor, () -> sync.listDiffs(null, null, null, sku, null, 0, 20));
    assertThat(diffs.totalElements()).isEqualTo(2L);
    var platformDiff =
        diffs.items().stream()
            .filter(d -> d.source().name().equals("PLATFORM_RECEIPT"))
            .findFirst()
            .orElseThrow();
    assertThat(platformDiff.outcome().name()).isEqualTo("ADJUSTED");
    assertThat(platformDiff.priceDelta()).isEqualByComparingTo("-2.0000");
    assertThat(platformDiff.deviationRatio()).isEqualByComparingTo("0.016667");

    // 上游事实结掉，摘要里带差额。
    var writebacks = tx(actor, () -> adjustments.listWritebacks(null, sku, "AMAZON", "US", 0, 20));
    assertThat(writebacks.items().get(0).status()).isEqualTo("ACKNOWLEDGED");
    assertThat(writebacks.items().get(0).ackNote()).contains("118");
  }

  @Test
  void 平台拒收则没有实际价且上游事实结掉() {
    UUID sku = observedSku("PPS");
    upstreamFact(sku, "120");
    var run = sync.sync(actor, new PlatformPriceSyncRequest(null, null, sku, null), bearer(actor));
    UUID recordId = run.items().get(0).id();

    var detail =
        write(
            actor,
            () ->
                sync.receipt(
                    recordId,
                    new PlatformPriceReceiptRequest(
                        PlatformPriceSyncOutcome.REJECTED, null, "REQ-3", "平台侧商品已下架"),
                    bearer(actor)));

    assertThat(detail.record().outcome()).isEqualTo(PlatformPriceSyncOutcome.REJECTED);
    assertThat(detail.record().actualPrice()).isNull();
    assertThat(detail.record().priceDelta()).isNull();

    var diffs = tx(actor, () -> sync.listDiffs(null, null, null, sku, null, 0, 20));
    var platformDiff =
        diffs.items().stream()
            .filter(d -> d.source().name().equals("PLATFORM_RECEIPT"))
            .findFirst()
            .orElseThrow();
    assertThat(platformDiff.outcome().name()).isEqualTo("REJECTED");
    assertThat(platformDiff.actualPrice()).isNull();

    // 02 队列收到失败回执。
    assertThat(completed).anyMatch(p -> p.endsWith("/fail"));

    var writebacks = tx(actor, () -> adjustments.listWritebacks(null, sku, "AMAZON", "US", 0, 20));
    assertThat(writebacks.items().get(0).status()).isEqualTo("ACKNOWLEDGED");
  }

  @Test
  void 回执不能写待定也不能对终态再收() {
    UUID sku = observedSku("PPS");
    upstreamFact(sku, "120");
    var run = sync.sync(actor, new PlatformPriceSyncRequest(null, null, sku, null), bearer(actor));
    UUID recordId = run.items().get(0).id();

    // PENDING 不是结论。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        sync.receipt(
                            recordId,
                            new PlatformPriceReceiptRequest(PlatformPriceSyncOutcome.PENDING, null, null, null),
                            bearer(actor))))
        .isInstanceOf(ResponseStatusException.class);

    // 先收一次（成功），再收第二次 ⇒ 拒绝。
    write(
        actor,
        () ->
            sync.receipt(
                recordId,
                new PlatformPriceReceiptRequest(
                    PlatformPriceSyncOutcome.ACCEPTED, new BigDecimal("120.0000"), "REQ-1", "ok"),
                bearer(actor)));
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        sync.receipt(
                            recordId,
                            new PlatformPriceReceiptRequest(
                                PlatformPriceSyncOutcome.ADJUSTED, new BigDecimal("1.0000"), "REQ-2", "再收一次"),
                            bearer(actor))))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 回执的价与结局不自洽时被拒() {
    UUID sku = observedSku("PPS");
    upstreamFact(sku, "120");
    var run = sync.sync(actor, new PlatformPriceSyncRequest(null, null, sku, null), bearer(actor));
    UUID recordId = run.items().get(0).id();

    // 说"按期望价收下"却给了另一个价 ⇒ 拒绝（否则差异就被抹掉了）。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        sync.receipt(
                            recordId,
                            new PlatformPriceReceiptRequest(
                                PlatformPriceSyncOutcome.ACCEPTED, new BigDecimal("99.0000"), "REQ-1", "对不上"),
                            bearer(actor))))
        .isInstanceOf(ResponseStatusException.class);
  }

  // ================================================================ 失败与重试

  @Test
  void 渠道不可达时不假装成功且事实仍待回写() {
    UUID sku = observedSku("PPS");
    upstreamFact(sku, "120");
    queueDown.set(true);

    var run = sync.sync(actor, new PlatformPriceSyncRequest(null, null, sku, null), bearer(actor));

    assertThat(run.failed()).isEqualTo(1);
    assertThat(run.dispatched()).isZero();
    var record = run.items().get(0);
    assertThat(record.outcome()).isEqualTo(PlatformPriceSyncOutcome.FAILED);
    assertThat(record.lastError()).isNotBlank();

    // 🔴 失败**不**结掉上游事实：它还得留在 PENDING 里等下一轮重试。
    var writebacks = tx(actor, () -> adjustments.listWritebacks(null, sku, "AMAZON", "US", 0, 20));
    assertThat(writebacks.items().get(0).status()).isEqualTo("PENDING");

    // 渠道恢复后再跑一轮：这次发得出去，且**不产生第二条回写记录**。
    queueDown.set(false);
    var retry = sync.sync(actor, new PlatformPriceSyncRequest(null, null, sku, null), bearer(actor));
    assertThat(retry.dispatched()).isEqualTo(1);
    assertThat(enqueueCount.get()).isEqualTo(1);

    var records = tx(actor, () -> sync.listRecords(null, null, null, sku, 0, 50));
    assertThat(records.totalElements()).isEqualTo(1L);
    assertThat(records.items().get(0).outcome()).isEqualTo(PlatformPriceSyncOutcome.PENDING);
  }

  @Test
  void 没有登记落点时显性拒收而不是凭空造任务() {
    UUID sku = observedSku("PPS");
    upstreamFact(sku, "120");
    shopsEmpty.set(true);

    var run = sync.sync(actor, new PlatformPriceSyncRequest(null, null, sku, null), bearer(actor));

    assertThat(run.rejected()).isEqualTo(1);
    assertThat(enqueueCount.get()).isZero();
    var record = run.items().get(0);
    assertThat(record.outcome()).isEqualTo(PlatformPriceSyncOutcome.REJECTED);
    assertThat(record.ruleReason()).contains("没有已登记的店铺");

    var diffs = tx(actor, () -> sync.listDiffs(null, null, null, sku, null, 0, 20));
    assertThat(diffs.items().get(0).reasonCode()).isEqualTo("NOT_REGISTERED");
  }

  // ================================================================ timestamptz

  @Test
  void timestamptz写回零漂移() {
    UUID sku = observedSku("PPS");
    upstreamFact(sku, "120");
    var run = sync.sync(actor, new PlatformPriceSyncRequest(null, null, sku, null), bearer(actor));
    UUID recordId = run.items().get(0).id();

    var before = OffsetDateTime.now();
    var detail =
        write(
            actor,
            () ->
                sync.receipt(
                    recordId,
                    new PlatformPriceReceiptRequest(
                        PlatformPriceSyncOutcome.ACCEPTED, new BigDecimal("120.0000"), "REQ-1", "ok"),
                    bearer(actor)));
    var after = OffsetDateTime.now();

    // 🔴 断言**写回的值**：不是"没抛异常"，是落库的那一刻就在区间内且与服务层读到的同一绝对时刻。
    var epoch =
        tx(
                actor,
                () ->
                    sql.fetchOne(
                            "select extract(epoch from completed_at) from platform_price_sync where id=?",
                            recordId)
                        .get(0, java.math.BigDecimal.class))
            .longValue();
    assertThat(epoch).isBetween(before.toEpochSecond(), after.toEpochSecond());
    assertThat(detail.record().completedAt().toEpochSecond()).isEqualTo(epoch);
    // 偏移量必须保留（写成 LocalDateTime 再补时区会偏 −28800s）。
    assertThat(detail.record().completedAt().getOffset()).isEqualTo(OffsetDateTime.now().getOffset());
  }

  // ================================================================ HTTP 与权限

  @Test
  void HTTP端点通() throws Exception {
    UUID sku = observedSku("PPS");
    upstreamFact(sku, "80");
    minPriceRule("AMAZON", "US", "100", "USD");

    http.perform(get("/catalog/price-sync/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ruleTypes").isArray());
    http.perform(get("/catalog/price-sync/rules").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").isNumber());
    http.perform(get("/catalog/price-sync/records").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());
    http.perform(get("/catalog/price-sync/diffs").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());

    http.perform(
            post("/catalog/price-sync/sync")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(JSON.writeValueAsString(new PlatformPriceSyncRequest(null, null, sku, null))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rejected").value(1));

    http.perform(get("/catalog/price-sync/records/" + sku).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
  }

  @Test
  void 只读角色不能同步与建规则() throws Exception {
    http.perform(
            post("/catalog/price-sync/sync")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    // ⚠️ 请求体必须合法：body 校验先于权限校验发生，
    //    用 "{}" 会先吃一个 400，测的就不是权限了。
    var validRule =
        JSON.writeValueAsString(
            new PlatformPriceRuleInput(
                PlatformPriceRuleType.MIN_PRICE, "AMAZON", "US", null, null, null,
                new BigDecimal("50"), "USD", null, null, null, null, null, null, "只读角色不该建得了"));
    http.perform(
            post("/catalog/price-sync/rules")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(validRule))
        .andExpect(status().isForbidden());
  }

  @Test
  void 未认证的调用被拒() throws Exception {
    http.perform(get("/catalog/price-sync/records")).andExpect(status().isUnauthorized());
  }
}
