package com.starcon.trade;

import static com.starcon.trade.ServiceTicketModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.trade.AftersalesCaseModels.AftersalesCaseCreate;
import com.starcon.trade.ServiceTicketModels.*;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 11.4.2.1 工单管理的集成测试（Testcontainers PostgreSQL 16 实跑）。
 *
 * <p>本模块的每一条断言都对着蓝图职责句里的一个承诺：
 * 咨询 / 投诉 / 催单 / 平台政策咨询四类工单、分配、SLA 计时、转派、关闭、满意度评价，
 * 外加那条加粗句「<b>工单与售后单要能互转：咨询升级为投诉、投诉升级为纠纷，
 * 需要保留上下文</b>」所要求的**两条真实通道**（不是两个标签）。
 *
 * <p>⚠️ <b>口径类断言比数字类断言重要</b>：一个把「没配处理时限」当成「处理没达标」
 * 算进分母的实现，报出来的达成率仍然是个漂亮的小数，
 * 只有断言「没配时限的工单不进分母」才拦得住。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ServiceTicketIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> perms =
      List.of("trade.aftersales.read", "trade.aftersales.write");

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
      }
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    actors.put(
        "Bearer tenant-a",
        new AccessService.Access(
            user, tenant, "Test", UUID.randomUUID(), null, perms,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", perms))));
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:8081");
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @MockitoSpyBean AccessService access;
  @Autowired ServiceTicketService tickets;
  @Autowired AftersalesCaseService cases;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    pg.stop();
  }

  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");
  static final BigDecimal AMOUNT = new BigDecimal("88.5000");
  static final BigDecimal QTY = new BigDecimal("2.000000");
  /** 单价 × 数量 = 行金额（DB 守卫 sales_order_line_amount_matches 钉死这一条）。 */
  static final BigDecimal LINE_UNIT = new BigDecimal("44.2500");

  @BeforeEach
  void stubIdentity() {
    org.mockito.Mockito
        .doAnswer(
            inv -> {
              AccessService.Access found = actors.get(inv.getArgument(0, String.class));
              if (found == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
              }
              return found;
            })
        .when(access)
        .require(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
  }

  @BeforeEach
  void clean() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate aftersales_ticket_sla_snapshot, aftersales_ticket_satisfaction,"
              + " aftersales_ticket_event, aftersales_ticket_assignment,"
              + " aftersales_service_ticket, aftersales_owner_roster, aftersales_reply_sla,"
              + " aftersales_case_note, aftersales_case_line, aftersales_authorization,"
              + " aftersales_owner_assignment, aftersales_case,"
              + " order_fulfillment_line_progress, sales_order cascade");
    }
  }

  <T> T as(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-a"), "trade.aftersales.write", work);
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /** 建一张订单（平台来源 AMAZON）。 */
  UUID seedOrder(String tag) {
    return seedOrder(tag, "AMAZON");
  }

  /** 建一张订单（指定平台来源 —— SLA 策略档是按平台档解析的）。 */
  UUID seedOrder(String tag, String source) {
    UUID orderId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    as(
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?::uuid,?::uuid,?,?,?,'USD','PREPAID','SUBMITTED',?::timestamptz,"
                  + "?::numeric,0,0,0,0,?::numeric,0,?::timestamptz,?::timestamptz)",
              orderId, tenant, "ORD-" + tag, source, "EXT-" + tag, T1, AMOUNT, AMOUNT, T1, T1);
          // ⚠️ 订单必须**有商品行**才能提交（DB 守卫 guard_sales_order_submitted）：
          //   一张没有商品行的订单在审单队列里无事可审。
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "spec_snapshot,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
                  + "created_at) values(?::uuid,?::uuid,?::uuid,1,?::uuid,?,?,'黑色 / L',?::numeric,"
                  + "?::numeric,0,0,?::numeric,'',?::timestamptz)",
              lineId, tenant, orderId, UUID.randomUUID(), "SKU-" + tag, "商品" + tag, QTY, LINE_UNIT,
              AMOUNT, T1);
          // ⚠️ 订单还必须有**收货地址快照**与**买家主体快照**（DB 守卫 guard_sales_order_submitted）：
          //   收货地址是下单当时冻结的那一份，没有它就无法发货。
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
                  + "recipient_phone,recipient_email,company_name,country_code,province,city,"
                  + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
                  + " values(?::uuid,?::uuid,?::uuid,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
                  + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, "买家-" + tag, "buyer-" + tag + "@example.com", T1);
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,?,'INDIVIDUAL','',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), "C-" + tag, "买家-" + tag, T1);
          return null;
        });
    return orderId;
  }

  /** 建一张售后单（单次建单，不重复建）。 */
  UUID newCase(String tag) {
    return newCase(tag, "AMAZON");
  }

  /** 建一张售后单（指定订单的平台来源）。 */
  UUID newCase(String tag, String source) {
    UUID orderId = seedOrder(tag, source);
    return as(
        () ->
            cases.create(
                    new AftersalesCaseCreate(
                        orderId, "RETURN_REFUND", "QUALITY_DEFECT", "外箱压损",
                        AMOUNT, "USD", QTY, "PLAT-" + tag, null, null,
                        "买家诉求-" + tag, T1, null, T1,
                        null, null, null, "TENANT_MAINTAINED", null, T1),
                    user)
                .id());
  }

  /** 配一档租户 SLA 策略（platform_scope='' = 全平台兜底档）。 */
  void seedSla(String scope, int replyHours, Integer resolveHours) {
    as(
        () -> {
          sql.execute(
              "insert into aftersales_reply_sla(id,tenant_id,platform_scope,reply_sla_hours,"
                  + "resolve_sla_hours,effective_from,effective_to,created_by,created_at)"
                  + " values(?::uuid,?::uuid,?,?,?,?::timestamptz,null,?::uuid,?::timestamptz)",
              UUID.randomUUID(), tenant, scope, replyHours, resolveHours, T1, user, T1);
          return null;
        });
  }

  /** 往责任人名册里加一行。 */
  void seedRoster(String ownerRef, int weight) {
    as(
        () -> {
          sql.execute(
              "insert into aftersales_owner_roster(id,tenant_id,owner_ref,owner_display_name,"
                  + "owner_role_label,owner_source,source_reference,accepting_new_cases,weight,"
                  + "created_at,updated_at)"
                  + " values(?::uuid,?::uuid,?,?,'客服'," + "'TENANT_MAINTAINED','',true,?,"
                  + "?::timestamptz,?::timestamptz)",
              UUID.randomUUID(), tenant, ownerRef, ownerRef, weight, T1, T1);
          return null;
        });
  }

  /** 建一张工单（默认咨询 / 人工创建）。 */
  ServiceTicketDetail newTicket(UUID caseId, String tag) {
    return as(
        () ->
            tickets.createTicket(
                new ServiceTicketCreate(
                    caseId.toString(), "INQUIRY", "MANUAL", "SRC-" + tag, "主题-" + tag,
                    "NORMAL", "诉求-" + tag, null, null, null, null),
                user));
  }

  // ==========================================================================
  // 建单
  // ==========================================================================

  @Test
  void 建单挂在售后单上且四类类型都落库() {
    seedSla("", 24, 48);
    UUID caseId = newCase("T1");

    ServiceTicketDetail d = newTicket(caseId, "A");
    assertThat(d.head().ticketNo()).startsWith("TK");
    assertThat(d.head().ticketStatus()).isEqualTo("OPEN");
    assertThat(d.head().ticketType()).isEqualTo("INQUIRY");
    assertThat(d.head().ticketTypeLabel()).isEqualTo("咨询");
    assertThat(d.head().sourceChannel()).isEqualTo("MANUAL");
    assertThat(d.head().sourceRef()).isEqualTo("SRC-A");
    assertThat(d.head().caseId()).isEqualTo(caseId);
    assertThat(d.head().caseNo()).isNotBlank();
    // SLA 档命中 ⇒ 口径来源是「租户 SLA 策略」。
    assertThat(d.head().slaBasis()).isEqualTo("POLICY_SLA");
    assertThat(d.head().slaReplyHours()).isEqualTo(24);
    assertThat(d.head().slaResolveHours()).isEqualTo(48);
    assertThat(d.head().replyDueAt()).isNotNull();
    assertThat(d.head().resolveDueAt()).isNotNull();
    // 事件留痕：建单这一件事必须在。
    assertThat(d.events()).extracting(ServiceTicketEventRow::eventType).contains("CREATED");
  }

  @Test
  void 四种工单类型逐个建出来() {
    seedSla("", 24, null);
    for (String type :
        List.of("INQUIRY", "COMPLAINT", "ORDER_CHASE", "POLICY_CONSULT")) {
      UUID caseId = newCase("T-" + type);
      ServiceTicketDetail d =
          as(
              () ->
                  tickets.createTicket(
                      new ServiceTicketCreate(
                          caseId.toString(), type, "MANUAL", null, "主题", "NORMAL", "诉求",
                          null, null, null, null),
                      user));
      assertThat(d.head().ticketType()).isEqualTo(type);
      assertThat(d.head().ticketTypeLabel()).isNotBlank();
    }
  }

  @Test
  void 一张售后单只转一次工单且重复转报出已有工单号() {
    seedSla("", 24, null);
    UUID caseId = newCase("DUP");
    ServiceTicketDetail first = newTicket(caseId, "DUP");

    assertThatThrownBy(() -> newTicket(caseId, "DUP2"))
        .hasStackTraceContaining("已转过工单")
        .hasStackTraceContaining(first.head().ticketNo());
  }

  @Test
  void 没配SLA档时明确报错而不是编一个默认小时数() {
    UUID caseId = newCase("NOSLA");
    assertThatThrownBy(() -> newTicket(caseId, "NOSLA"))
        .hasStackTraceContaining("没有配置租户回复时限策略")
        .hasStackTraceContaining("不会替租户编一个默认小时数");
  }

  @Test
  void 显式给回复时限时口径是平台给定且写回的是同一绝对时刻() {
    UUID caseId = newCase("GIVEN");
    OffsetDateTime given = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");

    ServiceTicketDetail d =
        as(
            () ->
                tickets.createTicket(
                    new ServiceTicketCreate(
                        caseId.toString(), "INQUIRY", "MANUAL", null, "主题", "NORMAL", "诉求",
                        null, given, null, null),
                    user));

    assertThat(d.head().slaBasis()).isEqualTo("PLATFORM_GIVEN");
    assertThat(d.head().slaPolicyId()).isNull();
    // ⚠️ 断言**写回的值**（不是「没抛异常」）：+08:00 的时刻必须还是同一个绝对时刻。
    assertThat(d.head().replyDueAt().toInstant()).isEqualTo(given.toInstant());
    OffsetDateTime written =
        as(
            () ->
                sql.fetchOne(
                        "select reply_due_at from aftersales_service_ticket"
                            + " where tenant_id = ?::uuid and id = ?::uuid",
                        tenant, d.head().id())
                    .get("reply_due_at", OffsetDateTime.class));
    assertThat(written.toInstant()).isEqualTo(given.toInstant());
  }

  @Test
  void SLA档按平台精确档优先于兜底档() {
    seedSla("", 24, null);
    seedSla("AMAZON", 4, null);
    UUID caseId = newCase("SCOPE");

    ServiceTicketDetail d = newTicket(caseId, "SCOPE");
    assertThat(d.head().slaReplyHours()).isEqualTo(4);
  }

  // ==========================================================================
  // 分配 / 转派
  // ==========================================================================

  @Test
  void 派单责任人必须在名册里() {
    seedSla("", 24, null);
    UUID caseId = newCase("ROSTER");
    ServiceTicketDetail t = newTicket(caseId, "R");

    assertThatThrownBy(
            () ->
                as(
                    () ->
                        tickets.assignTicket(
                            t.head().id(),
                            new ServiceTicketAssign("不在名册的人", null, "", 0L),
                            user)))
        .hasStackTraceContaining("不在名册里");
  }

  @Test
  void 名册加权轮转派单权重高的先拿到单() {
    seedSla("", 24, null);
    seedRoster("权重一", 1);
    seedRoster("权重十", 10);

    UUID caseA = newCase("W1");
    ServiceTicketDetail t1 = newTicket(caseA, "W1");
    // 先把一张单压给「权重一」：它的负载 = 1/1 = 1，而「权重十」是 0/10 = 0。
    as(
        () ->
            tickets.assignTicket(
                t1.head().id(), new ServiceTicketAssign("权重一", null, "先压一张", 0L), user));

    UUID caseB = newCase("W2");
    ServiceTicketDetail t2 = newTicket(caseB, "W2");
    ServiceTicketDetail assigned =
        as(
            () ->
                tickets.autoAssignTicket(
                    t2.head().id(), new ServiceTicketAutoAssign("轮转", 0L), user));

    // ⚠️ 断言**动作返回的那一份**，不是动作之前的旧快照。
    assertThat(assigned.head().assignedToRef()).isEqualTo("权重十");
    // 转派历史一条在（append-only）。
    assertThat(assigned.assignments()).hasSize(1);
    assertThat(assigned.assignments().get(0).assignMode()).isEqualTo("ROSTER_ROUND_ROBIN");
  }

  @Test
  void 名册为空时报没人可派而不是编一个默认客服() {
    seedSla("", 24, null);
    UUID caseId = newCase("EMPTY");
    ServiceTicketDetail t = newTicket(caseId, "E");

    assertThatThrownBy(
            () ->
                as(
                    () ->
                        tickets.autoAssignTicket(
                            t.head().id(), new ServiceTicketAutoAssign("", 0L), user)))
        .hasStackTraceContaining("没有可接新单的人")
        .hasStackTraceContaining("不替租户编一个「默认客服」");
  }

  @Test
  void 转派必须已有责任人且必须写理由() {
    seedSla("", 24, null);
    seedRoster("甲", 1);
    seedRoster("乙", 1);
    UUID caseId = newCase("T");
    ServiceTicketDetail t = newTicket(caseId, "T");

    // 没有责任人 ⇒ 没有「转」的对象。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        tickets.transferTicket(
                            t.head().id(),
                            new ServiceTicketTransfer("乙", null, "换个同事", 0L),
                            user)))
        .hasStackTraceContaining("没有「转」的对象");

    as(
        () ->
            tickets.assignTicket(
                t.head().id(), new ServiceTicketAssign("甲", null, "", 0L), user));
    // 理由必填。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        tickets.transferTicket(
                            t.head().id(),
                            new ServiceTicketTransfer("乙", null, "  ", 1L),
                            user)))
        .hasStackTraceContaining("必须给出转派理由");

    ServiceTicketDetail moved =
        as(
            () ->
                tickets.transferTicket(
                    t.head().id(),
                    new ServiceTicketTransfer("乙", null, "甲休假", 1L),
                    user));
    assertThat(moved.head().assignedToRef()).isEqualTo("乙");
    // 转派历史：首派 + 转派两条，**追加式**。
    assertThat(moved.assignments()).hasSize(2);
    assertThat(moved.assignments()).extracting(ServiceTicketAssignmentRow::assignAction)
        .containsExactlyInAnyOrder("ASSIGN", "TRANSFER");
  }

  @Test
  void 认领只能认领当前无责任人的工单且释放要把单放回待处理池() {
    seedSla("", 24, null);
    seedRoster("甲", 1);
    UUID caseId = newCase("CL");
    ServiceTicketDetail t = newTicket(caseId, "CL");

    ServiceTicketDetail claimed =
        as(
            () ->
                tickets.claimTicket(
                    t.head().id(), new ServiceTicketClaim("甲", null, "我来跟", 0L), user));
    assertThat(claimed.head().assignedToRef()).isEqualTo("甲");

    // 已有责任人 ⇒ 不能认领。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        tickets.claimTicket(
                            t.head().id(), new ServiceTicketClaim("甲", null, "我也来", 1L), user)))
        .hasStackTraceContaining("不能认领");

    ServiceTicketDetail released =
        as(
            () ->
                tickets.releaseTicket(
                    t.head().id(), new ServiceTicketRelease("我要下班了", 1L), user));
    assertThat(released.head().assignedToRef()).isEmpty();
    assertThat(released.head().assignedAt()).isNull();
    assertThat(released.assignments()).extracting(ServiceTicketAssignmentRow::assignAction)
        .contains("RELEASE");
  }

  // ==========================================================================
  // 处理 / 首次回复 / 状态流转
  // ==========================================================================

  @Test
  void 首次回复只能记一次() {
    seedSla("", 24, null);
    UUID caseId = newCase("FR");
    ServiceTicketDetail t = newTicket(caseId, "FR");

    ServiceTicketDetail replied =
        as(
            () ->
                tickets.recordFirstReply(
                    t.head().id(), new ServiceTicketFirstReply("已答复", 0L), user));
    assertThat(replied.head().firstReplyAt()).isNotNull();
    // 记录之后 SLA 状态从「计时中」变成「已达标」。
    assertThat(replied.head().replySlaState()).isEqualTo("MET");

    assertThatThrownBy(
            () ->
                as(
                    () ->
                        tickets.recordFirstReply(
                            t.head().id(), new ServiceTicketFirstReply("再记一次", 1L), user)))
        .hasStackTraceContaining("已经记录过首次回复");
  }

  @Test
  void 状态流转按状态机走且终态不可再流转() {
    seedSla("", 24, null);
    UUID caseId = newCase("ST");
    ServiceTicketDetail t = newTicket(caseId, "ST");

    ServiceTicketDetail inProgress =
        as(
            () ->
                tickets.changeStatus(
                    t.head().id(), new ServiceTicketStatusChange("IN_PROGRESS", "开始跟", 0L), user));
    assertThat(inProgress.head().ticketStatus()).isEqualTo("IN_PROGRESS");

    ServiceTicketDetail waiting =
        as(
            () ->
                tickets.changeStatus(
                    t.head().id(), new ServiceTicketStatusChange("WAITING_BUYER", "等买家", 1L), user));
    assertThat(waiting.head().ticketStatus()).isEqualTo("WAITING_BUYER");
    assertThat(waiting.head().waitingBuyerAt()).isNotNull();

    // 待处理不能直接跳到「已关闭」—— 关闭走 close 端点（必须带原因）。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        tickets.changeStatus(
                            t.head().id(), new ServiceTicketStatusChange("RESOLVED", "", 2L), user)))
        .hasStackTraceContaining("不能流转到");

    ServiceTicketDetail closed =
        as(
            () ->
                tickets.closeTicket(
                    t.head().id(), new ServiceTicketClose("RESOLVED", "已解决", 2L), user));
    assertThat(closed.head().ticketStatus()).isEqualTo("RESOLVED");
    assertThat(closed.head().closedAt()).isNotNull();

    // 终态不可再流转。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        tickets.changeStatus(
                            t.head().id(),
                            new ServiceTicketStatusChange("IN_PROGRESS", "", 3L),
                            user)))
        .hasStackTraceContaining("终态不可再流转");
  }

  // ==========================================================================
  // 关闭 / 满意度
  // ==========================================================================

  @Test
  void 关闭必须带原因且关闭与办完分得开() {
    seedSla("", 24, null);
    UUID c1 = newCase("CL1");
    ServiceTicketDetail t1 = newTicket(c1, "CL1");
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        tickets.closeTicket(
                            t1.head().id(), new ServiceTicketClose("", "", 0L), user)))
        .hasStackTraceContaining("必须给出关闭原因");

    ServiceTicketDetail done =
        as(
            () ->
                tickets.closeTicket(
                    t1.head().id(), new ServiceTicketClose("RESOLVED", "已解决", 0L), user));
    assertThat(done.head().ticketStatus()).isEqualTo("RESOLVED");
    assertThat(done.head().closeReasonLabel()).isEqualTo("已解决");

    UUID c2 = newCase("CL2");
    ServiceTicketDetail t2 = newTicket(c2, "CL2");
    ServiceTicketDetail silent =
        as(
            () ->
                tickets.closeTicket(
                    t2.head().id(), new ServiceTicketClose("NO_RESPONSE", "买家没回", 0L), user));
    // 「买家未回应」也是关闭，但**不是**办完 ⇒ 落 CANCELLED 而不是 RESOLVED。
    assertThat(silent.head().ticketStatus()).isEqualTo("CANCELLED");
    assertThat(silent.head().closeReason()).isEqualTo("NO_RESPONSE");
  }

  @Test
  void 关闭那一刻把SLA达成冻结成快照() {
    seedSla("", 24, 48);
    UUID caseId = newCase("SNAP");
    ServiceTicketDetail t = newTicket(caseId, "SNAP");

    // 及时回复 ⇒ 回复达标；未超处理时限 ⇒ 处理达标。
    as(
        () ->
            tickets.recordFirstReply(
                t.head().id(), new ServiceTicketFirstReply("已答复", 0L), user));
    ServiceTicketDetail closed =
        as(
            () ->
                tickets.closeTicket(
                    t.head().id(), new ServiceTicketClose("RESOLVED", "已解决", 1L), user));

    assertThat(closed.slaSnapshots()).hasSize(1);
    assertThat(closed.slaSnapshots().get(0).replyMet()).isTrue();
    assertThat(closed.slaSnapshots().get(0).resolveMet()).isTrue();
    assertThat(closed.slaSnapshots().get(0).slaReplyHours()).isEqualTo(24);
    assertThat(closed.slaSnapshots().get(0).closedAt()).isEqualTo(closed.head().closedAt());
  }

  @Test
  void 满意度只对已关闭的工单开放且一张一条() {
    seedSla("", 24, null);
    UUID caseId = newCase("SAT");
    ServiceTicketDetail t = newTicket(caseId, "SAT");

    assertThatThrownBy(
            () ->
                as(
                    () ->
                        tickets.recordSatisfaction(
                            t.head().id(), new ServiceTicketSatisfactionCreate(5, "很好"), user)))
        .hasStackTraceContaining("工单还没关闭");

    as(
        () ->
            tickets.closeTicket(
                t.head().id(), new ServiceTicketClose("RESOLVED", "已解决", 0L), user));
    ServiceTicketDetail rated =
        as(
            () ->
                tickets.recordSatisfaction(
                    t.head().id(), new ServiceTicketSatisfactionCreate(4, "响应快"), user));
    assertThat(rated.satisfactions()).hasSize(1);
    assertThat(rated.satisfactions().get(0).score()).isEqualTo(4);

    assertThatThrownBy(
            () ->
                as(
                    () ->
                        tickets.recordSatisfaction(
                            t.head().id(), new ServiceTicketSatisfactionCreate(1, "改分"), user)))
        .hasStackTraceContaining("已经评价过了");

    // 分值范围 1~5。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        tickets.recordSatisfaction(
                            t.head().id(), new ServiceTicketSatisfactionCreate(9, "越界"), user)))
        .hasStackTraceContaining("1~5");
  }

  // ==========================================================================
  // 升级：工单与售后单互转
  // ==========================================================================

  @Test
  void 咨询升级为投诉会真的建出一张售后单并把上下文带过去() {
    seedSla("", 24, null);
    seedRoster("甲", 1);
    UUID caseId = newCase("ESC");
    ServiceTicketDetail t =
        as(
            () ->
                tickets.createTicket(
                    new ServiceTicketCreate(
                        caseId.toString(), "INQUIRY", "MANUAL", "MSG-1", "退款多久到账",
                        "NORMAL", "买家问退款到账时间", "甲", null, null, null),
                    user));

    ServiceTicketDetail escalated =
        as(
            () ->
                tickets.escalateTicket(
                    t.head().id(),
                    new ServiceTicketEscalate(
                        "COMPLAINT", "买家不满答复", "RETURN_REFUND", "QUALITY_DEFECT",
                        AMOUNT, "USD", QTY, null, null, null, null, null, null, null, null, 1L),
                    user));

    assertThat(escalated.head().ticketType()).isEqualTo("COMPLAINT");
    assertThat(escalated.head().escalatedCaseId()).isNotNull();
    assertThat(escalated.head().escalatedCaseNo()).isNotBlank();
    assertThat(escalated.head().escalatedCaseNo()).isNotEqualTo(escalated.head().caseNo());
    // 上下文保留：新建的售后单里带着工单号与原诉求。
    String buyerRequest =
        as(
            () ->
                sql.fetchOne(
                        "select buyer_request from aftersales_case"
                            + " where tenant_id = ?::uuid and id = ?::uuid",
                        tenant, escalated.head().escalatedCaseId())
                    .get("buyer_request", String.class));
    assertThat(buyerRequest).contains(t.head().ticketNo()).contains("买家问退款到账时间");
    // 事件留痕。
    assertThat(escalated.events()).extracting(ServiceTicketEventRow::eventType)
        .contains("ESCALATED_TO_COMPLAINT");
  }

  @Test
  void 投诉升级为纠纷会真的建出一条平台纠纷() {
    seedSla("", 24, null);
    UUID caseId = newCase("ESC2");
    ServiceTicketDetail t =
        as(
            () ->
                tickets.createTicket(
                    new ServiceTicketCreate(
                        caseId.toString(), "COMPLAINT", "MESSAGE", "MSG-2", "平台判我不合规",
                        "HIGH", "买家投诉", null, null, null, null),
                    user));

    ServiceTicketDetail escalated =
        as(
            () ->
                tickets.escalateTicket(
                    t.head().id(),
                    new ServiceTicketEscalate(
                        "DISPUTE", "平台已立案", null, null, null, null, null,
                        "AMAZON", "Amazon", "A2Z-" + UUID.randomUUID(), "EXT-ORD",
                        "A_TO_Z", new BigDecimal("120.0000"), "USD",
                        OffsetDateTime.now(ZoneOffset.UTC).plusDays(3), 0L),
                    user));

    assertThat(escalated.head().escalatedDisputeId()).isNotNull();
    assertThat(escalated.head().escalatedDisputeNo()).isNotBlank();
    // 纠纷真的落在 11.4.1.1 的表里（不是 mock）。
    long disputes =
        as(
                () ->
                    sql.fetchOne(
                            "select count(*) from platform_dispute"
                                + " where tenant_id = ?::uuid and id = ?::uuid",
                            tenant, escalated.head().escalatedDisputeId())
                        .get(0, Long.class))
            .longValue();
    assertThat(disputes).isEqualTo(1L);
  }

  @Test
  void 类型推进是带方向的不能倒退() {
    seedSla("", 24, null);
    UUID caseId = newCase("DIR");
    ServiceTicketDetail t = newTicket(caseId, "DIR");

    // 咨询不能直接升为纠纷（要先升级为投诉）。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        tickets.escalateTicket(
                            t.head().id(),
                            new ServiceTicketEscalate(
                                "DISPUTE", "直接跳级", null, null, null, null, null,
                                "AMAZON", "Amazon", "A2Z-X", null, "A_TO_Z",
                                new BigDecimal("10.0000"), "USD",
                                OffsetDateTime.now(ZoneOffset.UTC).plusDays(1), 0L),
                            user)))
        .hasStackTraceContaining("只有「投诉」能升级为纠纷");
  }

  // ==========================================================================
  // SLA 达成统计
  // ==========================================================================

  @Test
  void SLA达成统计只认关闭时冻结的快照且没配处理时限的不进分母() {
    // 两档不同**平台档**的 SLA：'' 兜底档（24/48）与 SHOPIFY 精确档（24/无处理时限）。
    // ⚠️ 同一个 platform_scope 不能开两档（DB 守卫拒绝重叠区间）⇒ 靠平台档分开。
    seedSla("", 24, 48);
    seedSla("SHOPIFY", 24, null);

    UUID c1 = newCase("S1", "AMAZON");
    ServiceTicketDetail t1 = newTicket(c1, "S1");
    as(
        () ->
            tickets.recordFirstReply(
                t1.head().id(), new ServiceTicketFirstReply("已答复", 0L), user));
    as(
        () ->
            tickets.closeTicket(
                t1.head().id(), new ServiceTicketClose("RESOLVED", "已解决", 1L), user));

    // 第二张走 SHOPIFY 档：只管回复、**不配处理时限** ⇒ 不进处理时限的分母。
    UUID c2 = newCase("S2", "SHOPIFY");
    ServiceTicketDetail t2 = newTicket(c2, "S2");
    assertThat(t2.head().slaResolveHours()).isNull();
    as(
        () ->
            tickets.closeTicket(
                t2.head().id(), new ServiceTicketClose("RESOLVED", "已解决", 0L), user));

    ServiceTicketPage page = as(() -> tickets.listTickets(new ServiceTicketListQuery(0, 20, null, null, null, null, null, null, null, null, null, null, null, null, null)));

    assertThat(page.slaStats().closedCount()).isEqualTo(2L);
    assertThat(page.slaStats().replyMeasured()).isEqualTo(2L);
    assertThat(page.slaStats().replyMet()).isEqualTo(1L);
    assertThat(page.slaStats().replyBreached()).isEqualTo(1L);
    // ⚠️ 第二张没配处理时限 ⇒ **不进**处理时限的分母（0 而不是 1）。
    assertThat(page.slaStats().resolveMeasured()).isEqualTo(1L);
    assertThat(page.slaStats().resolveMet()).isEqualTo(1L);
    // 达成率：回复 1/2 = 50.00%，处理 1/1 = 100.00%。
    assertThat(page.slaStats().replyAchievementRate()).isEqualByComparingTo("50.00");
    assertThat(page.slaStats().resolveAchievementRate()).isEqualByComparingTo("100.00");
  }

  @Test
  void 没有任何关闭工单时达成率是空而不是零() {
    seedSla("", 24, null);
    ServiceTicketPage page =
        as(
            () ->
                tickets.listTickets(
                    new ServiceTicketListQuery(0, 20, null, null, null, null, null, null, null, null, null, null, null, null, null)));
    assertThat(page.slaStats().closedCount()).isZero();
    assertThat(page.slaStats().replyAchievementRate()).isNull();
  }

  // ==========================================================================
  // 列表 / 筛选 / 分页
  // ==========================================================================

  @Test
  void 真分页三页不重不漏且汇总与分页无关() {
    seedSla("", 24, null);
    List<UUID> ids = new java.util.ArrayList<>();
    for (int i = 0; i < 7; i++) {
      ids.add(newTicket(newCase("P" + i), "P" + i).head().id());
    }

    ServiceTicketPage p0 = as(() -> tickets.listTickets(new ServiceTicketListQuery(0, 3, null, null, null, null, null, null, null, null, null, null, null, null, null)));
    ServiceTicketPage p1 = as(() -> tickets.listTickets(new ServiceTicketListQuery(1, 3, null, null, null, null, null, null, null, null, null, null, null, null, null)));
    ServiceTicketPage p2 = as(() -> tickets.listTickets(new ServiceTicketListQuery(2, 3, null, null, null, null, null, null, null, null, null, null, null, null, null)));

    assertThat(p0.total()).isEqualTo(7L);
    assertThat(p0.rows()).hasSize(3);
    assertThat(p1.rows()).hasSize(3);
    assertThat(p2.rows()).hasSize(1);
    List<UUID> seen = new java.util.ArrayList<>();
    p0.rows().forEach(r -> seen.add(r.id()));
    p1.rows().forEach(r -> seen.add(r.id()));
    p2.rows().forEach(r -> seen.add(r.id()));
    assertThat(seen).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(ids);
    // 汇总与分页无关：每页看到的总数都是 7。
    assertThat(p1.summary().total()).isEqualTo(7L);
    assertThat(p2.summary().total()).isEqualTo(7L);
  }

  @Test
  void 多维筛选可叠加() {
    seedSla("", 24, null);
    seedRoster("甲", 1);
    seedRoster("乙", 1);
    UUID c1 = newCase("F1");
    ServiceTicketDetail t1 = newTicket(c1, "F1");
    as(
        () ->
            tickets.assignTicket(
                t1.head().id(), new ServiceTicketAssign("甲", null, "", 0L), user));
    UUID c2 = newCase("F2");
    ServiceTicketDetail t2 =
        as(
            () ->
                tickets.createTicket(
                    new ServiceTicketCreate(
                        c2.toString(), "COMPLAINT", "MESSAGE", "M1", "主题2", "URGENT", "诉求2",
                        null, null, null, null),
                    user));

    ServiceTicketPage byType =
        as(
            () ->
                tickets.listTickets(
                    new ServiceTicketListQuery(0, 20, null, "COMPLAINT", null, null, null, null, null, null, null, null, null, null, null)));
    assertThat(byType.rows()).extracting(ServiceTicketRow::id).containsExactly(t2.head().id());

    ServiceTicketPage byOwner =
        as(
            () ->
                tickets.listTickets(
                    new ServiceTicketListQuery(0, 20, null, null, null, null, "甲", null, null, null, null, null, null, null, null)));
    assertThat(byOwner.rows()).extracting(ServiceTicketRow::id).containsExactly(t1.head().id());

    ServiceTicketPage bySource =
        as(
            () ->
                tickets.listTickets(
                    new ServiceTicketListQuery(0, 20, null, null, null, "MESSAGE", null, null, null, null, null, null, null, null, null)));
    assertThat(bySource.rows()).extracting(ServiceTicketRow::id).containsExactly(t2.head().id());

    ServiceTicketPage byPriority =
        as(
            () ->
                tickets.listTickets(
                    new ServiceTicketListQuery(0, 20, null, null, "URGENT", null, null, null, null, null, null, null, null, null, null)));
    assertThat(byPriority.rows()).extracting(ServiceTicketRow::id).containsExactly(t2.head().id());

    ServiceTicketPage byCase =
        as(
            () ->
                tickets.listTickets(
                    new ServiceTicketListQuery(0, 20, null, null, null, null, null, c1.toString(), null, null, null, null, null, null, null)));
    assertThat(byCase.rows()).extracting(ServiceTicketRow::id).containsExactly(t1.head().id());
  }

  @Test
  void 超时筛选命中的是真超时且已回复的不算() {
    seedSla("", 24, null);
    UUID c1 = newCase("OD1");
    ServiceTicketDetail t1 = newTicket(c1, "OD1");
    // 把回复时限推到过去 ⇒ 真超时。
    as(
        () -> {
          sql.execute(
              "update aftersales_service_ticket set reply_due_at = ?::timestamptz"
                  + " where tenant_id = ?::uuid and id = ?::uuid",
              OffsetDateTime.now(ZoneOffset.UTC).minusHours(1), tenant, t1.head().id());
          return null;
        });
    UUID c2 = newCase("OD2");
    ServiceTicketDetail t2 = newTicket(c2, "OD2");
    ServiceTicketDetail replied =
        as(
            () ->
                tickets.recordFirstReply(
                    t2.head().id(), new ServiceTicketFirstReply("已答复", 0L), user));

    ServiceTicketPage overdue =
        as(
            () ->
                tickets.listTickets(
                    new ServiceTicketListQuery(0, 20, null, null, null, null, null, null, null, true, null, null, null, null, null)));
    assertThat(overdue.rows()).extracting(ServiceTicketRow::id).containsExactly(t1.head().id());
    assertThat(overdue.summary().overdue()).isEqualTo(1L);
    // 已回复的工单虽然时限也过了，但不算「超时未回」。
    assertThat(replied.head().replySlaState()).isEqualTo("MET");
  }

  // ==========================================================================
  // DB 护栏（绕过服务层直连 SQL 也一样）
  // ==========================================================================

  @Test
  void 绕过服务层改工单的标识列会被数据库挡住() {
    seedSla("", 24, null);
    UUID caseId = newCase("G");
    ServiceTicketDetail t = newTicket(caseId, "G");

    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "update aftersales_service_ticket set ticket_no = 'TK-HACK'"
                              + " where tenant_id = ?::uuid and id = ?::uuid",
                          tenant, t.head().id());
                      return null;
                    }))
        .hasStackTraceContaining("标识列");
  }

  @Test
  void 绕过服务层把工单挂到不存在的售后单上会被数据库挡住() {
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "insert into aftersales_service_ticket(id,tenant_id,ticket_no,case_id,"
                              + "ticket_status,priority,reason_note,owner_module,created_by,created_at)"
                              + " values(?::uuid,?::uuid,'TK-FAKE',?::uuid,'OPEN','NORMAL','',"
                              + "'11.4.2.1',?::uuid,now())",
                          UUID.randomUUID(), tenant, UUID.randomUUID(), user);
                      return null;
                    }))
        .hasStackTraceContaining("真实存在");
  }

  @Test
  void 附属事实表是追加式的运行角色改不动也删不掉() {
    as(
        () -> {
          assertThat(
                  sql.fetchOne(
                          "select has_table_privilege('trade_app',"
                              + " 'aftersales_ticket_assignment','UPDATE')")
                      .get(0, Boolean.class))
              .isFalse();
          assertThat(
                  sql.fetchOne(
                          "select has_table_privilege('trade_app',"
                              + " 'aftersales_ticket_event','DELETE')")
                      .get(0, Boolean.class))
              .isFalse();
          assertThat(
                  sql.fetchOne(
                          "select has_table_privilege('trade_app',"
                              + " 'aftersales_ticket_satisfaction','UPDATE')")
                      .get(0, Boolean.class))
              .isFalse();
          assertThat(
                  sql.fetchOne(
                          "select has_table_privilege('trade_app',"
                              + " 'aftersales_ticket_sla_snapshot','DELETE')")
                      .get(0, Boolean.class))
              .isFalse();
          // 主表的 DELETE 也仍然被收回（V156 的纪律，本模块不放开）。
          assertThat(
                  sql.fetchOne(
                          "select has_table_privilege('trade_app',"
                              + " 'aftersales_service_ticket','DELETE')")
                      .get(0, Boolean.class))
              .isFalse();
          // 主表的 UPDATE 是**列级白名单**：标识列改不动。
          assertThat(
                  sql.fetchOne(
                          "select has_column_privilege('trade_app',"
                              + " 'aftersales_service_ticket','ticket_no','UPDATE')")
                      .get(0, Boolean.class))
              .isFalse();
          assertThat(
                  sql.fetchOne(
                          "select has_column_privilege('trade_app',"
                              + " 'aftersales_service_ticket','ticket_status','UPDATE')")
                      .get(0, Boolean.class))
              .isTrue();
          return null;
        });
  }

  @Test
  void SLA判定函数是IMMUTABLE的所以能进索引也能在WHERE里用() {
    as(
        () -> {
          assertThat(
                  sql.fetchOne(
                          "select provolatile from pg_proc"
                              + " where proname = 'aftersales_ticket_sla_state'")
                      .get(0, String.class))
              .isEqualTo("i");
          return null;
        });
  }

  // ==========================================================================
  // HTTP 层
  // ==========================================================================

  @Test
  void HTTP层列表与详情返回真实数据() throws Exception {
    seedSla("", 24, null);
    UUID caseId = newCase("H");
    ServiceTicketDetail t = newTicket(caseId, "H");

    String page =
        http.perform(get("/trade/service-tickets").header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(page).contains(t.head().ticketNo()).contains(t.head().caseNo());

    String detail =
        http.perform(
                get("/trade/service-tickets/" + t.head().id())
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(detail).contains(t.head().ticketNo()).contains("CREATED");

    String caps =
        http.perform(
                get("/trade/service-tickets/capabilities")
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(caps).contains("一张售后单只转一次工单").contains("15 客户与客服");
  }

  // ==========================================================================
  // 跨模块引用守卫（V276）：升级去向必须真实存在**且属于当前租户**
  // ==========================================================================
  //
  // ⚠️ 这一节是 **V276 的自证**。
  //   V270 原来用两条**跨模块外键**钉住「升级去向必须真实存在」，
  //   但外键让 11.4.1.1 / 11.4.1.2 的 `truncate platform_dispute` 直接炸掉（跨车道破坏）。
  //   V276 把它换成「同租户存在性守卫触发器」：
  //   触发器**不挡 TRUNCATE**，但**照样挡住**「升级了但对方根本不存在」，
  //   而且比原来的**裸 id** 外键**更严** —— 跨租户引用也拦。

  /** 另一个租户：用来证明「跨租户引用」也被拦。 */
  static final UUID otherTenant = UUID.randomUUID();

  /**
   * 以**超管**连接（绕过 RLS）执行一段 SQL 并提交。
   *
   * <p>用 jOOQ 的 {@code DSL.using(connection)} 而不是裸 JDBC Statement：拼装 SQL 的写法与
   * {@code ServiceTicketService} 一致，免得测试里维护第二套绑参风格（同 ExchangeAuthorizationIntegrationTest）。
   */
  void runAsSuperuser(java.util.function.Consumer<org.jooq.DSLContext> work) throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      c.setAutoCommit(false);
      try {
        work.accept(org.jooq.impl.DSL.using(c, org.jooq.SQLDialect.POSTGRES));
        c.commit();
      } catch (Exception e) {
        c.rollback();
        throw e;
      }
    }
  }

  /**
   * 绕过服务层直连 SQL 写一行工单（只为打到 DB 守卫那一层）。
   *
   * <p>⚠️ 刻意**不**走 {@code ServiceTicketService}：服务层的升级动作会自己建出对方的单，
   * 写不进去的路径根本到不了「守卫」这一层。
   *
   * <p>只写被测的那一个升级去向列，另一个留 NULL —— 免得测试里维护「绑 null 的 uuid」那套写法。
   */
  void insertTicketRow(
      org.jooq.DSLContext dsl, UUID caseId, String tag, boolean dispute, UUID target) {
    dsl.execute(
        dispute
            ? "insert into aftersales_service_ticket(id,tenant_id,ticket_no,case_id,ticket_status,"
                + "priority,reason_note,owner_module,created_by,created_at,escalated_dispute_id)"
                + " values(?::uuid,?::uuid,?,?::uuid,'OPEN','NORMAL','','11.4.2.1',?::uuid,?::timestamptz,?::uuid)"
            : "insert into aftersales_service_ticket(id,tenant_id,ticket_no,case_id,ticket_status,"
                + "priority,reason_note,owner_module,created_by,created_at,escalated_case_id)"
                + " values(?::uuid,?::uuid,?,?::uuid,'OPEN','NORMAL','','11.4.2.1',?::uuid,?::timestamptz,?::uuid)",
        UUID.randomUUID(), tenant, "TK-GRD-" + tag, caseId, user, T1, target);
  }

  /** 走应用路径写一行工单（运行角色 + RLS + 守卫）。 */
  void insertTicketRow(UUID caseId, String tag, boolean dispute, UUID target) {
    as(
        () -> {
          insertTicketRow(sql, caseId, tag, dispute, target);
          return null;
        });
  }

  /** 以超管连接写一行工单（RLS 完全不参与 ⇒ 拦它的只能是守卫本身）。 */
  void insertTicketRowAsSuperuser(UUID caseId, String tag, boolean dispute, UUID target)
      throws Exception {
    runAsSuperuser(dsl -> insertTicketRow(dsl, caseId, tag, dispute, target));
  }

  /** 在**当前租户**名下建一条平台纠纷（走应用路径：运行角色 + RLS）。 */
  UUID seedDispute(String tag) {
    UUID id = UUID.randomUUID();
    as(
        () -> {
          sql.execute(
              "insert into platform_dispute(id,tenant_id,dispute_no,platform_code,platform_name,"
                  + "platform_dispute_no,platform_order_no,dispute_type,dispute_reason,"
                  + "dispute_currency,dispute_amount,evidence_deadline,dispute_status,created_by)"
                  + " values(?::uuid,?::uuid,?,'AMAZON','Amazon',?,'','A_TO_Z','','USD',?::numeric,"
                  + "?::timestamptz,'OPEN',?::uuid)",
              id, tenant, "PDS-" + tag, "AZ-" + tag, AMOUNT,
              OffsetDateTime.now(ZoneOffset.UTC).plusDays(14), user);
          return null;
        });
    return id;
  }

  /** 在 **otherTenant** 名下建一条平台纠纷（超管连接，绕过 RLS）。 */
  UUID seedOtherTenantDispute(String tag) throws Exception {
    UUID id = UUID.randomUUID();
    runAsSuperuser(
        dsl ->
            dsl.execute(
                "insert into platform_dispute(id,tenant_id,dispute_no,platform_code,platform_name,"
                    + "platform_dispute_no,platform_order_no,dispute_type,dispute_reason,"
                    + "dispute_currency,dispute_amount,evidence_deadline,dispute_status,created_by)"
                    + " values(?::uuid,?::uuid,?,'AMAZON','Amazon',?,'','A_TO_Z','','USD',?::numeric,"
                    + "?::timestamptz,'OPEN',?::uuid)",
                id, otherTenant, "OT-PDS-" + tag, "OT-AZ-" + tag, AMOUNT,
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(14), user));
    return id;
  }

  /** 在 **otherTenant** 名下建一张售后单（超管连接，绕过 RLS）。 */
  UUID seedOtherTenantCase(String tag) throws Exception {
    UUID id = UUID.randomUUID();
    runAsSuperuser(
        dsl ->
            dsl.execute(
                "insert into aftersales_case(id,tenant_id,case_no,order_id,case_type,status,"
                    + "reason_code,requested_amount,currency,requested_quantity,reply_due_at,"
                    + "received_at,created_at) values(?::uuid,?::uuid,?,?::uuid,'RETURN_REFUND',"
                    + "'RECEIVED','QUALITY_DEFECT',?::numeric,'USD',?::numeric,?::timestamptz,"
                    + "?::timestamptz,?::timestamptz)",
                id, otherTenant, "OT-CASE-" + tag, UUID.randomUUID(), AMOUNT, QTY,
                T1.plusDays(3), T1, T1));
    return id;
  }

  @Test
  void 升级去向指向不存在的纠纷会被DB拒绝() {
    seedSla("", 24, 48);
    UUID caseId = newCase("GRD-NODIS");

    assertDbRefused(
        () -> insertTicketRow(caseId, "NODIS", true, UUID.randomUUID()),
        "升级去向的平台纠纷不存在或不属于当前租户");
  }

  @Test
  void 升级去向指向别的租户的纠纷会被DB拒绝() throws Exception {
    seedSla("", 24, 48);
    UUID caseId = newCase("GRD-XDIS");
    UUID otherDispute = seedOtherTenantDispute("XDIS");

    // 走应用路径：运行角色 + RLS + 守卫（任一层都能拦，但必须是**守卫自己的措辞**）。
    assertDbRefused(
        () -> insertTicketRow(caseId, "XDIS", true, otherDispute),
        "升级去向的平台纠纷不存在或不属于当前租户");

    // ⚠️ 再走一次**超管连接**（RLS 完全不参与）：
    //   拦它的只能是守卫里的 tenant_id 判定 —— 这正是原来那条**裸 id 外键**放行的那个洞。
    assertDbRefused(
        () -> insertTicketRowAsSuperuser(caseId, "XDIS2", true, otherDispute),
        "升级去向的平台纠纷不存在或不属于当前租户");
  }

  @Test
  void 升级去向指向同租户真实存在的纠纷可以通过() {
    seedSla("", 24, 48);
    UUID caseId = newCase("GRD-OKDIS");
    UUID disputeId = seedDispute("OKDIS");

    insertTicketRow(caseId, "OKDIS", true, disputeId);

    assertThat(
            as(
                    () ->
                        sql.fetchOne(
                                "select escalated_dispute_id from aftersales_service_ticket"
                                    + " where tenant_id = ?::uuid and case_id = ?::uuid",
                                tenant, caseId)
                            .get("escalated_dispute_id", UUID.class)))
        .isEqualTo(disputeId);
  }

  @Test
  void 升级去向指向不存在的售后单会被DB拒绝() {
    seedSla("", 24, 48);
    UUID caseId = newCase("GRD-NOCASE");

    assertDbRefused(
        () -> insertTicketRow(caseId, "NOCASE", false, UUID.randomUUID()),
        "升级去向的售后单不存在或不属于当前租户");
  }

  @Test
  void 升级去向指向别的租户的售后单会被DB拒绝() throws Exception {
    seedSla("", 24, 48);
    UUID caseId = newCase("GRD-XCASE");
    UUID otherCase = seedOtherTenantCase("XCASE");

    assertDbRefused(
        () -> insertTicketRow(caseId, "XCASE", false, otherCase),
        "升级去向的售后单不存在或不属于当前租户");

    // ⚠️ 超管连接：RLS 不参与 ⇒ 拦它的只能是守卫里的 tenant_id 判定。
    assertDbRefused(
        () -> insertTicketRowAsSuperuser(caseId, "XCASE2", false, otherCase),
        "升级去向的售后单不存在或不属于当前租户");
  }

  @Test
  void 升级去向指向同租户真实存在的售后单可以通过() {
    seedSla("", 24, 48);
    UUID caseId = newCase("GRD-OKCASE");
    // 升级去向是**另一张**售后单（咨询升级为投诉：新建一张售后单）。
    UUID escalatedCaseId = newCase("GRD-OKCASE2");

    insertTicketRow(caseId, "OKCASE", false, escalatedCaseId);

    assertThat(
            as(
                    () ->
                        sql.fetchOne(
                                "select escalated_case_id from aftersales_service_ticket"
                                    + " where tenant_id = ?::uuid and case_id = ?::uuid",
                                tenant, caseId)
                            .get("escalated_case_id", UUID.class)))
        .isEqualTo(escalatedCaseId);
  }

  /** 沿 cause 链找最里面那条 SQLException —— DB 拒绝的**原文**在那里。 */
  static java.sql.SQLException rootSqlException(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof java.sql.SQLException sql) {
        return sql;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    throw new AssertionError("异常链里没有 SQLException，说明不是 DB 拒绝：", error);
  }

  /** 断言一段工作被 DB 拒绝（23514 = 触发器/check 约束）。 */
  static void assertDbRefused(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable work, String expectedFragment) {
    var error = org.assertj.core.api.Assertions.catchThrowable(work);
    assertThat(error).as("应当被 DB 拒绝").isNotNull();
    var sqlError = rootSqlException(error);
    assertThat(sqlError.getSQLState()).as("必须是 DB 层拒绝（23514）").isEqualTo("23514");
    assertThat(sqlError.getMessage()).contains(expectedFragment);
  }
}
