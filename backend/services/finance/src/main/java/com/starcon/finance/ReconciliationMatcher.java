package com.starcon.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 12.3.2.1 / 12.3.2.3 的**自动匹配引擎**（纯逻辑，不碰数据库）。
 *
 * <p>🔴 <b>卷 12 硬约束：「不允许『金额相同即匹配』——金额相同只是候选，必须结合订单号、日期、类型。」</b>
 *
 * <p>本引擎把那句话落成**候选生成**与**成组判定**两道独立的闸：
 *
 * <ol>
 *   <li><b>候选生成完全不看金额。</b>两个条目要成为候选，必须在
 *       {@code {REFERENCE 订单号, DATE 日期窗口, TYPE 类型}} 里
 *       <b>至少对上两个</b>，并且**同币种、同方向**。
 *       ⇒ 「金额相同」**根本不是**候选生成条件 —— 这一条比「金额 + 其它一个维度」
 *       更严，也更贴蓝图原话（「必须结合订单号、日期、类型」）。</li>
 *   <li><b>金额相等是成组的必要条件，不是候选条件。</b>一个匹配组必须
 *       <b>组内平台合计 = 组内我方合计</b>才算配平。
 *       ⇒ 一对多 / 多对一的组也是**按合计**配平的（平台拆单 vs 我方拆单）。</li>
 * </ol>
 *
 * <p>另外：只对上 <b>1 个</b>维度的条目<b>不会</b>被丢掉，而是落成
 * {@code DIMENSION_MISMATCH} 差异 —— 页面上要能看见「这两笔金额一样，
 * 但订单号和类型都不同，所以没配上」。这正是「金额相同只是候选」的可观测证据。
 *
 * <p><b>算法（确定性，不依赖 HashMap 迭代序）</b>：
 *
 * <ol>
 *   <li>生成全部候选对，按「维度数降序 → 日期差升序 → id 升序」排序。</li>
 *   <li><b>先吃「金额相等」的候选对</b>（一对一的最强信号）：
 *       平台拆成两笔、我方也拆成两笔且金额一一对应时，应该落成两个一对一，
 *       而不是硬凑成多对多。</li>
 *   <li>再处理剩下的候选对：每对起一个组，然后<b>按差额贪心补齐</b>
 *       （差多少就补多少，补不齐 ⇒ 落成 AMOUNT_MISMATCH 差异）。</li>
 * </ol>
 *
 * <h2>12.3.2.3 的规则驱动改造</h2>
 *
 * <p>本引擎原先把「匹配哪几个维度、日期窗口多宽、金额容差多大」**写死在代码里**
 * （{@code match(external, book, windowDays, tolerance)}）。12.3.2.3 交付了
 * <b>可配置、可持久化、可统计</b>的规则集 ⇒ 引擎改成接受一条
 * {@link Spec}（规则的引擎侧视图）：
 *
 * <ul>
 *   <li><b>匹配键</b>（{@code matchKeys}）= 候选维度池，只能取
 *       {@code {REFERENCE, DATE, TYPE}}；<b>至少两个</b>。</li>
 *   <li><b>至少对上的维度数</b>（{@code minDimensionCount}）<b>≥ 2</b>。</li>
 *   <li><b>容差</b>：{@code amountTolerance} 作用在**候选配对带**上
 *       （|Δ| ≤ 它 ⇒ 可配对），<b>不放宽「配平」**（配平必须逐位相等 ——
 *       12.3.2.1 的 DB 触发器 {@code guard_finance_reconciliation_match_group_amount}
 *       要求组内两侧合计相等）。</li>
 *   <li><b>模糊兜底</b>（{@code fuzzyEnabled}）：模糊阶段把**订单号**改成
 *       <b>前缀</b>比、日期窗口放宽、残差带放宽，但
 *       <b>仍要求对上 minDimensionCount 个维度</b> ⇒ 兜底不会退化成
 *       「金额相同即匹配」。</li>
 *   <li><b>优先级</b>（{@code priority}）：规则集按优先级依次试，
 *       高优先级规则先挑走条目。</li>
 * </ul>
 *
 * <p>⚠️ <b>默认行为逐位不变</b>：{@code match(external, book, windowDays, tolerance)}
 * 现在等价于「一条默认规则」（匹配键 = 三个维度、最少对上 2 个、窗口 = 入参、
 * 容差 = 入参、无模糊）⇒ 12.3.2.1 的既有行为与既有测试**不受影响**。
 *
 * <p>⚠️ <b>一处刻意的修正</b>：「先吃金额相等的候选对」那一趟现在要求
 * <b>逐位相等</b>（原来写的是 |Δ| ≤ 容差）。原写法在容差 &gt; 0 时会造出
 * 「两侧合计不相等的匹配组」，而 12.3.2.1 的 DB 触发器要求组内合计相等
 * ⇒ 旧写法在容差 &gt; 0 时会**直接撞触发器报错**。容差现在只作用在
 * <b>候选配对带</b>上（这才是它该管的事），配平一律逐位相等。
 */
final class ReconciliationMatcher {

  private ReconciliationMatcher() {}

  /** 受控词表里的匹配维度（AMOUNT 只出现在人话说明里，**不参与**维度计数）。 */
  static final String DIM_REFERENCE = "REFERENCE";
  static final String DIM_DATE = "DATE";
  static final String DIM_TYPE = "TYPE";
  static final String DIM_AMOUNT = "AMOUNT";

  /** 匹配形态。 */
  static final String ONE_TO_ONE = "ONE_TO_ONE";
  static final String ONE_TO_MANY = "ONE_TO_MANY";
  static final String MANY_TO_ONE = "MANY_TO_ONE";
  static final String MANY_TO_MANY = "MANY_TO_MANY";

  /** 规则集里的一条规则（**引擎侧的最小视图**，不含持久化细节）。 */
  record Spec(
      String ruleId,
      int priority,
      List<String> matchKeys,
      int minDimensionCount,
      int dateWindowDays,
      BigDecimal amountTolerance,
      boolean fuzzyEnabled,
      int fuzzyReferencePrefixLength,
      int fuzzyDateWindowDays,
      BigDecimal fuzzyAmountTolerance) {

    /** 精确阶段的候选带。 */
    BigDecimal tolerance() {
      return amountTolerance == null ? BigDecimal.ZERO : amountTolerance;
    }

    /** 模糊阶段的候选带。 */
    BigDecimal fuzzyTolerance() {
      return fuzzyAmountTolerance == null ? BigDecimal.ZERO : fuzzyAmountTolerance;
    }
  }

  /**
   * 默认规则：匹配键 = 三个维度、最少对上 2 个、无模糊。
   *
   * <p>⇒ 与「12.3.2.1 写死的行为」**逐位等价**。
   */
  static Spec defaultSpec(int windowDays, BigDecimal tolerance) {
    return new Spec(
        "",
        100,
        List.of(DIM_REFERENCE, DIM_DATE, DIM_TYPE),
        2,
        windowDays,
        tolerance == null ? BigDecimal.ZERO : tolerance,
        false,
        8,
        31,
        tolerance == null ? BigDecimal.ZERO : tolerance);
  }

  /**
   * 一个待匹配条目（平台账单侧或我方账侧，字段已经归一）。
   *
   * <p>⚠️ {@code type} 允许为空（银行流水本身没有业务类型 ⇒ 空 ⇒ <b>永远不参与</b>维度计数）。
   */
  record Item(
      String kind,
      UUID id,
      String no,
      String reference,
      LocalDate date,
      String type,
      String direction,
      BigDecimal amount,
      String currency,
      String attribution) {

    /** 归一后的参考号（比较时忽略大小写与首尾空白）。 */
    String normalizedReference() {
      return reference == null ? "" : reference.trim().toUpperCase(java.util.Locale.ROOT);
    }
  }

  /**
   * 一个候选对（外部条目 + 我方条目 + 对上的维度）。
   *
   * @param fuzzy 这一对是不是**模糊兜底**阶段配上的（人话说明要讲得出来）。
   */
  record Pair(Item external, Item book, List<String> dimensions, int dayDifference, boolean fuzzy) {

    Pair(Item external, Item book, List<String> dimensions, int dayDifference) {
      this(external, book, dimensions, dayDifference, false);
    }
  }

  /** 一个匹配组。 */
  record Group(
      UUID groupKey,
      int groupNo,
      String shape,
      List<Pair> pairs,
      BigDecimal externalAmount,
      BigDecimal bookAmount,
      BigDecimal difference,
      boolean balanced) {

    /** 组内平台账单条目数。 */
    int externalCount() {
      return (int) pairs.stream().map(Pair::external).map(Item::id).distinct().count();
    }

    /** 组内我方账条目数。 */
    int bookCount() {
      return (int) pairs.stream().map(Pair::book).map(Item::id).distinct().count();
    }
  }

  /** 一条「差一点点」的近似对（只对上 1 个维度 ⇒ 不足以构成匹配）。 */
  record NearMiss(Item external, Item book, List<String> dimensions) {}

  /** 一次匹配的全部产出。 */
  record Outcome(
      List<Group> groups,
      List<Item> unmatchedExternal,
      List<Item> unmatchedBook,
      List<NearMiss> nearMisses) {}

  // ================================================================ 类型兼容

  /**
   * 归一类型是否兼容。
   *
   * <p>⚠️ **对称**：{@code compatible(a,b) == compatible(b,a)}。
   */
  static boolean typeCompatible(String a, String b) {
    if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
      return false;
    }
    return switch (a) {
      case "SALE" -> "SALE".equals(b);
      case "REFUND" -> "REFUND".equals(b);
      case "COMPENSATION" -> "COMPENSATION".equals(b);
      case "SETTLEMENT" -> "SETTLEMENT".equals(b);
      case "FEE" -> "FEE".equals(b) || "PAYMENT".equals(b);
      case "TAX" -> "TAX".equals(b) || "PAYMENT".equals(b);
      case "ADJUSTMENT" -> "ADJUSTMENT".equals(b) || "PAYMENT".equals(b);
      case "PAYMENT" ->
          "PAYMENT".equals(b) || "FEE".equals(b) || "TAX".equals(b) || "ADJUSTMENT".equals(b);
      default -> false;
    };
  }

  // ================================================================ 维度判定

  /** 两个条目对上的维度（**只看订单号 / 日期 / 类型**，完全不看金额）。 */
  static List<String> dimensions(Item external, Item book, int windowDays) {
    return dimensions(
        external, book, windowDays, List.of(DIM_REFERENCE, DIM_DATE, DIM_TYPE), false, 8);
  }

  /**
   * 两个条目对上的维度（**规则驱动**：只看规则声明的匹配键）。
   *
   * @param matchKeys 候选维度池（只能取 {REFERENCE, DATE, TYPE}）
   * @param fuzzy 是不是模糊兜底阶段（订单号改按**前缀**比）
   * @param prefixLength 模糊阶段的订单号前缀长度
   */
  static List<String> dimensions(
      Item external,
      Item book,
      int windowDays,
      List<String> matchKeys,
      boolean fuzzy,
      int prefixLength) {
    var dims = new ArrayList<String>();
    for (var key : matchKeys == null ? List.<String>of() : matchKeys) {
      switch (key) {
        case DIM_REFERENCE -> {
          if (referenceMatches(external, book, fuzzy, prefixLength)) {
            dims.add(DIM_REFERENCE);
          }
        }
        case DIM_DATE -> {
          if (dateMatches(external, book, windowDays)) {
            dims.add(DIM_DATE);
          }
        }
        case DIM_TYPE -> {
          if (typeCompatible(external.type(), book.type())) {
            dims.add(DIM_TYPE);
          }
        }
        default -> {
          // ⚠️ 未知维度**不参与**计数（宁可少算一个维度，也不能把
          //   「金额」这类判据算成匹配维度）。
        }
      }
    }
    return dims;
  }

  /**
   * 订单号是否对上。
   *
   * <p>精确阶段：**逐字相等**（忽略大小写与首尾空白）。
   *
   * <p>模糊阶段：逐字不等时，比**前缀**（前 {@code prefixLength} 个字符）。
   * ⚠️ 前缀至少 4 个字符 —— 前缀太短形同没有单号，会把「金额相同」偷偷放回来。
   */
  static boolean referenceMatches(Item external, Item book, boolean fuzzy, int prefixLength) {
    var a = external.normalizedReference();
    var b = book.normalizedReference();
    if (a.isEmpty() || b.isEmpty()) {
      return false;
    }
    if (a.equals(b)) {
      return true;
    }
    if (!fuzzy) {
      return false;
    }
    var n = Math.min(prefixLength, Math.min(a.length(), b.length()));
    if (n < 4) {
      return false;
    }
    return a.substring(0, n).equals(b.substring(0, n));
  }

  /** 日期是否在窗口内。 */
  static boolean dateMatches(Item external, Item book, int windowDays) {
    if (external.date() == null || book.date() == null) {
      return false;
    }
    return dayDifference(external, book) <= windowDays;
  }

  /** 两个条目的日期差（天）。 */
  static int dayDifference(Item external, Item book) {
    if (external.date() == null || book.date() == null) {
      return 0;
    }
    return (int) Math.abs(external.date().toEpochDay() - book.date().toEpochDay());
  }

  // ================================================================ 匹配

  /**
   * 跑一次匹配（**默认规则**；与 12.3.2.1 改造前的行为逐位等价）。
   *
   * @param external 平台账单侧的条目
   * @param book 我方账侧的条目（**同一个来源** —— 不同来源之间不互配）
   * @param windowDays 日期窗口（1..31）
   * @param tolerance 金额容差（默认 0）
   */
  static Outcome match(
      List<Item> external, List<Item> book, int windowDays, BigDecimal tolerance) {
    return match(external, book, List.of(defaultSpec(windowDays, tolerance)));
  }

  /**
   * 跑一次匹配（**规则驱动**，12.3.2.3）。
   *
   * <p>规则集按 {@code priority} 升序依次试：**高优先级规则先挑走条目**。
   * 每条规则先跑**精确阶段**，再跑**模糊兜底阶段**（规则开了兜底才跑）。
   *
   * @param ruleSet 规则集（可空 / 空 ⇒ 退回默认规则）
   */
  static Outcome match(List<Item> external, List<Item> book, List<Spec> ruleSet) {
    var specs = orderedSpecs(ruleSet);
    var primary = specs.get(0);

    var usedExternal = new HashSet<UUID>();
    var usedBook = new HashSet<UUID>();
    var groups = new ArrayList<Group>();
    var groupNo = 0;

    for (var spec : specs) {
      var remainingExternal = remaining(external, usedExternal);
      var remainingBook = remaining(book, usedBook);
      if (remainingExternal.isEmpty() || remainingBook.isEmpty()) {
        break;
      }

      // ---- 精确阶段 ----
      var exact =
          matchOnce(remainingExternal, remainingBook, spec, false, usedExternal, usedBook);
      for (var group : exact) {
        groupNo++;
        groups.add(withGroupNo(group, groupNo));
      }

      // ---- 模糊兜底阶段（规则开了才跑）----
      if (!spec.fuzzyEnabled()) {
        continue;
      }
      var fuzzyExternal = remaining(external, usedExternal);
      var fuzzyBook = remaining(book, usedBook);
      if (fuzzyExternal.isEmpty() || fuzzyBook.isEmpty()) {
        continue;
      }
      var fuzzy =
          matchOnce(fuzzyExternal, fuzzyBook, spec, true, usedExternal, usedBook);
      for (var group : fuzzy) {
        groupNo++;
        groups.add(withGroupNo(group, groupNo));
      }
    }

    // ---- 未配上的条目 ----
    var unmatchedExternal = remaining(external, usedExternal);
    var unmatchedBook = remaining(book, usedBook);

    // ---- 「差一点点」的近似对：金额落在容差带内、但只对上 1 个维度 ----
    var nearMisses = new ArrayList<NearMiss>();
    var consumedBook = new HashSet<UUID>();
    var nearTolerance = primary.tolerance();
    for (var e : unmatchedExternal) {
      for (var b : unmatchedBook) {
        if (consumedBook.contains(b.id())) {
          continue;
        }
        if (!e.currency().equals(b.currency()) || !e.direction().equals(b.direction())) {
          continue;
        }
        if (delta(e.amount(), b.amount()).abs().compareTo(nearTolerance) > 0) {
          continue;
        }
        var dims =
            dimensions(
                e,
                b,
                primary.dateWindowDays(),
                primary.matchKeys(),
                false,
                primary.fuzzyReferencePrefixLength());
        if (dims.isEmpty() || dims.size() >= primary.minDimensionCount()) {
          continue;
        }
        nearMisses.add(new NearMiss(e, b, dims));
        consumedBook.add(b.id());
        break;
      }
    }
    var leftoverBook = unmatchedBook.stream().filter(i -> !consumedBook.contains(i.id())).toList();

    return new Outcome(
        List.copyOf(groups),
        List.copyOf(unmatchedExternal),
        List.copyOf(leftoverBook),
        List.copyOf(nearMisses));
  }

  /** 给组重新编号（规则集里多条规则的组要连续编号）。 */
  private static Group withGroupNo(Group group, int groupNo) {
    return new Group(
        group.groupKey(),
        groupNo,
        group.shape(),
        group.pairs(),
        group.externalAmount(),
        group.bookAmount(),
        group.difference(),
        group.balanced());
  }

  /** 规则集排序：优先级升序（数字小的先试）→ 规则 id（保证确定性）。 */
  static List<Spec> orderedSpecs(List<Spec> ruleSet) {
    if (ruleSet == null || ruleSet.isEmpty()) {
      return List.of(defaultSpec(7, BigDecimal.ZERO));
    }
    var specs = new ArrayList<>(ruleSet);
    specs.sort(
        Comparator.comparingInt(Spec::priority)
            .thenComparing(s -> s.ruleId() == null ? "" : s.ruleId()));
    return List.copyOf(specs);
  }

  /** 还没被任何规则挑走的条目。 */
  private static List<Item> remaining(List<Item> items, Set<UUID> used) {
    return items.stream().filter(i -> !used.contains(i.id())).toList();
  }

  /** 跑一趟匹配（一个阶段：精确或模糊）。 */
  private static List<Group> matchOnce(
      List<Item> external,
      List<Item> book,
      Spec spec,
      boolean fuzzy,
      Set<UUID> usedExternal,
      Set<UUID> usedBook) {
    var tolerance = fuzzy ? spec.fuzzyTolerance() : spec.tolerance();
    var windowDays = fuzzy ? spec.fuzzyDateWindowDays() : spec.dateWindowDays();
    var minDimensions = spec.minDimensionCount();

    // ---- 1. 候选生成：同币种 + 同方向 + 至少 minDimensions 个维度（**完全不看金额**）----
    var candidates = new ArrayList<Pair>();
    for (var e : external) {
      if (usedExternal.contains(e.id())) {
        continue;
      }
      for (var b : book) {
        if (usedBook.contains(b.id())) {
          continue;
        }
        if (!e.currency().equals(b.currency())) {
          continue;
        }
        if (!e.direction().equals(b.direction())) {
          continue;
        }
        var dims =
            dimensions(
                e, b, windowDays, spec.matchKeys(), fuzzy, spec.fuzzyReferencePrefixLength());
        if (dims.size() < minDimensions) {
          continue;
        }
        candidates.add(new Pair(e, b, dims, dayDifference(e, b), fuzzy));
      }
    }
    // 确定性排序：维度多的优先 → 日期差小的优先 → id 小的优先。
    candidates.sort(
        Comparator.comparingInt((Pair p) -> -p.dimensions().size())
            .thenComparingInt(Pair::dayDifference)
            .thenComparing(p -> p.external().id().toString())
            .thenComparing(p -> p.book().id().toString()));

    var groups = new ArrayList<Group>();

    // ---- 2. 先吃「金额逐位相等」的候选对（一对一的最强信号）----
    for (var candidate : candidates) {
      if (usedExternal.contains(candidate.external().id())
          || usedBook.contains(candidate.book().id())) {
        continue;
      }
      // ⚠️ **逐位相等**：容差只管候选带（能不能配对），不管配平 ——
      //   12.3.2.1 的 DB 触发器要求组内两侧合计相等。
      if (candidate.external().amount().compareTo(candidate.book().amount()) != 0) {
        continue;
      }
      usedExternal.add(candidate.external().id());
      usedBook.add(candidate.book().id());
      groups.add(
          new Group(
              UUID.randomUUID(),
              0,
              ONE_TO_ONE,
              List.of(candidate),
              candidate.external().amount(),
              candidate.book().amount(),
              BigDecimal.ZERO,
              true));
    }

    // ---- 3. 再处理剩下的候选对：起组 + 按差额贪心补齐 ----
    for (var candidate : candidates) {
      if (usedExternal.contains(candidate.external().id())
          || usedBook.contains(candidate.book().id())) {
        continue;
      }
      var group =
          buildGroup(candidate, candidates, usedExternal, usedBook, windowDays, tolerance);
      if (group == null) {
        continue;
      }
      for (var pair : group.pairs()) {
        usedExternal.add(pair.external().id());
        usedBook.add(pair.book().id());
      }
      groups.add(
          new Group(
              group.groupKey(),
              group.groupNo(),
              shapeOf(group.pairs()),
              group.pairs(),
              group.externalAmount(),
              group.bookAmount(),
              group.difference(),
              group.balanced()));
    }

    return groups;
  }

  /** 起一个组并按差额贪心补齐。 */
  private static Group buildGroup(
      Pair seed,
      List<Pair> candidates,
      Set<UUID> usedExternal,
      Set<UUID> usedBook,
      int windowDays,
      BigDecimal tolerance) {
    var pairs = new ArrayList<Pair>();
    pairs.add(seed);
    var externalAmount = seed.external().amount();
    var bookAmount = seed.book().amount();

    // 最多补 200 步（防病态输入下的死循环）。
    for (int step = 0; step < 200; step++) {
      var difference = externalAmount.subtract(bookAmount);
      // ⚠️ **配平一律逐位相等**：容差只作用在候选带上。
      if (difference.signum() == 0) {
        break;
      }
      if (difference.signum() > 0) {
        // 还缺我方账 ⇒ 补一个我方账条目。
        // ⚠️ 只加**我方账**那一侧的金额：新条目与组内已有条目共享同一侧
        //   （一对多时新条目与种子共享平台账单行），两侧都加会**重复计**。
        var partner = findBookPartner(pairs, candidates, usedBook, difference);
        if (partner == null) {
          break;
        }
        pairs.add(partner);
        bookAmount = bookAmount.add(partner.book().amount());
      } else {
        // 还缺平台账单 ⇒ 补一个平台账单条目（同样只加平台账单那一侧）。
        var partner = findExternalPartner(pairs, candidates, usedExternal, difference.negate());
        if (partner == null) {
          break;
        }
        pairs.add(partner);
        externalAmount = externalAmount.add(partner.external().amount());
      }
    }

    var difference = bookAmount.subtract(externalAmount);
    var balanced = difference.signum() == 0;
    return new Group(
        UUID.randomUUID(),
        0,
        shapeOf(pairs),
        List.copyOf(pairs),
        externalAmount,
        bookAmount,
        balanced ? BigDecimal.ZERO : difference,
        balanced);
  }

  /**
   * 补一个我方账条目。
   *
   * <p>⚠️ 新条目必须**与组内某个平台账单条目有候选关系**（⇒ 至少两个维度对得上），
   * 否则「凑金额」就退化成「金额相同即匹配」。取金额不超过差额的最大者 = 最佳拟合。
   */
  private static Pair findBookPartner(
      List<Pair> group, List<Pair> candidates, Set<UUID> usedBook, BigDecimal gap) {
    var groupExternalIds = new HashSet<UUID>();
    for (var pair : group) {
      groupExternalIds.add(pair.external().id());
    }
    Pair best = null;
    for (var candidate : candidates) {
      if (usedBook.contains(candidate.book().id())) {
        continue;
      }
      if (!groupExternalIds.contains(candidate.external().id())) {
        continue;
      }
      if (candidate.book().amount().compareTo(gap) > 0) {
        continue;
      }
      if (best == null || candidate.book().amount().compareTo(best.book().amount()) > 0) {
        best = candidate;
      }
    }
    return best;
  }

  /**
   * 补一个平台账单条目。
   *
   * <p>⚠️ 新条目必须**与组内某个我方账条目有候选关系**（同上：至少两个维度对得上）。
   */
  private static Pair findExternalPartner(
      List<Pair> group, List<Pair> candidates, Set<UUID> usedExternal, BigDecimal gap) {
    var groupBookIds = new HashSet<UUID>();
    for (var pair : group) {
      groupBookIds.add(pair.book().id());
    }
    Pair best = null;
    for (var candidate : candidates) {
      if (usedExternal.contains(candidate.external().id())) {
        continue;
      }
      if (!groupBookIds.contains(candidate.book().id())) {
        continue;
      }
      if (candidate.external().amount().compareTo(gap) > 0) {
        continue;
      }
      if (best == null
          || candidate.external().amount().compareTo(best.external().amount()) > 0) {
        best = candidate;
      }
    }
    return best;
  }

  /** 由组内两侧的条目数定形态。 */
  static String shapeOf(List<Pair> pairs) {
    var externalCount = (int) pairs.stream().map(Pair::external).map(Item::id).distinct().count();
    var bookCount = (int) pairs.stream().map(Pair::book).map(Item::id).distinct().count();
    if (externalCount == 1 && bookCount == 1) {
      return ONE_TO_ONE;
    }
    if (externalCount == 1) {
      return ONE_TO_MANY;
    }
    if (bookCount == 1) {
      return MANY_TO_ONE;
    }
    return MANY_TO_MANY;
  }

  /** 人话版的匹配依据（页面要能说清「为什么这两行配成一对」）。 */
  static String basisOf(Pair pair) {
    var parts = new ArrayList<String>();
    for (var dim : pair.dimensions()) {
      parts.add(
          switch (dim) {
            case DIM_REFERENCE ->
                pair.fuzzy() ? "订单号前缀相近（模糊兜底）" : "订单号/对方单号相等";
            case DIM_DATE -> "日期在窗口内（相差 " + pair.dayDifference() + " 天）";
            case DIM_TYPE -> "交易类型一致";
            default -> dim;
          });
    }
    parts.add("金额相等（组内合计相等）");
    if (pair.fuzzy()) {
      parts.add("精确维度未对上 ⇒ 模糊兜底");
    }
    return String.join(" + ", parts);
  }

  /** 维度名去重且顺序稳定。 */
  static List<String> orderedDimensions(List<String> dimensions) {
    var ordered = new LinkedHashSet<String>();
    for (var dim : dimensions) {
      ordered.add(dim);
    }
    return List.copyOf(ordered);
  }

  /** 两个金额的差（绝对值）。 */
  static BigDecimal delta(BigDecimal a, BigDecimal b) {
    return a.subtract(b).abs();
  }
}
