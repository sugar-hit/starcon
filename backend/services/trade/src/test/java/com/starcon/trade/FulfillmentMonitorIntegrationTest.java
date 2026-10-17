package com.starcon.trade;

import static com.starcon.trade.FulfillmentMonitorModels.*;
import static com.starcon.trade.FulfillmentRequestModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 9.3.3.2 履约流程监控。
 *
 * <p>本测试是 <b>V154 那三道闸门的独立复验</b>：不采信任何外部结论，逐条在真实
 * PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不只直连数据库，以便同时验证
 * 「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>为什么每个用例都开一个自己的租户</b>：一个容器里所有用例共享同一个库，
 * 用共享租户的话，「A 用例把 RESERVE 默认档调成 1 秒」会立刻改变「B 用例」的判定，
 * 测试就成了「看谁先跑」。RLS 本来就按租户隔离，给每个用例一个专属租户，
 * 检出与阈值都拿到真正的隔离。多租户三断言是唯一必须用两个固定租户的用例。
 *
 * <p><b>四组断言</b>：
 *
 * <ol>
 *   <li><b>加粗句（先查证再重试）</b>：未查证不许处置、查证证伪不许重试、
 *       拿别的案例的查证结论不许重试、并发重试只允许一条、只 SENT 不许结案。</li>
 *   <li><b>超时判定用数据库时钟</b>：阈值未到不检出；到了则断言<b>具体差值</b>
 *       （不是一个布尔）；租户阈值真的生效而不是硬编码常量；+08:00 偏移写回逐微秒相等。</li>
 *   <li><b>下游只登记、不假装</b>：释放登记缺省 REQUESTED、没锁过货不许释放、
 *       工作台未推送。</li>
 *   <li><b>多租户三断言</b>：两租户互不可见 / 伪造租户头无效 /
 *       连接池归还后不串租户。</li>
 * </ol>
 *
 * <p><b>用例之间为什么不靠「清库」隔离</b>：verification / event 是 append-only，
 * 案例 / 处置的 DELETE 也被触发器挡住，连特权连接都删不掉。所以每个用例用
 * <b>自己的 requestCode 前缀</b>隔离，断言也一律按该前缀收窄，而不是断言「全表为空」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FulfillmentMonitorIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      Stream.of("fulfillment")
          .flatMap(p -> Stream.of("read", "create", "update", "delete")
              .map(op -> "trade." + p + "." + op))
          .toList();

  static final String ENT = "CN-ENT-A";

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
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
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, List.of(grant("ALL", List.of("trade.fulfillment.read")))));
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

  @Autowired FulfillmentMonitorService monitor;
  @Autowired FulfillmentRequestService requests;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  /** 固定租户 A / B：只有多租户三断言用。 */
  AccessService.Access actorA;
  AccessService.Access actorB;

  /** 本用例专属租户：让检出与阈值拿到真正的隔离，不靠用例执行顺序。 */
  UUID tenantT;
  String bearerT;

  <T> T asA(Supplier<T> work) {
    return access.run(actorA, "trade.fulfillment.update", work);
  }

  <T> T asB(Supplier<T> work) {
    return access.run(actorB, "trade.fulfillment.update", work);
  }

  <T> T asT(Supplier<T> work) {
    return access.run(actors.get(bearerT), "trade.fulfillment.update", work);
  }

  @BeforeEach
  void seed() {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    tenantT = UUID.randomUUID();
    bearerT = "Bearer case-" + tenantT;
    actors.put(bearerT, actor(user, tenantT, List.of(grant("ALL", allPermissions))));
  }

  // ================================================================ 工具

  /**
   * 以**特权连接**（迁移用户）执行一段 SQL，并把 {@code app.current_tenant} 绑成给定租户。
   *
   * <p>默认回滚：探针要的就是「看一眼就撤销」。铺夹具必须传 {@code commit=true}，
   * 否则那条失败凭据跟着回滚，扫描当然什么都检不出来 —— 这是个看起来像 bug 的 bug。
   */
  void privileged(UUID tenant, JdbcRunnable body, boolean commit) throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      c.setAutoCommit(false);
      try {
        c.createStatement().execute("select set_config('app.current_tenant', '" + tenant + "', true)");
        body.run(c);
        if (commit) {
          c.commit();
        } else {
          c.rollback();
        }
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

  void privileged(UUID tenant, JdbcRunnable body) throws Exception {
    privileged(tenant, body, false);
  }

  interface JdbcRunnable {
    void run(java.sql.Connection c) throws Exception;
  }

  /** 建一张履约请求：RESERVE 阈值 1 秒（V152 的 CHECK 是 &gt;0，1 秒是最短可测窗口）。 */
  FulfillmentRequestSaved createRequest(String code) {
    return asT(
        () ->
            requests.create(
                new FulfillmentRequestInput(
                    code, "SO-" + code, "L1", "SPLIT-" + code, "CONS-" + code, "ALLOC-" + code,
                    "ROUTE-" + code, "SKU-" + code, "AMAZON", "WH-CN-SH", ENT, "US", "CN-EAST",
                    new BigDecimal("50.000000"), new BigDecimal("12.3456"), "USD", "idem-" + code,
                    1, 7200, 14400, 86400, "夹具备注"),
                user));
  }

  /** 把流程推进到 RESERVING（V152 允许 DRAFT → RESERVING）。 */
  void toReserving(FulfillmentRequestSaved saved) {
    asT(
        () -> {
          requests.advance(
              saved.requestId(),
              new FulfillmentRequestStatusInput(
                  FulfillmentRequestStatus.RESERVING, FulfillmentStepKind.RESERVE,
                  saved.processVersion(), null),
              user);
          return null;
        });
  }

  /** 走完一次预占并真的锁定（7.2 的回执），这样才有「可释放」的对象。 */
  void lockReservation(String code) {
    var saved = createRequest(code);
    toReserving(saved);
    var reservation =
        asT(
            () ->
                requests.requestReservation(
                    saved.requestId(),
                    new FulfillmentRequestReservationInput("idem-rsv-" + code),
                    user));
    asT(
        () ->
            requests.acknowledgeReservation(
                saved.requestId(),
                new FulfillmentRequestReservationAckInput(
                    FulfillmentReservationStatus.LOCKED, "RSV-REAL", new BigDecimal("50.000000"),
                    null),
                user));
  }

  /**
   * 预占被拒 + 留一条 INSUFFICIENT_STOCK 失败凭据。
   *
   * <p>检出依据是 9.3.3.1 的**真实**留痕表，不是推断。产出两条异常
   * （RESERVATION_FAILED 与 INSUFFICIENT_STOCK）—— 预占失败与库存不足在现实中
   * 常同时成立，而它们该走不同的处置动作，所以不合并。
   */
  void seedInsufficientStock(String code) throws Exception {
    var saved = createRequest(code);
    toReserving(saved);
    var reservation =
        asT(
            () ->
                requests.requestReservation(
                    saved.requestId(),
                    new FulfillmentRequestReservationInput("idem-isk-" + code),
                    user));
    asT(
        () ->
            requests.acknowledgeReservation(
                saved.requestId(),
                new FulfillmentRequestReservationAckInput(
                    FulfillmentReservationStatus.REJECTED, "", null, "仓库无可用库存"),
                user));
    // 失败留痕落在 WORK 步上：RESERVE/attempt=1 已被 requestReservation 占掉
    // （同一步同一次尝试只允许一行），而检出按 request_id + failure_code 联接，与步种无关。
    privileged(
        tenantT,
        c -> {
          try (var s = c.createStatement()) {
            s.execute(
                "insert into fulfillment_request_step(id, tenant_id, request_id, step_kind,"
                    + " attempt, status, started_at, finished_at, failure_code, failure_reason)"
                    + " select gen_random_uuid(), tenant_id, id, 'WORK', attempt, 'FAILED',"
                    + " clock_timestamp(), clock_timestamp(), 'INSUFFICIENT_STOCK',"
                    + " '仓库无可用库存' from fulfillment_request where id = '"
                    + saved.requestId() + "'");
          }
        },
        true);
    assertThat(reservation.id()).isNotNull();
  }

  /** B 租户的同构夹具（固定租户 b，只给多租户三断言用）。 */
  void seedInsufficientStockB(String code) throws Exception {
    seedRejectedOn(actorB, b, code, "WH-CN-BJ", "CN-NORTH", "B 租户缺货");
  }

  /** 在指定租户上造一张「预占被拒 + 缺货失败留痕」的流程。 */
  void seedRejectedOn(
      AccessService.Access actor, UUID tenant, String code, String warehouse, String region,
      String reason) throws Exception {
    access.run(
        actor,
        "trade.fulfillment.update",
        () -> {
          requests.create(
              new FulfillmentRequestInput(
                  code, "SO-" + code, "L1", "SPLIT-" + code, "CONS-" + code, "ALLOC-" + code,
                  "ROUTE-" + code, "SKU-" + code, "AMAZON", warehouse, ENT, "US", region,
                  new BigDecimal("20.000000"), null, "USD", "idem-" + code,
                  1, 7200, 14400, 86400, code + " 夹具"),
              user);
          return null;
        });
    privileged(
        tenant,
        c -> {
          try (var s = c.createStatement()) {
            s.execute(
                "insert into fulfillment_request_step(id, tenant_id, request_id, step_kind,"
                    + " attempt, status, started_at, finished_at, failure_code, failure_reason)"
                    + " values (gen_random_uuid(), '" + tenant + "',"
                    + " (select id from fulfillment_request where request_code = '" + code + "'),"
                    + " 'WORK', 1, 'FAILED', clock_timestamp(), clock_timestamp(),"
                    + " 'INSUFFICIENT_STOCK', '" + reason + "')");
          }
        },
        true);
  }

  /**
   * 绕过服务层直接往库里塞一条 RETRY 处置（特权连接，默认回滚）。
   *
   * <p>存在的理由：服务层的 if 只是体面，真正的闸门在 V154 的触发器与唯一索引，
   * 要验它们就必须<b>跳过服务层</b>直接打库。列与表达式只在这里写一遍。
   *
   * @param verificationId 引用哪条查证；刻意传别的案例的就能验「张冠李戴」
   */
  void probeInsertRetry(String dispositionNo, UUID caseId, UUID verificationId) throws Exception {
    probeInsertRetry(dispositionNo, caseId, verificationId, false);
  }

  /**
   * @param commit 落到库里还是撤销。并发与「撤回后重新发起」这两处**必须**提交 ——
   *     默认回滚的话每条插入都被撤销，唯一索引永远不会撞上，
   *     「并发只成功一条」会变成四条全过这种假绿。
   */
  void probeInsertRetry(
      String dispositionNo, UUID caseId, UUID verificationId, boolean commit) throws Exception {
    privileged(
        tenantT,
        c -> {
          try (var s = c.createStatement()) {
            s.execute(
                "insert into fulfillment_monitor_disposition("
                    + "id, tenant_id, disposition_no, case_id, request_id, attempt, action_kind,"
                    + " status, retry_grant_seconds, verification_id, note,"
                    + " created_by_membership_id, idempotency_key)"
                    + " select gen_random_uuid(), mc.tenant_id, '" + dispositionNo + "', mc.id,"
                    + " mc.request_id, mc.attempt, 'RETRY', 'PENDING', 3600, "
                    // verification_id 可空：空值要写成 NULL 而不是 ''，
                    // 否则撞 uuid 类型的语法错误，测的就不是闸门了。
                    + (verificationId == null ? "null" : "'" + verificationId + "'")
                    + ", '绕过服务层直接插入的重试', gen_random_uuid(),"
                    + " 'idem-" + dispositionNo + "'"
                    + " from fulfillment_monitor_case mc where mc.id = '" + caseId + "'");
          }
        },
        commit);
  }

  /** 查证坐实（走正常的 DOWNSTREAM_ACK 范围：查过下游才允许重试）。 */
  FulfillmentMonitorVerifySaved confirm(UUID caseId, long version, String key) {
    return asT(
        () ->
            monitor.verify(
                caseId,
                new FulfillmentMonitorVerifyInput(
                    version, FulfillmentMonitorConclusion.CONFIRMED,
                    FulfillmentMonitorVerifiedScope.DOWNSTREAM_ACK, "RSV-ACK-" + key,
                    "已向 7.2 查证回执：预占确实没有落地，货没被锁走", null, key),
                user));
  }

  UUID confirmedVerificationId(UUID caseId) {
    return asT(
        () ->
            sql.fetchOne(
                    "select id from fulfillment_monitor_verification where case_id = ?"
                        + " and conclusion = 'CONFIRMED'",
                    caseId)
                .get("id", UUID.class));
  }

  long caseVersion(UUID caseId) {
    return asT(
            () ->
                sql.fetchOne(
                        "select monitor_version from fulfillment_monitor_case where id = ?", caseId)
                    .get("monitor_version", Long.class))
        .longValue();
  }

  FulfillmentMonitorScanResult scan() {
    return asT(() -> monitor.scan(new FulfillmentMonitorScanInput(null, null, null, null), user));
  }

  FulfillmentMonitorScanResult scanAsOf(int seconds) {
    return asT(
        () -> monitor.scan(new FulfillmentMonitorScanInput(null, null, null, seconds), user));
  }

  /** 取某次扫描里某一类异常的案例条目。 */
  FulfillmentMonitorScanEntry entryOf(FulfillmentMonitorScanResult result, FulfillmentMonitorAnomalyKind kind) {
    return result.entries().stream()
        .filter(e -> e.anomalyKind() == kind)
        .findFirst()
        .orElseThrow(() -> new AssertionError("本次扫描没有检出 " + kind + "：" + result.entries()));
  }

  /** 按单号取案例清单第一条（用例靠 requestCode 前缀互相隔离）。 */
  FulfillmentMonitorCaseRow caseOf(String requestCode) {
    return asT(
            () ->
                monitor.pageCases(
                        new FulfillmentMonitorCaseQuery(
                            null, requestCode, null, null, null, null, null, null, null, null,
                            null, null, 0, 50))
                    .items()
                    .stream()
                    .findFirst()
                    .orElse(null));
  }

  FulfillmentMonitorCaseRow caseOfB(String requestCode) {
    return access.run(
        actorB,
        "trade.fulfillment.update",
        () ->
            monitor.pageCases(
                    new FulfillmentMonitorCaseQuery(
                        null, requestCode, null, null, null, null, null, null, null, null, null,
                        null, 0, 50))
                .items()
                .stream()
                .findFirst()
                .orElseThrow());
  }

  int countCases() {
    return sql.fetchOne("select count(*) c from fulfillment_monitor_case").get("c", Integer.class);
  }

  /**
   * 当前连接上生效的租户上下文。
   *
   * <p>注意：PostgreSQL 的 {@code current_setting(name, true)} 在未设置时返回**空串**
   * 而不是 NULL，所以断言用 {@code isBlank()} —— 两种表示都叫「已清空」。
   */
  String effectiveTenant() {
    return sql.fetchOne("select current_setting('app.current_tenant', true) t")
        .get("t", String.class);
  }

  // ==========================================================================
  // 断言 1：加粗句 —— 超时不是失败，先查证再决定重试还是释放预占
  // ==========================================================================

  @Test
  void 未查证的异常不许发起任何处置() throws Exception {
    seedInsufficientStock("MO-NOVERIFY");
    var entry = entryOf(scan(), FulfillmentMonitorAnomalyKind.INSUFFICIENT_STOCK);
    var detail = asT(() -> monitor.caseDetail(entry.caseId()));
    assertThat(detail.monitorCase().status()).isEqualTo(FulfillmentMonitorCaseStatus.OPEN);
    assertThat(detail.retryAllowed()).as("未查证时明确告诉界面不能重试").isFalse();
    assertThat(detail.retryBlockedReason())
        .as("必须写清差什么，不让操作员从 409 报文里反推")
        .contains("查证");

    // 服务层前置校验：不带 verificationId 直接拒。
    assertThatThrownBy(
            () ->
                asT(
                    () ->
                        monitor.dispose(
                            entry.caseId(),
                            new FulfillmentMonitorDispositionInput(
                                caseVersion(entry.caseId()), FulfillmentMonitorActionKind.RETRY,
                                null, 3600, null, null, null, null, "无查证就重试", "idem-nv"),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("先查证");

    // DB 闸门（绕过服务层）：案例还是 OPEN，8.4 节的触发器必须挡住。
    assertThatThrownBy(() -> probeInsertRetry("PROBE-NV", entry.caseId(), null))
        .hasMessageContaining("先查证");
  }

  @Test
  void 查证证伪的案例不能发起重试() throws Exception {
    seedInsufficientStock("MO-NOTCONFIRMED");
    var entry = entryOf(scan(), FulfillmentMonitorAnomalyKind.INSUFFICIENT_STOCK);
    // 先坐实一次（案例落到 VERIFIED），再补一条证伪记录用于下面的 DB 闸门。
    confirm(entry.caseId(), 0L, "idem-nc-first");
    privileged(
        tenantT,
        c -> {
          try (var s = c.createStatement()) {
            s.execute(
                "insert into fulfillment_monitor_verification("
                    + "id, tenant_id, case_id, verification_no, conclusion, verified_scope,"
                    + " evidence_ref, finding, verified_at, verified_by_membership_id,"
                    + " idempotency_key) values (gen_random_uuid(), '" + tenantT + "', '"
                    + entry.caseId() + "', 'V-NC', 'NOT_CONFIRMED', 'DOWNSTREAM_ACK',"
                    + " 'RSV-LATE', '回执晚到，预占其实已落地', clock_timestamp(), '"
                    + user + "', 'idem-nc-second')");
          }
        },
        true);
    var fakeVerification =
        asT(
                () ->
                    sql.fetchOne(
                            "select id from fulfillment_monitor_verification where case_id = ?"
                                + " and conclusion = 'NOT_CONFIRMED'",
                            entry.caseId())
                        .get("id", UUID.class))
            .toString();

    // 案例已 VERIFIED（8.4 放行），但引用的查证结论是证伪 ⇒ 8.1 必须拒。
    assertThatThrownBy(
            () ->
                probeInsertRetry("PROBE-NC", entry.caseId(), UUID.fromString(fakeVerification)))
        .hasMessageContaining("不允许发起重试");
  }

  @Test
  void 查证结论为证伪的案例直接落到已证伪而不是已查证() throws Exception {
    seedInsufficientStock("MO-DISMISSED");
    var entry = entryOf(scan(), FulfillmentMonitorAnomalyKind.INSUFFICIENT_STOCK);
    var saved =
        asT(
            () ->
                monitor.verify(
                    entry.caseId(),
                    new FulfillmentMonitorVerifyInput(
                        0L, FulfillmentMonitorConclusion.NOT_CONFIRMED,
                        FulfillmentMonitorVerifiedScope.DOWNSTREAM_ACK, "RSV-ACK-OK",
                        "下游回执显示预占其实已落地，只是回执晚到", null, "idem-dismiss"),
                    user));
    assertThat(saved.caseStatus()).isEqualTo(FulfillmentMonitorCaseStatus.DISMISSED);
    var detail = asT(() -> monitor.caseDetail(entry.caseId()));
    assertThat(detail.retryAllowed()).as("证伪的异常绝不许重试").isFalse();
    assertThat(detail.retryBlockedReason()).contains("证伪");
  }

  @Test
  void 只看本地状态不足以支持重试() throws Exception {
    seedInsufficientStock("MO-LOCALONLY");
    var entry = entryOf(scan(), FulfillmentMonitorAnomalyKind.INSUFFICIENT_STOCK);
    assertThatThrownBy(
            () ->
                asT(
                    () ->
                        monitor.verify(
                            entry.caseId(),
                            new FulfillmentMonitorVerifyInput(
                                0L, FulfillmentMonitorConclusion.CONFIRMED,
                                FulfillmentMonitorVerifiedScope.LOCAL_STATE_ONLY, "",
                                "本地状态没变", null, "idem-local"),
                            user)))
        .hasMessageContaining("只看本地状态不足以支持重试");
  }

  @Test
  void 拿别的案例的查证结论去重试本案例过不去() throws Exception {
    seedInsufficientStock("MO-CROSS-A");
    seedInsufficientStock("MO-CROSS-B");
    scan();
    var firstCase = caseOf("MO-CROSS-A");
    var secondCase = caseOf("MO-CROSS-B");
    assertThat(firstCase).isNotNull();
    assertThat(secondCase).isNotNull();

    confirm(firstCase.id(), 0L, "idem-cross-1");
    confirm(secondCase.id(), 0L, "idem-cross-2");
    var aVerification = confirmedVerificationId(firstCase.id());

    // 第二条案例也查证坐实，但它引用的 verification_id 属于**第一条**案例。
    assertThatThrownBy(
            () -> probeInsertRetry("PROBE-XCROSS", secondCase.id(), aVerification))
        .hasMessageContaining("不是同一个");
  }

  @Test
  void 同一流程同一次尝试只允许一条未取消的重试() throws Exception {
    seedInsufficientStock("MO-RETRYONCE");
    var entry = entryOf(scan(), FulfillmentMonitorAnomalyKind.INSUFFICIENT_STOCK);
    confirm(entry.caseId(), 0L, "idem-retryonce");
    var verificationId = confirmedVerificationId(entry.caseId());

    var first =
        asT(
            () ->
                monitor.dispose(
                    entry.caseId(),
                    new FulfillmentMonitorDispositionInput(
                        caseVersion(entry.caseId()), FulfillmentMonitorActionKind.RETRY,
                        verificationId, 7200, null, null, null, null,
                        "查证坐实下游没动，重试这一步", "idem-retry-1"),
                    user));
    assertThat(first.actionKind()).isEqualTo(FulfillmentMonitorActionKind.RETRY);
    assertThat(first.status()).isEqualTo(FulfillmentMonitorDispositionStatus.PENDING);

    // 第二条被部分唯一索引 fulfillment_monitor_retry_once_uq 拒。
    assertThatThrownBy(() -> probeInsertRetry("PROBE-RETRY2", entry.caseId(), verificationId))
        .hasMessageContaining("fulfillment_monitor_retry_once_uq");

    // 取消掉之后允许重新发起（否则一次误操作就永久锁死这条流程）。
    asT(
        () ->
            monitor.advanceDisposition(
                entry.caseId(), first.dispositionId(),
                new FulfillmentMonitorDispositionStatusInput(
                    FulfillmentMonitorDispositionStatus.PENDING,
                    FulfillmentMonitorDispositionStatus.CANCELLED, "撤回：下游已经自己推进了",
                    null)));
    probeInsertRetry("PROBE-RETRY3", entry.caseId(), verificationId, true);
    var live =
        asT(
                () ->
                    sql.fetchOne(
                            "select count(*) c from fulfillment_monitor_disposition"
                                + " where request_id = ? and action_kind = 'RETRY'"
                                + " and status <> 'CANCELLED'",
                            entry.requestId())
                        .get("c", Integer.class))
            .intValue();
    assertThat(live).as("撤回之后库里有一条生效的重试").isEqualTo(1);
  }

  @Test
  void 并发重试只有一个能成功_互斥来自DB而不是内存() throws Exception {
    seedInsufficientStock("MO-CONCURRENT");
    var entry = entryOf(scan(), FulfillmentMonitorAnomalyKind.INSUFFICIENT_STOCK);
    confirm(entry.caseId(), 0L, "idem-conc");
    var verificationId = confirmedVerificationId(entry.caseId());
    var requestId = entry.requestId();

    int threads = 4;
    var start = new CountDownLatch(1);
    var ok = new AtomicInteger();
    var rejected = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      for (int i = 0; i < threads; i++) {
        final int n = i;
        pool.submit(
            () -> {
              try {
                start.await();
                // 每条走**不同的** disposition_no 与幂等键，唯一索引是唯一的裁判。
                probeInsertRetry("CONC-" + n, entry.caseId(), verificationId, true);
                ok.incrementAndGet();
              } catch (Exception e) {
                rejected.incrementAndGet();
              }
            });
      }
      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }
    // **这是防重复发货的钉子**：四条并发重试里只可能有一条成立。
    assertThat(ok.get()).as("并发重试只允许一条成功").isEqualTo(1);
    assertThat(rejected.get()).isEqualTo(threads - 1);
    var live =
        asT(
                () ->
                    sql.fetchOne(
                            "select count(*) c from fulfillment_monitor_disposition"
                                + " where request_id = ? and action_kind = 'RETRY'"
                                + " and status <> 'CANCELLED'",
                            requestId)
                        .get("c", Integer.class))
            .intValue();
    assertThat(live).as("库里确实只有一条未取消的重试").isEqualTo(1);
  }

  @Test
  void 只发出SENT不算处置完_结案要求COMPLETED() throws Exception {
    seedInsufficientStock("MO-CLOSEGATE");
    var entry = entryOf(scan(), FulfillmentMonitorAnomalyKind.INSUFFICIENT_STOCK);
    confirm(entry.caseId(), 0L, "idem-close");
    var verificationId = confirmedVerificationId(entry.caseId());
    var disposition =
        asT(
            () ->
                monitor.dispose(
                    entry.caseId(),
                    new FulfillmentMonitorDispositionInput(
                        caseVersion(entry.caseId()), FulfillmentMonitorActionKind.RETRY,
                        verificationId, 7200, null, null, null, null, "查证坐实，重试",
                        "idem-close-d"),
                    user));
    asT(
        () ->
            monitor.advanceDisposition(
                entry.caseId(), disposition.dispositionId(),
                new FulfillmentMonitorDispositionStatusInput(
                    FulfillmentMonitorDispositionStatus.PENDING,
                    FulfillmentMonitorDispositionStatus.SENT, null, null)));
    assertThatThrownBy(
            () ->
                asT(
                    () ->
                        monitor.close(
                            entry.caseId(),
                            new FulfillmentMonitorCloseInput(
                                caseVersion(entry.caseId()), "提前结案"),
                            user)))
        .hasMessageContaining("已完成的处置");

    asT(
        () ->
            monitor.advanceDisposition(
                entry.caseId(), disposition.dispositionId(),
                new FulfillmentMonitorDispositionStatusInput(
                    FulfillmentMonitorDispositionStatus.SENT,
                    FulfillmentMonitorDispositionStatus.COMPLETED, "下游已受理并完成", null)));
    var closed =
        asT(
            () ->
                monitor.close(
                    entry.caseId(),
                    new FulfillmentMonitorCloseInput(caseVersion(entry.caseId()), "重试已完成"),
                    user));
    assertThat(closed.status()).isEqualTo(FulfillmentMonitorCaseStatus.DISPOSED);
  }

  // ==========================================================================
  // 断言 2：超时判定用数据库时钟，断言具体差值而不是布尔
  // ==========================================================================

  @Test
  void 阈值未到不检出_到了则断言具体时间差值() throws Exception {
    var saved = createRequest("MO-TIMEOUT");
    // deadline_at = requested_at + 1s（V152 建单时写入头行）。
    var head = asT(() -> requests.request(saved.requestId())).request();
    assertThat(head.deadlineAt()).as("RESERVE 阈值就是 1 秒").isEqualTo(saved.requestedAt().plusSeconds(1));
    assertThat(head.overdue()).as("刚建单不该超时").isFalse();

    // 阈值未到：扫描不应检出。
    assertThat(scan().createdCases())
        .as("阈值未到就检出 = 把 deadline 判早了（本仓曾因 -28800s 偏移踩过）")
        .isZero();

    // 真的等过阈值（deadline 在建单之后 1 秒，所以要等 1.6 秒才「超了 0.6 秒」）。
    Thread.sleep(2600);

    var hit = entryOf(scan(), FulfillmentMonitorAnomalyKind.STEP_STUCK);
    assertThat(hit.stepKind()).isEqualTo(FulfillmentMonitorStepKind.RESERVE);
    assertThat(hit.thresholdSeconds()).as("阈值必须是被解析出的那一个").isEqualTo(1);
    assertThat(hit.thresholdSource())
        .as("没配租户档时来源是建单快照")
        .isEqualTo(FulfillmentMonitorThresholdSource.SNAPSHOT_FROM_REQUEST);

    // ⚠ 断言**具体差值**：overdue_seconds 必须精确等于「扫描时刻 − deadline」的秒数。
    //   只断言「被判为超时」是不够的 —— 一个恒返回 1 的实现也能通过那种断言。
    var detectedAt =
        asT(
                () ->
                    sql.fetchOne(
                            "select detected_at from fulfillment_monitor_case where id = ?",
                            hit.caseId()))
            .get("detected_at", OffsetDateTime.class);
    var stored =
        asT(
                () ->
                    sql.fetchOne(
                            "select overdue_seconds from fulfillment_monitor_case where id = ?",
                            hit.caseId()))
            .get("overdue_seconds", BigDecimal.class);
    var expectedOverdue =
        new BigDecimal(
                java.time.Duration.between(saved.requestedAt().plusSeconds(1), detectedAt)
                    .toMillis())
            .divide(new BigDecimal("1000"), 3, java.math.RoundingMode.HALF_UP);
    assertThat(stored)
        .as("overdue_seconds 必须等于「扫描时刻 − deadline」的真实秒数，而不是一个常量")
        .isCloseTo(
            expectedOverdue,
            org.assertj.core.data.Offset.offset(new BigDecimal("0.005")));
    assertThat(stored).as("过了 1 秒阈值，至少要超 1 秒").isGreaterThanOrEqualTo(new BigDecimal("1.000"));
    assertThat(hit.ruleEvidence()).contains("阈值 1 秒");

    // 未结案的同类异常不重复检出（反复跑 scan 不刷屏）。
    assertThat(scan().createdCases()).isZero();
  }

  @Test
  void 租户阈值真的生效_不是硬编码常量() throws Exception {
    // 把 RESERVE 的租户默认档配成 3600 秒。
    asT(
        () ->
            monitor.putThreshold(
                new FulfillmentMonitorThresholdInput(
                    null, FulfillmentMonitorStepKind.RESERVE, 3600, "本租户默认给 1 小时"),
                user));
    createRequest("MO-THRESHOLD");
    assertThat(scan().createdCases())
        .as("租户阈值 3600s 未到，即使建单快照是 1s 也不该检出")
        .isZero();

    // 收紧到 1 秒。⚠ 阈值解析改的是**监控口径**，不改 V152 建单时写下的
    //   deadline_at（那是对那张单的快照，不该被回溯改写），所以仍要真的等过阈值。
    Thread.sleep(2600);
    asT(
        () ->
            monitor.putThreshold(
                new FulfillmentMonitorThresholdInput(
                    null, FulfillmentMonitorStepKind.RESERVE, 1, "临时收紧"),
                user));
    var hit = entryOf(scan(), FulfillmentMonitorAnomalyKind.STEP_STUCK);
    assertThat(hit.thresholdSeconds()).isEqualTo(1);
    assertThat(hit.thresholdSource()).isEqualTo(FulfillmentMonitorThresholdSource.TENANT_DEFAULT);

    // 仓库覆盖档优先于租户默认档。
    asT(
        () ->
            monitor.putThreshold(
                new FulfillmentMonitorThresholdInput(
                    "WH-CN-SH", FulfillmentMonitorStepKind.RESERVE, 2, "本仓单独放宽到 2 秒"),
                user));
    var effective = asT(() -> monitor.effectiveThresholds("WH-CN-SH"));
    var reserve =
        effective.thresholds().stream()
            .filter(t -> t.stepKind() == FulfillmentMonitorStepKind.RESERVE)
            .findFirst()
            .orElseThrow();
    assertThat(reserve.thresholdSeconds()).isEqualTo(2);
    assertThat(reserve.source()).isEqualTo(FulfillmentMonitorThresholdSource.TENANT_OVERRIDE);
    // 没配过的步骤回落内置兜底，且必须标出 BUILTIN（不能让界面以为有人配过）。
    var track =
        effective.thresholds().stream()
            .filter(t -> t.stepKind() == FulfillmentMonitorStepKind.TRACK)
            .findFirst()
            .orElseThrow();
    assertThat(track.source()).isEqualTo(FulfillmentMonitorThresholdSource.BUILTIN);
    assertThat(track.thresholdSeconds()).isEqualTo(86400);
  }

  @Test
  void asOf前移能复现一次真实超时且差值可断言() throws Exception {
    createRequest("MO-ASOF");
    asT(
        () ->
            monitor.putThreshold(
                new FulfillmentMonitorThresholdInput(
                    null, FulfillmentMonitorStepKind.RESERVE, 1, "测试档"),
                user));
    // asOfSecondsAgo = -3600 ⇒ 判定基准是「现在 + 3600 秒」，
    // deadline 是「建单时刻 + 1 秒」，所以超时应恰好在 3600 秒附近。
    var hit = entryOf(scanAsOf(-3600), FulfillmentMonitorAnomalyKind.STEP_STUCK);
    assertThat(hit.thresholdSeconds()).isEqualTo(1);
    assertThat(hit.overdueSeconds())
        .as("超时应约等于 3600 秒（判定基准前移了整整一小时）")
        .isBetween(new BigDecimal("3595.000"), new BigDecimal("3610.000"));
    assertThat(hit.ruleEvidence()).contains("阈值 1 秒");
  }

  @Test
  void 加08时区偏移的夹具写回后逐微秒相等() throws Exception {
    seedInsufficientStock("MO-TZ");
    var entry = entryOf(scan(), FulfillmentMonitorAnomalyKind.INSUFFICIENT_STOCK);
    // 刻意用 +08:00 非 UTC 偏移：用 Timestamp.valueOf(LocalDateTime) 绑定会静默偏 -28800s。
    OffsetDateTime captured =
        OffsetDateTime.of(2026, 10, 17, 9, 30, 15, 123456000, ZoneOffset.ofHours(8));
    var todo =
        asT(
            () ->
                monitor.createTodo(
                    entry.caseId(),
                    new FulfillmentMonitorTodoInput(
                        FulfillmentMonitorTodoAction.CONFIRM_DOWNSTREAM, "向仓库确认库存",
                        "这条单卡在预占，请仓库侧确认是否还有货", captured, null),
                    user));
    assertThat(todo.capturedAt())
        .as("+08:00 偏移必须原样保留，不能被当成 UTC 再偏 8 小时")
        .isEqualTo(captured);
    assertThat(todo.capturedAt().getOffset()).isEqualTo(ZoneOffset.ofHours(8));

    var stored =
        asT(
                () ->
                    sql.fetchOne(
                            "select captured_at from fulfillment_monitor_todo where id = ?",
                            todo.id()))
            .get("captured_at", OffsetDateTime.class);
    assertThat(stored.toInstant()).isEqualTo(captured.toInstant());
    // 采集时刻与登记时刻是两个值：登记时刻会把「十分钟前就该看的单」说成刚出现。
    assertThat(todo.status()).isEqualTo(FulfillmentMonitorTodoStatus.OPEN);
  }

  @Test
  void 查证时刻也按timestamptz绑定_不会偏8小时() throws Exception {
    seedInsufficientStock("MO-VTZ");
    var entry = entryOf(scan(), FulfillmentMonitorAnomalyKind.INSUFFICIENT_STOCK);
    OffsetDateTime verifiedAt = OffsetDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(3).withNano(0);
    var saved =
        asT(
            () ->
                monitor.verify(
                    entry.caseId(),
                    new FulfillmentMonitorVerifyInput(
                        0L, FulfillmentMonitorConclusion.CONFIRMED,
                        FulfillmentMonitorVerifiedScope.DOWNSTREAM_ACK, "RSV-TZ",
                        "3 分钟前就已向 7.2 查证，回执为空", verifiedAt, "idem-vtz"),
                    user));
    assertThat(saved.verifiedAt().toInstant()).isEqualTo(verifiedAt.toInstant());
    var stored =
        asT(
                () ->
                    sql.fetchOne(
                            "select verified_at from fulfillment_monitor_verification where case_id = ?"
                                + " and conclusion = 'CONFIRMED'",
                            entry.caseId()))
            .get("verified_at", OffsetDateTime.class);
    assertThat(stored.toInstant())
        .as("写回值必须与传入的瞬时相同（偏 8 小时会在界面上变成未来时间）")
        .isEqualTo(verifiedAt.toInstant());
  }

  // ==========================================================================
  // 断言 3：下游只登记、不假装
  // ==========================================================================

  @Test
  void 能力声明把两个未接通的下游显式报出来() {
    var caps = asT(monitor::capabilities);
    assertThat(caps.reservationReleaseDispatched())
        .as("7.2.1.3 未交付：释放指令没有真的发出去")
        .isFalse();
    assertThat(caps.workbenchTodoPushed()).as("1 工作台未交付：待办没有真的推过去").isFalse();
    assertThat(caps.processStateMutated()).as("本模块不代 9.3.3.1 改流程状态").isFalse();
    assertThat(caps.retryRequiresVerification()).isTrue();
    var release =
        caps.dimensions().stream()
            .filter(d -> d.key().equals("RESERVATION_RELEASE"))
            .findFirst()
            .orElseThrow();
    assertThat(release.available()).isFalse();
    assertThat(release.reason()).contains("未交付");
    var upstream =
        caps.dimensions().stream()
            .filter(d -> d.key().equals("FULFILLMENT_PROCESS_STATE"))
            .findFirst()
            .orElseThrow();
    assertThat(upstream.available()).as("9.3.3.1 已交付，这条是真的").isTrue();
  }

  @Test
  void 释放预占只登记指令_缺省待回执且要下游回执才算已释放() throws Exception {
    lockReservation("MO-RELEASE");
    // 这条流程还没超时；把判定基准推到一小时后，真实检出 STEP_STUCK。
    var hit = entryOf(scanAsOf(-3600), FulfillmentMonitorAnomalyKind.STEP_STUCK);
    var caseRow = caseOf(hit.requestCode());
    assertThat(caseRow).as("必须命中刚才那条锁过预占的流程").isNotNull();
    confirm(caseRow.id(), caseVersion(caseRow.id()), "idem-rel");

    var disposition =
        asT(
            () ->
                monitor.dispose(
                    caseRow.id(),
                    new FulfillmentMonitorDispositionInput(
                        caseVersion(caseRow.id()),
                        FulfillmentMonitorActionKind.RELEASE_RESERVATION, null, null, null,
                        "WH-CN-SH2", null, null, "下游已确认不再需要这批货，释放预占",
                        "idem-rel-d"),
                    user));
    assertThat(disposition.releaseId()).as("释放登记必须落一条").isNotNull();
    assertThat(disposition.releaseNo()).isNotNull();

    // 缺省必须是「待回执」：没有 7.2 的回执就不算释放。
    var detail = asT(() -> monitor.caseDetail(caseRow.id()));
    var pending = detail.releases().get(0);
    assertThat(pending.status()).isEqualTo(FulfillmentMonitorReleaseStatus.REQUESTED);
    assertThat(pending.statusLabel()).isEqualTo("待回执");
    assertThat(pending.sourceKind())
        .as("租户可维护登记；正式接口到位后同步器按同一形状改这个枚举即可")
        .isEqualTo(FulfillmentMonitorSourceKind.TENANT_MAINTAINED);
    assertThat(pending.releasedQty()).as("还没回执就没有释放数量").isNull();

    // 没有凭据与数量不许说「已释放」。
    assertThatThrownBy(
            () ->
                asT(
                    () ->
                        monitor.ackRelease(
                            caseRow.id(), disposition.releaseId(),
                            new FulfillmentMonitorReleaseAckInput(
                                FulfillmentMonitorReleaseStatus.RELEASED, "", null, null))))
        .hasMessageContaining("必须给下游凭据");

    var released =
        asT(
            () ->
                monitor.ackRelease(
                    caseRow.id(), disposition.releaseId(),
                    new FulfillmentMonitorReleaseAckInput(
                        FulfillmentMonitorReleaseStatus.RELEASED, "REL-DOWNSTREAM",
                        new BigDecimal("50.000000"), null)));
    assertThat(released.status()).isEqualTo(FulfillmentMonitorReleaseStatus.RELEASED);
    assertThat(released.releaseRef()).isEqualTo("REL-DOWNSTREAM");
    assertThat(released.releasedQty()).isEqualByComparingTo("50.000000");
  }

  @Test
  void 没锁过货就不许登记释放() throws Exception {
    seedInsufficientStock("MO-NOREL");
    var entry = entryOf(scan(), FulfillmentMonitorAnomalyKind.INSUFFICIENT_STOCK);
    confirm(entry.caseId(), 0L, "idem-norel");
    // 这条流程的预占是被拒的，不是 LOCKED ⇒ 没有可释放对象。
    assertThatThrownBy(
            () ->
                asT(
                    () ->
                        monitor.dispose(
                            entry.caseId(),
                            new FulfillmentMonitorDispositionInput(
                                caseVersion(entry.caseId()),
                                FulfillmentMonitorActionKind.RELEASE_RESERVATION, null, null,
                                null, null, null, null, "想释放一笔从没锁过的预占", "idem-norel-d"),
                            user)))
        .hasMessageContaining("还没有一条已锁定的预占指令");
  }

  // ==========================================================================
  // 断言 4：多租户三断言（这两个固定租户是唯一共享的用例）
  // ==========================================================================

  @Test
  void 断言1_两租户互不可见() throws Exception {
    seedRejectedOn(actorA, a, "MO-TENA", "WH-CN-SH", "CN-EAST", "A 租户缺货");
    seedRejectedOn(actorB, b, "MO-TENB", "WH-CN-BJ", "CN-NORTH", "B 租户缺货");
    asA(() -> monitor.scan(new FulfillmentMonitorScanInput(null, null, null, null), user));
    var aBefore = asA(this::countCases);
    var bBefore = asB(this::countCases);
    assertThat(aBefore).as("A 只看得到自己那一份").isPositive();
    assertThat(bBefore).isPositive();

    asB(() -> monitor.scan(new FulfillmentMonitorScanInput(null, null, null, null), user));
    assertThat(asA(this::countCases)).as("A 不该看到 B 扫描出来的案例").isEqualTo(aBefore);
    assertThat(asB(this::countCases)).as("B 的条数增加了").isGreaterThan(bBefore);

    // 更强的一格：按主键直查对方的案例也拿不到（不只是列表过滤）。
    var bCase = caseOfB("MO-TENB").id();
    assertThatThrownBy(() -> asA(() -> monitor.caseDetail(bCase)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在");
  }

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    seedInsufficientStockB("MO-FORGE");
    asB(() -> monitor.scan(new FulfillmentMonitorScanInput(null, null, null, null), user));
    var bTotal = asB(this::countCases);
    assertThat(bTotal).isPositive();

    // 带 B 的合法会话 + 伪造 A 的 X-Tenant-Id 头：看到的仍然是 B 的行数。
    http.perform(
            get("/trade/fulfillment/monitor/cases")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(bTotal));

    // 头里带一个**不存在的**租户也不能凭空改变可见行数。
    http.perform(
            get("/trade/fulfillment/monitor/cases")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", UUID.randomUUID()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(bTotal));

    assertThat(effectiveTenant()).as("提交后租户上下文必须已清空").isBlank();
  }

  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    seedRejectedOn(actorA, a, "MO-POOL1", "WH-CN-SH", "CN-EAST", "A 租户缺货");
    seedRejectedOn(actorB, b, "MO-POOL2", "WH-CN-BJ", "CN-NORTH", "B 租户缺货");
    asA(() -> monitor.scan(new FulfillmentMonitorScanInput(null, null, null, null), user));
    asB(() -> monitor.scan(new FulfillmentMonitorScanInput(null, null, null, null), user));
    var aFirst = asA(this::countCases);
    var bFirst = asB(this::countCases);
    assertThat(aFirst).as("A 侧也要真的看到案例，否则「不变」这句断言是空的").isPositive();
    assertThat(bFirst).isPositive();
    // 单连接池下交替读写：只有事务局部绑定 + 提交即清空才不会串。
    for (int i = 0; i < 25; i++) {
      assertThat(asA(this::countCases)).as("第 %d 轮 A 看到的条数", i).isEqualTo(aFirst);
      assertThat(asB(this::countCases)).as("第 %d 轮 B 看到的条数", i).isEqualTo(bFirst);
    }
    assertThat(effectiveTenant()).as("每轮之后租户上下文都必须已清空").isBlank();
  }

  // ================================================================ HTTP 与读筛选

  @Test
  void 端点走HTTP真的可用且读权限不许写() throws Exception {
    seedInsufficientStock("MO-HTTP");
    scan();
    http.perform(get("/trade/fulfillment/monitor/capabilities").header("Authorization", bearerT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.retryRequiresVerification").value(true))
        .andExpect(jsonPath("$.reservationReleaseDispatched").value(false))
        .andExpect(jsonPath("$.workbenchTodoPushed").value(false));

    http.perform(get("/trade/fulfillment/monitor/cases").header("Authorization", bearerT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.page").value(0));

    // 单号筛选是真的 SQL 过滤。⚠ 一次缺货同时构成「预占失败」与「库存不足」
    // 两条异常，两条都要在清单里 —— 所以这里是 2 而不是 1。
    http.perform(
            get("/trade/fulfillment/monitor/cases")
                .header("Authorization", bearerT)
                .param("requestCode", "MO-HTTP"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2));

    // 空结果也要是 200 + 空数组，而不是 404 / 报错。
    http.perform(
            get("/trade/fulfillment/monitor/cases")
                .header("Authorization", bearerT)
                .param("requestCode", "不存在的单号"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0))
        .andExpect(jsonPath("$.items").isArray());

    // 未认证就是 401。
    http.perform(get("/trade/fulfillment/monitor/cases")).andExpect(status().isUnauthorized());

    // 只读会话不许写。
    http.perform(
            post("/trade/fulfillment/monitor/scan")
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void 分页是真的分页() throws Exception {
    seedInsufficientStock("MO-PAGE");
    scan();
    var one =
        asT(
            () ->
                monitor.pageCases(
                    new FulfillmentMonitorCaseQuery(
                        null, "MO-PAGE", null, null, null, null, null, null, null, null, null,
                        null, 0, 1)));
    assertThat(one.items()).hasSize(1);
    assertThat(one.size()).isEqualTo(1);
    assertThat(one.total()).as("total 与分页无关").isEqualTo(2);
    var all =
        asT(
            () ->
                monitor.pageCases(
                    new FulfillmentMonitorCaseQuery(
                        null, "MO-PAGE", null, null, null, null, null, null, null, null, null,
                        null, 0, 50)));
    assertThat(all.items()).hasSize(2);
  }

  @Test
  void 事件流是append_only且改了或删了都会被DB挡下() throws Exception {
    seedInsufficientStock("MO-EVENT");
    var entry = entryOf(scan(), FulfillmentMonitorAnomalyKind.INSUFFICIENT_STOCK);
    var events = asT(() -> monitor.caseDetail(entry.caseId())).events();
    assertThat(events).as("检出必须留痕").isNotEmpty();
    assertThat(
            events.stream().anyMatch(e -> e.eventKind() == FulfillmentMonitorEventKind.DETECTED))
        .isTrue();

    assertThatThrownBy(
            () ->
                privileged(
                    tenantT,
                    c -> {
                      try (var s = c.createStatement()) {
                        s.execute(
                            "update fulfillment_monitor_event set detail = '改过的凭据'"
                                + " where case_id = '" + entry.caseId() + "'");
                      }
                    },
                    false))
        .hasMessageContaining("只允许追加");
    assertThatThrownBy(
            () ->
                privileged(
                    tenantT,
                    c -> {
                      try (var s = c.createStatement()) {
                        s.execute(
                            "delete from fulfillment_monitor_case where id = '"
                                + entry.caseId() + "'");
                      }
                    },
                    false))
        .hasMessageContaining("不允许删除");
  }

  @Test
  void 阈值不允许空串冒充默认档() {
    assertThatThrownBy(
            () ->
                asT(
                    () ->
                        monitor.putThreshold(
                            new FulfillmentMonitorThresholdInput(
                                "  ", FulfillmentMonitorStepKind.WORK, 3600, "空串"),
                            user)))
        .hasMessageContaining("空串");
  }

  @Test
  void 待办可以认领并完成() throws Exception {
    seedInsufficientStock("MO-TODO");
    var entry = entryOf(scan(), FulfillmentMonitorAnomalyKind.INSUFFICIENT_STOCK);
    var todo =
        asT(
            () ->
                monitor.createTodo(
                    entry.caseId(),
                    new FulfillmentMonitorTodoInput(
                        FulfillmentMonitorTodoAction.ESCALATE, "升级给仓储主管", null, null,
                        null),
                    user));
    var claimed =
        asT(
            () ->
                monitor.actionTodo(
                    todo.id(), new FulfillmentMonitorTodoActionInput("CLAIM", "我来跟"), user));
    assertThat(claimed.status()).isEqualTo(FulfillmentMonitorTodoStatus.CLAIMED);
    assertThat(claimed.claimedByMembershipId()).isEqualTo(user);
    var done =
        asT(
            () ->
                monitor.actionTodo(
                    todo.id(),
                    new FulfillmentMonitorTodoActionInput("DONE", "已确认缺货，转采购补货"),
                    user));
    assertThat(done.status()).isEqualTo(FulfillmentMonitorTodoStatus.DONE);
    assertThat(done.doneNote()).isEqualTo("已确认缺货，转采购补货");

    var page =
        asT(
            () ->
                monitor.pageTodos(
                    new FulfillmentMonitorTodoQuery(entry.caseId(), null, null, null, 0, 50)));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items().get(0).statusLabel()).isEqualTo("已完成");
  }

  @Test
  void 处置幂等键重放返回同一条而不是报错() throws Exception {
    seedInsufficientStock("MO-IDEM");
    var entry = entryOf(scan(), FulfillmentMonitorAnomalyKind.INSUFFICIENT_STOCK);
    confirm(entry.caseId(), 0L, "idem-disp");
    var verificationId = confirmedVerificationId(entry.caseId());
    var input =
        new FulfillmentMonitorDispositionInput(
            caseVersion(entry.caseId()), FulfillmentMonitorActionKind.MANUAL_INTERVENTION, null,
            null, null, null, null, null, "人工打电话给仓库", "idem-disp-key");
    var first = asT(() -> monitor.dispose(entry.caseId(), input, user));
    var second = asT(() -> monitor.dispose(entry.caseId(), input, user));
    assertThat(second.dispositionId()).isEqualTo(first.dispositionId());
    assertThat(second.dispositionNo()).isEqualTo(first.dispositionNo());
    var count =
        asT(
                () ->
                    sql.fetchOne(
                            "select count(*) c from fulfillment_monitor_disposition where case_id = ?",
                            entry.caseId())
                        .get("c", Integer.class))
            .intValue();
    assertThat(count).as("同键重放不产生第二条活").isEqualTo(1);
  }

  @Test
  void 切换路径必须给目标决策引用且金额必须带币种() throws Exception {
    seedInsufficientStock("MO-SWITCH");
    var entry = entryOf(scan(), FulfillmentMonitorAnomalyKind.INSUFFICIENT_STOCK);
    confirm(entry.caseId(), 0L, "idem-sw");
    assertThatThrownBy(
            () ->
                asT(
                    () ->
                        monitor.dispose(
                            entry.caseId(),
                            new FulfillmentMonitorDispositionInput(
                                caseVersion(entry.caseId()),
                                FulfillmentMonitorActionKind.SWITCH_PATH, null, null, null, null,
                                new BigDecimal("12.34"), null, "切换但不指明切到哪", "idem-sw-1"),
                            user)))
        .hasMessageContaining("成本差估值必须与币种成对出现");
    assertThatThrownBy(
            () ->
                asT(
                    () ->
                        monitor.dispose(
                            entry.caseId(),
                            new FulfillmentMonitorDispositionInput(
                                caseVersion(entry.caseId()),
                                FulfillmentMonitorActionKind.SWITCH_PATH, null, null, null, null,
                                new BigDecimal("12.3400"), "USD", "切到自发货", "idem-sw-2"),
                            user)))
        .hasMessageContaining("必须给目标 routing_decision_ref");
    var ok =
        asT(
            () ->
                monitor.dispose(
                    entry.caseId(),
                    new FulfillmentMonitorDispositionInput(
                        caseVersion(entry.caseId()), FulfillmentMonitorActionKind.SWITCH_PATH,
                        null, null, "ROUTE-FBA-2", "WH-CN-SZ", new BigDecimal("12.3400"), "USD",
                        "平台仓没货了，切自发货", "idem-sw-3"),
                    user));
    assertThat(ok.actionKind()).isEqualTo(FulfillmentMonitorActionKind.SWITCH_PATH);
    var row = asT(() -> monitor.caseDetail(entry.caseId())).dispositions().get(0);
    assertThat(row.targetRouteRef()).isEqualTo("ROUTE-FBA-2");
    assertThat(row.estimatedCostCurrencyCode()).isEqualTo("USD");
    assertThat(row.estimatedCostDelta()).isEqualByComparingTo("12.3400");
  }
}
