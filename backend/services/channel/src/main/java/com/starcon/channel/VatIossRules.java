package com.starcon.channel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 16.3.2.1 VAT/IOSS 的**判定规则**（纯函数，不碰数据库）。
 *
 * <p>蓝图：「…<b>IOSS 订单与一般 VAT 订单必须区分统计</b>，混在一起申报是最常见的错误。」
 *
 * <p>🔴 本类把那条加粗句变成**一张可逐条复核的判定表**：
 * 判定结果永远是 {@link Decision}（分档 + 原因码 + 人话），
 * **不允许**「判不出来就默认归到 IOSS」—— 判不了有独立的一档（{@code UNRESOLVED}）。
 *
 * <p>🔴 **判定的十步顺序是刻意的**，换个顺序就会得出不同的结论：
 *
 * <ol>
 *   <li>订单已取消 ⇒ 不在范围（取消的单不进申报统计）；</li>
 *   <li>没有收货国家 ⇒ <b>判不了</b>（可能欧盟也可能不是）；</li>
 *   <li>收货地不在欧盟 ⇒ 不在范围；</li>
 *   <li>买家类型没采到 ⇒ <b>判不了</b>（猜成 B2C 会把 B2B 单错算进 IOSS）；</li>
 *   <li>B2B ⇒ 一般 VAT（欧盟内 B2B 走反向征税，不是 IOSS）；</li>
 *   <li>没有生效的 IOSS 登记 ⇒ 一般 VAT（没有 IOSS 就没有 IOSS 订单）；</li>
 *   <li>店铺不在登记覆盖范围内 ⇒ 一般 VAT；</li>
 *   <li>含应税消费品（酒 / 烟 / 能源）⇒ 一般 VAT（**IOSS 不覆盖应税消费品**）；</li>
 *   <li>门槛没法折算（汇率读不到）⇒ <b>判不了</b>；</li>
 *   <li>货值超过免征门槛 ⇒ 一般 VAT；</li>
 *   <li>其余 ⇒ <b>IOSS</b>。</li>
 * </ol>
 *
 * <p>🔴 **税率是否读得到不参与分档判定**：分档回答「这单该不该走 IOSS」，
 * 税率回答「适用多少税率」，两件事。税率读不到时 {@code tax_rate} 留 <b>null</b>、
 * {@code tax_rate_source='UNAVAILABLE'} —— <b>绝不是 0%</b>。
 */
public final class VatIossRules {

  private VatIossRules() {}

  // ---------------------------------------------------------------- 词表

  /** 🔴 统计分档的受控值域（DB 层有 CHECK 钉死，这里是服务层同一份）。 */
  public static final List<String> FILING_SCOPES =
      List.of("IOSS", "GENERAL_VAT", "OUT_OF_SCOPE", "UNRESOLVED");

  /** 判定原因的受控值域。 */
  public static final List<String> REASON_CODES =
      List.of(
          "IOSS_ELIGIBLE",
          "ABOVE_THRESHOLD",
          "EXCISE_GOODS",
          "B2B_ORDER",
          "NON_EU_DESTINATION",
          "NO_DESTINATION_COUNTRY",
          "SHOP_OUT_OF_SCOPE",
          "NO_ACTIVE_REGISTRATION",
          "NO_CUSTOMER_TYPE",
          "THRESHOLD_UNRESOLVED",
          "ORDER_CANCELLED");

  /** 申报状态的受控值域（DB 层有 CHECK 钉死）。 */
  public static final List<String> FILING_STATUSES = List.of("DRAFT", "DRAFTED", "CONFIRMED");

  /** 申报周期的受控值域。 */
  public static final List<String> FILING_CYCLES =
      List.of("MONTHLY", "QUARTERLY", "SEMI_ANNUAL", "ANNUAL");

  /** 税种词表（本模块只认 VAT —— 美国州级销售税归 16.3.2.3）。 */
  public static final List<String> TAX_TYPES = List.of("VAT");

  // ---------------------------------------------------------------- 分档与原因的人话

  /** 统计分档的人话（页面直接显示）。 */
  public static String scopeLabel(String scope) {
    return switch (scope == null ? "" : scope) {
      case "IOSS" -> "IOSS 订单（欧盟 B2C、货值未超免征门槛）";
      case "GENERAL_VAT" -> "一般 VAT 订单（不适用 IOSS）";
      case "OUT_OF_SCOPE" -> "不在本模块申报范围";
      case "UNRESOLVED" -> "判不了（不足以归档，不计入 IOSS 申报）";
      default -> "未知分档";
    };
  }

  /** 判定原因的人话。 */
  public static String reasonLabel(String reasonCode) {
    return switch (reasonCode == null ? "" : reasonCode) {
      case "IOSS_ELIGIBLE" -> "符合 IOSS";
      case "ABOVE_THRESHOLD" -> "货值超过免征门槛";
      case "EXCISE_GOODS" -> "含应税消费品（酒 / 烟 / 能源）";
      case "B2B_ORDER" -> "B2B 订单（欧盟内 B2B 不走 IOSS）";
      case "NON_EU_DESTINATION" -> "收货地不在欧盟";
      case "NO_DESTINATION_COUNTRY" -> "订单没有收货国家";
      case "SHOP_OUT_OF_SCOPE" -> "店铺不在 IOSS 登记覆盖范围内";
      case "NO_ACTIVE_REGISTRATION" -> "没有生效的 IOSS 登记";
      case "NO_CUSTOMER_TYPE" -> "买家类型没采到（B2C / B2B 判不了）";
      case "THRESHOLD_UNRESOLVED" -> "汇率读不到，无法折算免征门槛";
      case "ORDER_CANCELLED" -> "订单已取消";
      default -> "未知原因";
    };
  }

  /** 申报周期的人话。 */
  public static String filingCycleLabel(String cycle) {
    return switch (cycle == null ? "" : cycle) {
      case "MONTHLY" -> "按月申报";
      case "QUARTERLY" -> "按季申报";
      case "SEMI_ANNUAL" -> "按半年申报";
      case "ANNUAL" -> "按年申报";
      default -> "未知周期";
    };
  }

  /** 申报状态的人话（🔴 页面把「本系统不代用户向税务机构提交」写在这里）。 */
  public static String filingStatusLabel(String status) {
    return switch (status == null ? "" : status) {
      case "DRAFT" -> "待提交（还没出底稿）";
      case "DRAFTED" -> "已出底稿（等待人工核对）";
      case "CONFIRMED" -> "已确认（数据完整可核）";
      default -> "未知状态";
    };
  }

  // ---------------------------------------------------------------- 判定

  /** 判定结果：分档 + 原因码 + 人话说明。 */
  public record Decision(String filingScope, String reasonCode, String reasonDetail) {}

  /**
   * 判定输入（**全部显式**，不藏默认值）。
   *
   * @param cancelled 订单是否已取消
   * @param destinationCountry 收货国家（空 = 没有）
   * @param destinationInEu 收货地是否落在欧盟辖区
   * @param customerType 买家类型（INDIVIDUAL / ORGANIZATION）
   * @param hasActiveRegistration 有没有生效的 IOSS 登记
   * @param shopInScope 店铺是否落在登记覆盖范围内
   * @param hasExciseGoods 是否含应税消费品
   * @param intrinsicValue 货值 + 运费（intrinsic value），已折算成**门槛币种**
   * @param threshold 免征门槛（门槛币种）
   * @param thresholdComparable 门槛能不能比较（false = 汇率读不到，没折算）
   */
  public record Inputs(
      boolean cancelled,
      String destinationCountry,
      boolean destinationInEu,
      String customerType,
      boolean hasActiveRegistration,
      boolean shopInScope,
      boolean hasExciseGoods,
      BigDecimal intrinsicValue,
      BigDecimal threshold,
      boolean thresholdComparable) {}

  /**
   * 🔴 **唯一的判定入口**：把一笔订单归到一档，并给出原因。
   *
   * <p>十步顺序见类注释。返回值永不为 null，且 {@code filingScope} 永远是
   * {@link #FILING_SCOPES} 里的一档。
   */
  public static Decision classify(Inputs in) {
    if (in.cancelled()) {
      return new Decision(
          "OUT_OF_SCOPE",
          "ORDER_CANCELLED",
          "订单已取消 —— 取消的单不进申报统计。");
    }
    if (in.destinationCountry() == null || in.destinationCountry().isBlank()) {
      return new Decision(
          "UNRESOLVED",
          "NO_DESTINATION_COUNTRY",
          "这一单没有收货国家 —— 无法判断它是不是欧盟订单，"
              + "所以**不能**归进 IOSS，也**不能**归成不在范围。");
    }
    if (!in.destinationInEu()) {
      return new Decision(
          "OUT_OF_SCOPE",
          "NON_EU_DESTINATION",
          "收货地 " + in.destinationCountry() + " 不在欧盟 —— 不属于本模块的申报范围。");
    }
    if (in.customerType() == null || in.customerType().isBlank()) {
      return new Decision(
          "UNRESOLVED",
          "NO_CUSTOMER_TYPE",
          "这一单的买家类型**没采到** ⇒ B2C / B2B 判不了 —— "
              + "**不许**默认当成 B2C（那会把 B2B 单错算进 IOSS）。");
    }
    if ("ORGANIZATION".equals(in.customerType())) {
      return new Decision(
          "GENERAL_VAT",
          "B2B_ORDER",
          "B2B 订单 —— 欧盟内 B2B 走反向征税，不适用 IOSS。");
    }
    if (!in.hasActiveRegistration()) {
      return new Decision(
          "GENERAL_VAT",
          "NO_ACTIVE_REGISTRATION",
          "本租户没有生效的 IOSS 登记 —— 没有 IOSS 就没有 IOSS 订单，"
              + "这一单按一般 VAT 处理。");
    }
    if (!in.shopInScope()) {
      return new Decision(
          "GENERAL_VAT",
          "SHOP_OUT_OF_SCOPE",
          "下单店铺不在 IOSS 登记的覆盖范围内 —— 这一单按一般 VAT 处理。");
    }
    if (in.hasExciseGoods()) {
      return new Decision(
          "GENERAL_VAT",
          "EXCISE_GOODS",
          "订单含应税消费品（酒 / 烟 / 能源）—— **IOSS 不覆盖应税消费品**，"
              + "哪怕货值没超过门槛也走一般 VAT。");
    }
    if (!in.thresholdComparable()) {
      return new Decision(
          "UNRESOLVED",
          "THRESHOLD_UNRESOLVED",
          "订单币种与门槛币种不同，而汇率**读不到** ⇒ 折算不出货值，"
              + "无法判断有没有超过免征门槛 —— 判不了，**不许**拿猜的汇率凑。");
    }
    if (in.intrinsicValue() != null
        && in.threshold() != null
        && in.intrinsicValue().compareTo(in.threshold()) > 0) {
      return new Decision(
          "GENERAL_VAT",
          "ABOVE_THRESHOLD",
          "货值 + 运费已超过免征门槛 —— 不适用 IOSS。");
    }
    return new Decision(
        "IOSS",
        "IOSS_ELIGIBLE",
        "欧盟 B2C 订单、货值未超免征门槛、非应税消费品、店铺在 IOSS 登记覆盖范围内"
            + " ⇒ 适用 IOSS。");
  }

  // ---------------------------------------------------------------- 数值纪律

  /**
   * 🔴 解析十进制金额 / 数量 / 税率：**禁浮点**，且**精度超限直接拒**。
   *
   * <p>不接受科学计数法（{@code 1E3}）、不接受空串、不接受非数字。
   * 税率的标度上限是 6（DB 的 {@code numeric(9,6)}）；金额与数量的标度上限同样是 6。
   *
   * @param raw 原始串
   * @param field 字段名（报错时写明是哪个字段）
   * @param scale 允许的最大标度
   * @return 解析出的 {@link BigDecimal}
   * @throws IllegalArgumentException 格式不对或精度超限时抛出（**不静默四舍五入**）
   */
  public static BigDecimal parseDecimal(String raw, String field, int scale) {
    String value = raw == null ? "" : raw.trim();
    if (value.isEmpty()) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    if (value.contains("e") || value.contains("E")) {
      throw new IllegalArgumentException(field + " 不接受科学计数法（请写普通的十进制数字）");
    }
    BigDecimal parsed;
    try {
      parsed = new BigDecimal(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(field + " 不是合法数字：" + value);
    }
    if (parsed.scale() > scale) {
      throw new IllegalArgumentException(
          field + " 的小数位超过 " + scale + " 位（收到 " + parsed.scale() + " 位）—— "
              + "不做静默四舍五入，请自己处理精度");
    }
    return parsed;
  }

  /**
   * 🔴 税率解析：标度上限 6，**且不许负数**。
   *
   * <p>{@code 0.19 = 19%} —— 税率一律 {@code numeric(9,6)}。
   */
  public static BigDecimal parseRate(String raw, String field) {
    BigDecimal rate = parseDecimal(raw, field, 6);
    if (rate.signum() < 0) {
      throw new IllegalArgumentException(field + " 不能是负数");
    }
    return rate;
  }

  /** 金额求和（统一标度，{@link RoundingMode#HALF_UP}）。 */
  public static BigDecimal sum(BigDecimal left, BigDecimal right) {
    if (left == null) {
      return right;
    }
    if (right == null) {
      return left;
    }
    return left.add(right);
  }

  /**
   * 🔴 折算：把一个金额换算成目标币种。
   *
   * <p>{@code rate} 的含义是「1 原币 = rate 目标币」（与 catalog 的
   * {@code GET /catalog/exchange-rates/latest} 的 base/quote 方向一致）。
   *
   * @return 折算后的金额（标度 6）
   */
  public static BigDecimal convert(BigDecimal amount, BigDecimal rate) {
    if (amount == null || rate == null) {
      return null;
    }
    return amount.multiply(rate).setScale(6, RoundingMode.HALF_UP);
  }

  /** 统一输出标度（6 位），避免页面上出现 {@code 150.0000000} 这种长度。 */
  public static String money(BigDecimal value) {
    return value == null ? "" : value.setScale(6, RoundingMode.HALF_UP).toPlainString();
  }
}
