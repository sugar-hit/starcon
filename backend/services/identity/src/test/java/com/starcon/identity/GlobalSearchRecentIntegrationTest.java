package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 1.2.1.1 全局搜索框 —— 「最近搜索记录」的集成测试。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。</b>
 *
 * <p><b>本测试真正在守的东西</b>：
 *
 * <ol>
 *   <li><b>最近搜索是「个人」的</b>：同租户里的另一个成员<b>看不到</b>你的搜索记录 ——
 *       少了 {@code user_id} 那一层，最近搜索就变成了团队共用的一份轨迹。</li>
 *   <li><b>同一个关键词只留一行</b>：重复搜同一个词只刷新时刻，不新增行
 *       （列表要的是「最近搜过的 N 个不同关键词」）。</li>
 *   <li><b>多租户三断言</b>：两租户互不可见、伪造 {@code X-Tenant-Id} 头无效、
 *       连接池归还后不串租户。</li>
 * </ol>
 */
class GlobalSearchRecentIntegrationTest extends IdentityIntegrationTestBase {

  private TenantTransactions tx() {
    return new TenantTransactions(
        sql, new org.springframework.transaction.support.TransactionTemplate(manager));
  }

  private UUID tenantOf(String token) throws Exception {
    return UUID.fromString(
        request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText());
  }

  /** 租户事务内直查（RLS 生效）。 */
  private List<Record> rowsIn(UUID tenant, String statement, Object... bindings) {
    return tx().run(tenant, () -> List.copyOf(sql.fetch(statement, bindings)));
  }

  private String record(String token, String keyword, String domain, String primaryRef)
      throws Exception {
    return request(
            token,
            "POST",
            "/identity/global-search/recent",
            Map.of("keyword", keyword, "domain", domain, "primaryRef", primaryRef),
            200)
        .get("keyword")
        .asText();
  }

  private JsonNode list(String token) throws Exception {
    return request(token, "GET", "/identity/global-search/recent?limit=10", null, 200).get("entries");
  }

  // ================================================================ 行为

  @Test
  void 登记后能读回最近搜索() throws Exception {
    String token = login();
    String tag = suffix();
    record(token, "SKU-" + tag, "PRODUCT", "SKU-" + tag);

    JsonNode entries = list(token);
    assertThat(entries.isArray()).isTrue();
    assertThat(entries.toString()).contains("SKU-" + tag).contains("PRODUCT");
  }

  @Test
  void 同一个关键词重复登记只留一行() throws Exception {
    String token = login();
    String tag = suffix();
    String keyword = "SO-" + tag;
    record(token, keyword, "ORDER", "SO-1");
    record(token, keyword, "ORDER", "SO-1");

    int count = 0;
    for (JsonNode entry : list(token)) {
      if (keyword.equals(entry.get("keyword").asText())) count++;
    }
    assertThat(count).as("同一个关键词只能有一条").isEqualTo(1);
  }

  @Test
  void 关键词少于两个字符后端直接400() throws Exception {
    String token = login();
    request(token, "POST", "/identity/global-search/recent", Map.of("keyword", "S"), 400);
    request(token, "POST", "/identity/global-search/recent", Map.of("keyword", "  "), 400);
  }

  @Test
  void 未知的域被拒而不是静默存空() throws Exception {
    String token = login();
    request(
        token,
        "POST",
        "/identity/global-search/recent",
        Map.of("keyword", "SKU-UNKNOWN", "domain", "NOT_A_DOMAIN"),
        400);
  }

  @Test
  void 没有全局搜索权限返回403() throws Exception {
    String owner = login();
    String tag = suffix();
    UUID role =
        UUID.fromString(
            request(
                    owner,
                    "POST",
                    "/identity/roles",
                    Map.of(
                        "code",
                        "NS" + tag,
                        "name",
                        "无搜索权限 " + tag,
                        "status",
                        "ACTIVE",
                        "permissions",
                        List.of()),
                    200)
                .asText());
    request(
        owner,
        "POST",
        "/identity/members",
        memberInput(
            "ns-" + tag.toLowerCase(), null, List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
        200);
    String member = loginUser("ns-" + tag.toLowerCase());

    request(member, "GET", "/identity/global-search/recent?limit=10", null, 403);
    request(member, "POST", "/identity/global-search/recent", Map.of("keyword", "SKU-X"), 403);
  }

  @Test
  void 清空后列表为空() throws Exception {
    String token = login();
    String tag = suffix();
    record(token, "SKU-CLEAR-" + tag, "PRODUCT", "SKU-CLEAR-" + tag);
    request(token, "DELETE", "/identity/global-search/recent", null, 200);
    assertThat(list(token).toString()).doesNotContain("SKU-CLEAR-" + tag);
  }

  // ================================================================ 多租户三断言

  @Test
  void 断言1_两租户互不可见且同租户内不同成员互不可见() throws Exception {
    String parent = login();
    String tag = suffix();
    record(parent, "SKU-ISO-" + tag, "PRODUCT", "SKU-ISO-" + tag);

    // 子租户：另一个租户看不到父租户的记录。
    request(
        parent,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            "GSC" + tag,
            "name",
            "全局搜索子租户 " + tag,
            "ownerUsername",
            "gsc-" + tag.toLowerCase(),
            "ownerPassword",
            "test-member-password"),
        200);
    String child = loginUser("gsc-" + tag.toLowerCase());
    assertThat(list(child).toString()).doesNotContain("SKU-ISO-" + tag);

    // 同租户内的**另一个成员**：也看不到（最近搜索是个人偏好，不是团队共享轨迹）。
    UUID role =
        UUID.fromString(
            request(
                    parent,
                    "POST",
                    "/identity/roles",
                    Map.of(
                        "code",
                        "GR" + tag,
                        "name",
                        "只读角色 " + tag,
                        "status",
                        "ACTIVE",
                        "permissions",
                        List.of("identity.global_search.read")),
                    200)
                .asText());
    request(
        parent,
        "POST",
        "/identity/members",
        memberInput(
            "gr-" + tag.toLowerCase(),
            null,
            List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
        200);
    String other = loginUser("gr-" + tag.toLowerCase());
    assertThat(list(other).toString()).doesNotContain("SKU-ISO-" + tag);

    // 行确实在父租户名下。
    assertThat(rowsIn(tenantOf(parent), "select keyword from global_search_recent where keyword=?", "SKU-ISO-" + tag))
        .hasSize(1);
    assertThat(
            rowsIn(
                tenantOf(child),
                "select keyword from global_search_recent where keyword=?",
                "SKU-ISO-" + tag))
        .as("子租户按主键直查父租户的记录必须读到零行")
        .isEmpty();
  }

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    String parent = login();
    String tag = suffix();
    record(parent, "SKU-FORGED-" + tag, "PRODUCT", "SKU-FORGED-" + tag);

    request(
        parent,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            "GSF" + tag,
            "name",
            "全局搜索伪造租户 " + tag,
            "ownerUsername",
            "gsf-" + tag.toLowerCase(),
            "ownerPassword",
            "test-member-password"),
        200);
    String child = loginUser("gsf-" + tag.toLowerCase());

    String honest =
        http.perform(
                get("/identity/global-search/recent")
                    .header("Authorization", "Bearer " + parent)
                    .header("X-Tenant-Id", tenantOf(child).toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(honest).contains("SKU-FORGED-" + tag);
    // 伪造头之后仍然只看到自己的租户：写入落到会话租户名下。
    assertThat(honest).doesNotContain("GSF" + tag);
  }

  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    String parent = login();
    String tag = suffix();
    record(parent, "SKU-POOL-" + tag, "PRODUCT", "SKU-POOL-" + tag);

    request(
        parent,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            "GSP" + tag,
            "name",
            "全局搜索池 " + tag,
            "ownerUsername",
            "gsp-" + tag.toLowerCase(),
            "ownerPassword",
            "test-member-password"),
        200);
    String child = loginUser("gsp-" + tag.toLowerCase());
    record(child, "SKU-POOL-CHILD-" + tag, "PRODUCT", "SKU-POOL-CHILD-" + tag);

    // 池大小为 1：每次读写都复用同一条连接。
    for (int i = 0; i < 5; i++) {
      String parentList =
          http.perform(
                  get("/identity/global-search/recent")
                      .header("Authorization", "Bearer " + parent))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      String childList =
          http.perform(
                  get("/identity/global-search/recent")
                      .header("Authorization", "Bearer " + child))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(parentList).as("第 %d 轮父租户", i).contains("SKU-POOL-" + tag).doesNotContain("SKU-POOL-CHILD-" + tag);
      assertThat(childList).as("第 %d 轮子租户", i).contains("SKU-POOL-CHILD-" + tag).doesNotContain("\"SKU-POOL-" + tag + "\"");
    }
  }

  @Test
  void 记录表是强制RLS() throws Exception {
    Boolean enabled =
        sql.fetchOne("select relforcerowsecurity from pg_class where relname = 'global_search_recent'")
            .get(0, Boolean.class);
    assertThat(enabled).as("global_search_recent 必须是 FORCE RLS").isTrue();
  }

  @Test
  void 写接口接受application_json() throws Exception {
    String token = login();
    String tag = suffix();
    String body =
        json.writeValueAsString(Map.of("keyword", "SKU-JSON-" + tag, "domain", "PRODUCT"));
    http.perform(
            post("/identity/global-search/recent")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
  }
}
