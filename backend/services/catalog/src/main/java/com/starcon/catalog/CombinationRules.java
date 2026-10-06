package com.starcon.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 3.1.3.2 组合唯一性校验的纯规则：组合签名、提交批次内的重复定位、离线可判的结构问题。
 *
 * <p>与 {@link SpecAxisRules} 同一��纪律：**能在保存前判定的，就不要留到生成 SKU 矩阵时才炸**。
 * 本类不碰数据库，所以可以在离线单测里穷举，与前端 {@code combination-model.ts} 同口径。
 *
 * <p><b>签名是唯一性的载体</b>。蓝图要求"同一 SPU 下不存在重复的规格值组合"，
 * 而 DB 的唯一索引只能对**列**建——所以必须把「一组轴值」压成一个可比较的字符串。
 * 这里用「轴编码升序 + {@code 编码=取值} + {@code |} 连接」：
 *
 * <ul>
 *   <li><b>可读而不是哈希</b>。冲突清单要念给人听（"COLOR=红色|SIZE=大 与已存在的 SKU 重复"），
 *       哈希只能让人去猜是哪两个。
 *   <li><b>分隔符不可能出现在内容里</b>。取值字符集来自 V9 的 attribute_option 值源
 *       （{@code [\p{IsHan}A-Za-z0-9 _\-./]}），不含 {@code =} 与 {@code |}。
 *   <li><b>排序而不是按提交顺序</b>。同一个组合先写 SIZE 再写 COLOR，与先写 COLOR 再写 SIZE，
 *       必须是同一个值——否则唯一性只在"按固定顺序提交"时成立。
 *   <li><b>排序键是轴编码，用字节序</b>。V10 的延迟约束触发器在 DB 侧用
 *       {@code ORDER BY axis_code COLLATE "C"} 重算同一串签名，两边必须逐字节同序。
 *       轴编码被 {@link SpecAxisRules#codeProblem} 限死为 {@code [A-Z0-9_-]}（纯 ASCII），
 *       所以 Java 的 {@code String.compareTo}（按 UTF-16 码元逐个比较）与字节序等价。
 * </ul>
 */
public final class CombinationRules {
  private CombinationRules() {}

  /** 一次草稿生成允许的组合数上限。超了直接拒绝并报真实行数，不做静默截断。 */
  public static final int MAX_DRAFT_COMBINATIONS = 2000;

  /** 一个组合最多可含的轴数。挡住"把整个字典塞进一个组合"这类构造。 */
  public static final int MAX_COMBINATION_AXES = 12;

  public static final String PAIR_SEPARATOR = "|";
  public static final String ASSIGN = "=";

  /** 组合里的一对「轴编码 + 取值」，已归一化。 */
  public record Part(String axisCode, String optionValue) {}

  /**
   * 签名结果。
   *
   * @param signature 规范化签名；不可签名时为 null
   * @param problem 不可签名的原因；为 null 表示可签名
   */
  public record Signature(String signature, String problem) {}

  /**
   * 算出组合签名。
   *
   * <p>返回 {@link Signature} 而不是裸 String：签名算不出来也是一种业务结论
   * （同一组合里两条一样的轴 / 取值为空），需要单独报给用户，不能当成"签名是空串"混过去。
   *
   * @param duplicatesAmongSameRun 是否已发现同一次提交里同一条轴重复（决定能否给出签名）
   */
  public static Signature signature(List<Part> parts, Set<String> duplicateAxisCodes) {
    if (parts == null || parts.isEmpty()) return new Signature(null, "组合不能为空：一个 SKU 至少要有一个规格取值");
    if (duplicateAxisCodes != null && !duplicateAxisCodes.isEmpty())
      return new Signature(null, "同一个组合里出现了重复的规格轴：" + String.join("、", duplicateAxisCodes));
    for (Part part : parts) {
      if (part == null || part.axisCode() == null || part.axisCode().isBlank())
        return new Signature(null, "组合里有规格轴缺少编码");
      if (part.optionValue() == null || part.optionValue().isBlank())
        return new Signature(null, "规格轴「" + part.axisCode() + "」缺少取值");
    }
    List<Part> sorted = new ArrayList<>(parts);
    // 字节序：轴编码是纯 ASCII（见类注释），compareTo 与 DB 侧 COLLATE "C" 同序。
    sorted.sort(Comparator.comparing(Part::axisCode));
    var joined = new StringBuilder();
    for (Part part : sorted) {
      if (joined.length() > 0) joined.append(PAIR_SEPARATOR);
      joined.append(part.axisCode()).append(ASSIGN).append(part.optionValue());
    }
    return new Signature(joined.toString(), null);
  }

  /**
   * 找出同一次提交里重复出现的组合。
   *
   * <p>返回 {@code 签名 → 该签名出现的下标列表}（下标从 0 起，界面上标"第 N 条"用）。
   * 重复**必须按结构化清单返回**，不能只回一个布尔值：运营要知道是"红/大 和 深灰/大"
   * 撞了，还是同一个组合被粘贴了两遍，两种情况的处理完全不同。
   *
   * @param signatures 与入参等长的签名数组；null 项表示该条不可签名（另由调用方报结构问题）
   */
  public static Map<String, List<Integer>> duplicates(List<String> signatures) {
    Map<String, List<Integer>> seen = new LinkedHashMap<>();
    for (int i = 0; i < signatures.size(); i++) {
      String signature = signatures.get(i);
      if (signature == null || signature.isBlank()) continue;
      seen.computeIfAbsent(signature, key -> new ArrayList<>()).add(i);
    }
    var result = new LinkedHashMap<String, List<Integer>>();
    seen.forEach(
        (signature, indexes) -> {
          if (indexes.size() > 1) result.put(signature, List.copyOf(indexes));
        });
    return result;
  }

  /**
   * 一个组合里重复出现的轴编码。
   *
   * <p>与"两个组合重复"是两件事：这里是同一条组合内部就写了 COLOR 两次，
   * 那种组合连签名都不该有（签名是键，键本身不合法）。
   */
  public static Set<String> duplicateAxisCodes(List<String> axisCodes) {
    var seen = new LinkedHashSet<String>();
    var repeated = new LinkedHashSet<String>();
    if (axisCodes == null) return repeated;
    for (String code : axisCodes) {
      if (code == null) continue;
      if (!seen.add(code)) repeated.add(code);
    }
    return repeated;
  }

  /**
   * 组合覆盖的轴与该 SPU 已绑定的轴求差，得到"没填的轴"。
   *
   * <p>缺轴的组合不合法：一个 SPU 上所有已绑定的轴都必须取到值，否则同一个组合
   * 在不同人填法下会指向不同的 SKU（例如"红"是红/大还是红/中，取决于谁漏填了 SIZE）。
   */
  public static Set<String> missingAxisCodes(Set<String> boundAxisCodes, Set<String> presentAxisCodes) {
    var missing = new LinkedHashSet<String>();
    if (boundAxisCodes == null) return missing;
    for (String code : boundAxisCodes) if (!presentAxisCodes.contains(code)) missing.add(code);
    return missing;
  }

  /** 一个组合里出现的、该 SPU 没有绑定的轴（挡住"混用别的 SPU 的规格轴"）。 */
  public static Set<String> foreignAxisCodes(Set<String> boundAxisCodes, Set<String> presentAxisCodes) {
    var foreign = new LinkedHashSet<String>();
    if (presentAxisCodes == null) return foreign;
    for (String code : presentAxisCodes) if (!boundAxisCodes.contains(code)) foreign.add(code);
    return foreign;
  }

  /**
   * 笛卡尔积展开矩阵（各轴的可用选项相乘）。
   *
   * <p>入参是"每条轴的可用取值列表"，顺序即 {@code axes} 的顺序。
   * 返回组合的轴编码列表序列，位置对应入参下标。
   *
   * <p>有一条轴没有可用取值时直接返回空列表：那条轴还没配选项，矩阵就是空的，
   * 不该按 1 假装有值——界面上"没绑轴"与"绑了但没选项"是两回事（见 SpecAxisRules 同处注释）。
   *
   * @param cap 超过就停止并抛 {@link MatrixTooLarge}：展开到一半再丢弃会白烧内存，
   *     而 3 亿行的草稿本来就生成不了，不如当场报真实行数
   */
  public static List<List<String>> expand(List<List<String>> optionLists, int cap) {
    var combinations = new ArrayList<List<String>>();
    if (optionLists == null || optionLists.isEmpty()) return combinations;
    for (List<String> options : optionLists) {
      if (options == null || options.isEmpty()) return List.of();
    }
    combinations.add(List.of());
    for (List<String> options : optionLists) {
      var next = new ArrayList<List<String>>();
      for (List<String> prefix : combinations) {
        for (String option : options) {
          var row = new ArrayList<>(prefix);
          row.add(option);
          if (row.size() > MAX_COMBINATION_AXES)
            throw new IllegalStateException(
                "一个 SKU 最多只能有 " + MAX_COMBINATION_AXES + " 条规格轴");
          next.add(List.copyOf(row));
          if (next.size() > cap)
            throw new MatrixTooLarge(cap, matrixSize(optionLists));
        }
      }
      combinations = next;
    }
    return combinations;
  }

  /**
   * 笛卡尔积的**行数**，不真的展开。
   *
   * <p>与 {@link SpecAxisRules#matrixRowCount} 同口径，用 {@code long} 并钳到上限：
   * 界面要显示真实规模，而不是显示一个 {@code int} 溢出后的负数。
   * 有一条轴没有可用取值时返回 0。
   *
   * <p><b>先判再乘</b>，不是乘完再判：10 条各 100 个选项 = 10<sup>20</sup>，
   * 乘完已经溢出成 7766279631452241920 —— 那是个正数，会被"没超限"的判断放行，
   * 于是界面上显示出一个精确到个位的假行数。除法判界是唯一不会被这一步带偏的写法。
   */
  public static long matrixSize(List<List<String>> optionLists) {
    long rows = 1;
    if (optionLists == null || optionLists.isEmpty()) return 0;
    for (List<String> options : optionLists) {
      if (options == null || options.isEmpty()) return 0;
      long size = options.size();
      if (rows > MATRIX_LIMIT / size) return Long.MAX_VALUE;
      rows = rows * size;
    }
    return rows;
  }

  /** 行数上限：越过它就只报"规模过大"，不再给精确值。 */
  private static final long MATRIX_LIMIT = 9_000_000_000_000_000_000L;

  /** 矩阵规模超过草稿生成上限。带上真实行数，让界面能说清"要缩到多少以内"。 */
  public static final class MatrixTooLarge extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final long cap;
    private final long actual;

    MatrixTooLarge(long cap, long actual) {
      super("矩阵规模超过单次草稿上限");
      this.cap = cap;
      this.actual = actual;
    }

    public long cap() {
      return cap;
    }

    public long actual() {
      return actual;
    }
  }
}