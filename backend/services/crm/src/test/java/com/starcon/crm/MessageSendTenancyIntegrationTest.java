package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.CopyOnWriteArrayList;
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
 * crm 服务的<b>租户隔离</b>证明（15.1.1.2 消息发送车道），跑在 AGENTS.md 明文要求的三条断言上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <ol>
 *   <li>两租户互不可见；
 *   <li>伪造租户上下文无效 —— 并且<b>要断言行确实落在自称的租户名下</b>，
 *       不是只断言 A 看不见（只断言「看不见」的话，一句「写入被全表拒掉」也能骗过它）；
 *   <li>连接池归还后不串租户。
 * </ol>
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p>刻意跑在<b>真实业务表</b>上（{@code crm_message_send} / {@code crm_message_send_template} /
 * {@code crm_message_send_attachment} / {@code crm_message_send_attempt}），
 * 不建占位探针表 —— 证明的是<b>真的要用的那张表</b>被隔离了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MessageSendTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID();
  static final UUID shop = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<MessageContextPort.ShopView> upstreamShops = new CopyOnWriteArrayList<>();
  static HttpServer identity;
  static HttpServer channel;
  static HttpServer trade;

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role crm_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    List<String> permissions =
        List.of("crm.message.send.read", "crm.message.send.update", "crm.message.read",
            "crm.message.update");
    actors.put(
        "session-a",
        new AccessService.Access(
            userA,
            a,
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
    try {
      channel = HttpServer.create(new InetSocketAddress(0), 0);
      channel.createContext(
          "/channel/shops",
          e -> {
            byte[] body = json.writeValueAsBytes(List.copyOf(upstreamShops));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      channel.setExecutor(Executors.newFixedThreadPool(2));
      channel.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    try {
      trade = HttpServer.create(new InetSocketAddress(0), 0);
      trade.createContext(
          "/trade/sales-orders/list",
          e -> {
            byte[] body =
                json.writeValueAsBytes(
                    Map.of(
                        "rows",
                        List.of(),
                        "page",
                        0,
                        "size",
                        0,
                        "total",
                        0,
                        "dataScope",
                        "ALL",
                        "dataScopeNote",
                        "本租户全量"));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      trade.setExecutor(Executors.newFixedThreadPool(2));
      trade.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "crm_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:" + channel.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + trade.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  /** 在可信租户事务里插一条收到的消息（timestamptz 一律绑 OffsetDateTime + {@code ?::timestamptz}）。 */
  UUID insertMessage(UUID tenant, String buyer) {
    UUID id = UUID.randomUUID();
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into crm_message"
                  + "(id,tenant_id,message_no,shop_id,shop_name,platform_code,buyer_reference,"
                  + " order_reference,subject,body,direction,message_type,priority,read_status,"
                  + " platform_time,attachment_count,source,created_at,updated_at)"
                  + " values (?,?,?,?,'测试店铺','AMAZON',?,'SO-T','主题','正文','INBOUND',"
                  + " 'BUYER_MESSAGE','NORMAL','UNREAD',?::timestamptz,0,'MANUAL_ENTRY',"
                  + " ?::timestamptz,?::timestamptz)",
              id,
              tenant,
              "MSG-" + tenant.toString().substring(0, 8) + "-" + buyer,
              shop,
              buyer,
              OffsetDateTime.now(ZoneOffset.UTC),
              OffsetDateTime.now(ZoneOffset.UTC),
              OffsetDateTime.now(ZoneOffset.UTC));
          return null;
        });
    return id;
  }

  /** 在可信租户事务里插一条发送记录。 */
  void insertSend(UUID tenant, UUID targetMessageId, String key) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into crm_message_send"
                  + "(id,tenant_id,send_no,target_message_id,shop_id,shop_name,platform_code,"
                  + " buyer_reference,order_reference,subject,body,body_source,transport,status,"
                  + " failure_reason,platform_receipt,platform_message_id,attachment_count,"
                  + " attempt_count,last_attempt_at,sent_at,idempotency_key,created_at,updated_at)"
                  + " values (?,?,?,?,?,'测试店铺','AMAZON','buyer-t','SO-T','主题','正文','MANUAL',"
                  + " 'MANUAL_HANDOFF','SENT','','','',0,1,?::timestamptz,?::timestamptz,?,"
                  + " ?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              "MSGS-" + tenant.toString().substring(0, 8) + "-" + key,
              targetMessageId,
              shop,
              OffsetDateTime.now(ZoneOffset.UTC),
              OffsetDateTime.now(ZoneOffset.UTC),
              key,
              OffsetDateTime.now(ZoneOffset.UTC),
              OffsetDateTime.now(ZoneOffset.UTC));
          return null;
        });
  }

  /** 以指定租户身份读发送记录的幂等键。 */
  List<String> keysOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select idempotency_key from crm_message_send order by idempotency_key")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select idempotency_key from crm_message_send").size();
  }

  String rawTenantSetting() {
    return sql.fetch("select current_setting('app.current_tenant', true)").stream()
        .findFirst()
        .map(r -> r.get(0, String.class))
        .orElse(null);
  }

  /** RLS 策略<b>实际</b>使用的值：空串与 NULL 都归一成 null，也就是「没有租户」。 */
  UUID effectiveTenant() {
    var row = sql.fetchOne("select nullif(current_setting('app.current_tenant', true),'')::uuid");
    return row == null ? null : row.get(0, UUID.class);
  }

  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table crm_message_send_attachment, crm_message_send_attempt,"
              + " crm_message_send, crm_message_send_template");
      s.execute("truncate table crm_message_attachment, crm_message_intent, crm_message");
    }
    upstreamShops.clear();
    upstreamShops.add(
        new MessageContextPort.ShopView(
            shop, "SHOP-T", "测试店铺", "AMAZON", "亚马逊", "SELF_FULFILLED", "ACTIVE"));
    UUID messageA = insertMessage(a, "buyer-a");
    UUID messageB = insertMessage(b, "buyer-b");
    insertSend(a, messageA, "key-a");
    insertSend(b, messageB, "key-b");
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是<b>强制</b>的
  // ==========================================================================
  @Test
  void 业务表强制rls而不是只启用() {
    for (String table :
        List.of(
            "crm_message_send_template",
            "crm_message_send",
            "crm_message_send_attachment",
            "crm_message_send_attempt")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V297 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住").isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('crm_app','" + table + "','SELECT')")
                  .get(0, Boolean.class))
          .as("crm_app 必须真的能读 " + table)
          .isTrue();
    }
  }

  // ==========================================================================
  // 断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    assertThat(keysOf(a)).containsExactly("key-a");
    assertThat(keysOf(b)).containsExactly("key-b");
    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byKey =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select idempotency_key from crm_message_send where idempotency_key = 'key-b'")
                    .size());
    assertThat(byKey).as("按条件直查也必须被 RLS 挡住").isZero();
  }

  // ==========================================================================
  // 断言 2 —— 伪造租户头无效，且<b>行落在会话租户名下</b>
  // ==========================================================================
  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    assertThat(rawTenantSetting()).isNullOrEmpty();
    assertThat(effectiveTenant()).as("未绑定租户时策略看到的租户必须是 null").isNull();
    assertThat(visibleWithoutTenant()).as("未绑定租户时不应看到任何行").isZero();

    var bound =
        tenants.run(
            b,
            () -> {
              // ⚠️ 刻意<b>不</b>在这里再调 tenants.run（那是嵌套事务）：
              //   这里要证明的是「事务内只看得到本租户」，直接查一次即可。
              var visible =
                  sql.fetch("select idempotency_key from crm_message_send order by idempotency_key")
                      .map(r -> r.get(0, String.class));
              assertThat(visible).as("事务内只看得到本租户").containsExactly("key-b");
              return rawTenantSetting();
            });
    assertThat(bound).isEqualTo(b.toString());
    assertThat(rawTenantSetting()).as("提交后租户上下文必须已清空").isNullOrEmpty();
    assertThat(effectiveTenant()).as("提交后策略看到的租户必须是 null").isNull();
    assertThat(visibleWithoutTenant()).as("提交后事务外不应看到任何行").isZero();

    // 伪造 X-Tenant-Id 头发一次登记：行必须落在**会话租户 a** 名下。
    UUID targetA =
        tenants.run(
            a,
            () ->
                sql.fetchOne("select id from crm_message where buyer_reference='buyer-a'")
                    .get("id", UUID.class));
    String content =
        """
        {"targetMessageId":"%s","body":"伪造头","transport":"MANUAL_HANDOFF","status":"SENT",
         "idempotencyKey":"forged-header-key"}
        """
            .formatted(targetA);

    http.perform(
            post("/crm/message-sends/sends")
                .header("Authorization", "session-a")
                .header("X-Tenant-Id", b.toString())
                .header("X-Internal-Key", "forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content(content))
        .andExpect(status().isOk());

    // 行确实存在（不是被全表拒掉），且<b>落在会话租户 a 名下</b>。
    var rows =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select id, tenant_id from crm_message_send where idempotency_key = ?",
                    "forged-header-key"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(1);
    assertThat(rows.get(0).get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);

    // 租户 b 看不到它（它不属于 b）。
    assertThat(
            tenants.run(
                b,
                () ->
                    sql.fetch(
                            "select id from crm_message_send where idempotency_key = ?",
                            "forged-header-key")
                        .size()))
        .as("这一行不属于 b，b 必须看不到")
        .isZero();

    // 租户 a 看得到（它确实属于 a）。
    assertThat(keysOf(a)).as("写入必须归到会话租户 a")
        .containsExactly("forged-header-key", "key-a");
  }

  // ==========================================================================
  // 断言 3
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证后一次读到的仍然是 B 而不是上一轮残留的 A。
    for (int i = 0; i < 5; i++) {
      assertThat(keysOf(a)).as("第 %d 轮租户 A", i).containsExactly("key-a");
      assertThat(keysOf(b)).as("第 %d 轮租户 B", i).containsExactly("key-b");
      assertThat(visibleWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(keysOf(a)).containsExactly("key-a");
    assertThat(keysOf(b)).containsExactly("key-b");
  }
}
