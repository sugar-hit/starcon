package com.starcon.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * 3.5.2.2 BOM 展开的纯规则层。
 *
 * <p>不碰数据库、时钟与 HTTP：给定同样的输入永远返回同样的结论，所以
 * 「深度上限怎么判」「缺口怎么算」「一个 SKU 是不是子装配」这三件事能被单元测试逐条钉死，
 * 而不必起一个 PG16 容器再摆一棵树。
 *
 * <p><b>与 3.5.2.1 的口径关系</b>（V26 文件头第零节逐条对齐）：
 *
 * <ul>
 *   <li>生效区间 R1–R5、损耗率 R6、<b>单层需求量 R7 逐字复用</b>：
 *       {@link #requiredQuantity} 直接转发 {@link BomRules#requiredQuantity}，
 *       不复制公式 —— 同一句「单位用量 × 产量 × (1+损耗率)」写两份，
 *       迟早有一份被改松，于是出现「3.5.2.1 算 6.3、3.5.2.2 算 6」这种只在跨模块比对里暴露的偏差。
 *   <li>本类<b>自己</b>钉死的只有展开特有的三条：E1 逐层结转、E2 缺口、E4 深度上限。
 * </ul>
 */
public final class BomExplosionRules {
  private BomExplosionRules() {}

  /**
   * 展开深度上限的默认值。
   *
   * <p>是<b>可用性</b>限制而不是数据库限制：真实 BOM 从「成品 → 组件」往下 seldom 超过四五层，
   * 而每多一层清单就多一行要人核对。再深的树多半是数据建模有问题（把工艺步骤做成了 BOM），
   * 不是真的需要展开 —— 那属于 08 加工单的作业单，不是物料清单。
   */
  public static final int DEFAULT_MAX_DEPTH = 10;

  /** 展开深度上限的绝对上限：调用方可以把默认调小，不能调得比它还大。 */
  public static final int MAX_DEPTH_LIMIT = 25;

  /** 数量与缺口的小数位数，与 {@code numeric(19,6)} 及 V26 的 CHECK 一致。 */
  public static final int QUANTITY_SCALE = BomRules.QUANTITY_SCALE;

  /**
   * 深度上限问题。
   *
   * <p>小于 1 是「只展开一层那就不是展开」；大于 {@link #MAX_DEPTH_LIMIT} 是把可用性上限
   * 变成了一个能无限调大的参数 —— 调大之后产出的清单没人核得对，而领错料要上仓库找回来。
   */
  public static String maxDepthProblem(Integer maxDepth) {
    int value = maxDepth == null ? DEFAULT_MAX_DEPTH : maxDepth;
    if (value < 1) return "展开深度上限至少是 1";
    if (value > MAX_DEPTH_LIMIT)
      return "展开深度上限最多 " + MAX_DEPTH_LIMIT + " 层（默认 " + DEFAULT_MAX_DEPTH + "）："
          + "再深的 BOM 树多半是把工艺步骤做成了物料清单，那属于 08 加工单的作业单，"
          + "展开出来也没人核得对";
    return null;
  }

  /** 把可空的深度参数解析成实际生效值（不传 = 默认）。 */
  public static int resolveMaxDepth(Integer maxDepth) {
    int value = maxDepth == null ? DEFAULT_MAX_DEPTH : maxDepth;
    return Math.min(Math.max(value, 1), MAX_DEPTH_LIMIT);
  }

  // ================================================================ E1 逐层结转

  /**
   * 一行的需求量：<b>规则 R7 的逐层递推</b>（V26 文件头第一节）。
   *
   * <p>{@code required = driverQuantity × unitQuantity × (1 + scrapRate/100)}，
   * 其中 {@code driverQuantity} 是：
   *
   * <ul>
   *   <li>顶层行 = 成品数量（{@code outputQuantity}）；
   *   <li>子装配行 = <b>父行的缺口 gap</b>，不是父行的需求量。
   * </ul>
   *
   * <p><b>为什么子级用父级的缺口</b>：半成品 SA 在本仓已有 4 个、在途 3 个，
 * *这次要用 10 个 → 只差 3 个才需要向下展开。按 10 展开会把 SA 下面的料多算 7 份，
   * 而那 7 份 SA 根本不生产 —— 多算出来的料会变成一张没人能解释为什么不买的采购单。
   *
   * <p>公式本身转发 {@link BomRules} 而不是复制：那是 3.5.2.1 已经用集成测试钉死的口径。
   */
  public static BigDecimal requiredQuantity(
      BigDecimal driverQuantity, BigDecimal unitQuantity, BigDecimal scrapRate) {
    return BomRules.requiredQuantity(unitQuantity, scrapRate, driverQuantity);
  }

  // ================================================================ E2 缺口

  /**
   * 缺口：{@code max(0, required − available − inTransit)}（V26 文件头第二节）。
   *
   * <p><b>为什么在途可以抵扣</b>：在途货的货权已归本租户（V1 守恒式「在手 = 在仓实物 + 在途」），
   * 它不是「还要不要买」而是「已经买了、只是还没到」。抵扣它是为了不把同一批料重复下单。
   *
   * <p><b>下限截 0 的理由</b>：负数表达的是「库存多了」。而「多出来多少」对采购决策无用，
   * 对页面排版有害（缺口列会出现负号）；富余量由 available / inTransit 两列自己说话，
   * 不额外开一列。余量由 {@link #surplus} 单独给出，供 06 判「这批料是不是该停采」。
   *
   * <p><b>不替下游做提前期决策</b>：在途到货晚于需要它的时刻时该不该照扣，
   * 是 06/07 的补货决定。所以本方法只做算术，三列原样保留给调用方判断。
   *
   * <p><b>全 BigDecimal，禁浮点</b>：这个数会被 06 按它下单。double 的误差在这里
   * 是「买回来的料比算出来的多几件」，而采购单是要付钱的。
   */
  public static BigDecimal gap(BigDecimal required, BigDecimal available, BigDecimal inTransit) {
    BigDecimal net = required.subtract(available).subtract(inTransit);
    return net.signum() > 0 ? net.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP) : zero();
  }

  /** 余量（可用 + 在途 超出需求的部分）。缺口为 0 而需求未满足时为 0。 */
  public static BigDecimal surplus(BigDecimal required, BigDecimal available, BigDecimal inTransit) {
    BigDecimal net = available.add(inTransit).subtract(required);
    return net.signum() > 0 ? net.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP) : zero();
  }

  /** 是否已覆盖（可用 + 在途已够本行需求）。覆盖后不再向下展开，否则只会产出一串 0 需求的噪声行。 */
  public static boolean covered(BigDecimal required, BigDecimal available, BigDecimal inTransit) {
    return available.add(inTransit).compareTo(required) >= 0;
  }

  private static BigDecimal zero() {
    return BigDecimal.ZERO.setScale(QUANTITY_SCALE);
  }

  // ================================================================ E3 子装配判定

  /**
   * 子 BOM 的候选选择：同一个成品 SKU 被多个 BOM 声明为产出时取哪一个。
   *
   * <p>{@code bom} 表对 (tenant_id, output_sku_id) <b>没有</b>唯一约束
   * （V25 只建了 bom_by_output_sku 索引），所以这一步的输入可能有多行。
   * 排序键是 {@code (code, id)}：编码是 06 采购建议与 08 加工单里的稳定引用，
   * 用它当排序键意味着「挑不挑得动」与「代码里看到的顺序」一致。
   *
   * <p>确定性 + 显式告警，而不是静默挑一个也不说 —— 见返回值的第二个元素。
   *
   * @return 选中的候选（无候选时为 null）与「是否存在多个候选」
   */
  public static java.util.Optional<java.util.AbstractMap.SimpleEntry<BomCandidate, Boolean>>
      pickSubBom(List<BomCandidate> candidates) {
    if (candidates == null || candidates.isEmpty()) return java.util.Optional.empty();
    var sorted = new java.util.ArrayList<>(candidates);
    sorted.sort(
        java.util.Comparator.comparing(BomCandidate::code).thenComparing(c -> c.id().toString()));
    return java.util.Optional.of(
        new java.util.AbstractMap.SimpleEntry<>(sorted.get(0), sorted.size() > 1));
  }

  /** 子 BOM 候选（一条 {@code bom} 行在本判定里需要的最小信息）。 */
  public record BomCandidate(UUID id, String code) {}

  // ================================================================ 展示辅助

  /** 缺口为正的行才需要采购/备货 —— 中间层是过程，采购对象是叶子件与缺料的半成品。 */
  public static boolean needsProcurement(BigDecimal gap) {
    return gap != null && gap.signum() > 0;
  }

  static {
    if (QUANTITY_SCALE != 6) throw new IllegalStateException("展开数量精度必须与 numeric(19,6) 一致");
    if (DEFAULT_MAX_DEPTH > MAX_DEPTH_LIMIT)
      throw new IllegalStateException("默认深度不能超过绝对上限");
  }
}
