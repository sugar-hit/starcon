package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.SettlementModels.SettlementCategory;
import com.starcon.finance.TransactionModels.*;
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
 * 12.3.1.2 交易明细的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖的是蓝图 12-finance.md 里 12.3.1.2 那几条**加粗句**与硬约束：
 *
 * <ul>
 *   <li>「管理结算单下的逐笔明细：订单销售、退款、费用（佣金/仓储/配送/广告）、赔付、调整、代扣税」⇒
 *       十类交易类型**真的**落库，且交易类型与结算汇总类别的配对被 DB 层 CHECK 钉死；
 *   <li>「**允许无订单行的费用类明细**」⇒ 佣金/仓储费/代扣税**不带订单号也必须能登记**，
 *       且「无订单行」在汇总校验里是**正常状态**不是异常；
 *   <li>「输入：…<code>idempotency_key</code>」⇒ 同一把幂等键在同一张单下重复登记被拒、
 *       跨**另一张在册**单也被拒，而**被取代的旧单不持有幂等键**（重新解析建新单这条路要能走通）；
 *   <li>「输出：明细行 + **汇总校验（明细合计 = 结算单汇总）**」⇒ 校验**真的算出来**，
 *       并且能**真的发现**对不上的地方（符号被绕过 / 混币种 / 跨周期）；
 *   <li>「**不强制每行都有订单**是硬约束」⇒ 订单列可空，**没有**「缺订单即异常」的校验；
 *   <li>「平台交易报告解析结果」⇒ 人工上传交易报告（CSV / JSON）**真的**能解析入库，
 *       且**平台交易报告自动同步**（卷 02 同步未交付）**当前不可产生**。
 * </ul>
 *
 * <p><b>断言风格与本仓其它模块一致</b>：业务规则与拒绝理由走**服务层 / 数据层**
 * （{@code assertThatThrownBy(...).hasStackTraceContaining(...)}），HTTP 层只断言**状态码**。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransactionIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();

  /** 上游 channel 的店铺（固定 id，测试里好引用）。 */
  static final UUID SHOP = UUID.randomUUID();

  static final List<String> PERMISSIONS =
      List.of("finance.transaction.read", "finance.transaction.create");

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
  @Autowired TransactionService transactions;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  /** 清库。用迁移用户（超管）连接：RLS 对超管不生效。 */
  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_settlement_transaction_source_file, finance_settlement_event,"
              + " finance_settlement_summary, finance_settlement_line, finance_settlement,"
              + " finance_settlement_source_file, finance_settlement_cycle, finance_receipt_event,"
              + " finance_receipt_allocation, finance_receipt, finance_receivable_event,"
              + " finance_collection_task, finance_receivable_line, finance_receivable,"
              + " finance_receivable_policy restart identity cascade");
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
   * 一份典型的平台**结算报告** CSV（12.3.1.1 的输入）。
   *
   * <p>本模块的测试用它**造出一张结算单**（上游已交付），再往这张单上追加交易明细。
   */
  static final String BILL_CSV =
      String.join(
          "\n",
          "category,amount,currency,quantity,occurred_on,external_reference,description",
          "SALES,1000.00,USD,10,2026-10-01,ORDER-1,\"Sales, order 1\"",
          "REFUND,100.00,USD,1,2026-10-02,ORDER-2,Refund");

  /** 只有一行退款的上游账单（筛选类用例用它，避免上游的行干扰断言）。 */
  static final String REFUND_ONLY_CSV =
      String.join(
          "\n",
          "category,amount,currency,quantity,occurred_on,external_reference,description",
          "REFUND,100.00,USD,1,2026-10-02,ORDER-2,Refund");

  /** 造一张结算单（走 12.3.1.1 的服务层，与生产同一条路径）。 */
  private UUID newSettlement(String reference) {
    return newSettlement(reference, BILL_CSV);
  }

  private UUID newSettlement(String reference, String csv) {
    var input =
        new SettlementModels.FinanceSettlementImportInput(
            SettlementModels.SettlementSource.MANUAL_UPLOAD,
            "amazon",
            reference,
            SettlementModels.SettlementImportFormat.CSV,
            csv,
            SHOP,
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 15),
            "USD",
            null);
    var result = as(() -> settlements.importBill(input, actor(), "session"));
    // 导入的行数 = 数据行数（表头不算）。
    assertThat(result.imported()).isEqualTo(csv.split("\n").length - 1);
    return result.settlement().id();
  }

  /** 登记一条明细（走服务层）。 */
  private FinanceTransactionRow register(
      UUID settlementId, FinanceTransactionType type, String amount, String key) {
    return register(settlementId, type, amount, key, null, null, null);
  }

  /** 带订单号（**可空**）的登记。 */
  private FinanceTransactionRow register(
      UUID settlementId,
      FinanceTransactionType type,
      String amount,
      String key,
      String orderReference) {
    return register(settlementId, type, amount, key, orderReference, null, null);
  }

  private FinanceTransactionRow register(
      UUID settlementId,
      FinanceTransactionType type,
      String amount,
      String key,
      String orderReference,
      String externalReference,
      LocalDate occurredOn) {
    var input =
        new FinanceTransactionInput(
            FinanceTransactionSource.MANUAL,
            settlementId,
            type,
            new BigDecimal(amount),
            null,
            null,
            occurredOn,
            externalReference,
            orderReference,
            null,
            key,
            "测试登记");
    return as(() -> transactions.register(input, actor()));
  }

  // ==========================================================================
  // 1. 明细真的落库
  // ==========================================================================

  @Test
  void 明细真的落库_归一后的贡献与账单原值都在() {
    UUID settlementId = newSettlement("RPT-1");

    var row = register(settlementId, FinanceTransactionType.ORDER_SALES, "250.00", "KEY-1", "ORDER-9", "TX-9", LocalDate.of(2026, 10, 5));

    assertThat(row.transactionType()).isEqualTo(FinanceTransactionType.ORDER_SALES);
    assertThat(row.transactionTypeLabel()).isEqualTo("订单销售");
    assertThat(row.category()).isEqualTo(SettlementCategory.SALES);
    assertThat(row.amount()).isEqualByComparingTo("250.0000");
    assertThat(row.rawAmount()).isEqualByComparingTo("250.0000");
    assertThat(row.currency()).isEqualTo("USD");
    assertThat(row.occurredOn()).isEqualTo(LocalDate.of(2026, 10, 5));
    assertThat(row.orderReference()).isEqualTo("ORDER-9");
    assertThat(row.externalReference()).isEqualTo("TX-9");
    assertThat(row.idempotencyKey()).isEqualTo("KEY-1");
    assertThat(row.source()).isEqualTo(FinanceTransactionSource.MANUAL);
    assertThat(row.settlementNo()).isNotBlank();

    // 真的在库里（不是只在返回值里）。
    var stored =
        one(
            "select amount, raw_amount, transaction_type, category, currency, occurred_on"
                + " from finance_settlement_line where id = ?",
            row.id());
    assertThat(stored.get("amount", BigDecimal.class)).isEqualByComparingTo("250.0000");
    assertThat(stored.get("transaction_type", String.class)).isEqualTo("ORDER_SALES");
    assertThat(stored.get("category", String.class)).isEqualTo("SALES");
    // occurred_on 是 date 列：读回来就是日历日，不带时刻、也没有时区漂移。
    assertThat(stored.get("occurred_on", LocalDate.class)).isEqualTo(LocalDate.of(2026, 10, 5));
  }

  @Test
  void 退款按符号约定归一_账单里写的正数原样留着() {
    UUID settlementId = newSettlement("RPT-2");

    var row = register(settlementId, FinanceTransactionType.REFUND, "88.50", "KEY-REFUND");

    assertThat(row.amount()).as("退款是支出 ⇒ 归一后为负").isEqualByComparingTo("-88.5000");
    assertThat(row.rawAmount()).as("账单里写的那个数原样保留").isEqualByComparingTo("88.5000");
  }

  @Test
  void 十类交易类型都能落库_代扣税进的是TAX汇总桶() {
    UUID settlementId = newSettlement("RPT-3");

    var types =
        List.of(
            FinanceTransactionType.ORDER_SALES,
            FinanceTransactionType.REFUND,
            FinanceTransactionType.COMMISSION,
            FinanceTransactionType.FBA_FEE,
            FinanceTransactionType.STORAGE_FEE,
            FinanceTransactionType.SHIPPING_FEE,
            FinanceTransactionType.ADVERTISING,
            FinanceTransactionType.COMPENSATION,
            FinanceTransactionType.ADJUSTMENT,
            FinanceTransactionType.WITHHOLDING_TAX);
    int i = 0;
    for (var type : types) {
      i++;
      register(settlementId, type, "10.00", "KEY-TYPE-" + i);
    }

    // 交易类型真的落库了（十类一个不少）。
    var stored =
        as(
            () ->
                sql.fetch(
                    "select distinct transaction_type from finance_settlement_line"
                        + " where settlement_id = ? order by transaction_type",
                    settlementId));
    assertThat(stored).hasSize(10);

    // 代扣税落在 TAX 这个**新的**汇总桶里（V348 放宽词表）。
    var taxCategory =
        one(
            "select category from finance_settlement_line where settlement_id = ?"
                + " and transaction_type = 'WITHHOLDING_TAX'",
            settlementId);
    assertThat(taxCategory.get(0, String.class)).isEqualTo("TAX");

    // 汇总项里也真的多了一行 TAX。
    var summaryCategories =
        as(
            () ->
                sql.fetch(
                    "select distinct category from finance_settlement_summary where settlement_id = ?",
                    settlementId));
    assertThat(summaryCategories.map(r -> r.get(0, String.class))).contains("TAX");
  }

  @Test
  void 交易类型与汇总类别的配对被DB层钉死_直连SQL也插不进去() {
    UUID settlementId = newSettlement("RPT-4");

    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "insert into finance_settlement_line(id,tenant_id,settlement_id,line_no,"
                              + " category,transaction_type,amount,raw_amount,currency,idempotency_key,"
                              + " source,description,created_at)"
                              + " values(?,?,?,99,'SALES','STORAGE_FEE',1,1,'USD','K-BAD','MANUAL','x',"
                              + "?::timestamptz)",
                          UUID.randomUUID(),
                          tenant,
                          settlementId,
                          OffsetDateTime.now(ZoneOffset.UTC));
                      return null;
                    }))
        .as("明细说仓储费、汇总类别却写 SALES ⇒ 必须被 CHECK 拒掉")
        .hasStackTraceContaining("finance_settlement_line_type_category_check");
  }

  // ==========================================================================
  // 2. **允许无订单行的费用类明细**（蓝图加粗句）
  // ==========================================================================

  @Test
  void 费用类明细不带订单号也必须能登记_而且不是异常() {
    UUID settlementId = newSettlement("RPT-5");

    // 佣金、仓储费、代扣税、月度调整：天然没有订单行。
    var commission = register(settlementId, FinanceTransactionType.COMMISSION, "15.00", "KEY-FEE-1");
    var storage = register(settlementId, FinanceTransactionType.STORAGE_FEE, "7.50", "KEY-FEE-2");
    var tax = register(settlementId, FinanceTransactionType.WITHHOLDING_TAX, "3.25", "KEY-FEE-3");

    assertThat(commission.orderReference()).isNull();
    assertThat(storage.orderReference()).isNull();
    assertThat(tax.orderReference()).isNull();

    // 「无订单行」在汇总校验里是**正常状态**，不是异常：不会让校验不通过。
    var check = as(() -> transactions.reconciliation(settlementId));
    // ⚠️ 上游账单那两行也**没有** order_reference（V345 把订单号放在 external_reference 里），
    //   所以「无订单行」= 上游 2 行 + 本模块 3 行 = 5；其中**费用类**只有本模块这 3 行。
    assertThat(check.orderlessLineCount()).isEqualTo(5);
    assertThat(check.orderlessFeeLineCount()).isEqualTo(3);
    assertThat(check.balanced()).isTrue();
    assertThat(check.issues())
        .as("无订单行不是问题，不许出现在 issues 里")
        .noneMatch(issue -> issue.contains("订单行"));
  }

  @Test
  void 给了订单行号却没给订单号要拒_但没给订单号本身不拒() {
    UUID settlementId = newSettlement("RPT-6");

    var input =
        new FinanceTransactionInput(
            FinanceTransactionSource.MANUAL,
            settlementId,
            FinanceTransactionType.ORDER_SALES,
            new BigDecimal("10.00"),
            null,
            null,
            null,
            "TX-X",
            null,
            "LINE-1",
            "KEY-ORD-1",
            null);
    assertThatThrownBy(() -> as(() -> transactions.register(input, actor())))
        .as("有行号却不知道是哪张订单的行 ⇒ 数据错误")
        .hasStackTraceContaining("订单行引用");

    // 不带订单号、也不带订单行号 ⇒ 完全合法。
    var ok = register(settlementId, FinanceTransactionType.ORDER_SALES, "10.00", "KEY-ORD-2");
    assertThat(ok.orderReference()).isNull();
    assertThat(ok.orderLineReference()).isNull();
  }

  // ==========================================================================
  // 3. 幂等键
  // ==========================================================================

  @Test
  void 同一把幂等键在同一张单下重复登记被拒() {
    UUID settlementId = newSettlement("RPT-7");

    register(settlementId, FinanceTransactionType.ORDER_SALES, "10.00", "KEY-DUP");

    assertThatThrownBy(
            () -> register(settlementId, FinanceTransactionType.ORDER_SALES, "10.00", "KEY-DUP"))
        .as("同一笔平台交易不许记两次")
        .hasStackTraceContaining("幂等键");

    // 库里还是只有一行。
    assertThat(
            count(
                "select count(*) from finance_settlement_line where settlement_id = ?"
                    + " and idempotency_key = 'KEY-DUP'",
                settlementId))
        .isEqualTo(1);
  }

  @Test
  void 同一把幂等键不许出现在另一张在册结算单下() {
    UUID first = newSettlement("RPT-8-A");
    UUID second = newSettlement("RPT-8-B");

    register(first, FinanceTransactionType.ORDER_SALES, "10.00", "KEY-X-SETTLE");

    assertThatThrownBy(
            () -> register(second, FinanceTransactionType.ORDER_SALES, "10.00", "KEY-X-SETTLE"))
        .as("同一笔交易记在两张在册单下 ⇒ 必须被拒")
        .hasStackTraceContaining("幂等键");
  }

  @Test
  void 被取代的旧单不持有幂等键_重新解析建新单这条路走得通() {
    UUID first = newSettlement("RPT-9-A");
    register(first, FinanceTransactionType.ORDER_SALES, "10.00", "KEY-REPARSE");

    // 模拟 12.3.1.1 的「重新解析」：旧单置为 SUPERSEDED（终态，不许再追加明细）。
    as(
        () -> {
          sql.execute(
              "update finance_settlement set status='SUPERSEDED' where id = ?", first);
          return null;
        });

    // 旧单是终态 ⇒ 不许再追加明细（账单是外部事实，正确做法是重新解析建新单）。
    assertThatThrownBy(
            () -> register(first, FinanceTransactionType.ORDER_SALES, "10.00", "KEY-REPARSE-2"))
        .as("终态结算单不可再追加明细")
        .hasStackTraceContaining("终态");

    // 新单可以拿到同一把键（旧单不再持有它）。
    UUID second = newSettlement("RPT-9-B");
    var row = register(second, FinanceTransactionType.ORDER_SALES, "10.00", "KEY-REPARSE");
    assertThat(row.idempotencyKey()).isEqualTo("KEY-REPARSE");
  }

  @Test
  void 明细行是append_only_直连SQL改不动也删不掉() {
    UUID settlementId = newSettlement("RPT-10");
    var row = register(settlementId, FinanceTransactionType.ORDER_SALES, "10.00", "KEY-FROZEN");

    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "update finance_settlement_line set amount = 9999 where id = ?", row.id());
                      return null;
                    }))
        .as("明细行是外部事实，只能追加")
        .hasStackTraceContaining("permission denied");

    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute("delete from finance_settlement_line where id = ?", row.id());
                      return null;
                    }))
        .as("明细行是外部事实，只能追加")
        .hasStackTraceContaining("permission denied");
  }

  // ==========================================================================
  // 4. 汇总校验
  // ==========================================================================

  @Test
  void 明细合计等于结算单汇总_三个数逐位相等() {
    UUID settlementId = newSettlement("RPT-11");
    register(settlementId, FinanceTransactionType.ORDER_SALES, "500.00", "KEY-SUM-1", "ORDER-A");
    register(settlementId, FinanceTransactionType.REFUND, "20.00", "KEY-SUM-2", "ORDER-B");
    register(settlementId, FinanceTransactionType.COMMISSION, "30.00", "KEY-SUM-3");

    var check = as(() -> transactions.reconciliation(settlementId));

    // 上游那两行是 1000 − 100 = 900；本模块追加 500 − 20 − 30 = 450 ⇒ 合计 1350。
    assertThat(check.lineTotal()).isEqualByComparingTo("1350.0000");
    assertThat(check.summaryTotal()).isEqualByComparingTo("1350.0000");
    assertThat(check.settlementAmount()).isEqualByComparingTo("1350.0000");
    assertThat(check.balanced()).isTrue();
    assertThat(check.issues()).isEmpty();
    assertThat(check.lineCount()).isEqualTo(5);

    // 逐类别也对得上。
    for (var category : check.categories()) {
      assertThat(category.matched()).as(category.categoryLabel() + " 类别对不上").isTrue();
      assertThat(category.lineAmount()).isEqualByComparingTo(category.summaryAmount());
      assertThat(category.lineCount()).isEqualTo(category.summaryLineCount());
    }
  }

  @Test
  void 汇总校验真的能发现对不上的地方_不是一句恒真的套话() {
    UUID settlementId = newSettlement("RPT-12");

    // ① 符号被绕过：退款类别里出现正的贡献（归一没走）。
    // ② 混币种：一行 EUR 挂在 USD 的结算单下。
    // ③ 跨周期：一行日期落在结算周期之外。
    //    这三样都只能靠**绕过服务层直连 SQL** 造出来 —— 所以校验不是恒真的。
    as(
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_settlement_line(id,tenant_id,settlement_id,line_no,category,"
                  + " transaction_type,amount,raw_amount,currency,occurred_on,idempotency_key,source,"
                  + " description,created_at)"
                  + " values(?,?,?,90,'REFUND','REFUND',50,50,'USD',?::date,'KEY-BAD-1','MANUAL','x',"
                  + "?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              settlementId,
              LocalDate.of(2026, 10, 5),
              now);
          sql.execute(
              "insert into finance_settlement_line(id,tenant_id,settlement_id,line_no,category,"
                  + " transaction_type,amount,raw_amount,currency,occurred_on,idempotency_key,source,"
                  + " description,created_at)"
                  + " values(?,?,?,91,'SALES','ORDER_SALES',9,9,'EUR',?::date,'KEY-BAD-2','MANUAL','x',"
                  + "?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              settlementId,
              LocalDate.of(2026, 10, 5),
              now);
          sql.execute(
              "insert into finance_settlement_line(id,tenant_id,settlement_id,line_no,category,"
                  + " transaction_type,amount,raw_amount,currency,occurred_on,idempotency_key,source,"
                  + " description,created_at)"
                  + " values(?,?,?,92,'SALES','ORDER_SALES',7,7,'USD',?::date,'KEY-BAD-3','MANUAL','x',"
                  + "?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              settlementId,
              LocalDate.of(2026, 11, 20),
              now);
          return null;
        });

    var check = as(() -> transactions.reconciliation(settlementId));

    assertThat(check.signViolationLineCount()).as("退款为正 ⇒ 符号被绕过").isEqualTo(1);
    assertThat(check.currencyMismatchLineCount()).as("EUR 挂在 USD 单下").isEqualTo(1);
    assertThat(check.outsidePeriodLineCount()).as("日期落在结算周期之外").isEqualTo(1);
    assertThat(check.issues()).isNotEmpty();
    assertThat(check.issues()).anyMatch(issue -> issue.contains("符号"));
    assertThat(check.issues()).anyMatch(issue -> issue.contains("币种"));
    assertThat(check.issues()).anyMatch(issue -> issue.contains("结算周期"));
    // 金额层面仍然是平的（三者相等）⇒ 「合计」与「口径」是**两回事**，校验把两者分开报。
    assertThat(check.balanced()).isTrue();
  }

  // ==========================================================================
  // 5. 币种与上游边界
  // ==========================================================================

  @Test
  void 明细行币种必须等于结算单币种() {
    UUID settlementId = newSettlement("RPT-13");

    var input =
        new FinanceTransactionInput(
            FinanceTransactionSource.MANUAL,
            settlementId,
            FinanceTransactionType.ORDER_SALES,
            new BigDecimal("10.00"),
            "EUR",
            null,
            null,
            null,
            null,
            null,
            "KEY-CUR-1",
            null);
    assertThatThrownBy(() -> as(() -> transactions.register(input, actor())))
        .as("混币种的合计不是任何一个金额")
        .hasStackTraceContaining("必须等于结算单币种");
  }

  @Test
  void 平台交易报告同步当前不可产生_服务层拒绝并写明归属模块() {
    UUID settlementId = newSettlement("RPT-14");

    var input =
        new FinanceTransactionInput(
            FinanceTransactionSource.PLATFORM_SYNC,
            settlementId,
            FinanceTransactionType.ORDER_SALES,
            new BigDecimal("10.00"),
            null,
            null,
            null,
            null,
            null,
            null,
            "KEY-SYNC-1",
            null);
    assertThatThrownBy(() -> as(() -> transactions.register(input, actor())))
        .as("上游「卷 02 同步」未交付该端点 ⇒ 这个来源不可产生")
        .hasStackTraceContaining("卷 02 同步");

    // 能力声明里如实报出来，并点名归属模块。
    var caps = as(() -> transactions.capabilities());
    var sync =
        caps.capabilities().stream()
            .filter(c -> c.module().equals("卷 02 同步"))
            .findFirst()
            .orElseThrow();
    assertThat(sync.available()).isFalse();
    assertThat(sync.ownerModule()).isEqualTo("卷 02 同步");
  }

  @Test
  void 未交付的下游在能力声明里报available_false_并点名归属模块() {
    var caps = as(() -> transactions.capabilities());

    var reconciliation =
        caps.capabilities().stream()
            .filter(c -> c.module().equals("12.3.2.1"))
            .findFirst()
            .orElseThrow();
    assertThat(reconciliation.available()).isFalse();
    assertThat(reconciliation.ownerModule()).isEqualTo("12.3.2.1");

    var profit =
        caps.capabilities().stream()
            .filter(c -> c.module().equals("13.1.1.1"))
            .findFirst()
            .orElseThrow();
    assertThat(profit.available()).isFalse();
    assertThat(profit.ownerModule()).contains("13");

    // 能力声明里不许出现「利润数字」这种产物。
    assertThat(caps.capabilities()).noneMatch(c -> c.name().contains("利润数字"));
  }

  // ==========================================================================
  // 6. 平台交易报告导入（真解析 + 真入库 + 幂等 + 逐条告警）
  // ==========================================================================

  /** 一份平台交易报告 CSV（12.3.1.2 的输入）。 */
  static final String REPORT_CSV =
      String.join(
          "\n",
          "transaction_type,amount,currency,quantity,occurred_on,external_reference,"
              + "order_reference,idempotency_key,description",
          "ORDER_SALES,300.00,USD,2,2026-10-03,TX-A,ORDER-A,IDEM-A,\"Sales, order A\"",
          "REFUND,25.00,USD,1,2026-10-04,TX-B,ORDER-B,IDEM-B,Refund",
          "COMMISSION,45.00,USD,,2026-10-05,TX-C,,IDEM-C,Platform commission",
          "STORAGE_FEE,12.00,USD,,2026-10-06,TX-D,,IDEM-D,Storage fee",
          "WITHHOLDING_TAX,8.00,USD,,2026-10-07,TX-E,,IDEM-E,Marketplace facilitator tax");

  private FinanceTransactionImportResult doImport(UUID settlementId, String fileKey, String content) {
    var input =
        new FinanceTransactionImportInput(
            FinanceTransactionSource.MANUAL_UPLOAD,
            settlementId,
            fileKey,
            FinanceTransactionImportFormat.CSV,
            content,
            null);
    return as(() -> transactions.importReport(input, actor()));
  }

  @Test
  void 平台交易报告真的能解析入库_原文与解析版本留痕() {
    UUID settlementId = newSettlement("RPT-15");

    var result = doImport(settlementId, "TXN-REPORT-1", REPORT_CSV);

    assertThat(result.scanned()).isEqualTo(5);
    assertThat(result.imported()).isEqualTo(5);
    assertThat(result.duplicated()).isEqualTo(0);
    assertThat(result.skipped()).isEqualTo(0);
    assertThat(result.parseVersion()).isEqualTo(1);

    // 明细真的落库了：费用类三行**没有订单号**（允许无订单行的费用类明细）。
    var lines =
        as(
            () ->
                sql.fetch(
                    "select transaction_type, category, order_reference, idempotency_key"
                        + " from finance_settlement_line where settlement_id = ?"
                        + " and idempotency_key like 'IDEM-%' order by transaction_type",
                    settlementId));
    assertThat(lines).hasSize(5);
    assertThat(lines.map(r -> r.get("order_reference", String.class)))
        .filteredOn(v -> v == null)
        .hasSize(3);

    // 原文与解析版本留痕。
    assertThat(result.sourceFile()).isNotNull();
    assertThat(result.sourceFile().contentHash()).hasSize(64);
    assertThat(result.sourceFile().parseStatus()).isEqualTo("PARSED");
    assertThat(result.sourceFile().parseVersion()).isEqualTo(1);

    var stored =
        one(
            "select raw_content, parse_version from"
                + " finance_settlement_transaction_source_file where file_key = ?",
            "TXN-REPORT-1");
    assertThat(stored.get("raw_content", String.class)).isEqualTo(REPORT_CSV);
  }

  @Test
  void 同一份报告重复导入是幂等的_不新建行() {
    UUID settlementId = newSettlement("RPT-16");

    var first = doImport(settlementId, "TXN-REPORT-2", REPORT_CSV);
    assertThat(first.imported()).isEqualTo(5);

    var second = doImport(settlementId, "TXN-REPORT-2", REPORT_CSV);
    assertThat(second.imported()).as("重复导入不新建行").isEqualTo(0);
    assertThat(second.duplicated()).isEqualTo(5);

    // 库里还是 5 行（上游那两行不算在内）。
    assertThat(
            count(
                "select count(*) from finance_settlement_line where settlement_id = ?"
                    + " and idempotency_key like 'IDEM-%'",
                settlementId))
        .isEqualTo(5);
  }

  @Test
  void 行级错误只跳过那一行_文件级错误整体失败() {
    UUID settlementId = newSettlement("RPT-17");

    // ① 行级：金额不是数字 + 类型不认识 ⇒ 只跳过那两行，其余照进。
    var broken =
        String.join(
            "\n",
            "transaction_type,amount,idempotency_key",
            "ORDER_SALES,100.00,IDEM-OK",
            "ORDER_SALES,abc,IDEM-BAD-NUM",
            "NOT_A_TYPE,50.00,IDEM-BAD-TYPE");
    var result = doImport(settlementId, "TXN-BROKEN", broken);
    assertThat(result.scanned()).isEqualTo(3);
    assertThat(result.imported()).isEqualTo(1);
    assertThat(result.skipped()).isEqualTo(2);
    assertThat(result.warnings()).hasSize(2);

    // ② 文件级：缺列 ⇒ 整体失败，一条都不导入。
    var noTypeColumn = String.join("\n", "amount,idempotency_key", "100.00,IDEM-X");
    assertThatThrownBy(() -> doImport(settlementId, "TXN-NO-TYPE", noTypeColumn))
        .as("文件级错误整体失败")
        .hasStackTraceContaining("交易类型列");
  }

  @Test
  void 没有幂等键也没有外部流水号的行被拒_因为它没法保证幂等() {
    UUID settlementId = newSettlement("RPT-18");

    var content = String.join("\n", "transaction_type,amount", "ORDER_SALES,10.00");
    var result = doImport(settlementId, "TXN-NO-KEY", content);
    assertThat(result.imported()).isEqualTo(0);
    assertThat(result.skipped()).isEqualTo(1);
    assertThat(result.warnings().get(0)).contains("幂等键");
  }

  @Test
  void 没有幂等键时从外部流水号派生_一笔平台交易的自然身份就是它的交易号() {
    UUID settlementId = newSettlement("RPT-19");

    var content =
        String.join(
            "\n",
            "transaction_type,amount,external_reference",
            "ORDER_SALES,10.00,TX-DERIVE-1");
    var result = doImport(settlementId, "TXN-DERIVE", content);
    assertThat(result.imported()).isEqualTo(1);

    var stored =
        one(
            "select idempotency_key from finance_settlement_line where settlement_id = ?"
                + " and external_reference = 'TX-DERIVE-1'",
            settlementId);
    assertThat(stored.get(0, String.class)).isEqualTo("TX-DERIVE-1");
  }

  // ==========================================================================
  // 7. 列表 / 分页 / 筛选
  // ==========================================================================

  @Test
  void 真分页_两页不重叠() {
    UUID settlementId = newSettlement("RPT-20");
    for (int i = 1; i <= 7; i++) {
      register(settlementId, FinanceTransactionType.ORDER_SALES, "10.00", "KEY-PAGE-" + i);
    }

    var query =
        new FinanceTransactionQuery(
            settlementId, null, null, null, null, null, null, null, null, null, null, 0, 3);
    var first = as(() -> transactions.page(query));
    var second =
        as(
            () ->
                transactions.page(
                    new FinanceTransactionQuery(
                        settlementId, null, null, null, null, null, null, null, null, null, null, 1, 3)));

    assertThat(first.total()).isEqualTo(9); // 上游两行 + 本模块七行
    assertThat(first.items()).hasSize(3);
    assertThat(second.items()).hasSize(3);
    assertThat(first.items()).doesNotContainAnyElementsOf(second.items());
  }

  @Test
  void 筛选与汇总都真的生效() {
    // 上游账单只有一行退款 ⇒ 断言里只有本模块登记的行，不被上游干扰。
    UUID settlementId = newSettlement("RPT-21", REFUND_ONLY_CSV);
    register(settlementId, FinanceTransactionType.ORDER_SALES, "100.00", "KEY-F-1", "ORDER-X");
    register(settlementId, FinanceTransactionType.ORDER_SALES, "200.00", "KEY-F-2", "ORDER-Y");
    register(settlementId, FinanceTransactionType.COMMISSION, "30.00", "KEY-F-3");

    var onlySales =
        as(
            () ->
                transactions.page(
                    new FinanceTransactionQuery(
                        settlementId,
                        null,
                        FinanceTransactionType.ORDER_SALES,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        50)));
    assertThat(onlySales.items()).hasSize(2);
    // 汇总覆盖整个筛选结果集（不是当前页）。
    assertThat(onlySales.summaryByCurrency()).hasSize(1);
    assertThat(onlySales.summaryByCurrency().get(0).amount()).isEqualByComparingTo("300.0000");
    assertThat(onlySales.summaryByCurrency().get(0).orderlessCount()).isEqualTo(0);

    // 「只看没订单行的」⇒ 上游那行退款 + 本模块的佣金（两笔都没有订单号）。
    var onlyOrderless =
        as(
            () ->
                transactions.page(
                    new FinanceTransactionQuery(
                        settlementId,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Boolean.FALSE,
                        null,
                        0,
                        50)));
    assertThat(onlyOrderless.items()).hasSize(2);
    assertThat(onlyOrderless.items())
        .filteredOn(r -> r.transactionType() == FinanceTransactionType.COMMISSION)
        .hasSize(1);
  }

  @Test
  void 筛选项全部来自真实数据() {
    UUID settlementId = newSettlement("RPT-22");
    register(settlementId, FinanceTransactionType.WITHHOLDING_TAX, "5.00", "KEY-OPT");

    var options = as(() -> transactions.options());
    assertThat(options.transactionTypes()).contains("WITHHOLDING_TAX");
    assertThat(options.categories()).contains("TAX");
    assertThat(options.currencies()).contains("USD");
    assertThat(options.sources()).contains("MANUAL");
  }

  // ==========================================================================
  // 8. HTTP 层
  // ==========================================================================

  @Test
  void http_登记与拒绝走的是同一条鉴权路径() throws Exception {
    UUID settlementId = newSettlement("RPT-23");

    http.perform(
            post("/finance/transactions")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    ("{\"settlementId\":\"%s\",\"transactionType\":\"ORDER_SALES\",\"amount\":\"12.0000\","
                            + "\"idempotencyKey\":\"HTTP-KEY-1\"}")
                        .formatted(settlementId)))
        .andExpect(status().isOk());

    // 同一把幂等键再来一次 ⇒ 409。
    http.perform(
            post("/finance/transactions")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    ("{\"settlementId\":\"%s\",\"transactionType\":\"ORDER_SALES\",\"amount\":\"12.0000\","
                            + "\"idempotencyKey\":\"HTTP-KEY-1\"}")
                        .formatted(settlementId)))
        .andExpect(status().isConflict());

    // 没带令牌 ⇒ 401。
    http.perform(get("/finance/transactions")).andExpect(status().isUnauthorized());
  }

  @Test
  void http_汇总校验端点返回真实结果() throws Exception {
    UUID settlementId = newSettlement("RPT-24");
    register(settlementId, FinanceTransactionType.ORDER_SALES, "10.00", "KEY-HTTP-R");

    http.perform(
            get("/finance/transactions/reconciliation")
                .param("settlementId", settlementId.toString())
                .header("Authorization", "session"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.balanced").value(true))
        .andExpect(jsonPath("$.lineCount").value(3));
  }
}
