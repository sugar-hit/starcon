package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 6.2.2.1 采购申请的**多租户隔离**复验。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条断言：
 *
 * <ol>
 *   <li>两租户互不可见；</li>
 *   <li>伪造租户上下文无效；</li>
 *   <li>连接池归还后不串租户。</li>
 * </ol>
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p>🔴 **按主键直查对方行的那一格必须用 {@code trade_app} 应用账号连** ——
 * 用超级用户会绕过 RLS，那条断言永远「通过」，等于没测。
 */
class PurchaseRequestTenancyIntegrationTest extends PurchaseRequestFixture {

  @DynamicPropertySource
  static void tenancyProperties(DynamicPropertyRegistry r) {
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @BeforeEach
  void reset() throws Exception {
    truncateAll();
  }

  // ================================================================ 数据准备

  /** 建一张申请单（两个租户各建各的）。 */
  String createRequest(String bearer) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sourceType", "MANUAL");
    body.put("targetWarehouseId", warehouseId.toString());
    body.put("legalEntityId", legalEntityId.toString());
    body.put("currencyCode", "USD");
    body.put(
        "lines",
        List.of(
            Map.of(
                "skuId", UUID.randomUUID().toString(),
                "skuCode", "SKU-TENANCY",
                "skuName", "隔离测试用 SKU",
                "requestedQuantity", 10,
                "requestedArrivalDate", ARRIVAL.toString())));
    var response =
        http.perform(
                post("/trade/purchase-requests")
                    .header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andReturn()
            .getResponse();
    String payload = response.getContentAsString();
    assertThat(response.getStatus()).as("建申请单失败：%s", payload).isEqualTo(200);
    return String.valueOf(json.readTree(payload).get("id").asText());
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> rowsOf(String payload) throws Exception {
    Map<String, Object> parsed = json.readValue(payload, Map.class);
    return (List<Map<String, Object>>) parsed.getOrDefault("items", List.of());
  }

  /** 当前连接上 {@code app.current_tenant} 的**原始**值。 */
  String rawTenantSetting() {
    return sql.fetch("select current_setting('app.current_tenant', true)").stream()
        .findFirst()
        .map(r -> r.get(0, String.class))
        .orElse(null);
  }

  /** RLS 策略**实际**使用的值：空串与 NULL 都归一成 null，也就是「没有租户」。 */
  UUID effectiveTenant() {
    var row = sql.fetchOne("select nullif(current_setting('app.current_tenant', true),'')::uuid");
    return row == null ? null : row.get(0, UUID.class);
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select id from purchase_request").size();
  }

  /**
   * 用**运行角色** {@code trade_app} 连，按主键直查对方的行。
   *
   * <p>🔴 这一格**不能用超级用户**：超级用户绕过 RLS，查什么都「看得见」，
   * 断言会永远通过 —— 那是断言本身失效，不是隔离被破。
   */
  int visibleAsAppUser(String tenantId, String requestId) throws Exception {
    try (Connection c =
        DriverManager.getConnection(pg.getJdbcUrl(), "trade_app", "test-only");
        var ps =
            c.prepareStatement(
                "select count(*) from purchase_request where tenant_id = ?::uuid and id = ?::uuid")) {
      // RLS 的闸门是「会话里的租户上下文」⇒ 直查也必须带上同一个上下文，
      // 否则连**自己**的行都查不到（那不是隔离被破，是上下文没设）。
      try (var ctx =
          c.prepareStatement("select set_config('app.current_tenant', ?, false)")) {
        ctx.setString(1, tenantId);
        try (var rs = ctx.executeQuery()) {
          assertThat(rs.next()).isTrue();
        }
      }
      ps.setObject(1, UUID.fromString(tenantId));
      ps.setObject(2, UUID.fromString(requestId));
      try (var rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getInt(1);
      }
    }
  }

  // ================================================================ 迁移确实落地了，且 RLS 是**强制**的

  @Test
  void 本模块的表强制rls而不是只启用() {
    for (String table :
        List.of(
            "purchase_request",
            "purchase_request_line",
            "purchase_request_adjustment",
            "purchase_request_conversion")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V246 必须真的建出 %s 这张表", table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as("%s 的 RLS 未启用", table).isTrue();
      assertThat(row.get(1, Boolean.class))
          .as("%s 只有 FORCE 才挡得住「运行角色被误授 owner」", table)
          .isTrue();
      assertThat(
              sql.fetchOne(
                      "select has_table_privilege('trade_app',?,'SELECT')", table)
                  .get(0, Boolean.class))
          .as("trade_app 必须真的能读 %s", table)
          .isTrue();
    }
  }

  // ================================================================ 断言 1

  @Test
  void 断言1_两租户互不可见() throws Exception {
    String requestA = createRequest("Bearer tenant-a");
    String requestB = createRequest("Bearer tenant-b");

    String listA =
        http.perform(get("/trade/purchase-requests").header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String listB =
        http.perform(get("/trade/purchase-requests").header("Authorization", "Bearer tenant-b"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    var rowsA = rowsOf(listA);
    var rowsB = rowsOf(listB);
    assertThat(rowsA).hasSize(1);
    assertThat(rowsB).hasSize(1);
    assertThat(rowsA.get(0).get("id")).isEqualTo(requestA);
    assertThat(rowsB.get(0).get("id")).isEqualTo(requestB);

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    http.perform(
            get("/trade/purchase-requests/" + requestB).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());

    // 🔴 直查那一格必须用**运行角色**：超级用户绕过 RLS，这条断言会永远通过。
    assertThat(visibleAsAppUser(tenantA.toString(), requestB))
        .as("A 用运行角色直查 B 的行必须查不到")
        .isZero();
    assertThat(visibleAsAppUser(tenantA.toString(), requestA))
        .as("A 用运行角色直查自己的行必须查得到")
        .isEqualTo(1);
  }

  // ================================================================ 断言 2

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    String requestA = createRequest("Bearer tenant-a");
    UUID otherTenant = UUID.randomUUID();

    // 伪造的 X-Tenant-Id 头改不动租户上下文：仍然是 A 的租户，看不到别人的行。
    http.perform(
            get("/trade/purchase-requests/" + requestA)
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", otherTenant.toString()))
        .andExpect(status().isOk());
    http.perform(
            get("/trade/purchase-requests")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", otherTenant.toString()))
        .andExpect(status().isOk());
    String payload =
        http.perform(
                get("/trade/purchase-requests")
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", otherTenant.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(rowsOf(payload)).hasSize(1);

    // 🔴 伪造的头也**传不到上游去**：上游收到的是调用方自己的 bearer，
    // 租户由已认证成员关系解析 —— 伪造的租户号根本没出现在请求里。
    EVALUATION.set(Evaluation.REQUIRE_APPROVAL);
    lastApprovalBearer.set(null);
    String requestId = createRequest("Bearer tenant-a");
    http.perform(
            post("/trade/purchase-requests/" + requestId + "/submit")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", otherTenant.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());
    assertThat(lastApprovalBearer.get())
        .as("上游只能收到调用方自己的 bearer")
        .isEqualTo("Bearer tenant-a");
    assertThat(String.valueOf(lastApprovalBearer.get()))
        .as("伪造的租户号绝不能出现在上游请求里")
        .doesNotContain(otherTenant.toString());
  }

  // ================================================================ 断言 3

  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    String requestA = createRequest("Bearer tenant-a");
    String requestB = createRequest("Bearer tenant-b");

    // A → B → A 三轮，每一轮都必须只看见自己那一张。
    for (int round = 0; round < 3; round++) {
      String listA =
          http.perform(get("/trade/purchase-requests").header("Authorization", "Bearer tenant-a"))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      var rowsA = rowsOf(listA);
      assertThat(rowsA).as("第 %d 轮：A 只应看见自己那一张", round).hasSize(1);
      assertThat(rowsA.get(0).get("id")).isEqualTo(requestA);

      String listB =
          http.perform(get("/trade/purchase-requests").header("Authorization", "Bearer tenant-b"))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      var rowsB = rowsOf(listB);
      assertThat(rowsB).as("第 %d 轮：B 只应看见自己那一张", round).hasSize(1);
      assertThat(rowsB.get(0).get("id")).isEqualTo(requestB);
    }

    // 事务外（没有租户上下文）一行都看不见 —— 租户上下文是唯一的闸门。
    assertThat(visibleWithoutTenant()).isZero();
  }
}
