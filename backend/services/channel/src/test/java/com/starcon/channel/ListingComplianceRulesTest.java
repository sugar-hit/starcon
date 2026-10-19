package com.starcon.channel;

import static com.starcon.channel.ListingComplianceRules.*;
import static com.starcon.channel.ListingComplianceSourcePort.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 4.2.3.2 合规预检 的<b>纯规则</b>单测。
 *
 * <p>本类只测 {@link ListingComplianceRules} —— 它不碰数据库、不碰 HTTP，
 * 所以「怎么判」这件事能被逐条钉死，而不必先起容器。
 */
class ListingComplianceRulesTest {

  // ==================================================================
  // 词命中
  // ==================================================================

  @Test
  @DisplayName("4.2.3.2 词命中：大小写不敏感，ASCII 词要求词边界")
  void wordMatching() {
    assertThat(indexOf("This is the BEST product", "best")).isEqualTo(12);
    assertThat(indexOf("bestseller in town", "best"))
        .as("best 不该命中 bestseller（词边界）")
        .isEqualTo(-1);
    assertThat(indexOf("最好的耳机", "最好")).as("中文按子串匹配").isEqualTo(0);
    assertThat(indexOf("abc best def", "best")).isEqualTo(4);
    assertThat(indexOf("", "best")).isEqualTo(-1);
    assertThat(indexOf("best", "")).isEqualTo(-1);
  }

  @Test
  @DisplayName("4.2.3.2 词命中：命中位置精确到「哪一栏 + 第几项」，四要素齐全")
  void wordRisksCarryFourElements() {
    WordEntry word =
        new WordEntry(
            "最好",
            "最好",
            "SUPERLATIVE",
            "BLOCKING",
            "HIGH",
            "平台极限词规则（2026 版）",
            "改成「更」",
            null,
            null);
    List<DraftField> fields =
        extractFields(
            Map.of(
                "TITLE", "这是最好的耳机",
                "BULLETS", List.of("第一条", "第二条也是最好")));

    List<RiskDraft> risks = wordRisks(List.of(word), fields);

    assertThat(risks).hasSize(2);
    RiskDraft title =
        risks.stream().filter(r -> "TITLE".equals(r.fieldKey())).findFirst().orElseThrow();
    assertThat(title.occurrenceIndex()).isEqualTo(-1);
    assertThat(title.matchedText()).isEqualTo("最好");
    assertThat(title.riskLevel()).isEqualTo("HIGH");
    assertThat(title.ruleSource()).isEqualTo("平台极限词规则（2026 版）");
    assertThat(title.suggestion()).isEqualTo("改成「更」");
    assertThat(title.dimension()).isEqualTo("SUPERLATIVE_WORD");

    RiskDraft bullet =
        risks.stream().filter(r -> "BULLETS".equals(r.fieldKey())).findFirst().orElseThrow();
    assertThat(bullet.occurrenceIndex()).as("列表字段要指到第几项").isEqualTo(1);
  }

  @Test
  @DisplayName("4.2.3.2 草稿内容提取：字符串 / 列表 / 键值三种形状都要读出来")
  void extractFieldsHandlesEveryShape() {
    List<DraftField> fields =
        extractFields(
            Map.of(
                "TITLE", "标题",
                "BULLETS", List.of("卖点一", "卖点二"),
                "CATEGORY_ATTRIBUTES", Map.of("material", "钢"),
                "A_PLUS", List.of(Map.of("heading", "模块标题", "body", "模块正文"))));

    assertThat(fields).hasSize(4);
    assertThat(fields.stream().filter(f -> "TITLE".equals(f.key())).findFirst().orElseThrow().texts())
        .containsExactly("标题");
    assertThat(fields.stream().filter(f -> "BULLETS".equals(f.key())).findFirst().orElseThrow().texts())
        .containsExactly("卖点一", "卖点二");
    assertThat(
            fields.stream()
                .filter(f -> "CATEGORY_ATTRIBUTES".equals(f.key()))
                .findFirst()
                .orElseThrow()
                .pairs())
        .containsEntry("material", "钢");
    assertThat(fields.stream().filter(f -> "A_PLUS".equals(f.key())).findFirst().orElseThrow().texts())
        .contains("模块标题", "模块正文");
  }

  // ==================================================================
  // 品牌侵权
  // ==================================================================

  @Test
  @DisplayName("4.2.3.2 品牌未登记 ⇒ 阻断项（可验证的违规才拦）")
  void unregisteredBrandIsBlocking() {
    DimensionResult result = brandRisks("Acme", null, List.of(), 30);
    assertThat(result.outcome().state()).isEqualTo("CHECKED");
    assertThat(result.risks()).hasSize(1);
    assertThat(result.risks().get(0).severity()).isEqualTo("BLOCKING");
    assertThat(result.risks().get(0).code()).isEqualTo("BRAND_NOT_REGISTERED");
    assertThat(result.risks().get(0).fieldKey()).isEqualTo("BRAND");
  }

  @Test
  @DisplayName("4.2.3.2 授权全部失效 ⇒ 阻断项；快到期 ⇒ 提醒项")
  void authorizationStates() {
    BrandRow brand =
        new BrandRow(
            "11111111-1111-1111-1111-111111111111",
            "ACME",
            "Acme",
            "AUTHORIZED",
            true,
            true,
            null,
            null,
            null,
            1,
            1,
            "2026-10-20T00:00:00Z");

    DimensionResult invalid =
        brandRisks(
            "Acme",
            brand,
            List.of(
                new BrandAuthorization(
                    "1", "11111111-1111-1111-1111-111111111111", "C1", "G", "2026-01-01", "2026-02-01",
                    null, "", true, false, -10)),
            30);
    assertThat(invalid.risks()).anyMatch(r -> "BRAND_UNAUTHORIZED".equals(r.code()) && "BLOCKING".equals(r.severity()));

    DimensionResult expiring =
        brandRisks(
            "Acme",
            brand,
            List.of(
                new BrandAuthorization(
                    "1", "11111111-1111-1111-1111-111111111111", "C1", "G", "2026-01-01", "2026-12-01",
                    null, "", true, true, 10)),
            30);
    assertThat(expiring.risks())
        .as("快到期只是提醒，不是阻断")
        .anyMatch(r -> "BRAND_AUTHORIZATION_EXPIRING".equals(r.code()) && "WARNING".equals(r.severity()));
  }

  // ==================================================================
  // 平台类目必填
  // ==================================================================

  @Test
  @DisplayName("4.2.3.2 平台类目必填缺失 ⇒ 阻断项；必填属性没绑内部属性 ⇒ 阻断项（归属 4.2.3.1）")
  void categoryRequiredRisks() {
    RequiredAttribute missing =
        new RequiredAttribute(
            "material", "材质", "REQUIRED", "TEXT", false, List.of(), "DIRECT",
            "22222222-2222-2222-2222-222222222222", "material", "材质", true);

    DimensionResult result = categoryRisks(Map.of(), List.of(missing));
    assertThat(result.risks()).hasSize(1);
    assertThat(result.risks().get(0).code()).isEqualTo("CATEGORY_REQUIRED_MISSING");
    assertThat(result.risks().get(0).severity()).isEqualTo("BLOCKING");
    assertThat(result.risks().get(0).fieldKey()).isEqualTo("CATEGORY_ATTRIBUTES");
    assertThat(result.risks().get(0).matchedText()).isEqualTo("material");

    DimensionResult filled = categoryRisks(Map.of("material", "钢"), List.of(missing));
    assertThat(filled.risks()).as("填了就不算缺失").isEmpty();

    RequiredAttribute unmapped =
        new RequiredAttribute(
            "warranty", "保修", "REQUIRED", "TEXT", false, List.of(), "DIRECT",
            null, null, null, false);
    DimensionResult unmappedResult = categoryRisks(Map.of(), List.of(unmapped));
    assertThat(unmappedResult.risks()).anyMatch(r -> "CATEGORY_REQUIRED_UNMAPPED".equals(r.code()));
    assertThat(unmappedResult.risks().get(0).ruleOwnerModule()).isEqualTo("4.2.3.1");
  }

  // ==================================================================
  // 图片合规
  // ==================================================================

  @Test
  @DisplayName("4.2.3.2 目标平台没有图片规格 ⇒ 图片维度 UNKNOWN（量不到不算通过）")
  void imageDimensionUnknownWithoutRecipe() {
    DimensionResult result = imageRisks(List.of("key/a.png"), List.of(), null, Map.of());
    assertThat(result.outcome().state()).isEqualTo("UNKNOWN");
    assertThat(result.outcome().ownerModule()).isEqualTo("3.4.1.2");
    assertThat(result.risks()).isEmpty();
  }

  @Test
  @DisplayName("4.2.3.2 图片尺寸不足 ⇒ 阻断项；白底未处理 / 无替代文本 ⇒ 提醒项")
  void imageRiskClassification() {
    ImageRecipe recipe =
        new ImageRecipe(
            "33333333-3333-3333-3333-333333333333",
            "AMZ-MAIN",
            "配方",
            "AMAZON",
            "png",
            true,
            true,
            "",
            "BOTTOM_RIGHT",
            30,
            new java.math.BigDecimal("1000"),
            new java.math.BigDecimal("1000"),
            "FIT",
            true,
            List.of());
    MediaAsset small =
        new MediaAsset(
            "44444444-4444-4444-4444-444444444444",
            "小图",
            "IMAGE",
            "PRIMARY",
            "png",
            "image/png",
            new java.math.BigDecimal("1024"),
            new java.math.BigDecimal("500"),
            new java.math.BigDecimal("500"),
            "key/a.png",
            "a.png",
            null,
            "VERIFIED",
            null,
            true);

    DimensionResult result = imageRisks(List.of("key/a.png"), List.of(small), recipe, Map.of());

    assertThat(result.outcome().state()).isEqualTo("CHECKED");
    assertThat(result.risks())
        .as("尺寸不足是可验证的违规 ⇒ 阻断项")
        .anyMatch(r -> "IMAGE_TOO_SMALL".equals(r.code()) && "BLOCKING".equals(r.severity()));
    assertThat(result.risks())
        .as("白底只是疑虑 ⇒ 提醒项")
        .anyMatch(r -> "IMAGE_WHITE_BACKGROUND_MISSING".equals(r.code()) && "WARNING".equals(r.severity()));
    assertThat(result.risks())
        .anyMatch(r -> "IMAGE_ALT_TEXT_MISSING".equals(r.code()) && "WARNING".equals(r.severity()));
  }

  @Test
  @DisplayName("4.2.3.2 素材在媒体库里找不到 ⇒ 阻断项")
  void missingAssetIsBlocking() {
    ImageRecipe recipe =
        new ImageRecipe(
            "33333333-3333-3333-3333-333333333333",
            "AMZ-MAIN",
            "配方",
            "AMAZON",
            "png",
            true,
            false,
            "",
            "BOTTOM_RIGHT",
            30,
            new java.math.BigDecimal("1000"),
            new java.math.BigDecimal("1000"),
            "FIT",
            true,
            List.of());
    DimensionResult result = imageRisks(List.of("key/missing.png"), List.of(), recipe, Map.of());
    assertThat(result.risks()).anyMatch(r -> "IMAGE_NOT_FOUND".equals(r.code()) && "BLOCKING".equals(r.severity()));
  }

  // ==================================================================
  // 结论三态
  // ==================================================================

  @Test
  @DisplayName("4.2.3.2 结论三态：有阻断项 ⇒ BLOCKED；有维度没量到 ⇒ INCOMPLETE；否则 PASS")
  void conclusionHasThreeStates() {
    assertThat(conclusion(0, 0)).isEqualTo("PASS");
    assertThat(conclusion(0, 1)).as("量不到不能说合规通过").isEqualTo("INCOMPLETE");
    assertThat(conclusion(2, 0)).isEqualTo("BLOCKED");
    assertThat(conclusion(2, 1)).as("阻断优先").isEqualTo("BLOCKED");
  }
}
