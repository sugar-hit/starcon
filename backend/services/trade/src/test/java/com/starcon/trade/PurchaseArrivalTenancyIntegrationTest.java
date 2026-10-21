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
 * 6.3.2.2 到货与逾期的<b>多租户三断言</b>。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且<b>实跑在本模块自己的五张表上</b>
 * （purchase_arrival_tracking 全族），<b>不是</b>占位探针表 ——
 * 探针表能证明 RLS 开着，证明不了<b>本模块的查询本身</b>没漏租户谓词。
 *
 * <p><b>本模块尤其不能省隔离</b>：跟踪单里同时有<b>供应商名、采购单号、单价、
 * 应付金额与逾期天数</b> —— 一个租户能看到另一个租户「向某供应商采购 100 件、
 * 单价 2.5、已确认应付 250 美元」，拿到的是**采购底价与账期**；
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
 * 刻意<b>不用</b> JDK 内置 {@code HttpServer} 起身份桩：它靠端口 0 让操作系统现分配，
 * 全量并发下偶发端口被抢占 → 503（本仓已知 flake）。这里一个额外监听端口都不开。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PurchaseArrivalTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID(), userB = UUID.randomUUID();
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
  @MockitoSpyBean WarehouseReceiptQueryPort receipts;
  @MockitoSpyBean FirstMileTrackingQueryPort transit;

  @Autowired PurchaseArrivalService arrivals;
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

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-a"), "trade.purchase.update", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-b"), "trade.purchase.update", work);
  }

  <T> T as(UUID uid, UUID tid, java.util.function.Supplier<T> work) {
    return access.run(actor(uid, tid), "trade.purchase.update", work);
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  LocalDate dbToday(UUID tid, UUID uid) {
    return as(
        uid,
        tid,
        () -> sql.fetchOne("select (now() at time zone 'UTC')::date").get(0, LocalDate.class));
  }

  /** 在指定租户里建一张跟踪单。 */
  PurchaseArrivalDetail seed(UUID tid, UUID uid, String tag) {
    UUID supplier = UUID.randomUUID();
    // ⚠️ 承诺日**在事务外**取（`dbToday` 自带一个事务）——
    //   在 `as(...)` 里面再开一次就是嵌套事务了。
    LocalDate today = dbToday(tid, uid);
    return as(
        uid,
        tid,
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
                    new BigDecimal("100"),
                    new BigDecimal("2.500000"),
                    "USD",
                    today.plusDays(7),
                    "FU-" + tag),
                uid));
  }

  private PurchaseArrivalQuery query() {
    return new PurchaseArrivalQuery(0, 20, null, null, null, null, null, null, null);
  }

  // ==========================================================================
  // 断言一：两租户互不可见
  // ==========================================================================

  @Test
  void 两租户互不可见() throws Exception {
    PurchaseArrivalDetail da = seed(a, userA, "ISO-A");
    // 每轮都新建实体：复用上一轮的记录，第二轮断言通过只能说明「同一条记录没变」。
    PurchaseArrivalDetail db = seed(b, userB, "ISO-B");

    // ---- 列表互不可见（含汇总口径）----
    assertThat(asA(() -> arrivals.list(query())).total()).isEqualTo(1);
    assertThat(asA(() -> arrivals.list(query())).rows())
        .extracting(PurchaseArrivalRow::id)
        .containsExactly(da.head().id());
    assertThat(asB(() -> arrivals.list(query())).rows())
        .extracting(PurchaseArrivalRow::id)
        .containsExactly(db.head().id());

    // 供应商名字不串（两边的供应商编码不同）。
    assertThat(asA(() -> arrivals.detail(da.head().id())).head().supplierCode())
        .isEqualTo("SUP-ISO-A");
    assertThat(asB(() -> arrivals.detail(db.head().id())).head().supplierCode())
        .isEqualTo("SUP-ISO-B");

    // ---- 逾期看板互不可见 ----
    assertThat(asA(() -> arrivals.alertBoard(null, null, null, 0, 50)).total()).isZero();
    assertThat(asB(() -> arrivals.alertBoard(null, null, null, 0, 50)).total()).isZero();

    // ⚠️ **按 id 直达也不行**：A 拿着 B 的跟踪单 id 读，
    //   得到的是与「不存在」完全相同的错误（不告诉调用方「这个 ID 存在但不属于你」）。
    assertThatThrownBy(() -> asA(() -> arrivals.detail(db.head().id())))
        .hasStackTraceContaining("不存在或不属于当前租户");
    // 写入口也拦住：不能替别人登记到货、不能替别人取消。
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        arrivals.registerArrival(
                            db.head().id(),
                            new PurchaseArrivalArrival(
                                0L, new BigDecimal("10"), T1, null, "", ""),
                            userA,
                            "Bearer tenant-a")))
        .hasStackTraceContaining("不存在或不属于当前租户");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        arrivals.cancel(
                            db.head().id(), new PurchaseArrivalCancel(0L, "越权取消"), userA)))
        .hasStackTraceContaining("不存在或不属于当前租户");

    // HTTP 层同样如此（按 id 直达的 URL 是最容易被猜到的那种）。
    http.perform(get("/trade/purchase-arrivals/{id}", db.head().id())
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().is4xxClientError());
  }

  // ==========================================================================
  // 断言二：伪造 tenant header 无效
  // ==========================================================================

  @Test
  void 伪造租户头无效() throws Exception {
    PurchaseArrivalDetail da = seed(a, userA, "FORGE-A");
    PurchaseArrivalDetail db = seed(b, userB, "FORGE-B");

    // 带着 **B 的令牌** + **伪造的 A 的 X-Tenant-Id**：读到的必须是 B 的数据，
    // 不是 A 的 —— 头改不动 identity 解析出来的可信租户。
    String list =
        http.perform(get("/trade/purchase-arrivals")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(list).contains(db.head().id().toString()).doesNotContain(da.head().id().toString());

    // 反过来：带着 A 的令牌 + 伪造的 B 的头，读到的仍然是 A 的数据。
    String list2 =
        http.perform(get("/trade/purchase-arrivals")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(list2).contains(da.head().id().toString()).doesNotContain(db.head().id().toString());

    // 详情同理。
    String detail =
        http.perform(get("/trade/purchase-arrivals/{id}", db.head().id())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(detail).contains(db.head().id().toString()).doesNotContain(da.head().id().toString());

    // 直接在 RLS 上验证：绑定 A 的租户之后，B 的行在库里**读不出来**，
    // 绕过服务层也一样（FORCE RLS 对表 owner 之外的角色同样生效，这里跑的是 trade_app）。
    assertThat(asA(() -> sql.fetchOne(
            "select count(*) from purchase_arrival_tracking where tenant_id=?::uuid and id=?::uuid", b, db.head().id()))
        .get(0, Long.class)).as("RLS 之下看不到别的租户的行").isZero();

    // ⚠️ **即使不带 tenant_id 谓词**也读不到（证明是真隔离，不是应用层过滤）。
    assertThat(asA(() -> sql.fetchOne(
            "select count(*) from purchase_arrival_tracking where id=?::uuid", db.head().id()))
        .get(0, Long.class)).as("不带租户谓词也读不到别人的行").isZero();
  }

  // ==========================================================================
  // 断言三：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 连接池归还后不串租户() throws Exception {
    PurchaseArrivalDetail da = seed(a, userA, "POOL-A");
    PurchaseArrivalDetail db = seed(b, userB, "POOL-B");

    // 交替借还：同一张跟踪单反复以两个租户的身份读，读到的永远是**当前绑定**的租户。
    for (int i = 0; i < 3; i++) {
      assertThat(asA(() -> arrivals.detail(da.head().id())).head().supplierCode())
          .isEqualTo("SUP-POOL-A");
      assertThat(asB(() -> arrivals.detail(db.head().id())).head().supplierCode())
          .isEqualTo("SUP-POOL-B");
      // 归还之后再读另一个租户的 id：仍然读不到。
      assertThatThrownBy(() -> asA(() -> arrivals.detail(db.head().id())))
          .hasStackTraceContaining("不存在或不属于当前租户");
      assertThatThrownBy(() -> asB(() -> arrivals.detail(da.head().id())))
          .hasStackTraceContaining("不存在或不属于当前租户");
    }

    // 到货与应付部分确认也在交替中完成，两个租户的结论互不污染。
    asA(
        () ->
            arrivals.registerArrival(
                da.head().id(),
                new PurchaseArrivalArrival(
                    da.head().version(), new BigDecimal("40"), T1, null, "", ""),
                userA,
                "Bearer tenant-a"));
    asB(
        () ->
            arrivals.registerArrival(
                db.head().id(),
                new PurchaseArrivalArrival(
                    db.head().version(), new BigDecimal("10"), T1, null, "", ""),
                userB,
                "Bearer tenant-b"));

    // A 的已收是 40，B 的是 10 —— 交替读不串。
    assertThat(asA(() -> arrivals.detail(da.head().id())).head().receivedQty())
        .isEqualByComparingTo("40.000000");
    assertThat(asB(() -> arrivals.detail(db.head().id())).head().receivedQty())
        .isEqualByComparingTo("10.000000");

    UUID confA =
        asA(() -> arrivals.detail(da.head().id())).payableConfirmations().get(0).id();
    UUID confB =
        asB(() -> arrivals.detail(db.head().id())).payableConfirmations().get(0).id();

    asA(
        () ->
            arrivals.confirmPayable(
                da.head().id(), confA, new PurchaseArrivalPayableConfirm("AP-A"), userA));
    asB(
        () ->
            arrivals.confirmPayable(
                db.head().id(), confB, new PurchaseArrivalPayableConfirm("AP-B"), userB));

    // 两条结论各自独立：金额、状态、单据号都不串。
    var pa = asA(() -> arrivals.detail(da.head().id()));
    var pb = asB(() -> arrivals.detail(db.head().id()));
    assertThat(pa.payableConfirmations().get(0).effectiveAmount()).isEqualByComparingTo("100.000000");
    assertThat(pa.payableConfirmations().get(0).financeReference()).isEqualTo("AP-A");
    assertThat(pb.payableConfirmations().get(0).effectiveAmount()).isEqualByComparingTo("25.000000");
    assertThat(pb.payableConfirmations().get(0).financeReference()).isEqualTo("AP-B");

    // 到货流水也各自独立。
    assertThat(asA(() -> arrivals.detail(da.head().id())).receipts()).hasSize(1);
    assertThat(asB(() -> arrivals.detail(db.head().id())).receipts()).hasSize(1);
    assertThat(asA(() -> arrivals.detail(da.head().id())).receipts().get(0).arrivedQty())
        .isEqualByComparingTo("40.000000");
    assertThat(asB(() -> arrivals.detail(db.head().id())).receipts().get(0).arrivedQty())
        .isEqualByComparingTo("10.000000");
  }
}
