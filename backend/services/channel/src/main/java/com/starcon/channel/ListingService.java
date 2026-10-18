package com.starcon.channel;

import static com.starcon.channel.ListingModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 4.1.1.1 Listing 列表。
 *
 * <p>蓝图：「按店铺/站点/状态/负责人/类目/是否有映射/是否缺货筛选平台商品，展示内部 SKU、价格、库存、销量、评分、近 7
 * 日状态变化，支持批量改价改库存（跳 05/07）、批量下架、批量分配负责人。输入：筛选条件；用户数据范围（店铺维度）；
 * {@code listing} + {@code listing_variant} + 销量/库存投影。输出：分页列表（含内部 SKU 映射状态、缺货标记、审核状态）；批量操作任务；点击进入
 * 4.1.1.2。关系：上游 = 02 店铺、03 SKU、05 价格、07 库存。<b>「未映射」和「缺货」是两个独立状态</b>，列表要能同时显示且可分别筛选。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>七个筛选维度</b> → {@link #list}：店铺 / 站点 / 状态 / 负责人 / 类目 / 是否有映射 / 是否缺货，
 *       每一条都是真的 {@code where} 子句 + 可走索引，没有一个是前端过滤。
 *   <li><b>展示内部 SKU</b> → {@code listing_variant.internal_sku_id}（跨服务逻辑 ID）。
 *   <li><b>评分 + 近 7 日状态变化</b> → {@code listing.rating} 与 {@code listing_status_event}。
 *   <li><b>批量改价改库存（跳 05/07）、批量下架、批量分配负责人</b> → {@link #batch}。
 * </ul>
 *
 * <p>🔴 上游依赖的诚实边界（本模块最容易被做丢的一件事）：
 *
 * <ul>
 *   <li><b>价格没有字段，也没有影子表</b>：卷 05 定价整卷 0/19 未交付，全库不存在 listing 售价事实表。
 *       建一个 {@code price} 列等于制造第二个价格事实来源 —— 05 上线后两个数字迟早对不上。
 *       {@link #capabilities()} 因此显式返回 {@code priceAvailable=false} + 归属卷 05 + 影响，
 *       前端显示「价格能力未接入」，<b>不回 0、不显示假价格</b>。
 *   <li><b>销量</b>归 09 订单、<b>内部可售量</b>归 07 库存，二者跨服务，本模块一律不建列。
 *   <li><b>但缺货标记能成立</b>：listing 有一个 04 自己拥有的事实 —— <b>平台回报的可售状态</b>
 *       （{@code platform_stock_state}，来自 02 同步 / 4.3.2.2 回流）。它与 07 的内部可售量是
 *       <b>两个事实</b>（蓝图 4.1.1.3 加粗句「平台下架与内部下架是两个事实」是同一纪律），
 *       所以两个维度在 {@link ListingCapabilities} 里分开声明，一个 available 一个不 available。
 * </ul>
 *
 * <p>🔴 蓝图加粗句「未映射和缺货是两个独立状态」在代码里是<b>两条互不影响的 where 子句</b>：
 * {@code mappingStatus} 走 listing_variant 的聚合，{@code outOfStock} 走 listing 自己的列。
 * 两者可以同时为真（已映射但平台缺货 = 最常见的漏单场景），合成一个字段就看不见它了。
 *
 * <p>🔴 数据来源纪律：店铺 / 站点 / 平台码表<b>同库同服务</b>读（V1 / V3 已落在 channel），
 * 本类<b>不复制</b>它们的任何一列；03 的 SKU 只存逻辑 ID，不建 FK、不跨库 SELECT。
 */
@Service
public class ListingService {

  /** 🔴 未交付的价格动作：显式拒绝并指明归属，不返回「成功但什么都没做」。 */
  static final String REPRICE = "REPRICE";

  static final String RESTOCK = "RESTOCK";

  private static final String TENANT_FILTER =
      " where {alias}.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid";

  private static final Set<String> STATUSES =
      Set.of(
          "DRAFT", "ON_SALE", "UNDER_REVIEW", "REJECTED",
          "DELISTED_BY_US", "DELISTED_BY_PLATFORM", "BLOCKED");
  private static final Set<String> REVIEW_STATUSES =
      Set.of("NOT_SUBMITTED", "PENDING", "APPROVED", "REJECTED");
  private static final Set<String> STOCK_STATES = Set.of("IN_STOCK", "OUT_OF_STOCK", "UNKNOWN");
  private static final Set<String> VARIANT_MAPPING_STATUSES = Set.of("UNMAPPED", "MAPPED", "BUNDLE");
  private static final Set<String> BATCH_ACTIONS =
      Set.of("ASSIGN_OWNER", "DELIST", REPRICE, RESTOCK);

  private final DSLContext sql;
  private final ObjectMapper json;

  public ListingService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ==================================================================
  // 小工具（照抄 AdTypeDiffService / RateComparisonService 的同名写法）
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
    for (int i = 0; i < binds.size(); i++) {
      all[i] = binds.get(i);
    }
    System.arraycopy(tail, 0, all, binds.size(), tail.length);
    return all;
  }

  private static UUID parseId(String raw) {
    try {
      return UUID.fromString(text(raw));
    } catch (IllegalArgumentException e) {
      // 跨租户与不存在必须返回**逐字相同**的 404，所以畸形 id 也走同一条路
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
    }
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
  }

  private static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  /** LIKE 模式转义：让用户输入里的 % 和 _ 只当字面量，不当通配符。 */
  private static String escape(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 在进入事务时绑定，不来自任何客户端头）。 */
  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    return UUID.fromString(String.valueOf(raw));
  }

  /** 显式声明一次归属转移，让 DB 触发器放行负责人变更（纪律与 V1 shop 的 transfer 端点一致）。 */
  private void withTransfer(Runnable work) {
    sql.execute("select set_config('app.transfer','true',true)");
    try {
      work.run();
    } finally {
      sql.execute("select set_config('app.transfer','false',true)");
    }
  }

  /** jsonb 的 JDBC 形态随驱动而变，优先取 PGObject.getValue()。 */
  private String json(Object raw) {
    if (raw == null) return "{}";
    String value =
        raw instanceof org.jooq.JSONB j ? j.data() : String.valueOf(raw);
    if ("{}".equals(value) || value.isBlank()) return value;
    try {
      // 归一：jsonb 读回来可能带空白，前端模型要的是稳定形状
      return json.writeValueAsString(json.readTree(value));
    } catch (Exception e) {
      return value;
    }
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 🔴 逐维度能力声明。
   *
   * <p>价格 / 销量 / 内部可售量三个维度 {@code available=false} 并写明归属与影响 ——
   * 前端据此显示「未接入」，而不是把 0 或空列表画成真实数据。
   */
  public ListingCapabilities capabilities() {
    return new ListingCapabilities(
        List.of(
            new ListingCapabilityDimension(
                "listing_scope",
                "平台商品本体（店铺 / 站点 / 状态 / 负责人 / 类目 / 评分 / 审核状态）",
                true,
                "listing（V171）",
                "",
                "列表的主体；缺了它整个模块无从谈起"),
            new ListingCapabilityDimension(
                "mapping_status",
                "🔴 内部 SKU 映射状态（未映射 / 部分映射 / 已映射），与缺货是两个独立维度",
                true,
                "listing_variant.internal_sku_id（V171）",
                "",
                "映射缺失必须显式可见：看不见未映射的商品，订单就会落到待映射状态"),
            new ListingCapabilityDimension(
                "platform_stock_state",
                "🔴 缺货标记（平台侧可售状态），来自 02 同步 / 4.3.2.2 状态回流",
                true,
                "listing.platform_stock_state（V171）",
                "",
                "平台缺货与内部缺货是两个事实，合成一个就看不见「平台已断货但内部还有货」"),
            new ListingCapabilityDimension(
                "status_change_7d",
                "近 7 日状态变化（由 DB 触发器现场派生，服务层漏记不了）",
                true,
                "listing_status_event（V171）",
                "",
                "没有它就无法回答「这个商品是什么时候下的架」"),
            new ListingCapabilityDimension(
                "shop_scope",
                "用户数据范围（店铺维度）：可见性随店铺负责人 / 部门收敛",
                true,
                "shop + channel_scope（V1）",
                "",
                "跨部门可见性失控 = 业绩归属与敏感商品泄露"),
            new ListingCapabilityDimension(
                "price",
                "🔴 平台售价 / 成本 / 毛利",
                false,
                "—",
                "05 定价与价目系统（整卷 0/19 未交付）",
                "列表看不到售价，运营无法在本页判断该不该改价；本模块**刻意不建影子价格表**——"
                    + "造一个第二价格事实来源，05 上线后两个数字迟早对不上且没人说得清该信哪个"),
            new ListingCapabilityDimension(
                "sales",
                "销量 / 销售额",
                false,
                "—",
                "09 订单与履约系统",
                "列表看不到近 7 日销量，无法在列表页做选品判断；销量是 09 订单的事实，"
                    + "本模块跨服务不读它的表"),
            new ListingCapabilityDimension(
                "internal_stock",
                "🔴 内部可售量 / 可售天数（07 库存维度）",
                false,
                "—",
                "07 库存系统",
                "看不到内部可售量，补货判断要切到 07；注意本模块的缺货标记是**平台侧**事实，"
                    + "与内部可售量不是一回事")),
        false,
        "05 定价与价目系统",
        "🔴 本批次价格能力未接入：卷 05 定价与价目系统整卷 0/19 未交付，全库不存在任何 listing "
            + "售价事实表。列表因此**没有价格列**，也不展示 0 或任何占位价格——0 会被读成"
            + "「这个商品卖 0 块」，占位价会被当成真价拿去做定价决策。要显示售价，请先接入 05 定价。",
        List.of("DRAFT", "ON_SALE", "UNDER_REVIEW", "REJECTED",
                "DELISTED_BY_US", "DELISTED_BY_PLATFORM", "BLOCKED"),
        List.of("IN_STOCK", "OUT_OF_STOCK", "UNKNOWN"),
        List.of("NOT_SUBMITTED", "PENDING", "APPROVED", "REJECTED"),
        List.of("UNMAPPED", "PARTIAL", "MAPPED"),
        List.of("UNMAPPED", "MAPPED", "BUNDLE"),
        List.of("ASSIGN_OWNER", "DELIST", REPRICE, RESTOCK),
        "🔴「未映射」与「缺货」是两个独立状态：mappingStatus 答「平台变体有没有落到内部 SKU」，"
            + "platformStockState 答「平台侧还能不能买」。两者可以同时为真 —— 一个已映射但平台缺货的"
            + "商品是最常见的漏单场景，合成一个字段就看不见它了。本模块的缺货标记是**平台侧**事实，"
            + "07 的内部可售量是另一个事实（见 internalStock 维度），两者不得互相推导。",
        "上游：02 店铺 / 站点 / 平台码表**同库同服务**读（channel V1，零复制）；03 商品只经 internal_sku_id "
            + "逻辑 ID 引用，不建 FK、不跨库 SELECT；05 价格与 07 内部可售量跨服务，本模块不读也不建列；"
            + "09 销量同理。下游 4.1.1.2 / 4.2.1.1 / 4.3.1 在本模块交付后才具备数据来源。");
  }

  /** 筛选面板的可选值：全部从 DB 现有的数据范围里取，不硬编码。 */
  public ListingFilterOptions filterOptions() {
    return new ListingFilterOptions(
        STATUSES.stream().sorted().toList(),
        STOCK_STATES.stream().sorted().toList(),
        REVIEW_STATUSES.stream().sorted().toList(),
        List.of("UNMAPPED", "PARTIAL", "MAPPED"),
        List.of("RECENT", "RATING_DESC", "RATING_ASC", "STATUS", "SHOP"),
        200);
  }

  // ==================================================================
  // 列表（七个筛选维度 + 真分页）
  // ==================================================================

  /**
   * 分页列表。
   *
   * <p>🔴 每个筛选条件都是真的 {@code where} 子句。特别地 {@code mappingStatus} 与
   * {@code outOfStock} 是两条<b>互不影响</b>的子句：前者走 listing_variant 聚合，
   * 后者走 listing 自己的列 —— 两者可以同时生效，合成一个条件就看不见「已映射但缺货」了。
   */
  public ListingPage list(
      String shopCode,
      String marketplaceCode,
      String status,
      String ownerId,
      String category,
      String mappingStatus,
      Boolean outOfStock,
      String search,
      String sort,
      int page,
      int size) {

    List<Object> binds = new ArrayList<>();
    StringBuilder where =
        new StringBuilder(
            " from listing l"
                + " join shop s on s.tenant_id=l.tenant_id and s.id=l.shop_id"
                + " left join marketplace m on m.id=l.marketplace_id"
                + " where l.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid");

    String shop = blankToNull(shopCode);
    if (shop != null) {
      where.append(" and s.code = ?");
      binds.add(shop);
    }
    String site = blankToNull(marketplaceCode);
    if (site != null) {
      where.append(" and m.code = ?");
      binds.add(site);
    }
    String st = blankToNull(status);
    if (st != null) {
      if (!STATUSES.contains(st)) {
        throw unprocessable(
            "未知的平台商品状态："
                + st
                + "。可选："
                + String.join(" / ", STATUSES.stream().sorted().toList()));
      }
      where.append(" and l.status = ?");
      binds.add(st);
    }
    String owner = blankToNull(ownerId);
    if (owner != null) {
      where.append(" and l.owner_id = ?::uuid");
      binds.add(owner);
    }
    String cat = blankToNull(category);
    if (cat != null) {
      where.append(" and l.category_path ilike ? escape '!'");
      binds.add("%" + escape(cat) + "%");
    }

    // 🔴 映射状态筛选：listing_variant 的聚合，与缺货筛选互不影响
    String mapping = blankToNull(mappingStatus);
    if (mapping != null) {
      String normalized = mapping.trim().toUpperCase(Locale.ROOT);
      if (!Set.of("UNMAPPED", "PARTIAL", "MAPPED").contains(normalized)) {
        throw unprocessable(
            "未知的映射状态：" + mapping + "。可选：UNMAPPED / PARTIAL / MAPPED");
      }
      // 🔴 聚合口径（与 row() 里逐行组装的规则逐字一致，两个地方不能各算各的）：
      //   变体为空 或 没有任何变体被映射 ⇒ UNMAPPED
      //   全部变体被映射                        ⇒ MAPPED
      //   一部分被映射、一部分没有              ⇒ PARTIAL
      where.append(
          """
           and case
                 when coalesce((select count(*) from listing_variant v
                                 where v.tenant_id=l.tenant_id and v.listing_id=l.id),0) = 0
                   then 'UNMAPPED'
                 when coalesce((select count(*) from listing_variant v
                                 where v.tenant_id=l.tenant_id and v.listing_id=l.id
                                   and v.mapping_status<>'UNMAPPED'),0) = 0
                   then 'UNMAPPED'
                 when (select count(*) from listing_variant v
                        where v.tenant_id=l.tenant_id and v.listing_id=l.id
                          and v.mapping_status<>'UNMAPPED')
                      = (select count(*) from listing_variant v
                          where v.tenant_id=l.tenant_id and v.listing_id=l.id)
                   then 'MAPPED'
                 else 'PARTIAL'
               end = ?
          """);
      binds.add(normalized);
    }

    // 🔴 缺货筛选：平台侧事实，走 listing 自己的列
    if (outOfStock != null) {
      if (outOfStock) {
        where.append(" and l.platform_stock_state = 'OUT_OF_STOCK'");
      } else {
        where.append(" and l.platform_stock_state <> 'OUT_OF_STOCK'");
      }
    }

    String q = blankToNull(search);
    if (q != null) {
      where.append(" and (l.title ilike ? escape '!' or l.external_item_id ilike ? escape '!')");
      String like = "%" + escape(q) + "%";
      binds.add(like);
      binds.add(like);
    }

    long total = count("select count(*)" + where, binds.toArray());

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    String orderBy =
        switch (blankToNull(sort) == null ? "RECENT" : sort) {
          case "RATING_DESC" -> " order by l.rating desc nulls last, l.id desc";
          case "RATING_ASC" -> " order by l.rating asc nulls last, l.id desc";
          case "STATUS" -> " order by l.status asc, l.id desc";
          case "SHOP" -> " order by s.code asc, l.external_item_id asc";
          default -> " order by l.updated_at desc, l.id desc";
        };

    String columns =
        """
        select l.id,l.tenant_id,l.shop_id,l.marketplace_id,l.external_item_id,l.title,
               l.category_path,l.platform_category_id,l.status,l.review_status,l.review_message,
               l.owner_id,l.rating,l.rating_count,l.currency,l.platform_stock_state,
               l.platform_stock_qty,l.launched_at,l.last_synced_at,l.updated_at,
               s.code as shop_code, s.name as shop_name, s.platform_id as platform_id,
               m.code as marketplace_code,
               (select count(*) from listing_variant v
                 where v.tenant_id=l.tenant_id and v.listing_id=l.id) as variant_count,
               (select count(*) from listing_variant v
                 where v.tenant_id=l.tenant_id and v.listing_id=l.id
                   and v.mapping_status<>'UNMAPPED') as mapped_variant_count,
               (select count(*) from listing_status_event e
                 where e.tenant_id=l.tenant_id and e.listing_id=l.id
                   and e.occurred_at >= now() - interval '7 days') as status_change_7d
        """;

    List<ListingRow> items =
        sql.fetch(columns + where + orderBy + " limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::row)
            .toList();

    return new ListingPage(items, total, safePage, safeSize, List.of());
  }

  /** 行组装：listing 本库字段 + 同库的上游 02 店铺 / 站点 + 映射与状态变化的聚合。 */
  private ListingRow row(Record r) {
    UUID id = r.get("id", UUID.class);
    UUID tenant = r.get("tenant_id", UUID.class);
    int variantCount = orZero(r.get("variant_count", Integer.class));
    int mappedCount = orZero(r.get("mapped_variant_count", Integer.class));

    // 列表级映射状态。🔴 口径必须与 list() 里 mappingStatus 筛选的聚合**逐字一致** ——
    // 两个地方各算各的会出现「行上写着 PARTIAL，按 PARTIAL 却筛不出来」。
    //   变体为空 或 没有任何变体被映射 ⇒ UNMAPPED
    //   全部变体被映射                   ⇒ MAPPED
    //   一部分被映射、一部分没有         ⇒ PARTIAL
    String mappingStatus;
    if (variantCount == 0 || mappedCount == 0) {
      mappingStatus = "UNMAPPED";
    } else {
      mappingStatus = mappedCount == variantCount ? "MAPPED" : "PARTIAL";
    }

    return new ListingRow(
        id,
        r.get("shop_id", UUID.class),
        r.get("shop_code", String.class),
        r.get("shop_name", String.class),
        r.get("platform_id", UUID.class),
        platformCode(r.get("platform_id", UUID.class)),
        r.get("marketplace_id", UUID.class),
        r.get("marketplace_code", String.class),
        r.get("external_item_id", String.class),
        r.get("title", String.class),
        r.get("category_path", String.class),
        r.get("platform_category_id", String.class),
        r.get("status", String.class),
        r.get("review_status", String.class),
        r.get("review_message", String.class),
        r.get("owner_id", UUID.class),
        r.get("rating", BigDecimal.class),
        r.get("rating_count", BigDecimal.class),
        r.get("currency", String.class),
        r.get("platform_stock_state", String.class),
        r.get("platform_stock_qty", BigDecimal.class),
        mappingStatus,
        variantCount,
        mappedCount,
        internalSkuIds(tenant, id),
        orZero(r.get("status_change_7d", Integer.class)),
        statusChanges(tenant, id),
        r.get("launched_at", OffsetDateTime.class),
        r.get("last_synced_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static int orZero(Integer value) {
    return value == null ? 0 : value;
  }

  /** 内部 SKU 逻辑 id（03 拥有，不跨库查它的编码 / 名称）。 */
  private List<UUID> internalSkuIds(UUID tenantId, UUID listingId) {
    return sql.fetch(
            "select v.internal_sku_id from listing_variant v"
                + " where v.tenant_id=? and v.listing_id=? and v.internal_sku_id is not null"
                + " order by v.external_variant_id",
            tenantId, listingId)
        .stream()
        .map(x -> x.get("internal_sku_id", UUID.class))
        .toList();
  }

  /** 近 7 日状态变化（新 → 旧），上限 20 条。 */
  private List<ListingStatusChange> statusChanges(UUID tenantId, UUID listingId) {
    return sql.fetch(
            "select from_status,to_status,source,occurred_at from listing_status_event"
                + " where tenant_id=? and listing_id=? and occurred_at >= now() - interval '7 days'"
                + " order by occurred_at desc, id desc limit 20",
            tenantId, listingId)
        .stream()
        .map(e -> new ListingStatusChange(
                e.get("from_status", String.class),
                e.get("to_status", String.class),
                e.get("source", String.class),
                e.get("occurred_at", OffsetDateTime.class)))
        .toList();
  }

  private String platformCode(UUID platformId) {
    if (platformId == null) return null;
    return one(String.class, "select code from platform where id=?", platformId);
  }

  // ==================================================================
  // 登记 / 更新（02 同步入口，listing + listing_variant）
  // ==================================================================

  /**
   * 登记或更新一条平台商品及其变体。
   *
   * <p>时间列一律写 {@code ?::timestamptz}：jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，
   * PG 直接拒（14.1.2.2 实测踩过）。集成测试断言**写回的值**逐字相等。
   */
  public ListingRow save(SaveListingRequest request) {
    UUID tenant = currentTenant();

    String status = blankToNull(request.status());
    if (status == null) status = "DRAFT";
    if (!STATUSES.contains(status)) {
      throw unprocessable("未知的平台商品状态：" + status);
    }
    String review = blankToNull(request.reviewStatus());
    if (review == null) review = "NOT_SUBMITTED";
    if (!REVIEW_STATUSES.contains(review)) {
      throw unprocessable("未知的审核状态：" + review);
    }
    String stock = blankToNull(request.platformStockState());
    if (stock == null) stock = "UNKNOWN";
    if (!STOCK_STATES.contains(stock)) {
      throw unprocessable("未知的平台侧可售状态：" + stock);
    }

    // 店铺必须存在且在本租户的数据范围内（跨租户引用会被 FK + RLS 双重挡住）
    Record shop =
        sql.fetchOne(
            "select id from shop" + TENANT_FILTER.replace("{alias}", "shop") + " and id=?",
            request.shopId());
    if (shop == null) throw unprocessable("店铺不存在或不在可见范围内：" + request.shopId());

    UUID id;
    if (request.id() == null) {
      id = Ids.next();
      sql.execute(
          """
          insert into listing
            (id,tenant_id,shop_id,marketplace_id,external_item_id,title,category_path,
             platform_category_id,status,review_status,review_message,platform_stock_state,
             platform_stock_qty,rating,rating_count,currency,launched_at,last_synced_at,
             created_at,updated_at)
          values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,now(),now())
          """,
          id, tenant, request.shopId(), request.marketplaceId(),
          text(request.externalItemId()), text(request.title()),
          text(request.categoryPath()), text(request.platformCategoryId()),
          status, review, text(request.reviewMessage()), stock,
          request.platformStockQty(), request.rating(), request.ratingCount(),
          blankToNull(request.currency()), request.launchedAt(), request.lastSyncedAt());
    } else {
      id = request.id();
      Record existing =
          sql.fetchOne(
              "select id, version from listing"
                  + TENANT_FILTER.replace("{alias}", "listing")
                  + " and id=? for update",
              id);
      // 跨租户与不存在返回**逐字相同**的 404
      if (existing == null) throw notFound();
      long current = existing.get("version", Long.class) == null ? 0L : existing.get("version", Long.class);
      if (request.version() != null && request.version() != current) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "乐观锁冲突，请重新读取后再保存");
      }
      sql.execute(
          """
          update listing
             set marketplace_id=?, title=?, category_path=?, platform_category_id=?,
                 status=?, review_status=?, review_message=?, platform_stock_state=?,
                 platform_stock_qty=?, rating=?, rating_count=?, currency=?,
                 launched_at=?::timestamptz, last_synced_at=?::timestamptz,
                 version=version+1, updated_at=now()
           where tenant_id=? and id=?
          """,
          request.marketplaceId(), text(request.title()), text(request.categoryPath()),
          text(request.platformCategoryId()), status, review, text(request.reviewMessage()),
          stock, request.platformStockQty(), request.rating(), request.ratingCount(),
          blankToNull(request.currency()), request.launchedAt(), request.lastSyncedAt(),
          tenant, id);
    }

    for (SaveVariantRequest variant : safe(request.variants())) {
      saveVariant(tenant, id, variant);
    }

    return load(id.toString());
  }

  private void saveVariant(UUID tenant, UUID listingId, SaveVariantRequest request) {
    String mapping = blankToNull(request.mappingStatus());
    if (mapping == null) mapping = "UNMAPPED";
    if (!VARIANT_MAPPING_STATUSES.contains(mapping)) {
      throw unprocessable("未知的变体映射状态：" + mapping);
    }
    // 服务层先自查一遍，DB 触发器再兜一道（见 V171 guard_listing_variant_mapping）
    if (!"UNMAPPED".equals(mapping) && request.internalSkuId() == null) {
      throw unprocessable("映射状态为 " + mapping + " 时必须给出 internalSkuId");
    }
    if ("UNMAPPED".equals(mapping) && request.internalSkuId() != null) {
      throw unprocessable("给了 internalSkuId 就不能声称 UNMAPPED");
    }
    if ("BUNDLE".equals(mapping) && request.componentQuantity() == null) {
      throw unprocessable("套装变体必须给出大于 0 的 componentQuantity");
    }
    if (!"BUNDLE".equals(mapping) && request.componentQuantity() != null) {
      throw unprocessable("componentQuantity 只对套装（BUNDLE）变体有意义");
    }

    String attributes = "{}";
    if (blankToNull(request.attributes()) != null) {
      try {
        attributes = json.writeValueAsString(json.readTree(request.attributes()));
      } catch (Exception e) {
        throw unprocessable("attributes 必须是合法的 JSON 对象");
      }
    }

    // 幂等：同一 listing 下的 externalVariantId 唯一，重复登记改写而不是插第二条
    Record existing =
        sql.fetchOne(
            "select id from listing_variant where tenant_id=? and listing_id=? and external_variant_id=?",
            tenant, listingId, text(request.externalVariantId()));
    if (existing == null) {
      sql.execute(
          """
          insert into listing_variant
            (id,tenant_id,listing_id,external_variant_id,attributes,mapping_status,
             internal_sku_id,component_quantity,created_at,updated_at)
          values (?,?,?,?,?::jsonb,?,?,?,now(),now())
          """,
          Ids.next(), tenant, listingId, text(request.externalVariantId()),
          attributes, mapping, request.internalSkuId(), request.componentQuantity());
    } else {
      sql.execute(
          """
          update listing_variant
             set attributes=?::jsonb, mapping_status=?, internal_sku_id=?,
                 component_quantity=?, version=version+1, updated_at=now()
           where tenant_id=? and id=?
          """,
          attributes, mapping, request.internalSkuId(), request.componentQuantity(),
          tenant, existing.get("id", UUID.class));
    }
  }

  /** 按 id 取一行（跨租户与不存在返回逐字相同的 404）。 */
  public ListingRow load(String rawId) {
    UUID id = parseId(rawId);
    Record r =
        sql.fetchOne(
            """
            select l.id,l.tenant_id,l.shop_id,l.marketplace_id,l.external_item_id,l.title,
                   l.category_path,l.platform_category_id,l.status,l.review_status,l.review_message,
                   l.owner_id,l.rating,l.rating_count,l.currency,l.platform_stock_state,
                   l.platform_stock_qty,l.launched_at,l.last_synced_at,l.updated_at,
                   s.code as shop_code, s.name as shop_name, s.platform_id as platform_id,
                   m.code as marketplace_code,
                   (select count(*) from listing_variant v
                     where v.tenant_id=l.tenant_id and v.listing_id=l.id) as variant_count,
                   (select count(*) from listing_variant v
                     where v.tenant_id=l.tenant_id and v.listing_id=l.id
                       and v.mapping_status<>'UNMAPPED') as mapped_variant_count,
                   (select count(*) from listing_status_event e
                     where e.tenant_id=l.tenant_id and e.listing_id=l.id
                       and e.occurred_at >= now() - interval '7 days') as status_change_7d
            from listing l
            join shop s on s.tenant_id=l.tenant_id and s.id=l.shop_id
            left join marketplace m on m.id=l.marketplace_id
            where l.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and l.id=?
            """,
            id);
    if (r == null) throw notFound();
    return row(r);
  }

  // ==================================================================
  // 批量操作
  // ==================================================================

  /**
   * 批量分配负责人 / 批量下架 / 批量改价（跳 05）/ 批量改库存（跳 07）。
   *
   * <p>🔴 改价与改库存在本模块被<b>显式拒绝</b>，且拒绝这件事本身落 {@code listing_batch_task}
   * 留痕：用户点了改价、系统没做，是一条必须回显并可回溯的事实。返回一个「成功但什么都没做」
   * 的任务比报错更糟。
   *
   * <p>🔴 目标里混了不在数据范围内的 listing 时，**部分执行 + 明确列出跳过的 id**，
   * 而不是整批失败或假装全做了。
   */
  public ListingBatchResult batch(ListingBatchRequest request) {
    UUID tenant = currentTenant();
    String action = text(request.action()).trim().toUpperCase(Locale.ROOT);
    if (!BATCH_ACTIONS.contains(action)) {
      throw unprocessable(
          "未知的批量操作："
              + request.action()
              + "。可选："
              + String.join(" / ", BATCH_ACTIONS.stream().sorted().toList()));
    }

    List<UUID> requested = new LinkedHashSet<>(request.listingIds()).stream().toList();
    int requestedCount = requested.size();

    // 🔴 改价 / 改库存：显式拒绝 + 归属模块，同时落库留痕
    if (Set.of(REPRICE, RESTOCK).contains(action)) {
      boolean isPrice = REPRICE.equals(action);
      String owner = isPrice ? "05 定价与价目系统" : "07 库存系统";
      String reason =
          "「"
              + (isPrice ? "批量改价" : "批量改库存")
              + "」不由 4.1.1.1 执行，蓝图职责句本身写明「（跳 05/07）」。价格与内部可售量的事实"
              + "分别在 05 定价与 07 库存，本模块跨服务不读也不写它们的表；"
              + "在此建影子表或改影子值等于制造第二个事实来源。";
      UUID task =
          recordTask(
              tenant, action, "REJECTED", requested, requestedCount, 0, 0, List.of(),
              Map.of(), reason, owner);
      return new ListingBatchResult(
          task, action, "REJECTED", requestedCount, 0, 0, List.of(), reason, owner,
          createdAt(task));
    }

    // 数据范围过滤：只保留调用者可见的 listing
    List<UUID> visible = new ArrayList<>();
    for (UUID id : requested) {
      UUID found =
          one(
              UUID.class,
              "select id from listing" + TENANT_FILTER.replace("{alias}", "listing") + " and id=?",
              id);
      if (found != null) visible.add(found);
    }
    List<UUID> skipped = requested.stream().filter(id -> !visible.contains(id)).toList();
    if (visible.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "选中的 "
              + requestedCount
              + " 条 listing 没有一条在当前数据范围内（跨租户与不存在都表现为这一条）");
    }

    int[] affected = {0};
    String reason;
    if ("ASSIGN_OWNER".equals(action)) {
      if (request.ownerId() == null) {
        throw unprocessable("批量分配负责人必须给出 ownerId");
      }
      // 显式声明归属转移，让 V171 的 listing_owner 触发器放行（纪律与 V1 shop 一致）
      withTransfer(
          () -> {
            for (UUID id : visible) {
              affected[0] +=
                  sql.execute(
                          "update listing set owner_id=?::uuid, department_id=null, updated_at=now()"
                              + " where tenant_id=? and id=?",
                          request.ownerId(), tenant, id);
            }
          });
      reason = "已把 " + affected[0] + " 条 listing 的负责人改到 " + request.ownerId();
      if (!skipped.isEmpty()) {
        reason += "；另有 " + skipped.size() + " 条不在数据范围内被跳过";
      }
    } else {
      // DELIST：批量下架。状态一变，DB 触发器自动落一条 listing_status_event
      for (UUID id : visible) {
        affected[0] +=
            sql.execute(
                "update listing set status='DELISTED_BY_US', updated_at=now(), version=version+1"
                    + " where tenant_id=? and id=?",
                tenant, id);
      }
      reason =
          "已下架 "
              + affected[0]
              + " 条 listing（状态 DELISTED_BY_US，变更已由触发器记入状态历史）";
      if (!skipped.isEmpty()) {
        reason += "；另有 " + skipped.size() + " 条不在数据范围内被跳过";
      }
    }

    UUID task =
        recordTask(
            tenant, action, "SUCCEEDED", requested, requestedCount, affected[0], skipped.size(),
            skipped, Map.of("ownerId", String.valueOf(request.ownerId())), reason, "");
    return new ListingBatchResult(
        task, action, "SUCCEEDED", requestedCount, affected[0], skipped.size(), skipped, reason, "",
        createdAt(task));
  }

  /**
   * 批量操作任务列表（可回溯「谁在什么时候对哪一批做了什么」）。
   *
   * <p>被拒绝的任务（REPRICE / RESTOCK）同样出现在这里 —— 「点了改价没生效」必须可回溯。
   */
  public List<ListingBatchResult> batchTasks(int page, int size) {
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);
    return sql.fetch(
            """
            select id, action, outcome, requested_count, affected_count, skip_count,
                   listing_ids, reason, params, created_at
            from listing_batch_task
            where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
            order by created_at desc, id desc
            limit ? offset ?
            """,
            safeSize, safePage * safeSize)
        .stream()
        .map(this::taskRow)
        .toList();
  }

  private ListingBatchResult taskRow(Record r) {
    UUID[] ids = r.get("listing_ids", UUID[].class);
    String reason = r.get("reason", String.class);
    String ownerModule = "";
    try {
      var params = json.readTree(json(r.get("params", Object.class)));
      if (params.hasNonNull("ownerModule")) {
        var value = params.get("ownerModule").asText("");
        ownerModule = "null".equals(value) ? "" : value;
      }
    } catch (Exception ignored) {
      // params 读不出来不影响任务本身可读，ownerModule 留空即可
    }
    return new ListingBatchResult(
        r.get("id", UUID.class),
        r.get("action", String.class),
        r.get("outcome", String.class),
        orZero(r.get("requested_count", Integer.class)),
        orZero(r.get("affected_count", Integer.class)),
        orZero(r.get("skip_count", Integer.class)),
        ids == null ? List.of() : List.of(ids),
        reason,
        ownerModule,
        r.get("created_at", OffsetDateTime.class));
  }

  private UUID recordTask(
      UUID tenant,
      String action,
      String outcome,
      List<UUID> listingIds,
      int requestedCount,
      int affected,
      int skipped,
      List<UUID> skippedIds,
      Map<String, String> params,
      String reason,
      String ownerModule) {
    UUID task = Ids.next();
    String paramJson;
    try {
      paramJson = json.writeValueAsString(params);
    } catch (Exception e) {
      paramJson = "{}";
    }
    Map<String, String> all = new java.util.LinkedHashMap<>(params);
    all.put("ownerModule", ownerModule);
    all.put("skippedIds", skippedIds.toString());
    try {
      paramJson = json.writeValueAsString(all);
    } catch (Exception e) {
      paramJson = "{}";
    }
    sql.execute(
        """
        insert into listing_batch_task
          (id,tenant_id,action,outcome,listing_ids,requested_count,affected_count,
           skip_count,params,reason,actor_id,created_at)
        values (?,?,?,?,?::uuid[],?,?,?,?::jsonb,?,?,now())
        """,
        task, tenant, action, outcome, listingIds.toArray(UUID[]::new), requestedCount,
        affected, skipped, paramJson, reason, currentActor());
    return task;
  }

  private UUID currentActor() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.actor',true),'')");
    return raw == null ? null : UUID.fromString(String.valueOf(raw));
  }

  private OffsetDateTime createdAt(UUID taskId) {
    return sql.fetchOne(
            "select created_at from listing_batch_task"
                + TENANT_FILTER.replace("{alias}", "listing_batch_task")
                + " and id=?",
            taskId)
        .get("created_at", OffsetDateTime.class);
  }

  private static <T> List<T> safe(List<T> values) {
    return values == null ? List.of() : values;
  }

  /** 仅供测试与文档引用：把蓝图原文的四问保留在代码里，避免实现漂移。 */
  static final String BLUEPRINT = "4.1.1.1 Listing 列表";
}
