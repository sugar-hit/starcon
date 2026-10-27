package com.starcon.channel;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 10.3.1.2 异常件的<b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（异常件 / 处置记录 / 理赔补发单 / 规则 / 下游待通知任务）</li>
 *   <li><b>伪造 tenant header 无效</b>（租户只来自已认证成员关系）</li>
 *   <li><b>连接池归还后不串租户</b>（池压到 1，A→B→A 三轮）</li>
 * </ol>
 *
 * <p>🔴 <b>按主键直查那条必须用 {@code channel_app} 连，不能用 Testcontainers 默认的超级用户。</b>
 * 超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
 * 那不是隔离被破坏，而是这个断言本身失效了。
 *
 * <p>🔴 本模块的夹具是**直接落库**的：本类的断言是「隔离」，不是「流程」——
 * 流程由 {@link LogisticsExceptionIntegrationTest} 负责。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LogisticsExceptionTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA, bearerB;

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

  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\"],\"scopeMode\":\"ALL\"}]";

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
  @Autowired MockMvc http;

  @BeforeEach
  void freshTenants() {
    a = UUID.randomUUID();
    b = UUID.randomUUID();
    bearerA = "Bearer t-a-" + a;
    bearerB = "Bearer t-b-" + b;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearerA, actor(user, a, grants));
    actors.put(bearerB, actor(user, b, grants));
    actorA = actors.get(bearerA);
    actorB = actors.get(bearerB);
    truncateAll();
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

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

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  // ==================================================================
  // 夹具：一个租户下的一整套异常件事实
  // ==================================================================

  /** 一个租户的全部主键。 */
  record Fixture(
      UUID caseId, UUID dispositionId, UUID claimId, UUID ruleId, UUID taskId) {}

  /**
   * 直接落一套完整的异常件事实。
   *
   * <p>走服务层会牵进上游轨迹数据，本类的断言是隔离而不是流程，所以直接落库
   * （app.actor 照常盖章，触发器与 RLS 全部真实生效）。
   */
  Fixture seed(UUID tenant) {
    UUID caseId = UUID.randomUUID();
    UUID dispositionId = UUID.randomUUID();
    UUID claimId = UUID.randomUUID();
    UUID ruleId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();

    execAsTenant(
        tenant,
        "insert into logistics_exception_rule"
            + " (id,tenant_id,rule_code,rule_type,match_node,match_exception_code,threshold_minutes,"
            + "  severity,resolve_sla_minutes,enabled,note,created_by_membership_id)"
            + " values('" + ruleId + "','" + tenant + "','RULE-TENANT','TRACKING_STALLED','','',"
            + "1440,'HIGH',2880,true,'隔离夹具','" + user + "')");

    execAsTenant(
        tenant,
        "insert into logistics_exception_case"
            + " (id,tenant_id,case_no,source,exception_type,severity,status,waybill_no,carrier_code,"
            + "  sales_order_no,stalled_duration_minutes,owner_membership_id,detected_at,due_at,"
            + "  note,created_by_membership_id)"
            + " values('" + caseId + "','" + tenant + "','EXC-TENANT','MANUAL','BUYER_COMPLAINT',"
            + "'HIGH','OPEN','WB-TENANT','SF','ORDER-TENANT',120,'" + user + "',now(),"
            + "now() + interval '1 day','隔离夹具','" + user + "')");

    execAsTenant(
        tenant,
        "insert into logistics_exception_disposition"
            + " (id,tenant_id,case_id,action_type,status,detail,operator_membership_id,occurred_at)"
            + " values('" + dispositionId + "','" + tenant + "','" + caseId + "','CONTACT_CARRIER',"
            + "'SUBMITTED','隔离夹具','" + user + "',now())");

    execAsTenant(
        tenant,
        "insert into logistics_exception_claim"
            + " (id,tenant_id,case_id,disposition_id,claim_no,claim_type,status,amount,currency,qty,"
            + "  created_by_membership_id)"
            + " values('" + claimId + "','" + tenant + "','" + caseId + "','" + dispositionId + "',"
            + "'CLM-TENANT','CARRIER_CLAIM','DRAFT',12.34,'USD',0,'" + user + "')");

    execAsTenant(
        tenant,
        "insert into logistics_exception_downstream_task"
            + " (id,tenant_id,case_id,disposition_id,downstream_module,downstream_status,note)"
            + " values('" + taskId + "','" + tenant + "','" + caseId + "','" + dispositionId + "',"
            + "'15','PENDING_NOTIFY','隔离夹具')");

    return new Fixture(caseId, dispositionId, claimId, ruleId, taskId);
  }

  /** 以应用角色（channel_app）直查一行计数 —— RLS 真实生效。 */
  static long countAsTenant(UUID tenant, String sql) {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.read',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(sql)) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以管理员连接读一行标量（只用于读 pg_class 这类元数据）。 */
  static String scalar(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static void execAsTenant(UUID tenant, String sql) {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("10.3.1.2 断言一：两租户互不可见（异常件 / 处置 / 单据 / 规则 / 下游任务）")
  void tenantsAreIsolated() {
    Fixture fa = seed(a);
    Fixture fb = seed(b);

    // 服务层：B 的每一次列表读都只能看到自己
    var bcases = as(actorB, "channel.operation.read", () -> service.cases(null, null, null, null, null, null, false, 0, 50));
    assertThat(bcases.rows()).as("B 读不到 A 的异常件").hasSize(1);
    assertThat(bcases.rows().get(0).id()).isEqualTo(fb.caseId());
    assertThat(as(actorA, "channel.operation.read", () -> service.cases(null, null, null, null, null, null, false, 0, 50)).rows().get(0).id())
        .isEqualTo(fa.caseId());

    assertThat(as(actorB, "channel.operation.read", () -> service.dispositions(null, null, null, 0, 50)).rows())
        .as("B 读不到 A 的处置记录").hasSize(1);
    assertThat(as(actorB, "channel.operation.read", () -> service.claims(null, null, null, 0, 50)).rows())
        .as("B 读不到 A 的单据").hasSize(1);
    assertThat(as(actorB, "channel.operation.read", () -> service.rules(null, 0, 50)).rows())
        .as("B 读不到 A 的规则").hasSize(1);
    assertThat(as(actorB, "channel.operation.read", () -> service.caseDetail(fb.caseId())).downstreamTasks())
        .as("B 只看得到自己的下游任务").hasSize(1);

    // 详情：B 拿 A 的主键 ⇒ 404
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as(actorB, "channel.operation.read", () -> service.caseDetail(fa.caseId()))))
        .as("B 按主键直取 A 的异常件必须拿不到")
        .isNotNull();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as(actorB, "channel.operation.read", () -> service.dispositionDetail(fa.dispositionId()))))
        .isNotNull();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as(actorB, "channel.operation.read", () -> service.claimDetail(fa.claimId()))))
        .isNotNull();

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    assertThat(countAsTenant(b, "select count(*) from logistics_exception_case where id='" + fa.caseId() + "'"))
        .as("B 的上下文里直查 A 的异常件必须读到零行").isZero();
    assertThat(countAsTenant(b, "select count(*) from logistics_exception_disposition where id='" + fa.dispositionId() + "'"))
        .isZero();
    assertThat(countAsTenant(b, "select count(*) from logistics_exception_claim where id='" + fa.claimId() + "'"))
        .isZero();
    assertThat(countAsTenant(b, "select count(*) from logistics_exception_rule where id='" + fa.ruleId() + "'"))
        .isZero();
    assertThat(countAsTenant(b, "select count(*) from logistics_exception_downstream_task where id='" + fa.taskId() + "'"))
        .isZero();

    // 反向同理
    assertThat(countAsTenant(a, "select count(*) from logistics_exception_case where id='" + fb.caseId() + "'"))
        .isZero();
    assertThat(countAsTenant(a, "select count(*) from logistics_exception_claim where id='" + fb.claimId() + "'"))
        .isZero();

    // 五张表都是 FORCE RLS
    for (String table :
        new String[] {
          "logistics_exception_rule",
          "logistics_exception_case",
          "logistics_exception_disposition",
          "logistics_exception_claim",
          "logistics_exception_downstream_task"
        }) {
      assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS").isEqualTo("true");
      assertThat(scalar("select is_nullable from information_schema.columns where table_name='" + table + "' and column_name='tenant_id'"))
          .as(table + ".tenant_id 必须 NOT NULL").isEqualTo("NO");
    }

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(countAsTenant(a, "select count(*) from logistics_exception_case where id='" + fa.caseId() + "'"))
        .as("A 的上下文必须能直查 A 自己的异常件（证明上面那个 0 是隔离，不是查询失效）")
        .isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from logistics_exception_claim where id='" + fa.claimId() + "'"))
        .isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from logistics_exception_claim where id='" + fb.claimId() + "'"))
        .isEqualTo(1);
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("10.3.1.2 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    Fixture fa = seed(a);
    Fixture fb = seed(b);

    // A 的会话 + B 的头 ⇒ 仍然读不到 B 的数据
    http.perform(get("/channel/logistics-tracking/exceptions/" + fb.caseId())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/logistics-tracking/exceptions")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].id").value(fa.caseId().toString()));

    // A 的会话 + A 的头 ⇒ 只能看到 A 自己的
    http.perform(get("/channel/logistics-tracking/exceptions")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].id").value(fa.caseId().toString()));

    // 概览也必须按会话里的租户算
    http.perform(get("/channel/logistics-tracking/exceptions/overview")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.caseCount").value(1));

    // 处置与单据同样按会话租户算
    http.perform(get("/channel/logistics-tracking/dispositions")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].id").value(fa.dispositionId().toString()));
    http.perform(get("/channel/logistics-tracking/dispositions/claims")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].id").value(fa.claimId().toString()));

    // 无会话 ⇒ 401（伪造头也不能代替会话）
    http.perform(get("/channel/logistics-tracking/exceptions")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("10.3.1.2 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotLeakTenants() {
    Fixture fa = seed(a);
    Fixture fb = seed(b);

    for (int round = 0; round < 3; round++) {
      var aRows =
          as(actorA, "channel.operation.read", () -> service.cases(null, null, null, null, null, null, false, 0, 50));
      assertThat(aRows.rows()).as("第 " + round + " 轮 A 只看到自己的一件").hasSize(1);
      assertThat(aRows.rows().get(0).id()).isEqualTo(fa.caseId());

      var bRows =
          as(actorB, "channel.operation.read", () -> service.cases(null, null, null, null, null, null, false, 0, 50));
      assertThat(bRows.rows()).as("第 " + round + " 轮 B 只看到自己的一件").hasSize(1);
      assertThat(bRows.rows().get(0).id()).isEqualTo(fb.caseId());

      // 详情也跟着换租户：同一个连接上，A 的上下文里绝不能读到 B 的件
      assertThat(
              org.assertj.core.api.Assertions.catchThrowable(
                  () -> as(actorA, "channel.operation.read", () -> service.caseDetail(fb.caseId()))))
          .as("第 " + round + " 轮 A 读 B 的件必须失败")
          .isNotNull();
      assertThat(
              org.assertj.core.api.Assertions.catchThrowable(
                  () -> as(actorB, "channel.operation.read", () -> service.caseDetail(fa.caseId()))))
          .as("第 " + round + " 轮 B 读 A 的件必须失败")
          .isNotNull();
    }
  }

  @Test
  @DisplayName("10.3.1.2 跨租户与不存在返回逐字相同的 404")
  void crossTenantAndMissingAreIndistinguishable() {
    Fixture fa = seed(a);
    UUID missing = UUID.randomUUID();

    var cross =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> as(actorB, "channel.operation.read", () -> service.caseDetail(fa.caseId())));
    var absent =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> as(actorB, "channel.operation.read", () -> service.caseDetail(missing)));
    assertThat(cross).isNotNull();
    assertThat(absent).isNotNull();
    assertThat(cross.getMessage()).as("跨租户与不存在的理由必须逐字相同").isEqualTo(absent.getMessage());
  }
}
