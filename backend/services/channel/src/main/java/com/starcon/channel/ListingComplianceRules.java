package com.starcon.channel;

import static com.starcon.channel.ListingComplianceSourcePort.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 4.2.3.2 合规预检 的<b>纯规则</b>：把「草稿内容 + 上游读数」变成风险清单。
 *
 * <p>🔴 本类<b>不碰数据库、不碰 HTTP</b>，全部是纯函数，所以每一维度都能被单测直接打穿。
 * 它与 {@link ListingCompliancePrecheckService} 的分工是：
 * 服务层负责取数与落库，本类负责<b>怎么判</b>。
 *
 * <p>🔴 <b>本类里有一条贯穿全部维度的判定纪律</b>：
 *
 * <ul>
 *   <li><b>可验证的违规 ⇒ 阻断项（BLOCKING）</b>：我们手里有证据（词真的出现在这一栏里、
 *       宽度真的小于平台规格、文件真的不在了、授权真的没有）。
 *   <li><b>无法验证的疑虑 ⇒ 提醒项（WARNING）</b>：比如「这张图没按白底配方处理过」
 *       （它本来可能就是张白图，只是没有处理记录）、「授权快到期了」。
 *   <li><b>量不到 ⇒ 不是「合规」</b>：读不到上游时该维度是 UNKNOWN，
 *       <b>不产出任何风险、也不进已判定维度的分母</b>。把「查不到」当成「没违规」，
 *       等于把一个侵权商品放行 —— 这是本模块最贵的错误，所以代码里不许出现这种回退。
 * </ul>
 */
public final class ListingComplianceRules {
  private ListingComplianceRules() {}

  // ================================================================ 取值域

  /** 风险维度键（与 DB 的 dimension CHECK 逐字一致）。 */
  public static final List<String> DIMENSIONS =
      List.of(
          "PROHIBITED_WORD",
          "SENSITIVE_WORD",
          "SUPERLATIVE_WORD",
          "BRAND_INFRINGEMENT",
          "CATEGORY_REQUIRED",
          "IMAGE");

  /** 词的类型（与 DB 的 category CHECK 逐字一致）。 */
  public static final List<String> WORD_CATEGORIES = List.of("PROHIBITED", "SENSITIVE", "SUPERLATIVE");

  /** 风险等级。 */
  public static final List<String> RISK_LEVELS = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

  /** 处置。 */
  public static final List<String> SEVERITIES = List.of("BLOCKING", "WARNING");

  /** 结论三态。 */
  public static final List<String> CONCLUSIONS = List.of("PASS", "BLOCKED", "INCOMPLETE");

  /** 维度状态。 */
  public static final List<String> DIMENSION_STATES = List.of("CHECKED", "UNKNOWN");

  /** 词类型 → 风险维度。 */
  public static String dimensionOfWordCategory(String category) {
    return switch (category == null ? "" : category) {
      case "SENSITIVE" -> "SENSITIVE_WORD";
      case "SUPERLATIVE" -> "SUPERLATIVE_WORD";
      default -> "PROHIBITED_WORD";
    };
  }

  /** 维度中文名。 */
  public static String dimensionLabel(String dimension) {
    return switch (dimension == null ? "" : dimension) {
      case "PROHIBITED_WORD" -> "违禁词";
      case "SENSITIVE_WORD" -> "敏感词";
      case "SUPERLATIVE_WORD" -> "极限词";
      case "BRAND_INFRINGEMENT" -> "品牌侵权";
      case "CATEGORY_REQUIRED" -> "平台类目必填";
      case "IMAGE" -> "图片合规";
      default -> dimension;
    };
  }

  /** 编辑器字段键 → 人话名（命中位置用）。 */
  public static String fieldLabel(String fieldKey) {
    return switch (fieldKey == null ? "" : fieldKey) {
      case "TITLE" -> "标题";
      case "BULLETS" -> "卖点";
      case "DESCRIPTION" -> "描述";
      case "IMAGES" -> "图片";
      case "VIDEO" -> "视频";
      case "A_PLUS" -> "A+ 模块";
      case "CATEGORY_ATTRIBUTES" -> "类目属性";
      case "COMPLIANCE_STATEMENTS" -> "合规声明";
      case "VARIANT_DIMENSIONS" -> "变体维度";
      case "CATEGORY_PATH" -> "平台类目路径";
      case "BRAND" -> "品牌";
      case "SOURCE_PRODUCT" -> "源商品";
      default -> fieldKey;
    };
  }

  // ================================================================ 中间结构

  /**
   * 草稿里的一栏内容。
   *
   * @param key 字段键（TITLE / BULLETS / …）
   * @param texts 这一栏的文本片段（字符串字段 1 段；列表字段 N 段；对象字段按 key 逐个）
   * @param listLike 是不是列表型字段（决定 occurrenceIndex 有没有意义）
   * @param pairs 键值型字段（类目属性 / 合规声明）的键值
   */
  public record DraftField(String key, List<String> texts, boolean listLike, Map<String, String> pairs) {
    public String label() {
      return fieldLabel(key);
    }
  }

  /** 一条待落库的风险（落库前的中间形态）。 */
  public record RiskDraft(
      String dimension,
      String severity,
      String riskLevel,
      String code,
      String fieldKey,
      String fieldLabel,
      String matchedText,
      int occurrenceIndex,
      String ruleSource,
      String ruleOwnerModule,
      String suggestion,
      String message,
      String ownerModule,
      Map<String, Object> evidence) {

    public Map<String, Object> evidenceOrEmpty() {
      return evidence == null ? Map.of() : evidence;
    }
  }

  /** 词库一行（服务层从库里读出来交给规则）。 */
  public record WordEntry(
      String word,
      String displayWord,
      String category,
      String severity,
      String riskLevel,
      String ruleSource,
      String suggestion,
      String platformCode,
      String locale) {}

  /** 维度判定结论（服务层把它落进扫描的 dimension_summary）。 */
  public record DimensionOutcome(
      String dimension,
      String state,
      int riskCount,
      int blockingCount,
      int warningCount,
      String source,
      String ownerModule,
      String note) {}

  /** 一个维度的产出：风险 + 结论。 */
  public record DimensionResult(List<RiskDraft> risks, DimensionOutcome outcome) {
    public static DimensionResult checked(
        String dimension, List<RiskDraft> risks, String source, String note) {
      return new DimensionResult(risks, outcome(dimension, "CHECKED", risks, source, null, note));
    }

    public static DimensionResult unknown(String dimension, String source, String ownerModule, String note) {
      return new DimensionResult(
          List.of(), outcome(dimension, "UNKNOWN", List.of(), source, ownerModule, note));
    }

    private static DimensionOutcome outcome(
        String dimension,
        String state,
        List<RiskDraft> risks,
        String source,
        String ownerModule,
        String note) {
      int blocking = 0;
      int warning = 0;
      for (RiskDraft risk : risks) {
        if ("BLOCKING".equals(risk.severity())) {
          blocking++;
        } else {
          warning++;
        }
      }
      return new DimensionOutcome(
          dimension, state, risks.size(), blocking, warning, source, ownerModule, note);
    }
  }

  // ================================================================ 草稿内容提取

  /**
   * 从 4.2.1.1 的 {@code field_values}（jsonb）里抽出可扫描的文本片段。
   *
   * <p>🔴 形状与 4.2.1.1 写进去的完全一致（V194 触发器也按这个形状校验）：
   * 字符串 / 字符串数组 / 对象（键值）/ 对象数组（A+ 模块）。
   * **读不出来就当没有**（不编一个空字符串冒充有内容）。
   */
  public static List<DraftField> extractFields(Map<String, Object> fieldValues) {
    List<DraftField> fields = new ArrayList<>();
    if (fieldValues == null || fieldValues.isEmpty()) {
      return fields;
    }
    for (Map.Entry<String, Object> entry : fieldValues.entrySet()) {
      String key = entry.getKey();
      Object raw = entry.getValue();
      if (raw == null) {
        continue;
      }
      if (raw instanceof String text) {
        if (!text.isBlank()) {
          fields.add(new DraftField(key, List.of(text), false, Map.of()));
        }
        continue;
      }
      if (raw instanceof List<?> list) {
        List<String> texts = new ArrayList<>();
        for (Object item : list) {
          if (item instanceof String text) {
            if (!text.isBlank()) {
              texts.add(text);
            }
          } else if (item instanceof Map<?, ?> map) {
            // A+ 模块：正文与标题都是可扫描的文本
            Object heading = map.get("heading");
            Object body = map.get("body");
            if (heading instanceof String h && !h.isBlank()) {
              texts.add(h);
            }
            if (body instanceof String b && !b.isBlank()) {
              texts.add(b);
            }
          }
        }
        if (!texts.isEmpty()) {
          fields.add(new DraftField(key, texts, true, Map.of()));
        }
        continue;
      }
      if (raw instanceof Map<?, ?> map) {
        Map<String, String> pairs = new LinkedHashMap<>();
        for (Map.Entry<?, ?> pair : map.entrySet()) {
          if (pair.getKey() == null) {
            continue;
          }
          pairs.put(String.valueOf(pair.getKey()), pair.getValue() == null ? "" : String.valueOf(pair.getValue()));
        }
        if (!pairs.isEmpty()) {
          List<String> texts = new ArrayList<>();
          for (Map.Entry<String, String> pair : pairs.entrySet()) {
            if (!pair.getValue().isBlank()) {
              texts.add(pair.getValue());
            }
          }
          fields.add(new DraftField(key, texts, false, pairs));
        }
      }
    }
    return fields;
  }

  // ================================================================ 词命中

  /**
   * 🔴 词命中：大小写不敏感；ASCII 词要求词边界（{@code best} 不该命中 {@code bestseller}），
   * 中文词按子串匹配。
   *
   * <p>命中位置精确到<b>哪一栏 + 第几项</b>：{@code fieldKey} / {@code occurrenceIndex}。
   */
  public static List<RiskDraft> wordRisks(List<WordEntry> words, List<DraftField> fields) {
    List<RiskDraft> risks = new ArrayList<>();
    for (WordEntry word : words) {
      if (word.word() == null || word.word().isBlank()) {
        continue;
      }
      for (DraftField field : fields) {
        List<String> texts = field.texts();
        for (int index = 0; index < texts.size(); index++) {
          String text = texts.get(index);
          int at = indexOf(text, word.word());
          if (at < 0) {
            continue;
          }
          String matched = text.substring(at, Math.min(text.length(), at + word.word().length()));
          Map<String, Object> evidence = new LinkedHashMap<>();
          evidence.put("word", word.displayWord() == null ? word.word() : word.displayWord());
          evidence.put("offset", at);
          evidence.put("context", excerpt(text, at, word.word().length()));
          risks.add(
              new RiskDraft(
                  dimensionOfWordCategory(word.category()),
                  word.severity() == null ? "WARNING" : word.severity(),
                  word.riskLevel() == null ? "MEDIUM" : word.riskLevel(),
                  "WORD_HIT",
                  field.key(),
                  field.label(),
                  matched,
                  field.listLike() ? index : -1,
                  word.ruleSource() == null ? "租户违禁词库" : word.ruleSource(),
                  null,
                  word.suggestion() == null ? "" : word.suggestion(),
                  "内容"
                      + field.label()
                      + "命中"
                      + (word.displayWord() == null ? word.word() : word.displayWord())
                      + "（"
                      + word.category()
                      + "）",
                  "4.2.3.2",
                  evidence));
        }
      }
    }
    return risks;
  }

  /**
   * 大小写不敏感的子串查找；ASCII 词要求左右不是字母 / 数字。
   *
   * <p>🔴 中文没有词边界（"最好" 与 "最好的" 本来就共享子串），所以边界规则只对
   * 首尾是 ASCII 字母 / 数字的词生效。
   */
  static int indexOf(String text, String needle) {
    if (text == null || needle == null || text.isEmpty() || needle.isEmpty()) {
      return -1;
    }
    String haystack = text.toLowerCase(Locale.ROOT);
    String target = needle.toLowerCase(Locale.ROOT);
    boolean boundaryLeft = isAsciiWordChar(target.charAt(0));
    boolean boundaryRight = isAsciiWordChar(target.charAt(target.length() - 1));
    int from = 0;
    while (from <= haystack.length() - target.length()) {
      int at = haystack.indexOf(target, from);
      if (at < 0) {
        return -1;
      }
      boolean ok = true;
      if (boundaryLeft && at > 0 && isAsciiWordChar(haystack.charAt(at - 1))) {
        ok = false;
      }
      if (ok && boundaryRight && at + target.length() < haystack.length()
          && isAsciiWordChar(haystack.charAt(at + target.length()))) {
        ok = false;
      }
      if (ok) {
        return at;
      }
      from = at + 1;
    }
    return -1;
  }

  private static boolean isAsciiWordChar(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
  }

  private static String excerpt(String text, int at, int length) {
    int start = Math.max(0, at - 12);
    int end = Math.min(text.length(), at + length + 12);
    return (start > 0 ? "…" : "") + text.substring(start, end) + (end < text.length() ? "…" : "");
  }

  // ================================================================ 品牌侵权

  /**
   * 品牌侵权维度。
   *
   * @param brandName 草稿源商品的品牌名（空 = 不适用）
   * @param brandRow 租户品牌库里匹配到的那一个（null = 没登记）
   * @param authorizations 该品牌的授权凭证
   * @param expiryWarnDays 授权到期多少天以内算提醒
   */
  public static DimensionResult brandRisks(
      String brandName, BrandRow brandRow, List<BrandAuthorization> authorizations, int expiryWarnDays) {
    List<RiskDraft> risks = new ArrayList<>();
    if (brandName == null || brandName.isBlank()) {
      return DimensionResult.checked(
          "BRAND_INFRINGEMENT",
          risks,
          "catalog GET /catalog/skus/{id}（品牌名）",
          "该草稿没有关联带品牌的源商品，本维度不适用");
    }
    String source = "catalog GET /catalog/brands + /catalog/brands/{id}/authorizations（03 品牌授权状态）";
    if (brandRow == null) {
      risks.add(
          new RiskDraft(
              "BRAND_INFRINGEMENT",
              "BLOCKING",
              "CRITICAL",
              "BRAND_NOT_REGISTERED",
              "BRAND",
              "品牌",
              brandName,
              -1,
              "catalog GET /catalog/brands?keyword=（租户品牌库）",
              null,
              "在租户品牌库中登记该品牌并录入品牌授权凭证，或改用无品牌商品",
              "品牌「"
                  + brandName
                  + "」不在租户品牌库里，没有授权依据，不能作为品牌刊登",
              "4.2.3.2",
              Map.of("brandName", brandName)));
      return DimensionResult.checked("BRAND_INFRINGEMENT", risks, source, "品牌未登记，已判为阻断项");
    }
    if (!brandRow.publishable()) {
      String code = brandRow.blockCode() == null || brandRow.blockCode().isBlank()
          ? "BRAND_NOT_PUBLISHABLE"
          : brandRow.blockCode();
      risks.add(
          new RiskDraft(
              "BRAND_INFRINGEMENT",
              "BLOCKING",
              "HIGH",
              code,
              "BRAND",
              "品牌",
              brandRow.name(),
              -1,
              "catalog GET /catalog/brands/{id}/listing-eligibility（品牌刊登资格）",
              null,
              "按品牌阻断原因处理（续约授权 / 补齐凭证 / 停用该品牌）后再发布",
              brandRow.blockMessage() == null || brandRow.blockMessage().isBlank()
                  ? "品牌「" + brandRow.name() + "」当前不可刊登"
                  : brandRow.blockMessage(),
              "4.2.3.2",
              Map.of("brandId", brandRow.id(), "blockCode", code)));
    }
    if (authorizations == null || authorizations.isEmpty()) {
      risks.add(
          new RiskDraft(
              "BRAND_INFRINGEMENT",
              "BLOCKING",
              "CRITICAL",
              "BRAND_UNAUTHORIZED",
              "BRAND",
              "品牌",
              brandRow.name(),
              -1,
              "catalog GET /catalog/brands/{id}/authorizations（03 品牌授权状态）",
              null,
              "录入有效的品牌授权凭证（授权方 + 证书编号 + 有效期）",
              "品牌「" + brandRow.name() + "」没有任何授权凭证，不能以品牌名义刊登",
              "4.2.3.2",
              Map.of("brandId", brandRow.id(), "authorizationCount", 0)));
    } else {
      boolean anyValid = false;
      int soonest = Integer.MAX_VALUE;
      for (BrandAuthorization authorization : authorizations) {
        if (authorization.enabled() && authorization.validOn()) {
          anyValid = true;
          soonest = Math.min(soonest, authorization.daysToExpiry());
        }
      }
      if (!anyValid) {
        risks.add(
            new RiskDraft(
                "BRAND_INFRINGEMENT",
                "BLOCKING",
                "CRITICAL",
                "BRAND_UNAUTHORIZED",
                "BRAND",
                "品牌",
                brandRow.name(),
                -1,
                "catalog GET /catalog/brands/{id}/authorizations（03 品牌授权状态）",
                null,
                "录入或更新有效的品牌授权凭证（授权方 + 证书编号 + 有效期）",
                "品牌「" + brandRow.name() + "」的授权凭证全部无效或已过期",
                "4.2.3.2",
                Map.of("brandId", brandRow.id(), "authorizationCount", authorizations.size())));
      } else if (soonest <= expiryWarnDays) {
        risks.add(
            new RiskDraft(
                "BRAND_INFRINGEMENT",
                "WARNING",
                "MEDIUM",
                "BRAND_AUTHORIZATION_EXPIRING",
                "BRAND",
                "品牌",
                brandRow.name(),
                -1,
                "catalog GET /catalog/brands/{id}/authorizations（03 品牌授权状态）",
                null,
                "在授权到期前完成续约，避免到期后被拦截",
                "品牌「"
                    + brandRow.name()
                    + "」的授权将在 "
                    + (soonest < 0 ? "已到期" : soonest + " 天内")
                    + "到期",
                "4.2.3.2",
                Map.of("brandId", brandRow.id(), "daysToExpiry", soonest)));
      }
    }
    return DimensionResult.checked("BRAND_INFRINGEMENT", risks, source, "已按品牌授权状态判定");
  }

  // ================================================================ 平台类目必填

  /**
   * 平台类目必填维度。
   *
   * @param draftAttributes 草稿里已填的类目属性（键值）
   * @param required 4.2.3.1 解析出来的平台属性规则
   */
  public static DimensionResult categoryRisks(
      Map<String, String> draftAttributes, List<RequiredAttribute> required) {
    List<RiskDraft> risks = new ArrayList<>();
    String source =
        "4.2.3.1 平台类目映射（catalog GET /catalog/category-mappings/resolve）";
    if (required == null || required.isEmpty()) {
      return DimensionResult.checked(
          "CATEGORY_REQUIRED",
          risks,
          source,
          "该平台类目没有登记必填属性，本维度不适用");
    }
    Map<String, String> filled =
        draftAttributes == null ? Map.of() : draftAttributes;
    for (RequiredAttribute attribute : required) {
      String requirement = attribute.requirement() == null ? "" : attribute.requirement();
      String code = attribute.platformAttributeCode() == null ? "" : attribute.platformAttributeCode();
      String name =
          attribute.platformAttributeName() == null || attribute.platformAttributeName().isBlank()
              ? code
              : attribute.platformAttributeName();
      if ("REQUIRED".equals(requirement) && !attribute.mapped()) {
        risks.add(
            new RiskDraft(
                "CATEGORY_REQUIRED",
                "BLOCKING",
                "HIGH",
                "CATEGORY_REQUIRED_UNMAPPED",
                "CATEGORY_ATTRIBUTES",
                "类目属性",
                code,
                -1,
                source,
                "4.2.3.1",
                "在平台类目映射里给必填属性「" + name + "」绑定内部属性",
                "平台必填属性「"
                    + name
                    + "」还没有绑定内部属性，刊登表单里根本没有这一栏，运营填不了",
                "4.2.3.1",
                Map.of(
                    "platformAttributeCode", code,
                    "internalAttributeCode", String.valueOf(attribute.internalAttributeCode()))));
        continue;
      }
      String value = lookup(filled, attribute);
      boolean missing = value == null || value.isBlank();
      if ("REQUIRED".equals(requirement) && missing) {
        risks.add(
            new RiskDraft(
                "CATEGORY_REQUIRED",
                "BLOCKING",
                "HIGH",
                "CATEGORY_REQUIRED_MISSING",
                "CATEGORY_ATTRIBUTES",
                "类目属性",
                code,
                -1,
                source,
                null,
                "在类目属性里填写「" + name + "」",
                "平台类目必填属性「" + name + "」缺失",
                "4.2.3.2",
                Map.of("platformAttributeCode", code, "requirement", requirement)));
      } else if ("RECOMMENDED".equals(requirement) && missing) {
        risks.add(
            new RiskDraft(
                "CATEGORY_REQUIRED",
                "WARNING",
                "LOW",
                "CATEGORY_RECOMMENDED_MISSING",
                "CATEGORY_ATTRIBUTES",
                "类目属性",
                code,
                -1,
                source,
                null,
                "建议在类目属性里补上「" + name + "」",
                "平台类目推荐属性「" + name + "」未填写",
                "4.2.3.2",
                Map.of("platformAttributeCode", code, "requirement", requirement)));
      }
    }
    return DimensionResult.checked("CATEGORY_REQUIRED", risks, source, "已按平台类目必填清单判定");
  }

  /** 类目属性的取值：先按内部属性码找，再按平台属性码找（两种都认，避免运营换一种填法就被判缺失）。 */
  private static String lookup(Map<String, String> filled, RequiredAttribute attribute) {
    String internal = attribute.internalAttributeCode();
    if (internal != null && !internal.isBlank()) {
      String value = filled.get(internal);
      if (value != null) {
        return value;
      }
    }
    String platform = attribute.platformAttributeCode();
    if (platform != null && !platform.isBlank()) {
      return filled.get(platform);
    }
    return null;
  }

  // ================================================================ 图片合规

  /**
   * 图片合规维度。
   *
   * @param refs 草稿图片集里的引用（存储键或素材 id）
   * @param assets 媒体库里读到的素材
   * @param recipe 目标平台的图片处理配方（null = 没有登记该平台的图片规格）
   * @param histories 素材处理历史（素材 id → 处理任务）
   * @param expiryWarnDays 预留（当前未用）
   */
  public static DimensionResult imageRisks(
      List<String> refs,
      List<MediaAsset> assets,
      ImageRecipe recipe,
      Map<String, List<AssetJobRow>> histories) {
    List<RiskDraft> risks = new ArrayList<>();
    String source =
        "catalog GET /catalog/media（03 媒体库）+ GET /catalog/media/processing/recipes（目标平台图片规格）";
    if (refs == null || refs.isEmpty()) {
      return DimensionResult.checked("IMAGE", risks, source, "该草稿没有配图，本维度不适用");
    }
    if (assets == null) {
      return DimensionResult.unknown("IMAGE", source, "3.4.1.1", "媒体库读不到，图片是否合规未能判定");
    }
    if (recipe == null) {
      return DimensionResult.unknown(
          "IMAGE",
          source,
          "3.4.1.2",
          "目标平台没有登记图片处理配方，尺寸 / 白底 / 水印三项未能判定（不算通过）");
    }
    Map<String, MediaAsset> byKey = new LinkedHashMap<>();
    for (MediaAsset asset : assets) {
      if (asset.storageKey() != null) {
        byKey.put(asset.storageKey(), asset);
      }
      if (asset.id() != null) {
        byKey.put(asset.id(), asset);
      }
    }
    for (int index = 0; index < refs.size(); index++) {
      String ref = refs.get(index);
      MediaAsset asset = byKey.get(ref);
      if (asset == null) {
        risks.add(
            new RiskDraft(
                "IMAGE",
                "BLOCKING",
                "HIGH",
                "IMAGE_NOT_FOUND",
                "IMAGES",
                "图片",
                ref,
                index,
                "catalog GET /catalog/media?keyword=（按存储键检索素材）",
                null,
                "重新选择一张素材，或先在媒体库里补上这张图",
                "图片「" + ref + "」在媒体库里找不到，发布时素材不存在",
                "4.2.3.2",
                Map.of("reference", ref)));
        continue;
      }
      risks.addAll(assetRisks(asset, index, recipe, histories == null ? Map.of() : histories, source));
    }
    return DimensionResult.checked("IMAGE", risks, source, "已按目标平台图片规格判定");
  }

  private static List<RiskDraft> assetRisks(
      MediaAsset asset,
      int index,
      ImageRecipe recipe,
      Map<String, List<AssetJobRow>> histories,
      String source) {
    List<RiskDraft> risks = new ArrayList<>();
    String integrity = asset.integrityStatus() == null ? "UNKNOWN" : asset.integrityStatus();
    if (!"VERIFIED".equals(integrity)) {
      boolean blocking = !"UNKNOWN".equals(integrity);
      risks.add(
          new RiskDraft(
              "IMAGE",
              blocking ? "BLOCKING" : "WARNING",
              blocking ? "HIGH" : "MEDIUM",
              "IMAGE_INTEGRITY_" + integrity,
              "IMAGES",
              "图片",
              asset.storageKey(),
              index,
              "catalog GET /catalog/media（素材完整性状态）",
              null,
              "重新上传或重新校验该素材后再发布",
              blocking
                  ? "图片「"
                      + name(asset)
                      + "」的素材完整性状态是 "
                      + integrity
                      + "，文件本身有问题"
                  : "图片「" + name(asset) + "」还没有做过完整性校验",
              "4.2.3.2",
              Map.of("integrityStatus", integrity, "storageKey", String.valueOf(asset.storageKey()))));
    }
    BigDecimal targetWidth = recipe.targetWidthPx();
    BigDecimal targetHeight = recipe.targetHeightPx();
    if (targetWidth == null || targetHeight == null) {
      for (RecipeRatio ratio : safeRatios(recipe.ratios())) {
        if ("MAIN".equals(ratio.label()) || ratio.label() == null) {
          targetWidth = ratio.widthPx();
          targetHeight = ratio.heightPx();
          break;
        }
      }
    }
    if (targetWidth != null && targetHeight != null) {
      BigDecimal width = asset.widthPx();
      BigDecimal height = asset.heightPx();
      if (width == null || height == null) {
        risks.add(
            new RiskDraft(
                "IMAGE",
                "WARNING",
                "MEDIUM",
                "IMAGE_SIZE_UNKNOWN",
                "IMAGES",
                "图片",
                asset.storageKey(),
                index,
                source,
                null,
                "补齐素材的宽高信息（重新探测）",
                "图片「" + name(asset) + "」没有宽高信息，尺寸这一项判不了",
                "4.2.3.2",
                Map.of("storageKey", String.valueOf(asset.storageKey()))));
      } else if (width.compareTo(targetWidth) < 0 || height.compareTo(targetHeight) < 0) {
        risks.add(
            new RiskDraft(
                "IMAGE",
                "BLOCKING",
                "HIGH",
                "IMAGE_TOO_SMALL",
                "IMAGES",
                "图片",
                asset.storageKey(),
                index,
                source + "（配方 " + recipe.code() + " 的目标尺寸）",
                null,
                "换成不小于 " + targetWidth.stripTrailingZeros().toPlainString() + "×"
                    + targetHeight.stripTrailingZeros().toPlainString() + " 的素材，或先做一次图片处理",
                "图片「"
                    + name(asset)
                    + "」尺寸 "
                    + width.stripTrailingZeros().toPlainString()
                    + "×"
                    + height.stripTrailingZeros().toPlainString()
                    + "，小于目标平台的 "
                    + targetWidth.stripTrailingZeros().toPlainString()
                    + "×"
                    + targetHeight.stripTrailingZeros().toPlainString(),
                "4.2.3.2",
                Map.of(
                    "widthPx", width,
                    "heightPx", height,
                    "targetWidthPx", targetWidth,
                    "targetHeightPx", targetHeight,
                    "recipeCode", String.valueOf(recipe.code()))));
      }
    }
    List<AssetJobRow> jobs = histories.get(asset.id());
    boolean processedWithWhite = false;
    boolean processedWithWatermark = false;
    if (jobs != null) {
      for (AssetJobRow job : jobs) {
        if (!"SUCCEEDED".equals(job.status())) {
          continue;
        }
        if (recipe.whiteBackground() && recipe.id() != null && recipe.id().equals(job.recipeId())) {
          processedWithWhite = true;
        }
        if (recipe.watermarkText() != null && !recipe.watermarkText().isBlank()
            && recipe.id() != null && recipe.id().equals(job.recipeId())) {
          processedWithWatermark = true;
        }
      }
    }
    if (recipe.whiteBackground() && !processedWithWhite) {
      risks.add(
          new RiskDraft(
              "IMAGE",
              "WARNING",
              "MEDIUM",
              "IMAGE_WHITE_BACKGROUND_MISSING",
              "IMAGES",
              "图片",
              asset.storageKey(),
              index,
              "catalog GET /catalog/media/processing/recipes（配方白底要求）+ 素材处理历史",
              null,
              "用该平台的图片处理配方跑一次白底处理，或换成白底素材",
              "目标平台要求白底，图片「"
                  + name(asset)
                  + "」没有按白底配方处理过的记录（它本来可能就是白图，需人工确认）",
              "4.2.3.2",
              Map.of("recipeCode", String.valueOf(recipe.code()), "storageKey", String.valueOf(asset.storageKey()))));
    }
    if (processedWithWatermark && index == 0) {
      risks.add(
          new RiskDraft(
              "IMAGE",
              "WARNING",
              "MEDIUM",
              "IMAGE_MAIN_WATERMARK",
              "IMAGES",
              "图片",
              asset.storageKey(),
              index,
              "catalog 素材处理历史（该素材是按带水印配方处理出来的）",
              null,
              "主图换一张不带水印的素材",
              "第一张图（主图）带水印，多数平台不允许主图带水印",
              "4.2.3.2",
              Map.of("recipeCode", String.valueOf(recipe.code()), "storageKey", String.valueOf(asset.storageKey()))));
    }
    if (asset.altText() == null || asset.altText().isBlank()) {
      risks.add(
          new RiskDraft(
              "IMAGE",
              "WARNING",
              "LOW",
              "IMAGE_ALT_TEXT_MISSING",
              "IMAGES",
              "图片",
              asset.storageKey(),
              index,
              "catalog GET /catalog/media（素材替代文本）",
              null,
              "在媒体库里给这张图补上替代文本",
              "图片「" + name(asset) + "」没有替代文本，平台与无障碍要求通常强制",
              "4.2.3.2",
              Map.of("storageKey", String.valueOf(asset.storageKey()))));
    }
    return risks;
  }

  private static List<RecipeRatio> safeRatios(List<RecipeRatio> ratios) {
    return ratios == null ? List.of() : ratios;
  }

  private static String name(MediaAsset asset) {
    if (asset.name() != null && !asset.name().isBlank()) {
      return asset.name();
    }
    return asset.storageKey() == null ? "(未命名)" : asset.storageKey();
  }

  // ================================================================ 结论

  /**
   * 汇总结论。
   *
   * <p>🔴 三态：
   *
   * <ul>
   *   <li>{@code BLOCKED} —— 有阻断项。
   *   <li>{@code PASS} —— 没有阻断项<b>且</b>全部维度都已判定。
   *   <li>{@code INCOMPLETE} —— 当前没有已知阻断项，但有维度没量到 ⇒ <b>不能说「合规通过」</b>。
   * </ul>
   */
  public static String conclusion(int blockingCount, int unknownDimensions) {
    if (blockingCount > 0) {
      return "BLOCKED";
    }
    return unknownDimensions > 0 ? "INCOMPLETE" : "PASS";
  }
}
