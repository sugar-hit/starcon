package com.starcon.channel;

import static com.starcon.channel.ComplianceAppealModels.*;
import static com.starcon.channel.PlatformViolationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 16.1.2.2 申诉管理的集成测试（**真实 PostgreSQL 16**，Testcontainers）。
 *
 * <p>🔴 这组用例守的是本模块最容易做丢的十二件事：
 *
 * <ol>
 *   <li><b>值域由 DB 层钉死</b>：非法申诉类型 / 非法结果**直接写库会被 CHECK 拒**；</li>
 *   <li><b>申诉配额真的有限</b>：超配额提交被拒，且**直接写 SQL 也绕不过去**；</li>
 *   <li><b>已用次数不可篡改</b>：连「自己 +1」都不行，只有提交动作能递增；</li>
 *   <li><b>没配配额 ≠ 无限</b>：没配配额的店铺提交被拒；</li>
 *   <li><b>状态机在服务层与 DB 层都被拒</b>：非法流转 422，**直接 UPDATE 也被触发器拒**；</li>
 *   <li><b>举证材料 / 提交记录 / 结果记录三样 append-only</b>：UPDATE / DELETE 都被 DB 拒；</li>
 *   <li><b>举证材料只在草稿态收集</b>：提交后再加材料被拒；</li>
 *   <li><b>申诉必须挂在真实、同店铺、未关闭的违规上</b>；</li>
 *   <li><b>影响档位是派生的</b>，且与上游 16.1.2.1 的规则**逐档位一致**；</li>
 *   <li><b>优先申诉高影响违规</b>：队列按影响档位从高到低排；</li>
 *   <li><b>申诉次数统计</b>：提交次数 = 申诉次数，驳回后再次提交**再计一次**；</li>
 *   <li><b>时间字段逐字往返</b>：写进去的 OffsetDateTime 读回来是同一个瞬间
 *       （有人用 {@code Timestamp.valueOf(LocalDateTime)} 就会静默偏 −28800s）。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlatformAppealIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");

  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\","
          + "\"channel.sync.read\",\"channel.sync.trigger\"],\"scopeMode\":\"ALL\"}]";

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

  static AccessService.Access actor(UUID uid, UUID tenantId, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenantId, "Test", UUID.randomUUID(), null,
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
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** 🔴 关掉 identity 这台静态桩（不起就停 ⇒ 端口被占住整整一轮 JVM 生命周期）。 */
  @AfterAll
  static void stopStubs() {
    if (identity != null) identity.stop(0);
  }

  @BeforeEach
  void freshTenant() {
    tenant = UUID.randomUUID();
    bearer = "Bearer t-" + tenant;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearer, actor(user, tenant, grants));
    who = actors.get(bearer);
  }

  UUID tenant;
  AccessService.Access who;
  String bearer;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired ComplianceAppealService service;
  @Autowired PlatformViolationService violations;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String permission, Supplier<T> work) {
    return access.run(who, permission, work);
  }

  void execAsTenant(UUID tenantId, String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 直连超级用户跑一句 SQL —— 用来证明「绕过服务层直接写库也做不到」。 */
  void execRaw(String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static String scalar(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(statement)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static long countRows(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(statement)) {
      return rs.next() ? rs.getLong(1) : 0L;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static OffsetDateTime offsetTime(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(statement)) {
      return rs.next() ? rs.getObject(1, OffsetDateTime.class) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  UUID seedShop(String code) {
    UUID id = UUID.randomUUID();
    execAsTenant(
        tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('"
            + tenant
            + "','"
            + id
            + "','"
            + AMAZON
            + "','"
            + code
            + "','测试店铺 "
            + code
            + "')");
    return id;
  }

  /** 用**上游自己的服务**造一条违规（证明本模块真的接在 16.1.2.1 上）。 */
  String seedViolation(UUID shopId, String noticeId, String type) {
    PlatformViolationRegistration reg =
        as(
            "channel.operation.create",
            () ->
                violations.register(
                    new PlatformViolationRegisterInput(
                        shopId.toString(),
                        noticeId,
                        type,
                        "测试违规 " + noticeId,
                        "说明",
                        OffsetDateTime.now(ZoneOffset.UTC).minusDays(2),
                        OffsetDateTime.now(ZoneOffset.UTC).minusDays(2),
                        OffsetDateTime.now(ZoneOffset.UTC).plusDays(5))));
    return reg.violation().violation().violationId();
  }

  /** 给违规挂一条处罚措施（决定**派生**的影响档位）。 */
  void seedPenalty(String violationId, String measure) {
    // 🔴 只有「扣款」这一种措施带金额（上游的纪律：其它四种一律不带金额）。
    java.math.BigDecimal amount = "DEDUCTION".equals(measure) ? new java.math.BigDecimal("80.00") : null;
    String currency = "DEDUCTION".equals(measure) ? "USD" : null;
    as(
        "channel.operation.create",
        () ->
            violations.addPenalty(
                violationId,
                new PlatformPenaltyInput(measure, amount, currency, null, null, "测试处罚")));
  }

  void setQuota(UUID shopId, int limit) {
    as(
        "channel.operation.update",
        () ->
            service.setQuota(
                new ComplianceAppealQuotaInput(
                    shopId.toString(),
                    limit,
                    OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
                    OffsetDateTime.now(ZoneOffset.UTC).plusDays(365))));
  }

  ComplianceAppeal createAppeal(String violationId, String type, String appealText) {
    return as(
        "channel.operation.create",
        () -> service.create(new ComplianceAppealCreateInput(violationId, type, appealText)));
  }

  // ==================================================================
  // 一、能力声明
  // ==================================================================

  @Test
  @DisplayName("16.1.2.2 能力声明：平台申诉接口未交付如实标 available=false；上游 16.1.2.1 已交付")
  void capabilitiesDeclareDownstreamTruthfully() {
    ComplianceAppealCapabilities caps = as("channel.operation.read", () -> service.capabilities());

    ComplianceAppealCapabilityGap platformApi =
        caps.gaps().stream()
            .filter(g -> g.moduleId().equals("PLATFORM_APPEAL_API"))
            .findFirst()
            .orElseThrow();
    assertThat(platformApi.available()).as("平台申诉接口本仓不存在 ⇒ available 必须为 false").isFalse();
    assertThat(platformApi.ownerModule()).isNotBlank();
    assertThat(platformApi.impact()).contains("不造指向不存在入口的放行形状");

    ComplianceAppealCapabilityGap upstream =
        caps.gaps().stream().filter(g -> g.moduleId().equals("16.1.2.1")).findFirst().orElseThrow();
    assertThat(upstream.available()).as("上游 16.1.2.1 已交付 ⇒ available 必须为 true").isTrue();

    assertThat(caps.appealTypes()).hasSize(6);
    assertThat(caps.statuses()).containsExactly("DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "WITHDRAWN");
    assertThat(caps.outcomes()).containsExactly("APPROVED", "REJECTED");
    assertThat(caps.statusTransitions().get("APPROVED")).isEmpty();
    assertThat(caps.quotaNote()).contains("申诉配额有限");
    assertThat(caps.priorityNote()).contains("优先申诉高影响违规");
    assertThat(caps.appendOnlySemantics()).contains("追加式");
  }

  // ==================================================================
  // 二、申诉单全生命周期
  // ==================================================================

  @Test
  @DisplayName("16.1.2.2 申诉单走完 草稿 → 提交 → 驳回 → 回草稿 → 再提交 → 通过")
  void appealLifecycleRunsThroughTheWholeStateMachine() {
    UUID shop = seedShop("LIFE-1");
    String violationId = seedViolation(shop, "LIFE-NOTICE", "PRODUCT_SAFETY");
    seedPenalty(violationId, "DELIST");
    setQuota(shop, 5);

    ComplianceAppeal appeal = createAppeal(violationId, "POLICY_MISINTERPRETATION", "这条不该罚");
    assertThat(appeal.status()).isEqualTo("DRAFT");
    assertThat(appeal.shopId()).isEqualTo(shop.toString());
    assertThat(appeal.impactLevel()).as("派生影响档位（下架级）").isEqualTo("HIGH");
    assertThat(appeal.submissionCount()).isZero();

    ComplianceAppealEvidence evidence =
        as(
            "channel.operation.create",
            () ->
                service.addEvidence(
                    appeal.appealId(),
                    new ComplianceAppealEvidenceInput("DOCUMENT", "质检报告.pdf", "补齐的质检报告")));
    assertThat(evidence.evidenceId()).isNotBlank();

    ComplianceAppealSubmissionResult submitted =
        as(
            "channel.operation.update",
            () ->
                service.submit(
                    appeal.appealId(),
                    new ComplianceAppealSubmitInput("CASE-001", "首次提交")));
    assertThat(submitted.appeal().status()).isEqualTo("SUBMITTED");
    assertThat(submitted.attemptNo()).isEqualTo(1);
    assertThat(submitted.quota().usedCount()).isEqualTo(1);
    assertThat(submitted.quota().remaining()).isEqualTo(4);

    ComplianceAppealDecisionResult rejected =
        as(
            "channel.operation.update",
            () ->
                service.decide(
                    appeal.appealId(),
                    new ComplianceAppealDecisionInput("REJECTED", "材料不足", "CASE-001", null)));
    assertThat(rejected.appeal().status()).isEqualTo("REJECTED");
    assertThat(rejected.decision().outcome()).isEqualTo("REJECTED");
    assertThat(rejected.decision().outcomeLabel()).isEqualTo("驳回");

    ComplianceAppeal reopened =
        as("channel.operation.update", () -> service.reopen(appeal.appealId()));
    assertThat(reopened.status()).isEqualTo("DRAFT");
    assertThat(reopened.submittedAt()).as("回到草稿 ⇒ 提交痕迹清空").isNull();

    as(
        "channel.operation.create",
        () ->
            service.addEvidence(
                appeal.appealId(),
                new ComplianceAppealEvidenceInput("IMAGE", "screenshot.png", "补充截图")));

    ComplianceAppealSubmissionResult resubmitted =
        as(
            "channel.operation.update",
            () ->
                service.submit(
                    appeal.appealId(),
                    new ComplianceAppealSubmitInput("CASE-002", "补充材料后再次提交")));
    assertThat(resubmitted.attemptNo()).as("第 2 次提交").isEqualTo(2);
    assertThat(resubmitted.quota().usedCount()).as("第 2 次提交再消耗一次配额").isEqualTo(2);
    assertThat(resubmitted.quota().remaining()).isEqualTo(3);

    ComplianceAppealDecisionResult approved =
        as(
            "channel.operation.update",
            () ->
                service.decide(
                    appeal.appealId(),
                    new ComplianceAppealDecisionInput("APPROVED", "证据充分，撤销处罚", "CASE-002", null)));
    assertThat(approved.appeal().status()).isEqualTo("APPROVED");

    ComplianceAppealDetail detail = as("channel.operation.read", () -> service.appeal(appeal.appealId()));
    assertThat(detail.submissions()).hasSize(2);
    assertThat(detail.decisions()).hasSize(2);
    assertThat(detail.evidence()).hasSize(2);
    assertThat(detail.appeal().lastOutcome()).isEqualTo("APPROVED");
    assertThat(detail.appeal().lastOutcomeReason()).isEqualTo("证据充分，撤销处罚");
    assertThat(detail.quota().usedCount()).isEqualTo(2);
  }

  // ==================================================================
  // 三、申诉配额（蓝图加粗句）
  // ==================================================================

  @Test
  @DisplayName("16.1.2.2 申诉配额真的有限：超配额提交被拒，直接写库也绕不过去")
  void quotaIsEnforcedEndToEnd() {
    UUID shop = seedShop("QUOTA-1");
    String violationId = seedViolation(shop, "QUOTA-NOTICE", "PRODUCT_SAFETY");
    setQuota(shop, 1);

    ComplianceAppeal appeal = createAppeal(violationId, "EVIDENCE_PROVIDED", "有证据");
    as(
        "channel.operation.update",
        () -> service.submit(appeal.appealId(), new ComplianceAppealSubmitInput("", "")));

    // 驳回 → 回草稿 → 再提交 ⇒ 配额已用尽
    as(
        "channel.operation.update",
        () ->
            service.decide(
                appeal.appealId(),
                new ComplianceAppealDecisionInput("REJECTED", "再补点材料", "", null)));
    as("channel.operation.update", () -> service.reopen(appeal.appealId()));

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.update",
                        () ->
                            service.submit(
                                appeal.appealId(), new ComplianceAppealSubmitInput("", "")))))
        .as("配额用尽 ⇒ 服务层必须拒（422）")
        .isNotNull();

    // 🔴 绕过服务层直接写库 ⇒ DB 触发器同样拒（23514）
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execRaw(
                        "update platform_appeal set status='SUBMITTED', submitted_at=now()"
                            + " where tenant_id='"
                            + tenant
                            + "' and id='"
                            + appeal.appealId()
                            + "'")))
        .as("直接写库提交也必须被配额触发器拒")
        .isNotNull();

    assertThat(
            countRows(
                "select used_count from platform_appeal_quota where tenant_id='"
                    + tenant
                    + "' and shop_id='"
                    + shop
                    + "'"))
        .as("配额只被消耗了一次（被拒的那次不算）")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("16.1.2.2 配额的已用次数不可篡改：直接改写与自己 +1 都被拒")
  void quotaUsedCountCannotBeTamperedWith() {
    UUID shop = seedShop("TAMPER-1");
    String violationId = seedViolation(shop, "TAMPER-NOTICE", "PRODUCT_SAFETY");
    setQuota(shop, 5);

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execRaw(
                        "update platform_appeal_quota set used_count = 99 where tenant_id='"
                            + tenant
                            + "' and shop_id='"
                            + shop
                            + "'")))
        .as("直接改写已用次数必须被拒").isNotNull();

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execRaw(
                        "update platform_appeal_quota set used_count = used_count + 1 where tenant_id='"
                            + tenant
                            + "' and shop_id='"
                            + shop
                            + "'")))
        .as("自己 +1 也必须被拒（只有提交动作能递增）").isNotNull();

    assertThat(
            countRows(
                "select used_count from platform_appeal_quota where tenant_id='"
                    + tenant
                    + "' and shop_id='"
                    + shop
                    + "'"))
        .isZero();
  }

  @Test
  @DisplayName("16.1.2.2 没配配额 ≠ 无限：没配配额的店铺提交被拒")
  void submitWithoutQuotaIsRejected() {
    UUID shop = seedShop("NOQUOTA-1");
    String violationId = seedViolation(shop, "NOQUOTA-NOTICE", "PRODUCT_SAFETY");
    ComplianceAppeal appeal = createAppeal(violationId, "OTHER", "试试");

    Throwable thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(
                    "channel.operation.update",
                    () -> service.submit(appeal.appealId(), new ComplianceAppealSubmitInput("", ""))));
    assertThat(thrown).as("没配配额 ⇒ 必须拒").isNotNull();
    assertThat(thrown.getMessage()).contains("配额");
  }

  @Test
  @DisplayName("16.1.2.2 撤回是终态且不消耗配额")
  void withdrawIsTerminalAndDoesNotConsumeQuota() {
    UUID shop = seedShop("WITHDRAW-1");
    String violationId = seedViolation(shop, "WITHDRAW-NOTICE", "PRODUCT_SAFETY");
    setQuota(shop, 3);

    ComplianceAppeal appeal = createAppeal(violationId, "OTHER", "不申诉了");
    ComplianceAppeal withdrawn =
        as("channel.operation.update", () -> service.withdraw(appeal.appealId(), null));
    assertThat(withdrawn.status()).isEqualTo("WITHDRAWN");

    assertThat(
            countRows(
                "select used_count from platform_appeal_quota where tenant_id='"
                    + tenant
                    + "' and shop_id='"
                    + shop
                    + "'"))
        .as("撤回不消耗配额").isZero();

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.update",
                        () ->
                            service.submit(
                                appeal.appealId(), new ComplianceAppealSubmitInput("", "")))))
        .as("撤回是终态 ⇒ 不能再提交").isNotNull();
  }

  // ==================================================================
  // 四、append-only
  // ==================================================================

  @Test
  @DisplayName("16.1.2.2 举证材料 / 提交记录 / 结果记录三样 append-only：UPDATE 与 DELETE 都被拒")
  void appendOnlyTablesRejectUpdateAndDelete() {
    UUID shop = seedShop("APPEND-1");
    String violationId = seedViolation(shop, "APPEND-NOTICE", "PRODUCT_SAFETY");
    setQuota(shop, 5);

    ComplianceAppeal appeal = createAppeal(violationId, "EVIDENCE_PROVIDED", "有证据");
    as(
        "channel.operation.create",
        () ->
            service.addEvidence(
                appeal.appealId(),
                new ComplianceAppealEvidenceInput("DOCUMENT", "a.pdf", "报告")));
    as(
        "channel.operation.update",
        () -> service.submit(appeal.appealId(), new ComplianceAppealSubmitInput("R-1", "")));
    as(
        "channel.operation.update",
        () ->
            service.decide(
                appeal.appealId(),
                new ComplianceAppealDecisionInput("REJECTED", "不行", "R-1", null)));

    String id = appeal.appealId();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execRaw(
                        "update platform_appeal_evidence set summary='改写' where tenant_id='"
                            + tenant
                            + "' and appeal_id='"
                            + id
                            + "'")))
        .as("举证材料不可改写").isNotNull();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execRaw(
                        "delete from platform_appeal_evidence where tenant_id='"
                            + tenant
                            + "' and appeal_id='"
                            + id
                            + "'")))
        .as("举证材料不可删除").isNotNull();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execRaw(
                        "update platform_appeal_submission set note='改写' where tenant_id='"
                            + tenant
                            + "' and appeal_id='"
                            + id
                            + "'")))
        .as("提交记录不可改写").isNotNull();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execRaw(
                        "delete from platform_appeal_submission where tenant_id='"
                            + tenant
                            + "' and appeal_id='"
                            + id
                            + "'")))
        .as("提交记录不可删除").isNotNull();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execRaw(
                        "update platform_appeal_decision set reason='改写' where tenant_id='"
                            + tenant
                            + "' and appeal_id='"
                            + id
                            + "'")))
        .as("结果记录不可改写").isNotNull();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execRaw(
                        "delete from platform_appeal_decision where tenant_id='"
                            + tenant
                            + "' and appeal_id='"
                            + id
                            + "'")))
        .as("结果记录不可删除").isNotNull();
  }

  // ==================================================================
  // 五、状态机与引用完整性
  // ==================================================================

  @Test
  @DisplayName("16.1.2.2 非法状态流转：服务层 422，直接 UPDATE 被触发器拒")
  void illegalTransitionsAreRejectedByServiceAndDatabase() {
    UUID shop = seedShop("STATE-1");
    String violationId = seedViolation(shop, "STATE-NOTICE", "PRODUCT_SAFETY");
    setQuota(shop, 5);

    ComplianceAppeal appeal = createAppeal(violationId, "OTHER", "文本");

    // 不提交就直接裁定 ⇒ 服务层拒
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.update",
                        () ->
                            service.decide(
                                appeal.appealId(),
                                new ComplianceAppealDecisionInput("APPROVED", "", "", null)))))
        .as("跳过提交直接裁定必须被拒").isNotNull();

    // 绕过服务层直接 UPDATE ⇒ 触发器拒
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execRaw(
                        "update platform_appeal set status='APPROVED' where tenant_id='"
                            + tenant
                            + "' and id='"
                            + appeal.appealId()
                            + "'")))
        .as("直接 UPDATE 状态也必须被拒").isNotNull();
  }

  @Test
  @DisplayName("16.1.2.2 申诉必须挂在真实、同店铺、未关闭的违规上")
  void appealMustReferenceRealViolationOfSameShop() {
    UUID shop = seedShop("REF-1");
    UUID otherShop = seedShop("REF-2");
    setQuota(shop, 5);
    setQuota(otherShop, 5);

    // 违规不存在 ⇒ 404
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> createAppeal(UUID.randomUUID().toString(), "OTHER", "文本")))
        .as("引用不存在的违规必须被拒").isNotNull();

    String violationId = seedViolation(shop, "REF-NOTICE", "PRODUCT_SAFETY");

    // 跨店铺申诉 ⇒ DB 触发器拒（直接写库）
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execAsTenant(
                        tenant,
                        "insert into platform_appeal"
                            + " (id, tenant_id, shop_id, violation_id, appeal_type, status, appeal_text,"
                            + " created_at, updated_at)"
                            + " values ('"
                            + UUID.randomUUID()
                            + "','"
                            + tenant
                            + "','"
                            + otherShop
                            + "','"
                            + violationId
                            + "','OTHER','DRAFT','跨店铺',now(),now())")))
        .as("跨店铺申诉必须被拒").isNotNull();
  }

  @Test
  @DisplayName("16.1.2.2 已关闭的违规不能再申诉；同一条违规同时只能有一个在办申诉")
  void closedViolationCannotBeAppealedAndOnlyOneOpenAppealPerViolation() {
    UUID shop = seedShop("OPEN-1");
    setQuota(shop, 5);
    String violationId = seedViolation(shop, "OPEN-NOTICE", "PRODUCT_SAFETY");

    ComplianceAppeal first = createAppeal(violationId, "OTHER", "第一次");
    assertThat(first.status()).isEqualTo("DRAFT");

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> createAppeal(violationId, "EVIDENCE_PROVIDED", "第二次")))
        .as("同一条违规已有在办申诉 ⇒ 必须被拒").isNotNull();

    // 把第一条撤掉 ⇒ 可以再提
    as("channel.operation.update", () -> service.withdraw(first.appealId(), null));
    ComplianceAppeal second = createAppeal(violationId, "EVIDENCE_PROVIDED", "第二次");
    assertThat(second.status()).isEqualTo("DRAFT");
  }

  @Test
  @DisplayName("16.1.2.2 举证材料只在草稿态收集")
  void evidenceCanOnlyBeCollectedInDraft() {
    UUID shop = seedShop("EVID-1");
    String violationId = seedViolation(shop, "EVID-NOTICE", "PRODUCT_SAFETY");
    setQuota(shop, 5);

    ComplianceAppeal appeal = createAppeal(violationId, "EVIDENCE_PROVIDED", "文本");
    as(
        "channel.operation.update",
        () -> service.submit(appeal.appealId(), new ComplianceAppealSubmitInput("", "")));

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.create",
                        () ->
                            service.addEvidence(
                                appeal.appealId(),
                                new ComplianceAppealEvidenceInput("DOCUMENT", "a.pdf", "补交")))))
        .as("提交后再补材料必须被拒").isNotNull();
  }

  @Test
  @DisplayName("16.1.2.2 申诉文本只有草稿态能改")
  void appealTextIsEditableOnlyInDraft() {
    UUID shop = seedShop("TEXT-1");
    String violationId = seedViolation(shop, "TEXT-NOTICE", "PRODUCT_SAFETY");
    setQuota(shop, 5);

    ComplianceAppeal appeal = createAppeal(violationId, "OTHER", "初稿");
    ComplianceAppeal updated =
        as(
            "channel.operation.update",
            () -> service.updateText(appeal.appealId(), new ComplianceAppealTextInput("改过的稿子")));
    assertThat(updated.appealText()).isEqualTo("改过的稿子");

    as(
        "channel.operation.update",
        () -> service.submit(appeal.appealId(), new ComplianceAppealSubmitInput("", "")));

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.update",
                        () ->
                            service.updateText(
                                appeal.appealId(), new ComplianceAppealTextInput("提交后再改"))))
            )
        .as("提交后改文本必须被拒").isNotNull();
  }

  // ==================================================================
  // 六、影响档位（派生）与优先队列
  // ==================================================================

  @Test
  @DisplayName("16.1.2.2 影响档位是派生的，且与上游 16.1.2.1 的规则逐档位一致")
  void impactLevelIsDerivedAndMatchesUpstreamRule() {
    UUID shop = seedShop("IMPACT-1");
    setQuota(shop, 20);

    List<String> measures = List.of("SUSPEND_ACCOUNT", "DELIST", "DEDUCTION", "WARNING");
    for (String measure : measures) {
      String violationId = seedViolation(shop, "IMPACT-" + measure, "PRODUCT_SAFETY");
      seedPenalty(violationId, measure);
      ComplianceAppeal appeal = createAppeal(violationId, "OTHER", "文本");
      String expected = PlatformViolationRules.impactLevel(List.of(measure));
      assertThat(appeal.impactLevel()).as("档位 " + measure).isEqualTo(expected);
      assertThat(
              scalar(
                  "select platform_appeal_impact_level('"
                      + tenant
                      + "','"
                      + violationId
                      + "')"))
          .as("DB 函数与上游 Java 规则必须同口径")
          .isEqualTo(expected);
    }

    // 没有处罚措施 ⇒ 上游自己的「尚无处罚措施」档位
    String bare = seedViolation(shop, "IMPACT-BARE", "PRODUCT_SAFETY");
    ComplianceAppeal bareAppeal = createAppeal(bare, "OTHER", "文本");
    assertThat(bareAppeal.impactLevel()).isEqualTo("NONE");
    assertThat(bareAppeal.impactLabel()).isEqualTo("尚无处罚措施");
  }

  @Test
  @DisplayName("16.1.2.2 优先申诉高影响违规：队列按影响档位从高到低排")
  void priorityQueueOrdersByImpactDescending() {
    UUID shop = seedShop("PRIO-1");
    setQuota(shop, 20);

    String low = seedViolation(shop, "PRIO-LOW", "PRODUCT_SAFETY");
    seedPenalty(low, "WARNING");
    String critical = seedViolation(shop, "PRIO-CRITICAL", "PRODUCT_SAFETY");
    seedPenalty(critical, "SUSPEND_ACCOUNT");
    String high = seedViolation(shop, "PRIO-HIGH", "PRODUCT_SAFETY");
    seedPenalty(high, "DELIST");

    ComplianceAppealPriorityPage queue =
        as("channel.operation.read", () -> service.priorityQueue(shop.toString(), null, 0, 20));
    assertThat(queue.items()).hasSize(3);
    assertThat(queue.items().get(0).impactLevel()).as("停号级排最前").isEqualTo("CRITICAL");
    assertThat(queue.items().get(1).impactLevel()).isEqualTo("HIGH");
    assertThat(queue.items().get(2).impactLevel()).isEqualTo("LOW");
    assertThat(queue.items().get(0).appealable()).as("配了配额且有余额 ⇒ 可以申诉").isTrue();
    assertThat(queue.items().get(0).quotaRemaining()).isEqualTo(20);

    // 已经在办申诉的违规不进队列
    createAppeal(critical, "OTHER", "在办");
    ComplianceAppealPriorityPage after =
        as("channel.operation.read", () -> service.priorityQueue(shop.toString(), null, 0, 20));
    assertThat(after.items()).hasSize(2);
    assertThat(after.items()).extracting(ComplianceAppealPriorityCandidate::violationId)
        .doesNotContain(critical);

    // 已关闭的违规不进队列
    as(
        "channel.operation.update",
        () ->
            violations.transition(
                low,
                new PlatformViolationStatusInput("IN_PROGRESS", "")));
    as(
        "channel.operation.update",
        () -> violations.transition(low, new PlatformViolationStatusInput("CLOSED", "")));
    ComplianceAppealPriorityPage closed =
        as("channel.operation.read", () -> service.priorityQueue(shop.toString(), null, 0, 20));
    assertThat(closed.items()).extracting(ComplianceAppealPriorityCandidate::violationId)
        .doesNotContain(low);
  }

  // ==================================================================
  // 七、统计与时间往返
  // ==================================================================

  @Test
  @DisplayName("16.1.2.2 申诉次数统计：提交次数 = 申诉次数，驳回后再次提交再计一次")
  void statsCountAppealsAndSubmissions() {
    UUID shop = seedShop("STATS-1");
    String violationId = seedViolation(shop, "STATS-NOTICE", "PRODUCT_SAFETY");
    setQuota(shop, 5);

    ComplianceAppeal appeal = createAppeal(violationId, "EVIDENCE_PROVIDED", "文本");
    as(
        "channel.operation.update",
        () -> service.submit(appeal.appealId(), new ComplianceAppealSubmitInput("", "")));
    as(
        "channel.operation.update",
        () ->
            service.decide(
                appeal.appealId(),
                new ComplianceAppealDecisionInput("REJECTED", "驳回", "", null)));
    as("channel.operation.update", () -> service.reopen(appeal.appealId()));
    as(
        "channel.operation.update",
        () -> service.submit(appeal.appealId(), new ComplianceAppealSubmitInput("", "")));
    as(
        "channel.operation.update",
        () ->
            service.decide(
                appeal.appealId(),
                new ComplianceAppealDecisionInput("APPROVED", "通过", "", null)));

    ComplianceAppealStats stats =
        as("channel.operation.read", () -> service.stats(shop.toString(), null, null));
    assertThat(stats.totalAppeals()).isEqualTo(1);
    assertThat(stats.totalSubmissions()).as("申诉次数 = 提交总次数").isEqualTo(2);
    assertThat(stats.rejectedCount()).isEqualTo(1);
    assertThat(stats.approvedCount()).isEqualTo(1);
    assertThat(stats.byStatus().get("APPROVED")).isEqualTo(1L);
    assertThat(stats.byType().get("EVIDENCE_PROVIDED")).isEqualTo(1L);
    assertThat(stats.quota()).isNotNull();
    assertThat(stats.quota().usedCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("16.1.2.2 时间字段逐字往返：写进去的 OffsetDateTime 读回来是同一个瞬间")
  void timestampsRoundTripWithoutTheDoubleOffsetTrap() {
    UUID shop = seedShop("TIME-1");
    String violationId = seedViolation(shop, "TIME-NOTICE", "PRODUCT_SAFETY");
    setQuota(shop, 5);

    ComplianceAppeal appeal = createAppeal(violationId, "OTHER", "文本");
    ComplianceAppealSubmissionResult submitted =
        as(
            "channel.operation.update",
            () -> service.submit(appeal.appealId(), new ComplianceAppealSubmitInput("", "")));

    OffsetDateTime stored =
        offsetTime(
            "select submitted_at from platform_appeal where tenant_id='"
                + tenant
                + "' and id='"
                + appeal.appealId()
                + "'");
    assertThat(stored).as("库里的提交时刻必须真的写进去了").isNotNull();
    assertThat(submitted.appeal().submittedAt().toInstant())
        .as("API 读回来的瞬间必须与库里一致（否则就是被二次偏移了）")
        .isEqualTo(stored.toInstant());
    assertThat(submitted.submission().submittedAt().toInstant())
        .as("提交记录里的时刻同样逐字往返")
        .isEqualTo(stored.toInstant());
  }

  // ==================================================================
  // 八、端点与鉴权
  // ==================================================================

  @Test
  @DisplayName("16.1.2.2 端点：无会话一律 401；有会话时能力声明 / 清单可读")
  void endpointsHonourAuthentication() throws Exception {
    http.perform(get("/channel/platform-appeals/capabilities"))
        .andExpect(status().isUnauthorized());
    http.perform(get("/channel/platform-appeals/appeals"))
        .andExpect(status().isUnauthorized());

    http.perform(get("/channel/platform-appeals/capabilities").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.appealTypes").isArray());
    http.perform(get("/channel/platform-appeals/appeals").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
    http.perform(get("/channel/platform-appeals/priority-queue").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());
    http.perform(get("/channel/platform-appeals/quotas").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());
    http.perform(get("/channel/platform-appeals/stats").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalAppeals").value(0));
  }

  @Test
  @DisplayName("16.1.2.2 申诉单清单是真分页：翻页拿到的是不同的行")
  void appealListIsReallyPaginated() {
    UUID shop = seedShop("PAGE-1");
    setQuota(shop, 10);
    for (int i = 0; i < 5; i++) {
      String violationId = seedViolation(shop, "PAGE-NOTICE-" + i, "PRODUCT_SAFETY");
      createAppeal(violationId, "OTHER", "文本 " + i);
    }
    ComplianceAppealPage first =
        as("channel.operation.read", () -> service.appeals(shop.toString(), null, null, null, null, null, "priority", 0, 2));
    ComplianceAppealPage second =
        as("channel.operation.read", () -> service.appeals(shop.toString(), null, null, null, null, null, "priority", 1, 2));
    assertThat(first.total()).isEqualTo(5);
    assertThat(first.items()).hasSize(2);
    assertThat(second.items()).hasSize(2);
    assertThat(
            first.items().stream().map(ComplianceAppeal::appealId).toList())
        .doesNotContainAnyElementsOf(second.items().stream().map(ComplianceAppeal::appealId).toList());
  }
}
