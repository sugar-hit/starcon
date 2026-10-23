package com.starcon.finance;

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
 * finance 服务的**租户隔离**证明（12.2.1.2 核销这一段），跑在 AGENTS.md 明文要求的三条断言上。
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
 * <p><b>刻意跑在本模块自己的业务表上</b>：隔离断言直接跑在 {@code finance_write_off} /
 * {@code finance_write_off_allocation} / {@code finance_write_off_event} /
 * {@code finance_advance_ledger} 上 —— 证明的是**本模块真的要用的那几张表**被隔离了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WriteOffTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-finance-write-off-tenancy-" + tenant + "-" + ordinal).getBytes());
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
            UUID.randomUUID(),
            a,
            List.of("finance.writeoff.read", "finance.writeoff.create", "finance.writeoff.update")));
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
    r.add("TRADE_URL", () -> "http://127.0.0.1:1");
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  /**
   * 在可信租户事务里插入一张收款单 + 一张核销记录（含一条分配与一条事件）。
   *
   * <p>⚠️ 直接插 SQL 而不走服务层：这里要证明的是<b>数据层</b>的隔离。
   */
  void insertWriteOff(UUID tenant, UUID id, String no) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          var receiptId = UUID.nameUUIDFromBytes((no + "-receipt").getBytes());
          sql.execute(
              "insert into finance_receipt"
                  + "(id,tenant_id,receipt_no,source,source_system,currency,amount,advance_flag,"
                  + " status,received_at,payer_type,payer_name,created_at,updated_at)"
                  + " values (?,?,?,'MANUAL','manual','USD',100,false,'RECORDED',?::timestamptz,"
                  + " 'CUSTOMER','客户',?::timestamptz,?::timestamptz)",
              receiptId,
              tenant,
              no + "-receipt",
              now,
              now,
              now);
          sql.execute(
              "insert into finance_write_off"
                  + "(id,tenant_id,write_off_no,receipt_id,currency,mode,status,settlement_amount,"
                  + " advance_amount,total_amount,note,occurred_at,created_at,updated_at)"
                  + " values (?,?,?,?,'USD','MANUAL','POSTED',100,0,100,'',?::timestamptz,"
                  + " ?::timestamptz,?::timestamptz)",
              id,
              tenant,
              no,
              receiptId,
              now,
              now,
              now);
          sql.execute(
              "insert into finance_write_off_allocation"
                  + "(id,tenant_id,write_off_id,receipt_id,allocation_type,order_no,amount,status,"
                  + " note,created_at,updated_at)"
                  + " values (?,?,?,?,'ADVANCE','',100,'POSTED','',?::timestamptz,?::timestamptz)",
              UUID.nameUUIDFromBytes((no + "-allocation").getBytes()),
              tenant,
              id,
              receiptId,
              now,
              now);
          sql.execute(
              "insert into finance_write_off_event"
                  + "(id,tenant_id,write_off_id,event_type,from_status,to_status,reason,occurred_at)"
                  + " values (?,?,?,'MANUAL_ASSIGNED','','POSTED','',?::timestamptz)",
              UUID.nameUUIDFromBytes((no + "-event").getBytes()),
              tenant,
              id,
              now);
          return null;
        });
  }

  /** 以指定租户身份读核销记录号。 */
  List<String> writeOffNosOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select write_off_no from finance_write_off order by write_off_no")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select write_off_no from finance_write_off").size();
  }

  /**
   * 每个用例前清空业务表，再种入两租户各一行。
   *
   * <p>清空与播种必须在<b>同一个方法</b>里：写成两个 {@code @BeforeEach} 时，JUnit 5
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
          "truncate table finance_write_off_event, finance_advance_ledger,"
              + " finance_write_off_allocation, finance_write_off,"
              + " finance_receipt_event, finance_receipt_allocation, finance_receipt,"
              + " finance_receivable_event, finance_collection_task, finance_receivable_line,"
              + " finance_receivable, finance_receivable_policy restart identity cascade");
    }
    insertWriteOff(a, idFor(a, 1), "tenant-a-write-off");
    insertWriteOff(b, idFor(b, 1), "tenant-b-write-off");
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 业务表强制rls而不是只启用() {
    for (String table :
        List.of(
            "finance_write_off",
            "finance_write_off_allocation",
            "finance_write_off_event",
            "finance_advance_ledger")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V344 必须真的建出 " + table).isNotNull();
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
    assertThat(writeOffNosOf(a)).containsExactly("tenant-a-write-off");
    assertThat(writeOffNosOf(b)).containsExactly("tenant-b-write-off");
    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch("select write_off_no from finance_write_off where id = ?", idFor(b, 1))
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();
    // 关联表同样隔离：分配明细与事件流也看不见对方的行。
    long bAllocations =
        tenants.run(
            a,
            () ->
                sql.fetchOne(
                        "select count(*) from finance_write_off_allocation a"
                            + " join finance_write_off w on w.tenant_id = a.tenant_id"
                            + " and w.id = a.write_off_id where w.write_off_no = ?",
                        "tenant-b-write-off")
                    .get(0, Long.class));
    long bEvents =
        tenants.run(
            a,
            () ->
                sql.fetchOne(
                        "select count(*) from finance_write_off_event e"
                            + " join finance_write_off w on w.tenant_id = e.tenant_id"
                            + " and w.id = e.write_off_id where w.write_off_no = ?",
                        "tenant-b-write-off")
                    .get(0, Long.class));
    assertThat(bAllocations).as("关联表同样隔离").isZero();
    assertThat(bEvents).as("事件流同样隔离").isZero();
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
                    "insert into finance_write_off"
                        + "(id,tenant_id,write_off_no,receipt_id,currency,mode,status,"
                        + " settlement_amount,advance_amount,total_amount,note,occurred_at,"
                        + " created_at,updated_at)"
                        + " values (?,?,?,?,'USD','MANUAL','POSTED',0,0,0,'',?::timestamptz,"
                        + " ?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    "forged-tenant-row",
                    UUID.randomUUID(),
                    now,
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
    // 收款单不存在 ⇒ 端点会先因「收款单不存在」失败；这里要证明的是**租户归属**，
    // 所以先用迁移用户（超管）造一张真实收款单，再伪造租户头发起请求。
    UUID receiptId = UUID.randomUUID();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var ps = c.prepareStatement(
            "insert into finance_receipt"
                + "(id,tenant_id,receipt_no,source,source_system,currency,amount,advance_flag,"
                + " status,received_at,payer_type,payer_name,created_at,updated_at)"
                + " values (?,?,?,'MANUAL','manual','USD',100,false,'RECORDED',?::timestamptz,"
                + " 'CUSTOMER','客户',?::timestamptz,?::timestamptz)")) {
      var now = OffsetDateTime.now(ZoneOffset.UTC);
      ps.setObject(1, receiptId);
      ps.setObject(2, a);
      ps.setObject(3, "forged-header-receipt");
      ps.setObject(4, now);
      ps.setObject(5, now);
      ps.setObject(6, now);
      ps.execute();
    }

    String body =
        """
        {"receiptId":"%s",
         "allocations":[{"type":"ADVANCE","amount":100}],
         "note":"伪造头"}
        """
            .formatted(receiptId);

    http.perform(
            post("/finance/write-off")
                .header("Authorization", "session-a")
                .header("X-Tenant-Id", b.toString())
                .header("X-Internal-Key", "forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());

    // 行确实存在（不是被全表拒掉）。
    // 读取**必须在某个租户事务里**做：事务外没有任何租户上下文，RLS 会诚实地把所有行都挡掉。
    var rows =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select id, tenant_id from finance_write_off where note = ?", "伪造头"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(1);
    // ...且它落在**会话租户** a 名下，**不是**调用方自称的 b。
    assertThat(rows.get(0).get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);

    // 租户 b 看不到它（它不属于 b）。
    long visibleToB =
        tenants.run(
            b,
            () -> sql.fetch("select id from finance_write_off where note = ?", "伪造头").size());
    assertThat(visibleToB).as("这一行不属于 b，b 必须看不到").isZero();

    // 租户 a 看得到（它确实属于 a）。
    assertThat(writeOffNosOf(a)).contains("tenant-a-write-off");
  }

  // ==========================================================================
  // 断言 3
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证后一次读到的仍然是 B 而不是上一轮残留的 A。
    for (int i = 0; i < 5; i++) {
      assertThat(writeOffNosOf(a)).as("第 %d 轮租户 A", i).containsExactly("tenant-a-write-off");
      assertThat(writeOffNosOf(b)).as("第 %d 轮租户 B", i).containsExactly("tenant-b-write-off");
      assertThat(visibleWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(writeOffNosOf(a)).containsExactly("tenant-a-write-off");
    assertThat(writeOffNosOf(b)).containsExactly("tenant-b-write-off");
  }
}
