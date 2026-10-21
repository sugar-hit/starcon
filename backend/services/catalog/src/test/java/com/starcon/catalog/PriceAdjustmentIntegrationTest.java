package com.starcon.catalog;

import static com.starcon.catalog.PriceAdjustmentModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.catalog.PriceAdjustmentModels.PriceAdjustmentExpectedVersionRequest;
import com.starcon.catalog.PriceAdjustmentModels.OrderQuery;
import com.starcon.catalog.PriceAdjustmentModels.PriceAdjustmentDetail;
import com.starcon.catalog.PriceAdjustmentModels.PriceAdjustmentRequest;
import com.starcon.catalog.PriceAdjustmentModels.PriceAdjustmentRejectRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.4.1.1 调价单的**功能**集成测试（真实 PostgreSQL 16 + 真实 HTTP 审批替身）。
 *
 * <p>覆盖蓝图里点名的每一件事：
 * 选择 SKU 与目标店铺、输入新旧价、说明原因、提交审批（若启用）、记录变更前后值、
 * 以及生效后产出「待回写」事实。
 */
class PriceAdjustmentIntegrationTest extends PriceAdjustmentFixture {

  @Autowired PriceAdjustmentService adjustments;
  @Autowired PriceAdjustmentApprovalPort approvals;

  @BeforeEach
  void resetApproval() {
    EVALUATION.set(Evaluation.REQUIRE_APPROVAL);
    identityDown.set(false);
    INSTANCES.clear();
    INSTANCE_SEQ.set(0);
  }

  // ================================================================ 创建

  @Test
  void 创建调价单记录变更前后值且利润来自测算引擎() {
    String category = unique("CAT");
    UUID sku = pricedSku(actor, "PA", category, "BrandPA");
    priceObservation(actor, sku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());

    var detail = write(actor, () -> adjustments.create(actor, request(sku, "120"), bearer(actor)));

    assertThat(detail.status()).isEqualTo(Status.DRAFT);
    assertThat(detail.statusLabel()).isEqualTo("草稿");
    assertThat(detail.version()).isEqualTo(1);
    assertThat(detail.skuId()).isEqualTo(sku);
    assertThat(detail.platformCode()).isEqualTo("AMAZON");
    assertThat(detail.siteCode()).isEqualTo("US");
    assertThat(detail.currency()).isEqualTo("USD");
    assertThat(detail.reason()).isEqualTo("竞品降价跟价");

    // 🔴 变更前后值（蓝图的输出句）。
    assertThat(detail.oldPrice()).isEqualTo(new BigDecimal("100.0000"));
    assertThat(detail.newPrice()).isEqualByComparingTo(new BigDecimal("120.00"));
    assertThat(detail.priceDelta()).isEqualTo(new BigDecimal("20.0000"));
    assertThat(detail.priceRate()).isEqualTo(new BigDecimal("0.200000"));
    assertThat(detail.priceSource()).isEqualTo(PriceSource.PRICE_OBSERVATION);
    assertThat(detail.priceObservedAt()).isNotNull();

    // 🔴 改价前后的利润**来自 5.3.1.1 的引擎**（单位成本 16.00、平台费 15%、物流 5）：
    //   售价 100 → 收入 100 − 平台费 15 − 物流 5 − 成本 16 = 64；
    //   售价 120 → 收入 120 − 平台费 18（120 的 15%）− 物流 5 − 成本 16 = 81。
    assertThat(detail.grossProfitBefore()).isEqualTo(new BigDecimal("64.0000"));
    assertThat(detail.grossProfitAfter()).isEqualTo(new BigDecimal("81.0000"));
    assertThat(detail.decisionReady()).isTrue();
    assertThat(detail.lossBefore()).isFalse();
    assertThat(detail.lossAfter()).isFalse();
    assertThat(detail.items()).hasSize(1);
  }

  @Test
  void 取不到当前价就整单拒绝而不是拿零顶() {
    UUID sku = pricedSku(actor, "PA", unique("CAT"), "BrandPA");
    // 🔴 **不登记任何价格观测** ⇒ 取不到当前价。
    var error =
        assertThrows(
            ResponseStatusException.class,
            () -> write(actor, () -> adjustments.create(actor, request(sku, "120"), bearer(actor))));
    assertThat(error.getStatusCode().value()).isEqualTo(400);
    assertThat(error.getMessage()).contains("没有任何价格事实");
    assertThat(error.getMessage()).contains("不会");
    // 这张 SKU 名下一条单子都没落库。
    assertThat(
            tx(
                actor,
                () ->
                    sql.fetchOne(
                            "select count(*) from price_adjustment_order where sku_id=?::uuid",
                            sku)
                        .get(0, Long.class)))
        .isEqualTo(0L);
  }

  @Test
  void 币种不同就整单拒绝绝不按一对一折算() {
    UUID sku = pricedSku(actor, "PA", unique("CAT"), "BrandPA");
    priceObservation(actor, sku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    var base = request(sku, "120");
    var euro =
        new PriceAdjustmentRequest(
            base.skuId(), base.platformCode(), base.siteCode(), "EUR", "USD", null,
            base.newPrice(), base.oldPrice(), base.quantity(), base.platformFeeRate(),
            base.platformFeeFixed(), base.logisticsCostPerUnit(), base.monthlyFixedCost(),
            base.costWindowFrom(), base.costWindowTo(), base.title(), base.reason(), base.note());
    var error =
        assertThrows(
            ResponseStatusException.class,
            () -> write(actor, () -> adjustments.create(actor, euro, bearer(actor))));
    assertThat(error.getStatusCode().value()).isEqualTo(400);
    assertThat(error.getMessage()).contains("绝不按 1:1 折算");
  }

  @Test
  void 你看到的旧价已经过期就冲突而不是照着过期价建单() {
    UUID sku = pricedSku(actor, "PA", unique("CAT"), "BrandPA");
    priceObservation(actor, sku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    var error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                write(
                    actor,
                    () -> adjustments.create(actor, request(sku, "80", "120"), bearer(actor))));
    assertThat(error.getStatusCode().value()).isEqualTo(409);
    assertThat(error.getMessage()).contains("已经不是当前价");
  }

  @Test
  void 新价不是正价就拒绝() {
    UUID sku = pricedSku(actor, "PA", unique("CAT"), "BrandPA");
    priceObservation(actor, sku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    var error =
        assertThrows(
            ResponseStatusException.class,
            () -> write(actor, () -> adjustments.create(actor, request(sku, "0"), bearer(actor))));
    assertThat(error.getStatusCode().value()).isEqualTo(400);
  }

  // ================================================================ 审批

  @Test
  void 命中规则就提交审批实例并停在待审() {
    UUID sku = pricedSku(actor, "PA", unique("CAT"), "BrandPA");
    priceObservation(actor, sku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    var draft = write(actor, () -> adjustments.create(actor, request(sku, "120"), bearer(actor)));

    var submitted =
        write(
            actor,
            () ->
                adjustments.submit(
                    actor, draft.id(), new PriceAdjustmentExpectedVersionRequest(draft.version(), null),
                    bearer(actor)));

    assertThat(submitted.status()).isEqualTo(Status.PENDING_APPROVAL);
    assertThat(submitted.statusLabel()).isEqualTo("待审");
    assertThat(submitted.approvalRequired()).isTrue();
    assertThat(submitted.approvalDecision()).isEqualTo("SUBMIT_FOR_APPROVAL");
    assertThat(submitted.approvalSource()).isEqualTo("RULE");
    assertThat(submitted.approvalInstanceId()).isNotNull();
    assertThat(submitted.approvalInstanceNo()).isNotBlank();
    assertThat(submitted.approvalFlowId()).isEqualTo(FLOW_ID);
    assertThat(submitted.version()).isEqualTo(draft.version() + 1);
    // 🔴 服务间授权密钥真的带上了（不是写死在代码里的）。
    assertThat(lastInternalKey.get()).isEqualTo("test-key");
    // 🔴 租户不是靠自定义头传过去的 —— 只透传调用方的会话。
    assertThat(lastApprovalBearer.get()).isEqualTo(bearer(actor));
  }

  @Test
  void 免审就直接进已批且不产生审批实例() {
    EVALUATION.set(Evaluation.EXEMPT);
    UUID sku = pricedSku(actor, "PA", unique("CAT"), "BrandPA");
    priceObservation(actor, sku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    var draft = write(actor, () -> adjustments.create(actor, request(sku, "120"), bearer(actor)));

    var submitted =
        write(
            actor,
            () ->
                adjustments.submit(
                    actor, draft.id(), new PriceAdjustmentExpectedVersionRequest(draft.version(), null),
                    bearer(actor)));

    assertThat(submitted.status()).isEqualTo(Status.APPROVED);
    assertThat(submitted.approvalRequired()).isFalse();
    assertThat(submitted.approvalDecision()).isEqualTo("EXEMPT");
    assertThat(submitted.approvalInstanceId()).isNull();
    assertThat(instanceCount()).isZero();
  }

  @Test
  void 租户未配规则就按默认放行处理() {
    EVALUATION.set(Evaluation.UNCONTROLLED);
    UUID sku = pricedSku(actor, "PA", unique("CAT"), "BrandPA");
    priceObservation(actor, sku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    var draft = write(actor, () -> adjustments.create(actor, request(sku, "120"), bearer(actor)));

    var submitted =
        write(
            actor,
            () ->
                adjustments.submit(
                    actor, draft.id(), new PriceAdjustmentExpectedVersionRequest(draft.version(), null),
                    bearer(actor)));

    assertThat(submitted.status()).isEqualTo(Status.APPROVED);
    assertThat(submitted.approvalSource()).isEqualTo("UNCONTROLLED");
  }

  @Test
  void 审批服务不可达就停在待审并如实标错而不是当成已批() {
    UUID sku = pricedSku(actor, "PA", unique("CAT"), "BrandPA");
    priceObservation(actor, sku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    var draft = write(actor, () -> adjustments.create(actor, request(sku, "120"), bearer(actor)));

    identityDown.set(true);
    var submitted =
        write(
            actor,
            () ->
                adjustments.submit(
                    actor, draft.id(), new PriceAdjustmentExpectedVersionRequest(draft.version(), null),
                    bearer(actor)));
    identityDown.set(false);

    // 🔴 停住 + 如实标错，**绝不**自动跳到已批。
    assertThat(submitted.status()).isEqualTo(Status.PENDING_APPROVAL);
    assertThat(submitted.approvalSyncError()).isNotBlank();
    assertThat(submitted.approvalSyncError()).contains("审批");
    assertThat(submitted.approvalInstanceId()).isNull();
    // 待审的单子**不能**执行。
    assertThat(
            assertThrows(
                ResponseStatusException.class,
                () ->
                    write(
                        actor,
                        () ->
                            adjustments.execute(
                                actor, draft.id(),
                                new PriceAdjustmentExpectedVersionRequest(submitted.version(), null),
                                bearer(actor))))
        .getStatusCode()
        .value())
        .isEqualTo(409);
  }

  @Test
  void 重复提交同一张单不产生第二条审批实例() {
    UUID sku = pricedSku(actor, "PA", unique("CAT"), "BrandPA");
    priceObservation(actor, sku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    var draft = write(actor, () -> adjustments.create(actor, request(sku, "120"), bearer(actor)));
    var submitted =
        write(
            actor,
            () ->
                adjustments.submit(
                    actor, draft.id(), new PriceAdjustmentExpectedVersionRequest(draft.version(), null),
                    bearer(actor)));
    assertThat(instanceCount()).isEqualTo(1);

    // 🔴 再提交一次：状态已经是待审 ⇒ 409，且**不产生第二条实例**。
    assertThat(
            assertThrows(
                ResponseStatusException.class,
                () ->
                    write(
                        actor,
                        () ->
                            adjustments.submit(
                                actor, draft.id(),
                                new PriceAdjustmentExpectedVersionRequest(submitted.version(), null),
                                bearer(actor))))
        .getStatusCode()
        .value())
        .isEqualTo(409);
    assertThat(instanceCount()).isEqualTo(1);

    // 🔴 端口层：同一来源单据再投一次 ⇒ 409（幂等命中），而不是产生第二条待办。
    assertThat(
            assertThrows(
                PriceAdjustmentApprovalPort.ApprovalConflictException.class,
                () ->
                    approvals.submit(
                        new PriceAdjustmentApprovalPort.SubmitInput(
                            FLOW_ID,
                            PriceAdjustmentApprovalPort.SOURCE_SYSTEM,
                            draft.id().toString(),
                            "标题",
                            "USD",
                            new BigDecimal("20")),
                        bearer(actor))))
        .hasMessageContaining("幂等");
    assertThat(instanceCount()).isEqualTo(1);
  }

  @Test
  void 刷新审批状态把结论拉回来() {
    UUID sku = pricedSku(actor, "PA", unique("CAT"), "BrandPA");
    priceObservation(actor, sku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    var draft = write(actor, () -> adjustments.create(actor, request(sku, "120"), bearer(actor)));
    var submitted =
        write(
            actor,
            () ->
                adjustments.submit(
                    actor, draft.id(), new PriceAdjustmentExpectedVersionRequest(draft.version(), null),
                    bearer(actor)));

    // 审批人还没决定 ⇒ 仍然是待审。
    var still = write(actor, () -> adjustments.refreshApproval(actor, draft.id(), bearer(actor)));
    assertThat(still.status()).isEqualTo(Status.PENDING_APPROVAL);

    // 审批人在 identity 那边批了 ⇒ 刷新后进已批。
    decide(draft.id().toString(), "APPROVED");
    var approved = write(actor, () -> adjustments.refreshApproval(actor, draft.id(), bearer(actor)));
    assertThat(approved.status()).isEqualTo(Status.APPROVED);
    assertThat(approved.approvalDecidedAt()).isNotNull();
    assertThat(approved.version()).isGreaterThan(submitted.version());
  }

  @Test
  void 审批被驳回就进已驳回() {
    UUID sku = pricedSku(actor, "PA", unique("CAT"), "BrandPA");
    priceObservation(actor, sku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    var draft = write(actor, () -> adjustments.create(actor, request(sku, "120"), bearer(actor)));
    write(
        actor,
        () ->
            adjustments.submit(
                actor, draft.id(), new PriceAdjustmentExpectedVersionRequest(draft.version(), null),
                bearer(actor)));

    decide(draft.id().toString(), "REJECTED");
    var rejected = write(actor, () -> adjustments.refreshApproval(actor, draft.id(), bearer(actor)));
    assertThat(rejected.status()).isEqualTo(Status.REJECTED);
    assertThat(rejected.rejectReason()).contains("驳回");
  }

  @Test
  void 读详情会顺带刷新审批状态() {
    UUID sku = pricedSku(actor, "PA", unique("CAT"), "BrandPA");
    priceObservation(actor, sku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    var draft = write(actor, () -> adjustments.create(actor, request(sku, "120"), bearer(actor)));
    write(
        actor,
        () ->
            adjustments.submit(
                actor, draft.id(), new PriceAdjustmentExpectedVersionRequest(draft.version(), null),
                bearer(actor)));
    decide(draft.id().toString(), "APPROVED");

    var detail = tx(actor, () -> adjustments.get(draft.id(), true, bearer(actor)));
    assertThat(detail.status()).isEqualTo(Status.APPROVED);
  }

  // ================================================================ 执行与回写事实

  @Test
  void 执行让新价生效并产出待回写事实() {
    EVALUATION.set(Evaluation.EXEMPT);
    UUID sku = pricedSku(actor, "PA", unique("CAT"), "BrandPA");
    priceObservation(actor, sku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    var draft = write(actor, () -> adjustments.create(actor, request(sku, "120"), bearer(actor)));
    var approved =
        write(
            actor,
            () ->
                adjustments.submit(
                    actor, draft.id(), new PriceAdjustmentExpectedVersionRequest(draft.version(), null),
                    bearer(actor)));

    var executed =
        write(
            actor,
            () ->
                adjustments.execute(
                    actor, draft.id(), new PriceAdjustmentExpectedVersionRequest(approved.version(), null),
                    bearer(actor)));

    assertThat(executed.status()).isEqualTo(Status.EXECUTED);
    assertThat(executed.executedAt()).isNotNull();

    // 🔴 产出一条「待回写」事实（下游 5.4.2.1 消费它；本模块**不调平台 API**）。
    var writebacks =
        tx(
            actor,
            () ->
                adjustments.listWritebacks(
                    "PENDING", sku, "AMAZON", "US", 0, 20));
    assertThat(writebacks.total()).isEqualTo(1L);
    var writeback = writebacks.items().get(0);
    assertThat(writeback.expectedPrice()).isEqualByComparingTo(new BigDecimal("120.00"));
    assertThat(writeback.previousPrice()).isEqualTo(new BigDecimal("100.0000"));
    assertThat(writeback.status()).isEqualTo("PENDING");
    assertThat(writeback.idempotencyKey()).isEqualTo("price-adjustment:" + draft.id());

    // 回执后状态转成 ACKNOWLEDGED。
    var acked =
        tx(
            actor,
            () ->
                adjustments.ackWriteback(
                    writeback.id(), new PriceAdjustmentWritebackAckRequest("平台已受理，价未变")));
    assertThat(acked.status()).isEqualTo("ACKNOWLEDGED");
    assertThat(acked.acknowledgedAt()).isNotNull();
  }

  @Test
  void 版本号对不上就冲突() {
    UUID sku = pricedSku(actor, "PA", unique("CAT"), "BrandPA");
    priceObservation(actor, sku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    var draft = write(actor, () -> adjustments.create(actor, request(sku, "120"), bearer(actor)));

    var error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                write(
                    actor,
                    () ->
                        adjustments.submit(
                            actor, draft.id(), new PriceAdjustmentExpectedVersionRequest(99, null),
                            bearer(actor))));
    assertThat(error.getStatusCode().value()).isEqualTo(409);
    assertThat(error.getMessage()).contains("版本冲突");
  }

  @Test
  void 待审且已有在办实例时不允许本地驳回() {
    UUID sku = pricedSku(actor, "PA", unique("CAT"), "BrandPA");
    priceObservation(actor, sku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    var draft = write(actor, () -> adjustments.create(actor, request(sku, "120"), bearer(actor)));
    var submitted =
        write(
            actor,
            () ->
                adjustments.submit(
                    actor, draft.id(), new PriceAdjustmentExpectedVersionRequest(draft.version(), null),
                    bearer(actor)));

    var error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                write(
                    actor,
                    () ->
                        adjustments.reject(
                            actor, draft.id(),
                            new PriceAdjustmentRejectRequest(submitted.version(), "不做了"),
                            bearer(actor))));
    assertThat(error.getStatusCode().value()).isEqualTo(409);
    assertThat(error.getMessage()).contains("待办");
  }

  @Test
  void 草稿可以本地驳回() {
    UUID sku = pricedSku(actor, "PA", unique("CAT"), "BrandPA");
    priceObservation(actor, sku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    var draft = write(actor, () -> adjustments.create(actor, request(sku, "120"), bearer(actor)));

    var rejected =
        write(
            actor,
            () ->
                adjustments.reject(
                    actor, draft.id(), new PriceAdjustmentRejectRequest(draft.version(), "改主意了"),
                    bearer(actor)));
    assertThat(rejected.status()).isEqualTo(Status.REJECTED);
    assertThat(rejected.rejectReason()).isEqualTo("改主意了");
    assertThat(rejected.rejectedAt()).isNotNull();
  }

  // ================================================================ 台账

  @Test
  void 台账真分页真筛选() {
    String category = unique("CAT");
    UUID sku = pricedSku(actor, "PA", category, "BrandPA");
    priceObservation(actor, sku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    for (int i = 0; i < 3; i++) {
      final String newPrice = "12" + i;
      write(actor, () -> adjustments.create(actor, request(sku, newPrice), bearer(actor)));
    }
    // 真分页：按 SKU 过滤后一共 3 条，第一页 2 条、total 3。
    var page =
        tx(
            actor,
            () -> adjustments.list(new OrderQuery(null, sku, null, null, null, null, 0, 2)));
    assertThat(page.items()).hasSize(2);
    assertThat(page.size()).isEqualTo(2);
    assertThat(page.total()).isEqualTo(3L);

    // 真分页：第二页只剩 1 条（不是把第一页重复一遍）。
    var page2 =
        tx(
            actor,
            () -> adjustments.list(new OrderQuery(null, sku, null, null, null, null, 1, 2)));
    assertThat(page2.items()).hasSize(1);
    assertThat(page2.total()).isEqualTo(3L);

    // 真筛选：按状态筛（新建的都是草稿）。
    var drafts =
        tx(
            actor,
            () ->
                adjustments.list(
                    new OrderQuery("DRAFT", sku, null, null, null, null, 0, 20)));
    assertThat(drafts.total()).isEqualTo(3L);

    // 真筛选：按店铺筛。
    var byShop =
        tx(
            actor,
            () ->
                adjustments.list(
                    new OrderQuery(null, sku, "AMAZON", "US", null, null, 0, 20)));
    assertThat(byShop.total()).isEqualTo(3L);

    // 真筛选：换一个店铺 ⇒ 零行（不是「筛选没生效，把全部返回」）。
    var otherShop =
        tx(
            actor,
            () ->
                adjustments.list(
                    new OrderQuery(null, sku, "AMAZON", "DE", null, null, 0, 20)));
    assertThat(otherShop.total()).isZero();
    assertThat(otherShop.items()).isEmpty();

    // 真筛选：按关键字（SKU 编码模糊匹配）。
    var skuCode = page.items().get(0).skuCode();
    var byKeyword =
        tx(
            actor,
            () -> adjustments.list(new OrderQuery(null, null, null, null, skuCode, null, 0, 20)));
    assertThat(byKeyword.total()).isEqualTo(3L);
    // 关键字匹配不到 ⇒ 真零行（不是「假分页返回一堆」）。
    var noMatch =
        tx(
            actor,
            () ->
                adjustments.list(
                    new OrderQuery(null, null, null, null, "不存在的关键字-ZZZ", null, 0, 20)));
    assertThat(noMatch.total()).isZero();
    assertThat(noMatch.items()).isEmpty();
  }

  @Test
  void 状态筛选值非法就报错() {
    assertThat(
            assertThrows(
                ResponseStatusException.class,
                () ->
                    tx(
                        actor,
                        () ->
                            adjustments.list(
                                new OrderQuery("NOT_A_STATUS", null, null, null, null, null, 0, 20))))
                .getStatusCode()
                .value())
        .isEqualTo(400);
  }

  // ================================================================ 时间与精度

  @Test
  void 时间戳写回零漂移() {
    UUID sku = pricedSku(actor, "PA", unique("CAT"), "BrandPA");
    priceObservation(actor, sku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    var draft = write(actor, () -> adjustments.create(actor, request(sku, "120"), bearer(actor)));

    // 🔴 写入一个带 +08:00 偏移量的时刻，读回来必须是**同一个绝对时刻** ——
    //   用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s，这里断言的是值本身。
    var stamp = OffsetDateTime.of(2026, 10, 21, 12, 0, 0, 0, ZoneOffset.ofHours(8));
    write(
        actor,
        () -> {
          sql.execute(
              "update price_adjustment_order set updated_at=?::timestamptz"
                  + " where tenant_id=? and id=?",
              stamp,
              actor.tenantId(),
              draft.id());
          return null;
        });
    var read =
        tx(
            actor,
            () ->
                sql.fetchOne(
                        "select updated_at from price_adjustment_order"
                            + " where tenant_id=? and id=?",
                        actor.tenantId(),
                        draft.id())
                    .get("updated_at", OffsetDateTime.class));
    assertThat(read.toInstant()).isEqualTo(stamp.toInstant());
    assertThat(read.toInstant()).isEqualTo(Instant.parse("2026-10-21T04:00:00Z"));
  }

  @Test
  void 金额与数量都是定点没有浮点误差() {
    UUID sku = pricedSku(actor, "PA", unique("CAT"), "BrandPA");
    priceObservation(actor, sku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    var detail = write(actor, () -> adjustments.create(actor, request(sku, "119.995"), bearer(actor)));
    // 对外标价 2 位 HALF_UP。
    assertThat(detail.newPrice()).isEqualByComparingTo(new BigDecimal("120.00"));
    // 内部金额 4 位小数。
    assertThat(detail.priceDelta().scale()).isEqualTo(4);
    assertThat(detail.grossProfitBefore().scale()).isEqualTo(4);
    // 费率 6 位小数。
    assertThat(detail.grossMarginBefore().scale()).isEqualTo(6);
    // 数量 6 位小数。
    assertThat(detail.quantity().scale()).isEqualTo(6);
  }

  // ================================================================ HTTP 端点

  @Test
  void http端点通() throws Exception {
    UUID sku = pricedSku(actor, "PA", unique("CAT"), "BrandPA");
    priceObservation(actor, sku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());

    http.perform(get("/catalog/price-adjustments/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvalNote").exists());
    http.perform(
            get("/catalog/price-adjustments/current-price")
                .param("skuId", sku.toString())
                .param("platformCode", "AMAZON")
                .param("siteCode", "US")
                .param("currency", "USD")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.price").value(100.0));

    var body =
        http.perform(
                post("/catalog/price-adjustments")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType("application/json")
                    .content(JSON.writeValueAsString(request(sku, "120"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.version").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var id = JSON.readTree(body).get("id").asText();

    http.perform(
            get("/catalog/price-adjustments/" + id).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/price-adjustments").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").isNumber());
  }

  @Test
  void 只读角色不能调价() throws Exception {
    UUID sku = pricedSku(actor, "PA", unique("CAT"), "BrandPA");
    priceObservation(actor, sku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    http.perform(
            post("/catalog/price-adjustments")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(JSON.writeValueAsString(request(sku, "120"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void 能力声明把关键口径写在页面上() {
    var caps = adjustments.capabilities();
    assertThat(caps.priceSourceNote()).contains("5.5.2.1");
    assertThat(caps.priceSourceNote()).contains("5.4.1.2");
    assertThat(caps.priceSourceNote()).contains("绝不用 0 顶");
    assertThat(caps.approvalNote()).contains("PRICE_CHANGE");
    assertThat(caps.approvalSyncNote()).contains("拉取式");
    assertThat(caps.stateMachineNote()).contains("草稿");
    assertThat(caps.versionNote()).contains("expectedVersion");
    assertThat(caps.roundingNote()).contains("2 位小数");
    assertThat(caps.downstreamNote()).contains("5.4.2.1");
    assertThat(caps.listingDefinitionNote()).contains("4.1.1.1");
    assertThat(List.of(caps.approvalNote(), caps.priceSourceNote())).allSatisfy(s -> assertThat(s).isNotBlank());
  }
}
