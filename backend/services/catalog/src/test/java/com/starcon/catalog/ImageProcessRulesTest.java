package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ImageProcessRules} 的纯规则测试。
 *
 * <p>这些边界有三处在用：服务层拒绝时的话、前端表单校验、集成测试断言。 所以在这里断言的是**可执行的中文原因**，而不是「返回了非 null」。
 */
class ImageProcessRulesTest {

  @Test
  void vocabulariesMatchTheDatabaseCheckConstraints() {
    // V19 的 CHECK 逐字允许这些值。词表与 CHECK 不一致时，DB 会拒掉一批合法输入，
    // 而症状是「接口校验过了但保存报约束冲突」。
    assertThat(ImageProcessRules.TARGET_FORMATS).containsExactly("png", "jpeg", "gif", "bmp");
    assertThat(ImageProcessRules.CROP_MODES).containsExactly("FIT", "CROP_FILL");
    assertThat(ImageProcessRules.WATERMARK_POSITIONS)
        .containsExactly("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT", "CENTER", "TILED");
    assertThat(ImageProcessRules.JOB_STATUSES)
        .containsExactly("PENDING", "RUNNING", "SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED");
    assertThat(ImageProcessRules.ITEM_STATUSES)
        .containsExactly("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "SKIPPED", "CANCELLED");
  }

  @Test
  void terminalStatusesAreTheOnesAWorkerStopsTouching() {
    // 终态判定决定「这个任务还会不会变」。把 PENDING/RUNNING 算成终态，
    // 收尾逻辑就会把还在跑的任务标成结束，页面上进度永远停住。
    for (String status : List.of("SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED"))
      assertThat(ImageProcessRules.terminal(status)).as(status).isTrue();
    for (String status : List.of("PENDING", "RUNNING"))
      assertThat(ImageProcessRules.terminal(status)).as(status).isFalse();
    assertThat(ImageProcessRules.partial("PARTIAL")).isTrue();
    assertThat(ImageProcessRules.partial("FAILED")).isFalse();
  }

  @Test
  void targetFormatRejectsWebpWithAnActionableReason() {
    assertThat(ImageProcessRules.targetFormatProblem("png")).isNull();
    assertThat(ImageProcessRules.targetFormatProblem("JPEG")).isNull();
    assertThat(ImageProcessRules.targetFormatProblem(" jpeg ")).isNull();
    // WEBP 必须拒，并说清为什么、改成什么。JDK 没有 WEBP 写入器，
    // 声称成功只会得到一个空产物。
    assertThat(ImageProcessRules.targetFormatProblem("webp")).contains("png").contains("ImageIO");
    assertThat(ImageProcessRules.targetFormatProblem("")).contains("不能为空");
    assertThat(ImageProcessRules.targetFormatProblem(null)).contains("不能为空");
  }

  @Test
  void backgroundRemovalWithOpaqueTargetRequiresWhiteBackground() {
    // png 能存 alpha，去背景单独用是合法的。
    assertThat(ImageProcessRules.backgroundFormatProblem(true, "png")).isNull();
    // 白底 + 去背景 = 标准流水线（先抠后铺白），任何格式都行。
    assertThat(ImageProcessRules.backgroundFormatProblem(true, "jpeg")).isNotNull();
    assertThat(ImageProcessRules.backgroundFormatProblem(false, "jpeg")).isNull();
    // 原因要指向可执行动作（改格式 / 开白底），而不是「参数非法」。
    assertThat(ImageProcessRules.backgroundFormatProblem(true, "bmp"))
        .contains("png")
        .contains("白底");
  }

  @Test
  void targetSizeRequiresBothDimensionsOrNeither() {
    assertThat(ImageProcessRules.targetSizeProblem(null, null)).isNull();
    assertThat(ImageProcessRules.targetSizeProblem(100, 100)).isNull();
    // 只给一个尺寸时裁剪结果不唯一：V19 的 CHECK 会拒，这里先给可读原因。
    assertThat(ImageProcessRules.targetSizeProblem(100, null)).contains("同时给宽和高");
    assertThat(ImageProcessRules.targetSizeProblem(null, 100)).contains("同时给宽和高");
    assertThat(ImageProcessRules.targetSizeProblem(0, 100)).contains("大于 0");
    assertThat(ImageProcessRules.targetSizeProblem(-1, 100)).contains("大于 0");
    assertThat(ImageProcessRules.targetSizeProblem(ImageProcessRules.MAX_TARGET_EDGE_PX + 1, 100))
        .contains(String.valueOf(ImageProcessRules.MAX_TARGET_EDGE_PX));
  }

  @Test
  void ratioLabelsMustBeUniqueWithinARecipe() {
    assertThat(ImageProcessRules.duplicateRatioProblem(List.of("MAIN", "THUMBNAIL"))).isNull();
    // 大小写不同也算同一个标签（存库时统一转大写）。
    assertThat(ImageProcessRules.duplicateRatioProblem(List.of("MAIN", "main")))
        .contains("MAIN")
        .contains("唯一");
    assertThat(ImageProcessRules.duplicateRatioProblem(null)).isNull();
    assertThat(ImageProcessRules.ratioLabelProblem("")).contains("不能为空");
    assertThat(ImageProcessRules.ratioLabelProblem("x".repeat(33))).contains("32");
    assertThat(ImageProcessRules.ratioSizeProblem(null, 10)).contains("宽和高");
    assertThat(ImageProcessRules.ratioSizeProblem(10, 0)).contains("大于 0");
  }

  @Test
  void capacityLimitsAreBoundedAndExplained() {
    assertThat(ImageProcessRules.assetsSizeProblem(0)).contains("至少选择一个");
    assertThat(ImageProcessRules.assetsSizeProblem(500)).isNull();
    assertThat(ImageProcessRules.assetsSizeProblem(501))
        .contains(String.valueOf(ImageProcessRules.MAX_ASSETS_PER_JOB));
    assertThat(ImageProcessRules.ratiosSizeProblem(8)).isNull();
    assertThat(ImageProcessRules.ratiosSizeProblem(9))
        .contains(String.valueOf(ImageProcessRules.MAX_RATIOS_PER_RECIPE));
  }

  @Test
  void enumAndRangeValidatorsRejectIllegalValues() {
    assertThat(ImageProcessRules.cropModeProblem("fit")).isNull();
    assertThat(ImageProcessRules.cropModeProblem("SQUEEZE")).contains("FIT");
    assertThat(ImageProcessRules.watermarkPositionProblem("center")).isNull();
    assertThat(ImageProcessRules.watermarkPositionProblem("SOMEWHERE")).contains("TOP_LEFT");
    assertThat(ImageProcessRules.toleranceProblem(0)).isNull();
    assertThat(ImageProcessRules.toleranceProblem(255)).isNull();
    assertThat(ImageProcessRules.toleranceProblem(256)).contains("0~255");
    assertThat(ImageProcessRules.toleranceProblem(-1)).contains("0~255");
    assertThat(ImageProcessRules.toleranceProblem(null)).contains("不能为空");
    assertThat(ImageProcessRules.opacityProblem(0)).isNull();
    assertThat(ImageProcessRules.opacityProblem(100)).isNull();
    assertThat(ImageProcessRules.opacityProblem(101)).contains("0~100");
    assertThat(ImageProcessRules.opacityProblem(null)).contains("不能为空");
    assertThat(ImageProcessRules.watermarkTextProblem("x".repeat(121))).contains("120");
    assertThat(ImageProcessRules.watermarkTextProblem(null)).isNull();
  }

  @Test
  void codeAndNameValidationMatchesTheDictionaryConvention() {
    // 与 brand.code / media_tag.code 同一口径（V19 同样带这个 CHECK）。
    assertThat(ImageProcessRules.recipeCodeProblem("amazon-main")).isNull();
    assertThat(ImageProcessRules.recipeCodeProblem("AMZ_1")).isNull();
    assertThat(ImageProcessRules.recipeCodeProblem(" amazon main ")).contains("英文字母");
    assertThat(ImageProcessRules.recipeCodeProblem("_leading")).contains("英文字母");
    assertThat(ImageProcessRules.recipeCodeProblem("")).contains("不能为空");
    assertThat(ImageProcessRules.recipeCodeProblem("x".repeat(33))).contains("32");
    assertThat(ImageProcessRules.recipeNameProblem("")).contains("不能为空");
    assertThat(ImageProcessRules.recipeNameProblem("x".repeat(101))).contains("100");
  }

  @Test
  void statusLabelsAreHumanReadable() {
    assertThat(ImageProcessRules.jobStatusLabel("PARTIAL")).isEqualTo("部分失败");
    assertThat(ImageProcessRules.itemStatusLabel("SKIPPED")).isEqualTo("已跳过");
    // 未知值原样返回而不是空字符串：页面上要能看出是后端给了个没见过的状态。
    assertThat(ImageProcessRules.jobStatusLabel("WEIRD")).isEqualTo("WEIRD");
    assertThat(ImageProcessRules.itemStatusLabel("WEIRD")).isEqualTo("WEIRD");
  }
}
