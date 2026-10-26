package com.starcon.channel;

import static com.starcon.channel.SalesTaxModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * 16.3.2.3 销售税与 Marketplace Facilitator 的集成测试（**真实 PostgreSQL 16**）。
 *
 * <p>🔴 这组用例守的是本模块最容易做丢的十三件事：
 *
 * <ol>
 *   <li><b>税率 / 阈值 / 免征额是数据</b>：录进去、查得到、**发新版本后旧版本内容冻结**（DB 拒）；</li>
 *   <li><b>同一州的生效区间不许重叠</b>（DB 触发器拒）；</li>
 *   <li><b>州码不许自由文本</b>：不存在的州码被拒（DB 触发器 + 服务层）；</li>
 *   <li><b>店铺所在州</b>登记后，自营应税销售额真的按州归集；</li>
 *   <li><b>代扣代缴报表是真上传 + 真解析 + 真落库</b>（原文与指纹都在库里）；</li>
 *   <li><b>行级错误只跳过那一行</b>，文件级错误整份失败；</li>
 *   <li><b>同一份报表重新导入 ⇒ 版本号 +1</b>（历史版本留着）；</li>
 *   <li><b>应缴与已代缴是两条线</b>，差异 = 应缴 − 已代缴，**不压成净额**；</li>
 *   <li><b>未达阈值 ⇒ 期间无申报义务</b>（但不是免税）；</li>
 *   <li><b>没有生效规则 ⇒ 判不了</b>（不许按 0 税率算出「不用交税」）；</li>
 *   <li><b>没登记店铺所在州 ⇒ 判不了</b>；</li>
 *   <li><b>timestamptz 往返断言写回的值</b>（不是只断言没抛异常）；</li>
 *   <li><b>真分页 + 真筛选</b>（不是假分页）。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SalesTaxIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer finance;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");

  /** 🔴 finance 桩：上游 12.3.1.2 交易明细的返回行（测试自己往里放）。 */
  static final List<Map<String, Object>> financeRows = new ArrayList<>();

  /** 🔴 finance 桩不可达时置位：验证「上游读不到就不产出结论」。 */
  static volatile boolean financeDown = false;

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

      // 🔴 finance 桩：只服务 `GET /finance/transactions` 这一个形状。
      finance = HttpServer.create(new InetSocketAddress(0), 0);
      finance.createContext(
          "/finance/transactions",
          e -> {
            if (financeDown) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            Map<String, String> query = new LinkedHashMap<>();
            for (String pair : e.getRequestURI().getRawQuery().split("&")) {
              if (pair.isEmpty()) continue;
              int eq = pair.indexOf('=');
              String key = eq < 0 ? pair : pair.substring(0, eq);
              String value = eq < 0 ? "" : pair.substring(eq + 1);
              query.put(
                  URLDecoder.decode(key, StandardCharsets.UTF_8),
                  URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
            String type = query.get("transactionType");
            String from = query.get("occurredFrom");
            String to = query.get("occurredTo");
            List<Map<String, Object>> matched = new ArrayList<>();
            for (Map<String, Object> row : financeRows) {
              if (type != null && !type.isEmpty() && !type.equals(row.get("transactionType"))) {
                continue;
              }
              String occurred = String.valueOf(row.get("occurredOn"));
              if (from != null && occurred.compareTo(from) < 0) continue;
              if (to != null && occurred.compareTo(to) > 0) continue;
              matched.add(row);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", matched);
            body.put("page", 0);
            body.put("size", 200);
            body.put("total", matched.size());
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
    financeRows.clear();
    financeDown = false;
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

  @Autowired SalesTaxService service;
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

  /**
   * 🔴 以<b>应用角色 channel_app</b>（非超级用户）按当前租户直查。
   *
   * <p>超级用户<b>绕过 RLS</b> —— 用它查「这一行落在这个租户名下」会看到别的租户的行，
   * 那样断言就永远「通过」了。
   */
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

  /** 造一家店（owner 由 stamp_shop_owner 触发器落成当前 actor）。 */
  UUID seedShop(String code) {
    UUID id = UUID.randomUUID();
    execAsTenant(
        tenant,
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
    return id;
  }

  /** 往 finance 桩里放一行交易明细。 */
  static void financeRow(
      String shopCode, String type, String amount, String currency, String occurredOn) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", UUID.randomUUID().toString());
    row.put("settlementNo", "ST-" + shopCode);
    row.put("shopCode", shopCode);
    row.put("shopName", "测试店铺 " + shopCode);
    row.put("platformCode", "AMAZON");
    row.put("transactionType", type);
    row.put("amount", new BigDecimal(amount));
    row.put("currency", currency);
    row.put("occurredOn", occurredOn);
    financeRows.add(row);
  }

  /** 录一版税率规则。 */
  SalesTaxRateRuleView rule(
      String state, String from, String baseRate, String threshold, String exemption) {
    return as(
        "channel.operation.create",
        () ->
            service.createRateRule(
                new SalesTaxRateRuleInput(
                    state,
                    from,
                    null,
                    baseRate,
                    threshold,
                    exemption,
                    true,
                    "QUARTERLY",
                    "集成测试规则")));
  }

  /** 登记一家店在一个州的销售税联系。 */
  SalesTaxShopStateView shopState(UUID shopId, String state, boolean registered) {
    return as(
        "channel.operation.create",
        () ->
            service.createShopState(
                new SalesTaxShopStateInput(
                    shopId.toString(),
                    state,
                    "INVENTORY_STORED",
                    registered,
                    registered ? "CA-12345678" : "",
                    "2026-01-01",
                    "集成测试")));
  }

  /** 上传一份 CSV 格式的代扣代缴报表。 */
  SalesTaxFacilitatorImportResult importCsv(String fileKey, String csv) {
    return as(
        "channel.operation.create",
        () ->
            service.importFacilitatorReport(
                new SalesTaxFacilitatorImportInput(
                    "AMAZON",
                    fileKey,
                    fileKey + ".csv",
                    "CSV",
                    csv,
                    "2026-02-01",
                    "2026-02-28")));
  }

  // ==================================================================
  // 一、税率规则
  // ==================================================================

  @Test
  @DisplayName("16.3.2.3 税率规则：录进去、查得到；发新版本后旧版本内容被 DB 冻结")
  void rateRuleIsVersionedAndFrozen() {
    SalesTaxRateRuleView v1 = rule("US-CA", "2026-01-01", "0.072500", "0", "0");
    assertThat(v1.ruleVersion()).isEqualTo(1);
    assertThat(v1.status()).isEqualTo("ACTIVE");
    assertThat(v1.baseRate()).isEqualByComparingTo("0.072500");

    // 生效版本查询（可解释性）
    var effective =
        as(
            "channel.operation.read",
            () -> service.effectiveRule("US-CA", "2026-03-01"));
    assertThat(effective.found()).isTrue();
    assertThat(effective.rule().ruleVersion()).isEqualTo(1);

    // 发新版本：封旧 + 建新
    SalesTaxRateRuleView v2 =
        as(
            "channel.operation.update",
            () ->
                service.updateRateRule(
                    v1.id(),
                    new SalesTaxRateRuleInput(
                        "US-CA",
                        "2026-04-01",
                        null,
                        "0.075000",
                        "0",
                        "0",
                        true,
                        "QUARTERLY",
                        "新版本")));
    assertThat(v2.ruleVersion()).isEqualTo(2);

    // 旧版本已封版：内容不许再改（DB 触发器拒）
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execAsTenant(
                        tenant,
                        "update sales_tax_rate_rule set base_rate = 0.01 where id = '"
                            + v1.id()
                            + "'")))
        .as("已封版的规则内容必须被 DB 拒掉")
        .isNotNull();

    // 生效版本随日期切换
    assertThat(
            as("channel.operation.read", () -> service.effectiveRule("US-CA", "2026-03-31"))
                .rule()
                .ruleVersion())
        .isEqualTo(1);
    assertThat(
            as("channel.operation.read", () -> service.effectiveRule("US-CA", "2026-04-01"))
                .rule()
                .ruleVersion())
        .isEqualTo(2);
  }

  @Test
  @DisplayName("16.3.2.3 税率规则：同一州的生效区间不许重叠（DB 触发器拒）")
  void overlappingRuleWindowsAreRejected() {
    rule("US-CA", "2026-01-01", "0.072500", "0", "0");
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execAsTenant(
                        tenant,
                        "insert into sales_tax_rate_rule (id,tenant_id,state_code,rule_version,"
                            + "effective_from,effective_to,base_rate,threshold_amount,exemption_amount,"
                            + "marketplace_facilitator,filing_cycle,status,note)"
                            + " values (gen_random_uuid(),'"
                            + tenant
                            + "','US-CA',9,'2026-06-01','2026-12-31',0.07,0,0,true,'QUARTERLY','ACTIVE','')")))
        .as("生效区间重叠必须被 DB 拒掉")
        .isNotNull();
  }

  @Test
  @DisplayName("16.3.2.3 州码：不许自由文本（不存在的州被服务层与 DB 层各拒一次）")
  void unknownStateIsRejected() {
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> rule("US-ZZ", "2026-01-01", "0.072500", "0", "0")))
        .as("不存在的州码必须被拒")
        .isNotNull();

    // DB 层同样钉死（直接插表绕过服务层也不行）
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execAsTenant(
                        tenant,
                        "insert into sales_tax_rate_rule (id,tenant_id,state_code,rule_version,"
                            + "effective_from,base_rate,threshold_amount,exemption_amount,"
                            + "marketplace_facilitator,filing_cycle,status,note)"
                            + " values (gen_random_uuid(),'"
                            + tenant
                            + "','US-ZZ',1,'2026-01-01',0.07,0,0,true,'QUARTERLY','ACTIVE','')")))
        .as("DB 层必须钉死州码")
        .isNotNull();
  }

  // ==================================================================
  // 二、店铺所在州
  // ==================================================================

  @Test
  @DisplayName("16.3.2.3 店铺所在州：登记后自营应税销售额真的按州归集")
  void shopStateDrivesAttribution() {
    UUID shop = seedShop("US-SHOP-A");
    SalesTaxShopStateView nexus = shopState(shop, "US-CA", true);
    assertThat(nexus.stateCode()).isEqualTo("US-CA");
    assertThat(nexus.registered()).isTrue();

    // 重复登记同一家店同一个州 ⇒ 冲突
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(() -> shopState(shop, "US-CA", true)))
        .as("同一家店同一个州只能有一条联系记录")
        .isNotNull();

    // 店铺目录里能看到这家店在哪个州
    var shops = as("channel.operation.read", () -> service.shops("US-SHOP-A", null, 0, 20));
    assertThat(shops.items()).hasSize(1);
    assertThat(shops.items().get(0).stateCodes()).containsExactly("US-CA");
    assertThat(shops.items().get(0).registeredStateCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("16.3.2.3 店铺所在州：店铺必须真实存在（DB 触发器钉死）")
  void shopStateRequiresRealShop() {
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    shopState(
                        UUID.randomUUID(),
                        "US-CA",
                        true)))
        .as("不存在的店铺必须被拒")
        .isNotNull();
  }

  // ==================================================================
  // 三、代扣代缴报表（真上传 + 真解析 + 真落库）
  // ==================================================================

  @Test
  @DisplayName("16.3.2.3 代扣代缴报表：真解析落库（原文 + 指纹 + 明细行）")
  void facilitatorReportIsParsedAndStored() {
    String csv =
        "state_code,platform_code,shop_code,taxable_amount,tax_collected,order_count,currency,remark\n"
            + "US-CA,AMAZON,US-SHOP-A,10000,700,12,USD,2026年2月代扣税\n"
            + "US-NV,AMAZON,US-SHOP-A,2000,130,3,USD,\n";
    SalesTaxFacilitatorImportResult result = importCsv("amz-2026-02", csv);

    assertThat(result.scanned()).isEqualTo(2);
    assertThat(result.imported()).isEqualTo(2);
    assertThat(result.skipped()).isZero();
    assertThat(result.report().parseStatus()).isEqualTo("PARSED");
    assertThat(result.report().parseVersion()).isEqualTo(1);
    assertThat(result.report().contentHash()).hasSize(64);

    // 原文一字不改地留着
    String raw =
        scalarAsTenant(
            tenant,
            "select raw_content from sales_tax_facilitator_report where id = '"
                + result.report().id()
                + "'");
    assertThat(raw).isEqualTo(csv);

    // 明细行按文件里的行号落库
    var detail = as("channel.operation.read", () -> service.facilitatorReport(result.report().id()));
    assertThat(detail.lines()).hasSize(2);
    assertThat(detail.lines().get(0).sourceLineNo()).isEqualTo(2);
    assertThat(detail.lines().get(0).stateCode()).isEqualTo("US-CA");
    assertThat(detail.lines().get(0).taxCollected()).isEqualByComparingTo("700.000000");
    assertThat(detail.lines().get(1).sourceLineNo()).isEqualTo(3);

    // 同一份报表重新导入 ⇒ 版本号 +1（历史版本留着）
    SalesTaxFacilitatorImportResult again = importCsv("amz-2026-02", csv);
    assertThat(again.report().parseVersion()).isEqualTo(2);
    assertThat(
            scalarAsTenant(
                tenant,
                "select count(*) from sales_tax_facilitator_report where file_key = 'amz-2026-02'"))
        .isEqualTo("2");
  }

  @Test
  @DisplayName("16.3.2.3 代扣代缴报表：行级错误只跳过那一行，文件级错误整份失败")
  void importErrorLevelsAreSeparated() {
    String csv =
        "state_code,tax_collected,currency\n"
            + "US-CA,700,USD\n"
            + "US-CA,不是数字,USD\n"
            + "US-ZZ,100,USD\n"
            + "US-NV,130,US\n";
    SalesTaxFacilitatorImportResult result = importCsv("amz-mixed", csv);

    assertThat(result.scanned()).isEqualTo(4);
    assertThat(result.imported()).isEqualTo(1);
    assertThat(result.skipped()).isEqualTo(3);
    assertThat(result.warnings()).hasSize(3);
    assertThat(result.warnings())
        .anyMatch(w -> w.contains("第 3 行") && w.contains("不是数字"));
    assertThat(result.warnings())
        .anyMatch(w -> w.contains("第 4 行") && w.contains("US-ZZ"));
    assertThat(result.warnings())
        .anyMatch(w -> w.contains("第 5 行") && w.contains("币种"));

    // 文件级错误：CSV 缺必需的列 ⇒ 整个请求失败，一条都不导入
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> importCsv("amz-broken", "foo,bar\n1,2\n")))
        .as("缺必需列必须整份失败")
        .isNotNull();
    assertThat(
            scalarAsTenant(
                tenant,
                "select count(*) from sales_tax_facilitator_report where file_key = 'amz-broken'"))
        .as("文件级失败不许落库")
        .isEqualTo("0");
  }

  // ==================================================================
  // 四、对账（应缴 vs 已代缴，两条线）
  // ==================================================================

  @Test
  @DisplayName("16.3.2.3 对账：应缴与已代缴分开存，差异 = 应缴 − 已代缴")
  void reconciliationKeepsTwoLines() {
    UUID shop = seedShop("US-SHOP-A");
    shopState(shop, "US-CA", true);
    rule("US-CA", "2026-01-01", "0.072500", "0", "0");
    financeRow("US-SHOP-A", "ORDER_SALES", "10000", "USD", "2026-02-15");
    importCsv(
        "amz-2026-02",
        "state_code,tax_collected,currency\nUS-CA,700,USD\n");

    var computed =
        as(
            "channel.operation.create",
            () ->
                service.computeReconciliations(
                    new SalesTaxReconciliationRequest(
                        "US-CA", "2026-02-01", "2026-02-28", null),
                    bearer));

    assertThat(computed).hasSize(1);
    var row = computed.get(0);
    assertThat(row.stateCode()).isEqualTo("US-CA");
    assertThat(row.currency()).isEqualTo("USD");
    // 自营应税销售额 10000（来自上游 12.3.1.2 的 ORDER_SALES 行）
    assertThat(row.selfTaxableSales()).isEqualByComparingTo("10000.000000");
    // 应缴 = 10000 × 7.25% = 725
    assertThat(row.expectedTax()).isEqualByComparingTo("725.000000");
    // 平台已代缴 700（来自上传的代扣代缴报表）
    assertThat(row.facilitatorTax()).isEqualByComparingTo("700.000000");
    // 差异 25（正数 = 平台少收了）
    assertThat(row.variance()).isEqualByComparingTo("25.000000");
    assertThat(row.status()).isEqualTo("VARIANCE");
    assertThat(row.ruleVersion()).isEqualTo(1);
    assertThat(row.shopCount()).isEqualTo(1);

    // 🔴 **两条线都在库里**（不是一个净额）
    String stored =
        scalarAsTenant(
            tenant,
            "select expected_tax || '|' || facilitator_tax || '|' || variance"
                + " from sales_tax_reconciliation where id = '"
                + row.id()
                + "'");
    assertThat(stored).isEqualTo("725.000000|700.000000|25.000000");
  }

  @Test
  @DisplayName("16.3.2.3 对账：已代缴等于应缴 ⇒ 已对平")
  void reconciliationBalances() {
    UUID shop = seedShop("US-SHOP-A");
    shopState(shop, "US-CA", true);
    rule("US-CA", "2026-01-01", "0.072500", "0", "0");
    financeRow("US-SHOP-A", "ORDER_SALES", "10000", "USD", "2026-02-15");
    importCsv("amz-2026-02", "state_code,tax_collected,currency\nUS-CA,725,USD\n");

    var computed =
        as(
            "channel.operation.create",
            () ->
                service.computeReconciliations(
                    new SalesTaxReconciliationRequest("US-CA", "2026-02-01", "2026-02-28", null),
                    bearer));
    assertThat(computed.get(0).status()).isEqualTo("BALANCED");
    assertThat(computed.get(0).variance()).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("16.3.2.3 对账：未达州级阈值 ⇒ 期间无申报义务（但不是免税）")
  void reconciliationBelowThreshold() {
    UUID shop = seedShop("US-SHOP-A");
    shopState(shop, "US-CA", true);
    rule("US-CA", "2026-01-01", "0.072500", "1000", "0");
    financeRow("US-SHOP-A", "ORDER_SALES", "100", "USD", "2026-02-15");
    importCsv("amz-2026-02", "state_code,tax_collected,currency\nUS-CA,7,USD\n");

    var computed =
        as(
            "channel.operation.create",
            () ->
                service.computeReconciliations(
                    new SalesTaxReconciliationRequest("US-CA", "2026-02-01", "2026-02-28", null),
                    bearer));
    assertThat(computed.get(0).status()).isEqualTo("BELOW_THRESHOLD");
    assertThat(computed.get(0).expectedTax()).isEqualByComparingTo("0");
    // 平台已经收了 7 —— 如实记着（可能要退）
    assertThat(computed.get(0).facilitatorTax()).isEqualByComparingTo("7");
  }

  @Test
  @DisplayName("16.3.2.3 对账：没有生效规则 ⇒ 判不了（RULE_MISSING），不按 0 税率算")
  void reconciliationWithoutRuleIsIndeterminate() {
    UUID shop = seedShop("US-SHOP-A");
    shopState(shop, "US-NV", true);
    financeRow("US-SHOP-A", "ORDER_SALES", "10000", "USD", "2026-02-15");

    var computed =
        as(
            "channel.operation.create",
            () ->
                service.computeReconciliations(
                    new SalesTaxReconciliationRequest("US-NV", "2026-02-01", "2026-02-28", null),
                    bearer));
    assertThat(computed.get(0).status()).isEqualTo("RULE_MISSING");
    assertThat(computed.get(0).expectedTax()).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("16.3.2.3 对账：没登记店铺所在州 ⇒ 判不了（NEXUS_MISSING）")
  void reconciliationWithoutNexusIsIndeterminate() {
    seedShop("US-SHOP-A");
    rule("US-WA", "2026-01-01", "0.065000", "0", "0");
    importCsv("amz-2026-02", "state_code,tax_collected,currency\nUS-WA,50,USD\n");

    var computed =
        as(
            "channel.operation.create",
            () ->
                service.computeReconciliations(
                    new SalesTaxReconciliationRequest("US-WA", "2026-02-01", "2026-02-28", null),
                    bearer));
    assertThat(computed.get(0).status()).isEqualTo("NEXUS_MISSING");
  }

  @Test
  @DisplayName("16.3.2.3 对账：上游读不到 ⇒ 整体失败，一条都不写（不许拿 0 当「没卖货」）")
  void upstreamUnavailableProducesNoConclusion() {
    UUID shop = seedShop("US-SHOP-A");
    shopState(shop, "US-CA", true);
    rule("US-CA", "2026-01-01", "0.072500", "0", "0");
    financeDown = true;

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.create",
                        () ->
                            service.computeReconciliations(
                                new SalesTaxReconciliationRequest(
                                    "US-CA", "2026-02-01", "2026-02-28", null),
                                bearer))))
        .as("上游读不到必须显式失败")
        .isNotNull();
    assertThat(
            scalarAsTenant(tenant, "select count(*) from sales_tax_reconciliation"))
        .as("上游读不到时不许落任何对账行")
        .isEqualTo("0");
  }

  @Test
  @DisplayName("16.3.2.3 对账：结算单口径的代扣税只作交叉验证，不参与差异计算")
  void settlementTaxIsCrossCheckOnly() {
    UUID shop = seedShop("US-SHOP-A");
    shopState(shop, "US-CA", true);
    rule("US-CA", "2026-01-01", "0.072500", "0", "0");
    financeRow("US-SHOP-A", "ORDER_SALES", "10000", "USD", "2026-02-15");
    financeRow("US-SHOP-A", "WITHHOLDING_TAX", "690", "USD", "2026-02-15");
    importCsv("amz-2026-02", "state_code,tax_collected,currency\nUS-CA,700,USD\n");

    var computed =
        as(
            "channel.operation.create",
            () ->
                service.computeReconciliations(
                    new SalesTaxReconciliationRequest("US-CA", "2026-02-01", "2026-02-28", null),
                    bearer));
    // 差异仍然只看「应缴 − 报表口径已代缴」
    assertThat(computed.get(0).variance()).isEqualByComparingTo("25.000000");
    // 结算单口径单独记着，供交叉验证
    assertThat(computed.get(0).settlementTax()).isEqualByComparingTo("690.000000");
  }

  // ==================================================================
  // 五、州状态与申报义务
  // ==================================================================

  @Test
  @DisplayName("16.3.2.3 州状态与申报义务：平台代缴不抵消申报义务")
  void stateStatusAndObligations() {
    UUID shop = seedShop("US-SHOP-A");
    shopState(shop, "US-CA", true);
    rule("US-CA", "2026-01-01", "0.072500", "0", "0");
    financeRow("US-SHOP-A", "ORDER_SALES", "10000", "USD", "2026-02-15");
    importCsv("amz-2026-02", "state_code,tax_collected,currency\nUS-CA,700,USD\n");
    as(
        "channel.operation.create",
        () ->
            service.computeReconciliations(
                new SalesTaxReconciliationRequest("US-CA", "2026-02-01", "2026-02-28", null),
                bearer));

    var states = as("channel.operation.read", () -> service.states("US-CA", null, null, null, 0, 20));
    assertThat(states.items()).hasSize(1);
    var state = states.items().get(0);
    assertThat(state.shopCount()).isEqualTo(1);
    assertThat(state.registeredShopCount()).isEqualTo(1);
    assertThat(state.registrationStatus()).isEqualTo("REGISTERED");
    assertThat(state.ruleStatus()).isEqualTo("RULE_FOUND");
    assertThat(state.marketplaceFacilitator()).isTrue();
    assertThat(state.latestReconciliationId()).isNotNull();
    assertThat(state.status()).isEqualTo("VARIANCE");

    // 申报义务：平台代缴了 700，但义务仍然是「需要申报」
    var obligations =
        as("channel.operation.read", () -> service.obligations("US-CA", null, null, null, 0, 20));
    assertThat(obligations.items()).hasSize(1);
    assertThat(obligations.items().get(0).filingObligation()).isEqualTo("FILING_REQUIRED");
    assertThat(obligations.items().get(0).expectedTax()).isEqualByComparingTo("725");
    assertThat(obligations.items().get(0).facilitatorTax()).isEqualByComparingTo("700");
    assertThat(obligations.items().get(0).variance()).isEqualByComparingTo("25");
  }

  @Test
  @DisplayName("16.3.2.3 分页与筛选：真分页（不是假分页）")
  void paginationIsReal() {
    for (int i = 0; i < 5; i++) {
      rule(stateFor(i), "2026-01-01", "0.072500", "0", "0");
    }
    var page1 = as("channel.operation.read", () -> service.rateRules(null, null, 0, 2));
    assertThat(page1.items()).hasSize(2);
    assertThat(page1.total()).isEqualTo(5);
    var page3 = as("channel.operation.read", () -> service.rateRules(null, null, 2, 2));
    assertThat(page3.items()).hasSize(1);
    assertThat(page3.total()).isEqualTo(5);
    // 筛选真的生效
    var filtered = as("channel.operation.read", () -> service.rateRules("US-CA", null, 0, 20));
    assertThat(filtered.items()).hasSize(1);
    assertThat(filtered.total()).isEqualTo(1);
  }

  /** 第 i 个州码（真实存在于 16.3.1.1 的辖区码表里）。 */
  static String stateFor(int i) {
    List<String> codes = List.of("US-CA", "US-NV", "US-WA", "US-NY", "US-TX");
    return codes.get(i);
  }

  // ==================================================================
  // 六、时间字段与 HTTP 层
  // ==================================================================

  @Test
  @DisplayName("16.3.2.3 timestamptz：写回的值必须原样（不是只断言没抛异常）")
  void timestamptzRoundTrips() {
    SalesTaxFacilitatorImportResult result =
        importCsv("amz-2026-02", "state_code,tax_collected,currency\nUS-CA,700,USD\n");
    String parsedAt =
        scalarAsTenant(
            tenant,
            "select extract(epoch from parsed_at)::bigint from sales_tax_facilitator_report"
                + " where id = '"
                + result.report().id()
                + "'");
    assertThat(parsedAt).isNotNull();
    long stored = Long.parseLong(parsedAt);
    // 与 DB 的 now() 相差在 5 分钟内（不是 −28800s 那种静默偏移）
    assertThat(Math.abs(stored - java.time.Instant.now().getEpochSecond()))
        .as("parsed_at 必须是真正的时刻，不是偏了 8 小时的本地时间")
        .isLessThan(300L);
  }

  @Test
  @DisplayName("16.3.2.3 HTTP 层：端点真的返回数据，伪造 tenant 头无效")
  void httpEndpointsWork() throws Exception {
    UUID shop = seedShop("US-SHOP-A");
    shopState(shop, "US-CA", true);
    rule("US-CA", "2026-01-01", "0.072500", "0", "0");

    http.perform(get("/channel/sales-tax/capabilities").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.gaps[?(@.moduleId=='12.6')].available").value(false));

    http.perform(get("/channel/sales-tax/states").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].stateCode").value("US-CA"))
        .andExpect(jsonPath("$.total").value(1));

    http.perform(get("/channel/sales-tax/shops").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].code").value("US-SHOP-A"));

    // 无会话一律 401（伪造头救不了）
    http.perform(get("/channel/sales-tax/states").header("X-Tenant-Id", tenant.toString()))
        .andExpect(status().isUnauthorized());

    // 能力声明里说 12.6 未交付 ⇒ 不许有申报提交端点
    http.perform(
            post("/channel/sales-tax/filings")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isNotFound());
  }
}
