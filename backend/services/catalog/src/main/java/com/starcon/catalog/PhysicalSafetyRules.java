package com.starcon.catalog;

import static com.starcon.catalog.PhysicalSafetyModels.PhysicalSafetyProfile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 3.7.2.1 物理与安全属性的<b>纯规则</b>：体积、体积重、渠道可用性、特殊申报、装箱体积核算。
 *
 * <p>抽成纯函数的理由同 {@link BarcodeScheme} 与 {@link BomRules}：这些判定决定的是 「这个 SKU
 * 能不能走这个渠道、要不要申报」，代价出现在**真实的面单与报关单**上， 而判定发生在录入那一刻。所以它们必须能不碰数据库逐条跑单元测试。
 *
 * <p><b>本类不落任何派生值</b>（V28 第一节）：体积重 = 体积 ÷ 除数，而除数是 <b>渠道自己的口径</b>，10 物流才有资格决定用哪个除数。落一列「体积重」等于偷偷替 10
 * 选定了除数，而它随渠道变化。
 *
 * <p><b>三条不可让步的纪律</b>：
 *
 * <ol>
 *   <li><b>缺一维就不给体积</b>（规则 R1）。拿 0 顶替会让一个 0 cm³ 的 SKU 算出 0 g 体积重， 渠道据此按「小件」给运费 —— 少收的钱收不回来。
 *   <li><b>安全属性未采集 ≠ 都不带</b>（规则 R2）。没有结论时渠道判定必须显式拒绝， 不能默认当成「普通货」放行：默认放行等于给了一条不填就能绕过申报的路。
 *   <li><b>渠道规则由调用方给</b>（规则 R3）。本模块不建渠道表（那是 10 的事实）， 只提供「按给定的渠道限制判定这个 SKU 能不能走」。
 * </ol>
 */
public final class PhysicalSafetyRules {
  /** mm³ → cm³。 */
  public static final BigDecimal MM3_PER_CM3 = new BigDecimal("1000");

  /** g → kg。 */
  public static final BigDecimal G_PER_KG = new BigDecimal("1000");

  /**
   * 体积重除数的<b>常见行业口径</b>，仅作为页面与接口的默认提示值。
   *
   * <p><b>本类不认定哪个除数是对的</b>：除数是渠道契约的一部分，不同渠道不同， 同一渠道不同时期也可能变。所以它是一个<b>入参</b>（{@link
   * #volumetricWeightKg}）， 这里的常量只是给「常见值」这一层说明用，调用方必须显式传自己的口径。
   */
  public static final BigDecimal AIR_DIVISOR = new BigDecimal("6000");

  public static final BigDecimal EXPRESS_DIVISOR = new BigDecimal("5000");
  public static final BigDecimal ROAD_DIVISOR = new BigDecimal("5000");

  private PhysicalSafetyRules() {}

  // ============================================================ 体积

  /**
   * 体积（cm³）：长宽高都真实采集到时才计算，缺一维返回 {@code null}（规则 R1）。
   *
   * <p>保留 3 位小数（与 3.1.2.2 详情页的 {@code volume} 同一口径，两个页面不能各算各的）。 某一维为 0 时同样返回 {@code null}：0
   * 长度的箱子不是「体积为 0」， 而是「这一维没量到」，两者在页面上会导出完全不同的运费。
   */
  public static BigDecimal volumeCm3(BigDecimal lengthMm, BigDecimal widthMm, BigDecimal heightMm) {
    if (!positive(lengthMm) || !positive(widthMm) || !positive(heightMm)) return null;
    return lengthMm
        .multiply(widthMm)
        .multiply(heightMm)
        .divide(MM3_PER_CM3, 3, RoundingMode.HALF_UP);
  }

  private static boolean positive(BigDecimal value) {
    return value != null && value.signum() > 0;
  }

  // ============================================================ 体积重

  /**
   * 体积重（kg）= 体积(cm³) ÷ 除数(cm³/kg)。
   *
   * <p><b>除数是入参不是常量</b>（规则 R3）。返回 {@code null} 的两种情况： 体积算不出来（规则 R1），或调用方没给除数 —— 后者不能默默套一个默认值：
   * 套了默认值就等于本模块替 10 物流选定了渠道口径，而它俩不一致时错的是运费。
   */
  public static BigDecimal volumetricWeightKg(BigDecimal volumeCm3, BigDecimal divisor) {
    if (volumeCm3 == null || divisor == null || divisor.signum() <= 0) return null;
    return volumeCm3.divide(divisor, 6, RoundingMode.HALF_UP);
  }

  /**
   * 计费重（kg）= max(实重, 体积重)。
   *
   * <p>渠道按体积重与实重里<b>较大者</b>计费，所以只给其中一个数渠道无法定价。 两个都算不出来时返回 {@code null}（不拿 0 顶替，理由同规则 R1）。
   *
   * <p>实重优先用<b>毛重</b>：包装本身也有重量，净重不含它。
   */
  public static BigDecimal chargeableWeightKg(
      BigDecimal grossWeightG, BigDecimal volumetricWeightKg) {
    BigDecimal actual =
        grossWeightG == null ? null : grossWeightG.divide(G_PER_KG, 6, RoundingMode.HALF_UP);
    if (actual == null) return volumetricWeightKg;
    if (volumetricWeightKg == null) return actual;
    return actual.max(volumetricWeightKg);
  }

  // ============================================================ 安全属性

  /** 四个安全标记的取值集合（V28 规则 B2：四个独立布尔，不合并成一个枚举）。 */
  public static final List<String> SAFETY_FLAGS =
      List.of("contains_battery", "contains_liquid", "dangerous_goods", "is_magnetic");

  public static final String CONTAINS_BATTERY = "contains_battery";
  public static final String CONTAINS_LIQUID = "contains_liquid";
  public static final String DANGEROUS_GOODS = "dangerous_goods";
  public static final String IS_MAGNETIC = "is_magnetic";

  public static String flagLabel(String flag) {
    return switch (flag) {
      case CONTAINS_BATTERY -> "带电（含电池）";
      case CONTAINS_LIQUID -> "液体";
      case DANGEROUS_GOODS -> "危险品";
      case IS_MAGNETIC -> "磁性";
      default -> flag;
    };
  }

  /** 命中的安全标记（按 V28 固定顺序，不随 HashMap 顺序漂移，页面上顺序才稳定）。 */
  public static List<String> activeFlags(PhysicalSafetyProfile profile) {
    List<String> active = new ArrayList<>(4);
    if (profile.containsBattery()) active.add(CONTAINS_BATTERY);
    if (profile.containsLiquid()) active.add(CONTAINS_LIQUID);
    if (profile.dangerousGoods()) active.add(DANGEROUS_GOODS);
    if (profile.isMagnetic()) active.add(IS_MAGNETIC);
    return active;
  }

  // ============================================================ 特殊申报

  /**
   * 需要申报的项目（规则 R2）。
   *
   * <p>四个标记各自对应一个申报动作，且互不蕴含。返回空列表有两种含义， 调用方<b>必须</b>配合 {@link #safetyCollected} 区分： 「已采集且都不带」=
   * 不需要申报；「未采集」= <b>不知道</b>，不能当成前者。
   */
  public static List<String> requiredDeclarations(PhysicalSafetyProfile profile) {
    List<String> required = new ArrayList<>(4);
    if (profile.containsBattery()) required.add("BATTERY");
    if (profile.containsLiquid()) required.add("LIQUID");
    if (profile.dangerousGoods()) required.add("DANGEROUS_GOODS");
    if (profile.isMagnetic()) required.add("MAGNETIC");
    return required;
  }

  public static String declarationLabel(String code) {
    return switch (code) {
      case "BATTERY" -> "电池申报";
      case "LIQUID" -> "液体申报";
      case "DANGEROUS_GOODS" -> "危险品申报";
      case "MAGNETIC" -> "磁性物申报";
      default -> code;
    };
  }

  // ============================================================ 渠道可用性

  /**
   * 一条渠道对某个 SKU 的限制。
   *
   * @param channelCode 渠道标识（<b>由调用方给</b>，本模块不建渠道表 —— 那是 10 的事实）
   * @param shippable 是否可发
   * @param blockers 阻断原因（可发时为空列表，不为 null）
   * @param declarations 该渠道下需要办的申报
   */
  public record ChannelVerdict(
      String channelCode, boolean shippable, List<String> blockers, List<String> declarations) {}

  /** 渠道限制的入参。四个 allowed* 都是「该渠道是否接受这一类货」。 */
  public record ChannelPolicy(
      String channelCode,
      boolean allowBattery,
      boolean allowLiquid,
      boolean allowDangerousGoods,
      boolean allowMagnetic,
      boolean requirePhysicalParameters) {}

  /**
   * 按给定渠道限制判定这个 SKU 能不能走（规则 R2 + R3）。
   *
   * <p><b>安全属性未采集时一律判不可发</b>（规则 R2）。理由：默认当成「普通货」放行， 等于给了一条「不填安全属性就能绕过申报」的路，而这正是本模块要堵的漏洞。 {@code
   * requirePhysicalParameters} 为真时同理：没有尺寸就算不出体积重， 渠道拿不到报价依据。
   */
  public static ChannelVerdict verdict(PhysicalSafetyProfile profile, ChannelPolicy policy) {
    List<String> blockers = new ArrayList<>();
    if (!profile.safetyCollected()) {
      // 未采集：这是「不知道」，不是「都不带」。渠道判定必须显式拒绝。
      blockers.add("SAFETY_NOT_COLLECTED");
    } else {
      if (profile.containsBattery() && !policy.allowBattery()) blockers.add("BATTERY_NOT_ALLOWED");
      if (profile.containsLiquid() && !policy.allowLiquid()) blockers.add("LIQUID_NOT_ALLOWED");
      if (profile.dangerousGoods() && !policy.allowDangerousGoods())
        blockers.add("DANGEROUS_GOODS_NOT_ALLOWED");
      if (profile.isMagnetic() && !policy.allowMagnetic()) blockers.add("MAGNETIC_NOT_ALLOWED");
    }
    if (policy.requirePhysicalParameters() && !profile.hasVolume()) {
      blockers.add("PHYSICAL_PARAMETERS_MISSING");
    }
    return new ChannelVerdict(
        policy.channelCode(),
        blockers.isEmpty(),
        List.copyOf(blockers),
        requiredDeclarations(profile));
  }

  public static String blockerLabel(String code) {
    return switch (code) {
      case "SAFETY_NOT_COLLECTED" -> "安全属性还没采集，渠道判定拒绝放行";
      case "BATTERY_NOT_ALLOWED" -> "带电货物，该渠道不收";
      case "LIQUID_NOT_ALLOWED" -> "液体货物，该渠道不收";
      case "DANGEROUS_GOODS_NOT_ALLOWED" -> "危险品，该渠道不收";
      case "MAGNETIC_NOT_ALLOWED" -> "磁性货物，该渠道不收";
      case "PHYSICAL_PARAMETERS_MISSING" -> "缺长宽高，算不出体积，渠道拿不到报价依据";
      default -> code;
    };
  }

  // ============================================================ 装箱体积核算（8 拣货）

  /** 一行装箱核算的结果。 */
  public record CartonLine(
      String skuCode,
      BigDecimal unitVolumeCm3,
      BigDecimal netWeightG,
      BigDecimal grossWeightG,
      int unitsPerCartonByVolume,
      int unitsPerCartonByWeight,
      int unitsPerCarton,
      boolean limitedByWeight,
      String note) {}

  /**
   * 装箱核算：给定每件的体积与重量、每箱可用容积与限重，算每箱能装几件。
   *
   * <p>取<b>体积与重量两条约束里更紧的那个</b>：只看体积会装出一箱超重的货， 只看重量会装出一箱塞不下的货。两条都算不出来（件没有体积也没有重量）时 {@code
   * unitsPerCarton = 0} 并在 note 里说明 —— 0 表示「算不出来」， 不表示「装 0 件」。
   *
   * @param cartonVolumeCm3 每箱可用容积（cm³）
   * @param cartonMaxGrossWeightG 每箱限重（g）
   */
  public static CartonLine carton(
      String skuCode,
      BigDecimal unitVolumeCm3,
      BigDecimal netWeightG,
      BigDecimal grossWeightG,
      BigDecimal cartonVolumeCm3,
      BigDecimal cartonMaxGrossWeightG) {
    int byVolume =
        (positive(unitVolumeCm3) && positive(cartonVolumeCm3))
            ? cartonVolumeCm3.divide(unitVolumeCm3, 0, RoundingMode.FLOOR).intValueExact()
            : 0;
    int byWeight =
        (positive(grossWeightG) && positive(cartonMaxGrossWeightG))
            ? cartonMaxGrossWeightG.divide(grossWeightG, 0, RoundingMode.FLOOR).intValueExact()
            : 0;

    boolean volumeKnown = byVolume > 0;
    boolean weightKnown = byWeight > 0;
    // 两条约束都算不出来时必须给 0，不能给 Integer.MAX_VALUE：后者会一路传到
    // 「需要几箱」而算出一个看似正常的 1 箱，把「算不出来」变成一个错的小数。
    int units;
    if (volumeKnown && weightKnown) units = Math.min(byVolume, byWeight);
    else if (volumeKnown) units = byVolume;
    else if (weightKnown) units = byWeight;
    else units = 0;
    boolean limitedByWeight = weightKnown && (!volumeKnown || byWeight < byVolume);
    String note =
        !volumeKnown && !weightKnown
            ? "这件既没有长宽高也没有毛重，算不出每箱可装几件"
            : !volumeKnown ? "缺长宽高，每箱件数只按限重算（偏大，实际装不下）" : "";
    return new CartonLine(
        skuCode,
        unitVolumeCm3,
        netWeightG,
        grossWeightG,
        byVolume,
        byWeight,
        units,
        limitedByWeight,
        note);
  }

  // ============================================================ 筛选

  /**
   * 包装方式筛选值：去首尾空格，全空当作不筛。
   *
   * <p>四个 {@code true/false} 标记在 {@link
   * com.starcon.catalog.PhysicalSafetyModels.PhysicalSafetyQuery} 里是 {@link Boolean}（null =
   * 不筛、false = 筛「明确不带」），直接按布尔绑进 SQL， <b>不</b>在这里转成字符串：转成 {@code "true"} 就会得到 {@code boolean =
   * character varying} 这种类型不匹配。
   */
  public static String normalizePackagingType(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /** 危险品 / 带电这类关键词的模糊搜索归一化。 */
  public static String normalizeKeyword(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    if (trimmed.isEmpty()) return null;
    if (trimmed.length() > 64) throw new IllegalArgumentException("搜索词最多 64 个字符");
    return trimmed.toLowerCase(Locale.ROOT);
  }

  /** 四个标记的展示文本，供列表页与详情页共用。 */
  public static String flagsText(PhysicalSafetyProfile profile) {
    if (!profile.safetyCollected()) return "未采集";
    Set<String> active = new LinkedHashSet<>(activeFlags(profile));
    if (active.isEmpty()) return "无特殊属性";
    List<String> labels = new ArrayList<>();
    for (String flag : SAFETY_FLAGS) if (active.contains(flag)) labels.add(flagLabel(flag));
    return String.join(" / ", labels);
  }
}
