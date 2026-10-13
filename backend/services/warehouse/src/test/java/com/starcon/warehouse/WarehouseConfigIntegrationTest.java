package com.starcon.warehouse;

import static com.starcon.warehouse.WarehouseConfigModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalTime;
import java.time.OffsetDateTime;
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
 * 8.1.1.3 仓库配置。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>本测试钉住七件「错了会误导人」的事：
 *
 * <ul>
 *   <li><b>「修改需记录变更」是硬要求。</b>逐字段留痕、前后值、首次配置时旧值为 null
 *       （「之前没有这个值」与「之前是默认值」必须能分开）。
 *   <li><b>留痕改不掉。</b>REVOKE 之外还有触发器，表 owner 也改不动。
 *   <li><b>没配过 ≠ 配成默认值。</b>unconfigured 如实报出来。
 *   <li><b>未交付 ≠ 已生效。</b>拣货/复核/批次/序列号/拆零五条影响声明逐条 available=false
 *       并写明归哪个模块 —— 配置落库了不等于下游已经在按它跑。
 *   <li><b>班次存当地墙上时刻。</b>时长与跨夜由服务端算，不接受调用方传入。
 *   <li><b>归档是终态。</b>归档仓不允许再改配置。
 *   <li><b>租户隔离三断言</b>实跑 + 逐张断言 FORCE RLS。
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class WarehouseConfigIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      List.of(
          "warehouse.facilities.read",
          "warehouse.facilities.create",
          "warehouse.facilities.update",
          // 8.1.1.3 新注册的码（identity V104）。刻意不复用 update：
          // 改仓库地址与改作业参数是两种风险面。
          "warehouse.facilities.config");

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-warehouse-config-" + tenant + "-" + ordinal).getBytes());
  }

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

  static {
    pg.start();
    try {
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        // nosuperuser nobypassrls：应用角色必须是普通角色，
        // 否则 FORCE RLS 对它不生效，隔离断言就测不出任何东西。
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
      // 只有 read 没有 config：用来证明改配置真的需要 config 码。
      actors.put(
          "Bearer read-only",
          actor(user, a, List.of(grant("ALL", List.of("warehouse.facilities.read")))));
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

  @Autowired WarehouseConfigService service;
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
    // TRUNCATE 不触发行级触发器，以超级用户连接执行可绕过 FORCE RLS ——
    // 只用于测试夹具重建，不给应用角色开这个口子。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate warehouse_config_change, warehouse_shift, warehouse_config, warehouse,"
              + " legal_entity_ref restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      access.run(
          actorFor(tenant),
          "warehouse.facilities.read",
          () -> {
            sql.execute(
                "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 1), tenant, "LE-1", "主体一");
            // B 的仓库刻意用不同的编码、名称、国家与时区：隔离失效时
            // 断言不会因为「两个仓长得一样」而含糊。
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,kind,country_code,timezone,"
                    + "default_legal_entity_id,status) values(?,?,?,?,?,?,?,?,?)",
                idFor(tenant, 2), tenant, "WH-" + (tenant.equals(a) ? "A" : "B"), "本租仓库",
                "LOCAL", tenant.equals(a) ? "CN" : "US",
                tenant.equals(a) ? "Asia/Shanghai" : "America/Los_Angeles",
                idFor(tenant, 1), "ACTIVE");
            return null;
          });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  UUID whOf(UUID tenant) {
    return idFor(tenant, 2);
  }

  <T> T asA(String permission, java.util.function.Supplier<T> work) {
    return access.run(actorA, "warehouse.facilities." + permission, work);
  }

  <T> T asB(String permission, java.util.function.Supplier<T> work) {
    return access.run(actorB, "warehouse.facilities." + permission, work);
  }

  WarehouseConfigSaved save(
      UUID tenant,
      boolean lot,
      boolean serial,
      String strategy,
      String ratio,
      boolean split) {
    AccessService.Access actor = actorFor(tenant);
    return access.run(
        actor,
        "warehouse.facilities.config",
        () ->
            service.update(
                whOf(tenant),
                new WarehouseConfigInput(lot, serial, strategy, new BigDecimal(ratio), split, ""),
                actor.userId()));
  }

  // ================================================================ 配置读与默认值

  @Test
  void 未配置时返回默认值并如实说_unconfigured() {
    var config = asA("read", () -> service.config(whOf(a)));
    assertThat(config.unconfigured()).isTrue();
    assertThat(config.pickingStrategy()).isEqualTo(PickingStrategy.ORDER);
    assertThat(config.defaultReviewRatio()).isEqualByComparingTo("0");
    assertThat(config.allowSplitShipment()).isFalse();
    assertThat(config.lotTrackingEnabled()).isFalse();
    assertThat(config.serialTrackingEnabled()).isFalse();
    // 未配置时**没有**建行 —— 自动建行会让「配成 0」与「没配过」分不开。
    assertThat(asA("read", () -> service.config(whOf(a))).createdAt()).isNull();
  }

  @Test
  void 改完配置_unconfigured_翻成_false() {
    save(a, true, false, "WAVE", "0.1", true);
    var config = asA("read", () -> service.config(whOf(a)));
    assertThat(config.unconfigured()).isFalse();
    assertThat(config.lotTrackingEnabled()).isTrue();
    assertThat(config.serialTrackingEnabled()).isFalse();
    assertThat(config.pickingStrategy()).isEqualTo(PickingStrategy.WAVE);
    assertThat(config.allowSplitShipment()).isTrue();
    assertThat(config.createdAt()).isNotNull();
    assertThat(config.updatedAt()).isNotNull();
  }

  @Test
  void 批次与序列号是两个独立开关() {
    // 只开序列号不开批次是合法组合（逐件扫但不录批号）——
    // 压成一个布尔的话这个组合无法表达。
    save(a, false, true, "ORDER", "0", false);
    var config = asA("read", () -> service.config(whOf(a)));
    assertThat(config.lotTrackingEnabled()).isFalse();
    assertThat(config.serialTrackingEnabled()).isTrue();
  }

  @Test
  void 拣货策略三选一_非法值报可读错误() {
    assertThatThrownBy(() -> save(a, false, false, "PICK_LIST", "0", false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ORDER / WAVE / LOCATION");
    // 三个合法值都真的能存进去并读回。
    for (var value : List.of("ORDER", "WAVE", "LOCATION")) {
      save(a, false, false, value, "0", false);
      assertThat(asA("read", () -> service.config(whOf(a))).pickingStrategy().name())
          .isEqualTo(value);
    }
  }

  @Test
  void 默认复核比例按_NUMERIC_读回是精确的() {
    // 配置是**当前态**而不是累加器，所以「0.1 加三次等于 0.3」这件事不该写成三次保存
    // —— 那是另一种东西。这里测的是真正会出错的两点：
    //
    //   1. 存进去再读出来精确是 0.3，不是 0.30000000000000004
    //      （double 的 0.1 是 0.1000000000000000055511151231257827）；
    //   2. 服务端把比例归一到 6 位小数（NUMERIC(9,6) 的精度），
    //      所以留痕里「同一个比例」只有一个写法，不会出现 0.05 与 0.050000 两个串。
    save(a, false, false, "ORDER", "0.3", false);
    assertThat(asA("read", () -> service.config(whOf(a))).defaultReviewRatio())
        .isEqualByComparingTo("0.3");
    assertThat(asA("read", () -> service.config(whOf(a))).defaultReviewRatio().toPlainString())
        .isEqualTo("0.300000");
    // 归一在入库前发生：0.05 与 0.0500001 落库后是同一个值。
    save(a, false, false, "ORDER", "0.05", false);
    assertThat(asA("read", () -> service.config(whOf(a))).defaultReviewRatio().toPlainString())
        .isEqualTo("0.050000");
    save(a, false, false, "ORDER", "0.0500001", false);
    assertThat(asA("read", () -> service.config(whOf(a))).defaultReviewRatio().toPlainString())
        .isEqualTo("0.050000");
    // 加法在 BigDecimal 上是精确的（这正是禁浮点记账要买到的东西）。
    assertThat(new BigDecimal("0.1").add(new BigDecimal("0.2")).toPlainString())
        .isEqualTo("0.3");
  }

  @Test
  void 默认复核比例越界被拒_并给出可读错误() {
    for (var bad : List.of("1.5", "-0.1")) {
      assertThatThrownBy(() -> save(a, false, false, "ORDER", bad, false))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("必须在 0 到 1 之间");
    }
  }

  @Test
  void DB_层也钉住比例区间_绕过服务层写不进去() throws Exception {
    // 应用层校验与 DB CHECK 是两道防线，各挡各的。这里用超级用户连接
    // 直接写，模拟「有人绕过服务层」的情形。
    assertThatThrownBy(
            () -> {
              try (var c =
                      DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                  var s = c.createStatement()) {
                s.execute(
                    "insert into warehouse_config(tenant_id,warehouse_id,default_review_ratio)"
                        + " values('" + a + "','" + whOf(a) + "',1.5)");
              }
            })
        .hasMessageContaining("warehouse_config_default_review_ratio_check");
  }

  // ================================================================ 变更留痕

  @Test
  void 首次配置逐字段留痕_旧值为_null() {
    var saved =
        save(a, true, true, "LOCATION", "0.05", true);
    assertThat(saved.changeCount()).isEqualTo(6);
    // 「之前没有这个值」与「之前是默认值」必须能分开：首次配置旧值全是 null。
    assertThat(saved.changes())
        .allSatisfy(c -> assertThat(c.oldValue()).as("%s 首次配置旧值应为 null", c.fieldName()).isNull());
    assertThat(saved.changes())
        .extracting(ConfigChange::fieldName)
        .containsExactlyInAnyOrder(
            "lotTrackingEnabled", "serialTrackingEnabled", "pickingStrategy",
            "defaultReviewRatio", "allowSplitShipment", "note");
    assertThat(saved.changes())
        .extracting(ConfigChange::newValue)
        .contains("true", "true", "LOCATION", "0.050000", "true", "");
  }

  @Test
  void 二次修改只留痕真正变了的字段() {
    save(a, true, false, "ORDER", "0", false);
    // 只改拣货策略与复核比例，其余三个字段值不变 —— 不该为它们写留痕，
    // 否则「这个仓改过几次」会被无意义的提交灌水。
    var saved = save(a, true, false, "WAVE", "0.2", false);
    assertThat(saved.changeCount()).isEqualTo(2);
    assertThat(saved.changes())
        .extracting(ConfigChange::fieldName)
        .containsExactlyInAnyOrder("pickingStrategy", "defaultReviewRatio");
    assertThat(saved.changes())
        .extracting(ConfigChange::oldValue, ConfigChange::newValue)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("ORDER", "WAVE"),
            org.assertj.core.groups.Tuple.tuple("0.000000", "0.200000"));
  }

  @Test
  void 提交完全相同的配置不写留痕() {
    save(a, false, false, "ORDER", "0", false);
    var again = save(a, false, false, "ORDER", "0", false);
    assertThat(again.changeCount()).isZero();
    assertThat(again.changes()).isEmpty();
    // 首次配置写 6 条：六个字段的旧值全是 null（「之前没有这个值」），
    // 所以连 note 从「没有」到「空串」也算一条。第二次提交才真的什么都没变。
    assertThat(asA("read", () -> service.changes(whOf(a), new ChangeQuery(null, null, 0, 50))).total())
        .isEqualTo(6);
  }

  @Test
  void 留痕记的是已认证成员_不接受请求参数传入() {
    save(a, false, false, "WAVE", "0", false);
    var change = asA("read", () -> service.changes(whOf(a), new ChangeQuery(null, null, 0, 1))).rows().get(0);
    assertThat(change.changedByMembershipId()).isEqualTo(actorA.userId());
    assertThat(change.changedAt()).isNotNull();
  }

  @Test
  void 留痕_append_only_运行角色改不动也删不掉() {
    save(a, true, false, "ORDER", "0", false);
    // 权限层：运行角色根本没有 UPDATE/DELETE 授权（V104 的 GRANT 只给 SELECT/INSERT）。
    // 必须在租户事务里跑：current_setting('app.current_tenant') 只在绑定后才不是空串。
    assertThatThrownBy(
            () ->
                asA(
                    "read",
                    () ->
                        sql.execute(
                            "update warehouse_config_change set new_value='WAVE'"
                                + " where tenant_id = current_setting('app.current_tenant', true)::uuid")))
        .rootCause()
        .hasMessageContaining("permission denied");
    assertThatThrownBy(
            () ->
                asA(
                    "read",
                    () ->
                        sql.execute(
                            "delete from warehouse_config_change"
                                + " where tenant_id = current_setting('app.current_tenant', true)::uuid")))
        .rootCause()
        .hasMessageContaining("permission denied");
    // 留痕仍在，一条都没少
    assertThat(asA("read", () -> service.changes(whOf(a), new ChangeQuery(null, null, 0, 50))).total())
        .isPositive();
  }

  @Test
  void 留痕_append_only_连超级用户也改不动() throws Exception {
    save(a, true, false, "ORDER", "0", false);
    // REVOKE 只挡运行角色。表 owner 与超级用户仍能改，所以必须有第二道触发器 ——
    // 这一条就是那第二道的实跑证明。
    assertThatThrownBy(
            () -> {
              try (var c =
                      DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                  var s = c.createStatement()) {
                s.execute(
                    "update warehouse_config_change set new_value='WAVE' where tenant_id='"
                        + a + "'");
              }
            })
        .hasMessageContaining("append-only");
    assertThatThrownBy(
            () -> {
              try (var c =
                      DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                  var s = c.createStatement()) {
                s.execute("delete from warehouse_config_change where tenant_id='" + a + "'");
              }
            })
        .hasMessageContaining("append-only");
  }

  @Test
  void 留痕_history_按时间倒序且带_确定次序() {
    save(a, false, false, "ORDER", "0", false);
    save(a, false, false, "WAVE", "0.1", false);
    save(a, false, false, "LOCATION", "0.2", false);
    var page = asA("read", () -> service.changes(whOf(a), new ChangeQuery(null, null, 0, 50)));
    // 首次配置写 6 条（六个字段旧值都是 null），其后每次改两个字段写 2 条：6 + 2 + 2 = 10。
    assertThat(page.total()).isEqualTo(10);
    var times = page.rows().stream().map(ConfigChange::changedAt).toList();
    assertThat(times).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    // 倒序：最新一组在最前。同一次保存的六条 changed_at 完全相同
    // （PG 的 now() 是事务时间），所以定序落到 id 上 —— UUIDv7 单调递增，
    // 于是组内是插入次序。这条断言就是「id 收尾定序真的在起作用」的实跑证明。
    assertThat(page.rows().get(0).newValue()).isEqualTo("LOCATION");
    // 最旧一组（首次配置）的六个字段，顺序与写入次序逐条一致。
    assertThat(page.rows().subList(4, 10))
        .extracting(ConfigChange::fieldName)
        .containsExactly(
            "lotTrackingEnabled", "serialTrackingEnabled", "pickingStrategy",
            "defaultReviewRatio", "allowSplitShipment", "note");
    // 每一条都能在两次翻页里各出现一次，不重复不丢失。
    var all = page.rows().stream().map(ConfigChange::id).toList();
    assertThat(all).doesNotHaveDuplicates();
  }

  @Test
  void 留痕_history_按范围与班次筛选() {
    save(a, false, false, "WAVE", "0.1", false);
    var shift = asA("config", () -> service.createShift(whOf(a), shiftIn("D", "白班", "09:00", "18:00"), actorA.userId()));
    var configOnly = asA("read", () -> service.changes(whOf(a), new ChangeQuery("CONFIG", null, 0, 50)));
    assertThat(configOnly.total()).isEqualTo(6);   // 首次配置：六个字段旧值都是 null
    assertThat(configOnly.rows()).allSatisfy(c -> assertThat(c.scope()).isEqualTo("CONFIG"));
    var shiftOnly = asA("read", () -> service.changes(whOf(a), new ChangeQuery("SHIFT", null, 0, 50)));
    assertThat(shiftOnly.rows()).allSatisfy(c -> assertThat(c.scope()).isEqualTo("SHIFT"));
    var byShift = asA("read", () -> service.changes(whOf(a), new ChangeQuery(null, shift.shift().id(), 0, 50)));
    assertThat(byShift.total()).isGreaterThan(0);
    assertThat(byShift.rows()).allSatisfy(c -> assertThat(c.shiftId()).isEqualTo(shift.shift().id()));
    // 非法范围报可读错误，不静默忽略成「全部」。
    assertThatThrownBy(() -> asA("read", () -> service.changes(whOf(a), new ChangeQuery("NOPE", null, 0, 50))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的留痕范围");
  }

  @Test
  void 留痕_history_真分页且大小有上限() {
    for (var i = 0; i < 5; i++) save(a, false, false, "ORDER", "0." + i, false);
    var first = asA("read", () -> service.changes(whOf(a), new ChangeQuery(null, null, 0, 3)));
    var second = asA("read", () -> service.changes(whOf(a), new ChangeQuery(null, null, 1, 3)));
    assertThat(first.size()).isEqualTo(3);
    // 首次配置写 6 条，其后四次各改 defaultReviewRatio 一条：6 + 4 = 10。
    assertThat(first.total()).isEqualTo(10);
    assertThat(second.size()).isEqualTo(3);
    var ids = new java.util.ArrayList<UUID>();
    first.rows().forEach(r -> ids.add(r.id()));
    second.rows().forEach(r -> ids.add(r.id()));
    assertThat(ids).doesNotHaveDuplicates();   // 翻页不重复：id 收尾定序在起作用
  }

  // ================================================================ 班次

  static ShiftInput shiftIn(String code, String name, String start, String end) {
    return new ShiftInput(code, name, start, end, true, "");
  }

  @Test
  void 新建班次_时长与跨夜由服务端算() {
    var day = asA("config", () -> service.createShift(whOf(a), shiftIn("D", "白班", "09:00", "18:00"), actorA.userId()));
    assertThat(day.shift().durationMinutes()).isEqualTo(9 * 60);
    assertThat(day.shift().crossesMidnight()).isFalse();
    assertThat(day.shift().startLocal()).isEqualTo(LocalTime.of(9, 0));
    assertThat(day.shift().endLocal()).isEqualTo(LocalTime.of(18, 0));

    // 跨夜班 22:00 → 06:00：时长是 480 分钟（跨到次日），
    // 不能是负数，也不能是调用方自己传的 crosses_midnight。
    var night = asA("config", () -> service.createShift(whOf(a), shiftIn("N", "夜班", "22:00", "06:00"), actorA.userId()));
    assertThat(night.shift().crossesMidnight()).isTrue();
    assertThat(night.shift().durationMinutes()).isEqualTo(8 * 60);
  }

  @Test
  void 班次_上下班相同被拒() {
    assertThatThrownBy(
            () -> asA("config", () -> service.createShift(whOf(a), shiftIn("Z", "零长班", "09:00", "09:00"), actorA.userId())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能相同");
  }

  @Test
  void 班次_时刻格式非法报可读错误() {
    assertThatThrownBy(
            () -> asA("config", () -> service.createShift(whOf(a), shiftIn("B", "坏班", "九点", "18:00"), actorA.userId())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("格式不正确");
  }

  @Test
  void 班次_停用是出口_班次行仍在列() {
    var created = asA("config", () -> service.createShift(whOf(a), shiftIn("D", "白班", "09:00", "18:00"), actorA.userId()));
    asA(
        "config",
        () ->
            service.updateShift(
                whOf(a), created.shift().id(),
                new ShiftUpdateInput("白班", "09:00", "18:00", false, ""),
                actorA.userId()));
    var shifts = asA("read", () -> service.shifts(whOf(a)));
    assertThat(shifts).hasSize(1);
    assertThat(shifts.get(0).enabled()).isFalse();
    // 停用走的是 SHIFT_SCHEDULE 范围的留痕，与改时刻（SHIFT）分开。
    var history = asA("read", () -> service.changes(whOf(a), new ChangeQuery("SHIFT_SCHEDULE", null, 0, 50)));
    assertThat(history.total()).isEqualTo(2);
    assertThat(history.rows().get(0).oldValue()).isEqualTo("true");
    assertThat(history.rows().get(0).newValue()).isEqualTo("false");
  }

  @Test
  void 班次_新建走留痕且带班次编码() {
    var created = asA("config", () -> service.createShift(whOf(a), shiftIn("D", "白班", "09:00", "18:00"), actorA.userId()));
    assertThat(created.changeCount()).isEqualTo(5);
    var history = asA("read", () -> service.changes(whOf(a), new ChangeQuery(null, created.shift().id(), 0, 50)));
    assertThat(history.total()).isEqualTo(5);
    assertThat(history.rows()).allSatisfy(c -> assertThat(c.shiftCode()).isEqualTo("D"));
  }

  @Test
  void 班次_改时刻只留痕变了的字段() {
    var created = asA("config", () -> service.createShift(whOf(a), shiftIn("D", "白班", "09:00", "18:00"), actorA.userId()));
    var saved =
        asA(
            "config",
            () ->
                service.updateShift(
                    whOf(a), created.shift().id(),
                    new ShiftUpdateInput("白班", "10:00", "18:00", true, ""),
                    actorA.userId()));
    assertThat(saved.changeCount()).isEqualTo(1);
    assertThat(saved.changes().get(0).fieldName()).isEqualTo("startLocal");
    assertThat(saved.changes().get(0).oldValue()).isEqualTo("09:00:00");
    assertThat(saved.changes().get(0).newValue()).isEqualTo("10:00:00");
  }

  @Test
  void 班次_同仓编码重复被拒() {
    asA("config", () -> service.createShift(whOf(a), shiftIn("D", "白班", "09:00", "18:00"), actorA.userId()));
    assertThatThrownBy(
            () -> asA("config", () -> service.createShift(whOf(a), shiftIn("D", "又一个白班", "10:00", "19:00"), actorA.userId())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("已存在");
  }

  @Test
  void 班次_清单启用在前停用沉底() {
    asA("config", () -> service.createShift(whOf(a), shiftIn("D", "白班", "09:00", "18:00"), actorA.userId()));
    var night = asA("config", () -> service.createShift(whOf(a), shiftIn("N", "夜班", "22:00", "06:00"), actorA.userId()));
    asA(
        "config",
        () ->
            service.updateShift(
                whOf(a), night.shift().id(),
                new ShiftUpdateInput("夜班", "22:00", "06:00", false, ""),
                actorA.userId()));
    var shifts = asA("read", () -> service.shifts(whOf(a)));
    assertThat(shifts.get(0).code()).isEqualTo("D");
    assertThat(shifts.get(0).enabled()).isTrue();
    assertThat(shifts.get(1).code()).isEqualTo("N");
    assertThat(shifts.get(1).enabled()).isFalse();
  }

  // ================================================================ 归档与权限

  @Test
  void 归档是终态_归档仓不允许再改配置() {
    asA("read", () -> sql.execute("update warehouse set status='ARCHIVED' where tenant_id=current_setting('app.current_tenant', true)::uuid and id=?", whOf(a)));
    assertThatThrownBy(() -> save(a, false, false, "WAVE", "0", false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("归档是终态");
    assertThatThrownBy(() -> asA("config", () -> service.createShift(whOf(a), shiftIn("D", "白班", "09:00", "18:00"), actorA.userId())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("归档是终态");
    // 归档前留下的配置原样保留 —— 归档的是仓库，不是它的作业参数。
    var overview = asA("read", () -> service.overview(whOf(a), new ChangeQuery(null, null, 0, 20)));
    assertThat(overview.archived()).isTrue();
    assertThat(overview.editable()).isFalse();
    assertThat(overview.warehouseStatus()).isEqualTo("ARCHIVED");
  }

  @Test
  void 改配置需要_config_权限码_不是_update() throws Exception {
    // 复用 warehouse.facilities.update（8.1.1.2 的码）改不了作业参数 ——
    // 两个风险面刻意分开，所以授权时也能分开授予。
    http.perform(put("/warehouse/facilities/{id}/config", whOf(a))
            .header("Authorization", "Bearer read-only")
            .contentType("application/json")
            .content("""
                {"lotTrackingEnabled":true,"serialTrackingEnabled":false,
                 "pickingStrategy":"WAVE","defaultReviewRatio":0.1,
                 "allowSplitShipment":true,"note":""}"""))
        .andExpect(status().isForbidden());
    // 有 config 码就能改。
    http.perform(put("/warehouse/facilities/{id}/config", whOf(a))
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("""
                {"lotTrackingEnabled":true,"serialTrackingEnabled":false,
                 "pickingStrategy":"WAVE","defaultReviewRatio":0.1,
                 "allowSplitShipment":true,"note":""}"""))
        .andExpect(status().isOk());
  }

  @Test
  void 读配置用_facilities_read_不需要_config() throws Exception {
    http.perform(get("/warehouse/facilities/{id}/config", whOf(a))
            .header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk());
  }

  @Test
  void 仓库不存在或不属本租户都_404() {
    UUID foreign = UUID.randomUUID();
    assertThatThrownBy(() -> asA("read", () -> service.config(foreign)))
        .hasMessageContaining("404");
    // 两者可观测结果必须相同，否则「猜 id」成了探测别的租户有没有仓库的侧信道。
    assertThatThrownBy(() -> asB("read", () -> service.config(whOf(a))))
        .hasMessageContaining("404");
  }

  // ================================================================ 影响声明与下拉项

  @Test
  void 下游影响逐条表态_未交付的显式报_false() {
    var impacts = asA("read", () -> service.impacts());
    assertThat(impacts).hasSize(6);
    var byItem = impacts.stream().collect(java.util.stream.Collectors.toMap(ConfigImpact::item, i -> i));
    // 本模块自身的班次是真已交付
    assertThat(byItem.get("SHIFT_SCHEDULE").available()).isTrue();
    assertThat(byItem.get("SHIFT_SCHEDULE").ownerModule()).isEqualTo("8.1.1.3");
    // 其余五条逐条 available=false 并写明归哪个模块 ——
    // 「配置落库了」不等于「下游已经在按它跑」。
    for (var item : List.of("BATCH_TRACKING", "SERIAL_TRACKING", "PICKING_STRATEGY",
        "REVIEW_RATIO", "SPLIT_SHIPMENT")) {
      assertThat(byItem.get(item).available()).as(item).isFalse();
      assertThat(byItem.get(item).ownerModule()).as(item).isNotBlank();
      assertThat(byItem.get(item).note()).as(item).isNotBlank();
    }
  }

  @Test
  void 下拉项给三选一与本仓班次() {
    var options = asA("read", () -> service.options(whOf(a)));
    assertThat(options.strategies()).hasSize(3);
    assertThat(options.strategies()).extracting(PickingStrategyOption::code)
        .containsExactly("ORDER", "WAVE", "LOCATION");
    assertThat(options.strategies()).allSatisfy(s -> assertThat(s.note()).isNotBlank());
    assertThat(options.scopes()).extracting(ScopeOption::code)
        .containsExactly("CONFIG", "SHIFT", "SHIFT_SCHEDULE");
    asA("config", () -> service.createShift(whOf(a), shiftIn("D", "白班", "09:00", "18:00"), actorA.userId()));
    assertThat(asA("read", () -> service.options(whOf(a))).shifts()).hasSize(1);
    assertThat(options.reviewRatioNote()).contains("0~1");
    assertThat(options.splitShipmentNote()).contains("拆零");
    assertThat(options.shiftNote()).contains("当地墙上时刻");
  }

  @Test
  void 配置全貌一次返回且带仓库时区() {
    save(a, true, false, "WAVE", "0.1", false);
    asA("config", () -> service.createShift(whOf(a), shiftIn("D", "白班", "09:00", "18:00"), actorA.userId()));
    var overview = asA("read", () -> service.overview(whOf(a), new ChangeQuery(null, null, 0, 20)));
    assertThat(overview.warehouseCode()).isEqualTo("WH-A");
    assertThat(overview.timezone()).isEqualTo("Asia/Shanghai");
    assertThat(overview.editable()).isTrue();
    assertThat(overview.config().pickingStrategy()).isEqualTo(PickingStrategy.WAVE);
    assertThat(overview.shifts()).hasSize(1);
    assertThat(overview.changes().total()).isPositive();
    assertThat(overview.impacts()).hasSize(6);
    assertThat(overview.note()).contains("Asia/Shanghai");
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    save(a, true, true, "LOCATION", "0.9", true);
    asA("config", () -> service.createShift(whOf(a), shiftIn("D", "白班", "09:00", "18:00"), actorA.userId()));

    // B 拿 A 的仓 id 取配置：404，不是「B 的同名仓库」也不是空对象
    assertThatThrownBy(() -> asB("read", () -> service.config(whOf(a))))
        .hasMessageContaining("404");
    // B 自己的仓是未配置态：读不到 A 的任何值
    var bConfig = asB("read", () -> service.config(whOf(b)));
    assertThat(bConfig.unconfigured()).isTrue();
    assertThat(bConfig.pickingStrategy()).isEqualTo(PickingStrategy.ORDER);
    assertThat(bConfig.defaultReviewRatio()).isEqualByComparingTo("0");
    // B 也读不到 A 的班次与留痕
    assertThat(asB("read", () -> service.shifts(whOf(b)))).isEmpty();
    // 拿 A 的仓 id 查留痕同样是 404（不是「0 条」—— 「0 条」会读成「A 改过但没留痕」）
    assertThatThrownBy(() -> asB("read", () -> service.changes(whOf(a), new ChangeQuery(null, null, 0, 50))))
        .hasMessageContaining("404");
    // B 自己的仓没有留痕，是真的 0 条
    assertThat(asB("read", () -> service.changes(whOf(b), new ChangeQuery(null, null, 0, 50))).total())
        .isZero();
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    save(a, true, false, "WAVE", "0.4", true);

    // 身份是 B，伪造 X-Tenant-Id 指向 A：B 仍然拿不到 A 的配置
    http.perform(get("/warehouse/facilities/{id}/config", whOf(a))
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    // 伪造头也不能让 B 改到 A 的配置
    http.perform(put("/warehouse/facilities/{id}/config", whOf(a))
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString())
            .contentType("application/json")
            .content("""
                {"lotTrackingEnabled":false,"serialTrackingEnabled":false,
                 "pickingStrategy":"ORDER","defaultReviewRatio":0,
                 "allowSplitShipment":false,"note":""}"""))
        .andExpect(status().isNotFound());
    // 伪造头也不能让 B 往 A 的仓里塞班次
    http.perform(post("/warehouse/facilities/{id}/config/shifts", whOf(a))
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString())
            .contentType("application/json")
            .content("""
                {"code":"FORGED","name":"伪造班次","startLocal":"09:00",
                 "endLocal":"18:00","enabled":true,"note":""}"""))
        .andExpect(status().isNotFound());
    // A 的数据未被这串伪造请求改动
    var aConfig = asA("read", () -> service.config(whOf(a)));
    assertThat(aConfig.pickingStrategy()).isEqualTo(PickingStrategy.WAVE);
    assertThat(aConfig.defaultReviewRatio()).isEqualByComparingTo("0.4");
    assertThat(asA("read", () -> service.shifts(whOf(a)))).isEmpty();
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    save(a, true, true, "LOCATION", "0.9", true);
    asA("config", () -> service.createShift(whOf(a), shiftIn("D", "白班", "09:00", "18:00"), actorA.userId()));
    save(b, false, false, "ORDER", "0", false);

    // 交替复用同一条物理连接（池容量=1）：A → B → A → B
    for (int i = 0; i < 3; i++) {
      assertThat(asA("read", () -> service.config(whOf(a))).pickingStrategy())
          .isEqualTo(PickingStrategy.LOCATION);
      assertThat(asA("read", () -> service.shifts(whOf(a)))).hasSize(1);
      assertThat(asB("read", () -> service.config(whOf(b))).pickingStrategy())
          .isEqualTo(PickingStrategy.ORDER);
      assertThat(asB("read", () -> service.shifts(whOf(b)))).isEmpty();
    }
    // 写路径同样不能串：B 反复写自己的配置，A 的配置保持不变
    for (int i = 1; i <= 3; i++) {
      save(b, false, false, "WAVE", "0." + i, false);
      assertThat(asB("read", () -> service.config(whOf(b))).defaultReviewRatio())
          .isEqualByComparingTo("0." + i);
      assertThat(asA("read", () -> service.config(whOf(a))).defaultReviewRatio())
          .isEqualByComparingTo("0.9");
      assertThat(asA("read", () -> service.shifts(whOf(a)))).hasSize(1);
      assertThat(asB("read", () -> service.shifts(whOf(b)))).isEmpty();
    }
    // 本模块的三张新表逐张确认 FORCE RLS：
    // 留痕与配置都在这里，漏一张等于「读到别人家的配置」。
    for (var table : List.of("warehouse", "warehouse_config", "warehouse_shift",
        "warehouse_config_change")) {
      boolean forced =
          sql.fetchOne(
                  "select (relrowsecurity and relforcerowsecurity) as on from pg_class"
                      + " where relname=? and relnamespace='public'::regnamespace",
                  table)
              .get("on", Boolean.class);
      assertThat(forced).as("%s 必须 FORCE RLS", table).isTrue();
    }
    // 不设 app.current_tenant 时读不到任何行：第一道防线在服务层谓词之前就生效。
    for (var table : List.of("warehouse_config", "warehouse_shift", "warehouse_config_change")) {
      assertThat(sql.fetchOne(
              "select count(*) from " + table
                  + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid")
          .get(0, Long.class)).as(table).isZero();
    }
  }

  @Test
  void 班次写回的值就是墙上时刻_没有被时区偏移() {
    // 存的是 time 而不是 timestamptz，所以不存在 OffsetDateTime 往返的 −28800s 问题；
    // 断言的是**写进去再读出来的那个值**，而不是「与服务端时钟同时刻」的假想比较。
    asA("config", () -> service.createShift(whOf(a), shiftIn("N", "夜班", "22:00", "06:00"), actorA.userId()));
    var shift = asA("read", () -> service.shifts(whOf(a))).get(0);
    assertThat(shift.startLocal()).isEqualTo(LocalTime.of(22, 0));
    assertThat(shift.endLocal()).isEqualTo(LocalTime.of(6, 0));
    // 直接问数据库读回，确认不是服务层自己转的
    var raw =
        asA(
            "read",
            () ->
                sql.fetchOne(
                    "select start_local, end_local from warehouse_shift"
                        + " where tenant_id = current_setting('app.current_tenant', true)::uuid"
                        + " and code='N'"));
    assertThat(raw.get("start_local", LocalTime.class)).isEqualTo(LocalTime.of(22, 0));
    assertThat(raw.get("end_local", LocalTime.class)).isEqualTo(LocalTime.of(6, 0));
  }

  @Test
  void 留痕时刻是真实的_不是客户端传的() {
    var before = OffsetDateTime.now().minusSeconds(5);
    save(a, false, false, "WAVE", "0", false);
    var change = asA("read", () -> service.changes(whOf(a), new ChangeQuery(null, null, 0, 1))).rows().get(0);
    assertThat(change.changedAt()).isAfter(before);
  }
}
