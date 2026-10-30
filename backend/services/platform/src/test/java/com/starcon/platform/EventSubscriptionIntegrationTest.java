package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.EventSubscriptionModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
 * 18.1.2.1 事件订阅的**功能**集成测试，跑在真实 PostgreSQL 16 上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p>覆盖蓝图点名的六项职责：可订阅事件列表、订阅端点配置、签名密钥、事件过滤、
 * 重试与死信策略、签名试算；外加三条硬约束：
 *
 * <ul>
 *   <li>「破坏性变更要新增版本而非原地修改」落到**物理约束**（触发器）；
 *   <li>签名密钥**明文只出现一次**、库里只有密文；
 *   <li>审计**只追加**（触发器挡 UPDATE / DELETE）。
 * </ul>
 *
 * <p>租户隔离的三条断言在 {@code EventSubscriptionTenancyIntegrationTest} 里（同一批表、同一套夹具）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EventSubscriptionIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

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
            "platform.webhook.read",
            "platform.webhook.create",
            "platform.webhook.update",
            "platform.webhook.revoke");
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
  }

  @Autowired DSLContext sql;
  @Autowired TenantTransactions tenants;
  @Autowired MockMvc http;
  @Autowired EventSubscriptionService service;

  // ================================================================ 夹具

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
          "truncate table platform_webhook_audit_event, platform_webhook_policy,"
              + " platform_webhook_signing_secret, platform_webhook_subscription_event,"
              + " platform_webhook_subscription, platform_webhook_event_type, platform_app"
              + " restart identity cascade");
    }
  }

  /** 插一个应用（订阅必须挂在已注册应用上 —— 真 FK）。 */
  private UUID seedApp(String code) {
    var id = UUID.randomUUID();
    inTenant(
        () -> {
          var now = dbNow();
          sql.execute(
              "insert into platform_app(id,tenant_id,app_code,app_name,app_type,description,status,"
                  + " created_at,updated_at) values(?,?,?,'夹具应用','THIRD_PARTY','','ACTIVE',"
                  + " ?::timestamptz,?::timestamptz)",
              id,
              tenant(),
              code,
              now,
              now);
          return null;
        });
    return id;
  }

  // ================================================================ 时钟守卫

  /**
   * DB 时钟取样。
   *
   * <p>🔴 **同一个钟域**：断言落库时刻时不能拿 JVM 墙钟去比 ——
   * 本仓已有两处同款守卫因为跨时钟域在负载下假红过。
   */
  private OffsetDateTime dbNow() {
    return inTenant(() -> sql.fetchOne("select clock_timestamp()").get(0, OffsetDateTime.class));
  }

  /** 断言落库时刻落在 [before, after] 之间（DB 钟域区间判定）。 */
  private void assertWithinDbClockWindow(
      OffsetDateTime value, OffsetDateTime before, OffsetDateTime after) {
    assertThat(value).as("落库时刻必须真的写进去了").isNotNull();
    assertThat(value.isBefore(before))
        .as("落库时刻 " + value + " 早于插入前的 DB 取样 " + before)
        .isFalse();
    assertThat(value.isAfter(after))
        .as("落库时刻 " + value + " 晚于插入后的 DB 取样 " + after)
        .isFalse();
  }

  // ================================================================ HTTP 助手

  private org.springframework.test.web.servlet.ResultActions perform(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      Object body)
      throws Exception {
    return http.perform(
        request
            .header("Authorization", "session-a")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)));
  }

  private EventSubscriptionEventTypeRow registerEventType(
      String eventType, int version, String sourceService, String sourceArtifact) throws Exception {
    var input =
        new EventSubscriptionEventTypeInput(
            eventType,
            version,
            "ACTIVE",
            "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}",
            sourceService,
            sourceArtifact);
    var response =
        perform(post("/platform/webhooks/event-types"), input)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(response, EventSubscriptionEventTypeRow.class);
  }

  private EventSubscriptionRow createSubscription(UUID appId, String name, String url)
      throws Exception {
    var input = new EventSubscriptionInput(appId, name, url, "ACTIVE", "测试订阅");
    var response =
        perform(post("/platform/webhooks/subscriptions"), input)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(response, EventSubscriptionRow.class);
  }

  // ================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ================================================================

  @Test
  void 本模块的六张表强制rls而不是只启用() {
    for (String table :
        List.of(
            "platform_webhook_event_type",
            "platform_webhook_subscription",
            "platform_webhook_subscription_event",
            "platform_webhook_signing_secret",
            "platform_webhook_policy",
            "platform_webhook_audit_event")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V412 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class))
          .as(table + " 只有 FORCE 才挡得住「运行角色被误授 owner」")
          .isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('platform_app','" + table + "','SELECT')")
                  .get(0, Boolean.class))
          .as("platform_app 必须真的能读 " + table)
          .isTrue();
      var nullable =
          sql.fetchOne(
                  "select is_nullable from information_schema.columns where table_name = ? and column_name = 'tenant_id'",
                  table)
              .get(0, String.class);
      assertThat(nullable).as(table + " 的 tenant_id 必须是 NOT NULL").isEqualTo("NO");
    }
  }

  @Test
  void 本模块不落任何金额也不建任何投递表() {
    var amountColumns =
        sql.fetch(
            "select table_name, column_name from information_schema.columns"
                + " where table_name like 'platform_webhook%'"
                + " and (column_name like '%amount%' or column_name like '%price%' or column_name like '%fee%'"
                + " or column_name like '%balance%' or column_name like '%tax%' or column_name like '%currency%')");
    assertThat(amountColumns).as("事件订阅模块不落金额").isEmpty();

    // 🔴 **投递明细归 18.1.2.2**：本车道不许建任何投递 / 死信表。
    var tables =
        sql.fetch(
                "select table_name from information_schema.tables where table_name like 'platform_webhook%'")
            .map(r -> r.get(0, String.class));
    assertThat(tables)
        .as("本车道只有订阅配置侧的六张表")
        .containsExactlyInAnyOrder(
            "platform_webhook_event_type",
            "platform_webhook_subscription",
            "platform_webhook_subscription_event",
            "platform_webhook_signing_secret",
            "platform_webhook_policy",
            "platform_webhook_audit_event");
  }

  // ================================================================
  // 可订阅事件列表
  // ================================================================

  @Test
  void 可订阅事件列表真查注册表而不是写死的清单() throws Exception {
    // 系统**零预置行**：清单不是一份写死的常量。
    var empty =
        json.readValue(
            http.perform(get("/platform/webhooks/event-types").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            EventSubscriptionEventTypePage.class);
    assertThat(empty.total()).as("系统不预置任何事件类型 —— 清单必须由登记产生").isZero();
    assertThat(empty.items()).isEmpty();

    registerEventType("SkuProfileSaved", 1, "catalog", "catalog/SkuDetailService.java:435");
    registerEventType(
        "SpuLifecycleChanged", 1, "catalog", "catalog/SpuLifecycleService.java:120");

    var page =
        json.readValue(
            http.perform(get("/platform/webhooks/event-types").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            EventSubscriptionEventTypePage.class);
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.items()).extracting(EventSubscriptionEventTypeRow::eventType)
        .containsExactly("SkuProfileSaved", "SpuLifecycleChanged");
    // 每行都带**出处**。
    assertThat(page.items().get(0).sourceService()).isEqualTo("catalog");
    assertThat(page.items().get(0).sourceArtifact()).isEqualTo("catalog/SkuDetailService.java:435");
  }

  @Test
  void 事件列表是真分页真筛选() throws Exception {
    registerEventType("SkuProfileSaved", 1, "catalog", "a.java:1");
    registerEventType("SkuCodeAssigned", 1, "catalog", "b.java:2");
    registerEventType("SaasSubscriptionCreated", 1, "identity", "c.java:3");

    var filtered =
        json.readValue(
            http.perform(
                    get("/platform/webhooks/event-types")
                        .header("Authorization", "session-a")
                        .param("keyword", "Sku"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            EventSubscriptionEventTypePage.class);
    assertThat(filtered.total()).isEqualTo(2);

    var byService =
        json.readValue(
            http.perform(
                    get("/platform/webhooks/event-types")
                        .header("Authorization", "session-a")
                        .param("keyword", "identity"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            EventSubscriptionEventTypePage.class);
    assertThat(byService.total()).isEqualTo(1);

    var paged =
        json.readValue(
            http.perform(
                    get("/platform/webhooks/event-types")
                        .header("Authorization", "session-a")
                        .param("size", "2")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            EventSubscriptionEventTypePage.class);
    assertThat(paged.total()).isEqualTo(3);
    assertThat(paged.items()).hasSize(2);
    assertThat(paged.size()).isEqualTo(2);

    var secondPage =
        json.readValue(
            http.perform(
                    get("/platform/webhooks/event-types")
                        .header("Authorization", "session-a")
                        .param("size", "2")
                        .param("page", "1"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            EventSubscriptionEventTypePage.class);
    assertThat(secondPage.items()).hasSize(1);
  }

  @Test
  void 登记事件定义后档案真实落库并带出处() throws Exception {
    var before = dbNow();
    var row =
        registerEventType(
            "SkuBatchStatusChanged", 1, "catalog", "catalog/SkuBatchService.java:88");
    var after = dbNow();

    assertThat(row.eventType()).isEqualTo("SkuBatchStatusChanged");
    assertThat(row.schemaVersion()).isEqualTo(1);
    assertThat(row.status()).isEqualTo("ACTIVE");

    var stored =
        inTenant(
            () ->
                sql.fetchOne(
                    "select event_type,schema_version,source_service,source_artifact,tenant_id,"
                        + " registered_at from platform_webhook_event_type"));
    assertThat(stored).isNotNull();
    assertThat(stored.get("event_type", String.class)).isEqualTo("SkuBatchStatusChanged");
    assertThat(stored.get("source_artifact", String.class))
        .isEqualTo("catalog/SkuBatchService.java:88");
    assertThat(stored.get("tenant_id", UUID.class)).isEqualTo(tenant());
    // 🔴 时间断言：**写回的值**落在 DB 钟域区间里。
    assertWithinDbClockWindow(stored.get("registered_at", OffsetDateTime.class), before, after);
  }

  @Test
  void 事件载荷schema必须是json对象且不能为空() throws Exception {
    var notObject =
        new EventSubscriptionEventTypeInput("X", 1, "ACTIVE", "[1,2,3]", "catalog", "a.java:1");
    perform(post("/platform/webhooks/event-types"), notObject).andExpect(status().isBadRequest());

    var empty =
        new EventSubscriptionEventTypeInput("X", 1, "ACTIVE", "{}", "catalog", "a.java:1");
    perform(post("/platform/webhooks/event-types"), empty).andExpect(status().isBadRequest());

    var noSource =
        new EventSubscriptionEventTypeInput("X", 1, "ACTIVE", "{\"type\":\"object\"}", "", "a.java:1");
    perform(post("/platform/webhooks/event-types"), noSource).andExpect(status().isBadRequest());
  }

  // ================================================================
  // 版本化契约
  // ================================================================

  @Test
  void 破坏性变更必须新增版本而不是原地修改() throws Exception {
    var row = registerEventType("SkuProfileSaved", 1, "catalog", "catalog/SkuDetailService.java:435");

    // 🔴 **物理约束**：直接改 payload_schema 会被触发器拒掉。
    assertThatThrownBy(
            () ->
                inTenant(
                    () -> {
                      sql.execute(
                          "update platform_webhook_event_type set payload_schema = ?::jsonb"
                              + " where id = ?",
                          "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\"}}}",
                          row.id());
                      return null;
                    }))
        .as("payload_schema 登记后不可原地修改")
        .hasStackTraceContaining("事件 schema 是版本化契约");

    // 改 event_type / schema_version 同理。
    assertThatThrownBy(
            () ->
                inTenant(
                    () -> {
                      sql.execute(
                          "update platform_webhook_event_type set schema_version = 2 where id = ?",
                          row.id());
                      return null;
                    }))
        .as("schema_version 登记后不可原地修改")
        .hasStackTraceContaining("事件 schema 是版本化契约");

    // 服务层也不给「改结构」的入口：重复登记同一版本直接 409。
    var again =
        new EventSubscriptionEventTypeInput(
            "SkuProfileSaved",
            1,
            "ACTIVE",
            "{\"type\":\"object\"}",
            "catalog",
            "catalog/SkuDetailService.java:435");
    perform(post("/platform/webhooks/event-types"), again).andExpect(status().isConflict());

    // 但**新增版本**是允许的 —— 这正是蓝图说的做法。
    var v2 = registerEventType("SkuProfileSaved", 2, "catalog", "catalog/SkuDetailService.java:435");
    assertThat(v2.schemaVersion()).isEqualTo(2);

    var page =
        json.readValue(
            http.perform(get("/platform/webhooks/event-types").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            EventSubscriptionEventTypePage.class);
    assertThat(page.total()).as("同一事件类型可以并存多个 schema 版本").isEqualTo(2);
  }

  @Test
  void 事件定义可以改状态但只能改状态() throws Exception {
    var row = registerEventType("SkuCodeAssigned", 1, "catalog", "a.java:1");
    var updated =
        json.readValue(
            perform(
                    post("/platform/webhooks/event-types/" + row.id() + "/status"),
                    new EventSubscriptionEventTypeStatusInput("DEPRECATED"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            EventSubscriptionEventTypeRow.class);
    assertThat(updated.status()).isEqualTo("DEPRECATED");
    assertThat(updated.statusLabel()).isEqualTo("已废弃");

    var stored =
        inTenant(
            () -> sql.fetchOne("select status from platform_webhook_event_type where id = ?", row.id()));
    assertThat(stored.get("status", String.class)).isEqualTo("DEPRECATED");
  }

  // ================================================================
  // 订阅端点
  // ================================================================

  @Test
  void 订阅端点配置真实落库() throws Exception {
    var appId = seedApp("erp-gateway");
    var before = dbNow();
    var row = createSubscription(appId, "订单事件", "https://partner.example.com/hooks/starcon");
    var after = dbNow();

    assertThat(row.name()).isEqualTo("订单事件");
    assertThat(row.targetUrl()).isEqualTo("https://partner.example.com/hooks/starcon");
    assertThat(row.status()).isEqualTo("ACTIVE");
    assertThat(row.appId()).isEqualTo(appId);

    var stored =
        inTenant(
            () ->
                sql.fetchOne(
                    "select name,target_url,status,app_id,tenant_id,created_at"
                        + " from platform_webhook_subscription"));
    assertThat(stored.get("target_url", String.class))
        .isEqualTo("https://partner.example.com/hooks/starcon");
    assertThat(stored.get("tenant_id", UUID.class)).isEqualTo(tenant());
    assertWithinDbClockWindow(stored.get("created_at", OffsetDateTime.class), before, after);
  }

  @Test
  void 订阅必须挂在已注册应用上且端点必须是http() throws Exception {
    var missing =
        new EventSubscriptionInput(UUID.randomUUID(), "x", "https://a.example.com", "ACTIVE", "");
    perform(post("/platform/webhooks/subscriptions"), missing)
        .andExpect(status().isBadRequest());

    var appId = seedApp("erp-gateway");
    var badScheme =
        new EventSubscriptionInput(appId, "x", "ftp://a.example.com", "ACTIVE", "");
    perform(post("/platform/webhooks/subscriptions"), badScheme).andExpect(status().isBadRequest());

    var noApp = new EventSubscriptionInput(null, "x", "https://a.example.com", "ACTIVE", "");
    perform(post("/platform/webhooks/subscriptions"), noApp).andExpect(status().isBadRequest());
  }

  @Test
  void 订阅可以改可以吊销但吊销不删() throws Exception {
    var appId = seedApp("erp-gateway");
    var row = createSubscription(appId, "订单事件", "https://partner.example.com/hooks/starcon");

    var updated =
        json.readValue(
            perform(
                    post("/platform/webhooks/subscriptions/" + row.id()),
                    new EventSubscriptionInput(null, "改过的名字", "https://partner.example.com/v2", "PAUSED", ""))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            EventSubscriptionRow.class);
    assertThat(updated.name()).isEqualTo("改过的名字");
    assertThat(updated.status()).isEqualTo("PAUSED");
    assertThat(updated.targetUrl()).isEqualTo("https://partner.example.com/v2");

    var revoked =
        json.readValue(
            perform(
                    post("/platform/webhooks/subscriptions/" + row.id() + "/revoke?reason=对接方下线"),
                    Map.of())
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            EventSubscriptionRow.class);
    assertThat(revoked.status()).isEqualTo("DISABLED");

    // 吊销**不删**：行还在（审计痕迹要留着）。
    assertThat(inTenant(() -> sql.fetch("select id from platform_webhook_subscription")).size())
        .isEqualTo(1);
  }

  // ================================================================
  // 事件过滤
  // ================================================================

  @Test
  void 事件过滤只能订阅已登记的schema() throws Exception {
    var appId = seedApp("erp-gateway");
    var subscription = createSubscription(appId, "订单事件", "https://partner.example.com/hooks");

    var unregistered =
        new EventSubscriptionFilterInput("NeverRegistered", 1, "{}", true);
    perform(post("/platform/webhooks/subscriptions/" + subscription.id() + "/events"), unregistered)
        .andExpect(status().isBadRequest());

    registerEventType("SkuProfileSaved", 1, "catalog", "catalog/SkuDetailService.java:435");
    var registered =
        new EventSubscriptionFilterInput(
            "SkuProfileSaved", 1, "{\"properties\":{\"tenantId\":{\"const\":\"t1\"}}}", true);
    var filter =
        json.readValue(
            perform(
                    post("/platform/webhooks/subscriptions/" + subscription.id() + "/events"),
                    registered)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            EventSubscriptionFilterRow.class);
    assertThat(filter.eventType()).isEqualTo("SkuProfileSaved");
    assertThat(filter.schemaVersion()).isEqualTo(1);
    assertThat(filter.filter()).contains("tenantId");

    // 库里真的有一行。
    assertThat(
            inTenant(
                    () ->
                        sql.fetch(
                            "select event_type,schema_version,filter::text as filter"
                                + " from platform_webhook_subscription_event"))
                .size())
        .isEqualTo(1);

    // 删掉它。
    http.perform(
            delete("/platform/webhooks/subscriptions/" + subscription.id() + "/events/" + filter.id())
                .header("Authorization", "session-a"))
        .andExpect(status().isOk());
    assertThat(
            inTenant(
                    () ->
                        sql.fetch("select id from platform_webhook_subscription_event"))
                .size())
        .isZero();
  }

  @Test
  void 不指定版本时订阅注册表里当前可用版本() throws Exception {
    var appId = seedApp("erp-gateway");
    var subscription = createSubscription(appId, "订单事件", "https://partner.example.com/hooks");
    registerEventType("SkuProfileSaved", 1, "catalog", "a.java:1");
    registerEventType("SkuProfileSaved", 2, "catalog", "a.java:2");

    var filter =
        json.readValue(
            perform(
                    post("/platform/webhooks/subscriptions/" + subscription.id() + "/events"),
                    new EventSubscriptionFilterInput("SkuProfileSaved", null, "{}", true))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            EventSubscriptionFilterRow.class);
    assertThat(filter.schemaVersion()).as("默认取当前可用（最新 ACTIVE）版本").isEqualTo(2);
  }

  // ================================================================
  // 签名密钥
  // ================================================================

  @Test
  void 签名密钥明文只在生成那一次出现() throws Exception {
    var appId = seedApp("erp-gateway");
    var subscription = createSubscription(appId, "订单事件", "https://partner.example.com/hooks");

    var issued =
        json.readValue(
            perform(post("/platform/webhooks/subscriptions/" + subscription.id() + "/signing-secrets"), Map.of())
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            EventSubscriptionSecretIssued.class);
    assertThat(issued.plaintext()).as("明文在这一刻出现").isNotBlank();
    assertThat(issued.secret().version()).isEqualTo(1);
    assertThat(issued.secret().status()).isEqualTo("ACTIVE");
    assertThat(issued.warning()).contains("唯一一次");

    // 库里只有**密文**，且密文解出来确实是那把明文。
    var stored =
        inTenant(
            () ->
                sql.fetchOne(
                    "select secret_ciphertext, secret_hint from platform_webhook_signing_secret"));
    var ciphertext = stored.get("secret_ciphertext", String.class);
    assertThat(ciphertext).isNotEqualTo(issued.plaintext());
    assertThat(ciphertext).as("密文是 Base64(nonce|ciphertext||tag)").isNotBlank();
    var cipher = new WebhookSigningSecretCipher("test-key");
    assertThat(cipher.open(ciphertext)).as("密文必须能解回同一把明文").isEqualTo(issued.plaintext());
    assertThat(stored.get("secret_hint", String.class)).isEqualTo(issued.plaintext().substring(issued.plaintext().length() - 4));

    // 读接口**拿不到明文**。
    var list =
        json.readValue(
            http.perform(
                    get("/platform/webhooks/subscriptions/" + subscription.id() + "/signing-secrets")
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            new com.fasterxml.jackson.core.type.TypeReference<List<EventSubscriptionSecretRow>>() {});
    assertThat(list).hasSize(1);
    assertThat(json.writeValueAsString(list)).doesNotContain(issued.plaintext());
    assertThat(list.get(0).secretHint()).isNotBlank();

    // 已有生效密钥时**不许再生成**（那是轮换的语义）。
    perform(post("/platform/webhooks/subscriptions/" + subscription.id() + "/signing-secrets"), Map.of())
        .andExpect(status().isConflict());
  }

  @Test
  void 轮换签名密钥让旧密钥失效且旧版留痕() throws Exception {
    var appId = seedApp("erp-gateway");
    var subscription = createSubscription(appId, "订单事件", "https://partner.example.com/hooks");

    var first =
        json.readValue(
            perform(post("/platform/webhooks/subscriptions/" + subscription.id() + "/signing-secrets"), Map.of())
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            EventSubscriptionSecretIssued.class);

    var rotated =
        json.readValue(
            perform(
                    post("/platform/webhooks/subscriptions/" + subscription.id() + "/signing-secrets/rotate"),
                    Map.of())
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            EventSubscriptionSecretIssued.class);
    assertThat(rotated.secret().version()).isEqualTo(2);
    assertThat(rotated.plaintext()).isNotEqualTo(first.plaintext());

    var statuses =
        inTenant(
            () ->
                sql.fetch("select version,status from platform_webhook_signing_secret order by version")
                    .map(r -> r.get("status", String.class)));
    assertThat(statuses).containsExactly("ROTATED", "ACTIVE");

    // 同一时刻只允许一把生效的密钥（部分唯一索引）。
    var now = dbNow();
    assertThatThrownBy(
            () ->
                inTenant(
                    () -> {
                      sql.execute(
                          "insert into platform_webhook_signing_secret(id,tenant_id,subscription_id,"
                              + " version,status,secret_ciphertext,secret_hint,created_at)"
                              + " values(?,?,?,3,'ACTIVE','x','zzzz',?::timestamptz)",
                          UUID.randomUUID(),
                          tenant(),
                          subscription.id(),
                          now);
                      return null;
                    }))
        .as("一个订阅同时只允许一把生效密钥")
        .hasStackTraceContaining("platform_webhook_signing_secret_one_active");
  }

  @Test
  void 签名试算算出来的签名对方能自己复算() throws Exception {
    var appId = seedApp("erp-gateway");
    var subscription = createSubscription(appId, "订单事件", "https://partner.example.com/hooks");
    var issued =
        json.readValue(
            perform(post("/platform/webhooks/subscriptions/" + subscription.id() + "/signing-secrets"), Map.of())
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            EventSubscriptionSecretIssued.class);

    var sample = "{\"eventType\":\"SkuProfileSaved\",\"data\":{\"id\":\"sku-1\"}}";
    var preview =
        json.readValue(
            perform(
                    post("/platform/webhooks/subscriptions/" + subscription.id() + "/signature-preview"),
                    new EventSubscriptionSignaturePreviewInput(sample, "1700000000"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            EventSubscriptionSignaturePreview.class);
    assertThat(preview.signatureAlgorithm()).isEqualTo("HMAC_SHA256");
    assertThat(preview.signatureHeader()).isEqualTo("X-Starcon-Signature");
    assertThat(preview.timestamp()).isEqualTo("1700000000");
    assertThat(preview.headerValue()).isEqualTo("t=1700000000,v1=" + preview.signature());

    // 🔴 **对方能自己复算**：用同一把密钥对「时间戳 + 点 + 原始字节」算 HMAC-SHA256。
    var expected =
        hmacHex(issued.plaintext(), ("1700000000." + sample).getBytes(StandardCharsets.UTF_8));
    assertThat(preview.signature()).as("签名必须是真算出来的").isEqualTo(expected);
    assertThat(preview.signedPayload()).isEqualTo("1700000000." + sample);

    // 🔴 试算**不投递、不写任何投递记录**：订阅配置侧的表里没有多出任何一行。
    assertThat(
            inTenant(
                    () ->
                        sql.fetch(
                            "select id from platform_webhook_audit_event where action = 'DELIVERED'"))
                .size())
        .isZero();
  }

  private static String hmacHex(String secret, byte[] data) throws Exception {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return java.util.HexFormat.of().formatHex(mac.doFinal(data));
  }

  @Test
  void 投递执行未接入时如实答409且不写任何投递记录() throws Exception {
    var appId = seedApp("erp-gateway");
    var subscription = createSubscription(appId, "订单事件", "https://partner.example.com/hooks");

    // 🔴 端点如实答 **409**（投递执行器未接入）。
    perform(
            post("/platform/webhooks/subscriptions/" + subscription.id() + "/deliver"),
            new EventSubscriptionDeliveryInput("SkuProfileSaved", 1, "{\"a\":1}"))
        .andExpect(status().isConflict());

    // ⚠️ 原因（归 18.1.2.2）走 HTTP 状态短语，本仓的服务层错误约定是
    //   「ResponseStatusException ⇒ 空 body，原因在状态里」（与 18.2.1.1 直连推送同一约定），
    //   所以在**服务层**断言原因文本，而不是在 HTTP body 里找。
    var thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                inTenant(
                    () ->
                        service.deliver(
                            subscription.id(),
                            new EventSubscriptionDeliveryInput("SkuProfileSaved", 1, "{\"a\":1}"),
                            null)));
    assertThat(thrown).as("投递未接入必须拒绝").isNotNull();
    assertThat(thrown.getMessage()).contains("18.1.2.2").contains("未接入");

    // 一次投递都不发生 ⇒ 没有任何投递痕迹。
    var tables =
        sql.fetch(
                "select table_name from information_schema.tables where table_name like '%delivery%'"
                    + " or table_name like '%dead_letter%' or table_name like '%webhook_delivery%'")
            .map(r -> r.get(0, String.class));
    assertThat(tables).as("本车道不建任何投递 / 死信表（归 18.1.2.2）").isEmpty();
  }

  // ================================================================
  // 策略
  // ================================================================

  @Test
  void 重试与死信策略配置真实落库() throws Exception {
    var policy =
        json.readValue(
            perform(
                    post("/platform/webhooks/policies"),
                    new EventSubscriptionPolicyInput(
                        null, 7, 45, new java.math.BigDecimal("2.50"), 900, 20, 14))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            EventSubscriptionPolicyRow.class);
    assertThat(policy.maxAttempts()).isEqualTo(7);
    assertThat(policy.backoffMultiplier()).isEqualByComparingTo("2.50");
    assertThat(policy.deadLetterRetentionDays()).isEqualTo(14);
    assertThat(policy.signatureAlgorithm()).isEqualTo("HMAC_SHA256");
    assertThat(policy.signatureHeader()).isEqualTo("X-Starcon-Signature");

    var stored =
        inTenant(
            () ->
                sql.fetchOne(
                    "select max_attempts,backoff_multiplier,dead_letter_retention_days"
                        + " from platform_webhook_policy where subscription_id is null"));
    assertThat(stored.get("max_attempts", Integer.class)).isEqualTo(7);
    assertThat(stored.get("backoff_multiplier", java.math.BigDecimal.class))
        .isEqualByComparingTo("2.50");
    assertThat(stored.get("dead_letter_retention_days", Integer.class)).isEqualTo(14);

    // 参数越界要在服务层就挡下来。
    var bad =
        new EventSubscriptionPolicyInput(null, 999, 45, new java.math.BigDecimal("2.50"), 900, 20, 14);
    perform(post("/platform/webhooks/policies"), bad).andExpect(status().isBadRequest());
  }

  @Test
  void 订阅专属策略优先于租户默认策略() throws Exception {
    var appId = seedApp("erp-gateway");
    var subscription = createSubscription(appId, "订单事件", "https://partner.example.com/hooks");

    perform(
            post("/platform/webhooks/policies"),
            new EventSubscriptionPolicyInput(
                null, 3, 10, new java.math.BigDecimal("2.00"), 600, 5, 7))
        .andExpect(status().isOk());
    perform(
            post("/platform/webhooks/policies"),
            new EventSubscriptionPolicyInput(
                subscription.id(), 9, 60, new java.math.BigDecimal("3.00"), 1800, 30, 90))
        .andExpect(status().isOk());

    var detail =
        json.readValue(
            http.perform(
                    get("/platform/webhooks/subscriptions/" + subscription.id())
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            EventSubscriptionDetail.class);
    assertThat(detail.effectivePolicy().maxAttempts()).as("订阅级专属策略优先").isEqualTo(9);
    assertThat(detail.effectivePolicy().deadLetterRetentionDays()).isEqualTo(90);
  }

  // ================================================================
  // 审计
  // ================================================================

  @Test
  void 订阅配置变更留下只追加的审计() throws Exception {
    var appId = seedApp("erp-gateway");
    var subscription = createSubscription(appId, "订单事件", "https://partner.example.com/hooks");
    perform(post("/platform/webhooks/subscriptions/" + subscription.id() + "/signing-secrets"), Map.of())
        .andExpect(status().isOk());

    var page =
        json.readValue(
            http.perform(get("/platform/webhooks/audit-events").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            EventSubscriptionAuditEventPage.class);
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.items()).extracting(EventSubscriptionAuditEventRow::action)
        .containsExactlyInAnyOrder("SUBSCRIPTION_CREATED", "SIGNING_SECRET_GENERATED");

    // 🔴 审计**只追加**：UPDATE / DELETE 都被触发器挡掉。
    assertThatThrownBy(
            () ->
                inTenant(
                    () -> {
                      sql.execute("update platform_webhook_audit_event set action = 'TAMPERED'");
                      return null;
                    }))
        .as("审计行不许改")
        .hasStackTraceContaining("追加式");
    assertThatThrownBy(
            () ->
                inTenant(
                    () -> {
                      sql.execute("delete from platform_webhook_audit_event");
                      return null;
                    }))
        .as("审计行不许删")
        .hasStackTraceContaining("追加式");
  }

  // ================================================================
  // 能力声明
  // ================================================================

  @Test
  void 能力声明如实回答未交付的部分() throws Exception {
    var capabilities =
        json.readValue(
            http.perform(get("/platform/webhooks/capabilities").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            EventSubscriptionCapabilities.class);

    var delivery =
        capabilities.capabilities().stream()
            .filter(c -> c.name().contains("事件实际投递"))
            .findFirst()
            .orElseThrow();
    assertThat(delivery.available()).as("投递未接入必须如实答 false").isFalse();
    assertThat(delivery.ownerModule()).isEqualTo("18.1.2.2");
    assertThat(delivery.basis()).isNotBlank();

    var eventList =
        capabilities.capabilities().stream()
            .filter(c -> c.name().contains("可订阅事件列表"))
            .findFirst()
            .orElseThrow();
    assertThat(eventList.available()).isTrue();
  }

  @Test
  void 概览是真查库() throws Exception {
    var appId = seedApp("erp-gateway");
    registerEventType("SkuProfileSaved", 1, "catalog", "a.java:1");
    var subscription = createSubscription(appId, "订单事件", "https://partner.example.com/hooks");
    perform(post("/platform/webhooks/subscriptions/" + subscription.id() + "/signing-secrets"), Map.of())
        .andExpect(status().isOk());

    var overview =
        json.readValue(
            http.perform(get("/platform/webhooks/overview").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            EventSubscriptionOverview.class);
    assertThat(overview.eventTypeCount()).isEqualTo(1);
    assertThat(overview.activeEventTypeCount()).isEqualTo(1);
    assertThat(overview.subscriptionCount()).isEqualTo(1);
    assertThat(overview.activeSubscriptionCount()).isEqualTo(1);
    assertThat(overview.signingSecretCount()).isEqualTo(1);
    assertThat(overview.activeSigningSecretCount()).isEqualTo(1);
  }
}
