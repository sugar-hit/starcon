package com.starcon.channel;

import static com.starcon.channel.DataGovernanceModels.*;
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
 * 16.6.2.1 数据出境与留存（Testcontainers 真实 PostgreSQL 16）。
 *
 * <p>本测试类钉住这几组事：
 *
 * <ol>
 *   <li><b>🔴 数据域登记表是本模块自建的核心事实表</b>：upsert 不新增行；
 *       策略 / 清理工单 / 删除请求都必须挂在**已登记**的数据域上。</li>
 *   <li><b>🔴 合法性基础是受控值域</b>：自由文本的合法性基础等于没登记。</li>
 *   <li><b>🔴 留存策略版本化</b>：改策略 = 旧条 SUPERSEDED + 新条 version_no 递增；
 *       <b>生效时刻不许回填</b>；<b>留存天数必须是整天</b>。</li>
 *   <li><b>🔴 「财务留存优先」是可判定的</b>：冲突对、双方期限、裁决结果与依据
 *       在裁决清单里**看得见**；法定留存期没走完 ⇒ 删除请求判成
 *       <b>DEFERRED（推迟）</b>，执行被拒（409）。</li>
 *   <li><b>🔴 判不了 ≠ 默认放行</b>：数据域没有留存策略 ⇒ 审批删除请求 <b>422</b>。</li>
 *   <li><b>🔴 审批人 ≠ 申请人</b>（自己批自己 403）。</li>
 *   <li><b>🔴 执行删除 = 派发清理工单</b>（本模块不跨服务删业务数据），
 *       且工单的完成截止日不得早于最早可清理日。</li>
 *   <li><b>🔴 清理执行记录 append-only</b>：应用账号与迁移超级账号各试一次改与删，都必须被拒。</li>
 *   <li><b>🔴 timestamptz 往返零漂移</b>（断言写回的值，不只断言没抛异常）。</li>
 *   <li><b>🔴 数量用 NUMERIC(19,6)</b>：留存天数与处理条数按值往返，禁浮点。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class DataGovernanceIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static final UUID approverUser = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  UUID tenant;
  AccessService.Access actor;
  AccessService.Access approver;
  String bearer;
  String approverBearer;

  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\"],\"scopeMode\":\"ALL\"}]";

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
    return new AccessService.Grant(UUID.randomUUID(), "SUPPORT", scope, permissions);
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

  @BeforeEach
  void freshTenant() {
    tenant = UUID.randomUUID();
    bearer = "Bearer t-" + tenant;
    approverBearer = "Bearer a-" + tenant;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearer, actor(user, tenant, grants));
    actors.put(approverBearer, actor(approverUser, tenant, grants));
    actor = actors.get(bearer);
    approver = actors.get(approverBearer);
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
  }

  @Autowired DataGovernanceService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  /**
   * 跑一段可能抛异常的代码，返回异常本身（没抛就返回 null）。
   *
   * <p>🔴 之所以包一层：断言里直接嵌四层 lambda 会让括号难以核对，
   * 而"括号少一个"这种错在编译期就会炸，不值得为此把断言写难读。
   */
  static Throwable thrown(Runnable work) {
    return org.assertj.core.api.Assertions.catchThrowable(work::run);
  }

  <T> T as(String permission, java.util.function.Supplier<T> work) {
    return access.run(actor, permission, work);
  }

  <T> T asApprover(String permission, java.util.function.Supplier<T> work) {
    return access.run(approver, permission, work);
  }

  /** 登记一个数据域。 */
  DataGovernanceDomainView domain(String code, String name, String owner, String dataClass) {
    DataGovernanceDomainInput input =
        new DataGovernanceDomainInput(code, name, owner, dataClass, true, false, "");
    return as("channel.operation.update", () -> service.saveDomain(input));
  }

  /** 登记一条留存策略（生效时刻在未来，避开「不许回填」）。 */
  RetentionPolicyView policy(String domainCode, String retentionClass, long days) {
    OffsetDateTime from = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10);
    RetentionPolicyInput input =
        new RetentionPolicyInput(
            domainCode,
            retentionClass + " 留存策略",
            retentionClass,
            java.math.BigDecimal.valueOf(days),
            "CREATION",
            "测试依据",
            from);
    return as("channel.operation.create", () -> service.createPolicy(actor, input));
  }

  /** 提交一条删除请求。 */
  DataGovernanceDeletionRequestView deletion(String domainCode, String subjectRef) {
    DataGovernanceDeletionRequestInput input =
        new DataGovernanceDeletionRequestInput(
            "FORGETTING_RIGHT", "ORDER", subjectRef, domainCode, "买家要求删除个人数据");
    return as("channel.operation.create", () -> service.createDeletionRequest(actor, input));
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

  void execAsApp(String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  void execAsSuper(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  // ==================================================================
  // 数据域
  // ==================================================================

  @Test
  @DisplayName("16.6.2.1 数据域登记是 upsert（同一个 domain_code 不新增行）")
  void domainRegistrationIsUpsert() {
    domain("ORDER", "9 订单", "9 订单（trade 服务）", "PII");
    domain("ORDER", "9 订单（改名）", "9 订单（trade 服务）", "PII");

    DataGovernanceDomainPage page = as("channel.operation.read", () -> service.domains("ORDER", 0, 50));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).domainName()).isEqualTo("9 订单（改名）");
    assertThat(page.items().get(0).version()).isEqualTo(1);
    assertThat(page.items().get(0).containsPii()).isTrue();
    assertThat(page.items().get(0).dataClassLabel()).isEqualTo("个人信息");
  }

  @Test
  @DisplayName("16.6.2.1 策略不能挂在没登记的数据域上")
  void policyRequiresRegisteredDomain() {
    Throwable error = thrown(() -> policy("NOT_REGISTERED", "STANDARD", 180));
    assertThat(error).as("未登记的数据域不许挂策略").isNotNull();
  }

  // ==================================================================
  // 跨境传输
  // ==================================================================

  @Test
  @DisplayName("16.6.2.1 跨境传输登记：合法性基础是受控值域")
  void transferLegalBasisIsControlled() throws Exception {
    domain("ORDER", "9 订单", "9 订单（trade 服务）", "PII");

    http.perform(
            post("/channel/data-governance/transfers")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        new DataGovernanceTransferInput(
                            "ORDER",
                            "DE",
                            "某德国物流商",
                            "COMPLIANCE_NEEDED",
                            "",
                            "",
                            OffsetDateTime.now(ZoneOffset.UTC).minusDays(30),
                            ""))))
        .andExpect(status().isBadRequest());

    DataGovernanceTransferInput input =
        new DataGovernanceTransferInput(
            "ORDER",
            "DE",
            "某德国物流商",
            "CONTRACT",
            "SCC 2021/914",
            "TIA-2026-001",
            OffsetDateTime.now(ZoneOffset.UTC).minusDays(30),
            "德国仓需要收货地址");
    DataGovernanceTransferView ok =
        as("channel.operation.create", () -> service.createTransfer(actor, input));
    assertThat(ok.legalBasis()).isEqualTo("CONTRACT");
    assertThat(ok.legalBasisLabel()).contains("履行合同");
    assertThat(ok.status()).isEqualTo("ACTIVE");
    assertThat(ok.targetRegion()).isEqualTo("DE");
    assertThat(ok.sourceDomainName()).isEqualTo("9 订单");
  }

  @Test
  @DisplayName("16.6.2.1 传输暂停 / 终止都要理由，终止是终态")
  void transferLifecycle() {
    domain("ORDER", "9 订单", "9 订单（trade 服务）", "PII");
    DataGovernanceTransferInput input =
        new DataGovernanceTransferInput(
            "ORDER",
            "US",
            "某美国 SaaS",
            "CONSENT",
            "",
            "",
            OffsetDateTime.now(ZoneOffset.UTC).minusDays(30),
            "");
    DataGovernanceTransferView transfer =
        as("channel.operation.create", () -> service.createTransfer(actor, input));

    DataGovernanceTransferView suspended =
        as(
            "channel.operation.update",
            () ->
                service.suspendTransfer(
                    actor,
                    transfer.transferId().toString(),
                    new DataGovernanceTransferStatusInput("对方资质待复核")));
    assertThat(suspended.status()).isEqualTo("SUSPENDED");
    assertThat(suspended.suspendReason()).isEqualTo("对方资质待复核");

    DataGovernanceTransferView ended =
        as(
            "channel.operation.update",
            () ->
                service.endTransfer(
                    actor,
                    transfer.transferId().toString(),
                    new DataGovernanceTransferStatusInput("合同到期终止")));
    assertThat(ended.status()).isEqualTo("ENDED");
    assertThat(ended.endReason()).isEqualTo("合同到期终止");
    assertThat(ended.effectiveTo()).isNotNull();

    Throwable error =
        thrown(
            () ->
                as(
                    "channel.operation.update",
                    () ->
                        service.endTransfer(
                            actor,
                            transfer.transferId().toString(),
                            new DataGovernanceTransferStatusInput("再终止一次"))));
    assertThat(error).as("已终止的传输是终态，不许复活").isNotNull();
  }

  @Test
  @DisplayName("16.6.2.1 传输列表是真分页 + 汇总")
  void transferListIsReallyPaged() {
    domain("ORDER", "9 订单", "9 订单（trade 服务）", "PII");
    for (int i = 0; i < 3; i++) {
      int n = i;
      DataGovernanceTransferInput input =
          new DataGovernanceTransferInput(
              "ORDER",
              "DE",
              "接收方 " + n,
              "CONTRACT",
              "",
              "",
              OffsetDateTime.now(ZoneOffset.UTC).minusDays(30),
              "");
      as("channel.operation.create", () -> service.createTransfer(actor, input));
    }
    DataGovernanceTransferPage page =
        as("channel.operation.read", () -> service.transfers(null, null, 0, 2));
    assertThat(page.total()).isEqualTo(3);
    assertThat(page.items()).hasSize(2);
    assertThat(page.counts().active()).isEqualTo(3);
    assertThat(page.counts().total()).isEqualTo(3);
  }

  // ==================================================================
  // 留存策略
  // ==================================================================

  @Test
  @DisplayName("16.6.2.1 留存策略版本化：改策略 = 旧条 SUPERSEDED + 新条版本号递增")
  void policyIsVersioned() {
    domain("ORDER", "9 订单", "9 订单（trade 服务）", "PII");
    RetentionPolicyView first = policy("ORDER", "STANDARD", 180);
    assertThat(first.versionNo()).isEqualTo(1);
    assertThat(first.status()).isEqualTo("ACTIVE");

    OffsetDateTime later = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2);
    RetentionPolicyInput v2 =
        new RetentionPolicyInput(
            "ORDER",
            "普通留存策略 v2",
            "STANDARD",
            java.math.BigDecimal.valueOf(365),
            "CREATION",
            "测试依据",
            later);
    RetentionPolicyView second =
        as("channel.operation.create", () -> service.createPolicy(actor, v2));
    assertThat(second.versionNo()).isEqualTo(2);
    assertThat(second.supersedesId()).isEqualTo(first.policyId());
    assertThat(second.status()).isEqualTo("ACTIVE");

    RetentionPolicyPage page = as("channel.operation.read", () -> service.policies("ORDER", 0, 50));
    assertThat(page.total()).isEqualTo(2);
    String oldStatus =
        page.items().stream()
            .filter(p -> p.policyId().equals(first.policyId()))
            .findFirst()
            .orElseThrow()
            .status();
    assertThat(oldStatus).as("旧版本必须变成 SUPERSEDED（历史判定不被改写）").isEqualTo("SUPERSEDED");
  }

  @Test
  @DisplayName("16.6.2.1 留存策略的生效时刻不许回填")
  void policyEffectiveFromCannotBeBackdated() {
    domain("ORDER", "9 订单", "9 订单（trade 服务）", "PII");
    RetentionPolicyInput backdated =
        new RetentionPolicyInput(
            "ORDER",
            "回填策略",
            "STANDARD",
            java.math.BigDecimal.valueOf(180),
            "CREATION",
            "测试依据",
            OffsetDateTime.now(ZoneOffset.UTC).minusDays(30));
    Throwable error =
        thrown(
            () ->
                as(
                    "channel.operation.create",
                    () -> service.createPolicy(actor, backdated)));
    assertThat(error).as("回填生效时刻必须被拒").isNotNull();
  }

  @Test
  @DisplayName("16.6.2.1 留存天数必须是整天")
  void retentionDaysMustBeWhole() {
    domain("ORDER", "9 订单", "9 订单（trade 服务）", "PII");
    RetentionPolicyInput fractional =
        new RetentionPolicyInput(
            "ORDER",
            "带小数的策略",
            "STANDARD",
            new java.math.BigDecimal("180.5"),
            "CREATION",
            "测试依据",
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10));
    Throwable error =
        thrown(
            () ->
                as(
                    "channel.operation.create",
                    () -> service.createPolicy(actor, fractional)));
    assertThat(error).as("留存天数带小数必须被拒").isNotNull();
  }

  @Test
  @DisplayName("16.6.2.1 timestamptz 往返零漂移（断言写回的值）")
  void timestamptzRoundTripsWithoutDrift() {
    domain("ORDER", "9 订单", "9 订单（trade 服务）", "PII");
    // 🔴 用 +08:00 的偏移量写入：Timestamp.valueOf(LocalDateTime…) 那种写法会静默偏 −28800s。
    //    （时刻取**未来**，因为策略的生效时刻不许回填。）
    OffsetDateTime effectiveFrom =
        OffsetDateTime.of(2027, 3, 1, 10, 0, 0, 0, ZoneOffset.ofHours(8));
    RetentionPolicyInput input =
        new RetentionPolicyInput(
            "ORDER",
            "时区往返策略",
            "STANDARD",
            java.math.BigDecimal.valueOf(180),
            "CREATION",
            "测试依据",
            effectiveFrom);
    RetentionPolicyView saved =
        as("channel.operation.create", () -> service.createPolicy(actor, input));

    assertThat(saved.effectiveFrom().toInstant())
        .as("生效时刻写回后必须还是同一个瞬间（零漂移）")
        .isEqualTo(effectiveFrom.toInstant());
    assertThat(saved.createdAt().toInstant()).isNotNull();

    // 直查数据库也必须是同一个瞬间。
    String raw =
        scalar(
            "select extract(epoch from effective_from)::bigint from retention_policy where id = '"
                + saved.policyId()
                + "'");
    assertThat(Long.parseLong(raw)).isEqualTo(effectiveFrom.toEpochSecond());
  }

  @Test
  @DisplayName("16.6.2.1 留存天数按 NUMERIC 往返（禁浮点）")
  void retentionDaysKeepsNumericPrecision() {
    domain("ORDER", "9 订单", "9 订单（trade 服务）", "PII");
    RetentionPolicyView saved = policy("ORDER", "STANDARD", 3650);
    assertThat(saved.retentionDays()).isEqualByComparingTo("3650");
    assertThat(saved.retentionDays().scale()).isLessThanOrEqualTo(6);
    String raw =
        scalar(
            "select retention_days from retention_policy where id = '" + saved.policyId() + "'");
    assertThat(new java.math.BigDecimal(raw)).isEqualByComparingTo("3650");
  }

  // ==================================================================
  // 冲突裁决（财务留存优先）
  // ==================================================================

  @Test
  @DisplayName("16.6.2.1 财务留存优先：冲突对 / 双方期限 / 裁决结果与依据都看得见")
  void financialRetentionPrevailsIsVisible() {
    domain("ORDER", "9 订单", "9 订单（trade 服务）", "PII");
    policy("ORDER", "STANDARD", 180);
    policy("ORDER", "FINANCIAL", 3650);

    RetentionConflictPage page =
        as("channel.operation.read", () -> service.conflicts("ORDER", 0, 50));
    assertThat(page.total()).isEqualTo(1);
    RetentionConflictView view = page.items().get(0);
    assertThat(view.domainCode()).isEqualTo("ORDER");
    assertThat(view.standardRetentionDays()).isEqualByComparingTo("180");
    assertThat(view.financialRetentionDays()).isEqualByComparingTo("3650");
    assertThat(view.conflictKind()).isEqualTo("FINANCIAL_LONGER");
    assertThat(view.resolution()).isEqualTo("FINANCIAL_PREVAILS");
    assertThat(view.resolutionLabel()).isEqualTo("财务留存优先");
    assertThat(view.effectiveRetentionDays()).isEqualByComparingTo("3650");
    assertThat(view.deletionBlocked()).isTrue();
    assertThat(view.deletionBlockedUntil()).isNotNull();
    assertThat(view.rationale()).contains("财务留存优先").contains("3650").contains("180");
    assertThat(page.counts().conflicting()).isEqualTo(1);
    assertThat(page.counts().deletionBlocked()).isEqualTo(1);
  }

  @Test
  @DisplayName("16.6.2.1 没登记留存策略的数据域 ⇒ 判不了（NO_POLICY），不是「没冲突」")
  void unregisteredPolicyIsUndecidable() {
    domain("ORDER", "9 订单", "9 订单（trade 服务）", "PII");
    RetentionConflictPage page =
        as("channel.operation.read", () -> service.conflicts("ORDER", 0, 50));
    assertThat(page.items().get(0).conflictKind()).isEqualTo("NO_POLICY");
    assertThat(page.items().get(0).effectiveRetentionDays()).isNull();
    assertThat(page.counts().conflicting()).isZero();
  }

  // ==================================================================
  // 删除请求
  // ==================================================================

  @Test
  @DisplayName("16.6.2.1 审批人 ≠ 申请人（自己批自己 403）")
  void approverMustNotBeRequester() {
    domain("ORDER", "9 订单", "9 订单（trade 服务）", "PII");
    policy("ORDER", "STANDARD", 180);
    DataGovernanceDeletionRequestView request = deletion("ORDER", "SO-1001");

    DataGovernanceDecisionInput note = new DataGovernanceDecisionInput("自己批自己");
    Throwable error =
        thrown(
            () ->
                as(
                    "channel.operation.update",
                    () -> service.approveDeletion(actor, request.requestId().toString(), note)));
    assertThat(error).as("自己批自己的审批必须被拒").isNotNull();
  }

  @Test
  @DisplayName("16.6.2.1 判不了 ≠ 默认放行：没有留存策略 ⇒ 审批删除请求 422")
  void deletionWithoutPolicyIsRejected() {
    domain("ORDER", "9 订单", "9 订单（trade 服务）", "PII");
    DataGovernanceDeletionRequestView request = deletion("ORDER", "SO-1001");

    DataGovernanceDecisionInput note = new DataGovernanceDecisionInput("同意");
    Throwable error =
        thrown(
            () ->
                asApprover(
                    "channel.operation.update",
                    () -> service.approveDeletion(approver, request.requestId().toString(), note)));
    assertThat(error).as("没有留存策略 ⇒ 判不了 ⇒ 不许批").isNotNull();
  }

  @Test
  @DisplayName("16.6.2.1 财务留存优先：法定期没走完 ⇒ 判成 DEFERRED，执行被拒")
  void financialRetentionDefersDeletion() {
    domain("ORDER", "9 订单", "9 订单（trade 服务）", "PII");
    policy("ORDER", "FINANCIAL", 3650);
    DataGovernanceDeletionRequestView request = deletion("ORDER", "SO-1001");

    DataGovernanceDecisionInput note = new DataGovernanceDecisionInput("已核实主体");
    DataGovernanceDeletionRequestView approved =
        asApprover(
            "channel.operation.update",
            () -> service.approveDeletion(approver, request.requestId().toString(), note));
    assertThat(approved.status()).isEqualTo("DEFERRED");
    assertThat(approved.statusLabel()).contains("推迟");
    assertThat(approved.deferUntil()).isNotNull();
    assertThat(approved.deferReason()).contains("财务");
    assertThat(approved.approvedBy()).isEqualTo(approverUser);

    Throwable error =
        thrown(
            () ->
                as(
                    "channel.operation.create",
                    () -> service.executeDeletion(actor, request.requestId().toString())));
    assertThat(error).as("被推迟的删除请求不许执行").isNotNull();
  }

  @Test
  @DisplayName("16.6.2.1 没有财务冲突时：审批 ⇒ 执行 ⇒ 派发清理工单 + 执行记录")
  void approvedDeletionProducesCleanupWorkOrder() {
    domain("ORDER", "9 订单", "9 订单（trade 服务）", "PII");
    policy("ORDER", "STANDARD", 180);
    DataGovernanceDeletionRequestView request = deletion("ORDER", "SO-1001");
    assertThat(request.status()).isEqualTo("PENDING");

    DataGovernanceDecisionInput note = new DataGovernanceDecisionInput("已核实主体");
    DataGovernanceDeletionRequestView approved =
        asApprover(
            "channel.operation.update",
            () -> service.approveDeletion(approver, request.requestId().toString(), note));
    assertThat(approved.status()).isEqualTo("APPROVED");
    assertThat(approved.deferUntil()).isNull();

    DataGovernanceDeletionRequestView executed =
        as(
            "channel.operation.create",
            () -> service.executeDeletion(actor, request.requestId().toString()));
    assertThat(executed.status()).isEqualTo("EXECUTED");
    assertThat(executed.cleanupWorkOrderId()).isNotNull();
    assertThat(executed.cleanupOrderNo()).isNotBlank();

    RetentionCleanupOrderView order =
        as(
            "channel.operation.read",
            () -> service.cleanupOrder(executed.cleanupWorkOrderId().toString()));
    assertThat(order.triggerKind()).isEqualTo("DELETION_REQUEST");
    assertThat(order.subjectRef()).isEqualTo("SO-1001");
    assertThat(order.status()).isEqualTo("PENDING");
  }

  @Test
  @DisplayName("16.6.2.1 未审批的删除请求不许执行")
  void pendingDeletionCannotBeExecuted() {
    domain("ORDER", "9 订单", "9 订单（trade 服务）", "PII");
    policy("ORDER", "STANDARD", 180);
    DataGovernanceDeletionRequestView request = deletion("ORDER", "SO-1001");

    Throwable error =
        thrown(
            () ->
                as(
                    "channel.operation.create",
                    () -> service.executeDeletion(actor, request.requestId().toString())));
    assertThat(error).as("未审批的删除请求不许执行").isNotNull();
  }

  @Test
  @DisplayName("16.6.2.1 驳回必须写理由")
  void rejectionRequiresReason() {
    domain("ORDER", "9 订单", "9 订单（trade 服务）", "PII");
    policy("ORDER", "STANDARD", 180);
    DataGovernanceDeletionRequestView request = deletion("ORDER", "SO-1001");

    DataGovernanceRejectInput note = new DataGovernanceRejectInput("主体身份无法核实，不予删除");
    DataGovernanceDeletionRequestView rejected =
        asApprover(
            "channel.operation.update",
            () -> service.rejectDeletion(approver, request.requestId().toString(), note));
    assertThat(rejected.status()).isEqualTo("REJECTED");
    assertThat(rejected.decisionNote()).isEqualTo("主体身份无法核实，不予删除");
  }

  // ==================================================================
  // 清理工单与执行记录
  // ==================================================================

  @Test
  @DisplayName("16.6.2.1 清理工单的完成截止日不得早于最早可清理日（财务留存优先）")
  void cleanupOrderCannotBeScheduledBeforeRetentionMatures() {
    domain("ORDER", "9 订单", "9 订单（trade 服务）", "PII");
    policy("ORDER", "FINANCIAL", 3650);

    RetentionCleanupOrderInput input =
        new RetentionCleanupOrderInput(
            "ORDER", "", "RETENTION_EXPIRY", OffsetDateTime.now(ZoneOffset.UTC).plusDays(1), null);
    Throwable error =
        thrown(
            () ->
                as(
                    "channel.operation.create",
                    () -> service.createCleanupOrder(actor, input)));
    assertThat(error).as("不得把清理排到法定留存期走完之前").isNotNull();
  }

  @Test
  @DisplayName("16.6.2.1 没有留存策略 ⇒ 不许开清理工单（没有依据的清理不许开）")
  void cleanupOrderRequiresPolicy() {
    domain("ORDER", "9 订单", "9 订单（trade 服务）", "PII");
    RetentionCleanupOrderInput input =
        new RetentionCleanupOrderInput("ORDER", "", "RETENTION_EXPIRY", null, null);
    Throwable error =
        thrown(
            () ->
                as(
                    "channel.operation.create",
                    () -> service.createCleanupOrder(actor, input)));
    assertThat(error).as("没有留存策略 ⇒ 算不出最早可清理日 ⇒ 不许开工单").isNotNull();
  }

  @Test
  @DisplayName("16.6.2.1 清理工单执行 ⇒ 落 append-only 执行记录，工单状态跟着走")
  void cleanupExecutionWritesRecord() {
    domain("ORDER", "9 订单", "9 订单（trade 服务）", "PII");
    policy("ORDER", "STANDARD", 180);
    RetentionCleanupOrderInput input =
        new RetentionCleanupOrderInput(
            "ORDER", "SO-2001", "MANUAL", null, new java.math.BigDecimal("12"));
    RetentionCleanupOrderView order =
        as("channel.operation.create", () -> service.createCleanupOrder(actor, input));

    RetentionCleanupExecuteInput execute =
        new RetentionCleanupExecuteInput(
            "SUCCESS", new java.math.BigDecimal("12"), "已清理 12 条过期记录");
    RetentionCleanupOrderView executed =
        as(
            "channel.operation.update",
            () -> service.executeCleanupOrder(actor, order.orderId().toString(), execute));
    assertThat(executed.status()).isEqualTo("COMPLETED");
    assertThat(executed.affectedRecordCount()).isEqualByComparingTo("12");

    RetentionCleanupRecordPage records =
        as(
            "channel.operation.read",
            () -> service.cleanupRecords(order.orderId().toString(), null, 0, 50));
    assertThat(records.total()).isEqualTo(1);
    RetentionCleanupRecordView record = records.items().get(0);
    assertThat(record.action()).isEqualTo("EXECUTE");
    assertThat(record.result()).isEqualTo("SUCCESS");
    assertThat(record.recordCount()).isEqualByComparingTo("12");
    assertThat(record.message()).isEqualTo("已清理 12 条过期记录");
    assertThat(record.executedAt()).isNotNull();

    // 🔴 完成后的工单不许再执行。
    RetentionCleanupExecuteInput again =
        new RetentionCleanupExecuteInput("SUCCESS", new java.math.BigDecimal("1"), "重复执行");
    Throwable error =
        thrown(
            () ->
                as(
                    "channel.operation.update",
                    () -> service.executeCleanupOrder(actor, order.orderId().toString(), again)));
    assertThat(error).as("已完成的工单不许再执行").isNotNull();
  }

  @Test
  @DisplayName("16.6.2.1 清理执行记录 append-only：应用账号与超级账号都改不动、删不掉")
  void cleanupRecordIsAppendOnly() {
    domain("ORDER", "9 订单", "9 订单（trade 服务）", "PII");
    policy("ORDER", "STANDARD", 180);
    RetentionCleanupOrderInput input =
        new RetentionCleanupOrderInput("ORDER", "SO-2001", "MANUAL", null, null);
    RetentionCleanupOrderView order =
        as("channel.operation.create", () -> service.createCleanupOrder(actor, input));
    RetentionCleanupExecuteInput execute =
        new RetentionCleanupExecuteInput("FAILED", new java.math.BigDecimal("0"), "下游不可达");
    as(
        "channel.operation.update",
        () -> service.executeCleanupOrder(actor, order.orderId().toString(), execute));
    String id =
        scalar(
            "select id from retention_cleanup_record where tenant_id = '"
                + tenant
                + "' and work_order_id = '"
                + order.orderId()
                + "'");

    Throwable updateAsApp =
        thrown(
            () ->
                execAsApp(
                    "update retention_cleanup_record set message = '改过了' where id = '" + id + "'"));
    assertThat(updateAsApp).as("应用账号不许改执行记录").isNotNull();

    Throwable deleteAsApp =
        thrown(
            () -> execAsApp("delete from retention_cleanup_record where id = '" + id + "'"));
    assertThat(deleteAsApp).as("应用账号不许删执行记录").isNotNull();

    Throwable updateAsSuper =
        thrown(
            () ->
                execAsSuper(
                    "update retention_cleanup_record set message = '改过了' where id = '" + id + "'"));
    assertThat(updateAsSuper).as("连迁移超级账号都改不掉执行记录").isNotNull();

    Throwable deleteAsSuper =
        thrown(
            () -> execAsSuper("delete from retention_cleanup_record where id = '" + id + "'"));
    assertThat(deleteAsSuper).as("连迁移超级账号都删不掉执行记录").isNotNull();
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  @Test
  @DisplayName("16.6.2.1 能力声明如实标出未交付的上游（available=false + ownerModule）")
  void capabilitiesAreHonest() {
    DataGovernanceCapabilities caps = as("channel.operation.read", () -> service.capabilities());
    assertThat(caps.conflictSemantics()).contains("财务留存优先");
    assertThat(caps.deletionSemantics()).contains("审批人 ≠ 申请人");
    assertThat(caps.cleanupSemantics()).contains("append-only");
    assertThat(caps.auditSemantics()).contains("append-only");
    assertThat(caps.boundaryNote()).contains("12 财务");
    assertThat(caps.downstreamNote()).contains("下游");

    DataGovernanceUpstreamState finance =
        caps.upstreams().stream()
            .filter(u -> u.sourceCode().equals("FINANCE_RETENTION_DETAIL"))
            .findFirst()
            .orElseThrow();
    assertThat(finance.available()).isFalse();
    assertThat(finance.ownerModule()).contains("12 财务");

    DataGovernanceUpstreamState cleanup =
        caps.upstreams().stream()
            .filter(u -> u.sourceCode().equals("CLEANUP_TASK_RUNNER"))
            .findFirst()
            .orElseThrow();
    assertThat(cleanup.available()).isFalse();
    assertThat(cleanup.ownerModule()).contains("数据清理任务");

    boolean identityAvailable =
        caps.upstreams().stream()
            .filter(u -> u.sourceCode().equals("IDENTITY_PERMISSION"))
            .findFirst()
            .orElseThrow()
            .available();
    assertThat(identityAvailable).as("17 权限已交付").isTrue();
  }

  /**
   * 🔴 收尾：停掉本地桩与 Testcontainers 容器。
   *
   * <p>不回收容器的话，Ryuk 只在 JVM 退出时按 session 标签统一回收 —— 整个模块跑完之前容器一直活着
   * （实测 channel 一个服务就能堆到 110+ 个 postgres 容器），容器内存 + Docker VM 把机器压进 swap，
   * JVM 被卡住十几分钟 ⇒ 全量跑时断言假红、SSL 握手失败。
   *
   * <p>顺序：先停外部服务，再停容器。反了会出现「容器已停但还有 in-flight 请求打进来」。
   */
  @AfterAll
  static void stopTestResources() {
    if (identity != null) identity.stop(0);
    pg.stop();
  }
}
