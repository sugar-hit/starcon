package com.starcon.catalog;

import com.starcon.catalog.BatchPriceAdjustmentModels.AdjustMode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 5.4.1.2 批量调价的**纯算术与纯文本**规则（无 IO、无租户上下文，可单测）。
 *
 * <p>本类只负责四件事：
 *
 * <ol>
 *   <li><b>新价怎么算</b>（{@link #newPrice}）—— 固定金额 / 百分比两种换算，
 *       舍入策略钉死在这里，页面与导出都写明同一套；
 *   <li><b>亏损怎么判</b>（{@link #lossFlag}）—— 毛利额为 null 时返回 null（不可信），
 *       **不是 false**；
 *   <li><b>预览令牌怎么算</b>（{@link #previewToken}）—— 预览内容的内容哈希；
 *   <li><b>口径常量</b>（精度、上限）。
 * </ol>
 *
 * <p><b>🔴 纪律一：全程 BigDecimal，零浮点。</b>
 *
 * <p><b>🔴 纪律二：售价 2 位小数，金额 4 位小数，费率 6 位小数。</b>
 *
 * <p>售价（{@link #PRICE_SCALE}）与内部金额（{@link #MONEY_SCALE}）**故意不同**：
 * 售价是**对外标价**，2.1.1.1 平台目录里的 currencyScale 就是 2（一个listing 卖 12.3456 美元
 * 是没有意义的）；而内部金额要留 4 位小数，因为平台费是售价的**比例**，
 * 4 位小数的扣减项算出来的毛利额要能对得上账。
 * 两者都用 HALF_UP，页面上写明。
 *
 * <p><b>🔴 纪律三：按模板方式的建议价不二次舍入。</b>
 * 「按模板」的新价来自 5.3.2.1 的 {@link PricingTemplateService#apply}，
 * 它是 4 位小数口径 —— 本模块原样采用，再舍一次会让它与「一键应用」页上的建议价对不上，
 * 而对不上就会有人以为其中一边算错了。
 */
public final class BatchPriceAdjustmentRules {

  private BatchPriceAdjustmentRules() {}

  /** 售价精度：2 位小数（与 2.1.1.1 平台目录的 currencyScale=2 一致）。 */
  public static final int PRICE_SCALE = 2;

  /** 金额精度：4 位小数（与 5.3.1.1 的 {@code ProfitCalculatorRules.MONEY_SCALE} 一致）。 */
  public static final int MONEY_SCALE = 4;

  /** 费率/毛利率精度：6 位小数（与 5.3.1.1 一致）。 */
  public static final int RATE_SCALE = 6;

  /** 舍入方式：四舍五入。全部金额、售价与费率统一用它。 */
  public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  /**
   * 一次批量调价最多覆盖多少个 listing（SKU × 店铺）。
   *
   * <p>🔴 这是**显式上限**，不是静默截断：超了直接报错让人缩小范围。
   * 理由：每个 listing 至少要跑两次 5.3.1.1 的测算（改价前 / 改价后），
   * 而每次测算的成本构成都要跨服务读 warehouse；「按模板」方式还要再加一次模板探测与复核。
   * 上限之外继续跑会让一次 HTTP 请求跑几分钟，而 HTTP 请求是有超时的 ——
   * 静默截断只会让运营以为「全改过了」。
   */
  public static final int MAX_TARGET_LISTINGS = 200;

  // ================================================================ 新价

  /**
   * 按调价方式算新价。
   *
   * <p>🔴 返回 <b>null</b> 表示「这一行调不了」（调完不是正价），由调用方**跳过这一行**并写明原因 ——
   * 绝不返回 0：0 会被当成「免费」，而它其实是一个算错的调价。
   *
   * <p>🔴 百分比方式的换算是 {@code old × (1 + rate)}，rate 是小数口径（0.1 = +10%）。
   * 固定金额方式是 {@code old + amount}，amount 可负（降价）。
   *
   * @param oldPrice 当前价（每单位）。可空 = 没有旧价 ⇒ 没有新价。
   * @param amount 固定金额（FIXED_AMOUNT 用）。可空。
   * @param rate 涨跌幅（小数口径，PERCENT 用）。可空。
   */
  public static BigDecimal newPrice(
      AdjustMode mode, BigDecimal oldPrice, BigDecimal amount, BigDecimal rate) {
    if (oldPrice == null) return null;
    BigDecimal raw;
    switch (mode) {
      case FIXED_AMOUNT -> {
        if (amount == null) return null;
        raw = oldPrice.add(amount);
      }
      case PERCENT -> {
        if (rate == null) return null;
        raw = oldPrice.multiply(BigDecimal.ONE.add(rate));
      }
      case TEMPLATE -> {
        // 按模板的新价由 5.3.2.1 算好，本方法不参与（也不二次舍入）。
        return null;
      }
      default -> {
        return null;
      }
    }
    BigDecimal rounded = raw.setScale(PRICE_SCALE, ROUNDING);
    // 调完不是正价 ⇒ 这一行调不了（返回 null，由调用方跳过并写明原因）。
    return rounded.signum() > 0 ? rounded : null;
  }

  /** 价差（新价 − 旧价）。正 = 涨价，负 = 降价。 */
  public static BigDecimal delta(BigDecimal oldPrice, BigDecimal newPrice) {
    if (oldPrice == null || newPrice == null) return null;
    return money(newPrice.subtract(oldPrice));
  }

  // ================================================================ 亏损标记

  /**
   * 亏损标记（毛利额 &lt; 0）。
   *
   * <p>🔴 毛利额为 <b>null</b> ⇒ 返回 <b>null</b>（不可信），**不是 false**：
   * 成本不完整时算不出毛利率，那种行是「没测准」而不是「没亏」，
   * 把它渲染成「不亏」会让一个可能巨亏的商品混进正常名单。
   */
  public static Boolean lossFlag(BigDecimal grossProfit) {
    if (grossProfit == null) return null;
    return grossProfit.signum() < 0;
  }

  // ================================================================ 预览令牌

  /**
   * 预览令牌 = 预览内容的 SHA-256 哈希（十六进制）。
   *
   * <p>🔴 它的用途是**绑定「预览」与「确认」**：执行时服务端**重算**一遍预览，
   * 把重算出来的令牌与客户端带回的令牌逐字比对 ——
   * 对不上说明「预览之后目标集合或当前价变了」，此时拒绝执行，
   * 而不是照着过期的数字改价（那会把一批 listing 改到一个谁也没确认过的价）。
   *
   * <p>🔴 令牌**不含**任何随机量与时间戳：同一份输入必须算出同一个令牌，
   * 否则「预览 → 确认」这条链在跨进程 / 跨实例时会被自己打断。
   *
   * @param canonical 规范化后的预览内容（由服务层拼装，字段顺序固定）。
   */
  public static String previewToken(String canonical) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  /** 规范化一个 BigDecimal 进令牌串：去尾零 + 定点，避免 {@code 10.00} 与 {@code 10} 算出两个令牌。 */
  public static String canonicalNumber(BigDecimal value) {
    if (value == null) return "-";
    return value.stripTrailingZeros().toPlainString();
  }

  // ================================================================ 口径

  /** 售价：2 位小数，HALF_UP。 */
  public static BigDecimal price(BigDecimal value) {
    return value == null ? null : value.setScale(PRICE_SCALE, ROUNDING);
  }

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

  /** 店铺的规范编码：{@code 平台码:站点码}。 */
  public static String shopCode(String platformCode, String siteCode) {
    return platformCode + ":" + siteCode;
  }

  /** 店铺清单的规范编码（按平台码、站点码升序，保证同一批目标两次算出同一个令牌）。 */
  public static String shopCodes(List<String[]> shops) {
    return shops.stream()
        .map(s -> shopCode(s[0], s[1]))
        .sorted()
        .distinct()
        .reduce((a, b) -> a + "," + b)
        .orElse("");
  }
}
