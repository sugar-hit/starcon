package com.starcon.catalog;

import java.util.List;
import java.util.Locale;

/**
 * 属性「有效值」的解析规则：SPU 公共值经过 SKU 覆写之后真正生效的那组值。
 *
 * <p>3.1.2.2 的属性区展示的是**有效值**，不是原始存的值——同一个属性在 SPU 上有公共值、在 SKU 上
 * 可能被覆写或被清空，直接把两行原始值并排摆出来，运营没法判断这个 SKU 实际是什么参数。
 * 3.2.2.2（SKU 属性覆写）尚未开发，所以规则在这里落成可单测的纯函数，详情端点与既有商品档案页
 * 共用同一份实现，避免两处对「有效值」的理解漂移。
 *
 * <p>为什么不用一条 SQL 的 COALESCE 搞定：这里必须区分三件 COALESCE 分不开的事——
 * <b>赋值</b>（SET）、<b>显式清空</b>（CLEAR）和<b>根本没有这一行</b>。前者取值，后两者都取不到值，
 * 但语义完全相反：清空是操作人主动抹掉的（不该退回公共值），没这一行才是继承。把它压成一个
 * COALESCE 会让「SKU 清空了某个公共属性」悄悄变回公共值，看起来像没清过。
 *
 * <p>取值模式（{@code attribute_definition.value_mode}）决定继承是否成立：
 * <ul>
 *   <li>{@code COMMON_LOCKED} 公共固定：SKU 侧不允许有赋值行（V1 的 {@code validate_value_owner}
 *       触发器已经拒绝），因此永远取公共值。
 *   <li>{@code COMMON_DEFAULT} 公共默认：公共值是默认，SKU 可以覆写或清空。
 *   <li>{@code SKU_ONLY} SKU 独立：只能由 SKU 赋值，没有 SKU 行就是未赋值，**不退回公共值**。
 * </ul>
 */
public final class EffectiveValues {
  private EffectiveValues() {}

  /** SPU 公共侧的取值模式。 */
  public static final String COMMON_LOCKED = "COMMON_LOCKED";
  public static final String COMMON_DEFAULT = "COMMON_DEFAULT";
  public static final String SKU_ONLY = "SKU_ONLY";

  /** SKU 没有单独赋值时的表达（此时 SKU 侧根本没有这一行）。 */
  public static final String INHERIT = "INHERIT";

  private static final List<String> NONE = List.of();

  /**
   * 有效值的来源。区分「谁的值在生效」与「为什么没有值」，两者都要显示：
   * 只说"没值"的话，运营无法分辨是自己漏填了还是这条属性本来就不适用。
   */
  public enum Source {
    /** SKU 显式赋值，覆盖了公共值。 */
    SKU_OVERRIDE("SKU 覆写"),
    /** SKU 显式清空了公共值：有效值就是空，且**不退回公共值**。 */
    SKU_CLEARED("SKU 已清空"),
    /** 继承 SPU 公共值。 */
    SPU_COMMON("SPU 公共值"),
    /** 没有任何赋值：SKU_ONLY 属性没填，或连公共值都没有。 */
    UNSET("未赋值");

    private final String label;

    Source(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public String code() {
      return name();
    }

    static Source parse(String value) {
      for (Source s : values())
        if (s.name().equals(value)) return s;
      throw new IllegalArgumentException("未知的有效值来源：" + value);
    }
  }

  /**
   * 一侧（SPU 公共 / SKU 覆写）的原始赋值。
   *
   * @param state {@code SET} / {@code CLEAR}；{@code null} 表示这一侧根本没有赋值行
   * @param values SET 时的取值，按 ordinal 升序
   */
  public record Raw(String state, List<String> values) {
    /** 这一侧是否真有赋值行。 */
    public boolean present() {
      return state != null;
    }

    /** 这一侧是否显式赋值（SET）。 */
    public boolean set() {
      return "SET".equals(state);
    }

    /** 这一侧是否显式清空（CLEAR）。 */
    public boolean cleared() {
      return "CLEAR".equals(state);
    }
  }

  /**
   * 解析结果。
   *
   * @param source 生效值的来源
   * @param overrideState SKU 侧的覆写状态：SET / CLEAR / INHERIT
   * @param rawValues SKU 侧原始存的取值（清空或继承时为空）
   * @param effectiveValues 真正生效的取值
   * @param inherited 是否继承自 SPU 公共值
   * @param missingRequired 必填属性却没有生效值——详情页据此把补全清单排在最前面
   */
  public record Resolution(
      Source source,
      String overrideState,
      List<String> rawValues,
      List<String> effectiveValues,
      boolean inherited,
      boolean missingRequired) {

    /** 展示用：把生效值拼成一行；没有生效值时返回空串而不是 "null"。 */
    public String effectiveText() {
      return String.join(" / ", effectiveValues);
    }
  }

  /** 解析：SKU 侧优先，公共值兜底，SKU_ONLY 永不兜底。 */
  public static Resolution resolve(String valueMode, Raw common, Raw sku, boolean required) {
    boolean locked = COMMON_LOCKED.equalsIgnoreCase(valueMode);
    // COMMON_LOCKED 下 SKU 侧不允许有赋值行（V1 的 validate_value_owner 触发器已经拒绝），
    // 所以这里直接忽略可能读到的脏行：宁可用公共值，也不能把公共固定属性显示成 SKU 覆写。
    if (!locked && sku != null && sku.present()) {
      if (sku.cleared())
        return new Resolution(Source.SKU_CLEARED, "CLEAR", NONE, NONE, false, required);
      if (sku.set())
        return new Resolution(
            Source.SKU_OVERRIDE,
            "SET",
            sku.values() == null ? NONE : List.copyOf(sku.values()),
            sku.values() == null ? NONE : List.copyOf(sku.values()),
            false,
            required && (sku.values() == null || sku.values().isEmpty()));
      throw new IllegalArgumentException("未知的属性赋值状态：" + sku.state());
    }

    // SKU_ONLY 只能由 SKU 赋值，没有 SKU 行就是未赋值，**不退回公共值**。
    if (SKU_ONLY.equalsIgnoreCase(valueMode))
      return new Resolution(Source.UNSET, INHERIT, NONE, NONE, false, required);

    if (common != null && common.set())
      return new Resolution(
          Source.SPU_COMMON,
          INHERIT,
          NONE,
          common.values() == null ? NONE : List.copyOf(common.values()),
          true,
          required && (common.values() == null || common.values().isEmpty()));

    return new Resolution(Source.UNSET, INHERIT, NONE, NONE, false, required);
  }

  /** 模式标签，供详情页显示「公共固定 / 公共默认 · 可覆写 / SKU 独立」。 */
  public static String modeLabel(String valueMode) {
    return switch (valueMode == null ? "" : valueMode.toUpperCase(Locale.ROOT)) {
      case COMMON_LOCKED -> "公共固定";
      case COMMON_DEFAULT -> "公共默认 · 可覆写";
      case SKU_ONLY -> "SKU 独立";
      default -> throw new IllegalArgumentException("未知的属性取值模式：" + valueMode);
    };
  }
}