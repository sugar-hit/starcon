package com.starcon.channel;

import com.starcon.channel.ListingEditorFieldRules.Requirement;
import com.starcon.channel.ListingEditorFieldRules.ValueShape;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.starcon.channel.ListingEditorFieldRules.LABELS;

/**
 * 16.2.2.1 合规预检的<b>规则层</b>：维度声明、权重、以及逐检查项的判定。
 *
 * <p>本类不碰数据库、不碰 HTTP，**可单测**（与 {@link ListingEditorFieldRules} 同一手法）。
 *
 * <p>🔴🔴 本类只有三样东西是"声明"，其余全是"判定"：
 *
 * <ol>
 *   <li><b>维度与权重</b>（{@link #DIMENSIONS}）：六个维度是蓝图职责句点名的
 *       「内容违规、认证缺失、尺寸规格不符、材质与描述不符、危险品与运输限制、标签与说明书要求」，
 *       权重是<b>本模块的治理政策</b>，**不是规则**。权重合计为 1 由类初始化块断言 ——
 *       改权重忘了配平会在启动时炸，而不是上线后让全店分数静默偏移。
 *   <li><b>各维度的可测性</b>：哪个维度现在量得到、量不到归谁。这一条是本模块最容易做假的地方，
 *       所以它写在代码里、也写在能力声明里，两边必须一致。
 *   <li><b>检查项的判定</b>：判定依据全部来自<b>已交付的数据源</b>（平台字段要求注册表、
 *       平台能力矩阵、内容草稿、catalog 的品牌授权），**没有一条硬编码的合规细则**。
 * </ol>
 *
 * <p>🔴 <b>「量不到」不是 0 分</b>：量不到的检查项 state=UNKNOWN，量不到的维度 state=UNKNOWN、
 * score=null，<b>都不进分母</b>。全维度不可知时总分给 null（UNKNOWN），不是 0。
 *
 * <p>🔴 <b>刻意复用 {@link ListingEditorFieldRules} 的形状</b>（{@link Requirement} /
 * {@link ValueShape} / {@link LABELS}）：本模块读的注册表与编辑器读的是<b>同一张表</b>
 * （{@code platform_listing_field_requirement}），"长度超限"在两个模块里必须是<b>同一个意思</b>。
 * 抄一份形状出来只会让两边的口径慢慢漂。
 */
public final class CompliancePrecheckRules {
  private CompliancePrecheckRules() {}

  // ================================================================ 词表

  /** 维度编码（界面按这个顺序渲染）。 */
  public static final List<String> DIMENSION_ORDER =
      List.of("CONTENT", "CERTIFICATION", "SIZE_SPEC", "MATERIAL", "HAZMAT", "LABELING");

  /** 检查项状态取值。 */
  public static final List<String> CHECK_STATES = List.of("PASS", "VIOLATION", "UNKNOWN");

  /** 严重度取值。 */
  public static final List<String> SEVERITIES = List.of("BLOCKING", "WARNING", "INFO");

  /** 两种触发语义。 */
  public static final List<String> TRIGGER_KINDS = List.of("DRAFT_SAVE", "PUBLISH_GATE");

  /** 本模块自身（量得到的检查项归它）。 */
  public static final String OWNER_SELF = "16.2.2.1";

  /** 平台能力矩阵的归属模块（能力键没声明时归它）。 */
  public static final String OWNER_CAPABILITY_MATRIX = "2.1.1.2";

  /** 4.2.3.2 合规预检（违禁词 / 侵权）—— 同批并行开发，**此刻不可用**。 */
  public static final String OWNER_LISTING_PRECHECK = "4.2.3.2";

  /** 16.2.1.1 规则库 —— 未交付，尺寸 / 材质 / 危险品 / 标签的细则归它。 */
  public static final String OWNER_RULE_LIBRARY = "16.2.1.1";

  /** 16.2.2.2 认证与资质 —— 未交付，认证档案归它。 */
  public static final String OWNER_CERTIFICATION = "16.2.2.2";

  /** 03 品牌（授权与刊登资格）—— catalog 侧，3.2.1.2。 */
  public static final String OWNER_BRAND = "3.2.1.2";

  /** 4.2.1.1 刊登编辑器（草稿自身的内容与源商品关联）。 */
  public static final String OWNER_EDITOR = "4.2.1.1";

  // ================================================================ 维度声明

  /**
   * 一个维度的声明。
   *
   * @param code 维度编码
   * @param label 中文名
   * @param weight 权重（0..1，比值；六个维度合计为 1）
   * @param basisOwnerModule 该维度的判定依据归谁（null = 本模块自己能判）
   * @param basisNote 量不到时的原因说明（界面原样显示）
   */
  public record Dimension(
      String code, String label, BigDecimal weight, String basisOwnerModule, String basisNote) {}

  /**
   * 六个维度。
   *
   * <p>🔴 <b>只有 CONTENT 现在量得到</b>：它的依据是已交付的平台字段要求注册表 +
   * 平台能力矩阵 + 内容草稿 + catalog 的品牌授权。其余五个维度的细则来源都未交付
   * （16.2.1.1 规则库 / 16.2.2.2 认证档案），**本模块不硬编码任何细则**，
   * 它们在报告里是 UNKNOWN 且不计入分母。
   */
  public static final List<Dimension> DIMENSIONS =
      List.of(
          new Dimension(
              "CONTENT",
              "内容合规",
              new BigDecimal("0.30000000"),
              null,
              "依据 = 平台字段要求注册表（V194）+ 平台能力矩阵（2.1.1.2）+ 内容草稿（4.2.1.1）"
                  + "+ 品牌授权与刊登资格（catalog 3.2.1.2）。"),
          new Dimension(
              "CERTIFICATION",
              "认证缺失",
              new BigDecimal("0.20000000"),
              OWNER_CERTIFICATION,
              "🔴 量不到：产品认证档案（CE / FCC / RoHS / PSE / FDA / CCC 等）由 16.2.2.2 认证与资质管理，"
                  + "该模块未交付 ⇒ 没有任何认证来源可比对。本维度给 UNKNOWN 且**不计入总分分母**"
                  + "（量不到不是 0 分）。接入 16.2.2.2 后自动可评。"),
          new Dimension(
              "SIZE_SPEC",
              "尺寸规格不符",
              new BigDecimal("0.15000000"),
              OWNER_RULE_LIBRARY,
              "🔴 量不到：平台对尺寸规格的合规细则归 16.2.1.1 规则库（禁限售与规格细则），"
                  + "该模块未交付 ⇒ 没有可版本化的细则可比对。本模块**不硬编码尺寸细则**，"
                  + "给 UNKNOWN 且不计入分母。"),
          new Dimension(
              "MATERIAL",
              "材质与描述不符",
              new BigDecimal("0.15000000"),
              OWNER_RULE_LIBRARY,
              "🔴 量不到：材质与描述的一致性细则（材质要求是规则库的一类规则）归 16.2.1.1，"
                  + "该模块未交付。本模块**不硬编码材质细则**，给 UNKNOWN 且不计入分母。"),
          new Dimension(
              "HAZMAT",
              "危险品与运输限制",
              new BigDecimal("0.10000000"),
              OWNER_RULE_LIBRARY,
              "🔴 量不到：禁限售与危险品运输限制的细则归 16.2.1.1 规则库，该模块未交付。"
                  + "本模块**不硬编码危险品清单**，给 UNKNOWN 且不计入分母。"),
          new Dimension(
              "LABELING",
              "标签与说明书要求",
              new BigDecimal("0.10000000"),
              OWNER_RULE_LIBRARY,
              "🔴 量不到：标签与说明书要求的细则归 16.2.1.1 规则库（标签要求是规则库的一类规则），"
                  + "该模块未交付。本模块**不硬编码标签细则**，给 UNKNOWN 且不计入分母。"));

  static {
    BigDecimal sum = BigDecimal.ZERO;
    for (Dimension dimension : DIMENSIONS) {
      sum = sum.add(dimension.weight());
    }
    if (sum.compareTo(BigDecimal.ONE) != 0) {
      throw new IllegalStateException(
          "16.2.2.1 维度权重合计必须是 1，实际是 " + sum + " —— 改权重忘了配平会让总分静默偏移");
    }
    if (DIMENSIONS.size() != DIMENSION_ORDER.size()) {
      throw new IllegalStateException("16.2.2.1 维度顺序表与维度声明数量对不上");
    }
  }

  /** 按编码取维度声明。 */
  public static Dimension dimension(String code) {
    for (Dimension dimension : DIMENSIONS) {
      if (dimension.code().equals(code)) {
        return dimension;
      }
    }
    throw new IllegalArgumentException("未知维度：" + code);
  }

  // ================================================================ 检查结果

  /**
   * 一条检查结果。
   *
   * @param dimensionCode 所属维度
   * @param checkCode 检查项编码
   * @param subjectRef 命中位置（字段键 / 能力键 / 品牌 id）
   * @param location 人话位置
   * @param severity BLOCKING / WARNING / INFO
   * @param state PASS / VIOLATION / UNKNOWN
   * @param message 描述
   * @param suggestion 整改建议（**量到的也必须有**）
   * @param ownerModule 归属模块
   * @param evidence 证据（实际值 vs 期望值）
   */
  public record CheckResult(
      String dimensionCode,
      String checkCode,
      String subjectRef,
      String location,
      String severity,
      String state,
      String message,
      String suggestion,
      String ownerModule,
      Map<String, Object> evidence) {}

  // ================================================================ 构造器

  private static Map<String, Object> evidence(Object... pairs) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      result.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    return result;
  }

  private static CheckResult pass(
      String dimension, String code, String subject, String location, String message, String owner) {
    return new CheckResult(
        dimension, code, subject, location, "INFO", "PASS", message, "", owner, evidence());
  }

  private static CheckResult violation(
      String dimension,
      String code,
      String subject,
      String location,
      String severity,
      String message,
      String suggestion,
      String owner,
      Map<String, Object> evidence) {
    return new CheckResult(
        dimension, code, subject, location, severity, "VIOLATION", message, suggestion, owner, evidence);
  }

  private static CheckResult unknown(
      String dimension,
      String code,
      String subject,
      String location,
      String message,
      String suggestion,
      String owner,
      Map<String, Object> evidence) {
    return new CheckResult(
        dimension, code, subject, location, "INFO", "UNKNOWN", message, suggestion, owner, evidence);
  }

  /**
   * 🔴 一个「量不到」的维度级检查项。
   *
   * <p>五个维度（认证 / 尺寸 / 材质 / 危险品 / 标签）的细则来源都未交付，所以它们<b>各自</b>
   * 带一条 UNKNOWN 检查项 —— 不是 0 分、也不是被悄悄丢掉。归属模块与原因写在里面，
   * 界面原样显示"这一维度量不到，归谁管"。
   */
  public static CheckResult unmeasurable(Dimension dimension) {
    return new CheckResult(
        dimension.code(),
        "DIMENSION_NOT_MEASURABLE",
        "",
        dimension.label(),
        "INFO",
        "UNKNOWN",
        dimension.basisNote(),
        "接入 "
            + dimension.basisOwnerModule()
            + " 之后重跑一次体检，这一维度才能得分；"
            + "在那之前它**不计入总分分母**（量不到不是 0 分）",
        dimension.basisOwnerModule(),
        evidence("ownerModule", dimension.basisOwnerModule(), "available", false));
  }

  // ================================================================ 内容维度判定

  /**
   * 内容维度的判定输入。
   *
   * @param requirements 平台字段要求（已按"就近覆盖"解出唯一一行）
   * @param shapes 草稿各栏的形状摘要
   * @param capabilities 平台能力矩阵（键缺失 = 未声明，不是 false）
   * @param brand 品牌引用读出结果
   * @param authorizations 品牌授权读出结果
   * @param eligibility 品牌刊登资格读出结果
   */
  public record ContentInput(
      List<Requirement> requirements,
      Map<String, ValueShape> shapes,
      Map<String, Boolean> capabilities,
      CompliancePrecheckSourcePort.BrandReference brand,
      CompliancePrecheckSourcePort.BrandAuthorizations authorizations,
      CompliancePrecheckSourcePort.BrandEligibility eligibility) {}

  /**
   * 🔴 内容维度判定。
   *
   * <p>判定依据全部来自已交付的注册表 / 能力矩阵 / 草稿内容，<b>没有一条硬编码的合规细则</b>：
   *
   * <ul>
   *   <li>平台字段要求注册表（V194）—— 必填 / 长度上下限 / 条数上下限 / 能力门控 / 明确不支持。
   *   <li>平台能力矩阵（2.1.1.2）—— 能力键<b>缺失</b>时是 UNKNOWN（不是 false、不是"没约束"）。
   *   <li>内容草稿（4.2.1.1）—— 被检的内容本身。
   *   <li>catalog 品牌授权与刊登资格（3.2.1.2）—— 品牌这一关。
   * </ul>
   *
   * <p>🔴 <b>违禁词 / 极限词 / 侵权扫描不在这里</b>：那归 4.2.3.2（同批并行开发，此刻不可用），
   * 本模块出的是一条 UNKNOWN + 归属模块，而不是自己偷偷做一份词库扫描。
   */
  public static List<CheckResult> evaluateContent(ContentInput input) {
    List<CheckResult> results = new ArrayList<>();

    for (Requirement requirement : input.requirements()) {
      results.addAll(evaluateField(requirement, input));
    }

    // 🔴 违禁词 / 极限词 / 品牌侵权扫描：归 4.2.3.2，本模块不代劳。
    results.add(
        unknown(
            "CONTENT",
            "CONTENT_RULE_LIBRARY",
            "WORD_AND_INFRINGEMENT",
            "标题 / 要点 / 描述 / 图片",
            "违禁词、极限词与品牌侵权扫描归 4.2.3.2（同批并行开发，此刻不可用）",
            "接入 4.2.3.2 的风险清单后，本项自动可评；在此之前这一维度只是**部分**被体检过",
            OWNER_LISTING_PRECHECK,
            evidence("ownerModule", OWNER_LISTING_PRECHECK, "available", false)));

    results.add(evaluateBrand(input));
    return results;
  }

  /** 一栏的判定。 */
  private static List<CheckResult> evaluateField(Requirement requirement, ContentInput input) {
    List<CheckResult> results = new ArrayList<>();
    String field = requirement.field();
    String label = LABELS.getOrDefault(field, field);
    ValueShape shape =
        input.shapes().getOrDefault(field, new ValueShape(false, false, 0, 0));
    boolean present = shape.present();
    boolean filled = shape.filled();

    // 🔴 (1) 细则归别的模块（注册表里写了 constraint_owner_module）⇒ 这一栏量不到。
    //    那正是"细则归 4.2.3.1 / 2.1.1.2 / 4.1.2.1"的登记位置 —— 本模块不代它裁决。
    String owner = requirement.constraintOwnerModule();
    if (owner != null && !owner.isBlank()) {
      results.add(
          unknown(
              "CONTENT",
              "FIELD_DETAIL_DELEGATED",
              field,
              label,
              label + "的合规细则归 " + owner + "，本模块不代它裁决",
              "接入 " + owner + " 的细则后这一栏才能判定；在那之前它**不计入得分分母**",
              owner,
              evidence("field", field, "ownerModule", owner)));
      return results;
    }

    // 🔴 (2) 能力门控。能力键**缺失** = 平台没声明 ⇒ UNKNOWN（不是 false、不是"没约束"）。
    String capability = requirement.requiresCapability();
    if (capability != null && !capability.isBlank()) {
      Boolean supported = input.capabilities().get(capability);
      if (supported == null) {
        results.add(
            unknown(
                "CONTENT",
                "FIELD_CAPABILITY_UNKNOWN",
                field,
                label,
                "能力矩阵没有声明 " + capability + "，无法判定平台是否支持" + label,
                "在 2.1.1.2 平台能力矩阵里登记 " + capability + " 之后这一栏才能判定",
                OWNER_CAPABILITY_MATRIX,
                evidence("field", field, "capability", capability, "declared", false)));
        return results;
      }
      if (!supported) {
        if (filled) {
          results.add(
              violation(
                  "CONTENT",
                  "FIELD_CAPABILITY_UNSUPPORTED",
                  field,
                  label,
                  "BLOCKING",
                  label + "要求平台能力 " + capability + "，能力矩阵声明平台不支持，内容里却有这一栏",
                  "删掉" + label + "，或换一个支持 " + capability + " 的平台",
                  OWNER_SELF,
                  evidence("field", field, "capability", capability, "declared", true, "actual", "used")));
        } else {
          results.add(
              pass(
                  "CONTENT",
                  "FIELD_CAPABILITY_UNSUPPORTED",
                  field,
                  label,
                  label + "不需要（平台不支持 " + capability + "，内容里也没填）",
                  OWNER_SELF));
        }
        return results;
      }
    }

    // 🔴 (3) 平台明确不支持这一栏：内容里却有 ⇒ 违规。
    if ("UNSUPPORTED".equals(requirement.requirement())) {
      if (filled) {
        results.add(
            violation(
                "CONTENT",
                "FIELD_UNSUPPORTED",
                field,
                label,
                "BLOCKING",
                "平台明确不支持" + label + "，草稿里却填了内容",
                "删掉" + label + "，平台不接受这一栏",
                OWNER_SELF,
                evidence("field", field, "requirement", "UNSUPPORTED", "actual", "filled")));
      } else {
        results.add(
            pass(
                "CONTENT",
                "FIELD_UNSUPPORTED",
                field,
                label,
                label + "没填（平台不支持这一栏，正确）",
                OWNER_SELF));
      }
      return results;
    }

    // 🔴 (4) 必填。
    if ("REQUIRED".equals(requirement.requirement()) && !filled) {
      results.add(
          violation(
              "CONTENT",
              "FIELD_REQUIRED_MISSING",
              field,
              label,
              "BLOCKING",
              label + "是必填项，当前为空",
              "补齐" + label + "，否则平台会拒收这条 listing",
              OWNER_SELF,
              evidence("field", field, "requirement", "REQUIRED", "actual", "empty")));
      return results;
    }

    int before = results.size();

    // 🔴 (5) 长度上下限。
    if (requirement.maxLength() != null && present && shape.textLength() > requirement.maxLength()) {
      results.add(
          violation(
              "CONTENT",
              "FIELD_MAX_LENGTH",
              field,
              label,
              "BLOCKING",
              label + "长度 " + shape.textLength() + " 超出平台上限 " + requirement.maxLength(),
              "把" + label + "删到 " + requirement.maxLength() + " 字以内",
              OWNER_SELF,
              evidence(
                  "field", field,
                  "limit", requirement.maxLength(),
                  "actual", shape.textLength())));
    } else if (requirement.minLength() != null
        && filled
        && shape.textLength() < requirement.minLength()) {
      results.add(
          violation(
              "CONTENT",
              "FIELD_MIN_LENGTH",
              field,
              label,
              "BLOCKING",
              label + "长度 " + shape.textLength() + " 短于平台下限 " + requirement.minLength(),
              "把" + label + "补到 " + requirement.minLength() + " 字以上",
              OWNER_SELF,
              evidence(
                  "field", field,
                  "limit", requirement.minLength(),
                  "actual", shape.textLength())));
    }

    // 🔴 (6) 条数上下限。
    if (requirement.maxItems() != null && shape.itemCount() > requirement.maxItems()) {
      results.add(
          violation(
              "CONTENT",
              "FIELD_MAX_ITEMS",
              field,
              label,
              "WARNING",
              label + "条数 " + shape.itemCount() + " 超出平台上限 " + requirement.maxItems(),
              "删到 " + requirement.maxItems() + " 条以内",
              OWNER_SELF,
              evidence(
                  "field", field,
                  "limit", requirement.maxItems(),
                  "actual", shape.itemCount())));
    } else if (requirement.minItems() != null
        && filled
        && shape.itemCount() < requirement.minItems()) {
      results.add(
          violation(
              "CONTENT",
              "FIELD_MIN_ITEMS",
              field,
              label,
              "BLOCKING",
              label + "条数 " + shape.itemCount() + " 少于平台下限 " + requirement.minItems(),
              "至少补到 " + requirement.minItems() + " 条",
              OWNER_SELF,
              evidence(
                  "field", field,
                  "limit", requirement.minItems(),
                  "actual", shape.itemCount())));
    }

    if (results.size() == before) {
      results.add(
          pass(
              "CONTENT",
              "FIELD_OK",
              field,
              label,
              label + "符合平台已登记的要求",
              OWNER_SELF));
    }
    return results;
  }

  /**
   * 🔴 品牌这一关：有没有授权、能不能刊登。
   *
   * <p>判定顺序：先看 catalog 算好的刊登资格（3.2.1.2），读不到就看授权凭证列表，
   * 两个都读不到才记 UNKNOWN —— <b>读不到绝不折成"通过"</b>。
   */
  private static CheckResult evaluateBrand(ContentInput input) {
    CompliancePrecheckSourcePort.BrandReference brand = input.brand();
    CompliancePrecheckSourcePort.BrandAuthorizations authorizations = input.authorizations();
    CompliancePrecheckSourcePort.BrandEligibility eligibility = input.eligibility();

    if (brand == null || !brand.available()) {
      String reason = brand == null ? "没有读到品牌信息" : brand.note();
      return unknown(
          "CONTENT",
          "BRAND_AUTHORIZATION",
          "",
          "品牌",
          "读不到品牌信息：" + reason,
          "确认源商品的品牌名能在 3.2.1.2 的品牌字典里查到，再重跑一次体检",
          OWNER_BRAND,
          evidence("available", false, "reason", reason));
    }

    if (eligibility != null && eligibility.available()) {
      if (eligibility.publishable()) {
        return pass(
            "CONTENT",
            "BRAND_AUTHORIZATION",
            brand.brandId(),
            "品牌「" + brand.name() + "」",
            "品牌已获授权，允许刊登（有效授权 " + eligibility.validAuthorizationCount() + " 条）",
            OWNER_SELF);
      }
      String message =
          eligibility.blockMessage() == null || eligibility.blockMessage().isBlank()
              ? "品牌当前不允许刊登"
              : eligibility.blockMessage();
      return violation(
          "CONTENT",
          "BRAND_AUTHORIZATION",
          brand.brandId(),
          "品牌「" + brand.name() + "」",
          "BLOCKING",
          "品牌不允许刊登：" + message
              + (eligibility.blockCode() == null || eligibility.blockCode().isBlank()
                  ? ""
                  : "（原因码 " + eligibility.blockCode() + "）"),
          "先在 3.2.1.2 补齐或续期该品牌的授权凭证，再刊登这条 listing",
          OWNER_BRAND,
          evidence(
              "brandId", brand.brandId(),
              "publishable", false,
              "blockCode", eligibility.blockCode(),
              "validAuthorizationCount", eligibility.validAuthorizationCount()));
    }

    if (authorizations != null && authorizations.available()) {
      long valid =
          authorizations.authorizations().stream()
              .filter(a -> a.enabled() && a.validOn())
              .count();
      if (valid > 0) {
        return pass(
            "CONTENT",
            "BRAND_AUTHORIZATION",
            brand.brandId(),
            "品牌「" + brand.name() + "」",
            "品牌有 " + valid + " 条有效授权（刊登资格判定读不到，按授权凭证判）",
            OWNER_SELF);
      }
      return violation(
          "CONTENT",
          "BRAND_AUTHORIZATION",
          brand.brandId(),
          "品牌「" + brand.name() + "」",
          "BLOCKING",
          "品牌当前没有有效授权凭证",
          "先在 3.2.1.2 补齐该品牌的授权凭证，再刊登这条 listing",
          OWNER_BRAND,
          evidence("brandId", brand.brandId(), "validAuthorizationCount", 0));
    }

    String reason =
        eligibility != null && !eligibility.note().isBlank()
            ? eligibility.note()
            : (authorizations == null ? "没有读到品牌授权" : authorizations.note());
    return unknown(
        "CONTENT",
        "BRAND_AUTHORIZATION",
        brand.brandId(),
        "品牌「" + brand.name() + "」",
        "读不到品牌的授权与刊登判定：" + reason,
        "等 catalog 可用后重跑一次体检；在那之前这一项**不计入得分分母**",
        OWNER_BRAND,
        evidence("brandId", brand.brandId(), "available", false, "reason", reason));
  }

  // ================================================================ 维度得分

  /**
   * 一个维度的得分结果。
   *
   * @param dimension 维度声明
   * @param results 该维度的检查结果
   */
  public record DimensionOutcome(
      Dimension dimension,
      BigDecimal score,
      String state,
      int checkItemCount,
      int passedItemCount,
      int failedItemCount,
      int unknownItemCount,
      String ownerModule,
      String unavailableReason) {}

  /**
   * 🔴 维度得分。
   *
   * <p>规则：<b>不可知分量不计入分母</b>。分母 = passed + failed（UNKNOWN 项不进分母）。
   * 分母为 0 ⇒ 该维度 UNKNOWN、得分 null（<b>不是 0 分</b>）。
   */
  public static DimensionOutcome score(Dimension dimension, List<CheckResult> results) {
    int passed = 0;
    int failed = 0;
    int unknown = 0;
    for (CheckResult result : results) {
      switch (result.state()) {
        case "PASS" -> passed++;
        case "VIOLATION" -> failed++;
        default -> unknown++;
      }
    }
    int known = passed + failed;
    if (known == 0) {
      // 量不到：得分 null，不是 0。
      String reason = "本维度当前没有任何可判定的检查项";
      String owner = dimension.basisOwnerModule();
      for (CheckResult result : results) {
        if ("UNKNOWN".equals(result.state())) {
          reason = result.message();
          if (result.ownerModule() != null && !result.ownerModule().isBlank()) {
            owner = result.ownerModule();
          }
          break;
        }
      }
      if (owner == null || owner.isBlank()) {
        owner = OWNER_CAPABILITY_MATRIX;
      }
      return new DimensionOutcome(
          dimension, null, "UNKNOWN", results.size(), passed, failed, unknown, owner, reason);
    }
    BigDecimal score =
        BigDecimal.valueOf(passed).divide(BigDecimal.valueOf(known), 4, java.math.RoundingMode.HALF_UP);
    return new DimensionOutcome(
        dimension, score, "SCORED", results.size(), passed, failed, unknown, null, "");
  }

  /**
   * 🔴 总体得分。
   *
   * <p>规则：**量不到的维度不计入分母**（权重也不进）。分母 = 被评出分的维度的权重之和。
   * 一个维度都评不出分 ⇒ 总体 UNKNOWN、得分 null（<b>不是 0 分</b>）。
   */
  public static BigDecimal overall(List<DimensionOutcome> outcomes) {
    BigDecimal weighted = BigDecimal.ZERO;
    BigDecimal weights = BigDecimal.ZERO;
    for (DimensionOutcome outcome : outcomes) {
      if (!"SCORED".equals(outcome.state()) || outcome.score() == null) {
        continue;
      }
      weighted = weighted.add(outcome.score().multiply(outcome.dimension().weight()));
      weights = weights.add(outcome.dimension().weight());
    }
    if (weights.compareTo(BigDecimal.ZERO) == 0) {
      return null;
    }
    return weighted.divide(weights, 4, java.math.RoundingMode.HALF_UP);
  }

  /**
   * 🔴 得分的<b>展示串</b>（只给界面看，不参与任何比较）。
   *
   * <p>🔴 <b>量不到时给「未知」，不是「0%」也不是「—」</b>：0% 会被读成「这一项一分没得」，
   * 而真相是「这一项我们没能力量」。前端<b>不重算</b>分数，只原样显示这个串。
   */
  public static String scoreLabel(BigDecimal ratio) {
    if (ratio == null) {
      return "未知";
    }
    return ratio.multiply(new BigDecimal("100")).setScale(2, java.math.RoundingMode.HALF_UP)
            .toPlainString()
        + "%";
  }
}
