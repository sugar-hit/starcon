package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.VoucherModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
 * finance 服务的**租户隔离**证明（12.5.2.1 凭证管理这一批表）。
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
 * <p><b>刻意跑在本模块自己的表上</b>：12.5.2.1 的表是 {@code finance_voucher_period} /
 * {@code finance_voucher} / {@code finance_voucher_line} / {@code finance_voucher_event} /
 * {@code finance_voucher_mapping} —— 证明的是**真的要用的那几张表**被隔离了，
 * 而不是只在别人的表上做过一次。
 */
@SpringBootTest
@AutoConfigureMockMvc
class VoucherTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer upstream;

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-finance-voucher-tenancy-" + tenant + "-" + ordinal).getBytes());
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
    var permissions =
        List.of(
            "finance.voucher.read",
            "finance.voucher.create",
            "finance.voucher.update");
    actors.put("session-a", actor(userA, a, permissions));
    actors.put("session-b", actor(UUID.randomUUID(), b, List.of("finance.voucher.read")));
    try {
      upstream = HttpServer.create(new InetSocketAddress(0), 0);
      upstream.createContext(
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
      upstream.setExecutor(Executors.newFixedThreadPool(4));
      upstream.start();
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
    // ⚠️ **连接池压到 1**：只有复用同一条连接，才能真正证明归还后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /**
   * 在可信租户事务里插入一整套凭证的数据（期间 + 凭证头 + 两条分录行 + 审计留痕 + 映射）。
   *
   * <p>⚠️ 直接插 SQL 而不走服务层：这里要证明的是<b>数据层</b>的隔离，
   * 服务层的策略闸门不该混进这个断言。
   */
  void insertVoucher(UUID tenant, int ordinal) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          var periodId = idFor(tenant, 10 + ordinal);
          var voucherId = idFor(tenant, 20 + ordinal);
          var mappingId = idFor(tenant, 30 + ordinal);
          sql.execute(
              "insert into finance_voucher_period(id,tenant_id,period_code,period_start,period_end,"
                  + "status,base_currency,created_at,updated_at)"
                  + " values(?,?,?,'2026-10-01','2026-10-31','OPEN','USD',?::timestamptz,?::timestamptz)",
              periodId,
              tenant,
              "TENANCY-" + ordinal,
              now,
              now);
          // 先建**草稿**再补分录行 —— 已过账的凭证加分录行会被触发器挡掉（那正是本模块要的闸）。
          sql.execute(
              "insert into finance_voucher(id,tenant_id,voucher_no,source_type,period_id,voucher_date,"
                  + "description,currency,status,created_by,created_at,updated_at)"
                  + " values(?,?,?,'MANUAL',?,?::date,'租户隔离夹具','USD','DRAFT',?,"
                  + "?::timestamptz,?::timestamptz)",
              voucherId,
              tenant,
              "VC-TENANCY-" + ordinal,
              periodId,
              LocalDate.of(2026, 10, 20),
              userA,
              now,
              now);
          for (int seq = 1; seq <= 2; seq++) {
            sql.execute(
                "insert into finance_voucher_line(id,tenant_id,voucher_id,line_no,line_type,"
                    + "subject_code,subject_name,summary,amount,currency,auxiliary_type,created_at,"
                    + "updated_at)"
                    + " values(?,?,?,?,?,?,'夹具科目','夹具',100.0000,'USD','NONE',?::timestamptz,"
                    + "?::timestamptz)",
                idFor(tenant, 40 + ordinal * 10 + seq),
                tenant,
                voucherId,
                seq,
                seq == 1 ? "DEBIT" : "CREDIT",
                "100" + seq,
                now,
                now);
          }
          // 再过账（草稿 → 已过账）：过账那一刻触发器会核对期间开放与本位币。
          sql.execute(
              "update finance_voucher set status='POSTED', total_debit=100.0000,"
                  + " total_credit=100.0000, line_count=2, posted_at=?::timestamptz, posted_by=?,"
                  + " version=version+1 where id=?",
              now,
              userA,
              voucherId);
          sql.execute(
              "insert into finance_voucher_event(id,tenant_id,voucher_id,event_type,actor_id,occurred_at)"
                  + " values(?,?,?,'CREATED',?,?::timestamptz)",
              idFor(tenant, 60 + ordinal),
              tenant,
              voucherId,
              userA,
              now);
          sql.execute(
              "insert into finance_voucher_mapping(id,tenant_id,source_type,event_kind,"
                  + "debit_subject_code,debit_subject_name,credit_subject_code,credit_subject_name,"
                  + "created_at,updated_at)"
                  + " values(?,?,'SHIPMENT','SALE','1001','夹具借方','6001','夹具贷方',"
                  + "?::timestamptz,?::timestamptz)",
              mappingId,
              tenant,
              now,
              now);
          return null;
        });
  }

  /** 以指定租户身份读凭证号。 */
  List<String> voucherNosOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select voucher_no from finance_voucher order by voucher_no")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select voucher_no from finance_voucher").size();
  }

  /**
   * 每个用例前清空本模块的表，再种入两租户各一整套。
   *
   * <p><b>清空与播种必须在同一个方法里</b>：写成两个 {@code @BeforeEach} 时，
   * JUnit 5 <b>不保证</b>它们的执行顺序，可能先播种再清空。
   */
  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_voucher_event, finance_voucher_line, finance_voucher,"
              + " finance_voucher_mapping, finance_voucher_period restart identity cascade");
    }
    insertVoucher(a, 1);
    insertVoucher(b, 2);
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 业务表强制rls而不是只启用() {
    for (String table :
        List.of(
            "finance_voucher_period",
            "finance_voucher",
            "finance_voucher_line",
            "finance_voucher_event",
            "finance_voucher_mapping")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V357 必须真的建出 " + table).isNotNull();
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
  // 断言 1 —— 两租户互不可见
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    assertThat(voucherNosOf(a)).containsExactly("VC-TENANCY-1");
    assertThat(voucherNosOf(b)).containsExactly("VC-TENANCY-2");
    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select voucher_no from finance_voucher where id = ?", idFor(b, 22)).size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();
    // 分录行同样隔离。
    var lines =
        tenants.run(a, () -> sql.fetch("select id from finance_voucher_line").size());
    assertThat(lines).as("分录行也必须被隔离").isEqualTo(2);
    // 审计留痕同样隔离。
    var events =
        tenants.run(a, () -> sql.fetch("select id from finance_voucher_event").size());
    assertThat(events).as("审计留痕也必须被隔离").isEqualTo(1);
    // 期间同样隔离。
    var periods =
        tenants.run(a, () -> sql.fetch("select period_code from finance_voucher_period").size());
    assertThat(periods).as("会计期间也必须被隔离").isEqualTo(1);
    // 映射同样隔离。
    var mappings =
        tenants.run(a, () -> sql.fetch("select id from finance_voucher_mapping").size());
    assertThat(mappings).as("业务事件映射也必须被隔离").isEqualTo(1);
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
                    "insert into finance_voucher(id,tenant_id,voucher_no,source_type,period_id,"
                        + "voucher_date,description,currency,status,created_at,updated_at)"
                        + " values(?,?,'VC-FORGED','MANUAL',?,'2026-10-20','伪造','USD','DRAFT',"
                        + "?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    idFor(a, 11),
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
    // 前提：租户 a 名下要有一个**开放**的会计期间。
    var periodId = idFor(a, 11);
    var input =
        new FinanceVoucherInput(
            "MANUAL",
            null,
            "",
            periodId,
            LocalDate.of(2026, 10, 20),
            "伪造头夹具",
            "USD",
            "伪造头夹具",
            null,
            List.of(
                new FinanceVoucherLineInput(
                    "DEBIT", "1001", "借", "夹具", new BigDecimal("10.0000"), "USD",
                    null, "NONE", null, ""),
                new FinanceVoucherLineInput(
                    "CREDIT", "6001", "贷", "夹具", new BigDecimal("10.0000"), "USD",
                    null, "NONE", null, "")));

    // 伪造租户头 + 伪造内部键：请求照样成功，但写入归属必须是**会话租户**。
    http.perform(
            post("/finance/vouchers")
                .header("Authorization", "session-a")
                .header("X-Tenant-Id", b.toString())
                .header("X-Internal-Key", "forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input)))
        .andExpect(status().isOk());

    // 行落在**会话租户**（a）名下，不在自称的租户（b）名下。
    assertThat(voucherNosOf(a)).contains("VC-TENANCY-1");
    assertThat(voucherNosOf(b)).containsExactly("VC-TENANCY-2");
    var landedInA =
        tenants.run(
            a,
            () ->
                sql.fetchOne(
                        "select count(*) from finance_voucher where description = ?", "伪造头夹具")
                    .get(0, Long.class));
    assertThat(landedInA).as("伪造租户头不能改变行的归属").isEqualTo(1);
    var landedInB =
        tenants.run(
            b,
            () ->
                sql.fetchOne(
                        "select count(*) from finance_voucher where description = ?", "伪造头夹具")
                    .get(0, Long.class));
    assertThat(landedInB).as("伪造租户头不能把行写进别的租户").isZero();

    // B 会话 + 伪造 A 头读 A 的凭证 ⇒ 必须 404（按 id 直查也拿不到）。
    http.perform(
            get("/finance/vouchers/" + idFor(a, 21))
                .header("Authorization", "session-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // ⚠️ **每轮都新建实体**：否则测的是幂等，不是隔离。
    for (int round = 0; round < 3; round++) {
      var entityId = UUID.randomUUID();
      var roundNo = round;
      // 每轮换一个 (来源类型, 细分)：映射表按这两个键唯一。
      // ⚠️ 不能用 SHIPMENT/SALE：夹具已经占用了这一组（映射表按 来源类型 + 细分 唯一）。
      var kinds =
          new String[][] {
            {"PAYMENT", "PAYMENT"}, {"PAYMENT", "RECEIPT"}, {"EXPENSE_ACCRUAL", "ACCRUAL"}
          };
      var kind = kinds[round % kinds.length];
      tenants.run(
          a,
          () -> {
            sql.execute(
                "insert into finance_voucher_mapping(id,tenant_id,source_type,event_kind,"
                    + "debit_subject_code,debit_subject_name,credit_subject_code,credit_subject_name,"
                    + "note,created_at,updated_at)"
                    + " values(?,?,?,?,'1001','轮次借方','6001','轮次贷方',?,"
                    + "?::timestamptz,?::timestamptz)",
                entityId,
                a,
                kind[0],
                kind[1],
                "ROUND-" + roundNo,
                OffsetDateTime.now(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC));
            return null;
          });
      // A 写完立刻让 B 读（连接池只有 1 条连接 ⇒ B 拿到的就是 A 刚归还的那条）。
      var seenByB =
          tenants.run(
              b,
              () ->
                  sql.fetchOne(
                          "select count(*) from finance_voucher_mapping where id = ?", entityId)
                      .get(0, Long.class));
      assertThat(seenByB).as("第 " + round + " 轮：B 必须看不见 A 刚写的行").isZero();
      // A 自己仍然看得见（没有把自己写丢）。
      var seenByA =
          tenants.run(
              a,
              () ->
                  sql.fetchOne(
                          "select count(*) from finance_voucher_mapping where id = ?", entityId)
                      .get(0, Long.class));
      assertThat(seenByA).as("第 " + round + " 轮：A 必须看得见自己刚写的行").isEqualTo(1);
      // 事务外读：一行都看不见（没有租户上下文）。
      assertThat(visibleWithoutTenant()).isZero();
    }
    // 两租户的凭证各自只有自己那一条。
    assertThat(voucherNosOf(a)).containsExactly("VC-TENANCY-1");
    assertThat(voucherNosOf(b)).containsExactly("VC-TENANCY-2");
  }
}
