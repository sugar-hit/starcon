package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.ReceiptModels.*;
import com.starcon.finance.ReceivableModels.*;
import com.starcon.finance.WriteOffModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.Instant;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 12.2.1.2 核销的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖的是蓝图 12-finance.md 里 12.2.1.2 那几条**加粗句**与「关键约束」：
 *
 * <ul>
 *   <li>「**核销必须精确到明细行**，只核销到订单头无法处理部分回款与折让」⇒ 分配引用的是
 *       {@code finance_receivable_line.id}，行级 {@code settled_amount} 真的被写到；
 *   <li>「自动匹配（**金额+订单号+日期**）」⇒ 三个条件**缺一不可**，金额相同**不**构成匹配；
 *   <li>「支持**部分核销、合并核销、预收挂账、核销撤销**」⇒ 四种能力各有用例；
 *   <li>「输出：核销记录 + 应收余额更新 + **预收余额**」⇒ 预收余额真的算出来；
 *   <li>「金额一律 BigDecimal + NUMERIC，禁止浮点」⇒ 金额是四位小数的 numeric，逐位相等；
 *   <li>「时间字段」⇒ 核销时刻写回的值**逐位相等**（偏 8 小时就是错账）。
 * </ul>
 *
 * <p><b>断言风格与本仓其它模块一致</b>：业务规则与拒绝理由走**服务层**
 * （{@code assertThatThrownBy(...).hasStackTraceContaining(...)}），DB 守卫另有直连用例。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WriteOffIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final List<String> PERMISSIONS =
      List.of("finance.writeoff.read", "finance.writeoff.create", "finance.writeoff.update");

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
    r.add("TRADE_URL", () -> "http://127.0.0.1:1");
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired ReceiptService receipts;
  @Autowired ReceivableService receivables;
  @Autowired WriteOffService writeOffs;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_write_off_event, finance_advance_ledger,"
              + " finance_write_off_allocation, finance_write_off,"
              + " finance_receipt_event, finance_receipt_allocation, finance_receipt,"
              + " finance_receivable_event, finance_collection_task, finance_receivable_line,"
              + " finance_receivable, finance_receivable_policy restart identity cascade");
    }
  }

  <T> T as(Supplier<T> work) {
    return tenants.run(tenant, work);
  }

  Record one(String statement, Object... binds) {
    return as(() -> sql.fetchOne(statement, binds));
  }

  long count(String statement, Object... binds) {
    return as(() -> sql.fetchOne(statement, binds).get(0, Long.class));
  }

  AccessService.Access actor() {
    return actors.get("session");
  }

  /** 用迁移用户（超管）直连跑一条**预期失败**的 SQL，返回错误消息（RLS 不干扰，报出来的就是触发器的错）。 */
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

  /** 登记一张收款单（默认 USD 1000，到账时刻 = 当前 UTC —— 自动匹配的日期条件要用它）。 */
  FinanceReceiptRow createReceipt(String amount, String currency) {
    var input =
        new FinanceReceiptInput(
            ReceiptSource.MANUAL,
            null,
            null,
            currency,
            new BigDecimal(amount),
            OffsetDateTime.now(ZoneOffset.UTC).withNano(0),
            PayerType.CUSTOMER,
            "客户甲",
            null,
            null,
            false,
            "测试夹具");
    return as(() -> receipts.create(input, actor()));
  }

  /** 造一张**真实**的应收（走 12.1.1.1 的服务层），返回 [应收单, 明细行 id...]。 */
  List<Object> createReceivable(String currency, String... lines) {
    as(
        () ->
            receivables.updatePolicy(
                ReceivableSource.MANUAL,
                new FinanceReceivablePolicyInput(true, 30, "测试夹具"),
                actor()));
    var lineInputs = new java.util.ArrayList<FinanceReceivableLineInput>();
    for (String spec : lines) {
      // 形如 "描述|订单号|金额"
      var parts = spec.split("\\|");
      lineInputs.add(
          new FinanceReceivableLineInput(
              parts[0], null, parts[1].isBlank() ? null : parts[1], null, null, null,
              new BigDecimal(parts[2])));
    }
    var input =
        new FinanceReceivableInput(
            ReceivableSource.MANUAL,
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
            lineInputs);
    var head = as(() -> receivables.create(input, actor()));
    var lineIds =
        as(
                () ->
                    sql.fetch(
                            "select id from finance_receivable_line where receivable_id = ?"
                                + " order by line_no",
                            head.id())
                        .map(r -> r.get(0, UUID.class)));
    var all = new java.util.ArrayList<Object>();
    all.add(head);
    all.addAll(lineIds);
    return all;
  }

  /** 一次人工核销（一条分配）。 */
  FinanceWriteOffDetail settle(FinanceReceiptRow receipt, UUID lineId, String amount) {
    var input =
        new FinanceWriteOffInput(
            receipt.id(),
            List.of(new FinanceWriteOffAllocationInput(AllocationType.SETTLEMENT, lineId, new BigDecimal(amount), null)),
            "测试核销",
            OffsetDateTime.now(ZoneOffset.UTC).withNano(0));
    return as(() -> writeOffs.create(input, actor()));
  }

  // ================================================================ 部分核销 / 合并核销

  @Test
  void 部分核销落在明细行上且应收单头状态跟着变() {
    var receipt = createReceipt("1000", "USD");
    var fixture = createReceivable("USD", "明细一|ORD-1|200", "明细二|ORD-2|300");
    var head = (FinanceReceivableRow) fixture.get(0);
    var lineId = (UUID) fixture.get(1);

    var detail = settle(receipt, lineId, "100");

    assertThat(detail.writeOff().settlementAmount()).isEqualByComparingTo("100.0000");
    assertThat(detail.writeOff().totalAmount()).isEqualByComparingTo("100.0000");
    assertThat(detail.writeOff().mode()).isEqualTo(WriteOffMode.MANUAL);
    assertThat(detail.writeOff().status()).isEqualTo(WriteOffStatus.POSTED);

    // 核销必须精确到明细行：分配引用的是**行** id。
    assertThat(detail.allocations()).hasSize(1);
    var allocation = detail.allocations().get(0);
    assertThat(allocation.receivableLineId()).isEqualTo(lineId);
    assertThat(allocation.receivableId()).isEqualTo(head.id());
    assertThat(allocation.orderNo()).isEqualTo("ORD-1");

    // 行级已核销金额真的被写了（**部分**核销 ⇒ 行的状态是「部分核销」）。
    var line =
        one("select settled_amount, settlement_status from finance_receivable_line where id = ?", lineId);
    assertThat(line.get("settled_amount", BigDecimal.class)).isEqualByComparingTo("100.0000");
    assertThat(line.get("settlement_status", String.class)).isEqualTo("PARTIALLY_SETTLED");

    // 单头的已核销金额与状态由上游派生触发器带出来（本模块不写单头）。
    var headRow = one("select settled_amount, status from finance_receivable where id = ?", head.id());
    assertThat(headRow.get("settled_amount", BigDecimal.class)).isEqualByComparingTo("100.0000");
    assertThat(headRow.get("status", String.class)).isEqualTo("PARTIALLY_SETTLED");

    // 收款单的未核销余额跟着变（生成列 amount − settled_amount）。
    var receiptRow = one("select settled_amount, unsettled_amount from finance_receipt where id = ?", receipt.id());
    assertThat(receiptRow.get("settled_amount", BigDecimal.class)).isEqualByComparingTo("100.0000");
    assertThat(receiptRow.get("unsettled_amount", BigDecimal.class)).isEqualByComparingTo("900.0000");
  }

  @Test
  void 合并核销一笔收款核销多笔应收明细行() {
    var receipt = createReceipt("1000", "USD");
    var first = createReceivable("USD", "明细一|ORD-1|200");
    var second = createReceivable("USD", "明细二|ORD-2|300");
    var headOne = (FinanceReceivableRow) first.get(0);
    var headTwo = (FinanceReceivableRow) second.get(0);
    var lineOne = (UUID) first.get(1);
    var lineTwo = (UUID) second.get(1);

    var input =
        new FinanceWriteOffInput(
            receipt.id(),
            List.of(
                new FinanceWriteOffAllocationInput(AllocationType.SETTLEMENT, lineOne, new BigDecimal("200"), null),
                new FinanceWriteOffAllocationInput(AllocationType.SETTLEMENT, lineTwo, new BigDecimal("300"), null)),
            "合并回款",
            OffsetDateTime.now(ZoneOffset.UTC).withNano(0));
    var detail = as(() -> writeOffs.create(input, actor()));

    assertThat(detail.writeOff().totalAmount()).isEqualByComparingTo("500.0000");
    assertThat(detail.allocations()).hasSize(2);
    assertThat(detail.allocations()).extracting(a -> a.receivableId())
        .containsExactlyInAnyOrder(headOne.id(), headTwo.id());
    assertThat(count("select count(*) from finance_write_off")).isEqualTo(1);
    assertThat(count("select count(*) from finance_write_off_allocation")).isEqualTo(2);
  }

  @Test
  void 核销金额不能超过明细行的未核销余额() {
    var receipt = createReceipt("1000", "USD");
    var fixture = createReceivable("USD", "明细|ORD-1|100");
    var lineId = (UUID) fixture.get(1);
    assertThatThrownBy(() -> settle(receipt, lineId, "150"))
        .as("超核销会让行的 settled_amount 大于 amount")
        .hasStackTraceContaining("未核销余额");
    assertThat(count("select count(*) from finance_write_off")).isZero();
  }

  @Test
  void 核销必须精确到明细行不接受只给单头() {
    var receipt = createReceipt("1000", "USD");
    createReceivable("USD", "明细|ORD-1|100");
    var input =
        new FinanceWriteOffInput(
            receipt.id(),
            List.of(new FinanceWriteOffAllocationInput(AllocationType.SETTLEMENT, null, new BigDecimal("100"), null)),
            "只给单头",
            null);
    assertThatThrownBy(() -> as(() -> writeOffs.create(input, actor())))
        .as("只核销到订单头无法处理部分回款与折让")
        .hasStackTraceContaining("明细行");
  }

  @Test
  void 跨币种不允许核销() {
    var receipt = createReceipt("1000", "USD");
    var fixture = createReceivable("EUR", "明细|ORD-1|100");
    var lineId = (UUID) fixture.get(1);
    assertThatThrownBy(() -> settle(receipt, lineId, "50"))
        .as("不同币种的金额加起来不是任何一个金额")
        .hasStackTraceContaining("跨币种");
  }

  @Test
  void 草稿应收单不可核销() {
    var receipt = createReceipt("1000", "USD");
    // 直接插一条**草稿**应收（上游守卫要求新建必须先是草稿），
    // 草稿还没确认 ⇒ 核销没有意义（确认时才校验明细行与合计）。
    var receivableId = UUID.randomUUID();
    var lineId = UUID.randomUUID();
    as(
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_receivable(id,tenant_id,receivable_no,source,counterparty_type,"
                  + " currency,status,due_date,confirmed_at,created_at,updated_at)"
                  + " values (?,?,?,'MANUAL','CUSTOMER','USD','DRAFT',?::date,?::timestamptz,"
                  + " ?::timestamptz,?::timestamptz)",
              receivableId,
              tenant,
              "DRAFT-1",
              LocalDate.now(ZoneOffset.UTC).plusDays(30),
              now,
              now,
              now);
          sql.execute(
              "insert into finance_receivable_line(id,tenant_id,receivable_id,line_no,amount,"
                  + " description,order_no,created_at,updated_at)"
                  + " values (?,?,?,1,100,'明细','ORD-DRAFT',?::timestamptz,?::timestamptz)",
              lineId,
              tenant,
              receivableId,
              now,
              now);
          return null;
        });
    assertThatThrownBy(() -> settle(receipt, lineId, "50"))
        .as("草稿应收还没确认，核销没有意义")
        .hasStackTraceContaining("确认");
    assertThat(count("select count(*) from finance_write_off")).isZero();
  }

  // ================================================================ 自动匹配

  @Test
  void 自动匹配要金额订单号日期三者同时成立() {
    var receipt = createReceipt("1000", "USD");
    createReceivable("USD", "明细|ORD-1|100");

    // ① 订单号不对 ⇒ 不命中
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        writeOffs.autoMatch(
                            new FinanceWriteOffAutoMatchInput(
                                receipt.id(), "ORD-999", null, null, null, null),
                            actor())))
        .hasStackTraceContaining("没有命中");
    // ② 金额不对 ⇒ 不命中（**金额相同不构成匹配**，但金额对而其它不对也不命中）
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        writeOffs.autoMatch(
                            new FinanceWriteOffAutoMatchInput(
                                receipt.id(), "ORD-1", new BigDecimal("999"), null, null, null),
                            actor())))
        .hasStackTraceContaining("没有命中");
    // ③ 日期不对 ⇒ 不命中
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        writeOffs.autoMatch(
                            new FinanceWriteOffAutoMatchInput(
                                receipt.id(),
                                "ORD-1",
                                new BigDecimal("100"),
                                LocalDate.now(ZoneOffset.UTC).minusDays(30),
                                null,
                                null),
                            actor())))
        .hasStackTraceContaining("没有命中");
    assertThat(count("select count(*) from finance_write_off")).isZero();

    // ④ 三者都对 ⇒ 命中并落库（金额给 100 = 该明细行的未核销余额）
    var result =
        as(
            () ->
                writeOffs.autoMatch(
                    new FinanceWriteOffAutoMatchInput(
                        receipt.id(), "ORD-1", new BigDecimal("100"), null, null, null),
                    actor()));
    assertThat(result.writeOff().mode()).isEqualTo(WriteOffMode.AUTO_MATCH);
    assertThat(result.writeOff().settlementAmount()).isEqualByComparingTo("100.0000");
    assertThat(result.candidates()).hasSize(1);
    // 命中原因逐条写出来，便于人工复核「凭什么匹配上」。
    assertThat(result.candidates().get(0).reasons()).hasSize(3);
    assertThat(String.join(" ", result.candidates().get(0).reasons()))
        .contains("订单号")
        .contains("金额条件")
        .contains("日期条件");
  }

  @Test
  void 自动匹配必须写明订单号否则退化成金额相同即匹配() {
    var receipt = createReceipt("1000", "USD");
    createReceivable("USD", "明细|ORD-1|100");
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        writeOffs.autoMatch(
                            new FinanceWriteOffAutoMatchInput(
                                receipt.id(), "  ", null, null, null, null),
                            actor())))
        .hasStackTraceContaining("订单号");
  }

  // ================================================================ 预收挂账 / 预收余额

  @Test
  void 预收挂账不减未核销余额而且预收余额真的算得出来() {
    var receipt = createReceipt("1000", "USD");
    var input =
        new FinanceWriteOffInput(
            receipt.id(),
            List.of(new FinanceWriteOffAllocationInput(AllocationType.ADVANCE, null, new BigDecimal("300"), null)),
            "预收挂账",
            null);
    var detail = as(() -> writeOffs.create(input, actor()));

    assertThat(detail.writeOff().mode()).isEqualTo(WriteOffMode.ADVANCE_PARKING);
    assertThat(detail.writeOff().advanceAmount()).isEqualByComparingTo("300.0000");

    // 口径与 12.2.1.1 一致：预收 ⊂ 未核销余额 ⇒ 挂账**不**减未核销余额。
    var receiptRow = one("select settled_amount, unsettled_amount from finance_receipt where id = ?", receipt.id());
    assertThat(receiptRow.get("settled_amount", BigDecimal.class)).isEqualByComparingTo("0.0000");
    assertThat(receiptRow.get("unsettled_amount", BigDecimal.class)).isEqualByComparingTo("1000.0000");

    // 预收余额真的算出来了（按币种分开）。
    var balance = as(() -> writeOffs.advanceBalance());
    assertThat(balance.byCurrency()).hasSize(1);
    var summary = balance.byCurrency().get(0);
    assertThat(summary.currency()).isEqualTo("USD");
    assertThat(summary.parkedAmount()).isEqualByComparingTo("300.0000");
    assertThat(summary.totalAmount()).isEqualByComparingTo("300.0000");
    assertThat(balance.ledgerCount()).isEqualTo(1);
  }

  @Test
  void 挂着的预收是专用的不能拿去核销别的应收() {
    var receipt = createReceipt("1000", "USD");
    var fixture = createReceivable("USD", "明细|ORD-1|900");
    var lineId = (UUID) fixture.get(1);
    var parking =
        new FinanceWriteOffInput(
            receipt.id(),
            List.of(new FinanceWriteOffAllocationInput(AllocationType.ADVANCE, null, new BigDecimal("300"), null)),
            "预收挂账",
            null);
    as(() -> writeOffs.create(parking, actor()));

    assertThatThrownBy(() -> settle(receipt, lineId, "900"))
        .as("挂着的 300 是专用的 ⇒ 只能核销 700")
        .hasStackTraceContaining("预收账");

    // 释放挂账后就能全额核销。
    var ledger = one("select id from finance_advance_ledger where status = 'OPEN'");
    as(() -> writeOffs.releaseAdvance(ledger.get(0, UUID.class), actor()));
    var detail = settle(receipt, lineId, "900");
    assertThat(detail.writeOff().settlementAmount()).isEqualByComparingTo("900.0000");
  }

  @Test
  void 带预收标记的收款单不能再挂预收账否则重复计数() {
    var input =
        new FinanceReceiptInput(
            ReceiptSource.MANUAL,
            null,
            null,
            "USD",
            new BigDecimal("1000"),
            OffsetDateTime.now(ZoneOffset.UTC).withNano(0),
            PayerType.CUSTOMER,
            "客户甲",
            null,
            null,
            true,
            "预收标记");
    var receipt = as(() -> receipts.create(input, actor()));
    var parking =
        new FinanceWriteOffInput(
            receipt.id(),
            List.of(new FinanceWriteOffAllocationInput(AllocationType.ADVANCE, null, new BigDecimal("300"), null)),
            "预收挂账",
            null);
    assertThatThrownBy(() -> as(() -> writeOffs.create(parking, actor())))
        .as("12.2.1.1 已把这笔钱的未核销余额算进预收了")
        .hasStackTraceContaining("预收标记");
  }

  // ================================================================ 核销撤销

  @Test
  void 核销撤销把settled金额真的退回去() {
    var receipt = createReceipt("1000", "USD");
    // 两行 ⇒ 核销其中一行后单头停在 PARTIALLY_SETTLED（不是终态，可以撤销）。
    var fixture = createReceivable("USD", "明细一|ORD-1|200", "明细二|ORD-2|300");
    var head = (FinanceReceivableRow) fixture.get(0);
    var lineId = (UUID) fixture.get(1);
    var detail = settle(receipt, lineId, "100");

    var reversed = as(() -> writeOffs.reverse(detail.writeOff().id(), new FinanceWriteOffReverseInput("匹配错了"), actor()));

    assertThat(reversed.writeOff().status()).isEqualTo(WriteOffStatus.REVERSED);
    assertThat(reversed.writeOff().reversedAt()).isNotNull();
    assertThat(reversed.allocations().get(0).status())
        .isEqualTo(com.starcon.finance.WriteOffModels.AllocationStatus.REVERSED);
    assertThat(reversed.events()).anyMatch(e -> "REVERSED".equals(e.eventType()));

    // 行级已核销金额退回去，单头状态回到未核销，收款单的未核销余额回到 1000。
    var line = one("select settled_amount, settlement_status from finance_receivable_line where id = ?", lineId);
    assertThat(line.get("settled_amount", BigDecimal.class)).isEqualByComparingTo("0.0000");
    assertThat(line.get("settlement_status", String.class)).isEqualTo("OPEN");
    var headRow = one("select settled_amount, status from finance_receivable where id = ?", head.id());
    assertThat(headRow.get("settled_amount", BigDecimal.class)).isEqualByComparingTo("0.0000");
    assertThat(headRow.get("status", String.class)).isEqualTo("OPEN");
    var receiptRow = one("select settled_amount, unsettled_amount from finance_receipt where id = ?", receipt.id());
    assertThat(receiptRow.get("settled_amount", BigDecimal.class)).isEqualByComparingTo("0.0000");
    assertThat(receiptRow.get("unsettled_amount", BigDecimal.class)).isEqualByComparingTo("1000.0000");

    // 已撤销的核销记录是终态：不可再撤销一次。
    assertThatThrownBy(
            () -> as(() -> writeOffs.reverse(detail.writeOff().id(), new FinanceWriteOffReverseInput("再撤一次"), actor())))
        .hasStackTraceContaining("终态");
  }

  @Test
  void 整单核销完成后撤销被上游终态守卫拒绝并说清替代路径() {
    var receipt = createReceipt("1000", "USD");
    // 只有一行 ⇒ 全额核销后单头变 SETTLED（12.1.1.1 写死的终态，整单冻结）。
    var fixture = createReceivable("USD", "明细|ORD-1|100");
    var lineId = (UUID) fixture.get(1);
    var detail = settle(receipt, lineId, "100");
    assertThat(one("select status from finance_receivable where id = ?",
            ((FinanceReceivableRow) fixture.get(0)).id())
        .get(0, String.class))
        .isEqualTo("SETTLED");

    assertThatThrownBy(
            () -> as(() -> writeOffs.reverse(detail.writeOff().id(), new FinanceWriteOffReverseInput("撤销"), actor())))
        .as("终态应收单整单冻结 ⇒ 核销金额退不回来（绕开守卫就等于给账目留后门）")
        .hasStackTraceContaining("终态")
        .hasStackTraceContaining("作废");
    // 退不回来，但也不能**部分**退回去 —— 状态必须原样。
    assertThat(one("select settled_amount from finance_receivable_line where id = ?", lineId)
        .get(0, BigDecimal.class)).isEqualByComparingTo("100.0000");
  }

  @Test
  void 撤销必须写明理由() {
    var receipt = createReceipt("1000", "USD");
    var fixture = createReceivable("USD", "明细一|ORD-1|200", "明细二|ORD-2|300");
    var lineId = (UUID) fixture.get(1);
    var detail = settle(receipt, lineId, "100");
    assertThatThrownBy(
            () -> as(() -> writeOffs.reverse(detail.writeOff().id(), new FinanceWriteOffReverseInput("  "), actor())))
        .hasStackTraceContaining("理由");
  }

  // ================================================================ 数据层不变量

  @Test
  void 核销记录没有分配明细时提交阶段被延迟约束拒绝() {
    var receipt = createReceipt("1000", "USD");
    var error =
        dbError(
            "insert into finance_write_off(id,tenant_id,write_off_no,receipt_id,currency,mode,"
                + " status,settlement_amount,advance_amount,total_amount,note,occurred_at)"
                + " values(?,?,?,?,'USD','MANUAL','POSTED',0,0,0,'',?::timestamptz)",
            UUID.randomUUID(),
            tenant,
            "WO-DIRECT",
            receipt.id(),
            OffsetDateTime.now(ZoneOffset.UTC));
    assertThat(error).as("直接插一条没有分配明细的核销记录必须被挡住").contains("没有任何分配明细");
  }

  @Test
  void 核销金额只能写在明细行的settled列上单头是派生的() {
    var receipt = createReceipt("1000", "USD");
    var fixture = createReceivable("USD", "明细一|ORD-1|200", "明细二|ORD-2|300");
    var head = (FinanceReceivableRow) fixture.get(0);
    settle(receipt, (UUID) fixture.get(1), "100");

    // 单头的已核销金额 == 明细行已核销金额之和（**没有第二个写入口**）。
    var headSum =
        one(
            "select coalesce(sum(settled_amount),0) from finance_receivable_line where receivable_id = ?",
            head.id())
            .get(0, BigDecimal.class);
    var headAmount = one("select settled_amount from finance_receivable where id = ?", head.id())
        .get(0, BigDecimal.class);
    assertThat(headAmount).isEqualByComparingTo(headSum);
    assertThat(headAmount).isEqualByComparingTo("100.0000");

    // 明细行的 settled_amount 是**本模块独占**的写入口：它带 CHECK（0 ≤ settled ≤ amount）。
    var column =
        one(
            "select data_type, numeric_scale from information_schema.columns"
                + " where table_name = 'finance_receivable_line' and column_name = 'settled_amount'");
    assertThat(column.get("data_type", String.class)).isEqualTo("numeric");
    assertThat(column.get("numeric_scale", Integer.class)).isEqualTo(4);
  }

  @Test
  void 核销金额是四位小数的numeric而不是浮点() {
    var receipt = createReceipt("1000", "USD");
    var fixture = createReceivable("USD", "明细|ORD-1|100");
    settle(receipt, (UUID) fixture.get(1), "0.1");
    var stored =
        one("select total_amount from finance_write_off").get(0, BigDecimal.class).toPlainString();
    assertThat(stored).isEqualTo("0.1000");
  }

  @Test
  void 核销时刻写回的值逐位相等不会偏八小时() {
    var receipt = createReceipt("1000", "USD");
    var fixture = createReceivable("USD", "明细一|ORD-1|200", "明细二|ORD-2|300");
    var lineId = (UUID) fixture.get(1);
    var occurredAt = OffsetDateTime.parse("2026-10-01T08:30:00+08:00");
    var input =
        new FinanceWriteOffInput(
            receipt.id(),
            List.of(new FinanceWriteOffAllocationInput(AllocationType.SETTLEMENT, lineId, new BigDecimal("100"), null)),
            "时刻",
            occurredAt);
    var detail = as(() -> writeOffs.create(input, actor()));
    assertThat(detail.writeOff().occurredAt().toInstant()).isEqualTo(Instant.parse("2026-10-01T00:30:00Z"));
    var stored =
        one("select occurred_at from finance_write_off where id = ?", detail.writeOff().id())
            .get(0, OffsetDateTime.class);
    assertThat(stored.toInstant()).isEqualTo(Instant.parse("2026-10-01T00:30:00Z"));
  }

  @Test
  void 能力声明把利润口径标成不可用并写明归属模块() {
    var capabilities = as(() -> writeOffs.capabilities());
    assertThat(capabilities.capabilities())
        .anyMatch(
            c ->
                "13.1.1.1".equals(c.module())
                    && !c.available()
                    && "13".equals(c.ownerModule()));
    assertThat(capabilities.downstreamGaps())
        .anyMatch(g -> "13.1.1.1".equals(g.module()) && !g.available());
    // 预收余额是本模块自己的账 ⇒ 它必须被声明为可用。
    assertThat(capabilities.capabilities())
        .anyMatch(c -> "预收挂账与预收余额".equals(c.name()) && c.available());
  }

  @Test
  void 列表是真分页真筛选且汇总覆盖整个结果集() {
    var receipt = createReceipt("1000", "USD");
    var fixture = createReceivable("USD", "明细一|ORD-1|200", "明细二|ORD-2|300");
    settle(receipt, (UUID) fixture.get(1), "100");
    settle(receipt, (UUID) fixture.get(2), "100");

    var page = as(() -> writeOffs.page(new WriteOffQuery(null, null, null, null, null, 0, 1)));
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.items()).hasSize(1);
    // 汇总覆盖**整个**筛选结果集，不是当前页。
    assertThat(page.summaryByCurrency().get(0).totalAmount()).isEqualByComparingTo("200.0000");

    var filtered = as(() -> writeOffs.page(new WriteOffQuery(null, WriteOffMode.MANUAL, "USD", null, null, 0, 50)));
    assertThat(filtered.total()).isEqualTo(2);
    var byCurrency = as(() -> writeOffs.page(new WriteOffQuery(null, null, "EUR", null, null, 0, 50)));
    assertThat(byCurrency.total()).isZero();
  }
}
