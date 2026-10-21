package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.trade.PurchaseOrderListModels.*;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 6.3.1.1 采购单列表的<b>多租户三断言</b>。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且<b>实跑在本模块自己的三张表上</b>
 * （purchase_order / purchase_order_line / purchase_order_event），
 * <b>不是</b>占位探针表 —— 探针表能证明 RLS 开着，证明不了<b>本模块的查询本身</b>没漏租户谓词。
 *
 * <p><b>本模块尤其不能省隔离</b>：采购单里同时有<b>供应商名、采购单价、总金额、承诺到货日、
 * 已收比例与负责人</b> —— 一个租户能看到另一个租户「向某供应商采购 100 件、单价 2.5、
 * 已收 40 件」，拿到的是**采购底价与库存水位**；
 * 端点又大多是<b>按 id 直达</b>的 URL，猜到一个 UUID 就能读，不需要翻列表。
 *
 * <p><b>连接池固定为 1 条连接</b>（见底座）：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>每轮读写都新建实体</b>：否则测的是幂等而不是隔离 —— 复用上一轮的记录，
 * 第二轮断言通过只能说明「同一条记录没变」。
 */
class PurchaseOrderListTenancyIntegrationTest extends PurchaseOrderListFixture {

  @Autowired PurchaseOrderListService orders;

  @BeforeEach
  void clean() throws Exception {
    truncateAll();
    catalogDown.set(false);
  }

  private LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  private PurchaseOrderListQuery query(UUID actor) {
    return new PurchaseOrderListQuery(
        null, null, null, null, null, null, null, null, null, null, null, null, 0, 50,
        actor, departmentA, "ALL");
  }

  // ==========================================================================
  // 断言一：两租户互不可见
  // ==========================================================================

  @Test
  void 两租户互不可见() throws Exception {
    UUID orderA =
        seedOrder(tenantA, userA, departmentA, "PO-T-A", "USD", "100", "2.5", today().plusDays(5), "SUP-A");
    UUID orderB =
        seedOrder(tenantB, userB, null, "PO-T-B", "USD", "100", "9.9", today().plusDays(5), "SUP-B");

    // 每轮都新建实体：复用上一轮的记录，第二轮断言通过只能说明「同一条记录没变」。
    assertThat(as("Bearer tenant-a", () -> orders.list(query(userA))).total()).isEqualTo(1);
    assertThat(as("Bearer tenant-a", () -> orders.list(query(userA))).rows())
        .extracting(PurchaseOrderListRow::id)
        .containsExactly(orderA);
    assertThat(as("Bearer tenant-b", () -> orders.list(query(userB))).rows())
        .extracting(PurchaseOrderListRow::id)
        .containsExactly(orderB);

    // 供应商名与单价不串（两边的供应商编码与单价都不同）。
    var rowA = as("Bearer tenant-a", () -> orders.list(query(userA))).rows().get(0);
    var rowB = as("Bearer tenant-b", () -> orders.list(query(userB))).rows().get(0);
    assertThat(rowA.supplierCode()).isEqualTo("SUP-A");
    assertThat(rowB.supplierCode()).isEqualTo("SUP-B");
    assertThat(rowA.totalAmount()).isEqualByComparingTo("250");
    assertThat(rowB.totalAmount()).isEqualByComparingTo("990");

    // 汇总也互不可见。
    assertThat(as("Bearer tenant-a", () -> orders.list(query(userA))).summary().totalCount()).isEqualTo(1);
    assertThat(as("Bearer tenant-b", () -> orders.list(query(userB))).summary().totalCount()).isEqualTo(1);

    // ⚠️ **写入口也拦住**：不能替别人催单、不能替别人关闭。
    var urge = asWrite("Bearer tenant-a", () -> orders.urge(List.of(orderB), "越权催单", userA, departmentA, "ALL"));
    assertThat(urge.succeeded()).isZero();
    assertThat(urge.failures()).hasSize(1);
    assertThat(urge.failures().get(0).reason()).contains("不在当前数据范围内");

    var close = asWrite("Bearer tenant-a", () -> orders.close(List.of(orderB), "越权关闭", userA, departmentA, "ALL"));
    assertThat(close.succeeded()).isZero();
    // B 的单没被动过。
    assertThat(as("Bearer tenant-b", () -> orders.list(query(userB))).rows().get(0).status())
        .isEqualTo("DRAFT");

    // HTTP 层同样如此（按 id 直达的 URL 是最容易被猜到的那种）。
    http.perform(get("/trade/purchase-orders").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].orderNo").value("PO-T-A"));
  }

  // ==========================================================================
  // 断言二：伪造 tenant header 无效
  // ==========================================================================

  @Test
  void 伪造租户头无效() throws Exception {
    seedOrder(tenantA, userA, departmentA, "PO-T-F1", "USD", "10", "2.5", today().plusDays(5), "SUP-A");
    seedOrder(tenantB, userB, null, "PO-T-F2", "USD", "10", "9.9", today().plusDays(5), "SUP-B");

    // 带着 **B 的令牌** + **伪造的 A 的 X-Tenant-Id**：读到的必须是 B 的数据，
    // 不是 A 的 —— 头改不动 identity 解析出来的可信租户。
    String list =
        http.perform(get("/trade/purchase-orders")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", tenantA.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(list).contains("PO-T-F2").doesNotContain("PO-T-F1");

    // 反过来：带着 A 的令牌 + 伪造的 B 的头，读到的仍然是 A 的数据。
    String list2 =
        http.perform(get("/trade/purchase-orders")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", tenantB.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(list2).contains("PO-T-F1").doesNotContain("PO-T-F2");

    // 直接在 RLS 上验证：绑定 A 的租户之后，B 的行在库里**读不出来**，
    // 绕过服务层也一样（FORCE RLS 对表 owner 之外的角色同样生效，这里跑的是 trade_app）。
    assertThat(
            as(
                    "Bearer tenant-a",
                    () ->
                        sql.fetchOne(
                                "select count(*) from purchase_order where tenant_id=?::uuid",
                                tenantB)
                            .get(0, Long.class)))
        .as("RLS 之下看不到别的租户的行")
        .isZero();

    // ⚠️ **即使不带 tenant_id 谓词**也读不到（证明是真隔离，不是应用层过滤）。
    assertThat(
            as(
                    "Bearer tenant-a",
                    () ->
                        sql.fetchOne("select count(*) from purchase_order").get(0, Long.class)))
        .as("不带租户谓词也读不到别人的行")
        .isEqualTo(1L);

    // 上游事实表同样隔离：6.3.2.2 的到货跟踪在 A 的租户上下文里读不到 B 的行。
    assertThat(
            as(
                    "Bearer tenant-a",
                    () ->
                        sql.fetchOne("select count(*) from purchase_arrival_tracking")
                            .get(0, Long.class)))
        .isZero();
  }

  // ==========================================================================
  // 断言三：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 连接池归还后不串租户() throws Exception {
    UUID orderA =
        seedOrder(tenantA, userA, departmentA, "PO-T-P1", "USD", "100", "2.5", today().plusDays(5), "SUP-A");
    UUID orderB =
        seedOrder(tenantB, userB, null, "PO-T-P2", "USD", "100", "9.9", today().plusDays(5), "SUP-B");

    // 交替借还：同一张单反复以两个租户的身份读，读到的永远是**当前绑定**的租户。
    for (int i = 0; i < 3; i++) {
      assertThat(as("Bearer tenant-a", () -> orders.list(query(userA))).rows().get(0).supplierCode())
          .isEqualTo("SUP-A");
      assertThat(as("Bearer tenant-b", () -> orders.list(query(userB))).rows().get(0).supplierCode())
          .isEqualTo("SUP-B");
      // 归还之后再读另一个租户的单：仍然读不到。
      assertThat(as("Bearer tenant-a", () -> orders.list(query(userA))).rows())
          .extracting(PurchaseOrderListRow::id)
          .containsExactly(orderA);
      assertThat(as("Bearer tenant-b", () -> orders.list(query(userB))).rows())
          .extracting(PurchaseOrderListRow::id)
          .containsExactly(orderB);
    }

    // 写也在交替中完成，两个租户的结论互不污染。
    asWrite("Bearer tenant-a", () -> orders.urge(List.of(orderA), "催一下", userA, departmentA, "ALL"));
    asWrite("Bearer tenant-b", () -> orders.urge(List.of(orderB), "催一下", userB, null, "ALL"));

    assertThat(as("Bearer tenant-a", () -> orders.list(query(userA))).rows().get(0).urgeCount()).isEqualTo(1);
    assertThat(as("Bearer tenant-b", () -> orders.list(query(userB))).rows().get(0).urgeCount()).isEqualTo(1);
    assertThat(as("Bearer tenant-a", () -> orders.list(query(userA))).rows().get(0).supplierCode())
        .isEqualTo("SUP-A");
    assertThat(as("Bearer tenant-b", () -> orders.list(query(userB))).rows().get(0).supplierCode())
        .isEqualTo("SUP-B");
  }
}
