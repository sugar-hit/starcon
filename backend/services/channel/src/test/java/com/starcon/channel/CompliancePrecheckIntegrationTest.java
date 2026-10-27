package com.starcon.channel;

import static com.starcon.channel.CompliancePrecheckModels.*;
import static com.starcon.channel.ListingEditorModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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
 * 16.2.2.1 合规预检。
 *
 * <p>本测试类钉住这几组事：
 *
 * <ol>
 *   <li><b>🔴 分维度得分，且不可知分量不计入分母</b>：Amazon 一份完整草稿 ⇒ 内容维度得分 1.0000
 *       （**不是** 4/11 —— 能力键缺失与细则归别人的那几项是 UNKNOWN，不进分母）。
 *   <li><b>🔴 全维度不可知时给 UNKNOWN 而不是 0 分</b>：一个没有登记任何字段要求的平台 +
 *       catalog 读不到 ⇒ 六个维度全 UNKNOWN，{@code overall_score} 是 <b>null</b>。
 *   <li><b>🔴 量不到的维度不稀释总分</b>：总体得分等于被评出分的维度的得分（1.0000，不是 0.3000）。
 *   <li><b>🔴 两次触发语义分得清</b>：保存时的即时反馈（DRAFT_SAVE）与发布前的强制校验（PUBLISH_GATE）
 *       产出两份报告，报告头带着触发语义，报告历史里分得清。
 *   <li><b>🔴 违规真算</b>：标题超长 / 必填缺失 / 平台明确不支持的栏被填 ⇒ 逐条指到字段。
 *   <li><b>🔴 能力键缺失 = UNKNOWN</b>，不是 false、不是「没约束」。
 *   <li><b>🔴 品牌这一关</b>：catalog 判不可刊登 ⇒ 阻断项；catalog 读不到 ⇒ 量不到（不是合规、不是 0 分）。
 *   <li><b>🔴 报告是那一次判定的凭据</b>：直接改 verdict 会被 DB 触发器拒。
 *   <li><b>🔴 草稿逐字未变</b>：体检对 4.2.1.1 的草稿表<b>只读</b>。
 *   <li><b>🔴 报告历史是真分页</b>。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class CompliancePrecheckIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static CompliancePrecheckCatalogStub catalog;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  UUID tenant;
  AccessService.Access actor;
  String bearer;

  // 🔴 平台与站点注册表由 V1 迁移随代码发布（只读码表），测试直接用它，不自己造一份。
  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");
  static final UUID SHOPIFY = UUID.fromString("11111111-1111-1111-1111-111111111102");
  UUID siteUs, siteShopifyUs;

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
      catalog = new CompliancePrecheckCatalogStub();
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
    siteUs = marketplaceId(AMAZON, "US");
    siteShopifyUs = marketplaceId(SHOPIFY, "US");
    catalog.skuStatus = 0;
    catalog.brandLookupFails = false;
    catalog.brandPublishable = true;
    catalog.validAuthorizationCount = 1;
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
    r.add("CATALOG_URL", () -> catalog.base());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired CompliancePrecheckService precheck;
  @Autowired ListingEditorService editor;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String permission, java.util.function.Supplier<T> work) {
    return access.run(actor, permission, work);
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

  static void exec(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
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

  UUID seedShop(String code, UUID platformId, UUID marketplaceId) {
    UUID id = UUID.randomUUID();
    execAsTenant("insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenant + "','" + id
        + "','" + platformId + "','" + code + "','测试店铺 " + code + "')");
    execAsTenant("insert into shop_marketplace (tenant_id,shop_id,marketplace_id,status)"
        + " values ('" + tenant + "','" + id + "','" + marketplaceId + "','ACTIVE')");
    return id;
  }

  static EditorFieldValue text(String field, String value) {
    return new EditorFieldValue(field, value, null, null, null);
  }

  static EditorFieldValue items(String field, String... values) {
    return new EditorFieldValue(field, null, List.of(values), null, null);
  }

  /**
   * 存一份内容草稿（4.2.1.1 写的，本模块只读）。
   *
   * <p>🔴 草稿表要求「关联线上 listing」与「关联源商品」至少给一个，所以源商品 id 缺省时
   * 造一个随机 UUID（它是**跨服务逻辑 id**，本服务不建 FK、也读不到它 —— 那正是
   * 「品牌这一项量不到」这条路径的入口）。
   */
  EditorDraftRow saveDraft(UUID shopId, UUID marketplaceId, UUID sourceSkuId, EditorFieldValue... values) {
    return as(
        "channel.operation.create",
        () ->
            editor.save(
                new EditorDraftRequest(
                    shopId,
                    marketplaceId,
                    "en-US",
                    sourceSkuId == null ? UUID.randomUUID() : sourceSkuId,
                    null,
                    "",
                    List.of(values),
                    "")));
  }

  CompliancePrecheckReport check(String draftId) {
    return as("channel.operation.create", () -> precheck.check(draftId, "DRAFT_SAVE", bearer));
  }

  CompliancePrecheckReport precheckGate(String draftId) {
    return as("channel.operation.create", () -> precheck.check(draftId, "PUBLISH_GATE", bearer));
  }

  CompliancePrecheckDimensionScore dimension(CompliancePrecheckReport report, String code) {
    return report.dimensions().stream()
        .filter(d -> d.dimensionCode().equals(code))
        .findFirst()
        .orElseThrow(() -> new AssertionError("报告里没有维度 " + code));
  }

  /** 测试用的平台 + 站点（全局码表，测试直接登记）。 */
  record TestPlatform(UUID platformId, UUID marketplaceId) {}

  /** 登记一个测试平台与它的站点。 */
  TestPlatform seedTestPlatform(String code) {
    UUID platform = UUID.randomUUID();
    UUID marketplace = UUID.randomUUID();
    exec(
        "insert into platform (id,code,name,integration,support_status,capabilities,position) values ('"
            + platform
            + "','"
            + code
            + "','测试平台','OFFICIAL_API','AVAILABLE','{}'::jsonb,99)");
    exec(
        "insert into marketplace (id,platform_id,code,name,country_code,default_currency,"
            + "default_timezone,default_locale,currency_scale,vat_required,position) values ('"
            + marketplace
            + "','"
            + platform
            + "','US','测试站','US','USD','UTC','en-US',2,false,99)");
    return new TestPlatform(platform, marketplace);
  }

  /** 登记一条平台字段要求（全局码表，测试直接写）。 */
  void seedFieldRequirement(UUID platformId, String fieldKey, String requirement) {
    exec(
        "insert into platform_listing_field_requirement"
            + " (id,platform_id,marketplace_id,category_path,field_key,value_kind,requirement,"
            + "  min_length,max_length,min_items,max_items,requires_capability,editable,value_source,"
            + "  constraint_owner_module,note)"
            + " values ('"
            + UUID.randomUUID()
            + "','"
            + platformId
            + "',null,null,'"
            + fieldKey
            + "','TEXT','"
            + requirement
            + "',1,200,null,null,null,true,'OPERATOR',null,'')");
  }

  /**
   * 直接插一条内容草稿。
   *
   * <p>🔴 4.2.1.1 的编辑器**会拒**「该平台没有这一栏」的内容，DB 触发器
   * {@code guard_editor_draft_field_values} 也一样 —— 所以「一个什么字段要求都没有的平台」
   * 只能这样造一条空草稿。这不是绕过校验，是构造一个**编辑器根本产不出来**的合法状态。
   */
  UUID seedDraftRaw(
      UUID shopId, UUID platformId, UUID marketplaceId, UUID sourceSkuId, String fieldValuesJson) {
    UUID id = UUID.randomUUID();
    execAsTenant(
        "insert into listing_editor_draft"
            + " (id,tenant_id,shop_id,platform_id,marketplace_id,locale,source_sku_id,category_path,"
            + "  field_values,state,version,created_at,updated_at)"
            + " values ('"
            + id
            + "','"
            + tenant
            + "','"
            + shopId
            + "','"
            + platformId
            + "','"
            + marketplaceId
            + "','en-US','"
            + sourceSkuId
            + "','',"
            + "'"
            + fieldValuesJson
            + "'::jsonb,'DRAFT',0,now(),now())");
    return id;
  }

  // ==================================================================
  // 一、能力声明
  // ==================================================================

  @Test
  @DisplayName("16.2.2.1 能力声明：五个维度量不到就如实标 available=false + ownerModule，不假装有分数")
  void capabilitiesDeclareUnknownDimensions() {
    var capabilities = as("channel.operation.read", () -> precheck.capabilities());

    assertThat(capabilities.dimensions()).hasSize(6);
    assertThat(capabilities.dimensionOrder())
        .containsExactly("CONTENT", "CERTIFICATION", "SIZE_SPEC", "MATERIAL", "HAZMAT", "LABELING");

    var content =
        capabilities.dimensions().stream()
            .filter(d -> d.code().equals("CONTENT"))
            .findFirst()
            .orElseThrow();
    assertThat(content.available()).as("内容维度的依据已交付，量得到").isTrue();
    assertThat(content.ownerModule()).isEmpty();

    var certification =
        capabilities.dimensions().stream()
            .filter(d -> d.code().equals("CERTIFICATION"))
            .findFirst()
            .orElseThrow();
    assertThat(certification.available()).as("认证档案在 16.2.2.2（未交付）⇒ 量不到").isFalse();
    assertThat(certification.ownerModule()).isEqualTo("16.2.2.2");
    assertThat(certification.impact()).as("量不到的影响必须说清楚").contains("16.2.2.2");

    for (String code : List.of("SIZE_SPEC", "MATERIAL", "HAZMAT", "LABELING")) {
      var dimension =
          capabilities.dimensions().stream()
              .filter(d -> d.code().equals(code))
              .findFirst()
              .orElseThrow();
      assertThat(dimension.available()).as(code + " 的细则归 16.2.1.1（未交付）⇒ 量不到").isFalse();
      assertThat(dimension.ownerModule()).isEqualTo("16.2.1.1");
    }

    assertThat(capabilities.unknownSemantics()).as("「量不到」的口径必须写出来").contains("不计入");
    assertThat(capabilities.scoreSemantics()).as("得分口径必须写出来").contains("0..1");
    assertThat(capabilities.enforcementNote()).as("拦截归 16.2.1.2，必须写明本模块不拦截").contains("16.2.1.2");
    assertThat(capabilities.boundaryNote()).as("与 4.2.3.2 的边界必须写明").contains("4.2.3.2");
  }

  // ==================================================================
  // 二、分维度得分 + 不可知不进分母
  // ==================================================================

  @Test
  @DisplayName("16.2.2.1 分维度得分：不可知分量不计入分母（内容维度 1.0000，不是 4/11）")
  void unknownChecksDoNotEnterTheDenominator() {
    UUID shop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    var draft =
        saveDraft(
            shop,
            siteUs,
            null,
            text("TITLE", "合规的标题"),
            text("DESCRIPTION", "合规的描述"),
            items("IMAGES", "https://img/1.jpg", "https://img/2.jpg"));

    var report = check(draft.draftId().toString());

    var content = dimension(report, "CONTENT");
    assertThat(content.state()).as("内容维度量得到").isEqualTo("SCORED");
    assertThat(content.score())
        .as("能力键缺失（video_support / a_plus_content）与细则归别人的四栏都是 UNKNOWN，不进分母")
        .isEqualByComparingTo(new BigDecimal("1.0000"));
    assertThat(content.passedItemCount()).isGreaterThan(0);
    assertThat(content.failedItemCount()).isZero();
    assertThat(content.unknownItemCount())
        .as("量不到的那几项必须显式计数，不能悄悄消失")
        .isGreaterThan(0);

    // 🔴 五个量不到的维度：得分是 null（**不是 0**），且各自带归属模块与原因。
    for (String code : List.of("CERTIFICATION", "SIZE_SPEC", "MATERIAL", "HAZMAT", "LABELING")) {
      var dimension = dimension(report, code);
      assertThat(dimension.state()).isEqualTo("UNKNOWN");
      assertThat(dimension.score()).as(code + " 量不到 ⇒ 得分是 null，不是 0 分").isNull();
      assertThat(dimension.ownerModule()).isNotBlank();
      assertThat(dimension.unavailableReason()).isNotBlank();
    }

    // 🔴 总体得分 = 被评出分的维度的得分（量不到的维度权重不进分母）。
    assertThat(report.header().overallScore())
        .as("五个未知维度不稀释总分：总体 = 内容维度得分 1.0000，不是 0.3000")
        .isEqualByComparingTo(new BigDecimal("1.0000"));
    assertThat(report.header().state()).isEqualTo("SCORED");
    assertThat(report.header().verdict()).isEqualTo("PASS");
    assertThat(report.header().hasUnknownDimensions()).isTrue();
    assertThat(report.header().scoredDimensionCount()).isEqualTo(1);
    assertThat(report.header().unknownDimensionCount()).isEqualTo(5);
  }

  @Test
  @DisplayName("16.2.2.1 全维度不可知时给 UNKNOWN 而不是 0 分（overall_score 是 null）")
  void allDimensionsUnknownGivesNullNotZero() {
    // 一个**没有登记任何字段要求**的测试平台：内容维度没有任何可判定的检查项。
    TestPlatform platform = seedTestPlatform("TESTPLAT" + UUID.randomUUID().toString().substring(0, 4));
    UUID shop = seedShop("TPL-" + UUID.randomUUID().toString().substring(0, 6),
        platform.platformId(), platform.marketplaceId());
    UUID skuId = UUID.randomUUID();
    catalog.putSku(skuId, "不存在的品牌");
    // catalog 读不到 ⇒ 品牌这一项也量不到 ⇒ 内容维度一个可评的检查项都没有。
    catalog.skuStatus = 500;

    UUID draftId =
        seedDraftRaw(shop, platform.platformId(), platform.marketplaceId(), skuId, "{}");
    var report = check(draftId.toString());

    assertThat(report.header().scoredDimensionCount()).as("一个维度都评不出分").isZero();
    assertThat(report.header().state()).isEqualTo("UNKNOWN");
    assertThat(report.header().overallScore())
        .as("全维度不可知时总体得分是 null，**不是 0 分**")
        .isNull();
    assertThat(report.header().verdict()).isEqualTo("UNKNOWN");
    assertThat(report.header().hasUnknownDimensions()).isTrue();
    for (CompliancePrecheckDimensionScore dimension : report.dimensions()) {
      assertThat(dimension.state()).isEqualTo("UNKNOWN");
      assertThat(dimension.score()).isNull();
      assertThat(dimension.ownerModule()).as("量不到的维度必须说清归谁").isNotBlank();
    }
    // 数据库里存的也是 null
    assertThat(scalar("select coalesce(overall_score::text,'<null>') from compliance_check_report where id='"
            + report.header().reportId() + "'"))
        .isEqualTo("<null>");
  }

  // ==================================================================
  // 三、违规真算
  // ==================================================================

  @Test
  @DisplayName("16.2.2.1 违规真算：标题超长逐条指到字段，且总分按比例下来")
  void longTitleIsAViolation() {
    UUID shop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    String tooLong = "标".repeat(250);
    var draft =
        saveDraft(
            shop,
            siteUs,
            null,
            text("TITLE", tooLong),
            text("DESCRIPTION", "合规的描述"),
            items("IMAGES", "https://img/1.jpg"));

    var report = check(draft.draftId().toString());

    var violation =
        report.findings().stream()
            .filter(f -> f.checkCode().equals("FIELD_MAX_LENGTH"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("没有报出标题超长"));
    assertThat(violation.dimensionCode()).isEqualTo("CONTENT");
    assertThat(violation.severity()).isEqualTo("BLOCKING");
    assertThat(violation.subjectRef()).isEqualTo("TITLE");
    assertThat(violation.location()).isEqualTo("标题");
    assertThat(violation.message()).contains("250").contains("200");
    assertThat(violation.suggestion()).as("每条问题都要有整改建议").isNotBlank();
    assertThat(violation.ownerModule()).isEqualTo("16.2.2.1");
    assertThat(violation.evidence()).containsEntry("limit", 200).containsEntry("actual", 250);

    var content = dimension(report, "CONTENT");
    assertThat(content.state()).isEqualTo("SCORED");
    assertThat(content.failedItemCount()).isEqualTo(1);
    assertThat(content.score()).isLessThan(new BigDecimal("1.0000"));
    assertThat(report.header().verdict()).isEqualTo("ATTENTION");
    assertThat(report.header().failedItemCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("16.2.2.1 必填缺失是阻断项，且问题清单的顺序是确定的")
  void missingRequiredFieldIsBlocking() {
    UUID shop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    var draft = saveDraft(shop, siteUs, null, text("TITLE", "只有标题"));

    var report = check(draft.draftId().toString());

    var missing =
        report.findings().stream()
            .filter(f -> f.checkCode().equals("FIELD_REQUIRED_MISSING"))
            .toList();
    assertThat(missing).extracting(CompliancePrecheckFinding::subjectRef)
        .as("Amazon 的必填栏：DESCRIPTION 与 IMAGES 都缺")
        .containsExactly("DESCRIPTION", "IMAGES");
    assertThat(missing).allMatch(f -> f.severity().equals("BLOCKING"));
    assertThat(report.header().verdict()).isEqualTo("ATTENTION");

    // 🔴 顺序必须是**确定的**：所有违规排在所有「量不到」之前。
    //    本表有 (tenant_id, report_id, dimension_code, id) 索引，按随机 UUID 扫出来的行序是随机的 ——
    //    顺序必须由生产时定下的序号承担，不靠读的时候碰运气。
    List<CompliancePrecheckFinding> all = report.findings();
    int firstUnknown = -1;
    for (int i = 0; i < all.size(); i++) {
      if ("UNKNOWN".equals(all.get(i).state())) {
        firstUnknown = i;
        break;
      }
    }
    assertThat(firstUnknown).as("清单里必须同时有违规项与量不到的项").isGreaterThan(0);
    assertThat(all.subList(firstUnknown, all.size()))
        .as("量不到的条目必须全部排在违规项之后")
        .allMatch(f -> f.state().equals("UNKNOWN"));

    // 重复读两次，行序必须一模一样
    var again = as("channel.operation.read", () -> precheck.report(report.header().reportId()));
    assertThat(again.findings().stream().map(CompliancePrecheckFinding::findingId).toList())
        .isEqualTo(all.stream().map(CompliancePrecheckFinding::findingId).toList());
  }

  @Test
  @DisplayName("16.2.2.1 平台改为不支持某一栏后，既有草稿里那一栏变成阻断项")
  void unsupportedFieldIsAViolation() {
    // 🔴 场景是真实的：平台要求注册表是**全局码表**，平台改版时它会变；
    //   而内容草稿是租户数据，会一直留着改版前填的内容。
    //   编辑器与 DB 触发器只拦「保存时」的非法字段，**拦不住事后改版** —— 这一条就是兜这个的。
    TestPlatform platform = seedTestPlatform("TESTUNS" + UUID.randomUUID().toString().substring(0, 4));
    seedFieldRequirement(platform.platformId(), "TITLE", "REQUIRED");
    UUID shop = seedShop("UNS-" + UUID.randomUUID().toString().substring(0, 6),
        platform.platformId(), platform.marketplaceId());
    var draft = saveDraft(shop, platform.marketplaceId(), null, text("TITLE", "标题"));

    // 平台改版：这一栏变成明确不支持（注册表要求 UNSUPPORTED 时不许再带任何上限与能力门控）。
    exec(
        "update platform_listing_field_requirement"
            + " set requirement='UNSUPPORTED', min_length=null, max_length=null,"
            + "     min_items=null, max_items=null, requires_capability=null"
            + " where platform_id='" + platform.platformId() + "' and field_key='TITLE'");

    var report = check(draft.draftId().toString());

    var violation =
        report.findings().stream()
            .filter(f -> f.checkCode().equals("FIELD_UNSUPPORTED"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("没有报出平台不支持的栏"));
    assertThat(violation.subjectRef()).isEqualTo("TITLE");
    assertThat(violation.severity()).isEqualTo("BLOCKING");
    assertThat(violation.message()).contains("不支持");
    assertThat(report.header().verdict()).isEqualTo("ATTENTION");
  }

  @Test
  @DisplayName("16.2.2.1 能力键缺失 = UNKNOWN（不是 false、不是「没约束」）")
  void missingCapabilityKeyIsUnknown() {
    UUID shop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    var draft =
        saveDraft(
            shop,
            siteUs,
            null,
            text("TITLE", "标题"),
            text("DESCRIPTION", "描述"),
            items("IMAGES", "https://img/1.jpg"),
            text("VIDEO", "https://video/1.mp4"));

    var report = check(draft.draftId().toString());

    // 🔴 平台能力矩阵里没有 video_support / a_plus_content 这两个键 ⇒ 那一栏**量不到**。
    var unknown =
        report.findings().stream()
            .filter(f -> f.checkCode().equals("FIELD_CAPABILITY_UNKNOWN"))
            .filter(f -> f.subjectRef().equals("VIDEO"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("没有报出能力键缺失"));
    assertThat(unknown.subjectRef()).isEqualTo("VIDEO");
    assertThat(unknown.state()).isEqualTo("UNKNOWN");
    assertThat(unknown.ownerModule()).isEqualTo("2.1.1.2");
    assertThat(unknown.message()).contains("video_support");
    assertThat(unknown.suggestion()).isNotBlank();

    // 🔴 能力键缺失**不产生违规**：不把「没声明」当成「不支持」。
    assertThat(report.findings())
        .as("能力键缺失不得被当成违规项")
        .noneMatch(f -> f.checkCode().equals("FIELD_CAPABILITY_UNSUPPORTED"));
    assertThat(report.header().failedItemCount()).isZero();
  }

  // ==================================================================
  // 四、品牌这一关
  // ==================================================================

  @Test
  @DisplayName("16.2.2.1 品牌不可刊登 ⇒ 阻断项，并写清归 3.2.1.2")
  void brandNotPublishableIsBlocking() {
    UUID shop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    UUID skuId = UUID.randomUUID();
    catalog.putBrand("未授权品牌");
    catalog.putSku(skuId, "未授权品牌");
    catalog.brandPublishable = false;
    catalog.brandBlockMessage = "该品牌当前没有有效授权";

    var draft =
        saveDraft(
            shop,
            siteUs,
            skuId,
            text("TITLE", "标题"),
            text("DESCRIPTION", "描述"),
            items("IMAGES", "https://img/1.jpg"));

    var report = check(draft.draftId().toString());

    var violation =
        report.findings().stream()
            .filter(f -> f.checkCode().equals("BRAND_AUTHORIZATION"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("没有报出品牌授权问题"));
    assertThat(violation.severity()).isEqualTo("BLOCKING");
    assertThat(violation.state()).isEqualTo("VIOLATION");
    assertThat(violation.message()).contains("该品牌当前没有有效授权");
    assertThat(violation.ownerModule()).isEqualTo("3.2.1.2");
  }

  @Test
  @DisplayName("16.2.2.1 catalog 读不到 ⇒ 品牌这一项量不到（不是合规、不是 0 分）")
  void catalogUnreachableMakesBrandUnknown() {
    UUID shop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    UUID skuId = UUID.randomUUID();
    catalog.putBrand("某品牌");
    catalog.putSku(skuId, "某品牌");
    catalog.skuStatus = 500;

    var draft =
        saveDraft(
            shop,
            siteUs,
            skuId,
            text("TITLE", "标题"),
            text("DESCRIPTION", "描述"),
            items("IMAGES", "https://img/1.jpg"));

    var report = check(draft.draftId().toString());

    var unknown =
        report.findings().stream()
            .filter(f -> f.checkCode().equals("BRAND_AUTHORIZATION"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("没有报出品牌量不到"));
    assertThat(unknown.state()).isEqualTo("UNKNOWN");
    assertThat(unknown.ownerModule()).isEqualTo("3.2.1.2");
    assertThat(unknown.message()).contains("读不到");

    // 🔴 品牌量不到不产生违规，也不拉低得分：其余检查项通过 ⇒ 内容维度仍是 1.0000。
    var content = dimension(report, "CONTENT");
    assertThat(content.failedItemCount()).isZero();
    assertThat(content.score()).isEqualByComparingTo(new BigDecimal("1.0000"));
    assertThat(report.header().verdict()).isEqualTo("PASS");
  }

  // ==================================================================
  // 五、两次触发语义
  // ==================================================================

  @Test
  @DisplayName("16.2.2.1 两次触发语义分得清：保存时的即时反馈 与 发布前的强制校验")
  void twoTriggerSemanticsAreDistinguishable() {
    UUID shop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    var draft =
        saveDraft(
            shop,
            siteUs,
            null,
            text("TITLE", "标题"),
            text("DESCRIPTION", "描述"),
            items("IMAGES", "https://img/1.jpg"));

    var onSave = check(draft.draftId().toString());
    var onPublish = precheckGate(draft.draftId().toString());

    assertThat(onSave.header().triggerKind()).isEqualTo("DRAFT_SAVE");
    assertThat(onPublish.header().triggerKind()).isEqualTo("PUBLISH_GATE");
    assertThat(onSave.header().reportId()).isNotEqualTo(onPublish.header().reportId());
    assertThat(onSave.header().triggerLabel()).isEqualTo("保存草稿时的即时反馈");
    assertThat(onPublish.header().triggerLabel()).isEqualTo("发布前的强制校验");

    var history = as("channel.operation.read", () -> precheck.reports(null, null, 0, 50));
    assertThat(history.items())
        .as("历史里两次体检分得清")
        .extracting(CompliancePrecheckReportSummary::triggerKind)
        .containsExactlyInAnyOrder("DRAFT_SAVE", "PUBLISH_GATE");
  }

  @Test
  @DisplayName("16.2.2.1 报告历史是真分页")
  void reportHistoryIsReallyPaged() {
    UUID shop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    var draft =
        saveDraft(
            shop,
            siteUs,
            null,
            text("TITLE", "标题"),
            text("DESCRIPTION", "描述"),
            items("IMAGES", "https://img/1.jpg"));
    check(draft.draftId().toString());
    check(draft.draftId().toString());
    check(draft.draftId().toString());

    var first = as("channel.operation.read", () -> precheck.reports(null, null, 0, 1));
    var second = as("channel.operation.read", () -> precheck.reports(null, null, 1, 1));
    var third = as("channel.operation.read", () -> precheck.reports(null, null, 2, 1));

    assertThat(first.total()).isEqualTo(3);
    assertThat(first.items()).hasSize(1);
    assertThat(second.items()).hasSize(1);
    assertThat(third.items()).hasSize(1);
    assertThat(List.of(first, second, third).stream().map(page -> page.items().get(0).reportId()).toList())
        .as("三页必须是三条不同的报告")
        .doesNotHaveDuplicates();

    // 按触发语义筛
    var gated = as("channel.operation.read", () -> precheck.reports(null, "PUBLISH_GATE", 0, 50));
    assertThat(gated.items()).isEmpty();
  }

  // ==================================================================
  // 六、报告是不可改写的凭据
  // ==================================================================

  @Test
  @DisplayName("16.2.2.1 报告是那一次判定的凭据：直接改 verdict 会被 DB 拒")
  void reportIsAppendOnly() {
    UUID shop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    // 标题超长 ⇒ 结论是 ATTENTION，然后试图把它改成 PASS。
    var draft =
        saveDraft(
            shop,
            siteUs,
            null,
            text("TITLE", "标".repeat(250)),
            text("DESCRIPTION", "描述"),
            items("IMAGES", "https://img/1.jpg"));
    var report = check(draft.draftId().toString());
    assertThat(report.header().verdict()).isEqualTo("ATTENTION");

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    exec(
                        "update compliance_check_report set verdict='PASS' where id='"
                            + report.header().reportId() + "'")))
        .as("事后改写结论必须被 DB 拒 —— 否则一次 ATTENTION 就能被改成 PASS")
        .isNotNull();
    assertThat(scalar("select verdict from compliance_check_report where id='"
            + report.header().reportId() + "'"))
        .isEqualTo("ATTENTION");
  }

  // ==================================================================
  // 七、只读上游 + 时间往返
  // ==================================================================

  @Test
  @DisplayName("16.2.2.1 体检对 4.2.1.1 的草稿只读：草稿内容逐字未变")
  void draftIsNotModified() {
    UUID shop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    var draft =
        saveDraft(
            shop,
            siteUs,
            null,
            text("TITLE", "标题"),
            text("DESCRIPTION", "描述"),
            items("IMAGES", "https://img/1.jpg"));
    String before =
        scalar("select field_values::text from listing_editor_draft where id='" + draft.draftId() + "'");

    check(draft.draftId().toString());

    String after =
        scalar("select field_values::text from listing_editor_draft where id='" + draft.draftId() + "'");
    assertThat(after).as("体检不得改写草稿").isEqualTo(before);
  }

  @Test
  @DisplayName("16.2.2.1 体检时刻真的写回库里（timestamptz 不偏 8 小时）")
  void checkedAtRoundTrips() {
    UUID shop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    var draft =
        saveDraft(
            shop,
            siteUs,
            null,
            text("TITLE", "标题"),
            text("DESCRIPTION", "描述"),
            items("IMAGES", "https://img/1.jpg"));

    OffsetDateTime before = OffsetDateTime.now().minusSeconds(5);
    var report = check(draft.draftId().toString());
    OffsetDateTime after = OffsetDateTime.now().plusSeconds(5);

    BigDecimal epoch =
        as(
            "channel.operation.read",
            () ->
                sql.fetchOne(
                        "select extract(epoch from checked_at) from compliance_check_report where id = ?",
                        UUID.fromString(report.header().reportId()))
                    .get(0, BigDecimal.class));
    assertThat(epoch).as("体检时刻必须真的落库").isNotNull();

    OffsetDateTime stored = OffsetDateTime.parse(report.header().checkedAt());
    BigDecimal drift =
        BigDecimal.valueOf(stored.toInstant().getEpochSecond())
            .subtract(epoch.setScale(0, java.math.RoundingMode.FLOOR));
    assertThat(drift.abs())
        .as("服务层读回的体检时刻必须与库里存的是同一刻（不偏 28800 秒）")
        .isLessThan(BigDecimal.ONE);
    assertThat(stored).isBetween(before, after);
  }

  // ==================================================================
  // 八、报告历史 / 草稿列表 / HTTP 面
  // ==================================================================

  @Test
  @DisplayName("16.2.2.1 可体检草稿列表是真分页且带标题")
  void draftListIsPaged() {
    UUID shop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    saveDraft(shop, siteUs, null, text("TITLE", "第一份草稿"), text("DESCRIPTION", "描述"));
    saveDraft(shop, siteUs, null, text("TITLE", "第二份草稿"), text("DESCRIPTION", "描述"));

    var page = as("channel.operation.read", () -> precheck.drafts(shop, 0, 1));
    assertThat(page.total()).isGreaterThanOrEqualTo(2);
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).title()).isNotBlank();
    assertThat(page.items().get(0).platformCode()).isEqualTo("AMAZON");
  }

  @Test
  @DisplayName("16.2.2.1 HTTP 面：能力声明、两个触发入口、报告、401 / 404")
  void httpSurface() throws Exception {
    UUID shop = seedShop("AMZ-" + UUID.randomUUID().toString().substring(0, 6), AMAZON, siteUs);
    var draft =
        saveDraft(
            shop,
            siteUs,
            null,
            text("TITLE", "标题"),
            text("DESCRIPTION", "描述"),
            items("IMAGES", "https://img/1.jpg"));

    http.perform(get("/channel/compliance-precheck/capabilities").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions.length()").value(6));

    http.perform(
            post("/channel/compliance-precheck/drafts/" + draft.draftId() + "/check")
                .header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.header.triggerKind").value("DRAFT_SAVE"))
        .andExpect(jsonPath("$.dimensions.length()").value(6));

    http.perform(
            post("/channel/compliance-precheck/drafts/" + draft.draftId() + "/precheck")
                .header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.header.triggerKind").value("PUBLISH_GATE"));

    http.perform(get("/channel/compliance-precheck/reports").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2));

    // 不存在的草稿 ⇒ 404
    http.perform(
            post("/channel/compliance-precheck/drafts/" + UUID.randomUUID() + "/check")
                .header("Authorization", bearer))
        .andExpect(status().isNotFound());

    // 不存在的报告 ⇒ 404
    http.perform(get("/channel/compliance-precheck/reports/" + UUID.randomUUID())
            .header("Authorization", bearer))
        .andExpect(status().isNotFound());

    // 无会话 ⇒ 401
    http.perform(get("/channel/compliance-precheck/capabilities")).andExpect(status().isUnauthorized());
    http.perform(post("/channel/compliance-precheck/drafts/" + draft.draftId() + "/check"))
        .andExpect(status().isUnauthorized());
  }

  /**
   * 🔴 收尾：停掉本地桩与 Testcontainers 容器。
   *
   * <p>不回收容器的话，Ryuk 只在 JVM 退出时按 session 标签统一回收 —— 整个模块跑完之前容器一直活着
   * （实测 channel 一个服务就能堆到 110+ 个 postgres 容器），容器内存 + Docker VM 把机器压进 swap，
   * JVM 被卡住十几分钟 ⇒ 全量跑时断言假红、SSL 握手失败。
   *
   * <p>顺序：先停外部服务，再停容器。反了会出现「容器已停但还有 in-flight 请求打进来」。
   */
  @AfterAll
  static void stopTestResources() {
    if (identity != null) identity.stop(0);
    if (catalog != null) catalog.close();
    pg.stop();
  }
}
