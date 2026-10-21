package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.trade.PurchaseOrderApprovalModels.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.3.1.3 采购单审批的**行为**测试（真实 PostgreSQL 16 + 强制 RLS）。
 *
 * <p>覆盖蓝图职责句的每一块：
 *
 * <ul>
 *   <li>按<b>金额阈值</b>与<b>采购员分级</b>审批（命中哪条规则、要几级几个人）；</li>
 *   <li><b>批量</b>审批、<b>驳回并附意见</b>（意见必填）；</li>
 *   <li>审批结果 + <b>审批链路记录</b>（谁、什么层级、哪条规则、当时的金额）；</li>
 *   <li>通过后<b>锁定单据</b>（数量与单价不可直接改）、<b>生成应付</b>、<b>允许收货</b>。</li>
 * </ul>
 */
class PurchaseOrderApprovalIntegrationTest extends PurchaseOrderApprovalFixture {

  @Autowired PurchaseOrderApprovalService approvals;

  @BeforeEach
  void clean() throws Exception {
    truncateAll();
    seedStandardConfig();
  }

  // ================================================================ 配置

  /** A 租户的标准配置：采购员分级 + 三条规则 + 四个审批人。 */
  private void seedStandardConfig() throws Exception {
    seedBuyer(tenantA, buyerA, "初级采购员A", "JUNIOR");
    // 分级不同 ⇒ 命中的档不同：这是「按采购员分级审批」的落点。
    seedRule(tenantA, "R-JUNIOR-LOW", "JUNIOR", "USD", "0", "1000", "L2", 1);
    seedRule(tenantA, "R-JUNIOR-HIGH", "JUNIOR", "USD", "1000", null, "L3", 2);
    seedRule(tenantA, "R-SENIOR-ANY", "SENIOR", "ANY", "0", null, "L1", 1);

    seedApprover(tenantA, approverA, "张经理", "L3", "ANY", "0", null);
    seedApprover(tenantA, approverA2, "李总监", "L3", "ANY", "0", null);
    seedApprover(tenantA, juniorApproverA, "初级审批人", "L1", "ANY", "0", null);
    // 层级够、金额上限只有 100 ⇒ 用来证明「金额授权区间」真的在拦。
    seedApprover(tenantA, bandApproverA, "窄额度审批人", "L3", "ANY", "0", "100");
  }

  // ================================================================ 命中判定

  @Test
  void 提交那一刻冻结审批链_金额阈值决定档位() throws Exception {
    // 250 = 100 × 2.5 ⇒ 落在 [0, 1000) ⇒ L2、1 人。
    UUID order = seedSubmittedOrder(tenantA, buyerA, "PO-T-1", "USD", "100", "2.5");

    PurchaseOrderApprovalDetail detail =
        as("Bearer approver-a", () -> approvals.detail(order, approverA, departmentA, "ALL"));

    assertThat(detail.caseStatus()).isEqualTo("PENDING");
    assertThat(detail.ruleName()).isEqualTo("规则-R-JUNIOR-LOW");
    assertThat(detail.ruleMatch()).isEqualTo("RULE");
    assertThat(detail.buyerLevel()).isEqualTo("JUNIOR");
    assertThat(detail.requiredTier()).isEqualTo("L2");
    assertThat(detail.requiredApprovals()).isEqualTo(1);
    assertThat(detail.orderAmount()).isEqualByComparingTo("250.0000");
    assertThat(detail.currency()).isEqualTo("USD");
  }

  @Test
  void 金额跨过阈值换档且要几个人就几个人() throws Exception {
    // 2500 ⇒ 落在 [1000, ∞) ⇒ L3、2 人。
    UUID order = seedSubmittedOrder(tenantA, buyerA, "PO-T-2", "USD", "100", "25");

    PurchaseOrderApprovalDetail detail =
        as("Bearer approver-a", () -> approvals.detail(order, approverA, departmentA, "ALL"));

    assertThat(detail.ruleName()).isEqualTo("规则-R-JUNIOR-HIGH");
    assertThat(detail.requiredTier()).isEqualTo("L3");
    assertThat(detail.requiredApprovals()).isEqualTo(2);
  }

  @Test
  void 采购员分级不同命中不同的规则() throws Exception {
    // 同一个金额（250），换个分级 ⇒ 命中另一条规则。
    seedBuyer(tenantA, buyerA, "高级采购员A", "SENIOR");
    UUID order = seedSubmittedOrder(tenantA, buyerA, "PO-T-3", "USD", "100", "2.5");

    PurchaseOrderApprovalDetail detail =
        as("Bearer approver-a", () -> approvals.detail(order, approverA, departmentA, "ALL"));

    assertThat(detail.buyerLevel()).isEqualTo("SENIOR");
    assertThat(detail.ruleName()).isEqualTo("规则-R-SENIOR-ANY");
    assertThat(detail.requiredTier()).isEqualTo("L1");
  }

  @Test
  void 提交之后改分级不改已冻结的审批链() throws Exception {
    UUID order = seedSubmittedOrder(tenantA, buyerA, "PO-T-3B", "USD", "100", "2.5");
    // 提交之后才把采购员改成高级 ⇒ **已冻结的审批链不变**：
    // 「这一单要几级审批」是提交那一刻定下的审计事实，不能事后跟着规则改。
    seedBuyer(tenantA, buyerA, "高级采购员A", "SENIOR");

    PurchaseOrderApprovalDetail detail =
        as("Bearer approver-a", () -> approvals.detail(order, approverA, departmentA, "ALL"));

    assertThat(detail.buyerLevel()).isEqualTo("JUNIOR");
    assertThat(detail.ruleName()).isEqualTo("规则-R-JUNIOR-LOW");
    assertThat(detail.requiredTier()).isEqualTo("L2");
  }

  @Test
  void 一条规则都没命中时走默认档_而不是当作已批() throws Exception {
    // B 租户没有配任何规则 ⇒ 默认档（L1、1 人），**不是**自动通过。
    UUID order = seedSubmittedOrder(tenantB, buyerB, "PO-T-4", "USD", "10", "3");

    PurchaseOrderApprovalDetail detail =
        as("Bearer approver-b", () -> approvals.detail(order, approverB, null, "ALL"));

    assertThat(detail.ruleMatch()).isEqualTo("DEFAULT");
    assertThat(detail.requiredTier()).isEqualTo("L1");
    assertThat(detail.requiredApprovals()).isEqualTo(1);
    assertThat(detail.caseStatus()).isEqualTo("PENDING");
    assertThat(detail.orderStatus()).isEqualTo("PENDING_APPROVAL");
  }

  // ================================================================ 审批通过

  @Test
  void 审批通过后锁定单据并生成应付与收货放行() throws Exception {
    UUID order = seedSubmittedOrder(tenantA, buyerA, "PO-A-1", "USD", "100", "2.5");

    PurchaseOrderApprovalDetail detail =
        asApprove(
            "Bearer approver-a",
            () ->
                approvals.approve(
                    order,
                    new PurchaseOrderApprovalDecisionRequest("同意，按合同价下单"),
                    approverA,
                    departmentA,
                    "ALL"));

    // ---- 审批结果 ----
    assertThat(detail.orderStatus()).isEqualTo("APPROVED");
    assertThat(detail.caseStatus()).isEqualTo("APPROVED");
    assertThat(detail.approvedCount()).isEqualTo(1);
    assertThat(detail.chain()).hasSize(1);
    PurchaseOrderApprovalChainRow step = detail.chain().get(0);
    assertThat(step.action()).isEqualTo("APPROVED");
    assertThat(step.approverUserId()).isEqualTo(approverA);
    assertThat(step.approvalTier()).isEqualTo("L3");
    assertThat(step.note()).isEqualTo("同意，按合同价下单");
    assertThat(step.orderAmount()).isEqualByComparingTo("250.0000");
    assertThat(step.ruleName()).isEqualTo("规则-R-JUNIOR-LOW");
    // 🔴 时刻要能读回来（timestamptz 零漂移），不是「没抛异常」。
    assertThat(step.occurredAt()).isNotNull();
    assertThat(step.occurredAt().getOffset().getTotalSeconds()).isEqualTo(28800);

    // ---- 应付登记：登记额 = 采购单金额，生效额恒为 0 ----
    assertThat(detail.payable()).isNotNull();
    assertThat(detail.payable().registeredAmount()).isEqualByComparingTo("250.0000");
    assertThat(detail.payable().effectiveAmount()).isEqualByComparingTo("0");
    assertThat(detail.payable().status()).isEqualTo("PENDING_FINANCE_CONFIRM");
    assertThat(detail.payable().currency()).isEqualTo("USD");

    // ---- 收货放行事实 ----
    assertThat(detail.receiptRelease()).isNotNull();
    assertThat(detail.receiptRelease().status()).isEqualTo("RELEASED");

    // ---- 采购单自己的留痕（6.3.1.1 的 purchase_order_event）----
    assertThat(detail.events())
        .anySatisfy(
            e -> {
              assertThat(e.eventType()).isEqualTo("STATUS_CHANGED");
              assertThat(e.fromStatus()).isEqualTo("PENDING_APPROVAL");
              assertThat(e.toStatus()).isEqualTo("APPROVED");
            });
  }

  @Test
  void 审批通过后数量与单价不可直接改_绕过服务层也不行() throws Exception {
    UUID order = seedSubmittedOrder(tenantA, buyerA, "PO-A-2", "USD", "100", "2.5");
    asApprove(
        "Bearer approver-a",
        () ->
            approvals.approve(
                order, new PurchaseOrderApprovalDecisionRequest(""), approverA, departmentA, "ALL"));

    // 🔴 **直接 SQL** 改数量（绕过服务层）⇒ DB 触发器必须挡住。
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "update purchase_order_line set quantity=200 where order_id=?::uuid")) {
      ps.setObject(1, order);
      assertThatThrownBy(ps::executeUpdate)
          .hasMessageContaining("数量与单价不可再改")
          .hasMessageContaining("变更单");
    }
    // 单价同理。
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "update purchase_order_line set unit_price=3 where order_id=?::uuid")) {
      ps.setObject(1, order);
      assertThatThrownBy(ps::executeUpdate).hasMessageContaining("数量与单价不可再改");
    }
    // 数量与单价都没动 ⇒ 允许（触发器只拦这两列）。
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "update purchase_order_line set note='备注改了' where order_id=?::uuid")) {
      ps.setObject(1, order);
      assertThat(ps.executeUpdate()).isEqualTo(1);
    }
    // 金额没被改大。
    assertThat(
            as(
                "Bearer approver-a",
                () ->
                    sql.fetchOne("select total_amount from purchase_order where id=?::uuid", order)
                        .get(0, BigDecimal.class)))
        .isEqualByComparingTo("250");
  }

  @Test
  void 提交之后待审批期间数量与单价同样不可改() throws Exception {
    UUID order = seedSubmittedOrder(tenantA, buyerA, "PO-A-3", "USD", "100", "2.5");

    try (var c = admin();
        var ps =
            c.prepareStatement(
                "update purchase_order_line set quantity=999 where order_id=?::uuid")) {
      ps.setObject(1, order);
      assertThatThrownBy(ps::executeUpdate).hasMessageContaining("数量与单价不可再改");
    }
  }

  @Test
  void 草稿期间数量与单价还能改() throws Exception {
    UUID order = seedDraftOrder(tenantA, buyerA, "PO-A-4", "USD", "100", "2.5");

    try (var c = admin();
        var ps =
            c.prepareStatement(
                "update purchase_order_line set quantity=200 where order_id=?::uuid")) {
      ps.setObject(1, order);
      assertThat(ps.executeUpdate()).isEqualTo(1);
    }
  }

  // ================================================================ 多人审批

  @Test
  void 需要几个人就几个人_一个人批完还挂着() throws Exception {
    UUID order = seedSubmittedOrder(tenantA, buyerA, "PO-A-5", "USD", "100", "25");

    PurchaseOrderApprovalDetail afterFirst =
        asApprove(
            "Bearer approver-a",
            () ->
                approvals.approve(
                    order, new PurchaseOrderApprovalDecisionRequest("第一批"), approverA, departmentA, "ALL"));
    assertThat(afterFirst.caseStatus()).isEqualTo("PENDING");
    assertThat(afterFirst.approvedCount()).isEqualTo(1);
    assertThat(afterFirst.orderStatus()).isEqualTo("PENDING_APPROVAL");
    assertThat(afterFirst.payable().status())
        .as("还没凑够人数 ⇒ 还不该登记应付")
        .isEqualTo("NONE");
    assertThat(afterFirst.payable().registeredAmount()).as("还没登记 ⇒ 金额是「没有」不是 0").isNull();
    assertThat(afterFirst.receiptRelease().status()).as("还没通过 ⇒ 还不该放行收货").isEqualTo("NONE");

    PurchaseOrderApprovalDetail afterSecond =
        asApprove(
            "Bearer approver-a2",
            () ->
                approvals.approve(
                    order, new PurchaseOrderApprovalDecisionRequest("第二批"), approverA2, departmentA, "ALL"));
    assertThat(afterSecond.caseStatus()).isEqualTo("APPROVED");
    assertThat(afterSecond.approvedCount()).isEqualTo(2);
    assertThat(afterSecond.orderStatus()).isEqualTo("APPROVED");
    assertThat(afterSecond.payable()).isNotNull();
    assertThat(afterSecond.payable().registeredAmount()).isEqualByComparingTo("2500.0000");
    assertThat(afterSecond.chain()).hasSize(2);
    assertThat(afterSecond.chain().get(1).seq()).isEqualTo(2);
  }

  @Test
  void 同一个人不能批两次凑人数() throws Exception {
    UUID order = seedSubmittedOrder(tenantA, buyerA, "PO-A-6", "USD", "100", "25");

    asApprove(
        "Bearer approver-a",
        () ->
            approvals.approve(
                order, new PurchaseOrderApprovalDecisionRequest("第一次"), approverA, departmentA, "ALL"));

    assertThatThrownBy(
            () ->
                asApprove(
                    "Bearer approver-a",
                    () ->
                        approvals.approve(
                            order,
                            new PurchaseOrderApprovalDecisionRequest("第二次"),
                            approverA,
                            departmentA,
                            "ALL")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已经在这张采购单上批过");
  }

  // ================================================================ 拒绝

  @Test
  void 驳回必须附意见() throws Exception {
    UUID order = seedSubmittedOrder(tenantA, buyerA, "PO-R-1", "USD", "100", "2.5");

    assertThatThrownBy(
            () ->
                asApprove(
                    "Bearer approver-a",
                    () ->
                        approvals.reject(
                            order,
                            new PurchaseOrderApprovalDecisionRequest("   "),
                            approverA,
                            departmentA,
                            "ALL")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("驳回必须附意见");
  }

  @Test
  void 驳回后单子进已驳回且意见进链路() throws Exception {
    UUID order = seedSubmittedOrder(tenantA, buyerA, "PO-R-2", "USD", "100", "2.5");

    PurchaseOrderApprovalDetail detail =
        asApprove(
            "Bearer approver-a",
            () ->
                approvals.reject(
                    order,
                    new PurchaseOrderApprovalDecisionRequest("单价比报价高，退回重报"),
                    approverA,
                    departmentA,
                    "ALL"));

    assertThat(detail.orderStatus()).isEqualTo("REJECTED");
    assertThat(detail.caseStatus()).isEqualTo("REJECTED");
    assertThat(detail.chain()).hasSize(1);
    assertThat(detail.chain().get(0).action()).isEqualTo("REJECTED");
    assertThat(detail.chain().get(0).note()).isEqualTo("单价比报价高，退回重报");
    // 驳回 ⇒ **不**登记应付、**不**放行收货（视图用 status=NONE 说「还没发生」，
    // 而不是给一个空对象 —— 空对象会被界面读成「金额 0」）。
    assertThat(detail.payable().status()).isEqualTo("NONE");
    assertThat(detail.payable().registeredAmount()).isNull();
    assertThat(detail.receiptRelease().status()).isEqualTo("NONE");
  }

  @Test
  void 已审批完的单不能再审批() throws Exception {
    UUID order = seedSubmittedOrder(tenantA, buyerA, "PO-A-7", "USD", "100", "2.5");
    asApprove(
        "Bearer approver-a",
        () ->
            approvals.approve(
                order, new PurchaseOrderApprovalDecisionRequest("同意"), approverA, departmentA, "ALL"));

    assertThatThrownBy(
            () ->
                asApprove(
                    "Bearer approver-a2",
                    () ->
                        approvals.approve(
                            order,
                            new PurchaseOrderApprovalDecisionRequest("再批一次"),
                            approverA2,
                            departmentA,
                            "ALL")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不在待审批状态");
  }

  // ================================================================ 权限闸

  @Test
  void 不能审批自己提交的采购单() throws Exception {
    UUID order = seedSubmittedOrder(tenantA, buyerA, "PO-X-1", "USD", "100", "2.5");
    // 采购员本人也是审批人（给自己配一条）⇒ 仍然不能自审。
    seedApprover(tenantA, buyerA, "初级采购员A", "L3", "ANY", "0", null);

    assertThatThrownBy(
            () ->
                asApprove(
                    "Bearer buyer-a",
                    () ->
                        approvals.approve(
                            order,
                            new PurchaseOrderApprovalDecisionRequest("自己批"),
                            buyerA,
                            departmentA,
                            "ALL")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能审批自己提交的采购单");
  }

  @Test
  void 层级不够批不了() throws Exception {
    UUID order = seedSubmittedOrder(tenantA, buyerA, "PO-X-2", "USD", "100", "2.5");

    assertThatThrownBy(
            () ->
                asApprove(
                    "Bearer junior-a",
                    () ->
                        approvals.approve(
                            order,
                            new PurchaseOrderApprovalDecisionRequest("试试"),
                            juniorApproverA,
                            departmentA,
                            "ALL")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("审批层级低于");
  }

  @Test
  void 金额超出授权区间批不了() throws Exception {
    UUID order = seedSubmittedOrder(tenantA, buyerA, "PO-X-3", "USD", "100", "2.5");

    assertThatThrownBy(
            () ->
                asApprove(
                    "Bearer band-a",
                    () ->
                        approvals.approve(
                            order,
                            new PurchaseOrderApprovalDecisionRequest("试试"),
                            bandApproverA,
                            departmentA,
                            "ALL")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("授权上限");
  }

  @Test
  void 不在册的审批人批不了() throws Exception {
    UUID order = seedSubmittedOrder(tenantA, buyerA, "PO-X-4", "USD", "100", "2.5");

    assertThatThrownBy(
            () ->
                asApprove(
                    "Bearer stranger-a",
                    () ->
                        approvals.approve(
                            order,
                            new PurchaseOrderApprovalDecisionRequest("试试"),
                            strangerA,
                            departmentA,
                            "ALL")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不是本租户在册的审批人");
  }

  // ================================================================ 批量

  @Test
  void 批量审批逐条给结论_失败的那张不拖累其它张() throws Exception {
    UUID ok1 = seedSubmittedOrder(tenantA, buyerA, "PO-B-1", "USD", "100", "2.5");
    UUID ok2 = seedSubmittedOrder(tenantA, buyerA, "PO-B-2", "USD", "100", "2.5");
    // 这一张的金额是 0 ⇒ 审批到最后会因「登记不出应付」被拒（前面的人已经批过）。
    UUID zero = seedSubmittedOrder(tenantA, buyerA, "PO-B-3", "USD", "100", "0");
    // 这一张压根不是待审批状态。
    UUID draft = seedDraftOrder(tenantA, buyerA, "PO-B-4", "USD", "100", "2.5");

    PurchaseOrderApprovalBatchResult result =
        asApprove(
            "Bearer approver-a",
            () ->
                approvals.batch(
                    new PurchaseOrderApprovalBatchRequest(
                        List.of(ok1, zero, draft, ok2), "APPROVE", "批量同意"),
                    approverA,
                    departmentA,
                    "ALL"));

    assertThat(result.items()).hasSize(4);
    assertThat(result.succeededCount()).isEqualTo(2);
    assertThat(result.failedCount()).isEqualTo(2);
    assertThat(result.items().get(0).succeeded()).isTrue();
    assertThat(result.items().get(1).succeeded()).isFalse();
    assertThat(result.items().get(1).message()).contains("金额是 0");
    assertThat(result.items().get(2).succeeded()).isFalse();
    assertThat(result.items().get(2).message()).contains("不在待审批状态");
    assertThat(result.items().get(3).succeeded()).isTrue();

    // 🔴 失败的那张**没有留下半截写入**：审批留痕必须还是 0 条。
    assertThat(countRecords(tenantA, zero)).isZero();
    // 成功的那两张各有一条留痕。
    assertThat(countRecords(tenantA, ok1)).isEqualTo(1);
    assertThat(countRecords(tenantA, ok2)).isEqualTo(1);
  }

  @Test
  void 批量驳回必须附意见() throws Exception {
    UUID order = seedSubmittedOrder(tenantA, buyerA, "PO-B-5", "USD", "100", "2.5");

    assertThatThrownBy(
            () ->
                asApprove(
                    "Bearer approver-a",
                    () ->
                        approvals.batch(
                            new PurchaseOrderApprovalBatchRequest(List.of(order), "REJECT", ""),
                            approverA,
                            departmentA,
                            "ALL")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("批量驳回必须附意见");
  }

  @Test
  void 批量驳回逐条给结论() throws Exception {
    UUID order = seedSubmittedOrder(tenantA, buyerA, "PO-B-6", "USD", "100", "2.5");

    PurchaseOrderApprovalBatchResult result =
        asApprove(
            "Bearer approver-a",
            () ->
                approvals.batch(
                    new PurchaseOrderApprovalBatchRequest(List.of(order), "REJECT", "单价偏高"),
                    approverA,
                    departmentA,
                    "ALL"));

    assertThat(result.succeededCount()).isEqualTo(1);
    assertThat(result.failedCount()).isZero();
    assertThat(
            as(
                "Bearer approver-a",
                () ->
                    sql.fetchOne(
                            "select status from purchase_order where id=?::uuid", order)
                        .get(0, String.class)))
        .isEqualTo("REJECTED");
  }

  // ================================================================ 应付

  @Test
  void 应付登记额与生效额分两列_未确认时生效额恒为0() throws Exception {
    UUID order = seedSubmittedOrder(tenantA, buyerA, "PO-P-1", "USD", "100", "2.5");
    asApprove(
        "Bearer approver-a",
        () ->
            approvals.approve(
                order, new PurchaseOrderApprovalDecisionRequest(""), approverA, departmentA, "ALL"));

    PurchaseOrderApprovalPayableView payable =
        as("Bearer approver-a", () -> approvals.payables(null, 0, 20).items().get(0));
    assertThat(payable.registeredAmount()).isEqualByComparingTo("250.0000");
    assertThat(payable.effectiveAmount()).isEqualByComparingTo("0");
    assertThat(payable.status()).isEqualTo("PENDING_FINANCE_CONFIRM");

    // 🔴 表级 CHECK：直接 SQL 把生效额改成非 0 而状态还是「待确认」⇒ 必须被拒。
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "update purchase_order_payable set effective_amount=250 where order_id=?::uuid")) {
      ps.setObject(1, order);
      assertThatThrownBy(ps::executeUpdate)
          .hasMessageContaining("purchase_order_payable_pending_zero");
    }
  }

  @Test
  void 财务确认后生效额等于登记额且凭证齐全() throws Exception {
    UUID order = seedSubmittedOrder(tenantA, buyerA, "PO-P-2", "USD", "100", "2.5");
    asApprove(
        "Bearer approver-a",
        () ->
            approvals.approve(
                order, new PurchaseOrderApprovalDecisionRequest(""), approverA, departmentA, "ALL"));

    UUID payableId =
        as(
                "Bearer approver-a",
                () ->
                    sql.fetchOne("select id from purchase_order_payable where order_id=?::uuid", order)
                        .get(0, UUID.class));

    // 确认必须带财务单据号。
    assertThatThrownBy(
            () ->
                as(
                    "Bearer approver-a",
                    () -> approvals.confirmPayable(payableId, "  ", approverA)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("财务单据号");

    PurchaseOrderApprovalPayableView confirmed =
        as(
            "Bearer approver-a",
            () -> approvals.confirmPayable(payableId, "FIN-2026-0001", approverA));
    assertThat(confirmed.status()).isEqualTo("CONFIRMED");
    assertThat(confirmed.effectiveAmount()).isEqualByComparingTo("250.0000");
    assertThat(confirmed.registeredAmount()).isEqualByComparingTo("250.0000");
    assertThat(confirmed.financeReference()).isEqualTo("FIN-2026-0001");
    assertThat(confirmed.confirmedAt()).isNotNull();
  }

  // ================================================================ 留痕不可改

  @Test
  void 审批链路记录是追加式的() throws Exception {
    UUID order = seedSubmittedOrder(tenantA, buyerA, "PO-L-1", "USD", "100", "2.5");
    asApprove(
        "Bearer approver-a",
        () ->
            approvals.approve(
                order, new PurchaseOrderApprovalDecisionRequest("同意"), approverA, departmentA, "ALL"));

    // 🔴 追加式守卫：**超级用户也绕不开**（触发器不是权限，权限能收回，触发器不能）。
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "update purchase_order_approval_record set note='改一下' where order_id=?::uuid")) {
      ps.setObject(1, order);
      assertThatThrownBy(ps::executeUpdate).hasMessageContaining("不可改写");
    }
    // 运行角色连改写的**权限**都没有（UPDATE / DELETE 已收回）。
    try (var c = runtimeConnection();
        var ps =
            c.prepareStatement(
                "update purchase_order_approval_record set note='改一下' where order_id=?::uuid")) {
      ps.setObject(1, order);
      assertThatThrownBy(ps::executeUpdate).hasMessageContaining("purchase_order_approval_record");
    }
    try (var c = runtimeConnection();
        var ps =
            c.prepareStatement(
                "delete from purchase_order_approval_record where order_id=?::uuid")) {
      ps.setObject(1, order);
      assertThatThrownBy(ps::executeUpdate).hasMessageContaining("purchase_order_approval_record");
    }
  }

  // ================================================================ HTTP 层

  @Test
  void http审批链路跑得通() throws Exception {
    UUID order = seedSubmittedOrder(tenantA, buyerA, "PO-H-1", "USD", "100", "2.5");

    http.perform(
            post("/trade/purchase-order-approvals/orders/" + order + "/approve")
                .header("Authorization", "Bearer approver-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"同意\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orderStatus").value("APPROVED"))
        .andExpect(jsonPath("$.caseStatus").value("APPROVED"))
        .andExpect(jsonPath("$.payable.status").value("PENDING_FINANCE_CONFIRM"))
        .andExpect(jsonPath("$.receiptRelease.status").value("RELEASED"));

    http.perform(
            get("/trade/purchase-order-approvals/orders")
                .header("Authorization", "Bearer approver-a")
                .param("caseStatus", "APPROVED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].orderNo").value("PO-H-1"));
  }

  @Test
  void 能力声明说清哪些下游未交付() throws Exception {
    http.perform(get("/trade/purchase-order-approvals/capabilities").header("Authorization", "Bearer approver-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.capabilities[?(@.code=='identityApproval')].available").value(false))
        .andExpect(jsonPath("$.capabilities[?(@.code=='financeService')].available").value(false))
        .andExpect(jsonPath("$.capabilities[?(@.code=='batchApproval')].available").value(true));
  }
}
