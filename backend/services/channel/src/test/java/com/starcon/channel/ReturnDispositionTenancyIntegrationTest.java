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
 * 10.7.1.1 退货接收与处置决策的<b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（接收登记 / 决策 / 处置单 / 成本 / 库存指令 / 再销售限制 / 留痕）</li>
 *   <li><b>伪造 tenant header 无效</b>（租户只来自已认证成员关系）</li>
 *   <li><b>连接池归还后不串租户</b>（池压到 1，A→B→A 三轮）</li>
 * </ol>
 *
 * <p>🔴 <b>按主键直查那条必须用 {@code channel_app} 连，不能用 Testcontainers 默认的超级用户。</b>
 * 超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
 * 那不是隔离被破坏，而是这个断言本身失效了。
 *
 * <p>🔴 本模块的夹具是**直接落库**的（不走服务层）：本类的断言是「隔离」，
 * 不是「流程」—— 上游 trade 夹具与决策流程由
 * {@link ReturnDispositionIntegrationTest} 负责。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReturnDispositionTenancyIntegrationTest {

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

  @Autowired ReturnDispositionService service;
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
      // CASCADE：这些表当前还没有外键引用，但以后任何车道给它们加外键时，
      // 引用方不在同一条 truncate 语句里 PG 会直接拒
      // （cannot truncate a table referenced in a foreign key constraint），级联一并清干净。
      s.execute("truncate return_disposition_event, return_disposition_cost,"
          + " return_inventory_change_instruction, return_disposition_order,"
          + " return_disposition_decision, return_resale_restriction,"
          + " return_receiving_registration cascade");
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  // ==================================================================
  // 夹具：一个租户下的一整套处置事实
  // ==================================================================

  /** 一个租户的全部主键（登记 / 决策 / 处置单 / 成本 / 指令 / 限制）。 */
  record Fixture(
      UUID registrationId,
      UUID decisionId,
      UUID orderId,
      UUID costId,
      UUID instructionId,
      UUID restrictionId) {}

  /**
   * 直接落一套完整的处置事实。
   *
   * <p>走服务层会先读上游 trade，本类的断言是隔离而不是流程，所以直接落库
   * （app.actor 照常盖章，触发器与 RLS 全部真实生效）。
   */
  Fixture seed(UUID tenant) {
    UUID registrationId = UUID.randomUUID();
    UUID decisionId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID costId = UUID.randomUUID();
    UUID instructionId = UUID.randomUUID();
    UUID restrictionId = UUID.randomUUID();
    String receiptId = UUID.randomUUID().toString();
    String receiptLineId = UUID.randomUUID().toString();

    execAsTenant(
        tenant,
        "insert into return_receiving_registration"
            + " (id,tenant_id,receipt_id,receipt_line_id,receipt_no,sku_code,sku_name,batch_no,"
            + "  brand_code,platform_code,warehouse_type,warehouse_ref,warehouse_country,received_qty,"
            + "  received_at,registered_by,note,registration_no)"
            + " values ('" + registrationId + "','" + tenant + "','" + receiptId + "','"
            + receiptLineId + "','RR-TENANT','SKU-TENANT','租户样机','B-T','BRAND-T','AMAZON',"
            + "'LOCAL','WH-T','DE',5, now(), '" + user + "','隔离夹具','RCV-TENANT')");

    execAsTenant(
        tenant,
        "insert into return_disposition_decision"
            + " (id,tenant_id,registration_id,receipt_id,receipt_line_id,receipt_no,sku_code,sku_name,"
            + "  batch_no,brand_code,platform_code,disposition_method,decided_qty,decision_basis,"
            + "  decided_by,decided_at,resale_restricted,status,decision_no)"
            + " values ('" + decisionId + "','" + tenant + "','" + registrationId + "','"
            + receiptId + "','" + receiptLineId + "','RR-TENANT','SKU-TENANT','租户样机',"
            + "'B-T','BRAND-T','AMAZON','RETURN_TO_COUNTRY',5,'隔离夹具', '" + user
            + "', now(), false,'RECORDED','RDC-TENANT')");

    execAsTenant(
        tenant,
        "insert into return_disposition_order"
            + " (id,tenant_id,decision_id,registration_id,receipt_id,receipt_line_id,receipt_no,"
            + "  sku_code,sku_name,batch_no,brand_code,platform_code,disposition_method,disposed_qty,"
            + "  cost_currency,salvage_currency,salvage_amount,status,issued_by,issued_at,disposition_no)"
            + " values ('" + orderId + "','" + tenant + "','" + decisionId + "','" + registrationId
            + "','" + receiptId + "','" + receiptLineId + "','RR-TENANT','SKU-TENANT',"
            + "'租户样机','B-T','BRAND-T','AMAZON','RETURN_TO_COUNTRY',5,'USD','USD',0,'ISSUED','"
            + user + "', now(), 'RDO-TENANT')");

    execAsTenant(
        tenant,
        "insert into return_disposition_cost"
            + " (id,tenant_id,order_id,cost_type,amount,currency,vendor_ref,incurred_at,note)"
            + " values ('" + costId + "','" + tenant + "','" + orderId
            + "','RETURN_FREIGHT',12.34,'USD','F-1', now(), '隔离夹具')");

    execAsTenant(
        tenant,
        "insert into return_inventory_change_instruction"
            + " (id,tenant_id,order_id,disposition_method,instruction_type,target_inventory_state,"
            + "  sku_code,sku_name,batch_no,qty,issued_at,issued_by)"
            + " values ('" + instructionId + "','" + tenant + "','" + orderId
            + "','RETURN_TO_COUNTRY','HOLD_FOR_REPATRIATION','RETURN_PENDING','SKU-TENANT',"
            + "'租户样机','B-T',5, now(), '" + user + "')");

    execAsTenant(
        tenant,
        "insert into return_resale_restriction"
            + " (id,tenant_id,subject_type,subject_ref,restriction_basis,reason,effective_from,status)"
            + " values ('" + restrictionId + "','" + tenant + "','BRAND','BRAND-T',"
            + "'BRAND_REQUIREMENT','隔离夹具的限制', now(), 'ACTIVE')");

    execAsTenant(
        tenant,
        "insert into return_disposition_event"
            + " (id,tenant_id,event_type,registration_id,decision_id,order_id,actor_id,occurred_at,detail)"
            + " values ('" + UUID.randomUUID() + "','" + tenant + "','RECEIVED','" + registrationId
            + "','" + decisionId + "','" + orderId + "','" + user + "', now(), '隔离夹具留痕')");

    return new Fixture(
        registrationId, decisionId, orderId, costId, instructionId, restrictionId);
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
  @DisplayName("10.7.1.1 断言一：两租户互不可见（登记 / 决策 / 处置单 / 成本 / 指令 / 限制 / 留痕）")
  void tenantsAreIsolated() {
    Fixture fa = seed(a);
    Fixture fb = seed(b);

    // 服务层：B 的每一次列表读都只能看到自己
    assertThat(as(actorB, "channel.operation.read", () -> service.registrations(null, null, null, 0, 50)).rows())
        .as("B 读不到 A 的接收登记").hasSize(1);
    assertThat(as(actorB, "channel.operation.read", () -> service.registrations(null, null, null, 0, 50)).rows().get(0).id())
        .isEqualTo(fb.registrationId());
    assertThat(as(actorA, "channel.operation.read", () -> service.registrations(null, null, null, 0, 50)).rows().get(0).id())
        .isEqualTo(fa.registrationId());

    assertThat(as(actorB, "channel.operation.read", () -> service.decisions(null, null, 0, 50)).rows())
        .as("B 读不到 A 的处置决策").hasSize(1);
    assertThat(as(actorB, "channel.operation.read", () -> service.orders(null, null, 0, 50)).rows())
        .as("B 读不到 A 的处置单").hasSize(1);
    assertThat(as(actorB, "channel.operation.read", () -> service.restrictions(null, null, 0, 50)).rows())
        .as("B 读不到 A 的再销售限制").hasSize(1);
    assertThat(as(actorB, "channel.operation.read", () -> service.events(null, null, null, 0, 50)).rows())
        .as("B 读不到 A 的留痕").hasSize(1);

    // 详情：B 拿 A 的主键 ⇒ 404
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as(actorB, "channel.operation.read", () -> service.registrationDetail(fa.registrationId()))))
        .as("B 按主键直取 A 的接收登记必须拿不到")
        .isNotNull();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as(actorB, "channel.operation.read", () -> service.decisionDetail(fa.decisionId()))))
        .isNotNull();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as(actorB, "channel.operation.read", () -> service.orderDetail(fa.orderId()))))
        .as("B 按主键直取 A 的处置单必须拿不到")
        .isNotNull();

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    assertThat(countAsTenant(b, "select count(*) from return_receiving_registration where id='" + fa.registrationId() + "'"))
        .as("B 的上下文里直查 A 的接收登记必须读到零行").isZero();
    assertThat(countAsTenant(b, "select count(*) from return_disposition_decision where id='" + fa.decisionId() + "'"))
        .isZero();
    assertThat(countAsTenant(b, "select count(*) from return_disposition_order where id='" + fa.orderId() + "'"))
        .isZero();
    assertThat(countAsTenant(b, "select count(*) from return_disposition_cost where id='" + fa.costId() + "'"))
        .isZero();
    assertThat(countAsTenant(b, "select count(*) from return_inventory_change_instruction where id='" + fa.instructionId() + "'"))
        .isZero();
    assertThat(countAsTenant(b, "select count(*) from return_resale_restriction where id='" + fa.restrictionId() + "'"))
        .isZero();

    // 反向同理
    assertThat(countAsTenant(a, "select count(*) from return_receiving_registration where id='" + fb.registrationId() + "'"))
        .isZero();
    assertThat(countAsTenant(a, "select count(*) from return_disposition_order where id='" + fb.orderId() + "'"))
        .isZero();

    // 七张表都是 FORCE RLS
    for (String table :
        new String[] {
          "return_receiving_registration", "return_disposition_decision", "return_disposition_order",
          "return_disposition_cost", "return_inventory_change_instruction",
          "return_resale_restriction", "return_disposition_event"
        }) {
      assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS").isEqualTo("true");
    }

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(countAsTenant(a, "select count(*) from return_receiving_registration where id='" + fa.registrationId() + "'"))
        .as("A 的上下文必须能直查 A 自己的登记（证明上面那个 0 是隔离，不是查询失效）")
        .isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from return_disposition_cost where id='" + fa.costId() + "'"))
        .isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from return_disposition_cost where id='" + fb.costId() + "'"))
        .isEqualTo(1);
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("10.7.1.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    Fixture fa = seed(a);
    Fixture fb = seed(b);

    // A 的会话 + B 的头 ⇒ 仍然读不到 B 的数据
    http.perform(get("/channel/return-dispositions/registrations/" + fb.registrationId())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/return-dispositions/registrations")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].id").value(fa.registrationId().toString()));

    // A 的会话 + A 的头 ⇒ 只能看到 A 自己的
    http.perform(get("/channel/return-dispositions/registrations")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].id").value(fa.registrationId().toString()));

    // 概览也必须按会话里的租户算
    http.perform(get("/channel/return-dispositions/overview")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.registrationCount").value(1));

    // 无会话一律 401（伪造的头救不了）
    http.perform(get("/channel/return-dispositions/registrations")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("10.7.1.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    Fixture fa = seed(a);
    Fixture fb = seed(b);

    for (int i = 0; i < 3; i++) {
      var rowsA = as(actorA, "channel.operation.read", () -> service.registrations(null, null, null, 0, 50)).rows();
      var rowsB = as(actorB, "channel.operation.read", () -> service.registrations(null, null, null, 0, 50)).rows();
      assertThat(rowsA).hasSize(1);
      assertThat(rowsA.get(0).id()).isEqualTo(fa.registrationId());
      assertThat(rowsA.get(0).skuCode()).isEqualTo("SKU-TENANT");
      assertThat(rowsB).hasSize(1);
      assertThat(rowsB.get(0).id()).isEqualTo(fb.registrationId());

      // 决策 / 处置单 / 成本也必须跟着租户走
      assertThat(as(actorA, "channel.operation.read", () -> service.decisions(null, null, 0, 50)).rows().get(0).id())
          .isEqualTo(fa.decisionId());
      assertThat(as(actorB, "channel.operation.read", () -> service.decisions(null, null, 0, 50)).rows().get(0).id())
          .isEqualTo(fb.decisionId());
      assertThat(as(actorA, "channel.operation.read", () -> service.orderDetail(fa.orderId())).head().id())
          .isEqualTo(fa.orderId());
      assertThat(as(actorB, "channel.operation.read", () -> service.orderDetail(fb.orderId())).head().id())
          .isEqualTo(fb.orderId());
      // 成本合计也必须是各自的那一条（串了就会把别人的费用算进自己的单子）
      assertThat(as(actorA, "channel.operation.read", () -> service.orderDetail(fa.orderId())).head().totalCostAmount())
          .isEqualByComparingTo("12.34");
      assertThat(as(actorB, "channel.operation.read", () -> service.orderDetail(fb.orderId())).head().totalCostAmount())
          .isEqualByComparingTo("12.34");
    }
  }
}
