package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.SettlementModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 12.3.1.1 结算单管理的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖的是蓝图 12-finance.md 里 12.3.1.1 那几条**加粗句**与「关键约束」：
 *
 * <ul>
 *   <li>「平台账单汇总（销售额、退款、佣金、FBA 费用、广告费、赔付、其他调整）」⇒ 七个类别**真的**落库；
 *   <li>「**结算金额必须由明细项求和得出，不许手填一个与明细对不上的总额**」⇒ 派生链在 DB 层，
 *       服务层与直连 SQL 都写不进一个对不上的总额；
 *   <li>「**平台账单是财务事实的重要外部来源，需保留原始文件与解析版本**」⇒ 原文 + 指纹 + 版本号留痕，
 *       重解析版本递增，结算单可追溯到具体哪一次解析；
 *   <li>「输入：人工上传账单」⇒ CSV（RFC 4180 引号）/ JSON 两条路都真入库；
 *   <li>「输出：…**与我方账的差额**」⇒ 差额**真的算出来**（平台侧 vs 我方应收，按来源分组）；
 *   <li>「平台结算报告同步（2 同步）」⇒ 上游没端点 ⇒ 该来源**当前不可产生**，**不造假账单**；
 *   <li>「金额一律 BigDecimal + NUMERIC，禁止浮点」⇒ 金额是四位小数的 numeric，逐位相等；
 *   <li>「**到账时间**」是核心字段 ⇒ 写回的值必须**逐位相等**（偏移 8 小时就是错账）。
 * </ul>
 *
 * <p><b>断言风格与本仓其它模块一致</b>：业务规则与拒绝理由走**服务层 / 数据层**
 * （{@code assertThatThrownBy(...).hasStackTraceContaining(...)}），HTTP 层只断言**状态码**。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SettlementIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();

  /** 上游 channel 的店铺（固定 id，测试里好引用）。 */
  static final UUID SHOP = UUID.randomUUID();

  static final List<String> PERMISSIONS =
      List.of("finance.settlement.read", "finance.settlement.create", "finance.settlement.update");

  /**
   * 测试里的 JSON mapper。
   *
   * <p>⚠️ {@code USE_BIG_DECIMAL_FOR_FLOATS} 必须开：默认 Jackson 把 JSON 里的浮点数读成 {@code double}，
   * 于是 {@code 20.0000} 变成 {@code 20.0} —— 断言就分不出「金额是四位小数」和「金额丢了一位」。
   */
  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer channel;

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

      // 上游 channel 2.3.1.1 的店铺列表（只读端口 ChannelShopPort 打的就是它）。
      channel = HttpServer.create(new InetSocketAddress(0), 0);
      channel.createContext(
          "/channel/shops",
          e -> {
            if (actors.get(e.getRequestHeaders().getFirst("Authorization")) == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body =
                ("[{\"id\":\""
                        + SHOP
                        + "\",\"code\":\"SHOP-1\",\"name\":\"美国一店\","
                        + "\"platformCode\":\"amazon\",\"platformName\":\"Amazon\",\"status\":\"ACTIVE\"}]")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      channel.setExecutor(Executors.newFixedThreadPool(2));
      channel.start();
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
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:" + channel.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://127.0.0.1:1");
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired SettlementService settlements;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  /** 清库。用迁移用户（超管）连接：RLS 对超管不生效。 */
  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_settlement_event, finance_settlement_summary,"
              + " finance_settlement_line, finance_settlement, finance_settlement_source_file,"
              + " finance_settlement_cycle, finance_receipt_event, finance_receipt_allocation,"
              + " finance_receipt, finance_receivable_event, finance_collection_task,"
              + " finance_receivable_line, finance_receivable, finance_receivable_policy"
              + " restart identity cascade");
    }
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
   * 一份典型的平台账单 CSV（金额单位：元；结算币种 USD）。
   *
   * <p>⚠️ 刻意用字符串拼接而不是文本块：第三行里有 RFC 4180 的转义引号（{@code ""}），
   * 在文本块里 {@code """} 会**结束**文本块。
   */
  static final String CSV =
      String.join(
          "\n",
          "category,amount,currency,quantity,occurred_on,external_reference,description",
          "SALES,1000.00,USD,10,2026-10-01,ORDER-1,\"Sales, order 1\"",
          "REFUND,100.00,USD,1,2026-10-02,ORDER-2,\"Refund \"\"special\"\"\"",
          "COMMISSION,150.00,USD,,,,,Platform commission",
          "FBA_FEE,80.00,USD,,,,,FBA fulfillment fee",
          "ADVERTISING,60.00,USD,,,,,Advertising spend",
          "COMPENSATION,20.00,USD,,,,,Reimbursement",
          "ADJUSTMENT,-10.00,USD,,,,,Other adjustment");

  /** 一份导入请求（默认覆盖参数）。 */
  private FinanceSettlementImportInput importInput(String fileKey, String content) {
    return new FinanceSettlementImportInput(
        SettlementSource.MANUAL_UPLOAD,
        "amazon",
        fileKey,
        SettlementImportFormat.CSV,
        content,
        SHOP,
        LocalDate.of(2026, 10, 1),
        LocalDate.of(2026, 10, 15),
        "USD",
        null);
  }

  /** 导入并返回结果（走服务层，与生产同一条路径）。 */
  private FinanceSettlementImportResult doImport(String fileKey, String content) {
    return as(() -> settlements.importBill(importInput(fileKey, content), actor(), "session"));
  }

  // ==========================================================================
  // 1. 人工上传账单真的能落地
  // ==========================================================================

  @Test
  void 平台账单CSV导入真的落库_七个类别都进汇总项() {
    var result = doImport("RPT-2026-10-01", CSV);

    assertThat(result.scanned()).isEqualTo(7);
    assertThat(result.imported()).isEqualTo(7);
    assertThat(result.skipped()).isEqualTo(0);
    assertThat(result.duplicate()).isFalse();
    assertThat(result.warnings()).isEmpty();
    assertThat(result.settlement()).isNotNull();

    var detail = as(() -> settlements.detail(result.settlement().id()));
    // 七个类别一个不少（蓝图点名的七项）。
    assertThat(detail.summary())
        .extracting(FinanceSettlementSummaryRow::category)
        .containsExactly(
            SettlementCategory.SALES,
            SettlementCategory.REFUND,
            SettlementCategory.COMMISSION,
            SettlementCategory.FBA_FEE,
            SettlementCategory.ADVERTISING,
            SettlementCategory.COMPENSATION,
            SettlementCategory.ADJUSTMENT);

    // 符号约定：收入为正、支出为负。
    assertThat(detail.summary())
        .filteredOn(s -> s.category() == SettlementCategory.SALES)
        .singleElement()
        .extracting(FinanceSettlementSummaryRow::amount)
        .isEqualTo(new BigDecimal("1000.0000"));
    assertThat(detail.summary())
        .filteredOn(s -> s.category() == SettlementCategory.REFUND)
        .singleElement()
        .extracting(FinanceSettlementSummaryRow::amount)
        .isEqualTo(new BigDecimal("-100.0000"));
    assertThat(detail.summary())
        .filteredOn(s -> s.category() == SettlementCategory.ADJUSTMENT)
        .singleElement()
        .extracting(FinanceSettlementSummaryRow::amount)
        .isEqualTo(new BigDecimal("-10.0000"));

    // 明细行真的落库了（7 行），且数量是 numeric(19,6)。
    assertThat(detail.lines()).hasSize(7);
    assertThat(detail.lines().get(0).quantity()).isEqualByComparingTo("10.000000");
    // 账单里写的那个数**原样保留**（退款写成正的 100.00）。
    assertThat(detail.lines().get(1).rawAmount()).isEqualByComparingTo("100.0000");
    assertThat(detail.lines().get(1).amount()).isEqualByComparingTo("-100.0000");
  }

  @Test
  void 结算金额等于各汇总项之和_而不是手填的() {
    var result = doImport("RPT-SUM", CSV);
    var settlement = result.settlement();

    // 1000 − 100 − 150 − 80 − 60 + 20 − 10 = 620
    assertThat(settlement.settlementAmount()).isEqualByComparingTo("620.0000");

    var summarySum =
        as(
                () ->
                    sql.fetchOne(
                            "select coalesce(sum(amount),0) from finance_settlement_summary"
                                + " where settlement_id = ?",
                            settlement.id())
                        .get(0, BigDecimal.class));
    assertThat(summarySum).isEqualByComparingTo("620.0000");
    assertThat(summarySum).isEqualByComparingTo(settlement.settlementAmount());
  }

  @Test
  void 汇总项与结算金额都写不进去_手填一个对不上的总额在DB层被拒() {
    var result = doImport("RPT-GUARD", CSV);
    UUID id = result.settlement().id();

    // ① 直接改结算金额 ⇒ 被派生守卫拒掉。
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "update finance_settlement set settlement_amount = 999999 where id = ?", id);
                      return null;
                    }))
        .as("结算金额只能由汇总项求和派生，不能手填")
        .hasStackTraceContaining("不允许直接写入");

    // ② 直接插汇总项 ⇒ 被派生守卫拒掉。
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "insert into finance_settlement_summary(id,tenant_id,settlement_id,category,"
                              + " amount,line_count) values(?,?,?,'SALES',999999,1)",
                          UUID.randomUUID(),
                          tenant,
                          id);
                      return null;
                    }))
        .as("汇总项由明细行派生，不能直接写")
        .hasStackTraceContaining("不允许直接写入");

    // ③ 改完之后结算金额仍然是派生出来的那个值。
    var after = as(() -> settlements.detail(id));
    assertThat(after.settlement().settlementAmount()).isEqualByComparingTo("620.0000");
  }

  // ==========================================================================
  // 2. RFC 4180 与两级错误分层
  // ==========================================================================

  @Test
  void CSV按RFC4180处理引号_引号里的逗号不会冲掉金额列() {
    var result = doImport("RPT-QUOTE", CSV);
    var detail = as(() -> settlements.detail(result.settlement().id()));

    // 第一行的描述里有逗号，且用了 "" 转义 —— 金额列仍然被正确解析。
    var sales =
        detail.lines().stream()
            .filter(l -> l.category() == SettlementCategory.SALES)
            .findFirst()
            .orElseThrow();
    assertThat(sales.amount()).isEqualByComparingTo("1000.0000");
    assertThat(sales.description()).isEqualTo("Sales, order 1");
    assertThat(sales.externalReference()).isEqualTo("ORDER-1");
  }

  @Test
  void 行级错误只跳过那一行_文件级错误整体失败() {
    // ① 行级：一行金额不是数字 + 一行类别不认识 ⇒ 只跳过那两行，其余照进。
    var broken =
        """
        category,amount
        SALES,100.00
        SALES,不是数字
        不认识的类别,50.00
        REFUND,20.00
        """;
    var result = doImport("RPT-ROWERR", broken);
    assertThat(result.scanned()).isEqualTo(4);
    assertThat(result.imported()).isEqualTo(2);
    assertThat(result.skipped()).isEqualTo(2);
    assertThat(result.warnings()).hasSize(2);
    assertThat(String.join("；", result.warnings()))
        .contains("金额不是数字")
        .contains("类别不认识");

    // ② 文件级：CSV 缺必需的列 ⇒ 整体失败（400），一条都不导入。
    assertThatThrownBy(() -> doImport("RPT-FILEERR", "foo,bar\n1,2\n"))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasStackTraceContaining("CSV 缺少必需的列");
    assertThat(count("select count(*) from finance_settlement")).isEqualTo(1);
  }

  @Test
  void JSON账单也能导入() {
    var content =
        """
        {"rows":[
          {"category":"SALES","amount":"800.00","currency":"USD","quantity":"4"},
          {"category":"COMMISSION","amount":"120.00","currency":"USD"}
        ]}
        """;
    var input =
        new FinanceSettlementImportInput(
            SettlementSource.MANUAL_UPLOAD,
            "shopify",
            "RPT-JSON",
            SettlementImportFormat.JSON,
            content,
            SHOP,
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 15),
            "USD",
            null);
    var result = as(() -> settlements.importBill(input, actor(), "session"));
    assertThat(result.imported()).isEqualTo(2);
    // 800 − 120 = 680
    assertThat(result.settlement().settlementAmount()).isEqualByComparingTo("680.0000");
  }

  // ==========================================================================
  // 3. 原始文件与解析版本
  // ==========================================================================

  @Test
  void 原始文件与解析版本留痕_结算单可追溯到具体哪一次解析() {
    var result = doImport("RPT-TRACE", CSV);
    var detail = as(() -> settlements.detail(result.settlement().id()));

    var file = detail.sourceFile();
    assertThat(file).isNotNull();
    assertThat(file.fileKey()).isEqualTo("RPT-TRACE");
    assertThat(file.parseVersion()).isEqualTo(1);
    assertThat(file.parseStatus()).isEqualTo("PARSED");
    // **原文一字不改地留着**。
    assertThat(file.contentHash()).hasSize(64);
    assertThat(count("select count(*) from finance_settlement_source_file where raw_content like '%Sales, order 1%'"))
        .isEqualTo(1);
    // 结算单指向具体那一次解析。
    assertThat(detail.settlement().sourceFileId()).isEqualTo(file.id());
    assertThat(detail.settlement().parseVersion()).isEqualTo(1);
  }

  @Test
  void 同一份账单重解析版本递增_旧单被取代但留痕() {
    var first = doImport("RPT-REPARSE", CSV);
    var firstId = first.settlement().id();
    assertThat(first.parseVersion()).isEqualTo(1);

    // 同一份账单、**改了内容** ⇒ 重解析 ⇒ 版本 +1，旧单置为 SUPERSEDED（不删）。
    var second = doImport("RPT-REPARSE", CSV.replace("1000.00", "1500.00"));
    assertThat(second.parseVersion()).isEqualTo(2);
    assertThat(second.duplicate()).isFalse();
    assertThat(second.settlement().id()).isNotEqualTo(firstId);
    assertThat(second.settlement().settlementAmount()).isEqualByComparingTo("1120.0000");

    var old = as(() -> settlements.detail(firstId));
    assertThat(old.settlement().status()).isEqualTo(SettlementStatus.SUPERSEDED);
    // 旧单仍在库里（它是「当时登记过」的留痕），且仍指向第 1 版解析。
    assertThat(old.sourceFile().parseVersion()).isEqualTo(1);
    assertThat(old.events()).anyMatch(e -> "SUPERSEDED".equals(e.eventType()));

    // 版本号在 DB 层唯一。
    assertThat(count("select count(*) from finance_settlement_source_file where file_key = 'RPT-REPARSE'"))
        .isEqualTo(2);
  }

  @Test
  void 同样内容重复上传是幂等命中_不新建结算单() {
    var first = doImport("RPT-IDEM", CSV);
    var second = doImport("RPT-IDEM", CSV);

    assertThat(second.duplicate()).isTrue();
    assertThat(second.settlement().id()).isEqualTo(first.settlement().id());
    assertThat(second.parseVersion()).isEqualTo(1);
    assertThat(count("select count(*) from finance_settlement")).isEqualTo(1);
    assertThat(count("select count(*) from finance_settlement_source_file")).isEqualTo(1);
  }

  // ==========================================================================
  // 4. 到账状态与时间
  // ==========================================================================

  @Test
  void 登记到账推进状态_到账时间写回逐位相等() {
    var result = doImport("RPT-ARRIVE", CSV);
    UUID id = result.settlement().id();

    var arrivedAt = OffsetDateTime.of(2026, 10, 20, 9, 30, 0, 0, ZoneOffset.ofHours(8));
    var partial =
        as(
            () ->
                settlements.recordArrival(
                    id,
                    new FinanceSettlementArrivalInput(arrivedAt, new BigDecimal("400.00"), null),
                    actor()));
    assertThat(partial.status()).isEqualTo(SettlementStatus.PARTIAL);
    assertThat(partial.receivedAmount()).isEqualByComparingTo("400.0000");
    // ⚠️ 时间必须**逐位相等**：偏 8 小时就是错账。
    assertThat(partial.arrivedAt()).isEqualTo(arrivedAt);
    assertThat(partial.arrivedAt().getOffset()).isEqualTo(ZoneOffset.ofHours(8));

    var full =
        as(
            () ->
                settlements.recordArrival(
                    id,
                    new FinanceSettlementArrivalInput(arrivedAt, new BigDecimal("620.00"), null),
                    actor()));
    assertThat(full.status()).isEqualTo(SettlementStatus.SETTLED);
    assertThat(full.arrivedAt()).isEqualTo(arrivedAt);

    // 到账时间是外部事实：不可再改。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        settlements.recordArrival(
                            id,
                            new FinanceSettlementArrivalInput(
                                arrivedAt.plusDays(1), new BigDecimal("620.00"), null),
                            actor())))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  @Test
  void 结算单事实不可改_作废是终态() {
    var result = doImport("RPT-VOID", CSV);
    UUID id = result.settlement().id();

    var voided =
        as(
            () ->
                settlements.voidSettlement(
                    id, new FinanceSettlementVoidInput("平台重复结算"), actor()));
    assertThat(voided.status()).isEqualTo(SettlementStatus.VOID);

    // 终态不可再改。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        settlements.recordArrival(
                            id,
                            new FinanceSettlementArrivalInput(
                                OffsetDateTime.now(ZoneOffset.UTC), new BigDecimal("620.00"), null),
                            actor())))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  // ==========================================================================
  // 5. 结算周期配置 → 预计到账时间
  // ==========================================================================

  @Test
  void 结算周期配置决定预计到账时间() {
    var cycle =
        as(
            () ->
                settlements.upsertCycle(
                    new FinanceSettlementCycleInput(
                        SHOP, SettlementCycleType.BIWEEKLY, 7, "USD", "ACTIVE", null),
                    actor(),
                    "session"));
    assertThat(cycle.cycleType()).isEqualTo(SettlementCycleType.BIWEEKLY);
    assertThat(cycle.payoutLagDays()).isEqualTo(7);

    // 周期结束日 + 7 天 = 2026-10-22，该日 00:00:00Z。
    var result = doImport("RPT-CYCLE", CSV);
    assertThat(result.settlement().expectedAt())
        .isEqualTo(OffsetDateTime.of(2026, 10, 22, 0, 0, 0, 0, ZoneOffset.UTC));
  }

  // ==========================================================================
  // 6. 与我方账的差额（真的算出来）
  // ==========================================================================

  @Test
  void 差额真的算出来_平台侧与我方应收侧逐位对齐() {
    var result = doImport("RPT-DIFF", CSV);
    UUID id = result.settlement().id();

    // 造一笔我方应收：同店铺、同币种、确认日在结算周期内。
    var receivableId = UUID.randomUUID();
    as(
        () -> {
          var confirmedAt = OffsetDateTime.of(2026, 10, 5, 12, 0, 0, 0, ZoneOffset.UTC);
          sql.execute(
              "insert into finance_receivable(id,tenant_id,receivable_no,source,source_system,"
                  + " counterparty_type,counterparty_name,shop_id,shop_name,currency,due_date,"
                  + " confirmed_at,status,created_at,updated_at)"
                  + " values(?,?,?,'MANUAL','manual','PLATFORM','Amazon',?,'美国一店','USD',"
                  + "?::date,?::timestamptz,'DRAFT',?::timestamptz,?::timestamptz)",
              receivableId,
              tenant,
              "AR-DIFF-1",
              SHOP,
              LocalDate.of(2026, 10, 20),
              confirmedAt,
              confirmedAt,
              confirmedAt);
          sql.execute(
              "insert into finance_receivable_line(id,tenant_id,receivable_id,line_no,description,"
                  + " sku_code,amount,settled_amount,created_at)"
                  + " values(?,?,?,1,'平台应收','SKU-1',1000,0,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              receivableId,
              confirmedAt);
          sql.execute(
              "update finance_receivable set status='OPEN' where id = ?", receivableId);
          return null;
        });

    var diff = as(() -> settlements.difference(id));

    // 平台侧：结算金额 620。
    assertThat(diff.platformSettlementAmount()).isEqualByComparingTo("620.0000");
    // 我方应收侧：1000，未核销余额 1000。
    assertThat(diff.receivableAmount()).isEqualByComparingTo("1000.0000");
    assertThat(diff.receivableOpenAmount()).isEqualByComparingTo("1000.0000");
    assertThat(diff.receivableCount()).isEqualTo(1);
    assertThat(diff.receivableBySource()).hasSize(1);
    assertThat(diff.receivableBySource().get(0).source()).isEqualTo("MANUAL");
    // 差额 = 平台结算金额 − 我方未核销余额 = 620 − 1000 = −380。
    assertThat(diff.differenceVsReceivable()).isEqualByComparingTo("-380.0000");

    // 收款侧：12.2.1.1 的 settlement_no 恒为 NULL ⇒ 现在恒为 0 笔，并说清归属模块。
    assertThat(diff.linkedReceiptCount()).isZero();
    assertThat(diff.linkedReceiptAmount()).isEqualByComparingTo("0");
    assertThat(diff.receiptLinkNote()).contains("12.2.1.1").contains("settlement_no");
    assertThat(diff.reconciliationNote()).contains("12.3.2.1");
  }

  // ==========================================================================
  // 7. 上游缺口如实标注
  // ==========================================================================

  @Test
  void 平台结算报告同步当前不可产生_归属卷02同步() {
    var input =
        new FinanceSettlementImportInput(
            SettlementSource.PLATFORM_SYNC,
            "amazon",
            "RPT-SYNC",
            SettlementImportFormat.CSV,
            CSV,
            SHOP,
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 15),
            "USD",
            null);
    assertThatThrownBy(() -> as(() -> settlements.importBill(input, actor(), "session")))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasStackTraceContaining("卷 02 同步");
    assertThat(count("select count(*) from finance_settlement")).isZero();
  }

  @Test
  void 能力声明如实标注上游缺口() {
    var caps = as(() -> settlements.capabilities());

    var sync =
        caps.capabilities().stream()
            .filter(c -> c.name().contains("同步"))
            .findFirst()
            .orElseThrow();
    assertThat(sync.available()).isFalse();
    assertThat(sync.ownerModule()).contains("卷 02 同步");

    assertThat(caps.capabilities())
        .anyMatch(c -> c.module().equals("12.3.1.1") && c.available());
    assertThat(caps.downstreamGaps())
        .anyMatch(g -> g.module().equals("12.3.2.1") && !g.available());
    assertThat(caps.downstreamGaps())
        .anyMatch(g -> g.module().equals("13.1.1.1") && !g.available());
    assertThat(caps.downstreamGaps())
        .anyMatch(g -> g.module().equals("12.2.1.1") && g.detail().contains("settlement_no"));
  }

  @Test
  void 店铺必须从上游核验_上游没有的店铺建不了单() {
    var input =
        new FinanceSettlementImportInput(
            SettlementSource.MANUAL_UPLOAD,
            "amazon",
            "RPT-NOSHOP",
            SettlementImportFormat.CSV,
            CSV,
            UUID.randomUUID(),
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 15),
            "USD",
            null);
    assertThatThrownBy(() -> as(() -> settlements.importBill(input, actor(), "session")))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasStackTraceContaining("上游");
  }

  // ==========================================================================
  // 8. HTTP 层
  // ==========================================================================

  @Test
  void 端点真的通_列表与详情与差额() throws Exception {
    var result = doImport("RPT-HTTP", CSV);
    UUID id = result.settlement().id();

    http.perform(get("/finance/settlement").header("Authorization", "session"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].settlementAmount").value(620.0000))
        .andExpect(jsonPath("$.summaryByCurrency[0].currency").value("USD"));

    http.perform(get("/finance/settlement/" + id).header("Authorization", "session"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.length()").value(7))
        .andExpect(jsonPath("$.lines.length()").value(7));

    http.perform(get("/finance/settlement/" + id + "/difference").header("Authorization", "session"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.platformSettlementAmount").value(620.0000));

    http.perform(get("/finance/settlement/capabilities").header("Authorization", "session"))
        .andExpect(status().isOk());

    http.perform(get("/finance/settlement/shops").header("Authorization", "session"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(SHOP.toString()));
  }

  @Test
  void 未认证的请求被拒() throws Exception {
    http.perform(get("/finance/settlement")).andExpect(status().isUnauthorized());
  }
}
