package com.starcon.trade;

import static com.starcon.trade.ShipmentWritebackModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 9.4.2.1 平台回传。
 *
 * <p>本测试是 <b>V147 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论，
 * 逐条在真实 PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不只直连数据库，
 * 以便同时验证「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>channel 那一格是跨服务替身，不是本模块的数据替身</b>：2 队列
 * （{@code platform_operation}）在 channel 服务自己的库里，AGENTS.md 禁止 trade
 * 直连那个库，所以这里用 {@code com.sun.net.httpserver} 起一个 2.5.1.1 / 2.5.1.2
 * 形状的端点（与本仓库其它跨服务端口测试同一手法）。回传任务、尝试凭据、
 * 查证记录、事件流<b>全部真落 PostgreSQL 16</b>。
 *
 * <p><b>时钟钉死</b>（{@link MutableClock}）：退避排程是本模块要断言的行为，
 * 若服务里散着 {@code OffsetDateTime.now()}，同一个用例只在某个钟点通过。
 * 夹具里的发货时刻同样 pin 死，并<b>断言写回值</b>而不是「没抛异常」。
 *
 * <p><b>本测试要钉住的</b>：
 * <ol>
 *   <li><b>三态真的是三态</b>：结果未知既不能被重试、也不能被当成成功；</li>
 *   <li><b>先查证再重发</b>：三条查证结论各有各的处置，STILL_UNKNOWN 继续禁重发；</li>
 *   <li><b>幂等</b>：重复建任务 / 重复提交都复用，不产生第二次平台动作；</li>
 *   <li><b>重试有次数也有退避</b>，退避时刻断言写回值；</li>
 *   <li><b>人工修正换键</b>，旧任务留证据，幂等键不可就地改写；</li>
 *   <li><b>9.4.1.2 接缝</b>：真实回传进展写进 {@code shipment_record} 的登记位；</li>
 *   <li>凭据表 append-only（权限 + 触发器两层）；</li>
 *   <li><b>多租户三断言</b>：两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ShipmentWritebackIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer channel;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 钉死的「当前时刻」。测试可显式推进，用来断言退避是否到点。 */
  static final MutableClock clock = new MutableClock(
      Instant.parse("2026-04-01T02:00:00Z"));

  /** 夹具里 pin 死的发货时刻。**刻意不用 now()**。 */
  static final OffsetDateTime SHIP_AT = OffsetDateTime.parse("2026-03-01T10:15:30+08:00");

  /** channel 侧收到的调用：path → 请求体。用来证明本模块真的投递了、真的查证了。 */
  static final List<String> channelCalls = new CopyOnWriteArrayList<>();

  /** channel 侧入队返回的任务号（按入队顺序发号，键命中时复用同一个 id）。 */
  static final Map<String, UUID> channelByKey = new ConcurrentHashMap<>();
  static final AtomicInteger CHANNEL_SEQ = new AtomicInteger();

  /** 让 2 队列整体不可用（模拟 channel 挂了 / 网络不通）。 */
  static volatile boolean channelDown = false;

  /** shop_id 由调用方给：channel 侧 shop 不存在时它的 FK 会顶回来。 */
  static final UUID SHOP_OK = UUID.randomUUID();
  static final UUID SHOP_MISSING = UUID.randomUUID();

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

  /** 可推进的固定时钟 —— 退避断言全靠它。 */
  static final class MutableClock extends Clock {
    private volatile Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void set(Instant next) {
      this.instant = next;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.systemDefault();
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }

  @TestConfiguration
  static class FixedClockConfiguration {
    @Bean
    @Primary
    Clock testClock() {
      return clock;
    }
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

      // 2.5.1.1 / 2.5.1.2 的替身：入队返回动作号，其余五个动作只记调用。
      // 入队刻意实现 channel 的真实语义：**幂等键命中复用既有动作 id** ——
      // 少了这一点就测不出「重复提交会不会产生第二次平台动作」。
      channel = HttpServer.create(new InetSocketAddress(0), 0);
      channel.createContext(
          "/platform-operations",
          e -> {
            if (e.getRequestHeaders().getFirst("Authorization") == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            String path = e.getRequestURI().getPath();
            String body = new String(e.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            channelCalls.add(path + " " + body);
            if (channelDown) {
              e.sendResponseHeaders(503, -1);
              e.close();
              return;
            }
            if ("/platform-operations".equals(path)) {
              var node = json.readTree(body);
              String key = node.get("idempotencyKey").asText();
              UUID id =
                  channelByKey.computeIfAbsent(
                      key, k -> new UUID(0, CHANNEL_SEQ.incrementAndGet()));
              byte[] out = ("\"" + id + "\"").getBytes(java.nio.charset.StandardCharsets.UTF_8);
              e.getResponseHeaders().add("Content-Type", "application/json");
              e.sendResponseHeaders(200, out.length);
              e.getResponseBody().write(out);
              e.close();
              return;
            }
            e.sendResponseHeaders(200, -1);
            e.close();
          });
      channel.start();
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
    r.add("CHANNEL_URL", () -> "http://localhost:" + channel.getAddress().getPort());
    // 单连接：只有复用同一条连接才能证明归还后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired ShipmentWritebackService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    channel.stop(0);
    pg.stop();
  }

  AccessService.Access actorA;
  AccessService.Access actorB;

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "trade.fulfillment.update", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "trade.fulfillment.update", work);
  }

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    channelCalls.clear();
    channelByKey.clear();
    CHANNEL_SEQ.set(0);
    channelDown = false;
    clock.set(Instant.parse("2026-04-01T02:00:00Z"));
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate shipment_writeback_event,shipment_writeback_verification,"
              + " shipment_writeback_attempt,shipment_writeback_task cascade");
      s.execute(
          "truncate shipment_record_event,shipment_record_callback,"
              + " shipment_record_visibility,shipment_record_tracking_observation,"
              + " shipment_record_parcel,shipment_record cascade");
    }
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  // ==========================================================================
  // 能力声明
  // ==========================================================================
  @Test
  void 能力声明如实报出三态与店铺目录的边界() {
    var caps = asA(service::capabilities);
    assertThat(caps.statuses()).contains("PENDING_VERIFICATION");
    assertThat(caps.threeStatePolicy()).contains("不是失败").contains("CONFIRMED_NOT_APPLIED");
    assertThat(caps.shopIdPolicy()).contains("调用方指定");
    var shop =
        caps.dimensions().stream()
            .filter(d -> d.dimension().equals("SHOP_ID_LOOKUP"))
            .findFirst()
            .orElseThrow();
    assertThat(shop.available()).as("2.3 店铺目录读不到 ⇒ available=false 并点名 owner").isFalse();
    assertThat(shop.ownerModule()).contains("2.3");
    assertThat(caps.idempotencyKeyBasis()).contains("服务端构造");
  }

  // ==========================================================================
  // 建任务与幂等
  // ==========================================================================
  @Test
  void 建任务幂等且幂等键由服务端构造() throws Exception {
    UUID record = seedRecord(a, "SF", "WB-A-001", SHIP_AT, 2);
    var input = new ShipmentWritebackCreateInput("AMAZON", SHOP_OK, null, "PO-1",
        List.of(record), "首批");

    var first = asA(() -> service.create(input, actorA.membershipId()));
    assertThat(first.createdTasks()).isEqualTo(1);
    assertThat(first.reusedTasks()).isZero();

    var page = asA(() -> service.tasks(null, null, null, null, null, null, 0, 50));
    assertThat(page.items()).hasSize(1);
    var task = page.items().get(0);
    assertThat(task.idempotencyKey())
        .as("键必须由服务端按 <recordId>:r<revision> 构造")
        .isEqualTo("ship-update:" + record + ":r1");
    assertThat(task.status()).isEqualTo(WritebackStatus.PENDING);
    assertThat(task.revision()).isEqualTo(1);
    assertThat(task.parcelCount()).as("箱数是真计数，不是票数").isEqualByComparingTo("2");
    assertThat(task.nextAttemptAt()).as("待回传必须已排下一次尝试时刻").isNotNull();

    // 重复建：复用而不是产生第二次平台动作
    var second = asA(() -> service.create(input, actorA.membershipId()));
    assertThat(second.createdTasks()).isZero();
    assertThat(second.reusedTasks()).isEqualTo(1);
    assertThat(asA(() -> service.tasks(null, null, null, null, null, null, 0, 50)).items())
        .hasSize(1);
  }

  @Test
  void 发货记录一条都看不见时报404_已作废仍逐条跳过() throws Exception {
    // 一条都解析不到（不存在，或属于别的租户）⇒ 报 404 而不是 200 + 静默跳过。
    // 「回传 200 但一条没建成」在 UI 上会被读成「已提交」，那正是伪造租户头
    // 想让人以为发生的事（见 断言2）。
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.create(
                            new ShipmentWritebackCreateInput("AMAZON", SHOP_OK, null, "",
                                List.of(UUID.randomUUID()), ""),
                            actorA.membershipId())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不属于本租户");

    // 部分可见仍逐条汇报：已作废是「看得见但不该回传」，走 skipped 而不是 404。
    UUID voided = seedRecord(a, "DHL", "WB-VOID-1", SHIP_AT, 1);
    // 作废必须带原因与变更时刻：V127 的 shipment_record_check3 强制这一点
    // （status='VOID' ⇒ status_reason 非空且 status_changed_at 非空）。
    asA(
        () ->
            sql.execute(
                "update shipment_record set status = 'VOID', status_reason = '交接单作废',"
                    + " status_changed_at = ?::timestamptz where id = ?",
                SHIP_AT, voided));
    var skipped =
        asA(
            () ->
                service.create(
                    new ShipmentWritebackCreateInput("AMAZON", SHOP_OK, null, "",
                        List.of(voided), ""),
                    actorA.membershipId()));
    assertThat(skipped.skippedRecords()).isEqualTo(1);
    assertThat(skipped.notes().get(0)).contains("作废");

    // 混合批次：一半看不见、一半已作废 —— 仍按逐条汇报，不整批失败。
    var mixed =
        asA(
            () ->
                service.create(
                    new ShipmentWritebackCreateInput("AMAZON", SHOP_OK, null, "",
                        List.of(voided, UUID.randomUUID()), ""),
                    actorA.membershipId()));
    assertThat(mixed.unresolvedRecords()).isEqualTo(1);
    assertThat(mixed.skippedRecords()).isEqualTo(1);
  }

  // ==========================================================================
  // 投递：经 2 队列
  // ==========================================================================
  @Test
  void 提交会把发货信息投进二队列且重复提交不产生第二次平台动作() throws Exception {
    UUID record = seedRecord(a, "SF", "WB-SUB-1", SHIP_AT, 2);
    String id = createTask(record).task().id().toString();

    var detail = asA(() -> service.submit(id, null, actorA.membershipId(), "Bearer tenant-a"));
    assertThat(detail.task().status()).isEqualTo(WritebackStatus.SUBMITTED);
    assertThat(detail.task().attemptCount()).isEqualTo(1);
    assertThat(detail.task().channelOperationId()).isNotNull();
    assertThat(channelCalls)
        .as("必须真的经 2 队列投递，而不是本地登记一下")
        .anyMatch(c -> c.startsWith("/platform-operations "))
        .anyMatch(c -> c.endsWith("/start") || c.contains("/start "));

    // payload 里必须带运单号 / 承运商 / 发货时间，且数量是字符串（禁浮点）
    var payload = detail.payloadJson();
    assertThat(payload).contains("WB-SUB-1").contains("SF").contains("2026-03-01T10:15:30+08:00");
    // payload 落库是 jsonb，读回来时 PostgreSQL 会把它规范化成 `"k": v`（冒号后带空格）。
    // 断言的是「数量是字符串而不是浮点」，与空白形态无关，故先去掉空格再比。
    assertThat(payload.replace(" ", ""))
        .contains("\"totalQuantity\":\"2.000000\"")
        .as("数量必须是字符串形态的定点数，不能退化成浮点");
    assertThat(payload.replace(" ", "")).doesNotContain("\"totalQuantity\":2");
  }

  @Test
  void 二队列不可达时如实失败而不是记成已受理() throws Exception {
    UUID record = seedRecord(a, "SF", "WB-DOWN-1", SHIP_AT, 1);
    String id = createTask(record).task().id().toString();
    channelDown = true;

    assertThatThrownBy(() -> asA(() -> service.submit(id, null, actorA.membershipId(),
        "Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("平台对接服务");

    // 关键：失败之后状态**没有**被推进成已提交
    var row = asA(() -> service.task(id));
    assertThat(row.task().status()).isEqualTo(WritebackStatus.PENDING);
    assertThat(row.task().channelOperationId()).isNull();
  }

  // ==========================================================================
  // 三态
  // ==========================================================================
  @Test
  void 成功态必须有回执凭据并写进九点四点一二的登记位() throws Exception {
    UUID record = seedRecord(a, "SF", "WB-OK-1", SHIP_AT, 1);
    String id = createTask(record).task().id().toString();
    asA(() -> service.submit(id, null, actorA.membershipId(), "Bearer tenant-a"));

    var done =
        asA(
            () ->
                service.registerOutcome(
                    id,
                    new ShipmentWritebackOutcomeInput(
                        WritebackStatus.CONFIRMED, "REQ-7788", "", ""),
                    actorA.membershipId(),
                    "Bearer tenant-a"));
    assertThat(done.task().status()).isEqualTo(WritebackStatus.CONFIRMED);
    assertThat(done.task().platformRequestId()).isEqualTo("REQ-7788");
    assertThat(done.attempts()).hasSize(1);
    assertThat(done.attempts().get(0).outcome()).isEqualTo(WritebackAttemptOutcome.CONFIRMED);
    assertThat(channelCalls).anyMatch(c -> c.contains("/succeed"));
    assertThat(channelCalls).noneMatch(c -> c.contains("/fail") || c.contains("/unknown"));

    // 9.4.1.2 接缝：真实进展写进 platform_callback_status（ACKNOWLEDGED）
    assertThat(callbackStatusOf(a, record)).isEqualTo("ACKNOWLEDGED");
  }

  @Test
  void 成功态必须带回执凭据_服务层与DB各拦一道() throws Exception {
    UUID record = seedRecord(a, "SF", "WB-OK-2", SHIP_AT, 1);
    String id = createTask(record).task().id().toString();

    // 第 1 道（服务层）：还没提交过的任务不许直接登记平台成功 ——
    // 「成功」必须有它所描述的那一次真实投递。
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.registerOutcome(
                            id,
                            new ShipmentWritebackOutcomeInput(
                                WritebackStatus.CONFIRMED, "REQ-X", "", ""),
                            actorA.membershipId(),
                            "Bearer tenant-a")))
        .as("未提交就登记成功")
        .hasMessageContaining("才能登记平台结果");

    // 第 2 道（DB）：SUBMITTED 态不允许没有队列动作号。
    // 这条约束正是让「服务层那条凭据检查」在已提交后几乎不可能被绕过的原因：
    // 到了 SUBMITTED 就必然已经有一个凭据，所以缺凭据的登记在 DB 层就被堵死了。
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update shipment_writeback_task set status = 'SUBMITTED',"
                                + " channel_operation_id = null where id = ?",
                            UUID.fromString(id))))
        .as("SUBMITTED 态必须带队列动作号")
        .hasMessageContaining("shipment_writeback_submitted_needs_queue_ref");

    // 提交之后：队列动作号本身就是凭据，平台请求号可以空着。
    asA(() -> service.submit(id, null, actorA.membershipId(), "Bearer tenant-a"));
    var done =
        asA(
            () ->
                service.registerOutcome(
                    id,
                    new ShipmentWritebackOutcomeInput(WritebackStatus.CONFIRMED, "", "", ""),
                    actorA.membershipId(),
                    "Bearer tenant-a"));
    assertThat(done.task().status()).isEqualTo(WritebackStatus.CONFIRMED);
    assertThat(done.task().channelOperationId()).as("凭据留痕").isNotNull();
  }

  @Test
  void 失败态排退避且退避时刻断言写回值() throws Exception {
    UUID record = seedRecord(a, "DHL", "WB-REJ-1", SHIP_AT, 1);
    String id = createTask(record).task().id().toString();
    asA(() -> service.submit(id, null, actorA.membershipId(), "Bearer tenant-a"));
    Instant rejectedAt = clock.instant();

    var detail =
        asA(
            () ->
                service.registerOutcome(
                    id,
                    new ShipmentWritebackOutcomeInput(
                        WritebackStatus.REJECTED, "", "运单号格式非法", ""),
                    actorA.membershipId(),
                    "Bearer tenant-a"));

    assertThat(detail.task().status()).isEqualTo(WritebackStatus.REJECTED);
    // 断言**写回的值**：第 1 次尝试后 60s，不是「大约 60 秒」
    assertThat(detail.task().nextAttemptAt().toInstant())
        .isEqualTo(rejectedAt.plusSeconds(60));
    assertThat(channelCalls).anyMatch(c -> c.contains("/fail"));
    assertThat(callbackStatusOf(a, record)).isEqualTo("REJECTED");
  }

  @Test
  void 结果未知必须写明为什么不知道() throws Exception {
    UUID record = seedRecord(a, "SF", "WB-UNK-1", SHIP_AT, 1);
    String id = createTask(record).task().id().toString();
    asA(() -> service.submit(id, null, actorA.membershipId(), "Bearer tenant-a"));

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.registerOutcome(
                            id,
                            new ShipmentWritebackOutcomeInput(
                                WritebackStatus.PENDING_VERIFICATION, "", "", ""),
                            actorA.membershipId(),
                            "Bearer tenant-a")))
        .hasMessageContaining("为什么不知道");

    // 拒绝也必须带原因（否则 9.4.1.2 的 REJECTED 登记 CHECK 会顶回来）
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.registerOutcome(
                            id,
                            new ShipmentWritebackOutcomeInput(WritebackStatus.REJECTED, "", "", ""),
                            actorA.membershipId(),
                            "Bearer tenant-a")))
        .hasMessageContaining("必须写明原因");
  }

  @Test
  void 结果未知既不能被重试也不能被当成成功() throws Exception {
    UUID record = seedRecord(a, "SF", "WB-UNK-2", SHIP_AT, 1);
    String id = createTask(record).task().id().toString();
    asA(() -> service.submit(id, null, actorA.membershipId(), "Bearer tenant-a"));

    var unknown =
        asA(
            () ->
                service.registerOutcome(
                    id,
                    new ShipmentWritebackOutcomeInput(
                        WritebackStatus.PENDING_VERIFICATION, "", "平台接口超时未返回", ""),
                    actorA.membershipId(),
                    "Bearer tenant-a"));

    // 1) 未知是独立状态，不是失败也不是成功
    assertThat(unknown.task().status()).isEqualTo(WritebackStatus.PENDING_VERIFICATION);
    assertThat(unknown.task().resendAllowed()).isFalse();
    // 2) 未知**不排**下一次尝试：排了就等于默认可以重发
    assertThat(unknown.task().nextAttemptAt()).isNull();
    // 3) 重试被拒，且说的是「先查证」
    assertThatThrownBy(
            () -> asA(() -> service.retry(id, null, actorA.membershipId(), "Bearer tenant-a")))
        .hasMessageContaining("先查证");
    // 4) 提交路径同样被拒（与重试共用同一道闸门）
    assertThatThrownBy(
            () -> asA(() -> service.submit(id, null, actorA.membershipId(), "Bearer tenant-a")))
        .hasMessageContaining("先查证");
    // 5) 批量重试也拿它不动
    var batch =
        asA(
            () ->
                service.batchRetry(
                    new ShipmentWritebackBatchRetryInput(null, null, 50, ""),
                    actorA.membershipId(),
                    "Bearer tenant-a"));
    assertThat(batch.blockedUnknown()).isEqualTo(1);
    assertThat(batch.submitted()).isZero();
    // 6) 界面上也不能把它画成可重发
    assertThat(unknown.task().blockedReason()).contains("先查证");
    // 7) 9.4.1.2 侧如实记「已提交 + 结果未知待查证」，不是拒绝
    assertThat(callbackStatusOf(a, record)).isEqualTo("SUBMITTED");
  }

  @Test
  void 查证结论仍是未知时继续禁止重发() throws Exception {
    String id = toUnknown("WB-UNK-3");
    var still =
        asA(
            () ->
                service.verify(
                    id,
                    new ShipmentWritebackVerifyInput(
                        WritebackVerificationConclusion.STILL_UNKNOWN,
                        WritebackVerificationSource.CHANNEL_VERIFY,
                        "平台侧查询接口同样超时"),
                    actorA.membershipId(),
                    "Bearer tenant-a"));
    assertThat(still.task().status()).isEqualTo(WritebackStatus.PENDING_VERIFICATION);
    assertThat(still.task().verification()).isEqualTo(WritebackVerification.STILL_UNKNOWN);
    assertThat(still.task().nextAttemptAt()).as("仍未定论就不排下一次尝试").isNull();
    assertThat(still.verifications()).hasSize(1);
    assertThatThrownBy(
            () -> asA(() -> service.retry(id, null, actorA.membershipId(), "Bearer tenant-a")))
        .hasMessageContaining("先出查证结论");
  }

  @Test
  void 查证确认已应用时转成功且不得重发() throws Exception {
    String id = toUnknown("WB-UNK-4");
    var applied =
        asA(
            () ->
                service.verify(
                    id,
                    new ShipmentWritebackVerifyInput(
                        WritebackVerificationConclusion.CONFIRMED_APPLIED,
                        WritebackVerificationSource.CHANNEL_VERIFY,
                        "平台回查到请求号 REQ-9911"),
                    actorA.membershipId(),
                    "Bearer tenant-a"));
    assertThat(applied.task().status()).isEqualTo(WritebackStatus.CONFIRMED);
    assertThat(applied.task().verification()).isEqualTo(WritebackVerification.CONFIRMED_APPLIED);
    assertThat(applied.task().resendAllowed()).isFalse();
    assertThatThrownBy(
            () -> asA(() -> service.retry(id, null, actorA.membershipId(), "Bearer tenant-a")))
        .hasMessageContaining("终态");
    assertThat(channelCalls).anyMatch(c -> c.contains("/verify"));
  }

  @Test
  void 查证确认未应用时才允许重发并排下一次() throws Exception {
    String id = toUnknown("WB-UNK-5");
    Instant at = clock.instant();
    var notApplied =
        asA(
            () ->
                service.verify(
                    id,
                    new ShipmentWritebackVerifyInput(
                        WritebackVerificationConclusion.CONFIRMED_NOT_APPLIED,
                        WritebackVerificationSource.CHANNEL_VERIFY,
                        "平台侧查无此发货记录"),
                    actorA.membershipId(),
                    "Bearer tenant-a"));
    assertThat(notApplied.task().status()).isEqualTo(WritebackStatus.REJECTED);
    assertThat(notApplied.task().nextAttemptAt().toInstant()).isEqualTo(at.plusSeconds(60));

    // 到点后才真的放行：退避未到点时先被拒
    assertThatThrownBy(
            () -> asA(() -> service.retry(id, null, actorA.membershipId(), "Bearer tenant-a")))
        .hasMessageContaining("退避未到点");
    clock.set(at.plusSeconds(61));
    var retried = asA(() -> service.retry(id, null, actorA.membershipId(), "Bearer tenant-a"));
    assertThat(retried.task().status()).isEqualTo(WritebackStatus.SUBMITTED);
    assertThat(retried.task().attemptCount()).isEqualTo(2);
  }

  @Test
  void 人工查证必须留主体与依据() throws Exception {
    String id = toUnknown("WB-UNK-6");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.verify(
                            id,
                            new ShipmentWritebackVerifyInput(
                                WritebackVerificationConclusion.CONFIRMED_APPLIED,
                                WritebackVerificationSource.MANUAL_ATTESTATION,
                                ""),
                            actorA.membershipId(),
                            "Bearer tenant-a")))
        .hasMessageContaining("依据");
  }

  // ==========================================================================
  // 重试与退避
  // ==========================================================================
  @Test
  void 批量重试逐条说清谁被重投谁被哪道闸门拦下() throws Exception {
    // 一条待回传（可投）、一条失败未到退避点、一条未知（禁重发）
    String fresh = createTask(seedRecord(a, "SF", "WB-B-1", SHIP_AT, 1)).task().id().toString();
    String rejected = createTask(seedRecord(a, "DHL", "WB-B-2", SHIP_AT, 1)).task().id().toString();
    asA(() -> service.submit(rejected, null, actorA.membershipId(), "Bearer tenant-a"));
    asA(
        () ->
            service.registerOutcome(
                rejected,
                new ShipmentWritebackOutcomeInput(WritebackStatus.REJECTED, "", "被平台拒绝", ""),
                actorA.membershipId(),
                "Bearer tenant-a"));
    toUnknown("WB-B-3");

    var batch =
        asA(
            () ->
                service.batchRetry(
                    new ShipmentWritebackBatchRetryInput(null, null, 50, "补一次"),
                    actorA.membershipId(),
                    "Bearer tenant-a"));

    assertThat(batch.submitted()).as("只有待回传那条真被重投").isEqualTo(1);
    assertThat(batch.rescheduled()).as("退避未到点改排而不是提前放出去").isEqualTo(1);
    assertThat(batch.blockedUnknown()).isEqualTo(1);
    assertThat(batch.blocked()).anyMatch(s -> s.contains("先查证"));
  }

  @Test
  void 达到重试上限后不再放行() throws Exception {
    UUID record = seedRecord(a, "SF", "WB-CAP-1", SHIP_AT, 1);
    String id = createTask(record).task().id().toString();
    // 走**合法路径**到失败态（直接改 PENDING → REJECTED 会被状态机触发器拒，那是好事）
    asA(() -> service.submit(id, null, actorA.membershipId(), "Bearer tenant-a"));
    asA(
        () ->
            service.registerOutcome(
                id,
                new ShipmentWritebackOutcomeInput(WritebackStatus.REJECTED, "", "被平台拒绝", ""),
                actorA.membershipId(),
                "Bearer tenant-a"));
    // 退避已到点、但次数已顶格
    clock.set(clock.instant().plusSeconds(120));
    asA(() -> sql.execute("update shipment_writeback_task set max_attempts = 1 where id = ?",
        UUID.fromString(id)));

    assertThatThrownBy(
            () -> asA(() -> service.retry(id, null, actorA.membershipId(), "Bearer tenant-a")))
        .hasMessageContaining("已达重试上限");
    var row = asA(() -> service.task(id));
    assertThat(row.task().resendAllowed()).isFalse();
    assertThat(row.task().blockedReason()).contains("已达重试上限");
  }

  // ==========================================================================
  // 人工修正
  // ==========================================================================
  @Test
  void 人工修正新建一版且幂等键必须变化() throws Exception {
    UUID record = seedRecord(a, "SF", "WB-WRONG-1", SHIP_AT, 1);
    String first = createTask(record).task().id().toString();
    var corrected =
        asA(
            () ->
                service.correct(
                    first,
                    new ShipmentWritebackCorrectionInput(
                        "YTO", "WB-FIXED-1", SHIP_AT, "PO-1", null, "原运单号录错了"),
                    actorA.membershipId()));
    var v2 = corrected.task();
    assertThat(v2.revision()).isEqualTo(2);
    assertThat(v2.correctionOf()).isEqualTo(UUID.fromString(first));
    assertThat(v2.idempotencyKey())
        .as("修正必须换键，否则平台侧会当重复静默丢弃")
        .isEqualTo("ship-update:" + record + ":r2");
    assertThat(corrected.payloadJson()).contains("WB-FIXED-1").contains("YTO");

    // 原任务转 SUPERSEDED 留作证据，不被删改
    var old = asA(() -> service.task(first));
    assertThat(old.task().status()).isEqualTo(WritebackStatus.SUPERSEDED);
    assertThat(old.timeline()).anyMatch(e -> e.eventKind().equals("SUPERSEDED"));
  }

  @Test
  void 幂等键不可就地改写且决定不回传的终态仍可被修正() throws Exception {
    UUID record = seedRecord(a, "SF", "WB-IMM-1", SHIP_AT, 1);
    String id = createTask(record).task().id().toString();

    // DB 级闸门：绕过服务层直接改键会被拒
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update shipment_writeback_task set idempotency_key = ? where id = ?",
                            "ship-update:hack:r9", UUID.fromString(id))))
        .hasMessageContaining("idempotency_key 不可就地改写");

    // SKIPPED 是终态证据：修正新版指向它，但**不**把它改写掉
    asA(() -> sql.execute("update shipment_writeback_task set status = 'SKIPPED',"
        + " correction_reason = '平台不支持该承运商', next_attempt_at = null where id = ?",
        UUID.fromString(id)));
    var fixed =
        asA(
            () ->
                service.correct(
                    id,
                    new ShipmentWritebackCorrectionInput(null, null, null, null, null,
                        "换一家承运商再试"),
                    actorA.membershipId()));
    assertThat(fixed.task().revision()).isEqualTo(2);
    var old = asA(() -> service.task(id));
    assertThat(old.task().status())
        .as("「当时为什么决定不回传」必须留在台账上")
        .isEqualTo(WritebackStatus.SKIPPED);
  }

  @Test
  void 已受理与结果未明的在途任务都拒绝修正() throws Exception {
    UUID record = seedRecord(a, "SF", "WB-INFL-1", SHIP_AT, 1);
    String id = createTask(record).task().id().toString();
    asA(() -> service.submit(id, null, actorA.membershipId(), "Bearer tenant-a"));

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.correct(
                            id,
                            new ShipmentWritebackCorrectionInput(null, null, null, null, null,
                                "想改一下"),
                            actorA.membershipId())))
        .hasMessageContaining("结果未回");
  }

  // ==========================================================================
  // DB 级闸门
  // ==========================================================================
  @Test
  void 未知态绕过服务层直接提交会被触发器拒() throws Exception {
    UUID record = seedRecord(a, "SF", "WB-DB-1", SHIP_AT, 1);
    String id = createTask(record).task().id().toString();
    // 先走服务层提交到 SUBMITTED，再用裸 SQL 落到未知态：
    // PENDING → PENDING_VERIFICATION 被 guard_shipment_writeback_state 直接拒掉
    // （未知只能从「已提交、结果没回」这个事实推出来，不能凭空造），
    // 所以这一段不能用一步到位。
    asA(() -> service.submit(id, null, actorA.membershipId(), "Bearer tenant-a"));
    asA(() -> sql.execute("update shipment_writeback_task set status = 'PENDING_VERIFICATION',"
        + " last_error = 'x', next_attempt_at = null where id = ?", UUID.fromString(id)));

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update shipment_writeback_task set status = 'SUBMITTED',"
                                + " channel_operation_id = ?, submitted_at = now(),"
                                + " next_attempt_at = null where id = ?",
                            UUID.randomUUID(), UUID.fromString(id))))
        .as("未知态直接重发 = 平台侧重复发货，必须由 DB 拦下")
        .hasMessageContaining("必须先查证");
  }

  @Test
  void 凭据表是append_only的_权限与触发器两层都在() throws Exception {
    UUID record = seedRecord(a, "SF", "WB-AP-1", SHIP_AT, 1);
    String id = createTask(record).task().id().toString();
    asA(() -> service.submit(id, null, actorA.membershipId(), "Bearer tenant-a"));
    asA(
        () ->
            service.registerOutcome(
                id,
                new ShipmentWritebackOutcomeInput(WritebackStatus.CONFIRMED, "REQ-1", "", ""),
                actorA.membershipId(),
                "Bearer tenant-a"));
    assertThat(asA(() -> sql.fetch("select id from shipment_writeback_attempt")).size())
        .isEqualTo(1);

    // 第 1 层：运行角色没有 UPDATE/DELETE 权限
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update shipment_writeback_attempt set error_message = 'x'")))
        .as("权限层兜底")
        .satisfies(t -> assertThat(sqlStateOf(t)).isEqualTo("42501")); // insufficient_privilege
    // 第 2 层：触发器。用**表属主**连接（它有 UPDATE 权限），否则第 1 层先把门关上，
    // 触发器就成了永远跑不到的死代码 —— 而 V147 的原话正是「两层都在才不算死代码」。
    // 另：必须先真的落一行查证凭据。BEFORE UPDATE ... FOR EACH ROW 在空表上不触发，
    // 对着空表断言「被拒」会得到一个什么都没发生的假通过。
    String unknownId = toUnknown("WB-AP-2");
    asA(
        () ->
            service.verify(
                unknownId,
                new ShipmentWritebackVerifyInput(
                    WritebackVerificationConclusion.STILL_UNKNOWN,
                    WritebackVerificationSource.MANUAL_ATTESTATION,
                    "平台上人工看过，确实没有这条发货记录"),
                actorA.membershipId(),
                "Bearer tenant-a"));
    assertThat(asA(() -> sql.fetch("select id from shipment_writeback_verification")).size())
        .as("查证凭据行必须真的落库，否则下面的触发器断言是空跑")
        .isEqualTo(1);
    assertThatThrownBy(
            () -> {
              try (var c =
                      DriverManager.getConnection(
                          pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                  var st =
                      c.prepareStatement(
                          "update shipment_writeback_verification set evidence = 'x'")) {
                st.executeUpdate();
              }
            })
        .as("表属主有 UPDATE 权限，仍然必须被 append-only 触发器拒下")
        .satisfies(
            t -> {
              assertThat(sqlStateOf(t)).isEqualTo("23514"); // check_violation
              assertThat(allMessagesOf(t)).contains("append-only");
            });
  }

  /**
   * 取异常链里最内层 {@link java.sql.SQLException} 的 SQLSTATE。
   *
   * <p>刻意不看异常 message：jOOQ 把驱动原文包掉后只剩
   * {@code "jOOQ; bad SQL grammar [...]"}，断言消息文本等于断言驱动的包装实现。
   * SQLSTATE 是 PostgreSQL 对「权限不足」「约束被违反」的稳定分类，正是这两道闸门
   * 各自应当落在的类别。
   */
  static String sqlStateOf(Throwable t) {
    for (Throwable cur = t; cur != null; cur = cur.getCause()) {
      if (cur instanceof java.sql.SQLException se && se.getSQLState() != null) {
        return se.getSQLState();
      }
      if (cur.getCause() == cur) break;
    }
    return null;
  }

  /** 异常链上所有 message 拼起来（驱动原文往往挂在最内层）。 */
  static String allMessagesOf(Throwable t) {
    StringBuilder sb = new StringBuilder();
    for (Throwable cur = t; cur != null; cur = cur.getCause()) {
      if (cur.getMessage() != null) sb.append(cur.getMessage()).append('\n');
      if (cur.getCause() == cur) break;
    }
    return sb.toString();
  }

  // ==========================================================================
  // 清单 / 看板 / 汇总
  // ==========================================================================
  @Test
  void 清单筛选分组与汇总都是真SQL() throws Exception {
    createTask(seedRecord(a, "SF", "WB-L-1", SHIP_AT, 1));
    createTask(seedRecord(a, "DHL", "WB-L-2", SHIP_AT, 3));
    String unknown = toUnknown("WB-L-3");

    var page = asA(() -> service.tasks(null, null, null, null, "WB-L-2", null, 0, 50));
    assertThat(page.items()).as("关键字筛选是真的").hasSize(1);
    assertThat(page.totalElements()).isEqualTo(1);

    var byCarrier =
        asA(() -> service.board(null, null, null, WritebackGroupBy.CARRIER));
    assertThat(byCarrier.groups()).hasSize(2);
    assertThat(byCarrier.summary().totalTasks()).isEqualTo(3);
    assertThat(byCarrier.summary().unknownTasks()).isEqualTo(1);

    // 分页不影响汇总
    var firstPage = asA(() -> service.tasks(null, null, null, null, null, null, 0, 1));
    assertThat(firstPage.items()).hasSize(1);
    assertThat(firstPage.totalPages()).isEqualTo(3);
    assertThat(firstPage.summary().totalTasks()).isEqualTo(3);

    assertThat(asA(() -> service.events("OUTCOME_UNKNOWN", null, 0, 50)).items())
        .hasSize(1);
    assertThat(asA(() -> service.events(null, UUID.fromString(unknown), 0, 50)).items())
        .isNotEmpty();
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() throws Exception {
    createTaskRef(a, actorA, seedRecord(a, "SF", "WB-T-A", SHIP_AT, 1));
    createTaskRef(b, actorB, seedRecord(b, "SF", "WB-T-B", SHIP_AT, 1));

    var pageA = asA(() -> service.tasks(null, null, null, null, null, null, 0, 50));
    var pageB = asB(() -> service.tasks(null, null, null, null, null, null, 0, 50));
    assertThat(pageA.items()).hasSize(1);
    assertThat(pageB.items()).as("B 租户看不到 A 的回传任务").hasSize(1);
    assertThat(pageA.items().get(0).masterWaybillNo()).isEqualTo("WB-T-A");
    assertThat(pageB.items().get(0).masterWaybillNo()).isEqualTo("WB-T-B");

    var idA = pageA.items().get(0).id();
    assertThatThrownBy(() -> asB(() -> service.task(idA.toString())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在");
    assertThat(asB(() -> service.events(null, idA, 0, 50)).items()).isEmpty();

    // 批量重试也不能跨租户动手：B 只该动到 B 自己那条（WB-T-B），
    // A 那条既不该被重投也不该被推进状态。
    var batchB =
        asB(
            () ->
                service.batchRetry(
                    new ShipmentWritebackBatchRetryInput(null, null, 50, ""),
                    actorB.membershipId(),
                    "Bearer tenant-b"));
    assertThat(batchB.retried())
        .as("B 的批量重试只碰 B 自己的任务")
        .allMatch(r -> r.masterWaybillNo().equals("WB-T-B"));
    assertThat(asA(() -> service.tasks(null, null, null, null, null, null, 0, 50)).items()
            .get(0).status())
        .as("A 的任务没被 B 的批量重试推进")
        .isEqualTo(WritebackStatus.PENDING);
  }

  // ==========================================================================
  // 断言 2：伪造租户上下文无效
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    createTask(seedRecord(a, "SF", "WB-FORGED", SHIP_AT, 1));
    UUID recordA = seedRecord(a, "SF", "WB-FORGED-2", SHIP_AT, 1);
    var idA = asA(() -> service.tasks(null, null, null, null, null, null, 0, 50)).items().get(0).id();

    // 事务外没有「环境租户」：任何租户的行都看不见
    assertThat(sql.fetch("select id from shipment_writeback_task").size()).isZero();

    // B 的会话 + 伪造指向 A 的头 → 依然 404
    http.perform(
            get("/trade/fulfillment/shipment-writeback/tasks/{id}", idA)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    // A 的会话 + 伪造指向 B 的头 → 仍按 A 解析
    http.perform(
            get("/trade/fulfillment/shipment-writeback/tasks/{id}", idA)
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk());

    // 写入路径同样不认头：B 会话伪造 A 的头，建任务落不到 A
    http.perform(
            post("/trade/fulfillment/shipment-writeback/tasks")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"platformCode\":\"AMAZON\",\"shopId\":\"" + SHOP_OK
                        + "\",\"recordIds\":[\"" + recordA + "\"]}"))
        .andExpect(status().isNotFound());
    assertThat(asA(() -> service.tasks(null, null, null, null, null, null, 0, 50)).items())
        .as("伪造头不得把回传任务写进别的租户")
        .hasSize(1);

    // 事务外依旧看不见任何租户的行
    assertThat(sql.fetch("select id from shipment_writeback_task").size()).isZero();
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    createTaskRef(a, actorA, seedRecord(a, "SF", "WB-POOL-A", SHIP_AT, 1));
    createTaskRef(b, actorB, seedRecord(b, "SF", "WB-POOL-B", SHIP_AT, 1));
    // 池大小为 1：下面每次读写都复用同一条连接
    for (int i = 0; i < 5; i++) {
      assertThat(asA(() -> service.tasks(null, null, null, null, null, null, 0, 50)).items()
              .get(0).masterWaybillNo())
          .as("第 %d 轮 A", i)
          .isEqualTo("WB-POOL-A");
      assertThat(asB(() -> service.tasks(null, null, null, null, null, null, 0, 50)).items()
              .get(0).masterWaybillNo())
          .as("第 %d 轮 B", i)
          .isEqualTo("WB-POOL-B");
      assertThat(sql.fetch("select id from shipment_writeback_task").size())
          .as("第 %d 轮事务外", i)
          .isZero();
    }
  }

  // ==========================================================================
  // HTTP 边界
  // ==========================================================================
  @Test
  void 端点确实走HTTP且未认证与只读被拒() throws Exception {
    UUID record = seedRecord(a, "SF", "WB-HTTP-1", SHIP_AT, 1);
    http.perform(
            post("/trade/fulfillment/shipment-writeback/tasks")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"platformCode\":\"AMAZON\",\"shopId\":\"" + SHOP_OK
                        + "\",\"recordIds\":[\"" + record + "\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.createdTasks").value(1));

    http.perform(
            get("/trade/fulfillment/shipment-writeback/tasks")
                .header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));

    http.perform(get("/trade/fulfillment/shipment-writeback/tasks"))
        .andExpect(status().isUnauthorized());
    http.perform(get("/trade/fulfillment/shipment-writeback/capabilities"))
        .andExpect(status().isUnauthorized());
    http.perform(
            post("/trade/fulfillment/shipment-writeback/retry-batch")
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"limit\":10}"))
        .andExpect(status().isForbidden());
    http.perform(
            get("/trade/fulfillment/shipment-writeback/tasks/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
    http.perform(
            get("/trade/fulfillment/shipment-writeback/tasks/not-a-uuid")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
    // 刻意**没有** PUT / DELETE：凭据行 append-only，改内容只能走修正
    http.perform(
            delete("/trade/fulfillment/shipment-writeback/tasks/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isMethodNotAllowed());
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  ShipmentWritebackTaskDetail createTask(UUID recordId) {
    return createTaskRef(a, actorA, recordId);
  }

  /**
   * 在<b>指定租户</b>的上下文里建任务。
   *
   * <p>刻意不接受「默认租户」：租户断言里 B 租户的任务必须真的在 B 的上下文里建，
   * 否则 B 那条记录会被 RLS 挡成「不存在」，create 静悄悄返回 0 条 ——
   * 测试看着过了，被测的隔离其实没跑到。
   */
  ShipmentWritebackTaskDetail createTaskRef(UUID tenant, AccessService.Access actor, UUID recordId) {
    String key = ShipmentWritebackService.idempotencyKey(recordId, 1);
    var created =
        access.run(
            actor,
            "trade.fulfillment.update",
            () ->
                service.create(
                    new ShipmentWritebackCreateInput(
                        "AMAZON", SHOP_OK, null, "PO-" + recordId.toString().substring(0, 4),
                        List.of(recordId), ""),
                    actor.membershipId()));
    assertThat(created.createdTasks()).as("租户 %s 的回传任务应建成", tenant).isEqualTo(1);
    var page =
        access.run(
            actor, "trade.fulfillment.update", () -> service.tasks(null, null, null, null, key, null, 0, 50));
    assertThat(page.items()).hasSize(1);
    return access.run(actor, "trade.fulfillment.update", () -> service.task(page.items().get(0).id().toString()));
  }

  /** 铺一条发货记录并建出回传任务，返回任务 id。 */
  String toUnknown(String waybill) throws Exception {
    return toUnknownRecord(a, actorA, waybill);
  }

  /** 把任务推进到「结果未知」，返回任务 id。 */
  String toUnknownRecord(UUID tenant, AccessService.Access actor, String waybill)
      throws Exception {
    String id =
        createTaskRef(tenant, actor, seedRecord(tenant, "SF", waybill, SHIP_AT, 1)).task().id().toString();
    String bearer = tenant.equals(a) ? "Bearer tenant-a" : "Bearer tenant-b";
    access.run(
        actor,
        "trade.fulfillment.update",
        () -> service.submit(id, null, actor.membershipId(), bearer));
    access.run(
        actor,
        "trade.fulfillment.update",
        () ->
            service.registerOutcome(
                id,
                new ShipmentWritebackOutcomeInput(
                    WritebackStatus.PENDING_VERIFICATION, "", "平台接口超时未返回", ""),
                actor.membershipId(),
                bearer));
    return id;
  }

  String callbackStatusOf(UUID tenant, UUID recordId) {
    return asA(
            () -> sql.fetchOne(
                    "select platform_callback_status from shipment_record where id = ?", recordId)
                .get(0, String.class))
        ;
  }

  /** 铺一条 9.4.1.2 的发货记录（用迁移账号直插，理由见类注释）。 */
  UUID seedRecord(UUID tenant, String carrier, String waybill, OffsetDateTime shippedAt, int parcels)
      throws Exception {
    UUID recordId = UUID.randomUUID();
    UUID handoverId = UUID.randomUUID();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      try (var st =
          c.prepareStatement(
              "insert into shipment_record (id,tenant_id,record_no,source_handover_id,"
                  + " source_packing_order_id,source_outbound_order_id,warehouse_id,carrier_code,"
                  + " carrier_name,master_waybill_no,shipped_at,parcel_count,parcel_item_count,"
                  + " total_quantity,total_gross_weight_g,total_volume_cbm,sales_order_no,shop_ref,"
                  + " synced_at)"
                  + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
        st.setObject(1, recordId);
        st.setObject(2, tenant);
        st.setString(3, "SR-" + waybill);
        st.setObject(4, handoverId);
        st.setObject(5, UUID.randomUUID());
        st.setObject(6, UUID.randomUUID());
        st.setObject(7, UUID.randomUUID());
        st.setString(8, carrier);
        st.setString(9, carrier + " Express");
        st.setString(10, waybill);
        st.setObject(11, shippedAt);
        st.setInt(12, parcels);
        st.setInt(13, parcels * 2);
        st.setBigDecimal(14, new BigDecimal("2.000000"));
        st.setBigDecimal(15, new BigDecimal("1350.000"));
        st.setBigDecimal(16, new BigDecimal("0.012500"));
        st.setString(17, "SO-" + waybill);
        st.setString(18, "SHOP-SF");
        st.setObject(19, shippedAt);
        st.executeUpdate();
      }
      for (int i = 1; i <= parcels; i++) {
        try (var st =
            c.prepareStatement(
                "insert into shipment_record_parcel (id,tenant_id,record_id,parcel_no,"
                    + " source_parcel_id,parcel_code,waybill_no,gross_weight_g,volume_cbm,"
                    + " item_count,total_quantity,synced_at)"
                    + " values (?,?,?,?,?,?,?,?,?,?,?,?)")) {
          st.setObject(1, UUID.randomUUID());
          st.setObject(2, tenant);
          st.setObject(3, recordId);
          st.setInt(4, i);
          st.setObject(5, UUID.randomUUID());
          st.setString(6, "PC-" + i);
          // 第一箱留空以覆盖「逐箱号跟随主运单号」这一支
          st.setString(7, i == 1 ? "" : waybill + "-" + i);
          st.setBigDecimal(8, new BigDecimal("1350.000"));
          st.setBigDecimal(9, new BigDecimal("0.012500"));
          st.setInt(10, 2);
          st.setBigDecimal(11, new BigDecimal("2.000000"));
          st.setObject(12, shippedAt);
          st.executeUpdate();
        }
      }
    }
    return recordId;
  }
}
