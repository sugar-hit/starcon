package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 3.5.1.2 组件维护的纯规则测试：把 R6..R9（循环引用的判定口径）逐条钉死。
 *
 * <p>这些规则<b>不需要数据库</b>：邻接表就是 {@code Map<套装, List<套装>>}， 给定同样的图永远返回同样的结论，所以「A 含 B 合法、A 含 B 且 B 含 A
 * 不合法」 这件事可以被穷举，而不必起一个 PG16 容器。
 *
 * <p>钉死它们的意义在于：若哪天有人把判定换成「只查有没有直接反向边」， A→B→C→A 这种三元环会被漏掉，而漏掉的环要到对方版本发布、09 订单展开时才显形。
 */
class BundleComponentRulesTest {
  private final UUID a = UUID.nameUUIDFromBytes("A".getBytes());
  private final UUID b = UUID.nameUUIDFromBytes("B".getBytes());
  private final UUID c = UUID.nameUUIDFromBytes("C".getBytes());
  private final UUID d = UUID.nameUUIDFromBytes("D".getBytes());

  /** 邻接表：A 含 B、B 含 C、C 含 A。 */
  private Map<UUID, List<UUID>> threeCycle() {
    Map<UUID, List<UUID>> edges = new LinkedHashMap<>();
    edges.put(a, List.of(b));
    edges.put(b, List.of(c));
    edges.put(c, List.of(a));
    return edges;
  }

  // ================================================================ R6 可达性

  @Test
  void plainNestingIsNotACycle() {
    // A 含 B 是合法的：B 自己没有被 A 包含过。
    Map<UUID, List<UUID>> edges = new LinkedHashMap<>();
    edges.put(a, List.of(b));
    assertThat(BundleComponentRules.reaches(edges, b, a)).isFalse();
    assertThat(BundleComponentRules.cyclePath(edges, b, a)).isNull();
  }

  @Test
  void twoBundleCycleIsDetected() {
    // A 含 B 且 B 含 A —— 蓝图文案里点名的那个例子。
    Map<UUID, List<UUID>> edges = new LinkedHashMap<>();
    edges.put(a, List.of(b));
    edges.put(b, List.of(a));
    assertThat(BundleComponentRules.reaches(edges, b, a)).isTrue();
    assertThat(BundleComponentRules.cyclePath(edges, b, a)).containsExactly(b, a);
  }

  @Test
  void threeBundleCycleIsDetected() {
    // A→B→C→A：只查「直接反向边」的实现会漏掉这种，所以这是本文件最关键的一个用例。
    List<UUID> path = BundleComponentRules.cyclePath(threeCycle(), c, a);
    assertThat(path).containsExactly(c, a);
    assertThat(BundleComponentRules.reaches(threeCycle(), b, a)).isTrue();
  }

  @Test
  void selfReferenceIsACycleOfLengthOne() {
    // 规则 R8：套装含自己是长度为 1 的环，展开时直接无限递归。
    assertThat(BundleComponentRules.cyclePath(threeCycle(), a, a)).containsExactly(a);
    assertThat(BundleComponentRules.reaches(Map.of(), a, a)).isTrue();
  }

  @Test
  void dagNeverReportsACycleForEdgesThatDoNotCloseALoop() {
    // 菱形：A→B、A→C、B→D、C→D。看起来像环，其实是 DAG。
    //
    // 判定方向要摆正：新增边 X→Y 是否成环 = {@code reaches(edges, Y, X)}（Y 能不能走回 X）。
    // 所以「B 能走到 D」本身不是环 —— 只有补上 D→B 才闭合成环。
    Map<UUID, List<UUID>> edges = new LinkedHashMap<>();
    edges.put(a, List.of(b, c));
    edges.put(b, List.of(d));
    edges.put(c, List.of(d));

    // 补一条**会**闭合的边：D→A（D 能走 D→… 不存在，但 A 能走到 D，所以补 D→A 成环）。
    assertThat(BundleComponentRules.reaches(edges, a, d)).isTrue();
    assertThat(BundleComponentRules.cyclePath(edges, a, d)).containsExactly(a, b, d);
    assertThat(BundleComponentRules.reaches(edges, b, d)).isTrue(); // 补 D→B
    assertThat(BundleComponentRules.reaches(edges, c, d)).isTrue(); // 补 D→C
    assertThat(BundleComponentRules.reaches(edges, a, b)).isTrue(); // 补 B→A
    assertThat(BundleComponentRules.reaches(edges, a, c)).isTrue(); // 补 C→A

    // 补一条**不会**闭合的边：DAG 里顺着方向或跨分支补都不成环。
    assertThat(BundleComponentRules.reaches(edges, b, a)).isFalse(); // 补 A→B（已有，且 A 无入边）
    assertThat(BundleComponentRules.reaches(edges, c, a)).isFalse(); // 补 A→C
    assertThat(BundleComponentRules.reaches(edges, d, b)).isFalse(); // 补 B→D（已有，且 D 无出边）
    assertThat(BundleComponentRules.reaches(edges, d, c)).isFalse(); // 补 C→D
    assertThat(BundleComponentRules.reaches(edges, b, c)).isFalse(); // 补 C→B（兄弟分支之间无路）
  }

  @Test
  void aCycleElsewhereInTheGraphDoesNotAffectUnrelatedBundles() {
    // B↔C 是个环，但 A 与 D 都不在环上：加 A 含 D 不该被环拦住。
    Map<UUID, List<UUID>> edges = new LinkedHashMap<>();
    edges.put(b, List.of(c));
    edges.put(c, List.of(b));
    assertThat(BundleComponentRules.reaches(edges, b, a)).isFalse();
    assertThat(BundleComponentRules.reaches(edges, d, a)).isFalse();
    assertThat(BundleComponentRules.reaches(edges, c, b)).isTrue();
  }

  @Test
  void traversalTerminatesOnSelfLoopsAndRepeatedEdges() {
    // 图里本来就有自环与重复边时，BFS 不能被带着跑飞（visited 去重必须生效）。
    Map<UUID, List<UUID>> edges = new LinkedHashMap<>();
    edges.put(a, List.of(a, b, b));
    edges.put(b, List.of(c, c, b));
    edges.put(c, List.of(a));
    assertThat(BundleComponentRules.reaches(edges, c, a)).isTrue();
    assertThat(BundleComponentRules.reaches(edges, b, d)).isFalse();
  }

  @Test
  void emptyGraphIsNotACycle() {
    assertThat(BundleComponentRules.reaches(Map.of(), a, b)).isFalse();
    assertThat(BundleComponentRules.cyclePath(new HashMap<>(), a, b)).isNull();
  }

  @Test
  void nullEndpointsAreNeverACycle() {
    // null 意味着「这个 SKU 不代表任何套装」或「套装 id 没查到」，
    // 那是「没有边」而不是「有环」—— 判成环会给出莫名其妙的报错。
    assertThat(BundleComponentRules.reaches(threeCycle(), null, a)).isFalse();
    assertThat(BundleComponentRules.reaches(threeCycle(), a, null)).isFalse();
  }

  @Test
  void depthCapBoundsTheWalkWithoutChangingTheVerdict() {
    // 一条 40 层深的链。深度上限 32：结论仍然是「不可达」（真的不可达），
    // 而如果终点就在上限之内，路径必须照常给出。
    Map<UUID, List<UUID>> edges = new LinkedHashMap<>();
    List<UUID> chain = new java.util.ArrayList<>();
    for (int i = 0; i < 40; i++) chain.add(UUID.nameUUIDFromBytes(("n" + i).getBytes()));
    for (int i = 0; i < chain.size() - 1; i++) edges.put(chain.get(i), List.of(chain.get(i + 1)));
    // 终点在第 3 步：低于上限，路径完整。
    List<UUID> shallow = BundleComponentRules.cyclePath(edges, chain.get(0), chain.get(3));
    assertThat(shallow).containsExactly(chain.get(0), chain.get(1), chain.get(2), chain.get(3));
    // 终点在第 35 步：超过上限，判为不可达（而不是把栈跑爆）。
    assertThat(BundleComponentRules.reaches(edges, chain.get(0), chain.get(35))).isFalse();
    assertThat(BundleComponentRules.MAX_WALK_DEPTH).isEqualTo(32);
  }

  // ================================================================ 文案

  @Test
  void ringTextClosesTheLoopWithTheFirstCode() {
    // 报错里那条环必须首尾相接：A → B → C → A。少一个箭头，运营会看不懂。
    assertThat(BundleComponentRules.ringText(List.of("BND-A", "BND-B", "BND-C")))
        .isEqualTo("BND-A → BND-B → BND-C → BND-A");
    assertThat(BundleComponentRules.ringText(List.of("BND-A"))).isEqualTo("BND-A → BND-A");
    assertThat(BundleComponentRules.ringText(List.of())).isEmpty();
  }

  @Test
  void cycleProblemNamesTheRingAndTheDownstreamCost() {
    String message = BundleComponentRules.cycleProblem("BND-A → BND-B → BND-A");
    // 报错要同时说清「环在哪」和「为什么必须拦」，少一样都会让人以为可以绕过去。
    assertThat(message).contains("BND-A → BND-B → BND-A");
    assertThat(message).contains("循环引用");
    assertThat(message).contains("无限递归");
    assertThat(message).contains("请换一个不在这条链上的组件");
  }

  @Test
  void publishedComponentProblemPointsAtCreatingANewVersion() {
    String message = BundleComponentRules.publishedComponentProblem("v1");
    assertThat(message).contains("已发布");
    assertThat(message).contains("新建一个版本");
    // 蓝图纪律：已发布版本的组件不可改，只能新建版本。
    assertThat(message).contains("历史订单冻结");
  }

  // ================================================================ 数量口径

  @Test
  void quantityRulesAreSharedWithBundleVersionManagement() {
    // 转发而不是复制：同一个规则写两份，迟早有一份被改松，
    // 于是出现「3.5.1.1 拦得住、3.5.1.2 拦不住」这种只在跨模块操作里暴露的漏洞。
    assertThat(BundleComponentRules.quantityProblem(BigDecimal.ZERO)).isNotNull();
    assertThat(BundleComponentRules.quantityProblem(new BigDecimal("-1"))).isNotNull();
    assertThat(BundleComponentRules.quantityProblem(null)).isNotNull();
    assertThat(BundleComponentRules.quantityProblem(new BigDecimal("0.0000001"))).isNotNull();
    assertThat(BundleComponentRules.quantityProblem(BigDecimal.ONE)).isNull();
    assertThat(BundleComponentRules.quantityProblem(new BigDecimal("0.5"))).isNull();
  }

  @Test
  void componentCountLimitIsTheSameAsTheWholeListReplacement() {
    // 整体替换会查清单规模，但「已经 50 个了再加一个」这条路只在单行新增上出现。
    assertThat(BundleComponentRules.componentCountProblem(0)).isNotNull();
    assertThat(BundleComponentRules.componentCountProblem(51)).isNotNull();
    assertThat(BundleComponentRules.componentCountProblem(1)).isNull();
    // 同一套上限：整体替换与单行新增必须用同一个数，否则会出现「替换能过、加一行超限」
    // 这种前后不一致的报错。
    assertThat(
            BundleComponentRules.componentCountProblem(BundleRules.MAX_COMPONENTS_PER_VERSION + 1))
        .isNotNull();
  }

  @Test
  void relinkProblemExplainsTheSilentDowngrade() {
    String message =
        BundleComponentRules.relinkProblem("BND-A → BND-B → BND-A", "SKU-1", "BND-A", "BND-B");
    assertThat(message).contains("SKU-1");
    assertThat(message).contains("BND-A");
    assertThat(message).contains("BND-B");
    assertThat(message).contains("循环引用");
  }
}
