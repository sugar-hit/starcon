package com.starcon.channel;

import static com.starcon.channel.ComplianceInterceptModels.*;
import static com.starcon.channel.ComplianceRuleModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.channel.ListingPublishJobModels.ListingPublishContentSnapshot;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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
 * 16.2.1.2 拦截执行的集成测试（**真实 PostgreSQL 16**，Testcontainers）。
 *
 * <p>🔴 这组用例守的是本模块最容易做丢的十四件事：
 *
 * <ol>
 *   <li><b>规则集为空 ⇒ 判不了，不是通过</b>（结论 UNDECIDABLE + releaseGranted=false）；</li>
 *   <li><b>命中违禁词 ⇒ 阻断</b>，逐条原因带原因码 / 规则 id / 当时生效的版本 id；</li>
 *   <li><b>警告级 ⇒ WARN 但放行</b>（blocked=false + releaseGranted=true）；</li>
 *   <li><b>判定绑定当时生效的那一版</b>：规则发新版本后，历史判定行仍指向旧版本；</li>
 *   <li><b>非词条类规则没有要求状态 ⇒ 判不了</b>（不是通过，也不是凭空阻断）；</li>
 *   <li><b>要求未满足 ⇒ 阻断</b>（依据必填）；</li>
 *   <li><b>豁免要有理由 + 有效期</b>（缺一个都 422）；</li>
 *   <li><b>申请人不能审批自己</b>；</li>
 *   <li><b>已批准的豁免参与判定 ⇒ 明确标出「因豁免放行」并指向豁免单 id</b>；</li>
 *   <li><b>豁免过期即失效</b>；</li>
 *   <li><b>判定凭据只追加</b>（UPDATE / DELETE 被 DB 拒）；豁免单终态不可回退、不可删；</li>
 *   <li><b>原因码具名</b>（DB 层 CHECK 兜底）；<b>不放行必须有原因</b>（延迟触发器）；</li>
 *   <li><b>刊登派发路径真的读服务端结论</b>（🔴 阻断在服务端强制，前端绕不过）；</li>
 *   <li><b>timestamptz 往返不偏 −28800s</b>；真分页真筛选；401 / 404 / 422。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ComplianceInterceptIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");

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
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * 🔴 关掉 identity 这台静态桩。
   *
   * <p>它是<b>静态</b>桩：不起就停 ⇒ 端口被占住整整一轮 JVM 生命周期。
   */
  @AfterAll
  static void stopStubs() {
    if (identity != null) identity.stop(0);
  }

  @BeforeEach
  void freshTenant() {
    tenant = UUID.randomUUID();
    bearer = "Bearer t-" + tenant;
    otherBearer = "Bearer other-" + tenant;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearer, actor(user, tenant, grants));
    actors.put(otherBearer, actor(UUID.randomUUID(), tenant, grants));
    who = actors.get(bearer);
    other = actors.get(otherBearer);
  }

  UUID tenant;
  AccessService.Access who;
  AccessService.Access other;
  String bearer;
  String otherBearer;

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

  @Autowired ComplianceInterceptService service;
  @Autowired ComplianceRuleService rules;
  @Autowired ListingPublishJobService publishes;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String permission, Supplier<T> work) {
    return access.run(who, permission, work);
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

  /** DB 侧的时刻（**同源**，不用 JVM 墙钟）。 */
  OffsetDateTime dbNow() {
    String raw = scalar("select now()");
    return raw == null ? null : OffsetDateTime.parse(raw.replace(' ', 'T'));
  }

  UUID marketplaceId(UUID platformId, String code) {
    String raw =
        scalar(
            "select id from marketplace where platform_id = '"
                + platformId
                + "' and code = '"
                + code
                + "'");
    return raw == null ? null : UUID.fromString(raw);
  }

  UUID seedShop(String code, UUID platformId, UUID marketplace) {
    UUID id = UUID.randomUUID();
    execAsTenant(
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenant + "','" + id
            + "','" + platformId + "','" + code + "','测试店铺 " + code + "')");
    if (marketplace != null) {
      execAsTenant(
          "insert into shop_marketplace (tenant_id,shop_id,marketplace_id,status) values ('" + tenant
              + "','" + id + "','" + marketplace + "','ACTIVE')");
    }
    return id;
  }

  /** 建一条规则（走 16.2.1.1 的真实服务，**上游数据是真的**）。 */
  String seedRule(
      String code, String type, String severity, List<String> terms, UUID shopId, UUID platformId) {
    String ruleId =
        as(
                "channel.operation.create",
                () ->
                    rules.createRule(new ComplianceRuleCreateInput(code, type, "测试规则 " + code, "说明"))
                        .rule()
                        .ruleId());
    as(
        "channel.operation.create",
        () ->
            rules.createVersion(
                ruleId,
                new ComplianceRuleVersionInput(
                    severity,
                    "版本标题 " + code,
                    "版本说明",
                    null,
                    "首版",
                    terms.stream().map(t -> new ComplianceRuleTermInput(t, "GENERAL", "")).toList(),
                    null,
                    List.of(
                        new ComplianceRuleScopeInput(
                            platformId.toString(), null, shopId.toString())))));
    return ruleId;
  }

  ComplianceInterceptEvaluateInput evaluateInput(
      String subjectId, UUID shopId, UUID platformId, UUID marketplace, List<String> titles) {
    List<ComplianceInterceptFieldInput> fields = new ArrayList<>();
    for (String title : titles) {
      fields.add(new ComplianceInterceptFieldInput("TITLE", title));
    }
    return new ComplianceInterceptEvaluateInput(
        "LISTING_PUBLISH",
        "PRODUCT",
        subjectId,
        shopId.toString(),
        platformId.toString(),
        marketplace == null ? null : marketplace.toString(),
        fields,
        List.of());
  }

  // ==================================================================
  // 一、能力声明与值域
  // ==================================================================

  @Test
  @DisplayName("16.2.1.2 能力声明：结论四值 + 未交付下游 available=false + ownerModule")
  void capabilitiesStateDomainsAndGaps() {
    ComplianceInterceptCapabilities caps = as("channel.operation.read", () -> service.capabilities());

    assertThat(caps.conclusions()).containsExactly("PASS", "WARN", "BLOCK", "UNDECIDABLE");
    assertThat(caps.scenes()).containsExactly("LISTING_PUBLISH", "SHIPMENT", "WITHDRAWAL");
    assertThat(caps.subjectTypes()).containsExactly("PRODUCT", "ORDER", "SHOP");
    assertThat(caps.conclusionNote()).contains("判不了").contains("绝不返回 PASS");

    // 🔴 8.3.4.1 发货：跨服务 ⇒ 只出结论，不跨服务 SQL。
    ComplianceInterceptCapabilityGap shipment =
        caps.gaps().stream().filter(g -> "8.3.4.1".equals(g.moduleId())).findFirst().orElseThrow();
    assertThat(shipment.available()).isFalse();
    assertThat(shipment.ownerModule()).contains("8.3.4.1");
    assertThat(shipment.impact()).contains("不跨服务 SQL");

    // 🔴 12 提现：finance 服务未落地 ⇒ 不声称已拦截提现。
    ComplianceInterceptCapabilityGap withdrawal =
        caps.gaps().stream().filter(g -> "12".equals(g.moduleId())).findFirst().orElseThrow();
    assertThat(withdrawal.available()).isFalse();
    assertThat(withdrawal.ownerModule()).contains("12");
    assertThat(withdrawal.impact()).contains("不声称已拦截提现");

    // 4.3.1.1 已交付（同服务）⇒ 闸门能落在刊登路径上。
    ComplianceInterceptCapabilityGap publish =
        caps.gaps().stream().filter(g -> "4.3.1.1".equals(g.moduleId())).findFirst().orElseThrow();
    assertThat(publish.available()).isTrue();
  }

  // ==================================================================
  // 二、判定：判不了 ≠ 通过
  // ==================================================================

  @Test
  @DisplayName("16.2.1.2 🔴 规则集为空 ⇒ 判不了（不是通过），并给出原因")
  void emptyRuleSetIsUndecidableNotPass() {
    UUID marketplace = marketplaceId(AMAZON, "US");
    UUID shop = seedShop("AMZ-" + UUID.randomUUID(), AMAZON, marketplace);

    ComplianceInterceptDecision decision =
        as(
            "channel.operation.create",
            () ->
                service.evaluate(
                    evaluateInput(UUID.randomUUID().toString(), shop, AMAZON, marketplace, List.of("普通标题")),
                    who.userId()));

    assertThat(decision.conclusion()).as("规则集为空不能返回 PASS").isEqualTo("UNDECIDABLE");
    assertThat(decision.releaseGranted()).as("判不了不能放行").isFalse();
    assertThat(decision.blocked()).as("判不了不等于被阻断").isFalse();
    assertThat(decision.reasons()).isNotEmpty();
    assertThat(decision.reasons().get(0).reasonCode()).isEqualTo("RULE_SET_EMPTY");
    assertThat(decision.reasons().get(0).undecidable()).isTrue();
    assertThat(decision.remedies()).isNotEmpty();
  }

  @Test
  @DisplayName("16.2.1.2 🔴 命中违禁词 ⇒ 阻断，逐条原因带原因码 / 规则 id / 当时生效的版本 id")
  void wordTermHitBlocks() {
    UUID marketplace = marketplaceId(AMAZON, "US");
    UUID shop = seedShop("AMZ-" + UUID.randomUUID(), AMAZON, marketplace);
    String ruleId = seedRule("PROHIBITED-1", "PROHIBITED_WORD", "BLOCKING", List.of("违禁词"), shop, AMAZON);

    ComplianceInterceptDecision decision =
        as(
            "channel.operation.create",
            () ->
                service.evaluate(
                    evaluateInput(
                        UUID.randomUUID().toString(),
                        shop,
                        AMAZON,
                        marketplace,
                        List.of("这是违禁词商品")),
                    who.userId()));

    assertThat(decision.conclusion()).isEqualTo("BLOCK");
    assertThat(decision.blocked()).isTrue();
    assertThat(decision.releaseGranted()).isFalse();
    assertThat(decision.ruleCount()).isEqualTo(1);
    assertThat(decision.blockingReasonCount()).isEqualTo(1);

    ComplianceInterceptReasonRow reason = decision.reasons().get(0);
    assertThat(reason.reasonCode()).isEqualTo("WORD_TERM_HIT");
    assertThat(reason.ruleId()).isEqualTo(ruleId);
    assertThat(reason.ruleVersionId()).as("必须指向当时生效的那一版").isNotBlank();
    assertThat(reason.matchedTerm()).isEqualTo("违禁词");
    assertThat(reason.fieldKey()).isEqualTo("TITLE");
    assertThat(reason.reasonText()).contains("违禁词");
    assertThat(reason.waived()).isFalse();
  }

  @Test
  @DisplayName("16.2.1.2 警告级命中 ⇒ WARN：可以放行但要带原因")
  void warningSeverityWarnsButReleases() {
    UUID marketplace = marketplaceId(AMAZON, "US");
    UUID shop = seedShop("AMZ-" + UUID.randomUUID(), AMAZON, marketplace);
    seedRule("SUPERLATIVE-1", "SUPERLATIVE_WORD", "WARNING", List.of("极限词"), shop, AMAZON);

    ComplianceInterceptDecision decision =
        as(
            "channel.operation.create",
            () ->
                service.evaluate(
                    evaluateInput(
                        UUID.randomUUID().toString(), shop, AMAZON, marketplace, List.of("这是极限词商品")),
                    who.userId()));

    assertThat(decision.conclusion()).isEqualTo("WARN");
    assertThat(decision.blocked()).isFalse();
    assertThat(decision.releaseGranted()).isTrue();
    assertThat(decision.reasons().get(0).severity()).isEqualTo("WARNING");
  }

  @Test
  @DisplayName("16.2.1.2 🔴 判定绑定当时生效的那一版：规则发新版本后，历史判定仍指向旧版本")
  void decisionFreezesTheVersionInForce() {
    UUID marketplace = marketplaceId(AMAZON, "US");
    UUID shop = seedShop("AMZ-" + UUID.randomUUID(), AMAZON, marketplace);
    String ruleId = seedRule("PROHIBITED-2", "PROHIBITED_WORD", "BLOCKING", List.of("违禁词"), shop, AMAZON);

    ComplianceInterceptDecision first =
        as(
            "channel.operation.create",
            () ->
                service.evaluate(
                    evaluateInput(
                        UUID.randomUUID().toString(), shop, AMAZON, marketplace, List.of("违禁词商品")),
                    who.userId()));
    String frozenVersion = first.reasons().get(0).ruleVersionId();

    // 🔴 规则发新版本（16.2.1.1 的唯一改动入口）。
    as(
        "channel.operation.create",
        () ->
            rules.createVersion(
                ruleId,
                new ComplianceRuleVersionInput(
                    "WARNING",
                    "新版本标题",
                    "新版本说明",
                    null,
                    "改了一版",
                    List.of(new ComplianceRuleTermInput("违禁词", "GENERAL", "")),
                    null,
                    List.of(
                        new ComplianceRuleScopeInput(
                            AMAZON.toString(), null, shop.toString())))));

    // 历史判定行不受影响（**规则变更不追溯改变历史判定**）。
    ComplianceInterceptDecision reread = as("channel.operation.read", () -> service.decision(first.decisionId()));
    assertThat(reread.reasons().get(0).ruleVersionId()).isEqualTo(frozenVersion);
    assertThat(reread.conclusion()).isEqualTo("BLOCK");
    assertThat(reread.reasons().get(0).severity()).isEqualTo("BLOCKING");
  }

  // ==================================================================
  // 三、非词条类规则：要求状态
  // ==================================================================

  @Test
  @DisplayName("16.2.1.2 🔴 认证类规则没有要求状态 ⇒ 判不了（不是通过）")
  void requirementRuleWithoutStateIsUndecidable() {
    UUID marketplace = marketplaceId(AMAZON, "US");
    UUID shop = seedShop("AMZ-" + UUID.randomUUID(), AMAZON, marketplace);
    seedRule("CERT-1", "CERTIFICATION_REQUIRED", "BLOCKING", List.of(), shop, AMAZON);

    ComplianceInterceptDecision decision =
        as(
            "channel.operation.create",
            () ->
                service.evaluate(
                    evaluateInput(
                        UUID.randomUUID().toString(), shop, AMAZON, marketplace, List.of("普通标题")),
                    who.userId()));

    assertThat(decision.conclusion()).isEqualTo("UNDECIDABLE");
    assertThat(decision.releaseGranted()).isFalse();
    assertThat(decision.reasons().get(0).reasonCode()).isEqualTo("REQUIREMENT_UNRESOLVED");
    assertThat(decision.reasons().get(0).ruleType()).isEqualTo("CERTIFICATION_REQUIRED");
  }

  @Test
  @DisplayName("16.2.1.2 要求未满足 ⇒ 阻断，且依据必填")
  void requirementNotSatisfiedBlocks() {
    UUID marketplace = marketplaceId(AMAZON, "US");
    UUID shop = seedShop("AMZ-" + UUID.randomUUID(), AMAZON, marketplace);
    seedRule("CERT-2", "CERTIFICATION_REQUIRED", "BLOCKING", List.of(), shop, AMAZON);

    ComplianceInterceptEvaluateInput input =
        new ComplianceInterceptEvaluateInput(
            "LISTING_PUBLISH",
            "PRODUCT",
            UUID.randomUUID().toString(),
            shop.toString(),
            AMAZON.toString(),
            marketplace.toString(),
            List.of(new ComplianceInterceptFieldInput("TITLE", "普通标题")),
            List.of(
                new ComplianceInterceptRequirementInput(
                    "CERT-2", "UNSATISFIED", "认证档案里没有对应类型的有效认证")));

    ComplianceInterceptDecision decision =
        as("channel.operation.create", () -> service.evaluate(input, who.userId()));

    assertThat(decision.conclusion()).isEqualTo("BLOCK");
    assertThat(decision.blocked()).isTrue();
    assertThat(decision.reasons().get(0).reasonCode()).isEqualTo("REQUIREMENT_NOT_SATISFIED");
    assertThat(decision.reasons().get(0).reasonText()).contains("认证档案");

    // 依据必填：不给依据直接 422。
    ComplianceInterceptEvaluateInput noEvidence =
        new ComplianceInterceptEvaluateInput(
            "LISTING_PUBLISH",
            "PRODUCT",
            UUID.randomUUID().toString(),
            shop.toString(),
            AMAZON.toString(),
            marketplace.toString(),
            List.of(),
            List.of(new ComplianceInterceptRequirementInput("CERT-2", "UNSATISFIED", "")));
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as("channel.operation.create", () -> service.evaluate(noEvidence, who.userId()))))
        .isNotNull();
  }

  // ==================================================================
  // 四、豁免：审批 + 理由 + 有效期
  // ==================================================================

  @Test
  @DisplayName("16.2.1.2 🔴 豁免必须有理由与有效期（缺一个都 422）")
  void exemptionNeedsReasonAndWindow() {
    UUID marketplace = marketplaceId(AMAZON, "US");
    UUID shop = seedShop("AMZ-" + UUID.randomUUID(), AMAZON, marketplace);
    String ruleId = seedRule("PROHIBITED-3", "PROHIBITED_WORD", "BLOCKING", List.of("违禁词"), shop, AMAZON);
    String subjectId = UUID.randomUUID().toString();

    // 理由为空 ⇒ 422
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.create",
                        () ->
                            service.applyExemption(
                                new ComplianceInterceptExemptionInput(
                                    "LISTING_PUBLISH",
                                    "PRODUCT",
                                    subjectId,
                                    ruleId,
                                    "   ",
                                    dbNow(),
                                    dbNow().plusDays(7)),
                                who.userId()))))
        .isNotNull();

    // 有效期缺失 ⇒ 422
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.create",
                        () ->
                            service.applyExemption(
                                new ComplianceInterceptExemptionInput(
                                    "LISTING_PUBLISH",
                                    "PRODUCT",
                                    subjectId,
                                    ruleId,
                                    "理由",
                                    dbNow(),
                                    null),
                                who.userId()))))
        .isNotNull();

    // 规则 id 缺失 ⇒ 422（一张豁免只针对一条规则）
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.create",
                        () ->
                            service.applyExemption(
                                new ComplianceInterceptExemptionInput(
                                    "LISTING_PUBLISH",
                                    "PRODUCT",
                                    subjectId,
                                    null,
                                    "理由",
                                    dbNow(),
                                    dbNow().plusDays(7)),
                                who.userId()))))
        .isNotNull();
  }

  @Test
  @DisplayName("16.2.1.2 🔴 申请人不能审批自己")
  void applicantCannotApproveOwnExemption() {
    UUID marketplace = marketplaceId(AMAZON, "US");
    UUID shop = seedShop("AMZ-" + UUID.randomUUID(), AMAZON, marketplace);
    String ruleId = seedRule("PROHIBITED-4", "PROHIBITED_WORD", "BLOCKING", List.of("违禁词"), shop, AMAZON);

    ComplianceInterceptExemptionRow applied =
        as(
            "channel.operation.create",
            () ->
                service.applyExemption(
                    new ComplianceInterceptExemptionInput(
                        "LISTING_PUBLISH",
                        "PRODUCT",
                        UUID.randomUUID().toString(),
                        ruleId,
                        "该商品已与平台沟通过，临时放行",
                        dbNow(),
                        dbNow().plusDays(7)),
                    who.userId()));
    assertThat(applied.status()).isEqualTo("PENDING");

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.update",
                        () ->
                            service.approveExemption(
                                applied.exemptionId(), "同意", who.userId()))))
        .as("申请人审批自己必须被拒")
        .isNotNull();

    // 审批意见为空 ⇒ 422
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.update",
                        () ->
                            service.approveExemption(applied.exemptionId(), "  ", other.userId()))))
        .isNotNull();
  }

  @Test
  @DisplayName("16.2.1.2 🔴 已批准的豁免参与判定 ⇒ 明确标出「因豁免放行」并指向豁免单 id")
  void approvedExemptionWaivesTheBlock() {
    UUID marketplace = marketplaceId(AMAZON, "US");
    UUID shop = seedShop("AMZ-" + UUID.randomUUID(), AMAZON, marketplace);
    String ruleId = seedRule("PROHIBITED-5", "PROHIBITED_WORD", "BLOCKING", List.of("违禁词"), shop, AMAZON);
    String subjectId = UUID.randomUUID().toString();

    ComplianceInterceptExemptionRow applied =
        as(
            "channel.operation.create",
            () ->
                service.applyExemption(
                    new ComplianceInterceptExemptionInput(
                        "LISTING_PUBLISH",
                        "PRODUCT",
                        subjectId,
                        ruleId,
                        "平台已口头同意",
                        dbNow(),
                        dbNow().plusDays(7)),
                    who.userId()));
    ComplianceInterceptExemptionRow approved =
        as(
            "channel.operation.update",
            () -> service.approveExemption(applied.exemptionId(), "已核实，同意放行", other.userId()));
    assertThat(approved.status()).isEqualTo("APPROVED");
    assertThat(approved.approverId()).isEqualTo(other.userId().toString());
    assertThat(approved.effective()).isTrue();

    ComplianceInterceptDecision decision =
        as(
            "channel.operation.create",
            () ->
                service.evaluate(
                    evaluateInput(subjectId, shop, AMAZON, marketplace, List.of("这是违禁词商品")),
                    who.userId()));

    assertThat(decision.conclusion()).as("豁免放行后不再阻断").isEqualTo("PASS");
    assertThat(decision.blocked()).isFalse();
    assertThat(decision.waivedReasonCount()).isEqualTo(1);
    assertThat(decision.reasons().get(0).waived()).isTrue();
    assertThat(decision.reasons().get(0).exemptionId()).isEqualTo(applied.exemptionId());
    assertThat(decision.reasons().get(0).reasonText()).contains("因豁免放行");
    assertThat(decision.exemptions()).isNotEmpty();
    assertThat(decision.exemptions().get(0).exemptionId()).isEqualTo(applied.exemptionId());
  }

  @Test
  @DisplayName("16.2.1.2 🔴 豁免过期即失效（阻断回来）")
  void expiredExemptionNoLongerWaives() {
    UUID marketplace = marketplaceId(AMAZON, "US");
    UUID shop = seedShop("AMZ-" + UUID.randomUUID(), AMAZON, marketplace);
    String ruleId = seedRule("PROHIBITED-6", "PROHIBITED_WORD", "BLOCKING", List.of("违禁词"), shop, AMAZON);
    String subjectId = UUID.randomUUID().toString();

    // 一张**已经过期**的已批准豁免（服务层不批过期豁免，这里直接写库验证判定侧的口径）。
    UUID exemptionId = UUID.randomUUID();
    execAsTenant(
        "insert into compliance_intercept_exemption"
            + " (id,tenant_id,scene,subject_type,subject_id,rule_id,status,applicant_id,"
            + " applicant_reason,approver_id,decision_note,valid_from,valid_to,decided_at)"
            + " values ('" + exemptionId + "','" + tenant + "','LISTING_PUBLISH','PRODUCT','" + subjectId
            + "','" + ruleId + "','APPROVED','" + user + "','历史理由','" + UUID.randomUUID()
            + "','当时批准', now() - interval '30 days', now() - interval '1 day', now() - interval '31 days')");

    ComplianceInterceptDecision decision =
        as(
            "channel.operation.create",
            () ->
                service.evaluate(
                    evaluateInput(subjectId, shop, AMAZON, marketplace, List.of("这是违禁词商品")),
                    who.userId()));

    assertThat(decision.conclusion()).as("豁免过期 ⇒ 阻断回来").isEqualTo("BLOCK");
    assertThat(decision.reasons().get(0).waived()).isFalse();
  }

  // ==================================================================
  // 五、DB 层不变量
  // ==================================================================

  @Test
  @DisplayName("16.2.1.2 🔴 判定凭据只追加：直接改 / 删被 DB 拒")
  void decisionIsAppendOnly() {
    UUID marketplace = marketplaceId(AMAZON, "US");
    UUID shop = seedShop("AMZ-" + UUID.randomUUID(), AMAZON, marketplace);
    seedRule("PROHIBITED-7", "PROHIBITED_WORD", "BLOCKING", List.of("违禁词"), shop, AMAZON);

    ComplianceInterceptDecision decision =
        as(
            "channel.operation.create",
            () ->
                service.evaluate(
                    evaluateInput(
                        UUID.randomUUID().toString(), shop, AMAZON, marketplace, List.of("违禁词商品")),
                    who.userId()));

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execAsTenant(
                        "update compliance_intercept_decision set conclusion='PASS' where id='"
                            + decision.decisionId() + "'")))
        .as("判定凭据不可改写")
        .isNotNull();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execAsTenant(
                        "delete from compliance_intercept_decision where id='"
                            + decision.decisionId() + "'")))
        .as("判定凭据不可删除")
        .isNotNull();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execAsTenant(
                        "update compliance_intercept_reason set reason_text='改过了' where decision_id='"
                            + decision.decisionId() + "'")))
        .as("原因行不可改写")
        .isNotNull();
  }

  @Test
  @DisplayName("16.2.1.2 🔴 豁免单终态不可回退、不可删除")
  void exemptionStateIsTerminal() {
    UUID marketplace = marketplaceId(AMAZON, "US");
    UUID shop = seedShop("AMZ-" + UUID.randomUUID(), AMAZON, marketplace);
    String ruleId = seedRule("PROHIBITED-8", "PROHIBITED_WORD", "BLOCKING", List.of("违禁词"), shop, AMAZON);

    ComplianceInterceptExemptionRow applied =
        as(
            "channel.operation.create",
            () ->
                service.applyExemption(
                    new ComplianceInterceptExemptionInput(
                        "LISTING_PUBLISH",
                        "PRODUCT",
                        UUID.randomUUID().toString(),
                        ruleId,
                        "理由",
                        dbNow(),
                        dbNow().plusDays(7)),
                    who.userId()));
    as(
        "channel.operation.update",
        () -> service.approveExemption(applied.exemptionId(), "同意", other.userId()));

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execAsTenant(
                        "update compliance_intercept_exemption set status='PENDING', approver_id=null,"
                            + " decided_at=null, decision_note='' where id='" + applied.exemptionId() + "'")))
        .as("终态不许回退")
        .isNotNull();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execAsTenant(
                        "delete from compliance_intercept_exemption where id='"
                            + applied.exemptionId() + "'")))
        .as("豁免单不可删除")
        .isNotNull();
  }

  @Test
  @DisplayName("16.2.1.2 🔴 原因码具名：DB 层 CHECK 兜底（报错要能看出是哪条口径）")
  void reasonCodeCheckIsNamed() {
    UUID marketplace = marketplaceId(AMAZON, "US");
    UUID shop = seedShop("AMZ-" + UUID.randomUUID(), AMAZON, marketplace);
    seedRule("PROHIBITED-9", "PROHIBITED_WORD", "BLOCKING", List.of("违禁词"), shop, AMAZON);

    ComplianceInterceptDecision decision =
        as(
            "channel.operation.create",
            () ->
                service.evaluate(
                    evaluateInput(
                        UUID.randomUUID().toString(), shop, AMAZON, marketplace, List.of("违禁词商品")),
                    who.userId()));

    Throwable error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                execAsTenant(
                    "insert into compliance_intercept_reason"
                        + " (id,tenant_id,decision_id,reason_code,reason_text)"
                        + " values ('" + UUID.randomUUID() + "','" + tenant + "','"
                        + decision.decisionId() + "','WHATEVER','随手写的')"));
    assertThat(error).isNotNull();
    assertThat(error.getMessage()).contains("compliance_intercept_reason_code_chk");
  }

  @Test
  @DisplayName("16.2.1.2 🔴 不放行必须有原因：BLOCK 判定零原因被延迟触发器拒")
  void blockingDecisionMustHaveReason() {
    UUID marketplace = marketplaceId(AMAZON, "US");
    UUID shop = seedShop("AMZ-" + UUID.randomUUID(), AMAZON, marketplace);

    Throwable error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                execAsTenant(
                    "insert into compliance_intercept_decision"
                        + " (id,tenant_id,scene,subject_type,subject_id,platform_id,conclusion,blocked,"
                        + " release_granted,exemption_ids,subject_snapshot,decided_at)"
                        + " values ('" + UUID.randomUUID() + "','" + tenant
                        + "','LISTING_PUBLISH','PRODUCT','sku-1','" + AMAZON
                        + "','BLOCK',true,false,'[]'::jsonb,'{}'::jsonb, now())"));
    assertThat(error).as("阻断却没有原因 ⇒ 必须被拒").isNotNull();
    assertThat(error.getMessage()).contains("至少有一条原因");
  }

  @Test
  @DisplayName("16.2.1.2 四张表都 FORCE RLS，跨服务只读角色拿不到")
  void tablesAreRlsProtected() {
    for (String table :
        List.of(
            "compliance_intercept_policy",
            "compliance_intercept_decision",
            "compliance_intercept_exemption",
            "compliance_intercept_reason")) {
      assertThat(
              scalar(
                  "select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS")
          .isEqualTo("true");
    }
    assertThat(
            scalar(
                "select count(*) from information_schema.role_table_grants"
                    + " where grantee='starcon_app' and table_name like 'compliance_intercept%'"))
        .as("starcon_app 拿不到本模块的任何表")
        .isEqualTo("0");
  }

  // ==================================================================
  // 六、策略 + 🔴 刊登派发路径上的服务端闸门
  // ==================================================================

  @Test
  @DisplayName("16.2.1.2 拦截默认不生效；开启后刊登派发路径真的读服务端结论")
  void publishDispatchReadsServerSideConclusion() {
    UUID marketplace = marketplaceId(AMAZON, "US");
    UUID shop = seedShop("AMZ-" + UUID.randomUUID(), AMAZON, marketplace);
    seedRule("PROHIBITED-10", "PROHIBITED_WORD", "BLOCKING", List.of("违禁词"), shop, AMAZON);
    UUID skuId = UUID.randomUUID();

    // ---- 默认不生效：不拦，也不产生判定凭据 ----
    assertThat(
            as("channel.operation.read", () -> service.policy()).items().stream()
                .filter(row -> "LISTING_PUBLISH".equals(row.scene()))
                .findFirst()
                .orElseThrow()
                .enabled())
        .as("拦截默认不生效")
        .isFalse();

    UUID jobId = seedPublishJob(shop, marketplace, skuId, "这是违禁词商品");
    as("channel.operation.update", () -> publishes.dispatch(jobId.toString()));

    assertThat(countRows("select count(*) from compliance_intercept_decision where tenant_id='" + tenant + "'"))
        .as("没开拦截就不该产生判定凭据")
        .isZero();

    // ---- 开启拦截：派发前读服务端结论，命中阻断 ⇒ 目标失败并写明原因 ----
    as(
        "channel.operation.update",
        () ->
            service.savePolicy(
                "LISTING_PUBLISH",
                new ComplianceInterceptPolicyInput("true", "BLOCK", ""),
                who.userId()));

    UUID blockedJob = seedPublishJob(shop, marketplace, skuId, "这是违禁词商品");
    as("channel.operation.update", () -> publishes.dispatch(blockedJob.toString()));

    assertThat(
            scalar(
                "select state from listing_publish_job_target where job_id='" + blockedJob + "'"))
        .as("命中阻断 ⇒ 目标被标成失败（服务端强制）")
        .isEqualTo("FAILED");
    assertThat(
            scalar(
                "select failure_reason from listing_publish_job_target where job_id='"
                    + blockedJob + "'"))
        .as("失败原因要写明是合规拦截")
        .contains("合规校验");

    assertThat(countRows("select count(*) from compliance_intercept_decision where tenant_id='" + tenant + "'"))
        .as("判定凭据必须留下（append-only 的凭据）")
        .isEqualTo(1);
    assertThat(
            scalar("select conclusion from compliance_intercept_decision where tenant_id='" + tenant + "'"))
        .isEqualTo("BLOCK");
  }

  @Test
  @DisplayName("16.2.1.2 豁免获批后，刊登派发路径放行")
  void publishDispatchReleasesAfterExemption() {
    UUID marketplace = marketplaceId(AMAZON, "US");
    UUID shop = seedShop("AMZ-" + UUID.randomUUID(), AMAZON, marketplace);
    String ruleId = seedRule("PROHIBITED-11", "PROHIBITED_WORD", "BLOCKING", List.of("违禁词"), shop, AMAZON);
    UUID skuId = UUID.randomUUID();

    as(
        "channel.operation.update",
        () ->
            service.savePolicy(
                "LISTING_PUBLISH",
                new ComplianceInterceptPolicyInput("true", "BLOCK", ""),
                who.userId()));

    ComplianceInterceptExemptionRow applied =
        as(
            "channel.operation.create",
            () ->
                service.applyExemption(
                    new ComplianceInterceptExemptionInput(
                        "LISTING_PUBLISH",
                        "PRODUCT",
                        skuId.toString(),
                        ruleId,
                        "平台已同意",
                        dbNow(),
                        dbNow().plusDays(7)),
                    who.userId()));
    as(
        "channel.operation.update",
        () -> service.approveExemption(applied.exemptionId(), "同意", other.userId()));

    UUID jobId = seedPublishJob(shop, marketplace, skuId, "这是违禁词商品");
    as("channel.operation.update", () -> publishes.dispatch(jobId.toString()));

    String failure = scalar("select failure_reason from listing_publish_job_target where job_id='" + jobId + "'");
    assertThat(failure == null ? "" : failure)
        .as("豁免放行 ⇒ 不再被合规拦下")
        .doesNotContain("合规校验");
    assertThat(scalar("select conclusion from compliance_intercept_decision where tenant_id='" + tenant + "'"))
        .as("判定结论是 PASS，但**因豁免放行**这件事必须留痕")
        .isEqualTo("PASS");
    assertThat(countRows("select count(*) from compliance_intercept_reason where waived=true and tenant_id='" + tenant + "'"))
        .isEqualTo(1);
  }

  /** 建一个刊登作业 + 一个目标（内容快照里带标题）。 */
  UUID seedPublishJob(UUID shop, UUID marketplace, UUID skuId, String title) {
    UUID jobId = UUID.randomUUID();
    UUID targetId = UUID.randomUUID();
    String snapshot =
        writeJson(
            new ListingPublishContentSnapshot(
                title,
                List.of(),
                "描述正文",
                List.of(),
                "Electronics > Audio",
                null,
                "EDITOR_SUBMISSION",
                UUID.randomUUID(),
                "测试用",
                List.of()));
    execAsTenant(
        "insert into listing_publish_job"
            + " (id,tenant_id,source_sku_id,content_source,content_source_ids,price_source,stock_source,"
            + " idempotency_key,state,content_snapshot,price_state,stock_state,primary_shop_id,note,created_by)"
            + " values ('" + jobId + "','" + tenant + "','" + skuId + "','EDITOR_SUBMISSION','[]'::jsonb,"
            + " 'PRICING_SERVICE','KEEP_UNSET','job-" + jobId + "','DRAFT','" + snapshot
            + "'::jsonb,'NOT_SET','NOT_SET','" + shop + "','','" + user + "')");
    execAsTenant(
        "insert into listing_publish_job_target"
            + " (id,tenant_id,job_id,position,platform_id,shop_id,marketplace_id,locale,category_path,"
            + " state,content_source,content_source_id,content_snapshot)"
            + " values ('" + targetId + "','" + tenant + "','" + jobId + "',0,'" + AMAZON + "','" + shop
            + "','" + marketplace + "','en-US','','DRAFT','EDITOR_SUBMISSION','" + UUID.randomUUID()
            + "','" + snapshot + "'::jsonb)");
    return jobId;
  }

  private static String writeJson(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ==================================================================
  // 七、时间往返 + 分页 + HTTP 面
  // ==================================================================

  @Test
  @DisplayName("16.2.1.2 timestamptz 往返逐字相等（+08:00 写入不偏 28800 秒）")
  void exemptionWindowRoundTrips() {
    UUID marketplace = marketplaceId(AMAZON, "US");
    UUID shop = seedShop("AMZ-" + UUID.randomUUID(), AMAZON, marketplace);
    String ruleId = seedRule("PROHIBITED-12", "PROHIBITED_WORD", "BLOCKING", List.of("违禁词"), shop, AMAZON);

    OffsetDateTime from = OffsetDateTime.parse("2026-12-01T09:00:00+08:00");
    OffsetDateTime to = OffsetDateTime.parse("2026-12-08T09:00:00+08:00");
    ComplianceInterceptExemptionRow applied =
        as(
            "channel.operation.create",
            () ->
                service.applyExemption(
                    new ComplianceInterceptExemptionInput(
                        "LISTING_PUBLISH",
                        "PRODUCT",
                        UUID.randomUUID().toString(),
                        ruleId,
                        "理由",
                        from,
                        to),
                    who.userId()));

    assertThat(applied.validFrom()).isEqualTo(from);
    assertThat(applied.validTo()).isEqualTo(to);
    assertThat(
            scalar(
                "select to_char(valid_from at time zone 'UTC','YYYY-MM-DD HH24:MI:SS')"
                    + " from compliance_intercept_exemption where id='" + applied.exemptionId() + "'"))
        .isEqualTo("2026-12-01 01:00:00");
  }

  @Test
  @DisplayName("16.2.1.2 判定历史真分页 + 真筛选")
  void decisionHistoryIsReallyPaged() {
    UUID marketplace = marketplaceId(AMAZON, "US");
    UUID shop = seedShop("AMZ-" + UUID.randomUUID(), AMAZON, marketplace);
    seedRule("PROHIBITED-13", "PROHIBITED_WORD", "BLOCKING", List.of("违禁词"), shop, AMAZON);

    for (int i = 0; i < 5; i++) {
      as(
          "channel.operation.create",
          () ->
              service.evaluate(
                  evaluateInput(
                      UUID.randomUUID().toString(), shop, AMAZON, marketplace, List.of("违禁词商品")),
                  who.userId()));
    }
    ComplianceInterceptDecisionPage page =
        as("channel.operation.read", () -> service.decisions(null, null, null, null, null, 0, 2));
    assertThat(page.total()).isEqualTo(5);
    assertThat(page.items()).hasSize(2);
    assertThat(page.blockCount()).isEqualTo(5);

    ComplianceInterceptDecisionPage second =
        as("channel.operation.read", () -> service.decisions(null, null, null, null, null, 1, 2));
    assertThat(second.items()).hasSize(2);
    assertThat(second.items().get(0).decisionId())
        .isNotEqualTo(page.items().get(0).decisionId());

    // 筛选：只取 BLOCK
    ComplianceInterceptDecisionPage blocked =
        as("channel.operation.read", () -> service.decisions(null, null, "BLOCK", null, null, 0, 20));
    assertThat(blocked.total()).isEqualTo(5);

    // 筛选：按主体
    String oneSubject = UUID.randomUUID().toString();
    as(
        "channel.operation.create",
        () ->
            service.evaluate(
                evaluateInput(oneSubject, shop, AMAZON, marketplace, List.of("违禁词商品")),
                who.userId()));
    ComplianceInterceptDecisionPage bySubject =
        as("channel.operation.read", () -> service.decisions(null, null, null, oneSubject, null, 0, 20));
    assertThat(bySubject.total()).isEqualTo(1);
  }

  @Test
  @DisplayName("16.2.1.2 HTTP 面：401 / 200 / 404 / 422")
  void httpSurface() throws Exception {
    UUID marketplace = marketplaceId(AMAZON, "US");
    UUID shop = seedShop("AMZ-" + UUID.randomUUID(), AMAZON, marketplace);

    http.perform(get("/channel/compliance-intercepts/capabilities"))
        .andExpect(status().isUnauthorized());

    http.perform(get("/channel/compliance-intercepts/capabilities").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.conclusions").isArray());

    http.perform(get("/channel/compliance-intercepts/decisions/" + UUID.randomUUID())
            .header("Authorization", bearer))
        .andExpect(status().isNotFound());

    http.perform(
            post("/channel/compliance-intercepts/decisions")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        new ComplianceInterceptEvaluateInput(
                            "PUBLISH", "PRODUCT", "sku-1", null, null, null, List.of(), List.of()))))
        .andExpect(status().isUnprocessableEntity());
  }
}
