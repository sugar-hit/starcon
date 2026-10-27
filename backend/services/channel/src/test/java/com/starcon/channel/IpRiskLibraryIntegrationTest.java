package com.starcon.channel;

import static com.starcon.channel.IpRiskLibraryModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
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
 * 16.4.1.1 侵权库的集成测试（**真实 PostgreSQL 16**，Testcontainers）。
 *
 * <p>🔴 这组用例守的是本模块最容易做丢的十三件事：
 *
 * <ol>
 *   <li><b>权利类型 / 风险等级由 DB 层 CHECK 钉死</b>：自由文本直接写库会被拒；</li>
 *   <li><b>适用平台是多对多</b>：一条条目登记两个平台 ⇒ 两个平台都能反查到它；</li>
 *   <li><b>匹配特征是多值</b>：多个关键词都存得住，且**可被检索**；</li>
 *   <li><b>登记是幂等的</b>：同一个条目编码登记两次 ⇒ 不产生第二条；</li>
 *   <li><b>批量导入有行级结果</b>：哪一行、哪个字段、为什么；</li>
 *   <li><b>批量导入幂等</b>：同一批重复导入 ⇒ 全部「跳过」，条目数不变；</li>
 *   <li><b>部分失败不回滚已成功的行</b>：一行坏的不影响另一行落库；</li>
 *   <li><b>停用必写理由</b>；停用后内容<b>冻结</b>（服务层 + DB 双拒）；</li>
 *   <li><b>不做物理删除</b>：直接 DELETE 被触发器拒；</li>
 *   <li><b>停用时刻写回不偏 −28800s</b>（timestamptz 往返）；</li>
 *   <li><b>上游读不到就说读不到</b>（available=false + 原因），不是空集合；</li>
 *   <li><b>真分页真筛选</b> + 401 / 403 / 404 / 422。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class IpRiskLibraryIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static IpRiskLibraryUpstreamStub upstream;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\","
          + "\"channel.sync.read\",\"channel.sync.trigger\"],\"scopeMode\":\"ALL\"}]";

  static final List<String> allPermissions =
      java.util.stream.Stream.of("registry", "connection", "shop", "sync", "operation")
          .flatMap(
              p ->
                  java.util.stream.Stream.concat(
                      java.util.stream.Stream.of("read", "create", "update", "delete")
                          .map(op -> "channel." + p + "." + op),
                      p.equals("sync")
                          ? java.util.stream.Stream.of("channel.sync.trigger", "channel.sync.complete")
                          : java.util.stream.Stream.empty()))
          .distinct()
          .toList();

  static AccessService.Access actor(UUID uid, UUID tenantId, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenantId, "Test", UUID.randomUUID(), null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='starcon_app')"
            + " then create role starcon_app nologin; end if; end $$");
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='channel_app')"
            + " then create role channel_app login password 'test-only' nosuperuser nobypassrls; end if; end $$");
        s.execute("alter role channel_app login password 'test-only' nosuperuser nobypassrls");
      }
      upstream = new IpRiskLibraryUpstreamStub();
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            AccessService.Access who = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (who == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = new ObjectMapper().writeValueAsBytes(who);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * 🔴 关掉 identity 这台静态桩与上游桩。
   *
   * <p>它们是<b>静态</b>桩：不起就停 ⇒ 端口被占住整整一轮 JVM 生命周期。
   */
  @AfterAll
  static void stopStubs() {
    if (identity != null) identity.stop(0);
    if (upstream != null) upstream.close();
    pg.stop();
  }



  @BeforeEach
  void freshTenant() {
    tenant = UUID.randomUUID();
    bearer = "Bearer t-" + tenant;
    readOnlyBearer = "Bearer r-" + tenant;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearer, actor(user, tenant, grants));
    actors.put(readOnlyBearer, actor(user, tenant, List.of(grant("ALL", List.of("channel.operation.read")))));
    who = actors.get(bearer);
    reader = actors.get(readOnlyBearer);
    upstream.brandIds.clear();
    upstream.catalogStatus = 0;
  }

  UUID tenant;
  AccessService.Access who;
  AccessService.Access reader;
  String bearer;
  String readOnlyBearer;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("CATALOG_URL", () -> upstream.base());
  }

  @Autowired IpRiskLibraryService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String permission, Supplier<T> work) {
    return access.run(who, permission, work);
  }

  <T> T asReader(Supplier<T> work) {
    return access.run(reader, "channel.operation.read", work);
  }

  static void execAsTenant(UUID tenantId, String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static String scalar(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(statement)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static long countRows(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(statement)) {
      return rs.next() ? rs.getLong(1) : 0L;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * 从 DB 取「现在」（DB 时钟）。
   *
   * <p>🔴 时刻基准必须**同源**：参照时刻也取自 DB，才不会跟 JVM 墙钟跨钟域比较。
   * {@code clock_timestamp()} 是**语句时刻**（DB 时钟），{@code now()} 是**事务开始时刻**。
   */
  static OffsetDateTime dbNow() {
    String raw = scalar("select clock_timestamp()");
    return raw == null ? null : OffsetDateTime.parse(raw.replace(' ', 'T'));
  }

  /**
   * 停用时刻守卫：两端**同钟域**（参照时刻也取自 DB 钟），区间包住「停用动作」本身。
   *
   * <p>🔴 为什么不再用「JVM 墙钟 ± 5 分钟」：那是拿**宿主墙钟**比**DB 写的时刻**，容差只有 5 分钟；
   * 机器一卡（宿主时钟跳变 / 线程停顿十几分钟）就必然假红 —— L2 round 115 打红过 1 例，
   * 实际偏差 14m58s（紧邻的日志是 {@code HikariPool ... clock leap detected (housekeeper delta=15m27s)}）。
   *
   * <p>改成区间后：停顿只会把区间撑宽，**不会**把一个本来正确的时刻挤出区间 ⇒ 不再假红；
   * 而**真偏 8 小时（±28800s）必然落在区间外**，守卫照样当场抓住（见
   * {@code theDisableTimestampGuardStillRejectsTheEightHourShift}）。
   */
  static void assertDisabledAtWithinDbClockWindow(
      OffsetDateTime disabledAt, OffsetDateTime dbBefore, OffsetDateTime dbAfter) {
    assertThat(disabledAt.toInstant())
        .as("停用时刻写回不得偏 8 小时（两端同钟域：参照时刻也取自 DB 钟）")
        .isAfterOrEqualTo(dbBefore.toInstant())
        .isBeforeOrEqualTo(dbAfter.toInstant());
  }

  /** 登记一个条目的便捷方法。 */
  IpRiskLibraryEntry seed(String code, String rightType, String riskLevel, String title) {
    return seed(code, rightType, riskLevel, title, List.of("AMAZON", "EBAY"));
  }

  /** 登记一个条目（**适用平台可指定** —— 多对多的用例要靠它）。 */
  IpRiskLibraryEntry seed(
      String code, String rightType, String riskLevel, String title, List<String> platforms) {
    return as(
            "channel.operation.create",
            () ->
                service.createEntry(
                        new IpRiskLibraryEntryInput(
                            code,
                            rightType,
                            riskLevel,
                            title,
                            "权利人甲",
                            null,
                            "BrandX",
                            "注册号 " + code,
                            "备注",
                            platforms,
                            List.of(
                                new IpRiskLibraryTermInput("brandx", "KEYWORD"),
                                new IpRiskLibraryTermInput("brand x", "PHRASE"))))
                    .entry());
  }

  // ==================================================================
  // 1. 值域由 DB 层钉死
  // ==================================================================

  @Test
  @DisplayName("16.4.1.1 权利类型 / 风险等级 / 生命周期由 DB 层 CHECK 钉死")
  void valueDomainsAreEnforcedByDatabase() {
    // 🔴 自由文本权利类型直接写库 ⇒ CHECK 拒。
    assertThatThrownBy(
            () ->
                execAsTenant(
                    tenant,
                    "insert into ip_risk_entry (id, tenant_id, entry_code, right_type, risk_level,"
                        + " title, created_at, updated_at) values ('"
                        + UUID.randomUUID()
                        + "','"
                        + tenant
                        + "','DB-1','商标','HIGH','x',now(),now())"))
        .hasMessageContaining("ip_risk_entry_right_type_check");

    // 🔴 裸数字风险等级（0..100）直接写库 ⇒ CHECK 拒。
    assertThatThrownBy(
            () ->
                execAsTenant(
                    tenant,
                    "insert into ip_risk_entry (id, tenant_id, entry_code, right_type, risk_level,"
                        + " title, created_at, updated_at) values ('"
                        + UUID.randomUUID()
                        + "','"
                        + tenant
                        + "','DB-2','TRADEMARK','72','x',now(),now())"))
        .hasMessageContaining("ip_risk_entry_risk_level_check");

    // 🔴 非法生命周期 ⇒ CHECK 拒。
    assertThatThrownBy(
            () ->
                execAsTenant(
                    tenant,
                    "insert into ip_risk_entry (id, tenant_id, entry_code, right_type, risk_level,"
                        + " title, status, created_at, updated_at) values ('"
                        + UUID.randomUUID()
                        + "','"
                        + tenant
                        + "','DB-3','TRADEMARK','HIGH','x','DELETED',now(),now())"))
        .hasMessageContaining("ip_risk_entry_status_check");
  }

  // ==================================================================
  // 2. 登记：多平台 + 多特征
  // ==================================================================

  @Test
  @DisplayName("16.4.1.1 登记条目：适用平台是多对多，匹配特征是多值")
  void createEntryStoresPlatformsAndTerms() {
    IpRiskLibraryEntry entry = seed("TM-001", "TRADEMARK", "HIGH", "Nike 商标");

    assertThat(entry.rightType()).isEqualTo("TRADEMARK");
    assertThat(entry.rightTypeLabel()).isEqualTo("品牌商标");
    assertThat(entry.riskLevel()).isEqualTo("HIGH");
    assertThat(entry.riskLevelLabel()).isEqualTo("高风险");
    assertThat(entry.status()).isEqualTo("ACTIVE");
    assertThat(entry.source()).isEqualTo("MANUAL");
    // 🔴 平台是两个（多对多），不是一个单列。
    assertThat(entry.platformCodes()).containsExactlyInAnyOrder("AMAZON", "EBAY");
    // 🔴 匹配特征是两个（多值）。
    assertThat(entry.matchTerms()).containsExactlyInAnyOrder("brandx", "brand x");

    // 🔴 落库断言（不是只看返回值）。
    assertThat(countRows("select count(*) from ip_risk_entry where tenant_id='" + tenant + "'"))
        .isEqualTo(1);
    assertThat(
            countRows(
                "select count(*) from ip_risk_entry_platform where tenant_id='"
                    + tenant
                    + "' and entry_id='"
                    + entry.entryId()
                    + "'"))
        .isEqualTo(2);
    assertThat(
            countRows(
                "select count(*) from ip_risk_match_term where tenant_id='"
                    + tenant
                    + "' and entry_id='"
                    + entry.entryId()
                    + "'"))
        .isEqualTo(2);
  }

  // ==================================================================
  // 3. 幂等
  // ==================================================================

  @Test
  @DisplayName("16.4.1.1 登记是幂等的：同一个条目编码不产生第二条")
  void registrationIsIdempotent() {
    IpRiskLibraryEntry first = seed("TM-IDEM", "TRADEMARK", "HIGH", "第一次登记");
    IpRiskLibraryEntryCreation second =
        as(
            "channel.operation.create",
            () ->
                service.createEntry(
                    new IpRiskLibraryEntryInput(
                        "TM-IDEM",
                        "PATENT",
                        "LOW",
                        "第二次登记（不该覆盖）",
                        "",
                        null,
                        null,
                        null,
                        "",
                        List.of("SHOPIFY"),
                        List.of())));

    assertThat(second.created()).isFalse();
    assertThat(second.entry().entryId()).isEqualTo(first.entryId());
    // 🔴 既有内容**原样保留**（幂等不是覆盖）。
    assertThat(second.entry().title()).isEqualTo("第一次登记");
    assertThat(second.entry().rightType()).isEqualTo("TRADEMARK");
    assertThat(countRows("select count(*) from ip_risk_entry where tenant_id='" + tenant + "'"))
        .isEqualTo(1);
  }

  // ==================================================================
  // 4. 批量导入：行级结果 + 幂等 + 部分失败不回滚
  // ==================================================================

  @Test
  @DisplayName("16.4.1.1 批量导入：行级结果 + 幂等 + 部分失败不回滚已成功的行")
  void batchImportHasRowLevelResultsAndKeepsSuccesses() {
    IpRiskLibraryImportResult result =
        as(
            "channel.operation.create",
            () ->
                service.importEntries(
                    new IpRiskLibraryImportInput(
                        "brand-risk-2026.csv",
                        List.of(
                            new IpRiskLibraryImportRowInput(
                                "IMP-1",
                                "TRADEMARK",
                                "HIGH",
                                "导入的商标",
                                "权利人乙",
                                null,
                                "BrandY",
                                null,
                                "",
                                List.of("AMAZON"),
                                List.of(new IpRiskLibraryTermInput(" brandy", "KEYWORD"))),
                            new IpRiskLibraryImportRowInput(
                                "IMP-2",
                                "商标", // 🔴 自由文本权利类型 ⇒ 这一行失败
                                "HIGH",
                                "坏行",
                                "",
                                null,
                                null,
                                null,
                                "",
                                List.of("AMAZON"),
                                List.of()),
                            new IpRiskLibraryImportRowInput(
                                "IMP-3",
                                "PATENT",
                                "MEDIUM",
                                "导入的专利",
                                "",
                                null,
                                null,
                                null,
                                "",
                                List.of("EBAY"),
                                List.of())))));

    assertThat(result.totalRows()).isEqualTo(3);
    assertThat(result.insertedRows()).isEqualTo(2);
    assertThat(result.failedRows()).isEqualTo(1);
    assertThat(result.skippedRows()).isZero();
    assertThat(result.status()).isEqualTo("PARTIAL");

    // 🔴 逐行结果：哪一行、哪个字段、为什么。
    assertThat(result.rows()).hasSize(3);
    IpRiskLibraryImportRowResult bad = result.rows().get(1);
    assertThat(bad.rowNo()).isEqualTo(2);
    assertThat(bad.outcome()).isEqualTo("FAILED");
    assertThat(bad.errorField()).isEqualTo("rightType");
    assertThat(bad.errorMessage()).contains("TRADEMARK");
    assertThat(bad.entryId()).isNull();
    assertThat(result.rows().get(0).outcome()).isEqualTo("INSERTED");
    assertThat(result.rows().get(2).outcome()).isEqualTo("INSERTED");

    // 🔴 **部分失败不回滚已成功的行**：两条成功的行确实落库了。
    assertThat(countRows("select count(*) from ip_risk_entry where tenant_id='" + tenant + "'"))
        .isEqualTo(2);
    assertThat(countRows("select count(*) from ip_risk_match_term where tenant_id='" + tenant + "'"))
        .isEqualTo(1);
    assertThat(countRows("select count(*) from ip_risk_import_batch where tenant_id='" + tenant + "'"))
        .isEqualTo(1);
    assertThat(countRows("select count(*) from ip_risk_import_row where tenant_id='" + tenant + "'"))
        .isEqualTo(3);

    // 🔴 重复导入同一批 ⇒ 全部「跳过」，条目数不变（幂等）。
    IpRiskLibraryImportResult again =
        as(
            "channel.operation.create",
            () ->
                service.importEntries(
                    new IpRiskLibraryImportInput(
                        "brand-risk-2026.csv",
                        List.of(
                            new IpRiskLibraryImportRowInput(
                                "IMP-1",
                                "TRADEMARK",
                                "HIGH",
                                "导入的商标",
                                "权利人乙",
                                null,
                                "BrandY",
                                null,
                                "",
                                List.of("AMAZON"),
                                List.of(new IpRiskLibraryTermInput(" brandy", "KEYWORD"))),
                            new IpRiskLibraryImportRowInput(
                                "IMP-3",
                                "PATENT",
                                "MEDIUM",
                                "导入的专利",
                                "",
                                null,
                                null,
                                null,
                                "",
                                List.of("EBAY"),
                                List.of())))));
    assertThat(again.insertedRows()).isZero();
    assertThat(again.skippedRows()).isEqualTo(2);
    assertThat(again.failedRows()).isZero();
    assertThat(countRows("select count(*) from ip_risk_entry where tenant_id='" + tenant + "'"))
        .isEqualTo(2);

    // 🔴 行级结果**落库**可回查（不是只在返回值里）。
    IpRiskLibraryImportDetail detail =
        as("channel.operation.read", () -> service.importBatch(result.batchId()));
    assertThat(detail.rows()).hasSize(3);
    assertThat(detail.batch().insertedRows()).isEqualTo(2);
    assertThat(detail.batch().failedRows()).isEqualTo(1);
  }

  @Test
  @DisplayName("16.4.1.1 批量导入：平台目录里没有的平台按行报错，不影响其它行")
  void batchImportRejectsUnknownPlatformPerRow() {
    IpRiskLibraryImportResult result =
        as(
            "channel.operation.create",
            () ->
                service.importEntries(
                    new IpRiskLibraryImportInput(
                        "mixed.csv",
                        List.of(
                            new IpRiskLibraryImportRowInput(
                                "OK-1",
                                "COPYRIGHT",
                                "LOW",
                                "好行",
                                "",
                                null,
                                null,
                                null,
                                "",
                                List.of("SHOPIFY"),
                                List.of()),
                            new IpRiskLibraryImportRowInput(
                                "BAD-1",
                                "COPYRIGHT",
                                "LOW",
                                "平台不存在的行",
                                "",
                                null,
                                null,
                                null,
                                "",
                                List.of("NOT_A_PLATFORM"),
                                List.of())))));
    assertThat(result.insertedRows()).isEqualTo(1);
    assertThat(result.failedRows()).isEqualTo(1);
    assertThat(result.rows().get(1).errorField()).isEqualTo("platforms");
    assertThat(result.rows().get(1).errorMessage()).contains("NOT_A_PLATFORM");
  }

  @Test
  @DisplayName("16.4.1.1 批量导入：行级 DB 错误只回滚那一行，已成功的行留下")
  void batchImportRollsBackOnlyTheFailingRow() {
    // 🔴 这一行的匹配特征超长（> varchar(200)）⇒ **DB 层**报错（不是本地校验）。
    //    它考验的是「逐行 savepoint」：这一行失败**不得**把已成功的行一起带走，
    //    也不得把整笔事务打成 aborted 让后面的行全灭。
    String tooLong = "x".repeat(300);
    IpRiskLibraryImportResult result =
        as(
            "channel.operation.create",
            () ->
                service.importEntries(
                    new IpRiskLibraryImportInput(
                        "db-error.csv",
                        List.of(
                            new IpRiskLibraryImportRowInput(
                                "OK-1",
                                "TRADEMARK",
                                "HIGH",
                                "好行",
                                "",
                                null,
                                null,
                                null,
                                "",
                                List.of("AMAZON"),
                                List.of(new IpRiskLibraryTermInput("ok-term", "KEYWORD"))),
                            new IpRiskLibraryImportRowInput(
                                "BAD-1",
                                "TRADEMARK",
                                "HIGH",
                                "超长特征行",
                                "",
                                null,
                                null,
                                null,
                                "",
                                List.of("AMAZON"),
                                List.of(new IpRiskLibraryTermInput(tooLong, "KEYWORD"))),
                            new IpRiskLibraryImportRowInput(
                                "OK-2",
                                "PATENT",
                                "LOW",
                                "另一条好行",
                                "",
                                null,
                                null,
                                null,
                                "",
                                List.of("EBAY"),
                                List.of())))));

    assertThat(result.insertedRows()).isEqualTo(2);
    assertThat(result.failedRows()).isEqualTo(1);
    assertThat(result.rows().get(1).outcome()).isEqualTo("FAILED");
    assertThat(result.rows().get(1).errorMessage()).isNotBlank();

    // 🔴 已成功的两行**留在库里**（没有被那一行的失败带走）。
    assertThat(countRows("select count(*) from ip_risk_entry where tenant_id='" + tenant + "'"))
        .isEqualTo(2);
    assertThat(countRows("select count(*) from ip_risk_match_term where tenant_id='" + tenant + "'"))
        .isEqualTo(1);
  }

  // ==================================================================
  // 5. 生命周期：停用必写理由 / 冻结 / 不物理删除
  // ==================================================================

  @Test
  @DisplayName("16.4.1.1 停用必写理由；停用后内容冻结；不物理删除")
  void lifecycleRules() {
    IpRiskLibraryEntry entry = seed("TM-LIFE", "TRADEMARK", "HIGH", "生命周期用例");

    // 🔴 停用没写理由 ⇒ 422。
    assertThatThrownBy(
            () ->
                as(
                    "channel.operation.update",
                    () -> service.setStatus(entry.entryId(), new IpRiskLibraryStatusInput("DISABLED", "  "))))
        .hasMessageContaining("理由");

    // 🔴 参照时刻也取自 DB 钟（两端同钟域）：夹住「停用」这一次写入。
    OffsetDateTime dbBefore = dbNow();
    IpRiskLibraryEntryDetail disabled =
        as(
            "channel.operation.update",
            () ->
                service.setStatus(
                    entry.entryId(),
                    new IpRiskLibraryStatusInput("DISABLED", "品牌方已发函，暂缓上架")));
    OffsetDateTime dbAfter = dbNow();
    assertThat(disabled.entry().status()).isEqualTo("DISABLED");
    assertThat(disabled.entry().disableReason()).contains("品牌方已发函");
    assertThat(disabled.entry().disabledAt()).isNotNull();
    // 🔴 timestamptz 往返不偏 −28800s：写回值落在「停用动作前后两个 DB 时刻」之间。
    assertDisabledAtWithinDbClockWindow(disabled.entry().disabledAt(), dbBefore, dbAfter);

    // 🔴 停用后不可改内容（服务层 422）。
    assertThatThrownBy(
            () ->
                as(
                    "channel.operation.update",
                    () ->
                        service.updateEntry(
                            entry.entryId(),
                            new IpRiskLibraryEntryInput(
                                "TM-LIFE", "PATENT", "LOW", "偷偷改", "", null, null, null, "",
                                List.of(), List.of()))))
        .hasMessageContaining("冻结");

    // 🔴 停用后不可改内容（DB 层也拒 —— 直接 UPDATE 被触发器挡）。
    assertThatThrownBy(
            () ->
                execAsTenant(
                    tenant,
                    "update ip_risk_entry set risk_level = 'LOW' where tenant_id='"
                        + tenant
                        + "' and id='"
                        + entry.entryId()
                        + "'"))
        .hasMessageContaining("已停用");

    // 🔴 停用后不可再删改匹配特征（DB 层触发器）。
    assertThatThrownBy(
            () ->
                execAsTenant(
                    tenant,
                    "delete from ip_risk_match_term where tenant_id='"
                        + tenant
                        + "' and entry_id='"
                        + entry.entryId()
                        + "'"))
        .hasMessageContaining("已停用");

    // 🔴 不做物理删除。
    assertThatThrownBy(
            () ->
                execAsTenant(
                    tenant,
                    "delete from ip_risk_entry where tenant_id='"
                        + tenant
                        + "' and id='"
                        + entry.entryId()
                        + "'"))
        .hasMessageContaining("不可物理删除");

    // 🔴 重新启用：历史（停用理由与时刻）**保留**。
    IpRiskLibraryEntryDetail reactivated =
        as(
            "channel.operation.update",
            () ->
                service.setStatus(
                    entry.entryId(), new IpRiskLibraryStatusInput("ACTIVE", "复核后恢复")));
    assertThat(reactivated.entry().status()).isEqualTo("ACTIVE");
    assertThat(reactivated.entry().disableReason()).contains("品牌方已发函");
    assertThat(reactivated.entry().disabledAt()).isNotNull();
  }

  @Test
  @DisplayName("16.4.1.1 停用时刻守卫仍能当场抓住真偏 8 小时（守卫自证：没被放松）")
  void theDisableTimestampGuardStillRejectsTheEightHourShift() {
    // 基准时刻取自 DB 钟，守卫的判定里没有任何真实时钟依赖 ⇒ 这条用例自己不会假红。
    OffsetDateTime dbBefore = dbNow();
    OffsetDateTime dbAfter = dbNow();
    OffsetDateTime real = dbAfter;

    // 正常的写回值必须通过守卫。
    assertThatCode(() -> assertDisabledAtWithinDbClockWindow(real, dbBefore, dbAfter))
        .doesNotThrowAnyException();

    // 🔴 真偏 8 小时（两个方向）必须当场被拒 —— 守卫没被放松。
    assertThatThrownBy(
            () -> assertDisabledAtWithinDbClockWindow(real.minusHours(8), dbBefore, dbAfter))
        .isInstanceOf(AssertionError.class);
    assertThatThrownBy(
            () -> assertDisabledAtWithinDbClockWindow(real.plusHours(8), dbBefore, dbAfter))
        .isInstanceOf(AssertionError.class);
  }

  // ==================================================================
  // 6. 平台反查 + 筛选 + 分页
  // ==================================================================

  @Test
  @DisplayName("16.4.1.1 平台反查：这个平台上有哪些高风险条目")
  void platformFilterAndSummary() {
    seed("P-1", "TRADEMARK", "HIGH", "Amazon 高风险");
    seed("P-2", "PATENT", "LOW", "Amazon 低风险");
    seed("P-3", "COPYRIGHT", "MEDIUM", "只有 Shopify 的条目", List.of("SHOPIFY"));

    IpRiskLibraryEntryPage amazonHigh =
        as(
            "channel.operation.read",
            () -> service.entries(null, "HIGH", null, "AMAZON", null, 0, 20));
    assertThat(amazonHigh.total()).isEqualTo(1);
    assertThat(amazonHigh.items()).extracting(IpRiskLibraryEntry::entryCode).containsExactly("P-1");
    assertThat(amazonHigh.riskLevelCounts().get("HIGH")).isEqualTo(1);

    IpRiskLibraryEntryPage amazonAll =
        as("channel.operation.read", () -> service.entries(null, null, null, "AMAZON", null, 0, 20));
    assertThat(amazonAll.total()).isEqualTo(2);
    assertThat(amazonAll.rightTypeCounts().get("PATENT")).isEqualTo(1);

    IpRiskLibraryPlatformSummaryPage summary =
        as("channel.operation.read", () -> service.platformSummary());
    IpRiskLibraryPlatformSummary amazon =
        summary.items().stream()
            .filter(s -> s.platformCode().equals("AMAZON"))
            .findFirst()
            .orElseThrow();
    assertThat(amazon.high()).isEqualTo(1);
    assertThat(amazon.low()).isEqualTo(1);
    assertThat(amazon.medium()).isZero();
    assertThat(amazon.total()).isEqualTo(2);

    IpRiskLibraryPlatformSummary shopify =
        summary.items().stream()
            .filter(s -> s.platformCode().equals("SHOPIFY"))
            .findFirst()
            .orElseThrow();
    assertThat(shopify.medium()).isEqualTo(1);
    assertThat(shopify.high()).isZero();
  }

  @Test
  @DisplayName("16.4.1.1 匹配特征可被检索（关键词 / 短语都能命中）")
  void matchTermsAreSearchable() {
    seed("S-1", "TRADEMARK", "HIGH", "可检索的条目");

    IpRiskLibraryEntryPage byTerm =
        as("channel.operation.read", () -> service.entries(null, null, null, null, "brandx", 0, 20));
    assertThat(byTerm.total()).isEqualTo(1);
    assertThat(byTerm.items()).extracting(IpRiskLibraryEntry::entryCode).containsExactly("S-1");

    IpRiskLibraryEntryPage byPhrase =
        as("channel.operation.read", () -> service.entries(null, null, null, null, "brand x", 0, 20));
    assertThat(byPhrase.total()).isEqualTo(1);
  }

  @Test
  @DisplayName("16.4.1.1 真分页：第 2 页与第 1 页不重叠，total 是全量")
  void paginationIsReal() {
    for (int i = 0; i < 5; i++) {
      seed("PAGE-" + i, "TRADEMARK", "HIGH", "分页条目 " + i);
    }
    IpRiskLibraryEntryPage first = as("channel.operation.read", () -> service.entries(null, null, null, null, null, 0, 2));
    IpRiskLibraryEntryPage second = as("channel.operation.read", () -> service.entries(null, null, null, null, null, 1, 2));
    assertThat(first.total()).isEqualTo(5);
    assertThat(second.total()).isEqualTo(5);
    assertThat(first.items()).hasSize(2);
    assertThat(second.items()).hasSize(2);
    assertThat(first.items()).extracting(IpRiskLibraryEntry::entryId)
        .doesNotContainAnyElementsOf(second.items().stream().map(IpRiskLibraryEntry::entryId).toList());
  }

  // ==================================================================
  // 7. 上游：读不到就说读不到
  // ==================================================================

  @Test
  @DisplayName("16.4.1.1 上游读不到时显式说读不到，不是空集合")
  void upstreamUnavailableIsReportedHonestly() {
    upstream.putBrand("BrandZ");
    IpRiskLibraryBrandSet ok = as("channel.operation.read", () -> service.brands(null, bearer));
    assertThat(ok.available()).isTrue();
    assertThat(ok.items()).extracting(IpRiskLibraryBrandCandidate::name).contains("BrandZ");

    upstream.catalogStatus = 500;
    IpRiskLibraryBrandSet broken = as("channel.operation.read", () -> service.brands(null, bearer));
    // 🔴 available=false + 人话原因，**不是**「上游一个品牌都没有」。
    assertThat(broken.available()).isFalse();
    assertThat(broken.note()).isNotBlank();
    assertThat(broken.items()).isEmpty();
  }

  // ==================================================================
  // 8. HTTP 层：401 / 403 / 404 / 422
  // ==================================================================

  @Test
  @DisplayName("16.4.1.1 HTTP 层：401 / 403 / 404 / 422 都要对")
  void httpStatusCodes() throws Exception {
    // 无会话 ⇒ 401。
    http.perform(get("/channel/ip-risk-library/entries")).andExpect(status().isUnauthorized());

    // 只有读权限 ⇒ 写端点 403。
    http.perform(
            post("/channel/ip-risk-library/entries")
                .header("Authorization", readOnlyBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(
                        new IpRiskLibraryEntryInput(
                            "X-1", "TRADEMARK", "HIGH", "标题", "", null, null, null, "",
                            List.of(), List.of()))))
        .andExpect(status().isForbidden());

    // 不存在的条目 ⇒ 404。
    http.perform(
            get("/channel/ip-risk-library/entries/" + UUID.randomUUID())
                .header("Authorization", bearer))
        .andExpect(status().isNotFound());

    // 非法权利类型 ⇒ 422。
    http.perform(
            post("/channel/ip-risk-library/entries")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(
                        new IpRiskLibraryEntryInput(
                            "X-2", "商标", "HIGH", "标题", "", null, null, null, "",
                            List.of(), List.of()))))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  @DisplayName("16.4.1.1 能力声明如实标出未交付的下游")
  void capabilitiesReportTheGap() {
    IpRiskLibraryCapabilities capabilities = as("channel.operation.read", () -> service.capabilities());
    IpRiskLibraryCapabilityGap detection =
        capabilities.gaps().stream()
            .filter(g -> g.moduleId().equals("16.4.1.2"))
            .findFirst()
            .orElseThrow();
    assertThat(detection.available()).isFalse();
    assertThat(detection.ownerModule()).contains("16.4.1.2");
    assertThat(detection.impact()).contains("未交付");
    assertThat(capabilities.rightTypes()).containsExactly("TRADEMARK", "PATENT", "COPYRIGHT", "IMAGE_RIGHT");
    assertThat(capabilities.riskLevels()).containsExactly("HIGH", "MEDIUM", "LOW");
  }
}
