package com.starcon.channel;

import static com.starcon.channel.BuyerBlacklistModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 16.5.1.1 黑名单。
 *
 * <p>本测试类钉住这几组事：
 *
 * <ol>
 *   <li><b>🔴 登记与解除都留痕，历史不可抹掉</b>：登记行 append-only，解除只**追加事件**，
 *       直接 UPDATE / DELETE 登记行会被 DB 触发器拒。</li>
 *   <li><b>🔴 申诉解除</b>：申诉是一张单子，裁决只许走一次；通过 ⇒ 解除，驳回 ⇒ 维持。</li>
 *   <li><b>🔴 生效范围参与判定</b>：仅本店铺的黑名单在别的店铺**不命中**，全店铺的到处命中。</li>
 *   <li><b>🔴 读不到 ≠ 没风险</b>：9.2 审单不可达 ⇒ 503，**不是**「这个买家没有风险」。</li>
 *   <li><b>🔴 无记录 ≠ 没风险</b>：9.2 查无此人时仍可登记，界面显示「未知」而不是「干净」。</li>
 *   <li><b>🔴 同一范围下只能有一条生效中</b>（服务层 409 + DB 延迟约束，双重拦）。</li>
 *   <li><b>🔴 风险等级只升不降</b>、<b>🔴 店铺必须真实存在</b>、<b>🔴 拦截标记是时点事实</b>。</li>
 *   <li><b>🔴 未交付的下游不编造</b>：9.2.1.3 / 11 售后在能力声明里 available=false + ownerModule。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class BuyerBlacklistIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  /** V1 播种的 AMAZON 平台（全局码表）。 */
  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");

  static HttpServer identity;
  static BuyerBlacklistTradeStub trade;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  UUID tenant;
  AccessService.Access actor;
  String bearer;

  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\"],\"scopeMode\":\"ALL\"}]";

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
      trade = new BuyerBlacklistTradeStub();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @BeforeEach
  void freshTenant() {
    tenant = UUID.randomUUID();
    bearer = "Bearer t-" + tenant;
    actors.put(bearer, actor(user, tenant, List.of(grant("ALL", allPermissions))));
    actor = actors.get(bearer);
    trade.reset();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("TRADE_URL", trade::base);
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired BuyerBlacklistService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String permission, java.util.function.Supplier<T> work) {
    return access.run(actor, permission, work);
  }

  /** 主体键（与 trade / 16.5.1.2 同一公式）。 */
  static String key(String name, String phone, String email) {
    return BuyerRiskScoreSourcePort.buyerKey(name, phone, email);
  }

  void execAsTenant(String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以**应用账号 channel_app**（真实 RLS）直查 —— 超级用户绕过 RLS 会让断言永远「通过」。 */
  long countAsApp(String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(statement)) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  String scalar(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(statement)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * 跑一段**多语句事务脚本**并提交。
   *
   * <p>🔴 用它是为了触发**延迟约束**（同一个买家同一范围下只能有一条生效中的黑名单）：
   * 延迟约束在**提交那一刻**才判定，单条 autocommit 语句测不出来。
   *
   * @param asApp true ⇒ 用应用账号 channel_app（真实 RLS）；false ⇒ 用迁移超级账号
   * @return 脚本跑完后抛出的异常（null ⇒ 顺利提交）
   */
  RuntimeException runScript(boolean asApp, String... statements) {
    String url = pg.getJdbcUrl();
    String userName = asApp ? "channel_app" : pg.getUsername();
    String password = asApp ? "test-only" : pg.getPassword();
    try (Connection c = DriverManager.getConnection(url, userName, password);
        var s = c.createStatement()) {
      c.setAutoCommit(false);
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      for (String statement : statements) {
        s.execute(statement);
      }
      c.commit();
      return null;
    } catch (SQLException e) {
      return new RuntimeException(e);
    }
  }

  /** 播种一个店铺。 */
  String seedShop(String code) {
    UUID id = UUID.randomUUID();
    execAsTenant(
        "insert into shop (tenant_id,id,platform_id,code,name) values ('"
            + tenant
            + "','"
            + id
            + "','"
            + AMAZON
            + "','"
            + code
            + "','测试店铺 "
            + code
            + "')");
    return code;
  }

  BuyerBlacklistRegisterInput registerInput(
      String name, String phone, String email, String scope, String shopCode, String level, String reason) {
    return new BuyerBlacklistRegisterInput(
        name, phone, email, scope, shopCode, level, reason, "");
  }

  BuyerBlacklistCheckInput checkInput(String name, String phone, String email, String shopCode) {
    return new BuyerBlacklistCheckInput(name, phone, email, shopCode, "SO-TEST-1");
  }

  /** 登记一个在 9.2 审单里有画像的买家。 */
  BuyerBlacklistEntryView registerKnown(
      String name, String phone, String email, String scope, String shopCode, String level, String reason) {
    trade.putProfile(key(name, phone, email), name, phone, email, "STANDARD", false, "");
    return as(
        "channel.operation.create",
        () -> service.register(bearer, registerInput(name, phone, email, scope, shopCode, level, reason), "tester"));
  }

  // ==================================================================
  // 一、登记
  // ==================================================================

  @Test
  @DisplayName("16.5.1.1 手工录入黑名单：登记落库 + 一条 REGISTERED 事件 + 当前状态生效中")
  void manualRegistrationPersistsEntryAndEvent() {
    BuyerBlacklistEntryView entry =
        registerKnown("Alice Smith", "+1-555-0100", "alice@example.com", "ALL_SHOPS", "", "HIGH", "多次欺诈退货");

    assertThat(entry.status()).isEqualTo("ACTIVE");
    assertThat(entry.statusLabel()).isEqualTo("生效中");
    assertThat(entry.source()).isEqualTo("MANUAL");
    assertThat(entry.riskLevel()).isEqualTo("HIGH");
    assertThat(entry.scope()).isEqualTo("ALL_SHOPS");
    assertThat(entry.buyerKey()).isEqualTo(key("Alice Smith", "+1-555-0100", "alice@example.com"));
    assertThat(entry.reason()).isEqualTo("多次欺诈退货");
    // 登记人留痕（谁拉黑的）
    assertThat(entry.registeredBy()).isNotBlank();

    assertThat(countAsApp("select count(*) from buyer_blacklist_entry"))
        .as("登记行真的落库了")
        .isEqualTo(1);
    assertThat(countAsApp("select count(*) from buyer_blacklist_status_event"))
        .as("登记必然配一条 REGISTERED 事件")
        .isEqualTo(1);

    BuyerBlacklistEntryDetail detail = as("channel.operation.read", () -> service.entry(entry.entryId().toString()));
    assertThat(detail.events()).hasSize(1);
    assertThat(detail.events().get(0).eventType()).isEqualTo("REGISTERED");
    assertThat(detail.events().get(0).eventSeq()).isEqualTo(1);
    assertThat(detail.events().get(0).reason()).isEqualTo("多次欺诈退货");
    assertThat(detail.events().get(0).occurredAt()).isNotNull();
  }

  @Test
  @DisplayName("16.5.1.1 自动录入（风控信号）：来源与信号引用都留痕")
  void autoRegistrationKeepsSignalRef() {
    String buyerKey = key("Bob Jones", "+1-555-0200", "bob@example.com");
    BuyerBlacklistEntryView entry =
        as(
            "channel.operation.create",
            () ->
                service.registerAuto(
                    new BuyerBlacklistAutoInput(
                        buyerKey, "ALL_SHOPS", "", "CRITICAL", "风控信号命中欺诈", "SIGNAL-FRAUD-001", ""),
                    "SYSTEM"));

    assertThat(entry.source()).isEqualTo("RISK_SIGNAL");
    assertThat(entry.sourceLabel()).isEqualTo("风控信号自动");
    assertThat(entry.sourceRef()).isEqualTo("SIGNAL-FRAUD-001");
    assertThat(entry.riskLevel()).isEqualTo("CRITICAL");
    assertThat(entry.status()).isEqualTo("ACTIVE");
    assertThat(countAsApp("select count(*) from buyer_blacklist_entry")).isEqualTo(1);
  }

  @Test
  @DisplayName("16.5.1.1 生效范围参与判定：仅本店铺的黑名单在别的店铺不命中")
  void scopeLimitsWhereTheBlacklistApplies() {
    seedShop("SHOP_A");
    seedShop("SHOP_B");
    registerKnown("Carol White", "+1-555-0300", "carol@example.com", "SHOP_ONLY", "SHOP_A", "HIGH", "仅 A 店");

    BuyerBlacklistCheckResult atA =
        as(
            "channel.operation.create",
            () ->
                service.check(
                    bearer, checkInput("Carol White", "+1-555-0300", "carol@example.com", "SHOP_A")));
    assertThat(atA.blacklisted()).as("A 店命中").isTrue();
    assertThat(atA.matchedScope()).isEqualTo("SHOP_ONLY");
    assertThat(atA.matchedShopCode()).isEqualTo("SHOP_A");

    BuyerBlacklistCheckResult atB =
        as(
            "channel.operation.create",
            () ->
                service.check(
                    bearer, checkInput("Carol White", "+1-555-0300", "carol@example.com", "SHOP_B")));
    assertThat(atB.blacklisted()).as("B 店不命中（生效范围参与了判定）").isFalse();
    assertThat(atB.flagId()).as("没命中就不落拦截标记").isNull();
  }

  @Test
  @DisplayName("16.5.1.1 全店铺范围在每一家店都命中")
  void allShopsScopeHitsEveryShop() {
    seedShop("SHOP_A");
    seedShop("SHOP_B");
    registerKnown("Dave Brown", "+1-555-0400", "dave@example.com", "ALL_SHOPS", "", "MEDIUM", "全店铺");

    for (String shop : List.of("SHOP_A", "SHOP_B")) {
      BuyerBlacklistCheckResult result =
          as(
              "channel.operation.create",
              () ->
                  service.check(
                      bearer, checkInput("Dave Brown", "+1-555-0400", "dave@example.com", shop)));
      assertThat(result.blacklisted()).as(shop + " 命中").isTrue();
      assertThat(result.matchedScope()).isEqualTo("ALL_SHOPS");
    }
  }

  @Test
  @DisplayName("16.5.1.1 生效范围与店铺必须自洽（服务层 422）")
  void scopeAndShopCodeMustBeConsistent() {
    seedShop("SHOP_A");
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> registerKnown("Eve White", "+1-555-0500", "eve@example.com", "ALL_SHOPS", "SHOP_A", "LOW", "x")))
        .as("全店铺却指定了店铺 ⇒ 拒")
        .isNotNull();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> registerKnown("Eve White", "+1-555-0500", "eve@example.com", "SHOP_ONLY", "", "LOW", "x")))
        .as("仅本店铺却没给店铺 ⇒ 拒")
        .isNotNull();
    assertThat(countAsApp("select count(*) from buyer_blacklist_entry")).isZero();
  }

  @Test
  @DisplayName("16.5.1.1 仅本店铺时店铺必须真实存在（否则这条黑名单永远不会生效）")
  void shopMustReallyExist() {
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    registerKnown(
                        "Frank Hall", "+1-555-0600", "frank@example.com", "SHOP_ONLY", "NO_SUCH_SHOP", "LOW", "x")))
        .as("店铺不存在 ⇒ 拒")
        .isNotNull();
    assertThat(countAsApp("select count(*) from buyer_blacklist_entry")).isZero();
  }

  // ==================================================================
  // 二、解除与申诉（加粗句「黑名单应支持申诉解除」的落点）
  // ==================================================================

  @Test
  @DisplayName("16.5.1.1 解除只追加事件，登记行一字未改（历史不可抹掉）")
  void releaseAppendsEventAndNeverMutatesTheRegistration() {
    BuyerBlacklistEntryView before =
        registerKnown("Grace King", "+1-555-0700", "grace@example.com", "ALL_SHOPS", "", "HIGH", "确认欺诈");
    String originalReason = before.reason();
    java.time.OffsetDateTime originalRegisteredAt = before.registeredAt();
    String originalRegisteredBy = before.registeredBy();

    BuyerBlacklistEntryView after =
        as(
            "channel.operation.update",
            () ->
                service.release(
                    before.entryId().toString(),
                    new BuyerBlacklistReleaseInput("买家出示了平台退款凭证，误判"),
                    "tester"));

    assertThat(after.status()).isEqualTo("RELEASED");
    assertThat(after.statusLabel()).isEqualTo("已解除");
    // 🔴 登记行不可改：原始登记原样保留
    assertThat(after.reason()).isEqualTo(originalReason);
    assertThat(after.registeredAt()).isEqualTo(originalRegisteredAt);
    assertThat(after.registeredBy()).isEqualTo(originalRegisteredBy);

    BuyerBlacklistEntryDetail detail =
        as("channel.operation.read", () -> service.entry(before.entryId().toString()));
    assertThat(detail.events()).hasSize(2);
    BuyerBlacklistStatusEventView release = detail.events().get(1);
    assertThat(release.eventType()).isEqualTo("RELEASED");
    assertThat(release.fromStatus()).isEqualTo("ACTIVE");
    assertThat(release.toStatus()).isEqualTo("RELEASED");
    assertThat(release.actor()).isEqualTo("tester");
    assertThat(release.reason()).isEqualTo("买家出示了平台退款凭证，误判");
    assertThat(release.occurredAt()).isNotNull();
  }

  @Test
  @DisplayName("16.5.1.1 登记行不可 UPDATE / DELETE（DB 触发器兜底，应用账号与超级账号各试一次）")
  void registrationRowsAreImmutable() {
    BuyerBlacklistEntryView entry =
        registerKnown("Henry Cole", "+1-555-0800", "henry@example.com", "ALL_SHOPS", "", "LOW", "x");

    for (boolean asApp : new boolean[] {true, false}) {
      assertThat(runScript(asApp, "update buyer_blacklist_entry set reason = '偷偷改掉' where id = '"
              + entry.entryId() + "'"))
          .as("直接 UPDATE 登记行必须被拒（asApp=" + asApp + "）")
          .isNotNull();
      assertThat(runScript(asApp, "delete from buyer_blacklist_entry where id = '"
              + entry.entryId() + "'"))
          .as("直接 DELETE 登记行必须被拒（asApp=" + asApp + "）")
          .isNotNull();
      assertThat(runScript(asApp, "update buyer_blacklist_status_event set reason = '偷偷改掉'"))
          .as("直接 UPDATE 状态事件必须被拒（asApp=" + asApp + "）")
          .isNotNull();
    }
    assertThat(countAsApp("select count(*) from buyer_blacklist_entry")).isEqualTo(1);
  }

  @Test
  @DisplayName("16.5.1.1 申诉通过 ⇒ 解除并留痕；申诉驳回 ⇒ 维持黑名单")
  void appealDecisionReleasesOrKeeps() {
    BuyerBlacklistEntryView entry =
        registerKnown("Ivy Baker", "+1-555-0900", "ivy@example.com", "ALL_SHOPS", "", "MEDIUM", "疑似薅羊毛");

    BuyerBlacklistAppealView appeal =
        as(
            "channel.operation.create",
            () ->
                service.submitAppeal(
                    entry.entryId().toString(),
                    new BuyerBlacklistAppealInput("我一次都没薅过，这是朋友的号"),
                    "客服小王"));

    BuyerBlacklistAppealView decided =
        as(
            "channel.operation.update",
            () ->
                service.decideAppeal(
                    appeal.appealId().toString(),
                    new BuyerBlacklistAppealDecisionInput("APPROVED", "核实后确认是同行代下单，已解除"),
                    "风控主管"));

    assertThat(decided.decision()).isEqualTo("APPROVED");
    assertThat(decided.decidedBy()).isEqualTo("风控主管");
    assertThat(decided.decidedAt()).isNotNull();

    BuyerBlacklistEntryDetail detail =
        as("channel.operation.read", () -> service.entry(entry.entryId().toString()));
    assertThat(detail.entry().status()).as("申诉通过 ⇒ 已解除").isEqualTo("RELEASED");
    assertThat(detail.events()).hasSize(2);
    assertThat(detail.events().get(1).eventType()).isEqualTo("APPEAL_APPROVED");
    assertThat(detail.events().get(1).appealId()).isEqualTo(appeal.appealId());
    assertThat(detail.events().get(1).reason()).isEqualTo("核实后确认是同行代下单，已解除");
    assertThat(detail.appeals()).hasSize(1);
    assertThat(detail.appeals().get(0).appealReason()).isEqualTo("我一次都没薅过，这是朋友的号");
  }

  @Test
  @DisplayName("16.5.1.1 申诉驳回 ⇒ 维持黑名单，并留下驳回留痕")
  void appealRejectionKeepsTheBlacklist() {
    BuyerBlacklistEntryView entry =
        registerKnown("Jack Fox", "+1-555-1000", "jack@example.com", "ALL_SHOPS", "", "HIGH", "确认欺诈");

    BuyerBlacklistAppealView appeal =
        as(
            "channel.operation.create",
            () ->
                service.submitAppeal(
                    entry.entryId().toString(),
                    new BuyerBlacklistAppealInput("我要申诉"),
                    "客服小王"));
    as(
        "channel.operation.update",
        () ->
            service.decideAppeal(
                appeal.appealId().toString(),
                new BuyerBlacklistAppealDecisionInput("REJECTED", "证据不足，维持黑名单"),
                "风控主管"));

    BuyerBlacklistEntryDetail detail =
        as("channel.operation.read", () -> service.entry(entry.entryId().toString()));
    assertThat(detail.entry().status()).as("申诉驳回 ⇒ 仍生效").isEqualTo("ACTIVE");
    assertThat(detail.events()).hasSize(2);
    assertThat(detail.events().get(1).eventType()).isEqualTo("APPEAL_REJECTED");
    assertThat(detail.events().get(1).toStatus()).isEqualTo("ACTIVE");
  }

  @Test
  @DisplayName("16.5.1.1 一张申诉单只许裁决一次")
  void appealCanOnlyBeDecidedOnce() {
    BuyerBlacklistEntryView entry =
        registerKnown("Kim Diaz", "+1-555-1100", "kim@example.com", "ALL_SHOPS", "", "LOW", "x");
    BuyerBlacklistAppealView appeal =
        as(
            "channel.operation.create",
            () ->
                service.submitAppeal(
                    entry.entryId().toString(),
                    new BuyerBlacklistAppealInput("申诉"),
                    "客服小王"));
    as(
        "channel.operation.update",
        () ->
            service.decideAppeal(
                appeal.appealId().toString(),
                new BuyerBlacklistAppealDecisionInput("REJECTED", "维持"),
                "风控主管"));

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.update",
                        () ->
                            service.decideAppeal(
                                appeal.appealId().toString(),
                                new BuyerBlacklistAppealDecisionInput("APPROVED", "改判"),
                                "风控主管"))))
        .as("二次裁决必须被拒")
        .isNotNull();
  }

  @Test
  @DisplayName("16.5.1.1 申诉单的事实部分不可改（DB 触发器兜底）")
  void appealFactsAreImmutable() {
    BuyerBlacklistEntryView entry =
        registerKnown("Lee Ray", "+1-555-1200", "lee@example.com", "ALL_SHOPS", "", "LOW", "x");
    BuyerBlacklistAppealView appeal =
        as(
            "channel.operation.create",
            () ->
                service.submitAppeal(
                    entry.entryId().toString(),
                    new BuyerBlacklistAppealInput("原始申诉理由"),
                    "客服小王"));

    for (boolean asApp : new boolean[] {true, false}) {
      assertThat(runScript(asApp, "update buyer_blacklist_appeal set appeal_reason = '改口' where id = '"
              + appeal.appealId() + "'"))
          .as("改申诉理由必须被拒（asApp=" + asApp + "）")
          .isNotNull();
    }
    assertThat(scalar("select appeal_reason from buyer_blacklist_appeal where id = '" + appeal.appealId() + "'"))
        .isEqualTo("原始申诉理由");
  }

  // ==================================================================
  // 三、跨服务读面（9.2 审单）
  // ==================================================================

  @Test
  @DisplayName("16.5.1.1 9.2 审单读不到 ⇒ 503，绝不冒充「这个买家没有风险」")
  void upstreamUnavailableIsServiceUnavailable() throws Exception {
    trade.unreachable = true;
    http.perform(
            post("/channel/buyer-blacklist/check")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        checkInput("Nina Young", "+1-555-1300", "nina@example.com", ""))))
        .andExpect(status().isServiceUnavailable());

    http.perform(
            post("/channel/buyer-blacklist/entries")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        registerInput(
                            "Nina Young", "+1-555-1300", "nina@example.com", "ALL_SHOPS", "", "HIGH", "x"))))
        .andExpect(status().isServiceUnavailable());

    assertThat(countAsApp("select count(*) from buyer_blacklist_entry"))
        .as("读不到时一条都不能写")
        .isZero();
  }

  @Test
  @DisplayName("16.5.1.1 9.2 查无此人 ⇒ 仍然可登记，但界面显示「未知」而不是「干净」")
  void unknownBuyerIsStillRegistrable() {
    // 9.2 里没有这个人的画像
    BuyerBlacklistEntryView entry =
        as(
            "channel.operation.create",
            () ->
                service.register(
                    bearer,
                    registerInput(
                        "Owen King", "+1-555-1400", "owen@example.com", "ALL_SHOPS", "", "LOW", "x"),
                    "tester"));
    assertThat(entry.buyerKey()).isEqualTo(key("Owen King", "+1-555-1400", "owen@example.com"));

    BuyerBlacklistCheckResult check =
        as(
            "channel.operation.create",
            () ->
                service.check(
                    bearer, checkInput("Owen King", "+1-555-1400", "owen@example.com", "")));
    assertThat(check.buyerKnown()).as("9.2 里没有这个人的记录").isFalse();
    assertThat(check.upstreamAvailable()).isTrue();
    assertThat(check.upstreamNote()).contains("无记录");
  }

  // ==================================================================
  // 四、订单拦截标记
  // ==================================================================

  @Test
  @DisplayName("16.5.1.1 下单检查命中 ⇒ 落一条订单拦截标记，并只给建议动作")
  void orderCheckWritesFlagAndOnlySuggests() {
    registerKnown("Pia Hall", "+1-555-1500", "pia@example.com", "ALL_SHOPS", "", "HIGH", "确认欺诈");

    BuyerBlacklistCheckResult check =
        as(
            "channel.operation.create",
            () ->
                service.check(
                    bearer, checkInput("Pia Hall", "+1-555-1500", "pia@example.com", "")));

    assertThat(check.blacklisted()).isTrue();
    assertThat(check.recommendedAction()).as("HIGH ⇒ 建议拦截").isEqualTo("BLOCK");
    assertThat(check.recommendedActionLabel()).isEqualTo("建议拦截");
    assertThat(check.flagWritten()).isTrue();
    assertThat(check.flagId()).isNotNull();

    assertThat(countAsApp("select count(*) from buyer_blacklist_order_flag")).isEqualTo(1);
    BuyerBlacklistFlagPage flags =
        as("channel.operation.read", () -> service.flags(null, null, 0, 50));
    assertThat(flags.total()).isEqualTo(1);
    assertThat(flags.items().get(0).orderRef()).isEqualTo("SO-TEST-1");
    assertThat(flags.counts().block()).isEqualTo(1);
  }

  @Test
  @DisplayName("16.5.1.1 拦截标记是时点事实：解除之后旧标记仍在（不改写历史）")
  void flagsArePointInTimeFacts() {
    BuyerBlacklistEntryView entry =
        registerKnown("Quin Scott", "+1-555-1600", "quin@example.com", "ALL_SHOPS", "", "CRITICAL", "确认欺诈");
    as(
        "channel.operation.create",
        () -> service.check(bearer, checkInput("Quin Scott", "+1-555-1600", "quin@example.com", "")));

    as(
        "channel.operation.update",
        () ->
            service.release(
                entry.entryId().toString(),
                new BuyerBlacklistReleaseInput("核实后确认误判"),
                "tester"));

    assertThat(countAsApp("select count(*) from buyer_blacklist_order_flag"))
        .as("解除黑名单不改写「当时被标记过」这个事实")
        .isEqualTo(1);

    BuyerBlacklistCheckResult after =
        as(
            "channel.operation.create",
            () ->
                service.check(
                    bearer, checkInput("Quin Scott", "+1-555-1600", "quin@example.com", "")));
    assertThat(after.blacklisted()).as("解除之后再判定 ⇒ 不再命中").isFalse();
  }

  // ==================================================================
  // 五、唯一性与等级不变量
  // ==================================================================

  @Test
  @DisplayName("16.5.1.1 同一买家同一生效范围下只能有一条生效中（服务层 409 + DB 延迟约束）")
  void onlyOneActiveEntryPerScope() {
    registerKnown("Rose Adams", "+1-555-1700", "rose@example.com", "ALL_SHOPS", "", "LOW", "第一条");

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    registerKnown(
                        "Rose Adams", "+1-555-1700", "rose@example.com", "ALL_SHOPS", "", "LOW", "第二条")))
        .as("服务层先拦")
        .isNotNull();

    // 🔴 DB 层（延迟约束）：绕过服务层直写两条生效中的，必须在提交那一刻被拒。
    String buyerKey = key("Rose Adams", "+1-555-1700", "rose@example.com");
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    String insertEntry =
        "insert into buyer_blacklist_entry (id, tenant_id, buyer_key, buyer_name, buyer_phone,"
            + " buyer_email, scope, shop_code, risk_level, reason, source, source_ref,"
            + " registered_at, registered_by, note, created_at) values ('%s', '%s', '%s', '', '', '',"
            + " 'ALL_SHOPS', '', 'LOW', '直写', 'MANUAL', '', now(), '直写', '', now())";
    String insertEvent =
        "insert into buyer_blacklist_status_event (id, tenant_id, entry_id, event_seq, from_status,"
            + " to_status, event_type, risk_level, actor, reason, appeal_id, occurred_at, created_at)"
            + " values ('%s', '%s', '%s', 1, 'ACTIVE', 'ACTIVE', 'REGISTERED', 'LOW', '直写', '直写',"
            + " null, now(), now())";

    for (boolean asApp : new boolean[] {true, false}) {
      RuntimeException failure =
          runScript(
              asApp,
              String.format(insertEntry, first, tenant, buyerKey),
              String.format(insertEvent, UUID.randomUUID(), tenant, first),
              String.format(insertEntry, second, tenant, buyerKey),
              String.format(insertEvent, UUID.randomUUID(), tenant, second));
      assertThat(failure)
          .as("绕过服务层直写两条生效中的黑名单必须在提交时被拒（asApp=" + asApp + "）")
          .isNotNull();
    }
  }

  @Test
  @DisplayName("16.5.1.1 风险等级只升不降（DB 触发器）")
  void riskLevelCannotBeDowngraded() {
    String buyerKey = key("Sara Nash", "+1-555-1800", "sara@example.com");
    UUID entryId = UUID.randomUUID();
    String insertEntry =
        "insert into buyer_blacklist_entry (id, tenant_id, buyer_key, buyer_name, buyer_phone,"
            + " buyer_email, scope, shop_code, risk_level, reason, source, source_ref,"
            + " registered_at, registered_by, note, created_at) values ('"
            + entryId
            + "', '"
            + tenant
            + "', '"
            + buyerKey
            + "', '', '', '', 'ALL_SHOPS', '', 'HIGH', '直写', 'MANUAL', '', now(), '直写', '', now())";
    String insertEvent =
        "insert into buyer_blacklist_status_event (id, tenant_id, entry_id, event_seq, from_status,"
            + " to_status, event_type, risk_level, actor, reason, appeal_id, occurred_at, created_at)"
            + " values ('%s', '%s', '%s', %d, 'ACTIVE', 'ACTIVE', '%s', '%s', '直写', '直写',"
            + " null, now(), now())";

    // 降级必须被拒
    assertThat(
            runScript(
                true,
                insertEntry,
                String.format(
                    insertEvent, UUID.randomUUID(), tenant, entryId, 1, "REGISTERED", "HIGH"),
                String.format(
                    insertEvent, UUID.randomUUID(), tenant, entryId, 2, "ESCALATED", "LOW")))
        .as("ESCALATED 却把等级降下来 ⇒ 必须被拒")
        .isNotNull();

    // 升级必须允许
    assertThat(
            runScript(
                true,
                insertEntry,
                String.format(
                    insertEvent, UUID.randomUUID(), tenant, entryId, 1, "REGISTERED", "HIGH"),
                String.format(
                    insertEvent, UUID.randomUUID(), tenant, entryId, 2, "ESCALATED", "CRITICAL")))
        .as("正常升级必须能写进去")
        .isNull();
  }

  @Test
  @DisplayName("16.5.1.1 没有状态事件的登记会被延迟约束拒掉（否则它会从所有清单里消失）")
  void registrationWithoutEventIsRejected() {
    String buyerKey = key("Tom Pace", "+1-555-1900", "tom@example.com");
    UUID entryId = UUID.randomUUID();
    assertThat(
            runScript(
                true,
                "insert into buyer_blacklist_entry (id, tenant_id, buyer_key, buyer_name, buyer_phone,"
                    + " buyer_email, scope, shop_code, risk_level, reason, source, source_ref,"
                    + " registered_at, registered_by, note, created_at) values ('"
                    + entryId
                    + "', '"
                    + tenant
                    + "', '"
                    + buyerKey
                    + "', '', '', '', 'ALL_SHOPS', '', 'LOW', '直写', 'MANUAL', '', now(), '直写', '',"
                    + " now())"))
        .as("光有登记、没有状态事件的行必须被拒")
        .isNotNull();
  }

  // ==================================================================
  // 六、能力声明与清单
  // ==================================================================

  @Test
  @DisplayName("16.5.1.1 能力声明：未交付的下游逐条标 available=false + ownerModule")
  void capabilitiesDeclareUndeliveredDownstream() {
    BuyerBlacklistCapabilities caps = as("channel.operation.read", () -> service.capabilities());

    BuyerBlacklistCapability intercept =
        caps.capabilities().stream()
            .filter(c -> c.capabilityCode().equals("INTERCEPT_ACTION"))
            .findFirst()
            .orElseThrow();
    assertThat(intercept.available()).as("9.2.1.3 拦截不是本模块代劳的").isFalse();
    assertThat(intercept.ownerModule()).isEqualTo("9.2.1.3");

    BuyerBlacklistCapability aftersales =
        caps.capabilities().stream()
            .filter(c -> c.capabilityCode().equals("AFTERSALES_CASE"))
            .findFirst()
            .orElseThrow();
    assertThat(aftersales.available()).as("11 售后不是本模块代劳的").isFalse();
    assertThat(aftersales.ownerModule()).isEqualTo("11.1.1.1");

    assertThat(caps.capabilities().stream().filter(c -> c.capabilityCode().equals("APPEAL")).findFirst()
            .orElseThrow().available())
        .as("申诉解除是本模块自己的职责")
        .isTrue();
    assertThat(caps.releaseSemantics()).contains("追加");
    assertThat(caps.appealSemantics()).contains("申诉");
  }

  @Test
  @DisplayName("16.5.1.1 黑名单只能解除不能删除：没有 DELETE 端点（405）")
  void thereIsNoDeleteEndpoint() throws Exception {
    BuyerBlacklistEntryView entry =
        registerKnown("Zoe Pike", "+1-555-2200", "zoe@example.com", "ALL_SHOPS", "", "LOW", "x");

    http.perform(delete("/channel/buyer-blacklist/entries/" + entry.entryId())
            .header("Authorization", bearer))
        .andExpect(status().isMethodNotAllowed());
    assertThat(countAsApp("select count(*) from buyer_blacklist_entry"))
        .as("被拒的删除不能碰掉任何一行")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("16.5.1.1 清单是真分页 + 真筛选，汇总不随分页变小")
  void listIsReallyPagedAndFiltered() {
    seedShop("SHOP_A");
    registerKnown("Uma Gray", "+1-555-2000", "uma@example.com", "SHOP_ONLY", "SHOP_A", "HIGH", "x");
    registerKnown("Vic Long", "+1-555-2100", "vic@example.com", "ALL_SHOPS", "", "LOW", "y");

    BuyerBlacklistEntryPage all = as("channel.operation.read", () -> service.entries(null, null, null, null, null, null, 0, 50));
    assertThat(all.total()).isEqualTo(2);
    assertThat(all.items()).hasSize(2);
    assertThat(all.counts().active()).isEqualTo(2);
    assertThat(all.counts().shopOnly()).isEqualTo(1);
    assertThat(all.counts().allShops()).isEqualTo(1);
    assertThat(all.counts().high()).isEqualTo(1);

    BuyerBlacklistEntryPage firstPage =
        as("channel.operation.read", () -> service.entries(null, null, null, null, null, null, 0, 1));
    assertThat(firstPage.items()).hasSize(1);
    assertThat(firstPage.total()).as("total 不随分页变小").isEqualTo(2);
    assertThat(firstPage.counts().active()).as("汇总不随分页变小").isEqualTo(2);

    BuyerBlacklistEntryPage filtered =
        as("channel.operation.read", () -> service.entries(null, "SHOP_ONLY", null, null, null, null, 0, 50));
    assertThat(filtered.total()).isEqualTo(1);
    assertThat(filtered.items().get(0).scope()).isEqualTo("SHOP_ONLY");

    BuyerBlacklistEntryPage byKeyword =
        as("channel.operation.read", () -> service.entries(null, null, null, null, null, "Vic", 0, 50));
    assertThat(byKeyword.total()).isEqualTo(1);
    assertThat(byKeyword.items().get(0).buyerName()).isEqualTo("Vic Long");
  }
}
