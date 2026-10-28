package com.starcon.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 14.3.1.3 商品维度报表的**测试夹具**（两个测试类共用）。
 *
 * <p>🔴 本模块要跨两个服务读（13.4.1.2 reporting、03 catalog）⇒ 夹具起两个 HTTP 桩：
 * <ul>
 *   <li><b>reporting 桩</b>：{@code GET /reporting/sku-profit/report} —— 返回 SKU 级利润事实。
 *   <li><b>catalog 桩</b>：{@code GET /catalog/skus} —— 返回 SKU 编码 / SPU 归属。
 * </ul>
 *
 * <p>🔴 两个桩都<b>按 bearer 分租户</b>（与 identity 桩同一套约定）：转发的是调用方自己的
 * 会话，租户隔离因此是两道。桩**不按请求头里的租户号分** —— 那正是本模块要防的事。
 */
final class AdProductReportFixture {

  private AdProductReportFixture() {}

  /** Testcontainers 真实 PostgreSQL 16。 */
  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");

  /** identity 桩：bearer → 已认证成员。 */
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** reporting 桩：bearer → 利润事实行。 */
  static final Map<String, List<Map<String, Object>>> profitByBearer = new ConcurrentHashMap<>();

  /** catalog 桩：bearer → SKU 身份行。 */
  static final Map<String, List<Map<String, Object>>> skusByBearer = new ConcurrentHashMap<>();

  static HttpServer identity;
  static HttpServer reporting;
  static HttpServer catalog;

  /** 🔴 固定时刻（有人用 {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s）。 */
  static final java.time.OffsetDateTime DATA_AS_OF =
      java.time.OffsetDateTime.parse("2026-10-01T09:00:00+08:00");
  static final java.time.OffsetDateTime STARTED_AT =
      java.time.OffsetDateTime.parse("2026-10-01T08:00:00+08:00");
  static final java.time.OffsetDateTime FINISHED_AT =
      java.time.OffsetDateTime.parse("2026-10-01T09:05:00+08:00");
  static final java.time.OffsetDateTime STRUCTURE_AS_OF =
      java.time.OffsetDateTime.parse("2026-10-01T09:00:00+08:00");

  static final java.time.LocalDate DAY_1 = java.time.LocalDate.of(2026, 10, 1);

  /** V1 平台目录里真实存在的 id：...-101 = AMAZON。 */
  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");

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

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  /** 启动容器与三个桩（幂等）。 */
  static synchronized void start() {
    if (identity != null) return;
    try {
      if (!pg.isRunning()) {
        pg.start();
      }
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute(
            "do $$ begin if not exists(select 1 from pg_roles where rolname='starcon_app')"
                + " then create role starcon_app nologin; end if; end $$");
        s.execute(
            "do $$ begin if not exists(select 1 from pg_roles where rolname='channel_app')"
                + " then create role channel_app login password 'test-only' nosuperuser nobypassrls; end if; end $$");
        s.execute(
            "alter role channel_app login password 'test-only' nosuperuser nobypassrls");
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

      // ---- reporting 桩：13.4.1.2 SKU 利润 ----
      reporting = HttpServer.create(new InetSocketAddress(0), 0);
      reporting.createContext(
          "/reporting/sku-profit/report",
          e -> {
            String bearer = e.getRequestHeaders().getFirst("Authorization");
            List<Map<String, Object>> rows = profitByBearer.get(bearer);
            if (rows == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("from", "2026-10-01");
            body.put("to", "2026-10-31");
            body.put("dimension", "SKU");
            body.put("rows", rows);
            body.put("skuCount", rows.size());
            body.put("comparableCount", rows.size());
            body.put("notComparableCount", 0);
            body.put("dataUpdatedAt", DATA_AS_OF.toString());
            body.put("dataUpdatedNote", "桩");
            body.put("caliberNote", "桩");
            body.put("warnings", List.of());
            writeJson(e, body);
          });
      reporting.start();

      // ---- catalog 桩：SKU 编码 / SPU 归属 ----
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/skus",
          e -> {
            String bearer = e.getRequestHeaders().getFirst("Authorization");
            List<Map<String, Object>> skus = skusByBearer.get(bearer);
            if (skus == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", skus);
            body.put("total", skus.size());
            body.put("page", 0);
            body.put("size", 200);
            writeJson(e, body);
          });
      catalog.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** 停掉三个桩（容器由 Testcontainers 的 ryuk 回收）。 */
  static synchronized void stop() {
    if (identity != null) {
      identity.stop(0);
      identity = null;
    }
    if (reporting != null) {
      reporting.stop(0);
      reporting = null;
    }
    if (catalog != null) {
      catalog.stop(0);
      catalog = null;
    }
  }

  private static void writeJson(com.sun.net.httpserver.HttpExchange e, Map<String, Object> body)
      throws java.io.IOException {
    byte[] bytes = new ObjectMapper().writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(200, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  // ------------------------------------------------------------------
  // 数据播种
  // ------------------------------------------------------------------

  /** 播种平台默认映射（全局码表）：归一口径的真相在 2.1.2.1，不在请求里。 */
  static void seedGlobalCatalog() {
    exec(
        "insert into platform_enum_mapping (id,platform_id,enum_kind,external_value,internal_value)"
            + " values ('"
            + UUID.randomUUID()
            + "','"
            + AMAZON
            + "','AD_TYPE','sponsored_products','AUTO_DISCOVERY')"
            + " on conflict (platform_id,enum_kind,external_value) do nothing");
  }

  /** 一条利润事实（SKU × 币种）。 */
  static Map<String, Object> profitRow(
      String skuCode,
      String currency,
      String revenue,
      String quantity,
      String adFee,
      String totalCost,
      String netProfit) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("skuCode", skuCode);
    row.put("currency", currency);
    row.put(
        "components",
        List.of(
            component("REVENUE", revenue, quantity),
            component("AD_FEE", adFee, null),
            component("COGS", totalCost, null)));
    row.put("totalCost", new java.math.BigDecimal(totalCost));
    row.put("netProfit", new java.math.BigDecimal(netProfit));
    row.put("netMargin", new java.math.BigDecimal("0.25"));
    row.put("netProfitComparable", true);
    row.put("missingComponents", List.of());
    row.put("modeBreakdown", List.of());
    row.put("factCount", 3);
    row.put("note", "桩");
    return row;
  }

  private static Map<String, Object> component(
      String component, String amount, String quantity) {
    Map<String, Object> c = new LinkedHashMap<>();
    c.put("component", component);
    c.put("label", component);
    c.put("amount", amount == null ? null : new java.math.BigDecimal(amount));
    c.put("quantity", quantity == null ? null : new java.math.BigDecimal(quantity));
    c.put("status", "AVAILABLE");
    c.put("available", true);
    c.put("reason", "");
    return c;
  }

  /** 一条 SKU 身份（id → 编码 / SPU）。 */
  static Map<String, Object> skuRow(UUID skuId, String code, String name, String spuCode) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", skuId.toString());
    row.put("code", code);
    row.put("name", name);
    row.put("spuCode", spuCode);
    return row;
  }

  // ------------------------------------------------------------------
  // SQL 执行辅助
  // ------------------------------------------------------------------

  /** 以管理员（超级用户，RLS 不生效）连接执行 SQL。 */
  static void exec(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以「已认证的租户操作者」身份执行 SQL：绑定租户 + actor + 权限 + 数据范围。 */
  static void execAsTenant(UUID tenant, String sql) {
    execAsTenant(tenant, sql, new Object[0]);
  }

  /** 同上，但带绑定参数（时间列用 {@code ?::timestamptz}）。 */
  static void execAsTenant(UUID tenant, String sql, Object... binds) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + UUID.randomUUID() + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      if (binds.length == 0) {
        s.execute(sql);
      } else {
        try (var ps = c.prepareStatement(sql)) {
          for (int i = 0; i < binds.length; i++) {
            ps.setObject(i + 1, binds[i]);
          }
          ps.execute();
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static String execOneAsTenant(UUID tenant, String sql) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + UUID.randomUUID() + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(sql)) {
        if (rs.next()) {
          return rs.getString(1);
        }
        throw new IllegalStateException("查询没有返回行：" + sql);
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以「已认证的租户操作者」身份查一列（字符串）。 */
  static List<String> queryStringsAsTenant(UUID tenant, String sql) {
    return queryStrings(tenant, sql, pg.getUsername(), pg.getPassword());
  }

  /**
   * 以**应用角色**（channel_app，nobypassrls）身份查一列 —— 真正走 RLS。
   *
   * <p>🔴 容器超级用户是 BYPASSRLS 的 ⇒ 用它查「看得到几行」证明不了隔离。
   * 隔离的证明必须用**应用角色**做：FORCE RLS 对它才生效。
   */
  static List<String> queryStringsAsRlsTenant(UUID tenant, String sql) {
    return queryStrings(tenant, sql, "channel_app", "test-only");
  }

  private static List<String> queryStrings(
      UUID tenant, String sql, String user, String password) {
    List<String> out = new ArrayList<>();
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), user, password);
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + UUID.randomUUID() + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.read',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(sql)) {
        while (rs.next()) out.add(rs.getString(1));
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
    return out;
  }
}
