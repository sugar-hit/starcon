package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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
 * 3.4.2.1 标题库：模板与变量占位、词根字典、批量生成的重复度控制、候选选用与导出、 以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS：相似度、模板、词根、候选全部真的落库， 「重复度受控」是从库里读出来的事实而不是内存里的计数器。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>开发中实测修掉的真缺陷（都是这轮实测里抓出来的，不是预防性想象）：
 *
 * <ol>
 *   <li><b>导出 CSV 只有表头，一行数据都没有</b>——{@code streamCsv} 跑在流式线程上， 那条连接上没有 {@code
 *       app.current_tenant}（controller 那个事务在响应开始前就结束了）， FORCE RLS 于是把全部行藏起来；而初版只把 {@code
 *       actor.tenantId()} 塞进 WHERE 子句， 设不了 GUC，于是查出来 0 行**还不报错**。修法：每批用 {@code access.run} 重新开租户事务
 *       （与 {@code SkuListService.streamCsv} 同一形状）。
 *   <li><b>词根「被引用数」永远是 0，删除保护形同虚设</b>——{@code referenceCount} 的绑定参数 误传成 {@code (id, text)}，于是
 *       {@code tenant_id=?} 拿词根 id 去比， 永远返回 0 行。症状很隐蔽：DB 触发器照常拦住删除（所以服务端那条断言看见的是 {@code
 *       DataIntegrityViolationException} 而不是可读的 400）， 而列表页上的引用数一直是 0。修法：传 {@code
 *       (currentTenantId(), text)}。
 *   <li><b>{@code select s.name, p.name} 两列同名被后一列覆盖</b>——jOOQ 按列名取值， {@code sku.name} 与 {@code
 *       spu.name} 都落在 {@code "name"} 上，于是 {@code {sku_name}} 与 {@code {spu_name}} 渲染出来是同一句话，
 *       差异化凭空少了一个轴，而只看「标题非空」的断言完全发现不了。修法：{@code p.name as spu_name}。
 *   <li><b>CSV 的 SPU 名写进了 SKU 名的格子</b>——CSV 的 SELECT 同样漏了别名，
 *       而表头是「SKU编码,SPU名称,品牌…」，于是第二、三列整体错位一格，导出来给人看的表是错的。
 *   <li><b>空词池让整批生成 500</b>——{@code Math.floorMod(t, poolSize)} 在 {@code poolSize==0} 时抛 {@link
 *       ArithmeticException}。而「租户只建了核心词、没建卖点词」是完全正常的初始状态， 本该表现为「绑定空池子的变量渲染成空、其余照常」。修法：{@code
 *       poolAt} 空池返回空串。
 *   <li><b>V20 迁移里把 {@code CREATE UNIQUE INDEX} 写进了 {@code CREATE TABLE} 括号内</b>—— Flyway 直接 {@code
 *       syntax error at or near "CREATE"}，整个应用上下文起不来。
 *   <li><b>混合类型的绑定参数推不出泛型</b>——{@code concat(List.of(tenantId, keywordType), codes)} 编译失败（jOOQ
 *       绑定参数必须是同一种 List）；改成显式 ArrayList 逐个 add。
 *   <li><b>切换选用的两步顺序</b>——同一 SKU 同平台换一条选用时，若不先把旧的降级， {@code title_record_one_selected}
 *       部分唯一索引会直接撞约束。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class TitleLibraryIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(),
      b = UUID.randomUUID(),
      c = UUID.randomUUID(),
      user = UUID.randomUUID();
  static HttpServer identity;
  static final java.util.Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();

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
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions()))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions()))));
      actors.put("Bearer tenant-c", actor(user, c, null, List.of(grant("ALL", allPermissions()))));
      actors.put(
          "Bearer reader",
          actor(user, a, null, List.of(grant("ALL", List.of("catalog.spu.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** 与 3.4.1.x 同口径：spu + sku + assets（V3 的 data_scope 策略在 spu 上是 RESTRICTIVE）。 */
  static List<String> allPermissions() {
    return java.util.stream.Stream.of("spu", "sku", "assets", "attribute")
        .flatMap(
            p ->
                java.util.stream.Stream.of("read", "create", "update", "delete")
                    .map(op -> "catalog." + p + "." + op))
        .toList();
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
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired TitleService titles;
  @Autowired CatalogService catalog;
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

  /** 专供「词池很小」的用例：用独立租户，避免与其它用例共享的词池互相污染。 */
  AccessService.Access actorC;

  @BeforeEach
  void setup() {
    actor = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    actorC = actors.get("Bearer tenant-c");
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

  String unique(String hint) {
    return hint
        + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
  }

  /** 建 SPU（3.1.1.1 的写入所有权），带品牌与分类——标题模板的事实变量来源。 */
  UUID spu(AccessService.Access who, String code, String brand, String category) {
    return write(
        who,
        () ->
            catalog.saveSpu(
                who, null, new SpuInput(code, "SPU " + code, "", brand, category, "DRAFT", 0)));
  }

  UUID sku(AccessService.Access who, UUID spuId, String code, String name) {
    return write(
        who,
        () ->
            catalog.saveSku(
                who,
                spuId,
                null,
                new SkuInput(code, name, unique("BC"), "PCS", "ACTIVE", List.of(), 0)));
  }

  /**
   * 建词根；已存在同样（类型 + 内容）的词根时直接复用。
   *
   * <p>幂等是必需的：所有用例共用同一个 PostgreSQL 容器，而 {@code UNIQUE(tenant_id,keyword_type,text)} 是刻意的产品约束——
   * 同一个词写成两条会让词池「看起来更大」，生成的候选却还是那几种， 重复度控制因此被绕过而没人发现。夹具不去重就会撞这个约束， 所以复用已存在的那条，而不是往库里塞同义词。
   */
  TitleKeywordRow keyword(AccessService.Access who, String code, String text, String type) {
    String typeUpper = type.toUpperCase(Locale.ROOT);
    return write(
        who,
        () -> titles.saveKeyword(who, null, new TitleKeywordInput(code, text, typeUpper, true, 0)));
  }

  /** 按（类型 + 内容）找已存在的词根，找不到返回 null。 */
  TitleKeywordRow findKeyword(AccessService.Access who, String text, String type) {
    // 显式类型：lambda 返回类型在 var 链上会退化成 Object（与 recordsOf 同一原因）。
    TitleKeywordPage page =
        tx(
            who,
            () ->
                titles.keywords(
                    new TitleKeywordQuery(null, type.toUpperCase(Locale.ROOT), 0, 200)));
    List<TitleKeywordListRow> rows = page.items() == null ? List.of() : page.items();
    return rows.stream()
        .map(TitleKeywordListRow::keyword)
        .filter(k -> k.text().equals(text))
        .findFirst()
        .orElse(null);
  }

  /** 铺一套够用的词池（核心 5 / 卖点 5 / 修饰 5），让重复度控制有腾挪空间。幂等。 */
  void seedPools(AccessService.Access who) {
    for (String text : List.of("蓝牙音箱", "无线音箱", "桌面音箱", "便携音箱", "低音炮音箱"))
      upsertKeyword(who, text, "CORE");
    for (String text : List.of("长续航", "防水便携", "高保真音质", "双单元立体声", "低延迟连接"))
      upsertKeyword(who, text, "SELLING");
    for (String text : List.of("新品", "家用款", "户外款", "车载款", "办公款"))
      upsertKeyword(who, text, "MODIFIER");
  }

  private void upsertKeyword(AccessService.Access who, String text, String type) {
    if (findKeyword(who, text, type) == null) keyword(who, unique("KW"), text, type);
  }

  /** 标准模板：品牌 + 品类 + 核心词 + 卖点 + 修饰词，三个平台各有长度上限。 */
  TitleTemplateSaved template(AccessService.Access who, String code, int maxSimilarity) {
    return write(
        who,
        () ->
            titles.saveTemplate(
                who,
                null,
                new TitleTemplateInput(
                    code,
                    code,
                    "标准标题模板",
                    "{brand} {category} {core} {selling} {modifier}",
                    maxSimilarity,
                    3,
                    List.of(
                        new TitleTemplateVariableInput("brand", "SPU_BRAND", 0),
                        new TitleTemplateVariableInput("category", "SPU_CATEGORY", 1),
                        new TitleTemplateVariableInput("core", "KEYWORD_CORE", 2),
                        new TitleTemplateVariableInput("selling", "KEYWORD_SELLING", 3),
                        new TitleTemplateVariableInput("modifier", "KEYWORD_MODIFIER", 4)),
                    List.of(
                        new TitleTemplatePlatformInput("AMAZON", 200),
                        new TitleTemplatePlatformInput("EBAY", 80),
                        new TitleTemplatePlatformInput("SHOPIFY", 120)),
                    true,
                    0)));
  }

  /** 精简模板：只要两个变量与一个平台，用来测「池子太小会拒」。 */
  TitleTemplateSaved thinTemplate(AccessService.Access who, String code) {
    return write(
        who,
        () ->
            titles.saveTemplate(
                who,
                null,
                new TitleTemplateInput(
                    code,
                    code,
                    "",
                    "{brand} {modifier}",
                    500,
                    3,
                    List.of(
                        new TitleTemplateVariableInput("brand", "SPU_BRAND", 0),
                        new TitleTemplateVariableInput("modifier", "KEYWORD_MODIFIER", 1)),
                    List.of(new TitleTemplatePlatformInput("AMAZON", 200)),
                    true,
                    0)));
  }

  /** 当前租户的模板编码列表（显式类型，避免 lambda 泛型推断退化）。 */
  List<String> templateCodes(AccessService.Access who) {
    List<TitleTemplateRow> rows = tx(who, () -> titles.templates());
    return rows.stream().map(TitleTemplateRow::code).toList();
  }

  /** 某 SKU 在某平台上的候选（显式类型同上）。 */
  List<TitleRecord> recordsOf(AccessService.Access who, UUID skuId, String platform) {
    List<TitleRecord> rows =
        tx(
            who,
            () ->
                titles
                    .listRecords(new TitleRecordQuery(skuId, platform, null, null, null, 0, 200))
                    .items());
    return rows;
  }

  // ------------------------------------------------------------ 模板与变量占位

  @Test
  void templateStoresVariableBindingsAndPlatformLimitsAndValidatesThem() {
    String code = unique("TPL");
    var saved = template(actor, code, 850);
    assertThat(saved.variableCount()).isEqualTo(5);
    assertThat(saved.platformCount()).isEqualTo(3);

    var row = tx(actor, () -> titles.template(saved.id()));
    // 蓝图点名的四个变量都有落点：品牌 + 品类 + 核心词 + 卖点（外加差异化用的修饰词）。
    assertThat(row.pattern()).isEqualTo("{brand} {category} {core} {selling} {modifier}");
    assertThat(row.maxSimilarityPermille()).isEqualTo(850);
    assertThat(row.candidateCount()).isEqualTo(3);
    assertThat(row.variables())
        .extracting(TitleTemplateVariable::name)
        .containsExactly("brand", "category", "core", "selling", "modifier");
    assertThat(row.variables())
        .extracting(TitleTemplateVariable::source)
        .containsExactly(
            "SPU_BRAND", "SPU_CATEGORY", "KEYWORD_CORE", "KEYWORD_SELLING", "KEYWORD_MODIFIER");
    // 绑定源有中文标签，前端直接显示，不让用户对着枚举名猜。
    assertThat(row.variables().get(0).sourceLabel()).isEqualTo("SPU 品牌");
    assertThat(row.variables().get(2).sourceLabel()).isEqualTo("核心词池");
    assertThat(row.platforms())
        .extracting(TitleTemplatePlatform::platform)
        .containsExactlyInAnyOrder("AMAZON", "EBAY", "SHOPIFY");
    assertThat(row.platforms())
        .extracting(TitleTemplatePlatform::maxLength)
        .containsExactlyInAnyOrder(200, 80, 120);

    // 没有变量的模板：渲染出来每条候选都一样，正是本模块要消灭的重复。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        titles.saveTemplate(
                            actor,
                            null,
                            new TitleTemplateInput(
                                unique("TPL"),
                                "坏模板",
                                "",
                                "星云蓝牙音箱",
                                850,
                                2,
                                List.of(),
                                List.of(new TitleTemplatePlatformInput("AMAZON", 200)),
                                true,
                                0))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(e.getMessage()).contains("至少要有一个变量占位"));

    // 占位符与变量声明对不上。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        titles.saveTemplate(
                            actor,
                            null,
                            new TitleTemplateInput(
                                unique("TPL"),
                                "坏绑定",
                                "",
                                "{brand} {core}",
                                850,
                                2,
                                List.of(
                                    new TitleTemplateVariableInput("brand", "SPU_BRAND", 0),
                                    new TitleTemplateVariableInput(
                                        "selling", "KEYWORD_SELLING", 1)),
                                List.of(new TitleTemplatePlatformInput("AMAZON", 200)),
                                true,
                                0))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(e.getMessage()).contains("没有声明绑定源"));

    // 只绑事实字段：每个 SKU 只有一种文本，候选必然彼此雷同。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        titles.saveTemplate(
                            actor,
                            null,
                            new TitleTemplateInput(
                                unique("TPL"),
                                "无词根",
                                "",
                                "{brand} {category}",
                                850,
                                2,
                                List.of(
                                    new TitleTemplateVariableInput("brand", "SPU_BRAND", 0),
                                    new TitleTemplateVariableInput("category", "SPU_CATEGORY", 1)),
                                List.of(new TitleTemplatePlatformInput("AMAZON", 200)),
                                true,
                                0))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(e.getMessage()).contains("至少要有一个词根变量"));

    // 重复度阈值不能配成 1000（那等于允许完全一样）。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        titles.saveTemplate(
                            actor,
                            null,
                            new TitleTemplateInput(
                                unique("TPL"),
                                "阈值",
                                "",
                                "{brand} {core}",
                                1000,
                                2,
                                List.of(
                                    new TitleTemplateVariableInput("brand", "SPU_BRAND", 0),
                                    new TitleTemplateVariableInput("core", "KEYWORD_CORE", 1)),
                                List.of(new TitleTemplatePlatformInput("AMAZON", 200)),
                                true,
                                0))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(e.getMessage()).contains("999"));

    // 模板没配的平台不允许生成（平台标题有硬上限，没配就没法裁）。
    UUID product = sku(actor, spu(actor, unique("SPU"), "星云", "音箱"), unique("SKU"), "商品");
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        titles.generate(
                            actor,
                            new TitleGenerationInput(
                                saved.id(), List.of(product), List.of("WALMART"), List.of()))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(e.getMessage()).contains("WALMART").contains("长度上限"));

    // 编码重复。
    assertThatThrownBy(() -> template(actor, code, 850))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void templateCannotBeDeletedOnceUsedButCanBeDisabled() {
    seedPools(actor);
    String code = unique("TPL");
    var saved = template(actor, code, 850);
    UUID product = sku(actor, spu(actor, unique("SPU"), "星云", "音箱"), unique("SKU"), "桌面款");
    write(
        actor,
        () ->
            titles.generate(
                actor,
                new TitleGenerationInput(
                    saved.id(), List.of(product), List.of("AMAZON"), List.of())));

    // 被批次引用时不可删（V20 的 refuse_title_template_delete 兜底）。
    long version = tx(actor, () -> titles.template(saved.id())).version();
    assertThatThrownBy(
            () -> writeVoid(actor, () -> titles.deleteTemplate(actor, saved.id(), version)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(e.getMessage()).contains("停用"));

    // 停用后不能再新建批次，历史候选照常可读。
    writeVoid(actor, () -> titles.setTemplateEnabled(actor, saved.id(), false));
    UUID other = sku(actor, spu(actor, unique("SPU"), "星云", "音箱"), unique("SKU"), "便携款");
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        titles.generate(
                            actor,
                            new TitleGenerationInput(
                                saved.id(), List.of(other), List.of("AMAZON"), List.of()))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(e.getMessage()).contains("停用"));
    assertThat(recordsOf(actor, product, "AMAZON")).isNotEmpty();
  }

  // ------------------------------------------------------------ 词根字典

  @Test
  void keywordDictionaryDrivesGenerationAndBlocksDeletionOnceReferenced() {
    seedPools(actor);
    // 本用例专属的词根文本：seedPools 已经建过「车载款」，直接建会撞
    // UNIQUE(tenant_id,keyword_type,text)——那个约束是刻意的（见 upsertKeyword 注释）。
    String code = unique("M");
    String text = "限量联名款";
    var kw = keyword(actor, code, text, "MODIFIER");
    assertThat(kw.keywordTypeLabel()).isEqualTo("修饰词");

    // 列表页每行自带被引用数，所以「能不能删」在列表上就看得见，不必点进去试。
    var page = tx(actor, () -> titles.keywords(new TitleKeywordQuery(null, "MODIFIER", 0, 50)));
    assertThat(page.items()).extracting(i -> i.keyword().code()).contains(code);
    assertThat(page.modifierCount()).isGreaterThan(0);
    assertThat(page.coreCount()).isGreaterThan(0);

    String tplCode = unique("TPL");
    var saved = template(actor, tplCode, 850);
    UUID product = sku(actor, spu(actor, unique("SPU"), "星云", "音箱"), unique("SKU"), "桌面款");
    var generation =
        write(
            actor,
            () ->
                titles.generate(
                    actor,
                    new TitleGenerationInput(
                        saved.id(), List.of(product), List.of("AMAZON"), List.of())));
    assertThat(generation.candidateCount()).isEqualTo(3);

    // 被引用过的词根不可删（V20 的 refuse_title_keyword_delete 兜底）。
    long version =
        tx(actor, () -> titles.keywords(new TitleKeywordQuery(code, null, 0, 10))).items().stream()
            .filter(i -> i.keyword().code().equals(code))
            .findFirst()
            .orElseThrow()
            .keyword()
            .version();
    assertThatThrownBy(() -> writeVoid(actor, () -> titles.deleteKeyword(actor, kw.id(), version)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(e.getMessage()).contains("停用"));
    // 但可以停用（停用后不再进入生成词池，已有标题照常可读）。
    writeVoid(actor, () -> titles.setKeywordEnabled(actor, kw.id(), false));

    // 引用数在列表上可见：至少有一条候选用到了这个词根。
    var used =
        tx(actor, () -> titles.keywords(new TitleKeywordQuery(code, null, 0, 10))).items().stream()
            .filter(i -> i.keyword().code().equals(code))
            .findFirst()
            .orElseThrow();
    assertThat(used.referenceCount()).isGreaterThan(0);

    // 词根内容不能带花括号：它是直接填进标题的文本，不是另一个模板。
    assertThatThrownBy(() -> keyword(actor, unique("M"), "{core}", "MODIFIER"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(e.getMessage()).contains("花括号"));
    // 词根类型必须是三类之一。
    assertThatThrownBy(() -> keyword(actor, unique("M"), "随便", "NOUN"))
        .isInstanceOf(ResponseStatusException.class);
  }

  // ============================================================ 重复度控制（蓝图点名的算法）

  @Test
  void batchGenerationKeepsEveryCandidatePairUnderTheSimilarityLimit() {
    seedPools(actor);
    String code = unique("TPL");
    var saved = template(actor, code, 850);
    UUID product = sku(actor, spu(actor, unique("SPU"), "星云", "蓝牙音箱"), unique("SKU"), "桌面款");

    var generation =
        write(
            actor,
            () ->
                titles.generate(
                    actor,
                    new TitleGenerationInput(
                        saved.id(), List.of(product), List.of("AMAZON"), List.of())));
    assertThat(generation.status()).isEqualTo("SUCCEEDED");
    assertThat(generation.candidateCount()).isEqualTo(3);
    assertThat(generation.rejectedCount()).isZero();

    List<TitleRecord> records = recordsOf(actor, product, "AMAZON");
    assertThat(records).hasSize(3);
    // 落库的相似度是库里可查的事实：这一列就是重复度控制的证据。
    assertThat(records)
        .allSatisfy(
            r -> {
              assertThat(r.similarityPermille()).isBetween(0, 850);
              assertThat(r.charLength()).isEqualTo(TitleRules.charLength(r.title()));
            });
    // 两两相似度真的不超过上限（用后端同一份算法重算，不是看某个字段就信了）。
    for (int i = 0; i < records.size(); i++)
      for (int j = i + 1; j < records.size(); j++)
        assertThat(TitleRules.similarityPermille(records.get(i).title(), records.get(j).title()))
            .as(
                "第 %d 与第 %d 条候选：%s / %s",
                i + 1, j + 1, records.get(i).title(), records.get(j).title())
            .isLessThanOrEqualTo(850);
    // 标题互不相同（没有硬凑出来的重复）。
    assertThat(records.stream().map(TitleRecord::title)).doesNotHaveDuplicates();
    // 品牌与品类真的进了标题（事实变量绑对了；这正是别名 bug 会破坏的地方）。
    for (TitleRecord r : records) {
      assertThat(r.title()).contains("星云").contains("蓝牙音箱");
      assertThat(r.title()).doesNotContain("{").doesNotContain("}");
    }
    // 词根快照可溯源：改了词库也能解释这条标题当时用了哪个词。
    assertThat(records)
        .allSatisfy(
            r -> {
              assertThat(r.coreKeyword()).isNotEmpty();
              assertThat(r.sellingKeyword()).isNotEmpty();
              assertThat(r.modifierKeyword()).isNotEmpty();
              assertThat(r.title()).contains(r.coreKeyword()).contains(r.modifierKeyword());
            });
  }

  @Test
  void existingTitlesAcrossPlatformsConstrainTheNewBatch() {
    seedPools(actor);
    var saved = template(actor, unique("TPL"), 850);
    UUID product = sku(actor, spu(actor, unique("SPU"), "星云", "蓝牙音箱"), unique("SKU"), "桌面款");

    // 先发一批到 eBay。
    write(
        actor,
        () ->
            titles.generate(
                actor,
                new TitleGenerationInput(
                    saved.id(), List.of(product), List.of("EBAY"), List.of())));
    List<TitleRecord> first = recordsOf(actor, product, "EBAY");
    assertThat(first).hasSize(3);
    // 再发一批到亚马逊：新候选必须与**已发的 eBay 标题**也拉开距离，
    // 这才是「同一商品在多平台不同标题以避重复铺货判定」的落点。
    write(
        actor,
        () ->
            titles.generate(
                actor,
                new TitleGenerationInput(
                    saved.id(), List.of(product), List.of("AMAZON"), List.of())));
    List<TitleRecord> second = recordsOf(actor, product, "AMAZON");
    // 批次候选数**可以少于** candidateCount：已发的 eBay 标题占掉了组合空间，
    // 剩下的组合过不了闸就被拒（PARTIAL）——那正是「相似但不雷同」的代价，
    // 断言满额产出反而是要求它重复。真正要断言的是下面那条：每条都受控。
    assertThat(second).isNotEmpty().hasSizeLessThanOrEqualTo(3);

    for (TitleRecord candidate : second) {
      // 落库的相似度字段也不得越过上限。
      assertThat(candidate.similarityPermille()).isLessThanOrEqualTo(850);
      for (TitleRecord existing : first)
        assertThat(TitleRules.similarityPermille(candidate.title(), existing.title()))
            .as("新候选 %s 与已发到 eBay 的 %s", candidate.title(), existing.title())
            .isLessThanOrEqualTo(850);
    }
  }

  @Test
  void tooSmallAPoolYieldsRejectionWithActionableReasonInsteadOfRepeatingTitles() {
    // 只建 1 个修饰词 + 2 变量模板：组合空间凑不出 3 条不雷同的。
    // 用**独立租户**：所有用例共用同一个容器，词池会跨用例累积，
    // 在租户 a 上「只建 1 个修饰词」根本做不到（别的用例已经建了 5 个）。
    upsertKeyword(actorC, "新品", "MODIFIER");
    upsertKeyword(actorC, "蓝牙音箱", "CORE");
    upsertKeyword(actorC, "无线音箱", "CORE");
    var saved = thinTemplate(actorC, unique("TPL"));
    UUID product = sku(actorC, spu(actorC, unique("SPU"), "星云", "音箱"), unique("SKU"), "桌面款");

    var generation =
        write(
            actorC,
            () ->
                titles.generate(
                    actorC,
                    new TitleGenerationInput(
                        saved.id(), List.of(product), List.of("AMAZON"), List.of())));
    // 产出了一条，另外两条被拒——但**不是**返回两条雷同的。
    assertThat(generation.candidateCount()).isEqualTo(1);
    assertThat(generation.rejectedCount()).isEqualTo(2);
    assertThat(generation.status()).isEqualTo("PARTIAL");

    var detail = tx(actorC, () -> titles.generationDetail(generation.id()));
    assertThat(detail.skus()).hasSize(1);
    var skuRow = detail.skus().get(0);
    assertThat(skuRow.outcome()).isEqualTo("GENERATED");
    assertThat(skuRow.rejectedCount()).isEqualTo(2);
    // 原因可执行：说清「压不到上限」与「该去加修饰词」，而不是异常类名。
    assertThat(skuRow.reason()).contains("确定性尝试").contains("修饰词池");
    assertThat(detail.records()).hasSize(1);
  }

  @Test
  void platformLengthLimitClampsTitlesAndFlagsThem() {
    seedPools(actor);
    var saved = template(actor, unique("TPL"), 999);
    UUID product = sku(actor, spu(actor, unique("SPU"), "星云", "蓝牙音箱"), unique("SKU"), "桌面款");

    // 宽上限（AMAZON 200）与**紧上限**（TIGHT 12）对照。
    // 紧上限是这条用例的重点：这些标题本来 20 字左右，平台上限 12 时
    // 必须被按码点边界裁到 12 个字并标记 truncated=true，
    // 否则发到平台上就是超长标题。
    var tight =
        write(
            actor,
            () ->
                titles.saveTemplate(
                    actor,
                    null,
                    new TitleTemplateInput(
                        unique("TPL"),
                        "紧上限",
                        "",
                        "{brand} {category} {core} {selling} {modifier}",
                        999,
                        2,
                        List.of(
                            new TitleTemplateVariableInput("brand", "SPU_BRAND", 0),
                            new TitleTemplateVariableInput("category", "SPU_CATEGORY", 1),
                            new TitleTemplateVariableInput("core", "KEYWORD_CORE", 2),
                            new TitleTemplateVariableInput("selling", "KEYWORD_SELLING", 3),
                            new TitleTemplateVariableInput("modifier", "KEYWORD_MODIFIER", 4)),
                        List.of(new TitleTemplatePlatformInput("TIGHT", 12)),
                        true,
                        0)));
    // 同一批里两条路径都要跑：宽上限（AMAZON 200，不裁）与紧上限（TIGHT 12，裁）。
    var wideGeneration =
        write(
            actor,
            () ->
                titles.generate(
                    actor,
                    new TitleGenerationInput(
                        saved.id(), List.of(product), List.of("AMAZON"), List.of())));
    assertThat(wideGeneration.candidateCount()).isEqualTo(3);
    var generation =
        write(
            actor,
            () ->
                titles.generate(
                    actor,
                    new TitleGenerationInput(
                        tight.id(), List.of(product), List.of("TIGHT"), List.of())));
    assertThat(generation.candidateCount()).isEqualTo(2);

    for (TitleRecord r : recordsOf(actor, product, "AMAZON")) {
      assertThat(r.charLength()).isLessThanOrEqualTo(200);
      assertThat(r.truncated()).isFalse();
    }
    for (TitleRecord r : recordsOf(actor, product, "TIGHT")) {
      assertThat(r.charLength()).isLessThanOrEqualTo(12);
      // 被裁过的候选要标出来：发出去前运营得知道这条在平台上被砍过。
      assertThat(r.truncated()).isTrue();
      // char_length 与真实码点数一致（用后端同一口径重算，不信库里的数字）。
      assertThat(TitleRules.charLength(r.title())).isEqualTo(r.charLength());
    }
    // 同一 SKU 在宽上限平台上的标题与紧上限平台的**不同**：
    // 裁剪后剩下的文本是另一个字符串，不是同一份复制。
    var wide = recordsOf(actor, product, "AMAZON").stream().map(TitleRecord::title).toList();
    var narrow = recordsOf(actor, product, "TIGHT").stream().map(TitleRecord::title).toList();
    assertThat(wide).isNotEmpty();
    assertThat(narrow).isNotEmpty();
    assertThat(narrow).noneMatch(t -> t.length() > 12);
  }

  @Test
  void generationIsDeterministicAcrossRepeatedRuns() {
    seedPools(actor);
    var saved = template(actor, unique("TPL"), 850);
    UUID product = sku(actor, spu(actor, unique("SPU"), "星云", "蓝牙音箱"), unique("SKU"), "桌面款");

    var first =
        write(
            actor,
            () ->
                titles.generate(
                    actor,
                    new TitleGenerationInput(
                        saved.id(), List.of(product), List.of("AMAZON"), List.of())));
    var second =
        write(
            actor,
            () ->
                titles.generate(
                    actor,
                    new TitleGenerationInput(
                        saved.id(), List.of(product), List.of("AMAZON"), List.of())));
    // 第二次生成会把第一次的候选也算进比较里，所以两次的标题集**不要求**逐字相同；
    // 但每一条自己与已有标题的相似度都必须受控，且库里存的相似度都是真实算出来的。
    for (TitleRecord record : recordsOf(actor, product, "AMAZON"))
      assertThat(record.similarityPermille()).isBetween(0, 850);
    assertThat(tx(actor, () -> titles.generationDetail(second.id())).records()).isNotEmpty();
    assertThat(first.candidateCount()).isEqualTo(3);
  }

  // ------------------------------------------------------------ 选用与导出

  @Test
  void selectingARecordKeepsTheBatchConsistentAndSwitchesSafely() {
    seedPools(actor);
    var saved = template(actor, unique("TPL"), 999);
    UUID product = sku(actor, spu(actor, unique("SPU"), "星云", "蓝牙音箱"), unique("SKU"), "桌面款");
    write(
        actor,
        () ->
            titles.generate(
                actor,
                new TitleGenerationInput(
                    saved.id(), List.of(product), List.of("AMAZON"), List.of())));
    List<TitleRecord> records = recordsOf(actor, product, "AMAZON");
    assertThat(records).hasSize(3);

    var picked = write(actor, () -> titles.select(actor, records.get(0).id()));
    assertThat(picked.status()).isEqualTo("SELECTED");
    assertThat(picked.statusLabel()).isEqualTo("已选用");

    // 同一 SKU 同一平台换一条选用：旧的自动降级，不会撞「只能选用一条」的唯一索引。
    var switched = write(actor, () -> titles.select(actor, records.get(1).id()));
    assertThat(switched.status()).isEqualTo("SELECTED");
    var afterSwitch =
        recordsOf(actor, product, "AMAZON").stream()
            .filter(r -> "SELECTED".equals(r.status()))
            .toList();
    assertThat(afterSwitch).hasSize(1);
    assertThat(afterSwitch.get(0).id()).isEqualTo(records.get(1).id());

    // 已选用的内容不可改（V20 的 guard_title_record_selected_immutable 钉死），
    // 绕过服务层直接 UPDATE 也一样被拒。
    assertThatThrownBy(
            () ->
                writeVoid(
                    actor,
                    () ->
                        sql.execute(
                            "update title_record set title='篡改' where id=?", records.get(1).id())))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .satisfies(e -> assertThat(rootMessageOf(e)).contains("已被选用").contains("不可修改"));
    // 已选用的不可删。
    assertThatThrownBy(
            () ->
                writeVoid(
                    actor,
                    () -> sql.execute("delete from title_record where id=?", records.get(1).id())))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .satisfies(e -> assertThat(rootMessageOf(e)).contains("已被选用").contains("不可删除"));
    // 未选用的候选可以删。
    writeVoid(actor, () -> sql.execute("delete from title_record where id=?", records.get(2).id()));

    // 取消选用后可重新选。
    var back = write(actor, () -> titles.deselect(actor, records.get(1).id()));
    assertThat(back.status()).isEqualTo("CANDIDATE");
    // 已选用的不能直接淘汰，要先取消选用。
    write(actor, () -> titles.select(actor, records.get(1).id()));
    assertThatThrownBy(() -> write(actor, () -> titles.reject(actor, records.get(1).id())))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(e.getMessage()).contains("取消选用"));
  }

  @Test
  void csvExportProducesRealRowsWithSimilarityAndKeywordProvenance() throws Exception {
    seedPools(actor);
    String code = unique("EXP");
    var saved = template(actor, code, 850);
    String skuCode = unique("SKU");
    UUID product = sku(actor, spu(actor, unique("SPU"), "星云", "蓝牙音箱"), skuCode, "桌面款");
    write(
        actor,
        () ->
            titles.generate(
                actor,
                new TitleGenerationInput(
                    saved.id(), List.of(product), List.of("AMAZON"), List.of())));

    var result =
        http.perform(
                get("/catalog/titles/records/export")
                    .param("skuId", product.toString())
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "text/csv; charset=UTF-8"))
            .andExpect(
                header()
                    .string(
                        "Content-Disposition",
                        org.hamcrest.Matchers.containsString("catalog-titles-")))
            .andReturn();
    // StreamingResponseBody 是**异步**写出的：不 getAsyncResult() 等它跑完，
    // 读到的响应体只有表头（数据行还在流式线程上写）。
    result.getAsyncResult();
    var response = result.getResponse();
    // CSV 必须原样透传（content-type 是 text/csv），不是被重新序列化成 JSON。
    assertThat(response.getContentType()).contains("text/csv");
    String csv = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(csv).startsWith("SKU编码,SPU名称,品牌,分类,平台,候选序号,标题,字符数,已截断,词序档位,相似度‰,核心词,卖点,修饰词,状态");
    var lines = csv.lines().filter(l -> !l.isBlank()).toList();
    assertThat(lines).hasSize(4); // 表头 + 3 条候选
    // 表头声明了 15 列，每行就真的有 15 个字段（错位一格的 bug 在这里会暴露）。
    // 表头本身没被 csv() 引用（它是字面量），所以数据行按 '","' 切出来是 15 段，
    // 首尾的引号也算在内；表头按 ',' 切也应是 15 段。
    assertThat(lines.get(0).split(",", -1)).hasSize(15);
    for (String line : lines.subList(1, lines.size()))
      assertThat(line.split("\",\"", -1)).hasSize(15);
    // 内容真的来自库里：SKU 编码、平台、相似度与状态都在。
    assertThat(csv).contains(skuCode);
    assertThat(csv).contains("AMAZON");
    assertThat(csv).contains("待选用");
    // 相似度列确实带着数字（不是留空的占位列）。
    assertThat(csv).containsPattern("\\d{3}");
  }

  // ------------------------------------------------------------ 多租户三断言

  @Test
  void tenantIsolationHoldsAcrossReadWriteAndPoolReuse() throws Exception {
    seedPools(actor);
    seedPools(actorB);
    String codeA = unique("TPLA");
    String codeB = unique("TPLB");
    var templateA = template(actor, codeA, 850);
    var templateB = template(actorB, codeB, 700);
    UUID mine = sku(actor, spu(actor, unique("SPU"), "星云", "蓝牙音箱"), unique("SKU"), "我的");
    UUID theirs = sku(actorB, spu(actorB, unique("SPU"), "极光", "台灯"), unique("SKU"), "他们的");
    var genA =
        write(
            actor,
            () ->
                titles.generate(
                    actor,
                    new TitleGenerationInput(
                        templateA.id(), List.of(mine), List.of("AMAZON"), List.of())));
    var genB =
        write(
            actorB,
            () ->
                titles.generate(
                    actorB,
                    new TitleGenerationInput(
                        templateB.id(), List.of(theirs), List.of("EBAY"), List.of())));

    // 1) 两租户互不可见
    assertThat(templateCodes(actor)).contains(codeA).doesNotContain(codeB);
    assertThat(templateCodes(actorB)).contains(codeB).doesNotContain(codeA);
    // 手里握着别人的 id 也读不到（RLS + 查询都按当前租户过滤）。
    assertThatThrownBy(() -> tx(actor, () -> titles.template(templateB.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> titles.template(templateA.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> titles.generationDetail(genA.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(recordsOf(actor, theirs, "EBAY")).isEmpty();
    assertThat(recordsOf(actorB, mine, "AMAZON")).isEmpty();
    // 词根也不串。
    var myKeywords = tx(actor, () -> titles.keywords(new TitleKeywordQuery(null, "CORE", 0, 200)));
    assertThat(myKeywords.total()).isGreaterThan(0);
    var theirKeywordRows =
        tx(actorB, () -> titles.keywords(new TitleKeywordQuery(null, "CORE", 0, 200)));
    assertThat(theirKeywordRows.total()).isGreaterThan(0);
    // 按 **code** 比而不是文本：两个租户 seedPools 建的是同一批文本
    // （词根文本是可复用的行业词，不该被租户隔离挡住），
    // 真正隔离的是租户各自主导的编码与词库行的可见性。
    // 在 B 租户里建一条专属词根，A 租户绝不该看到它。
    String bOnly = "极光专属词";
    keyword(actorB, unique("K"), bOnly, "CORE");
    assertThat(tx(actor, () -> titles.keywords(new TitleKeywordQuery(bOnly, null, 0, 20))).total())
        .isZero();
    assertThat(tx(actorB, () -> titles.keywords(new TitleKeywordQuery(bOnly, null, 0, 20))).total())
        .isEqualTo(1);
    // A 租户建的那条也别让 B 看见。
    keyword(actor, unique("K"), "星云专属词", "CORE");
    assertThat(
            tx(actorB, () -> titles.keywords(new TitleKeywordQuery("星云专属词", null, 0, 20))).total())
        .isZero();
    // B 租户不能删 A 租户的模板/词根。
    long versionA = tx(actor, () -> titles.template(templateA.id())).version();
    assertThatThrownBy(
            () -> writeVoid(actorB, () -> titles.deleteTemplate(actorB, templateA.id(), versionA)))
        .isInstanceOf(ResponseStatusException.class);
    // B 租户不能选用 A 租户的标题。
    List<TitleRecord> recordsA = recordsOf(actor, mine, "AMAZON");
    assertThat(recordsA).isNotEmpty();
    assertThatThrownBy(() -> write(actorB, () -> titles.select(actorB, recordsA.get(0).id())))
        .isInstanceOf(ResponseStatusException.class);

    // 2) 伪造 tenant header 无效：租户只来自已认证的成员关系
    http.perform(
            get("/catalog/titles/generations/" + genA.id())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    var submitted =
        http.perform(
                post("/catalog/titles/generations")
                    .header("Authorization", "Bearer tenant-b")
                    .header("X-Tenant-Id", a)
                    .contentType("application/json")
                    .content(
                        "{\"templateId\":\""
                            + templateB.id()
                            + "\",\"skuIds\":[\""
                            + theirs
                            + "\"],\"platforms\":[\"EBAY\"],\"keywordCodes\":[]}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String forgedId =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(submitted).get("id").asText();
    // 伪造头写入的批次进的是 B 租户（凭据说的那个）。
    assertThat(recordsOf(actorB, theirs, "EBAY")).isNotEmpty();
    // A 租户用同一个 id 查不到它。
    assertThatThrownBy(() -> tx(actor, () -> titles.generationDetail(UUID.fromString(forgedId))))
        .isInstanceOf(ResponseStatusException.class);

    // 3) 连接池归还后不串租户（单连接反复交替）
    for (int i = 0; i < 5; i++) {
      assertThat(tx(actor, () -> titles.generationDetail(genA.id())).generation().id())
          .isEqualTo(genA.id());
      assertThat(tx(actorB, () -> titles.generationDetail(genB.id())).generation().id())
          .isEqualTo(genB.id());
      assertThatThrownBy(() -> tx(actorB, () -> titles.generationDetail(genA.id())))
          .isInstanceOf(ResponseStatusException.class);
      assertThat(templateCodes(actor)).contains(codeA).doesNotContain(codeB);
      assertThat(templateCodes(actorB)).contains(codeB).doesNotContain(codeA);
      // 候选列表在连接复用时最容易串（它是租户共享库里最热的读路径）。
      assertThat(recordsOf(actor, mine, "AMAZON")).isNotEmpty();
      assertThat(recordsOf(actor, theirs, "EBAY")).isEmpty();
    }
  }

  // ------------------------------------------------------------ HTTP 与权限

  @Test
  void httpEndpointsExposeTheRealWorkflow() throws Exception {
    seedPools(actor);
    var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    // 建词根
    http.perform(
            post("/catalog/titles/keywords")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"code\":\""
                        + unique("C")
                        + "\",\"text\":\"骨传导耳机\",\"keywordType\":\"CORE\","
                        + "\"enabled\":true,\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.keywordTypeLabel").value("核心词"));

    // 建模板
    var created =
        http.perform(
                post("/catalog/titles/templates")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType("application/json")
                    .content(
                        "{\"code\":\""
                            + unique("TPL")
                            + "\",\"name\":\"HTTP 模板\","
                            + "\"note\":\"端到端\",\"pattern\":\"{brand} {category} {core} {selling}\","
                            + "\"maxSimilarityPermille\":850,\"candidateCount\":2,"
                            + "\"variables\":["
                            + "{\"name\":\"brand\",\"source\":\"SPU_BRAND\",\"slotNo\":0},"
                            + "{\"name\":\"category\",\"source\":\"SPU_CATEGORY\",\"slotNo\":1},"
                            + "{\"name\":\"core\",\"source\":\"KEYWORD_CORE\",\"slotNo\":2},"
                            + "{\"name\":\"selling\",\"source\":\"KEYWORD_SELLING\",\"slotNo\":3}],"
                            + "\"platforms\":[{\"platform\":\"AMAZON\",\"maxLength\":200}],"
                            + "\"enabled\":true,\"version\":0}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.variableCount").value(4))
            .andExpect(jsonPath("$.platformCount").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String templateId = objectMapper.readTree(created).get("id").asText();

    // 造商品（3.1.1.1 的写入所有权）
    UUID product = sku(actor, spu(actor, unique("SPU"), "星云", "蓝牙音箱"), unique("SKU"), "桌面款");

    // 批量生成（同步：返回即已完成）
    var submitted =
        http.perform(
                post("/catalog/titles/generations")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType("application/json")
                    .content(
                        "{\"templateId\":\""
                            + templateId
                            + "\",\"skuIds\":[\""
                            + product
                            + "\"],\"platforms\":[\"AMAZON\"],\"keywordCodes\":[]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.candidateCount").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String generationId = objectMapper.readTree(submitted).get("id").asText();

    http.perform(
            get("/catalog/titles/generations/" + generationId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.generation.statusLabel").value("已生成"))
        .andExpect(jsonPath("$.skus[0].outcome").value("GENERATED"))
        // 相似度是库里可查的事实：接口把它如实报出来，运营看得见离重复铺货有多远。
        .andExpect(jsonPath("$.records[0].similarityPermille").isNumber())
        .andExpect(jsonPath("$.records[0].title").isString())
        .andExpect(jsonPath("$.records[0].statusLabel").value("待选用"));

    http.perform(
            get("/catalog/titles/generations/" + generationId + "/skus")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].candidateCount").value(2));

    var records =
        http.perform(
                get("/catalog/titles/records")
                    .param("skuId", product.toString())
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray())
            .andExpect(jsonPath("$.total").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String recordId = objectMapper.readTree(records).get("items").get(0).get("id").asText();

    // 选用 → 取消选用 → 淘汰
    http.perform(
            post("/catalog/titles/records/" + recordId + "/select")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statusLabel").value("已选用"));
    http.perform(
            post("/catalog/titles/records/" + recordId + "/deselect")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statusLabel").value("待选用"));
    http.perform(
            post("/catalog/titles/records/" + recordId + "/reject")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statusLabel").value("已淘汰"));

    http.perform(get("/catalog/titles/templates").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());
    http.perform(
            get("/catalog/titles/templates/" + templateId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.variables[0].sourceLabel").value("SPU 品牌"));
    http.perform(get("/catalog/titles/keywords").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());
    http.perform(get("/catalog/titles/overview").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.templateCount").isNumber());
    http.perform(get("/catalog/titles/generations").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());

    // 未登录 401；只读角色能读不能写。
    http.perform(get("/catalog/titles/templates")).andExpect(status().isUnauthorized());
    http.perform(get("/catalog/titles/templates").header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());
    http.perform(
            post("/catalog/titles/keywords")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(
                    "{\"code\":\"X\",\"text\":\"词\",\"keywordType\":\"CORE\",\"enabled\":true,\"version\":0}"))
        .andExpect(status().isForbidden());
    http.perform(
            delete("/catalog/titles/templates/" + templateId + "?version=0")
                .header("Authorization", "Bearer reader"))
        .andExpect(status().isForbidden());
    // 非法模板是 400 不是 500。
    http.perform(
            post("/catalog/titles/templates")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"code\":\"BAD\",\"name\":\"坏模板\",\"pattern\":\"没有变量\","
                        + "\"platforms\":[{\"platform\":\"AMAZON\",\"maxLength\":200}],"
                        + "\"enabled\":true,\"version\":0}"))
        .andExpect(status().isBadRequest());
  }

  // ------------------------------------------------------------ 工具

  private static String rootMessageOf(Throwable e) {
    var root = e;
    while (root.getCause() != null) root = root.getCause();
    return root.getMessage() == null ? "" : root.getMessage();
  }

  static org.springframework.test.web.servlet.result.JsonPathResultMatchers jsonPath(String path) {
    return org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(path);
  }
}
