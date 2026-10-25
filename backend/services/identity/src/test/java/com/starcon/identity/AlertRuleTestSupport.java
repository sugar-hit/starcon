package com.starcon.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 1.3.2.1 预警规则 —— 集成测试的**共用辅助**（**不含任何容器**）。
 *
 * <p>🔴 <b>这里刻意不持有容器</b>：容器由每个具体测试类**自己**持有并在
 * {@code @AfterAll} 里 {@code .stop()}。
 *
 * <p>本仓的 {@code IdentityIntegrationTestBase} 是一个**共享 static 容器**
 * （43 个测试类继承它），那是历史包袱 —— 容器只随 JVM 生命周期回收，
 * 累积到几百个测试类时会把机器压住。新写的测试类不再进那个坑：
 * 每个类一个自己的容器，用完就停。
 *
 * <p>所以本类是**纯辅助**：只有请求 / 登录 / 建租户 / 建成员这些夹具方法，
 * 容器与 {@code @DynamicPropertySource} 由子类各写各的。
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class AlertRuleTestSupport {

  @Autowired MockMvc http;
  @Autowired ObjectMapper json;
  @Autowired org.jooq.DSLContext sql;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;

  /** 租户事务助手（与生产代码同一条路径）。 */
  com.starcon.tenancy.TenantTransactions tx() {
    return new com.starcon.tenancy.TenantTransactions(
        sql, new org.springframework.transaction.support.TransactionTemplate(manager));
  }

  /**
   * 发一次请求并断言状态码，返回解析后的响应体。
   *
   * <p>每次都带一个随机 {@code X-Tenant-Id}：租户必须来自已认证成员关系，
   * 这个头对判定没有任何影响，测试里保留它正是为了证明这一点。
   */
  JsonNode request(String token, String method, String path, Object body, int expected)
      throws Exception {
    var builder =
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                org.springframework.http.HttpMethod.valueOf(method), path)
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", UUID.randomUUID())
            .contentType("application/json");
    if (body != null) builder.content(json.writeValueAsString(body));
    var response = http.perform(builder).andReturn().getResponse();
    String text = response.getContentAsString();
    assertThat(response.getStatus())
        .as("%s %s 期望 %d，实际 %d，响应体：%s", method, path, expected, response.getStatus(), text)
        .isEqualTo(expected);
    return text.isEmpty() ? json.getNodeFactory().nullNode() : json.readTree(text);
  }

  /** 断言状态码并返回响应体（不预设期望值，供「期望 403 / 404」的场景用）。 */
  JsonNode requestExpecting(String token, String method, String path, Object body, int expected)
      throws Exception {
    return request(token, method, path, body, expected);
  }

  private org.springframework.test.web.servlet.ResultMatcher status(int expected) {
    return org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(expected);
  }

  /** 以本地所有者（OWNER，拥有全部权限码）身份登录，返回 Bearer token。 */
  String login() throws Exception {
    var result =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/auth/login")
                    .contentType("application/json")
                    .content("{\"username\":\"local-admin\",\"password\":\"test-admin-password\"}"))
            .andExpect(status(200))
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
  }

  /** 以成员身份登录（成员口令固定）。 */
  String loginUser(String username) throws Exception {
    return request(
            "",
            "POST",
            "/auth/login",
            Map.of("username", username, "password", "test-member-password"),
            200)
        .get("token")
        .asText();
  }

  /** 随机标记，避免不同测试之间的编码撞唯一约束。 */
  String suffix() {
    return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
  }

  /** 当前会话所属租户 id。 */
  UUID tenantOf(String token) throws Exception {
    return UUID.fromString(
        request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText());
  }

  /** 另建一个子租户（用来验「两租户互不可见」）。 */
  String childTenant(String parentToken, String tag) throws Exception {
    return createTenant(parentToken, tag, "arc");
  }

  /**
   * 另建一个**独立租户**并返回它的 OWNER token。
   *
   * <p>🔴 <b>每个测试用例一个自己的租户</b>：本类的容器是**一个类一个**的，
   * 但同一个类里的各个测试方法仍然共用同一个容器、同一个数据库 ——
   * 如果大家都用 {@code local-admin} 那个租户，那么「A 测试建的规则」会出现在
   * 「B 测试的清单里」，断言就没法说「本租户还没有自定义规则」。
   * 每个用例一个子租户 = 每个用例一个干净的 {@code tenant_id}。
   */
  String isolatedTenant(String tag) throws Exception {
    return createTenant(login(), tag, "ari");
  }

  /** 建租户（编码与账号前缀按 prefix 区分，避免同一个编码用两次）。 */
  private String createTenant(String parentToken, String tag, String prefix) throws Exception {
    String username = "ar" + prefix + "-" + tag.toLowerCase();
    request(
        parentToken,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            prefix.toUpperCase() + tag,
            "name",
            "预警租户 " + prefix + tag,
            "ownerUsername",
            username,
            "ownerPassword",
            "test-member-password"),
        200);
    return loginUser(username);
  }

  /**
   * 在当前租户里建一个带指定权限与范围的成员，并返回它的登录 token。
   *
   * @param permissions 该角色持有的权限码
   * @param scopeMode 授权范围（ALL / DEPARTMENT / SELF）
   */
  String memberWith(
      String ownerToken, String tag, List<String> permissions, String scopeMode) throws Exception {
    UUID role = createRole(ownerToken, tag, permissions);
    addMember(ownerToken, "arm-" + tag.toLowerCase(), role, scopeMode, 200);
    return loginUser("arm-" + tag.toLowerCase());
  }

  /** 建一个角色，返回它的 id。 */
  UUID createRole(String ownerToken, String tag, List<String> permissions) throws Exception {
    return UUID.fromString(
        request(
                ownerToken,
                "POST",
                "/identity/roles",
                Map.of(
                    "code",
                    "AR" + tag,
                    "name",
                    "预警角色 " + tag,
                    "status",
                    "ACTIVE",
                    "permissions",
                    permissions),
                200)
            .asText());
  }

  /**
   * 在当前租户里加一个成员（可断言期望状态码）。
   *
   * @param scopeMode 授权范围（ALL / DEPARTMENT / SELF）
   */
  void addMember(
      String ownerToken, String username, UUID roleId, String scopeMode, int expected)
      throws Exception {
    Map<String, Object> member = new HashMap<>();
    member.put("username", username);
    member.put("password", "test-member-password");
    member.put("status", "ACTIVE");
    member.put("assignments", List.of(Map.of("roleId", roleId.toString(), "scopeMode", scopeMode)));
    request(ownerToken, "POST", "/identity/members", member, expected);
  }

  /** 在当前租户里建一个**没有任何权限**的成员（用来验 403）。 */
  String memberWithout(String ownerToken, String tag) throws Exception {
    return memberWith(ownerToken, tag, List.of(), "ALL");
  }
}
