package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.FinancialReportModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.LocalDate;
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
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 12.5.2.2 财务报表的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖蓝图 12-finance.md 12.5.2.2 的每一句**加粗句**与「关键约束」：
 *
 * <ul>
 *   <li>「生成<b>资产负债表、利润表、现金流量表、科目余额表、明细账</b>」⇒ 五张报表**全部**出数；</li>
 *   <li>「支持<b>期间对比</b>」⇒ 两段期间并排 + 变动额；</li>
 *   <li>「<b>导出</b>」⇒ CSV / JSON 真内容 + 一条留痕；</li>
 *   <li>「<b>钻取到凭证</b>」⇒ 报表行能钻到真实凭证与分录行，钻出来的数 = 报表上的数；</li>
 *   <li>「<b>报表数字必须能钻到凭证</b>，"报表与明细对不上"是财务系统的致命问题」⇒
 *       借贷平衡 + 会计恒等式两条自检，差额必须是 0。</li>
 * </ul>
 *
 * <p><b>三条口径各有断言</b>：草稿不进报表 / 红冲按净额抵消 / 多币种绝不相加。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FinancialReportIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID userId = UUID.randomUUID();
  static final UUID bookId = UUID.randomUUID();
  static final UUID legalEntityId = UUID.randomUUID();

  /**
   * 权限：报表三件 + 凭证三件。
   *
   * <p>凭证权限是**为了让夹具走真实的 12.5.2.1 端点**（建单 / 过账 / 红冲）——
   * 直接用 SQL 造「已红冲」的凭证会绕过红冲语义，那就测不到本模块最关键的口径了。
   */
  static final List<String> PERMISSIONS =
      List.of(
          "finance.report.read",
          "finance.report.create",
          "finance.report.update",
          "finance.voucher.read",
          "finance.voucher.create",
          "finance.voucher.update");

  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer upstream;

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
            userId,
            tenant,
            "Test",
            UUID.randomUUID(),
            null,
            PERMISSIONS,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", PERMISSIONS))));
    try {
      upstream = HttpServer.create(new InetSocketAddress(0), 0);
      upstream.createContext(
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
      upstream.setExecutor(Executors.newFixedThreadPool(4));
      upstream.start();
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
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired FinancialReportService reports;

  // ================================================================ 夹具

  static UUID septPeriod;
  static UUID octPeriod;
  static UUID novPeriod;

  /**
   * 清空 + 播种：三个会计期间（9 月 USD / 10 月 USD / 11 月 EUR）+ 一套科目。
   *
   * <p>⚠️ **清空与播种必须在同一个方法里**：写成两个 {@code @BeforeEach} 时 JUnit 5
   * <b>不保证</b>它们的执行顺序，可能先播种再清空。
   */
  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_report_export_event, finance_report_template,"
              + " finance_voucher_event, finance_voucher_line, finance_voucher,"
              + " finance_voucher_mapping, finance_voucher_period,"
              + " finance_account_subject_event, finance_account_subject_event_mapping_line,"
              + " finance_account_subject_event_mapping, finance_account_subject"
              + " restart identity cascade");
    }
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    tenants.run(
        tenant,
        () -> {
          septPeriod = UUID.randomUUID();
          octPeriod = UUID.randomUUID();
          novPeriod = UUID.randomUUID();
          insertPeriod(septPeriod, "2026-09", "2026-09-01", "2026-09-30", "USD", now);
          insertPeriod(octPeriod, "2026-10", "2026-10-01", "2026-10-31", "USD", now);
          insertPeriod(novPeriod, "2026-11", "2026-11-01", "2026-11-30", "EUR", now);
          insertSubject("1001", "库存现金", "ASSET", "DEBIT", now);
          insertSubject("1002", "银行存款", "ASSET", "DEBIT", now);
          insertSubject("1122", "应收账款", "ASSET", "DEBIT", now);
          insertSubject("2202", "应付账款", "LIABILITY", "CREDIT", now);
          insertSubject("4001", "实收资本", "EQUITY", "CREDIT", now);
          insertSubject("6001", "主营业务收入", "PROFIT_LOSS", "CREDIT", now);
          insertSubject("6401", "主营业务成本", "COST", "DEBIT", now);
          return null;
        });
  }

  void insertPeriod(
      UUID id, String code, String start, String end, String currency, OffsetDateTime now) {
    sql.execute(
        "insert into finance_voucher_period(id,tenant_id,period_code,period_start,period_end,"
            + "status,base_currency,created_at,updated_at)"
            + " values(?,?,?,?::date,?::date,'OPEN',?,?::timestamptz,?::timestamptz)",
        id,
        tenant,
        code,
        start,
        end,
        currency,
        now,
        now);
  }

  void insertSubject(
      String code, String name, String category, String direction, OffsetDateTime now) {
    sql.execute(
        "insert into finance_account_subject(id,tenant_id,accounting_book_id,legal_entity_id,"
            + "subject_code,subject_name,category,balance_direction,is_detail,status,source,"
            + "created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,true,'ACTIVE','CUSTOM',?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        tenant,
        bookId,
        legalEntityId,
        code,
        name,
        category,
        direction,
        now,
        now);
  }

  // ---------------------------------------------------------------- 凭证夹具

  /** 一条分录行（lineType / 科目编码 / 金额）。 */
  record SeedLine(String lineType, String subjectCode, String amount) {}

  /**
   * 直连 SQL 插一张**已过账**凭证。
   *
   * <p>⚠️ **为什么不用 12.5.2.1 的 HTTP 建单端点**：那属于**别的车道**的文件，本车道不碰它；
   *   而它的凭证号生成（{@code substring(voucher_no from 10)} 取到了带连字符的 {@code -0001}，
   *   被 PostgreSQL 解析成 −1）在同一租户同月建第 3 张凭证时会撞号 ⇒ 本夹具直连 SQL 播种，
   *   报表侧要验的事实（凭证 / 分录行 / 状态）与走 API 完全一致。
   *   ⚠️ 播种仍然**先建草稿再过账**，让 12.5.2.1 的触发器照样把关（期间开放 / 本位币 / 借贷平衡）。
   */
  UUID seedPostedVoucher(
      UUID periodId,
      LocalDate date,
      String currency,
      String voucherNo,
      String description,
      List<SeedLine> lines) {
    var id = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into finance_voucher(id,tenant_id,voucher_no,source_type,period_id,voucher_date,"
                  + "description,currency,status,created_by,created_at,updated_at)"
                  + " values(?,?,?,'MANUAL',?,?::date,?,?,'DRAFT',?,?::timestamptz,?::timestamptz)",
              id,
              tenant,
              voucherNo,
              periodId,
              date,
              description,
              currency,
              userId,
              now,
              now);
          var debit = BigDecimal.ZERO;
          var credit = BigDecimal.ZERO;
          var seq = 1;
          for (var line : lines) {
            var amount = new BigDecimal(line.amount());
            sql.execute(
                "insert into finance_voucher_line(id,tenant_id,voucher_id,line_no,line_type,"
                    + "subject_code,subject_name,summary,amount,currency,auxiliary_type,created_at,"
                    + "updated_at)"
                    + " values(?,?,?,?,?,?,'测试科目','测试摘要',?::numeric,?,'NONE',?::timestamptz,"
                    + "?::timestamptz)",
                UUID.randomUUID(),
                tenant,
                id,
                seq,
                line.lineType(),
                line.subjectCode(),
                amount,
                currency,
                now,
                now);
            if ("DEBIT".equals(line.lineType())) {
              debit = debit.add(amount);
            } else {
              credit = credit.add(amount);
            }
            seq++;
          }
          sql.execute(
              "update finance_voucher set status='POSTED', total_debit=?::numeric,"
                  + " total_credit=?::numeric, line_count=?, posted_at=?::timestamptz, posted_by=?,"
                  + " version=version+1 where id=?",
              debit,
              credit,
              seq - 1,
              now,
              userId,
              id);
          return null;
        });
    return id;
  }

  /** 插一张**草稿**凭证（草稿不是账，绝不能进报表）。 */
  void seedDraftVoucher(
      UUID periodId,
      LocalDate date,
      String currency,
      String voucherNo,
      String description,
      List<SeedLine> lines) {
    var id = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into finance_voucher(id,tenant_id,voucher_no,source_type,period_id,voucher_date,"
                  + "description,currency,status,created_by,created_at,updated_at)"
                  + " values(?,?,?,'MANUAL',?,?::date,?,?,'DRAFT',?,?::timestamptz,?::timestamptz)",
              id,
              tenant,
              voucherNo,
              periodId,
              date,
              description,
              currency,
              userId,
              now,
              now);
          var seq = 1;
          for (var line : lines) {
            sql.execute(
                "insert into finance_voucher_line(id,tenant_id,voucher_id,line_no,line_type,"
                    + "subject_code,subject_name,summary,amount,currency,auxiliary_type,created_at,"
                    + "updated_at)"
                    + " values(?,?,?,?,?,?,'测试科目','测试摘要',?::numeric,?,'NONE',?::timestamptz,"
                    + "?::timestamptz)",
                UUID.randomUUID(),
                tenant,
                id,
                seq,
                line.lineType(),
                line.subjectCode(),
                new BigDecimal(line.amount()),
                currency,
                now,
                now);
            seq++;
          }
          return null;
        });
  }

  /**
   * 红冲：新建一张**反向凭证**（借贷对调、金额相同）+ 原凭证标 REVERSED。
   *
   * <p>与 12.5.2.1 的 {@code VoucherService.reverse} 同一套语义与同一套约束
   * （DB 层 {@code finance_voucher_reversed_ck} 钉死「REVERSED ⇒ 必须指向那张红冲凭证」）。
   */
  void seedReversal(UUID originalId, String reversalNo) {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    tenants.run(
        tenant,
        () -> {
          var original = sql.fetchOne("select * from finance_voucher where id = ?", originalId);
          var periodId = original.get("period_id", UUID.class);
          var currency = original.get("currency", String.class);
          var date = original.get("voucher_date", LocalDate.class);
          var reversalId = UUID.randomUUID();
          sql.execute(
              "insert into finance_voucher(id,tenant_id,voucher_no,source_type,reversal_of_id,"
                  + "period_id,voucher_date,description,currency,status,created_by,created_at,updated_at)"
                  + " values(?,?,?,'REVERSAL',?,?,?::date,?,?,'DRAFT',?,?::timestamptz,?::timestamptz)",
              reversalId,
              tenant,
              reversalNo,
              originalId,
              periodId,
              date,
              "红冲：" + original.get("description", String.class),
              currency,
              userId,
              now,
              now);
          var debit = BigDecimal.ZERO;
          var credit = BigDecimal.ZERO;
          var seq = 1;
          for (var r :
              sql.fetch(
                  "select * from finance_voucher_line where voucher_id = ? order by line_no",
                  originalId)) {
            var swapped = "DEBIT".equals(r.get("line_type", String.class)) ? "CREDIT" : "DEBIT";
            var amount = r.get("amount", BigDecimal.class);
            sql.execute(
                "insert into finance_voucher_line(id,tenant_id,voucher_id,line_no,line_type,"
                    + "subject_code,subject_name,summary,amount,currency,auxiliary_type,created_at,"
                    + "updated_at)"
                    + " values(?,?,?,?,?,?,'测试科目','测试摘要',?::numeric,?,'NONE',?::timestamptz,"
                    + "?::timestamptz)",
                UUID.randomUUID(),
                tenant,
                reversalId,
                seq,
                swapped,
                r.get("subject_code", String.class),
                amount,
                currency,
                now,
                now);
            if ("DEBIT".equals(swapped)) {
              debit = debit.add(amount);
            } else {
              credit = credit.add(amount);
            }
            seq++;
          }
          sql.execute(
              "update finance_voucher set status='POSTED', total_debit=?::numeric,"
                  + " total_credit=?::numeric, line_count=?, posted_at=?::timestamptz, posted_by=?,"
                  + " version=version+1 where id=?",
              debit,
              credit,
              seq - 1,
              now,
              userId,
              reversalId);
          sql.execute(
              "update finance_voucher set status='REVERSED', reversal_voucher_id=?,"
                  + " reversed_at=?::timestamptz, version=version+1, updated_at=?::timestamptz"
                  + " where id=?",
              reversalId,
              now,
              now,
              originalId);
          return null;
        });
  }

  // ---------------------------------------------------------------- 报表夹具

  /** 铺一套标准账（9 月 + 10 月 + 一张草稿 + 一笔已红冲 + 11 月 EUR）。 */
  void seedStandardLedger() {
    // 9 月：现金 +100 / 收入 +100；银行存款 +1000 / 实收资本 +1000。
    seedPostedVoucher(
        septPeriod,
        LocalDate.of(2026, 9, 10),
        "USD",
        "VC-202609-0001",
        "9 月现金收入",
        List.of(
            new SeedLine("DEBIT", "1001", "100.0000"), new SeedLine("CREDIT", "6001", "100.0000")));
    seedPostedVoucher(
        septPeriod,
        LocalDate.of(2026, 9, 20),
        "USD",
        "VC-202609-0002",
        "9 月股东投入",
        List.of(
            new SeedLine("DEBIT", "1002", "1000.0000"), new SeedLine("CREDIT", "4001", "1000.0000")));
    // 10 月：应收 +500 / 收入 +500；成本 +200 / 银行存款 −200。
    seedPostedVoucher(
        octPeriod,
        LocalDate.of(2026, 10, 5),
        "USD",
        "VC-202610-0001",
        "10 月赊销",
        List.of(
            new SeedLine("DEBIT", "1122", "500.0000"), new SeedLine("CREDIT", "6001", "500.0000")));
    seedPostedVoucher(
        octPeriod,
        LocalDate.of(2026, 10, 8),
        "USD",
        "VC-202610-0002",
        "10 月结转成本",
        List.of(
            new SeedLine("DEBIT", "6401", "200.0000"), new SeedLine("CREDIT", "1002", "200.0000")));
    // 10 月草稿：绝不进报表。
    seedDraftVoucher(
        octPeriod,
        LocalDate.of(2026, 10, 9),
        "USD",
        "VC-202610-9001",
        "10 月未过账草稿",
        List.of(
            new SeedLine("DEBIT", "1002", "999.0000"), new SeedLine("CREDIT", "6001", "999.0000")));
    // 10 月应付 +700 / 银行存款 −700 ⇒ 随后红冲 ⇒ 净额归零。
    var toReverse =
        seedPostedVoucher(
            octPeriod,
            LocalDate.of(2026, 10, 12),
            "USD",
            "VC-202610-0003",
            "10 月应付（随后红冲）",
            List.of(
                new SeedLine("DEBIT", "2202", "700.0000"),
                new SeedLine("CREDIT", "1002", "700.0000")));
    seedReversal(toReverse, "VC-202610-0004");
    // 11 月 EUR：应收 +300 / 收入 +300（多币种分组用）。
    seedPostedVoucher(
        novPeriod,
        LocalDate.of(2026, 11, 3),
        "EUR",
        "VC-202611-0001",
        "11 月欧元赊销",
        List.of(
            new SeedLine("DEBIT", "1122", "300.0000"), new SeedLine("CREDIT", "6001", "300.0000")));
  }

  /** 调一张报表（HTTP 真跑）。 */
  FinanceReportResult report(
      String path,
      UUID from,
      UUID to,
      UUID compareFrom,
      UUID compareTo,
      String currency,
      String subjectCode)
      throws Exception {
    var request =
        get("/finance/financial-reports/" + path)
            .header("Authorization", "session")
            .param("periodFromId", from.toString())
            .param("periodToId", to.toString());
    if (compareFrom != null) request = request.param("comparePeriodFromId", compareFrom.toString());
    if (compareTo != null) request = request.param("comparePeriodToId", compareTo.toString());
    if (currency != null) request = request.param("currency", currency);
    if (subjectCode != null) request = request.param("subjectCode", subjectCode);
    var response = http.perform(request).andReturn();
    var body = response.getResponse().getContentAsString();
    assertThat(response.getResponse().getStatus()).as("取报表失败：" + body).isEqualTo(200);
    return json.readValue(body, FinanceReportResult.class);
  }

  /**
   * 把报表行上的 {@code drillPath} 拆成「路径 + 查询参数」。
   *
   * <p>🔴 **这一步证明路径是真能走的**：路径自带全部参数（报表类型 / 行码 / 币种 /
   *   期间），页面不需要自己拼，也就不会拼错。
   */
  org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder drillRequest(
      String drillPath) {
    var uri = java.net.URI.create(drillPath);
    var builder = get(uri.getPath());
    var query = uri.getQuery();
    if (query != null && !query.isBlank()) {
      for (var part : query.split("&")) {
        var kv = part.split("=", 2);
        if (kv.length != 2) continue;
        builder =
            builder.param(
                java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
      }
    }
    return builder;
  }

  /** 在某个币种分组里找一行。 */
  FinanceReportRow rowOf(FinanceReportResult result, String currency, String lineCode) {
    for (var group : result.currencyGroups()) {
      if (!group.currency().equals(currency)) continue;
      for (var row : group.rows()) {
        if (row.lineCode().equals(lineCode)) return row;
      }
    }
    throw new AssertionError("报表里没有这一行：" + currency + " / " + lineCode);
  }

  FinanceReportCurrencyGroup groupOf(FinanceReportResult result, String currency) {
    for (var group : result.currencyGroups()) {
      if (group.currency().equals(currency)) return group;
    }
    throw new AssertionError("报表里没有这个币种分组：" + currency);
  }

  BigDecimal amount(String raw) {
    return new BigDecimal(raw);
  }

  // ==========================================================================
  // 口径一：草稿绝不进报表
  // ==========================================================================
  @Test
  void 草稿凭证绝不进报表_且排除数量写在结果里() throws Exception {
    seedStandardLedger();
    var result = report("balance-sheet", octPeriod, octPeriod, null, null, null, null);
    // 草稿那张 999.0000 的凭证不能出现在任何一行里。
    var usd = groupOf(result, "USD");
    for (var row : usd.rows()) {
      assertThat(row.periodDebit().toPlainString())
          .as("草稿凭证混进了报表（借方发生额）")
          .doesNotContain("999");
      assertThat(row.periodCredit().toPlainString())
          .as("草稿凭证混进了报表（贷方发生额）")
          .doesNotContain("999");
    }
    assertThat(result.draftExcludedCount()).as("被排除的草稿数必须写进结果里").isEqualTo(1L);
    assertThat(result.notice()).contains("草稿");
  }

  // ==========================================================================
  // 口径二：红冲按「原凭证 + 反向凭证」净额抵消
  // ==========================================================================
  @Test
  void 红冲按原凭证与反向凭证净额抵消_科目余额归零() throws Exception {
    seedStandardLedger();
    var result = report("trial-balance", octPeriod, octPeriod, null, null, null, null);
    var usd = groupOf(result, "USD");
    // 2202（应付）：借 700（原凭证）+ 贷 700（反向凭证）⇒ 净额 0。
    var payable = rowOf(result, "USD", "2202");
    assertThat(payable.closingDebit()).isEqualByComparingTo("0.0000");
    assertThat(payable.closingCredit()).isEqualByComparingTo("0.0000");
    // 1002（银行存款）：期初 1000，本期 −200（红冲那笔抵消后不影响）⇒ 800。
    var bank = rowOf(result, "USD", "1002");
    assertThat(bank.openingBalance()).isEqualByComparingTo("1000.0000");
    assertThat(bank.closingBalance()).isEqualByComparingTo("800.0000");
    // 本期已红冲的原凭证数写进结果里（不藏着）。
    assertThat(result.reversedVoucherCount()).isEqualTo(1L);
  }

  // ==========================================================================
  // 口径三：多币种绝不相加
  // ==========================================================================
  @Test
  void 多币种分组呈现_绝不相加() throws Exception {
    seedStandardLedger();
    var result = report("balance-sheet", octPeriod, novPeriod, null, null, null, null);
    assertThat(result.currencyGroups()).hasSize(2);
    assertThat(result.currencyGroups().stream().map(FinanceReportCurrencyGroup::currency))
        .containsExactly("USD", "EUR");
    // 每一组只含自己币种的金额：USD 组里绝不该出现 EUR 的 300。
    var usd = groupOf(result, "USD");
    for (var row : usd.rows()) {
      assertThat(row.currency()).isEqualTo("USD");
      assertThat(row.closingBalance().toPlainString()).doesNotContain("300");
    }
    var eur = groupOf(result, "EUR");
    assertThat(eur.rows()).isNotEmpty();
    for (var row : eur.rows()) {
      assertThat(row.currency()).isEqualTo("EUR");
    }
    // 报表结果里**没有**「本位币金额」这种字段 ⇒ 结构上做不到折算相加。
    assertThat(result.currencyScope()).isEqualTo("ALL");
  }

  // ==========================================================================
  // 加粗句：报表数字必须能钻到凭证
  // ==========================================================================
  @Test
  void 报表数字能钻到凭证_钻出来的合计等于报表上的数() throws Exception {
    seedStandardLedger();
    var result = report("balance-sheet", octPeriod, octPeriod, null, null, null, null);
    var cash = rowOf(result, "USD", "1001");
    assertThat(cash.closingBalance()).isEqualByComparingTo("100.0000");
    assertThat(cash.drillPath()).as("每一行都必须带真实可走的钻取路径").startsWith("/finance/financial-reports/drill?");

    var response =
        http.perform(
                drillRequest(cash.drillPath())
                    .header("Authorization", "session"))
            .andReturn();
    var body = response.getResponse().getContentAsString();
    assertThat(response.getResponse().getStatus()).as("钻取失败：" + body).isEqualTo(200);
    var drill = json.readValue(body, FinanceReportDrill.class);
    assertThat(drill.entries()).isNotEmpty();
    // 钻出来的必须是**真实凭证 + 分录行**。
    assertThat(drill.entries().get(0).voucherNo()).startsWith("VC-");
    assertThat(drill.entries().get(0).lineNo()).isEqualTo(1);
    assertThat(drill.entries().get(0).drillPath()).isEqualTo("/finance/vouchers/" + drill.entries().get(0).voucherId());
    // 钻出来的净额 = 报表上的数字（符号规则一致）。
    assertThat(drill.netTotal()).isEqualByComparingTo(cash.closingBalance());
    assertThat(drill.debitTotal()).isEqualByComparingTo("100.0000");
  }

  @Test
  void 合计行同样能钻到凭证() throws Exception {
    seedStandardLedger();
    var result = report("balance-sheet", octPeriod, octPeriod, null, null, null, null);
    var total = rowOf(result, "USD", "TOTAL:ASSET");
    assertThat(total.totalRow()).isTrue();
    assertThat(total.drillPath()).contains("lineCode=TOTAL%3AASSET");
    var response =
        http.perform(
                drillRequest(total.drillPath())
                    .header("Authorization", "session"))
            .andReturn();
    assertThat(response.getResponse().getStatus()).isEqualTo(200);
    var drill = json.readValue(response.getResponse().getContentAsString(), FinanceReportDrill.class);
    assertThat(drill.entries()).isNotEmpty();
    assertThat(drill.netTotal()).isEqualByComparingTo(total.closingBalance());
  }

  // ==========================================================================
  // 加粗句：报表与明细对不上是致命问题 ⇒ 机器自检
  // ==========================================================================
  @Test
  void 资产负债表_会计恒等式差额为零() throws Exception {
    seedStandardLedger();
    var result = report("balance-sheet", octPeriod, octPeriod, null, null, null, null);
    var usd = groupOf(result, "USD");
    assertThat(usd.checks()).hasSize(2);
    for (var check : usd.checks()) {
      assertThat(check.difference())
          .as("自检「" + check.checkName() + "」差额必须为 0：" + check.detail())
          .isEqualByComparingTo("0.0000");
      assertThat(check.balanced()).isTrue();
    }
    // 恒等式：资产 1400 = 负债 0 + 权益 1000 + 累计未结转损益 400。
    assertThat(rowOf(result, "USD", "TOTAL:ASSET").closingBalance()).isEqualByComparingTo("1400.0000");
    assertThat(rowOf(result, "USD", "TOTAL:LIABILITY").closingBalance()).isEqualByComparingTo("0.0000");
    assertThat(rowOf(result, "USD", "TOTAL:EQUITY").closingBalance()).isEqualByComparingTo("1000.0000");
    assertThat(rowOf(result, "USD", "TOTAL:RETAINED").closingBalance()).isEqualByComparingTo("400.0000");
  }

  @Test
  void 利润表_收入减成本等于本期损益() throws Exception {
    seedStandardLedger();
    var result = report("income-statement", octPeriod, octPeriod, null, null, null, null);
    assertThat(rowOf(result, "USD", "6001").periodNet()).isEqualByComparingTo("500.0000");
    assertThat(rowOf(result, "USD", "6401").periodNet()).isEqualByComparingTo("200.0000");
    assertThat(rowOf(result, "USD", "TOTAL:REVENUE").periodNet()).isEqualByComparingTo("500.0000");
    assertThat(rowOf(result, "USD", "TOTAL:EXPENSE").periodNet()).isEqualByComparingTo("200.0000");
    assertThat(rowOf(result, "USD", "TOTAL:PROFIT").periodNet()).isEqualByComparingTo("300.0000");
  }

  @Test
  void 科目余额表_期末借贷合计相等() throws Exception {
    seedStandardLedger();
    var result = report("trial-balance", octPeriod, octPeriod, null, null, null, null);
    var usd = groupOf(result, "USD");
    assertThat(usd.checks()).hasSize(2);
    for (var check : usd.checks()) {
      assertThat(check.difference()).as(check.checkName()).isEqualByComparingTo("0.0000");
    }
    var total = rowOf(result, "USD", "TOTAL:ALL");
    assertThat(total.closingDebit()).isEqualByComparingTo("1600.0000");
    assertThat(total.closingCredit()).isEqualByComparingTo("1600.0000");
  }

  @Test
  void 明细账_逐笔带凭证号与行号且余额连续() throws Exception {
    seedStandardLedger();
    var result = report("general-ledger", octPeriod, octPeriod, null, null, "USD", "6001");
    var usd = groupOf(result, "USD");
    assertThat(usd.ledgerEntries()).isNotEmpty();
    var entry = usd.ledgerEntries().get(0);
    assertThat(entry.subjectCode()).isEqualTo("6001");
    assertThat(entry.voucherNo()).startsWith("VC-");
    assertThat(entry.drillPath()).isEqualTo("/finance/vouchers/" + entry.voucherId());
    // 逐笔余额从**期初余额**起算：9 月已累计 100（贷方科目）。
    assertThat(entry.runningBalance()).isEqualByComparingTo("600.0000");
    // 对方科目必须看得见。
    assertThat(entry.counterpartSummary()).contains("1122");
  }

  @Test
  void 明细账必须指定科目_否则如实报错() throws Exception {
    seedStandardLedger();
    var response =
        http.perform(
                get("/finance/financial-reports/general-ledger")
                    .header("Authorization", "session")
                    .param("periodFromId", octPeriod.toString())
                    .param("periodToId", octPeriod.toString()))
            .andReturn();
    assertThat(response.getResponse().getStatus()).isEqualTo(200);
    var result = json.readValue(response.getResponse().getContentAsString(), FinanceReportResult.class);
    assertThat(result.notice()).contains("必须指定一个科目编码");
  }

  // ==========================================================================
  // 科目是软引用：没在科目表登记 ⇒ 归「未分类」，不并入合计，如实报出差额
  // ==========================================================================
  @Test
  void 未在科目表登记的科目归入未分类且不并入合计() throws Exception {
    seedStandardLedger();
    // 9999 没有在 12.5.1.1 的科目表登记 ⇒ 报表不得假装知道它是资产还是负债。
    seedPostedVoucher(
        octPeriod,
        LocalDate.of(2026, 10, 15),
        "USD",
        "VC-202610-0005",
        "10 月未登记科目",
        List.of(
            new SeedLine("DEBIT", "9999", "60.0000"), new SeedLine("CREDIT", "6001", "60.0000")));
    var result = report("balance-sheet", octPeriod, octPeriod, null, null, null, null);
    var unclassified = rowOf(result, "USD", "9999");
    assertThat(unclassified.section()).isEqualTo("UNCLASSIFIED");
    assertThat(unclassified.sectionLabel()).isEqualTo("未分类");
    // 资产合计里**不含**这笔 60（未分类不并入任何合计）。
    assertThat(rowOf(result, "USD", "TOTAL:ASSET").closingBalance()).isEqualByComparingTo("1400.0000");
    // 恒等式差额**如实报出来**（差额 = 未登记科目的金额），不自欺。
    var identity =
        groupOf(result, "USD").checks().stream()
            .filter(c -> c.checkName().contains("会计恒等式"))
            .findFirst()
            .orElseThrow();
    assertThat(identity.balanced()).isFalse();
    assertThat(identity.difference().abs()).isEqualByComparingTo("60.0000");
    assertThat(identity.detail()).contains("没在 12.5.1.1 科目表登记");
  }

  // ==========================================================================
  // 期间对比
  // ==========================================================================
  @Test
  void 期间对比_两段期间并排且给出变动额() throws Exception {
    seedStandardLedger();
    var result = report("balance-sheet", octPeriod, octPeriod, septPeriod, septPeriod, null, null);
    assertThat(result.comparePeriodLabel()).isEqualTo("2026-09");
    var equity = rowOf(result, "USD", "4001");
    // 本期期末 1000，对比期期末 1000 ⇒ 变动额 0。
    assertThat(equity.comparisonClosingBalance()).isEqualByComparingTo("1000.0000");
    assertThat(equity.deltaClosingBalance()).isEqualByComparingTo("0.0000");
    // 收入科目：本期期末 600（累计），对比期 100 ⇒ 变动 500。
    var revenue = rowOf(result, "USD", "6001");
    assertThat(revenue.comparisonClosingBalance()).isEqualByComparingTo("100.0000");
    assertThat(revenue.deltaClosingBalance()).isEqualByComparingTo("500.0000");
  }

  // ==========================================================================
  // 现金流量表（模板驱动）
  // ==========================================================================
  @Test
  void 现金流量表未配置现金科目时出数零行并说明原因() throws Exception {
    seedStandardLedger();
    var result = report("cash-flow", octPeriod, octPeriod, null, null, null, null);
    assertThat(result.currencyGroups()).isNotEmpty();
    for (var group : result.currencyGroups()) {
      assertThat(group.rows()).as("没配现金科目就不能虚构现金流").isEmpty();
    }
    assertThat(result.notice()).contains("未配置现金科目");
  }

  @Test
  void 现金流量表配置现金科目后按对方科目归类() throws Exception {
    seedStandardLedger();
    // 现金科目：1002 银行存款；对方科目：6001 收入 → 经营活动。
    createTemplate("CASH_FLOW", "1002", "CASH", null, "现金科目");
    createTemplate("CASH_FLOW", "6001", "NON_CASH", "OPERATING", "收入");
    createTemplate("CASH_FLOW", "6401", "NON_CASH", "OPERATING", "成本");
    var result = report("cash-flow", octPeriod, octPeriod, null, null, null, null);
    var operating = rowOf(result, "USD", "CASH_FLOW:OPERATING");
    // 10 月：借 6401 200 / 贷 1002 200 ⇒ 对方是借方 ⇒ 现金流出 200。
    assertThat(operating.periodCredit()).isEqualByComparingTo("200.0000");
    assertThat(operating.periodNet()).isEqualByComparingTo("-200.0000");
    assertThat(operating.drillPath()).contains("lineCode=CASH_FLOW%3AOPERATING");
  }

  // ==========================================================================
  // 导出
  // ==========================================================================
  @Test
  void 导出csv带bom且内容真实并写一条留痕() throws Exception {
    seedStandardLedger();
    var response =
        http.perform(
                get("/finance/financial-reports/export")
                    .header("Authorization", "session")
                    .param("reportType", "BALANCE_SHEET")
                    .param("format", "CSV")
                    .param("periodFromId", octPeriod.toString())
                    .param("periodToId", octPeriod.toString()))
            .andReturn();
    assertThat(response.getResponse().getStatus()).isEqualTo(200);
    var content = response.getResponse().getContentAsString();
    assertThat(content).as("CSV 必须带 UTF-8 BOM").startsWith("\uFEFF");
    assertThat(content).contains("报表行编码").contains("1001").contains("1400.0000");
    assertThat(response.getResponse().getHeader("Content-Disposition"))
        .contains("financial-report-balance_sheet-2026-10-2026-10.csv");

    // 留痕：一条 append-only 的导出记录 + SHA-256。
    var events =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                        "select report_type, format, row_count, content_hash from finance_report_export_event")
                    .map(
                        r ->
                            r.get("report_type", String.class)
                                + "|"
                                + r.get("format", String.class)
                                + "|"
                                + r.get("row_count", Integer.class)
                                + "|"
                                + r.get("content_hash", String.class)));
    assertThat(events).hasSize(1);
    assertThat(events.get(0)).startsWith("BALANCE_SHEET|CSV|");
    assertThat(events.get(0)).matches(".*\\|[0-9a-f]{64}$");
  }

  @Test
  void 导出json是真实内容() throws Exception {
    seedStandardLedger();
    var response =
        http.perform(
                get("/finance/financial-reports/export")
                    .header("Authorization", "session")
                    .param("reportType", "TRIAL_BALANCE")
                    .param("format", "JSON")
                    .param("periodFromId", octPeriod.toString())
                    .param("periodToId", octPeriod.toString()))
            .andReturn();
    assertThat(response.getResponse().getStatus()).isEqualTo(200);
    var content = response.getResponse().getContentAsString();
    assertThat(content).contains("\"reportType\"").contains("TRIAL_BALANCE").contains("1002");
  }

  @Test
  void 导出格式只支持csv与json() throws Exception {
    seedStandardLedger();
    http.perform(
            get("/finance/financial-reports/export")
                .header("Authorization", "session")
                .param("reportType", "TRIAL_BALANCE")
                .param("format", "XLSX")
                .param("periodFromId", octPeriod.toString())
                .param("periodToId", octPeriod.toString()))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 报表模板
  // ==========================================================================
  @Test
  void 报表模板可登记可停用且能归并科目() throws Exception {
    seedStandardLedger();
    // 把 1001 与 1002 归并成一行「货币资金」。
    var created = createTemplate("BALANCE_SHEET", "1001", null, null, "货币资金");
    createTemplate("BALANCE_SHEET", "1002", null, null, "货币资金");
    var result = report("balance-sheet", octPeriod, octPeriod, null, null, null, null);
    var merged = rowOf(result, "USD", "货币资金");
    assertThat(merged.subjectCode()).as("归并了多个科目就不再指向单一科目").isNull();
    assertThat(merged.closingBalance()).isEqualByComparingTo("900.0000");

    // 停用后模板不再生效 ⇒ 回到「一行一个科目」。
    http.perform(
            post("/finance/financial-reports/templates/" + created.id() + "/disable")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());
    var after = report("balance-sheet", octPeriod, octPeriod, null, null, null, null);
    assertThat(rowOf(after, "USD", "1001").closingBalance()).isEqualByComparingTo("100.0000");
  }

  @Test
  void 模板缺现金角色时如实拒绝() throws Exception {
    var body =
        "{\"reportType\":\"CASH_FLOW\",\"subjectCode\":\"1002\",\"lineCode\":\"CASH\"}";
    http.perform(
            post("/finance/financial-reports/templates")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
    // 拒绝理由走**服务层**（HTTP 层只断言状态码，与本仓其它模块一致）。
    var actor = actors.get("session");
    assertThatThrownBy(
            () ->
                reports.createTemplate(
                    json.readValue(body, FinanceReportTemplateInput.class), actor))
        .as("现金流量表的模板行必须说清现金角色")
        .hasStackTraceContaining("现金角色");
  }

  @Test
  void 模板不可删除_只能停用() throws Exception {
    var created = createTemplate("BALANCE_SHEET", "1001", null, null, "货币资金");
    // 后端**没有**删除端点 ⇒ DELETE 落到 405（方法不允许）。
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    "/finance/financial-reports/templates/" + created.id())
                .header("Authorization", "session"))
        .andExpect(status().isMethodNotAllowed());
  }

  FinanceReportTemplateRow createTemplate(
      String reportType, String subjectCode, String cashRole, String cashGroup, String lineName)
      throws Exception {
    var body =
        new java.util.LinkedHashMap<String, Object>();
    body.put("reportType", reportType);
    body.put("subjectCode", subjectCode);
    body.put("lineCode", lineName == null ? subjectCode : lineName);
    body.put("lineName", lineName == null ? "" : lineName);
    if (cashGroup != null) body.put("cashFlowGroup", cashGroup);
    if (cashRole != null) body.put("cashRole", cashRole);
    var response =
        http.perform(
                post("/finance/financial-reports/templates")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andReturn();
    var content = response.getResponse().getContentAsString();
    assertThat(response.getResponse().getStatus()).as("登记模板失败：" + content).isEqualTo(200);
    return json.readValue(content, FinanceReportTemplateRow.class);
  }

  // ==========================================================================
  // 期间范围校验
  // ==========================================================================
  @Test
  void 期间范围不合法时如实拒绝() throws Exception {
    seedStandardLedger();
    http.perform(
            get("/finance/financial-reports/balance-sheet")
                .header("Authorization", "session")
                .param("periodFromId", octPeriod.toString())
                .param("periodToId", septPeriod.toString()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 缺期间范围时如实拒绝() throws Exception {
    seedStandardLedger();
    http.perform(
            get("/finance/financial-reports/balance-sheet").header("Authorization", "session"))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 能力声明与筛选项
  // ==========================================================================
  @Test
  void 能力声明逐条说清口径与前置条件() throws Exception {
    var response =
        http.perform(
                get("/finance/financial-reports/capabilities").header("Authorization", "session"))
            .andReturn();
    assertThat(response.getResponse().getStatus()).isEqualTo(200);
    var caps =
        json.readValue(response.getResponse().getContentAsString(), FinanceReportCapabilities.class);
    assertThat(caps.bases()).hasSize(5);
    for (var basis : caps.bases()) {
      assertThat(basis.draftPolicy()).as(basis.reportType() + " 必须写清草稿口径").contains("草稿");
      assertThat(basis.reversalPolicy()).as(basis.reportType() + " 必须写清红冲口径").contains("红冲");
      assertThat(basis.currencyPolicy()).as(basis.reportType() + " 必须写清多币种口径").contains("币种");
    }
    assertThat(caps.capabilities()).anyMatch(c -> c.name().contains("现金流量表需要先配置"));
  }

  @Test
  void 筛选项来自真实数据() throws Exception {
    seedStandardLedger();
    var response =
        http.perform(
                get("/finance/financial-reports/options").header("Authorization", "session"))
            .andReturn();
    assertThat(response.getResponse().getStatus()).isEqualTo(200);
    var options =
        json.readValue(response.getResponse().getContentAsString(), FinanceReportOptions.class);
    assertThat(options.periods()).hasSize(3);
    assertThat(options.currencies()).containsExactly("EUR", "USD");
    assertThat(options.subjects()).extracting(FinanceReportSubjectRef::subjectCode)
        .contains("1001", "6001");
    assertThat(options.reportTypes()).hasSize(5);
  }
}
