package com.starcon.channel;

import static com.starcon.channel.ListingMappingVersionModels.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 4.1.2.2 映射版本。
 *
 * <p>蓝图：「映射变更时生成新版本而非原地修改，保留每个版本对应的商品/订单范围，支持查看某订单使用的是哪个版本、按版本回溯受影响订单。输入：映射变更；生效时间点；变更原因。输出：<b>版本列表 + 受影响订单范围查询</b>。关系：上游 = 4.1.2.1；下游 = 09 订单（{@code order_line_component} 冻结）。<b>改映射不影响已产生的订单</b>，只对新订单生效——这是财务与售后追溯的前提。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>「生成新版本而非原地修改」</b> → <b>不是</b>本类的写路径，而是 V176 的两道 DB 护栏
 *       （{@code guard_listing_mapping_version_frozen} 拒绝改写历史版本、
 *       {@code refuse_listing_mapping_version_delete} 拒绝删除已关闭版本）。
 *       本类<b>一个字节都不写</b> {@code listing_mapping_version} —— 版本由 4.1.2.1 的
 *       {@code save} / {@code remove} 产生，本类负责让它们<b>看得见、回溯得到</b>。</li>
 *   <li><b>「保留每个版本对应的商品范围」</b> → {@link #productScope} + V182 的
 *       {@code listing_mapping_version_scope} 冻结快照。商品范围<b>不是</b>现查
 *       {@code listing} 得来的：listing 会被重新同步、也可以被删除，
 *       现查会让历史被后来的事实改写 —— 那正是「保留」要防的事。</li>
 *   <li><b>「按版本回溯」</b> → {@link #list}（跨映射的版本列表，带生效区间重叠筛选）+
 *       {@link #detail}（单版详情 + <b>与上一版的真比对</b> {@code changedFields}）。</li>
 *   <li><b>「受影响订单范围查询」</b> → {@link #affectedOrders}，
 *       <b>显式 available=false</b>：受影响订单是 09 订单（trade）的真实数据，
 *       trade 侧目前没有任何可供 channel 查询订单的已交付端点，
 *       {@code order_line_component} 的冻结也未落地。
 *       AGENTS.md 禁止跨服务 SQL / 直连对方库，所以这里<b>不造假数据</b>，
 *       只如实声明归属（与 {@code ReservationController} / {@code WarehouseFacilityController} 同一手法）。</li>
 * </ul>
 *
 * <p>🔴 <b>「查不到」与「确实没有」必须可区分</b>：{@link VersionAffectedOrders#items()} 恒为
 * {@code null} 而不是空数组。返回空数组会被对账流程读成"这版映射没有影响任何订单"，
 * 而真相是"09 订单侧尚无法查询"。同理，任何一个 {@code available=false} 的维度
 * 都不返回空列表冒充「这一项没有数据」。
 *
 * <p>🔴 数据来源纪律：平台侧（listing / variant / shop）与映射版本<b>同库同服务</b>读；
 * 03 的 {@code internal_sku_id} <b>跨服务只存逻辑 ID</b>，本类<b>一条 SQL 都不碰 catalog 的库</b>，
 * 也<b>不复制 catalog 的任何一列</b>（与 V176 同一纪律）。
 */
@Service
public class ListingMappingVersionService {

  /** 单页上限：藏在代码里就等于没告诉调用方。 */
  static final int MAX_PAGE_SIZE = 200;

  static final int DEFAULT_PAGE_SIZE = 50;

  /** 排序取值 —— 前端只翻译 query string，排序全在服务端。 */
  static final List<String> SORTS = List.of("RECENT", "EFFECTIVE", "OLDEST", "VERSION");

  private static final String OWNER_ORDERS = "09 订单履约（trade 服务 · order_line_component 冻结）";

  private static final String AFFECTED_ORDERS_NOTE =
      "按版本回溯受影响订单需要 09 订单侧的真实订单数据。集成头上 trade 没有任何可供 channel "
          + "查询订单的已交付端点，order_line_component 的冻结也未落地；AGENTS.md 禁止跨服务 SQL / "
          + "直连对方库，因此本模块不返回任何订单，也不返回空数组冒充「没有受影响订单」。"
          + "本模块能真实交付的是「该版本对应的商品范围」与「改了什么」，两者都已落地。";

  /**
   * 版本行 + 商品范围的联合列。
   *
   * <p>🔴 用 {@code inner join} 范围表而不是 {@code left join}：V182 的触发器保证每个版本
   * 恰好一条范围（UNIQUE(tenant_id, version_id) 兜底），所以「版本存在但范围缺失」是
   * 数据完整性事故而不是常态。{@code left join} 会把那种事故变成界面上一个空白格子。
   */
  private static final String VERSION_COLUMNS =
      """
      select v.id, v.mapping_id, v.version_no, v.relation_type, v.mapping_status,
             v.internal_sku_id, v.component_quantity, v.effective_from, v.effective_to,
             v.change_reason, v.change_source, v.actor_id, v.created_at,
             s.listing_id, s.external_item_id, s.title_snapshot, s.listing_variant_id,
             s.external_variant_id, s.shop_code, s.captured_at
      from listing_mapping_version v
      join listing_mapping_version_scope s on s.tenant_id = v.tenant_id and s.version_id = v.id
      """;

  private final DSLContext sql;

  public ListingMappingVersionService(DSLContext sql) {
    this.sql = sql;
  }

  // ==================================================================
  // 能力声明 / 筛选项
  // ==================================================================

  /** 能力声明：逐维度 available + ownerModule + 影响。未交付段不用空数组冒充。 */
  public ListingMappingVersionCapabilities capabilities() {
    return new ListingMappingVersionCapabilities(
        List.of(
            new VersionCapabilityDimension(
                "version_list",
                "🔴 版本列表（跨映射的真分页列表 + 生效区间重叠筛选）",
                true,
                "listing_mapping_version（V176）",
                "",
                "查不到「映射改过几次、每次什么时候改的、为什么改」"),
            new VersionCapabilityDimension(
                "version_detail",
                "单版详情 + 与上一版的真比对（改了什么）",
                true,
                "listing_mapping_version（V176）",
                "",
                "只能逐条点开猜历史，回溯成本极高"),
            new VersionCapabilityDimension(
                "product_scope",
                "🔴 保留每个版本对应的商品范围（冻结快照）",
                true,
                "listing_mapping_version_scope（V182）",
                "",
                "版本说得出「指向哪个 SKU」，却说不出「当时对应哪个平台商品」——"
                    + "listing 重新同步或被删除后历史会被改写或消失"),
            new VersionCapabilityDimension(
                "effective_window",
                "🔴 生效时间点（半开区间 [effectiveFrom, effectiveTo)，null = 当前生效）",
                true,
                "listing_mapping_version（V176）",
                "",
                "回答不了「这个时刻用的是哪一版」"),
            new VersionCapabilityDimension(
                "change_reason",
                "🔴 变更原因（逐版必填）",
                true,
                "listing_mapping_version（V176）",
                "",
                "历史只剩结果、没有理由，售后与财务追溯断在半路"),
            new VersionCapabilityDimension(
                "version_creation",
                "映射变更生成新版本（写入路径在上游 4.1.2.1）",
                true,
                "listing_mapping_version · 4.1.2.1",
                "",
                "改映射会原地覆盖，历史不可回溯"),
            new VersionCapabilityDimension(
                "affected_orders",
                "🔴 按版本回溯受影响订单范围",
                false,
                "—",
                OWNER_ORDERS,
                "回答不了「改这一版会波及哪些已产生的订单」；本模块**不返回空数组冒充** —— "
                    + "09 订单侧尚无任何可供查询的真实来源，建一张空引用表只会让界面看起来已交付"),
            new VersionCapabilityDimension(
                "order_to_version",
                "查看某订单使用的是哪个版本",
                false,
                "—",
                OWNER_ORDERS,
                "订单行不冻结版本 id，事后无法从订单反查回当时的映射；"
                    + "这需要 order_line_component 在订单产生时写入版本标识，属于 09 订单的职责")),
        SORTS,
        List.of("MANUAL", "BATCH_CONFIRMED"),
        List.of("ONE_TO_ONE", "ONE_TO_MANY", "MANY_TO_ONE"),
        "受影响订单范围与「订单 → 版本」反查两段未交付：它们指向 09 订单（trade）的真实数据，"
            + "本模块不跨服务读取，也不用空结果冒充。其余维度全部已交付。",
        "上游 = 4.1.2.1 映射管理（版本行由它的 save / remove 产生）；"
            + "下游 = 09 订单（order_line_component 冻结）。"
            + "「改映射不影响已产生的订单」由 V176 的两道 DB 触发器保证，订单侧冻结版本标识后本模块的查询面才闭环。");
  }

  /** 筛选面板词表：不硬编码在前端。 */
  public VersionFilterOptions filterOptions() {
    return new VersionFilterOptions(
        List.of("ONE_TO_ONE", "ONE_TO_MANY", "MANY_TO_ONE"),
        List.of("MANUAL", "BATCH_CONFIRMED"),
        List.of("CURRENT", "CLOSED"),
        SORTS,
        MAX_PAGE_SIZE);
  }

  // ==================================================================
  // 版本列表
  // ==================================================================

  /**
   * 版本列表：真分页 + 九个筛选维度。
   *
   * <p>🔴 生效区间筛选是<b>重叠</b>语义（{@code from <= windowTo AND (to IS NULL OR to >= windowFrom)}）
   * 而不是「起点落在窗口内」：一版只要在窗口内<b>曾经生效过</b>就应该被列出来，
   * 否则「查 10 月的变更」会把 9 月生效、10 月仍在生效的那一版漏掉。
   */
  public VersionPage list(
      UUID mappingId,
      UUID listingId,
      UUID listingVariantId,
      UUID internalSkuId,
      String shopCode,
      String relationType,
      String changeSource,
      String effectiveState,
      OffsetDateTime windowFrom,
      OffsetDateTime windowTo,
      String search,
      String sort,
      int page,
      int size) {
    String sortKey = sort == null || !SORTS.contains(sort) ? "RECENT" : sort;
    int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int pageNo = Math.max(page, 0);

    List<Object> binds = new ArrayList<>();
    String where = filterWhere(
        binds,
        mappingId,
        listingId,
        listingVariantId,
        internalSkuId,
        shopCode,
        relationType,
        changeSource,
        effectiveState,
        windowFrom,
        windowTo,
        search);

    long total =
        count(
            "select count(*) from listing_mapping_version v"
                + " join listing_mapping_version_scope s on s.tenant_id = v.tenant_id and s.version_id = v.id"
                + where,
            binds.toArray());
    if (total == 0) return new VersionPage(List.of(), 0, pageNo, pageSize, 0);

    String orderBy =
        switch (sortKey) {
          case "EFFECTIVE" -> " order by v.effective_from desc, v.version_no desc, v.id";
          case "OLDEST" -> " order by v.effective_from asc, v.version_no asc, v.id";
          // 同一映射内按版本号**升序**（1 → N），读起来就是一条版本历史
          case "VERSION" -> " order by v.mapping_id, v.version_no, v.id";
          default -> " order by v.created_at desc, v.id desc";
        };

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(pageSize);
    pageBinds.add(pageNo * pageSize);
    List<VersionRow> items =
        sql.fetch(VERSION_COLUMNS + where + orderBy + " limit ? offset ?", pageBinds.toArray())
            .stream()
            .map(ListingMappingVersionService::versionRow)
            .toList();

    int currentInPage = (int) items.stream().filter(VersionRow::current).count();
    return new VersionPage(items, total, pageNo, pageSize, currentInPage);
  }

  /** 筛选条件拼装：where 片段与 binds 一一对应，顺序不可错位。 */
  private String filterWhere(
      List<Object> binds,
      UUID mappingId,
      UUID listingId,
      UUID listingVariantId,
      UUID internalSkuId,
      String shopCode,
      String relationType,
      String changeSource,
      String effectiveState,
      OffsetDateTime windowFrom,
      OffsetDateTime windowTo,
      String search) {
    StringBuilder where = new StringBuilder(" where v.tenant_id = ?");
    binds.add(currentTenant());

    if (mappingId != null) {
      where.append(" and v.mapping_id = ?");
      binds.add(mappingId);
    }
    if (listingId != null) {
      where.append(" and s.listing_id = ?");
      binds.add(listingId);
    }
    if (listingVariantId != null) {
      where.append(" and s.listing_variant_id = ?");
      binds.add(listingVariantId);
    }
    if (internalSkuId != null) {
      where.append(" and v.internal_sku_id = ?");
      binds.add(internalSkuId);
    }
    if (shopCode != null && !shopCode.isBlank()) {
      where.append(" and s.shop_code = ?");
      binds.add(shopCode.trim());
    }
    if (relationType != null && !relationType.isBlank()) {
      where.append(" and v.relation_type = ?");
      binds.add(relationType.trim());
    }
    if (changeSource != null && !changeSource.isBlank()) {
      where.append(" and v.change_source = ?");
      binds.add(changeSource.trim());
    }
    if ("CURRENT".equals(effectiveState)) {
      where.append(" and v.effective_to is null");
    } else if ("CLOSED".equals(effectiveState)) {
      where.append(" and v.effective_to is not null");
    }
    // 🔴 timestamptz 列一律 `?::timestamptz`：jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，
    // PG 直接拒（14.1.2.2 实测踩过）。
    if (windowFrom != null) {
      where.append(" and (v.effective_to is null or v.effective_to >= ?::timestamptz)");
      binds.add(windowFrom);
    }
    if (windowTo != null) {
      where.append(" and v.effective_from <= ?::timestamptz");
      binds.add(windowTo);
    }
    if (search != null && !search.isBlank()) {
      String like = "%" + escape(search.trim()) + "%";
      where.append(
          " and (v.change_reason like ? escape '!' or s.external_item_id like ? escape '!'"
              + " or s.title_snapshot like ? escape '!' or s.external_variant_id like ? escape '!'"
              + " or s.shop_code like ? escape '!' or cast(v.internal_sku_id as text) like ? escape '!')");
      for (int i = 0; i < 6; i++) binds.add(like);
    }
    return where.toString();
  }

  // ==================================================================
  // 版本详情 + 商品范围
  // ==================================================================

  /**
   * 单版详情：版本 + 冻结的商品范围 + 与上一版的真比对。
   *
   * <p>🔴 {@code changedFields} 是<b>逐字段比对</b>出来的，不是前端根据两个版本自己 diff 的：
   * 比对发生在服务端、对着库里那两行原始值，前端 diff 会在字段缺失时把"没变"读成"变了"。
   */
  public MappingVersionDetail detail(String id) {
    UUID versionId = parseId(id);
    UUID tenant = currentTenant();

    Record row =
        sql.fetchOne(VERSION_COLUMNS + " where v.tenant_id = ? and v.id = ?", tenant, versionId);
    // 跨租户与不存在都表现为这一条：不泄露"别的租户有这么一条"
    if (row == null) throw notFound();

    VersionRow version = versionRow(row);
    VersionProductScope scope = productScope(id);

    Integer previous = neighbourVersionNo(version.mappingId(), version.versionNo(), true);
    Integer next = neighbourVersionNo(version.mappingId(), version.versionNo(), false);

    List<String> changed = new ArrayList<>();
    if (previous != null) changed.addAll(changedFields(tenant, version.mappingId(), previous, version));

    return new MappingVersionDetail(
        version, scope, previous, next, changed, affectedOrders(id));
  }

  /**
   * 按版本回溯商品范围（V182 的冻结快照 + 平台侧当前值对照）。
   *
   * <p>🔴 用 {@code left join listing}：平台商品可能已被删除，而**快照仍在** ——
   * 那正是这张表存在的理由，此时 current 列为 null、{@code driftedFromPlatform=true}。
   * 用 {@code inner join} 会让"商品没了"变成"整个版本查不到"，恰好丢掉最该被记住的那一段历史。
   */
  public VersionProductScope productScope(String id) {
    UUID versionId = parseId(id);
    UUID tenant = currentTenant();

    Record row =
        sql.fetchOne(
            """
            select s.version_id, s.mapping_id, s.listing_id, s.listing_variant_id,
                   s.external_item_id, s.title_snapshot, s.external_variant_id, s.shop_code,
                   s.captured_at, l.external_item_id as current_external_item_id,
                   l.title as current_title
            from listing_mapping_version_scope s
            left join listing l on l.tenant_id = s.tenant_id and l.id = s.listing_id
            where s.tenant_id = ? and s.version_id = ?
            """,
            tenant,
            versionId);
    if (row == null) throw notFound();

    String snapshotItem = row.get("external_item_id", String.class);
    String snapshotTitle = row.get("title_snapshot", String.class);
    String currentItem = row.get("current_external_item_id", String.class);
    String currentTitle = row.get("current_title", String.class);

    boolean drifted =
        currentItem == null
            || currentTitle == null
            || !snapshotItem.equals(currentItem)
            || !snapshotTitle.equals(currentTitle);

    return new VersionProductScope(
        row.get("version_id", UUID.class),
        row.get("mapping_id", UUID.class),
        row.get("listing_id", UUID.class),
        snapshotItem,
        snapshotTitle,
        row.get("listing_variant_id", UUID.class),
        row.get("external_variant_id", String.class),
        row.get("shop_code", String.class),
        currentItem,
        currentTitle,
        drifted,
        row.get("captured_at", OffsetDateTime.class),
        "平台侧当前值只读自 listing，本模块不改它；快照回答「当时叫什么」，当前值回答「现在叫什么」，"
            + "两者不一致是正常的（平台侧重新同步过，或商品已被删除）");
  }

  /** 相邻版本号：previous=true 取 version_no 更小的最大者，否则取更小的最小者。 */
  private Integer neighbourVersionNo(UUID mappingId, int versionNo, boolean previous) {
    UUID tenant = currentTenant();
    Object value =
        sql.fetchValue(
            """
            select version_no from listing_mapping_version
            where tenant_id = ? and mapping_id = ? and version_no %s ?
            order by version_no %s
            limit 1
            """
                .formatted(previous ? "<" : ">", previous ? "desc" : "asc"),
            tenant,
            mappingId,
            versionNo);
    return value == null ? null : ((Number) value).intValue();
  }

  /**
   * 本版相对上一版改了什么。
   *
   * <p>逐字段比对库里那两行原始值；{@code component_quantity} 用 {@link BigDecimal#compareTo}
   * 而不是 {@code equals}：{@code 2.0} 与 {@code 2.000000} 是同一个数量，
   * {@code equals} 会把它们报成一次"没理由的变更"。
   */
  private List<String> changedFields(UUID tenant, UUID mappingId, int previousNo, VersionRow current) {
    Record previous =
        sql.fetchOne(
            """
            select relation_type, mapping_status, internal_sku_id, component_quantity
            from listing_mapping_version
            where tenant_id = ? and mapping_id = ? and version_no = ?
            """,
            tenant,
            mappingId,
            previousNo);
    if (previous == null) return List.of();

    List<String> changed = new ArrayList<>();
    if (!java.util.Objects.equals(previous.get("relation_type", String.class), current.relationType()))
      changed.add("RELATION_TYPE");
    if (!java.util.Objects.equals(previous.get("mapping_status", String.class), current.mappingStatus()))
      changed.add("MAPPING_STATUS");
    if (!java.util.Objects.equals(previous.get("internal_sku_id", UUID.class), current.internalSkuId()))
      changed.add("INTERNAL_SKU_ID");
    BigDecimal before = previous.get("component_quantity", BigDecimal.class);
    BigDecimal after = current.componentQuantity();
    if (before == null ? after != null : before.compareTo(after) != 0) changed.add("COMPONENT_QUANTITY");
    return changed;
  }

  // ==================================================================
  // 受影响订单范围（未交付 —— 如实声明，不造数据）
  // ==================================================================

  /**
   * 按版本回溯受影响订单范围。
   *
   * <p>🔴 <b>本方法刻意不查任何订单</b>，也不返回空数组。受影响订单是 09 订单（trade）的真实数据，
   * trade 侧目前没有可供 channel 查询订单的已交付端点；AGENTS.md 禁止跨服务 SQL / 直连对方库 /
   * 共享视图。这里返回 {@code available=false} + {@code ownerModule} + {@code items=null}：
   * 「查不到」与「确实没有受影响订单」必须在类型上就分得开。
   *
   * <p>版本自身的字段照常返回（版本号与生效区间是本服务的事实），调用方拿到的是
   * 「这一版是什么、生效了哪段时间」加上「它影响了哪些订单暂时查不了」。
   */
  public VersionAffectedOrders affectedOrders(String id) {
    UUID versionId = parseId(id);
    UUID tenant = currentTenant();
    Record row =
        sql.fetchOne(
            "select version_no from listing_mapping_version where tenant_id = ? and id = ?",
            tenant,
            versionId);
    if (row == null) throw notFound();

    return new VersionAffectedOrders(
        versionId,
        row.get("version_no", Integer.class),
        false,
        OWNER_ORDERS,
        AFFECTED_ORDERS_NOTE,
        "09 订单侧在订单行产生时把本版本 id 冻结进 order_line_component，并提供"
            + "「按映射版本查询受影响订单」的授权端点；本模块的版本列表与商品范围届时可直接作为其数据源。");
  }

  // ==================================================================
  // 辅助
  // ==================================================================

  /** 总条数：显式收成 Long（与 4.1.2.1 的 count() 同款）。 */
  private long count(String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? 0L : row.get(0, Long.class);
  }

  private static VersionRow versionRow(Record r) {
    OffsetDateTime effectiveTo = r.get("effective_to", OffsetDateTime.class);
    return new VersionRow(
        r.get("id", UUID.class),
        r.get("mapping_id", UUID.class),
        r.get("version_no", Integer.class),
        r.get("relation_type", String.class),
        r.get("mapping_status", String.class),
        r.get("internal_sku_id", UUID.class),
        r.get("component_quantity", BigDecimal.class),
        r.get("effective_from", OffsetDateTime.class),
        effectiveTo,
        effectiveTo == null,
        r.get("change_reason", String.class),
        r.get("change_source", String.class),
        r.get("actor_id", UUID.class),
        r.get("listing_id", UUID.class),
        r.get("external_item_id", String.class),
        r.get("title_snapshot", String.class),
        r.get("listing_variant_id", UUID.class),
        r.get("external_variant_id", String.class),
        r.get("shop_code", String.class),
        r.get("captured_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private UUID currentTenant() {    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    return UUID.fromString(String.valueOf(raw));
  }

  private static UUID parseId(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (RuntimeException e) {
      // 与「不存在」同一条路径：不把"格式不对"暴露成一个可区分的信号
      throw notFound();
    }
  }

  private static String escape(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
  }
}
