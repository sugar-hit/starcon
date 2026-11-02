package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.NotifyDeliveryModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
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
 * 18.5.2.1「投递记录」的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <ol>
 *   <li>两租户互不可见；</li>
 *   <li>伪造租户上下文无效 —— 并且<b>要断言行确实落在自称的租户名下</b>，
 *       不是只断言 A 看不见（只断言「看不见」的话，一句「写入被全表拒掉」也能骗过它）；</li>
 *   <li>连接池归还后不串租户。</li>
 * </ol>
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p>🔴 <b>刻意跑在本模块自己的四张表与本模块的端点上</b>：
 * {@code platform_notify_delivery_record} / {@code platform_notify_delivery_record_attempt} /
 * {@code platform_notify_unsubscribe} / {@code platform_notify_unsubscribe_block}。
 *
 * <p>🔴 本模块的隔离面比别的模块**宽一层**：退订表与拦截表记着「谁明确表示不要再联系」——
 * 跨租户可见等于把甲租户的客户退订名单交给乙租户（合规事故，不只是越权）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotifyDeliveryTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 本模块的四张表。 */
  static final List<String> TABLES =
      List.of(
          "platform_notify_delivery_record",
          "platform_notify_delivery_record_attempt",
          "platform_notify_unsubscribe",
          "platform_notify_unsubscribe_block");

  static final List<String> PERMISSIONS =
      List.of(
          "platform.notify_delivery.read",
          "platform.notify_delivery.create",
          "platform.notify_delivery.retry",
          "platform.notify_delivery.unsubscribe");

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-platform-notify-delivery-" + tenant + "-" + ordinal).getBytes());
  }

  static AccessService.Access actor(UUID uid, UUID tenant, List<String> permissions) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
        permissions,
        List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions)));
  }

  static Map<String, Object> map(Object... keyValues) {
    var out = new LinkedHashMap<String, Object>();
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      out.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
    }
    return out;
  }

  static void respond(HttpExchange e, int status, Object body) throws IOException {
    byte[] bytes = json.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(status, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role platform_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    actors.put("session-a", actor(UUID.randomUUID(), a, PERMISSIONS));
    actors.put("session-b", actor(UUID.randomUUID(), b, PERMISSIONS));
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
            respond(e, 200, access);
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
    // 连接池压到 1：只有复用同一条连接，才能真正证明归还后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stopContainers() {
    if (identity != null) identity.stop(0);
    if (pg.isRunning()) pg.stop();
  }

  // ================================================================ 夹具

  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_notify_unsubscribe_block, platform_notify_delivery_record_attempt,"
              + " platform_notify_delivery_record, platform_notify_unsubscribe,"
              + " platform_notify_template_activation, platform_notify_template_version,"
              + " platform_notify_template restart identity cascade");
    }
    seedTenant(a, "tenant-a");
    seedTenant(b, "tenant-b");
  }

  /**
   * 在可信租户事务里造齐这个租户的四张表的参照行。
   *
   * <p>🔴 退订表与拦截表里写着「谁明确表示不要再联系」——
   * 串租户意味着「A 的客户退订名单出现在 B 的界面上」，这是本模块最严重的一类越界。
   */
  void seedTenant(UUID tenant, String marker) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into platform_notify_template(id,tenant_id,template_key,scene,language,name,"
                  + " created_at,updated_at) values(?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
              idFor(tenant, 0),
              tenant,
              "order-paid-" + marker,
              "ORDER_STATUS",
              "zh-CN",
              "模板-" + marker,
              now,
              now);
          sql.execute(
              "insert into platform_notify_delivery_record(id,tenant_id,template_id,template_key,"
                  + " scope_type,scope_ref,channel,status,recipient_count,blocked_count,targeted_count,"
                  + " delivered_count,failed_count,attempt_count,failure_reason,delivery_rate,"
                  + " created_at,updated_at)"
                  + " values(?,?,?,?,'SUBSCRIBERS',?,'EMAIL','PARTIAL',2,1,1,1,0,1,'退订拦截',"
                  + " 1.0000,?::timestamptz,?::timestamptz)",
              idFor(tenant, 1),
              tenant,
              idFor(tenant, 0),
              "order-paid-" + marker,
              "seg-" + marker,
              now,
              now);
          sql.execute(
              "insert into platform_notify_delivery_record_attempt(id,tenant_id,delivery_id,attempt_no,"
                  + " recipient_ref,channel,status_code,outcome,attempt_source,duration_ms,failure_reason,"
                  + " occurred_at)"
                  + " values(?,?,?,1,?,'EMAIL',200,'DELIVERED','INITIAL',10,'',?::timestamptz)",
              idFor(tenant, 2),
              tenant,
              idFor(tenant, 1),
              "ok-" + marker + "@x.com",
              now);
          sql.execute(
              "insert into platform_notify_unsubscribe(id,tenant_id,recipient_ref,channel,scope_type,"
                  + " scope_key,reason,source,unsubscribed_at,created_at)"
                  + " values(?,?,?,'ALL','GLOBAL','*','不想再收','USER_REQUEST',?::timestamptz,"
                  + " ?::timestamptz)",
              idFor(tenant, 3),
              tenant,
              "blocked-" + marker + "@x.com",
              now,
              now);
          sql.execute(
              "insert into platform_notify_unsubscribe_block(id,tenant_id,delivery_id,unsubscribe_id,"
                  + " recipient_ref,channel,unsubscribe_scope_type,unsubscribe_scope_key,blocked_at,"
                  + " created_at)"
                  + " values(?,?,?,?,?,'EMAIL','GLOBAL','*',?::timestamptz,?::timestamptz)",
              idFor(tenant, 4),
              tenant,
              idFor(tenant, 1),
              idFor(tenant, 3),
              "blocked-" + marker + "@x.com",
              now,
              now);
          return null;
        });
  }

  /** 以指定租户身份读投递记录里的收件人（串租户意味着它也会串）。 */
  List<String> recipientsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select recipient_ref from platform_notify_delivery_record_attempt order by recipient_ref")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读退订名单。 */
  List<String> unsubscribedOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select recipient_ref from platform_notify_unsubscribe order by recipient_ref")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读拦截留痕。 */
  List<String> blockedOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select recipient_ref from platform_notify_unsubscribe_block order by recipient_ref")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select id from platform_notify_delivery_record").size();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 本模块的四张表强制rls而不是只启用() {
    for (String table : TABLES) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V439 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class))
          .as(table + " 只有 FORCE 才挡得住「运行角色被误授 owner」")
          .isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('platform_app','" + table + "','SELECT')")
                  .get(0, Boolean.class))
          .as("platform_app 必须真的能读 " + table)
          .isTrue();
      assertThat(
              sql.fetchOne(
                      "select has_table_privilege('platform_app','" + table + "','DELETE')")
                  .get(0, Boolean.class))
          .as(table + " 不该给 DELETE：投递明细与退订登记是「发生过」的证据")
          .isFalse();
      var nullable =
          sql.fetchOne(
                  "select is_nullable from information_schema.columns where table_name = ? and column_name = 'tenant_id'",
                  table)
              .get(0, String.class);
      assertThat(nullable).as(table + " 的 tenant_id 必须是 NOT NULL").isEqualTo("NO");
    }
  }

  // ==========================================================================
  // 断言 1 —— 两租户互不可见
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    assertThat(recipientsOf(a)).containsExactly("ok-tenant-a@x.com");
    assertThat(recipientsOf(b)).containsExactly("ok-tenant-b@x.com");
    assertThat(unsubscribedOf(a)).containsExactly("blocked-tenant-a@x.com");
    assertThat(unsubscribedOf(b)).containsExactly("blocked-tenant-b@x.com");
    assertThat(blockedOf(a)).containsExactly("blocked-tenant-a@x.com");
    assertThat(blockedOf(b)).containsExactly("blocked-tenant-b@x.com");

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    for (var ordinal : List.of(1, 2, 3, 4)) {
      var table =
          switch (ordinal) {
            case 1 -> "platform_notify_delivery_record";
            case 2 -> "platform_notify_delivery_record_attempt";
            case 3 -> "platform_notify_unsubscribe";
            default -> "platform_notify_unsubscribe_block";
          };
      var cross =
          tenants.run(
              a, () -> sql.fetch("select id from " + table + " where id = ?", idFor(b, ordinal)).size());
      assertThat(cross).as("按主键直查对方的 " + table + " 也必须被挡").isZero();
    }

    // 没有租户上下文时一行都看不见。
    assertThat(visibleWithoutTenant()).as("事务外必须一行都看不见").isZero();
  }

  @Test
  void 断言1_端点只看得到本租户的数据() throws Exception {
    var aList =
        json.readValue(
            http.perform(
                    get("/platform/notifications/deliveries").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            NotifyDeliveryRow[].class);
    assertThat(aList).hasSize(1);
    assertThat(aList[0].templateKey()).isEqualTo("order-paid-tenant-a");

    var bList =
        json.readValue(
            http.perform(
                    get("/platform/notifications/deliveries").header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            NotifyDeliveryRow[].class);
    assertThat(bList).hasSize(1);
    assertThat(bList[0].templateKey()).isEqualTo("order-paid-tenant-b");

    // 🔴 A 直接按 B 的投递记录 id 取详情 ⇒ 取不到（B 的字段一个字都不许回给 A）。
    var crossTenant =
        http.perform(
                get("/platform/notifications/deliveries/" + idFor(b, 1))
                    .header("Authorization", "session-a"))
            .andReturn()
            .getResponse();
    assertThat(crossTenant.getStatus()).isBetween(400, 499);
    assertThat(crossTenant.getContentAsString()).doesNotContain("tenant-b");

    // 🔴 退订名单与拦截名单同理。
    var aUnsubs =
        json.readValue(
            http.perform(
                    get("/platform/notifications/unsubscribes").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            NotifyUnsubscribeRow[].class);
    assertThat(aUnsubs).hasSize(1);
    assertThat(aUnsubs[0].recipientRef()).isEqualTo("blocked-tenant-a@x.com");

    var aBlocks =
        json.readValue(
            http.perform(
                    get("/platform/notifications/unsubscribes/blocks")
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            NotifyBlockRow[].class);
    assertThat(aBlocks).hasSize(1);
    assertThat(aBlocks[0].recipientRef()).isEqualTo("blocked-tenant-a@x.com");
  }

  // ==========================================================================
  // 断言 2 —— 伪造租户上下文无效
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() {
    // 事务外没有任何租户上下文：伪造一个 tenant_id 往里写，策略直接拒绝。
    UUID forged = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    assertThatThrownBy(
            () ->
                sql.execute(
                    "insert into platform_notify_delivery_record(id,tenant_id,template_id,template_key,"
                        + " scope_type,scope_ref,channel,status,recipient_count,blocked_count,"
                        + " targeted_count,delivered_count,failed_count,attempt_count,failure_reason,"
                        + " delivery_rate,created_at,updated_at)"
                        + " values(?,?,?,'forged','SINGLE','x','EMAIL','PENDING',0,0,0,0,0,0,'',0,"
                        + " ?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    idFor(a, 0),
                    now,
                    now))
        .as("没有可信租户上下文时，写入必须被挡住 —— 租户不是客户端自称的")
        // ⚠️ 用整条异常链而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因在 cause 里。
        .satisfies(
            e -> {
              var chain = new StringBuilder();
              for (var cause = e; cause != null; cause = cause.getCause()) {
                chain.append(cause.getMessage()).append('\n');
              }
              assertThat(chain.toString())
                  .as("必须是「缺租户上下文」或「RLS 拒绝」其中之一")
                  .containsAnyOf("app.current_tenant", "row-level security");
            });

    // 伪造的行一条都没落库。
    assertThat(sql.fetch("select id from platform_notify_delivery_record where template_key = 'forged'").size())
        .isZero();
  }

  @Test
  void 断言2_伪造的租户头改不动会话租户() throws Exception {
    // 伪造的 X-Tenant-Id 头 + 真实会话 ⇒ 行必须落在**会话租户**名下。
    var response =
        http.perform(
                post("/platform/notifications/unsubscribes")
                    .header("Authorization", "session-a")
                    .header("X-Tenant-Id", b.toString())
                    .header("X-Internal-Key", "forged")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            map(
                                "recipientRef", "forged-header@x.com",
                                "channel", "EMAIL",
                                "scopeType", "GLOBAL",
                                "reason", "伪造头写入的退订",
                                "source", "USER_REQUEST"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse();

    // 行确实存在（不是被全表拒掉）。
    var rows =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select recipient_ref, tenant_id from platform_notify_unsubscribe"
                        + " order by recipient_ref"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(2);
    var forged =
        rows.stream()
            .filter(r -> "forged-header@x.com".equals(r.get("recipient_ref", String.class)))
            .findFirst()
            .orElse(null);
    assertThat(forged).as("新建的那一行必须存在").isNotNull();
    // ...且它落在**会话租户** a 名下，**不是**调用方自称的 b。
    assertThat(forged.get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);

    // 租户 b 看不到它，b 的数据仍然是 b 的自己的。
    assertThat(unsubscribedOf(b)).containsExactly("blocked-tenant-b@x.com");
    assertThat(unsubscribedOf(a)).containsExactly("blocked-tenant-a@x.com", "forged-header@x.com");
    assertThat(response.getStatus()).isEqualTo(201);
  }

  @Test
  void 断言2_请求体不接受归属字段() throws Exception {
    var body =
        """
        {"recipientRef":"x@x.com","channel":"EMAIL","scopeType":"GLOBAL",
         "tenantId":"22222222-2222-2222-2222-222222222222"}
        """;
    http.perform(
            post("/platform/notifications/unsubscribes")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 交替使用同一批连接（A → B → A → B …），每次都必须只看见自己租户的行。
    for (int round = 0; round < 5; round++) {
      assertThat(recipientsOf(a)).as("第 " + round + " 轮：A 只能看见 A 的尝试").containsExactly("ok-tenant-a@x.com");
      assertThat(recipientsOf(b)).as("第 " + round + " 轮：B 只能看见 B 的尝试").containsExactly("ok-tenant-b@x.com");
      assertThat(unsubscribedOf(a)).as("第 " + round + " 轮：A 只能看见 A 的退订").containsExactly("blocked-tenant-a@x.com");
      assertThat(unsubscribedOf(b)).as("第 " + round + " 轮：B 只能看见 B 的退订").containsExactly("blocked-tenant-b@x.com");
      assertThat(blockedOf(a)).as("第 " + round + " 轮：A 只能看见 A 的拦截").containsExactly("blocked-tenant-a@x.com");
      assertThat(blockedOf(b)).as("第 " + round + " 轮：B 只能看见 B 的拦截").containsExactly("blocked-tenant-b@x.com");
    }
  }

  @Test
  void 断言3_连续切换租户后统计也不串() {
    for (int round = 0; round < 4; round++) {
      var statsA =
          tenants.run(
              a,
              () ->
                  sql.fetchOne(
                          """
                          select count(*) as deliveries,
                                 coalesce(sum(blocked_count),0) as blocked
                            from platform_notify_delivery_record
                          """)
                      .get("blocked", BigDecimal.class));
      var statsB =
          tenants.run(
              b,
              () ->
                  sql.fetchOne(
                          """
                          select count(*) as deliveries,
                                 coalesce(sum(blocked_count),0) as blocked
                            from platform_notify_delivery_record
                          """)
                      .get("blocked", BigDecimal.class));
      assertThat(statsA).as("第 " + round + " 轮：A 的拦截数").isEqualByComparingTo("1");
      assertThat(statsB).as("第 " + round + " 轮：B 的拦截数").isEqualByComparingTo("1");
    }
  }
}
