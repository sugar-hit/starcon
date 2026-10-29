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
 * 10.7.1.3 弃置与销毁的<b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（销毁单 / 凭证 / 费用 / 索赔依据 / 留痕 / 凭证要求）</li>
 *   <li><b>伪造 tenant header 无效</b>（租户只来自已认证成员关系）</li>
 *   <li><b>连接池归还后不串租户</b>（池压到 1，A→B→A 三轮）</li>
 * </ol>
 *
 * <p>🔴 <b>按主键直查那条必须用 {@code channel_app} 连，不能用 Testcontainers 默认的超级用户。</b>
 * 超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
 * 那不是隔离被破坏，而是这个断言本身失效了。
 *
 * <p>🔴 本模块的夹具是**直接落库**的（不走服务层）：本类的断言是「隔离」，
 * 不是「流程」—— 建单与凭证闸的流程由 {@link ReturnDisposalIntegrationTest} 负责。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReturnDisposalTenancyIntegrationTest {

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

  @Autowired ReturnDisposalService service;
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
      s.execute("truncate return_disposal_event, return_disposal_claim_basis, return_disposal_cost,"
          + " return_disposal_evidence, return_disposal_order, return_disposal_evidence_policy,"
          + " return_inventory_change_instruction, return_disposition_cost, return_disposition_order,"
          + " return_disposition_decision, return_resale_restriction, return_receiving_registration"
          + " cascade");
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  // ==================================================================
  // 夹具：一个租户下的一整套销毁事实
  // ==================================================================

  record Fixture(
      UUID registrationId,
      UUID decisionId,
      UUID orderId,
      UUID disposalId,
      UUID evidenceId,
      UUID weightEvidenceId,
      UUID certificateEvidenceId,
      UUID executorEvidenceId,
      UUID costId,
      UUID claimId,
      UUID eventId,
      UUID policyId) {}

  /** 直接落一套完整的销毁事实（app.actor 照常盖章，触发器与 RLS 全部真实生效）。 */
  Fixture seed(UUID tenant) {
    UUID registrationId = UUID.randomUUID();
    UUID decisionId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID disposalId = UUID.randomUUID();
    UUID evidenceId = UUID.randomUUID();
    UUID weightEvidenceId = UUID.randomUUID();
    UUID certificateEvidenceId = UUID.randomUUID();
    UUID executorEvidenceId = UUID.randomUUID();
    UUID costId = UUID.randomUUID();
    UUID claimId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID policyId = UUID.randomUUID();
    String receiptId = UUID.randomUUID().toString();
    String receiptLineId = UUID.randomUUID().toString();
    String receiptNo = "RR-TENANT";
    String dispositionNo = "RDO-TENANT";

    execAsTenant(
        tenant,
        "insert into return_receiving_registration"
            + " (id,tenant_id,receipt_id,receipt_line_id,receipt_no,sku_code,sku_name,batch_no,"
            + "  brand_code,platform_code,warehouse_type,warehouse_ref,warehouse_country,received_qty,"
            + "  received_at,note,registration_no)"
            + " values ('" + registrationId + "','" + tenant + "','" + receiptId + "','"
            + receiptLineId + "','" + receiptNo + "','SKU-TENANT','租户样机','B-T','BRAND-T','AMAZON',"
            + "'OVERSEAS','WH-T','DE',5, now(), '隔离夹具','RCV-TENANT')");

    execAsTenant(
        tenant,
        "insert into return_disposition_decision"
            + " (id,tenant_id,registration_id,receipt_id,receipt_line_id,receipt_no,sku_code,sku_name,"
            + "  batch_no,brand_code,platform_code,disposition_method,decided_qty,decision_basis,"
            + "  decided_at,resale_restricted,status,decision_no)"
            + " values ('" + decisionId + "','" + tenant + "','" + registrationId + "','"
            + receiptId + "','" + receiptLineId + "','" + receiptNo + "','SKU-TENANT','租户样机',"
            + "'B-T','BRAND-T','AMAZON','DISCARD',5,'隔离夹具', now(), false,"
            + "'RECORDED','RDC-TENANT')");

    execAsTenant(
        tenant,
        "insert into return_disposition_order"
            + " (id,tenant_id,decision_id,registration_id,receipt_id,receipt_line_id,receipt_no,"
            + "  sku_code,sku_name,batch_no,brand_code,platform_code,disposition_method,disposed_qty,"
            + "  cost_currency,salvage_currency,salvage_amount,status,issued_at,disposition_no)"
            + " values ('" + orderId + "','" + tenant + "','" + decisionId + "','" + registrationId
            + "','" + receiptId + "','" + receiptLineId + "','" + receiptNo + "','SKU-TENANT',"
            + "'租户样机','B-T','BRAND-T','AMAZON','DISCARD',5,'USD','USD',0,'ISSUED',"
            + " now(), '" + dispositionNo + "')");

    // 凭证要求（租户级配置）
    execAsTenant(
        tenant,
        "insert into return_disposal_evidence_policy"
            + " (id,tenant_id,subject_type,subject_ref,required_photo,required_weight,"
            + "  required_certificate,required_executor,effective_from,note)"
            + " values ('" + policyId + "','" + tenant + "','BRAND','BRAND-T',true,true,true,true,"
            + " now(), '隔离夹具')");

    // 销毁单：先草稿，再排期，再销毁（状态机迁移必须合法）
    execAsTenant(
        tenant,
        "insert into return_disposal_order"
            + " (id,tenant_id,disposal_no,disposition_order_id,decision_id,source_reference,"
            + "  receipt_no,sku_code,sku_name,batch_no,brand_code,platform_code,disposal_method,"
            + "  disposal_qty,weight_unit,cost_currency,note)"
            + " values ('" + disposalId + "','" + tenant + "','DSP-TENANT','" + orderId + "','"
            + decisionId + "','" + dispositionNo + "','" + receiptNo + "','SKU-TENANT','租户样机',"
            + "'B-T','BRAND-T','AMAZON','SHRED',5,'KG','USD','隔离夹具')");

    execAsTenant(
        tenant,
        "insert into return_disposal_evidence"
            + " (id,tenant_id,disposal_order_id,evidence_kind,photo_url,weight_kg,certificate_no,"
            + "  executor_name,occurred_at,note)"
            + " values ('" + evidenceId + "','" + tenant + "','" + disposalId + "','PHOTO',"
            + "'https://cdn.example.com/tenant.jpg',null,'','', now(), '隔离夹具')");
    // 🔴 夹具的品牌方要求四面旗子全开 ⇒ 四类凭证都必须真的在
    execAsTenant(
        tenant,
        "insert into return_disposal_evidence"
            + " (id,tenant_id,disposal_order_id,evidence_kind,photo_url,weight_kg,certificate_no,"
            + "  executor_name,occurred_at,note)"
            + " values ('" + weightEvidenceId + "','" + tenant + "','" + disposalId + "','WEIGHT',"
            + "'',12.500000,'','', now(), '隔离夹具')");
    execAsTenant(
        tenant,
        "insert into return_disposal_evidence"
            + " (id,tenant_id,disposal_order_id,evidence_kind,photo_url,weight_kg,certificate_no,"
            + "  executor_name,occurred_at,note)"
            + " values ('" + certificateEvidenceId + "','" + tenant + "','" + disposalId
            + "','CERTIFICATE','',null,'INC-TENANT-0001','', now(), '隔离夹具')");
    execAsTenant(
        tenant,
        "insert into return_disposal_evidence"
            + " (id,tenant_id,disposal_order_id,evidence_kind,photo_url,weight_kg,certificate_no,"
            + "  executor_name,occurred_at,note)"
            + " values ('" + executorEvidenceId + "','" + tenant + "','" + disposalId + "','EXECUTOR',"
            + "'',null,'','销毁厂 T', now(), '隔离夹具')");

    execAsTenant(
        tenant,
        "update return_disposal_order set status='SCHEDULED' where tenant_id='"
            + tenant + "' and id='" + disposalId + "'");
    execAsTenant(
        tenant,
        "update return_disposal_order set status='DISPOSED', disposed_at=now(), executed_by='"
            + user + "', executor_name='销毁厂 T', weight_kg=12.500000 where tenant_id='"
            + tenant + "' and id='" + disposalId + "'");

    execAsTenant(
        tenant,
        "insert into return_disposal_cost"
            + " (id,tenant_id,disposal_order_id,cost_type,amount,currency,vendor_ref,incurred_at,note)"
            + " values ('" + costId + "','" + tenant + "','" + disposalId
            + "','DESTROY_FEE',12.34,'USD','销毁厂 T', now(), '隔离夹具')");

    execAsTenant(
        tenant,
        "insert into return_disposal_claim_basis"
            + " (id,tenant_id,disposal_order_id,claim_type,counterparty_code,claim_qty,"
            + "  claim_weight_kg,claimed_amount,claimed_currency,source_reference,note)"
            + " values ('" + claimId + "','" + tenant + "','" + disposalId
            + "','BRAND','BRAND-T',5,12.500000,100.00,'USD','" + dispositionNo + "','隔离夹具')");

    execAsTenant(
        tenant,
        "insert into return_disposal_event"
            + " (id,tenant_id,event_type,order_id,evidence_id,cost_id,claim_basis_id,actor_id,"
            + "  occurred_at,detail)"
            + " values ('" + eventId + "','" + tenant + "','ORDER_DISPOSED','" + disposalId + "','"
            + evidenceId + "','" + costId + "','" + claimId + "','" + user
            + "', now(), '隔离夹具留痕')");

    return new Fixture(
        registrationId, decisionId, orderId, disposalId, evidenceId, weightEvidenceId,
        certificateEvidenceId, executorEvidenceId, costId, claimId, eventId, policyId);
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
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
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
  @DisplayName("10.7.1.3 断言一：两租户互不可见（销毁单 / 凭证 / 费用 / 索赔依据 / 留痕 / 凭证要求）")
  void tenantsAreIsolated() {
    Fixture fa = seed(a);
    Fixture fb = seed(b);

    // 服务层：A / B 各自只看到自己的销毁单
    var rowsA = as(actorA, "channel.operation.read", () -> service.orders(null, null, null, 0, 50)).rows();
    var rowsB = as(actorB, "channel.operation.read", () -> service.orders(null, null, null, 0, 50)).rows();
    assertThat(rowsA).hasSize(1);
    assertThat(rowsB).hasSize(1);
    assertThat(rowsA.get(0).id()).isEqualTo(fa.disposalId());
    assertThat(rowsB.get(0).id()).isEqualTo(fb.disposalId());

    // 🔴 凭证要求也是租户级配置：A 看不到 B 的品牌方要求
    var policiesA = as(actorA, "channel.operation.read", () -> service.policies(null, null, 0, 50)).rows();
    var policiesB = as(actorB, "channel.operation.read", () -> service.policies(null, null, 0, 50)).rows();
    assertThat(policiesA).hasSize(1);
    assertThat(policiesA.get(0).id()).isEqualTo(fa.policyId());
    assertThat(policiesB.get(0).id()).isEqualTo(fb.policyId());

    // 留痕也必须按租户隔离
    assertThat(as(actorA, "channel.operation.read", () -> service.events(null, 0, 50)).rows())
        .as("A 读不到 B 的留痕").hasSize(1);

    // 详情：B 拿 A 的主键 ⇒ 404
    assertThatThrownBy(() -> as(actorB, "channel.operation.read", () -> service.orderDetail(fa.disposalId())))
        .as("B 按主键直取 A 的销毁单必须拿不到")
        .hasMessageContaining("不存在");

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    assertThat(countAsTenant(b, "select count(*) from return_disposal_order where id='" + fa.disposalId() + "'"))
        .as("B 的上下文里直查 A 的销毁单必须读到零行").isZero();
    assertThat(countAsTenant(b, "select count(*) from return_disposal_evidence where id='" + fa.evidenceId() + "'"))
        .isZero();
    assertThat(countAsTenant(b, "select count(*) from return_disposal_cost where id='" + fa.costId() + "'"))
        .isZero();
    assertThat(countAsTenant(b, "select count(*) from return_disposal_claim_basis where id='" + fa.claimId() + "'"))
        .isZero();
    assertThat(countAsTenant(b, "select count(*) from return_disposal_event where id='" + fa.eventId() + "'"))
        .isZero();
    assertThat(countAsTenant(b, "select count(*) from return_disposal_evidence_policy where id='" + fa.policyId() + "'"))
        .as("B 的上下文里读不到 A 的凭证要求").isZero();

    // 反向同理
    assertThat(countAsTenant(a, "select count(*) from return_disposal_order where id='" + fb.disposalId() + "'"))
        .isZero();
    assertThat(countAsTenant(a, "select count(*) from return_disposal_claim_basis where id='" + fb.claimId() + "'"))
        .isZero();

    // 六张表都是 FORCE RLS
    for (String table :
        new String[] {
          "return_disposal_evidence_policy", "return_disposal_order", "return_disposal_evidence",
          "return_disposal_cost", "return_disposal_claim_basis", "return_disposal_event"
        }) {
      assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS").isEqualTo("true");
    }

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(countAsTenant(a, "select count(*) from return_disposal_order where id='" + fa.disposalId() + "'"))
        .as("A 的上下文必须能直查 A 自己的销毁单（证明上面那个 0 是隔离，不是查询失效）")
        .isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from return_disposal_cost where id='" + fa.costId() + "'"))
        .isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from return_disposal_cost where id='" + fb.costId() + "'"))
        .isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from return_disposal_evidence_policy where id='" + fa.policyId() + "'"))
        .isEqualTo(1);
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("10.7.1.3 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    Fixture fa = seed(a);
    Fixture fb = seed(b);

    // A 的会话 + B 的头 ⇒ 仍然读不到 B 的数据
    http.perform(get("/channel/return-disposals/orders/" + fb.disposalId())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/return-disposals/orders")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].id").value(fa.disposalId().toString()));

    // A 的会话 + A 的头 ⇒ 只能看到 A 自己的
    http.perform(get("/channel/return-disposals/orders")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].id").value(fa.disposalId().toString()));

    // 概览也必须按会话里的租户算
    http.perform(get("/channel/return-disposals/overview")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orderCount").value(1));

    // 凭证要求同样按会话里的租户算
    http.perform(get("/channel/return-disposals/policies")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].id").value(fa.policyId().toString()));

    // 无会话一律 401（伪造的头救不了）
    http.perform(get("/channel/return-disposals/orders")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("10.7.1.3 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    Fixture fa = seed(a);
    Fixture fb = seed(b);

    for (int i = 0; i < 3; i++) {
      var rowsA = as(actorA, "channel.operation.read", () -> service.orders(null, null, null, 0, 50)).rows();
      var rowsB = as(actorB, "channel.operation.read", () -> service.orders(null, null, null, 0, 50)).rows();
      assertThat(rowsA).hasSize(1);
      assertThat(rowsA.get(0).id()).isEqualTo(fa.disposalId());
      assertThat(rowsA.get(0).disposalNo()).isEqualTo("DSP-TENANT");
      assertThat(rowsB).hasSize(1);
      assertThat(rowsB.get(0).id()).isEqualTo(fb.disposalId());

      // 详情里的凭证 / 费用 / 索赔依据也必须跟着租户走
      var detailA = as(actorA, "channel.operation.read", () -> service.orderDetail(fa.disposalId()));
      var detailB = as(actorB, "channel.operation.read", () -> service.orderDetail(fb.disposalId()));
      // 凭证按种类排序，所以按 kind 取而不是按下标
      assertThat(detailA.evidence()).hasSize(4);
      assertThat(detailB.evidence()).hasSize(4);
      assertThat(detailA.evidence().stream().filter(e -> "PHOTO".equals(e.evidenceKind())).findFirst().orElseThrow().id())
          .isEqualTo(fa.evidenceId());
      assertThat(detailB.evidence().stream().filter(e -> "PHOTO".equals(e.evidenceKind())).findFirst().orElseThrow().id())
          .isEqualTo(fb.evidenceId());
      // 费用合计也必须是各自的那一条（串了就会把别人的销毁费算进自己的单子）
      assertThat(detailA.order().totalCost()).isEqualByComparingTo("12.34");
      assertThat(detailB.order().totalCost()).isEqualByComparingTo("12.34");
      assertThat(detailA.claimBases().get(0).id()).isEqualTo(fa.claimId());
      assertThat(detailB.claimBases().get(0).id()).isEqualTo(fb.claimId());
    }
  }
}
