package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.starcon.trade.RmaAuthorizationModels.*;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 11.2.1.1 退货授权单。
 *
 * <p>跑的是蓝图职责句的五块与那条加粗句：
 *
 * <blockquote>
 * 职责：审批退货申请：确认可退数量（原订单行已退/已发数量约束）、退货地址、买家回寄物流、目标处理方式；部分退货需说明理由。
 * 输出：退货授权单（授权/部分授权/拒绝）+ 拒绝理由。
 * <b>授权不等于已收到</b>——授权后货可能在路上或根本没寄回，需要超时未寄自动关闭。
 * </blockquote>
 *
 * <p><b>本类最看重的六组断言</b>：
 *
 * <ol>
 *   <li><b>可退量上限真的被约束</b>：已发为 0、没有履约投影、别的单已占用 —— 三种情况都批不出去，
 *       而且<b>绕过服务层直连 SQL 也批不出去</b>（DB 触发器那一道）。</li>
 *   <li><b>时间写回零漂移</b>：夹具刻意用 +08:00 非 UTC 偏移，
 *       {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s，只有这样才能测出来。</li>
 *   <li><b>「授权不等于已收到」</b>：本模块没有 RECEIVED 状态，
 *       {@code received} 恒 false；已退数量恒 null 且标注 11.2.2.2 未交付（不填 0 冒充）。</li>
 *   <li><b>超时未寄自动关闭</b>：判据只看时限与在途状态，撤销 / 拒绝的单不再命中，
 *       关闭后可退量立刻还给订单行。</li>
 *   <li><b>留痕是 append-only</b>：运行角色对 rma_decision_event 连 UPDATE/DELETE 权限都没有。</li>
 *   <li><b>分页 / 排序 / 检索都是真的</b>：两页不重叠、汇总不受分页、排序白名单拒绝任意表达式。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class RmaAuthorizationIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID other = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
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

  @Autowired RmaAuthorizationService rma;
  @Autowired AftersalesCaseService cases;
  @Autowired AftersalesCaseDetailService details;
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
  static final OffsetDateTime T2 = OffsetDateTime.parse("2026-03-02T09:30:00+08:00");

  @BeforeEach
  void clean() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate rma_decision_event, rma_authorization_line, rma_authorization,"
              + " aftersales_case_line, aftersales_message, aftersales_attachment,"
              + " aftersales_case_note, aftersales_authorization, aftersales_service_ticket,"
              + " aftersales_owner_assignment, aftersales_case,"
              + " order_fulfillment_line_progress, sales_order cascade");
    }
  }

  /**
   * 多条夹具语句**放在同一个事务**里跑（迁移角色直连）。
   *
   * <p>存在的理由：V99 的头行汇总不变式是**延迟约束**，只在提交时判定。
   * 逐条 {@code superSql} 是自动提交的，插完明细行再改头的那一瞬，头还是旧值，
   * 第一条语句提交时就已经失败 —— 报的还是「头与行两个答案」这种和操作对不上的错。
   */
  void superTx(String... statements) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      c.setAutoCommit(false);
      try (var s = c.createStatement()) {
        for (String statement : statements) {
          s.executeUpdate(statement);
        }
      }
      c.commit();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  <T> T asTenant(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-main"), "trade.aftersales.write", work);
  }

  /**
   * 夹具专用的<b>迁移角色</b>直连。
   *
   * <p>只用来造「别的车道拥有的表被改坏」这类前置条件（改售后单类型 / 抬高申请数量）：
   * {@code trade_app} 对 aftersales_case 只有 SELECT，<b>刻意</b>不给 UPDATE ——
   * 那张表是 11.1.1.1 的写入所有权，运行角色能改它就等于这条边界形同虚设。
   * 所以造这类前置条件只能走迁移角色，跟生产改数据要走运维通道是同一件事。
   */
  void superSql(String sql, Object... args) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var p = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        p.setObject(i + 1, args[i]);
      }
      p.executeUpdate();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ================================================================ 夹具

  /**
   * 一张真实订单（带商品行 + 地址 + 客户快照）+ 一张真实售后单 + 若干售后商品行。
   *
   * @param shipped 已发数量；{@code null} 表示**不写**履约进度投影（模拟「9.3.3.1 从没写过」）
   */
  record Fixture(UUID caseId, UUID orderId, UUID lineId, List<UUID> caseLineIds) {}

  Fixture seed(String orderNo, BigDecimal orderedQty, BigDecimal shipped, BigDecimal caseQty) {
    UUID orderId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    BigDecimal unitPrice = new BigDecimal("5.0000");
    BigDecimal lineAmount = unitPrice.multiply(orderedQty);

    asTenant(
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?,?,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,?::numeric,0,0,"
                  + "0,0,?::numeric,0,?::timestamptz,?::timestamptz)",
              orderId, tenant, orderNo, "EXT-" + orderNo, T1, lineAmount, lineAmount, T1, T1);
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "spec_snapshot,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
                  + "created_at) values(?,?::uuid,?::uuid,1,?,?,?,'黑色 / L',?::numeric,"
                  + "?::numeric,0,0,?::numeric,'',?::timestamptz)",
              lineId, tenant, orderId, UUID.randomUUID(), "SKU-" + orderNo, "商品" + orderNo,
              orderedQty, unitPrice, lineAmount, T1);
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
                  + "recipient_phone,recipient_email,company_name,country_code,province,city,"
                  + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
                  + " values(?,?::uuid,?::uuid,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
                  + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, "买家" + orderNo, "buyer@example.com", T1);
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?,?::uuid,?::uuid,?,?,?,'INDIVIDUAL','',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), "C-" + orderNo,
              "买家" + orderNo, T1);
          if (shipped != null) {
            // ⚠️ reserved+picking+shipped <= ordered 是 V119 的 CHECK，写夹具时必须给对。
            sql.execute(
                "insert into order_fulfillment_line_progress(id,tenant_id,order_id,order_line_id,"
                    + "ordered_qty,reserved_qty,picking_qty,shipped_qty,shipped_at,progress_source,"
                    + "observed_at,version,created_at,updated_at)"
                    + " values(?,?::uuid,?::uuid,?::uuid,?::numeric,0,0,?::numeric,"
                    + "case when ?::numeric > 0 then ?::timestamptz else null end,"
                    + "'MODULE_9_3_3_1',?::timestamptz,0,?::timestamptz,?::timestamptz)",
                UUID.randomUUID(), tenant, orderId, lineId, orderedQty, shipped, shipped, T1, T1, T1, T1);
          }
          return null;
        });

    UUID caseId =
        asTenant(
            () ->
                cases.create(
                        new AftersalesCaseModels.AftersalesCaseCreate(
                            orderId, "RETURN_REFUND", "QUALITY_DEFECT", "外箱完好但里面碎了",
                            lineAmount, "USD", caseQty, "PLAT-" + orderNo, null, null,
                            "买家要求退这一件", T1, null, T1,
                            user.toString(), "客服小林", "售后专员", "TENANT_MAINTAINED", null, T1),
                        user)
                    .id());

    UUID caseLineId =
        asTenant(
            () ->
                details.addLine(
                        caseId,
                        new AftersalesCaseDetailModels.AftersalesCaseLineCreate(
                            lineId, caseQty, lineAmount, "USD", "第 1 件碎了"),
                        user)
                    .id());

    return new Fixture(caseId, orderId, lineId, List.of(caseLineId));
  }

  /** 一个建好的退货授权单草稿。 */
  RmaDetail draft(Fixture f) {
    return asTenant(
        () ->
            rma.create(
                new RmaCreate(
                    f.caseId(), "REFUND_TO_BUYER", "TENANT_MAINTAINED", null,
                    "退货仓管理员", "+1-213-555-0199", "rma@example.com", "US", "CA", "ONTARIO",
                    "", "M5V 2T6", "88 Yonge St", "3 楼", null, null),
                user));
  }

  private static List<RmaLineDecision> authorizeAll(RmaDetail d, BigDecimal qty) {
    return d.lines().stream()
        .map(l -> new RmaLineDecision(l.id(), qty, null))
        .toList();
  }

  private OffsetDateTime plusDays(long days) {
    return OffsetDateTime.now(ZoneOffset.UTC).plusDays(days);
  }

  // ================================================================ 建单

  @Test
  void 建单落库且默认取全部售后商品行() {
    var f = seed("MO-1", new BigDecimal("3"), new BigDecimal("3"), new BigDecimal("2"));
    var d = draft(f);

    assertThat(d.head().rmaNo()).startsWith("RMA");
    assertThat(d.head().rmaStatus()).isEqualTo("DRAFT");
    assertThat(d.head().decision()).isEqualTo("PENDING");
    assertThat(d.lines()).hasSize(1);
    // 建单不是审批：授权量恒为 0。
    assertThat(d.lines().get(0).authorizedQty()).isEqualByComparingTo("0");
    assertThat(d.lines().get(0).requestedQty()).isEqualByComparingTo("2");
    // SKU / 单价**实时读订单行**，不是复制来的。
    assertThat(d.lines().get(0).skuCode()).isEqualTo("SKU-MO-1");
    assertThat(d.lines().get(0).unitPrice()).isEqualTo("5.0000");
    // 建单那一刻就写下第一条留痕。
    assertThat(d.events()).hasSize(1);
    assertThat(d.events().get(0).eventType()).isEqualTo("CREATED");
    assertThat(d.events().get(0).toStatus()).isEqualTo("DRAFT");
  }

  @Test
  void 非退货退款类不能发起退货授权() {
    var f = seed("MO-2", new BigDecimal("3"), new BigDecimal("3"), new BigDecimal("2"));
    // 换货类：换货授权属 11.3.2.1，不是本模块。
    superSql("update aftersales_case set case_type='EXCHANGE' where tenant_id=?::uuid and id=?::uuid",
        tenant, f.caseId());
    assertThatThrownBy(() -> draft(f))
        .hasMessageContaining("只有 RETURN_REFUND");
  }

  @Test
  void 结案售后单不能发起退货授权() {
    var f = seed("MO-3", new BigDecimal("3"), new BigDecimal("3"), new BigDecimal("2"));
    superSql(
        "update aftersales_case set status='RESOLVED', resolved_at=?::timestamptz"
            + " where tenant_id=?::uuid and id=?::uuid",
        T1, tenant, f.caseId());
    assertThatThrownBy(() -> draft(f)).hasMessageContaining("已结案");
  }

  @Test
  void 退货地址必填且国家码必须是两位大写字母() {
    var f = seed("MO-4", new BigDecimal("3"), new BigDecimal("3"), new BigDecimal("2"));
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        rma.create(
                            new RmaCreate(f.caseId(), "REFUND_TO_BUYER", "TENANT_MAINTAINED", null,
                                "", "+1", null, "US", null, null, null, null, "", null, null, null),
                            user)))
        .hasMessageContaining("收货人不能为空");
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        rma.create(
                            new RmaCreate(f.caseId(), "REFUND_TO_BUYER", "TENANT_MAINTAINED", null,
                                "退货仓", "+1", null, "USA", null, null, null, null, "", null, null, null),
                            user)))
        .hasMessageContaining("ISO 3166");
  }

  @Test
  void 地址来源写成MODULE必须指明来源段() {
    var f = seed("MO-5", new BigDecimal("3"), new BigDecimal("3"), new BigDecimal("2"));
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        rma.create(
                            new RmaCreate(f.caseId(), "REFUND_TO_BUYER",
                                "MODULE_3_6_3_ADDRESS_BOOK", null,
                                "退货仓", "+1", null, "US", null, null, null, null, "地址", null, null, null),
                            user)))
        .hasMessageContaining("sourceReference");
  }

  // ================================================================ 可退量

  @Test
  void 可退量取订购与已发的较小者() {
    var f = seed("MO-6", new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("5"));
    var report = asTenant(() -> rma.returnableQuantities(f.caseId()));

    assertThat(report.lines()).hasSize(1);
    var line = report.lines().get(0);
    assertThat(line.orderedQty()).isEqualByComparingTo("5");
    assertThat(line.shippedQty()).isEqualByComparingTo("3");
    // min(5, 3) = 3。
    assertThat(line.returnableQty()).isEqualByComparingTo("3");
    assertThat(line.blockingReasons()).isEmpty();
  }

  @Test
  void 没有履约进度投影时已发按零处理并说清原因() {
    var f = seed("MO-7", new BigDecimal("5"), null, new BigDecimal("5"));
    var line = asTenant(() -> rma.returnableQuantities(f.caseId())).lines().get(0);

    assertThat(line.shippedQty()).isNull();
    assertThat(line.shippedQuantitySource()).isEqualTo("NO_FULFILLMENT_PROGRESS");
    assertThat(line.returnableQty()).isEqualByComparingTo("0");
    assertThat(line.blockingReasons()).anyMatch(s -> s.contains("没有履约进度投影"));
  }

  @Test
  void 已发为零不能退() {
    var f = seed("MO-8", new BigDecimal("5"), new BigDecimal("0"), new BigDecimal("5"));
    var line = asTenant(() -> rma.returnableQuantities(f.caseId())).lines().get(0);

    assertThat(line.returnableQty()).isEqualByComparingTo("0");
    assertThat(line.blockingReasons()).anyMatch(s -> s.contains("已发数量为 0"));
  }

  @Test
  void 已退数量如实报不可得不填零冒充() {
    var f = seed("MO-9", new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("5"));
    var report = asTenant(() -> rma.returnableQuantities(f.caseId()));

    // 11.2.2.2 未交付 ⇒ receivedQty 恒 null，**不填 0**（填 0 会让人以为「从没退过」）。
    assertThat(report.receivedQuantityDelivered()).isFalse();
    assertThat(report.receivedQuantityOwnerModule()).isEqualTo("11.2.2.2 实收分配");
    assertThat(report.lines().get(0).receivedQty()).isNull();
    assertThat(report.lines().get(0).receivedQuantitySource()).isEqualTo("NOT_DELIVERED_11_2_2_2");
  }

  // ================================================================ 审批

  @Test
  void 全部授权并把寄回时限与基数快照落库() {
    var f = seed("MO-10", new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("2"));
    var d = draft(f);
    var due = plusDays(14);

    var decided =
        asTenant(
            () ->
                rma.decide(
                    d.head().id(),
                    new RmaDecide("APPROVED", due, null, authorizeAll(d, new BigDecimal("2")), 0L),
                    user));

    assertThat(decided.head().rmaStatus()).isEqualTo("AUTHORIZED");
    assertThat(decided.head().decision()).isEqualTo("APPROVED");
    assertThat(decided.authorizedQty()).isEqualByComparingTo("2");
    assertThat(decided.head().version()).isEqualTo(1L);

    // ⚠️ 时间写回零漂移：夹具是 +08:00，用 Timestamp.valueOf 会偏 −28800s。
    assertThat(decided.head().returnByAt()).isEqualTo(due);
    assertThat(decided.head().returnByAt().toInstant()).isEqualTo(due.toInstant());

    // 审批那一刻的三个基数快照落库，供事后审计「当时凭什么批了这个数」。
    var line = decided.lines().get(0);
    assertThat(line.orderedQtySnapshot()).isEqualByComparingTo("5");
    assertThat(line.shippedQtySnapshot()).isEqualByComparingTo("3");
    assertThat(line.priorAuthorizedQtySnapshot()).isEqualByComparingTo("0");
    assertThat(line.returnableQty()).isEqualByComparingTo("3");

    // 决定进了留痕（追加式，DB 触发器写的）。
    assertThat(decided.events()).hasSize(2);
    assertThat(decided.events().get(1).eventType()).isEqualTo("AUTHORIZED");
    assertThat(decided.events().get(1).fromStatus()).isEqualTo("DRAFT");
    assertThat(decided.events().get(1).toStatus()).isEqualTo("AUTHORIZED");
  }

  @Test
  void 部分授权必须说明理由() {
    var f = seed("MO-11", new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("2"));
    var d = draft(f);

    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        rma.decide(
                            d.head().id(),
                            new RmaDecide("PARTIALLY_APPROVED", plusDays(14), null,
                                authorizeAll(d, new BigDecimal("1")), 0L),
                            user)))
        .hasMessageContaining("部分授权必须说明理由");

    var ok =
        asTenant(
            () ->
                rma.decide(
                    d.head().id(),
                    new RmaDecide("PARTIALLY_APPROVED", plusDays(14), "第 2 件外包装已破，仅退 1 件",
                        authorizeAll(d, new BigDecimal("1")), 0L),
                    user));
    assertThat(ok.head().decision()).isEqualTo("PARTIALLY_APPROVED");
    assertThat(ok.authorizedQty()).isEqualByComparingTo("1");
  }

  @Test
  void 拒绝必须说明理由且授权量归零() {
    var f = seed("MO-12", new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("2"));
    var d = draft(f);

    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        rma.decide(
                            d.head().id(),
                            new RmaDecide("REJECTED", null, "  ",
                                authorizeAll(d, new BigDecimal("2")), 0L),
                            user)))
        .hasMessageContaining("拒绝必须说明理由");

    var rejected =
        asTenant(
            () ->
                rma.decide(
                    d.head().id(),
                    new RmaDecide("REJECTED", null, "超过售后期第 30 天", 
                        authorizeAll(d, new BigDecimal("2")), 0L),
                    user));
    assertThat(rejected.head().decision()).isEqualTo("REJECTED");
    assertThat(rejected.authorizedQty()).isEqualByComparingTo("0");
    assertThat(rejected.head().decisionReason()).isEqualTo("超过售后期第 30 天");
  }

  @Test
  void 授权必须给出寄回时限() {
    var f = seed("MO-13", new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("2"));
    var d = draft(f);
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        rma.decide(
                            d.head().id(),
                            new RmaDecide("APPROVED", null, null,
                                authorizeAll(d, new BigDecimal("1")), 0L),
                            user)))
        .hasMessageContaining("必须给出买家寄回时限");
  }

  @Test
  void 授权量超过可退上限被拒() {
    // 订购 5、已发 3 ⇒ 上限 3；申请 5 件里批 4 件必须被拒。
    var f = seed("MO-14", new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("5"));
    var d = draft(f);
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        rma.decide(
                            d.head().id(),
                            new RmaDecide("APPROVED", plusDays(14), null,
                                authorizeAll(d, new BigDecimal("4")), 0L),
                            user)))
        .hasMessageContaining("超过可退上限");
  }

  @Test
  void 没有履约投影时授权量再小也批不出去() {
    var f = seed("MO-15", new BigDecimal("5"), null, new BigDecimal("5"));
    var d = draft(f);
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        rma.decide(
                            d.head().id(),
                            new RmaDecide("APPROVED", plusDays(14), null,
                                authorizeAll(d, new BigDecimal("1")), 0L),
                            user)))
        .hasMessageContaining("超过可退上限");
  }

  @Test
  void 授权量超过售后申请数量被拒() {
    var f = seed("MO-16", new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("2"));
    var d = draft(f);
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        rma.decide(
                            d.head().id(),
                            new RmaDecide("APPROVED", plusDays(14), null,
                                authorizeAll(d, new BigDecimal("3")), 0L),
                            user)))
        .hasMessageContaining("超过售后商品行申请数量");
  }

  @Test
  void 漏报某一行会被拒() {
    var f = seed("MO-17", new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("4"));
    // 给这条订单加第二条商品行 + 第二条售后商品行，让授权单有两行可报。
    UUID line2 = UUID.randomUUID();
    // ⚠️ 三条语句必须**同一个事务**：V99 的头行汇总不变式是延迟约束，
    //   插完行再改头若是两个事务，第一条提交时就因头还是旧值而失败。
    // 售后单申请数量要先抬高，第二条商品行才登记得进去（V157 触发器：累计 ≤ 申请量）。
    superTx(
        "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
            + "spec_snapshot,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
            + "created_at) values('" + line2 + "','" + tenant + "','" + f.orderId()
            + "',2,'" + UUID.randomUUID() + "','SKU-MO-17-B','商品B','白色 / M',2,5.0000,0,0,"
            + "10.0000,'','" + T1 + "')",
        "update sales_order set subtotal_amount = subtotal_amount + 10.0000,"
            + " grand_total_amount = grand_total_amount + 10.0000"
            + " where tenant_id = '" + tenant + "'::uuid and id = '" + f.orderId() + "'::uuid",
        "update aftersales_case set requested_quantity = requested_quantity + 2"
            + " where tenant_id = '" + tenant + "'::uuid and id = '" + f.caseId() + "'::uuid");
    asTenant(
        () ->
            details.addLine(
                f.caseId(),
                new AftersalesCaseDetailModels.AftersalesCaseLineCreate(
                    line2, new BigDecimal("2"), null, "USD", "第二行也退"),
                user));

    var d = draft(f);
    assertThat(d.lines()).hasSize(2);

    // 只报第一行 ⇒ 漏报第二行，必须被拒（漏报会让那一行的授权量永远停在 0）。
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        rma.decide(
                            d.head().id(),
                            new RmaDecide("APPROVED", plusDays(14), null,
                                List.of(new RmaLineDecision(d.lines().get(0).id(),
                                    new BigDecimal("1"), null)),
                                0L),
                            user)))
        .hasMessageContaining("必须对本单的每一行");
  }

  @Test
  void 版本不符拒绝审批且缺失版本也拒绝() {
    var f = seed("MO-18", new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("2"));
    var d = draft(f);
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        rma.decide(
                            d.head().id(),
                            new RmaDecide("APPROVED", plusDays(14), null,
                                authorizeAll(d, new BigDecimal("1")), null),
                            user)))
        .hasMessageContaining("expectedVersion");
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        rma.decide(
                            d.head().id(),
                            new RmaDecide("APPROVED", plusDays(14), null,
                                authorizeAll(d, new BigDecimal("1")), 99L),
                            user)))
        .hasMessageContaining("版本不符");
  }

  @Test
  void 已审批的单不能二次审批() {
    var f = seed("MO-19", new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("2"));
    var d = draft(f);
    var decided =
        asTenant(
            () ->
                rma.decide(
                    d.head().id(),
                    new RmaDecide("APPROVED", plusDays(14), null,
                        authorizeAll(d, new BigDecimal("1")), 0L),
                    user));
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        rma.decide(
                            decided.head().id(),
                            new RmaDecide("APPROVED", plusDays(20), null,
                                authorizeAll(decided, new BigDecimal("2")), 1L),
                            user)))
        .hasMessageContaining("只有草稿可以审批");
  }

  // ================================================================ 在途占用与释放

  @Test
  void 别的在途授权占用可退量且撤销后立刻归还() {
    var f = seed("MO-20", new BigDecimal("5"), new BigDecimal("4"), new BigDecimal("4"));

    var first = draft(f);
    asTenant(
        () ->
            rma.decide(
                first.head().id(),
                new RmaDecide("APPROVED", plusDays(14), null,
                    authorizeAll(first, new BigDecimal("3")), 0L),
                user));

    // 第二张单的上限被第一张占了 3 ⇒ 只剩 1。
    var second = draft(f);
    var report = asTenant(() -> rma.returnableQuantities(f.caseId()));
    assertThat(report.lines().get(0).inflightAuthorizedQty()).isEqualByComparingTo("3");
    assertThat(report.lines().get(0).returnableQty()).isEqualByComparingTo("1");

    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        rma.decide(
                            second.head().id(),
                            new RmaDecide("APPROVED", plusDays(14), null,
                                authorizeAll(second, new BigDecimal("2")), 0L),
                            user)))
        .hasMessageContaining("超过可退上限");

    // 撤销第一张 ⇒ 量立刻还给订单行。
    asTenant(() -> rma.cancel(first.head().id(), new RmaCancel("批错了", 1L), user));
    var after = asTenant(() -> rma.returnableQuantities(f.caseId()));
    assertThat(after.lines().get(0).inflightAuthorizedQty()).isEqualByComparingTo("0");
    assertThat(after.lines().get(0).returnableQty()).isEqualByComparingTo("4");

    var secondOk =
        asTenant(
            () ->
                rma.decide(
                    second.head().id(),
                    new RmaDecide("APPROVED", plusDays(14), null,
                        authorizeAll(second, new BigDecimal("2")), 0L),
                    user));
    assertThat(secondOk.authorizedQty()).isEqualByComparingTo("2");
  }

  @Test
  void 拒绝与过期的单不占用可退量() {
    var f = seed("MO-21", new BigDecimal("5"), new BigDecimal("4"), new BigDecimal("4"));

    var rejected = draft(f);
    asTenant(
        () ->
            rma.decide(
                rejected.head().id(),
                new RmaDecide("REJECTED", null, "不符合退货条件",
                    authorizeAll(rejected, new BigDecimal("0")), 0L),
                user));

    // 拒绝的单把行授权成 0，且不算在途。
    var report = asTenant(() -> rma.returnableQuantities(f.caseId()));
    assertThat(report.lines().get(0).inflightAuthorizedQty()).isEqualByComparingTo("0");
  }

  // ================================================================ DB 触发器（绕过服务层）

  @Test
  void 绕过服务层直连SQL批超上限的量被数据库挡下() {
    var f = seed("MO-22", new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("5"));
    var d = draft(f);
    var lineId = d.lines().get(0).id();

    // 表级 CHECK：已发 3 ⇒ 上限 3，批 4 件过不去。
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        sql.execute(
                            "update rma_authorization_line set authorized_qty=4,"
                                + " ordered_qty_snapshot=5, shipped_qty_snapshot=3,"
                                + " prior_authorized_qty_snapshot=0"
                                + " where tenant_id=?::uuid and id=?::uuid",
                            tenant, lineId)))
        .hasMessageContaining("超过可退上限");

    // 表级 CHECK：授权量不得超过申请量（与可退上限那条 CHECK 同时存在，报哪个由约束顺序决定）。
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        sql.execute(
                            "update rma_authorization_line set authorized_qty=6,"
                                + " ordered_qty_snapshot=5, shipped_qty_snapshot=3"
                                + " where tenant_id=?::uuid and id=?::uuid",
                            tenant, lineId)))
        .hasMessageContaining("rma_authorization_line");
  }

  @Test
  void 绕过服务层写与订单不匹配的商品行被数据库挡下() {
    var f = seed("MO-23", new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("2"));
    var other = seed("MO-24", new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("2"));
    var d = draft(f);

    // 把本单的授权行指向**另一张售后单**的商品行。
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        sql.execute(
                            "insert into rma_authorization_line(id,tenant_id,rma_id,case_line_id,"
                                + "requested_qty,authorized_qty,line_note,created_at,updated_at)"
                                + " values(?,?::uuid,?::uuid,?::uuid,1.000000,0,'',"
                                + "?::timestamptz,?::timestamptz)",
                            UUID.randomUUID(), tenant, d.head().id(),
                            other.caseLineIds().get(0), T1, T1)))
        .hasMessageContaining("不属于本授权单所关联的售后单");
  }

  @Test
  void 结案单的行不允许再改() {
    var f = seed("MO-25", new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("2"));
    var d = draft(f);
    asTenant(
        () ->
            rma.decide(
                d.head().id(),
                new RmaDecide("APPROVED", plusDays(14), null,
                    authorizeAll(d, new BigDecimal("1")), 0L),
                user));
    asTenant(() -> rma.cancel(d.head().id(), new RmaCancel("批错了", 1L), user));

    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        sql.execute(
                            "update rma_authorization_line set authorized_qty=0"
                                + " where tenant_id=?::uuid and rma_id=?::uuid",
                            tenant, d.head().id())))
        .hasMessageContaining("已结案");
  }

  @Test
  void 留痕表对运行角色是追加式的() {
    // 留痕表**追加式**：改掉或删掉一行审批记录等于抹掉纠纷证据。
    // 正对照：单头与授权行**要**能改（审批 / 撤销 / 超时关闭都走它们），
    // 只断言「被收回」而不断言「该留的还留着」的话，把 GRANT 全删掉测试也照样绿。
    assertThat(privilege("rma_decision_event", "UPDATE")).as("留痕表的 UPDATE 必须被收回").isFalse();
    assertThat(privilege("rma_decision_event", "DELETE")).as("留痕表的 DELETE 必须被收回").isFalse();
    assertThat(privilege("rma_decision_event", "INSERT")).as("留痕表的 INSERT 必须留着（DB 触发器要写它）")
        .isTrue();
    assertThat(privilege("rma_authorization", "UPDATE")).isTrue();
    assertThat(privilege("rma_authorization_line", "UPDATE")).isTrue();
  }

  boolean privilege(String table, String action) {
    return asTenant(
        () ->
            sql.fetchOne("select has_table_privilege('trade_app',?,?)", table, action)
                .get(0, Boolean.class));
  }

  @Test
  void 三张表都强制开启行级安全() {
    for (String table : List.of("rma_authorization", "rma_authorization_line", "rma_decision_event")) {
      var row =
          asTenant(
              () ->
                  sql.fetchOne(
                      "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?",
                      table));
      assertThat(row).as("V161 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " RLS 未启用").isTrue();
      // 只有 FORCE 才挡得住 owner —— 不 FORCE 的话连表 owner（迁移角色）都绕得过。
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住 owner").isTrue();
      assertThat(privilege(table, "SELECT"))
          .as("trade_app 必须真的能读 " + table + "，否则「看不到」可能只是谁都读不了")
          .isTrue();
      assertThat(privilege(table, "INSERT")).as("trade_app 必须真能往 " + table + " 写").isTrue();
    }
  }

  // ================================================================ 回寄物流

  @Test
  void 登记回寄物流三件套同进同出且留人() {
    var f = seed("MO-27", new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("2"));
    var d = draft(f);
    var decided =
        asTenant(
            () ->
                rma.decide(
                    d.head().id(),
                    new RmaDecide("APPROVED", plusDays(14), null,
                        authorizeAll(d, new BigDecimal("2")), 0L),
                    user));

    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        rma.recordReturnLogistics(
                            decided.head().id(),
                            new RmaReturnLogistics("FedEx", "", T2, "TENANT_MAINTAINED", 1L),
                            user)))
        .hasMessageContaining("运单号不能为空");

    var logged =
        asTenant(
            () ->
                rma.recordReturnLogistics(
                    decided.head().id(),
                    new RmaReturnLogistics("FedEx", "FX-778899", T2, "TENANT_MAINTAINED", 1L),
                    user));
    assertThat(logged.head().returnCarrier()).isEqualTo("FedEx");
    assertThat(logged.head().returnTrackingNo()).isEqualTo("FX-778899");
    assertThat(logged.head().returnLogisticsRecordedBy()).isEqualTo(user);
    // ⚠️ 时间写回零漂移（夹具 +08:00）。
    assertThat(logged.head().returnShippedAt()).isEqualTo(T2);
  }

  @Test
  void 草稿不能登记回寄物流() {
    var f = seed("MO-28", new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("2"));
    var d = draft(f);
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        rma.recordReturnLogistics(
                            d.head().id(),
                            new RmaReturnLogistics("FedEx", "FX-1", T2, "TENANT_MAINTAINED", 0L),
                            user)))
        .hasMessageContaining("只有已授权");
  }

  // ================================================================ 超时未寄自动关闭

  @Test
  void 超时未寄自动关闭且关闭后可退量归还订单行() {
    var f = seed("MO-29", new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("5"));
    var d = draft(f);
    asTenant(
        () ->
            rma.decide(
                d.head().id(),
                new RmaDecide("APPROVED", plusDays(14), null,
                    authorizeAll(d, new BigDecimal("5")), 0L),
                user));
    assertThat(asTenant(() -> rma.returnableQuantities(f.caseId()))
            .lines().get(0).returnableQty()).isEqualByComparingTo("0");

    // 把时限拨到过去（模拟时间过去），再跑关闭。
    asTenant(
        () ->
            sql.execute(
                "update rma_authorization set return_by_at = ?::timestamptz"
                    + " where tenant_id = ?::uuid and id = ?::uuid",
                T1, tenant, d.head().id()));

    var result = asTenant(() -> rma.expireOverdue());
    assertThat(result.scanned()).isEqualTo(1);
    assertThat(result.expired()).isEqualTo(1);
    assertThat(result.items().get(0).outcome()).isEqualTo("EXPIRED");
    assertThat(result.items().get(0).tracked()).isFalse();

    var closed = asTenant(() -> rma.detail(d.head().id()));
    assertThat(closed.head().rmaStatus()).isEqualTo("EXPIRED");
    assertThat(closed.head().expiredAt()).isNotNull();
    assertThat(closed.timelinessState()).isEqualTo("CLOSED");
    assertThat(closed.events()).hasSize(3);
    assertThat(closed.events().get(2).eventType()).isEqualTo("EXPIRED");
    assertThat(closed.events().get(2).reasonNote()).isEqualTo("超时未寄回，自动关闭");

    // 关闭后可退量立刻还给订单行（货没寄回，数量回到可退）。
    assertThat(asTenant(() -> rma.returnableQuantities(f.caseId()))
        .lines().get(0).returnableQty()).isEqualByComparingTo("5");

    // 再跑一次不再命中（已不是在途状态）。
    assertThat(asTenant(() -> rma.expireOverdue()).expired()).isZero();
  }

  @Test
  void 未到时限与已撤销的单都不被关闭() {
    // 订购 / 已发 6：keep 批 5，dropped 还能批 1（两张单的额度必须算得开）。
    var f = seed("MO-30", new BigDecimal("6"), new BigDecimal("6"), new BigDecimal("6"));

    var keep = draft(f);
    asTenant(
        () ->
            rma.decide(
                keep.head().id(),
                new RmaDecide("APPROVED", plusDays(14), null,
                    authorizeAll(keep, new BigDecimal("5")), 0L),
                user));

    var dropped = draft(f);
    var droppedOk =
        asTenant(
            () ->
                rma.decide(
                    dropped.head().id(),
                    new RmaDecide("APPROVED", plusDays(1), null,
                        authorizeAll(dropped, new BigDecimal("1")), 0L),
                    user));
    asTenant(() -> rma.cancel(droppedOk.head().id(), new RmaCancel("买家改主意了", 1L), user));
    // 把已撤销那张的时限也拨到过去：它**不该**再被关一次（撤销已经是终态）。
    superSql(
        "update rma_authorization set return_by_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        T1, tenant, droppedOk.head().id());

    var result = asTenant(() -> rma.expireOverdue());
    assertThat(result.scanned()).isZero();
    assertThat(result.expired()).isZero();
    assertThat(asTenant(() -> rma.detail(keep.head().id())).head().rmaStatus()).isEqualTo("AUTHORIZED");
    assertThat(asTenant(() -> rma.detail(droppedOk.head().id())).head().rmaStatus())
        .isEqualTo("CANCELLED");
  }

  @Test
  void 已登记回寄物流的单到时限仍照常关闭() {
    // 蓝图加粗句：授权后货「可能在路上**或根本没寄回**」——
    // 登记了运单号只说明买家报了个号，货到没到库是 11.2.2.1 的事。
    var f = seed("MO-31", new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("5"));
    var d = draft(f);
    var decided =
        asTenant(
            () ->
                rma.decide(
                    d.head().id(),
                    new RmaDecide("APPROVED", plusDays(14), null,
                        authorizeAll(d, new BigDecimal("5")), 0L),
                    user));
    asTenant(
        () ->
            rma.recordReturnLogistics(
                decided.head().id(),
                new RmaReturnLogistics("UPS", "UP-123", T2, "TENANT_MAINTAINED", 1L),
                user));
    asTenant(
        () ->
            sql.execute(
                "update rma_authorization set return_by_at = ?::timestamptz"
                    + " where tenant_id = ?::uuid and id = ?::uuid",
                T1, tenant, d.head().id()));

    var result = asTenant(() -> rma.expireOverdue());
    assertThat(result.expired()).isEqualTo(1);
    assertThat(result.items().get(0).tracked()).isTrue();
    assertThat(asTenant(() -> rma.detail(d.head().id())).head().rmaStatus()).isEqualTo("EXPIRED");
  }

  // ================================================================ 草稿改单

  @Test
  void 只有草稿能改地址() {
    var f = seed("MO-32", new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("2"));
    var d = draft(f);

    var updated =
        asTenant(
            () ->
                rma.update(
                    d.head().id(),
                    new RmaUpdate("SCRAP", "TENANT_MAINTAINED", null, "新收货人", "+1", null,
                        "CA", null, null, null, null, "新地址", null, null, 0L)));
    assertThat(updated.head().targetDisposition()).isEqualTo("SCRAP");
    assertThat(updated.head().returnRecipientName()).isEqualTo("新收货人");

    asTenant(
        () ->
            rma.decide(
                d.head().id(),
                new RmaDecide("APPROVED", plusDays(14), null,
                    authorizeAll(updated, new BigDecimal("1")), 1L),
                user));
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        rma.update(
                            d.head().id(),
                            new RmaUpdate("SCRAP", "TENANT_MAINTAINED", null, "另一个", "+1", null,
                                "CA", null, null, null, null, "第三个地址", null, null, 2L))))
        .hasMessageContaining("只有草稿可以审批或修改");
  }

  // ================================================================ 列表

  @Test
  void 列表真分页两页不重叠且汇总不受分页() {
    var f = seed("MO-33", new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("5"));
    for (int i = 0; i < 5; i++) {
      draft(f);
    }

    var p1 = asTenant(() -> rma.list(new RmaListQuery(0, 2, null, null, null, null, null, null, null)));
    var p2 = asTenant(() -> rma.list(new RmaListQuery(1, 2, null, null, null, null, null, null, null)));

    assertThat(p1.total()).isEqualTo(5);
    assertThat(p2.total()).isEqualTo(5);
    assertThat(p1.rows()).hasSize(2);
    assertThat(p2.rows()).hasSize(2);
    assertThat(p1.rows().stream().map(RmaRow::id).collect(java.util.stream.Collectors.toSet()))
        .doesNotContainAnyElementsOf(
            p2.rows().stream().map(RmaRow::id).collect(java.util.stream.Collectors.toSet()));
    // 汇总是**全量**口径，不随分页变。
    assertThat(p1.summary().total()).isEqualTo(5);
    assertThat(p1.summary().draft()).isEqualTo(5);
  }

  @Test
  void 列表筛选与排序白名单() {
    var f = seed("MO-34", new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("5"));
    var d = draft(f);
    asTenant(
        () ->
            rma.decide(
                d.head().id(),
                new RmaDecide("APPROVED", plusDays(14), null,
                    authorizeAll(d, new BigDecimal("2")), 0L),
                user));

    assertThat(asTenant(() -> rma.list(
            new RmaListQuery(0, 50, "AUTHORIZED", null, null, null, null, null, null))).rows())
        .hasSize(1);
    assertThat(asTenant(() -> rma.list(
            new RmaListQuery(0, 50, null, "REJECTED", null, null, null, null, null))).rows())
        .isEmpty();
    assertThat(asTenant(() -> rma.list(
            new RmaListQuery(0, 50, null, null, "REFUND_TO_BUYER", null, null, null, null))).rows())
        .hasSize(1);
    assertThat(asTenant(() -> rma.list(
            new RmaListQuery(0, 50, null, null, null, f.caseId(), null, null, null))).rows())
        .hasSize(1);

    // 非法审批决定直接报错，不静默忽略。
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        rma.list(
                            new RmaListQuery(0, 50, null, "WHATEVER", null, null, null, null, null))))
        .hasMessageContaining("审批决定只能是");

    // 排序白名单：任意表达式落回默认键而不进 SQL。
    var sorted = asTenant(
        () -> rma.list(new RmaListQuery(0, 50, null, null, null, null, null, null, "id; drop table x")));
    assertThat(sorted.rows()).hasSize(1);
    assertThat(sorted.sortNote()).contains("排序白名单");
  }

  @Test
  void 真实模糊检索命中授权单号与售后单号() {
    var f = seed("MO-SEARCH-77", new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("5"));
    var d = draft(f);

    assertThat(asTenant(() -> rma.list(
            new RmaListQuery(0, 50, null, null, null, null, null, d.head().rmaNo(), null))).rows())
        .hasSize(1);
    assertThat(asTenant(() -> rma.list(
            new RmaListQuery(0, 50, null, null, null, null, null, "MO-SEARCH-77", null))).rows())
        .hasSize(1);
    assertThat(asTenant(() -> rma.list(
            new RmaListQuery(0, 50, null, null, null, null, null, "根本不存在的号", null))).rows())
        .isEmpty();
  }

  @Test
  void 列表行如实报未收到货并给出归属模块() {
    var f = seed("MO-35", new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("5"));
    var d = draft(f);
    asTenant(
        () ->
            rma.decide(
                d.head().id(),
                new RmaDecide("APPROVED", plusDays(14), null,
                    authorizeAll(d, new BigDecimal("2")), 0L),
                user));

    var row = asTenant(() -> rma.list(new RmaListQuery(0, 50, null, null, null, null, null, null, null)))
        .rows().get(0);
    // 蓝图加粗句：「授权不等于已收到」。
    assertThat(row.received()).isFalse();
    assertThat(row.receivedOwnerModule()).isEqualTo("11.2.2.1 退货入库");
    assertThat(row.timelinessState()).isEqualTo("ON_TRACK");
    assertThat(row.remainingSeconds()).isPositive();
    assertThat(row.action().allowed()).isFalse();
    assertThat(row.action().blockingReason()).contains("只有草稿可审批");
  }

  @Test
  void 超时筛选只看在途单() {
    var f = seed("MO-36", new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("5"));
    var d = draft(f);
    asTenant(
        () ->
            rma.decide(
                d.head().id(),
                new RmaDecide("APPROVED", plusDays(14), null,
                    authorizeAll(d, new BigDecimal("2")), 0L),
                user));
    draft(f); // 草稿不进超时队列

    assertThat(asTenant(() -> rma.list(
            new RmaListQuery(0, 50, null, null, null, null, true, null, null))).rows())
        .isEmpty();

    asTenant(
        () ->
            sql.execute(
                "update rma_authorization set return_by_at = ?::timestamptz"
                    + " where tenant_id = ?::uuid and id = ?::uuid",
                T1, tenant, d.head().id()));
    var overdue = asTenant(() -> rma.list(
        new RmaListQuery(0, 50, null, null, null, null, true, null, null)));
    assertThat(overdue.rows()).hasSize(1);
    assertThat(overdue.rows().get(0).timelinessState()).isEqualTo("OVERDUE");
    assertThat(overdue.summary().overdue()).isEqualTo(1);
    assertThat(overdue.summary().awaitingShipment()).isEqualTo(1);
    // 已退数量恒为 0：登记实收属 11.2.2.1，未交付。
    assertThat(overdue.summary().receivedLogged()).isZero();
  }

  // ================================================================ 能力声明

  @Test
  void 能力声明如实报未交付的下游与刻意不做的事() {
    var c = asTenant(() -> rma.capabilities());

    assertThat(c.relatedModules()).allMatch(m -> !m.delivered());
    assertThat(c.relatedModules()).allMatch(m -> m.href() == null);
    assertThat(c.relatedModules()).extracting(RmaRelatedModule::ownerModule)
        .contains("11.2.1.2", "11.2.2.1", "11.2.2.2");

    RmaSectionCapability received =
        c.sections().stream().filter(s -> s.section().equals("receivedGoods")).findFirst().orElseThrow();
    assertThat(received.available()).isFalse();
    assertThat(received.ownerModule()).isEqualTo("11.2.2.1 退货入库");
    assertThat(received.landsIn()).isNull();

    RmaSectionCapability rq =
        c.sections().stream().filter(s -> s.section().equals("returnableQuantity")).findFirst().orElseThrow();
    assertThat(rq.available()).isTrue();
    assertThat(rq.landsIn()).contains("rma_authorization_line");

    // 刻意不做的事要点名归属模块。
    assertThat(c.boundaries()).isNotEmpty();
    assertThat(c.boundaries()).anyMatch(b -> b.ownerModule().contains("11.2.2.2"));

    // 未交付的动作入口必须禁用并写明归属。
    RmaActionEntry receive =
        c.actions().stream().filter(a -> a.code().equals("RECEIVE")).findFirst().orElseThrow();
    assertThat(receive.allowed()).isFalse();
    assertThat(receive.ownerModule()).isEqualTo("11.2.2.1 退货入库");

    assertThat(c.receivedNote()).contains("授权不等于已收到");
  }

  // ================================================================ HTTP 契约

  @Test
  void HTTP契约与鉴权() throws Exception {
    var f = seed("MO-37", new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("2"));
    var d = draft(f);

    http.perform(get("/trade/rma-authorizations/capabilities")).andExpect(status().isUnauthorized());
    http.perform(get("/trade/rma-authorizations"))
        .andExpect(status().isUnauthorized());
    http.perform(post("/trade/rma-authorizations")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());

    http.perform(get("/trade/rma-authorizations").header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk());

    // 不存在的 id 与跨租户的 id 同一条错误。
    http.perform(get("/trade/rma-authorizations/" + UUID.randomUUID())
            .header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isBadRequest());

    // 建单缺必填 → 400。
    http.perform(post("/trade/rma-authorizations")
            .header("Authorization", "Bearer tenant-main")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"caseId\":\"" + f.caseId() + "\"}"))
        .andExpect(status().isBadRequest());

    // 审批缺 decision → 400。
    http.perform(post("/trade/rma-authorizations/" + d.head().id() + "/decide")
            .header("Authorization", "Bearer tenant-main")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());

    // 版本不符 → 400（冲突的是业务前置条件，不是数据层）。
    http.perform(post("/trade/rma-authorizations/" + d.head().id() + "/decide")
            .header("Authorization", "Bearer tenant-main")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(
                new RmaDecide("APPROVED", plusDays(14), null,
                    authorizeAll(d, new BigDecimal("1")), 42L))))
        .andExpect(status().isBadRequest());

    // 超限授权量 → 400。
    http.perform(post("/trade/rma-authorizations/" + d.head().id() + "/decide")
            .header("Authorization", "Bearer tenant-main")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(
                new RmaDecide("APPROVED", plusDays(14), null,
                    authorizeAll(d, new BigDecimal("3")), 0L))))
        .andExpect(status().isBadRequest());

    // 可退量校验缺 caseId → 400。
    http.perform(get("/trade/rma-authorizations/returnable-quantities")
            .header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isBadRequest());

    http.perform(get("/trade/rma-authorizations/returnable-quantities")
            .param("caseId", f.caseId().toString())
            .header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.receivedQuantityDelivered").value(false));

    // 详情响应带「未收到货」的归属模块。
    http.perform(get("/trade/rma-authorizations/" + d.head().id())
            .header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.received").value(false))
        .andExpect(jsonPath("$.receivedOwnerModule").value("11.2.2.1 退货入库"));

    // 超时关闭端点可用。
    http.perform(post("/trade/rma-authorizations/expire-overdue")
            .header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.expired").value(0));
  }
}
