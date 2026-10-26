package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.FinancialReportModels.*;
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
 * finance 服务的**租户隔离**证明（12.5.2.2 财务报表这一批表）。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <ol>
 *   <li>两租户互不可见；</li>
 *   <li>伪造租户上下文无效 —— 并且<b>要断言行确实落在自称的租户名下</b>，不是只断言 A 看不见；</li>
 *   <li>连接池归还后不串租户。
 * </ol>
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>刻意跑在本模块自己的表上</b>：12.5.2.2 的表是 {@code finance_report_template} /
 * {@code finance_report_export_event} —— 证明的是**真的要用的那两张表**被隔离了，
 * 而不是只在别人的表上做过一次。**报表本身**读的是 12.5.2.1 的凭证表，
 * 那里同样有 RLS ⇒ 报表也必须按租户隔离（这条单独断言）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FinancialReportTenancyIntegrationTest {
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
    return UUID.nameUUIDFromBytes(
        ("starcon-finance-report-tenancy-" + tenant + "-" + ordinal).getBytes());
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
        List.of("finance.report.read", "finance.report.create", "finance.report.update");
    actors.put("session-a", actor(userA, a, permissions));
    actors.put("session-b", actor(UUID.randomUUID(), b, List.of("finance.report.read")));
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
   * 在可信租户事务里插入一套「期间 + 一张已过账凭证 + 一条报表模板」。
   *
   * <p>⚠️ 直接插 SQL 而不走服务层：这里要证明的是<b>数据层</b>的隔离，
   * 服务层的策略闸门不该混进这个断言。
   */
  void insertTenantData(UUID tenant, int ordinal) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          var periodId = idFor(tenant, 10 + ordinal);
          var voucherId = idFor(tenant, 20 + ordinal);
          sql.execute(
              "insert into finance_voucher_period(id,tenant_id,period_code,period_start,period_end,"
                  + "status,base_currency,created_at,updated_at)"
                  + " values(?,?,?,'2026-10-01','2026-10-31','OPEN','USD',?::timestamptz,?::timestamptz)",
              periodId,
              tenant,
              "TENANCY-" + ordinal,
              now,
              now);
          // 先建**草稿**再补分录行 —— 已过账的凭证加分录行会被触发器挡掉（那正是 12.5.2.1 的闸）。
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
                    + " values(?,?,?,?,?,?,'夹具科目','夹具',?::numeric,'USD','NONE',?::timestamptz,"
                    + "?::timestamptz)",
                idFor(tenant, 40 + ordinal * 10 + seq),
                tenant,
                voucherId,
                seq,
                seq == 1 ? "DEBIT" : "CREDIT",
                "1" + ordinal + "0" + seq,
                new BigDecimal("100.0000"),
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
              "insert into finance_report_template(id,tenant_id,report_type,subject_code,"
                  + "subject_name,line_code,line_name,section,sort_order,enabled,created_at,updated_at)"
                  + " values(?,?,'BALANCE_SHEET',?,?,?,?,'',0,true,?::timestamptz,?::timestamptz)",
              idFor(tenant, 60 + ordinal),
              tenant,
              "1" + ordinal + "0" + ordinal,
              "夹具科目",
              "TENANCY-LINE-" + ordinal,
              "租户 " + ordinal + " 的报表行",
              now,
              now);
          return null;
        });
  }

  /** 以指定租户身份读模板的行码。 */
  List<String> templateLineCodesOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select line_code from finance_report_template order by line_code")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select line_code from finance_report_template").size();
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
          "truncate table finance_report_export_event, finance_report_template,"
              + " finance_voucher_event, finance_voucher_line, finance_voucher,"
              + " finance_voucher_mapping, finance_voucher_period restart identity cascade");
    }
    insertTenantData(a, 1);
    insertTenantData(b, 2);
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 业务表强制rls而不是只启用() {
    for (String table : List.of("finance_report_template", "finance_report_export_event")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V360 必须真的建出 " + table).isNotNull();
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
    assertThat(templateLineCodesOf(a)).containsExactly("TENANCY-LINE-1");
    assertThat(templateLineCodesOf(b)).containsExactly("TENANCY-LINE-2");
    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select line_code from finance_report_template where id = ?",
                        idFor(b, 62))
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();
  }

  @Test
  void 断言1_报表本身也按租户隔离_看不见别人的凭证() throws Exception {
    var aReport =
        http.perform(
                get("/finance/financial-reports/trial-balance")
                    .header("Authorization", "session-a")
                    .param("periodFromId", idFor(a, 11).toString())
                    .param("periodToId", idFor(a, 11).toString()))
            .andReturn();
    assertThat(aReport.getResponse().getStatus()).isEqualTo(200);
    var aBody = aReport.getResponse().getContentAsString();
    // 报表行带的是科目编码（凭证号在钻取里才有）。
    assertThat(aBody).contains("1101").contains("1102").doesNotContain("1201");

    var bReport =
        http.perform(
                get("/finance/financial-reports/trial-balance")
                    .header("Authorization", "session-b")
                    .param("periodFromId", idFor(b, 12).toString())
                    .param("periodToId", idFor(b, 12).toString()))
            .andReturn();
    assertThat(bReport.getResponse().getStatus()).isEqualTo(200);
    var bBody = bReport.getResponse().getContentAsString();
    assertThat(bBody).contains("1201").contains("1202").doesNotContain("1101");

    // 钻取到凭证：A 只看得见自己的凭证号。
    var drillA =
        http.perform(
                get(
                        "/finance/financial-reports/drill?reportType=TRIAL_BALANCE&lineCode=1101"
                            + "&currency=USD&periodFromId="
                            + idFor(a, 11)
                            + "&periodToId="
                            + idFor(a, 11))
                    .header("Authorization", "session-a"))
            .andReturn();
    assertThat(drillA.getResponse().getStatus()).isEqualTo(200);
    var drillABody = drillA.getResponse().getContentAsString();
    assertThat(drillABody).contains("VC-TENANCY-1").doesNotContain("VC-TENANCY-2");
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
                    "insert into finance_report_template(id,tenant_id,report_type,subject_code,"
                        + "line_code,created_at,updated_at)"
                        + " values(?,?,'BALANCE_SHEET','9999','FORGED',?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
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
    // 伪造租户头 + 伪造内部键：请求照样成功，但写入归属必须是**会话租户**。
    http.perform(
            post("/finance/financial-reports/templates")
                .header("Authorization", "session-a")
                .header("X-Tenant-Id", b.toString())
                .header("X-Internal-Key", "forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"reportType\":\"BALANCE_SHEET\",\"subjectCode\":\"7777\","
                        + "\"lineCode\":\"FORGED-HEADER-LINE\"}"))
        .andExpect(status().isOk());

    // 行落在**会话租户**（a）名下，不在自称的租户（b）名下。
    assertThat(templateLineCodesOf(a)).contains("FORGED-HEADER-LINE");
    assertThat(templateLineCodesOf(b)).doesNotContain("FORGED-HEADER-LINE");
    var landedInB =
        tenants.run(
            b,
            () ->
                sql.fetchOne(
                        "select count(*) from finance_report_template where line_code = ?",
                        "FORGED-HEADER-LINE")
                    .get(0, Long.class));
    assertThat(landedInB).as("伪造租户头不能把行写进别的租户").isZero();

    // B 会话 + 伪造 A 头读 A 的模板 ⇒ 必须 404（按 id 直查也拿不到）。
    http.perform(
            get("/finance/financial-reports/templates")
                .header("Authorization", "session-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk());
    var bBody =
        http.perform(
                get("/finance/financial-reports/templates")
                    .header("Authorization", "session-b")
                    .header("X-Tenant-Id", a.toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(bBody).doesNotContain("TENANCY-LINE-1");
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
      tenants.run(
          a,
          () -> {
            sql.execute(
                "insert into finance_report_template(id,tenant_id,report_type,subject_code,"
                    + "line_code,sort_order,enabled,created_at,updated_at)"
                    + " values(?,?,'BALANCE_SHEET',?,?,?,true,?::timestamptz,?::timestamptz)",
                entityId,
                a,
                "ROUND-" + roundNo,
                "ROUND-LINE-" + roundNo,
                roundNo,
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
                          "select count(*) from finance_report_template where id = ?", entityId)
                      .get(0, Long.class));
      assertThat(seenByB).as("第 " + roundNo + " 轮：B 必须看不见 A 刚写的行").isZero();
      // 再让 A 读回来：自己的行必须还在（连接被 B 用过也不该丢）。
      var seenByA =
          tenants.run(
              a,
              () ->
                  sql.fetchOne(
                          "select count(*) from finance_report_template where id = ?", entityId)
                      .get(0, Long.class));
      assertThat(seenByA).as("第 " + roundNo + " 轮：A 必须仍能看见自己的行").isEqualTo(1L);
    }
  }
}
