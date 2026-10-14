package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 4.2.2.2 描述模板：五段骨架 + 变量绑定 + 多语言 + 图片插入位 + 渲染后的描述 HTML， 以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS：描述模板、段落、插图位、渲染结果、逐段 HTML、 图片落点与缺失报告全部真的落库，「HTML 里没有残留占位符」是从库里读出来的事实
 * 而不是内存里的返回值。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本文件最重要的断言是 {@link #unboundVariablesAreReportedAndNeverLeakIntoHtml()}：</b>
 * 它逐条验证蓝图硬要求「变量未绑定必须显式提示，不允许把 {@code {{尺寸}}} 原样发到平台」—— 渲染出的 HTML 里不残留任何花括号、段落被标成缺变量、缺失逐条落到 {@code
 * description_render_issue}，并且从 DB 层直接改状态也改不成「已填好」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DescriptionTemplateIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
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

  @Autowired DescriptionTemplateService templates;
  @Autowired DescriptionTemplateRenderService renders;
  @Autowired DescriptionService descriptions;
  @Autowired MediaService media;
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

  String unique(String hint) {
    return hint
        + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
  }

  UUID spu(AccessService.Access who, String code, String brand, String category) {
    return write(
        who,
        () ->
            catalog.saveSpu(
                who, null, new SpuInput(code, "星云桌面音箱", "", brand, category, "DRAFT", 0)));
  }

  UUID sku(AccessService.Access who, UUID spuId, String code, String name) {
    // saveSku 走乐观锁：同一个 SPU 下挂第二个 SKU 时版本已经被第一个 bumped 了，
    // 所以这里必须读当前版本而不是写死 0。
    return write(
        who,
        () ->
            catalog.saveSku(
                who,
                spuId,
                null,
                new SkuInput(
                    code,
                    name,
                    unique("BC"),
                    "PCS",
                    "ACTIVE",
                    List.of(),
                    catalog.detail(spuId).product().version())));
  }

  byte[] png(int width, int height, Color color) {
    try {
      var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
      Graphics2D g = image.createGraphics();
      g.setColor(color);
      g.fillRect(0, 0, width, height);
      g.dispose();
      var out = new ByteArrayOutputStream();
      ImageIO.write(image, "png", out);
      return out.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 真传一张 PNG 进素材库（宽高从字节探测，不是调用方填的）。 */
  UUID uploadImage(
      AccessService.Access who, String name, String purpose, int w, int h, Color color) {
    return write(
        who,
        () ->
            media
                .upload(
                    who,
                    new MockMultipartFile("file", name + ".png", null, png(w, h, color)),
                    new MediaUpload(
                        name, "IMAGE", purpose, "描述模板用图", name + " 的替代文本", List.of(), true))
                .id());
  }

  /**
   * 短语库是租户级的，同一句卖点只能有一条（V21 的 UNIQUE(tenant_id,phrase_type,text) 是刻意的产品约束）。所有用例共用同一个容器，所以这里是幂等的。
   */
  ContentPhraseRow phrase(AccessService.Access who, String code, String text, String type) {
    String typeUpper = type.toUpperCase(Locale.ROOT);
    var page =
        tx(who, () -> descriptions.phrases(new ContentPhraseQuery(null, typeUpper, null, 0, 200)));
    if (page.items() != null)
      for (var row : page.items()) if (row.phrase().text().equals(text)) return row.phrase();
    return write(
        who,
        () ->
            descriptions.savePhrase(
                who, null, new ContentPhraseInput(code, text, typeUpper, true, 0)));
  }

  /** 铺一套短语池，让 PHRASE_* 变量绑得上。幂等（同一租户内）。 */
  void seedPools(AccessService.Access who) {
    phrase(who, unique("PH"), "双单元立体声", "SELLING");
    phrase(who, unique("PH"), "露营 / 泳池 / 车载", "SCENE");
    phrase(who, unique("PH"), "请勿在充电时拆解", "CAUTION");
  }

  ContentTemplateVariableInput variable(String name, String source) {
    return new ContentTemplateVariableInput(name, source, null);
  }

  /** 五段齐全的标准模板（每段都带一个变量，覆盖三个绑定源）。 */
  List<DescriptionSectionInput> standardSections(boolean withSlot) {
    return List.of(
        new DescriptionSectionInput(
            "INTRO",
            "{spuName} 是 {brand} 出品的 {category} 产品，{selling}。",
            List.of(
                variable("spuName", "SPU_NAME"),
                variable("brand", "SPU_BRAND"),
                variable("category", "SPU_CATEGORY"),
                variable("selling", "PHRASE_SELLING")),
            withSlot
                ? List.of(new DescriptionImageSlotInput("SKU_PRIMARY", "主图", null, true, 0))
                : List.of(),
            true,
            null),
        new DescriptionSectionInput(
            "SCENE",
            "适用于 {scene} 等场景。",
            List.of(variable("scene", "PHRASE_SCENE")),
            List.of(),
            true,
            null),
        new DescriptionSectionInput(
            "SPEC",
            "型号 {skuName}（编码 {skuCode}），规格见下表。",
            List.of(variable("skuName", "SKU_NAME"), variable("skuCode", "SKU_CODE")),
            List.of(),
            true,
            null),
        new DescriptionSectionInput(
            "CAUTION",
            "{caution}。",
            List.of(variable("caution", "PHRASE_CAUTION")),
            List.of(),
            true,
            null),
        new DescriptionSectionInput(
            "SERVICE_PROMISE", "本店提供 7 天无理由退换与一年质保。", List.of(), List.of(), true, null));
  }

  DescriptionTemplateSaved template(AccessService.Access who, String code, boolean withSlot) {
    return write(
        who,
        () ->
            templates.saveTemplate(
                who,
                null,
                new DescriptionTemplateInput(
                    code, "描述模板 " + code, "", "zh-CN", standardSections(withSlot), true, 0)));
  }

  /**
   * 只有产品介绍一段，且品牌那个变量绑的是这个 SPU 上的空字段（专供「变量缺失」用例）。
   *
   * <p>刻意<b>不</b>写未声明绑定的占位符：保存期就被 {@code DescriptionRules.variableBindingProblem} 挡掉了（见 {@link
   * #placeholderWithoutBindingIsRejectedAtSaveTime()}）。 也就是说 UNBOUND_PLACEHOLDER 这条 issue_kind
   * 在正常路径上是**不可达**的， 它是给「模板改版与翻译不同步」那类漂移留的兜底。
   */
  DescriptionTemplateSaved thinTemplate(AccessService.Access who, String code) {
    return write(
        who,
        () ->
            templates.saveTemplate(
                who,
                null,
                new DescriptionTemplateInput(
                    code,
                    "薄模板 " + code,
                    "",
                    "zh-CN",
                    List.of(
                        new DescriptionSectionInput(
                            "INTRO",
                            "{brand} 的 {spuName}。",
                            List.of(
                                variable("brand", "SPU_BRAND"), variable("spuName", "SPU_NAME")),
                            List.of(),
                            true,
                            null)),
                    true,
                    0)));
  }

  // ============================================================ 五段骨架

  /**
   * 五段骨架真的落库，且段落背后的内容模板与变量**复用 3.4.2.2 的表**。
   *
   * <p>这个用例是 V96 文件头那个取舍判断的回归护栏： 「变量与多语言复用 V21」不是文档里的一句话，而是 {@code
   * description_template_section.content_template_id} 真的指向 content_template， 且 {@code
   * content_template_variable} 里查得到那些变量。 哪天有人在本模块另起一张变量表，这里会红。
   */
  @Test
  void fiveSectionSkeletonPersistsAndReusesV21Tables() {
    seedPools(actor);
    String code = unique("DTPL");
    var saved = template(actor, code, true);
    assertThat(saved.sectionCount()).isEqualTo(5);
    assertThat(saved.imageSlotCount()).isEqualTo(1);

    var row = tx(actor, () -> templates.template(saved.id()));
    assertThat(row.sections())
        .extracting(DescriptionSectionSummary::sectionType)
        .containsExactly("INTRO", "SCENE", "SPEC", "CAUTION", "SERVICE_PROMISE");
    assertThat(row.sections())
        .extracting(DescriptionSectionSummary::sortOrder)
        .containsExactly(0, 1, 2, 3, 4);
    // 五类显式建模，不是糊成一个正文大字符串。
    assertThat(row.sections())
        .extracting(DescriptionSectionSummary::sectionTypeLabel)
        .containsExactly("产品介绍", "使用场景", "规格参数", "注意事项", "售后承诺");
    assertThat(row.sections().get(0).variableCount()).isEqualTo(4);
    assertThat(row.sections().get(4).variableCount()).isZero();

    // 段落背后的 content_template_id 指向 V21 的表，不是本模块另建的表。
    var sectionRows =
        tx(
            actor,
            () ->
                sql.fetch(
                    "select s.section_type,s.content_template_id,c.code,c.content_type"
                        + " from description_template_section s"
                        + " join content_template c on c.tenant_id=s.tenant_id and c.id=s.content_template_id"
                        + " where s.tenant_id=? and s.template_id=? order by s.sort_order",
                    templates.currentTenantId(),
                    saved.id()));
    assertThat(sectionRows).hasSize(5);
    for (var r : sectionRows)
      assertThat(r.get("content_type", String.class)).isEqualTo("DESCRIPTION");
    // 段落编码由模板编码 + 段落类型派生（长度截到 32）。
    for (var r : sectionRows)
      assertThat(r.get("code", String.class)).startsWith(code).hasSizeLessThanOrEqualTo(32);

    // 变量落在 V21 的 content_template_variable 上：{{尺寸}} 的绑定源全仓只有一个答案。
    UUID introContentTemplate = sectionRows.get(0).get("content_template_id", UUID.class);
    var variables =
        tx(
            actor,
            () ->
                sql.fetch(
                    "select variable_name,source from content_template_variable where template_id=?"
                        + " order by slot_no",
                    introContentTemplate));
    assertThat(variables)
        .extracting(r -> r.get("variable_name", String.class))
        .containsExactly("spuName", "brand", "category", "selling");
    assertThat(variables)
        .extracting(r -> r.get("source", String.class))
        .containsExactly("SPU_NAME", "SPU_BRAND", "SPU_CATEGORY", "PHRASE_SELLING");

    // 插图位也在库里。
    var slots =
        tx(
            actor,
            () ->
                sql.fetch(
                    "select i.slot_no,i.bind_source,i.required from description_image_slot i"
                        + " join description_template_section d"
                        + " on d.tenant_id=i.tenant_id and d.id=i.section_id where d.template_id=?",
                    saved.id()));
    assertThat(slots).hasSize(1);
    assertThat(slots.get(0).get("bind_source", String.class)).isEqualTo("SKU_PRIMARY");
    assertThat(slots.get(0).get("required", Boolean.class)).isTrue();

    // 概览能回答「有多少模板没配齐五类段落」与「缺图位」。
    var overview = tx(actor, () -> templates.overview());
    assertThat(overview.templateCount()).isPositive();
    assertThat(overview.sectionCount()).isPositive();
    assertThat(overview.imageSlotCount()).isPositive();
    assertThat(overview.note()).contains("变量未绑定必须显式提示");
  }

  /** 段落重复类型被拒：同一模板里两段「产品介绍」会让渲染出两个同名 section。 */
  @Test
  void duplicateSectionTypeIsRejected() {
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        templates.saveTemplate(
                            actor,
                            null,
                            new DescriptionTemplateInput(
                                unique("DUP"),
                                "重复段落",
                                "",
                                "zh-CN",
                                List.of(
                                    new DescriptionSectionInput(
                                        "INTRO", "第一段", List.of(), List.of(), true, null),
                                    new DescriptionSectionInput(
                                        "INTRO", "第二段", List.of(), List.of(), true, null)),
                                true,
                                0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("重复");
  }

  /** 插图位编号必须从 0 连续：data-slot-no 是渲染出来给平台定位用的。 */
  @Test
  void imageSlotNumberingMustBeContiguous() {
    seedPools(actor);
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        templates.saveTemplate(
                            actor,
                            null,
                            new DescriptionTemplateInput(
                                unique("SLOT"),
                                "位号不连续",
                                "",
                                "zh-CN",
                                List.of(
                                    new DescriptionSectionInput(
                                        "INTRO",
                                        "正文",
                                        List.of(),
                                        List.of(
                                            new DescriptionImageSlotInput(
                                                "RENDER_INPUT", "第一张", null, false, 0),
                                            new DescriptionImageSlotInput(
                                                "RENDER_INPUT", "第三张", null, false, 2)),
                                        true,
                                        null)),
                                true,
                                0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("连续递增");
  }

  // ============================================================ 渲染后的描述 HTML

  /**
   * 渲染产出的**是真 HTML**，不是模板字符串： 五个 {@code <section data-section-type="…">} 按顺序串起来， 每段一个小标题，变量已替换成真实值。
   */
  @Test
  void renderProducesRealHtmlWithFiveTypedSections() {
    seedPools(actor);
    UUID target = sku(actor, spu(actor, unique("SPU"), "星云", "音频"), unique("SKU"), "桌面款");
    var saved = template(actor, unique("DTPL"), false);

    var product =
        write(
            actor,
            () ->
                renders.render(
                    actor, new DescriptionRenderInput(saved.id(), target, "zh-CN", List.of())));

    assertThat(product.status()).isEqualTo("SUCCEEDED");
    String html = product.html();
    assertThat(html).startsWith("<div class=\"description-template\"");
    assertThat(html)
        .contains(
            "data-template-code=\""
                + tx(actor, () -> templates.template(saved.id())).code()
                + "\"");
    assertThat(html).contains("data-locale=\"zh-CN\"");
    // 五段按类型、按顺序出现。
    assertThat(html.indexOf("data-section-type=\"INTRO\""))
        .isLessThan(html.indexOf("data-section-type=\"SCENE\""));
    assertThat(html.indexOf("data-section-type=\"SCENE\""))
        .isLessThan(html.indexOf("data-section-type=\"SPEC\""));
    assertThat(html.indexOf("data-section-type=\"SPEC\""))
        .isLessThan(html.indexOf("data-section-type=\"CAUTION\""));
    assertThat(html.indexOf("data-section-type=\"CAUTION\""))
        .isLessThan(html.indexOf("data-section-type=\"SERVICE_PROMISE\""));
    // 小标题按段落类型落成 <h3>。
    assertThat(html)
        .contains(
            "<h3>产品介绍</h3>", "<h3>使用场景</h3>", "<h3>规格参数</h3>", "<h3>注意事项</h3>", "<h3>售后承诺</h3>");
    // 变量已替换成真实值（品牌、分类、卖点短语都进了正文）。
    assertThat(html).contains("星云").contains("音频").contains("双单元立体声");
    // 模板字符串不是产物：花括号一个都不剩。
    assertThat(html).doesNotContain("{").doesNotContain("}");
    assertThat(product.sections()).hasSize(5);
    assertThat(product.sections()).allMatch(s -> "COMPLETE".equals(s.status()));
    assertThat(product.issueCount()).isZero();
    assertThat(product.note()).contains("可以直接交给");
  }

  /** 「渲染后的描述 HTML」真的落库了——不是内存里的一次性返回值。 */
  @Test
  void renderedHtmlIsPersistedAndReadableFromDatabase() {
    seedPools(actor);
    UUID target = sku(actor, spu(actor, unique("SPU"), "星云", "音频"), unique("SKU"), "桌面款");
    var saved = template(actor, unique("DTPL"), false);
    var product =
        write(
            actor,
            () ->
                renders.render(
                    actor, new DescriptionRenderInput(saved.id(), target, "zh-CN", List.of())));

    var stored =
        tx(
            actor,
            () ->
                sql.fetchOne(
                    "select html,status,locale from description_render where id=?",
                    product.renderId()));
    assertThat(stored.get("html", String.class)).isEqualTo(product.html());
    assertThat(stored.get("status", String.class)).isEqualTo("SUCCEEDED");
    assertThat(stored.get("locale", String.class)).isEqualTo("zh-CN");

    // 逐段 HTML 也落库了，五行。
    var sectionRows =
        tx(
            actor,
            () ->
                sql.fetch(
                    "select section_type,sort_order,html,status from description_render_section"
                        + " where tenant_id=? and render_id=? order by sort_order",
                    templates.currentTenantId(),
                    product.renderId()));
    assertThat(sectionRows).hasSize(5);
    for (var r : sectionRows) {
      assertThat(r.get("status", String.class)).isEqualTo("COMPLETE");
      assertThat(r.get("html", String.class)).contains("<section data-section-type=");
      assertThat(r.get("html", String.class)).doesNotContain("{");
    }

    // 再读一次（走端点同一条路径）拿到的还是同一份 HTML。
    var reread = tx(actor, () -> renders.product(product.renderId()));
    assertThat(reread.html()).isEqualTo(product.html());
  }

  /** 渲染记录的业务时间：timestamptz 写回断言（用 OffsetDateTime 绑定，不偏 −28800s）。 */
  @Test
  void renderTimestampRoundTripsAsInstant() {
    seedPools(actor);
    UUID target = sku(actor, spu(actor, unique("SPU"), "星云", "音频"), unique("SKU"), "桌面款");
    var saved = template(actor, unique("DTPL"), false);
    var product =
        write(
            actor,
            () ->
                renders.render(
                    actor, new DescriptionRenderInput(saved.id(), target, "zh-CN", List.of())));

    OffsetDateTime stored =
        tx(
            actor,
            () ->
                sql.fetchOne(
                        "select created_at from description_render where id=?", product.renderId())
                    .get("created_at", OffsetDateTime.class));
    assertThat(stored).isNotNull();
    // 写回值必须与库里读出来的是同一瞬间（时区无关的 Instant 口径）。
    assertThat(stored.toInstant()).isEqualTo(OffsetDateTime.parse(product.createdAt()).toInstant());
    // 不是未来、也不是 1970：timestamptz 绑定写错时最典型的症状是静默偏 8 小时。
    assertThat(stored.toInstant()).isAfter(java.time.Instant.parse("2020-01-01T00:00:00Z"));
    assertThat(stored.toInstant()).isBefore(java.time.Instant.now().plusSeconds(60));
  }

  // ============================================================ 变量未绑定必须显式提示

  /**
   * <b>本文件最重要的用例。</b>
   *
   * <p>逐条验证蓝图硬要求「变量未绑定必须显式提示，不允许把 {{尺寸}} 原样发到平台」：
   *
   * <ol>
   *   <li>HTML 里<b>不残留任何花括号</b>（正文字面量 {@code {unknownVar}} 被剥掉）；
   *   <li>取不到值的变量逐条落到 {@code description_render_issue}（不是拼在 note 里的一句���）；
   *   <li>受影响的段落被标成 INCOMPLETE，批次被标成 PARTIAL 而不是 SUCCEEDED；
   *   <li><b>从 DB 层直接改状态也改不成「已填好」</b>（V96 的触发器）；
   *   <li>从 DB 层手工插一行带占位符的 HTML 会被 CHECK + 触发器双重拒收。
   * </ol>
   */
  @Test
  void unboundVariablesAreReportedAndNeverLeakIntoHtml() {
    // 品牌留空：SPU_BRAND 在这个 SPU 上是空的 → EMPTY_FACT。
    UUID target = sku(actor, spu(actor, unique("SPU"), "", "音频"), unique("SKU"), "桌面款");
    var saved = thinTemplate(actor, unique("THIN"));

    var product =
        write(
            actor,
            () ->
                renders.render(
                    actor, new DescriptionRenderInput(saved.id(), target, "zh-CN", List.of())));

    // 1) HTML 里没有占位符残留：一个花括号都没有。
    assertThat(product.html()).doesNotContain("{").doesNotContain("}");
    assertThat(product.html()).doesNotContain("brand");
    // 剥空之后剩下的句子仍然可读（不是「 的 。」）。
    assertThat(product.html()).contains("的 星云桌面音箱。").doesNotContain("  ");

    // 2) 缺失逐条落库，可枚举、可查，而不是一句话。
    assertThat(product.issueCount()).isPositive();
    var issues =
        tx(
            actor,
            () ->
                sql.fetch(
                    "select variable_name,issue_kind,detail from description_render_issue"
                        + " where tenant_id=? and render_id=? order by variable_name",
                    templates.currentTenantId(),
                    product.renderId()));
    assertThat(issues).isNotEmpty();
    assertThat(issues).extracting(r -> r.get("variable_name", String.class)).contains("brand");
    assertThat(issues).extracting(r -> r.get("issue_kind", String.class)).contains("EMPTY_FACT");
    for (var r : issues) assertThat(r.get("detail", String.class)).contains("品牌");
    // 明细在端点上也有，且带可读标签。
    assertThat(product.issues())
        .extracting(DescriptionRenderIssueRow::variableName)
        .contains("brand");
    assertThat(product.issues())
        .extracting(DescriptionRenderIssueRow::issueKindLabel)
        .contains("商品字段为空");

    // 3) 受影响段落标成 INCOMPLETE，批次不是 SUCCEEDED（这个模板只有一段且没填好 → FAILED）。
    assertThat(product.status()).isEqualTo("FAILED");
    assertThat(product.sections()).allMatch(s -> "INCOMPLETE".equals(s.status()));
    assertThat(product.note()).contains("不能直接发到平台");

    // 库里也是同样的结论。
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                            "select status from description_render where id=?", product.renderId()))
                .get("status", String.class))
        .isEqualTo("FAILED");

    // 4) DB 层：缺变量的段落不能被直接改成「已填好」。
    UUID sectionRenderId = product.sections().get(0).id();
    assertThatThrownBy(
            () ->
                writeVoid(
                    actor,
                    () ->
                        sql.execute(
                            "update description_render_section set status='COMPLETE' where id=?",
                            sectionRenderId)))
        .hasMessageContaining("不能直接标记为已填好");

    // 5) DB 层：手工插一行带占位符的 HTML 会被 CHECK 与触发器双重拒收。
    assertThatThrownBy(
            () ->
                writeVoid(
                    actor,
                    () ->
                        sql.execute(
                            "insert into description_render_section(id,tenant_id,render_id,section_id,section_type,"
                                + "sort_order,locale,heading,html,image_count,status)"
                                + " values(?,?,?,?,?,?,?,?,?,?, 'COMPLETE')",
                            UUID.randomUUID(),
                            templates.currentTenantId(),
                            product.renderId(),
                            product.sections().get(0).id(),
                            "SPEC",
                            99,
                            "zh-CN",
                            "规格参数",
                            "<p>把 {{尺寸}} 原样发出去了</p>",
                            0)))
        .hasMessageContaining("占位符");

    // 而「有缺失」也不能被直接改成 SUCCEEDED：HTML 里已经没有占位符了
    // （缺变量的位置早被剥掉），所以这条靠的是计数那道闸——
    // 否则把 PARTIAL 改成 SUCCEEDED 就能让 4.2.1.1 照单上传一份「brand 没填上」的描述。
    assertThatThrownBy(
            () ->
                writeVoid(
                    actor,
                    () ->
                        sql.execute(
                            "update description_render set status='SUCCEEDED' where id=?",
                            product.renderId())))
        .hasMessageContaining("description_render_succeeded_has_no_gap");
  }

  /**
   * 同系列型号清单（{@code SKU_SIBLING_LIST}）这个绑定源单独覆盖。
   *
   * <p>它是唯一一个「取值来源不是某个字段，而是「别的行」的绑定源： SPU 下只有它自己时取不到值，3.4.2.2 的取值代码会报 「这个 SKU 所在 SPU
   * 下只有它自己，没有可列的同系列型号」—— 而不是笼统的「商品字段为空」，因为这两种的修法完全不同。
   */
  @Test
  void siblingListBindingIsReportedWithItsOwnActionableReason() {
    UUID target = sku(actor, spu(actor, unique("SPU"), "星云", "音频"), unique("SKU"), "桌面款");
    var saved =
        write(
            actor,
            () ->
                templates.saveTemplate(
                    actor,
                    null,
                    new DescriptionTemplateInput(
                        unique("SIB"),
                        "同系列型号",
                        "",
                        "zh-CN",
                        List.of(
                            new DescriptionSectionInput(
                                "SPEC",
                                "适配型号：{siblings}。",
                                List.of(variable("siblings", "SKU_SIBLING_LIST")),
                                List.of(),
                                true,
                                null)),
                        true,
                        0)));

    var product =
        write(
            actor,
            () ->
                renders.render(
                    actor, new DescriptionRenderInput(saved.id(), target, "zh-CN", List.of())));

    // 只有一段且它没填好 → 一段可用段落都没产出，所以是 FAILED 而不是 PARTIAL。
    assertThat(product.status()).isEqualTo("FAILED");
    assertThat(product.html()).doesNotContain("{").doesNotContain("siblings");
    assertThat(product.issues()).hasSize(1);
    assertThat(product.issues().get(0).issueKind()).isEqualTo("EMPTY_FACT");
    assertThat(product.issues().get(0).detail()).contains("同系列型号").contains("再加几个 SKU");
  }

  /** 占位符与变量声明对不上时在保存期就拒（配置期的缺失比渲染期的更早暴露）。 */
  @Test
  void placeholderWithoutBindingIsRejectedAtSaveTime() {
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        templates.saveTemplate(
                            actor,
                            null,
                            new DescriptionTemplateInput(
                                unique("BAD"),
                                "占位符没绑定",
                                "",
                                "zh-CN",
                                List.of(
                                    new DescriptionSectionInput(
                                        "INTRO",
                                        "尺寸是 {size}，重量是 {weight}。",
                                        List.of(variable("size", "SKU_SPEC")),
                                        List.of(),
                                        true,
                                        null)),
                                true,
                                0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("weight")
        .hasMessageContaining("没有声明绑定源");
  }

  /** 缺译文不退回源语言：那一段报 TRANSLATION_MISSING 并降级。 */
  @Test
  void missingTranslationIsReportedAndNeverFallsBackToSourceLocale() {
    seedPools(actor);
    UUID target = sku(actor, spu(actor, unique("SPU"), "星云", "音频"), unique("SKU"), "桌面款");
    var saved = template(actor, unique("DTPL"), false);

    var product =
        write(
            actor,
            () ->
                renders.render(
                    actor, new DescriptionRenderInput(saved.id(), target, "en-US", List.of())));

    assertThat(product.status()).isEqualTo("FAILED");
    assertThat(product.html()).doesNotContain("星云").doesNotContain("{");
    assertThat(product.issues()).isNotEmpty();
    assertThat(product.issues()).allMatch(i -> "TRANSLATION_MISSING".equals(i.issueKind()));
    assertThat(product.issues().get(0).detail()).contains("不会悄悄退回源语言");
    assertThat(product.note()).contains("该语言没有译文").contains("不能直接发到平台");
  }

  /** 译文占位符必须与源语言一致（复用 3.4.2.2 的同一份校验）。 */
  @Test
  void translationMustKeepTheSamePlaceholders() {
    seedPools(actor);
    var saved = template(actor, unique("DTPL"), false);
    UUID introContentTemplate =
        tx(
            actor,
            () ->
                sql.fetchOne(
                        "select content_template_id from description_template_section"
                            + " where template_id=? and section_type='INTRO'",
                        saved.id())
                    .get("content_template_id", UUID.class));

    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        descriptions.saveTranslation(
                            actor,
                            introContentTemplate,
                            null,
                            new ContentTranslationInput(
                                "en-US",
                                "Intro EN",
                                "{spuName} is made by {brand}. {selling}",
                                "AI",
                                "DRAFT",
                                0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("占位符");

    // 占位符集合一致时可以存，而且渲染时真的用上了译文。
    var translation =
        write(
            actor,
            () ->
                descriptions.saveTranslation(
                    actor,
                    introContentTemplate,
                    null,
                    new ContentTranslationInput(
                        "en-US",
                        "Intro EN",
                        "{spuName} is a {category} product by {brand}. {selling}. {brand} {category} {spuName} {selling}.",
                        "AI",
                        "DRAFT",
                        0)));
    assertThat(translation.locale()).isEqualTo("en-US");

    UUID target = sku(actor, spu(actor, unique("SPU"), "星云", "音频"), unique("SKU"), "桌面款");
    var product =
        write(
            actor,
            () ->
                renders.render(
                    actor, new DescriptionRenderInput(saved.id(), target, "en-US", List.of())));
    assertThat(product.sections().get(0).html()).contains("is a").contains("星云");
  }

  // ============================================================ 图片插入位

  /**
   * 图片插入位真的落成 {@code <img> 标签，位置可解析，宽高来自素材库。
   *
   * <p>用 {@code SKU_PRIMARY}：先真传一张 PNG 进素材库（3.4.1.1 的写入所有权），
   * 引用到 SKU 上，再渲染——src 落的是那条 storage_key 约定的地址，
   * 宽高是从图片字节探测出来的（120×80），不是调用方填的。
   */
  @Test
  void imageSlotBecomesRealImgTagWithResolvablePosition() {
    seedPools(actor);
    UUID target = sku(actor, spu(actor, unique("SPU"), "星云", "音频"), unique("SKU"), "桌面款");
    UUID assetId =
        uploadImage(actor, "主图-" + unique("IMG"), "PRIMARY", 120, 80, new Color(10, 20, 30));
    writeVoid(
        actor,
        () -> media.addReference(actor, assetId, new MediaReferenceInput("SKU", null, target, "")));

    var saved = template(actor, unique("DTPL"), true);
    var product =
        write(
            actor,
            () ->
                renders.render(
                    actor, new DescriptionRenderInput(saved.id(), target, "zh-CN", List.of())));

    assertThat(product.status()).isEqualTo("SUCCEEDED");
    assertThat(product.imageCount()).isEqualTo(1);

    // 真的落成了 img 标签，且位置可解析：全文档第几张 + 哪一段 + 这段第几个位。
    assertThat(product.html()).contains("<img data-position=\"0\"");
    assertThat(product.html()).contains("data-section-type=\"INTRO\" data-slot-no=\"0\"");
    assertThat(product.html()).contains("alt=\"主图\"");
    assertThat(product.html()).contains("width=\"120\"").contains("height=\"80\"");
    String storageKey = tx(actor, () -> media.detail(assetId)).asset().storageKey();
    assertThat(product.html()).contains("src=\"/api/media/" + storageKey + "\"");
    // 不是文字描述：HTML 里有一张真 <img>。
    assertThat(product.html()).contains("</section>").contains("<img");

    // 图片落点也落库了，position 存在且可查。
    var stored =
        tx(
            actor,
            () ->
                sql.fetch(
                    "select position,slot_no,asset_id,src,alt,width_px,height_px"
                        + " from description_render_image where tenant_id=? and render_id=?"
                        + " order by position",
                    templates.currentTenantId(),
                    product.renderId()));
    assertThat(stored).hasSize(1);
    assertThat(stored.get(0).get("position", Integer.class)).isZero();
    assertThat(stored.get(0).get("slot_no", Integer.class)).isZero();
    assertThat(stored.get(0).get("asset_id", UUID.class)).isEqualTo(assetId);
    assertThat(stored.get(0).get("width_px", java.math.BigDecimal.class))
        .isEqualByComparingTo("120");
    assertThat(stored.get(0).get("height_px", java.math.BigDecimal.class))
        .isEqualByComparingTo("80");
    assertThat(product.sections().get(0).images()).hasSize(1);
  }

  /** 必填插图位取不到图：报 IMAGE_UNBOUND 并把段落降级（缺图的详情页不该发到平台）。 */
  @Test
  void requiredImageSlotWithoutImageIsReported() {
    seedPools(actor);
    UUID target = sku(actor, spu(actor, unique("SPU"), "星云", "音频"), unique("SKU"), "桌面款");
    // 没有给这个 SKU 挂任何素材 → SKU_PRIMARY 取不到图。
    var saved = template(actor, unique("DTPL"), true);
    var product =
        write(
            actor,
            () ->
                renders.render(
                    actor, new DescriptionRenderInput(saved.id(), target, "zh-CN", List.of())));

    assertThat(product.status()).isEqualTo("PARTIAL");
    assertThat(product.imageCount()).isZero();
    assertThat(product.html()).doesNotContain("<img").doesNotContain("{");
    assertThat(product.sections().get(0).status()).isEqualTo("INCOMPLETE");
    assertThat(product.issues())
        .extracting(DescriptionRenderIssueRow::issueKind)
        .contains("IMAGE_UNBOUND");
    assertThat(product.issues())
        .extracting(DescriptionRenderIssueRow::issueKindLabel)
        .contains("必填插图位没有图");
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                            "select count(*) from description_render_issue where tenant_id=? and render_id=?"
                                + " and issue_kind='IMAGE_UNBOUND'",
                            templates.currentTenantId(),
                            product.renderId()))
                .get(0, Long.class))
        .isEqualTo(1L);
  }

  /** 渲染时由编辑器给图：URL 必须是 http/https（javascript: 是一条真实注入路径）。 */
  @Test
  void callerSuppliedImageUrlMustBeHttpOrHttps() {
    seedPools(actor);
    UUID target = sku(actor, spu(actor, unique("SPU"), "星云", "音频"), unique("SKU"), "桌面款");
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        renders.render(
                            actor,
                            new DescriptionRenderInput(
                                savedForSlot(target),
                                target,
                                "zh-CN",
                                List.of(
                                    new DescriptionRenderInputImage(
                                        0, "javascript:alert(1)", "", null, null, null))))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("http://");
  }

  UUID savedForSlot(UUID skuId) {
    return template(actor, unique("DTPL"), true).id();
  }

  /** 渲染时给的图照样落成 &lt;img&gt;，并带调用方给的宽高（NUMERIC，禁浮点）。 */
  @Test
  void callerSuppliedImageBecomesImgTag() {
    seedPools(actor);
    UUID target = sku(actor, spu(actor, unique("SPU"), "星云", "音频"), unique("SKU"), "桌面款");
    UUID assetId =
        uploadImage(actor, "详情图-" + unique("IMG"), "DETAIL", 640, 480, new Color(1, 2, 3));
    var saved =
        write(
            actor,
            () ->
                templates.saveTemplate(
                    actor,
                    null,
                    new DescriptionTemplateInput(
                        unique("DTPL"),
                        "编辑器给图",
                        "",
                        "zh-CN",
                        List.of(
                            new DescriptionSectionInput(
                                "INTRO",
                                "产品介绍正文。",
                                List.of(),
                                List.of(
                                    new DescriptionImageSlotInput(
                                        "RENDER_INPUT", "", assetId, true, 0)),
                                true,
                                null)),
                        true,
                        0)));

    var product =
        write(
            actor,
            () ->
                renders.render(
                    actor,
                    new DescriptionRenderInput(
                        saved.id(),
                        target,
                        "zh-CN",
                        List.of(
                            new DescriptionRenderInputImage(
                                0,
                                "https://cdn.example.com/detail.png",
                                "详情图",
                                assetId,
                                new java.math.BigDecimal("640"),
                                new java.math.BigDecimal("480"))))));

    assertThat(product.status()).isEqualTo("SUCCEEDED");
    assertThat(product.html())
        .contains("src=\"https://cdn.example.com/detail.png\"")
        .contains("width=\"640\"")
        .contains("height=\"480\"");
  }

  // ============================================================ 端点与停用

  /** HTTP 端点真的可用：概览、列表、渲染、详情。 */
  @Test
  void httpEndpointsExposeOverviewTemplatesAndRenders() throws Exception {
    seedPools(actor);
    UUID target = sku(actor, spu(actor, unique("SPU"), "星云", "音频"), unique("SKU"), "桌面款");
    var saved = template(actor, unique("DTPL"), false);

    http.perform(
            get("/catalog/description-templates/overview")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.templateCount").isNumber());
    http.perform(
            get("/catalog/description-templates/options")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sectionTypes.length()").value(5))
        .andExpect(jsonPath("$.imageBindSources.length()").value(3))
        .andExpect(jsonPath("$.issueKinds.length()").value(5))
        .andExpect(jsonPath("$.variableSources.length()").value(11));
    http.perform(
            get("/catalog/description-templates/templates")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(
            get("/catalog/description-templates/templates/{id}/sections", saved.id())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(5));

    var body =
        http.perform(
                post("/catalog/description-templates/renders")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType("application/json")
                    .content(
                        "{\"templateId\":\""
                            + saved.id()
                            + "\",\"skuId\":\""
                            + target
                            + "\",\"locale\":\"zh-CN\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.html").isString())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String renderId =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("renderId").asText();
    http.perform(
            get("/catalog/description-templates/renders/{id}", renderId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sections.length()").value(5));
    http.perform(
            get("/catalog/description-templates/renders")
                .header("Authorization", "Bearer tenant-a")
                .param("status", "SUCCEEDED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").isNumber())
        .andExpect(jsonPath("$.items[0].html").doesNotExist());
  }

  /** 停用后不能发起渲染，历史渲染照常可读。 */
  @Test
  void disabledTemplateCannotRenderButHistoryStaysReadable() {
    seedPools(actor);
    UUID target = sku(actor, spu(actor, unique("SPU"), "星云", "音频"), unique("SKU"), "桌面款");
    var saved = template(actor, unique("DTPL"), false);
    var product =
        write(
            actor,
            () ->
                renders.render(
                    actor, new DescriptionRenderInput(saved.id(), target, "zh-CN", List.of())));

    write(actor, () -> templates.setEnabled(actor, saved.id(), false));
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        renders.render(
                            actor,
                            new DescriptionRenderInput(saved.id(), target, "zh-CN", List.of()))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已停用");
    assertThat(tx(actor, () -> renders.product(product.renderId())).html()).isNotEmpty();
  }

  /** 被渲染引用过的模板不能删（历史 HTML 需要溯源），删除保护说清被引用几次。 */
  @Test
  void templateReferencedByRenderCannotBeDeleted() {
    seedPools(actor);
    UUID target = sku(actor, spu(actor, unique("SPU"), "星云", "音频"), unique("SKU"), "桌面款");
    var saved = template(actor, unique("DTPL"), false);
    write(
        actor,
        () ->
            renders.render(
                actor, new DescriptionRenderInput(saved.id(), target, "zh-CN", List.of())));
    long version = tx(actor, () -> templates.template(saved.id())).version();

    assertThatThrownBy(
            () -> writeVoid(actor, () -> templates.deleteTemplate(actor, saved.id(), version)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已被")
        .hasMessageContaining("不可删除");
  }

  /** 删除会连带清掉段落背后的内容模板，不留孤儿。 */
  @Test
  void deletingTemplateRemovesBackingContentTemplates() {
    seedPools(actor);
    var saved = template(actor, unique("DTPL"), false);
    List<UUID> contentTemplateIds =
        tx(
                actor,
                () ->
                    sql.fetch(
                        "select content_template_id from description_template_section where tenant_id=?"
                            + " and template_id=?",
                        templates.currentTenantId(),
                        saved.id()))
            .stream()
            .map(r -> r.get("content_template_id", UUID.class))
            .toList();
    assertThat(contentTemplateIds).hasSize(5);
    long version = tx(actor, () -> templates.template(saved.id())).version();

    writeVoid(actor, () -> templates.deleteTemplate(actor, saved.id(), version));

    assertThat(
            tx(
                actor,
                () ->
                    sql.fetch(
                        "select id from description_template_section where template_id=?",
                        saved.id())))
        .isEmpty();
    assertThat(
            tx(
                actor,
                () -> sql.fetchOne("select id from description_template where id=?", saved.id())))
        .isNull();
    for (UUID id : contentTemplateIds)
      assertThat(tx(actor, () -> sql.fetchOne("select id from content_template where id=?", id)))
          .isNull();
  }

  // ============================================================ 租户隔离三断言

  /**
   * 租户隔离三断言：
   *
   * <ol>
   *   <li>两租户互不可见（模板、段落、渲染、插图位都按当前租户过滤）；
   *   <li>伪造 {@code X-Tenant-Id} 头无效（租户只来自已认证的成员关系）；
   *   <li>连接池归还后不串租户（单连接反复交替）。
   * </ol>
   */
  @Test
  void tenantIsolationHoldsAcrossReadWriteAndPoolReuse() throws Exception {
    seedPools(actor);
    seedPools(actorB);
    String codeA = unique("DTPLA");
    String codeB = unique("DTPLB");
    var templateA = template(actor, codeA, false);
    var templateB = template(actorB, codeB, false);
    UUID mine = sku(actor, spu(actor, unique("SPU"), "星云", "音频"), unique("SKU"), "我的");
    UUID theirs = sku(actorB, spu(actorB, unique("SPU"), "极光", "台灯"), unique("SKU"), "他们的");
    var renderA =
        write(
            actor,
            () ->
                renders.render(
                    actor, new DescriptionRenderInput(templateA.id(), mine, "zh-CN", List.of())));
    var renderB =
        write(
            actorB,
            () ->
                renders.render(
                    actorB,
                    new DescriptionRenderInput(templateB.id(), theirs, "zh-CN", List.of())));

    // 1) 两租户互不可见
    assertThat(templateCodes(actor)).contains(codeA).doesNotContain(codeB);
    assertThat(templateCodes(actorB)).contains(codeB).doesNotContain(codeA);
    assertThatThrownBy(() -> tx(actor, () -> templates.template(templateB.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> templates.template(templateA.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actor, () -> templates.sections(templateB.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actor, () -> renders.product(renderB.renderId())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> renders.product(renderA.renderId())))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(
            tx(
                    actor,
                    () ->
                        renders.listRenders(new DescriptionRenderQuery(null, null, theirs, 0, 20)))
                .items())
        .isEmpty();
    assertThat(
            tx(
                    actor,
                    () -> renders.listRenders(new DescriptionRenderQuery(null, null, mine, 0, 20)))
                .items())
        .isNotEmpty();
    // 插图位也不串。
    assertThat(tx(actor, () -> sectionIdsOf(templateA.id()))).isNotEmpty();
    assertThat(tx(actor, () -> sectionIdsOf(templateB.id()))).isEmpty();

    // B 租户不能删 A 的模板，也不能拿 A 的模板渲染自己的 SKU。
    long versionA = tx(actor, () -> templates.template(templateA.id())).version();
    assertThatThrownBy(
            () ->
                writeVoid(actorB, () -> templates.deleteTemplate(actorB, templateA.id(), versionA)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () ->
                write(
                    actorB,
                    () ->
                        renders.render(
                            actorB,
                            new DescriptionRenderInput(
                                templateA.id(), theirs, "zh-CN", List.of()))))
        .isInstanceOf(ResponseStatusException.class);

    // 2) 伪造 tenant header 无效：租户只来自已认证的成员关系
    http.perform(
            get("/catalog/description-templates/renders/{id}", renderA.renderId())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    var submitted =
        http.perform(
                post("/catalog/description-templates/renders")
                    .header("Authorization", "Bearer tenant-b")
                    .header("X-Tenant-Id", a)
                    .contentType("application/json")
                    .content(
                        "{\"templateId\":\""
                            + templateB.id()
                            + "\",\"skuId\":\""
                            + theirs
                            + "\",\"locale\":\"zh-CN\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String forgedId =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(submitted)
            .get("renderId")
            .asText();
    // 伪造头写入的渲染进的是 B 租户（凭据说的那个），A 租户用同一个 id 查不到它。
    assertThatThrownBy(() -> tx(actor, () -> renders.product(UUID.fromString(forgedId))))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(tx(actorB, () -> renders.product(UUID.fromString(forgedId))).renderId())
        .isEqualTo(UUID.fromString(forgedId));

    // 3) 连接池归还后不串租户（单连接反复交替）
    for (int i = 0; i < 5; i++) {
      assertThat(tx(actor, () -> renders.product(renderA.renderId())).renderId())
          .isEqualTo(renderA.renderId());
      assertThat(tx(actorB, () -> renders.product(renderB.renderId())).renderId())
          .isEqualTo(renderB.renderId());
      assertThatThrownBy(() -> tx(actorB, () -> renders.product(renderA.renderId())))
          .isInstanceOf(ResponseStatusException.class);
      assertThat(templateCodes(actor)).contains(codeA).doesNotContain(codeB);
      assertThat(templateCodes(actorB)).contains(codeB).doesNotContain(codeA);
      assertThat(tx(actor, () -> sectionIdsOf(templateA.id()))).isNotEmpty();
      assertThat(tx(actor, () -> sectionIdsOf(templateB.id()))).isEmpty();
      assertThat(tx(actorB, () -> sectionIdsOf(templateB.id()))).isNotEmpty();
      assertThat(tx(actorB, () -> sectionIdsOf(templateA.id()))).isEmpty();
    }
  }

  List<String> templateCodes(AccessService.Access who) {
    return tx(who, () -> templates.templates()).stream().map(DescriptionTemplateRow::code).toList();
  }

  List<UUID> sectionIdsOf(UUID templateId) {
    return sql
        .fetch("select id from description_template_section where template_id=?", templateId)
        .stream()
        .map(r -> r.get("id", UUID.class))
        .toList();
  }
}
