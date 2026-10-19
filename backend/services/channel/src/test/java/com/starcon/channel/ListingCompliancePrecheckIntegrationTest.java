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
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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
 * 4.2.3.2 合规预检 的集成测试。
 *
 * <p>蓝图：「发布前扫描内容的违规风险：违禁词/敏感词命中、极限词、品牌侵权（外观/商标）、平台类目必填缺失、
 * 图片不合规（尺寸/白底/水印）、认证缺失。……输出：风险清单（风险等级、命中位置、规则来源、建议替换）；
 * 阻断项（未修复不允许发布）与提醒项。……<b>阻断项在服务端强制校验</b>，前端提示不能作为唯一防线。」
 *
 * <p>🔴 本测试类要打穿的是蓝图里那两句加粗：
 *
 * <ol>
 *   <li><b>风险清单四要素缺一不可</b>：每条风险都必须带风险等级、命中位置（指到具体字段与第几项）、
 *       规则来源、建议替换 —— 逐条断言，不是抽查一条。
 *   <li><b>阻断项在服务端强制校验</b>：服务层的发布闸门给出 allowed=false，
 *       <b>而且 DB 层的触发器会把 4.2.1.1 的入队动作直接拒掉</b> ——
 *       「绕过界面直接调提交端点」这条路上也有拒绝证据，不是只在前端提示。
 * </ol>
 *
 * <p>🔴 另一条贯穿全类的不变量：**量不到 ≠ 合规**。上游读不到时该维度必须是 UNKNOWN，
 * 而且**不计入已判定维度的分母**、结论不能是 PASS。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingCompliancePrecheckIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static StubComplianceCatalogServer catalog;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  /** V1 迁移随代码发布的平台码表（只读），测试直接用它，不自己造一份。 */
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

  UUID tenant;
  String bearer;
  AccessService.Access actor;
  UUID siteUs;
  UUID shopId;

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
      catalog = new StubComplianceCatalogServer();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
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
    r.add("CATALOG_URL", catalog::base);
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired ListingCompliancePrecheckService service;
  @Autowired ListingEditorService editor;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String permission, java.util.function.Supplier<T> work) {
    return access.run(actor, permission, work);
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

  @BeforeEach
  void freshTenant() {
    catalog.reset();
    tenant = UUID.randomUUID();
    bearer = "Bearer t-" + tenant;
    actors.put(bearer, actor(user, tenant, List.of(grant("ALL", allPermissions))));
    actor = actors.get(bearer);
    siteUs = marketplaceId(AMAZON, "US");
    shopId = UUID.randomUUID();
    execAsTenant(
        tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('"
            + tenant
            + "','"
            + shopId
            + "','"
            + AMAZON
            + "','SHOP-"
            + UUID.randomUUID().toString().substring(0, 6)
            + "','测试店铺')");
    execAsTenant(
        tenant,
        "insert into shop_marketplace (tenant_id,shop_id,marketplace_id,status) values ('"
            + tenant
            + "','"
            + shopId
            + "','"
            + siteUs
            + "','ACTIVE')");
  }

  /**
   * 源商品：登记到 stub catalog 上（带品牌名），返回 SKU id。
   *
   * <p>🔴 品牌维度要能从「源商品的品牌名」读到东西，源商品就必须真的存在于上游 ——
   * 否则读不到会走 UNKNOWN 分支，测的就不是「品牌侵权判定」而是「上游挂了」。
   */
  UUID sourceSku(String brandName) {
    UUID skuId = UUID.randomUUID();
    catalog.putSku(skuId, brandName);
    return skuId;
  }

  /** 建一份编辑器草稿（扫描的内容来源就是它）。 */
  UUID saveDraft(String title, List<String> bullets, String imageKey, String categoryPath) {
    return saveDraft(sourceSku("未登记品牌"), title, bullets, imageKey, categoryPath);
  }

  /** 建一份编辑器草稿，源商品已指定。 */
  UUID saveDraft(
      UUID sourceSkuId, String title, List<String> bullets, String imageKey, String categoryPath) {
    List<EditorFieldValue> values = new java.util.ArrayList<>();
    values.add(new EditorFieldValue("TITLE", title, null, null, null));
    if (bullets != null) {
      values.add(new EditorFieldValue("BULLETS", null, bullets, null, null));
    }
    values.add(new EditorFieldValue("DESCRIPTION", "普通描述内容", null, null, null));
    if (imageKey != null) {
      values.add(new EditorFieldValue("IMAGES", null, List.of(imageKey), null, null));
    }
    EditorDraftRow row =
        as(
            "channel.operation.create",
            () ->
                editor.save(
                    new EditorDraftRequest(
                        shopId,
                        siteUs,
                        "en-US",
                        sourceSkuId,
                        null,
                        categoryPath == null ? "" : categoryPath,
                        values,
                        "")));
    return row.draftId();
  }

  /** 往租户词库里加一个词。 */
  UUID addWord(
      String word, String category, String severity, String riskLevel, String ruleSource,
      String suggestion) {
    return as(
            "channel.operation.create",
            () ->
                service.saveWord(
                    null,
                    new ListingComplianceWordInput(
                        word, category, severity, riskLevel, ruleSource, suggestion, null, null, "")))
        .wordId();
  }

  /** 直接以租户身份往 listing_editor_submission 插一条入队记录（用来打 DB 层的触发器）。 */
  void insertSubmission(UUID draftId, String idempotencyKey) {
    execAsTenant(
        tenant,
        "insert into listing_editor_submission(id,tenant_id,draft_id,platform_id,shop_id,marketplace_id,"
            + "locale,state,content_snapshot,validation_snapshot,idempotency_key) values ('"
            + UUID.randomUUID()
            + "','"
            + tenant
            + "','"
            + draftId
            + "','"
            + AMAZON
            + "','"
            + shopId
            + "','"
            + siteUs
            + "','en-US','QUEUED','{}'::jsonb,'{}'::jsonb,'"
            + idempotencyKey
            + "')");
  }

  // ==================================================================
  // 一、能力声明：未交付的段必须 available=false + ownerModule
  // ==================================================================

  @Test
  @DisplayName("4.2.3.2 能力声明：四个未交付段 available=false + ownerModule，不假装有")
  void capabilitiesDeclareMissingUpstreams() {
    var caps = as("channel.operation.read", () -> service.capabilities());
    Map<String, ListingComplianceCapabilityDimension> byDimension = new java.util.HashMap<>();
    for (ListingComplianceCapabilityDimension dimension : caps.dimensions()) {
      byDimension.put(dimension.dimension(), dimension);
    }
    assertThat(byDimension.get("certification").available())
        .as("16.2.2.2 认证与资质未交付 ⇒ 认证维度 available=false").isFalse();
    assertThat(byDimension.get("certification").ownerModule()).isEqualTo("16.2.2.2");
    assertThat(byDimension.get("certification").impact()).contains("不算通过");

    assertThat(byDimension.get("platform_rule_library").available())
        .as("16.2.1.1 合规规则库未交付 ⇒ available=false").isFalse();
    assertThat(byDimension.get("platform_rule_library").ownerModule()).isEqualTo("16.2.1.1");

    assertThat(byDimension.get("compliance_health_report").available())
        .as("16.2.2.1 合规预检（体检报告）是同批并行车道，此刻不存在 ⇒ available=false").isFalse();
    assertThat(byDimension.get("compliance_health_report").ownerModule()).isEqualTo("16.2.2.1");

    assertThat(byDimension.get("publish_dispatch").available())
        .as("4.3.1.1 一键刊登同批并行、尚未交付 ⇒ available=false").isFalse();
    assertThat(byDimension.get("publish_dispatch").ownerModule()).isEqualTo("4.3.1.1");

    assertThat(byDimension.get("server_side_enforcement").available())
        .as("阻断项在服务端强制校验这一段是本模块自己交付的").isTrue();
    assertThat(caps.serverEnforcementNote()).contains("服务端").contains("前端");
  }

  // ==================================================================
  // 二、词命中：四要素缺一不可 + 命中位置指到具体字段与第几项
  // ==================================================================

  @Test
  @DisplayName("4.2.3.2 风险清单四要素：风险等级 / 命中位置 / 规则来源 / 建议替换，一样都不少")
  void riskRowsCarryAllFourElements() {
    addWord("最", "SUPERLATIVE", "BLOCKING", "HIGH", "平台极限词规则（2026 版）", "改为「更」或删掉");
    UUID draftId = saveDraft("这是最好的耳机", List.of("第一项卖点", "第二项也最好"), null, "");

    var result = as("channel.operation.read", () -> service.scan(draftId, "MANUAL", bearer));

    assertThat(result.risks()).as("极限词命中应产出风险").isNotEmpty();
    for (ListingComplianceRiskRow risk : result.risks()) {
      assertThat(risk.riskLevel()).as("风险等级").isNotBlank();
      assertThat(risk.fieldKey()).as("命中位置：字段键").isNotBlank();
      assertThat(risk.fieldLabel()).as("命中位置：字段中文名").isNotBlank();
      assertThat(risk.ruleSource()).as("规则来源").isNotBlank();
      assertThat(risk.suggestion()).as("建议替换").isNotBlank();
    }

    ListingComplianceRiskRow titleRisk =
        result.risks().stream()
            .filter(r -> "TITLE".equals(r.fieldKey()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("标题里的极限词必须被指到 TITLE 字段"));
    assertThat(titleRisk.occurrenceIndex()).as("标题不是列表字段").isEqualTo(-1);
    assertThat(titleRisk.matchedText()).isEqualTo("最");
    assertThat(titleRisk.suggestion()).isEqualTo("改为「更」或删掉");
    assertThat(titleRisk.ruleSource()).isEqualTo("平台极限词规则（2026 版）");
    assertThat(titleRisk.severity()).isEqualTo("BLOCKING");
    assertThat(titleRisk.riskLevel()).isEqualTo("HIGH");
    assertThat(titleRisk.dimension()).isEqualTo("SUPERLATIVE_WORD");

    ListingComplianceRiskRow bulletRisk =
        result.risks().stream()
            .filter(r -> "BULLETS".equals(r.fieldKey()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("卖点里的极限词必须被指到 BULLETS 字段"));
    assertThat(bulletRisk.occurrenceIndex()).as("命中位置要指到列表里的第几项").isEqualTo(1);
  }

  @Test
  @DisplayName("4.2.3.2 词命中大小写不敏感；ASCII 词要求词边界（best 不该命中 bestseller）")
  void wordMatchingIsCaseInsensitiveAndBounded() {
    addWord("Best", "PROHIBITED", "BLOCKING", "CRITICAL", "品牌保护词表", "改成 Better choice");
    UUID draftId = saveDraft("This is the BEST product", List.of("bestseller in town"), null, "");

    var result = as("channel.operation.read", () -> service.scan(draftId, "MANUAL", bearer));

    assertThat(result.risks())
        .as("大小写不同也要命中（Best / BEST）")
        .anyMatch(r -> "TITLE".equals(r.fieldKey()));
    assertThat(result.risks())
        .as("bestseller 不是 best 的命中（词边界）")
        .noneMatch(r -> "BULLETS".equals(r.fieldKey()));
  }

  // ==================================================================
  // 三、🔴 阻断项在服务端强制校验（服务层 + DB 层）
  // ==================================================================

  @Test
  @DisplayName("4.2.3.2 阻断项在服务端强制校验：发布闸门 allowed=false，且 DB 层直接拒写入队")
  void blockingRisksAreEnforcedServerSide() {
    addWord("违禁", "PROHIBITED", "BLOCKING", "CRITICAL", "平台违禁词规则", "删掉这个词");
    UUID draftId = saveDraft("含违禁词的标题", null, null, "");

    var result = as("channel.operation.read", () -> service.scan(draftId, "MANUAL", bearer));
    assertThat(result.blockingCount()).as("违禁词命中是阻断项").isGreaterThan(0);
    assertThat(result.conclusion()).isEqualTo("BLOCKED");

    // 服务层：发布闸门
    var gate = as("channel.operation.read", () -> service.publishGate(draftId));
    assertThat(gate.allowed()).as("有未修复的阻断项 ⇒ 服务端不允许发布").isFalse();
    assertThat(gate.blockers()).as("闸门要逐条给出阻断项").isNotEmpty();
    assertThat(gate.note()).contains("未修复不允许发布");

    // 🔴 DB 层：绕过前端直接入队，数据库必须拒
    Throwable rejected =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> insertSubmission(draftId, "idem-" + UUID.randomUUID()));
    assertThat(rejected).as("DB 触发器必须拒掉有未修复阻断项的入队").isNotNull();
    assertThat(rootMessage(rejected))
        .as("拒绝理由要说清是合规阻断项")
        .contains("unresolved blocking compliance risk");

    // 处理掉阻断项之后，闸门放行、DB 也放行
    List<UUID> riskIds = result.risks().stream().map(ListingComplianceRiskRow::riskId).toList();
    as("channel.operation.update", () -> service.decideRisks(riskIds, "RESOLVED", "已改写文案"));
    var gateAfter = as("channel.operation.read", () -> service.publishGate(draftId));
    assertThat(gateAfter.allowed()).as("阻断项处理完之后服务端放行").isTrue();
    insertSubmission(draftId, "idem-" + UUID.randomUUID());
    assertThat(
            scalar(
                "select count(*) from listing_editor_submission where tenant_id='"
                    + tenant
                    + "' and draft_id='"
                    + draftId
                    + "'"))
        .as("没有阻断项时入队必须成功").isEqualTo("1");
  }

  @Test
  @DisplayName("4.2.3.2 风险处置：标记已修复会立刻改写扫描的阻断项计数")
  void resolvingRisksUpdatesScanCounts() {
    addWord("违禁", "PROHIBITED", "BLOCKING", "CRITICAL", "平台违禁词规则", "删掉这个词");
    UUID draftId = saveDraft("含违禁词的标题", null, null, "");
    var result = as("channel.operation.read", () -> service.scan(draftId, "MANUAL", bearer));
    assertThat(result.blockingCount()).isGreaterThan(0);

    as(
        "channel.operation.update",
        () ->
            service.decideRisks(
                result.risks().stream().map(ListingComplianceRiskRow::riskId).toList(),
                "IGNORED",
                "已与平台确认该词可用"));

    var after = as("channel.operation.read", () -> service.scanDetail(result.scanId()));
    assertThat(after.blockingCount()).as("已忽略的阻断项不再计入").isZero();
    assertThat(after.conclusion()).isNotEqualTo("BLOCKED");
  }

  // ==================================================================
  // 四、🔴 量不到 ≠ 合规
  // ==================================================================

  @Test
  @DisplayName("4.2.3.2 量不到不是合规：认证维度恒为 UNKNOWN 且不计入已判定维度的分母")
  void unknownDimensionsAreNotCountedAsPassed() {
    UUID brandId = UUID.randomUUID();
    catalog.putBrand(brandId, "已授权品牌", true, null, null);
    catalog.putAuthorization(brandId, true, 365);
    UUID draftId = saveDraft(sourceSku("已授权品牌"), "一个没有风险词的标题", null, null, "");
    var result = as("channel.operation.read", () -> service.scan(draftId, "MANUAL", bearer));

    ListingComplianceDimension certification =
        result.dimensions().stream()
            .filter(d -> "CERTIFICATION".equals(d.dimension()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("认证维度必须在维度清单里"));
    assertThat(certification.state()).isEqualTo("UNKNOWN");
    assertThat(certification.ownerModule()).isEqualTo("16.2.2.2");
    assertThat(certification.riskCount()).as("量不到的维度不产出风险").isZero();

    long unknownCount =
        result.dimensions().stream().filter(d -> "UNKNOWN".equals(d.state())).count();
    assertThat(result.evaluatedDimensionCount())
        .as("已判定维度数 = 总维度数 - 量不到的维度数（量到的不进分母）")
        .isEqualTo(result.totalDimensionCount() - unknownCount);
    assertThat(result.conclusion())
        .as("有维度没量到 ⇒ 结论不能是 PASS（那会被读成「合规通过」）")
        .isEqualTo("INCOMPLETE");
    assertThat(result.state()).isEqualTo("PARTIAL");
  }

  @Test
  @DisplayName("4.2.3.2 上游都接得上时，只有认证维度量不到（6/7 个维度已判定）")
  void onlyCertificationStaysUnknownWhenEveryUpstreamAnswers() {
    UUID brandId = UUID.randomUUID();
    catalog.putBrand(brandId, "有授权品牌", true, null, null);
    catalog.putAuthorization(brandId, true, 365);
    String internalCategoryId = UUID.randomUUID().toString();
    catalog.putCategoryMapping("AMAZON", "US", internalCategoryId, "Electronics > Audio");
    catalog.putRequiredAttribute(UUID.fromString(internalCategoryId), "material", "material", "RECOMMENDED");

    UUID draftId =
        saveDraft(
            sourceSku("有授权品牌"),
            "一个没有风险词的标题",
            null,
            null,
            "Electronics > Audio");
    var result = as("channel.operation.read", () -> service.scan(draftId, "MANUAL", bearer));

    long unknownCount =
        result.dimensions().stream().filter(d -> "UNKNOWN".equals(d.state())).count();
    assertThat(unknownCount).as("只有认证维度量不到").isEqualTo(1);
    assertThat(result.evaluatedDimensionCount()).isEqualTo(result.totalDimensionCount() - 1);
    assertThat(result.totalDimensionCount()).isEqualTo(7);
    assertThat(result.conclusion())
        .as("没有阻断项、但认证维度没量到 ⇒ INCOMPLETE，不是 PASS")
        .isEqualTo("INCOMPLETE");
    assertThat(result.dimensions())
        .filteredOn(d -> "CERTIFICATION".equals(d.dimension()))
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.state()).isEqualTo("UNKNOWN");
              assertThat(d.ownerModule()).isEqualTo("16.2.2.2");
            });
  }

  @Test
  @DisplayName("4.2.3.2 上游读不到 ⇒ 该维度 UNKNOWN，而不是「无风险」")
  void upstreamFailureMakesDimensionUnknown() {
    UUID draftId = saveDraft("一个没有风险词的标题", null, null, "");
    catalog.skuStatus = 500;
    try {
      var result = as("channel.operation.read", () -> service.scan(draftId, "MANUAL", bearer));
      ListingComplianceDimension brand =
          result.dimensions().stream()
              .filter(d -> "BRAND_INFRINGEMENT".equals(d.dimension()))
              .findFirst()
              .orElseThrow(() -> new AssertionError("品牌维度必须在维度清单里"));
      assertThat(brand.state()).as("源商品读不到 ⇒ 品牌维度 UNKNOWN").isEqualTo("UNKNOWN");
      assertThat(brand.note()).isNotBlank();
      assertThat(brand.ownerModule()).isNotBlank();
      assertThat(result.risks())
          .as("读不到上游不产出任何「合规」结论")
          .noneMatch(r -> "BRAND_INFRINGEMENT".equals(r.dimension()));
    } finally {
      catalog.skuStatus = 0;
    }
  }

  // ==================================================================
  // 五、品牌侵权 / 平台类目必填 / 图片不合规
  // ==================================================================

  @Test
  @DisplayName("4.2.3.2 品牌侵权：品牌未在租户品牌库登记 ⇒ 阻断项")
  void unregisteredBrandIsBlocking() {
    UUID draftId = saveDraft(sourceSku("未登记品牌"), "一个没有风险词的标题", null, null, "");
    var result = as("channel.operation.read", () -> service.scan(draftId, "MANUAL", bearer));

    ListingComplianceRiskRow brand =
        result.risks().stream()
            .filter(r -> "BRAND_INFRINGEMENT".equals(r.dimension()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("源商品的品牌不在租户品牌库 ⇒ 必须判为阻断项"));
    assertThat(brand.severity()).isEqualTo("BLOCKING");
    assertThat(brand.code()).isEqualTo("BRAND_NOT_REGISTERED");
    assertThat(brand.fieldKey()).isEqualTo("BRAND");
    assertThat(brand.suggestion()).isNotBlank();
  }

  @Test
  @DisplayName("4.2.3.2 品牌侵权：品牌已登记但授权全部失效 ⇒ 阻断项")
  void invalidAuthorizationIsBlocking() {
    UUID brandId = UUID.randomUUID();
    catalog.putBrand(brandId, "无授权品牌", false, "AUTHORIZATION_INVALID", "品牌授权已失效");
    catalog.putAuthorization(brandId, false, -10);
    UUID draftId = saveDraft(sourceSku("无授权品牌"), "一个没有风险词的标题", null, null, "");

    var result = as("channel.operation.read", () -> service.scan(draftId, "MANUAL", bearer));

    ListingComplianceRiskRow brand =
        result.risks().stream()
            .filter(r -> "BRAND_INFRINGEMENT".equals(r.dimension()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("授权失效必须判为阻断项"));
    assertThat(brand.severity()).isEqualTo("BLOCKING");
    assertThat(brand.code()).isIn("BRAND_UNAUTHORIZED", "AUTHORIZATION_INVALID");
  }

  @Test
  @DisplayName("4.2.3.2 平台类目必填缺失 ⇒ 阻断项；命中位置指到类目属性与属性码")
  void missingRequiredCategoryAttributeIsBlocking() {
    String internalCategoryId = UUID.randomUUID().toString();
    catalog.putCategoryMapping("AMAZON", "US", internalCategoryId, "Electronics > Audio");
    catalog.putRequiredAttribute(UUID.fromString(internalCategoryId), "material", "material", "REQUIRED");
    UUID draftId = saveDraft("一个没有风险词的标题", null, null, "Electronics > Audio");

    var result = as("channel.operation.read", () -> service.scan(draftId, "MANUAL", bearer));

    ListingComplianceRiskRow category =
        result.risks().stream()
            .filter(r -> "CATEGORY_REQUIRED".equals(r.dimension()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("平台类目必填缺失必须判为阻断项"));
    assertThat(category.severity()).isEqualTo("BLOCKING");
    assertThat(category.code()).isEqualTo("CATEGORY_REQUIRED_MISSING");
    assertThat(category.fieldKey()).isEqualTo("CATEGORY_ATTRIBUTES");
    assertThat(category.matchedText()).isEqualTo("material");
    assertThat(category.ruleSource()).contains("4.2.3.1");
  }

  @Test
  @DisplayName("4.2.3.2 图片不合规：尺寸不足 ⇒ 阻断项；白底未处理 ⇒ 提醒项")
  void imageViolationsAreClassified() {
    UUID recipeId = UUID.randomUUID();
    catalog.putRecipe(recipeId, "AMZ-MAIN", "AMAZON", true, "", "1000", "1000");
    UUID assetId = UUID.randomUUID();
    catalog.putMedia(assetId, "key/small.png", "小图", "500", "500", "VERIFIED", null);
    UUID draftId = saveDraft("一个没有风险词的标题", null, "key/small.png", "");

    var result = as("channel.operation.read", () -> service.scan(draftId, "MANUAL", bearer));

    ListingComplianceRiskRow tooSmall =
        result.risks().stream()
            .filter(r -> "IMAGE_TOO_SMALL".equals(r.code()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("图片尺寸不足必须判为阻断项"));
    assertThat(tooSmall.severity()).isEqualTo("BLOCKING");
    assertThat(tooSmall.fieldKey()).isEqualTo("IMAGES");
    assertThat(tooSmall.occurrenceIndex()).isEqualTo(0);

    assertThat(result.risks())
        .as("白底未处理只是疑虑（这张图本来就可能是白图）⇒ 提醒项，不是阻断项")
        .anyMatch(r -> "IMAGE_WHITE_BACKGROUND_MISSING".equals(r.code()) && "WARNING".equals(r.severity()));
    assertThat(result.risks())
        .as("没有替代文本 ⇒ 提醒项")
        .anyMatch(r -> "IMAGE_ALT_TEXT_MISSING".equals(r.code()) && "WARNING".equals(r.severity()));
  }

  // ==================================================================
  // 六、词库维护 + 真分页 + 时间往返
  // ==================================================================

  @Test
  @DisplayName("4.2.3.2 词库：增删改查 + 真分页 + 唯一约束")
  void wordLibraryCrudAndPaging() {
    for (int i = 0; i < 5; i++) {
      addWord("违禁词" + i, "PROHIBITED", "BLOCKING", "HIGH", "规则" + i, "替换" + i);
    }
    var page = as("channel.operation.read", () -> service.words(0, 2, null, null, null, null, null, null));
    assertThat(page.total()).as("总条数是真实计数").isGreaterThanOrEqualTo(5);
    assertThat(page.items()).hasSize(2);
    assertThat(page.size()).isEqualTo(2);
    assertThat(page.page()).isZero();

    var filtered =
        as("channel.operation.read", () -> service.words(0, 20, "SUPERLATIVE", null, null, null, null, null));
    assertThat(filtered.items()).as("按类型筛选是真筛选").isEmpty();

    UUID wordId =
        as(
                "channel.operation.read",
                () -> service.words(0, 20, "PROHIBITED", null, null, null, null, null))
            .items()
            .get(0)
            .wordId();
    var disabled = as("channel.operation.update", () -> service.setWordEnabled(wordId, false));
    assertThat(disabled.enabled()).isFalse();
    var enabledOnly =
        as("channel.operation.read", () -> service.words(0, 20, null, null, null, null, null, true));
    assertThat(enabledOnly.items())
        .as("停用的词不再出现在启用词表里（扫描也不该再命中它）")
        .extracting(ListingComplianceWordRow::wordId)
        .doesNotContain(wordId);
    var disabledOnly =
        as("channel.operation.read", () -> service.words(0, 20, null, null, null, null, null, false));
    assertThat(disabledOnly.items()).extracting(ListingComplianceWordRow::wordId).contains(wordId);

    as(
        "channel.operation.delete",
        () -> {
          service.deleteWord(wordId);
          return null;
        });
    assertThat(
            as(
                    "channel.operation.read",
                    () -> service.words(0, 20, null, null, null, null, "PROHIBITED", null))
                .items())
        .as("删掉的词不能再被查到")
        .extracting(ListingComplianceWordRow::wordId)
        .doesNotContain(wordId);
  }

  @Test
  @DisplayName("4.2.3.2 词库必填校验：规则来源与建议替换为空直接拒（风险清单四要素不能缺）")
  void wordLibraryRejectsIncompleteRows() {
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.create",
                        () ->
                            service.saveWord(
                                null,
                                new ListingComplianceWordInput(
                                    "x", "PROHIBITED", "BLOCKING", "HIGH", "  ", "替换", null, null, "")))))
        .as("规则来源不能为空")
        .isNotNull();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.create",
                        () ->
                            service.saveWord(
                                null,
                                new ListingComplianceWordInput(
                                    "x", "PROHIBITED", "BLOCKING", "HIGH", "来源", "  ", null, null, "")))))
        .as("建议替换不能为空")
        .isNotNull();
  }

  @Test
  @DisplayName("4.2.3.2 扫描历史真分页，且扫描时刻是写回的真实值（timestamptz 往返）")
  void scanHistoryPagingAndTimestampRoundTrip() {
    UUID draftId = saveDraft("一个没有风险词的标题", null, null, "");
    OffsetDateTime before = OffsetDateTime.now().minusMinutes(1);
    var result = as("channel.operation.read", () -> service.scan(draftId, "MANUAL", bearer));
    OffsetDateTime after = OffsetDateTime.now().plusMinutes(1);

    assertThat(result.scannedAt().toInstant())
        .as("扫描时刻必须是真实写回的值，不能只断言「没抛异常」")
        .isBetween(before.toInstant(), after.toInstant());
    // 🔴 再对着 DB 里的那一列核一次：DTO 与库里存的是同一个时刻（不是两个各自为政的时间）
    String stored =
        scalar("select extract(epoch from scanned_at) from listing_compliance_scan where id='"
            + result.scanId() + "'");
    assertThat(Double.parseDouble(stored))
        .as("DTO 里的扫描时刻必须与库里那一列是同一刻")
        .isCloseTo(
            result.scannedAt().toInstant().toEpochMilli() / 1000.0,
            org.assertj.core.data.Offset.offset(0.001));

    // 再扫一次 ⇒ 至少两条扫描，第二页才有东西
    var second = as("channel.operation.read", () -> service.scan(draftId, "MANUAL", bearer));
    assertThat(second.scanId()).isNotEqualTo(result.scanId());

    var page = as("channel.operation.read", () -> service.scans(0, 1, null, null, null));
    assertThat(page.items()).hasSize(1);
    assertThat(page.total()).as("总条数是真实计数").isGreaterThanOrEqualTo(2);
    var page2 = as("channel.operation.read", () -> service.scans(1, 1, null, null, null));
    assertThat(page2.items()).hasSize(1);
    assertThat(page2.items().get(0).scanId())
        .as("第二页必须是另一条扫描，不能重复第一条")
        .isNotEqualTo(page.items().get(0).scanId());
  }

  @Test
  @DisplayName("4.2.3.2 本模块的错误处理器不误伤别的模块：非合规的 DB 冲突仍是 409，不是 500")
  void nonComplianceDatabaseErrorStillReturns409() throws Exception {
    // 🔴 ListingComplianceErrorHandler 的优先级高于 channel 通用处理器，所以
    // DataAccessException 一律先到本类。若本类把非合规的异常**再抛出去**，
    // Spring 会把它交给默认的 500 处理 —— 别的模块（唯一约束冲突等）的 409 就变成了 500。
    // 这里用「重复录入同一个词」触发一次真实的唯一约束冲突，钉住 409 不变。
    addWord("重复词", "PROHIBITED", "BLOCKING", "HIGH", "规则", "替换");

    http.perform(
            post("/channel/listing-compliance-precheck/words")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        Map.of(
                            "word", "重复词",
                            "category", "PROHIBITED",
                            "severity", "BLOCKING",
                            "riskLevel", "HIGH",
                            "ruleSource", "规则",
                            "suggestion", "替换"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("店铺编码重复")));
  }

  @Test
  @DisplayName("4.2.3.2 DB 层强制：风险行缺四要素之一 ⇒ 直接拒写")
  void databaseRejectsIncompleteRiskRow() {    UUID draftId = saveDraft("一个没有风险词的标题", null, null, "");
    var result = as("channel.operation.read", () -> service.scan(draftId, "MANUAL", bearer));
    UUID scanId = result.scanId();

    Throwable rejected =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                execAsTenant(
                    tenant,
                    "insert into listing_compliance_risk(id,tenant_id,scan_id,dimension,severity,risk_level,"
                        + "code,field_key,field_label,rule_source,suggestion,message) values ('"
                        + UUID.randomUUID()
                        + "','"
                        + tenant
                        + "','"
                        + scanId
                        + "','PROHIBITED_WORD','BLOCKING','HIGH','WORD_HIT','TITLE','标题','','替换','说明')"));
    assertThat(rejected).as("规则来源为空串 ⇒ DB 直接拒").isNotNull();
  }

  // ==================================================================
  // 七、HTTP 端点
  // ==================================================================

  @Test
  @DisplayName("4.2.3.2 HTTP 端点：能力声明 / 扫描 / 发布闸门 / 词库")
  void httpEndpoints() throws Exception {
    addWord("违禁", "PROHIBITED", "BLOCKING", "CRITICAL", "平台违禁词规则", "删掉这个词");
    UUID draftId = saveDraft("含违禁词的标题", null, null, "");

    http.perform(get("/channel/listing-compliance-precheck/capabilities").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions[?(@.dimension=='certification')].available").value(false))
        .andExpect(jsonPath("$.dimensions[?(@.dimension=='certification')].ownerModule")
            .value("16.2.2.2"));

    http.perform(
            post("/channel/listing-compliance-precheck/scans")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content(JSON.writeValueAsString(Map.of("draftId", draftId.toString(), "triggerSource", "MANUAL"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.blockingCount").value(org.hamcrest.Matchers.greaterThan(0)))
        .andExpect(jsonPath("$.conclusion").value("BLOCKED"))
        .andExpect(jsonPath("$.risks[0].riskLevel").exists())
        .andExpect(jsonPath("$.risks[0].fieldKey").exists())
        .andExpect(jsonPath("$.risks[0].ruleSource").exists())
        .andExpect(jsonPath("$.risks[0].suggestion").exists());

    http.perform(
            get("/channel/listing-compliance-precheck/drafts/{id}/publish-gate", draftId)
                .header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(false))
        .andExpect(jsonPath("$.blockers[0].fieldKey").exists());

    http.perform(get("/channel/listing-compliance-precheck/words").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThan(0)))
        .andExpect(jsonPath("$.items[0].ruleSource").exists());

    http.perform(get("/channel/listing-compliance-precheck/drafts").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThan(0)));
  }

  // ==================================================================
  // 辅助
  // ==================================================================

  private static String rootMessage(Throwable e) {
    Throwable current = e;
    StringBuilder builder = new StringBuilder();
    while (current != null) {
      if (current.getMessage() != null) {
        builder.append(current.getMessage()).append('\n');
      }
      current = current.getCause();
    }
    return builder.toString();
  }
}
