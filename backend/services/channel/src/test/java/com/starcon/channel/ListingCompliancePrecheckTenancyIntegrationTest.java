package com.starcon.channel;

import static com.starcon.channel.ListingComplianceModels.*;
import static com.starcon.channel.ListingEditorModels.*;
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
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 4.2.3.2 合规预检 的多租户三断言（AGENTS.md 硬性要求，缺一不可）。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（扫描记录 / 风险清单 / 词库都不可见）
 *   <li><b>伪造 tenant header 无效</b>（租户只来自已认证成员关系）
 *   <li><b>连接池归还后不串租户</b>（池压到 1，A→B→A 三轮）
 * </ol>
 *
 * <p>🔴 <b>按主键直查那条必须用 {@code channel_app} 连，不能用 Testcontainers 默认的超级用户。</b>
 * 超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
 * 那不是隔离被破坏，而是这个断言本身失效了。
 *
 * <p>🔴 本模块的三张表（{@code listing_compliance_word} / {@code listing_compliance_scan} /
 * {@code listing_compliance_risk}）全部是 {@code tenant_id NOT NULL + ENABLE/FORCE RLS}，
 * 词库是租户级资产、扫描与风险随店铺数据范围收敛。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingCompliancePrecheckTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");

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

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA, bearerB;
  UUID siteUs;
  UUID shopA, shopB;

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
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @BeforeEach
  void freshTenants() {
    a = UUID.randomUUID();
    b = UUID.randomUUID();
    bearerA = "Bearer t-a-" + a;
    bearerB = "Bearer t-b-" + b;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearerA, actor(user, a, grants));
    actors.put(bearerB, actor(user, b, grants));
    actorA = actors.get(bearerA);
    actorB = actors.get(bearerB);
    siteUs = marketplaceId(AMAZON, "US");
    shopA = seedShop(a, "TEN-A-1");
    shopB = seedShop(b, "TEN-B-1");
  }

  static UUID marketplaceId(UUID platformId, String code) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(
            "select id from marketplace where platform_id='" + platformId + "' and code='" + code + "'")) {
      return rs.next() ? UUID.fromString(rs.getString(1)) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    // 上游不可达：与本测试要断言的隔离无关，但用真上游更接近生产。
    r.add("CATALOG_URL", () -> "http://127.0.0.1:1");
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired ListingCompliancePrecheckService service;
  @Autowired ListingEditorService editor;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  static void execAsTenant(UUID tenantId, String sql) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以<b>应用角色 channel_app</b>（非超级用户）直查一行计数 —— 超级用户绕过 RLS 会让断言永远「通过」。 */
  static long countAsTenant(UUID tenantId, String sql) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(sql)) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static String scalar(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static AccessService.Access actor(UUID uid, UUID tenantId, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenantId, "Test", UUID.randomUUID(), null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  UUID seedShop(UUID tenantId, String code) {
    UUID id = UUID.randomUUID();
    execAsTenant(
        tenantId,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('"
            + tenantId
            + "','"
            + id
            + "','"
            + AMAZON
            + "','"
            + code
            + "','测试店铺 "
            + code
            + "')");
    execAsTenant(
        tenantId,
        "insert into shop_marketplace (tenant_id,shop_id,marketplace_id,status) values ('"
            + tenantId
            + "','"
            + id
            + "','"
            + siteUs
            + "','ACTIVE')");
    return id;
  }

  /** 在某个租户下建一份草稿 + 一条词 + 扫一次。 */
  record Fixture(UUID draftId, UUID scanId, UUID riskId, UUID wordId) {}

  Fixture seed(AccessService.Access who, UUID shopId, String bearer, String word) {
    UUID wordId =
        as(
                who,
                "channel.operation.create",
                () ->
                    service.saveWord(
                        null,
                        new ListingComplianceWordInput(
                            word, "PROHIBITED", "BLOCKING", "CRITICAL", "平台违禁词规则", "删掉", null, null, "")))
            .wordId();
    EditorDraftRow draft =
        as(
            who,
            "channel.operation.create",
            () ->
                editor.save(
                    new EditorDraftRequest(
                        shopId,
                        siteUs,
                        "en-US",
                        UUID.randomUUID(),
                        null,
                        "",
                        List.of(
                            new EditorFieldValue("TITLE", "含" + word + "的标题", null, null, null),
                            new EditorFieldValue("DESCRIPTION", "描述", null, null, null)),
                        "")));
    var result = as(who, "channel.operation.read", () -> service.scan(draft.draftId(), "MANUAL", bearer));
    UUID riskId = result.risks().isEmpty() ? null : result.risks().get(0).riskId();
    return new Fixture(draft.draftId(), result.scanId(), riskId, wordId);
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("4.2.3.2 断言一：两租户互不可见（扫描 / 风险 / 词库都不可见）")
  void tenantsAreIsolated() {
    Fixture fa = seed(actorA, shopA, bearerA, "违禁A");
    Fixture fb = seed(actorB, shopB, bearerB, "违禁B");

    // B 读不到 A 的扫描
    assertThat(as(actorB, "channel.operation.read", () -> service.scans(0, 50, null, null, null)).items())
        .as("B 的租户上下文里读不到 A 的扫描")
        .extracting(ListingComplianceScanRow::scanId)
        .containsExactly(fb.scanId());
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as(actorB, "channel.operation.read", () -> service.scanDetail(fa.scanId()))))
        .as("B 按 id 直取 A 的扫描必须失败")
        .isNotNull();

    // B 读不到 A 的词
    assertThat(as(actorB, "channel.operation.read", () -> service.words(0, 50, null, null, null, null, null, null)).items())
        .as("B 读不到 A 的违禁词")
        .extracting(ListingComplianceWordRow::wordId)
        .containsExactly(fb.wordId());

    // B 读不到 A 的发布闸门（按 A 的草稿 id 直查）
    var gateB = as(actorB, "channel.operation.read", () -> service.publishGate(fa.draftId()));
    assertThat(gateB.state()).as("B 在自己的上下文里查 A 的草稿 ⇒ 看不到任何扫描证据").isEqualTo("NOT_SCANNED");
    assertThat(gateB.blockers()).isEmpty();

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    assertThat(countAsTenant(b, "select count(*) from listing_compliance_scan where id='" + fa.scanId() + "'"))
        .as("B 的上下文里直查 A 的扫描必须读到零行").isZero();
    assertThat(countAsTenant(b, "select count(*) from listing_compliance_risk where id='" + fa.riskId() + "'"))
        .as("B 的上下文里直查 A 的风险必须读到零行").isZero();
    assertThat(countAsTenant(b, "select count(*) from listing_compliance_word where id='" + fa.wordId() + "'"))
        .as("B 的上下文里直查 A 的词必须读到零行").isZero();

    // 三张表都是 FORCE RLS
    for (String table : List.of("listing_compliance_word", "listing_compliance_scan", "listing_compliance_risk")) {
      assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS").isEqualTo("true");
      assertThat(scalar("select relrowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 ENABLE RLS").isEqualTo("true");
    }

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(countAsTenant(a, "select count(*) from listing_compliance_scan where id='" + fa.scanId() + "'"))
        .as("A 的上下文必须能直查 A 自己的扫描（证明上面那个 0 是隔离，不是查询失效）")
        .isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from listing_compliance_risk where id='" + fa.riskId() + "'"))
        .isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from listing_compliance_word where id='" + fa.wordId() + "'"))
        .isEqualTo(1);
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("4.2.3.2 断言二：伪造 X-Tenant-Id 头无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    Fixture fa = seed(actorA, shopA, bearerA, "违禁A");
    Fixture fb = seed(actorB, shopB, bearerB, "违禁B");

    // 带着 A 的身份、但把 X-Tenant-Id 伪造成 B ⇒ 必须仍然只看到 A 的数据
    http.perform(
            get("/channel/listing-compliance-precheck/scans")
                .header("Authorization", bearerA)
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].scanId").value(fa.scanId().toString()))
        .andExpect(jsonPath("$.items[?(@.scanId=='" + fb.scanId() + "')]").isEmpty());

    http.perform(
            get("/channel/listing-compliance-precheck/words")
                .header("Authorization", bearerA)
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].wordId").value(fa.wordId().toString()));

    // 反向也一样：B 的身份 + 伪造成 A 的头 ⇒ 看到的是 B 的
    http.perform(
            get("/channel/listing-compliance-precheck/scans")
                .header("Authorization", bearerB)
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].scanId").value(fb.scanId().toString()));
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("4.2.3.2 断言三：连接池压到 1 条后 A→B→A 三轮不串租户")
  void poolReturnDoesNotLeakTenant() {
    Fixture fa = seed(actorA, shopA, bearerA, "违禁A");
    Fixture fb = seed(actorB, shopB, bearerB, "违禁B");

    for (int round = 0; round < 3; round++) {
      assertThat(as(actorA, "channel.operation.read", () -> service.scans(0, 50, null, null, null)).items())
          .as("第 " + round + " 轮：A 只看到自己的扫描")
          .extracting(ListingComplianceScanRow::scanId)
          .containsExactly(fa.scanId());
      assertThat(as(actorA, "channel.operation.read", () -> service.words(0, 50, null, null, null, null, null, null)).items())
          .as("第 " + round + " 轮：A 只看到自己的词")
          .extracting(ListingComplianceWordRow::wordId)
          .containsExactly(fa.wordId());

      assertThat(as(actorB, "channel.operation.read", () -> service.scans(0, 50, null, null, null)).items())
          .as("第 " + round + " 轮：B 只看到自己的扫描")
          .extracting(ListingComplianceScanRow::scanId)
          .containsExactly(fb.scanId());
      assertThat(as(actorB, "channel.operation.read", () -> service.words(0, 50, null, null, null, null, null, null)).items())
          .as("第 " + round + " 轮：B 只看到自己的词")
          .extracting(ListingComplianceWordRow::wordId)
          .containsExactly(fb.wordId());
    }
  }
}
