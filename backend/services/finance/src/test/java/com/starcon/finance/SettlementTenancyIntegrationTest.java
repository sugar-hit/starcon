package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
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
 * finance 服务的**租户隔离**证明（12.3.1.1 结算单这一段），跑在 AGENTS.md 明文要求的三条断言上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <ol>
 *   <li>两租户互不可见；
 *   <li>伪造租户上下文无效 —— 并且<b>要断言行确实落在自称的租户名下</b>，不是只断言 A 看不见；
 *   <li>连接池归还后不串租户。
 * </ol>
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>刻意跑在本模块自己的业务表上</b>：隔离断言直接跑在 {@code finance_settlement} /
 * {@code finance_settlement_summary} / {@code finance_settlement_line} /
 * {@code finance_settlement_source_file} / {@code finance_settlement_cycle} /
 * {@code finance_settlement_event} 上 —— 证明的是**本模块真的要用的那几张表**被隔离了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SettlementTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID();

  /** 上游 channel 的店铺（这条用例会走服务层建单，所以要给真的店铺 id）。 */
  static final UUID SHOP_ID = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer channel;

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-finance-settlement-tenancy-" + tenant + "-" + ordinal).getBytes());
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

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role finance_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    actors.put(
        "session-a",
        actor(
            userA,
            a,
            List.of(
                "finance.settlement.read",
                "finance.settlement.create",
                "finance.settlement.update")));
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

      // 上游 channel 2.3.1.1 的店铺列表（只读端口 ChannelShopPort 打的就是它）。
      channel = HttpServer.create(new InetSocketAddress(0), 0);
      channel.createContext(
          "/channel/shops",
          e -> {
            if (actors.get(e.getRequestHeaders().getFirst("Authorization")) == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body =
                ("[{\"id\":\""
                        + SHOP_ID
                        + "\",\"code\":\"SHOP-1\",\"name\":\"美国一店\","
                        + "\"platformCode\":\"amazon\",\"platformName\":\"Amazon\",\"status\":\"ACTIVE\"}]")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "finance_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:" + channel.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://127.0.0.1:1");
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  /**
   * 在可信租户事务里插入一张结算单（timestamptz 一律绑 OffsetDateTime + {@code ?::timestamptz}）。
   *
   * <p>⚠️ 直接插 SQL 而不走服务层：这里要证明的是<b>数据层</b>的隔离。
   */
  void insertSettlement(UUID tenant, UUID id, String no) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_settlement"
                  + "(id,tenant_id,settlement_no,shop_id,shop_code,shop_name,platform_code,"
                  + " period_start,period_end,currency,settlement_amount,status,received_amount,"
                  + " source,source_system,source_reference,parse_version,note,version,created_at,updated_at)"
                  + " values (?,?,?,?,?,?,?,?::date,?::date,'USD',0,'PENDING',0,'MANUAL_UPLOAD','manual',"
                  + " ?,1,'',0,?::timestamptz,?::timestamptz)",
              id,
              tenant,
              no,
              UUID.randomUUID(),
              "SHOP-1",
              "美国一店",
              "amazon",
              LocalDate.of(2026, 10, 1),
              LocalDate.of(2026, 10, 15),
              no + "-ref",
              now,
              now);
          sql.execute(
              "insert into finance_settlement_line"
                  + "(id,tenant_id,settlement_id,line_no,category,amount,raw_amount,currency,description,created_at)"
                  + " values (?,?,?,1,'SALES',100,100,'USD','行',?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              id,
              now);
          return null;
        });
  }

  /** 以指定租户身份读结算单号。 */
  List<String> settlementNosOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select settlement_no from finance_settlement order by settlement_no")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select settlement_no from finance_settlement").size();
  }

  /**
   * 每个用例前清空业务表，再种入两租户各一行。
   *
   * <p>这些测试共享同一个容器与同一个库，用例之间**不会**自动回滚，所以断言 2 写下的伪造头那一行
   * 会留在库里，把断言 1 / 3 的 {@code containsExactly} 变成永远失败 —— 那是测试夹具的脏，
   * 不是隔离被破坏。
   *
   * <p><b>清空与播种必须在同一个方法里</b>：写成两个 {@code @BeforeEach} 时，JUnit 5
   * <b>不保证</b>它们的执行顺序。
   *
   * <p>用**迁移用户**（超管）连接来 TRUNCATE：RLS 对超管不生效；
   * 而 FORCE RLS 下的运行角色做不到「清掉所有租户的行」。
   */
  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_settlement_event, finance_settlement_summary,"
              + " finance_settlement_line, finance_settlement, finance_settlement_source_file,"
              + " finance_settlement_cycle, finance_receipt_event, finance_receipt_allocation,"
              + " finance_receipt, finance_receivable_event, finance_collection_task,"
              + " finance_receivable_line, finance_receivable, finance_receivable_policy"
              + " restart identity cascade");
    }
    insertSettlement(a, idFor(a, 1), "tenant-a-settlement");
    insertSettlement(b, idFor(b, 1), "tenant-b-settlement");
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 业务表强制rls而不是只启用() {
    for (String table :
        List.of(
            "finance_settlement",
            "finance_settlement_summary",
            "finance_settlement_line",
            "finance_settlement_source_file",
            "finance_settlement_cycle",
            "finance_settlement_event")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V345 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class))
          .as(table + " 只有 FORCE 才挡得住「运行角色被误授 owner」")
          .isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('finance_app','" + table + "','SELECT')")
                  .get(0, Boolean.class))
          .as("finance_app 必须真的能读 " + table)
          .isTrue();
    }
  }

  // ==========================================================================
  // 断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    assertThat(settlementNosOf(a)).containsExactly("tenant-a-settlement");
    assertThat(settlementNosOf(b)).containsExactly("tenant-b-settlement");
    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch("select settlement_no from finance_settlement where id = ?", idFor(b, 1))
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();
  }

  // ==========================================================================
  // 断言 2 —— 伪造租户上下文无效
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() {
    UUID forged = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    assertThatThrownBy(
            () ->
                sql.execute(
                    "insert into finance_settlement"
                        + "(id,tenant_id,settlement_no,shop_id,period_start,period_end,currency,"
                        + " settlement_amount,status,received_amount,source,source_system,parse_version,"
                        + " version,created_at,updated_at)"
                        + " values (?,?,?,?,?::date,?::date,'USD',0,'PENDING',0,'MANUAL_UPLOAD','manual',1,0,"
                        + "?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    "forged-tenant-row",
                    UUID.randomUUID(),
                    LocalDate.of(2026, 10, 1),
                    LocalDate.of(2026, 10, 15),
                    now,
                    now))
        .as("没有可信租户上下文时，写入必须被 RLS 挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因（RLS 策略拒绝）在 cause 里。
        .hasStackTraceContaining("row-level security");

    // 伪造的行一条都没落库。
    assertThat(visibleWithoutTenant()).isZero();
  }

  // ==========================================================================
  // 断言 2 的另一半 —— 伪造租户**头**无效，且**行落在会话租户名下**
  // ==========================================================================
  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    // 手工登记一张结算单（走服务层 ⇒ 真走鉴权 + 事务 + RLS）。
    var body =
        """
        {"source":"MANUAL","sourceSystem":"manual","sourceReference":"FORGED-HDR-1",
         "shopId":"%s","periodStart":"2026-10-01","periodEnd":"2026-10-15","currency":"USD",
         "lines":[{"category":"SALES","amount":"100.0000"}]}
        """
            .formatted(SHOP_ID);

    http.perform(
            post("/finance/settlement")
                .header("Authorization", "session-a")
                .header("X-Tenant-Id", b.toString())
                .header("X-Internal-Key", "forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());

    // 行确实存在（不是被全表拒掉）。
    // 读取**必须在某个租户事务里**做：事务外没有任何租户上下文，RLS 会诚实地把
    // 所有行都挡掉 —— 那恰好又是一次隔离生效的证明，但会让「行到底写到哪去了」
    // 这个问题答不了。
    var rows =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select id, tenant_id from finance_settlement where source_reference = ?",
                    "FORGED-HDR-1"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(1);
    // ...且它落在**会话租户** a 名下，**不是**调用方自称的 b。
    assertThat(rows.get(0).get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);

    // 租户 b 看不到它（它不属于 b）。
    var visibleToB =
        tenants.run(
            b,
            () ->
                sql.fetch(
                        "select id from finance_settlement where source_reference = ?", "FORGED-HDR-1")
                    .size());
    assertThat(visibleToB).as("这一行不属于 b，b 必须看不到").isZero();

    // 租户 a 看得到（它确实属于 a）。
    assertThat(settlementNosOf(a)).hasSize(2).contains("tenant-a-settlement");
  }

  // ==========================================================================
  // 断言 3
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证后一次读到的仍然是 B 而不是上一轮残留的 A。
    for (int i = 0; i < 5; i++) {
      assertThat(settlementNosOf(a)).as("第 %d 轮租户 A", i).containsExactly("tenant-a-settlement");
      assertThat(settlementNosOf(b)).as("第 %d 轮租户 B", i).containsExactly("tenant-b-settlement");
      assertThat(visibleWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(settlementNosOf(a)).containsExactly("tenant-a-settlement");
    assertThat(settlementNosOf(b)).containsExactly("tenant-b-settlement");
  }
}
