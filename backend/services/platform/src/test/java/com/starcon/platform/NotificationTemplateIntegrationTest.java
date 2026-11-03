package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.NotificationTemplateModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
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
 * 18.5.1.1「模板管理」的**功能**集成测试，跑在真实 PostgreSQL 16 上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p>本测试盯住六件事：
 *
 * <ol>
 *   <li><b>版本不可篡改</b>：编辑 = 新增版本；老版本行一个字节都不动；运行角色连
 *       UPDATE / DELETE 权限都没有（直连 SQL 改不动）。
 *   <li><b>历史通知保留当时内容</b>：模板改版后，**按旧时刻**解析仍然是**旧内容**。
 *   <li><b>渲染失败就点名报错</b>：缺变量 / 多余变量 / 未知变量 / 语法错误 / 类型不符，
 *       一律点名报错，**绝不**返回空串或残留 {@code {{xxx}}}。
 *   <li><b>渲染预览不落库</b>。
 *   <li><b>真分页真筛选</b>：列表按场景 / 语言 / 启用状态筛得动，翻页翻得动。
 *   <li><b>时间没被时区吃掉</b>：写回库的启用时刻与读出来的是同一个时刻。
 * </ol>
 *
 * <p>租户隔离的三条断言在 {@code NotificationTemplateTenancyIntegrationTest} 里（同一批表、同一套夹具）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationTemplateIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer catalog;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

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
    var permissions =
        List.of(
            "platform.template.read",
            "platform.template.create",
            "platform.template.update",
            "platform.template.delete");
    actors.put(
        "session-a",
        new AccessService.Access(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Test",
            UUID.randomUUID(),
            null,
            permissions,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions))));
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

      // catalog 上游桩：模拟**上游服务返回什么**，不是本模块的数据源。
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/titles/templates",
          e -> {
            if (!"session-a".equals(e.getRequestHeaders().getFirst("Authorization"))) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body =
                json.writeValueAsBytes(
                    List.of(
                        Map.of(
                            "id",
                            "6f1f0e2a-1c3d-4f5a-9b7c-8d9e0f1a2b3c",
                            "code",
                            "TITLE-STD",
                            "name",
                            "标准标题",
                            "note",
                            "品牌+品类+核心词",
                            "pattern",
                            "{{brand}} {{category}} {{core}}",
                            "enabled",
                            true,
                            "version",
                            3)));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      catalog.createContext(
          "/catalog/description-templates/templates",
          e -> {
            byte[] body = json.writeValueAsBytes(List.of());
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      catalog.setExecutor(Executors.newFixedThreadPool(2));
      catalog.start();
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
    r.add("CATALOG_URL", () -> "http://127.0.0.1:" + catalog.getAddress().getPort());
  }

  @Autowired DSLContext sql;
  @Autowired TenantTransactions tenants;
  @Autowired MockMvc http;

  private UUID tenant() {
    return actors.get("session-a").tenantId();
  }

  /** 直接读库时**必须**在可信租户事务里读 —— 否则 RLS 会把行全部挡掉。 */
  private <T> T inTenant(java.util.function.Supplier<T> work) {
    return tenants.run(tenant(), work);
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_notify_template_activation, platform_notify_template_version,"
              + " platform_notify_template, platform_app_audit_event, platform_app_quota_usage,"
              + " platform_app_quota, platform_app_ip_allowlist, platform_app_scope,"
              + " platform_app_credential, platform_app restart identity cascade");
    }
  }

  // ================================================================ 夹具

  private NotifyTemplateRow createTemplate(
      String key, String scene, String language, String body, List<NotifyTemplateVariableDefinition> vars)
      throws Exception {
    var payload =
        new NotifyTemplateInput(key, scene, language, "测试模板", "说明", "标题", body, vars, "第 1 版");
    var response =
        http.perform(
                post("/platform/ai/templates")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(payload)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(response, NotifyTemplateRow.class);
  }

  private NotifyTemplateVersionDetail appendVersion(UUID templateId, String body, String note)
      throws Exception {
    var payload = new NotifyTemplateVersionInput("标题", body, List.of(), note);
    var response =
        http.perform(
                post("/platform/ai/templates/" + templateId + "/versions")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(payload)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(response, NotifyTemplateVersionDetail.class);
  }

  private NotifyTemplateDetail enable(UUID templateId, UUID versionId) throws Exception {
    var response =
        http.perform(
                post("/platform/ai/templates/" + templateId + "/versions/" + versionId + "/enable")
                    .header("Authorization", "session-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(response, NotifyTemplateDetail.class);
  }

  private NotifyTemplateResolved resolve(String key, String scene, String language, OffsetDateTime at)
      throws Exception {
    var response =
        http.perform(
                get("/platform/ai/templates/resolve")
                    .header("Authorization", "session-a")
                    .param("templateKey", key)
                    .param("scene", scene)
                    .param("language", language)
                    .param("at", at.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(response, NotifyTemplateResolved.class);
  }

  // ==========================================================================
  // 迁移确实落地了
  // ==========================================================================
  @Test
  void 迁移落地且版本行在权限层就不可篡改() throws Exception {
    for (String table :
        List.of(
            "platform_notify_template",
            "platform_notify_template_version",
            "platform_notify_template_activation")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V427 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住 owner 身份").isTrue();
      var nullable =
          sql.fetchOne(
                  "select is_nullable from information_schema.columns"
                      + " where table_name = ? and column_name = 'tenant_id'",
                  table)
              .get(0, String.class);
      assertThat(nullable).as(table + " 的 tenant_id 必须是 NOT NULL").isEqualTo("NO");
    }

    // 🔴 版本行：**不给 UPDATE / DELETE** —— 版本不可篡改由权限层兜底。
    for (String privilege : List.of("UPDATE", "DELETE")) {
      assertThat(
              sql.fetchOne(
                      "select has_table_privilege('platform_app','platform_notify_template_version',?)",
                      privilege)
                  .get(0, Boolean.class))
          .as("platform_app 不该有版本行的 " + privilege + " 权限")
          .isFalse();
    }
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('platform_app','platform_notify_template_version','INSERT')")
                .get(0, Boolean.class))
        .as("platform_app 必须能写新版本")
        .isTrue();
  }

  @Test
  void 场景词表与数据库约束逐字同表() throws Exception {
    // 枚举与 CHECK 约束漂移 ⇒ 前端下拉里出现一个数据库写不进去的场景。
    var check =
        String.join(
            " ",
            sql.fetch(
                    "select pg_get_constraintdef(oid) as def from pg_constraint"
                        + " where conrelid = 'platform_notify_template'::regclass and contype = 'c'")
                .map(r -> r.get("def", String.class)));
    for (var scene : NotifyTemplateScene.values()) {
      assertThat(check).as("CHECK 约束里必须有场景 " + scene.name()).contains(scene.name());
    }
    assertThat(NotifyTemplateScene.values()).hasSize(7);
  }

  @Test
  void 同一模板同时只允许一个启用版本由数据库兜底() throws Exception {
    var template = createTemplate("ORDER_PAID", "ORDER_STATUS", "zh-CN", "正文", List.of());
    var version = inTenant(
        () ->
            sql.fetchOne(
                    "select id from platform_notify_template_version where template_id = ?",
                    template.id())
                .get("id", UUID.class));
    enable(template.id(), version);

    // 直接插第二条「生效中」的启用记录 ⇒ 唯一索引挡住。
    assertThatThrownBy(
            () ->
                inTenant(
                    () ->
                        sql.execute(
                            "insert into platform_notify_template_activation(id,tenant_id,template_id,"
                                + " version_id,activated_at) values(?,?,?,?,?::timestamptz)",
                            UUID.randomUUID(),
                            tenant(),
                            template.id(),
                            version,
                            OffsetDateTime.now(ZoneOffset.UTC))))
        .as("第二个「生效中」的版本必须在数据库层写不进去")
        .hasStackTraceContaining("duplicate key");
  }

  // ==========================================================================
  // 版本不可篡改
  // ==========================================================================
  @Test
  void 编辑只新增版本老版本一个字节都不动() throws Exception {
    var template = createTemplate("ORDER_PAID", "ORDER_STATUS", "zh-CN", "第一版正文", List.of());
    var v1 =
        inTenant(
            () ->
                sql.fetchOne(
                        "select body from platform_notify_template_version where template_id = ?"
                            + " and version_no = 1",
                        template.id())
                    .get("body", String.class));
    assertThat(v1).isEqualTo("第一版正文");

    var v2 = appendVersion(template.id(), "第二版正文", "改了文案");
    assertThat(v2.version().versionNo()).isEqualTo(2);

    // 老版本行原样不动。
    var bodies =
        inTenant(
            () ->
                sql.fetch(
                        "select version_no, body from platform_notify_template_version"
                            + " where template_id = ? order by version_no",
                        template.id())
                    .map(r -> r.get("version_no", Integer.class) + ":" + r.get("body", String.class)));
    assertThat(bodies).containsExactly("1:第一版正文", "2:第二版正文");
  }

  @Test
  void 直连sql也改不动版本行() throws Exception {
    var template = createTemplate("ORDER_PAID", "ORDER_STATUS", "zh-CN", "原始正文", List.of());
    var versionId =
        inTenant(
            () ->
                sql.fetchOne(
                        "select id from platform_notify_template_version where template_id = ?",
                        template.id())
                    .get("id", UUID.class));

    // 运行角色连 UPDATE 权限都没有 ⇒ 直连 SQL 也改不动。
    assertThatThrownBy(
            () ->
                sql.execute(
                    "update platform_notify_template_version set body = '被篡改了' where id = ?",
                    versionId))
        .as("版本行必须改不动")
        .hasStackTraceContaining("permission denied");

    var body =
        inTenant(
            () ->
                sql.fetchOne(
                        "select body from platform_notify_template_version where id = ?", versionId)
                    .get("body", String.class));
    assertThat(body).isEqualTo("原始正文");
  }

  // ==========================================================================
  // 历史通知保留当时内容（核心断言）
  // ==========================================================================
  @Test
  void 模板改版后按旧时刻解析仍然是旧内容() throws Exception {
    var template = createTemplate("ORDER_PAID", "ORDER_STATUS", "zh-CN", "旧内容", List.of());
    var v1 =
        inTenant(
            () ->
                sql.fetchOne(
                        "select id from platform_notify_template_version where template_id = ?"
                            + " and version_no = 1",
                        template.id())
                    .get("id", UUID.class));
    enable(template.id(), v1);
    var firstActivatedAt =
        inTenant(
            () ->
                sql.fetchOne(
                        "select activated_at from platform_notify_template_activation"
                            + " where template_id = ? and version_id = ?",
                        template.id(),
                        v1)
                    .get("activated_at", OffsetDateTime.class));

    // 改版：新版本接管（模板当前有生效版本）。
    var v2 = appendVersion(template.id(), "新内容", "改版");
    var secondActivatedAt =
        inTenant(
            () ->
                sql.fetchOne(
                        "select activated_at from platform_notify_template_activation"
                            + " where template_id = ? and version_id = ?",
                        template.id(),
                        v2.version().id())
                    .get("activated_at", OffsetDateTime.class));
    assertThat(secondActivatedAt).isAfter(firstActivatedAt);

    // 🔴 **旧时刻**解析 ⇒ 仍然是旧内容、旧版本 id。
    var atOldMoment = resolve("ORDER_PAID", "ORDER_STATUS", "zh-CN", firstActivatedAt);
    assertThat(atOldMoment.body()).isEqualTo("旧内容");
    assertThat(atOldMoment.versionId()).isEqualTo(v1);
    assertThat(atOldMoment.versionNo()).isEqualTo(1);

    // **新时刻**解析 ⇒ 新内容、新版本 id。
    var atNewMoment = resolve("ORDER_PAID", "ORDER_STATUS", "zh-CN", secondActivatedAt);
    assertThat(atNewMoment.body()).isEqualTo("新内容");
    assertThat(atNewMoment.versionId()).isEqualTo(v2.version().id());
    assertThat(atNewMoment.versionNo()).isEqualTo(2);
  }

  @Test
  void 停用版本后那一时刻没有生效版本() throws Exception {
    var template = createTemplate("ORDER_PAID", "ORDER_STATUS", "zh-CN", "正文", List.of());
    var v1 =
        inTenant(
            () ->
                sql.fetchOne(
                        "select id from platform_notify_template_version where template_id = ?",
                        template.id())
                    .get("id", UUID.class));
    enable(template.id(), v1);
    http.perform(
            post("/platform/ai/templates/" + template.id() + "/versions/" + v1 + "/disable")
                .header("Authorization", "session-a"))
        .andExpect(status().isOk());

    // 版本行还在（不可篡改、不可物理删），但它**不再生效**。
    assertThat(
            inTenant(
                () ->
                    sql.fetchOne(
                            "select body from platform_notify_template_version where id = ?", v1)
                        .get("body", String.class)))
        .isEqualTo("正文");
    http.perform(
            get("/platform/ai/templates/resolve")
                .header("Authorization", "session-a")
                .param("templateKey", "ORDER_PAID")
                .param("scene", "ORDER_STATUS")
                .param("language", "zh-CN")
                .param("at", OffsetDateTime.now(ZoneOffset.UTC).toString()))
        .andExpect(status().isNotFound());
  }

  @Test
  void 启用时刻写回库与读出来是同一个时刻() throws Exception {
    var before = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1);
    var template = createTemplate("ORDER_PAID", "ORDER_STATUS", "zh-CN", "正文", List.of());
    var v1 =
        inTenant(
            () ->
                sql.fetchOne(
                        "select id from platform_notify_template_version where template_id = ?",
                        template.id())
                    .get("id", UUID.class));
    enable(template.id(), v1);
    var after = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1);

    var stored =
        inTenant(
            () ->
                sql.fetchOne(
                        "select activated_at from platform_notify_template_activation"
                            + " where template_id = ? and version_id = ?",
                        template.id(),
                        v1)
                    .get("activated_at", OffsetDateTime.class));
    // 🔴 断言**写回的时刻**，而不是「没抛异常」：偏 8 小时照样能通过后者。
    //   比较用 instant：OffsetDateTime 的 offset 只是表示方式（JVM 默认时区），
    //   真正要守住的是「哪一个时刻」。
    assertThat(stored.toInstant())
        .isAfterOrEqualTo(before.toInstant())
        .isBeforeOrEqualTo(after.toInstant());
    // 接口读出来的与库里存的必须是**同一个时刻**。
    assertThat(stored.toInstant())
        .isEqualTo(enabledVersionActivatedAt(template.id(), v1).toInstant());
  }

  // ==========================================================================
  // 渲染预览
  // ==========================================================================
  @Test
  void 渲染缺变量报错并点名缺哪个() throws Exception {
    var payload =
        new NotifyTemplateRenderInput(
            null,
            null,
            "标题",
            "买家 {{buyer_name}} 的订单 {{order_no}} 已发货",
            List.of(
                new NotifyTemplateVariableDefinition("buyer_name", "TEXT", true, null, ""),
                new NotifyTemplateVariableDefinition("order_no", "TEXT", true, null, "")),
            Map.of("buyer_name", "小明"));
    var response =
        http.perform(
                post("/platform/ai/templates/render-preview")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(payload)))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var result = json.readValue(response, NotifyTemplateRenderResult.class);
    assertThat(result.ok()).isFalse();
    assertThat(result.renderedBody()).as("渲染失败时正文必须为 null（绝不是空串）").isNull();
    var missing =
        result.errors().stream()
            .filter(e -> "MISSING_VARIABLE".equals(e.code()))
            .findFirst()
            .orElseThrow();
    assertThat(missing.variables()).containsExactly("order_no");
    assertThat(missing.message()).contains("order_no");
  }

  @Test
  void 渲染未知变量报错并点名是哪个() throws Exception {
    // 正文里写了 {{nickname}}，但变量定义里**只声明了** buyer_name ⇒ 未知变量。
    var payload =
        new NotifyTemplateRenderInput(
            null,
            null,
            "",
            "你好 {{buyer_name}}（{{nickname}}）",
            List.of(new NotifyTemplateVariableDefinition("buyer_name", "TEXT", true, null, "")),
            Map.of("buyer_name", "小明"));
    var response =
        http.perform(
                post("/platform/ai/templates/render-preview")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(payload)))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var result = json.readValue(response, NotifyTemplateRenderResult.class);
    var unknown =
        result.errors().stream()
            .filter(e -> "UNKNOWN_VARIABLE".equals(e.code()))
            .findFirst()
            .orElseThrow();
    assertThat(unknown.variables()).containsExactly("nickname");
    assertThat(unknown.message()).contains("nickname");
  }

  @Test
  void 渲染多余变量报错() throws Exception {
    var payload =
        new NotifyTemplateRenderInput(
            null,
            null,
            "",
            "你好 {{buyer_name}}",
            List.of(new NotifyTemplateVariableDefinition("buyer_name", "TEXT", true, null, "")),
            Map.of("buyer_name", "小明", "extra_one", "多余"));
    var response =
        http.perform(
                post("/platform/ai/templates/render-preview")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(payload)))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var result = json.readValue(response, NotifyTemplateRenderResult.class);
    assertThat(result.errors().stream().anyMatch(e -> "EXTRA_VARIABLE".equals(e.code()))).isTrue();
  }

  @Test
  void 模板语法错误报错() throws Exception {
    var payload =
        new NotifyTemplateRenderInput(
            null,
            null,
            "",
            "你好 {{buyer_name",
            List.of(new NotifyTemplateVariableDefinition("buyer_name", "TEXT", true, null, "")),
            Map.of("buyer_name", "小明"));
    var response =
        http.perform(
                post("/platform/ai/templates/render-preview")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(payload)))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var result = json.readValue(response, NotifyTemplateRenderResult.class);
    assertThat(result.errors().stream().anyMatch(e -> "SYNTAX_ERROR".equals(e.code()))).isTrue();
    assertThat(result.renderedBody()).isNull();
  }

  @Test
  void 变量类型不符报错() throws Exception {
    var payload =
        new NotifyTemplateRenderInput(
            null,
            null,
            "",
            "金额 {{amount}}",
            List.of(new NotifyTemplateVariableDefinition("amount", "NUMBER", true, null, "")),
            Map.of("amount", "十二块"));
    var response =
        http.perform(
                post("/platform/ai/templates/render-preview")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(payload)))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var result = json.readValue(response, NotifyTemplateRenderResult.class);
    assertThat(result.errors().stream().anyMatch(e -> "INVALID_VALUE".equals(e.code()))).isTrue();
  }

  @Test
  void 渲染成功且不落库() throws Exception {
    var payload =
        new NotifyTemplateRenderInput(
            null,
            null,
            "发货通知",
            "买家 {{buyer_name}} 的订单 {{order_no}} 已发货",
            List.of(
                new NotifyTemplateVariableDefinition("buyer_name", "TEXT", true, null, ""),
                new NotifyTemplateVariableDefinition("order_no", "TEXT", true, null, "")),
            Map.of("buyer_name", "小明", "order_no", "SO-1"));
    var response =
        http.perform(
                post("/platform/ai/templates/render-preview")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(payload)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var result = json.readValue(response, NotifyTemplateRenderResult.class);
    assertThat(result.ok()).isTrue();
    assertThat(result.renderedBody()).isEqualTo("买家 小明 的订单 SO-1 已发货");
    assertThat(result.renderedSubject()).isEqualTo("发货通知");
    assertThat(result.errors()).isEmpty();

    // 🔴 预览**不落库**：一张表都没多。
    assertThat(inTenant(() -> sql.fetch("select id from platform_notify_template").size())).isZero();
    assertThat(inTenant(() -> sql.fetch("select id from platform_notify_template_version").size()))
        .isZero();
  }

  @Test
  void 预览已保存的版本用真实内容() throws Exception {
    var template = createTemplate("ORDER_PAID", "ORDER_STATUS", "zh-CN", "第一版正文", List.of());
    var versionId =
        inTenant(
            () ->
                sql.fetchOne(
                        "select id from platform_notify_template_version where template_id = ?",
                        template.id())
                    .get("id", UUID.class));
    var payload =
        new NotifyTemplateRenderInput(
            template.id().toString(), versionId.toString(), null, null, null, Map.of());
    var response =
        http.perform(
                post("/platform/ai/templates/render-preview")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(payload)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var result = json.readValue(response, NotifyTemplateRenderResult.class);
    assertThat(result.renderedBody()).isEqualTo("第一版正文");
    assertThat(result.versionId()).isEqualTo(versionId);
  }

  @Test
  void 正文里有没声明的变量时模板存不进来() throws Exception {
    var payload =
        new NotifyTemplateInput(
            "ORDER_PAID",
            "ORDER_STATUS",
            "zh-CN",
            "测试",
            "",
            "",
            "你好 {{buyer_name}} 与 {{nickname}}",
            List.of(new NotifyTemplateVariableDefinition("buyer_name", "TEXT", true, null, "")),
            "");
    http.perform(
            post("/platform/ai/templates")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload)))
        .andExpect(status().isBadRequest());
    // 一行都没落库（连主记录也没有）。
    assertThat(inTenant(() -> sql.fetch("select id from platform_notify_template").size())).isZero();
    assertThat(inTenant(() -> sql.fetch("select id from platform_notify_template_version").size()))
        .isZero();
  }

  // ==========================================================================
  // 列表：真分页 + 真筛选
  // ==========================================================================
  @Test
  void 列表真分页真筛选() throws Exception {
    createTemplate("ORDER_PAID", "ORDER_STATUS", "zh-CN", "正文", List.of());
    createTemplate("ORDER_PAID", "ORDER_STATUS", "en-US", "正文", List.of());
    createTemplate("STOCK_LOW", "STOCK", "zh-CN", "正文", List.of());

    var all = listPage(null, null, null, null, "ACTIVE", 0, 20);
    assertThat(all.total()).isEqualTo(3);
    assertThat(all.items()).hasSize(3);

    // 场景筛选。
    assertThat(listPage(null, "ORDER_STATUS", null, null, "ACTIVE", 0, 20).total()).isEqualTo(2);
    // 语言筛选。
    assertThat(listPage(null, null, "en-US", null, "ACTIVE", 0, 20).total()).isEqualTo(1);
    // 关键词筛选（真筛，不是摆设）。
    assertThat(listPage("STOCK", null, null, null, "ACTIVE", 0, 20).total()).isEqualTo(1);
    assertThat(listPage("ORDER", null, null, null, "ACTIVE", 0, 20).total()).isEqualTo(2);
    assertThat(listPage("压根没有这个模板", null, null, null, "ACTIVE", 0, 20).total()).isZero();

    // 真分页：size=1 时总数不变、每页 1 条、页数对得上。
    var firstPage = listPage(null, "STOCK", null, null, "ACTIVE", 0, 1);
    assertThat(firstPage.total()).isEqualTo(1);
    assertThat(firstPage.items()).hasSize(1);
    assertThat(firstPage.totalPages()).isEqualTo(1);

    var paged = listPage(null, null, null, null, "ACTIVE", 1, 2);
    assertThat(paged.total()).isEqualTo(3);
    assertThat(paged.items()).hasSize(1);
    assertThat(paged.page()).isEqualTo(1);
  }

  @Test
  void 启用状态筛选真的分得开() throws Exception {
    var template = createTemplate("ORDER_PAID", "ORDER_STATUS", "zh-CN", "正文", List.of());
    var other = createTemplate("STOCK_LOW", "STOCK", "zh-CN", "正文", List.of());
    var versionId =
        inTenant(
            () ->
                sql.fetchOne(
                        "select id from platform_notify_template_version where template_id = ?",
                        template.id())
                    .get("id", UUID.class));
    enable(template.id(), versionId);

    var enabled = listPage(null, null, null, Boolean.TRUE, "ACTIVE", 0, 20);
    assertThat(enabled.total()).isEqualTo(1);
    assertThat(enabled.items().get(0).templateKey()).isEqualTo("order_paid");

    var disabled = listPage(null, null, null, Boolean.FALSE, "ACTIVE", 0, 20);
    assertThat(disabled.total()).isEqualTo(1);
    assertThat(disabled.items().get(0).templateKey()).isEqualTo("stock_low");
    assertThat(other.templateKey()).isEqualTo("stock_low");
  }

  @Test
  void 删除模板是归档不是物理删() throws Exception {
    var template = createTemplate("ORDER_PAID", "ORDER_STATUS", "zh-CN", "正文", List.of());
    var versionId =
        inTenant(
            () ->
                sql.fetchOne(
                        "select id from platform_notify_template_version where template_id = ?",
                        template.id())
                    .get("id", UUID.class));
    enable(template.id(), versionId);

    http.perform(
            delete("/platform/ai/templates/" + template.id()).header("Authorization", "session-a"))
        .andExpect(status().isOk());

    // 主记录还在（归档了），版本行还在（历史通知解释得清）。
    assertThat(inTenant(() -> sql.fetch("select id from platform_notify_template").size())).isEqualTo(1);
    assertThat(inTenant(() -> sql.fetch("select id from platform_notify_template_version").size()))
        .isEqualTo(1);
    // 默认列表（ACTIVE）里不再出现；归档视图里能看到。
    assertThat(listPage(null, null, null, null, "ACTIVE", 0, 20).total()).isZero();
    assertThat(listPage(null, null, null, null, "ARCHIVED", 0, 20).total()).isEqualTo(1);
    assertThat(listPage(null, null, null, null, "ALL", 0, 20).total()).isEqualTo(1);
    // 归档后那一刻没有生效版本。
    http.perform(
            get("/platform/ai/templates/resolve")
                .header("Authorization", "session-a")
                .param("templateKey", "ORDER_PAID")
                .param("scene", "ORDER_STATUS")
                .param("language", "zh-CN")
                .param("at", OffsetDateTime.now(ZoneOffset.UTC).toString()))
        .andExpect(status().isNotFound());
  }

  // ==========================================================================
  // 其它纪律
  // ==========================================================================
  @Test
  void 请求体不接受租户字段() throws Exception {
    var body = "{\"templateKey\":\"ORDER_PAID\",\"scene\":\"ORDER_STATUS\",\"language\":\"zh-CN\","
        + "\"name\":\"x\",\"body\":\"y\",\"tenantId\":\"" + tenant() + "\"}";
    http.perform(
            post("/platform/ai/templates")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 能力声明把场景语言变量约定与边界都写清了() throws Exception {
    var response =
        http.perform(get("/platform/ai/templates/capabilities").header("Authorization", "session-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var capabilities = json.readValue(response, NotifyTemplateCapabilities.class);
    assertThat(capabilities.capabilities()).isNotEmpty();
    assertThat(capabilities.conventions().scenes()).hasSize(7);
    assertThat(capabilities.conventions().languageRule()).contains("IANA");
    assertThat(capabilities.conventions().renderErrorCodes()).isNotEmpty();
    // 18.5.2.1 尚未交付 ⇒ 必须如实标成不可用并写明归谁。
    assertThat(capabilities.gaps())
        .anyMatch(g -> "18.5.2.1".equals(g.moduleId()) && !g.available());
  }

  @Test
  void 上游文案资产读得到() throws Exception {
    var response =
        http.perform(
                get("/platform/ai/templates/upstream/content-assets")
                    .header("Authorization", "session-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var view = json.readValue(response, NotifyTemplateContentAssetView.class);
    assertThat(view.connected()).isTrue();
    assertThat(view.titles()).hasSize(1);
    assertThat(view.titles().get(0).code()).isEqualTo("TITLE-STD");
  }

  /** 从接口读出来的启用时刻（与库里存的是同一个时刻）。 */
  private OffsetDateTime enabledVersionActivatedAt(UUID templateId, UUID versionId) throws Exception {
    var response =
        http.perform(
                get("/platform/ai/templates/" + templateId + "/versions/" + versionId)
                    .header("Authorization", "session-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(response, NotifyTemplateVersionDetail.class).version().activatedAt();
  }

  // ================================================================ 工具

  /** 列表查询（每个筛选参数都可空 = 不筛）。 */
  private NotifyTemplatePage listPage(
      String keyword,
      String scene,
      String language,
      Boolean enabled,
      String status,
      int page,
      int size)
      throws Exception {
    var request =
        get("/platform/ai/templates")
            .header("Authorization", "session-a")
            .param("page", String.valueOf(page))
            .param("size", String.valueOf(size));
    if (keyword != null) request.param("keyword", keyword);
    if (scene != null) request.param("scene", scene);
    if (language != null) request.param("language", language);
    if (enabled != null) request.param("enabled", String.valueOf(enabled));
    if (status != null) request.param("status", status);
    var response =
        http.perform(request)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(response, NotifyTemplatePage.class);
  }
}
