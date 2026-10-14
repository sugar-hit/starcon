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
 * 3.4.2.2 描述与卖点库：模板与变量占位、多语言译文、卖点短语库、批量填充、 **变量缺失报告**、CSV 导出，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS：模板、译文、短语、段落与缺失报告全部真的落库， 「变量缺失已被报告」是从库里读出来的事实而不是内存里的返回值。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本文件最重要的断言是 {@link #missingVariablesAreReportedAndNeverLeftAsLiteralPlaceholders()}：</b>
 * 它逐条验证蓝图硬要求「变量缺失必须在填充时报告，不能静默留下占位符」—— 正文里不残留 {@code {brand}}、段落被标成缺变量、缺失逐条落到 {@code
 * content_render_issue}，并且从 DB 层直接改状态也改不成「已填好」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DescriptionLibraryIntegrationTest {
  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
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
      try (var conn =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = conn.createStatement()) {
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

  @Autowired DescriptionService descriptions;
  @Autowired DescriptionRenderService renders;
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

  /** 专供「短语池为空」的用例：用独立租户，避免与其它用例共享的短语池互相污染。 */
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

  /** 建 SPU（3.1.1.1 的写入所有权），带品牌与分类——描述模板的事实变量来源。 */
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
   * 建短语；已存在同样（类型 + 内容）的短语时直接复用。
   *
   * <p>幂等是必需的：所有用例共用同一个 PostgreSQL 容器，而 {@code UNIQUE(tenant_id,phrase_type,text)} 是刻意的产品约束——
   * 同一句卖点写成两条会让短语池「看起来更大」，填充出来的描述却还是那几种。
   */
  ContentPhraseRow phrase(AccessService.Access who, String code, String text, String type) {
    String typeUpper = type.toUpperCase(Locale.ROOT);
    return write(
        who,
        () ->
            descriptions.savePhrase(
                who, null, new ContentPhraseInput(code, text, typeUpper, true, 0)));
  }

  ContentPhraseRow findPhrase(AccessService.Access who, String text, String type) {
    ContentPhrasePage page =
        tx(
            who,
            () ->
                descriptions.phrases(
                    new ContentPhraseQuery(null, type.toUpperCase(Locale.ROOT), null, 0, 200)));
    List<ContentPhraseListRow> rows = page.items() == null ? List.of() : page.items();
    return rows.stream()
        .map(ContentPhraseListRow::phrase)
        .filter(p -> p.text().equals(text))
        .findFirst()
        .orElse(null);
  }

  /** 铺一套卖点/特性/场景/注意事项短语（各 2 条），让四个池子都有腾挪空间。幂等。 */
  void seedPools(AccessService.Access who) {
    for (String text : List.of("双单元立体声，低音下潜到 20Hz", "高保真音质，支持 AAC 与 LDAC"))
      upsertPhrase(who, text, "SELLING");
    for (String text : List.of("IP67 防水，1.5 米深水 30 分钟", "Type-C 快充，10 分钟用 4 小时"))
      upsertPhrase(who, text, "FEATURE");
    for (String text : List.of("露营 / 泳池 / 车载三场景", "桌面与床头两处摆放")) upsertPhrase(who, text, "SCENE");
    for (String text : List.of("请勿在充电时拆解本机", "请勿将本机置于 -10℃ 以下环境"))
      upsertPhrase(who, text, "CAUTION");
  }

  private void upsertPhrase(AccessService.Access who, String text, String type) {
    if (findPhrase(who, text, type) == null) phrase(who, unique("PH"), text, type);
  }

  /** 标准模板：品牌 + 品类 + 卖点 + 特性，源语言 zh-CN。 */
  ContentTemplateSaved template(AccessService.Access who, String code) {
    return write(
        who,
        () ->
            descriptions.saveTemplate(
                who,
                null,
                new ContentTemplateInput(
                    code,
                    "详情描述 " + code,
                    "端到端夹具",
                    "DESCRIPTION",
                    "{brand} {category}蓝牙音箱，{selling}，{feature}。",
                    "zh-CN",
                    List.of(
                        new ContentTemplateVariableInput("brand", "SPU_BRAND", 0),
                        new ContentTemplateVariableInput("category", "SPU_CATEGORY", 1),
                        new ContentTemplateVariableInput("selling", "PHRASE_SELLING", 2),
                        new ContentTemplateVariableInput("feature", "PHRASE_FEATURE", 3)),
                    true,
                    0)));
  }

  List<String> templateCodes(AccessService.Access who) {
    return tx(who, () -> descriptions.templates()).stream().map(ContentTemplateRow::code).toList();
  }

  List<ContentSection> sectionsOf(AccessService.Access who, UUID skuId, String locale) {
    return tx(
            who,
            () ->
                renders.listSections(
                    new ContentSectionQuery(skuId, locale, null, null, null, null, 0, 200)))
        .items();
  }

  // ============================================================ 变量缺失报告（蓝图硬要求）

  /**
   * <b>蓝图硬要求的主用例：「变量缺失必须在填充时报告，不能静默留下占位符。」</b>
   *
   * <p>造一个品牌为空的 SPU，然后逐层验证：
   *
   * <ol>
   *   <li>填充**照常产出**正文（不是整批失败）——运营看到的是「这段缺 brand」， 而不是「填充失败」；
   *   <li>正文里**没有** {@code {brand}} 字面量（剥掉了）；
   *   <li>段落被标成「缺变量」，**不会**被 04 刊登当成可用内容；
   *   <li>缺失**逐条**落到 {@code content_render_issue}，带变量名与成因；
   *   <li>批次状态是「部分段落缺变量」，并给出可行动的解释；
   *   <li>按变量汇总答得出「brand 缺了几次」。
   * </ol>
   */
  @Test
  void missingVariablesAreReportedAndNeverLeftAsLiteralPlaceholders() {
    seedPools(actor);
    var saved = template(actor, unique("TPL"));
    // 品牌为空、分类为空的 SPU —— 两个事实变量都取不到值。
    UUID spuId = spu(actor, unique("SPU"), "", "");
    UUID target = sku(actor, spuId, unique("SKU"), "桌面款");

    var render =
        write(
            actor,
            () ->
                renders.render(
                    actor, new ContentRenderInput(saved.id(), List.of(target), "zh-CN")));

    // 缺变量是一种输出，不是一种失败：批次仍产出了段落。
    assertThat(render.status()).isEqualTo("PARTIAL");
    assertThat(render.sectionCount()).isEqualTo(1);
    assertThat(render.completeCount()).isZero();
    assertThat(render.incompleteCount()).isEqualTo(1);
    assertThat(render.issueCount()).isEqualTo(2);

    // 硬要求 1：正文里一个占位符都不能剩。
    List<ContentSection> sections = sectionsOf(actor, target, "zh-CN");
    assertThat(sections).hasSize(1);
    ContentSection section = sections.get(0);
    assertThat(section.text()).doesNotContain("{").doesNotContain("}");
    assertThat(section.text()).doesNotContain("{brand}").doesNotContain("{category}");
    assertThat(section.text()).contains("蓝牙音箱");
    // 两个短语变量都填上了（取池子里哪一条由序号决定，所以不锚定具体那句话）。
    assertThat(section.usedPhrases()).hasSize(2);
    assertThat(section.usedPhrases()).allSatisfy(p -> assertThat(p).isNotBlank());
    // 剥空之后不留双空格、不留重复标点。
    assertThat(section.text()).doesNotContain("  ");
    assertThat(section.text()).doesNotContain("，，");

    // 硬要求 2：段落标成缺变量，不会被下游当成可用内容。
    assertThat(section.complete()).isFalse();
    assertThat(section.status()).isEqualTo("INCOMPLETE");
    assertThat(section.statusLabel()).isEqualTo("缺变量");

    // 硬要求 3：缺失逐条落到表里，带变量名、类型与可执行原因。
    assertThat(section.issues()).hasSize(2);
    assertThat(section.issues())
        .extracting(ContentRenderIssue::variableName)
        .containsExactlyInAnyOrder("brand", "category");
    assertThat(section.issues())
        .allSatisfy(
            i -> {
              assertThat(i.issueKind()).isEqualTo("EMPTY_FACT");
              assertThat(i.issueKindLabel()).isEqualTo("商品字段为空");
              assertThat(i.detail()).contains("补上这个字段");
            });
    // 库里也真的有一行（不只是接口层拼出来的）。
    assertThat(tx(actor, () -> renders.missingVariablesOf(render.id())))
        .extracting(ContentMissingVariable::variableName)
        .containsExactlyInAnyOrder("brand", "category");

    // 批次解释要可行动：说清正文照样产出了、缺了什么、怎么修。
    assertThat(render.note()).contains("缺变量").contains("占位符");
    var detail = tx(actor, () -> renders.renderDetail(render.id()));
    assertThat(detail.note()).contains("占位符已被剥掉");
    assertThat(detail.missingVariables()).hasSize(2);
    assertThat(detail.missingVariables()).allSatisfy(m -> assertThat(m.occurrences()).isEqualTo(1));

    // 逐 SKU 结果同样带原因（列表页上要显示）。
    var skuRows = tx(actor, () -> renders.renderSkus(render.id()));
    assertThat(skuRows).hasSize(1);
    assertThat(skuRows.get(0).outcome()).isEqualTo("INCOMPLETE");
    assertThat(skuRows.get(0).reason()).contains("brand").contains("category");
  }

  /**
   * DB 层兜底：把「缺变量」的段落直接改成「已填好」必须失败。
   *
   * <p>服务层没有任何路径会这么干，所以这里只能手工 SQL 模拟 「一次绕过服务层的写入」——而那正是蓝图那条硬要求要防的情况。
   */
  @Test
  void databaseRefusesToPromoteAnIncompleteSectionToComplete() {
    seedPools(actor);
    var saved = template(actor, unique("TPL"));
    UUID spuId = spu(actor, unique("SPU"), "", "音频");
    UUID target = sku(actor, spuId, unique("SKU"), "桌面款");
    var render =
        write(
            actor,
            () ->
                renders.render(
                    actor, new ContentRenderInput(saved.id(), List.of(target), "zh-CN")));
    assertThat(render.incompleteCount()).isEqualTo(1);

    UUID sectionId = sectionsOf(actor, target, "zh-CN").get(0).id();

    // (1) 触发器：INCOMPLETE → COMPLETE 被拒（缺掉的变量不会因为改状态就回来）。
    assertThatThrownBy(
            () ->
                writeVoid(
                    actor,
                    () ->
                        sql.execute(
                            "update content_render_section set status='COMPLETE' where tenant_id=? and id=?",
                            a,
                            sectionId)))
        .hasMessageContaining("不能直接标记为已填好");

    // (2) CHECK：声称完整就必须有正文，且正文里不许有花括号。
    //     直接插一行「完整 + 带占位符」的记录，两条约束都会拦。
    assertThatThrownBy(
            () ->
                writeVoid(
                    actor,
                    () ->
                        sql.execute(
                            "insert into content_render_section(id,tenant_id,render_id,render_sku_id,sku_id,"
                                + "content_type,locale,text,char_length,status) values(?,?,?,?,?,?,?,?,?,'COMPLETE')",
                            UUID.randomUUID(),
                            a,
                            render.id(),
                            skuRows(render.id()).get(0).id(),
                            target,
                            "DESCRIPTION",
                            "zh-CN",
                            "{brand} 漏了占位符",
                            12)))
        .hasMessageContaining("残留了占位符");

    // (3) CHECK：声称完整就必须有正文（空文本撞 complete_needs_text）。
    assertThatThrownBy(
            () ->
                writeVoid(
                    actor,
                    () ->
                        sql.execute(
                            "insert into content_render_section(id,tenant_id,render_id,render_sku_id,sku_id,"
                                + "content_type,locale,text,char_length,status) values(?,?,?,?,?,?,?,?,0,'COMPLETE')",
                            UUID.randomUUID(),
                            a,
                            render.id(),
                            skuRows(render.id()).get(0).id(),
                            target,
                            "DESCRIPTION",
                            "zh-CN",
                            "")))
        .hasMessageContaining("content_render_section_complete_needs_text");
  }

  /** 短语池为空是与「商品字段为空」不同的修法，必须分开报。 */
  @Test
  void emptyPhrasePoolIsReportedWithItsOwnActionableReason() {
    // actorC 是专用租户：它的四个池子一定都是空的（别的用例不会往它上面建短语）。
    var saved = template(actorC, unique("TPL"));
    UUID spuId = spu(actorC, unique("SPU"), "星云", "音频");
    UUID target = sku(actorC, spuId, unique("SKU"), "桌面款");

    var render =
        write(
            actorC,
            () ->
                renders.render(
                    actorC, new ContentRenderInput(saved.id(), List.of(target), "zh-CN")));

    // brand/category 取得到值、两个短语池都空：正文照样产出了（只是短语处被剥空），
    // 所以是「部分段落缺变量」而不是「未产出」——运营手上有一份缺变量的清单可行动。
    assertThat(render.status()).isEqualTo("PARTIAL");
    var section = sectionsOf(actorC, target, "zh-CN").get(0);
    assertThat(section.complete()).isFalse();
    assertThat(section.text()).doesNotContain("{").doesNotContain("}");
    assertThat(section.text()).contains("星云 音频蓝牙音箱");
    assertThat(section.issues())
        .extracting(ContentRenderIssue::variableName)
        .containsExactlyInAnyOrder("selling", "feature");
    assertThat(section.issues())
        .allSatisfy(
            i -> {
              assertThat(i.issueKind()).isEqualTo("EMPTY_PHRASE_POOL");
              assertThat(i.issueKindLabel()).isEqualTo("短语池为空");
              // 修法必须是「去建短语」，而不是「去补商品字段」——后者在这里毫无意义。
              assertThat(i.detail()).contains("建至少一条");
            });

    // 补上短语后重跑：同样的输入不再报缺变量。
    seedPools(actorC);
    var again =
        write(
            actorC,
            () ->
                renders.render(
                    actorC, new ContentRenderInput(saved.id(), List.of(target), "zh-CN")));
    assertThat(again.status()).isEqualTo("SUCCEEDED");
    assertThat(again.issueCount()).isZero();
    // 两批的段落都在库里（历史批次不被覆盖），所以要挑「已填好」的那一段，
    // 直接取第一条会拿到上一批的失败结果。
    var usable =
        sectionsOf(actorC, target, "zh-CN").stream()
            .filter(ContentSection::complete)
            .findFirst()
            .orElseThrow();
    assertThat(usable.issues()).isEmpty();
    assertThat(usable.text()).isNotBlank().doesNotContain("{");
    // 变量真的参与了填充：两个短语都填上了（取的是池子里哪一条由序号决定）。
    assertThat(usable.text()).isNotEqualTo("星云 音频蓝牙音箱。");
    // 两个变量都缺时不会留下「，，。」这种重复标点。
    assertThat(section.text()).doesNotContain("，，");
  }

  /** 补上缺失的字段后重跑，段落变成「已填好」，下游才引用得到。 */
  @Test
  void refillingAfterTheVariableIsAvailableProducesUsableSections() {
    seedPools(actor);
    var saved = template(actor, unique("TPL"));
    UUID spuId = spu(actor, unique("SPU"), "", "音频");
    UUID target = sku(actor, spuId, unique("SKU"), "桌面款");

    var first =
        write(
            actor,
            () ->
                renders.render(
                    actor, new ContentRenderInput(saved.id(), List.of(target), "zh-CN")));
    assertThat(first.incompleteCount()).isEqualTo(1);

    // 补上品牌（3.1.1.1 的写入所有权，用直写 SQL 模拟「运营在商品页补了品牌」）。
    writeVoid(
        actor, () -> sql.execute("update spu set brand='星云' where tenant_id=? and id=?", a, spuId));

    var second =
        write(
            actor,
            () ->
                renders.render(
                    actor, new ContentRenderInput(saved.id(), List.of(target), "zh-CN")));
    assertThat(second.status()).isEqualTo("SUCCEEDED");
    assertThat(second.issueCount()).isZero();

    List<ContentSection> sections = sectionsOf(actor, target, "zh-CN");
    // 两批都在库里（历史批次不被覆盖），只有新的这批是可用��。
    assertThat(sections).hasSize(2);
    ContentSection usable =
        sections.stream().filter(ContentSection::complete).findFirst().orElseThrow();
    assertThat(usable.text()).startsWith("星云 音频蓝牙音箱");
    assertThat(usable.issues()).isEmpty();
    // 短语文本快照：改了短语库之后仍能解释「这段当时填了什么」。
    assertThat(usable.usedPhrases()).isNotEmpty().allSatisfy(p -> assertThat(p).isNotBlank());
  }

  List<ContentRenderSkuRow> skuRows(UUID renderId) {
    return tx(actor, () -> renders.renderSkus(renderId));
  }

  // ============================================================ 四类内容与多语言

  /** 蓝图的四类内容都要有真实数据模型与端点，不是只有详情描述。 */
  @Test
  void allFourContentTypesAreRealAndCarryTheirOwnLabels() {
    seedPools(actor);
    for (String type : List.of("DESCRIPTION", "INSTRUCTION", "CAUTION", "SELLING_POINT")) {
      String code = unique("T" + type.substring(0, 2));
      var saved =
          write(
              actor,
              () ->
                  descriptions.saveTemplate(
                      actor,
                      null,
                      new ContentTemplateInput(
                          code,
                          type + " 模板",
                          "",
                          type,
                          "{brand} 这段文字属于 " + type + "。",
                          "zh-CN",
                          List.of(new ContentTemplateVariableInput("brand", "SPU_BRAND", 0)),
                          true,
                          0)));
      assertThat(saved.id()).isNotNull();
      var row = tx(actor, () -> descriptions.template(saved.id()));
      assertThat(row.contentType()).isEqualTo(type);
      assertThat(row.contentTypeLabel()).isEqualTo(DescriptionRules.contentTypeLabel(type));
    }
    // 概览按类型分列计数：四类都真的进库了。
    var overview = tx(actor, () -> descriptions.overview());
    assertThat(overview.descriptionTemplateCount()).isGreaterThan(0);
    assertThat(overview.instructionTemplateCount()).isGreaterThan(0);
    assertThat(overview.cautionTemplateCount()).isGreaterThan(0);
    assertThat(overview.sellingPointTemplateCount()).isGreaterThan(0);
  }

  /** 固定文本的注意事项没有变量也允许（不强制加占位符），填充后原样产出。 */
  @Test
  void fixedCautionTemplateNeedsNoVariablesAndRendersUnchanged() {
    var saved =
        write(
            actor,
            () ->
                descriptions.saveTemplate(
                    actor,
                    null,
                    new ContentTemplateInput(
                        unique("TPL"),
                        "固定注意事项",
                        "",
                        "CAUTION",
                        "请勿在充电时拆解本机。请勿将本机置于 -10℃ 以下环境。",
                        "zh-CN",
                        List.of(),
                        true,
                        0)));
    UUID target = sku(actor, spu(actor, unique("SPU"), "星云", "音频"), unique("SKU"), "桌面款");

    var render =
        write(
            actor,
            () ->
                renders.render(
                    actor, new ContentRenderInput(saved.id(), List.of(target), "zh-CN")));

    assertThat(render.status()).isEqualTo("SUCCEEDED");
    assertThat(render.issueCount()).isZero();
    assertThat(sectionsOf(actor, target, "zh-CN").get(0).text())
        .isEqualTo("请勿在充电时拆解本机。请勿将本机置于 -10℃ 以下环境。");
  }

  /**
   * 多语言版本：译文占位符必须与源语言一致，缺译文时整批拒绝。
   *
   * <p>「缺译文」在提交时就拒而不是悄悄退回源语言正文 —— 那会让运营以为发出去的是译文，而 04 刊登引用的是段落里的 text。
   */
  @Test
  void translationsMustKeepPlaceholdersAndMissingLocaleIsRefusedUpFront() {
    seedPools(actor);
    var saved = template(actor, unique("TPL"));
    UUID target = sku(actor, spu(actor, unique("SPU"), "星云", "音频"), unique("SKU"), "桌面款");

    // 译文占位符不一致 → 保存时就拒。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        descriptions.saveTranslation(
                            actor,
                            saved.id(),
                            null,
                            new ContentTranslationInput(
                                "en-US", "EN", "{brand} uses material", "AI", "DRAFT", 0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("漏掉了源语言的占位符");
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        descriptions.saveTranslation(
                            actor,
                            saved.id(),
                            null,
                            new ContentTranslationInput(
                                "en-US",
                                "EN",
                                "{brand} {category} {selling} {feature} {color}",
                                "AI",
                                "DRAFT",
                                0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("在源语言正文里没有");

    // 缺译文时提交填充 → 整批拒，不退回源语言。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        renders.render(
                            actor, new ContentRenderInput(saved.id(), List.of(target), "en-US"))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能用这个语言填充");

    // 完整度检查答得出「哪些语言能直接填」。
    var coverage =
        tx(
            actor,
            () -> descriptions.translationCoverage(saved.id(), List.of("zh-CN", "en-US", "ja-JP")));
    assertThat(coverage.missingLocales()).containsExactlyInAnyOrder("en-US", "ja-JP");
    assertThat(coverage.note()).contains("整批报").contains("悄悄退回源语言正文");

    // 建一条占位符一致的 AI 预填译文 → 能按英文填。
    var translation =
        write(
            actor,
            () ->
                descriptions.saveTranslation(
                    actor,
                    saved.id(),
                    null,
                    new ContentTranslationInput(
                        "en-US",
                        "Detail",
                        "{brand} {category} speaker, {selling}, {feature}.",
                        "AI",
                        "DRAFT",
                        0)));
    assertThat(translation.originLabel()).isEqualTo("AI 预填");
    assertThat(translation.statusLabel()).isEqualTo("草稿");

    var render =
        write(
            actor,
            () ->
                renders.render(
                    actor, new ContentRenderInput(saved.id(), List.of(target), "en-US")));
    assertThat(render.status()).isEqualTo("SUCCEEDED");
    ContentSection section = sectionsOf(actor, target, "en-US").get(0);
    assertThat(section.text()).startsWith("星云 音频 speaker").doesNotContain("{");
    assertThat(sectionsOf(actor, target, "en-US")).hasSize(1);

    // 源语言本身不建译文行（它就是模板正文），否则「内容来自哪里」有两个答案。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        descriptions.saveTranslation(
                            actor,
                            saved.id(),
                            null,
                            new ContentTranslationInput(
                                "zh-CN",
                                "重复的源语言",
                                "{brand} {category} {selling} {feature}。",
                                "MANUAL",
                                "REVIEWED",
                                0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("是模板的源语言");
  }

  // ============================================================ 校验与引用保护

  @Test
  void templateValidationAndReferenceProtectionHold() {
    seedPools(actor);
    // 少声明绑定源 → 拒（那会在填充时变成「缺变量」）。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        descriptions.saveTemplate(
                            actor,
                            null,
                            new ContentTemplateInput(
                                unique("TPL"),
                                "少变量",
                                "",
                                "DESCRIPTION",
                                "{brand} 用 {material}",
                                "zh-CN",
                                List.of(new ContentTemplateVariableInput("brand", "SPU_BRAND", 0)),
                                true,
                                0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("没有声明绑定源");
    // 死配置（声明了但正文里没有）→ 拒。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        descriptions.saveTemplate(
                            actor,
                            null,
                            new ContentTemplateInput(
                                unique("TPL"),
                                "死配置",
                                "",
                                "DESCRIPTION",
                                "{brand} 音箱",
                                "zh-CN",
                                List.of(
                                    new ContentTemplateVariableInput("brand", "SPU_BRAND", 0),
                                    new ContentTemplateVariableInput(
                                        "material", "SPU_CATEGORY", 1)),
                                true,
                                0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("死配置");

    var saved = template(actor, unique("TPL"));
    UUID target = sku(actor, spu(actor, unique("SPU"), "星云", "音频"), unique("SKU"), "桌面款");
    write(
        actor,
        () -> renders.render(actor, new ContentRenderInput(saved.id(), List.of(target), "zh-CN")));

    // 被批次引用后不可删 → 只能停用。
    long version = tx(actor, () -> descriptions.template(saved.id())).version();
    assertThatThrownBy(
            () -> writeVoid(actor, () -> descriptions.deleteTemplate(actor, saved.id(), version)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不可删除");
    var disabled = write(actor, () -> descriptions.setTemplateEnabled(actor, saved.id(), false));
    assertThat(disabled.id()).isEqualTo(saved.id());
    // 停用后不能再用它新建批次。
    UUID other = sku(actor, spu(actor, unique("SPU"), "星云", "音频"), unique("SKU"), "另一个");
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        renders.render(
                            actor, new ContentRenderInput(saved.id(), List.of(other), "zh-CN"))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已停用");
  }

  /** 短语被段落引用后不可删；列表上就能看见引用数。 */
  @Test
  void phraseDictionaryIsIdempotentAndBlocksDeletionOnceReferenced() {
    seedPools(actor);
    var selling = findPhrase(actor, "双单元立体声，低音下潜到 20Hz", "SELLING");
    assertThat(selling).isNotNull();
    // 幂等：同类型同文本再建一条会撞唯一约束，所以夹具必须先查后建。
    assertThat(findPhrase(actor, "双单元立体声，低音下潜到 20Hz", "SELLING")).isNotNull();
    assertThat(findPhrase(actor, "双单元立体声，低音下潜到 20Hz", "FEATURE")).isNull();

    var saved = template(actor, unique("TPL"));
    UUID target = sku(actor, spu(actor, unique("SPU"), "星云", "音频"), unique("SKU"), "桌面款");
    write(
        actor,
        () -> renders.render(actor, new ContentRenderInput(saved.id(), List.of(target), "zh-CN")));

    // 引用数在列表上就看得见。池子按 (code, id) 排序，命中的是哪一条取决于
    // 各用例生成的 code，所以要挑**真正被引用到的那一条**来验证删除保护，
    // 而不是锚定某一句文案。
    var page =
        tx(actor, () -> descriptions.phrases(new ContentPhraseQuery(null, "SELLING", null, 0, 50)));
    assertThat(page.total()).isGreaterThan(0);
    var referenced =
        page.items().stream()
            .filter(r -> r.referenceCount() > 0)
            .findFirst()
            .orElseThrow(() -> new AssertionError("没有任何卖点短语显示被引用，引用数统计是坏的"));
    assertThat(referenced.phrase().phraseType()).isEqualTo("SELLING");

    long version = referenced.phrase().version();
    assertThatThrownBy(
            () ->
                writeVoid(
                    actor,
                    () -> descriptions.deletePhrase(actor, referenced.phrase().id(), version)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不可删除");

    // 停用可用：停用后不再参与填充，但不破坏历史段落。
    write(actor, () -> descriptions.setPhraseEnabled(actor, referenced.phrase().id(), false));
    assertThat(tx(actor, () -> descriptions.template(saved.id())).renderCount()).isGreaterThan(0);
    // 被停用的短语仍在库里可读（历史段落引用的是它的文本）。
    assertThat(
            tx(
                    actor,
                    () ->
                        descriptions.phrases(
                            new ContentPhraseQuery(
                                referenced.phrase().text(), null, "false", 0, 20)))
                .total())
        .isEqualTo(1);
  }

  // ============================================================ 租户隔离三断言

  /**
   * 租户隔离三断言：
   *
   * <ol>
   *   <li>两租户互不可见（读、写、删、导出都按当前租户过滤）；
   *   <li>伪造 {@code X-Tenant-Id} 头无效（租户只来自已认证的成员关系）；
   *   <li>连接池归还后不串租户（单连接反复交替）。
   * </ol>
   */
  @Test
  void tenantIsolationHoldsAcrossReadWriteAndPoolReuse() throws Exception {
    seedPools(actor);
    seedPools(actorB);
    String codeA = unique("TPLA");
    String codeB = unique("TPLB");
    var templateA = template(actor, codeA);
    var templateB = template(actorB, codeB);
    UUID mine = sku(actor, spu(actor, unique("SPU"), "星云", "音频"), unique("SKU"), "我的");
    UUID theirs = sku(actorB, spu(actorB, unique("SPU"), "极光", "台灯"), unique("SKU"), "他们的");
    var renderA =
        write(
            actor,
            () ->
                renders.render(
                    actor, new ContentRenderInput(templateA.id(), List.of(mine), "zh-CN")));
    var renderB =
        write(
            actorB,
            () ->
                renders.render(
                    actorB, new ContentRenderInput(templateB.id(), List.of(theirs), "zh-CN")));

    // 1) 两租户互不可见
    assertThat(templateCodes(actor)).contains(codeA).doesNotContain(codeB);
    assertThat(templateCodes(actorB)).contains(codeB).doesNotContain(codeA);
    // 手里握着别人的 id 也读不到（RLS + 查询都按当前租户过滤）。
    assertThatThrownBy(() -> tx(actor, () -> descriptions.template(templateB.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> descriptions.template(templateA.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actor, () -> renders.renderDetail(renderB.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> renders.renderDetail(renderA.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(sectionsOf(actor, theirs, "zh-CN")).isEmpty();
    assertThat(sectionsOf(actorB, mine, "zh-CN")).isEmpty();

    // 短语也不串：A 租户搜不到 B 租户建的短语。
    phrase(actorB, unique("PH"), "极光专属短语", "SCENE");
    assertThat(
            tx(
                    actorB,
                    () -> descriptions.phrases(new ContentPhraseQuery("极光专属短语", null, null, 0, 20)))
                .total())
        .isEqualTo(1);
    assertThat(
            tx(
                    actor,
                    () -> descriptions.phrases(new ContentPhraseQuery("极光专属短语", null, null, 0, 20)))
                .total())
        .isZero();

    // 译文也不串。
    write(
        actorB,
        () ->
            descriptions.saveTranslation(
                actorB,
                templateB.id(),
                null,
                new ContentTranslationInput(
                    "en-US",
                    "B only",
                    "{brand} {category} {selling} {feature} only.",
                    "MANUAL",
                    "REVIEWED",
                    0)));
    assertThat(tx(actorB, () -> descriptions.translations(templateB.id()))).isNotEmpty();
    // A 租户连「看一眼 B 的译文」都做不到（模板本身就被 RLS 挡了）。
    assertThatThrownBy(() -> tx(actor, () -> descriptions.translations(templateB.id())))
        .isInstanceOf(ResponseStatusException.class);

    // B 租户不能删 A 租户的模板，也不能用 A 租户的模板填自己的 SKU。
    long versionA = tx(actor, () -> descriptions.template(templateA.id())).version();
    assertThatThrownBy(
            () ->
                writeVoid(
                    actorB, () -> descriptions.deleteTemplate(actorB, templateA.id(), versionA)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () ->
                write(
                    actorB,
                    () ->
                        renders.render(
                            actorB,
                            new ContentRenderInput(templateA.id(), List.of(theirs), "zh-CN"))))
        .isInstanceOf(ResponseStatusException.class);

    // 2) 伪造 tenant header 无效：租户只来自已认证的成员关系
    http.perform(
            get("/catalog/descriptions/renders/" + renderA.id())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    var submitted =
        http.perform(
                post("/catalog/descriptions/renders")
                    .header("Authorization", "Bearer tenant-b")
                    .header("X-Tenant-Id", a)
                    .contentType("application/json")
                    .content(
                        "{\"templateId\":\""
                            + templateB.id()
                            + "\",\"skuIds\":[\""
                            + theirs
                            + "\"],\"locale\":\"zh-CN\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String forgedId =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(submitted).get("id").asText();
    // 伪造头写入的批次进的是 B 租户（凭据说的那个）。
    assertThat(sectionsOf(actorB, theirs, "zh-CN")).hasSize(2);
    // A 租户用同一个 id 查不到它。
    assertThatThrownBy(() -> tx(actor, () -> renders.renderDetail(UUID.fromString(forgedId))))
        .isInstanceOf(ResponseStatusException.class);
    // 伪造头读取段落也拿不到 A 租户的内容。
    http.perform(
            get("/catalog/descriptions/sections?skuId=" + mine)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));

    // 3) 连接池归还后不串租户（单连接反复交替）
    for (int i = 0; i < 5; i++) {
      assertThat(tx(actor, () -> renders.renderDetail(renderA.id())).render().id())
          .isEqualTo(renderA.id());
      assertThat(tx(actorB, () -> renders.renderDetail(renderB.id())).render().id())
          .isEqualTo(renderB.id());
      assertThatThrownBy(() -> tx(actorB, () -> renders.renderDetail(renderA.id())))
          .isInstanceOf(ResponseStatusException.class);
      assertThat(templateCodes(actor)).contains(codeA).doesNotContain(codeB);
      assertThat(templateCodes(actorB)).contains(codeB).doesNotContain(codeA);
      // 段落列表在连接复用时最容易串（它是 04 刊登最热的读路径）。
      assertThat(sectionsOf(actor, mine, "zh-CN")).isNotEmpty();
      assertThat(sectionsOf(actor, theirs, "zh-CN")).isEmpty();
      assertThat(sectionsOf(actorB, theirs, "zh-CN")).isNotEmpty();
      assertThat(sectionsOf(actorB, mine, "zh-CN")).isEmpty();
    }
  }

  // ============================================================ CSV 导出与 HTTP

  /**
   * CSV 导出产出真实行。
   *
   * <p>这个用例专门盯 3.4.2.1 踩过的坑：流式线程的连接上没有 {@code app.current_tenant}， FORCE RLS
   * 会把全部行藏起来，于是导出只有一个表头、而且**不报错**。
   */
  @Test
  void csvExportProducesRealRowsWithMissingVariableReport() throws Exception {
    seedPools(actor);
    var saved = template(actor, unique("TPL"));
    UUID spuId = spu(actor, unique("SPU"), "", "音频");
    UUID target = sku(actor, spuId, unique("SKU"), "桌面款");
    write(
        actor,
        () -> renders.render(actor, new ContentRenderInput(saved.id(), List.of(target), "zh-CN")));

    var chunks = new java.util.ArrayList<String>();
    writeVoid(
        actor,
        () ->
            renders.streamCsv(
                actor,
                new ContentSectionQuery(target, "zh-CN", null, null, null, null, 0, 0),
                chunks::add));
    String csv = String.join("", chunks);
    var lines = csv.split("\n");

    assertThat(lines[0]).contains("SKU编码").contains("缺失变量").contains("正文");
    // 表头之外至少一条真实数据行，且带上了缺失报告。
    assertThat(lines).hasSizeGreaterThan(1);
    assertThat(csv).contains("缺变量").contains("brand");
    // 正文列里没有占位符（这是硬要求在导出产物上的体现）。
    assertThat(csv).doesNotContain("{brand}");
    // RFC 4180：字段被引号包住。
    assertThat(lines[1]).startsWith("\"");
  }

  /** HTTP 端点与权限：读权限能读，写端点要写权限。 */
  @Test
  void httpEndpointsExposeTheRealWorkflow() throws Exception {
    seedPools(actor);
    var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    // 建短语
    http.perform(
            post("/catalog/descriptions/phrases")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"code\":\""
                        + unique("PH")
                        + "\",\"text\":\"磁吸底座，一秒吸附\","
                        + "\"phraseType\":\"FEATURE\",\"enabled\":true,\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.phraseTypeLabel").value("特性描述"));

    // 建模板
    var created =
        http.perform(
                post("/catalog/descriptions/templates")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType("application/json")
                    .content(
                        "{\"code\":\""
                            + unique("TPL")
                            + "\",\"name\":\"HTTP 模板\","
                            + "\"note\":\"端到端\",\"contentType\":\"DESCRIPTION\","
                            + "\"body\":\"{brand} {category}音箱，{selling}，{feature}。\","
                            + "\"sourceLocale\":\"zh-CN\","
                            + "\"variables\":[{\"name\":\"brand\",\"source\":\"SPU_BRAND\",\"slotNo\":0},"
                            + "{\"name\":\"category\",\"source\":\"SPU_CATEGORY\",\"slotNo\":1},"
                            + "{\"name\":\"selling\",\"source\":\"PHRASE_SELLING\",\"slotNo\":2},"
                            + "{\"name\":\"feature\",\"source\":\"PHRASE_FEATURE\",\"slotNo\":3}],"
                            + "\"enabled\":true,\"version\":0}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.variableCount").value(4))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String templateId = objectMapper.readTree(created).get("id").asText();

    // 造商品（3.1.1.1 的写入所有权）
    UUID product = sku(actor, spu(actor, unique("SPU"), "星云", "音频"), unique("SKU"), "桌面款");

    // 批量填充（同步：返回即已完成）
    var submitted =
        http.perform(
                post("/catalog/descriptions/renders")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType("application/json")
                    .content(
                        "{\"templateId\":\""
                            + templateId
                            + "\",\"skuIds\":[\""
                            + product
                            + "\"],\"locale\":\"zh-CN\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String renderId = objectMapper.readTree(submitted).get("id").asText();

    // 批次详情
    http.perform(
            get("/catalog/descriptions/renders/" + renderId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.render.id").value(renderId))
        .andExpect(jsonPath("$.sections[0].complete").value(true))
        .andExpect(
            jsonPath("$.sections[0].text")
                .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("{"))));

    // 缺失变量汇总（空数组而不是缺字段）
    http.perform(
            get("/catalog/descriptions/renders/" + renderId + "/missing-variables")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    // 段落列表 + 概览
    http.perform(
            get("/catalog/descriptions/sections?skuId=" + product)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));
    http.perform(get("/catalog/descriptions/overview").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.note").value(org.hamcrest.Matchers.containsString("占位符")));

    // 只读权限不能写
    http.perform(
            post("/catalog/descriptions/renders")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(
                    "{\"templateId\":\""
                        + templateId
                        + "\",\"skuIds\":[\""
                        + product
                        + "\"],\"locale\":\"zh-CN\"}"))
        .andExpect(status().isForbidden());
    // 未认证 → 401
    http.perform(get("/catalog/descriptions/overview")).andExpect(status().isUnauthorized());
  }
}
