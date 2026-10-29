package com.starcon.channel;

import static com.starcon.channel.StockForecastModels.*;
import static com.starcon.channel.WarehouseConnectionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
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
 * 10.4.2.2 备货预报。
 *
 * <p>本测试类钉三件事：
 *
 * <ol>
 *   <li><b>蓝图加粗句「平台仓备货有严格规格」</b>：规格是结构化数据，提交前的规格闸
 *       <b>真的拦得住</b>违规预报 —— 逐条点名违反哪一条，而不是「提交成功然后被仓端拒收」。
 *       另外钉「规格版本冻结在预报单上」「仓端接收/拒收回执都能落库且回执不可改」。
 *   <li><b>货件任务不做假 HTTP</b>：平台侧没登记货件编号时任务 FAILED 且可重试，
 *       登记后推进成功并回填货件编号 —— 绝不凭空造一个编号。
 *   <li><b>状态机在 DB 层钉死</b>：绕过服务层直接写 SQL 也跳不过非法状态转换与明细冻结。
 * </ol>
 *
 * <p>多租户三断言由 {@link StockForecastTenancyIntegrationTest} 负责。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StockForecastIntegrationTest {
  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  /** 复用 identity 已注册的 channel 权限码：本模块不新增权限码，identity 服务零改动。 */
  static final List<String> allPermissions =
      java.util.stream.Stream.of("registry", "connection", "shop", "sync", "operation")
          .flatMap(
              p ->
                  java.util.stream.Stream.concat(
                      java.util.stream.Stream.of("read", "create", "update", "delete")
                          .map(op -> "channel." + p + "." + op),
                      p.equals("sync")
                          ? java.util.stream.Stream.of(
                              "channel.sync.trigger", "channel.sync.complete")
                          : java.util.stream.Stream.empty()))
          .distinct()
          .toList();

  static AccessService.Access actor(UUID uid, UUID t, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        t,
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
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
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

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 上游 warehouse 不可达：可用性口径必须报「读不到」，不得用 0 冒充
    r.add("WAREHOUSE_URL", () -> "http://127.0.0.1:1");
  }

  @Autowired StockForecastService forecasts;
  @Autowired WarehouseConnectionService warehouses;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  UUID tenant;
  AccessService.Access actorA;
  String bearer;

  @BeforeEach
  void setup() {
    // 每个测试一个独立租户：列表/汇总断言不能被其它测试留下的数据污染
    tenant = UUID.randomUUID();
    bearer = "Bearer forecast-" + tenant;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearer, actor(user, tenant, grants));
    actorA = actors.get(bearer);
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  <T> T as(String permission, java.util.function.Supplier<T> work) {
    return access.run(actorA, permission, work);
  }

  // ==================================================================
  // 夹具
  // ==================================================================

  /** 目的仓：平台仓（PLATFORM_FBA），走 10.4.2.1 的连接与仓账户。 */
  UUID destinationAccount() {
    UUID providerId =
        as(
            "channel.connection.read",
            () ->
                warehouses.providers().stream()
                    .filter(p -> p.code().equals("PLATFORM_FBA"))
                    .findFirst()
                    .orElseThrow()
                    .id());
    UUID connectionId =
        as(
            "channel.connection.create",
            () ->
                warehouses.createConnection(
                    new WarehouseConnectionInput(
                        providerId,
                        "vault://platform-warehouse/" + UUID.randomUUID(),
                        "acct-" + UUID.randomUUID().toString().substring(0, 8),
                        "FBA US Hub",
                        "platform-shipment-report")));
    return as(
        "channel.connection.create",
        () ->
            warehouses.createAccount(
                connectionId,
                new WarehouseAccountInput(
                    "WH-" + UUID.randomUUID().toString().substring(0, 8),
                    "FBA US",
                    "US",
                    "America/New_York",
                    WarehouseAccountKind.PLATFORM_FBA)));
  }

  /** 一版严格规格：箱型白名单 + 单箱件数上限 + 贴标必填 + 托盘必填 + 禁止混装。 */
  UUID strictSpec(UUID accountId) {
    return as(
        "channel.operation.create",
        () ->
            forecasts.createSpec(
                new CreateForecastSpecRequest(
                    accountId,
                    List.of("CARTON_STANDARD", "CARTON_LARGE"),
                    10,
                    new BigDecimal("25.000000"),
                    1,
                    true,
                    true,
                    List.of("FNSKU"),
                    true,
                    20,
                    new BigDecimal("800.000000"),
                    false,
                    3,
                    "严格规格：箱型白名单 + 贴标 + 托盘 + 禁止混装")));
  }

  /** 一版宽松规格：只要求箱数，便于测「合规预报能一路走到仓端回执」。 */
  UUID looseSpec(UUID accountId) {
    return as(
        "channel.operation.create",
        () ->
            forecasts.createSpec(
                new CreateForecastSpecRequest(
                    accountId,
                    List.of("CARTON_STANDARD"),
                    100,
                    null,
                    null,
                    false,
                    false,
                    List.of(),
                    false,
                    null,
                    null,
                    true,
                    0,
                    "宽松规格")));
  }

  OffsetDateTime arrival() {
    // 规格要求 3 天提前期，夹具给一个远期时间（避开 lead time 判定的不确定性）
    return OffsetDateTime.parse("2026-12-20T09:30:00+08:00");
  }

  UUID createForecast(UUID accountId, List<ForecastLineInput> lines) {
    return as(
        "channel.operation.create",
        () ->
            forecasts.createForecast(
                new CreateForecastRequest(accountId, "MANUAL", arrival(), "夹具预报单", lines)));
  }

  static ForecastLineInput line(
      String sku, String qty, String boxType, Integer cartons, Integer pallets, String label) {
    return new ForecastLineInput(
        sku,
        new BigDecimal(qty),
        "PCS",
        boxType,
        cartons,
        pallets,
        label,
        "CARTON-" + sku,
        new BigDecimal("1.500000"),
        "夹具明细");
  }

  // ==================================================================
  // 蓝图加粗句：规格是结构化数据 + 提交前的硬闸
  // ==================================================================

  @Test
  @DisplayName("10.4.2.2 规格不过 ⇒ 422 明确拒绝并逐条点名违反哪一条（不是提交成功再被拒收）")
  void specGateRejectsNonCompliantForecast() {
    UUID account = destinationAccount();
    strictSpec(account);

    // 三行合起来踩 7 条：箱型不在白名单、单箱件数超限、单箱重量超限、缺贴标、
    // 缺托盘、托盘重量超限、以及两个 SKU 共用同一箱型触发的混装限制。
    UUID forecast =
        createForecast(
            account,
            List.of(
                line("SKU-BAD", "100", "CARTON_TINY", 1, 1, "FNSKU"),
                line("SKU-HEAVY", "1000", "CARTON_STANDARD", 1, null, ""),
                line("SKU-MIX", "100", "CARTON_STANDARD", 2, 1, "FNSKU")));

    var thrown =
        catchThrowable(
            () -> as("channel.operation.update", () -> forecasts.validate(forecast)));
    assertThat(thrown).isInstanceOf(StockForecastService.SpecValidationException.class);

    SpecCheckResult result =
        as("channel.operation.read", () -> forecasts.specCheck(forecast));
    assertThat(result.passed()).isFalse();
    List<String> rules =
        result.violations().stream().map(SpecViolation::rule).distinct().toList();
    assertThat(rules)
        .contains(
            SpecRule.BOX_TYPE_NOT_ALLOWED.name(),
            SpecRule.MAX_UNITS_PER_CARTON_EXCEEDED.name(),
            SpecRule.MAX_WEIGHT_PER_CARTON_EXCEEDED.name(),
            SpecRule.LABEL_FORMAT_MISSING.name(),
            SpecRule.PALLET_REQUIRED_MISSING.name(),
            SpecRule.PALLET_WEIGHT_EXCEEDED.name(),
            SpecRule.MIXED_LOADING_FORBIDDEN.name());
    // 每条违规都要说清「期望什么 / 实际什么」，不能只有一句「规格不符」
    assertThat(result.violations())
        .allSatisfy(
            v -> {
              assertThat(v.ruleLabel()).isNotBlank();
              assertThat(v.message()).isNotBlank();
            });
    assertThat(result.violations())
        .filteredOn(v -> SpecRule.BOX_TYPE_NOT_ALLOWED.name().equals(v.rule()))
        .singleElement()
        .satisfies(
            v -> {
              assertThat(v.expected()).contains("CARTON_STANDARD");
              assertThat(v.actual()).isEqualTo("CARTON_TINY");
            });

    // 没过闸就不能提交
    assertThat(catchThrowable(() -> as("channel.operation.update", () -> forecasts.submit(forecast))))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    var status = as("channel.operation.read", () -> forecasts.forecast(forecast)).forecast().status();
    assertThat(status).isEqualTo("DRAFT");
  }

  @Test
  @DisplayName("10.4.2.2 HTTP 层同样 422 且响应体带 violations 数组（前端据此逐条提示）")
  void httpSpecGateReturnsStructuredViolations() throws Exception {
    UUID account = destinationAccount();
    strictSpec(account);
    UUID forecast =
        createForecast(
            account, List.of(line("SKU-HTTP", "999", "CARTON_TINY", 1, null, "")));

    http.perform(post("/channel/stock-forecasts/{id}/validate", forecast)
            .header("Authorization", bearer)
            .contentType("application/json"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.violations").isArray())
        .andExpect(jsonPath("$.violations[0].rule").exists())
        .andExpect(jsonPath("$.violations[0].ruleLabel").exists())
        .andExpect(jsonPath("$.error").value("SPEC_VALIDATION_FAILED"));
  }

  @Test
  @DisplayName("10.4.2.2 合规预报过闸 → 提交 → 货件任务生成")
  void compliantForecastPassesAndSubmits() {
    UUID account = destinationAccount();
    looseSpec(account);
    UUID forecast =
        createForecast(
            account, List.of(line("SKU-OK", "50", "CARTON_STANDARD", 5, null, "FNSKU")));

    SpecCheckResult check = as("channel.operation.update", () -> forecasts.validate(forecast));
    assertThat(check.passed()).isTrue();
    assertThat(check.violations()).isEmpty();

    UUID taskId = as("channel.operation.update", () -> forecasts.submit(forecast));
    assertThat(taskId).isNotNull();

    var detail = as("channel.operation.read", () -> forecasts.forecast(forecast));
    assertThat(detail.forecast().status()).isEqualTo("SUBMITTED");
    assertThat(detail.shipmentTask()).isNotNull();
    assertThat(detail.shipmentTask().status()).isEqualTo("PENDING");
    assertThat(detail.forecast().submittedAt()).isNotNull();
  }

  @Test
  @DisplayName("10.4.2.2 规格版本冻结在预报单上：换规格不影响已建单")
  void specVersionIsFrozenOnForecast() {
    UUID account = destinationAccount();
    UUID v1 = strictSpec(account);
    UUID forecast =
        createForecast(
            account, List.of(line("SKU-FROZEN", "500", "CARTON_TINY", 1, null, "")));

    // 换成宽松规格后，已建的预报单仍然按第 1 版（严格版）判定
    UUID v2 = looseSpec(account);
    assertThat(v2).isNotEqualTo(v1);

    var detail = as("channel.operation.read", () -> forecasts.forecast(forecast));
    assertThat(detail.spec().id()).isEqualTo(v1);
    assertThat(detail.spec().versionNo()).isEqualTo(1);
    assertThat(detail.spec().mixedLoadingAllowed()).isFalse();

    SpecCheckResult check = as("channel.operation.read", () -> forecasts.specCheck(forecast));
    assertThat(check.passed()).isFalse();
    assertThat(check.specVersion()).isEqualTo(1);
  }

  @Test
  @DisplayName("10.4.2.2 没有生效规格时建单直接说清缺什么（不留一张注定被拒的单）")
  void forecastRequiresActiveSpec() {
    UUID account = destinationAccount();
    var thrown =
        catchThrowable(
            () ->
                createForecast(
                    account, List.of(line("SKU-NOSPEC", "10", "CARTON_STANDARD", 1, null, ""))));
    assertThat(thrown).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(thrown.getMessage()).contains("备货规格");
  }

  // ==================================================================
  // 货件任务：不做假 HTTP，编号靠登记带回
  // ==================================================================

  @Test
  @DisplayName("10.4.2.2 货件任务：平台侧没登记 ⇒ FAILED 可重试；登记后推进成功并回填编号")
  void shipmentTaskFailsThenSucceedsAfterRegistration() {
    UUID account = destinationAccount();
    looseSpec(account);
    UUID forecast =
        createForecast(
            account, List.of(line("SKU-SHIP", "50", "CARTON_STANDARD", 5, null, "FNSKU")));
    as("channel.operation.update", () -> forecasts.validate(forecast));
    as("channel.operation.update", () -> forecasts.submit(forecast));

    // 平台侧没登记货件编号 ⇒ 任务 FAILED，绝不凭空造一个编号。
    // 注意：失败是**如实返回**而不是抛异常 —— 抛异常会让租户事务回滚，
    // 把刚写下的 FAILED 一起回滚掉，现场就变成「什么都没发生过」。
    var failed = as("channel.operation.update", () -> forecasts.dispatchTask(forecast, bearer));
    assertThat(failed.status()).isEqualTo("FAILED");
    assertThat(failed.platformShipmentId()).isEmpty();
    assertThat(failed.lastError()).contains("尚未登记");
    // 预报单停在 SUBMITTED：货件没建出来就不该说建出来了
    assertThat(as("channel.operation.read", () -> forecasts.forecast(forecast)).forecast().status())
        .isEqualTo("SUBMITTED");

    // 重试 → PENDING
    var retried = as("channel.operation.update", () -> forecasts.retryTask(forecast));
    assertThat(retried.status()).isEqualTo("PENDING");
    assertThat(retried.attempts()).isEqualTo(1);

    // 登记平台货件报送行（真实外部边界：平台回调或人工登记）
    OffsetDateTime reportedAt = OffsetDateTime.parse("2026-10-18T02:15:00Z");
    as(
        "channel.operation.update",
        () ->
            forecasts.registerShipmentReport(
                forecast,
                new CreateShipmentReportRequest(
                    "FBA-SHIP-0001", "PLATFORM_CALLBACK", reportedAt)));

    var done = as("channel.operation.update", () -> forecasts.dispatchTask(forecast, bearer));
    assertThat(done.status()).isEqualTo("SUCCEEDED");
    assertThat(done.platformShipmentId()).isEqualTo("FBA-SHIP-0001");
    assertThat(done.attempts()).isEqualTo(2);

    var detail = as("channel.operation.read", () -> forecasts.forecast(forecast));
    assertThat(detail.forecast().status()).isEqualTo("SHIPMENT_CREATED");
    assertThat(detail.forecast().platformShipmentId()).isEqualTo("FBA-SHIP-0001");
  }

  // ==================================================================
  // 仓端回执：接收与拒收都是真实业务结果
  // ==================================================================

  @Test
  @DisplayName("10.4.2.2 仓端接收与拒收两条回执都落库并可查，拒收必须写理由")
  void warehouseReceiptsAreRecorded() {
    UUID account = destinationAccount();
    looseSpec(account);

    // —— 接收 ——
    UUID accepted = submittedForecast(account, "SKU-ACCEPT");
    as(
        "channel.operation.update",
        () ->
            forecasts.registerReceipt(
                accepted,
                new CreateReceiptRequest(
                    "ACCEPTED", "RCV-1", "全部入库", "", OffsetDateTime.parse("2026-10-19T08:00:00Z"),
                    "WAREHOUSE_FEEDBACK")));
    var acceptedDetail = as("channel.operation.read", () -> forecasts.forecast(accepted));
    assertThat(acceptedDetail.forecast().status()).isEqualTo("ACCEPTED");
    assertThat(acceptedDetail.receipts()).hasSize(1);
    assertThat(acceptedDetail.receipts().get(0).receiptType()).isEqualTo("ACCEPTED");
    assertThat(acceptedDetail.receipts().get(0).receivedAt())
        .isEqualTo(OffsetDateTime.parse("2026-10-19T08:00:00Z"));

    // —— 拒收：没写理由 ⇒ 400 ——
    UUID rejected = submittedForecast(account, "SKU-REJECT");
    assertThat(
            catchThrowable(
                () ->
                    as(
                        "channel.operation.update",
                        () ->
                            forecasts.registerReceipt(
                                rejected,
                                new CreateReceiptRequest(
                                    "REJECTED",
                                    "RCV-2",
                                    "",
                                    "",
                                    OffsetDateTime.parse("2026-10-19T09:00:00Z"),
                                    "WAREHOUSE_FEEDBACK")))))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

    as(
        "channel.operation.update",
        () ->
            forecasts.registerReceipt(
                rejected,
                new CreateReceiptRequest(
                    "REJECTED",
                    "RCV-2",
                    "外箱尺寸不符",
                    "箱型不符合目的仓规格",
                    OffsetDateTime.parse("2026-10-19T09:00:00Z"),
                    "WAREHOUSE_FEEDBACK")));
    var rejectedDetail = as("channel.operation.read", () -> forecasts.forecast(rejected));
    assertThat(rejectedDetail.forecast().status()).isEqualTo("REJECTED");
    assertThat(rejectedDetail.receipts()).hasSize(1);
    assertThat(rejectedDetail.receipts().get(0).rejectedReason()).isEqualTo("箱型不符合目的仓规格");
  }

  @Test
  @DisplayName("10.4.2.2 仓端回执 append-only：应用账号与超级账号都改不动、删不掉")
  void receiptsAreAppendOnly() throws Exception {
    UUID account = destinationAccount();
    looseSpec(account);
    UUID forecast = submittedForecast(account, "SKU-APPEND");
    as(
        "channel.operation.update",
        () ->
            forecasts.registerReceipt(
                forecast,
                new CreateReceiptRequest(
                    "ACCEPTED",
                    "RCV-3",
                    "",
                    "",
                    OffsetDateTime.parse("2026-10-19T08:00:00Z"),
                    "WAREHOUSE_FEEDBACK")));

    // 应用账号：权限面先拒（只有 SELECT/INSERT）
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      for (String sql :
          List.of(
              "update warehouse_forecast_receipt set detail='改一下'",
              "delete from warehouse_forecast_receipt")) {
        Throwable e = catchThrowable(() -> s.execute(sql));
        assertThat(rootMessage(e)).containsAnyOf("permission denied", "no privileges");
      }
    }

    // 超级账号（等价于绕过服务层直连数据库）：触发器照样拒
    assertThat(
            catchThrowable(
                () ->
                    adminUpdate(
                        "update warehouse_forecast_receipt set detail='改一下'")))
        .isNotNull();
    assertThat(
            catchThrowable(() -> adminUpdate("delete from warehouse_forecast_receipt")))
        .isNotNull();

    // 回执还在，一条没少
    assertThat(as("channel.operation.read", () -> forecasts.receipts(forecast))).hasSize(1);
  }

  // ==================================================================
  // 状态机在 DB 层钉死
  // ==================================================================

  @Test
  @DisplayName("10.4.2.2 非法状态转换与明细冻结：绕过服务层写 SQL 同样被拒")
  void stateMachineIsEnforcedInDatabase() {
    UUID account = destinationAccount();
    looseSpec(account);
    UUID forecast =
        createForecast(
            account, List.of(line("SKU-STATE", "50", "CARTON_STANDARD", 5, null, "FNSKU")));

    // DRAFT → ACCEPTED 是非法跳转（必须先过闸、再提交、再建货件）
    Throwable illegal =
        catchThrowable(() -> adminUpdate("update warehouse_forecast set status='ACCEPTED' where id='" + forecast + "'"));
    assertThat(illegal).isNotNull();
    assertThat(rootMessage(illegal)).contains("状态不能从");

    // 过闸后离开草稿态，明细就冻结了
    as("channel.operation.update", () -> forecasts.validate(forecast));
    Throwable frozen =
        catchThrowable(
            () -> adminUpdate("update warehouse_forecast_line set quantity=1 where forecast_id='" + forecast + "'"));
    assertThat(frozen).isNotNull();
    assertThat(rootMessage(frozen)).contains("草稿态");

    // 服务层同样挡住：非草稿态不能换明细
    assertThat(
            catchThrowable(
                () ->
                    as(
                        "channel.operation.update",
                        () -> {
                          forecasts.replaceLines(
                              forecast,
                              new ReplaceForecastLinesRequest(
                                  List.of(
                                      line("SKU-STATE2", "1", "CARTON_STANDARD", 1, null, "FNSKU"))));
                          return null;
                        })))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  // ==================================================================
  // 列表：真分页 + 真筛选 + 真排序
  // ==================================================================

  @Test
  @DisplayName("10.4.2.2 预报单列表是真分页 + 真筛选 + 真排序")
  void listIsReallyPagedFilteredAndSorted() {
    UUID account = destinationAccount();
    looseSpec(account);
    UUID f1 = createForecast(account, List.of(line("SKU-L1", "10", "CARTON_STANDARD", 1, null, "FNSKU")));
    UUID f2 = createForecast(account, List.of(line("SKU-L2", "20", "CARTON_STANDARD", 2, null, "FNSKU")));
    UUID f3 = createForecast(account, List.of(line("SKU-L3", "30", "CARTON_STANDARD", 3, null, "FNSKU")));
    UUID f4 = createForecast(account, List.of(line("SKU-L4", "40", "CARTON_STANDARD", 4, null, "FNSKU")));
    // f4 走完校验并提交，用来验证「按状态筛选」是真的在筛
    as("channel.operation.update", () -> forecasts.validate(f4));
    as("channel.operation.update", () -> forecasts.submit(f4));

    var page0 = as("channel.operation.read", () -> forecasts.forecasts(null, null, null, null, 0, 2));
    assertThat(page0.rows()).hasSize(2);
    assertThat(page0.total()).isEqualTo(4);
    assertThat(page0.pageCount()).isEqualTo(2);
    assertThat(page0.page()).isZero();

    var page1 = as("channel.operation.read", () -> forecasts.forecasts(null, null, null, null, 1, 2));
    assertThat(page1.rows()).hasSize(2);
    assertThat(page1.rows().get(0).id()).isNotEqualTo(page0.rows().get(0).id());

    // 真筛选：按 SKU 关键字
    var bySku = as("channel.operation.read", () -> forecasts.forecasts(null, null, "SKU-L2", null, 0, 50));
    assertThat(bySku.rows()).hasSize(1);
    assertThat(bySku.rows().get(0).id()).isEqualTo(f2);

    // 真筛选：按状态（草稿 3 张、已提交 1 张）
    var byStatus = as("channel.operation.read", () -> forecasts.forecasts("DRAFT", null, null, null, 0, 50));
    assertThat(byStatus.rows()).hasSize(3);
    var submitted =
        as("channel.operation.read", () -> forecasts.forecasts("SUBMITTED", null, null, null, 0, 50));
    assertThat(submitted.rows()).hasSize(1);
    assertThat(submitted.rows().get(0).id()).isEqualTo(f4);
    var byOtherStatus =
        as("channel.operation.read", () -> forecasts.forecasts("ACCEPTED", null, null, null, 0, 50));
    assertThat(byOtherStatus.rows()).isEmpty();

    // 真排序：单号升序
    var sorted = as("channel.operation.read", () -> forecasts.forecasts(null, null, null, "FORECAST_NO_ASC", 0, 50));
    assertThat(sorted.rows().stream().map(ForecastRow::forecastNo).toList())
        .isSorted();
    assertThat(sorted.rows().get(0).id()).isEqualTo(f1);
  }

  // ==================================================================
  // 时间字段：timestamptz 写回值必须断言（不能只断言没抛异常）
  // ==================================================================

  @Test
  @DisplayName("10.4.2.2 期望到货时间与回报时刻按 OffsetDateTime 原样写回（timestamptz 不偏 8 小时）")
  void timestampsRoundTripWithoutShift() {
    UUID account = destinationAccount();
    looseSpec(account);
    OffsetDateTime expected = OffsetDateTime.parse("2026-12-20T09:30:00+08:00");
    UUID forecast =
        as(
            "channel.operation.create",
            () ->
                forecasts.createForecast(
                    new CreateForecastRequest(
                        account,
                        "MANUAL",
                        expected,
                        "时刻夹具",
                        List.of(line("SKU-TIME", "10", "CARTON_STANDARD", 1, null, "FNSKU")))));

    var row = as("channel.operation.read", () -> forecasts.forecast(forecast));
    assertThat(row.forecast().expectedArrivalAt()).isNotNull();
    // 同一瞬间：不因时区写法不同而偏 8 小时
    assertThat(row.forecast().expectedArrivalAt().toInstant())
        .isEqualTo(expected.toInstant());

    // 回报时刻同样断言写回值
    OffsetDateTime received = OffsetDateTime.parse("2026-10-19T08:00:00Z");
    as("channel.operation.update", () -> forecasts.validate(forecast));
    as("channel.operation.update", () -> forecasts.submit(forecast));
    // 登记平台货件报送行后推进任务，预报单才会进到 SHIPMENT_CREATED
    as(
        "channel.operation.update",
        () ->
            forecasts.registerShipmentReport(
                forecast,
                new CreateShipmentReportRequest(
                    "FBA-TIME", "MANUAL_REGISTRATION", OffsetDateTime.parse("2026-10-18T00:00:00Z"))));
    as("channel.operation.update", () -> forecasts.dispatchTask(forecast, bearer));
    as(
        "channel.operation.update",
        () ->
            forecasts.registerReceipt(
                forecast,
                new CreateReceiptRequest(
                    "ACCEPTED", "RCV-T", "", "", received, "WAREHOUSE_FEEDBACK")));
    var receipts = as("channel.operation.read", () -> forecasts.receipts(forecast));
    assertThat(receipts).hasSize(1);
    assertThat(receipts.get(0).receivedAt().toInstant()).isEqualTo(received.toInstant());
  }

  // ==================================================================
  // 只读 Port 不可达时：显式「不可用」，不用 0 冒充
  // ==================================================================

  @Test
  @DisplayName("10.4.2.2 上游库存/在途读不到 ⇒ 503 明确报不可用（绝不用 0 冒充）")
  void availabilityReportsUnavailableInsteadOfZero() {
    UUID account = destinationAccount();
    looseSpec(account);
    UUID forecast =
        createForecast(
            account, List.of(line("SKU-AVAIL", "10", "CARTON_STANDARD", 1, null, "FNSKU")));

    var thrown =
        catchThrowable(
            () -> as("channel.operation.read", () -> forecasts.availability(forecast, 0, 50, bearer)));
    assertThat(thrown).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(thrown.getMessage()).contains("读不到");
    assertThat(thrown.getMessage()).doesNotContain("= 0");
  }

  @Test
  @DisplayName("10.4.2.2 能力声明把未接通的维度如实标成 available=false")
  void capabilitiesDeclareUnavailableDimensions() {
    var caps = as("channel.operation.read", () -> forecasts.capabilities());
    assertThat(caps.dimensions()).isNotEmpty();
    assertThat(
            caps.dimensions().stream()
                .filter(d -> d.key().equals("platformShipmentApi"))
                .findFirst()
                .orElseThrow())
        .satisfies(
            d -> {
              assertThat(d.available()).isFalse();
              assertThat(d.ownerModule()).isEqualTo("10.4.2.2");
              assertThat(d.impact()).isNotBlank();
            });
    assertThat(
            caps.dimensions().stream()
                .filter(d -> d.key().equals("specGate"))
                .findFirst()
                .orElseThrow()
                .available())
        .isTrue();
  }

  // ==================================================================
  // 工具
  // ==================================================================

  /** 走到「货件已创建」的预报单（用于回执测试）。 */
  UUID submittedForecast(UUID account, String sku) {
    UUID forecast =
        createForecast(account, List.of(line(sku, "50", "CARTON_STANDARD", 5, null, "FNSKU")));
    as("channel.operation.update", () -> forecasts.validate(forecast));
    as("channel.operation.update", () -> forecasts.submit(forecast));
    as(
        "channel.operation.update",
        () ->
            forecasts.registerShipmentReport(
                forecast,
                new CreateShipmentReportRequest(
                    "FBA-" + sku, "MANUAL_REGISTRATION", OffsetDateTime.parse("2026-10-18T00:00:00Z"))));
    as("channel.operation.update", () -> forecasts.dispatchTask(forecast, bearer));
    return forecast;
  }

  /** 以迁移账号（超级权限）执行 DML —— 等价于「绕过服务层与权限面直连数据库」。 */
  private void adminUpdate(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute(statement);
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
  }

  private static String rootMessage(Throwable t) {
    Throwable cur = t;
    while (cur.getCause() != null) cur = cur.getCause();
    return cur.getMessage() == null ? "" : cur.getMessage();
  }
}
