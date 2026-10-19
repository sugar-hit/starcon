package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.trade.ProductIssueAttributionModels.*;
import java.math.BigDecimal;
import java.sql.DriverManager;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 11.1.2.2 商品问题归因的<b>多租户三断言</b>。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且**实跑在本模块自己的五张表上**
 * （product_issue_* 全族），<b>不是</b>占位探针表 —— 探针表能证明 RLS 开着，
 * 证明不了<b>本模块的查询本身</b>没漏租户谓词。
 *
 * <p><b>本模块尤其不能省隔离</b>：归因结论是要拿去和供应商索赔的<b>责任判定</b> ——
 * 一个租户能看到另一个租户「这批货判给供应商」，等于拿到了索赔谈判的底牌；
 * 而批次质量标记是「这个批次退回来多少、归到了哪些环节」的统计，
 * 跨租户读到这个等于直接知道对手供应商的退货画像。
 * 端点又大多是<b>按 id 直达</b>的 URL，猜到一个 UUID 就能读，不需要翻列表。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>每轮读写都新建实体</b>：否则测的是幂等而不是隔离 —— 复用上一轮的记录，
 * 第二轮断言通过只能说明「同一条记录没变」。
 *
 * <p>⚠️ 身份桩用 {@code AccessService} 的 <b>spy</b>（只替换对 identity 的一次 HTTP 调用），
 * {@code run} 仍是真实实现 ⇒ RLS 租户绑定跑的是生产代码路径。
 * 一个额外监听端口都不开。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductIssueAttributionTenancyIntegrationTest {
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
  @Autowired ProductIssueAttributionService attributions;
  @Autowired AftersalesCaseService cases;
  @Autowired AftersalesCaseDetailService details;
  @Autowired RmaAuthorizationService rma;
  @Autowired ReturnReceiptService receipts;
  @Autowired ProductLotTracePort lotTrace;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    pg.stop();
  }

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
              String permission = inv.getArgument(1, String.class);
              boolean allowed =
                  found.grants().stream()
                      .anyMatch(
                          g ->
                              g.permissions().contains(permission)
                                  && g.permissions()
                                      .contains(
                                          permission.substring(0, permission.lastIndexOf('.')) + ".read")
                                  && ("ALL".equals(g.scopeMode()) || "SELF".equals(g.scopeMode())));
              if (!allowed) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色没有此操作权限");
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
          "truncate product_issue_event, product_issue_photo_comparison, product_issue_photo,"
              + " product_issue_lot_flag, product_issue_attribution,"
              + " return_receipt_photo, return_inventory_posting, return_receipt_line,"
              + " return_receipt_event, return_receipt,"
              + " rma_decision_event, rma_authorization_line, rma_authorization,"
              + " aftersales_case_line, aftersales_case_note, aftersales_authorization,"
              + " aftersales_service_ticket, aftersales_owner_assignment, aftersales_case,"
              + " order_fulfillment_line_progress, sales_order cascade");
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
  // 夹具：建订单 → 售后单 → 授权 → 实收入库（归因数量上限的来源）
  // ==========================================================================

  record Fixture(UUID caseId, String skuCode, BigDecimal receivedQty) {}

  Fixture seed(UUID tid, UUID uid, String tag, String goodsCondition) throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    BigDecimal qty = new BigDecimal("4");
    BigDecimal unitPrice = new BigDecimal("5.0000");
    BigDecimal amount = unitPrice.multiply(qty);

    as(uid, tid, () -> {
      sql.execute(
          "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
              + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
              + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
              + " values(?,?,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,?::numeric,"
              + "0,0,0,0,?::numeric,0,?::timestamptz,?::timestamptz)",
          orderId, tid, "ORD-" + tag, "EXT-" + tag, T1, amount, amount, T1, T1);
      sql.execute(
          "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
              + "spec_snapshot,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
              + "created_at) values(?,?::uuid,?::uuid,1,?,?,?,'黑色 / L',?::numeric,?::numeric,"
              + "0,0,?::numeric,'',?::timestamptz)",
          lineId, tid, orderId, UUID.randomUUID(), "SKU-" + tag, "商品" + tag,
          qty, unitPrice, amount, T1);
      sql.execute(
          "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
              + "recipient_phone,recipient_email,company_name,country_code,province,city,"
              + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
              + " values(?,?::uuid,?::uuid,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
              + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
          UUID.randomUUID(), tid, orderId, "买家-" + tag, "buyer-" + tag + "@example.com", T1);
      sql.execute(
          "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
              + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
              + " values(?,?::uuid,?::uuid,?,?,?,'INDIVIDUAL','',?::timestamptz)",
          UUID.randomUUID(), tid, orderId, UUID.randomUUID(), "C-" + tag, "买家-" + tag, T1);
      sql.execute(
          "insert into order_fulfillment_line_progress(id,tenant_id,order_id,order_line_id,"
              + "ordered_qty,reserved_qty,picking_qty,shipped_qty,shipped_at,progress_source,"
              + "observed_at,version,created_at,updated_at)"
              + " values(?,?::uuid,?::uuid,?::uuid,?::numeric,0,0,?::numeric,?::timestamptz,"
              + "'MODULE_9_3_3_1',?::timestamptz,0,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(), tid, orderId, lineId, qty, qty, T1, T1, T1, T1);
      return null;
    });

    UUID caseId =
        as(uid, tid, () -> cases.create(
                new AftersalesCaseModels.AftersalesCaseCreate(
                    orderId, "RETURN_REFUND", "QUALITY_DEFECT", "外箱压损",
                    amount, "USD", qty, "PLAT-" + tag, null, null,
                    "买家诉求-" + tag, T1, null, T1,
                    null, null, null, "TENANT_MAINTAINED", null, T1),
                uid)
            .id());
    as(uid, tid, () -> details.addLine(
            caseId,
            new AftersalesCaseDetailModels.AftersalesCaseLineCreate(lineId, qty, amount, "USD", "第 1 件"),
            uid));

    UUID rmaId =
        as(uid, tid, () -> rma.create(
                new RmaAuthorizationModels.RmaCreate(
                    caseId, "REFUND_TO_BUYER", "TENANT_MAINTAINED", null,
                    "退货仓-" + tag, "+1-213-555-0199", "rma-" + tag + "@example.com",
                    "US", "CA", "ONTARIO", "", "M5V 2T6", "88 Yonge St", "3 楼", null, null),
                uid)
            .head()
            .id());
    UUID rmaLineId = as(uid, tid, () -> rma.detail(rmaId)).lines().get(0).id();
    as(uid, tid, () -> rma.decide(
        rmaId,
        new RmaAuthorizationModels.RmaDecide(
            "PARTIALLY_APPROVED", due(), "外包装压损，只退部分",
            List.of(new RmaAuthorizationModels.RmaLineDecision(rmaLineId, new BigDecimal("3"), null)),
            0L),
        uid));

    var draft =
        as(uid, tid, () -> receipts.create(
            new ReturnReceiptModels.ReturnReceiptCreate(
                rmaId, null, "CARRIER", "FedEx", "RT-" + tag, "外箱一角压瘪", T1,
                List.of(
                    new ReturnReceiptModels.ReturnReceiptLineDraft(
                        rmaLineId, "SKU-X", "SN-" + UUID.randomUUID(), "LOT-" + tag,
                        new BigDecimal("2"), "DAMAGED", goodsCondition, "外箱压损")),
                List.of()),
            uid));
    as(uid, tid, () -> receipts.post(draft.head().id(),
        new ReturnReceiptModels.ReturnReceiptPost(null, draft.head().version()), uid));

    return new Fixture(caseId, "SKU-" + tag, new BigDecimal("2.000000"));
  }

  /**
   * 建一张归因单。
   *
   * <p>⚠️ 刻意**不传 lotId**：批次要走 warehouse 的 HTTP 端口，而本测试刻意**不开**任何
   * 额外监听端口（照抄 11.2.3.1 租户测试的同一决定：JDK 内置 HttpServer 靠端口 0 现分配，
   * 全量并发下偶发端口被抢占 → 503 的已知 flake）。
   * 跨服务读的真实路径由 {@code ProductLotTracePortIntegrationTest} 单独覆盖。
   */
  UUID open(UUID tid, UUID uid, UUID caseId, BigDecimal qty) {
    return as(uid, tid, () -> attributions.create(
            new AttributionCreate(
                caseId, null, null, null, null, null, null,
                null, "SKU-T", "商品T", qty),
            uid,
            "Bearer tenant-a")
        .row()
        .id());
  }

  /**
   * 直接把批次 id 挂到归因单上。
   *
   * <p>⚠️ 刻意**绕过服务层直写**：批次真值在 warehouse 独立库，建单时本该经
   * {@code ProductLotTracePort} 真查一次快照。本测试断言的是**租户隔离**，
   * 不该为了它去起一个 HTTP 桩端口（照抄 11.2.3.1 租户测试的同一决定：JDK 内置 HttpServer
   * 靠端口 0 现分配，全量并发下偶发端口被抢占 → 503 的已知 flake）。
   * 真实跨服务读由 {@code ProductLotTracePortIntegrationTest} 单独覆盖。
   */
  void attachLot(UUID tenant, UUID attributionId, UUID lotId, String lotCode) {
    as(tenant == a ? userA : userB, tenant, () -> {
      sql.execute(
          "update product_issue_attribution set lot_id = ?::uuid, lot_code = ?"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          lotId, lotCode, tenant, attributionId);
      return null;
    });
  }

  private static ProductIssueAttributionService.AttributionListQuery query() {
    return new ProductIssueAttributionService.AttributionListQuery(
        0, 20, null, null, null, null, null, null);
  }

  // ==========================================================================
  // 断言一：两租户互不可见
  // ==========================================================================

  @Test
  void 两租户互不可见() throws Exception {
    Fixture fa = seed(a, userA, "ISO-A", "OPENED_UNDAMAGED");
    Fixture fb = seed(b, userB, "ISO-B", "OPENED_UNDAMAGED");

    // 每轮都新建实体：复用上一轮的记录，第二轮断言通过只能说明「同一条记录没变」。
    UUID ia = open(a, userA, fa.caseId(), new BigDecimal("1.000000"));
    UUID ib = open(b, userB, fb.caseId(), new BigDecimal("1.000000"));

    // ---- 列表互不可见（列表页 + 汇总口径）----
    assertThat(asA(() -> attributions.listAttributions(query())).total()).isEqualTo(1);
    assertThat(asA(() -> attributions.listAttributions(query())).rows())
        .extracting(AttributionRow::id).containsExactly(ia);
    assertThat(asB(() -> attributions.listAttributions(query())).rows())
        .extracting(AttributionRow::id).containsExactly(ib);

    // 汇总口径也各自独立：A 看不到 B 的那一条。
    assertThat(asA(() -> attributions.listAttributions(query())).summary().total()).isEqualTo(1);
    assertThat(asB(() -> attributions.listAttributions(query())).summary().total()).isEqualTo(1);

    // ⚠️ **按 id 直达也不行**：A 拿着 B 的归因单 id 读，
    //   得到的是与「不存在」完全相同的错误（不告诉调用方「这个 ID 存在但不属于你」）。
    assertThatThrownBy(() -> asA(() -> attributions.detail(ib)))
        .hasStackTraceContaining("不存在或不属于当前租户");
    assertThatThrownBy(() -> asA(() -> attributions.statusOf(ib)))
        .hasStackTraceContaining("不存在或不属于当前租户");

    // 判定入口也拦住：不能替别人下责任结论。
    assertThatThrownBy(() -> asA(() -> attributions.attribute(
        ib, new AttributionConclude("PRODUCTION", "越权归因", "越权建议"), userA)))
        .hasStackTraceContaining("不存在或不属于当前租户");

    // 批次质量标记同样互不可见：给两张单各挂一个批次并出归因结论，
    // 各自只看得见**自己那个批次**的标记行。
    UUID lotA = UUID.randomUUID(), lotB = UUID.randomUUID();
    attachLot(a, ia, lotA, "LOT-ISO-A");
    attachLot(b, ib, lotB, "LOT-ISO-B");
    asA(() -> attributions.attribute(
        ia, new AttributionConclude("PRODUCTION", "A 租户判生产责任", "A 的建议"), userA));
    asB(() -> attributions.attribute(
        ib, new AttributionConclude("PRODUCTION", "B 租户判生产责任", "B 的建议"), userB));

    assertThat(asA(() -> attributions.listLotFlags(null, null, null, 0, 20)).rows())
        .extracting(LotFlagRow::lotCode).containsExactly("LOT-ISO-A");
    assertThat(asB(() -> attributions.listLotFlags(null, null, null, 0, 20)).rows())
        .extracting(LotFlagRow::lotCode).containsExactly("LOT-ISO-B");
    // 按 id 直达也不行：A 拿着 B 的批次 id 读，拿到的与「没有标记」完全相同。
    assertThatThrownBy(() -> asA(() -> attributions.lotFlag(lotB)))
        .hasStackTraceContaining("该批次还没有质量标记");

    // HTTP 层同样如此（按 id 直达的 URL 是最容易被猜到的那种）。
    http.perform(get("/trade/product-attribution/attributions/{id}", ib)
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().is4xxClientError());
  }

  // ==========================================================================
  // 断言二：伪造 tenant header 无效
  // ==========================================================================

  @Test
  void 伪造租户头无效() throws Exception {
    Fixture fa = seed(a, userA, "FORGE-A", "OPENED_UNDAMAGED");
    Fixture fb = seed(b, userB, "FORGE-B", "OPENED_UNDAMAGED");
    UUID ia = open(a, userA, fa.caseId(), new BigDecimal("1.000000"));
    UUID ib = open(b, userB, fb.caseId(), new BigDecimal("1.000000"));

    // 带着 **B 的令牌** + **伪造的 A 的 X-Tenant-Id**：读到的必须是 B 的数据，
    // 不是 A 的 —— 头改不动 identity 解析出来的可信租户。
    String page =
        http.perform(get("/trade/product-attribution/attributions")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(page).contains(ib.toString()).doesNotContain(ia.toString());

    // 反过来：带着 A 的令牌 + 伪造的 B 的头，读到的仍然是 A 的数据。
    String page2 =
        http.perform(get("/trade/product-attribution/attributions")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(page2).contains(ia.toString()).doesNotContain(ib.toString());

    // 直接在 RLS 上验证：绑定 A 的租户之后，B 的行在库里**读不出来**，
    // 绕过服务层也一样（FORCE RLS 对表 owner 之外的角色同样生效，这里跑的是 trade_app）。
    assertThat(asA(() -> sql.fetchOne(
            "select count(*) from product_issue_attribution where tenant_id=?::uuid and id=?::uuid",
            b, ib))
        .get(0, Long.class)).as("RLS 之下看不到别的租户的行").isZero();
    // 直接查不带 tenant_id 谓词也一样读不到别的租户的行。
    assertThat(asA(() -> sql.fetchOne(
            "select count(*) from product_issue_attribution where id=?::uuid", ib))
        .get(0, Long.class)).as("不带租户谓词也读不到别人的行").isZero();
  }

  // ==========================================================================
  // 断言三：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 连接池归还后不串租户() throws Exception {
    Fixture fa = seed(a, userA, "POOL-A", "OPENED_UNDAMAGED");
    Fixture fb = seed(b, userB, "POOL-B", "OPENED_UNDAMAGED");
    UUID ia = open(a, userA, fa.caseId(), new BigDecimal("1.000000"));
    UUID ib = open(b, userB, fb.caseId(), new BigDecimal("1.000000"));

    // 交替借还：同一张归因单反复以两个租户的身份读，读到的永远是**当前绑定**的租户。
    for (int i = 0; i < 3; i++) {
      assertThat(asA(() -> attributions.detail(ia)).row().skuCode()).isEqualTo("SKU-T");
      assertThat(asB(() -> attributions.detail(ib)).row().skuCode()).isEqualTo("SKU-T");
      // 归还之后再读另一个租户的 id：仍然读不到。
      assertThatThrownBy(() -> asA(() -> attributions.detail(ib)))
          .hasStackTraceContaining("不存在或不属于当前租户");
      assertThatThrownBy(() -> asB(() -> attributions.detail(ia)))
          .hasStackTraceContaining("不存在或不属于当前租户");
    }

    // 归因也在交替中完成，两个租户的结论互不污染。
    asA(() -> attributions.attribute(
        ia, new AttributionConclude("TRANSPORT", "A 租户判运输责任", "A 的改进建议"), userA));
    asB(() -> attributions.attribute(
        ib, new AttributionConclude("DESCRIPTION", "B 租户判描述不符", "B 的改进建议"), userB));

    var da = asA(() -> attributions.detail(ia));
    var db = asB(() -> attributions.detail(ib));
    assertThat(da.row().attributedStage()).isEqualTo("TRANSPORT");
    assertThat(db.row().attributedStage()).isEqualTo("DESCRIPTION");
    assertThat(da.row().attributionNote()).isEqualTo("A 租户判运输责任");
    assertThat(db.row().attributionNote()).isEqualTo("B 租户判描述不符");

    // 留痕也各自独立：交替读回来，A 只看得到自己那两条事件。
    assertThat(asA(() -> attributions.detail(ia)).events())
        .extracting(AttributionEventRow::eventNote)
        .allMatch(n -> !n.contains("B 租户"));
  }

  // ==========================================================================
  // 附：伪造租户头不能写
  // ==========================================================================

  @Test
  void 伪造租户头不能写() throws Exception {
    Fixture fa = seed(a, userA, "WRITE-A", "OPENED_UNDAMAGED");
    Fixture fb = seed(b, userB, "WRITE-B", "OPENED_UNDAMAGED");
    UUID ia = open(a, userA, fa.caseId(), new BigDecimal("1.000000"));
    UUID ib = open(b, userB, fb.caseId(), new BigDecimal("1.000000"));

    // B 的令牌 + 伪造的 A 的头去**写** A 的归因单：写不进 A 的行。
    String body = "{\"attributedStage\":\"PRODUCTION\",\"attributionNote\":\"越权归因\","
        + "\"improvementSuggestion\":\"越权建议\"}";
    http.perform(MockMvcRequestBuilders.post(
            "/trade/product-attribution/attributions/{id}/attribute", ia)
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().is4xxClientError());

    // A 的归因单仍然是草稿。
    assertThat(asA(() -> attributions.detail(ia)).row().status()).isEqualTo("DRAFT");

    // 反过来，A 也不能替 B 归因。
    http.perform(MockMvcRequestBuilders.post(
            "/trade/product-attribution/attributions/{id}/attribute", ib)
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().is4xxClientError());
    assertThat(asB(() -> attributions.detail(ib)).row().status()).isEqualTo("DRAFT");
  }
}
