package com.starcon.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 10.2.2.1 包裹详情的集成测试。
 *
 * <p>真 PostgreSQL 16 + Testcontainers，<b>池容量 1</b>（只有复用同一条连接才能证明连接池
 * 归还后不串租户）。多租户三断言逐条实跑：两租户互不可见、伪造 tenant header 无效、
 * 连接池归还后不串租户。
 *
 * <p>夹具走<b>真实表</b>（V72 出库单 / V117 复核单 / V121 打包与包裹 / V73 打印），
 * 不 mock 任何一段：本模块的核心断言「parcel_item → 销售订单行的关联能不能归集到行」
 * 必须在真表、真触发器（V125 的守卫）上成立才有意义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ParcelDetailIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      List.of("warehouse.inventory.read", "warehouse.inventory.update");

  static final ZoneId WAREHOUSE_ZONE = ZoneId.of("Asia/Shanghai");

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-parcel-detail-" + tenant + "-" + ordinal).getBytes());
  }

  /** 跨服务的销售订单行 uuid（trade 服务 V99 的 sales_order_line）。warehouse 只存裸引用。 */
  static UUID salesLine(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-trade-sol-" + tenant + "-" + ordinal).getBytes());
  }

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(), grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role warehouse_app login password 'test-only' nosuperuser nobypassrls");
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
            byte[] body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, List.of(grant("ALL", List.of("warehouse.inventory.read")))));
      actors.put(
          "Bearer no-permission",
          actor(user, a, List.of(grant("ALL", List.of("warehouse.other.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "warehouse_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired ParcelDetailService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actorA;
  AccessService.Access actorB;

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate parcel_item_sales_order_line, packing_event, parcel_item, parcel,"
              + " packing_order, packing_material,"
              + " recount_event, recount_exception, recount_scan, recount_line, recount_session,"
              + " recount_rule, pick_wave_event, pick_wave_task, pick_wave, pick_wave_rule,"
              + " pick_task_serial, pick_task_pick, pick_task_exception, pick_task_line,"
              + " pick_task_event, pick_task, outbound_pick_request, outbound_order_event,"
              + " outbound_order_line, outbound_order, inventory_serial, stock_bucket,"
              + " inventory_balance, warehouse_location, inventory_lot,"
              + " inventory_quality_status, legal_entity_ref, sku_ref, warehouse cascade");
      s.execute("truncate label_print_log, print_job_item, print_job, print_target,"
          + " print_reprint_rule, print_device, print_template cascade");
      s.execute("truncate inventory_entry, inventory_transaction restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      access.run(
          actorFor(tenant),
          "warehouse.inventory.read",
          () -> {
            sql.execute(
                "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 1), tenant, "LE-1", "主体一");
            for (int i = 2; i <= 10; i++) {
              sql.execute(
                  "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                  idFor(tenant, i), tenant, "SKU-" + (i - 1), "商品" + (i - 1));
            }
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 9), tenant, "SELLABLE", "可售", "SELLABLE");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,kind,country_code,timezone,"
                    + "default_legal_entity_id,status) values(?,?,?,?,?,?,?,?,?)",
                idFor(tenant, 11), tenant, "WH-" + (tenant.equals(a) ? "A" : "B"), "本租仓库",
                "LOCAL", tenant.equals(a) ? "CN" : "US", WAREHOUSE_ZONE.getId(),
                idFor(tenant, 1), "ACTIVE");
            return null;
          });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  <T> T as(UUID tenant, java.util.function.Supplier<T> work) {
    return access.run(actorFor(tenant), "warehouse.inventory.read", work);
  }

  <T> T asWrite(UUID tenant, java.util.function.Supplier<T> work) {
    return access.run(actorFor(tenant), "warehouse.inventory.update", work);
  }

  UUID wh(UUID tenant) {
    return idFor(tenant, 11);
  }

  UUID sku(UUID tenant, int n) {
    return idFor(tenant, 1 + n);
  }

  // ================================================================ 夹具

  /** 一条完整的销售来源出库单 + 复核基线行（V72 / V117 的真实表与真实约束）。 */
  record Outbound(UUID orderId, UUID lineId) {}

  Outbound salesOutbound(UUID tenant, String orderCode, String orderNo, int qty) {
    return asWrite(
        tenant,
        () -> {
          UUID orderId = UUID.randomUUID();
          // 明细行只能在「待拣」状态下加（V72 的行守卫），随后再推到待打包 ——
          // 走真实的守卫顺序，不绕过状态机。
          sql.execute(
              "insert into outbound_order(id,tenant_id,order_code,source_type,source_external_ref,"
                  + "warehouse_id,status,created_by_user_id) values(?,?,?,'SALES',?,?,'PENDING_PICKING',?)",
              orderId, tenant, orderCode, orderNo, wh(tenant), user);
          UUID lineId = UUID.randomUUID();
          sql.execute(
              "insert into outbound_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,"
                  + "sku_name,quantity) values(?,?,?,1,?,?,?,cast(? as numeric))",
              lineId, tenant, orderId, sku(tenant, 1), "SKU-1", "商品1", String.valueOf(qty));
          // 复核单只能在出库单处于「待复核」时开单并判通过（V117 的开单守卫），
          // 所以这里先走到 PENDING_RECHECK 停下 —— 复核通过后由 passedRecount
          // 再推进到 PENDING_PACKING（V72 真实跃迁：PENDING_RECHECK → PENDING_PACKING）。
          for (var step : List.of("PICKING", "PENDING_RECHECK")) {
            sql.execute(
                "update outbound_order set status=? where tenant_id=? and id=?",
                step, tenant, orderId);
          }
          return new Outbound(orderId, lineId);
        });
  }

  /** 出库单推进到「待打包」（复核通过之后的下一步）。 */
  void advanceToPacking(UUID tenant, Outbound ob) {
    asWrite(
        tenant,
        () -> {
          sql.execute(
              "update outbound_order set status='PENDING_PACKING' where tenant_id=? and id=?",
              tenant, ob.orderId());
          return null;
        });
  }

  /** 一张 PASSED 的复核单（打包单只能从 PASSED 的复核单开，V121 §4）。 */
  UUID passedRecount(UUID tenant, Outbound ob, int qty) {
    return asWrite(
        tenant,
        () -> {
          // 复核单的 pick_task_id 是**真复合外键**：这里按 V72/V110 的真实链路
          // 建拣货交接请求 → 拣货任务，再开复核单（不塞随机 uuid 绕过外键）。
          UUID staging = UUID.randomUUID();
          // 库位编码在 (租户, 仓) 内唯一 —— 一次夹具可能建多个出库单，各用各的编码。
          sql.execute(
              "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,status,"
                  + "pickable) values(?,?,?,?,?,'SHIPPING','ACTIVE',true)",
              staging, tenant, wh(tenant), "STAGE-" + staging.toString().substring(0, 8), "打包区");

          UUID requestId = UUID.randomUUID();
          sql.execute(
              "insert into outbound_pick_request(id,tenant_id,order_id,line_count,"
                  + "total_piece_count,requested_by_user_id,requested_at) "
                  + "values(?,?,?,1,cast(? as numeric),?,?::timestamptz)",
              requestId, tenant, ob.orderId(), String.valueOf(qty), user, OffsetDateTime.now());

          UUID taskId = UUID.randomUUID();
          sql.execute(
              "insert into pick_task(id,tenant_id,task_code,outbound_order_id,pick_request_id,"
                  + "warehouse_id,staging_location_id,status,assignee_user_id,planned_line_count,"
                  + "planned_quantity,picked_line_count,picked_quantity,path_basis,started_at,"
                  + "completed_at,created_by) "
                  + "values(?,?,?,?,?,?,?,'COMPLETED',?,1,cast(? as numeric),1,cast(? as numeric),"
                  + "'测试路径',?::timestamptz,?::timestamptz,?)",
              taskId, tenant, "PT-" + UUID.randomUUID().toString().substring(0, 8),
              ob.orderId(), requestId, wh(tenant), staging, user, String.valueOf(qty),
              String.valueOf(qty), OffsetDateTime.now(), OffsetDateTime.now(), user);

          // 复核单先以 OPEN 建立（PASSED 的前置要求「每行都 MATCHED」，而基线行
          // 还没有，所以先开单），再建基线行并扫成 MATCHED，最后判通过。
          UUID sessionId = UUID.randomUUID();
          sql.execute(
              "insert into recount_session(id,tenant_id,session_code,outbound_order_id,"
                  + "pick_task_id,warehouse_id,status,verify_basis,outbound_status_before,"
                  + "baseline_line_count,baseline_quantity,reviewer_user_id,created_by) "
                  + "values(?,?,?,?,?,?,'OPEN','测试基线','PENDING_RECHECK',1,cast(? as numeric),?,?)",
              sessionId, tenant, "RS-" + UUID.randomUUID().toString().substring(0, 8),
              ob.orderId(), taskId, wh(tenant), String.valueOf(qty), user, user);
          UUID lineId = UUID.randomUUID();
          sql.execute(
              "insert into recount_line(id,tenant_id,session_id,outbound_line_id,line_no,sku_id,"
                  + "sku_code,sku_name,expected_quantity,picked_quantity,scanned_quantity,"
                  + "line_status) "
                  + "values(?,?,?,?,1,?,?,?,cast(? as numeric),cast(? as numeric),"
                  + "cast(? as numeric),'MATCHED')",
              lineId, tenant, sessionId, ob.lineId(), sku(tenant, 1), "SKU-1", "商品1",
              String.valueOf(qty), String.valueOf(qty), String.valueOf(qty));
          // 复核通过（V117 的守卫：每行都 MATCHED + 有复核人 + 完成时刻）。
          sql.execute(
              "update recount_session set status='PASSED',completed_at=?::timestamptz "
                  + "where tenant_id=? and id=?",
              OffsetDateTime.now(), tenant, sessionId);
          // 复核通过 ⇒ 出库单从「待复核」推进到「待打包」，打包单才能开（V121 的开单守卫）。
          advanceToPacking(tenant, ob);
          return lineId;
        });
  }

  record Pack(UUID packingId, UUID parcelId, UUID itemId, UUID recountLineId, UUID outboundLineId) {}

  /**
   * 建包材 + 打包单 + 包裹 + 明细项，最后判完成（走真实触发器：净重/体积由 DB 派生）。
   *
   * <p>刻意走完整的 OPEN → 装包裹 → 装明细 → 称重 → PACKED 链路，而不是直接插一行
   * PACKED：V121 的完成守卫（五件事缺一不可）正是本模块要读的那份凭据的成因，
   * 绕过去写出来的包裹不是真实存在的包裹。
   */
  Pack packed(UUID tenant, Outbound ob, UUID recountLineId, int qty, boolean measured) {
    return asWrite(
        tenant,
        () -> {
          UUID materialId = carton(tenant);

          UUID packingId = UUID.randomUUID();
          sql.execute(
              "insert into packing_order(id,tenant_id,packing_code,recount_session_id,"
                  + "outbound_order_id,warehouse_id,packing_basis,outbound_status_before,"
                  + "packer_user_id,status,created_by) "
                  + "select ?,?,?,rs.id,?,?,'测试基线','PENDING_PACKING',?,'OPEN',? "
                  + "from recount_session rs where rs.tenant_id=? and rs.id=?",
              packingId, tenant, "PK-" + UUID.randomUUID().toString().substring(0, 8),
              ob.orderId(), wh(tenant), user, user, tenant, recountSessionOf(tenant, recountLineId));

          UUID parcelId = UUID.randomUUID();
          sql.execute(
              "insert into parcel(id,tenant_id,packing_order_id,parcel_no,parcel_code,material_id,"
                  + "tare_weight_g,created_by) values(?,?,?,1,?,?,200,?)",
              parcelId, tenant, packingId, "PC-" + UUID.randomUUID().toString().substring(0, 8),
              materialId, user);

          UUID itemId = UUID.randomUUID();
          sql.execute(
              "insert into parcel_item(id,tenant_id,parcel_id,item_no,recount_line_id,"
                  + "outbound_line_id,sku_id,sku_code,sku_name,quantity,created_by) "
                  + "values(?,?,?,1,?,?,?,?,?,cast(? as numeric),?)",
              itemId, tenant, parcelId, recountLineId, ob.lineId(), sku(tenant, 1),
              "SKU-1", "商品1", String.valueOf(qty), user);

          if (measured) {
            sql.execute(
                "update parcel set outer_length_cm=30,outer_width_cm=20,outer_height_cm=10,"
                    + "measured_gross_weight_g=cast(? as numeric),measured_at=?::timestamptz,"
                    + "measured_by=? where tenant_id=? and id=?",
                String.valueOf(1200 + qty), OffsetDateTime.now(), user, tenant, parcelId);
            // 判完成（V121 §6.1：有箱、有明细、每箱都称过量过、每行实装恰好等于应发、有打包人）。
            sql.execute(
                "update packing_order set status='PACKED',completed_at=?::timestamptz "
                    + "where tenant_id=? and id=?",
                OffsetDateTime.now(), tenant, packingId);
          }
          // 未称重时打包单留在 OPEN —— 那正是「尚未实测」的真实形态。
          return new Pack(packingId, parcelId, itemId, recountLineId, ob.lineId());
        });
  }

  UUID recountSessionOf(UUID tenant, UUID recountLineId) {
    return sql.fetchOne(
            "select session_id from recount_line where tenant_id=? and id=?", tenant, recountLineId)
        .get("session_id", UUID.class);
  }

  /** 建立「明细项 → 销售订单行」关联（V125 的真表，走真触发器）。 */
  void link(UUID tenant, Pack pack, String orderNo, int lineNo, int salesQty, String basis) {
    asWrite(
        tenant,
        () -> {
          sql.execute(
              "insert into parcel_item_sales_order_line(id,tenant_id,parcel_item_id,parcel_id,"
                  + "sales_order_id,sales_order_line_id,sales_order_no,sales_order_line_no,sku_id,"
                  + "sku_code,allocated_quantity,sales_line_quantity,match_basis,created_by) "
                  + "select ?,?,?,?,?,?,?,?,?,?,pi.quantity,cast(? as numeric),?,? "
                  + "from parcel_item pi where pi.tenant_id=? and pi.id=?",
              UUID.randomUUID(), tenant, pack.itemId(), pack.parcelId(),
              UUID.randomUUID(), salesLine(tenant, lineNo), orderNo, lineNo,
              sku(tenant, 1), "SKU-1", String.valueOf(salesQty), basis, user, tenant, pack.itemId());
          return null;
        });
  }

  // ================================================================ 详情：读得到的东西

  @Test
  void detailReturnsMeasurementsItemsAndLabel() throws Exception {
    var ob = salesOutbound(a, "OB-1", "SO-1001", 3);
    var recountLine = passedRecount(a, ob, 3);
    var pack = packed(a, ob, recountLine, 3, true);
    // 面单必须在打包单**仍是 OPEN** 时记 —— V121 的守卫拒绝对已结束的打包单再改包裹
    // （凭据只能处置一次）。所以这里用一张未判完成的包裹来验证面单段。
    var labelOb = salesOutbound(a, "OB-1L", "SO-1001L", 3);
    var labelLine = passedRecount(a, labelOb, 3);
    var labelPack = packedOpen(a, labelOb, labelLine, 3);
    recordLabel(a, labelPack.parcelId(), "SF1234567890");

    http.perform(get("/warehouse/parcels/" + pack.parcelId())
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        // 实测重量体积：毛重 1203g、自重 200g ⇒ 净重 1003g（DB 触发器派生）。
        .andExpect(jsonPath("$.header.measuredGrossWeightG").value(1203.0))
        .andExpect(jsonPath("$.header.tareWeightG").value(200.0))
        .andExpect(jsonPath("$.header.netContentWeightG").value(1003.0))
        .andExpect(jsonPath("$.header.outerLengthCm").value(30.0))
        .andExpect(jsonPath("$.header.outerHeightCm").value(10.0))
        // 体积 = 30×20×10/10⁶ = 0.006000 m³（DB 触发器派生）。
        .andExpect(jsonPath("$.header.volumeCbm").value(0.006))
        .andExpect(jsonPath("$.header.measured").value(true))
        .andExpect(jsonPath("$.header.measuredAt").isNotEmpty())
        // SKU 与数量
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].skuCode").value("SKU-1"))
        .andExpect(jsonPath("$.items[0].quantity").value(3.0))
        .andExpect(jsonPath("$.items[0].outboundLineNo").value(1))
        // 操作入口：五段，指向真正做这件事的模块
        .andExpect(jsonPath("$.actions.length()").value(5))
        .andExpect(jsonPath("$.notes.length()").value(4));

    // 面单段（8.3.4.2 已交付的打印表，本服务真读得到）。
    String labelBody =
        http.perform(get("/warehouse/parcels/" + labelPack.parcelId())
                .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    var labelJson = new com.fasterxml.jackson.databind.ObjectMapper().readTree(labelBody);
    assertThat(labelJson.get("label").get("available").asBoolean()).isTrue();
    assertThat(labelJson.get("label").get("waybillNo").asText()).isEqualTo("SF1234567890");
    assertThat(labelJson.get("label").get("jobStatusLabel").asText()).isEqualTo("已完成");
  }

  /**
   * 一只标准纸箱目录项。material_code 在租户内唯一，所以一次夹具建多个包裹时复用同一条
   * （真实业务里包材目录是共享的，不是一个包裹一只箱子）。
   */
  UUID carton(UUID tenant) {
    return asWrite(
        tenant,
        () -> {
          var existing =
              sql.fetchOne(
                  "select id from packing_material where tenant_id"
                      + " = current_setting('app.current_tenant', true)::uuid and material_code='CTN-1'");
          if (existing != null) return existing.get("id", UUID.class);
          UUID id = UUID.randomUUID();
          sql.execute(
              "insert into packing_material(id,tenant_id,material_code,name,material_role,"
                  + "material_type,outer_length_cm,outer_width_cm,outer_height_cm,tare_weight_g,"
                  + "max_gross_weight_g,created_by) values(?,?,?,?,'PACKAGING','CARTON',"
                  + "30,20,10,200,50000,?)",
              id, tenant, "CTN-1", "标准纸箱", user);
          return id;
        });
  }

  /** 建一个停在 OPEN 的包裹（已称重但打包单未判完成），供「打包单仍可改」的用例使用。 */
  Pack packedOpen(UUID tenant, Outbound ob, UUID recountLineId, int qty) {
    return asWrite(
        tenant,
        () -> {
          UUID materialId = carton(tenant);
          UUID packingId = UUID.randomUUID();
          sql.execute(
              "insert into packing_order(id,tenant_id,packing_code,recount_session_id,"
                  + "outbound_order_id,warehouse_id,packing_basis,outbound_status_before,"
                  + "packer_user_id,status,created_by) "
                  + "select ?,?,?,rs.id,?,?,'测试基线','PENDING_PACKING',?,'OPEN',? "
                  + "from recount_session rs where rs.tenant_id=? and rs.id=?",
              packingId, tenant, "PK-" + UUID.randomUUID().toString().substring(0, 8),
              ob.orderId(), wh(tenant), user, user, tenant, recountSessionOf(tenant, recountLineId));
          UUID parcelId = UUID.randomUUID();
          sql.execute(
              "insert into parcel(id,tenant_id,packing_order_id,parcel_no,parcel_code,material_id,"
                  + "tare_weight_g,created_by) values(?,?,?,1,?,?,200,?)",
              parcelId, tenant, packingId, "PC-" + UUID.randomUUID().toString().substring(0, 8),
              materialId, user);
          UUID itemId = UUID.randomUUID();
          sql.execute(
              "insert into parcel_item(id,tenant_id,parcel_id,item_no,recount_line_id,"
                  + "outbound_line_id,sku_id,sku_code,sku_name,quantity,created_by) "
                  + "values(?,?,?,1,?,?,?,?,?,cast(? as numeric),?)",
              itemId, tenant, parcelId, recountLineId, ob.lineId(), sku(tenant, 1),
              "SKU-1", "商品1", String.valueOf(qty), user);
          return new Pack(packingId, parcelId, itemId, recountLineId, ob.lineId());
        });
  }

  @Test
  void unmeasuredParcelReportsNullNotZero() {
    var ob = salesOutbound(a, "OB-NM", "SO-2002", 2);
    var recountLine = passedRecount(a, ob, 2);
    var pack = packed(a, ob, recountLine, 2, false);

    var d = as(a, () -> service.detail(pack.parcelId()));
    // 未称重 = null，**不是 0** —— 0 会被读成「量出来是 0 厘米 / 0 克」。
    assertThat(d.header().measured()).isFalse();
    assertThat(d.header().measuredGrossWeightG()).isNull();
    assertThat(d.header().netContentWeightG()).isNull();
    assertThat(d.header().volumeCbm()).isNull();
    assertThat(d.header().outerLengthCm()).isNull();
    assertThat(d.header().measuredAt()).isNull();
  }

  @Test
  void parcelWithoutLabelIsReportedAsNotPrintedNotUnavailable() {
    var ob = salesOutbound(a, "OB-NL", "SO-2003", 1);
    var recountLine = passedRecount(a, ob, 1);
    var pack = packed(a, ob, recountLine, 1, true);

    var d = as(a, () -> service.detail(pack.parcelId()));
    // 「这一箱还没印过面单」是**事实**，不是「面单能力不可用」。
    assertThat(d.label().available()).isFalse();
    assertThat(d.label().printJobId()).isNull();
    assertThat(d.label().note()).contains("还没有记过面单打印");
  }

  // ================================================================ 蓝图点名的那句

  @Test
  void linkedItemsMakeFreightAllocatableToLine() {
    var ob = salesOutbound(a, "OB-L1", "SO-3001", 4);
    var recountLine = passedRecount(a, ob, 4);
    var pack = packed(a, ob, recountLine, 4, true);
    link(a, pack, "SO-3001", 1, 4, "SKU_UNIQUE_ALIGNED");

    var d = as(a, () -> service.detail(pack.parcelId()));
    // 蓝图：parcel_item 与订单行的对应关系建起来了 ⇒ 运费能归到**行**。
    assertThat(d.costAllocation().allocatableToLine()).isTrue();
    assertThat(d.costAllocation().fallbackLevel()).isEqualTo("LINE");
    assertThat(d.costAllocation().matchedQuantity()).isEqualByComparingTo("4.000000");
    assertThat(d.costAllocation().unmatchedQuantity()).isEqualByComparingTo("0.000000");
    assertThat(d.costAllocation().linkedItemCount()).isEqualTo(1);
    assertThat(d.costAllocation().distinctSalesOrderLineCount()).isEqualTo(1);
    // 归集分母 = 本箱分给该行的件数 = 明细项实装件数。
    var link = d.items().get(0).salesOrderLines().get(0);
    assertThat(link.allocatedQuantity()).isEqualByComparingTo("4.000000");
    assertThat(link.salesLineQuantity()).isEqualByComparingTo("4.000000");
    assertThat(link.salesOrderNo()).isEqualTo("SO-3001");
    assertThat(link.salesOrderLineNo()).isEqualTo(1);
    assertThat(link.strongMatch()).isTrue();
    // 恒等式：已关联 + 未关联 == 合计
    assertThat(
            d.costAllocation()
                .matchedQuantity()
                .add(d.costAllocation().unmatchedQuantity())
                .compareTo(d.costAllocation().totalQuantity()))
        .isZero();
  }

  @Test
  void unlinkedItemsFallBackToOrderLevelAndNameThem() {
    var ob = salesOutbound(a, "OB-U1", "SO-3002", 5);
    var recountLine = passedRecount(a, ob, 5);
    var pack = packed(a, ob, recountLine, 5, true);
    // **不**建关联。

    var d = as(a, () -> service.detail(pack.parcelId()));
    // 蓝图：缺了就只能算到订单级 —— 且必须如实说出来，不能静默按订单摊。
    assertThat(d.costAllocation().allocatableToLine()).isFalse();
    assertThat(d.costAllocation().fallbackLevel()).isEqualTo("ORDER");
    assertThat(d.costAllocation().fallbackReason()).contains("只能归到订单级");
    assertThat(d.costAllocation().unlinkedItemCount()).isEqualTo(1);
    assertThat(d.costAllocation().matchedQuantity()).isEqualByComparingTo("0.000000");
    assertThat(d.costAllocation().unmatchedQuantity()).isEqualByComparingTo("5.000000");
    // 未关联的那一条要能追到具体是哪件货。
    assertThat(d.costAllocation().unmatchedItems()).hasSize(1);
    assertThat(d.costAllocation().unmatchedItems().get(0).skuCode()).isEqualTo("SKU-1");
    assertThat(d.costAllocation().unmatchedItems().get(0).quantity()).isEqualByComparingTo("5.000000");
    // 明细项自己也要说清为什么没有订单行。
    assertThat(d.items().get(0).salesOrderLines()).isEmpty();
    assertThat(d.items().get(0).orderLineNote()).contains("还没有关联到销售订单行");
  }

  @Test
  void weakMatchIsLabelledSoFinanceCanReviewIt() {
    var ob = salesOutbound(a, "OB-W1", "SO-3003", 2);
    var recountLine = passedRecount(a, ob, 2);
    var pack = packed(a, ob, recountLine, 2, true);
    link(a, pack, "SO-3003", 2, 2, "SKU_NAME_FALLBACK");

    var d = as(a, () -> service.detail(pack.parcelId()));
    var link = d.items().get(0).salesOrderLines().get(0);
    // 弱关联必须显式标注：财务据此决定要不要追加人工复核，而不是当成等价匹配。
    assertThat(link.matchBasis()).isEqualTo("SKU_NAME_FALLBACK");
    assertThat(link.strongMatch()).isFalse();
    assertThat(link.matchBasisLabel()).contains("弱关联");
    // 弱关联**仍然算**归集到行 —— 它已经建立起来了，只是强度不同。
    assertThat(d.costAllocation().allocatableToLine()).isTrue();
  }

  // ================================================================ V125 的 DB 级守卫

  @Test
  void allocatedQuantityMustEqualPackedQuantity() {
    var ob = salesOutbound(a, "OB-G1", "SO-4001", 3);
    var recountLine = passedRecount(a, ob, 3);
    var pack = packed(a, ob, recountLine, 3, true);

    // 分母编错 = 运费按错的权重摊到每一行，V125 的触发器当场拒。
    var thrown =
        catchThrowable(
            () ->
                asWrite(
                    a,
                    () -> {
                      sql.execute(
                          "insert into parcel_item_sales_order_line(id,tenant_id,parcel_item_id,"
                              + "parcel_id,sales_order_id,sales_order_line_id,sales_order_no,"
                              + "sales_order_line_no,sku_id,sku_code,allocated_quantity,"
                              + "sales_line_quantity,match_basis,created_by) "
                              + "values(?,?,?,?,?,?,?,1,?,'SKU-1',cast(? as numeric),"
                              + "cast(? as numeric),'SKU_UNIQUE_ALIGNED',?)",
                          UUID.randomUUID(), a, pack.itemId(), pack.parcelId(), UUID.randomUUID(),
                          salesLine(a, 1), "SO-4001", sku(a, 1), "9", "3", user);
                      return null;
                    }));
    assertThat(thrown).isNotNull();
    assertThat(rootMessage(thrown)).contains("分母编错");
  }

  @Test
  void linkMustNotPointAtAnotherSku() {
    var ob = salesOutbound(a, "OB-G2", "SO-4002", 2);
    var recountLine = passedRecount(a, ob, 2);
    var pack = packed(a, ob, recountLine, 2, true);

    // 拿着 A 件货的号去指 B 行的货 ⇒ 运费摊到不相干的行上，比不摊更糟。
    var thrown =
        catchThrowable(
            () ->
                asWrite(
                    a,
                    () -> {
                      sql.execute(
                          "insert into parcel_item_sales_order_line(id,tenant_id,parcel_item_id,"
                              + "parcel_id,sales_order_id,sales_order_line_id,sales_order_no,"
                              + "sales_order_line_no,sku_id,sku_code,allocated_quantity,"
                              + "sales_line_quantity,match_basis,created_by) "
                              + "values(?,?,?,?,?,?,?,1,?,'SKU-9',cast(? as numeric),"
                              + "cast(? as numeric),'SKU_UNIQUE_ALIGNED',?)",
                          UUID.randomUUID(), a, pack.itemId(), pack.parcelId(), UUID.randomUUID(),
                          salesLine(a, 1), "SO-4002", sku(a, 9), "2", "2", user);
                      return null;
                    }));
    assertThat(thrown).isNotNull();
    assertThat(rootMessage(thrown)).contains("不是同一个商品");
  }

  @Test
  void oneParcelItemCannotBeLinkedToTwoSalesOrderLines() {
    var ob = salesOutbound(a, "OB-G3", "SO-4003", 2);
    var recountLine = passedRecount(a, ob, 2);
    var pack = packed(a, ob, recountLine, 2, true);
    link(a, pack, "SO-4003", 1, 2, "SKU_UNIQUE_ALIGNED");

    // 同一件货同时记在两个行上 ⇒ 这一件的运费被**重复**摊一次（本表最贵的错）。
    var thrown =
        catchThrowable(
            () ->
                asWrite(
                    a,
                    () -> {
                      sql.execute(
                          "insert into parcel_item_sales_order_line(id,tenant_id,parcel_item_id,"
                              + "parcel_id,sales_order_id,sales_order_line_id,sales_order_no,"
                              + "sales_order_line_no,sku_id,sku_code,allocated_quantity,"
                              + "sales_line_quantity,match_basis,created_by) "
                              + "values(?,?,?,?,?,?,?,9,?,'SKU-1',cast(? as numeric),"
                              + "cast(? as numeric),'SKU_UNIQUE_ALIGNED',?)",
                          UUID.randomUUID(), a, pack.itemId(), pack.parcelId(), UUID.randomUUID(),
                          salesLine(a, 9), "SO-9999", sku(a, 1), "2", "2", user);
                      return null;
                    }));
    assertThat(thrown).isNotNull();
  }

  @Test
  void linkIsReadOnlyFactOnceWritten() {
    var ob = salesOutbound(a, "OB-G4", "SO-4004", 2);
    var recountLine = passedRecount(a, ob, 2);
    var pack = packed(a, ob, recountLine, 2, true);
    link(a, pack, "SO-4004", 1, 2, "SKU_UNIQUE_ALIGNED");

    // 第一道：V125 **刻意没给 warehouse_app UPDATE 权限**，应用角色连改都改不了。
    var denied =
        catchThrowable(
            () ->
                asWrite(
                    a,
                    () -> {
                      sql.execute(
                          "update parcel_item_sales_order_line set match_basis='SKU_NAME_FALLBACK'"
                              + " where tenant_id=? and parcel_id=?",
                          a, pack.parcelId());
                      return null;
                    }));
    assertThat(denied).isNotNull();
    assertThat(rootMessage(denied)).contains("permission denied");

    // 第二道：即使绕过 GRANT（用超级用户直连），V125 的只读触发器照样拒 ——
    // 对齐口径与归集分母都可能成为举证材料，写下即凭据。
    var guarded =
        catchThrowable(
            () -> {
              try (var c =
                      DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                  var s = c.createStatement()) {
                // 绕过 warehouse_app 的 GRANT 限制，用超级用户直连复现「即使拿到写权限也改不动」。
                s.execute("set app.current_tenant = '" + a + "'");
                s.execute(
                    "update parcel_item_sales_order_line set match_basis='SKU_NAME_FALLBACK'"
                        + " where tenant_id='" + a + "' and parcel_id='" + pack.parcelId() + "'");
              }
            });
    assertThat(guarded).isNotNull();
    assertThat(rootMessage(guarded)).contains("事实凭据");
  }

  // ================================================================ 多租户三断言

  @Test
  void tenantsCannotSeeEachOthersParcels() throws Exception {
    var obA = salesOutbound(a, "OB-TA", "SO-TA", 2);
    var lineA = passedRecount(a, obA, 2);
    var packA = packed(a, obA, lineA, 2, true);
    link(a, packA, "SO-TA", 1, 2, "SKU_UNIQUE_ALIGNED");

    var obB = salesOutbound(b, "OB-TB", "SO-TB", 3);
    var lineB = passedRecount(b, obB, 3);
    var packB = packed(b, obB, lineB, 3, true);
    link(b, packB, "SO-TB", 1, 3, "SKU_UNIQUE_ALIGNED");

    // A 看得见自己的，且**看不到** B 的。
    assertThat(as(a, () -> service.detail(packA.parcelId())).header().parcelCode()).isNotBlank();
    var thrown = catchThrowable(() -> as(b, () -> service.detail(packA.parcelId())));
    assertThat(thrown).isNotNull();
    // 跨租户与不存在**同一个出口**：不给「拿 B 的 id 探测存在性」留信息面。
    assertThat(rootMessage(thrown)).contains("不存在或不属于当前租户");
    // 反向同理。
    assertThat(as(b, () -> service.detail(packB.parcelId())).costAllocation().matchedQuantity())
        .isEqualByComparingTo("3.000000");
    assertThat(catchThrowable(() -> as(a, () -> service.detail(packB.parcelId())))).isNotNull();
  }

  @Test
  void forgedTenantHeaderIsIgnored() throws Exception {
    var obA = salesOutbound(a, "OB-FT", "SO-FT", 1);
    var lineA = passedRecount(a, obA, 1);
    var packA = packed(a, obA, lineA, 1, true);

    // 伪造 X-Tenant-Id 头：代理忽略它，身份由 Authorization 解析。
    // 连身份头也伪造时结果是 401，而不是「换成 B 的租户」。
    var forged =
        http.perform(
                get("/warehouse/parcels/" + packA.parcelId())
                    .header("Authorization", "Bearer forged")
                    .header("X-Tenant-Id", b.toString()))
            .andReturn()
            .getResponse();
    assertThat(forged.getStatus()).isEqualTo(401);

    // 带上真实身份但伪造租户头：看到的仍然是 A 自己那一箱（不是 B 的，也不是 404）。
    http.perform(
            get("/warehouse/parcels/" + packA.parcelId())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.header.parcelId").value(packA.parcelId().toString()));

    // 没有权限的令牌 403（租户对了也不行）。
    http.perform(
            get("/warehouse/parcels/" + packA.parcelId())
                .header("Authorization", "Bearer no-permission"))
        .andExpect(status().isForbidden());
  }

  @Test
  void pooledConnectionDoesNotLeakTenantAcrossReturns() {
    var obA = salesOutbound(a, "OB-POOL-A", "SO-POOL-A", 2);
    var lineA = passedRecount(a, obA, 2);
    var packA = packed(a, obA, lineA, 2, true);
    link(a, packA, "SO-POOL-A", 1, 2, "SKU_UNIQUE_ALIGNED");

    var obB = salesOutbound(b, "OB-POOL-B", "SO-POOL-B", 5);
    var lineB = passedRecount(b, obB, 5);
    var packB = packed(b, obB, lineB, 5, true);
    link(b, packB, "SO-POOL-B", 1, 5, "SKU_UNIQUE_ALIGNED");

    // A → B → A 三轮：连接池只有 1 条连接，每次归还后都可能被下一个人拿到。
    for (int round = 0; round < 3; round++) {
      assertThat(as(a, () -> service.detail(packA.parcelId())).costAllocation().matchedQuantity())
          .isEqualByComparingTo("2.000000");
      assertThat(as(b, () -> service.detail(packB.parcelId())).costAllocation().matchedQuantity())
          .isEqualByComparingTo("5.000000");
      // 归集分母不得跨租户串：A 那一箱永远只看到自己那 2 件。
      assertThat(
              as(a, () -> service.detail(packA.parcelId())).items().get(0).salesOrderLines().get(0)
                  .allocatedQuantity())
          .isEqualByComparingTo("2.000000");
      assertThat(
              as(b, () -> service.detail(packB.parcelId())).items().get(0).salesOrderLines().get(0)
                  .allocatedQuantity())
          .isEqualByComparingTo("5.000000");
    }
  }

  // ================================================================ 能力声明

  @Test
  void capabilitiesDeclareUnavailableSectionsHonestly() throws Exception {
    var body =
        http.perform(get("/warehouse/parcels/capabilities").header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.costAllocationPolicy.lineLevelSupported").value(true))
            .andExpect(jsonPath("$.costAllocationPolicy.basis")
                .value("PARCEL_ITEM_TO_SALES_ORDER_LINE"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    var caps = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
    var sections = caps.get("sections");

    // 轨迹与交运信息**不存在于本服务**：available=false + ownerModule，
    // 不用空列表冒充「这一票没有轨迹」。
    assertThat(section(sections, "物流轨迹").get("available").asBoolean()).isFalse();
    assertThat(section(sections, "物流轨迹").get("ownerModule").asText()).isEqualTo("10.3.1.1");
    assertThat(section(sections, "交运信息").get("available").asBoolean()).isFalse();
    assertThat(section(sections, "交运信息").get("ownerModule").asText()).isEqualTo("8.3.4.1");
    assertThat(section(sections, "运费与体积重").get("available").asBoolean()).isFalse();
    // 下游模块逐个点名，下游才能各接各的。
    assertThat(caps.get("downstreamModules").toString()).contains("10.3.1.1", "12 财务");
  }

  /** 按 label 取一段能力声明。 */
  static com.fasterxml.jackson.databind.JsonNode section(
      com.fasterxml.jackson.databind.JsonNode sections, String label) {
    for (var n : sections) {
      if (label.equals(n.get("label").asText())) return n;
    }
    throw new AssertionError("能力声明里没有这一段：" + label + "，实际有 " + sections);
  }

  @Test
  void detailOfAnotherTenantReturnsSameNotFoundAsMissing() throws Exception {
    var obA = salesOutbound(a, "OB-404", "SO-404", 1);
    var lineA = passedRecount(a, obA, 1);
    var packA = packed(a, obA, lineA, 1, true);

    // 别人的真实包裹 与 根本不存在的 uuid ⇒ **同一个 404**，不留存在性探测面。
    var crossTenant =
        http.perform(
                get("/warehouse/parcels/" + packA.parcelId())
                    .header("Authorization", "Bearer tenant-b"))
            .andReturn()
            .getResponse();
    var missing =
        http.perform(
                get("/warehouse/parcels/" + UUID.randomUUID())
                    .header("Authorization", "Bearer tenant-b"))
            .andReturn()
            .getResponse();

    assertThat(crossTenant.getStatus()).isEqualTo(404);
    assertThat(missing.getStatus()).isEqualTo(404);
    assertThat(crossTenant.getContentAsString()).isEqualTo(missing.getContentAsString());
  }

  // ================================================================ 内部

  /** 打印一次面单（走 V73 的真实表），让 label 段有真数据可读。 */
  void recordLabel(UUID tenant, UUID parcelId, String waybill) {
    asWrite(
        tenant,
        () -> {
          UUID templateId = UUID.randomUUID();
          sql.execute(
              "insert into print_template(id,tenant_id,code,name,object_type,paper_size,created_by)"
                  + " values(?,?,?,?,'SHIPPING_LABEL','4X6',?)",
              templateId, tenant, "TPL-1", "面单模板", user);
          UUID targetId = UUID.randomUUID();
          sql.execute(
              "insert into print_target(id,tenant_id,object_type,external_ref,waybill_no,"
                  + "created_at) values(?,?,'SHIPPING_LABEL',?,?,now())",
              targetId, tenant, waybill, waybill);
          UUID jobId = UUID.randomUUID();
          sql.execute(
              "insert into print_job(id,tenant_id,job_no,template_id,status,copies,target_count,"
                  + "label_count,finished_at,created_by) values(?,?,?,?,'COMPLETED',1,1,1,"
                  + "now(),?)",
              jobId, tenant, "PJ-" + UUID.randomUUID().toString().substring(0, 8), templateId, user);
          sql.execute(
              "insert into print_job_item(id,tenant_id,job_id,target_id,line_no,object_type,"
                  + "external_ref,print_count_at_render,rendered_content,content_checksum)"
                  + " values(?,?,?,?,1,'SHIPPING_LABEL',?,1,'ZPL-TEST','sum')",
              UUID.randomUUID(), tenant, jobId, targetId, waybill);
          sql.execute(
              "update parcel set label_print_job_id=?,label_print_count=1,"
                  + "label_printed_at=now() where tenant_id=? and id=?",
              jobId, tenant, parcelId);
          return null;
        });
  }

  /** 取最内层异常消息：PG 触发器里的中文文案包在 PSQLException 的 cause 链上。 */
  static String rootMessage(Throwable t) {
    Throwable cur = t;
    while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
    return cur.getMessage() == null ? "" : cur.getMessage();
  }

  /** 未使用的常量守门：确保 ZERO 口径不与断言里的 BigDecimal 混淆。 */
  @Test
  void scaleIsSixDigitsAndNonFloating() {
    var ob = salesOutbound(a, "OB-SC", "SO-SC", 1);
    var line = passedRecount(a, ob, 1);
    var pack = packed(a, ob, line, 1, true);
    link(a, pack, "SO-SC", 1, 1, "SKU_UNIQUE_ALIGNED");

    BigDecimal qty = as(a, () -> service.detail(pack.parcelId())).items().get(0).quantity();
    // 数量一律 NUMERIC(19,6)：scale 必须是 6，不允许 double 混进来。
    assertThat(qty.scale()).isEqualTo(6);
    assertThat(new BigDecimal(qty.toPlainString())).isEqualByComparingTo("1.000000");
  }
}
