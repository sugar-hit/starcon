package com.starcon.catalog;

import static com.starcon.catalog.ImportValidationRules.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 3.8.1.2 校验报告的纯规则单测：分类词表的封闭性、行结论的归并、导出行的装配与筛选。
 *
 * <p>不接数据库，因为这些判定全部是纯函数 —— 而它们恰好是最容易在集成测试里被 「顺手写对」从而永远测不到的地方（分类表漏一个码、要靠人肉发现； 行结论在 2 条可跳过 + 1
 * 条必须修正时到底算哪一类，也很容易只测了单条问题的情况）。
 */
class ImportValidationRulesTest {

  // ================================================================ 分类词表

  /**
   * 封闭词表必须与上游 3.8.1.1 能产出的全部机器码一一对应。
   *
   * <p>这个断言是本文件最重要的一条：{@code ALL_CODES} 少一个码 = 那个码生成报告时会炸 （{@code categoryOf} 返回 null，服务层显式失败）；多一个码
   * = 上游已经不产出了， 报告里的分类词表在骗人。两种偏差都必须在这里被钉住，而不是等运营发现。
   */
  @Test
  void 分类词表覆盖上游全部十四个机器码() {
    assertThat(ALL_CODES)
        .containsExactlyInAnyOrder(
            // ImportRules 产出
            "REQUIRED",
            "MATCH_KEY_REQUIRED",
            "INVALID_TYPE",
            "INVALID_ENUM",
            "TOO_LONG",
            "DUPLICATE_IN_FILE",
            "EXISTS_IN_CREATE_MODE",
            "NOT_FOUND_IN_UPDATE_MODE",
            "NEGATIVE",
            "GROSS_LT_NET",
            // ImportJobExecutor 预校验侧产出
            "UNMAPPED_COLUMN",
            "REFERENCE_NOT_FOUND",
            "BARCODE_TAKEN",
            // ImportJobExecutor 执行侧产出
            "WRITE_FAILED");
    assertThat(ALL_CODES).as("词表大小就是 14，少一个或多一个都说明与上游脱节了").hasSize(14);
    // 每一个码都必须能归类，不能落在兜底桶里。
    for (String code : ALL_CODES) assertThat(categoryOf(code)).as("错误码 %s 必须能归类", code).isNotNull();
    // 未知码明确返回 null（而不是某个兜底分类）。
    assertThat(categoryOf("SOMETHING_NEW")).isNull();
    assertThat(categoryOf(null)).isNull();
  }

  /** 蓝图点名的五类必须真的各自有码，报告页上不能出现一栏永远是 0 的类别。 */
  @Test
  void 蓝图五类各有归属且第六类是写入失败() {
    assertThat(categoryOf("REQUIRED")).isEqualTo(Category.REQUIRED_MISSING);
    assertThat(categoryOf("MATCH_KEY_REQUIRED")).isEqualTo(Category.REQUIRED_MISSING);
    assertThat(categoryOf("UNMAPPED_COLUMN")).isEqualTo(Category.REQUIRED_MISSING);
    assertThat(categoryOf("INVALID_TYPE")).isEqualTo(Category.FORMAT_INVALID);
    assertThat(categoryOf("INVALID_ENUM")).isEqualTo(Category.FORMAT_INVALID);
    assertThat(categoryOf("TOO_LONG")).isEqualTo(Category.FORMAT_INVALID);
    assertThat(categoryOf("NEGATIVE")).isEqualTo(Category.FORMAT_INVALID);
    assertThat(categoryOf("GROSS_LT_NET")).isEqualTo(Category.FORMAT_INVALID);
    assertThat(categoryOf("DUPLICATE_IN_FILE")).isEqualTo(Category.DUPLICATE_CODE);
    assertThat(categoryOf("REFERENCE_NOT_FOUND")).isEqualTo(Category.REFERENCE_MISSING);
    assertThat(categoryOf("EXISTS_IN_CREATE_MODE")).isEqualTo(Category.UNIQUE_CONFLICT);
    assertThat(categoryOf("NOT_FOUND_IN_UPDATE_MODE")).isEqualTo(Category.UNIQUE_CONFLICT);
    assertThat(categoryOf("BARCODE_TAKEN")).isEqualTo(Category.UNIQUE_CONFLICT);
    assertThat(categoryOf("WRITE_FAILED")).isEqualTo(Category.WRITE_FAILED);
    // 六类都有中文名（导出文件里给运营看的就是它）。
    assertThat(Set.of(Category.values()))
        .allSatisfy(c -> assertThat(c.label()).as("分类 %s 要有中文名", c).isNotBlank());
  }

  // ================================================================ 行结论归并

  /**
   * 「有 ERROR 就整行不落库」：一条可跳过 + 一条必须修正 = 必须修正。
   *
   * <p>这条是蓝图那句「校验失败的行不落库，不允许尽力导入产生半截数据」在代码里的落点。 只测单条问题会漏掉这个组合 —— 而组合恰恰是真实文件里最常见的情况。
   */
  @Test
  void 行结论从条级问题归并且有错误就不落库() {
    assertThat(conclude(List.of())).isEqualTo(RowConclusion.CLEAN);
    assertThat(
            conclude(List.of(warning("spu_name", "spu_name", "EXISTS_IN_CREATE_MODE", "已存在，会被跳过"))))
        .isEqualTo(RowConclusion.SKIPPABLE);
    assertThat(
            conclude(List.of(error("spu_code", "spu_code", "REQUIRED", "「spu_code」是必填列，这一行没有值"))))
        .isEqualTo(RowConclusion.BLOCKING);
    // 关键组合：两个可跳过 + 一个必须修正 → 仍然不落库
    assertThat(
            conclude(
                List.of(
                    warning("spu_name", "spu_name", "EXISTS_IN_CREATE_MODE", "已存在，会被跳过"),
                    error("spu_code", "spu_code", "REQUIRED", "「spu_code」是必填列，这一行没有值"),
                    error("brand", "brand", "TOO_LONG", "「brand」最长 200 个字符"))))
        .isEqualTo(RowConclusion.BLOCKING);
  }

  /** blocking 直接读上游的 Severity 枚举：未知级别抛错而不是默认成「不阻断」。 */
  @Test
  void 严重级别以3_8_1_1的枚举为准且未知级别报错() {
    assertThat(blocking("ERROR")).isTrue();
    assertThat(blocking("WARNING")).isFalse();
    assertThat(blocking(" error ")).isTrue();
    assertThatThrownBy(() -> blocking("FATAL")).isInstanceOf(IllegalArgumentException.class);
  }

  // ================================================================ 导出行的装配

  @Test
  void 导出列头是行号加模板列加七个标注列() {
    var headers = exportHeaders(List.of("商品编码", "商品名称"));
    assertThat(headers)
        .containsExactly(
            "行号", "商品编码", "商品名称", "问题字段", "必须修正", "问题类别", "问题代码", "问题说明", "本行结论", "是否落库");
    assertThat(ANNOTATION_HEADERS).hasSize(7);
  }

  @Test
  void 标注行逐条给出问题且字段名去重() {
    var row =
        annotatedRow(
            4,
            List.of("SPU-1", "耳机"),
            List.of(
                error("spu_code", "商品编码", "REQUIRED", "「spu_code」是必填列，这一行没有值"),
                error("spu_name", "商品名称", "TOO_LONG", "「spu_name」最长 200 个字符")));
    assertThat(row.rowNo()).isEqualTo(4);
    assertThat(row.values()).containsExactly("SPU-1", "耳机");
    // 同一字段两条问题只出现一次字段名，但代码与说明逐条给全
    assertThat(row.fieldLabels()).as("字段名去重").isEqualTo("商品编码；商品名称");
    assertThat(row.codes()).isEqualTo("REQUIRED；TOO_LONG");
    assertThat(row.messages()).contains("「spu_code」是必填列");
    assertThat(row.mustFix()).isEqualTo("是");
    assertThat(row.conclusion()).isEqualTo("必须修正");
    assertThat(row.willImport()).as("有错误的行永不落库").isFalse();
    assertThat(row.toCsvRow())
        .containsExactly(
            "4",
            "SPU-1",
            "耳机",
            "商品编码；商品名称",
            "是",
            "必填缺失；格式错误",
            "REQUIRED；TOO_LONG",
            "「spu_code」是必填列，这一行没有值；「spu_name」最长 200 个字符",
            "必须修正",
            "否");
  }

  @Test
  void 干净行给出校验通过且会落库() {
    var row = annotatedRow(2, List.of("SPU-1", "耳机"), List.of());
    assertThat(row.mustFix()).isEqualTo("否");
    assertThat(row.conclusion()).isEqualTo("校验通过");
    assertThat(row.willImport()).isTrue();
    assertThat(row.fieldLabels()).isEmpty();
  }

  @Test
  void 整行级问题的字段名留空但代码照给() {
    var row =
        annotatedRow(
            9,
            List.of("SPU-1", "耳机"),
            List.of(error("", "", "DUPLICATE_IN_FILE", "这一行与本文件里另一行指向同一条记录")));
    assertThat(row.fieldLabels()).as("整行级问题没有列名").isEmpty();
    assertThat(row.codes()).isEqualTo("DUPLICATE_IN_FILE");
    assertThat(row.toCsvRow().get(3)).as("问题字段那一格必须是空").isEmpty();
    assertThat(row.toCsvRow().get(4)).as("必须修正那一格是「是」").isEqualTo("是");
  }

  // ================================================================ 导出行筛选

  @Test
  void 带标注的错误文件给出全部行修正版模板只给有问题的行() {
    // 带标注的错误文件：ALL 时连校验通过的行一起给（运营要看好行/坏行的比例）
    assertThat(rowIncluded(RowConclusion.CLEAN, "ALL", false)).isTrue();
    assertThat(rowIncluded(RowConclusion.BLOCKING, "ALL", false)).isTrue();
    assertThat(rowIncluded(RowConclusion.SKIPPABLE, "ALL", false)).isTrue();
    // 修正版模板：校验通过的行没有可改的内容，不给
    assertThat(rowIncluded(RowConclusion.CLEAN, "ALL", true)).isFalse();
    assertThat(rowIncluded(RowConclusion.BLOCKING, "ALL", true)).isTrue();
    assertThat(rowIncluded(RowConclusion.SKIPPABLE, "ALL", true)).isTrue();
    assertThat(problemOnly(KIND_ANNOTATED)).isFalse();
    assertThat(problemOnly(KIND_FIXED_TEMPLATE)).isTrue();
  }

  @Test
  void 严重级别筛选按行收敛而不是按条() {
    // ERROR 筛选只要必须修正的行：可跳过的行不给（半行导出没有意义）
    assertThat(rowIncluded(RowConclusion.BLOCKING, "ERROR", false)).isTrue();
    assertThat(rowIncluded(RowConclusion.SKIPPABLE, "ERROR", false)).isFalse();
    assertThat(rowIncluded(RowConclusion.SKIPPABLE, "WARNING", false)).isTrue();
    assertThat(rowIncluded(RowConclusion.BLOCKING, "WARNING", false)).isFalse();
    // 空白按 ALL 处理（未传筛选 = 全部）
    assertThat(rowIncluded(RowConclusion.CLEAN, null, false)).isTrue();
    assertThat(rowIncluded(RowConclusion.BLOCKING, "  ", false)).isTrue();
    assertThatThrownBy(() -> rowIncluded(RowConclusion.CLEAN, "NOPE", false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(SEVERITY_FILTERS).containsExactlyInAnyOrder("ALL", "ERROR", "WARNING");
  }

  @Test
  void 逐行结论的中文名不套任务状态的词表() {
    assertThat(outcomeLabel("VALID")).isEqualTo("校验通过");
    assertThat(outcomeLabel("INVALID")).isEqualTo("校验不通过");
    assertThat(outcomeLabel("PENDING")).isEqualTo("待预校验");
    assertThat(outcomeLabel("IMPORTED")).isEqualTo("已导入");
    assertThat(outcomeLabel("SKIPPED")).isEqualTo("已跳过");
    assertThat(outcomeLabel("FAILED")).isEqualTo("写入失败");
    assertThat(outcomeLabel(null)).isEmpty();
  }

  // ================================================================ 工具

  private static Issue error(String fieldKey, String fieldLabel, String code, String message) {
    return new Issue(2, fieldKey, fieldLabel, code, categoryOf(code).name(), "ERROR", message, "");
  }

  private static Issue warning(String fieldKey, String fieldLabel, String code, String message) {
    return new Issue(
        2, fieldKey, fieldLabel, code, categoryOf(code).name(), "WARNING", message, "");
  }
}
