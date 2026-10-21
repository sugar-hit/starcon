package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.trade.PurchaseArrivalModels.*;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.jooq.Record;
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
 * 6.3.2.2 到货与逾期。
 *
 * <p>跑的是蓝图职责句与那条加粗句：
 *
 * <blockquote>
 * 职责：跟踪采购单的到货状态：已发货（在途）、部分到货、全部到货、逾期未到、取消。
 * 逾期自动预警并给出处置建议（催货/改供应商/调库存/延期售卖）。
 * 输入：8 仓储的收货事件；跟单记录；物流在途状态（10）。
 * 输出：到货状态 + 逾期预警 + 处置建议。
 * 关系：上游 = 8 仓储作业、10 物流；下游 = 6.2.1.1 断货风险、1 工作台待办。
 * <b>部分到货必须立即更新已收数量并触发应付部分确认</b>，否则采购与财务三方数据对不上。
 * </blockquote>
 *
 * <p><b>本类最看重的十二组断言</b>：
 *
 * <ol>
 *   <li><b>部分到货立即更新已收数量</b>：不是等到「全部到货」才一次性写。
 *       登记 40 件之后，已收 = 40、未收 = 60、可收 = 100，三者自洽。</li>
 *   <li><b>部分到货同时落一笔应付部分确认</b>：金额 = 单价快照 × 本次到货数量，
 *       状态恒为「待财务确认」、<b>生效金额恒为 0</b>。</li>
 *   <li><b>到货流水逐笔留痕</b>：几次、每次多少、累计多少都能答出来。</li>
 *   <li><b>不许超收</b>：超出未收数量的登记被拒（服务层与 DB 各拒一次）。</li>
 *   <li><b>非法状态跃迁被拒</b>：已取消不能跳到全部到货；已发货在途不能跳到逾期未到。</li>
 *   <li><b>逾期口径</b>：承诺日已过 ⇒ 逾期未到 + 逾期天数；承诺日未到 ⇒ 不逾期。
 *       <b>判定用同源 DB 时钟</b>（不拿应用机本地时钟去比）。</li>
 *   <li><b>在途不判逾期</b>：标记已发货后，承诺日已过也不判逾期，逾期天数恒为 0。</li>
 *   <li><b>处置建议带依据</b>：依据里有逾期天数、缺口数量、供应商逾期单数这些具体数字。</li>
 *   <li><b>没有财务确认就没有生效</b>：确认那一刻生效金额才变成登记金额。</li>
 *   <li><b>绕过后端直连 SQL 也写不进非法状态</b>（DB 守卫触发器兜底）。</li>
 *   <li><b>终态不可逆</b>：全部到货 / 取消之后整行冻结。</li>
 *   <li><b>金额是 NUMERIC、零浮点误差</b>；时间写回零漂移。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PurchaseArrivalIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> perms =
      List.of("trade.purchase.read", "trade.purchase.update", "trade.purchase.finance");

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

  @Autowired PurchaseArrivalService arrivals;
  /**
   * 只桩掉 {@code require}（对 identity 的一次 HTTP 调用），{@code run} 仍是真实实现 ——
   * 也就是说 RLS 租户绑定与权限判据跑的都是生产代码路径。
   */
  @MockitoSpyBean AccessService access;
  /**
   * 8.2.1.1 收货单在 warehouse 独立库。这里桩掉**读端口**而不是数据本身：
   * 登记到货仍然走真实的服务层路径（快照仍然被复制进到货流水），
   * 只是不真的发一次跨服务 HTTP。
   */
  @MockitoSpyBean WarehouseReceiptQueryPort receipts;
  /** 10.4.1.2 在途跟踪同上。 */
  @MockitoSpyBean FirstMileTrackingQueryPort transit;

  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    pg.stop();
  }

  /** **非 UTC 偏移**夹具：只有这种时刻才能测出 Timestamp.valueOf 的 −28800s 静默偏移。 */
  static final OffsetDateTime T_ARRIVED = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");

  static {
    actors.put("Bearer main", actor(user, tenant));
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
          "truncate purchase_arrival_event, purchase_arrival_disposition,"
              + " purchase_arrival_payable_confirmation, purchase_arrival_receipt,"
              + " purchase_arrival_tracking cascade");
    }
  }

  <T> T as(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer main"), "trade.purchase.update", work);
  }

  // ================================================================ 夹具

  /** **同源 DB 时钟**的今天 —— 逾期断言必须用它，不拿应用机本地时钟去比。 */
  LocalDate dbToday() {
    return as(() -> sql.fetchOne("select (now() at time zone 'UTC')::date").get(0, LocalDate.class));
  }

  /** 夹具：建一张跟踪单。 */
  PurchaseArrivalDetail seed(String tag, LocalDate promised, String qty) {
    UUID supplier = UUID.randomUUID();
    return as(
        () ->
            arrivals.create(
                new PurchaseArrivalCreate(
                    "PO-" + tag,
                    UUID.randomUUID(),
                    "SRC-" + tag,
                    "PURCHASE_ORDER",
                    supplier,
                    "SUP-" + tag,
                    "供应商-" + tag,
                    new BigDecimal(qty),
                    new BigDecimal("2.500000"),
                    "USD",
                    promised,
                    "FU-" + tag),
                user));
  }

  // ================================================================ 断言组

  @Test
  void 建单后数量三件套自洽且已收为零() {
    PurchaseArrivalDetail d = seed("A", dbToday().plusDays(7), "100");
    PurchaseArrivalRow h = d.head();
    assertThat(h.status()).isEqualTo("PENDING_SHIPMENT");
    assertThat(h.receivableQty()).isEqualByComparingTo("100.000000");
    assertThat(h.receivedQty()).isEqualByComparingTo("0.000000");
    assertThat(h.outstandingQty()).isEqualByComparingTo("100.000000");
    assertThat(h.arrivalCount()).isZero();
    assertThat(h.overdue()).isFalse();
    assertThat(h.overdueDays()).isZero();
    // 跟踪单号与采购单号都要有 —— 这是采购员跟单时唯一能拿到的两个锚点。
    assertThat(h.trackingNo()).startsWith("PA-");
    assertThat(h.purchaseOrderNo()).isEqualTo("PO-A");
  }

  @Test
  void 部分到货立即更新已收数量并落一笔应付部分确认() {
    PurchaseArrivalDetail d = seed("B", dbToday().plusDays(7), "100");

    PurchaseArrivalDetail after =
        as(
            () ->
                arrivals.registerArrival(
                    d.head().id(),
                    new PurchaseArrivalArrival(
                        d.head().version(), new BigDecimal("40"), T_ARRIVED, null, "", "首批到货"),
                    user,
                    "Bearer main"));

    // ① **已收数量真的被更新了**（不是等全部到货才写）。
    assertThat(after.head().status()).isEqualTo("PARTIALLY_RECEIVED");
    assertThat(after.head().receivedQty()).isEqualByComparingTo("40.000000");
    assertThat(after.head().outstandingQty()).isEqualByComparingTo("60.000000");
    assertThat(after.head().receivableQty()).isEqualByComparingTo("100.000000");
    assertThat(after.head().arrivalCount()).isEqualTo(1);

    // ② **同时落一笔应付部分确认**，金额 = 单价快照 × 本次到货数量（DB 算的）。
    assertThat(after.payableConfirmations()).hasSize(1);
    PurchaseArrivalPayableRow c = after.payableConfirmations().get(0);
    assertThat(c.confirmationStatus()).isEqualTo("PENDING_FINANCE_CONFIRM");
    assertThat(c.arrivedQty()).isEqualByComparingTo("40.000000");
    assertThat(c.unitPrice()).isEqualByComparingTo("2.500000");
    assertThat(c.confirmationAmount()).isEqualByComparingTo("100.000000");
    assertThat(c.currency()).isEqualTo("USD");
    // ⚠️ **没有财务确认就没有生效** —— 生效金额恒为 0，绝不是「已触发财务确认」。
    assertThat(c.effectiveAmount()).isEqualByComparingTo("0.000000");
    assertThat(c.basisNote()).contains("2.500000").contains("40.000000");

    // ③ 头部的应付累计口径也对得上。
    assertThat(after.head().pendingPayableAmount()).isEqualByComparingTo("100.000000");
    assertThat(after.head().confirmedPayableAmount()).isEqualByComparingTo("0.000000");
  }

  @Test
  void 到货流水逐笔留痕可回答收了几次每次多少累计多少() {
    PurchaseArrivalDetail d = seed("C", dbToday().plusDays(7), "100");
    UUID id = d.head().id();

    PurchaseArrivalDetail first =
        as(
            () ->
                arrivals.registerArrival(
                    id,
                    new PurchaseArrivalArrival(
                        d.head().version(), new BigDecimal("30"), T_ARRIVED, null, "", "第一批"),
                    user,
                    "Bearer main"));
    PurchaseArrivalDetail second =
        as(
            () ->
                arrivals.registerArrival(
                    id,
                    new PurchaseArrivalArrival(
                        first.head().version(),
                        new BigDecimal("20"),
                        T_ARRIVED.plusDays(1),
                        null,
                        "",
                        "第二批"),
                    user,
                    "Bearer main"));

    assertThat(second.receipts()).hasSize(2);
    assertThat(second.receipts().get(0).arrivalSeq()).isEqualTo(1);
    assertThat(second.receipts().get(0).arrivedQty()).isEqualByComparingTo("30.000000");
    assertThat(second.receipts().get(0).arrivalType()).isEqualTo("PARTIAL");
    assertThat(second.receipts().get(1).arrivalSeq()).isEqualTo(2);
    assertThat(second.receipts().get(1).arrivedQty()).isEqualByComparingTo("20.000000");
    // 累计：30 + 20 = 50，未收 50。
    assertThat(second.head().receivedQty()).isEqualByComparingTo("50.000000");
    assertThat(second.head().outstandingQty()).isEqualByComparingTo("50.000000");
    assertThat(second.head().arrivalCount()).isEqualTo(2);
    // 两次到货 ⇒ 两笔应付部分确认（一次到货一笔）。
    assertThat(second.payableConfirmations()).hasSize(2);
    assertThat(second.payableConfirmations().get(0).confirmationAmount())
        .isEqualByComparingTo("75.000000");
    assertThat(second.payableConfirmations().get(1).confirmationAmount())
        .isEqualByComparingTo("50.000000");
  }

  @Test
  void 全部到货后未收归零且状态到终态() {
    PurchaseArrivalDetail d = seed("D", dbToday().plusDays(7), "100");
    PurchaseArrivalDetail after =
        as(
            () ->
                arrivals.registerArrival(
                    d.head().id(),
                    new PurchaseArrivalArrival(
                        d.head().version(), new BigDecimal("100"), T_ARRIVED, null, "", "一次到齐"),
                    user,
                    "Bearer main"));
    assertThat(after.head().status()).isEqualTo("FULLY_RECEIVED");
    assertThat(after.head().receivedQty()).isEqualByComparingTo("100.000000");
    assertThat(after.head().outstandingQty()).isEqualByComparingTo("0.000000");
    // 本次补齐了缺口 ⇒ 类型由 DB 判成「全部到货」，不是调用方填的。
    assertThat(after.receipts().get(0).arrivalType()).isEqualTo("FULL");
  }

  @Test
  void 超收被拒() {
    PurchaseArrivalDetail d = seed("E", dbToday().plusDays(7), "100");
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        arrivals.registerArrival(
                            d.head().id(),
                            new PurchaseArrivalArrival(
                                d.head().version(), new BigDecimal("101"), T_ARRIVED, null, "", ""),
                            user,
                            "Bearer main")))
        .hasStackTraceContaining("不得超过未收数量");
  }

  @Test
  void 已发货在途不判逾期() {
    // 承诺日已经过去 10 天。
    PurchaseArrivalDetail d = seed("F", dbToday().minusDays(10), "100");

    // 未发货 ⇒ 逾期未到，逾期 10 天。
    PurchaseArrivalDetail overdue = as(() -> arrivals.detail(d.head().id()));
    assertThat(overdue.head().status()).isEqualTo("OVERDUE");
    assertThat(overdue.head().overdueDays()).isEqualTo(10);

    // ⚠️ **已发货但在途不算逾期**（还在路上，判逾期会误伤）。
    PurchaseArrivalDetail shipped =
        as(
            () ->
                arrivals.ship(
                    d.head().id(),
                    new PurchaseArrivalShip(
                        overdue.head().version(), "FMSH-F", "WB-F", T_ARRIVED, null, ""),
                    user));
    assertThat(shipped.head().status()).isEqualTo("IN_TRANSIT");
    assertThat(shipped.head().overdue()).isFalse();
    assertThat(shipped.head().overdueDays()).isZero();
  }

  @Test
  void 逾期天数按同源db时钟算且承诺日未到不逾期() {
    LocalDate today = dbToday();

    // 承诺日 5 天后 ⇒ 不逾期。
    PurchaseArrivalDetail notYet = seed("G", today.plusDays(5), "100");
    assertThat(notYet.head().overdue()).isFalse();
    assertThat(notYet.head().overdueDays()).isZero();
    assertThat(notYet.head().daysUntilPromise()).isEqualTo(5);

    // 承诺日 3 天前 ⇒ 逾期 3 天。
    PurchaseArrivalDetail late = seed("H", today.minusDays(3), "100");
    PurchaseArrivalDetail detail = as(() -> arrivals.detail(late.head().id()));
    assertThat(detail.head().status()).isEqualTo("OVERDUE");
    assertThat(detail.head().overdueDays()).isEqualTo(3);
    assertThat(detail.head().daysUntilPromise()).isEqualTo(-3);
  }

  @Test
  void 逾期单给出带数字依据的处置建议() {
    PurchaseArrivalDetail d = seed("I", dbToday().minusDays(12), "100");
    PurchaseArrivalDetail detail = as(() -> arrivals.detail(d.head().id()));

    List<PurchaseArrivalDispositionRow> dispositions = detail.dispositions();
    assertThat(dispositions).isNotEmpty();

    PurchaseArrivalDispositionRow urge =
        dispositions.stream()
            .filter(d2 -> "URGE_SUPPLIER".equals(d2.actionType()))
            .findFirst()
            .orElseThrow();
    // 依据里必须有具体数字：逾期天数、已收、可收、缺口。
    assertThat(urge.basisNote()).contains("已逾期 12 天").contains("缺口");
    assertThat(urge.basisNote()).contains("100.000000");
    assertThat(urge.basisFacts()).contains("\"overdueDays\":12");
    assertThat(urge.basisFacts()).contains("\"outstandingQty\":100.000000");

    // 一件未到 ⇒ 建议延期售卖。
    assertThat(dispositions.stream().anyMatch(d2 -> "DELAY_SALE".equals(d2.actionType()))).isTrue();
    // 逾期 12 天还没到 14 天的改供应商阈值 ⇒ 本单不触发改供应商。
    assertThat(dispositions.stream().anyMatch(d2 -> "SWITCH_SUPPLIER".equals(d2.actionType())))
        .isFalse();
  }

  @Test
  void 改供应商建议依据带该供应商的逾期单数() {
    // 同一个供应商两张逾期单（第二张逾期 20 天 ⇒ 触发改供应商）。
    UUID supplier = UUID.randomUUID();
    // ⚠️ 承诺日**在事务外**算好 —— 在 `as(...)` 里面再开一次就是嵌套事务。
    LocalDate today = dbToday();
    for (int i = 0; i < 2; i++) {
      final int index = i;
      final LocalDate promised = today.minusDays(i == 0 ? 2 : 20);
      as(
          () ->
              arrivals.create(
                  new PurchaseArrivalCreate(
                      "PO-J" + index,
                      UUID.randomUUID(),
                      "SRC-J" + index,
                      "PURCHASE_ORDER",
                      supplier,
                      "SUP-J",
                      "供应商-J",
                      new BigDecimal("100"),
                      new BigDecimal("2.500000"),
                      "USD",
                      promised,
                      "FU-J"),
                  user));
    }
    PurchaseArrivalAlertBoard board = as(() -> arrivals.alertBoard(null, supplier, null, 0, 50));
    assertThat(board.total()).isEqualTo(2);
    PurchaseArrivalAlertRow row =
        board.rows().stream()
            .filter(r -> r.overdueDays() == 20)
            .findFirst()
            .orElseThrow();
    PurchaseArrivalDispositionRow switchSupplier =
        row.dispositions().stream()
            .filter(d -> "SWITCH_SUPPLIER".equals(d.actionType()))
            .findFirst()
            .orElseThrow();
    assertThat(switchSupplier.basisNote()).contains("2 单逾期").contains("累计逾期 22 天");
    assertThat(switchSupplier.basisFacts()).contains("\"supplierOverdueCount\":2");
  }

  @Test
  void 在途滞留进预警看板但状态仍是已发货在途() {
    PurchaseArrivalDetail d = seed("K", dbToday().plusDays(30), "100");
    as(
        () ->
            arrivals.ship(
                d.head().id(),
                new PurchaseArrivalShip(d.head().version(), "FMSH-K", "WB-K", T_ARRIVED, null, ""),
                user));

    // 桩 10.4.1.2 的在途详情：最后一个轨迹节点距今 12 天。
    // ⚠️ 时刻**在事务外**取（`select now()` 不需要租户上下文）——
    //   在 doAnswer 里再开一次 `as(...)` 会造成嵌套事务。
    OffsetDateTime dbNow = sql.fetchOne("select now()").get(0, OffsetDateTime.class);
    OffsetDateTime lastNode = dbNow.minusDays(12);
    UUID trackingId = UUID.randomUUID();
    FirstMileTrackingQueryPort.TrackingView view =
        new FirstMileTrackingQueryPort.TrackingView(
            trackingId,
            UUID.randomUUID(),
            "FMSH-K",
            "SEA",
            "承运人K",
            "WB-K",
            "IN_TRANSIT",
            "OCEAN",
            "CLEARED",
            "海上",
            lastNode,
            dbNow.plusDays(3),
            new BigDecimal("3"),
            false,
            "WATCH",
            0,
            1,
            null);
    org.mockito.Mockito
        .doReturn(view)
        .when(transit)
        .findByShipmentCode(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito
        .doReturn(
            new FirstMileTrackingQueryPort.TrackingDetail(
                view,
                List.of(
                    new FirstMileTrackingQueryPort.TrackingEventView(
                        UUID.randomUUID(), "LOCATION", "海上", "最后节点", lastNode))))
        .when(transit)
        .detail(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString());

    PurchaseArrivalDetail synced = as(() -> arrivals.syncInTransit(d.head().id(), user, "Bearer main"));
    assertThat(synced.head().status()).isEqualTo("IN_TRANSIT");
    assertThat(synced.head().overdue()).isFalse();
    assertThat(synced.head().overdueDays()).isZero();
    assertThat(synced.head().inTransitDwellDays()).isEqualTo(12);

    // 预警看板把它当成「在途滞留」而不是「逾期」。
    PurchaseArrivalAlertBoard board = as(() -> arrivals.alertBoard(null, null, null, 0, 50));
    assertThat(board.summary().inTransitStalledCount()).isEqualTo(1);
    assertThat(board.summary().overdueCount()).isZero();
    assertThat(board.rows().get(0).alertType()).isEqualTo("IN_TRANSIT_STALLED");
    // 催货建议的依据是「滞留 N 天」。
    assertThat(board.rows().get(0).dispositions().get(0).basisNote()).contains("滞留 12 天");
  }

  @Test
  void 财务确认前生效金额恒为零确认后才生效() {
    PurchaseArrivalDetail d = seed("L", dbToday().plusDays(7), "100");
    PurchaseArrivalDetail after =
        as(
            () ->
                arrivals.registerArrival(
                    d.head().id(),
                    new PurchaseArrivalArrival(
                        d.head().version(), new BigDecimal("40"), T_ARRIVED, null, "", ""),
                    user,
                    "Bearer main"));
    UUID confirmationId = after.payableConfirmations().get(0).id();

    // 登记后：待确认 + 生效金额 0。
    assertThat(after.payableConfirmations().get(0).effectiveAmount())
        .isEqualByComparingTo("0.000000");

    // 确认那一刻才生效。
    PurchaseArrivalDetail confirmed =
        as(
            () ->
                arrivals.confirmPayable(
                    d.head().id(),
                    confirmationId,
                    new PurchaseArrivalPayableConfirm("AP-2026-001"),
                    user));
    PurchaseArrivalPayableRow c =
        confirmed.payableConfirmations().stream()
            .filter(p -> p.id().equals(confirmationId))
            .findFirst()
            .orElseThrow();
    assertThat(c.confirmationStatus()).isEqualTo("CONFIRMED");
    assertThat(c.effectiveAmount()).isEqualByComparingTo("100.000000");
    assertThat(c.financeReference()).isEqualTo("AP-2026-001");
    assertThat(confirmed.head().confirmedPayableAmount()).isEqualByComparingTo("100.000000");
    assertThat(confirmed.head().pendingPayableAmount()).isEqualByComparingTo("0.000000");
  }

  @Test
  void 非法状态跃迁被拒() {
    // 已取消不能直接跳到全部到货。
    PurchaseArrivalDetail d = seed("M", dbToday().plusDays(7), "100");
    as(
        () ->
            arrivals.cancel(
                d.head().id(), new PurchaseArrivalCancel(d.head().version(), "供应商不做了"), user));

    assertThatThrownBy(
            () ->
                as(
                    () ->
                        arrivals.registerArrival(
                            d.head().id(),
                            new PurchaseArrivalArrival(
                                0L, new BigDecimal("10"), T_ARRIVED, null, "", ""),
                            user,
                            "Bearer main")))
        .hasStackTraceContaining("已终结");

    // 已发货在途不能跳到逾期未到（DB 守卫① 显式拒绝）。
    PurchaseArrivalDetail e = seed("N", dbToday().minusDays(5), "100");
    as(
        () ->
            arrivals.ship(
                e.head().id(),
                new PurchaseArrivalShip(e.head().version(), "FMSH-N", "WB-N", T_ARRIVED, null, ""),
                user));
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        sql.execute(
                            "update purchase_arrival_tracking set arrival_status = 'OVERDUE'"
                                + " where tenant_id = ?::uuid and id = ?::uuid",
                            tenant,
                            e.head().id())))
        .hasStackTraceContaining("在途");
  }

  @Test
  void 绕过后端直连sql也写不进非法状态() {
    PurchaseArrivalDetail d = seed("O", dbToday().plusDays(7), "100");

    // ① 直连 SQL 改已收数量 ⇒ 触发器按流水重算回去（调用方传不进来）。
    as(
        () ->
            sql.execute(
                "update purchase_arrival_tracking set received_qty = 999, outstanding_qty = 0"
                    + " where tenant_id = ?::uuid and id = ?::uuid",
                tenant,
                d.head().id()));
    Record r =
        as(
            () ->
                sql.fetchOne(
                    "select received_qty, outstanding_qty from purchase_arrival_tracking"
                        + " where tenant_id = ?::uuid and id = ?::uuid",
                    tenant,
                    d.head().id()));
    assertThat(r.get("received_qty", BigDecimal.class)).isEqualByComparingTo("0.000000");
    assertThat(r.get("outstanding_qty", BigDecimal.class)).isEqualByComparingTo("100.000000");

    // ② 直连 SQL 把待确认的应付确认改成已生效 ⇒ 守卫④ 按状态把它清回 0。
    PurchaseArrivalDetail after =
        as(
            () ->
                arrivals.registerArrival(
                    d.head().id(),
                    new PurchaseArrivalArrival(
                        d.head().version(), new BigDecimal("10"), T_ARRIVED, null, "", ""),
                    user,
                    "Bearer main"));
    UUID confirmationId = after.payableConfirmations().get(0).id();
    as(
        () ->
            sql.execute(
                "update purchase_arrival_payable_confirmation set effective_amount = 999"
                    + " where tenant_id = ?::uuid and id = ?::uuid",
                tenant,
                confirmationId));
    BigDecimal effective =
        as(
                () ->
                    sql.fetchOne(
                            "select effective_amount from purchase_arrival_payable_confirmation"
                                + " where tenant_id = ?::uuid and id = ?::uuid",
                            tenant,
                            confirmationId)
                        .get("effective_amount", BigDecimal.class));
    assertThat(effective).isEqualByComparingTo("0.000000");

    // ③ 直连 SQL 越权登记一笔到货（超收）⇒ DB 守卫② 拒。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        sql.execute(
                            "insert into purchase_arrival_receipt("
                                + "id,tenant_id,tracking_id,arrival_seq,arrived_qty,arrival_type,"
                                + "arrived_at,note,recorded_by)"
                                + " values(?::uuid,?::uuid,?::uuid,9,?::numeric,'PARTIAL',"
                                + "?::timestamptz,'',?::uuid)",
                            UUID.randomUUID(),
                            tenant,
                            d.head().id(),
                            new BigDecimal("9999"),
                            T_ARRIVED,
                            user)))
        .hasStackTraceContaining("不得超过未收数量");
  }

  @Test
  void 全部到货是终态整行冻结() {
    PurchaseArrivalDetail d = seed("P", dbToday().plusDays(7), "100");
    PurchaseArrivalDetail after =
        as(
            () ->
                arrivals.registerArrival(
                    d.head().id(),
                    new PurchaseArrivalArrival(
                        d.head().version(), new BigDecimal("100"), T_ARRIVED, null, "", ""),
                    user,
                    "Bearer main"));
    assertThat(after.head().status()).isEqualTo("FULLY_RECEIVED");
    // 终态之后不能再取消。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        arrivals.cancel(
                            d.head().id(),
                            new PurchaseArrivalCancel(after.head().version(), "反悔"),
                            user)))
        .hasStackTraceContaining("终态");
  }

  @Test
  void 取消必须写理由() {
    PurchaseArrivalDetail d = seed("Q", dbToday().plusDays(7), "100");
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        arrivals.cancel(
                            d.head().id(), new PurchaseArrivalCancel(d.head().version(), "  "), user)))
        .hasStackTraceContaining("必须给出取消理由");
  }

  @Test
  void 收货单号要去warehouse核对核对不到就报错() {
    PurchaseArrivalDetail d = seed("R", dbToday().plusDays(7), "100");
    org.mockito.Mockito
        .doReturn(null)
        .when(receipts)
        .findByCode(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());

    assertThatThrownBy(
            () ->
                as(
                    () ->
                        arrivals.registerArrival(
                            d.head().id(),
                            new PurchaseArrivalArrival(
                                d.head().version(),
                                new BigDecimal("10"),
                                T_ARRIVED,
                                "GR-404",
                                "",
                                ""),
                            user,
                            "Bearer main")))
        .hasStackTraceContaining("仓库里没有这张收货单");
  }

  @Test
  void 收货单核对通过后快照进到货流水() {
    PurchaseArrivalDetail d = seed("S", dbToday().plusDays(7), "100");
    org.mockito.Mockito
        .doReturn(
            new WarehouseReceiptQueryPort.ReceiptView(
                UUID.randomUUID(),
                "GR-001",
                "TRANSFER",
                "PO-S",
                UUID.randomUUID(),
                "WH-1",
                "RECEIVED",
                "WB-S",
                new BigDecimal("100"),
                new BigDecimal("40"),
                new BigDecimal("0"),
                new BigDecimal("60"),
                3,
                0))
        .when(receipts)
        .findByCode(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());

    PurchaseArrivalDetail after =
        as(
            () ->
                arrivals.registerArrival(
                    d.head().id(),
                    new PurchaseArrivalArrival(
                        d.head().version(),
                        new BigDecimal("40"),
                        T_ARRIVED,
                        "GR-001",
                        "",
                        ""),
                    user,
                    "Bearer main"));
    PurchaseArrivalReceiptRow receipt = after.receipts().get(0);
    assertThat(receipt.warehouseReceiptCode()).isEqualTo("GR-001");
    assertThat(receipt.warehouseReceiptReceivedQty()).isEqualByComparingTo("40.000000");
    assertThat(receipt.warehouseReceiptSyncedAt()).isNotNull();
  }

  @Test
  void 时间写回零漂移() {
    PurchaseArrivalDetail d = seed("T", dbToday().plusDays(7), "100");
    PurchaseArrivalDetail after =
        as(
            () ->
                arrivals.registerArrival(
                    d.head().id(),
                    new PurchaseArrivalArrival(
                        d.head().version(), new BigDecimal("10"), T_ARRIVED, null, "", ""),
                    user,
                    "Bearer main"));
    // T_ARRIVED 是 +08:00 偏移的时刻；写回来必须是**同一个瞬间**，
    // 不能因为 Timestamp.valueOf(LocalDateTime) 偏 −28800s。
    assertThat(after.receipts().get(0).arrivedAt().toInstant())
        .isEqualTo(T_ARRIVED.toInstant());
  }

  @Test
  void 列表分页筛选与汇总口径() {
    seed("U1", dbToday().minusDays(5), "100");
    seed("U2", dbToday().minusDays(6), "100");
    seed("U3", dbToday().plusDays(5), "100");

    PurchaseArrivalQuery all = new PurchaseArrivalQuery(0, 20, null, null, null, null, null, null, null);
    PurchaseArrivalPage page = as(() -> arrivals.list(all));
    assertThat(page.total()).isEqualTo(3);
    assertThat(page.summary().overdue()).isEqualTo(2);
    assertThat(page.summary().fullyReceived()).isZero();
    // 汇总不受分页影响。
    PurchaseArrivalPage small =
        as(() -> arrivals.list(new PurchaseArrivalQuery(0, 1, null, null, null, null, null, null, null)));
    assertThat(small.rows()).hasSize(1);
    assertThat(small.summary().total()).isEqualTo(3);

    // 只筛逾期。
    PurchaseArrivalPage overdue =
        as(
            () ->
                arrivals.list(
                    new PurchaseArrivalQuery(0, 20, null, null, true, null, null, null, null)));
    assertThat(overdue.total()).isEqualTo(2);
    assertThat(overdue.rows()).allMatch(r -> "OVERDUE".equals(r.status()));
  }

  @Test
  void 处置建议可采纳可忽略() {
    PurchaseArrivalDetail d = seed("V", dbToday().minusDays(10), "100");
    PurchaseArrivalDetail detail = as(() -> arrivals.detail(d.head().id()));
    UUID dispositionId =
        detail.dispositions().stream()
            .filter(x -> "URGE_SUPPLIER".equals(x.actionType()))
            .findFirst()
            .orElseThrow()
            .id();
    assertThat(dispositionId).isNotNull();

    PurchaseArrivalDetail adopted =
        as(
            () ->
                arrivals.resolveDisposition(
                    d.head().id(),
                    dispositionId,
                    new PurchaseArrivalDispositionResolve("ADOPTED", "已联系供应商"),
                    user));
    PurchaseArrivalDispositionRow row =
        adopted.dispositions().stream()
            .filter(x -> "URGE_SUPPLIER".equals(x.actionType()))
            .findFirst()
            .orElseThrow();
    assertThat(row.dispositionStatus()).isEqualTo("ADOPTED");
    assertThat(row.resolveNote()).isEqualTo("已联系供应商");
  }

  @Test
  void 能力声明如实报出未交付的上游() {
    PurchaseArrivalCapabilities c = as(() -> arrivals.capabilities());
    assertThat(c.sections()).isNotEmpty();
    // 6.3.1.x 采购单与 6.3.2.1 跟单未交付 ⇒ 如实报 false，**不假装有**。
    assertThat(c.sections().stream().filter(s -> "6.3.2.1".equals(s.owner())).count()).isEqualTo(1);
    assertThat(c.sections().stream().filter(s -> "6.3.1.1 / 6.3.1.2 / 6.3.1.3".equals(s.owner())).count())
        .isEqualTo(1);
    assertThat(c.sections().stream().filter(s -> "12 财务".equals(s.owner())).count()).isEqualTo(1);
    assertThat(c.overdueRuleNote()).contains("在途");
    assertThat(c.payableRuleNote()).contains("不返回");
  }

  @Test
  void http层按id直达跨租户查不到() throws Exception {
    PurchaseArrivalDetail d = seed("W", dbToday().plusDays(7), "100");
    // 拿一个不存在的 id ⇒ 4xx。
    http.perform(get("/trade/purchase-arrivals/{id}", UUID.randomUUID())
            .header("Authorization", "Bearer main"))
        .andExpect(status().is4xxClientError());
    // 自己租户的能读到。
    http.perform(get("/trade/purchase-arrivals/{id}", d.head().id())
            .header("Authorization", "Bearer main"))
        .andExpect(status().isOk());
  }
}
