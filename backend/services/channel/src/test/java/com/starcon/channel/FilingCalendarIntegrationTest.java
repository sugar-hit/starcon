package com.starcon.channel;

import static com.starcon.channel.FilingCalendarModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 16.3.1.2 申报日历的集成测试（**真实 PostgreSQL 16**）。
 *
 * <p>🔴 这组用例守的是本模块最容易做丢的十三件事：
 *
 * <ol>
 *   <li><b>申报期与截止日是真算出来的</b>（按生效起始日 + 期起始日 + 周期），不是常量；</li>
 *   <li><b>timestamptz 往返断言写回的值</b>（不是只断言没抛异常）；</li>
 *   <li><b>辖区码值域钉死</b>：自由文本辖区码在服务层与 DB 层各拒一次；</li>
 *   <li><b>「已提交」必须带依据</b>（谁 / 何时 / 凭据号）—— DB 触发器钉死；</li>
 *   <li><b>状态单调</b>：倒退在服务层与 DB 层各拒一次；</li>
 *   <li><b>「已确认」必须先「已提交」</b>；</li>
 *   <li><b>数据就绪是投影</b>：finance 有生效底稿 ⇒ 就绪；没有 ⇒ 不就绪；</li>
 *   <li><b>「读不到」与「没就绪」是两个状态</b>：finance 挂掉 ⇒ available=false，<b>不是</b>就绪=false；</li>
 *   <li><b>提醒真的派生出来</b>（提前 + 截止两档），分页截断<b>如实报总数</b>；</li>
 *   <li><b>责任人必须是本租户成员</b>；成员目录读不到 ⇒ 拒绝落库（不存查无此人的 id）；</li>
 *   <li><b>所需数据 JSONB 写回读回一致</b>；</li>
 *   <li><b>逾期判定按服务端时间</b>（客户端时间不影响）；</li>
 *   <li><b>HTTP 层真分页真筛选</b>。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class FilingCalendarIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer finance;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  /** 成员目录里���一个成员（identity 拥有；本模块只存它的逻辑 ID）。 */
  static final UUID MEMBER = UUID.fromString("22222222-2222-2222-2222-222222222201");

  /** 模拟 identity 的成员目录是否可读（false ⇒ 403，模拟「读不到」）。 */
  static volatile boolean membersReadable = true;

  /** 模拟 finance 的申报底稿是否可读（true ⇒ 503，模拟「读不到」）。 */
  static volatile boolean financeDown = false;

  /** 模拟 finance 返回的生效底稿。 */
  static final List<Map<String, Object>> financeDrafts = new ArrayList<>();

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
        uid, tenantId, "Test", MEMBER, null,
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
      identity.createContext(
          "/identity/members",
          e -> {
            if (!membersReadable) {
              e.sendResponseHeaders(403, -1);
              e.close();
              return;
            }
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("id", MEMBER.toString());
            member.put("username", "合规负责人");
            member.put("status", "ACTIVE");
            byte[] body = new ObjectMapper().writeValueAsBytes(List.of(member));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      identity.start();

      finance = HttpServer.create(new InetSocketAddress(0), 0);
      finance.createContext(
          "/finance/filing-draft/drafts",
          e -> {
            if (financeDown) {
              e.sendResponseHeaders(503, -1);
              e.close();
              return;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", financeDrafts);
            body.put("page", 0);
            body.put("size", 200);
            body.put("total", financeDrafts.size());
            byte[] bytes = new ObjectMapper().writeValueAsBytes(body);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, bytes.length);
            e.getResponseBody().write(bytes);
            e.close();
          });
      finance.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @AfterAll
  static void stopStubs() {
    if (identity != null) identity.stop(0);
    if (finance != null) finance.stop(0);
  }

  @BeforeEach
  void freshTenant() {
    tenant = UUID.randomUUID();
    bearer = "Bearer t-" + tenant;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearer, actor(user, tenant, grants));
    who = actors.get(bearer);
    membersReadable = true;
    financeDown = false;
    financeDrafts.clear();
  }

  UUID tenant;
  AccessService.Access who;
  String bearer;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("FINANCE_URL", () -> "http://localhost:" + finance.getAddress().getPort());
  }

  @Autowired FilingCalendarService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String permission, Supplier<T> work) {
    return access.run(who, permission, work);
  }

  void execAsTenant(UUID tenantId, String statement) {
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

  /** 以应用角色 channel_app（非超级用户）按当前租户直查 —— 超级用户绕过 RLS 会让断言永远「通过」。 */
  String scalarAsTenant(UUID tenantId, String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(statement)) {
        return rs.next() ? rs.getString(1) : null;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 登记一份周期配置（按季、德国、期起始日 1 号、期末后 10 天当地时间 23 点截止）。 */
  FilingCalendarCycleConfigView config(
      String jurisdiction, String taxType, String cycle, int anchorDay, int offsetDays,
      String zone, int leadDays, String effectiveFrom) {
    return as(
        "channel.operation.create",
        () ->
            service.createCycleConfig(
                new FilingCalendarCycleConfigInput(
                    jurisdiction, taxType, cycle, anchorDay, offsetDays, 23, zone, leadDays,
                    effectiveFrom, "ACTIVE", "集成测试配置")));
  }

  /** 生成一批日历条目（按周期配置真算申报期与截止日）。 */
  FilingCalendarGenerateResult generate(String referenceDate) {
    return as(
        "channel.operation.create",
        () -> service.generateEntries(referenceDate, who, bearer));
  }

  FilingCalendarEntryView entry(String id) {
    return as("channel.operation.read", () -> service.entry(UUID.fromString(id), bearer));
  }

  // ==================================================================
  // 1. 辖区码值域钉死
  // ==================================================================

  @Test
  @DisplayName("16.3.1.2 自由文本辖区码在服务层被拒（辖区不是自由文本）")
  void freeTextJurisdictionRejected() {
    assertThatThrownBy(
            () ->
                config("XX-NOT-A-JURISDICTION", "VAT", "MONTHLY", 1, 10, "UTC", 7, "2026-01-01"))
        .hasMessageContaining("受控辖区码表");
  }

  @Test
  @DisplayName("16.3.1.2 自由文本辖区码在 DB 层也被拒（触发器钉死，不靠应用层自觉）")
  void freeTextJurisdictionRejectedByDatabase() {
    Throwable thrown =
        catchThrowable(
            () ->
                execAsTenant(
                    tenant,
                    "insert into filing_calendar_entry (id,tenant_id,jurisdiction_code,tax_type,"
                        + " filing_cycle,period_start,period_end,due_at,status,status_advanced_at,"
                        + " required_data,created_at,updated_at) values ('"
                        + UUID.randomUUID()
                        + "','"
                        + tenant
                        + "','XX-NOPE','VAT','MONTHLY','2026-01-01','2026-01-31',"
                        + "'2026-02-10T23:00:00Z','NOT_STARTED',now(),'[]',now(),now())"));
    assertThat(thrown).isNotNull();
    assertThat(rootMessage(thrown)).contains("not a known jurisdiction code");
  }

  @Test
  @DisplayName("16.3.1.2 申报线（���种）与周期是受控词表")
  void taxTypeAndCycleAreControlled() {
    assertThatThrownBy(
            () -> config("EU-DE", "WITHHOLDING", "MONTHLY", 1, 10, "UTC", 7, "2026-01-01"))
        .hasMessageContaining("申报线");
    assertThatThrownBy(() -> config("EU-DE", "VAT", "DAILY", 1, 10, "UTC", 7, "2026-01-01"))
        .hasMessageContaining("申报周期");
  }

  // ==================================================================
  // 2. 申报期与截止日是真算出来的
  // ==================================================================

  @Test
  @DisplayName("16.3.1.2 按周期配置生成申报期：期与截止日都是算出来的")
  void generateComputesPeriodAndDueDate() {
    config("EU-DE", "VAT", "QUARTERLY", 1, 10, "Europe/Berlin", 7, "2026-01-01");
    FilingCalendarGenerateResult result = generate("2026-05-15");

    assertThat(result.created()).isEqualTo(1);
    assertThat(result.skipped()).isZero();
    FilingCalendarEntryView view = result.items().get(0);
    assertThat(view.periodStart()).isEqualTo("2026-04-01");
    assertThat(view.periodEnd()).isEqualTo("2026-06-30");
    // 期末 + 10 天 = 2026-07-10，当地 23 点（Berlin 夏季是 +02:00）
    assertThat(view.dueAt()).isEqualTo("2026-07-10T23:00+02:00");
    assertThat(view.status()).isEqualTo("NOT_STARTED");
    assertThat(view.jurisdictionName()).isEqualTo("德国");
  }

  @Test
  @DisplayName("16.3.1.2 重复生成不插第二条（这一期已经有一条了）")
  void generateIsIdempotent() {
    config("EU-DE", "VAT", "QUARTERLY", 1, 10, "Europe/Berlin", 7, "2026-01-01");
    assertThat(generate("2026-05-15").created()).isEqualTo(1);
    FilingCalendarGenerateResult second = generate("2026-05-15");
    assertThat(second.created()).isZero();
    assertThat(second.skipped()).isEqualTo(1);
    assertThat(
            scalarAsTenant(
                tenant, "select count(*) from filing_calendar_entry where tenant_id='" + tenant + "'"))
        .isEqualTo("1");
  }

  @Test
  @DisplayName("16.3.1.2 截止日写回库里的值**逐字相等**（timestamptz 不许偏 −28800s）")
  void dueDateRoundTripsExactly() {
    config("EU-DE", "VAT", "MONTHLY", 1, 10, "Asia/Shanghai", 7, "2026-01-01");
    FilingCalendarEntryView view = generate("2026-03-10").items().get(0);
    assertThat(view.periodStart()).isEqualTo("2026-03-01");
    assertThat(view.periodEnd()).isEqualTo("2026-03-31");
    assertThat(view.dueAt()).isEqualTo("2026-04-10T23:00+08:00");

    String stored =
        scalarAsTenant(
            tenant,
            "select to_char(due_at at time zone 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI')"
                + " from filing_calendar_entry where tenant_id='" + tenant + "'");
    assertThat(stored).as("库里存的就是当地 2026-04-10 23:00").isEqualTo("2026-04-10 23:00");

    String epoch =
        scalarAsTenant(
            tenant,
            "select extract(epoch from due_at)::bigint from filing_calendar_entry where tenant_id='"
                + tenant + "'");
    assertThat(epoch)
        .as("瞬时也要对：2026-04-10T23:00+08:00 = 2026-04-10T15:00Z")
        .isEqualTo(String.valueOf(LocalDate.of(2026, 4, 10).atTime(15, 0)
            .toInstant(ZoneOffset.UTC).getEpochSecond()));
  }

  // ==================================================================
  // 3. 状态：受控 + 单调 + 依据
  // ==================================================================

  @Test
  @DisplayName("16.3.1.2 推进到「已提交」必须带凭据号（没有依据就推不到已提交）")
  void submitRequiresEvidence() {
    config("EU-DE", "VAT", "MONTHLY", 1, 10, "UTC", 7, "2026-01-01");
    String id = generate("2026-03-10").items().get(0).id();

    assertThatThrownBy(
            () ->
                as(
                    "channel.operation.update",
                    () ->
                        service.advance(
                            UUID.fromString(id),
                            new FilingCalendarAdvanceInput("SUBMITTED", "", null),
                            who,
                            bearer)))
        .hasMessageContaining("凭据号");
  }

  @Test
  @DisplayName("16.3.1.2 「已提交」的依据真的落库（谁 / 何时 / 凭据号）")
  void submitEvidenceIsPersisted() {
    config("EU-DE", "VAT", "MONTHLY", 1, 10, "UTC", 7, "2026-01-01");
    String id = generate("2026-03-10").items().get(0).id();
    FilingCalendarEntryView view =
        as(
            "channel.operation.update",
            () ->
                service.advance(
                    UUID.fromString(id),
                    new FilingCalendarAdvanceInput("SUBMITTED", "DE-2026-03-4711", "已向德国税务局提交"),
                    who,
                    bearer));

    assertThat(view.status()).isEqualTo("SUBMITTED");
    assertThat(view.submissionReference()).isEqualTo("DE-2026-03-4711");
    assertThat(view.submittedBy()).isEqualTo(MEMBER.toString());
    assertThat(view.submittedAt()).isNotEmpty();

    assertThat(
            scalarAsTenant(
                tenant,
                "select submission_reference from filing_calendar_entry where tenant_id='"
                    + tenant + "'"))
        .isEqualTo("DE-2026-03-4711");
    assertThat(
            scalarAsTenant(
                tenant,
                "select submitted_by from filing_calendar_entry where tenant_id='" + tenant + "'"))
        .isEqualTo(MEMBER.toString());
  }

  @Test
  @DisplayName("16.3.1.2 状态倒退被拒（服务层 422）")
  void statusCannotGoBackwards() {
    config("EU-DE", "VAT", "MONTHLY", 1, 10, "UTC", 7, "2026-01-01");
    String id = generate("2026-03-10").items().get(0).id();
    as(
        "channel.operation.update",
        () ->
            service.advance(
                UUID.fromString(id),
                new FilingCalendarAdvanceInput("SUBMITTED", "REF-1", null),
                who,
                bearer));

    assertThatThrownBy(
            () ->
                as(
                    "channel.operation.update",
                    () ->
                        service.advance(
                            UUID.fromString(id),
                            new FilingCalendarAdvanceInput("DATA_READY", null, null),
                            who,
                            bearer)))
        .hasMessageContaining("不能倒退");
  }

  @Test
  @DisplayName("16.3.1.2 状态倒退在 DB 层也被拒（触发器钉死，不靠应用层自觉）")
  void statusCannotGoBackwardsInDatabase() {
    config("EU-DE", "VAT", "MONTHLY", 1, 10, "UTC", 7, "2026-01-01");
    String id = generate("2026-03-10").items().get(0).id();
    // 先推进到「已提交」，才有「倒退」可言
    as(
        "channel.operation.update",
        () ->
            service.advance(
                UUID.fromString(id),
                new FilingCalendarAdvanceInput("SUBMITTED", "REF-DB", null),
                who,
                bearer));
    Throwable thrown =
        catchThrowable(
            () ->
                execAsTenant(
                    tenant,
                    "update filing_calendar_entry set status='NOT_STARTED' where tenant_id='"
                        + tenant + "'"));
    assertThat(thrown).isNotNull();
    assertThat(rootMessage(thrown)).contains("cannot move backwards");
  }

  @Test
  @DisplayName("16.3.1.2 标「已确认」必须先「已提交」")
  void confirmRequiresSubmitted() {
    config("EU-DE", "VAT", "MONTHLY", 1, 10, "UTC", 7, "2026-01-01");
    String id = generate("2026-03-10").items().get(0).id();
    assertThatThrownBy(
            () ->
                as(
                    "channel.operation.update",
                    () ->
                        service.advance(
                            UUID.fromString(id),
                            new FilingCalendarAdvanceInput("CONFIRMED", "C-1", null),
                            who,
                            bearer)))
        .hasMessageContaining("已提交");
  }

  // ==================================================================
  // 4. 数据就绪 = 12.6.1.2 的生效底稿（投影，不是抄一份）
  // ==================================================================

  @Test
  @DisplayName("16.3.1.2 有生效底稿 ⇒ 数据就绪（并自动把状态推到「数据就绪」）")
  void readinessFollowsLiveDraft() {
    config("EU-DE", "VAT", "MONTHLY", 1, 10, "UTC", 7, "2026-01-01");
    String id = generate("2026-03-10").items().get(0).id();
    UUID draftId = UUID.randomUUID();
    financeDrafts.add(
        Map.of(
            "id", draftId.toString(),
            "jurisdictionCode", "EU-DE",
            "taxType", "VAT",
            "periodStart", "2026-03-01",
            "periodEnd", "2026-03-31",
            "filingCycle", "MONTHLY",
            "status", "GENERATED"));

    int refreshed = as("channel.operation.update", () -> service.refreshReadiness(bearer));
    assertThat(refreshed).isEqualTo(1);

    FilingCalendarEntryView view = entry(id);
    assertThat(view.dataReady()).isTrue();
    assertThat(view.dataReadyDraftId()).isEqualTo(draftId.toString());
    assertThat(view.dataReadinessAvailable()).isTrue();
    assertThat(view.status()).as("数据就绪是投影 ⇒ 状态自动前进").isEqualTo("DATA_READY");
    assertThat(
            scalarAsTenant(
                tenant, "select data_ready_draft_id from filing_calendar_entry where tenant_id='"
                    + tenant + "'"))
        .isEqualTo(draftId.toString());
  }

  @Test
  @DisplayName("16.3.1.2 没有生效底稿 ⇒ 不就绪（但**读到了**，不是「读不到」）")
  void readinessWithoutDraft() {
    config("EU-DE", "VAT", "MONTHLY", 1, 10, "UTC", 7, "2026-01-01");
    String id = generate("2026-03-10").items().get(0).id();
    as("channel.operation.update", () -> service.refreshReadiness(bearer));

    FilingCalendarEntryView view = entry(id);
    assertThat(view.dataReady()).isFalse();
    assertThat(view.dataReadinessAvailable()).as("读到了上游 ⇒ available=true").isTrue();
    assertThat(view.dataReadinessNote()).contains("没有生效中的底稿");
    assertThat(view.status()).isEqualTo("NOT_STARTED");
  }

  @Test
  @DisplayName("16.3.1.2 finance 读不到 ⇒ **available=false**，不是「数据没就绪」")
  void readinessUnavailableIsNotNotReady() {
    config("EU-DE", "VAT", "MONTHLY", 1, 10, "UTC", 7, "2026-01-01");
    String id = generate("2026-03-10").items().get(0).id();
    financeDown = true;
    as("channel.operation.update", () -> service.refreshReadiness(bearer));

    FilingCalendarEntryView view = entry(id);
    assertThat(view.dataReadinessAvailable()).as("读不到 ≠ 没就绪").isFalse();
    assertThat(view.dataReady()).isFalse();
    assertThat(view.dataReadinessNote()).contains("读不到");
    assertThat(view.status()).isEqualTo("NOT_STARTED");
  }

  @Test
  @DisplayName("16.3.1.2 12.6.1.2 没覆盖的申报线 ⇒ 判不了就绪（写明归谁，不假装就绪）")
  void readinessForUnsupportedTaxType() {
    config("US-CA", "SALES_TAX", "QUARTERLY", 1, 10, "UTC", 7, "2026-01-01");
    String id = generate("2026-05-15").items().get(0).id();
    as("channel.operation.update", () -> service.refreshReadiness(bearer));

    FilingCalendarEntryView view = entry(id);
    assertThat(view.dataReadinessAvailable()).isTrue();
    assertThat(view.dataReady()).isFalse();
    assertThat(view.dataReadinessNote()).contains("只做 VAT / IOSS");
  }

  // ==================================================================
  // 5. 提醒
  // ==================================================================

  @Test
  @DisplayName("16.3.1.2 条目真的派生两条提醒（提前 + 截止），分页截断**如实报总数**")
  void remindersAreMaterialisedAndTruncationIsReported() {
    config("EU-DE", "VAT", "MONTHLY", 1, 10, "UTC", 7, "2026-01-01");
    generate("2026-03-10");

    FilingCalendarReminderPage page =
        as("channel.operation.read", () -> service.reminders(null, null, null, null, 0, 20, bearer));
    assertThat(page.items()).hasSize(2);
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.unacknowledgedCount()).isEqualTo(2);
    assertThat(page.items()).extracting(FilingCalendarReminderView::kind)
        .containsExactlyInAnyOrder("DUE_SOON", "DUE");
    assertThat(page.notice()).contains("共 2 条");

    // 只取 1 条 ⇒ 如实报「只取回前 1 条（共 2 条）」
    FilingCalendarReminderPage one =
        as("channel.operation.read", () -> service.reminders(null, null, null, null, 0, 1, bearer));
    assertThat(one.items()).hasSize(1);
    assertThat(one.total()).isEqualTo(2);
    assertThat(one.unacknowledgedCount()).as("未确认数是全量计数，不受分页影响").isEqualTo(2);
    assertThat(one.notice()).contains("只取回前 1 条").contains("共 2 条");
  }

  @Test
  @DisplayName("16.3.1.2 确认已知悉会落库（谁 / 何时）")
  void acknowledgeReminderPersists() {
    config("EU-DE", "VAT", "MONTHLY", 1, 10, "UTC", 7, "2026-01-01");
    generate("2026-03-10");
    FilingCalendarReminderPage page =
        as("channel.operation.read", () -> service.reminders(null, null, null, null, 0, 20, bearer));
    String reminderId = page.items().get(0).id();

    FilingCalendarReminderView view =
        as(
            "channel.operation.update",
            () -> service.acknowledgeReminder(UUID.fromString(reminderId), who, bearer));
    assertThat(view.acknowledged()).isTrue();
    assertThat(view.acknowledgedBy()).isEqualTo(MEMBER.toString());
    assertThat(view.acknowledgedAt()).isNotEmpty();
  }

  @Test
  @DisplayName("16.3.1.2 标了「已提交」⇒ 未确认的提醒作废（已经报过了就别再催）")
  void submittedClearsPendingReminders() {
    config("EU-DE", "VAT", "MONTHLY", 1, 10, "UTC", 7, "2026-01-01");
    String id = generate("2026-03-10").items().get(0).id();
    as(
        "channel.operation.update",
        () ->
            service.advance(
                UUID.fromString(id),
                new FilingCalendarAdvanceInput("SUBMITTED", "REF-9", null),
                who,
                bearer));

    FilingCalendarReminderPage page =
        as("channel.operation.read", () -> service.reminders(null, null, null, null, 0, 20, bearer));
    assertThat(page.total()).isZero();
  }

  // ==================================================================
  // 6. 责任人 = 租户内成员
  // ==================================================================

  @Test
  @DisplayName("16.3.1.2 责任人是本租户成员时落库，并显示姓名（姓名是现读的，不抄）")
  void responsibleMemberIsAccepted() {
    config("EU-DE", "VAT", "MONTHLY", 1, 10, "UTC", 7, "2026-01-01");
    String id = generate("2026-03-10").items().get(0).id();
    FilingCalendarEntryView view =
        as(
            "channel.operation.update",
            () ->
                service.updateEntry(
                    UUID.fromString(id),
                    new FilingCalendarEntryInput(
                        "EU-DE", "VAT", "MONTHLY", null, null, null, null, MEMBER.toString(),
                        null, null),
                    who,
                    bearer));
    assertThat(view.responsibleMembershipId()).isEqualTo(MEMBER.toString());
    assertThat(view.responsibleName()).isEqualTo("合规负责人");
    assertThat(view.responsibleAvailable()).isTrue();
    assertThat(
            scalarAsTenant(
                tenant,
                "select responsible_membership_id from filing_calendar_entry where tenant_id='"
                    + tenant + "'"))
        .isEqualTo(MEMBER.toString());
  }

  @Test
  @DisplayName("16.3.1.2 责任人不存在 ⇒ 拒绝落库（不存查无此人的 id）")
  void unknownResponsibleIsRejected() {
    config("EU-DE", "VAT", "MONTHLY", 1, 10, "UTC", 7, "2026-01-01");
    String id = generate("2026-03-10").items().get(0).id();
    assertThatThrownBy(
            () ->
                as(
                    "channel.operation.update",
                    () ->
                        service.updateEntry(
                            UUID.fromString(id),
                            new FilingCalendarEntryInput(
                                "EU-DE", "VAT", "MONTHLY", null, null, null, null,
                                "33333333-3333-3333-3333-333333333301", null, null),
                            who,
                            bearer)))
        .hasMessageContaining("不在本租户");
  }

  @Test
  @DisplayName("16.3.1.2 成员目录读不到 ⇒ 拒绝落库（503），不静默接受")
  void responsibleRejectedWhenDirectoryUnavailable() {
    config("EU-DE", "VAT", "MONTHLY", 1, 10, "UTC", 7, "2026-01-01");
    String id = generate("2026-03-10").items().get(0).id();
    membersReadable = false;
    assertThatThrownBy(
            () ->
                as(
                    "channel.operation.update",
                    () ->
                        service.updateEntry(
                            UUID.fromString(id),
                            new FilingCalendarEntryInput(
                                "EU-DE", "VAT", "MONTHLY", null, null, null, null,
                                "44444444-4444-4444-4444-444444444401", null, null),
                            who,
                            bearer)))
        .hasMessageContaining("读不到");
  }

  // ==================================================================
  // 7. 所需数据 / 逾期 / HTTP
  // ==================================================================

  @Test
  @DisplayName("16.3.1.2 所需数据清单写回读回一致（JSONB）")
  void requiredDataRoundTrips() {
    config("EU-DE", "VAT", "MONTHLY", 1, 10, "UTC", 7, "2026-01-01");
    String id = generate("2026-03-10").items().get(0).id();
    FilingCalendarEntryView view =
        as(
            "channel.operation.update",
            () ->
                service.updateEntry(
                    UUID.fromString(id),
                    new FilingCalendarEntryInput(
                        "EU-DE", "VAT", "MONTHLY", null, null, null,
                        List.of(
                            new FilingCalendarRequiredData(
                                "TAXABLE_SALES", "应税销售额", true, "12.6.1.2 申报底稿"),
                            new FilingCalendarRequiredData(
                                "VAT_COLLECTED", "已收增值税", true, "12.6.1.2 申报底稿"),
                            new FilingCalendarRequiredData(
                                "DEDUCTIBLE_INPUT", "可抵扣进项", false, "12.6.1.1 凭证")),
                        null,
                        null,
                        null),
                    who,
                    bearer));
    assertThat(view.requiredData()).hasSize(3);
    assertThat(view.requiredData().get(0).code()).isEqualTo("TAXABLE_SALES");
    assertThat(view.requiredData().get(2).required()).isFalse();
    assertThat(
            scalarAsTenant(
                tenant,
                "select required_data::text from filing_calendar_entry where tenant_id='"
                    + tenant + "'"))
        .contains("TAXABLE_SALES")
        .contains("DEDUCTIBLE_INPUT");
  }

  @Test
  @DisplayName("16.3.1.2 逾期判定按服务端时间（过去的截止日 ⇒ 逾期，负数天数）")
  void overdueIsComputedServerSide() {
    config("EU-DE", "VAT", "MONTHLY", 1, 10, "UTC", 7, "2024-01-01");
    FilingCalendarEntryView view = generate("2024-03-10").items().get(0);
    assertThat(view.overdue()).isTrue();
    assertThat(view.daysToDue()).isNegative();

    // 🔴 用一个**真的在未来**的期间（服务端时钟为准），否则这条断言会随日历翻页而失效
    config("EU-DE", "VAT", "QUARTERLY", 1, 10, "UTC", 7, "2030-01-01");
    FilingCalendarEntryView future = generate("2030-05-15").items().get(0);
    assertThat(future.overdue()).isFalse();
    assertThat(future.daysToDue()).isPositive();
  }

  @Test
  @DisplayName("16.3.1.2 HTTP 层真分页真筛选 + 能力声明")
  void httpEndpointsWork() throws Exception {
    config("EU-DE", "VAT", "MONTHLY", 1, 10, "UTC", 7, "2026-01-01");
    generate("2026-03-10");

    http.perform(get("/channel/filing-calendar/entries").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].jurisdictionCode").value("EU-DE"))
        .andExpect(jsonPath("$.items[0].jurisdictionName").value("德国"));

    http.perform(
            get("/channel/filing-calendar/entries")
                .header("Authorization", bearer)
                .param("jurisdictionCode", "US-CA"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));

    http.perform(get("/channel/filing-calendar/capabilities").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.upstreams[?(@.moduleId=='12.6.1.2')].available").value(true))
        .andExpect(jsonPath("$.statuses").value(org.hamcrest.Matchers.hasSize(4)));

    http.perform(get("/channel/filing-calendar/reminders").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.unacknowledgedCount").value(2));

    // 无会话 ⇒ 401（伪造 tenant header 救不了）
    http.perform(get("/channel/filing-calendar/entries").header("X-Tenant-Id", tenant.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("16.3.1.2 没有周期配置也没有截止日 ⇒ 明确报错（不编一个截止日出来）")
  void missingDueDateIsRejected() {
    assertThatThrownBy(
            () ->
                as(
                    "channel.operation.create",
                    () ->
                        service.createEntry(
                            new FilingCalendarEntryInput(
                                "EU-DE", "VAT", "MONTHLY", "2026-03-01", "2026-03-31", null,
                                null, null, null, null),
                            who,
                            bearer)))
        .hasMessageContaining("周期配置");
  }

  // ==================================================================
  // 工具
  // ==================================================================

  /** 取最内层的异常消息（PostgreSQL 的触发器异常包了好几层）。 */
  static String rootMessage(Throwable thrown) {
    Throwable current = thrown;
    StringBuilder all = new StringBuilder();
    while (current != null) {
      if (current.getMessage() != null) {
        all.append(current.getMessage()).append('\n');
      }
      current = current.getCause();
    }
    return all.toString();
  }
}
