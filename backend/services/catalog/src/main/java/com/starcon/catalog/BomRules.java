package com.starcon.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 3.5.2.1 BOM 版本管理的纯规则层。
 *
 * <p>不碰数据库、时钟与随机数：给定同样的输入永远返回同样的结论，所以「生效区间怎么判」 「损耗率怎么算」这两件事可以被单元测试逐条钉死，而不必起一个 PG16 容器。
 *
 * <p>生效区间 R1–R5 与 V23（3.5.1.1）**逐字同口径**，不另立一套：BOM 与套装的时间语义 都是「某一时刻该用哪一版」。所以本类不复制 {@link
 * BundleRules} 的区间判定， 而是直接转发 —— 同一个「区间重叠」写成两份，迟早有一份被改松，于是出现 「3.5.1.1 拦得住、3.5.2.1
 * 拦不住」这种只在跨模块操作里暴露的漏洞。
 *
 * <p>本类<B>自己</B>钉死的只有 BOM 独有的两条（V25 文件头第三节）：
 *
 * <ul>
 *   <li><b>R6 损耗率是百分数</b>：{@code 5} 表示 5%，不是比率 0.05。
 *   <li><b>R7 需求量</b>：{@code unit_quantity × outputQuantity × (1 + scrap_rate/100)}， {@link
 *       #requiredQuantity} 保留 6 位小数 HALF_UP。
 * </ul>
 */
public final class BomRules {
  private BomRules() {}

  /** 版本状态。与 V25 的 {@code bom_version.status} CHECK 同一口径。 */
  public static final List<String> STATUSES = List.of("DRAFT", "PUBLISHED");

  /** BOM 状态。与 V25 的 {@code bom.status} CHECK 同一口径。 */
  public static final List<String> BOM_STATUSES = List.of("ACTIVE", "ARCHIVED");

  /**
   * 单个版本最多多少个组件。
   *
   * <p>是<b>可用性</b>限制而不是数据库限制：一个 BOM 有 200 个组件时， 08 组装单领出来的料有 200 条，人已经没法核对「这批料到底对不对」，
   * 而领错的料是要上仓库去找回来的。
   */
  public static final int MAX_COMPONENTS_PER_VERSION = 50;

  /** 版本号长度上限，与 {@code bom_version.version_no varchar(32)} 一致。 */
  public static final int MAX_VERSION_NO_LENGTH = 32;

  /** 名称长度上限，与 {@code bom_version.name varchar(200)} 一致。 */
  public static final int MAX_NAME_LENGTH = 200;

  /** 单位用量的整数位数上限（19 - 6），与 {@code numeric(19,6)} 一致。 */
  public static final int QUANTITY_PRECISION = 13;

  /** 单位用量与需求量的共同小数位数，与 {@code numeric(19,6)} 一致。 */
  public static final int QUANTITY_SCALE = 6;

  /** 损耗率的小数位数上限，与 {@code bom_component.scrap_rate numeric(7,4)} 一致。 */
  public static final int SCRAP_SCALE = 4;

  /**
   * 损耗率上界（百分数）。
   *
   * <p>见 R6：100% 损耗 = 这个组件一丁点都用不上、只需要补损耗。 那一行不该留在 BOM 里（真要表达废料，请在成品上记，不该让「领料 0 件」出现在组装单上）， 所以数据库
   * CHECK 与本常量同取 100。
   */
  public static final BigDecimal SCRAP_MAX = new BigDecimal("100");

  /** 百分数换算基数。R6/R7 里「5 表示 5%」全靠这个 100。 */
  private static final BigDecimal PERCENT = new BigDecimal("100");

  // ================================================================ 规范化

  public static String normalize(String value) {
    return value == null ? "" : value.trim();
  }

  public static String normalizeUpper(String value) {
    return normalize(value).toUpperCase(Locale.ROOT);
  }

  /**
   * BOM 编码规范化为大写。
   *
   * <p>与 SKU/SPU/套装编码同一口径：编码是 06 采购建议与 08 加工单里的稳定引用， {@code bom-1} 与 {@code BOM-1} 各建一行会让「这个编码指哪个
   * BOM」无法回答。
   */
  public static String normalizeCode(String value) {
    return normalize(value).toUpperCase(Locale.ROOT);
  }

  /** 版本号规范化：只去空白，<b>不改大小写</b>（与套装版本号同理由：V1 与 v1 是两个标签）。 */
  public static String normalizeVersionNo(String value) {
    return normalize(value);
  }

  // ================================================================ 码表

  /** 版本状态是否在词表内。空输入不报错（不是筛选条件时要当「不过滤」）。 */
  public static String statusProblem(String status) {
    if (status.isEmpty()) return null;
    return STATUSES.contains(status) ? null : "BOM 版本状态只能是 DRAFT 或 PUBLISHED";
  }

  public static String bomStatusProblem(String status) {
    if (status.isEmpty()) return null;
    return BOM_STATUSES.contains(status) ? null : "BOM 状态只能是 ACTIVE 或 ARCHIVED";
  }

  public static String statusLabel(String status) {
    return switch (status) {
      case "DRAFT" -> "草稿";
      case "PUBLISHED" -> "已发布";
      default -> status;
    };
  }

  public static String bomStatusLabel(String status) {
    return switch (status) {
      case "ACTIVE" -> "启用";
      case "ARCHIVED" -> "已归档";
      default -> status;
    };
  }

  // ================================================================ 字段问题

  public static String codeProblem(String code) {
    if (code.isEmpty()) return "BOM 编码不能为空";
    if (code.length() > 64) return "BOM 编码最多 64 个字符";
    return null;
  }

  public static String versionNoProblem(String versionNo) {
    if (versionNo.isEmpty()) return "版本号不能为空";
    if (versionNo.length() > MAX_VERSION_NO_LENGTH)
      return "版本号最多 " + MAX_VERSION_NO_LENGTH + " 个字符";
    return null;
  }

  public static String nameProblem(String name) {
    if (name.isEmpty()) return "BOM 名称不能为空";
    if (name.length() > MAX_NAME_LENGTH) return "BOM 名称最多 " + MAX_NAME_LENGTH + " 个字符";
    return null;
  }

  public static String noteProblem(String note) {
    return note.length() > 500 ? "备注最多 500 个字符" : null;
  }

  /**
   * 单位用量问题（规则 R7 的因子）。
   *
   * <p>三条：必须大于 0、小数位 ≤ 6、整数位 ≤ 13（与 {@code numeric(19,6)} 逐字一致）。 用 {@code BigDecimal.compareTo} 而不是
   * {@code equals}， 否则 {@code 1} 与 {@code 1.0} 会被当成两个不同的数而误拒。
   */
  public static String quantityProblem(BigDecimal quantity) {
    if (quantity == null) return "单位用量不能为空";
    if (quantity.stripTrailingZeros().scale() > QUANTITY_SCALE)
      return "单位用量最多 " + QUANTITY_SCALE + " 位小数";
    if (quantity.compareTo(BigDecimal.ZERO) <= 0)
      return "单位用量必须大于 0：用量为 0 的组件是「配了但不领」——" + "组装单会领出一条 0 件，采购建议会提一个 0 需求，两边都答不出「这个组件到底要不要」";
    if (quantity.precision() - quantity.scale() > QUANTITY_PRECISION)
      return "单位用量整数部分最多 " + QUANTITY_PRECISION + " 位";
    return null;
  }

  /**
   * 损耗率问题（规则 R6）。
   *
   * <p>收的是<b>百分数</b>：{@code 5} = 5%。三条：非空、≥ 0、≤ 100，小数位 ≤ 4。
   */
  public static String scrapRateProblem(BigDecimal scrapRate) {
    if (scrapRate == null) return "损耗率不能为空（无损耗请填 0）";
    if (scrapRate.stripTrailingZeros().scale() > SCRAP_SCALE)
      return "损耗率最多 " + SCRAP_SCALE + " 位小数";
    if (scrapRate.compareTo(BigDecimal.ZERO) < 0) return "损耗率不能是负数";
    if (scrapRate.compareTo(SCRAP_MAX) > 0)
      return "损耗率不能超过 100%：100% 损耗等于这个组件一丁点都用不上、只需要补损耗，"
          + "那不是一条有效的 BOM 行（要表达废料请在成品上记，不该让组装单出现「领料 0 件」）";
    return null;
  }

  /** 组件清单规模问题。 */
  public static String componentCountProblem(int count) {
    if (count == 0) return "一个 BOM 至少要有一个组件：空组件的 BOM 算不出任何需求量，" + "08 组装单按它领不出任何料，06 采购建议按它提不出任何需求";
    if (count > MAX_COMPONENTS_PER_VERSION)
      return "一个 BOM 最多 " + MAX_COMPONENTS_PER_VERSION + " 个组件（可用性上限，不是数据库限制）：再多组装单领出来的料就没人核得对了";
    return null;
  }

  // ================================================================ R1 区间解析（转发 BundleRules）

  /**
   * 解析生效起点。收 ISO-8601（必须带时区偏移）。
   *
   * <p>不转发而保留签名一致是因为错误文案要带上「生效起点」这个字段名； 判定本身（{@code OffsetDateTime.parse} + 截到毫秒）与 V23 完全同源。
   */
  public static Instant parseInstant(String value, String field) {
    String text = normalize(value);
    if (text.isEmpty()) return null;
    try {
      OffsetDateTime parsed = OffsetDateTime.parse(text);
      // 精度收到毫秒：PG 的 timestamptz 是微秒精度，前端来回序列化时秒级以下会被截断，
      // 不收窄就会出现「保存后区间变了」。
      return parsed.toInstant().truncatedTo(ChronoUnit.MILLIS);
    } catch (DateTimeParseException e) {
      throw MediaRules.bad(
          field + " 不是合法的带时区时间（应为 2026-03-01T00:00:00Z 或 2026-03-01T08:00:00+08:00）：" + text);
    }
  }

  // ================================================================ R7 需求量（单层）

  /**
   * 需求量：生产 {@code outputQuantity} 个成品需要某组件多少（规则 R7）。
   *
   * <p>{@code required = unitQuantity × outputQuantity × (1 + scrapRate/100)}， 保留 {@value
   * #QUANTITY_SCALE} 位小数、{@link RoundingMode#HALF_UP}。
   *
   * <p><b>全 BigDecimal，禁 double</b>：这个数会被 08 按实物领料、被 06 按它下单， double 的 {@code 0.1 + 0.2}
   * 类误差在这里是「领出来的料比算出来的多几件」。
   *
   * <p><b>单层，只算一层</b>：多级递归展开是 3.5.2.2 的职责（BOM 展开）， 本方法只把「本版本直接列出的组件」乘一遍。
   *
   * <p><b>不取整成整数</b>：取整、补安全库存、判缺口是 06/3.5.2.2 的决定， 蓝图 3.5.2.2 写得很清楚「展开结果是建议不是事实」。
   * 提前取整就是在下游拿到单据之前先替它做了决定。
   */
  public static BigDecimal requiredQuantity(
      BigDecimal unitQuantity, BigDecimal scrapRate, BigDecimal outputQuantity) {
    if (unitQuantity == null || outputQuantity == null) return null;
    BigDecimal rate = scrapRate == null ? BigDecimal.ZERO : scrapRate;
    BigDecimal factor =
        BigDecimal.ONE.add(rate.divide(PERCENT, QUANTITY_SCALE + 4, RoundingMode.HALF_UP));
    return unitQuantity
        .multiply(outputQuantity)
        .multiply(factor)
        .setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 产量问题。
   *
   * <p>必须大于 0 且整数位 ≤ 13。产 0 个成品时需求量恒为 0，返回一份全是 0 的清单 会让人以为「这个 BOM 什么料都不用」—— 真状态是「你没说要生产几个」。
   */
  public static String outputQuantityProblem(BigDecimal outputQuantity) {
    if (outputQuantity == null) return "请填写要生产的成品数量";
    if (outputQuantity.stripTrailingZeros().scale() > 0) return "成品数量必须是整数：生产计划按件下达，0.5 个成品在仓库里不存在";
    if (outputQuantity.compareTo(BigDecimal.ZERO) <= 0)
      return "成品数量必须大于 0：产 0 个时每个组件的需求量都是 0，" + "拿到的清单会被误读成「这个 BOM 什么料都不用」";
    if (outputQuantity.precision() - outputQuantity.scale() > QUANTITY_PRECISION)
      return "成品数量整数部分最多 " + QUANTITY_PRECISION + " 位";
    return null;
  }

  // ================================================================ 生效区间 R1–R5（转发 BundleRules，不复制）

  /**
   * 区间顺序问题。{@code to} 为空表示开放区间（+∞），不算问题。
   *
   * <p>半开区间，所以 {@code to} 必须<b>严格大于</b> {@code from}。 转发 {@link BundleRules} 而不是复制：见类注释。
   */
  public static String rangeProblem(Instant from, Instant to) {
    return BundleRules.rangeProblem(from, to);
  }

  /** 两个半开区间是否重叠（规则 R1 + R2 的判定核心）。转发 {@link BundleRules}。 */
  public static boolean overlaps(Instant aFrom, Instant aTo, Instant bFrom, Instant bTo) {
    return BundleRules.overlaps(aFrom, aTo, bFrom, bTo);
  }

  /** 某个时刻是否落在这个已发布版本的区间里（规则 R4）。转发 {@link BundleRules}。 */
  public static boolean covers(Instant from, Instant to, Instant at) {
    return BundleRules.covers(from, to, at);
  }

  /**
   * 「把一个已发布版本关到 {@code newTo}」是否合法（规则 R5）。转发 {@link BundleRules}。
   *
   * @param now 当前时刻由调用方传入（这里不读系统时钟，保持纯函数）
   */
  public static String forwardCloseProblem(Instant currentTo, Instant newTo, Instant now) {
    return BundleRules.forwardCloseProblem(currentTo, newTo, now);
  }

  // ================================================================ 自检

  static {
    // 词表与 V25 的 CHECK 有笔误时这里直接炸，不要等到数据里出现第四个状态才发现。
    if (Set.copyOf(STATUSES).size() != STATUSES.size())
      throw new IllegalStateException("STATUSES 有重复项");
    if (Set.copyOf(BOM_STATUSES).size() != BOM_STATUSES.size())
      throw new IllegalStateException("BOM_STATUSES 有重复项");
    // numeric(19,6)：整数位 + 小数位必须刚好等于声明精度。
    if (QUANTITY_PRECISION + QUANTITY_SCALE != 19)
      throw new IllegalStateException("单位用量精度与 numeric(19,6) 不一致");
    // 规则 R6：上界必须是 100% 这个「百分数」语义下的数，不是 1。
    if (SCRAP_MAX.compareTo(PERCENT) != 0) throw new IllegalStateException("损耗率上界与「百分数」语义不一致（R6）");
  }
}
