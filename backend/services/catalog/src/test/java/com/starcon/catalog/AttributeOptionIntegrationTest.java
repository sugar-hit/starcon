package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.util.*;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 3.2.1.2 属性选项管理：跨属性聚合视图、启用/停用生命周期、批量重排、真实分页与排序， 以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>每个用例用独立编码/类目（{@code uniqueCode} / {@code uniqueCategory}）： 容器在整个测试类共用，固定编码会互相抢占字典位。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AttributeOptionIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final java.util.Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("spu", "sku", "attribute", "research", "compliance", "assets")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "catalog." + p + "." + op))
          .toList();

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        dept,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role catalog_app login password 'test-only' nosuperuser nobypassrls");
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
            byte[] body =
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
      // 只有 attribute 读权限：所有写端点必须 403。
      actors.put(
          "Bearer reader",
          actor(
              user,
              a,
              null,
              List.of(grant("ALL", List.of("catalog.spu.read", "catalog.attribute.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "catalog_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired CatalogService catalog;
  @Autowired AttributeDictionaryService dictionary;
  @Autowired AttributeOptionService options;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actor;
  AccessService.Access actorB;

  @BeforeEach
  void setup() {
    actor = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
  }

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.attribute.read", work);
  }

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.attribute.update", work);
  }

  void write(AccessService.Access who, Runnable work) {
    access.run(
        who,
        "catalog.attribute.update",
        () -> {
          work.run();
          return null;
        });
  }

  // ------------------------------------------------------------ 夹具

  String uniqueCode(String hint) {
    return hint + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
  }

  String uniqueCategory(String hint) {
    return hint + "-" + UUID.randomUUID().toString().substring(0, 6);
  }

  static AttributeDefinitionInput input(
      String code, String name, String dataType, String usage, List<String> values) {
    return new AttributeDefinitionInput(
        code,
        name,
        dataType,
        "COMMON_DEFAULT",
        usage,
        false,
        false,
        false,
        false,
        "",
        "基本参数",
        0,
        true,
        values,
        0);
  }

  /** 建一条枚举字典条目并带上选项（全部 position=0，靠 value 决胜排序）。 */
  UUID optionDict(String code, String name, List<String> values) {
    return write(
            actor,
            () -> dictionary.save(actor, null, input(code, name, "OPTION", "DESCRIPTION", values)))
        .id();
  }

  AttributeOptionPage list(AttributeOptionQuery query) {
    return tx(actor, () -> options.list(query));
  }

  AttributeOptionPage listOf(AccessService.Access who, AttributeOptionQuery query) {
    return tx(who, () -> options.list(query));
  }

  // ------------------------------------------------------------ 聚合列表

  @Test
  void aggregateViewSpansEveryEnumAttributeWithItsOwnDefinition() {
    String colorCode = uniqueCode("AGGC");
    UUID color = optionDict(colorCode, "聚合颜色", List.of("红", "蓝"));
    UUID size = optionDict(uniqueCode("AGGS"), "聚合尺码", List.of("S", "M", "L"));

    var page = list(new AttributeOptionQuery(null, null, null, null, null, 0, 200));
    assertThat(page.items())
        .filteredOn(r -> r.attributeId().equals(color))
        .extracting(AttributeOptionOverviewRow::value)
        .containsExactlyInAnyOrder("红", "蓝");
    assertThat(page.items()).filteredOn(r -> r.attributeId().equals(size)).hasSize(3);

    // 归属信息必须一起带出来，否则用户在总览里认不出某个值属于哪个属性。
    var colorRow =
        page.items().stream()
            .filter(r -> r.attributeId().equals(color) && r.value().equals("红"))
            .findFirst()
            .orElseThrow();
    assertThat(colorRow.attributeCode()).isEqualTo(colorCode);
    assertThat(colorRow.attributeName()).isEqualTo("聚合颜色");
  }

  @Test
  void aggregateViewOnlyContainsDictionaryOptionsNotSpuLevelDefinitions() {
    String code = uniqueCode("AGGD");
    UUID mine = optionDict(code, "字典枚举", List.of("字典值"));

    // 建一个 SPU 级属性定义并给它挂选项：那是商品自己的取值，不该出现在字典总览里。
    UUID spuId =
        write(
            actor,
            () ->
                catalog.saveSpu(
                    actor,
                    null,
                    new SpuInput(
                        "AO-" + UUID.randomUUID().toString().substring(0, 8),
                        "SPU 级选项测试",
                        "描述",
                        "品牌",
                        uniqueCategory("spu"),
                        "DRAFT",
                        0)));
    UUID spuLevel =
        write(
                actor,
                () ->
                    dictionary.save(
                        actor,
                        null,
                        input(
                            uniqueCode("SPUATTR"),
                            "SPU 级属性",
                            "OPTION",
                            "DESCRIPTION",
                            List.of("SPU 私有值"))))
            .id();
    // 必须在租户上下文里改：裸 sql.execute 不带 app.current_tenant，
    // RLS 会把这条 UPDATE 直接挡掉（0 行），属性就仍然是字典条目——那会让断言假绿。
    write(
        actor,
        () -> sql.execute("update attribute_definition set spu_id=? where id=?", spuId, spuLevel));

    var page = list(new AttributeOptionQuery(null, null, null, null, null, 0, 500));
    assertThat(page.items()).extracting(AttributeOptionOverviewRow::attributeId).contains(mine);
    assertThat(page.items())
        .extracting(AttributeOptionOverviewRow::value)
        .doesNotContain("SPU 私有值");
  }

  @Test
  void aggregateViewFiltersByAttributeEnabledAndReferenced() {
    String code = uniqueCode("FILT");
    UUID dict = optionDict(code, "筛选属性", List.of("启用值", "停用值", "被规则引用"));
    UUID other = optionDict(uniqueCode("FILT"), "另一个属性", List.of("别的属性值"));

    write(actor, () -> options.toggle(actor, dict, "停用值", new AttributeOptionToggleInput(false)));
    write(
        actor,
        () ->
            dictionary.saveCategoryRule(
                actor,
                dict,
                new CategoryAttributeRuleInput(
                    uniqueCategory("FILT"), false, null, null, 0, List.of("被规则引用"))));

    // 按属性筛
    assertThat(list(new AttributeOptionQuery(null, dict, null, null, null, 0, 200)).items())
        .extracting(AttributeOptionOverviewRow::value)
        .containsExactlyInAnyOrder("启用值", "停用值", "被规则引用");
    assertThat(list(new AttributeOptionQuery(null, other, null, null, null, 0, 200)).items())
        .extracting(AttributeOptionOverviewRow::value)
        .containsExactly("别的属性值");

    // 按启用状态三态筛
    assertThat(list(new AttributeOptionQuery(null, dict, true, null, null, 0, 200)).items())
        .extracting(AttributeOptionOverviewRow::value)
        .containsExactlyInAnyOrder("启用值", "被规则引用");
    assertThat(list(new AttributeOptionQuery(null, dict, false, null, null, 0, 200)).items())
        .extracting(AttributeOptionOverviewRow::value)
        .containsExactly("停用值");

    // 按是否被引用筛：类目规则引用是真实存在的，所以这里必须能筛出差异
    assertThat(list(new AttributeOptionQuery(null, dict, null, true, null, 0, 200)).items())
        .extracting(AttributeOptionOverviewRow::value)
        .containsExactly("被规则引用");
    assertThat(list(new AttributeOptionQuery(null, dict, null, false, null, 0, 200)).items())
        .extracting(AttributeOptionOverviewRow::value)
        .containsExactlyInAnyOrder("启用值", "停用值");
  }

  @Test
  void referencedFilterFollowsTheRealReferenceCounts() {
    String code = uniqueCode("REF");
    UUID dict = optionDict(code, "引用属性", List.of("有人用", "没人用"));
    write(
        actor,
        () ->
            dictionary.saveCategoryRule(
                actor,
                dict,
                new CategoryAttributeRuleInput(
                    uniqueCategory("REF"), false, null, null, 0, List.of("有人用"))));

    var rows = list(new AttributeOptionQuery(null, dict, null, null, null, 0, 200)).items();
    var used = rows.stream().filter(r -> r.value().equals("有人用")).findFirst().orElseThrow();
    var unused = rows.stream().filter(r -> r.value().equals("没人用")).findFirst().orElseThrow();

    // categoryRuleCount 必须是真的，不能因为 productValueCount 为 0 就把整行判成「没人用」。
    assertThat(used.categoryRuleCount()).isEqualTo(1);
    assertThat(used.inUse()).isTrue();
    assertThat(used.deletable()).isFalse();
    assertThat(unused.categoryRuleCount()).isZero();
    assertThat(unused.inUse()).isFalse();
    assertThat(unused.deletable()).isTrue();
  }

  @Test
  void productValueCountReflectsDatabaseRealityWithoutFabrication() {
    String code = uniqueCode("PVC");
    UUID dict = optionDict(code, "商品引用属性", List.of("从没被赋值的值"));

    var row = list(new AttributeOptionQuery(null, dict, null, null, null, 0, 200)).items().get(0);

    // attribute_value 的复合外键仍锁在 SPU 作用域，字典条目拿不到商品属性值。
    // 这里断言它**如实为 0**，同时断言聚合视图没有偷偷编一个数出来。
    assertThat(row.productValueCount()).isZero();
    assertThat(row.categoryRuleCount()).isZero();
    assertThat(row.inUse()).isFalse();
    // 页面必须能解释这个 0 是怎么回事，而不是让运营以为「计数坏了」。
    assertThat(list(new AttributeOptionQuery(null, dict, null, null, null, 0, 200)).note())
        .contains("productValueCount")
        .contains("恒为 0");
  }

  @Test
  void keywordSearchMatchesOptionValueAndAttributeCodeAndName() {
    String code = uniqueCode("KW");
    UUID dict = optionDict(code, "搜索用属性", List.of("独特取值"));
    optionDict(uniqueCode("KWOTHER"), "别的属性", List.of("别的值"));

    assertThat(list(new AttributeOptionQuery("独特取值", null, null, null, null, 0, 200)).items())
        .extracting(AttributeOptionOverviewRow::value)
        .containsExactly("独特取值");
    assertThat(list(new AttributeOptionQuery(code, null, null, null, null, 0, 200)).items())
        .extracting(AttributeOptionOverviewRow::attributeId)
        .containsExactly(dict);
    assertThat(list(new AttributeOptionQuery("搜索用属性", null, null, null, null, 0, 200)).items())
        .extracting(AttributeOptionOverviewRow::attributeId)
        .containsExactly(dict);
  }

  @Test
  void keywordSearchEscapesWildcardCharacters() {
    String code = uniqueCode("ESC");
    UUID dict = optionDict(code, "通配符属性", List.of("100%棉", "a_b"));

    // '%' 与 '_' 必须被转义成字面量，否则搜 '%' 会把整个属性的选项全捞出来。
    assertThat(list(new AttributeOptionQuery("%", null, null, null, null, 0, 200)).items())
        .extracting(AttributeOptionOverviewRow::value)
        .containsExactly("100%棉");
    assertThat(list(new AttributeOptionQuery("_", null, null, null, null, 0, 200)).items())
        .extracting(AttributeOptionOverviewRow::value)
        .containsExactly("a_b");
    assertThat(list(new AttributeOptionQuery(null, dict, null, null, null, 0, 200)).items())
        .hasSize(2);
  }

  @Test
  void pagingIsRealAndTotalFollowsFilters() {
    UUID dict = optionDict(uniqueCode("PAGE"), "分页属性", List.of("v0", "v1", "v2", "v3", "v4"));

    var first = list(new AttributeOptionQuery(null, dict, null, null, "VALUE", 0, 2));
    assertThat(first.total()).isEqualTo(5);
    assertThat(first.size()).isEqualTo(2);
    assertThat(first.items()).hasSize(2);

    var second = list(new AttributeOptionQuery(null, dict, null, null, "VALUE", 1, 2));
    assertThat(second.total()).isEqualTo(5);
    var third = list(new AttributeOptionQuery(null, dict, null, null, "VALUE", 2, 2));
    assertThat(third.total()).isEqualTo(5);

    // 三页拼起来正好是全部 5 个，且不重不漏（真实分页，不是每页都返回前 N 条）。
    var seen = new ArrayList<String>();
    first.items().forEach(r -> seen.add(r.value()));
    second.items().forEach(r -> seen.add(r.value()));
    third.items().forEach(r -> seen.add(r.value()));
    assertThat(seen).containsExactlyInAnyOrder("v0", "v1", "v2", "v3", "v4");
    assertThat(seen).doesNotHaveDuplicates();

    // 筛选后 total 同步变化。
    write(actor, () -> options.toggle(actor, dict, "v0", new AttributeOptionToggleInput(false)));
    assertThat(list(new AttributeOptionQuery(null, dict, false, null, null, 0, 200)).total())
        .isEqualTo(1);
  }

  @Test
  void globalCountsIgnoreFiltersSoUsersSeeTheUnfilteredTotals() {
    UUID dict = optionDict(uniqueCode("CNT"), "计数属性", List.of("开1", "开2", "关1"));
    write(actor, () -> options.toggle(actor, dict, "关1", new AttributeOptionToggleInput(false)));

    var filtered = list(new AttributeOptionQuery(null, dict, true, null, null, 0, 200));
    assertThat(filtered.items()).hasSize(2);
    // enabledCount / inUseCount 是**全租户**口径，不随筛选变化：0 与「没查过」含义不同。
    assertThat(filtered.enabledCount()).isGreaterThanOrEqualTo(2);

    // 换一个更窄的筛选，两个全局计数必须**纹丝不动**——这正是「不受筛选影响」的意思。
    var narrower = list(new AttributeOptionQuery(null, dict, false, null, null, 0, 200));
    assertThat(narrower.items()).hasSize(1);
    assertThat(narrower.enabledCount()).isEqualTo(filtered.enabledCount());
    assertThat(narrower.inUseCount()).isEqualTo(filtered.inUseCount());

    // inUseCount 至少要能反映真实的类目规则引用（前面的用例已在本租户建过引用）。
    assertThat(filtered.inUseCount()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void sortingIsAWhitelistAndFallsBackToPositionForUnknownValues() {
    UUID dict = optionDict(uniqueCode("SORT"), "排序属性", List.of("bbb", "aaa", "ccc"));
    write(actor, () -> options.toggle(actor, dict, "aaa", new AttributeOptionToggleInput(false)));

    assertThat(list(new AttributeOptionQuery(null, dict, null, null, "VALUE", 0, 200)).items())
        .extracting(AttributeOptionOverviewRow::value)
        .containsExactly("aaa", "bbb", "ccc");
    assertThat(list(new AttributeOptionQuery(null, dict, null, null, "CREATED_AT", 0, 200)).items())
        .extracting(AttributeOptionOverviewRow::value)
        .containsExactlyInAnyOrder("aaa", "bbb", "ccc");

    // 注入串与未知档位都必须退回默认排序，不能原样拼进 SQL，也不能直接报错。
    assertThat(
            list(new AttributeOptionQuery(
                    null, dict, null, null, "value; drop table attribute_option", 0, 200))
                .items())
        .hasSize(3);
    assertThat(list(new AttributeOptionQuery(null, dict, null, null, "NOT_A_SORT", 0, 200)).items())
        .hasSize(3);
    assertThat(list(new AttributeOptionQuery(null, dict, null, null, "REFERENCES", 0, 200)).items())
        .hasSize(3);
  }

  // ------------------------------------------------------------ 启用 / 停用

  @Test
  void disablingAnOptionKeepsItReadableAndOnlyBlocksNewUse() {
    UUID dict = optionDict(uniqueCode("LIFE"), "生命周期属性", List.of("在用值", "停用值"));
    write(
        actor,
        () ->
            dictionary.saveCategoryRule(
                actor,
                dict,
                new CategoryAttributeRuleInput(
                    uniqueCategory("LIFE"), false, null, null, 0, List.of("停用值"))));

    var toggled =
        write(
                actor,
                () -> options.toggle(actor, dict, "停用值", new AttributeOptionToggleInput(false)))
            .enabled();
    assertThat(toggled).isFalse();

    // 停用不删除：值仍在库里，引用计数照常可见（历史单据还要靠它解析）。
    var row =
        list(new AttributeOptionQuery(null, dict, null, null, null, 0, 200)).items().stream()
            .filter(r -> r.value().equals("停用值"))
            .findFirst()
            .orElseThrow();
    assertThat(row.enabled()).isFalse();
    assertThat(row.inUse()).isTrue();
    assertThat(row.deletable()).isFalse();

    // 重新启用是允许的（误停用要能改回来）。
    assertThat(
            write(
                    actor,
                    () -> options.toggle(actor, dict, "停用值", new AttributeOptionToggleInput(true)))
                .enabled())
        .isTrue();
  }

  @Test
  void referencedOptionCanBeDisabledButNotDeleted() {
    String code = uniqueCode("GARD");
    UUID dict = optionDict(code, "有引用属性", List.of("被引用的值", "自由的值"));
    String category = uniqueCategory("GARD");
    write(
        actor,
        () ->
            dictionary.saveCategoryRule(
                actor,
                dict,
                new CategoryAttributeRuleInput(category, false, null, null, 0, List.of("被引用的值"))));

    // 被引用的选项：停用可以
    assertThat(
            write(
                    actor,
                    () ->
                        options.toggle(actor, dict, "被引用的值", new AttributeOptionToggleInput(false)))
                .enabled())
        .isFalse();
    // 删除不行——守卫必须对聚合视图同样生效
    assertThatThrownBy(() -> write(actor, () -> dictionary.deleteOption(actor, dict, "被引用的值")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
    // 无引用的选项：删除可以
    write(actor, () -> dictionary.deleteOption(actor, dict, "自由的值"));
    assertThat(list(new AttributeOptionQuery(null, dict, null, null, null, 0, 200)).items())
        .extracting(AttributeOptionOverviewRow::value)
        .containsExactly("被引用的值");
  }

  @Test
  void enablingAnOptionUnderADisabledAttributeIsRejectedWithAReadableReason() {
    String code = uniqueCode("PARENT");
    UUID dict = optionDict(code, "父属性", List.of("子选项"));
    write(actor, () -> options.toggle(actor, dict, "子选项", new AttributeOptionToggleInput(false)));
    // 停用父属性。选项列表必须原样带上：3.2.1.1 的规则是枚举属性至少一个选项，
    // 而**更新不接管选项集合**（选项有独立的子资源），所以这里传的是当前值而不是空集。
    write(
        actor,
        () ->
            dictionary.save(
                actor,
                dict,
                new AttributeDefinitionInput(
                    code,
                    "父属性",
                    "OPTION",
                    "COMMON_DEFAULT",
                    "DESCRIPTION",
                    false,
                    false,
                    false,
                    false,
                    "",
                    "基本参数",
                    0,
                    false,
                    List.of("子选项"),
                    0)));

    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () -> options.toggle(actor, dict, "子选项", new AttributeOptionToggleInput(true))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> {
              assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400);
              assertThat(e.getMessage()).contains("所属属性已停用");
            });
  }

  @Test
  void togglingUnknownOptionOrForeignAttributeIsNotFound() {
    UUID dict = optionDict(uniqueCode("MISS"), "缺失选项属性", List.of("存在的值"));
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        options.toggle(actor, dict, "不存在", new AttributeOptionToggleInput(false))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
  }

  // ------------------------------------------------------------ 批量重排

  @Test
  void batchReorderRewritesEveryPositionInOneGo() {
    UUID dict = optionDict(uniqueCode("REORD"), "重排属性", List.of("甲", "乙", "丙"));

    var saved =
        write(
                actor,
                () ->
                    options.reorder(
                        actor,
                        new AttributeOptionOrderInput(
                            dict,
                            List.of(
                                new AttributeOptionOrderItem("丙", 0),
                                new AttributeOptionOrderItem("甲", 1),
                                new AttributeOptionOrderItem("乙", 2)))))
            .applied();

    // applied 只数**真正改动了的行**：夹具里三个选项的 position 都是 0，
    // 而「丙 → 0」本来就位，所以只有两行需要写。这让重复提交同一份顺序天然幂等。
    assertThat(saved).isEqualTo(2);
    assertThat(
            tx(actor, () -> dictionary.options(dict)).stream()
                .map(AttributeOptionRow::value)
                .toList())
        .containsExactly("丙", "甲", "乙");
    // 聚合视图按 position 读出来的顺序必须与逐条读取一致。
    assertThat(list(new AttributeOptionQuery(null, dict, null, null, null, 0, 200)).items())
        .extracting(AttributeOptionOverviewRow::value)
        .containsExactly("丙", "甲", "乙");
  }

  @Test
  void batchReorderIsIdempotentWhenPositionsAlreadyMatch() {
    UUID dict = optionDict(uniqueCode("IDEM"), "幂等重排属性", List.of("一", "二"));
    write(
        actor,
        () ->
            options.reorder(
                actor,
                new AttributeOptionOrderInput(
                    dict,
                    List.of(
                        new AttributeOptionOrderItem("一", 0),
                        new AttributeOptionOrderItem("二", 1)))));

    var again =
        write(
            actor,
            () ->
                options.reorder(
                    actor,
                    new AttributeOptionOrderInput(
                        dict,
                        List.of(
                            new AttributeOptionOrderItem("一", 0),
                            new AttributeOptionOrderItem("二", 1)))));
    assertThat(again.submitted()).isEqualTo(2);
    assertThat(again.applied()).isZero();
  }

  @Test
  void batchReorderRejectsUnknownDuplicateAndOversizedSubmissions() {
    UUID dict = optionDict(uniqueCode("BADR"), "非法重排属性", List.of("甲", "乙"));
    UUID other = optionDict(uniqueCode("BADO"), "别人的属性", List.of("别人的值"));

    // 不属于该属性的值：不能静默改到别人的选项上
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        options.reorder(
                            actor,
                            new AttributeOptionOrderInput(
                                dict, List.of(new AttributeOptionOrderItem("别人的值", 0))))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> {
              assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400);
              assertThat(e.getMessage()).contains("不属于该属性");
            });

    // 同批次重复值：会按顺序覆盖同一个值，用户以为排了两次
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        options.reorder(
                            actor,
                            new AttributeOptionOrderInput(
                                dict,
                                List.of(
                                    new AttributeOptionOrderItem("甲", 0),
                                    new AttributeOptionOrderItem("甲", 1))))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> {
              assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400);
              assertThat(e.getMessage()).contains("重复");
            });

    // 超出单项上限的批量：不能拿它当批量写接口绕过 50 项上限
    var tooMany = new ArrayList<AttributeOptionOrderItem>();
    for (int i = 0; i < 51; i++) tooMany.add(new AttributeOptionOrderItem("值" + i, i));
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () -> options.reorder(actor, new AttributeOptionOrderInput(dict, tooMany))))
        .isInstanceOf(ResponseStatusException.class);

    assertThat(list(new AttributeOptionQuery(null, other, null, null, null, 0, 200)).items())
        .extracting(AttributeOptionOverviewRow::value)
        .containsExactly("别人的值");
  }

  @Test
  void batchReorderLeavesNoPartialStateWhenOneEntryIsInvalid() {
    UUID dict = optionDict(uniqueCode("ATOM"), "原子性属性", List.of("甲", "乙", "丙"));
    // 第二项非法：整批必须失败，甲的位置也不能被改掉。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        options.reorder(
                            actor,
                            new AttributeOptionOrderInput(
                                dict,
                                List.of(
                                    new AttributeOptionOrderItem("丙", 9),
                                    new AttributeOptionOrderItem("不存在", 1))))))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(
            tx(actor, () -> dictionary.options(dict)).stream()
                .map(AttributeOptionRow::value)
                .toList())
        .containsExactlyInAnyOrder("甲", "乙", "丙");
  }

  // ------------------------------------------------------------ 租户隔离三断言

  @Test
  void optionManagementIsTenantIsolatedAndHeaderForgeryIsIgnored() throws Exception {
    String myCode = uniqueCode("ISO");
    UUID myId = optionDict(myCode, "我租户的颜色", List.of("我租户的颜色值", "我租户第二个值"));
    write(
        actor,
        () ->
            dictionary.saveCategoryRule(
                actor,
                myId,
                new CategoryAttributeRuleInput(
                    uniqueCategory("ISO"), true, null, null, 0, List.of("我租户的颜色值"))));

    // B 租户建自己的同编码字典条目（**同 code，不同租户**）
    UUID theirId =
        write(
                actorB,
                () ->
                    dictionary.save(
                        actorB,
                        null,
                        input(myCode, "B 租户同编码属性", "OPTION", "DESCRIPTION", List.of("B 的值"))))
            .id();

    // 1) 两租户互不可见
    // 断言值而不是 id：一条属性有两个选项，按 id 抽会得到两行同一个 id（那是正确行为，不是重复）。
    assertThat(
            listOf(actor, new AttributeOptionQuery(myCode, null, null, null, null, 0, 200)).items())
        .extracting(AttributeOptionOverviewRow::value)
        .containsExactlyInAnyOrder("我租户的颜色值", "我租户第二个值");
    assertThat(
            listOf(actorB, new AttributeOptionQuery(myCode, null, null, null, null, 0, 200))
                .items())
        .extracting(AttributeOptionOverviewRow::value)
        .containsExactly("B 的值");
    assertThat(
            listOf(actor, new AttributeOptionQuery(myCode, null, null, null, null, 0, 200)).items())
        .extracting(AttributeOptionOverviewRow::attributeId)
        .containsOnly(myId);
    assertThat(
            listOf(actorB, new AttributeOptionQuery(myCode, null, null, null, null, 0, 200))
                .items())
        .extracting(AttributeOptionOverviewRow::attributeId)
        .containsOnly(theirId);
    // B 的总览里永远看不到 A 的选项
    assertThat(
            listOf(actorB, new AttributeOptionQuery(null, null, null, null, null, 0, 500)).items())
        .extracting(AttributeOptionOverviewRow::value)
        .doesNotContain("我租户的颜色值");
    // 按 A 的 attributeId 直接筛也筛不出来（RLS 已把行过滤掉，不是服务层漏判）
    assertThat(
            listOf(actorB, new AttributeOptionQuery(null, myId, null, null, null, 0, 200)).items())
        .isEmpty();
    // B 停用 / 重排 A 的选项 → 404
    assertThatThrownBy(
            () ->
                write(
                    actorB,
                    () ->
                        options.toggle(
                            actorB, myId, "我租户的颜色值", new AttributeOptionToggleInput(false))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    assertThatThrownBy(
            () ->
                write(
                    actorB,
                    () ->
                        options.reorder(
                            actorB,
                            new AttributeOptionOrderInput(
                                myId, List.of(new AttributeOptionOrderItem("我租户的颜色值", 7))))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    // B 删 A 的选项 → 404（守卫先看归属，再看引用）
    assertThatThrownBy(() -> write(actorB, () -> dictionary.deleteOption(actorB, myId, "我租户的颜色值")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    // A 的数据仍在
    assertThat(list(new AttributeOptionQuery(null, myId, null, null, null, 0, 200)).items())
        .hasSize(2);

    // 2) 伪造 tenant header 无效：租户只来自已认证的成员关系
    http.perform(
            get("/catalog/attributes/options?attributeId=" + myId)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.not(containsString("我租户的颜色值"))));
    http.perform(
            get("/catalog/attributes/options?q=" + myCode)
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(myCode)));
    // 伪造头也不能借道写
    http.perform(
            put("/catalog/attributes/options/lifecycle")
                .param("attributeId", myId.toString())
                .param("value", "我租户的颜色值")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType("application/json")
                .content("{\"enabled\":false}"))
        .andExpect(status().isNotFound());
    http.perform(
            put("/catalog/attributes/options/order")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType("application/json")
                .content(
                    "{\"attributeId\":\""
                        + myId
                        + "\",\"items\":[{\"value\":\"我租户的颜色值\",\"position\":5}]}"))
        .andExpect(status().isNotFound());
    assertThat(list(new AttributeOptionQuery(null, myId, null, null, null, 0, 200)).items())
        .allMatch(AttributeOptionOverviewRow::enabled);

    // 3) 连接池归还后不串租户（单连接反复交替）
    for (int i = 0; i < 5; i++) {
      assertThat(
              listOf(actor, new AttributeOptionQuery(null, myId, null, null, null, 0, 200)).items())
          .extracting(AttributeOptionOverviewRow::value)
          .containsExactlyInAnyOrder("我租户的颜色值", "我租户第二个值");
      assertThat(
              listOf(actorB, new AttributeOptionQuery(null, theirId, null, null, null, 0, 200))
                  .items())
          .extracting(AttributeOptionOverviewRow::value)
          .containsExactly("B 的值");
      assertThat(
              listOf(actorB, new AttributeOptionQuery(null, myId, null, null, null, 0, 200))
                  .items())
          .isEmpty();
      assertThatThrownBy(
              () ->
                  write(
                      actorB,
                      () ->
                          options.toggle(
                              actorB, myId, "我租户的颜色值", new AttributeOptionToggleInput(false))))
          .isInstanceOf(ResponseStatusException.class);
      assertThat(
              listOf(actor, new AttributeOptionQuery(null, myId, null, true, null, 0, 200)).items())
          .extracting(AttributeOptionOverviewRow::value)
          .containsExactly("我租户的颜色值");
      assertThat(
              listOf(actorB, new AttributeOptionQuery(null, theirId, null, true, null, 0, 200))
                  .items())
          .isEmpty();
    }
  }

  // ------------------------------------------------------------ 权限与 HTTP

  @Test
  void readOnlyRoleCanListButNotWrite() throws Exception {
    http.perform(get("/catalog/attributes/options").header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());
    http.perform(
            put("/catalog/attributes/options/lifecycle?attributeId="
                    + UUID.randomUUID()
                    + "&value=x")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content("{\"enabled\":false}"))
        .andExpect(status().isForbidden());
    http.perform(
            put("/catalog/attributes/options/order")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(
                    "{\"attributeId\":\""
                        + UUID.randomUUID()
                        + "\",\"items\":[{\"value\":\"x\",\"position\":0}]}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void unauthenticatedAccessIsRejected() throws Exception {
    http.perform(get("/catalog/attributes/options")).andExpect(status().isUnauthorized());
    http.perform(
            put("/catalog/attributes/options/order")
                .contentType("application/json")
                .content(
                    "{\"attributeId\":\""
                        + UUID.randomUUID()
                        + "\",\"items\":[{\"value\":\"x\",\"position\":0}]}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void httpEndpointsExposeTheRealWorkflow() throws Exception {
    String code = uniqueCode("HTTP");
    UUID dict = optionDict(code, "HTTP 属性", List.of("甲", "乙", "丙"));

    String body =
        http.perform(
                get("/catalog/attributes/options?attributeId=" + dict + "&sort=VALUE")
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).contains("甲").contains("乙").contains("丙");

    http.perform(
            put("/catalog/attributes/options/lifecycle")
                .param("attributeId", dict.toString())
                .param("value", "乙")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().is4xxClientError()); // 缺 body，不该当成成功

    http.perform(
            put("/catalog/attributes/options/lifecycle")
                .param("attributeId", dict.toString())
                .param("value", "乙")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"enabled\":false}"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("\"enabled\":false")));

    http.perform(
            put("/catalog/attributes/options/order")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"attributeId\":\""
                        + dict
                        + "\",\"items\":[{\"value\":\"丙\",\"position\":0},{\"value\":\"甲\",\"position\":1},{\"value\":\"乙\",\"position\":2}]}"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("\"submitted\":3")))
        .andExpect(content().string(containsString("\"applied\":2")));

    assertThat(
            http.perform(
                    get("/catalog/attributes/options?attributeId=" + dict + "&sort=POSITION")
                        .header("Authorization", "Bearer tenant-a"))
                .andReturn()
                .getResponse()
                .getContentAsString())
        .contains("丙");
  }

  @Test
  void v12MigrationCreatedTheIndexesTheAggregateViewDependsOn() {
    for (String index :
        List.of(
            "attribute_option_enabled_list",
            "attribute_option_attribute_lookup",
            "attribute_option_created",
            "category_attribute_option_reverse",
            "attribute_value_item_option")) {
      boolean exists =
          Boolean.TRUE.equals(
              tx(
                  actor,
                  () ->
                      sql.fetchOne(
                              "select count(*) from pg_class c join pg_namespace n"
                                  + " on n.oid=c.relnamespace"
                                  + " where c.relkind='i' and c.relname=?",
                              index)
                          .get(0, Boolean.class)));
      assertThat(exists).as("index %s must exist", index).isTrue();
    }

    // 新表都不该有：V12 只加索引，列由 V11 建好了。
    assertThat(
            tx(
                actor,
                () ->
                    sql.fetchOne(
                            "select count(*) from information_schema.columns"
                                + " where table_name='attribute_option' and column_name='position'")
                        .get(0, Long.class)))
        .isEqualTo(1);
  }

  @Test
  void deliveredDictionaryOptionEndpointsStillWorkAlongsideTheAggregateView() {
    // 3.2.1.1 的逐属性选项路径没有被本模块改坏：这是两个模块共处一张表的回归证明。
    UUID dict = optionDict(uniqueCode("REGR"), "回归属性", List.of("红", "蓝"));
    assertThat(tx(actor, () -> dictionary.options(dict))).hasSize(2);
    write(actor, () -> dictionary.addOption(actor, dict, new AttributeOptionInput("绿", 2, true)));
    assertThat(list(new AttributeOptionQuery(null, dict, null, null, null, 0, 200)).items())
        .extracting(AttributeOptionOverviewRow::value)
        .containsExactlyInAnyOrder("红", "蓝", "绿");
  }
}
