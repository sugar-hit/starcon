package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 1.2.2.2 快捷入口配置 —— 集成测试。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。</b>
 *
 * <p><b>本测试真正在守的东西</b>：
 *
 * <ol>
 *   <li><b>合并规则是确定的</b>：多模板命中同一个入口时谁赢、模板内怎么排、
 *       个人覆盖怎么改、个人移除怎么压过模板存在 —— 每一条都有断言，
 *       「按插入顺序覆盖」这种隐式行为不许存在。</li>
 *   <li><b>权限闸真的生效</b>：模板里的入口若指向用户无权访问的页面，
 *       该入口**不生效**（不是返回后再置灰）。</li>
 *   <li><b>新成员只拿到模板项</b>：没有任何个人覆盖的新人，拿到的就是岗位模板那批入口。</li>
 *   <li><b>覆盖项不能凭空新增入口</b>：那是 1.2.2.1 收藏的职责。</li>
 *   <li><b>绕过服务层直连 SQL 也一样被数据库拒</b>：畸形入口清单在 DB 层就被 CHECK 打回。</li>
 * </ol>
 */
class ShortcutTemplateIntegrationTest extends IdentityIntegrationTestBase {

  private TenantTransactions tx() {
    return new TenantTransactions(
        sql, new org.springframework.transaction.support.TransactionTemplate(manager));
  }

  /** 租户事务内直查（RLS 生效）。 */
  private List<Record> rowsIn(UUID tenant, String statement, Object... bindings) {
    return tx().run(tenant, () -> List.copyOf(sql.fetch(statement, bindings)));
  }

  // ================================================================ 测试夹具

  private UUID createRole(String token, String code, List<String> permissions) throws Exception {
    return UUID.fromString(
        request(
                token,
                "POST",
                "/identity/roles",
                Map.of(
                    "code", code,
                    "name", "快捷入口测试角色 " + code,
                    "status", "ACTIVE",
                    "permissions", permissions),
                200)
            .asText());
  }

  private UUID createDepartment(String token, String code, String name) throws Exception {
    return createDepartment(token, code, name, null);
  }

  /** 造一条入口。 */
  private Map<String, Object> entry(
      String key, String label, String pageCode, String path, Integer position) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("key", key);
    item.put("label", label);
    item.put("pageCode", pageCode);
    item.put("path", path);
    if (position != null) item.put("position", position);
    return item;
  }

  /**
   * 每个用例用**自己的**入口标识。
   *
   * <p>为什么必须唯一：这些用例共用同一个租户（测试底座只建一个租户），
   * 而 TENANT 范围的模板对全租户可见 —— 用例 A 建的入口会出现在用例 B 的生效集合里。
   * 入口标识撞车时，「谁的模板赢了」就取决于模板编码的字典序，断言会变得不确定。
   */
  private String key(String prefix, String tag) {
    return prefix + "-" + tag.toLowerCase();
  }

  /** 造一张模板。 */
  private Map<String, Object> template(
      String code, String scopeType, UUID roleId, UUID departmentId, int priority, Object... entries) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("name", "模板 " + code);
    body.put("description", "");
    body.put("scopeType", scopeType);
    if (roleId != null) body.put("roleId", roleId.toString());
    if (departmentId != null) body.put("departmentId", departmentId.toString());
    body.put("status", "ACTIVE");
    body.put("priority", priority);
    body.put("entries", List.of(entries));
    return body;
  }

  private UUID createTemplate(String token, Map<String, Object> body) throws Exception {
    return UUID.fromString(
        request(token, "POST", "/identity/shortcut-templates", body, 200).get("id").asText());
  }

  private JsonNode effective(String token) throws Exception {
    return request(token, "GET", "/identity/shortcut-templates/effective", null, 200);
  }

  private List<String> effectiveKeys(String token) throws Exception {
    List<String> keys = new ArrayList<>();
    for (JsonNode entry : effective(token).get("entries")) {
      keys.add(entry.get("key").asText());
    }
    return keys;
  }

  private JsonNode effectiveEntry(String token, String key) throws Exception {
    for (JsonNode entry : effective(token).get("entries")) {
      if (key.equals(entry.get("key").asText())) return entry;
    }
    return null;
  }

  /** 建一个只有 {@code identity.shortcut.read} 的成员（拿不到任何业务页面的读权限）。 */
  private String readerMember(String tag, List<String> extraPermissions) throws Exception {
    String owner = login();
    List<String> permissions = new ArrayList<>(List.of("identity.shortcut.read"));
    permissions.addAll(extraPermissions);
    UUID role = createRole(owner, "SR" + tag, permissions);
    request(
        owner,
        "POST",
        "/identity/members",
        memberInput(
            "sr-" + tag.toLowerCase(),
            null,
            List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
        200);
    return loginUser("sr-" + tag.toLowerCase());
  }

  // ================================================================ 模板 CRUD

  @Test
  void 建模板后能在列表与详情里读回且入口清单原样往返() throws Exception {
    String owner = login();
    String tag = suffix();
    UUID id =
        createTemplate(
            owner,
            template(
                "ops-" + tag,
                "TENANT",
                null,
                null,
                10,
                entry(key("rt1", tag), "订单", "trade.orders", "/orders", 10),
                entry(key("rt2", tag), "SKU", "catalog.sku", "/catalog/skus", 20)));

    JsonNode detail = request(owner, "GET", "/identity/shortcut-templates/" + id, null, 200);
    assertThat(detail.get("code").asText()).isEqualTo("ops-" + tag);
    assertThat(detail.get("scopeType").asText()).isEqualTo("TENANT");
    assertThat(detail.get("entries")).hasSize(2);
    assertThat(detail.get("entries").get(0).get("key").asText()).isEqualTo(key("rt1", tag));
    assertThat(detail.get("entries").get(0).get("pageCode").asText()).isEqualTo("trade.orders");
    assertThat(detail.get("entries").get(0).get("path").asText()).isEqualTo("/orders");
    assertThat(detail.get("entries").get(1).get("position").asInt()).isEqualTo(20);

    JsonNode list = request(owner, "GET", "/identity/shortcut-templates?limit=100", null, 200);
    assertThat(list.get("total").asInt()).isGreaterThan(0);
    assertThat(list.get("templates").toString()).contains("ops-" + tag);
  }

  @Test
  void 删模板后详情变成404() throws Exception {
    String owner = login();
    String tag = suffix();
    UUID id = createTemplate(owner, template("del-" + tag, "TENANT", null, null, 10));
    request(owner, "DELETE", "/identity/shortcut-templates/" + id, null, 200);
    request(owner, "GET", "/identity/shortcut-templates/" + id, null, 404);
  }

  @Test
  void 列表是真分页且两页不重叠() throws Exception {
    String owner = login();
    String tag = suffix();
    for (int i = 0; i < 3; i++) {
      createTemplate(owner, template("page-" + tag + "-" + i, "TENANT", null, null, 100 + i));
    }
    JsonNode first = request(owner, "GET", "/identity/shortcut-templates?limit=1&offset=0", null, 200);
    JsonNode second = request(owner, "GET", "/identity/shortcut-templates?limit=1&offset=1", null, 200);
    assertThat(first.get("templates")).hasSize(1);
    assertThat(second.get("templates")).hasSize(1);
    assertThat(first.get("templates").get(0).get("id").asText())
        .isNotEqualTo(second.get("templates").get(0).get("id").asText());
    assertThat(first.get("total").asInt()).isEqualTo(second.get("total").asInt());
  }

  @Test
  void 改模板后入口清单整体替换() throws Exception {
    String owner = login();
    String tag = suffix();
    UUID id =
        createTemplate(
            owner,
            template(
                "upd-" + tag,
                "TENANT",
                null,
                null,
                10,
                entry(key("upd1", tag), "订单", "trade.orders", "/orders", 10)));

    Map<String, Object> body = template("upd-" + tag, "TENANT", null, null, 20);
    body.put("name", "改过的模板");
    body.put("entries", List.of(entry(key("upd2", tag), "SKU", "catalog.sku", "/catalog/skus", 30)));
    request(owner, "PUT", "/identity/shortcut-templates/" + id, body, 200);

    JsonNode detail = request(owner, "GET", "/identity/shortcut-templates/" + id, null, 200);
    assertThat(detail.get("name").asText()).isEqualTo("改过的模板");
    assertThat(detail.get("priority").asInt()).isEqualTo(20);
    assertThat(detail.get("entries")).hasSize(1);
    assertThat(detail.get("entries").get(0).get("key").asText()).isEqualTo(key("upd2", tag));
  }

  // ================================================================ 适用范围

  @Test
  void 岗位模板只对持有该岗位的成员生效() throws Exception {
    String owner = login();
    String tag = suffix();
    UUID role = createRole(owner, "RT" + tag, List.of("identity.shortcut.read", "catalog.sku.read"));
    createTemplate(
        owner,
        template(
            "role-" + tag,
            "ROLE",
            role,
            null,
            10,
            entry(key("role", tag), "SKU", "catalog.sku", "/catalog/skus", 10)));

    // 持有该岗位的成员能看到。
    request(
        owner,
        "POST",
        "/identity/members",
        memberInput(
            "rt-" + tag.toLowerCase(),
            null,
            List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
        200);
    String holder = loginUser("rt-" + tag.toLowerCase());
    assertThat(effectiveKeys(holder)).contains(key("role", tag));

    // 不持有该岗位的成员看不到。
    String other = readerMember(tag + "B", List.of("catalog.sku.read"));
    assertThat(effectiveKeys(other)).doesNotContain(key("role", tag));
  }

  @Test
  void 团队模板只对本部门的成员生效() throws Exception {
    String owner = login();
    String tag = suffix();
    UUID dept = createDepartment(owner, "SD" + tag, "运营部 " + tag);
    createTemplate(
        owner,
        template(
            "dept-" + tag,
            "DEPARTMENT",
            null,
            dept,
            10,
            entry(key("dept", tag), "SPU", "catalog.spu", "/catalog/spus", 10)));

    UUID role = createRole(owner, "DT" + tag, List.of("identity.shortcut.read", "catalog.spu.read"));
    request(
        owner,
        "POST",
        "/identity/members",
        memberInput(
            "dt-" + tag.toLowerCase(),
            dept.toString(),
            List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
        200);
    String inside = loginUser("dt-" + tag.toLowerCase());
    assertThat(effectiveKeys(inside)).contains(key("dept", tag));

    // 同租户但别的部门的成员看不到。
    String outside = readerMember(tag + "B", List.of("catalog.spu.read"));
    assertThat(effectiveKeys(outside)).doesNotContain(key("dept", tag));
  }

  // ================================================================ 合并规则

  @Test
  void 多张模板命中同一个入口时优先级高的赢() throws Exception {
    String owner = login();
    String tag = suffix();
    // priority 小的先合并 ⇒ 同 key 时它赢。
    createTemplate(
        owner,
        template(
            "low-" + tag, "TENANT", null, null, 10, entry("dup.key", "低优先", "trade.orders", "/low", 10)));
    createTemplate(
        owner,
        template(
            "high-" + tag,
            "TENANT",
            null,
            null,
            99,
            entry("dup.key", "高优先", "trade.orders", "/high", 10)));

    JsonNode entry = effectiveEntry(owner, "dup.key");
    assertThat(entry).as("同一入口只能出现一次").isNotNull();
    assertThat(entry.get("label").asText()).isEqualTo("低优先");
    assertThat(entry.get("sourceTemplateCode").asText()).isEqualTo("low-" + tag);
    long count =
        effective(owner).get("entries").findValuesAsText("key").stream().filter("dup.key"::equals).count();
    assertThat(count).as("同一个入口不能出现两次").isEqualTo(1);
  }

  @Test
  void 模板内按position排序且最终结果按position排序() throws Exception {
    String owner = login();
    String tag = suffix();
    createTemplate(
        owner,
        template(
            "ord-" + tag,
            "TENANT",
            null,
            null,
            10,
            entry(key("ord2", tag), "第二", "trade.orders", "/second", 20),
            entry(key("ord1", tag), "第一", "catalog.sku", "/first", 10)));

    List<String> keys = effectiveKeys(owner);
    int second = keys.indexOf(key("ord2", tag));
    int first = keys.indexOf(key("ord1", tag));
    assertThat(second).isGreaterThanOrEqualTo(0);
    assertThat(first).isGreaterThanOrEqualTo(0);
    assertThat(first).as("position 小的在前").isLessThan(second);
  }

  @Test
  void 个人覆盖改标题后生效集合里就是改过的标题() throws Exception {
    String owner = login();
    String tag = suffix();
    createTemplate(
        owner,
        template(
            "ovr-" + tag,
            "TENANT",
            null,
            null,
            10,
            entry(key("ovr", tag), "原始标题", "trade.orders", "/orders", 10)));

    request(
        owner,
        "POST",
        "/identity/shortcut-templates/overrides",
        Map.of("targetKey", key("ovr", tag), "action", "UPSERT", "label", "我的订单"),
        200);

    JsonNode entry = effectiveEntry(owner, key("ovr", tag));
    assertThat(entry.get("label").asText()).isEqualTo("我的订单");
    assertThat(entry.get("path").asText()).as("没覆盖的字段沿用模板项的值").isEqualTo("/orders");
    assertThat(entry.get("overridden").asBoolean()).isTrue();
  }

  @Test
  void 个人移除优先于模板存在() throws Exception {
    String owner = login();
    String tag = suffix();
    createTemplate(
        owner,
        template(
            "rm-" + tag,
            "TENANT",
            null,
            null,
            10,
            entry(key("rm", tag), "要被移除", "trade.orders", "/gone", 10)));

    assertThat(effectiveKeys(owner)).contains(key("rm", tag));
    request(
        owner,
        "POST",
        "/identity/shortcut-templates/overrides",
        Map.of("targetKey", key("rm", tag), "action", "REMOVE"),
        200);

    assertThat(effectiveKeys(owner)).doesNotContain(key("rm", tag));
    assertThat(effective(owner).get("removedByOverride").asInt()).isEqualTo(1);
  }

  @Test
  void 删掉覆盖项后条目回到模板的样子() throws Exception {
    String owner = login();
    String tag = suffix();
    createTemplate(
        owner,
        template(
            "undo-" + tag,
            "TENANT",
            null,
            null,
            10,
            entry(key("undo", tag), "原始", "trade.orders", "/undo", 10)));

    JsonNode override =
        request(
                owner,
                "POST",
                "/identity/shortcut-templates/overrides",
                Map.of("targetKey", key("undo", tag), "action", "UPSERT", "label", "改过的"),
                200)
            .get("id");
    assertThat(effectiveEntry(owner, key("undo", tag)).get("label").asText()).isEqualTo("改过的");

    request(
        owner,
        "DELETE",
        "/identity/shortcut-templates/overrides/" + override.asText(),
        null,
        200);
    assertThat(effectiveEntry(owner, key("undo", tag)).get("label").asText()).isEqualTo("原始");
  }

  @Test
  void 新成员没有任何个人覆盖时只拿到模板项() throws Exception {
    String owner = login();
    String tag = suffix();
    createTemplate(
        owner,
        template(
            "new-" + tag,
            "TENANT",
            null,
            null,
            10,
            entry(key("new1", tag), "订单", "trade.orders", "/orders", 10),
            entry(key("new2", tag), "SKU", "catalog.sku", "/catalog/skus", 20)));

    String fresh = readerMember(tag, List.of("trade.orders.read", "catalog.sku.read"));
    JsonNode set = effective(fresh);
    // 这个租户里还留着别的用例建的模板，所以这里断言「我的那两条在」而不是「一共两条」。
    assertThat(effectiveKeys(fresh)).contains(key("new1", tag), key("new2", tag));
    assertThat(set.get("overrideCount").asInt()).isZero();
    assertThat(effectiveEntry(fresh, key("new1", tag)).get("overridden").asBoolean()).isFalse();
    assertThat(effectiveEntry(fresh, key("new2", tag)).get("overridden").asBoolean()).isFalse();
  }

  // ================================================================ 权限闸

  @Test
  void 入口指向无权访问的页面时不生效() throws Exception {
    String owner = login();
    String tag = suffix();
    createTemplate(
        owner,
        template(
            "perm-" + tag,
            "TENANT",
            null,
            null,
            10,
            entry(key("perm1", tag), "订单", "trade.orders", "/orders", 10),
            entry(key("perm2", tag), "SKU", "catalog.sku", "/catalog/skus", 20)));

    // 只有 catalog.sku.read，没有 trade.orders.read。
    String limited = readerMember(tag, List.of("catalog.sku.read"));
    JsonNode set = effective(limited);
    assertThat(effectiveKeys(limited)).contains(key("perm2", tag)).doesNotContain(key("perm1", tag));
    assertThat(set.get("hiddenByPermission").asInt())
        .as("被权限闸挡住的入口要报数（精确值随同租户里其它用例建的模板浮动，所以只断言下界）")
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void 权限变更后入口自动失效并回落() throws Exception {
    String owner = login();
    String tag = suffix();
    createTemplate(
        owner,
        template(
            "fall-" + tag,
            "TENANT",
            null,
            null,
            10,
            entry(key("fall", tag), "SKU", "catalog.sku", "/catalog/skus", 10)));

    UUID role = createRole(owner, "FR" + tag, List.of("identity.shortcut.read", "catalog.sku.read"));
    request(
        owner,
        "POST",
        "/identity/members",
        memberInput(
            "fr-" + tag.toLowerCase(),
            null,
            List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
        200);
    String member = loginUser("fr-" + tag.toLowerCase());
    assertThat(effectiveKeys(member)).contains(key("fall", tag));

    // 撤掉 catalog.sku 权限：入口立刻失效，不需要任何同步任务。
    request(
        owner,
        "PUT",
        "/identity/roles/" + role,
        Map.of(
            "code", "FR" + tag,
            "name", "快捷入口测试角色 FR" + tag,
            "status", "ACTIVE",
            "permissions", List.of("identity.shortcut.read")),
        200);
    assertThat(effectiveKeys(member)).doesNotContain(key("fall", tag));
  }

  // ================================================================ 校验

  @Test
  void 入口缺必填字段被拒() throws Exception {
    String owner = login();
    String tag = suffix();
    Map<String, Object> missingPath = new LinkedHashMap<>();
    missingPath.put("key", "no.path");
    missingPath.put("label", "缺路径");
    missingPath.put("pageCode", "trade.orders");
    request(
        owner,
        "POST",
        "/identity/shortcut-templates",
        template("mp-" + tag, "TENANT", null, null, 10, missingPath),
        400);
  }

  @Test
  void 入口路径带目录穿越被拒() throws Exception {
    String owner = login();
    String tag = suffix();
    request(
        owner,
        "POST",
        "/identity/shortcut-templates",
        template(
            "tp-" + tag,
            "TENANT",
            null,
            null,
            10,
            entry("bad.path", "坏路径", "trade.orders", "/a/../../etc/passwd", 10)),
        400);
  }

  @Test
  void 同一模板内入口标识重复被拒() throws Exception {
    String owner = login();
    String tag = suffix();
    request(
        owner,
        "POST",
        "/identity/shortcut-templates",
        template(
            "dk-" + tag,
            "TENANT",
            null,
            null,
            10,
            entry("same.key", "一", "trade.orders", "/one", 10),
            entry("same.key", "二", "catalog.sku", "/two", 20)),
        400);
  }

  @Test
  void 入口指向未注册的目标页面被拒() throws Exception {
    String owner = login();
    String tag = suffix();
    request(
        owner,
        "POST",
        "/identity/shortcut-templates",
        template(
            "up-" + tag,
            "TENANT",
            null,
            null,
            10,
            entry("bad.page", "未注册页面", "not.a.registered.page", "/nowhere", 10)),
        400);
  }

  @Test
  void 岗位模板指向不存在的岗位被拒() throws Exception {
    String owner = login();
    String tag = suffix();
    request(
        owner,
        "POST",
        "/identity/shortcut-templates",
        template(
            "nr-" + tag,
            "ROLE",
            UUID.randomUUID(),
            null,
            10,
            entry("trade.orders", "订单", "trade.orders", "/orders", 10)),
        400);
  }

  @Test
  void 覆盖项不能凭空新增入口() throws Exception {
    String owner = login();
    String tag = suffix();
    createTemplate(
        owner,
        template(
            "nn-" + tag,
            "TENANT",
            null,
            null,
            10,
            entry(key("nn", tag), "订单", "trade.orders", "/orders", 10)));

    // 模板里没有这个入口：新增是 1.2.2.1 收藏的职责，本模块不提供。
    request(
        owner,
        "POST",
        "/identity/shortcut-templates/overrides",
        Map.of("targetKey", "brand.new.key", "action", "UPSERT", "label", "凭空新增"),
        400);
  }

  @Test
  void 覆盖动作非法或没有可覆盖字段被拒() throws Exception {
    String owner = login();
    String tag = suffix();
    createTemplate(
        owner,
        template(
            "oa-" + tag,
            "TENANT",
            null,
            null,
            10,
            entry(key("oa", tag), "订单", "trade.orders", "/orders", 10)));

    request(
        owner,
        "POST",
        "/identity/shortcut-templates/overrides",
        Map.of("targetKey", key("oa", tag), "action", "HIDE", "label", "x"),
        400);
    request(
        owner,
        "POST",
        "/identity/shortcut-templates/overrides",
        Map.of("targetKey", key("oa", tag), "action", "UPSERT"),
        400);
  }

  @Test
  void 模板编码重复被拒() throws Exception {
    String owner = login();
    String tag = suffix();
    createTemplate(owner, template("dup.code-" + tag, "TENANT", null, null, 10));
    // 唯一键冲突按仓库既有口径返回 409（不是 400）。
    request(
        owner,
        "POST",
        "/identity/shortcut-templates",
        template("dup.code-" + tag, "TENANT", null, null, 10),
        409);
  }

  // ================================================================ 权限闸（接口层）

  @Test
  void 没有快捷入口权限返回403() throws Exception {
    String owner = login();
    String tag = suffix();
    UUID role = createRole(owner, "NS" + tag, List.of("identity.role.read"));
    request(
        owner,
        "POST",
        "/identity/members",
        memberInput(
            "ns-" + tag.toLowerCase(),
            null,
            List.of(Map.of("roleId", role.toString(), "scopeMode", "ALL"))),
        200);
    String member = loginUser("ns-" + tag.toLowerCase());

    request(member, "GET", "/identity/shortcut-templates", null, 403);
    request(member, "GET", "/identity/shortcut-templates/effective", null, 403);
    request(member, "POST", "/identity/shortcut-templates", template("x-" + tag, "TENANT", null, null, 10), 403);
  }

  @Test
  void 只有读权限不能改模板但能改自己的覆盖项() throws Exception {
    String owner = login();
    String tag = suffix();
    createTemplate(
        owner,
        template(
            "ro-" + tag,
            "TENANT",
            null,
            null,
            10,
            entry(key("ro", tag), "SKU", "catalog.sku", "/catalog/skus", 10)));

    // 读者：有 identity.shortcut.read 与 catalog.sku.read，但没有 manage。
    String reader = readerMember(tag, List.of("catalog.sku.read"));
    request(reader, "GET", "/identity/shortcut-templates", null, 200);

    // 模板是租户级组织配置：没有 manage 就不能建。
    request(
        reader,
        "POST",
        "/identity/shortcut-templates",
        template("x-" + tag, "TENANT", null, null, 10),
        403);

    // 自己的覆盖项是个人配置：有 read 就能改。
    request(
        reader,
        "POST",
        "/identity/shortcut-templates/overrides",
        Map.of("targetKey", key("ro", tag), "action", "UPSERT", "label", "我的 SKU"),
        200);
    assertThat(effectiveEntry(reader, key("ro", tag)).get("label").asText()).isEqualTo("我的 SKU");
  }

  // ================================================================ 数据库层

  @Test
  void 绕过服务层直连SQL写畸形入口一样被数据库拒() throws Exception {
    String owner = login();
    String tag = suffix();
    UUID tenantId = UUID.fromString(
        request(owner, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText());
    UUID membershipId =
        UUID.fromString(request(owner, "GET", "/auth/session", null, 200).get("membershipId").asText());

    // ① 入口缺 path。
    var missingPath =
        assertThrows(
            RuntimeException.class,
            () ->
                tx()
                    .run(
                        tenantId,
                        () -> {
                          sql.fetch(
                              "insert into shortcut_entry_template"
                                  + "(id,tenant_id,code,name,scope_type,status,priority,entries,created_by)"
                                  + " values (?::uuid,?::uuid,?::varchar,'x','TENANT','ACTIVE',10,"
                                  + "?::jsonb,?::uuid)",
                              UUID.randomUUID(),
                              tenantId,
                              "direct-" + tag,
                              "[{\"key\":\"a.b\",\"label\":\"L\",\"pageCode\":\"trade.orders\"}]",
                              membershipId);
                          return null;
                        }));
    assertThat(missingPath.getMessage()).contains("shortcut_entry_template_entries_chk");

    // ② 同模板内 key 重复。
    var duplicated =
        assertThrows(
            RuntimeException.class,
            () ->
                tx()
                    .run(
                        tenantId,
                        () -> {
                          sql.fetch(
                              "insert into shortcut_entry_template"
                                  + "(id,tenant_id,code,name,scope_type,status,priority,entries,created_by)"
                                  + " values (?::uuid,?::uuid,?::varchar,'x','TENANT','ACTIVE',10,"
                                  + "?::jsonb,?::uuid)",
                              UUID.randomUUID(),
                              tenantId,
                              "direct2-" + tag,
                              "[{\"key\":\"a.b\",\"label\":\"L\",\"pageCode\":\"trade.orders\",\"path\":\"/a\"},"
                                  + "{\"key\":\"a.b\",\"label\":\"M\",\"pageCode\":\"trade.orders\",\"path\":\"/b\"}]",
                              membershipId);
                          return null;
                        }));
    assertThat(duplicated.getMessage()).contains("shortcut_entry_template_entries_chk");

    // ③ 模板指向不存在的岗位：外键在数据库层兜住。
    var badRole =
        assertThrows(
            RuntimeException.class,
            () ->
                tx()
                    .run(
                        tenantId,
                        () -> {
                          sql.fetch(
                              "insert into shortcut_entry_template"
                                  + "(id,tenant_id,code,name,scope_type,role_id,status,priority,entries,created_by)"
                                  + " values (?::uuid,?::uuid,?::varchar,'x','ROLE',?::uuid,'ACTIVE',10,'[]'::jsonb,"
                                  + "?::uuid)",
                              UUID.randomUUID(),
                              tenantId,
                              "direct3-" + tag,
                              UUID.randomUUID(),
                              membershipId);
                          return null;
                        }));
    assertThat(badRole.getMessage()).contains("shortcut_entry_template_scope_fk_role");
  }

  @Test
  void 两张表都是强制RLS() throws Exception {
    for (String table : List.of("shortcut_entry_template", "user_shortcut_override")) {
      Boolean forced =
          sql.fetchOne("select relforcerowsecurity from pg_class where relname = ?", table)
              .get(0, Boolean.class);
      Boolean enabled =
          sql.fetchOne("select relrowsecurity from pg_class where relname = ?", table)
              .get(0, Boolean.class);
      assertThat(enabled).as(table + " 必须 ENABLE RLS").isTrue();
      assertThat(forced).as(table + " 必须是 FORCE RLS").isTrue();
    }
  }

  @Test
  void 时间字段写回零漂移() throws Exception {
    String owner = login();
    String tag = suffix();
    OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
    createTemplate(
        owner,
        template(
            "ts-" + tag,
            "TENANT",
            null,
            null,
            10,
            entry("trade.orders", "订单", "trade.orders", "/orders", 10)));

    UUID tenantId = UUID.fromString(
        request(owner, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText());
    List<Record> rows =
        rowsIn(tenantId, "select created_at, updated_at from shortcut_entry_template where code = ?", "ts-" + tag);
    assertThat(rows).hasSize(1);
    OffsetDateTime written = rows.getFirst().get("created_at", OffsetDateTime.class);
    assertThat(written).isNotNull();
    assertThat(Duration.between(before, written).abs().getSeconds())
        .as("timestamptz 写回不能偏（用 Timestamp.valueOf 会静默偏 −28800s）")
        .isLessThan(60);
  }

  @Test
  void 写接口接受application_json() throws Exception {
    String owner = login();
    String tag = suffix();
    String body =
        json.writeValueAsString(
            template(
                "json-" + tag,
                "TENANT",
                null,
                null,
                10,
                entry("trade.orders", "订单", "trade.orders", "/orders", 10)));
    http.perform(
            post("/identity/shortcut-templates")
                .header("Authorization", "Bearer " + owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
  }

  @Test
  void 能力声明把合并规则与边界原样下发() throws Exception {
    String owner = login();
    JsonNode caps = request(owner, "GET", "/identity/shortcut-templates/capabilities", null, 200);
    assertThat(caps.get("templateManagement").asBoolean()).isTrue();
    assertThat(caps.get("personalOverride").asBoolean()).isTrue();
    assertThat(caps.get("effectiveSet").asBoolean()).isTrue();
    assertThat(caps.get("permissionScopedEntries").asBoolean()).isTrue();
    assertThat(caps.get("favoritesList").asBoolean()).as("1.2.2.1 未交付").isFalse();
    assertThat(caps.get("favoritesOwnerModule").asText()).isEqualTo("1.2.2.1 常用页面收藏");
    assertThat(caps.get("mergeRules").toString()).contains("REMOVE").contains("position");
    assertThat(caps.get("boundaries").toString()).contains("1.2.2.1");
  }
}
