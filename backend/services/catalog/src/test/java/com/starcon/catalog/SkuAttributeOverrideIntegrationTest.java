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
 * 3.2.2.2 SKU 属性覆写：四种来源的解析、覆写 / 显式清空 / 取消覆写、类型与选项校验、
 * 类目必填校验，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>所有服务调用都经 {@link #write} / {@link #read} 包一层 {@code access.run}：
 * 租户上下文是事务级设置的（{@code set_config(..., true)}），裸调服务方法拿不到
 * {@code app.current_tenant}，RLS 会把行全部过滤掉——那样断言的不是业务规则而是 RLS。
 *
 * <p>每个用例用独立编码与类目：容器在整个测试类共用，固定编码会互相抢占字典位。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SkuAttributeOverrideIntegrationTest {
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
      // 只有属性读权限：所有写端点必须 403。
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
  @Autowired CategoryTreeService categories;
  @Autowired SkuAttributeOverrideService overrides;
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
  AccessService.Access reader;

  @BeforeEach
  void setup() {
    actor = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    reader = actors.get("Bearer reader");
  }

  /** 写上下文：包一层租户事务，异常会回滚整笔写入。 */
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

  <T> T read(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.attribute.read", work);
  }

  // ============================================================ 夹具

  static String uniqueCode(String hint) {
    return (hint + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT))
        .replace('-', 'X');
  }

  static String uniqueCategory(String hint) {
    return hint + "-" + UUID.randomUUID().toString().substring(0, 6);
  }

  /** 建一个草稿商品；saveSpu 只回 id，靠类目回查避免依赖查询顺序。 */
  UUID spu(String category) {
    return spuFor(actor, category);
  }

  UUID spuFor(AccessService.Access who, String category) {
    write(
        who,
        () ->
            catalog.saveSpu(
                who,
                null,
                new SpuInput(
                    "OVR-" + UUID.randomUUID().toString().substring(0, 8),
                    "覆写测试商品",
                    "描述",
                    "品牌",
                    category,
                    "DRAFT",
                    0)));
    return read(
            who,
            () ->
                sql.fetchOne(
                    "select id from spu where category=? order by created_at desc limit 1", category))
        .get("id", UUID.class);
  }

  /**
   * 建一条 SPU 级属性定义。
   *
   * <p>每次都现读 {@code spu.version} 再传进去：{@code saveAttribute} 会走乐观锁并顶一次版本号，
   * 固定传 0 在第二条属性上就会被 409 拒掉。
   */
  UUID attr(
      UUID spuId,
      String name,
      String dataType,
      String valueMode,
      boolean required,
      List<String> options,
      List<String> defaultValues) {
    String code = uniqueCode("A");
    write(
        actor,
        () ->
            catalog.saveAttribute(
                actor,
                spuId,
                null,
                new AttributeInput(
                    code,
                    name,
                    dataType,
                    valueMode,
                    false,
                    required,
                    false,
                    "",
                    "基本参数",
                    options,
                    defaultValues,
                    spuVersion(spuId))));
    return read(
            actor,
            () ->
                sql.fetchOne(
                    "select id from attribute_definition where spu_id=? and code=?", spuId, code))
        .get("id", UUID.class);
  }

  long spuVersion(UUID spuId) {
    return read(actor, () -> sql.fetchOne("select version from spu where id=?", spuId))
        .get("version", Long.class);
  }

  UUID createSku(UUID spuId, List<ValueInput> values) {
    return write(
        actor,
        () ->
            catalog.saveSku(
                actor,
                spuId,
                null,
                new SkuInput(
                    "SKU-" + UUID.randomUUID().toString().substring(0, 8),
                    "覆写测试 SKU",
                    "",
                    "件",
                    "DRAFT",
                    values,
                    spuVersion(spuId))));
  }

  /**
   * 给某个类目挂一条必填规则。
   *
   * <p>用 SQL 直插而不是 3.2.1.1 的端点：已交付的 {@code saveCategoryRule} 走 {@code dictionaryRow}，
   * 要求 {@code spu_id} 为空，只能给字典条目建规则。但表的复合外键
   * {@code (tenant_id,attribute_id) → attribute_definition(tenant_id,id)} 与 RLS 都允许指向
   * SPU 级定义，所以本模块读的这条校验路径是真实可用的——不是为测试单开的后门。
   *
   * <p>必须在租户上下文里写：裸 {@code sql.execute} 不带 {@code app.current_tenant}，RLS 会直接拒。
   */
  void categoryRule(String category, UUID attributeId) {
    write(
        actor,
        () ->
            sql.execute(
                "insert into category_attribute_rule(tenant_id,category,category_id,attribute_id,required)"
                    + " values(?,?,?,?,true)",
                actor.tenantId(),
                category,
                // V14 之后 category_id 是 NOT NULL 外键：规则必须锚在一个真实类目上。
                // 解析走 CategoryTreeService（3.3.1.1 交付的唯一写入口），语义与
                // AttributeDictionaryService.saveCategoryRule 里那次调用完全一致。
                categories.resolveOrCreateCategoryId(actor, category),
                attributeId));
  }

  SkuOverrideView view(UUID skuId) {
    return read(actor, () -> overrides.view(skuId));
  }

  SkuOverrideView viewOf(AccessService.Access who, UUID skuId) {
    return read(who, () -> overrides.view(skuId));
  }

  SkuOverrideAttribute row(SkuOverrideView panel, UUID attributeId) {
    return panel.attributes().stream()
        .filter(a -> a.attributeId().equals(attributeId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("面板里没有这条属性：" + attributeId));
  }

  static SkuOverrideInput values(String... values) {
    return new SkuOverrideInput(List.of(values));
  }

  // ============================================================ 断言辅助

  /**
   * 断言这次写会被 400 拒掉。
   *
   * <p>两种形态都算：服务层用 {@code CatalogService.check} 抛的 {@link ResponseStatusException}，
   * 以及 {@code ApiErrorHandler} 映射成 400 的 {@link IllegalArgumentException} /
   * {@link java.time.format.DateTimeParseException}——日期格式就是这么拒的，
   * 用户传错格式不该看到 500。
   */
  void assertBadRequest(java.util.function.Supplier<?> work) {
    assertBadRequest(actor, work);
  }

  void assertBadRequest(AccessService.Access who, java.util.function.Supplier<?> work) {
    try {
      write(who, work);
    } catch (ResponseStatusException e) {
      assertThat(e.getStatusCode().value()).isEqualTo(400);
      return;
    } catch (IllegalArgumentException | java.time.format.DateTimeParseException expected) {
      return;
    }
    throw new AssertionError("预期 400，但请求通过了");
  }

  void assertNotFound(java.util.function.Supplier<?> work) {
    assertNotFound(actor, work);
  }

  ResponseStatusException expectFailure(java.util.function.Supplier<?> work) {
    return expectFailure(actor, work);
  }

  ResponseStatusException expectFailure(
      AccessService.Access who, java.util.function.Supplier<?> work) {
    try {
      write(who, work);
    } catch (ResponseStatusException e) {
      return e;
    }
    throw new AssertionError("预期被拒（400），但请求通过了");
  }

  /** 取回异常对象用于检查结构化载荷；任何异常类型都算数。 */
  Throwable thrown(java.util.function.Supplier<?> work) {
    return thrown(actor, work);
  }

  Throwable thrown(AccessService.Access who, java.util.function.Supplier<?> work) {
    try {
      write(who, work);
    } catch (RuntimeException | Error e) {
      return e;
    }
    throw new AssertionError("预期抛异常，但没有");
  }

  void assertNotFound(AccessService.Access who, java.util.function.Supplier<?> work) {
    ResponseStatusException e = expectFailure(who, work);
    assertThat(e.getStatusCode().value()).isEqualTo(404);
  }

  // ============================================================ 四态解析

  /**
   * 核心断言：同一个 SKU 的四条属性分别落在四种来源上，且「显式清空」**不等于**「未设置」。
   *
   * <p>这是整个模块的灵魂——清空那条有效值是空且不退回公共值；未设置那条也是空，
   * 但两者在数据层形态不同（一个有 state=CLEAR 的行，一个根本没有行）。
   */
  @Test
  void resolvesFourSourcesAndExplicitClearIsNotTheSameAsUnset() {
    UUID spuId = spu(uniqueCategory("four"));
    UUID inherit = attr(spuId, "材质", "TEXT", "COMMON_DEFAULT", false, List.of(), List.of("公共材质"));
    UUID override = attr(spuId, "克重", "TEXT", "COMMON_DEFAULT", false, List.of(), List.of("公共克重"));
    UUID cleared = attr(spuId, "容量", "TEXT", "COMMON_DEFAULT", false, List.of(), List.of("公共容量"));
    UUID never = attr(spuId, "保质期", "TEXT", "COMMON_DEFAULT", false, List.of(), List.of());
    UUID skuId = createSku(spuId, List.of());

    set(actor, skuId, override, values("SKU 克重"));
    clear(actor, skuId, cleared);

    var panel = view(skuId);

    // SPU_COMMON：SKU 侧没有行，继承公共值
    var inherited = row(panel, inherit);
    assertThat(inherited.source()).isEqualTo("SPU_COMMON");
    assertThat(inherited.sourceLabel()).isEqualTo("SPU 公共值");
    assertThat(inherited.effectiveValues()).containsExactly("公共材质");
    assertThat(inherited.overrideState()).isEqualTo("INHERIT");
    assertThat(inherited.inherited()).isTrue();

    // SKU_OVERRIDE
    var over = row(panel, override);
    assertThat(over.source()).isEqualTo("SKU_OVERRIDE");
    assertThat(over.effectiveValues()).containsExactly("SKU 克重");
    assertThat(over.overrideState()).isEqualTo("SET");
    assertThat(over.inherited()).isFalse();
    assertThat(over.rawValues()).containsExactly("SKU 克重");

    // SKU_CLEARED：有效值为空，且**不退回公共值**
    var clear = row(panel, cleared);
    assertThat(clear.source()).isEqualTo("SKU_CLEARED");
    assertThat(clear.sourceLabel()).isEqualTo("SKU 已清空");
    assertThat(clear.effectiveValues()).isEmpty();
    assertThat(clear.rawValues()).isEmpty();
    assertThat(clear.inherited()).isFalse();
    assertThat(clear.overrideState()).isEqualTo("CLEAR");

    // UNSET：公共侧本来就没有值
    var unset = row(panel, never);
    assertThat(unset.source()).isEqualTo("UNSET");
    assertThat(unset.sourceLabel()).isEqualTo("未赋值");
    assertThat(unset.effectiveValues()).isEmpty();

    // 汇总四态计数
    assertThat(panel.summary().total()).isEqualTo(4);
    assertThat(panel.summary().inherited()).isEqualTo(1);
    assertThat(panel.summary().overridden()).isEqualTo(1);
    assertThat(panel.summary().cleared()).isEqualTo(1);
    assertThat(panel.summary().unset()).isEqualTo(1);

    // 数据层：清空是一个**存在的行**，未设置是**不存在的行**——两者不可能被 NULL 混同
    String clearedState =
        read(
                actor,
                () ->
                    sql.fetchOne(
                        "select state from attribute_value where attribute_id=? and sku_id=?",
                        cleared,
                        skuId))
            .get("state", String.class);
    assertThat(clearedState).isEqualTo("CLEAR");
    org.jooq.Record unsetRow =
        read(
            actor,
            () ->
                sql.fetchOne(
                    "select state from attribute_value where attribute_id=? and sku_id=?",
                    never,
                    skuId));
    assertThat(unsetRow).isNull();
    // 清空行不带任何取值（V1 的 validate_attribute_item 触发器也会拒绝）
    long clearedItems =
        read(
                actor,
                () ->
                    sql.fetchOne(
                        "select count(*) as c from attribute_value_item"
                            + " where value_id=(select id from attribute_value"
                            + " where attribute_id=? and sku_id=?)",
                        cleared,
                        skuId))
            .get("c", Long.class);
    assertThat(clearedItems).isZero();
  }

  /** SKU_ONLY 属性不继承公共值：公共侧本来就不能有值（V1 触发器拒绝），未填就是 UNSET。 */
  @Test
  void skuOnlyAttributeResolvesIndependently() {
    UUID spuId = spu(uniqueCategory("skuonly"));
    UUID own = attr(spuId, "独立属性", "TEXT", "SKU_ONLY", false, List.of(), List.of());
    UUID skuId = createSku(spuId, List.of());

    assertThat(row(view(skuId), own).source()).isEqualTo("UNSET");
    var panel = set(actor, skuId, own, values("本 SKU 专属"));
    assertThat(row(panel, own).source()).isEqualTo("SKU_OVERRIDE");
    assertThat(row(panel, own).effectiveValues()).containsExactly("本 SKU 专属");
    assertThat(row(panel, own).valueModeLabel()).isEqualTo("SKU 独立");
  }

  // ============================================================ 取消覆写

  /** 删除覆写回落 SPU 公共值；本来就没有覆写时幂等成功而不是 404。 */
  @Test
  void removingOverrideFallsBackToSpuValueAndIsIdempotent() {
    UUID spuId = spu(uniqueCategory("remove"));
    UUID color = attr(spuId, "颜色", "OPTION", "COMMON_DEFAULT", false, List.of("红", "蓝"), List.of("红"));
    UUID skuId = createSku(spuId, List.of());

    set(actor, skuId, color, values("蓝"));
    assertThat(row(view(skuId), color).effectiveValues()).containsExactly("蓝");

    // 先清空再删掉清空记录，等于直接落回继承
    clear(actor, skuId, color);
    assertThat(row(view(skuId), color).source()).isEqualTo("SKU_CLEARED");
    var back = remove(actor, skuId, color);
    assertThat(row(back, color).source()).isEqualTo("SPU_COMMON");
    assertThat(row(back, color).effectiveValues()).containsExactly("红");
    assertThat(row(back, color).overrideState()).isEqualTo("INHERIT");

    // 幂等：再删一次仍然成功，且结果不变
    var again = remove(actor, skuId, color);
    assertThat(row(again, color).source()).isEqualTo("SPU_COMMON");
  }

  // ============================================================ 类型与选项校验

  /** 四种数据类型走 SPU 侧同一套校验：数值精度、布尔字面量、日期格式、单值上限。 */
  @Test
  void validatesValueTypesThroughTheSameRulesAsSpuCommonValues() {
    UUID spuId = spu(uniqueCategory("types"));
    UUID decimal = attr(spuId, "重量", "DECIMAL", "COMMON_DEFAULT", false, List.of(), List.of());
    UUID bool = attr(spuId, "是否带电", "BOOLEAN", "COMMON_DEFAULT", false, List.of(), List.of());
    UUID date = attr(spuId, "生产日", "DATE", "COMMON_DEFAULT", false, List.of(), List.of());
    UUID single = attr(spuId, "单值属性", "TEXT", "COMMON_DEFAULT", false, List.of(), List.of());
    UUID skuId = createSku(spuId, List.of());

    // 合法值先落库，作为"非法提交必须整条回滚"的对照
    set(actor, skuId, decimal, values("12.5000000001"));
    set(actor, skuId, bool, values("true"));
    set(actor, skuId, date, values("2026-10-07"));
    assertThat(row(view(skuId), decimal).effectiveValues()).containsExactly("12.5000000001");

    // 数值超过 20 位整数
    assertBadRequest(() -> set(actor, skuId, decimal, values("123456789012345678901")));
    // 布尔只接受 true / false
    assertBadRequest(() -> set(actor, skuId, bool, values("TRUE")));
    // 日期必须能解析
    assertBadRequest(() -> set(actor, skuId, date, values("07/10/2026")));
    // 单值属性传两个值
    assertBadRequest(() -> set(actor, skuId, single, values("一", "二")));

    // 非法提交一条都没落库：合法值还在，没写过的仍是 UNSET
    var panel = view(skuId);
    assertThat(row(panel, decimal).effectiveValues()).containsExactly("12.5000000001");
    assertThat(row(panel, bool).effectiveValues()).containsExactly("true");
    assertThat(row(panel, single).source()).isEqualTo("UNSET");
  }

  /** 枚举取值必须属于该属性且处于启用状态；两种拒绝的说法不一样，要能分辨。 */
  @Test
  void rejectsOptionOutsideTheAttributeAndDisabledOption() {
    UUID spuId = spu(uniqueCategory("opt"));
    UUID color = attr(spuId, "颜色", "OPTION", "COMMON_DEFAULT", false, List.of("红", "蓝"), List.of());
    UUID skuId = createSku(spuId, List.of());

    // 合法
    set(actor, skuId, color, values("蓝"));
    assertThat(row(view(skuId), color).effectiveValues()).containsExactly("蓝");

    // 不属于该属性
    ResponseStatusException unknown = expectFailure(() -> set(actor, skuId, color, values("绿")));
    assertThat(unknown.getStatusCode().value()).isEqualTo(400);
    assertThat(unknown.getReason()).contains("没有选项");

    // 存在但已停用：说的是"停用"，不是"不存在"
    write(actor, () -> sql.execute(
        "update attribute_option set enabled=false where attribute_id=? and value=?", color, "蓝"));
    ResponseStatusException disabled =
        expectFailure(() -> set(actor, skuId, color, values("蓝")));
    assertThat(disabled.getStatusCode().value()).isEqualTo(400);
    assertThat(disabled.getReason()).contains("已停用");

    // 面板照实呈现：已停用的取值仍属于该属性的选项集合（历史值还要能解析）
    var panel = row(view(skuId), color);
    assertThat(panel.options()).containsExactlyInAnyOrder("红", "蓝");
    assertThat(panel.effectiveValues()).containsExactly("蓝");
  }

  /** 公共固定属性不接受 SKU 侧赋值；已停用属性不能赋新值但仍可取消覆写。 */
  @Test
  void commonLockedIsNotOverridableAndDisabledAttributeBlocksNewValueOnly() {
    UUID spuId = spu(uniqueCategory("locked"));
    UUID locked = attr(spuId, "锁死属性", "TEXT", "COMMON_LOCKED", false, List.of(), List.of("锁死值"));
    UUID soft = attr(spuId, "可停用属性", "TEXT", "COMMON_DEFAULT", false, List.of(), List.of("默认值"));
    UUID skuId = createSku(spuId, List.of());

    var panel = view(skuId);
    assertThat(row(panel, locked).overridable()).isFalse();
    assertThat(row(panel, locked).blockReason()).contains("公共固定");
    assertThat(row(panel, locked).valueModeLabel()).isEqualTo("公共固定");
    assertThat(panel.summary().blocked()).isEqualTo(1);
    assertBadRequest(() -> set(actor, skuId, locked, values("想改")));
    assertBadRequest(() -> clear(actor, skuId, locked));

    // 停用属性：挡住新建赋值，已有取值照常可读
    set(actor, skuId, soft, values("改过的值"));
    write(actor, () -> sql.execute("update attribute_definition set enabled=false where id=?", soft));
    var disabled = row(view(skuId), soft);
    assertThat(disabled.overridable()).isFalse();
    assertThat(disabled.blockReason()).contains("停用");
    assertThat(disabled.effectiveValues()).containsExactly("改过的值");
    assertBadRequest(() -> set(actor, skuId, soft, values("又改了")));
    // 但取消覆写仍然允许，否则「改不动」会变成「永远清不掉」
    var removed = remove(actor, skuId, soft);
    assertThat(row(removed, soft).source()).isEqualTo("SPU_COMMON");
    assertThat(row(removed, soft).effectiveValues()).containsExactly("默认值");
  }

  // ============================================================ 类目必填校验

  /**
   * 类目必填：有效值缺失时阻止保存，并回传**逐条**缺失清单。
   *
   * <p>类目规则用 SQL 直插，理由见 {@link #categoryRule} 的说明。
   */
  @Test
  void categoryRequiredBlocksSaveAndReturnsMissingList() {
    String category = uniqueCategory("req");
    UUID spuId = spu(category);
    UUID required = attr(spuId, "必填属性", "TEXT", "COMMON_DEFAULT", false, List.of(), List.of());
    UUID free = attr(spuId, "自由属性", "TEXT", "COMMON_DEFAULT", false, List.of(), List.of());
    UUID skuId = createSku(spuId, List.of());
    categoryRule(category, required);

    // 改一条与必填无关的属性也会被挡：这里是整 SKU 范围的必填校验
    var thrown =
        (SkuAttributeOverrideService.RequiredMissingException)
            thrown(actor, () -> set(actor, skuId, free, values("自由值")));
    assertThat(thrown.missing()).hasSize(1);
    assertThat(thrown.missing().getFirst().attributeId()).isEqualTo(required);
    assertThat(thrown.missing().getFirst().name()).isEqualTo("必填属性");
    assertThat(thrown.missing().getFirst().reason()).contains("还没有生效值");
    // 回滚：这条自由值没有落库
    assertThat(row(view(skuId), free).source()).isEqualTo("UNSET");

    // 面板照实标出缺失，界面上不用等 400 才知道差哪条
    var panel = view(skuId);
    assertThat(row(panel, required).missingRequired()).isTrue();
    assertThat(row(panel, required).categoryRequired()).isTrue();
    assertThat(row(panel, required).required()).isFalse();
    assertThat(panel.summary().missingRequired()).isEqualTo(1);

    // 显式清空必填属性同样被拒：清空就是声明这个 SKU 没有该属性
    ResponseStatusException refused = expectFailure(() -> clear(actor, skuId, required));
    assertThat(refused.getStatusCode().value()).isEqualTo(400);
    assertThat(refused.getReason()).contains("类目「" + category + "」");
    assertThat(refused.getReason()).contains("不能显式清空");

    // 补齐之后就能保存了
    var ok = set(actor, skuId, required, values("补上的必填值"));
    assertThat(row(ok, required).source()).isEqualTo("SKU_OVERRIDE");
    assertThat(row(ok, required).missingRequired()).isFalse();
    assertThat(ok.summary().missingRequired()).isZero();
    assertThat(row(set(actor, skuId, free, values("自由值")), free).effectiveValues())
        .containsExactly("自由值");

    // 清空必填属性此时也依然被拒：必填不是"当前缺了"才拦，是"这类属性不能清空"
    assertBadRequest(() -> clear(actor, skuId, required));
    assertThat(row(view(skuId), required).effectiveValues()).containsExactly("补上的必填值");

    // 取消覆写会让它重新缺必填，同样被挡
    var rollback =
        (SkuAttributeOverrideService.RequiredMissingException)
            thrown(actor, () -> remove(actor, skuId, required));
    assertThat(rollback.missing().getFirst().attributeId()).isEqualTo(required);
    assertThat(row(view(skuId), required).effectiveValues()).containsExactly("补上的必填值");
  }

  /** 定义自身 required 的属性同样不能被清空（与类目必填同一条口径）。 */
  @Test
  void definitionRequiredAttributeCannotBeCleared() {
    UUID spuId = spu(uniqueCategory("defreq"));
    UUID required = attr(spuId, "必填属性", "TEXT", "COMMON_DEFAULT", true, List.of(), List.of());
    UUID skuId = createSku(spuId, List.of(new ValueInput(required, "INHERIT", List.of())));

    // 必填且公共侧无值 → 面板立刻标出缺失
    assertThat(row(view(skuId), required).missingRequired()).isTrue();
    var panel = set(actor, skuId, required, values("SKU 值"));
    assertThat(row(panel, required).required()).isTrue();
    assertThat(row(panel, required).missingRequired()).isFalse();

    assertBadRequest(() -> clear(actor, skuId, required));
    assertThat(row(view(skuId), required).effectiveValues()).containsExactly("SKU 值");
  }

  /** 类目没有把属性标成必填时，清空是允许的——这正是「显式清空」的价值所在。 */
  @Test
  void clearingOptionalAttributeIsAllowedAndMeansAbsentNotUnset() {
    UUID spuId = spu(uniqueCategory("optional"));
    UUID capacity = attr(spuId, "容量", "TEXT", "COMMON_DEFAULT", false, List.of(), List.of("1L"));
    UUID skuId = createSku(spuId, List.of());

    var panel = clear(actor, skuId, capacity);
    var cleared = row(panel, capacity);
    assertThat(cleared.source()).isEqualTo("SKU_CLEARED");
    assertThat(cleared.effectiveValues()).isEmpty();
    assertThat(cleared.missingRequired()).isFalse();
    assertThat(cleared.overrideState()).isEqualTo("CLEAR");
    assertThat(cleared.rawValues()).isEmpty();
    // 公共值那行还在，只是被这一行 CLEAR 盖住了——这才是"显式清空"而非"删掉公共值"
    String commonState =
        read(
                actor,
                () ->
                    sql.fetchOne(
                        "select state from attribute_value where attribute_id=? and sku_id is null",
                        capacity))
            .get("state", String.class);
    assertThat(commonState).isEqualTo("SET");
  }

  // ============================================================ 权限、审计、版本

  /** 只读角色能看解析结果，但三个写端点全部 403。 */
  @Test
  void readOnlyRoleSeesPanelButCannotWrite() throws Exception {
    UUID spuId = spu(uniqueCategory("perm"));
    UUID color = attr(spuId, "颜色", "OPTION", "COMMON_DEFAULT", false, List.of("红"), List.of("红"));
    UUID skuId = createSku(spuId, List.of());
    String base = base(skuId);

    assertThat(row(viewOf(reader, skuId), color).effectiveValues()).containsExactly("红");
    http.perform(get(panel(skuId)).header("Authorization", "Bearer reader")).andExpect(status().isOk());
    http.perform(
            put(base + "/" + color)
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content("{\"values\":[\"红\"]}"))
        .andExpect(status().isForbidden());
    http.perform(put(base + "/" + color + "/clear").header("Authorization", "Bearer reader"))
        .andExpect(status().isForbidden());
    http.perform(delete(base + "/" + color).header("Authorization", "Bearer reader"))
        .andExpect(status().isForbidden());

    // 端点真的接上了：写端点在 MockMvc 上跑通，返回写入后的解析结果
    http.perform(
            put(base + "/" + color)
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"values\":[\"红\"]}"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("SKU_OVERRIDE")));
    http.perform(
            put(base + "/" + color + "/clear").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("SKU_CLEARED")));
    http.perform(delete(base + "/" + color).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("SPU_COMMON")));
  }

  /** 三种写动作各留一条审计事件，aggregate 仍是 SPU（下游按同一条流订阅）。 */
  @Test
  void eachWriteLeavesAnAuditEventKeyedOnTheSpu() {
    UUID spuId = spu(uniqueCategory("audit"));
    UUID material = attr(spuId, "材质", "TEXT", "COMMON_DEFAULT", false, List.of(), List.of("棉"));
    UUID skuId = createSku(spuId, List.of());

    set(actor, skuId, material, values("亚麻"));
    clear(actor, skuId, material);
    remove(actor, skuId, material);

    var actions =
        read(
                actor,
                () ->
                    sql.fetch(
                            "select action from audit_event where resource_id=? order by occurred_at",
                            spuId))
            .getValues("action", String.class);
    assertThat(actions)
        .containsSubsequence("SkuAttributeOverridden", "SkuAttributeCleared", "SkuAttributeOverrideRemoved");
  }

  /** 覆写写入不推进 sku.version：那个版本号是 3.1.2.x 的乐观锁凭据，不归本模块顶。 */
  @Test
  void overrideDoesNotBumpSkuVersion() {
    UUID spuId = spu(uniqueCategory("version"));
    UUID material = attr(spuId, "材质", "TEXT", "COMMON_DEFAULT", false, List.of(), List.of("棉"));
    UUID skuId = createSku(spuId, List.of());
    long before = skuVersion(skuId);

    set(actor, skuId, material, values("亚麻"));
    clear(actor, skuId, material);
    remove(actor, skuId, material);
    assertThat(skuVersion(skuId)).isEqualTo(before);

    // 但覆写行自己带系统时间，界面上能回答「这次覆写是什么时候写的」
    var panel = set(actor, skuId, material, values("亚麻"));
    assertThat(row(panel, material).overrideUpdatedAt()).isNotNull();
    // 继承态没有覆写行，因此没有覆写时间
    assertThat(row(remove(actor, skuId, material), material).overrideUpdatedAt()).isNull();
  }

  long skuVersion(UUID skuId) {
    return read(actor, () -> sql.fetchOne("select version from sku where id=?", skuId))
        .get("version", Long.class);
  }

  /** 陌生 SKU / 陌生属性 / 别的商品的属性定义：404，不是 403 也不是 500。 */
  @Test
  void unknownOrForeignResourceIsNotFound() {
    UUID spuId = spu(uniqueCategory("notfound"));
    UUID skuId = createSku(spuId, List.of());
    UUID otherSpu = spu(uniqueCategory("notfound-other"));

    assertNotFound(actor, () -> read(actor, () -> overrides.view(UUID.randomUUID())));
    assertNotFound(actor, () -> set(actor, skuId, UUID.randomUUID(), values("x")));
    // 别的商品的属性定义不能在这个 SKU 上覆写
    UUID foreign = attr(otherSpu, "别的商品属性", "TEXT", "COMMON_DEFAULT", false, List.of(), List.of("别的"));
    assertNotFound(actor, () -> set(actor, skuId, foreign, values("x")));
    assertNotFound(actor, () -> clear(actor, skuId, foreign));
    assertNotFound(actor, () -> remove(actor, skuId, foreign));
  }

  // ============================================================ 租户隔离三断言

  @Test
  void skuAttributeOverrideIsTenantIsolatedAndHeaderForgeryIsIgnored() throws Exception {
    UUID spuId = spu(uniqueCategory("iso"));
    UUID material = attr(spuId, "材质", "TEXT", "COMMON_DEFAULT", false, List.of(), List.of("我租户的公共值"));
    UUID skuId = createSku(spuId, List.of());
    set(actor, skuId, material, values("我租户的覆写值"));

    // B 租户建自己的商品与属性（**同名字段，不同租户**）
    UUID theirSpu = spuFor(actorB, uniqueCategory("iso"));
    UUID theirAttr = attrFor(actorB, theirSpu, "材质", List.of("B 租户的公共值"));
    UUID theirSku = skuFor(actorB, theirSpu);

    // 1) 两租户互不可见
    assertThat(row(view(skuId), material).effectiveValues()).containsExactly("我租户的覆写值");
    assertThat(row(viewOf(actorB, theirSku), theirAttr).effectiveValues())
        .containsExactly("B 租户的公共值");
    // B 读 A 的 SKU → 404（RLS 已经把行过滤掉，不是服务层软判断）
    assertNotFound(actorB, () -> read(actorB, () -> overrides.view(skuId)));
    // B 在 A 的 SKU 上写也 404
    assertNotFound(actorB, () -> set(actorB, skuId, material, values("B 越权写入")));
    assertNotFound(actorB, () -> clear(actorB, skuId, material));
    assertNotFound(actorB, () -> remove(actorB, skuId, material));
    // A 的数据仍在
    assertThat(row(view(skuId), material).effectiveValues()).containsExactly("我租户的覆写值");

    String base = base(skuId);

    // 2) 伪造 tenant header 无效：租户只来自已认证的成员关系
    http.perform(get(panel(skuId)).header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    http.perform(
            put(base + "/" + material + "/clear")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    http.perform(
            delete(base + "/" + material)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    // 伪造头也不能借道写：A 自己的会话带上 B 的租户头，仍然只能操作 A 的 SKU
    http.perform(get(panel(skuId)).header("Authorization", "Bearer tenant-a").header("X-Tenant-Id", b))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("我租户的覆写值")));
    assertThat(row(view(skuId), material).source()).isEqualTo("SKU_OVERRIDE");

    // 3) 连接池归还后不串租户（单连接反复交替）
    for (int i = 0; i < 5; i++) {
      assertThat(row(view(skuId), material).effectiveValues()).containsExactly("我租户的覆写值");
      assertThat(row(viewOf(actorB, theirSku), theirAttr).effectiveValues())
          .containsExactly("B 租户的公共值");
      assertNotFound(actorB, () -> read(actorB, () -> overrides.view(skuId)));
      assertNotFound(actorB, () -> clear(actorB, skuId, material));
      assertThat(row(view(skuId), material).source()).isEqualTo("SKU_OVERRIDE");
    }
  }

  // ============================================================ 薄封装

  /** 读端点路径。 */
  String panel(UUID skuId) {
    return "/catalog/sku-attribute-override/skus/" + skuId;
  }

  /** 写端点前缀。 */
  String base(UUID skuId) {
    return panel(skuId) + "/attribute-overrides";
  }

  SkuOverrideView set(
      AccessService.Access who, UUID skuId, UUID attributeId, SkuOverrideInput input) {
    return write(who, () -> overrides.set(who, skuId, attributeId, input));
  }

  SkuOverrideView clear(AccessService.Access who, UUID skuId, UUID attributeId) {
    return write(who, () -> overrides.clear(who, skuId, attributeId));
  }

  SkuOverrideView remove(AccessService.Access who, UUID skuId, UUID attributeId) {
    return write(who, () -> overrides.remove(who, skuId, attributeId));
  }

  /** B 租户侧的属性夹具：与 {@link #attr} 同形，只是操作人不同。 */
  UUID attrFor(AccessService.Access who, UUID spuId, String name, List<String> defaultValues) {
    String code = uniqueCode("A");
    write(
        who,
        () ->
            catalog.saveAttribute(
                who,
                spuId,
                null,
                new AttributeInput(
                    code,
                    name,
                    "TEXT",
                    "COMMON_DEFAULT",
                    false,
                    false,
                    false,
                    "",
                    "基本参数",
                    List.of(),
                    defaultValues,
                    read(who, () -> sql.fetchOne("select version from spu where id=?", spuId))
                        .get("version", Long.class))));
    return read(
            who,
            () ->
                sql.fetchOne(
                    "select id from attribute_definition where spu_id=? and code=?", spuId, code))
        .get("id", UUID.class);
  }

  UUID skuFor(AccessService.Access who, UUID spuId) {
    return write(
        who,
        () ->
            catalog.saveSku(
                who,
                spuId,
                null,
                new SkuInput(
                    "SKU-" + UUID.randomUUID().toString().substring(0, 8),
                    "B 的 SKU",
                    "",
                    "件",
                    "DRAFT",
                    List.of(),
                    read(who, () -> sql.fetchOne("select version from spu where id=?", spuId))
                        .get("version", Long.class))));
  }
}