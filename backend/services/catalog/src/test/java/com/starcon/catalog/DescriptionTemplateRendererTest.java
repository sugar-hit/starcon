package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import com.starcon.catalog.DescriptionTemplateRenderer.ImageSlotSpec;
import com.starcon.catalog.DescriptionTemplateRenderer.ResolvedImage;
import com.starcon.catalog.DescriptionTemplateRenderer.SectionRendered;
import com.starcon.catalog.DescriptionTemplateRenderer.SectionSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 4.2.2.2 描述模板渲染引擎的纯函数断言。
 *
 * <p>与集成测试分工：这里不碰数据库，钉的是「拼装规则」本身
 * （五段顺序、&lt;img&gt; 位置可解析、白名单标签转义、占位符绝不残留）；
 * 「这些真的落进了 PostgreSQL」由 {@code DescriptionTemplateIntegrationTest} 负责。
 */
class DescriptionTemplateRendererTest {
  static final UUID TEMPLATE = UUID.randomUUID();
  static final UUID INTRO = UUID.randomUUID();
  static final UUID SPEC = UUID.randomUUID();

  static DescriptionRenderer.SkuFacts sku() {
    return new DescriptionRenderer.SkuFacts(
        UUID.randomUUID(), "SKU-1", "桌面款", "星云桌面音箱", "星云", "音频", "黑色 / 2.0声道",
        List.of("SKU-1", "SKU-2"));
  }

  static DescriptionRenderer.PhrasePools pools() {
    return new DescriptionRenderer.PhrasePools(
        List.of("双单元立体声"), List.of("IP67"), List.of("露营 / 泳池"), List.of("请勿充电时拆解"));
  }

  static SectionSpec section(
      UUID id, String type, int order, String body, List<DescriptionRenderer.VariableSpec> vars) {
    return new SectionSpec(id, type, order, true, "zh-CN", body, true, vars, List.of());
  }

  // ============================================================ 五段顺序

  @Test
  void sectionsAreEmittedInSortOrderWithTypedMarkers() {
    var specs =
        List.of(
            section(SPEC, "SPEC", 2, "规格：{spec}。", List.of(var("spec", "SKU_SPEC"))),
            section(INTRO, "INTRO", 0, "产品介绍。", List.of()));
    var doc =
        DescriptionTemplateRenderer.render(
            TEMPLATE, "DTPL", "zh-CN", specs, sku(), pools(), Map.of());

    assertThat(doc.sections()).extracting(SectionRendered::sectionType).containsExactly("INTRO", "SPEC");
    // 输出顺序跟着 sort_order 走，而不是跟着调用方给的顺序或枚举顺序。
    assertThat(doc.html().indexOf("data-section-type=\"INTRO\""))
        .isLessThan(doc.html().indexOf("data-section-type=\"SPEC\""));
    assertThat(doc.html()).contains("<h3>产品介绍</h3>").contains("<h3>规格参数</h3>");
    assertThat(doc.html()).startsWith("<div class=\"description-template\" data-template-code=\"DTPL\"");
    assertThat(doc.complete()).isTrue();
  }

  static DescriptionRenderer.VariableSpec var(String name, String source) {
    return new DescriptionRenderer.VariableSpec(name, source, 0);
  }

  @Test
  void showHeadingFalseOmitsTheHeadingButKeepsTheSection() {
    var specs =
        List.of(
            new SectionSpec(SPEC, "SPEC", 0, false, "zh-CN", "参数表", true, List.of(), List.of()));
    var doc =
        DescriptionTemplateRenderer.render(
            TEMPLATE, "DTPL", "zh-CN", specs, sku(), pools(), Map.of());
    assertThat(doc.html()).contains("<section data-section-type=\"SPEC\">");
    assertThat(doc.html()).doesNotContain("<h3>");
    // 段落小标题仍然作为字段返回给前端（前端要显示「规格参数」这个标签）。
    assertThat(doc.sections().get(0).heading()).isEqualTo("规格参数");
  }

  // ============================================================ 占位符绝不残留

  @Test
  void missingVariablesAreStrippedAndReportedNotLeftInHtml() {
    var specs =
        List.of(
            section(
                INTRO,
                "INTRO",
                0,
                "{brand} 的 {spuName}。",
                List.of(var("brand", "SPU_BRAND"), var("spuName", "SPU_NAME"))));
    var facts =
        new DescriptionRenderer.SkuFacts(
            UUID.randomUUID(), "SKU-1", "桌面款", "星云桌面音箱", "", "音频", "黑色", List.of());
    var doc =
        DescriptionTemplateRenderer.render(TEMPLATE, "DTPL", "zh-CN", specs, facts, pools(), Map.of());

    assertThat(doc.html()).doesNotContain("{").doesNotContain("}");
    assertThat(doc.html()).doesNotContain("brand");
    assertThat(doc.html()).contains("星云桌面音箱");
    assertThat(doc.complete()).isFalse();
    assertThat(doc.sections().get(0).issues()).hasSize(1);
    assertThat(doc.sections().get(0).issues().get(0).issueKind()).isEqualTo("EMPTY_FACT");
    assertThat(doc.sections().get(0).issues().get(0).variableName()).isEqualTo("brand");
    // 剥空之后标点前的空格也被收掉了。
    assertThat(doc.html()).contains("。").doesNotContain(" 。");
  }

  @Test
  void emptyPhrasePoolIsReportedSeparatelyFromEmptyFact() {
    var specs =
        List.of(section(INTRO, "INTRO", 0, "{selling}", List.of(var("selling", "PHRASE_SELLING"))));
    var emptyPools =
        new DescriptionRenderer.PhrasePools(List.of(), List.of(), List.of(), List.of());
    var doc =
        DescriptionTemplateRenderer.render(TEMPLATE, "DTPL", "zh-CN", specs, sku(), emptyPools, Map.of());
    assertThat(doc.html()).doesNotContain("{");
    assertThat(doc.sections().get(0).issues().get(0).issueKind()).isEqualTo("EMPTY_PHRASE_POOL");
  }

  @Test
  void missingTranslationDegradesTheSectionAndNeverFallsBackToSourceLocale() {
    var specs =
        List.of(
            new SectionSpec(
                INTRO, "INTRO", 0, true, "en-US", "星云的音频产品", false, List.of(), List.of()));
    var doc =
        DescriptionTemplateRenderer.render(TEMPLATE, "DTPL", "en-US", specs, sku(), pools(), Map.of());
    assertThat(doc.html()).doesNotContain("星云");
    assertThat(doc.sections().get(0).issues().get(0).issueKind()).isEqualTo("TRANSLATION_MISSING");
    assertThat(doc.complete()).isFalse();
  }

  // ============================================================ 图片插入位

  @Test
  void imageSlotsBecomeImgTagsWithGlobalPosition() {
    var slotA = new ImageSlotSpec(UUID.randomUUID(), 0, "RENDER_INPUT", "主图", null, true);
    var slotB = new ImageSlotSpec(UUID.randomUUID(), 1, "RENDER_INPUT", "细节图", null, false);
    var specs =
        List.of(
            new SectionSpec(
                INTRO, "INTRO", 0, true, "zh-CN", "产品介绍正文", true, List.of(), List.of(slotA, slotB)),
            new SectionSpec(
                SPEC, "SPEC", 1, true, "zh-CN", "规格正文", true, List.of(),
                List.of(new ImageSlotSpec(UUID.randomUUID(), 0, "RENDER_INPUT", "参数图", null, false))));
    Map<String, ResolvedImage> resolved = new LinkedHashMap<>();
    resolved.put(
        DescriptionTemplateRenderer.key(slotA, INTRO),
        new ResolvedImage(slotA.imageSlotId(), 0, null, "https://cdn/a.png", "a", null, null));
    resolved.put(
        DescriptionTemplateRenderer.key(slotB, INTRO),
        new ResolvedImage(slotB.imageSlotId(), 1, null, "https://cdn/b.png", "b", null, null));
    ResolvedImage specImage =
        new ResolvedImage(null, 0, null, "https://cdn/c.png", "c", null, null);
    resolved.put(
        DescriptionTemplateRenderer.key(
            new ImageSlotSpec(null, 0, "RENDER_INPUT", "参数图", null, false), SPEC),
        specImage);

    var doc = DescriptionTemplateRenderer.render(TEMPLATE, "DTPL", "zh-CN", specs, sku(), pools(), resolved);

    // data-position 是全文档连续序号，跨段落不回零。
    assertThat(doc.html()).contains("data-position=\"0\" data-section-type=\"INTRO\" data-slot-no=\"0\"");
    assertThat(doc.html()).contains("data-position=\"1\" data-section-type=\"INTRO\" data-slot-no=\"1\"");
    assertThat(doc.html()).contains("data-position=\"2\" data-section-type=\"SPEC\" data-slot-no=\"0\"");
    assertThat(doc.html()).contains("src=\"https://cdn/a.png\"").contains("alt=\"主图\"");
    assertThat(doc.sections()).flatExtracting(s -> s.images()).hasSize(3);
  }

  @Test
  void requiredImageSlotWithoutImageDegradesAndReports() {
    var slot = new ImageSlotSpec(UUID.randomUUID(), 0, "SKU_PRIMARY", "主图", null, true);
    var specs =
        List.of(
            new SectionSpec(
                INTRO, "INTRO", 0, true, "zh-CN", "产品介绍正文", true, List.of(), List.of(slot)));
    var doc = DescriptionTemplateRenderer.render(TEMPLATE, "DTPL", "zh-CN", specs, sku(), pools(), Map.of());
    assertThat(doc.html()).doesNotContain("<img");
    assertThat(doc.complete()).isFalse();
    var issue = doc.sections().get(0).issues().get(0);
    assertThat(issue.issueKind()).isEqualTo("IMAGE_UNBOUND");
    assertThat(issue.variableName()).isEqualTo("slot-0");
    assertThat(issue.detail()).contains("必填");
  }

  @Test
  void optionalImageSlotWithoutImageIsSilent() {
    var slot = new ImageSlotSpec(UUID.randomUUID(), 0, "SKU_GALLERY", "", null, false);
    var specs =
        List.of(
            new SectionSpec(
                INTRO, "INTRO", 0, true, "zh-CN", "产品介绍正文", true, List.of(), List.of(slot)));
    var doc = DescriptionTemplateRenderer.render(TEMPLATE, "DTPL", "zh-CN", specs, sku(), pools(), Map.of());
    assertThat(doc.complete()).isTrue();
    assertThat(doc.sections().get(0).issues()).isEmpty();
  }

  // ============================================================ HTML 安全

  @Test
  void onlyWhitelistedInlineTagsSurviveAndTheRestIsEscaped() {
    var specs =
        List.of(
            section(
                INTRO,
                "INTRO",
                0,
                "重要：<b>加粗</b>与 <script>alert(1)</script> 与 <img src=x onerror=y>",
                List.of()));
    var doc = DescriptionTemplateRenderer.render(TEMPLATE, "DTPL", "zh-CN", specs, sku(), pools(), Map.of());
    assertThat(doc.html()).contains("<b>加粗</b>");
    assertThat(doc.html()).doesNotContain("<script>");
    assertThat(doc.html()).doesNotContain("onerror=y>");
    assertThat(doc.html()).contains("&lt;script&gt;").contains("&lt;img src=x onerror=y&gt;");
  }

  @Test
  void variableValuesAreEscapedSoDataCannotBecomeMarkup() {
    var specs =
        List.of(
            section(INTRO, "INTRO", 0, "品牌：{brand}。", List.of(var("brand", "SPU_BRAND"))));
    var facts =
        new DescriptionRenderer.SkuFacts(
            UUID.randomUUID(), "SKU-1", "桌面款", "星云", "<img src=x onerror=alert(1)>", "音频",
            "黑色", List.of());
    var doc = DescriptionTemplateRenderer.render(TEMPLATE, "DTPL", "zh-CN", specs, facts, pools(), Map.of());
    // 品牌名里的标签被转义成文本，而不是变成一个真标签。
    assertThat(doc.html()).doesNotContain("<img src=x");
    assertThat(doc.html()).contains("&lt;img src=x onerror=alert(1)&gt;");
  }

  @Test
  void assetDimensionsAreRenderedAsPlainIntegers() {
    var slot = new ImageSlotSpec(UUID.randomUUID(), 0, "RENDER_INPUT", "主图", null, true);
    var specs =
        List.of(
            new SectionSpec(
                INTRO, "INTRO", 0, true, "zh-CN", "正文", true, List.of(), List.of(slot)));
    Map<String, ResolvedImage> resolved =
        Map.of(
            DescriptionTemplateRenderer.key(slot, INTRO),
            new ResolvedImage(
                slot.imageSlotId(), 0, null, "https://cdn/a.png", "a",
                new java.math.BigDecimal("640"), new java.math.BigDecimal("480.0")));
    var doc = DescriptionTemplateRenderer.render(TEMPLATE, "DTPL", "zh-CN", specs, sku(), pools(), resolved);
    assertThat(doc.html()).contains("width=\"640\"").contains("height=\"480\"");
    assertThat(doc.html()).doesNotContain("E+");
  }
}
