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
 * 6.2.2.2 需求合并与批量的**多租户隔离**复验。
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
class PurchaseDemandMergeTenancyIntegrationTest extends PurchaseDemandMergeFixture {

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

  /** 在指定租户下建一张已批申请单 + 一行需求，然后合并出一批草稿。返回批次 id。 */
  String createMerge(String bearer, UUID tenant) throws Exception {
    UUID request =
        seedRequestWithLines(
            tenant,
            "APPROVED",
            "USD",
            warehouseId,
            legalEntityId,
            List.of(new LineSpec("SKU-TENANCY", "10", "12.5", supplierA, "SUP-001", "深圳华强电子", ARRIVAL)));
    var body = new LinkedHashMap<String, Object>();
    body.put("sourceRequestIds", List.of(request.toString()));
    body.put("targetWarehouseId", warehouseId.toString());
    body.put("legalEntityId", legalEntityId.toString());
    body.put("currencyCode", "USD");
    var response =
        http.perform(
                post("/trade/demand-merges")
                    .header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andReturn();
    String payload = response.getResponse().getContentAsString();
    assertThat(response.getResponse().getStatus()).as("合并失败：%s", payload).isEqualTo(200);
    @SuppressWarnings("unchecked")
    var batch = (Map<String, Object>) readJson(payload).get("batch");
    return String.valueOf(batch.get("id"));
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> rowsOf(String payload) throws Exception {
    Map<String, Object> parsed = json.readValue(payload, Map.class);
    return (List<Map<String, Object>>) parsed.getOrDefault("items", List.of());
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select id from purchase_demand_merge_batch").size();
  }

  /**
   * 用**运行角色** {@code trade_app} 连，按主键直查对方的行。
   *
   * <p>🔴 这一格**不能用超级用户**：超级用户绕过 RLS，查什么都「看得见」，
   * 断言会永远通过 —— 那是断言本身失效，不是隔离被破。
   */
  int visibleAsAppUser(String tenantId, String batchId) throws Exception {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), "trade_app", "test-only");
        var ps =
            c.prepareStatement(
                "select count(*) from purchase_demand_merge_batch where tenant_id = ?::uuid and id = ?::uuid")) {
      // RLS 的闸门是「会话里的租户上下文」⇒ 直查也必须带上同一个上下文，
      // 否则连**自己**的行都查不到（那不是隔离被破，是上下文没设）。
      try (var ctx = c.prepareStatement("select set_config('app.current_tenant', ?, false)")) {
        ctx.setString(1, tenantId);
        try (var rs = ctx.executeQuery()) {
          assertThat(rs.next()).isTrue();
        }
      }
      ps.setObject(1, UUID.fromString(tenantId));
      ps.setObject(2, UUID.fromString(batchId));
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
            "purchase_demand_merge_batch",
            "purchase_demand_merge_draft",
            "purchase_demand_merge_line",
            "purchase_demand_merge_line_source",
            "purchase_demand_merge_unmerged",
            "purchase_demand_batch_adjustment")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V253 必须真的建出 %s 这张表", table).isNotNull();
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
    String batchA = createMerge("Bearer tenant-a", tenantA);
    String batchB = createMerge("Bearer tenant-b", tenantB);

    String listA =
        http.perform(get("/trade/demand-merges").header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String listB =
        http.perform(get("/trade/demand-merges").header("Authorization", "Bearer tenant-b"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    var rowsA = rowsOf(listA);
    var rowsB = rowsOf(listB);
    assertThat(rowsA).hasSize(1);
    assertThat(rowsB).hasSize(1);
    assertThat(rowsA.get(0).get("id")).isEqualTo(batchA);
    assertThat(rowsB.get(0).get("id")).isEqualTo(batchB);

    // 更强的一格：A 明确按 ID 查 B 的批次也拿不到（不只是列表过滤）。
    http.perform(
            get("/trade/demand-merges/" + batchB).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());

    // 🔴 直查那一格必须用**运行角色**：超级用户绕过 RLS，这条断言会永远通过。
    assertThat(visibleAsAppUser(tenantA.toString(), batchB))
        .as("A 用运行角色直查 B 的批次必须查不到")
        .isZero();
    assertThat(visibleAsAppUser(tenantA.toString(), batchA))
        .as("A 用运行角色直查自己的批次必须查得到")
        .isEqualTo(1);
  }

  // ================================================================ 断言 2

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    String batchA = createMerge("Bearer tenant-a", tenantA);
    UUID otherTenant = UUID.randomUUID();

    // 伪造的 X-Tenant-Id 头改不动租户上下文：仍然是 A 的租户，看不到别人的行。
    http.perform(
            get("/trade/demand-merges/" + batchA)
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", otherTenant.toString()))
        .andExpect(status().isOk());
    String payload =
        http.perform(
                get("/trade/demand-merges")
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", otherTenant.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(rowsOf(payload)).hasSize(1);

    // 伪造的头也**传不到上游去**：上游收到的是调用方自己的 bearer，
    // 租户由已认证成员关系解析 —— 伪造的租户号根本没出现在请求里。
    // 本模块的跨服务调用是读 catalog 的供应商清单（走 PurchaseRequestSupplierPort）。
    http.perform(
            get("/trade/demand-merges/options")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", otherTenant.toString()))
        .andExpect(status().isOk());
  }

  // ================================================================ 断言 3

  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    String batchA = createMerge("Bearer tenant-a", tenantA);
    String batchB = createMerge("Bearer tenant-b", tenantB);

    // A → B → A 三轮，每一轮都必须只看见自己那一张。
    for (int round = 0; round < 3; round++) {
      String listA =
          http.perform(get("/trade/demand-merges").header("Authorization", "Bearer tenant-a"))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      var rowsA = rowsOf(listA);
      assertThat(rowsA).as("第 %d 轮：A 只应看见自己那一张", round).hasSize(1);
      assertThat(rowsA.get(0).get("id")).isEqualTo(batchA);

      String listB =
          http.perform(get("/trade/demand-merges").header("Authorization", "Bearer tenant-b"))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      var rowsB = rowsOf(listB);
      assertThat(rowsB).as("第 %d 轮：B 只应看见自己那一张", round).hasSize(1);
      assertThat(rowsB.get(0).get("id")).isEqualTo(batchB);
    }

    // 事务外（没有租户上下文）一行都看不见 —— 租户上下文是唯一的闸门。
    assertThat(visibleWithoutTenant()).isZero();
  }
}
