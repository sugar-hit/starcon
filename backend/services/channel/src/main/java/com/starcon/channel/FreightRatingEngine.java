package com.starcon.channel;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 10.1.2.1 运费计算器 的<b>计费引擎</b>。
 *
 * <p>蓝图：「输入收货国家、重量、体积、件数、申报价值、是否带电/液体等，输出各可用渠道的
 * 运费与预计时效」。本类是那个「运费」二字真正发生的地方：它把
 * <b>包裹参数 × 渠道规则集 × 附加费</b>变成钱。
 *
 * <p>🔴 三条纪律：
 *
 * <ol>
 *   <li><b>本类不读数据库，也没有任何费率常量</b>。档位、首重续重、体积重除数、
 *       舍入位数与舍入模式全部由调用方（{@link FreightCalculatorService}）从
 *       {@code freight_rate_card} / {@code freight_rate_band} / 上游 V136 的渠道配置
 *       与附加费读出来传进来。换承运商的费率是**改数据**，不是改代码发版。
 *   <li><b>没有 double / float</b>：金额与数量全程 {@link BigDecimal}。
 *   <li><b>算不出来就抛 {@link RatingException}，绝不返回 0</b>。一条 0 元运费会被
 *       当成「这家最便宜」选进 9.3.2.1 分仓与 5.2.3.2 费用测算 —— 而真相是
 *       「这条渠道的规则没配全」。
 * </ol>
 *
 * <p>本类可以脱离 Spring 单独 new 出来测，所以边界用例（重量段闭开、首重进位、
 * 半分舍入、体积重除数）在单测里钉死，不需要起容器。
 */
public final class FreightRatingEngine {

  /** 数量统一按 NUMERIC(19,6) 的精度折算（与列定义一致，不靠默认 MathContext）。 */
  private static final int QTY_SCALE = 6;

  /** 百分比换算的中間精度；比任何金额位数都高，所以它不会引入二次舍入误差。 */
  private static final MathContext PERCENT_MC = new MathContext(20, RoundingMode.HALF_UP);

  /**
   * 渠道级回退口径：<b>没有生效费率卡</b>时的舍入规则。
   *
   * <p>刻意做成具名常量并每次都写进 {@code calc_trace}：它是「这条运费的算式」的一部分，
   * 事后对账要能看出当时用的是回退口径而不是费率卡口径。它**不是费率来源** ——
   * 单价仍然来自渠道配置的 {@code base_rate}（DB）。
   */
  public static final String FALLBACK_ROUNDING_MODE = "HALF_UP";

  public static final int FALLBACK_ROUNDING_SCALE = 2;

  /** 无状态：服务层与测试都拿这一个实例（它没有字段，也不需要依赖）。 */
  public static final FreightRatingEngine INSTANCE = new FreightRatingEngine();

  private FreightRatingEngine() {}

  // ==================================================================
  // 输入
  // ==================================================================

  /**
   * 一个待试算的包裹。
   *
   * <p>尺寸三个要么都给（{@code hasDimensions}）要么都不给 —— 半套尺寸算出来的体积重
   * 是一个看起来正常的错数字，所以这一项在 DB 层也有 CHECK。
   */
  public record PackageSpec(
      BigDecimal weightKg,
      BigDecimal lengthCm,
      BigDecimal widthCm,
      BigDecimal heightCm,
      int pieces) {

    public boolean hasDimensions() {
      return lengthCm != null && widthCm != null && heightCm != null;
    }

    /** 体积（cm³）；没给尺寸返回 {@code null} 而不是 0（0 会被当成「体积为零的包裹」）。 */
    public BigDecimal volumeCm3() {
      if (!hasDimensions()) {
        return null;
      }
      return lengthCm.multiply(widthCm).multiply(heightCm);
    }
  }

  /**
   * 渠道侧的计费参数（全部来自上游 10.1.1.2 {@code logistics_channel_config}）。
   *
   * <p>刻意<b>不含</b>附加费与限制：那些由 {@link Surcharge} 与
   * 10.1.1.2 的判定端点分别提供，本类只负责把规则算成钱，不负责决定规则是否命中渠道。
   */
  public record ChannelTerms(
      String billingMode,
      String currency,
      BigDecimal baseRate,
      BigDecimal chargeDivisor,
      BigDecimal minCharge,
      String insuranceMode,
      BigDecimal insuranceRatePct,
      BigDecimal insuranceMinFee) {}

  /** 费率卡的一个计分档位。下界恒闭，上界闭开由 {@code toQtyInclusive} 决定。 */
  public record Band(
      int bandNo, BigDecimal fromQty, BigDecimal toQty, boolean toQtyInclusive, BigDecimal rateAmount) {}

  /** 费率卡（{@code freight_rate_card} + {@code freight_rate_band} 的内存形状）。 */
  public record RateCard(
      String chargeModel,
      String basis,
      String roundingMode,
      int roundingScale,
      BigDecimal volumetricDivisor,
      BigDecimal firstQty,
      BigDecimal firstAmount,
      BigDecimal additionalQty,
      BigDecimal additionalAmount,
      List<Band> bands) {

    public List<Band> orderedBands() {
      return bands.stream()
          .sorted(Comparator.comparing(Band::fromQty).thenComparing(Band::bandNo))
          .toList();
    }
  }

  /**
   * 一条附加费（上游 10.1.1.2 {@code logistics_channel_surcharge}）。
   *
   * <p>旺季费带生效区间；{@code effectiveFrom}/{@code effectiveTo} 为 {@code null} 表示常驻。
   */
  public record Surcharge(
      String surchargeKind,
      String chargeMode,
      BigDecimal amount,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveTo) {}

  // ==================================================================
  // 输出
  // ==================================================================

  /**
   * 一条渠道的试算结论。
   *
   * <p>{@code baseCharge} 等各档费用**已经按费率卡的舍入口径舍入过**，
   * {@code totalCharge} 是它们的和 —— 界面上分项与合计因此必然对得上
   * （DB 层 freight_quote_line 还有一条同口径的 CHECK 兜底）。
   */
  public record Rating(
      String rateSource,
      String basis,
      BigDecimal chargeableQty,
      BigDecimal volumetricQty,
      BigDecimal baseCharge,
      BigDecimal fuelFee,
      BigDecimal remoteFee,
      BigDecimal peakFee,
      BigDecimal insuranceFee,
      BigDecimal totalCharge,
      String roundingMode,
      int roundingScale,
      List<String> trace,
      List<String> notes) {}

  /** 算不出钱时的理由（渠道单条失败不影响整张对照表）。 */
  public static final class RatingException extends RuntimeException {
    public RatingException(String message) {
      super(message);
    }
  }

  // ==================================================================
  // 计算
  // ==================================================================

  /**
   * 算一条渠道的运费。
   *
   * @param pkg 包裹参数
   * @param terms 渠道计费参数（上游 10.1.1.2）
   * @param card 生效费率卡；{@code null} ⇒ 回落到渠道单值口径（{@code CHANNEL_FLAT}）
   * @param remoteArea 目的国对这个渠道是否算偏远（{@code freight_channel_country}）
   * @param surcharges 该渠道登记的附加费全集（上游 10.1.1.2）
   * @param declaredValue 申报价值；{@code null} 表示未提供（影响按申报价值投保的保费）
   * @param quotedAt 求值时刻 —— 旺季费按它判定，且调用方会把它存进试算单
   */
  public Rating rate(
      PackageSpec pkg,
      ChannelTerms terms,
      RateCard card,
      boolean remoteArea,
      List<Surcharge> surcharges,
      BigDecimal declaredValue,
      OffsetDateTime quotedAt) {

    List<String> trace = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    List<Surcharge> fees = surcharges == null ? List.of() : surcharges;

    String basis = card != null ? card.basis() : basisFor(terms.billingMode());
    // 口径一致性：费率卡量纲与渠道计费方式不一致 ⇒ 不猜，直接算不了。
    // V140 的 trigger (a) 已经在写入路径上拦过，这里是读路径的第二道（老数据 / 直写 SQL）。
    if (!basis.equals(basisFor(terms.billingMode()))) {
      throw new RatingException(
          "费率卡量纲 " + basis + " 与渠道计费方式 " + terms.billingMode() + " 不一致，无法取价");
    }

    // 体积重先算一次：trace 里不能因为算了两遍就出现两条一样的记录
    BigDecimal volumetric = volumetricQty(pkg, terms, card, basis, trace, notes);
    BigDecimal chargeable = chargeableQty(pkg, terms, card, basis, volumetric, trace, notes);

    String roundingMode = card != null ? card.roundingMode() : FALLBACK_ROUNDING_MODE;
    int scale = card != null ? card.roundingScale() : FALLBACK_ROUNDING_SCALE;
    String rateSource = card != null ? "RATE_CARD" : "CHANNEL_FLAT";
    trace.add(
        "口径来源="
            + ("RATE_CARD".equals(rateSource)
                ? "生效费率卡（" + card.chargeModel() + "）"
                : "渠道单值回退（未配置生效费率卡，base_rate=" + plain(terms.baseRate()) + "）")
            + "；舍入="
            + roundingMode
            + "@"
            + scale);

    // ---- ① 基础运费 ----
    BigDecimal baseRaw;
    if (card != null && "FIRST_PLUS_ADDITIONAL".equals(card.chargeModel())) {
      baseRaw = firstPlusAdditional(chargeable, card, trace);
    } else if (card != null) {
      baseRaw = flatRate(chargeable, card, trace);
    } else {
      baseRaw = flatFallback(chargeable, terms, trace);
    }

    // 最低收费：低于它按它收（上游 10.1.1.2 的 min_charge）。
    BigDecimal minCharge = terms.minCharge() == null ? BigDecimal.ZERO : terms.minCharge();
    if (baseRaw.compareTo(minCharge) < 0) {
      trace.add("基础运费 " + plain(baseRaw) + " 低于渠道最低收费，按最低收费 " + plain(minCharge) + " 收");
      baseRaw = minCharge;
    }
    BigDecimal base = round(baseRaw, roundingMode, scale);

    // ---- ② 燃油（百分比随基础运费浮动，或固定额）----
    BigDecimal fuel = round(fuelBase(fees, base, trace), roundingMode, scale);

    // ---- ③ 偏远（固定额，只在该国确实偏远时收）----
    BigDecimal remote =
        round(
            surchargeFixed(fees, "REMOTE_AREA", remoteArea, trace, quotedAt),
            roundingMode,
            scale);

    // ---- ④ 旺季（固定额，只在生效区间内收；区间左闭右开）----
    BigDecimal peak = round(surchargeFixed(fees, "PEAK_SEASON", true, trace, quotedAt), roundingMode, scale);

    // ---- ⑤ 保费 ----
    BigDecimal insurance = round(insurance(terms, declaredValue, notes, trace), roundingMode, scale);

    BigDecimal total = base.add(fuel).add(remote).add(peak).add(insurance);

    trace.add("合计=" + plain(total) + "（" + terms.currency() + "，分项之和）");
    return new Rating(
        rateSource, basis, chargeable, volumetric, base, fuel, remote, peak, insurance, total,
        roundingMode, scale, trace, notes);
  }

  // ------------------------------------------------------------------
  // 计费量
  // ------------------------------------------------------------------

  private static String basisFor(String billingMode) {
    return switch (billingMode == null ? "" : billingMode) {
      case "BY_WEIGHT" -> "WEIGHT";
      case "BY_VOLUME" -> "VOLUME";
      case "BY_PIECE" -> "PIECE";
      default -> throw new RatingException("未知计费方式：" + billingMode);
    };
  }

  /**
   * 计费量。
   *
   * <ul>
   *   <li>{@code WEIGHT}：实重；给了体积重除数时取 <b>max(实重, 体积重)</b> ——
   *       承运商按大的那个收，不取大的那个就是系统性少收。
   *   <li>{@code VOLUME}：按体积计费的渠道，量纲落在渠道自己的 {@code charge_divisor}
   *       折算出来的 kg 上（上游 10.1.1.2 语义：体积折算基数，默认 6000 cm³/kg）。
   *       没给尺寸时**算不了**，而不是按 0 体积报价。
   *   <li>{@code PIECE}：件数。
   * </ul>
   */
  private BigDecimal chargeableQty(
      PackageSpec pkg,
      ChannelTerms terms,
      RateCard card,
      String basis,
      BigDecimal volumetric,
      List<String> trace,
      List<String> notes) {
    return switch (basis) {
      case "WEIGHT" -> {
        BigDecimal actual = qty(pkg.weightKg());
        BigDecimal result = actual;
        if (volumetric != null && volumetric.compareTo(actual) > 0) {
          result = volumetric;
          trace.add("体积重 " + plain(volumetric) + "kg > 实重 " + plain(actual) + "kg，按体积重计费");
        }
        yield result;
      }
      case "VOLUME" -> {
        if (!pkg.hasDimensions()) {
          throw new RatingException("按体积计费的渠道必须给长宽高（cm）才能试算");
        }
        BigDecimal divisor = terms.chargeDivisor();
        if (divisor == null || divisor.compareTo(BigDecimal.ZERO) <= 0) {
          throw new RatingException("渠道未配置体积折算基数（charge_divisor），按体积计费算不了");
        }
        BigDecimal kg =
            pkg.volumeCm3()
                .divide(divisor, QTY_SCALE, RoundingMode.HALF_UP);
        trace.add("体积 " + plain(pkg.volumeCm3()) + "cm³ ÷ 折算基数 " + plain(divisor)
            + " = " + plain(kg) + "kg");
        yield kg;
      }
      default -> {
        if (pkg.pieces() <= 0) {
          throw new RatingException("按件计费的渠道必须给件数（≥1）才能试算");
        }
        yield BigDecimal.valueOf(pkg.pieces());
      }
    };
  }

  /**
   * 体积重（kg）。
   *
   * <p>按体积计费的渠道 {@code VOLUME}：量就是折算出来的 kg。
   * 按重计费的渠道 {@code WEIGHT}：只在费率卡配了 {@code volumetric_divisor} 时才算 ——
   * 没配就不做体积重折算（大多数渠道本就不折算）。
   */
  private BigDecimal volumetricQty(
      PackageSpec pkg,
      ChannelTerms terms,
      RateCard card,
      String basis,
      List<String> trace,
      List<String> notes) {
    if ("VOLUME".equals(basis)) {
      if (!pkg.hasDimensions()) {
        return null;
      }
      BigDecimal divisor = terms.chargeDivisor();
      if (divisor == null || divisor.compareTo(BigDecimal.ZERO) <= 0) {
        return null;
      }
      return pkg.volumeCm3().divide(divisor, QTY_SCALE, RoundingMode.HALF_UP);
    }
    if (!"WEIGHT".equals(basis)) {
      return null;
    }
    BigDecimal divisor = card == null ? null : card.volumetricDivisor();
    if (divisor == null) {
      return null;
    }
    if (!pkg.hasDimensions()) {
      notes.add("渠道配了体积重折算基数，但本次没给长宽高，按实重试算");
      return null;
    }
    if (divisor.compareTo(BigDecimal.ZERO) <= 0) {
      notes.add("体积重折算基数非法（" + plain(divisor) + "），本次忽略体积重");
      return null;
    }
    BigDecimal result = pkg.volumeCm3().divide(divisor, QTY_SCALE, RoundingMode.HALF_UP);
    trace.add("体积 " + plain(pkg.volumeCm3()) + "cm³ ÷ 体积重基数 " + plain(divisor)
        + " = 体积重 " + plain(result) + "kg");
    return result;
  }

  // ------------------------------------------------------------------
  // 两种取价模型
  // ------------------------------------------------------------------

  /**
   * 计分档位（{@code FLAT_RATE}）：逐段取「落在本段内的量 × 本段单价」再相加。
   *
   * <p>段区间语义见 V140 第三节：下界恒闭，上界闭开由 {@code toQtyInclusive} 决定，
   * {@code toQty == null} 表示无上限。分段求和的总量与闭开无关（都等于计费量），
   * 闭开影响的是**归属**（{@code trace} 里能看出某个重量落在哪一段）与
   * **重叠校验**（重叠会把同一个量算两遍钱）。
   *
   * <p>🔴 落在所有段之外（例如费率卡最后一段封顶而货更重）时抛异常而不是少算一段 ——
   * 少算出来的运费是一个看起来正常的数字。
   */
  private BigDecimal flatRate(BigDecimal chargeable, RateCard card, List<String> trace) {
    List<Band> bands = card.orderedBands();
    if (bands.isEmpty()) {
      throw new RatingException("费率卡（分段模型）没有任何档位，算不出运费");
    }
    BigDecimal total = BigDecimal.ZERO;
    BigDecimal covered = BigDecimal.ZERO;
    for (Band band : bands) {
      BigDecimal end =
          band.toQty() == null ? chargeable : chargeable.min(band.toQty());
      if (end.compareTo(band.fromQty()) <= 0) {
        continue;
      }
      BigDecimal segQty = end.subtract(band.fromQty());
      BigDecimal amount = segQty.multiply(band.rateAmount());
      covered = covered.add(segQty);
      total = total.add(amount);
      trace.add(
          "第 " + band.bandNo() + " 段 ["
              + plain(band.fromQty()) + ", "
              + (band.toQty() == null ? "∞" : plain(band.toQty()))
              // 无上界 = +∞，那个端点永远不闭合 ⇒ 写 ')'（DB 侧 band_range 同口径）
              + (band.toQty() != null && band.toQtyInclusive() ? "]" : ")")
              + " 命中 " + plain(segQty) + " × 单价 " + plain(band.rateAmount())
              + " = " + plain(amount));
    }
    if (covered.compareTo(chargeable) < 0) {
      throw new RatingException(
          "计费量 " + plain(chargeable) + " 超出了费率卡的最高一档（只覆盖到 "
              + plain(covered) + "），这一段没有单价 —— 请补一档无上限区间或修正最高档上界");
    }
    return total;
  }

  /**
   * 首重 + 续重（{@code FIRST_PLUS_ADDITIONAL}）。
   *
   * <p>算式：{@code 首重费 + ceil((计费量 − 首重量) ÷ 续重单位) × 续重费}。
   *
   * <p>🔴 续重的**进位方向固定是向上（CEILING）**，且刻意与金额的舍入模式分开：
   * 「0.5 件续重」在业务上不存在，少算半段续重等于白送承运商一段运费；而金额怎么
   * 舍入是承运商的对账口径，由费率卡的 {@code roundingMode} 决定。两者混成一个
   * {@code RoundingMode} 是这类计算最常见的错。
   */
  private BigDecimal firstPlusAdditional(BigDecimal chargeable, RateCard card, List<String> trace) {
    BigDecimal firstQty = card.firstQty();
    BigDecimal firstAmount = card.firstAmount();
    BigDecimal stepQty = card.additionalQty();
    BigDecimal stepAmount = card.additionalAmount();
    if (firstQty == null || firstAmount == null || stepQty == null || stepAmount == null) {
      throw new RatingException("首重/续重费率卡缺参数（首重或续重为空），算不出运费");
    }
    if (chargeable.compareTo(firstQty) <= 0) {
      trace.add("计费量 " + plain(chargeable) + "kg 不超过首重 " + plain(firstQty)
          + "kg，只收首重费 " + plain(firstAmount));
      return firstAmount;
    }
    BigDecimal remainder = chargeable.subtract(firstQty);
    // 向上进位：余 0.000001kg 也要算一整段续重
    BigDecimal steps =
        remainder.divide(stepQty, 0, RoundingMode.CEILING);
    trace.add(
        "首重 " + plain(firstQty) + "kg 收 " + plain(firstAmount)
            + "；余 " + plain(remainder) + "kg ÷ 续重单位 " + plain(stepQty)
            + " 进位得 " + plain(steps) + " 段 × " + plain(stepAmount));
    return firstAmount.add(steps.multiply(stepAmount));
  }

  /**
   * 无生效费率卡时的渠道单值回退：{@code 计费量 ÷ charge_divisor × base_rate}。
   *
   * <p>存在的理由：费率卡是本模块的新东西，而上游 10.1.1.2 的 {@code base_rate}
   * 本身就是一个合法的线性单价。完全不算会让「渠道配好了却算不出钱」；
   * 算但**标成 CHANNEL_FLAT 并写进 trace**，是为了让事后对账知道这条不是分段口径。
   */
  private BigDecimal flatFallback(BigDecimal chargeable, ChannelTerms terms, List<String> trace) {
    BigDecimal divisor = terms.chargeDivisor() == null ? BigDecimal.ONE : terms.chargeDivisor();
    if (divisor.compareTo(BigDecimal.ZERO) <= 0) {
      throw new RatingException("渠道折算基数非法（" + plain(divisor) + "），算不出运费");
    }
    BigDecimal result =
        chargeable.divide(divisor, QTY_SCALE + 6, RoundingMode.HALF_UP).multiply(terms.baseRate());
    trace.add("无生效费率卡，按渠道单值试算：" + plain(chargeable) + " ÷ " + plain(divisor)
        + " × 单价 " + plain(terms.baseRate()) + " = " + plain(result));
    return result;
  }

  // ------------------------------------------------------------------
  // 附加费
  // ------------------------------------------------------------------

  /** 燃油：{@code PERCENT} 随基础运费浮动，{@code FIXED} 是固定额。 */
  private BigDecimal fuelBase(List<Surcharge> surcharges, BigDecimal base, List<String> trace) {
    BigDecimal total = BigDecimal.ZERO;
    for (Surcharge s : surcharges) {
      if (!"FUEL".equals(s.surchargeKind())) {
        continue;
      }
      BigDecimal amount =
          "PERCENT".equals(s.chargeMode())
              ? base.multiply(s.amount()).divide(new BigDecimal("100"), PERCENT_MC)
              : s.amount();
      trace.add("燃油附加费（" + s.chargeMode() + " " + plain(s.amount()) + "）= " + plain(amount));
      total = total.add(amount);
    }
    return total;
  }

  /**
   * 取一条固定额附加费；{@code applicable} 为 {@code false} 时不收。
   *
   * <p>旺季区间判定为<b>左闭右开</b>（{@code from ≤ 时刻 < to}）：这样
   * {@code [10:00, 11:00)} 与 {@code [11:00, 12:00)} 首尾相接不重叠，
   * 区间交界那一刻归属唯一。V140 的列级 CHECK 只保证 {@code from < to}，
   * 闭开这一半钉在这里与集成测试里。
   */
  private BigDecimal surchargeFixed(
      List<Surcharge> surcharges, String kind, boolean applicable, List<String> trace, OffsetDateTime at) {
    BigDecimal total = BigDecimal.ZERO;
    for (Surcharge s : surcharges) {
      if (!kind.equals(s.surchargeKind()) || !applicable) {
        continue;
      }
      boolean inWindow =
          (s.effectiveFrom() == null || !at.isBefore(s.effectiveFrom()))
              && (s.effectiveTo() == null || at.isBefore(s.effectiveTo()));
      if (!inWindow) {
        trace.add(kind + " 附加费不在生效区间内，本次不收（区间 "
            + (s.effectiveFrom() == null ? "常驻" : s.effectiveFrom() + " ~ " + s.effectiveTo())
            + "，求值时刻 " + at + "）");
        continue;
      }
      trace.add(kind + " 附加费 = " + plain(s.amount()));
      total = total.add(s.amount());
    }
    return total;
  }

  // ------------------------------------------------------------------
  // 保费
  // ------------------------------------------------------------------

  /**
   * 保费（上游 10.1.1.2 的 {@code insurance_*}）。
   *
   * <ul>
   *   <li>{@code NONE} 不承保 ⇒ 0（保价上限那道硬边界由 10.1.1.2 的判定端点给 BLOCK）
   *   <li>{@code FIXED} 固定保价费 ⇒ {@code insurance_min_fee}
   *   <li>{@code DECLARED_VALUE} 按申报价值投保 ⇒ {@code max(申报价值 × 费率, 最低保费)}
   * </ul>
   *
   * <p>未提供申报价值时给 0 并记一条 {@code note}：这不是「不用交保费」，
   * 而是「这条保费算不出来」，让它在界面上保持可见比悄悄算成 0 好。
   */
  private BigDecimal insurance(
      ChannelTerms terms, BigDecimal declaredValue, List<String> notes, List<String> trace) {
    String mode = terms.insuranceMode() == null ? "NONE" : terms.insuranceMode();
    BigDecimal minFee = terms.insuranceMinFee() == null ? BigDecimal.ZERO : terms.insuranceMinFee();
    switch (mode) {
      case "FIXED" -> {
        trace.add("保价费（固定保价费）= " + plain(minFee));
        return minFee;
      }
      case "DECLARED_VALUE" -> {
        if (declaredValue == null) {
          notes.add("渠道按申报价值投保，但本次未提供申报价值，保费无法计算（按 0 计入合计）");
          trace.add("按申报价值投保但申报价值缺失 ⇒ 保费 0（须人工确认）");
          return BigDecimal.ZERO;
        }
        BigDecimal rate = terms.insuranceRatePct() == null ? BigDecimal.ZERO : terms.insuranceRatePct();
        BigDecimal byRate = declaredValue.multiply(rate).divide(new BigDecimal("100"), PERCENT_MC);
        BigDecimal result = byRate.max(minFee);
        trace.add("保费（申报价值 " + plain(declaredValue) + " × " + plain(rate) + "% = "
            + plain(byRate) + "，最低保费 " + plain(minFee) + "）= " + plain(result));
        return result;
      }
      default -> {
        trace.add("渠道不承保（insurance_mode=NONE），保费 0");
        return BigDecimal.ZERO;
      }
    }
  }

  // ------------------------------------------------------------------
  // 工具
  // ------------------------------------------------------------------

  /** 按费率卡的口径舍入到 {@code scale} 位（位数与模式都来自数据，不来自实现）。 */
  public static BigDecimal round(BigDecimal value, String mode, int scale) {
    if (value == null) {
      return null;
    }
    return value.setScale(scale, RoundingMode.valueOf(mode));
  }

  private static BigDecimal qty(BigDecimal value) {
    if (value == null) {
      throw new RatingException("缺少计费重量");
    }
    return value;
  }

  /** {@code toPlainString} + 去掉多余的尾随 0，避免 trace 里出现 21.000000 这种噪音。 */
  private static String plain(BigDecimal value) {
    if (value == null) {
      return "-";
    }
    BigDecimal stripped = value.stripTrailingZeros();
    return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
  }

  /** 供上层复用的口径校验（把非法 roundingMode 提前拒掉，而不是在 setScale 时炸）。 */
  public static boolean isRoundingMode(String mode) {
    return mode == null
        || switch (mode.toUpperCase(Locale.ROOT)) {
          case "HALF_UP", "HALF_EVEN", "HALF_DOWN", "CEILING", "FLOOR" -> true;
          default -> false;
        };
  }
}
