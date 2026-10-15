package com.starcon.trade;

import static com.starcon.trade.WaybillRedirectModels.*;
import static com.starcon.trade.WaybillModels.WaybillRequestStatus;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 10.2.1.3 改址与取消。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>本模块<b>只读</b> 10.2.1.2 的 {@code logistics_waybill_request} 与 9.1.1.3 的
 * {@code order_change_request}，所以本测试把它们当作**已交付的上游**直接落数据
 * （而不是把整条链路再跑一遍）。本模块自己的四张表全部真落 PostgreSQL 16。
 *
 * <p><b>本测试要钉住的九条</b>：
 *
 * <ol>
 *   <li><b>时限判定吃固定时刻，不吃 now()</b>：出号时刻钉死在
 *       {@code 2026-10-01T00:00:00Z}，规则登记 24 小时，<b>断言 deadline_at 恰好等于
 *       {@code 2026-10-02T00:00:00Z}</b>，并在边界前后各发一次（23:59:59 通过 /
 *       00:00:01 超时）。这是蓝图加粗句「超时的改址请求要明确告知能否成功」的落点；
 *       如果判定依赖 now()，这两个用例会只在某个时间段通过。</li>
 *   <li><b>「已超时限」与「渠道未接通」是两回事</b>：前者 verdict=DEADLINE_PASSED、
 *       canSucceed=false 且话术是「再也不会成功」；后者 verdict=WITHIN_WINDOW、
 *       canSucceed=false 但话术是「接通后仍可成功」。混起来就是让客服猜。</li>
 *   <li><b>不凭空受理</b>：没有 vendor 实现时 outcome 恒为 NOT_ATTEMPTED，
 *       <b>绝不</b>出现 ACCEPTED；接上替身后才可能 ACCEPTED / REJECTED。</li>
 *   <li><b>「已受理」只来自承运商真实回话</b>：替身返回 REJECTED 时如实落 REJECTED。</li>
 *   <li><b>判定不可改写</b>：直接 UPDATE 改 verdict / deadline_at 被 DB 拒。</li>
 *   <li><b>截止时刻由规则重算</b>：服务层算错 deadline_at 时 DB 触发器当场拒。</li>
 *   <li><b>买家通知只对有结论的票建</b>：超时会建（高频诉求要告知），
 *       还在等回话的不建；通知任务是只追加凭据，改删都被拒。</li>
 *   <li><b>timestamptz 往返断言写回的值</b>（不是只断言没抛异常）。</li>
 *   <li><b>多租户三断言</b>：两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class WaybillRedirectIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /**
   * 承运商侧替身：**方法内局部**开关（照抄 10.2.1.2 的做法 —— static 开关会被别的用例
   * 与 JUnit 执行顺序污染，于是「未接通应返回 Unavailable」那条断言只在某个顺序下成立）。
   */
  static final Map<String, Boolean> CONNECTED = new ConcurrentHashMap<>();

  static final List<String> allPermissions =
      java.util.stream.Stream.of("fulfillment")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "trade." + p + "." + op))
          .toList();

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        dept,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
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
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
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
    // 单连接：只有复用同一条连接才能证明归还后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired WaybillRedirectService service;
  @Autowired AccessService access;
  @Autowired CarrierRedirectAdapterRegistry registry;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actorA;
  AccessService.Access actorB;

  /**
   * 逐个用例清库。
   *
   * <p>⚠️ 这一条是**必需**的，不是洁癖：一个容器跑十几个用例，而本模块的断言里有
   * 「全租户共 0 条通知」「汇总 total == 2」这类**全局计数**。不清库的话，
   * 「超时那次建了一条通知」会把「未回话那次不建」的断言炸掉，而炸不炸取决于
   * JUnit 的用例执行顺序 —— 那种测试是定时炸弹，CI 上偶发红。
   * 用容器超级用户 truncate（照抄 10.2.1.2 的 reset）：RLS 对超级用户不生效，
   * 所以清得干净；被测代码仍然走 trade_app + 强制 RLS，不受影响。
   */
  @BeforeEach
  void resetTables() throws Exception {
    CONNECTED.clear(); // 每个用例从「全部未接通」开始
    actorA = access.require("Bearer tenant-a", "trade.fulfillment.read");
    actorB = access.require("Bearer tenant-b", "trade.fulfillment.read");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate logistics_buyer_notification_task, logistics_waybill_cancel,"
              + " logistics_waybill_redirect, logistics_carrier_redirect_rule,"
              + " order_change_request, order_cancellation_request,"
              + " logistics_waybill_request, logistics_carrier cascade");
    }
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "trade.fulfillment.update", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "trade.fulfillment.update", work);
  }

  // ================================================================ 夹具

  /** 固定出号时刻：全部用例都用它，使 deadline_at 可精确断言。 */
  static final String ISSUED_AT = "2026-10-01T00:00:00Z";
  /** 24 小时时限 ⇒ 截止时刻恰好是 2026-10-02T00:00:00Z。 */
  static final String DEADLINE_AT = "2026-10-02T00:00:00Z";

  /** 落一条**已交付上游**的已出号运单（10.2.1.2 的表），当改址 / 取消的只读输入。 */
  UUID seedIssuedWaybill(String carrierCode, String carrierChannel, String salesOrderNo) {
    var id = UUID.randomUUID();
    seedCarrier(carrierCode, carrierChannel);
    var requestNo = "WAR-" + id.toString().substring(0, 8).toUpperCase();
    var issued = OffsetDateTime.parse(ISSUED_AT);
    asA(
        () -> {
          sql.execute(
              "insert into logistics_waybill_request"
                  + " (id, tenant_id, request_no, idempotency_key, pending_unit_id,"
                  + "  pending_parcel_id, outbound_order_no, sales_order_no, warehouse_ref,"
                  + "  carrier_code, carrier_channel, ship_from_name, ship_from_country,"
                  + "  ship_to_name, ship_to_country, ship_to_address_line, ship_to_city,"
                  + "  ship_to_postal_code, gross_weight_kg, length_mm, width_mm, height_mm,"
                  + "  declared_currency, status, waybill_no, issued_at, attempt_count,"
                  + "  requested_by_membership_id, created_at, updated_at)"
                  + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'ISSUED',?,"
                  + " ?::timestamptz,1,?,?::timestamptz,?::timestamptz)",
              id, a, requestNo, "idem-" + id, UUID.randomUUID(), UUID.randomUUID(),
              "OB-" + requestNo, salesOrderNo, "US-WEST", carrierCode, carrierChannel,
              "深圳仓", "CN", "Buyer", "US", "1 Main St", "LA", "90001",
              new java.math.BigDecimal("2.500000"), new java.math.BigDecimal("300"),
              new java.math.BigDecimal("200"), new java.math.BigDecimal("100"), "USD",
              "WB" + requestNo, issued, user, issued, issued);
          return null;
        });
    return id;
  }

  /** 落一条**未出号**的运单（状态 QUEUED），用来验「未出号不走改址」。 */
  UUID seedUnissuedWaybill(String carrierCode) {
    var id = UUID.randomUUID();
    seedCarrier(carrierCode, "THIRD_PARTY");
    var requestNo = "WAR-" + id.toString().substring(0, 8).toUpperCase();
    var issued = OffsetDateTime.parse(ISSUED_AT);
    asA(
        () -> {
          sql.execute(
              "insert into logistics_waybill_request"
                  + " (id, tenant_id, request_no, idempotency_key, pending_unit_id,"
                  + "  pending_parcel_id, outbound_order_no, sales_order_no, warehouse_ref,"
                  + "  carrier_code, carrier_channel, ship_from_name, ship_from_country,"
                  + "  ship_to_name, ship_to_country, gross_weight_kg, length_mm, width_mm,"
                  + "  height_mm, declared_currency, status, attempt_count,"
                  + "  requested_by_membership_id, created_at, updated_at)"
                  + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'QUEUED',0,?,"
                  + " ?::timestamptz,?::timestamptz)",
              id, a, requestNo, "idem-" + id, UUID.randomUUID(), UUID.randomUUID(),
              "OB-" + requestNo, "SO-1", "US-WEST", carrierCode, "THIRD_PARTY",
              "深圳仓", "CN", "Buyer", "US", new java.math.BigDecimal("1.0"),
              new java.math.BigDecimal("300"), new java.math.BigDecimal("200"),
              new java.math.BigDecimal("100"), "USD", user, issued, issued);
          return null;
        });
    return id;
  }

  void seedCarrier(String code, String channel) {
    asA(
        () -> {
          sql.execute(
              "insert into logistics_carrier (id, tenant_id, code, display_name, carrier_channel,"
                  + " adapter_key, connected, unavailable_reason, registered_by_membership_id,"
                  + " registered_at)"
                  + " values (?,?,?,?,?,?,false,?,?,?::timestamptz)"
                  + " on conflict (tenant_id, code) do nothing",
              UUID.randomUUID(), a, code, code + " 承运商", channel, "test_" + code.toLowerCase(),
              "10.1 物流渠道未交付（测试夹具刻意不接通）", user,
              OffsetDateTime.parse("2026-10-01T00:00:00Z"));
          return null;
        });
  }

  /** 登记一条「支持改址 + 支持取消，各 24 小时」的规则。 */
  void seedRule(String carrierCode, boolean redirect, boolean cancel) {
    seedRule(carrierCode, redirect ? 24 : null, cancel ? 24 : null);
  }

  /**
   * 登记一条规则，时限小时数显式给定。
   *
   * <p>必须能分别指定两个时限：改址与取消的窗口在现实里就是不同的，而且
   * 「同一时刻、两个承运商、两种判定」这类用例只能靠**不同窗口**才造得出来。
   * 传 {@code null} 表示该承运商不支持这一项（服务层会要求补 unsupportedReason）。
   */
  void seedRule(String carrierCode, Integer redirectHours, Integer cancelHours) {
    asA(
        () -> {
          service.upsertRule(
              new CarrierRedirectRuleInput(
                  carrierCode,
                  redirectHours != null,
                  redirectHours,
                  cancelHours != null,
                  cancelHours,
                  redirectHours != null && cancelHours != null ? "" : "该承运商不支持此操作",
                  ""),
              user);
          return null;
        });
  }

  WaybillRedirectRow redirect(
      UUID requestId, OffsetDateTime requestedAt, UUID orderChangeId) {
    return asA(
        () ->
            service.redirect(
                requestId.toString(),
                new WaybillRedirectInput(
                    "Jane New", "US", "9 Market St", "San Francisco", "94105",
                    "买家换了个收件地址", requestedAt, orderChangeId),
                user));
  }

  WaybillCancelRow cancel(UUID requestId, OffsetDateTime requestedAt) {
    return asA(
        () ->
            service.cancel(
                requestId.toString(),
                new WaybillCancelInput("买家要求撤单", requestedAt, null),
                user));
  }

  /** 承运商侧替身：模拟**外部承运商**，不是本模块的数据（与 10.2.1.2 同一手法）。 */
  CarrierRedirectPort stubAdapter(String key) {
    return new CarrierRedirectPort() {
      @Override
      public String adapterKey() {
        return key;
      }

      @Override
      public Resolution resolve(String carrierCode, String adapterKey) {
        return Boolean.TRUE.equals(CONNECTED.get(key))
            ? new Resolution.Connected(this)
            : new Resolution.Unavailable(adapterKey, "10.1.1.1", "承运商未接通");
      }

      @Override
      public boolean supportsRedirect(String carrierCode) {
        return Boolean.TRUE.equals(CONNECTED.get(key));
      }

      @Override
      public boolean supportsCancel(String carrierCode) {
        return Boolean.TRUE.equals(CONNECTED.get(key));
      }

      @Override
      public RedirectOutcome redirect(RedirectCommand command) {
        if (!Boolean.TRUE.equals(CONNECTED.get(key)))
          return RedirectOutcome.adapterMissing("承运商未接通");
        // 「已揽收不可改」是现实里最常见的拒收理由：替身就按这个回。
        return RedirectOutcome.rejected("carrier-ref-1", "票已揽收，无法改址");
      }

      @Override
      public CancelOutcome cancel(CancelCommand command) {
        if (!Boolean.TRUE.equals(CONNECTED.get(key)))
          return CancelOutcome.adapterMissing("承运商未接通");
        return CancelOutcome.accepted("cancel-ref-1");
      }
    };
  }

  // ================================================================ ① 时限判定吃固定时刻

  @Test
  void 时限判定吃固定时刻_截止时刻恰好等于出号加24小时() {
    var requestId = seedIssuedWaybill("DHL", "THIRD_PARTY", "SO-T1");
    seedRule("DHL", true, true);

    // 在时限内发起（10-01 12:00，距出号 12 小时）。
    var row = redirect(requestId, OffsetDateTime.parse("2026-10-01T12:00:00Z"), null);

    // ⚠️ 断言**写回的值**，不是只断言没抛异常：deadline_at 必须精确等于出号 + 24h。
    assertThat(row.deadlineAt()).isEqualTo(OffsetDateTime.parse(DEADLINE_AT));
    // 也从库里读回来钉一遍（证明 timestamptz 绑定没静默偏 −28800s）。
    var fromDb =
        asA(
            () ->
                sql.fetchOne(
                        "select deadline_at, issued_at_snapshot from logistics_waybill_redirect"
                            + " where id = ?",
                        row.id())
                    .get("deadline_at", OffsetDateTime.class));
    assertThat(fromDb).isEqualTo(OffsetDateTime.parse(DEADLINE_AT));
    assertThat(
            asA(
                () ->
                    sql.fetchOne(
                            "select issued_at_snapshot from logistics_waybill_redirect where id = ?",
                            row.id())
                        .get("issued_at_snapshot", OffsetDateTime.class)))
        .isEqualTo(OffsetDateTime.parse(ISSUED_AT));
    assertThat(row.verdict()).isEqualTo(DeadlineVerdict.WITHIN_WINDOW);
  }

  @Test
  void 边界前后_235959通过_000001超时() {
    var requestId = seedIssuedWaybill("DHL", "THIRD_PARTY", "SO-T2");
    seedRule("DHL", true, true);

    // 截止前 1 秒：通过。
    var justBefore = redirect(requestId, OffsetDateTime.parse("2026-10-01T23:59:59Z"), null);
    assertThat(justBefore.verdict()).isEqualTo(DeadlineVerdict.WITHIN_WINDOW);

    // 截止后 1 秒：超时。这条正是蓝图加粗句的场景 —— 必须给一句准话。
    var justAfter = redirect(requestId, OffsetDateTime.parse("2026-10-02T00:00:01Z"), null);
    assertThat(justAfter.verdict()).isEqualTo(DeadlineVerdict.DEADLINE_PASSED);
    assertThat(justAfter.canSucceed()).isFalse();
    assertThat(justAfter.canSucceedReason()).contains("再也不会");
    assertThat(justAfter.deadlineAt()).isEqualTo(OffsetDateTime.parse(DEADLINE_AT));
  }

  // ================================================================ ② 超时 vs 未接通是两回事

  @Test
  void 已超时限_与渠道未接通_是两回事() {
    var expiredId = seedIssuedWaybill("DHL", "THIRD_PARTY", "SO-D1");
    var waitingId = seedIssuedWaybill("FEDEX", "THIRD_PARTY", "SO-D2");
    // ⚠️ 两票的出号时刻**相同**，所以只有在**时限不同**时，「同一时刻」才真能造出
    //   两种处境。曾经两票都登记 24 小时、又在同一时刻发起，结果两票都超时限，
    //   断言「FEDEX 在时限内」只可能靠改夹具才成立 —— 那是把结论写进测试的前提里。
    //   现在：DHL 24 小时（此刻已过），FEDEX 72 小时（此刻仍在窗口内）。
    seedRule("DHL", 24, 24);
    seedRule("FEDEX", 72, 72);

    var at = OffsetDateTime.parse("2026-10-02T00:00:01Z");
    var expired = redirect(expiredId, at, null);
    var waiting = redirect(waitingId, at, null);

    // 已超时限：verdict=DEADLINE_PASSED，话术是「再也不会成功」。
    assertThat(expired.verdict()).isEqualTo(DeadlineVerdict.DEADLINE_PASSED);
    assertThat(expired.canSucceed()).isFalse();
    assertThat(expired.canSucceedReason()).contains("再也不会");

    // 未接通（FEDEX 时限内、但没有 vendor 实现）：
    // verdict=WITHIN_WINDOW，canSucceed=false，话术是「接通后仍可成功」。
    // ⚠️ 关键是它与上面**不是同一个 verdict**，混起来就是让客服猜。
    assertThat(waiting.verdict()).isEqualTo(DeadlineVerdict.WITHIN_WINDOW);
    assertThat(waiting.canSucceed()).isFalse();
    assertThat(waiting.canSucceedReason()).contains("渠道未接通");
    assertThat(waiting.canSucceedReason()).contains("接通后仍可成功");
    // 两个截止时刻也必须各自算对（证明它们确实是不同的窗口，而不是同一个）。
    assertThat(expired.deadlineAt()).isEqualTo(OffsetDateTime.parse(DEADLINE_AT));
    assertThat(waiting.deadlineAt()).isEqualTo(OffsetDateTime.parse("2026-10-04T00:00:00Z"));
  }

  @Test
  void 渠道不支持_与规则未登记_是两回事() {
    var unsupportedId = seedIssuedWaybill("DHL", "THIRD_PARTY", "SO-U1");
    var unregisteredId = seedIssuedWaybill("SF", "THIRD_PARTY", "SO-U2");
    seedRule("DHL", false, false); // 明确不支持
    // SF 刻意**不登记规则** → 未登记。

    var unsupported = redirect(unsupportedId, OffsetDateTime.parse("2026-10-01T10:00:00Z"), null);
    assertThat(unsupported.verdict()).isEqualTo(DeadlineVerdict.CHANNEL_UNSUPPORTED);
    assertThat(unsupported.canSucceed()).isFalse();
    assertThat(unsupported.deadlineAt()).isNull(); // 不支持就没有时限，不编一个

    var unregistered =
        redirect(unregisteredId, OffsetDateTime.parse("2026-10-01T10:00:00Z"), null);
    assertThat(unregistered.verdict()).isEqualTo(DeadlineVerdict.RULE_UNREGISTERED);
    assertThat(unregistered.canSucceed()).isFalse();
    assertThat(unregistered.deadlineAt()).isNull();
    // 「不知道」不是「不支持」—— 话术要分开。
    assertThat(unregistered.canSucceedReason()).contains("不知道");
  }

  // ================================================================ ③ 不凭空受理

  @Test
  void 不凭空受理_未接通时永不出现ACCEPTED() {
    var requestId = seedIssuedWaybill("DHL", "THIRD_PARTY", "SO-N1");
    seedRule("DHL", true, true);
    // 不注册任何 vendor 实现 ⇒ resolve 返回 Unavailable。
    CONNECTED.clear();

    var row = redirect(requestId, OffsetDateTime.parse("2026-10-01T10:00:00Z"), null);
    assertThat(row.outcome()).isEqualTo(StoredOutcome.NOT_ATTEMPTED);
    assertThat(row.outcome()).isNotEqualTo(StoredOutcome.ACCEPTED);
    assertThat(row.carrierRef()).isEmpty();
    assertThat(row.nextAction()).contains("未派发");
  }

  /**
   * 走**真实派发链路**的改址：承运商拒收 ⇒ 落 REJECTED，且通知类型必须是「被拒」。
   *
   * <p>⚠️ 这条用例是整个模块最容易造假的地方，必须说清它证明了什么：
   * 前一版只直接调 {@code port.redirect(...)} 然后断言返回对象的枚举 ——
   * 那是测**替身自己**，不是测本模块。而本模块真正要保证的是：
   * 派发 → 回话 → 落库 → 建通知 这一整条链，且**落库不被 DB 触发器挡住**
   * （曾经 guard 把 can_succeed/can_succeed_reason 钉成永久不可改，
   * 一次真实受理会撞 check_violation 回滚，界面永远停在「未受理」）。
   *
   * <p>做法：直接构造一个带**已接通替身**的服务（同一个类、同一份 jOOQ SQL，
   * 只是换一张注册表），跑完整 redirect()，再从库里把那一行读回来断言。
   */
  @Test
  void 派发链路_承运商拒收则如实落REJECTED且通知为被拒() {
    var requestId = seedIssuedWaybill("DHL", "THIRD_PARTY", "SO-A1");
    seedRule("DHL", true, true);
    CONNECTED.put("test_dhl", true);
    var connected = new WaybillRedirectService(sql, new CarrierRedirectAdapterRegistry(
        List.of(stubAdapter("test_dhl"))));

    var row = asA(
        () ->
            connected.redirect(
                requestId.toString(),
                new WaybillRedirectInput(
                    "Jane New", "US", "9 Market St", "San Francisco", "94105",
                    "买家换了个收件地址", OffsetDateTime.parse("2026-10-01T10:00:00Z"), null),
                user));

    // 承运商回了 REJECTED（「票已揽收」）⇒ 如实落 REJECTED，**不是** ACCEPTED。
    assertThat(row.outcome()).isEqualTo(StoredOutcome.REJECTED);
    assertThat(row.outcome()).isNotEqualTo(StoredOutcome.ACCEPTED);
    assertThat(row.carrierRef()).isEqualTo("carrier-ref-1");
    assertThat(row.outcomeReason()).contains("票已揽收");
    assertThat(row.canSucceed()).isFalse();
    assertThat(row.dispatchedAt()).isNotNull();

    // 通知类型必须跟着**真实回话**走：被拒就是「改址被拒」。
    // 按判定阶段的 canSucceed 建任务会给买家发一条「改址已受理」——货根本没改。
    var kinds =
        asA(
            () ->
                sql.fetch(
                        "select notification_kind from logistics_buyer_notification_task"
                            + " where tenant_id = ? and redirect_id = ?",
                        a, row.id())
                    .stream()
                    .map(x -> x.get("notification_kind", String.class))
                    .toList());
    assertThat(kinds).containsExactly("REDIRECT_REJECTED");
  }

  /** 走**真实派发链路**的取消：承运商受理 ⇒ 落 ACCEPTED。唯一的「已受理」来源。 */
  @Test
  void 派发链路_取消被真实受理则落ACCEPTED且带回执() {
    var requestId = seedIssuedWaybill("DHL", "THIRD_PARTY", "SO-A2");
    seedRule("DHL", true, true);
    CONNECTED.put("test_dhl", true);
    var connected = new WaybillRedirectService(sql, new CarrierRedirectAdapterRegistry(
        List.of(stubAdapter("test_dhl"))));

    var row = asA(
        () ->
            connected.cancel(
                requestId.toString(),
                new WaybillCancelInput(
                    "买家要求撤单", OffsetDateTime.parse("2026-10-01T10:00:00Z"), null),
                user));

    assertThat(row.outcome()).isEqualTo(StoredOutcome.ACCEPTED);
    assertThat(row.carrierRef()).isEqualTo("cancel-ref-1");
    assertThat(row.dispatchedAt()).isNotNull();
    // 已出结果之后结论**冻结**：再改一次会被 DB 拒（凭据不可回退）。
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update logistics_waybill_cancel set can_succeed = false"
                              + " where id = ?",
                          row.id());
                      return null;
                    }))
        .hasStackTraceContaining("不允许再改写");
  }

  /**
   * 同一票可以**重复发起**：接通渠道后重试不该被任何唯一索引挡住。
   *
   * <p>曾经有一版迁移建了「同一运单只能有一条 outcome=NOT_ATTEMPTED」的条件唯一索引，
   * 看上去能挡重复提交 —— 但 NOT_ATTEMPTED 在本模块是「**没有受理凭据**」而不是「在途」：
   * 渠道未接通时那张单会永远停在这个值，于是运营接通渠道后想重试会撞 23505。
   * 一个把「重试」堵死的约束，代价远大于它挡住的重复提交。
   */
  @Test
  void 同一票可重复发起_接通渠道后重试不被唯一索引挡() {
    var requestId = seedIssuedWaybill("DHL", "THIRD_PARTY", "SO-R1");
    seedRule("DHL", true, true);

    // 第一次：渠道未接通 ⇒ NOT_ATTEMPTED。
    var first = redirect(requestId, OffsetDateTime.parse("2026-10-01T10:00:00Z"), null);
    assertThat(first.outcome()).isEqualTo(StoredOutcome.NOT_ATTEMPTED);

    // 接通后重试：必须能建出**第二条**单据，而不是撞唯一键。
    CONNECTED.put("test_dhl", true);
    var connected = new WaybillRedirectService(sql, new CarrierRedirectAdapterRegistry(
        List.of(stubAdapter("test_dhl"))));
    var second = asA(
        () ->
            connected.redirect(
                requestId.toString(),
                new WaybillRedirectInput(
                    "Jane New", "US", "9 Market St", "San Francisco", "94105",
                    "渠道接通后重试", OffsetDateTime.parse("2026-10-01T11:00:00Z"), null),
                user));

    assertThat(second.id()).isNotEqualTo(first.id());
    assertThat(second.redirectNo()).isNotEqualTo(first.redirectNo());
    assertThat(second.outcome()).isEqualTo(StoredOutcome.REJECTED);
    // 两条单据都还在，谁也没被覆盖。
    assertThat(
            asA(
                () ->
                    sql.fetchOne("select count(*) from logistics_waybill_redirect where tenant_id = ?",
                        a)
                        .get(0, Integer.class)))
        .isEqualTo(2);
  }

  // ================================================================ ④ 未出号不走改址

  @Test
  void 未出号的运单不走改址_指向10_2_1_2() {
    var requestId = seedUnissuedWaybill("DHL");
    seedRule("DHL", true, true);
    assertThatThrownBy(
            () -> redirect(requestId, OffsetDateTime.parse("2026-10-01T10:00:00Z"), null))
        .isInstanceOf(ResponseStatusException.class)
        .hasStackTraceContaining("10.2.1.2")
        .hasStackTraceContaining("作废");
  }

  // ================================================================ ⑤ 判定不可改写

  @Test
  void 判定不可改写_直接UPDATE被DB拒() {
    var requestId = seedIssuedWaybill("DHL", "THIRD_PARTY", "SO-I1");
    seedRule("DHL", true, true);
    var row = redirect(requestId, OffsetDateTime.parse("2026-10-01T10:00:00Z"), null);

    // ⚠️ 必须改成**与原值不同**的判定：改成一样的值是空更新，触发器按
    // `IS DISTINCT FROM` 判定为「没变」而**正确地**放行 —— 那时断言「被拒」
    // 就是在要求触发器拒绝一个什么都没改的 UPDATE，是测试自己写错了前提。
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update logistics_waybill_redirect set verdict = 'DEADLINE_PASSED'"
                              + " where id = ?",
                          row.id());
                      return null;
                    }))
        .hasStackTraceContaining("凭据");
    // 改 deadline_at：被触发器拒。
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update logistics_waybill_redirect set deadline_at = ?::timestamptz"
                              + " where id = ?",
                          OffsetDateTime.parse("2027-01-01T00:00:00Z"), row.id());
                      return null;
                    }))
        .hasStackTraceContaining("凭据");
  }

  @Test
  void 通知任务不可改删() {
    var requestId = seedIssuedWaybill("DHL", "THIRD_PARTY", "SO-I2");
    seedRule("DHL", true, true);
    // 超时 ⇒ 有结论 ⇒ 建通知任务。
    redirect(requestId, OffsetDateTime.parse("2026-10-05T00:00:00Z"), null);
    var taskId =
        asA(
            () ->
                sql.fetchOne("select id from logistics_buyer_notification_task where tenant_id=?"
                        + " order by created_at desc limit 1", a)
                    .get("id", UUID.class));

    // ⚠️ 这里被拒的**不是**触发器，而是 REVOKE：V135 对这张表连 UPDATE/DELETE 权限
    //   都没给 trade_app（只追加凭据）。两层都在（权限 + 触发器），但**先撞哪一层**
    //   取决于执行身份，所以断言必须认「被拒绝」这件事本身，而不是认某一层的文案 ——
    //   认死文案会让「换个角色跑」就红，而安全结论其实一模一样。
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update logistics_buyer_notification_task set body_summary = ?"
                              + " where id = ?",
                          "改写", taskId);
                      return null;
                    }))
        .hasStackTraceContaining("logistics_buyer_notification_task");
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute("delete from logistics_buyer_notification_task where id = ?",
                          taskId);
                      return null;
                    }))
        .hasStackTraceContaining("logistics_buyer_notification_task");

    // 行**确实还在**：被拒绝不等于被静默删掉。
    assertThat(
            asA(
                () ->
                    sql.fetchOne("select count(*) from logistics_buyer_notification_task"
                            + " where id = ?", taskId)
                        .get(0, Integer.class)))
        .isEqualTo(1);
  }

  // ================================================================ ⑥ 截止时刻由规则重算

  @Test
  void 截止时刻算错_被DB触发器当场拒() {
    var requestId = seedIssuedWaybill("DHL", "THIRD_PARTY", "SO-T3");
    seedRule("DHL", true, true);
    // 绕过服务层直接 INSERT：运单号 / 申请单号 / 承运商**必须与上游一致**，
    // 否则先撞「与运单申请不一致」那条校验，本用例想验的「截止时刻被重算」根本走不到。
    var waybill =
        asA(
            () ->
                sql.fetchOne(
                        "select request_no, waybill_no, carrier_code from"
                            + " logistics_waybill_request where id = ?",
                        requestId));
    var id = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "insert into logistics_waybill_redirect"
                              + " (id, tenant_id, redirect_no, request_id, request_no, waybill_no,"
                              + "  carrier_code, issued_at_snapshot, deadline_at, recipient_name,"
                              + "  recipient_country, reason, verdict, can_succeed,"
                              + "  can_succeed_reason, outcome, requested_by_membership_id,"
                              + "  requested_at, updated_at)"
                              + " values (?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?,'WITHIN_WINDOW',"
                              + "  true,'理由','NOT_ATTEMPTED',?,?::timestamptz,?::timestamptz)",
                          id, a, "RAC-BAD", requestId,
                          waybill.get("request_no", String.class),
                          waybill.get("waybill_no", String.class),
                          waybill.get("carrier_code", String.class),
                          OffsetDateTime.parse(ISSUED_AT),
                          OffsetDateTime.parse("2026-10-03T00:00:00Z"), // 错的：应为 10-02
                          "Jane", "US", "测试", user, OffsetDateTime.parse("2026-10-01T10:00:00Z"),
                          OffsetDateTime.parse("2026-10-01T10:00:00Z"));
                      return null;
                    }))
        .hasStackTraceContaining("截止时刻");
  }

  @Test
  void 未出号运单直接INSERT改址单_被DB拒() {
    var requestId = seedUnissuedWaybill("DHL");
    seedRule("DHL", true, true);
    var waybill =
        asA(
            () ->
                sql.fetchOne(
                    "select request_no, waybill_no, carrier_code from"
                        + " logistics_waybill_request where id = ?",
                    requestId));
    var id = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "insert into logistics_waybill_redirect"
                              + " (id, tenant_id, redirect_no, request_id, request_no, waybill_no,"
                              + "  carrier_code, issued_at_snapshot, deadline_at, recipient_name,"
                              + "  recipient_country, reason, verdict, can_succeed,"
                              + "  can_succeed_reason, outcome, requested_by_membership_id,"
                              + "  requested_at, updated_at)"
                              + " values (?,?,?,?,?,?,?,?::timestamptz,null,?,?,?,'WITHIN_WINDOW',"
                              + "  true,'理由','NOT_ATTEMPTED',?,?::timestamptz,?::timestamptz)",
                          id, a, "RAC-BAD2", requestId,
                          waybill.get("request_no", String.class),
                          waybill.get("waybill_no", String.class),
                          waybill.get("carrier_code", String.class),
                          OffsetDateTime.parse(ISSUED_AT), "Jane", "US", "测试", user,
                          OffsetDateTime.parse("2026-10-01T10:00:00Z"),
                          OffsetDateTime.parse("2026-10-01T10:00:00Z"));
                      return null;
                    }))
        .hasStackTraceContaining("已出号");
  }

  // ================================================================ ⑦ 买家通知只对有结论的票建

  @Test
  void 通知只对有结论的票建_超时会建_未回话不建() {
    // (a) 未接通 + 在时限内 = 还没有结论 ⇒ 不建通知。
    var waitingId = seedIssuedWaybill("FEDEX", "THIRD_PARTY", "SO-C1");
    seedRule("FEDEX", true, true);
    redirect(waitingId, OffsetDateTime.parse("2026-10-01T10:00:00Z"), null);
    assertThat(
            asA(
                () ->
                    sql.fetchOne("select count(*) from logistics_buyer_notification_task"
                            + " where tenant_id = ?", a)
                        .get(0, Integer.class)))
        .isZero();

    // (b) 超时 = 有结论（高频诉求，必须告知）⇒ 建通知，且类型是 REDIRECT_EXPIRED。
    var expiredId = seedIssuedWaybill("DHL", "THIRD_PARTY", "SO-C2");
    seedRule("DHL", true, true);
    redirect(expiredId, OffsetDateTime.parse("2026-10-05T00:00:00Z"), null);
    var kind =
        asA(
            () ->
                sql.fetchOne("select notification_kind from logistics_buyer_notification_task"
                        + " where tenant_id = ? order by created_at desc limit 1", a)
                    .get("notification_kind", String.class));
    assertThat(kind).isEqualTo("REDIRECT_EXPIRED");
    // 通知任务恒为 QUEUED、ownerModule=15（本模块只建任务不发送）。
    var status =
        asA(
            () ->
                sql.fetchOne("select status, owner_module from"
                        + " logistics_buyer_notification_task where tenant_id = ?"
                        + " order by created_at desc limit 1", a));
    assertThat(status.get("status", String.class)).isEqualTo("QUEUED");
    assertThat(status.get("owner_module", String.class)).isEqualTo("15");
  }

  // ================================================================ ⑧ 联动订单地址变更

  @Test
  void 联动订单地址变更_校验类型与同订单() {
    var requestId = seedIssuedWaybill("DHL", "THIRD_PARTY", "SO-L1");
    seedRule("DHL", true, true);
    // 造一张 ADDRESS_MODIFY 单（9.1.1.3 的表，本模块只读）。
    var changeId = UUID.randomUUID();
    asA(
        () -> {
          sql.execute(
              "insert into order_change_request (id, tenant_id, order_id, order_no, change_kind,"
                  + " status, reason, currency, requested_by_membership_id, requested_at)"
                  + " values (?,?,?,?,?,?,?,?,?,?::timestamptz)",
              changeId, a, UUID.randomUUID(), "SO-L1", "ADDRESS_MODIFY", "CONFIRMED", "买家改地址",
              "USD", user, OffsetDateTime.parse(ISSUED_AT));
          return null;
        });

    var row = redirect(requestId, OffsetDateTime.parse("2026-10-01T10:00:00Z"), changeId);
    assertThat(row.orderChangeRequestId()).isEqualTo(changeId);
    assertThat(row.orderChangeStatus()).isEqualTo("CONFIRMED");

    // 不同订单的单据：联动被拒。
    var otherChangeId = UUID.randomUUID();
    asA(
        () -> {
          sql.execute(
              "insert into order_change_request (id, tenant_id, order_id, order_no, change_kind,"
                  + " status, reason, currency, requested_by_membership_id, requested_at)"
                  + " values (?,?,?,?,?,?,?,?,?,?::timestamptz)",
              otherChangeId, a, UUID.randomUUID(), "SO-OTHER", "ADDRESS_MODIFY", "CONFIRMED",
              "别的订单", "USD", user, OffsetDateTime.parse(ISSUED_AT));
          return null;
        });
    assertThatThrownBy(
            () -> redirect(requestId, OffsetDateTime.parse("2026-10-01T10:00:00Z"), otherChangeId))
        .isInstanceOf(ResponseStatusException.class)
        .hasStackTraceContaining("不是同一张单");

    // 非 ADDRESS_MODIFY 类型：联动被拒。
    var qtyChangeId = UUID.randomUUID();
    asA(
        () -> {
          sql.execute(
              "insert into order_change_request (id, tenant_id, order_id, order_no, change_kind,"
                  + " status, reason, currency, requested_by_membership_id, requested_at)"
                  + " values (?,?,?,?,?,?,?,?,?,?::timestamptz)",
              qtyChangeId, a, UUID.randomUUID(), "SO-L1", "QUANTITY_DECREASE", "CONFIRMED",
              "改数量", "USD", user, OffsetDateTime.parse(ISSUED_AT));
          return null;
        });
    assertThatThrownBy(
            () -> redirect(requestId, OffsetDateTime.parse("2026-10-01T10:00:00Z"), qtyChangeId))
        .isInstanceOf(ResponseStatusException.class)
        .hasStackTraceContaining("不是 ADDRESS_MODIFY");
  }

  // ================================================================ ⑨ 汇总与筛选

  @Test
  void 汇总不受分页影响且按维度分列() {
    var inWindow = seedIssuedWaybill("DHL", "THIRD_PARTY", "SO-S1");
    var expired = seedIssuedWaybill("DHL", "THIRD_PARTY", "SO-S2");
    seedRule("DHL", true, true);
    redirect(inWindow, OffsetDateTime.parse("2026-10-01T10:00:00Z"), null);
    redirect(expired, OffsetDateTime.parse("2026-10-05T00:00:00Z"), null);

    // 取 size=1 的第一页，但汇总必须覆盖全量 2 条。
    var page =
        asA(
            () ->
                service.redirects(null, null, null, null, null, null, null, null, 0, 1));
    assertThat(page.items()).hasSize(1);
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.withinWindow()).isEqualTo(1);
    assertThat(page.deadlinePassed()).isEqualTo(1);
    // 两票都是「现在不能成」（一个超时、一个未接通）⇒ canSucceed=0。
    assertThat(page.canSucceedCount()).isZero();

    // 按 verdict 筛选：真筛。
    var passed =
        asA(
            () -> service.redirects(null, "DEADLINE_PASSED", null, null, null, null, null, null,
                0, 20));
    assertThat(passed.items()).hasSize(1);
    assertThat(passed.items().get(0).verdict()).isEqualTo(DeadlineVerdict.DEADLINE_PASSED);
  }

  @Test
  void 预判_不落库且区分能否成功() {
    var inWindow = seedIssuedWaybill("DHL", "THIRD_PARTY", "SO-P1");
    var expired = seedIssuedWaybill("FEDEX", "THIRD_PARTY", "SO-P2");
    var unissued = seedUnissuedWaybill("UPS");
    // 判定时刻固定在 10-03：DHL 给 96 小时窗口 ⇒ 此刻**仍在窗口内**；
    // FEDEX 给 24 小时 ⇒ 此刻**已超时限**。同样是已出号，结论却相反 ——
    // 用例名说「区分能否成功」，前提就必须真的能区分出来。
    seedRule("DHL", 96, 96);
    seedRule("FEDEX", 24, 24);
    seedRule("UPS", 24, 24);

    var asOf = OffsetDateTime.parse("2026-10-03T00:00:00Z");
    var result =
        asA(
            () ->
                service.precheck(
                    new WaybillRedirectPrecheckInput(
                        List.of(inWindow.toString(), expired.toString(), unissued.toString())),
                    asOf));

    assertThat(result.selected()).isEqualTo(3);
    assertThat(result.issued()).isEqualTo(2);
    assertThat(result.notIssued()).isEqualTo(1);
    // 预判不落库：三张单据一条都没建。
    assertThat(
            asA(
                () ->
                    sql.fetchOne("select count(*) from logistics_waybill_redirect where tenant_id=?"
                            + " and requested_by_membership_id = ? and requested_at ="
                            + " ?::timestamptz",
                        a, user, asOf)
                        .get(0, Integer.class)))
        .isZero();

    var byId = new java.util.HashMap<UUID, WaybillRedirectPrecheckEntry>();
    result.entries().forEach(e -> byId.put(e.requestId(), e));

    // 仍在窗口内：不能说「再也不会成功」。
    assertThat(byId.get(inWindow).verdict()).isEqualTo(DeadlineVerdict.WITHIN_WINDOW);
    assertThat(byId.get(inWindow).canSucceed()).isFalse();
    assertThat(byId.get(inWindow).canSucceedReason()).contains("接通后仍可成功");
    assertThat(byId.get(inWindow).deadlineAt()).isEqualTo(OffsetDateTime.parse("2026-10-05T00:00:00Z"));

    // 已超时限：明确判死，话术是「再也不会成功」。
    assertThat(byId.get(expired).verdict()).isEqualTo(DeadlineVerdict.DEADLINE_PASSED);
    assertThat(byId.get(expired).canSucceed()).isFalse();
    assertThat(byId.get(expired).canSucceedReason()).contains("再也不会");
    // 汇总因此是一增一减，不是全判死。
    assertThat(result.canSucceed()).isZero();
    assertThat(result.cannotSucceed()).isEqualTo(3);

    // 未出号的那票明确指向 10.2.1.2。
    assertThat(byId.get(unissued).waybillIssued()).isFalse();
    assertThat(byId.get(unissued).waybillStatusNote()).contains("10.2.1.2");
  }

  // ================================================================ ⑩ 多租户三断言

  @Test
  void 两租户互不可见() {
    var requestId = seedIssuedWaybill("DHL", "THIRD_PARTY", "SO-TN1");
    seedRule("DHL", true, true);
    redirect(requestId, OffsetDateTime.parse("2026-10-01T10:00:00Z"), null);

    // A 能看到自己的单据。
    assertThat(asA(() -> sql.fetchOne("select count(*) from logistics_waybill_redirect")
            .get(0, Integer.class)))
        .isPositive();
    // B 看不到 A 的任何一张单据 / 规则 / 通知任务。
    assertThat(asB(() -> sql.fetchOne("select count(*) from logistics_waybill_redirect")
            .get(0, Integer.class)))
        .isZero();
    assertThat(asB(() -> sql.fetchOne("select count(*) from logistics_carrier_redirect_rule")
            .get(0, Integer.class)))
        .isZero();
    assertThat(asB(() -> sql.fetchOne("select count(*) from logistics_buyer_notification_task")
            .get(0, Integer.class)))
        .isZero();
    // B 查清单也是空的（而不是报错）。
    assertThat(
            asB(
                () ->
                    service.redirects(null, null, null, null, null, null, null, null, 0, 50)
                        .total()))
        .isZero();
  }

  @Test
  void 伪造租户头无效() throws Exception {
    var requestId = seedIssuedWaybill("DHL", "THIRD_PARTY", "SO-TN2");
    seedRule("DHL", true, true);
    redirect(requestId, OffsetDateTime.parse("2026-10-01T10:00:00Z"), null);

    // 伪造 X-Tenant-Id 走端点：落进的仍然是 A 的租户。
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/trade/logistics/waybill-address-change/redirects")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
            .status().isOk());

    assertThat(asA(() -> sql.fetchOne("select count(*) from logistics_waybill_redirect")
            .get(0, Integer.class)))
        .isPositive();
    assertThat(asB(() -> sql.fetchOne("select count(*) from logistics_waybill_redirect")
            .get(0, Integer.class)))
        .isZero();
  }

  @Test
  void 连接池归还后不串租户_A到B到A三轮() {
    var requestId = seedIssuedWaybill("DHL", "THIRD_PARTY", "SO-TN3");
    seedRule("DHL", true, true);
    var redirectNo =
        redirect(requestId, OffsetDateTime.parse("2026-10-01T10:00:00Z"), null).redirectNo();

    for (int round = 0; round < 3; round++) {
      assertThat(asA(() -> sql.fetchOne("select count(*) from logistics_waybill_redirect")
                  .get(0, Integer.class)))
          .as("第 %d 轮 A 租户应看到自己的行", round)
          .isPositive();
      assertThat(asB(() -> sql.fetchOne("select count(*) from logistics_waybill_redirect")
                  .get(0, Integer.class)))
          .as("第 %d 轮 B 租户必须为空（连接池只有 1 条连接，串租户会在这里暴露）", round)
          .isZero();
      assertThat(
              asA(
                  () ->
                      service.redirects(null, null, null, null, redirectNo, null, null, null, 0,
                              50)
                          .total()))
          .as("第 %d 轮 A 按单号查得到", round)
          .isPositive();
      assertThat(
              asB(
                  () ->
                      service.redirects(null, null, null, null, redirectNo, null, null, null, 0,
                              50)
                          .total()))
          .as("第 %d 轮 B 按同一个单号查不到", round)
          .isZero();
    }
  }

  // ================================================================ 端点冒烟

  @Test
  void 端点返回能力声明() throws Exception {
    var body =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/trade/logistics/waybill-address-change/capabilities")
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    // 承运商那一跳未交付必须 available=false 并点名 ownerModule。
    assertThat(body).contains("10.1.1.1");
    assertThat(body).contains("DEADLINE_PASSED");
  }
}
