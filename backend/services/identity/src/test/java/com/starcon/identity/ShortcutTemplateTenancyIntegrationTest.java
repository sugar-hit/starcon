package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

/**
 * 1.2.2.2 快捷入口配置 —— 多租户隔离的集成测试。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS。</b>
 *
 * <p>本模块有两张表：{@code shortcut_entry_template}（租户级模板）与
 * {@code user_shortcut_override}（个人覆盖项）。两张表都必须真隔离：
 *
 * <ol>
 *   <li><b>断言 1</b>：两租户互不可见（列表 + 按主键直查 + 写库正对照）。</li>
 *   <li><b>断言 2</b>：伪造 {@code X-Tenant-Id} 头无效 —— 租户来自已认证会话，
 *       伪造头改不动 {@code app.current_tenant}，写入仍然落在会话租户名下。</li>
 *   <li><b>断言 3</b>：连接池归还后不串租户（池大小压到 1，5 轮交替读写，每轮新建实体）。</li>
 * </ol>
 */
class ShortcutTemplateTenancyIntegrationTest extends IdentityIntegrationTestBase {

  private TenantTransactions tx() {
    return new TenantTransactions(
        sql, new org.springframework.transaction.support.TransactionTemplate(manager));
  }

  /** 租户事务内直查（RLS 生效）。 */
  private List<Record> rowsIn(UUID tenant, String statement, Object... bindings) {
    return tx().run(tenant, () -> List.copyOf(sql.fetch(statement, bindings)));
  }

  private UUID tenantOf(String token) throws Exception {
    return UUID.fromString(
        request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText());
  }

  /** 建一张全员模板。 */
  private UUID createTemplate(String token, String code) throws Exception {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("key", "trade.orders");
    entry.put("label", "订单");
    entry.put("pageCode", "trade.orders");
    entry.put("path", "/orders");
    entry.put("position", 10);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("name", "模板 " + code);
    body.put("description", "");
    body.put("scopeType", "TENANT");
    body.put("status", "ACTIVE");
    body.put("priority", 10);
    body.put("entries", List.of(entry));

    return UUID.fromString(
        request(token, "POST", "/identity/shortcut-templates", body, 200).get("id").asText());
  }

  /** 建一个子租户并返回它的所有者会话。 */
  private String childTenant(String tag) throws Exception {
    String parent = login();
    request(
        parent,
        "POST",
        "/identity/children",
        Map.of(
            "code", "SC" + tag,
            "name", "快捷入口子租户 " + tag,
            "ownerUsername", "sc-" + tag.toLowerCase(),
            "ownerPassword", "test-member-password"),
        200);
    return loginUser("sc-" + tag.toLowerCase());
  }

  // ================================================================ 断言 1

  @Test
  void 断言1_两租户互不可见() throws Exception {
    String parent = login();
    String tag = suffix();
    UUID templateId = createTemplate(parent, "iso-" + tag);

    String child = childTenant(tag);

    // 列表里看不到对方的模板。
    JsonNode childList = request(child, "GET", "/identity/shortcut-templates?limit=100", null, 200);
    assertThat(childList.toString()).as("子租户看不到父租户的模板").doesNotContain("iso-" + tag);

    // 按主键直查也读不到（不是「列表过滤掉了」，而是整行不可见）。
    request(child, "GET", "/identity/shortcut-templates/" + templateId, null, 404);

    // 生效集合里也没有对方的模板入口。
    JsonNode childEffective =
        request(child, "GET", "/identity/shortcut-templates/effective", null, 200);
    assertThat(childEffective.toString()).doesNotContain("iso-" + tag);

    // 正对照：这一行确实落在父租户名下。
    assertThat(
            rowsIn(
                tenantOf(parent),
                "select code from shortcut_entry_template where code = ?",
                "iso-" + tag))
        .as("模板行确实在父租户名下")
        .hasSize(1);
    assertThat(
            rowsIn(
                tenantOf(child),
                "select code from shortcut_entry_template where code = ?",
                "iso-" + tag))
        .as("子租户按主键直查父租户的模板必须读到零行")
        .isEmpty();
  }

  @Test
  void 断言1_个人覆盖项也跨租户不可见() throws Exception {
    String parent = login();
    String tag = suffix();
    createTemplate(parent, "ovr-" + tag);
    request(
        parent,
        "POST",
        "/identity/shortcut-templates/overrides",
        Map.of("targetKey", "trade.orders", "action", "REMOVE"),
        200);

    String child = childTenant(tag);

    JsonNode childOverrides =
        request(child, "GET", "/identity/shortcut-templates/overrides", null, 200);
    assertThat(childOverrides.toString()).as("覆盖项也是个人数据").doesNotContain("trade.orders");

    // 正对照：父租户自己的覆盖项看得见，且子租户的覆盖项表里没有父租户的行。
    JsonNode parentOverrides =
        request(parent, "GET", "/identity/shortcut-templates/overrides", null, 200);
    assertThat(parentOverrides.toString()).contains("trade.orders");
    assertThat(
            rowsIn(
                tenantOf(child),
                "select target_key from user_shortcut_override where target_key = ?",
                "trade.orders"))
        .isEmpty();
  }

  // ================================================================ 断言 2

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    String parent = login();
    String tag = suffix();
    createTemplate(parent, "forged-" + tag);

    String child = childTenant(tag);
    UUID childTenantId = tenantOf(child);

    // 带着**子租户**的租户头去访问父租户会话：仍然只看到父租户的数据。
    String honest =
        http.perform(
                get("/identity/shortcut-templates?limit=100")
                    .header("Authorization", "Bearer " + parent)
                    .header("X-Tenant-Id", childTenantId.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(honest).contains("forged-" + tag);
    assertThat(honest).as("伪造头不能把视图切到别的租户").doesNotContain("SC" + tag);

    // 伪造头之后再写一行：写入仍然落在会话租户名下（比对数据库，而不是只看状态码）。
    String code = "forged-write-" + tag;
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("key", "catalog.sku");
    entry.put("label", "SKU");
    entry.put("pageCode", "catalog.sku");
    entry.put("path", "/catalog/skus");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("name", "伪造头写入 " + tag);
    body.put("scopeType", "TENANT");
    body.put("status", "ACTIVE");
    body.put("priority", 10);
    body.put("entries", List.of(entry));
    http.perform(
            post("/identity/shortcut-templates")
                .header("Authorization", "Bearer " + parent)
                .header("X-Tenant-Id", childTenantId.toString())
                .contentType("application/json")
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());

    assertThat(
            rowsIn(tenantOf(parent), "select code from shortcut_entry_template where code = ?", code))
        .as("写入必须落在会话租户名下")
        .hasSize(1);
    assertThat(
            rowsIn(tenantOf(child), "select code from shortcut_entry_template where code = ?", code))
        .as("伪造头不能让行落到别的租户名下")
        .isEmpty();
  }

  // ================================================================ 断言 3

  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    String parent = login();
    String tag = suffix();
    String child = childTenant(tag);

    // 池大小是 1（测试底座刻意压的）：每次读写都复用同一条物理连接。
    for (int i = 0; i < 5; i++) {
      // 每轮都新建实体：否则测的是幂等，不是隔离。
      String parentCode = "pool-p-" + tag + "-" + i;
      String childCode = "pool-c-" + tag + "-" + i;
      createTemplate(parent, parentCode);
      createTemplate(child, childCode);

      String parentList =
          http.perform(
                  get("/identity/shortcut-templates?limit=100")
                      .header("Authorization", "Bearer " + parent))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      String childList =
          http.perform(
                  get("/identity/shortcut-templates?limit=100")
                      .header("Authorization", "Bearer " + child))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();

      assertThat(parentList).as("第 %d 轮父租户", i).contains(parentCode).doesNotContain(childCode);
      assertThat(childList).as("第 %d 轮子租户", i).contains(childCode).doesNotContain(parentCode);
    }
  }

  @Test
  void 断言3_覆盖项在连接池复用下也不串租户() throws Exception {
    String parent = login();
    String tag = suffix();
    String child = childTenant(tag);
    createTemplate(parent, "poolovr-" + tag);
    createTemplate(child, "poolovr-" + tag);

    for (int i = 0; i < 5; i++) {
      request(
          parent,
          "POST",
          "/identity/shortcut-templates/overrides",
          Map.of("targetKey", "trade.orders", "action", "UPSERT", "label", "父-" + i),
          200);
      request(
          child,
          "POST",
          "/identity/shortcut-templates/overrides",
          Map.of("targetKey", "trade.orders", "action", "UPSERT", "label", "子-" + i),
          200);

      String parentList =
          http.perform(
                  get("/identity/shortcut-templates/effective")
                      .header("Authorization", "Bearer " + parent))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      String childList =
          http.perform(
                  get("/identity/shortcut-templates/effective")
                      .header("Authorization", "Bearer " + child))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();

      assertThat(parentList).as("第 %d 轮父租户", i).contains("父-" + i).doesNotContain("子-" + i);
      assertThat(childList).as("第 %d 轮子租户", i).contains("子-" + i).doesNotContain("父-" + i);
    }
  }
}
