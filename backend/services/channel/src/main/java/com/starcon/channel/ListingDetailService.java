package com.starcon.channel;

import static com.starcon.channel.ListingDetailModels.*;
import static com.starcon.channel.ListingModels.ListingStatusChange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 4.1.1.2 Listing 详情。
 *
 * <p>蓝图：「单个平台商品的全景：平台原始信息（ASIN/item_id、标题、状态、评分评论数）、内部映射、内容对照（平台当前内容 vs
 * 内部草稿内容）、价格库存差异、发布历史、平台操作记录。输入：{@code listing_id}；聚合平台快照与内部数据；用户权限。输出：左右对照视图 +
 * 编辑入口 + 差异高亮 + 回写按钮。关系：上游 = 4.1.1.1；下游 = 4.2.1.1 编辑、4.3.2.1 回写。<b>差异对照是本模块的核心价值</b>：让运营一眼看出「平台上卖的价格/库存和我系统里设的不一样」。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>平台原始信息</b> → {@link #detail} 的 {@code profile}：V171 {@code listing} + 同库上游 02 店铺 / 站点 / 平台码表，零复制。
 *   <li><b>内部映射</b> → {@code listing_variant}（V171）逐变体列出，含内部 SKU 逻辑 id。
 *   <li><b>内容对照</b> → {@code listing_content_snapshot}（V175，本模块自己的平台侧内容快照）逐字段对照；内部草稿侧 {@code
 *       internalKnown=false}。
 *   <li><b>价格库存差异</b> → {@link #diff} 的 14 行差异表（详见下）。
 *   <li><b>发布历史</b> → {@code listing_publish_record}（V175）追加写。
 *   <li><b>平台操作记录</b> → 直接读 V2 {@code platform_operation}，不复制任何一列。
 * </ul>
 *
 * 🔴 <b>本模块最容易被做丢的一件事：差异表的「不可比对」必须与「相同」分开。</b>
 *
 * <p>蓝图原话是「平台上卖的价格/库存<b>和我系统里设的不一样</b>」—— 这是一句<b>双边</b>断言。而本批次：
 *
 * <ul>
 *   <li>平台侧有：{@code platform_stock_state} / {@code platform_stock_qty}（02 同步回流的事实），以及 V169 {@code
 *       writeback_target} 里「平台上当前在售的值」的登记簿快照。
 *   <li>系统侧<b>没有</b>：卷 05 定价整卷 0/19 未交付（没有「系统里设的价格」）、07 库存未交付（没有内部可售量）、
 *       4.2.1.1 刊登编辑器未交付（没有内容草稿）。
 * </ul>
 *
 * ⇒ 售价差异与内部可售量差异<b>本批次不可计算</b>。{@link ListingDiffRow#status()} 把「只有一侧存在」（{@code
 * PLATFORM_ONLY}）与「有一侧根本没接入」（{@code UNAVAILABLE}）分成两个值，
 * 并在 {@code available=false} 时带上 {@code ownerModule} + {@code impact}。
 * <b>把缺一侧写成 SAME 比不显示更糟</b> —— 那会让运营以为「平台上卖 0 块、系统里也是 0，一致」。
 *
 * <p>🔴 <b>能</b>算出来的差异有三条，全部是本模块真算的，没有一个是编的：
 *
 * <ol>
 *   <li>平台回报可售量 vs 系统回写登记簿快照 —— 两个真实数字。这是本批次最接近蓝图原意的一条：
 *       「系统以为有 50 件，平台其实只有 3 件」。
 *   <li>平台变体 vs 内部 SKU 映射 —— 变体条数 ≠ 已映射条数即 DIFFERENT。
 *   <li>平台在售状态 vs 平台可售状态 —— {@code ON_SALE} 且 {@code OUT_OF_STOCK} 是平台自己前后不一致，漏单最常见的来源。
 * </ol>
 *
 * <p>🔴 数据来源纪律：店铺 / 站点 / 平台码表<b>同库同服务</b>读（V1），本类<b>不复制</b>它们的任何一列；03 的 SKU 只经逻辑 id 引用；
 * 05 / 07 / 09 跨服务，本类<b>不读也不建列</b>。
 */
@Service
public class ListingDetailService {

  /** 🔴 差异分组。分组只是给界面分区用，不改变任何一行的判定口径。 */
  private static final String GROUP_CONTENT = "CONTENT";

  private static final String GROUP_PRICE_STOCK = "PRICE_STOCK";
  private static final String GROUP_PLATFORM_SIDE = "PLATFORM_SIDE";
  private static final String GROUP_REGISTRY = "REGISTRY";
  private static final String GROUP_MAPPING = "MAPPING";

  /** 🔴 差异状态。UNAVAILABLE 与 SAME 是**两个不同的值**，理由见类注释。 */
  private static final String STATUS_DIFFERENT = "DIFFERENT";

  private static final String STATUS_SAME = "SAME";
  private static final String STATUS_PLATFORM_ONLY = "PLATFORM_ONLY";
  private static final String STATUS_UNAVAILABLE = "UNAVAILABLE";
  private static final String STATUS_UNKNOWN = "UNKNOWN";

  private static final String OWNER_PRICE = "05 定价与价目系统";
  private static final String OWNER_STOCK = "07 库存系统";
  private static final String OWNER_SALES = "09 订单与履约系统";
  private static final String OWNER_EDIT = "4.2.1.1 刊登编辑器";
  private static final String OWNER_WRITEBACK = "4.3.2.1 内容回写";

  private static final Set<String> SOURCES = Set.of("SYNC", "COLLECTION", "MANUAL");
  private static final Set<String> PUBLISH_ACTIONS = Set.of("PUBLISH", "CONTENT_WRITEBACK");
  private static final Set<String> PUBLISH_OUTCOMES = Set.of("SUCCEEDED", "FAILED", "REJECTED");

  private static final int MAX_PAGE_SIZE = 200;

  /** 内容对照的字段顺序（左右两列按这个顺序展示，前后端一致）。 */
  private static final List<String> CONTENT_FIELDS =
      List.of(
          "title",
          "bullet_points",
          "description",
          "images",
          "video_url",
          "a_plus_modules",
          "category_attributes",
          "compliance_statements");

  private final DSLContext sql;
  private final ObjectMapper json;

  public ListingDetailService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ==================================================================
  // 小工具（照抄 ListingService 的同名写法，两个类不共享私有方法）
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

  /** 当前事务内的租户（由 backend/libraries/tenancy 在进入事务时绑定，不来自任何客户端头）。 */
  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(String.valueOf(raw));
  }

  /** jsonb 的 JDBC 形态随驱动而变，优先取 PGObject.getValue()。 */
  private String jsonText(Object raw) {
    if (raw == null) return "";
    String value = raw instanceof org.jooq.JSONB j ? j.data() : String.valueOf(raw);
    if ("{}".equals(value) || "[]".equals(value) || value.isBlank()) return value;
    try {
      // 归一：jsonb 读回来可能带空白，界面要的是稳定形状
      return json.writeValueAsString(json.readTree(value));
    } catch (Exception e) {
      return value;
    }
  }

  /** 空 jsonb 容器：数组给 []，对象给 {}（与 V175 的 CHECK 对齐）。 */
  private static String emptyContainer(String kind) {
    return "array".equals(kind) ? "[]" : "{}";
  }

  /**
   * 数量的展示形态。
   *
   * <p>🔴 只做<b>无损</b>的尾随零归一（{@code stripTrailingZeros}）：{@code numeric(19,6)} 的 3 存成
   * {@code 3.000000}，界面显示成「3.000000」会让「平台 3 件 / 登记 3 件」看着像两个不同格式的值。
   * 归一不改变数值（{@code 3.000000 == 3}），也不把 null 变成 0 —— null 仍然是「没有值」。
   */
  private static String render(BigDecimal value) {
    if (value == null) return null;
    BigDecimal stripped = value.stripTrailingZeros();
    return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
  }

  /** jsonb 读回来之后，数组 / 对象是否「有内容」。空数组不算有值 —— 那与「平台没这个字段」是一回事。 */
  private boolean hasContent(String rawJson) {
    String value = jsonText(rawJson);
    if (value.isBlank()) return false;
    try {
      JsonNode node = json.readTree(value);
      if (node == null || node.isNull()) return false;
      return node.isArray() || node.isObject() ? node.size() > 0 : !node.asText("").isBlank();
    } catch (Exception e) {
      return !value.isBlank();
    }
  }

  /** 把平台侧的结构化字段渲染成可读文本：数组取元素、对象取键值、其余原样。 */
  private String readable(String rawJson) {
    String value = jsonText(rawJson);
    if (value.isBlank()) return "";
    try {
      JsonNode node = json.readTree(value);
      if (node == null || node.isNull()) return "";
      if (node.isArray()) {
        List<String> parts = new ArrayList<>();
        node.forEach(
            item -> {
              String rendered = item.isValueNode() ? item.asText() : item.toString();
              parts.add(rendered);
            });
        return String.join("；", parts);
      }
      if (node.isObject()) {
        List<String> parts = new ArrayList<>();
        node.fields()
            .forEachRemaining(
                entry -> parts.add(entry.getKey() + "=" + entry.getValue().asText("")));
        return String.join("；", parts);
      }
      return node.asText("");
    } catch (Exception e) {
      return value;
    }
  }

  /**
   * 校验并归一请求里的 JSON 字段。
   *
   * <p>校验在服务层做（而不是让 DB 的 CHECK 报一个看不懂的类型错），但 DB 层的 CHECK 仍然保留 ——
   * 绕过服务层直接写 SQL 时它才拦得住。
   */
  private String jsonOfKind(String raw, String kind, String label) {
    String value = blankToNull(raw);
    if (value == null) return emptyContainer(kind);
    JsonNode node;
    try {
      node = json.readTree(value);
    } catch (Exception e) {
      throw unprocessable(label + " 必须是合法的 JSON");
    }
    if (node == null) return emptyContainer(kind);
    boolean ok = "array".equals(kind) ? node.isArray() : node.isObject();
    if (!ok) {
      throw unprocessable(
          label + " 必须是 JSON " + ("array".equals(kind) ? "数组" : "对象") + "，收到的是 " + node.getNodeType());
    }
    try {
      return json.writeValueAsString(node);
    } catch (Exception e) {
      throw unprocessable(label + " 不是可序列化的 JSON");
    }
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 🔴 逐维度能力声明。
   *
   * <p>价格 / 销量 / 内部可售量 / 内容草稿四个维度 {@code available=false} 并写明归属与影响 ——
   * 前端据此显示「未接入」，<b>不回 0、不显示假草稿</b>。
   */
  public ListingDetailCapabilities capabilities() {
    return new ListingDetailCapabilities(
        List.of(
            new ListingDetailCapabilityDimension(
                "platform_profile",
                "平台原始信息（ASIN / item_id、标题、状态、评分评论数、审核状态）",
                true,
                "listing + shop / marketplace / platform（V171 + V1，同库同服务）",
                "",
                "缺了它整个详情页无从谈起"),
            new ListingDetailCapabilityDimension(
                "internal_mapping",
                "🔴 内部映射（平台变体 → 内部 SKU 逻辑 id）与未映射 / 部分映射 / 已映射",
                true,
                "listing_variant.internal_sku_id（V171）",
                "",
                "看不见未映射的商品，订单就会落到待映射状态"),
            new ListingDetailCapabilityDimension(
                "platform_content",
                "🔴 平台当前内容快照（标题 / 要点 / 描述 / 图片 / 视频 / A+ / 类目属性 / 合规声明）",
                true,
                "listing_content_snapshot（V175）",
                "",
                "内容对照的左列；没有它就只剩 listing.title 一个字段，撑不起逐字段对照"),
            new ListingDetailCapabilityDimension(
                "internal_content_draft",
                "🔴 内部草稿内容（按平台 + 站点 + 语言保存的内容草稿）",
                false,
                "—",
                OWNER_EDIT,
                "内容对照的右列本批次没有值 ⇒ 差异表的 CONTENT 分组全部 UNAVAILABLE，"
                    + "运营能看见平台上写了什么，看不见自己系统里打算写什么。**不预建草稿表**——"
                    + "一张没有写入方的空表会被当成「草稿系统已交付」，比明写未接入危险得多"),
            new ListingDetailCapabilityDimension(
                "platform_stock_state",
                "🔴 平台侧可售状态与可售量（缺货标记的来源）",
                true,
                "listing.platform_stock_state / platform_stock_qty（V171）",
                "",
                "平台缺货与内部缺货是两个事实，合成一个就看不见「平台已断货但内部还有货」"),
            new ListingDetailCapabilityDimension(
                "registry_snapshot",
                "回写登记簿快照（平台上当前在售的价 / 量的登记值，2.5.2.1 交付）",
                true,
                "writeback_target.current_price / current_stock（V169）",
                "",
                "本批次**唯一**能拿来算库存差异的两个真实数字之间的落差"),
            new ListingDetailCapabilityDimension(
                "publish_history",
                "发布历史（发的是哪份内容、平台回了什么、失败原因）",
                true,
                "listing_publish_record（V175）",
                "",
                "没有它就无法回答「上次发出去的到底是哪一版内容、为什么被拒」"),
            new ListingDetailCapabilityDimension(
                "platform_operations",
                "平台操作记录（发往平台的写操作及其回执）",
                true,
                "platform_operation（V2，同库同服务，零复制）",
                "",
                "平台申诉时「我们什么时候发的、平台回了什么」要靠它核验"),
            new ListingDetailCapabilityDimension(
                "status_history",
                "状态变更轨迹（来源 MANUAL / PLATFORM）",
                true,
                "listing_status_event（V171，DB 触发器现场派生）",
                "",
                "与发布历史不是一回事：状态变了不等于发过一次布"),
            new ListingDetailCapabilityDimension(
                "price",
                "🔴 平台售价 / 成本 / 毛利",
                false,
                "—",
                OWNER_PRICE,
                "**售价差异本批次不可计算**：卷 05 定价与价目系统整卷 0/19 未交付，全库不存在「系统里设的售价」事实。"
                    + "回写登记簿里有平台上当前在售价的**快照**，但那是登记簿的当前值、不是定价系统的结论，"
                    + "拿它当售价就是制造第二个价格事实来源"),
            new ListingDetailCapabilityDimension(
                "internal_stock",
                "🔴 内部可售量 / 可售天数（07 库存维度）",
                false,
                "—",
                OWNER_STOCK,
                "**库存差异本批次不可计算**：07 库存系统未交付，系统侧可售量查不到。"
                    + "本模块能算的是「平台回报可售量 vs 系统回写登记簿快照」，那是两个真实数字之间的落差，"
                    + "**不等于**蓝图说的内部可售量"),
            new ListingDetailCapabilityDimension(
                "sales",
                "销量 / 销售额",
                false,
                "—",
                OWNER_SALES,
                "详情页看不到近 7 日销量，无法在这一页做选品判断；销量是 09 订单的事实，本模块跨服务不读它的表")),
        false,
        OWNER_PRICE,
        "🔴 本批次价格能力未接入：卷 05 定价与价目系统整卷 0/19 未交付，全库不存在任何 listing 售价事实表。"
            + "详情页因此**没有售价字段**，也不展示 0 或任何占位价格 —— 0 会被读成「这个商品卖 0 块」。"
            + "要显示售价与售价差异，请先接入 05 定价。",
        false,
        OWNER_STOCK,
        "🔴 内部可售量未接入（归 07 库存）。本模块的缺货标记是**平台侧**事实，与内部可售量是两个事实，"
            + "界面上分开显示、分行标注，不得合成一个「库存」字段。",
        false,
        OWNER_EDIT,
        "🔴 内容草稿未接入（归 4.2.1.1 刊登编辑器）。内容对照的左列（平台当前内容）有值，"
            + "右列（内部草稿内容）整列标记为未接入 —— 不显示占位草稿、不显示「无差异」。",
        false,
        OWNER_SALES,
        "🔴 销量未接入（归 " + OWNER_SALES + "）。详情页看不到近 7 日销量，无法在这一页做选品判断；"
            + "销量是订单的事实，本模块跨服务不读它的表，也不复制任何一列。",
        CONTENT_FIELDS,
        List.of(GROUP_CONTENT, GROUP_PRICE_STOCK, GROUP_PLATFORM_SIDE, GROUP_REGISTRY, GROUP_MAPPING),
        false,
        OWNER_EDIT,
        "🔴 编辑入口本批次不可用：内容草稿与刊登编辑器归 4.2.1.1，尚未交付。"
            + "按钮禁用并写明归属 —— 一个点了没反应的「编辑」按钮比没有按钮更糟。",
        false,
        OWNER_WRITEBACK,
        "🔴 内容回写按钮本批次不可用：4.3.2.1 内容回写尚未交付，且它**按蓝图写明不触碰价格与库存**"
            + "（改价改库存归 05 / 07）。本模块能做的只是如实显示该商品在 2.5.2.1 回写登记簿里的登记状态。",
        "🔴 平台操作记录来自 platform_operation（V2）按 shop_id + payload 的 targetType / externalRef 精确归属。"
            + "**直接单条派发（不经 writeback 目标）的操作不带这两个键，无法归属到具体商品**，本模块不猜："
            + "这类记录属于店铺级事实，请在 2.5.1.1 的任务清单里看。",
        "上游：4.1.1.1 的 listing / listing_variant / listing_status_event（V171，零 ALTER）+ 同库 02 店铺 / 站点 / 平台码表（V1）"
            + " + 2.5.1.1 platform_operation（V2）+ 2.5.2.1 writeback_target（V169），全部同库同服务直读。"
            + "跨服务的 03 商品（只经逻辑 id 引用）、05 定价、07 库存、09 订单、4.2.1.1 草稿一律不读不建列。"
            + "下游：4.2.1.1 编辑、4.3.2.1 回写在本模块交付后才具备数据来源。");
  }

  // ==================================================================
  // 平台原始信息（V171 + 同库上游码表）
  // ==================================================================

  private static final String PROFILE_SQL =
      """
      select l.id,l.tenant_id,l.shop_id,l.marketplace_id,l.external_item_id,l.title,
             l.category_path,l.platform_category_id,l.status,l.review_status,l.review_message,
             l.owner_id,l.rating,l.rating_count,l.currency,l.platform_stock_state,
             l.platform_stock_qty,l.launched_at,l.last_synced_at,l.updated_at,
             s.code as shop_code, s.name as shop_name, s.platform_id as platform_id,
             m.code as marketplace_code
      from listing l
      join shop s on s.tenant_id=l.tenant_id and s.id=l.shop_id
      left join marketplace m on m.id=l.marketplace_id
      where l.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
        and l.id=?
      """;

  private ListingPlatformProfile profile(UUID listingId) {
    Record r = sql.fetchOne(PROFILE_SQL, listingId);
    // 跨租户与不存在返回**逐字相同**的 404
    if (r == null) throw notFound();
    UUID platformId = r.get("platform_id", UUID.class);
    return new ListingPlatformProfile(
        r.get("id", UUID.class),
        r.get("shop_id", UUID.class),
        r.get("shop_code", String.class),
        r.get("shop_name", String.class),
        platformId,
        platformCode(platformId),
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
        r.get("launched_at", OffsetDateTime.class),
        r.get("last_synced_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private String platformCode(UUID platformId) {
    if (platformId == null) return null;
    return one(String.class, "select code from platform where id=?", platformId);
  }

  // ==================================================================
  // 内部映射（V171 listing_variant）
  // ==================================================================

  private List<ListingVariantMapping> variants(UUID listingId) {
    return sql.fetch(
            """
            select id, external_variant_id, mapping_status, internal_sku_id,
                   component_quantity, attributes, updated_at
            from listing_variant
            where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and listing_id=?
            order by external_variant_id, id
            """,
            listingId)
        .stream()
        .map(
            r ->
                new ListingVariantMapping(
                    r.get("id", UUID.class),
                    r.get("external_variant_id", String.class),
                    r.get("mapping_status", String.class),
                    r.get("internal_sku_id", UUID.class),
                    r.get("component_quantity", BigDecimal.class),
                    jsonText(r.get("attributes", Object.class)),
                    r.get("updated_at", OffsetDateTime.class)))
        .toList();
  }

  /**
   * 列表级映射状态。
   *
   * <p>🔴 口径必须与 4.1.1.1 {@code ListingService.row()} 的聚合<b>逐字一致</b> —— 两个地方各算各的会出现
   * 「列表里写着 PARTIAL，点进详情却是 UNMAPPED」。
   */
  private static String mappingStatus(int variantCount, int mappedCount) {
    if (variantCount == 0 || mappedCount == 0) return "UNMAPPED";
    return mappedCount == variantCount ? "MAPPED" : "PARTIAL";
  }

  // ==================================================================
  // 内容对照（V175 listing_content_snapshot）
  // ==================================================================

  private static final String SNAPSHOT_SQL =
      """
      select id, language, source, is_current, captured_at, title, bullet_points, description,
             images, video_url, a_plus_modules, category_attributes, compliance_statements
      from listing_content_snapshot
      where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
        and listing_id=? and is_current
      order by captured_at desc, id desc
      limit 1
      """;

  private Record currentSnapshot(UUID listingId) {
    return sql.fetchOne(SNAPSHOT_SQL, listingId);
  }

  private ListingContentSnapshotView snapshotView(UUID listingId, Record snapshot) {
    long history =
        snapshot == null
            ? 0
            : count(
                """
                select count(*) from listing_content_snapshot
                where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
                  and listing_id=?
                """,
                listingId);
    if (snapshot == null) return null;
    return new ListingContentSnapshotView(
        snapshot.get("id", UUID.class),
        snapshot.get("language", String.class),
        snapshot.get("source", String.class),
        Boolean.TRUE.equals(snapshot.get("is_current", Boolean.class)),
        snapshot.get("captured_at", OffsetDateTime.class),
        history);
  }

  private static String fieldLabel(String field) {
    return switch (field) {
      case "title" -> "标题";
      case "bullet_points" -> "要点";
      case "description" -> "描述";
      case "images" -> "图片集";
      case "video_url" -> "视频";
      case "a_plus_modules" -> "A+ 模块";
      case "category_attributes" -> "类目属性";
      case "compliance_statements" -> "合规声明";
      default -> field;
    };
  }

  /** 结构化字段（数组 / 对象）读出来先渲染成可读文本；标量字段原样。 */
  private String platformContentOf(Record snapshot, String field) {
    if (snapshot == null) return null;
    return switch (field) {
      case "title", "video_url" -> {
        String v = snapshot.get(field, String.class);
        yield v == null || v.isBlank() ? null : v;
      }
      case "description" -> {
        String v = snapshot.get(field, String.class);
        yield v == null || v.isBlank() ? null : v;
      }
      default -> {
        Object raw = snapshot.get(field, Object.class);
        String rendered = readable(jsonText(raw));
        yield rendered.isBlank() ? null : rendered;
      }
    };
  }

  /**
   * 内容对照。
   *
   * <p>🔴 右列（内部草稿）**恒为 {@code internalKnown=false} + {@code internalValue=null}**，因为 4.2.1.1 尚未交付。
   * 标题在「没有快照」时退回 {@code listing.title} —— 那是 02 同步写下的**平台侧**事实，不是草稿，
   * 落在左列是对的，不会把平台事实误标成内部草稿。
   */
  private List<ListingContentField> contentComparison(
      ListingPlatformProfile profile, Record snapshot) {
    List<ListingContentField> rows = new ArrayList<>();
    for (String field : CONTENT_FIELDS) {
      String platformValue = platformContentOf(snapshot, field);
      if (platformValue == null && "title".equals(field)) {
        String fromListing = blankToNull(profile.title());
        platformValue = fromListing;
      }
      rows.add(
          new ListingContentField(
              field,
              fieldLabel(field),
              "平台当前内容",
              platformValue,
              platformValue != null,
              "内部草稿内容",
              null,
              false,
              false,
              OWNER_EDIT,
              "内容草稿归 " + OWNER_EDIT + "，尚未交付：本字段只有平台侧有值，"
                  + "**无法判断内部草稿是否与平台一致**（显示成「一致」会是假结论）"));
    }
    return rows;
  }

  // ==================================================================
  // 回写登记簿（V169 writeback_target，只读）
  // ==================================================================

  /**
   * 🔴 回写登记簿状态（只读）。
   *
   * <p>本模块**不写** {@code writeback_target}：那张表的唯一写入所有者是 2.5.2.1（每张事实表只有一个写入所有者）。
   * 这里只把「平台上当前在售的值」的登记状态读出来，并把它与「售价」严格区分开。
   */
  private ListingWritebackRegistryView writebackRegistry(ListingPlatformProfile profile) {
    Record r =
        sql.fetchOne(
            """
            select id, current_stock, current_price, currency, status, blocked_reason, updated_at
            from writeback_target
            where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and shop_id=? and target_type='LISTING' and external_ref=?
            """,
            profile.shopId(), profile.externalItemId());
    if (r == null) {
      return new ListingWritebackRegistryView(
          false,
          null,
          null,
          null,
          null,
          "",
          "",
          null,
          "2.5.2.1 writeback_target（V169）",
          "该商品尚未登记为可回写目标 ⇒ 平台上当前在售的价 / 量在本系统内**没有登记值**。"
              + "未登记不是 0，也不是「平台上卖 0 块」。");
    }
    return new ListingWritebackRegistryView(
        true,
        r.get("id", UUID.class),
        r.get("current_stock", BigDecimal.class),
        r.get("current_price", BigDecimal.class),
        r.get("currency", String.class),
        r.get("status", String.class),
        r.get("blocked_reason", String.class),
        r.get("updated_at", OffsetDateTime.class),
        "2.5.2.1 writeback_target（V169）· 登记时登记的「平台上当前在售的值」",
        "⚠️ 这是**回写登记簿的当前值快照**，不是定价系统的售价结论（卷 05 未交付），"
            + "也不是内部可售量（归 07 库存）。它只参与「平台回报值 vs 登记簿值」这一条差异判定。");
  }

  // ==================================================================
  // 差异对照（本模块的核心价值）
  // ==================================================================

  /**
   * 差异表。
   *
   * <p>🔴 每一行的 {@code status} 都由本类算出，没有一行是前端猜的；「有一侧未接入」的行**照样出现在表里**
   * （{@code available=false}），而不是被隐藏 —— 隐藏未接入的行等于隐藏缺口。
   */
  public ListingDiffReport diff(String rawId) {
    UUID listingId = parseId(rawId);
    ListingPlatformProfile profile = profile(listingId);
    Record snapshot = currentSnapshot(listingId);
    ListingWritebackRegistryView registry = writebackRegistry(profile);
    List<ListingVariantMapping> variantRows = variants(listingId);

    int mappedCount = (int) variantRows.stream().filter(v -> !"UNMAPPED".equals(v.mappingStatus())).count();
    int variantCount = variantRows.size();

    List<ListingDiffRow> rows = new ArrayList<>();

    // ---- 1. CONTENT：内容对照（平台当前内容 vs 内部草稿内容）----
    for (ListingContentField field : contentComparison(profile, snapshot)) {
      rows.add(
          new ListingDiffRow(
              GROUP_CONTENT,
              "content_" + field.field(),
              field.label(),
              field.platformLabel(),
              field.platformValue(),
              field.platformKnown(),
              field.internalLabel(),
              null,
              false,
              STATUS_UNAVAILABLE,
              false,
              OWNER_EDIT,
              "内部草稿归 " + OWNER_EDIT + "，尚未交付 ⇒ 本字段**无法比对**。"
                  + "平台侧"
                  + (field.platformKnown() ? "有值" : "无值（平台没给这个字段）")
                  + "；右列恒为未接入，不是「无差异」。",
              snapshot == null
                  ? "本 listing 尚未登记过平台内容快照，左列回退到 listing.title（02 同步写下的平台侧事实）"
                  : "左列取自当前内容快照（" + snapshot.get("language", String.class) + "）"));
    }

    // ---- 2. PRICE_STOCK：售价 / 内部可售量 / 平台可售状态 ----
    // 🔴 三行全部 UNAVAILABLE：卷 05 / 07 未交付，「系统里设的值」查不到。
    //    左侧仍然如实展示平台上有的真实数字 —— 它是真事实，只是**不足以算出差异**。
    rows.add(
        new ListingDiffRow(
            GROUP_PRICE_STOCK,
            "price",
            "售价",
            "回写登记簿当前价快照",
            registry.registered() && registry.currentPrice() != null
                ? render(registry.currentPrice())
                    + (registry.currency() == null ? "" : " " + registry.currency())
                : null,
            registry.registered() && registry.currentPrice() != null,
            "内部定价（05）",
            null,
            false,
            STATUS_UNAVAILABLE,
            false,
            OWNER_PRICE,
            "🔴 售价差异本批次不可计算：卷 05 定价与价目系统整卷 0/19 未交付，没有「系统里设的售价」可与平台侧比。"
                + "左侧是 2.5.2.1 回写登记簿登记的平台上当前价快照，**它不是售价结论**，不要拿它当售价用。",
            registry.registered()
                ? "未登记为可回写目标时左侧为空 —— 那表示「本系统没有登记值」，不是「平台上卖 0 块」"
                : "回写登记簿里没有这条记录"));
    rows.add(
        new ListingDiffRow(
            GROUP_PRICE_STOCK,
            "internal_stock",
            "内部可售量",
            "平台侧可售量（02 同步 / 4.3.2.2 回流）",
            render(profile.platformStockQty()),
            profile.platformStockQty() != null,
            "内部可售量（07）",
            null,
            false,
            STATUS_UNAVAILABLE,
            false,
            OWNER_STOCK,
            "🔴 内部可售量归 " + OWNER_STOCK + "，未交付 ⇒ 本行不可比对。"
                + "注意左侧是**平台侧**可售量，与内部可售量不是一回事（蓝图 4.1.1.3：平台下架与内部下架是两个事实）。",
            profile.platformStockState() == null ? "" : "平台侧可售状态：" + profile.platformStockState()));
    rows.add(
        new ListingDiffRow(
            GROUP_PRICE_STOCK,
            "platform_stock_state",
            "平台可售状态",
            "平台回报的可售状态",
            profile.platformStockState(),
            profile.platformStockState() != null,
            "内部应售状态（07）",
            null,
            false,
            STATUS_UNAVAILABLE,
            false,
            OWNER_STOCK,
            "内部侧缺的是「我系统里认为它该不该有货」，归 " + OWNER_STOCK
                + "。平台侧状态本身是 04 自己拥有的事实，与 07 的内部可售量不得互相推导。",
            "UNKNOWN = 平台没回报，不是「有货」"));

    // ---- 3. REGISTRY：平台回报 vs 系统回写登记簿（本批次唯一能真算出落差的库存差异）----
    rows.add(registryRow(profile, registry));

    // ---- 4. PLATFORM_SIDE：平台自己的两个事实是否自相矛盾 ----
    rows.add(saleableConsistency(profile));

    // ---- 5. MAPPING：平台变体 vs 内部 SKU 映射 ----
    rows.add(mappingRow(variantRows, variantCount, mappedCount));

    int different = (int) rows.stream().filter(r -> STATUS_DIFFERENT.equals(r.status())).count();
    int same = (int) rows.stream().filter(r -> STATUS_SAME.equals(r.status())).count();
    int unavailableCount = (int) rows.stream().filter(r -> !r.available()).count();

    return new ListingDiffReport(
        listingId,
        rows,
        different,
        same,
        unavailableCount,
        "🔴 差异口径：可比对（两侧事实来源都已交付）的行给出 DIFFERENT / SAME / 只有一侧有值；"
            + "有一侧未接入的行给出 UNAVAILABLE + 归属模块，**不写成 SAME** —— "
            + "把「只有平台侧有值」显示成「一致」，会让运营以为平台上卖 0 块、系统里也是 0。"
            + "本批次可比对的只有三行：平台回报可售量 vs 回写登记簿快照、平台状态 vs 平台可售状态、平台变体 vs 内部映射；"
            + "售价与内部可售量两侧各缺一环（05 / 07 未交付），内容对照右列缺 4.2.1.1。");
  }

  /** 平台回报可售量 vs 系统回写登记簿快照：两个真实数字之间的落差。 */
  private ListingDiffRow registryRow(
      ListingPlatformProfile profile, ListingWritebackRegistryView registry) {
    BigDecimal platformQty = profile.platformStockQty();
    BigDecimal registryQty = registry.currentStock();
    boolean leftKnown = platformQty != null;
    boolean rightKnown = registryQty != null;

    String status;
    if (leftKnown && rightKnown) {
      status = platformQty.compareTo(registryQty) == 0 ? STATUS_SAME : STATUS_DIFFERENT;
    } else if (leftKnown) {
      status = STATUS_PLATFORM_ONLY;
    } else if (rightKnown) {
      status = STATUS_PLATFORM_ONLY;
    } else {
      status = STATUS_UNKNOWN;
    }

    return new ListingDiffRow(
        GROUP_REGISTRY,
        "platform_stock_vs_registry",
        "平台可售量 vs 系统登记值",
        "平台回报可售量（02 同步 / 4.3.2.2 回流）",
        leftKnown ? render(platformQty) : null,
        leftKnown,
        "回写登记簿当前库存快照（2.5.2.1）",
        rightKnown ? render(registryQty) : null,
        rightKnown,
        status,
        true,
        "",
        "",
        "🔴 本批次**唯一**能真算出落差的库存差异：两个数字都是真实的平台侧值，"
            + "但它们来自两处（同步回报 / 2.5.2.1 登记簿），落后就会不一致。"
            + "⚠️ 它**不等于**蓝图说的「内部可售量」—— 那一侧归 07 库存，未接入；本行只说明"
            + "「系统登记的数与平台回报的数对不上」，修的是回写前的登记动作。");
  }

  /** 平台商品状态 vs 平台可售状态：平台自己前后不一致是最常见的漏单来源。 */
  private ListingDiffRow saleableConsistency(ListingPlatformProfile profile) {
    String state = profile.platformStockState();
    String statusName = profile.status();
    String status;
    String note;
    if (state == null || "UNKNOWN".equals(state)) {
      status = STATUS_UNKNOWN;
      note = "平台没回报可售状态 ⇒ 无法判断平台上到底能不能买（UNKNOWN 不是「有货」）。";
    } else if ("ON_SALE".equals(statusName) && "OUT_OF_STOCK".equals(state)) {
      status = STATUS_DIFFERENT;
      note = "🔴 平台状态标着在售，平台可售状态却是缺货 —— 平台自己前后不一致，"
          + "这是漏单最常见的来源：运营在系统里看到「在售」，买家在平台上买不到。";
    } else {
      status = STATUS_SAME;
      note = "平台商品状态与平台可售状态没有互相矛盾。";
    }
    return new ListingDiffRow(
        GROUP_PLATFORM_SIDE,
        "saleable_consistency",
        "在售状态 vs 平台可售状态",
        "平台商品状态（listing.status）",
        statusName,
        statusName != null,
        "平台回报可售状态（listing.platform_stock_state）",
        state,
        state != null && !"UNKNOWN".equals(state),
        status,
        true,
        "",
        "",
        note);
  }

  /** 平台变体 vs 内部 SKU 映射：变体条数与已映射条数不等即 DIFFERENT。 */
  private ListingDiffRow mappingRow(
      List<ListingVariantMapping> variantRows, int variantCount, int mappedCount) {
    String status;
    String note;
    if (variantCount == 0) {
      status = STATUS_UNKNOWN;
      note = "该 listing 尚未登记任何平台变体 ⇒ 没有可比的映射（这不是「全部已映射」）。";
    } else if (mappedCount == variantCount) {
      status = STATUS_SAME;
      note = "全部 " + variantCount + " 个平台变体都已映射到内部 SKU。";
    } else {
      status = STATUS_DIFFERENT;
      note = "🔴 " + (variantCount - mappedCount) + " / " + variantCount
          + " 个平台变体还没有落到内部 SKU —— 订单落到这些变体上时没有内部商品可对。";
    }
    String left =
        variantCount == 0
            ? null
            : variantRows.stream().map(ListingVariantMapping::externalVariantId)
                .reduce((a, b) -> a + "、" + b)
                .orElse(null);
    String right =
        mappedCount == 0
            ? null
            : variantRows.stream()
                .filter(v -> !"UNMAPPED".equals(v.mappingStatus()))
                .map(v -> v.externalVariantId() + "→" + v.internalSkuId())
                .reduce((a, b) -> a + "、" + b)
                .orElse(null);
    return new ListingDiffRow(
        GROUP_MAPPING,
        "variant_mapping",
        "平台变体 vs 内部 SKU 映射",
        "平台变体（listing_variant）",
        left,
        variantCount > 0,
        "已映射的内部 SKU（逻辑 id）",
        right,
        mappedCount > 0,
        status,
        true,
        "",
        "",
        note);
  }

  /**
   * 内容对照（左右两列）。
   *
   * <p>🔴 右列恒为未接入：内部草稿归 4.2.1.1。本方法**不返回**「两边一样」这种结论 ——
   * 有一侧没接入时，任何「一致 / 不一致」的判断都是编的。
   */
  public ListingContentComparison content(String rawId) {
    UUID listingId = parseId(rawId);
    ListingPlatformProfile profile = profile(listingId);
    Record snapshot = currentSnapshot(listingId);
    return new ListingContentComparison(
        listingId,
        snapshotView(listingId, snapshot),
        contentComparison(profile, snapshot),
        false,
        OWNER_EDIT,
        "🔴 左列（平台当前内容）来自 listing_content_snapshot（V175），"
            + "未登记过快照时标题回退到 listing.title（02 同步写下的平台侧事实）。"
            + "右列（内部草稿内容）归 " + OWNER_EDIT + "，尚未交付 ⇒ 整列标记为未接入，"
            + "**不显示占位草稿、不显示「无差异」**：有一侧没接入时，「一致」和「不一致」都答不出来。");
  }

  // ==================================================================
  // 发布历史（V175 listing_publish_record）
  // ==================================================================

  public List<ListingPublishRecordView> publishHistory(String rawId, int page, int size) {
    UUID listingId = parseId(rawId);
    profile(listingId); // 不存在 / 不可见 → 与详情返回逐字相同的 404
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    return sql.fetch(
            """
            select id, snapshot_id, action, outcome, platform_request_id, reason, actor_id, occurred_at
            from listing_publish_record
            where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and listing_id=?
            order by occurred_at desc, id desc
            limit ? offset ?
            """,
            concat(List.of(listingId), safeSize, safePage * safeSize))
        .stream()
        .map(
            r ->
                new ListingPublishRecordView(
                    r.get("id", UUID.class),
                    r.get("snapshot_id", UUID.class),
                    r.get("action", String.class),
                    r.get("outcome", String.class),
                    r.get("platform_request_id", String.class),
                    r.get("reason", String.class),
                    r.get("actor_id", UUID.class),
                    r.get("occurred_at", OffsetDateTime.class)))
        .toList();
  }

  public long publishHistoryTotal(String rawId) {
    UUID listingId = parseId(rawId);
    profile(listingId);
    return count(
        """
        select count(*) from listing_publish_record
        where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
          and listing_id=?
        """,
        listingId);
  }

  // ==================================================================
  // 平台操作记录（V2 platform_operation，零复制）
  // ==================================================================

  /**
   * 平台操作记录。
   *
   * <p>🔴 归属口径：2.5.2.1 写 {@code platform_operation} 时把 {@code targetType} 与 {@code externalRef} 原文写进 payload，
   * 于是「本商品的操作」= shop_id 相等 + payload 的两个键命中。
   * <b>直接单条派发（不经 writeback 目标）的操作不带这两个键，无法归属到商品</b> —— 那属于店铺级事实，
   * 本模块不猜、不硬塞进来。
   */
  public List<ListingPlatformOperationView> platformOperations(
      String rawId, int page, int size) {
    UUID listingId = parseId(rawId);
    ListingPlatformProfile profile = profile(listingId);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    return sql.fetch(
            """
            select o.id,o.operation,o.status,o.verification,o.attempts,
                   o.platform_request_id,o.last_error,o.payload,o.occurred_at
            from platform_operation o
            where o.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and o.shop_id=?
              and o.payload->>'targetType' = 'LISTING'
              and o.payload->>'externalRef' = ?
            order by o.occurred_at desc, o.id desc
            limit ? offset ?
            """,
            concat(List.of(profile.shopId(), profile.externalItemId()), safeSize,
                safePage * safeSize))
        .stream()
        .map(
            r ->
                new ListingPlatformOperationView(
                    r.get("id", UUID.class),
                    r.get("operation", String.class),
                    r.get("status", String.class),
                    r.get("verification", String.class),
                    r.get("attempts", Integer.class) == null ? 0 : r.get("attempts", Integer.class),
                    r.get("platform_request_id", String.class),
                    r.get("last_error", String.class),
                    jsonText(r.get("payload", Object.class)),
                    r.get("occurred_at", OffsetDateTime.class)))
        .toList();
  }

  public long platformOperationsTotal(String rawId) {
    UUID listingId = parseId(rawId);
    ListingPlatformProfile profile = profile(listingId);
    return count(
        """
        select count(*) from platform_operation o
        where o.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
          and o.shop_id=? and o.payload->>'targetType' = 'LISTING' and o.payload->>'externalRef' = ?
        """,
        profile.shopId(), profile.externalItemId());
  }

  // ==================================================================
  // 状态变更轨迹（V171 listing_status_event，与发布历史不是一回事）
  // ==================================================================

  private List<ListingStatusChange> statusChanges(UUID listingId) {
    return sql.fetch(
            """
            select from_status,to_status,source,occurred_at from listing_status_event
            where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and listing_id=?
            order by occurred_at desc, id desc
            limit 50
            """,
            listingId)
        .stream()
        .map(
            e ->
                new ListingStatusChange(
                    e.get("from_status", String.class),
                    e.get("to_status", String.class),
                    e.get("source", String.class),
                    e.get("occurred_at", OffsetDateTime.class)))
        .toList();
  }

  // ==================================================================
  // 全景
  // ==================================================================

  public ListingDetail detail(String rawId) {
    UUID listingId = parseId(rawId);
    ListingPlatformProfile profile = profile(listingId);
    Record snapshot = currentSnapshot(listingId);
    List<ListingVariantMapping> variantRows = variants(listingId);
    int variantCount = variantRows.size();
    int mappedCount =
        (int) variantRows.stream().filter(v -> !"UNMAPPED".equals(v.mappingStatus())).count();
    ListingWritebackRegistryView registry = writebackRegistry(profile);
    ListingDiffReport report = diff(rawId);

    return new ListingDetail(
        profile,
        mappingStatus(variantCount, mappedCount),
        variantCount,
        mappedCount,
        variantRows,
        contentComparison(profile, snapshot),
        snapshotView(listingId, snapshot),
        report,
        publishHistory(rawId, 0, MAX_PAGE_SIZE),
        platformOperations(rawId, 0, MAX_PAGE_SIZE),
        statusChanges(listingId),
        registry,
        report.differentCount(),
        capabilities().dimensions().stream()
            .filter(d -> !d.available())
            .map(ListingDetailCapabilityDimension::dimension)
            .toList());
  }

  // ==================================================================
  // 写入：平台内容快照（02 同步 / 4.4.1 采集入口）
  // ==================================================================

  /**
   * 登记一份平台当前内容快照。
   *
   * <p>🔴 只有平台侧字段。请求体里**没有**任何草稿字段 —— 内部草稿归 4.2.1.1。
   *
   * <p>时间列一律写 {@code ?::timestamptz}：jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒。
   */
  public SaveContentSnapshotResult saveSnapshot(String rawId, SaveContentSnapshotRequest request) {
    UUID tenant = currentTenant();
    UUID listingId = parseId(rawId);
    profile(listingId); // 不存在 / 不可见 → 与详情返回逐字相同的 404

    String language = text(request.language());
    if (language.isEmpty()) throw unprocessable("语言不能为空");
    String source = blankToNull(request.source());
    if (source == null) source = "SYNC";
    source = source.trim().toUpperCase(Locale.ROOT);
    if (!SOURCES.contains(source)) {
      throw unprocessable(
          "未知的快照来源：" + source + "。可选：" + String.join(" / ", SOURCES.stream().sorted().toList()));
    }
    boolean current = request.current() == null || request.current();
    OffsetDateTime capturedAt = request.capturedAt() == null ? OffsetDateTime.now() : request.capturedAt();

    String title = text(request.title());
    if (title.length() > 500) throw unprocessable("平台标题原文最长 500 字符");
    String bullets = jsonOfKind(request.bulletPoints(), "array", "要点 bulletPoints");
    String description = request.description() == null ? "" : request.description();
    String images = jsonOfKind(request.images(), "array", "图片集 images");
    String videoUrl = text(request.videoUrl());
    String aPlus = jsonOfKind(request.aPlusModules(), "array", "A+ 模块 aPlusModules");
    String attributes = jsonOfKind(request.categoryAttributes(), "object", "类目属性 categoryAttributes");
    String compliance =
        jsonOfKind(request.complianceStatements(), "object", "合规声明 complianceStatements");

    UUID id = Ids.next();
    sql.execute(
        """
        insert into listing_content_snapshot
          (id,tenant_id,listing_id,language,title,bullet_points,description,images,video_url,
           a_plus_modules,category_attributes,compliance_statements,source,is_current,
           captured_at,created_at,updated_at)
        values (?,?,?,?,?,?::jsonb,?,?::jsonb,?,?::jsonb,?::jsonb,?::jsonb,?,?,?::timestamptz,now(),now())
        """,
        id, tenant, listingId, language, title, bullets, description, images, videoUrl,
        aPlus, attributes, compliance, source, current, capturedAt);

    // 触发的降级把上一份当前快照翻成 is_current=false；这里回读确认落库结果
    Record written =
        sql.fetchOne(
            """
            select id, language, is_current, captured_at from listing_content_snapshot
            where tenant_id=? and id=?
            """,
            tenant, id);
    long history =
        count(
            "select count(*) from listing_content_snapshot where tenant_id=? and listing_id=?",
            tenant, listingId);
    OffsetDateTime writtenAt = written.get("captured_at", OffsetDateTime.class);
    return new SaveContentSnapshotResult(
        written.get("id", UUID.class),
        listingId,
        written.get("language", String.class),
        Boolean.TRUE.equals(written.get("is_current", Boolean.class)),
        writtenAt,
        writtenAt == null ? "" : writtenAt.toString(),
        history);
  }

  // ==================================================================
  // 写入：发布动作结果（4.3.1.1 / 4.3.2.1 调用）
  // ==================================================================

  /**
   * 登记一次发布 / 内容回写的结果。
   *
   * <p>🔴 本模块**不发起**发布（那是 4.3.1.1 / 4.3.2.1 的活），只留痕「发了什么、平台回了什么」。
   * 记录是追加写且不可改写（V175 触发器兜底）—— 它已经是「当时发了什么」的凭据。
   */
  public SavePublishRecordResult savePublishRecord(String rawId, SavePublishRecordRequest request) {
    UUID tenant = currentTenant();
    UUID listingId = parseId(rawId);
    profile(listingId);

    String action = blankToNull(request.action());
    if (action == null) action = "PUBLISH";
    action = action.trim().toUpperCase(Locale.ROOT);
    if (!PUBLISH_ACTIONS.contains(action)) {
      throw unprocessable(
          "未知的发布动作：" + action + "。可选：" + String.join(" / ", PUBLISH_ACTIONS.stream().sorted().toList()));
    }
    String outcome = blankToNull(request.outcome());
    if (outcome == null) throw unprocessable("必须给出发布结果 outcome");
    outcome = outcome.trim().toUpperCase(Locale.ROOT);
    if (!PUBLISH_OUTCOMES.contains(outcome)) {
      throw unprocessable(
          "未知的发布结果：" + outcome + "。可选：" + String.join(" / ", PUBLISH_OUTCOMES.stream().sorted().toList()));
    }
    String reason = text(request.reason());
    if (("FAILED".equals(outcome) || "REJECTED".equals(outcome)) && reason.isEmpty()) {
      throw unprocessable(outcome + " 必须写明原因 —— 光一个 FAILED 没人知道该改什么");
    }
    if (reason.length() > 500) throw unprocessable("原因最长 500 字符");
    String platformRequestId = text(request.platformRequestId());
    if (platformRequestId.length() > 128) throw unprocessable("平台请求 ID 最长 128 字符");

    UUID snapshotId = request.snapshotId();
    if (snapshotId != null) {
      // 引用别的商品的快照在服务层就拒（DB 触发器再兜一道）
      UUID found =
          one(
              UUID.class,
              """
              select id from listing_content_snapshot
              where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid
                and id=? and listing_id=?
              """,
              snapshotId, listingId);
      if (found == null) {
        throw unprocessable("引用内容快照不属于本 listing（或不存在）：" + snapshotId);
      }
    }

    OffsetDateTime occurredAt =
        request.occurredAt() == null ? OffsetDateTime.now() : request.occurredAt();
    UUID id = Ids.next();
    sql.execute(
        """
        insert into listing_publish_record
          (id,tenant_id,listing_id,snapshot_id,action,outcome,platform_request_id,reason,
           actor_id,occurred_at,created_at)
        values (?,?,?,?,?,?,?,?,?,?::timestamptz,now())
        """,
        id, tenant, listingId, snapshotId, action, outcome, platformRequestId, reason,
        currentActor(), occurredAt);

    Record written =
        sql.fetchOne(
            """
            select id, snapshot_id, action, outcome, occurred_at from listing_publish_record
            where tenant_id=? and id=?
            """,
            tenant, id);
    OffsetDateTime writtenAt = written.get("occurred_at", OffsetDateTime.class);
    return new SavePublishRecordResult(
        written.get("id", UUID.class),
        written.get("snapshot_id", UUID.class),
        written.get("action", String.class),
        written.get("outcome", String.class),
        writtenAt,
        writtenAt == null ? "" : writtenAt.toString());
  }

  private UUID currentActor() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.actor',true),'')");
    return raw == null ? null : UUID.fromString(String.valueOf(raw));
  }

  /** 仅供测试与文档引用：把蓝图原文保留在代码里，避免实现漂移。 */
  static final String BLUEPRINT = "4.1.1.2 Listing 详情";
}
