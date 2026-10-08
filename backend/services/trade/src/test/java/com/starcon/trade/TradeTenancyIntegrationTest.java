package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.tenancy.TenantTransactions;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
 * 服务引导的**唯一**证据：证明 trade 不是一个能启动的空壳。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p>跑的是 AGENTS.md 明文要求的三条断言：
 *
 * <ol>
 *   <li>两租户互不可见；
 *   <li>伪造租户上下文无效；
 *   <li>连接池归还后不串租户。
 * </ol>
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p>本测试刻意不碰任何卷 09 业务形状：订单、分仓、履约请求都属于后续车道。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TradeTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  // 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID
  // （那会让租户 B 的插入直接撞租户 A 的主键，失败原因会与隔离问题混淆）。
  static final UUID rowA = idFor(a, 1);
  static final UUID rowB = idFor(b, 1);

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-trade-tenancy-" + tenant + "-" + ordinal).getBytes());
  }

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
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
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    // AccessService（9.3.2.2 随第一条业务端点引入）是 @Value 注入的 Bean，
    // 没有这两个属性时**整个上下文起不来** —— 与数据源无关，是构造器注入就失败。
    r.add("IDENTITY_URL", () -> "http://localhost:8081");
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  /** 在可信租户事务里写入一行基线表。timestamptz 一律绑 OffsetDateTime + `?::timestamptz`。 */
  void insert(UUID tenant, UUID id, String label) {
    tenants.run(
        tenant,
        () -> {
          // on conflict do nothing：ID 按租户固定派生，@BeforeEach 每个用例都跑一次，
          // 不去重的话第二个用例会撞主键，失败原因与隔离问题无关。
          sql.execute(
              "insert into trade_tenancy_probe(id,tenant_id,label,created_at)"
                  + " values (?,?,?,?::timestamptz)"
                  + " on conflict (id) do nothing",
              id, tenant, label, OffsetDateTime.now(ZoneOffset.UTC));
          return null;
        });
  }

  /** 以指定租户身份读基线表，返回可见的 label。 */
  List<String> labelsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select label from trade_tenancy_probe order by label")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select label from trade_tenancy_probe").size();
  }

  /** 在**当前**已绑定的租户事务里读，不新开事务（用于验证事务内 RLS 立即生效）。 */
  List<String> labelsUnderCurrentBinding() {
    return sql.fetch("select label from trade_tenancy_probe order by label")
        .map(r -> r.get(0, String.class));
  }

  /**
   * 当前连接上 {@code app.current_tenant} 的**原始**值。
   *
   * <p>刻意用 {@code fetch(...).get(0, Class)} 而不是 {@code fetchValue(sql, Class)} ——
   * 后者会匹配到 {@code (String, Object...)} 重载，把 Class 本身当成一个绑定值
   * （与 channel OAuthAuthorizationService 里记下的是同一个坑）。
   *
   * <p>注意：事务局部 {@code set_config} 提交之后，PostgreSQL 会把自定义 GUC 复位成
   * **空串**而不是「回到未设置」，所以这里可能拿到 {@code ""}、也可能拿到 {@code null}。
   * 这正是 V1 策略里用 {@code nullif(current_setting(...),'')::uuid} 而不是直接 cast 的原因。
   */
  String rawTenantSetting() {
    return sql.fetch("select current_setting('app.current_tenant', true)")
        .stream()
        .findFirst()
        .map(r -> r.get(0, String.class))
        .orElse(null);
  }

  /** RLS 策略**实际**使用的值：空串与 NULL 都归一成 null，也就是「没有租户」。 */
  UUID effectiveTenant() {
    var row = sql.fetchOne("select nullif(current_setting('app.current_tenant', true),'')::uuid");
    return row == null ? null : row.get(0, UUID.class);
  }

  @BeforeEach
  void seed() {
    insert(a, rowA, "tenant-a-row");
    insert(b, rowB, "tenant-b-row");
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 基线表强制rls而不是只启用() {
    var row =
        sql.fetchOne(
            "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?",
            "trade_tenancy_probe");
    assertThat(row).as("V1 基线迁移必须真的建出这张表").isNotNull();
    assertThat(row.get(0, Boolean.class)).as("RLS 未启用").isTrue();
    assertThat(row.get(1, Boolean.class)).as("只有 FORCE 才挡得住「运行角色被误授 owner」").isTrue();
    // 运行角色确实拿到了写权限 —— 否则「看不到别人的行」可能只是因为谁都读不了。
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('trade_app','trade_tenancy_probe','SELECT')")
                .get(0, Boolean.class))
        .as("trade_app 必须真的能读这张表")
        .isTrue();
  }

  // ==========================================================================
  // 断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    assertThat(labelsOf(a)).containsExactly("tenant-a-row");
    assertThat(labelsOf(b)).containsExactly("tenant-b-row");
    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select label from trade_tenancy_probe where id = ?", rowB)
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();
  }

  // ==========================================================================
  // 断言 2
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    // (1) 服务在事务外没有「环境租户」：app.current_tenant 要么未设置、要么已被复位成空串，
    //     两种情况归一后都是 null，于是任何租户的行都看不见 ——
    //     不存在一个可以被沿用的默认租户。
    assertThat(rawTenantSetting()).isNullOrEmpty();
    assertThat(effectiveTenant()).isNull();
    assertThat(visibleWithoutTenant()).as("未绑定租户时不应看到任何行").isZero();

    // (2) 事务内绑定的值精确等于可信参数，提交后立刻失效。
    //     「可信」指的是它来自 TenantTransactions.run 的 UUID 形参（服务端认证结果），
    //     而不是任何请求头；set_config 第三参 true 保证它活不过本事务。
    var bound =
        tenants.run(
            b,
            () -> {
              assertThat(effectiveTenant()).as("事务内只看得到本租户").isEqualTo(b);
              assertThat(labelsUnderCurrentBinding()).as("事务内只看得到本租户")
                  .containsExactly("tenant-b-row");
              return rawTenantSetting();
            });
    assertThat(bound).isEqualTo(b.toString());
    assertThat(effectiveTenant()).as("提交后租户上下文必须已清空").isNull();
    assertThat(visibleWithoutTenant()).isZero();

    // (3) HTTP 边界上没有伪造入口：X-Tenant-Id 没有任何消费者。
    //     trade 目前还没有端点，所以任意 trade 路径都是 404 —— 那个头无处可去，
    //     也就没有任何一次请求能让某个租户被绑进本服务。
    //     第一条 9.x 端点车道落地后，这里必须升级成
    //     「带合法会话 + 伪造 X-Tenant-Id → 仍只看到会话租户的行」，
    //     与 warehouse InventoryAvailabilityIntegrationTest 同一句断言。
    http.perform(get("/trade/orders").header("X-Tenant-Id", a)).andExpect(status().isNotFound());
    http.perform(post("/trade/orders").header("X-Tenant-Id", a).contentType("application/json")
            .content("{}"))
        .andExpect(status().isNotFound());
    assertThat(visibleWithoutTenant()).as("伪造请求头不得留下任何租户上下文").isZero();
  }

  // ==========================================================================
  // 断言 3
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证第 6 次读到的仍然是 B 而不是上一轮残留的 A。
    for (int i = 0; i < 5; i++) {
      assertThat(labelsOf(a)).as("第 %d 轮租户 A", i).containsExactly("tenant-a-row");
      assertThat(labelsOf(b)).as("第 %d 轮租户 B", i).containsExactly("tenant-b-row");
      assertThat(visibleWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(labelsOf(a)).containsExactly("tenant-a-row");
    assertThat(labelsOf(b)).containsExactly("tenant-b-row");
  }
}