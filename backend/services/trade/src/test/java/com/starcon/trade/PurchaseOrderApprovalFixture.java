package com.starcon.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 6.3.1.3 采购单审批的<b>共享底座</b>。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身</b>。
 *
 * <p>本模块**不调任何跨服务写端点** ⇒ 底座只需要一个 identity 替身（回答
 * {@code GET /internal/access}：「你是谁、在哪个租户、有哪些权限与范围」）。
 * 租户隔离仍由 trade 侧的 RLS 在数据库层强制 —— 替身**不决定**任何业务结论。
 *
 * <p><b>上游事实怎么来的</b>：采购单（6.3.1.1 的 {@code purchase_order} / {@code purchase_order_line}）
 * 与 6.3.1.2 的提交快照**直接种进它们自己的表** —— 本模块是它们的下游消费者，
 * 读它们的事实表正是本模块要证明的事；种数据用迁移用户（超级用户），
 * 生产运行角色 {@code trade_app} 对那些表只有上游授予的权限。
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class PurchaseOrderApprovalFixture {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenantA = UUID.randomUUID(), tenantB = UUID.randomUUID();

  /** A 租户：采购员（提交人）、两个审批人、另一个部门的成员。 */
  static final UUID buyerA = UUID.randomUUID();

  static final UUID approverA = UUID.randomUUID();
  static final UUID approverA2 = UUID.randomUUID();
  static final UUID juniorApproverA = UUID.randomUUID();
  /** 层级够、但**金额授权上限很低**的审批人（用来证明「授权区间」真的在拦）。 */
  static final UUID bandApproverA = UUID.randomUUID();

  /** 有权限但**不在册审批人**里的用户。 */
  static final UUID strangerA = UUID.randomUUID();

  static final UUID departmentA = UUID.randomUUID();

  /** B 租户：采购员与审批人。 */
  static final UUID buyerB = UUID.randomUUID();
  static final UUID approverB = UUID.randomUUID();

  /** 供应商与目标仓（采购单上的逻辑引用，不建跨库 FK）。 */
  static final UUID supplierRefId = UUID.randomUUID();
  static final UUID warehouseId = UUID.randomUUID();

  static HttpServer identity;
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 本模块的四个权限码（读 / 配置 / 审批 / 财务确认）。 */
  static final List<String> perms =
      List.of(
          "trade.purchase.read",
          "trade.purchase.update",
          "trade.purchase.approve",
          "trade.purchase.finance");

  static AccessService.Access actor(UUID uid, UUID tenant, UUID dept, String scope) {
    var grants = List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", scope, perms));
    return new AccessService.Access(uid, tenant, "Test", UUID.randomUUID(), dept, perms, grants);
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，隔离断言全部变成空断言。
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
      }

      // ---- identity：只回答「你是谁 / 哪个租户 / 什么权限与范围」 ----
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
      identity.start();

      actors.put("Bearer buyer-a", actor(buyerA, tenantA, departmentA, "ALL"));
      actors.put("Bearer approver-a", actor(approverA, tenantA, departmentA, "ALL"));
      actors.put("Bearer approver-a2", actor(approverA2, tenantA, departmentA, "ALL"));
      actors.put("Bearer junior-a", actor(juniorApproverA, tenantA, departmentA, "ALL"));
      actors.put("Bearer band-a", actor(bandApproverA, tenantA, departmentA, "ALL"));
      actors.put("Bearer stranger-a", actor(strangerA, tenantA, departmentA, "ALL"));
      actors.put("Bearer buyer-b", actor(buyerB, tenantB, null, "ALL"));
      actors.put("Bearer approver-b", actor(approverB, tenantB, null, "ALL"));
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
    // ⚠️ **连接池固定为 1**：池大于 1 时每次可能拿到不同连接，
    // 「归还后不串租户」可能只是碰巧换了条干净连接。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @AfterAll
  static void noteSharedResources() {
    // 🔴 **刻意不在这里停容器与替身服务**：它们被两个测试类共享，
    // 先结束的测试类把共享资源停掉会让后面的类全部失败。
    // 改由 JVM 退出时由 Testcontainers 的 Ryuk 统一回收。
  }

  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 工具

  /** 用迁移用户（超级用户）连接：绕开 RLS，只用来清表与种上游事实。 */
  static Connection admin() throws Exception {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }

  /**
   * 用**运行角色**（trade_app）连接：用来证明「留痕表对运行角色是追加式的」。
   *
   * <p>⚠️ 它与 {@link #admin()} 的差别正是要断言的东西：运行角色**既没有** UPDATE/DELETE
   * 权限，**也**躲不开追加式触发器。
   */
  static Connection runtimeConnection() throws Exception {
    return DriverManager.getConnection(pg.getJdbcUrl(), "trade_app", "test-only");
  }

  /** 清掉本模块的表与上游事实表。 */
  static void truncateAll() throws Exception {
    try (var c = admin();
        var s = c.createStatement()) {
      s.execute(
          "truncate purchase_order_approval_record,purchase_order_approval_case,"
              + " purchase_order_payable,purchase_order_receipt_release,"
              + " purchase_order_approval_approver,purchase_order_approval_buyer,"
              + " purchase_order_approval_rule,"
              + " purchase_order_event,purchase_order_line,purchase_order cascade");
    }
  }

  /** 以某个身份跑一段工作（真实绑租户 ⇒ 真实 RLS）。 */
  <T> T as(String bearer, java.util.function.Supplier<T> work) {
    return access.run(actors.get(bearer), "trade.purchase.read", work);
  }

  /** 以某个身份跑一段工作（写权限码，真实绑租户）。 */
  <T> T asWrite(String bearer, java.util.function.Supplier<T> work) {
    return access.run(actors.get(bearer), "trade.purchase.update", work);
  }

  /** 以某个身份跑一段工作（审批权限码，真实绑租户）。 */
  <T> T asApprove(String bearer, java.util.function.Supplier<T> work) {
    return access.run(actors.get(bearer), "trade.purchase.approve", work);
  }

  // ================================================================ 数据准备

  /**
   * 种一张**已提交**（PENDING_APPROVAL）的采购单。
   *
   * <p>⚠️ 提交这一步是<b>真的</b>走 {@code update purchase_order set status='PENDING_APPROVAL'}
   * —— 它会触发 V262 的 {@code purchase_order_approval_case_after_submit}，
   * 于是审批链是**DB 触发器**生成的（这正是要证明的那条链路）。
   */
  static UUID seedSubmittedOrder(
      UUID tenant, UUID owner, String orderNo, String currency, String quantity, String unitPrice)
      throws Exception {
    UUID id = seedDraftOrder(tenant, owner, orderNo, currency, quantity, unitPrice);
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "update purchase_order set status='PENDING_APPROVAL',version=version+1"
                    + " where id=?::uuid and status='DRAFT'")) {
      ps.setObject(1, id);
      ps.executeUpdate();
    }
    return id;
  }

  /** 种一张**草稿**（DRAFT）采购单（头 + 一行；金额与行数由 DB 触发器算）。 */
  static UUID seedDraftOrder(
      UUID tenant, UUID owner, String orderNo, String currency, String quantity, String unitPrice)
      throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into purchase_order(id,tenant_id,order_no,source_type,source_request_no,"
                    + " supplier_ref_id,supplier_code,supplier_name,legal_entity_id,legal_entity_code,"
                    + " legal_entity_name,currency,status,promised_arrival_date,target_warehouse_id,"
                    + " target_warehouse_code,target_warehouse_name,owner_user_id,owner_department_id,"
                    + " created_by,created_at,updated_at)"
                    + " values (?::uuid,?::uuid,?::varchar,'MANUAL','',"
                    + " ?::uuid,?::varchar,?::varchar,?::uuid,'US-LLC','美国子公司',?::varchar,'DRAFT',"
                    + " ?::date,?::uuid,'WH-LA-01','洛杉矶仓',?::uuid,?::uuid,"
                    + " ?::uuid,now(),now())")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setString(3, orderNo);
      ps.setObject(4, supplierRefId);
      ps.setString(5, "SUP-TEST");
      ps.setString(6, "测试供应商");
      ps.setObject(7, UUID.randomUUID());
      ps.setString(8, currency);
      ps.setDate(9, java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC).plusDays(20)));
      ps.setObject(10, warehouseId);
      ps.setObject(11, owner);
      ps.setObject(12, departmentA);
      ps.setObject(13, owner);
      ps.executeUpdate();
    }
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into purchase_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                    + " quantity,unit_code,unit_price,price_source,created_at,updated_at)"
                    + " values (?::uuid,?::uuid,?::uuid,1,?::uuid,?::varchar,?::varchar,?::numeric,'',"
                    + " ?::numeric,'MANUAL',now(),now())")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setObject(2, tenant);
      ps.setObject(3, id);
      ps.setObject(4, UUID.nameUUIDFromBytes(("SKU-" + orderNo).getBytes()));
      ps.setString(5, "SKU-" + orderNo);
      ps.setString(6, "SKU-" + orderNo + " 名称");
      ps.setBigDecimal(7, new BigDecimal(quantity));
      ps.setBigDecimal(8, new BigDecimal(unitPrice));
      ps.executeUpdate();
    }
    return id;
  }

  /** 种一条审批规则。 */
  static UUID seedRule(
      UUID tenant,
      String ruleNo,
      String buyerLevel,
      String currency,
      String minAmount,
      String maxAmount,
      String tier,
      int approvals)
      throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into purchase_order_approval_rule(id,tenant_id,rule_no,rule_name,buyer_level,"
                    + " currency,min_amount,max_amount,required_tier,required_approvals,priority,status,"
                    + " created_by,created_at,updated_at,version)"
                    + " values (?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::varchar,"
                    + " ?::numeric,?::numeric,?::varchar,?::integer,100,'ACTIVE',?::uuid,now(),now(),0)")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setString(3, ruleNo);
      ps.setString(4, "规则-" + ruleNo);
      ps.setString(5, buyerLevel);
      ps.setString(6, currency);
      ps.setBigDecimal(7, new BigDecimal(minAmount));
      if (maxAmount == null) {
        ps.setNull(8, java.sql.Types.NUMERIC);
      } else {
        ps.setBigDecimal(8, new BigDecimal(maxAmount));
      }
      ps.setString(9, tier);
      ps.setInt(10, approvals);
      ps.setObject(11, buyerA);
      ps.executeUpdate();
    }
    return id;
  }

  /**
   * 种一个采购员分级（<b>幂等</b>：同一个人再种一次就是改他的分级 ——
   * 一个租户里一个人只能有一个分级，重复种会撞唯一键）。
   */
  static UUID seedBuyer(UUID tenant, UUID userId, String name, String level) throws Exception {
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "update purchase_order_approval_buyer set buyer_level=?::varchar,user_name=?::varchar,"
                    + " updated_at=now() where tenant_id=?::uuid and user_id=?::uuid")) {
      ps.setString(1, level);
      ps.setString(2, name);
      ps.setObject(3, tenant);
      ps.setObject(4, userId);
      if (ps.executeUpdate() > 0) {
        return null;
      }
    }
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into purchase_order_approval_buyer(id,tenant_id,user_id,user_name,buyer_level,"
                    + " status,created_by,created_at,updated_at,version)"
                    + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,'ACTIVE',?::uuid,now(),now(),0)")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setObject(3, userId);
      ps.setString(4, name);
      ps.setString(5, level);
      ps.setObject(6, userId);
      ps.executeUpdate();
    }
    return id;
  }

  /** 种一个审批人（层级 + 金额授权区间）。 */
  static UUID seedApprover(
      UUID tenant, UUID userId, String name, String tier, String currency, String min, String max)
      throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into purchase_order_approval_approver(id,tenant_id,user_id,user_name,"
                    + " approval_tier,currency,min_amount,max_amount,status,created_by,created_at,"
                    + " updated_at,version)"
                    + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::numeric,"
                    + " ?::numeric,'ACTIVE',?::uuid,now(),now(),0)")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setObject(3, userId);
      ps.setString(4, name);
      ps.setString(5, tier);
      ps.setString(6, currency);
      ps.setBigDecimal(7, new BigDecimal(min));
      if (max == null) {
        ps.setNull(8, java.sql.Types.NUMERIC);
      } else {
        ps.setBigDecimal(8, new BigDecimal(max));
      }
      ps.setObject(9, userId);
      ps.executeUpdate();
    }
    return id;
  }

  /** 工具：把一条审批链路记录读出来（断言用）。 */
  static long countRecords(UUID tenant, UUID orderId) throws Exception {
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "select count(*) from purchase_order_approval_record where tenant_id=?::uuid"
                    + " and order_id=?::uuid")) {
      ps.setObject(1, tenant);
      ps.setObject(2, orderId);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }
}
