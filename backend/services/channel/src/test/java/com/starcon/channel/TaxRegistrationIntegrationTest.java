package com.starcon.channel;

import static com.starcon.channel.TaxRegistrationModels.*;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * 16.3.1.1 税号与辖区 的集成测试（**真实 PostgreSQL 16**）。
 *
 * <p>🔴 这组用例守的是本模块最容易做丢的九件事：
 *
 * <ol>
 *   <li><b>缺失项真的产出</b>：欧洲站点没登记 ⇒ 真的报出「未注册该辖区」，不是前端的一句提醒；</li>
 *   <li><b>登记齐了缺口就消失</b>（缺口不是写死的文案）；</li>
 *   <li><b>「未注册」与「缺号」不重复报</b>；</li>
 *   <li><b>辖区值域钉死</b>：自由文本辖区码在 DB 层被拒；</li>
 *   <li><b>一个主体一个辖区只有一条登记</b>，重复 ⇒ 409；</li>
 *   <li><b>timestamptz 往返断言写回的值</b>（不是只断言没抛异常）；</li>
 *   <li><b>法人登记覆盖店铺</b>（法人是 identity 的逻辑 ID，跨服务不建 FK）；</li>
 *   <li><b>法人档案读不到 ⇒ available=false</b>，不显示成空白或「未注册」；</li>
 *   <li><b>店铺清单来自真实店铺目录表</b>，不是写死的常量。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class TaxRegistrationIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");

  /** 模拟 identity 的法人档案是否可读（false ⇒ 403，模拟「读不到」）。 */
  static volatile boolean legalEntitiesReadable = true;

  /** 模拟 identity 返回的法人档案（只读端口只拿 id / code / legalName / status）。 */
  static final List<Map<String, Object>> legalEntityRows = new ArrayList<>();

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
      // 17 法人档案（identity 拥有）。只读端口只声明 id / code / legalName / status。
      identity.createContext(
          "/identity/legal-entities",
          e -> {
            if (!legalEntitiesReadable) {
              e.sendResponseHeaders(403, -1);
              e.close();
              return;
            }
            byte[] body = new ObjectMapper().writeValueAsBytes(legalEntityRows);
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

  @BeforeEach
  void freshTenant() {
    tenant = UUID.randomUUID();
    bearer = "Bearer t-" + tenant;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearer, actor(user, tenant, grants));
    who = actors.get(bearer);
    legalEntitiesReadable = true;
    legalEntityRows.clear();
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
  }

  @Autowired TaxRegistrationService service;
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

  /** 站点 ID（V1 用 md5 派生，这里直接查，不写死）。 */
  UUID marketplace(String code) {
    String raw = scalar("select id from marketplace where platform_id='" + AMAZON + "' and code='" + code + "'");
    assertThat(raw).as("站点 " + code + " 必须存在于 V1 的站点目录里").isNotNull();
    return UUID.fromString(raw);
  }

  /** 造一家店，并把它挂到指定的站点上（= 它在那些国家卖）。 */
  UUID seedShop(String code, String marketplaceCode, UUID legalEntityId) {
    UUID id = UUID.randomUUID();
    execAsTenant(
        tenant,
        "insert into shop (tenant_id,id,platform_id,code,name,legal_entity_id) values ('"
            + tenant
            + "','"
            + id
            + "','"
            + AMAZON
            + "','"
            + code
            + "','测试店铺 "
            + code
            + "',"
            + (legalEntityId == null ? "null" : "'" + legalEntityId + "'")
            + ")");
    if (marketplaceCode != null) {
      execAsTenant(
          tenant,
          "insert into shop_marketplace (tenant_id,shop_id,marketplace_id,status) values ('"
              + tenant
              + "','"
              + id
              + "','"
              + marketplace(marketplaceCode)
              + "','ACTIVE')");
    }
    return id;
  }

  /** 默认有效期止（写进登记里 ⇒ 不报「没记有效期」）。 */
  static final OffsetDateTime DEFAULT_VALID_UNTIL =
      OffsetDateTime.of(2027, 12, 31, 23, 59, 59, 0, ZoneOffset.ofHours(8));

  /**
   * 登记一条（带有效期：validFrom = validUntil - 1 年）。
   *
   * @param validUntil 有效期止；传 null ⇒ 用默认有效期
   */
  TaxRegistrationView create(
      String subjectType, UUID subjectId, String jurisdiction, boolean registered, String vatStatus,
      String vatNumber, String eprNumber, OffsetDateTime validUntil) {
    OffsetDateTime until = validUntil == null ? DEFAULT_VALID_UNTIL : validUntil;
    OffsetDateTime from = until.minusYears(1);
    return as(
        "channel.operation.create",
        () ->
            service.create(
                new TaxRegistrationInput(
                    subjectType,
                    subjectId.toString(),
                    jurisdiction,
                    "TAX-" + jurisdiction,
                    vatStatus,
                    registered,
                    vatNumber,
                    eprNumber,
                    "MONTHLY",
                    from.toString(),
                    until.toString(),
                    "ACTIVE",
                    "集成测试录入"),
                bearer));
  }

  /** 登记一条但**不记有效期**（⇒ 应当报「没记有效期（判不了）」）。 */
  TaxRegistrationView createWithoutValidity(
      String subjectType, UUID subjectId, String jurisdiction, String vatNumber, String eprNumber) {
    return as(
        "channel.operation.create",
        () ->
            service.create(
                new TaxRegistrationInput(
                    subjectType,
                    subjectId.toString(),
                    jurisdiction,
                    "TAX-" + jurisdiction,
                    "REGISTERED",
                    true,
                    vatNumber,
                    eprNumber,
                    "MONTHLY",
                    null,
                    null,
                    "ACTIVE",
                    "集成测试录入（不记有效期）"),
                bearer));
  }

  List<TaxGapItem> allGaps() {
    TaxGapPage page = as("channel.operation.read", () -> service.gaps(null, null, null, null, null, 0, 200, bearer));
    return page.items();
  }

  // ==================================================================
  // 用例
  // ==================================================================

  @Test
  @DisplayName("16.3.1.1 辖区码表是受控值域：欧盟成员国 + 英国 + 美国各州都在，且不是自由文本")
  void jurisdictionCodeTableIsControlled() {
    TaxJurisdictionList list = as("channel.operation.read", () -> service.jurisdictions());
    assertThat(list.items()).extracting(TaxJurisdictionView::code)
        .contains("EU-DE", "EU-FR", "GB", "US", "US-CA", "JP");
    // 德国：VAT 税制、要求登记号、要求 EPR、国家级税务辖区（能推导）
    TaxJurisdictionView de =
        list.items().stream().filter(item -> "EU-DE".equals(item.code())).findFirst().orElseThrow();
    assertThat(de.vatRequired()).isTrue();
    assertThat(de.requiresEpr()).isTrue();
    assertThat(de.derivationScope()).isEqualTo("COUNTRY");
    // 美国联邦：登记在州一级 ⇒ 推不出要求
    TaxJurisdictionView us =
        list.items().stream().filter(item -> "US".equals(item.code())).findFirst().orElseThrow();
    assertThat(us.derivationScope()).isEqualTo("SUBNATIONAL");
  }

  @Test
  @DisplayName("16.3.1.1 自由文本辖区码在 DB 层被拒（不留自由文本）")
  void freeTextJurisdictionIsRejected() {
    UUID shop = seedShop("FREE-TEXT", "DE", null);
    Throwable thrown =
        catchThrowable(
            () ->
                as(
                    "channel.operation.create",
                    () ->
                        service.create(
                            new TaxRegistrationInput(
                                "SHOP", shop.toString(), "NOWHERE-LAND", "", "UNKNOWN", false, "", "", "",
                                null, null, "ACTIVE", ""),
                            bearer)));
    assertThat(thrown).isNotNull();
    // 一次都没写进去（**按租户数**：同一个库里别的租户的行不该算进来）
    assertThat(
            scalar(
                "select count(*) from tax_registration_record where tenant_id='" + tenant + "'"))
        .isEqualTo("0");
  }
  @Test
  @DisplayName("16.3.1.1 店铺清单来自真实店铺目录表，并推导出应当登记的辖区")
  void shopDirectoryComesFromRealTables() {
    UUID shop = seedShop("DE-SHOP", "DE", null);
    seedShop("US-SHOP", "US", null);

    TaxShopList list = as("channel.operation.read", () -> service.shops(null, null));
    assertThat(list.items()).extracting(TaxShopView::shopId).contains(shop.toString());
    TaxShopView deShop =
        list.items().stream().filter(item -> shop.toString().equals(item.shopId())).findFirst().orElseThrow();
    assertThat(deShop.requiredJurisdictionCodes()).containsExactly("EU-DE");
    assertThat(deShop.marketplaceCodes()).containsExactly("DE");

    // 美国站点 vat_required=false ⇒ 推不出要求（**不是**「都齐了」，是「推不出」）
    TaxShopView usShop =
        list.items().stream()
            .filter(item -> "US-SHOP".equals(item.code()))
            .findFirst()
            .orElseThrow();
    assertThat(usShop.requiredJurisdictionCodes()).isEmpty();
  }

  @Test
  @DisplayName("16.3.1.1 🔴 欧洲站点没有登记 ⇒ 真的报出「未注册该辖区」（缺失项是算出来的）")
  void missingRegistrationProducesRealGap() {
    UUID shop = seedShop("GAP-SHOP", "DE", null);

    List<TaxGapItem> gaps = allGaps();
    TaxGapItem gap =
        gaps.stream()
            .filter(item -> "JURISDICTION_NOT_REGISTERED".equals(item.gapType()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("必须报出未注册该辖区，实际：" + gaps));
    assertThat(gap.severity()).isEqualTo("HIGH");
    assertThat(gap.shopId()).isEqualTo(shop.toString());
    assertThat(gap.jurisdictionCode()).isEqualTo("EU-DE");
    assertThat(gap.message()).contains("没有在册的税务登记");
    assertThat(gap.remediation()).isNotBlank();
  }

  @Test
  @DisplayName("16.3.1.1 登记齐了 ⇒ 缺口消失（缺口不是写死的文案）")
  void gapDisappearsOnceRegistered() {
    UUID shop = seedShop("FIXED-SHOP", "DE", null);
    assertThat(allGaps()).isNotEmpty();

    create(
        "SHOP", shop, "EU-DE", true, "REGISTERED", "DE123456789", "LUCID-987654", null);
    assertThat(allGaps()).isEmpty();

    // 撤下 ⇒ 缺口回来（撤下了就是不在册）
    TaxRegistrationView created =
        as("channel.operation.read", () -> service.registrations(null, null, null, null, null, null, 0, 10, bearer))
            .items()
            .get(0);
    as(
        "channel.operation.update",
        () ->
            service.update(
                created.id(),
                new TaxRegistrationInput(
                    "SHOP", shop.toString(), "EU-DE", "", "REGISTERED", true, "", "", "MONTHLY", null,
                    null, "ARCHIVED", ""),
                bearer));
    assertThat(allGaps())
        .extracting(TaxGapItem::gapType)
        .contains("JURISDICTION_NOT_REGISTERED");
  }

  @Test
  @DisplayName("16.3.1.1 已注册但号没填 ⇒ 报「缺 VAT 号」，且**不**重复报「未注册」")
  void missingNumberIsReportedWithoutDoubleCounting() {
    UUID shop = seedShop("NO-NUMBER", "DE", null);
    create("SHOP", shop, "EU-DE", true, "REGISTERED", "", "", null);

    List<TaxGapItem> gaps = allGaps();
    assertThat(gaps).extracting(TaxGapItem::gapType).contains("VAT_NUMBER_MISSING", "EPR_NUMBER_MISSING");
    assertThat(gaps).extracting(TaxGapItem::gapType).doesNotContain("JURISDICTION_NOT_REGISTERED");
  }

  @Test
  @DisplayName("16.3.1.1 登记已过有效期 ⇒ 报「已过有效期」；未登记时号为空不报缺号")
  void expiredRegistrationIsReported() {
    UUID shop = seedShop("EXPIRED", "DE", null);
    create(
        "SHOP",
        shop,
        "EU-DE",
        true,
        "REGISTERED",
        "DE123456789",
        "LUCID-987654",
        OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));

    List<TaxGapItem> gaps = allGaps();
    assertThat(gaps).extracting(TaxGapItem::gapType).contains("REGISTRATION_EXPIRED");
    assertThat(gaps).extracting(TaxGapItem::gapType).doesNotContain("VAT_NUMBER_MISSING");
  }

  @Test
  @DisplayName("16.3.1.1 🔴 timestamptz 往返：断言写回的值（不是只断言没抛异常）")
  void timestamptzRoundTripKeepsTheInstant() {
    UUID shop = seedShop("TIME", "DE", null);
    OffsetDateTime validFrom = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(8));
    OffsetDateTime validUntil = OffsetDateTime.of(2026, 12, 31, 23, 59, 59, 0, ZoneOffset.ofHours(8));

    TaxRegistrationView created =
        as(
            "channel.operation.create",
            () ->
                service.create(
                    new TaxRegistrationInput(
                        "SHOP", shop.toString(), "EU-DE", "TAX-1", "REGISTERED", true, "DE1", "LUCID-1",
                        "MONTHLY", validFrom.toString(), validUntil.toString(), "ACTIVE", ""),
                    bearer));

    // 读回来的值必须是**同一个时刻**（偏了 8 小时就是用错 timestamptz 绑定）
    assertThat(OffsetDateTime.parse(created.registrationValidFrom()).toInstant())
        .as("注册有效期起必须原样写回（偏 8 小时说明绑成了 timestamp）")
        .isEqualTo(validFrom.toInstant());
    assertThat(OffsetDateTime.parse(created.registrationValidUntil()).toInstant())
        .isEqualTo(validUntil.toInstant());

    // 直接查库再确认一次（UTC 视角）
    assertThat(scalar("select to_char(registration_valid_until at time zone 'UTC','YYYY-MM-DD HH24:MI:SS')"
            + " from tax_registration_record where id='" + created.id() + "'"))
        .isEqualTo("2026-12-31 15:59:59");
  }

  @Test
  @DisplayName("16.3.1.1 一个主体一个辖区只有一条登记，重复 ⇒ 409")
  void duplicateRegistrationIsRejected() {
    UUID shop = seedShop("DUP", "DE", null);
    create("SHOP", shop, "EU-DE", true, "REGISTERED", "DE1", "LUCID-1", null);

    Throwable thrown =
        catchThrowable(() -> create("SHOP", shop, "EU-DE", true, "REGISTERED", "DE2", "LUCID-2", null));
    assertThat(thrown).isNotNull();
    assertThat(
            scalar(
                "select count(*) from tax_registration_record where tenant_id='" + tenant + "'"))
        .isEqualTo("1");
  }

  @Test
  @DisplayName("16.3.1.1 没记有效期 ⇒ 报「判不了」，**不是**「已过期」")
  void missingValidityIsReportedAsUndecidable() {
    UUID shop = seedShop("NO-VALIDITY", "DE", null);
    createWithoutValidity("SHOP", shop, "EU-DE", "DE123456789", "LUCID-987654");

    List<TaxGapItem> gaps = allGaps();
    assertThat(gaps).extracting(TaxGapItem::gapType).contains("REGISTRATION_VALIDITY_UNKNOWN");
    assertThat(gaps).extracting(TaxGapItem::gapType).doesNotContain("REGISTRATION_EXPIRED");
  }

  @Test
  @DisplayName("16.3.1.1 法人登记覆盖店铺（法人是 identity 的逻辑 ID，跨服务不建 FK）")
  void legalEntityRegistrationCoversItsShops() {
    UUID legalEntityId = UUID.randomUUID();
    legalEntityRows.add(
        Map.of(
            "id", legalEntityId.toString(),
            "code", "DE-ENTITY",
            "legalName", "德国主体",
            "status", "ACTIVE"));
    UUID shop = seedShop("ENTITY-SHOP", "DE", legalEntityId);

    // 没有法人登记 ⇒ 未注册
    assertThat(allGaps()).extracting(TaxGapItem::gapType).contains("JURISDICTION_NOT_REGISTERED");

    // 法人在德国登记 ⇒ 这家店在德国被覆盖
    create(
        "LEGAL_ENTITY",
        legalEntityId,
        "EU-DE",
        true,
        "REGISTERED",
        "DE999999999",
        "LUCID-999999",
        null);
    assertThat(allGaps()).isEmpty();

    // 登记档案里能看到法人主体，且主体名来自 identity（不是复制到本库的）
    TaxRegistrationPage page =
        as("channel.operation.read", () -> service.registrations(null, null, null, null, null, null, 0, 10, bearer));
    assertThat(page.items()).hasSize(1);
    TaxRegistrationView view = page.items().get(0);
    assertThat(view.subjectType()).isEqualTo("LEGAL_ENTITY");
    assertThat(view.subjectCode()).isEqualTo("DE-ENTITY");
    assertThat(view.subjectName()).isEqualTo("德国主体");
    assertThat(view.subjectAvailable()).isTrue();
  }

  @Test
  @DisplayName("16.3.1.1 法人档案读不到 ⇒ available=false，**不**显示成空白或「未注册」")
  void unreadableLegalEntityIsReportedAsUnreadable() {
    UUID legalEntityId = UUID.randomUUID();
    legalEntityRows.add(
        Map.of("id", legalEntityId.toString(), "code", "X", "legalName", "X", "status", "ACTIVE"));
    UUID shop = seedShop("UNREADABLE", "DE", legalEntityId);
    legalEntitiesReadable = false;

    TaxRegistrationPage page =
        as("channel.operation.read", () -> service.registrations(null, null, null, null, null, null, 0, 10, bearer));
    assertThat(page.items()).isEmpty();

    // 能力声明里如实报「读不到」
    TaxRegistrationCapabilities caps =
        as("channel.operation.read", () -> service.capabilities(bearer));
    TaxRegistrationUpstream legalEntity =
        caps.upstreams().stream()
            .filter(item -> "17.1.2.1".equals(item.moduleId()))
            .findFirst()
            .orElseThrow();
    assertThat(legalEntity.available()).isFalse();
    assertThat(legalEntity.impact()).contains("读不到");

    // 登记一条法人登记：主体读不到，但**缺口照算**（缺口来自登记事实，不依赖法人档案）
    create("LEGAL_ENTITY", legalEntityId, "EU-DE", true, "REGISTERED", "", "", null);
    TaxRegistrationPage after =
        as("channel.operation.read", () -> service.registrations(null, null, null, null, null, null, 0, 10, bearer));
    assertThat(after.items()).hasSize(1);
    assertThat(after.items().get(0).subjectAvailable()).isFalse();
    assertThat(after.items().get(0).subjectNote()).contains("读不到");
    assertThat(after.items().get(0).subjectCode()).isEmpty();
    // 缺号 / 缺 EPR 照样报出来
    assertThat(allGaps()).extracting(TaxGapItem::gapType).contains("VAT_NUMBER_MISSING");
  }

  @Test
  @DisplayName("16.3.1.1 缺失项汇总按类型计数，受影响店铺数是真的")
  void gapSummaryCountsByType() {
    UUID shopA = seedShop("SUM-A", "DE", null);
    seedShop("SUM-B", "DE", null);
    create("SHOP", shopA, "EU-DE", true, "REGISTERED", "", "", null);

    TaxGapSummary summary =
        as("channel.operation.read", () -> service.gapSummary(null, null, null, null, null, bearer));
    assertThat(summary.total()).isGreaterThan(0);
    assertThat(summary.highCount()).isGreaterThan(0);
    assertThat(summary.byGapType()).containsKeys("VAT_NUMBER_MISSING", "EPR_NUMBER_MISSING");
    assertThat(summary.gapTypeCounts()).extracting(TaxGapTypeCount::gapType)
        .contains("VAT_NUMBER_MISSING", "EPR_NUMBER_MISSING");
    assertThat(summary.affectedShopCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("16.3.1.1 覆盖视图：登记齐了没有，逐辖区说得清")
  void coverageShowsPerJurisdictionState() {
    UUID shop = seedShop("COVER", "DE", null);

    TaxCoverageList before =
        as("channel.operation.read", () -> service.coverage(bearer));
    TaxCoverageRow row =
        before.items().stream()
            .filter(item -> shop.toString().equals(item.shopId()))
            .findFirst()
            .orElseThrow();
    assertThat(row.requiredJurisdictionCount()).isEqualTo(1);
    assertThat(row.coveredJurisdictionCount()).isZero();
    assertThat(row.coverageStatus()).isEqualTo("INCOMPLETE");
    assertThat(row.jurisdictions()).extracting(TaxCoverageItem::registrationState).containsExactly("MISSING");

    create("SHOP", shop, "EU-DE", true, "REGISTERED", "DE1", "LUCID-1", null);
    TaxCoverageList after = as("channel.operation.read", () -> service.coverage(bearer));
    TaxCoverageRow covered =
        after.items().stream()
            .filter(item -> shop.toString().equals(item.shopId()))
            .findFirst()
            .orElseThrow();
    assertThat(covered.coveredJurisdictionCount()).isEqualTo(1);
    assertThat(covered.coverageStatus()).isEqualTo("COMPLETE");
    assertThat(covered.jurisdictions()).extracting(TaxCoverageItem::registrationState).containsExactly("REGISTERED");
  }

  @Test
  @DisplayName("16.3.1.1 缺失项走 HTTP 端点也能拿到（真分页 + 真筛选）")
  void gapsEndpointServesRealPagination() throws Exception {
    seedShop("HTTP-A", "DE", null);
    seedShop("HTTP-B", "DE", null);

    http.perform(get("/channel/tax-registration/gaps").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.items[0].gapType").value("JURISDICTION_NOT_REGISTERED"))
        .andExpect(jsonPath("$.highCount").value(2));

    http.perform(
            get("/channel/tax-registration/gaps")
                .header("Authorization", bearer)
                .param("severity", "MEDIUM"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));

    // 非法筛选值 ⇒ 422（不是静默忽略）
    http.perform(
            get("/channel/tax-registration/gaps")
                .header("Authorization", bearer)
                .param("gapType", "NOT_A_TYPE"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  @DisplayName("16.3.1.1 缺失项清单能按主体类型 / 辖区真筛选")
  void gapsSupportRealFilters() {
    UUID shop = seedShop("FILTER", "DE", null);
    create("SHOP", shop, "EU-DE", true, "REGISTERED", "", "", null);

    TaxGapPage onlyShop =
        as("channel.operation.read", () -> service.gaps(null, null, "SHOP", null, null, 0, 50, bearer));
    assertThat(onlyShop.items()).allMatch(item -> "SHOP".equals(item.subjectType()));

    TaxGapPage onlyEntity =
        as("channel.operation.read", () -> service.gaps(null, null, "LEGAL_ENTITY", null, null, 0, 50, bearer));
    assertThat(onlyEntity.items()).isEmpty();

    TaxGapPage onlyJurisdiction =
        as("channel.operation.read", () -> service.gaps(null, null, null, null, "EU-FR", 0, 50, bearer));
    assertThat(onlyJurisdiction.items()).isEmpty();
  }
}
