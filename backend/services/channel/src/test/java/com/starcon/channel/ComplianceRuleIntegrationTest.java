package com.starcon.channel;

import static com.starcon.channel.ComplianceRuleModels.*;
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
 * 16.2.1.1 规则库的集成测试（**真实 PostgreSQL 16**，Testcontainers）。
 *
 * <p>🔴 这组用例守的是本模块最容易做丢的十二件事：
 *
 * <ol>
 *   <li><b>值域由 DB 层钉死</b>：非法规则类型 / 非法严重度**直接写库会被 CHECK 拒**；</li>
 *   <li><b>规则改动 = 新增一个版本</b>：改词**不改**旧版本，旧版本的词条原样留着；</li>
 *   <li><b>版本内容不可改写</b>：直接 UPDATE 严重度被触发器拒；</li>
 *   <li><b>生效时刻不许回填</b>：服务层 422 + DB 触发器也拒；</li>
 *   <li><b>同一时刻只有一个版本生效</b>：新版本落库时上一版窗口被自动关闭；</li>
 *   <li><b>生效时间窗不得重叠</b>：插到旧版本之前被拒；</li>
 *   <li><b>词条属于版本</b>：版本生效后加词被拒（服务层 + DB 双拒）；词条不可删除；</li>
 *   <li><b>词库上传是批量的</b>：整份词库一次落库，注释行忽略，重复词跳过；</li>
 *   <li><b>非词条类规则带词条被拒</b>（认证要求 / 禁限售 / 标签要求 / 材质要求）；</li>
 *   <li><b>适用范围自洽</b>：站点必须属于平台、店铺必须属于平台且在本租户目录里；</li>
 *   <li><b>timestamptz 往返不偏 −28800s</b>：写 +08:00 读回同一绝对时刻；</li>
 *   <li><b>真分页真筛选</b> + 401 + 404 + 非法筛选 422。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ComplianceRuleIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");
  static final UUID SHOPIFY = UUID.fromString("11111111-1111-1111-1111-111111111102");

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
    pg.stop();
  }



  @BeforeEach
  void freshTenant() {
    tenant = UUID.randomUUID();
    bearer = "Bearer t-" + tenant;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearer, actor(user, tenant, grants));
    who = actors.get(bearer);
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

  @Autowired ComplianceRuleService service;
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

  /** 直接写库（超级用户）——用来验证 DB 层的 CHECK 与触发器真的在拦。 */
  void execRaw(String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
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

  UUID seedShop(UUID platformId, String code) {
    UUID id = UUID.randomUUID();
    execAsTenant(
        tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('"
            + tenant
            + "','"
            + id
            + "','"
            + platformId
            + "','"
            + code
            + "','测试店铺 "
            + code
            + "')");
    return id;
  }

  /** 取某个平台下某个站点的 id（站点 id 由迁移按 md5 派生）。 */
  UUID marketplaceId(UUID platformId, String code) {
    return UUID.fromString(
        scalar(
            "select id from marketplace where platform_id = '"
                + platformId
                + "' and code = '"
                + code
                + "'"));
  }

  /** 建一条规则（只建身份）。 */
  String createRule(String code, String type) {
    return as(
            "channel.operation.create",
            () ->
                service.createRule(
                        new ComplianceRuleCreateInput(code, type, "测试规则 " + code, "说明"))
                    .rule()
                    .ruleId());
  }

  /** 发一个版本（默认立即生效）。 */
  ComplianceRuleVersionDetail createVersion(
      String ruleId, String severity, List<String> terms, OffsetDateTime effectiveFrom) {
    return as(
        "channel.operation.create",
        () ->
            service.createVersion(
                ruleId,
                new ComplianceRuleVersionInput(
                    severity,
                    "版本标题",
                    "版本说明",
                    effectiveFrom,
                    "改了一版",
                    terms.stream().map(t -> new ComplianceRuleTermInput(t, "GENERAL", "")).toList(),
                    null,
                    List.of())));
  }

  // ==================================================================
  // 一、能力声明
  // ==================================================================

  @Test
  @DisplayName("16.2.1.1 能力声明：值域 + 三条下游的 available 真假与 ownerModule")
  void capabilitiesStateDomainsAndGaps() {
    ComplianceRuleCapabilities caps = as("channel.operation.read", () -> service.capabilities());

    assertThat(caps.ruleTypes()).hasSize(7).contains("PROHIBITED_WORD", "MATERIAL_REQUIREMENT");
    assertThat(caps.severities()).containsExactly("BLOCKING", "WARNING", "INFO");
    assertThat(caps.wordRuleTypes())
        .containsExactly("PROHIBITED_WORD", "SUPERLATIVE_WORD", "INFRINGEMENT_WORD");

    // 🔴 16.2.1.2 拦截执行**未交付** ⇒ available=false + ownerModule + 影响说明。
    ComplianceRuleCapabilityGap execution =
        caps.gaps().stream().filter(g -> "16.2.1.2".equals(g.moduleId())).findFirst().orElseThrow();
    assertThat(execution.available()).isFalse();
    assertThat(execution.ownerModule()).isEqualTo("16.2.1.2 拦截执行");
    assertThat(execution.impact()).contains("未交付").contains("不建拦截表");

    // 🔴 4.2.3.2 合规预检**已交付**（同服务），但用的是它自己的租户级词表。
    ComplianceRuleCapabilityGap precheck =
        caps.gaps().stream().filter(g -> "4.2.3.2".equals(g.moduleId())).findFirst().orElseThrow();
    assertThat(precheck.available()).isTrue();
    assertThat(precheck.impact()).contains("自己的租户级词表");

    // 版本化口径必须在能力声明里说清楚（蓝图加粗句）。
    assertThat(caps.versioningNote()).contains("新增一个版本").contains("不许回填");
  }

  // ==================================================================
  // 二、规则身份
  // ==================================================================

  @Test
  @DisplayName("16.2.1.1 建规则幂等：同一个规则编码不产生第二条")
  void createRuleIsIdempotentOnCode() {
    ComplianceRuleCreation first =
        as(
            "channel.operation.create",
            () ->
                service.createRule(
                    new ComplianceRuleCreateInput("RULE-IDEM", "PROHIBITED_WORD", "标题", "说明")));
    assertThat(first.created()).isTrue();

    ComplianceRuleCreation second =
        as(
            "channel.operation.create",
            () ->
                service.createRule(
                    new ComplianceRuleCreateInput("RULE-IDEM", "PROHIBITED_WORD", "另一个标题", "")));
    assertThat(second.created()).as("重复编码必须命中幂等，不新建第二条").isFalse();
    assertThat(second.rule().ruleId()).isEqualTo(first.rule().ruleId());

    assertThat(countRows("select count(*) from compliance_rule where rule_code = 'RULE-IDEM'"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("16.2.1.1 非法规则类型：服务层 422 + DB 层 CHECK 直接拒")
  void invalidRuleTypeRejectedByServiceAndDatabase() {
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.create",
                        () ->
                            service.createRule(
                                new ComplianceRuleCreateInput(
                                    "RULE-BAD", "NOT_A_TYPE", "标题", "")))))
        .as("服务层必须先拒（带人话）")
        .isNotNull()
        .hasMessageContaining("未知的规则类型");

    // 🔴 DB 层 CHECK 兜底：直接写库用非法类型 ⇒ 23514。
    Throwable dbError =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                execRaw(
                    "insert into compliance_rule (id,tenant_id,rule_code,rule_type,status,created_at,updated_at)"
                        + " values ('"
                        + UUID.randomUUID()
                        + "','"
                        + tenant
                        + "','RULE-RAW-BAD','NOT_A_TYPE','ACTIVE',now(),now())"));
    assertThat(dbError).as("DB 层 CHECK 必须直接拒").isNotNull();
    assertThat(rootMessage(dbError)).contains("rule_type");
  }

  @Test
  @DisplayName("16.2.1.1 非法严重度：DB 层 CHECK 直接拒")
  void invalidSeverityRejectedByDatabase() {
    String ruleId = createRule("RULE-SEV", "PROHIBITED_WORD");
    Throwable error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                execAsTenant(
                    tenant,
                    "insert into compliance_rule_version (id,tenant_id,rule_id,version_no,severity,title,effective_from,created_at)"
                        + " values ('"
                        + UUID.randomUUID()
                        + "','"
                        + tenant
                        + "','"
                        + ruleId
                        + "',1,'CRITICAL','标题',now(),now())"));
    assertThat(error).as("DB 层 CHECK 必须直接拒").isNotNull();
    assertThat(rootMessage(error)).contains("severity");
    // 规则下**没有**版本。
    assertThat(countRows("select count(*) from compliance_rule_version where rule_id = '" + ruleId + "'"))
        .isZero();
  }

  @Test
  @DisplayName("16.2.1.1 规则生命周期可停用 / 重新启用；生效规则集随之变化")
  void ruleLifecycleCanBeRetired() {
    String ruleId = createRule("RULE-LIFE", "PROHIBITED_WORD");
    createVersion(ruleId, "BLOCKING", List.of("违禁"), null);
    assertThat(as("channel.operation.read", () -> service.effective(null, null, null, 0, 50)).total())
        .isEqualTo(1);

    as(
        "channel.operation.update",
        () -> service.updateRule(ruleId, new ComplianceRuleUpdateInput("RETIRED")));
    assertThat(as("channel.operation.read", () -> service.effective(null, null, null, 0, 50)).total())
        .as("停用的规则不再出现在生效规则集里")
        .isZero();

    as(
        "channel.operation.update",
        () -> service.updateRule(ruleId, new ComplianceRuleUpdateInput("ACTIVE")));
    assertThat(as("channel.operation.read", () -> service.effective(null, null, null, 0, 50)).total())
        .isEqualTo(1);
  }

  // ==================================================================
  // 三、版本化（蓝图加粗句）
  // ==================================================================

  @Test
  @DisplayName("16.2.1.1 改规则 = 新增一个版本：旧版本的词条原样留着")
  void changingWordsMeansNewVersion() {
    String ruleId = createRule("RULE-VER", "PROHIBITED_WORD");
    ComplianceRuleVersionDetail v1 = createVersion(ruleId, "BLOCKING", List.of("违禁词A"), null);
    assertThat(v1.version().versionNo()).isEqualTo(1);
    assertThat(v1.terms()).extracting(ComplianceRuleTerm::term).containsExactly("违禁词A");

    // 🔴 改词**不是**改 v1，而是新增 v2。
    ComplianceRuleVersionDetail v2 =
        as(
            "channel.operation.create",
            () ->
                service.createVersion(
                    ruleId,
                    new ComplianceRuleVersionInput(
                        "WARNING",
                        "第二版",
                        "把违禁词A 换掉了",
                        null,
                        "换词",
                        List.of(new ComplianceRuleTermInput("违禁词B", "GENERAL", "")),
                        null,
                        List.of())));
    assertThat(v2.version().versionNo()).isEqualTo(2);
    assertThat(v2.terms()).extracting(ComplianceRuleTerm::term).containsExactly("违禁词B");

    // 🔴 v1 的词条**原样留着** —— 历史判定解释得清。
    ComplianceRuleVersionDetail old =
        as("channel.operation.read", () -> service.versionDetail(ruleId, v1.version().versionId()));
    assertThat(old.terms()).extracting(ComplianceRuleTerm::term).containsExactly("违禁词A");
    assertThat(old.version().severity()).isEqualTo("BLOCKING");
    assertThat(old.version().inForce()).as("v1 已被 v2 接替，不再生效").isFalse();
    assertThat(old.version().effectiveTo()).isNotNull();
    assertThat(v2.version().inForce()).isTrue();
  }

  @Test
  @DisplayName("16.2.1.1 生效时刻不许回填：服务层 422 + DB 触发器也拒")
  void backdatedEffectiveFromRejected() {
    String ruleId = createRule("RULE-BACK", "PROHIBITED_WORD");

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    createVersion(
                        ruleId,
                        "BLOCKING",
                        List.of("旧词"),
                        OffsetDateTime.now(ZoneOffset.UTC).minusDays(1))))
        .as("服务层必须先拒")
        .isNotNull()
        .hasMessageContaining("不许回填");

    // 🔴 DB 触发器兜底：直接写库塞一个过去的生效时刻。
    Throwable dbError =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                execAsTenant(
                    tenant,
                    "insert into compliance_rule_version (id,tenant_id,rule_id,version_no,severity,title,effective_from,created_at)"
                        + " values ('"
                        + UUID.randomUUID()
                        + "','"
                        + tenant
                        + "','"
                        + ruleId
                        + "',1,'BLOCKING','标题',now() - interval '1 day',now())"));
    assertThat(dbError).as("DB 触发器必须直接拒").isNotNull();
    assertThat(rootMessage(dbError)).contains("不许回填");
  }

  @Test
  @DisplayName("16.2.1.1 版本内容不可改写：直接 UPDATE 严重度被触发器拒")
  void versionContentIsImmutable() {
    String ruleId = createRule("RULE-IMM", "PROHIBITED_WORD");
    ComplianceRuleVersionDetail v1 = createVersion(ruleId, "BLOCKING", List.of("词"), null);

    Throwable error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                execAsTenant(
                    tenant,
                    "update compliance_rule_version set severity = 'INFO' where id = '"
                        + v1.version().versionId()
                        + "'"));
    assertThat(error).as("版本内容不可改写").isNotNull();
    assertThat(rootMessage(error)).contains("不可改写");

    // 🔴 生效时刻也不可改（不许改写「当时生效的那一版」）。
    Throwable windowError =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                execAsTenant(
                    tenant,
                    "update compliance_rule_version set effective_from = now() - interval '1 day' where id = '"
                        + v1.version().versionId()
                        + "'"));
    assertThat(windowError).isNotNull();

    // 🔴 严重度仍然是原值。
    assertThat(
            scalar(
                "select severity from compliance_rule_version where id = '"
                    + v1.version().versionId()
                    + "'"))
        .isEqualTo("BLOCKING");
  }

  @Test
  @DisplayName("16.2.1.1 生效时间窗不得重叠：插到旧版本之前被拒")
  void effectiveWindowsMustNotOverlap() {
    String ruleId = createRule("RULE-OVL", "PROHIBITED_WORD");
    // 🔴 第一版落在**将来**（回填过去会先被「不许回填」挡住，测不到重叠这一层）。
    createVersion(ruleId, "BLOCKING", List.of("词"), OffsetDateTime.now(ZoneOffset.UTC).plusHours(2));

    // 🔴 直接写库塞一个**更早**（但仍在将来）的生效时刻 ⇒ 与既有版本重叠。
    Throwable error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                execAsTenant(
                    tenant,
                    "insert into compliance_rule_version (id,tenant_id,rule_id,version_no,severity,title,effective_from,created_at)"
                        + " values ('"
                        + UUID.randomUUID()
                        + "','"
                        + tenant
                        + "','"
                        + ruleId
                        + "',2,'WARNING','第二版',now() + interval '1 hour',now())"));
    assertThat(error).as("生效窗口重叠必须被拒").isNotNull();
    assertThat(rootMessage(error)).contains("重叠");
  }

  @Test
  @DisplayName("16.2.1.1 某一时刻生效的是哪一版：可解释性")
  void effectiveVersionAtInstantExplainsHistory() {
    String ruleId = createRule("RULE-HIST", "PROHIBITED_WORD");
    createVersion(ruleId, "BLOCKING", List.of("旧词"), null);

    OffsetDateTime future = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2);
    ComplianceRuleVersionDetail v2 =
        as(
            "channel.operation.create",
            () ->
                service.createVersion(
                    ruleId,
                    new ComplianceRuleVersionInput(
                        "WARNING", "第二版", "换词", future, "换词",
                        List.of(new ComplianceRuleTermInput("新词", "GENERAL", "")), null, List.of())));

    // 🔴 此刻生效的是 v1。
    ComplianceRuleEffectiveVersion now =
        as("channel.operation.read", () -> service.effectiveVersion(ruleId, null));
    assertThat(now.version().versionNo()).isEqualTo(1);
    assertThat(now.terms()).extracting(ComplianceRuleTerm::term).containsExactly("旧词");

    // 🔴 两个小时之后（v2 的生效时刻）生效的是 v2 —— **历史判定解释得清**。
    ComplianceRuleEffectiveVersion later =
        as(
            "channel.operation.read",
            () -> service.effectiveVersion(ruleId, future.plusMinutes(1).toString()));
    assertThat(later.version().versionNo()).isEqualTo(2);
    assertThat(later.terms()).extracting(ComplianceRuleTerm::term).containsExactly("新词");
    assertThat(later.resolvedAt()).isNotNull();
    assertThat(v2.version().versionNo()).isEqualTo(2);
  }

  // ==================================================================
  // 四、词条
  // ==================================================================

  @Test
  @DisplayName("16.2.1.1 词条属于版本：版本生效后加词被拒（服务层 + DB 双拒）")
  void termsAreFrozenAfterVersionInForce() {
    String ruleId = createRule("RULE-FRZ", "PROHIBITED_WORD");
    ComplianceRuleVersionDetail v1 = createVersion(ruleId, "BLOCKING", List.of("词"), null);

    // 🔴 服务层先拒（带人话）。
    Throwable serviceError =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(
                    "channel.operation.create",
                    () ->
                        service.addTerms(
                            ruleId,
                            v1.version().versionId(),
                            new ComplianceRuleTermsInput(
                                List.of(
                                    new ComplianceRuleTermInput("后来的词", "GENERAL", ""))))));
    assertThat(serviceError).as("生效后的版本不可再加词").isNotNull();
    assertThat(serviceError).hasMessageContaining("新增一个版本");

    // 🔴 DB 触发器兜底：直接写库插词。
    Throwable dbError =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                execAsTenant(
                    tenant,
                    "insert into compliance_rule_term (id,tenant_id,rule_version_id,term,term_kind,created_at)"
                        + " values ('"
                        + UUID.randomUUID()
                        + "','"
                        + tenant
                        + "','"
                        + v1.version().versionId()
                        + "','偷偷加的词','GENERAL',now())"));
    assertThat(dbError).as("DB 触发器必须直接拒").isNotNull();
    assertThat(rootMessage(dbError)).contains("已经生效");
  }

  @Test
  @DisplayName("16.2.1.1 词条不可删除")
  void termsCannotBeDeleted() {
    String ruleId = createRule("RULE-DEL", "PROHIBITED_WORD");
    ComplianceRuleVersionDetail v1 = createVersion(ruleId, "BLOCKING", List.of("词"), null);
    Throwable error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                execAsTenant(
                    tenant,
                    "delete from compliance_rule_term where rule_version_id = '"
                        + v1.version().versionId()
                        + "'"));
    assertThat(error).isNotNull();
    assertThat(rootMessage(error)).contains("不可删除");
  }

  @Test
  @DisplayName("16.2.1.1 词库上传是批量的：整份词库一次落库，注释行忽略，重复词跳过")
  void wordLibraryUploadIsBatch() {
    String ruleId = createRule("RULE-UPL", "PROHIBITED_WORD");
    // 先发一个**将来**生效的版本，才可以在生效前继续补词条。
    OffsetDateTime future = OffsetDateTime.now(ZoneOffset.UTC).plusHours(3);
    ComplianceRuleVersionDetail v1 =
        as(
            "channel.operation.create",
            () ->
                service.createVersion(
                    ruleId,
                    new ComplianceRuleVersionInput(
                        "BLOCKING", "版本标题", "说明", future, "先占位",
                        List.of(new ComplianceRuleTermInput("已存在的词", "GENERAL", "")),
                        null,
                        List.of())));

    ComplianceRuleTermBatchResult result =
        as(
            "channel.operation.create",
            () ->
                service.uploadTerms(
                    ruleId,
                    v1.version().versionId(),
                    new ComplianceRuleTermsUploadInput(
                        "违禁词一\n# 这是注释\n违禁词二\n\n违禁词一\n 违禁词三  \n")));

    assertThat(result.requested()).isEqualTo(4);
    assertThat(result.inserted()).as("重复的词被跳过，只写入 3 个").isEqualTo(3);
    assertThat(result.skipped()).isEqualTo(1);
    assertThat(result.skippedTerms()).containsExactly("违禁词一");

    ComplianceRuleTermPage page =
        as(
            "channel.operation.read",
            () -> service.terms(ruleId, v1.version().versionId(), 0, 50));
    assertThat(page.total()).isEqualTo(4);
    assertThat(page.items()).extracting(ComplianceRuleTerm::term)
        .containsExactlyInAnyOrder("已存在的词", "违禁词一", "违禁词二", "违禁词三");
  }

  @Test
  @DisplayName("16.2.1.1 非词条类规则带词条被拒（认证要求 / 禁限售 / 标签要求 / 材质要求）")
  void nonWordRuleTypesRejectTerms() {
    String ruleId = createRule("RULE-CERT", "CERTIFICATION_REQUIRED");
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    createVersion(ruleId, "BLOCKING", List.of("不该有的词"), null)))
        .as("认证要求类规则不带词条")
        .isNotNull()
        .hasMessageContaining("不带词条");
  }

  // ==================================================================
  // 五、适用范围
  // ==================================================================

  @Test
  @DisplayName("16.2.1.1 适用范围：平台 + 站点 + 店铺，且必须自洽")
  void scopeMustBeSelfConsistent() {
    UUID shopId = seedShop(AMAZON, "SCOPE-SHOP");
    UUID amazonUs = marketplaceId(AMAZON, "US");
    String ruleId = createRule("RULE-SCOPE", "PROHIBITED_WORD");

    ComplianceRuleVersionDetail detail =
        as(
            "channel.operation.create",
            () ->
                service.createVersion(
                    ruleId,
                    new ComplianceRuleVersionInput(
                        "BLOCKING",
                        "标题",
                        "说明",
                        null,
                        "配范围",
                        List.of(new ComplianceRuleTermInput("词", "GENERAL", "")),
                        null,
                        List.of(
                            new ComplianceRuleScopeInput(
                                AMAZON.toString(), amazonUs.toString(), shopId.toString()),
                            new ComplianceRuleScopeInput(
                                SHOPIFY.toString(), null, null)))));

    assertThat(detail.scopes()).hasSize(2);
    ComplianceRuleScope amazon =
        detail.scopes().stream()
            .filter(s -> "AMAZON".equals(s.platformCode()))
            .findFirst()
            .orElseThrow();
    assertThat(amazon.marketplaceCode()).isEqualTo("US");
    assertThat(amazon.shopCode()).isEqualTo("SCOPE-SHOP");
    ComplianceRuleScope shopify =
        detail.scopes().stream()
            .filter(s -> "SHOPIFY".equals(s.platformCode()))
            .findFirst()
            .orElseThrow();
    assertThat(shopify.marketplaceCode()).as("NULL 站点 = 该平台全部站点").isNull();
    assertThat(shopify.shopCode()).as("NULL 店铺 = 该平台/站点下全部店铺").isNull();

    // 🔴 站点不属于平台 ⇒ DB 触发器拒。
    Throwable error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                execAsTenant(
                    tenant,
                    "insert into compliance_rule_scope (id,tenant_id,rule_id,rule_version_id,platform_id,marketplace_id,shop_id,created_at)"
                        + " values ('"
                        + UUID.randomUUID()
                        + "','"
                        + tenant
                        + "','"
                        + ruleId
                        + "','"
                        + detail.version().versionId()
                        + "','"
                        + SHOPIFY
                        + "','"
                        + amazonUs
                        + "',null,now())"));
    assertThat(error).as("站点与平台必须自洽").isNotNull();
    assertThat(rootMessage(error)).contains("不属于所选平台");

    // 🔴 店铺不属于平台 ⇒ DB 触发器拒。
    Throwable shopError =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                execAsTenant(
                    tenant,
                    "insert into compliance_rule_scope (id,tenant_id,rule_id,rule_version_id,platform_id,marketplace_id,shop_id,created_at)"
                        + " values ('"
                        + UUID.randomUUID()
                        + "','"
                        + tenant
                        + "','"
                        + ruleId
                        + "','"
                        + detail.version().versionId()
                        + "','"
                        + SHOPIFY
                        + "',null,'"
                        + shopId
                        + "',now())"));
    assertThat(shopError).as("店铺必须属于所选平台").isNotNull();
  }

  @Test
  @DisplayName("16.2.1.1 适用范围不可删除")
  void scopesCannotBeDeleted() {
    String ruleId = createRule("RULE-SDEL", "PROHIBITED_WORD");
    ComplianceRuleVersionDetail detail =
        as(
            "channel.operation.create",
            () ->
                service.createVersion(
                    ruleId,
                    new ComplianceRuleVersionInput(
                        "BLOCKING", "标题", "说明", null, "配范围", null, null,
                        List.of(new ComplianceRuleScopeInput(SHOPIFY.toString(), null, null)))));
    Throwable error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                execAsTenant(
                    tenant,
                    "delete from compliance_rule_scope where rule_version_id = '"
                        + detail.version().versionId()
                        + "'"));
    assertThat(error).isNotNull();
    assertThat(rootMessage(error)).contains("不可删除");
  }

  @Test
  @DisplayName("16.2.1.1 店铺范围清单来自真实店铺目录，规则数是真算出来的")
  void shopScopeComesFromRealDirectory() {
    seedShop(AMAZON, "COUNT-SHOP");
    String ruleId = createRule("RULE-CNT", "PROHIBITED_WORD");
    as(
        "channel.operation.create",
        () ->
            service.createVersion(
                ruleId,
                new ComplianceRuleVersionInput(
                    "BLOCKING", "标题", "说明", null, "配范围", null, null,
                    List.of(new ComplianceRuleScopeInput(AMAZON.toString(), null, null)))));

    ComplianceRuleShopPage page = as("channel.operation.read", () -> service.shops(null, null, 0, 50));
    assertThat(page.total()).isGreaterThanOrEqualTo(1);
    ComplianceRuleShop shop =
        page.items().stream()
            .filter(s -> "COUNT-SHOP".equals(s.shopCode()))
            .findFirst()
            .orElseThrow();
    assertThat(shop.platformCode()).isEqualTo("AMAZON");
    assertThat(shop.ruleCount()).isEqualTo(1);
    assertThat(shop.effectiveRuleCount()).isEqualTo(1);
  }

  // ==================================================================
  // 六、时间往返
  // ==================================================================

  @Test
  @DisplayName("16.2.1.1 timestamptz 往返不偏 −28800s：+08:00 写入读回同一绝对时刻")
  void timestamptzRoundTripKeepsTheInstant() {
    String ruleId = createRule("RULE-TZ", "PROHIBITED_WORD");
    OffsetDateTime written = OffsetDateTime.now(ZoneOffset.ofHours(8)).plusDays(1).withNano(0);
    ComplianceRuleVersionDetail detail =
        as(
            "channel.operation.create",
            () ->
                service.createVersion(
                    ruleId,
                    new ComplianceRuleVersionInput(
                        "BLOCKING", "标题", "说明", written, "时区往返", null, null, List.of())));

    OffsetDateTime read = detail.version().effectiveFrom();
    assertThat(read).isNotNull();
    assertThat(read.toInstant())
        .as("写回的值必须与写进去的是同一绝对时刻（不能偏 −28800 秒）")
        .isEqualTo(written.toInstant());

    // 🔴 库里那一列也必须是同一刻（用 epoch 比，避免会话时区影响）。
    assertThat(
            Long.parseLong(
                scalar(
                    "select extract(epoch from effective_from)::bigint from compliance_rule_version where id = '"
                        + detail.version().versionId()
                        + "'")))
        .isEqualTo(written.toEpochSecond());
  }

  // ==================================================================
  // 七、生效规则集与 HTTP 面
  // ==================================================================

  @Test
  @DisplayName("16.2.1.1 生效规则集：只给此刻生效的那一版，且可按平台 / 店铺收敛")
  void effectiveSetResolvesTheInForceVersion() {
    UUID shopId = seedShop(AMAZON, "EFF-SHOP");
    String ruleId = createRule("RULE-EFF", "PROHIBITED_WORD");
    as(
        "channel.operation.create",
        () ->
            service.createVersion(
                ruleId,
                new ComplianceRuleVersionInput(
                    "BLOCKING", "标题", "说明", null, "配范围",
                    List.of(new ComplianceRuleTermInput("词", "GENERAL", "")),
                    null,
                    List.of(
                        new ComplianceRuleScopeInput(
                            AMAZON.toString(), null, shopId.toString())))));

    ComplianceRuleEffectiveSet all =
        as("channel.operation.read", () -> service.effective(null, null, null, 0, 50));
    assertThat(all.total()).isEqualTo(1);
    ComplianceRuleEffectiveEntry entry = all.items().get(0);
    assertThat(entry.version().severity()).isEqualTo("BLOCKING");
    assertThat(entry.termCount()).isEqualTo(1);
    assertThat(entry.platformCodes()).containsExactly("AMAZON");
    assertThat(entry.shopCodes()).containsExactly("EFF-SHOP");

    // 🔴 按平台收敛。
    assertThat(
            as("channel.operation.read", () -> service.effective(null, "AMAZON", null, 0, 50)).total())
        .isEqualTo(1);
    assertThat(
            as("channel.operation.read", () -> service.effective(null, "SHOPIFY", null, 0, 50)).total())
        .as("不适用于该平台的规则不该出现")
        .isZero();
  }

  @Test
  @DisplayName("16.2.1.1 HTTP：真分页真筛选 + 401 + 404 + 非法筛选 422")
  void httpSurfaceIsReal() throws Exception {
    for (int i = 0; i < 3; i++) {
      String ruleId = createRule("RULE-HTTP-" + i, "PROHIBITED_WORD");
      createVersion(ruleId, "BLOCKING", List.of("词" + i), null);
    }

    http.perform(get("/channel/compliance-rules/rules")
            .header("Authorization", bearer)
            .param("size", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.total").value(3))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(2));

    // 🔴 真筛选。
    http.perform(get("/channel/compliance-rules/rules")
            .header("Authorization", bearer)
            .param("keyword", "RULE-HTTP-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));

    // 🔴 非法筛选 ⇒ 422。
    http.perform(get("/channel/compliance-rules/rules")
            .header("Authorization", bearer)
            .param("ruleType", "NOT_A_TYPE"))
        .andExpect(status().isUnprocessableEntity());

    // 🔴 无会话 ⇒ 401。
    http.perform(get("/channel/compliance-rules/rules")).andExpect(status().isUnauthorized());

    // 🔴 不存在 ⇒ 404。
    http.perform(get("/channel/compliance-rules/rules/" + UUID.randomUUID())
            .header("Authorization", bearer))
        .andExpect(status().isNotFound());

    // 🔴 能力声明与生效规则集都真的可读。
    http.perform(get("/channel/compliance-rules/capabilities").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ruleTypes.length()").value(7));
    http.perform(get("/channel/compliance-rules/effective").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(3));
  }

  @Test
  @DisplayName("16.2.1.1 四张表都是 FORCE RLS，且规则库落在本租户名下")
  void tablesAreForceRls() {
    String ruleId = createRule("RULE-RLS", "PROHIBITED_WORD");
    createVersion(ruleId, "BLOCKING", List.of("词"), null);
    for (String table :
        List.of(
            "compliance_rule",
            "compliance_rule_version",
            "compliance_rule_term",
            "compliance_rule_scope")) {
      assertThat(
              scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS")
          .isEqualTo("true");
    }
    assertThat(scalar("select tenant_id from compliance_rule where rule_code = 'RULE-RLS'"))
        .isEqualTo(tenant.toString());
  }

  // ==================================================================
  // 辅助
  // ==================================================================

  /** 取最内层异常的消息（PostgreSQL 的约束名只出现在最内层）。 */
  static String rootMessage(Throwable error) {
    Throwable current = error;
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
