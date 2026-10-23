package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.ReceiptModels.*;
import com.starcon.finance.ReceivableModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDateTime;
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
 * 12.2.1.1 收款单的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖的是蓝图 12-finance.md 里 12.2.1.1 那几条**加粗句**与「关键约束」：
 *
 * <ul>
 *   <li>「一笔收款可核销多笔应收（合并回款），也可能是预收」⇒ 预留关系能挂多笔应收；预收与预留互斥；
 *   <li>「上游 = 12.3.1 结算」⇒ 平台回款来源**当前不可产生**，**不填 0、不造假结算**；
 *   <li>「下游 = 12.7.1 资金账户」⇒ 受管资金账户引用**恒为 null**，只有自由文本标识；
 *   <li>「输入：到账流水导入或手工登记」⇒ CSV / JSON 两条路都真入库，且**幂等**；
 *   <li>「金额一律 BigDecimal + NUMERIC，禁止浮点」⇒ 金额是四位小数的 numeric，逐位相等；
 *   <li>「**到账时间**」是核心字段 ⇒ 写回的值必须**逐位相等**（偏移 8 小时就是错账）。
 * </ul>
 *
 * <p><b>断言风格与本仓其它模块一致</b>：业务规则与拒绝理由走**服务层**
 * （{@code assertThatThrownBy(...).hasStackTraceContaining(...)}）， HTTP 层只断言**状态码**。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReceiptIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final List<String> PERMISSIONS =
      List.of("finance.receipt.read", "finance.receipt.create", "finance.receipt.update");

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
    // 本模块不读 trade（上游 12.3.1 结算未交付 ⇒ 平台回款不可产生）；
    // 这里给一个指向本地空端口的地址，保证 trade 端口 bean 能构造出来。
    r.add("TRADE_URL", () -> "http://127.0.0.1:1");
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired ReceiptService receipts;
  @Autowired ReceivableService receivables;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  /** 清库。用迁移用户（超管）连接：RLS 对超管不生效。 */
  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_receipt_event, finance_receipt_allocation, finance_receipt,"
              + " finance_receivable_event, finance_collection_task, finance_receivable_line,"
              + " finance_receivable, finance_receivable_policy restart identity cascade");
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
   * 用迁移用户（超管）直连跑一条**预期失败**的 SQL，返回错误消息。
   *
   * <p>触发器断言必须这么跑：超管不受 RLS 干扰，报出来的错就是**触发器**的错，
   * 而不是「RLS 挡住了这一行」这种把断言糊弄过去的错。
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

  /** 手工登记一张收款单（默认 USD 1000，东八区 08:30 到账）。 */
  FinanceReceiptRow createManual(String extraJson) {
    var input =
        inputOf(
            """
            {"currency":"USD","amount":1000,"receivedAt":"2026-10-01T08:30:00+08:00",
             "payerType":"CUSTOMER","payerName":"客户甲"%s}
            """
                .formatted(extraJson == null ? "" : extraJson));
    return as(() -> receipts.create(input, actor()));
  }

  FinanceReceiptInput inputOf(String body) {
    try {
      return json.readValue(body, FinanceReceiptInput.class);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  /** 造一笔**真实**的应收（走 12.1.1.1 的服务层），供预留核销用。 */
  FinanceReceivableRow createReceivable(String currency, String amount) {
    as(
        () ->
            receivables.updatePolicy(
                ReceivableSource.MANUAL,
                new FinanceReceivablePolicyInput(true, 30, "测试夹具"),
                actor()));
    var input =
        new FinanceReceivableInput(
            null,
            CounterpartyType.CUSTOMER,
            null,
            "客户甲",
            null,
            "",
            currency,
            null,
            null,
            null,
            null,
            List.of(new FinanceReceivableLineInput("明细", null, null, null, null, null, new BigDecimal(amount))));
    return as(() -> receivables.create(input, actor()));
  }

  // ================================================================ 登记

  @Test
  void 登记收款单后金额币种与未核销余额都对() {
    var receipt = createManual(null);
    assertThat(receipt.amount()).isEqualByComparingTo("1000.0000");
    assertThat(receipt.currency()).isEqualTo("USD");
    assertThat(receipt.status()).isEqualTo(ReceiptStatus.RECORDED);
    // 核销（12.2.1.2）未交付 ⇒ 已核销 0、未核销余额恒等于收款金额。
    assertThat(receipt.settledAmount()).isEqualByComparingTo("0.0000");
    assertThat(receipt.unsettledAmount()).isEqualByComparingTo("1000.0000");
    assertThat(receipt.source()).isEqualTo(ReceiptSource.MANUAL);
  }

  @Test
  void 金额落库是四位小数的numeric而不是浮点() {
    createManual(",\"amount\":0.1");
    var column =
        one(
            "select data_type, numeric_scale from information_schema.columns"
                + " where table_name = 'finance_receipt' and column_name = 'amount'");
    assertThat(column.get("data_type", String.class)).isEqualTo("numeric");
    assertThat(column.get("numeric_scale", Integer.class)).isEqualTo(4);
    // 逐位相等：金额是 0.1000，不是 0.1、也不是 0.1000000000000000055511151231257827。
    assertThat(
            one("select amount from finance_receipt").get(0, BigDecimal.class).toPlainString())
        .isEqualTo("0.1000");
  }

  @Test
  void 到账时间写回的值逐位相等不会偏八小时() {
    var receipt = createManual(null);
    // 2026-10-01T08:30:00+08:00 == 2026-10-01T00:30:00Z
    assertThat(receipt.receivedAt().toInstant()).isEqualTo(Instant.parse("2026-10-01T00:30:00Z"));
    assertThat(receipt.receivedAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime())
        .isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 30));
    // 数据层再验一次（读的是 timestamptz 本身，不是应用层的往返）。
    var stored =
        one("select received_at from finance_receipt").get(0, OffsetDateTime.class);
    assertThat(stored.toInstant()).isEqualTo(Instant.parse("2026-10-01T00:30:00Z"));
  }

  @Test
  void 没写到账时间必须被拒而不是默默填当前时刻() {
    var input =
        inputOf(
            """
            {"currency":"USD","amount":1000,"payerType":"CUSTOMER","payerName":"客户甲"}
            """);
    assertThatThrownBy(() -> as(() -> receipts.create(input, actor())))
        .as("默认填当前时刻会把「昨天到账的钱」记成今天到账")
        .hasStackTraceContaining("到账时间");
    assertThat(count("select count(*) from finance_receipt")).isZero();
  }

  @Test
  void 金额必须大于零且必须写明币种() {
    var zero =
        inputOf(
            """
            {"currency":"USD","amount":0,"receivedAt":"2026-10-01T08:30:00+08:00",
             "payerType":"CUSTOMER","payerName":"客户甲"}
            """);
    assertThatThrownBy(() -> as(() -> receipts.create(zero, actor())))
        .hasStackTraceContaining("必须大于 0");
    var badCurrency =
        inputOf(
            """
            {"currency":"US","amount":10,"receivedAt":"2026-10-01T08:30:00+08:00",
             "payerType":"CUSTOMER","payerName":"客户甲"}
            """);
    assertThatThrownBy(() -> as(() -> receipts.create(badCurrency, actor())))
        .hasStackTraceContaining("币种");
  }

  // ================================================================ 上游缺口（缺口如实标注）

  @Test
  void 平台回款来源当前不可产生且必须写明归属模块() {
    var input =
        inputOf(
            """
            {"source":"PLATFORM_SETTLEMENT","currency":"USD","amount":1000,
             "receivedAt":"2026-10-01T08:30:00+08:00","payerType":"PLATFORM","payerName":"Amazon"}
            """);
    assertThatThrownBy(() -> as(() -> receipts.create(input, actor())))
        .hasStackTraceContaining("12.3.1");
    assertThat(count("select count(*) from finance_receipt"))
        .as("被拒的登记不得留下任何行（绝不填 0、也绝不造假结算）")
        .isZero();
  }

  @Test
  void 受管资金账户与结算单号恒为null() {
    var receipt = createManual(",\"fundAccountLabel\":\"招商银行 6222****1234\"");
    assertThat(receipt.fundAccountRef()).as("12.7.1.1 未交付 ⇒ 受管账户引用恒为 null").isNull();
    assertThat(receipt.settlementNo()).as("12.3.1.1 未交付 ⇒ 结算单号恒为 null").isNull();
    // 自由文本标识可以填（它**不是**受管资金账户）。
    assertThat(receipt.fundAccountLabel()).isEqualTo("招商银行 6222****1234");
    // 数据层也钉死了：直接写 SQL 也写不进去。
    UUID id = one("select id from finance_receipt").get(0, UUID.class);
    assertThat(dbError("update finance_receipt set fund_account_ref = ? where id = ?", UUID.randomUUID(), id))
        .contains("fund_account_ref");
    assertThat(dbError("update finance_receipt set settlement_no = 'ST-1' where id = ?", id))
        .contains("settlement_no");
  }

  @Test
  void 能力声明把三个缺口分别归到具体模块() {
    var caps = as(() -> receipts.capabilities());
    var byName = caps.capabilities().stream().collect(java.util.stream.Collectors.toMap(c -> c.name(), c -> c));
    assertThat(byName.get("平台结算 → 收款（平台回款）").available()).isFalse();
    assertThat(byName.get("平台结算 → 收款（平台回款）").ownerModule()).isEqualTo("12.3.1.1");
    assertThat(byName.get("资金账户（受管账户）").available()).isFalse();
    assertThat(byName.get("资金账户（受管账户）").ownerModule()).isEqualTo("12.7.1.1");
    assertThat(byName.get("核销（收款核销到应收）").available()).isFalse();
    assertThat(byName.get("核销（收款核销到应收）").ownerModule()).isEqualTo("12.2.1.2");
    // 核销未交付 ⇒ 未核销余额恒等于收款金额，这条口径必须写在声明里。
    assertThat(byName.get("核销（收款核销到应收）").detail()).contains("恒等于收款金额");
  }

  // ================================================================ 到账流水导入

  @Test
  void csv导入真的入库而且第二次导入命中幂等() {
    var csv =
        """
        source_reference,amount,currency,received_at,payer_name,payer_type,note
        BANK-001,1500.00,USD,2026-10-01T08:30:00+08:00,ACME Trading Ltd,CUSTOMER,货款
        BANK-002,250.50,EUR,2026-10-02T10:00:00Z,Euro Buyer GmbH,CUSTOMER,尾款
        """;
    var input = new FinanceReceiptImportInput(ReceiptSource.BANK_TRANSFER, "bank", ImportFormat.CSV, csv);
    var first = as(() -> receipts.importFlow(input, actor()));
    assertThat(first.scanned()).isEqualTo(2);
    assertThat(first.imported()).isEqualTo(2);
    assertThat(first.skipped()).isZero();
    assertThat(first.warnings()).isEmpty();
    assertThat(count("select count(*) from finance_receipt")).isEqualTo(2);

    // 幂等：同一来源系统 + 同一来源流水号再导一次，不会长出第二张单。
    var second = as(() -> receipts.importFlow(input, actor()));
    assertThat(second.scanned()).isEqualTo(2);
    assertThat(second.imported()).isZero();
    assertThat(second.skipped()).isEqualTo(2);
    assertThat(count("select count(*) from finance_receipt")).isEqualTo(2);

    // 金额逐位相等（不是浮点）。
    var amounts =
        as(
            () ->
                sql.fetch("select amount from finance_receipt order by currency")
                    .map(r -> r.get(0, BigDecimal.class).toPlainString()));
    assertThat(amounts).containsExactly("250.5000", "1500.0000");
  }

  @Test
  void json导入真的入库() {
    var body =
        """
        {"rows":[
          {"source_reference":"PP-9","amount":"99.9900","currency":"usd",
           "received_at":"2026-09-30T23:59:59+08:00","payer_name":"PayPal 买家"},
          {"source_reference":"ST-9","amount":10,"currency":"USD",
           "received_at":"2026-09-30T15:59:59Z","payer_name":"Stripe 买家"}
        ]}
        """;
    var input = new FinanceReceiptImportInput(ReceiptSource.PAYMENT_GATEWAY, "paypal", ImportFormat.JSON, body);
    var result = as(() -> receipts.importFlow(input, actor()));
    assertThat(result.scanned()).isEqualTo(2);
    assertThat(result.imported()).isEqualTo(2);
    assertThat(count("select count(*) from finance_receipt")).isEqualTo(2);
    var receipt = one("select * from finance_receipt where source_reference = 'PP-9'");
    assertThat(receipt.get("currency", String.class)).isEqualTo("USD");
    // 到账时间同样逐位相等。
    assertThat(receipt.get("received_at", OffsetDateTime.class).toInstant())
        .isEqualTo(Instant.parse("2026-09-30T15:59:59Z"));
  }

  @Test
  void csv行级错误只跳过那一行并逐条说明原因() {
    var csv =
        """
        source_reference,amount,currency,received_at,payer_name
        BANK-OK,10,USD,2026-10-01T08:30:00+08:00,甲
        BANK-BAD-AMOUNT,abc,USD,2026-10-01T08:30:00+08:00,乙
        BANK-BAD-CURRENCY,10,US,2026-10-01T08:30:00+08:00,丙
        ,10,USD,2026-10-01T08:30:00+08:00,丁
        """;
    var input = new FinanceReceiptImportInput(ReceiptSource.BANK_TRANSFER, "bank", ImportFormat.CSV, csv);
    var result = as(() -> receipts.importFlow(input, actor()));
    assertThat(result.scanned()).isEqualTo(4);
    assertThat(result.imported()).isEqualTo(1);
    assertThat(result.skipped()).isEqualTo(3);
    assertThat(result.warnings()).hasSize(3);
    assertThat(result.warnings()).anyMatch(w -> w.contains("金额不是数字"));
    assertThat(result.warnings()).anyMatch(w -> w.contains("币种必须是三个字母"));
    assertThat(result.warnings()).anyMatch(w -> w.contains("来源流水号"));
    assertThat(count("select count(*) from finance_receipt")).isEqualTo(1);
  }

  @Test
  void csv缺必需列是文件级错误整体失败一条都不导入() {
    var csv =
        """
        amount,currency,received_at
        10,USD,2026-10-01T08:30:00+08:00
        """;
    var input = new FinanceReceiptImportInput(ReceiptSource.BANK_TRANSFER, "bank", ImportFormat.CSV, csv);
    assertThatThrownBy(() -> as(() -> receipts.importFlow(input, actor())))
        .hasStackTraceContaining("缺少必需的列");
    assertThat(count("select count(*) from finance_receipt")).isZero();
  }

  @Test
  void 到账时间没带时区偏移的那一行被跳过并说清会偏八小时() {
    var csv =
        """
        source_reference,amount,currency,received_at
        BANK-NO-TZ,10,USD,2026-10-01T08:30:00
        """;
    var input = new FinanceReceiptImportInput(ReceiptSource.BANK_TRANSFER, "bank", ImportFormat.CSV, csv);
    var result = as(() -> receipts.importFlow(input, actor()));
    // 行级错误 ⇒ 只跳过那一行，并逐条说明原因（绝不默默按服务器本地时区补齐）。
    assertThat(result.scanned()).isEqualTo(1);
    assertThat(result.imported()).isZero();
    assertThat(result.skipped()).isEqualTo(1);
    assertThat(result.warnings()).hasSize(1);
    assertThat(result.warnings().get(0)).contains("时区偏移");
    assertThat(count("select count(*) from finance_receipt")).isZero();
  }

  @Test
  void 导入备注里带逗号不会把金额列冲掉() {
    var csv =
        """
        source_reference,amount,currency,received_at,payer_name,note
        BANK-CSV,12.34,USD,2026-10-01T08:30:00+08:00,"ACME, Ltd","货款, 第一笔"
        """;
    var input = new FinanceReceiptImportInput(ReceiptSource.BANK_TRANSFER, "bank", ImportFormat.CSV, csv);
    var result = as(() -> receipts.importFlow(input, actor()));
    assertThat(result.imported()).isEqualTo(1);
    assertThat(result.warnings()).isEmpty();
    var receipt = one("select * from finance_receipt where source_reference = 'BANK-CSV'");
    assertThat(receipt.get("amount", BigDecimal.class)).isEqualByComparingTo("12.3400");
    assertThat(receipt.get("payer_name", String.class)).isEqualTo("ACME, Ltd");
    assertThat(receipt.get("note", String.class)).isEqualTo("货款, 第一笔");
  }

  // ================================================================ 预留核销（合并回款）

  @Test
  void 一笔收款可以预留到多笔应收且预留不减少未核销余额() {
    var receipt = createManual(null);
    var first = createReceivable("USD", "300");
    var second = createReceivable("USD", "400");

    as(
        () ->
            receipts.reserveAllocation(
                receipt.id(),
                new FinanceReceiptAllocationInput(first.id(), new BigDecimal("300"), "合并回款"),
                actor()));
    var allocation =
        as(
            () ->
                receipts.reserveAllocation(
                    receipt.id(),
                    new FinanceReceiptAllocationInput(second.id(), new BigDecimal("400"), "合并回款"),
                    actor()));
    assertThat(allocation.reservedAmount()).isEqualByComparingTo("400.0000");
    assertThat(allocation.status()).isEqualTo(AllocationStatus.RESERVED);

    var detail = as(() -> receipts.detail(receipt.id()));
    assertThat(detail.allocations()).hasSize(2);

    // ⚠️ **预留不是核销**：未核销余额仍然是 1000（没有任何核销能减它）。
    assertThat(detail.receipt().unsettledAmount()).isEqualByComparingTo("1000.0000");
    assertThat(detail.receipt().settledAmount()).isEqualByComparingTo("0.0000");
  }

  @Test
  void 跨币种的预留核销被拒() {
    var receipt = createManual(null);
    var eur = createReceivable("EUR", "300");
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        receipts.reserveAllocation(
                            receipt.id(),
                            new FinanceReceiptAllocationInput(eur.id(), new BigDecimal("300"), null),
                            actor())))
        .hasStackTraceContaining("跨币种");
    assertThat(count("select count(*) from finance_receipt_allocation")).isZero();
  }

  @Test
  void 预留超过收款金额或超过应收未核销余额都被拒() {
    var receipt = createManual(null);
    var receivable = createReceivable("USD", "300");
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        receipts.reserveAllocation(
                            receipt.id(),
                            new FinanceReceiptAllocationInput(
                                receivable.id(), new BigDecimal("500"), null),
                            actor())))
        .hasStackTraceContaining("未核销余额");
    // 另一条：预留总额超过收款金额。
    var big = createReceivable("USD", "5000");
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        receipts.reserveAllocation(
                            receipt.id(),
                            new FinanceReceiptAllocationInput(
                                big.id(), new BigDecimal("5000"), null),
                            actor())))
        .hasStackTraceContaining("超过收款单");
    assertThat(count("select count(*) from finance_receipt_allocation")).isZero();
  }

  @Test
  void 预收单不能有核销预留() {
    var receipt = createManual(",\"advanceFlag\":true");
    assertThat(receipt.advanceFlag()).isTrue();
    var receivable = createReceivable("USD", "300");
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        receipts.reserveAllocation(
                            receipt.id(),
                            new FinanceReceiptAllocationInput(receivable.id(), new BigDecimal("300"), null),
                            actor())))
        .hasStackTraceContaining("预收");
    assertThat(count("select count(*) from finance_receipt_allocation")).isZero();
  }

  @Test
  void 重复预留被拒而释放之后可以重新预留() {
    var receipt = createManual(null);
    var receivable = createReceivable("USD", "300");
    var allocation =
        as(
            () ->
                receipts.reserveAllocation(
                    receipt.id(),
                    new FinanceReceiptAllocationInput(receivable.id(), new BigDecimal("300"), null),
                    actor()));
    // 重复预留是同一次预留。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        receipts.reserveAllocation(
                            receipt.id(),
                            new FinanceReceiptAllocationInput(
                                receivable.id(), new BigDecimal("300"), null),
                            actor())))
        .hasStackTraceContaining("重复预留");

    var released = as(() -> receipts.releaseAllocation(receipt.id(), allocation.id(), actor()));
    assertThat(released.status()).isEqualTo(AllocationStatus.RELEASED);
    // 释放之后**可以**重新预留（计划变了：先撤再改）。
    var again =
        as(
            () ->
                receipts.reserveAllocation(
                    receipt.id(),
                    new FinanceReceiptAllocationInput(receivable.id(), new BigDecimal("200"), null),
                    actor()));
    assertThat(again.status()).isEqualTo(AllocationStatus.RESERVED);
    assertThat(again.reservedAmount()).isEqualByComparingTo("200.0000");
  }

  // ================================================================ 作废 / 冻结

  @Test
  void 作废必须写理由且作废后整单冻结() {
    var receipt = createManual(null);
    assertThatThrownBy(
            () ->
                as(() -> receipts.voidReceipt(receipt.id(), new FinanceReceiptVoidInput(""), actor())))
        .hasStackTraceContaining("理由");
    var voided =
        as(
            () ->
                receipts.voidReceipt(
                    receipt.id(), new FinanceReceiptVoidInput("登记错了，作废"), actor()));
    assertThat(voided.status()).isEqualTo(ReceiptStatus.VOID);
    UUID id = one("select id from finance_receipt").get(0, UUID.class);
    assertThat(dbError("update finance_receipt set note = '改一下' where id = ?", id))
        .contains("终态");
  }

  @Test
  void 到账事实登记后改不动() {
    var receipt = createManual(null);
    UUID id = one("select id from finance_receipt").get(0, UUID.class);
    assertThat(dbError("update finance_receipt set amount = 999 where id = ?", id))
        .contains("不可再改");
    assertThat(dbError("update finance_receipt set received_at = now() where id = ?", id))
        .contains("不可再改");
  }

  // ================================================================ 未核销余额

  @Test
  void 未核销余额按币种分开且作废的不计入() {
    createManual(null);
    createManual(",\"currency\":\"EUR\",\"amount\":250,\"payerName\":\"欧洲客户\"");
    var voided = createManual(",\"currency\":\"EUR\",\"amount\":999,\"payerName\":\"作废客户\"");
    as(
        () ->
            receipts.voidReceipt(
                voided.id(), new FinanceReceiptVoidInput("登记错了"), actor()));

    var balance = as(() -> receipts.balance());
    assertThat(balance.byCurrency()).hasSize(2);
    var usd = balance.byCurrency().stream().filter(s -> s.currency().equals("USD")).findFirst().orElseThrow();
    var eur = balance.byCurrency().stream().filter(s -> s.currency().equals("EUR")).findFirst().orElseThrow();
    assertThat(usd.unsettledAmount()).isEqualByComparingTo("1000.0000");
    assertThat(eur.unsettledAmount()).isEqualByComparingTo("250.0000");
    // 作废的那张不计入在册张数。
    assertThat(balance.receiptCount()).isEqualTo(2);
  }

  @Test
  void 预收金额在未核销余额里单列() {
    createManual(",\"advanceFlag\":true,\"payerName\":\"预收客户\"");
    createManual(",\"payerName\":\"普通客户\"");
    var balance = as(() -> receipts.balance());
    var usd = balance.byCurrency().stream().filter(s -> s.currency().equals("USD")).findFirst().orElseThrow();
    assertThat(usd.advanceCount()).isEqualTo(1);
    assertThat(usd.advanceAmount()).isEqualByComparingTo("1000.0000");
    assertThat(balance.advanceCount()).isEqualTo(1);
  }

  // ================================================================ 列表 / 筛选

  @Test
  void 列表是真分页真筛选且汇总覆盖整个结果集() {
    createManual(",\"payerName\":\"甲\"");
    createManual(",\"payerName\":\"乙\",\"currency\":\"EUR\"");
    createManual(",\"payerName\":\"丙\",\"currency\":\"EUR\"");

    var all = as(() -> receipts.page(new ReceiptQuery(null, null, null, null, null, null, null, null, null, 0, 50)));
    assertThat(all.total()).isEqualTo(3);
    assertThat(all.items()).hasSize(3);
    // 汇总覆盖整个筛选结果集（不是当前页）。
    assertThat(all.summaryByCurrency()).hasSize(2);

    var filtered = as(() -> receipts.page(new ReceiptQuery(null, null, null, "EUR", null, null, null, null, null, 0, 50)));
    assertThat(filtered.total()).isEqualTo(2);
    assertThat(filtered.summaryByCurrency()).hasSize(1);

    var paged = as(() -> receipts.page(new ReceiptQuery(null, null, null, null, null, null, null, null, null, 1, 2)));
    assertThat(paged.items()).hasSize(1);
    assertThat(paged.total()).isEqualTo(3);

    var keyword = as(() -> receipts.page(new ReceiptQuery(null, null, null, null, null, null, null, null, "甲", 0, 50)));
    assertThat(keyword.total()).isEqualTo(1);
  }

  @Test
  void 筛选项来自真实数据() {
    createManual(",\"sourceSystem\":\"bank\"");
    var options = as(() -> receipts.options());
    assertThat(options.currencies()).contains("USD");
    assertThat(options.sourceSystems()).contains("bank");
    assertThat(options.statuses()).contains("RECORDED");
  }
  // ================================================================ HTTP 层

  @Test
  void http登记收款单走通端到端() throws Exception {
    var body =
        """
        {"currency":"USD","amount":100,"receivedAt":"2026-10-01T08:30:00+08:00",
         "payerType":"CUSTOMER","payerName":"HTTP 客户"}
        """;
    http.perform(
            post("/finance/receipts")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
    assertThat(count("select count(*) from finance_receipt")).isEqualTo(1);
  }

  @Test
  void http层平台回款被拒是400() throws Exception {
    var body =
        """
        {"source":"PLATFORM_SETTLEMENT","currency":"USD","amount":100,
         "receivedAt":"2026-10-01T08:30:00+08:00","payerType":"PLATFORM","payerName":"Amazon"}
        """;
    http.perform(
            post("/finance/receipts")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
    assertThat(count("select count(*) from finance_receipt")).isZero();
  }

  @Test
  void 未认证的请求拿不到收款单() throws Exception {
    http.perform(get("/finance/receipts")).andExpect(status().isUnauthorized());
  }
}
