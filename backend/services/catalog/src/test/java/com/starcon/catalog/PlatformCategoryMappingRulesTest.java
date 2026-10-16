package com.starcon.catalog;

import static com.starcon.catalog.PlatformCategoryMappingRules.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 4.2.3.1 平台类目映射的纯规则单测。
 *
 * <p>与前端 {@code platform-mapping-model.test.ts} 同一批用例，两边必须同时改：
 * 前端拦下的输入服务端也会拦，反过来也一样，否则「界面说不行、接口说行」。
 */
class PlatformCategoryMappingRulesTest {

  @Test
  void platformAndSiteCodesAreUppercasedAndShapeChecked() {
    assertThat(normalizePlatform(" amazon ")).isEqualTo("AMAZON");
    assertThat(normalizeSite(" us ")).isEqualTo("US");

    assertThat(platformProblem("amazon")).isNull();
    assertThat(siteProblem("de")).isNull();
    assertThat(platformProblem("AMAZON-UK")).isNull();

    assertThat(platformProblem(null)).contains("平台编码不能为空");
    assertThat(platformProblem("  ")).contains("平台编码不能为空");
    assertThat(platformProblem("亚马逊")).contains("平台编码请使用");
    assertThat(platformProblem("-AMAZON")).contains("平台编码请使用");
    assertThat(platformProblem("A".repeat(33))).contains("最多 32");
    assertThat(platformProblem("A".repeat(32))).isNull();

    // 两条消息必须能分辨是平台栏还是站点栏错了，否则运营不知道该改哪一栏。
    assertThat(platformProblem("亚马逊")).isNotEqualTo(siteProblem("美国站"));
    assertThat(siteProblem(null)).contains("站点编码不能为空");
    assertThat(siteProblem("美国站")).contains("站点编码请使用");
    assertThat(siteProblem("A".repeat(33))).contains("最多 32");
  }

  @Test
  void platformAttributeCodeKeepsItsCase() {
    // 平台属性码要原样进请求体：顺手大写会让刊登被平台直接驳回。
    assertThat(normalizePlatformAttributeCode(" bullet_point_1 ")).isEqualTo("bullet_point_1");
    assertThat(platformAttributeCodeProblem("bullet_point_1")).isNull();
    assertThat(platformAttributeCodeProblem("item-type.name")).isNull();
    assertThat(platformAttributeCodeProblem("材质")).contains("平台属性编码请使用");
    assertThat(platformAttributeCodeProblem("_x")).contains("平台属性编码请使用");
    assertThat(platformAttributeCodeProblem("B".repeat(65))).contains("最多 64");
    assertThat(platformAttributeNameProblem(" ")).contains("平台属性显示名不能为空");
    assertThat(platformAttributeNameProblem("材质")).isNull();
  }

  @Test
  void vocabularyChecksGiveReadableReasons() {
    assertThat(requirementProblem("REQUIRED")).isNull();
    assertThat(requirementProblem("required")).contains("要求强度只能是");
    assertThat(requirementProblem("")).contains("要求强度不能为空");

    assertThat(platformDataTypeProblem("ENUM")).isNull();
    assertThat(platformDataTypeProblem("OPTION")).contains("平台数据类型只能是");

    assertThat(transformProblem(null)).isNull();
    assertThat(transformProblem("  ")).isNull();
    assertThat(normalizeTransform(null)).isEqualTo("DIRECT");
    assertThat(normalizeTransform(" upper ")).isEqualTo("UPPER");
    assertThat(transformProblem("LOWERCASE")).contains("值变换只能是");
    assertThat(transformProblem("DIRECT")).isNull();
  }

  @Test
  void allowedValuesAreTrimmedDeduplicatedAndNeverBlank() {
    assertThat(normalizeAllowedValues(null)).isEmpty();
    assertThat(normalizeAllowedValues(List.of("  红色 ", "蓝色", "红色", "", "   ")))
        .containsExactly("红色", "蓝色");
  }

  @Test
  void attributeRuleProblemsAreReportedOneAtATimeInFieldOrder() {
    assertThat(
            attributeProblem("material", "材质", "REQUIRED", "TEXT", List.of(), "DIRECT", 0))
        .isNull();

    // 枚举必须给候选值：渲染不出下拉也校验不了值，只能原样发给平台等驳回。
    assertThat(attributeProblem("color", "颜色", "REQUIRED", "ENUM", List.of(), "DIRECT", 0))
        .contains("必须提供候选值");
    assertThat(
            attributeProblem("color", "颜色", "REQUIRED", "ENUM", List.of("红", "蓝"), "DIRECT", 0))
        .isNull();

    // 顺序：编码 → 名称 → 强度 → 类型 → 变换 → 候选值 → 排序位。
    // 一次只报一项，运营才不用反复提交七轮。
    assertThat(attributeProblem("", "", "", "", null, null, 0)).contains("平台属性编码不能为空");
    assertThat(attributeProblem("material", "", "REQUIRED", "TEXT", null, null, 0))
        .contains("平台属性显示名不能为空");
    assertThat(attributeProblem("material", "材质", "NOPE", "TEXT", null, null, 0))
        .contains("要求强度只能是");
    assertThat(attributeProblem("material", "材质", "REQUIRED", "NOPE", null, null, 0))
        .contains("平台数据类型只能是");
    assertThat(attributeProblem("material", "材质", "REQUIRED", "TEXT", null, "NOPE", 0))
        .contains("值变换只能是");
    assertThat(attributeProblem("material", "材质", "REQUIRED", "TEXT", List.of("x".repeat(101)), "DIRECT", 0))
        .contains("单个候选值最多");
    assertThat(
            attributeProblem(
                "material", "材质", "REQUIRED", "TEXT", List.of("a", "b", "c"), "DIRECT", -1))
        .contains("排序位不能为负数");
  }
}
