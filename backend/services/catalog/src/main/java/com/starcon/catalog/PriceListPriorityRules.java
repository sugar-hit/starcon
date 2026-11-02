package com.starcon.catalog;

import com.starcon.catalog.PriceListPriorityModels.ConflictDimension;
import com.starcon.catalog.PriceListPriorityModels.DimensionCoverage;
import com.starcon.catalog.PriceListPriorityModels.PriorityRuleInput;
import com.starcon.catalog.PriceListPriorityModels.PriorityScopeType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 5.1.2.1 优先级与冲突检测：**纯规则**部分（无 IO、无租户上下文，可单测）。
 *
 * <p>本类回答四个问题：
 *
 * <ol>
 *   <li>优先级规则怎么归一化（上下文取值、重复、缺 GLOBAL）；
 *   <li>一个上下文里，**哪条规则生效**（specificity 顺序）；
 *   <li>什么时候算「同优先级 ⇒ 不可唯一解析」（阻断保存的条件）；
 *   <li>六个维度各自的覆盖情况（含「同 SKU 未覆盖」的原因）。
 * </ol>
 *
 * <h2>🔴 纪律一：数字越小 = 优先级越高</h2>
 *
 * <p>1 是最高优先级（国内 ERP 的习惯）。并列时**不可解析** —— 绝不随机取一条
 * （蓝图 5.1.1.2 关系句：「同优先级多条有效价目必须报错让用户选，不随机取」）。
 *
 * <h2>🔴 纪律二：specificity 顺序固定为 站点 &gt; 店铺 &gt; 法人 &gt; 全局</h2>
 *
 * <p>租户可以给某张价目表在**某个上下文**里单独排位（比它的全局默认更具体），
 * 但**绝不能**出现两条同样具体的规则同时命中 —— 那会让「哪条生效」变成猜谜。
 * 因此同一张表里同一个上下文只能有一条规则（DB 层 UNIQUE 约束）。
 *
 * <h2>🔴 纪律三：未配置优先级 = 不参与解析（而不是「价格是 0」）</h2>
 *
 * <p>没排进这场赛跑的价目表不参与解析，但它**仍然**出现在冲突报告里并标注原因 ——
 * 静默吞掉它的话，运营会以为自己的表在生效。
 */
public final class PriceListPriorityRules {

  private PriceListPriorityRules() {}

  /** 全局上下文在 {@code scope_ref} 里固定写成这个值（DB 层 CHECK 绑定）。 */
  public static final String GLOBAL_REF = "*";

  /**
   * 解析上下文：店铺 + （可选）站点 + （可选）法人。
   *
   * <p>🔴 三段都是**可选**的（站点可以为空：有些租户不按站点定价；法人可以为空：
   * 店铺没有绑定法人）。匹配时哪一段为空就跳过哪一段。
   */
  public record Context(
      String shopCode, String siteCode, String legalEntityCode) {

    /** 空串归一成 null（避免 "" 参与比较产生假匹配）。 */
    public Context {
      shopCode = blankToNull(shopCode);
      siteCode = blankToNull(siteCode);
      legalEntityCode = blankToNull(legalEntityCode);
    }

    private static String blankToNull(String raw) {
      if (raw == null) return null;
      var trimmed = raw.trim();
      return trimmed.isEmpty() ? null : trimmed;
    }
  }

  /**
   * 一条参与计算的优先级规则（内部形状：只有匹配与排序用得到的字段）。
   *
   * @param scopeType 上下文类型
   * @param scopeRef 上下文取值（GLOBAL 时是 {@link #GLOBAL_REF}）
   * @param priority 数字越小优先级越高
   */
  public record Rule(PriorityScopeType scopeType, String scopeRef, int priority) {

    /** 该规则是否命中给定上下文（忽略大小写 —— 编码的大小写不该改变语义）。 */
    public boolean matches(Context ctx) {
      if (ctx == null || scopeType == null) return false;
      return switch (scopeType) {
        case GLOBAL -> true;
        case SHOP -> same(ctx.shopCode(), scopeRef);
        case SITE -> same(ctx.siteCode(), scopeRef);
        case LEGAL_ENTITY -> same(ctx.legalEntityCode(), scopeRef);
      };
    }

    private static boolean same(String left, String right) {
      if (left == null || right == null) return false;
      return left.trim().equalsIgnoreCase(right.trim());
    }
  }

  // ================================================================ 归一化

  /**
   * 归一化优先级规则：去空白、去重、排序，并强制「有且只有一条 GLOBAL」。
   *
   * <p>排序口径：全局 → 法人 → 店铺 → 站点（从粗到细，与 5.1.1.1 的适用范围排序同一思路），
   * 同类按取值排 —— 页面上顺序稳定，diff 不会因为插入顺序而抖动。
   *
   * @throws IllegalArgumentException 缺 GLOBAL / 有多条 GLOBAL / 上下文重复 / 取值为空
   */
  public static List<PriorityRuleInput> normalizeRules(List<PriorityRuleInput> rules) {
    if (rules == null || rules.isEmpty()) {
      throw new IllegalArgumentException(
          "至少要有一条优先级规则 —— 没有优先级的价目表不参与解析（那等于它没有排进这场赛跑）");
    }
    // 第一遍：逐条校验 + 数 GLOBAL（**先数 GLOBAL 再查重复** ——
    // 两条 GLOBAL 是「只能有一条 GLOBAL」，不是「重复登记」，报错要说对）。
    var unique = new java.util.LinkedHashMap<String, PriorityRuleInput>();
    int globals = 0;
    for (var rule : rules) {
      if (rule == null || rule.scopeType() == null) {
        throw new IllegalArgumentException("优先级规则缺上下文类型");
      }
      if (rule.priority() == null) throw new IllegalArgumentException("优先级规则缺优先级数字");
      if (rule.priority() < 0 || rule.priority() > 9999) {
        throw new IllegalArgumentException("优先级必须是 0 到 9999 之间的数字（数字越小优先级越高）");
      }
      var ref =
          rule.scopeType() == PriorityScopeType.GLOBAL
              ? GLOBAL_REF
              : (rule.scopeRef() == null ? "" : rule.scopeRef().trim());
      if (rule.scopeType() == PriorityScopeType.GLOBAL) {
        globals++;
      } else if (ref.isEmpty()) {
        throw new IllegalArgumentException(
            rule.scopeType().label() + " 优先级规则必须给编码（法人编码 / 店铺编码 / 站点码）");
      }
      if (globals > 1) {
        throw new IllegalArgumentException("只能有一条 GLOBAL（全局）优先级规则");
      }
      var key = rule.scopeType().name() + "|" + ref.toUpperCase(Locale.ROOT);
      if (unique.containsKey(key)) {
        throw new IllegalArgumentException(
            "优先级规则重复：" + rule.scopeType().label() + " " + ref + "（同一张价目表里只登记一次）");
      }
      unique.put(
          key,
          new PriorityRuleInput(
              rule.scopeType(), ref, rule.priority(), rule.note() == null ? "" : rule.note().trim()));
    }
    if (globals == 0) {
      throw new IllegalArgumentException(
          "必须有一条 GLOBAL（全局）优先级规则 —— 没有它，价目表在某些上下文里就没有优先级");
    }
    var out = new ArrayList<>(unique.values());
    out.sort(
        (a, b) -> {
          var byType = Integer.compare(displayOrder(a.scopeType()), displayOrder(b.scopeType()));
          return byType != 0 ? byType : a.scopeRef().compareToIgnoreCase(b.scopeRef());
        });
    return out;
  }

  /**
   * 展示顺序：全局 → 法人 → 店铺 → 站点（从粗到细）。
   *
   * <p>🔴 与 {@link #specificity} **方向相反**且各有用途：
   * specificity 决定「哪条规则命中」（越具体越优先），displayOrder 决定「页面上按什么顺序排」
   * （与 5.1.1.1 的适用范围排序同一思路：先看粗的再看细的）。
   */
  public static int displayOrder(PriorityScopeType type) {
    if (type == null) return 3;
    return switch (type) {
      case GLOBAL -> 0;
      case LEGAL_ENTITY -> 1;
      case SHOP -> 2;
      case SITE -> 3;
    };
  }

  /**
   * specificity 顺序：站点最具体，全局最笼统。
   *
   * <p>数字越小越具体（命中时取最小的那条）。
   */
  public static int specificity(PriorityScopeType type) {
    if (type == null) return 3;
    return switch (type) {
      case SITE -> 0;
      case SHOP -> 1;
      case LEGAL_ENTITY -> 2;
      case GLOBAL -> 3;
    };
  }

  // ================================================================ 命中与并列

  /**
   * 一个上下文里生效的那条规则（最具体的那条）。
   *
   * @return 命中的规则；一张都没命中时返回 null（= 不参与解析）
   */
  public static Rule effectiveRule(List<Rule> rules, Context ctx) {
    if (rules == null || rules.isEmpty()) return null;
    Rule best = null;
    for (var rule : rules) {
      if (rule == null || !rule.matches(ctx)) continue;
      if (best == null || specificity(rule.scopeType()) < specificity(best.scopeType())) best = rule;
    }
    return best;
  }

  /**
   * 两边是否「同优先级 ⇒ 不可唯一解析」。
   *
   * <p>🔴 只有**两边都排了位**且数字相同才算并列。
   * 一边没排位（null）不算并列 —— 它压根不参与解析（纪律三），
   * 那种情况在冲突报告里以「未配置优先级」呈现。
   */
  public static boolean tie(Rule left, Rule right) {
    if (left == null || right == null) return false;
    return left.priority() == right.priority();
  }

  // ================================================================ 维度覆盖说明

  /**
   * 六个维度各自的覆盖情况。
   *
   * <p>🔴 「同 SKU」这一维度 {@code covered=false}，原因写清「上游未交付」——
   * 不许让界面显示成「没有冲突」。
   */
  public static List<DimensionCoverage> dimensionCoverage() {
    return List.of(
        new DimensionCoverage(
            ConflictDimension.SHOP,
            ConflictDimension.SHOP.label(),
            true,
            "已覆盖：两张价目表覆盖同一个店铺编码时命中（店铺来自 2.3.1.1 channel）。"),
        new DimensionCoverage(
            ConflictDimension.SITE,
            ConflictDimension.SITE.label(),
            true,
            "已覆盖：两张价目表覆盖同一个站点码时命中（站点来自 2.1.1.1 channel）。"
                + "店铺与站点的隶属关系也从 channel 读，所以「按店铺的表」与「按站点的表」撞上时能被发现。"),
        new DimensionCoverage(
            ConflictDimension.LEGAL_ENTITY,
            ConflictDimension.LEGAL_ENTITY.label(),
            true,
            "已覆盖：两张价目表覆盖同一个法人编码时命中（法人来自 17.1.2.2 identity）。"),
        new DimensionCoverage(
            ConflictDimension.TIME,
            ConflictDimension.TIME.label(),
            true,
            "已覆盖：两张价目表的生效区间 [起点, 终点) 有交集时命中 —— "
                + "区间重叠由数据库判（真实 SQL 谓词），不是把数据捞出来在内存里比。"),
        new DimensionCoverage(
            ConflictDimension.PRIORITY,
            ConflictDimension.PRIORITY.label(),
            true,
            "已覆盖：两边在同一上下文里的有效优先级相同 ⇒ 不可唯一解析 ⇒ 保存被拒并列出明细。"),
        new DimensionCoverage(
            ConflictDimension.SKU,
            ConflictDimension.SKU.label(),
            false,
            "🔴 **本模块不覆盖**：「同 SKU」要知道哪些 SKU 落在哪张价目表里，"
                + "那是价目项（price_list_item，**5.1.1.2 的写入所有权**）的事实，"
                + "而 5.1.1.2 尚未交付 ⇒ 本模块只做**价目级**检测。"
                + "这不是「没有冲突」，是「这一维度的判定依据还没交付」。"));
  }

  /** 「同 SKU 未覆盖」的一句话说明（能力声明 / 冲突报告 / 解析结果共用同一句）。 */
  public static String skuDimensionNote() {
    return "「同 SKU」维度未覆盖：价目项表 price_list_item 是 5.1.1.2 的写入所有权，尚未交付 ⇒ "
        + "本模块只做价目级冲突检测（两张价目表覆盖同一店铺/站点/法人且生效区间重叠）。"
        + "这一维度不是「没有冲突」，而是「判定依据还没交付」。";
  }
}
