package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.trade.PurchaseFollowupModels.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 6.3.2.1 跟单与交期的<b>多租户三断言</b>。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且<b>实跑在本模块自己的三张表上</b>
 * （purchase_followup_record / purchase_delivery_change / purchase_delivery_writeback），
 * <b>不是</b>占位探针表 —— 探针表能证明 RLS 开着，证明不了<b>本模块的查询本身</b>没漏租户谓词。
 *
 * <p><b>本模块尤其不能省隔离</b>：跟单记录里同时有<b>供应商名、沟通内容、供应商答复、
 * 承诺到货日变更原因</b> —— 一个租户能看到另一个租户「向某供应商催货、供应商说了什么、
 * 交期为什么延后」，拿到的是<b>对方的采购底牌与供应链内幕</b>；
 * 端点又大多是<b>按单号直达</b>的 URL，猜到一个单号就能读，不需要翻列表。
 *
 * <p><b>连接池固定为 1 条连接</b>（见底座）：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>每轮读写都新建实体</b>：否则测的是幂等而不是隔离 —— 复用上一轮的记录，
 * 第二轮断言通过只能说明「同一条记录没变」。
 */
class PurchaseFollowupTenancyIntegrationTest extends PurchaseFollowupFixture {

  @Autowired PurchaseFollowupService followups;

  @BeforeEach
  void clean() throws Exception {
    truncateAll();
    warehouseDown.set(false);
    catalogDown.set(false);
  }

  private PurchaseFollowupRecordQuery query(UUID actor, UUID dept, String scope) {
    return new PurchaseFollowupRecordQuery(
        null, null, null, null, null, null, null, 0, 50, actor, dept, scope);
  }

  // ==========================================================================
  // 断言一：两租户互不可见
  // ==========================================================================

  @Test
  void 两租户互不可见() throws Exception {
    seedOrder(
        tenantA, userA, departmentA, "PO-T-A", supplierA, "SUP-A", "供应商 A",
        today().plusDays(5), "IN_FULFILLMENT", OffsetDateTime.now(ZoneOffset.UTC));
    seedOrder(
        tenantB, userB, null, "PO-T-B", supplierB, "SUP-B", "供应商 B",
        today().plusDays(5), "IN_FULFILLMENT", OffsetDateTime.now(ZoneOffset.UTC));

    // A 租户登记一条跟单 + 一次交期变更；B 租户也各登记一条（内容不同）。
    asWrite("Bearer tenant-a", () -> followups.recordFollowup(
        new PurchaseFollowupRecordInput(
            "PO-T-A", null, null, PurchaseFollowupType.URGING, PurchaseFollowupChannel.PHONE,
            "A 租户的催单内容", null, OffsetDateTime.now(ZoneOffset.UTC), null),
        userA, "ALL", departmentA));
    asWrite("Bearer tenant-b", () -> followups.recordFollowup(
        new PurchaseFollowupRecordInput(
            "PO-T-B", null, null, PurchaseFollowupType.URGING, PurchaseFollowupChannel.PHONE,
            "B 租户的催单内容", null, OffsetDateTime.now(ZoneOffset.UTC), null),
        userB, "ALL", null));
    asWrite("Bearer tenant-a", () -> followups.recordChange(
        new PurchaseFollowupChangeInput(
            "PO-T-A", null, null, today().plusDays(5), today().plusDays(15),
            "A 租户：供应商产能排满", null),
        userA, "ALL", departmentA));
    asWrite("Bearer tenant-b", () -> followups.recordChange(
        new PurchaseFollowupChangeInput(
            "PO-T-B", null, null, today().plusDays(5), today().plusDays(20),
            "B 租户：海运晚点", null),
        userB, "ALL", null));

    // 每轮都新建实体：复用上一轮的记录，第二轮断言通过只能说明「同一条记录没变」。
    var rowsA = as("Bearer tenant-a", () -> followups.listFollowups(query(userA, departmentA, "ALL")));
    assertThat(rowsA.total()).isEqualTo(1);
    assertThat(rowsA.rows().get(0).purchaseOrderNo()).isEqualTo("PO-T-A");
    assertThat(rowsA.rows().get(0).content()).contains("A 租户");
    assertThat(rowsA.rows().get(0).supplierCode()).isEqualTo("SUP-A");

    var rowsB = as("Bearer tenant-b", () -> followups.listFollowups(query(userB, null, "ALL")));
    assertThat(rowsB.total()).isEqualTo(1);
    assertThat(rowsB.rows().get(0).purchaseOrderNo()).isEqualTo("PO-T-B");
    assertThat(rowsB.rows().get(0).content()).contains("B 租户");
    assertThat(rowsB.rows().get(0).supplierCode()).isEqualTo("SUP-B");

    // 交期变更历史同样互不可见，**原因也不串**。
    var changesA = as("Bearer tenant-a", () -> followups.listChanges(
        new PurchaseFollowupChangeQuery(
            null, null, null, null, null, 0, 50, userA, departmentA, "ALL")));
    assertThat(changesA.total()).isEqualTo(1);
    assertThat(changesA.rows().get(0).reason()).contains("A 租户");
    assertThat(changesA.rows().get(0).toPromisedDate()).isEqualTo(today().plusDays(15));

    var changesB = as("Bearer tenant-b", () -> followups.listChanges(
        new PurchaseFollowupChangeQuery(
            null, null, null, null, null, 0, 50, userB, null, "ALL")));
    assertThat(changesB.total()).isEqualTo(1);
    assertThat(changesB.rows().get(0).reason()).contains("B 租户");
    assertThat(changesB.rows().get(0).toPromisedDate()).isEqualTo(today().plusDays(20));

    // 延迟统计也互不可见。
    var statsA = as("Bearer tenant-a", () -> followups.stats(null, null, false, false));
    var statsB = as("Bearer tenant-b", () -> followups.stats(null, null, false, false));
    assertThat(statsA.changedOrderCount()).isEqualTo(1);
    assertThat(statsB.changedOrderCount()).isEqualTo(1);
    assertThat(statsA.changeCount()).isEqualTo(1);
    assertThat(statsB.changeCount()).isEqualTo(1);

    // ⚠️ **写入口也拦住**：不能替别人的单子跟单、不能替别人改交期。
    assertThatThrownBy(
            () ->
                asWrite("Bearer tenant-a", () -> followups.recordFollowup(
                    new PurchaseFollowupRecordInput(
                        "PO-T-B", null, null, PurchaseFollowupType.URGING,
                        PurchaseFollowupChannel.PHONE, "越权跟单", null,
                        OffsetDateTime.now(ZoneOffset.UTC), null),
                    userA, "ALL", departmentA)))
        .hasMessageContaining("不存在或不在当前数据范围内");

    // B 的单没被动过。
    var afterB = as("Bearer tenant-b", () -> followups.listFollowups(query(userB, null, "ALL")));
    assertThat(afterB.total()).isEqualTo(1);
    assertThat(afterB.rows().get(0).content()).contains("B 租户");

    // HTTP 层同样如此（按单号直达的 URL 是最容易被猜到的那种）。
    http.perform(get("/trade/purchase-followups").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].purchaseOrderNo").value("PO-T-A"));
  }

  // ==========================================================================
  // 断言二：伪造 tenant header 无效
  // ==========================================================================

  @Test
  void 伪造租户头无效() throws Exception {
    seedOrder(
        tenantA, userA, departmentA, "PO-T-F1", supplierA, "SUP-A", "供应商 A",
        today().plusDays(5), "IN_FULFILLMENT", OffsetDateTime.now(ZoneOffset.UTC));
    seedOrder(
        tenantB, userB, null, "PO-T-F2", supplierB, "SUP-B", "供应商 B",
        today().plusDays(5), "IN_FULFILLMENT", OffsetDateTime.now(ZoneOffset.UTC));
    asWrite("Bearer tenant-a", () -> followups.recordFollowup(
        new PurchaseFollowupRecordInput(
            "PO-T-F1", null, null, PurchaseFollowupType.URGING, PurchaseFollowupChannel.PHONE,
            "A 的内容", null, OffsetDateTime.now(ZoneOffset.UTC), null),
        userA, "ALL", departmentA));
    asWrite("Bearer tenant-b", () -> followups.recordFollowup(
        new PurchaseFollowupRecordInput(
            "PO-T-F2", null, null, PurchaseFollowupType.URGING, PurchaseFollowupChannel.PHONE,
            "B 的内容", null, OffsetDateTime.now(ZoneOffset.UTC), null),
        userB, "ALL", null));

    // 带着 **B 的令牌** + **伪造的 A 的 X-Tenant-Id**：读到的必须是 B 的数据，
    // 不是 A 的 —— 头改不动 identity 解析出来的可信租户。
    String list =
        http.perform(get("/trade/purchase-followups")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", tenantA.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(list).contains("PO-T-F2").doesNotContain("PO-T-F1");

    // 反过来：带着 A 的令牌 + 伪造的 B 的头，读到的仍然是 A 的数据。
    String list2 =
        http.perform(get("/trade/purchase-followups")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", tenantB.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(list2).contains("PO-T-F1").doesNotContain("PO-T-F2");

    // 直接在 RLS 上验证：绑定 A 的租户之后，B 的行在库里**读不出来**。
    assertThat(
            as(
                    "Bearer tenant-a",
                    () ->
                        sql.fetchOne(
                                "select count(*) from purchase_followup_record where tenant_id=?::uuid",
                                tenantB)
                            .get(0, Long.class)))
        .as("RLS 之下看不到别的租户的行")
        .isZero();
    assertThat(
            as(
                    "Bearer tenant-a",
                    () ->
                        sql.fetchOne(
                                "select count(*) from purchase_delivery_change where tenant_id=?::uuid",
                                tenantB)
                            .get(0, Long.class)))
        .as("RLS 之下看不到别的租户的交期变更")
        .isZero();

    // ⚠️ **即使不带 tenant_id 谓词**也读不到（证明是真隔离，不是应用层过滤）。
    assertThat(
            as(
                    "Bearer tenant-a",
                    () ->
                        sql.fetchOne("select count(*) from purchase_followup_record").get(0, Long.class)))
        .as("不带租户谓词也读不到别人的行")
        .isEqualTo(1L);
  }

  // ==========================================================================
  // 断言三：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 连接池归还后不串租户() throws Exception {
    seedOrder(
        tenantA, userA, departmentA, "PO-T-P1", supplierA, "SUP-A", "供应商 A",
        today().plusDays(5), "IN_FULFILLMENT", OffsetDateTime.now(ZoneOffset.UTC));
    seedOrder(
        tenantB, userB, null, "PO-T-P2", supplierB, "SUP-B", "供应商 B",
        today().plusDays(5), "IN_FULFILLMENT", OffsetDateTime.now(ZoneOffset.UTC));
    asWrite("Bearer tenant-a", () -> followups.recordFollowup(
        new PurchaseFollowupRecordInput(
            "PO-T-P1", null, null, PurchaseFollowupType.URGING, PurchaseFollowupChannel.PHONE,
            "A 的内容", null, OffsetDateTime.now(ZoneOffset.UTC), null),
        userA, "ALL", departmentA));
    asWrite("Bearer tenant-b", () -> followups.recordFollowup(
        new PurchaseFollowupRecordInput(
            "PO-T-P2", null, null, PurchaseFollowupType.URGING, PurchaseFollowupChannel.PHONE,
            "B 的内容", null, OffsetDateTime.now(ZoneOffset.UTC), null),
        userB, "ALL", null));

    // 交替借还：同一张单反复以两个租户的身份读，读到的永远是**当前绑定**的租户。
    for (int i = 0; i < 3; i++) {
      assertThat(
              as("Bearer tenant-a", () -> followups.listFollowups(query(userA, departmentA, "ALL")))
                  .rows()
                  .get(0)
                  .supplierCode())
          .isEqualTo("SUP-A");
      assertThat(
              as("Bearer tenant-b", () -> followups.listFollowups(query(userB, null, "ALL")))
                  .rows()
                  .get(0)
                  .supplierCode())
          .isEqualTo("SUP-B");
      // 归还之后再读另一个租户的单：仍然读不到。
      assertThat(
              as("Bearer tenant-a", () -> followups.listFollowups(query(userA, departmentA, "ALL")))
                  .rows())
          .extracting(PurchaseFollowupRecordRow::purchaseOrderNo)
          .containsExactly("PO-T-P1");
      assertThat(
              as("Bearer tenant-b", () -> followups.listFollowups(query(userB, null, "ALL")))
                  .rows())
          .extracting(PurchaseFollowupRecordRow::purchaseOrderNo)
          .containsExactly("PO-T-P2");
    }

    // 写也在交替中完成，两个租户的结论互不污染。
    asWrite("Bearer tenant-a", () -> followups.recordChange(
        new PurchaseFollowupChangeInput(
            "PO-T-P1", null, null, today().plusDays(5), today().plusDays(12),
            "A 改期", null),
        userA, "ALL", departmentA));
    asWrite("Bearer tenant-b", () -> followups.recordChange(
        new PurchaseFollowupChangeInput(
            "PO-T-P2", null, null, today().plusDays(5), today().plusDays(18),
            "B 改期", null),
        userB, "ALL", null));

    var changesA = as("Bearer tenant-a", () -> followups.listChanges(
        new PurchaseFollowupChangeQuery(
            null, null, null, null, null, 0, 50, userA, departmentA, "ALL")));
    assertThat(changesA.rows().get(0).reason()).contains("A 改期");
    var changesB = as("Bearer tenant-b", () -> followups.listChanges(
        new PurchaseFollowupChangeQuery(
            null, null, null, null, null, 0, 50, userB, null, "ALL")));
    assertThat(changesB.rows().get(0).reason()).contains("B 改期");
  }

  // ==========================================================================
  // 附加：数据范围（SELF）也在本模块生效
  // ==========================================================================

  @Test
  void 数据范围SELF只看得到本人负责的单() throws Exception {
    // 同租户两张单：一张负责人是 userA，一张是别人。
    seedOrder(
        tenantA, userA, departmentA, "PO-SELF-1", supplierA, "SUP-A", "供应商 A",
        today().plusDays(5), "IN_FULFILLMENT", OffsetDateTime.now(ZoneOffset.UTC));
    seedOrder(
        tenantA, userC, departmentA, "PO-SELF-2", supplierA, "SUP-A", "供应商 A",
        today().plusDays(5), "IN_FULFILLMENT", OffsetDateTime.now(ZoneOffset.UTC));
    asWrite("Bearer tenant-a", () -> followups.recordFollowup(
        new PurchaseFollowupRecordInput(
            "PO-SELF-1", null, null, PurchaseFollowupType.URGING, PurchaseFollowupChannel.PHONE,
            "我负责的单", null, OffsetDateTime.now(ZoneOffset.UTC), null),
        userA, "ALL", departmentA));
    asWrite("Bearer tenant-a", () -> followups.recordFollowup(
        new PurchaseFollowupRecordInput(
            "PO-SELF-2", null, null, PurchaseFollowupType.URGING, PurchaseFollowupChannel.PHONE,
            "别人负责的单", null, OffsetDateTime.now(ZoneOffset.UTC), null),
        userC, "ALL", departmentA));

    var all = as("Bearer tenant-a", () -> followups.listFollowups(query(userA, departmentA, "ALL")));
    assertThat(all.total()).isEqualTo(2);

    var self = as("Bearer self-a", () -> followups.listFollowups(query(userC, departmentA, "SELF")));
    assertThat(self.total()).isEqualTo(1);
    assertThat(self.rows().get(0).content()).contains("别人负责的单");
  }
}
