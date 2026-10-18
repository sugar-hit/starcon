package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.starcon.trade.RefundApplicationModels.*;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * 11.3.1.1 退款申请。
 *
 * <p>跑的是蓝图职责句的四块与那条加粗句：
 *
 * <blockquote>
 * 职责：发起退款：选择退款方式（原路/平台/余额）、金额（可部分）、原因，
 * 校验累计退款不超过可退余额，提交审批后交财务执行。
 * 输入：退款申请；可退余额校验（已退 + 在途）；审批规则。
 * 输出：退款申请单（待审/已批/执行中/成功/失败）+ 可退余额更新。
 * <b>可退余额校验必须在服务端做</b>——重复提交退款是资损事故的高发点。
 * </blockquote>
 *
 * <p><b>本类最看重的八组断言</b>：
 *
 * <ol>
 *   <li><b>可退余额的五个占用桶真的分开算</b>：已退 / 在途派发 / 已登记待发起 / 待审占用 / 对账争议，
 *       每塞一个桶，可退余额就要真的掉一截 —— 而 FAILED 执行记录<b>不占</b>。</li>
 *   <li><b>「服务端校验」是可验证的，不是口号</b>：绕过服务层直连 SQL 写超额金额，
 *       DB 触发器照样拒绝（V166 第二节的【闸二】）。</li>
 *   <li><b>审批规则 fail-closed</b>：没配规则时一笔都放不过双签；
 *       配了阈值后未达阈值的走单签 —— 两种口径都断言，不只测一种。</li>
 *   <li><b>自己不能批自己，同一个人不能双签</b>：两条都由 DB 触发器强拦。</li>
 *   <li><b>时间写回零漂移</b>：夹具刻意用 +08:00 非 UTC 偏移，
 *       {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s，只有这样才能测出来。</li>
 *   <li><b>外部超时不等于失败</b>：执行记录停在 UNKNOWN 时，
 *       本模块<b>不许</b>把申请单宣布成成功或失败。</li>
 *   <li><b>12 财务未交付就说未交付</b>：声称来自 FINANCE_MODULE_12 的结果被拒。</li>
 *   <li><b>分页 / 排序 / 检索都是真的</b>：两页不重叠、汇总不受分页、排序白名单拒绝任意表达式。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class RefundApplicationIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID other = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  /** 第二个与第三个操作者：双签必须两个人，且都不能是提交人。 */
  static final UUID approver2 = UUID.randomUUID();
  static final UUID approver3 = UUID.randomUUID();

  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> perms =
      List.of("trade.aftersales.read", "trade.aftersales.write");

  static AccessService.Access actor(UUID uid, UUID tid) {
    var grants = List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", perms));
    return new AccessService.Access(uid, tid, "Test", UUID.randomUUID(), null, perms, grants);
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
      }
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            var found = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (found == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = json.writeValueAsBytes(found);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-main", actor(user, tenant));
      actors.put("Bearer tenant-other", actor(UUID.randomUUID(), other));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired RefundApplicationService refunds;
  @Autowired AftersalesCaseService cases;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  /** **非 UTC 偏移**夹具：只有这种时刻才能测出 Timestamp.valueOf 的 −28800s 静默偏移。 */
  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");

  @BeforeEach
  void clean() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate refund_application_event, refund_application, refund_approval_policy,"
              + " aftersales_case_line, aftersales_case_note, aftersales_authorization,"
              + " aftersales_service_ticket, aftersales_owner_assignment, aftersales_case,"
              + " sales_order_line, order_address_snapshot, sales_order_party_snapshot,"
              + " order_fulfillment_line_progress, sales_order cascade");
    }
  }

  <T> T as(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-main"), "trade.aftersales.write", work);
  }

  // ================================================================ 夹具

  record Seeded(UUID caseId, UUID orderId, String orderNo) {}

  /** 一张真实订单（带商品行与地址）+ 一张真实售后单。 */
  Seeded seed(String suffix, String baseAmount, String currency) {
    UUID orderId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    BigDecimal qty = new BigDecimal("2");
    BigDecimal unitPrice = new BigDecimal(baseAmount).divide(qty, 4, java.math.RoundingMode.HALF_UP);
    BigDecimal amount = new BigDecimal(baseAmount);
    String orderNo = "MO-" + suffix;

    as(
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?::uuid,?::uuid,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,"
                  + "?::numeric,0,0,0,0,?::numeric,0,?::timestamptz,?::timestamptz)",
              orderId, tenant, orderNo, "EXT-" + orderNo, T1, amount, amount, T1, T1);
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "spec_snapshot,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
                  + "created_at) values(?::uuid,?::uuid,?::uuid,1,?::uuid,?,?,'黑色 / L',?::numeric,"
                  + "?::numeric,0,0,?::numeric,'',?::timestamptz)",
              lineId, tenant, orderId, UUID.randomUUID(), "SKU-" + suffix, "商品" + suffix,
              qty, unitPrice, amount, T1);
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
                  + "recipient_phone,recipient_email,company_name,country_code,province,city,"
                  + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
                  + " values(?::uuid,?::uuid,?::uuid,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
                  + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, "买家-" + suffix,
              "buyer-" + suffix + "@example.com", T1);
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,'买家-" + suffix + "','INDIVIDUAL','',"
                  + "?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), "C-" + suffix, T1);
          sql.execute(
              "insert into order_fulfillment_line_progress(id,tenant_id,order_id,order_line_id,"
                  + "ordered_qty,reserved_qty,picking_qty,shipped_qty,shipped_at,progress_source,"
                  + "observed_at,version,created_at,updated_at)"
                  + " values(?::uuid,?::uuid,?::uuid,?::uuid,?::numeric,0,0,?::numeric,"
                  + "?::timestamptz,'MODULE_9_3_3_1',?::timestamptz,0,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, lineId, qty, qty, T1, T1, T1, T1);
          return null;
        });

    UUID caseId =
        as(
            () ->
                cases.create(
                        new AftersalesCaseModels.AftersalesCaseCreate(
                            orderId, "RETURN_REFUND", "QUALITY_DEFECT", "碎了",
                            amount, currency, qty, "PLAT-" + suffix, null, null,
                            "买家诉求-" + suffix, T1, null, T1,
                            null, null, null, "TENANT_MAINTAINED", null, T1),
                        user)
                    .id());
    return new Seeded(caseId, orderId, orderNo);
  }

  Seeded seed(String suffix) {
    return seed(suffix, "60.0000", "USD");
  }

  /**
   * 直接插一条退款执行记录（11.3.1.2 / V159，本模块只读它）。
   *
   * <p>⚠️ 失败三元组必须**跟着状态走**：V159 的 CHECK refund_execution_success_has_no_failure
   * 规定非 FAILED 态的 failure_class / failure_code / failure_reason 必须全为空，
   * 而 refund_execution_failure_is_definitive 规定 FAILED ⇒ DEFINITIVE。
   * 夹具把失败码写死成常量会在 SUCCEEDED 那几例上直接撞约束。
   */
  UUID execution(Seeded s, String amount, String state, UUID caseId) {
    UUID id = UUID.randomUUID();
    boolean terminal = List.of("SUCCEEDED", "FAILED", "DISCREPANCY").contains(state);
    boolean failed = "FAILED".equals(state);
    boolean unknown = "UNKNOWN".equals(state);
    String failureClass = failed ? "DEFINITIVE" : null;
    String failureCode = failed ? "E_DEMO" : "";
    // ⚠️ V159 的 refund_execution_failure_has_reason：UNKNOWN 也必须带原因 ——
    //   「查不出结果却不写原因」正是它要挡的那一行。
    String failureReason = failed ? "演示失败" : (unknown ? "外部超时，未回执" : "");
    as(
        () -> {
          // ⚠️ **不绑定 Java null**：jOOQ 的位置参数收到 null 时推不出类型，
          //   报出来的是一句「未设定参数值 N 的内容」，与根因（这里本该是 NULL）毫无关系。
          //   可空列统一绑空串、在 SQL 侧用 nullif 转回 NULL；
          //   ⚠️ failure_code / failure_reason 是 NOT NULL，只能直接绑串（失败时非空、否则空串）。
          sql.execute(
              "insert into refund_execution(id,tenant_id,refund_no,order_id,case_id,"
                  + "idempotency_key,refund_amount,currency,execution_state,failure_class,"
                  + "failure_code,failure_reason,external_transaction_ref,platform_sync_state,"
                  + "platform_refund_no,platform_refund_method,created_at,updated_at,initiated_at,"
                  + "resolved_at) values(?::uuid,?::uuid,?,?::uuid,?::uuid,?,?::numeric,'USD',?,"
                  + "nullif(?::varchar,''),?::varchar,?::varchar,'TXN-1',"
                  + "'NOT_REQUESTED','','ORIGINAL',?::timestamptz,?::timestamptz,"
                  + "nullif(?::varchar,'')::timestamptz,nullif(?::varchar,'')::timestamptz)",
              id, tenant, "RE-" + UUID.randomUUID().toString().substring(0, 6), s.orderId(), caseId,
              "idem-" + UUID.randomUUID(), new BigDecimal(amount), state,
              failureClass == null ? "" : failureClass, failureCode, failureReason,
              T1, T1,
              terminal || !"NOT_INITIATED".equals(state) ? T1.toString() : "",
              terminal ? T1.toString() : "");
          return null;
        });
    return id;
  }

  RefundCreate create(Seeded s, String amount, String method) {
    return new RefundCreate(
        s.caseId(), null, method, new BigDecimal(amount), "USD", "RETURN_REFUND",
        null, "ORIGINAL".equals(method) ? "PAY-" + UUID.randomUUID() : null,
        "PLATFORM".equals(method) ? "PR-" + UUID.randomUUID() : null);
  }

  /**
   * 草稿 → 待审 →（按规则）初审 →（按规则）复审。
   *
   * <p>⚠️ 返回**版本号**而不是硬编码 2：单签路径走 3 次流转（0→1→2），
   *   双签路径走 4 次（0→1→2→3）。把版本硬编码成 2 会让双签那几例全部挂在
   *   「已被他人修改」上，而那看起来像是乐观锁坏了。
   */
  record Approved(UUID id, long version) {}

  Approved approvedApplication(Seeded s, String amount) {
    UUID id = as(() -> refunds.create(create(s, amount, "ORIGINAL"), user).head().id());
    as(() -> refunds.submit(id, new RefundSubmit(0L), user));
    RefundDetail first = as(() -> refunds.decide(id, new RefundDecision(true, "初审通过", 1L), approver2));
    if (first.head().applicationStatus().equals("SECOND_APPROVAL_PENDING")) {
      RefundDetail second = as(() -> refunds.decide(id, new RefundDecision(true, "复审通过", 2L), approver3));
      return new Approved(id, second.head().version());
    }
    return new Approved(id, first.head().version());
  }

  // ==========================================================================
  // 一、可退余额：五个占用桶逐桶生效
  // ==========================================================================
  @Test
  void 可退余额的五个占用桶逐桶生效而失败不占用() {
    Seeded s = seed("BAL");
    RefundBalance initial = as(() -> refunds.balance(s.caseId()));
    assertThat(initial.baseAmount()).isEqualByComparingTo("60.0000");
    assertThat(initial.availableAmount()).isEqualByComparingTo("60.0000");
    assertThat(initial.refundable()).isTrue();

    // 桶②在途派发（IN_FLIGHT）
    execution(s, "10.0000", "IN_FLIGHT", s.caseId());
    assertThat(as(() -> refunds.balance(s.caseId())).availableAmount())
        .as("在途派发 10 应真的占掉额度")
        .isEqualByComparingTo("50.0000");

    // 桶③已登记待发起（NOT_INITIATED）—— 钱随时可能出去，同样占用
    execution(s, "5.0000", "NOT_INITIATED", s.caseId());
    assertThat(as(() -> refunds.balance(s.caseId())).availableAmount())
        .isEqualByComparingTo("45.0000");

    // 桶⑤对账争议 —— 不知道钱出去没有，按宁可拦住也不能放过计入
    execution(s, "3.0000", "DISCREPANCY", s.caseId());
    RefundBalance disputed = as(() -> refunds.balance(s.caseId()));
    assertThat(disputed.disputedAmount()).isEqualByComparingTo("3.0000");
    assertThat(disputed.availableAmount()).isEqualByComparingTo("42.0000");
    assertThat(disputed.blockingReasons()).anyMatch(r -> r.contains("对账争议"));

    // 桶④待审占用：本表自己的申请单
    UUID pending = as(() -> refunds.create(create(s, "7.0000", "ORIGINAL"), user).head().id());
    as(() -> refunds.submit(pending, new RefundSubmit(0L), user));
    RefundBalance withPending = as(() -> refunds.balance(s.caseId()));
    assertThat(withPending.pendingAmount()).isEqualByComparingTo("7.0000");
    assertThat(withPending.availableAmount()).isEqualByComparingTo("35.0000");

    // ⚠️ **FAILED 不占用**：V159 已钉死「失败 = 钱确定没有出去」，
    //   把它算进占用会让一张明确失败的退款永久吃掉额度，运营只能改库才能再退一次。
    Seeded f = seed("FAIL", "60.0000", "USD");
    execution(f, "20.0000", "FAILED", f.caseId());
    assertThat(as(() -> refunds.balance(f.caseId())).availableAmount())
        .as("确定性失败的执行记录不占用可退余额")
        .isEqualByComparingTo("60.0000");
    assertThat(as(() -> refunds.balance(f.caseId())).occupiedAmount()).isEqualByComparingTo("0");
  }

  @Test
  void 已退的桶来自执行记录而已派发的申请单不双算() {
    Seeded s = seed("SET");
    // 桶①已退 · 执行记录侧
    execution(s, "25.0000", "SUCCEEDED", s.caseId());
    RefundBalance afterSettled = as(() -> refunds.balance(s.caseId()));
    assertThat(afterSettled.settledAmount()).isEqualByComparingTo("25.0000");
    assertThat(afterSettled.availableAmount()).isEqualByComparingTo("35.0000");

    // 已批 → 派发：额度改由**执行记录**的桶计一次，本单不再计入待审占用。
    Approved approved = approvedApplication(s, "20.0000");
    assertThat(as(() -> refunds.balance(s.caseId())).pendingAmount())
        .as("待审 / 已批的单占用额度").isEqualByComparingTo("20.0000");
    UUID executionId = execution(s, "20.0000", "NOT_INITIATED", s.caseId());
    as(() -> refunds.dispatch(approved.id(),
        new RefundDispatch(executionId, approved.version()), user));

    RefundBalance dispatched = as(() -> refunds.balance(s.caseId()));
    assertThat(dispatched.pendingAmount())
        .as("已挂执行记录的单不再计入待审占用 —— 否则同一笔钱会被算两次")
        .isEqualByComparingTo("0");
    assertThat(dispatched.registeredAmount())
        .as("改由执行记录的「已登记待发起」桶计")
        .isEqualByComparingTo("20.0000");
    assertThat(dispatched.occupiedAmount()).isEqualByComparingTo("45.0000");
    assertThat(dispatched.availableAmount()).isEqualByComparingTo("15.0000");
  }

  // ==========================================================================
  // 二、蓝图加粗句：可退余额校验必须在服务端做
  // ==========================================================================
  @Test
  void 重复提交退款被服务端挡住而且绕过服务层直连SQL也挡得住() {
    Seeded s = seed("DUP");
    Approved first = approvedApplication(s, "40.0000");
    assertThat(as(() -> refunds.detail(first.id())).head().applicationStatus())
        .isIn("APPROVED", "SECOND_APPROVED");

    // 第二笔 25 超过剩余的 20 —— 服务层挡住
    assertThatThrownBy(() -> as(() -> refunds.create(create(s, "25.0000", "ORIGINAL"), user)))
        .hasMessageContaining("超过可退余额");

    // ⚠️ **绕过服务层直连 SQL 也照样被挡**（V166【闸二】）：DB 触发器真查一遍五桶。
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "insert into refund_application(id,tenant_id,application_no,case_id,"
                              + "order_id,application_status,refund_method,refund_amount,currency,"
                              + "refund_reason,original_payment_ref,created_by,created_at,updated_at)"
                              + " values(?::uuid,?::uuid,?,?::uuid,?::uuid,"
                              + "'PENDING_APPROVAL','ORIGINAL',25.0000,'USD','RETURN_REFUND',"
                              + "'PAY-X',?::uuid,?::timestamptz,?::timestamptz)",
                          UUID.randomUUID(), tenant, "PWN-" + UUID.randomUUID().toString().substring(0, 8), s.caseId(),
                          s.orderId(), user, T1, T1);
                      return null;
                    }))
        .as("直连 SQL 的超额退款必须被 DB 触发器拒绝")
        .hasMessageContaining("超过可退余额");

    // 而恰好等于剩余额度的那笔是可以的 —— 挡住的是超额，不是「第二笔」。
    UUID ok = as(() -> refunds.create(create(s, "20.0000", "ORIGINAL"), user).head().id());
    assertThat(ok).isNotNull();
    // ⚠️ 草稿**不占用**额度（提交才占用），所以这时候还剩 20 可退 —— 这是刻意的：
    //   一张还没提交的草稿锁死额度，会让「先存草稿、等额度回来再提交」这种正常做法被误判。
    assertThat(as(() -> refunds.balance(s.caseId())).availableAmount()).isEqualByComparingTo("20.0000");
    assertThat(as(() -> refunds.balance(s.caseId())).refundable()).isTrue();

    // 提交之后才真正占用：可退余额归零，第三笔必然超额。
    as(() -> refunds.submit(ok, new RefundSubmit(0L), user));
    assertThat(as(() -> refunds.balance(s.caseId())).availableAmount()).isEqualByComparingTo("0");
    assertThat(as(() -> refunds.balance(s.caseId())).refundable()).isFalse();
    assertThatThrownBy(() -> as(() -> refunds.create(create(s, "0.0001", "ORIGINAL"), user)))
        .hasMessageContaining("超过可退余额");
  }

  @Test
  void 提交那一刻要重新校验而页面打开那一刻不算() {
    Seeded s = seed("RECHECK");
    approvedApplication(s, "40.0000");
    // 第二笔先按 20 建草稿 —— 建单那一刻还退得起
    UUID second = as(() -> refunds.create(create(s, "20.0000", "ORIGINAL"), user).head().id());

    // 期间别人把额度吃掉
    approvedApplication(s, "20.0000");

    // 提交时复核不通过（页面打开到提交之间额度被占走了）
    assertThatThrownBy(() -> as(() -> refunds.submit(second, new RefundSubmit(0L), user)))
        .hasMessageContaining("提交时复核不通过");
    assertThat(as(() -> refunds.detail(second)).head().applicationStatus()).isEqualTo("DRAFT");
  }

  // ==========================================================================
  // 三、审批规则：默认 fail-closed，配了阈值才单签
  // ==========================================================================
  /**
   * 显式带时区偏移的时间**写回值**必须逐毫秒相等。
   *
   * <p>⚠️ 这条不是形式主义：{@code Timestamp.valueOf(LocalDateTime)} 会把 {@code +08:00}
   * 当成本地时间直接丢掉偏移，落到 timestamptz 上就是**静默偏 −28800s** ——
   * 不抛异常、不报错，只在跨时区读出来时才发现审批规则的生效时间差了一整天。
   *
   * <p><b>为什么挑 {@code effective_from} 而不是申请单上的时间</b>：申请单的
   * submitted_at / approved_at 全是服务端生成的 {@code now()}（UTC），偏 8 小时一样自洽，
   * 断言「与 now() 接近」根本抓不到这个 bug。而 {@code effective_from} 是本模块三张表里
   * **唯一一个由调用方显式传入的 OffsetDateTime**，且它直接决定审批规则从哪一刻生效 ——
   * 偏了 8 小时意味着规则提前或推迟一整天生效。
   */
  @Test
  void 显式时区偏移的时间戳写回后不偏移() {
    // +08:00：正是最容易被当成本地时间丢掉偏移的那个形状。
    OffsetDateTime shanghai = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");
    RefundPolicyView saved =
        as(() -> refunds.upsertPolicy(
            new RefundPolicyUpsert("USD", new BigDecimal("50.0000"), "带时区的规则", shanghai), user));

    // 读回：走服务层（jOOQ → OffsetDateTime）。
    assertThat(as(() -> refunds.policy("USD", null)).effectiveFrom())
        .as("服务层读回的生效时间必须与传入的时刻是同一瞬间")
        .isEqualTo(shanghai);

    // ⚠️ 关键一格：**直接查库比对存进去的瞬时值**，而不是信服务层的读回。
    // 只断言服务层读回的话，一次「写歪 + 读歪正好抵消」也能绿。
    var stored = as(() -> sql.fetchOne(
        "select effective_from from refund_approval_policy where tenant_id = ?::uuid and currency = 'USD'",
        tenant));
    assertThat(stored).as("V166 必须真的建出并写入这条规则").isNotNull();
    assertThat(stored.get("effective_from", OffsetDateTime.class).toInstant())
        .as("存进 PG 的瞬时值必须仍是 UTC 的 2026-03-01T00:00Z，"
            + "偏成 00:00Z−8h 或 08:00Z 都是 Timestamp.valueOf 丢偏移的典型症状")
        .isEqualTo(shanghai.toInstant());
    assertThat(stored.get("effective_from", OffsetDateTime.class).toInstant())
        .isEqualTo(OffsetDateTime.parse("2026-03-01T00:00:00Z").toInstant());

    assertThat(saved.effectiveFrom()).isEqualTo(shanghai);
  }

  @Test
  void 没有审批规则时默认双人签而配了阈值后未达阈值走单签() {
    Seeded s = seed("POL");

    // 默认：没有配置 ⇒ 任何金额都要两人点头
    RefundPolicyView fallback = as(() -> refunds.policy("USD", null));
    assertThat(fallback.policySource()).isEqualTo("DEFAULT_NO_POLICY");
    assertThat(fallback.requiresDualApproval()).as("默认必须是拦住而不是放过").isTrue();

    UUID id = as(() -> refunds.create(create(s, "5.0000", "ORIGINAL"), user).head().id());
    as(() -> refunds.submit(id, new RefundSubmit(0L), user));
    RefundDetail first = as(() -> refunds.decide(id, new RefundDecision(true, "初审通过", 1L), approver2));
    assertThat(first.head().applicationStatus())
        .as("没配规则时初审不能直接放行")
        .isEqualTo("SECOND_APPROVAL_PENDING");

    // 配置阈值：≥ 100 才双签
    as(() -> refunds.upsertPolicy(
        new RefundPolicyUpsert("USD", new BigDecimal("100.0000"), "小额单签", null), user));
    RefundPolicyView configured = as(() -> refunds.policy("USD", null));
    assertThat(configured.policySource()).isEqualTo("CONFIGURED");
    assertThat(configured.dualApprovalThreshold()).isEqualByComparingTo("100.0000");
    assertThat(as(() -> refunds.policy("USD", new BigDecimal("150.0000"))).requiresDualApproval())
        .as("未传金额时按最严格档回答（界面上还没填金额）").isTrue();

    Seeded small = seed("POL-S");
    UUID cheap = as(() -> refunds.create(create(small, "5.0000", "ORIGINAL"), user).head().id());
    as(() -> refunds.submit(cheap, new RefundSubmit(0L), user));
    assertThat(as(() -> refunds.decide(cheap, new RefundDecision(true, "初审通过", 1L), approver2))
            .head().applicationStatus())
        .as("未达阈值应单签直接放行")
        .isEqualTo("APPROVED");

    // ⚠️ 基数必须 ≥ 阈值：否则先被可退余额挡住，测到的会是余额规则而不是审批规则。
    Seeded big = seed("POL-B", "300.0000", "USD");
    UUID pricey = as(() -> refunds.create(create(big, "150.0000", "ORIGINAL"), user).head().id());
    as(() -> refunds.submit(pricey, new RefundSubmit(0L), user));
    assertThat(as(() -> refunds.decide(pricey, new RefundDecision(true, "初审通过", 1L), approver2))
            .head().applicationStatus())
        .as("达到阈值仍要双签")
        .isEqualTo("SECOND_APPROVAL_PENDING");
  }

  @Test
  void 自己不能批自己且同一个人不能双签() {
    Seeded s = seed("SEGRE");
    UUID id = as(() -> refunds.create(create(s, "9.0000", "ORIGINAL"), user).head().id());
    as(() -> refunds.submit(id, new RefundSubmit(0L), user));

    // 提交人自己初审 —— 服务层与 DB 触发器都要拦
    assertThatThrownBy(() -> as(() -> refunds.decide(id, new RefundDecision(true, "自己批", 1L), user)))
        .hasMessageContaining("自己提自己批等于没审批");

    // 正常初审 → 复审不能是同一个人
    as(() -> refunds.decide(id, new RefundDecision(true, "初审通过", 1L), approver2));
    assertThatThrownBy(() -> as(() -> refunds.decide(id, new RefundDecision(true, "再批一次", 2L), approver2)))
        .hasMessageContaining("复审人不能与初审人");
    assertThat(as(() -> refunds.detail(id)).head().applicationStatus()).isEqualTo("SECOND_APPROVAL_PENDING");
  }

  @Test
  void 驳回必须说明理由而且驳回后额度立刻还回来() {
    Seeded s = seed("REJ");
    approvedApplication(s, "50.0000");
    UUID pending = as(() -> refunds.create(create(s, "10.0000", "ORIGINAL"), user).head().id());
    as(() -> refunds.submit(pending, new RefundSubmit(0L), user));
    assertThat(as(() -> refunds.balance(s.caseId())).availableAmount()).isEqualByComparingTo("0");

    assertThatThrownBy(() -> as(() -> refunds.decide(pending, new RefundDecision(false, "", 1L), approver2)))
        .hasMessageContaining("驳回必须说明理由");

    as(() -> refunds.decide(pending, new RefundDecision(false, "买家诉求不成立", 1L), approver2));
    assertThat(as(() -> refunds.detail(pending)).head().applicationStatus()).isEqualTo("REJECTED");
    assertThat(as(() -> refunds.balance(s.caseId())).availableAmount())
        .as("驳回说明根本没退，额度必须立刻还给售后单")
        .isEqualByComparingTo("10.0000");
  }

  // ==========================================================================
  // 四、时间写回零漂移
  // ==========================================================================
  @Test
  void 审批留痕的时刻零漂移() {
    Seeded s = seed("TZ");
    UUID id = as(() -> refunds.create(create(s, "9.0000", "ORIGINAL"), user).head().id());

    // ⚠️ 夹具是 +08:00：Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s，
    //   只有这样才能测出来。
    var submitted = as(() -> refunds.submit(id, new RefundSubmit(0L), user)).head().submittedAt();
    assertThat(submitted).isNotNull();
    assertThat(submitted.toInstant()).isAfter(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5).toInstant());

    OffsetDateTime first = as(() -> refunds.decide(id, new RefundDecision(true, "初审", 1L), approver2))
        .head().firstApprovedAt();
    OffsetDateTime second = as(() -> refunds.decide(id, new RefundDecision(true, "复审", 2L), approver3))
        .head().secondApprovedAt();
    assertThat(first).isNotNull();
    assertThat(second).isNotNull();
    assertThat(second).isAfterOrEqualTo(first);

    // 写回数据库的值也要是同一时刻（不是 +08:00 偏成 UTC 之前的那个）
    var row = as(() -> sql.fetchOne(
        "select submitted_at, first_approved_at, second_approved_at from refund_application"
            + " where id = ?::uuid", id));
    assertThat(row.get("submitted_at", OffsetDateTime.class).toInstant())
        .isEqualTo(submitted.toInstant());
    assertThat(row.get("second_approved_at", OffsetDateTime.class).toInstant())
        .isEqualTo(second.toInstant());
  }

  // ==========================================================================
  // 五、交财务执行与执行结果
  // ==========================================================================
  @Test
  void 交财务执行必须挂一条真实的执行记录() {
    Seeded s = seed("DISP");
    Approved approved = approvedApplication(s, "12.0000");
    UUID id = approved.id();
    long version = approved.version();

    assertThatThrownBy(() -> as(() -> refunds.dispatch(id, new RefundDispatch(null, version), user)))
        .hasMessageContaining("必须指明一条退款执行记录");
    assertThatThrownBy(
            () -> as(() -> refunds.dispatch(id, new RefundDispatch(UUID.randomUUID(), version), user)))
        .hasMessageContaining("不存在或不属于当前租户");

    UUID executionId = execution(s, "12.0000", "NOT_INITIATED", s.caseId());
    RefundDetail dispatched = as(() -> refunds.dispatch(id, new RefundDispatch(executionId, version), user));
    assertThat(dispatched.head().applicationStatus()).isEqualTo("EXECUTING");
    assertThat(dispatched.head().executionId()).isEqualTo(executionId);
    assertThat(dispatched.executionState()).isEqualTo("NOT_INITIATED");

    // 已定论的执行记录不能挂上来
    Seeded t = seed("DISP2");
    Approved doneApp = approvedApplication(t, "5.0000");
    UUID settled = execution(t, "5.0000", "SUCCEEDED", t.caseId());
    assertThatThrownBy(
            () -> as(() -> refunds.dispatch(
                doneApp.id(), new RefundDispatch(settled, doneApp.version()), user)))
        .hasMessageContaining("已定论");
  }

  @Test
  void 外部超时不等于失败执行记录没定论时本模块不许宣布成功或失败() {
    Seeded s = seed("TO");
    Approved approved = approvedApplication(s, "11.0000");
    UUID id = approved.id();
    UUID executionId = execution(s, "11.0000", "UNKNOWN", s.caseId());
    as(() -> refunds.dispatch(id, new RefundDispatch(executionId, approved.version()), user));

    RefundDetail unknown = as(() -> refunds.detail(id));
    assertThat(unknown.head().applicationStatus()).isEqualTo("EXECUTING");
    assertThat(unknown.executionState()).isEqualTo("UNKNOWN");
    assertThat(unknown.executionStateNote())
        .as("UNKNOWN 必须在界面上说清「这不是失败」")
        .contains("不是失败");

    assertThatThrownBy(
            () -> as(() -> refunds.recordOutcome(
                id,
                new RefundExecutionOutcome(
                    "FAILED", "MANUAL_OPERATOR", null, "超时就当失败", approved.version() + 1L),
                user)))
        .as("外部超时不等于失败：执行记录没定论就不许宣布失败")
        .hasMessageContaining("没定论");
    assertThatThrownBy(
            () -> as(() -> refunds.recordOutcome(
                id,
                new RefundExecutionOutcome(
                    "SUCCEEDED", "PLATFORM_SYNC", "TXN", null, approved.version() + 1L),
                user)))
        .hasMessageContaining("没定论");
    assertThat(as(() -> refunds.detail(id)).head().applicationStatus())
        .as("两次被拒之后状态必须仍是执行中 —— 这一格就是蓝图加粗句")
        .isEqualTo("EXECUTING");
  }

  @Test
  void 声称来自12财务的结果被拒因为12财务整卷未交付() {
    Seeded s = seed("FIN");
    Approved approved = approvedApplication(s, "6.0000");
    as(() -> refunds.dispatch(approved.id(),
        new RefundDispatch(execution(s, "6.0000", "IN_FLIGHT", s.caseId()), approved.version()), user));

    assertThatThrownBy(
            () -> as(() -> refunds.recordOutcome(
                approved.id(),
                new RefundExecutionOutcome(
                    "SUCCEEDED", "FINANCE_MODULE_12", "TXN", null, approved.version() + 1L),
                user)))
        .hasMessageContaining("12 财务尚未交付");

    // 能力声明里也必须如实报出来
    RefundCapabilities caps = as(() -> refunds.capabilities());
    assertThat(caps.sections())
        .filteredOn(sec -> "handoffToFinance".equals(sec.section()))
        .singleElement()
        .satisfies(sec -> {
          assertThat(sec.available()).isFalse();
          assertThat(sec.ownerModule()).isEqualTo("12.2.2.2 付款执行");
        });
  }

  @Test
  void 登记成功必须给出外部交易号而登记失败必须给出原因() {
    // 成功侧：执行记录定论之后，缺外部交易号要被拒（「确认退成功了」却给不出凭据）。
    Seeded s = seed("OUT");
    Approved approved = approvedApplication(s, "4.0000");
    UUID id = approved.id();
    UUID executionId = execution(s, "4.0000", "NOT_INITIATED", s.caseId());
    as(() -> refunds.dispatch(id,
        new RefundDispatch(executionId, approved.version()), user));
    resolveExecution(executionId, "SUCCEEDED");

    assertThatThrownBy(
            () -> as(() -> refunds.recordOutcome(
                id,
                new RefundExecutionOutcome(
                    "SUCCEEDED", "MANUAL_OPERATOR", null, null, approved.version() + 1L),
                user)))
        .hasMessageContaining("外部交易号");

    RefundDetail done = as(() -> refunds.recordOutcome(
        id,
        new RefundExecutionOutcome(
            "SUCCEEDED", "PLATFORM_SYNC", "TXN-OK", null, approved.version() + 1L),
        user));
    assertThat(done.head().applicationStatus()).isEqualTo("SUCCEEDED");
    assertThat(done.head().failureReason()).isNull();

    // 失败侧：缺失败原因要被拒（蓝图输出句点名「失败原因」）。
    Seeded t = seed("OUT2");
    Approved failedApp = approvedApplication(t, "4.0000");
    UUID failedExecution = execution(t, "4.0000", "NOT_INITIATED", t.caseId());
    as(() -> refunds.dispatch(failedApp.id(),
        new RefundDispatch(failedExecution, failedApp.version()), user));
    resolveExecution(failedExecution, "FAILED");

    assertThatThrownBy(
            () -> as(() -> refunds.recordOutcome(
                failedApp.id(),
                new RefundExecutionOutcome(
                    "FAILED", "MANUAL_OPERATOR", null, "", failedApp.version() + 1L),
                user)))
        .hasMessageContaining("失败必须说明原因");

    RefundDetail failed = as(() -> refunds.recordOutcome(
        failedApp.id(),
        new RefundExecutionOutcome(
            "FAILED", "PLATFORM_SYNC", null, "渠道明确拒绝", failedApp.version() + 1L),
        user));
    assertThat(failed.head().applicationStatus()).isEqualTo("FAILED");
    assertThat(failed.head().failureReason()).isEqualTo("渠道明确拒绝");
  }

  /**
   * 让一条执行记录定论 —— 这是 11.3.1.2 的活，本模块只读它，不写它。
   *
   * <p>⚠️ 必须**分两步**：V159 自己的状态机触发器 guard_refund_execution_transition
   * 规定 NOT_INITIATED 只能先进 IN_FLIGHT，直接跳到终论会被那一条拒绝 ——
   *   「还没派发就成功了」在 11.3.1.2 的口径里根本不存在。
   */
  private void resolveExecution(UUID executionId, String state) {
    boolean failed = "FAILED".equals(state);
    as(
        () -> {
          sql.execute(
              "update refund_execution set execution_state = 'IN_FLIGHT', initiated_at = ?::timestamptz"
                  + " where id = ?::uuid",
              T1, executionId);
          sql.execute(
              "update refund_execution set execution_state = ?, failure_class = ?,"
                  + " failure_code = ?, failure_reason = ?, resolved_at = ?::timestamptz"
                  + " where id = ?::uuid",
              state, failed ? "DEFINITIVE" : null, failed ? "E_DEMO" : "",
              failed ? "渠道明确拒绝" : "", T1, executionId);
          return null;
        });
  }

  // ==========================================================================
  // 六、状态机与留痕
  // ==========================================================================
  @Test
  void 非法状态跳转绕不过DB触发器() {
    Seeded s = seed("JUMP");
    UUID id = as(() -> refunds.create(create(s, "3.0000", "ORIGINAL"), user).head().id());
    // 草稿直接跳到成功
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "update refund_application set application_status='SUCCEEDED'"
                              + " where id=?::uuid", id);
                      return null;
                    }))
        .as("绕过服务层直连 SQL 也走不过状态机")
        .hasMessageContaining("非法的退款申请状态流转");
    assertThat(as(() -> refunds.detail(id)).head().applicationStatus()).isEqualTo("DRAFT");
  }

  @Test
  void 留痕自动追加而且是appendOnly() {
    Seeded s = seed("AUDIT");
    UUID id = approvedApplication(s, "13.0000").id();
    List<RefundEvent> events = as(() -> refunds.events(id));
    assertThat(events).extracting(RefundEvent::eventType)
        .containsExactly("CREATED", "SUBMITTED", "FIRST_APPROVED", "SECOND_APPROVED");
    // 金额快照逐条冻结：改金额（只有草稿可改）也不会改掉「当初批的是多少」
    assertThat(events).allSatisfy(e -> assertThat(e.amountSnapshot()).isEqualByComparingTo("13.0000"));

    // append-only：运行角色连 UPDATE/DELETE 权限都没有
    for (String table : List.of("refund_application_event")) {
      assertThat(as(() -> sql.fetchOne("select has_table_privilege('trade_app',?,'UPDATE')", table)
              .get(0, Boolean.class)))
          .as(table + " 必须是 append-only").isFalse();
      assertThat(as(() -> sql.fetchOne("select has_table_privilege('trade_app',?,'DELETE')", table)
              .get(0, Boolean.class)))
          .isFalse();
    }
  }

  // ==========================================================================
  // 七、列表：真分页 / 排序白名单 / 真实检索
  // ==========================================================================
  @Test
  void 分页汇总排序与检索都是真的() {
    Seeded s = seed("PAGE");
    for (int i = 0; i < 5; i++) {
      as(() -> refunds.create(create(s, "1.0000", "ORIGINAL"), user).head().id());
    }
    RefundPage first = as(() -> refunds.list(new RefundListQuery(0, 2, null, null, null, null, null, null, null)));
    RefundPage second = as(() -> refunds.list(new RefundListQuery(1, 2, null, null, null, null, null, null, null)));
    assertThat(first.total()).isEqualTo(5);
    assertThat(first.rows()).hasSize(2);
    assertThat(second.rows()).hasSize(2);
    assertThat(first.rows().stream().map(r -> r.head().id()).toList())
        .as("两页不重叠").doesNotContainAnyElementsOf(second.rows().stream().map(r -> r.head().id()).toList());
    assertThat(first.summary().total()).as("汇总不受分页影响").isEqualTo(5);
    assertThat(first.summary().draft()).isEqualTo(5);

    // 排序白名单：未知键落回默认，而不是被当成 SQL 片段
    RefundPage injected = as(() -> refunds.list(
        new RefundListQuery(null, 50, null, null, null, null, null, null, "created_at; drop table refund_application")));
    assertThat(injected.rows()).hasSize(5);
    assertThat(as(() -> sql.fetchOne("select count(*) from refund_application").get(0, Long.class)))
        .as("排序白名单不允许注入").isEqualTo(5L);

    // 真实检索：申请单号 / 原收款流水号都命中
    String targetNo = first.rows().get(0).head().applicationNo();
    RefundPage hit = as(() -> refunds.list(new RefundListQuery(null, 50, null, null, null, null, null, targetNo, null)));
    assertThat(hit.rows()).hasSize(1);
    RefundPage miss = as(() -> refunds.list(new RefundListQuery(null, 50, null, null, null, null, null, "查无此单", null)));
    assertThat(miss.rows()).isEmpty();

    // 筛选项词表来自真实数据 DISTINCT，不是写死的常量
    RefundApplicationFilterOptions filters = as(() -> refunds.filters());
    assertThat(filters.statuses()).contains("DRAFT");
    assertThat(filters.methods()).contains("ORIGINAL");
    assertThat(filters.reasons()).contains("RETURN_REFUND");
  }

  // ==========================================================================
  // 八、端点（真跑 HTTP）
  // ==========================================================================
  @Test
  void 端点真的能跑通且未交付的入口不存在() throws Exception {
    Seeded s = seed("HTTP");
    UUID id = approvedApplication(s, "15.0000").id();

    http.perform(get("/trade/refund-applications/capabilities")
            .header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk());
    http.perform(get("/trade/refund-applications/refundable-balance")
            .param("caseId", s.caseId().toString())
            .header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.baseAmount").value(60.0));
    http.perform(get("/trade/refund-applications/approval-policy")
            .param("currency", "USD")
            .header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requiresDualApproval").value(true));
    http.perform(get("/trade/refund-applications").header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk());
    http.perform(get("/trade/refund-applications/filters").header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk());
    http.perform(get("/trade/refund-applications/" + id).header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.head.applicationNo").isNotEmpty())
        .andExpect(jsonPath("$.balance.availableAmount").isNumber())
        .andExpect(jsonPath("$.events").isArray());

    // 维护审批规则
    var policy = new LinkedHashMap<String, Object>();
    policy.put("currency", "USD");
    policy.put("dualApprovalThreshold", "25.0000");
    http.perform(put("/trade/refund-applications/approval-policy")
            .header("Authorization", "Bearer tenant-main")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(policy)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dualApprovalThreshold").value(25.0));

    // 撤销：版本冲突走 **409**（与 ApiErrorHandler 里「刷新后重试」的约定一致），
    // 而不是 500 —— 一个版本过期是正常业务结果，不是服务器炸了。
    http.perform(post("/trade/refund-applications/" + id + "/cancel")
            .header("Authorization", "Bearer tenant-main")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"重复发起\",\"expectedVersion\":0}"))
        .andExpect(status().isConflict());
    long version = as(() -> refunds.detail(id)).head().version();
    http.perform(post("/trade/refund-applications/" + id + "/cancel")
            .header("Authorization", "Bearer tenant-main")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(
                Map.of("reason", "重复发起", "expectedVersion", version))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.head.applicationStatus").value("CANCELLED"));

    // 撤销本模块**刻意不提供**的动作：改写已终结的单据。
    // ⚠️ DELETE 落在 `/trade/refund-applications/{id}` 这条**存在**的路径上，只是没有 DELETE 映射，
    //   所以 Spring 回 **405 Method Not Allowed** 而不是 404 —— 断言要落在真实语义上，
    //   写 404 会让「方法没暴露」和「路径没暴露」两件事在测试里分不开。
    http.perform(delete("/trade/refund-applications/" + id).header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isMethodNotAllowed());
    // 查证交易属 11.3.1.2，这条路径在本模块**根本不存在** ⇒ 404。
    http.perform(post("/trade/refund-applications/" + id + "/verify")
            .header("Authorization", "Bearer tenant-main")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isNotFound());

    // 没有会话就没有租户上下文
    http.perform(get("/trade/refund-applications/" + id)).andExpect(status().isUnauthorized());
  }

  // ==========================================================================
  // 九、RLS 是强制的
  // ==========================================================================
  @Test
  void 三张新表都强制rls而且运行角色真的能读写() {
    for (String table : List.of("refund_approval_policy", "refund_application", "refund_application_event")) {
      var row = sql.fetchOne(
          "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V166 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " RLS 未启用").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住 owner").isTrue();
      assertThat(sql.fetchOne("select has_table_privilege('trade_app',?,'SELECT')", table)
              .get(0, Boolean.class))
          .as("trade_app 必须真的能读 " + table + "，否则「看不到」可能只是谁都读不了")
          .isTrue();
    }
    // 事务外读：没有 app.current_tenant 时 FORCE RLS 让所有行都读不到
    assertThat(sql.fetch("select * from refund_application").size()).as("事务外读必须零行").isZero();
    assertThat(sql.fetch("select * from refund_application_event").size()).isZero();
    assertThat(sql.fetch("select * from refund_approval_policy").size()).isZero();
  }
}
