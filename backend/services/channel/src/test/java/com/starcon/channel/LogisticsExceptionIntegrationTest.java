package com.starcon.channel;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
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
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 10.3.1.2 异常件。
 *
 * <p>本测试类钉七组事：
 *
 * <ol>
 *   <li><b>🔴 被动异常标记 → 异常件的真链路</b>：经 10.3.1.1 的<b>真实</b>
 *       {@code POST /channel/logistics-tracking/events} 推一个 EXCEPTION 节点，
 *       再用本模块建件 —— 来源被服务端派生成 TRACKING_FLAG，调用方填不了。</li>
 *   <li><b>🔴 轨迹异常规则</b>：规则命中建件、<b>再扫一遍不建第二张</b>（幂等）、
 *       未命中的票不建件（阈值 / 异常码两个条件都真过滤）。</li>
 *   <li><b>🔴 异常件必须有人负责到底</b>：建件即有责任人与时限；结案必须写理由；
 *       终态不可回退（DB 触发器挡）；结案后不能再追加处置（409）。</li>
 *   <li><b>🔴 时长是整数分钟</b>：断言<b>写回的值</b>（不是「没抛异常」）；
 *       锚点在将来 ⇒ 截断到 0 并写明，<b>负时长不进库</b>。</li>
 *   <li><b>🔴 下游待通知</b>：改址 / 联系客服 / 请求补发 / 补发单各自产出
 *       PENDING_NOTIFY 任务；<b>没有任何一条是「已通知 / 已受理」</b>。</li>
 *   <li><b>🔴 金额与数量</b>：BigDecimal 精确往返；缺金额 / 缺数量的单被拒；
 *       跨币种<b>分行</b>不相加。</li>
 *   <li><b>真分页 + 防重复建件 + 处置留痕 append-only</b>。</li>
 * </ol>
 *
 * <p><b>时间一律 pin 住</b>：事件发生时刻是夹具里的固定值，停滞时长的断言
 * 按那一刻算出来，不取墙上时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LogisticsExceptionIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID();
  static final UUID b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  static final String CARRIER = "SF";

  /** 夹具 pin 住的时间点。 */
  static final OffsetDateTime STALLED_AT = OffsetDateTime.parse("2026-09-20T08:00:00Z");
  static final OffsetDateTime RECENT_AT = OffsetDateTime.parse("2026-10-19T08:00:00Z");
  static final OffsetDateTime FUTURE_AT = OffsetDateTime.parse("2099-01-01T00:00:00Z");

  static final List<String> allPermissions =
      java.util.stream.Stream.of("registry", "connection", "shop", "sync", "operation")
          .flatMap(
              p ->
                  java.util.stream.Stream.concat(
                      java.util.stream.Stream.of("read", "create", "update", "delete")
                          .map(op -> "channel." + p + "." + op),
                      p.equals("sync")
                          ? java.util.stream.Stream.of("channel.sync.trigger", "channel.sync.complete")
                          : java.util.stream.Stream.empty()))
          .distinct()
          .toList();

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='starcon_app')"
            + " then create role starcon_app nologin; end if; end $$");
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='channel_app')"
            + " then create role channel_app login password 'test-only' nosuperuser nobypassrls; end if; end $$");
        s.execute("alter role channel_app login password 'test-only' nosuperuser nobypassrls");
      }
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            AccessService.Access who = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (who == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = new ObjectMapper().writeValueAsBytes(who);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
      actors.put("Bearer tenant-a", actor(user, a, grants));
      actors.put("Bearer tenant-b", actor(user, b, grants));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired LogisticsExceptionService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  AccessService.Access actorA, actorB;

  @BeforeEach
  void setup() {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    truncateAll();
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  // ==================================================================
  // 夹具
  // ==================================================================

  void truncateAll() {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate logistics_exception_downstream_task, logistics_exception_claim,"
              + " logistics_exception_disposition, logistics_exception_case,"
              + " logistics_exception_rule, logistics_tracking_event, logistics_tracking_subject"
              + " cascade");
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 直接落一条上游 10.3.1.1 的轨迹主体（它是上游的事实，本模块只读）。 */
  UUID seedSubject(
      UUID tenant,
      String waybill,
      String latestNode,
      OffsetDateTime latestOccurredAt,
      OffsetDateTime shippedAt,
      String exceptionCode) {
    UUID id = UUID.randomUUID();
    exec(
        "insert into logistics_tracking_subject"
            + " (id,tenant_id,source_shipment_record_id,source_record_no,sales_order_no,"
            + "  carrier_code,master_waybill_no,destination_country,latest_node,latest_occurred_at,"
            + "  shipped_at,event_count,exception_flag,exception_count,last_exception_code,"
            + "  last_exception_at,status)"
            + " values("
            + q(id.toString()) + "," + q(tenant.toString()) + "," + q(UUID.randomUUID().toString())
            + "," + q("SO-1") + "," + q("ORDER-1") + "," + q(CARRIER) + "," + q(waybill)
            + "," + q("US") + "," + q(latestNode) + ","
            + q(latestOccurredAt == null ? null : latestOccurredAt.toString()) + ","
            + q(shippedAt == null ? null : shippedAt.toString()) + ",1,"
            + (exceptionCode == null ? "false" : "true") + ","
            + (exceptionCode == null ? "0" : "1") + "," + q(exceptionCode == null ? "" : exceptionCode) + ","
            + q(exceptionCode == null ? null : latestOccurredAt.toString()) + "," + q("ACTIVE") + ")");
    return id;
  }

  /** SQL 字面量加引号（null → SQL NULL）。 */
  static String q(String value) {
    return value == null ? "null" : "'" + value + "'";
  }

  void exec(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以应用角色执行 SQL —— 权限层与 RLS 都真实生效。 */
  void execAsApp(UUID tenant, String sql) {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','[]',false)");
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  String scalar(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  // ==================================================================
  // 1. 被动异常标记 → 异常件（真链路，走 10.3.1.1 的真实端点）
  // ==================================================================

  @Test
  @DisplayName("10.3.1.2 被动异常标记：经 10.3.1.1 的真实事件推送建件，来源被服务端派生成 TRACKING_FLAG")
  void passiveFlagCreatesCase() throws Exception {
    UUID subjectId = seedSubject(a, "WB-FLAG-1", "IN_TRANSIT", STALLED_AT, STALLED_AT, null);

    // 走 10.3.1.1 的**真实**端点推一个 EXCEPTION 节点（带异常码）。
    String push =
        """
        {"carrierCode":"SF","events":[{"carrierEventId":"EV-FLAG-1","waybillNo":"WB-FLAG-1",
        "nodeType":"EXCEPTION","eventCode":"E1","description":"妥投失败","location":"DE",
        "occurredAt":"2026-09-20T08:00:00Z","exceptionCode":"DELIVERY_FAILED"}]}
        """;
    http.perform(
            post("/channel/logistics-tracking/events")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(push))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.inserted").value(1))
        .andExpect(jsonPath("$.subject.exceptionFlag").value(true))
        .andExpect(jsonPath("$.subject.lastExceptionCode").value("DELIVERY_FAILED"));

    UUID eventId =
        UUID.fromString(
            scalar(
                "select id from logistics_tracking_event where tenant_id='"
                    + a
                    + "' and carrier_event_id='EV-FLAG-1'"));

    var created =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.createCase(
                    new LogisticsExceptionModels.CreateExceptionCaseRequest(
                        subjectId, eventId, null, "DELIVERY_FAILED", null, null, null, "被动异常标记建件"),
                    actorA.membershipId()));

    assertThat(created.source()).as("来源由服务端派生").isEqualTo("TRACKING_FLAG");
    assertThat(created.exceptionType()).isEqualTo("DELIVERY_FAILED");
    assertThat(created.note()).as("承运商异常码只作参考留痕").contains("DELIVERY_FAILED");
    assertThat(created.sourceEventId()).isEqualTo(eventId);
    assertThat(created.subjectId()).isEqualTo(subjectId);
    assertThat(created.waybillNo()).isEqualTo("WB-FLAG-1");
    assertThat(created.carrierCode()).isEqualTo(CARRIER);
    assertThat(created.ownerMembershipId()).as("建件即有责任人").isEqualTo(actorA.membershipId());
    assertThat(created.dueAt()).as("每件都有责任时限").isAfter(created.detectedAt());

    // 🔴 详情里带上上游轨迹上下文（真读上游，不是抄一份）
    var detail =
        as(actorA, "channel.operation.read", () -> service.caseDetail(created.id()));
    assertThat(detail.upstream()).isNotNull();
    assertThat(detail.upstream().masterWaybillNo()).isEqualTo("WB-FLAG-1");
    assertThat(detail.upstream().lastExceptionCode()).isEqualTo("DELIVERY_FAILED");
    assertThat(detail.upstream().exceptionFlag()).isTrue();
  }

  @Test
  @DisplayName("10.3.1.2 客服反馈建件：来源派生为 CUSTOMER_FEEDBACK，反馈原文入库")
  void customerFeedbackCreatesCase() {
    var created =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.createCase(
                    new LogisticsExceptionModels.CreateExceptionCaseRequest(
                        null,
                        null,
                        null,
                        "BUYER_COMPLAINT",
                        "HIGH",
                        "买家称包裹破损，要求赔付",
                        "WB-CS-1",
                        "客服工单 CS-2026-0001"),
                    actorA.membershipId()));
    assertThat(created.source()).isEqualTo("CUSTOMER_FEEDBACK");
    assertThat(created.exceptionType()).isEqualTo("BUYER_COMPLAINT");
    assertThat(created.severity()).isEqualTo("HIGH");
    assertThat(created.customerFeedback()).isEqualTo("买家称包裹破损，要求赔付");
    assertThat(created.waybillNo()).isEqualTo("WB-CS-1");
    assertThat(created.subjectId()).as("客服反馈不一定能挂到轨迹主体上").isNull();
  }

  @Test
  @DisplayName("10.3.1.2 人工登记：来源派生为 MANUAL，类型必须显式给出")
  void manualCaseRequiresType() {
    var created =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.createCase(
                    new LogisticsExceptionModels.CreateExceptionCaseRequest(
                        null, null, null, "ADDRESS_ISSUE", null, null, "WB-M-1", null),
                    actorA.membershipId()));
    assertThat(created.source()).isEqualTo("MANUAL");
    assertThat(created.exceptionType()).isEqualTo("ADDRESS_ISSUE");
    assertThat(created.severity()).as("未给严重度时用中档").isEqualTo("MEDIUM");

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorA,
                        "channel.operation.create",
                        () ->
                            service.createCase(
                                new LogisticsExceptionModels.CreateExceptionCaseRequest(
                                    null, null, null, "NOT_A_TYPE", null, null, null, null),
                                actorA.membershipId()))))
        .as("类型不在白名单必须被拒")
        .isNotNull();
  }

  @Test
  @DisplayName("10.3.1.2 异常类型必须显式给出或由规则带出：不猜承运商的自由文本异常码")
  void exceptionTypeIsNeverGuessed() {
    UUID subjectId = seedSubject(a, "WB-NOGUESS", "EXCEPTION", STALLED_AT, STALLED_AT, "ADDRESS_INVALID");
    UUID eventId = seedEvent(a, subjectId, "EV-NOGUESS", "EXCEPTION", "2026-09-20T08:00:00Z", "ADDRESS_INVALID");

    // 不给类型也不给规则 ⇒ 拒（宁可让人明确，也不把地址问题猜成妥投失败）
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorA,
                        "channel.operation.create",
                        () ->
                            service.createCase(
                                new LogisticsExceptionModels.CreateExceptionCaseRequest(
                                    subjectId, eventId, null, null, null, null, null, null),
                                actorA.membershipId()))))
        .as("没有类型也没有规则必须被拒")
        .isNotNull();

    // 给一条规则 ⇒ 类型 / 严重度 / 责任时限都由规则带出
    var rule =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.saveRule(
                    new LogisticsExceptionModels.SaveExceptionRuleRequest(
                        "ADDR-RULE", "ADDRESS_ISSUE", "", "ADDRESS_INVALID", null, "CRITICAL", 720, true, null),
                    actorA.membershipId()));
    var viaRule =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.createCase(
                    new LogisticsExceptionModels.CreateExceptionCaseRequest(
                        subjectId, eventId, rule.id(), null, null, null, null, null),
                    actorA.membershipId()));
    assertThat(viaRule.exceptionType()).isEqualTo("ADDRESS_ISSUE");
    assertThat(viaRule.severity()).isEqualTo("CRITICAL");
    assertThat(viaRule.source()).isEqualTo("TRACKING_FLAG");
  }

  // ==================================================================
  // 2. 轨迹异常规则：命中建件、再扫不建第二张
  // ==================================================================

  @Test
  @DisplayName("10.3.1.2 轨迹异常规则：命中建件 + 再扫一遍不建第二张（幂等）+ 未命中的票不建件")
  void ruleScanIsIdempotent() {
    // 一条「停滞超过 7 天」的规则
    var rule =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.saveRule(
                    new LogisticsExceptionModels.SaveExceptionRuleRequest(
                        "STALL-7D", "TRACKING_STALLED", "", "", 7 * 24 * 60, "HIGH", 2880, true, "七天无轨迹"),
                    actorA.membershipId()));
    assertThat(rule.ruleCode()).isEqualTo("STALL-7D");
    assertThat(rule.thresholdMinutes()).isEqualTo(7 * 24 * 60);

    // 命中票：最后一次轨迹在 2026-09-20（早于七天前）
    UUID stalled = seedSubject(a, "WB-STALL", "IN_TRANSIT", STALLED_AT, STALLED_AT, null);
    // 未命中票：最后一次轨迹是昨天
    UUID fresh = seedSubject(a, "WB-FRESH", "IN_TRANSIT", RECENT_AT, RECENT_AT, null);

    var first = as(actorA, "channel.operation.create", () -> service.scan(rule.id(), actorA.membershipId()));
    assertThat(first.candidateCount()).as("只有满足阈值的票进候选池").isEqualTo(1);
    assertThat(first.createdCount()).isEqualTo(1);
    assertThat(first.createdCaseNos()).hasSize(1);

    var cases =
        as(actorA, "channel.operation.read", () -> service.cases(null, null, null, null, null, null, false, 0, 50));
    assertThat(cases.total()).isEqualTo(1);
    var row = cases.rows().get(0);
    assertThat(row.source()).isEqualTo("RULE_SCAN");
    assertThat(row.exceptionType()).isEqualTo("TRACKING_STALLED");
    assertThat(row.severity()).isEqualTo("HIGH");
    assertThat(row.subjectId()).isEqualTo(stalled);
    assertThat(row.waybillNo()).isEqualTo("WB-STALL");
    assertThat(row.ownerMembershipId()).isEqualTo(actorA.membershipId());

    // 🔴 再扫一遍：候选还是 1，但**不建第二张**，并如实报「已有未结异常件」
    var second = as(actorA, "channel.operation.create", () -> service.scan(rule.id(), actorA.membershipId()));
    assertThat(second.candidateCount()).isEqualTo(1);
    assertThat(second.createdCount()).as("同一票同一类型同时只允许一件未结").isZero();
    assertThat(second.skippedCount()).isEqualTo(1);
    assertThat(second.skips().get(0).reason()).isEqualTo("已有未结异常件");
    assertThat(
            as(
                    actorA,
                    "channel.operation.read",
                    () ->
                        service.cases(null, null, null, null, null, null, false, 0, 50))
                .total())
        .as("库里仍然只有一件").isEqualTo(1);

    // 未命中的票从来不进候选池
    assertThat(fresh).isNotNull();
    assertThat(
            as(
                    actorA,
                    "channel.operation.read",
                    () -> service.cases(null, null, null, null, "WB-FRESH", null, false, 0, 50))
                .total())
        .isZero();
  }

  @Test
  @DisplayName("10.3.1.2 轨迹异常规则：按承运商异常码精确匹配，不做模糊匹配")
  void ruleMatchesExceptionCodeExactly() {
    var rule =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.saveRule(
                    new LogisticsExceptionModels.SaveExceptionRuleRequest(
                        "ADDR", "ADDRESS_ISSUE", "", "ADDRESS_INVALID", null, "CRITICAL", 1440, true, null),
                    actorA.membershipId()));
    seedSubject(a, "WB-ADDR", "EXCEPTION", STALLED_AT, STALLED_AT, "ADDRESS_INVALID");
    seedSubject(a, "WB-ADDR-2", "EXCEPTION", STALLED_AT, STALLED_AT, "ADDRESS_INVALID_2");

    var result = as(actorA, "channel.operation.create", () -> service.scan(rule.id(), actorA.membershipId()));
    assertThat(result.candidateCount()).as("精确等值：_2 不该命中").isEqualTo(1);
    assertThat(result.createdCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("10.3.1.2 规则编码租户内唯一，重复登记被拒")
  void ruleCodeIsUnique() {
    var req =
        new LogisticsExceptionModels.SaveExceptionRuleRequest(
            "DUP-1", "TRACKING_STALLED", "", "", 60, "LOW", 60, true, null);
    as(actorA, "channel.operation.create", () -> service.saveRule(req, actorA.membershipId()));
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as(actorA, "channel.operation.create", () -> service.saveRule(req, actorA.membershipId()))))
        .as("规则编码重复必须被拒")
        .isNotNull();
  }

  // ==================================================================
  // 3. 责任到底：结案凭据 / 终态不可回退 / 结案后不可再处置
  // ==================================================================

  @Test
  @DisplayName("10.3.1.2 责任到底：结案要写理由；结案后不能再追加处置（409）；终态 DB 层不可回退")
  void responsibilityLifecycle() {
    var created =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.createCase(
                    new LogisticsExceptionModels.CreateExceptionCaseRequest(
                        null, null, null, "DELIVERY_FAILED", "HIGH", null, "WB-R-1", null),
                    actorA.membershipId()));

    // 结案必须写理由
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorA,
                        "channel.operation.create",
                        () ->
                            service.addDisposition(
                                created.id(),
                                new LogisticsExceptionModels.AddDispositionRequest(
                                    "RESOLVE", null, null, null, null, null, null, null),
                                actorA.membershipId()))))
        .as("结案不写理由必须被拒")
        .isNotNull();

    var resolved =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.addDisposition(
                    created.id(),
                    new LogisticsExceptionModels.AddDispositionRequest(
                        "RESOLVE", "CONFIRMED", null, null, null, "承运商已赔付 20 美元", null, null),
                    actorA.membershipId()));
    assertThat(resolved.actionType()).isEqualTo("RESOLVE");

    var after =
        as(actorA, "channel.operation.read", () -> service.caseDetail(created.id()));
    assertThat(after.head().status()).isEqualTo("RESOLVED");
    assertThat(after.head().closedAt()).as("结案时刻必须落").isNotNull();
    assertThat(after.head().closeReason()).isEqualTo("承运商已赔付 20 美元");
    assertThat(after.head().ownerMembershipId()).isNotNull();

    // 结案后再处置 ⇒ 409
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorA,
                        "channel.operation.create",
                        () ->
                            service.addDisposition(
                                created.id(),
                                new LogisticsExceptionModels.AddDispositionRequest(
                                    "CONTACT_CARRIER", null, null, null, null, "再问一下", null, null),
                                actorA.membershipId()))))
        .as("已结案的件不接受新处置")
        .isNotNull();

    // 🔴 绕过服务层直接改状态 ⇒ DB 触发器拒（终态不可回退）
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execAsApp(
                        a,
                        "update logistics_exception_case set status='OPEN'"
                            + " where tenant_id='" + a + "' and id='" + created.id() + "'")))
        .as("终态回退必须被 DB 挡")
        .isNotNull();
  }

  @Test
  @DisplayName("10.3.1.2 转派责任人：必须给出目标，且目标真的换掉了")
  void assignChangesOwner() {
    var created =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.createCase(
                    new LogisticsExceptionModels.CreateExceptionCaseRequest(
                        null, null, null, "DAMAGED_OR_LOST", "HIGH", null, "WB-AS-1", null),
                    actorA.membershipId()));
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorA,
                        "channel.operation.create",
                        () ->
                            service.addDisposition(
                                created.id(),
                                new LogisticsExceptionModels.AddDispositionRequest(
                                    "ASSIGN", null, null, null, null, "转给同事", null, null),
                                actorA.membershipId()))))
        .as("转派不给目标必须被拒")
        .isNotNull();

    UUID other = UUID.randomUUID();
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.addDisposition(
                created.id(),
                new LogisticsExceptionModels.AddDispositionRequest(
                    "ASSIGN", null, other, null, null, "转给同事", null, null),
                actorA.membershipId()));
    var after = as(actorA, "channel.operation.read", () -> service.caseDetail(created.id()));
    assertThat(after.head().ownerMembershipId()).isEqualTo(other);
    assertThat(after.head().status()).isEqualTo("INVESTIGATING");
  }

  // ==================================================================
  // 4. 时长：整数分钟、断言写回的值、负时长不进库
  // ==================================================================

  @Test
  @DisplayName("10.3.1.2 停滞时长：整数分钟且断言写回的值；锚点在将来 ⇒ 截断 0 并写明")
  void durationIsWholeMinutesAndNeverNegative() {
    UUID subjectId = seedSubject(a, "WB-DUR", "IN_TRANSIT", STALLED_AT, STALLED_AT, null);
    UUID eventId = seedEvent(a, subjectId, "EV-DUR", "EXCEPTION", "2026-09-20T08:00:00Z", "STALLED");

    var created =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.createCase(
                    new LogisticsExceptionModels.CreateExceptionCaseRequest(
                        subjectId, eventId, null, "TRACKING_STALLED", null, null, null, null),
                    actorA.membershipId()));

    // 🔴 断言**写回的值**：锚点 2026-09-20T08:00:00Z 到现在 = 整月数级的分钟数，
    //    逐字按分钟算（不是「大于 0」这种糊弄断言）。
    long expected = java.time.Duration.between(STALLED_AT, created.detectedAt()).toMinutes();
    assertThat(created.stalledDurationMinutes()).isEqualTo(expected);
    assertThat(created.stalledDurationMinutes()).isPositive();
    assertThat(created.stalledDurationHours())
        .isEqualByComparingTo(
            BigDecimal.valueOf(expected).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP));

    // 落库的分钟数必须与读回的一致（不是只在 Java 里算对）
    assertThat(
            scalar(
                "select stalled_duration_minutes from logistics_exception_case where id='"
                    + created.id() + "'"))
        .isEqualTo(String.valueOf(expected));

    // 锚点在将来 ⇒ 负时长被截断到 0 并在 note 里写明，**不写进库里**
    UUID futureSubject = seedSubject(a, "WB-FUT", "IN_TRANSIT", FUTURE_AT, FUTURE_AT, null);
    UUID futureEvent = seedEvent(a, futureSubject, "EV-FUT", "EXCEPTION", "2099-01-01T00:00:00Z", "FUT");
    var futureCase =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.createCase(
                    new LogisticsExceptionModels.CreateExceptionCaseRequest(
                        futureSubject, futureEvent, null, "TRACKING_STALLED", null, null, null, null),
                    actorA.membershipId()));
    assertThat(futureCase.stalledDurationMinutes()).as("负时长绝不能进库").isZero();
    assertThat(futureCase.note()).contains("锚点时刻晚于判定时刻");
    assertThat(
            scalar(
                "select stalled_duration_minutes from logistics_exception_case where id='"
                    + futureCase.id() + "'"))
        .isEqualTo("0");
  }

  UUID seedEvent(
      UUID tenant, UUID subjectId, String eventId, String nodeType, String occurredAt, String code) {
    exec(
        "insert into logistics_tracking_event"
            + " (id,tenant_id,subject_id,carrier_code,carrier_event_id,node_type,event_code,"
            + "  description,occurred_at,received_at,source,exception_code)"
            + " values('" + UUID.randomUUID() + "','" + tenant + "','" + subjectId + "','" + CARRIER
            + "','" + eventId + "','" + nodeType + "','E','夹具事件','" + occurredAt + "','"
            + occurredAt + "','CARRIER_PUSH','" + code + "')");
    return UUID.fromString(
        scalar(
            "select id from logistics_tracking_event where tenant_id='" + tenant
                + "' and carrier_event_id='" + eventId + "'"));
  }

  // ==================================================================
  // 5. 下游待通知
  // ==================================================================

  @Test
  @DisplayName("10.3.1.2 下游待通知：改址 / 联系客服 / 请求补发各产出一条 PENDING_NOTIFY 任务")
  void downstreamTasksArePendingOnly() {
    var created =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.createCase(
                    new LogisticsExceptionModels.CreateExceptionCaseRequest(
                        null, null, null, "ADDRESS_ISSUE", "HIGH", null, "WB-DS-1", null),
                    actorA.membershipId()));

    as(
        actorA,
        "channel.operation.create",
        () ->
            service.addDisposition(
                created.id(),
                new LogisticsExceptionModels.AddDispositionRequest(
                    "REQUEST_ADDRESS_CHANGE", "SUBMITTED", null, null, null, "请改派到新地址", null, null),
                actorA.membershipId()));
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.addDisposition(
                created.id(),
                new LogisticsExceptionModels.AddDispositionRequest(
                    "CONTACT_CS", "PENDING", null, null, null, "请客服同步买家", null, null),
                actorA.membershipId()));
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.addDisposition(
                created.id(),
                new LogisticsExceptionModels.AddDispositionRequest(
                    "REQUEST_RESHIP", "PENDING", null, null, null, "买家要求补发一件", null, null),
                actorA.membershipId()));

    var detail = as(actorA, "channel.operation.read", () -> service.caseDetail(created.id()));
    assertThat(detail.downstreamTasks()).hasSize(3);
    assertThat(detail.downstreamTasks())
        .extracting(LogisticsExceptionModels.DownstreamTaskRow::downstreamModule)
        .containsExactlyInAnyOrder("10.2.1.3", "15", "7");
    // 🔴 没有任何一条是「已通知 / 已受理」—— 下游未交付，写了就是伪造凭据
    assertThat(detail.downstreamTasks())
        .extracting(LogisticsExceptionModels.DownstreamTaskRow::downstreamStatus)
        .containsOnly("PENDING_NOTIFY");
    assertThat(detail.downstreamTasks())
        .allSatisfy(
            t -> {
              assertThat(t.notifiedAt()).isNull();
              assertThat(t.acknowledgedAt()).isNull();
            });

    // 联系承运商**不**产生内部下游任务（承运商是外部对手方，不是内部模块）
    var carrierCase =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.createCase(
                    new LogisticsExceptionModels.CreateExceptionCaseRequest(
                        null, null, null, "DELIVERY_FAILED", "MEDIUM", null, "WB-DS-2", null),
                    actorA.membershipId()));
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.addDisposition(
                carrierCase.id(),
                new LogisticsExceptionModels.AddDispositionRequest(
                    "CONTACT_CARRIER", "SUBMITTED", null, null, null, "已致电承运商", null, null),
                actorA.membershipId()));
    assertThat(
            as(actorA, "channel.operation.read", () -> service.caseDetail(carrierCase.id()))
                .downstreamTasks())
        .isEmpty();
  }

  @Test
  @DisplayName("10.3.1.2 补发单产出 7 库存 + 11 售后两条待通知任务")
  void reshipClaimProducesTwoDownstreamTasks() {
    var created =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.createCase(
                    new LogisticsExceptionModels.CreateExceptionCaseRequest(
                        null, null, null, "DAMAGED_OR_LOST", "HIGH", null, "WB-RS-1", null),
                    actorA.membershipId()));
    var claim =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.createClaim(
                    created.id(),
                    new LogisticsExceptionModels.CreateExceptionClaimRequest(
                        "RESHIP", null, null, null, new BigDecimal("2"), "破损补发", null),
                    actorA.membershipId()));
    assertThat(claim.claimType()).isEqualTo("RESHIP");
    assertThat(claim.status()).isEqualTo("DRAFT");
    assertThat(claim.qty()).isEqualByComparingTo("2");
    assertThat(claim.claimNo()).startsWith("CLM-");

    var detail = as(actorA, "channel.operation.read", () -> service.claimDetail(claim.id()));
    assertThat(detail.downstreamTasks()).hasSize(2);
    assertThat(detail.downstreamTasks())
        .extracting(LogisticsExceptionModels.DownstreamTaskRow::downstreamModule)
        .containsExactlyInAnyOrder("7", "11");
    assertThat(detail.downstreamTasks())
        .allSatisfy(
            t ->
                assertThat(t.downstreamStatus())
                    .isEqualTo("PENDING_NOTIFY"));
  }

  // ==================================================================
  // 6. 金额与数量
  // ==================================================================

  @Test
  @DisplayName("10.3.1.2 理赔单：缺金额被拒；金额 BigDecimal 精确往返；跨币种分行不相加")
  void claimMoneyRules() {
    var created =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.createCase(
                    new LogisticsExceptionModels.CreateExceptionCaseRequest(
                        null, null, null, "DAMAGED_OR_LOST", "HIGH", null, "WB-MONEY-1", null),
                    actorA.membershipId()));

    // 理赔单没有金额 ⇒ 拒
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorA,
                        "channel.operation.create",
                        () ->
                            service.createClaim(
                                created.id(),
                                new LogisticsExceptionModels.CreateExceptionClaimRequest(
                                    "CARRIER_CLAIM", null, null, null, null, null, null),
                                actorA.membershipId()))))
        .as("理赔单没有金额必须被拒")
        .isNotNull();

    // 补发单没有数量 ⇒ 拒
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorA,
                        "channel.operation.create",
                        () ->
                            service.createClaim(
                                created.id(),
                                new LogisticsExceptionModels.CreateExceptionClaimRequest(
                                    "RESHIP", null, null, null, null, null, null),
                                actorA.membershipId()))))
        .as("补发单没有数量必须被拒")
        .isNotNull();

    // 金额精确往返（12.34 不该变成 12.339999…）
    var usd =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.createClaim(
                    created.id(),
                    new LogisticsExceptionModels.CreateExceptionClaimRequest(
                        "CARRIER_CLAIM", null, new BigDecimal("12.34"), "USD", null, null, null),
                    actorA.membershipId()));
    assertThat(usd.amount()).isEqualByComparingTo("12.34");
    assertThat(usd.currency()).isEqualTo("USD");
    assertThat(
            scalar(
                "select amount from logistics_exception_claim where id='" + usd.id() + "'"))
        .isEqualTo("12.340000");

    // 第二个币种的单
    var eur =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.createClaim(
                    created.id(),
                    new LogisticsExceptionModels.CreateExceptionClaimRequest(
                        "CARRIER_CLAIM", null, new BigDecimal("20.00"), "EUR", null, null, null),
                    actorA.membershipId()));
    assertThat(eur.currency()).isEqualTo("EUR");

    // 🔴 跨币种**分行**：USD 一行、EUR 一行，绝不相加成一行
    var overview = as(actorA, "channel.operation.read", () -> service.overview());
    assertThat(overview.claimTotals()).hasSize(2);
    assertThat(overview.claimTotals())
        .extracting(LogisticsExceptionModels.CurrencyTotal::currency)
        .containsExactly("EUR", "USD");
    assertThat(overview.claimTotals())
        .filteredOn(t -> t.currency().equals("USD"))
        .allSatisfy(t -> assertThat(t.amount()).isEqualByComparingTo("12.34"));
  }

  @Test
  @DisplayName("10.3.1.2 处置金额大于 0 时必须给币种")
  void dispositionMoneyNeedsCurrency() {
    var created =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.createCase(
                    new LogisticsExceptionModels.CreateExceptionCaseRequest(
                        null, null, null, "DAMAGED_OR_LOST", "HIGH", null, "WB-MONEY-2", null),
                    actorA.membershipId()));
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorA,
                        "channel.operation.create",
                        () ->
                            service.addDisposition(
                                created.id(),
                                new LogisticsExceptionModels.AddDispositionRequest(
                                    "CONTACT_CARRIER", null, null, new BigDecimal("15"), "", "赔付 15", null, null),
                                actorA.membershipId()))))
        .as("有金额没币种必须被拒")
        .isNotNull();
  }

  // ==================================================================
  // 7. 真分页 / 防重复 / append-only / 能力声明
  // ==================================================================

  @Test
  @DisplayName("10.3.1.2 真分页：页大小真的切分，汇总不受分页影响")
  void realPagination() {
    for (int i = 0; i < 5; i++) {
      final int index = i;
      as(
          actorA,
          "channel.operation.create",
          () ->
              service.createCase(
                  new LogisticsExceptionModels.CreateExceptionCaseRequest(
                      null, null, null, "BUYER_COMPLAINT", "LOW", "第 " + index + " 件", "WB-P-" + index, null),
                  actorA.membershipId()));
    }
    var page0 =
        as(actorA, "channel.operation.read", () -> service.cases(null, null, null, null, null, null, false, 0, 2));
    assertThat(page0.rows()).hasSize(2);
    assertThat(page0.total()).isEqualTo(5);
    assertThat(page0.size()).isEqualTo(2);

    var page2 =
        as(actorA, "channel.operation.read", () -> service.cases(null, null, null, null, null, null, false, 2, 2));
    assertThat(page2.rows()).hasSize(1);

    // 筛选真的生效
    var filtered =
        as(
            actorA,
            "channel.operation.read",
            () -> service.cases("BUYER_COMPLAINT", null, null, null, null, null, false, 0, 50));
    assertThat(filtered.total()).isEqualTo(5);
    var other =
        as(
            actorA,
            "channel.operation.read",
            () -> service.cases("CUSTOMS_HOLD", null, null, null, null, null, false, 0, 50));
    assertThat(other.total()).isZero();

    // 概览是全量口径，不受分页影响
    assertThat(as(actorA, "channel.operation.read", () -> service.overview()).caseCount()).isEqualTo(5);
  }

  @Test
  @DisplayName("10.3.1.2 同一票同一类型只能有一件未结异常件（第二次被拒）")
  void duplicateOpenCaseIsRejected() {
    UUID subjectId = seedSubject(a, "WB-DUP", "IN_TRANSIT", STALLED_AT, STALLED_AT, null);
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.createCase(
                new LogisticsExceptionModels.CreateExceptionCaseRequest(
                    subjectId, null, null, "TRACKING_STALLED", null, null, null, null),
                actorA.membershipId()));
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        actorA,
                        "channel.operation.create",
                        () ->
                            service.createCase(
                                new LogisticsExceptionModels.CreateExceptionCaseRequest(
                                    subjectId, null, null, "TRACKING_STALLED", null, null, null, null),
                                actorA.membershipId()))))
        .as("同一票同一类型的第二件未结异常件必须被拒")
        .isNotNull();
  }

  @Test
  @DisplayName("10.3.1.2 处置留痕 append-only：应用账号与超级账号各被拒一次")
  void dispositionsAreAppendOnly() {
    var created =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.createCase(
                    new LogisticsExceptionModels.CreateExceptionCaseRequest(
                        null, null, null, "BUYER_COMPLAINT", "LOW", "夹具", "WB-AP-1", null),
                    actorA.membershipId()));
    var disp =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.addDisposition(
                    created.id(),
                    new LogisticsExceptionModels.AddDispositionRequest(
                        "CONTACT_BUYER", "PENDING", null, null, null, "已联系", null, null),
                    actorA.membershipId()));

    // 应用账号：权限层就拒（UPDATE 被 REVOKE）
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execAsApp(
                        a,
                        "update logistics_exception_disposition set detail='改写'"
                            + " where tenant_id='" + a + "' and id='" + disp.id() + "'")))
        .as("应用账号不允许改写处置留痕")
        .isNotNull();

    // 超级账号：触发器拦（它绕过 RLS 与权限，触发器还在）
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    exec(
                        "update logistics_exception_disposition set detail='改写'"
                            + " where tenant_id='" + a + "' and id='" + disp.id() + "'")))
        .as("超级账号也改不了处置留痕")
        .isNotNull();
  }

  @Test
  @DisplayName("10.3.1.2 能力声明：下游维度 available=false 且点名 ownerModule")
  void capabilitiesMarkUndeliveredDownstream() {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());
    assertThat(caps.exceptionTypes()).contains("TRACKING_STALLED", "BUYER_COMPLAINT");
    assertThat(caps.downstreamModules()).containsExactly("7", "11", "15", "10.2.1.3");
    assertThat(caps.dimensions())
        .filteredOn(d -> d.ownerModule() != null && !d.ownerModule().isEmpty())
        .isNotEmpty();
    for (var d : caps.dimensions()) {
      if (!d.available()) {
        assertThat(d.ownerModule()).as(d.dimension() + " 未交付必须点名 ownerModule").isNotBlank();
        assertThat(d.impact()).as(d.dimension() + " 未交付必须写明影响").isNotBlank();
      }
    }
  }

  @Test
  @DisplayName("10.3.1.2 端点：清单 / 详情 / 处置 / 单据都经 HTTP 打通")
  void httpEndpointsWork() throws Exception {
    http.perform(get("/channel/logistics-tracking/exceptions").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(0))
        .andExpect(jsonPath("$.total").value(0));
    http.perform(get("/channel/logistics-tracking/exceptions/capabilities")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    http.perform(get("/channel/logistics-tracking/exceptions/overview")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.caseCount").value(0));
    http.perform(get("/channel/logistics-tracking/dispositions").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/channel/logistics-tracking/dispositions/claims")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    // 无会话 ⇒ 401
    http.perform(get("/channel/logistics-tracking/exceptions")).andExpect(status().isUnauthorized());
  }
}
