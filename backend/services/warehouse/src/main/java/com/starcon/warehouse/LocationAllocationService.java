package com.starcon.warehouse;

import static com.starcon.warehouse.LocationAllocationModels.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 8.1.2.2 库位自动分配。
 *
 * <p>蓝图：「按规则为入库商品推荐库位：同品类聚类、最短上架路径、容量与承重匹配、 待检/残次专用库位强制规则。输入：入库 SKU 与数量；库位属性与占用；路径距离。
 * 输出：推荐库位列表（带推荐理由）。」关系段红线：「**质量状态决定库位类型**： 残次品入残次库位是硬规则，不允许混放。」
 *
 * <p><b>规则分两类：硬规则做过滤，排序规则做打分。</b>
 *
 * <ol>
 *   <li><b>硬规则</b>（不满足就<b>不进候选</b>，不是排在后面扣分）：质量状态推出的库位类型 （V106 触发器 {@code
 *       guard_location_allocation_placement} 是权威判据）、 库位启用、同仓、容量/承重不超限、该行尚未被指派。把残次品放进存储位是硬规则错误，
 *       而不是一个「不太推荐」的选项 —— 所以它不能出现在候选列表里等人点。
 *   <li><b>排序规则</b>（只影响先后）：同品类聚类、最短上架路径、容量与承重余量、空库位。
 * </ol>
 *
 * <p><b>score = 各条理由的权重之和。</b>这不是巧合而是刻意的：{@link LocationAllocationReason} 里带
 * weight，所以「它为什么排第一」逐条加起来就是那个 score，不需要额外解释一个算不出 来的数字。无法判定的规则（库位没量过容量 / 调用方没给需求容量）给 <b>0 分并带一条
 * CAPACITY_UNKNOWN / WEIGHT_UNKNOWN 说明</b> —— 「没量过」既不加分也不减分， 悄悄按「够」处理才是把不确定性伪装成结论。
 *
 * <p><b>「同品类」不是 catalog 类目。</b>catalog 的类目树（3.3.1.1）未开发，且 AGENTS.md 禁止把上游权威列复制成本地列（sku_ref
 * 明文不存类目）。所以聚类信号取自 {@code stock_bucket} 的<b>真实共置关系</b>：这个库位上已有的 SKU 里，有多少个
 * 历史上与入库这批货同过别的库位。这是本服务内可得的真信号，但它不等于 catalog 类目， 所以 {@link LocationAllocationCapabilities} 里 {@code
 * catalogCategoryAvailable=false} 如实标出来。
 *
 * <p><b>「路径距离」是库位树跳数，不是巷道路径长度。</b>按 V105 的 {@code parent_id} 树算「源库位 →
 * 目标库位」的跳数：两者的最近公共祖先深度用<b>路径共有段数</b>求 （库位编码在仓内唯一，所以共有段数就是 LCA 层号），距离 = depth(src)+depth(dst)-2×LCA。
 * 真正的巷道路径图属 8.3.2.1，未交付，capabilities 里 {@code routingGraphAvailable=false}。
 *
 * <p><b>容量口径只覆盖本模块承诺过的量。</b>capacity 是体积单位而 stock_bucket.on_hand 是件数， 本服务没有「每个 SKU
 * 占多少体积」的真相来源，拿件数凑利用率是单位错配。所以 {@code remainingCapacity = capacity − 本模块未取消决策已承诺的容量}，而「库位上既有库存
 * 占了多少体积」如实标成不可判定（{@code stockVolumeAvailable=false}）。
 *
 * <p><b>租户谓词每条查询都写。</b>RLS 是第一道，显式 {@code tenant_id=} 是第二道。
 */
@Service
public class LocationAllocationService {

  /** 每个明细行最多返回的候选数（默认值，可被请求覆盖并夹到上限）。 */
  private static final int DEFAULT_CANDIDATES = 10;

  /** 候选数硬上限：库位规模有界，返回上限是为了不让一个响应把整仓库位倒出来。 */
  private static final int MAX_CANDIDATES = 50;

  /** 真分页上限（与 8.1.1.1 同一口径）。 */
  private static final int MAX_PAGE_SIZE = 200;

  // ---- 排序权重：score 恒等于这些权重之和，理由列表逐条列出 ----
  private static final int W_TYPE_RULE = 30;
  private static final int W_SAME_SKU_HERE = 40;
  private static final int W_CLUSTER_PER_SKU = 15;
  private static final int W_CLUSTER_MAX = 50;
  private static final int W_PATH_BASE = 40;
  private static final int W_PATH_PER_HOP = 6;
  private static final int W_CAPACITY_FIT = 20;
  private static final int W_WEIGHT_FIT = 10;
  private static final int W_EMPTY = 20;

  /** 排序键白名单：非法键 400，而不是静默忽略（与 8.1.1.1 / 8.1.1.2 同一纪律）。 */
  private static final List<String> SORT_KEYS =
      List.of("CREATED_AT", "UPDATED_AT", "ALLOCATE_QUANTITY", "SOURCE_DISTANCE");

  private static final List<String> SORT_SQL =
      List.of("a.created_at", "a.updated_at", "a.allocate_quantity", "a.source_distance");

  private final DSLContext sql;

  public LocationAllocationService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明。
   *
   * <p>四条 {@code false} 都是本服务**真的拿不到**的东西，逐条写明理由： catalog 类目树未开发且禁止本地复制（聚类改用 stock_bucket 真实共置）、
   * 巷道路径图归 8.3.2.1（距离改用库位树跳数）、既有库存的体积口径无真相来源 （容量只覆盖本模块承诺量）、上架任务归 8.2.2.1。
   */
  public LocationAllocationCapabilities capabilities() {
    return new LocationAllocationCapabilities(
        true,
        true,
        true,
        false,
        "8.3.2.1",
        true,
        false,
        "3.3.1.1",
        true,
        false,
        false,
        "8.2.2.1",
        List.of(PlacementRule.values()),
        List.of(
            WarehouseLocationTreeModels.LocationType.STORAGE,
            WarehouseLocationTreeModels.LocationType.QUARANTINE,
            WarehouseLocationTreeModels.LocationType.DEFECTIVE),
        MAX_CANDIDATES,
        "质量状态到库位类型的三条硬规则由 V106 触发器在 DB 层强制；"
            + "同品类聚类取自 stock_bucket 的真实共置关系，不是 catalog 类目"
            + "（类目树 3.3.1.1 未开发，且禁止把上游权威列复制成本地列）；"
            + "路径距离是库位树跳数，巷道路径图归 8.3.2.1；"
            + "容量与承重只核算本模块未取消决策已承诺的量，既有库存占用的体积在本服务无真相来源。");
  }

  // ================================================================ 推荐

  /**
   * 为一张收货单的入库明细推荐库位。
   *
   * <p>推荐是<b>纯计算</b>，不落库 —— 落库的只有人真正选定的那个库位 （见 {@link #confirm}）。原因写在 V106 文件头：候选列表没人消费，逐次落库只会
   * 在用户点两下的功夫里刷出几百行垃圾数据。
   *
   * @param requiredCapacity / requiredWeight 调用方量出来的这批货要占多少容量/承重。 <b>可空 =
   *     未量过</b>：此时容量与承重规则判不了，服务层会把 evaluated 标成 false 并写明原因，而不是当成「够」
   */
  public List<LocationAllocationLinePlan> recommend(LocationAllocationRecommendInput input) {
    if (input == null || input.receiptId() == null) {
      throw new IllegalArgumentException("必须指定收货单：推荐的对象是入库 SKU 与数量");
    }
    int limit = clamp(input.limit(), DEFAULT_CANDIDATES, MAX_CANDIDATES);

    var receipt = requireReceipt(input.receiptId());
    UUID warehouseId = receipt.get("warehouse_id", UUID.class);
    UUID sourceLocationId = resolveSourceLocation(input.receiptId(), warehouseId);

    // 逐行推荐：每一行的质量状态（→ 目标库位类型）都不同，一次查询跨不了行类型，
    // 所以按行算。每行一条查询，不是 N×M。
    List<LocationAllocationLinePlan> plans = new ArrayList<>();
    for (org.jooq.Record line : receiptLines(input.receiptId(), input.receiptLineId())) {
      plans.add(planForLine(line, warehouseId, sourceLocationId, input, limit));
    }
    return plans;
  }

  /** 一条收货明细的候选推荐。 */
  private LocationAllocationLinePlan planForLine(
      org.jooq.Record line,
      UUID warehouseId,
      UUID sourceLocationId,
      LocationAllocationRecommendInput input,
      int limit) {

    UUID lineId = line.get("id", UUID.class);
    UUID skuId = line.get("sku_id", UUID.class);
    String role = line.get("quality_role", String.class);
    PlacementRule rule = PlacementRule.of(role);
    WarehouseLocationTreeModels.LocationType requiredType = rule.requiredType();

    BigDecimal received = nz(line.get("received_quantity", BigDecimal.class));
    BigDecimal allocated = allocatedQuantity(lineId);
    BigDecimal outstanding = received.subtract(allocated);

    // 该仓该类型的库位总数（含停用）：用来诚实报告「排除了多少个」。
    long examined = countOfLocations(warehouseId, requiredType);

    Scored scored = candidates(warehouseId, sourceLocationId, skuId, lineId, rule, input, limit);

    var source = locationRef(sourceLocationId);

    return new LocationAllocationLinePlan(
        lineId,
        skuId,
        line.get("sku_code", String.class),
        line.get("sku_name", String.class),
        "上架数量按 8.2.1.1 的累计实收量减去已指派量；拒收量不入库，不参与上架。",
        line.get("quality_status_id", UUID.class),
        role,
        line.get("quality_name", String.class),
        rule,
        requiredType,
        requiredType.label(),
        outstanding,
        outstanding,
        sourceLocationId,
        source == null ? null : source.get("code", String.class),
        source == null ? null : source.get("type", String.class),
        source == null ? null : source.get("depth", Integer.class),
        nonNegative(input.requiredCapacity()),
        nonNegative(input.requiredWeight()),
        scored.eligible,
        Math.max(0L, examined - scored.eligible),
        scored.candidates,
        outstanding.compareTo(BigDecimal.ZERO) <= 0
            ? "该明细行的货已被全部指派上架，没有待上架数量。"
            : note(rule, scored.eligible));
  }

  private static String note(PlacementRule rule, long eligible) {
    if (eligible == 0) {
      return "本仓没有类型符合硬规则「"
          + rule.label()
          + " → "
          + rule.requiredType().label()
          + "」且容量承重不超限的启用库位：先在库位树（8.1.2.1）里建出对应类型的库位，"
          + "再回来推荐。";
    }
    return "候选按推荐分倒序，score 等于各条理由权重之和；" + "容量与承重无法判定的库位给 0 分并单独标注，既不加分也不当成通过。";
  }

  /**
   * 候选计算：一条查询拿全每个候选库位的**结构 / 距离 / 占用 / 承诺 / 聚类**五组事实。
   *
   * <p>纪律：
   *
   * <ol>
   *   <li>结构来自递归 CTE，只带层级字段；占用与承诺都是<b>标量子查询</b>按 {@code location_id} 收口 —— 在递归项里 join stock_bucket
   *       / location_allocation 会让同一库位随明细行数重复，聚类计数被放大（与 8.1.1.1 / 8.1.1.2 / 8.1.2.1 同纪律）。
   *   <li>距离用<b>路径共有段数</b>当 LCA 层号：库位编码在仓内唯一 （{@code
   *       UNIQUE(tenant_id,warehouse_id,code)}），所以两条路径共有的段 必然就是公共祖先链上的段。distance =
   *       depth(src)+depth(dst)-2×共有段数。
   *   <li>聚类排除「同在本库位」的情形：真正的信号是<b>别的</b>库位上的历史共置。 入库这批货已经在本库位的情况单独由 same_sku_here 表达。
   * </ol>
   */
  private Scored candidates(
      UUID warehouseId,
      UUID sourceLocationId,
      UUID skuId,
      UUID lineId,
      PlacementRule rule,
      LocationAllocationRecommendInput input,
      int limit) {

    BigDecimal needCapacity = nonNegative(input.requiredCapacity());
    BigDecimal needWeight = nonNegative(input.requiredWeight());
    WarehouseLocationTreeModels.LocationType requiredType = rule.requiredType();

    var rows =
        sql.fetch(
            """
            with recursive t as (
              select l.id, l.parent_id, l.code, l.name, l.type, l.status,
                     l.capacity, l.weight_limit,
                     0 as depth, l.code::text as path
                from warehouse_location l
               where l.tenant_id = current_setting('app.current_tenant', true)::uuid
                 and l.warehouse_id = ? and l.parent_id is null
              union all
              select c.id, c.parent_id, c.code, c.name, c.type, c.status,
                     c.capacity, c.weight_limit,
                     t.depth + 1, t.path || '|' || c.code
                from warehouse_location c
                join t on t.id = c.parent_id
               where c.tenant_id = current_setting('app.current_tenant', true)::uuid
                 and c.warehouse_id = ?
            ),
            src as (
              select id, depth, path from t where id = ?
            ),
            cand as (
              select t.id, t.code, t.name, t.type, t.status, t.capacity, t.weight_limit, t.depth,
                     -- 最近公共祖先层号 = 两条路径共有的段数（库位编码仓内唯一）。
                     (select count(*) from unnest(string_to_array(src.path, '|')) as seg(v)
                       where seg.v = any(string_to_array(t.path, '|'))) as lca_depth,
                     src.depth as src_depth
                from t cross join src
               where t.type = ? and t.status = 'ACTIVE' and t.id <> src.id
            )
            select c.id, c.code, c.name, c.type, c.depth,
                   c.capacity, c.weight_limit,
                   c.src_depth + c.depth - 2 * c.lca_depth as distance,
                   -- 占用：标量子查询，按 location_id 收口
                   (select coalesce(sum(k.on_hand),0) from stock_bucket k
                     where k.tenant_id = current_setting('app.current_tenant', true)::uuid
                       and k.location_id = c.id) as on_hand,
                   (select count(distinct k.sku_id) from stock_bucket k
                     where k.tenant_id = current_setting('app.current_tenant', true)::uuid
                       and k.location_id = c.id and k.on_hand > 0) as on_hand_sku_count,
                   -- 同品类聚类：本库位上的 SKU 里，有多少个在**别的**库位上与入库这批货共置过。
                   (select count(distinct k.sku_id) from stock_bucket k
                     where k.tenant_id = current_setting('app.current_tenant', true)::uuid
                       and k.location_id = c.id and k.on_hand > 0 and k.sku_id <> ?
                       and exists (select 1 from stock_bucket a
                                    where a.tenant_id = k.tenant_id
                                      and a.sku_id = ?
                                      and a.location_id <> c.id
                                      and exists (select 1 from stock_bucket b
                                                   where b.tenant_id = a.tenant_id
                                                     and b.location_id = a.location_id
                                                     and b.sku_id = k.sku_id))) as affinity_skus,
                   -- 入库这批货已经在本库位：最强的聚类信号（它已经住这儿了）
                   exists (select 1 from stock_bucket k
                            where k.tenant_id = current_setting('app.current_tenant', true)::uuid
                              and k.location_id = c.id and k.sku_id = ? and k.on_hand > 0)
                     as same_sku_here,
                   -- 本模块未取消决策已承诺的容量/承重（排除本行自身，改判时保持幂等）
                   (select coalesce(sum(a.required_capacity),0) from location_allocation a
                     where a.tenant_id = current_setting('app.current_tenant', true)::uuid
                       and a.location_id = c.id and a.status <> 'CANCELLED'
                       and a.receipt_line_id <> ?) as committed_capacity,
                   (select coalesce(sum(a.required_weight),0) from location_allocation a
                     where a.tenant_id = current_setting('app.current_tenant', true)::uuid
                       and a.location_id = c.id and a.status <> 'CANCELLED'
                       and a.receipt_line_id <> ?) as committed_weight
              from cand c
             order by c.code
            """,
            warehouseId,
            warehouseId,
            sourceLocationId,
            requiredType.name(),
            skuId,
            skuId,
            skuId,
            lineId,
            lineId);

    List<LocationAllocationCandidate> eligible = new ArrayList<>();

    for (org.jooq.Record r : rows) {
      UUID locationId = r.get("id", UUID.class);
      BigDecimal capacity = r.get("capacity", BigDecimal.class);
      BigDecimal weightLimit = r.get("weight_limit", BigDecimal.class);
      BigDecimal committedCapacity = nz(r.get("committed_capacity", BigDecimal.class));
      BigDecimal committedWeight = nz(r.get("committed_weight", BigDecimal.class));
      BigDecimal onHand = nz(r.get("on_hand", BigDecimal.class));
      int distance = r.get("distance", Integer.class);
      long affinitySkus = count(r, "affinity_skus");
      boolean sameSkuHere = Boolean.TRUE.equals(r.get("same_sku_here", Boolean.class));

      // 硬规则：容量/承重**超限**就不进候选。装不下的库位不是「差一点的推荐」，
      // 是错的答案 —— 把它留在列表里等人点，等于把硬约束伪装成建议。
      boolean capacityEvaluated = needCapacity != null && capacity != null;
      BigDecimal remainingCapacity = capacity == null ? null : capacity.subtract(committedCapacity);
      if (capacityEvaluated && needCapacity.compareTo(remainingCapacity) > 0) continue;

      boolean weightEvaluated = needWeight != null && weightLimit != null;
      BigDecimal remainingWeight =
          weightLimit == null ? null : weightLimit.subtract(committedWeight);
      if (weightEvaluated && needWeight.compareTo(remainingWeight) > 0) continue;

      ScoreCard scored = new ScoreCard();
      scored.reason(ReasonCode.TYPE_RULE, W_TYPE_RULE);
      if (sameSkuHere) scored.reason(ReasonCode.CATEGORY_CLUSTER, W_SAME_SKU_HERE);
      if (affinitySkus > 0) {
        scored.reason(
            ReasonCode.CATEGORY_CLUSTER,
            Math.min(W_CLUSTER_MAX, (int) affinitySkus * W_CLUSTER_PER_SKU));
      }
      scored.reason(ReasonCode.SHORTEST_PATH, Math.max(0, W_PATH_BASE - distance * W_PATH_PER_HOP));
      if (capacityEvaluated) scored.reason(ReasonCode.CAPACITY_FIT, W_CAPACITY_FIT);
      else scored.note(ReasonCode.CAPACITY_UNKNOWN);
      if (weightEvaluated) scored.reason(ReasonCode.WEIGHT_FIT, W_WEIGHT_FIT);
      else scored.note(ReasonCode.WEIGHT_UNKNOWN);
      if (onHand.compareTo(BigDecimal.ZERO) == 0) scored.reason(ReasonCode.EMPTY_LOCATION, W_EMPTY);
      if (committedCapacity.compareTo(BigDecimal.ZERO) > 0
          || committedWeight.compareTo(BigDecimal.ZERO) > 0) {
        scored.note(ReasonCode.ALREADY_COMMITTED);
      }

      WarehouseLocationTreeModels.LocationType candidateType =
          WarehouseLocationTreeModels.LocationType.of(r.get("type", String.class));

      eligible.add(
          new LocationAllocationCandidate(
              locationId,
              r.get("code", String.class),
              r.get("name", String.class),
              candidateType,
              candidateType.label(),
              r.get("depth", Integer.class),
              distance,
              capacity,
              weightLimit,
              committedCapacity,
              remainingCapacity,
              capacityEvaluated,
              needCapacity,
              weightEvaluated,
              needWeight,
              committedWeight,
              remainingWeight,
              onHand,
              count(r, "on_hand_sku_count"),
              affinitySkus,
              scored.score(),
              scored.reasons()));
    }

    // 排序在 Java 侧做，因为 score 由多条规则累加而成（SQL 侧排序拿不到理由列表）。
    // 分数相同再按「路径短 → 聚类多 → 编码」逐级 tie-break，保证同一输入结果稳定可复现。
    eligible.sort(
        Comparator.comparingInt(LocationAllocationCandidate::score)
            .reversed()
            .thenComparingInt(LocationAllocationCandidate::distance)
            .thenComparing(
                Comparator.comparingLong(LocationAllocationCandidate::affinitySkus).reversed())
            .thenComparing(LocationAllocationCandidate::code));

    long eligibleCount = eligible.size();
    List<LocationAllocationCandidate> limited =
        eligible.size() > limit ? eligible.subList(0, limit) : eligible;
    return new Scored(eligibleCount, List.copyOf(limited));
  }

  /** 候选汇总：合格数（截断前）与返回的候选列表。 */
  private record Scored(long eligible, List<LocationAllocationCandidate> candidates) {}

  /**
   * 打分累加器：<b>score 恒等于各条理由权重之和</b>。
   *
   * <p>{@link #note} 记的是「无法判定 / 需要知道」这类说明，权重恒为 0 —— 既不加分也不扣分。把「没量过」按「够」处理才是把不确定性伪装成结论。
   *
   * <p>刻意做成 {@code Service} 内部的私有类而不是 Models 里的 record：它不进任何 响应体，springdoc 不会把它扫成 schema，也就不存在与别人的
   * record 撞名的风险。
   */
  private static final class ScoreCard {
    private int score;
    private final List<LocationAllocationReason> reasons = new ArrayList<>();

    void reason(ReasonCode code, int weight) {
      score += weight;
      reasons.add(new LocationAllocationReason(code, code.text(), weight));
    }

    void note(ReasonCode code) {
      reasons.add(new LocationAllocationReason(code, code.text(), 0));
    }

    int score() {
      return score;
    }

    List<LocationAllocationReason> reasons() {
      return List.copyOf(reasons);
    }
  }

  // ================================================================ 写入

  /**
   * 选定一个库位，落成一条上架决策。
   *
   * <p><b>分数与理由不采信调用方。</b>入参里根本没有这两个字段 —— 它们由服务端按同一套 规则重算并冻结成快照，否则「推荐理由」就成了一条可以随便编的字段。
   *
   * <p>DB 触发器 {@code guard_location_allocation_placement} 是权威判据（硬规则、
   * 不许混放、容量不得超占、同一行不得双指派）；应用层先查一遍只为给出可读的中文报错。 两道都要：只做应用层检查的话，运维脚本或别的写入口照样能把残次品放进存储位。
   */
  @Transactional
  public LocationAllocationSaved confirm(LocationAllocationConfirmInput input, UUID actorId) {
    if (input == null || input.receiptId() == null || input.receiptLineId() == null) {
      throw new IllegalArgumentException("必须同时指定收货单与收货明细行");
    }
    if (input.locationId() == null) throw new IllegalArgumentException("必须指定目标库位");

    var receipt = requireReceipt(input.receiptId());
    UUID warehouseId = receipt.get("warehouse_id", UUID.class);

    var line = receiptLine(input.receiptId(), input.receiptLineId());
    String role = line.get("quality_role", String.class);
    PlacementRule rule = PlacementRule.of(role);
    WarehouseLocationTreeModels.LocationType requiredType = rule.requiredType();

    BigDecimal received = nz(line.get("received_quantity", BigDecimal.class));
    BigDecimal alreadyAllocated = allocatedQuantity(input.receiptLineId());
    BigDecimal outstanding = received.subtract(alreadyAllocated);

    BigDecimal quantity =
        input.allocateQuantity() == null ? outstanding : nonNegative(input.allocateQuantity());
    if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "上架数量必须大于 0：该明细行累计实收 " + received + "，已被指派 " + alreadyAllocated);
    }
    if (quantity.compareTo(outstanding) > 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "上架数量 "
              + quantity
              + " 超过该行尚未指派的 "
              + outstanding
              + "（累计实收 "
              + received
              + " − 已指派 "
              + alreadyAllocated
              + "）");
    }

    BigDecimal needCapacity = nonNegative(input.requiredCapacity());
    BigDecimal needWeight = nonNegative(input.requiredWeight());

    // 源库位：货现在在哪（最近一次实收事件）。上架路径从它起算。
    UUID sourceLocationId = resolveSourceLocation(input.receiptId(), warehouseId);

    var target = locationRow(warehouseId, input.locationId());
    if (target == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "目标库位不存在");
    if (!"ACTIVE".equals(target.get("status", String.class))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "目标库位已停用，不能接上架");
    }
    WarehouseLocationTreeModels.LocationType targetType =
        WarehouseLocationTreeModels.LocationType.of(target.get("type", String.class));
    if (targetType != requiredType) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "质量状态「"
              + rule.label()
              + "」只能进 "
              + requiredType.label()
              + "类库位，选中的库位是 "
              + targetType.label()
              + "类："
              + mixNote(rule));
    }
    if (input.locationId().equals(sourceLocationId)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标库位就是货当前所在的库位，没有发生移动");
    }

    // 决策快照：距离与聚化命中数在落库这一刻定住，之后库位占用会变，只有快照留得住。
    int distance = distanceBetween(warehouseId, sourceLocationId, input.locationId());
    long affinitySkus = affinitySkus(warehouseId, sourceLocationId, input.locationId(), line);
    String ruleSummary = ruleSummary(rule, requiredType, distance, affinitySkus);

    UUID id = UUID.randomUUID();
    // 触发器会再独立校一遍硬规则 / 混放 / 容量，这里 insert 的是「已校验过」的同一批值。
    sql.execute(
        """
        insert into location_allocation
          (id,tenant_id,receipt_id,receipt_line_id,sku_id,quality_status_id,warehouse_id,
           source_location_id,location_id,allocate_quantity,required_capacity,required_weight,
           source_distance,affinity_skus,required_type,rule_summary,status,created_by)
        values (?::uuid,current_setting('app.current_tenant', true)::uuid,?::uuid,?::uuid,?::uuid,
                ?::uuid,?::uuid,?::uuid,?::uuid,cast(? as numeric),cast(? as numeric),
                cast(? as numeric),?::int,?::bigint,?,?,'PLANNED',?::uuid)
        """,
        id,
        input.receiptId(),
        input.receiptLineId(),
        line.get("sku_id", UUID.class),
        line.get("quality_status_id", UUID.class),
        warehouseId,
        sourceLocationId,
        input.locationId(),
        quantity,
        needCapacity == null
            ? org.jooq.impl.DSL.val((BigDecimal) null, BigDecimal.class)
            : needCapacity,
        needWeight == null
            ? org.jooq.impl.DSL.val((BigDecimal) null, BigDecimal.class)
            : needWeight,
        distance,
        affinitySkus,
        requiredType.name(),
        ruleSummary,
        actorId);

    return new LocationAllocationSaved(
        id,
        input.receiptLineId(),
        input.locationId(),
        target.get("code", String.class),
        "PLANNED",
        AllocationStatus.PLANNED.label(),
        rule,
        ruleSummary,
        // createdAt 从库里读回来（而不是回显一个本地 new 出来的时间）：
        // timestamptz 的真实写回值是唯一可信的那一个。
        sql.fetchOne("select created_at from location_allocation" + " where id = ?::uuid", id)
            .get("created_at", OffsetDateTime.class),
        null,
        "上架决策已选定。8.2.2.1 上架任务尚未交付，此刻还没有下游消费它。");
  }

  /** 释放已选定的上架决策（交班给 8.2.2.1）。 */
  @Transactional
  public LocationAllocationSaved release(UUID allocationId, UUID actorId) {
    requireAllocation(allocationId);
    int updated =
        sql.execute(
            """
            update location_allocation
               set status = 'RELEASED', released_at = now(), released_by = ?::uuid,
                   version = version + 1, updated_at = now()
             where tenant_id = current_setting('app.current_tenant', true)::uuid
               and id = ? and status = 'PLANNED'
            """,
            actorId,
            allocationId);
    if (updated == 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "只有「已选定」状态的决策可以释放");
    }
    return saved(allocationId, "已释放，等待 8.2.2.1 上架任务消费。");
  }

  /**
   * 撤销上架决策。
   *
   * <p><b>必须带原因</b>（V106 的 CHECK 也会拒）——「为什么撤掉这个决定」是现场复盘的必答题。 撤销后该行可以重新指派（部分唯一索引只约束未取消的决策）。
   */
  @Transactional
  public LocationAllocationSaved cancel(UUID allocationId, LocationAllocationCancelInput input) {
    requireAllocation(allocationId);
    String reason = required(input == null ? null : input.reason(), 200, "撤销原因");
    int updated =
        sql.execute(
            """
            update location_allocation
               set status = 'CANCELLED', cancelled_at = now(), cancel_reason = ?,
                   version = version + 1, updated_at = now()
             where tenant_id = current_setting('app.current_tenant', true)::uuid
               and id = ? and status <> 'CANCELLED'
            """,
            reason,
            allocationId);
    if (updated == 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该决策已经撤销过了");
    }
    return saved(allocationId, "已撤销，该收货明细行可以重新指派库位。");
  }

  // ================================================================ 读

  /** 某张收货单上的全部上架决策（8.2.2.1 未来按收货单拉上架清单就是这一条）。 */
  public List<LocationAllocationDecision> byReceipt(UUID receiptId) {
    requireReceipt(receiptId);
    return sql
        .fetch(
            DECISION_SQL
                + " where a.tenant_id = current_setting('app.current_tenant', true)::uuid"
                + " and a.receipt_id = ?::uuid order by a.created_at desc, a.id",
            receiptId)
        .stream()
        .map(LocationAllocationService::decision)
        .toList();
  }

  /** 决策清单（真分页 + 排序白名单）。合计**不受分页与筛选影响**。 */
  public LocationAllocationPage list(LocationAllocationQuery query) {
    LocationAllocationQuery q =
        query == null
            ? new LocationAllocationQuery(null, null, null, null, null, null, null)
            : query;

    StringBuilder where =
        new StringBuilder(" where a.tenant_id = current_setting('app.current_tenant', true)::uuid");
    List<Object> args = new ArrayList<>();
    if (q.warehouseId() != null) {
      where.append(" and a.warehouse_id = ?::uuid");
      args.add(q.warehouseId());
    }
    if (q.status() != null && !q.status().isBlank()) {
      where.append(" and a.status = ?");
      args.add(AllocationStatus.of(q.status()).name());
    }
    if (q.qualityRole() != null && !q.qualityRole().isBlank()) {
      where.append(" and q.role = ?");
      args.add(PlacementRule.of(q.qualityRole()).name());
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      where.append(
          " and (a.rule_summary like ? or k.code like ? or k.name like ? or l.code like ? or l.name like ? or r.receipt_code like ?)");
      String kw = "%" + q.keyword().trim() + "%";
      args.add(kw);
      args.add(kw);
      args.add(kw);
      args.add(kw);
      args.add(kw);
      args.add(kw);
    }

    var totalRow =
        sql.fetchOne(
            "select count(*) from location_allocation a"
                + " join inventory_quality_status q on q.tenant_id=a.tenant_id and q.id=a.quality_status_id"
                + " join sku_ref k on k.tenant_id=a.tenant_id and k.id=a.sku_id"
                + " join warehouse_location l on l.tenant_id=a.tenant_id and l.id=a.location_id"
                + " join goods_receipt r on r.tenant_id=a.tenant_id and r.id=a.receipt_id"
                + where,
            args.toArray());
    long total = totalRow == null ? 0L : count(totalRow, 0);

    // 合计独立算一遍：它在**不带 limit/offset**的同一组筛选条件下聚合，
    // 所以「合计 = 当前筛选下的全部」是结构上成立的，而不是「当前页的和」。
    var totalsRow =
        sql.fetchOne(
            """
            select count(*) as decision_count,
                   count(*) filter (where a.status = 'PLANNED')   as planned,
                   count(*) filter (where a.status = 'RELEASED')  as released,
                   count(*) filter (where a.status = 'CANCELLED') as cancelled,
                   count(*) filter (where q.role = 'DEFECTIVE')   as defective,
                   count(*) filter (where q.role = 'PENDING_INSPECTION') as pending,
                   coalesce(sum(a.allocate_quantity),0) as allocate_quantity,
                   count(distinct a.receipt_line_id) as coverage_lines
              from location_allocation a
              join inventory_quality_status q on q.tenant_id=a.tenant_id and q.id=a.quality_status_id
              join sku_ref k on k.tenant_id=a.tenant_id and k.id=a.sku_id
              join warehouse_location l on l.tenant_id=a.tenant_id and l.id=a.location_id
              join goods_receipt r on r.tenant_id=a.tenant_id and r.id=a.receipt_id
            """
                + where,
            args.toArray());

    int size = clamp(q.size(), 20, MAX_PAGE_SIZE);
    int page = q.page() == null || q.page() < 1 ? 1 : q.page();
    int idx =
        SORT_KEYS.indexOf(
            q.sort() == null || q.sort().isBlank()
                ? "CREATED_AT"
                : q.sort().trim().toUpperCase(Locale.ROOT));
    if (idx < 0) {
      throw new IllegalArgumentException(
          "排序键不在白名单内：" + q.sort() + "（可用：" + String.join(" / ", SORT_KEYS) + "）");
    }
    String orderBy =
        SORT_SQL.get(idx)
            + (idx == 0 || idx == 1 ? " desc" : idx == 2 ? " desc" : " asc")
            + ", a.id desc";

    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(size);
    pageArgs.add((long) (page - 1) * size);
    var rows =
        sql.fetch(
            DECISION_SQL + where + " order by " + orderBy + " limit ?::int offset ?::bigint",
            pageArgs.toArray());

    return new LocationAllocationPage(
        totals(
            totalsRow == null ? 0L : count(totalsRow, "decision_count"),
            totalsRow == null ? 0L : count(totalsRow, "planned"),
            totalsRow == null ? 0L : count(totalsRow, "released"),
            totalsRow == null ? 0L : count(totalsRow, "cancelled"),
            totalsRow == null ? 0L : count(totalsRow, "defective"),
            totalsRow == null ? 0L : count(totalsRow, "pending"),
            totalsRow == null
                ? BigDecimal.ZERO
                : nz(totalsRow.get("allocate_quantity", BigDecimal.class)),
            totalsRow == null ? 0L : count(totalsRow, "coverage_lines")),
        rows.stream().map(LocationAllocationService::decision).toList(),
        total,
        page,
        size,
        "筛选与排序都作用在本租户的行上（RLS + 显式 tenant_id 双重）；" + "合计不受分页影响；容量与承重只看本模块已承诺的量。");
  }

  /** 决策行公共 SELECT：一条 SQL 供 byReceipt 与 list 复用，两个口径不可能各算各的。 */
  private static final String DECISION_SQL =
      """
      select a.id, a.receipt_id, r.receipt_code, a.receipt_line_id, a.sku_id,
             k.code as sku_code, k.name as sku_name,
             a.quality_status_id, q.role as quality_role, q.name as quality_name,
             a.required_type, a.warehouse_id, w.name as warehouse_name,
             a.source_location_id, s.code as source_location_code,
             a.location_id, l.code as location_code, l.name as location_name,
             a.allocate_quantity, a.required_capacity, a.required_weight,
             a.source_distance, a.affinity_skus, a.status, a.rule_summary,
             a.cancel_reason, a.released_at, a.created_at, a.updated_at
        from location_allocation a
        join inventory_quality_status q on q.tenant_id = a.tenant_id and q.id = a.quality_status_id
        join sku_ref k on k.tenant_id = a.tenant_id and k.id = a.sku_id
        join warehouse w on w.tenant_id = a.tenant_id and w.id = a.warehouse_id
        join warehouse_location l on l.tenant_id = a.tenant_id and l.id = a.location_id
        join warehouse_location s on s.tenant_id = a.tenant_id and s.id = a.source_location_id
        join goods_receipt r on r.tenant_id = a.tenant_id and r.id = a.receipt_id
      """;

  private static LocationAllocationDecision decision(org.jooq.Record r) {
    PlacementRule rule = PlacementRule.of(r.get("quality_role", String.class));
    WarehouseLocationTreeModels.LocationType requiredType =
        WarehouseLocationTreeModels.LocationType.of(r.get("required_type", String.class));
    AllocationStatus status = AllocationStatus.of(r.get("status", String.class));
    return new LocationAllocationDecision(
        r.get("id", UUID.class),
        r.get("receipt_id", UUID.class),
        r.get("receipt_code", String.class),
        r.get("receipt_line_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("quality_status_id", UUID.class),
        r.get("quality_role", String.class),
        r.get("quality_name", String.class),
        rule,
        requiredType,
        requiredType.label(),
        r.get("warehouse_id", UUID.class),
        r.get("warehouse_name", String.class),
        r.get("source_location_id", UUID.class),
        r.get("source_location_code", String.class),
        r.get("location_id", UUID.class),
        r.get("location_code", String.class),
        r.get("location_name", String.class),
        nz(r.get("allocate_quantity", BigDecimal.class)),
        r.get("required_capacity", BigDecimal.class),
        r.get("required_weight", BigDecimal.class),
        r.get("source_distance", Integer.class),
        count(r, "affinity_skus"),
        status.name(),
        status.label(),
        r.get("rule_summary", String.class),
        r.get("cancel_reason", String.class),
        r.get("released_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static LocationAllocationTotals totals(
      long decisions,
      long planned,
      long released,
      long cancelled,
      long defective,
      long pending,
      BigDecimal quantity,
      long coverage) {
    return new LocationAllocationTotals(
        decisions, planned, released, cancelled, defective, pending, quantity, coverage);
  }

  private LocationAllocationSaved saved(UUID id, String note) {
    var row =
        sql.fetchOne(
            DECISION_SQL
                + " where a.tenant_id = current_setting('app.current_tenant', true)::uuid and a.id = ?::uuid",
            id);
    if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "上架决策不存在");
    var d = decision(row);
    return new LocationAllocationSaved(
        d.id(),
        d.receiptLineId(),
        d.locationId(),
        d.locationCode(),
        d.status(),
        d.statusLabel(),
        d.requiredRule(),
        d.ruleSummary(),
        d.createdAt(),
        d.releasedAt(),
        note);
  }

  private void requireAllocation(UUID id) {
    var row =
        sql.fetchOne(
            "select 1 from location_allocation where tenant_id = current_setting('app.current_tenant', true)::uuid and id = ?::uuid",
            id);
    if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "上架决策不存在");
  }

  // ================================================================ 内部

  /** 收货单必须属于本租户，否则 404（「不存在」与「不属于你」不可区分）。 */
  private org.jooq.Record requireReceipt(UUID receiptId) {
    var row =
        sql.fetchOne(
            """
            select r.id, r.warehouse_id, r.receipt_code, r.status
              from goods_receipt r
             where r.tenant_id = current_setting('app.current_tenant', true)::uuid and r.id = ?::uuid
            """,
            receiptId);
    if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "收货单不存在");
    return row;
  }

  private List<org.jooq.Record> receiptLines(UUID receiptId, UUID lineId) {
    var rows =
        sql.fetch(
            LINE_SQL
                + " where l.tenant_id = current_setting('app.current_tenant', true)::uuid"
                + " and l.receipt_id = ?::uuid"
                + (lineId == null ? "" : " and l.id = ?::uuid")
                + " order by l.sku_id",
            lineId == null ? new Object[] {receiptId} : new Object[] {receiptId, lineId});
    if (rows.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, lineId == null ? "该收货单没有明细行" : "收货明细行不存在");
    }
    return rows;
  }

  private org.jooq.Record receiptLine(UUID receiptId, UUID lineId) {
    var rows = receiptLines(receiptId, lineId);
    return rows.get(0);
  }

  private static final String LINE_SQL =
      """
      select l.id, l.sku_id, l.quality_status_id, l.received_quantity, l.rejected_quantity,
             k.code as sku_code, k.name as sku_name,
             q.role as quality_role, q.code as quality_code, q.name as quality_name
        from goods_receipt_line l
        left join sku_ref k on k.tenant_id = l.tenant_id and k.id = l.sku_id
        join inventory_quality_status q on q.tenant_id = l.tenant_id and q.id = l.quality_status_id
      """;

  /**
   * 源库位：最近一次实收事件落在哪个库位（8.2.1.1 的 {@code goods_receipt_event.location_id}）。
   *
   * <p><b>必须有。</b>「最短上架路径」要一个起点，没有起点就算不出距离。与其给一个 编出来的距离（0 或 -1），不如说清楚：这批货还没有任何实收记录，没有可计算的上架起点。
   */
  private UUID resolveSourceLocation(UUID receiptId, UUID warehouseId) {
    var row =
        sql.fetchOne(
            """
            select e.location_id,
                   (select l.warehouse_id from warehouse_location l
                     where l.tenant_id = e.tenant_id and l.id = e.location_id) as loc_warehouse
              from goods_receipt_event e
             where e.tenant_id = current_setting('app.current_tenant', true)::uuid
               and e.receipt_id = ?::uuid
             order by e.received_at desc, e.id desc
             limit 1
            """,
            receiptId);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "该收货单还没有任何实收记录，没有可计算的上架起点库位：" + "先在 8.2.1.1 收货单里登记实收（实收必须落到一个库位），再回来做上架推荐。");
    }
    UUID locationId = row.get("location_id", UUID.class);
    UUID locWarehouse = row.get("loc_warehouse", UUID.class);
    if (!warehouseId.equals(locWarehouse)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该收货单的实收库位属于另一个仓库，与收货单仓库不一致，请先修正数据");
    }
    return locationId;
  }

  /** 该明细行已被未取消决策占用的数量（已指派、还没上架的）。 */
  private BigDecimal allocatedQuantity(UUID lineId) {
    var row =
        sql.fetchOne(
            """
            select coalesce(sum(allocate_quantity),0) as allocated
              from location_allocation
             where tenant_id = current_setting('app.current_tenant', true)::uuid
               and receipt_line_id = ?::uuid and status <> 'CANCELLED'
            """,
            lineId);
    return row == null ? BigDecimal.ZERO : nz(row.get("allocated", BigDecimal.class));
  }

  private long countOfLocations(UUID warehouseId, WarehouseLocationTreeModels.LocationType type) {
    return countOfLocations(warehouseId, type, null);
  }

  private long countOfLocations(
      UUID warehouseId, WarehouseLocationTreeModels.LocationType type, String status) {
    var row =
        sql.fetchOne(
            """
            select count(*) as n from warehouse_location
             where tenant_id = current_setting('app.current_tenant', true)::uuid
               and warehouse_id = ?::uuid and type = ?
            """
                + (status == null ? "" : " and status = ?"),
            status == null
                ? new Object[] {warehouseId, type.name()}
                : new Object[] {warehouseId, type.name(), status});
    return row == null ? 0L : count(row, "n");
  }

  /** 库位引用（含它在树里的深度），用于计划头部的「货现在在哪」。 */
  private org.jooq.Record locationRef(UUID locationId) {
    if (locationId == null) return null;
    var row =
        sql.fetchOne(
            """
            with recursive t as (
              select l.id, l.parent_id, l.code, l.type, 0 as depth
                from warehouse_location l
               where l.tenant_id = current_setting('app.current_tenant', true)::uuid
                 and l.id = ?::uuid and l.parent_id is null
              union all
              select c.id, c.parent_id, c.code, c.type, t.depth + 1
                from warehouse_location c join t on t.id = c.parent_id
               where c.tenant_id = current_setting('app.current_tenant', true)::uuid
            )
            select id, code, type, depth from t where id = ?::uuid
            """,
            locationId,
            locationId);
    return row == null
        ? sql.fetchOne(
            "select id, code, type, 0 as depth from warehouse_location"
                + " where tenant_id = current_setting('app.current_tenant', true)::uuid and id = ?::uuid",
            locationId)
        : row;
  }

  private org.jooq.Record locationRow(UUID warehouseId, UUID locationId) {
    return sql.fetchOne(
        """
        select id, code, name, type, status from warehouse_location
         where tenant_id = current_setting('app.current_tenant', true)::uuid
           and warehouse_id = ?::uuid and id = ?::uuid
        """,
        warehouseId,
        locationId);
  }

  /**
   * 两个库位之间的树跳数（V105 的 {@code parent_id} 树）。
   *
   * <p>LCA 层号 = 两条路径<b>共有段数</b>（库位编码在仓内唯一，所以共有段必然落在公共祖先链上）。 距离 = depth(a) + depth(b) − 2×LCA。
   */
  private int distanceBetween(UUID warehouseId, UUID from, UUID to) {
    var row =
        sql.fetchOne(
            """
            with recursive t as (
              select l.id, l.parent_id, l.code, 0 as depth, l.code::text as path
                from warehouse_location l
               where l.tenant_id = current_setting('app.current_tenant', true)::uuid
                 and l.warehouse_id = ?::uuid and l.parent_id is null
              union all
              select c.id, c.parent_id, c.code, t.depth + 1, t.path || '|' || c.code
                from warehouse_location c join t on t.id = c.parent_id
               where c.tenant_id = current_setting('app.current_tenant', true)::uuid
                 and c.warehouse_id = ?::uuid
            ),
            a as (select depth, path from t where id = ?::uuid),
            b as (select depth, path from t where id = ?::uuid)
            select (select depth from a) + (select depth from b)
                   - 2 * (select count(*) from unnest(string_to_array((select path from a),'|')) as s(v)
                           where s.v = any(string_to_array((select path from b),'|'))) as distance
            """,
            warehouseId,
            warehouseId,
            from,
            to);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "源库位与目标库位不在同一棵库位树上，算不出上架路径");
    }
    Integer d = row.get("distance", Integer.class);
    return d == null ? 0 : d;
  }

  /** 入库这批货与目标库位上已有 SKU 的历史共置数（决策快照用）。 */
  private long affinitySkus(
      UUID warehouseId, UUID sourceLocationId, UUID locationId, org.jooq.Record line) {
    var row =
        sql.fetchOne(
            """
            select count(distinct k.sku_id) as affinity
              from stock_bucket k
             where k.tenant_id = current_setting('app.current_tenant', true)::uuid
               and k.location_id = ?::uuid and k.on_hand > 0 and k.sku_id <> ?::uuid
               and exists (select 1 from stock_bucket a
                            where a.tenant_id = k.tenant_id and a.sku_id = ?::uuid
                              and a.location_id <> ?::uuid
                              and exists (select 1 from stock_bucket b
                                           where b.tenant_id = a.tenant_id
                                             and b.location_id = a.location_id
                                             and b.sku_id = k.sku_id))
            """,
            locationId,
            line.get("sku_id", UUID.class),
            line.get("sku_id", UUID.class),
            locationId);
    return row == null ? 0L : count(row, "affinity");
  }

  private static String ruleSummary(
      PlacementRule rule,
      WarehouseLocationTreeModels.LocationType requiredType,
      int distance,
      long affinitySkus) {
    return "质量状态「"
        + rule.label()
        + "」→ "
        + requiredType.label()
        + "类库位（硬规则）；"
        + "上架路径 "
        + distance
        + " 跳；"
        + "同品类聚类命中 "
        + affinitySkus
        + " 个共置 SKU。";
  }

  private static String mixNote(PlacementRule rule) {
    return switch (rule) {
      case DEFECTIVE -> "残次品混进非残次库位会跟着正常品一起被拣走发出去，蓝图明确禁止";
      case PENDING_INSPECTION -> "待检品混进非待检库位等于未过质检就进了可发货区";
      case SELLABLE -> "质量状态与库位类型不一致会让「这批货在不在待检区」永远答不出来";
    };
  }

  private static int clamp(Integer value, int fallback, int max) {
    if (value == null) return fallback;
    if (value < 1) return fallback;
    return Math.min(value, max);
  }

  private static String required(String raw, int max, String field) {
    if (raw == null || raw.isBlank()) throw new IllegalArgumentException(field + "不能为空");
    String v = raw.trim();
    if (v.length() > max) throw new IllegalArgumentException(field + "长度不能超过 " + max);
    return v;
  }

  private static BigDecimal nonNegative(BigDecimal value) {
    if (value == null) return null;
    if (value.compareTo(BigDecimal.ZERO) < 0) {
      throw new IllegalArgumentException("容量与承重需求不能为负数");
    }
    return value;
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  private static long count(org.jooq.Record r, String column) {
    Long v = r.get(column, Long.class);
    return v == null ? 0L : v;
  }

  private static long count(org.jooq.Record r, int column) {
    Long v = r.get(column, Long.class);
    return v == null ? 0L : v;
  }
}
