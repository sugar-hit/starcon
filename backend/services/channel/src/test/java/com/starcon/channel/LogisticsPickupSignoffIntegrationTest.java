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
 * 10.3.2.2 揽收与签收确认。
 *
 * <p>本测试类钉七组事：
 *
 * <ol>
 *   <li><b>责任起点来自 10.3.2.1 交运批次</b>：只有<b>已登记交接</b>的批次才建单
 *       （DRAFT 批次责任还没交出去，此时判「超时未揽收」是无中生有）。</li>
 *   <li><b>时限是快照</b>：建单后改 SLA 规则，<b>已有的截止时刻不变</b> ——
 *       事后改档期不能让一笔已经延误的货变成准点。</li>
 *   <li><b>主动监控</b>：巡检按 {@code now()} 判超时（超时小时数是 BigDecimal），
 *       状态跃迁只留一次事件，重跑巡检不刷时间线。</li>
 *   <li><b>提醒去重</b>：同一件 + 同一类 + 同一责任窗口只提醒一次（DB 唯一约束）。</li>
 *   <li><b>运单号对不上时如实报缺口，不猜</b>：缺运单号 / 缺轨迹主体 /
 *       运单号不唯一，三种缺口各自可辨，且**都不自动确认**。</li>
 *   <li><b>下游未接线</b>：待通知记录状态到 READY 为止，<b>不写「已回传」</b>。</li>
 *   <li><b>确认事件流 append-only</b>：UPDATE / DELETE 被拒（应用账号与迁移超级账号各试一次）。</li>
 * </ol>
 *
 * <p><b>时间</b>：凡是要跟「现在」比较的夹具（超时、即将超时）都用
 * <b>相对当前时刻</b>的偏移，否则用例会随墙钟漂移；凡是要断言<b>写回值</b>的
 * 夹具（交运时刻、截止时刻）都用 pin 死的绝对时刻，并断言读回来的值 ——
 * timestamptz 绑定写错会静默偏 8 小时，只断言「没抛异常」是查不出来的。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LogisticsPickupSignoffIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID tenant = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final String CARRIER = "SF";
  static final String BEARER = "Bearer tenant-a";

  /** pin 死的绝对时刻：用于断言 timestamptz 写回值没有偏。 */
  static final OffsetDateTime HANDOVER_PINNED = OffsetDateTime.parse("2026-10-15T09:30:00Z");

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

  static AccessService.Access actor(UUID uid, UUID t, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, t, "Test", UUID.randomUUID(), null,
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
      actors.put(
          BEARER,
          actor(user, tenant, List.of(grant("ALL", allPermissions))));
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
  }

  @Autowired LogisticsPickupSignoffService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @BeforeEach
  void clean() {
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

  <T> T as(String permission, java.util.function.Supplier<T> work) {
    var who = actors.get(BEARER);
    return access.run(who, permission, work);
  }

  // ==================================================================
  // 夹具
  // ==================================================================

  /** 以应用角色执行一条 SQL（租户上下文真实绑定，RLS 真实生效）。 */
  static void execAsTenant(UUID t, String statement) {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + t + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以迁移超级账号执行（用于验证触发器不是死代码）。 */
  static void execAsSuper(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
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

  /** 一个已登记交接的批次 + 逐件清单。 */
  record Handover(UUID batchId, UUID manifestItemId, String batchNo, String waybillNo) {}

  /**
   * 造一个交运批次（可选已登记交接）。
   *
   * <p>写入顺序必须贴着 V129 的触发器走：批次先建（计数为 0）→ 候选入批 →
   * 逐件清单 → 再把批次推到 DISPATCH_PENDING（此时 DB 会重算逐件清单并比对计数）。
   */
  Handover seedHandover(String batchNo, String waybillNo, OffsetDateTime handoverAt,
      boolean dispatched) {
    UUID candidateId = UUID.randomUUID();
    execAsTenant(
        tenant,
        "insert into handover_candidate (id,tenant_id,source_unit_id,source_packing_order_id,"
            + " packed_at,handed_over_at,parcel_count,total_weight_kg,warehouse_ref,"
            + " outbound_order_no,sales_order_no,destination_country,carrier_code,carrier_channel,"
            + " waybill_no,waybill_gap_reason,status,batch_id,synced_at)"
            + " values ('" + candidateId + "','" + tenant + "','" + UUID.randomUUID() + "','"
            + UUID.randomUUID() + "','" + handoverAt.minusHours(2) + "','" + handoverAt.minusHours(2)
            + "',1,1.500000,'WH-1','OB-1','SO-1','US','" + CARRIER + "','POSTAL','"
            + (waybillNo.isEmpty() ? "" : waybillNo)
            + "','" + (waybillNo.isEmpty() ? "上游尚未签发运单号" : "")
            + "','PENDING',null,now())");

    UUID batchId = UUID.randomUUID();
    String bucket = handoverAt.atZoneSameInstant(ZoneOffset.UTC).toLocalDate().toString();
    execAsTenant(
        tenant,
        "insert into handover_batch (id,tenant_id,batch_no,carrier_code,carrier_channel,"
            + " handover_at,handover_bucket,unit_count,parcel_count,total_weight_kg,status,"
            + " status_detail,version,created_at,updated_at)"
            + " values ('" + batchId + "','" + tenant + "','" + batchNo + "','" + CARRIER
            + "','POSTAL','" + handoverAt + "','" + bucket + "',0,0,0,'DRAFT','',0,now(),now())");

    execAsTenant(
        tenant,
        "update handover_candidate set status='BATCHED', batch_id='" + batchId + "'"
            + " where tenant_id='" + tenant + "' and id='" + candidateId + "'");

    UUID manifestId = UUID.randomUUID();
    execAsTenant(
        tenant,
        "insert into handover_manifest_item (id,tenant_id,batch_id,candidate_id,parcel_no,"
            + " parcel_ref,handover_at,handed_by_membership_id,waybill_no,waybill_gap_reason,"
            + " waybill_gap_code,gross_weight_kg,length_mm,width_mm,height_mm,remark,created_at)"
            + " values ('" + manifestId + "','" + tenant + "','" + batchId + "','" + candidateId
            + "',1,'BOX-1','" + handoverAt + "','" + user + "','" + waybillNo + "','"
            + (waybillNo.isEmpty() ? "上游尚未签发运单号" : "") + "','"
            + (waybillNo.isEmpty() ? "UPSTREAM_NOT_ISSUED" : "")
            + "',1.500000,100,100,100,'',now())");

    if (dispatched) {
      execAsTenant(
          tenant,
          "update handover_batch set unit_count=1,parcel_count=1,total_weight_kg=1.500000,"
              + " status='DISPATCH_PENDING', dispatched_by_membership_id='" + user + "',"
              + " dispatched_at='" + handoverAt + "', version=1, updated_at=now()"
              + " where tenant_id='" + tenant + "' and id='" + batchId + "'");
    }
    return new Handover(batchId, manifestId, batchNo, waybillNo);
  }

  /** 造一个轨迹主体（上游 10.3.1.1 的投影）。 */
  UUID seedSubject(String waybillNo) {
    UUID id = UUID.randomUUID();
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
            + " values ('" + id + "','" + tenant + "','" + UUID.randomUUID() + "','REC-1','SO-1',"
            + "'OB-1','WH-1','" + CARRIER + "','" + waybillNo + "','US',null,1.500000,'STANDARD',1,"
            + "'" + handoverNow() + "',null,null,null,null,null,0,'NO_TRACE','','',null,null,"
            + "0,false,0,'','',null,'ACTIVE',0,now(),now())");
    return id;
  }

  /** 造一条承运商轨迹事件。 */
  void seedEvent(UUID subjectId, String carrierEventId, String nodeType, OffsetDateTime at) {
    execAsTenant(
        tenant,
        "insert into logistics_tracking_event (id,tenant_id,subject_id,carrier_code,"
            + " carrier_event_id,node_type,event_code,description,location,occurred_at,received_at,"
            + " estimated_delivery_at,source,exception_code,exception_note,payload,"
            + " registered_by_membership_id,created_at)"
            + " values ('" + UUID.randomUUID() + "','" + tenant + "','" + subjectId + "','" + CARRIER
            + "','" + carrierEventId + "','" + nodeType + "','EV-1','','','" + at + "','" + at
            + "',null,'CARRIER_PUSH',"
            // ⚠️ 异常节点**必须**带异常码（V212 的表级 CHECK：标了异常却说不清是什么异常
            //    = 假标记）；非异常节点两个字段都是空串 —— 列数与值数必须逐字对上。
            + ("EXCEPTION".equals(nodeType) ? "'CARRIER_DAMAGED','包裹外包装破损'" : "'',''")
            + ",'{}'::jsonb,'" + user + "',now())");
  }

  /** 登记一条时限规则。 */
  void seedSla(BigDecimal pickupHours, BigDecimal leadHours, BigDecimal reviewDelay,
      BigDecimal windowHours) {
    as(
        "channel.operation.create",
        () ->
            service.registerSlaRule(
                new PickupSignoffSlaRuleInput(
                    CARRIER,
                    null,
                    pickupHours,
                    leadHours,
                    reviewDelay,
                    windowHours,
                    "ACTIVE",
                    "测试夹具")));
  }

  static OffsetDateTime handoverNow() {
    return OffsetDateTime.now(ZoneOffset.UTC).minusDays(3);
  }

  // ==================================================================
  // 1. 能力声明
  // ==================================================================

  @Test
  @DisplayName("10.3.2.2 能力声明：未交付维度 available=false + ownerModule + impact 三者齐备")
  void capabilitiesReportGapsHonestly() {
    var caps = as("channel.operation.read", () -> service.capabilities());
    assertThat(caps.dimensions()).isNotEmpty();
    for (var d : caps.dimensions()) {
      assertThat(d.dimension()).isNotBlank();
      assertThat(d.description()).isNotBlank();
      if (!d.available()) {
        assertThat(d.ownerModule())
            .as("未交付维度 " + d.dimension() + " 必须点名缺口归属模块")
            .isNotBlank();
        assertThat(d.impact())
            .as("未交付维度 " + d.dimension() + " 必须写明缺了会怎样")
            .isNotBlank();
      }
    }
    // 关键缺口：下游未接线 ⇒ 不得声称可用
    var writeback =
        caps.dimensions().stream()
            .filter(d -> d.dimension().equals("platform_writeback"))
            .findFirst()
            .orElseThrow();
    assertThat(writeback.available()).isFalse();
    assertThat(writeback.ownerModule()).isEqualTo("9.4.2.1");
    var review =
        caps.dimensions().stream()
            .filter(d -> d.dimension().equals("review_invitation"))
            .findFirst()
            .orElseThrow();
    assertThat(review.available()).isFalse();
    // 枚举集合如实列出
    assertThat(caps.caseStatuses()).contains("PENDING_PICKUP", "PICKUP_OVERDUE", "DELIVERED");
    assertThat(caps.pickupStates()).contains("PENDING", "CONFIRMED", "OVERDUE");
    assertThat(caps.gapCodes()).contains("NO_WAYBILL", "SLA_UNCONFIGURED");
  }

  // ==================================================================
  // 2. 建单：责任起点来自已登记交接的批次
  // ==================================================================

  @Test
  @DisplayName("10.3.2.2 只有已登记交接的批次才建单（DRAFT 批次责任还没交出去）")
  void onlyDispatchedBatchesGetCases() {
    seedHandover("HNB-DRAFT", "WB-DRAFT", HANDOVER_PINNED, false);
    seedHandover("HNB-READY", "WB-READY", HANDOVER_PINNED, true);

    var result = as("channel.operation.read", () -> service.sync(new PickupSignoffSyncInput(null, null, 0, 50)));

    assertThat(result.scanned()).isEqualTo(1);
    assertThat(result.created()).isEqualTo(1);
    var page = as("channel.operation.read", () -> service.cases(null, null, null, null, null, null,
        null, false, false, "HANDOVER_AT_DESC", 0, 50));
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).sourceBatchNo()).isEqualTo("HNB-READY");
  }

  @Test
  @DisplayName("10.3.2.2 同步是幂等的：重复同步不产生第二条确认单，也不抹掉已确认的状态")
  void syncIsIdempotent() {
    seedHandover("HNB-1", "WB-1", HANDOVER_PINNED, true);
    seedSubject("WB-1");
    seedSla(new BigDecimal("24"), new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("168"));

    as("channel.operation.read", () -> service.sync(new PickupSignoffSyncInput(null, null, 0, 50)));
    var first = as("channel.operation.read", () -> service.cases(null, null, null, null, null, null,
        null, false, false, "HANDOVER_AT_DESC", 0, 50));
    assertThat(first.items()).hasSize(1);
    UUID caseId = first.items().get(0).id();

    // 人工确认揽收，再重跑同步：确认状态不能被同步抹掉
    as("channel.operation.update", () -> service.attest(caseId,
        new PickupSignoffAttestInput("PICKUP", HANDOVER_PINNED.plusHours(2), "MANUAL-1", ""),
        user));

    var second = as("channel.operation.read", () -> service.sync(new PickupSignoffSyncInput(null, null, 0, 50)));
    assertThat(second.created()).isZero();
    assertThat(second.updated()).isEqualTo(1);
    var page = as("channel.operation.read", () -> service.cases(null, null, null, null, null, null,
        null, false, false, "HANDOVER_AT_DESC", 0, 50));
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).id()).isEqualTo(caseId);
    assertThat(page.items().get(0).pickupState()).isEqualTo("CONFIRMED");
    assertThat(page.items().get(0).pickupEvidenceSource()).isEqualTo("MANUAL_ATTESTATION");
  }

  // ==================================================================
  // 3. 时限是快照
  // ==================================================================

  @Test
  @DisplayName("10.3.2.2 揽收时限是建单时的快照：事后改规则不改变已有的截止时刻")
  void slaIsSnapshotted() {
    seedHandover("HNB-SNAP", "WB-SNAP", HANDOVER_PINNED, true);
    seedSubject("WB-SNAP");
    seedSla(new BigDecimal("24"), new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("168"));

    as("channel.operation.read", () -> service.sync(new PickupSignoffSyncInput(null, null, 0, 50)));
    var before = as("channel.operation.read", () -> service.cases(null, null, null, null, null, null,
        null, false, false, "HANDOVER_AT_DESC", 0, 50)).items().get(0);
    assertThat(before.pickupSlaHours()).isEqualByComparingTo("24.000000");
    assertThat(before.pickupDeadlineAt())
        .as("截止时刻 = 交运时刻 + 24 小时")
        .isEqualTo(HANDOVER_PINNED.plusHours(24));

    // 改规则：24 小时 → 48 小时
    seedSla(new BigDecimal("48"), new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("168"));
    as("channel.operation.read", () -> service.sync(new PickupSignoffSyncInput(null, null, 0, 50)));
    var after = as("channel.operation.read", () -> service.cases(null, null, null, null, null, null,
        null, false, false, "HANDOVER_AT_DESC", 0, 50)).items().get(0);
    assertThat(after.pickupDeadlineAt())
        .as("事后改规则不能把已定的截止时刻改掉")
        .isEqualTo(HANDOVER_PINNED.plusHours(24));
    assertThat(after.pickupSlaHours()).isEqualByComparingTo("24.000000");

    // 新建的件才用新规则
    seedHandover("HNB-SNAP2", "WB-SNAP2", HANDOVER_PINNED, true);
    seedSubject("WB-SNAP2");
    as("channel.operation.read", () -> service.sync(new PickupSignoffSyncInput(null, null, 0, 50)));
    var fresh = as("channel.operation.read", () -> service.cases(null, null, null, null, null, null,
        "WB-SNAP2", false, false, "HANDOVER_AT_DESC", 0, 50)).items().get(0);
    assertThat(fresh.pickupSlaHours()).isEqualByComparingTo("48.000000");
    assertThat(fresh.pickupDeadlineAt()).isEqualTo(HANDOVER_PINNED.plusHours(48));
  }

  @Test
  @DisplayName("10.3.2.2 timestamptz 写回值没有偏（交运时刻按绝对时刻断言）")
  void timestampsRoundTripWithoutShift() {
    seedHandover("HNB-TIME", "WB-TIME", HANDOVER_PINNED, true);
    seedSubject("WB-TIME");
    seedSla(new BigDecimal("24"), new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("168"));

    as("channel.operation.read", () -> service.sync(new PickupSignoffSyncInput(null, null, 0, 50)));
    var row = as("channel.operation.read", () -> service.cases(null, null, null, null, null, null,
        null, false, false, "HANDOVER_AT_DESC", 0, 50)).items().get(0);

    assertThat(row.handoverAt()).isEqualTo(HANDOVER_PINNED);
    assertThat(row.pickupDeadlineAt()).isEqualTo(HANDOVER_PINNED.plusHours(24));
  }

  // ==================================================================
  // 4. 主动监控：巡检推进状态
  // ==================================================================

  @Test
  @DisplayName("10.3.2.2 巡检：承运商 PICKED_UP 事件 ⇒ 确认揽收 + 平台回传待通知")
  void reconcileConfirmsPickup() {
    seedHandover("HNB-PU", "WB-PU", HANDOVER_PINNED, true);
    UUID subjectId = seedSubject("WB-PU");
    seedSla(new BigDecimal("24"), new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("168"));
    seedEvent(subjectId, "EV-PU-1", "PICKED_UP", HANDOVER_PINNED.plusHours(2));

    var result = as("channel.operation.update", () -> service.reconcile(
        new PickupSignoffReconcileInput(null, null, 0, 50)));

    assertThat(result.pickupConfirmed()).isEqualTo(1);
    var row = as("channel.operation.read", () -> service.cases(null, null, null, null, null, null,
        null, false, false, "HANDOVER_AT_DESC", 0, 50)).items().get(0);
    assertThat(row.status()).isEqualTo("PICKED_UP");
    assertThat(row.pickupState()).isEqualTo("CONFIRMED");
    assertThat(row.pickupEvidenceSource()).isEqualTo("CARRIER_EVENT");
    assertThat(row.pickupEvidenceRef()).isEqualTo("EV-PU-1");
    assertThat(row.pickupConfirmedAt()).isEqualTo(HANDOVER_PINNED.plusHours(2));

    // 下游待通知：平台履约回传，状态 READY（**不写「已回传」**）
    var notifications = as("channel.operation.read", () -> service.notifications(null, null, null, null, 0, 50));
    assertThat(notifications.items()).hasSize(1);
    assertThat(notifications.items().get(0).notificationKind())
        .isEqualTo("PLATFORM_FULFILLMENT_WRITEBACK");
    assertThat(notifications.items().get(0).downstreamModule()).isEqualTo("9.4.2.1");
    assertThat(notifications.items().get(0).downstreamStatus()).isEqualTo("READY");
  }

  @Test
  @DisplayName("10.3.2.2 巡检：DELIVERED 事件 ⇒ 已签收 + 评价邀请与售后窗口待通知（时机按规则算）")
  void reconcileConfirmsDelivery() {
    seedHandover("HNB-DL", "WB-DL", HANDOVER_PINNED, true);
    UUID subjectId = seedSubject("WB-DL");
    seedSla(new BigDecimal("24"), new BigDecimal("4"), new BigDecimal("6"), new BigDecimal("72"));
    seedEvent(subjectId, "EV-DL-1", "PICKED_UP", HANDOVER_PINNED.plusHours(2));
    seedEvent(subjectId, "EV-DL-2", "DELIVERED", HANDOVER_PINNED.plusHours(50));

    as("channel.operation.update", () -> service.reconcile(
        new PickupSignoffReconcileInput(null, null, 0, 50)));

    var row = as("channel.operation.read", () -> service.cases(null, null, null, null, null, null,
        null, false, false, "HANDOVER_AT_DESC", 0, 50)).items().get(0);
    assertThat(row.status()).isEqualTo("DELIVERED");
    assertThat(row.deliveryState()).isEqualTo("CONFIRMED");
    assertThat(row.deliveryConfirmedAt()).isEqualTo(HANDOVER_PINNED.plusHours(50));

    var notifications = as("channel.operation.read", () -> service.notifications(null, null, null, null, 0, 50));
    assertThat(notifications.items()).hasSize(3);
    var review =
        notifications.items().stream()
            .filter(n -> n.notificationKind().equals("REVIEW_INVITATION"))
            .findFirst()
            .orElseThrow();
    assertThat(review.downstreamModule()).isEqualTo("15.2.2.1");
    assertThat(review.dueAt())
        .as("评价邀请时机 = 签收时刻 + 6 小时")
        .isEqualTo(HANDOVER_PINNED.plusHours(56));
    var window =
        notifications.items().stream()
            .filter(n -> n.notificationKind().equals("AFTER_SALES_WINDOW_CLOSE"))
            .findFirst()
            .orElseThrow();
    assertThat(window.downstreamModule()).isEqualTo("15");
    assertThat(window.dueAt())
        .as("售后窗口关闭 = 签收时刻 + 72 小时")
        .isEqualTo(HANDOVER_PINNED.plusHours(122));
  }

  // ==================================================================
  // 5. 超时未揽收
  // ==================================================================

  @Test
  @DisplayName("10.3.2.2 超时未揽收：过了时限判 OVERDUE，超时小时数是 BigDecimal，并生成提醒")
  void overduePickupIsDetected() {
    // 交运发生在 30 小时前，24 小时时限 ⇒ 已经超时 6 小时
    OffsetDateTime handover = OffsetDateTime.now(ZoneOffset.UTC).minusHours(30);
    seedHandover("HNB-OD", "WB-OD", handover, true);
    seedSubject("WB-OD");
    seedSla(new BigDecimal("24"), new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("168"));

    as("channel.operation.update", () -> service.reconcile(
        new PickupSignoffReconcileInput(null, null, 0, 50)));

    var row = as("channel.operation.read", () -> service.cases(null, null, null, null, null, null,
        null, false, false, "HANDOVER_AT_DESC", 0, 50)).items().get(0);
    assertThat(row.status()).isEqualTo("PICKUP_OVERDUE");
    assertThat(row.pickupState()).isEqualTo("OVERDUE");
    assertThat(row.overdueHours()).isNotNull();
    assertThat(row.overdueHours())
        .as("超时 6 小时（允许巡检耗时带来的毫秒级误差）")
        .isBetween(new BigDecimal("5.900000"), new BigDecimal("6.100000"));

    // 提醒任务：PICKUP_OVERDUE
    var reminders = as("channel.operation.read", () -> service.reminders(null, null, null, null, 0, 50));
    assertThat(reminders.items()).hasSize(1);
    assertThat(reminders.items().get(0).reminderKind()).isEqualTo("PICKUP_OVERDUE");
    assertThat(reminders.items().get(0).status()).isEqualTo("PENDING");
  }

  @Test
  @DisplayName("10.3.2.2 重跑巡检不会刷时间线，也不会重复提醒（DB 级去重）")
  void reconcileIsIdempotent() {
    OffsetDateTime handover = OffsetDateTime.now(ZoneOffset.UTC).minusHours(30);
    seedHandover("HNB-IDEM", "WB-IDEM", handover, true);
    seedSubject("WB-IDEM");
    seedSla(new BigDecimal("24"), new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("168"));

    as("channel.operation.update", () -> service.reconcile(
        new PickupSignoffReconcileInput(null, null, 0, 50)));
    long eventsAfterFirst = countEvents();
    long remindersAfterFirst = countReminders();

    var second = as("channel.operation.update", () -> service.reconcile(
        new PickupSignoffReconcileInput(null, null, 0, 50)));

    assertThat(second.overdueDetected()).isZero();
    assertThat(second.remindersCreated()).isZero();
    assertThat(countEvents()).isEqualTo(eventsAfterFirst);
    assertThat(countReminders()).isEqualTo(remindersAfterFirst);
  }

  @Test
  @DisplayName("10.3.2.2 即将超时：到点前 lead 小时生成 PICKUP_DUE_SOON 提醒")
  void dueSoonReminderIsGenerated() {
    // 交运发生在 21 小时前，24 小时时限、提前 4 小时提醒 ⇒ 提醒窗口已开但还没超时
    OffsetDateTime handover = OffsetDateTime.now(ZoneOffset.UTC).minusHours(21);
    seedHandover("HNB-SOON", "WB-SOON", handover, true);
    seedSubject("WB-SOON");
    seedSla(new BigDecimal("24"), new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("168"));

    as("channel.operation.update", () -> service.reconcile(
        new PickupSignoffReconcileInput(null, null, 0, 50)));

    var row = as("channel.operation.read", () -> service.cases(null, null, null, null, null, null,
        null, false, false, "HANDOVER_AT_DESC", 0, 50)).items().get(0);
    assertThat(row.status()).isEqualTo("PENDING_PICKUP");
    assertThat(row.pickupState()).isEqualTo("PENDING");

    var reminders = as("channel.operation.read", () -> service.reminders(null, null, null, null, 0, 50));
    assertThat(reminders.items()).hasSize(1);
    assertThat(reminders.items().get(0).reminderKind()).isEqualTo("PICKUP_DUE_SOON");
  }

  long countEvents() {
    return as("channel.operation.read", () -> sql.fetchOne(
            "select count(*) from logistics_pickup_signoff_event").get(0, Long.class));
  }

  long countReminders() {
    return as("channel.operation.read", () -> sql.fetchOne(
            "select count(*) from logistics_pickup_signoff_reminder").get(0, Long.class));
  }

  // ==================================================================
  // 6. 缺口：运单号对不上时如实报，不猜
  // ==================================================================

  @Test
  @DisplayName("10.3.2.2 缺运单号 ⇒ 报 NO_WAYBILL，且不自动确认")
  void missingWaybillIsReportedNotGuessed() {
    seedHandover("HNB-NOWB", "", HANDOVER_PINNED, true);
    seedSla(new BigDecimal("24"), new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("168"));

    as("channel.operation.update", () -> service.reconcile(
        new PickupSignoffReconcileInput(null, null, 0, 50)));

    var row = as("channel.operation.read", () -> service.cases(null, null, null, null, null, null,
        null, false, false, "HANDOVER_AT_DESC", 0, 50)).items().get(0);
    assertThat(row.gapCode()).isEqualTo("NO_WAYBILL");
    assertThat(row.gapReason()).isNotBlank();
    // 🔴「不猜」钉的是**不得自动确认**，不是「不许报超时」：
    //   缺运单号 = 永远看不见承运商事件 = 恰恰是最可能被平台判未发货的那批。
    //   把缺口件从超时口径里剔掉，等于让风险最大的件没人管 —— 那是把监控做反了。
    assertThat(row.pickupState()).as("缺口件不得自动确认揽收").isNotEqualTo("CONFIRMED");
    assertThat(row.pickupConfirmedAt()).as("缺口件不得凭空长出确认时刻").isNull();
    assertThat(row.pickupEvidenceSource()).as("缺口件不得凭空长出承运商证据").isEmpty();
    assertThat(row.status()).as("缺口件不得被标成已揽收").isNotEqualTo("PICKED_UP");
    // 但超时要照报：看不见 ⇒ 更该盯
    assertThat(row.pickupState()).as("过了时限的缺口件照样进超时口径").isEqualTo("OVERDUE");
    assertThat(row.status()).isEqualTo("PICKUP_OVERDUE");
    var reminders = as("channel.operation.read", () -> service.reminders(null, null, null, null, 0, 50));
    assertThat(reminders.items()).as("缺口件超时要真的生成提醒任务").hasSize(1);
    assertThat(reminders.items().get(0).reminderKind()).isEqualTo("PICKUP_OVERDUE");
  }

  @Test
  @DisplayName("10.3.2.2 缺轨迹主体 ⇒ 报 TRACKING_SUBJECT_MISSING")
  void missingSubjectIsReported() {
    seedHandover("HNB-NOSUB", "WB-NOSUB", HANDOVER_PINNED, true);
    seedSla(new BigDecimal("24"), new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("168"));

    as("channel.operation.update", () -> service.reconcile(
        new PickupSignoffReconcileInput(null, null, 0, 50)));

    var row = as("channel.operation.read", () -> service.cases(null, null, null, null, null, null,
        null, false, false, "HANDOVER_AT_DESC", 0, 50)).items().get(0);
    assertThat(row.gapCode()).isEqualTo("TRACKING_SUBJECT_MISSING");
    assertThat(row.sourceSubjectId()).isNull();
  }

  @Test
  @DisplayName("10.3.2.2 同一运单号命中多个轨迹主体 ⇒ 报 SUBJECT_AMBIGUOUS，且不自动确认")
  void ambiguousSubjectIsNotGuessed() {
    seedHandover("HNB-AMB", "WB-AMB", HANDOVER_PINNED, true);
    UUID s1 = seedSubject("WB-AMB");
    seedSubject("WB-AMB");
    seedSla(new BigDecimal("24"), new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("168"));
    seedEvent(s1, "EV-AMB-1", "PICKED_UP", HANDOVER_PINNED.plusHours(2));

    as("channel.operation.update", () -> service.reconcile(
        new PickupSignoffReconcileInput(null, null, 0, 50)));

    var row = as("channel.operation.read", () -> service.cases(null, null, null, null, null, null,
        null, false, false, "HANDOVER_AT_DESC", 0, 50)).items().get(0);
    assertThat(row.gapCode()).isEqualTo("SUBJECT_AMBIGUOUS");
    assertThat(row.subjectMatchCount()).isEqualTo(2);
    // 🔴 同一运单号命中两个主体时**不得自动确认**：猜错一件等于把别的货标成已揽收，
    //   而「已揽收」是要回传平台履约指标的（V217 的口径）。
    //   超时照报 —— 看不见哪一票被揽收，正是最该盯的情形。
    assertThat(row.pickupState())
        .as("运单号不唯一时不得自动确认（猜错一件 = 把别的货标成已揽收）")
        .isNotEqualTo("CONFIRMED");
    assertThat(row.pickupConfirmedAt()).as("不得凭空长出确认时刻").isNull();
    assertThat(row.pickupEvidenceSource()).as("不得凭空长出承运商证据").isEmpty();
    assertThat(row.status()).as("不得被标成已揽收").isNotEqualTo("PICKED_UP");
    assertThat(row.pickupState()).as("过了时限的歧义件照样进超时口径").isEqualTo("OVERDUE");
    assertThat(row.status()).isEqualTo("PICKUP_OVERDUE");
  }

  @Test
  @DisplayName("10.3.2.2 没配时限规则 ⇒ 报 SLA_UNCONFIGURED（不用默认值冒充）")
  void missingSlaIsReported() {
    seedHandover("HNB-NOSLA", "WB-NOSLA", HANDOVER_PINNED, true);
    seedSubject("WB-NOSLA");

    as("channel.operation.update", () -> service.reconcile(
        new PickupSignoffReconcileInput(null, null, 0, 50)));

    var row = as("channel.operation.read", () -> service.cases(null, null, null, null, null, null,
        null, false, false, "HANDOVER_AT_DESC", 0, 50)).items().get(0);
    assertThat(row.gapCode()).isEqualTo("SLA_UNCONFIGURED");
    assertThat(row.pickupDeadlineAt()).isNull();
    assertThat(row.status()).isEqualTo("PENDING_PICKUP");
  }

  // ==================================================================
  // 7. 人工确认 / 提醒处置 / 待通知消费
  // ==================================================================

  @Test
  @DisplayName("10.3.2.2 人工确认：带凭据号与时刻，且产出下游待通知")
  void manualAttestationWorks() {
    seedHandover("HNB-ATT", "WB-ATT", HANDOVER_PINNED, true);
    seedSubject("WB-ATT");
    seedSla(new BigDecimal("24"), new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("168"));
    as("channel.operation.read", () -> service.sync(new PickupSignoffSyncInput(null, null, 0, 50)));
    UUID caseId = as("channel.operation.read", () -> service.cases(null, null, null, null, null, null,
        null, false, false, "HANDOVER_AT_DESC", 0, 50)).items().get(0).id();

    var detail = as("channel.operation.update", () -> service.attest(caseId,
        new PickupSignoffAttestInput("PICKUP", HANDOVER_PINNED.plusHours(3), "MANUAL-PICK-1", "现场登记"),
        user));

    assertThat(detail.signoffCase().status()).isEqualTo("PICKED_UP");
    assertThat(detail.signoffCase().pickupEvidenceSource()).isEqualTo("MANUAL_ATTESTATION");
    assertThat(detail.signoffCase().pickupEvidenceRef()).isEqualTo("MANUAL-PICK-1");
    assertThat(detail.timeline()).isNotEmpty();
    assertThat(detail.timeline().get(0).eventKind()).isEqualTo("ATTESTED");
    assertThat(detail.notifications())
        .as("人工确认同样要产出下游待通知，否则永远等不到评价邀请")
        .isNotEmpty();
  }

  @Test
  @DisplayName("10.3.2.2 重复人工确认被拒（不会把确认时间改掉）")
  void duplicateAttestationIsRejected() {
    seedHandover("HNB-ATT2", "WB-ATT2", HANDOVER_PINNED, true);
    seedSubject("WB-ATT2");
    seedSla(new BigDecimal("24"), new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("168"));
    as("channel.operation.read", () -> service.sync(new PickupSignoffSyncInput(null, null, 0, 50)));
    UUID caseId = as("channel.operation.read", () -> service.cases(null, null, null, null, null, null,
        null, false, false, "HANDOVER_AT_DESC", 0, 50)).items().get(0).id();
    as("channel.operation.update", () -> service.attest(caseId,
        new PickupSignoffAttestInput("PICKUP", HANDOVER_PINNED.plusHours(3), "MANUAL-PICK-1", ""), user));

    assertThat(org.assertj.core.api.Assertions.catchThrowable(
            () -> as("channel.operation.update", () -> service.attest(caseId,
                new PickupSignoffAttestInput("PICKUP", HANDOVER_PINNED.plusHours(9), "MANUAL-PICK-2", ""),
                user))))
        .isNotNull();
  }

  @Test
  @DisplayName("10.3.2.2 提醒可认领、可处置；待通知可登记消费结果")
  void remindersAndNotificationsAreActionable() {
    OffsetDateTime handover = OffsetDateTime.now(ZoneOffset.UTC).minusHours(30);
    seedHandover("HNB-ACT", "WB-ACT", handover, true);
    seedSubject("WB-ACT");
    seedSla(new BigDecimal("24"), new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("168"));
    as("channel.operation.update", () -> service.reconcile(
        new PickupSignoffReconcileInput(null, null, 0, 50)));

    UUID reminderId = as("channel.operation.read", () -> service.reminders(null, null, null, null, 0, 50))
        .items().get(0).id();
    var acked = as("channel.operation.update", () -> service.acknowledgeReminder(reminderId,
        new PickupSignoffReminderActionInput("已联系承运商"), user));
    assertThat(acked.status()).isEqualTo("ACKNOWLEDGED");
    var resolved = as("channel.operation.update", () -> service.resolveReminder(reminderId,
        new PickupSignoffReminderActionInput("已补揽收"), user));
    assertThat(resolved.status()).isEqualTo("RESOLVED");

    UUID caseId = as("channel.operation.read", () -> service.cases(null, null, null, null, null, null,
        null, false, false, "HANDOVER_AT_DESC", 0, 50)).items().get(0).id();
    as("channel.operation.update", () -> service.attest(caseId,
        new PickupSignoffAttestInput("PICKUP", handover.plusHours(1), "MANUAL-1", ""), user));
    UUID notificationId = as("channel.operation.read", () -> service.notifications(null, null, null, null, 0, 50))
        .items().get(0).id();
    var consumed = as("channel.operation.update", () -> service.consumeNotification(notificationId,
        new PickupSignoffNotificationConsumeInput("CONSUMED", "下游已取走"), user));
    assertThat(consumed.downstreamStatus()).isEqualTo("CONSUMED");
    assertThat(consumed.attemptCount()).isEqualTo(1);
  }

  // ==================================================================
  // 8. 列表 / 分页 / 汇总
  // ==================================================================

  @Test
  @DisplayName("10.3.2.2 清单是真分页，汇总不受分页影响")
  void listIsReallyPaginated() {
    for (int i = 0; i < 3; i++) {
      seedHandover("HNB-P" + i, "WB-P" + i, HANDOVER_PINNED.plusMinutes(i), true);
      seedSubject("WB-P" + i);
    }
    seedSla(new BigDecimal("24"), new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("168"));
    as("channel.operation.read", () -> service.sync(new PickupSignoffSyncInput(null, null, 0, 50)));

    var page = as("channel.operation.read", () -> service.cases(null, null, null, null, null, null,
        null, false, false, "HANDOVER_AT_DESC", 0, 2));
    assertThat(page.items()).hasSize(2);
    assertThat(page.totalElements()).isEqualTo(3);
    assertThat(page.totalPages()).isEqualTo(2);
    assertThat(page.summary().totalCount())
        .as("汇要是全量口径，不受 limit/offset 影响")
        .isEqualTo(3);
  }

  @Test
  @DisplayName("10.3.2.2 关键词与筛选是真过滤")
  void filtersReallyFilter() {
    seedHandover("HNB-F1", "WB-F1", HANDOVER_PINNED, true);
    seedSubject("WB-F1");
    seedHandover("HNB-F2", "WB-F2", HANDOVER_PINNED, true);
    seedSubject("WB-F2");
    seedSla(new BigDecimal("24"), new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("168"));
    as("channel.operation.read", () -> service.sync(new PickupSignoffSyncInput(null, null, 0, 50)));

    var byKeyword = as("channel.operation.read", () -> service.cases(null, null, null, null, null, null,
        "WB-F1", false, false, "HANDOVER_AT_DESC", 0, 50));
    assertThat(byKeyword.items()).hasSize(1);
    assertThat(byKeyword.items().get(0).masterWaybillNo()).isEqualTo("WB-F1");

    var byBatch = as("channel.operation.read", () -> service.cases(null, null, null, null, "HNB-F2",
        null, null, false, false, "HANDOVER_AT_DESC", 0, 50));
    assertThat(byBatch.items()).hasSize(1);
    assertThat(byBatch.items().get(0).sourceBatchNo()).isEqualTo("HNB-F2");
  }

  // ==================================================================
  // 9. 确认事件流 append-only
  // ==================================================================

  @Test
  @DisplayName("10.3.2.2 确认事件流 append-only：应用账号与超级账号各试一次")
  void signoffEventsAreAppendOnly() {
    seedHandover("HNB-AP", "WB-AP", HANDOVER_PINNED, true);
    seedSubject("WB-AP");
    seedSla(new BigDecimal("24"), new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("168"));
    as("channel.operation.read", () -> service.sync(new PickupSignoffSyncInput(null, null, 0, 50)));
    UUID eventId = as("channel.operation.read", () -> sql.fetchOne(
            "select id from logistics_pickup_signoff_event limit 1").get("id", UUID.class));

    // 应用账号：权限层挡住（42501）
    var denied = org.assertj.core.api.Assertions.catchThrowable(() -> execAsTenant(tenant,
        "update logistics_pickup_signoff_event set detail='改写' where tenant_id='" + tenant
            + "' and id='" + eventId + "'"));
    assertThat(denied).isNotNull();
    assertThat(sqlState(denied))
        .as("应用账号的 UPDATE 必须被权限层拒（42501）")
        .isEqualTo("42501");

    // 超级账号：触发器挡住（23514），证明触发器不是死代码
    var rejected = org.assertj.core.api.Assertions.catchThrowable(() -> execAsSuper(
        "update logistics_pickup_signoff_event set detail='改写' where tenant_id='" + tenant
            + "' and id='" + eventId + "'"));
    assertThat(rejected).isNotNull();
    assertThat(sqlState(rejected))
        .as("超级账号的 UPDATE 必须被触发器拒（23514）")
        .isEqualTo("23514");
  }

  /** 从异常链里取出 SQLSTATE（PostgreSQL 的错误消息里**不含** SQLSTATE）。 */
  static String sqlState(Throwable t) {
    Throwable cur = t;
    while (cur != null) {
      if (cur instanceof SQLException sql && sql.getSQLState() != null) {
        return sql.getSQLState();
      }
      cur = cur.getCause();
    }
    return "";
  }

  @Test
  @DisplayName("10.3.2.2 业务表全部 tenant_id NOT NULL + FORCE RLS")
  void rlsIsForcedOnEveryTable() {
    for (String table :
        new String[] {
          "logistics_pickup_signoff_sla_rule", "logistics_pickup_signoff_case",
          "logistics_pickup_signoff_event", "logistics_pickup_signoff_reminder",
          "logistics_pickup_signoff_notification"
        }) {
      assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS").isEqualTo("true");
      assertThat(scalar("select is_nullable from information_schema.columns"
              + " where table_name='" + table + "' and column_name='tenant_id'"))
          .as(table + ".tenant_id 必须 NOT NULL").isEqualTo("NO");
    }
  }

  // ==================================================================
  // 10. 端点确实走 HTTP
  // ==================================================================

  @Test
  @DisplayName("10.3.2.2 端点确实走 HTTP 且未认证被拒")
  void endpointsRequireAuthentication() throws Exception {
    http.perform(get("/channel/logistics-pickup-signoff/capabilities"))
        .andExpect(status().isUnauthorized());
    http.perform(get("/channel/logistics-pickup-signoff/cases")
            .header("Authorization", BEARER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());
    http.perform(get("/channel/logistics-pickup-signoff/overview")
            .header("Authorization", BEARER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalCount").exists());
  }
}
