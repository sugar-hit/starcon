package com.starcon.channel;

import static com.starcon.channel.ListingMappingGapModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 2.4.2.3 映射缺失队列。
 *
 * <p>蓝图：「**职责**：平台商品已同步但未映射到内部 SKU 的记录，等待人工建立映射或创建新 SKU。
 * **输入**：{@code listing_variant} 无映射的记录；{@code listing_mapping_version} 的错误状态。
 * **输出**：待映射清单（平台商品、变体、店铺、价格）；操作入口：映射到已有 SKU / 新建 SKU / 标记忽略。
 * **关系**：上游 = 04 刊登与内容、09 订单（缺映射订单也进此队列）；下游 = 04 映射管理。
 * <b>不按名称猜 SKU</b>；映射建立后回填受影响订单，历史订单冻结当时映射版本。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>「平台商品已同步但未映射」</b> → 队列由 <b>DB 触发器</b>维护（V191）：
 *       变体一进未映射就入队，一建立映射就出队。交给服务层就会出现「映射已建立但队列还挂着」
 *       这种极难发现的不一致（与 V176 的现状位投影同一纪律）。
 *   <li><b>「{@code listing_mapping_version} 的错误状态」</b> → {@code gap_reason=MAPPING_RELEASED}：
 *       当前生效版本是解除版（{@code mapping_status='UNMAPPED'}）时，变体退回未映射，需要**重建**
 *       而不是首次建立 —— 这两种情况对运营是两种不同的待办，不能混成一条。
 *   <li><b>三个操作入口</b> → {@link #mapToExistingSku} / {@link #createSku} / {@link #ignore}。
 *   <li><b>「映射建立后回填」</b> → 映射版本落库时由 DB 触发器把缺口回填到<b>那一版</b>；
 *       {@code mapping_version_id} <b>不可改写</b>（V191 的冻结触发器）。
 * </ul>
 *
 * <p>🔴 蓝图加粗句「<b>不按名称猜 SKU</b>」在代码里是<b>模型的缺席</b>：
 * 清单行<b>没有任何</b>「建议 SKU / 相似度 / 候选」字段 —— 那个形状一存在，
 * 赶订单的人就会去选它。候选与相似度归 4.1.2.1 的 {@code listing_mapping_candidate}
 * （那里恒带 {@code requiresConfirmation=true}），本队列只摆缺口，决策由人给。
 *
 * <p>🔴 跨服务纪律：{@code internal_sku_id} 是 <b>03 商品的逻辑 ID</b>，本类
 * <b>一条 SQL 都不碰 catalog 的库</b>、也<b>不复制 catalog 的任何一列</b>。
 * 「新建 SKU」同理 —— 03 建档需要 SPU 归属 + 编码规则 + 属性值，channel 一个都没有，
 * 所以本类只把意图<b>登记</b>下来（落库、可查、可撤），派发到 03 是<b>待接线</b>。
 */
@Service
public class ListingMappingGapService {

  /** 队列状态取值。 */
  static final String PENDING = "PENDING";

  static final String MAPPED = "MAPPED";
  static final String IGNORED = "IGNORED";

  /** 来源：平台商品同步（本地来源，已接入）。 */
  static final String SOURCE_LISTING_SYNC = "LISTING_SYNC";

  /** 来源：09 订单缺映射（**经事件接入，当前 trade 侧未推**）。 */
  static final String SOURCE_ORDER = "ORDER";

  /** 🔴 09 订单来源的归属模块（能力声明里的 ownerModule）。 */
  static final String ORDER_OWNER = "09 订单履约（trade 服务）";

  /** 🔴 03 商品的归属模块（「新建 SKU」的派发目标）。 */
  static final String CATALOG_OWNER = "03 商品主数据（catalog 服务）";

  /** 🔴 05 定价的归属模块（价格的归属）。 */
  static final String PRICING_OWNER = "05 定价与价目系统";

  static final List<String> SORTS = List.of("RECENT", "OLDEST", "PRICE", "SHOP");

  private static final String TENANT_FILTER =
      " where {alias}.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 队列行的查询骨架：列表与详情共用。 */
  private static final String GAP_FROM =
      """
      from listing_mapping_gap g
      join listing l on l.tenant_id=g.tenant_id and l.id=g.listing_id
      join shop s on s.tenant_id=l.tenant_id and s.id=l.shop_id
      join platform p on p.id=s.platform_id
      left join listing_mapping_version mv on mv.tenant_id=g.tenant_id and mv.id=g.mapping_version_id
      """;

  private static final String GAP_COLUMNS =
      """
      select g.id,g.listing_id,g.listing_variant_id,g.source,g.gap_reason,g.status,g.decision,
             g.shop_code,s.name as shop_name,p.code as platform_code,
             g.external_item_id,g.title_snapshot,g.external_variant_id,g.attributes_snapshot,
             g.price,g.currency,
             g.internal_sku_id,g.mapping_id,g.mapping_version_id,mv.effective_from as mapped_effective_from,
             g.decided_by,g.decided_at,g.decision_note,g.ignore_reason,g.backfilled_at,g.order_ref,
             g.created_at,g.updated_at
      """;

  private final DSLContext sql;
  private final ObjectMapper json;
  private final ListingMappingService mappings;

  public ListingMappingGapService(
      DSLContext sql, ObjectMapper json, ListingMappingService mappings) {
    this.sql = sql;
    this.json = json;
    this.mappings = mappings;
  }

  // ==================================================================
  // 小工具（照抄 ListingMappingService 的同名写法）
  // ==================================================================

  private <T> T one(Class<T> type, String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, type);
  }

  private long count(String query, Object... binds) {
    Long v = one(Long.class, query, binds);
    return v == null ? 0L : v;
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(String value) {
    String trimmed = text(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static Object[] concat(List<Object> binds, Object... tail) {
    Object[] all = new Object[binds.size() + tail.length];
    for (int i = 0; i < binds.size(); i++) all[i] = binds.get(i);
    System.arraycopy(tail, 0, all, binds.size(), tail.length);
    return all;
  }

  private static String escape(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    return UUID.fromString(String.valueOf(raw));
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private String jsonText(Object raw) {
    if (raw == null) return "{}";
    String value = raw instanceof org.jooq.JSONB j ? j.data() : String.valueOf(raw);
    if (value.isBlank()) return value;
    try {
      return json.writeValueAsString(json.readTree(value));
    } catch (Exception e) {
      return value;
    }
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 🔴 逐维度能力声明：已交付的段 available=true，<b>没交付的段显式 false + ownerModule + impact</b>。
   *
   * <p>三条边界在本类里的落点：
   *
   * <ul>
   *   <li>{@code order_source} = false —— 09 订单在 trade 服务，本批次 trade 侧<b>没有推</b>
   *       这条事件，所以订单来源<b>未接线</b>（接入点已建，但没有生产者）。
   *   <li>{@code order_backfill} = false —— 映射建立后回填<b>受影响订单</b>需要 09 订单的真实来源；
   *       本模块只回填<b>当前待映射记录</b>，<b>不编造受影响订单条数</b>。
   *   <li>{@code price} = false —— 05 定价整卷未交付，channel 侧没有平台售价事实，
   *       价格位显示「未接入（归 05 定价）」，<b>不显示 0、不显示假价格</b>。
   * </ul>
   */
  public MappingGapCapabilities capabilities() {
    return new MappingGapCapabilities(
        List.of(
            new MappingGapCapabilityDimension(
                "gap_list",
                "待映射清单（平台商品、变体、店铺、价格位）",
                true,
                "listing_mapping_gap（V191）+ listing_variant（V171，同库同服务）",
                "",
                "没有它，运营看不到哪些平台商品还没有内部 SKU"),
            new MappingGapCapabilityDimension(
                "gap_filters",
                "筛选维度：店铺 / 平台 / 变体关键词 / 价格区间 / 状态 / 来源",
                true,
                "listing_mapping_gap 索引（V191）",
                "",
                "变体一多就无法定位待办"),
            new MappingGapCapabilityDimension(
                "map_to_existing_sku",
                "🔴 操作入口一：映射到已有 SKU（人给 internal_sku_id）",
                true,
                "listing_mapping / listing_mapping_version（V176，改用 4.1.2.1 的同一条写入路径）",
                "",
                "没有它，队列只能看不能处理"),
            new MappingGapCapabilityDimension(
                "create_sku",
                "🔴 操作入口二：新建 SKU（**意图登记**；派发到 03 未接线）",
                true,
                "listing_mapping_gap_sku_request（V191）",
                CATALOG_OWNER,
                "**动作是真的**（落库、可查、可撤），但 03 侧建档尚无接线 ⇒ 请求停在 PENDING；"
                    + "本模块**不假装** SKU 已创建"),
            new MappingGapCapabilityDimension(
                "ignore",
                "🔴 操作入口三：标记忽略（必填原因）",
                true,
                "listing_mapping_gap（V191）",
                "",
                "没有它，同一批不需要映射的商品会反复出现在待办里"),
            new MappingGapCapabilityDimension(
                "no_name_guessing",
                "🔴 不按名称猜 SKU：队列只呈现待人工决策，**不提供任何自动匹配建议**",
                true,
                "listing_mapping_gap（V191）—— 模型层没有「建议 SKU / 相似度」字段",
                "",
                "任何自动匹配都违反蓝图加粗句；候选与相似度归 4.1.2.1 且恒需人工确认"),
            new MappingGapCapabilityDimension(
                "backfill_current_gap",
                "映射建立后回填**当前**待映射记录（回填目标 = 当时那一版，不可改写）",
                true,
                "listing_mapping_gap.mapping_version_id（V191 冻结触发器）+ listing_mapping_version（V176）",
                "",
                "没有它，队列会一直挂着已经解决的缺口"),
            new MappingGapCapabilityDimension(
                "order_source",
                "🔴 上游：09 订单缺映射进此队列（**经事件接入**）",
                false,
                "—",
                ORDER_OWNER,
                "**当前 trade 侧没有推这条事件** ⇒ 订单来源**未接线**（接入点已建："
                    + "source='ORDER' + order_ref + 事件入口端点，但没有生产者）；"
                    + "本模块以**已接入的本地来源**建模（listing_variant 无映射 + "
                    + "listing_mapping_version 解除态）"),
            new MappingGapCapabilityDimension(
                "order_backfill",
                "🔴 映射建立后回填**受影响订单**",
                false,
                "—",
                ORDER_OWNER,
                "受影响订单范围需要 09 订单的真实来源，本仓尚无 ⇒ 本模块**只回填当前待映射记录**，"
                    + "**不返回订单条数**（返回 0 会被读成「没有订单受影响」，而真相是「订单还没接进来」）"),
            new MappingGapCapabilityDimension(
                "price",
                "🔴 价格位（平台侧售价快照）",
                false,
                "listing_mapping_gap.price（V191，numeric(19,4)）",
                PRICING_OWNER,
                "05 定价整卷未交付，channel 侧**不存在任何「平台 listing 售价」事实表** ⇒ "
                    + "平台商品来源的队列行没有价格快照；价格位显示「未接入（归 05 定价）」，"
                    + "**不显示 0、不显示假价格**；价格区间筛选因此只覆盖带快照的行"),
            new MappingGapCapabilityDimension(
                "order_source_booking",
                "🔴 订单来源的事件接入点（事件入口端点）",
                true,
                "POST /channel/mapping-gaps/orders（V191 接入点）",
                ORDER_OWNER,
                "端点与字段都是真的，事件来了就能直接进队列；**当前没有生产者**（见 order_source）")),
        List.of(PENDING, MAPPED, IGNORED),
        List.of("NONE", MAPPED, "CREATE_SKU", IGNORED),
        List.of("MAPPING_RELEASED", "ORDER_UNMAPPED", "VARIANT_UNMAPPED"),
        List.of(SOURCE_LISTING_SYNC, SOURCE_ORDER),
        false,
        "🔴 本批次 channel 侧没有任何「平台 listing 售价」事实表（05 定价整卷未交付，"
            + "V171 第二节已论证：建一个 price 影子列等于制造第二个价格事实来源）⇒ "
            + "**平台商品来源的队列行没有价格快照**。价格位对有快照的行显示真实数字、"
            + "对没有快照的行显示「未接入（归 05 定价）」，**不显示 0、不显示假价格**。",
        PRICING_OWNER,
        false,
        "🔴 本批次未交付段：① 「09 订单缺映射进此队列」—— 事件接入点已建（source='ORDER' + "
            + "order_ref + 事件入口端点），但**当前 trade 侧没有推这条事件**，订单来源**未接线**；"
            + "本模块以**已接入的本地来源**建模（listing_variant 无映射 + listing_mapping_version "
            + "解除态）。② 「映射建立后回填**受影响订单**」—— 需要 09 订单的真实来源，"
            + "本仓尚无 ⇒ 本模块**只回填当前待映射记录**，**不返回订单条数**（返回 0 会被读成"
            + "「没有订单受影响」）。③ 「价格」—— 归 05 定价，平台商品来源无价格快照，"
            + "价格位显式「未接入」。④ 「新建 SKU」的**派发**—— 03 建档需要 SPU 归属 + 编码规则 + "
            + "属性值，channel 一个都没有 ⇒ 本模块只**登记意图**，请求停在 PENDING，"
            + "**不假装 SKU 已创建**。",
        "上游：4.1.1.1 的 listing / listing_variant（channel V171，**同库同服务**读，零复制）+ "
            + "4.1.2.1 的 listing_mapping / listing_mapping_version（V176，**同库同服务**读）。"
            + "03 商品的 SKU **跨服务**：只存逻辑 ID（不建 FK、不复制 catalog 任何一列），"
            + "「新建 SKU」的派发走 03 授权 API/event（**待接线**）。下游：4.1.2.1 映射管理"
            + "（本模块的「映射到已有 SKU」**直接复用**它的写入路径，版本历史因此是同一条）；"
            + "09 订单（**待接线**）。");
  }

  /** 筛选面板词表（不硬编码在前端）。 */
  public MappingGapFilterOptions filterOptions() {
    List<MappingGapShopOption> shops =
        sql.fetch(
                """
                select distinct s.code as shop_code, s.name as shop_name, p.code as platform_code
                from listing_mapping_gap g
                join listing l on l.tenant_id=g.tenant_id and l.id=g.listing_id
                join shop s on s.tenant_id=l.tenant_id and s.id=l.shop_id
                join platform p on p.id=s.platform_id
                where g.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
                order by s.code
                """)
            .stream()
            .map(
                r ->
                    new MappingGapShopOption(
                        r.get("shop_code", String.class),
                        r.get("shop_name", String.class),
                        r.get("platform_code", String.class)))
            .toList();
    List<String> platformCodes =
        sql.fetch(
                """
                select distinct p.code
                from listing_mapping_gap g
                join listing l on l.tenant_id=g.tenant_id and l.id=g.listing_id
                join shop s on s.tenant_id=l.tenant_id and s.id=l.shop_id
                join platform p on p.id=s.platform_id
                where g.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
                order by p.code
                """)
            .stream()
            .map(r -> r.get(0, String.class))
            .toList();
    return new MappingGapFilterOptions(
        shops,
        platformCodes,
        List.of(PENDING, MAPPED, IGNORED),
        List.of("NONE", MAPPED, "CREATE_SKU", IGNORED),
        List.of("MAPPING_RELEASED", "ORDER_UNMAPPED", "VARIANT_UNMAPPED"),
        List.of(SOURCE_LISTING_SYNC, SOURCE_ORDER),
        SORTS);
  }

  // ==================================================================
  // 待映射清单
  // ==================================================================

  /**
   * 待映射清单（真分页 + 六个筛选维度）。
   *
   * <p>🔴 价格区间是**真 SQL**（{@code price >= ?} / {@code price <= ?}），不是摆设：
   * 它精确地筛「有平台价快照且落在区间内」的行。平台商品来源当前没有价格快照
   * （05 定价未交付），所以这个筛选当前只覆盖订单来源的行 —— 这一点在
   * {@link #capabilities()} 与页面上都写明，不让人以为「筛完没结果 = 没有缺口」。
   */
  public MappingGapPage list(
      String shopCode,
      String platformCode,
      String search,
      String status,
      String source,
      String gapReason,
      BigDecimal minPrice,
      BigDecimal maxPrice,
      String sort,
      int page,
      int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(GAP_FROM);
    where.append(
        " where g.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid");

    String shop = blankToNull(shopCode);
    if (shop != null) {
      where.append(" and g.shop_code = ?");
      binds.add(shop);
    }
    String platform = blankToNull(platformCode);
    if (platform != null) {
      where.append(" and p.code = ?");
      binds.add(platform);
    }
    String q = blankToNull(search);
    if (q != null) {
      where.append(
          " and (g.title_snapshot ilike ? escape '!'"
              + " or g.external_item_id ilike ? escape '!'"
              + " or g.external_variant_id ilike ? escape '!'"
              + " or g.shop_code ilike ? escape '!'"
              + " or g.order_ref ilike ? escape '!')");
      String like = "%" + escape(q) + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    String st = blankToNull(status);
    if (st != null) {
      where.append(" and g.status = ?");
      binds.add(st);
    }
    String src = blankToNull(source);
    if (src != null) {
      where.append(" and g.source = ?");
      binds.add(src);
    }
    String reason = blankToNull(gapReason);
    if (reason != null) {
      where.append(" and g.gap_reason = ?");
      binds.add(reason);
    }
    if (minPrice != null) {
      where.append(" and g.price >= ?");
      binds.add(minPrice);
    }
    if (maxPrice != null) {
      where.append(" and g.price <= ?");
      binds.add(maxPrice);
    }

    long total = count("select count(*)" + where, binds.toArray());
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    List<MappingGapRow> items =
        sql.fetch(
                GAP_COLUMNS
                    + where
                    + " order by "
                    + orderClause(sort)
                    + " limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::gapRow)
            .toList();

    int pendingInPage = (int) items.stream().filter(r -> PENDING.equals(r.status())).count();
    int priceSnapshotInPage = (int) items.stream().filter(r -> r.price() != null).count();
    return new MappingGapPage(items, total, safePage, safeSize, pendingInPage, priceSnapshotInPage);
  }

  private static String orderClause(String sort) {
    String key = text(sort).toUpperCase(Locale.ROOT);
    return switch (key) {
      case "OLDEST" -> "g.created_at asc, g.id asc";
      case "PRICE" -> "g.price desc nulls last, g.created_at desc, g.id desc";
      case "SHOP" -> "g.shop_code asc, g.created_at desc, g.id desc";
      default -> "g.created_at desc, g.id desc";
    };
  }

  /** 队列概览：状态 / 原因 / 店铺分布 + 最老的一条 + 价格快照条数。 */
  public MappingGapSummary summary() {
    List<MappingGapCount> byStatus =
        sql.fetch(
                """
                select g.status as key, count(*)::bigint as total
                from listing_mapping_gap g
                where g.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
                group by g.status order by total desc, g.status
                """)
            .stream()
            .map(r -> new MappingGapCount(r.get("key", String.class), r.get("total", Long.class)))
            .toList();
    List<MappingGapCount> byReason =
        sql.fetch(
                """
                select g.gap_reason as key, count(*)::bigint as total
                from listing_mapping_gap g
                where g.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
                group by g.gap_reason order by total desc, g.gap_reason
                """)
            .stream()
            .map(r -> new MappingGapCount(r.get("key", String.class), r.get("total", Long.class)))
            .toList();
    List<MappingGapShopCount> byShop =
        sql.fetch(
                """
                select g.shop_code, s.name as shop_name, p.code as platform_code,
                       count(*)::bigint as total
                from listing_mapping_gap g
                join listing l on l.tenant_id=g.tenant_id and l.id=g.listing_id
                join shop s on s.tenant_id=l.tenant_id and s.id=l.shop_id
                join platform p on p.id=s.platform_id
                where g.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
                  and g.status='PENDING'
                group by g.shop_code, s.name, p.code
                order by total desc, g.shop_code
                """)
            .stream()
            .map(
                r ->
                    new MappingGapShopCount(
                        r.get("shop_code", String.class),
                        r.get("shop_name", String.class),
                        r.get("platform_code", String.class),
                        r.get("total", Long.class)))
            .toList();
    Long pendingTotal =
        one(
            Long.class,
            "select count(*)::bigint from listing_mapping_gap"
                + " where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and status='PENDING'");
    OffsetDateTime oldest =
        one(
            OffsetDateTime.class,
            "select min(created_at) from listing_mapping_gap"
                + " where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and status='PENDING'");
    Long priceSnapshotCount =
        one(
            Long.class,
            "select count(*)::bigint from listing_mapping_gap"
                + " where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and price is not null");
    return new MappingGapSummary(
        byStatus,
        byReason,
        byShop,
        pendingTotal == null ? 0L : pendingTotal,
        oldest,
        priceSnapshotCount == null ? 0L : priceSnapshotCount,
        false);
  }

  /** 缺口详情：清单行 + 建档请求 + 订单回填的如实说明。 */
  public MappingGapDetail detail(UUID id) {
    MappingGapRow gap = load(id);
    List<MappingGapSkuRequestView> requests =
        sql.fetch(
                """
                select id,requested_code,requested_name,requested_barcode,requested_base_unit,
                       status,dispatched_at,created_sku_id,last_error,created_at
                from listing_mapping_gap_sku_request
                where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
                  and gap_id = ? order by created_at desc
                """,
                id)
            .stream()
            .map(
                r ->
                    new MappingGapSkuRequestView(
                        r.get("id", UUID.class),
                        r.get("requested_code", String.class),
                        r.get("requested_name", String.class),
                        r.get("requested_barcode", String.class),
                        r.get("requested_base_unit", String.class),
                        r.get("status", String.class),
                        r.get("dispatched_at", OffsetDateTime.class),
                        r.get("created_sku_id", UUID.class),
                        r.get("last_error", String.class),
                        r.get("created_at", OffsetDateTime.class)))
            .toList();
    return new MappingGapDetail(gap, requests, orderBackfillNote());
  }

  private MappingGapRow load(UUID id) {
    Record row =
        sql.fetchOne(
            GAP_COLUMNS
                + GAP_FROM
                + " where g.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and g.id = ?",
            id);
    if (row == null) throw notFound();
    return gapRow(row);
  }

  private MappingGapRow gapRow(Record r) {
    OffsetDateTime created = r.get("created_at", OffsetDateTime.class);
    long waitingDays =
        created == null
            ? 0L
            : Math.max(0L, ChronoUnit.DAYS.between(created, OffsetDateTime.now()));
    return new MappingGapRow(
        r.get("id", UUID.class),
        r.get("listing_id", UUID.class),
        r.get("listing_variant_id", UUID.class),
        r.get("source", String.class),
        r.get("gap_reason", String.class),
        r.get("status", String.class),
        r.get("decision", String.class),
        r.get("shop_code", String.class),
        r.get("shop_name", String.class),
        r.get("platform_code", String.class),
        r.get("external_item_id", String.class),
        r.get("title_snapshot", String.class),
        r.get("external_variant_id", String.class),
        jsonText(r.get("attributes_snapshot", Object.class)),
        r.get("price", BigDecimal.class),
        r.get("currency", String.class),
        r.get("internal_sku_id", UUID.class),
        r.get("mapping_id", UUID.class),
        r.get("mapping_version_id", UUID.class),
        r.get("mapped_effective_from", OffsetDateTime.class),
        r.get("decided_by", UUID.class),
        r.get("decided_at", OffsetDateTime.class),
        r.get("decision_note", String.class),
        r.get("ignore_reason", String.class),
        r.get("backfilled_at", OffsetDateTime.class),
        r.get("order_ref", String.class),
        waitingDays,
        created,
        r.get("updated_at", OffsetDateTime.class));
  }

  // ==================================================================
  // 操作入口一：映射到已有 SKU
  // ==================================================================

  /**
   * 映射到已有 SKU。
   *
   * <p>🔴 **直接复用 4.1.2.1 的写入路径**（{@link ListingMappingService#save}）：
   * 映射行与版本历史因此是**同一条**，不会出现「队列里说映射到 A、映射管理里说是 B」。
   * 改映射生成新版本、不原地改的纪律也一并继承。
   *
   * <p>🔴 本方法**不做任何自动匹配**：{@code internalSkuId} 必须是调用方明确给出的。
   *
   * <p>🔴 回填：映射版本落库时，V191 的 DB 触发器把缺口标成 {@code MAPPED} 并回填到
   * <b>那一版</b>（{@code mapping_version_id}），此后不可改写。
   */
  public MappingGapActionResult mapToExistingSku(
      AccessService.Access actor, UUID gapId, MappingGapMapRequest request) {
    UUID tenant = currentTenant();
    Record gap =
        sql.fetchOne(
            "select id,listing_variant_id,status,decision,internal_sku_id"
                + " from listing_mapping_gap"
                + TENANT_FILTER.replace("{alias}", "listing_mapping_gap")
                + " and id=? for update",
            gapId);
    if (gap == null) throw notFound();
    String status = gap.get("status", String.class);
    if (!PENDING.equals(status)) {
      throw conflict("该缺口已处理过（" + status + "），不能重复决策；如需重做请先解除映射");
    }
    UUID variantId = gap.get("listing_variant_id", UUID.class);

    // 交给 4.1.2.1 建立映射（同一张 listing_mapping / listing_mapping_version）
    // 🔴 参数顺序照抄 SaveMappingRequest：(id, listingVariantId, relationType, internalSkuId,
    //   componentQuantity, changeReason, effectiveFrom, expectedVersionNo)
    mappings.save(
        new ListingMappingModels.SaveMappingRequest(
            null,
            variantId,
            request.relationType(),
            request.internalSkuId(),
            request.componentQuantity(),
            request.changeReason(),
            request.effectiveFrom(),
            null));

    MappingGapRow row = load(gapId);
    // 回填由 DB 触发器完成；这里兜一道断言，避免"看起来成功但没回填"
    if (row.mappingVersionId() == null) {
      throw conflict("映射已建立，但缺口回填未生效（mapping_version_id 为空），请检查映射版本是否落库");
    }
    return new MappingGapActionResult(
        row,
        "已映射到内部 SKU "
            + request.internalSkuId()
            + "，并回填到映射版本 "
            + row.mappingVersionId()
            + "（该版本是这一条缺口的**历史事实**，不可改写）。"
            + "🔴 09 订单侧的受影响订单回填**尚未接线**（trade 服务未推事件），本次不涉及订单。");
  }

  // ==================================================================
  // 操作入口二：新建 SKU
  // ==================================================================

  /**
   * 新建 SKU。
   *
   * <p>🔴 本方法**不在本服务建档**。03 的建档接口（{@code POST /catalog/products/{id}/skus}）
   * 需要 productId（SPU 归属）+ code + name + barcode + baseUnit + 属性值 —— channel
   * 一个都没有，**跨服务也不该复制**。所以这里只把意图**登记**下来（落库、可查、可撤），
   * 派发到 03 是<b>待接线</b>：请求停在 {@code PENDING}，**不假装 SKU 已创建**。
   */
  public MappingGapSkuRequestResult createSku(
      AccessService.Access actor, UUID gapId, MappingGapCreateSkuRequest request) {
    UUID tenant = currentTenant();
    Record gap =
        sql.fetchOne(
            "select id,listing_variant_id,status,decision from listing_mapping_gap"
                + TENANT_FILTER.replace("{alias}", "listing_mapping_gap")
                + " and id=? for update",
            gapId);
    if (gap == null) throw notFound();
    String status = gap.get("status", String.class);
    if (!PENDING.equals(status)) {
      throw conflict("该缺口已处理过（" + status + "），不能重复决策");
    }

    String code = text(request.requestedCode());
    String name = text(request.requestedName());
    if (code.isEmpty() && name.isEmpty()) {
      throw unprocessable("至少给出期望的 SKU 编码或名称，否则 03 侧无从建档");
    }

    UUID id = Ids.next();
    sql.execute(
        """
        insert into listing_mapping_gap_sku_request
          (id,tenant_id,gap_id,requested_code,requested_name,requested_barcode,
           requested_base_unit,status,actor_id,created_at,updated_at)
        values (?,?,?,?,?,?,?,'PENDING',?,now(),now())
        on conflict (tenant_id,gap_id) where status in ('PENDING','DISPATCHED')
        do update set requested_code=excluded.requested_code,
                      requested_name=excluded.requested_name,
                      requested_barcode=excluded.requested_barcode,
                      requested_base_unit=excluded.requested_base_unit,
                      version=listing_mapping_gap_sku_request.version+1,
                      updated_at=now()
        """,
        id,
        tenant,
        gapId,
        code,
        name,
        text(request.requestedBarcode()),
        text(request.requestedBaseUnit()),
        actor.userId());

    // 缺口本身仍留在队列里：等 03 建档 + 建立映射之后才会离队
    sql.execute(
        "update listing_mapping_gap set decision='CREATE_SKU',"
            + " decision_note=?,version=version+1,updated_at=now()"
            + " where tenant_id=? and id=? and status='PENDING'",
        text(request.note()).isEmpty() ? "已登记新建 SKU 请求" : text(request.note()),
        tenant,
        gapId);

    Record request0 =
        sql.fetchOne(
            "select id,requested_code,requested_name,requested_barcode,requested_base_unit,"
                + " status,dispatched_at,created_sku_id,last_error,created_at"
                + " from listing_mapping_gap_sku_request"
                + TENANT_FILTER.replace("{alias}", "listing_mapping_gap_sku_request")
                + " and gap_id=? order by created_at desc limit 1",
            gapId);
    MappingGapSkuRequestView view =
        new MappingGapSkuRequestView(
            request0.get("id", UUID.class),
            request0.get("requested_code", String.class),
            request0.get("requested_name", String.class),
            request0.get("requested_barcode", String.class),
            request0.get("requested_base_unit", String.class),
            request0.get("status", String.class),
            request0.get("dispatched_at", OffsetDateTime.class),
            request0.get("created_sku_id", UUID.class),
            request0.get("last_error", String.class),
            request0.get("created_at", OffsetDateTime.class));
    return new MappingGapSkuRequestResult(
        view,
        "已登记「新建 SKU」请求（落库、可查、可撤）。🔴 **当前 03 侧无接线**："
            + "catalog 的建档接口需要 SPU 归属 + 编码规则 + 属性值，channel 没有这些事实，"
            + "跨服务也不该复制 ⇒ 请求停在 PENDING，**没有创建任何 SKU**。"
            + "该缺口仍留在队列里，等 SKU 建好并建立映射后才会离队。");
  }

  // ==================================================================
  // 操作入口三：标记忽略
  // ==================================================================

  /**
   * 标记忽略。
   *
   * <p>🔴 原因必填：不写原因就没人知道这条为什么不用映射；下一次盘点时它会被当成
   * 「漏处理」重新翻出来。
   */
  public MappingGapActionResult ignore(AccessService.Access actor, UUID gapId, MappingGapIgnoreRequest request) {
    UUID tenant = currentTenant();
    Record gap =
        sql.fetchOne(
            "select id,status from listing_mapping_gap"
                + TENANT_FILTER.replace("{alias}", "listing_mapping_gap")
                + " and id=? for update",
            gapId);
    if (gap == null) throw notFound();
    String status = gap.get("status", String.class);
    if (!PENDING.equals(status)) {
      throw conflict("该缺口已处理过（" + status + "），不能重复决策");
    }
    String reason = text(request.reason());
    if (reason.isEmpty()) {
      throw unprocessable("忽略原因必填：不写原因就没人知道这条为什么不用映射");
    }
    sql.execute(
        "update listing_mapping_gap set status='IGNORED',decision='IGNORED',ignore_reason=?,"
            + " decision_note=?,decided_by=?,decided_at=now(),version=version+1,updated_at=now()"
            + " where tenant_id=? and id=? and status='PENDING'",
        reason,
        text(request.note()),
        actor.userId(),
        tenant,
        gapId);
    return new MappingGapActionResult(
        load(gapId),
        "已标记忽略（原因：" + reason + "）。该缺口离队；变体仍然没有映射，"
            + "若它后来出现在订单里，仍会以「未映射」进 09 订单的待映射状态。");
  }

  // ==================================================================
  // 订单来源的接入点（事件入口）
  // ==================================================================

  /**
   * 订单来源的**接入点**（事件入口）。
   *
   * <p>🔴 当前 trade 侧<b>没有推</b>这条事件 —— 所以本端点目前没有生产者。
   * 它是接入点本身：字段与校验都是真的，事件来了就能直接进队列。
   * 能力声明里的 {@code orderSourceWired=false} 说清"还没接"，本方法**不假装已接**。
   */
  public MappingGapOrderAccepted acceptOrderGap(MappingGapOrderRequest request) {
    UUID tenant = currentTenant();
    UUID variantId = request.listingVariantId();
    Record variant =
        sql.fetchOne(
            "select v.id,v.listing_id,v.external_variant_id,v.mapping_status"
                + " from listing_variant v"
                + " where v.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and v.id = ?::uuid",
            variantId);
    if (variant == null) {
      throw unprocessable("平台变体不存在或不在可见范围内：" + variantId);
    }
    if (!"UNMAPPED".equals(variant.get("mapping_status", String.class))) {
      throw conflict("该平台变体已建立映射，不该再进映射缺失队列");
    }
    if (request.price() != null && text(request.currency()).isEmpty()) {
      throw unprocessable("给了平台价就必须给币种（两者同生共死）");
    }
    if (request.price() == null && !text(request.currency()).isEmpty()) {
      throw unprocessable("给了币种就必须给平台价（两者同生共死）");
    }

    // 队列行：订单来源与平台商品来源**分列**（同源唯一），订单来源挂到同一个变体上
    Record existing =
        sql.fetchOne(
            "select id from listing_mapping_gap"
                + TENANT_FILTER.replace("{alias}", "listing_mapping_gap")
                + " and listing_variant_id=? and source=? and status='PENDING'",
            variantId,
            SOURCE_ORDER);
    UUID gapId;
    if (existing != null) {
      gapId = existing.get("id", UUID.class);
      sql.execute(
          "update listing_mapping_gap set order_ref=?,price=?,currency=?,"
              + " version=version+1,updated_at=now() where tenant_id=? and id=?",
          text(request.orderRef()),
          request.price(),
          blankToNull(request.currency()),
          tenant,
          gapId);
    } else {
      gapId = Ids.next();
      sql.execute(
          """
          insert into listing_mapping_gap
            (id,tenant_id,listing_id,listing_variant_id,source,gap_reason,status,decision,
             shop_code,external_item_id,title_snapshot,external_variant_id,attributes_snapshot,
             price,currency,order_ref,created_at,updated_at)
          select ?,?,l.id,v.id,?,'ORDER_UNMAPPED','PENDING','NONE',
                 coalesce(s.code,''),l.external_item_id,l.title,v.external_variant_id,v.attributes,
                 ?,?,?,now(),now()
            from listing_variant v
            join listing l on l.tenant_id=v.tenant_id and l.id=v.listing_id
            left join shop s on s.tenant_id=l.tenant_id and s.id=l.shop_id
           where v.tenant_id=? and v.id=?
          on conflict (tenant_id,listing_variant_id,source) where status='PENDING' do nothing
          """,
          gapId,
          tenant,
          SOURCE_ORDER,
          request.price(),
          blankToNull(request.currency()),
          text(request.orderRef()),
          tenant,
          variantId);
    }
    return new MappingGapOrderAccepted(
        gapId,
        "已受理订单缺映射（订单号 " + text(request.orderRef()) + "），进入待映射队列。"
            + "🔴 **当前 trade 侧没有推这条事件** —— 本端点是接入点本身（字段与校验都是真的），"
            + "但**尚未有生产者**；能力声明里 orderSourceWired=false。");
  }

  // ==================================================================
  // 说明文案
  // ==================================================================

  private String orderBackfillNote() {
    return "🔴 **09 订单侧的「受影响订单回填」尚未接线**：受影响订单范围需要 09 订单的真实来源，"
        + "本仓尚无 ⇒ 本模块只回填**当前待映射记录**（mapping_version_id 记的是当时那一版，"
        + "且不可改写），**不返回受影响订单条数** —— 返回 0 会被读成「没有订单受影响」，"
        + "而真相是「订单还没接进来」。";
  }
}
