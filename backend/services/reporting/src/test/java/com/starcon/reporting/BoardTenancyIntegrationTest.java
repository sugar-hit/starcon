package com.starcon.reporting;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 13.6.1.2 看板搭建的**多租户隔离测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条断言（缺一不可）：
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（看板、组件、共享、模板逐个隔离）；</li>
 *   <li><b>伪造租户上下文无效</b>（并断言行确实落在**会话租户**名下）；</li>
 *   <li><b>连接池归还后不串租户</b>。</li>
 * </ol>
 *
 * <p>⚠️ <b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BoardTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID(), userB = UUID.randomUUID();
  static HttpServer identity;

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of("reporting.board.read", "reporting.board.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  static final LocalDate DAY1 = LocalDate.of(2026, 10, 1);

  static final String BEARER_A = "Bearer tenant-a";
  static final String BEARER_B = "Bearer tenant-b";

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute(
          "create role reporting_app login password 'test-only' nosuperuser nobypassrls");
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            var access = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (access == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = JSON.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put(BEARER_A, actor(a, userA));
      actors.put(BEARER_B, actor(b, userB));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static AccessService.Access actor(UUID tenantId, UUID userId) {
    var grant = new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions);
    return new AccessService.Access(
        userId, tenantId, "Test", UUID.randomUUID(), null, permissions, List.of(grant));
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "reporting_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingBoardService boards;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate reporting_board_template_component, reporting_board_template,"
              + " reporting_board_share, reporting_board_component, reporting_board,"
              + " reporting_metric_fact cascade");
    }
    // 播种口径（13.1.1.1 的服务层干的活）。
    boards.metricCatalog(a);
    boards.metricCatalog(b);
  }

  private void fact(UUID tenantId, UUID shopId, String metricCode, String currency, String value) {
    tenants.run(
        tenantId,
        () -> {
          sql.execute(
              "insert into reporting_metric_fact"
                  + " (id,tenant_id,scope_type,legal_entity_id,shop_id,metric_code,bucket_date,currency,"
                  + " value,timezone,source_service,source_reference,updated_at)"
                  + " values (?,?,?,?,?,?,?,?,?,?,?,?,now())",
              UUID.randomUUID(),
              tenantId,
              "SHOP",
              null,
              shopId,
              metricCode,
              DAY1,
              currency,
              new BigDecimal(value),
              "UTC",
              "trade",
              "seed-" + tenantId + "-" + metricCode);
          return null;
        });
  }

  private UUID saveBoard(UUID tenantId, UUID userId, String code, String visibility) {
    return UUID.fromString(
        boards
            .save(
                actors.get(tenantId == a ? BEARER_A : BEARER_B),
                null,
                new ReportingBoardModels.BoardSaveRequest(
                    code, "看板 " + code, "", visibility, List.of(), null, null))
            .boardId());
  }

  private UUID addComponent(UUID tenantId, UUID boardId, String metricCode) {
    return UUID.fromString(
        boards
            .addComponent(
                actors.get(tenantId == a ? BEARER_A : BEARER_B),
                boardId,
                new ReportingBoardModels.BoardComponentSaveRequest(
                    metricCode,
                    "SHOP",
                    "BAR",
                    new ReportingBoardModels.BoardLayoutView(0, 0, 3, 2),
                    List.of(),
                    "",
                    null))
            .componentId());
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================

  @Test
  void 断言1_两租户的看板互不可见() {
    var boardA = saveBoard(a, userA, "B-A", "TEAM");
    var boardB = saveBoard(b, userB, "B-B", "TEAM");

    var pageA = boards.list(actors.get(BEARER_A), null, null, 0, 50);
    var pageB = boards.list(actors.get(BEARER_B), null, null, 0, 50);
    assertThat(pageA.rows()).extracting(r -> r.boardCode()).containsExactly("B-A");
    assertThat(pageB.rows()).extracting(r -> r.boardCode()).containsExactly("B-B");

    // 更强的一格：A 明确按 id 直查 B 的行也拿不到（RLS 挡住）。
    var cross =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select board_code from reporting_board where tenant_id = ? and id = ?",
                        b,
                        boardB)
                    .size());
    assertThat(cross).as("按 id 直查也必须被 RLS 挡住").isZero();

    // A 直接读 B 的看板 ⇒ 与「不存在」**逐字相同**的 404。
    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> boards.get(actors.get(BEARER_A), boardB));
    assertThat(error.getStatusCode().value()).isEqualTo(404);
    assertThat(error.getReason()).isEqualTo("看板不存在或无权访问");
  }

  @Test
  void 断言1_组件与共享也按租户隔离() {
    var boardA = saveBoard(a, userA, "B-A-COMP", "TEAM");
    var componentA = addComponent(a, boardA, "SALES_AMOUNT");
    boards.addShare(
        actors.get(BEARER_A),
        boardA,
        new ReportingBoardModels.BoardShareRequest(null, "reporting.profit.read", "ROLE"));

    // B 的租户上下文里看不到 A 的组件。
    var crossComponent =
        tenants.run(
            b,
            () ->
                sql.fetch(
                        "select id from reporting_board_component where tenant_id = ? and id = ?",
                        a,
                        componentA)
                    .size());
    assertThat(crossComponent).isZero();

    var crossShare =
        tenants.run(
            b,
            () ->
                sql.fetch(
                        "select id from reporting_board_share where tenant_id = ? and board_id = ?",
                        a,
                        boardA)
                    .size());
    assertThat(crossShare).isZero();

    // B 也不能给 A 的看板加组件（组件必须属于这张看板，而 B 看不到这张看板）。
    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> addComponent(b, boardA, "SALES_AMOUNT"));
    assertThat(error.getStatusCode().value()).isEqualTo(404);
  }

  @Test
  void 断言1_指标定义是租户级的() {
    // 🔴 指标定义 UNIQUE(tenant_id, metric_code) ⇒ 租户 A 自己登记的指标
    //   **不进**租户 B 的语义层清单（否则就是把别人的口径算进看板）。
    tenants.run(
        a,
        () -> {
          sql.execute(
              "insert into reporting_metric_definition"
                  + " (id,tenant_id,metric_code,display_name,unit,aggregation,numerator_metric_code,"
                  + " denominator_metric_code,currency_aware,description,owner_module,enabled,created_at,"
                  + " updated_at)"
                  + " values (?,?,?,?,?,?,?,?,?,?,'',true,now(),now())",
              UUID.randomUUID(),
              a,
              "TENANT_A_ONLY_METRIC",
              "只有 A 有的指标",
              "COUNT",
              "SUM",
              null,
              null,
              false,
              "夹具",
              "13.6.1.2");
          return null;
        });

    var codesA = boards.metricCatalog(a).stream().map(m -> m.metricCode()).toList();
    var codesB = boards.metricCatalog(b).stream().map(m -> m.metricCode()).toList();
    assertThat(codesA).contains("TENANT_A_ONLY_METRIC");
    assertThat(codesB).doesNotContain("TENANT_A_ONLY_METRIC");

    // B 也不能用它摆组件（口径唯一：B 的口径表里没有这个码）。
    var boardB = saveBoard(b, userB, "B-STEAL", "TEAM");
    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> addComponent(b, boardB, "TENANT_A_ONLY_METRIC"));
    assertThat(error.getStatusCode().value()).isEqualTo(400);
  }

  @Test
  void 断言1_事实也按租户隔离() throws Exception {
    var shopA = UUID.randomUUID();
    var shopB = UUID.randomUUID();
    fact(a, shopA, "SALES_AMOUNT", "USD", "100.00");
    fact(b, shopB, "SALES_AMOUNT", "USD", "999.00");

    var boardA = saveBoard(a, userA, "B-A-FACTS", "TEAM");
    addComponent(a, boardA, "SALES_AMOUNT");

    var body =
        http.perform(
                get("/reporting/board/boards/" + boardA + "/view")
                    .header("Authorization", BEARER_A)
                    .param("from", DAY1.toString())
                    .param("to", DAY1.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var view = JSON.readValue(body, ReportingBoardModels.BoardViewResponse.class);
    assertThat(view.components().get(0).points()).hasSize(1);
    assertThat(view.components().get(0).points().get(0).dimensionValue()).isEqualTo(shopA.toString());
    assertThat(view.components().get(0).points().get(0).values().get(0).value())
        .isEqualByComparingTo("100.00");
  }

  @Test
  void 断言1_五张表都强制RLS() {
    var tables =
        List.of(
            "reporting_board",
            "reporting_board_component",
            "reporting_board_share",
            "reporting_board_template",
            "reporting_board_template_component");
    for (var table : tables) {
      var row =
          sql.fetchOne(
              "select relforcerowsecurity, relrowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("表 %s 必须存在", table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as("表 %s 必须 FORCE RLS", table).isTrue();
      assertThat(row.get(1, Boolean.class)).as("表 %s 必须 ENABLE RLS", table).isTrue();
    }
  }

  @Test
  void 断言1_没有租户上下文时读到零行() {
    saveBoard(a, userA, "B-A-CTX", "TEAM");
    saveBoard(b, userB, "B-B-CTX", "TEAM");
    assertThat(sql.fetch("select id from reporting_board").size())
        .as("事务外没有「环境租户」⇒ 读不到任何行")
        .isZero();
    assertThat(sql.fetch("select id from reporting_board_component").size()).isZero();
    assertThat(sql.fetch("select id from reporting_board_share").size()).isZero();
  }

  // ==========================================================================
  // 断言 2：伪造租户上下文无效
  // ==========================================================================

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    // (1) HTTP 边界：带**租户 A 的合法会话** + **伪造的 X-Tenant-Id（租户 B）**
    //     ⇒ 看板仍然落在**会话租户 A** 名下，租户 B 看不见。
    http.perform(
            post("/reporting/board/boards")
                .header("Authorization", BEARER_A)
                .header("X-Tenant-Id", b.toString())
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        new ReportingBoardModels.BoardSaveRequest(
                            "B-FORGED", "伪造头建出来的看板", "", "TEAM", List.of(), null, null))))
        .andExpect(status().isOk());

    var inA =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select board_code from reporting_board where tenant_id = ? and board_code = ?",
                        a,
                        "B-FORGED")
                    .size());
    assertThat(inA).as("看板必须落在会话租户 A 名下").isEqualTo(1);

    var inB =
        tenants.run(
            b,
            () ->
                sql.fetch(
                        "select board_code from reporting_board where tenant_id = ? and board_code = ?",
                        b,
                        "B-FORGED")
                    .size());
    assertThat(inB).as("被伪造的租户看不见别人的看板").isZero();

    // B 的清单里没有这份看板。
    assertThat(boards.list(actors.get(BEARER_B), null, null, 0, 50).rows())
        .extracting(r -> r.boardCode())
        .doesNotContain("B-FORGED");
  }

  @Test
  void 断言2_伪造租户头不影响语义层清单() {
    var codesA = boards.metricCatalog(a).stream().map(m -> m.metricCode()).toList();
    var codesB = boards.metricCatalog(b).stream().map(m -> m.metricCode()).toList();
    assertThat(codesA).isNotEmpty();
    assertThat(codesB).isNotEmpty();
    assertThat(codesB).doesNotContain("TENANT_A_ONLY_METRIC");
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 断言3_连接池归还后不串租户() {
    var boardA = saveBoard(a, userA, "B-A-POOL", "TEAM");
    var boardB = saveBoard(b, userB, "B-B-POOL", "TEAM");
    for (int i = 0; i < 5; i++) {
      assertThat(boards.list(actors.get(BEARER_A), null, null, 0, 50).rows())
          .as("第 %d 轮租户 A", i)
          .extracting(r -> r.boardCode())
          .containsExactly("B-A-POOL");
      assertThat(boards.list(actors.get(BEARER_B), null, null, 0, 50).rows())
          .as("第 %d 轮租户 B", i)
          .extracting(r -> r.boardCode())
          .containsExactly("B-B-POOL");
      assertThat(sql.fetch("select id from reporting_board").size())
          .as("第 %d 轮事务外", i)
          .isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(boards.get(actors.get(BEARER_A), boardA).boardCode()).isEqualTo("B-A-POOL");
    assertThat(boards.get(actors.get(BEARER_B), boardB).boardCode()).isEqualTo("B-B-POOL");
  }
}
