package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
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
 * 12.1.1.2 应收账龄的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <ol>
 *   <li>两租户互不可见；
 *   <li>伪造租户上下文无效 —— 并且<b>要断言行确实落在自称的租户名下</b>，不是只断言 A 看不见（只断言「看不见」的话，一句「写入被全表拒掉」也能骗过它）；
 *   <li>连接池归还后不串租户。
 * </ol>
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>刻意跑在本模块自己的表与端点上</b>：除了 12.1.1.1 的五张表，本模块新增 {@code finance_aging_bucket}
 * （账龄区间配置）—— 断言直接跑在这张表与本模块的三个读端点 / 一个写端点上，
 * 证明的是**真的要用的那张表**被隔离了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReceivableAgingTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-finance-aging-tenancy-" + tenant + "-" + ordinal).getBytes());
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
    var permissions = List.of("finance.receivable.read", "finance.receivable.update");
    actors.put("session-a", actor(userA, a, permissions));
    actors.put("session-b", actor(UUID.randomUUID(), b, permissions));
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
    r.add("DB_USER", () -> "finance_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  /**
   * 在可信租户事务里插入一张应收单（timestamptz 一律绑 OffsetDateTime + {@code ?::timestamptz}）。
   *
   * <p>⚠️ 直接插 SQL 而不走服务层：这里要证明的是<b>数据层</b>的隔离，
   * 服务层的策略闸门不该混进这个断言。
   */
  void insertReceivable(UUID tenant, UUID id, String no, String currency, String amount) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_receivable"
                  + "(id,tenant_id,receivable_no,source,source_system,counterparty_type,counterparty_name,"
                  + " currency,due_date,confirmed_at,status,created_at,updated_at)"
                  + " values (?,?,?,'MANUAL','manual','CUSTOMER','客户',? ,?::date,?::timestamptz,'DRAFT',"
                  + "?::timestamptz,?::timestamptz)",
              id,
              tenant,
              no,
              currency,
              LocalDate.now(ZoneOffset.UTC).minusDays(30),
              now,
              now,
              now);
          sql.execute(
              "insert into finance_receivable_line(id,tenant_id,receivable_id,line_no,description,amount,"
                  + " settled_amount,created_at,updated_at)"
                  + " values(?,?,?,1,'明细',?,0,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              id,
              new BigDecimal(amount),
              now,
              now);
          sql.execute("update finance_receivable set status='OPEN' where id = ?", id);
          return null;
        });
  }

  /** 以指定租户身份读应收单号。 */
  List<String> receivableNosOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select receivable_no from finance_receivable order by receivable_no")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读账龄区间配置的编码。 */
  List<String> bucketCodesOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select code from finance_aging_bucket order by code")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select receivable_no from finance_receivable").size();
  }

  /**
   * 每个用例前清空业务表，再种入两租户各一张应收单。
   *
   * <p><b>清空与播种必须在同一个方法里</b>：写成两个 {@code @BeforeEach} 时，JUnit 5 <b>不保证</b>它们的执行顺序。
   *
   * <p>用**迁移用户**（超管）连接来 TRUNCATE：RLS 对超管不生效。
   */
  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_receivable_event, finance_collection_task,"
              + " finance_receivable_line, finance_receivable, finance_receivable_policy,"
              + " finance_aging_bucket restart identity cascade");
    }
    insertReceivable(a, idFor(a, 1), "tenant-a-receivable", "USD", "100");
    insertReceivable(b, idFor(b, 1), "tenant-b-receivable", "EUR", "500");
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 账龄配置表强制rls而不是只启用() {
    for (String table : List.of("finance_aging_bucket", "finance_receivable")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V339 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住「运行角色被误授 owner」").isTrue();
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
    assertThat(receivableNosOf(a)).containsExactly("tenant-a-receivable");
    assertThat(receivableNosOf(b)).containsExactly("tenant-b-receivable");
    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch("select receivable_no from finance_receivable where id = ?", idFor(b, 1))
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();

    // 账龄区间配置同样隔离：A 配一份，B 看不见。
    // （配的是一份**合法**的区间：未到期档 + 一个无上界档 —— 非法配置会让报表端点直接拒绝。）
    tenants.run(
        a,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_aging_bucket(id,tenant_id,code,label,overdue_from_days,"
                  + " overdue_to_days,sort_order,created_at,updated_at)"
                  + " values(?,?,'NOT_DUE','未到期',null,null,0,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              a,
              now,
              now);
          sql.execute(
              "insert into finance_aging_bucket(id,tenant_id,code,label,overdue_from_days,"
                  + " overdue_to_days,sort_order,created_at,updated_at)"
                  + " values(?,?,'A_ONLY','A 的档',1,null,1,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              a,
              now,
              now);
          return null;
        });
    assertThat(bucketCodesOf(a)).containsExactly("A_ONLY", "NOT_DUE");
    assertThat(bucketCodesOf(b)).isEmpty();
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
                    "insert into finance_aging_bucket(id,tenant_id,code,label,overdue_from_days,"
                        + " overdue_to_days,sort_order,created_at,updated_at)"
                        + " values(?,?,'FORGED','伪造',1,null,1,?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    now,
                    now))
        .as("没有可信租户上下文时，写入必须被 RLS 挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因（RLS 策略拒绝）在 cause 里。
        .hasStackTraceContaining("row-level security");

    // 伪造的行一条都没落库。
    assertThat(
            sql.fetch("select code from finance_aging_bucket where code = 'FORGED'").size())
        .isZero();
  }

  // ==========================================================================
  // 断言 2 的另一半 —— 伪造租户**头**无效，且**行落在会话租户名下**
  // ==========================================================================
  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    String body =
        """
        {"buckets":[{"code":"NOT_DUE","label":"未到期","overdueFromDays":null,"overdueToDays":null,"sortOrder":0},
                    {"code":"D1_PLUS","label":"逾期 1 天以上","overdueFromDays":1,"overdueToDays":null,"sortOrder":1}]}
        """;

    http.perform(
            put("/finance/receivable-aging/buckets")
                .header("Authorization", "session-a")
                // 伪造的租户头与内部键：代理与服务层都必须忽略它们。
                .header("X-Tenant-Id", b.toString())
                .header("X-Internal-Key", "forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());

    // 行确实存在（不是被全表拒掉）。
    var rows =
        tenants.run(
            a,
            () -> sql.fetch("select code, tenant_id from finance_aging_bucket order by code"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(2);
    // ...且它落在**会话租户** a 名下，**不是**调用方自称的 b。
    assertThat(rows.get(0).get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);

    // 租户 b 看不到它（它不属于 b），并且 b 的报表仍然是 b 自己的口径。
    assertThat(bucketCodesOf(b)).isEmpty();
    var bDistribution =
        json.readValue(
            http.perform(
                    get("/finance/receivable-aging/distribution").header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ReceivableAgingModels.FinanceReceivableAgingDistribution.class);
    assertThat(bDistribution.bucketSource())
        .as("b 没有配过区间 ⇒ b 看到的必须是蓝图默认，而不是 a 的配置")
        .isEqualTo("BLUEPRINT_DEFAULT");
    assertThat(bDistribution.totals())
        .extracting(ReceivableAgingModels.FinanceAgingCurrencyTotal::currency)
        .containsExactly("EUR");
  }

  @Test
  void 断言2_伪造租户头读不到别的租户的账龄() throws Exception {
    var aDistribution =
        json.readValue(
            http.perform(
                    get("/finance/receivable-aging/distribution")
                        .header("Authorization", "session-a")
                        .header("X-Tenant-Id", b.toString()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ReceivableAgingModels.FinanceReceivableAgingDistribution.class);
    // A 只看得到自己的 USD 那一张。
    assertThat(aDistribution.totals())
        .extracting(ReceivableAgingModels.FinanceAgingCurrencyTotal::currency)
        .containsExactly("USD");
  }

  // ==========================================================================
  // 断言 3
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证后一次读到的仍然是 B 而不是上一轮残留的 A。
    for (int i = 0; i < 5; i++) {
      assertThat(receivableNosOf(a)).as("第 %d 轮租户 A", i).containsExactly("tenant-a-receivable");
      assertThat(receivableNosOf(b)).as("第 %d 轮租户 B", i).containsExactly("tenant-b-receivable");
      assertThat(bucketCodesOf(a)).as("第 %d 轮 A 的区间配置", i).isEmpty();
      assertThat(bucketCodesOf(b)).as("第 %d 轮 B 的区间配置", i).isEmpty();
      assertThat(visibleWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(receivableNosOf(a)).containsExactly("tenant-a-receivable");
    assertThat(receivableNosOf(b)).containsExactly("tenant-b-receivable");
  }
}
