package com.starcon.catalog;

import com.starcon.catalog.BatchCalculationModels.Flag;
import com.starcon.catalog.BatchCalculationModels.ResultStatus;
import com.starcon.catalog.BatchCalculationModels.Sort;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

/**
 * 5.3.1.2 批量测算的**纯算术与纯文本**规则（无 IO、无租户上下文，可单测）。
 *
 * <p>本类只负责三件事：
 *
 * <ol>
 *   <li><b>异常标记怎么判</b>（{@link #flags}）—— 蓝图那句「标记亏损品与低毛利品」的落点；
 *   <li><b>CSV 怎么转义</b>（{@link #csvCell}）—— 导出是给人拿去开会的，
 *       单元格里的 {@code =SUM(...)} 必须在 Excel 里是**文本**而不是公式；
 *   <li><b>口径常量</b>（精度、默认阈值、上限）—— 页面与导出都写明同一套。
 * </ol>
 *
 * <p><b>🔴 纪律一：两个标记互斥。</b>亏损品（毛利额 &lt; 0）与低毛利品（毛利率低于阈值）
 * 在数值上是重叠的（亏 1% 的品毛利率也是负的，当然也低于 15% 阈值）。
 * 若不互斥，同一个 SKU 会被计两次，「问题品总数」就多算，
 * 而「问题品总数」正是运营拿去决定清库存范围的那个数。
 *
 * <p><b>🔴 纪律二：全程 BigDecimal，零浮点。</b>金额 4 位小数、费率 6 位小数，
 * 与 5.3.1.1（{@link ProfitCalculatorRules}）**逐字相同** —— 下游 5.4.1.2 批量调价
 * 会拿这里的数字直接改价，两套精度必然在对账时打架。
 */
public final class BatchCalculationRules {

  private BatchCalculationRules() {}

  /** 金额精度：4 位小数（与 5.3.1.1 的 {@code ProfitCalculatorRules.MONEY_SCALE} 一致）。 */
  public static final int MONEY_SCALE = 4;

  /** 费率/毛利率精度：6 位小数（与 5.3.1.1 一致）。 */
  public static final int RATE_SCALE = 6;

  /** 舍入方式：四舍五入。全部金额与费率统一用它，页面上写明。 */
  public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  /** 低毛利阈值默认值（小数口径：0.15 = 15%）。 */
  public static final BigDecimal DEFAULT_LOW_MARGIN_THRESHOLD = new BigDecimal("0.150000");

  /**
   * 一次批量测算最多覆盖多少个 SKU。
   *
   * <p>🔴 这是**显式上限**，不是静默截断：超了直接报错让人缩小范围。
   * 理由：每个 SKU 的成本构成要跨服务读 warehouse（头程 + 关税），
   * 200 个 SKU 就是 400 次跨服务调用；再往上会让一次 HTTP 请求跑几分钟，
   * 而 HTTP 请求是有超时的 —— 静默截断只会让运营以为「全测过了」。
   */
  public static final int MAX_SCOPE_SIZE = 200;

  /** 单次导出的行数上限。超了**明确报错**，不静默截断（截断的 CSV 会让人以为数据完整）。 */
  public static final int MAX_EXPORT_ROWS = 20_000;

  // ================================================================ 异常标记

  /** 一行的两个标记。 */
  public record Flags(boolean loss, boolean lowMargin) {

    /** 两个标记都是 false = 正常品。 */
    public boolean problem() {
      return loss || lowMargin;
    }
  }

  /**
   * 判异常标记。
   *
   * <p>🔴 <b>毛利率或毛利额为 null ⇒ 没有任何标记</b>：
   * 成本不完整时（决策未就绪）根本算不出毛利率，
   * 那种行**不是**「低毛利品」，它是「没测准」。把它标成低毛利品，
   * 等于把一个待补数据的 SKU 混进清库存名单。
   *
   * @param grossProfit 毛利额（每单位）。可空 = 成本不完整。
   * @param grossMarginRate 毛利率（小数口径）。可空 = 成本不完整。
   * @param lowMarginThreshold 低毛利阈值（小数口径）。
   */
  public static Flags flags(
      BigDecimal grossProfit, BigDecimal grossMarginRate, BigDecimal lowMarginThreshold) {
    if (grossProfit == null || grossMarginRate == null) return new Flags(false, false);
    BigDecimal threshold =
        lowMarginThreshold == null ? DEFAULT_LOW_MARGIN_THRESHOLD : lowMarginThreshold;
    // 亏损：毛利额 < 0。低毛利：**没亏**，但毛利率低于阈值（两者互斥）。
    if (grossProfit.signum() < 0) return new Flags(true, false);
    if (grossMarginRate.compareTo(threshold) < 0) return new Flags(false, true);
    return new Flags(false, false);
  }

  // ================================================================ 词表解析

  /** 解析异常标记筛选。空 = 不过滤。未知值返回 null（由服务层转成 400）。 */
  public static Flag parseFlag(String raw) {
    if (raw == null || raw.isBlank()) return null;
    return Flag.parse(raw.trim());
  }

  /** 解析状态筛选。空 = 不过滤。未知值返回 null（由服务层转成 400）。 */
  public static ResultStatus parseStatus(String raw) {
    if (raw == null || raw.isBlank()) return null;
    return ResultStatus.parse(raw.trim());
  }

  /** 解析排序。空 = 默认（MARGIN_ASC）。未知值返回 null（由服务层转成 400）。 */
  public static Sort parseSort(String raw) {
    if (raw == null || raw.isBlank()) return Sort.MARGIN_ASC;
    return Sort.parse(raw.trim());
  }

  // ================================================================ 口径

  /** 金额：4 位小数，HALF_UP。 */
  public static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(MONEY_SCALE, ROUNDING);
  }

  /** 费率/毛利率：6 位小数，HALF_UP。 */
  public static BigDecimal rate(BigDecimal value) {
    return value == null ? null : value.setScale(RATE_SCALE, ROUNDING);
  }

  /** 百分比展示（口径串用）：0.25 → 「25%」。 */
  public static String percent(BigDecimal value) {
    if (value == null) return "—";
    return value.multiply(BigDecimal.valueOf(100)).setScale(2, ROUNDING).stripTrailingZeros()
        .toPlainString()
        + "%";
  }

  /** 归一化平台码 / 站点码：去空白 + 大写。 */
  public static String code(String raw) {
    if (raw == null) return null;
    String trimmed = raw.trim();
    return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
  }

  /** 归一化范围文本（分类 / 品牌）：去空白，空串 = 不限定。 */
  public static String scope(String raw) {
    if (raw == null) return "";
    return raw.trim();
  }

  // ================================================================ CSV

  /**
   * CSV 单元格转义（RFC 4180）+ Excel 公式注入切断。
   *
   * <p>🔴 **前导 {@code = + - @} 加单引号**：SKU 名称是外部导入进来的自由文本，
   * 一个叫 {@code =HYPERLINK("http://evil","点我")} 的商品名在 Excel 里双击就会发请求。
   * 加一个单引号前缀后它在 Excel 里就是**文本**。
   *
   * <p>🔴 含逗号 / 引号 / 换行的值必须用双引号包起来，内部引号翻倍。
   */
  public static String csvCell(String raw) {
    if (raw == null) return "";
    String value = raw;
    if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) value = "'" + value;
    if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0
        || value.indexOf('\r') >= 0) {
      return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    return value;
  }

  /** 一行 CSV（用逗号连接已转义的单元格）。 */
  public static String csvRow(List<String> cells) {
    var out = new StringBuilder();
    for (int i = 0; i < cells.size(); i++) {
      if (i > 0) out.append(',');
      out.append(csvCell(cells.get(i)));
    }
    return out.toString();
  }

  /** 金额 → CSV 文本（null → 空串，不是 "null"、也不是 0）。 */
  public static String csvMoney(BigDecimal value) {
    return value == null ? "" : money(value).toPlainString();
  }

  /** 比率 → CSV 文本（null → 空串）。小数口径原样导出，不转成百分数 —— 百分号在 CSV 里容易被再解析一次。 */
  public static String csvRate(BigDecimal value) {
    return value == null ? "" : rate(value).toPlainString();
  }
}
