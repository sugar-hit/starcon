package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 18.1.1.1 应用与凭证 —— {@code platform.app} 权限码登记（V378）。
 *
 * <p>被测对象是 <b>identity 服务里的权限码登记</b>，不是 platform 的 23 个端点本身：
 *
 * <ul>
 *   <li>platform 的 {@code AppCredentialController} 用 {@code access.require(bearer,
 *       "platform.app." + permission)} 强制鉴权，实际只用到 4 个动作：{@code read / create /
 *       update / revoke}；
 *   <li>但 identity 的 {@code permission} 表里从来没有注册过 {@code platform.app} 任何一行 ——
 *       而 {@code role_permission.permission_code} 有指向 {@code permission(code)} 的外键 ——
 *       于是「没有任何角色可能持有 {@code platform.app.read}」，这些端点在真实环境里恒 403。
 * </ul>
 *
 * <p>本类断言四件事：
 *
 * <ol>
 *   <li><b>4 个权限码真的登记了</b>：{@code platform.app.read/create/update/revoke} 存在，
 *       {@code page_code = 'platform.app'}、{@code action} 正确，{@code page_resource} 里也有
 *       {@code platform.app} 这一行；
 *   <li><b>存量 OWNER 角色也拿到了这 4 个码</b> —— 本条是本任务的核心断言：补权语句必须覆盖
 *       {@code platform.app.%} <b>整个集合</b>，只补 {@code read} 一条的话，
 *       {@code create / update / revoke} 对既有租户不可见；
 *   <li><b>迁移幂等</b>：同一段 SQL 跑两次不报错、不产生重复行；
 *   <li><b>多租户三断言</b>（两租户互不可见 / 伪造租户头无效 / 连接池归还后不串租户）。
 * </ol>
 *
 * <p><b>迁移文件本体参与测试</b>：补权与幂等断言读的是 classpath 上的
 * {@code V378__platform_app_permission.sql}，而不是在测试里复刻一份 SQL ——
 * 复刻的那份与真正上线的那份迟早会漂移，漂移了就测不到真东西。
 *
 * <p><b>重跑迁移用迁移身份</b>（容器超级用户），与 Flyway 真正执行 V378 的身份一致：
 * 迁移脚本要在<b>没有租户上下文</b>的情况下给所有租户的存量 OWNER 补权，
 * 用应用身份跑会撞 RLS、只能覆盖一个租户，那是另一件事、也不是上线时的行为。
 */
class PlatformAppPermissionIntegrationTest extends IdentityIntegrationTestBase {

  /** platform 服务的 AppCredentialController 实际用到的 4 个动作。 */
  private static final List<String> APP_ACTIONS = List.of("read", "create", "update", "revoke");

  /** 登记出去的 4 个权限码。 */
  private static final List<String> APP_CODES =
      APP_ACTIONS.stream().map(action -> "platform.app." + action).toList();

  // ------------------------------------------------------------------
  // 小工具
  // ------------------------------------------------------------------

  private TenantTransactions tx() {
    return new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  /** 在指定租户的事务里查行（租户上下文由 {@code app.current_tenant} 设置，RLS 生效）。 */
  private List<Record> rowsIn(UUID tenant, String statement, Object... bindings) {
    return tx().run(tenant, () -> List.copyOf(sql.fetch(statement, bindings)));
  }

  /** 在指定租户的事务里执行写操作。 */
  private int execIn(UUID tenant, String statement, Object... bindings) {
    return tx().run(tenant, () -> sql.execute(statement, bindings));
  }

  private UUID tenantOf(String token) throws Exception {
    return UUID.fromString(
        request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText());
  }

  /** 某个租户自己的 OWNER 角色 id（RLS 保证查到的就是本租户那一个）。 */
  private UUID ownerRoleId(UUID tenant) {
    var rows = rowsIn(tenant, "select id from role where code='OWNER'");
    assertThat(rows).as("每个租户有且只有一个 OWNER 角色").hasSize(1);
    return rows.get(0).get("id", UUID.class);
  }

  /** 某个角色的 platform.app.* 授权码。 */
  private List<String> appCodesOf(UUID tenant, UUID roleId) {
    return rowsIn(
            tenant,
            "select permission_code from role_permission"
                + " where role_id=? and permission_code like 'platform.app.%' order by permission_code",
            roleId)
        .stream()
        .map(row -> row.get("permission_code", String.class))
        .toList();
  }

  /** 建一个子租户并返回子租户所有者的 token。 */
  private String childTenant(String parentToken, String tag) throws Exception {
    request(
        parentToken,
        "POST",
        "/identity/children",
        Map.of(
            "code", "APP" + tag,
            "name", "应用与凭证子租户 " + tag,
            "ownerUsername", "app-" + tag.toLowerCase(),
            "ownerPassword", "test-member-password"),
        200);
    return loginUser("app-" + tag.toLowerCase());
  }

  // ------------------------------------------------------------------
  // 迁移文件本体
  // ------------------------------------------------------------------

  /**
   * 读 classpath 上的 V378 迁移文件，切成一条条语句。
   *
   * <p>整行 {@code --} 注释直接丢掉，剩下的按 {@code ;} 切分 —— 本迁移没有 {@code $$} 块，
   * 也没有字符串字面量里带 {@code ;}，所以这样切是安全的。
   */
  private static List<String> migrationStatements() throws Exception {
    try (var in =
        PlatformAppPermissionIntegrationTest.class.getResourceAsStream(
            "/db/migration/V378__platform_app_permission.sql")) {
      assertThat(in).as("V378 迁移文件必须在 classpath 上").isNotNull();
      var text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      var body =
          text.lines()
              .filter(line -> !line.stripLeading().startsWith("--"))
              .collect(Collectors.joining("\n"));
      return Arrays.stream(body.split(";"))
          .map(String::strip)
          .filter(statement -> !statement.isEmpty())
          .toList();
    }
  }

  /**
   * 以迁移身份（容器超级用户）重跑 V378 的全部语句。
   *
   * <p>与 Flyway 真正执行 V378 的身份一致：迁移脚本要在<b>没有租户上下文</b>的情况下
   * 给所有租户的存量 OWNER 补权。
   */
  private void runMigration() throws Exception {
    var statements = migrationStatements();
    assertThat(statements).as("V378 应当是三段式（页面 / 权限码 / OWNER 补权）").hasSize(3);
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement()) {
      for (var statement : statements) {
        s.execute(statement);
      }
    }
  }

  // ------------------------------------------------------------------
  // 一、权限码登记
  // ------------------------------------------------------------------

  @Test
  void 四个权限码已登记且归属platform_app页面() {
    var rows = sql.fetch("select code,page_code,action from permission where page_code='platform.app'");
    assertThat(rows).as("platform.app 下面只应有这 4 个动作").hasSize(4);

    assertThat(rows.stream().map(row -> row.get("code", String.class)).toList())
        .as("4 个权限码必须齐")
        .containsExactlyInAnyOrderElementsOf(APP_CODES);
    assertThat(rows.stream().map(row -> row.get("action", String.class)).toList())
        .as("action 必须与权限码后缀一致")
        .containsExactlyInAnyOrderElementsOf(APP_ACTIONS);
    for (var row : rows) {
      assertThat(row.get("page_code", String.class)).isEqualTo("platform.app");
      assertThat("platform.app." + row.get("action", String.class))
          .as("权限码必须由 page_code 与 action 拼成")
          .isEqualTo(row.get("code", String.class));
    }

    // page_resource 必须有这一行（permission.page_code 的外键指向它）。
    var page = sql.fetchOne("select code,name from page_resource where code='platform.app'");
    assertThat(page).as("page_resource 里必须有 platform.app 这一行").isNotNull();
    assertThat(page.get("name", String.class)).as("页面名不能为空").isNotBlank();
  }

  @Test
  void 权限码目录接口能查到这四个码() throws Exception {
    // 不只是表里有 —— 角色管理页面读的是 GET /identity/permissions，
    // 授权界面看不到的码等于没登记。
    var list = request(login(), "GET", "/identity/permissions", null, 200);
    var codes = list.findValuesAsText("code");
    assertThat(codes).as("权限码目录里必须能看到这 4 个码").containsAll(APP_CODES);

    var pageCodes = list.findValuesAsText("pageCode");
    assertThat(pageCodes).contains("platform.app");
  }

  // ------------------------------------------------------------------
  // 二、存量 OWNER 补权（本任务的核心断言）
  // ------------------------------------------------------------------

  @Test
  void 存量租户的OWNER角色补权后拿到全部四个码() throws Exception {
    String parent = login();
    String tag = suffix();
    String child = childTenant(parent, tag);
    UUID childId = tenantOf(child);
    UUID ownerRole = ownerRoleId(childId);

    // 新租户的 OWNER 由 create_child 把 permission 表里的**全部**权限码授过去，
    // 所以新租户天生就有这 4 个码 —— 这一步证明「新接入」的路径是通的。
    assertThat(appCodesOf(childId, ownerRole))
        .as("新建租户的 OWNER 角色应当拿到全部 4 个码")
        .containsExactlyInAnyOrderElementsOf(APP_CODES);

    // 模拟「存量 OWNER」：这个角色是在这 4 个码登记**之前**建的，所以拿不到它们。
    // 删掉这 4 行，等价于「这个角色比权限码更老」。
    execIn(
        childId,
        "delete from role_permission where role_id=? and permission_code like 'platform.app.%'",
        ownerRole);
    assertThat(appCodesOf(childId, ownerRole))
        .as("前置条件：此刻这个 OWNER 角色拿不到 platform.app 的任何码")
        .isEmpty();

    // 跑迁移里的补权语句。
    runMigration();

    // 🔴 核心断言：补权覆盖**整个 platform.app.% 集合**，不是只补 read 一条。
    // 只补 read 的话，create / update / revoke 对既有租户的 OWNER 依然不可见。
    assertThat(appCodesOf(childId, ownerRole))
        .as("存量 OWNER 必须拿到全部 4 个码，而不只是 read")
        .containsExactlyInAnyOrderElementsOf(APP_CODES);
  }

  // ------------------------------------------------------------------
  // 三、迁移幂等
  // ------------------------------------------------------------------

  @Test
  void 迁移重复执行两次不报错也不产生重复行() throws Exception {
    long permissionsBefore =
        sql.fetch("select code from permission where page_code='platform.app'").size();
    long pagesBefore = sql.fetch("select code from page_resource where code='platform.app'").size();
    long grantsBefore =
        sql.fetch("select tenant_id,role_id,permission_code from role_permission"
                + " where permission_code like 'platform.app.%'")
            .size();

    // 迁移在容器启动时已由 Flyway 跑过一次，这里再跑两遍 = 共三遍。
    runMigration();
    runMigration();

    assertThat(sql.fetch("select code from permission where page_code='platform.app'").size())
        .as("重复执行不得新增权限码行")
        .isEqualTo((int) permissionsBefore);
    assertThat(sql.fetch("select code from page_resource where code='platform.app'").size())
        .as("重复执行不得新增页面行")
        .isEqualTo((int) pagesBefore);
    assertThat(sql.fetch("select tenant_id,role_id,permission_code from role_permission"
            + " where permission_code like 'platform.app.%'")
            .size())
        .as("重复执行不得新增授权行")
        .isEqualTo((int) grantsBefore);

    // 授权表不得出现「同一个 (租户, 角色, 权限码) 多行」。
    var duplicates =
        sql.fetch(
            "select tenant_id,role_id,permission_code,count(*) as n from role_permission"
                + " where permission_code like 'platform.app.%'"
                + " group by tenant_id,role_id,permission_code having count(*)>1");
    assertThat(duplicates).as("重复执行不得产生重复的授权行").isEmpty();
  }

  // ------------------------------------------------------------------
  // 四、多租户三断言
  // ------------------------------------------------------------------

  /**
   * 断言一：两租户互不可见。
   *
   * <p>{@code role} 与 {@code role_permission} 都是 {@code ENABLE + FORCE RLS}
   * （V1 建立，policy 只认 {@code app.current_tenant}），所以在子租户的事务里直查
   * 父租户的角色 id 必须读到<b>零行</b>。
   */
  @Test
  void 两租户的OWNER角色与授权行互不可见() throws Exception {
    String parent = login();
    String tag = suffix();
    String child = childTenant(parent, tag);
    UUID parentId = tenantOf(parent);
    UUID childId = tenantOf(child);
    UUID parentOwner = ownerRoleId(parentId);
    UUID childOwner = ownerRoleId(childId);

    // 各自看得见自己的 OWNER 授权行。
    assertThat(appCodesOf(parentId, parentOwner))
        .as("父租户看得见自己的 OWNER 授权行")
        .containsExactlyInAnyOrderElementsOf(APP_CODES);
    assertThat(appCodesOf(childId, childOwner))
        .as("子租户看得见自己的 OWNER 授权行")
        .containsExactlyInAnyOrderElementsOf(APP_CODES);

    // 子租户看不见父租户的角色行与授权行。
    assertThat(rowsIn(childId, "select id from role where id=?", parentOwner))
        .as("子租户不得看见父租户的角色行")
        .isEmpty();
    assertThat(rowsIn(childId, "select permission_code from role_permission where role_id=?", parentOwner))
        .as("子租户不得看见父租户的授权行")
        .isEmpty();

    // 反向亦然。
    assertThat(rowsIn(parentId, "select id from role where id=?", childOwner))
        .as("父租户不得看见子租户的角色行")
        .isEmpty();
    assertThat(rowsIn(parentId, "select permission_code from role_permission where role_id=?", childOwner))
        .as("父租户不得看见子租户的授权行")
        .isEmpty();

    // HTTP 层同样成立：角色列表接口按租户上下文过滤。
    var childRoles = request(child, "GET", "/identity/roles", null, 200);
    assertThat(childRoles.toString())
        .as("子租户的角色列表里只有子租户的角色")
        .contains(childOwner.toString())
        .doesNotContain(parentOwner.toString());
  }

  /**
   * 断言二：伪造 {@code X-Tenant-Id} 头无效。
   *
   * <p>租户来自已认证用户的成员关系，这个头在整条链路上没有任何读取点。
   */
  @Test
  void 伪造租户头无效() throws Exception {
    String parent = login();
    String tag = suffix();
    String child = childTenant(parent, tag);
    UUID parentId = tenantOf(parent);
    UUID childId = tenantOf(child);
    UUID parentOwner = ownerRoleId(parentId);
    UUID childOwner = ownerRoleId(childId);

    // 伪造一个随机租户 id 与**子租户真实**租户 id：结果都必须不变。
    for (String forged : List.of(UUID.randomUUID().toString(), childId.toString())) {
      var body =
          http.perform(
                  get("/identity/roles")
                      .header("Authorization", "Bearer " + parent)
                      .header("X-Tenant-Id", forged))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();

      assertThat(body)
          .as("伪造 X-Tenant-Id=" + forged + " 不得改变可见范围")
          .contains(parentOwner.toString())
          .doesNotContain(childOwner.toString());
    }

    // 伪造头的**写**同样无效：这里用授权行做等价证明 ——
    // 父租户 OWNER 的 platform.app 授权行一个都没少，子租户 OWNER 的也没被父租户碰到。
    assertThat(appCodesOf(parentId, parentOwner))
        .as("伪造租户头不得让父租户丢掉自己的授权行")
        .containsExactlyInAnyOrderElementsOf(APP_CODES);
    assertThat(appCodesOf(childId, childOwner))
        .as("伪造租户头不得把授权行写进子租户")
        .containsExactlyInAnyOrderElementsOf(APP_CODES);
  }

  /**
   * 断言三：连接池归还后不串租户。
   *
   * <p>池压到 1 条连接（见基类），所以下面每一轮都必然复用同一条物理连接。
   */
  @Test
  void 连接池归还后多轮交替读写不串租户() throws Exception {
    String parent = login();
    String tag = suffix();
    String child = childTenant(parent, tag);
    UUID parentId = tenantOf(parent);
    UUID childId = tenantOf(child);
    UUID parentOwner = ownerRoleId(parentId);
    UUID childOwner = ownerRoleId(childId);

    for (int i = 0; i < 5; i++) {
      assertThat(appCodesOf(parentId, parentOwner))
          .as("第 %d 轮：父租户只看得见自己的 OWNER 授权行", i)
          .containsExactlyInAnyOrderElementsOf(APP_CODES);
      assertThat(appCodesOf(childId, childOwner))
          .as("第 %d 轮：子租户只看得见自己的 OWNER 授权行", i)
          .containsExactlyInAnyOrderElementsOf(APP_CODES);

      assertThat(rowsIn(childId, "select permission_code from role_permission where role_id=?", parentOwner))
          .as("第 %d 轮：子租户读不得看见父租户的授权行", i)
          .isEmpty();
      assertThat(rowsIn(parentId, "select permission_code from role_permission where role_id=?", childOwner))
          .as("第 %d 轮：父租户读不得看见子租户的授权行", i)
          .isEmpty();
    }
  }

  /**
   * 授权行表是租户级事实表，必须强制 RLS —— 否则租户隔离形同虚设。
   */
  @Test
  void 角色与授权表在两租户之间都查不到对方的行且均为强制RLS() {
    assertThat(sql.fetch("select id from role")).as("事务外直查必须为空：租户上下文不是默认放行").isEmpty();
    assertThat(sql.fetch("select tenant_id,role_id,permission_code from role_permission"))
        .as("事务外直查必须为空")
        .isEmpty();

    for (var table : List.of("role", "role_permission")) {
      var forced =
          sql.fetchOne("select relforcerowsecurity from pg_class where relname = ?", table)
              .get(0, Boolean.class);
      assertThat(forced).as("%s 必须是 FORCE RLS", table).isTrue();
    }

    assertThat(
            sql.fetchOne("select rolbypassrls, rolsuper from pg_roles where rolname=current_user")
                .get("rolbypassrls", Boolean.class))
        .as("应用连接不得持有 bypassrls，否则 FORCE RLS 形同虚设")
        .isFalse();
  }
}
