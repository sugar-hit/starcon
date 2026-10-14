package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 17.5.2.1 数据字典。
 *
 * <p>重点不是「能存进去」，而是蓝图点名的三条硬要求真的成立，外加多租户三断言：
 *
 * <ol>
 *   <li><b>租户级自定义扩展</b>——目录是平台出厂的全局码表，字典项由租户自己建， 同一目录在不同租户下条目数可以不同，且互不可见。
 *   <li><b>不允许修改已被历史数据引用的编码</b>——被引用后 PUT 改编码返回 409； 直接绕过应用连库改也会被 {@code dict_item_update_guard()}
 *       触发器挡下； 名称与说明仍可改。
 *   <li><b>字典项被引用后只能停用不能删除</b>——控制器没有 DELETE 端点（405）， {@code identity_app} 没有 DELETE 权限，外键
 *       RESTRICT， {@code dict_item_delete_guard()} 触发器挡下所有 DELETE。
 * </ol>
 *
 * <p>时间字段全部按值断言：用 {@code Timestamp.valueOf(LocalDateTime)} 绑 timestamptz 会静默偏 −28800 秒，所以 {@code
 * disabledAt} / {@code updatedAt} / {@code changedAt} 都落在"请求前后两个墙钟之间"才算通过。
 */
class DictionaryIntegrationTest extends IdentityIntegrationTestBase {

  /**
   * 每个用例前清空本模块的数据。
   *
   * <p>容器与 local-admin 租户在所有测试类之间共享（见 IdentityIntegrationTestBase）。 {@code dict_item} 与 {@code
   * dict_history} 都有行级触发器（删除保护 / 不可篡改）， 普通 DELETE 会被挡下，所以用 TRUNCATE —— 它不触发行级触发器。
   */
  @org.junit.jupiter.api.BeforeEach
  void resetModule() throws Exception {
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement()) {
      s.execute("truncate dict_history, dict_item_usage, dict_item");
    }
  }

  private TenantTransactions tx() {
    return new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  /** 租户外直连（绕过应用连接池与租户上下文），用于触发器级别的断言。 */
  private void asSuperuser(String statement) throws Exception {
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement()) {
      s.execute(statement);
    }
  }

  // ------------------------------------------------------------------
  // 辅助
  // ------------------------------------------------------------------

  private JsonNode catalog(String token) throws Exception {
    return request(token, "GET", "/identity/dict-catalog", null, 200);
  }

  private JsonNode items(String token, String query) throws Exception {
    return request(
        token,
        "GET",
        "/identity/dict-items" + (query == null || query.isEmpty() ? "" : "?" + query),
        null,
        200);
  }

  private JsonNode findItem(JsonNode list, String itemCode) {
    for (JsonNode n : list) if (itemCode.equals(n.get("itemCode").asText())) return n;
    return null;
  }

  private JsonNode findCatalog(JsonNode list, String code) {
    for (JsonNode n : list) if (code.equals(n.get("code").asText())) return n;
    return null;
  }

  private Map<String, Object> itemInput(String dictCode, String itemCode, String name) {
    var input = new LinkedHashMap<String, Object>();
    input.put("dictCode", dictCode);
    input.put("itemCode", itemCode);
    input.put("name", name);
    input.put("description", "集成测试字典项");
    input.put("sortOrder", 10);
    return input;
  }

  private UUID createItem(String token, String dictCode, String itemCode, String name)
      throws Exception {
    return UUID.fromString(
        request(token, "POST", "/identity/dict-items", itemInput(dictCode, itemCode, name), 200)
            .asText());
  }

  private void recordUsage(String token, UUID itemId, String sourceSystem, long count)
      throws Exception {
    request(
        token,
        "POST",
        "/identity/dict-items/" + itemId + "/usage",
        Map.of("sourceSystem", sourceSystem, "usageCount", count, "note", "集成测试登记"),
        200);
  }

  private UUID tenantOf(String token) throws Exception {
    return UUID.fromString(
        request(token, "GET", "/identity/tenants/current", null, 200).get("tenantId").asText());
  }

  // ------------------------------------------------------------------
  // 出厂目录与租户级自定义扩展
  // ------------------------------------------------------------------

  @Test
  void catalogCoversTheFourKindsNamedByBlueprint() throws Exception {
    String token = login();
    var list = catalog(token);

    // 蓝图职责点名的四类：状态枚举、原因码、分类标签、单位类型，一个不少。
    assertThat(findCatalog(list, "ORDER_STATUS").get("itemKind").asText()).isEqualTo("STATUS_ENUM");
    assertThat(findCatalog(list, "ORDER_CANCEL_REASON").get("itemKind").asText())
        .isEqualTo("REASON_CODE");
    assertThat(findCatalog(list, "PRODUCT_CATEGORY").get("itemKind").asText())
        .isEqualTo("CATEGORY_LABEL");
    assertThat(findCatalog(list, "UNIT_TYPE").get("itemKind").asText()).isEqualTo("UNIT_TYPE");

    // 目录是平台出厂的全局码表：所有条目 codeLocked=true，
    // 页面上才能直接告诉用户「这类字典的编码是锁死的」。
    assertThat(list).isNotEmpty();
    for (JsonNode n : list) {
      assertThat(n.get("codeLocked").asBoolean())
          .as("目录 %s 必须声明编码锁", n.get("code").asText())
          .isTrue();
      assertThat(n.get("status").asText()).isEqualTo("ACTIVE");
    }

    // 用例之间共享容器与租户，所以不断言 itemCount 为 0；
    // 「这个字典一个项都没配」由 tenantIsolationTwoVisibleForgedHeaderAndPoolReturn 之外
    // 的用例在各自字典下证明：新建一个没人用过的字典，它的计数就是 0。
    var fresh = findCatalog(list, "UNIT_TYPE");
    assertThat(fresh.get("itemCount").asLong()).isNotNegative();
    assertThat(fresh.get("itemCount").asLong())
        .isEqualTo(
            fresh.get("activeItemCount").asLong()
                + countDisabled(items(token, "dictCode=UNIT_TYPE")));
  }

  /** UNIT_TYPE 下停用项的数量，用来核对 active_item_count 确实只数 ACTIVE。 */
  private long countDisabled(JsonNode list) {
    long n = 0;
    for (JsonNode row : list) if ("DISABLED".equals(row.get("status").asText())) n++;
    return n;
  }

  @Test
  void tenantCanExtendDictionaryAndCountsArePerTenant() throws Exception {
    String owner = login();
    String tag = suffix();

    // 同一个出厂字典，父租户建两个项、子租户建一个完全不同编码的项
    createItem(owner, "ORDER_STATUS", "PAID_" + tag, "已付款");
    createItem(owner, "ORDER_STATUS", "SHIPPED_" + tag, "已发货");
    var parentCatalog = findCatalog(catalog(owner), "ORDER_STATUS");
    assertThat(parentCatalog.get("itemCount").asLong()).isEqualTo(2);
    assertThat(parentCatalog.get("activeItemCount").asLong()).isEqualTo(2);

    // 子租户
    String childUser = "dict-child-" + tag.toLowerCase();
    request(
        owner,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            "DC" + tag,
            "name",
            "数据字典子租户",
            "ownerUsername",
            childUser,
            "ownerPassword",
            "test-member-password"),
        200);
    String childToken = loginUser(childUser);
    createItem(childToken, "ORDER_STATUS", "CHILD_ONLY_" + tag, "子租户专属状态");

    var childCatalog = findCatalog(catalog(childToken), "ORDER_STATUS");
    assertThat(childCatalog.get("itemCount").asLong()).as("父租户建的字典项不得计入子租户").isEqualTo(1);
    assertThat(items(childToken, "dictCode=ORDER_STATUS").toString())
        .contains("CHILD_ONLY_" + tag)
        .doesNotContain("PAID_" + tag);
  }

  @Test
  void itemCodeMustBeUppercaseIdentifierAndUniquePerDictionary() throws Exception {
    String token = login();
    String tag = suffix();

    // 小写编码被拒：小写与空格会让同一个字典项在不同系统里出现两种写法
    request(
        token,
        "POST",
        "/identity/dict-items",
        itemInput("ORDER_STATUS", "paid_" + tag, "已付款"),
        400);
    // 单个字符的编码太短：编码会进单据、报表与导出，太短没有区分度
    request(token, "POST", "/identity/dict-items", itemInput("ORDER_STATUS", "P", "已付款"), 400);
    // 编码含不允许的字符
    request(
        token,
        "POST",
        "/identity/dict-items",
        itemInput("ORDER_STATUS", "PAID-" + tag, "已付款"),
        400);
    request(
        token, "POST", "/identity/dict-items", itemInput("NO_SUCH_DICT", "OK_" + tag, "不存在"), 400);

    createItem(token, "ORDER_STATUS", "PAID_" + tag, "已付款");
    // 同字典下编码唯一，撞码 409 而不是覆盖
    request(
        token, "POST", "/identity/dict-items", itemInput("ORDER_STATUS", "PAID_" + tag, "重复"), 409);
    // 不同字典下同一编码互不冲突：唯一约束是 (租户, 字典, 编码)，不是 (租户, 编码)
    createItem(token, "AFTERSALE_REASON", "PAID_" + tag, "售后原因");
    assertThat(items(token, "dictCode=AFTERSALE_REASON").toString()).contains("PAID_" + tag);
  }

  // ------------------------------------------------------------------
  // 引用统计：真实数据，不是写死的数字
  // ------------------------------------------------------------------

  @Test
  void referenceStatisticsComeFromRealUsageLedger() throws Exception {
    String token = login();
    String tag = suffix();
    UUID id = createItem(token, "ORDER_CANCEL_REASON", "CANCEL_" + tag, "买家取消");

    // 刚建出来时没有被任何来源系统引用过
    var fresh = request(token, "GET", "/identity/dict-items/" + id, null, 200);
    assertThat(fresh.get("referenceCount").asLong()).isZero();
    assertThat(fresh.get("referencingSystems").asLong()).isZero();
    assertThat(fresh.get("referenced").asBoolean()).isFalse();

    // 下游登记引用后，统计跟着变
    recordUsage(token, id, "trade", 128);
    recordUsage(token, id, "catalog", 7);
    var referenced = request(token, "GET", "/identity/dict-items/" + id, null, 200);
    assertThat(referenced.get("referenceCount").asLong()).isEqualTo(135);
    assertThat(referenced.get("referencingSystems").asLong()).isEqualTo(2);
    assertThat(referenced.get("referenced").asBoolean()).isTrue();

    // 同一来源系统重复登记是覆盖而不是累加（否则计数会变成永远对不上的天文数字）
    recordUsage(token, id, "trade", 130);
    var again = request(token, "GET", "/identity/dict-items/" + id, null, 200);
    assertThat(again.get("referenceCount").asLong()).isEqualTo(137);
    assertThat(again.get("referencingSystems").asLong()).isEqualTo(2);

    // 台账明细按来源系统列出，不是只有总数
    var usages = request(token, "GET", "/identity/dict-items/" + id + "/usage", null, 200);
    assertThat(usages).hasSize(2);
    for (JsonNode u : usages) {
      assertThat(u.get("sourceSystem").asText()).isIn("trade", "catalog");
      assertThat(u.get("usageCount").asLong()).isPositive();
      assertThat(u.get("firstReferencedAt").asText()).isNotBlank();
    }

    // 列表口径与详情口径一致：引用统计不是两个各算各的数
    assertThat(
            findItem(items(token, "dictCode=ORDER_CANCEL_REASON"), "CANCEL_" + tag)
                .get("referenceCount")
                .asLong())
        .isEqualTo(137);
  }

  @Test
  void zeroUsageStillCountsAsReferencedBecauseHistoryCounts() throws Exception {
    String token = login();
    String tag = suffix();
    UUID id = createItem(token, "INVENTORY_ADJUST_REASON", "BROKEN_" + tag, "破损");

    recordUsage(token, id, "warehouse", 40);
    // 下游把计数归零：当下没人用它了
    recordUsage(token, id, "warehouse", 0);

    var row = request(token, "GET", "/identity/dict-items/" + id, null, 200);
    assertThat(row.get("referenceCount").asLong()).isZero();
    assertThat(row.get("referencingSystems").asLong()).isEqualTo(1);
    assertThat(row.get("referenced").asBoolean()).as("计数归零只说明当下没人用，历史单据里还留着它，仍算被引用").isTrue();

    // 归零之后编码依然锁死
    var input = itemInput("INVENTORY_ADJUST_REASON", "DAMAGE_" + tag, "报损");
    request(token, "PUT", "/identity/dict-items/" + id, input, 409);
  }

  // ------------------------------------------------------------------
  // 核心不变量：被引用后只能停用不能删除
  // ------------------------------------------------------------------

  @Test
  void referencedItemCannotBeDeletedAtAnyLayer() throws Exception {
    String token = login();
    String tag = suffix();
    UUID id = createItem(token, "ORDER_STATUS", "PAID_" + tag, "已付款");
    recordUsage(token, id, "trade", 99);

    // 防线一：控制器没有 DELETE 端点。MockMvc 走 DispatcherServlet，
    // 路径存在但方法不匹配会落到 405 而不是被转发到别的处理器。
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    "/identity/dict-items/" + id)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isMethodNotAllowed());

    // 防线二：identity_app 没有 DELETE 权限。
    try (var c =
            DriverManager.getConnection(pg().getJdbcUrl(), pg().getUsername(), pg().getPassword());
        var s = c.createStatement();
        var rs =
            s.executeQuery(
                "select has_table_privilege('identity_app','dict_item','DELETE') as can_delete")) {
      rs.next();
      assertThat(rs.getBoolean("can_delete")).as("迁移只授予 SELECT/INSERT/UPDATE，不授予 DELETE").isFalse();
    }

    // 防线三 + 四：外键 RESTRICT 与删除保护触发器。
    // 先删台账让外键有话说（外键 RESTRICT 会先拦下来），再直接删字典项（触发器拦下来）。
    assertThatCode(() -> asSuperuser("delete from dict_item where id='" + id + "'"))
        .as("被引用的字典项必须删不掉")
        .isInstanceOf(Exception.class);

    // 逐层确认：即便先清掉台账，删除保护触发器仍然拦得住
    asSuperuser("delete from dict_item_usage where item_id='" + id + "'");
    assertThatCode(() -> asSuperuser("delete from dict_item where id='" + id + "'"))
        .as("字典项不提供物理删除，触发器必须拒绝")
        .isInstanceOf(Exception.class);
    // 字典项还在
    assertThat(
            request(token, "GET", "/identity/dict-items/" + id, null, 200).get("itemCode").asText())
        .isEqualTo("PAID_" + tag);
  }

  @Test
  void unreferencedItemIsStillNotDeletableOnlyDeactivatable() throws Exception {
    String token = login();
    String tag = suffix();
    UUID id = createItem(token, "PRODUCT_TAG", "TAG_" + tag, "促销标签");

    // 即使从未被引用，删除保护触发器仍然拦得住——本模块的口径是「停用而非删除」
    assertThatCode(() -> asSuperuser("delete from dict_item where id='" + id + "'"))
        .isInstanceOf(Exception.class);

    // 正确路径是停用
    request(
        token,
        "POST",
        "/identity/dict-items/" + id + "/deactivate",
        Map.of("status", "DISABLED", "reason", "促销季结束"),
        200);
    var row = request(token, "GET", "/identity/dict-items/" + id, null, 200);
    assertThat(row.get("status").asText()).isEqualTo("DISABLED");
    assertThat(row.get("disabledReason").asText()).isEqualTo("促销季结束");
    // 历史单据仍能读回它 —— 这正是不能删的理由
    assertThat(row.get("name").asText()).isEqualTo("促销标签");
  }

  @Test
  void deactivateThenReactivateKeepsIdAndUsageLedger() throws Exception {
    String token = login();
    String tag = suffix();
    UUID id = createItem(token, "ORDER_CANCEL_REASON", "STOCKOUT_" + tag, "缺货取消");
    recordUsage(token, id, "trade", 12);

    OffsetDateTime before = OffsetDateTime.now();
    request(
        token,
        "POST",
        "/identity/dict-items/" + id + "/deactivate",
        Map.of("status", "DISABLED", "reason", "该原因已废弃"),
        200);
    var off = request(token, "GET", "/identity/dict-items/" + id, null, 200);

    // 停用时刻按值断言：用 Timestamp.valueOf(LocalDateTime) 绑 timestamptz 会静默偏 −28800 秒
    OffsetDateTime disabledAt = OffsetDateTime.parse(off.get("disabledAt").asText());
    assertThat(disabledAt.toInstant())
        .isBetween(
            before.toInstant().minusSeconds(5), OffsetDateTime.now().toInstant().plusSeconds(5));
    assertThat(off.get("version").asLong()).isEqualTo(1);

    // 停用不影响引用统计：已经发生的历史引用是事实
    assertThat(off.get("referenceCount").asLong()).isEqualTo(12);
    assertThat(off.get("referenced").asBoolean()).isTrue();

    // 停用的项不进下拉选项，但列表里还看得见
    assertThat(items(token, "dictCode=ORDER_CANCEL_REASON&status=DISABLED").toString())
        .contains("STOCKOUT_" + tag);
    var options =
        request(token, "GET", "/identity/dict-items/options/ORDER_CANCEL_REASON", null, 200);
    assertThat(options.toString()).doesNotContain("STOCKOUT_" + tag);

    // 恢复保持同一个 id 与同一份引用台账
    request(
        token,
        "POST",
        "/identity/dict-items/" + id + "/reactivate",
        Map.of("status", "ACTIVE", "reason", "误停用，恢复"),
        200);
    var on = request(token, "GET", "/identity/dict-items/" + id, null, 200);
    assertThat(on.get("id").asText()).isEqualTo(id.toString());
    assertThat(on.get("status").asText()).isEqualTo("ACTIVE");
    assertThat(on.get("referenceCount").asLong()).isEqualTo(12);
    assertThat(on.get("version").asLong()).isEqualTo(2);

    // 重复停用 409、重复恢复 409，不静默改时刻
    request(
        token,
        "POST",
        "/identity/dict-items/" + id + "/reactivate",
        Map.of("status", "ACTIVE", "reason", "再恢复一次"),
        409);
    request(
        token,
        "POST",
        "/identity/dict-items/" + id + "/deactivate",
        Map.of("status", "DISABLED", "reason", "停用"),
        200);
    request(
        token,
        "POST",
        "/identity/dict-items/" + id + "/deactivate",
        Map.of("status", "DISABLED", "reason", "再停一次"),
        409);
  }

  @Test
  void statusChangeRequiresReasonAndRightDirection() throws Exception {
    String token = login();
    String tag = suffix();
    UUID id = createItem(token, "SHELF_STATUS", "LISTED_" + tag, "已上架");

    // 停用必须带原因
    request(
        token,
        "POST",
        "/identity/dict-items/" + id + "/deactivate",
        Map.of("status", "DISABLED", "reason", ""),
        400);
    request(
        token,
        "POST",
        "/identity/dict-items/" + id + "/deactivate",
        Map.of("status", "DISABLED"),
        400);
    // 方向不能搞反：停用端点只收 DISABLED
    request(
        token,
        "POST",
        "/identity/dict-items/" + id + "/deactivate",
        Map.of("status", "ACTIVE", "reason", "方向反了"),
        400);
    request(
        token,
        "POST",
        "/identity/dict-items/" + id + "/reactivate",
        Map.of("status", "DISABLED", "reason", "方向反了"),
        400);
    // 非法状态值
    request(
        token,
        "POST",
        "/identity/dict-items/" + id + "/deactivate",
        Map.of("status", "RETIRED", "reason", "非法状态"),
        400);
  }

  // ------------------------------------------------------------------
  // 编码不可改：控制器与触发器两道
  // ------------------------------------------------------------------

  @Test
  void referencedCodeIsImmutableButNameStillEditable() throws Exception {
    String token = login();
    String tag = suffix();
    UUID id = createItem(token, "ORDER_CANCEL_REASON", "CANCEL_" + tag, "买家取消");
    recordUsage(token, id, "trade", 55);

    // 被引用后改编码 → 409
    request(
        token,
        "PUT",
        "/identity/dict-items/" + id,
        itemInput("ORDER_CANCEL_REASON", "BUYER_CANCEL_" + tag, "买家取消"),
        409);

    // 名称与说明仍可改：业务含义变了要能改，承载历史数据的那个字符串不能改
    var edit = new LinkedHashMap<String, Object>();
    edit.put("dictCode", "ORDER_CANCEL_REASON");
    edit.put("itemCode", "CANCEL_" + tag);
    edit.put("name", "买家主动取消");
    edit.put("description", "改名不改编码");
    edit.put("sortOrder", 20);
    request(token, "PUT", "/identity/dict-items/" + id, edit, 200);
    var row = request(token, "GET", "/identity/dict-items/" + id, null, 200);
    assertThat(row.get("name").asText()).isEqualTo("买家主动取消");
    assertThat(row.get("itemCode").asText()).isEqualTo("CANCEL_" + tag);
    assertThat(row.get("version").asLong()).isEqualTo(1);

    // 不传 itemCode = 不改编码（不是把它清空）
    var noCode = new LinkedHashMap<String, Object>();
    noCode.put("dictCode", "ORDER_CANCEL_REASON");
    noCode.put("name", "买家自行取消");
    noCode.put("description", "不传编码");
    request(token, "PUT", "/identity/dict-items/" + id, noCode, 200);
    assertThat(
            request(token, "GET", "/identity/dict-items/" + id, null, 200).get("itemCode").asText())
        .isEqualTo("CANCEL_" + tag);

    // 绕过应用直接改编码：触发器必须挡下
    assertThatCode(
            () -> asSuperuser("update dict_item set item_code='HACKED' where id='" + id + "'"))
        .as("dict_item_update_guard() 必须在数据库层拒绝改被引用编码")
        .isInstanceOf(Exception.class);
    assertThat(
            request(token, "GET", "/identity/dict-items/" + id, null, 200).get("itemCode").asText())
        .isEqualTo("CANCEL_" + tag);
  }

  @Test
  void unreferencedCodeCanChangeButCannotMoveToAnotherDictionary() throws Exception {
    String token = login();
    String tag = suffix();
    UUID id = createItem(token, "PRODUCT_TAG", "TEMP_" + tag, "临时标签");

    // 未被引用 → 编码可改
    request(
        token,
        "PUT",
        "/identity/dict-items/" + id,
        itemInput("PRODUCT_TAG", "KEPT_" + tag, "临时标签"),
        200);
    assertThat(
            request(token, "GET", "/identity/dict-items/" + id, null, 200).get("itemCode").asText())
        .isEqualTo("KEPT_" + tag);

    // 换字典等于换一条记录：会把这个字典项的引用统计切成两半
    request(
        token,
        "PUT",
        "/identity/dict-items/" + id,
        itemInput("PRODUCT_CATEGORY", "KEPT_" + tag, "临时标签"),
        400);
    // 改到同字典下已被占用的编码 → 409
    createItem(token, "PRODUCT_TAG", "TAKEN_" + tag, "已被占用");
    request(
        token,
        "PUT",
        "/identity/dict-items/" + id,
        itemInput("PRODUCT_TAG", "TAKEN_" + tag, "临时标签"),
        409);
    // 但改成同字典下没被占用的编码是可以的
    request(
        token,
        "PUT",
        "/identity/dict-items/" + id,
        itemInput("PRODUCT_TAG", "FREE_" + tag, "临时标签"),
        200);
    assertThat(
            request(token, "GET", "/identity/dict-items/" + id, null, 200).get("itemCode").asText())
        .isEqualTo("FREE_" + tag);
  }

  // ------------------------------------------------------------------
  // 变更历史：追加、不可改、前后值完整
  // ------------------------------------------------------------------

  @Test
  void historyRecordsBeforeAndAfterAndIsImmutable() throws Exception {
    String token = login();
    String tag = suffix();
    UUID id = createItem(token, "AFTERSALE_REASON", "WRONG_ITEM_" + tag, "发错货");

    var edit = new LinkedHashMap<String, Object>();
    edit.put("dictCode", "AFTERSALE_REASON");
    edit.put("itemCode", "WRONG_ITEM_" + tag);
    edit.put("name", "发错商品");
    edit.put("description", "改名");
    request(token, "PUT", "/identity/dict-items/" + id, edit, 200);
    request(
        token,
        "POST",
        "/identity/dict-items/" + id + "/deactivate",
        Map.of("status", "DISABLED", "reason", "改由品质部处理"),
        200);

    var history = request(token, "GET", "/identity/dict-history?itemId=" + id, null, 200);
    assertThat(history).hasSize(3);

    var created = byAction(history, "CREATED");
    assertThat(created.get("beforeName").isNull()).isTrue();
    assertThat(created.get("afterName").asText()).isEqualTo("发错货");

    var updated = byAction(history, "UPDATED");
    assertThat(updated.get("beforeName").asText()).isEqualTo("发错货");
    assertThat(updated.get("afterName").asText()).isEqualTo("发错商品");
    assertThat(updated.get("afterDescription").asText()).isEqualTo("改名");

    var deactivated = byAction(history, "DEACTIVATED");
    assertThat(deactivated.get("beforeStatus").asText()).isEqualTo("ACTIVE");
    assertThat(deactivated.get("afterStatus").asText()).isEqualTo("DISABLED");
    assertThat(deactivated.get("reason").asText()).isEqualTo("改由品质部处理");

    // changed_at 是系统时间，落在请求前后两个墙钟之间（timestamptz 无 −28800s 漂移）
    OffsetDateTime changedAt = OffsetDateTime.parse(deactivated.get("changedAt").asText());
    assertThat(changedAt.toInstant())
        .isBetween(
            OffsetDateTime.now().minusMinutes(5).toInstant(),
            OffsetDateTime.now().plusMinutes(1).toInstant());

    // 不可篡改：直连数据库改历史会被触发器挡下
    assertThatCode(
            () -> asSuperuser("update dict_history set reason='篡改' where tenant_id is not null"))
        .as("dict_history_immutable() 必须拒绝 UPDATE")
        .isInstanceOf(Exception.class);
    assertThatCode(() -> asSuperuser("delete from dict_history"))
        .as("dict_history_immutable() 必须拒绝 DELETE")
        .isInstanceOf(Exception.class);
  }

  private JsonNode byAction(JsonNode history, String action) {
    for (JsonNode n : history) if (action.equals(n.get("action").asText())) return n;
    throw new AssertionError("变更历史里没有 " + action + " 记录");
  }

  // ------------------------------------------------------------------
  // 下拉选项与搜索
  // ------------------------------------------------------------------

  @Test
  void optionsOnlyServeActiveItemsAndUnknownDictionaryIs404() throws Exception {
    String token = login();
    String tag = suffix();
    // 编码刻意不含彼此的子串（KILOGRAM 含 GRAM），否则 doesNotContain 断言测不出东西
    createItem(token, "UNIT_TYPE", "KILOGRAM_" + tag, "千克");
    UUID off = createItem(token, "UNIT_TYPE", "OUNCE_" + tag, "盎司");
    request(
        token,
        "POST",
        "/identity/dict-items/" + off + "/deactivate",
        Map.of("status", "DISABLED", "reason", "暂时不用"),
        200);

    var options = request(token, "GET", "/identity/dict-items/options/UNIT_TYPE", null, 200);
    assertThat(options.toString()).contains("KILOGRAM_" + tag).doesNotContain("OUNCE_" + tag);
    for (JsonNode n : options) assertThat(n.get("status").asText()).isEqualTo("ACTIVE");

    // 字典不存在 404：空数组会被调用方渲染成「这个字典没有可选项」，与「字典配错了」是两回事
    request(token, "GET", "/identity/dict-items/options/NO_SUCH_DICT", null, 404);
  }

  @Test
  void searchAndFilterAreRealQueries() throws Exception {
    String token = login();
    String tag = suffix();
    createItem(token, "ORDER_STATUS", "WAITPAY_" + tag, "待付款");
    createItem(token, "ORDER_STATUS", "PAID_" + tag, "已付款");
    UUID off = createItem(token, "ORDER_STATUS", "CLOSED_" + tag, "已关闭");
    request(
        token,
        "POST",
        "/identity/dict-items/" + off + "/deactivate",
        Map.of("status", "DISABLED", "reason", "已归档"),
        200);

    // 按编码搜
    assertThat(items(token, "dictCode=ORDER_STATUS&search=waitpay").toString())
        .contains("WAITPAY_" + tag)
        .doesNotContain("PAID_" + tag);
    // 按名称搜
    assertThat(items(token, "search=已付款").toString()).contains("PAID_" + tag);
    // 按状态过滤
    var disabled = items(token, "dictCode=ORDER_STATUS&status=DISABLED");
    assertThat(disabled).hasSize(1);
    assertThat(disabled.get(0).get("itemCode").asText()).isEqualTo("CLOSED_" + tag);
    // 非法状态被拒
    request(token, "GET", "/identity/dict-items?status=RETIRED", null, 400);
    // 默认含停用项：停用项要看得见才能判断「这个值还在用不用」
    assertThat(items(token, "dictCode=ORDER_STATUS&search=CLOSED").toString())
        .contains("CLOSED_" + tag);
  }

  // ------------------------------------------------------------------
  // 多租户三断言
  // ------------------------------------------------------------------

  @Test
  void tenantIsolationTwoVisibleForgedHeaderAndPoolReturn() throws Exception {
    String tag = suffix();
    String owner = login();

    UUID parentItem = createItem(owner, "PRODUCT_CATEGORY", "PTC_" + tag, "父租户分类");
    recordUsage(owner, parentItem, "catalog", 21);
    request(
        owner,
        "POST",
        "/identity/dict-items/" + parentItem + "/deactivate",
        Map.of("status", "DISABLED", "reason", "父租户停用"),
        200);

    // 子租户造一套完全不同的数据
    String childUser = "dict-iso-" + tag.toLowerCase();
    request(
        owner,
        "POST",
        "/identity/children",
        Map.of(
            "code",
            "DI" + tag,
            "name",
            "数据字典隔离子租户",
            "ownerUsername",
            childUser,
            "ownerPassword",
            "test-member-password"),
        200);
    String childToken = loginUser(childUser);
    UUID childItem = createItem(childToken, "PRODUCT_CATEGORY", "CTC_" + tag, "子租户分类");
    recordUsage(childToken, childItem, "trade", 5);

    // ---- 断言一：两租户互不可见
    var parentItems = items(owner, "dictCode=PRODUCT_CATEGORY");
    assertThat(parentItems.toString())
        .contains("PTC_" + tag)
        .doesNotContain("CTC_" + tag)
        .doesNotContain(childItem.toString());
    var childItems = items(childToken, "dictCode=PRODUCT_CATEGORY");
    assertThat(childItems.toString())
        .contains("CTC_" + tag)
        .doesNotContain("PTC_" + tag)
        .doesNotContain(parentItem.toString());

    // 父租户拿子租户的 id 直接读必须是 404，不是 403（否则泄露「这个 id 存在」）
    request(owner, "GET", "/identity/dict-items/" + childItem, null, 404);
    request(owner, "GET", "/identity/dict-items/" + childItem + "/usage", null, 404);
    request(
        owner,
        "POST",
        "/identity/dict-items/" + childItem + "/deactivate",
        Map.of("status", "DISABLED", "reason", "越权"),
        404);
    request(
        owner,
        "POST",
        "/identity/dict-items/" + childItem + "/usage",
        Map.of("sourceSystem", "catalog", "usageCount", 1, "note", "越权"),
        404);
    request(
        owner,
        "PUT",
        "/identity/dict-items/" + childItem,
        itemInput("PRODUCT_CATEGORY", "HACK_" + tag, "越权"),
        404);
    // 变更历史也隔离
    assertThat(request(owner, "GET", "/identity/dict-history", null, 200).toString())
        .doesNotContain("CTC_" + tag);

    // ---- 断言二：伪造 tenant header 无效。request() 每次都带随机 X-Tenant-Id。
    String forged = UUID.randomUUID().toString();
    var forgedView =
        http.perform(
                get("/identity/dict-items?dictCode=PRODUCT_CATEGORY")
                    .header("Authorization", "Bearer " + owner)
                    .header("X-Tenant-Id", forged))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forgedView)
        .as("伪造 tenant header 不得改变看到的租户")
        .contains("PTC_" + tag)
        .doesNotContain("CTC_" + tag);
    // 伪造成子租户的真实租户 id：结果仍然不变，证明租户来自已认证成员关系
    var forgedChild =
        http.perform(
                get("/identity/dict-items?dictCode=PRODUCT_CATEGORY")
                    .header("Authorization", "Bearer " + owner)
                    .header("X-Tenant-Id", tenantOf(childToken)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forgedChild).contains("PTC_" + tag).doesNotContain("CTC_" + tag);
    // 未登录查不到任何字典项
    http.perform(get("/identity/dict-items")).andExpect(status().isUnauthorized());
    http.perform(get("/identity/dict-catalog")).andExpect(status().isUnauthorized());

    // ---- 断言三：连接池归还后不串租户。池大小被压到 1，交替租户请求复用同一物理连接。
    for (int i = 0; i < 3; i++) {
      var parent = items(owner, "dictCode=PRODUCT_CATEGORY");
      assertThat(findItem(parent, "PTC_" + tag).get("status").asText())
          .as("第 %s 轮父租户停用状态不得丢", i)
          .isEqualTo("DISABLED");
      assertThat(findItem(parent, "PTC_" + tag).get("referenceCount").asLong()).isEqualTo(21);
      assertThat(parent.toString()).as("第 %s 轮父租户不得看到子租户字典项", i).doesNotContain("CTC_" + tag);

      var child = items(childToken, "dictCode=PRODUCT_CATEGORY");
      assertThat(findItem(child, "CTC_" + tag).get("referenceCount").asLong())
          .as("第 %s 轮子租户引用统计不得被父租户污染", i)
          .isEqualTo(5);
      assertThat(child.toString()).as("第 %s 轮子租户不得看到父租户字典项", i).doesNotContain("PTC_" + tag);
    }

    // 事务外（无租户上下文）直查应当为空
    assertThat(sql.fetch("select id from dict_item")).isEmpty();
    assertThat(sql.fetch("select id from dict_item_usage")).isEmpty();
    assertThat(tx().run(tenantOf(owner), () -> sql.fetch("select id from dict_item"))).isNotEmpty();
  }
}
