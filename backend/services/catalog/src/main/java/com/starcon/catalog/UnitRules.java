package com.starcon.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 3.1.2.4 单位与换算的纯规则：量纲词表、角色词表、单位码字符集、精度与换算精度。
 *
 * <p>与 {@link BarcodeScheme} 同一条纪律：**能在保存前判定的，就不要留到换算时才炸**。 量纲不匹配（件 → 千克）如果等到 07
 * 库存记一笔账才发现，那笔账已经按错比例记完了； 单位码非法如果等到快照冻结才发现，采购单已经生成。
 *
 * <p>本类不碰数据库，因此可以在离线单测里穷举，与前端 {@code unit-conversion-model.ts} 同口径。
 *
 * <p>类名不叫 {@code UnitOfMeasure}：那是被静态导入的 DTO record，两者在同一批文件里 会直接撞名（构造器解析到纯规则类上，编译期才炸）。
 */
public final class UnitRules {
  private UnitRules() {}

  /** 单位角色。BASE 不落换算表——基础单位是 {@code sku.base_unit}，不在这里维护。 */
  public static final Set<String> ROLES = Set.of("PURCHASE", "SALE", "PACKAGING", "BASE");

  /** 可写换算表的角色（BASE 由基础单位本身承担，比例恒为 1，不占换算行）。 */
  public static final Set<String> CONVERSION_ROLES = Set.of("PURCHASE", "SALE", "PACKAGING");

  public static final Map<String, String> DIMENSIONS =
      Map.of(
          "WEIGHT", "重量",
          "LENGTH", "长度",
          "VOLUME", "体积",
          "COUNT", "数量",
          "PACKAGE", "包装");

  public static final Map<String, String> ROLE_LABELS =
      Map.of(
          "PURCHASE", "采购单位",
          "SALE", "销售单位",
          "PACKAGING", "包装单位",
          "BASE", "基础单位");

  public static final Map<String, String> DOCUMENT_TYPES =
      Map.of(
          "PURCHASE", "06 采购",
          "INBOUND", "07 入库",
          "OUTBOUND", "07 出库",
          "PICKING", "08 拣货",
          "TRANSFER", "07 调拨",
          "COUNT", "07 盘点");

  /**
   * 单位码字符集：中文、拉丁字母、数字、下划线、短横线，1~32 位。
   *
   * <p>与 {@link CatalogService#code} 的 [A-Z0-9] 词表**故意不同**——单位码不是内部编码， 真实数据里就是 '件' / '箱' / '千克'。套
   * ASCII 词表会把既有数据判成非法。
   */
  private static final String CODE_PATTERN = "[\\p{IsHan}A-Za-z0-9][\\p{IsHan}A-Za-z0-9_-]{0,31}";

  public static String normalizeCode(String value) {
    return value == null ? "" : value.trim();
  }

  /** 返回 null 表示可用；否则给出可执行的原因。离线可判，与后端写入口同口径。 */
  public static String codeProblem(String value) {
    String code = normalizeCode(value);
    if (code.isEmpty()) return "单位编码不能为空";
    if (!code.matches(CODE_PATTERN)) return "单位编码只能使用中文、英文字母、数字、下划线或短横线，长度 1~32 位";
    return null;
  }

  public static String normalizeDimension(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  public static String normalizeRole(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  public static String normalizeDocumentType(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  public static String dimensionLabel(String dimension) {
    return DIMENSIONS.getOrDefault(dimension, dimension);
  }

  public static String roleLabel(String role) {
    return ROLE_LABELS.getOrDefault(role, role);
  }

  public static String documentTypeLabel(String type) {
    return DOCUMENT_TYPES.getOrDefault(type, type);
  }

  /**
   * 换算比例的校验。返回 null 表示可用。
   *
   * <p>比例必须严格大于 0：0 会让基础数量恒为 0（入库记录写成 0 件），负数会让出库变成入库。 这两种都是会真实打错账的错误，所以在**保存换算版本**时就要拒绝。
   */
  public static String ratioProblem(BigDecimal quantityPerBase) {
    if (quantityPerBase == null) return "换算比例不能为空";
    if (quantityPerBase.signum() <= 0) return "换算比例必须大于 0";
    if (quantityPerBase.scale() > 6) return "换算比例最多 6 位小数";
    if (quantityPerBase.precision() - quantityPerBase.scale() > 13) return "换算比例的整数部分最多 13 位";
    return null;
  }

  /**
   * 基础数量 = 原始数量 × 换算比例，保留 6 位小数（与 numeric(19,6) 存储精度一致）。
   *
   * <p>用 HALF_UP 而不是截断：1 箱 = 3 件时，2.5 箱截断成 7 件会让账面少 0.5 件， 而 HALF_UP 仍会把差额留在累计里。四舍五入到 6
   * 位已经远细于任何实操精度。
   */
  public static BigDecimal toBaseQuantity(BigDecimal quantity, BigDecimal quantityPerBase) {
    return quantity.multiply(quantityPerBase).setScale(6, RoundingMode.HALF_UP);
  }

  /** 按单位精度展示数量：字典的 scale 决定单据上显示几位小数，空值不画成 0。 */
  public static String display(BigDecimal quantity, int scale) {
    if (quantity == null) return "—";
    return quantity.setScale(Math.max(0, Math.min(6, scale)), RoundingMode.HALF_UP).toPlainString();
  }

  /**
   * 同一 SKU 的同一单位同一角色只能有一个"当前生效"版本。
   *
   * <p>区间不重叠由 V6 的 {@code validate_unit_conversion_window} 触发器在 DB 层保证；
   * 这里只回答"这一天命中哪一版"，用于换算计算与快照冻结。查不到就是查不到—— 调用方据此报错，**不要**回退到"最近的一版"：静默回退会让一张按过期比例生成的 采购单看起来完全正常。
   */
  public static boolean effectiveOn(
      java.time.LocalDate from, java.time.LocalDate to, java.time.LocalDate at) {
    return !from.isAfter(at) && (to == null || !to.isBefore(at));
  }
}
