package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.PaymentPlanModels.*;
import com.starcon.finance.PayableModels.*;
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
 * 12.1.2.2 付款计划的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖的是蓝图 12-finance.md 12.1.2.2 里那两句**加粗句**与「关键约束」：
 *
 * <ul>
 *   <li>「按付款条件（<b>账期、里程碑、到期日</b>）生成付款计划」⇒ 三种条件**各是各的算法**：
 *       到期日取上游事实、账期要租户自己配的规则、里程碑要拆条按比例；
 *   <li>「<b>支持按供应商与到期窗口批量安排付款</b>」⇒ 安排是真的批量落库、按币种分批、
 *       批次金额由 DB 触发器派生；同一张应付不会被排两次；
 *   <li>「<b>付款计划与资金预测联动</b>：跨境资金常有回款延迟与付款刚性，错配会导致现金流断裂」
 *       ⇒ 预测按 (币种 × 到期窗口) 实时聚合，**计划改了预测当场跟着变**；
 *   <li>下游 12.2.2.1 未交付 ⇒ <b>不造付款单、不写「已付款」</b>（表级 CHECK 钉死）；
 *   <li>下游 1.3 未交付 ⇒ <b>不推送</b>（push_status 恒为 NOT_PUSHED）。
 * </ul>
 *
 * <p><b>断言风格与本仓其它模块一致</b>：业务规则与拒绝理由走**服务层**
 * （{@code assertThatThrownBy(...).hasStackTraceContaining(...)}），HTTP 层只断言**状态码**。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentPlanIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID supplier = UUID.randomUUID();
  static final List<String> PERMISSIONS =
      List.of("finance.paymentplan.read", "finance.paymentplan.create", "finance.paymentplan.update");

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
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired PaymentPlanService plans;
  @Autowired PayableService payables;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  /** 清库。用迁移用户（超管）连接：RLS 对超管不生效。 */
  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_payment_plan_event, finance_payment_reminder,"
              + " finance_payment_plan, finance_payment_batch, finance_payment_milestone,"
              + " finance_payment_milestone_rule, finance_payment_term_rule,"
              + " finance_payable_event, finance_payable_line, finance_payable,"
              + " finance_prepayment_allocation, finance_prepayment, finance_payable_policy,"
              + " finance_receivable_event, finance_receivable_line, finance_receivable,"
              + " finance_receivable_policy, finance_collection_task"
              + " restart identity cascade");
    }
  }

  /** 在**租户事务里**跑一段工作（与生产同一条路径：RLS 靠它隔离）。 */
  <T> T as(Supplier<T> work) {
    return tenants.run(tenant, work);
  }

  /** 在**租户事务里**读一行。 */
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
   * <p>触发器断言必须这么跑：超管不受 RLS 干扰，报出来的错就是**触发器**的错。
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

  /** 人工录入一张应付单（**上游 12.1.2.1 的服务层**，不是本模块直接写上游表）。 */
  FinancePayableRow createPayable(String no, String currency, String amount, LocalDate dueDate) {
    as(
        () ->
            payables.updatePolicy(
                PayableSource.MANUAL,
                ObligationKind.PAYABLE,
                new FinancePayablePolicyInput(true, 30, "测试夹具"),
                actor()));
    var input =
        new FinancePayableInput(
            PayableSource.MANUAL,
            CounterpartyType.SUPPLIER,
            supplier,
            "供应商甲",
            currency,
            null,
            null,
            dueDate,
            "测试夹具",
            List.of(new FinancePayableLineInput("明细", null, null, null, null, new BigDecimal(amount))));
    return as(() -> payables.create(input, actor()));
  }

  /** 建一条账期规则。 */
  FinancePaymentTermRuleRow createTermRule(int termDays, TermBaseKind baseKind) {
    return as(
        () ->
            plans.createTermRule(
                new FinancePaymentTermRuleInput(
                    "供应商甲 " + termDays + " 天账期", supplier, "USD", baseKind, termDays, true, "测试夹具"),
                actor()));
  }

  /** 建一套里程碑规则（30% / 70%）。 */
  FinancePaymentMilestoneRuleRow createMilestoneRule() {
    return as(
        () ->
            plans.createMilestoneRule(
                new FinancePaymentMilestoneRuleInput(
                    "供应商甲 两期付款",
                    supplier,
                    "USD",
                    true,
                    "测试夹具",
                    List.of(
                        new FinancePaymentMilestoneInput(1, "首期 30%", 0, null, new BigDecimal("0.3")),
                        new FinancePaymentMilestoneInput(2, "尾款 70%", 30, null, new BigDecimal("0.7")))),
                actor()));
  }

  /** 生成一个计划（只生成，不排批次）。 */
  FinancePaymentPlanScheduleResult generate(PaymentCondition condition, UUID termRuleId, UUID milestoneRuleId) {
    return as(
        () ->
            plans.generate(
                new FinancePaymentPlanGenerateInput(
                    condition, termRuleId, milestoneRuleId, supplier, null, null, "测试夹具"),
                actor()));
  }

  /** 按供应商与到期窗口批量安排付款。 */
  FinancePaymentPlanScheduleResult schedule(
      PaymentCondition condition,
      UUID termRuleId,
      UUID milestoneRuleId,
      LocalDate dueFrom,
      LocalDate dueTo,
      LocalDate windowStart,
      LocalDate windowEnd) {
    return as(
        () ->
            plans.schedule(
                new FinancePaymentPlanScheduleInput(
                    supplier,
                    dueFrom,
                    dueTo,
                    windowStart,
                    windowEnd,
                    condition,
                    termRuleId,
                    milestoneRuleId,
                    "测试夹具"),
                actor()));
  }

  // ==========================================================================
  // 三种付款条件
  // ==========================================================================

  @Test
  void 到期日条件取的是应付自己的到期日() {
    var dueDate = LocalDate.now(ZoneOffset.UTC).plusDays(20);
    var payable = createPayable("AP-DUE", "USD", "100.0000", dueDate);

    var result = generate(PaymentCondition.DUE_DATE, null, null);

    assertThat(result.planned()).isEqualTo(1);
    var plan = result.plans().get(0);
    assertThat(plan.paymentCondition()).isEqualTo(PaymentCondition.DUE_DATE);
    assertThat(plan.conditionDate()).as("到期日条件的条件日 = 应付的到期日").isEqualTo(dueDate);
    assertThat(plan.plannedPaidDate()).isEqualTo(dueDate);
    assertThat(plan.clamped()).as("没有被付款窗口压过").isFalse();
    assertThat(plan.payableId()).isEqualTo(payable.id());
    assertThat(plan.plannedAmount()).isEqualByComparingTo("100.0000");
  }

  @Test
  void 账期条件必须先有账期规则没有就排不出计划() {
    createPayable("AP-TERM", "USD", "100.0000", LocalDate.now(ZoneOffset.UTC).plusDays(20));

    // 没有账期规则 ⇒ 排不出计划，且**说清原因**（不是含糊的「参数错误」）。
    var blind = generate(PaymentCondition.TERM, null, null);
    assertThat(blind.planned()).as("没有账期规则就不该产生任何计划").isZero();
    assertThat(blind.warnings())
        .as("必须说清是「没有账期规则」")
        .anyMatch(w -> w.contains("没有可用的账期规则"));

    var rule = createTermRule(30, TermBaseKind.DUE_DATE);
    var result = generate(PaymentCondition.TERM, rule.id(), null);
    assertThat(result.planned()).isEqualTo(1);
    var plan = result.plans().get(0);
    assertThat(plan.paymentCondition()).isEqualTo(PaymentCondition.TERM);
    assertThat(plan.termRuleId()).isEqualTo(rule.id());
    // 起算基准是到期日 ⇒ 条件日 = 到期日 + 30 天。
    assertThat(plan.conditionDate())
        .isEqualTo(LocalDate.now(ZoneOffset.UTC).plusDays(20).plusDays(30));
  }

  @Test
  void 账期条件从确认日起算时条件日等于确认日加账期天数() {
    var payable = createPayable("AP-TERM-BASE", "USD", "50.0000", LocalDate.now(ZoneOffset.UTC).plusDays(45));
    var confirmedDate =
        one("select (confirmed_at at time zone 'UTC')::date as d from finance_payable where id = ?", payable.id())
            .get("d", LocalDate.class);

    var rule = createTermRule(15, TermBaseKind.CONFIRMED_DATE);
    var result = generate(PaymentCondition.TERM, rule.id(), null);

    assertThat(result.planned()).isEqualTo(1);
    assertThat(result.plans().get(0).conditionDate()).isEqualTo(confirmedDate.plusDays(15));
  }

  @Test
  void 里程碑条件把一张应付拆成多条计划并按比例拆金额() {
    createPayable("AP-MS", "USD", "100.0000", LocalDate.now(ZoneOffset.UTC).plusDays(20));
    var rule = createMilestoneRule();

    var result = generate(PaymentCondition.MILESTONE, null, rule.id());

    assertThat(result.planned()).as("一张应付拆成两条计划").isEqualTo(2);
    var amounts =
        result.plans().stream().map(FinancePaymentPlanRow::plannedAmount).sorted().toList();
    assertThat(amounts.get(0)).isEqualByComparingTo("30.0000");
    assertThat(amounts.get(1)).isEqualByComparingTo("70.0000");
    // 两条计划的里程碑不同。
    assertThat(result.plans().stream().map(FinancePaymentPlanRow::milestoneId).distinct().count())
        .isEqualTo(2);
    // 条件日 = 确认日 + 偏移天数。
    var confirmedDate =
        one("select (confirmed_at at time zone 'UTC')::date as d from finance_payable limit 1")
            .get("d", LocalDate.class);
    var sorted =
        result.plans().stream()
            .sorted(java.util.Comparator.comparing(FinancePaymentPlanRow::conditionDate))
            .toList();
    assertThat(sorted.get(0).conditionDate()).isEqualTo(confirmedDate);
    assertThat(sorted.get(1).conditionDate()).isEqualTo(confirmedDate.plusDays(30));
  }

  @Test
  void 里程碑比例之和不是一就整套规则都写不进去() {
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        plans.createMilestoneRule(
                            new FinancePaymentMilestoneRuleInput(
                                "比例不对",
                                supplier,
                                "USD",
                                true,
                                "测试夹具",
                                List.of(
                                    new FinancePaymentMilestoneInput(1, "首期", 0, null, new BigDecimal("0.3")),
                                    new FinancePaymentMilestoneInput(2, "尾款", 30, null, new BigDecimal("0.4")))),
                            actor())))
        .as("里程碑合起来必须正好覆盖这笔应付的全部未付金额")
        .hasStackTraceContaining("比例之和必须为 1");
  }

  // ==========================================================================
  // 批量安排付款
  // ==========================================================================

  @Test
  void 批量安排真的批量落库并按币种分批() {
    createPayable("AP-S1", "USD", "100.0000", LocalDate.now(ZoneOffset.UTC).plusDays(10));
    createPayable("AP-S2", "USD", "200.0000", LocalDate.now(ZoneOffset.UTC).plusDays(15));
    createPayable("AP-S3", "EUR", "50.0000", LocalDate.now(ZoneOffset.UTC).plusDays(12));

    var result =
        schedule(
            PaymentCondition.DUE_DATE,
            null,
            null,
            LocalDate.now(ZoneOffset.UTC),
            LocalDate.now(ZoneOffset.UTC).plusDays(30),
            LocalDate.now(ZoneOffset.UTC).plusDays(20),
            LocalDate.now(ZoneOffset.UTC).plusDays(31));

    assertThat(result.scanned()).isEqualTo(3);
    assertThat(result.planned()).isEqualTo(3);
    assertThat(result.batchCount()).as("按币种分批 ⇒ 两批").isEqualTo(2);
    assertThat(result.batches()).hasSize(2);

    // 批次的金额与笔数由 DB 触发器派生，不是服务层算的。
    var usdBatch = result.batches().stream().filter(b -> b.currency().equals("USD")).findFirst().orElseThrow();
    assertThat(usdBatch.plannedAmount()).isEqualByComparingTo("300.0000");
    assertThat(usdBatch.planCount()).isEqualTo(2);
    var eurBatch = result.batches().stream().filter(b -> b.currency().equals("EUR")).findFirst().orElseThrow();
    assertThat(eurBatch.plannedAmount()).isEqualByComparingTo("50.0000");
    assertThat(eurBatch.planCount()).isEqualTo(1);

    // 计划真的挂到了批次上，状态是「已安排」。
    assertThat(result.plans()).allMatch(p -> p.status() == PaymentPlanStatus.SCHEDULED);
    assertThat(result.plans()).allMatch(p -> p.batchId() != null);
  }

  @Test
  void 条件日落在付款窗口之外时被压进窗口并留下clamped标记() {
    var dueDate = LocalDate.now(ZoneOffset.UTC).plusDays(60);
    createPayable("AP-CLAMP", "USD", "100.0000", dueDate);

    var result =
        schedule(
            PaymentCondition.DUE_DATE,
            null,
            null,
            LocalDate.now(ZoneOffset.UTC),
            dueDate.plusDays(5),
            LocalDate.now(ZoneOffset.UTC).plusDays(10),
            LocalDate.now(ZoneOffset.UTC).plusDays(20));

    assertThat(result.planned()).isEqualTo(1);
    var plan = result.plans().get(0);
    assertThat(plan.conditionDate()).as("条件日仍按到期日算").isEqualTo(dueDate);
    assertThat(plan.plannedPaidDate()).as("计划日被压进付款窗口").isEqualTo(dueDate.minusDays(40));
    assertThat(plan.clamped()).as("clamped 是生成列 ⇒ 与两个日期不可能对不上").isTrue();
  }

  @Test
  void 同一张应付不会被排两次() {
    createPayable("AP-DUP", "USD", "100.0000", LocalDate.now(ZoneOffset.UTC).plusDays(10));
    var from = LocalDate.now(ZoneOffset.UTC);
    var to = from.plusDays(30);

    var first =
        schedule(PaymentCondition.DUE_DATE, null, null, from, to, from.plusDays(1), from.plusDays(5));
    assertThat(first.planned()).isEqualTo(1);

    var second =
        schedule(PaymentCondition.DUE_DATE, null, null, from, to, from.plusDays(1), from.plusDays(5));
    assertThat(second.planned()).as("同一张在册应付不能被排第二次").isZero();
    assertThat(second.rearranged()).as("第二次是改期，不是新建").isEqualTo(1);
    assertThat(count("select count(*) from finance_payment_plan")).isEqualTo(1);
  }

  @Test
  void 换条件必须先取消旧计划() {
    createPayable("AP-SWITCH", "USD", "100.0000", LocalDate.now(ZoneOffset.UTC).plusDays(10));
    var rule = createTermRule(30, TermBaseKind.DUE_DATE);
    var from = LocalDate.now(ZoneOffset.UTC);

    schedule(PaymentCondition.DUE_DATE, null, null, from, from.plusDays(30), from.plusDays(1), from.plusDays(5));
    var switched =
        schedule(PaymentCondition.TERM, rule.id(), null, from, from.plusDays(30), from.plusDays(1), from.plusDays(5));

    assertThat(switched.planned()).isZero();
    assertThat(switched.warnings()).anyMatch(w -> w.contains("换条件请先取消那条计划"));
  }

  @Test
  void 取消计划之后提醒当场失效() {
    createPayable("AP-CANCEL", "USD", "100.0000", LocalDate.now(ZoneOffset.UTC).plusDays(3));
    var result = generate(PaymentCondition.DUE_DATE, null, null);
    var planId = result.plans().get(0).id();

    as(() -> plans.scanReminders(LocalDate.now(ZoneOffset.UTC), 30, actor()));
    assertThat(count("select count(*) from finance_payment_reminder where status = 'PENDING'")).isEqualTo(1);

    as(() -> plans.cancel(planId, "供应商退款了", actor()));

    var reminder = one("select * from finance_payment_reminder where plan_id = ?", planId);
    assertThat(reminder.get("status", String.class)).isEqualTo("DISMISSED");
    assertThat(reminder.get("dismiss_reason", String.class)).isEqualTo("计划已取消");
  }

  // ==========================================================================
  // 到期提醒
  // ==========================================================================

  @Test
  void 到期判定是真的按计划付款日算出来的() {
    var today = LocalDate.now(ZoneOffset.UTC);
    createPayable("AP-REM", "USD", "100.0000", today);
    var result = generate(PaymentCondition.DUE_DATE, null, null);

    var scan = as(() -> plans.scanReminders(today, 30, actor()));
    assertThat(scan.created()).isEqualTo(1);

    var reminder = one("select * from finance_payment_reminder where plan_id = ?", result.plans().get(0).id());
    assertThat(reminder.get("reminder_type", String.class)).as("今天到期").isEqualTo("DUE");

    // 同一天再扫一次：什么都没变 ⇒ 不该更新。
    var again = as(() -> plans.scanReminders(today, 30, actor()));
    assertThat(again.updated()).as("计划没变就不该更新").isZero();
    assertThat(again.unchanged()).isEqualTo(1);

    // 明天再扫：同一张计划**已经逾期** ⇒ 判定真的变了，提醒跟着改并重置为「待处理」。
    var later = as(() -> plans.scanReminders(today.plusDays(1), 30, actor()));
    assertThat(later.updated()).isEqualTo(1);
    var overdue = one("select * from finance_payment_reminder where plan_id = ?", result.plans().get(0).id());
    assertThat(overdue.get("reminder_type", String.class)).isEqualTo("OVERDUE");
    assertThat(overdue.get("status", String.class)).isEqualTo("PENDING");
  }

  @Test
  void 已确认的提醒不会被下一次扫描重置() {
    var today = LocalDate.now(ZoneOffset.UTC);
    createPayable("AP-ACK", "USD", "100.0000", today.plusDays(5));
    var result = generate(PaymentCondition.DUE_DATE, null, null);
    var planId = result.plans().get(0).id();

    as(() -> plans.scanReminders(today, 30, actor()));
    var reminderId = one("select id from finance_payment_reminder where plan_id = ?", planId).get(0, UUID.class);
    as(() -> plans.acknowledgeReminder(reminderId, "已知悉", actor()));

    var scan = as(() -> plans.scanReminders(today, 30, actor()));
    assertThat(scan.unchanged()).as("已确认的提醒不被重置").isEqualTo(1);
    var reminder = one("select * from finance_payment_reminder where id = ?", reminderId);
    assertThat(reminder.get("status", String.class)).isEqualTo("ACKNOWLEDGED");
    assertThat(reminder.get("acknowledged_by", UUID.class)).isEqualTo(actor().userId());
    assertThat(reminder.get("acknowledged_at", OffsetDateTime.class)).isNotNull();
  }

  @Test
  void 人手动忽略的提醒不会被扫回来() {
    var today = LocalDate.now(ZoneOffset.UTC);
    createPayable("AP-DISMISS", "USD", "100.0000", today.plusDays(5));
    var result = generate(PaymentCondition.DUE_DATE, null, null);
    var planId = result.plans().get(0).id();

    as(() -> plans.scanReminders(today, 30, actor()));
    var reminderId = one("select id from finance_payment_reminder where plan_id = ?", planId).get(0, UUID.class);
    as(() -> plans.dismissReminder(reminderId, "这单走预付款", actor()));

    var scan = as(() -> plans.scanReminders(today, 30, actor()));
    assertThat(scan.unchanged()).isEqualTo(1);
    var reminder = one("select * from finance_payment_reminder where id = ?", reminderId);
    assertThat(reminder.get("status", String.class)).isEqualTo("DISMISSED");
    assertThat(reminder.get("dismiss_reason", String.class)).isEqualTo("这单走预付款");
  }

  @Test
  void 计划改了提醒跟着改并重置为待处理() {
    var today = LocalDate.now(ZoneOffset.UTC);
    createPayable("AP-RESCHED", "USD", "100.0000", today.plusDays(5));
    var result = generate(PaymentCondition.DUE_DATE, null, null);
    var planId = result.plans().get(0).id();

    as(() -> plans.scanReminders(today, 30, actor()));
    var reminderId = one("select id from finance_payment_reminder where plan_id = ?", planId).get(0, UUID.class);
    as(() -> plans.acknowledgeReminder(reminderId, "先确认", actor()));

    // 重新排期 ⇒ 计划付款日变了 ⇒ 提醒要跟着改，并重置为「待处理」。
    var rescheduled =
        schedule(
            PaymentCondition.DUE_DATE,
            null,
            null,
            today,
            today.plusDays(30),
            today.plusDays(20),
            today.plusDays(25));
    assertThat(rescheduled.rearranged()).isEqualTo(1);

    var scan = as(() -> plans.scanReminders(today, 30, actor()));
    assertThat(scan.updated()).isEqualTo(1);
    var reminder = one("select * from finance_payment_reminder where id = ?", reminderId);
    assertThat(reminder.get("status", String.class)).isEqualTo("PENDING");
    assertThat(reminder.get("remind_date", LocalDate.class)).isEqualTo(today.plusDays(20));
    assertThat(reminder.get("acknowledged_at", OffsetDateTime.class)).isNull();
  }

  @Test
  void 提醒没有任何推送痕迹() {
    var today = LocalDate.now(ZoneOffset.UTC);
    createPayable("AP-NOPUSH", "USD", "100.0000", today);
    generate(PaymentCondition.DUE_DATE, null, null);
    as(() -> plans.scanReminders(today, 30, actor()));

    var reminder = one("select * from finance_payment_reminder limit 1");
    assertThat(reminder.get("push_status", String.class)).as("推送通道属 1.3（未交付）").isEqualTo("NOT_PUSHED");
    assertThat(reminder.get("push_owner_module", String.class)).isEqualTo("1.3");
    // 表里**根本没有** sent_at 之类的列 —— 假发送无处可藏。
    var columns =
        as(
            () ->
                sql.fetch(
                        "select column_name from information_schema.columns where table_name = 'finance_payment_reminder'")
                    .map(r -> r.get(0, String.class)));
    assertThat(columns).doesNotContain("sent_at", "pushed_at", "notified_at", "delivered_at");
  }

  // ==========================================================================
  // 资金需求预测（与计划联动）
  // ==========================================================================

  /** 种一张应收单（12.1.1.1 已交付的表；本模块**只读**它）。 */
  void seedReceivable(String no, String currency, String amount, LocalDate dueDate) {
    as(
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          var id = UUID.randomUUID();
          sql.execute(
              "insert into finance_receivable(id,tenant_id,receivable_no,source,source_system,"
                  + " counterparty_type,counterparty_name,currency,due_date,confirmed_at,status,"
                  + " dispute_flag,dispute_note,created_at,updated_at)"
                  + " values(?,?,?,'MANUAL','manual','CUSTOMER','客户甲',?,?::date,?::timestamptz,'DRAFT',"
                  + " false,'',?::timestamptz,?::timestamptz)",
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
                  + " amount,settled_amount,created_at,updated_at)"
                  + " values(?,?,?,1,'测试明细',?,0,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              id,
              new BigDecimal(amount),
              now,
              now);
          sql.execute("update finance_receivable set status='OPEN' where id = ?", id);
          return null;
        });
  }

  @Test
  void 预测按币种与到期窗口聚合且计划改了跟着变() {
    var today = LocalDate.now(ZoneOffset.UTC);
    createPayable("AP-FC", "USD", "100.0000", today.plusDays(3));
    seedReceivable("AR-FC", "USD", "40.0000", today.plusDays(3));
    generate(PaymentCondition.DUE_DATE, null, null);

    var before = as(() -> plans.forecast(today, today.plusDays(60), 7));
    var usdBefore =
        before.totalsByCurrency().stream().filter(t -> t.currency().equals("USD")).findFirst().orElseThrow();
    assertThat(usdBefore.outflowAmount()).isEqualByComparingTo("100.0000");
    assertThat(usdBefore.inflowAmount()).isEqualByComparingTo("40.0000");
    assertThat(usdBefore.netAmount()).as("净额 = 流入 − 流出").isEqualByComparingTo("-60.0000");
    assertThat(usdBefore.gapWindowCount()).as("净流出 ⇒ 现金流断裂风险").isGreaterThan(0);

    // 取消计划 ⇒ 预测当场跟着变（**不是两份互不相干的静态数字**）。
    var planId = one("select id from finance_payment_plan limit 1").get(0, UUID.class);
    as(() -> plans.cancel(planId, "改走预付款", actor()));

    var after = as(() -> plans.forecast(today, today.plusDays(60), 7));
    var usdAfter =
        after.totalsByCurrency().stream().filter(t -> t.currency().equals("USD")).findFirst().orElseThrow();
    assertThat(usdAfter.outflowAmount()).as("计划改了，预测当场跟着变").isEqualByComparingTo("0.0000");
    assertThat(usdAfter.netAmount()).isEqualByComparingTo("40.0000");
    assertThat(usdAfter.gapWindowCount()).isZero();
  }

  @Test
  void 预测不跨币种相加() {
    var today = LocalDate.now(ZoneOffset.UTC);
    createPayable("AP-FC-USD", "USD", "100.0000", today.plusDays(3));
    createPayable("AP-FC-EUR", "EUR", "80.0000", today.plusDays(3));
    generate(PaymentCondition.DUE_DATE, null, null);

    var forecast = as(() -> plans.forecast(today, today.plusDays(30), 7));
    assertThat(forecast.totalsByCurrency()).hasSize(2);
    var usd = forecast.totalsByCurrency().stream().filter(t -> t.currency().equals("USD")).findFirst().orElseThrow();
    var eur = forecast.totalsByCurrency().stream().filter(t -> t.currency().equals("EUR")).findFirst().orElseThrow();
    assertThat(usd.outflowAmount()).isEqualByComparingTo("100.0000");
    assertThat(eur.outflowAmount()).isEqualByComparingTo("80.0000");
    // 每个窗口行也必须按币种一行。
    assertThat(forecast.windows()).allMatch(w -> w.currency().equals("USD") || w.currency().equals("EUR"));
  }

  // ==========================================================================
  // 下游缺口：付款执行 / 提醒推送都不可用
  // ==========================================================================

  @Test
  void 数据层就不存在已付款这个状态() {
    var today = LocalDate.now(ZoneOffset.UTC);
    createPayable("AP-NOPAY", "USD", "100.0000", today.plusDays(3));
    var result = generate(PaymentCondition.DUE_DATE, null, null);
    var planId = result.plans().get(0).id();

    var plan = one("select * from finance_payment_plan where id = ?", planId);
    assertThat(plan.get("execution_available", Boolean.class)).as("12.2.2.1 未交付").isFalse();
    assertThat(plan.get("execution_owner_module", String.class)).isEqualTo("12.2.2.1");
    assertThat(plan.get("status", String.class)).as("绝不写「已付款」").isEqualTo("PLANNED");

    // 表级 CHECK 兜底：把执行可用打开必须被 DB 拒掉。
    assertThat(dbError("update finance_payment_plan set execution_available = true where id = ?", planId))
        .contains("execution_available");
    assertThat(
            dbError(
                "insert into finance_payment_batch(id,tenant_id,batch_no,counterparty_type,due_window_start,"
                    + " due_window_end,payment_window_start,payment_window_end,currency,execution_available)"
                    + " values(?,?,'PB-X','SUPPLIER',?::date,?::date,?::date,?::date,'USD',true)",
                UUID.randomUUID(),
                tenant,
                today,
                today,
                today,
                today))
        .contains("execution_available");

    // 表里也**没有**「已付款」「付款单号」这类列。
    var columns =
        as(
            () ->
                sql.fetch(
                        "select column_name from information_schema.columns where table_name = 'finance_payment_plan'")
                    .map(r -> r.get(0, String.class)));
    assertThat(columns).doesNotContain("paid_at", "executed_at", "payment_no", "paid_amount");
  }

  @Test
  void 能力声明如实报出下游缺口() {
    var caps = as(() -> plans.capabilities());
    assertThat(caps.capabilities()).anyMatch(c -> c.module().equals("12.2.2.1") && !c.available());
    assertThat(caps.capabilities()).anyMatch(c -> c.module().equals("1.3") && !c.available());
    assertThat(caps.downstreamGaps()).anyMatch(g -> g.module().equals("12.2.2.1"));
    assertThat(caps.downstreamGaps()).anyMatch(g -> g.module().equals("1.3"));
    // 本模块自己交付的那几格必须是 available=true。
    assertThat(caps.capabilities())
        .filteredOn(c -> c.module().equals("12.1.2.2"))
        .allMatch(c -> c.available());
  }

  // ==========================================================================
  // 金额与时间口径
  // ==========================================================================

  @Test
  void 金额是四位小数而不是浮点() {
    createPayable("AP-DEC", "USD", "12.3456", LocalDate.now(ZoneOffset.UTC).plusDays(5));
    var result = generate(PaymentCondition.DUE_DATE, null, null);
    assertThat(result.plans().get(0).plannedAmount()).isEqualByComparingTo("12.3456");
    assertThat(result.plans().get(0).plannedAmount().scale()).isEqualTo(4);
  }

  @Test
  void 时间字段写回后读回来必须是同一个时刻() {
    createPayable("AP-TS", "USD", "10.0000", LocalDate.now(ZoneOffset.UTC).plusDays(5));
    var result = generate(PaymentCondition.DUE_DATE, null, null);
    var planId = result.plans().get(0).id();

    var row = one("select * from finance_payment_plan where id = ?", planId);
    var createdAt = row.get("created_at", OffsetDateTime.class);
    var updatedAt = row.get("updated_at", OffsetDateTime.class);
    assertThat(createdAt).as("timestamptz 必须真的写进去了").isNotNull();
    assertThat(updatedAt).isNotNull();
    assertThat(createdAt.toInstant()).isEqualTo(updatedAt.toInstant());
    // 夹具的落库时刻就在「现在」附近（不是 1970，也不是未来）。
    assertThat(createdAt.toInstant())
        .isAfter(java.time.Instant.now().minusSeconds(600))
        .isBefore(java.time.Instant.now().plusSeconds(600));
  }

  // ==========================================================================
  // HTTP 层：状态码
  // ==========================================================================

  @Test
  void 按供应商筛选时汇总也跟着筛() {
    createPayable("AP-F1", "USD", "100.0000", LocalDate.now(ZoneOffset.UTC).plusDays(5));
    generate(PaymentCondition.DUE_DATE, null, null);

    var page =
        as(
            () ->
                plans.page(
                    new PaymentPlanQuery(
                        null, null, null, supplier, null, null, null, null, null, 0, 50)));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.summaryByCurrency()).hasSize(1);
    assertThat(page.summaryByCurrency().get(0).plannedAmount()).isEqualByComparingTo("100.0000");

    // 换一个（不存在的）供应商 ⇒ 列表与汇总都必须是空的 —— 汇总覆盖的是**整个筛选结果集**。
    var empty =
        as(
            () ->
                plans.page(
                    new PaymentPlanQuery(
                        null,
                        null,
                        null,
                        UUID.randomUUID(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        50)));
    assertThat(empty.total()).isZero();
    assertThat(empty.summaryByCurrency()).isEmpty();
  }

  @Test
  void http层端点可用且未交付的形状不存在() throws Exception {
    http.perform(get("/finance/payment-plans/capabilities").header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(get("/finance/payment-plans/options").header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(get("/finance/payment-plans").header("Authorization", "session")).andExpect(status().isOk());
    http.perform(get("/finance/payment-plans/forecast").header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(get("/finance/payment-plans/reminders").header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(get("/finance/payment-plans/batches").header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(get("/finance/payment-plans/term-rules").header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(get("/finance/payment-plans/milestone-rules").header("Authorization", "session"))
        .andExpect(status().isOk());

    // 未认证 ⇒ 401。
    http.perform(get("/finance/payment-plans")).andExpect(status().isUnauthorized());
  }

  @Test
  void 没有身份会话时端点一律拒绝() throws Exception {
    http.perform(get("/finance/payment-plans/forecast").header("Authorization", "nope"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void 生成与安排走HTTP也是真的落库() throws Exception {
    var today = LocalDate.now(ZoneOffset.UTC);
    createPayable("AP-HTTP", "USD", "60.0000", today.plusDays(4));

    http.perform(
            post("/finance/payment-plans/schedule")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new FinancePaymentPlanScheduleInput(
                            supplier,
                            today,
                            today.plusDays(30),
                            today.plusDays(1),
                            today.plusDays(10),
                            PaymentCondition.DUE_DATE,
                            null,
                            null,
                            "HTTP 夹具"))))
        .andExpect(status().isOk());

    assertThat(count("select count(*) from finance_payment_plan")).isEqualTo(1);
    assertThat(count("select count(*) from finance_payment_batch")).isEqualTo(1);
  }
}
