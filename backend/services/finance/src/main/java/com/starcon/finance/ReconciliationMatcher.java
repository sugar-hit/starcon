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
 * 12.3.2.1 的**自动匹配引擎**（纯逻辑，不碰数据库）。
 *
 * <p>🔴 <b>卷 12 硬约束：「不允许『金额相同即匹配」——金额相同只是候选，必须结合订单号、日期、类型。」</b>
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
 *       <b>组内平台合计 = 组内我方合计</b>才算配平（容差内）。
 *       ⇒ 一对多 / 多对一的组也是**按合计**配平的（平台拆单 vs 我方拆单）。</li>
 * </ol>
 *
 * <p>另外：只对上 <b>1 个</b>维度的条目**不会**被丢掉，而是落成
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

  /** 一个候选对（外部条目 + 我方条目 + 对上的维度）。 */
  record Pair(Item external, Item book, List<String> dimensions, int dayDifference) {}

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
    var dims = new ArrayList<String>();
    var reference = external.normalizedReference();
    if (!reference.isEmpty() && reference.equals(book.normalizedReference())) {
      dims.add(DIM_REFERENCE);
    }
    if (external.date() != null && book.date() != null) {
      var diff = Math.abs((long) external.date().toEpochDay() - book.date().toEpochDay());
      if (diff <= windowDays) {
        dims.add(DIM_DATE);
      }
    }
    if (typeCompatible(external.type(), book.type())) {
      dims.add(DIM_TYPE);
    }
    return dims;
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
   * 跑一次匹配。
   *
   * @param external 平台账单侧的条目
   * @param book 我方账侧的条目（**同一个来源** —— 不同来源之间不互配）
   * @param windowDays 日期窗口（1..31）
   * @param tolerance 金额容差（默认 0）
   */
  static Outcome match(
      List<Item> external, List<Item> book, int windowDays, BigDecimal tolerance) {
    var tol = tolerance == null ? BigDecimal.ZERO : tolerance;

    // ---- 1. 候选生成：同币种 + 同方向 + 至少两个维度（**完全不看金额**）----
    var candidates = new ArrayList<Pair>();
    for (var e : external) {
      for (var b : book) {
        if (!e.currency().equals(b.currency())) {
          continue;
        }
        if (!e.direction().equals(b.direction())) {
          continue;
        }
        var dims = dimensions(e, b, windowDays);
        if (dims.size() < 2) {
          continue;
        }
        candidates.add(new Pair(e, b, dims, dayDifference(e, b)));
      }
    }
    // 确定性排序：维度多的优先 → 日期差小的优先 → id 小的优先。
    candidates.sort(
        Comparator.comparingInt((Pair p) -> -p.dimensions().size())
            .thenComparingInt(Pair::dayDifference)
            .thenComparing(p -> p.external().id().toString())
            .thenComparing(p -> p.book().id().toString()));

    var usedExternal = new HashSet<UUID>();
    var usedBook = new HashSet<UUID>();
    var groups = new ArrayList<Group>();
    var groupNo = 0;

    // ---- 2. 先吃「金额相等」的候选对（一对一的最强信号）----
    for (var candidate : candidates) {
      if (usedExternal.contains(candidate.external().id())
          || usedBook.contains(candidate.book().id())) {
        continue;
      }
      if (candidate.external().amount().subtract(candidate.book().amount()).abs().compareTo(tol) > 0) {
        continue;
      }
      groupNo++;
      usedExternal.add(candidate.external().id());
      usedBook.add(candidate.book().id());
      groups.add(
          new Group(
              UUID.randomUUID(),
              groupNo,
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
      var group = buildGroup(candidate, candidates, usedExternal, usedBook, windowDays, tol);
      if (group == null) {
        continue;
      }
      groupNo++;
      for (var pair : group.pairs()) {
        usedExternal.add(pair.external().id());
        usedBook.add(pair.book().id());
      }
      groups.add(
          new Group(
              UUID.randomUUID(),
              groupNo,
              shapeOf(group.pairs()),
              group.pairs(),
              group.externalAmount(),
              group.bookAmount(),
              group.difference(),
              group.balanced()));
    }

    // ---- 4. 未配上的条目 ----
    var unmatchedExternal =
        external.stream().filter(i -> !usedExternal.contains(i.id())).toList();
    var unmatchedBook = book.stream().filter(i -> !usedBook.contains(i.id())).toList();

    // ---- 5. 「差一点点」的近似对：金额相同、但只对上 1 个维度 ----
    var nearMisses = new ArrayList<NearMiss>();
    var consumedBook = new HashSet<UUID>();
    for (var e : unmatchedExternal) {
      for (var b : unmatchedBook) {
        if (consumedBook.contains(b.id())) {
          continue;
        }
        if (!e.currency().equals(b.currency()) || !e.direction().equals(b.direction())) {
          continue;
        }
        if (e.amount().subtract(b.amount()).abs().compareTo(tol) > 0) {
          continue;
        }
        var dims = dimensions(e, b, windowDays);
        if (dims.size() != 1) {
          continue;
        }
        nearMisses.add(new NearMiss(e, b, dims));
        consumedBook.add(b.id());
        break;
      }
    }
    var remainingBook =
        unmatchedBook.stream().filter(i -> !consumedBook.contains(i.id())).toList();

    return new Outcome(
        List.copyOf(groups),
        List.copyOf(unmatchedExternal),
        List.copyOf(remainingBook),
        List.copyOf(nearMisses));
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
      if (difference.abs().compareTo(tolerance) <= 0) {
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
    var balanced = difference.abs().compareTo(tolerance) <= 0;
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
            case DIM_REFERENCE -> "订单号/对方单号相等";
            case DIM_DATE -> "日期在窗口内（相差 " + pair.dayDifference() + " 天）";
            case DIM_TYPE -> "交易类型一致";
            default -> dim;
          });
    }
    parts.add("金额相等（组内合计相等）");
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
}
