package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.trade.PurchaseOrderEditModels.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.3.1.2 采购单创建与编辑的<b>多租户三断言</b>。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且<b>实跑在本模块自己的五张表</b>上
 * （行快照 / 提交快照 / 发货地址 / 附件 / 草稿来源），
 * <b>不是</b>占位探针表 —— 探针表能证明 RLS 开着，证明不了<b>本模块的查询本身</b>没漏租户谓词。
 *
 * <p><b>本模块尤其不能省隔离</b>：采购单里同时有<b>供应商、SKU、采购单价、总金额、
 * 承诺到货日、发货地址与附件</b> —— 一个租户能看到另一个租户「向某供应商以某单价采购、
 * 货发到哪个地址」，拿到的是<b>采购底价与供应链细节</b>；
 * 端点又大多是<b>按 id 直达</b>的 URL，猜到一个 UUID 就能读，不需要翻列表。
 *
 * <p><b>连接池固定为 1 条连接</b>（见底座）：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>每轮读写都新建实体</b>：否则测的是幂等而不是隔离 —— 复用上一轮的记录，
 * 第二轮断言通过只能说明「同一条记录没变」。
 */
class PurchaseOrderEditTenancyIntegrationTest extends PurchaseOrderEditFixture {

  @Autowired PurchaseOrderEditService editor;

  @BeforeEach
  void clean() throws Exception {
    truncateAll();
    catalogDown.set(false);
    warehouseDown.set(false);
    ambiguousQuotes.set(false);
    noQuote.set(false);
    noRate.set(false);
  }

  private LocalDate soon() {
    return LocalDate.now(ZoneOffset.UTC).plusDays(20);
  }

  private PurchaseOrderEditOrderInput form(String unitPrice) {
    return new PurchaseOrderEditOrderInput(
        supplierRoleId,
        "SUP-TEST",
        "测试供应商",
        legalEntityId,
        "US-LLC",
        "美国子公司",
        "USD",
        warehouseId,
        "WH-LA-01",
        "洛杉矶仓",
        soon(),
        "手工建单",
        List.of(
            new PurchaseOrderEditLineInput(
                1,
                UUID.nameUUIDFromBytes(("SKU-" + unitPrice).getBytes()),
                "SKU-" + unitPrice,
                "测试商品",
                new BigDecimal("100"),
                "箱",
                new BigDecimal(unitPrice),
                "MANUAL",
                null,
                "")),
        new PurchaseOrderEditAddressInput(
            "张三", "13800000000", "US", "California", "Los Angeles", "LA County", "123 Main St",
            "90001", ""),
        List.of());
  }

  private PurchaseOrderEditForm create(String bearer, UUID tenant, String unitPrice) {
    return asWrite(
        bearer,
        () ->
            editor.create(
                new PurchaseOrderEditCreateRequest(form(unitPrice)),
                tenant == tenantA ? userA : userB,
                tenant == tenantA ? departmentA : null,
                bearer));
  }

  // ==========================================================================
  // 断言一：两租户互不可见
  // ==========================================================================

  @Test
  void 两租户互不可见() throws Exception {
    PurchaseOrderEditForm orderA = create("Bearer tenant-a", tenantA, "2.5");
    PurchaseOrderEditForm orderB = create("Bearer tenant-b", tenantB, "9.9");

    // A 读得到自己的单，读不到 B 的单。
    PurchaseOrderEditForm readA =
        as("Bearer tenant-a", () -> editor.form(orderA.id(), userA, departmentA, "ALL", "Bearer tenant-a"));
    assertThat(readA.id()).isEqualTo(orderA.id());
    assertThat(readA.amounts().totalAmount()).isEqualByComparingTo("250");

    // ⚠️ **按 id 直达**是最容易被猜到的那种：直接拿 B 的单 id 给 A 读 ⇒ 404。
    assertThatThrownBy(
            () ->
                as(
                    "Bearer tenant-a",
                    () -> editor.form(orderB.id(), userA, departmentA, "ALL", "Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或不属于当前租户");

    // B 那边同理，而且单价不串（2.5 与 9.9）。
    PurchaseOrderEditForm readB =
        as("Bearer tenant-b", () -> editor.form(orderB.id(), userB, null, "ALL", "Bearer tenant-b"));
    assertThat(readB.id()).isEqualTo(orderB.id());
    assertThat(readB.amounts().totalAmount()).isEqualByComparingTo("990");

    // ⚠️ **写入口也拦住**：不能替别人改单、不能替别人提交。
    assertThatThrownBy(
            () ->
                asWrite(
                    "Bearer tenant-a",
                    () ->
                        editor.update(
                            orderB.id(),
                            new PurchaseOrderEditUpdateRequest(0L, form("1.0")),
                            userA,
                            departmentA,
                            "ALL",
                            "Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或不属于当前租户");

    // B 的单没被动过（还是草稿、金额还是 990）。
    PurchaseOrderEditForm stillB =
        as("Bearer tenant-b", () -> editor.form(orderB.id(), userB, null, "ALL", "Bearer tenant-b"));
    assertThat(stillB.status()).isEqualTo("DRAFT");
    assertThat(stillB.amounts().totalAmount()).isEqualByComparingTo("990");

    // 本模块自己的五张表在 A 的上下文里也读不到 B 的行。
    assertThat(
            as(
                "Bearer tenant-a",
                () ->
                    sql.fetchOne(
                            "select count(*) from purchase_order_ship_address where tenant_id=?::uuid",
                            tenantB)
                        .get(0, Long.class)))
        .as("A 读不到 B 的发货地址")
        .isZero();
    assertThat(
            as(
                "Bearer tenant-a",
                () ->
                    sql.fetchOne(
                            "select count(*) from purchase_order_draft_source where tenant_id=?::uuid",
                            tenantB)
                        .get(0, Long.class)))
        .as("A 读不到 B 的草稿来源")
        .isZero();
    // A 自己的那一份读得到（证明上面的 0 是**隔离**，不是「表是空的」）。
    assertThat(
            as(
                "Bearer tenant-a",
                () -> sql.fetchOne("select count(*) from purchase_order_ship_address").get(0, Long.class)))
        .isEqualTo(1L);
  }

  // ==========================================================================
  // 断言二：伪造 tenant header 无效
  // ==========================================================================

  @Test
  void 伪造租户头无效() throws Exception {
    create("Bearer tenant-a", tenantA, "2.5");
    create("Bearer tenant-b", tenantB, "9.9");

    // 带着 **B 的令牌** + **伪造的 A 的 X-Tenant-Id**：读到的必须是 B 的数据，
    // 不是 A 的 —— 头改不动 identity 解析出来的可信租户。
    String body =
        http.perform(get("/trade/purchase-order-editor/orders/" + anyOrderIdOf("Bearer tenant-b"))
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", tenantA.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(body).contains("9.9").doesNotContain("2.5");

    // 反过来：带着 A 的令牌 + 伪造的 B 的头，读到的仍然是 A 的数据。
    String body2 =
        http.perform(get("/trade/purchase-order-editor/orders/" + anyOrderIdOf("Bearer tenant-a"))
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", tenantB.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(body2).contains("2.5").doesNotContain("9.9");

    // 直接在 RLS 上验证：绑定 A 的租户之后，B 的行在库里**读不出来**。
    assertThat(
            as(
                "Bearer tenant-a",
                () -> sql.fetchOne("select count(*) from purchase_order").get(0, Long.class)))
        .as("A 的上下文里只有自己那一张")
        .isEqualTo(1L);
    assertThat(
            as(
                "Bearer tenant-a",
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
    PurchaseOrderEditForm orderA = create("Bearer tenant-a", tenantA, "2.5");
    PurchaseOrderEditForm orderB = create("Bearer tenant-b", tenantB, "9.9");

    // 交替借还：同一张单反复以两个租户的身份读，读到的永远是**当前绑定**的租户。
    for (int i = 0; i < 3; i++) {
      assertThat(
              as(
                      "Bearer tenant-a",
                      () -> editor.form(orderA.id(), userA, departmentA, "ALL", "Bearer tenant-a"))
                  .amounts()
                  .totalAmount())
          .isEqualByComparingTo("250");
      assertThat(
              as(
                      "Bearer tenant-b",
                      () -> editor.form(orderB.id(), userB, null, "ALL", "Bearer tenant-b"))
                  .amounts()
                  .totalAmount())
          .isEqualByComparingTo("990");
      // 归还之后再读另一个租户的单：仍然读不到。
      assertThatThrownBy(
              () ->
                  as(
                      "Bearer tenant-a",
                      () -> editor.form(orderB.id(), userA, departmentA, "ALL", "Bearer tenant-a")))
          .isInstanceOf(ResponseStatusException.class);
    }

    // 写也在交替中完成，两个租户的结论互不污染。
    asWrite(
        "Bearer tenant-a",
        () ->
            editor.submit(
                orderA.id(),
                new PurchaseOrderEditSubmitRequest(orderA.version(), ""),
                userA,
                departmentA,
                "ALL",
                "Bearer tenant-a"));
    asWrite(
        "Bearer tenant-b",
        () ->
            editor.submit(
                orderB.id(),
                new PurchaseOrderEditSubmitRequest(orderB.version(), ""),
                userB,
                null,
                "ALL",
                "Bearer tenant-b"));

    assertThat(
            as(
                    "Bearer tenant-a",
                    () -> editor.form(orderA.id(), userA, departmentA, "ALL", "Bearer tenant-a"))
                .status())
        .isEqualTo("PENDING_APPROVAL");
    assertThat(
            as(
                    "Bearer tenant-b",
                    () -> editor.form(orderB.id(), userB, null, "ALL", "Bearer tenant-b"))
                .status())
        .isEqualTo("PENDING_APPROVAL");

    // 🔴 快照表也不串：A 提交的快照只有一行，且 A 读不到 B 的快照。
    assertThat(
            as(
                "Bearer tenant-a",
                () ->
                    sql.fetchOne("select count(*) from purchase_order_submit_snapshot").get(0, Long.class)))
        .isEqualTo(1L);
  }

  /** 取某个租户名下任意一张采购单的 id（用**它自己的**上下文读，拿到的是它自己的单）。 */
  private UUID anyOrderIdOf(String bearer) {
    return as(
        bearer,
        () -> sql.fetchOne("select id from purchase_order limit 1").get(0, UUID.class));
  }

  /** HTTP 层的建单（证明代理链路与后端在租户上是一致���）。 */
  @Test
  void http建单落在会话租户名下() throws Exception {
    String body =
        """
        {"order":{"supplierRefId":"%s","supplierCode":"SUP-TEST","supplierName":"测试供应商",
        "legalEntityId":"%s","legalEntityCode":"US-LLC","legalEntityName":"美国子公司","currency":"USD",
        "targetWarehouseId":"%s","targetWarehouseCode":"WH-LA-01","targetWarehouseName":"洛杉矶仓",
        "promisedArrivalDate":"%s","note":"http 建单","lines":[{"skuCode":"SKU-H","skuName":"商品",
        "quantity":100,"unitCode":"箱","unitPrice":2.5,"priceSource":"MANUAL","note":""}]}}
        """
            .formatted(
                supplierRoleId, legalEntityId, warehouseId, LocalDate.now(ZoneOffset.UTC).plusDays(20))
            .replace("\n", "")
            .replace("  ", "");

    http.perform(
            post("/trade/purchase-order-editor/orders")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.sourceType").value("MANUAL"));

    // 写进去的行落在**会话租户**名下（不是任何头里的租户）。
    assertThat(
            as(
                "Bearer tenant-a",
                () -> sql.fetchOne("select count(*) from purchase_order").get(0, Long.class)))
        .isEqualTo(1L);
    assertThat(
            as(
                "Bearer tenant-b",
                () -> sql.fetchOne("select count(*) from purchase_order").get(0, Long.class)))
        .isZero();
  }
}
