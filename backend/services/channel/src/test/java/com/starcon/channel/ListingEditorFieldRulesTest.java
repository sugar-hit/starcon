package com.starcon.channel;

import static com.starcon.channel.ListingEditorFieldRules.*;
import static org.assertj.core.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 4.2.1.1 刊登编辑器 的**纯规则**单测（不碰数据库、不碰 HTTP）。
 *
 * <p>这一层专门钉三件事：
 *
 * <ol>
 *   <li><b>字段集由数据推导</b>：同样的输入推同样的字段集；平台之间**没有代码分支**。
 *   <li><b>「不知道」是一个状态</b>：能力矩阵没声明 ⇒ UNKNOWN；细则归别的模块 ⇒ DELEGATED。
 *   <li><b>校验逐条指到具体字段</b>：必填缺失 / 超长 / 超条数各自带限值与实际值。
 * </ol>
 */
class ListingEditorFieldRulesTest {

  static Requirement requirement(
      String field,
      String valueKind,
      String req,
      Integer minLength,
      Integer maxLength,
      Integer minItems,
      Integer maxItems,
      String requiresCapability,
      boolean editable,
      String valueSource,
      String constraintOwnerModule,
      String note) {
    return new Requirement(
        field, valueKind, req, minLength, maxLength, minItems, maxItems, requiresCapability,
        editable, valueSource, constraintOwnerModule, note);
  }

  static ValueShape text(String value) {
    return new ValueShape(true, !value.isBlank(), value.codePointCount(0, value.length()), 0);
  }

  static ValueShape list(int count) {
    return new ValueShape(true, count > 0, 10, count);
  }

  @Test
  @DisplayName("4.2.1.1 字段集由平台要求推导：只登记的字段才出现在表单里")
  void fieldsComeFromTheRequirementRows() {
    List<Requirement> rows =
        List.of(
            requirement("TITLE", "TEXT", "REQUIRED", 1, 200, null, null, null, true, "OPERATOR", null, ""),
            requirement("IMAGES", "IMAGE_LIST", "REQUIRED", null, null, 1, 9, null, true, "OPERATOR", null, ""));
    var fields = resolve(rows, Map.of());
    assertThat(fields).extracting(Field::field).containsExactly("TITLE", "IMAGES");
    assertThat(fields.get(0).label()).isEqualTo("标题");
    assertThat(fields.get(0).maxLength()).isEqualTo(200);
    assertThat(fields.get(0).constraintState()).isEqualTo("KNOWN");
  }

  @Test
  @DisplayName("4.2.1.1 能力矩阵没声明 ⇒ UNKNOWN（不是「不支持」，也不是「没有约束」）")
  void unknownCapabilityIsAState() {
    var rows =
        List.of(
            requirement("VIDEO", "VIDEO_REF", "OPTIONAL", null, null, null, 1, "video_support", true,
                "OPERATOR", null, ""));
    var unknown = resolve(rows, Map.of());
    assertThat(unknown.get(0).capabilityState()).isEqualTo("UNKNOWN");
    assertThat(unknown.get(0).ownerModule()).isEqualTo("2.1.1.2");

    var supported = resolve(rows, Map.of("video_support", true));
    assertThat(supported.get(0).capabilityState()).isEqualTo("SUPPORTED");

    var unsupported = resolve(rows, Map.of("video_support", false));
    assertThat(unsupported.get(0).capabilityState()).isEqualTo("UNSUPPORTED");
    assertThat(unsupported.get(0).unavailableReason()).isNotBlank();
  }

  @Test
  @DisplayName("4.2.1.1 细则归别的模块 ⇒ DELEGATED + ownerModule")
  void delegatedConstraintKeepsItsOwner() {
    var rows =
        List.of(
            requirement("CATEGORY_ATTRIBUTES", "ATTRIBUTE_MAP", "REQUIRED", null, null, null, null, null,
                true, "OPERATOR", "4.2.3.1", ""));
    var fields = resolve(rows, Map.of());
    assertThat(fields.get(0).constraintState()).isEqualTo("DELEGATED");
    assertThat(fields.get(0).ownerModule()).isEqualTo("4.2.3.1");
  }

  @Test
  @DisplayName("4.2.1.1 校验：必填缺失 / 超长 / 超条数 / 条数不足，逐条指到字段")
  void validationPointsAtFields() {
    var rows =
        List.of(
            requirement("TITLE", "TEXT", "REQUIRED", 1, 200, null, null, null, true, "OPERATOR", null, ""),
            requirement("IMAGES", "IMAGE_LIST", "REQUIRED", null, null, 1, 9, null, true, "OPERATOR", null, ""));
    var fields = resolve(rows, Map.of());

    Map<String, ValueShape> values = new LinkedHashMap<>();
    values.put("TITLE", text(""));
    values.put("IMAGES", list(12));
    var result = validate(fields, values);
    assertThat(result.valid()).isFalse();
    assertThat(result.blockingCount()).isPositive();
    assertThat(result.violations())
        .anyMatch(v -> "TITLE".equals(v.field()) && "REQUIRED_MISSING".equals(v.code()));
    var tooMany =
        result.violations().stream()
            .filter(v -> "IMAGES".equals(v.field()) && "ITEM_COUNT_EXCEEDED".equals(v.code()))
            .findFirst()
            .orElseThrow();
    assertThat(tooMany.limit()).isEqualTo(9);
    assertThat(tooMany.actual()).isEqualTo(12);

    Map<String, ValueShape> tooLongValues = new LinkedHashMap<>();
    tooLongValues.put("TITLE", text("标".repeat(201)));
    tooLongValues.put("IMAGES", list(1));
    var lengthResult = validate(fields, tooLongValues);
    var tooLong =
        lengthResult.violations().stream()
            .filter(v -> "TITLE".equals(v.field()) && "LENGTH_EXCEEDED".equals(v.code()))
            .findFirst()
            .orElseThrow();
    assertThat(tooLong.limit()).isEqualTo(200);
    assertThat(tooLong.actual()).isEqualTo(201);
  }

  @Test
  @DisplayName("4.2.1.1 载荷里出现该平台没有的字段 ⇒ 阻断（表单由平台要求驱动）")
  void undeclaredFieldIsBlocking() {
    var rows =
        List.of(
            requirement("TITLE", "TEXT", "REQUIRED", 1, 200, null, null, null, true, "OPERATOR", null, ""));
    var fields = resolve(rows, Map.of());
    var result = validate(fields, Map.of("BULLETS", list(1)));
    assertThat(result.valid()).isFalse();
    assertThat(result.violations())
        .anyMatch(v -> "BULLETS".equals(v.field()) && "FIELD_NOT_DECLARED".equals(v.code()));
  }

  @Test
  @DisplayName("4.2.1.1 平台要求整体状态：空注册表 ⇒ UNKNOWN，部分未知 ⇒ PARTIAL")
  void requirementsStateIsHonest() {
    assertThat(requirementsState(List.of())).isEqualTo("UNKNOWN");
    var known =
        resolve(
            List.of(requirement("TITLE", "TEXT", "REQUIRED", 1, 200, null, null, null, true, "OPERATOR", null, "")),
            Map.of());
    assertThat(requirementsState(known)).isEqualTo("KNOWN");
    var partial =
        resolve(
            List.of(
                requirement("TITLE", "TEXT", "REQUIRED", 1, 200, null, null, null, true, "OPERATOR", null, ""),
                requirement("VIDEO", "VIDEO_REF", "OPTIONAL", null, null, null, 1, "video_support", true,
                    "OPERATOR", null, "")),
            Map.of());
    assertThat(requirementsState(partial)).isEqualTo("PARTIAL");
  }
}
