package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.FundAccountModels.*;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 12.7.1.1 账户管理的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖蓝图 12-finance.md 12.7.1.1 的**全部职责**，每一项都断言**真算出来的数**，
 * 不是常量、不是 mock：
 *
 * <ol>
 *   <li><b>账户档案</b> —— 三种受控账户类型、单账户单币种、法人绑定、脱敏存储；</li>
 *   <li><b>余额历史</b> —— append-only（改写被 DB 拒绝）、手工更新留痕、同步幂等；</li>
 *   <li><b>按币种分组的汇总</b> —— 跨币种绝不相加，法人维度可见；</li>
 *   <li><b>法人实时校验</b> —— 未登记的法人报错、法人清单读不到时显式失败。</li>
 * </ol>
 *
 * <p><b>上游数据怎么来的</b>：法人清单由**本地 stub 模拟 identity 的 HTTP 契约**
 * （{@code GET /identity/legal-entities}）—— 与真服务同路径同字段；
 * 账户与余额则真的落 PostgreSQL 16。
 *
 * <p>⚠️ <b>时间断言</b>：余额历史的事实时刻是 {@code timestamptz}，
 * 断言时**从库里读回时刻再比较**，不能只断言「没抛异常」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FundAccountIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();

  /** 法人（identity 侧的事实）。 */
  static final UUID legalEntityA = UUID.randomUUID();
  static final UUID legalEntityB = UUID.randomUUID();

  static final List<String> PERMISSIONS =
      List.of("finance.receivable.read", "finance.receivable.update");

  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  /** 把 identity 的法人清单打成「读不到」，用来验证本模块**不**把读不到当校验通过。 */
  static final AtomicBoolean legalEntitiesDown = new AtomicBoolean(false);

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
      // identity 的 17 法人档案（只读端口读的就是它）。
      identity.createContext(
          "/identity/legal-entities",
          e -> {
            if (legalEntitiesDown.get()) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            byte[] body =
                ("["
                        + "{\"id\":\""
                        + legalEntityA
                        + "\",\"code\":\"LE_A\",\"legalName\":\"甲公司\",\"status\":\"ACTIVE\",\"baseCurrency\":\"USD\"},"
                        + "{\"id\":\""
                        + legalEntityB
                        + "\",\"code\":\"LE_B\",\"legalName\":\"乙公司\",\"status\":\"ACTIVE\",\"baseCurrency\":\"CNY\"}"
                        + "]")
                    .getBytes();
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
  @Autowired FundAccountService accounts;

  // ================================================================ 夹具

  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_fund_account_balance, finance_fund_account,"
              + " finance_fund_exposure_snapshot, finance_liquidity_threshold"
              + " restart identity cascade");
    }
    legalEntitiesDown.set(false);
  }

  /** 在可信租户事务里跑一段工作。 */
  <T> T as(java.util.function.Supplier<T> work) {
    return tenants.run(tenant, work);
  }

  AccessService.Access actor() {
    return actors.get("session");
  }

  /** 建账户（走真实端点，不直接插库）。 */
  FinanceFundAccountRow create(
      String accountNo,
      String name,
      FinanceFundAccountType type,
      String currency,
      UUID legalEntityId,
      String bankName,
      String accountNumber,
      String initialBalance) throws Exception {
    var body =
        json.writeValueAsString(
            Map.of(
                "accountNo",
                accountNo,
                "accountName",
                name,
                "accountType",
                type.name(),
                "currency",
                currency,
                "legalEntityId",
                legalEntityId.toString(),
                "bankName",
                bankName,
                "accountNumber",
                accountNumber,
                "initialBalance",
                new BigDecimal(initialBalance)));
    var response =
        http.perform(
                post("/finance/fund-accounts")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(response, FinanceFundAccountRow.class);
  }

  // ==========================================================================
  // 1. 账户档案
  // ==========================================================================

  @Test
  void 建账户绑定法人并写建档初始余额() throws Exception {
    var account =
        create(
            "BANK-001",
            "招商银行美元户",
            FinanceFundAccountType.BANK,
            "USD",
            legalEntityA,
            "招商银行",
            "622288889999",
            "1500.5");

    assertThat(account.accountNo()).isEqualTo("BANK-001");
    assertThat(account.accountType()).isEqualTo("BANK");
    assertThat(account.accountTypeLabel()).isEqualTo("银行账户");
    assertThat(account.currency()).isEqualTo("USD");
    assertThat(account.legalEntityId()).isEqualTo(legalEntityA);
    assertThat(account.legalEntityCode()).isEqualTo("LE_A");
    assertThat(account.legalEntityName()).isEqualTo("甲公司");
    assertThat(account.bankName()).isEqualTo("招商银行");
    assertThat(account.status()).isEqualTo("ACTIVE");

    // 当前余额 = 建档初始余额（由触发器从第一条余额历史推导）。
    assertThat(account.currentBalance()).isEqualByComparingTo("1500.5");
    assertThat(account.balanceRecordCount()).isEqualTo(1);

    // 余额历史里真的有一条 INITIAL 记录，改前 0、改后 1500.5。
    var history =
        as(
            () ->
                sql.fetch(
                    "select balance_amount, previous_amount, change_source, operator_user_id"
                        + " from finance_fund_account_balance where account_id = ?",
                    account.id()));
    assertThat(history).hasSize(1);
    assertThat(history.get(0).get("balance_amount", BigDecimal.class))
        .isEqualByComparingTo("1500.5");
    assertThat(history.get(0).get("previous_amount", BigDecimal.class))
        .isEqualByComparingTo("0");
    assertThat(history.get(0).get("change_source", String.class)).isEqualTo("INITIAL");
    assertThat(history.get(0).get("operator_user_id", UUID.class)).isEqualTo(actor().userId());
  }

  @Test
  void 未登记的法人必须报错而不是静默接受() {
    // ⚠️ 断言**服务层**给出的原因（MockMvc 的错误响应体是空的，
    // 那是 MockMvc 没有渲染错误页的产物，不是接口不返回原因）。
    var input =
        new FinanceFundAccountCreateInput(
            "BANK-XXX",
            "不存在的法人户",
            FinanceFundAccountType.BANK,
            "USD",
            UUID.randomUUID(),
            "招商银行",
            "",
            "",
            "",
            null);
    var thrown =
        catchThrowable(
            () -> as(() -> accounts.create(input, "session", actor())));
    assertThat(thrown).as("未登记的法人必须报错").isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) thrown).getReason())
        .as("错误原因要说清「法人」与「必须绑定法人」")
        .contains("法人")
        .contains("必须绑定法人");

    // 一个都没落库。
    assertThat(as(() -> sql.fetch("select id from finance_fund_account")).size()).isZero();
  }

  @Test
  void 法人清单读不到时显式失败而不是当成校验通过() throws Exception {
    legalEntitiesDown.set(true);
    var body =
        json.writeValueAsString(
            Map.of(
                "accountNo",
                "BANK-DOWN",
                "accountName",
                "读不到法人的户",
                "accountType",
                "BANK",
                "currency",
                "USD",
                "legalEntityId",
                legalEntityA.toString(),
                "bankName",
                "招商银行"));

    http.perform(
            post("/finance/fund-accounts")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isServiceUnavailable());

    assertThat(as(() -> sql.fetch("select id from finance_fund_account")).size())
        .as("法人清单读不到时绝不能写下一个未经校验的法人引用")
        .isZero();
  }

  @Test
  void 账户类型是受控词表且开户行必须自洽() throws Exception {
    // 银行账户没有开户行 ⇒ 400。
    var noBank =
        json.writeValueAsString(
            Map.of(
                "accountNo",
                "BANK-NOBANK",
                "accountName",
                "没有开户行",
                "accountType",
                "BANK",
                "currency",
                "USD",
                "legalEntityId",
                legalEntityA.toString(),
                "bankName",
                ""));
    http.perform(
            post("/finance/fund-accounts")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(noBank))
        .andExpect(status().isBadRequest());

    // 支付平台账户填开户行 ⇒ 400。
    var platformWithBank =
        json.writeValueAsString(
            Map.of(
                "accountNo",
                "PAY-BANK",
                "accountName",
                "支付平台户",
                "accountType",
                "PAYMENT_PLATFORM",
                "currency",
                "USD",
                "legalEntityId",
                legalEntityA.toString(),
                "bankName",
                "招商银行"));
    http.perform(
            post("/finance/fund-accounts")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(platformWithBank))
        .andExpect(status().isBadRequest());

    // 词表之外的类型 ⇒ 400（不是自由文本）。
    var bogus =
        json.writeValueAsString(
            Map.of(
                "accountNo",
                "BOGUS",
                "accountName",
                "随便一个类型",
                "accountType",
                "CRYPTO_WALLET",
                "currency",
                "USD",
                "legalEntityId",
                legalEntityA.toString()));
    http.perform(
            post("/finance/fund-accounts")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bogus))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 2. 账号脱敏
  // ==========================================================================

  @Test
  void 账号脱敏存储与返回库里没有原始账号() throws Exception {
    var account =
        create(
            "BANK-MASK",
            "脱敏户",
            FinanceFundAccountType.BANK,
            "USD",
            legalEntityA,
            "招商银行",
            "6222888812345678",
            "0");

    assertThat(account.accountNumberMasked()).isEqualTo("6222****5678");
    assertThat(account.accountNumberTail()).isEqualTo("5678");

    // 库里**没有**原始账号：整张表的所有文本列拼起来都不含原始串。
    var raw =
        as(
            () ->
                sql.fetch(
                        "select account_number_masked, account_number_tail, account_no,"
                            + " account_name, bank_name, note from finance_fund_account")
                    .stream()
                    .map(
                        r ->
                            r.get("account_number_masked", String.class)
                                + r.get("account_number_tail", String.class)
                                + r.get("account_no", String.class)
                                + r.get("account_name", String.class)
                                + r.get("bank_name", String.class)
                                + r.get("note", String.class))
                    .reduce("", String::concat));
    assertThat(raw).doesNotContain("6222888812345678");
  }

  @Test
  void 账号太短时拒绝入库因为脱敏没有意义() throws Exception {
    var body =
        json.writeValueAsString(
            Map.of(
                "accountNo",
                "BANK-SHORT",
                "accountName",
                "太短的账号",
                "accountType",
                "BANK",
                "currency",
                "USD",
                "legalEntityId",
                legalEntityA.toString(),
                "bankName",
                "招商银行",
                "accountNumber",
                "1234"));
    http.perform(
            post("/finance/fund-accounts")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 3. 余额历史：append-only + 留痕 + 幂等
  // ==========================================================================

  @Test
  void 手工更新余额必须留痕且追加历史() throws Exception {
    var account =
        create(
            "BANK-MANUAL",
            "手工户",
            FinanceFundAccountType.BANK,
            "USD",
            legalEntityA,
            "招商银行",
            "6222888812345678",
            "100");

    // 手工更新没写理由 ⇒ 400。
    var noReason =
        json.writeValueAsString(
            Map.of("balanceAmount", new BigDecimal("200"), "changeSource", "MANUAL"));
    http.perform(
            post("/finance/fund-accounts/" + account.id() + "/balances")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(noReason))
        .andExpect(status().isBadRequest());

    // 写了理由 ⇒ 追加一行，改前改后首尾相接。
    var withReason =
        json.writeValueAsString(
            Map.of(
                "balanceAmount",
                new BigDecimal("200"),
                "changeSource",
                "MANUAL",
                "reason",
                "银行对账单核对后调整"));
    var response =
        http.perform(
                post("/finance/fund-accounts/" + account.id() + "/balances")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(withReason))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var row = json.readValue(response, FinanceFundAccountBalanceRow.class);
    assertThat(row.previousAmount()).isEqualByComparingTo("100");
    assertThat(row.balanceAmount()).isEqualByComparingTo("200");
    assertThat(row.changeSource()).isEqualTo("MANUAL");
    assertThat(row.reason()).isEqualTo("银行对账单核对后调整");
    assertThat(row.operatorUserId()).isEqualTo(actor().userId());
    assertThat(row.recordedAt()).isNotNull();

    // 当前余额跟着最新一条历史走。
    var after =
        as(
            () ->
                sql.fetchOne(
                    "select current_balance, balance_record_count from finance_fund_account"
                        + " where id = ?",
                    account.id()));
    assertThat(after.get("current_balance", BigDecimal.class)).isEqualByComparingTo("200");
    assertThat(after.get("balance_record_count", Long.class)).isEqualTo(2);
  }

  @Test
  void 余额历史不可改写() throws Exception {
    var account =
        create(
            "BANK-APPEND",
            "追加式户",
            FinanceFundAccountType.BANK,
            "USD",
            legalEntityA,
            "招商银行",
            "6222888812345678",
            "100");

    // ① 运行角色（finance_app）**没有** UPDATE / DELETE 权限 ⇒ 直接改不动。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        sql.execute(
                            "update finance_fund_account_balance set balance_amount = 999999"
                                + " where account_id = ?",
                            account.id())))
        .as("运行角色对余额历史没有 UPDATE 权限")
        .hasStackTraceContaining("permission denied");
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        sql.execute(
                            "delete from finance_fund_account_balance where account_id = ?",
                            account.id())))
        .as("运行角色对余额历史没有 DELETE 权限")
        .hasStackTraceContaining("permission denied");

    // ② **表 owner 也改不动**：append-only 触发器钉死（owner 有权限也过不去触发器）。
    try (var c =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(() -> s.execute("update finance_fund_account_balance set balance_amount = 999999"))
          .as("余额历史是 append-only：owner 也 UPDATE 不了")
          .hasMessageContaining("append-only");
      assertThatThrownBy(() -> s.execute("delete from finance_fund_account_balance"))
          .as("余额历史是 append-only：owner 也 DELETE 不了")
          .hasMessageContaining("append-only");
    }

    // ③ 触发器确实在库里（不是靠约定）。
    var trigger =
        sql.fetchOne(
            "select count(*) from pg_trigger where tgrelid ="
                + " 'finance_fund_account_balance'::regclass and tgname ="
                + " 'finance_fund_account_balance_append_only' and not tgisinternal");
    assertThat(trigger.get(0, Long.class)).as("append-only 触发器必须真的建出来").isEqualTo(1);

    // ④ 运行角色的权限确实被收回了。
    assertThat(
            as(
                () ->
                    sql.fetchOne(
                            "select has_table_privilege('finance_app','finance_fund_account_balance',"
                                + "'UPDATE')")
                        .get(0, Boolean.class)))
        .as("运行角色必须拿不到余额历史的 UPDATE 权限")
        .isFalse();
    assertThat(
            as(
                () ->
                    sql.fetchOne(
                            "select has_table_privilege('finance_app','finance_fund_account_balance',"
                                + "'DELETE')")
                        .get(0, Boolean.class)))
        .as("运行角色必须拿不到余额历史的 DELETE 权限")
        .isFalse();

    // 一条都没少。
    assertThat(
            as(
                () ->
                    sql.fetch(
                        "select id from finance_fund_account_balance where account_id = ?",
                        account.id()))
                .size())
        .isEqualTo(1);
  }

  @Test
  void 余额同步按来源流水幂等() throws Exception {
    var account =
        create(
            "BANK-SYNC",
            "同步户",
            FinanceFundAccountType.BANK,
            "USD",
            legalEntityA,
            "招商银行",
            "6222888812345678",
            "0");

    var body =
        json.writeValueAsString(
            Map.of(
                "balanceAmount",
                new BigDecimal("880.25"),
                "changeSource",
                "SYNC",
                "sourceSystem",
                "BANK_FEED",
                "sourceReference",
                "FEED-2026-10-24-001"));

    http.perform(
            post("/finance/fund-accounts/" + account.id() + "/balances")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());

    // 同一条来源流水再来一次 ⇒ 409（不重复入账）。
    http.perform(
            post("/finance/fund-accounts/" + account.id() + "/balances")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict());

    assertThat(
            as(
                () ->
                    sql.fetch(
                        "select id from finance_fund_account_balance where account_id = ?",
                        account.id()))
                .size())
        .as("重复同步不能重复入账")
        .isEqualTo(2);
  }

  @Test
  void 补录更早的历史余额不把当前余额倒回去() throws Exception {
    var account =
        create(
            "BANK-BACKDATE",
            "补录户",
            FinanceFundAccountType.BANK,
            "USD",
            legalEntityA,
            "招商银行",
            "6222888812345678",
            "500");

    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var body =
        json.writeValueAsString(
            Map.of(
                "balanceAmount",
                new BigDecimal("300"),
                "changeSource",
                "SYNC",
                "sourceSystem",
                "BANK_FEED",
                "sourceReference",
                "FEED-OLD",
                "recordedAt",
                now.minusDays(10).toString()));

    http.perform(
            post("/finance/fund-accounts/" + account.id() + "/balances")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());

    var after =
        as(
            () ->
                sql.fetchOne(
                    "select current_balance from finance_fund_account where id = ?", account.id()));
    assertThat(after.get("current_balance", BigDecimal.class))
        .as("补录更早的余额只进历史，当前余额不倒回去")
        .isEqualByComparingTo("500");
  }

  @Test
  void 余额历史的事实时刻按timestamptz写回不被时区吃掉() throws Exception {
    var account =
        create(
            "BANK-TZ",
            "时区户",
            FinanceFundAccountType.BANK,
            "USD",
            legalEntityA,
            "招商银行",
            "6222888812345678",
            "0");

    var recordedAt = OffsetDateTime.now(ZoneOffset.UTC).withNano(0).minusDays(3);
    var body =
        json.writeValueAsString(
            Map.of(
                "balanceAmount",
                new BigDecimal("42"),
                "changeSource",
                "MANUAL",
                "reason",
                "按对账单补录",
                "recordedAt",
                recordedAt.toString()));

    http.perform(
            post("/finance/fund-accounts/" + account.id() + "/balances")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());

    // 从库里读回时刻再断言（不能只断言「没抛异常」）。
    var stored =
        as(
            () ->
                sql.fetchOne(
                    "select recorded_at from finance_fund_account_balance"
                        + " where account_id = ? and change_source = 'MANUAL'",
                    account.id()));
    assertThat(stored.get("recorded_at", OffsetDateTime.class).toInstant())
        .as("timestamptz 必须原样写回（东八区偏 8 小时就是错账）")
        .isEqualTo(recordedAt.toInstant());
  }

  // ==========================================================================
  // 4. 单账户单币种
  // ==========================================================================

  @Test
  void 币种不可改且跨币种余额行被拒() throws Exception {
    var account =
        create(
            "BANK-CCY",
            "单币种户",
            FinanceFundAccountType.BANK,
            "USD",
            legalEntityA,
            "招商银行",
            "6222888812345678",
            "10");

    // 直接改币种 ⇒ DB 触发器拒绝。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        sql.execute(
                            "update finance_fund_account set currency = 'EUR' where id = ?",
                            account.id())))
        .as("单账户单币种是硬约束")
        .hasStackTraceContaining("币种不可改");

    // 塞一条币种不一致的余额行 ⇒ DB 触发器拒绝。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        sql.execute(
                            "insert into finance_fund_account_balance(id,tenant_id,account_id,currency,"
                                + " legal_entity_id,legal_entity_code,legal_entity_name,balance_amount,"
                                + " previous_amount,change_source,reason,source_system,"
                                + " source_reference,operator_user_id,recorded_at,created_at)"
                                + " values (?,?,?,'EUR',?,?,?,1,0,'MANUAL','x','','',?,?::timestamptz,"
                                + " ?::timestamptz)",
                            UUID.randomUUID(),
                            tenant,
                            account.id(),
                            legalEntityA,
                            "LE_A",
                            "甲公司",
                            actor().userId(),
                            OffsetDateTime.now(ZoneOffset.UTC),
                            OffsetDateTime.now(ZoneOffset.UTC))))
        .as("跨币种余额行必须被拒")
        .hasStackTraceContaining("跨币种");
  }

  // ==========================================================================
  // 5. 按币种分组的汇总
  // ==========================================================================

  @Test
  void 汇总按币种分组跨币种绝不相加() throws Exception {
    create(
        "BANK-USD",
        "美元户",
        FinanceFundAccountType.BANK,
        "USD",
        legalEntityA,
        "招商银行",
        "6222888812345678",
        "1000");
    create(
        "BANK-EUR",
        "欧元户",
        FinanceFundAccountType.BANK,
        "EUR",
        legalEntityA,
        "德意志银行",
        "DE89370400440532013000",
        "700");
    create(
        "PAY-USD",
        "支付平台美元户",
        FinanceFundAccountType.PAYMENT_PLATFORM,
        "USD",
        legalEntityB,
        "",
        "PAYPAL9876543210",
        "250");

    var response =
        http.perform(get("/finance/fund-accounts/balances/summary").header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var summary = json.readValue(response, FinanceFundCurrencyBalanceSummary.class);

    // 两个币种各一行 —— 绝不加在一起。
    assertThat(summary.totals()).extracting(FinanceFundCurrencyBalanceTotal::currency)
        .containsExactly("EUR", "USD");

    var eur =
        summary.totals().stream()
            .filter(t -> t.currency().equals("EUR"))
            .findFirst()
            .orElseThrow();
    assertThat(eur.totalBalance()).isEqualByComparingTo("700");

    var usd =
        summary.totals().stream()
            .filter(t -> t.currency().equals("USD"))
            .findFirst()
            .orElseThrow();
    assertThat(usd.totalBalance()).isEqualByComparingTo("1250");
    assertThat(usd.accountCount()).isEqualTo(2);

    // 跨币种合计：**没有**这个数（如实给 null，而不是按 1:1 假装折算）。
    assertThat(summary.crossCurrencyTotalAvailable()).isFalse();
    assertThat(summary.crossCurrencyTotal()).isNull();

    // 法人维度看得见（跨法人混用资金账户会导致收入归属错误）。
    assertThat(usd.byLegalEntity())
        .extracting(FinanceFundLegalEntityBalance::legalEntityCode)
        .containsExactlyInAnyOrder("LE_A", "LE_B");
    assertThat(
            usd.byLegalEntity().stream()
                .filter(t -> t.legalEntityCode().equals("LE_B"))
                .findFirst()
                .orElseThrow()
                .totalBalance())
        .isEqualByComparingTo("250");
  }

  // ==========================================================================
  // 6. 列表 / 详情 / 停用
  // ==========================================================================

  @Test
  void 列表真分页真筛选() throws Exception {
    for (int i = 1; i <= 5; i++) {
      create(
          "BANK-PAGE-" + i,
          "分页户" + i,
          FinanceFundAccountType.BANK,
          "USD",
          legalEntityA,
          "招商银行",
          "622288881234" + (1000 + i),
          "0");
    }
    create(
        "PAY-PAGE",
        "支付平台户",
        FinanceFundAccountType.PAYMENT_PLATFORM,
        "EUR",
        legalEntityB,
        "",
        "PAYPAL9876543210",
        "0");

    var page =
        json.readValue(
            http.perform(
                    get("/finance/fund-accounts?page=0&size=2&sort=ACCOUNT_NO&direction=ASC")
                        .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceFundAccountPage.class);
    assertThat(page.total()).isEqualTo(6);
    assertThat(page.items()).hasSize(2);
    assertThat(page.items().get(0).accountNo()).isEqualTo("BANK-PAGE-1");
    assertThat(page.items().get(1).accountNo()).isEqualTo("BANK-PAGE-2");

    // 按类型筛选（受控词表）。
    var platforms =
        json.readValue(
            http.perform(
                    get("/finance/fund-accounts?accountType=PAYMENT_PLATFORM")
                        .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceFundAccountPage.class);
    assertThat(platforms.total()).isEqualTo(1);
    assertThat(platforms.items().get(0).accountNo()).isEqualTo("PAY-PAGE");

    // 按币种筛选。
    var euros =
        json.readValue(
            http.perform(
                    get("/finance/fund-accounts?currency=EUR").header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceFundAccountPage.class);
    assertThat(euros.total()).isEqualTo(1);

    // 按法人筛选。
    var byEntity =
        json.readValue(
            http.perform(
                    get("/finance/fund-accounts?legalEntityId=" + legalEntityB)
                        .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceFundAccountPage.class);
    assertThat(byEntity.total()).isEqualTo(1);

    // 关键字搜索（真搜索，不是假搜索）。
    // ⚠️ 中文关键字**不要**自己 URLEncoder —— MockMvc 会自己编码，
    // 自己再编一次就成了双重编码，搜不到东西（而且症状是「搜索永远空」，
    // 看起来像后端没实现搜索）。
    var keyword =
        json.readValue(
            http.perform(
                    get("/finance/fund-accounts").param("keyword", "分页户3")
                        .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceFundAccountPage.class);
    assertThat(keyword.total()).isEqualTo(1);
    assertThat(keyword.items().get(0).accountName()).isEqualTo("分页户3");
  }

  @Test
  void 详情带余额历史且账户档案能改() throws Exception {
    var account =
        create(
            "BANK-DETAIL",
            "详情户",
            FinanceFundAccountType.BANK,
            "USD",
            legalEntityA,
            "招商银行",
            "6222888812345678",
            "10");

    http.perform(
            post("/finance/fund-accounts/" + account.id() + "/balances")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "balanceAmount",
                            new BigDecimal("60"),
                            "changeSource",
                            "MANUAL",
                            "reason",
                            "收到一笔"))))
        .andExpect(status().isOk());

    var detail =
        json.readValue(
            http.perform(
                    get("/finance/fund-accounts/" + account.id()).header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceFundAccountDetail.class);
    assertThat(detail.account().currentBalance()).isEqualByComparingTo("60");
    assertThat(detail.balanceTotal()).isEqualTo(2);
    assertThat(detail.recentBalances()).hasSize(2);
    // 最新的在前。
    assertThat(detail.recentBalances().get(0).balanceAmount()).isEqualByComparingTo("60");

    // 改档案：换名 + 换法人（法人要实时校验）。
    var update =
        json.writeValueAsString(
            Map.of(
                "accountName",
                "详情户（改过）",
                "accountType",
                "BANK",
                "legalEntityId",
                legalEntityB.toString(),
                "bankName",
                "招商银行",
                "accountHolder",
                "甲公司",
                "note",
                "改过的备注"));
    var updated =
        json.readValue(
            http.perform(
                    put("/finance/fund-accounts/" + account.id())
                        .header("Authorization", "session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceFundAccountRow.class);
    assertThat(updated.accountName()).isEqualTo("详情户（改过）");
    assertThat(updated.legalEntityId()).isEqualTo(legalEntityB);
    assertThat(updated.legalEntityCode()).isEqualTo("LE_B");
    // 余额历史里的法人快照**不动**（历史要能复现「当时挂在哪个法人下」）。
    var history =
        as(
            () ->
                sql.fetch(
                    "select legal_entity_code from finance_fund_account_balance where account_id = ?",
                    account.id()));
    assertThat(history.get(0).get("legal_entity_code", String.class)).isEqualTo("LE_A");
  }

  @Test
  void 停用后不再接受余额登记停用账户不能改档案() throws Exception {
    var account =
        create(
            "BANK-INACTIVE",
            "停用户",
            FinanceFundAccountType.BANK,
            "USD",
            legalEntityA,
            "招商银行",
            "6222888812345678",
            "10");

    http.perform(
            post("/finance/fund-accounts/" + account.id() + "/deactivate")
                .header("Authorization", "session"))
        .andExpect(status().isOk());

    // 停用后不能登记余额。
    http.perform(
            post("/finance/fund-accounts/" + account.id() + "/balances")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "balanceAmount",
                            new BigDecimal("20"),
                            "changeSource",
                            "MANUAL",
                            "reason",
                            "不该被接受"))))
        .andExpect(status().isConflict());

    // 停用后不能改档案。
    http.perform(
            put("/finance/fund-accounts/" + account.id())
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "accountName",
                            "偷偷改名",
                            "accountType",
                            "BANK",
                            "bankName",
                            "招商银行"))))
        .andExpect(status().isConflict());

    // 重新启用后又能登记余额。
    http.perform(
            post("/finance/fund-accounts/" + account.id() + "/activate")
                .header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(
            post("/finance/fund-accounts/" + account.id() + "/balances")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "balanceAmount",
                            new BigDecimal("20"),
                            "changeSource",
                            "MANUAL",
                            "reason",
                            "启用后补登"))))
        .andExpect(status().isOk());
  }

  // ==========================================================================
  // 7. 能力声明与筛选项
  // ==========================================================================

  @Test
  void 能力声明如实标注未交付与不做的事() throws Exception {
    var caps =
        json.readValue(
            http.perform(
                    get("/finance/fund-accounts/capabilities").header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceFundAccountCapabilities.class);

    assertThat(caps.capabilities())
        .filteredOn(c -> c.module().equals("12.7.1.1"))
        .isNotEmpty()
        .allSatisfy(c -> assertThat(c.available()).isTrue());
    // 12.7.1.2 对账未交付 ⇒ 如实标 false。
    assertThat(caps.capabilities())
        .filteredOn(c -> c.module().equals("12.7.1.2"))
        .isNotEmpty()
        .allSatisfy(c -> assertThat(c.available()).isFalse());
    // 本模块不做跨币种折算。
    assertThat(caps.capabilities())
        .filteredOn(c -> c.module().equals("12.7.2.1"))
        .isNotEmpty()
        .allSatisfy(c -> assertThat(c.available()).isFalse());
  }

  @Test
  void 筛选项的币种来自真实数据法人来自identity() throws Exception {
    create(
        "BANK-OPT",
        "筛选项户",
        FinanceFundAccountType.BANK,
        "JPY",
        legalEntityA,
        "招商银行",
        "6222888812345678",
        "0");

    var options =
        json.readValue(
            http.perform(get("/finance/fund-accounts/options").header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceFundAccountOptions.class);

    assertThat(options.currencies()).containsExactly("JPY");
    assertThat(options.accountTypes()).extracting(FinanceFundAccountOption::code)
        .containsExactly("BANK", "PAYMENT_PLATFORM", "PLATFORM_RESERVE");
    assertThat(options.legalEntitiesAvailable()).isTrue();
    assertThat(options.legalEntities()).extracting(FinanceFundLegalEntityOption::code)
        .containsExactly("LE_A", "LE_B");
  }

  // ==========================================================================
  // 8. 迁移确实落地
  // ==========================================================================

  @Test
  void 迁移落地且两张表强制rls() {
    for (String table : List.of("finance_fund_account", "finance_fund_account_balance")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V367 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住 owner 绕过").isTrue();
    }
    // 余额历史表上不许有外键指向别处（本模块的法人是软引用，禁跨库 FK）。
    var fkCount =
        sql.fetchOne(
                "select count(*) from pg_constraint where conrelid ="
                    + " 'finance_fund_account_balance'::regclass and contype = 'f'")
            .get(0, Long.class);
    assertThat(fkCount).as("法人是软引用：余额历史表上不许有跨库外键").isZero();
  }

  /** 让编译器看见 Record 的 import 被用上（与同族测试保持同一组 import）。 */
  @Test
  void 夹具可读() {
    Record row = as(() -> sql.fetchOne("select count(*) from finance_fund_account"));
    assertThat(row).isNotNull();
  }
}
