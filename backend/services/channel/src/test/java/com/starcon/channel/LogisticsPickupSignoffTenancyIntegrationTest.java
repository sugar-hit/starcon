package com.starcon.channel;

import static com.starcon.channel.LogisticsPickupSignoffModels.*;
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
import java.time.ZoneOffset;
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
 * 10.3.2.2 揽收与签收确认的<b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（确认单 / 提醒任务 / 下游待通知 / 确认事件 / 时限规则）</li>
 *   <li><b>伪造 tenant header 无效</b>（租户只来自已认证成员关系）</li>
 *   <li><b>连接池归还后不串租户</b>（池压到 1，A→B→A 三轮）</li>
 * </ol>
 *
 * <p>🔴 <b>按主键直查那条必须用 {@code channel_app} 连，不能用 Testcontainers 默认的超级用户。</b>
 * 超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
 * 那不是隔离被破坏，而是这个断言本身失效了。
 *
 * <p>🔴 本模块的夹具是**直接落库**的（不走服务层）：本类的断言是「隔离」，
 * 不是「流程」—— 流程由 {@link LogisticsPickupSignoffIntegrationTest} 负责。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LogisticsPickupSignoffTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  UUID a, b;
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

  @Autowired LogisticsPickupSignoffService service;
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
      // CASCADE：V216 的 logistics_exception_case、V217 的 logistics_pickup_signoff_case
      // 以外键引用 logistics_tracking_subject / logistics_tracking_event / handover_manifest_item，
      // 引用方不在同一条 truncate 语句里，PG 会直接拒
      // （cannot truncate a table referenced in a foreign key constraint），级联一并清干净。
      s.execute("truncate logistics_pickup_signoff_notification, logistics_pickup_signoff_reminder,"
          + " logistics_pickup_signoff_event, logistics_pickup_signoff_case,"
          + " logistics_pickup_signoff_sla_rule,"
          + " logistics_tracking_event, logistics_tracking_subject,"
          + " handover_manifest_item, handover_candidate, handover_event, handover_batch cascade");
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  // ==================================================================
  // 夹具：一个租户下的一整套确认事实
  // ==================================================================

  /** 一个租户的全部主键。 */
  record Fixture(UUID caseId, UUID reminderId, UUID notificationId, UUID eventId, UUID ruleId) {}

  /**
   * 直接落一套完整的确认事实（含交运批次、轨迹主体、承运商事件）。
   *
   * <p>写入顺序贴着 V129 / V212 的触发器走：批次先建（计数 0）→ 候选入批 →
   * 逐件清单 → 批次推到 DISPATCH_PENDING（DB 重算逐件清单并比对计数）。
   */
  Fixture seed(UUID tenant, String suffix) {
    OffsetDateTime handover = OffsetDateTime.parse("2026-10-15T09:30:00Z");
    UUID candidateId = UUID.randomUUID();
    execAsTenant(
        tenant,
        "insert into handover_candidate (id,tenant_id,source_unit_id,source_packing_order_id,"
            + " packed_at,handed_over_at,parcel_count,total_weight_kg,warehouse_ref,"
            + " outbound_order_no,sales_order_no,destination_country,carrier_code,carrier_channel,"
            + " waybill_no,status,batch_id,synced_at)"
            + " values ('" + candidateId + "','" + tenant + "','" + UUID.randomUUID() + "','"
            + UUID.randomUUID() + "','" + handover.minusHours(2) + "','" + handover.minusHours(2)
            + "',1,1.500000,'WH-1','OB-1','SO-1','US','SF','POSTAL','WB-" + suffix
            + "','PENDING',null,now())");

    UUID batchId = UUID.randomUUID();
    execAsTenant(
        tenant,
        "insert into handover_batch (id,tenant_id,batch_no,carrier_code,carrier_channel,"
            + " handover_at,handover_bucket,unit_count,parcel_count,total_weight_kg,status,"
            + " status_detail,version,created_at,updated_at)"
            + " values ('" + batchId + "','" + tenant + "','HNB-" + suffix + "','SF','POSTAL','"
            + handover + "','2026-10-15',0,0,0,'DRAFT','',0,now(),now())");
    execAsTenant(
        tenant,
        "update handover_candidate set status='BATCHED', batch_id='" + batchId + "'"
            + " where tenant_id='" + tenant + "' and id='" + candidateId + "'");

    UUID manifestId = UUID.randomUUID();
    execAsTenant(
        tenant,
        "insert into handover_manifest_item (id,tenant_id,batch_id,candidate_id,parcel_no,"
            + " parcel_ref,handover_at,handed_by_membership_id,waybill_no,gross_weight_kg,"
            + " length_mm,width_mm,height_mm,remark,created_at)"
            + " values ('" + manifestId + "','" + tenant + "','" + batchId + "','" + candidateId
            + "',1,'BOX-1','" + handover + "','" + user + "','WB-" + suffix
            + "',1.500000,100,100,100,'',now())");
    execAsTenant(
        tenant,
        "update handover_batch set unit_count=1,parcel_count=1,total_weight_kg=1.500000,"
            + " status='DISPATCH_PENDING', dispatched_by_membership_id='" + user + "',"
            + " dispatched_at='" + handover + "', version=1, updated_at=now()"
            + " where tenant_id='" + tenant + "' and id='" + batchId + "'");

    UUID subjectId = UUID.randomUUID();
    execAsTenant(
        tenant,
        "insert into logistics_tracking_subject (id,tenant_id,source_shipment_record_id,"
            + " source_record_no,sales_order_no,outbound_order_no,warehouse_ref,carrier_code,"
            + " master_waybill_no,destination_country,channel_id,gross_weight_kg,service_level,"
            + " parcel_count,shipped_at,promised_transit_days_min,promised_transit_days_max,"
            + " promised_eta_earliest_at,promised_eta_at,carrier_eta_at,matched_promise_count,"
            + " latest_node,latest_event_code,latest_description,latest_occurred_at,"
            + " latest_received_at,event_count,exception_flag,exception_count,last_exception_code,"
            + " last_exception_note,last_exception_at,status,version,created_at,updated_at)"
            + " values ('" + subjectId + "','" + tenant + "','" + UUID.randomUUID() + "','REC-1',"
            + "'SO-1','OB-1','WH-1','SF','WB-" + suffix + "','US',null,1.500000,'STANDARD',1,'"
            + handover.minusDays(1) + "',null,null,null,null,null,0,'NO_TRACE','','',null,null,"
            + "0,false,0,'','',null,'ACTIVE',0,now(),now())");

    execAsTenant(
        tenant,
        "insert into logistics_tracking_event (id,tenant_id,subject_id,carrier_code,"
            + " carrier_event_id,node_type,event_code,description,location,occurred_at,received_at,"
            + " estimated_delivery_at,source,exception_code,exception_note,payload,"
            + " registered_by_membership_id,created_at)"
            + " values ('" + UUID.randomUUID() + "','" + tenant + "','" + subjectId + "','SF','EV-"
            + suffix + "-PU','PICKED_UP','EV-1','','','" + handover.plusHours(2) + "','"
            + handover.plusHours(2) + "',null,'CARRIER_PUSH','','','{}'::jsonb,'" + user
            + "',now())");
    // 签收事件：产出「已签收待跟进」的提醒与评价邀请 / 售后窗口待通知
    execAsTenant(
        tenant,
        "insert into logistics_tracking_event (id,tenant_id,subject_id,carrier_code,"
            + " carrier_event_id,node_type,event_code,description,location,occurred_at,received_at,"
            + " estimated_delivery_at,source,exception_code,exception_note,payload,"
            + " registered_by_membership_id,created_at)"
            + " values ('" + UUID.randomUUID() + "','" + tenant + "','" + subjectId + "','SF','EV-"
            + suffix + "-DL','DELIVERED','EV-2','','','" + handover.plusHours(50) + "','"
            + handover.plusHours(50) + "',null,'CARRIER_PUSH','','','{}'::jsonb,'" + user
            + "',now())");

    UUID ruleId = UUID.randomUUID();
    execAsTenant(
        tenant,
        "insert into logistics_pickup_signoff_sla_rule (id,tenant_id,carrier_code,channel_id,"
            + " pickup_sla_hours,reminder_lead_hours,review_invite_delay_hours,"
            + " after_sales_window_hours,status,note,version,created_at,updated_at)"
            + " values ('" + ruleId + "','" + tenant + "','SF',null,24.000000,4.000000,0.000000,"
            + "168.000000,'ACTIVE','隔离夹具',0,now(),now())");

    // 用服务层建单 + 巡检：产出确认单 / 提醒 / 待通知 / 事件
    var who = actors.get(bearerFor(tenant));
    access.run(
        who,
        "channel.operation.create",
        () -> service.sync(new PickupSignoffSyncInput(null, null, 0, 50)));
    access.run(
        who,
        "channel.operation.update",
        () -> service.reconcile(new PickupSignoffReconcileInput(null, null, 0, 50)));

    UUID caseId =
        access.run(
                who,
                "channel.operation.read",
                () -> service.cases(null, null, null, null, null, null, null, false, false,
                    "HANDOVER_AT_DESC", 0, 50))
            .items()
            .get(0)
            .id();
    UUID reminderId =
        access.run(
                who,
                "channel.operation.read",
                () -> service.reminders(null, null, null, null, 0, 50))
            .items()
            .get(0)
            .id();
    UUID notificationId =
        access.run(
                who,
                "channel.operation.read",
                () -> service.notifications(null, null, null, null, 0, 50))
            .items()
            .get(0)
            .id();
    UUID eventId =
        UUID.fromString(
            scalarAsTenant(
                tenant,
                "select id from logistics_pickup_signoff_event where tenant_id = '"
                    + tenant
                    + "' limit 1"));
    return new Fixture(caseId, reminderId, notificationId, eventId, ruleId);
  }

  String bearerFor(UUID tenant) {
    return tenant.equals(a) ? bearerA : bearerB;
  }

  /** 以应用角色（channel_app）直查一行计数 —— RLS 真实生效。 */
  static long countAsTenant(UUID tenant, String statement) {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.read',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(statement)) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * 以应用角色（channel_app）在指定租户上下文里直查一列（RLS 真实生效）。
   *
   * <p>🔴 夹具里取主键**不能**直接用注入的 {@code DSLContext}：那张表开着 FORCE RLS，
   * 而裸 {@code DSLContext} 的连接上没有 {@code app.current_tenant}，策略把行全部滤掉，
   * {@code fetchOne} 返回 null ⇒ 取主键那一步就 NPE，看不出「是隔离生效了」。
   */
  static String scalarAsTenant(UUID tenant, String statement) {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.read',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(statement)) {
        return rs.next() ? rs.getString(1) : null;
      }
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

  static void execAsTenant(UUID tenant, String statement) {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("10.3.2.2 断言一：两租户互不可见（确认单 / 提醒 / 待通知 / 事件 / 规则）")
  void tenantsAreIsolated() {
    Fixture fa = seed(a, "A");
    Fixture fb = seed(b, "B");

    var actorA = actors.get(bearerA);
    var actorB = actors.get(bearerB);

    // 服务层：B 的每一次列表读都只能看到自己
    assertThat(as(actorB, "channel.operation.read",
            () -> service.cases(null, null, null, null, null, null, null, false, false,
                "HANDOVER_AT_DESC", 0, 50)).items())
        .as("B 读不到 A 的确认单").hasSize(1);
    assertThat(as(actorB, "channel.operation.read",
            () -> service.cases(null, null, null, null, null, null, null, false, false,
                "HANDOVER_AT_DESC", 0, 50)).items().get(0).id())
        .isEqualTo(fb.caseId());
    assertThat(as(actorA, "channel.operation.read",
            () -> service.cases(null, null, null, null, null, null, null, false, false,
                "HANDOVER_AT_DESC", 0, 50)).items().get(0).id())
        .isEqualTo(fa.caseId());

    assertThat(as(actorB, "channel.operation.read", () -> service.reminders(null, null, null, null, 0, 50)).items())
        .as("B 读不到 A 的提醒任务").hasSize(1);
    assertThat(as(actorB, "channel.operation.read", () -> service.notifications(null, null, null, null, 0, 50)).items())
        .as("B 读不到 A 的下游待通知").hasSize(3);
    // 确认事件：夹具那一件会走完「建单 → 揽收 → 签收」三段变迁 ⇒ 每租户 3 条。
    // 判据不是「条数是 1」，而是**B 读到的每一条都挂在 B 自己的确认单上**。
    assertThat(as(actorB, "channel.operation.read", () -> service.events(null, null, null, 0, 50)).items())
        .as("B 读不到 A 的确认事件")
        .hasSize(3)
        .allSatisfy(e -> assertThat(e.caseId()).as("B 读到的确认事件必须全挂在 B 自己的单上")
            .isEqualTo(fb.caseId()));
    assertThat(as(actorB, "channel.operation.read", () -> service.slaRules(null, null, 0, 50)).items())
        .as("B 读不到 A 的时限规则").hasSize(1);

    // 详情：B 拿 A 的主键 ⇒ 404
    assertThat(org.assertj.core.api.Assertions.catchThrowable(
            () -> as(actorB, "channel.operation.read", () -> service.caseDetail(fa.caseId()))))
        .as("B 按主键直取 A 的确认单必须拿不到")
        .isNotNull();

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    assertThat(countAsTenant(b, "select count(*) from logistics_pickup_signoff_case where id='" + fa.caseId() + "'"))
        .as("B 的上下文里直查 A 的确认单必须读到零行").isZero();
    assertThat(countAsTenant(b, "select count(*) from logistics_pickup_signoff_reminder where id='" + fa.reminderId() + "'"))
        .isZero();
    assertThat(countAsTenant(b, "select count(*) from logistics_pickup_signoff_notification where id='" + fa.notificationId() + "'"))
        .isZero();
    assertThat(countAsTenant(b, "select count(*) from logistics_pickup_signoff_event where id='" + fa.eventId() + "'"))
        .isZero();
    assertThat(countAsTenant(b, "select count(*) from logistics_pickup_signoff_sla_rule where id='" + fa.ruleId() + "'"))
        .isZero();

    // 反向同理
    assertThat(countAsTenant(a, "select count(*) from logistics_pickup_signoff_case where id='" + fb.caseId() + "'"))
        .isZero();
    assertThat(countAsTenant(a, "select count(*) from logistics_pickup_signoff_notification where id='" + fb.notificationId() + "'"))
        .isZero();

    // 五张表都是 FORCE RLS
    for (String table :
        new String[] {
          "logistics_pickup_signoff_sla_rule", "logistics_pickup_signoff_case",
          "logistics_pickup_signoff_event", "logistics_pickup_signoff_reminder",
          "logistics_pickup_signoff_notification"
        }) {
      assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS").isEqualTo("true");
    }

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(countAsTenant(a, "select count(*) from logistics_pickup_signoff_case where id='" + fa.caseId() + "'"))
        .as("A 的上下文必须能直查 A 自己的确认单（证明上面那个 0 是隔离，不是查询失效）")
        .isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from logistics_pickup_signoff_case where id='" + fb.caseId() + "'"))
        .isEqualTo(1);
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("10.3.2.2 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    Fixture fa = seed(a, "A");
    Fixture fb = seed(b, "B");

    // A 的会话 + B 的头 ⇒ 仍然读不到 B 的数据
    http.perform(get("/channel/logistics-pickup-signoff/cases/" + fb.caseId())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/logistics-pickup-signoff/cases")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(fa.caseId().toString()));

    // A 的会话 + A 的头 ⇒ 只能看到 A 自己的
    http.perform(get("/channel/logistics-pickup-signoff/cases")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(fa.caseId().toString()));

    // 概览也必须按会话里的租户算
    http.perform(get("/channel/logistics-pickup-signoff/overview")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalCount").value(1));

    // 🔴 写入路径同样不被头影响：A 的会话写不出 B 的行
    http.perform(post("/channel/logistics-pickup-signoff/reconcile")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString())
            .contentType("application/json")
            .content("{}"))
        .andExpect(status().isOk());
    assertThat(countAsTenant(a, "select count(*) from logistics_pickup_signoff_case")).isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from logistics_pickup_signoff_case")).isEqualTo(1);

    // 无会话一律 401
    http.perform(get("/channel/logistics-pickup-signoff/cases")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("10.3.2.2 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void poolDoesNotLeakTenants() {
    Fixture fa = seed(a, "A");
    Fixture fb = seed(b, "B");
    var actorA = actors.get(bearerA);
    var actorB = actors.get(bearerB);

    for (int round = 0; round < 3; round++) {
      var rowsA = as(actorA, "channel.operation.read",
          () -> service.cases(null, null, null, null, null, null, null, false, false,
              "HANDOVER_AT_DESC", 0, 50));
      assertThat(rowsA.items()).as("第 " + round + " 轮 A 只看到自己的一行").hasSize(1);
      assertThat(rowsA.items().get(0).id()).isEqualTo(fa.caseId());
      assertThat(rowsA.items().get(0).masterWaybillNo()).isEqualTo("WB-A");

      var rowsB = as(actorB, "channel.operation.read",
          () -> service.cases(null, null, null, null, null, null, null, false, false,
              "HANDOVER_AT_DESC", 0, 50));
      assertThat(rowsB.items()).as("第 " + round + " 轮 B 只看到自己的一行").hasSize(1);
      assertThat(rowsB.items().get(0).id()).isEqualTo(fb.caseId());
      assertThat(rowsB.items().get(0).masterWaybillNo()).isEqualTo("WB-B");

      // 汇总也必须按当轮租户算
      assertThat(as(actorA, "channel.operation.read", () -> service.overview()).totalCount())
          .as("第 " + round + " 轮 A 的概览只算 A 自己的").isEqualTo(1);
      assertThat(as(actorB, "channel.operation.read", () -> service.overview()).totalCount())
          .isEqualTo(1);
    }
  }
}
