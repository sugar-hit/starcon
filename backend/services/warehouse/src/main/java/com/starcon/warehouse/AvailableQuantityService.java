package com.starcon.warehouse;

import static com.starcon.warehouse.AvailableQuantityModels.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 7.1.1.3 可用量计算：**全系统唯一的可售量口径**。
 *
 * <p>蓝图关系段的原话是「可售量是全系统的单一口径来源，不允许各系统各自算 —— 这是超卖的第一根源」。 兑现这句话的办法不是「再建一张表」，而是<b>让这个算式只有这一处</b>：
 * 本类里的每一个数都由 {@code stock_bucket}（V2）× {@code inventory_quality_status}（V1）× {@code
 * warehouse_location / inventory_lot}（V2）× {@code inventory_policy}（V1）现算，
 * 读时不写、算完不留痕。所以本模块<b>没有自己的迁移</b>：派生数据存下来只会比它描述的库存更早过期， 而过期的那一份恰好是防超卖用的数。
 *
 * <p><b>算式（与 {@link AvailableQuantityModels} 的类头逐字对应）</b>：
 *
 * <pre>
 *   ① bucket 可用 = on_hand − reserved        ← stock_bucket.available 生成列
 *   ② 可售基数     = Σ available，限 role='SELLABLE' 且库位可拣且启用 且批次未冻结
 *   ③ 可售量       = max(0, ② − ④)
 *   ④ 安全缓冲     = Σ_SKU inventory_policy.safety_stock（SKU &gt; WAREHOUSE &gt; DEFAULT）
 * </pre>
 *
 * <p><b>预占（7.2.1.2 / V107）是从①里进来的，不是另减一次。</b>{@code stock_bucket.reserved} 由 V2 的触发器同步自 {@code
 * inventory_reservation}，V107 的分配表把「圈在哪」钉到 仓/货位/批次三维。蓝图的总纲写着「available = on_hand −
 * reserved，<b>没有第三个来源</b>」， 所以本模块<b>不去 join reservation_allocation 重新累一遍</b>：那样等于把预占扣两次 —— 一次在
 * {@code reserved} 列里，一次在分配表里。两边一旦不一致（分配表有终态行、余额还没回写），
 * 可售量就会凭空少一块，而且少得毫无规律。分配表在本模块里的正确用法是**溯源**（哪个预占占了哪些量）， 那是 7.2.1.2 的输出，不是本模块的输入。
 *
 * <p><b>「不重复扣减」的实现方式是互斥五片，而不是连减五刀。</b>待检、残次、未上架、批次冻结 都是把 ② 的<b>筛选范围</b>缩小，它们与「合格可售」那片互斥且穷尽，于是
 * {@code Σ五片 == available} 恒等。若改成先算 available 再依次减，残次货会被扣两次 （先当不合格排除、再当扣减项），而恒等式会当场破掉。
 *
 * <p><b>与 7.2.1.3（并行车道）的边界</b>：本模块<b>不实现释放，也不对预占用量做自动过期扣减</b>。 仍在 HELD
 * 态且已超时的那部分预占<b>照扣</b>（它此刻确实占着货），并在 {@link AvailableQuantityCapabilities#releaseNote()} 里说明这一点。 等
 * 7.2.1.3 落地后，改的是它把 {@code reserved} 减下去，本模块的算式一行都不用动 —— 这也正是把预占只当作 {@code reserved}
 * 列、而不在这里重算一遍的理由。
 *
 * <p><b>递归 CTE 不参与统计</b>，聚合各自独立成查询（与 8.5.2.1 同一条纪律）： 安全缓冲走 {@code scope_pairs} 这张 distinct 集合 +
 * 三个标量子查询，<b>不</b>把它 join 进主聚合再求和 —— join 进来会让缓冲按 bucket 行数被放大 N 倍。
 */
@Service
public class AvailableQuantityService {

  /** 主聚合的固定 FROM。质量状态、库位、批次都是<b>本服务</b>的表，所以这里可以放心 JOIN （与 7.1.1.2 的 FROM 同一套口径，列都逐字相同）。 */
  private static final String FROM =
      clause(
          """
          from stock_bucket b
          join sku_ref s on s.tenant_id=b.tenant_id and s.id=b.sku_id
          join warehouse w on w.tenant_id=b.tenant_id and w.id=b.warehouse_id
          join legal_entity_ref le on le.tenant_id=b.tenant_id and le.id=b.legal_entity_id
          join inventory_quality_status q on q.tenant_id=b.tenant_id and q.id=b.quality_status_id
          join warehouse_location loc
            on loc.tenant_id=b.tenant_id and loc.warehouse_id=b.warehouse_id and loc.id=b.location_id
          left join inventory_lot lot on lot.tenant_id=b.tenant_id and lot.id=b.lot_id
          """);

  /** 文本块片段的前导空格兜底（Java 文本块会丢掉 {@code """} 后的那一个换行）。 */
  private static String clause(String fragment) {
    return " " + fragment.stripLeading();
  }

  // ------------------------------------------------------------ 五片的分类表达式
  //
  // 这五条表达式是**整个模块的口径**。它们被 SELECT、GROUP BY 与 ORDER BY 共用，
  // 所以「界面上算的可售」与「库里算的可售」不可能分叉成两个数。
  //
  // 「合格且允许销售」三个条件同时成立才算入可售基数：
  //   合格      : quality_status.role = 'SELLABLE'（V1 的受控三值词表）
  //   允许销售  : location.pickable = true 且 status = 'ACTIVE'
  //               —— V2 建这列时写明「可售量计算（7.1.1.3）要把不可拣库位的数量排除在可发货之外」
  //   批次可用  : lot 为空（非批次管理品）或 lot.status = 'ACTIVE'

  private static final String QUALIFIED =
      "q.role='SELLABLE' and loc.pickable and loc.status='ACTIVE'"
          + " and (lot.id is null or lot.status='ACTIVE')";

  /** 片一：合格可售 —— 唯一计入可售基数的那一片。 */
  private static final String SELLABLE = "case when " + QUALIFIED + " then b.available else 0 end";

  /** 片二：待检。role 判定，与库位/批次无关（质检中的货不管放哪都发不出去）。 */
  private static final String PENDING =
      "case when q.role='PENDING_INSPECTION' then b.available else 0 end";

  /** 片三：残次。 */
  private static final String DEFECTIVE =
      "case when q.role='DEFECTIVE' then b.available else 0 end";

  /**
   * 片四：未上架 / 不可发货。
   *
   * <p>带 {@code q.role='SELLABLE'} 前缀是<b>互斥性</b>的要求：待检品放在不可拣库位上， 它只该落进片二。若这里不限定 role，同一批货会同时进片二与片四，
   * 「Σ五片 == available」当场破掉，而破掉的方式正是蓝图禁止的「重复扣减」。
   */
  private static final String UNLISTED =
      "case when q.role='SELLABLE' and (not loc.pickable or loc.status<>'ACTIVE')"
          + " then b.available else 0 end";

  /** 片五：批次冻结 / 耗尽 / 已过期。同样带 role 前缀保证与片四互斥。 */
  private static final String BLOCKED_LOT =
      "case when q.role='SELLABLE' and loc.pickable and loc.status='ACTIVE'"
          + " and lot.id is not null and lot.status<>'ACTIVE' then b.available else 0 end";

  /** 五片求和 —— 与 {@code Σ b.available} 恒等（{@code sliceBalanced} 的库内判据）。 */
  private static final String SLICES_TOTAL =
      "(" + SELLABLE + "+" + PENDING + "+" + DEFECTIVE + "+" + UNLISTED + "+" + BLOCKED_LOT + ")";

  /**
   * 五片的量、行数与合计。
   *
   * <p>量与行数<b>同一条 SQL、同一条 where</b> 算出来，所以界面上不可能出现 「这一片有 500 件但一行都没有」这种无法解释的组合 —— 那只会是两处口径漂移的产物。
   */
  private static final String SLICE_COLUMNS =
      " coalesce(sum("
          + SELLABLE
          + "),0) as s_sellable,"
          + " coalesce(sum("
          + PENDING
          + "),0) as s_pending,"
          + " coalesce(sum("
          + DEFECTIVE
          + "),0) as s_defective,"
          + " coalesce(sum("
          + UNLISTED
          + "),0) as s_unlisted,"
          + " coalesce(sum("
          + BLOCKED_LOT
          + "),0) as s_blocked_lot,"
          + " count(*) filter (where "
          + QUALIFIED
          + ") as bc_sellable,"
          + " count(*) filter (where q.role='PENDING_INSPECTION') as bc_pending,"
          + " count(*) filter (where q.role='DEFECTIVE') as bc_defective,"
          + " count(*) filter (where q.role='SELLABLE' and (not loc.pickable or loc.status<>'ACTIVE')) as bc_unlisted,"
          + " count(*) filter (where q.role='SELLABLE' and loc.pickable and loc.status='ACTIVE'"
          + " and lot.id is not null and lot.status<>'ACTIVE') as bc_blocked_lot,"
          + " coalesce(sum("
          + SLICES_TOTAL
          + "),0) as slices_total,";

  /** 排序键白名单。排序键来自 query string，只允许这四个；非法键报 400。 */
  private static final List<String> SORTS = List.of("ON_HAND", "AVAILABLE", "SELLABLE", "SKU_CODE");

  /**
   * 可售量算式原文，随响应返回。
   *
   * <p>蓝图说可售量是全系统的单一口径来源 —— 那么口径本身就必须能被每个消费端读到， 而不是只躺在文档里等人来对。同一段话也在 {@link
   * AvailableQuantityCapabilities} 里。
   */
  private static final String FORMULA =
      "sellable = max(0, Σ available[role='SELLABLE' ∧ location.pickable ∧ location.status='ACTIVE' "
          + "∧ (lot 为空 ∨ lot.status='ACTIVE')] − Σ_SKU policy.safety_stock"
          + "[scope: SKU > WAREHOUSE > DEFAULT])；available = on_hand − reserved（生成列）";

  /** 五片恒等式原文，随响应返回。 */
  private static final String SLICE_IDENTITY =
      "合格可售 + 待检 + 残次 + 未上架/不可发货 + 批次冻结 == Σ available" + "（五片互斥且穷尽；被排除的片是**筛选**不是扣减，不重复扣减）";

  private final DSLContext sql;
  private final SellablePolicyService sellablePolicies;

  public AvailableQuantityService(DSLContext sql, SellablePolicyService sellablePolicies) {
    this.sql = sql;
    this.sellablePolicies = sellablePolicies;
  }

  // ------------------------------------------------------------ 能力声明

  /** 接入情况。前端据此<b>禁用</b>不可用维度并说明归属模块，而不是让用户填完再收一个 422。 */
  public AvailableQuantityCapabilities capabilities() {
    return new AvailableQuantityCapabilities(
        true,
        "7.1.1.2 / V1",
        "质量状态取 inventory_quality_status.role 的受控三值词表（SELLABLE / PENDING_INSPECTION / DEFECTIVE），"
            + "是本服务自己的表，五片互斥性由它保证",
        true,
        "8.1.2.1 / V2",
        "库位可拣属性 warehouse_location.pickable 与 status 均已交付；V2 建表时即写明供 7.1.1.3 排除不可发货库位",
        true,
        "7.3 / V2",
        "批次状态 inventory_lot.status（ACTIVE / DEPLETED / EXPIRED）已交付，非 ACTIVE 的批次不计入可售",
        true,
        "7.2.1 / V1",
        "安全缓冲取 inventory_policy.safety_stock，按 SKU > WAREHOUSE > DEFAULT 解析；" + "未配置时为 null 而不是 0",
        true,
        "7.2.2.1 / V56",
        "生效渠道口径只读取与展示，平台回写额仍由 7.2.2.1 算；本模块不重复实现共享池分配",
        false,
        "7.3.1.2",
        "按日期的效期剔除（lot.expires_on < 今天）刻意不做：没有效期策略时凭空剔货，" + "会让一个还没设效期的租户少一批可售，而少掉的那批正是没人能解释的部分",
        false,
        "7.2.1.3",
        "预占过期自动释放属 7.2.1.3（并行车道），本模块不实现释放，也不对预占用量做自动过期扣减",
        "预占从 stock_bucket.reserved 进入算式（V2 触发器同步自 inventory_reservation），"
            + "本模块不 join reservation_allocation 重算一遍 —— 两处各算一次会重复扣减。"
            + "7.2.1.3 落地前，仍处于 HELD 且已超时的预占会照常占用可售量；"
            + "它落地后由它把 reserved 减下去，本模块算式无需改动",
        FORMULA,
        SLICE_IDENTITY,
        "可售量不落库、不留历史快照：请求一个历史时刻的可售量没有定义（预占与释放在动），" + "computedAt 取服务端当前时刻，不接受调用方传时刻");
  }

  // ------------------------------------------------------------ 报告

  /** 口径报告：合计 + 构成拆解 + 能力声明 + 生效渠道口径。纯读，不写任何表。 */
  public AvailableQuantityReport report(AvailableQuantityQuery q) {
    var binds = new ArrayList<Object>();
    var where = new ArrayList<String>();
    conditions(q, where, binds);
    String w = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    Map<UUID, SafetyBuffer> buffers = buffers(w, binds);

    Record totals =
        sql.fetchOne(
            "select coalesce(sum(b.on_hand),0) as on_hand,"
                + " coalesce(sum(b.reserved),0) as reserved,"
                + " coalesce(sum(b.available),0) as available,"
                + SLICE_COLUMNS
                + " count(distinct b.sku_id) as sku_count,"
                + " count(*) as bucket_count,"
                + " count(distinct b.warehouse_id) as warehouse_count,"
                + " count(distinct b.location_id) as location_count,"
                + " count(distinct b.lot_id) as lot_count"
                + FROM
                + w,
            binds.toArray());

    return new AvailableQuantityReport(
        totals(totals, buffers),
        channelPolicy(q),
        capabilities(),
        java.time.OffsetDateTime.now(),
        "构成拆解是**并列的切分**不是瀑布：被排除的片不进可售基数，也不再当扣减项扣一次");
  }

  // ------------------------------------------------------------ 分 SKU 行

  /** 分 SKU 的可售量。真分页 + 排序白名单；合计来自同一条 FROM 独立聚合，不受分页影响。 */
  public AvailableQuantityPage skuRows(AvailableQuantityQuery q) {
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, q.size() == null ? 50 : q.size()));
    int page = Math.max(0, q.page() == null ? 0 : q.page());
    var binds = new ArrayList<Object>();
    var where = new ArrayList<String>();
    conditions(q, where, binds);
    String w = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    Map<UUID, SafetyBuffer> buffers = buffers(w, binds);

    Record totals =
        sql.fetchOne(
            "select coalesce(sum(b.on_hand),0) as on_hand,"
                + " coalesce(sum(b.reserved),0) as reserved,"
                + " coalesce(sum(b.available),0) as available,"
                + SLICE_COLUMNS
                + " count(distinct b.sku_id) as sku_count,"
                + " count(*) as bucket_count,"
                + " count(distinct b.warehouse_id) as warehouse_count,"
                + " count(distinct b.location_id) as location_count,"
                + " count(distinct b.lot_id) as lot_count"
                + FROM
                + w,
            binds.toArray());

    var rows =
        sql.fetch(
            "select b.sku_id,s.code as sku_code,s.name as sku_name,"
                + " coalesce(sum(b.on_hand),0) as on_hand,"
                + " coalesce(sum(b.reserved),0) as reserved,"
                + " coalesce(sum(b.available),0) as available,"
                + " coalesce(sum("
                + SELLABLE
                + "),0) as sellable_before_buffer,"
                + " count(*) as bucket_count,"
                + " count(distinct b.warehouse_id) as warehouse_count,"
                + " count(distinct b.location_id) as location_count,"
                + " count(distinct b.lot_id) as lot_count"
                + FROM
                + w
                + " group by b.sku_id,s.code,s.name"
                + order(q)
                + " limit ? offset ?",
            append(binds, size, (long) page * size));

    long total =
        sql.fetchOne("select count(distinct b.sku_id)" + FROM + w, binds.toArray())
            .get(0, Long.class);

    return new AvailableQuantityPage(
        totals(totals, buffers),
        rows.stream().map(r -> row(r, buffers.get(r.get("sku_id", UUID.class)))).toList(),
        total,
        page,
        size,
        "缓冲按 SKU 解析后汇总：SKU 档只计一次，WAREHOUSE 档按范围内该 SKU 所在的仓各计一次，" + "避免同一份安全库存被多个 bucket 行放大");
  }

  // ------------------------------------------------------------ 合计组装

  /** 一个 SKU 的缓冲解析结果。{@code buffer} 为 null = 没配。 */
  private record SafetyBuffer(BigDecimal buffer, String scopeType) {}

  /** 构成拆解的固定序：片一在前，其余按排除原因排。界面上的行序不能随数据变。 */
  private static AvailableQuantitySlice slice(
      String code, String label, boolean counted, String basis, BigDecimal amount, long buckets) {
    return new AvailableQuantitySlice(code, label, amount, buckets, counted, basis);
  }

  /**
   * 由一行聚合结果组装合计与拆解。
   *
   * <p>{@code safetyBuffer}（租户配没配）与 {@code safetyBufferDeducted}（这次减了多少） 是<b>两个不同的数</b>，必须分开：没配缓冲时
   * deducted = 0（算式里没有减项）， 但 safetyBuffer 必须是 null 而不是 0 —— 「没留缓冲」与「留了 0 件缓冲」在界面上
   * 必须能区分，否则运营会以为系统替他保住了 0 件。
   */
  private AvailableQuantityTotals totals(Record r, Map<UUID, SafetyBuffer> buffers) {
    BigDecimal available = decimal(r, "available");
    BigDecimal before = decimal(r, "s_sellable");

    BigDecimal bufferSum = BigDecimal.ZERO;
    long bufferedSkuCount = 0;
    for (var b : buffers.values()) {
      if (b.buffer() != null) {
        bufferSum = bufferSum.add(b.buffer());
        bufferedSkuCount++;
      }
    }
    // 范围内一个 SKU 都没配过 → 配置值给 null；配过的按实际求和（可能恰好是 0，那是真的配了 0）。
    BigDecimal configured = bufferedSkuCount == 0 ? null : bufferSum;
    BigDecimal deducted = bufferSum;
    BigDecimal raw = before.subtract(deducted);
    boolean exceeds = raw.signum() < 0;
    BigDecimal sellable = exceeds ? BigDecimal.ZERO : raw;

    var slices =
        List.of(
            slice(
                "SELLABLE",
                "合格可售",
                true,
                "role=SELLABLE ∧ 库位可拣启用 ∧ 批次未冻结",
                decimal(r, "s_sellable"),
                count(r, "bc_sellable")),
            slice(
                "PENDING_INSPECTION",
                "待检",
                false,
                "role=PENDING_INSPECTION，被排除不重复扣减",
                decimal(r, "s_pending"),
                count(r, "bc_pending")),
            slice(
                "DEFECTIVE",
                "残次",
                false,
                "role=DEFECTIVE，被排除不重复扣减",
                decimal(r, "s_defective"),
                count(r, "bc_defective")),
            slice(
                "UNLISTED",
                "未上架 / 不可发货",
                false,
                "role=SELLABLE 但库位不可拣或已停用；**排除**而非从可售基数再扣一次",
                decimal(r, "s_unlisted"),
                count(r, "bc_unlisted")),
            slice(
                "BLOCKED_LOT",
                "批次已冻结 / 耗尽 / 过期",
                false,
                "role=SELLABLE、库位可拣，但批次状态非 ACTIVE；**排除**而非再扣一次",
                decimal(r, "s_blocked_lot"),
                count(r, "bc_blocked_lot")));

    // 恒等式判定位：五片之和必须恰好等于 available。库里有 CHECK(available>=0) 与
    // V2 的 balance↔bucket 守恒触发器兜底，但恒等式本身坏掉时（口径改错）
    // 要能当场看见，而不是等它变成一个超卖。
    boolean balanced = decimal(r, "slices_total").compareTo(available) == 0;

    return new AvailableQuantityTotals(
        decimal(r, "on_hand"),
        decimal(r, "reserved"),
        available,
        before,
        configured,
        deducted,
        bufferedSkuCount,
        count(r, "sku_count"),
        count(r, "bucket_count"),
        count(r, "warehouse_count"),
        count(r, "location_count"),
        count(r, "lot_count"),
        sellable,
        exceeds,
        slices,
        balanced);
  }

  private AvailableQuantityRow row(Record r, SafetyBuffer buffer) {
    BigDecimal before = decimal(r, "sellable_before_buffer");
    BigDecimal value =
        buffer == null || buffer.buffer() == null ? BigDecimal.ZERO : buffer.buffer();
    BigDecimal raw = before.subtract(value);
    boolean exceeds = raw.signum() < 0;
    return new AvailableQuantityRow(
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        decimal(r, "on_hand"),
        decimal(r, "reserved"),
        decimal(r, "available"),
        before,
        buffer == null ? null : buffer.buffer(),
        buffer == null ? "NONE" : buffer.scopeType(),
        buffer == null ? "未配置安全缓冲" : scopeLabel(buffer.scopeType()),
        exceeds ? BigDecimal.ZERO : raw,
        exceeds,
        count(r, "bucket_count"),
        count(r, "warehouse_count"),
        count(r, "location_count"),
        r.get("lot_count", Long.class));
  }

  private static String scopeLabel(String scopeType) {
    return switch (scopeType) {
      case "SKU" -> "SKU 档";
      case "WAREHOUSE" -> "仓库档（范围内该 SKU 所在仓各计一次）";
      case "DEFAULT" -> "全租户兜底档";
      default -> "未配置";
    };
  }

  // ------------------------------------------------------------ 安全缓冲解析

  /**
   * 按 SKU 解析安全缓冲。
   *
   * <p><b>三条纪律</b>：
   *
   * <ol>
   *   <li><b>不 join 进主聚合。</b>{@code scope_pairs} 先把 (tenant, sku, warehouse) 去重， 再算缓冲。若直接 join
   *       进主聚合，缓冲会被 bucket 行数放大 —— 一个 SKU 有 20 条 bucket 记录，它的安全库存就被扣 20 次。
   *   <li><b>SKU 档只计一次，WAREHOUSE 档按仓各计一次。</b>安全库存的承诺单位不同： SKU 档是「这个 SKU 全网留 N 件」，WAREHOUSE
   *       档是「这个仓给这个 SKU 留 N 件」。 一个 SKU 落在 3 个仓上、3 个仓各有 WAREHOUSE 档，求和是对的（3 份承诺）， 而 SKU 档一旦命中就必须只算一份
   *       —— 否则同一份承诺被算 3 次。
   *   <li><b>三个分支都只取 {@code safety_stock IS NOT NULL} 的行。</b>这样 {@code coalesce} 命中的来源与 {@code
   *       scope_type} 判定的来源必然一致； 若 WAREHOUSE 档存在但 safety_stock 为 null 而实际值来自 DEFAULT， scope_type
   *       不能报成 WAREHOUSE（那是另一条策略，与本值无关）。
   * </ol>
   */
  private Map<UUID, SafetyBuffer> buffers(String w, List<Object> binds) {
    var result = new LinkedHashMap<UUID, SafetyBuffer>();
    sql.fetch(
            """
            with scope_pairs as materialized (
              select distinct b.tenant_id,b.sku_id,b.warehouse_id
                from stock_bucket b
                join sku_ref s on s.tenant_id=b.tenant_id and s.id=b.sku_id
            """
                + w
                + """
            ),
            sku_buf as (
              select sp.tenant_id,sp.sku_id,p.safety_stock
                from scope_pairs sp
                join inventory_policy p
                  on p.tenant_id=sp.tenant_id and p.enabled
                 and p.scope_type='SKU' and p.scope_key=sp.sku_id::text
               where p.safety_stock is not null
            ),
            wh_buf as (
              select sp.tenant_id,sp.sku_id,p.safety_stock
                from scope_pairs sp
                join inventory_policy p
                  on p.tenant_id=sp.tenant_id and p.enabled
                 and p.scope_type='WAREHOUSE' and p.scope_key=sp.warehouse_id::text
               where p.safety_stock is not null
            ),
            skus as (
              select distinct tenant_id,sku_id from scope_pairs
            ),
            resolved as (
              select k.tenant_id,k.sku_id,
                     (select s.safety_stock from sku_buf s
                       where s.tenant_id=k.tenant_id and s.sku_id=k.sku_id limit 1) as sku_buffer,
                     (select sum(wb.safety_stock) from wh_buf wb
                       where wb.tenant_id=k.tenant_id and wb.sku_id=k.sku_id) as wh_buffer,
                     (select d.safety_stock from inventory_policy d
                       where d.tenant_id=k.tenant_id and d.enabled
                         and d.scope_type='DEFAULT' and btrim(d.scope_key)=''
                         and d.safety_stock is not null limit 1) as default_buffer
                from skus k
            )
            select r.tenant_id,r.sku_id,
                   coalesce(r.sku_buffer,r.wh_buffer,r.default_buffer) as safety_stock,
                   case when r.sku_buffer is not null then 'SKU'
                        when r.wh_buffer is not null then 'WAREHOUSE'
                        when r.default_buffer is not null then 'DEFAULT'
                        else 'NONE' end as scope_type
              from resolved r
            """,
            binds.toArray())
        .forEach(
            r -> {
              UUID tenant = r.get("tenant_id", UUID.class);
              UUID sku = r.get("sku_id", UUID.class);
              if (tenant != null && sku != null) {
                result.put(
                    sku,
                    new SafetyBuffer(
                        r.get("safety_stock", BigDecimal.class),
                        r.get("scope_type", String.class)));
              }
            });
    return result;
  }

  // ------------------------------------------------------------ 渠道口径（7.2.2.1）

  /**
   * 生效的渠道口径，<b>只读与展示</b>。
   *
   * <p>刻意不重算平台回写额：共享池的分配与池余量是 7.2.2.1 的产出（{@code /pools} 与 {@code
   * /calculation}），本模块再造一遍就会与它漂移，而这两个数都会被回写到平台上 —— 漂移的直接后果是超卖或断货。
   */
  private AvailableChannelPolicy channelPolicy(AvailableQuantityQuery q) {
    // 档位名直接透传给 7.2.2.1，由**它**裁决非法值并抛 400 —— 本模块不维护第二份
    // 档位词表（那正是「同一个词表两处写、后来漂移」的起点）。
    var effective = sellablePolicies.effective(q.channelScopeType(), q.channelReferenceId(), null);
    var row = effective.policy();
    return new AvailableChannelPolicy(
        row != null,
        row == null ? null : row.id(),
        q.channelScopeType() == null || q.channelScopeType().isBlank()
            ? "DEFAULT"
            : q.channelScopeType(),
        effective.resolvedScopeType().name(),
        row == null ? null : row.poolMode().name(),
        row == null ? null : row.poolKey(),
        row == null ? null : row.bufferRatio(),
        row != null && row.oversellAllowed(),
        row == null ? null : row.oversellTolerance(),
        row == null ? null : row.platformStockScope().name(),
        row != null && row.includeInTransit(),
        effective.usedFallback(),
        "7.2.2.1",
        row == null
            ? "全租户未配置渠道可售量口径：平台回写额由 7.2.2.1 拒绝回写，本模块也不按 0 处理"
            : "本模块只展示口径，平台回写额仍由 7.2.2.1 算（bufferRatio 是「给平台留百分之几」，"
                + "与本模块的 safetyBuffer「留多少件」不是同一个数）");
  }

  // ------------------------------------------------------------ 筛选与排序

  /**
   * 筛选条件。
   *
   * <p><b>所有条件只引用 {@code b}（bucket 事实表）与 {@code s}（sku 参照）</b>， 所以同一段条件既能用在大 FROM 上，也能用在 {@code
   * scope_pairs} 那条只 join 了 {@code sku_ref} 的窄查询上 —— 缓冲解析与主聚合因此共用<b>同一份</b>筛选定义， 不会出现「主聚合按 A
   * 范围算、缓冲按 B 范围算」这种错得毫无规律的不一致。
   */
  private void conditions(AvailableQuantityQuery q, List<String> where, List<Object> binds) {
    if (q.warehouseId() != null) {
      where.add("b.warehouse_id=?");
      binds.add(q.warehouseId());
    }
    if (q.legalEntityId() != null) {
      where.add("b.legal_entity_id=?");
      binds.add(q.legalEntityId());
    }
    if (q.locationId() != null) {
      where.add("b.location_id=?");
      binds.add(q.locationId());
    }
    if (q.skuId() != null) {
      where.add("b.sku_id=?");
      binds.add(q.skuId());
    }
    if (q.qualityStatusId() != null) {
      where.add("b.quality_status_id=?");
      binds.add(q.qualityStatusId());
    }
    // 关键字：SKU 编码与名称。可用量页最常见的用法就是「搜一个 SKU 看它还能卖多少」。
    if (q.keyword() != null && !q.keyword().isBlank()) {
      where.add("(s.code ilike ? or s.name ilike ?)");
      String like = "%" + q.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
    }
  }

  private String order(AvailableQuantityQuery q) {
    String key =
        q.sort() == null || q.sort().isBlank()
            ? "SELLABLE"
            : q.sort().trim().toUpperCase(java.util.Locale.ROOT);
    if (!SORTS.contains(key)) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "排序键不在白名单内：" + key);
    }
    // 决胜键补 sku_id：同一 SKU 只有一个分组，但显式补上让 ORDER BY 完全确定，
    // 翻页时不依赖 PG 对 GROUP BY 的实现细节。
    return switch (key) {
      case "ON_HAND" -> " order by on_hand desc,b.sku_id";
      case "AVAILABLE" -> " order by available desc,b.sku_id";
      case "SKU_CODE" -> " order by s.code,b.sku_id";
      default -> " order by sellable_before_buffer desc,b.sku_id";
    };
  }

  // ------------------------------------------------------------ 小工具

  private static Object[] append(List<Object> binds, Object... extra) {
    var out = new ArrayList<>(binds);
    out.addAll(List.of(extra));
    return out.toArray();
  }

  private static BigDecimal decimal(Record r, String column) {
    return nz(r.get(column, BigDecimal.class));
  }

  private static long count(Record r, String column) {
    Long v = r.get(column, Long.class);
    return v == null ? 0L : v;
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }
}
