package com.starcon.trade;

import static com.starcon.trade.ReturnAnalyticsModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.trade.ReturnAnalyticsModels.*;
import com.starcon.trade.RmaAuthorizationModels.*;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
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
 * 11.5.1.1 退货分析的集成测试（Testcontainers PostgreSQL 16 实跑）。
 *
 * <p>本模块的每一条断言都对着蓝图职责句里的一个承诺：
 * 退货率（按店铺/SKU/类目/原因/批次）、退货金额趋势、拒退率、处理时效、
 * 平均退款金额、原因分布，外加那条加粗句「<b>退货率必须与销售额同口径比较</b>」
 * 与输出句「<b>退货分析报表 + 维度下钻 + 异常识别</b>」。
 *
 * <p>⚠️ <b>口径类断言比数字类断言重要</b>：一个把跨币种加在一起的实现，
 * 报出来的退货率仍然是个漂亮的小数，只有断言「每币种一行、且没有跨币种合计」
 * 才拦得住。同理，分母为 0 时必须断言 {@code null} 而不是 0 ——
 * 「没有销售额」与「退货率 0%」在界面上长得一模一样，而后者会把人引向错误的结论。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReturnAnalyticsIntegrationTest {
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
  @Autowired ReturnAnalyticsService analytics;
  @Autowired RmaAuthorizationService rma;
  @Autowired ReturnReceiptService receipts;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    pg.stop();
  }

  /** 2026-03-01 08:00 +08:00 —— 换算成 UTC 是 2026-03-01 00:00Z（正好落在 UTC 日界上）。 */
  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");

  static OffsetDateTime due() {
    return OffsetDateTime.now(ZoneOffset.ofHours(8)).plusDays(14);
  }

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
          "truncate return_analysis_event, return_analysis_snapshot, return_analysis_basis,"
              + " refund_application_event, refund_execution_report, refund_application,"
              + " refund_execution,"
              + " platform_order_component, platform_order_sync_event, platform_order_sync_batch,"
              + " platform_order_sync_exception, platform_order_enum_mapping, platform_order,"
              + " return_receipt_photo, return_inventory_posting, return_receipt_line,"
              + " return_receipt_event, return_receipt,"
              + " rma_decision_event, rma_authorization_line, rma_authorization,"
              + " aftersales_reason_attribution_item, aftersales_reason_attribution_set,"
              + " aftersales_reason_category,"
              + " aftersales_case_line, aftersales_case_note, aftersales_authorization,"
              + " aftersales_service_ticket, aftersales_owner_assignment, aftersales_case,"
              + " order_fulfillment_line_progress, sales_order_line, sales_order,"
              + " sales_order_party_snapshot, order_address_snapshot cascade");
    }
  }

  <T> T as(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-a"), "trade.aftersales.write", work);
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  record Seed(
      String tag,
      UUID orderId,
      UUID lineId,
      UUID skuId,
      UUID caseId,
      UUID caseLineId,
      UUID rmaId,
      String currency,
      String shopCode) {}

  /**
   * 建一条完整的退货链路：订单 → 订单行 → 发货进度 → 平台单投影 →
   * 售后单 → 售后商品行 →（可选）退货授权单。
   *
   * @param orderAmount 已提交订单的总额（销售额分母）
   * @param returnAmount 售后单上买家申请退的金额（退货率分子）
   */
  Seed seed(
      String tag,
      String currency,
      BigDecimal orderAmount,
      BigDecimal returnAmount,
      BigDecimal qty,
      String shopCode,
      String reasonCode,
      String caseType) {
    UUID orderId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID skuId = UUID.randomUUID();
    // ⚠️ **行金额必须等于订单总额**：V99 的延迟约束触发器（guard_sales_order_totals）
    //   会核对「小计 = Σ 行小计」，对不上就在**提交那一刻**炸掉。
    //   所以单价 = 订单总额 ÷ 数量（测试里的数量恒为 4，金额都是 4 的倍数）。
    BigDecimal unitPrice = orderAmount.divide(qty, 4, java.math.RoundingMode.HALF_UP);

    as(() -> {
      sql.execute(
          "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
              + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
              + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
              + " values(?::uuid,?::uuid,?,'AMAZON',?,?,'PREPAID','SUBMITTED',?::timestamptz,"
              + "?::numeric,0,0,0,0,?::numeric,0,?::timestamptz,?::timestamptz)",
          orderId, tenant, "ORD-" + tag, "EXT-" + tag, currency, T1, orderAmount, orderAmount,
          T1, T1);
      // ⚠️ 收货地址与客户快照是**提交订单的必备输入**（V99 的延迟约束触发器会查），
      //   必须与订单在**同一个事务**里写入 —— 否则提交那一刻会报「没有收货地址快照」。
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
      sql.execute(
          "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
              + "spec_snapshot,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
              + "created_at) values(?::uuid,?::uuid,?::uuid,1,?::uuid,?,?,'黑色 / L',?::numeric,"
              + "?::numeric,0,0,?::numeric,'',?::timestamptz)",
          lineId, tenant, orderId, skuId, "SKU-" + tag, "商品" + tag, qty, unitPrice,
          unitPrice.multiply(qty), T1);
      sql.execute(
          "insert into order_fulfillment_line_progress(id,tenant_id,order_id,order_line_id,"
              + "ordered_qty,reserved_qty,picking_qty,shipped_qty,shipped_at,progress_source,"
              + "observed_at,version,created_at,updated_at)"
              + " values(?::uuid,?::uuid,?::uuid,?::uuid,?::numeric,0,0,?::numeric,?::timestamptz,"
              + "'MODULE_9_3_3_1',?::timestamptz,0,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(), tenant, orderId, lineId, qty, qty, T1, T1, T1, T1);
      if (shopCode != null) {
        sql.execute(
            "insert into platform_order(id,tenant_id,platform_code,shop_code,external_order_no,"
                + "idempotency_key,sales_order_id,platform_status,normalized_status,"
                + "platform_event_version,platform_placed_at,platform_updated_at,currency,"
                + "platform_original_amount,platform_discount_amount,platform_tax_amount,"
                + "platform_shipping_amount,platform_grand_total_amount,restored_grand_total_amount,"
                + "mapping_version,content_fingerprint,synced_at,created_at,updated_at)"
                + " values(?::uuid,?::uuid,'AMAZON',?,?,?,?::uuid,'PLACED','CONFIRMED',1,"
                + "?::timestamptz,?::timestamptz,?,?::numeric,0,0,0,?::numeric,?::numeric,"
                + "'v1',?,?::timestamptz,?::timestamptz,?::timestamptz)",
            UUID.randomUUID(), tenant, shopCode, "EXT-" + tag, "idem-" + tag, orderId, T1, T1,
            currency, orderAmount, orderAmount, orderAmount,
            "fp-" + tag, T1, T1, T1);
      }
      return null;
    });

    UUID caseId = UUID.randomUUID();
    UUID caseLineId = UUID.randomUUID();
    as(() -> {
      sql.execute(
          "insert into aftersales_case(id,tenant_id,case_no,order_id,case_type,status,reason_code,"
              + "reason_note,requested_amount,currency,requested_quantity,platform_case_no,"
              + "platform_case_url,platform_received_at,buyer_request,reply_due_at,resolve_due_at,"
              + "received_at,created_at,updated_at)"
              + " values(?::uuid,?::uuid,?,?::uuid,?,'IN_PROCESS',?,'',?::numeric,?,?::numeric,"
              + "'','',?::timestamptz,'',?::timestamptz,?::timestamptz,?::timestamptz,?::timestamptz,"
              + "?::timestamptz)",
          caseId, tenant, "CS-" + tag, orderId, caseType, reasonCode, returnAmount, currency, qty,
          T1, due(), due(), T1, T1, T1);
      sql.execute(
          "insert into aftersales_case_line(id,tenant_id,case_id,order_line_id,quantity,"
              + "requested_amount,currency,buyer_note,created_at)"
              + " values(?::uuid,?::uuid,?::uuid,?::uuid,?::numeric,?::numeric,?,'',?::timestamptz)",
          caseLineId, tenant, caseId, lineId, qty, returnAmount, currency, T1);
      return null;
    });

    return new Seed(tag, orderId, lineId, skuId, caseId, caseLineId, null, currency, shopCode);
  }

  /** 在夹具之上建一张退货授权单并做出决定（走真实的 RMA 服务，触发器全是真的）。 */
  UUID decideRma(Seed seed, String decision, BigDecimal authorizedQty) {
    UUID rmaId =
        as(() -> rma.create(
                new RmaCreate(
                    seed.caseId(), "REFUND_TO_BUYER", "TENANT_MAINTAINED", null,
                    "退货仓-" + seed.tag(), "+1-213-555-0199", "rma-" + seed.tag() + "@example.com",
                    "US", "CA", "ONTARIO", "", "M5V 2T6", "88 Yonge St", "3 楼", null, null),
                user)
            .head()
            .id());
    UUID rmaLineId = as(() -> rma.detail(rmaId).lines().get(0).id());
    if ("REJECTED".equals(decision)) {
      as(() -> rma.decide(
          rmaId,
          new RmaDecide(
              "REJECTED", null, "证据不足，不予退货",
              List.of(new RmaLineDecision(rmaLineId, BigDecimal.ZERO, null)), 0L),
          user));
    } else {
      as(() -> rma.decide(
          rmaId,
          new RmaDecide(
              decision, due(), "同意退货",
              List.of(new RmaLineDecision(rmaLineId, authorizedQty, null)), 0L),
          user));
    }
    return rmaId;
  }

  /** 登记一笔**已成功**的退款（真实落库，走 V166 的守卫）。 */
  void succeedRefund(Seed seed, BigDecimal amount, OffsetDateTime outcomeAt) {
    UUID executionId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    UUID submitter = UUID.randomUUID();
    UUID firstApprover = UUID.randomUUID();
    UUID secondApprover = UUID.randomUUID();
    as(() -> {
      sql.execute(
          "insert into refund_execution(id,tenant_id,refund_no,order_id,case_id,idempotency_key,"
              + "refund_amount,currency,execution_state,platform_sync_state,platform_refund_method,"
              + "created_at,updated_at,initiated_at,resolved_at)"
              + " values(?::uuid,?::uuid,?,?::uuid,?::uuid,?,?::numeric,?,'IN_FLIGHT','NOT_REQUESTED',"
              + "'BALANCE',?::timestamptz,?::timestamptz,?::timestamptz,null)",
          executionId, tenant, "RE-" + UUID.randomUUID().toString().substring(0, 12), seed.orderId(),
          seed.caseId(), "idem-refund-" + seed.tag() + "-" + UUID.randomUUID(), amount,
          seed.currency(), T1, T1, T1);
      sql.execute(
          "insert into refund_application(id,tenant_id,application_no,case_id,order_id,rma_id,"
              + "application_status,refund_method,refund_amount,currency,refund_reason,"
              + "refund_reason_note,original_payment_ref,platform_refund_ref,submitted_by,"
              + "submitted_at,first_approved_by,first_approved_at,second_approved_by,"
              + "second_approved_at,approval_note,dispatched_by,dispatched_at,execution_id,"
              + "outcome_source,outcome_recorded_by,outcome_recorded_at,failure_reason,created_by,"
              + "created_at,updated_at,version)"
              + " values(?::uuid,?::uuid,?,?::uuid,?::uuid,?::uuid,'SUCCEEDED','BALANCE',?::numeric,"
              + "?,'RETURN_REFUND','','','',?::uuid,?::timestamptz,?::uuid,?::timestamptz,?::uuid,"
              + "?::timestamptz,'已签',?::uuid,?::timestamptz,?::uuid,'MANUAL_OPERATOR',?::uuid,"
              + "?::timestamptz,'',?::uuid,?::timestamptz,?::timestamptz,0)",
          applicationId, tenant, "RA-" + UUID.randomUUID().toString().substring(0, 12), seed.caseId(),
          seed.orderId(), seed.rmaId(), amount, seed.currency(),
          // ⚠️ **提交人 / 初审人 / 复审人必须是三个不同的人**：
          //   V166 有两条 CHECK 钉死（提交人不能自己批、双签的两个人必须不同）。
          submitter, T1, firstApprover, T1, secondApprover, T1,
          user, T1, executionId, user, outcomeAt, user, T1, T1);
      return null;
    });
  }

  private static ReturnAnalysisWindow all() {
    return new ReturnAnalysisWindow(null, null);
  }

  private ReturnCurrencySummary currencyOf(ReturnAnalysisOverview overview, String currency) {
    return overview.currencies().stream()
        .filter(c -> c.currency().equals(currency))
        .findFirst()
        .orElseThrow(() -> new AssertionError("总览里没有币种 " + currency));
  }

  // ==========================================================================
  // 一、口径：退货率必须与销售额同币种同窗口
  // ==========================================================================

  @Test
  void 退货率等于退货金额除以销售额() {
    Seed s = seed("RATE", "USD", new BigDecimal("1000.0000"), new BigDecimal("100.0000"),
        new BigDecimal("4"), "SHOP-A", "QUALITY_DEFECT", "RETURN_REFUND");

    ReturnAnalysisOverview overview = as(() -> analytics.overview(all(), null));
    ReturnCurrencySummary usd = currencyOf(overview, "USD");

    assertThat(usd.caseCount()).isEqualTo(1);
    assertThat(usd.returnAmount()).isEqualByComparingTo("100.0000");
    assertThat(usd.salesAmount()).isEqualByComparingTo("1000.0000");
    // 100 / 1000 = 0.1 → 标度 4 的比值 0.1000（= 10%）。
    assertThat(usd.returnRate()).isEqualByComparingTo("0.1000");
    assertThat(usd.caliberSource()).isEqualTo(CaliberSource.DEFAULT.name());
  }

  @Test
  void 跨币种既不相加也不相除() {
    seed("CUR-USD", "USD", new BigDecimal("1000.0000"), new BigDecimal("100.0000"),
        new BigDecimal("4"), "SHOP-A", "QUALITY_DEFECT", "RETURN_REFUND");
    seed("CUR-EUR", "EUR", new BigDecimal("800.0000"), new BigDecimal("80.0000"),
        new BigDecimal("4"), "SHOP-B", "LOGISTICS_DAMAGE", "RETURN_REFUND");

    ReturnAnalysisOverview overview = as(() -> analytics.overview(all(), null));

    // 两个币种各自一行，各算各的率。
    assertThat(overview.currencies()).hasSize(2);
    assertThat(currencyOf(overview, "USD").returnRate()).isEqualByComparingTo("0.1000");
    assertThat(currencyOf(overview, "EUR").returnRate()).isEqualByComparingTo("0.1000");
    // ⚠️ **合计里只有计数**：跨币种的金额与比率**一个都没有** ——
    //   把 100 元 + 80 欧加成一个「退货金额」是没有意义的。
    assertThat(overview.totals().caseCount()).isEqualTo(2);
    assertThat(overview.totals().salesOrderCount()).isEqualTo(2);
  }

  @Test
  void 没有销售额时退货率是空而不是零() {
    // 只建售后单，不建订单 ⇒ 分母为 0。
    Seed s = seed("NOSALES", "USD", new BigDecimal("1000.0000"), new BigDecimal("100.0000"),
        new BigDecimal("4"), null, "QUALITY_DEFECT", "RETURN_REFUND");
    as(() -> {
      sql.execute("delete from sales_order where tenant_id = ?::uuid and id = ?::uuid",
          tenant, s.orderId());
      return null;
    });

    ReturnAnalysisOverview overview = as(() -> analytics.overview(all(), null));
    ReturnCurrencySummary usd = currencyOf(overview, "USD");
    assertThat(usd.salesAmount()).isEqualByComparingTo("0");
    // ⚠️ **null 而不是 0**：没有销售额就没有退货率，报 0 会被读成「退货率 0%，很健康」。
    assertThat(usd.returnRate()).isNull();
    assertThat(usd.note()).contains("没有已提交订单");
  }

  @Test
  void 拒绝的售后单不进分子() {
    Seed s = seed("REJ-CASE", "USD", new BigDecimal("1000.0000"), new BigDecimal("100.0000"),
        new BigDecimal("4"), "SHOP-A", "FRAUD_RETURN", "RETURN_REFUND");
    as(() -> {
      sql.execute(
          "update aftersales_case set status = 'REJECTED', resolved_at = ?::timestamptz"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          T1, tenant, s.caseId());
      return null;
    });

    ReturnAnalysisOverview overview = as(() -> analytics.overview(all(), null));
    ReturnCurrencySummary usd = currencyOf(overview, "USD");
    // 被拒的售后单没有发生退货 ⇒ 不进分子；但它仍然是已提交订单 ⇒ 分母不变。
    assertThat(usd.caseCount()).isZero();
    // ⚠️ 有销售额、零退货 ⇒ 率是 **0.0000**（不是 null）：
    //   null 的语义是「算不出来（没有销售额）」，0 的语义是「有销售额但一件没退」。
    assertThat(usd.returnRate()).isEqualByComparingTo("0.0000");
    assertThat(usd.salesAmount()).isEqualByComparingTo("1000.0000");
  }

  @Test
  void 期间过滤按utc日界且截止日含当天() {
    Seed s = seed("WINDOW", "USD", new BigDecimal("1000.0000"), new BigDecimal("100.0000"),
        new BigDecimal("4"), "SHOP-A", "QUALITY_DEFECT", "RETURN_REFUND");

    // 2026-03-01 08:00+08:00 = 2026-03-01 00:00Z —— 正好落在 UTC 日界上。
    ReturnAnalysisWindow day =
        new ReturnAnalysisWindow(LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-01"));
    assertThat(as(() -> analytics.overview(day, null)).currencies()).hasSize(1);

    // 前一天不包含它。
    ReturnAnalysisWindow prev =
        new ReturnAnalysisWindow(LocalDate.parse("2026-02-28"), LocalDate.parse("2026-02-28"));
    assertThat(as(() -> analytics.overview(prev, null)).currencies()).isEmpty();

    // 截止日**含当天**：3 月 1 日这一行覆盖 00:00Z 到 3 月 2 日 00:00Z。
    ReturnAnalysisWindow twoDays =
        new ReturnAnalysisWindow(LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-02"));
    assertThat(as(() -> analytics.overview(twoDays, null)).currencies()).hasSize(1);
  }

  // ==========================================================================
  // 二、维度下钻
  // ==========================================================================

  @Test
  void 店铺维度按平台单投影聚合且分母是同店铺销售额() {
    seed("SHOP-A", "USD", new BigDecimal("1000.0000"), new BigDecimal("100.0000"),
        new BigDecimal("4"), "SHOP-A", "QUALITY_DEFECT", "RETURN_REFUND");
    seed("SHOP-B", "USD", new BigDecimal("500.0000"), new BigDecimal("50.0000"),
        new BigDecimal("4"), "SHOP-B", "LOGISTICS_DAMAGE", "RETURN_REFUND");

    ReturnBreakdownReport report = as(() -> analytics.breakdown(all(), "SHOP", null, null));
    assertThat(report.caliber()).isEqualTo(ReturnCaliber.CASE_LEVEL.name());
    assertThat(report.rows()).hasSize(2);

    ReturnBreakdownRow a =
        report.rows().stream()
            .filter(r -> r.dimensionKey().equals("AMAZON/SHOP-A"))
            .findFirst()
            .orElseThrow();
    // ⚠️ 分母是**这个店铺自己的**销售额（1000），不是全店（1500）。
    assertThat(a.salesAmount()).isEqualByComparingTo("1000.0000");
    assertThat(a.returnAmount()).isEqualByComparingTo("100.0000");
    assertThat(a.returnRate()).isEqualByComparingTo("0.1000");
  }

  @Test
  void 没有平台单投影的订单落在未关联平台单桶里() {
    seed("NOPLATFORM", "USD", new BigDecimal("1000.0000"), new BigDecimal("100.0000"),
        new BigDecimal("4"), null, "QUALITY_DEFECT", "RETURN_REFUND");

    ReturnBreakdownReport report = as(() -> analytics.breakdown(all(), "SHOP", null, null));
    assertThat(report.rows()).hasSize(1);
    // 不静默消失。
    assertThat(report.rows().get(0).dimensionLabel()).isEqualTo("未关联平台单");
    assertThat(report.rows().get(0).dimensionKey()).isEqualTo("/");
  }

  @Test
  void sku维度是行级口径且分母是这一条sku自己的行金额() {
    seed("SKU-A", "USD", new BigDecimal("1000.0000"), new BigDecimal("100.0000"),
        new BigDecimal("4"), "SHOP-A", "QUALITY_DEFECT", "RETURN_REFUND");
    seed("SKU-B", "USD", new BigDecimal("500.0000"), new BigDecimal("100.0000"),
        new BigDecimal("4"), "SHOP-A", "QUALITY_DEFECT", "RETURN_REFUND");

    ReturnBreakdownReport report = as(() -> analytics.breakdown(all(), "SKU", null, null));
    assertThat(report.caliber()).isEqualTo(ReturnCaliber.LINE_LEVEL.name());
    assertThat(report.rows()).hasSize(2);
    ReturnBreakdownRow a =
        report.rows().stream()
            .filter(r -> r.dimensionKey().equals("SKU-SKU-A"))
            .findFirst()
            .orElseThrow();
    ReturnBreakdownRow b =
        report.rows().stream()
            .filter(r -> r.dimensionKey().equals("SKU-SKU-B"))
            .findFirst()
            .orElseThrow();
    // ⚠️ **分母是这一条 SKU 自己的行金额**，不是全店（1500）：
    //   拿全店销售额当每个 SKU 的分母，报出来的是「占比」而不是「退货率」。
    assertThat(a.salesAmount()).isEqualByComparingTo("1000.0000");
    assertThat(a.returnRate()).isEqualByComparingTo("0.1000");
    assertThat(b.salesAmount()).isEqualByComparingTo("500.0000");
    assertThat(b.returnRate()).isEqualByComparingTo("0.2000");
    assertThat(report.lineAmountCoverage()).isEqualByComparingTo("100.00");
  }

  @Test
  void 类目维度把没归类的sku落在未归类桶里() {
    seed("CAT-1", "USD", new BigDecimal("1000.0000"), new BigDecimal("100.0000"),
        new BigDecimal("4"), "SHOP-A", "QUALITY_DEFECT", "RETURN_REFUND");

    ReturnBreakdownReport report = as(() -> analytics.breakdown(all(), "CATEGORY", null, null));
    assertThat(report.rows()).hasSize(1);
    assertThat(report.rows().get(0).dimensionKey()).isEqualTo("UNMAPPED");
    assertThat(report.rows().get(0).dimensionLabel()).isEqualTo("未归类");
  }

  @Test
  void 原因维度分母是全量同期销售额() {
    seed("REASON-A", "USD", new BigDecimal("1000.0000"), new BigDecimal("100.0000"),
        new BigDecimal("4"), "SHOP-A", "QUALITY_DEFECT", "RETURN_REFUND");
    seed("REASON-B", "USD", new BigDecimal("1000.0000"), new BigDecimal("200.0000"),
        new BigDecimal("4"), "SHOP-A", "LOGISTICS_DAMAGE", "RETURN_REFUND");

    ReturnBreakdownReport report = as(() -> analytics.breakdown(all(), "REASON", null, null));
    assertThat(report.rows()).hasSize(2);
    ReturnBreakdownRow quality =
        report.rows().stream()
            .filter(r -> r.dimensionKey().equals("QUALITY_DEFECT"))
            .findFirst()
            .orElseThrow();
    // 分母是**全量**销售额（两单共 2000），不是这一条原因内部的销售额。
    assertThat(quality.salesAmount()).isEqualByComparingTo("2000.0000");
    assertThat(quality.returnRate()).isEqualByComparingTo("0.0500");
    // ⚠️ 合计里的销售额**每个币种只加一次**（按行加会乘上「原因条数」）。
    assertThat(report.totalSalesAmount()).isEqualByComparingTo("2000.0000");
    assertThat(report.totalReturnAmount()).isEqualByComparingTo("300.0000");
  }

  @Test
  void 多个币种时不给跨币种合计() {
    seed("MULTI-USD", "USD", new BigDecimal("1000.0000"), new BigDecimal("100.0000"),
        new BigDecimal("4"), "SHOP-A", "QUALITY_DEFECT", "RETURN_REFUND");
    seed("MULTI-EUR", "EUR", new BigDecimal("800.0000"), new BigDecimal("80.0000"),
        new BigDecimal("4"), "SHOP-A", "QUALITY_DEFECT", "RETURN_REFUND");

    ReturnBreakdownReport report = as(() -> analytics.breakdown(all(), "SHOP", null, null));
    assertThat(report.rows()).hasSize(2);
    // ⚠️ 跨币种**不许合计**：100 美元 + 80 欧元 = 180 是一个没有意义的数，
    //   而 180 美元 ÷ 1800 美元 = 10% 更是一个「看起来很像退货率」的假数。
    assertThat(report.totalReturnAmount()).isNull();
    assertThat(report.totalSalesAmount()).isNull();
    assertThat(report.totalReturnRate()).isNull();
    assertThat(report.note()).contains("多个币种");
    // 选定币种之后合计恢复（同一份数据、同一个维度，只是分母不再跨币种）。
    ReturnBreakdownReport usd = as(() -> analytics.breakdown(all(), "SHOP", "USD", null));
    assertThat(usd.totalReturnAmount()).isEqualByComparingTo("100.0000");
    assertThat(usd.totalSalesAmount()).isEqualByComparingTo("1000.0000");
    assertThat(usd.totalReturnRate()).isEqualByComparingTo("0.1000");
  }

  @Test
  void 批次维度是件数口径() {
    Seed s = seed("BATCH-1", "USD", new BigDecimal("1000.0000"), new BigDecimal("100.0000"),
        new BigDecimal("4"), "SHOP-A", "QUALITY_DEFECT", "RETURN_REFUND");
    UUID rmaId = decideRma(s, "APPROVED", new BigDecimal("4"));
    var draft =
        as(() -> receipts.create(
            new ReturnReceiptModels.ReturnReceiptCreate(
                rmaId, null, "CARRIER", "FedEx", "RT-BATCH-1", "外箱压损", T1,
                List.of(
                    new ReturnReceiptModels.ReturnReceiptLineDraft(
                        as(() -> rma.detail(rmaId).lines().get(0).id()),
                        "SKU-BATCH-1", "SN-" + UUID.randomUUID(), "LOT-B1",
                        new BigDecimal("2"), "DAMAGED", "OPENED_UNDAMAGED", "外箱压损")),
                List.of()),
            user));
    as(() -> {
      receipts.post(draft.head().id(),
          new ReturnReceiptModels.ReturnReceiptPost(null, draft.head().version()), user);
      return null;
    });

    ReturnBreakdownReport report = as(() -> analytics.breakdown(all(), "BATCH", null, null));
    assertThat(report.caliber()).isEqualTo(ReturnCaliber.QTY_LEVEL.name());
    assertThat(report.rows()).hasSize(1);
    ReturnBreakdownRow row = report.rows().get(0);
    assertThat(row.dimensionKey()).isEqualTo("LOT-B1");
    assertThat(row.receivedQty()).isEqualByComparingTo("2.000000");
    // 分母是关联 SKU 同期销量件数（4 件）。
    assertThat(row.salesQty()).isEqualByComparingTo("4.000000");
    assertThat(row.returnRate()).isEqualByComparingTo("0.5000");
    // 批次维度**不报金额**（一笔售后行分批到货时金额该算进哪个批次没有唯一答案）。
    assertThat(row.returnAmount()).isNull();
  }

  // ==========================================================================
  // 三、趋势
  // ==========================================================================

  @Test
  void 趋势按utc分桶且每桶自带同窗口销售额() {
    seed("TREND-1", "USD", new BigDecimal("1000.0000"), new BigDecimal("100.0000"),
        new BigDecimal("4"), "SHOP-A", "QUALITY_DEFECT", "RETURN_REFUND");

    ReturnTrendReport report = as(() -> analytics.trend(all(), "DAY", null));
    assertThat(report.granularity()).isEqualTo("DAY");
    assertThat(report.points()).hasSize(1);
    ReturnTrendPoint point = report.points().get(0);
    // 2026-03-01 08:00+08:00 = 2026-03-01 00:00Z ⇒ 落在 3 月 1 日这个桶。
    assertThat(point.bucket()).startsWith("2026-03-01");
    assertThat(point.returnAmount()).isEqualByComparingTo("100.0000");
    assertThat(point.salesAmount()).isEqualByComparingTo("1000.0000");
    assertThat(point.returnRate()).isEqualByComparingTo("0.1000");
  }

  // ==========================================================================
  // 四、拒退率 / 处理时效 / 平均退款金额
  // ==========================================================================

  @Test
  void 拒退率只把已决定的授权单算进分母() {
    Seed approved = seed("RMA-OK", "USD", new BigDecimal("1000.0000"), new BigDecimal("100.0000"),
        new BigDecimal("4"), "SHOP-A", "QUALITY_DEFECT", "RETURN_REFUND");
    decideRma(approved, "APPROVED", new BigDecimal("4"));
    Seed rejected = seed("RMA-NO", "USD", new BigDecimal("1000.0000"), new BigDecimal("100.0000"),
        new BigDecimal("4"), "SHOP-A", "FRAUD_RETURN", "RETURN_REFUND");
    decideRma(rejected, "REJECTED", BigDecimal.ZERO);
    Seed draft = seed("RMA-DRAFT", "USD", new BigDecimal("1000.0000"), new BigDecimal("100.0000"),
        new BigDecimal("4"), "SHOP-A", "BUYER_REASON", "RETURN_REFUND");
    // 草稿（还没批）不进分母。

    ReturnRejectionSummary summary = as(() -> analytics.overview(all(), null)).rejection();
    assertThat(summary.decidedCount()).isEqualTo(2);
    assertThat(summary.rejectedCount()).isEqualTo(1);
    assertThat(summary.authorizedCount()).isEqualTo(1);
    assertThat(summary.rejectionRate()).isEqualByComparingTo("0.5000");
    assertThat(summary.note()).contains("已做出决定");
    assertThat(draft.caseId()).isNotNull();
  }

  @Test
  void 处理时效带样本数且没有样本时是空() {
    assertThat(as(() -> analytics.overview(all(), null)).processing().caseSampleCount()).isZero();
    assertThat(as(() -> analytics.overview(all(), null)).processing().avgCaseResolutionHours())
        .isNull();

    Seed s = seed("PROC-1", "USD", new BigDecimal("1000.0000"), new BigDecimal("100.0000"),
        new BigDecimal("4"), "SHOP-A", "QUALITY_DEFECT", "RETURN_REFUND");
    as(() -> {
      sql.execute(
          "update aftersales_case set status = 'RESOLVED', resolved_at = ?::timestamptz"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          T1.plusHours(48), tenant, s.caseId());
      return null;
    });

    ReturnProcessingSummary processing = as(() -> analytics.overview(all(), null)).processing();
    assertThat(processing.caseSampleCount()).isEqualTo(1);
    assertThat(processing.avgCaseResolutionHours()).isEqualByComparingTo("48.00");
  }

  @Test
  void 平均退款金额只算已成功的退款() {
    Seed s = seed("REFUND-1", "USD", new BigDecimal("1000.0000"), new BigDecimal("100.0000"),
        new BigDecimal("4"), "SHOP-A", "QUALITY_DEFECT", "RETURN_REFUND");
    s = new Seed(s.tag(), s.orderId(), s.lineId(), s.skuId(), s.caseId(), s.caseLineId(),
        decideRma(s, "APPROVED", new BigDecimal("4")), s.currency(), s.shopCode());
    succeedRefund(s, new BigDecimal("80.0000"), T1.plusHours(24));

    ReturnCurrencySummary usd = currencyOf(as(() -> analytics.overview(all(), null)), "USD");
    assertThat(usd.refundCount()).isEqualTo(1);
    assertThat(usd.refundTotal()).isEqualByComparingTo("80.0000");
    assertThat(usd.avgRefundAmount()).isEqualByComparingTo("80.0000");
  }

  @Test
  void 原因分布按七类词表且归因覆盖率单独报() {
    seed("DIST-1", "USD", new BigDecimal("1000.0000"), new BigDecimal("100.0000"),
        new BigDecimal("4"), "SHOP-A", "QUALITY_DEFECT", "RETURN_REFUND");

    ReturnAnalysisOverview overview = as(() -> analytics.overview(all(), null));
    assertThat(overview.reasonDistribution()).hasSize(1);
    ReturnReasonSlice slice = overview.reasonDistribution().get(0);
    assertThat(slice.reasonCode()).isEqualTo("QUALITY_DEFECT");
    assertThat(slice.label()).isEqualTo("质量问题");
    assertThat(slice.share()).isEqualByComparingTo("100.00");
    // 没归因 ⇒ 覆盖率 0（**不是**「没有质量问题」）。
    assertThat(overview.attributedCaseCount()).isZero();
    assertThat(overview.attributionCoverage()).isEqualByComparingTo("0.00");
  }

  // ==========================================================================
  // 五、异常识别
  // ==========================================================================

  @Test
  void 异常识别按阈值报出并带上判据() {
    // 一个退货率极高的店铺 + 大量销售额兜底。
    // ⚠️ SHOP-BAD 的销售额必须**过最小基数门槛**（总体销售额的 1%），
    //   否则它会被「基数太小、可能是噪声」这一条挡掉 —— 那正是这条门槛存在的理由。
    seed("ANOM-SHOP", "USD", new BigDecimal("1000.0000"), new BigDecimal("80.0000"),
        new BigDecimal("4"), "SHOP-BAD", "QUALITY_DEFECT", "RETURN_REFUND");
    seed("ANOM-BASE", "USD", new BigDecimal("10000.0000"), new BigDecimal("100.0000"),
        new BigDecimal("4"), "SHOP-OK", "LOGISTICS_DAMAGE", "RETURN_REFUND");

    ReturnAnomalyReport report = as(() -> analytics.anomalies(all(), "USD"));
    assertThat(report.thresholds()).isNotNull();
    assertThat(report.thresholds().source()).isEqualTo(CaliberSource.DEFAULT.name());
    assertThat(report.thresholds().rateMultiplier()).isEqualByComparingTo("2.0000");
    assertThat(report.rows()).isNotEmpty();
    ReturnAnomalyRow spike =
        report.rows().stream()
            .filter(r -> r.code().equals("RETURN_RATE_SPIKE"))
            .findFirst()
            .orElseThrow();
    assertThat(spike.dimensionLabel()).contains("SHOP-BAD");
    // 判据要说清楚「为什么是异常」。
    assertThat(spike.note()).contains("倍");
    assertThat(spike.metricValue()).isNotNull();
    assertThat(spike.baselineValue()).isNotNull();
  }

  @Test
  void 阈值随租户口径走() {
    seed("THR-SHOP", "USD", new BigDecimal("1000.0000"), new BigDecimal("80.0000"),
        new BigDecimal("4"), "SHOP-BAD", "QUALITY_DEFECT", "RETURN_REFUND");
    seed("THR-BASE", "USD", new BigDecimal("10000.0000"), new BigDecimal("100.0000"),
        new BigDecimal("4"), "SHOP-OK", "LOGISTICS_DAMAGE", "RETURN_REFUND");

    as(() ->
        analytics.createBasis(
            new ReturnAnalysisBasisCreate(
                "严口径", "USD", false,
                new BigDecimal("1.2000"), new BigDecimal("0.000000"),
                new BigDecimal("20.0000"), 5L,
                new BigDecimal("3.0000"), new BigDecimal("10.000000"),
                new BigDecimal("0.600000"), 10L,
                new BigDecimal("5.0000"), LocalDate.parse("2026-01-01"), "测试用"),
            user));

    ReturnAnomalyReport report = as(() -> analytics.anomalies(all(), "USD"));
    assertThat(report.thresholds().source()).isEqualTo(CaliberSource.TENANT_BASIS.name());
    assertThat(report.thresholds().rateMultiplier()).isEqualByComparingTo("1.2000");
  }

  // ==========================================================================
  // 六、口径配置
  // ==========================================================================

  @Test
  void 含仅退款的口径会把仅退款算进分子() {
    seed("RO-A", "USD", new BigDecimal("1000.0000"), new BigDecimal("100.0000"),
        new BigDecimal("4"), "SHOP-A", "QUALITY_DEFECT", "RETURN_REFUND");
    seed("RO-B", "USD", new BigDecimal("1000.0000"), new BigDecimal("300.0000"),
        new BigDecimal("4"), "SHOP-A", "BUYER_REASON", "REFUND_ONLY");

    // 默认口径**不含**仅退款。
    ReturnAnalysisOverview before = as(() -> analytics.overview(all(), null));
    assertThat(currencyOf(before, "USD").caseCount()).isEqualTo(1);
    assertThat(currencyOf(before, "USD").returnAmount()).isEqualByComparingTo("100.0000");

    as(() ->
        analytics.createBasis(
            new ReturnAnalysisBasisCreate(
                "含仅退款", "USD", true,
                new BigDecimal("2.0000"), new BigDecimal("0.010000"),
                new BigDecimal("20.0000"), 5L,
                new BigDecimal("3.0000"), new BigDecimal("10.000000"),
                new BigDecimal("0.600000"), 10L,
                new BigDecimal("5.0000"), LocalDate.parse("2026-01-01"), "测试用"),
            user));

    ReturnAnalysisOverview after = as(() -> analytics.overview(all(), null));
    assertThat(currencyOf(after, "USD").caseCount()).isEqualTo(2);
    assertThat(currencyOf(after, "USD").returnAmount()).isEqualByComparingTo("400.0000");
    assertThat(currencyOf(after, "USD").caliberSource())
        .isEqualTo(CaliberSource.TENANT_BASIS.name());
    assertThat(currencyOf(after, "USD").includeRefundOnly()).isTrue();
  }

  @Test
  void 口径换版留痕且老版本保留() {
    as(() ->
        analytics.createBasis(
            new ReturnAnalysisBasisCreate(
                "第一版", "USD", false,
                new BigDecimal("2.0000"), new BigDecimal("0.010000"),
                new BigDecimal("20.0000"), 5L,
                new BigDecimal("3.0000"), new BigDecimal("10.000000"),
                new BigDecimal("0.600000"), 10L,
                new BigDecimal("5.0000"), LocalDate.parse("2026-01-01"), "第一版"),
            user));
    ReturnAnalysisBasisRow first = as(() -> analytics.listBasis("USD", null, 0, 20)).rows().get(0);

    ReturnAnalysisBasisRow second =
        as(() ->
            analytics.supersedeBasis(
                first.id(),
                new ReturnAnalysisBasisSupersede(
                    "第二版", "USD", true,
                    new BigDecimal("3.0000"), new BigDecimal("0.020000"),
                    new BigDecimal("25.0000"), 6L,
                    new BigDecimal("4.0000"), new BigDecimal("20.000000"),
                    new BigDecimal("0.700000"), 11L,
                    new BigDecimal("6.0000"), LocalDate.parse("2026-02-01"), "第二版",
                    "口径调整", first.version()),
                user));

    // 新版本生效、老版本保留为已换版并指向新版本。
    assertThat(second.basisStatus()).isEqualTo("ACTIVE");
    assertThat(second.rateAlertMultiplier()).isEqualByComparingTo("3.0000");
    ReturnAnalysisBasisPage page = as(() -> analytics.listBasis("USD", null, 0, 20));
    assertThat(page.rows()).hasSize(2);
    ReturnAnalysisBasisRow old =
        page.rows().stream()
            .filter(r -> r.basisNo().equals(first.basisNo()))
            .findFirst()
            .orElseThrow();
    assertThat(old.basisStatus()).isEqualTo("SUPERSEDED");
    assertThat(old.supersededBy()).isEqualTo(second.id());
    assertThat(old.supersedeReason()).isEqualTo("口径调整");

    // 换版必须留痕（V271 守卫①）。
    Integer events =
        as(() ->
            sql.fetchOne(
                    "select count(*) as c from return_analysis_event"
                        + " where tenant_id = ?::uuid and event_scope = 'BASIS'"
                        + " and event_type = 'SUPERSEDED'",
                    tenant)
                .get("c", Integer.class));
    assertThat(events).isEqualTo(1);
  }

  @Test
  void 同一币种不能有两份生效口径() {
    as(() ->
        analytics.createBasis(
            new ReturnAnalysisBasisCreate(
                "第一版", "USD", false,
                new BigDecimal("2.0000"), new BigDecimal("0.010000"),
                new BigDecimal("20.0000"), 5L,
                new BigDecimal("3.0000"), new BigDecimal("10.000000"),
                new BigDecimal("0.600000"), 10L,
                new BigDecimal("5.0000"), LocalDate.parse("2026-01-01"), "第一版"),
            user));

    assertThatThrownBy(
            () ->
                as(() ->
                    analytics.createBasis(
                        new ReturnAnalysisBasisCreate(
                            "第二版", "USD", false,
                            new BigDecimal("2.0000"), new BigDecimal("0.010000"),
                            new BigDecimal("20.0000"), 5L,
                            new BigDecimal("3.0000"), new BigDecimal("10.000000"),
                            new BigDecimal("0.600000"), 10L,
                            new BigDecimal("5.0000"), LocalDate.parse("2026-01-01"), "第二版"),
                        user)))
        .hasStackTraceContaining("已有一份生效口径");
  }

  // ==========================================================================
  // 七、快照
  // ==========================================================================

  @Test
  void 快照保存后可查且不可改写() {
    seed("SNAP-1", "USD", new BigDecimal("1000.0000"), new BigDecimal("100.0000"),
        new BigDecimal("4"), "SHOP-A", "QUALITY_DEFECT", "RETURN_REFUND");

    ReturnAnalysisSnapshotPage saved =
        as(() ->
            analytics.createSnapshot(
                new ReturnAnalysisSnapshotRequest(null, null, "USD", "SHOP"), user));
    assertThat(saved.rows()).isNotEmpty();
    ReturnAnalysisSnapshotRow overview =
        saved.rows().stream()
            .filter(r -> r.dimension().equals("OVERVIEW"))
            .findFirst()
            .orElseThrow();
    assertThat(overview.returnRate()).isEqualByComparingTo("0.1000");
    assertThat(overview.rejectedCount()).isNotNull();
    ReturnAnalysisSnapshotRow shop =
        saved.rows().stream()
            .filter(r -> r.dimension().equals("SHOP"))
            .findFirst()
            .orElseThrow();
    assertThat(shop.dimensionKey()).isEqualTo("AMAZON/SHOP-A");
    // 维度行的拒退列是**不适用**（null），不是 0。
    assertThat(shop.rejectedCount()).isNull();

    // 快照是**追加式**的：运行角色没有 UPDATE 权限。
    assertThatThrownBy(
            () ->
                as(() ->
                    sql.execute(
                        "update return_analysis_snapshot set return_rate = 0.9999"
                            + " where tenant_id = ?::uuid and snapshot_no = ?",
                        tenant, saved.rows().get(0).snapshotNo())))
        .hasStackTraceContaining("permission denied");
  }

  // ==========================================================================
  // 八、能力声明与 HTTP 层
  // ==========================================================================

  @Test
  void 能力声明逐项覆盖职责句() {
    ReturnAnalysisCapabilities capabilities = as(() -> analytics.capabilities());
    assertThat(capabilities.sections()).hasSize(7);
    assertThat(capabilities.sections())
        .extracting(ReturnAnalysisSection::key)
        .contains(
            "return-rate", "amount-trend", "rejection-rate", "processing-time", "avg-refund",
            "reason-distribution", "anomaly");
    assertThat(capabilities.caliberNote()).contains("同币种");
    assertThat(capabilities.currencyNote()).contains("按币种分组");
    assertThat(capabilities.relatedModules())
        .filteredOn(m -> m.moduleId().equals("13"))
        .singleElement()
        .satisfies(m -> assertThat(m.delivered()).isFalse());
  }

  @Test
  void http层按币种与维度筛() throws Exception {
    seed("HTTP-1", "USD", new BigDecimal("1000.0000"), new BigDecimal("100.0000"),
        new BigDecimal("4"), "SHOP-A", "QUALITY_DEFECT", "RETURN_REFUND");

    String body =
        http.perform(get("/trade/return-analysis/breakdown")
                .header("Authorization", "Bearer tenant-a")
                .param("dimension", "SHOP"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(body).contains("SHOP-A");
    assertThat(body).contains("CASE_LEVEL");

    // 维度非法 ⇒ 400（不是静默当成「全部」）。
    http.perform(get("/trade/return-analysis/breakdown")
            .header("Authorization", "Bearer tenant-a")
            .param("dimension", "NOPE"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 时间绑定写回一致() {
    Seed s = seed("TZ-1", "USD", new BigDecimal("1000.0000"), new BigDecimal("100.0000"),
        new BigDecimal("4"), "SHOP-A", "QUALITY_DEFECT", "RETURN_REFUND");
    OffsetDateTime stored =
        as(() ->
            sql.fetchOne(
                    "select received_at from aftersales_case where tenant_id = ?::uuid and id = ?::uuid",
                    tenant, s.caseId())
                .get("received_at", OffsetDateTime.class));
    // ⚠️ 断言**写回的值**：绑定错了时区不会抛异常，只会让时刻悄悄偏 8 小时。
    //   断言的是**瞬间**（timestamptz 存的就是瞬间）：渲染出来的偏移量由**会话时区**
    //   决定（这里是 +08:00），断言偏移量会得到一个与存储无关的假失败。
    assertThat(stored.toInstant()).isEqualTo(T1.toInstant());
    assertThat(stored.toInstant()).isEqualTo(java.time.Instant.parse("2026-03-01T00:00:00Z"));
  }
}
