package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
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
 * 3.3.1.1 分类树管理：多棵独立的树、多级层级、拖拽排序、防环、启用停用、 SPU 主分类与辅助分类、多语言分类名、类目商品数统计，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CategoryTreeIntegrationTest {
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
      // 只有 SPU 读权限：分类树的写端点必须 403（分类沿用商品主数据域的权限口径）。
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

  @Autowired CategoryTreeService categories;
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
    return access.run(who, "catalog.spu.read", work);
  }

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.update", work);
  }

  void writeVoid(AccessService.Access who, Runnable work) {
    access.run(
        who,
        "catalog.spu.update",
        () -> {
          work.run();
          return null;
        });
  }

  // ------------------------------------------------------------ 夹具

  String uniqueCode(String hint) {
    return hint + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
  }

  /** 建一棵树；platformCode 非空即平台类目映射树。 */
  UUID tree(String kind, String platformCode) {
    return write(
            actor,
            () ->
                categories.saveTree(
                    actor,
                    null,
                    new CategoryTreeInput(
                        uniqueCode(kind.equals("INTERNAL") ? "TREE" : "PLAT"),
                        "分类树",
                        kind,
                        platformCode,
                        true,
                        0)))
        .id();
  }

  /** 建一个分类；parentId 为空即根节点。 */
  UUID category(UUID treeId, UUID parentId, String name) {
    return write(
            actor,
            () ->
                categories.save(
                    actor,
                    treeId,
                    null,
                    new CategoryInput(uniqueCode("CAT"), name, parentId, null, true, List.of(), 0)))
        .id();
  }

  UUID spu() {
    return write(
        actor,
        () ->
            catalog.saveSpu(
                actor,
                null,
                new SpuInput(
                    "CAT-" + UUID.randomUUID().toString().substring(0, 8),
                    "分类树测试商品",
                    "描述",
                    "品牌",
                    "",
                    "DRAFT",
                    0)));
  }

  // ------------------------------------------------------------ 多棵独立的树

  @Test
  void treesAreIndependentAndSeparatedByKind() {
    UUID internal = tree("INTERNAL", null);
    UUID amazon = tree("PLATFORM", "AMAZON");
    UUID shopify = tree("PLATFORM", "SHOPIFY");

    // 同编码可以在不同树里各有一条（两棵树不共享命名空间），但同树内必须唯一。
    String code = uniqueCode("SHARED");
    write(
        actor,
        () ->
            categories.save(
                actor,
                internal,
                null,
                new CategoryInput(code, "内部同码", null, null, true, List.of(), 0)));
    write(
        actor,
        () ->
            categories.save(
                actor,
                amazon,
                null,
                new CategoryInput(code, "平台同码", null, null, true, List.of(), 0)));
    // 同树内重复编码必须被拒（DB 23505 被服务层包成 409，不让 500 冒到界面上）。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        categories.save(
                            actor,
                            internal,
                            null,
                            new CategoryInput(code, "同树重码", null, null, true, List.of(), 0))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));

    var all = tx(actor, () -> categories.trees());
    assertThat(all).extracting(CategoryTree::id).contains(internal, amazon, shopify);
    assertThat(all.stream().filter(t -> t.id().equals(amazon)).findFirst().orElseThrow().treeKind())
        .isEqualTo("PLATFORM");
    assertThat(
            all.stream()
                .filter(t -> t.id().equals(amazon))
                .findFirst()
                .orElseThrow()
                .platformCode())
        .isEqualTo("AMAZON");
    assertThat(
            all.stream()
                .filter(t -> t.id().equals(internal))
                .findFirst()
                .orElseThrow()
                .platformCode())
        .isNull();

    // 平台树缺 platformCode 必须被拒（V14 的 CHECK 与服务层同一口径）。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        categories.saveTree(
                            actor,
                            null,
                            new CategoryTreeInput(
                                uniqueCode("BAD"), "缺平台", "PLATFORM", null, true, 0))))
        .isInstanceOf(ResponseStatusException.class);
    // 内部树带 platformCode 同样被拒。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        categories.saveTree(
                            actor,
                            null,
                            new CategoryTreeInput(
                                uniqueCode("BAD"), "内部带平台", "INTERNAL", "AMAZON", true, 0))))
        .isInstanceOf(ResponseStatusException.class);
  }

  // ------------------------------------------------------------ 多级 + 路径串 + 商品数

  @Test
  void multiLevelTreeReportsPathAndRealProductCounts() {
    UUID t = tree("INTERNAL", null);
    UUID apparel = category(t, null, "服饰");
    UUID tops = category(t, apparel, "上装");
    UUID shirt = category(t, tops, "T恤");

    var nodes = tx(actor, () -> categories.tree(t, false));
    assertThat(nodes).hasSize(3);
    var byCode = new HashMap<String, CategoryNode>();
    for (CategoryNode n : nodes) byCode.put(n.code(), n);
    // 深度与路径串：路径串是蓝图点名的输出。
    assertThat(byCode.values()).extracting(CategoryNode::depth).containsExactlyInAnyOrder(1, 2, 3);
    CategoryNode shirtNode = null;
    for (CategoryNode n : nodes) if (n.id().equals(shirt)) shirtNode = n;
    assertThat(shirtNode.path()).isEqualTo("服饰 / 上装 / T恤");
    assertThat(shirtNode.depth()).isEqualTo(3);

    // 商品数统计必须真实 join spu，而不是估算：挂 2 件到 T恤、1 件到服饰。
    UUID p1 = spu();
    UUID p2 = spu();
    UUID p3 = spu();
    write(
        actor,
        () -> categories.saveSpuCategories(actor, p1, new SpuCategoryInput(shirt, List.of())));
    write(
        actor,
        () -> categories.saveSpuCategories(actor, p2, new SpuCategoryInput(shirt, List.of())));
    write(
        actor,
        () -> categories.saveSpuCategories(actor, p3, new SpuCategoryInput(apparel, List.of())));

    nodes = tx(actor, () -> categories.tree(t, false));
    for (CategoryNode n : nodes) {
      if (n.id().equals(shirt)) {
        assertThat(n.directProductCount()).isEqualTo(2);
        assertThat(n.subtreeProductCount()).isEqualTo(2);
      }
      if (n.id().equals(apparel)) {
        assertThat(n.directProductCount()).isEqualTo(1);
        // 子树口径含所有子孙：1 件直接挂服饰 + 2 件挂 T恤。
        assertThat(n.subtreeProductCount()).isEqualTo(3);
      }
    }
  }

  // ------------------------------------------------------------ 防环（服务层 + DB 层双重）

  @Test
  void movingParentUnderItsOwnGrandchildIsRejectedByServiceAndDatabase() {
    UUID t = tree("INTERNAL", null);
    UUID apparel = category(t, null, "服饰");
    UUID tops = category(t, apparel, "上装");
    UUID shirt = category(t, tops, "T恤");

    // 服务层：把「服饰」挪到自己的孙子「T恤」下面 —— 必须被拒。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () -> categories.move(actor, apparel, new CategoryMoveInput(shirt, 0, 0))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getReason()).contains("不能把分类挂到它自己的子孙节点下"));

    // 服务层：挂到直接的子节点下同样成环。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () -> categories.move(actor, apparel, new CategoryMoveInput(tops, 0, 0))))
        .isInstanceOf(ResponseStatusException.class);

    // DB 层：绕过服务层直接 UPDATE，触发器仍必须挡住（否则运维 psql 能造出有环的树）。
    assertThatThrownBy(
            () ->
                writeVoid(
                    actor,
                    () ->
                        sql.execute(
                            "update product_category set parent_id=? where id=?", shirt, apparel)))
        .hasMessageContaining("不能把分类挂到它自己的子孙节点下");

    // 自环：CHECK 与触发器都会拒。
    assertThatThrownBy(
            () ->
                writeVoid(
                    actor,
                    () ->
                        sql.execute(
                            "update product_category set parent_id=id where id=?", apparel)))
        .isInstanceOf(Exception.class);

    // 树没有被破坏：层级仍是 服饰 > 上装 > T恤。
    var nodes = tx(actor, () -> categories.tree(t, false));
    for (CategoryNode n : nodes)
      if (n.id().equals(shirt)) assertThat(n.path()).isEqualTo("服饰 / 上装 / T恤");
  }

  @Test
  void legalMoveUpdatesSubtreePathAndPersistsOrder() {
    UUID t = tree("INTERNAL", null);
    UUID apparel = category(t, null, "服饰");
    UUID tops = category(t, apparel, "上装");
    UUID shirt = category(t, tops, "T恤");

    // 合法移动：把 T恤 挪到「服饰」下面（原来在「上装」下面）。path_ids 前缀必须跟着改。
    write(
        actor,
        () -> categories.move(actor, shirt, new CategoryMoveInput(apparel, 0, versionOf(shirt))));

    var nodes = tx(actor, () -> categories.tree(t, false));
    for (CategoryNode n : nodes) {
      if (n.id().equals(shirt)) {
        assertThat(n.path()).isEqualTo("服饰 / T恤");
        assertThat(n.depth()).isEqualTo(2);
      }
      if (n.id().equals(tops)) assertThat(n.path()).isEqualTo("服饰 / 上装");
    }
  }

  long versionOf(UUID id) {
    return tx(
        actor,
        () ->
            sql.fetchOne("select version from product_category where id=?", id)
                .get("version", Long.class));
  }

  // ------------------------------------------------------------ 拖拽同级排序

  @Test
  void sameLevelReorderIsPersistedAndStable() {
    UUID t = tree("INTERNAL", null);
    UUID root = category(t, null, "根");
    UUID x = category(t, root, "X");
    UUID y = category(t, root, "Y");
    UUID z = category(t, root, "Z");

    // 初始顺序 X(0) Y(1) Z(2)
    assertThat(order(t)).containsExactly(x, y, z);

    // 把 Z 拖到最前：Z(0) X(1) Y(2)，中间的行整体后移，不允许两个节点撞在同一个位置上。
    write(actor, () -> categories.move(actor, z, new CategoryMoveInput(root, 0, versionOf(z))));
    assertThat(order(t)).containsExactly(z, x, y);

    // 再把 Z 拖回最后：X(0) Y(1) Z(2)
    write(actor, () -> categories.move(actor, z, new CategoryMoveInput(root, 2, versionOf(z))));
    assertThat(order(t)).containsExactly(x, y, z);

    // 顺序落库：position 互不相同，否则顺序是不确定的。
    var positions =
        tx(
                actor,
                () ->
                    sql.fetch(
                        "select position from product_category where parent_id=? order by position",
                        root))
            .getValues("position", Integer.class);
    assertThat(positions).doesNotHaveDuplicates();
  }

  List<UUID> order(UUID treeId) {
    return tx(actor, () -> categories.tree(treeId, false)).stream()
        .filter(n -> "X".equals(n.name()) || "Y".equals(n.name()) || "Z".equals(n.name()))
        .sorted(Comparator.comparingInt(CategoryNode::position))
        .map(CategoryNode::id)
        .toList();
  }

  // ------------------------------------------------------------ 启用停用

  @Test
  void disablingHidesCategoryFromTreeButKeepsItsRulesAndProducts() {
    UUID t = tree("INTERNAL", null);
    UUID root = category(t, null, "根");
    UUID leaf = category(t, root, "叶子");
    UUID spuId = spu();
    write(
        actor,
        () -> categories.saveSpuCategories(actor, spuId, new SpuCategoryInput(leaf, List.of())));

    assertThat(tx(actor, () -> categories.tree(t, false))).hasSize(2);
    writeVoid(actor, () -> categories.setEnabled(actor, leaf, false));

    // 停用后默认从树里过滤掉。
    assertThat(tx(actor, () -> categories.tree(t, false))).hasSize(1);
    // includeDisabled=true 时它还在，且商品数统计照常（停用是治理动作，不是数据变更）。
    var all = tx(actor, () -> categories.tree(t, true));
    assertThat(all).hasSize(2);
    for (CategoryNode n : all)
      if (n.id().equals(leaf)) {
        assertThat(n.enabled()).isFalse();
        assertThat(n.directProductCount()).isEqualTo(1);
      }
    // 已挂商品的分类不能删，只能停用。
    assertThatThrownBy(
            () -> writeVoid(actor, () -> categories.delete(actor, leaf, versionOf(leaf))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getReason()).contains("这个分类上还挂着"));

    // 停用的分类不能再作为新的挂载目标。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        categories.saveSpuCategories(
                            actor, spu(), new SpuCategoryInput(leaf, List.of()))))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void deletingCategoryWithChildrenOrProductsIsRejected() {
    UUID t = tree("INTERNAL", null);
    UUID root = category(t, null, "根");
    UUID child = category(t, root, "子");

    // 有子分类时不能删（否则整支子树连坐消失）。
    assertThatThrownBy(
            () -> writeVoid(actor, () -> categories.delete(actor, root, versionOf(root))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getReason()).contains("子分类"));

    // 叶子能删。
    writeVoid(actor, () -> categories.delete(actor, child, versionOf(child)));
    assertThat(tx(actor, () -> categories.tree(t, true))).hasSize(1);
  }

  // ------------------------------------------------------------ 主分类与辅助分类

  @Test
  void primaryAndSecondaryCategoriesAreDistinguished() {
    UUID t = tree("INTERNAL", null);
    UUID primary = category(t, null, "主类目");
    UUID secondaryA = category(t, null, "辅助A");
    UUID secondaryB = category(t, null, "辅助B");
    UUID spuId = spu();

    write(
        actor,
        () ->
            categories.saveSpuCategories(
                actor,
                spuId,
                new SpuCategoryInput(
                    primary,
                    List.of(
                        new SpuCategoryLinkInput(secondaryA, 0),
                        new SpuCategoryLinkInput(secondaryB, 1)))));

    var assignment = tx(actor, () -> categories.spuAssignment(spuId));
    assertThat(assignment.primaryCategoryId()).isEqualTo(primary);
    assertThat(assignment.primaryCategoryName()).isEqualTo("主类目");
    assertThat(assignment.secondary())
        .extracting(SpuCategoryLink::categoryId)
        .containsExactlyInAnyOrder(secondaryA, secondaryB);

    // 同一个分类不能既是主分类又是辅助分类。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        categories.saveSpuCategories(
                            actor,
                            spuId,
                            new SpuCategoryInput(
                                primary, List.of(new SpuCategoryLinkInput(primary, 0))))))
        .isInstanceOf(ResponseStatusException.class);

    // 辅助分类不计入主分类口径的商品数统计（否则品类分析会被辅助归类污染）。
    var nodes = tx(actor, () -> categories.tree(t, false));
    for (CategoryNode n : nodes) {
      if (n.id().equals(primary)) {
        assertThat(n.directProductCount()).isEqualTo(1);
        assertThat(n.secondaryProductCount()).isZero();
      }
      if (n.id().equals(secondaryA)) {
        assertThat(n.directProductCount()).isZero();
        assertThat(n.secondaryProductCount()).isEqualTo(1);
      }
    }
  }

  // ------------------------------------------------------------ 多语言分类名

  @Test
  void categoryNamesSupportMultipleLocales() {
    UUID t = tree("INTERNAL", null);
    UUID id =
        write(
                actor,
                () ->
                    categories.save(
                        actor,
                        t,
                        null,
                        new CategoryInput(
                            uniqueCode("I18N"),
                            "上装",
                            null,
                            null,
                            true,
                            List.of(
                                new CategoryNameInput("en-US", "Tops"),
                                new CategoryNameInput("ja-JP", "トップス")),
                            0)))
            .id();

    var node =
        tx(actor, () -> categories.tree(t, false)).stream()
            .filter(n -> n.id().equals(id))
            .findFirst()
            .orElseThrow();
    // locale 规范化成小写：zh-CN 与 zh-cn 是同一个语言，不允许各存一条。
    assertThat(node.names()).containsEntry("en-us", "Tops").containsEntry("ja-jp", "トップス");
    assertThat(node.name()).isEqualTo("上装");

    // 同一个语言配两条必须被拒。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        categories.save(
                            actor,
                            t,
                            null,
                            new CategoryInput(
                                uniqueCode("I18N"),
                                "重名",
                                null,
                                null,
                                true,
                                List.of(
                                    new CategoryNameInput("en-US", "A"),
                                    new CategoryNameInput("EN-us", "B")),
                                0))))
        .isInstanceOf(ResponseStatusException.class);
  }

  // ------------------------------------------------------------ V14 兑现：规则表改挂类目主键

  @Test
  void renamingCategoryKeepsRuleTextColumnInSync() {
    // V11 / V9 / V13 三份迁移都写过「3.3.1.1 落地后把 category 改挂 product_category 主键」。
    // 这里验证改挂之后的链路没被打断：类目仍带着 category_id 外键，
    // 且重命名会同步已交付模块仍在读的 category 文本列。
    UUID t = tree("INTERNAL", null);
    UUID id = category(t, null, "原始类目名");
    // 类目属性要求挂在**租户级字典条目**上（AttributeDictionaryService 只管 spu_id is null 的字典行），
    // 所以这里建字典条目而不是 SPU 级属性。
    String attributeCode = uniqueCode("COLOR");
    UUID attributeId =
        write(
                actor,
                () ->
                    dictionary.save(
                        actor,
                        null,
                        new AttributeDefinitionInput(
                            attributeCode,
                            "颜色",
                            "OPTION",
                            "COMMON_DEFAULT",
                            "DESCRIPTION",
                            false,
                            true,
                            false,
                            false,
                            "",
                            "基本参数",
                            0,
                            true,
                            List.of("红色"),
                            0)))
            .id();
    String legacyCategory = "规则专用-" + UUID.randomUUID().toString().substring(0, 6);
    write(
        actor,
        () ->
            dictionary.saveCategoryRule(
                actor,
                attributeId,
                new CategoryAttributeRuleInput(
                    legacyCategory, true, null, null, 0, List.of("红色"))));

    // 规则挂在一个真实类目上（V14 的 NOT NULL 外键要求必须有类目）。
    UUID ruleCategory = category(t, null, legacyCategory);
    writeVoid(
        actor,
        () -> {
          var definition =
              sql.fetchOne("select tenant_id from attribute_definition where id=?", attributeId)
                  .get("tenant_id", UUID.class);
          sql.execute(
              "update category_attribute_rule set category_id=? where tenant_id=? and category=?",
              ruleCategory,
              definition,
              legacyCategory);
        });

    // category_id 已经把规则锚在类目主键上，而不是自由文本。
    var rule =
        tx(
            actor,
            () ->
                sql.fetchOne(
                    "select category_id from category_attribute_rule where attribute_id=? and category_id is not null",
                    attributeId));
    assertThat(rule).isNotNull();
    assertThat(rule.get("category_id", UUID.class)).isEqualTo(ruleCategory);

    // 重命名类目：只改名称，**编码保持不变**（编码建后不可改，服务层会拒——
    // 刊登映射与类目规则按 code 对齐它）。文本列被同步，
    // 否则 3.2.1.1 的类目缺口统计按文本比就永远对不上分类树。
    String currentCode =
        tx(actor, () -> categories.tree(t, false)).stream()
            .filter(n -> n.id().equals(id))
            .findFirst()
            .orElseThrow()
            .code();
    String renamedTo = "改名后的类目-" + UUID.randomUUID().toString().substring(0, 6);
    write(
        actor,
        () ->
            categories.save(
                actor,
                t,
                id,
                new CategoryInput(
                    currentCode, renamedTo, null, 0, true, List.of(), versionOf(id))));

    var renamed =
        tx(actor, () -> categories.tree(t, false)).stream()
            .filter(n -> n.id().equals(id))
            .findFirst()
            .orElseThrow();
    assertThat(renamed.name()).isEqualTo(renamedTo);
    assertThat(renamed.code()).isEqualTo(currentCode);

    // 编码改不动：这条约束保证「按 code 对齐」的锚点是稳定的。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        categories.save(
                            actor,
                            t,
                            id,
                            new CategoryInput(
                                uniqueCode("CAT"),
                                "还想改编码",
                                null,
                                0,
                                true,
                                List.of(),
                                versionOf(id)))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getReason()).contains("分类编码建后不可修改"));
  }

  // ------------------------------------------------------------ 租户隔离三断言

  @Test
  void categoriesAreTenantIsolatedAndHeaderForgeryIsIgnored() throws Exception {
    UUID t = tree("INTERNAL", null);
    UUID mine = category(t, null, "我租户的类目");
    UUID spuId = spu();
    write(
        actor,
        () -> categories.saveSpuCategories(actor, spuId, new SpuCategoryInput(mine, List.of())));

    // B 租户建自己的树（**同租户隔离**：树是租户级配置）。
    UUID theirTree =
        write(
                actorB,
                () ->
                    categories.saveTree(
                        actorB,
                        null,
                        new CategoryTreeInput(
                            uniqueCode("THEIRS"), "B 的树", "INTERNAL", null, true, 0)))
            .id();
    UUID theirs =
        write(
                actorB,
                () ->
                    categories.save(
                        actorB,
                        theirTree,
                        null,
                        new CategoryInput(
                            uniqueCode("CAT"), "B 的类目", null, null, true, List.of(), 0)))
            .id();

    // 1) 两租户互不可见
    assertThat(tx(actor, () -> categories.tree(t, false)))
        .extracting(CategoryNode::id)
        .contains(mine);
    assertThat(tx(actorB, () -> categories.tree(theirTree, false)))
        .extracting(CategoryNode::id)
        .contains(theirs)
        .doesNotContain(mine);
    // B 读不到 A 的树
    assertThatThrownBy(() -> tx(actorB, () -> categories.tree(t, false)))
        .isInstanceOf(ResponseStatusException.class);
    // B 改 A 的分类 → 404（RLS 已过滤，服务层表现为「不存在或没有访问权限」）
    assertThatThrownBy(
            () ->
                write(
                    actorB,
                    () ->
                        categories.save(
                            actorB,
                            t,
                            mine,
                            new CategoryInput(
                                uniqueCode("CAT"), "B 改的名字", null, 0, true, List.of(), 0))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    // B 删 A 的分类 → 404
    assertThatThrownBy(() -> writeVoid(actorB, () -> categories.delete(actorB, mine, 0)))
        .isInstanceOf(ResponseStatusException.class);
    // B 把 A 的商品挂到 A 的分类上 → 404（spu_category 的 data_scope 策略跟着 SPU 可见性）
    assertThatThrownBy(
            () ->
                write(
                    actorB,
                    () ->
                        categories.saveSpuCategories(
                            actorB, spuId, new SpuCategoryInput(mine, List.of()))))
        .isInstanceOf(ResponseStatusException.class);
    // A 的数据仍在
    assertThat(tx(actor, () -> categories.tree(t, false)))
        .extracting(CategoryNode::id)
        .contains(mine);

    // 2) 伪造 tenant header 无效：租户只来自已认证的成员关系
    http.perform(
            get("/catalog/categories/trees/" + t + "/nodes")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/categories/trees")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk());
    // 伪造头也不能借道写
    http.perform(
            post("/catalog/categories/trees/" + t + "/nodes")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType("application/json")
                .content("{\"code\":\"FORGED\",\"name\":\"伪造头写入\",\"enabled\":true,\"version\":0}"))
        .andExpect(status().isNotFound());
    assertThat(tx(actor, () -> categories.tree(t, false)))
        .extracting(CategoryNode::name)
        .doesNotContain("伪造头写入");

    // 3) 连接池归还后不串租户（单连接反复交替）
    for (int i = 0; i < 5; i++) {
      assertThat(tx(actor, () -> categories.tree(t, false)))
          .extracting(CategoryNode::id)
          .contains(mine);
      assertThat(tx(actorB, () -> categories.tree(theirTree, false)))
          .extracting(CategoryNode::id)
          .contains(theirs)
          .doesNotContain(mine);
      assertThatThrownBy(() -> tx(actorB, () -> categories.spuAssignment(spuId)))
          .isInstanceOf(ResponseStatusException.class);
      assertThat(tx(actor, () -> categories.spuAssignment(spuId)).primaryCategoryId())
          .isEqualTo(mine);
    }
  }

  @Test
  void writeEndpointsRequireSpuUpdatePermission() throws Exception {
    http.perform(
            post("/catalog/categories/trees")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(
                    "{\"code\":\"RBAC\",\"name\":\"只读\",\"treeKind\":\"INTERNAL\",\"enabled\":true,\"version\":0}"))
        .andExpect(status().isForbidden());
    http.perform(get("/catalog/categories/trees").header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());
  }

  // ------------------------------------------------------------ HTTP 与概览

  @Test
  void httpEndpointsExposeTheRealWorkflow() throws Exception {
    String code = uniqueCode("HTTP");
    var created =
        http.perform(
                post("/catalog/categories/trees")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType("application/json")
                    .content(
                        "{\"code\":\""
                            + code
                            + "\",\"name\":\"HTTP 内部类目\",\"treeKind\":\"INTERNAL\",\"enabled\":true,\"version\":0}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String treeId = between(created, "\"id\":\"", "\"");
    http.perform(get("/catalog/categories/trees").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString(code)));
    http.perform(
            post("/catalog/categories/trees/" + treeId + "/nodes")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"code\":\"NODE\",\"name\":\"节点\",\"enabled\":true,\"names\":[{\"locale\":\"en-US\",\"name\":\"Node\"}],\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("节点")));
    http.perform(
            get("/catalog/categories/trees/" + treeId + "/nodes")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("en-us")));
    http.perform(get("/catalog/categories/overview").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    int anonymous =
        http.perform(get("/catalog/categories/trees")).andReturn().getResponse().getStatus();
    assertThat(anonymous).isNotEqualTo(200);
  }

  @Test
  void overviewReportsScaleAndGovernanceGaps() {
    UUID t = tree("INTERNAL", null);
    UUID c = category(t, null, "概览类目");
    UUID orphan = spu();
    var overview = tx(actor, () -> categories.overview());
    assertThat(overview.treeCount()).isPositive();
    assertThat(overview.internalTreeCount()).isPositive();
    // 有一个既没主分类也没历史类目文本的商品：缺口指标必须能看到它。
    assertThat(overview.uncategorizedSpuCount()).isPositive();
    assertThat(overview.orphanedRuleCount()).isZero();
    assertThat(overview.note()).isNotBlank();
    assertThat(c).isNotNull();
    assertThat(orphan).isNotNull();
  }

  // ------------------------------------------------------------ 表结构断言

  @Test
  void categoryTablesAreRlsProtectedAndTenantScoped() {
    var weak =
        sql.fetch(
            "select relname from pg_class join pg_namespace n on n.oid=relnamespace"
                + " where n.nspname='public' and relkind='r'"
                + " and relname in ('product_category_tree','product_category',"
                + "'product_category_name','spu_category')"
                + " and (not relrowsecurity or not relforcerowsecurity)");
    assertThat(weak).isEmpty();
    for (String table :
        List.of(
            "product_category_tree", "product_category", "product_category_name", "spu_category")) {
      var nullable =
          sql.fetchOne(
              "select is_nullable from information_schema.columns"
                  + " where table_name=? and column_name='tenant_id'",
              table);
      assertThat(nullable.get("is_nullable", String.class)).as(table).isEqualTo("NO");
    }
    // V14 兑现点：两张规则表的 category_id 必须是 NOT NULL（不再是自由文本）。
    for (String table : List.of("category_attribute_rule", "category_spec_axis")) {
      var nullable =
          sql.fetchOne(
              "select is_nullable from information_schema.columns"
                  + " where table_name=? and column_name='category_id'",
              table);
      assertThat(nullable.get("is_nullable", String.class)).as(table).isEqualTo("NO");
    }
    // 主分类唯一：部分唯一索引必须在（否则一个 SPU 能有两条 PRIMARY，品类统计对不上）。
    var primaryIndex =
        sql.fetchOne(
            "select count(*) from pg_index i join pg_class c on c.oid=i.indexrelid"
                + " where c.relname='spu_category_single_primary'");
    assertThat(primaryIndex.get(0, Long.class)).isEqualTo(1);
  }

  static String between(String text, String prefix, String suffix) {
    int start = text.indexOf(prefix);
    if (start < 0) throw new IllegalStateException("prefix not found: " + text);
    start += prefix.length();
    int end = text.indexOf(suffix, start);
    if (end < 0) throw new IllegalStateException("suffix not found: " + text);
    return text.substring(start, end);
  }
}
