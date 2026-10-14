package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * identity 集成测试的共享底座。
 *
 * <p>只放**跨测试类共用**的东西：真实 PostgreSQL 16 容器、Flyway 迁移、登录与请求辅助。 每个被测模块的断言留在自己的测试类里，避免一个类膨胀到几千行后没人敢改。
 *
 * <p>容器是 {@code static} 且只启动一次；两个测试类共享同一个数据库，靠 {@code suffix()} 生成的随机标记隔开各自的测试数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class IdentityIntegrationTestBase {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    pg.start();
    // Testcontainers 的 Ryuk 本来就会在 JVM 退出时回收容器；这里不再手工 stop，
    // 否则先结束的测试类会把共享容器停掉，后面所有测试假失败。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role identity_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "identity_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("LOCAL_BOOTSTRAP_ENABLED", () -> "true");
    r.add("LOCAL_ADMIN_PASSWORD", () -> "test-admin-password");
    // 池刻意压到 1：这样「连接归还后不串租户」才能被真正验证——
    // 每个请求必然复用同一条物理连接，租户设置如果泄漏到连接上，第二个租户就会看到第一个租户的数据。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired MockMvc http;
  @Autowired ObjectMapper json;
  @Autowired org.jooq.DSLContext sql;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;

  static PostgreSQLContainer<?> pg() {
    return pg;
  }

  // 不使用 @AfterAll 停容器。
  //
  // 容器由 static 初始化块启动一次、被所有子类共享；若把 @AfterAll 放在基类，
  // 它会被每个子类各自继承并执行——先跑完的测试类会把容器停掉，后面的类全部失败，
  // 且症状表现为业务断言不符（数据半截、租户深度超限），极难定位。
  // 改由 JVM 退出钩子统一回收：容器生命周期 = JVM 生命周期。

  /**
   * 发一次请求并断言状态码，返回解析后的响应体。
   *
   * <p>每次都带一个随机 {@code X-Tenant-Id}：租户必须来自已认证成员关系， 这个头对判定没有任何影响，测试里保留它正是为了证明这一点。
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
    var response = http.perform(builder).andExpect(status().is(expected)).andReturn().getResponse();
    return response.getContentAsString().isEmpty()
        ? json.getNodeFactory().nullNode()
        : json.readTree(response.getContentAsString());
  }

  /** 以本地所有者身份登录，返回 Bearer token。 */
  String login() throws Exception {
    var result =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/auth/login")
                    .contentType("application/json")
                    .content("{\"username\":\"local-admin\",\"password\":\"test-admin-password\"}"))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
  }

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

  Map<String, Object> memberInput(String username, String dept, List<?> assignments) {
    return memberInput(username, dept, assignments, "test-member-password");
  }

  Map<String, Object> memberInput(
      String username, String dept, List<?> assignments, String password) {
    var input = new HashMap<String, Object>();
    input.put("username", username);
    input.put("password", password);
    input.put("status", "ACTIVE");
    input.put("departmentId", dept);
    input.put("assignments", assignments);
    return input;
  }

  Map<String, Object> legalEntityInput(String code, String currency) {
    var input = new HashMap<String, Object>();
    input.put("code", code);
    input.put("legalName", "测试法人 " + code);
    input.put("registrationCountry", "US");
    input.put("registrationNo", "REG-" + code);
    input.put("taxNo", "TAX-" + code);
    input.put("baseCurrency", currency);
    input.put("address", "1 Test Street");
    input.put("amountScale", 2);
    input.put("quantityScale", 3);
    input.put("rounding", "HALF_UP");
    input.put("status", "ACTIVE");
    return input;
  }

  Map<String, Object> departmentInput(String code, String name) {
    var input = new HashMap<String, Object>();
    input.put("code", code);
    input.put("name", name);
    return input;
  }

  Map<String, Object> departmentInput(String code, String name, UUID parent) {
    var input = departmentInput(code, name);
    input.put("parentId", parent);
    return input;
  }

  /** 部门输入 + 可选引用（所属法人 / 负责人），避免每个断言都手搓 HashMap。 */
  Map<String, Object> departmentInput(
      String code, String name, UUID parent, UUID legalEntity, UUID leader) {
    var input = departmentInput(code, name, parent);
    input.put("legalEntityId", legalEntity);
    input.put("leaderMembershipId", leader);
    return input;
  }

  UUID createDepartment(String token, String code, String name, UUID parent) throws Exception {
    return UUID.fromString(
        request(token, "POST", "/identity/departments", departmentInput(code, name, parent), 200)
            .asText());
  }

  /** 树里按 code 找节点，找不到返回 null，方便断言里直接取 children。 */
  JsonNode findNode(JsonNode nodes, String code) {
    for (JsonNode node : nodes) {
      if (code.equals(node.get("code").asText())) return node;
      var deeper = findNode(node.get("children"), code);
      if (deeper != null) return deeper;
    }
    return null;
  }
}
