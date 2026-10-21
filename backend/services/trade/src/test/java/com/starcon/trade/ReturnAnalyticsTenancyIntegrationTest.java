package com.starcon.trade;

import static com.starcon.trade.ReturnAnalyticsModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.trade.ReturnAnalyticsModels.*;
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
 * 11.5.1.1 退货分析的<b>多租户三断言</b>（AGENTS.md 明文要求，缺一不可）。
 *
 * <p>跑的是**实打在本模块自己的三张表上**的三条断言（return_analysis_basis /
 * return_analysis_snapshot / return_analysis_event），
 * **不是**占位探针表 —— 探针表能证明 RLS 开着，
 * 证明不了<b>本模块的报表查询本身</b>没漏租户谓词。
 *
 * <p><b>本模块尤其不能省隔离</b>：退货率与拒退率是要拿去找平台对账、
 * 跟供应商谈质量改进的<b>底牌</b> —— 一个租户能看到另一个租户
 * 「这个店铺退货率 30%、这个批次退回来 40 件」，等于拿到了对手的经营画像。
 * 而本模块的报表端点全是<b>无 id 直达的 GET</b>（一个 URL 就能拿到全量聚合），
 * 比按 id 直达更难靠路由形状挡住。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，
 * 才能真正证明归还后不会串租户 —— 池大于 1 时每次可能拿到不同连接，
 * 「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>每轮读写都新建实体</b>：否则测的是幂等而不是隔离 ——
 * 复用上一轮的记录，第二轮断言通过只能说明「同一条记录没变」。
 *
 * <p>⚠️ 身份桩用 {@code AccessService} 的 <b>spy</b>（只替换对 identity 的一次 HTTP 调用），
 * {@code run} 仍是真实实现 ⇒ RLS 租户绑定跑的是生产代码路径。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReturnAnalyticsTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID(), userB = UUID.randomUUID();
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
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    actors.put("Bearer tenant-a", actor(userA, a));
    actors.put("Bearer tenant-b", actor(userB, b));
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
    // ⚠️ **连接池固定为 1**：池大于 1 时每次可能拿到不同连接，
    // 「归还后不串租户」可能只是碰巧换了条干净连接。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @MockitoSpyBean AccessService access;
  @Autowired ReturnAnalyticsService analytics;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    pg.stop();
  }

  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");

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

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-a"), "trade.aftersales.write", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-b"), "trade.aftersales.write", work);
  }

  <T> T as(UUID uid, UUID tid, java.util.function.Supplier<T> work) {
    return access.run(actor(uid, tid), "trade.aftersales.write", work);
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  record Seeded(UUID orderId, UUID caseId, String shopCode) {}

  /**
   * 建订单 → 售后单（+ 平台单投影）。
   *
   * @param amount 已提交订单总额（销售额分母）
   * @param returned 售后单申请退货金额（退货率分子）
   */
  Seeded seed(UUID tid, UUID uid, String tag, BigDecimal amount, BigDecimal returned, String shopCode) {
    UUID orderId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID caseId = UUID.randomUUID();
    BigDecimal qty = new BigDecimal("4");
    BigDecimal unitPrice = amount.divide(qty, 4, java.math.RoundingMode.HALF_UP);

    as(uid, tid, () -> {
      sql.execute(
          "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
              + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
              + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
              + " values(?::uuid,?::uuid,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,"
              + "?::numeric,0,0,0,0,?::numeric,0,?::timestamptz,?::timestamptz)",
          orderId, tid, "ORD-" + tag, "EXT-" + tag, T1, amount, amount, T1, T1);
      sql.execute(
          "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
              + "recipient_phone,recipient_email,company_name,country_code,province,city,"
              + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
              + " values(?::uuid,?::uuid,?::uuid,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
              + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
          UUID.randomUUID(), tid, orderId, "买家-" + tag, "buyer-" + tag + "@example.com", T1);
      sql.execute(
          "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
              + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
              + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,?,'INDIVIDUAL','',?::timestamptz)",
          UUID.randomUUID(), tid, orderId, UUID.randomUUID(), "C-" + tag, "买家-" + tag, T1);
      sql.execute(
          "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
              + "spec_snapshot,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
              + "created_at) values(?::uuid,?::uuid,?::uuid,1,?::uuid,?,?,'黑色 / L',?::numeric,"
              + "?::numeric,0,0,?::numeric,'',?::timestamptz)",
          lineId, tid, orderId, UUID.randomUUID(), "SKU-" + tag, "商品" + tag, qty, unitPrice,
          amount, T1);
      sql.execute(
          "insert into platform_order(id,tenant_id,platform_code,shop_code,external_order_no,"
              + "idempotency_key,sales_order_id,platform_status,normalized_status,"
              + "platform_event_version,platform_placed_at,platform_updated_at,currency,"
              + "platform_original_amount,platform_discount_amount,platform_tax_amount,"
              + "platform_shipping_amount,platform_grand_total_amount,restored_grand_total_amount,"
              + "mapping_version,content_fingerprint,synced_at,created_at,updated_at)"
              + " values(?::uuid,?::uuid,'AMAZON',?,?,?,?::uuid,'PLACED','CONFIRMED',1,"
              + "?::timestamptz,?::timestamptz,'USD',?::numeric,0,0,0,?::numeric,?::numeric,"
              + "'v1',?,?::timestamptz,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(), tid, shopCode, "EXT-" + tag, "idem-" + tag, orderId, T1, T1,
          amount, amount, amount, "fp-" + tag, T1, T1, T1);
      sql.execute(
          "insert into aftersales_case(id,tenant_id,case_no,order_id,case_type,status,reason_code,"
              + "reason_note,requested_amount,currency,requested_quantity,platform_case_no,"
              + "platform_case_url,platform_received_at,buyer_request,reply_due_at,resolve_due_at,"
              + "received_at,created_at,updated_at)"
              + " values(?::uuid,?::uuid,?,?::uuid,'RETURN_REFUND','IN_PROCESS','QUALITY_DEFECT','',"
              + "?::numeric,'USD',?::numeric,'','',?::timestamptz,'',?::timestamptz,?::timestamptz,"
              + "?::timestamptz,?::timestamptz,?::timestamptz)",
          caseId, tid, "CS-" + tag, orderId, returned, qty, T1,
          OffsetDateTime.now(ZoneOffset.ofHours(8)).plusDays(14),
          OffsetDateTime.now(ZoneOffset.ofHours(8)).plusDays(14), T1, T1, T1);
      sql.execute(
          "insert into aftersales_case_line(id,tenant_id,case_id,order_line_id,quantity,"
              + "requested_amount,currency,buyer_note,created_at)"
              + " values(?::uuid,?::uuid,?::uuid,?::uuid,?::numeric,?::numeric,'USD','',?::timestamptz)",
          UUID.randomUUID(), tid, caseId, lineId, qty, returned, T1);
      return null;
    });
    return new Seeded(orderId, caseId, shopCode);
  }

  /** 给租户登记一份口径（口径表是本模块自己的写入所有者表）。 */
  ReturnAnalysisBasisRow basis(UUID tid, UUID uid, String name, boolean includeRefundOnly) {
    return as(uid, tid, () ->
        analytics.createBasis(
            new ReturnAnalysisBasisCreate(
                name, "USD", includeRefundOnly,
                new BigDecimal("2.0000"), new BigDecimal("0.010000"),
                new BigDecimal("20.0000"), 5L,
                new BigDecimal("3.0000"), new BigDecimal("10.000000"),
                new BigDecimal("0.600000"), 10L,
                new BigDecimal("5.0000"), LocalDate.parse("2026-01-01"), name + " 的口径"),
            uid));
  }

  private static ReturnAnalysisWindow all() {
    return new ReturnAnalysisWindow(null, null);
  }

  /**
   * 在给定租户的上下文里裸查快照表（**没有 WHERE**），返回 {@code id @ tenant_id}。
   *
   * <p>这是「FORCE RLS 真的在生效」的直接证据：RLS 若没开，这里会返回两个租户的行。
   */
  java.util.List<String> snapshotRowsOf(UUID tenant) {
    AccessService.Access found =
        actors.get("Bearer tenant-a").tenantId().equals(tenant)
            ? actors.get("Bearer tenant-a")
            : actors.get("Bearer tenant-b");
    return access.run(found, "trade.aftersales.read", () -> {
      var out = new java.util.ArrayList<String>();
      for (org.jooq.Record rec : sql.fetch("select id, tenant_id from return_analysis_snapshot")) {
        out.add(rec.get("id", UUID.class) + " @ " + rec.get("tenant_id", UUID.class));
      }
      return out;
    });
  }

  // ==========================================================================
  // 断言一：两租户互不可见
  // ==========================================================================

  @Test
  void 两租户互不可见() throws Exception {
    Seeded sa = seed(a, userA, "ISO-A", new BigDecimal("1000.0000"), new BigDecimal("100.0000"),
        "SHOP-A");
    Seeded sb = seed(b, userB, "ISO-B", new BigDecimal("800.0000"), new BigDecimal("80.0000"),
        "SHOP-B");

    // 每轮都新建实体：复用上一轮的记录，第二轮断言通过只能说明「同一条记录没变」。
    assertThat(asA(() -> analytics.overview(all(), null)).currencies()).hasSize(1);
    assertThat(asB(() -> analytics.overview(all(), null)).currencies()).hasSize(1);

    // 各自的金额与率互不相串。
    ReturnCurrencySummary ra = asA(() -> analytics.overview(all(), null)).currencies().get(0);
    ReturnCurrencySummary rb = asB(() -> analytics.overview(all(), null)).currencies().get(0);
    assertThat(ra.returnAmount()).isEqualByComparingTo("100.0000");
    assertThat(rb.returnAmount()).isEqualByComparingTo("80.0000");
    assertThat(ra.salesAmount()).isEqualByComparingTo("1000.0000");
    assertThat(rb.salesAmount()).isEqualByComparingTo("800.0000");

    // 店铺维度互不可见：A 只看得见 SHOP-A。
    ReturnBreakdownReport ba = asA(() -> analytics.breakdown(all(), "SHOP", null, null));
    ReturnBreakdownReport bb = asB(() -> analytics.breakdown(all(), "SHOP", null, null));
    assertThat(ba.rows()).extracting(ReturnBreakdownRow::dimensionLabel)
        .containsExactly("AMAZON / SHOP-A");
    assertThat(bb.rows()).extracting(ReturnBreakdownRow::dimensionLabel)
        .containsExactly("AMAZON / SHOP-B");

    // ---- 口径表：每租户各自一份，且互不可见 ----
    ReturnAnalysisBasisRow basisA = basis(a, userA, "A 的口径", false);
    assertThat(asA(() -> analytics.listBasis("USD", null, 0, 20)).rows())
        .extracting(ReturnAnalysisBasisRow::basisName)
        .containsExactly("A 的口径");
    assertThat(asB(() -> analytics.listBasis("USD", null, 0, 20)).rows()).isEmpty();

    // ⚠️ **不能替别人换版**：拿 A 的口径 id 在 B 的上下文里改，
    //   得到的是与「不存在」完全相同的错误 —— 不告诉调用方「这个 id 存在但不属于你」。
    assertThatThrownBy(
            () ->
                asB(() ->
                    analytics.supersedeBasis(
                        basisA.id(),
                        new ReturnAnalysisBasisSupersede(
                            "越权换版", "USD", false,
                            new BigDecimal("2.0000"), new BigDecimal("0.010000"),
                            new BigDecimal("20.0000"), 5L,
                            new BigDecimal("3.0000"), new BigDecimal("10.000000"),
                            new BigDecimal("0.600000"), 10L,
                            new BigDecimal("5.0000"), LocalDate.parse("2026-01-01"), "",
                            "越权换版", basisA.version()),
                        userB)))
        .hasStackTraceContaining("不存在或不属于当前租户");

    // ---- 快照表：A 的快照在 B 的列表里一条都没有 ----
    asA(() ->
        analytics.createSnapshot(
            new ReturnAnalysisSnapshotRequest(null, null, "USD", "SHOP"), userA));
    assertThat(asA(() -> analytics.listSnapshots(null, null, null, 0, 20)).rows()).isNotEmpty();
    assertThat(asB(() -> analytics.listSnapshots(null, null, null, 0, 20)).rows()).isEmpty();

    // ---- HTTP 层：两个租户各自只看见自己的数字 ----
    String bodyA =
        http.perform(get("/trade/return-analysis/overview")
                .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    String bodyB =
        http.perform(get("/trade/return-analysis/overview")
                .header("Authorization", "Bearer tenant-b"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(bodyA).contains("100.0000").doesNotContain("80.0000");
    assertThat(bodyB).contains("80.0000").doesNotContain("100.0000");

    String breakdownA =
        http.perform(get("/trade/return-analysis/breakdown")
                .header("Authorization", "Bearer tenant-a")
                .param("dimension", "SHOP"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(breakdownA).contains("SHOP-A").doesNotContain("SHOP-B");
  }

  // ==========================================================================
  // 断言二：伪造 tenant header 无效
  // ==========================================================================

  @Test
  void 伪造租户头无效() throws Exception {
    seed(a, userA, "FORGE-A", new BigDecimal("1000.0000"), new BigDecimal("100.0000"), "SHOP-A");
    seed(b, userB, "FORGE-B", new BigDecimal("800.0000"), new BigDecimal("80.0000"), "SHOP-B");
    basis(a, userA, "A 的口径", false);

    // 带着 **B 的令牌** + **伪造的 A 的 X-Tenant-Id**：读到的必须是 B 的数据。
    String overview =
        http.perform(get("/trade/return-analysis/overview")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(overview).contains("80.0000").doesNotContain("100.0000");

    // 反过来：带着 A 的令牌 + 伪造的 B 的头，读到的仍然是 A 的数据。
    String overview2 =
        http.perform(get("/trade/return-analysis/overview")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(overview2).contains("100.0000").doesNotContain("80.0000");

    // 口径表同样不认这个头：B 的令牌 + 伪造的 A 的头 ⇒ B 看不到 A 的口径。
    String basisBody =
        http.perform(get("/trade/return-analysis/basis")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(basisBody).doesNotContain("A 的口径");

    // 直接在 RLS 上验证：绑定 A 的租户之后，B 的行在库里**读不出来**，
    // 绕过服务层也一样（FORCE RLS 对表 owner 之外的角色同样生效，这里跑的是 trade_app）。
    assertThat(
            asA(() ->
                    sql.fetchOne(
                            "select count(*) as c from return_analysis_basis where basis_name = ?",
                            "A 的口径")
                        .get("c", Integer.class)))
        .isEqualTo(1);
    assertThat(
            asA(() ->
                    sql.fetchOne(
                            "select count(*) as c from return_analysis_basis"
                                + " where tenant_id = ?::uuid",
                            b)
                        .get("c", Integer.class)))
        .isZero();
    // **不带** WHERE 的裸查同样被 RLS 收窄 —— 这才是 FORCE 的意义。
    assertThat(
            asA(() ->
                    sql.fetchOne("select count(*) as c from return_analysis_basis").get("c", Integer.class)))
        .isEqualTo(1);
  }

  // ==========================================================================
  // 断言三：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 连接池归还后不串租户() throws Exception {
    // ⚠️ **每一轮都重新 seed**（新建订单 / 售后单 / 快照），
    //   而不是复用上一轮的行：复用时第二轮通过只能说明「那条记录没变」，
    //   证不了「这一轮的租户上下文是对的」。
    for (int round = 0; round < 3; round++) {
      final int r = round;
      Seeded sa =
          seed(a, userA, "POOL-A" + r, new BigDecimal("1000.0000"), new BigDecimal("100.0000"),
              "SHOP-A");
      Seeded sb =
          seed(b, userB, "POOL-B" + r, new BigDecimal("800.0000"), new BigDecimal("80.0000"),
              "SHOP-B");
      // 快照是**追加式**的 ⇒ 每轮的行数会涨，所以断言的是「**恰好 +1**」，
      // 而不是固定的行数（固定行数在第二轮就会假失败）。
      int aBefore = snapshotRowsOf(a).size();
      int bBefore = snapshotRowsOf(b).size();

      asA(() ->
          analytics.createSnapshot(
              new ReturnAnalysisSnapshotRequest(null, null, "USD", "SHOP"), userA));

      // A → B → A → B 交替，且**每次都换一批新实体**：
      // 若上一轮的租户上下文残留在连接上，B 就会读到 A 的行（或反过来）。
      // ⚠️ 每一轮都新建了售后单 ⇒ 金额是**累加**的（第 n 轮 = n × 单轮金额）。
      BigDecimal expectA = new BigDecimal("100.0000").multiply(BigDecimal.valueOf(r + 1));
      BigDecimal expectB = new BigDecimal("80.0000").multiply(BigDecimal.valueOf(r + 1));
      assertThat(asA(() -> analytics.overview(all(), null)).currencies().get(0).returnAmount())
          .isEqualByComparingTo(expectA);
      assertThat(asB(() -> analytics.overview(all(), null)).currencies().get(0).returnAmount())
          .isEqualByComparingTo(expectB);
      assertThat(asA(() -> analytics.overview(all(), null)).currencies().get(0).returnAmount())
          .isEqualByComparingTo(expectA);
      assertThat(asB(() -> analytics.overview(all(), null)).currencies().get(0).returnAmount())
          .isEqualByComparingTo(expectB);

      // 裸查（RLS 本身）在两个上下文里各自只看得见**自己那一侧**的行。
      // ⚠️ 一次保存会落**多行**（总体行 OVERVIEW + 每个维度值一行），
      //   所以断言的是「涨了」+「新增的行全部属于 A」，不是固定的行数。
      java.util.List<String> aAfter = snapshotRowsOf(a);
      assertThat(aAfter.size()).isGreaterThan(aBefore);
      assertThat(snapshotRowsOf(b)).hasSize(bBefore);
      assertThat(aAfter.subList(aBefore, aAfter.size()))
          .allMatch(row -> row.endsWith("@ " + a));

      // 写路径也一样：两次写在同一个连接上先后发生，第二次不能继承第一次的租户。
      asB(() ->
          analytics.createSnapshot(
              new ReturnAnalysisSnapshotRequest(null, null, "USD", "SHOP"), userB));
      java.util.List<String> bAfter = snapshotRowsOf(b);
      assertThat(bAfter.size()).isGreaterThan(bBefore);
      assertThat(bAfter.subList(bBefore, bAfter.size()))
          .allMatch(row -> row.endsWith("@ " + b));
      // A 那一侧**一条都没多** —— B 的写没有继承 A 的租户上下文。
      assertThat(snapshotRowsOf(a)).hasSize(aAfter.size());

      // 写路径之后紧接着读：B 看得见刚写的那些条，A **一条都看不见**（含 id 不相等）。
      assertThat(snapshotRowsOf(a)).hasSize(aAfter.size());
      assertThat(snapshotRowsOf(b)).hasSize(bAfter.size());
      assertThat(asA(() -> analytics.listSnapshots(null, null, null, 0, 20)).rows())
          .hasSize(aAfter.size());
      assertThat(asB(() -> analytics.listSnapshots(null, null, null, 0, 20)).rows())
          .hasSize(bAfter.size());
    }
  }
}
