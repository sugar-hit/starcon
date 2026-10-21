package com.starcon.channel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 4.2.1.1 刊登编辑器 的<b>纯规则</b>：把「平台字段要求」解成表单字段集，再把内容解成校验结果。
 *
 * <p>🔴 这个类里**没有一行平台分支**（没有 {@code if (platform == AMAZON)}），它只认三样东西：
 *
 * <ol>
 *   <li>{@link Requirement} —— 平台字段要求注册表（{@code platform_listing_field_requirement}）的一行；
 *   <li>平台能力矩阵（{@code platform.capabilities}，2.1.1.2 已交付）—— 判"平台支不支持这一栏"；
 *   <li>内容值 —— 判"填没填、超没超限"。
 * </ol>
 *
 * <p>所以「平台能力矩阵变化时表单自动变化」是**结构上**成立的：矩阵里多一个键 / 少一个键，
 * 推导结果就变，**不需要改本类，也不需要改前端**。
 *
 * <p>🔴 「不知道」是一个状态：
 *
 * <ul>
 *   <li>能力矩阵**没有**声明某个能力 ⇒ {@code capabilityState = UNKNOWN}（**不是**"不支持"）；
 *   <li>字段细则归别的模块 ⇒ {@code constraintState = DELEGATED} + ownerModule；
 *   <li>字段压根没有上限数据 ⇒ {@code constraintState = UNKNOWN} + ownerModule。
 * </ul>
 *
 * <p>本类不碰数据库、不碰 HTTP，**可单测**（{@code ListingEditorFieldRulesTest}）。
 */
public final class ListingEditorFieldRules {
  private ListingEditorFieldRules() {}

  // ================================================================ 词表

  /** 表单渲染顺序（= 蓝图职责句里点名的九个栏位，**与平台无关**）。 */
  public static final List<String> FIELD_ORDER =
      List.of(
          "TITLE",
          "BULLETS",
          "DESCRIPTION",
          "VARIANT_DIMENSIONS",
          "IMAGES",
          "VIDEO",
          "A_PLUS",
          "CATEGORY_ATTRIBUTES",
          "COMPLIANCE_STATEMENTS");

  /** 字段中文名。 */
  public static final Map<String, String> LABELS =
      Map.of(
          "TITLE", "标题",
          "BULLETS", "要点",
          "DESCRIPTION", "描述",
          "VARIANT_DIMENSIONS", "变体维度",
          "IMAGES", "图片集",
          "VIDEO", "视频",
          "A_PLUS", "A+ 模块",
          "CATEGORY_ATTRIBUTES", "类目属性",
          "COMPLIANCE_STATEMENTS", "合规声明");

  /** 能力状态取值。 */
  public static final List<String> CAPABILITY_STATES =
      List.of("SUPPORTED", "UNSUPPORTED", "UNKNOWN", "NOT_APPLICABLE");

  /** 约束状态取值。 */
  public static final List<String> CONSTRAINT_STATES = List.of("KNOWN", "DELEGATED", "UNKNOWN");

  /** 平台要求整体状态取值。 */
  public static final List<String> REQUIREMENTS_STATES = List.of("KNOWN", "PARTIAL", "UNKNOWN");

  /** 违规严重度。 */
  public static final List<String> SEVERITIES = List.of("BLOCKING", "WARNING");

  /** 能力矩阵没有声明某个能力时的归属模块。 */
  public static final String CAPABILITY_OWNER_MODULE = "2.1.1.2";

  /** 平台字段要求（字段级细则）的归属模块。 */
  public static final String FIELD_REQUIREMENT_OWNER_MODULE = "2.1.1.2";

  // ================================================================ 输入

  /** 平台字段要求注册表的一行（已由服务层按"就近覆盖"解出唯一一行）。 */
  public record Requirement(
      String field,
      String valueKind,
      String requirement,
      Integer minLength,
      Integer maxLength,
      Integer minItems,
      Integer maxItems,
      String requiresCapability,
      boolean editable,
      String valueSource,
      String constraintOwnerModule,
      String note) {}

  /** 一栏内容的形状摘要（服务层把传输模型压成这个，规则层只认它）。 */
  public record ValueShape(
      /** 载荷里有没有这一栏。 */
      boolean present,
      /** 有值（文本非空白 / 列表非空 / 映射非空）。 */
      boolean filled,
      /** 文本长度（列表取最长项）。 */
      int textLength,
      /** 条数（文本为 0）。 */
      int itemCount) {}

  // ================================================================ 输出

  /** 解出来的字段（= 表单要渲染的一栏）。 */
  public record Field(
      String field,
      String label,
      String valueKind,
      String requirement,
      String constraintState,
      String capabilityState,
      Integer minLength,
      Integer maxLength,
      Integer minItems,
      Integer maxItems,
      boolean editable,
      String valueSource,
      String ownerModule,
      String note,
      String unavailableReason) {}

  /** 一条违规。 */
  public record Violation(
      String field,
      String label,
      String severity,
      String code,
      String message,
      String ownerModule,
      Integer limit,
      Integer actual) {}

  /** 校验结果。 */
  public record Validation(
      boolean valid, int blockingCount, int warningCount, List<Violation> violations, String summary) {}

  // ================================================================ 推导

  /**
   * 把平台字段要求解成表单字段集。
   *
   * @param rows 已按"就近覆盖"解出的唯一行（同一 field 只出现一次）
   * @param capabilities 平台能力矩阵（{@code platform.capabilities}）；**键缺失 = 该能力未声明**
   * @return 字段集（按 {@link #FIELD_ORDER} 排序）
   */
  public static List<Field> resolve(List<Requirement> rows, Map<String, Boolean> capabilities) {
    Map<String, Requirement> byField = new LinkedHashMap<>();
    for (Requirement row : rows) {
      byField.put(row.field(), row);
    }
    List<Field> fields = new ArrayList<>();
    for (String key : FIELD_ORDER) {
      Requirement row = byField.get(key);
      if (row == null) continue;
      fields.add(toField(row, capabilities));
    }
    return fields;
  }

  private static Field toField(Requirement row, Map<String, Boolean> capabilities) {
    String capabilityState = capabilityState(row.requiresCapability(), capabilities);
    String constraintState = constraintState(row);
    String ownerModule =
        "DELEGATED".equals(constraintState) ? row.constraintOwnerModule() : null;
    if (ownerModule == null && "UNKNOWN".equals(constraintState)) {
      ownerModule = FIELD_REQUIREMENT_OWNER_MODULE;
    }
    if (ownerModule == null && "UNKNOWN".equals(capabilityState)) {
      ownerModule = CAPABILITY_OWNER_MODULE;
    }
    String unavailableReason = null;
    if ("UNSUPPORTED".equals(row.requirement())) {
      unavailableReason = row.note() == null || row.note().isBlank() ? "该平台不支持这一栏" : row.note();
    } else if ("UNSUPPORTED".equals(capabilityState)) {
      unavailableReason =
          "平台能力矩阵声明平台不支持这一栏（能力 " + row.requiresCapability() + "）";
    }
    return new Field(
        row.field(),
        LABELS.getOrDefault(row.field(), row.field()),
        row.valueKind(),
        row.requirement(),
        constraintState,
        capabilityState,
        row.minLength(),
        row.maxLength(),
        row.minItems(),
        row.maxItems(),
        row.editable(),
        row.valueSource(),
        ownerModule,
        row.note(),
        unavailableReason);
  }

  /** 能力状态：矩阵里没有这个键 ⇒ UNKNOWN（**不是**"不支持"）。 */
  public static String capabilityState(String requiresCapability, Map<String, Boolean> capabilities) {
    if (requiresCapability == null || requiresCapability.isBlank()) return "NOT_APPLICABLE";
    Boolean declared = capabilities == null ? null : capabilities.get(requiresCapability);
    if (declared == null) return "UNKNOWN";
    return declared ? "SUPPORTED" : "UNSUPPORTED";
  }

  /** 约束状态：细则归别的模块 ⇒ DELEGATED；没有上限数据 ⇒ UNKNOWN；否则 KNOWN。 */
  public static String constraintState(Requirement row) {
    if (row.constraintOwnerModule() != null && !row.constraintOwnerModule().isBlank()) {
      return "DELEGATED";
    }
    if (row.maxLength() != null || row.minLength() != null
        || row.maxItems() != null || row.minItems() != null) {
      return "KNOWN";
    }
    return "UNKNOWN";
  }

  /** 平台要求的整体状态。 */
  public static String requirementsState(List<Field> fields) {
    if (fields.isEmpty()) return "UNKNOWN";
    for (Field field : fields) {
      if ("UNKNOWN".equals(field.constraintState())
          || "DELEGATED".equals(field.constraintState())
          || "UNKNOWN".equals(field.capabilityState())) {
        return "PARTIAL";
      }
    }
    return "KNOWN";
  }

  // ================================================================ 校验

  /**
   * 按字段集校验内容。
   *
   * @param fields 表单字段集（{@link #resolve} 的结果）
   * @param values 逐栏内容的形状摘要
   * @return 逐条违规 + 结论
   */
  public static Validation validate(List<Field> fields, Map<String, ValueShape> values) {
    List<Violation> violations = new ArrayList<>();
    for (Field field : fields) {
      // 平台明确不支持 / 能力矩阵说不支持的栏，不参与校验（它压根不该出现在表单里）
      if ("UNSUPPORTED".equals(field.requirement()) || "UNSUPPORTED".equals(field.capabilityState())) {
        continue;
      }
      ValueShape value = values.get(field.field());
      boolean present = value != null && value.present();
      boolean filled = present && value.filled();

      // 🔴 必填只在**约束已知**时才算阻断项：
      //   细则归别的模块（DELEGATED）时我们并不知道"到底要填什么"，
      //   拿一条我们答不出来的必填去阻断提交，就是把用户挡在一扇打不开的门前 ——
      //   那种情况只出提醒（REQUIREMENTS_DELEGATED），并写明归哪个模块。
      if ("REQUIRED".equals(field.requirement())
          && !filled
          && "KNOWN".equals(field.constraintState())) {
        violations.add(
            new Violation(
                field.field(),
                field.label(),
                "BLOCKING",
                "REQUIRED_MISSING",
                field.label() + "是必填项，当前为空",
                null,
                null,
                null));
      }
      if ("RECOMMENDED".equals(field.requirement()) && present && !filled) {
        violations.add(
            new Violation(
                field.field(),
                field.label(),
                "WARNING",
                "RECOMMENDED_EMPTY",
                field.label() + "是建议填写的项，当前为空",
                null,
                null,
                null));
      }

      if ("KNOWN".equals(field.constraintState())) {
        if (field.maxLength() != null && present && value.textLength() > field.maxLength()) {
          violations.add(
              new Violation(
                  field.field(),
                  field.label(),
                  "BLOCKING",
                  "LENGTH_EXCEEDED",
                  field.label() + "超出平台长度上限（" + value.textLength() + " / " + field.maxLength() + "）",
                  null,
                  field.maxLength(),
                  value.textLength()));
        }
        if (field.maxItems() != null && present && value.itemCount() > field.maxItems()) {
          violations.add(
              new Violation(
                  field.field(),
                  field.label(),
                  "BLOCKING",
                  "ITEM_COUNT_EXCEEDED",
                  field.label() + "条数超出平台上限（" + value.itemCount() + " / " + field.maxItems() + "）",
                  null,
                  field.maxItems(),
                  value.itemCount()));
        }
        if (field.minItems() != null && filled && value.itemCount() < field.minItems()) {
          violations.add(
              new Violation(
                  field.field(),
                  field.label(),
                  "BLOCKING",
                  "ITEM_COUNT_UNDER",
                  field.label() + "条数少于平台下限（" + value.itemCount() + " / " + field.minItems() + "）",
                  null,
                  field.minItems(),
                  value.itemCount()));
        }
      } else if ("DELEGATED".equals(field.constraintState())) {
        violations.add(
            new Violation(
                field.field(),
                field.label(),
                "WARNING",
                "REQUIREMENTS_DELEGATED",
                field.label() + "的细则要求归 " + field.ownerModule() + "，本模块不代它裁决",
                field.ownerModule(),
                null,
                null));
      } else {
        violations.add(
            new Violation(
                field.field(),
                field.label(),
                "WARNING",
                "REQUIREMENTS_UNKNOWN",
                field.label() + "的长度 / 条数上限未知（归 " + field.ownerModule() + "），无法判定是否超限",
                field.ownerModule(),
                null,
                null));
      }

      if ("UNKNOWN".equals(field.capabilityState())) {
        violations.add(
            new Violation(
                field.field(),
                field.label(),
                "WARNING",
                "CAPABILITY_UNKNOWN",
                "平台是否支持" + field.label() + "未知（能力矩阵未声明，归 " + field.ownerModule() + "）",
                field.ownerModule(),
                null,
                null));
      }
    }

    // 载荷里出现了该平台**没有**的栏 —— 阻断（表单由平台要求驱动，多出来的栏没有依据）
    for (String field : values.keySet()) {
      boolean declared =
          fields.stream()
              .anyMatch(f -> f.field().equals(field) && !"UNSUPPORTED".equals(f.requirement())
                  && !"UNSUPPORTED".equals(f.capabilityState()));
      if (!declared) {
        violations.add(
            new Violation(
                field,
                LABELS.getOrDefault(field, field),
                "BLOCKING",
                "FIELD_NOT_DECLARED",
                LABELS.getOrDefault(field, field) + "不是该平台登记的字段，无法保存",
                null,
                null,
                null));
      }
    }

    int blocking = (int) violations.stream().filter(v -> "BLOCKING".equals(v.severity())).count();
    int warning = violations.size() - blocking;
    String summary =
        blocking == 0
            ? (warning == 0 ? "校验通过" : "校验通过（" + warning + " 条提醒）")
            : ("有 " + blocking + " 条阻断项，提交审核前必须修掉");
    return new Validation(blocking == 0, blocking, warning, violations, summary);
  }
}
