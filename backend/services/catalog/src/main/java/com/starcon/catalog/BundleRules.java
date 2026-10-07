package com.starcon.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 3.5.1.1 套装版本管理的纯规则层。
 *
 * <p>不碰数据库、时钟与随机数：给定同样的输入永远返回同样的结论，所以「生效区间怎么判」
 * 这件事可以被单元测试逐条钉死，而不必起一个 PG16 容器。
 *
 * <p>本类最重要的产出是<b>生效区间的五条裁决规则</b>（R1..R5，V23 文件头第二节有完整论证）。
 * 这里把它们写成可执行的判定，全链路（服务层校验、DB 触发器报错文案、单元测试）用同一套口径：
 *
 * <ul>
 *   <li><b>R1 半开区间</b>：{@code [from, to)}，{@code to} 为 null 表示 +∞。
 *       {@link #overlaps} 用这个形状判重叠。
 *   <li><b>R2 重叠一律拒绝</b>：不裁决「谁生效」，直接判为冲突并报错。
 *   <li><b>R3 只有已发布版本参与判定</b>：{@link #overlaps} 的调用方负责先过滤 status。
 *   <li><b>R4 当前生效版本</b>：{@link #covers} 是纯函数式的时刻判定。
 *   <li><b>R5 只允许向前关闭</b>：{@link #forwardCloseProblem} 判定「把这个版本关到某个时刻」
 *       是否合法 —— 必须落在未来，且不能比原来的终点更晚。
 * </ul>
 */
public final class BundleRules {
  private BundleRules() {}

  /** 版本状态。与 V23 的 {@code bundle_version.status} CHECK 同一口径。 */
  public static final List<String> STATUSES = List.of("DRAFT", "PUBLISHED");

  /** 套装状态。与 V23 的 {@code bundle.status} CHECK 同一口径。 */
  public static final List<String> BUNDLE_STATUSES = List.of("ACTIVE", "ARCHIVED");

  /**
   * 单个版本最多多少个组件。
   *
   * <p>不是数据库限制，是<b>可用性</b>限制：一个套装有 200 个组件时，09 订单展开出来的
   * 子行有 200 条，人已经没法核对「这个套装到底包含什么」，而展开结果是要冻结进订单的。
   * 超了要在提交时就拒，而不是让订单里出现一条没人核过的 200 行子清单。
   */
  public static final int MAX_COMPONENTS_PER_VERSION = 50;

  /** 版本号长度上限，与 {@code bundle_version.version_no varchar(32)} 一致。 */
  public static final int MAX_VERSION_NO_LENGTH = 32;

  /** 套装名称长度上限，与 {@code bundle_version.name varchar(200)} 一致。 */
  public static final int MAX_NAME_LENGTH = 200;

  /** 组件数量的小数位数上限，与 {@code bundle_component.quantity numeric(19,6)} 一致。 */
  public static final int QUANTITY_SCALE = 6;

  /** 组件数量的整数位数上限（19 - 6）。 */
  public static final int QUANTITY_PRECISION = 13;

  // ================================================================ 规范化

  public static String normalize(String value) {
    return value == null ? "" : value.trim();
  }

  public static String normalizeUpper(String value) {
    return normalize(value).toUpperCase(Locale.ROOT);
  }

  /**
   * 套装编码规范化为大写。
   *
   * <p>与 3.1.1 的 SKU/SPU 编码同一口径：编码是刊登与订单里的稳定引用，
   * {@code bnd-1} 与 {@code BND-1} 各建一行会让「这个编码指哪个套装」无法回答。
   */
  public static String normalizeCode(String value) {
    return normalize(value).toUpperCase(Locale.ROOT);
  }

  /**
   * 版本号规范化：只去空白，**不改大小写**。
   *
   * <p>与编码不同，版本号是人写的标签（{@code v1}、{@code 2026-春季}），
   * 大小写折叠会让 {@code V1} 与 {@code v1} 合并成一个看不出区别的标签。
   */
  public static String normalizeVersionNo(String value) {
    return normalize(value);
  }

  // ================================================================ 码表问题

  /** 状态是否在词表内。空输入不报错（不是筛选条件时要当「不过滤」）。 */
  public static String statusProblem(String status) {
    if (status.isEmpty()) return null;
    return STATUSES.contains(status) ? null : "版本状态只能是 DRAFT 或 PUBLISHED";
  }

  /** 套装状态是否在词表内。 */
  public static String bundleStatusProblem(String status) {
    if (status.isEmpty()) return null;
    return BUNDLE_STATUSES.contains(status)
        ? null
        : "套装状态只能是 ACTIVE 或 ARCHIVED";
  }

  public static String statusLabel(String status) {
    return switch (status) {
      case "DRAFT" -> "草稿";
      case "PUBLISHED" -> "已发布";
      default -> status;
    };
  }

  public static String bundleStatusLabel(String status) {
    return switch (status) {
      case "ACTIVE" -> "启用";
      case "ARCHIVED" -> "已归档";
      default -> status;
    };
  }

  // ================================================================ 字段问题

  public static String codeProblem(String code) {
    if (code.isEmpty()) return "套装编码不能为空";
    if (code.length() > 64) return "套装编码最多 64 个字符";
    return null;
  }

  public static String versionNoProblem(String versionNo) {
    if (versionNo.isEmpty()) return "版本号不能为空";
    if (versionNo.length() > MAX_VERSION_NO_LENGTH)
      return "版本号最多 " + MAX_VERSION_NO_LENGTH + " 个字符";
    return null;
  }

  public static String nameProblem(String name) {
    if (name.isEmpty()) return "套装名称不能为空";
    if (name.length() > MAX_NAME_LENGTH) return "套装名称最多 " + MAX_NAME_LENGTH + " 个字符";
    return null;
  }

  public static String noteProblem(String note) {
    return note.length() > 500 ? "备注最多 500 个字符" : null;
  }

  /**
   * 组件数量问题。
   *
   * <p>三条：必须大于 0（小数位 6、整数位 13，与 {@code numeric(19,6)} 一致）。
   * 用 {@code BigDecimal.compareTo} 而不是 {@code equals}，
   * 否则 {@code 1} 与 {@code 1.0} 会被当成两个不同的数而误拒。
   */
  public static String quantityProblem(BigDecimal quantity) {
    if (quantity == null) return "组件数量不能为空";
    if (quantity.stripTrailingZeros().scale() > QUANTITY_SCALE)
      return "组件数量最多 " + QUANTITY_SCALE + " 位小数";
    if (quantity.compareTo(BigDecimal.ZERO) <= 0)
      return "组件数量必须大于 0：数量为 0 的组件会让 09 订单展开出一条 0 子行、"
          + "07 库存扣出一条 0 扣减，两边都答不出「这个组件到底要不要」";
    if (quantity.precision() - quantity.scale() > QUANTITY_PRECISION)
      return "组件数量整数部分最多 " + QUANTITY_PRECISION + " 位";
    return null;
  }

  /** 组件清单规模问题。 */
  public static String componentCountProblem(int count) {
    if (count == 0)
      return "一个套装至少要有一个组件：空组件的套装展开成订单行时什么都不是，"
          + "而刊登上却显示成一个能买的商品";
    if (count > MAX_COMPONENTS_PER_VERSION)
      return "一个套装最多 "
          + MAX_COMPONENTS_PER_VERSION
          + " 个组件（可用性上限，不是数据库限制）：再多订单展开出来的子行就没人核得对了";
    return null;
  }

  // ================================================================ R1 区间解析

  /**
   * 解析生效起点。收 ISO-8601（{@code 2026-03-01T00:00:00Z} 或带偏移的写法）。
   *
   * <p>只接受带时区的写法：没有偏移量的 {@code 2026-03-01T00:00:00} 会按 JVM 默认时区
   * 解析，同一份数据在不同机器上得到不同的生效时刻 —— 那是比「写错」更糟的错法。
   */
  public static Instant parseInstant(String value, String field) {
    String text = normalize(value);
    if (text.isEmpty()) return null;
    try {
      OffsetDateTime parsed = OffsetDateTime.parse(text);
      // 精度收到毫秒：PG 的 timestamptz 也是微秒精度，
      // 而前端来回序列化时秒级以下会被截断，不收窄就会出现「保存后区间变了」。
      return parsed.toInstant().truncatedTo(ChronoUnit.MILLIS);
    } catch (DateTimeParseException e) {
      throw MediaRules.bad(
          field + " 不是合法的带时区时间（应为 2026-03-01T00:00:00Z 或 2026-03-01T08:00:00+08:00）：" + text);
    }
  }

  /**
   * 区间顺序问题。{@code to} 为空表示开放区间（+∞），不算问题。
   *
   * <p>半开区间，所以 {@code to} 必须<b>严格大于</b> {@code from}；
   * 相等意味着「从不生效」（空区间），那是一个没有版本的版本。
   */
  public static String rangeProblem(Instant from, Instant to) {
    if (from == null) return "生效起点不能为空";
    if (to != null && !to.isAfter(from))
      return "生效终点必须晚于生效起点：生效区间是左闭右开的 [起点, 终点)，"
          + "终点等于起点等于「从不生效」";
    return null;
  }

  // ================================================================ R2 重叠判定

  /**
   * 两个半开区间是否重叠（规则 R1 + R2 的判定核心）。
   *
   * <p>{@code null} 端点按 +∞ 处理。用「严格小于/大于」而不是「小于等于」，
   * 这正是半开区间在边界上不相交的原因：{@code [a,b)} 与 {@code [b,c)} 在 {@code b} 处不重叠。
   */
  public static boolean overlaps(Instant aFrom, Instant aTo, Instant bFrom, Instant bTo) {
    boolean startsBeforeOtherEnds = aTo == null || bFrom.isBefore(aTo);
    boolean otherStartsBeforeEnds = bTo == null || aFrom.isBefore(bTo);
    return startsBeforeOtherEnds && otherStartsBeforeEnds;
  }

  // ================================================================ R4 当前生效版本

  /**
   * 某个时刻是否落在这个已发布版本的区间里（规则 R4）。
   *
   * <p>纯函数，不看库里有什么。{@code at} 落在左端点算命中、落在右端点算不命中。
   */
  public static boolean covers(Instant from, Instant to, Instant at) {
    if (from == null || at == null) return false;
    if (at.isBefore(from)) return false;
    return to == null || at.isBefore(to);
  }

  // ================================================================ R5 向前关闭

  /**
   * 「把一个已发布版本关到 {@code newTo}」是否合法。
   *
   * <p>三条都必须是「历史不被追溯改写」的推论（V23 规则 R5）：
   *
   * <ol>
   *   <li>不能传 null —— 重新变回「一直有效」会与已接替它的版本重叠；
   *   <li>{@code newTo} 必须在当前时刻之后 —— 落进过去等于追溯改写「那时生效的是哪一版」；
   *   <li>{@code newTo} 不能晚于原终点 —— 往后延同样会与接替版本重叠。
   * </ol>
   *
   * <p>「原终点」为 null（开放区间）时第 3 条自动满足。
   *
   * @param now 当前时刻，由调用方传入（这里不读系统时钟，保持纯函数）
   */
  public static String forwardCloseProblem(Instant currentTo, Instant newTo, Instant now) {
    if (newTo == null)
      return "关闭生效区间要指定一个具体时刻：留空等于重新变成「一直有效」，"
          + "那会让已经接替它的版本与它同时生效，「这个时刻生效的是哪一版」就不再唯一";
    if (now != null && !newTo.isAfter(now))
      return "生效终点必须落在当前时刻之后：设成已经过去的时刻等于追溯改写历史上生效的是哪一版，"
          + "而历史订单冻结的正是当时的版本";
    if (currentTo != null && newTo.isAfter(currentTo))
      return "生效终点只能收紧不能往后延：往后延会与已经接替它的版本重叠";
    return null;
  }

  // ================================================================ 自检

  static {
    // 词表与 V23 的 CHECK 有笔误时这里直接炸，不要等到数据里出现第四个状态才发现。
    if (Set.copyOf(STATUSES).size() != STATUSES.size())
      throw new IllegalStateException("STATUSES 有重复项");
    if (Set.copyOf(BUNDLE_STATUSES).size() != BUNDLE_STATUSES.size())
      throw new IllegalStateException("BUNDLE_STATUSES 有重复项");
    // numeric(19,6)：整数位 + 小数位必须刚好等于声明精度。
    if (QUANTITY_PRECISION + QUANTITY_SCALE != 19)
      throw new IllegalStateException("组件数量精度与 numeric(19,6) 不一致");
  }
}