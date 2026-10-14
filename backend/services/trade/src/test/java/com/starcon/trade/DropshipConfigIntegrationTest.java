package com.starcon.trade;

import static com.starcon.trade.DropshipConfigModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 9.6.1.1 代发模式配置。
 *
 * <p>本测试是 <b>V118 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论， 逐条在真实 PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不只直连数据库，
 * 以便同时验证「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>四组断言</b>：
 *
 * <ol>
 *   <li><b>物流路径 ≠ 货权</b>（本模块唯一不可让步的一条）：四种「物流 × 货权」组合的 生成列逐格核对，并证明绕开服务层直接写 SQL 也改不了这个结论；
 *   <li><b>非纯代理必须单独确认</b>：服务层与 DB 两层各拒一次；改货权/结算维度后 既有确认被作废，重新激活仍被拒；
 *   <li><b>可售标记如实</b>：没有供应商库存观测是 <b>null</b> 而不是 0； 差价为负被拦住；可售与可刊登是两列；
 *   <li><b>多租户三断言</b>：两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class DropshipConfigIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("fulfillment")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "trade." + p + "." + op))
          .toList();

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-trade-dropship-test-" + tenant + "-" + ordinal).getBytes());
  }

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        idFor(tenant, 99),
        dept,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
      }
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            var access = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (access == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = json.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, null, List.of(grant("ALL", List.of("trade.fulfillment.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired DropshipConfigService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actorA;
  AccessService.Access actorB;

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "trade.fulfillment.update", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "trade.fulfillment.update", work);
  }

  <T> T as(UUID tenant, java.util.function.Supplier<T> work) {
    return tenant.equals(a) ? asA(work) : asB(work);
  }

  static OffsetDateTime at(int daysFromNow) {
    return OffsetDateTime.now(ZoneOffset.ofHours(8))
        .plusDays(daysFromNow)
        .withNano(0)
        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
  }

  /** 沿 cause 链找最里面那条 SQLException —— DB 拒绝的**原文**在那里。 */
  static java.sql.SQLException rootSqlException(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof java.sql.SQLException sql) {
        return sql;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    throw new AssertionError("异常链里没有 SQLException，说明不是 DB 拒绝：", error);
  }

  /**
   * 断言一段工作被 DB 拒绝。
   *
   * @param acceptableStates 可接受的 SQLState（23514 = 触发器/check 约束，42501 = 权限不足）
   * @param expectedFragment 拒绝理由里应当出现的片段；仅当走的是 23514 时断言
   */
  static void assertDbRefused(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable work,
      String expectedFragment,
      String... acceptableStates) {
    var error = catchThrowable(work);
    var e = rootSqlException(error);
    assertThat(e.getSQLState())
        .as("必须被 DB 拒绝，实际 SQLState=%s，消息=%s", e.getSQLState(), e.getMessage())
        .isIn(acceptableStates);
    if ("23514".equals(e.getSQLState())) {
      assertThat(e.getMessage()).as("拒绝理由必须可读").contains(expectedFragment);
    }
  }

  @FunctionalInterface
  interface OwnerSql {
    void run() throws Exception;
  }

  /** 以**表所有者**身份直连执行一条 SQL（FORCE RLS 对 owner 同样生效，仍需显式绑定租户）。 */
  static OwnerSql asTableOwner(UUID tenant, String sqlText) {
    return () -> {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var st = c.createStatement()) {
        st.execute("select set_config('app.current_tenant','" + tenant + "',false)");
        st.execute(sqlText);
      }
    };
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================
  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate fulfillment_dropship_event,fulfillment_dropship_evaluation,"
              + " fulfillment_dropship_supplier_stock,fulfillment_dropship_config_line,"
              + " fulfillment_dropship_config,fulfillment_dropship_supplier_ref,"
              + " fulfillment_dropship_shop_ref cascade");
    }
    for (var tenant : List.of(a, b)) {
      seedTenant(tenant);
    }
  }

  private void seedTenant(UUID tenant) {
    as(
        tenant,
        () -> {
          sql.execute(
              "insert into fulfillment_dropship_shop_ref(id,tenant_id,shop_code,shop_name,"
                  + "platform_code,region_code,active,source_system,synced_at,created_at,updated_at)"
                  + " values (?,?,?,?,?,'US',true,'MODULE_2_3_1',?::timestamptz,"
                  + "?::timestamptz,?::timestamptz)",
              idFor(tenant, 1),
              tenant,
              "SHOP-US-1",
              "美国店",
              "AMAZON",
              at(-1),
              at(0),
              at(0));
          sql.execute(
              "insert into fulfillment_dropship_supplier_ref(id,tenant_id,supplier_code,"
                  + "supplier_name,currency_code,quote_valid_from,quote_valid_to,quote_source,"
                  + "lead_time_days,active,synced_at,created_at,updated_at)"
                  + " values (?,?,?,?,'USD',current_date,current_date+90,'MODULE_3_6_2',3.00,true,"
                  + "?::timestamptz,?::timestamptz,?::timestamptz)",
              idFor(tenant, 2),
              tenant,
              "SUP-ACME",
              "Acme 一件代发",
              at(-1),
              at(0),
              at(0));
          sql.execute(
              "insert into fulfillment_dropship_supplier_stock(id,tenant_id,supplier_code,"
                  + "sku_ref,available_qty,stock_source,observed_at,created_at,updated_at)"
                  + " values (?,?,?,'SKU-OK',?,'MODULE_3_6_2',?::timestamptz,?::timestamptz,"
                  + "?::timestamptz)",
              idFor(tenant, 3),
              tenant,
              "SUP-ACME",
              new BigDecimal("500.000000"),
              at(0),
              at(0),
              at(0));
          return null;
        });
  }

  /** 一条纯代理代发配置（SUPPLIER_DIRECT + PURE_AGENCY）+ 两个 SKU 明细行，并激活。 */
  UUID activePureAgencyConfig(UUID tenant) {
    return as(
        tenant,
        () -> {
          var saved =
              service.saveConfig(
                  pureAgencyInput(tenant, "DSH-PURE-1", "SHOP-US-1", "100.0000", "150.0000"),
                  idFor(tenant, 99));
          service.saveConfigLine(saved.id(), lineInput("SKU-OK", null, null));
          service.saveConfigLine(saved.id(), lineInput("SKU-EMPTY", null, null));
          service.advanceStatus(
              saved.id(), new DropshipConfigStatusInput("ACTIVE", "激活"), idFor(tenant, 99));
          return saved.id();
        });
  }

  DropshipConfigInput pureAgencyInput(
      UUID tenant, String code, String shop, String cost, String sell) {
    return new DropshipConfigInput(
        code,
        "纯代理代发",
        "SHOP",
        shop,
        "",
        "SUPPLIER_DIRECT",
        "SUP-ACME",
        "PURE_AGENCY",
        null,
        null,
        null,
        "",
        "ON_RECEIPT",
        "MARGIN_SHARE",
        new BigDecimal("0.500000"),
        "USD",
        new BigDecimal(cost),
        new BigDecimal(sell),
        "SUPPLIER_STOCK",
        new BigDecimal("5.000000"),
        "PAUSE_WHEN_UNSELLABLE",
        10,
        null,
        null,
        "");
  }

  DropshipConfigLineInput lineInput(String sku, String costOverride, String sellOverride) {
    return new DropshipConfigLineInput(
        sku,
        sku,
        costOverride == null ? null : new BigDecimal(costOverride),
        sellOverride == null ? null : new BigDecimal(sellOverride),
        true,
        "");
  }

  // ==========================================================================
  // 迁移落地与 RLS 强制
  // ==========================================================================
  @Test
  void 迁移落地且七张表全部强制rls() {
    for (var table :
        List.of(
            "fulfillment_dropship_shop_ref",
            "fulfillment_dropship_supplier_ref",
            "fulfillment_dropship_config",
            "fulfillment_dropship_config_line",
            "fulfillment_dropship_supplier_stock",
            "fulfillment_dropship_evaluation",
            "fulfillment_dropship_event")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity,relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V118 必须建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住运行角色被误授 owner").isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('trade_app',?,'SELECT')", table)
                  .get(0, Boolean.class))
          .as(table + "：trade_app 必须真的能读")
          .isTrue();
    }
  }

  // ==========================================================================
  // 组 1：物流路径 ≠ 货权（本模块唯一不可让步的一条）
  // ==========================================================================

  @Test
  void 纯代理直发是唯一不占用自有库存的组合() {
    var pure =
        asA(
            () ->
                service.saveConfig(
                    pureAgencyInput(a, "DSH-PURE", "SHOP-US-1", "100.0000", "150.0000"),
                    idFor(a, 99)));
    var pureRow =
        asA(
            () ->
                sql.fetchOne(
                    "select counts_against_own_inventory,pure_agency_exempt"
                        + " from fulfillment_dropship_config where id = ?",
                    pure.id()));
    assertThat(pureRow.get("pure_agency_exempt", Boolean.class))
        .as("供应商直发 + 纯代理 = 不记我方库存")
        .isTrue();
    assertThat(pureRow.get("counts_against_own_inventory", Boolean.class)).isFalse();

    // 关键那一格：供应商直发**但货权在我方**（VMI 型）—— 仍然占用我方库存。
    var vmi =
        asA(
            () ->
                service.saveConfig(
                    ourAccountInput("DSH-VMI", "100.0000", "150.0000"), idFor(a, 99)));
    var vmiRow =
        asA(
            () ->
                sql.fetchOne(
                    "select counts_against_own_inventory,pure_agency_exempt"
                        + " from fulfillment_dropship_config where id = ?",
                    vmi.id()));
    assertThat(vmiRow.get("pure_agency_exempt", Boolean.class))
        .as("供应商直发 + 我方货权：不适用「不记我方库存」")
        .isFalse();
    assertThat(vmiRow.get("counts_against_own_inventory", Boolean.class))
        .as("不经我方仓不代表不承担货权 —— 这一格就是蓝图加粗句的落点")
        .isTrue();

    // 自营发货 + 我方货权：占用。
    var self = asA(() -> service.saveConfig(selfFulfilledInput(), idFor(a, 99)));
    var selfRow =
        asA(
            () ->
                sql.fetchOne(
                    "select counts_against_own_inventory,pure_agency_exempt"
                        + " from fulfillment_dropship_config where id = ?",
                    self.id()));
    assertThat(selfRow.get("counts_against_own_inventory", Boolean.class)).isTrue();
    assertThat(selfRow.get("pure_agency_exempt", Boolean.class)).isFalse();
  }

  @Test
  void 绕开服务层直接改库存口径会被数据库挡住() {
    var saved =
        asA(
            () ->
                service.saveConfig(
                    pureAgencyInput(a, "DSH-GUARD", "SHOP-US-1", "100.0000", "150.0000"),
                    idFor(a, 99)));
    // counts_against_own_inventory 是 GENERATED ALWAYS 列：应用层根本写不了它。
    // 428C9 = "column ... can only be updated to DEFAULT"，正是生成列的拒绝码。
    assertDbRefused(
        () ->
            asA(
                () ->
                    sql.execute(
                        "update fulfillment_dropship_config"
                            + " set counts_against_own_inventory = true where id = '"
                            + saved.id()
                            + "'")),
        "can only be updated to DEFAULT",
        "428C9");
  }

  @Test
  void 自营发货不得声明纯代理() {
    assertThatThrownBy(
            () -> asA(() -> service.saveConfig(selfFulfilledPureAgencyInput(), idFor(a, 99))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("自营发货不可能是「纯代理」");
  }

  // ==========================================================================
  // 组 2：非纯代理必须单独确认货权
  // ==========================================================================
  @Test
  void 我方货权缺确认人时服务层拒绝() {
    var input =
        new DropshipConfigInput(
            "DSH-NOCONF",
            "缺确认",
            "SHOP",
            "SHOP-US-1",
            "",
            "SUPPLIER_DIRECT",
            "SUP-ACME",
            "OUR_ACCOUNT",
            at(0),
            null,
            "确认过",
            "",
            "ON_ORDER",
            "FLAT_COST",
            null,
            "USD",
            new BigDecimal("100.0000"),
            new BigDecimal("150.0000"),
            "SKIP",
            null,
            "PAUSE_WHEN_UNSELLABLE",
            10,
            null,
            null,
            "");
    assertThatThrownBy(() -> asA(() -> service.saveConfig(input, idFor(a, 99))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("ownershipConfirmedByMembershipId");
  }

  @Test
  void 我方货权缺确认依据时服务层拒绝() {
    var input =
        new DropshipConfigInput(
            "DSH-NONOTE",
            "缺依据",
            "SHOP",
            "SHOP-US-1",
            "",
            "SUPPLIER_DIRECT",
            "SUP-ACME",
            "OUR_ACCOUNT",
            at(0),
            idFor(a, 99),
            "  ",
            "7.7",
            "ON_ORDER",
            "FLAT_COST",
            null,
            "USD",
            new BigDecimal("100.0000"),
            new BigDecimal("150.0000"),
            "SKIP",
            null,
            "PAUSE_WHEN_UNSELLABLE",
            10,
            null,
            null,
            "");
    assertThatThrownBy(() -> asA(() -> service.saveConfig(input, idFor(a, 99))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("7.7 货权规则联合评审");
  }

  @Test
  void 纯代理不得携带确认字段() {
    var input =
        new DropshipConfigInput(
            "DSH-BOGUS",
            "纯代理却带确认",
            "SHOP",
            "SHOP-US-1",
            "",
            "SUPPLIER_DIRECT",
            "SUP-ACME",
            "PURE_AGENCY",
            at(0),
            idFor(a, 99),
            "假装确认过",
            "",
            "ON_ORDER",
            "FLAT_COST",
            null,
            "USD",
            new BigDecimal("100.0000"),
            new BigDecimal("150.0000"),
            "SKIP",
            null,
            "PAUSE_WHEN_UNSELLABLE",
            10,
            null,
            null,
            "");
    assertThatThrownBy(() -> asA(() -> service.saveConfig(input, idFor(a, 99))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不需要「单独确认」");
  }

  @Test
  void 改货权或结算维度后既有确认被作废() {
    var saved =
        asA(
            () ->
                service.saveConfig(
                    ourAccountInput("DSH-EXPIRE", "100.0000", "150.0000"), idFor(a, 99)));
    // 原先有确认：确认时间非空。
    var before =
        asA(
            () ->
                sql.fetchOne(
                    "select ownership_confirmed_at from fulfillment_dropship_config where id = ?",
                    saved.id()));
    assertThat(before.get(0, OffsetDateTime.class)).as("初次保存应带上确认时间").isNotNull();

    // 改供货价（结算维度）→ 触发器作废确认；同时触发器拒绝未重新确认的写入。
    // 这里直接绕开服务层写 SQL，证明作废 + 拒绝在 DB 层，不靠应用层自觉。
    assertDbRefused(
        () ->
            asTableOwner(
                    a,
                    "update fulfillment_dropship_config set supplier_cost = 120.0000,"
                        + " ownership_confirmed_at = ownership_confirmed_at where id = '"
                        + saved.id()
                        + "'")
                .run(),
        "必须单独确认",
        "23514");
  }

  @Test
  void 已关闭配置的明细行被冻结() {
    var saved =
        asA(
            () ->
                service.saveConfig(
                    pureAgencyInput(a, "DSH-FREEZE", "SHOP-US-1", "100.0000", "150.0000"),
                    idFor(a, 99)));
    asA(() -> service.saveConfigLine(saved.id(), lineInput("SKU-OK", null, null)));
    asA(
        () ->
            service.advanceStatus(
                saved.id(), new DropshipConfigStatusInput("ACTIVE", ""), idFor(a, 99)));
    asA(
        () ->
            service.advanceStatus(
                saved.id(), new DropshipConfigStatusInput("CLOSED", "收尾"), idFor(a, 99)));
    assertThatThrownBy(
            () -> asA(() -> service.saveConfigLine(saved.id(), lineInput("SKU-LATE", null, null))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已冻结");
  }

  // ==========================================================================
  // 组 3：差价抽成与可售标记
  // ==========================================================================
  @Test
  void 差价抽成全程bigdecimal且生成列口径一致() {
    // 供货价 33.3333、抽成比例 0.333333 —— 浮点在这种组合下必然出偏差。
    var saved =
        asA(
            () ->
                service.saveConfig(
                    new DropshipConfigInput(
                        "DSH-MARGIN",
                        "差价抽成",
                        "SKU",
                        "",
                        "SKU-M",
                        "SUPPLIER_DIRECT",
                        "SUP-ACME",
                        "PURE_AGENCY",
                        null,
                        null,
                        null,
                        "",
                        "ON_ORDER",
                        "MARGIN_SHARE",
                        new BigDecimal("0.333333"),
                        "USD",
                        new BigDecimal("33.3333"),
                        new BigDecimal("99.9999"),
                        "SKIP",
                        null,
                        "PAUSE_WHEN_UNSELLABLE",
                        10,
                        null,
                        null,
                        ""),
                    idFor(a, 99)));
    var row =
        asA(
            () ->
                sql.fetchOne(
                    "select gross_spread,expected_margin_amount,expected_supplier_amount"
                        + " from fulfillment_dropship_config where id = ?",
                    saved.id()));
    BigDecimal spread = row.get("gross_spread", BigDecimal.class);
    BigDecimal margin = row.get("expected_margin_amount", BigDecimal.class);
    BigDecimal payable = row.get("expected_supplier_amount", BigDecimal.class);
    // 99.9999 − 33.3333 = 66.6666（全 BigDecimal，无二进制浮点误差）
    assertThat(spread).isEqualByComparingTo("66.6666");
    // 毛利 = 差价 × 留存比例 = 66.6666 × 0.333333
    assertThat(margin)
        .isEqualByComparingTo(
            new BigDecimal("66.6666")
                .multiply(new BigDecimal("0.333333"))
                .setScale(4, java.math.RoundingMode.HALF_UP));
    // 毛利 + 应付供应商 == 参考售价（两条金额口径自洽）
    assertThat(margin.add(payable).setScale(4, java.math.RoundingMode.HALF_UP))
        .as("毛利与应付供应商之和必须等于参考售价，否则对账会凭空差一笔")
        .isEqualByComparingTo("99.9999");
  }

  @Test
  void 固定供货价不得携带抽成比例() {
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.saveConfig(
                            new DropshipConfigInput(
                                "DSH-BADRATE",
                                "固定价却带抽成",
                                "SKU",
                                "",
                                "SKU-M",
                                "SUPPLIER_DIRECT",
                                "SUP-ACME",
                                "PURE_AGENCY",
                                null,
                                null,
                                null,
                                "",
                                "ON_ORDER",
                                "FLAT_COST",
                                new BigDecimal("0.2"),
                                "USD",
                                new BigDecimal("10.0000"),
                                new BigDecimal("20.0000"),
                                "SKIP",
                                null,
                                "ALWAYS_LIST",
                                10,
                                null,
                                null,
                                ""),
                            idFor(a, 99))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("marginShareRate 必须留空");
  }

  @Test
  void 没有供应商库存观测是可售缺口而不是零库存() {
    var configId = activePureAgencyConfig(a);
    var saved =
        asA(
            () ->
                service.evaluate(
                    new DropshipEvaluationRequest(configId, "SKU-EMPTY", new BigDecimal("10"))));
    assertThat(saved.sellable()).as("没有观测记录无法判定可售").isFalse();
    assertThat(saved.reasonCode()).isEqualTo("NO_SUPPLIER_STOCK_RECORD");
    var row =
        asA(
            () ->
                sql.fetchOne(
                    "select supplier_available_qty from fulfillment_dropship_evaluation where id = ?",
                    saved.id()));
    assertThat(row.get(0, BigDecimal.class)).as("「没人报过」必须是 null 而不是 0：0 会被读成「供应商确实没货」").isNull();
  }

  @Test
  void 供应商库存不足不可售且需求含安全余量() {
    var configId = activePureAgencyConfig(a);
    var saved =
        asA(
            () ->
                service.evaluate(
                    new DropshipEvaluationRequest(configId, "SKU-OK", new BigDecimal("600"))));
    assertThat(saved.sellable()).isFalse();
    assertThat(saved.reasonCode()).isEqualTo("SUPPLIER_STOCK_INSUFFICIENT");
    var row =
        asA(
            () ->
                sql.fetchOne(
                    "select required_qty,supplier_available_qty from fulfillment_dropship_evaluation"
                        + " where id = ?",
                    saved.id()));
    assertThat(row.get("required_qty", BigDecimal.class))
        .as("需求 = 请求 600 + 安全余量 5")
        .isEqualByComparingTo("605.000000");
    assertThat(row.get("supplier_available_qty", BigDecimal.class))
        .as("这次是真的问到了供应商，所以这里是 0 以外的确定值")
        .isEqualByComparingTo("500.000000");
  }

  @Test
  void 差价为负被拦住且可售与可刊登是两列() {
    // 供货价高于售价 → 差价转负
    var configId =
        asA(
            () -> {
              var saved =
                  service.saveConfig(
                      new DropshipConfigInput(
                          "DSH-LOSS",
                          "差价转负",
                          "SKU",
                          "",
                          "SKU-OK",
                          "SUPPLIER_DIRECT",
                          "SUP-ACME",
                          "PURE_AGENCY",
                          null,
                          null,
                          null,
                          "",
                          "ON_ORDER",
                          "FLAT_COST",
                          null,
                          "USD",
                          new BigDecimal("180.0000"),
                          new BigDecimal("150.0000"),
                          "SKIP",
                          null,
                          "ALWAYS_LIST",
                          10,
                          null,
                          null,
                          ""),
                      idFor(a, 99));
              service.saveConfigLine(saved.id(), lineInput("SKU-OK", null, null));
              service.advanceStatus(
                  saved.id(), new DropshipConfigStatusInput("ACTIVE", ""), idFor(a, 99));
              return saved.id();
            });
    var saved =
        asA(
            () ->
                service.evaluate(
                    new DropshipEvaluationRequest(configId, "SKU-OK", new BigDecimal("5"))));
    assertThat(saved.sellable()).as("差价为负不可售").isFalse();
    assertThat(saved.listable()).as("ALWAYS_LIST 策略下不可售但仍可刊登 —— 这正是两列分开的意义").isTrue();
    assertThat(saved.reasonCode()).isEqualTo("NEGATIVE_MARGIN");
  }

  @Test
  void 未激活的配置不参与可售判定() {
    var saved =
        asA(
            () ->
                service.saveConfig(
                    pureAgencyInput(a, "DSH-DRAFT", "SHOP-US-1", "100.0000", "150.0000"),
                    idFor(a, 99)));
    asA(() -> service.saveConfigLine(saved.id(), lineInput("SKU-OK", null, null)));
    var evaluated =
        asA(
            () ->
                service.evaluate(
                    new DropshipEvaluationRequest(saved.id(), "SKU-OK", new BigDecimal("5"))));
    assertThat(evaluated.sellable()).isFalse();
    assertThat(evaluated.reasonCode()).isEqualTo("CONFIG_NOT_ACTIVE");
  }

  @Test
  void 空配置不得激活() {
    var saved =
        asA(
            () ->
                service.saveConfig(
                    pureAgencyInput(a, "DSH-EMPTY", "SHOP-US-1", "100.0000", "150.0000"),
                    idFor(a, 99)));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.advanceStatus(
                            saved.id(), new DropshipConfigStatusInput("ACTIVE", ""), idFor(a, 99))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("SKU 明细行");
  }

  @Test
  void 可售标记不可改写或删除() {
    var configId = activePureAgencyConfig(a);
    var saved =
        asA(
            () ->
                service.evaluate(
                    new DropshipEvaluationRequest(configId, "SKU-OK", new BigDecimal("5"))));
    // 第 1 层：运行角色已被 REVOKE UPDATE/DELETE。
    assertDbRefused(
        () ->
            asA(
                () ->
                    sql.execute(
                        "update fulfillment_dropship_evaluation set sellable = false where id = '"
                            + saved.id()
                            + "'")),
        "",
        "42501");
    // 第 2 层：绕开权限以表所有者身份再撞一次 —— 证明触发器不是死代码。
    assertDbRefused(
        () ->
            asTableOwner(
                    a,
                    "delete from fulfillment_dropship_evaluation where id = '" + saved.id() + "'")
                .run(),
        "不可改写或删除",
        "23514");
  }

  @Test
  void 事件流只追加() {
    var configId = activePureAgencyConfig(a);
    assertDbRefused(
        () ->
            asTableOwner(
                    a,
                    "delete from fulfillment_dropship_event where config_id = '" + configId + "'")
                .run(),
        "只追加",
        "23514");
  }

  // ==========================================================================
  // 时间字段：OffsetDateTime 绑定，写回值必须断言（防 −28800s 静默偏移）
  // ==========================================================================
  @Test
  void timestamptz写入没有静默偏移() {
    OffsetDateTime sent = at(-7);
    var saved =
        asA(
            () ->
                service.saveSupplierStock(
                    new DropshipSupplierStockInput(
                        "SUP-ACME",
                        "SKU-TIME",
                        new BigDecimal("12.000000"),
                        "TENANT_MAINTAINED",
                        sent)));
    var row =
        asA(
            () ->
                sql.fetchOne(
                    "select observed_at from fulfillment_dropship_supplier_stock where id = ?",
                    saved.id()));
    OffsetDateTime read = row.get(0, OffsetDateTime.class);
    assertThat(read.toInstant())
        .as("写入的时点必须原样读回；偏 8 小时是 Timestamp.valueOf(LocalDateTime) 那个经典坑")
        .isEqualTo(sent.toInstant());
  }

  @Test
  void 有效时间写入后读回一致() {
    var saved =
        asA(
            () ->
                service.saveConfig(
                    new DropshipConfigInput(
                        "DSH-TIME",
                        "有效期",
                        "SKU",
                        "",
                        "SKU-OK",
                        "SUPPLIER_DIRECT",
                        "SUP-ACME",
                        "PURE_AGENCY",
                        null,
                        null,
                        null,
                        "",
                        "ON_RECEIPT",
                        "MARGIN_SHARE",
                        new BigDecimal("0.600000"),
                        "USD",
                        new BigDecimal("40.0000"),
                        new BigDecimal("100.0000"),
                        "SUPPLIER_STOCK",
                        new BigDecimal("2.000000"),
                        "PAUSE_WHEN_NEGATIVE_MARGIN",
                        5,
                        at(-3),
                        at(30),
                        ""),
                    idFor(a, 99)));
    var row =
        asA(
            () ->
                sql.fetchOne(
                    "select effective_from,effective_to from fulfillment_dropship_config where id = ?",
                    saved.id()));
    assertThat(row.get("effective_from", OffsetDateTime.class).toInstant())
        .isEqualTo(at(-3).toInstant());
    assertThat(row.get("effective_to", OffsetDateTime.class).toInstant())
        .isEqualTo(at(30).toInstant());
  }

  // ==========================================================================
  // 分页是真分页
  // ==========================================================================
  @Test
  void 分页是真分页() {
    for (int i = 1; i <= 7; i++) {
      var n = i;
      asA(
          () ->
              service.saveConfig(
                  pureAgencyInput(a, "DSH-P" + n, "SHOP-US-1", "10.0000", "20.0000"),
                  idFor(a, 99)));
    }
    var page =
        asA(
            () ->
                service.pageConfigs(
                    new DropshipConfigQuery(
                        null, null, null, null, null, null, null, null, null, null, null, 0, 3)));
    assertThat(page.items()).as("真分页：total 与分页无关").hasSize(3);
    assertThat(page.total()).isEqualTo(7);
    var second =
        asA(
            () ->
                service.pageConfigs(
                    new DropshipConfigQuery(
                        null, null, null, null, null, null, null, null, null, null, null, 1, 3)));
    assertThat(second.items()).hasSize(3);
    assertThat(second.items().get(0).configCode()).isNotEqualTo(page.items().get(0).configCode());
  }

  // ==========================================================================
  // 多租户：断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    activePureAgencyConfig(a);
    activePureAgencyConfig(b);
    var inA =
        asA(
            () ->
                service.pageConfigs(
                    new DropshipConfigQuery(
                        null, null, null, null, null, null, null, null, null, null, null, 0, 50)));
    var inB =
        asB(
            () ->
                service.pageConfigs(
                    new DropshipConfigQuery(
                        null, null, null, null, null, null, null, null, null, null, null, 0, 50)));
    assertThat(inA.total()).isEqualTo(1);
    assertThat(inB.total()).isEqualTo(1);
    // 按主键直查对方租户的行也拿不到（不只是列表过滤）。
    var anyB = inB.items().get(0);
    assertThatThrownBy(() -> asA(() -> service.configDetail(anyB.id())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或不属于当前租户");
  }

  // ==========================================================================
  // 多租户：断言 2
  // ==========================================================================
  @Test
  void 断言2_伪造租户头无效() throws Exception {
    var configId = activePureAgencyConfig(a);

    // (1) B 的会话 + 伪造指向 A 的头 → 详情查不到 A 的配置。
    http.perform(
            get("/trade/fulfillment/dropship/configs/{id}", configId)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());

    // (2) 写入路径同样不认头：B 会话伪造 A 的头，配置仍落进 B。
    http.perform(
            post("/trade/fulfillment/dropship/configs")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"configCode":"DSH-FAKE","configName":"伪造头写入","scopeKind":"SKU",
                     "skuRef":"SKU-OK","fulfillmentPath":"SUPPLIER_DIRECT","supplierCode":"SUP-ACME",
                     "ownershipMode":"PURE_AGENCY","settlementTiming":"ON_ORDER",
                     "settlementBasis":"FLAT_COST","currencyCode":"USD","supplierCost":10.0000,
                     "referenceSellPrice":20.0000,"availabilityCheckMode":"SKIP",
                     "listingPolicy":"ALWAYS_LIST"}"""))
        .andExpect(status().isOk());

    var inA =
        asA(
            () ->
                service.pageConfigs(
                    new DropshipConfigQuery(
                        "DSH-FAKE",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        10)));
    var inB =
        asB(
            () ->
                service.pageConfigs(
                    new DropshipConfigQuery(
                        "DSH-FAKE",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        10)));
    assertThat(inA.total()).as("伪造头不得把数据写进别的租户").isZero();
    assertThat(inB.total()).isEqualTo(1);

    // (3) 提交后租户上下文必须已清空：事务外读不到任何行。
    assertThat(sql.fetch("select id from fulfillment_dropship_config").size()).isZero();
  }

  // ==========================================================================
  // 多租户：断言 3
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    activePureAgencyConfig(a);
    activePureAgencyConfig(b);
    // 池大小为 1：下面每次读写都复用同一条连接。
    for (int i = 0; i < 5; i++) {
      assertThat(codes(a, i)).as("第 %d 轮租户 A", i).containsExactly("DSH-PURE-1");
      assertThat(codes(b, i)).as("第 %d 轮租户 B", i).containsExactly("DSH-PURE-1");
      assertThat(sql.fetch("select id from fulfillment_dropship_config").size())
          .as("第 %d 轮事务外", i)
          .isZero();
    }
  }

  java.util.List<String> codes(UUID tenant, int round) {
    return as(
        tenant,
        () ->
            service
                .pageConfigs(
                    new DropshipConfigQuery(
                        null, null, null, null, null, null, null, null, null, null, null, 0, 50))
                .items()
                .stream()
                .map(DropshipConfigRow::configCode)
                .toList());
  }

  // ==========================================================================
  // 其余输入
  // ==========================================================================
  DropshipConfigInput ourAccountInput(String code, String cost, String sell) {
    return new DropshipConfigInput(
        code,
        "供应商直发但货权在我方",
        "SHOP",
        "SHOP-US-1",
        "",
        "SUPPLIER_DIRECT",
        "SUP-ACME",
        "OUR_ACCOUNT",
        at(0),
        idFor(a, 99),
        "与 7.7 货权规则联合评审确认：货权属我方",
        "7.7",
        "ON_ORDER",
        "FLAT_COST",
        null,
        "USD",
        new BigDecimal(cost),
        new BigDecimal(sell),
        "SKIP",
        null,
        "PAUSE_WHEN_UNSELLABLE",
        10,
        null,
        null,
        "");
  }

  DropshipConfigInput selfFulfilledInput() {
    return new DropshipConfigInput(
        "DSH-SELF",
        "自营发货",
        "SHOP",
        "SHOP-US-1",
        "",
        "SELF_FULFILLED",
        "",
        "OUR_ACCOUNT",
        at(0),
        idFor(a, 99),
        "自营发货，货权当然在我方",
        "7.7",
        "ON_ORDER",
        "FLAT_COST",
        null,
        "USD",
        new BigDecimal("100.0000"),
        new BigDecimal("150.0000"),
        "SKIP",
        null,
        "PAUSE_WHEN_UNSELLABLE",
        10,
        null,
        null,
        "");
  }

  DropshipConfigInput selfFulfilledPureAgencyInput() {
    return new DropshipConfigInput(
        "DSH-BADSELF",
        "自营却纯代理",
        "SHOP",
        "SHOP-US-1",
        "",
        "SELF_FULFILLED",
        "",
        "PURE_AGENCY",
        null,
        null,
        null,
        "",
        "ON_ORDER",
        "FLAT_COST",
        null,
        "USD",
        new BigDecimal("100.0000"),
        new BigDecimal("150.0000"),
        "SKIP",
        null,
        "ALWAYS_LIST",
        10,
        null,
        null,
        "");
  }
}
