package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.trade.PurchaseOrderApprovalModels.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.3.1.3 采购单审批的<b>多租户三断言</b>。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且<b>实跑在本模块自己的表</b>上
 * （审批规则 / 采购员分级 / 审批人 / 审批单 / 审批链路 / 应付 / 收货放行），
 * <b>不是</b>占位探针表 —— 探针表能证明 RLS 开着，证明不了<b>本模块的查询本身</b>没漏租户谓词。
 *
 * <p><b>本模块尤其不能省隔离</b>：审批链路里同时有<b>供应商、采购单价、总金额、
 * 采购员分级、审批人是谁</b> —— 一个租户能看到另一个租户「谁批的、批了多少钱」，
 * 拿到的是**采购底价与内部审批口径**；端点又大多是<b>按 id 直达</b>的 URL，
 * 猜到一个 UUID 就能读，不需要翻列表。
 *
 * <p><b>连接池固定为 1 条连接</b>（见底座）：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>每轮读写都新建实体</b>：否则测的是幂等而不是隔离 —— 复用上一轮的记录，
 * 第二轮断言通过只能说明「同一条记录没变」。
 */
class PurchaseOrderApprovalTenancyIntegrationTest extends PurchaseOrderApprovalFixture {

  @Autowired PurchaseOrderApprovalService approvals;

  @BeforeEach
  void clean() throws Exception {
    truncateAll();
    // A 租户：采购员分级 + 规则 + 审批人。
    seedBuyer(tenantA, buyerA, "初级采购员A", "JUNIOR");
    seedRule(tenantA, "R-A", "JUNIOR", "USD", "0", null, "L2", 1);
    seedApprover(tenantA, approverA, "张经理", "L3", "ANY", "0", null);

    // B 租户：同样配一套（**不是**空租户 —— 隔离要在「配置齐了」时才算数）。
    seedBuyer(tenantB, buyerB, "初级采购员B", "JUNIOR");
    seedRule(tenantB, "R-B", "JUNIOR", "USD", "0", null, "L2", 1);
    seedApprover(tenantB, approverB, "张经理B", "L3", "ANY", "0", null);
  }

  // ==========================================================================
  // 断言一：两租户互不可见
  // ==========================================================================

  @Test
  void 两租户互不可见() throws Exception {
    UUID orderA = seedSubmittedOrder(tenantA, buyerA, "PO-A-1", "USD", "100", "2.5");
    UUID orderB = seedSubmittedOrder(tenantB, buyerB, "PO-B-1", "USD", "100", "9.9");

    // A 读得到自己的单，读不到 B 的单。
    PurchaseOrderApprovalDetail readA =
        as("Bearer approver-a", () -> approvals.detail(orderA, approverA, departmentA, "ALL"));
    assertThat(readA.orderId()).isEqualTo(orderA);
    assertThat(readA.orderAmount()).isEqualByComparingTo("250.0000");

    // ⚠️ **按 id 直达**是最容易被猜到的那种：直接拿 B 的单 id 给 A 读 ⇒ 404。
    assertThatThrownBy(
            () ->
                as(
                    "Bearer approver-a",
                    () -> approvals.detail(orderB, approverA, departmentA, "ALL")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或不属于当前租户");

    // B 那边同理，而且单价不串（2.5 与 9.9）。
    PurchaseOrderApprovalDetail readB =
        as("Bearer approver-b", () -> approvals.detail(orderB, approverB, null, "ALL"));
    assertThat(readB.orderId()).isEqualTo(orderB);
    assertThat(readB.orderAmount()).isEqualByComparingTo("990.0000");

    // ⚠️ **写入口也拦住**：不能替别人批单。
    assertThatThrownBy(
            () ->
                asApprove(
                    "Bearer approver-a",
                    () ->
                        approvals.approve(
                            orderB,
                            new PurchaseOrderApprovalDecisionRequest("越权批"),
                            approverA,
                            departmentA,
                            "ALL")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或不属于当前租户");

    // B 的单没被动过（还在待审批）。
    assertThat(
            as(
                "Bearer approver-b",
                () ->
                    sql.fetchOne("select status from purchase_order where id=?::uuid", orderB)
                        .get(0, String.class)))
        .isEqualTo("PENDING_APPROVAL");

    // 台账也只看得见自己租户的单。
    assertThat(
            as("Bearer approver-a", () -> approvals.list(null, null, null, null, null, 0, 20, approverA, departmentA, "ALL").total()))
        .isEqualTo(1L);
    assertThat(
            as("Bearer approver-b", () -> approvals.list(null, null, null, null, null, 0, 20, approverB, null, "ALL").total()))
        .isEqualTo(1L);

    // 本模块自己的表在 A 的上下文里也读不到 B 的行。
    assertThat(
            as(
                "Bearer approver-a",
                () ->
                    sql.fetchOne(
                            "select count(*) from purchase_order_approval_case where tenant_id=?::uuid",
                            tenantB)
                        .get(0, Long.class)))
        .as("A 读不到 B 的审批单")
        .isZero();
    assertThat(
            as(
                "Bearer approver-a",
                () ->
                    sql.fetchOne(
                            "select count(*) from purchase_order_approval_rule where tenant_id=?::uuid",
                            tenantB)
                        .get(0, Long.class)))
        .as("A 读不到 B 的审批规则")
        .isZero();
    // A 自己的那一份读得到（证明上面的 0 是**隔离**，不是「表是空的」）。
    assertThat(
            as(
                "Bearer approver-a",
                () ->
                    sql.fetchOne("select count(*) from purchase_order_approval_rule").get(0, Long.class)))
        .isEqualTo(1L);
  }

  // ==========================================================================
  // 断言二：伪造 tenant header 无效
  // ==========================================================================

  @Test
  void 伪造租户头无效() throws Exception {
    UUID orderA = seedSubmittedOrder(tenantA, buyerA, "PO-A-2", "USD", "100", "2.5");
    UUID orderB = seedSubmittedOrder(tenantB, buyerB, "PO-B-2", "USD", "100", "9.9");

    // 带着 **B 的令牌** + **伪造的 A 的 X-Tenant-Id**：读到的必须是 B 的数据，
    // 不是 A 的 —— 头改不动 identity 解析出来的可信租户。
    String body =
        http.perform(get("/trade/purchase-order-approvals/orders/" + orderB)
                .header("Authorization", "Bearer approver-b")
                .header("X-Tenant-Id", tenantA.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(body).contains("990.0000").doesNotContain("250.0000");

    // 反过来：带着 A 的令牌 + 伪造的 B 的头，读到的仍然是 A 的数据。
    String body2 =
        http.perform(get("/trade/purchase-order-approvals/orders/" + orderA)
                .header("Authorization", "Bearer approver-a")
                .header("X-Tenant-Id", tenantB.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(body2).contains("250.0000").doesNotContain("990.0000");

    // 🔴 **写路径也按会话租户走**：带着 A 的令牌 + 伪造的 B 的头去批 B 的单 ⇒ 批不到。
    http.perform(
            post("/trade/purchase-order-approvals/orders/" + orderB + "/approve")
                .header("Authorization", "Bearer approver-a")
                .header("X-Tenant-Id", tenantB.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"越权\"}"))
        .andExpect(status().isNotFound());

    // 直接在 RLS 上验证：绑定 A 的租户之后，B 的行在库里**读不出来**。
    assertThat(
            as(
                "Bearer approver-a",
                () -> sql.fetchOne("select count(*) from purchase_order").get(0, Long.class)))
        .as("A 的上下文里只有自己那一张")
        .isEqualTo(1L);
    assertThat(
            as(
                "Bearer approver-a",
                () ->
                    sql.fetchOne(
                            "select count(*) from purchase_order where tenant_id=?::uuid", tenantB)
                        .get(0, Long.class)))
        .as("RLS 之下看不到别的租户的行")
        .isZero();
  }

  // ==========================================================================
  // 断言三：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 连接池归还后不串租户() throws Exception {
    UUID orderA = seedSubmittedOrder(tenantA, buyerA, "PO-A-3", "USD", "100", "2.5");
    UUID orderB = seedSubmittedOrder(tenantB, buyerB, "PO-B-3", "USD", "100", "9.9");

    // 交替借还：同一张单反复以两个租户的身份读，读到的永远是**当前绑定**的租户。
    for (int i = 0; i < 3; i++) {
      assertThat(
              as(
                      "Bearer approver-a",
                      () -> approvals.detail(orderA, approverA, departmentA, "ALL"))
                  .orderAmount())
          .isEqualByComparingTo("250.0000");
      assertThat(
              as("Bearer approver-b", () -> approvals.detail(orderB, approverB, null, "ALL"))
                  .orderAmount())
          .isEqualByComparingTo("990.0000");
      // 归还之后再读另一个租户的单：仍然读不到。
      assertThatThrownBy(
              () ->
                  as(
                      "Bearer approver-a",
                      () -> approvals.detail(orderB, approverA, departmentA, "ALL")))
          .isInstanceOf(ResponseStatusException.class);
    }

    // 写也在交替中完成，两个租户的结论互不污染。
    asApprove(
        "Bearer approver-a",
        () ->
            approvals.approve(
                orderA,
                new PurchaseOrderApprovalDecisionRequest("A 批"),
                approverA,
                departmentA,
                "ALL"));
    asApprove(
        "Bearer approver-b",
        () ->
            approvals.approve(
                orderB,
                new PurchaseOrderApprovalDecisionRequest("B 批"),
                approverB,
                null,
                "ALL"));

    assertThat(
            as(
                    "Bearer approver-a",
                    () -> approvals.detail(orderA, approverA, departmentA, "ALL"))
                .orderStatus())
        .isEqualTo("APPROVED");
    assertThat(
            as("Bearer approver-b", () -> approvals.detail(orderB, approverB, null, "ALL"))
                .orderStatus())
        .isEqualTo("APPROVED");

    // 🔴 本模块自己的表也不串：A 的上下文里只有 A 的审批单与留痕。
    assertThat(
            as(
                "Bearer approver-a",
                () ->
                    sql.fetchOne("select count(*) from purchase_order_approval_case")
                        .get(0, Long.class)))
        .isEqualTo(1L);
    assertThat(
            as(
                "Bearer approver-a",
                () ->
                    sql.fetchOne("select count(*) from purchase_order_approval_record")
                        .get(0, Long.class)))
        .isEqualTo(1L);
    assertThat(
            as(
                "Bearer approver-a",
                () ->
                    sql.fetchOne("select count(*) from purchase_order_payable").get(0, Long.class)))
        .isEqualTo(1L);
    // ⚠️ 金额也不串：A 的应付是 250，B 的是 990。
    assertThat(
            as(
                "Bearer approver-a",
                () ->
                    sql.fetchOne("select registered_amount from purchase_order_payable")
                        .get(0, BigDecimal.class)))
        .isEqualByComparingTo("250.0000");
  }
}
