package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static com.starcon.catalog.PlatformCategoryMappingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 4.2.3.1 平台类目映射：映射 CRUD 与多对多语义、平台 + 站点两个维度、树种类守卫、
 * 默认目标的唯一性、平台类目必填属性规则（含「必填但未绑内部属性」这个正常状态）、
 * 给 4.2.1.1 的 resolve 解析、timestamptz 往返，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlatformCategoryMappingIntegrationTest {
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
      // 只有 SPU 读权限：写端点必须 403（沿用商品主数据域权限，不新造词表）。
      actors.put(
          "Bearer reader",
          actor(user, a, null, List.of(grant("ALL", List.of("catalog.spu.read")))));
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
  @Autowired AttributeDictionaryService dictionary;
  @Autowired PlatformCategoryMappingService mappings;
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
  /** 每个测试一个唯一标记。同一个容器跑全部测试，站点码不隔离就会互相污染筛选结果。 */
  String tag;

  @BeforeEach
  void setup() {
    actor = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
  }

  /** 本测试的主站点。仍是「AMAZON 的某个站点」，语义与字面量 US 一致，只是互不撞车。 */
  String siteA() {
    return "US" + tag;
  }

  /** 本测试的第二个站点，用来证明站点是两个独立维度而不是一个字符串。 */
  String siteB() {
    return "DE" + tag;
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

  UUID tree(AccessService.Access who, String kind, String platformCode) {
    return write(
            who,
            () ->
                categories.saveTree(
                    who,
                    null,
                    new CategoryTreeInput(
                        uniqueCode("INTERNAL".equals(kind) ? "TREE" : "PLAT"),
                        "分类树",
                        kind,
                        platformCode,
                        true,
                        0)))
        .id();
  }

  UUID category(AccessService.Access who, UUID treeId, UUID parentId, String name) {
    return write(
            who,
            () ->
                categories.save(
                    who,
                    treeId,
                    null,
                    new CategoryInput(uniqueCode("CAT"), name, parentId, null, true, List.of(), 0)))
        .id();
  }

  /** 建一个租户级字典属性（3.2.1.1 的字典行，spu_id IS NULL）。 */
  UUID attribute(AccessService.Access who, String code, String name, String dataType, String... options) {
    if ("OPTION".equals(dataType) && options.length == 0)
      throw new IllegalArgumentException("枚举属性必须至少给一个选项：" + code);
    return write(
            who,
            () ->
                dictionary.save(
                    who,
                    null,
                    new AttributeDefinitionInput(
                        code,
                        name,
                        dataType,
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
                        List.of(options),
                        0)))
        .id();
  }

  PlatformCategoryMappingInput mappingInput(
      UUID internal, String platform, String site, UUID platformCategory, boolean isDefault) {
    return new PlatformCategoryMappingInput(internal, platform, site, platformCategory, isDefault, true, 0);
  }

  PlatformAttributeMappingInput attributeInput(
      String code, String name, UUID internalAttributeId, String requirement, String dataType) {
    return new PlatformAttributeMappingInput(
        code,
        name,
        internalAttributeId,
        requirement,
        dataType,
        false,
        "ENUM".equals(dataType) ? List.of("A", "B") : List.of(),
        "DIRECT",
        true,
        0,
        0);
  }

  PlatformCategoryMappingRow save(PlatformCategoryMappingInput input) {
    return write(actor, () -> mappings.save(actor, null, input));
  }

  // ------------------------------------------------------------ 映射 CRUD 与两个维度

  @Test
  void mappingIsManyToManyAndCarriesPlatformAndSiteSeparately() {
    UUID internalTree = tree(actor, "INTERNAL", null);
    UUID shirt = category(actor, internalTree, null, "T恤");
    UUID pants = category(actor, internalTree, null, "裤子");
    UUID amazonTree = tree(actor, "PLATFORM", "AMAZON");
    UUID amazonShirt = category(actor, amazonTree, null, "Amazon 男士上装");
    UUID amazonPants = category(actor, amazonTree, null, "Amazon 男士下装");
    UUID shopifyTree = tree(actor, "PLATFORM", "SHOPIFY");
    UUID shopifyShirt = category(actor, shopifyTree, null, "Shopify 服饰-T恤");

    // 同一个内部类目，在不同平台/不同站点映射到不同平台类目 —— 本模块的核心语义。
    PlatformCategoryMappingRow us = save(mappingInput(shirt, "AMAZON", siteA(), amazonShirt, true));
    PlatformCategoryMappingRow de = save(mappingInput(shirt, "AMAZON", siteB(), amazonPants, true));
    PlatformCategoryMappingRow shopify = save(mappingInput(shirt, "SHOPIFY", siteA(), shopifyShirt, true));
    assertThat(us.id()).isNotEqualTo(de.id());
    assertThat(us.siteCode()).isEqualTo(siteA());
    assertThat(de.siteCode()).isEqualTo(siteB());

    // 多对多的另一侧：两个内部分类映射到同一个平台类目。
    PlatformCategoryMappingRow pantsToSame =
        save(mappingInput(pants, "AMAZON", siteA(), amazonShirt, false));
    var detailA = tx(actor, () -> mappings.detail(us.id()));
    assertThat(detailA.mapping().mappedInternalCount()).isEqualTo(2);
    assertThat(detailA.alsoMappedFrom())
        .extracting(MappedInternalCategory::internalCategoryId)
        .containsExactly(pants);

    // 台账按平台与站点分别筛选：三条 US + 一条 DE。
    assertThat(tx(actor, () -> mappings.page(null, "AMAZON", siteA(), null, null, false, null, 1, 20))
            .items())
        .hasSize(2);
    assertThat(tx(actor, () -> mappings.page(null, "AMAZON", siteB(), null, null, false, null, 1, 20))
            .items())
        .hasSize(1);
    assertThat(tx(actor, () -> mappings.page(null, "SHOPIFY", null, null, null, false, null, 1, 20))
            .items())
        .hasSize(1);
    // 按内部分类筛选：一个内部分类在三个平台站点组合上都有映射。
    assertThat(tx(actor, () -> mappings.page(shirt, null, null, null, null, false, null, 1, 20))
            .items())
        .hasSize(3);

    // 关键词同时搜内部类目与平台类目。
    assertThat(tx(actor, () -> mappings.page(null, null, null, "裤子", null, false, null, 1, 20))
            .items())
        .hasSize(1);
    assertThat(tx(actor, () -> mappings.page(null, null, null, "Shopify 服饰", null, false, null, 1, 20))
            .items())
        .hasSize(1);
    assertThat(shopify.platformCategoryName()).isEqualTo("Shopify 服饰-T恤");
  }

  @Test
  void duplicateTupleIsRejectedButSamePlatformDifferentSiteIsAllowed() {
    UUID internalTree = tree(actor, "INTERNAL", null);
    UUID shirt = category(actor, internalTree, null, "T恤");
    UUID amazonTree = tree(actor, "PLATFORM", "AMAZON");
    UUID amazonShirt = category(actor, amazonTree, null, "Amazon 男士上装");

    save(mappingInput(shirt, "AMAZON", siteA(), amazonShirt, false));
    assertThatThrownBy(() -> save(mappingInput(shirt, "amazon", siteA().toLowerCase(), amazonShirt, false)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已存在");
    // 同平台不同站点是合法的另一条映射。
    assertThat(save(mappingInput(shirt, "AMAZON", siteB(), amazonShirt, false)).siteCode())
        .isEqualTo(siteB());
  }

  // ------------------------------------------------------------ 树种类守卫

  @Test
  void bothSidesMustComeFromTheRightKindOfTree() {
    UUID internalTree = tree(actor, "INTERNAL", null);
    UUID shirt = category(actor, internalTree, null, "T恤");
    UUID amazonTree = tree(actor, "PLATFORM", "AMAZON");
    UUID amazonShirt = category(actor, amazonTree, null, "Amazon 男士上装");
    UUID shopifyTree = tree(actor, "PLATFORM", "SHOPIFY");
    UUID shopifyShirt = category(actor, shopifyTree, null, "Shopify T恤");

    // 左边塞平台类目：拒绝并说清是哪一侧错了。
    assertThatThrownBy(() -> save(mappingInput(amazonShirt, "AMAZON", siteA(), amazonShirt, false)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("内部管理分类树");
    // 右边塞内部类目。
    assertThatThrownBy(() -> save(mappingInput(shirt, "AMAZON", siteA(), shirt, false)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("平台类目树");
    // 平台归属与平台树声明不一致。
    assertThatThrownBy(() -> save(mappingInput(shirt, "AMAZON", siteA(), shopifyShirt, false)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("SHOPIFY");

    // DB 层触发器兜底：绕过服务层直接写，跨树/错平台的行同样写不进去。
    UUID other = category(actor, internalTree, null, "外套");
    assertThatThrownBy(
            () ->
                tx(
                    actor,
                    () ->
                        sql.execute(
                            "insert into platform_category_mapping(id,tenant_id,internal_category_id,platform_code,site_code,platform_category_id)"
                                + " values(?,?,?,?,?,?)",
                            UUID.randomUUID(),
                            a,
                            shirt,
                            "AMAZON",
                            siteA(),
                            shopifyShirt)))
        .hasMessageContaining("AMAZON");

    // 跨租户引用同类目也写不进去（复合外键 + RLS 双重）。
    assertThatThrownBy(
            () ->
                tx(
                    actorB,
                    () ->
                        sql.execute(
                            "insert into platform_category_mapping(id,tenant_id,internal_category_id,platform_code,site_code,platform_category_id)"
                                + " values(?,?,?,?,?,?)",
                            UUID.randomUUID(),
                            b,
                            shirt,
                            "AMAZON",
                            siteA(),
                            amazonShirt)))
        .isInstanceOf(Exception.class);
    assertThat(other).isNotNull();
  }

  @Test
  void platformAndSiteCodesAreNormalizedAndValidated() {
    UUID internalTree = tree(actor, "INTERNAL", null);
    UUID shirt = category(actor, internalTree, null, "T恤");
    UUID amazonTree = tree(actor, "PLATFORM", "AMAZON");
    UUID amazonShirt = category(actor, amazonTree, null, "Amazon 男士上装");

    // 小写平台码会归一到大写，并匹配 AMazon 树（树本身是平台码的权威来源）。
    PlatformCategoryMappingRow row = save(mappingInput(shirt, "amazon", " " + siteA().toLowerCase() + " ", amazonShirt, false));
    assertThat(row.platformCode()).isEqualTo("AMAZON");
    assertThat(row.siteCode()).isEqualTo(siteA());

    assertThatThrownBy(() -> save(mappingInput(shirt, "亚马逊", siteA(), amazonShirt, false)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("平台编码");
    assertThatThrownBy(() -> save(mappingInput(shirt, "AMAZON", "美国站", amazonShirt, false)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("站点编码");
  }

  // ------------------------------------------------------------ 默认目标

  @Test
  void onlyOneDefaultTargetPerInternalCategoryPlatformAndSite() {
    UUID internalTree = tree(actor, "INTERNAL", null);
    UUID shirt = category(actor, internalTree, null, "T恤");
    UUID amazonTree = tree(actor, "PLATFORM", "AMAZON");
    UUID amazonShirt = category(actor, amazonTree, null, "Amazon 男士上装");
    UUID amazonShirtAlt = category(actor, amazonTree, null, "Amazon 男士上装-活动");

    PlatformCategoryMappingRow first =
        save(mappingInput(shirt, "AMAZON", siteA(), amazonShirt, true));
    PlatformCategoryMappingRow second =
        save(mappingInput(shirt, "AMAZON", siteA(), amazonShirtAlt, true));
    assertThat(second.isDefault()).isTrue();
    // 设第二个为默认会把第一个降级（partial unique index 保证至多一个默认）。
    assertThat(tx(actor, () -> mappings.page(shirt, "AMAZON", siteA(), null, null, false, null, 1, 20))
            .items())
        .filteredOn(PlatformCategoryMappingRow::isDefault)
        .extracting(PlatformCategoryMappingRow::id)
        .containsExactly(second.id());
    assertThat(tx(actor, () -> mappings.page(shirt, "AMAZON", siteA(), null, null, false, null, 1, 20))
            .items())
        .extracting(PlatformCategoryMappingRow::id)
        .contains(first.id(), second.id());

    // 显式设默认走独立端点（语义：把已存在的某条切成默认）。
    writeVoid(actor, () -> mappings.setDefault(actor, first.id(), first.version()));
    assertThat(tx(actor, () -> mappings.page(shirt, "AMAZON", siteA(), null, null, false, null, 1, 20))
            .items())
        .filteredOn(PlatformCategoryMappingRow::isDefault)
        .extracting(PlatformCategoryMappingRow::id)
        .containsExactly(first.id());
  }

  // ------------------------------------------------------------ 属性映射规则

  @Test
  void requiredAttributesCanBeRecordedWithoutAnInternalBinding() {
    UUID internalTree = tree(actor, "INTERNAL", null);
    UUID shirt = category(actor, internalTree, null, "T恤");
    UUID amazonTree = tree(actor, "PLATFORM", "AMAZON");
    UUID amazonShirt = category(actor, amazonTree, null, "Amazon 男士上装");
    UUID material = attribute(actor, "MAT" + UUID.randomUUID().toString().substring(0, 4), "材质", "TEXT");
    UUID brand = attribute(actor, "BRD" + UUID.randomUUID().toString().substring(0, 4), "品牌", "TEXT");

    PlatformCategoryMappingRow mapping = save(mappingInput(shirt, "AMAZON", siteA(), amazonShirt, true));

    // 已绑定：平台的「材质」对应内部属性字典里的「材质」。
    PlatformAttributeMappingRow bound =
        write(
            actor,
            () ->
                mappings.saveAttributeRule(
                    actor, mapping.id(), null, attributeInput("material", "材质", material, "REQUIRED", "TEXT")));
    assertThat(bound.internalAttributeCode()).isEqualTo(materialCode(material));
    assertThat(bound.internalAttributeName()).isEqualTo("材质");

    // 未绑定：平台还要求一个我们字典里没有的属性。**这必须能记下来**，
    // 否则运营配不出这条规则，只能等刊登被平台驳回才知道自己缺一个属性。
    PlatformAttributeMappingRow unmapped =
        write(
            actor,
            () ->
                mappings.saveAttributeRule(
                    actor,
                    mapping.id(),
                    null,
                    attributeInput("care_instructions", "保养说明", null, "REQUIRED", "TEXT")));
    assertThat(unmapped.internalAttributeId()).isNull();
    assertThat(unmapped.internalAttributeCode()).isNull();

    // 枚举属性带候选值；同映射下同一平台属性码不能重复。
    write(
        actor,
        () ->
            mappings.saveAttributeRule(
                actor,
                mapping.id(),
                null,
                attributeInput("size", "尺码", null, "RECOMMENDED", "ENUM")));
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        mappings.saveAttributeRule(
                            actor, mapping.id(), null, attributeInput("material", "材质2", brand, "OPTIONAL", "TEXT"))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已存在");

    // 缺口统计：2 条必填，其中 1 条没绑内部属性。
    var row = tx(actor, () -> mappings.detail(mapping.id())).mapping();
    assertThat(row.attributeRuleCount()).isEqualTo(3);
    assertThat(row.requiredCount()).isEqualTo(2);
    assertThat(row.unmappedRequiredCount()).isEqualTo(1);

    var detail = tx(actor, () -> mappings.detail(mapping.id()));
    assertThat(detail.summary().unmappedRequiredCount()).isEqualTo(1);
    assertThat(detail.attributes()).extracting(PlatformAttributeMappingRow::platformAttributeCode)
        .containsExactly("care_instructions", "material", "size");

    // 「只看有缺口的」筛选。
    assertThat(tx(actor, () -> mappings.page(shirt, null, null, null, null, true, null, 1, 20)).items())
        .extracting(PlatformCategoryMappingRow::id)
        .containsExactly(mapping.id());
  }

  @Test
  void attributeRulesRejectBadEnumsDictionaryLeaksAndCrossMappingWrites() {
    UUID internalTree = tree(actor, "INTERNAL", null);
    UUID shirt = category(actor, internalTree, null, "T恤");
    UUID amazonTree = tree(actor, "PLATFORM", "AMAZON");
    UUID amazonShirt = category(actor, amazonTree, null, "Amazon 男士上装");
    PlatformCategoryMappingRow mapping = save(mappingInput(shirt, "AMAZON", siteA(), amazonShirt, true));
    UUID material = attribute(actor, "MAT" + UUID.randomUUID().toString().substring(0, 4), "材质", "TEXT");

    // ENUM 没有候选值：渲染不出下拉也校验不了值，只能原样发给平台等驳回。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        mappings.saveAttributeRule(
                            actor,
                            mapping.id(),
                            null,
                            new PlatformAttributeMappingInput(
                                "color", "颜色", null, "REQUIRED", "ENUM", false, List.of(), "DIRECT", true, 0, 0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("候选值");
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        mappings.saveAttributeRule(
                            actor, mapping.id(), null, attributeInput("color", "颜色", null, "WHATEVER", "TEXT"))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("要求强度");
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        mappings.saveAttributeRule(
                            actor,
                            mapping.id(),
                            null,
                            attributeInput("材质", "材质", material, "REQUIRED", "TEXT"))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("平台属性编码");

    // SPU 私有属性不能绑：那个 SPU 被删会让这条必填规则静默失效。
    // 属性定义在 V11 里 spu_id 可空，NOT NULL 的是 V1 的写法，所以这里直接插一条
    // spu_id 非空的行——那正是 3.2.2.2 管理的「商品私有属性」形态。
    UUID spu =
        write(
                actor,
                () ->
                    catalogService.saveSpu(
                        actor, null, new SpuInput(uniqueCode("SPU"), "私有属性宿主", "", "品牌", "", "DRAFT", 0)));
    UUID spuAttribute =
        tx(
            actor,
            () -> {
              UUID id = UUID.randomUUID();
              sql.execute(
                  "insert into attribute_definition(id,tenant_id,spu_id,code,name,data_type,value_mode,"
                      + "multiple,required,variant_axis,unit,group_name,position)"
                      + " values(?,?,?,?,?,'TEXT','COMMON_DEFAULT',false,false,false,'','基本参数',0)",
                  id,
                  a,
                  spu,
                  "SPUONLY" + UUID.randomUUID().toString().substring(0, 4),
                  "商品私有属性");
              return id;
            });
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        mappings.saveAttributeRule(
                            actor, mapping.id(), null, attributeInput("x", "X", spuAttribute, "OPTIONAL", "TEXT"))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("租户级属性字典条目");
    // 下拉候选里也不该出现它（界面上给出后来会被服务端拒绝的选项是最白填一次的）。
    assertThat(tx(actor, () -> mappings.attributeOptions("商品私有属性"))).isEmpty();

    // 改/删别人的规则：归属校验挡住。
    PlatformAttributeMappingRow rule =
        write(
            actor,
            () ->
                mappings.saveAttributeRule(
                    actor, mapping.id(), null, attributeInput("material", "材质", material, "REQUIRED", "TEXT")));
    UUID otherTree = tree(actor, "INTERNAL", null);
    UUID otherCategory = category(actor, otherTree, null, "袜子");
    PlatformCategoryMappingRow otherMapping =
        save(mappingInput(otherCategory, "AMAZON", siteA(), amazonShirt, false));
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        mappings.saveAttributeRule(
                            actor,
                            otherMapping.id(),
                            rule.id(),
                            attributeInput("material", "材质", material, "REQUIRED", "TEXT"))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不属于当前映射");
    assertThatThrownBy(
            () -> writeVoid(actor, () -> mappings.deleteAttributeRule(actor, otherMapping.id(), rule.id())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不属于当前映射");
    assertThat(tx(actor, () -> mappings.attributeRows(List.of(mapping.id()))).size()).isEqualTo(1);
  }

  @Autowired CatalogService catalogService;

  String materialCode(UUID attributeId) {
    return tx(actor, () -> sql.fetchOne("select code from attribute_definition where id=?", attributeId))
        .get("code", String.class);
  }

  // ------------------------------------------------------------ resolve（4.2.1.1）

  @Test
  void resolvePicksTheDefaultTargetAndRefusesToGuessWhenAmbiguous() {
    UUID internalTree = tree(actor, "INTERNAL", null);
    UUID shirt = category(actor, internalTree, null, "T恤");
    UUID amazonTree = tree(actor, "PLATFORM", "AMAZON");
    UUID amazonRoot = category(actor, amazonTree, null, "服饰");
    UUID amazonShirt = category(actor, amazonTree, amazonRoot, "男士上装");
    UUID amazonShirtAlt = category(actor, amazonTree, amazonRoot, "男士上装-活动");
    UUID material = attribute(actor, "MAT" + UUID.randomUUID().toString().substring(0, 4), "材质", "TEXT");

    // 没有任何映射 → 明确报未映射，不给一个假的默认。
    assertThatThrownBy(() -> tx(actor, () -> mappings.resolve(shirt, "AMAZON", siteA())))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND))
        .hasMessageContaining("还没有启用的映射");

    PlatformCategoryMappingRow main = save(mappingInput(shirt, "AMAZON", siteA(), amazonShirt, true));
    write(
        actor,
        () ->
            mappings.saveAttributeRule(
                actor, main.id(), null, attributeInput("material", "材质", material, "REQUIRED", "TEXT")));
    write(
        actor,
        () ->
            mappings.saveAttributeRule(
                actor, main.id(), null, attributeInput("care", "保养说明", null, "REQUIRED", "TEXT")));
    write(
        actor,
        () ->
            mappings.saveAttributeRule(
                actor, main.id(), null, attributeInput("bullet", "要点", null, "OPTIONAL", "TEXT")));

    PlatformCategoryResolveResult ok = tx(actor, () -> mappings.resolve(shirt, "AMAZON", siteA()));
    assertThat(ok.platformCategoryId()).isEqualTo(amazonShirt);
    assertThat(ok.isDefault()).isTrue();
    // 路径串把平台树的层级显示出来（运营认平台类目靠的是路径而不是 id）。
    assertThat(ok.platformCategoryPath()).isEqualTo("服饰 / 男士上装");
    assertThat(ok.requiredAttributes()).hasSize(3);
    assertThat(ok.requiredAttributes())
        .filteredOn(PlatformRequiredAttribute::mapped)
        .extracting(PlatformRequiredAttribute::platformAttributeCode)
        .containsExactly("material");
    assertThat(ok.unmappedRequiredCount()).isEqualTo(1);

    // 多条启用映射但没有默认 → 明确要求指定，不静默挑第一条。
    // 注意是「两条都启用且都不是默认」：只留一条启用映射时 resolve 本就该直接命中，
    // 那样测不出歧义这一支。
    save(mappingInput(shirt, "AMAZON", siteA(), amazonShirtAlt, false));
    var mainNow = tx(actor, () -> mappings.detail(main.id())).mapping();
    write(
        actor,
        () ->
            mappings.save(
                actor,
                main.id(),
                new PlatformCategoryMappingInput(
                    shirt, "AMAZON", siteA(), amazonShirt, false, true, mainNow.version())));
    assertThatThrownBy(() -> tx(actor, () -> mappings.resolve(shirt, "AMAZON", siteA())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("设为默认");
  }

  // ------------------------------------------------------------ 概览与候选

  @Test
  void overviewAndCandidatesComeFromRealDataNotConstants() {
    UUID internalTree = tree(actor, "INTERNAL", null);
    UUID shirt = category(actor, internalTree, null, "T恤");
    UUID amazonTree = tree(actor, "PLATFORM", "AMAZON");
    UUID amazonShirt = category(actor, amazonTree, null, "Amazon 男士上装");
    UUID material = attribute(actor, "MAT" + UUID.randomUUID().toString().substring(0, 4), "材质", "TEXT");

    PlatformCategoryMappingRow mapping = save(mappingInput(shirt, "AMAZON", siteA(), amazonShirt, true));
    write(
        actor,
        () ->
            mappings.saveAttributeRule(
                actor, mapping.id(), null, attributeInput("material", "材质", material, "REQUIRED", "TEXT")));
    write(
        actor,
        () ->
            mappings.saveAttributeRule(
                actor, mapping.id(), null, attributeInput("care", "保养说明", null, "REQUIRED", "TEXT")));

    PlatformCategoryMappingOverview overview = tx(actor, () -> mappings.overview());
    assertThat(overview.mappingCount()).isGreaterThanOrEqualTo(1);
    assertThat(overview.defaultMappingCount()).isGreaterThanOrEqualTo(1);
    assertThat(overview.internalCategoryCount()).isGreaterThanOrEqualTo(1);
    assertThat(overview.mappedInternalCategoryCount()).isGreaterThanOrEqualTo(1);
    assertThat(overview.platformCount()).isGreaterThanOrEqualTo(1);
    assertThat(overview.siteCount()).isGreaterThanOrEqualTo(1);
    assertThat(overview.unmappedRequiredCount()).isGreaterThanOrEqualTo(1);
    assertThat(overview.coverage())
        .filteredOn(c -> "AMAZON".equals(c.platformCode()) && siteA().equals(c.siteCode()))
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.mappingCount()).isEqualTo(1);
              assertThat(c.unmappedRequiredCount()).isEqualTo(1);
              assertThat(c.mappedInternalCategoryCount()).isEqualTo(1);
            });

    // 平台候选来自真实建好的平台树，不是硬编码常量。
    var sites = tx(actor, () -> mappings.platformSites());
    assertThat(sites).extracting(PlatformSiteOption::platformCode).contains("AMAZON");
    assertThat(sites)
        .filteredOn(s -> "AMAZON".equals(s.platformCode()))
        .singleElement()
        .satisfies(s -> assertThat(s.siteCodes()).contains(siteA()));

    // 内部属性候选只给租户级字典条目。
    assertThat(tx(actor, () -> mappings.attributeOptions("材质")))
        .extracting(InternalAttributeOption::name)
        .contains("材质");
  }

  // ------------------------------------------------------------ 真分页

  @Test
  void pageIsRealPaginationWithMatchingTotal() {
    UUID internalTree = tree(actor, "INTERNAL", null);
    UUID amazonTree = tree(actor, "PLATFORM", "AMAZON");
    UUID amazonShirt = category(actor, amazonTree, null, "Amazon 男士上装");
    List<UUID> saved = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      UUID shirt = category(actor, internalTree, null, "分页商品 " + i);
      saved.add(save(mappingInput(shirt, "AMAZON", siteA(), amazonShirt, false)).id());
    }

    var first = tx(actor, () -> mappings.page(null, "AMAZON", siteA(), "分页商品", null, false, "createdAt,asc", 1, 2));
    assertThat(first.items()).hasSize(2);
    assertThat(first.page()).isEqualTo(1);
    assertThat(first.size()).isEqualTo(2);
    assertThat(first.total()).isEqualTo(5);

    var second = tx(actor, () -> mappings.page(null, "AMAZON", siteA(), "分页商品", null, false, "createdAt,asc", 2, 2));
    var third = tx(actor, () -> mappings.page(null, "AMAZON", siteA(), "分页商品", null, false, "createdAt,asc", 3, 2));
    assertThat(second.items()).hasSize(2);
    assertThat(third.items()).hasSize(1);
    // 三页合起来正好 5 条且不重不漏 —— 这才是真分页，假分页会在这里露馅。
    List<UUID> paged = new ArrayList<>();
    first.items().forEach(r -> paged.add(r.id()));
    second.items().forEach(r -> paged.add(r.id()));
    third.items().forEach(r -> paged.add(r.id()));
    assertThat(paged).containsExactlyInAnyOrderElementsOf(saved);
  }

  // ------------------------------------------------------------ 停用与删除

  @Test
  void disablingKeepsAttributeRulesAndDeletingCascadesThem() {
    UUID internalTree = tree(actor, "INTERNAL", null);
    UUID shirt = category(actor, internalTree, null, "T恤");
    UUID amazonTree = tree(actor, "PLATFORM", "AMAZON");
    UUID amazonShirt = category(actor, amazonTree, null, "Amazon 男士上装");
    UUID material = attribute(actor, "MAT" + UUID.randomUUID().toString().substring(0, 4), "材质", "TEXT");

    PlatformCategoryMappingRow mapping = save(mappingInput(shirt, "AMAZON", siteA(), amazonShirt, true));
    write(
        actor,
        () ->
            mappings.saveAttributeRule(
                actor, mapping.id(), null, attributeInput("material", "材质", material, "REQUIRED", "TEXT")));

    writeVoid(actor, () -> mappings.setEnabled(actor, mapping.id(), false, mapping.version()));
    // 停用后：按「只看启用」筛不到它，按「只看停用」能筛到它。
    assertThat(tx(actor, () -> mappings.page(shirt, "AMAZON", siteA(), null, true, false, null, 1, 20)).items())
        .isEmpty();
    assertThat(tx(actor, () -> mappings.page(shirt, "AMAZON", siteA(), null, false, false, null, 1, 20)).items())
        .hasSize(1);
    // 停用不删属性规则：平台仍然要求那些属性。
    assertThat(tx(actor, () -> mappings.attributeRows(List.of(mapping.id())))).hasSize(1);

    // 乐观锁：陈旧 version 被拒绝。
    assertThatThrownBy(() -> writeVoid(actor, () -> mappings.delete(actor, mapping.id(), mapping.version())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已被他人修改");

    long currentVersion =
        tx(
                actor,
                () ->
                    sql.fetchOne("select version from platform_category_mapping where id=?", mapping.id())
                        .get("version", Long.class));
    writeVoid(actor, () -> mappings.delete(actor, mapping.id(), currentVersion));
    assertThat(tx(actor, () -> mappings.attributeRows(List.of(mapping.id())))).isEmpty();
    assertThatThrownBy(() -> tx(actor, () -> mappings.detail(mapping.id())))
        .isInstanceOf(ResponseStatusException.class);
  }

  // ------------------------------------------------------------ timestamptz 往返

  @Test
  void timestampsRoundTripWithoutTheSilentEightHourShift() throws Exception {
    UUID internalTree = tree(actor, "INTERNAL", null);
    UUID shirt = category(actor, internalTree, null, "T恤");
    UUID amazonTree = tree(actor, "PLATFORM", "AMAZON");
    UUID amazonShirt = category(actor, amazonTree, null, "Amazon 男士上装");

    OffsetDateTime before = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    PlatformCategoryMappingRow row = save(mappingInput(shirt, "AMAZON", siteA(), amazonShirt, true));

    // 断言写回的值，而不是只断言「没抛异常」。
    // 若把 timestamptz 当 LocalDateTime 绑定，PG 存的是对的、读出来会偏 −28800s，
    // 症状是页面上所有时间都早 8 小时，而 created_at 是 now() 生成的、看起来「一直是这样」。
    OffsetDateTime dbCreated =
        tx(
            actor,
            () ->
                sql.fetchOne("select created_at from platform_category_mapping where id=?", row.id())
                    .get("created_at", OffsetDateTime.class));
    assertThat(dbCreated).isNotNull();
    assertThat(Math.abs(Duration.between(before, dbCreated).toMinutes()))
        .as("created_at 写回值必须落在测试时刻附近，偏 8 小时即为时区静默漂移")
        .isLessThan(5);

    OffsetDateTime dtoCreated = OffsetDateTime.parse(row.createdAt());
    assertThat(dtoCreated.toInstant()).isEqualTo(dbCreated.toInstant());
    // DTO 输出的是 UTC ISO 串，前端拿它渲染不会随浏览器时区变化。
    assertThat(row.createdAt()).endsWith("Z");

    // updated_at 同样核对，并确认改一次之后它确实前移。
    String firstUpdated = tx(actor, () -> mappings.detail(row.id())).mapping().updatedAt();
    Thread.sleep(1100);
    write(
        actor,
        () ->
            mappings.save(
                actor,
                row.id(),
                new PlatformCategoryMappingInput(
                    shirt, "AMAZON", siteA(), amazonShirt, true, false, row.version())));
    String secondUpdated = tx(actor, () -> mappings.detail(row.id())).mapping().updatedAt();
    assertThat(OffsetDateTime.parse(secondUpdated))
        .isAfter(OffsetDateTime.parse(firstUpdated));
  }

  // ------------------------------------------------------------ 租户隔离三断言

  @Test
  void tenantIsolationTwoWayForgedHeaderAndConnectionReuse() throws Exception {
    UUID theirTree = tree(actorB, "INTERNAL", null);
    UUID theirShirt = category(actorB, theirTree, null, "B 的衬衫");
    UUID theirAmazonTree = tree(actorB, "PLATFORM", "AMAZON");
    UUID theirAmazonShirt = category(actorB, theirAmazonTree, null, "B 的 Amazon 衬衫");
    UUID theirMaterial =
        attribute(actorB, "MAT" + UUID.randomUUID().toString().substring(0, 4), "材质", "TEXT");
    PlatformCategoryMappingRow theirs =
        write(
            actorB,
            () ->
                mappings.save(
                    actorB, null, mappingInput(theirShirt, "AMAZON", siteA(), theirAmazonShirt, true)));
    write(
        actorB,
        () ->
            mappings.saveAttributeRule(
                actorB,
                theirs.id(),
                null,
                attributeInput("material", "材质", theirMaterial, "REQUIRED", "TEXT")));

    UUID myTree = tree(actor, "INTERNAL", null);
    UUID myShirt = category(actor, myTree, null, "A 的衬衫");
    UUID myAmazonTree = tree(actor, "PLATFORM", "AMAZON");
    UUID myAmazonShirt = category(actor, myAmazonTree, null, "A 的 Amazon 衬衫");
    PlatformCategoryMappingRow mine =
        save(mappingInput(myShirt, "AMAZON", siteA(), myAmazonShirt, true));

    // 1) 两租户互不可见。
    assertThat(tx(actor, () -> mappings.page(null, "AMAZON", siteA(), null, null, false, null, 1, 50)).items())
        .extracting(PlatformCategoryMappingRow::id)
        .contains(mine.id())
        .doesNotContain(theirs.id());
    assertThat(tx(actorB, () -> mappings.page(null, "AMAZON", siteA(), null, null, false, null, 1, 50)).items())
        .extracting(PlatformCategoryMappingRow::id)
        .contains(theirs.id())
        .doesNotContain(mine.id());
    // 详情同样按行隔离：A 读 B 的映射拿到的是 404 而不是「空对象」。
    assertThatThrownBy(() -> tx(actor, () -> mappings.detail(theirs.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> mappings.detail(mine.id())))
        .isInstanceOf(ResponseStatusException.class);
    // 概览的缺口统计也不能把对方的必填属性算进来。
    // 用相对断言（同容器里别的测试也会造数据，绝对值不可靠）：B 那条已绑定的规则
    // 不构成缺口，所以两边缺口数相同；A 看 B 的规则数为 0。
    assertThat(tx(actor, () -> mappings.attributeRows(List.of(theirs.id())))).isEmpty();
    // B 连加两条属性规则（一条未绑、一条已绑）：B 的两个计数各涨，A 的必须纹丝不动。
    // 用增量而不是绝对值——同一个容器里其它测试也在租户 A 造数据，绝对值不可靠。
    long aRulesBefore = tx(actor, () -> mappings.overview().attributeRuleCount());
    long aUnmappedBefore = tx(actor, () -> mappings.overview().unmappedRequiredCount());
    long bRulesBefore = tx(actorB, () -> mappings.overview().attributeRuleCount());
    long bUnmappedBefore = tx(actorB, () -> mappings.overview().unmappedRequiredCount());
    write(
        actorB,
        () ->
            mappings.saveAttributeRule(
                actorB,
                theirs.id(),
                null,
                attributeInput("care", "保养说明", null, "REQUIRED", "TEXT")));
    write(
        actorB,
        () ->
            mappings.saveAttributeRule(
                actorB,
                theirs.id(),
                null,
                attributeInput("brand_line", "品牌行", theirMaterial, "REQUIRED", "TEXT")));
    assertThat(tx(actorB, () -> mappings.overview().attributeRuleCount())).isEqualTo(bRulesBefore + 2);
    assertThat(tx(actorB, () -> mappings.overview().unmappedRequiredCount()))
        .isEqualTo(bUnmappedBefore + 1);
    assertThat(tx(actor, () -> mappings.overview().attributeRuleCount())).isEqualTo(aRulesBefore);
    assertThat(tx(actor, () -> mappings.overview().unmappedRequiredCount())).isEqualTo(aUnmappedBefore);

    // 2) 伪造 tenant header 无效：租户只来自已认证的成员关系。
    http.perform(
            get("/catalog/category-mappings/" + theirs.id())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/category-mappings")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .param("platformCode", "AMAZON")
                // 站点也要带上：两个租户用的是**同一个**站点码，
                // 所以这里能分辨的唯一依据就是 RLS，不靠筛选条件把差异挡掉。
                .param("siteCode", siteA()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].id", hasItem(theirs.id().toString())))
        .andExpect(jsonPath("$.items[*].id", not(hasItem(mine.id().toString()))));
    // 伪造头也不能借道写。
    http.perform(
            post("/catalog/category-mappings")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType("application/json")
                .content(
                    "{\"internalCategoryId\":\""
                        + myShirt
                        + "\",\"platformCode\":\"AMAZON\",\"siteCode\":\"US\",\"platformCategoryId\":\""
                        + myAmazonShirt
                        + "\",\"isDefault\":true,\"enabled\":true,\"version\":0}"))
        .andExpect(status().isNotFound());
    assertThat(tx(actorB, () -> mappings.page(null, "AMAZON", siteA(), null, null, false, null, 1, 50)).items())
        .extracting(PlatformCategoryMappingRow::id)
        .doesNotContain(mine.id());

    // 3) 连接池归还后不串租户（单连接反复交替）。
    for (int i = 0; i < 5; i++) {
      assertThat(tx(actor, () -> mappings.detail(mine.id())).mapping().id()).isEqualTo(mine.id());
      assertThat(tx(actorB, () -> mappings.detail(theirs.id())).mapping().id()).isEqualTo(theirs.id());
      // 交叉访问：B 拿不到 A 的映射（RLS 直接读不到行）。
      assertThatThrownBy(() -> tx(actorB, () -> mappings.detail(mine.id())))
          .isInstanceOf(ResponseStatusException.class);
      // resolve 路径同样不能串（它是另一个查询形状）。
      assertThat(tx(actor, () -> mappings.resolve(myShirt, "AMAZON", siteA())).internalCategoryId())
          .isEqualTo(myShirt);
      assertThat(tx(actorB, () -> mappings.resolve(theirShirt, "AMAZON", siteA())).internalCategoryId())
          .isEqualTo(theirShirt);
      assertThat(tx(actor, () -> mappings.attributeRows(List.of(mine.id()))).size()).isEqualTo(0);
      // B 的三条（material 已绑 + care 未绑 + brand_line 已绑）反复读都只属于 B。
      assertThat(tx(actorB, () -> mappings.attributeRows(List.of(theirs.id()))).size()).isEqualTo(3);
    }
  }

  // ------------------------------------------------------------ HTTP 与权限

  @Test
  void httpEndpointsExposeTheRealWorkflowAndEnforcePermissions() throws Exception {
    UUID internalTree = tree(actor, "INTERNAL", null);
    UUID shirt = category(actor, internalTree, null, "HTTP 类目");
    UUID amazonTree = tree(actor, "PLATFORM", "AMAZON");
    UUID amazonShirt = category(actor, amazonTree, null, "HTTP Amazon 类目");
    UUID material = attribute(actor, "MAT" + UUID.randomUUID().toString().substring(0, 4), "材质", "TEXT");

    http.perform(
            post("/catalog/category-mappings")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"internalCategoryId\":\""
                        + shirt
                        + "\",\"platformCode\":\"amazon\",\"siteCode\":\""
                        + siteA().toLowerCase()
                        + "\",\"platformCategoryId\":\""
                        + amazonShirt
                        + "\",\"isDefault\":true,\"enabled\":true,\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.platformCode").value("AMAZON"))
        .andExpect(jsonPath("$.siteCode").value(siteA()))
        .andExpect(jsonPath("$.isDefault").value(true));

    var mappingId =
        tx(actor, () -> mappings.page(null, "AMAZON", siteA(), "HTTP 类目", null, false, null, 1, 20))
            .items()
            .get(0)
            .id();

    http.perform(
            post("/catalog/category-mappings/" + mappingId + "/attributes")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"platformAttributeCode\":\"material\",\"platformAttributeName\":\"材质\","
                        + "\"internalAttributeId\":\""
                        + material
                        + "\",\"requirement\":\"REQUIRED\",\"platformDataType\":\"TEXT\","
                        + "\"multiple\":false,\"allowedValues\":[],\"transform\":\"DIRECT\","
                        + "\"enabled\":true,\"position\":0,\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.internalAttributeName").value("材质"));

    http.perform(
            post("/catalog/category-mappings/" + mappingId + "/attributes")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"platformAttributeCode\":\"color\",\"platformAttributeName\":\"颜色\","
                        + "\"requirement\":\"REQUIRED\",\"platformDataType\":\"ENUM\","
                        + "\"multiple\":false,\"allowedValues\":[],\"transform\":\"DIRECT\","
                        + "\"enabled\":true,\"position\":0,\"version\":0}"))
        .andExpect(status().isBadRequest());

    http.perform(get("/catalog/category-mappings/" + mappingId).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.attributes.length()").value(1))
        .andExpect(jsonPath("$.summary.unmappedRequiredCount").value(0));

    http.perform(
            get("/catalog/category-mappings/resolve")
                .header("Authorization", "Bearer tenant-a")
                .param("internalCategoryId", shirt.toString())
                .param("platformCode", "AMAZON")
                .param("siteCode", siteA()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requiredAttributes.length()").value(1))
        .andExpect(jsonPath("$.unmappedRequiredCount").value(0));

    http.perform(
            get("/catalog/category-mappings/platform-sites").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].platformCode", hasItem("AMAZON")));

    http.perform(
            get("/catalog/category-mappings/attribute-options?q=材质")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());

    // 只有读权限的角色不能写。
    http.perform(
            post("/catalog/category-mappings")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(
                    "{\"internalCategoryId\":\""
                        + shirt
                        + "\",\"platformCode\":\"AMAZON\",\"siteCode\":\"US\",\"platformCategoryId\":\""
                        + amazonShirt
                        + "\",\"isDefault\":true,\"enabled\":true,\"version\":0}"))
        .andExpect(status().isForbidden());

    // 未认证 401。
    http.perform(get("/catalog/category-mappings")).andExpect(status().isUnauthorized());
  }
}
