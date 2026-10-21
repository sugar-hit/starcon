package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import com.starcon.catalog.BatchCalculationModels.Flag;
import com.starcon.catalog.BatchCalculationModels.ResultStatus;
import com.starcon.catalog.BatchCalculationModels.Sort;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 5.3.1.2 批量测算的**纯规则**单测。
 *
 * <p>这些规则看着不起眼，但它们守的是蓝图那句话的技术落点：
 * 「标记亏损品与低毛利品，支持导出并筛选（毛利率 &lt; X%、售价 &lt; Y）」——
 * 以及它旁边那条隐含的纪律：**「没测准」与「测出来是亏的」是两件事**。
 */
class BatchCalculationRulesTest {

  // ================================================================ 异常标记

  @Test
  void 亏损品与低毛利品互斥() {
    // 亏损：毛利额 < 0 ⇒ 亏损品，且**不是**低毛利品。
    var loss = BatchCalculationRules.flags(new BigDecimal("-12.5"), new BigDecimal("-1.25"), new BigDecimal("0.15"));
    assertThat(loss.loss()).isTrue();
    assertThat(loss.lowMargin()).isFalse();
    assertThat(loss.problem()).isTrue();

    // 低毛利：还没亏，但毛利率低于阈值。
    var low = BatchCalculationRules.flags(new BigDecimal("0.25"), new BigDecimal("0.01"), new BigDecimal("0.15"));
    assertThat(low.loss()).isFalse();
    assertThat(low.lowMargin()).isTrue();
    assertThat(low.problem()).isTrue();

    // 正常：两个标记都没有。
    var ok = BatchCalculationRules.flags(new BigDecimal("64"), new BigDecimal("0.64"), new BigDecimal("0.15"));
    assertThat(ok.loss()).isFalse();
    assertThat(ok.lowMargin()).isFalse();
    assertThat(ok.problem()).isFalse();
  }

  @Test
  void 毛利率或毛利额缺失时没有任何标记() {
    // 🔴 成本不完整 ⇒ 算不出毛利率 ⇒ 这一行不是「低毛利品」，它是「没测准」。
    var noMargin = BatchCalculationRules.flags(null, null, new BigDecimal("0.15"));
    assertThat(noMargin.loss()).isFalse();
    assertThat(noMargin.lowMargin()).isFalse();
    assertThat(noMargin.problem()).isFalse();

    var onlyProfit = BatchCalculationRules.flags(new BigDecimal("5"), null, new BigDecimal("0.15"));
    assertThat(onlyProfit.loss()).isFalse();
    assertThat(onlyProfit.lowMargin()).isFalse();
  }

  @Test
  void 阈值边界是严格小于() {
    // 毛利率恰好等于阈值 ⇒ 不算低毛利（阈值是「低于」，不是「不高于」）。
    var atThreshold =
        BatchCalculationRules.flags(new BigDecimal("15"), new BigDecimal("0.15"), new BigDecimal("0.15"));
    assertThat(atThreshold.lowMargin()).isFalse();
    var below =
        BatchCalculationRules.flags(new BigDecimal("14.99"), new BigDecimal("0.1499"), new BigDecimal("0.15"));
    assertThat(below.lowMargin()).isTrue();
  }

  @Test
  void 阈值为空时用默认值() {
    var flags = BatchCalculationRules.flags(new BigDecimal("10"), new BigDecimal("0.10"), null);
    assertThat(flags.lowMargin()).isTrue();
  }

  // ================================================================ 词表

  @Test
  void 词表解析大小写不敏感且未知返回null() {
    assertThat(BatchCalculationRules.parseFlag("loss")).isEqualTo(Flag.LOSS);
    assertThat(BatchCalculationRules.parseFlag("LOW_MARGIN")).isEqualTo(Flag.LOW_MARGIN);
    assertThat(BatchCalculationRules.parseFlag("nope")).isNull();
    assertThat(BatchCalculationRules.parseFlag(null)).isNull();
    assertThat(BatchCalculationRules.parseFlag("")).isNull();

    assertThat(BatchCalculationRules.parseStatus("calculated")).isEqualTo(ResultStatus.CALCULATED);
    assertThat(BatchCalculationRules.parseStatus("nope")).isNull();

    assertThat(BatchCalculationRules.parseSort(null)).isEqualTo(Sort.MARGIN_ASC);
    assertThat(BatchCalculationRules.parseSort("")).isEqualTo(Sort.MARGIN_ASC);
    assertThat(BatchCalculationRules.parseSort("price_desc")).isEqualTo(Sort.PRICE_DESC);
    assertThat(BatchCalculationRules.parseSort("nope")).isNull();
  }

  // ================================================================ 归一化

  @Test
  void 平台码与站点码归一化为大写且空串变null() {
    assertThat(BatchCalculationRules.code(" amazon ")).isEqualTo("AMAZON");
    assertThat(BatchCalculationRules.code("")).isNull();
    assertThat(BatchCalculationRules.code(null)).isNull();
  }

  @Test
  void 范围文本去空白且空串表示不限定() {
    assertThat(BatchCalculationRules.scope("  电子 ")).isEqualTo("电子");
    assertThat(BatchCalculationRules.scope("   ")).isEmpty();
    assertThat(BatchCalculationRules.scope(null)).isEmpty();
  }

  // ================================================================ 口径

  @Test
  void 金额四位费率六位且统一四舍五入() {
    assertThat(BatchCalculationRules.money(new BigDecimal("10")).scale()).isEqualTo(4);
    assertThat(BatchCalculationRules.rate(new BigDecimal("0.1234565")).scale()).isEqualTo(6);
    assertThat(BatchCalculationRules.rate(new BigDecimal("0.1234565")))
        .isEqualByComparingTo("0.123457");
    assertThat(BatchCalculationRules.money(null)).isNull();
    assertThat(BatchCalculationRules.rate(null)).isNull();
  }

  @Test
  void 百分比展示与空值() {
    assertThat(BatchCalculationRules.percent(new BigDecimal("0.25"))).isEqualTo("25%");
    assertThat(BatchCalculationRules.percent(new BigDecimal("0.255"))).isEqualTo("25.5%");
    assertThat(BatchCalculationRules.percent(null)).isEqualTo("—");
  }

  // ================================================================ CSV

  @Test
  void CSV转义按RFC4180并切断公式注入() {
    // 🔴 公式注入：SKU 名称是外部导入进来的自由文本。
    assertThat(BatchCalculationRules.csvCell("=1+1")).isEqualTo("'=1+1");
    assertThat(BatchCalculationRules.csvCell("+1+1")).isEqualTo("'+1+1");
    assertThat(BatchCalculationRules.csvCell("-1+1")).isEqualTo("'-1+1");
    assertThat(BatchCalculationRules.csvCell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
    // 含逗号 / 引号 / 换行的值必须加双引号，内部引号翻倍。
    assertThat(BatchCalculationRules.csvCell("a,b")).isEqualTo("\"a,b\"");
    assertThat(BatchCalculationRules.csvCell("说\"话\"")).isEqualTo("\"说\"\"话\"\"\"");
    assertThat(BatchCalculationRules.csvCell("第一行\n第二行")).isEqualTo("\"第一行\n第二行\"");
    // 既有引号又有公式前缀：单引号前缀落在 RFC 4180 的包裹**里面**，
    // Excel 读到的单元格内容仍然以 ' 开头 ⇒ 是文本而不是公式。
    var injected = BatchCalculationRules.csvCell("=HYPERLINK(\"http://evil\",\"点我\")");
    assertThat(injected).startsWith("\"").endsWith("\"");
    assertThat(injected).contains("'=HYPERLINK");
    // 普通值原样。
    assertThat(BatchCalculationRules.csvCell("普通名字")).isEqualTo("普通名字");
    assertThat(BatchCalculationRules.csvCell("")).isEmpty();
    assertThat(BatchCalculationRules.csvCell(null)).isEmpty();
  }

  @Test
  void CSV行按逗号连接() {
    assertThat(BatchCalculationRules.csvRow(java.util.Arrays.asList("a", "b,c", null)))
        .isEqualTo("a,\"b,c\",");
  }

  @Test
  void CSV里的金额与比率空值是空串而不是0() {
    assertThat(BatchCalculationRules.csvMoney(null)).isEmpty();
    assertThat(BatchCalculationRules.csvRate(null)).isEmpty();
    assertThat(BatchCalculationRules.csvMoney(new BigDecimal("10"))).isEqualTo("10.0000");
    assertThat(BatchCalculationRules.csvRate(new BigDecimal("0.64"))).isEqualTo("0.640000");
  }

  // ================================================================ 上限

  @Test
  void 上限是显式常量而不是散落在代码里的魔数() {
    // 一次批量测算最多 200 个 SKU；单次导出最多 2 万行。
    assertThat(BatchCalculationRules.MAX_SCOPE_SIZE).isEqualTo(200);
    assertThat(BatchCalculationRules.MAX_EXPORT_ROWS).isEqualTo(20_000);
  }
}
