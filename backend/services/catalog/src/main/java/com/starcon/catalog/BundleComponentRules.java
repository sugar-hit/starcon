package com.starcon.catalog;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;/**
 * 3.5.1.2 组件维护的纯规则层。
 *
 * <p>不碰数据库、时钟与随机数：给定同样的输入永远返回同样的结论，所以「怎么算成环」
 * 这件事可以被单元测试逐条钉死，而不必起一个 PG16 容器。
 *
 * <p>本类最重要的产出是<b>循环引用的判定口径</b>（R6..R9，V24 文件头第三节有完整论证）。
 * 关键点是<b>成环不是「套装直接引用自己」</b>，而是要沿包含链走一圈：
 *
 * <pre>
 *   套装 A 含 SKU S，而 S 是套装 B 的可售 SKU   ==   边 A → B
 *   套装 B 含 SKU T，而 T 是套装 C 的可售 SKU   ==   边 B → C
 *   套装 C 含 SKU S'，而 S' 是套装 A 的可售 SKU ==   边 C → A   ← 成环
 * </pre>
 *
 * <p>所以「A 含 B」永远是合法的（B 自己没被 A 包含过），只有<b>闭合成环</b>才拒绝。
 * 这个区别决定了 {@link #reaches} 是唯一的判定入口，而不是「直接查有没有反向边」。
 *
 * <p>DB 侧（{@code find_bundle_cycle_path} + 两个触发器）用同一套口径做纵深防线，
 * 两边必须给出同一个答案：服务层报「BND-A → BND-B → BND-C → BND-A」，
 * 触发器也必须报同一条环。
 */
public final class BundleComponentRules {
  private BundleComponentRules() {}

  /**
   * 遍历的深度硬上限。
   *
   * <p>理论上「是否成环」不需要它（路径去重已经保证终止），但一个 32 层深的套装链在业务上
   * 没有意义，停在这里比继续走更便宜。它只影响「极深的环报出多长的路径」，
   * <b>不影响是否成环的结论</b> —— 目标一旦被找到就立刻返回。
   */
  public static final int MAX_WALK_DEPTH = 32;

  /** 组件角色长度上限，与 {@code bundle_component.role text} 的服务层口径一致。 */
  public static final int MAX_ROLE_LENGTH = 32;

  /** 组件备注长度上限，与 {@code bundle_component.note text} 的服务层口径一致。 */
  public static final int MAX_NOTE_LENGTH = 200;

  /** 指向备注长度上限，与 {@code bundle_sku.note text} 一致。 */
  public static final int MAX_LINK_NOTE_LENGTH = 200;

  // ================================================================ 规范化

  public static String normalize(String value) {
    return value == null ? "" : value.trim();
  }

  /** 角色与备注：自由文本，规范化只去首尾空白。 */
  public static String roleProblem(String role) {
    return role.length() > MAX_ROLE_LENGTH ? "组件角色最多 " + MAX_ROLE_LENGTH + " 个字符" : null;
  }

  public static String componentNoteProblem(String note) {
    return note.length() > MAX_NOTE_LENGTH ? "组件备注最多 " + MAX_NOTE_LENGTH + " 个字符" : null;
  }

  public static String linkNoteProblem(String note) {
    return note.length() > MAX_LINK_NOTE_LENGTH
        ? "指向备注最多 " + MAX_LINK_NOTE_LENGTH + " 个字符"
        : null;
  }

  /**
   * 组件数量问题（与 3.5.1.1 的 {@code BundleRules.quantityProblem} 逐字同一口径）。
   *
   * <p>转发而不是复制：同一个「数量必须大于 0」的规则写成两份，迟早会有一份被改松，
   * 于是出现「3.5.1.1 拦得住、3.5.1.2 拦不住」这种只在跨模块操作里才暴露的漏洞。
   */
  public static String quantityProblem(BigDecimal quantity) {
    return BundleRules.quantityProblem(quantity);
  }

  /**
   * 单个版本的组件上限（与 {@code BundleRules.MAX_COMPONENTS_PER_VERSION} 同一口径）。
   *
   * <p>逐行增删时也要查：整体替换会查清单规模，但「已经 50 个了再加一个」这条路
   * 只在 3.5.1.2 的单行新增上出现。
   */
  public static String componentCountProblem(int count) {
    return BundleRules.componentCountProblem(count);
  }

  // ================================================================ R6 循环判定

  /**
   * 从 {@code from} 出发沿包含边能否走到 {@code to}（规则 R6 的判定核心）。
   *
   * <p>{@code edges} 是「套装 → 它直接包含的套装」的邻接表，一条边的来源是
   * 「{@code from} 的某个版本里有一个代表 {@code to} 的可售 SKU 作为组件」。
   *
   * <p>用广度优先而不是递归：套装数量可达几百，递归在 Java 里会顶爆调用栈，
   * 而 BFS 顺带就是「最短环」—— 报错里给运营的那条环越短越好读。
   *
   * <p>{@code from == to} 直接判可达（规则 R8：自引用是长度为 1 的环）。
   */
  public static boolean reaches(Map<UUID, List<UUID>> edges, UUID from, UUID to) {
    return cyclePath(edges, from, to) != null;
  }

  /**
   * 从 {@code from} 到 {@code to} 的一条最短包含路径；不可达时返回 null。
   *
   * <p>路径首元素是 {@code from}、末元素是 {@code to}。调用方把首尾再接起来
   * 就能得到一条闭合的环（{@code A → B → C → A}）。
   */
  public static List<UUID> cyclePath(Map<UUID, List<UUID>> edges, UUID from, UUID to) {
    if (from == null || to == null) return null;
    // 自引用：套装含自己，展开时直接无限递归（规则 R8）。
    if (from.equals(to)) return List.of(from);
    return shortestPath(edges, from, to);
  }

  /** BFS 主体：返回 {@code from → … → to} 的节点序列，不可达返回 null。 */
  private static List<UUID> shortestPath(Map<UUID, List<UUID>> edges, UUID from, UUID to) {
    Deque<UUID> queue = new ArrayDeque<>();
    Map<UUID, UUID> cameFrom = new java.util.HashMap<>();
    Set<UUID> visited = new java.util.LinkedHashSet<>();
    queue.add(from);
    visited.add(from);
    while (!queue.isEmpty()) {
      UUID current = queue.poll();
      for (UUID next : edges.getOrDefault(current, List.of())) {
        // 路径去重：已访问过的节点不再展开。这既剪掉了与目标无关的死循环，
        // 又保证遍历一定终止 —— 图里有环时 BFS 不会无限跑。
        if (!visited.add(next)) continue;
        cameFrom.put(next, current);
        if (next.equals(to)) {
          List<UUID> path = new ArrayList<>();
          for (UUID node = to; node != null; node = cameFrom.get(node)) path.add(node);
          java.util.Collections.reverse(path);
          return path;
        }
        // 深度上限只截断「继续往深处找」，不影响已找到的答案。
        if (cameFrom.size() < MAX_WALK_DEPTH) queue.add(next);
      }
    }
    return null;
  }

  /** 环的展示文案：把路径接成闭合的 {@code A → B → C → A}（规则 R9：拒绝而不是自动拆环）。 */
  public static String ringText(List<String> codes) {
    if (codes == null || codes.isEmpty()) return "";
    return String.join(" → ", codes) + " → " + codes.get(0);
  }

  /** 成环的拒绝理由。规则 R9：给出整条环让人自己改，不替运营决定断哪一处。 */
  public static String cycleProblem(String ring) {
    return "组件会形成循环引用（"
        + ring
        + "）：套装之间已经存在一条包含链，再把这个组件放进来就闭合成环。"
        + "09 订单按组件逐层展开时会在环上无限递归，而订单要冻结的是有限的展开结果。"
        + "请换一个不在这条链上的组件，或先解开链上某一处指向";
  }

  /** 指向变更成环的拒绝理由（换一种说法：环是「改指向」造出来的，不是「加组件」）。 */
  public static String relinkProblem(String ring, String skuCode, String holderCode, String targetCode) {
    return "这个指向会形成循环引用（"
        + ring
        + "）：SKU「"
        + skuCode
        + "」现在被套装「"
        + holderCode
        + "」当作组件，改成代表套装「"
        + targetCode
        + "」后两个套装就互相包含了。"
        + "09 订单按组件逐层展开时会在环上无限递归，而订单要冻结的是有限的展开结果。"
        + "请解开链上某一处指向，或换一个 SKU 来代表这个套装";
  }

  // ================================================================ 组件可写性

  /**
   * 已发布版本的组件一律不可改（蓝图原话：「已发布版本的组件不可改，只能新建版本」）。
   *
   * <p>V23 的 {@code guard_bundle_component_published} 已经在 DB 层钉死这条，
   * 这里给的是「为什么」—— 让人看见理由比让人撞一个 409 强。
   */
  public static String publishedComponentProblem(String versionNo) {
    return "版本「"
        + versionNo
        + "」已发布，它的组件不可增删改：历史订单冻结的是当时展开的组件清单，"
        + "改了会让「这单当时包含哪些组件」无从查证。要改组件请新建一个版本并让它接替本版";
  }

  /** 嵌套提示：组件 SKU 本身代表另一个套装时，展开会继续往下走一层。 */
  public static String nestedHint(String linkedBundleCode, int depth) {
    return "组件 SKU「"
        + linkedBundleCode
        + "」代表套装 "
        + linkedBundleCode
        + "，所以这行不是终点：09 订单展开时会继续按那个套装的生效版本往下展开（当前嵌套深度 "
        + depth
        + "）";
  }

  // ================================================================ 工具
}
