package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.ReceivableModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 12.1.1.1 应收单管理的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖的是蓝图 12-finance.md 里那几条**加粗句**与「关键约束」：
 *
 * <ul>
 *   <li>「应收确认政策需单独配置」 ⇒ 没有启用的政策就没有任何应收能产生；
 *   <li>「未发货订单不默认等于收入」 ⇒ B2B 账期应收不能手工录入，只能由发货确认导入；
 *   <li>「汇率快照必须留在单据上」 ⇒ 汇率与本位币金额建单时定死，之后改不动；
 *   <li>「金额一律 BigDecimal + NUMERIC，禁止浮点」 ⇒ 金额是四位小数的 numeric，逐位相等；
 *   <li>「平台账单不强制每行都有订单」 ⇒ 明细行的订单号可空；
 *   <li>「平台结算未交付」⇒ 平台两个来源建单被拒，**不填 0、不造假结算**。
 * </ul>
 *
 * <p><b>断言风格与本仓其它模块一致</b>：业务规则与拒绝理由走**服务层** （{@code
 * assertThatThrownBy(...).hasStackTraceContaining(...)}）， HTTP 层只断言**状态码**（MockMvc 不走错误分派，响应体是空的 ——
 * 真实容器里 {@code server.error.include-message=always} 会把理由带回去）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReceivableIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final List<String> PERMISSIONS =
      List.of("finance.receivable.read", "finance.receivable.create", "finance.receivable.update");

  /**
   * 测试里的 JSON mapper。
   *
   * <p>⚠️ {@code USE_BIG_DECIMAL_FOR_FLOATS} 必须开：默认 Jackson 把 JSON 里的浮点数读成 {@code double}，于是 {@code
   * 20.0000} 变成 {@code 20.0} —— 断言就分不出 「金额是四位小数」和「金额丢了一位」。金额断言必须逐位相等，这是本仓的纪律。
   */
  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer tradeUpstream;

  /** 上游（trade）返回的账期应收：测试可以改它来模拟各种上游形状。 */
  static volatile String upstreamPayload = "{\"items\":[],\"page\":0,\"size\":200,\"total\":0}";

  /** 把上游打成故障：模拟「trade 读不到」。 */
  static volatile boolean upstreamDown = false;

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role finance_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    actors.put(
        "session",
        new AccessService.Access(
            UUID.randomUUID(),
            tenant,
            "Test",
            UUID.randomUUID(),
            null,
            PERMISSIONS,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", PERMISSIONS))));
    try {
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
      identity.setExecutor(Executors.newFixedThreadPool(2));
      identity.start();

      tradeUpstream = HttpServer.create(new InetSocketAddress(0), 0);
      tradeUpstream.createContext(
          "/trade/b2b-orders/receivables",
          e -> {
            if (upstreamDown) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            byte[] body = upstreamPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      tradeUpstream.setExecutor(Executors.newFixedThreadPool(2));
      tradeUpstream.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "finance_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + tradeUpstream.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired ReceivableService receivables;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  /** 清库。用迁移用户（超管）连接：RLS 对超管不生效。 */
  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_receivable_event, finance_collection_task,"
              + " finance_receivable_line, finance_receivable, finance_receivable_policy"
              + " restart identity cascade");
    }
    upstreamPayload = "{\"items\":[],\"page\":0,\"size\":200,\"total\":0}";
    upstreamDown = false;
  }

  /** 在**租户事务里**跑一段工作（与生产同一条路径：RLS 靠它隔离）。 */
  <T> T as(Supplier<T> work) {
    return tenants.run(tenant, work);
  }

  /** 在**租户事务里**读一行（事务外 RLS 会诚实地把所有行都挡掉）。 */
  Record one(String statement, Object... binds) {
    return as(() -> sql.fetchOne(statement, binds));
  }

  /** 在**租户事务里**计数。 */
  long count(String statement, Object... binds) {
    return as(() -> sql.fetchOne(statement, binds).get(0, Long.class));
  }

  /** 测试用的可信 actor。 */
  AccessService.Access actor() {
    return actors.get("session");
  }

  /**
   * 用迁移用户（超管）直连跑一条**预期失败**的 SQL，返回错误消息。
   *
   * <p>触发器断言必须这么跑：超管不受 RLS 干扰，报出来的错就是**触发器**的错， 而不是「RLS 挡住了这一行」这种把断言糊弄过去的错。
   */
  String dbError(String statement, Object... binds) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var ps = c.prepareStatement(statement)) {
      for (int i = 0; i < binds.length; i++) ps.setObject(i + 1, binds[i]);
      ps.execute();
      return "NO_ERROR";
    } catch (Exception e) {
      return e.getMessage();
    }
  }

  /** 配置一个来源的应收确认政策（走服务层）。 */
  void enablePolicy(String source, int dueDays) {
    as(
        () ->
            receivables.updatePolicy(
                ReceivableSource.valueOf(source),
                new FinanceReceivablePolicyInput(true, dueDays, "测试夹具"),
                actor()));
  }

  /** 人工录入一张应收单（默认两行明细，合计 20.0000）。 */
  FinanceReceivableRow createManual(String extraJson) {
    var input =
        inputOf(
            """
            {"counterpartyType":"CUSTOMER","counterpartyName":"客户甲","currency":"USD"%s,
             "lines":[{"description":"明细一","amount":12.5},{"description":"明细二","amount":7.5}]}
            """
                .formatted(extraJson == null ? "" : extraJson));
    return as(() -> receivables.create(input, actor()));
  }

  // ================================================================ 政策闸门

  @Test
  void 没有启用的确认政策就产生不了任何应收() {
    var input = manualInput(null);
    assertThatThrownBy(() -> as(() -> receivables.create(input, actor())))
        .as("必须说清是「政策没配」，而不是含糊的「参数错误」")
        .hasStackTraceContaining("应收确认政策");
  }

  @Test
  void 政策禁用时同样产生不了应收() {
    as(
        () ->
            receivables.updatePolicy(
                ReceivableSource.MANUAL,
                new FinanceReceivablePolicyInput(false, 30, null),
                actor()));
    var input = manualInput(null);
    assertThatThrownBy(() -> as(() -> receivables.create(input, actor())))
        .hasStackTraceContaining("禁用");
  }

  @Test
  void 政策列表把五个来源全列出来并区分未配置与已禁用() {
    var views = as(() -> receivables.policies());
    assertThat(views).hasSize(5);
    assertThat(views)
        .extracting(FinanceReceivablePolicyView::source)
        .containsExactlyInAnyOrder(
            ReceivableSource.PLATFORM_SETTLEMENT,
            ReceivableSource.PLATFORM_WITHHOLDING,
            ReceivableSource.B2B_CREDIT_TERM,
            ReceivableSource.MANUAL,
            ReceivableSource.OTHER);
    assertThat(views).allMatch(v -> !v.configured());
    // 平台两个来源当前不可产生 —— 必须写明归属模块 12.3.1.1。
    var platform =
        views.stream()
            .filter(v -> v.source() == ReceivableSource.PLATFORM_SETTLEMENT)
            .findFirst()
            .orElseThrow();
    assertThat(platform.producible()).isFalse();
    assertThat(platform.ownerModule()).isEqualTo("12.3.1.1");
    assertThat(platform.detail()).contains("未交付");
  }

  // ================================================================ 建单

  @Test
  void 建单后单头金额等于明细合计且状态是未核销() {
    enablePolicy("MANUAL", 30);
    var receivable = createManual(null);
    assertThat(receivable.amount()).isEqualByComparingTo("20.0000");
    assertThat(receivable.status()).isEqualTo(ReceivableStatus.OPEN);
    assertThat(receivable.settledAmount()).isEqualByComparingTo("0.0000");
    assertThat(receivable.source()).isEqualTo(ReceivableSource.MANUAL);
    // 到期日 = 确认日 + 政策账期 30 天。
    // ⚠️ 必须用 withOffsetSameInstant(UTC) 比对：服务端的到期日按 **UTC 日历日** 计算
    //    （ReceivableService 的 TODAY_UTC，刻意为之 —— 用会话时区会凭空多出空桶），
    //    而 confirmedAt() 从 timestamptz 读回来带的是 JVM 本地时区偏移；本地 00:00-08:00 窗口内两者差一天。
    assertThat(receivable.dueDate()).isEqualTo(utcCalendarDate(receivable.confirmedAt()).plusDays(30));
  }

  /**
   * 服务端口径的日历日：把确认时刻折算到 **UTC 日历日** 再取日期。
   *
   * <p>⚠️ 服务端的到期日按 UTC 日历日推导（{@code ReceivableService.TODAY_UTC}，刻意为之 —— 用会话时区会凭空多出空桶）；
   * 而 {@code confirmed_at} 是 timestamptz，从库里读回来时 JDBC 按 **JVM 本地时区** 给偏移，
   * 直接 {@code .toLocalDate()} 拿到的是**本地日历日** —— 本地 00:00-08:00 窗口内与 UTC 日历日差一天。
   */
  private static LocalDate utcCalendarDate(OffsetDateTime instant) {
    return instant.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
  }

  @Test
  void 到期日按UTC日历日推导而不是跟着JVM本地时区走() {
    enablePolicy("MANUAL", 30);
    var receivable = createManual(null);
    var confirmedAt = receivable.confirmedAt();
    var utcDay = utcCalendarDate(confirmedAt);

    // ① 契约：到期日 = confirmedAt 的 **UTC 日历日** + 账期。
    assertThat(receivable.dueDate()).isEqualTo(utcDay.plusDays(30));

    // ② 落库口径（由 DB 独立算一遍，不受「读回时带本地偏移」的影响）：
    //    due_date 必须等于 (confirmed_at at time zone 'UTC')::date + 账期 —— 服务端写进去的就是 UTC 日历日。
    var row =
        one(
            "select due_date, (confirmed_at at time zone 'UTC')::date as utc_day"
                + " from finance_receivable where id = ?",
            receivable.id());
    assertThat(row.get("due_date", LocalDate.class))
        .isEqualTo(row.get("utc_day", LocalDate.class).plusDays(30));

    // ③ 本地日历日与 UTC 日历日**不同**时（本地 00:00-08:00 窗口），到期日必须落在 UTC 那一天，
    //    绝不能跟着本地日历日走 —— 这条正是「差一天」那个坑的直接反证。
    if (!utcDay.equals(confirmedAt.toLocalDate())) {
      assertThat(receivable.dueDate()).isNotEqualTo(confirmedAt.toLocalDate().plusDays(30));
    }

    // ④ 跨日边界夹具（对任何 JVM 时区都成立，不依赖运行时刻）：
    //    2026-10-01T16:30:00Z 在 UTC 下是 10-01，在 +08:00 下是 10-02 —— 正好差一天。
    //    口径必须落在 UTC 那一天推出来的到期日（10-31），而不是本地那天（11-01）。
    var crossing = OffsetDateTime.parse("2026-10-01T16:30:00Z");
    assertThat(utcCalendarDate(crossing)).isEqualTo(LocalDate.of(2026, 10, 1));
    assertThat(crossing.withOffsetSameInstant(ZoneOffset.ofHours(8)).toLocalDate())
        .isEqualTo(LocalDate.of(2026, 10, 2));
    assertThat(utcCalendarDate(crossing).plusDays(30)).isEqualTo(LocalDate.of(2026, 10, 31));
    assertThat(utcCalendarDate(crossing).plusDays(30))
        .isNotEqualTo(crossing.withOffsetSameInstant(ZoneOffset.ofHours(8)).toLocalDate().plusDays(30));
  }

  @Test
  void 金额落库是四位小数的numeric而不是浮点() {
    enablePolicy("MANUAL", 0);
    createManual(null);
    var column =
        one(
            "select data_type, numeric_scale from information_schema.columns"
                + " where table_name = 'finance_receivable' and column_name = 'amount'");
    assertThat(column.get("data_type", String.class)).isEqualTo("numeric");
    assertThat(column.get("numeric_scale", Integer.class)).isEqualTo(4);
    // 逐位相等：金额是 20.0000，不是 20、也不是 20.0。
    assertThat(
            one("select amount from finance_receivable").get(0, BigDecimal.class).toPlainString())
        .isEqualTo("20.0000");
    var quantityColumn =
        one(
            "select data_type, numeric_scale from information_schema.columns"
                + " where table_name = 'finance_receivable_line' and column_name = 'quantity'");
    assertThat(quantityColumn.get("data_type", String.class)).isEqualTo("numeric");
    assertThat(quantityColumn.get("numeric_scale", Integer.class)).isEqualTo(6);
  }

  @Test
  void 明细行必须有金额且不能为零() {
    enablePolicy("MANUAL", 0);
    var input =
        inputOf(
            """
            {"counterpartyType":"CUSTOMER","counterpartyName":"客户甲","currency":"USD",
             "lines":[{"description":"明细一","amount":0}]}
            """);
    assertThatThrownBy(() -> as(() -> receivables.create(input, actor())))
        .hasStackTraceContaining("必须大于 0");
  }

  @Test
  void 没有明细行的应收单建不出来() {
    enablePolicy("MANUAL", 0);
    var input =
        inputOf(
            """
            {"counterpartyType":"CUSTOMER","counterpartyName":"客户甲","currency":"USD","lines":[]}
            """);
    assertThatThrownBy(() -> as(() -> receivables.create(input, actor())))
        .hasStackTraceContaining("至少要有一条明细行");
  }

  @Test
  void b2b账期应收不能手工录入() {
    enablePolicy("B2B_CREDIT_TERM", 30);
    var input =
        inputOf(
            """
            {"source":"B2B_CREDIT_TERM","counterpartyType":"CUSTOMER","counterpartyName":"客户甲",
             "currency":"USD","lines":[{"description":"明细一","amount":12.5}]}
            """);
    assertThatThrownBy(() -> as(() -> receivables.create(input, actor())))
        .hasStackTraceContaining("发货确认");
  }

  @Test
  void 平台结算来源当前不可产生且必须写明归属模块() {
    enablePolicy("PLATFORM_SETTLEMENT", 30);
    var input =
        inputOf(
            """
            {"source":"PLATFORM_SETTLEMENT","counterpartyType":"PLATFORM","counterpartyName":"Amazon",
             "currency":"USD","lines":[{"description":"明细一","amount":12.5}]}
            """);
    assertThatThrownBy(() -> as(() -> receivables.create(input, actor())))
        .hasStackTraceContaining("12.3.1");
    assertThat(count("select count(*) from finance_receivable"))
        .as("被拒的建单不得留下任何行（绝不填 0、也绝不造假结算）")
        .isZero();
  }

  // ================================================================ 汇率快照

  @Test
  void 汇率快照建单时定死且本位币金额等于金额乘汇率() {
    enablePolicy("MANUAL", 0);
    var receivable = createManual(",\"exchangeRate\":7.2,\"baseCurrency\":\"CNY\"");
    assertThat(receivable.exchangeRate()).isEqualByComparingTo("7.20000000");
    assertThat(receivable.baseCurrency()).isEqualTo("CNY");
    assertThat(receivable.baseAmount()).isEqualByComparingTo("144.0000");
  }

  @Test
  void 没有汇率时本位币金额是null而不是零() {
    enablePolicy("MANUAL", 0);
    var receivable = createManual(null);
    assertThat(receivable.exchangeRate()).isNull();
    assertThat(receivable.baseAmount()).as("「没折算」与「折算了 0 块」必须能分开").isNull();
  }

  @Test
  void 确认之后汇率快照改不动() {
    enablePolicy("MANUAL", 0);
    createManual(",\"exchangeRate\":7.2,\"baseCurrency\":\"CNY\"");
    UUID id = one("select id from finance_receivable").get(0, UUID.class);
    assertThat(dbError("update finance_receivable set exchange_rate = 7.3 where id = ?", id))
        .contains("快照");
  }

  @Test
  void 确认之后明细行改不动() {
    enablePolicy("MANUAL", 0);
    createManual(null);
    UUID lineId =
        one("select id from finance_receivable_line order by line_no limit 1").get(0, UUID.class);
    assertThat(dbError("update finance_receivable_line set amount = 99 where id = ?", lineId))
        .contains("不可再改");
  }

  // ================================================================ 核销派生（12.2.1.2 未交付，写路径未开放）

  @Test
  void 明细行的核销金额把单头状态派生出来() {
    enablePolicy("MANUAL", 0);
    createManual(null);
    UUID lineId =
        one("select id from finance_receivable_line order by line_no limit 1").get(0, UUID.class);
    // 12.2.1.2 交付后写的就是这一列。本模块不开放写端点，所以这里直接写 SQL，
    // 验证「单头状态由明细派生」这条不变量真的成立。
    as(
        () ->
            sql.execute(
                "update finance_receivable_line set settled_amount = 5 where id = ?", lineId));
    var head = one("select status, settled_amount from finance_receivable");
    assertThat(head.get("status", String.class)).isEqualTo("PARTIALLY_SETTLED");
    assertThat(head.get("settled_amount", BigDecimal.class)).isEqualByComparingTo("5.0000");

    as(
        () ->
            sql.execute(
                "update finance_receivable_line set settled_amount = 12.5 where id = ?", lineId));
    head = one("select status, settled_amount from finance_receivable");
    assertThat(head.get("status", String.class)).isEqualTo("PARTIALLY_SETTLED");
  }

  @Test
  void 全部核销后单头是已核销且已核销金额等于单头金额() {
    enablePolicy("MANUAL", 0);
    createManual(null);
    as(() -> sql.execute("update finance_receivable_line set settled_amount = amount"));
    var head = one("select status, settled_amount, amount from finance_receivable");
    assertThat(head.get("status", String.class)).isEqualTo("SETTLED");
    assertThat(head.get("settled_amount", BigDecimal.class))
        .isEqualByComparingTo(head.get("amount", BigDecimal.class));
  }

  @Test
  void 已核销的应收不能作废() {
    enablePolicy("MANUAL", 0);
    createManual(null);
    as(() -> sql.execute("update finance_receivable_line set settled_amount = 5"));
    UUID id = one("select id from finance_receivable").get(0, UUID.class);
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        receivables.voidReceivable(
                            id, new FinanceReceivableVoidInput("试试作废"), actor())))
        .hasStackTraceContaining("12.1.1.3");
  }

  // ================================================================ 作废

  @Test
  void 作废必须写理由且作废后整单冻结() {
    enablePolicy("MANUAL", 0);
    createManual(null);
    UUID id = one("select id from finance_receivable").get(0, UUID.class);
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        receivables.voidReceivable(
                            id, new FinanceReceivableVoidInput(""), actor())))
        .hasStackTraceContaining("理由");
    var voided =
        as(
            () ->
                receivables.voidReceivable(id, new FinanceReceivableVoidInput("客户赖账，撤销"), actor()));
    assertThat(voided.status()).isEqualTo(ReceivableStatus.VOID);
    // 终态整单冻结：再改任何一列都被 DB 拒。
    assertThat(dbError("update finance_receivable set note = '改一下' where id = ?", id))
        .contains("终态");
  }

  // ================================================================ 争议

  @Test
  void 标注争议必须写明情况且争议金额单独统计() {
    enablePolicy("MANUAL", 0);
    createManual(null);
    UUID id = one("select id from finance_receivable").get(0, UUID.class);
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        receivables.flagDispute(
                            id, new FinanceReceivableDisputeInput(true, ""), actor())))
        .hasStackTraceContaining("情况");
    var flagged =
        as(
            () ->
                receivables.flagDispute(
                    id, new FinanceReceivableDisputeInput(true, "平台冻结中"), actor()));
    assertThat(flagged.disputeFlag()).isTrue();
    var aging = as(() -> receivables.aging());
    assertThat(aging.buckets()).hasSize(1);
    assertThat(aging.buckets().get(0).disputeAmount()).isEqualByComparingTo("20.0000");
    assertThat(aging.buckets().get(0).bucket()).isEqualTo(AgingBucket.NOT_DUE);
  }

  // ================================================================ 账龄

  @Test
  void 账龄按五档分桶并按币种分开() {
    enablePolicy("MANUAL", 0);
    // 未到期（政策账期 0 天 ⇒ 确认即到期）
    createManual(null);
    insertReceivable("AR-OVERDUE-45", "USD", LocalDate.now(ZoneOffset.UTC).minusDays(45));
    insertReceivable("AR-OVERDUE-100", "EUR", LocalDate.now(ZoneOffset.UTC).minusDays(100));
    insertReceivable("AR-OVERDUE-10", "EUR", LocalDate.now(ZoneOffset.UTC).minusDays(10));

    var aging = as(() -> receivables.aging());
    var byBucketCurrency = new HashMap<String, BigDecimal>();
    for (var b : aging.buckets()) {
      byBucketCurrency.put(b.bucket().name() + "/" + b.currency(), b.amount());
    }
    assertThat(byBucketCurrency).containsEntry("NOT_DUE/USD", new BigDecimal("20.0000"));
    assertThat(byBucketCurrency).containsEntry("D31_60/USD", new BigDecimal("10.0000"));
    assertThat(byBucketCurrency).containsEntry("D90_PLUS/EUR", new BigDecimal("10.0000"));
    assertThat(byBucketCurrency).containsEntry("D1_30/EUR", new BigDecimal("10.0000"));
    // 币种分开：USD 30、EUR 30 —— 但它们**不**被加成一个数。
    assertThat(aging.currencyTotals()).hasSize(2);
    assertThat(aging.openCount()).isEqualTo(4);
  }

  // ================================================================ 催收任务

  @Test
  void 催收任务同一张应收同一级别只能有一条() {
    enablePolicy("MANUAL", 0);
    createManual(null);
    UUID id = one("select id from finance_receivable").get(0, UUID.class);
    var task =
        as(
            () ->
                receivables.createCollectionTask(
                    id,
                    new FinanceCollectionTaskInput(CollectionTaskLevel.OVERDUE, null, null, "第一次催"),
                    actor()));
    assertThat(task.level()).isEqualTo(CollectionTaskLevel.OVERDUE);
    assertThat(task.status()).isEqualTo(CollectionTaskStatus.OPEN);
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        receivables.createCollectionTask(
                            id,
                            new FinanceCollectionTaskInput(
                                CollectionTaskLevel.OVERDUE, null, null, "再催一次"),
                            actor())))
        .as("重复催收是同一次催收")
        .hasStackTraceContaining("重复催收");
    assertThat(count("select count(*) from finance_collection_task")).isEqualTo(1);
  }

  @Test
  void 批量生成逾期催收任务是幂等的() {
    enablePolicy("MANUAL", 0);
    insertReceivable("AR-PAST-DUE", "USD", LocalDate.now(ZoneOffset.UTC).minusDays(5));

    var first = as(() -> receivables.generateOverdueTasks(actor()));
    assertThat(first).hasSize(1);
    var second = as(() -> receivables.generateOverdueTasks(actor()));
    assertThat(second).as("重复生成不得产生第二条任务").isEmpty();
    assertThat(count("select count(*) from finance_collection_task")).isEqualTo(1);
  }

  @Test
  void 催收任务可以关闭且关闭后是终态() {
    enablePolicy("MANUAL", 0);
    createManual(null);
    UUID id = one("select id from finance_receivable").get(0, UUID.class);
    var created =
        as(
            () ->
                receivables.createCollectionTask(
                    id,
                    new FinanceCollectionTaskInput(
                        CollectionTaskLevel.REMINDER, null, null, "到期提醒"),
                    actor()));
    var closed =
        as(
            () ->
                receivables.closeCollectionTask(
                    created.id(), new FinanceCollectionTaskCloseInput("已收到货款"), actor()));
    assertThat(closed.status()).isEqualTo(CollectionTaskStatus.DONE);
    assertThat(closed.completedAt()).isNotNull();
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        receivables.closeCollectionTask(
                            created.id(), new FinanceCollectionTaskCloseInput("再关一次"), actor())))
        .hasStackTraceContaining("终态");
  }

  // ================================================================ 导入

  @Test
  void 从发货确认导入是幂等的且带上上游溯源() {
    enablePolicy("B2B_CREDIT_TERM", 30);
    upstreamPayload =
        upstreamPayloadOf("AR-UPSTREAM-001", "100.0000", "2.000000", "50.0000", "100.0000");
    var first = as(() -> receivables.importFromShipmentConfirmations("session", actor()));
    assertThat(first.imported()).isEqualTo(1);
    assertThat(first.skipped()).isZero();
    assertThat(first.warnings()).isEmpty();

    var stored = one("select * from finance_receivable");
    assertThat(stored.get("source", String.class)).isEqualTo("B2B_CREDIT_TERM");
    assertThat(stored.get("source_system", String.class)).isEqualTo("trade");
    assertThat(stored.get("source_reference", String.class)).isEqualTo("AR-UPSTREAM-001");
    assertThat(stored.get("amount", BigDecimal.class)).isEqualByComparingTo("100.0000");
    assertThat(stored.get("status", String.class)).isEqualTo("OPEN");

    // 再导一次：幂等跳过，不长出第二张。
    var second = as(() -> receivables.importFromShipmentConfirmations("session", actor()));
    assertThat(second.imported()).isZero();
    assertThat(second.skipped()).isEqualTo(1);
    assertThat(count("select count(*) from finance_receivable")).isEqualTo(1);
  }

  @Test
  void 上游单头金额与明细对不上时跳过并如实报出来() {
    enablePolicy("B2B_CREDIT_TERM", 30);
    upstreamPayload =
        upstreamPayloadOf("AR-BROKEN-001", "100.0000", "2.000000", "60.0000", "120.0000");
    var result = as(() -> receivables.importFromShipmentConfirmations("session", actor()));
    assertThat(result.imported()).isZero();
    assertThat(result.warnings()).hasSize(1);
    assertThat(result.warnings().get(0)).contains("AR-BROKEN-001");
    assertThat(count("select count(*) from finance_receivable")).as("造出一张金额对不上的应收比不导入更糟").isZero();
  }

  @Test
  void 上游读不到时整体失败而不是报零条() {
    enablePolicy("B2B_CREDIT_TERM", 30);
    upstreamDown = true;
    assertThatThrownBy(
            () -> as(() -> receivables.importFromShipmentConfirmations("session", actor())))
        .as("上游读不到必须显式失败，绝不返回「导入 0 条」冒充「上游没有数据」")
        .hasStackTraceContaining("trade 服务");
    assertThat(count("select count(*) from finance_receivable")).isZero();
  }

  @Test
  void 上游读不到时http层是503() throws Exception {
    enablePolicy("B2B_CREDIT_TERM", 30);
    upstreamDown = true;
    http.perform(post("/finance/receivables/import-b2b").header("Authorization", "session"))
        .andExpect(status().isServiceUnavailable());
  }

  // ================================================================ 列表与汇总

  @Test
  void 汇总覆盖整个筛选结果集而不是当前页() {
    enablePolicy("MANUAL", 0);
    for (int i = 0; i < 5; i++) {
      createManual(null);
    }
    var page1 =
        as(
            () ->
                receivables.page(
                    new ReceivableQuery(
                        null, null, null, null, null, null, null, null, null, null, 0, 2)));
    var page2 =
        as(
            () ->
                receivables.page(
                    new ReceivableQuery(
                        null, null, null, null, null, null, null, null, null, null, 1, 2)));
    assertThat(page1.items()).hasSize(2);
    assertThat(page2.items()).hasSize(2);
    assertThat(page1.total()).isEqualTo(5);
    assertThat(page1.summaryByCurrency().get(0).amount()).isEqualByComparingTo("100.0000");
    assertThat(page2.summaryByCurrency().get(0).amount())
        .as("翻页不得让汇总变小")
        .isEqualByComparingTo("100.0000");
  }

  @Test
  void 币种分开汇总不跨币种相加() {
    enablePolicy("MANUAL", 0);
    createManual(null);
    var eur =
        inputOf(
            """
            {"counterpartyType":"CUSTOMER","counterpartyName":"客户乙","currency":"EUR",
             "lines":[{"description":"明细一","amount":30}]}
            """);
    as(() -> receivables.create(eur, actor()));
    var page =
        as(
            () ->
                receivables.page(
                    new ReceivableQuery(
                        null, null, null, null, null, null, null, null, null, null, 0, 50)));
    assertThat(page.summaryByCurrency()).hasSize(2);
    assertThat(page.summaryByCurrency())
        .extracting(FinanceReceivableCurrencySummary::currency)
        .containsExactlyInAnyOrder("USD", "EUR");
  }

  @Test
  void 筛选是真的筛选且汇总跟着变() {
    enablePolicy("MANUAL", 0);
    createManual(null);
    var eur =
        inputOf(
            """
            {"counterpartyType":"CUSTOMER","counterpartyName":"客户乙","currency":"EUR",
             "lines":[{"description":"明细一","amount":30}]}
            """);
    as(() -> receivables.create(eur, actor()));
    var page =
        as(
            () ->
                receivables.page(
                    new ReceivableQuery(
                        null, null, "EUR", null, null, null, null, null, null, null, 0, 50)));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.summaryByCurrency()).hasSize(1);
    assertThat(page.summaryByCurrency().get(0).currency()).isEqualTo("EUR");
  }

  @Test
  void 逾期筛选只给真正逾期且未核销的() {
    enablePolicy("MANUAL", 0);
    createManual(null);
    insertReceivable("AR-OVERDUE-45", "USD", LocalDate.now(ZoneOffset.UTC).minusDays(45));
    var page =
        as(
            () ->
                receivables.page(
                    new ReceivableQuery(
                        null, null, null, null, null, null, true, null, null, null, 0, 50)));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items().get(0).receivableNo()).isEqualTo("AR-OVERDUE-45");
    assertThat(page.overdueCount()).isEqualTo(1);
  }

  // ================================================================ 时间字段

  @Test
  void timestamptz写回零漂移() {
    enablePolicy("MANUAL", 0);
    // 刻意用 +08:00 偏移写入：写进去的是同一个瞬间，读回来必须是同一个瞬间。
    var original = OffsetDateTime.of(2026, 10, 23, 16, 30, 0, 0, ZoneOffset.ofHours(8));
    UUID id = UUID.randomUUID();
    as(
        () -> {
          sql.execute(
              "insert into finance_receivable(id,tenant_id,receivable_no,source,source_system,"
                  + " counterparty_type,counterparty_name,currency,due_date,confirmed_at,status,"
                  + " created_at,updated_at)"
                  + " values(?,?,'AR-TZ-001','MANUAL','manual','CUSTOMER','客户','USD',"
                  + " ?::date,?::timestamptz,'DRAFT',?::timestamptz,?::timestamptz)",
              id,
              tenant,
              original.toLocalDate(),
              original,
              original,
              original);
          return null;
        });
    var readBack =
        one("select confirmed_at from finance_receivable where id = ?", id)
            .get("confirmed_at", OffsetDateTime.class);
    assertThat(readBack.toInstant())
        .as("timestamptz 必须写回同一个瞬间（不是偏 8 小时的墙上时间）")
        .isEqualTo(original.toInstant());
  }

  // ================================================================ 夹具工具

  /** 人工录入的入参（可带额外字段）。 */
  private FinanceReceivableInput manualInput(String extraJson) {
    return inputOf(
        """
        {"counterpartyType":"CUSTOMER","counterpartyName":"客户甲","currency":"USD"%s,
         "lines":[{"description":"明细一","amount":12.5}]}
        """
            .formatted(extraJson == null ? "" : extraJson));
  }

  /** 读一个入参（JSON 解析失败是测试写错了，直接炸）。 */
  private FinanceReceivableInput inputOf(String body) {
    try {
      return json.readValue(body, FinanceReceivableInput.class);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 直接插一张应收单（绕开服务层：这里要造的是「已逾期」这种服务层造不出的形状）。 */
  void insertReceivable(String no, String currency, LocalDate dueDate) {
    as(
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          UUID id = UUID.randomUUID();
          sql.execute(
              "insert into finance_receivable(id,tenant_id,receivable_no,source,source_system,"
                  + " counterparty_type,counterparty_name,currency,due_date,confirmed_at,status,"
                  + " created_at,updated_at)"
                  + " values(?,?,?,'MANUAL','manual','CUSTOMER','客户',?,?::date,?::timestamptz,'DRAFT',"
                  + " ?::timestamptz,?::timestamptz)",
              id,
              tenant,
              no,
              currency,
              dueDate,
              now,
              now,
              now);
          sql.execute(
              "insert into finance_receivable_line(id,tenant_id,receivable_id,line_no,description,"
                  + " sku_code,amount) values(?,?,?,1,'夹具明细','',10.0000)",
              UUID.randomUUID(),
              tenant,
              id);
          // 确认：DB 触发器会校验「至少一条明细行」并把金额派生到单头。
          sql.execute(
              "update finance_receivable set status='OPEN' where id = ? and tenant_id = ?",
              id,
              tenant);
          return null;
        });
  }

  /** 造一条上游账期应收的 JSON。 */
  static String upstreamPayloadOf(
      String no, String amount, String quantity, String unitPrice, String lineAmount) {
    return """
        {"items":[{"id":"11111111-1111-1111-1111-111111111111","receivableNo":"%s",
        "orderId":"22222222-2222-2222-2222-222222222222","orderNo":"B2B-001",
        "shipmentId":"33333333-3333-3333-3333-333333333333",
        "customerPartyId":"44444444-4444-4444-4444-444444444444","customerName":"B2B 客户",
        "currency":"USD","paymentMethod":"CREDIT_TERM","paymentTermsDays":30,
        "amount":%s,"confirmedAt":"2026-10-01T08:00:00Z","dueDate":"2026-10-31T00:00:00Z",
        "status":"OPEN","invoiceState":"NOT_REQUESTED",
        "lines":[{"id":"55555555-5555-5555-5555-555555555555",
        "orderLineId":"66666666-6666-6666-6666-666666666666","skuCode":"SKU-001",
        "quantity":%s,"unitPrice":%s,"lineAmount":%s}]}],
        "page":0,"size":200,"total":1}
        """
        .formatted(no, amount, quantity, unitPrice, lineAmount);
  }
}
