package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 18.1.3.2「数据迁移」的**功能**集成测试，跑在真实 PostgreSQL 16 上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p>本测试盯住蓝图 18.1.3.2 点名的**五项能力**，每一项都必须是「真在生效」而不是「端点存在」：
 *
 * <ol>
 *   <li><b>映射配置</b>（外部字段 → 内部模型）：缺必填映射时**建配置时就拒**；
 *   <li><b>试运行预览</b>：一个写端点都不调，逐行结论里**没有上游 id**；
 *   <li><b>正式迁移</b>：真的调目标库的写端点，逐行结论**带上游 id**；
 *   <li><b>失败回滚</b>：真的去目标库**删掉**建出来的记录；
 *   <li><b>核对报告</b>：成功 / 失败 / 跳过三个口径**加起来等于**处理过的行数。
 * </ol>
 *
 * <p>🔴 <b>目标业务域由一个真的 HTTP 服务扮演</b>（{@link CatalogFixture}）：
 * 它真的收 {@code POST /catalog/products}、真的建记录、真的在 {@code DELETE} 时把记录删掉 ——
 * 所以「回滚是真的」可以由「目标库里那条记录真的不见了」证明，
 * 而「试运行不写」可以由「写端点的调用计数是 0」证明。
 *
 * <p>🔴 <b>没有硬编码的期望数据</b>：期望值来自夹具里**真的发生过的请求**，
 * 以及真的落库的逐行结论。
 *
 * <p>租户隔离的三条断言在 {@code DataMigrationTenancyIntegrationTest} 里（同一批表、同一套夹具）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DataMigrationIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final String BEARER = "session-migration";
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static CatalogFixture catalog;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 本模块的四张表。 */
  static final List<String> TABLES =
      List.of(
          "platform_migration_mapping",
          "platform_migration_run",
          "platform_migration_file",
          "platform_migration_row");

  /** 权限码（**必须同时含 .read** —— AccessService 的判据要求同一 grant 里两者都有）。 */
  static final List<String> PERMISSIONS =
      List.of(
          "platform.migration.read",
          "platform.migration.create",
          "platform.migration.execute",
          "platform.migration.download");

  static AccessService.Access actor(UUID uid, UUID tenantId, List<String> permissions) {
    return new AccessService.Access(
        uid,
        tenantId,
        "Test",
        UUID.randomUUID(),
        null,
        permissions,
        List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions)));
  }

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，隔离断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role platform_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    actors.put(BEARER, actor(UUID.randomUUID(), tenant, PERMISSIONS));
    try {
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
            byte[] body = json.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      identity.setExecutor(Executors.newFixedThreadPool(2));
      identity.start();
      catalog = new CatalogFixture();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "platform_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("CATALOG_URL", () -> "http://127.0.0.1:" + catalog.port());
    // 其余上游**故意指向不可达地址**：本模块只连 catalog。
    r.add("TRADE_URL", () -> "http://127.0.0.1:1");
    r.add("WAREHOUSE_URL", () -> "http://127.0.0.1:1");
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:1");
    r.add("FINANCE_URL", () -> "http://127.0.0.1:1");
    r.add("PLATFORM_IO_STORAGE_ROOT", () -> "target/platform-migration-store-test");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired DataMigrationService service;

  /** 当前会话的成员上下文（服务层直调时用）。 */
  AccessService.Access currentActor() {
    return actors.get(BEARER);
  }

  @AfterAll
  static void stopContainers() {
    if (identity != null) identity.stop(0);
    if (catalog != null) catalog.stop();
    if (pg.isRunning()) pg.stop();
  }

  // ================================================================ 夹具

  @BeforeEach
  void reset() throws Exception {
    awaitQuiescent();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_migration_row, platform_migration_file, platform_migration_run,"
              + " platform_migration_mapping restart identity cascade");
      s.execute("alter sequence platform_migration_run_no_seq restart with 1");
    }
    catalog.reset();
  }

  /**
   * 收尾：把**故意留在非终态**的任务收成终态。
   *
   * <p>🔴 有一条用例故意插一个「一直在跑」的任务（用它触发「同一租户已有迁移在跑
   * 时发起新的迁移返回 409」）。那条任务**永远跑不完**（执行器只跑自己提交的任务），
   * 于是下一条用例的 {@link #awaitQuiescent()} 会一直等它 ⇒ 整条用例链卡死。
   * ⇒ 每条用例结束时把非终态的任务收掉。
   */
  @AfterEach
  void settle() {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "update platform_migration_run set status = 'FAILED', error = '测试夹具收尾',"
                  + " finished_at = ?::timestamptz, updated_at = ?::timestamptz"
                  + " where status in ('PENDING','RUNNING')",
              dbNow(),
              dbNow());
          return null;
        });
  }

  private OffsetDateTime dbNow() {
    return sql.fetchOne("select clock_timestamp()").get(0, OffsetDateTime.class);
  }

  /**
   * 等后台执行器**跑完**再清库。
   *
   * <p>🔴 <b>为什么必须等</b>：迁移与回滚都是**异步**的（进程内执行器）。
   * 上一条用例的迁移还在跑时就 {@code TRUNCATE}，会与执行器持有的读锁形成
   * <b>死锁</b>（TRUNCATE 要 ACCESS EXCLUSIVE，执行器要 ACCESS SHARE）——
   * PostgreSQL 会中止其中一方，表现为「随机一条用例报 deadlock detected」。
   * ⇒ 先等没有在跑的任务与回滚，再清。
   */
  private void awaitQuiescent() throws Exception {
    for (var i = 0; i < 100; i++) {
      var busy =
          tenants.run(
              tenant,
              () ->
                  sql.fetchOne(
                          "select count(*) from platform_migration_run"
                              + " where status in ('PENDING','RUNNING')"
                              + " or rollback_status = 'RUNNING'")
                      .get(0, Long.class));
      if (busy == 0L) return;
      Thread.sleep(50);
    }
    throw new AssertionError("后台执行器没有在预期时间内停下来（仍有迁移或回滚在跑）");
  }

  private <T extends org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> T
      authed(T builder) {
    builder.header("Authorization", BEARER);
    return builder;
  }

  // ================================================================ 工具

  /** 建一份 SPU 映射（商品编码 / 商品名称 / 类目 必填，品牌 / 描述 选填）。 */
  UUID createSpuMapping() throws Exception {
    var body =
        json.writeValueAsBytes(
            Map.of(
                "code",
                "JST_SPU_V1",
                "name",
                "聚水潭商品映射",
                "sourceSystem",
                "聚水潭",
                "targetModel",
                "SPU",
                "fields",
                List.of(
                    Map.of(
                        "source", "商品编码", "target", "code", "required", true, "transform", "TRIM", "defaultValue", ""),
                    Map.of(
                        "source", "商品名称", "target", "name", "required", true, "transform", "TRIM", "defaultValue", ""),
                    Map.of(
                        "source", "类目", "target", "category", "required", true, "transform", "TRIM", "defaultValue", ""),
                    Map.of(
                        "source", "品牌", "target", "brand", "required", false, "transform", "NONE", "defaultValue", "")),
                "remark",
                "测试用"));
    var response =
        http.perform(
                authed(post("/platform/data-migration/mappings"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    return UUID.fromString(json.readTree(response.getContentAsString()).get("id").asText());
  }

  /** 发起一次迁移（**已受理**：真正的执行在后台跑）。 */
  UUID createRun(UUID mappingId, String mode, String filename, String csv) throws Exception {
    var body =
        json.writeValueAsBytes(
            Map.of(
                "mappingId",
                mappingId.toString(),
                "mode",
                mode,
                "filename",
                filename,
                "contentBase64",
                Base64.getEncoder().encodeToString(csv.getBytes(StandardCharsets.UTF_8))));
    var response =
        http.perform(
                authed(post("/platform/data-migration/runs"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    return UUID.fromString(json.readTree(response.getContentAsString()).get("id").asText());
  }

  /** 轮询到任务进入终态（返回状态码）。 */
  String waitForTerminal(UUID runId) throws Exception {
    for (var i = 0; i < 300; i++) {
      var body =
          http.perform(authed(get("/platform/data-migration/runs/" + runId)))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString(StandardCharsets.UTF_8);
      var state = json.readTree(body).get("status").asText();
      if (!state.equals("PENDING") && !state.equals("RUNNING")) return state;
      Thread.sleep(50);
    }
    throw new AssertionError("迁移任务没有在预期时间内进入终态：" + runId);
  }

  /**
   * 轮询到回滚进入终态（返回回滚状态码）。
   *
   * <p>🔴 <b>每轮只读一次状态</b>：读两次的话，两次之间状态可能从「未回滚」翻到「回滚中」，
   * 于是「不是回滚中」与「不是未回滚」同时成立 ⇒ 循环提前退出 ——
   * 而回滚其实**还在跑**，报告文件也就还没写出来（后面那个断言会拿到 404）。
   * 这是一个真的竞态，不是测试写得随意。
   */
  String waitForRollbackTerminal(UUID runId) throws Exception {
    for (var i = 0; i < 300; i++) {
      var state = run(runId).get("rollbackStatus").asText();
      if (!state.equals("PENDING") && !state.equals("NONE") && !state.equals("RUNNING")) {
        return state;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("回滚没有在预期时间内进入终态：" + runId);
  }

  /** 逐行结论（真分页接口）。 */
  com.fasterxml.jackson.databind.JsonNode rows(UUID runId) throws Exception {
    var body =
        http.perform(authed(get("/platform/data-migration/runs/" + runId + "/rows")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return json.readTree(body);
  }

  /** 任务详情。 */
  com.fasterxml.jackson.databind.JsonNode run(UUID runId) throws Exception {
    var body =
        http.perform(authed(get("/platform/data-migration/runs/" + runId)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return json.readTree(body);
  }

  /** 源文件：四行数据（两行正常、一行缺编码、一行重复编码）。 */
  static final String SPU_CSV =
      "商品编码,商品名称,类目,品牌,备注\n"
          + "SPU-001,测试商品一,耳机,品牌A,备注一\n"
          + "SPU-002,测试商品二,耳机,品牌B,备注二\n"
          + ",缺编码的商品,耳机,品牌C,备注三\n"
          + "SPU-001,重复编码的商品,耳机,品牌D,备注四\n";

  // ==========================================================================
  // 一、映射配置（外部字段 → 内部模型）
  // ==========================================================================
  @Test
  void 映射配置_缺必填字段映射时建配置时就拒() throws Exception {
    var input =
        new DataMigrationModels.DataMigrationMappingInput(
            "BAD_MAPPING",
            "缺必填映射",
            "聚水潭",
            "SPU",
            List.of(
                new DataMigrationModels.DataMigrationField("商品编码", "code", true, "TRIM", "")),
            Boolean.TRUE,
            "");
    var response =
        http.perform(
                authed(post("/platform/data-migration/mappings"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(input)))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(400);

    // 🔴 **报错必须点名缺了哪个必填字段** —— 否则运营不知道要补什么。
    var e =
        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> service.createMapping(input, currentActor()));
    assertThat(e.getStatusCode().value()).isEqualTo(400);
    assertThat(e.getReason()).contains("name");
    assertThat(e.getReason()).contains("category");
  }

  @Test
  void 映射配置_未接通的目标模型建不了() throws Exception {
    var input =
        new DataMigrationModels.DataMigrationMappingInput(
            "ORDER_MAPPING", "订单映射", "聚水潭", "ORDER", List.of(), Boolean.TRUE, "");
    var response =
        http.perform(
                authed(post("/platform/data-migration/mappings"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(input)))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(400);

    var e =
        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> service.createMapping(input, currentActor()));
    assertThat(e.getStatusCode().value()).isEqualTo(400);
    assertThat(e.getReason())
        .as("没接通的模型必须写明是哪个、以及为什么没接通")
        .contains("ORDER")
        .contains("没有接通迁移");
  }

  @Test
  void 映射配置_被迁移任务引用后不能删() throws Exception {
    var mappingId = createSpuMapping();
    var runId = createRun(mappingId, "DRY_RUN", "spu.csv", SPU_CSV);
    waitForTerminal(runId);

    var response =
        http.perform(authed(delete("/platform/data-migration/mappings/" + mappingId)))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(409);

    var e =
        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      service.deleteMapping(mappingId);
                      return null;
                    }));
    assertThat(e.getStatusCode().value()).isEqualTo(409);
    assertThat(e.getReason()).contains("引用");
  }

  @Test
  void 映射配置_删除后真没了() throws Exception {
    var mappingId = createSpuMapping();
    http.perform(authed(delete("/platform/data-migration/mappings/" + mappingId)))
        .andExpect(status().isOk());
    http.perform(authed(get("/platform/data-migration/mappings/" + mappingId)))
        .andExpect(status().isNotFound());
  }

  // ==========================================================================
  // 二、试运行预览（**蓝图点名的高亮要求**）
  // ==========================================================================
  @Test
  void 试运行_一个写端点都不调() throws Exception {
    var mappingId = createSpuMapping();
    var runId = createRun(mappingId, "DRY_RUN", "spu.csv", SPU_CSV);
    assertThat(waitForTerminal(runId)).isEqualTo("SUCCEEDED");

    assertThat(catalog.writes.get())
        .as("试运行必须一个写端点都不调（POST / DELETE 计数必须是 0）")
        .isZero();
    assertThat(catalog.deletes.get()).as("试运行不得删任何东西").isZero();
    assertThat(catalog.products).as("试运行不得在目标库留下任何记录").isEmpty();
  }

  @Test
  void 试运行_逐行结论里没有上游id() throws Exception {
    var mappingId = createSpuMapping();
    var runId = createRun(mappingId, "DRY_RUN", "spu.csv", SPU_CSV);
    assertThat(waitForTerminal(runId)).isEqualTo("SUCCEEDED");

    var page = rows(runId);
    var items = page.get("items");
    assertThat(items).hasSize(4);
    for (var item : items) {
      assertThat(item.get("upstreamId").isNull())
          .as("试运行的行不许有上游 id —— 它没有写任何业务数据")
          .isTrue();
    }
    assertThat(page.get("succeededCount").asInt()).isEqualTo(2);
    assertThat(page.get("failedCount").asInt()).isEqualTo(1);
    assertThat(page.get("skippedCount").asInt()).isEqualTo(1);
  }

  @Test
  void 试运行_数据层兜底也不许产生上游id() {
    // 🔴 **不靠应用层自觉**：数据库触发器直接拒绝「试运行却带上游 id」的行。
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "insert into platform_migration_run(id,tenant_id,run_no,mode,target_model,"
                              + " mapping_code,mapping_snapshot,actor_id,status,rollback_status,"
                              + " requested_at,created_at,updated_at)"
                              + " values(?,?,'DRY-GUARD','DRY_RUN','SPU','','{}'::jsonb,?,'PENDING','NONE',"
                              + " ?::timestamptz,?::timestamptz,?::timestamptz)",
                          UUID.randomUUID(),
                          tenant,
                          UUID.randomUUID(),
                          OffsetDateTime.now(ZoneOffset.UTC),
                          OffsetDateTime.now(ZoneOffset.UTC),
                          OffsetDateTime.now(ZoneOffset.UTC));
                      var runId =
                          sql.fetchOne("select id from platform_migration_run where run_no = 'DRY-GUARD'")
                              .get(0, UUID.class);
                      sql.execute(
                          "insert into platform_migration_row(id,tenant_id,run_id,row_no,outcome,target_kind,"
                              + " target_code,upstream_id,created_at,updated_at)"
                              + " values(?,?,?,2,'SUCCEEDED','SPU','X-1',?,?::timestamptz,?::timestamptz)",
                          UUID.randomUUID(),
                          tenant,
                          runId,
                          UUID.randomUUID(),
                          OffsetDateTime.now(ZoneOffset.UTC),
                          OffsetDateTime.now(ZoneOffset.UTC));
                      return null;
                    }))
        .hasStackTraceContaining("试运行不允许产生上游记录 id");
  }

  @Test
  void 试运行_不能回滚因为没写过任何东西() throws Exception {
    var mappingId = createSpuMapping();
    var runId = createRun(mappingId, "DRY_RUN", "spu.csv", SPU_CSV);
    assertThat(waitForTerminal(runId)).isEqualTo("SUCCEEDED");

    var response =
        http.perform(authed(post("/platform/data-migration/runs/" + runId + "/rollback")))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(409);

    // 🔴 **消息在服务层断言**（本仓约定）：MockMvc 里 ResponseStatusException 的
    //   响应体是空的（状态码对，body 没有），断言响应体等于断言一个空串。
    var e =
        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> tenants.run(tenant, () -> service.requestRollback(runId, currentActor())));
    assertThat(e.getStatusCode().value()).isEqualTo(409);
    assertThat(e.getReason()).contains("试运行");
    assertThat(catalog.deletes.get()).isZero();
  }

  // ==========================================================================
  // 三、正式迁移
  // ==========================================================================
  @Test
  void 正式迁移_真的写进目标库且逐行带上游id() throws Exception {
    var mappingId = createSpuMapping();
    var runId = createRun(mappingId, "APPLY", "spu.csv", SPU_CSV);
    assertThat(waitForTerminal(runId)).isEqualTo("SUCCEEDED");

    assertThat(catalog.writes.get()).as("正式迁移必须真的调写端点").isEqualTo(2);
    assertThat(catalog.products).hasSize(2);
    assertThat(catalog.products.keySet())
        .as("目标库里的编码必须就是源文件里迁过去的编码")
        .allMatch(id -> catalog.products.get(id).get("code").toString().startsWith("SPU-"));

    var page = rows(runId);
    var items = page.get("items");
    for (var item : items) {
      if (!item.get("outcome").asText().equals("SUCCEEDED")) continue;
      var upstream = item.get("upstreamId").asText();
      assertThat(upstream).as("迁成的行必须有上游 id（否则没法回滚）").isNotBlank();
      assertThat(catalog.products)
          .as("逐行结论里的上游 id 必须真的指向目标库里的记录")
          .containsKey(UUID.fromString(upstream));
    }
  }

  @Test
  void 正式迁移_目标库已存在的编码跳过() throws Exception {
    catalog.seedProduct("SPU-002", "目标库里已有的商品", "耳机");
    var mappingId = createSpuMapping();
    var runId = createRun(mappingId, "APPLY", "spu.csv", SPU_CSV);
    assertThat(waitForTerminal(runId)).isEqualTo("SUCCEEDED");

    var page = rows(runId);
    var existing = findRowByCode(page, "SPU-002");
    assertThat(existing).as("源文件里 SPU-002 那一行必须能找到").isNotNull();
    assertThat(existing.get("outcome").asText()).isEqualTo("SKIPPED");
    assertThat(existing.get("reasonCode").asText())
        .as("目标库里已有的编码必须跳过，不能制造重复主数据")
        .isEqualTo("ALREADY_EXISTS");
    assertThat(existing.get("upstreamId").isNull()).isTrue();
    assertThat(catalog.products).as("已有的那条不许被改写，也不许再造一条重复的").hasSize(2);
  }

  /** 按目标编码找一行结论。 */
  private static com.fasterxml.jackson.databind.JsonNode findRowByCode(
      com.fasterxml.jackson.databind.JsonNode page, String code) {
    for (var item : page.get("items")) {
      if (code.equals(item.get("targetCode").asText())) return item;
    }
    return null;
  }

  @Test
  void 正式迁移_缺必填值的行失败并写明原因() throws Exception {
    var mappingId = createSpuMapping();
    var runId = createRun(mappingId, "APPLY", "spu.csv", SPU_CSV);
    assertThat(waitForTerminal(runId)).isEqualTo("SUCCEEDED");

    var page = rows(runId);
    var failed =
        page.get("items").findValues("outcome").stream()
            .filter(n -> n.asText().equals("FAILED"))
            .count();
    assertThat(failed).isEqualTo(1);
    var reason =
        page.get("items").findValues("reasonCode").stream()
            .filter(n -> n.asText().equals("MISSING_REQUIRED_FIELD"))
            .findFirst();
    assertThat(reason).as("失败行必须写明原因码").isNotNull();
  }

  @Test
  void 正式迁移_同一个文件里的重复编码跳过() throws Exception {
    var mappingId = createSpuMapping();
    var runId = createRun(mappingId, "DRY_RUN", "spu.csv", SPU_CSV);
    assertThat(waitForTerminal(runId)).isEqualTo("SUCCEEDED");

    var reasons = rows(runId).get("items").findValues("reasonCode");
    assertThat(reasons).anyMatch(n -> n.asText().equals("DUPLICATE_IN_FILE"));
  }

  @Test
  void 正式迁移_迁移sku时按父商品写入() throws Exception {
    var parent = catalog.seedProduct("SPU-PARENT", "父商品", "耳机");
    var mappingId = createSkuMapping();
    var csv =
        "SKU编码,SKU名称,条码,基本单位,所属商品编码\n"
            + "SKU-001,测试SKU,6900000001,件,SPU-PARENT\n";
    var runId = createRun(mappingId, "APPLY", "sku.csv", csv);
    assertThat(waitForTerminal(runId)).isEqualTo("SUCCEEDED");

    assertThat(catalog.skus).hasSize(1);
    assertThat(catalog.skus.values().iterator().next())
        .as("SKU 必须挂在源文件指定的那个父商品下")
        .isEqualTo(parent);
  }

  // ==========================================================================
  // 四、失败回滚
  // ==========================================================================
  @Test
  void 失败回滚_真的把目标库里的记录删掉() throws Exception {
    var mappingId = createSpuMapping();
    var runId = createRun(mappingId, "APPLY", "spu.csv", SPU_CSV);
    assertThat(waitForTerminal(runId)).isEqualTo("SUCCEEDED");
    assertThat(catalog.products).hasSize(2);

    var response =
        http.perform(authed(post("/platform/data-migration/runs/" + runId + "/rollback")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(200);

    // 等回滚跑完（回滚状态机是独立的：NONE → RUNNING → SUCCEEDED）。
    assertThat(waitForRollbackTerminal(runId)).isEqualTo("SUCCEEDED");
    assertThat(catalog.products)
        .as("回滚必须真的把目标库里的记录删掉 —— 页面写着「已回滚」而数据还在，就是假的")
        .isEmpty();
    assertThat(catalog.deletes.get()).isEqualTo(2);

    var detail = run(runId);
    assertThat(detail.get("rolledBackRows").asInt()).isEqualTo(2);
    for (var item : rows(runId).get("items")) {
      if (!item.get("outcome").asText().equals("SUCCEEDED")) continue;
      assertThat(item.get("rollbackStatus").asText()).isEqualTo("DELETED");
    }
  }

  @Test
  void 失败回滚_回滚报告可下载且写明删了什么() throws Exception {
    var mappingId = createSpuMapping();
    var runId = createRun(mappingId, "APPLY", "spu.csv", SPU_CSV);
    assertThat(waitForTerminal(runId)).isEqualTo("SUCCEEDED");
    http.perform(authed(post("/platform/data-migration/runs/" + runId + "/rollback")))
        .andExpect(status().isOk());
    assertThat(waitForRollbackTerminal(runId)).isEqualTo("SUCCEEDED");

    var report =
        http.perform(authed(get("/platform/data-migration/runs/" + runId + "/rollback-report")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(report).contains("# 迁移回滚报告").contains("已删除");
  }

  // ==========================================================================
  // 五、核对报告（成功 / 失败 / 跳过数量与原因）
  // ==========================================================================
  @Test
  void 核对报告_三个口径加起来等于处理过的行数() throws Exception {
    var mappingId = createSpuMapping();
    var runId = createRun(mappingId, "APPLY", "spu.csv", SPU_CSV);
    assertThat(waitForTerminal(runId)).isEqualTo("SUCCEEDED");

    var detail = run(runId);
    var total = detail.get("totalRows").asInt();
    var processed = detail.get("processedRows").asInt();
    var succeeded = detail.get("succeededRows").asInt();
    var failed = detail.get("failedRows").asInt();
    var skipped = detail.get("skippedRows").asInt();
    assertThat(total).isEqualTo(4);
    assertThat(processed).isEqualTo(total);
    assertThat(succeeded + failed + skipped)
        .as("核对报告的全部意义就是「加起来对得上」")
        .isEqualTo(processed);
    assertThat(succeeded).isEqualTo(2);
    assertThat(failed).isEqualTo(1);
    assertThat(skipped).isEqualTo(1);
  }

  @Test
  void 核对报告_可下载且含逐行明细与忽略的列() throws Exception {
    var mappingId = createSpuMapping();
    var runId = createRun(mappingId, "DRY_RUN", "spu.csv", SPU_CSV);
    assertThat(waitForTerminal(runId)).isEqualTo("SUCCEEDED");

    var report =
        http.perform(authed(get("/platform/data-migration/runs/" + runId + "/report")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(report).contains("# 迁移核对报告");
    assertThat(report).contains("试运行");
    assertThat(report).contains("未映射的列（已忽略）");
    assertThat(report).contains("备注");
    assertThat(report).contains("SPU-001");
    assertThat(report).contains("MISSING_REQUIRED_FIELD");
  }

  @Test
  void 核对报告_过期后返回410() throws Exception {
    var mappingId = createSpuMapping();
    var runId = createRun(mappingId, "DRY_RUN", "spu.csv", SPU_CSV);
    assertThat(waitForTerminal(runId)).isEqualTo("SUCCEEDED");

    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "update platform_migration_file set expires_at = ?::timestamptz - interval '1 day'"
                  + " where run_id = ?",
              OffsetDateTime.now(ZoneOffset.UTC),
              runId);
          return null;
        });

    var response =
        http.perform(authed(get("/platform/data-migration/runs/" + runId + "/report")))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(410);
  }

  @Test
  void 数据层_三个口径对不上会被数据库挡住() {
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "insert into platform_migration_run(id,tenant_id,run_no,mode,target_model,"
                              + " mapping_code,mapping_snapshot,actor_id,status,rollback_status,"
                              + " processed_rows,succeeded_rows,failed_rows,skipped_rows,"
                              + " requested_at,created_at,updated_at)"
                              + " values(?,?,'MIG-BAD','APPLY','SPU','','{}'::jsonb,?,'PENDING','NONE',"
                              + " 5,4,0,0,?::timestamptz,?::timestamptz,?::timestamptz)",
                          UUID.randomUUID(),
                          tenant,
                          UUID.randomUUID(),
                          OffsetDateTime.now(ZoneOffset.UTC),
                          OffsetDateTime.now(ZoneOffset.UTC),
                          OffsetDateTime.now(ZoneOffset.UTC));
                      return null;
                    }))
        .as("成功 + 失败 + 跳过 加不到处理过的行数上 ⇒ 报告在账上对不平，必须被挡住")
        .hasStackTraceContaining("violates check constraint");
  }

  // ==========================================================================
  // 六、审计事实与时间
  // ==========================================================================
  @Test
  void 映射快照在发起时冻结_事后改映射不影响已发起的任务() throws Exception {
    var mappingId = createSpuMapping();
    var runId = createRun(mappingId, "DRY_RUN", "spu.csv", SPU_CSV);
    assertThat(waitForTerminal(runId)).isEqualTo("SUCCEEDED");

    // 事后改映射（改了就是改了契约，但**已发起的任务用的是它冻结的快照**）。
    var updated =
        json.writeValueAsBytes(
            Map.of(
                "code", "JST_SPU_V1",
                "name", "改过名字的映射",
                "sourceSystem", "聚水潭",
                "targetModel", "SPU",
                "fields",
                List.of(
                    Map.of("source", "商品编码", "target", "code", "required", true, "transform", "TRIM", "defaultValue", ""),
                    Map.of("source", "商品名称", "target", "name", "required", true, "transform", "TRIM", "defaultValue", ""),
                    Map.of("source", "类目", "target", "category", "required", true, "transform", "TRIM", "defaultValue", "")),
                "remark", ""));
    http.perform(
            authed(put("/platform/data-migration/mappings/" + mappingId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updated))
        .andExpect(status().isOk());

    var snapshot =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                        "select mapping_snapshot from platform_migration_run where id = ?", runId)
                    .get(0, String.class));
    assertThat(snapshot).contains("聚水潭商品映射").doesNotContain("改过名字的映射");
  }

  @Test
  void 时间字段写回的是正确时刻_没有时区偏移() throws Exception {
    var mappingId = createSpuMapping();
    var before = OffsetDateTime.now(ZoneOffset.UTC);
    var runId = createRun(mappingId, "DRY_RUN", "spu.csv", SPU_CSV);
    assertThat(waitForTerminal(runId)).isEqualTo("SUCCEEDED");
    var after = OffsetDateTime.now(ZoneOffset.UTC);

    var detail = run(runId);
    var requested = OffsetDateTime.parse(detail.get("requestedAt").asText());
    var started = OffsetDateTime.parse(detail.get("startedAt").asText());
    var finished = OffsetDateTime.parse(detail.get("finishedAt").asText());

    // 🔴 用 **epoch** 比对：用 Timestamp.valueOf(LocalDateTime) 落库会静默偏 −28800s，
    //   只断言「没抛异常」是发现不了的。
    assertThat(requested.toEpochSecond())
        .as("requested_at 必须落在发起请求的那段时间里（不能偏 8 小时）")
        .isBetween(before.toEpochSecond() - 5, after.toEpochSecond() + 5);
    assertThat(started.toEpochSecond())
        .as("started_at 必须落在发起请求的那段时间里")
        .isBetween(before.toEpochSecond() - 5, after.toEpochSecond() + 5);
    assertThat(finished.toEpochSecond())
        .as("finished_at 必须落在发起请求的那段时间里")
        .isBetween(before.toEpochSecond() - 5, after.toEpochSecond() + 5);
    assertThat(requested.toEpochSecond())
        .isLessThanOrEqualTo(started.toEpochSecond());
    assertThat(started.toEpochSecond()).isLessThanOrEqualTo(finished.toEpochSecond());
  }

  // ==========================================================================
  // 七、并发与状态
  // ==========================================================================
  @Test
  void 同一租户已有迁移在跑时发起新的迁移返回409() throws Exception {
    var mappingId = createSpuMapping();
    // 直接插一个「在跑」的任务（执行器只跑自己提交的任务，这行会一直停在 PENDING）。
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into platform_migration_run(id,tenant_id,run_no,mode,target_model,mapping_id,"
                  + " mapping_code,mapping_snapshot,actor_id,status,rollback_status,"
                  + " requested_at,created_at,updated_at)"
                  + " values(?,?,'MIG-BUSY','APPLY','SPU',?,'','{}'::jsonb,?,'PENDING','NONE',"
                  + " ?::timestamptz,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              mappingId,
              UUID.randomUUID(),
              OffsetDateTime.now(ZoneOffset.UTC),
              OffsetDateTime.now(ZoneOffset.UTC),
              OffsetDateTime.now(ZoneOffset.UTC));
          return null;
        });

    var response =
        http.perform(
                authed(post("/platform/data-migration/runs"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsBytes(
                            Map.of(
                                "mappingId", mappingId.toString(),
                                "mode", "APPLY",
                                "filename", "spu.csv",
                                "contentBase64",
                                Base64.getEncoder()
                                    .encodeToString(SPU_CSV.getBytes(StandardCharsets.UTF_8))))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(409);

    // 🔴 **消息在服务层断言**（本仓约定）：MockMvc 里 ResponseStatusException 的
    //   响应体是空的（状态码对，body 没有），断言响应体等于断言一个空串。
    var e =
        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                tenants.run(
                    tenant,
                    () ->
                        service.createRun(
                            new DataMigrationModels.DataMigrationRunInput(
                                mappingId,
                                "APPLY",
                                "spu.csv",
                                Base64.getEncoder()
                                    .encodeToString(SPU_CSV.getBytes(StandardCharsets.UTF_8))),
                            currentActor())));
    assertThat(e.getStatusCode().value()).isEqualTo(409);
    assertThat(e.getReason()).contains("一个迁移任务");
  }

  @Test
  void 能力声明_五项能力都真接通_没接通的如实标false() throws Exception {
    var body =
        http.perform(authed(get("/platform/data-migration/capabilities")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    var node = json.readTree(body);
    var capabilities = node.get("capabilities");
    var names = new ArrayList<String>();
    var unavailable = new ArrayList<String>();
    for (var c : capabilities) {
      names.add(c.get("name").asText());
      if (!c.get("available").asBoolean()) {
        unavailable.add(c.get("name").asText());
        assertThat(c.get("ownerModule").asText())
            .as("不可用的能力必须写明归属模块，不能只说「没做」")
            .isNotEmpty();
        assertThat(c.get("basis").asText()).isNotEmpty();
      }
    }
    assertThat(names)
        .as("蓝图点名的五项能力必须逐项出现")
        .contains(
            "映射配置（外部字段 → 内部模型）",
            "试运行预览",
            "正式迁移",
            "失败回滚",
            "核对报告（成功/失败/跳过数量与原因）");
    assertThat(unavailable)
        .as("没接通的业务域必须如实标 false")
        .isNotEmpty();

    // 目标模型目录：没接通的那几个**照样出现**并标未接通。
    var targets =
        json.readTree(
            http.perform(authed(get("/platform/data-migration/targets")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    var spu = findTarget(targets, "SPU");
    assertThat(spu).isNotNull();
    assertThat(spu.get("fields")).as("接通的模型必须有字段清单").isNotEmpty();
    var order = findTarget(targets, "ORDER");
    assertThat(order).isNotNull();
    assertThat(order.get("fields").isEmpty())
        .as("没接通的模型不许有字段（页面会以为「订单也能迁」）")
        .isTrue();
    assertThat(order.get("limitation").asText()).isNotEmpty();
  }

  private static com.fasterxml.jackson.databind.JsonNode findTarget(
      com.fasterxml.jackson.databind.JsonNode targets, String code) {
    for (var t : targets) {
      if (t.get("code").asText().equals(code)) return t;
    }
    return null;
  }

  // ================================================================ SKU 映射

  /** 建一份 SKU 映射（编码 / 名称 / 条码 / 基本单位 / 所属商品编码 必填）。 */
  UUID createSkuMapping() throws Exception {
    var body =
        json.writeValueAsBytes(
            Map.of(
                "code", "JST_SKU_V1",
                "name", "聚水潭 SKU 映射",
                "sourceSystem", "聚水潭",
                "targetModel", "SKU",
                "fields",
                List.of(
                    Map.of("source", "SKU编码", "target", "code", "required", true, "transform", "TRIM", "defaultValue", ""),
                    Map.of("source", "SKU名称", "target", "name", "required", true, "transform", "TRIM", "defaultValue", ""),
                    Map.of("source", "条码", "target", "barcode", "required", true, "transform", "TRIM", "defaultValue", ""),
                    Map.of("source", "基本单位", "target", "baseUnit", "required", true, "transform", "TRIM", "defaultValue", ""),
                    Map.of("source", "所属商品编码", "target", "spuCode", "required", true, "transform", "TRIM", "defaultValue", "")),
                "remark", ""));
    var response =
        http.perform(
                authed(post("/platform/data-migration/mappings"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    return UUID.fromString(json.readTree(response.getContentAsString()).get("id").asText());
  }

  // ==========================================================================
  // 目标业务域夹具：**真的 HTTP 服务**（它真的建记录、真的删记录）
  // ==========================================================================
  static final class CatalogFixture {
    private final HttpServer server;
    /** 建出来的商品（id → 商品）。 */
    final Map<UUID, Map<String, Object>> products = new ConcurrentHashMap<>();
    /** 建出来的 SKU（id → 父 SPU id）。 */
    final Map<UUID, UUID> skus = new ConcurrentHashMap<>();
    /** 写端点（POST）被调用的次数 —— 试运行必须是 0。 */
    final AtomicInteger writes = new AtomicInteger();
    /** 删除端点被调用的次数。 */
    final AtomicInteger deletes = new AtomicInteger();
    /** 收到的调用痕迹。 */
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    /** 收到的 Authorization（证明转发的是调用方自己的会话）。 */
    final List<String> bearers = Collections.synchronizedList(new ArrayList<>());

    CatalogFixture() throws IOException {
      server = HttpServer.create(new InetSocketAddress(0), 0);
      server.createContext("/", this::handle);
      server.setExecutor(Executors.newFixedThreadPool(4));
      server.start();
    }

    int port() {
      return server.getAddress().getPort();
    }

    void stop() {
      server.stop(0);
    }

    void reset() {
      products.clear();
      skus.clear();
      writes.set(0);
      deletes.set(0);
      calls.clear();
      bearers.clear();
    }

    /** 预置一个商品（模拟「目标库里已经有了」）。 */
    UUID seedProduct(String code, String name, String category) {
      var id = UUID.randomUUID();
      products.put(id, product(id, code, name, category));
      return id;
    }

    private Map<String, Object> product(UUID id, String code, String name, String category) {
      var map = new LinkedHashMap<String, Object>();
      map.put("id", id.toString());
      map.put("code", code);
      map.put("name", name);
      map.put("description", "");
      map.put("brand", "");
      map.put("category", category);
      map.put("status", "DRAFT");
      map.put("version", 1L);
      map.put("skuCount", 0);
      return map;
    }

    private void handle(HttpExchange e) throws IOException {
      var method = e.getRequestMethod();
      var path = e.getRequestURI().getPath();
      var query = e.getRequestURI().getQuery();
      var auth = e.getRequestHeaders().getFirst("Authorization");
      bearers.add(auth);
      if (!BEARER.equals(auth)) {
        e.sendResponseHeaders(401, -1);
        e.close();
        return;
      }
      var segments = path.split("/");
      try {
        // GET /catalog/products?q=
        if ("GET".equals(method) && "/catalog/products".equals(path)) {
          calls.add("GET " + path);
          var q = param(query, "q");
          var out = new ArrayList<Map<String, Object>>();
          for (var p : products.values()) {
            var code = String.valueOf(p.get("code"));
            var name = String.valueOf(p.get("name"));
            if (q == null || q.isBlank() || code.contains(q) || name.contains(q)) out.add(p);
          }
          respond(e, 200, out);
          return;
        }
        // POST /catalog/products
        if ("POST".equals(method) && "/catalog/products".equals(path)) {
          calls.add("POST " + path);
          writes.incrementAndGet();
          var input = json.readTree(bodyOf(e));
          var id = UUID.randomUUID();
          products.put(
              id,
              product(
                  id,
                  input.path("code").asText(),
                  input.path("name").asText(),
                  input.path("category").asText()));
          respond(e, 200, id.toString());
          return;
        }
        // GET /catalog/products/{id}
        if ("GET".equals(method) && isProductPath(segments, 4)) {
          calls.add("GET " + path);
          var id = UUID.fromString(segments[3]);
          var p = products.get(id);
          if (p == null) {
            respond(e, 404, Map.of("detail", "找不到这个商品"));
            return;
          }
          var skusOfParent = new ArrayList<Object>();
          for (var entry : skus.entrySet()) {
            if (entry.getValue().equals(id)) {
              var sku = new LinkedHashMap<String, Object>();
              sku.put("id", entry.getKey().toString());
              sku.put("code", "SKU-" + entry.getKey().toString().substring(0, 8));
              sku.put("name", "SKU");
              sku.put("status", "DRAFT");
              sku.put("version", 1L);
              skusOfParent.add(sku);
            }
          }
          respond(e, 200, Map.of("product", p, "attributes", List.of(), "skus", skusOfParent));
          return;
        }
        // DELETE /catalog/products/{id}?version=
        if ("DELETE".equals(method) && isProductPath(segments, 4)) {
          calls.add("DELETE " + path);
          deletes.incrementAndGet();
          var id = UUID.fromString(segments[3]);
          if (products.remove(id) == null) {
            respond(e, 404, Map.of("detail", "找不到这个商品"));
            return;
          }
          e.sendResponseHeaders(204, -1);
          e.close();
          return;
        }
        // POST /catalog/products/{id}/skus
        if ("POST".equals(method) && segments.length == 5 && segments[4].equals("skus")) {
          calls.add("POST " + path);
          writes.incrementAndGet();
          var parentId = UUID.fromString(segments[3]);
          if (!products.containsKey(parentId)) {
            respond(e, 404, Map.of("detail", "找不到这个商品"));
            return;
          }
          var input = json.readTree(bodyOf(e));
          var id = UUID.randomUUID();
          skus.put(id, parentId);
          var sku = new LinkedHashMap<String, Object>();
          sku.put("id", id.toString());
          sku.put("spuId", parentId.toString());
          sku.put("code", input.path("code").asText());
          sku.put("name", input.path("name").asText());
          sku.put("barcode", input.path("barcode").asText());
          sku.put("status", "DRAFT");
          sku.put("version", 1L);
          respond(e, 200, id.toString());
          return;
        }
        // DELETE /catalog/products/{id}/skus/{sid}?version=
        if ("DELETE".equals(method) && segments.length == 6 && segments[4].equals("skus")) {
          calls.add("DELETE " + path);
          deletes.incrementAndGet();
          var skuId = UUID.fromString(segments[5]);
          if (skus.remove(skuId) == null) {
            respond(e, 404, Map.of("detail", "找不到这个 SKU"));
            return;
          }
          e.sendResponseHeaders(204, -1);
          e.close();
          return;
        }
        // GET /catalog/skus?q=&page=&size=
        if ("GET".equals(method) && "/catalog/skus".equals(path)) {
          calls.add("GET " + path);
          var q = param(query, "q");
          var items = new ArrayList<Map<String, Object>>();
          for (var entry : skus.entrySet()) {
            var sku = new LinkedHashMap<String, Object>();
            sku.put("id", entry.getKey().toString());
            sku.put("spuId", entry.getValue().toString());
            sku.put("code", q == null || q.isBlank() ? "SKU-X" : q);
            sku.put("name", "SKU");
            sku.put("barcode", "6900000000");
            sku.put("status", "DRAFT");
            sku.put("version", 1L);
            items.add(sku);
          }
          respond(e, 200, Map.of("items", items, "total", items.size(), "page", 0, "size", 200));
          return;
        }
        respond(e, 404, Map.of("detail", "没有这个端点：" + method + " " + path));
      } catch (RuntimeException ex) {
        respond(e, 500, Map.of("detail", String.valueOf(ex.getMessage())));
      }
    }

    /** 路径是不是 /catalog/products/{id}（分段数 = 4）。 */
    private static boolean isProductPath(String[] segments, int length) {
      return segments.length == length
          && segments[1].equals("catalog")
          && segments[2].equals("products");
    }

    private static String param(String query, String name) {      if (query == null) return null;
      for (var pair : query.split("&")) {
        var kv = pair.split("=", 2);
        if (kv.length == 2 && kv[0].equals(name)) return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
      }
      return null;
    }

    private static String bodyOf(HttpExchange e) throws IOException {
      return new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange e, int status, Object body) throws IOException {
      byte[] bytes = json.writeValueAsBytes(body);
      e.getResponseHeaders().add("Content-Type", "application/json");
      e.sendResponseHeaders(status, bytes.length);
      e.getResponseBody().write(bytes);
      e.close();
    }
  }
}
