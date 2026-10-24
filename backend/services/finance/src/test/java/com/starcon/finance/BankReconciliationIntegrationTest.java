package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.BankReconciliationModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
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
 * 12.7.1.2 银行对账的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖蓝图 12-finance.md 12.7.1.2 的**全部职责**，每一项都断言**真算出来的数**，
 * 不是常量、不是 mock：
 *
 * <ol>
 *   <li><b>流水导入</b> —— CSV / JSON 真解析、幂等、逐行告警（时区偏移、币种、表头）；</li>
 *   <li><b>自动匹配</b> —— 单号 / 对手方 / 日期窗口三类规则，
 *       以及 🔴 **「金额相同即匹配」被拒绝**（卷 12 硬约束）；</li>
 *   <li><b>未匹配清单 + 人工认领</b> —— 认领到**真实存在**的单据、认领到不存在的单据被拒、
 *       终态不可重复处理、排除必须写理由；</li>
 *   <li><b>对账报告</b> —— 余额调节表真的算得出差额；账面余额读不到时**恒为 null**；</li>
 *   <li><b>纪律</b> —— 银行流水 append-only（DB 拒 UPDATE/DELETE）、规则表不预置、
 *       规则编码不可改、日期窗口上限。</li>
 * </ol>
 *
 * <p>⚠️ <b>时间断言</b>：流水事实时刻是 {@code timestamptz}，
 * 断言时**从库里读回时刻再比较**（逐位相等），不能只断言「没抛异常」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BankReconciliationIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();

  static final List<String> PERMISSIONS =
      List.of("finance.receivable.read", "finance.receivable.update");

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
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired BankReconciliationService recon;

  /** 参照行 ID 按用途派生（主键是**全局** uuid，测试之间不能复用同一批固定值）。 */
  static UUID idOf(String tag) {
    return UUID.nameUUIDFromBytes(("starcon-finance-bank-recon-" + tag).getBytes());
  }

  // ================================================================ 夹具

  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_bank_recon_event, finance_bank_recon_report,"
              + " finance_bank_recon_claim, finance_bank_recon_unmatched,"
              + " finance_bank_recon_match, finance_bank_recon_system_line,"
              + " finance_bank_recon_statement_line, finance_bank_recon_session,"
              + " finance_bank_recon_rule, finance_fund_account_balance, finance_fund_account,"
              + " finance_receipt, finance_payment_order restart identity cascade");
    }
    insertAccount("FA-USD", "招商银行美元户", "USD", "1000");
  }

  /** 在可信租户事务里插一个资金账户（12.7.1.1）。balance 为 null ⇒ **不**写余额历史。 */
  void insertAccount(String no, String name, String currency, String balance) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          var accountId = idOf("account-" + no);
          sql.execute(
              "insert into finance_fund_account(id,tenant_id,account_no,account_name,account_type,"
                  + " currency,legal_entity_id,legal_entity_code,legal_entity_name,bank_name,"
                  + " account_number_masked,account_number_tail,account_holder,status,"
                  + " current_balance,current_balance_at,balance_record_count,note,version,"
                  + " created_at,updated_at)"
                  + " values (?,?,?,?,'BANK',?,?,?,'测试法人','测试银行','****5678','5678','',"
                  + " 'ACTIVE',0,?::timestamptz,0,'',0,?::timestamptz,?::timestamptz)",
              accountId,
              tenant,
              no,
              name,
              currency,
              idOf("legal-entity"),
              "LE_A",
              now,
              now,
              now);
          if (balance == null) {
            return null;
          }
          sql.execute(
              "insert into finance_fund_account_balance(id,tenant_id,account_id,currency,"
                  + " legal_entity_id,legal_entity_code,legal_entity_name,balance_amount,"
                  + " previous_amount,change_source,reason,source_system,source_reference,"
                  + " operator_user_id,recorded_at,created_at)"
                  + " values (?,?,?,?,?,?,'测试法人',?,0,'INITIAL','建档','','',?,?::timestamptz,"
                  + " ?::timestamptz)",
              idOf("balance-" + no),
              tenant,
              accountId,
              currency,
              idOf("legal-entity"),
              "LE_A",
              new BigDecimal(balance),
              UUID.randomUUID(),
              now,
              now);
          return null;
        });
  }

  /** 插一张收款单（12.2.1.1）——系统侧的「资金流入」事实。 */
  void insertReceipt(String no, String amount, String currency, String sourceRef, String payer,
      String receivedAt) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into finance_receipt(id,tenant_id,receipt_no,source,source_system,"
                  + " source_reference,currency,amount,settled_amount,advance_flag,status,"
                  + " received_at,payer_type,payer_name,fund_account_ref,fund_account_label,"
                  + " settlement_no,note,version,created_at,updated_at)"
                  + " values (?,?,?,'BANK_TRANSFER','bank',?,?,?,0,false,'RECORDED',"
                  + " ?::timestamptz,'CUSTOMER',?,null,'',null,'',0,now(),now())",
              idOf("receipt-" + no),
              tenant,
              no,
              sourceRef,
              currency,
              new BigDecimal(amount),
              receivedAt,
              payer);
          return null;
        });
  }

  /** 插一张付款单（12.2.2.1）——系统侧的「资金流出」事实。 */
  void insertPaymentOrder(String no, String amount, String currency, String counterparty,
      String createdAt) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into finance_payment_order(id,tenant_id,order_no,counterparty_type,"
                  + " counterparty_name,currency,amount,payment_method,payment_account_name,"
                  + " status,execution_available,execution_owner_module,approval_status,"
                  + " submitted_by,submitted_at,approval_instance_id,approved_by,approved_at,"
                  + " failure_reason,note,version,created_at,updated_at)"
                  + " values (?,?,?,'SUPPLIER',?,?,?,'BANK_TRANSFER',?,'APPROVED',true,'12.2.2.2',"
                  + " 'APPROVED',?,?::timestamptz,?,?,?::timestamptz,'','',0,?::timestamptz,"
                  + " ?::timestamptz)",
              idOf("payment-" + no),
              tenant,
              no,
              counterparty,
              currency,
              new BigDecimal(amount),
              "测试银行",
              UUID.randomUUID(),
              createdAt,
              UUID.randomUUID(),
              UUID.randomUUID(),
              createdAt,
              createdAt,
              createdAt);
          return null;
        });
  }

  <T> T as(java.util.function.Supplier<T> work) {
    return tenants.run(tenant, work);
  }

  AccessService.Access actor() {
    return actors.get("session");
  }

  // ================================================================ 夹具辅助

  /** 建对账批次（走真实服务层，在可信租户事务里）。 */
  FinanceBankReconSessionRow createSession(String accountNo, String periodStart, String periodEnd) {
    return as(
        () ->
            recon.createSession(
                new FinanceBankReconSessionCreateInput(
                    idOf("account-" + accountNo),
                    java.time.LocalDate.parse(periodStart),
                    java.time.LocalDate.parse(periodEnd),
                    new BigDecimal("1000"),
                    new BigDecimal("1000"),
                    "bank",
                    ""),
                actor()));
  }

  /** 导入一份 CSV 流水。 */
  FinanceBankReconImportResult importCsv(UUID sessionId, String csv, BigDecimal closing) {
    return as(
        () ->
            recon.importStatement(
                sessionId,
                new FinanceBankReconImportInput(
                    FinanceBankReconImportFormat.CSV, csv, null, closing),
                actor()));
  }

  /** 跑匹配。 */
  FinanceBankReconMatchResult match(UUID sessionId) {
    return as(() -> recon.runMatch(sessionId, actor()));
  }

  /** 未匹配清单（读操作必须**在可信租户事务里**跑：RLS 靠 app.current_tenant 生效）。 */
  FinanceBankReconUnmatchedPage unmatchedOf(UUID sessionId, String status, String side) {
    return as(() -> recon.unmatched(sessionId, status, side, 0, 20));
  }

  // ==========================================================================
  // 1. 导入
  // ==========================================================================
  @Test
  void 导入csv真入库且逐行告警不静默丢弃() {
    var session = createSession("FA-USD", "2026-10-01", "2026-10-31");
    var csv =
        """
        occurredAt,direction,amount,currency,bankReference,externalReference,counterparty,memo
        2026-10-05T10:00:00+08:00,IN,1200.50,USD,BK-001,SO-1001,ACME Ltd,平台回款
        2026-10-06T09:00:00+08:00,IN,300,USD,BK-002,,,只有金额
        2026-10-07T09:00:00,IN,50,USD,BK-003,,,没有时区
        2026-10-08T09:00:00+08:00,IN,60,EUR,BK-004,,,币种不对
        2026-10-09T09:00:00+08:00,IN,70,USD,BK-001,,,重复流水号
        """;

    var result = importCsv(session.id(), csv, new BigDecimal("1210.50"));

    assertThat(result.parsedCount()).isEqualTo(5);
    assertThat(result.importedCount()).as("只有两行合法").isEqualTo(2);
    assertThat(result.skippedCount()).isEqualTo(3);
    assertThat(result.warnings()).hasSize(3);
    assertThat(result.warnings().get(0)).contains("第 4 行").contains("时区偏移");
    assertThat(result.warnings().get(1)).contains("第 5 行").contains("币种");
    assertThat(result.warnings().get(2)).contains("第 6 行").contains("幂等");

    // 库里真的有两条。
    var rows =
        as(
            () ->
                sql.fetch(
                    "select bank_reference, amount, direction, currency from"
                        + " finance_bank_recon_statement_line order by bank_reference"));
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).get("bank_reference", String.class)).isEqualTo("BK-001");
    assertThat(rows.get(0).get("amount", BigDecimal.class)).isEqualByComparingTo("1200.50");
    assertThat(rows.get(1).get("bank_reference", String.class)).isEqualTo("BK-002");
    assertThat(rows.get(1).get("amount", BigDecimal.class)).isEqualByComparingTo("300");

    // ⚠️ 时间断言：从库里读回来逐位相等（东八区偏 8 小时就是错账）。
    var occurredAt =
        as(
                () ->
                    sql.fetchOne(
                            "select occurred_at from finance_bank_recon_statement_line"
                                + " where bank_reference = 'BK-001'")
                        .get(0, OffsetDateTime.class));
    assertThat(occurredAt.toInstant())
        .isEqualTo(java.time.Instant.parse("2026-10-05T02:00:00Z"));
  }

  @Test
  void 导入json真入库() {
    var session = createSession("FA-USD", "2026-10-01", "2026-10-31");
    var content =
        """
        [{"occurredAt":"2026-10-11T08:00:00Z","direction":"OUT","amount":250.25,
          "currency":"USD","bankReference":"BK-JSON-1","externalReference":"PO-2001",
          "counterparty":"SUPPLIER A","memo":"采购付款"}]
        """;
    var result =
        as(
            () ->
                recon.importStatement(
                    session.id(),
                    new FinanceBankReconImportInput(
                        FinanceBankReconImportFormat.JSON, content, null, null),
                    actor()));

    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isZero();
    assertThat(result.warnings()).isEmpty();
  }

  @Test
  void csv表头缺列时整份拒收而不是猜列序() {
    var session = createSession("FA-USD", "2026-10-01", "2026-10-31");
    var csv = "a,b,c\n1,2,3\n";
    var thrown =
        catchThrowable(
            () ->
                as(
                    () ->
                        recon.importStatement(
                            session.id(),
                            new FinanceBankReconImportInput(
                                FinanceBankReconImportFormat.CSV, csv, null, null),
                            actor())));
    assertThat(thrown).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) thrown).getReason())
        .as("必须说清缺哪一列")
        .contains("表头")
        .contains("occurredat");
    assertThat(as(() -> sql.fetch("select id from finance_bank_recon_statement_line")).size())
        .isZero();
  }

  @Test
  void 重复导入同一份流水是幂等的() {
    var session = createSession("FA-USD", "2026-10-01", "2026-10-31");
    var csv =
        """
        occurredAt,direction,amount,currency,bankReference
        2026-10-05T10:00:00+08:00,IN,100,USD,BK-IDEM
        """;
    var first = importCsv(session.id(), csv, null);
    var second = importCsv(session.id(), csv, null);
    assertThat(first.importedCount()).isEqualTo(1);
    assertThat(second.importedCount()).isZero();
    assertThat(second.skippedCount()).isEqualTo(1);
    assertThat(second.warnings().get(0)).contains("幂等");
    assertThat(as(() -> sql.fetch("select id from finance_bank_recon_statement_line")).size())
        .isEqualTo(1);
  }

  // ==========================================================================
  // 2. 自动匹配
  // ==========================================================================
  @Test
  void 单号精确相等才配上() {
    insertReceipt("RC-1", "1000", "USD", "SO-777", "ACME Ltd", "2026-10-05T10:00:00+08:00");
    var session = createSession("FA-USD", "2026-10-01", "2026-10-31");
    var csv =
        """
        occurredAt,direction,amount,currency,bankReference,externalReference,counterparty
        2026-10-05T10:00:00+08:00,IN,1000,USD,BK-1,SO-777,别的名字
        """;
    importCsv(session.id(), csv, null);

    var result = match(session.id());
    assertThat(result.matchedCount()).isEqualTo(1);
    assertThat(result.unmatchedBankCount()).isZero();
    assertThat(result.unmatchedSystemCount()).isZero();

    // 命中依据说清了「为什么是这一对」。
    var matches = as(() -> recon.matches(session.id(), 0, 10));
    assertThat(matches.items()).hasSize(1);
    assertThat(matches.items().get(0).ruleType()).isEqualTo("REFERENCE_EXACT");
    assertThat(matches.items().get(0).matchBasis()).contains("对方单号相等").contains("金额相等");
    assertThat(matches.items().get(0).dayDifference()).isZero();
  }

  @Test
  void 对手方相等也能配上() {
    insertReceipt("RC-2", "800", "USD", "REF-XYZ", "ACME Co., Ltd.", "2026-10-05T10:00:00+08:00");
    var session = createSession("FA-USD", "2026-10-01", "2026-10-31");
    var csv =
        """
        occurredAt,direction,amount,currency,bankReference,externalReference,counterparty
        2026-10-05T11:00:00+08:00,IN,800,USD,BK-2,别的单号,ACME CO LTD
        """;
    importCsv(session.id(), csv, null);

    var result = match(session.id());
    assertThat(result.matchedCount()).isEqualTo(1);
    assertThat(as(() -> recon.matches(session.id(), 0, 10)).items().get(0).ruleType())
        .isEqualTo("COUNTERPARTY_AMOUNT");
  }

  @Test
  void 金额相同但既无单号也无对手方且日期超窗口时不配() {
    // 🔴 卷 12 硬约束：「金额相同只是候选，必须结合订单号、日期、类型」。
    insertReceipt("RC-3", "500", "USD", "SO-888", "ACME Ltd", "2026-10-05T10:00:00+08:00");
    var session = createSession("FA-USD", "2026-10-01", "2026-10-31");
    var csv =
        """
        occurredAt,direction,amount,currency,bankReference,externalReference,counterparty
        2026-10-25T10:00:00+08:00,IN,500,USD,BK-3,,
        """;
    importCsv(session.id(), csv, null);

    var result = match(session.id());
    assertThat(result.matchedCount()).as("金额相同不等于匹配").isZero();
    assertThat(result.unmatchedBankCount()).isEqualTo(1);
    assertThat(result.unmatchedSystemCount()).isEqualTo(1);
  }

  @Test
  void 方向不同不配() {
    insertReceipt("RC-4", "500", "USD", "SO-889", "ACME Ltd", "2026-10-05T10:00:00+08:00");
    var session = createSession("FA-USD", "2026-10-01", "2026-10-31");
    var csv =
        """
        occurredAt,direction,amount,currency,bankReference,externalReference,counterparty
        2026-10-05T10:00:00+08:00,OUT,500,USD,BK-4,SO-889,ACME Ltd
        """;
    importCsv(session.id(), csv, null);

    var result = match(session.id());
    assertThat(result.matchedCount()).as("收入与支出不是同一笔").isZero();
    assertThat(result.unmatchedBankCount()).isEqualTo(1);
  }

  @Test
  void 付款单也能进入系统侧候选并与流出匹配() {
    insertPaymentOrder("PO-1", "300", "USD", "SUPPLIER A", "2026-10-12T08:00:00+08:00");
    var session = createSession("FA-USD", "2026-10-01", "2026-10-31");
    var csv =
        """
        occurredAt,direction,amount,currency,bankReference,externalReference,counterparty
        2026-10-12T09:00:00+08:00,OUT,300,USD,BK-PO,PO-1,SUPPLIER A
        """;
    importCsv(session.id(), csv, null);

    var result = match(session.id());
    assertThat(result.systemLineCount()).isEqualTo(1);
    assertThat(result.matchedCount()).isEqualTo(1);
    var systemLines = as(() -> recon.systemLines(session.id(), 0, 10));
    assertThat(systemLines.items().get(0).sourceType()).isEqualTo("PAYMENT_ORDER");
    assertThat(systemLines.items().get(0).direction()).isEqualTo("OUT");
  }

  @Test
  void 停用账户不能建对账批次() {
    as(
        () -> {
          sql.execute(
              "update finance_fund_account set status = 'INACTIVE' where account_no = 'FA-USD'");
          return null;
        });
    var thrown =
        catchThrowable(
            () ->
                as(
                    () ->
                        recon.createSession(
                            new FinanceBankReconSessionCreateInput(
                                idOf("account-FA-USD"),
                                java.time.LocalDate.parse("2026-10-01"),
                                java.time.LocalDate.parse("2026-10-31"),
                                null,
                                null,
                                "bank",
                                ""),
                            actor())));
    assertThat(thrown).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) thrown).getReason()).contains("停用");
  }

  // ==========================================================================
  // 3. 未匹配清单 + 人工认领
  // ==========================================================================
  @Test
  void 未匹配项两边都列出来() {
    insertReceipt("RC-5", "100", "USD", "SO-1", "ACME", "2026-10-05T10:00:00+08:00");
    var session = createSession("FA-USD", "2026-10-01", "2026-10-31");
    var csv =
        """
        occurredAt,direction,amount,currency,bankReference
        2026-10-20T10:00:00+08:00,IN,999,USD,BK-X
        """;
    importCsv(session.id(), csv, null);
    match(session.id());

    var unmatched = unmatchedOf(session.id(), null, null);
    assertThat(unmatched.items()).hasSize(2);
    var bank = unmatched.items().stream().filter(r -> r.side().equals("BANK")).findFirst().orElseThrow();
    var system =
        unmatched.items().stream().filter(r -> r.side().equals("SYSTEM")).findFirst().orElseThrow();
    assertThat(bank.amount()).isEqualByComparingTo("999");
    assertThat(system.amount()).isEqualByComparingTo("100");
    assertThat(bank.status()).isEqualTo("OPEN");
  }

  @Test
  void 认领到真实存在的收款单才成功() {
    insertReceipt("RC-6", "100", "USD", "SO-1", "ACME", "2026-10-05T10:00:00+08:00");
    var session = createSession("FA-USD", "2026-10-01", "2026-10-31");
    var csv =
        """
        occurredAt,direction,amount,currency,bankReference
        2026-10-20T10:00:00+08:00,IN,999,USD,BK-Y
        """;
    importCsv(session.id(), csv, null);
    match(session.id());

    var bankUnmatched =
        unmatchedOf(session.id(), "OPEN", "BANK").items().get(0);

    var claimed =
        as(
            () ->
                recon.claim(
                    session.id(),
                    bankUnmatched.id(),
                    new FinanceBankReconClaimInput(
                        FinanceBankReconDocumentType.RECEIPT,
                        idOf("receipt-RC-6"),
                        "RC-6",
                        "这笔钱其实对应这张收款单，只是没进本期候选集"),
                    actor()));

    assertThat(claimed.status()).isEqualTo("CLAIMED");
    assertThat(claimed.claimedDocumentType()).isEqualTo("RECEIPT");
    assertThat(claimed.claimedDocumentNo()).isEqualTo("RC-6");
    assertThat(claimed.claimedBy()).isEqualTo(actor().userId());
    assertThat(claimed.claimReason()).contains("没进本期候选集");

    // 认领记录**追加**进 append-only 的举证表。
    var claims = as(() -> recon.claims(session.id(), bankUnmatched.id(), 0, 10));
    assertThat(claims.items()).hasSize(1);
    assertThat(claims.items().get(0).action()).isEqualTo("CLAIM");

    // 终态：不能重复认领。
    var again =
        catchThrowable(
            () ->
                as(
                    () ->
                        recon.claim(
                            session.id(),
                            bankUnmatched.id(),
                            new FinanceBankReconClaimInput(
                                FinanceBankReconDocumentType.RECEIPT,
                                idOf("receipt-RC-6"),
                                "RC-6",
                                "再来一次"),
                            actor())));
    assertThat(again).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) again).getReason()).contains("已处理过");
  }

  @Test
  void 认领到不存在的单据被拒() {
    var session = createSession("FA-USD", "2026-10-01", "2026-10-31");
    var csv =
        """
        occurredAt,direction,amount,currency,bankReference
        2026-10-20T10:00:00+08:00,IN,999,USD,BK-Z
        """;
    importCsv(session.id(), csv, null);
    match(session.id());
    var unmatched = unmatchedOf(session.id(), "OPEN", "BANK").items().get(0);

    var thrown =
        catchThrowable(
            () ->
                as(
                    () ->
                        recon.claim(
                            session.id(),
                            unmatched.id(),
                            new FinanceBankReconClaimInput(
                                FinanceBankReconDocumentType.RECEIPT,
                                UUID.randomUUID(),
                                "RC-NOT-EXIST",
                                "认领到一张不存在的单据"),
                            actor())));
    assertThat(thrown).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) thrown).getReason()).contains("不存在");
    // 未匹配项仍是待处理。
    assertThat(unmatchedOf(session.id(), "OPEN", "BANK").items()).hasSize(1);
  }

  @Test
  void 排除必须写理由() {
    var session = createSession("FA-USD", "2026-10-01", "2026-10-31");
    var csv =
        """
        occurredAt,direction,amount,currency,bankReference
        2026-10-20T10:00:00+08:00,IN,999,USD,BK-E
        """;
    importCsv(session.id(), csv, null);
    match(session.id());
    var unmatched = unmatchedOf(session.id(), "OPEN", "BANK").items().get(0);

    var excluded =
        as(
            () ->
                recon.exclude(
                    session.id(),
                    unmatched.id(),
                    new FinanceBankReconExcludeInput("这笔是别的法人账户的"),
                    actor()));
    assertThat(excluded.status()).isEqualTo("EXCLUDED");
    assertThat(excluded.excludeReason()).contains("别的法人");
    assertThat(unmatchedOf(session.id(), "OPEN", "BANK").items()).isEmpty();
  }

  // ==========================================================================
  // 4. 对账报告
  // ==========================================================================
  @Test
  void 对账报告算出余额调节与差额() {
    insertReceipt("RC-7", "1000", "USD", "SO-7", "ACME", "2026-10-05T10:00:00+08:00");
    var session = createSession("FA-USD", "2026-10-01", "2026-10-31");
    var csv =
        """
        occurredAt,direction,amount,currency,bankReference,externalReference
        2026-10-05T10:00:00+08:00,IN,1000,USD,BK-R,SO-7
        2026-10-20T10:00:00+08:00,IN,200,USD,BK-R2,
        """;
    importCsv(session.id(), csv, new BigDecimal("1200"));

    // 银行已收 200 未入账（系统侧没有）⇒ 调节后余额 = 银行期末 1200 + 200 = 1400；
    // 我方账面期末余额来自 12.7.1.1 的余额历史（建档 1000）⇒ 差额 = 1400 − 1000 = 400。
    match(session.id());
    var report = as(() -> recon.reconcile(session.id(), actor()));

    assertThat(report.currency()).isEqualTo("USD");
    assertThat(report.matchedCount()).isEqualTo(1);
    assertThat(report.unmatchedBankCount()).isEqualTo(1);
    assertThat(report.unmatchedSystemCount()).isZero();
    assertThat(report.statementClosingBalance()).isEqualByComparingTo("1200");
    assertThat(report.bookBalanceAvailable()).isTrue();
    assertThat(report.bookClosingBalance()).isEqualByComparingTo("1000");
    assertThat(report.unmatchedBankInAmount()).isEqualByComparingTo("200");
    assertThat(report.adjustedBalance()).isEqualByComparingTo("1400");
    assertThat(report.difference()).isEqualByComparingTo("400");

    // 确认后是终态。
    var again =
        catchThrowable(() -> as(() -> recon.reconcile(session.id(), actor())));
    assertThat(again).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) again).getReason()).contains("已确认");
  }

  @Test
  void 账面余额读不到时差额恒为null而不是零() {
    // 余额历史是 append-only（DB 拒绝 DELETE）⇒ 用一个**压根没有余额历史**的账户。
    insertAccount("FA-NOBAL", "没有余额历史的户", "USD", null);
    var session = createSession("FA-NOBAL", "2026-10-01", "2026-10-31");
    var csv =
        """
        occurredAt,direction,amount,currency,bankReference
        2026-10-20T10:00:00+08:00,IN,200,USD,BK-NB
        """;
    importCsv(session.id(), csv, new BigDecimal("1000"));
    match(session.id());
    var report = as(() -> recon.reconcile(session.id(), actor()));

    assertThat(report.bookBalanceAvailable()).isFalse();
    assertThat(report.bookClosingBalance()).isNull();
    assertThat(report.adjustedBalance()).as("读不到账面余额 ⇒ 调节后余额也算不出").isNull();
    assertThat(report.difference()).as("读不到 ≠ 账平了").isNull();
  }

  // ==========================================================================
  // 5. 纪律
  // ==========================================================================
  @Test
  void 银行流水是append_only改写被db拒绝() {
    var session = createSession("FA-USD", "2026-10-01", "2026-10-31");
    var csv =
        """
        occurredAt,direction,amount,currency,bankReference
        2026-10-05T10:00:00+08:00,IN,100,USD,BK-FROZEN
        """;
    importCsv(session.id(), csv, null);

    var thrown =
        catchThrowable(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "update finance_bank_recon_statement_line set amount = 999"
                              + " where bank_reference = 'BK-FROZEN'");
                      return null;
                    }));
    assertThat(thrown).as("改写外部事实必须被 DB 拒绝").isNotNull();
    assertThat(
            as(
                    () ->
                        sql.fetchOne(
                                "select amount from finance_bank_recon_statement_line"
                                    + " where bank_reference = 'BK-FROZEN'")
                            .get(0, BigDecimal.class)))
        .isEqualByComparingTo("100");
  }

  @Test
  void 匹配规则表不预置建批次时写入内置默认规则() {
    assertThat(as(() -> sql.fetch("select id from finance_bank_recon_rule")).size())
        .as("规则表里一行都不该预置")
        .isZero();

    createSession("FA-USD", "2026-10-01", "2026-10-31");

    var rules = as(() -> recon.rules(0, 20));
    assertThat(rules.items()).hasSize(3);
    assertThat(rules.items()).extracting(FinanceBankReconRuleRow::ruleType)
        .containsExactlyInAnyOrder("REFERENCE_EXACT", "COUNTERPARTY_AMOUNT", "AMOUNT_DATE_WINDOW");
    assertThat(rules.items()).allMatch(FinanceBankReconRuleRow::builtin);
    // 🔴 卷 12 硬约束：日期窗口上限 31 天（窗口过大等于用「金额相同」冒充匹配）。
    assertThat(rules.items()).allMatch(r -> r.dateWindowDays() <= 31);
  }

  @Test
  void 规则编码不可改因为它是历史匹配的追溯依据() {
    createSession("FA-USD", "2026-10-01", "2026-10-31");
    var rule = as(() -> recon.rules(0, 20)).items().get(0);

    var thrown =
        catchThrowable(
            () ->
                as(
                    () ->
                        recon.updateRule(
                            rule.id(),
                            new FinanceBankReconRuleInput(
                                "SOMETHING_ELSE",
                                "换个编码",
                                FinanceBankReconRuleType.REFERENCE_EXACT,
                                10,
                                true,
                                3,
                                BigDecimal.ZERO,
                                false,
                                ""),
                            actor())));
    assertThat(thrown).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) thrown).getReason()).contains("不可改");
  }

  @Test
  void 新建规则的日期窗口超出上限被拒() {
    var thrown =
        catchThrowable(
            () ->
                as(
                    () ->
                        recon.createRule(
                            new FinanceBankReconRuleInput(
                                "WIDE_WINDOW",
                                "窗口过大",
                                FinanceBankReconRuleType.AMOUNT_DATE_WINDOW,
                                50,
                                true,
                                365,
                                BigDecimal.ZERO,
                                false,
                                ""),
                            actor())));
    assertThat(thrown).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) thrown).getReason()).contains("1–31");
  }

  @Test
  void 对账批次的状态只能向前不能回退() {
    var session = createSession("FA-USD", "2026-10-01", "2026-10-31");
    match(session.id());
    assertThat(as(() -> recon.getSession(session.id())).status()).isEqualTo("MATCHED");

    var thrown =
        catchThrowable(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "update finance_bank_recon_session set status = 'DRAFT'"
                              + " where id = ? and tenant_id = ?",
                          session.id(),
                          tenant);
                      return null;
                    }));
    assertThat(thrown).as("状态回退必须被 DB 拒绝").isNotNull();
    assertThat(as(() -> recon.getSession(session.id())).status()).isEqualTo("MATCHED");
  }

  @Test
  void 事件流留痕了每一步() {
    var session = createSession("FA-USD", "2026-10-01", "2026-10-31");
    var csv =
        """
        occurredAt,direction,amount,currency,bankReference
        2026-10-05T10:00:00+08:00,IN,100,USD,BK-EV
        """;
    importCsv(session.id(), csv, null);
    match(session.id());

    var events = as(() -> recon.events(session.id(), 0, 20));
    assertThat(events.items()).extracting(FinanceBankReconEventRow::eventType)
        .contains("SESSION_CREATED", "STATEMENT_IMPORTED", "MATCH_EXECUTED");
  }

  // ==========================================================================
  // 6. 端点层
  // ==========================================================================
  @Test
  void 端点层能建批次导入跑匹配并出报告() throws Exception {
    var accountId = idOf("account-FA-USD");
    var create =
        json.writeValueAsString(
            Map.of(
                "accountId", accountId.toString(),
                "periodStart", "2026-10-01",
                "periodEnd", "2026-10-31",
                "sourceSystem", "bank"));
    var created =
        http.perform(
                post("/finance/bank-reconciliation/sessions")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(create))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var session = json.readValue(created, FinanceBankReconSessionRow.class);
    assertThat(session.status()).isEqualTo("DRAFT");
    assertThat(session.currency()).isEqualTo("USD");

    var importBody =
        json.writeValueAsString(
            Map.of(
                "format", "CSV",
                "content",
                    "occurredAt,direction,amount,currency,bankReference\n"
                        + "2026-10-05T10:00:00+08:00,IN,100,USD,BK-HTTP\n"));
    http.perform(
            post("/finance/bank-reconciliation/sessions/" + session.id() + "/import")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(importBody))
        .andExpect(status().isOk());

    http.perform(
            post("/finance/bank-reconciliation/sessions/" + session.id() + "/match")
                .header("Authorization", "session"))
        .andExpect(status().isOk());

    http.perform(
            get("/finance/bank-reconciliation/sessions/" + session.id() + "/report")
                .header("Authorization", "session"))
        .andExpect(status().isNotFound());

    http.perform(
            post("/finance/bank-reconciliation/sessions/" + session.id() + "/reconcile")
                .header("Authorization", "session"))
        .andExpect(status().isOk());

    http.perform(
            get("/finance/bank-reconciliation/sessions/" + session.id() + "/report")
                .header("Authorization", "session"))
        .andExpect(status().isOk());

    // 未登录 ⇒ 401（租户不来自任何请求头）。
    http.perform(get("/finance/bank-reconciliation/sessions")).andExpect(status().isUnauthorized());
  }
}
