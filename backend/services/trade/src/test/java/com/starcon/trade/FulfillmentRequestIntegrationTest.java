package com.starcon.trade;

import static com.starcon.trade.FulfillmentRequestModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 9.3.3.1 履约请求。
 *
 * <p>本测试是 <b>V152 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论，
 * 逐条在真实 PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不是只直连数据库，
 * 以便同时验证「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>四组断言</b>：
 *
 * <ol>
 *   <li><b>流程状态机与乐观并发</b>：合法推进走通、跳步被 DB 拒、版本冲突被拒、
 *       终态不可离开、DB 白名单与 Java 转移表逐条一致；</li>
 *   <li><b>断点恢复与超时重试</b>：卡住 → 挂起（带凭据）→ 从断点恢复 →
 *       attempt 递增且旧凭据留在历史里；没到阈值不许报超时；</li>
 *   <li><b>下游只登记、不假装</b>：没有 warehouse / 8.3.1 回执就不许往下走，
 *       预占缺省是「待回执」而不是「已锁定」；</li>
 *   <li><b>多租户三断言</b>：两租户互不可见 / 伪造 tenant header 无效 /
 *       连接池归还后不串租户。</li>
 * </ol>
 *
 * <p><b>用例之间为什么不靠「清库」隔离</b>：{@code _step} / {@code _event} / {@code _timeout}
 * 三张留痕表的 {@code DELETE} 被触发器挡住，连特权连接都删不掉 —— 那正是 append-only 的意思。
 * 所以每个用例用<b>自己的 requestCode 前缀</b>隔离，断言也一律按该前缀收窄，
 * 而不是断言「全表为空」。后者会在「别的用例留了行」时误报成隔离出问题。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FulfillmentRequestIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final List<String> allPermissions =
      Stream.of("fulfillment")
          .flatMap(p -> Stream.of("read", "create", "update", "delete")
              .map(op -> "trade." + p + "." + op))
          .toList();

  static final String ENT = "CN-ENT-A";

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
      actors.put(
          "Bearer read-only",
          actor(user, a, null, List.of(grant("ALL", List.of("trade.fulfillment.read")))));
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
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired FulfillmentRequestService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actorA;
  AccessService.Access actorB;

  <T> T asA(Supplier<T> work) {
    return access.run(actorA, "trade.fulfillment.update", work);
  }

  <T> T asB(Supplier<T> work) {
    return access.run(actorB, "trade.fulfillment.update", work);
  }

  @BeforeEach
  void seed() {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
  }

  // ================================================================ 工具

  /**
   * 以**特权连接**（迁移用户）执行一段 SQL，并把 {@code app.current_tenant} 绑成给定租户。
   *
   * <p>为什么不用 {@code sql}（jOOQ，跑在 {@code trade_app} 上）：V152 第 11 节把
   * {@code UPDATE} 从 {@code trade_app} 收走了，所以直连应用角色**任何** UPDATE 都只会得到
   * 「permission denied」—— 用它去验第 8.1 节的白名单，验到的是权限而不是状态机。
   * 特权连接只用来探针「生产代码根本不该走的那条路」。
   */
  void privileged(UUID tenant, JdbcRunnable body) throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      c.setAutoCommit(false);
      try {
        c.createStatement().execute("select set_config('app.current_tenant', '" + tenant + "', true)");
        body.run(c);
        c.rollback();
      } catch (Exception e) {
        try {
          c.rollback();
        } catch (Exception ignored) {
          // 连接已断时忽略。
        }
        throw e;
      }
    }
  }

  /** 特权连接上要跑的一段 SQL。 */
  interface JdbcRunnable {
    void run(java.sql.Connection c) throws Exception;
  }

  FulfillmentRequestInput input(String code) {
    return new FulfillmentRequestInput(
        code,
        "SO-" + code,
        "L1",
        "SPLIT-" + code,
        "CONS-" + code,
        "ALLOC-" + code,
        "ROUTE-" + code,
        "SKU-" + code,
        "AMAZON",
        "WH-CN-SH",
        ENT,
        "US",
        "CN-EAST",
        new BigDecimal("50.000000"),
        new BigDecimal("12.3456"),
        "USD",
        "idem-" + code,
        3600,
        7200,
        14400,
        86400,
        "夹具备注");
  }

  FulfillmentRequestSaved create(String code) {
    return asA(() -> service.create(input(code), user));
  }

  UUID idOf(String code) {
    return asA(
            () ->
                service.pageRequests(
                        new FulfillmentRequestQuery(
                            code, null, null, null, null, null, null, null, null, null, null, null,
                            null, 0, 10)))
        .items()
        .get(0)
        .id();
  }

  FulfillmentRequestRow read(UUID id) {
    return asA(() -> service.request(id)).request();
  }

  /**
   * 建一张「阈值 1 秒」的请求并等它真的超时。
   *
   * <p>刻意**不**用「把 deadline 往回拨」来制造超时：V152 第 8.1 节的
   * 「推进后 deadline 不得回退」不变量会把它拒掉（这正说明那条不变量在生效）。
   * 需要真的走 TIMEOUT 路径的用例才用这个；其余挂起场景用 STEP_FAILED ——
   * 按设计 STEP_FAILED / STALE_ACK 不要求阈值已到（「超时不是失败，先查证再决定」）。
   */
  FulfillmentRequestSaved createShortLived(String code) throws Exception {
    var saved =
        asA(
            () ->
                service.create(
                    new FulfillmentRequestInput(
                        code, "SO-" + code, "L1", "SP-" + code, "CO-" + code, "AL-" + code,
                        "RT-" + code, "SKU-" + code, "AMAZON", "WH-CN-SH", ENT, "US", "CN-EAST",
                        new BigDecimal("50.000000"), null, "USD", "idem-" + code,
                        1, 1, 1, 1, ""),
                    user));
    Thread.sleep(1500L);
    assertThat(read(saved.requestId()).overdue()).as("等过阈值后应当是超时态").isTrue();
    return saved;
  }

  /** 推到「已锁定预占」（RESERVE 步完成）。 */
  FulfillmentRequestTransitionResult toReserved(FulfillmentRequestSaved start) {
    asA(
        () ->
            service.advance(
                start.requestId(),
                new FulfillmentRequestStatusInput(
                    FulfillmentRequestStatus.RESERVING,
                    FulfillmentStepKind.RESERVE,
                    start.processVersion(),
                    3600),
                user));
    var afterReserving = read(start.requestId());
    asA(
        () ->
            service.requestReservation(
                start.requestId(), new FulfillmentRequestReservationInput(null), user));
    asA(
        () ->
            service.acknowledgeReservation(
                start.requestId(),
                new FulfillmentRequestReservationAckInput(
                    FulfillmentReservationStatus.LOCKED,
                    "RSV-" + start.requestCode(),
                    new BigDecimal("50.000000"),
                    ""),
                user));
    return asA(
        () ->
            service.advance(
                start.requestId(),
                new FulfillmentRequestStatusInput(
                    FulfillmentRequestStatus.RESERVED,
                    FulfillmentStepKind.OUTBOUND,
                    afterReserving.processVersion(),
                    7200),
                user));
  }

  /** 再推进到「出库单已确认」（OUTBOUND 步也完成）。 */
  FulfillmentRequestTransitionResult toWorking(FulfillmentRequestTransitionResult reserved) {
    asA(
        () ->
            service.advance(
                reserved.requestId(),
                new FulfillmentRequestStatusInput(
                    FulfillmentRequestStatus.OUTBOUND_PENDING,
                    FulfillmentStepKind.OUTBOUND,
                    reserved.processVersion(),
                    7200),
                user));
    asA(
        () ->
            service.requestOutboundOrder(
                reserved.requestId(),
                new FulfillmentRequestOutboundInput(null, null),
                user));
    asA(
        () ->
            service.acknowledgeOutboundOrder(
                reserved.requestId(),
                new FulfillmentRequestOutboundAckInput(
                    FulfillmentOutboundStatus.CONFIRMED,
                    "OB-" + read(reserved.requestId()).requestCode(),
                    "PKG-1",
                    ""),
                user));
    var afterConfirmed = read(reserved.requestId());
    return asA(
        () ->
            service.advance(
                reserved.requestId(),
                new FulfillmentRequestStatusInput(
                    FulfillmentRequestStatus.OUTBOUND_CONFIRMED,
                    FulfillmentStepKind.OUTBOUND,
                    afterConfirmed.processVersion(),
                    14400),
                user));
  }

  /** 编排一条作业并让它进入 RUNNING。 */
  FulfillmentRequestWorkOrderRow assignRunningWork(String code) {
    UUID id = idOf(code);
    asA(
        () ->
            service.assignWorkOrders(
                id,
                new FulfillmentRequestWorkOrderInput(
                    List.of(
                        new FulfillmentRequestWorkTask(
                            "WO-PICK",
                            FulfillmentWorkTaskType.PICK,
                            "WH-CN-SH",
                            "SKU-" + code,
                            new BigDecimal("50.000000"),
                            null))),
                user));
    var rows = asA(() -> service.request(id)).workOrders();
    assertThat(rows).hasSize(1);
    return asA(
        () ->
            service.updateWorkOrder(
                id,
                rows.get(0).id(),
                new FulfillmentRequestWorkOrderStatusInput(
                    FulfillmentWorkStatus.RUNNING, "USR-1", ""),
                user));
  }

  // ================================================================ 1. 流程状态机

  @Test
  void 完整走通一次履约_从建单到完成() throws Exception {
    var created = create("FLOW1");
    assertThat(created.status()).isEqualTo(FulfillmentRequestStatus.DRAFT);
    assertThat(created.currentStep()).isEqualTo(FulfillmentStepKind.RESERVE);
    assertThat(created.processVersion()).isZero();

    var reserved = toReserved(created);
    assertThat(reserved.status()).isEqualTo(FulfillmentRequestStatus.RESERVED);
    var confirmed = toWorking(reserved);
    assertThat(confirmed.status()).isEqualTo(FulfillmentRequestStatus.OUTBOUND_CONFIRMED);

    UUID id = idOf("FLOW1");
    // 先推进到「作业编排中」，再派活 —— 派活是 WORK 步内部的事，不是它的入口。
    var afterConfirm = read(id);
    asA(
        () ->
            service.advance(
                id,
                new FulfillmentRequestStatusInput(
                    FulfillmentRequestStatus.WORKING,
                    FulfillmentStepKind.WORK,
                    afterConfirm.processVersion(),
                    86400),
                user));
    var work = assignRunningWork("FLOW1");
    asA(
        () ->
            service.updateWorkOrder(
                id,
                work.id(),
                new FulfillmentRequestWorkOrderStatusInput(FulfillmentWorkStatus.DONE, "USR-1", ""),
                user));
    asA(
        () ->
            service.trackComplete(
                id, new FulfillmentRequestRetryInput(read(id).processVersion(), "全部收尾"), user));

    var finished = read(id);
    assertThat(finished.status()).isEqualTo(FulfillmentRequestStatus.COMPLETED);
    assertThat(finished.currentStep()).isEqualTo(FulfillmentStepKind.TRACK);
    assertThat(finished.completedAt()).isNotNull();

    // 蓝图四项输出齐全
    var detail = asA(() -> service.request(id));
    assertThat(detail.steps())
        .hasSize(4)
        .allMatch(s -> s.status() == FulfillmentStepStatus.COMPLETED);
    assertThat(detail.reservations())
        .singleElement()
        .extracting(FulfillmentRequestReservationRow::status)
        .isEqualTo(FulfillmentReservationStatus.LOCKED);
    assertThat(detail.outboundOrders())
        .singleElement()
        .extracting(FulfillmentRequestOutboundRow::status)
        .isEqualTo(FulfillmentOutboundStatus.CONFIRMED);
    assertThat(detail.workOrders())
        .singleElement()
        .extracting(FulfillmentRequestWorkOrderRow::status)
        .isEqualTo(FulfillmentWorkStatus.DONE);
    assertThat(detail.events()).isNotEmpty();
  }

  @Test
  void 版本冲突被拒_并发推进只认第一个() throws Exception {
    var created = create("VER1");
    asA(
        () ->
            service.advance(
                created.requestId(),
                new FulfillmentRequestStatusInput(
                    FulfillmentRequestStatus.RESERVING,
                    FulfillmentStepKind.RESERVE,
                    0L,
                    3600),
                user));
    assertThat(read(created.requestId()).processVersion()).isEqualTo(1L);

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.advance(
                            created.requestId(),
                            new FulfillmentRequestStatusInput(
                                FulfillmentRequestStatus.RESERVING,
                                FulfillmentStepKind.RESERVE,
                                0L,
                                3600),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("版本冲突");

    // HTTP 层是 409 而不是 500
    http.perform(
            post("/trade/fulfillment/requests/{id}/advance", created.requestId())
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"status\":\"RESERVING\",\"currentStep\":\"RESERVE\",\"expectedVersion\":0}"))
        .andExpect(status().isConflict());
  }

  @Test
  void expectedVersion缺失被拒() throws Exception {
    var created = create("VER2");
    http.perform(
            post("/trade/fulfillment/requests/{id}/advance", created.requestId())
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"RESERVING\",\"currentStep\":\"RESERVE\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 跳过步骤的推进被拒() throws Exception {
    var created = create("SKIP1");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.advance(
                            created.requestId(),
                            new FulfillmentRequestStatusInput(
                                FulfillmentRequestStatus.RESERVED,
                                FulfillmentStepKind.OUTBOUND,
                                0L,
                                7200),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能从");
  }

  @Test
  void 绕过服务层直接写SQL也跳不过阶梯() {
    // Java 侧已挡住；DB 的延迟约束才是真裁判，所以这里用**特权连接**直连 DB 试。
    // 刻意**只改 current_step、不改 status**：改 status 会先被第 8.1 节的状态白名单拦下，
    // 那样验到的是白名单而不是阶梯 —— 两层闸门必须分开验，否则一层坏了被另一层盖住。
    var created = create("SQLSKIP");
    assertThatThrownBy(
            () ->
                privileged(
                    actorA.tenantId(),
                    c -> {
                      try (var st = c.createStatement()) {
                        st.execute(
                            "update fulfillment_request set current_step = 'OUTBOUND',"
                                + " process_version = process_version + 1,"
                                + " last_step_at = clock_timestamp(),"
                                + " deadline_at = clock_timestamp() + interval '2 hour'"
                                + " where id = '" + created.requestId() + "'");
                        c.commit();
                      }
                    }))
        .as("锁定预占步没完成就把断点挪到出库单，必须被延迟约束拒掉")
        .hasMessageContaining("锁定预占步");
  }

  @Test
  void 终态不可离开() throws Exception {
    var created = create("TERM1");
    privileged(
        actorA.tenantId(),
        c -> {
          try (var st = c.createStatement()) {
            st.execute(
                "select cancel_fulfillment_request('" + created.requestId() + "','单测取消',0,'"
                    + user + "')");
          }
          c.commit();
        });
    assertThat(read(created.requestId()).status()).isEqualTo(FulfillmentRequestStatus.CANCELLED);
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.advance(
                            created.requestId(),
                            new FulfillmentRequestStatusInput(
                                FulfillmentRequestStatus.RESERVING,
                                FulfillmentStepKind.RESERVE,
                                1L,
                                3600),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("终态");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.cancel(
                            created.requestId(),
                            new FulfillmentRequestCancelInput(1L, "再取消一次"),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("终态");
  }

  @Test
  void 取消必须写明原因() throws Exception {
    var created = create("CAN1");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.cancel(
                            created.requestId(),
                            new FulfillmentRequestCancelInput(0L, "   "),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("原因");
  }

  @Test
  void 事件流不可改写() throws Exception {
    var created = create("EVT1");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update fulfillment_request_event set detail = '篡改' where request_id = ?",
                            created.requestId())))
        .as("应用角色连 UPDATE 都没有：留痕表只允许追加");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "delete from fulfillment_request_event where request_id = ?",
                            created.requestId())))
        .as("留痕表的 DELETE 也必须被拒");
  }

  @Test
  void 幂等键相同只建一张单() throws Exception {
    var first = create("IDEM1");
    var again = asA(() -> service.create(input("IDEM1"), user));
    assertThat(again.requestId()).isEqualTo(first.requestId());
    assertThat(
            asA(
                    () ->
                        service.pageRequests(
                            new FulfillmentRequestQuery(
                                "IDEM1", null, null, null, null, null, null, null, null, null,
                                null, null, null, 0, 10)))
                .total())
        .isEqualTo(1L);
  }

  /**
   * DB 第 8.1 节的转移白名单与 Java 枚举 {@code allowedTargets()} 逐条一致。
   *
   * <p>两处各写一遍然后各自漂移是这类状态机最常见的失效方式，所以这里逐条比对。
   * 刻意<b>只</b>挑「白名单判得出来」的用例：像 {@code RESERVED → OUTBOUND_PENDING} 那种
   * 「合法但需要一条预占回执」的跳转，会被延迟约束以别的理由拒，把它算进判定里
   * 就把「白名单」和「证据不足」混成一条了。
   */
  @Test
  void DB白名单与Java转移表逐条一致() throws Exception {
    record Probe(FulfillmentRequestStatus from, FulfillmentRequestStatus to, boolean allowed) {}
    List<Probe> probes =
        List.of(
            new Probe(FulfillmentRequestStatus.RESERVING, FulfillmentRequestStatus.DRAFT, false),
            new Probe(FulfillmentRequestStatus.RESERVING, FulfillmentRequestStatus.COMPLETED, false),
            new Probe(FulfillmentRequestStatus.RESERVED, FulfillmentRequestStatus.RESERVING, false),
            new Probe(FulfillmentRequestStatus.WORKING, FulfillmentRequestStatus.OUTBOUND_PENDING, false),
            new Probe(FulfillmentRequestStatus.COMPLETED, FulfillmentRequestStatus.WORKING, false),
            new Probe(FulfillmentRequestStatus.CANCELLED, FulfillmentRequestStatus.RESERVING, false),
            // 刻意只留**一条**合法跳转做对照：BLOCKED → CANCELLED。
            // 「合法」的那些（RESERVED→OUTBOUND_PENDING、WORKING→COMPLETED）都需要证据
            // （预占回执 / 干完的作业），会被延迟约束以别的理由拒 —— 把它们算进来
            // 就把「白名单」和「证据不足」混成一条，正是本用例要避免的。
            new Probe(FulfillmentRequestStatus.BLOCKED, FulfillmentRequestStatus.CANCELLED, true));
    for (Probe probe : probes) {
      assertThat(probe.from().allowedTargets().contains(probe.to()))
          .as(
              "Java 枚举 allowedTargets() 与本用例写死的不一致：%s -> %s",
              probe.from(), probe.to())
          .isEqualTo(probe.allowed());
      assertThat(dbWhitelistAllows(probe.from(), probe.to()))
          .as(
              "DB 与 Java 对 %s -> %s 的白名单判定不一致（DB 原话：%s）",
              probe.from(), probe.to(), lastProbeMessage)
          .isEqualTo(probe.allowed());
    }
  }

  private String lastProbeMessage;

  private boolean dbWhitelistAllows(FulfillmentRequestStatus from, FulfillmentRequestStatus to)
      throws Exception {
    UUID probe = UUID.randomUUID();
    try {
      privileged(
          actorA.tenantId(),
          c -> {
            try (var st = c.createStatement()) {
              // ⚠ 直接**生**在 from 状态，不走「DRAFT → from」：
              //   DRAFT → RESERVED 本身就是非法跳转，用它搭台子的话探针会在
              //   还没测 from → to 之前先被拒，于是每一对都误判成「白名单拒绝」。
              st.execute(
                  "insert into fulfillment_request (id, tenant_id, request_code, order_ref,"
                      + " allocation_decision_ref, sku_ref, warehouse_ref, legal_entity_code,"
                      + " destination_country_code, requested_qty, status, current_step,"
                      + " process_version, idempotency_key, requested_at, deadline_at, last_step_at,"
                      + " created_by_membership_id, completed_at, cancelled_at, cancel_reason,"
                      + " blocked_at, blocked_reason_code, blocked_reason) values ('"
                      + probe
                      + "','"
                      + actorA.tenantId()
                      + "','PROBE-"
                      + probe
                      + "','SO-P','A-1','SKU-P','WH-P','"
                      + ENT
                      + "','US',1.000000,'"
                      + from.name()
                      + "','"
                      + (from == FulfillmentRequestStatus.COMPLETED ? "TRACK" : "RESERVE")
                      + "',0,'probe:"
                      + probe
                      + "',clock_timestamp(),clock_timestamp() + interval '1 hour',"
                      + "clock_timestamp(),'"
                      + user
                      + "',"
                      // 终态/挂起态的伴生列：直接生在这些状态上时，CHECK 要求它们非空。
                      + (from == FulfillmentRequestStatus.COMPLETED ? "clock_timestamp()" : "null")
                      + ","
                      + (from == FulfillmentRequestStatus.CANCELLED ? "clock_timestamp()" : "null")
                      + ","
                      + (from == FulfillmentRequestStatus.CANCELLED ? "'探针'" : "''")
                      + ","
                      + (from == FulfillmentRequestStatus.BLOCKED ? "clock_timestamp()" : "null")
                      + ","
                      + (from == FulfillmentRequestStatus.BLOCKED ? "'STALE_ACK'" : "''")
                      + ","
                      + (from == FulfillmentRequestStatus.BLOCKED ? "'探针'" : "''")
                      + ")");
              // 转到 CANCELLED 时要一并清掉挂起痕迹 —— 与真正的 cancel 函数同一形状，
              // 否则验到的是「状态与挂起痕迹不自洽」那条 CHECK，而不是第 8.1 节的白名单。
              st.execute(
                  "update fulfillment_request set status = '"
                      + to.name()
                      + "', process_version = process_version + 1,"
                      + " last_step_at = clock_timestamp(),"
                      + " deadline_at = clock_timestamp() + interval '2 hour',"
                      + " blocked_at = null, blocked_reason_code = '', blocked_reason = ''"
                      + (to == FulfillmentRequestStatus.CANCELLED
                          ? ", cancelled_at = clock_timestamp(), cancel_reason = '探针'"
                          : "")
                      + " where id = '"
                      + probe
                      + "'");
              c.commit();
            }
          });
      lastProbeMessage = "(没抛异常)";
      return true;
    } catch (Exception e) {
      lastProbeMessage = String.valueOf(e.getMessage());
      if (lastProbeMessage.length() > 300) {
        lastProbeMessage = lastProbeMessage.substring(0, 300);
      }
      if (lastProbeMessage.contains("状态不能从")
          || lastProbeMessage.contains("是终态，不允许再改状态")) {
        return false;
      }
      throw new AssertionError(
          "探针没能验到第 8.1 节的白名单，而是被别的约束拒了：" + lastProbeMessage);
    }
  }

  // ================================================================ 2. 断点恢复 / 超时重试

  @Test
  void 挂起必须落在当前步并带凭据() throws Exception {
    var created = create("BLK1");
    // 阈值未到就报超时 —— 必须被拒（蓝图：先查证再决定）
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.block(
                            created.requestId(),
                            new FulfillmentRequestBlockInput(
                                FulfillmentStepKind.RESERVE,
                                FulfillmentDetectionKind.TIMEOUT,
                                FulfillmentBlockReason.RESERVE_TIMEOUT,
                                "假装超时",
                                0L),
                            user)))
        // ⚠ 刻意**不**断言异常类型：这条拦截发生在 DB（第 10.2 节的窄函数里），
        //   服务层自己的 allowedTargets 校验先放过去了，所以回来的是
        //   DataIntegrityViolationException 而不是 ResponseStatusException。
        //   真正要钉住的是「阈值没到就不许报超时」这个行为本身。
        .hasMessageContaining("尚未超时");

    asA(
        () ->
            service.block(
                created.requestId(),
                new FulfillmentRequestBlockInput(
                    FulfillmentStepKind.RESERVE,
                    FulfillmentDetectionKind.STEP_FAILED,
                    FulfillmentBlockReason.RESERVE_REJECTED,
                    "warehouse 回了缺货",
                    0L),
                user));

    var blocked = read(created.requestId());
    assertThat(blocked.status()).isEqualTo(FulfillmentRequestStatus.BLOCKED);
    assertThat(blocked.blockedReasonCode()).isEqualTo(FulfillmentBlockReason.RESERVE_REJECTED);
    assertThat(asA(() -> service.request(created.requestId())).timeouts())
        .singleElement()
        .extracting(FulfillmentRequestTimeoutRow::stepKind)
        .isEqualTo(FulfillmentStepKind.RESERVE);
  }

  @Test
  void 挂起必须落在当前步_不能赖到别步上() throws Exception {
    var reserved = toReserved(create("BLK2"));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.block(
                            reserved.requestId(),
                            new FulfillmentRequestBlockInput(
                                FulfillmentStepKind.WORK,
                                FulfillmentDetectionKind.STEP_FAILED,
                                FulfillmentBlockReason.WORK_TIMEOUT,
                                "赖到别步",
                                2L),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("当前步");
  }

  @Test
  void 断点恢复_从卡住的那一步继续而不是从头() throws Exception {
    var reserved = toReserved(create("RTY1"));
    long version = reserved.processVersion();
    asA(
        () ->
            service.block(
                reserved.requestId(),
                new FulfillmentRequestBlockInput(
                    FulfillmentStepKind.OUTBOUND,
                    FulfillmentDetectionKind.STEP_FAILED,
                    FulfillmentBlockReason.OUTBOUND_TIMEOUT,
                    "8.3.1 回了缺货",
                    version),
                user));
    var blocked = read(reserved.requestId());
    assertThat(blocked.status()).isEqualTo(FulfillmentRequestStatus.BLOCKED);
    assertThat(blocked.currentStep()).isEqualTo(FulfillmentStepKind.OUTBOUND);
    long blockedVersion = blocked.processVersion();

    var resumed =
        asA(
            () ->
                service.retry(
                    reserved.requestId(),
                    new FulfillmentRequestRetryInput(
                        blockedVersion, "已电话确认 8.3.1 在处理，重新下发"),
                    user));
    // 落回 OUTBOUND 对应的「进行中」状态，而不是回到 DRAFT 重新发预占
    assertThat(resumed.status()).isEqualTo(FulfillmentRequestStatus.OUTBOUND_PENDING);
    assertThat(resumed.currentStep()).isEqualTo(FulfillmentStepKind.OUTBOUND);
    assertThat(resumed.attempt()).isEqualTo(2);
    assertThat(resumed.processVersion()).isEqualTo(blockedVersion + 1);

    var detail = asA(() -> service.request(reserved.requestId()));
    // 旧尝试的挂起凭据留在历史里（attempt 仍是 1）
    assertThat(detail.timeouts())
        .singleElement()
        .extracting(FulfillmentRequestTimeoutRow::attempt)
        .isEqualTo(1);
    // 重试前锁定预占已经完成，所以断点恢复后可以直接出库
    assertThat(detail.steps())
        .filteredOn(s -> s.stepKind() == FulfillmentStepKind.RESERVE)
        .singleElement()
        .extracting(FulfillmentRequestStepRow::status)
        .isEqualTo(FulfillmentStepStatus.COMPLETED);
  }

  @Test
  void 恢复后截止时刻必须顺延_否则重试出来当场又超时() throws Exception {
    var reserved = toReserved(create("DL1"));
    asA(
        () ->
            service.block(
                reserved.requestId(),
                new FulfillmentRequestBlockInput(
                    FulfillmentStepKind.OUTBOUND,
                    FulfillmentDetectionKind.STEP_FAILED,
                    FulfillmentBlockReason.OUTBOUND_TIMEOUT,
                    "出库单超时",
                    reserved.processVersion()),
                user));
    var resumed =
        asA(
            () ->
                service.retry(
                    reserved.requestId(),
                    new FulfillmentRequestRetryInput(
                        read(reserved.requestId()).processVersion(), "重新下发"),
                    user));
    assertThat(resumed.deadlineAt()).isAfter(OffsetDateTime.now().minusMinutes(1));
    assertThat(read(reserved.requestId()).overdue())
        .as("恢复后不应立刻就是超时态")
        .isFalse();
  }

  @Test
  void 重试必须写明理由() throws Exception {
    var reserved = toReserved(create("RTY2"));
    asA(
        () ->
            service.block(
                reserved.requestId(),
                new FulfillmentRequestBlockInput(
                    FulfillmentStepKind.OUTBOUND,
                    FulfillmentDetectionKind.STEP_FAILED,
                    FulfillmentBlockReason.OUTBOUND_TIMEOUT,
                    "出库单超时",
                    reserved.processVersion()),
                user));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.retry(
                            reserved.requestId(),
                            new FulfillmentRequestRetryInput(
                                read(reserved.requestId()).processVersion(), "  "),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("为什么重试");
  }

  @Test
  void 只有挂起的才能重试() throws Exception {
    var created = create("RTY3");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.retry(
                            created.requestId(),
                            new FulfillmentRequestRetryInput(0L, "没卡住就重试"),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("没卡住");
  }

  @Test
  void 超时扫描只挂真的超了的并逐条报出() throws Exception {
    var due = createShortLived("SCAN1");
    var notYet = create("SCAN2");

    var result =
        asA(() -> service.scanTimeouts(new FulfillmentRequestScanInput(null, 100), user));
    assertThat(result.blocked()).anyMatch(b -> b.requestId().equals(due.requestId()));
    assertThat(result.blocked()).noneMatch(b -> b.requestId().equals(notYet.requestId()));
    assertThat(read(due.requestId()).status()).isEqualTo(FulfillmentRequestStatus.BLOCKED);
    assertThat(read(notYet.requestId()).status()).isEqualTo(FulfillmentRequestStatus.DRAFT);

    // 已被挂起的不该被重复挂起
    var again =
        asA(() -> service.scanTimeouts(new FulfillmentRequestScanInput(null, 100), user));
    assertThat(again.blocked()).noneMatch(b -> b.requestId().equals(due.requestId()));
  }

  @Test
  void 可恢复清单只列挂起的() throws Exception {
    var reserved = toReserved(create("RSM1"));
    var plain = create("RSM2");
    asA(
        () ->
            service.block(
                reserved.requestId(),
                new FulfillmentRequestBlockInput(
                    FulfillmentStepKind.OUTBOUND,
                    FulfillmentDetectionKind.STEP_FAILED,
                    FulfillmentBlockReason.OUTBOUND_TIMEOUT,
                    "出库单超时",
                    reserved.processVersion()),
                user));
    var page = asA(() -> service.pageResumable(0, 200));
    assertThat(page.items())
        .extracting(FulfillmentRequestRow::requestCode)
        .contains("RSM1")
        .doesNotContain("RSM2");
    assertThat(page.items())
        .filteredOn(r -> r.requestCode().equals("RSM1"))
        .allMatch(r -> r.status() == FulfillmentRequestStatus.BLOCKED);
    assertThat(plain).isNotNull();
  }

  @Test
  void 流程状态落库后能被重新读取_模拟进程重启() throws Exception {
    // 蓝图加粗句：流程状态必须持久化，不能只放内存。
    // 这里新建一个 service 实例（等价于重启后新建的 bean），只靠 DB 里的状态继续。
    // 时钟用系统默认 —— 与容器里那个 bean 同一个语义（重启后拿到的也是它）。
    var freshService = new FulfillmentRequestService(sql, Clock.systemDefaultZone());
    var reserved = toReserved(create("RST1"));
    var seen =
        access.run(
            actorA, "trade.fulfillment.update", () -> freshService.request(reserved.requestId()));
    assertThat(seen.request().status()).isEqualTo(FulfillmentRequestStatus.RESERVED);
    assertThat(seen.request().currentStep()).isEqualTo(FulfillmentStepKind.OUTBOUND);
    assertThat(seen.steps())
        .anyMatch(
            s ->
                s.stepKind() == FulfillmentStepKind.RESERVE
                    && s.status() == FulfillmentStepStatus.COMPLETED);
    var advanced =
        access.run(
            actorA,
            "trade.fulfillment.update",
            () ->
                freshService.advance(
                    reserved.requestId(),
                    new FulfillmentRequestStatusInput(
                        FulfillmentRequestStatus.OUTBOUND_PENDING,
                        FulfillmentStepKind.OUTBOUND,
                        seen.request().processVersion(),
                        7200),
                    user));
    assertThat(advanced.status()).isEqualTo(FulfillmentRequestStatus.OUTBOUND_PENDING);
  }

  // ================================================================ 3. 下游只登记、不假装

  @Test
  void 预占缺省是待回执_不是已锁定() throws Exception {
    var created = create("RES1");
    asA(
        () ->
            service.advance(
                created.requestId(),
                new FulfillmentRequestStatusInput(
                    FulfillmentRequestStatus.RESERVING,
                    FulfillmentStepKind.RESERVE,
                    0L,
                    3600),
                user));
    var row =
        asA(
            () ->
                service.requestReservation(
                    created.requestId(), new FulfillmentRequestReservationInput(null), user));
    assertThat(row.status())
        .as("没有 warehouse 回执就不许显示成「已锁定」——这正是 V116 踩过的坑")
        .isEqualTo(FulfillmentReservationStatus.REQUESTED);
    assertThat(row.lockedQty()).isNull();
    assertThat(row.reservationRef()).isEmpty();
  }

  @Test
  void 没有预占回执就不许说已锁定() throws Exception {
    var created = create("RES2");
    asA(
        () ->
            service.advance(
                created.requestId(),
                new FulfillmentRequestStatusInput(
                    FulfillmentRequestStatus.RESERVING,
                    FulfillmentStepKind.RESERVE,
                    0L,
                    3600),
                user));
    asA(
        () ->
            service.requestReservation(
                created.requestId(), new FulfillmentRequestReservationInput(null), user));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.advance(
                            created.requestId(),
                            new FulfillmentRequestStatusInput(
                                FulfillmentRequestStatus.RESERVED,
                                FulfillmentStepKind.OUTBOUND,
                                1L,
                                7200),
                            user)))
        .as("没有 warehouse 回执就推进到「已锁定预占」必须被拒")
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void 锁定数量不得超过需求量() throws Exception {
    var created = create("RES3");
    asA(
        () ->
            service.advance(
                created.requestId(),
                new FulfillmentRequestStatusInput(
                    FulfillmentRequestStatus.RESERVING,
                    FulfillmentStepKind.RESERVE,
                    0L,
                    3600),
                user));
    asA(
        () ->
            service.requestReservation(
                created.requestId(), new FulfillmentRequestReservationInput(null), user));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.acknowledgeReservation(
                            created.requestId(),
                            new FulfillmentRequestReservationAckInput(
                                FulfillmentReservationStatus.LOCKED,
                                "RSV-X",
                                new BigDecimal("80.000000"),
                                ""),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("超发");
  }

  @Test
  void 预占被拒必须写明原因() throws Exception {
    var created = create("RES4");
    asA(
        () ->
            service.advance(
                created.requestId(),
                new FulfillmentRequestStatusInput(
                    FulfillmentRequestStatus.RESERVING,
                    FulfillmentStepKind.RESERVE,
                    0L,
                    3600),
                user));
    asA(
        () ->
            service.requestReservation(
                created.requestId(), new FulfillmentRequestReservationInput(null), user));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.acknowledgeReservation(
                            created.requestId(),
                            new FulfillmentRequestReservationAckInput(
                                FulfillmentReservationStatus.REJECTED, "", null, " "),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("原因");
  }

  @Test
  void 同一回执重放不会把已拒翻成已锁() throws Exception {
    var created = create("RES5");
    asA(
        () ->
            service.advance(
                created.requestId(),
                new FulfillmentRequestStatusInput(
                    FulfillmentRequestStatus.RESERVING,
                    FulfillmentStepKind.RESERVE,
                    0L,
                    3600),
                user));
    asA(
        () ->
            service.requestReservation(
                created.requestId(), new FulfillmentRequestReservationInput(null), user));
    asA(
        () ->
            service.acknowledgeReservation(
                created.requestId(),
                new FulfillmentRequestReservationAckInput(
                    FulfillmentReservationStatus.REJECTED, "", null, "缺货"),
                user));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.acknowledgeReservation(
                            created.requestId(),
                            new FulfillmentRequestReservationAckInput(
                                FulfillmentReservationStatus.LOCKED,
                                "RSV-Y",
                                new BigDecimal("50.000000"),
                                ""),
                            user)))
        .hasMessageContaining("只有待回执");
  }

  @Test
  void 作业不许跳过RUNNING直接完成() throws Exception {
    var confirmed = toWorking(toReserved(create("WRK1")));
    UUID id = confirmed.requestId();
    asA(
        () ->
            service.advance(
                id,
                new FulfillmentRequestStatusInput(
                    FulfillmentRequestStatus.WORKING,
                    FulfillmentStepKind.WORK,
                    confirmed.processVersion(),
                    86400),
                user));
    asA(
        () ->
            service.assignWorkOrders(
                id,
                new FulfillmentRequestWorkOrderInput(
                    List.of(
                        new FulfillmentRequestWorkTask(
                            "WO-1",
                            FulfillmentWorkTaskType.PICK,
                            "WH-CN-SH",
                            "SKU-WRK1",
                            new BigDecimal("50.000000"),
                            null))),
                user));
    var work = asA(() -> service.request(id)).workOrders().get(0);
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.updateWorkOrder(
                            id,
                            work.id(),
                            new FulfillmentRequestWorkOrderStatusInput(
                                FulfillmentWorkStatus.DONE, "USR", ""),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已派工");
  }

  @Test
  void 能力声明显式报出下游未接通与不预占上游库存() throws Exception {
    var caps = asA(() -> service.capabilities());
    assertThat(caps.reservesUpstreamStock())
        .as("本模块只登记指令，不写 warehouse 库存 —— V116 踩过的坑")
        .isFalse();
    assertThat(caps.outboundOrderDispatched()).isFalse();
    assertThat(caps.logisticsOrderPlaced()).isFalse();
    assertThat(caps.processStatePersisted()).isTrue();
    assertThat(caps.monitoringDelivered()).isFalse();
    assertThat(caps.dimensions())
        .filteredOn(d -> d.key().equals("ORDER_SPLIT"))
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.available()).isFalse();
              assertThat(d.ownerModule()).contains("9.3.1.1");
            });
    assertThat(caps.dimensions())
        .filteredOn(d -> d.key().equals("STOCK_RESERVATION"))
        .singleElement()
        .satisfies(d -> assertThat(d.available()).isFalse());

    http.perform(
            get("/trade/fulfillment/requests/capabilities")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reservesUpstreamStock").value(false))
        .andExpect(jsonPath("$.processStatePersisted").value(true));
  }

  // ================================================================ 4. 金额 / 数量 / 时间刻度

  @Test
  void 金额与数量的刻度按numeric落库_断言读回刻度() throws Exception {
    create("SCL1");
    var row = read(idOf("SCL1"));
    assertThat(row.requestedQty()).isEqualTo(new BigDecimal("50.000000"));
    assertThat(row.requestedQty().scale()).isEqualTo(6);
    assertThat(row.estimatedHandlingAmount()).isEqualTo(new BigDecimal("12.3456"));
    assertThat(row.estimatedHandlingAmount().scale()).isEqualTo(4);

    // 超过 4 位的金额按 HALF_UP 到 4 位落库，不是截断
    asA(
        () ->
            service.create(
                new FulfillmentRequestInput(
                    "SCL2", "SO-S2", "L1", "SP-2", "CO-2", "AL-2", "RT-2", "SKU-2", "AMAZON",
                    "WH-CN-SH", ENT, "US", "CN-EAST", new BigDecimal("1.23456789"),
                    new BigDecimal("9.87654321"), "USD", "idem-s2", null, null, null, null, ""),
                user));
    var second = read(idOf("SCL2"));
    assertThat(second.requestedQty()).isEqualTo(new BigDecimal("1.234568"));
    assertThat(second.requestedQty().scale()).isEqualTo(6);
    assertThat(second.estimatedHandlingAmount()).isEqualTo(new BigDecimal("9.8765"));
    assertThat(second.estimatedHandlingAmount().scale()).isEqualTo(4);
  }

  /**
   * 时间必须写对：OffsetDateTime 绑定 + {@code ?::timestamptz}。
   *
   * <p>用 {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 -28800s，
   * 而超时判定完全依赖 deadline，所以这里断言**写回的值**，不是「没抛异常」。
   */
  @Test
  void 时间写回正确_时区不偏移() throws Exception {
    var created =
        asA(
            () ->
                service.create(
                    new FulfillmentRequestInput(
                        "TIM1", "SO-T", "L1", "SP-T", "CO-T", "AL-T", "RT-T", "SKU-T", "AMAZON",
                        "WH-CN-SH", ENT, "US", "CN-EAST", new BigDecimal("1.000000"), null, "USD",
                        "idem-t", 60, 60, 60, 60, ""),
                    user));
    assertThat(created.requestedAt()).isNotNull();
    assertThat(created.deadlineAt()).isEqualTo(created.requestedAt().plusSeconds(60));

    var dbRequested =
        asA(
                () ->
                    sql.fetchOne(
                            "select requested_at from fulfillment_request where id = ?",
                            created.requestId())
                        .get("requested_at", OffsetDateTime.class));
    var dbDeadline =
        asA(
                () ->
                    sql.fetchOne(
                            "select deadline_at from fulfillment_request where id = ?",
                            created.requestId())
                        .get("deadline_at", OffsetDateTime.class));
    assertThat(dbRequested).isEqualTo(created.requestedAt());
    assertThat(dbDeadline).isEqualTo(created.deadlineAt());
    // 真正的判据：截止时刻比「此刻」晚 60 秒，不是早 8 小时
    assertThat(dbDeadline).isAfter(OffsetDateTime.now().plusSeconds(30));
    assertThat(read(created.requestId()).overdue())
        .as("刚建单的单子不该是超时态（时区偏了 8 小时的话这里会挂）")
        .isFalse();
  }

  // ================================================================ 5. 跨时钟域回归（V211）

  /**
   * 时钟倒挂：<b>JVM 时钟比 DB 时钟快 5 秒</b>时，一次<b>完全合法</b>的「履约步完成」
   * 必须写得进去，且写回的值自洽。
   *
   * <p><b>旧实现为什么会被 DB 随机拒掉</b>：{@code fulfillment_request_step} 上有表级约束
   * {@code CHECK (finished_at IS NULL OR finished_at >= started_at)}，而旧实现里
   * {@code started_at} 取 <b>JVM 墙钟</b>、{@code finished_at} 取 <b>DB 时钟</b> ——
   * 同一行的两个字段分属两个时钟源，JVM 快几十微秒就违约。
   * 生产后果不是「测试红」，是<b>履约流程卡在 RUNNING</b>。
   *
   * <p><b>本用例怎么做到确定性</b>：不靠运气撞那几十微秒的窗口，而是把 JVM 时钟
   * （注入的 {@link Clock}）整体前移 5 秒，<b>把「JVM 比 DB 快」这个前提钉死</b>，
   * 再走真实写路径（建单 → 发起预占 → 回执完成）。
   * 修好之后：{@code started_at} 落在 <b>DB 时钟</b>上，JVM 那 5 秒的领先<b>渗不进去</b>，
   * 完成写得进去，且 {@code finished_at >= started_at}、{@code elapsed_seconds} 与两者自洽。
   *
   * <p>为什么不能直接「显式写一个比 DB 时钟未来几十毫秒的 started_at 再去完成」：
   * 修好之后这一对时间戳<b>都由 DB 写</b>，手工写坏的那一行就是<b>真的坏行</b>，
   * 完成它理应被拒 —— 那正是下面 {@code 结束早于开始的行仍然被拒} 那个用例在守的东西。
   * 跨时钟域这个缺陷要验的是「环境里 JVM 与 DB 快慢不同时，写入仍然成立」。
   */
  @Test
  void 时钟倒挂_履约步完成仍然写得进去且耗时自洽() throws Exception {
    // JVM 时钟整体前移 5 秒 = 生产上击穿约束的那个条件（JVM 比 DB 快），
    // 用注入的 Clock 钉死，不靠运气撞几十微秒的窗口。
    var shifted = Clock.offset(Clock.systemUTC(), Duration.ofSeconds(5));
    var fast = new FulfillmentRequestService(sql, shifted);

    var created = asA(() -> fast.create(input("CLOCK1"), user));

    // 前提钉死：此刻 JVM 时钟确实比 DB 时钟快 ~5 秒（否则这个用例什么都没验到）。
    var jvmNow = OffsetDateTime.ofInstant(shifted.instant(), ZoneId.systemDefault());
    var dbBefore = dbNow();
    assertThat(jvmNow)
        .as("JVM 时钟必须真的比 DB 时钟快，否则这个用例验不到任何东西")
        .isAfter(dbBefore.plusSeconds(4));
    // 建单回执里的时刻是**库里写下的**那一份 —— 它也必须落在 DB 时钟上。
    assertThat(Math.abs(Duration.between(created.requestedAt(), dbBefore).getSeconds()))
        .as("requested_at 必须取 DB 时钟")
        .isLessThan(2);

    // 推进到「预占中」并发起预占指令 —— 这一步会写下 RUNNING 的流程步骤行。
    asA(
        () ->
            fast.advance(
                created.requestId(),
                new FulfillmentRequestStatusInput(
                    FulfillmentRequestStatus.RESERVING,
                    FulfillmentStepKind.RESERVE,
                    created.processVersion(),
                    3600),
                user));
    asA(() -> fast.requestReservation(created.requestId(), new FulfillmentRequestReservationInput(null), user));

    var running = stepRow(created.requestId(), FulfillmentStepKind.RESERVE);
    assertThat(running.status()).isEqualTo("RUNNING");
    assertThat(Math.abs(Duration.between(running.startedAt(), dbNow()).getSeconds()))
        .as("started_at 必须取 DB 时钟")
        .isLessThan(2);
    assertThat(running.startedAt())
        .as("JVM 快 5 秒的那个时刻不许渗进 started_at")
        .isBefore(jvmNow.minusSeconds(3));

    // 完成这一步（warehouse 回执）—— 修复前这一句会被表级约束拒掉。
    asA(
        () ->
            fast.acknowledgeReservation(
                created.requestId(),
                new FulfillmentRequestReservationAckInput(
                    FulfillmentReservationStatus.LOCKED,
                    "RSV-CLOCK1",
                    new BigDecimal("50.000000"),
                    ""),
                user));

    // ↓ 断言的是**写回库里的值**（直查库，不看内存对象）。
    var done = stepRow(created.requestId(), FulfillmentStepKind.RESERVE);
    assertThat(done.status()).isEqualTo("COMPLETED");
    assertThat(done.finishedAt()).as("完成时刻不能早于开始时刻").isAfterOrEqualTo(done.startedAt());
    assertThat(done.elapsed()).as("耗时不能是负数").isNotNegative();
    assertThat(done.elapsed())
        .as("耗时必须与写回的两个时间戳自洽")
        .isEqualByComparingTo(expectedElapsed(done.startedAt(), done.finishedAt()));
    assertThat(Math.abs(Duration.between(done.finishedAt(), dbNow()).getSeconds()))
        .as("finished_at 必须取 DB 时钟")
        .isLessThan(2);
  }

  /**
   * 「结束早于开始」的行仍然被拒 —— 证明那条 CHECK <b>没有被放宽</b>。
   *
   * <p>修这个缺陷<b>不是</b>把约束松掉：约束防的是「结束早于开始」这个<b>真</b>错误，
   * 松掉它等于把真 bug 放进来。这里直接往留痕表塞一条这样的行，DB 必须拒。
   */
  @Test
  void 结束早于开始的行仍然被拒_约束没有被放宽() throws Exception {
    var created = create("CHK1");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "insert into fulfillment_request_step (id, tenant_id, request_id,"
                                + " step_kind, attempt, status, work_order_count, started_at,"
                                + " finished_at, elapsed_seconds)"
                                + " values (?,?,?,'TRACK',9,'COMPLETED',0,"
                                + " clock_timestamp() + interval '1 hour', clock_timestamp(),0.000)",
                            UUID.randomUUID(),
                            actorA.tenantId(),
                            created.requestId())))
        .as("结束早于开始的流程步骤行必须被表级约束拒掉")
        .hasMessageContaining("fulfillment_request_step");

    // 约束的原文也钉住：它必须仍然是 finished_at >= started_at。
    var defs =
        asA(
            () ->
                sql.fetch(
                    "select pg_get_constraintdef(oid) d from pg_constraint"
                        + " where conrelid = 'fulfillment_request_step'::regclass and contype = 'c'"));
    assertThat(defs.map(r -> r.get("d", String.class)))
        .as("CHECK (finished_at >= started_at) 必须原样还在")
        .anyMatch(d -> d.contains("finished_at") && d.contains("started_at") && d.contains(">="));
  }

  /** DB 自己的时钟：判「这一行的时间戳落在哪个时钟域上」用它，不拿 JVM 墙钟比。 */
  OffsetDateTime dbNow() {
    return asA(() -> sql.fetchOne("select clock_timestamp() as ts").get("ts", OffsetDateTime.class));
  }

  /** 流程步骤行的**写回值**（直查库，不经过 DTO 映射）。 */
  record StepRow(String status, OffsetDateTime startedAt, OffsetDateTime finishedAt, BigDecimal elapsed) {}

  StepRow stepRow(UUID requestId, FulfillmentStepKind kind) {
    var r =
        asA(
            () ->
                sql.fetchOne(
                    "select status, started_at, finished_at, elapsed_seconds"
                        + " from fulfillment_request_step"
                        + " where request_id = ? and step_kind = ? and attempt = 1",
                    requestId,
                    kind.name()));
    assertThat(r).as("流程步骤行必须存在").isNotNull();
    return new StepRow(
        r.get("status", String.class),
        r.get("started_at", OffsetDateTime.class),
        r.get("finished_at", OffsetDateTime.class),
        r.get("elapsed_seconds", BigDecimal.class));
  }

  /** 由两个时间戳算出应有的耗时（与 DB 的 round(...,3) 同一种算法：毫秒三位、half-up）。 */
  static BigDecimal expectedElapsed(OffsetDateTime started, OffsetDateTime finished) {
    return BigDecimal.valueOf(Duration.between(started, finished).toNanos())
        .divide(BigDecimal.valueOf(1_000_000_000L), 3, RoundingMode.HALF_UP);
  }

  // ================================================================ 6. 多租户三断言
  @Test
  void 断言1_两租户互不可见() throws Exception {
    var mine = create("ISOA1");
    var theirs = asB(() -> service.create(input("ISOB1"), user));

    var pageA =
        asA(
            () ->
                service.pageRequests(
                    new FulfillmentRequestQuery(
                        null, null, null, null, null, null, null, null, null, null, null, null,
                        null, 0, 200)));
    assertThat(pageA.items())
        .extracting(FulfillmentRequestRow::requestCode)
        .contains("ISOA1")
        .doesNotContain("ISOB1");

    // 更强的一格：按 id 直查对方的履约请求也拿不到
    assertThatThrownBy(() -> asA(() -> service.request(theirs.requestId())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在");
    assertThatThrownBy(() -> asB(() -> service.request(mine.requestId())))
        .isInstanceOf(ResponseStatusException.class);

    // 超时记录同样隔离（B 的页里不该出现 A 的请求号）
    assertThat(asB(() -> service.pageTimeouts(null, null, 0, 200)).items())
        .extracting(FulfillmentRequestTimeoutRow::requestId)
        .doesNotContain(mine.requestId());
    // 可恢复清单同样隔离
    assertThat(asB(() -> service.pageResumable(0, 200)).items())
        .extracting(FulfillmentRequestRow::requestCode)
        .doesNotContain("ISOA1");
  }

  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    // 刻意**不**走 asA：asA 会把 app.current_tenant 绑上，那测的就不是「未绑定」了。
    assertThat(sql.fetch("select id from fulfillment_request").size())
        .as("未绑定租户时不应看到任何行")
        .isZero();

    var mine = create("FK1");
    http.perform(
            get("/trade/fulfillment/requests/{id}", mine.requestId())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk());

    // B 的会话 + 伪造指向 A 的头 → 依然 404
    http.perform(
            get("/trade/fulfillment/requests/{id}", mine.requestId())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());

    // 写入路径同样不认头：B 会话伪造 A 的头，落库仍属 B
    http.perform(
            post("/trade/fulfillment/requests")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"requestCode\":\"FKW\",\"orderRef\":\"SO-FW\","
                        + "\"allocationDecisionRef\":\"AL-FW\","
                        + "\"skuRef\":\"SKU-FW\",\"warehouseRef\":\"WH-CN-SH\","
                        + "\"legalEntityCode\":\"CN-ENT-A\","
                        + "\"destinationCountryCode\":\"US\",\"requestedQty\":10}"))
        .andExpect(status().isOk());
    assertThat(
            asA(
                    () ->
                        service.pageRequests(
                            new FulfillmentRequestQuery(
                                "FKW", null, null, null, null, null, null, null, null, null,
                                null, null, null, 0, 10)))
                .total())
        .as("伪造头不得把数据写进别的租户")
        .isZero();
    assertThat(
            asB(
                    () ->
                        service.pageRequests(
                            new FulfillmentRequestQuery(
                                "FKW", null, null, null, null, null, null, null, null, null,
                                null, null, null, 0, 10)))
                .total())
        .isEqualTo(1L);

    // 提交后租户上下文必须已清空
    assertThat(sql.fetch("select id from fulfillment_request").size())
        .as("提交后租户上下文必须已清空")
        .isZero();
  }

  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    asA(() -> service.create(input("POL1"), user));
    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证第 5 次读到的仍然是 B 而不是上一轮残留的 A。
    for (int i = 0; i < 5; i++) {
      var pageA =
          asA(
              () ->
                  service.pageRequests(
                      new FulfillmentRequestQuery(
                          null, null, null, null, null, null, null, null, null, null, null, null,
                          null, 0, 200)));
      var pageB =
          asB(
              () ->
                  service.pageRequests(
                      new FulfillmentRequestQuery(
                          "POL1", null, null, null, null, null, null, null, null, null, null,
                          null, null, 0, 200)));
      assertThat(pageA.items())
          .extracting(FulfillmentRequestRow::requestCode)
          .as("第 %d 轮 A", i)
          .contains("POL1");
      assertThat(pageB.total()).as("第 %d 轮 B", i).isZero();
      assertThat(sql.fetch("select id from fulfillment_request").size())
          .as("第 %d 轮事务外", i)
          .isZero();
    }
    assertThat(asB(() -> service.pageResumable(0, 200)).items())
        .extracting(FulfillmentRequestRow::requestCode)
        .doesNotContain("POL1");
  }

  @Test
  void 窄函数自己判租户_不靠外层RLS() throws Exception {
    // SECURITY DEFINER 的函数体以属主身份执行、**绕过 RLS**，所以必须自己判租户。
    // 这里用超管连接（本来就绕过 RLS）直接调窄函数，验证它仍然拒绝跨租户操作。
    var mine = create("BYP1");
    assertThatThrownBy(
            () ->
                privileged(
                    b,
                    c -> {
                      try (var st = c.createStatement()) {
                        st.execute(
                            "select advance_fulfillment_request_status('"
                                + mine.requestId()
                                + "', 'RESERVING', 'RESERVE', 0, '"
                                + user
                                + "', 3600)");
                      }
                    }))
        .as("超管连接 + 别的租户上下文，必须仍然被窄函数拒绝")
        .hasMessageContaining("不属于本租户");
  }

  @Test
  void 只读权限不许推进流程() throws Exception {
    var mine = create("RO1");
    http.perform(
            post("/trade/fulfillment/requests/{id}/advance", mine.requestId())
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"status\":\"RESERVING\",\"currentStep\":\"RESERVE\",\"expectedVersion\":0}"))
        .andExpect(status().isForbidden());
  }
}
