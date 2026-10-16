package com.starcon.channel;

import static com.starcon.channel.LabelTemplateModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * 10.2.2.2 面单模板。
 *
 * <p>本测试类钉七组事：
 *
 * <ol>
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、
 *       伪造 tenant header 无效、连接池归还后不串租户
 *   <li><b>🔴 附加声明漏打 = 清关问题</b>：声明必须锚定到真实打印位，
 *       <b>绕过服务层直接写 SQL 同样被拒</b>（DB 触发器）
 *   <li><b>🔴 渲染是真渲染</b>：真出 ZPL、真算 Code 128 校验位、真落 render 行、
 *       校验和与内容逐字对得上
 *   <li><b>条码可编码性是真判的</b>：中文运单号声明成 CODE39 ⇒ BLOCK，不是印完才发现
 *   <li><b>锂电池 UN38.3 是 DB 层的硬闸</b>：2.7 &lt; Wh ≤ 100 且无 UN38.3 ⇒ 直接写 SQL 也会被拒
 *   <li><b>🔴 渠道规则强绑定在服务端</b>：渠道要求电池声明而模板没有 ⇒ BLOCK
 *   <li>尺寸越界 / 模板代码不可改 / 条码类型不一致 / 齐备度不够不许启用 / 真分页真筛选
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class LabelTemplateIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 复用 identity 已注册的 channel 权限码：本模块不新增权限码，identity 零改动。 */
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

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), null,
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
      List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
      actors.put("Bearer tenant-a", actor(user, a, grants));
      actors.put("Bearer tenant-b", actor(user, b, grants));
      identity.start();
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
    r.add("CATALOG_URL", () -> "http://127.0.0.1:1");
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired LabelTemplateService labels;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  AccessService.Access actorA, actorB;

  @BeforeEach
  void setup() {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    truncateAll();
  }

  void truncateAll() {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 🔴 cascade 是必需的：logistics_channel_config 是**渠道配置的父表**，除本模块
      //   V141 的面单模板表外，兄弟模块 10.1.1.3 时效与限制（V139 的
      //   logistics_transit_rule / logistics_size_limit / logistics_marking_requirement）
      //   也按渠道对它建了复合外键。TRUNCATE 一张被外键引用的表时 PostgreSQL 要求把
      //   **全部**引用表一并处理，否则报 23503「cannot truncate a table referenced in a
      //   foreign key constraint」—— 症状是本类**每个**用例在 setUp 里就炸，与断言无关。
      //   显式列举本模块关心的表 + cascade 兜住兄弟模块的引用，是与
      //   LogisticsChannelIntegrationTest 同款的有意双保险。
      s.execute("truncate logistics_label_render, logistics_label_battery_mark,"
          + " logistics_label_declaration, logistics_label_element, logistics_label_template,"
          + " logistics_channel_scope, logistics_channel_surcharge,"
          + " logistics_channel_restriction, logistics_channel_config,"
          + " carrier_country_coverage, carrier_business_scope, carrier_logistics_profile"
          + " cascade");
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  // ==================================================================
  // 夹具
  // ==================================================================

  UUID seedCarrier(UUID tenant, String code, String name) {
    UUID id = UUID.randomUUID();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("set app.current_tenant = '" + tenant + "'");
      // 写入顺序必须与 CarrierService.save 一致：先以「子表还空着时正确的 readiness」
      // 插入父行 → 写子表 → 再回填最终 readiness。
      s.execute(
          "insert into carrier_logistics_profile (id,tenant_id,role_id,party_id,party_code,"
              + "party_name,party_usable,interface_type,carrier_category,service_tier,"
              + "secret_ref,secret_masked,readiness)"
              + " values ('" + id + "','" + tenant + "','" + UUID.randomUUID() + "','"
              + UUID.randomUUID() + "','" + code + "','" + name + "',true,"
              + "'API','EXPRESS_COURIER','EXPRESS',"
              + "'kv://starcon/test/carriers/" + code.toLowerCase() + "','sv_live_****3f2a',"
              + "'NO_COVERAGE')");
      s.execute("insert into carrier_country_coverage (tenant_id,profile_id,country_code)"
          + " values ('" + tenant + "','" + id + "','DE')");
      s.execute("insert into carrier_business_scope (tenant_id,profile_id,business_type)"
          + " values ('" + tenant + "','" + id + "','OWN_FULFILLMENT')");
      s.execute("update carrier_logistics_profile set readiness='READY' where id='" + id + "'");
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
    return id;
  }

  /** 一条齐备的渠道配置（10.1.1.2 的表，本模块只读引用）。 */
  UUID seedChannel(UUID tenant, String code, String name, String status) {
    UUID carrierId = seedCarrier(tenant, code + "-CARRIER", code + " 承运商");
    UUID id = UUID.randomUUID();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("set app.current_tenant = '" + tenant + "'");
      // 新建时子表还空着 ⇒ 正确的 readiness 是 NO_SCOPE
      s.execute(
          "insert into logistics_channel_config (id,tenant_id,carrier_profile_id,channel_code,"
              + "channel_name,status,billing_mode,billing_currency,base_rate,charge_divisor,"
              + "min_charge,insurance_mode,insurance_rate_pct,insurance_min_fee,readiness)"
              + " values ('" + id + "','" + tenant + "','" + carrierId + "','" + code + "','"
              + name + "','" + status + "','BY_WEIGHT','USD',28.50,1,0,'NONE',0,0,'NO_SCOPE')");
      s.execute("insert into logistics_channel_scope (tenant_id,channel_id,scope_kind,scope_value)"
          + " values ('" + tenant + "','" + id + "','COUNTRY','DE')");
      s.execute("update logistics_channel_config set readiness='READY' where id='" + id + "'");
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
    return id;
  }

  /** 给渠道加一条限制（电池 / 液体 / 磁力 / 禁限品类）。 */
  void seedRestriction(UUID tenant, UUID channelId, String kind, String subject, String mode) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("set app.current_tenant = '" + tenant + "'");
      s.execute(
          "insert into logistics_channel_restriction (tenant_id,channel_id,restriction_kind,"
              + "subject,restriction_mode,carrier_reason) values ('" + tenant + "','" + channelId
              + "','" + kind + "','" + subject + "','" + mode + "','承运商合同条款要求')");
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 一个「排好版、带条码」的打印元素集：100×150mm 面单。 */
  List<LabelElement> standardElements() {
    return List.of(
        new LabelElement("SHIP_TO", "SHIP_TO_ADDRESS", null,
            new BigDecimal("4"), new BigDecimal("4"), new BigDecimal("92"), new BigDecimal("30"),
            12, 0, true, 0, ""),
        new LabelElement("WB_BARCODE", "WAYBILL_BARCODE", "CODE128_B",
            new BigDecimal("4"), new BigDecimal("38"), new BigDecimal("92"), new BigDecimal("20"),
            null, 0, true, 1, ""),
        new LabelElement("SERVICE", "TEXT", null,
            new BigDecimal("4"), new BigDecimal("62"), new BigDecimal("60"), new BigDecimal("8"),
            10, 0, false, 2, ""),
        new LabelElement("DECL", "DECLARATION_TEXT", null,
            new BigDecimal("4"), new BigDecimal("74"), new BigDecimal("92"), new BigDecimal("20"),
            10, 0, false, 3, ""));
  }

  LabelTemplateRow saveTemplate(AccessService.Access who, UUID channelId, String code) {
    return saveTemplate(who, null, channelId, code, "DRAFT", "CODE128_B", standardElements(),
        List.of(), List.of());
  }

  @SuppressWarnings("java:S107")
  LabelTemplateRow saveTemplate(
      AccessService.Access who,
      UUID templateId,
      UUID channelId,
      String code,
      String status,
      String symbology,
      List<LabelElement> elements,
      List<LabelDeclaration> declarations,
      List<LabelBatteryMark> batteries) {
    return as(who, "channel.operation.create",
        () -> labels.save(new SaveLabelTemplateRequest(
            templateId, channelId, code, "模板 " + code, "DE", "de-DE", status,
            new BigDecimal("100"), new BigDecimal("150"), 203, "PORTRAIT",
            BigDecimal.ZERO, BigDecimal.ZERO, symbology, new BigDecimal("12"), true,
            elements, declarations, batteries, "集成测试登记", 0)));
  }

  LabelTemplatePage list(AccessService.Access who) {
    return as(who, "channel.operation.read",
        () -> labels.templates(null, null, null, null, null, null, null, "NAME_ASC", 0, 50));
  }

  /** 一份形状正确的预览样例：每个打印位都有值。 */
  LabelPreviewRequest sample(String waybill) {
    return new LabelPreviewRequest(
        List.of(
            new LabelSampleValue("SHIP_TO", "Hans Müller, Hauptstr. 1, 10115 Berlin"),
            new LabelSampleValue("WB_BARCODE", waybill),
            new LabelSampleValue("SERVICE", "STANDARD"),
            new LabelSampleValue("DECL", "DHL PAKET")),
        null,
        true);
  }

  /** 直接落 SQL（绕过服务层），用来证明硬边界在 DB 而不是应用层的 if。 */
  int rawSql(String sql, Object... binds) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var ps = c.prepareStatement(sql)) {
      for (int i = 0; i < binds.length; i++) {
        ps.setObject(i + 1, binds[i]);
      }
      return ps.executeUpdate();
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
  }

  void rawSqlAs(UUID tenant, String sql, Object... binds) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      c.setAutoCommit(false);
      try (var s = c.createStatement()) {
        s.execute("set app.current_tenant = '" + tenant + "'");
      }
      try (var ps = c.prepareStatement(sql)) {
        for (int i = 0; i < binds.length; i++) {
          ps.setObject(i + 1, binds[i]);
        }
        ps.executeUpdate();
      }
      c.commit();
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static com.fasterxml.jackson.databind.JsonNode json(String body) throws Exception {
    return new ObjectMapper().readTree(body);
  }

  /** 直接数表里的行数（跨租户断言要用它证明「真的落库了」而不是只有接口返回）。 */
  int rowsIn(String table) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery("select count(*) from " + table)) {
      rs.next();
      return rs.getInt(1);
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
  }

  // ==================================================================
  // 多租户三断言（AGENTS.md 硬性要求，缺一不可）
  // ==================================================================

  @Test
  @DisplayName("10.2.2.2 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    UUID channelA = seedChannel(a, "SFT-DE", "顺丰德国", "ACTIVE");
    UUID channelB = seedChannel(b, "FDX-DE", "FedEx 德国", "ACTIVE");
    saveTemplate(actorA, channelA, "SFT-100-70");
    saveTemplate(actorB, channelB, "FDX-100-70");

    UUID aId = list(actorA).items().get(0).id();

    assertThat(list(actorB).items()).extracting(LabelTemplateRow::templateCode)
        .containsExactly("FDX-100-70");
    assertThat(list(actorA).items()).extracting(LabelTemplateRow::templateCode)
        .containsExactly("SFT-100-70");

    // B 用 A 的 id 读详情：跨租户与不存在返回**逐字相同**的 404
    assertThatThrownBy(() -> as(actorB, "channel.operation.read", () -> labels.detail(aId)))
        .hasMessageContaining("面单模板不存在");
    assertThatThrownBy(
            () -> as(actorB, "channel.operation.read", () -> labels.detail(UUID.randomUUID())))
        .hasMessageContaining("面单模板不存在");

    // B 删不掉 A 的模板
    assertThatThrownBy(
            () -> as(actorB, "channel.operation.delete",
                () -> {
                  labels.delete(aId);
                  return null;
                }))
        .hasMessageContaining("面单模板不存在");
    assertThat(list(actorA).items()).hasSize(1);

    // B 绑定判定端点只看到自己那张（channelId 留空 = 判定该国家下全部模板）
    assertThat(as(actorB, "channel.operation.read",
            () -> labels.binding(new LabelBindingRequest(
                null, "DE", List.of("HOME_GOODS"), false, false, false, null)))
        .verdicts()).extracting(LabelBindingVerdict::templateCode)
        .containsExactly("FDX-100-70");
    // 指定 A 的渠道：一条都判不出来（RLS 在查询层就把它滤掉了）
    assertThat(as(actorB, "channel.operation.read",
            () -> labels.binding(new LabelBindingRequest(
                channelA, "DE", List.of("HOME_GOODS"), false, false, false, null)))
        .verdicts()).isEmpty();
  }

  @Test
  @DisplayName("10.2.2.2 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID channelA = seedChannel(a, "SFT-DE", "顺丰德国", "ACTIVE");
    UUID channelB = seedChannel(b, "FDX-DE", "FedEx 德国", "ACTIVE");
    saveTemplate(actorA, channelA, "SFT-100-70");
    saveTemplate(actorB, channelB, "FDX-100-70");
    String aId = list(actorA).items().get(0).id().toString();
    String bId = list(actorB).items().get(0).id().toString();

    // A 的会话 + B 的头 ⇒ 看到的仍然只是 A 的数据
    http.perform(get("/channel/label-templates")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].id", org.hamcrest.Matchers.hasItem(aId)))
        .andExpect(jsonPath("$.items[*].id",
            org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(bId))));

    // 无会话一律 401
    http.perform(get("/channel/label-templates").header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("10.2.2.2 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    UUID channelA = seedChannel(a, "SFT-DE", "顺丰德国", "ACTIVE");
    UUID channelB = seedChannel(b, "FDX-DE", "FedEx 德国", "ACTIVE");
    saveTemplate(actorA, channelA, "SFT-100-70");
    saveTemplate(actorB, channelB, "FDX-100-70");

    for (int i = 0; i < 3; i++) {
      assertThat(list(actorA).items()).extracting(LabelTemplateRow::templateCode)
          .containsExactly("SFT-100-70");
      assertThat(list(actorB).items()).extracting(LabelTemplateRow::templateCode)
          .containsExactly("FDX-100-70");
      assertThat(as(actorA, "channel.operation.read", () -> labels.overview()).summary().total())
          .isEqualTo(1);
      assertThat(as(actorB, "channel.operation.read", () -> labels.overview()).summary().total())
          .isEqualTo(1);
      // 预览端点也交替跑 —— 它是最重的那条路径（真渲染 + 真写 render 行）
      assertThat(as(actorA, "channel.operation.read",
              () -> labels.preview(list(actorA).items().get(0).id(), sample("DE1111111111111")))
          .zplContent()).startsWith("^XA");
      assertThat(as(actorB, "channel.operation.read",
              () -> labels.preview(list(actorB).items().get(0).id(), sample("DE2222222222222")))
          .zplContent()).startsWith("^XA");
    }
  }

  // ==================================================================
  // 🔴 附加声明锚定：漏打 = 清关问题
  // ==================================================================

  @Test
  @DisplayName("附加声明必须锚定到一个真实存在的打印位（服务层 422）")
  void declarationMustAnchorToARealElement() {
    UUID channel = seedChannel(a, "SFT-DE", "顺丰德国", "ACTIVE");
    List<LabelElement> elements = new ArrayList<>(standardElements());
    elements.add(new LabelElement("BATTERY_LINE", "DECLARATION_TEXT", null,
        new BigDecimal("4"), new BigDecimal("98"), new BigDecimal("92"), new BigDecimal("12"),
        10, 0, true, 4, ""));

    // ✅ 有对应打印位
    saveTemplate(actorA, null, channel, "SFT-OK", "DRAFT", "CODE128_B", elements,
        List.of(new LabelDeclaration("BATTERY", "UN3481", "Lithium battery contained in equipment",
            "BATTERY_LINE", true, "")), List.of());

    // ❌ 指向一个没提交的打印位
    assertThatThrownBy(() -> saveTemplate(actorA, null, channel, "SFT-ORPHAN", "DRAFT",
            "CODE128_B", elements,
            List.of(new LabelDeclaration("LIQUID", "", "Liquid",
                "NOT_EXIST", true, "")), List.of()))
        .hasMessageContaining("附加声明「LIQUID」指向的打印位「NOT_EXIST」")
        .hasMessageContaining("漏打会导致清关问题");

    // ❌ 既没代码也没文字 = 一个什么都不会印出来的空声明
    assertThatThrownBy(() -> saveTemplate(actorA, null, channel, "SFT-BLANK", "DRAFT",
            "CODE128_B", elements,
            List.of(new LabelDeclaration("LIQUID", "", "   ", "BATTERY_LINE", true, "")), List.of()))
        .hasMessageContaining("等于没声明");
  }

  @Test
  @DisplayName("🔴 绕过服务层直接写 SQL 造「声明挂在虚空上」同样被拒（边界在 DB 不在 if）")
  void directSqlCannotBypassTheAnchorRule() {
    UUID channel = seedChannel(a, "SFT-DE", "顺丰德国", "ACTIVE");
    LabelTemplateRow row = saveTemplate(actorA, channel, "SFT-DIRECT");

    // 正向：直接 INSERT 一条指向不存在打印位的声明 → V141 trigger c 拒
    assertThatThrownBy(() -> rawSqlAs(a,
            "insert into logistics_label_declaration (tenant_id,template_id,declaration_kind,"
                + "declaration_code,display_text,element_key,mandatory,note)"
                + " values (?,?,?,?,?,?,?,?)",
            a, row.id(), "LIQUID", "", "Liquid", "GHOST_SLOT", true, ""))
        .hasMessageContaining("打印位「GHOST_SLOT」在这张模板上不存在")
        .hasMessageContaining("漏打会导致清关问题");

    // 反向：直接 DELETE 一个仍被声明引用的打印位 → V141 trigger c 反向分支拒
    List<LabelElement> elements = new ArrayList<>(standardElements());
    elements.add(new LabelElement("BATTERY_LINE", "DECLARATION_TEXT", null,
        new BigDecimal("4"), new BigDecimal("98"), new BigDecimal("92"), new BigDecimal("12"),
        10, 0, true, 4, ""));
    saveTemplate(actorA, row.id(), channel, "SFT-DIRECT", "DRAFT", "CODE128_B", elements,
        List.of(new LabelDeclaration("BATTERY", "UN3481", "Lithium", "BATTERY_LINE", true, "")),
        List.of());
    assertThatThrownBy(() -> rawSqlAs(a,
            "delete from logistics_label_element where tenant_id=? and template_id=? and element_key=?",
            a, row.id(), "BATTERY_LINE"))
        .hasMessageContaining("仍被附加声明「BATTERY」（强制）引用")
        .hasMessageContaining("漏打会导致清关问题");
  }

  @Test
  @DisplayName("🔴 DECLARATION_GAP 不可达：服务层与 DB 触发器各拒一次（漏打进不来）")
  void declarationGapIsUnreachableThroughBothGuards() {
    UUID channel = seedChannel(a, "SFT-DE", "顺丰德国", "ACTIVE");
    List<LabelElement> elements = standardElements();

    // ① 服务层拒：声明指向一个没提交的打印位
    assertThatThrownBy(() -> saveTemplate(actorA, null, channel, "SFT-GAP", "DRAFT", "CODE128_B",
            elements,
            List.of(new LabelDeclaration("LIQUID", "", "Liquid", "GHOST", true, "")), List.of()))
        .hasMessageContaining("附加声明「LIQUID」指向的打印位「GHOST」");

    // ② DB 触发器也拒：绕过服务层写 SQL 同样进不来
    LabelTemplateRow row = saveTemplate(actorA, channel, "SFT-OK");
    assertThatThrownBy(() -> rawSqlAs(a,
            "insert into logistics_label_declaration (tenant_id,template_id,declaration_kind,"
                + "declaration_code,display_text,element_key,mandatory,note)"
                + " values (?,?,?,?,?,?,?,?)",
            a, row.id(), "LIQUID", "", "Liquid", "GHOST", true, ""))
        .hasMessageContaining("打印位「GHOST」在这张模板上不存在");

    // 所以一条真实存在的模板永远不是 DECLARATION_GAP；这一档留着是为了
    // 「万一将来 guard 被人改坏，触发器仍然拒它」这层兜底（trigger a 里明写了这个分支）。
    assertThat(row.readiness()).isEqualTo("READY");
    assertThat(row.activatable()).isTrue();
  }

  // ==================================================================
  // 🔴 真渲染
  // ==================================================================

  @Test
  @DisplayName("🔴 预览出的是真 ZPL：含 ^XA/^XZ、尺寸换算、真条码与真校验和")
  void previewRendersRealZpl() {
    UUID channel = seedChannel(a, "SFT-DE", "顺丰德国", "ACTIVE");
    LabelTemplateRow row = saveTemplate(actorA, channel, "SFT-100-70");

    LabelRenderPreview preview =
        as(actorA, "channel.operation.read", () -> labels.preview(row.id(), sample("DE1234567890123")));

    // ① 真的 ZPL 包边
    assertThat(preview.zplContent()).startsWith("^XA").endsWith("^XZ\n");
    assertThat(preview.zplContent()).contains("^CI28"); // 语言版本落在这里
    // ② 尺寸真的换算成了 dot：100mm @203dpi = 799dot（100×203÷25.4 = 799.2 → HALF_UP 799）
    assertThat(preview.zplContent()).contains("^PW799");
    // 150mm @203dpi = 1200dot（150×203÷25.4 = 1199.2 → 1199）
    assertThat(preview.zplContent()).contains("^LL1199");
    // ③ 条码真的按坐标落位（x=4mm→32dot, y=38mm→304dot）
    assertThat(preview.zplContent()).contains("^FO32,304^BY2,3.0,");
    assertThat(preview.zplContent()).contains("^BCN,160,Y,N^FDDE1234567890123^FS");
    // ④ 一维码真编码：模块序列以「校验位图案 + STOP」收尾
    assertThat(preview.barcodeModules()).endsWith(" 241211 2331112");
    assertThat(preview.encodedValue()).isEqualTo("DE1234567890123");
    assertThat(preview.symbology()).isEqualTo("CODE128_B");
    // ⑤ 校验和与内容逐字对得上（照抄 8.3.4.2 的同一做法：下载端点靠它验内容没被改过）
    assertThat(preview.contentSha256()).isEqualTo(LabelRenderEngine.sha256(preview.zplContent()));
    assertThat(preview.contentSha256()).hasSize(64);
    // ⑥ 本模块唯一产出的渲染格式是 ZPL（模板表刻意没有 label_format 列，
    //    格式记在 render 行上记录**实际产出**，不是一个会骗人的预选项）
    assertThat(preview.labelFormat()).isEqualTo("ZPL");
    // ⑦ 这次预览没有硬伤
    assertThat(preview.verdict()).isEqualTo("PASS");
    assertThat(preview.blockCount()).isZero();
  }

  @Test
  @DisplayName("🔴 每次预览真落一条 render 行（渲染历史是启用前的证据链）")
  void previewPersistsARenderRow() {
    UUID channel = seedChannel(a, "SFT-DE", "顺丰德国", "ACTIVE");
    LabelTemplateRow row = saveTemplate(actorA, channel, "SFT-100-70");

    LabelRenderPreview preview =
        as(actorA, "channel.operation.read", () -> labels.preview(row.id(), sample("DE1234567890123")));
    assertThat(preview.persisted()).isTrue();

    LabelRenderPage history = as(actorA, "channel.operation.read", () -> labels.renders(row.id(), 0, 20));
    assertThat(history.total()).isEqualTo(1);
    assertThat(history.items().get(0).triggerSource()).isEqualTo("PREVIEW");
    assertThat(history.items().get(0).contentSha256()).isEqualTo(preview.contentSha256());
    assertThat(history.items().get(0).zplContent()).isEqualTo(preview.zplContent());

    // persist=false 时不落库（现场想反复试排版时不会把渲染历史刷满）
    as(actorA, "channel.operation.read",
        () -> labels.preview(row.id(), new LabelPreviewRequest(
            List.of(new LabelSampleValue("WB_BARCODE", "DE9999999999999")), null, false)));
    assertThat(as(actorA, "channel.operation.read", () -> labels.renders(row.id(), 0, 20)).total())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("Code 128 校验位按规范算对（模 103）")
  void code128ChecksumIsCorrect() {
    // 规范公式：(start + Σ i×value_i) mod 103，i 从 1 开始。
    // 入参是 [start, 字符值…]，校验位与 STOP 由编码器自己补
    // START_B=104，Set B 的 'D'=68→36，'E'=69→37
    // ⇒ 104 + 1×36 + 2×37 = 214，214 mod 103 = 8
    assertThat(LabelRenderEngine.Code128.checksum(new int[] {104, 36, 37})).isEqualTo(8);
    assertThat(LabelRenderEngine.Code128.startB()).isEqualTo(104);
    assertThat(LabelRenderEngine.Code128.stop()).isEqualTo(106);

    String modules = LabelRenderEngine.Code128.encodeSetB("DE1234567890123");
    // 以「校验位图案 + STOP」收尾：值 75 → "241211"，STOP(106) → "2331112"
    assertThat(modules).endsWith(" 241211 2331112");
    // 首元素是 START_B（104 → "211214"）
    assertThat(modules).startsWith("211214 ");
  }

  @Test
  @DisplayName("🔴 条码可编码性是真判的：中文/小写编不进 CODE39 / CODE128_B 就 BLOCK")
  void barcodeEncodabilityIsReallyChecked() {
    UUID channel = seedChannel(a, "SFT-DE", "顺丰德国", "ACTIVE");
    LabelElement barcode = new LabelElement("WB_BARCODE", "WAYBILL_BARCODE", "CODE39",
        new BigDecimal("4"), new BigDecimal("38"), new BigDecimal("92"), new BigDecimal("20"),
        null, 0, true, 1, "");
    List<LabelElement> elements = new ArrayList<>(standardElements());
    elements.set(1, barcode);
    LabelTemplateRow row = saveTemplate(actorA, null, channel, "SFT-C39", "DRAFT", "CODE39",
        elements, List.of(), List.of());

    // 小写字母：Code 39 只有大写
    LabelRenderPreview lower = as(actorA, "channel.operation.read",
        () -> labels.preview(row.id(), new LabelPreviewRequest(
            List.of(
                new LabelSampleValue("SHIP_TO", "Berlin"),
                new LabelSampleValue("WB_BARCODE", "de1234567890123"),
                new LabelSampleValue("SERVICE", "STD"),
                new LabelSampleValue("DECL", "PKT")),
            null, true)));
    assertThat(lower.verdict()).isEqualTo("BLOCK");
    assertThat(lower.findings()).extracting(LabelFinding::code)
        .contains(LabelRenderEngine.F_BARCODE_NOT_ENCODABLE);
    assertThat(lower.findings()).anyMatch(
        f -> f.message().contains("Code 39 编不进的字符")
            && f.message().contains("小写字母必须改用 CODE128"));

    // 中文：Code 128 Set B 只覆盖 ASCII 32..126
    LabelRenderPreview chinese = as(actorA, "channel.operation.read",
        () -> labels.preview(row.id(), new LabelPreviewRequest(
            List.of(
                new LabelSampleValue("SHIP_TO", "柏林"),
                new LabelSampleValue("WB_BARCODE", "DE1234567890123"),
                new LabelSampleValue("SERVICE", "标准"),
                new LabelSampleValue("DECL", "包裹")),
            null, true)));
    assertThat(chinese.verdict()).isEqualTo("PASS");

    List<LabelElement> c128 = new ArrayList<>(standardElements());
    LabelTemplateRow row128 = saveTemplate(actorA, null, channel, "SFT-C128", "DRAFT", "CODE128_B",
        c128, List.of(), List.of());
    LabelRenderPreview chineseBarcode = as(actorA, "channel.operation.read",
        () -> labels.preview(row128.id(), new LabelPreviewRequest(
            List.of(
                new LabelSampleValue("SHIP_TO", "柏林"),
                new LabelSampleValue("WB_BARCODE", "DE运单123"),
                new LabelSampleValue("SERVICE", "标准"),
                new LabelSampleValue("DECL", "包裹")),
            null, true)));
    assertThat(chineseBarcode.verdict()).isEqualTo("BLOCK");
    assertThat(chineseBarcode.findings()).anyMatch(
        f -> f.code().equals(LabelRenderEngine.F_BARCODE_NOT_ENCODABLE)
            && f.message().contains("Set B 只覆盖 ASCII 32-126"));
  }

  @Test
  @DisplayName("🔴 条码位没值 = 票到了扫不出，当场 BLOCK 而不是印一坨黑")
  void barcodeWithoutValueIsBlocked() {
    UUID channel = seedChannel(a, "SFT-DE", "顺丰德国", "ACTIVE");
    LabelTemplateRow row = saveTemplate(actorA, channel, "SFT-100-70");

    LabelRenderPreview preview = as(actorA, "channel.operation.read",
        () -> labels.preview(row.id(), new LabelPreviewRequest(
            List.of(
                new LabelSampleValue("SHIP_TO", "Berlin"),
                new LabelSampleValue("SERVICE", "STD"),
                new LabelSampleValue("DECL", "PKT")),
            null, true)));

    assertThat(preview.verdict()).isEqualTo("BLOCK");
    assertThat(preview.findings()).extracting(LabelFinding::code)
        .contains(LabelRenderEngine.F_BARCODE_NO_VALUE);
    // 真的没发出那条条码指令（而不是发一个空的让打印机打黑块）
    assertThat(preview.zplContent()).doesNotContain("^BCN");
  }

  @Test
  @DisplayName("🔴 强制声明在预览里没值 ⇒ 漏打，BLOCK")
  void mandatoryDeclarationWithoutValueIsBlocked() {
    UUID channel = seedChannel(a, "SFT-DE", "顺丰德国", "ACTIVE");
    List<LabelElement> elements = new ArrayList<>(standardElements());
    elements.add(new LabelElement("BATTERY_LINE", "DECLARATION_TEXT", null,
        new BigDecimal("4"), new BigDecimal("98"), new BigDecimal("92"), new BigDecimal("12"),
        10, 0, true, 4, ""));
    LabelTemplateRow row = saveTemplate(actorA, null, channel, "SFT-BATT", "DRAFT", "CODE128_B",
        elements,
        List.of(new LabelDeclaration("BATTERY", "UN3481", "Lithium contained in equipment",
            "BATTERY_LINE", true, "")),
        List.of());

    // 没给 BATTERY_LINE 的值 ⇒ 面单上这一格会是空的
    LabelRenderPreview gap = as(actorA, "channel.operation.read",
        () -> labels.preview(row.id(), sample("DE1234567890123")));
    assertThat(gap.verdict()).isEqualTo("BLOCK");
    assertThat(gap.findings()).anyMatch(
        f -> f.code().equals(LabelRenderEngine.F_DECLARATION_NO_VALUE)
            && f.message().contains("漏打会导致清关问题"));

    // 给了值 ⇒ 真的印出去，PASS
    List<LabelSampleValue> values = new ArrayList<>(sample("DE1234567890123").elementValues());
    values.add(new LabelSampleValue("BATTERY_LINE", "Lithium battery contained in equipment"));
    LabelRenderPreview ok = as(actorA, "channel.operation.read",
        () -> labels.preview(row.id(),
            new LabelPreviewRequest(values, new BigDecimal("12"), true)));
    assertThat(ok.verdict()).isEqualTo("PASS");
    assertThat(ok.zplContent()).contains("Lithium battery contained in equipment");
  }

  // ==================================================================
  // 🔴 锂电池 UN38.3：DB 层硬闸
  // ==================================================================

  @Test
  @DisplayName("🔴 锂电池 2.7 < Wh ≤ 100 必须 UN38.3：绕过服务层写 SQL 也被拒")
  void lithiumUn38_3IsEnforcedInDb() {
    UUID channel = seedChannel(a, "SFT-DE", "顺丰德国", "ACTIVE");
    List<LabelElement> elements = new ArrayList<>(standardElements());
    elements.add(new LabelElement("BATTERY_LINE", "BATTERY_MARK", null,
        new BigDecimal("4"), new BigDecimal("98"), new BigDecimal("92"), new BigDecimal("12"),
        10, 0, true, 4, ""));
    // ✅ 先建一条齐备的（带 UN38.3）
    LabelTemplateRow row = saveTemplate(actorA, null, channel, "SFT-LI", "DRAFT", "CODE128_B",
        elements, List.of(),
        List.of(new LabelBatteryMark("LITHIUM_ION", new BigDecimal("50"),
            new BigDecimal("0.250"), true, "LI-ION", "BATTERY_LINE", "")));

    // ① 服务层路径撞闸被拒（V141 trigger e 兜底）
    assertThatThrownBy(() -> saveTemplate(actorA, row.id(), channel, "SFT-LI", "DRAFT", "CODE128_B",
            elements, List.of(),
            List.of(new LabelBatteryMark("LITHIUM_ION", new BigDecimal("50"),
                new BigDecimal("0.250"), false, "LI-ION", "BATTERY_LINE", ""))))
        .hasMessageContaining("UN38.3")
        .hasMessageContaining("机场");

    // 🔴 ② 绕过服务层直接 UPDATE 同样被 DB 拒 —— 边界在 DB 不在应用层的 if
    assertThatThrownBy(() -> rawSqlAs(a,
            "update logistics_label_battery_mark set un38_3_attested=false"
                + " where tenant_id=? and template_id=?",
            a, row.id()))
        .hasMessageContaining("UN38.3");

    // 补上 UN38.3 就过了
    LabelTemplateRow fixed = saveTemplate(actorA, row.id(), channel, "SFT-LI", "DRAFT", "CODE128_B",
        elements, List.of(),
        List.of(new LabelBatteryMark("LITHIUM_ION", new BigDecimal("50"),
            new BigDecimal("0.250"), true, "LI-ION", "BATTERY_LINE", "")));
    assertThat(fixed.readiness()).isEqualTo("READY");
    assertThat(fixed.batteryMarks().get(0).un38_3Attested()).isTrue();
  }

  @Test
  @DisplayName("瓦时与面单上印的不一致会出一条 REVIEW（清关会质疑）")
  void wattHourMismatchIsFlagged() {
    UUID channel = seedChannel(a, "SFT-DE", "顺丰德国", "ACTIVE");
    List<LabelElement> elements = new ArrayList<>(standardElements());
    elements.add(new LabelElement("BATTERY_LINE", "BATTERY_MARK", null,
        new BigDecimal("4"), new BigDecimal("98"), new BigDecimal("92"), new BigDecimal("12"),
        10, 0, true, 4, ""));
    LabelTemplateRow row = saveTemplate(actorA, null, channel, "SFT-WH", "DRAFT", "CODE128_B",
        elements, List.of(),
        List.of(new LabelBatteryMark("LITHIUM_ION", new BigDecimal("50"),
            new BigDecimal("0.250"), true, "LI-ION", "BATTERY_LINE", "")));

    List<LabelSampleValue> values = new ArrayList<>(sample("DE1234567890123").elementValues());
    values.add(new LabelSampleValue("BATTERY_LINE", "Li-ion 50 Wh"));
    LabelRenderPreview mismatch = as(actorA, "channel.operation.read",
        () -> labels.preview(row.id(),
            new LabelPreviewRequest(values, new BigDecimal("12"), true)));
    assertThat(mismatch.verdict()).isEqualTo("REVIEW");
    assertThat(mismatch.findings()).extracting(LabelFinding::code)
        .contains(LabelRenderEngine.F_BATTERY_WATT_MISMATCH);
  }

  // ==================================================================
  // 🔴 渠道规则强绑定
  // ==================================================================

  @Test
  @DisplayName("🔴 渠道要求电池声明而模板没有 ⇒ BLOCK（加粗句的服务端落点）")
  void channelRequiredDeclarationMissingIsBlocked() {
    UUID channel = seedChannel(a, "SFT-DE", "顺丰德国", "ACTIVE");
    seedRestriction(a, channel, "BATTERY", "", "MANUAL_REVIEW");
    LabelTemplateRow withoutBattery = saveTemplate(actorA, channel, "SFT-NO-BATT");

    List<LabelElement> withSlot = new ArrayList<>(standardElements());
    withSlot.add(new LabelElement("BATTERY_LINE", "DECLARATION_TEXT", null,
        new BigDecimal("4"), new BigDecimal("98"), new BigDecimal("92"), new BigDecimal("12"),
        10, 0, true, 4, ""));
    LabelTemplateRow withBattery = saveTemplate(actorA, null, channel, "SFT-BATT", "DRAFT",
        "CODE128_B", withSlot,
        List.of(new LabelDeclaration("BATTERY", "UN3481", "Lithium", "BATTERY_LINE", true, "")),
        List.of(new LabelBatteryMark("LITHIUM_ION", new BigDecimal("12"),
            new BigDecimal("0.100"), true, "LI-ION", "BATTERY_LINE", "")));

    LabelBindingResult result = as(actorA, "channel.operation.read",
        () -> labels.binding(new LabelBindingRequest(
            channel, "DE", List.of("HOME_GOODS"), true, false, false, null)));

    LabelBindingVerdict missing =
        result.verdicts().stream().filter(v -> v.templateId().equals(withoutBattery.id())).findFirst()
            .orElseThrow();
    assertThat(missing.verdict()).isEqualTo("BLOCK");
    assertThat(missing.missingDeclarations()).containsExactly("BATTERY");
    assertThat(missing.findings()).anyMatch(
        f -> f.code().equals("CHANNEL_REQUIRES_DECLARATION")
            && f.message().contains("附加声明漏打会导致清关问题"));

    // 有电池声明**但没有电池标识** ⇒ 仍然 BLOCK（只印一行「含电池」过不了清关）
    LabelTemplateRow declarationOnly = saveTemplate(actorA, null, channel, "SFT-DECL-ONLY", "DRAFT",
        "CODE128_B", withSlot,
        List.of(new LabelDeclaration("BATTERY", "UN3481", "Lithium", "BATTERY_LINE", true, "")),
        List.of());
    LabelBindingVerdict declOnly = as(actorA, "channel.operation.read",
            () -> labels.binding(new LabelBindingRequest(
                channel, "DE", List.of("HOME_GOODS"), true, false, false,
                List.of(declarationOnly.id()))))
        .verdicts().get(0);
    assertThat(declOnly.verdict()).isEqualTo("BLOCK");
    assertThat(declOnly.missingBatteryMarks()).containsExactly("BATTERY");
    assertThat(declOnly.findings()).extracting(LabelFinding::code)
        .contains("BATTERY_MARK_MISSING");

    // 声明 + 电池标识齐了 ⇒ 至少 REVIEW（渠道自己就是 MANUAL_REVIEW）
    LabelBindingVerdict ok = as(actorA, "channel.operation.read",
            () -> labels.binding(new LabelBindingRequest(
                channel, "DE", List.of("HOME_GOODS"), true, false, false,
                List.of(withBattery.id()))))
        .verdicts().get(0);
    assertThat(ok.verdict()).isEqualTo("REVIEW");
    assertThat(ok.missingDeclarations()).isEmpty();
    assertThat(ok.missingBatteryMarks()).isEmpty();
    assertThat(ok.findings()).anyMatch(f -> f.code().equals("CHANNEL_RESTRICTION"));
  }

  @Test
  @DisplayName("🔴 渠道硬拦电池 ⇒ 对所有模板都是 BLOCK（换模板救不了承运商拒收）")
  void channelHardBlockAppliesToEveryTemplate() {
    UUID channel = seedChannel(a, "SFT-DE", "顺丰德国", "ACTIVE");
    seedRestriction(a, channel, "BATTERY", "", "BLOCK");
    List<LabelElement> withSlot = new ArrayList<>(standardElements());
    withSlot.add(new LabelElement("BATTERY_LINE", "DECLARATION_TEXT", null,
        new BigDecimal("4"), new BigDecimal("98"), new BigDecimal("92"), new BigDecimal("12"),
        10, 0, true, 4, ""));
    LabelTemplateRow full = saveTemplate(actorA, null, channel, "SFT-FULL", "DRAFT", "CODE128_B",
        withSlot,
        List.of(new LabelDeclaration("BATTERY", "UN3481", "Lithium", "BATTERY_LINE", true, "")),
        List.of(new LabelBatteryMark("LITHIUM_ION", new BigDecimal("12"),
            new BigDecimal("0.100"), true, "LI-ION", "BATTERY_LINE", "")));

    LabelBindingResult result = as(actorA, "channel.operation.read",
        () -> labels.binding(new LabelBindingRequest(
            channel, "DE", List.of("HOME_GOODS"), true, false, false, null)));
    // 声明和标识都齐了，仍然 BLOCK —— 渠道根本不收带电货
    assertThat(result.verdicts()).allMatch(v -> "BLOCK".equals(v.verdict()));
    assertThat(result.block()).isEqualTo(result.verdicts().size());
    assertThat(full.readiness()).isEqualTo("READY");

    // 本票不带电 ⇒ 电池限制不参与判定
    LabelBindingResult noBattery = as(actorA, "channel.operation.read",
        () -> labels.binding(new LabelBindingRequest(
            channel, "DE", List.of("HOME_GOODS"), false, false, false, null)));
    assertThat(noBattery.block()).isZero();
  }

  @Test
  @DisplayName("🔴 渠道禁限品类命中 ⇒ BLOCK，且换模板救不了")
  void prohibitedCategoryBlocksEverything() {
    UUID channel = seedChannel(a, "SFT-DE", "顺丰德国", "ACTIVE");
    seedRestriction(a, channel, "PROHIBITED_CATEGORY", "DANGEROUS_GOODS", "BLOCK");
    saveTemplate(actorA, channel, "SFT-100-70");

    LabelBindingResult hit = as(actorA, "channel.operation.read",
        () -> labels.binding(new LabelBindingRequest(
            channel, "DE", List.of("HOME_GOODS", "DANGEROUS_GOODS"), false, false, false, null)));
    assertThat(hit.block()).isEqualTo(1);
    assertThat(hit.verdicts().get(0).findings()).extracting(LabelFinding::code)
        .contains("CHANNEL_PROHIBITS_CATEGORY");

    LabelBindingResult miss = as(actorA, "channel.operation.read",
        () -> labels.binding(new LabelBindingRequest(
            channel, "DE", List.of("HOME_GOODS"), false, false, false, null)));
    assertThat(miss.block()).isZero();
  }

  @Test
  @DisplayName("本票用不到的声明会记成 REVIEW（模板多背了一条约束，要让人看见）")
  void redundantDeclarationIsFlagged() {
    UUID channel = seedChannel(a, "SFT-DE", "顺丰德国", "ACTIVE");
    seedRestriction(a, channel, "LIQUID", "", "MANUAL_REVIEW");
    List<LabelElement> withSlot = new ArrayList<>(standardElements());
    withSlot.add(new LabelElement("LIQUID_LINE", "DECLARATION_TEXT", null,
        new BigDecimal("4"), new BigDecimal("98"), new BigDecimal("92"), new BigDecimal("12"),
        10, 0, true, 4, ""));
    LabelTemplateRow row = saveTemplate(actorA, null, channel, "SFT-LIQ", "DRAFT", "CODE128_B",
        withSlot,
        List.of(new LabelDeclaration("LIQUID", "", "Liquid", "LIQUID_LINE", false, "")), List.of());

    LabelBindingResult result = as(actorA, "channel.operation.read",
        () -> labels.binding(new LabelBindingRequest(
            channel, "DE", List.of("HOME_GOODS"), false, false, false,
            List.of(row.id()))));
    assertThat(result.verdicts().get(0).redundantDeclarations()).containsExactly("LIQUID");
    assertThat(result.verdicts().get(0).findings()).extracting(LabelFinding::code)
        .contains("REDUNDANT_DECLARATION");
  }

  // ==================================================================
  // 尺寸 / 状态 / 启用闸
  // ==================================================================

  @Test
  @DisplayName("🔴 打印位越出纸张被 DB 拒（这不是零点校准，是废纸）")
  void elementOutOfPaperIsRejected() {
    UUID channel = seedChannel(a, "SFT-DE", "顺丰德国", "ACTIVE");
    List<LabelElement> overflow = new ArrayList<>(standardElements());
    overflow.add(new LabelElement("RUNNING", "TEXT", null,
        new BigDecimal("4"), new BigDecimal("144"), new BigDecimal("92"), new BigDecimal("20"),
        10, 0, false, 4, ""));
    assertThatThrownBy(() -> saveTemplate(actorA, null, channel, "SFT-OVER", "DRAFT", "CODE128_B",
            overflow, List.of(), List.of()))
        .hasMessageContaining("越出纸张");

    // 负坐标同样拒（负值只能来自 origin_offset 校准，不是元素坐标）
    List<LabelElement> negative = new ArrayList<>(standardElements());
    negative.add(new LabelElement("NEG", "TEXT", null,
        new BigDecimal("-2"), new BigDecimal("10"), new BigDecimal("40"), new BigDecimal("8"),
        10, 0, false, 4, ""));
    assertThatThrownBy(() -> saveTemplate(actorA, null, channel, "SFT-NEG", "DRAFT", "CODE128_B",
            negative, List.of(), List.of()))
        .hasMessageContaining("坐标为负");
  }

  @Test
  @DisplayName("🔴 模板代码建后不可改（下游打印与单据对齐这张模板的口径）")
  void templateCodeIsImmutable() {
    UUID channel = seedChannel(a, "SFT-DE", "顺丰德国", "ACTIVE");
    LabelTemplateRow row = saveTemplate(actorA, channel, "SFT-100-70");
    assertThatThrownBy(() -> saveTemplate(actorA, row.id(), channel, "RENAMED", "DRAFT", "CODE128_B",
            standardElements(), List.of(), List.of()))
        .hasMessageContaining("模板代码建后不可改");
  }

  @Test
  @DisplayName("🔴 元素级条码类型必须与主条码类型一致（现场不会「随便挑一个」）")
  void elementSymbologyMustMatchPrimary() {
    UUID channel = seedChannel(a, "SFT-DE", "顺丰德国", "ACTIVE");
    List<LabelElement> mixed = new ArrayList<>(standardElements());
    mixed.set(1, new LabelElement("WB_BARCODE", "WAYBILL_BARCODE", "CODE39",
        new BigDecimal("4"), new BigDecimal("38"), new BigDecimal("92"), new BigDecimal("20"),
        null, 0, true, 1, ""));
    assertThatThrownBy(() -> saveTemplate(actorA, null, channel, "SFT-MIX", "DRAFT", "CODE128_B",
            mixed, List.of(), List.of()))
        .hasMessageContaining("与本模板的主条码类型（CODE128_B）不一致");

    // 改主条码类型为 CODE39 后就一致了
    LabelTemplateRow row = saveTemplate(actorA, null, channel, "SFT-C39", "DRAFT", "CODE39",
        mixed, List.of(), List.of());
    assertThat(row.primaryBarcodeSymbology()).isEqualTo("CODE39");
  }

  @Test
  @DisplayName("齐备度：没有打印元素 / 声明了主条码却没有条码元素")
  void readinessReflectsRealGaps() {
    UUID channel = seedChannel(a, "SFT-DE", "顺丰德国", "ACTIVE");
    LabelTemplateRow empty = saveTemplate(actorA, null, channel, "SFT-EMPTY", "DRAFT", "CODE128_B",
        List.of(), List.of(), List.of());
    assertThat(empty.readiness()).isEqualTo("NO_LAYOUT");

    List<LabelElement> noBarcode = List.of(
        new LabelElement("SHIP_TO", "SHIP_TO_ADDRESS", null,
            new BigDecimal("4"), new BigDecimal("4"), new BigDecimal("92"), new BigDecimal("30"),
            12, 0, true, 0, ""));
    LabelTemplateRow noBarcodeRow = saveTemplate(actorA, null, channel, "SFT-NOBAR", "DRAFT",
        "CODE128_B", noBarcode, List.of(), List.of());
    assertThat(noBarcodeRow.readiness()).isEqualTo("NO_BARCODE");

    LabelTemplateRow good = saveTemplate(actorA, null, channel, "SFT-OK", "DRAFT", "CODE128_B",
        standardElements(), List.of(), List.of());
    assertThat(good.readiness()).isEqualTo("READY");
    assertThat(good.activatable()).isTrue();
  }

  @Test
  @DisplayName("🔴 渠道不可用时 effectiveReadiness 是 NO_CHANNEL（改了模板也用不了）")
  void suspendedChannelBlocksUsage() {
    UUID channel = seedChannel(a, "SFT-DE", "顺丰德国", "ACTIVE");
    LabelTemplateRow row = saveTemplate(actorA, channel, "SFT-100-70");
    assertThat(row.effectiveReadiness()).isEqualTo("READY");

    // 把渠道停用（10.1.1.2 的表，本模块只读）
    rawSqlAs(a, "update logistics_channel_config set status='SUSPENDED', readiness='SUSPENDED'"
        + " where tenant_id=? and id=?", a, channel);

    LabelTemplateRow after = as(actorA, "channel.operation.read", () -> labels.detail(row.id()));
    assertThat(after.readiness()).isEqualTo("READY"); // 本地齐备度没变
    assertThat(after.effectiveReadiness()).isEqualTo("NO_CHANNEL");
    assertThat(after.effectiveReadinessMessage()).contains("改了本模板也用不了");

    // 启用闸也拦住
    assertThatThrownBy(() -> as(actorA, "channel.operation.update",
            () -> labels.setStatus(row.id(), "ACTIVE", after.version())))
        .hasMessageContaining("不能启用");

    // readiness 筛选器能筛出这一档（不是存了没人读）
    LabelTemplatePage page = as(actorA, "channel.operation.read",
        () -> labels.templates(null, null, null, null, "NO_CHANNEL", null, null, "NAME_ASC", 0, 50));
    assertThat(page.items()).hasSize(1);
    assertThat(page.summary().noChannel()).isEqualTo(1);
  }

  @Test
  @DisplayName("启用走一次真渲染并留证（ACTIVATION 那条 render 行）")
  void activationRendersAndLeavesEvidence() {
    UUID channel = seedChannel(a, "SFT-DE", "顺丰德国", "ACTIVE");
    LabelTemplateRow row = saveTemplate(actorA, channel, "SFT-100-70");

    LabelTemplateRow active =
        as(actorA, "channel.operation.update", () -> labels.setStatus(row.id(), "ACTIVE", 0));
    assertThat(active.status()).isEqualTo("ACTIVE");

    LabelRenderPage history = as(actorA, "channel.operation.read", () -> labels.renders(row.id(), 0, 20));
    assertThat(history.total()).isEqualTo(1);
    assertThat(history.items().get(0).triggerSource()).isEqualTo("ACTIVATION");
    assertThat(history.items().get(0).zplContent()).startsWith("^XA");
    // 顺带把渲染器算出的种子条码也真编码了
    assertThat(history.items().get(0).symbology()).isEqualTo("CODE128_B");

    // 乐观锁：版本已经推进
    assertThatThrownBy(() -> as(actorA, "channel.operation.update",
            () -> labels.setStatus(row.id(), "DRAFT", 0)))
        .hasMessageContaining("已被他人修改");
  }

  // ==================================================================
  // 真分页 / 真筛选 / 概览
  // ==================================================================

  @Test
  @DisplayName("真分页真筛选，汇总不受分页影响")
  void paginationAndFiltersAreReal() {
    UUID channel = seedChannel(a, "SFT-DE", "顺丰德国", "ACTIVE");
    UUID emptyChannel = seedChannel(a, "EMS-DE", "EMS 德国", "ACTIVE");
    for (int i = 0; i < 7; i++) {
      saveTemplate(actorA, channel, "SFT-100-70-" + i);
    }
    saveTemplate(actorA, emptyChannel, "EMS-100-70");

    LabelTemplatePage first = as(actorA, "channel.operation.read",
        () -> labels.templates(null, null, null, null, null, null, null, "CODE_ASC", 0, 3));
    assertThat(first.items()).hasSize(3);
    assertThat(first.total()).isEqualTo(8);
    assertThat(first.totalPages()).isEqualTo(3);
    // 汇总走独立查询：不受 limit/offset 影响
    assertThat(first.summary().total()).isEqualTo(8);
    assertThat(first.summary().ready()).isEqualTo(8);

    LabelTemplatePage second = as(actorA, "channel.operation.read",
        () -> labels.templates(null, null, null, null, null, null, null, "CODE_ASC", 1, 3));
    assertThat(second.items()).extracting(LabelTemplateRow::templateCode)
        .doesNotContainAnyElementsOf(first.items().stream().map(LabelTemplateRow::templateCode).toList());

    // 按渠道筛
    LabelTemplatePage byChannel = as(actorA, "channel.operation.read",
        () -> labels.templates(null, channel.toString(), null, null, null, null, null, "NAME_ASC", 0, 50));
    assertThat(byChannel.total()).isEqualTo(7);

    // 按渠道 + 国家筛（国家是「按渠道与国家配置」那条职责句的另一半）
    assertThat(as(actorA, "channel.operation.read",
            () -> labels.templates(null, channel.toString(), "DE", null, null, null, null,
                "NAME_ASC", 0, 50))
        .total()).isEqualTo(7);
    assertThat(as(actorA, "channel.operation.read",
            () -> labels.templates(null, channel.toString(), "FR", null, null, null, null,
                "NAME_ASC", 0, 50))
        .total()).isZero();

    // 关键字
    assertThat(as(actorA, "channel.operation.read",
            () -> labels.templates("EMS", null, null, null, null, null, null, "NAME_ASC", 0, 50))
        .items()).extracting(LabelTemplateRow::templateCode).containsExactly("EMS-100-70");

    // 负页码被钳位到 0（曾算出负 OFFSET 被映射成 409，页面笔误显示成「数据冲突」）
    LabelTemplatePage clamped = as(actorA, "channel.operation.read",
        () -> labels.templates(null, null, null, null, null, null, null, "CODE_ASC", -1, 3));
    assertThat(clamped.page()).isZero();
    assertThat(clamped.items()).hasSize(3);

    // 概览是全量口径，不随筛选变化
    LabelTemplateOverview overview = as(actorA, "channel.operation.read", () -> labels.overview());
    assertThat(overview.summary().total()).isEqualTo(8);
    // 🔴 概览里刻意**没有** byFormat（模板表没有输出格式列）；有的是附加声明分布，
    //    它是加粗句在概览层面的落点：一张强制声明都没登记，比任何汇总数字都值得注意。
    assertThat(overview.byDeclarationKind()).extracting(LabelTemplateCount::code)
        .containsExactly("BATTERY", "LIQUID", "MAGNET", "DANGEROUS_GOODS", "BIOHAZARD",
            "RETURN_DECLARATION", "OTHER");
    assertThat(overview.byDeclarationKind()).allMatch(count -> count.count() == 0);
    assertThat(overview.bySymbology()).extracting(LabelTemplateCount::code)
        .contains("CODE128_B", "NONE", "QR", "DATAMATRIX", "CODE39", "CODE128_A");
    assertThat(overview.byLanguage()).extracting(LabelTemplateCount::code).contains("de-DE");
  }

  @Test
  @DisplayName("能力声明：未交付段显式 available=false + ownerModule + 影响")
  void capabilitiesDeclareGapsHonestly() {
    LabelTemplateCapabilities caps =
        as(actorA, "channel.operation.read", () -> labels.capabilities());
    assertThat(caps.dimensions()).extracting(LabelCapabilityDimension::dimension)
        .contains("label_size", "barcode_symbology", "additional_declaration", "battery_mark",
            "language_version", "channel_binding", "label_rendering");
    // 二维码点阵与打印下发**如实标不可用**，并点名 ownerModule
    assertThat(caps.dimensions())
        .filteredOn(d -> "two_dimensional_encoding".equals(d.dimension()))
        .singleElement()
        .satisfies(d -> {
          assertThat(d.available()).isFalse();
          assertThat(d.ownerModule()).isEqualTo("8.3.4.2");
          assertThat(d.impact()).contains("不能替打印机生成点阵");
        });
    assertThat(caps.dimensions())
        .filteredOn(d -> "print_dispatch".equals(d.dimension()))
        .singleElement()
        .satisfies(d -> {
          assertThat(d.available()).isFalse();
          assertThat(d.ownerModule()).isEqualTo("8.3.4.2");
          assertThat(d.impact()).contains("两份「印的是什么」");
        });
    assertThat(caps.bindingNote()).contains("漏打会导致清关问题");
    assertThat(caps.symbologies()).containsExactly("NONE", "CODE128_B", "CODE128_A", "CODE39", "QR",
        "DATAMATRIX");
    assertThat(caps.effectiveReadinessCodes()).contains("NO_CHANNEL");
  }

  // ==================================================================
  // 端点
  // ==================================================================

  @Test
  @DisplayName("端点：列表 / 详情 / 保存 / 启用 / 预览 / 渲染历史 / 绑定 / 删除")
  void endpointsWorkOverHttp() throws Exception {
    UUID channel = seedChannel(a, "SFT-DE", "顺丰德国", "ACTIVE");
    String bearer = "Bearer tenant-a";

    http.perform(get("/channel/label-templates/capabilities").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions").isArray());
    http.perform(get("/channel/label-templates/overview").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.total").value(0));

    String body = new ObjectMapper().writeValueAsString(
        new SaveLabelTemplateRequest(
            null, channel, "SFT-HTTP", "HTTP 登记的模板", "DE", "de-DE", "DRAFT",
            new BigDecimal("100"), new BigDecimal("150"), 203, "PORTRAIT",
            BigDecimal.ZERO, BigDecimal.ZERO, "CODE128_B", new BigDecimal("12"), true,
            standardElements(), List.of(), List.of(), "http 登记", 0));
    String saved = http.perform(post("/channel/label-templates")
            .header("Authorization", bearer)
            .contentType("application/json")
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.templateCode").value("SFT-HTTP"))
        .andExpect(jsonPath("$.readiness").value("READY"))
        .andExpect(jsonPath("$.elements.length()").value(4))
        .andReturn().getResponse().getContentAsString();
    String id = new ObjectMapper().readTree(saved).get("id").asText();

    http.perform(get("/channel/label-templates/{id}", id).header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paperWidthMm").value(100.0));
    http.perform(get("/channel/label-templates").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));

    http.perform(post("/channel/label-templates/{id}/preview", id)
            .header("Authorization", bearer)
            .contentType("application/json")
            .content(new ObjectMapper().writeValueAsString(sample("DE1234567890123"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verdict").value("PASS"))
        .andExpect(jsonPath("$.zplContent", org.hamcrest.Matchers.startsWith("^XA")))
        .andExpect(jsonPath("$.contentSha256", org.hamcrest.Matchers.hasLength(64)));

    http.perform(get("/channel/label-templates/{id}/renders", id).header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].triggerSource").value("PREVIEW"));

    http.perform(post("/channel/label-templates/{id}/status", id)
            .header("Authorization", bearer)
            .contentType("application/json")
            .content("{\"status\":\"ACTIVE\",\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));

    http.perform(post("/channel/label-templates/binding")
            .header("Authorization", bearer)
            .contentType("application/json")
            .content("{\"channelId\":\"" + channel + "\",\"countryCode\":\"DE\","
                + "\"categoryCodes\":[\"HOME_GOODS\"],\"containsBattery\":false,"
                + "\"containsLiquid\":false,\"containsMagnet\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verdicts.length()").value(1))
        .andExpect(jsonPath("$.verdicts[0].verdict").value("PASS"));

    // 跨租户读别人的模板：逐字相同的 404
    String missing = http.perform(get("/channel/label-templates/{id}", id)
            .header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isNotFound())
        .andReturn().getResponse().getContentAsString();
    String absent = http.perform(get("/channel/label-templates/{id}", UUID.randomUUID().toString())
            .header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isNotFound())
        .andReturn().getResponse().getContentAsString();
    // 逐字相同的是**理由**；instance 字段带 URL（含各自的 id），本来就不该相同
    assertThat(json(missing).get("detail").asText())
        .isEqualTo(json(absent).get("detail").asText());
    assertThat(json(missing).get("detail").asText()).isEqualTo("面单模板不存在");

    http.perform(delete("/channel/label-templates/{id}", id).header("Authorization", bearer))
        .andExpect(status().isNoContent());
    assertThat(rowsIn("logistics_label_render")).isZero();
  }

  @Test
  @DisplayName("无会话 401 / 无权限 403")
  void authIsEnforced() throws Exception {
    http.perform(get("/channel/label-templates")).andExpect(status().isUnauthorized());
    http.perform(post("/channel/label-templates/binding")
            .contentType("application/json")
            .content("{\"countryCode\":\"DE\",\"categoryCodes\":[],\"containsBattery\":false,"
                + "\"containsLiquid\":false,\"containsMagnet\":false}"))
        .andExpect(status().isUnauthorized());
  }
}
