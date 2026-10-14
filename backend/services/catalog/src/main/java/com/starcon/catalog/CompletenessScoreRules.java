package com.starcon.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 3.9.2.1 完整度评分的纯规则：六个维度与权重、分档阈值、两条阻断链、 缺失码词表、图片阈值、认证四态判定，以及报关属性与认证的写入前置条件。
 *
 * <p>与 {@link SupplierQuoteRules} / {@link PhysicalSafetyRules} 同一条纪律：
 * <b>能在算分之前判定的，就不要留到下游才炸</b>。「HS 编码写成了 8471.3」 不会在评分时报错，它会在报关行退单时才炸，而那时单子已经发了。
 *
 * <p>规则 R1~R8 在 {@code V37__completeness_score.sql} 第十节有同样的文字。 本类是评分的<b>唯一</b>口径实现（V37 第七节：刻意不写 DB
 * 函数， 否则评分逻辑会变成 Java 与 plpgsql 两份，前端预览还要第三份）， 前端 {@code completeness-score-model.ts} 是它的镜像。
 *
 * <p>本类不碰数据库，可以在离线单测里穷举。
 *
 * <p>类名不叫 {@code CompletenessScore}：那是被静态导入的 DTO record （{@link
 * CompletenessScoreModels}），两者在同一批文件里会直接撞名。 与 {@link SupplierQuoteRules} 同一个理由。
 */
public final class CompletenessScoreRules {
  private CompletenessScoreRules() {}

  // ================================================================ 词表（规则 R8：枚举只增不改）

  /** 六个维度。顺序即评分卡与清单里的展示顺序（按权重降序，不是按字母）。 */
  public static final Set<String> DIMENSION_NAMES =
      Set.of("ATTRIBUTE", "MEDIA", "CATEGORY", "CUSTOMS", "PACKAGING", "CERTIFICATION");

  /** 被评主体类型。与 {@code media_reference.owner_type} 同一词表。 */
  public static final Set<String> OWNER_TYPES = Set.of("SPU", "SKU");

  /** 完整度分档。 */
  public static final Set<String> GRADES = Set.of("EXCELLENT", "GOOD", "FAIR", "POOR");

  /** 认证状态（规则 R5）。{@code NOT_REQUIRED} 与另外三个语义相反。 */
  public static final Set<String> CERTIFICATION_STATUSES =
      Set.of("HELD", "PENDING", "EXPIRED", "NOT_REQUIRED");

  /** 被评主体类型：SPU 级（在 SPU 上维护的值）。 */
  public static final String OWNER_SPU = "SPU";

  /** 被评主体类型：SKU 级（变体自己的值）。 */
  public static final String OWNER_SKU = "SKU";

  // ================================================================ 缺失码（稳定机器码）

  // 必填属性
  public static final String MISSING_REQUIRED_ATTRIBUTE = "REQUIRED_ATTRIBUTE_MISSING";
  public static final String MISSING_SKU_REQUIRED_ATTRIBUTE = "SKU_REQUIRED_ATTRIBUTE_MISSING";
  // 类目必填
  public static final String MISSING_PRIMARY_CATEGORY = "PRIMARY_CATEGORY_UNASSIGNED";
  public static final String MISSING_CATEGORY_RULE_VALUE = "CATEGORY_RULE_VALUE_MISSING";
  // 图片
  public static final String MISSING_PRIMARY_IMAGE = "PRIMARY_IMAGE_MISSING";
  public static final String MISSING_IMAGE_COUNT = "IMAGE_COUNT_BELOW_MIN";
  public static final String MISSING_PRIMARY_IMAGE_EDGE = "PRIMARY_IMAGE_EDGE_TOO_SMALL";
  // 报关
  public static final String MISSING_CUSTOMS_DECLARATION = "CUSTOMS_DECLARATION_MISSING";
  public static final String MISSING_HS_CODE = "HS_CODE_MISSING";
  public static final String MISSING_DECLARED_NAME = "DECLARED_NAME_MISSING";
  public static final String MISSING_ORIGIN_COUNTRY = "ORIGIN_COUNTRY_MISSING";
  // 包装
  public static final String MISSING_PACKAGE_RECORD = "PACKAGE_RECORD_MISSING";
  public static final String MISSING_PACKAGE_DIMENSION = "PACKAGE_DIMENSION_MISSING";
  public static final String MISSING_PACKAGE_WEIGHT = "PACKAGE_WEIGHT_MISSING";
  // 认证
  public static final String MISSING_CERTIFICATION_NONE = "CERTIFICATION_NOT_REGISTERED";
  public static final String MISSING_CERTIFICATION_PENDING = "CERTIFICATION_PENDING_ONLY";
  public static final String MISSING_CERTIFICATION_EXPIRED = "CERTIFICATION_EXPIRED_ONLY";
  // 无变体
  public static final String MISSING_SPU_WITHOUT_SKU = "SPU_HAS_NO_SKU";

  // ================================================================ 维度权重（第一节）

  /** 权重精度：4 位小数，与 {@code numeric(5,4)} 及前端同精度。 */
  public static final int SCORE_SCALE = 4;

  /**
   * 维度定义：权重 + 中文名 + 卡住哪条链。
   *
   * <p>顺序即展示顺序（按权重降序）。六项权重合计精确等于 1.0000（第一节）。
   */
  public record Dimension(String code, String label, BigDecimal weight, List<String> blockedFor) {}

  private static final List<Dimension> WEIGHTED_DIMENSIONS =
      List.of(
          new Dimension("ATTRIBUTE", "必填属性", bd("0.2500"), List.of("LISTING")),
          new Dimension("MEDIA", "图片数量与尺寸", bd("0.2000"), List.of("LISTING")),
          new Dimension("CATEGORY", "类目必填", bd("0.1500"), List.of("LISTING")),
          new Dimension("CUSTOMS", "报关属性", bd("0.1500"), List.of("CUSTOMS")),
          new Dimension("PACKAGING", "包装尺寸重量", bd("0.1500"), List.of("CUSTOMS")),
          new Dimension("CERTIFICATION", "认证", bd("0.1000"), List.of("LISTING")));

  private static BigDecimal bd(String value) {
    return new BigDecimal(value);
  }

  /** 六个维度定义（含权重与阻断链）。 */
  public static List<Dimension> dimensions() {
    return WEIGHTED_DIMENSIONS;
  }

  /**
   * 断言权重合计为 1。
   *
   * <p><b>在类初始化时就跑</b>，而不是等到算分时：权重表改漏一项（或合计写成 0.99） 应该在<b>启动</b>时炸掉，而不是上线后让所有分数静默偏移 ——
   * 分数偏移没有任何异常表现，页面照常显示，只是全店都低了 2 分。 这是 V37 第一节续钉死的那条。
   */
  static {
    BigDecimal sum = BigDecimal.ZERO;
    for (Dimension dimension : WEIGHTED_DIMENSIONS) sum = sum.add(dimension.weight());
    if (sum.compareTo(BigDecimal.ONE) != 0) {
      throw new ExceptionInInitializerError("完整度维度权重合计必须是 1.0000，实际为 " + sum.toPlainString());
    }
  }

  /** 按 code 取维度定义；未知 code 返回 null（调用方据此跳过未知维度，而不是崩）。 */
  public static Dimension dimension(String code) {
    if (code == null) return null;
    String normalized = code.trim().toUpperCase(Locale.ROOT);
    for (Dimension dimension : WEIGHTED_DIMENSIONS)
      if (dimension.code().equals(normalized)) return dimension;
    return null;
  }

  // ================================================================ 图片阈值（规则 R6）

  /** 达到该图片总数才算完整。3 是跨境平台主图位的通行下限。 */
  public static final int MIN_IMAGE_COUNT = 3;

  /** 主图最短边下限（像素）。1000px 是主流平台对主图的通行下限。 */
  public static final int MIN_PRIMARY_IMAGE_EDGE_PX = 1000;

  // ================================================================ 阻断链与门槛（规则 R3）

  /** 刊登链的维度。 */
  public static final List<String> LISTING_CHAIN =
      List.of("ATTRIBUTE", "MEDIA", "CATEGORY", "CERTIFICATION");

  /** 清关链的维度。 */
  public static final List<String> CUSTOMS_CHAIN = List.of("CUSTOMS", "PACKAGING");

  /** 刊登就绪的总分门槛（0..1）。低于它即使所有刊登维度都无缺失也不放行。 */
  public static final BigDecimal LISTING_GATE = bd("0.7500");

  /** 清关就绪的总分门槛（0..1）。比刊登低一档：报关只要求清关链的字段齐，评分是辅助。 */
  public static final BigDecimal CUSTOMS_GATE = bd("0.6000");

  /** 分档阈值（规则 R2）。作用在 0..1 比值上，不是百分数。 */
  private static final BigDecimal EXCELLENT_THRESHOLD = bd("0.9000");

  private static final BigDecimal GOOD_THRESHOLD = bd("0.7500");
  private static final BigDecimal FAIR_THRESHOLD = bd("0.6000");

  // 三个阈值对外开 getter：服务层用它们**拼**出 SQL 里的分档 CASE（不抄一份常量，
  // 见 CompletenessScoreService#gradeCase），所以阈值也只有这一份实现。

  /** 「优秀」下限。 */
  public static BigDecimal excellentThreshold() {
    return EXCELLENT_THRESHOLD;
  }

  /** 「良好」下限。 */
  public static BigDecimal goodThreshold() {
    return GOOD_THRESHOLD;
  }

  /** 「一般」下限。 */
  public static BigDecimal fairThreshold() {
    return FAIR_THRESHOLD;
  }

  /**
   * 加权求和 → 总分（规则 R2），从维度展示记录取比率。
   *
   * <p>刻意<b>不</b>叫 {@code totalScore}：它与下面的 {@code totalScore(List<BigDecimal>)} 擦除后是同一个签名（两个
   * {@code List}），Java 不会按泛型参数重载。
   */
  public static BigDecimal totalScoreOf(List<CompletenessScoreModels.CompletenessDimension> dims) {
    return totalScore(
        dims.stream().map(CompletenessScoreModels.CompletenessDimension::ratio).toList());
  }

  /**
   * 加权求和 → 总分（规则 R2）。
   *
   * <p>公式：{@code Σ(weight × ratio) / Σ(weight)}。分母写权重之和而不是写死 1： 规则 R4 保证每个维度至少一条检查项，所以正常路径上分母恒为
   * 1，而这里的除法是 <b>兜底</b> —— 将来新增维度时若忘了补缺失项，分母会小于 1，分数仍落在 0..1 而不是超过 1（一个会算出 1.15
   * 的完整度比一个偏低的完整度更容易被察觉，但仍然是错的）。
   *
   * <p>标度固定 4 位（{@code numeric(5,4)}）：不 scale 的话 {@code 0.25 × 1/3} 会得到 {@code
   * 0.08333333333333333}，写库时被 numeric 舍入，而读出口按 4 位比较时 与 {@code 0.0833} 的相等判断会时真时假。
   */
  public static BigDecimal totalScore(List<BigDecimal> ratios) {
    var dimensions = dimensions();
    BigDecimal weighted = BigDecimal.ZERO;
    BigDecimal weightSum = BigDecimal.ZERO;
    for (int i = 0; i < dimensions.size(); i++) {
      BigDecimal ratio =
          i < ratios.size() && ratios.get(i) != null ? ratios.get(i) : BigDecimal.ONE;
      weighted = weighted.add(dimensions.get(i).weight().multiply(ratio));
      weightSum = weightSum.add(dimensions.get(i).weight());
    }
    if (weightSum.signum() == 0) return BigDecimal.ZERO.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    return weighted.divide(weightSum, SCORE_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 分档。
   *
   * <p>阈值与权重同源（都用 0..1），所以改权重时阈值不会被忘记换算。 用 {@code compareTo} 而不是 {@code equals}：{@code score} 是
   * {@code numeric(5,4)} 读出来的 BigDecimal，标度不保证与字面量一致， 而 {@code equals} 会把 {@code 0.75} 与 {@code
   * 0.7500} 判成不等 —— 那会让 恰好 0.75 分的商品掉到下一档，且没有任何报错。
   */
  public static String gradeOf(BigDecimal score) {
    if (score == null) return "POOR";
    if (score.compareTo(EXCELLENT_THRESHOLD) >= 0) return "EXCELLENT";
    if (score.compareTo(GOOD_THRESHOLD) >= 0) return "GOOD";
    if (score.compareTo(FAIR_THRESHOLD) >= 0) return "FAIR";
    return "POOR";
  }

  /** 分档中文名。 */
  public static String gradeLabel(String grade) {
    if (grade == null) return "未评分";
    return switch (grade) {
      case "EXCELLENT" -> "优秀";
      case "GOOD" -> "良好";
      case "FAIR" -> "一般";
      case "POOR" -> "较差";
      default -> grade;
    };
  }

  /** 分档的排序权重（治理视图按严重程度排：POOR 在前）。 */
  public static int gradeRank(String grade) {
    if (grade == null) return 4;
    return switch (grade) {
      case "POOR" -> 0;
      case "FAIR" -> 1;
      case "GOOD" -> 2;
      case "EXCELLENT" -> 3;
      default -> 4;
    };
  }

  // ================================================================ 加权求和与就绪判定

  /**
   * 就绪判定（规则 R3）。
   *
   * @param chain 该链的维度清单（{@link #LISTING_CHAIN} 或 {@link #CUSTOMS_CHAIN}）
   * @param gate 该链的总分门槛
   * @param dims 全部六个维度的得分
   * @param score 总分
   * @return 就绪与否
   */
  public static boolean chainReady(
      List<String> chain,
      BigDecimal gate,
      List<CompletenessScoreModels.CompletenessDimension> dims,
      BigDecimal score) {
    if (score == null || score.compareTo(gate) < 0) return false;
    for (CompletenessScoreModels.CompletenessDimension dim : dims) {
      if (!chain.contains(dim.dimension())) continue;
      if (!dim.complete()) return false;
    }
    return true;
  }

  /** 某条链上所有未达标的维度名（阻塞原因）。 */
  public static List<String> chainBlockers(
      List<String> chain,
      List<CompletenessScoreModels.CompletenessDimension> dims,
      BigDecimal score,
      BigDecimal gate) {
    var blockers = new ArrayList<String>();
    if (score == null || score.compareTo(gate) < 0) {
      blockers.add(
          "总分低于"
              + (gate.compareTo(CUSTOMS_GATE) == 0 ? "清关" : "刊登")
              + "就绪门槛（"
              + percentLabel(score)
              + " < "
              + percentLabel(gate)
              + "）");
    }
    for (CompletenessScoreModels.CompletenessDimension dim : dims) {
      if (chain.contains(dim.dimension()) && !dim.complete()) blockers.add(dim.dimensionLabel());
    }
    return blockers;
  }

  /** 百分数展示串：{@code 0.7500} → {@code 75.00%}。只给界面看，不参与任何比较。 */
  public static String percentLabel(BigDecimal ratio) {
    if (ratio == null) return "—";
    return ratio.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP).toPlainString()
        + "%";
  }

  /** 比值的展示串：{@code 0.7500} → {@code 75.00}。存进 {@code scorePercent}。 */
  public static String percentValue(BigDecimal ratio) {
    if (ratio == null) return null;
    return ratio.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  // ================================================================ 认证（规则 R5）

  /**
   * 认证结论的<b>文案与映射</b>侧（规则 R5 的四态判定本身不在这里 —— 见下）。
   *
   * <p><b>为什么本类没有 {@code certificationCompleteness}</b>：认证四态的判定必须是
   * <b>唯一一份</b>实现，而评分的<b>全部</b>读出口（评分卡 / 列表 / 概览 / 缺失清单） 都要在 SQL 里按主体<b>聚合</b>出这条结论 —— 一份 SPU
   * 的认证结论是它每个变体结论的汇总。 Java 侧再判一次，两处各算一遍的症状是"评分卡说已齐全、补全清单说待续期"， 而两边读的是同一张表。所以<b>判定落在 SQL 的 {@code
   * sku_cert} 聚合里</b>（唯一实现）， 本类只保留：结论 → 缺失码、结论 → 中文名、状态 → 中文名、单张证书是否仍有效。
   *
   * <p>SQL 侧 {@code sku_cert} 的 CASE 顺序（与 {@link #certificationMissingCode} 对齐）： {@code
   * NOT_REQUIRED} → 有效 {@code HELD} → {@code EXPIRED} → {@code PENDING} → {@code NONE}。
   * 先认「确认不需要」与「确实拿到了」，再认「过期」，最后才是「还在等」—— 反过来写的话，一个既有待审又有过期证书的 SKU 会显示成"还在等审批"， 而真正要做的是先把过期那张续掉。规则
   * R5 的三种"不完整"各有各的缺失码， 因为它们的下一步动作完全不同（补录 / 跟进审批 / 续期）。
   */

  /** 认证结论的中文名。 */
  public static String certificationCompletenessLabel(String completeness) {
    if (completeness == null) return "未登记";
    return switch (completeness) {
      case "COMPLETE" -> "已齐全";
      case "PENDING" -> "已申请未取得";
      case "EXPIRED" -> "已过期待续期";
      case "NONE" -> "未登记";
      default -> completeness;
    };
  }

  /** 认证状态的展示名。 */
  public static String certificationStatusLabel(String status) {
    if (status == null) return "未登记";
    return switch (status) {
      case "HELD" -> "已取得";
      case "PENDING" -> "已申请";
      case "EXPIRED" -> "已过期";
      case "NOT_REQUIRED" -> "无需认证";
      default -> status;
    };
  }

  /** 一张 HELD 证书在判定日是否仍然有效。 */
  public static boolean certificateValid(LocalDate expiresOn, LocalDate today) {
    if (expiresOn == null) return true; // 长期有效（多数 CE 认证不写到期日）
    return today == null || !expiresOn.isBefore(today);
  }

  /** 认证结论 → 缺失码（规则 R5：三种"不完整"各有各的码）。 */
  public static String certificationMissingCode(String completeness) {
    if (completeness == null || "COMPLETE".equals(completeness)) return null;
    return switch (completeness) {
      case "PENDING" -> MISSING_CERTIFICATION_PENDING;
      case "EXPIRED" -> MISSING_CERTIFICATION_EXPIRED;
      default -> MISSING_CERTIFICATION_NONE;
    };
  }

  // ================================================================ 报关属性判定

  /**
   * 报关属性的完整度结论。
   *
   * <p>四个检查项各有各的缺失码，因为它们的下一步动作不同： 没有报关行 → <b>去建档</b>；有行但没填 HS 编码 → <b>去补字段</b>。
   * 合成一条「报关属性缺失」的话，运营会去建档，发现行已经在了， 于是以为系统出错 —— 而真正的原因（字段没填）被这句话盖住了。
   *
   * @param hasRow 是否已建报关属性行
   * @param hsCode 归一后的 HS 编码（空 = 未填）
   * @param declaredName 归一后的申报品名
   * @param originCountry 归一后的原产国
   * @return 空数组 = 完整；否则逐条缺失（顺序固定：先建档，再补字段）
   */
  public static List<String> customsMissingCodes(
      boolean hasRow, String hsCode, String declaredName, String originCountry) {
    var missing = new ArrayList<String>();
    if (!hasRow) {
      missing.add(MISSING_CUSTOMS_DECLARATION);
      return missing;
    }
    if (hsCode == null || hsCode.isEmpty()) missing.add(MISSING_HS_CODE);
    if (declaredName == null || declaredName.isEmpty()) missing.add(MISSING_DECLARED_NAME);
    if (originCountry == null || originCountry.isEmpty()) missing.add(MISSING_ORIGIN_COUNTRY);
    return missing;
  }

  /** 报关缺失码 → 人读的说明。 */
  public static String customsDetail(String code) {
    return switch (code) {
      case MISSING_CUSTOMS_DECLARATION -> "尚未登记报关属性，HS 编码与申报品名缺失时报关行会退单";
      case MISSING_HS_CODE -> "HS 编码未填：海关商品编号缺失时无法确定监管条件与税率";
      case MISSING_DECLARED_NAME -> "申报品名未填：报关单必须写明商品名称";
      case MISSING_ORIGIN_COUNTRY -> "原产国未填：它决定协定税率，自贸协定零关税凭它生效";
      default -> code;
    };
  }

  /** 报关缺失码 → 下一步动作。 */
  public static String customsRemedy(String code) {
    return switch (code) {
      case MISSING_CUSTOMS_DECLARATION -> "在 SKU 详情的「报关属性」中建档";
      case MISSING_HS_CODE -> "补填 HS 编码（可写国际段前 6 位）";
      case MISSING_DECLARED_NAME -> "补填申报品名";
      case MISSING_ORIGIN_COUNTRY -> "补填原产国（ISO 3166-1 两位码，如 CN / US）";
      default -> "补齐报关属性";
    };
  }

  // ================================================================ 缺失码 → 文案（统一出口）

  /** 缺失码 → 人读的说明（读出口与前端共用同一批文案）。 */
  public static String missingDetail(String code, String context) {
    return switch (code) {
      case MISSING_REQUIRED_ATTRIBUTE -> "必填属性「" + context + "」未取值";
      case MISSING_SKU_REQUIRED_ATTRIBUTE -> "该 SKU 的规格必填属性「" + context + "」未取值";
      case MISSING_PRIMARY_CATEGORY -> "SPU 没有指定主分类：类目必填规则取决于它，没分类就没有规则集";
      case MISSING_CATEGORY_RULE_VALUE -> "类目必填属性「" + context + "」未取值";
      case MISSING_PRIMARY_IMAGE -> "没有主图：平台主图位不能空";
      case MISSING_IMAGE_COUNT -> "图片共 " + context + " 张，少于下限 " + MIN_IMAGE_COUNT + " 张";
      case MISSING_PRIMARY_IMAGE_EDGE ->
          "主图最短边 " + context + "px，小于下限 " + MIN_PRIMARY_IMAGE_EDGE_PX + "px";
      case MISSING_PACKAGE_RECORD -> "没有包装记录：尺寸重量缺失时无法算体积重与清关申报";
      case MISSING_PACKAGE_DIMENSION -> "包装尺寸不完整（缺 " + context + "）：三边必须都有值才能算体积";
      case MISSING_PACKAGE_WEIGHT -> "包装重量不完整（缺 " + context + "）：净重与毛重都必须有值";
      case MISSING_CERTIFICATION_NONE -> "没有任何认证登记：若该 SKU 确实无需认证，请显式记一条「无需认证」";
      case MISSING_CERTIFICATION_PENDING -> "认证已申请但尚未取得（" + context + "）：等审批即可，补录证书编号救不了它";
      case MISSING_CERTIFICATION_EXPIRED -> "认证证书已过期（" + context + "）：需要续期";
      case MISSING_SPU_WITHOUT_SKU -> "该 SPU 还没有任何 SKU：变体级属性、报关与包装都无法确认";
      default -> customsDetail(code);
    };
  }

  /** 缺失码 → 下一步动作。 */
  public static String missingRemedy(String code) {
    return switch (code) {
      case MISSING_REQUIRED_ATTRIBUTE, MISSING_SKU_REQUIRED_ATTRIBUTE -> "在商品属性中补齐该属性值";
      case MISSING_PRIMARY_CATEGORY -> "在商品档案中指定主分类";
      case MISSING_CATEGORY_RULE_VALUE -> "补齐该类目要求的属性值";
      case MISSING_PRIMARY_IMAGE -> "上传并设为主图";
      case MISSING_IMAGE_COUNT -> "补足图片数量";
      case MISSING_PRIMARY_IMAGE_EDGE -> "上传更高分辨率的主图或用 3.4.1.2 图片处理放大";
      case MISSING_PACKAGE_RECORD -> "在 SKU 详情维护包装尺寸重量";
      case MISSING_PACKAGE_DIMENSION, MISSING_PACKAGE_WEIGHT -> "补齐包装尺寸与重量";
      case MISSING_CERTIFICATION_NONE -> "登记认证；确实不需要则记一条「无需认证」";
      case MISSING_CERTIFICATION_PENDING -> "跟进认证审批，取得后把状态改为已取得并补证书编号";
      case MISSING_CERTIFICATION_EXPIRED -> "发起认证续期并更新证书有效期";
      case MISSING_SPU_WITHOUT_SKU -> "先为该 SPU 建至少一个 SKU";
      default -> customsRemedy(code);
    };
  }

  /** 缺失码 → 检查项主体类型（补全清单按它分组）。 */
  public static String missingSubjectType(String code) {
    return switch (code) {
      case MISSING_REQUIRED_ATTRIBUTE,
              MISSING_SKU_REQUIRED_ATTRIBUTE,
              MISSING_CATEGORY_RULE_VALUE ->
          "ATTRIBUTE";
      case MISSING_PRIMARY_CATEGORY -> "CATEGORY";
      case MISSING_PRIMARY_IMAGE, MISSING_IMAGE_COUNT, MISSING_PRIMARY_IMAGE_EDGE -> "MEDIA_ASSET";
      case MISSING_CUSTOMS_DECLARATION,
              MISSING_HS_CODE,
              MISSING_DECLARED_NAME,
              MISSING_ORIGIN_COUNTRY ->
          "CUSTOMS";
      case MISSING_PACKAGE_RECORD, MISSING_PACKAGE_DIMENSION, MISSING_PACKAGE_WEIGHT -> "PACKAGE";
      case MISSING_CERTIFICATION_NONE,
              MISSING_CERTIFICATION_PENDING,
              MISSING_CERTIFICATION_EXPIRED ->
          "CERTIFICATION";
      case MISSING_SPU_WITHOUT_SKU -> "SKU";
      default -> "OTHER";
    };
  }

  /** 缺失码 → 缺失项清单上的「下一步入口」路由（前端按它给跳转链接）。 */
  public static String missingNextEntry(String code) {
    return switch (missingSubjectType(code)) {
      case "ATTRIBUTE" -> "/products/sku/[id]/attributes";
      case "MEDIA_ASSET" -> "/products/media";
      case "CATEGORY" -> "/products/categories";
      case "CUSTOMS", "PACKAGE", "CERTIFICATION" -> "/products/completeness";
      case "SKU" -> "/products/sku";
      default -> "/products";
    };
  }

  // ================================================================ 维度名（补全清单用）

  /** 维度码 → 中文名。 */
  public static String dimensionLabel(String code) {
    Dimension dimension = dimension(code);
    return dimension == null ? code : dimension.label();
  }

  /** 下拉选项（{@code /options} 端点与前端共用）。 */
  public record Option(String code, String label) {}

  /** 维度下拉选项，顺序即展示顺序。 */
  public static List<Option> dimensionOptions() {
    var options = new ArrayList<Option>();
    for (Dimension dimension : WEIGHTED_DIMENSIONS)
      options.add(new Option(dimension.code(), dimension.label()));
    return options;
  }

  /** 分档下拉选项，按严重程度排（治理视图先看最差的）。 */
  public static List<Option> gradeOptions() {
    var options = new ArrayList<Option>();
    for (String grade : List.of("POOR", "FAIR", "GOOD", "EXCELLENT"))
      options.add(new Option(grade, gradeLabel(grade)));
    return options;
  }

  /** 认证状态下拉选项，按「结论优先」排：无需 → 已取得 → 已申请 → 已过期。 */
  public static List<Option> certificationStatusOptions() {
    return List.of(
        new Option("NOT_REQUIRED", "无需认证"),
        new Option("HELD", "已取得"),
        new Option("PENDING", "已申请"),
        new Option("EXPIRED", "已过期"));
  }

  /** 被评主体类型下拉选项。 */
  public static List<Option> ownerTypeOptions() {
    return List.of(new Option(OWNER_SPU, "SPU"), new Option(OWNER_SKU, "SKU"));
  }

  // ================================================================ 聚合记录（概览与清单用）

  /** 分档计数（{@code grade} + {@code count}）。 */
  public record GradeCount(String grade, String label, long count) {}

  /** 维度缺口（{@code dimension} + 未满足数）。 */
  public record DimensionGap(String dimension, String dimensionLabel, long missingCount) {}

  /** 预检阻塞项（供 4.2.3.2 刊登预检与报关预检共用）。 */
  public record PrecheckBlocker(
      String ownerType,
      UUID ownerId,
      String ownerCode,
      String ownerName,
      BigDecimal score,
      String grade,
      List<String> missingCodes,
      List<String> blockers,
      String hint) {}

  /** 批量评估的单条失败（主体不存在 / 跨租户不可见）。 */
  public record EvaluationFailure(UUID ownerId, String reason) {}

  // ================================================================ 通用规范化

  /** 文本规范化：去首尾空白，空串归 null（"没填"）。 */
  public static String emptyToNull(String value) {
    if (value == null) return null;
    String text = value.trim();
    return text.isEmpty() ? null : text;
  }

  /** 枚举规范化：大写 + 去空白，空串归 null。 */
  public static String normalizeEnum(String value) {
    String text = emptyToNull(value);
    return text == null ? null : text.toUpperCase(Locale.ROOT);
  }

  /**
   * HS 编码规范化：去空白与分隔符后转大写。
   *
   * <p>去分隔符的理由：报关行回传的编码常带点与短横线（{@code 8471.30}、{@code 8471-30}）， 而我们自己录入的是 {@code
   * 847130}。不归一的话同一个编码会存成两种写法， 按 HS 编码筛选漏掉一半 —— 而页面上看不出来，因为两行看着一样。
   */
  public static String normalizeHsCode(String value) {
    String text = emptyToNull(value);
    if (text == null) return null;
    return text.replaceAll("[\\s.\\-_/]", "").toUpperCase(Locale.ROOT);
  }

  /** 原产国规范化：两位大写字母。 */
  public static String normalizeCountry(String value) {
    String text = normalizeEnum(value);
    return text == null ? null : text;
  }

  /** 布尔归一：null → true（{@code enabled}/{@code scored} 都是「缺省为真」）。 */
  public static boolean normalizeFlag(Boolean value) {
    return value == null || value;
  }

  /** 版本号归一：null → 0（无行时按新建处理）。 */
  public static long normalizeVersion(Long value) {
    return value == null ? 0L : value;
  }

  /** 行级乐观锁：把请求里的版本号转成 CAS 条件用的小工具。 */
  public static long requireVersion(Long value) {
    return value == null ? 0L : value;
  }

  // ================================================================ 写入前置条件

  /** HS 编码校验：格式而非词表（V37 第四节）。 */
  public static String hsCodeProblem(String value) {
    String code = normalizeHsCode(value);
    if (code == null) return null; // 空 = 未填，由评分判为缺失，不在写入时拦
    if (code.length() > 32) return "HS 编码最多 32 个字符";
    if (!code.matches("[0-9A-Z]+")) return "HS 编码只含数字与字母（小数点、短横线会被自动去掉）：" + code;
    return null;
  }

  /** 申报品名校验。空 = 未填，由评分判缺失。 */
  public static String declaredNameProblem(String value) {
    String name = emptyToNull(value);
    if (name == null) return null;
    if (name.length() > 200) return "申报品名最多 200 个字符";
    return null;
  }

  /** 原产国校验：ISO 3166-1 alpha-2 两位大写。空 = 未填。 */
  public static String originCountryProblem(String value) {
    String country = normalizeCountry(value);
    if (country == null) return null;
    if (!country.matches("[A-Z]{2}")) return "原产国请用 ISO 3166-1 两位码（如 CN / US / DE）";
    return null;
  }

  /** 法定计量单位校验：长度上限。刻意不查单位字典（V37 第四节）。 */
  public static String legalUnitProblem(String value) {
    String unit = emptyToNull(value);
    if (unit == null) return null;
    if (unit.length() > 32) return "法定计量单位最多 32 个字符";
    return null;
  }

  /** 申报要素条数上限。60 条已远超海关常见要求，防手滑。 */
  public static final int MAX_DECLARATION_ELEMENTS = 60;

  /**
   * 报关属性整体校验（写入前）。
   *
   * <p>刻意<b>不</b>要求 HS 编码与申报品名必填：报关属性是<b>逐步补全</b>的， 建档时常常只有品名、HS 编码要等供应商确认。要求全填会让第一步"建一行"都做不了，
   * 于是运营干脆不建 —— 那样连"缺了哪几项"都看不到。 必填与否由<b>评分</b>判，评分报的是缺失项而不是拒写。
   */
  public static String customsProblem(
      String hsCode,
      String declaredName,
      String originCountry,
      String legalUnit,
      List<String> declarationElements) {
    String problem = hsCodeProblem(hsCode);
    if (problem != null) return problem;
    problem = declaredNameProblem(declaredName);
    if (problem != null) return problem;
    problem = originCountryProblem(originCountry);
    if (problem != null) return problem;
    problem = legalUnitProblem(legalUnit);
    if (problem != null) return problem;
    if (declarationElements != null && declarationElements.size() > MAX_DECLARATION_ELEMENTS)
      return "申报要素最多 " + MAX_DECLARATION_ELEMENTS + " 条";
    return null;
  }

  /** 一条报关属性是否可以落库（至少有一个字段非空，见 V37 的 CHECK）。 */
  public static boolean customsHasContent(
      String hsCode,
      String declaredName,
      String originCountry,
      String legalUnit,
      List<String> declarationElements,
      Boolean enabled) {
    if (normalizeFlag(enabled)) {
      return notBlank(hsCode)
          || notBlank(declaredName)
          || notBlank(originCountry)
          || notBlank(legalUnit)
          || (declarationElements != null && !declarationElements.isEmpty());
    }
    return true;
  }

  /** 认证代码校验：非空、长度、大写字母数字。刻意不查受控词表（V37 第二节）。 */
  public static String certificationCodeProblem(String value) {
    String code = normalizeEnum(value);
    if (code == null) return "认证种类代码必填（如 CE / FCC / UL / PSE / 3C）";
    if (code.length() > 32) return "认证种类代码最多 32 个字符";
    if (!code.matches("[A-Z0-9]+")) return "认证种类代码只含大写字母与数字：" + code;
    return null;
  }

  /** 认证状态校验。空 → 报错（HELD/PENDING/EXPIRED/NOT_REQUIRED 必须显式选一个）。 */
  public static String certificationStatusProblem(String value) {
    String status = normalizeEnum(value);
    if (status == null) return "认证状态必填：已取得 / 已申请 / 已过期 / 无需认证";
    if (!CERTIFICATION_STATUSES.contains(status))
      return "认证状态不在词表里（可用："
          + String.join(" / ", certificationStatusOptions().stream().map(Option::label).toList())
          + "）";
    return null;
  }

  /**
   * 一条认证的写入前置校验。
   *
   * <p>HELD 必须有证书编号、NOT_REQUIRED 不能有编号与有效期 —— 与 {@code guard_sku_certification_status}
   * 触发器同一口径（纵深防御： CHECK 可被 {@code DROP CONSTRAINT} 绕过，触发器不能）。
   *
   * @param expiresOn 用来判断长期有效的 null 与具体到期日
   */
  public static String certificationProblem(
      String code,
      String name,
      String status,
      String certificateNo,
      LocalDate issuedOn,
      LocalDate expiresOn,
      Boolean scored,
      String note) {
    String problem = certificationCodeProblem(code);
    if (problem != null) return problem;
    String normalizedStatus = normalizeEnum(status);
    problem = certificationStatusProblem(status);
    if (problem != null) return problem;
    if (emptyToNull(name) != null && emptyToNull(name).length() > 100) return "认证名称最多 100 个字符";
    if (emptyToNull(certificateNo) != null && emptyToNull(certificateNo).length() > 100)
      return "证书编号最多 100 个字符";
    if (emptyToNull(note) != null && emptyToNull(note).length() > 500) return "备注最多 500 个字符";
    if ("HELD".equals(normalizedStatus) && emptyToNull(certificateNo) == null)
      return "认证状态为「已取得」时必须填写证书编号：一张没有编号的证书无法被海关与平台核对";
    if ("NOT_REQUIRED".equals(normalizedStatus)) {
      if (emptyToNull(certificateNo) != null || issuedOn != null || expiresOn != null)
        return "认证状态为「无需认证」时不能有证书编号与有效期：这一行表达的是「压根没有这张证书」";
    }
    if (issuedOn != null && expiresOn != null && expiresOn.isBefore(issuedOn))
      return "认证到期日不能早于签发日（" + issuedOn + " → " + expiresOn + "）";
    return null;
  }

  private static boolean notBlank(String value) {
    return emptyToNull(value) != null;
  }

  // ================================================================ 筛选与分页

  /** 单页最大行数。评分对象是租户级主数据，行数量级在万位。 */
  public static final int MAX_PAGE_SIZE = 200;

  /** 页大小校验与归一（null → 20，越界 → 报错或截断）。 */
  public static Integer normalizeSize(Integer size) {
    if (size == null) return 20;
    if (size < 1) return 1;
    return Math.min(size, MAX_PAGE_SIZE);
  }

  /** 页码归一（null → 0，负数 → 0）。 */
  public static int normalizePage(Integer page) {
    return page == null || page < 0 ? 0 : page;
  }

  /** 主体类型校验。 */
  public static String ownerTypeProblem(String value) {
    String ownerType = normalizeEnum(value);
    if (ownerType == null) return "被评主体类型必填（SPU / SKU）";
    if (!OWNER_TYPES.contains(ownerType)) return "被评主体类型只支持 SPU 或 SKU";
    return null;
  }

  /** 维度筛选校验。空 = 不筛。 */
  public static String dimensionProblem(String value) {
    String dimension = normalizeEnum(value);
    if (dimension == null) return null;
    if (!DIMENSION_NAMES.contains(dimension))
      return "维度不在词表里（可用：" + String.join(" / ", DIMENSION_NAMES) + "）";
    return null;
  }

  /** 分档筛选校验。空 = 不筛。 */
  public static String gradeProblem(String value) {
    String grade = normalizeEnum(value);
    if (grade == null) return null;
    if (!GRADES.contains(grade)) return "分档不在词表里（可用：" + String.join(" / ", GRADES) + "）";
    return null;
  }

  /** 排序字段白名单：只允许按这几列排，未知字段回落到总分。 */
  public static final Set<String> ORDER_FIELDS = Set.of("score", "missing", "owner", "updatedAt");

  /**
   * 排序字段规范化。
   *
   * <p>未知字段**回落到 score** 而不是拼进 SQL：排序字段会进入 {@code order by}， 未做白名单就等于一个注入口，而 ORDER BY 后面接的列名不能参数化。
   */
  public static String normalizeOrderBy(String value) {
    String field = emptyToNull(value);
    if (field == null) return "score";
    String normalized = field.trim().toLowerCase(Locale.ROOT);
    return ORDER_FIELDS.contains(normalized) ? normalized : "score";
  }

  /** LIKE 模式里的通配符转义。与 SupplierQuoteService 同一份实现。 */
  public static String escapeLike(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  /** 按维度顺序给检查项排序（评分卡里按维度分组展示）。 */
  public static List<String> dimensionOrder() {
    var codes = new ArrayList<String>();
    for (Dimension dimension : WEIGHTED_DIMENSIONS) codes.add(dimension.code());
    return codes;
  }

  /** 按维度顺序排序的维度得分列表。 */
  public static List<CompletenessScoreModels.CompletenessDimension> sortDimensions(
      List<CompletenessScoreModels.CompletenessDimension> dims) {
    var order = dimensionOrder();
    var sorted = new ArrayList<>(dims);
    sorted.sort(
        Comparator.comparingInt(
            dim -> {
              int index = order.indexOf(dim.dimension());
              return index < 0 ? order.size() : index;
            }));
    return sorted;
  }

  /** 缺失项按维度顺序 + 缺失码排序（清单里稳定输出，避免每次刷新顺序都变）。 */
  public static List<CompletenessScoreModels.CompletenessCheck> sortChecks(
      List<CompletenessScoreModels.CompletenessCheck> checks) {
    var order = dimensionOrder();
    var sorted = new ArrayList<>(checks);
    sorted.sort(
        Comparator.comparingInt(
                (CompletenessScoreModels.CompletenessCheck check) -> {
                  int index = order.indexOf(check.dimension());
                  return index < 0 ? order.size() : index;
                })
            .thenComparing(CompletenessScoreModels.CompletenessCheck::code)
            .thenComparing(check -> check.subjectLabel() == null ? "" : check.subjectLabel()));
    return sorted;
  }

  /** 构造检查项的便捷方法（服务层与单测共用，保证六格顺序一致）。 */
  public static CompletenessScoreModels.CompletenessCheck check(
      String code, String dimension, String subjectLabel, boolean satisfied) {
    return new CompletenessScoreModels.CompletenessCheck(
        code,
        dimension,
        missingSubjectType(code),
        subjectLabel,
        missingDetail(code, subjectLabel),
        missingRemedy(code),
        satisfied);
  }

  /** 构造一条满意状态的检查项（不出现在缺失清单里，但仍计入进度）。 */
  public static CompletenessScoreModels.CompletenessCheck satisfiedCheck(
      String code, String dimension, String subjectLabel, String detail) {
    return new CompletenessScoreModels.CompletenessCheck(
        code, dimension, missingSubjectType(code), subjectLabel, detail, null, true);
  }

  /** 维度得分 → 展示记录（按维度定义补齐权重、中文名与阻断链）。 */
  public static CompletenessScoreModels.CompletenessDimension dimensionScore(
      String code, long satisfied, long total) {
    Dimension definition = dimension(code);
    BigDecimal ratio =
        total <= 0
            ? BigDecimal.ONE
            : BigDecimal.valueOf(satisfied)
                .divide(BigDecimal.valueOf(total), SCORE_SCALE, RoundingMode.HALF_UP);
    return new CompletenessScoreModels.CompletenessDimension(
        definition.code(),
        definition.label(),
        definition.weight(),
        satisfied,
        total,
        ratio,
        total > 0 && satisfied >= total,
        definition.blockedFor());
  }

  /** 把维度定义按 code 建索引（服务层算完检查项后一次性组装）。 */
  public static Map<String, Dimension> dimensionIndex() {
    var index = new LinkedHashMap<String, Dimension>();
    for (Dimension dimension : WEIGHTED_DIMENSIONS) index.put(dimension.code(), dimension);
    return index;
  }
}
