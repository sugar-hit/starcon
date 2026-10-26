package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 17.7.2.2 平台运维视图。
 *
 * <p>重点不是「能存进去」，而是蓝图点名的硬要求真的成立：
 *
 * <ol>
 *   <li><b>跨租户视图只看技术指标不看业务数据</b> —— 这一条在**表结构层面**证明：
 *       指标表 / 告警表的每一个字符型列都挂着取值域 CHECK，库里没有自由文本列；
 *       四张表都没有金额语义列。集成测试逐列断言这一点（{@link
 *       #metricTablesCannotHoldBusinessData()}）。
 *   <li><b>平台管理员视角</b> —— 跨租户读出口只有 SECURITY DEFINER 函数，函数内部
 *       自己再判一次三道闸门；普通租户即使**带着** {@code identity.platform_ops.*}
 *       权限码（{@code create_child} 会全量授予）也一律 403。
 *   <li><b>成本是数量不是金额</b> —— 成本估算 = Σ(最近一次采样值 × 口径权重)，
 *       与发票 / 应收 / 付款 / 结算无关。
 * </ol>
 *
 * <p>另含多租户三断言（两租户互不可见 / 伪造租户头无效 / 连接池归还后不串租户）。
 * 时间字段按值断言：用 {@code Timestamp.valueOf(LocalDateTime)} 绑 timestamptz
 * 会静默偏 −28800 秒，只断言「没抛异常」是查不出来的。
 */
class PlatformOpsIntegrationTest extends IdentityIntegrationTestBase {

  private TenantTransactions tx() {
    return new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  /** 租户外直连（绕过应用连接池与租户上下文），用于结构级断言。 */
  private List<Map<String, Object>> query(String sql) throws Exception {
    var rows = new ArrayList<Map<String, Object>>();
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      var meta = rs.getMetaData();
      while (rs.next()) {
        var row = new LinkedHashMap<String, Object>();
        for (int i = 1; i <= meta.getColumnCount(); i++) row.put(meta.getColumnLabel(i), read(rs, i));
        rows.add(row);
      }
    }
    return rows;
  }

  /**
   * 读一列。
   *
   * <p>timestamptz 经原生 JDBC 读回来是 {@code java.sql.Timestamp}（它按**本地时区**解释
   * 那一刻），这里统一转成 {@link OffsetDateTime}，断言才能按绝对时刻比 ——
   * 直接 cast 会抛 ClassCastException，而且更糟的是「能 cast 但偏了 8 小时」。
   */
  private static Object read(java.sql.ResultSet rs, int index) throws Exception {
    var value = rs.getObject(index);
    if (value instanceof java.sql.Timestamp stamp) return stamp.toInstant().atOffset(ZoneOffset.UTC);
    if (value instanceof java.sql.Date date) return date.toLocalDate();
    return value;
  }

  private void asSuperuser(String statement) throws Exception {
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement()) {
      s.execute(statement);
    }
  }

  /** 直连库跑一条 count 查询（绕过应用连接池与 RLS），用于核对共享状态有没有被污染。 */
  private long superuserCount(String query) throws Exception {
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(query)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  // ------------------------------------------------------------------
  // 工具
  // ------------------------------------------------------------------

  /**
   * 专属平台运营身份：一个**只属于本次用例**的子租户所有者。
   *
   * <p>🔴 **绝不能用共享的 {@code login()}（local-admin / 根租户 OWNER）**：
   * 平台运营角色登记会把这个角色**永久挂到调用者本人**身上
   * （{@code platform_ops_register_role} 的最后一句是
   * {@code INSERT INTO role_assignment(..., a.membership_id, rid, 'ALL', a.user_id)}）。
   * 共享 owner 从 1 个角色变成 2 个之后，别的测试类里
   * 「按 grants.get(0) 取角色」的写法就会飘 —— 症状是某个与本模块无关的断言
   * 偶发 200/403 摇摆，极难定位。
   *
   * <p>闸门是**按租户**判的（{@code saas_platform_gate} 只要求
   * {@code saas_platform_role.tenant_id = 会话所在租户}，没有「必须是根租户」的要求），
   * 所以子租户的所有者可以在**自己租户里**登记平台运营角色并通过闸门 ——
   * 本模块用这个专属身份，让共享状态零污染。
   */
  private String platformOpsOwnerToken() throws Exception {
    String tag = suffix();
    String root = login();
    String opsUser = "ops-platform-" + tag.toLowerCase();
    request(
        root,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            "OPX" + tag,
            "name",
            "平台运维专属租户",
            "ownerUsername",
            opsUser,
            "ownerPassword",
            "test-member-password"),
        200);
    return loginUser(opsUser);
  }

  /** 平台运营角色登记（平台运营侧专用路径：浏览器打不到，要内部密钥）。 */
  private void registerPlatformRole(String token) throws Exception {
    http.perform(
            post("/identity/platform-ops/roles")
                .header("Authorization", "Bearer " + token)
                .header("X-Internal-Key", "test-key"))
        .andExpect(status().isOk());
  }

  /**
   * 平台运营身份：专属身份 + 登记平台运营角色。
   *
   * <p>登记走的是平台运营专用路径（要内部密钥），浏览器打不到 —— 这正是「平台运营侧专用路径」的形状。
   */
  private String platformToken() throws Exception {
    String token = platformOpsOwnerToken();
    registerPlatformRole(token);
    return token;
  }

  /**
   * 哨兵：共享 owner 的角色集合不得被本测试类扩大。
   *
   * <p>放在 {@code @AfterEach} 而不是某个用例里：污染一旦发生，当场红，而不是等到别的
   * 测试类炸出来（症状表现为「某个与本模块无关的断言偶发失败」）。
   */
  @AfterEach
  void sharedOwnerKeepsExactlyOneRole() throws Exception {
    String token = login();
    var me = request(token, "GET", "/auth/session", null, 200);
    var grants = me.get("grants");
    assertThat(grants).as("共享 owner 的角色集合不得被本测试类扩大").hasSize(1);
    assertThat(grants.get(0).get("roleCode").asText()).isEqualTo("OWNER");
    // 数据库层同样看一眼：role_assignment 里这个成员仍然只有一行。
    assertThat(
            superuserCount(
                "select count(*) from role_assignment where membership_id='"
                    + me.get("membershipId").asText()
                    + "'"))
        .as("共享 owner 在 role_assignment 里必须仍然只有一行")
        .isEqualTo(1);
  }

  /** 一个**普通租户**的所有者：带着 identity.platform_ops.* 权限码，但不是平台运营方。 */
  private String ordinaryTenantToken(String tag) throws Exception {
    String owner = login();
    String childUser = "ops-ordinary-" + tag.toLowerCase();
    request(
        owner,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            "OP" + tag,
            "name",
            "平台运维普通租户",
            "ownerUsername",
            childUser,
            "ownerPassword",
            "test-member-password"),
        200);
    return loginUser(childUser);
  }

  private UUID tenantOf(String token) throws Exception {
    return UUID.fromString(
        request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText());
  }

  /** 指标接入（服务端到服务端，要内部密钥）。 */
  private JsonNode ingest(List<Map<String, Object>> batch) throws Exception {
    return ingestExpecting(200, batch);
  }

  /** 指标接入并断言状态码；返回响应体（错误时用来断言可读原因）。 */
  private JsonNode ingestExpecting(int expected, List<Map<String, Object>> batch) throws Exception {
    var response =
        http.perform(
                post("/identity/platform-ops/ingest/metrics")
                    .header("X-Internal-Key", "test-key")
                    .contentType("application/json")
                    .content(json.writeValueAsString(batch)))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as("指标接入的状态码").isEqualTo(expected);
    var body = response.getContentAsString();
    return body.isEmpty() ? null : json.readTree(body);
  }

  /** 一条指标采样。 */
  private Map<String, Object> sample(
      UUID tenantId,
      String serviceCode,
      String metricCode,
      String unit,
      String value,
      OffsetDateTime from,
      OffsetDateTime to) {
    var input = new LinkedHashMap<String, Object>();
    input.put("tenantId", tenantId.toString());
    input.put("serviceCode", serviceCode);
    input.put("metricCode", metricCode);
    input.put("sampleValue", new BigDecimal(value));
    input.put("unit", unit);
    input.put("windowStart", from.toString());
    input.put("windowEnd", to.toString());
    input.put("source", "PUSH");
    return input;
  }

  private OffsetDateTime windowStart() {
    return OffsetDateTime.of(2026, 10, 23, 0, 0, 0, 0, ZoneOffset.UTC);
  }

  private OffsetDateTime windowEnd() {
    return OffsetDateTime.of(2026, 10, 23, 1, 0, 0, 0, ZoneOffset.UTC);
  }

  @BeforeEach
  void resetModule() throws Exception {
    // 指标与告警是事实：清理用 TRUNCATE（不触发行级触发器）。
    asSuperuser("truncate platform_ops_alert, platform_ops_metric");
    // 阈值与成本权重是出厂口径：恢复成种子值。
    asSuperuser(
        "update platform_ops_threshold set warn_threshold=0.010000,critical_threshold=0.050000,"
            + " comparison='GT',enabled=true,updated_at=now() where metric_code='ERROR_RATE'");
    asSuperuser(
        "update platform_ops_threshold set warn_threshold=100.000000,critical_threshold=1000.000000,"
            + " comparison='GT',enabled=true,updated_at=now() where metric_code='TASK_BACKLOG'");
  }

  // ------------------------------------------------------------------
  // 纪律一：平台管理员视角，普通租户不可访问
  // ------------------------------------------------------------------

  @Test
  void ordinaryTenantIsDeniedEvenThoughItOwnsThePermissionCodes() throws Exception {
    String tag = suffix();
    String ordinary = ordinaryTenantToken(tag);

    // 先证明这个普通租户**确实**带着平台运维权限码（create_child 会把 permission 全量授给 OWNER）。
    // 少了这一步，「403」可能只是因为权限码没发到，而不是闸门真的在起作用。
    var session = request(ordinary, "GET", "/auth/session", null, 200);
    var codes = new ArrayList<String>();
    session.get("permissions").forEach(p -> codes.add(p.asText()));
    assertThat(codes)
        .as("普通租户所有者带着平台运维权限码，这条断言才成立")
        .contains("identity.platform_ops.read", "identity.platform_ops.alert");

    // 平台运营域一律拒绝。
    request(ordinary, "GET", "/identity/platform-ops/capabilities", null, 403);
    request(ordinary, "GET", "/identity/platform-ops/overview", null, 403);
    request(ordinary, "GET", "/identity/platform-ops/tenants", null, 403);
    request(ordinary, "GET", "/identity/platform-ops/metrics", null, 403);
    request(ordinary, "GET", "/identity/platform-ops/alerts", null, 403);
    request(ordinary, "GET", "/identity/platform-ops/thresholds", null, 403);
    request(
        ordinary,
        "PUT",
        "/identity/platform-ops/thresholds/ERROR_RATE",
        Map.of(
            "warnThreshold", new BigDecimal("0.9"),
            "criticalThreshold", new BigDecimal("0.99"),
            "comparison",
            "GT"),
        403);
    request(
        ordinary,
        "POST",
        "/identity/platform-ops/alerts/" + UUID.randomUUID() + "/acknowledge",
        null,
        403);

    // 未登录一律 401。
    http.perform(get("/identity/platform-ops/tenants")).andExpect(status().isUnauthorized());
  }

  @Test
  void platformRoleRegistrationAndIngestAreServerToServerOnly() throws Exception {
    // 🔴 用**专属身份**做登记：登记会把平台运营角色挂到调用者本人身上，
    //    对共享 owner 做这件事会污染别的测试类依赖的共享状态。
    String token = platformOpsOwnerToken();
    // 没有内部密钥 → 拒绝。
    http.perform(post("/identity/platform-ops/roles").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
    // 伪造内部密钥同样拒绝。
    http.perform(
            post("/identity/platform-ops/roles")
                .header("Authorization", "Bearer " + token)
                .header("X-Internal-Key", "forged"))
        .andExpect(status().isForbidden());
    // 正确的内部密钥 → 登记成功。
    http.perform(
            post("/identity/platform-ops/roles")
                .header("Authorization", "Bearer " + token)
                .header("X-Internal-Key", "test-key"))
        .andExpect(status().isOk());

    // 指标接入同样要内部密钥。
    UUID tenant = tenantOf(token);
    var batch =
        List.of(
            sample(tenant, "identity", "ERROR_RATE", "RATIO", "0.01", windowStart(), windowEnd()));
    http.perform(
            post("/identity/platform-ops/ingest/metrics")
                .contentType("application/json")
                .content(json.writeValueAsString(batch)))
        .andExpect(status().isForbidden());
  }

  // ------------------------------------------------------------------
  // 纪律二：🔴 跨租户视图只看技术指标，不看业务数据
  // ------------------------------------------------------------------

  @Test
  void metricTablesCannotHoldBusinessData() throws Exception {
    // ① 本模块的四张表：**没有任何金额语义列**。
    //    「成本」是容量 / 调用量口径的估算数量，不是发票 / 应收 / 付款 / 结算。
    var moneyish =
        List.of(
            "amount", "price", "fee", "balance", "tax", "currency", "invoice", "payment",
            "receivable", "payable", "settle", "revenue", "cost_amount");
    for (String table :
        List.of(
            "platform_ops_metric",
            "platform_ops_alert",
            "platform_ops_threshold",
            "platform_ops_cost_model")) {
      var columns = query(
          "select column_name from information_schema.columns where table_name='"
              + table
              + "'");
      for (var column : columns) {
        var name = String.valueOf(column.get("column_name")).toLowerCase();
        for (String money : moneyish)
          assertThat(name)
              .as("表 " + table + " 不许有金额语义列（发现 " + name + "）")
              .doesNotContain(money);
      }
    }

    // ② 指标表 / 告警表的每一个**字符型列**都挂着取值域 CHECK ——
    //    这就是「业务数据塞不进来」的结构性证明：字符列的值只能来自固定枚举。
    //    判定用 pg_constraint.conkey（约束实际覆盖的列），不用字符串匹配约束表达式。
    var freeTextish =
        List.of(
            "name", "description", "note", "message", "detail", "content", "label", "comment",
            "remark", "title", "body", "reason", "address", "phone", "email",
            "customer", "order", "product", "sku", "buyer", "seller");
    for (String table : List.of("platform_ops_metric", "platform_ops_alert")) {
      var columns =
          query(
              "select column_name,data_type from information_schema.columns where table_name='"
                  + table
                  + "' order by ordinal_position");
      var checks =
          query(
              "select a.attname as column_name, c.conname as constraint_name"
                  + " from pg_attribute a"
                  + " join pg_class r on r.oid=a.attrelid"
                  + " join pg_constraint c on c.conrelid=r.oid and c.contype='c' and a.attnum=any(c.conkey)"
                  + " where r.relname='"
                  + table
                  + "' and a.attnum>0 and not a.attisdropped");
      assertThat(columns).as(table + " 必须有列").isNotEmpty();
      assertThat(checks).as(table + " 必须有 CHECK 约束").isNotEmpty();
      for (var column : columns) {
        var name = String.valueOf(column.get("column_name")).toLowerCase();
        var type = String.valueOf(column.get("data_type"));
        for (String free : freeTextish)
          assertThat(name)
              .as(table + "." + name + " 不许是自由文本列")
              .doesNotContain(free);
        if (type.equals("character varying") || type.equals("text") || type.equals("character")) {
          boolean constrained =
              checks.stream().anyMatch(c -> name.equals(String.valueOf(c.get("column_name"))));
          assertThat(constrained)
              .as(table + "." + name + " 必须有取值域 CHECK（否则它能装下任意业务文本）")
              .isTrue();
        }
      }
    }
  }

  @Test
  void ratioAndStateMetricsAreConstrainedByTheDatabase() throws Exception {
    UUID tenant = tenantOf(login());
    // 比率类指标写成 5（= 500%）必须被数据库挡下。
    assertThatThrownBy(
            () ->
                asSuperuser(
                    "insert into platform_ops_metric"
                        + " (tenant_id,service_code,metric_code,sample_value,unit,severity,window_start,window_end)"
                        + " values ('"
                        + tenant
                        + "','identity','ERROR_RATE',5,'RATIO','OK',now(),now()+interval '1 hour')"))
        .hasMessageContaining("platform_ops_metric");

    // 在线状态只能 0 / 1。
    assertThatThrownBy(
            () ->
                asSuperuser(
                    "insert into platform_ops_metric"
                        + " (tenant_id,service_code,metric_code,sample_value,unit,severity,window_start,window_end)"
                        + " values ('"
                        + tenant
                        + "','identity','ONLINE_STATUS',7,'STATE','OK',now(),now()+interval '1 hour')"))
        .hasMessageContaining("platform_ops_metric");

    // 指标码与单位必须成对。
    assertThatThrownBy(
            () ->
                asSuperuser(
                    "insert into platform_ops_metric"
                        + " (tenant_id,service_code,metric_code,sample_value,unit,severity,window_start,window_end)"
                        + " values ('"
                        + tenant
                        + "','identity','ERROR_RATE',1,'COUNT','OK',now(),now()+interval '1 hour')"))
        .hasMessageContaining("platform_ops_metric");
  }

  // ------------------------------------------------------------------
  // 纪律三：多租户三断言
  // ------------------------------------------------------------------

  @Test
  void twoTenantsCannotSeeEachOthersMetrics() throws Exception {
    String tag = suffix();
    String platform = platformToken();
    String ordinary = ordinaryTenantToken(tag);
    UUID platformTenant = tenantOf(platform);
    UUID ordinaryTenant = tenantOf(ordinary);

    ingest(
        List.of(
            sample(
                platformTenant, "identity", "ERROR_RATE", "RATIO", "0.02", windowStart(), windowEnd()),
            sample(
                ordinaryTenant,
                "catalog",
                "TASK_BACKLOG",
                "COUNT",
                "7",
                windowStart(),
                windowEnd())));

    // 平台运营看得到两个租户（跨租户读是它的职责）。
    var board = request(platform, "GET", "/identity/platform-ops/tenants", null, 200);
    assertThat(board).isNotEmpty();
    assertThat(tenantIds(board)).contains(platformTenant, ordinaryTenant);

    // 但租户自检只看得到自己：两个租户互不可见。
    var mine = request(ordinary, "GET", "/identity/platform-ops/self/metrics", null, 200);
    assertThat(tenantIds(mine)).containsOnly(ordinaryTenant);
    var theirs = request(platform, "GET", "/identity/platform-ops/self/metrics", null, 200);
    assertThat(tenantIds(theirs)).containsOnly(platformTenant);
  }

  @Test
  void forgedTenantHeaderDoesNotChangeAnything() throws Exception {
    String tag = suffix();
    String ordinary = ordinaryTenantToken(tag);
    UUID ordinaryTenant = tenantOf(ordinary);
    UUID platformTenant = tenantOf(login());

    ingest(
        List.of(
            sample(
                ordinaryTenant, "identity", "ERROR_RATE", "RATIO", "0.03", windowStart(), windowEnd()),
            sample(
                platformTenant,
                "catalog",
                "TASK_BACKLOG",
                "COUNT",
                "9",
                windowStart(),
                windowEnd())));

    // 显式伪造 X-Tenant-Id 指向另一个租户：结果必须不变。
    var response =
        http.perform(
                get("/identity/platform-ops/self/metrics")
                    .header("Authorization", "Bearer " + ordinary)
                    .header("X-Tenant-Id", platformTenant.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var body = json.readTree(response.getContentAsString());
    assertThat(tenantIds(body)).containsOnly(ordinaryTenant);
  }

  @Test
  void pooledConnectionDoesNotLeakTenantContext() throws Exception {
    String tag = suffix();
    String platform = platformToken();
    String ordinary = ordinaryTenantToken(tag);
    UUID platformTenant = tenantOf(platform);
    UUID ordinaryTenant = tenantOf(ordinary);

    ingest(
        List.of(
            sample(
                platformTenant, "identity", "ERROR_RATE", "RATIO", "0.04", windowStart(), windowEnd()),
            sample(
                ordinaryTenant,
                "catalog",
                "TASK_BACKLOG",
                "COUNT",
                "11",
                windowStart(),
                windowEnd())));

    // 连接池在基类里被压到 1：每个请求必然复用同一条物理连接。
    // 交替读写 4 轮，任何一轮串租户都会立刻暴露。
    for (int round = 0; round < 4; round++) {
      assertThat(tenantIds(request(platform, "GET", "/identity/platform-ops/self/metrics", null, 200)))
          .as("第 " + round + " 轮：平台租户只看得到自己")
          .containsOnly(platformTenant);
      assertThat(
              tenantIds(request(ordinary, "GET", "/identity/platform-ops/self/metrics", null, 200)))
          .as("第 " + round + " 轮：普通租户只看得到自己")
          .containsOnly(ordinaryTenant);
    }
  }

  @Test
  void tenantTablesAreForceRlsAndPlatformTablesAreNot() throws Exception {
    var rows =
        query(
            "select c.relname, c.relrowsecurity, c.relforcerowsecurity,"
                + " (select count(*) from information_schema.columns col"
                + "  where col.table_name=c.relname and col.column_name='tenant_id') as tenant_columns"
                + " from pg_class c where c.relname in"
                + " ('platform_ops_metric','platform_ops_alert','platform_ops_threshold','platform_ops_cost_model')"
                + " order by c.relname");
    assertThat(rows).hasSize(4);
    for (var row : rows) {
      var name = String.valueOf(row.get("relname"));
      var tenantColumns = ((Number) row.get("tenant_columns")).intValue();
      if (name.equals("platform_ops_metric") || name.equals("platform_ops_alert")) {
        assertThat(tenantColumns).as(name + " 必须带 tenant_id").isEqualTo(1);
        assertThat((Boolean) row.get("relrowsecurity")).as(name + " 必须 ENABLE RLS").isTrue();
        assertThat((Boolean) row.get("relforcerowsecurity")).as(name + " 必须 FORCE RLS").isTrue();
      } else {
        assertThat(tenantColumns).as(name + " 是平台级表，不带 tenant_id").isZero();
        assertThat((Boolean) row.get("relrowsecurity"))
            .as(name + " 是平台级表，不参与租户隔离")
            .isFalse();
      }
    }
  }

  // ------------------------------------------------------------------
  // 纪律四：看板读的是真数据
  // ------------------------------------------------------------------

  @Test
  void dashboardShowsRealTechnicalMetricsAcrossTenants() throws Exception {
    String tag = suffix();
    String platform = platformToken();
    String ordinary = ordinaryTenantToken(tag);
    UUID platformTenant = tenantOf(platform);
    UUID ordinaryTenant = tenantOf(ordinary);

    ingest(
        List.of(
            sample(
                platformTenant,
                "identity",
                "ERROR_RATE",
                "RATIO",
                "0.060000",
                windowStart(),
                windowEnd()),
            sample(
                platformTenant,
                "identity",
                "TASK_BACKLOG",
                "COUNT",
                "1500.000000",
                windowStart(),
                windowEnd()),
            sample(
                platformTenant,
                "identity",
                "ONLINE_STATUS",
                "STATE",
                "1",
                windowStart(),
                windowEnd()),
            sample(
                ordinaryTenant,
                "catalog",
                "ONLINE_STATUS",
                "STATE",
                "0",
                windowStart(),
                windowEnd())));

    var board = request(platform, "GET", "/identity/platform-ops/tenants", null, 200);
    var platformRow = findTenant(board, platformTenant);
    var ordinaryRow = findTenant(board, ordinaryTenant);

    assertThat(platformRow.get("online").asBoolean())
        .as("在线状态采样为 1 ⇒ 在线")
        .isTrue();
    assertThat(new BigDecimal(platformRow.get("errorRate").asText()))
        .isEqualByComparingTo(new BigDecimal("0.060000"));
    assertThat(new BigDecimal(platformRow.get("taskBacklog").asText()))
        .isEqualByComparingTo(new BigDecimal("1500"));
    assertThat(ordinaryRow.get("online").asBoolean())
        .as("在线状态采样为 0 ⇒ 离线")
        .isFalse();

    // 没有采样的指标是 null（**不是 0**）：把「没数据」说成「指标是 0」是把缺口说成了事实。
    assertThat(ordinaryRow.get("errorRate").isNull())
        .as("普通租户没有错误率采样，必须是 null 而不是 0")
        .isTrue();

    // 汇总与逐租户行同源。
    var overview = request(platform, "GET", "/identity/platform-ops/overview", null, 200);
    assertThat(overview.get("onlineTenantCount").asLong()).isEqualTo(1);
    assertThat(overview.get("offlineTenantCount").asLong()).isEqualTo(1);
    assertThat(overview.get("tenantCount").asLong()).isEqualTo(board.size());
  }

  @Test
  void metricsEndpointPaginatesForReal() throws Exception {
    String platform = platformToken();
    UUID tenant = tenantOf(platform);
    var batch = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 7; i++) {
      batch.add(
          sample(
              tenant,
              "identity",
              "ERROR_RATE",
              "RATIO",
              "0.0" + i,
              windowStart().plusHours(i),
              windowEnd().plusHours(i)));
    }
    ingest(batch);

    var first = request(platform, "GET", "/identity/platform-ops/metrics?limit=3&offset=0", null, 200);
    var second = request(platform, "GET", "/identity/platform-ops/metrics?limit=3&offset=3", null, 200);
    assertThat(first.get("items").size()).isEqualTo(3);
    assertThat(second.get("items").size()).isEqualTo(3);
    assertThat(first.get("total").asLong()).isEqualTo(7);
    assertThat(second.get("total").asLong()).isEqualTo(7);
    assertThat(itemIds(first.get("items")))
        .as("两页不能重复：真分页不是把同一页复制两遍")
        .doesNotContainAnyElementsOf(itemIds(second.get("items")));

    // 按指标码过滤也是真的。
    var filtered =
        request(platform, "GET", "/identity/platform-ops/metrics?metricCode=ERROR_RATE", null, 200);
    assertThat(filtered.get("total").asLong()).isEqualTo(7);
    var empty =
        request(platform, "GET", "/identity/platform-ops/metrics?metricCode=AVAILABILITY", null, 200);
    assertThat(empty.get("total").asLong()).isZero();
    assertThat(empty.get("items").size()).isZero();
  }

  // ------------------------------------------------------------------
  // 纪律五：告警真的会被阈值触发、被流转
  // ------------------------------------------------------------------

  @Test
  void thresholdBreachRaisesAnAlertAndRecoveryClosesIt() throws Exception {
    String platform = platformToken();
    UUID tenant = tenantOf(platform);

    // 错误率 0.08 > critical 0.05 ⇒ 严重告警。
    ingest(
        List.of(
            sample(
                tenant, "identity", "ERROR_RATE", "RATIO", "0.080000", windowStart(), windowEnd())));
    var alerts = request(platform, "GET", "/identity/platform-ops/alerts", null, 200);
    assertThat(alerts.get("total").asLong()).isEqualTo(1);
    var alert = alerts.get("items").get(0);
    assertThat(alert.get("alertCode").asText()).isEqualTo("ERROR_RATE_HIGH");
    assertThat(alert.get("severity").asText()).isEqualTo("CRITICAL");
    assertThat(alert.get("status").asText()).isEqualTo("OPEN");
    assertThat(new BigDecimal(alert.get("observedValue").asText()))
        .isEqualByComparingTo(new BigDecimal("0.08"));
    var alertId = alert.get("id").asText();

    // 确认。
    var acknowledged =
        request(
            platform,
            "POST",
            "/identity/platform-ops/alerts/" + alertId + "/acknowledge",
            null,
            200);
    assertThat(acknowledged.get("status").asText()).isEqualTo("ACKNOWLEDGED");
    assertThat(acknowledged.get("acknowledgedAt").isNull()).isFalse();

    // 回到阈值以内 ⇒ 自动闭环。
    ingest(
        List.of(
            sample(
                tenant,
                "identity",
                "ERROR_RATE",
                "RATIO",
                "0.001000",
                windowStart().plusHours(1),
                windowEnd().plusHours(1))));
    var closed = request(platform, "GET", "/identity/platform-ops/alerts", null, 200);
    assertThat(closed.get("total").asLong()).isEqualTo(1);
    assertThat(closed.get("items").get(0).get("status").asText()).isEqualTo("RESOLVED");
    assertThat(closed.get("items").get(0).get("resolvedAt").isNull()).isFalse();
  }

  @Test
  void thresholdsAreConfigurableByPlatformOps() throws Exception {
    String platform = platformToken();
    var updated =
        request(
            platform,
            "PUT",
            "/identity/platform-ops/thresholds/TASK_BACKLOG",
            Map.of(
                "warnThreshold", new BigDecimal("5"),
                "criticalThreshold", new BigDecimal("10"),
                "comparison",
                "GT",
                "enabled",
                true),
            200);
    assertThat(new BigDecimal(updated.get("warnThreshold").asText()))
        .isEqualByComparingTo(new BigDecimal("5"));
    assertThat(new BigDecimal(updated.get("criticalThreshold").asText()))
        .isEqualByComparingTo(new BigDecimal("10"));

    // 两级阈值与比较方向不自洽 ⇒ 拒绝。
    request(
        platform,
        "PUT",
        "/identity/platform-ops/thresholds/TASK_BACKLOG",
        Map.of(
            "warnThreshold", new BigDecimal("50"),
            "criticalThreshold", new BigDecimal("10"),
            "comparison",
            "GT"),
        400);
  }

  // ------------------------------------------------------------------
  // 纪律六：成本是数量；时间零漂移
  // ------------------------------------------------------------------

  @Test
  void costEstimateIsAWeightedQuantityNotAnAmount() throws Exception {
    String platform = platformToken();
    UUID tenant = tenantOf(platform);
    ingest(
        List.of(
            sample(
                tenant,
                "identity",
                "API_CALL_COUNT",
                "COUNT",
                "1000",
                windowStart(),
                windowEnd()),
            sample(
                tenant,
                "identity",
                "AI_CALL_COUNT",
                "COUNT",
                "10",
                windowStart(),
                windowEnd())));

    var board = request(platform, "GET", "/identity/platform-ops/tenants", null, 200);
    var row = findTenant(board, tenant);
    // 1000 × 0.01 + 10 × 0.05 = 10.5（估算单位数量）。
    assertThat(new BigDecimal(row.get("costEstimate").asText()))
        .isEqualByComparingTo(new BigDecimal("10.500000"));
  }

  @Test
  void timestampsRoundTripWithoutDrift() throws Exception {
    String platform = platformToken();
    UUID tenant = tenantOf(platform);
    // 用 +08:00 偏移写入：写回后必须还是同一个绝对时刻。
    var from = OffsetDateTime.of(2026, 10, 23, 8, 0, 0, 0, ZoneOffset.ofHours(8));
    var to = OffsetDateTime.of(2026, 10, 23, 9, 0, 0, 0, ZoneOffset.ofHours(8));
    ingest(List.of(sample(tenant, "identity", "ERROR_RATE", "RATIO", "0.01", from, to)));

    var rows =
        query(
            "select window_start,window_end from platform_ops_metric where tenant_id='"
                + tenant
                + "' order by created_at desc limit 1");
    assertThat(rows).hasSize(1);
    assertThat(((OffsetDateTime) rows.get(0).get("window_start")).toInstant())
        .isEqualTo(from.toInstant());
    assertThat(((OffsetDateTime) rows.get(0).get("window_end")).toInstant())
        .isEqualTo(to.toInstant());
  }

  @Test
  void ingestRejectsMalformedSamples() throws Exception {
    String platform = platformToken();
    UUID tenant = tenantOf(platform);
    // 单位与指标码不匹配。
    ingestExpecting(
        400,
        List.of(sample(tenant, "identity", "ERROR_RATE", "COUNT", "1", windowStart(), windowEnd())));
    // 采集窗口结束时刻早于开始时刻。
    ingestExpecting(
        400,
        List.of(
            sample(tenant, "identity", "ERROR_RATE", "RATIO", "0.1", windowEnd(), windowStart())));
    // 未知的技术指标码。
    ingestExpecting(
        400,
        List.of(
            sample(tenant, "identity", "ORDER_AMOUNT", "COUNT", "1", windowStart(), windowEnd())));
    // 比率类指标超过 1。
    ingestExpecting(
        400,
        List.of(sample(tenant, "identity", "ERROR_RATE", "RATIO", "5", windowStart(), windowEnd())));
    // 负数采样值。
    ingestExpecting(
        400,
        List.of(sample(tenant, "identity", "ERROR_RATE", "RATIO", "-1", windowStart(), windowEnd())));
    // 不存在的租户。
    ingestExpecting(
        400,
        List.of(
            sample(
                UUID.randomUUID(),
                "identity",
                "ERROR_RATE",
                "RATIO",
                "0.1",
                windowStart(),
                windowEnd())));

    // 一次被拒的接入**不许留下半条数据**。
    var rows =
        query("select count(*) as c from platform_ops_metric where tenant_id='" + tenant + "'");
    assertThat(((Number) rows.get(0).get("c")).intValue())
        .as("被拒的接入一条都不许入库")
        .isZero();
  }

  @Test
  void capabilitiesDeclareWhatIsNotDelivered() throws Exception {
    String platform = platformToken();
    var capabilities = request(platform, "GET", "/identity/platform-ops/capabilities", null, 200);
    var dimensions = capabilities.get("dimensions");
    assertThat(dimensions.size()).isGreaterThan(0);
    var byName = new java.util.HashMap<String, JsonNode>();
    for (var d : dimensions) byName.put(d.get("dimension").asText(), d);

    assertThat(byName.get("CROSS_TENANT_METRICS").get("available").asBoolean()).isTrue();
    assertThat(byName.get("PLATFORM_HEALTH_DASHBOARD").get("available").asBoolean()).isTrue();

    // 未交付的维度必须同时给出 ownerModule 与 impact（空列表与 0 冒充「没数据」是把缺口说成了事实）。
    for (var d : dimensions) {
      if (d.get("available").asBoolean()) continue;
      assertThat(d.get("ownerModule").asText())
          .as("available=false 必须写明归属模块：" + d.get("dimension").asText())
          .isNotBlank();
      assertThat(d.get("impact").asText())
          .as("available=false 必须写明影响：" + d.get("dimension").asText())
          .isNotBlank();
    }
    assertThat(byName.get("UPSTREAM_COLLECTION").get("available").asBoolean()).isFalse();
  }

  // ------------------------------------------------------------------
  // 小工具
  // ------------------------------------------------------------------

  private List<UUID> tenantIds(JsonNode rows) {
    var ids = new ArrayList<UUID>();
    rows.forEach(
        row -> {
          var node = row.get("tenantId");
          if (node != null && !node.isNull()) ids.add(UUID.fromString(node.asText()));
        });
    return ids;
  }

  private List<String> itemIds(JsonNode rows) {
    var ids = new ArrayList<String>();
    rows.forEach(
        row -> {
          var node = row.get("id");
          if (node != null && !node.isNull()) ids.add(node.asText());
        });
    return ids;
  }

  private JsonNode findTenant(JsonNode rows, UUID tenantId) {
    for (var row : rows)
      if (tenantId.toString().equals(row.get("tenantId").asText())) return row;
    throw new AssertionError("看板里没有租户 " + tenantId);
  }
}
