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
 * 15.4.1.1 工单列表的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
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
 * <p><b>刻意跑在真实业务表上</b>（{@code crm_service_business_hours} /
 * {@code crm_service_business_holiday} / {@code crm_service_business_hours_audit}）：
 * 证明的是**真的要用的那张表**被隔离了，而不是一张之后会被删掉的探针表。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ServiceTicketListTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

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
      s.execute("create role crm_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    List<String> permissions =
        List.of("crm.service_ticket.read", "crm.service_ticket.update");
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
    r.add("DB_USER", () -> "crm_app");
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

  /** 在可信租户事务里插入一条营业时段（timestamptz 一律绑 OffsetDateTime + {@code ?::timestamptz}）。 */
  void insertWindow(UUID tenant, UUID id, String scopeKey, int weekday, String start, String end) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into crm_service_business_hours"
                  + "(id,tenant_id,scope_key,weekday,start_time,end_time,timezone,enabled,"
                  + " created_at,updated_at)"
                  + " values (?,?::uuid,?,?,?::time,?::time,'Asia/Shanghai',true,"
                  + "?::timestamptz,?::timestamptz)",
              id,
              tenant,
              scopeKey,
              weekday,
              java.sql.Time.valueOf(start),
              java.sql.Time.valueOf(end),
              OffsetDateTime.now(ZoneOffset.UTC),
              OffsetDateTime.now(ZoneOffset.UTC));
          return null;
        });
  }

  /** 以指定租户身份读营业时段的口径（时区 + 起点）。 */
  List<String> windowsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch(
                    "select scope_key || ' ' || weekday || ' ' || start_time"
                        + " from crm_service_business_hours order by weekday, start_time")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select id from crm_service_business_hours").size();
  }

  String rawTenantSetting() {
    return sql.fetch("select current_setting('app.current_tenant', true)").stream()
        .findFirst()
        .map(r -> r.get(0, String.class))
        .orElse(null);
  }

  /** RLS 策略**实际**使用的值：空串与 NULL 都归一成 null，也就是「没有租户」。 */
  UUID effectiveTenant() {
    var row = sql.fetchOne("select nullif(current_setting('app.current_tenant', true),'')::uuid");
    return row == null ? null : row.get(0, UUID.class);
  }

  /**
   * 每个用例前清空三张业务表，再种入两租户各一行。
   *
   * <p>这些测试共享同一个容器与同一个库，用例之间**不会**自动回滚，
   * 所以「断言 2」写下的伪造头那一行会留在库里 —— 那是测试夹具的脏，不是隔离被破坏。
   */
  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table crm_service_business_hours_audit, crm_service_business_holiday,"
              + " crm_service_business_hours");
    }
    insertWindow(a, UUID.randomUUID(), "DEFAULT", 1, "09:00:00", "18:00:00");
    insertWindow(b, UUID.randomUUID(), "DEFAULT", 1, "08:00:00", "17:00:00");
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 业务表强制rls而不是只启用() {
    for (String table :
        List.of(
            "crm_service_business_hours",
            "crm_service_business_holiday",
            "crm_service_business_hours_audit")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V306 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class))
          .as(table + " 只有 FORCE 才挡得住「运行角色被误授 owner」")
          .isTrue();
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
    assertThat(windowsOf(a)).containsExactly("DEFAULT 1 09:00:00");
    assertThat(windowsOf(b)).containsExactly("DEFAULT 1 08:00:00");
  }

  // ==========================================================================
  // 断言 2 —— 伪造租户头无效，且**行落在会话租户名下**
  // ==========================================================================
  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    // (1) 服务在事务外没有「环境租户」：不存在一个可以被沿用的默认租户。
    assertThat(rawTenantSetting()).isNullOrEmpty();
    assertThat(effectiveTenant()).isNull();
    assertThat(visibleWithoutTenant()).as("未绑定租户时不应看到任何行").isZero();

    // (2) 事务内绑定的值精确等于可信参数，提交后立刻失效。
    var bound =
        tenants.run(
            b,
            () -> {
              assertThat(effectiveTenant()).as("事务内只看得到本租户").isEqualTo(b);
              return rawTenantSetting();
            });
    assertThat(bound).isEqualTo(b.toString());
    assertThat(effectiveTenant()).as("提交后租户上下文必须已清空").isNull();
    assertThat(visibleWithoutTenant()).isZero();

    // (3) 真正的入口断言：带**合法会话**（会话租户是 a）但**伪造 X-Tenant-Id: b**
    //     写一条营业时段。
    //     只断言「A 看不见 B」是不够的 —— 那一句「写入被全表拒掉」也能让它通过。
    //     这里要证明的是：这一行**确实写进去了**，而且**落在会话租户 a 名下**，
    //     完全没有落到调用方自称的 b 名下。
    http.perform(
            put("/crm/service-tickets/business-hours")
                .header("Authorization", "session-a")
                .header("X-Tenant-Id", b.toString())
                .header("X-Internal-Key", "forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"timezone":"Asia/Shanghai",
                     "windows":[{"weekday":3,"startTime":"10:00:00","endTime":"16:00:00","enabled":true}]}
                    """))
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
                    "select scope_key, weekday, start_time, tenant_id"
                        + " from crm_service_business_hours where weekday = 3"));
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
                        "select id from crm_service_business_hours where weekday = 3")
                    .size());
    assertThat(visibleToB).as("这一行不属于 b，b 必须看不到").isZero();

    // 租户 a 看得到它（它确实属于 a）。
    // ⚠️ 营业时段的写入是**整段替换**（改一次作用域 = 该作用域的新口径），
    //   所以租户 a 原来那条周一窗口已被这次改写替换掉 —— 这也顺带证明了
    //   这次改写**只动了会话租户 a 的作用域**，没有波及租户 b。
    assertThat(windowsOf(a))
        .as("写入必须归到会话租户 a")
        .containsExactly("DEFAULT 3 10:00:00");
    assertThat(windowsOf(b)).as("b 仍然只看得见自己那一行").containsExactly("DEFAULT 1 08:00:00");
  }

  // ==========================================================================
  // 断言 3
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证后一次读到的仍然是 B 而不是上一轮残留的 A。
    for (int i = 0; i < 5; i++) {
      assertThat(windowsOf(a)).as("第 %d 轮租户 A", i).containsExactly("DEFAULT 1 09:00:00");
      assertThat(windowsOf(b)).as("第 %d 轮租户 B", i).containsExactly("DEFAULT 1 08:00:00");
      assertThat(visibleWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(windowsOf(a)).containsExactly("DEFAULT 1 09:00:00");
    assertThat(windowsOf(b)).containsExactly("DEFAULT 1 08:00:00");
  }
}
