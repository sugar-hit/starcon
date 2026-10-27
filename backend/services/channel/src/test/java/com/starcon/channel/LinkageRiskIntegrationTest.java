package com.starcon.channel;

import static com.starcon.channel.LinkageRiskModels.*;
import static com.starcon.channel.LinkageRiskRules.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * 16.5.2.2 关联风险。
 *
 * <p>本测试类钉住这几组事：
 *
 * <ol>
 *   <li><b>🔴 能力声明逐条如实</b>：支付方式 / 收款账户（12.7.1.1 未交付）与工作台预警（下游）
 *       必须 available=false + ownerModule；设备 / IP / 地址是「可登记但**不自动发现**」。</li>
 *   <li><b>🔴 阈值不给默认值</b>：没登记策略就没有图谱与判定，图谱 known=false、扫描 422。</li>
 *   <li><b>🔴 维度必须先在策略里启用才允许登记</b>（fail-closed）。</li>
 *   <li><b>🔴 法人维度自动发现</b>：边来自真实的 {@code shop.legal_entity_id}。</li>
 *   <li><b>🔴 连通分量</b>：A↔B 共享设备、B↔C 共享地址 ⇒ A/B/C 是**一组**。</li>
 *   <li><b>🔴 重扫更新而不是新增</b>：同一组关联只留一条提示。</li>
 *   <li><b>🔴 处置状态机 + 留痕 append-only</b>：终态不可回退；留痕改不动。</li>
 *   <li><b>🔴 时间字段断言写回值</b>（不是「没抛异常」）。</li>
 *   <li><b>🔴 读不到 ≠ 没有</b>：identity 不可达时法人名称是「读不到」，不是空字符串。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class LinkageRiskIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static LinkageRiskIdentityStub identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** V1 种子里 Amazon / Shopify / eBay 的平台 id。 */
  static final String AMAZON = "11111111-1111-1111-1111-111111111101";
  static final String SHOPIFY = "11111111-1111-1111-1111-111111111102";
  static final String EBAY = "11111111-1111-1111-1111-111111111103";

  UUID tenant;
  AccessService.Access actor;
  String bearer;

  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\"],\"scopeMode\":\"ALL\"}]";

  static final List<String> allPermissions =
      List.of(
          "channel.operation.read",
          "channel.operation.create",
          "channel.operation.update",
          "channel.operation.delete");

  static AccessService.Access actor(UUID uid, UUID tenantId, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenantId,
        "Test",
        UUID.randomUUID(),
        null,
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
      identity = new LinkageRiskIdentityStub();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @BeforeEach
  void freshTenant() {
    tenant = UUID.randomUUID();
    bearer = "Bearer t-" + tenant;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actor = actor(user, tenant, grants);
    actors.put(bearer, actor);
    identity.register(bearer, actor);
    identity.reset();
    truncateLinkage();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", identity::base);
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired LinkageRiskService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String permission, java.util.function.Supplier<T> work) {
    return access.run(actor, permission, work);
  }

  /** 以超级用户执行一条 SQL（绕开 RLS，只用于播种与结构断言）。 */
  static void execSuper(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以超级用户执行一条 SQL 并返回第一列。 */
  static String execSuperReturning(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(statement)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以超级用户 + 租户上下文执行一条 SQL（播种店铺要过 owner 触发器）。 */
  static void execAsTenant(UUID tenantId, String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
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

  /** 清掉本模块的表（测试之间互不干扰）。 */
  static void truncateLinkage() {
    execSuper(
        "truncate linkage_alert_action,linkage_remediation,linkage_alert,linkage_cluster_shop,"
            + "linkage_cluster,linkage_shop_identifier,linkage_identifier,"
            + "linkage_policy_dimension,linkage_policy");
  }

  /** 种一家真实店铺（走 shop 表 + owner 触发器）。 */
  UUID seedShop(String code, String platformId, String legalEntityId) {
    UUID id = UUID.randomUUID();
    execAsTenant(
        tenant,
        "insert into shop (tenant_id,id,platform_id,code,name"
            + (legalEntityId == null ? "" : ",legal_entity_id")
            + ") values ('"
            + tenant
            + "','"
            + id
            + "','"
            + platformId
            + "','"
            + code
            + "','测试店铺 "
            + code
            + "'"
            + (legalEntityId == null ? "" : ",'" + legalEntityId + "'")
            + ")");
    return id;
  }

  /** 登记一份策略（默认：阈值 2、全部租户范围、设备维度 HIGH 敏感度）。 */
  LinkageRiskPolicyView saveDefaultPolicy(String... dimensions) {
    List<LinkageRiskPolicyDimensionInput> dims = new java.util.ArrayList<>();
    for (String dimension : dimensions) {
      dims.add(new LinkageRiskPolicyDimensionInput(dimension, "HIGH", 2, "测试依据", true));
    }
    return as(
        "channel.operation.update",
        () ->
            service.savePolicy(
                new LinkageRiskPolicyInput("测试关联策略", "ACTIVE", 2, "TENANT", dims), actor));
  }

  LinkageRiskIdentifierView registerIdentifier(String dimension, String key) {
    return as(
        "channel.operation.create",
        () ->
            service.createIdentifier(
                new LinkageRiskIdentifierInput(dimension, key, key, "测试登记", null), actor));
  }

  void linkShop(String identifierId, UUID shopId) {
    as(
        "channel.operation.create",
        () ->
            service.linkShop(
                identifierId,
                new LinkageRiskShopLinkInput(shopId, "MANUAL", "测试登记", null),
                actor));
  }

  LinkageRiskScanResult scan() {
    return as("channel.operation.create", () -> service.scan(bearer, actor));
  }

  LinkageRiskGraph graph() {
    return as("channel.operation.read", () -> service.graph(bearer));
  }

  // ==================================================================
  // 1. 能力声明：读不到的维度必须如实标
  // ==================================================================

  @Test
  @DisplayName("16.5.2.2 能力声明：支付方式 / 收款账户 / 工作台预警 available=false + ownerModule")
  void capabilitiesReportUnavailableDimensionsHonestly() {
    var caps = as("channel.operation.read", service::capabilities);

    var payment =
        caps.capabilities().stream()
            .filter(c -> c.code().equals("PAYMENT_METHOD"))
            .findFirst()
            .orElseThrow();
    assertThat(payment.available()).isFalse();
    assertThat(payment.ownerModule()).isEqualTo("12.7.1.1");
    assertThat(payment.impact()).contains("未开发");

    var payout =
        caps.capabilities().stream()
            .filter(c -> c.code().equals("PAYOUT_ACCOUNT"))
            .findFirst()
            .orElseThrow();
    assertThat(payout.available()).isFalse();
    assertThat(payout.ownerModule()).isEqualTo("12.7.1.1");

    var workbench =
        caps.capabilities().stream()
            .filter(c -> c.code().equals("WORKBENCH_ALERT"))
            .findFirst()
            .orElseThrow();
    assertThat(workbench.available()).isFalse();
    assertThat(workbench.ownerModule()).isEqualTo("1");

    // 🔴 设备 / IP / 地址：可登记（available=true）但**不自动发现**（autoDiscoverable=false）
    for (String code : List.of("DEVICE", "NETWORK_IP", "ADDRESS")) {
      var capability =
          caps.capabilities().stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow();
      assertThat(capability.available()).as(code + " 可登记").isTrue();
      assertThat(capability.autoDiscoverable()).as(code + " 不自动发现").isFalse();
      assertThat(capability.impact()).contains("不自动发现");
    }

    // 🔴 法人：可判且**自动发现**（事实来自 shop.legal_entity_id）
    var legal =
        caps.capabilities().stream()
            .filter(c -> c.code().equals("LEGAL_ENTITY"))
            .findFirst()
            .orElseThrow();
    assertThat(legal.available()).isTrue();
    assertThat(legal.autoDiscoverable()).isTrue();
  }

  // ==================================================================
  // 2. 没有策略就没有判定
  // ==================================================================

  @Test
  @DisplayName("16.5.2.2 没有策略：图谱 known=false，扫描 422（阈值不给默认值）")
  void withoutPolicyNothingIsJudged() {
    seedShop("S1", AMAZON, null);

    LinkageRiskGraph graph = graph();
    assertThat(graph.known()).isFalse();
    assertThat(graph.clusters()).isEmpty();
    assertThat(graph.note()).contains("还没登记关联风险策略");

    assertThatThrownBy(() -> scan())
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("先登记关联风险策略");
  }

  @Test
  @DisplayName("16.5.2.2 策略往返：阈值由租户显式给，后端不预填默认值")
  void policyRoundTrip() {
    var before = as("channel.operation.read", service::policy);
    assertThat(before.configured()).isFalse();
    assertThat(before.minSharedShopCount()).isZero();

    var saved = saveDefaultPolicy("LEGAL_ENTITY", "DEVICE");
    assertThat(saved.configured()).isTrue();
    assertThat(saved.minSharedShopCount()).isEqualTo(2);
    assertThat(saved.evaluationScope()).isEqualTo("TENANT");
    assertThat(saved.dimensions()).hasSize(2);

    // 🔴 整体替换：再存一次只剩一个维度
    var replaced = saveDefaultPolicy("DEVICE");
    assertThat(replaced.dimensions()).hasSize(1);
    assertThat(replaced.dimensions().get(0).dimension()).isEqualTo("DEVICE");
  }

  // ==================================================================
  // 3. 登记面：fail-closed + 真校验
  // ==================================================================

  @Test
  @DisplayName("16.5.2.2 维度未在策略里启用 ⇒ 不许登记（fail-closed）")
  void registeringUnenabledDimensionIsRejected() {
    saveDefaultPolicy("LEGAL_ENTITY");

    assertThatThrownBy(() -> registerIdentifier("DEVICE", "DEV-01"))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("还没有被纳入关联风险判定");
  }

  @Test
  @DisplayName("16.5.2.2 非法 IP 字面量 ⇒ 400（不是 500），主机名也被拒")
  void invalidIpIdentifierIsRejected() {
    saveDefaultPolicy("NETWORK_IP");

    assertThatThrownBy(() -> registerIdentifier("NETWORK_IP", "shop.example.com"))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("不接受主机名");
  }

  @Test
  @DisplayName("16.5.2.2 挂到不存在的店铺 ⇒ 404（图谱里不能有幽灵节点）")
  void linkingUnknownShopIsRejected() {
    saveDefaultPolicy("DEVICE");
    var identifier = registerIdentifier("DEVICE", "DEV-404");

    assertThatThrownBy(() -> linkShop(identifier.identifierId().toString(), UUID.randomUUID()))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("店铺不存在");
  }

  // ==================================================================
  // 4. 法人维度自动发现
  // ==================================================================

  @Test
  @DisplayName("16.5.2.2 法人维度自动发现：边来自真实的 shop.legal_entity_id")
  void legalEntityLinkIsAutoDiscovered() {
    String legalEntity = LinkageRiskIdentityStub.legalEntityId();
    identity.addLegalEntity(legalEntity, "LE-1", "测试法人");
    saveDefaultPolicy("LEGAL_ENTITY");
    seedShop("S1", AMAZON, legalEntity);
    seedShop("S2", AMAZON, legalEntity);

    LinkageRiskGraph graph = graph();
    assertThat(graph.known()).isTrue();
    assertThat(graph.links()).hasSize(1);
    var link = graph.links().get(0);
    assertThat(link.dimension()).isEqualTo("LEGAL_ENTITY");
    assertThat(link.autoDiscovered()).isTrue();
    assertThat(link.factorLabel()).isEqualTo("测试法人");
    assertThat(graph.clusters()).hasSize(1);
    assertThat(graph.clusters().get(0).shopCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("16.5.2.2 法人名称读不到 ⇒ available=false + null 名称（不是空字符串）")
  void legalEntityNameUnavailableIsNotBlank() {
    String legalEntity = LinkageRiskIdentityStub.legalEntityId();
    identity.mode = LinkageRiskIdentityStub.Mode.UNAVAILABLE;
    saveDefaultPolicy("LEGAL_ENTITY");
    seedShop("S1", AMAZON, legalEntity);
    seedShop("S2", AMAZON, legalEntity);

    var shops = as("channel.operation.read", () -> service.shops(bearer, "", 0, 50));
    assertThat(shops.items()).isNotEmpty();
    var shop = shops.items().get(0);
    assertThat(shop.legalEntityNameAvailable()).isFalse();
    assertThat(shop.legalEntityName()).isNull();

    // 🔴 图谱里的法人因子也不显示成空名字
    LinkageRiskGraph graph = graph();
    assertThat(graph.links()).hasSize(1);
    assertThat(graph.links().get(0).factorLabel()).contains("读不到");
  }

  // ==================================================================
  // 5. 登记维度 + 连通分量
  // ==================================================================

  @Test
  @DisplayName("16.5.2.2 共享设备 ⇒ 一个关联组 + 一条提示 + 整改建议")
  void sharedDeviceProducesClusterAlertAndRemediations() {
    saveDefaultPolicy("DEVICE");
    UUID s1 = seedShop("S1", AMAZON, null);
    UUID s2 = seedShop("S2", AMAZON, null);
    var identifier = registerIdentifier("DEVICE", "DEV-01");
    linkShop(identifier.identifierId().toString(), s1);
    linkShop(identifier.identifierId().toString(), s2);

    LinkageRiskScanResult result = scan();
    assertThat(result.known()).isTrue();
    assertThat(result.shopsScanned()).isEqualTo(2);
    assertThat(result.linksFound()).isEqualTo(1);
    assertThat(result.clustersFound()).isEqualTo(1);
    assertThat(result.alertsCreated()).isEqualTo(1);
    assertThat(result.remediationsCreated()).isEqualTo(2); // 分离设备 + 官方 API 授权

    var alerts = as("channel.operation.read", () -> service.alerts(null, null, 0, 50));
    assertThat(alerts.total()).isEqualTo(1);
    var alert = alerts.items().get(0);
    assertThat(alert.severity()).isEqualTo("HIGH");
    assertThat(alert.shopCount()).isEqualTo(2);
    assertThat(alert.shops()).hasSize(2);
    assertThat(alert.remediations()).hasSize(2);
    assertThat(alert.remediations())
        .extracting(LinkageRiskRemediationView::recommendationCode)
        .containsExactlyInAnyOrder("SEPARATE_DEVICE", "OFFICIAL_API_AUTHORIZATION");
  }

  @Test
  @DisplayName("16.5.2.2 连通分量：A↔B 共享设备、B↔C 共享地址 ⇒ A/B/C 是一组")
  void transitiveLinkageFormsOneCluster() {
    saveDefaultPolicy("DEVICE", "ADDRESS");
    UUID s1 = seedShop("S1", AMAZON, null);
    UUID s2 = seedShop("S2", AMAZON, null);
    UUID s3 = seedShop("S3", AMAZON, null);

    var device = registerIdentifier("DEVICE", "DEV-01");
    linkShop(device.identifierId().toString(), s1);
    linkShop(device.identifierId().toString(), s2);

    var address = registerIdentifier("ADDRESS", "ADDR-01");
    linkShop(address.identifierId().toString(), s2);
    linkShop(address.identifierId().toString(), s3);

    LinkageRiskGraph graph = graph();
    assertThat(graph.links()).hasSize(2);
    assertThat(graph.clusters()).hasSize(1);
    var cluster = graph.clusters().get(0);
    assertThat(cluster.shopCount()).isEqualTo(3);
    assertThat(cluster.dimensions()).containsExactlyInAnyOrder("DEVICE", "ADDRESS");
  }

  @Test
  @DisplayName("16.5.2.2 跨平台 ⇒ 风险等级升一档（同平台不动）")
  void crossPlatformRaisesRiskLevel() {
    saveDefaultPolicy("LEGAL_ENTITY");
    String legalEntity = LinkageRiskIdentityStub.legalEntityId();
    identity.addLegalEntity(legalEntity, "LE-1", "测试法人");
    seedShop("S1", AMAZON, legalEntity);
    seedShop("S2", SHOPIFY, legalEntity);

    LinkageRiskGraph graph = graph();
    assertThat(graph.clusters()).hasSize(1);
    var cluster = graph.clusters().get(0);
    assertThat(cluster.crossPlatform()).isTrue();
    assertThat(cluster.severity()).isEqualTo("HIGH");
    assertThat(cluster.riskLevel()).isEqualTo("CRITICAL");
  }

  @Test
  @DisplayName("16.5.2.2 判定范围 PLATFORM ⇒ 跨平台共享不算关联")
  void platformScopeIgnoresCrossPlatformSharing() {
    as(
        "channel.operation.update",
        () ->
            service.savePolicy(
                new LinkageRiskPolicyInput(
                    "仅同平台",
                    "ACTIVE",
                    2,
                    "PLATFORM",
                    List.of(
                        new LinkageRiskPolicyDimensionInput("LEGAL_ENTITY", "HIGH", 2, "测试", true))),
                actor));
    String legalEntity = LinkageRiskIdentityStub.legalEntityId();
    seedShop("S1", AMAZON, legalEntity);
    seedShop("S2", SHOPIFY, legalEntity);

    LinkageRiskGraph graph = graph();
    assertThat(graph.links()).isEmpty();
    assertThat(graph.clusters()).isEmpty();
  }

  // ==================================================================
  // 6. 重扫：更新而不是新增
  // ==================================================================

  @Test
  @DisplayName("16.5.2.2 重扫同一组关联 ⇒ 更新既有提示，不是刷出新提示")
  void rescanUpdatesInsteadOfDuplicating() {
    saveDefaultPolicy("DEVICE");
    UUID s1 = seedShop("S1", AMAZON, null);
    UUID s2 = seedShop("S2", AMAZON, null);
    var identifier = registerIdentifier("DEVICE", "DEV-01");
    linkShop(identifier.identifierId().toString(), s1);
    linkShop(identifier.identifierId().toString(), s2);

    scan();
    var second = scan();
    assertThat(second.clustersCreated()).isZero();
    assertThat(second.clustersUpdated()).isEqualTo(1);
    assertThat(second.alertsCreated()).isZero();
    assertThat(second.alertsUpdated()).isEqualTo(1);

    var alerts = as("channel.operation.read", () -> service.alerts(null, null, 0, 50));
    assertThat(alerts.total()).isEqualTo(1);
    // 🔴 整改建议也不该被刷成重复行
    var remediations = as("channel.operation.read", () -> service.remediations(null, 0, 50));
    assertThat(remediations.total()).isEqualTo(2);
  }

  @Test
  @DisplayName("16.5.2.2 事实消失后重扫 ⇒ 旧提示自动收口成「已整改」")
  void removingSharedFactResolvesAlertOnRescan() {
    saveDefaultPolicy("DEVICE");
    UUID s1 = seedShop("S1", AMAZON, null);
    UUID s2 = seedShop("S2", AMAZON, null);
    var identifier = registerIdentifier("DEVICE", "DEV-01");
    linkShop(identifier.identifierId().toString(), s1);
    linkShop(identifier.identifierId().toString(), s2);
    scan();

    as(
        "channel.operation.delete",
        () -> {
          service.unlinkShop(identifier.identifierId().toString(), s2.toString());
          return null;
        });
    scan();

    var alerts = as("channel.operation.read", () -> service.alerts(null, null, 0, 50));
    assertThat(alerts.total()).isEqualTo(1);
    var alert = alerts.items().get(0);
    assertThat(alert.status()).isEqualTo("REMEDIATED");
    assertThat(alert.resolutionNote()).contains("事实已变化");
  }

  // ==================================================================
  // 7. 处置状态机 + 留痕
  // ==================================================================

  @Test
  @DisplayName("16.5.2.2 提示处置：待处置 → 已确认 → 已整改；终态不可回退；每次处置留一行")
  void alertDispositionLeavesTrace() {
    saveDefaultPolicy("DEVICE");
    UUID s1 = seedShop("S1", AMAZON, null);
    UUID s2 = seedShop("S2", AMAZON, null);
    var identifier = registerIdentifier("DEVICE", "DEV-01");
    linkShop(identifier.identifierId().toString(), s1);
    linkShop(identifier.identifierId().toString(), s2);
    scan();

    var alerts = as("channel.operation.read", () -> service.alerts(null, null, 0, 50));
    String alertId = alerts.items().get(0).alertId().toString();

    var acknowledged =
        as("channel.operation.update", () -> service.acknowledgeAlert(alertId, "已知悉", actor));
    assertThat(acknowledged.status()).isEqualTo("ACKNOWLEDGED");
    assertThat(acknowledged.acknowledgeReason()).isEqualTo("已知悉");
    // 🔴 扫描时已经留了一行 RESCAN，本次处置再留一行 ⇒ 两行
    assertThat(acknowledged.actions()).hasSize(2);
    assertThat(acknowledged.actions())
        .extracting(LinkageRiskAlertActionView::action)
        .containsExactlyInAnyOrder("RESCAN", "ACKNOWLEDGE");

    var resolved =
        as("channel.operation.update", () -> service.resolveAlert(alertId, "已分离设备", actor));
    assertThat(resolved.status()).isEqualTo("REMEDIATED");
    assertThat(resolved.actions()).hasSize(3);
    assertThat(resolved.actions())
        .extracting(LinkageRiskAlertActionView::action)
        .containsExactlyInAnyOrder("RESOLVE", "ACKNOWLEDGE", "RESCAN");

    // 🔴 终态不可回退（⚠️ 必须走 as(...)：直接调 service 会**没有租户上下文**，
    //    RLS 会把行全部过滤掉 ⇒ 报的是 404 而不是状态机拒绝）
    assertThatThrownBy(
            () ->
                as(
                    "channel.operation.update",
                    () -> service.dismissAlert(alertId, "再忽略一次", actor)))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("终态");
  }

  @Test
  @DisplayName("16.5.2.2 处置理由必填（空理由 400）")
  void dispositionReasonIsRequired() throws Exception {
    saveDefaultPolicy("DEVICE");
    UUID s1 = seedShop("S1", AMAZON, null);
    UUID s2 = seedShop("S2", AMAZON, null);
    var identifier = registerIdentifier("DEVICE", "DEV-01");
    linkShop(identifier.identifierId().toString(), s1);
    linkShop(identifier.identifierId().toString(), s2);
    scan();
    var alerts = as("channel.operation.read", () -> service.alerts(null, null, 0, 50));
    String alertId = alerts.items().get(0).alertId().toString();

    http.perform(
            post("/channel/linkage-risk/alerts/{id}/acknowledge", alertId)
                .header("Authorization", bearer)
                .contentType("application/json")
                .content("{\"reason\":\"\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("16.5.2.2 整改建议状态机：建议 → 已采纳 → 已完成；终态不可回退")
  void remediationStatusMachine() {
    saveDefaultPolicy("DEVICE");
    UUID s1 = seedShop("S1", AMAZON, null);
    UUID s2 = seedShop("S2", AMAZON, null);
    var identifier = registerIdentifier("DEVICE", "DEV-01");
    linkShop(identifier.identifierId().toString(), s1);
    linkShop(identifier.identifierId().toString(), s2);
    scan();

    var remediations = as("channel.operation.read", () -> service.remediations(null, 0, 50));
    var proposal = remediations.items().get(0);
    String id = proposal.remediationId().toString();

    var accepted =
        as(
            "channel.operation.update",
            () ->
                service.updateRemediation(
                    id, new LinkageRiskRemediationStatusInput("ACCEPTED", "接受"), actor));
    assertThat(accepted.status()).isEqualTo("ACCEPTED");
    assertThat(accepted.decisionNote()).isEqualTo("接受");

    var done =
        as(
            "channel.operation.update",
            () ->
                service.updateRemediation(
                    id, new LinkageRiskRemediationStatusInput("DONE", "已处理"), actor));
    assertThat(done.status()).isEqualTo("DONE");

    assertThatThrownBy(
            () ->
                as(
                    "channel.operation.update",
                    () ->
                        service.updateRemediation(
                            id, new LinkageRiskRemediationStatusInput("ACCEPTED", "再来一次"), actor)))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("终态");
  }

  // ==================================================================
  // 8. append-only
  // ==================================================================

  @Test
  @DisplayName("16.5.2.2 处置留痕 append-only：应用账号无权限 + 超级账号被守卫触发器拒")
  void alertActionIsAppendOnly() {
    saveDefaultPolicy("DEVICE");
    UUID s1 = seedShop("S1", AMAZON, null);
    UUID s2 = seedShop("S2", AMAZON, null);
    var identifier = registerIdentifier("DEVICE", "DEV-01");
    linkShop(identifier.identifierId().toString(), s1);
    linkShop(identifier.identifierId().toString(), s2);
    scan();
    var alerts = as("channel.operation.read", () -> service.alerts(null, null, 0, 50));
    String alertId = alerts.items().get(0).alertId().toString();
    as("channel.operation.update", () -> service.acknowledgeAlert(alertId, "已知悉", actor));

    String actionId =
        execSuperReturning("select id::text from linkage_alert_action limit 1");
    assertThat(actionId).isNotNull();

    // 🔴 超级账号（迁移账号）也改不动：守卫触发器直接 RAISE
    assertThatThrownBy(
            () ->
                execSuper(
                    "update linkage_alert_action set reason='改写痕迹' where id='"
                        + actionId
                        + "'"))
        .hasMessageContaining("留痕凭据");
    assertThatThrownBy(
            () -> execSuper("delete from linkage_alert_action where id='" + actionId + "'"))
        .hasMessageContaining("留痕凭据");

    // 🔴 应用账号根本没有 UPDATE / DELETE 权限
    assertThatThrownBy(
            () ->
                execAsApp(
                    "update linkage_alert_action set reason='改写痕迹' where id='"
                        + actionId
                        + "'"))
        .hasMessageContaining("permission denied");
  }

  /** 以应用账号 channel_app 执行一条 SQL（权限不足时必须失败）。 */
  static void execAsApp(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e.getMessage(), e);
    }
  }

  // ==================================================================
  // 9. 时间字段：断言写回的值
  // ==================================================================

  @Test
  @DisplayName("16.5.2.2 时间字段断言写回值（OffsetDateTime 往返不偏 8 小时）")
  void timestampRoundTrips() {
    saveDefaultPolicy("DEVICE");
    OffsetDateTime observedAt = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    var identifier =
        as(
            "channel.operation.create",
            () ->
                service.createIdentifier(
                    new LinkageRiskIdentifierInput(
                        "DEVICE", "DEV-TS", "DEV-TS", "时间测试", observedAt),
                    actor));

    assertThat(identifier.firstObservedAt()).isEqualTo(observedAt);
    assertThat(identifier.lastObservedAt()).isEqualTo(observedAt);

    UUID s1 = seedShop("S1", AMAZON, null);
    as(
        "channel.operation.create",
        () ->
            service.linkShop(
                identifier.identifierId().toString(),
                new LinkageRiskShopLinkInput(s1, "MANUAL", "时间测试", observedAt),
                actor));
    var reread = as("channel.operation.read", () -> service.identifiers(null, null, 0, 50));
    assertThat(reread.items().get(0).firstObservedAt()).isEqualTo(observedAt);
  }

  // ==================================================================
  // 10. HTTP 层：读不到 / 未交付的形状
  // ==================================================================

  @Test
  @DisplayName("16.5.2.2 HTTP：能力声明与图谱端点可读；未交付的形状一律不存在")
  void httpEndpointsExposeOnlyRealShapes() throws Exception {
    saveDefaultPolicy("DEVICE");
    seedShop("S1", AMAZON, null);

    http.perform(get("/channel/linkage-risk/capabilities").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.capabilities").isArray());

    http.perform(get("/channel/linkage-risk/graph").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.known").value(true));

    http.perform(get("/channel/linkage-risk/shops").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));

    // 🔴 本模块没有「资金账户登记」入口（归 12.7.1.1，未交付）
    http.perform(
            post("/channel/linkage-risk/identifiers")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content("{\"dimension\":\"PAYOUT_ACCOUNT\",\"identifierKey\":\"x\"}"))
        .andExpect(status().isBadRequest());

    // 🔴 法人维度也不接受登记（真相在 shop.legal_entity_id）
    http.perform(
            post("/channel/linkage-risk/identifiers")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content("{\"dimension\":\"LEGAL_ENTITY\",\"identifierKey\":\"x\"}"))
        .andExpect(status().isBadRequest());

    // 🔴 没有会话一律 401
    http.perform(get("/channel/linkage-risk/graph")).andExpect(status().isUnauthorized());
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
    if (identity != null) identity.close();
    pg.stop();
  }
}
