package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
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
 * 3.2.1.1 属性定义列表：租户级属性字典 CRUD、选项集合、类目绑定规则、真实分页， 以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AttributeDictionaryIntegrationTest {
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

  /** 字典编码在租户内唯一：每个用例用独立编码，避免用例之间互相抢占字典位。 */
  String uniqueCode(String hint) {
    return hint + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
  }

  /** 最简字典条目：文本、不筛选、不必填、按 SPU 覆写模式下不适用（COMMON_DEFAULT）、启用。 */
  static AttributeDefinitionInput input(
      String code, String name, String dataType, String usage, List<String> options) {
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
        options,
        0);
  }

  /** 更新既有字典条目用：除编码/类型外，其余字段一律改掉，用来断言可变字段真的可变。 */
  static AttributeDefinitionInput rename(String code, String name, long version) {
    return new AttributeDefinitionInput(
        code,
        name,
        "TEXT",
        "COMMON_DEFAULT",
        "COMPLIANCE",
        false,
        false,
        true,
        false,
        "",
        "合规信息",
        1,
        false,
        List.of(),
        version);
  }

  /** 每个用例用独立类目：容器是整个测试类共用的，固定类目会互相串数据。 */
  static String uniqueCategory(String hint) {
    return hint + "-" + UUID.randomUUID().toString().substring(0, 6);
  }

  /** 建一条文本字典条目（最简形态）。 */
  UUID dict(String code, String name) {
    return write(
            actor,
            () -> dictionary.save(actor, null, input(code, name, "TEXT", "DESCRIPTION", List.of())))
        .id();
  }

  /** 建一条枚举字典条目并带上选项。 */
  UUID optionDict(String code, String name, List<String> options) {
    return write(
            actor,
            () -> dictionary.save(actor, null, input(code, name, "OPTION", "DESCRIPTION", options)))
        .id();
  }

  UUID spu(String category) {
    return write(
        actor,
        () ->
            catalog.saveSpu(
                actor,
                null,
                new SpuInput(
                    "AD-" + UUID.randomUUID().toString().substring(0, 8),
                    "属性字典测试商品",
                    "描述",
                    "品牌",
                    category,
                    "DRAFT",
                    0)));
  }

  static String between(String text, String prefix, String suffix) {
    int start = text.indexOf(prefix);
    if (start < 0) throw new IllegalStateException("prefix not found: " + text);
    start += prefix.length();
    int end = text.indexOf(suffix, start);
    if (end < 0) throw new IllegalStateException("suffix not found: " + text);
    return text.substring(start, end);
  }

  // ------------------------------------------------------------ 字典 CRUD

  @Test
  void dictionaryEntryIsCreatedWithAllBlueprintFields() {
    String code = uniqueCode("DICT");
    var saved =
        write(
            actor,
            () ->
                dictionary.save(
                    actor,
                    null,
                    new AttributeDefinitionInput(
                        code,
                        "含水率",
                        "DECIMAL",
                        "COMMON_DEFAULT",
                        "LOGISTICS",
                        false,
                        true,
                        true,
                        false,
                        "%",
                        "物流参数",
                        3,
                        true,
                        List.of(),
                        0)));
    assertThat(saved.version()).isZero();

    var row =
        tx(
                actor,
                () ->
                    dictionary.list(
                        new AttributeDefinitionQuery(null, null, null, null, null, null, 0, 20)))
            .items()
            .stream()
            .filter(r -> r.code().equals(code))
            .findFirst()
            .orElseThrow();
    // 蓝图点名的字段逐个断言，不能只看「建成功了」。
    assertThat(row.name()).isEqualTo("含水率");
    assertThat(row.dataType()).isEqualTo("DECIMAL");
    assertThat(row.usage()).isEqualTo("LOGISTICS");
    assertThat(row.filterable()).isTrue();
    assertThat(row.multiple()).isFalse();
    assertThat(row.required()).isTrue();
    assertThat(row.unit()).isEqualTo("%");
    assertThat(row.groupName()).isEqualTo("物流参数");
    assertThat(row.position()).isEqualTo(3);
    assertThat(row.enabled()).isTrue();
  }

  @Test
  void duplicateCodeInSameTenantIsRejected() {
    String code = uniqueCode("DUP");
    dict(code, "第一个");
    assertThatThrownBy(() -> dict(code, "第二个"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
  }

  @Test
  void codeAndDataTypeAreImmutableAfterCreation() {
    String code = uniqueCode("IMM");
    UUID id = dict(code, "原名");
    long version =
        tx(
                actor,
                () ->
                    dictionary.list(
                        new AttributeDefinitionQuery(null, null, null, null, null, null, 0, 200)))
            .items()
            .stream()
            .filter(r -> r.id().equals(id))
            .findFirst()
            .orElseThrow()
            .version();

    // 改编码：必须被拒并说明原因（SPU 级定义与规格轴都按 code 对齐它）。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        dictionary.save(
                            actor,
                            id,
                            input(uniqueCode("OTHER"), "改名了", "TEXT", "DESCRIPTION", List.of()))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));

    // 改数据类型：同样被拒（已存在的取值与类目范围都按这个类型解析）。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        dictionary.save(
                            actor,
                            id,
                            new AttributeDefinitionInput(
                                code,
                                "原名",
                                "DECIMAL",
                                "COMMON_DEFAULT",
                                "DESCRIPTION",
                                false,
                                false,
                                false,
                                false,
                                "",
                                "基本参数",
                                0,
                                true,
                                List.of(),
                                version))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));

    // 名称/用途/筛选这些可变字段要真的能改。
    var updated = write(actor, () -> dictionary.save(actor, id, rename(code, "新名", version)));
    assertThat(updated.version()).isEqualTo(version + 1);
    assertThat(updated.optionCount()).isZero();
  }

  @Test
  void staleVersionIsRejectedAsConflict() {
    String code = uniqueCode("VER");
    UUID id = dict(code, "乐观锁");
    // 存盘时 version=0；带 99 提交必须 409，且不能把改动写进去。
    assertThatThrownBy(
            () -> write(actor, () -> dictionary.save(actor, id, rename(code, "不该写进去", 99))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
    assertThat(tx(actor, () -> dictionary.options(id))).isEmpty();
    var row =
        tx(
                actor,
                () ->
                    dictionary.list(
                        new AttributeDefinitionQuery(code, null, null, null, null, null, 0, 20)))
            .items()
            .getFirst();
    assertThat(row.name()).isEqualTo("乐观锁");
  }

  @Test
  void invalidDefinitionIsRejectedWithReadableReason() {
    // 枚举属性必须给选项。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        dictionary.save(
                            actor,
                            null,
                            input(
                                uniqueCode("NOOPT"), "枚举没选项", "OPTION", "DESCRIPTION", List.of()))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("至少提供一个选项");
    // 非枚举属性不能挂选项。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        dictionary.save(
                            actor,
                            null,
                            input(
                                uniqueCode("BADOPT"),
                                "文本挂选项",
                                "TEXT",
                                "DESCRIPTION",
                                List.of("棉")))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("只有枚举属性");
    // 规格轴必须是必填单选枚举 + 用途=规格。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        dictionary.save(
                            actor,
                            null,
                            new AttributeDefinitionInput(
                                uniqueCode("AXBAD"),
                                "规格轴形态不对",
                                "TEXT",
                                "COMMON_DEFAULT",
                                "SPECIFICATION",
                                false,
                                false,
                                true,
                                true,
                                "",
                                "基本参数",
                                0,
                                true,
                                List.of(),
                                0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必填单选枚举属性");
    // 规格轴必须登记为规格用途。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        dictionary.save(
                            actor,
                            null,
                            new AttributeDefinitionInput(
                                uniqueCode("AXUSE"),
                                "用途不对",
                                "OPTION",
                                "SKU_ONLY",
                                "DESCRIPTION",
                                false,
                                true,
                                false,
                                true,
                                "",
                                "基本参数",
                                0,
                                true,
                                List.of("XL"),
                                0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("用途必须是「规格」");
    // 同样形态但用途=规格：合法（V11 的 attribute_definition_variant_usage 在应用层的同口径）。
    write(
        actor,
        () ->
            dictionary.save(
                actor,
                null,
                new AttributeDefinitionInput(
                    uniqueCode("AXOK"),
                    "规格轴属性",
                    "OPTION",
                    "SKU_ONLY",
                    "SPECIFICATION",
                    false,
                    true,
                    false,
                    true,
                    "",
                    "规格参数",
                    0,
                    true,
                    List.of("XL"),
                    0)));
  }

  @Test
  void deletingDictionaryEntryRemovesItFromTheList() {
    String code = uniqueCode("DEL");
    UUID id = dict(code, "待删除");
    write(actor, () -> dictionary.delete(actor, id, 0));
    var codes =
        tx(
                actor,
                () ->
                    dictionary.list(
                        new AttributeDefinitionQuery(null, null, null, null, null, null, 0, 200)))
            .items()
            .stream()
            .map(AttributeDefinitionRow::code)
            .toList();
    assertThat(codes).doesNotContain(code);
  }

  // ------------------------------------------------------------ 真实分页与搜索

  @Test
  void pagingIsRealAndTotalFollowsFilters() {
    String prefix = uniqueCode("PAGE");
    List<String> codes = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      String code = prefix + i;
      codes.add(code);
      dict(code, "分页条目" + i);
    }
    var firstPage =
        tx(
            actor,
            () ->
                dictionary.list(
                    new AttributeDefinitionQuery(prefix, null, null, null, null, null, 0, 2)));
    assertThat(firstPage.items()).hasSize(2);
    assertThat(firstPage.total()).isEqualTo(5);
    assertThat(firstPage.size()).isEqualTo(2);

    var secondPage =
        tx(
            actor,
            () ->
                dictionary.list(
                    new AttributeDefinitionQuery(prefix, null, null, null, null, null, 1, 2)));
    assertThat(secondPage.items()).hasSize(2);
    // 两页不能重叠：真实分页，不是把同一批数据换个标签。
    assertThat(secondPage.items().stream().map(AttributeDefinitionRow::code).toList())
        .doesNotContainAnyElementsOf(
            firstPage.items().stream().map(AttributeDefinitionRow::code).toList());

    var lastPage =
        tx(
            actor,
            () ->
                dictionary.list(
                    new AttributeDefinitionQuery(prefix, null, null, null, null, null, 2, 2)));
    assertThat(lastPage.items()).hasSize(1);

    // 按名称搜索（关键词要真的能命中，不是摆设）。
    var byName =
        tx(
            actor,
            () ->
                dictionary.list(
                    new AttributeDefinitionQuery("分页条目3", null, null, null, null, null, 0, 20)));
    assertThat(byName.items()).extracting(AttributeDefinitionRow::code).containsExactly(prefix + 3);
    assertThat(byName.total()).isEqualTo(1);

    // 按数据类型过滤后 total 同步收窄。
    var byType =
        tx(
            actor,
            () ->
                dictionary.list(
                    new AttributeDefinitionQuery(
                        prefix, "DECIMAL", null, null, null, null, 0, 20)));
    assertThat(byType.items()).isEmpty();
    assertThat(byType.total()).isZero();

    assertThat(codes).hasSize(5);
  }

  @Test
  void searchEscapesWildcardCharacters() {
    String code = uniqueCode("PCT");
    dict(code, "含百分号100%");
    // 不转义的话 '%' 会变成通配符，把整本字典都捞出来。
    var page =
        tx(
            actor,
            () ->
                dictionary.list(
                    new AttributeDefinitionQuery(
                        "含百分号100%", null, null, null, null, null, 0, 200)));
    assertThat(page.items()).extracting(AttributeDefinitionRow::code).containsExactly(code);
  }

  @Test
  void listOnlyContainsDictionaryRowsNotSpuLevelDefinitions() {
    String code = uniqueCode("SCOPE");
    UUID id = dict(code, "字典条目");
    // 建一个同编码的 SPU 级定义（3.2.2.1 的写入路径）。
    UUID spuId = spu("服饰");
    write(
        actor,
        () ->
            catalog.saveAttribute(
                actor,
                spuId,
                null,
                new AttributeInput(
                    code,
                    "SPU 级属性",
                    "TEXT",
                    "COMMON_DEFAULT",
                    false,
                    false,
                    false,
                    "",
                    "基本参数",
                    List.of(),
                    List.of(),
                    0)));
    var page =
        tx(
            actor,
            () ->
                dictionary.list(
                    new AttributeDefinitionQuery(code, null, null, null, null, null, 0, 200)));
    // 同编码的字典行与 SPU 级行不在同一个命名空间里：字典页只列字典行。
    assertThat(page.items()).extracting(AttributeDefinitionRow::code).containsExactly(code);
    assertThat(page.items().getFirst().id()).isEqualTo(id);
  }

  // ------------------------------------------------------------ 选项集合

  @Test
  void optionsAreCreatedListedUpdatedAndDeleted() {
    UUID id = optionDict(uniqueCode("OPT"), "颜色", List.of("红色", "蓝色"));
    var rows = tx(actor, () -> dictionary.options(id));
    assertThat(rows).extracting(AttributeOptionRow::value).containsExactly("红色", "蓝色");
    assertThat(rows).allMatch(AttributeOptionRow::enabled);

    write(actor, () -> dictionary.addOption(actor, id, new AttributeOptionInput(" 绿色 ", 5, true)));
    assertThat(tx(actor, () -> dictionary.options(id)))
        .extracting(AttributeOptionRow::value)
        .containsExactly("红色", "蓝色", "绿色");
    // 值存的是 trim 后的形态，位置按传入的落库。
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                            "select value,position from attribute_option"
                                + " where attribute_id=? and value='绿色'",
                            id))
                .get("position", Integer.class))
        .isEqualTo(5);

    // 改位置与启用状态（停用不删除）。
    write(
        actor,
        () -> dictionary.updateOption(actor, id, "红色", new AttributeOptionInput("红色", 9, false)));
    var disabled =
        tx(actor, () -> dictionary.options(id)).stream()
            .filter(r -> r.value().equals("红色"))
            .findFirst()
            .orElseThrow();
    assertThat(disabled.enabled()).isFalse();
    assertThat(disabled.position()).isEqualTo(9);

    write(actor, () -> dictionary.deleteOption(actor, id, "绿色"));
    assertThat(tx(actor, () -> dictionary.options(id)))
        .extracting(AttributeOptionRow::value)
        .containsExactlyInAnyOrder("红色", "蓝色");
  }

  @Test
  void registeringAnExistingOptionIsIdempotentNotADuplicateRow() {
    UUID id = optionDict(uniqueCode("IDEM"), "尺码", List.of("XL"));
    write(actor, () -> dictionary.addOption(actor, id, new AttributeOptionInput("XL", 7, false)));
    var rows = tx(actor, () -> dictionary.options(id));
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().position()).isEqualTo(7);
    assertThat(rows.getFirst().enabled()).isFalse();
  }

  @Test
  void optionsAreRejectedOnNonOptionAttributes() {
    UUID id = dict(uniqueCode("NOTOPT"), "纯文本");
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () -> dictionary.addOption(actor, id, new AttributeOptionInput("棉", 0, true))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("只有枚举属性");
  }

  @Test
  void optionReferencedByCategoryRuleCannotBeDeleted() {
    UUID id = optionDict(uniqueCode("REFDEL"), "尺码", List.of("XL", "L"));
    write(
        actor,
        () ->
            dictionary.saveCategoryRule(
                actor,
                id,
                new CategoryAttributeRuleInput("服饰", true, null, null, 0, List.of("XL"))));
    // 选项列表要把「被几条类目规则引用」讲出来，否则用户点了删除才知道删不掉。
    var option =
        tx(actor, () -> dictionary.options(id)).stream()
            .filter(r -> r.value().equals("XL"))
            .findFirst()
            .orElseThrow();
    assertThat(option.categoryRuleCount()).isEqualTo(1);
    assertThat(option.inUse()).isTrue();

    assertThatThrownBy(() -> write(actor, () -> dictionary.deleteOption(actor, id, "XL")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("类目规则");
    // 不被引用的那个照常能删。
    write(actor, () -> dictionary.deleteOption(actor, id, "L"));
  }

  @Test
  void maxOptionCountIsEnforced() {
    UUID id = optionDict(uniqueCode("MAXOPT"), "多值", List.of("初始选项"));
    for (int i = 0; i < 49; i++) {
      int position = i;
      write(
          actor,
          () ->
              dictionary.addOption(
                  actor, id, new AttributeOptionInput("选项" + UUID.randomUUID(), position, true)));
    }
    // 初始 1 个 + 新增 49 个 = 50，正好到上限。
    assertThat(tx(actor, () -> dictionary.options(id))).hasSize(50);
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        dictionary.addOption(
                            actor,
                            id,
                            new AttributeOptionInput("第51个" + UUID.randomUUID(), 51, true))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("最多配置 50 个选项");
  }

  // ------------------------------------------------------------ 类目绑定规则

  @Test
  void categoryRuleConfiguresRequiredAndAllowedOptions() {
    String code = uniqueCode("CATRULE");
    String category = uniqueCategory("服饰");
    UUID id = optionDict(code, "颜色", List.of("红色", "蓝色", "黑色"));
    write(
        actor,
        () ->
            dictionary.saveCategoryRule(
                actor,
                id,
                new CategoryAttributeRuleInput(
                    category, true, null, null, 0, List.of("红色", "蓝色"))));
    spu(category);

    var rules = tx(actor, () -> dictionary.categoryRules(category, null));
    assertThat(rules).hasSize(1);
    CategoryAttributeRule rule = rules.getFirst();
    assertThat(rule.category()).isEqualTo(category);
    assertThat(rule.required()).isTrue();
    assertThat(rule.attributeId()).isEqualTo(id);
    assertThat(rule.attributeCode()).isEqualTo(code);
    assertThat(rule.attributeName()).isEqualTo("颜色");
    assertThat(rule.dataType()).isEqualTo("OPTION");
    // 可选范围要真的返回给界面，否则界面上看不出这个类目只允许两个值。
    assertThat(rule.allowedOptions()).containsExactly("红色", "蓝色");
    // productCount 让运营看见这条规则覆盖了多少商品。
    assertThat(rule.productCount()).isPositive();
  }

  @Test
  void allowedOptionsMustExistInTheValueSource() {
    UUID id = optionDict(uniqueCode("BADOPT"), "颜色", List.of("红色"));
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        dictionary.saveCategoryRule(
                            actor,
                            id,
                            new CategoryAttributeRuleInput(
                                "服饰", false, null, null, 0, List.of("不存在的颜色")))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不在该属性的选项集合里");
  }

  @Test
  void numericRangeIsConfiguredForDecimalAttributes() {
    UUID id =
        write(
                actor,
                () ->
                    dictionary.save(
                        actor,
                        null,
                        input(uniqueCode("RANGE"), "重量", "DECIMAL", "LOGISTICS", List.of())))
            .id();
    String category = uniqueCategory("家电");
    write(
        actor,
        () ->
            dictionary.saveCategoryRule(
                actor,
                id,
                new CategoryAttributeRuleInput(
                    category, false, new BigDecimal("0.5"), new BigDecimal("20.0"), 2, List.of())));
    var rule = tx(actor, () -> dictionary.categoryRules(category, null)).getFirst();
    assertThat(rule.dataType()).isEqualTo("DECIMAL");
    // NUMERIC 必须原样往返：BigDecimal 往返被截断是最容易漏的一类静默错误。
    assertThat(rule.minValue()).isEqualByComparingTo("0.5");
    assertThat(rule.maxValue()).isEqualByComparingTo("20.0");
    assertThat(rule.position()).isEqualTo(2);

    // 上下限倒置必须被拒。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        dictionary.saveCategoryRule(
                            actor,
                            id,
                            new CategoryAttributeRuleInput(
                                category,
                                false,
                                new BigDecimal("30"),
                                new BigDecimal("1"),
                                0,
                                List.of()))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("下限不能大于上限");
    // 枚举属性不能用取值上下限表达范围（范围形态的校验先于可选值形态报错，
    // 因为「给枚举属性配上下限」本身就是更根本的错）。
    UUID optId = optionDict(uniqueCode("MIXED"), "颜色", List.of("红色"));
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        dictionary.saveCategoryRule(
                            actor,
                            optId,
                            new CategoryAttributeRuleInput(
                                uniqueCategory("服饰"), false, BigDecimal.ONE, null, 0, List.of()))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("只对数值属性");
    // 只给可选值子集、不碰上下限，就是合法写法。
    String optionCategory = uniqueCategory("服饰");
    write(
        actor,
        () ->
            dictionary.saveCategoryRule(
                actor,
                optId,
                new CategoryAttributeRuleInput(
                    optionCategory, false, null, null, 0, List.of("红色"))));
    assertThat(
            tx(actor, () -> dictionary.categoryRules(optionCategory, null))
                .getFirst()
                .allowedOptions())
        .containsExactly("红色");
  }

  @Test
  void categoryRuleIsUpsertedAndDeletable() {
    String category = uniqueCategory("鞋靴");
    UUID id = optionDict(uniqueCode("UPDCAT"), "尺码", List.of("XL", "L"));
    write(
        actor,
        () ->
            dictionary.saveCategoryRule(
                actor,
                id,
                new CategoryAttributeRuleInput(category, false, null, null, 0, List.of("XL"))));
    write(
        actor,
        () ->
            dictionary.saveCategoryRule(
                actor,
                id,
                new CategoryAttributeRuleInput(category, true, null, null, 4, List.of("XL", "L"))));
    var rules = tx(actor, () -> dictionary.categoryRules(category, null));
    // upsert：同一个 (类目, 属性) 只有一行，不会越改越多。
    assertThat(rules).hasSize(1);
    assertThat(rules.getFirst().required()).isTrue();
    assertThat(rules.getFirst().position()).isEqualTo(4);
    assertThat(rules.getFirst().allowedOptions()).containsExactly("L", "XL");

    write(actor, () -> dictionary.deleteCategoryRule(actor, id, category));
    assertThat(tx(actor, () -> dictionary.categoryRules(category, null))).isEmpty();
  }

  @Test
  void rulesCanBeFilteredByAttribute() {
    UUID first = optionDict(uniqueCode("FILTER1"), "颜色", List.of("红色"));
    UUID second = optionDict(uniqueCode("FILTER2"), "尺码", List.of("XL"));
    String category = uniqueCategory("服饰");
    write(
        actor,
        () ->
            dictionary.saveCategoryRule(
                actor,
                first,
                new CategoryAttributeRuleInput(category, true, null, null, 0, List.of())));
    write(
        actor,
        () ->
            dictionary.saveCategoryRule(
                actor,
                second,
                new CategoryAttributeRuleInput(category, false, null, null, 0, List.of())));
    assertThat(tx(actor, () -> dictionary.categoryRules(category, first))).hasSize(1);
    assertThat(tx(actor, () -> dictionary.categoryRules(category, second))).hasSize(1);
    assertThat(tx(actor, () -> dictionary.categoryRules(null, null)).size())
        .isGreaterThanOrEqualTo(2);
  }

  @Test
  void deletingAttributeCascadesItsCategoryRules() {
    UUID id = optionDict(uniqueCode("CASCADE"), "颜色", List.of("红色"));
    String category = uniqueCategory("服饰");
    write(
        actor,
        () ->
            dictionary.saveCategoryRule(
                actor,
                id,
                new CategoryAttributeRuleInput(category, true, null, null, 0, List.of())));
    write(actor, () -> dictionary.delete(actor, id, 0));
    // 规则跟着属性走：属性没了要求就没有意义，留着会变成指向空处的死规则。
    assertThat(tx(actor, () -> dictionary.categoryRules(category, null)))
        .noneMatch(r -> r.attributeId().equals(id));
  }

  // ------------------------------------------------------------ 概览

  @Test
  void overviewSeparatesDictionaryFromSpuLevelDefinitions() {
    String code = uniqueCode("OVW");
    UUID id = dict(code, "字典条目");
    UUID spuId = spu("服饰");
    write(
        actor,
        () ->
            catalog.saveAttribute(
                actor,
                spuId,
                null,
                new AttributeInput(
                    code,
                    "SPU 级属性",
                    "TEXT",
                    "COMMON_DEFAULT",
                    false,
                    false,
                    false,
                    "",
                    "基本参数",
                    List.of(),
                    List.of(),
                    0)));
    var overview = tx(actor, () -> dictionary.overview());
    // 同表两种作用域必须分开报，否则「定义了几条字典」会被商品属性污染。
    assertThat(overview.dictionaryAttributeCount()).isPositive();
    assertThat(overview.spuLevelDefinitionCount()).isPositive();
    assertThat(overview.enabledAttributeCount())
        .isLessThanOrEqualTo(overview.dictionaryAttributeCount());
    assertThat(overview.topUsedAttributes()).isNotEmpty();
    assertThat(overview.note()).contains("作用域");

    // 被类目要求过的属性要能出现在「被最多类目要求」的榜里。
    UUID optId = optionDict(uniqueCode("OVWOPT"), "颜色", List.of("红色"));
    write(
        actor,
        () ->
            dictionary.saveCategoryRule(
                actor,
                optId,
                new CategoryAttributeRuleInput(
                    uniqueCategory("服饰"), true, null, null, 0, List.of())));
    var after =
        tx(actor, () -> dictionary.overview()).topUsedAttributes().stream()
            .filter(u -> u.id().equals(optId))
            .findFirst()
            .orElseThrow();
    assertThat(after.categoryRuleCount()).isEqualTo(1);
    assertThat(id).isNotNull();
  }

  // ------------------------------------------------------------ 租户隔离三断言

  @Test
  void dictionaryEntriesAreTenantIsolatedAndHeaderForgeryIsIgnored() throws Exception {
    String myCode = uniqueCode("ISO");
    UUID myId = optionDict(myCode, "我租户的颜色", List.of("我租户的颜色值"));
    write(
        actor,
        () ->
            dictionary.saveCategoryRule(
                actor,
                myId,
                new CategoryAttributeRuleInput("我租户类目", true, null, null, 0, List.of())));
    UUID spuId = spu("我租户类目");

    // B 租户建自己的同编码字典条目（**同 code，不同租户**）：字典是租户级隔离的。
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
    var mine =
        tx(
            actor,
            () ->
                dictionary.list(
                    new AttributeDefinitionQuery(myCode, null, null, null, null, null, 0, 200)));
    assertThat(mine.items()).extracting(AttributeDefinitionRow::id).containsExactly(myId);
    var theirs =
        tx(
            actorB,
            () ->
                dictionary.list(
                    new AttributeDefinitionQuery(myCode, null, null, null, null, null, 0, 200)));
    assertThat(theirs.items()).extracting(AttributeDefinitionRow::id).containsExactly(theirId);

    // B 读不到 A 的选项
    assertThatThrownBy(() -> tx(actorB, () -> dictionary.options(myId)))
        .isInstanceOf(ResponseStatusException.class);
    // B 读不到 A 的类目规则
    assertThat(tx(actorB, () -> dictionary.categoryRules("我租户类目", null))).isEmpty();
    // B 往 A 的字典条目上加选项 → 404
    assertThatThrownBy(
            () ->
                write(
                    actorB,
                    () ->
                        dictionary.addOption(
                            actorB, myId, new AttributeOptionInput("越权写入", 9, true))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    // B 改 A 的字典条目 → 404
    assertThatThrownBy(
            () ->
                write(
                    actorB,
                    () ->
                        dictionary.save(
                            actorB,
                            myId,
                            input(myCode, "B 改的名字", "OPTION", "DESCRIPTION", List.of("x")))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    // B 删 A 的字典条目 → 404
    assertThatThrownBy(() -> write(actorB, () -> dictionary.delete(actorB, myId, 0)))
        .isInstanceOf(ResponseStatusException.class);
    // B 给 A 的字典条目配类目规则 → 404
    assertThatThrownBy(
            () ->
                write(
                    actorB,
                    () ->
                        dictionary.saveCategoryRule(
                            actorB,
                            myId,
                            new CategoryAttributeRuleInput(
                                "B 的类目", true, null, null, 0, List.of()))))
        .isInstanceOf(ResponseStatusException.class);
    // A 的数据仍在
    assertThat(tx(actor, () -> dictionary.options(myId))).hasSize(1);

    // 2) 伪造 tenant header 无效：租户只来自已认证的成员关系
    http.perform(
            get("/catalog/attributes/" + myId + "/options")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/attributes?q=" + myCode)
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString(myCode)));
    // 伪造头也不能借道写
    http.perform(
            post("/catalog/attributes/" + myId + "/options")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType("application/json")
                .content("{\"value\":\"伪造头写入\",\"position\":9,\"enabled\":true}"))
        .andExpect(status().isNotFound());
    assertThat(tx(actor, () -> dictionary.options(myId))).hasSize(1);

    // 3) 连接池归还后不串租户（单连接反复交替）
    for (int i = 0; i < 5; i++) {
      assertThat(tx(actor, () -> dictionary.options(myId)))
          .extracting(AttributeOptionRow::value)
          .containsExactly("我租户的颜色值");
      assertThat(tx(actorB, () -> dictionary.options(theirId)))
          .extracting(AttributeOptionRow::value)
          .containsExactly("B 的值");
      assertThatThrownBy(() -> tx(actorB, () -> dictionary.options(myId)))
          .isInstanceOf(ResponseStatusException.class);
      assertThat(tx(actor, () -> dictionary.categoryRules("我租户类目", null))).hasSize(1);
      assertThat(tx(actorB, () -> dictionary.categoryRules("我租户类目", null))).isEmpty();
    }
    assertThat(spuId).isNotNull();
  }

  @Test
  void writeEndpointsRequireAttributeUpdatePermission() throws Exception {
    String code = uniqueCode("RBAC");
    http.perform(
            post("/catalog/attributes")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(
                    "{\"code\":\""
                        + code
                        + "\",\"name\":\"只读\",\"dataType\":\"TEXT\",\"valueMode\":\"COMMON_DEFAULT\","
                        + "\"usage\":\"DESCRIPTION\",\"multiple\":false,\"required\":false,"
                        + "\"filterable\":false,\"variantAxis\":false,\"unit\":\"\",\"groupName\":\"基本参数\","
                        + "\"position\":0,\"enabled\":true,\"options\":[],\"version\":0}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void httpEndpointsExposeTheRealWorkflow() throws Exception {
    String code = uniqueCode("HTTP");
    http.perform(get("/catalog/attributes").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/attributes/overview").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());

    var created =
        http.perform(
                post("/catalog/attributes")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType("application/json")
                    .content(
                        "{\"code\":\""
                            + code
                            + "\",\"name\":\"HTTP 颜色\",\"dataType\":\"OPTION\",\"valueMode\":\"COMMON_DEFAULT\","
                            + "\"usage\":\"DESCRIPTION\",\"multiple\":false,\"required\":false,"
                            + "\"filterable\":true,\"variantAxis\":false,\"unit\":\"\",\"groupName\":\"基本参数\","
                            + "\"position\":0,\"enabled\":true,\"options\":[\"红色\"],\"version\":0}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = between(created, "\"id\":\"", "\"");
    http.perform(
            get("/catalog/attributes/" + id + "/options")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("红色")));
    http.perform(
            post("/catalog/attributes/" + id + "/category-rules")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"category\":\"服饰\",\"required\":true,\"position\":0,\"allowedOptions\":[\"红色\"]}"))
        .andExpect(status().isOk());
    http.perform(
            get("/catalog/attributes/category-rules?attributeId=" + id)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("服饰")));
    // 未认证 → 不能是 200（身份服务拒绝或不可达都算）。
    int anonymous = http.perform(get("/catalog/attributes")).andReturn().getResponse().getStatus();
    assertThat(anonymous).isNotEqualTo(200);
  }

  // ------------------------------------------------------------ 表结构断言

  @Test
  void dictionaryTablesAreRlsProtectedAndTenantScoped() {
    var weak =
        sql.fetch(
            "select relname from pg_class join pg_namespace n on n.oid=relnamespace"
                + " where n.nspname='public' and relkind='r'"
                + " and relname in ('attribute_definition','attribute_option',"
                + "'category_attribute_rule','category_attribute_option')"
                + " and (not relrowsecurity or not relforcerowsecurity)");
    assertThat(weak).isEmpty();
    for (String table :
        List.of(
            "attribute_definition",
            "attribute_option",
            "category_attribute_rule",
            "category_attribute_option")) {
      var nullable =
          sql.fetchOne(
              "select is_nullable from information_schema.columns"
                  + " where table_name=? and column_name='tenant_id'",
              table);
      assertThat(nullable.get("is_nullable", String.class)).as(table).isEqualTo("NO");
    }
    // 字典是租户级的：spu_id 必须可空，否则退化成「每个 SPU 一套字典」就违反蓝图。
    var nullableSpu =
        sql.fetchOne(
            "select is_nullable from information_schema.columns"
                + " where table_name='attribute_definition' and column_name='spu_id'");
    assertThat(nullableSpu.get("is_nullable", String.class)).isEqualTo("YES");
    // 编码唯一性必须覆盖字典作用域（NULLS NOT DISTINCT），否则同租户能建两条同码字典。
    var nullsNotDistinct =
        sql.fetchOne(
            "select count(*) from pg_index i"
                + " join pg_class c on c.oid=i.indexrelid"
                + " where c.relname='attribute_definition_scope_code_key' and i.indnullsnotdistinct");
    assertThat(nullsNotDistinct.get(0, Long.class)).isEqualTo(1);
    // 蓝图点名的两个字段必须在。
    for (String column : List.of("usage", "filterable")) {
      var exists =
          sql.fetchOne(
              "select 1 from information_schema.columns"
                  + " where table_name='attribute_definition' and column_name=?",
              column);
      assertThat(exists).as(column).isNotNull();
    }
  }

  /**
   * V11 改了 {@code attribute_definition} 与 {@code attribute_option}，这里是给已交付的 3.2.2.1（SPU
   * 公共属性）立的回归桩：字典提升之后，SPU 级定义与属性赋值必须照常工作。
   */
  @Test
  void deliveredSpuLevelAttributesStillWork() {
    // saveAttribute 走的是 **SPU 的** version 乐观锁（CatalogService.lock(spu, input.version())），
    // 每写一次属性就把 SPU 版本 +1。所以两条属性要分属两个 SPU，否则第二次一定撞 409。
    UUID textSpu = spu("服饰");
    String code = uniqueCode("REGRESS");
    write(
        actor,
        () ->
            catalog.saveAttribute(
                actor,
                textSpu,
                null,
                new AttributeInput(
                    code,
                    "回归用属性",
                    "TEXT",
                    "COMMON_DEFAULT",
                    false,
                    true,
                    false,
                    "",
                    "基本参数",
                    List.of(),
                    List.of("回归值"),
                    0)));
    var attributes = tx(actor, () -> catalog.attributes(textSpu));
    assertThat(attributes).extracting(Attribute::code).contains(code);
    assertThat(
            attributes.stream()
                .filter(x -> x.code().equals(code))
                .findFirst()
                .orElseThrow()
                .defaultValues())
        .containsExactly("回归值");

    // SPU 级属性带公共值时，attribute_value / attribute_value_item 这条链也照常工作。
    var valueRows =
        tx(
            actor,
            () ->
                sql.fetch(
                    "select v.state,i.text_value from attribute_value v"
                        + " join attribute_value_item i on i.tenant_id=v.tenant_id and i.value_id=v.id"
                        + " join attribute_definition d on d.tenant_id=v.tenant_id and d.id=v.attribute_id"
                        + " where d.spu_id=? and d.code=?",
                    textSpu,
                    code));
    assertThat(valueRows).hasSize(1);
    assertThat(valueRows.getFirst().get("state", String.class)).isEqualTo("SET");
    assertThat(valueRows.getFirst().get("text_value", String.class)).isEqualTo("回归值");

    // 新增列之后，SPU 级属性带选项的路径也要能走通（V11 给 attribute_option 加了列）。
    UUID optionSpu = spu("服饰");
    String optCode = uniqueCode("REGRESSOPT");
    write(
        actor,
        () ->
            catalog.saveAttribute(
                actor,
                optionSpu,
                null,
                new AttributeInput(
                    optCode,
                    "回归用枚举属性",
                    "OPTION",
                    "SKU_ONLY",
                    false,
                    true,
                    false,
                    "",
                    "基本参数",
                    List.of("红", "蓝"),
                    List.of(),
                    0)));
    assertThat(
            tx(
                actor,
                () ->
                    sql.fetchOne("select count(*) from attribute_option where value in ('红','蓝')")
                        .get(0, Long.class)))
        .isEqualTo(2L);
  }
}
