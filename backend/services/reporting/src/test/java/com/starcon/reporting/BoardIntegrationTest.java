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
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 13.6.1.2 看板搭建的**集成测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>本文件验的是**业务口径**（多租户三条断言在 {@code BoardTenancyIntegrationTest}）：
 *
 * <ul>
 *   <li>组件的指标码**只能引用已登记的 metric_code**（口径唯一）—— 服务层拒绝 <b>且</b> DB 触发器也拒绝；</li>
 *   <li>维度**只能是白名单里的 5 个码** —— 服务层拒绝 <b>且</b> DB CHECK 也拒绝；</li>
 *   <li>🔴 **不同角色看到的组件不同** —— 库层视图 + 服务层，两条同规则；</li>
 *   <li>🔴 **非法布局直接报错**，不静默丢弃；</li>
 *   <li>🔴 **筛选值只走绑定参数**（注入串进不来）；</li>
 *   <li>🔴 **分母为 0 ⇒ 不可比（不是 0）**；🔴 **涉币指标不跨币种相加**；</li>
 *   <li>按角色共享 + 共享配置；默认值；模板实例化。</li>
 * </ul>
 *
 * <p>⚠️ **事实是真的投影事实**：夹具直接写 {@code reporting_metric_fact}（13.1.1.1 的投影表），
 * 口径表由 13.1.1.1 的服务层播种 —— 本模块**只读**它们，不自己发明指标码。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BoardIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID owner = UUID.randomUUID();
  /** 同事 A：多一个权限（reporting.profit.read）。 */
  static final UUID richUser = UUID.randomUUID();
  /** 同事 B：只有看板读权限。 */
  static final UUID plainUser = UUID.randomUUID();

  static final String BEARER_OWNER = "Bearer owner";
  static final String BEARER_RICH = "Bearer rich";
  static final String BEARER_PLAIN = "Bearer plain";

  static HttpServer identity;

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> boardPermissions =
      List.of("reporting.board.read", "reporting.board.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  static final LocalDate DAY1 = LocalDate.of(2026, 10, 1);
  static final LocalDate DAY2 = LocalDate.of(2026, 10, 2);

  static final UUID SHOP_A = UUID.randomUUID();
  static final UUID SHOP_B = UUID.randomUUID();
  /** 🔴 只有零销售额的店铺：用来证明「分母为 0 ⇒ 不可比」，不是 0。 */
  static final UUID SHOP_ZERO = UUID.randomUUID();

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，多租户断言全部变成空断言。
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
      actors.put(BEARER_OWNER, actor(owner, boardPermissions, "reporting.profit.read"));
      actors.put(BEARER_RICH, actor(richUser, boardPermissions, "reporting.profit.read"));
      actors.put(BEARER_PLAIN, actor(plainUser, List.of("reporting.board.read")));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static AccessService.Access actor(UUID userId, List<String> permissions, String... extra) {
    var all = new java.util.ArrayList<String>(permissions);
    all.addAll(List.of(extra));
    var grant = new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", all);
    return new AccessService.Access(
        userId, tenant, "Test", UUID.randomUUID(), null, all, List.of(grant));
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
  @Autowired AccessService access;

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
    // 播种口径（13.1.1.1 的服务层干的活）—— 本模块只读它。
    boards.metricCatalog(tenant);
    seedFacts();
  }

  // ==========================================================================
  // 夹具事实
  // ==========================================================================

  private void seedFacts() {
    // 店铺 A：DAY1 销售额 100 / 成本 60 / 订单 2；DAY2 销售额 200 / 成本 100 / 订单 4。
    fact(SHOP_A, DAY1, "SALES_AMOUNT", "USD", "100.00");
    fact(SHOP_A, DAY1, "COGS_AMOUNT", "USD", "60.00");
    fact(SHOP_A, DAY1, "ORDER_COUNT", "USD", "2");
    fact(SHOP_A, DAY2, "SALES_AMOUNT", "USD", "200.00");
    fact(SHOP_A, DAY2, "COGS_AMOUNT", "USD", "100.00");
    fact(SHOP_A, DAY2, "ORDER_COUNT", "USD", "4");
    // 店铺 B：DAY1 美元；DAY2 欧元 ⇒ 跨币种，但**绝不相加**。
    fact(SHOP_B, DAY1, "SALES_AMOUNT", "USD", "50.00");
    fact(SHOP_B, DAY1, "COGS_AMOUNT", "USD", "50.00");
    fact(SHOP_B, DAY1, "ORDER_COUNT", "USD", "1");
    fact(SHOP_B, DAY2, "SALES_AMOUNT", "EUR", "80.00");
    fact(SHOP_B, DAY2, "COGS_AMOUNT", "EUR", "20.00");
    fact(SHOP_B, DAY2, "ORDER_COUNT", "EUR", "2");
    // 🔴 零店铺：销售额真的是 0（**这个 0 是真的**），成本 0 ⇒ 毛利率的分母是 0。
    fact(SHOP_ZERO, DAY1, "SALES_AMOUNT", "USD", "0.00");
    fact(SHOP_ZERO, DAY1, "COGS_AMOUNT", "USD", "0.00");
    fact(SHOP_ZERO, DAY1, "ORDER_COUNT", "USD", "3");
  }

  private void fact(UUID shopId, LocalDate day, String metricCode, String currency, String value) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into reporting_metric_fact"
                  + " (id,tenant_id,scope_type,legal_entity_id,shop_id,metric_code,bucket_date,currency,"
                  + " value,timezone,source_service,source_reference,updated_at)"
                  + " values (?,?,?,?,?,?,?,?,?,?,?,?,now())",
              UUID.randomUUID(),
              tenant,
              "SHOP",
              null,
              shopId,
              metricCode,
              day,
              currency,
              new BigDecimal(value),
              "UTC",
              "trade",
              "seed-" + shopId + "-" + metricCode + "-" + day + "-" + currency);
          return null;
        });
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private UUID newBoard(String code, String visibility) {
    var view =
        boards.save(
            actors.get(BEARER_OWNER),
            null,
            new ReportingBoardModels.BoardSaveRequest(
                code, "测试看板 " + code, "说明", visibility, List.of(), null, null));
    return UUID.fromString(view.boardId());
  }

  private UUID addComponent(
      UUID boardId, String metricCode, String dimensionCode, String permission, int x, int y) {
    var view =
        boards.addComponent(
            actors.get(BEARER_OWNER),
            boardId,
            new ReportingBoardModels.BoardComponentSaveRequest(
                metricCode,
                dimensionCode,
                "BAR",
                new ReportingBoardModels.BoardLayoutView(x, y, 3, 2),
                List.of(),
                permission,
                null));
    return UUID.fromString(view.componentId());
  }

  private List<ReportingBoardModels.BoardComponentView> visibleComponents(String bearer, UUID boardId)
      throws Exception {
    var body =
        http.perform(get("/reporting/board/boards/" + boardId).header("Authorization", bearer))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var view = JSON.readValue(body, ReportingBoardModels.BoardView.class);
    return view.components();
  }

  // ==========================================================================
  // 能力声明与目录
  // ==========================================================================

  @Test
  void 能力声明写清未交付的导出() {
    var capabilities = boards.capabilities();
    assertThat(capabilities.boardAvailable()).isTrue();
    assertThat(capabilities.templateAvailable()).isTrue();
    assertThat(capabilities.notDelivered())
        .as("导出属 13.6.2.1（未交付）⇒ 如实写出来")
        .anyMatch(note -> note.contains("13.6.2.1"));
    assertThat(capabilities.rules())
        .extracting(rule -> rule.ruleCode())
        .contains("METRIC_REGISTRY", "PERMISSION_FILTER", "ROLE_SHARE", "LAYOUT_SHAPE");
  }

  @Test
  void 指标清单按租户过滤且维度白名单只有五个() {
    var catalog = boards.metricCatalog(tenant);
    assertThat(catalog).isNotEmpty();
    assertThat(catalog).extracting(m -> m.metricCode()).contains("SALES_AMOUNT", "GROSS_MARGIN");
    assertThat(boards.dimensionCatalog()).extracting(d -> d.dimensionCode())
        .containsExactly("SCOPE_TYPE", "LEGAL_ENTITY", "SHOP", "BUCKET_DATE", "CURRENCY");
  }

  // ==========================================================================
  // 口径唯一（组件的指标码必须已登记）
  // ==========================================================================

  @Test
  void 未登记的指标码被服务层拒绝() {
    var boardId = newBoard("B-UNKNOWN", "TEAM");
    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                boards.addComponent(
                    actors.get(BEARER_OWNER),
                    boardId,
                    new ReportingBoardModels.BoardComponentSaveRequest(
                        "NOT_REGISTERED_METRIC",
                        "SHOP",
                        "BAR",
                        new ReportingBoardModels.BoardLayoutView(0, 0, 3, 2),
                        List.of(),
                        "",
                        null)));
    assertThat(error.getStatusCode().value()).isEqualTo(400);
    assertThat(error.getReason()).contains("口径唯一");
  }

  @Test
  void 未登记的指标码被DB触发器拒绝() {
    var boardId = newBoard("B-TRIGGER", "TEAM");
    // 🔴 绕开服务层直接写库 ⇒ 触发器 `guard_reporting_board_component_known` 必须拒绝。
    var error =
        assertThrows(
            org.springframework.dao.DataIntegrityViolationException.class,
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "insert into reporting_board_component"
                              + " (id,tenant_id,board_id,metric_code,dimension_code,chart_type,layout,filter,"
                              + " required_permission,sort_order,created_at,updated_at)"
                              + " values (?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,now(),now())",
                          UUID.randomUUID(),
                          tenant,
                          boardId,
                          "STILL_NOT_REGISTERED",
                          "SHOP",
                          "BAR",
                          "{\"x\":0,\"y\":0,\"w\":3,\"h\":2}",
                          "[]",
                          "",
                          0);
                      return null;
                    }));
    assertThat(error.getMessage()).contains("尚未在口径表中登记");
  }

  @Test
  void 维度不在白名单被拒绝() {
    var boardId = newBoard("B-DIM", "TEAM");
    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                boards.addComponent(
                    actors.get(BEARER_OWNER),
                    boardId,
                    new ReportingBoardModels.BoardComponentSaveRequest(
                        "SALES_AMOUNT",
                        "CUSTOMER_NAME",
                        "BAR",
                        new ReportingBoardModels.BoardLayoutView(0, 0, 3, 2),
                        List.of(),
                        "",
                        null)));
    assertThat(error.getStatusCode().value()).isEqualTo(400);
  }

  @Test
  void 维度不在白名单被DB的CHECK拒绝() {
    var boardId = newBoard("B-DIM-CHECK", "TEAM");
    var error =
        assertThrows(
            org.springframework.dao.DataIntegrityViolationException.class,
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "insert into reporting_board_component"
                              + " (id,tenant_id,board_id,metric_code,dimension_code,chart_type,layout,filter,"
                              + " required_permission,sort_order,created_at,updated_at)"
                              + " values (?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,now(),now())",
                          UUID.randomUUID(),
                          tenant,
                          boardId,
                          "SALES_AMOUNT",
                          "CUSTOMER_NAME",
                          "BAR",
                          "{\"x\":0,\"y\":0,\"w\":3,\"h\":2}",
                          "[]",
                          "",
                          0);
                      return null;
                    }));
    assertThat(error.getMessage()).contains("dimension_code");
  }

  // ==========================================================================
  // 布局（拖拽）
  // ==========================================================================

  @Test
  void 非法布局直接报错不静默丢弃() {
    var boardId = newBoard("B-LAYOUT", "TEAM");
    // 负数位置。
    var negative =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                boards.addComponent(
                    actors.get(BEARER_OWNER),
                    boardId,
                    new ReportingBoardModels.BoardComponentSaveRequest(
                        "SALES_AMOUNT",
                        "SHOP",
                        "BAR",
                        new ReportingBoardModels.BoardLayoutView(-1, 0, 3, 2),
                        List.of(),
                        "",
                        null)));
    assertThat(negative.getStatusCode().value()).isEqualTo(400);

    // 尺寸为 0。
    var zero =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                boards.addComponent(
                    actors.get(BEARER_OWNER),
                    boardId,
                    new ReportingBoardModels.BoardComponentSaveRequest(
                        "ORDER_COUNT",
                        "SHOP",
                        "BAR",
                        new ReportingBoardModels.BoardLayoutView(0, 0, 0, 2),
                        List.of(),
                        "",
                        null)));
    assertThat(zero.getStatusCode().value()).isEqualTo(400);

    // 超出 12 栅格宽。
    var overflow =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                boards.addComponent(
                    actors.get(BEARER_OWNER),
                    boardId,
                    new ReportingBoardModels.BoardComponentSaveRequest(
                        "UNIT_SOLD",
                        "SHOP",
                        "BAR",
                        new ReportingBoardModels.BoardLayoutView(10, 0, 4, 2),
                        List.of(),
                        "",
                        null)));
    assertThat(overflow.getStatusCode().value()).isEqualTo(400);
    assertThat(overflow.getReason()).contains("12");
  }

  @Test
  void 拖拽布局保存后读回来位置变了() throws Exception {
    var boardId = newBoard("B-DRAG", "TEAM");
    var first = addComponent(boardId, "SALES_AMOUNT", "SHOP", "", 0, 0);
    var second = addComponent(boardId, "ORDER_COUNT", "SHOP", "", 3, 0);

    var saved =
        boards.saveLayout(
            actors.get(BEARER_OWNER),
            boardId,
            new ReportingBoardModels.BoardLayoutSaveRequest(
                List.of(
                    new ReportingBoardModels.BoardLayoutItem(
                        first.toString(), 6, 2, 4, 3, 1),
                    new ReportingBoardModels.BoardLayoutItem(
                        second.toString(), 0, 0, 6, 2, 0))));
    assertThat(saved).hasSize(2);
    var byId = saved.stream().collect(java.util.stream.Collectors.toMap(c -> c.componentId(), c -> c));
    assertThat(byId.get(first.toString()).layout())
        .isEqualTo(new ReportingBoardModels.BoardLayoutView(6, 2, 4, 3));
    assertThat(byId.get(second.toString()).layout())
        .isEqualTo(new ReportingBoardModels.BoardLayoutView(0, 0, 6, 2));

    // 落库的值也要真的写进去（不是只在内存里）。
    var stored =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                        "select layout::text from reporting_board_component where tenant_id = ? and id = ?",
                        tenant,
                        first)
                    .get(0, String.class));
    var storedLayout = JSON.readValue(stored, ReportingBoardModels.BoardLayoutView.class);
    assertThat(storedLayout).isEqualTo(new ReportingBoardModels.BoardLayoutView(6, 2, 4, 3));
  }

  @Test
  void 布局批量保存时非法形状整批拒绝() {
    var boardId = newBoard("B-DRAG-BAD", "TEAM");
    var first = addComponent(boardId, "SALES_AMOUNT", "SHOP", "", 0, 0);
    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                boards.saveLayout(
                    actors.get(BEARER_OWNER),
                    boardId,
                    new ReportingBoardModels.BoardLayoutSaveRequest(
                        List.of(
                            new ReportingBoardModels.BoardLayoutItem(first.toString(), 0, 0, 3, 2, 0),
                            new ReportingBoardModels.BoardLayoutItem(
                                first.toString(), -5, 0, 3, 2, 1)))));
    assertThat(error.getStatusCode().value()).isEqualTo(400);
  }

  // ==========================================================================
  // 🔴 权限过滤：不同角色看到的组件不同
  // ==========================================================================

  @Test
  void 不同角色打开同一个看板看到的组件不同() throws Exception {
    var boardId = newBoard("B-PERM", "TEAM");
    addComponent(boardId, "SALES_AMOUNT", "SHOP", "", 0, 0);
    addComponent(boardId, "GROSS_MARGIN", "SHOP", "reporting.profit.read", 3, 0);

    // 同事 A 有 reporting.profit.read ⇒ 两个组件都看得见。
    var rich = visibleComponents(BEARER_RICH, boardId);
    assertThat(rich).hasSize(2);

    // 同事 B 没有 ⇒ 只看得见没有权限要求的那一个。
    var plain = visibleComponents(BEARER_PLAIN, boardId);
    assertThat(plain).hasSize(1);
    assertThat(plain).extracting(c -> c.metricCode()).containsExactly("SALES_AMOUNT");
  }

  @Test
  void 组件可见性在库层也是不变量() throws Exception {
    var boardId = newBoard("B-PERM-DB", "TEAM");
    addComponent(boardId, "SALES_AMOUNT", "SHOP", "", 0, 0);
    addComponent(boardId, "GROSS_MARGIN", "SHOP", "reporting.profit.read", 3, 0);

    // 🔴 走 HTTP（会话里带着 app.grants / app.actor）⇒ 库层视图按权限过滤。
    for (var bearer : List.of(BEARER_RICH, BEARER_PLAIN)) {
      var count =
          http.perform(get("/reporting/board/boards/" + boardId).header("Authorization", bearer))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      var view = JSON.readValue(count, ReportingBoardModels.BoardView.class);
      assertThat(view.components())
          .as("看得见的组件集合必须因角色而不同")
          .hasSize(bearer.equals(BEARER_RICH) ? 2 : 1);
    }

    // 🔴 **直接查库层视图**（不经服务层）：同一个视图，两种授权 ⇒ 两个不同的行数。
    var richRows =
        visibleRowsThroughView(actors.get(BEARER_RICH), boardId);
    var plainRows =
        visibleRowsThroughView(actors.get(BEARER_PLAIN), boardId);
    assertThat(richRows).isEqualTo(2);
    assertThat(plainRows).isEqualTo(1);
  }

  private int visibleRowsThroughView(AccessService.Access actor, UUID boardId) {
    return access.run(
        actor,
        "reporting.board.read",
        () ->
            tenants.run(
                actor.tenantId(),
                () ->
                    sql.fetch(
                            "select id from reporting_board_component_visible"
                                + " where tenant_id = ? and board_id = ?",
                            actor.tenantId(),
                            boardId)
                        .size()));
  }

  @Test
  void 看板视图只给出有权限的组件且标出总数() throws Exception {
    var boardId = newBoard("B-VIEW-PERM", "TEAM");
    addComponent(boardId, "SALES_AMOUNT", "SHOP", "", 0, 0);
    addComponent(boardId, "GROSS_MARGIN", "SHOP", "reporting.profit.read", 3, 0);

    var body =
        http.perform(get("/reporting/board/boards/" + boardId + "/view").header("Authorization", BEARER_PLAIN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var view = JSON.readValue(body, ReportingBoardModels.BoardViewResponse.class);
    assertThat(view.visibleComponentCount()).isEqualTo(1);
    assertThat(view.totalComponentCount()).as("分母是看板上的组件总数，不因为过滤而变小").isEqualTo(2);
    assertThat(view.components()).hasSize(1);
  }

  // ==========================================================================
  // 共享（按角色）
  // ==========================================================================

  @Test
  void 私有看板共享给权限码之后别人能打开() throws Exception {
    var boardId = newBoard("B-PRIVATE", "PRIVATE");

    // 没共享之前 ⇒ 与「不存在」**逐字相同**的 404。
    var missing =
        http.perform(get("/reporting/board/boards/" + boardId).header("Authorization", BEARER_PLAIN))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var cross =
        http.perform(get("/reporting/board/boards/" + boardId).header("Authorization", BEARER_RICH))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(cross).as("无权访问与不存在必须逐字相同").isEqualTo(missing);

    // 共享给持有 reporting.profit.read 的人。
    boards.addShare(
        actors.get(BEARER_OWNER),
        boardId,
        new ReportingBoardModels.BoardShareRequest(null, "reporting.profit.read", "ROLE"));
    http.perform(get("/reporting/board/boards/" + boardId).header("Authorization", BEARER_RICH))
        .andExpect(status().isOk());
    // 没有那个权限的人仍然打不开。
    http.perform(get("/reporting/board/boards/" + boardId).header("Authorization", BEARER_PLAIN))
        .andExpect(status().isNotFound());
  }

  @Test
  void 共享清单只有创建者能看() throws Exception {
    var boardId = newBoard("B-SHARE-LIST", "TEAM");
    boards.addShare(
        actors.get(BEARER_OWNER),
        boardId,
        new ReportingBoardModels.BoardShareRequest(UUID.randomUUID().toString(), null, "ROLE"));
    var shares = boards.listShares(actors.get(BEARER_OWNER), boardId);
    assertThat(shares).hasSize(1);
    assertThat(shares.get(0).roleId()).isNotBlank();
    assertThat(shares.get(0).permissionCode()).isEmpty();

    http.perform(get("/reporting/board/boards/" + boardId + "/shares").header("Authorization", BEARER_PLAIN))
        .andExpect(status().isNotFound());
  }

  @Test
  void 角色与权限码二选一() {
    var boardId = newBoard("B-SHARE-XOR", "TEAM");
    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                boards.addShare(
                    actors.get(BEARER_OWNER),
                    boardId,
                    new ReportingBoardModels.BoardShareRequest(
                        UUID.randomUUID().toString(), "reporting.profit.read", "ROLE")));
    assertThat(error.getStatusCode().value()).isEqualTo(400);
    assertThat(error.getReason()).contains("二选一");
  }

  @Test
  void 撤掉共享之后就打不开了() throws Exception {
    var boardId = newBoard("B-SHARE-REVOKE", "PRIVATE");
    var share =
        boards.addShare(
            actors.get(BEARER_OWNER),
            boardId,
            new ReportingBoardModels.BoardShareRequest(null, "reporting.profit.read", "ROLE"));
    http.perform(get("/reporting/board/boards/" + boardId).header("Authorization", BEARER_RICH))
        .andExpect(status().isOk());
    boards.removeShare(actors.get(BEARER_OWNER), boardId, UUID.fromString(share.shareId()));
    http.perform(get("/reporting/board/boards/" + boardId).header("Authorization", BEARER_RICH))
        .andExpect(status().isNotFound());
  }

  // ==========================================================================
  // 组件数据（口径不重算）
  // ==========================================================================

  @Test
  void 组件数据真的算出来() throws Exception {
    var boardId = newBoard("B-DATA", "TEAM");
    addComponent(boardId, "SALES_AMOUNT", "SHOP", "", 0, 0);

    var body =
        http.perform(
                get("/reporting/board/boards/" + boardId + "/view")
                    .header("Authorization", BEARER_OWNER)
                    .param("from", DAY1.toString())
                    .param("to", DAY2.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var view = JSON.readValue(body, ReportingBoardModels.BoardViewResponse.class);
    assertThat(view.components()).hasSize(1);
    var component = view.components().get(0);
    assertThat(component.metricCode()).isEqualTo("SALES_AMOUNT");
    assertThat(component.status()).isEqualTo("AVAILABLE");
    assertThat(component.points()).isNotEmpty();

    // 店铺 A：DAY1 100 + DAY2 200 = 300（USD）。
    var shopA =
        component.points().stream()
            .filter(point -> SHOP_A.toString().equals(point.dimensionValue()))
            .findFirst()
            .orElseThrow();
    assertThat(shopA.values()).hasSize(1);
    assertThat(shopA.values().get(0).currency()).isEqualTo("USD");
    assertThat(shopA.values().get(0).value()).isEqualByComparingTo("300.00");
  }

  @Test
  void 涉币指标按币种分别报绝不跨币种相加() throws Exception {
    var boardId = newBoard("B-CURRENCY", "TEAM");
    addComponent(boardId, "SALES_AMOUNT", "SHOP", "", 0, 0);

    var body =
        http.perform(
                get("/reporting/board/boards/" + boardId + "/view")
                    .header("Authorization", BEARER_OWNER)
                    .param("from", DAY1.toString())
                    .param("to", DAY2.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var view = JSON.readValue(body, ReportingBoardModels.BoardViewResponse.class);
    var component = view.components().get(0);
    var currencies = new java.util.HashSet<String>();
    for (var point : component.points()) {
      for (var value : point.values()) {
        currencies.add(value.currency());
      }
    }
    assertThat(currencies).as("美元与欧元必须分行报，不能相加成一个数").containsExactlyInAnyOrder("USD", "EUR");
    // 🔴 店铺 B 在 DAY1 是 50 美元、DAY2 是 80 欧元 ⇒ **两个值，不是一个 130**。
    var shopB =
        component.points().stream()
            .filter(point -> SHOP_B.toString().equals(point.dimensionValue()))
            .findFirst()
            .orElseThrow();
    assertThat(shopB.values()).hasSize(2);
    assertThat(shopB.values()).extracting(value -> value.currency()).containsExactlyInAnyOrder("USD", "EUR");
    for (var value : shopB.values()) {
      assertThat(value.value()).as("跨币种绝不相加").isEqualByComparingTo(value.currency().equals("USD") ? "50.00" : "80.00");
    }
  }

  @Test
  void 分母为零显示不可比而不是零() throws Exception {
    var boardId = newBoard("B-NOT-COMPARABLE", "TEAM");
    addComponent(boardId, "GROSS_MARGIN", "SHOP", "", 0, 0);

    var body =
        http.perform(
                get("/reporting/board/boards/" + boardId + "/view")
                    .header("Authorization", BEARER_OWNER)
                    .param("from", DAY1.toString())
                    .param("to", DAY1.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var view = JSON.readValue(body, ReportingBoardModels.BoardViewResponse.class);
    var component = view.components().get(0);
    var zeroPoint =
        component.points().stream()
            .filter(point -> SHOP_ZERO.toString().equals(point.dimensionValue()))
            .findFirst()
            .orElseThrow();
    assertThat(zeroPoint.values()).isNotEmpty();
    assertThat(zeroPoint.values().get(0).value()).as("分母为 0 ⇒ 不可比，不是 0").isNull();
    assertThat(zeroPoint.values().get(0).availability()).isEqualTo("NOT_COMPARABLE");
    assertThat(zeroPoint.values().get(0).availabilityNote()).isNotBlank();
  }

  @Test
  void 数据更新时间常驻显示() throws Exception {
    var boardId = newBoard("B-LATENCY", "TEAM");
    addComponent(boardId, "SALES_AMOUNT", "SHOP", "", 0, 0);
    var body =
        http.perform(get("/reporting/board/boards/" + boardId + "/view").header("Authorization", BEARER_OWNER))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var view = JSON.readValue(body, ReportingBoardModels.BoardViewResponse.class);
    assertThat(view.dataUpdatedAt()).as("卷 13 卷首：数据延迟要显示").isNotNull();
    assertThat(view.dataUpdatedNote()).isNotBlank();
    assertThat(view.caliberNotes()).isNotEmpty();
  }

  // ==========================================================================
  // 筛选（值只走绑定参数）
  // ==========================================================================

  @Test
  void 注入串进不来筛选值() {
    var boardId = newBoard("B-INJECTION", "TEAM");
    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                boards.addComponent(
                    actors.get(BEARER_OWNER),
                    boardId,
                    new ReportingBoardModels.BoardComponentSaveRequest(
                        "SALES_AMOUNT",
                        "SHOP",
                        "BAR",
                        new ReportingBoardModels.BoardLayoutView(0, 0, 3, 2),
                        List.of(
                            new ReportingBoardModels.BoardFilterRequest(
                                "SHOP", "EQ", "x'; drop table reporting_board; --", null)),
                        "",
                        null)));
    assertThat(error.getStatusCode().value()).isEqualTo(400);
    assertThat(error.getReason()).contains("uuid");
    // 表还在。
    assertThat(
            tenants.run(
                tenant,
                () ->
                    sql.fetch("select id from reporting_board where tenant_id = ?", tenant).size()))
        .isEqualTo(1);
  }

  @Test
  void 筛选真的把数据滤掉() throws Exception {
    var boardId = newBoard("B-FILTER", "TEAM");
    boards.addComponent(
        actors.get(BEARER_OWNER),
        boardId,
        new ReportingBoardModels.BoardComponentSaveRequest(
            "SALES_AMOUNT",
            "SHOP",
            "BAR",
            new ReportingBoardModels.BoardLayoutView(0, 0, 3, 2),
            List.of(new ReportingBoardModels.BoardFilterRequest("SHOP", "EQ", SHOP_A.toString(), null)),
            "",
            null));

    var body =
        http.perform(
                get("/reporting/board/boards/" + boardId + "/view")
                    .header("Authorization", BEARER_OWNER)
                    .param("from", DAY1.toString())
                    .param("to", DAY2.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var view = JSON.readValue(body, ReportingBoardModels.BoardViewResponse.class);
    var component = view.components().get(0);
    assertThat(component.points()).as("只应剩下被筛中的那家店铺").hasSize(1);
    assertThat(component.points().get(0).dimensionValue()).isEqualTo(SHOP_A.toString());
  }

  // ==========================================================================
  // 默认值
  // ==========================================================================

  @Test
  void 默认筛选与默认窗口生效() throws Exception {
    var boardId = newBoard("B-DEFAULTS", "TEAM");
    boards.saveDefaults(
        actors.get(BEARER_OWNER),
        boardId,
        new ReportingBoardModels.BoardDefaultsRequest(
            List.of(new ReportingBoardModels.BoardFilterRequest("SHOP", "EQ", SHOP_A.toString(), null)),
            DAY1,
            DAY2));
    var defaults = boards.getDefaults(actors.get(BEARER_OWNER), boardId);
    assertThat(defaults.defaultFilters()).hasSize(1);
    assertThat(defaults.defaultWindowFrom()).isEqualTo(DAY1);
    assertThat(defaults.defaultWindowTo()).isEqualTo(DAY2);

    addComponent(boardId, "SALES_AMOUNT", "SHOP", "", 0, 0);
    var body =
        http.perform(get("/reporting/board/boards/" + boardId + "/view").header("Authorization", BEARER_OWNER))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var view = JSON.readValue(body, ReportingBoardModels.BoardViewResponse.class);
    assertThat(view.from()).as("窗口取看板的默认值").isEqualTo(DAY1);
    assertThat(view.to()).isEqualTo(DAY2);
    var component = view.components().get(0);
    assertThat(component.points()).as("看板的默认筛选要落到组件上").hasSize(1);
    assertThat(component.points().get(0).dimensionValue()).isEqualTo(SHOP_A.toString());
  }

  // ==========================================================================
  // 模板
  // ==========================================================================

  @Test
  void 模板实例化把组件整份复制过来() {
    var template =
        boards.createTemplate(
            actors.get(BEARER_OWNER),
            new ReportingBoardModels.BoardTemplateSaveRequest(
                "T-SALES",
                "销售模板",
                "说明",
                List.of(
                    new ReportingBoardModels.BoardComponentSaveRequest(
                        "SALES_AMOUNT",
                        "SHOP",
                        "BAR",
                        new ReportingBoardModels.BoardLayoutView(0, 0, 6, 2),
                        List.of(),
                        "",
                        null))));
    assertThat(template.components()).hasSize(1);

    var board =
        boards.instantiateTemplate(
            actors.get(BEARER_OWNER),
            UUID.fromString(template.templateId()),
            new ReportingBoardModels.BoardTemplateInstantiateRequest(
                "B-FROM-TEMPLATE", "从模板建的看板", "", "TEAM"));
    assertThat(board.components()).hasSize(1);
    assertThat(board.components().get(0).metricCode()).isEqualTo("SALES_AMOUNT");
    assertThat(board.components().get(0).layout())
        .isEqualTo(new ReportingBoardModels.BoardLayoutView(0, 0, 6, 2));
  }

  @Test
  void 模板里的指标码也必须已登记() {
    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                boards.createTemplate(
                    actors.get(BEARER_OWNER),
                    new ReportingBoardModels.BoardTemplateSaveRequest(
                        "T-BAD",
                        "坏模板",
                        "",
                        List.of(
                            new ReportingBoardModels.BoardComponentSaveRequest(
                                "NOT_REGISTERED_METRIC",
                                "SHOP",
                                "BAR",
                                new ReportingBoardModels.BoardLayoutView(0, 0, 3, 2),
                                List.of(),
                                "",
                                null)))));
    assertThat(error.getStatusCode().value()).isEqualTo(400);
  }

  // ==========================================================================
  // 删除
  // ==========================================================================

  @Test
  void 删除看板连带删掉组件与共享() {
    var boardId = newBoard("B-DELETE", "TEAM");
    addComponent(boardId, "SALES_AMOUNT", "SHOP", "", 0, 0);
    boards.addShare(
        actors.get(BEARER_OWNER),
        boardId,
        new ReportingBoardModels.BoardShareRequest(null, "reporting.profit.read", "ROLE"));

    boards.delete(actors.get(BEARER_OWNER), boardId);

    assertThat(
            tenants.run(
                tenant,
                () ->
                    sql.fetch(
                            "select id from reporting_board_component where tenant_id = ? and board_id = ?",
                            tenant,
                            boardId)
                        .size()))
        .isZero();
    assertThat(
            tenants.run(
                tenant,
                () ->
                    sql.fetch(
                            "select id from reporting_board_share where tenant_id = ? and board_id = ?",
                            tenant,
                            boardId)
                        .size()))
        .isZero();
  }

  // ==========================================================================
  // 清单与工作台
  // ==========================================================================

  @Test
  void 看板清单是真分页真搜索() {
    newBoard("B-PAGE-1", "TEAM");
    newBoard("B-PAGE-2", "TEAM");
    var page = boards.list(actors.get(BEARER_OWNER), "B-PAGE", null, 0, 1);
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.rows()).hasSize(1);
    assertThat(page.page()).isZero();
    assertThat(page.size()).isEqualTo(1);
  }

  @Test
  void 工作台只给出我能打开的看板() {
    var privateBoard = newBoard("B-WB-PRIVATE", "PRIVATE");
    newBoard("B-WB-TEAM", "TEAM");
    var workbench = boards.workbench(actors.get(BEARER_PLAIN));
    assertThat(workbench.boards())
        .extracting(item -> item.boardCode())
        .containsExactly("B-WB-TEAM");
    assertThat(privateBoard).isNotNull();
  }
}
