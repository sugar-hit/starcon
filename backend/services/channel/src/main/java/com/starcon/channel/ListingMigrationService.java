package com.starcon.channel;

import static com.starcon.channel.ListingMigrationModels.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 4.4.2.2 批量搬迁。
 *
 * <p>蓝图：「批量把一批 listing 从一个店铺搬到多个店铺，支持按规则筛选（类目、销量区间、价格区间），并对重复铺货风险给出提示。输入：
 * 筛选规则；目标店铺集合；重复度控制参数。输出：批量搬迁任务 + 结果明细；重复度风险提示。关系：上游 = 4.4.2.1；下游 = 4.3.1.2。
 * <b>搬迁后必须做标题差异化</b>，否则平台可能判定重复铺货并抑制 listing。」
 *
 * <p>🔴 <b>归属 channel 而不是 catalog</b>：源商品、目标店铺、目标草稿、待发布任务全部是 channel 自己的事实
 * （{@code listing} 按 {@code shop} 收敛可见性、{@code publish_batch} 是 4.3.1.2 的队列）。放 catalog 就得为店铺维度数据
 * 范围去读 channel 的表 —— 那是跨服务 SQL。与 4.1.1.1 / 4.1.1.2 / 4.3.1.2 / 4.4.2.1 同一先例。
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>输入：筛选规则</b> → {@link #preview} / {@link #execute} 里的 {@code candidates()}：类目（{@code
 *       category_path} / {@code platform_category_id}）、价格区间（{@code writeback_target.current_price}）、
 *       销量区间（**来源未接入**，按未知值策略如实报命中数）。真分页 + 真筛选 + 真排序。
 *   <li><b>输入：目标店铺集合</b> → {@code resolveTargets()}：每个目标店铺必须存在、与源店铺不同、与源店铺同平台、
 *       目标站点已被该店铺启用。
 *   <li><b>输入：重复度控制参数</b> → {@code DuplicatePolicy}：达到 {@code maxRiskLevel} 的搬迁项被跳过；
 *       风险 UNKNOWN（目标店铺无可比商品）时按 {@code onUnknown} 处置。
 *   <li><b>输出：批量搬迁任务 + 结果明细</b> → {@link #execute} 落 {@code listing_migration_batch} +
 *       {@code listing_migration_item}（源商品 × 目标店铺）。
 *   <li><b>输出：重复度风险提示</b> → {@link #duplicateRisks}：分值、等级、判定依据、锚点商品。
 * </ul>
 *
 * 🔴 <b>三条边界各有物理落点，不是提示文案：</b>
 *
 * <ol>
 *   <li><b>标题差异化是流程的一部分</b>：{@link #differentiateTitle} / {@link #differentiateBullets} / {@link
 *       #differentiateDescription} 算出差异化后的内容，逐条留痕在 {@code listing_migration_content_diff}（原值 / 新值 /
 *       规则 / 规则参数 / 谁改的）；V193 的 CHECK 与触发器在 DB 层拒绝"没做差异化的搬迁结果"。
 *   <li><b>重复度分值是算出来的</b>：{@link #assessDuplicate} 用标题（字符二元组 Dice）、类目（类目段 Dice + 平台类目 ID
 *       相等）、属性（共有属性键的取值一致率）三轴加权，权重与阈值公开在 {@link #capabilities()}，判定依据逐条落库。
 *       目标店铺一条既有 listing 都没有 ⇒ 等级 UNKNOWN、**分值为 null（不是 0）**。
 *   <li><b>幂等</b>：{@code idempotency_key} 唯一（DB 约束）+ 同批次（源 × 目标）唯一 + 已搬迁对唯一索引
 *       {@code listing_migration_item_live}。重复触发同一个搬迁任务不会产生第二条搬迁结果。
 * </ol>
 */
@Service
public class ListingMigrationService {

  // ==================================================================
  // 常量：重复度权重与阈值（公开在 capabilities，判定依据逐条落库）
  // ==================================================================

  /** 标题相似度权重。标题是平台判定重复铺货的第一依据，权重最高。 */
  public static final double WEIGHT_TITLE = 0.60;

  /** 类目相似度权重。 */
  public static final double WEIGHT_CATEGORY = 0.25;

  /** 属性相似度权重。 */
  public static final double WEIGHT_ATTRIBUTE = 0.15;

  /** CRITICAL 阈值（含）。 */
  public static final double THRESHOLD_CRITICAL = 0.85;

  /** HIGH 阈值（含）。 */
  public static final double THRESHOLD_HIGH = 0.65;

  /** MEDIUM 阈值（含）。 */
  public static final double THRESHOLD_MEDIUM = 0.40;

  /** 比对上限（默认）。 */
  public static final int DEFAULT_MAX_COMPARABLES = 200;

  /** 单次搬迁允许产生的目标商品数上限：4.3.1.2 的 publish_batch.limit_count 上限是 1000。 */
  public static final int MAX_TARGET_LISTINGS = 1000;

  private static final int MAX_PAGE_SIZE = 200;

  /**
   * 🔴 销量的数据源**尚未交付**。
   *
   * <p>平台侧销量归 4.5.1.1 平台商品监控（未交付）；内部订单口径归 09（跨服务，AGENTS.md 禁止跨库 SQL）。
   * 所以这里表达的是一个**恒为未知的表达式** —— 它不是 0：命中与否完全由 {@code unknownPolicy} 决定，
   * 界面上也会明说这个维度没接入（{@code capabilities} 里 available=false + ownerModule）。
   */
  private static final String SALES_EXPR = "null::numeric";

  /** 价格维度的数据源：2.5.2.1 回写登记簿里「平台上当前在售的值」的快照（V169）。 */
  private static final String PRICE_SOURCE = "writeback_target.current_price（V169 · 平台侧在售价快照）";

  /** 标题差异化的规则集（公开在 capabilities，逐条留痕在 listing_migration_content_diff）。 */
  private static final List<String> TITLE_RULES =
      List.of("TEMPLATE", "STORE_MARKER", "SITE_MARKER", "TRUNCATE", "COLLISION_RESOLVE");

  private final DSLContext sql;
  private final ObjectMapper json;

  public ListingMigrationService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ==================================================================
  // 小工具（照抄 ListingClaimService 的同名写法，两个类不共享私有方法）
  // ==================================================================

  private <T> T one(Class<T> type, String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, type);
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }

  private static String blankToNull(String value) {
    String trimmed = text(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static UUID parseId(String raw) {
    try {
      return UUID.fromString(text(raw));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
    }
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
  }

  private static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private static int clampSize(int size) {
    return Math.max(1, Math.min(size, MAX_PAGE_SIZE));
  }

  private static int pageOf(Integer page) {
    return page == null || page < 0 ? 0 : page;
  }

  /** 拼上分页参数（limit / offset）后转成 jOOQ 的绑定数组。 */
  private static Object[] pageBinds(List<Object> binds, int limit, long offset) {
    List<Object> all = new ArrayList<>(binds);
    all.add(limit);
    all.add(offset);
    return all.toArray();
  }

  /** count(*) 的标量读取（jOOQ 3.19 的 fetchValue(String, Class, Object...) 不存在，只能自己转型）。 */
  private Long countOf(String query, Object... binds) {
    Object raw = sql.fetchValue(query, binds);
    return raw == null ? null : ((Number) raw).longValue();
  }

  /** 单个 uuid 标量的读取。 */
  private UUID uuidOf(String query, Object... binds) {
    Object raw = sql.fetchValue(query, binds);
    if (raw == null) return null;
    if (raw instanceof UUID id) return id;
    return UUID.fromString(String.valueOf(raw));
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 在事务内绑定，不来自任何客户端头）。 */
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
      return json.writeValueAsString(json.readTree(value));
    } catch (Exception e) {
      return value;
    }
  }

  private JsonNode jsonNode(Object raw) {
    String value = jsonText(raw);
    if (value.isBlank()) return null;
    try {
      return json.readTree(value);
    } catch (Exception e) {
      return null;
    }
  }

  /** 可变长度的键值对（{@link Map#of} 最多 10 对，快照里的字段比这多）。 */
  private static Map<String, Object> map(Object... keyValues) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      out.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
    }
    return out;
  }

  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 当前事务的时刻（PG 的 now()）。 */
  private OffsetDateTime transactionNow() {
    Object raw = sql.fetchValue("select now()", OffsetDateTime.class);
    if (raw instanceof OffsetDateTime value) return value;
    if (raw instanceof java.sql.Timestamp ts) {
      return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
    }
    throw new IllegalStateException("无法读取事务时刻：" + raw);
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 🔴 逐维度能力声明。
   *
   * <p>未交付段一律 {@code available=false} + ownerModule + impact：销量（4.5.1.1 平台商品监控）、
   * 内部可售量（07）、平台审核结果（4.3.1.1 / 4.3.2.2）、内部内容草稿（4.2.1.1 / 4.2.1.2）、跨平台搬迁。
   */
  public ListingMigrationCapabilities capabilities() {
    return new ListingMigrationCapabilities(
        List.of(
            new ListingMigrationCapabilityDimension(
                "source_listing",
                "源 listing（平台商品、所属店铺与站点、平台状态）",
                true,
                "listing + shop / marketplace（V171 + V1，同库同服务）",
                "",
                "缺了它整条搬迁无从谈起"),
            new ListingMigrationCapabilityDimension(
                "source_content_snapshot",
                "🔴 被复用的源内容快照（标题 / 卖点 / 描述 / 图片 / 类目属性）",
                true,
                "listing_content_snapshot（V175）",
                "",
                "没有它就没有「原商品内容」可差异化 —— 搬迁会退化成建空壳 listing"),
            new ListingMigrationCapabilityDimension(
                "category_filter",
                "类目筛选（类目路径 / 平台类目 ID）",
                true,
                "listing.category_path / listing.platform_category_id（V171）",
                "",
                "类目筛不了 ⇒ 只能整店搬迁"),
            new ListingMigrationCapabilityDimension(
                "price_filter",
                "🔴 价格区间筛选（平台侧在售价快照；**不是 05 定价的权威售价**）",
                true,
                PRICE_SOURCE,
                "",
                "该 listing 未登记为可回写目标 ⇒ 价格是 UNKNOWN（**不是 0**）"),
            new ListingMigrationCapabilityDimension(
                "sales_filter",
                "🔴 销量区间筛选",
                false,
                "无已交付数据源",
                "4.5.1.1 平台商品监控（平台侧销量表现，未交付）；内部订单口径归 09（跨服务）",
                "销量维度取不到值 ⇒ 命中数按未知值策略如实报，**绝不读成 0**"),
            new ListingMigrationCapabilityDimension(
                "internal_stock",
                "内部可售量（缺货差异）",
                false,
                "无已交付数据源",
                "07 库存（未交付）",
                "本模块不判断缺货，跨店搬家用的是平台侧可售状态"),
            new ListingMigrationCapabilityDimension(
                "platform_review",
                "平台审核结果",
                false,
                "无已交付数据源",
                "4.3.1.1 一键刊登 / 4.3.2.2 平台状态回流（未交付）",
                "本模块只产出草稿与待发布任务，**不发起发布也不判审核**"),
            new ListingMigrationCapabilityDimension(
                "internal_content_draft",
                "内部内容草稿（4.2.1.1 刊登编辑器 / 4.2.1.2 批量编辑）",
                false,
                "无已交付数据源",
                "4.2.1.1 / 4.2.1.2（未交付）",
                "待发布任务的 draft_id 为空，内容以本次搬迁登记的内容快照为准"),
            new ListingMigrationCapabilityDimension(
                "cross_platform",
                "跨平台搬迁",
                false,
                "无已交付数据源",
                "平台类目 ID、站点与发布限流都按平台绑定",
                "本模块只做**同平台跨店**搬迁；跨平台请求 422 并点名两个平台")),
        List.of("CATEGORY", "PRICE", "SALES"),
        UNKNOWN_POLICIES,
        SORT_FIELDS,
        RISK_LEVELS,
        true,
        "搬迁结果的标题**一定**与原标题不同（归一化后也不同）："
            + "服务层按规则算出差异化标题并逐条留痕，DB 层的 CHECK 与触发器再复核一次。"
            + "平台判定重复铺货并抑制 listing 的风险不是靠提示文案挡的。",
        TITLE_RULES,
        "标题 " + WEIGHT_TITLE + " / 类目 " + WEIGHT_CATEGORY + " / 属性 " + WEIGHT_ATTRIBUTE,
        "CRITICAL ≥ " + THRESHOLD_CRITICAL + "，HIGH ≥ " + THRESHOLD_HIGH + "，MEDIUM ≥ " + THRESHOLD_MEDIUM
            + "，其余 LOW；分值算不出来时是 UNKNOWN（**分值为 null，不是 0**）",
        true,
        "重复度分值由标题（字符二元组 Dice）、类目（类目段 Dice + 平台类目 ID 相等）、"
            + "属性（共有属性键的取值一致率）三轴按上述权重加权算出，"
            + "不可知的分量不计入分母（**未知不是 0**），判定依据逐条落库。",
        "同一个搬迁任务重复触发只会得到同一个批次：幂等键唯一（调用方给，或按请求内容派生），"
            + "同批次（源商品 × 目标店铺）唯一，已搬迁的那一对在 DB 层也搬不了第二次。",
        "上游 = 4.4.2.1 商品认领（认领事实只读：被认领过去的商品不再重复搬迁）；"
            + "下游 = 4.3.1.2 批量发布（待发布任务**复用** V183 的 publish_batch / publish_task，不另建平行表）。");
  }

  // ==================================================================
  // 筛选：真分页 + 真筛选 + 真排序
  // ==================================================================

  /** 一次筛选的内部结果：命中行 + 命中总数。 */
  private record CandidatePage(List<Record> rows, long total) {}

  /** 归一化后的筛选条件。 */
  private record NormalizedFilter(
      UUID sourceShopId,
      List<String> categoryPaths,
      List<String> platformCategoryIds,
      BigDecimal priceMin,
      BigDecimal priceMax,
      BigDecimal salesMin,
      BigDecimal salesMax,
      String unknownPolicy,
      String sortBy,
      String sortOrder,
      int page,
      int size) {}

  private NormalizedFilter normalize(MigrationFilterRequest request) {
    if (request == null || request.sourceShopId() == null) {
      throw unprocessable("必须给出源店铺（批量搬迁 = 从一个店铺搬到多个店铺）。");
    }
    BigDecimal priceMin = request.priceMin();
    BigDecimal priceMax = request.priceMax();
    if (priceMin != null && priceMax != null && priceMin.compareTo(priceMax) > 0) {
      throw unprocessable("价格区间的下界不能大于上界。");
    }
    BigDecimal salesMin = request.salesMin();
    BigDecimal salesMax = request.salesMax();
    if (salesMin != null && salesMax != null && salesMin.compareTo(salesMax) > 0) {
      throw unprocessable("销量区间的下界不能大于上界。");
    }
    String policy = text(request.unknownPolicy()).toUpperCase(Locale.ROOT);
    if (policy.isEmpty()) policy = "EXCLUDE";
    if (!UNKNOWN_POLICIES.contains(policy)) {
      throw unprocessable("未知的未知值策略：" + request.unknownPolicy() + "。可选：" + String.join(" / ", UNKNOWN_POLICIES));
    }
    String sortBy = text(request.sortBy()).toUpperCase(Locale.ROOT);
    if (sortBy.isEmpty()) sortBy = "UPDATED_AT";
    if (!SORT_FIELDS.contains(sortBy)) {
      throw unprocessable("未知的排序维度：" + request.sortBy() + "。可选：" + String.join(" / ", SORT_FIELDS));
    }
    String sortOrder = text(request.sortOrder()).toUpperCase(Locale.ROOT);
    if (sortOrder.isEmpty()) sortOrder = "DESC";
    if (!Set.of("ASC", "DESC").contains(sortOrder)) {
      throw unprocessable("未知的排序方向：" + request.sortOrder() + "。可选：ASC / DESC");
    }
    return new NormalizedFilter(
        request.sourceShopId(),
        cleanList(request.categoryPaths()),
        cleanList(request.platformCategoryIds()),
        priceMin,
        priceMax,
        salesMin,
        salesMax,
        policy,
        sortBy,
        sortOrder,
        pageOf(request.page()),
        clampSize(request.size() == null ? 50 : request.size()));
  }

  private static List<String> cleanList(List<String> raw) {
    if (raw == null) return List.of();
    List<String> out = new ArrayList<>();
    for (String value : raw) {
      String trimmed = text(value);
      if (!trimmed.isEmpty() && !out.contains(trimmed)) out.add(trimmed);
    }
    return out;
  }

  /** 候选商品查询：类目 / 价格 / 销量三个维度都是真过滤（销量维度恒为未知 ⇒ 由未知值策略决定命中与否）。 */
  private CandidatePage candidates(NormalizedFilter filter) {
    UUID tenant = currentTenant();
    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    where.add("l.tenant_id = ?");
    binds.add(tenant);
    where.add("l.shop_id = ?");
    binds.add(filter.sourceShopId());

    // ---- 类目
    if (!filter.categoryPaths().isEmpty()) {
      where.add(
          "l.category_path in ("
              + String.join(",", java.util.Collections.nCopies(filter.categoryPaths().size(), "?"))
              + ")");
      binds.addAll(filter.categoryPaths());
    }
    if (!filter.platformCategoryIds().isEmpty()) {
      where.add(
          "l.platform_category_id in ("
              + String.join(",", java.util.Collections.nCopies(filter.platformCategoryIds().size(), "?"))
              + ")");
      binds.addAll(filter.platformCategoryIds());
    }

    // ---- 价格（来源：writeback_target.current_price；NULL = 没有登记值 = UNKNOWN，不是 0）
    if (filter.priceMin() != null || filter.priceMax() != null) {
      where.add(numericPredicate("wt.current_price", filter.priceMin(), filter.priceMax(), filter.unknownPolicy(), binds));
    }
    // ---- 销量（来源未接入 ⇒ 恒为未知 ⇒ 由未知值策略决定）
    if (filter.salesMin() != null || filter.salesMax() != null) {
      where.add(numericPredicate(SALES_EXPR, filter.salesMin(), filter.salesMax(), filter.unknownPolicy(), binds));
    }

    String from =
        """
        from listing l
        join shop s on s.tenant_id=l.tenant_id and s.id=l.shop_id
        left join writeback_target wt
               on wt.tenant_id=l.tenant_id and wt.shop_id=l.shop_id
              and wt.target_type='LISTING' and wt.external_ref=l.external_item_id
        where"""
            + " "
            + String.join(" and ", where);

    String totalSql = "select count(*) " + from;
    Long total = countOf(totalSql, binds.toArray());

    String orderBy = orderBy(filter);
    String pageSql =
        """
        select l.id, l.external_item_id, l.title, l.category_path, l.platform_category_id, l.status,
               l.review_status, l.version, l.marketplace_id, l.created_at, l.updated_at,
               wt.current_price as price, s.code as shop_code, s.name as shop_name
        """
            + from
            + " order by "
            + orderBy
            + " limit ? offset ?";
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(filter.size());
    pageBinds.add((long) filter.page() * filter.size());
    List<Record> rows = sql.fetch(pageSql, pageBinds.toArray());
    return new CandidatePage(rows, total == null ? 0L : total);
  }

  /**
   * 数值维度的三值谓词。
   *
   * <p>「取不到值」是一种状态（UNKNOWN），不是 0：
   * <ul>
   *   <li>{@code EXCLUDE}：只保留**确认落在区间内**的行（未知的排除在外）；
   *   <li>{@code INCLUDE}：区间内的行保留，未知的也保留（无法确认它在区间外）；
   *   <li>{@code ONLY}：只保留未知的行（"把不知道的那些挑出来"）。
   * </ul>
   */
  private String numericPredicate(
      String expr, BigDecimal min, BigDecimal max, String policy, List<Object> binds) {
    StringBuilder known = new StringBuilder("(").append(expr).append(" is not null");
    if (min != null) {
      known.append(" and ").append(expr).append(" >= ?");
      binds.add(min);
    }
    if (max != null) {
      known.append(" and ").append(expr).append(" <= ?");
      binds.add(max);
    }
    known.append(")");
    String unknown = "(" + expr + " is null)";
    return switch (policy) {
      case "INCLUDE" -> "(" + known + " or " + unknown + ")";
      case "ONLY" -> unknown;
      default -> known.toString();
    };
  }

  /** 排序：未知值永远排最后（不知道不是一个可比较的数）。 */
  private String orderBy(NormalizedFilter filter) {
    String direction = "DESC".equals(filter.sortOrder()) ? "desc" : "asc";
    String column =
        switch (filter.sortBy()) {
          case "CREATED_AT" -> "l.created_at";
          case "TITLE" -> "l.title";
          case "PRICE" -> "wt.current_price";
          case "SALES" -> SALES_EXPR;
          default -> "l.updated_at";
        };
    return column + " " + direction + " nulls last, l.id " + direction;
  }

  /**
   * 筛选说明（过滤命中要能解释）。
   *
   * <p>逐维度报：是否参与过滤、数据源是否已交付、命中条数、未知条数、以及一句人话。
   */
  private MigrationFilterExplanation explain(NormalizedFilter filter, long matched) {
    UUID tenant = currentTenant();
    Record stats =
        sql.fetchOne(
            """
            select count(*) as base,
                   count(*) filter (where wt.current_price is not null) as price_known,
                   count(*) filter (where wt.current_price is null) as price_unknown,
                   count(*) filter (where btrim(l.category_path) <> '' or btrim(l.platform_category_id) <> '')
                     as category_known,
                   count(*) filter (where btrim(l.category_path) = '' and btrim(l.platform_category_id) = '')
                     as category_unknown
            from listing l
            left join writeback_target wt
                   on wt.tenant_id=l.tenant_id and wt.shop_id=l.shop_id
                  and wt.target_type='LISTING' and wt.external_ref=l.external_item_id
            where l.tenant_id=? and l.shop_id=?
            """,
            tenant,
            filter.sourceShopId());
    long base = stats == null ? 0L : stats.get("base", Long.class);
    long priceKnown = stats == null ? 0L : stats.get("price_known", Long.class);
    long priceUnknown = stats == null ? 0L : stats.get("price_unknown", Long.class);
    long categoryKnown = stats == null ? 0L : stats.get("category_known", Long.class);
    long categoryUnknown = stats == null ? 0L : stats.get("category_unknown", Long.class);

    List<MigrationFilterDimensionReport> reports = new ArrayList<>();

    // ---- 类目
    boolean categoryApplied = !filter.categoryPaths().isEmpty() || !filter.platformCategoryIds().isEmpty();
    long categoryMatched = base;
    if (categoryApplied) {
      List<Object> binds = new ArrayList<>();
      List<String> where = new ArrayList<>();
      where.add("l.tenant_id=?");
      binds.add(tenant);
      where.add("l.shop_id=?");
      binds.add(filter.sourceShopId());
      if (!filter.categoryPaths().isEmpty()) {
        where.add(
            "l.category_path in ("
                + String.join(",", java.util.Collections.nCopies(filter.categoryPaths().size(), "?"))
                + ")");
        binds.addAll(filter.categoryPaths());
      }
      if (!filter.platformCategoryIds().isEmpty()) {
        where.add(
            "l.platform_category_id in ("
                + String.join(",", java.util.Collections.nCopies(filter.platformCategoryIds().size(), "?"))
                + ")");
        binds.addAll(filter.platformCategoryIds());
      }
      Long count =
          countOf("select count(*) from listing l where " + String.join(" and ", where), binds.toArray());
      categoryMatched = count == null ? 0L : count;
    }
    reports.add(
        new MigrationFilterDimensionReport(
            "CATEGORY",
            categoryApplied,
            true,
            "listing.category_path / listing.platform_category_id（V171）",
            "",
            filter.unknownPolicy(),
            categoryMatched,
            categoryUnknown,
            categoryApplied
                ? "按类目过滤：命中 " + categoryMatched + " 条（基线 " + base + " 条），"
                    + categoryUnknown + " 条没有登记类目（未知，不是 0 条）。"
                : "未按类目过滤：候选是源店铺的全部 " + base + " 条 listing。"));

    // ---- 价格
    boolean priceApplied = filter.priceMin() != null || filter.priceMax() != null;
    long priceMatched = 0;
    if (priceApplied) {
      List<Object> binds = new ArrayList<>();
      List<String> where = new ArrayList<>();
      where.add("l.tenant_id=?");
      binds.add(tenant);
      where.add("l.shop_id=?");
      binds.add(filter.sourceShopId());
      where.add("wt.current_price is not null");
      if (filter.priceMin() != null) {
        where.add("wt.current_price >= ?");
        binds.add(filter.priceMin());
      }
      if (filter.priceMax() != null) {
        where.add("wt.current_price <= ?");
        binds.add(filter.priceMax());
      }
      Long count =
          countOf(
              """
              select count(*)
              from listing l
              left join writeback_target wt
                     on wt.tenant_id=l.tenant_id and wt.shop_id=l.shop_id
                    and wt.target_type='LISTING' and wt.external_ref=l.external_item_id
              where"""
                  + " "
                  + String.join(" and ", where),
              binds.toArray());
      priceMatched = count == null ? 0L : count;
    }
    reports.add(
        new MigrationFilterDimensionReport(
            "PRICE",
            priceApplied,
            true,
            PRICE_SOURCE,
            "",
            filter.unknownPolicy(),
            priceMatched,
            priceUnknown,
            priceApplied
                ? "按价格区间过滤：命中 " + priceMatched + " 条；基线（源店铺全部 " + base + " 条）里 "
                    + priceUnknown + " 条没有登记平台侧在售价（未知，**不是 0**），按未知值策略 "
                    + filter.unknownPolicy() + " 处理。"
                : "未按价格过滤：候选是源店铺的全部 " + base + " 条 listing；其中 "
                    + priceUnknown + " 条没有登记平台侧在售价（未知，不是 0）。"));

    // ---- 销量
    boolean salesApplied = filter.salesMin() != null || filter.salesMax() != null;
    reports.add(
        new MigrationFilterDimensionReport(
            "SALES",
            salesApplied,
            false,
            "无已交付数据源",
            "4.5.1.1 平台商品监控（平台侧销量表现，未交付）",
            filter.unknownPolicy(),
            0L,
            base,
            salesApplied
                ? "🔴 销量维度**未接入**：平台侧销量归 4.5.1.1（未交付），内部订单口径归 09（跨服务）。"
                    + "全部 " + base + " 条候选的销量都是未知（**不是 0**），"
                    + "按未知值策略 " + filter.unknownPolicy() + " 处理 ⇒ 命中 0 条。"
                : "销量维度**未接入**（4.5.1.1 平台商品监控 / 09 订单），本次未按销量过滤。"));

    String note =
        "源店铺共 "
            + base
            + " 条 listing，本次筛选命中 "
            + matched
            + " 条。"
            + (salesApplied
                ? "⚠️ 销量维度未接入，销量区间不参与判定（命中数按未知值策略如实报）。"
                : "")
            + (priceApplied
                ? "⚠️ 价格维度取的是平台侧在售价快照，不是 05 定价的权威售价。"
                : "");
    return new MigrationFilterExplanation(base, matched, reports, note);
  }

  // ==================================================================
  // 目标店铺解析
  // ==================================================================

  /** 目标店铺上下文（店铺 + 站点 + 平台）。 */
  private record TargetContext(
      UUID shopId, String shopCode, String shopName, UUID marketplaceId, String marketplaceCode, UUID platformId) {}

  private List<TargetContext> resolveTargets(List<MigrationTarget> targets, UUID sourceShopId) {
    if (targets == null || targets.isEmpty()) {
      throw unprocessable("必须给出至少一个目标店铺。");
    }
    UUID tenant = currentTenant();
    Record sourceShop =
        sql.fetchOne(
            """
            select id, platform_id, code, name from shop
            where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid and id=?
            """,
            sourceShopId);
    if (sourceShop == null) {
      throw unprocessable("源店铺不存在或不在可见范围内：" + sourceShopId);
    }
    UUID sourcePlatformId = sourceShop.get("platform_id", UUID.class);

    List<TargetContext> out = new ArrayList<>();
    Set<UUID> seen = new java.util.HashSet<>();
    for (MigrationTarget target : targets) {
      if (target == null || target.shopId() == null) {
        throw unprocessable("目标店铺不能为空。");
      }
      if (!seen.add(target.shopId())) {
        throw unprocessable("目标店铺重复：" + target.shopId());
      }
      if (target.shopId().equals(sourceShopId)) {
        throw unprocessable(
            "🔴 目标店铺必须与源店铺不同：把商品搬回它自己所在的店铺不构成批量搬迁。");
      }
      Record shop =
          sql.fetchOne(
              """
              select id, platform_id, code, name from shop
              where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid and id=?
              """,
              target.shopId());
      if (shop == null) {
        throw unprocessable("目标店铺不存在或不在可见范围内：" + target.shopId());
      }
      UUID platformId = shop.get("platform_id", UUID.class);
      if (!platformId.equals(sourcePlatformId)) {
        throw unprocessable(
            "🔴 跨平台搬迁不在本模块范围：源店铺在平台 "
                + platformCode(sourcePlatformId)
                + "，目标店铺 "
                + shop.get("code", String.class)
                + " 在平台 "
                + platformCode(platformId)
                + "。平台类目 ID、站点与发布限流都按平台绑定，本模块只做**同平台跨店**搬迁。");
      }
      // 站点：给了就用给的；没给就取该店铺唯一启用的站点
      UUID marketplaceId = target.marketplaceId();
      if (marketplaceId == null) {
        List<Record> active =
            sql.fetch(
                """
                select m.id, m.code from marketplace m
                join shop_marketplace sm on sm.tenant_id=? and sm.shop_id=? and sm.marketplace_id=m.id
                where sm.status='ACTIVE'
                order by m.code
                """,
                tenant,
                target.shopId());
        if (active.isEmpty()) {
          throw unprocessable(
              "目标店铺 " + shop.get("code", String.class) + " 没有启用任何站点，无法作为搬迁目标。");
        }
        if (active.size() > 1) {
          throw unprocessable(
              "目标店铺 "
                  + shop.get("code", String.class)
                  + " 启用了多个站点（"
                  + String.join(
                      " / ",
                      active.stream().map(r -> String.valueOf(r.get("code", String.class))).toList())
                  + "），请显式指定目标站点。");
        }
        marketplaceId = active.get(0).get("id", UUID.class);
      }
      Record site =
          sql.fetchOne(
              """
              select m.id, m.code, sm.status from marketplace m
              join shop_marketplace sm on sm.tenant_id=? and sm.shop_id=? and sm.marketplace_id=m.id
              where m.id=?
              """,
              tenant,
              target.shopId(),
              marketplaceId);
      if (site == null) {
        throw unprocessable(
            "目标店铺未启用该站点：" + marketplaceId + "（站点必须先在 2.3.1.1 启用）");
      }
      if (!"ACTIVE".equals(site.get("status", String.class))) {
        throw unprocessable(
            "目标店铺在该站点的状态是 " + site.get("status", String.class) + "（已停用），不能作为搬迁目标。");
      }
      out.add(
          new TargetContext(
              target.shopId(),
              shop.get("code", String.class),
              shop.get("name", String.class),
              site.get("id", UUID.class),
              site.get("code", String.class),
              platformId));
    }
    return out;
  }

  private String platformCode(UUID platformId) {
    String code = one(String.class, "select code from platform where id=?", platformId);
    return code == null ? String.valueOf(platformId) : code;
  }

  // ==================================================================
  // 源内容
  // ==================================================================

  /** 源 listing 及其当前内容快照。 */
  private record SourceContent(
      UUID listingId,
      String externalItemId,
      String title,
      String categoryPath,
      String platformCategoryId,
      String status,
      String reviewStatus,
      long version,
      UUID marketplaceId,
      BigDecimal price,
      String language,
      List<String> bullets,
      String description,
      JsonNode attributes) {}

  private SourceContent loadSource(UUID tenant, UUID listingId) {
    Record row =
        sql.fetchOne(
            """
            select l.id, l.external_item_id, l.title, l.category_path, l.platform_category_id, l.status,
                   l.review_status, l.version, l.marketplace_id, wt.current_price as price
            from listing l
            left join writeback_target wt
                   on wt.tenant_id=l.tenant_id and wt.shop_id=l.shop_id
                  and wt.target_type='LISTING' and wt.external_ref=l.external_item_id
            where l.tenant_id=? and l.id=?
            """,
            tenant,
            listingId);
    if (row == null) return null;
    Record snapshot =
        sql.fetchOne(
            """
            select id, language, title, bullet_points, description, category_attributes
            from listing_content_snapshot
            where tenant_id=? and listing_id=? and is_current
            order by captured_at desc, id
            limit 1
            """,
            tenant,
            listingId);
    String title = row.get("title", String.class);
    String language = "";
    List<String> bullets = List.of();
    String description = "";
    JsonNode attributes = null;
    if (snapshot != null) {
      language = text(snapshot.get("language", String.class));
      String snapshotTitle = text(snapshot.get("title", String.class));
      if (!snapshotTitle.isEmpty()) title = snapshotTitle;
      bullets = bulletList(snapshot.get("bullet_points"));
      description = text(snapshot.get("description", String.class));
      attributes = jsonNode(snapshot.get("category_attributes"));
    }
    return new SourceContent(
        row.get("id", UUID.class),
        row.get("external_item_id", String.class),
        title,
        row.get("category_path", String.class),
        row.get("platform_category_id", String.class),
        row.get("status", String.class),
        row.get("review_status", String.class),
        row.get("version", Long.class) == null ? 0L : row.get("version", Long.class),
        row.get("marketplace_id", UUID.class),
        row.get("price", BigDecimal.class),
        language,
        bullets,
        description,
        attributes);
  }

  private List<String> bulletList(Object raw) {
    if (raw == null) return List.of();
    String value = jsonText(raw);
    if (value.isBlank() || "[]".equals(value) || "{}".equals(value) || "null".equals(value)) return List.of();
    try {
      JsonNode node = json.readTree(value);
      if (!node.isArray()) return List.of();
      List<String> out = new ArrayList<>();
      for (JsonNode element : node) {
        out.add(element.isValueNode() ? element.asText() : element.toString());
      }
      return out;
    } catch (Exception e) {
      return List.of();
    }
  }

  // ==================================================================
  // 重复度评估
  // ==================================================================

  /** 一次重复度评估的结果。 */
  private record DuplicateAssessment(
      String level,
      BigDecimal score,
      UUID comparableListingId,
      String comparableTitle,
      int comparableCount,
      BigDecimal titleSimilarity,
      BigDecimal categorySimilarity,
      BigDecimal attributeSimilarity,
      MigrationDuplicateBasis basis) {}

  /**
   * 🔴 重复度评估：分值是**算出来的**。
   *
   * <p>三轴：标题（字符二元组 Dice）、类目（平台类目 ID 相等 / 类目段 Dice）、属性（共有属性键的取值一致率）。
   * 不可知的分量**不计入分母**（未知不是 0）；三个分量都不可知时等级是 UNKNOWN、**分值为 null**。
   */
  private DuplicateAssessment assessDuplicate(SourceContent source, UUID targetShopId, int maxComparables) {
    UUID tenant = currentTenant();
    List<Record> comparables =
        sql.fetch(
            """
            select l.id, l.title, l.category_path, l.platform_category_id, s.category_attributes
            from listing l
            left join lateral (
              select snap.category_attributes from listing_content_snapshot snap
              where snap.tenant_id=l.tenant_id and snap.listing_id=l.id and snap.is_current
              order by snap.captured_at desc, snap.id
              limit 1
            ) s on true
            where l.tenant_id=? and l.shop_id=?
            order by l.updated_at desc, l.id
            limit ?
            """,
            tenant,
            targetShopId,
            maxComparables);

    if (comparables.isEmpty()) {
      return new DuplicateAssessment(
          "UNKNOWN",
          null,
          null,
          "",
          0,
          null,
          null,
          null,
          new MigrationDuplicateBasis(
              source.title(),
              WEIGHT_TITLE,
              WEIGHT_CATEGORY,
              WEIGHT_ATTRIBUTE,
              THRESHOLD_CRITICAL,
              THRESHOLD_HIGH,
              THRESHOLD_MEDIUM,
              0,
              null,
              "",
              null,
              null,
              null,
              null,
              null,
              List.of(),
              List.of(),
              "目标店铺没有任何既有 listing，无从比较 ⇒ 风险 UNKNOWN（**不是 0 分**）。"));
    }

    Double bestScore = null;
    Record best = null;
    Double bestTitle = null;
    Double bestCategory = null;
    Double bestAttribute = null;
    int sharedBigrams = 0;
    int unionBigrams = 0;
    List<String> matchedAttributes = List.of();
    List<String> conflictingAttributes = List.of();

    for (Record candidate : comparables) {
      String candidateTitle = text(candidate.get("title", String.class));
      Double titleSimilarity = bigramDice(source.title(), candidateTitle);
      Double categorySimilarity =
          categorySimilarity(
              source.categoryPath(),
              source.platformCategoryId(),
              candidate.get("category_path", String.class),
              candidate.get("platform_category_id", String.class));
      Double attributeSimilarity =
          attributeSimilarity(source.attributes(), jsonNode(candidate.get("category_attributes")));
      Double composite = composite(titleSimilarity, categorySimilarity, attributeSimilarity);
      if (composite == null) continue;
      if (bestScore == null || composite > bestScore) {
        bestScore = composite;
        best = candidate;
        bestTitle = titleSimilarity;
        bestCategory = categorySimilarity;
        bestAttribute = attributeSimilarity;
        int[] counts = bigramCounts(source.title(), candidateTitle);
        sharedBigrams = counts[0];
        unionBigrams = counts[1];
        AttributeDetail detail = attributeDetail(source.attributes(), jsonNode(candidate.get("category_attributes")));
        matchedAttributes = detail.matched();
        conflictingAttributes = detail.conflicting();
      }
    }

    if (bestScore == null || best == null) {
      return new DuplicateAssessment(
          "UNKNOWN",
          null,
          null,
          "",
          comparables.size(),
          null,
          null,
          null,
          new MigrationDuplicateBasis(
              source.title(),
              WEIGHT_TITLE,
              WEIGHT_CATEGORY,
              WEIGHT_ATTRIBUTE,
              THRESHOLD_CRITICAL,
              THRESHOLD_HIGH,
              THRESHOLD_MEDIUM,
              comparables.size(),
              null,
              "",
              null,
              null,
              null,
              null,
              null,
              List.of(),
              List.of(),
              "目标店铺有 " + comparables.size() + " 条既有 listing，但标题 / 类目 / 属性三个维度都不可知 "
                  + "⇒ 风险 UNKNOWN（**不是 0 分**）。"));
    }

    BigDecimal score = BigDecimal.valueOf(bestScore).setScale(5, RoundingMode.HALF_UP);
    String level = levelOf(bestScore);
    String bestTitleText = text(best.get("title", String.class));
    String explanation =
        "与目标店铺的《"
            + bestTitleText
            + "》最接近：标题相似度 "
            + percent(bestTitle)
            + "、类目相似度 "
            + percent(bestCategory)
            + "、属性相似度 "
            + percent(bestAttribute)
            + "，按标题 "
            + WEIGHT_TITLE
            + " / 类目 "
            + WEIGHT_CATEGORY
            + " / 属性 "
            + WEIGHT_ATTRIBUTE
            + " 加权得 "
            + score
            + " ⇒ "
            + level
            + "（CRITICAL ≥ "
            + THRESHOLD_CRITICAL
            + "，HIGH ≥ "
            + THRESHOLD_HIGH
            + "，MEDIUM ≥ "
            + THRESHOLD_MEDIUM
            + "）。";
    return new DuplicateAssessment(
        level,
        score,
        best.get("id", UUID.class),
        bestTitleText,
        comparables.size(),
        scale(bestTitle),
        scale(bestCategory),
        scale(bestAttribute),
        new MigrationDuplicateBasis(
            source.title(),
            WEIGHT_TITLE,
            WEIGHT_CATEGORY,
            WEIGHT_ATTRIBUTE,
            THRESHOLD_CRITICAL,
            THRESHOLD_HIGH,
            THRESHOLD_MEDIUM,
            comparables.size(),
            best.get("id", UUID.class),
            bestTitleText,
            scale(bestTitle),
            scale(bestCategory),
            scale(bestAttribute),
            sharedBigrams,
            unionBigrams,
            matchedAttributes,
            conflictingAttributes,
            explanation));
  }

  private static BigDecimal scale(Double value) {
    return value == null ? null : BigDecimal.valueOf(value).setScale(5, RoundingMode.HALF_UP);
  }

  private static String percent(Double value) {
    return value == null ? "未知" : BigDecimal.valueOf(value * 100).setScale(1, RoundingMode.HALF_UP) + "%";
  }

  /** 等级：分值算不出来时是 UNKNOWN，不是 LOW。 */
  private static String levelOf(double score) {
    if (score >= THRESHOLD_CRITICAL) return "CRITICAL";
    if (score >= THRESHOLD_HIGH) return "HIGH";
    if (score >= THRESHOLD_MEDIUM) return "MEDIUM";
    return "LOW";
  }

  /** 三轴加权：不可知的分量不计入分母。 */
  private static Double composite(Double title, Double category, Double attribute) {
    double sum = 0;
    double weight = 0;
    if (title != null) {
      sum += title * WEIGHT_TITLE;
      weight += WEIGHT_TITLE;
    }
    if (category != null) {
      sum += category * WEIGHT_CATEGORY;
      weight += WEIGHT_CATEGORY;
    }
    if (attribute != null) {
      sum += attribute * WEIGHT_ATTRIBUTE;
      weight += WEIGHT_ATTRIBUTE;
    }
    if (weight == 0) return null;
    return sum / weight;
  }

  /**
   * 标题相似度：字符二元组 Dice 系数。
   *
   * <p>🔴 为什么不用空格分词：中文标题没有空格，按词切会把整条标题变成一个词，
   * 两个不同的中文标题会算出 1.0。字符二元组对中英文都成立，且完全确定（同样的输入永远同样的分）。
   */
  static double bigramDice(String a, String b) {
    String left = normalizeTitle(a);
    String right = normalizeTitle(b);
    if (left.equals(right)) return 1.0;
    if (left.isEmpty() || right.isEmpty()) return 0.0;
    int[] counts = bigramCounts(a, b);
    if (counts[1] == 0) return 0.0;
    return 2.0 * counts[0] / counts[1];
  }

  /** @return [命中的二元组数, 二元组总数] */
  private static int[] bigramCounts(String a, String b) {
    Map<String, Integer> left = bigrams(normalizeTitle(a));
    Map<String, Integer> right = bigrams(normalizeTitle(b));
    int shared = 0;
    for (Map.Entry<String, Integer> entry : left.entrySet()) {
      shared += Math.min(entry.getValue(), right.getOrDefault(entry.getKey(), 0));
    }
    int total =
        left.values().stream().mapToInt(Integer::intValue).sum()
            + right.values().stream().mapToInt(Integer::intValue).sum();
    return new int[] {shared, total};
  }

  private static Map<String, Integer> bigrams(String normalized) {
    Map<String, Integer> out = new HashMap<>();
    for (int i = 0; i + 1 < normalized.length(); i++) {
      out.merge(normalized.substring(i, i + 2), 1, Integer::sum);
    }
    return out;
  }

  /** 归一化：只保留字母与数字并转小写（大小写与空白差异不算差异化）。 */
  static String normalizeTitle(String value) {
    if (value == null) return "";
    StringBuilder out = new StringBuilder();
    for (char c : value.toLowerCase(Locale.ROOT).toCharArray()) {
      if (Character.isLetterOrDigit(c)) out.append(c);
    }
    return out.toString();
  }

  /** 类目相似度：平台类目 ID 相等直接满分，否则按类目路径的段落重合度算。 */
  static Double categorySimilarity(
      String pathA, String categoryIdA, String pathB, String categoryIdB) {
    boolean knownA = hasText(categoryIdA) || hasText(pathA);
    boolean knownB = hasText(categoryIdB) || hasText(pathB);
    if (!knownA || !knownB) return null;
    if (hasText(categoryIdA) && hasText(categoryIdB) && categoryIdA.trim().equals(categoryIdB.trim())) {
      return 1.0;
    }
    return segmentDice(pathA, pathB);
  }

  /** 类目路径的段落 Dice（按 > / / 等分隔符切段）。 */
  static double segmentDice(String a, String b) {
    List<String> left = segments(a);
    List<String> right = segments(b);
    if (left.isEmpty() || right.isEmpty()) return 0.0;
    Map<String, Integer> counts = new HashMap<>();
    for (String segment : left) counts.merge(segment, 1, Integer::sum);
    int shared = 0;
    for (String segment : right) {
      int available = counts.getOrDefault(segment, 0);
      if (available > 0) {
        shared++;
        counts.put(segment, available - 1);
      }
    }
    return 2.0 * shared / (left.size() + right.size());
  }

  private static List<String> segments(String path) {
    if (!hasText(path)) return List.of();
    return Arrays.stream(path.split("[>»/／|｜]"))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();
  }

  /** 属性相似度：只比**两边都声明**的属性键；只有一边声明的不计入分母。 */
  static Double attributeSimilarity(JsonNode a, JsonNode b) {
    AttributeDetail detail = attributeDetail(a, b);
    if (detail.comparable() == 0) return null;
    return (double) detail.matched().size() / detail.comparable();
  }

  /** 属性比对的明细。 */
  private record AttributeDetail(List<String> matched, List<String> conflicting, int comparable) {}

  private static AttributeDetail attributeDetail(JsonNode a, JsonNode b) {
    if (a == null || b == null || !a.isObject() || !b.isObject()) {
      return new AttributeDetail(List.of(), List.of(), 0);
    }
    List<String> matched = new ArrayList<>();
    List<String> conflicting = new ArrayList<>();
    int comparable = 0;
    List<String> keys = new ArrayList<>();
    a.fieldNames().forEachRemaining(keys::add);
    b.fieldNames().forEachRemaining(
        key -> {
          if (!keys.contains(key)) keys.add(key);
        });
    for (String key : keys) {
      JsonNode left = a.get(key);
      JsonNode right = b.get(key);
      if (left == null || right == null) continue; // 只有一边声明 ⇒ 不可比，不计入分母
      comparable++;
      if (left.equals(right)) matched.add(key);
      else conflicting.add(key);
    }
    return new AttributeDetail(matched, conflicting, comparable);
  }

  // ==================================================================
  // 标题 / 卖点 / 描述差异化
  // ==================================================================

  /** 一次差异化：每一步规则与它之后的值。 */
  private record DiffStep(String ruleId, String valueBefore, String valueAfter, MigrationRuleParams params) {}

  /** 标题差异化方案。 */
  private record TitlePlan(String original, String differentiated, List<DiffStep> steps) {}

  /**
   * 🔴 标题差异化：产出**一定与原标题不同**的标题，并留下每一步规则的痕迹。
   *
   * <p>规则顺序：
   * <ol>
   *   <li>{@code TEMPLATE} —— 调用方给了模板就用模板（占位符 {title} {storeName} {shopCode} {marketplaceCode} {categoryLeaf}）；
   *   <li>{@code STORE_MARKER} —— 默认规则：前缀加上目标店铺身份（店铺名 + 店铺编码）；
   *   <li>{@code SITE_MARKER} —— 目标站点与源站点不同才加（内容按平台 + 站点隔离，跨站点必须标出来）；
   *   <li>{@code TRUNCATE} —— 结果超过 500 字时截断**原标题部分**（原值在留痕里是完整的）；
   *   <li>{@code COLLISION_RESOLVE} —— 结果与目标店铺既有标题撞车时加序号消歧。
   * </ol>
   */
  private TitlePlan differentiateTitle(
      SourceContent source,
      TargetContext target,
      String titleTemplate,
      Set<String> existingTitles,
      Set<String> producedTitles) {
    String original = text(source.title());
    if (original.isEmpty()) {
      throw unprocessable("源 listing 没有标题，无法做标题差异化（不搬没有标题的商品）。");
    }
    List<DiffStep> steps = new ArrayList<>();
    String current = original;

    String storeName = hasText(target.shopName()) ? target.shopName() : target.shopCode();
    String storeCode = hasText(target.shopCode()) ? target.shopCode() : "";
    if (!hasText(storeName) || !hasText(storeCode)) {
      throw unprocessable(
          "🔴 目标店铺缺少店铺名或店铺编码，无法做标题差异化（差异化是搬迁的硬性要求，不允许跳过）。");
    }
    String categoryLeaf = "";
    List<String> segments = segments(source.categoryPath());
    if (!segments.isEmpty()) categoryLeaf = segments.get(segments.size() - 1);

    if (hasText(titleTemplate)) {
      String rendered =
          titleTemplate
              .replace("{title}", current)
              .replace("{storeName}", storeName)
              .replace("{shopCode}", storeCode)
              .replace("{marketplaceCode}", text(target.marketplaceCode()))
              .replace("{categoryLeaf}", categoryLeaf);
      if (rendered.isBlank() || rendered.equals(current)) {
        throw unprocessable("标题模板渲染后与原标题相同，无法做差异化（模板：" + titleTemplate + "）。");
      }
      steps.add(
          new DiffStep(
              "TEMPLATE",
              current,
              rendered,
              new MigrationRuleParams(
                  storeName, storeCode, target.marketplaceCode(), titleTemplate, null, "按调用方给定的标题模板渲染")));
      current = rendered;
    } else {
      String marked = storeName + "（" + storeCode + "）- " + current;
      steps.add(
          new DiffStep(
              "STORE_MARKER",
              current,
              marked,
              new MigrationRuleParams(
                  storeName,
                  storeCode,
                  target.marketplaceCode(),
                  null,
                  null,
                  "前缀加上目标店铺身份（店铺名 + 店铺编码），让目标店铺的标题与源商品可区分")));
      current = marked;
    }

    // 站点：内容按平台 + 站点隔离，跨站点必须标出来
    if (source.marketplaceId() != null && !source.marketplaceId().equals(target.marketplaceId())) {
      String marked = current + " - " + text(target.marketplaceCode());
      steps.add(
          new DiffStep(
              "SITE_MARKER",
              current,
              marked,
              new MigrationRuleParams(
                  storeName,
                  storeCode,
                  target.marketplaceCode(),
                  null,
                  null,
                  "目标站点与源站点不同，标题标注目标站点（内容按平台 + 站点隔离）")));
      current = marked;
    }

    // 长度：listing.title 是 varchar(500)，超长插不进去。截断的是原标题部分，原值在留痕里完整保留。
    if (current.length() > 500) {
      int suffixLength = current.length() - original.length();
      int keep = Math.max(1, 500 - Math.max(0, suffixLength));
      String trimmed = original.substring(0, Math.min(original.length(), keep));
      String shortened = current.substring(0, current.length() - original.length()) + trimmed;
      steps.add(
          new DiffStep(
              "TRUNCATE",
              current,
              shortened,
              new MigrationRuleParams(
                  storeName,
                  storeCode,
                  target.marketplaceCode(),
                  null,
                  null,
                  "标题超过 500 字上限，截断原标题部分（原值在留痕里是完整的）")));
      current = shortened;
    }

    // 撞车消歧：目标店铺里已经有同名标题 ⇒ 加序号
    int sequence = 1;
    String candidate = current;
    while (existingTitles.contains(normalizeTitle(candidate)) || producedTitles.contains(normalizeTitle(candidate))) {
      sequence++;
      candidate = current + " #" + sequence;
      if (sequence > 50) {
        throw unprocessable("目标店铺里同名标题过多，无法消歧（超过 50 次）。");
      }
    }
    if (sequence > 1) {
      steps.add(
          new DiffStep(
              "COLLISION_RESOLVE",
              current,
              candidate,
              new MigrationRuleParams(
                  storeName,
                  storeCode,
                  target.marketplaceCode(),
                  null,
                  sequence,
                  "与目标店铺既有标题撞车，加序号消歧")));
      current = candidate;
    }

    if (normalizeTitle(current).equals(normalizeTitle(original))) {
      throw unprocessable("🔴 标题差异化没有产生任何差异（平台看到的是同一个标题），搬迁被拒绝。");
    }
    return new TitlePlan(original, current, steps);
  }

  /** 卖点差异化：追加一条与目标店铺绑定的卖点（源没有卖点时，落一条店铺专属卖点）。 */
  private DiffStep differentiateBullets(SourceContent source, TargetContext target) {
    List<String> before = source.bullets();
    String marker =
        target.shopName() + "（" + target.shopCode() + "）店铺直供发货，售后与退换按本店规则执行";
    List<String> after = new ArrayList<>(before);
    after.add(marker);
    return new DiffStep(
        "BULLET_STORE_MARKER",
        writeJson(before),
        writeJson(after),
        new MigrationRuleParams(
            target.shopName(),
            target.shopCode(),
            target.marketplaceCode(),
            null,
            null,
            "追加一条与目标店铺绑定的卖点（源没有卖点时落一条店铺专属卖点）"));
  }

  /** 描述差异化：追加一段与目标店铺绑定的说明。 */
  private DiffStep differentiateDescription(SourceContent source, TargetContext target) {
    String before = source.description();
    String tail =
        target.shopName()
            + "（"
            + target.shopCode()
            + "）店铺直供，本商品为该店铺专属渠道商品，标题、卖点与描述均已针对本店做差异化处理。";
    String after = before.isEmpty() ? tail : before + "\n\n" + tail;
    return new DiffStep(
        "DESCRIPTION_STORE_MARKER",
        before,
        after,
        new MigrationRuleParams(
            target.shopName(),
            target.shopCode(),
            target.marketplaceCode(),
            null,
            null,
            "追加一段与目标店铺绑定的描述"));
  }

  // ==================================================================
  // 预览
  // ==================================================================

  /** 预览：筛选命中的候选商品 × 目标店铺的风险与差异化计划（**不写任何数据**）。 */
  public ListingMigrationPreview preview(MigrationFilterRequest filter, List<MigrationTarget> targets) {
    NormalizedFilter normalized = normalize(filter);
    List<TargetContext> resolved = resolveTargets(targets, normalized.sourceShopId());
    CandidatePage page = candidates(normalized);
    MigrationFilterExplanation explanation = explain(normalized, page.total());

    List<ListingMigrationPreviewCandidate> items = new ArrayList<>();
    UUID tenant = currentTenant();
    for (Record row : page.rows()) {
      UUID listingId = row.get("id", UUID.class);
      SourceContent source = loadSource(tenant, listingId);
      if (source == null) continue;
      List<ListingMigrationPreviewTarget> perTarget = new ArrayList<>();
      for (TargetContext target : resolved) {
        perTarget.add(previewTarget(source, target));
      }
      items.add(
          new ListingMigrationPreviewCandidate(
              source.listingId(),
              source.title(),
              source.categoryPath(),
              source.platformCategoryId(),
              source.status(),
              priceNote(source.price()),
              perTarget));
    }
    return new ListingMigrationPreview(
        explanation, items, normalized.page(), normalized.size(), page.total());
  }

  private ListingMigrationPreviewTarget previewTarget(SourceContent source, TargetContext target) {
    Set<String> existing = existingTitles(target.shopId());
    TitlePlan plan;
    try {
      plan = differentiateTitle(source, target, null, existing, new java.util.HashSet<>());
    } catch (ResponseStatusException e) {
      plan = null;
    }
    DuplicateAssessment assessment = assessDuplicate(source, target.shopId(), DEFAULT_MAX_COMPARABLES);
    boolean claimed = alreadyClaimed(source.listingId(), target.shopId());
    boolean migrated = alreadyMigrated(source.listingId(), target.shopId());
    return new ListingMigrationPreviewTarget(
        target.shopId(),
        target.shopCode(),
        target.shopName(),
        target.marketplaceId(),
        target.marketplaceCode(),
        plan == null ? "" : plan.differentiated(),
        plan == null ? List.of() : plan.steps().stream().map(DiffStep::ruleId).toList(),
        migrated,
        claimed,
        assessment.level(),
        assessment.score(),
        assessment.basis().explanation());
  }

  private String priceNote(BigDecimal price) {
    if (price == null) {
      return "价格未知：该 listing 未登记为可回写目标 ⇒ 平台上当前在售的价没有登记值（**不是 0**）。";
    }
    return "价格取自 " + PRICE_SOURCE + "：" + price + "（平台侧快照，不是 05 定价的权威售价）。";
  }

  /** 目标店铺里已有的标题（归一化后），用于撞车消歧。 */
  private Set<String> existingTitles(UUID shopId) {
    List<String> titles =
        sql.fetch(
                """
                select title from listing
                where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid and shop_id=?
                """,
                shopId)
            .stream()
            .map(row -> row.get("title", String.class))
            .toList();
    Set<String> out = new java.util.HashSet<>();
    for (String title : titles) out.add(normalizeTitle(title));
    return out;
  }

  /** 已搬迁：同一对（源商品 × 目标店铺）搬过了没有。 */
  private boolean alreadyMigrated(UUID sourceListingId, UUID targetShopId) {
    Long count =
        countOf(
            """
            select count(*) from listing_migration_item
            where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid
              and source_listing_id=? and target_shop_id=? and state='MIGRATED'
            """,
            sourceListingId,
            targetShopId);
    return count != null && count > 0;
  }

  /** 上游 4.4.2.1：这个源商品是不是已经被认领到目标店铺了（只读，不抢写入所有权）。 */
  private boolean alreadyClaimed(UUID sourceListingId, UUID targetShopId) {
    Long count =
        countOf(
            """
            select count(*) from listing_claim
            where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid
              and source_listing_id=? and target_shop_id=?
            """,
            sourceListingId,
            targetShopId);
    return count != null && count > 0;
  }

  // ==================================================================
  // 执行（批量搬迁）
  // ==================================================================

  /**
   * 🔴 批量搬迁。
   *
   * <p>幂等：同一个幂等键重复触发 ⇒ 返回**同一个批次**，不产生第二条搬迁结果。
   * 标题差异化是流程的一部分（每个 MIGRATED 的结果都带差异化后的标题与逐条留痕）。
   */
  public ListingMigrationBatchRow execute(MigrationExecuteRequest request, UUID actorId) {
    UUID tenant = currentTenant();
    NormalizedFilter filter = normalize(request.filter());
    List<TargetContext> targets = resolveTargets(request.targets(), filter.sourceShopId());
    DuplicatePolicy policy = normalizePolicy(request.duplicatePolicy());

    // ---- 幂等：同一个键直接返回已有批次
    String idempotencyKey = idempotencyKey(request, filter, targets, policy);
    Record existing =
        sql.fetchOne(
            """
            select id from listing_migration_batch
            where tenant_id=? and idempotency_key=?
            """,
            tenant,
            idempotencyKey);
    if (existing != null) {
      return loadBatch(existing.get("id", UUID.class));
    }

    CandidatePage page = candidates(filter);
    if (page.rows().isEmpty()) {
      MigrationFilterExplanation explanation = explain(filter, page.total());
      throw unprocessable(
          "🔴 没有命中任何候选商品，本次搬迁没有产生任何结果。筛选说明：" + explanation.note());
    }
    long planned = (long) page.rows().size() * targets.size();
    if (planned > MAX_TARGET_LISTINGS) {
      throw unprocessable(
          "🔴 本次搬迁会产生 " + planned + " 个目标商品，超过单次上限 " + MAX_TARGET_LISTINGS
              + "（4.3.1.2 的发布批次上限）。请缩小筛选范围或分批搬迁。");
    }

    // ---- 批次
    UUID batchId = Ids.next();
    List<UUID> targetIds = targets.stream().map(TargetContext::shopId).toList();
    String targetDetail =
        writeJson(
            targets.stream()
                .map(
                    t ->
                        Map.of(
                            "shopId", t.shopId().toString(),
                            "shopCode", t.shopCode(),
                            "shopName", t.shopName(),
                            "marketplaceId", t.marketplaceId().toString(),
                            "marketplaceCode", t.marketplaceCode()))
                .toList());
    String filterSnapshot =
        writeJson(
            map(
                "sourceShopId", filter.sourceShopId().toString(),
                "categoryPaths", filter.categoryPaths(),
                "platformCategoryIds", filter.platformCategoryIds(),
                "priceMin", filter.priceMin() == null ? "" : filter.priceMin().toPlainString(),
                "priceMax", filter.priceMax() == null ? "" : filter.priceMax().toPlainString(),
                "salesMin", filter.salesMin() == null ? "" : filter.salesMin().toPlainString(),
                "salesMax", filter.salesMax() == null ? "" : filter.salesMax().toPlainString(),
                "unknownPolicy", filter.unknownPolicy(),
                "sortBy", filter.sortBy(),
                "sortOrder", filter.sortOrder(),
                "priceSource", PRICE_SOURCE,
                "salesSource", "未接入（4.5.1.1 平台商品监控 / 09 订单）"));
    String policySnapshot =
        writeJson(
            Map.of(
                "maxRiskLevel", policy.maxRiskLevel(),
                "onUnknown", policy.onUnknown(),
                "maxComparables", policy.maxComparables(),
                "titleTemplate", policy.titleTemplate() == null ? "" : policy.titleTemplate()));
    sql.execute(
        """
        insert into listing_migration_batch
          (id,tenant_id,source_shop_id,target_shop_ids,target_detail,filter_rule,duplicate_policy,
           idempotency_key,state,candidate_count,total_count,migrated_count,skipped_count,failed_count,
           publish_batch_id,actor_id,note,created_at,updated_at)
        values (?,?,?,?::uuid[],?::jsonb,?::jsonb,?::jsonb,?, 'EXECUTED',?,0,0,0,0,null,?,?,now(),now())
        """,
        batchId,
        tenant,
        filter.sourceShopId(),
        targetIds.toArray(new UUID[0]),
        targetDetail,
        filterSnapshot,
        policySnapshot,
        idempotencyKey,
        (int) page.rows().size(),
        actorId,
        text(request.note()));

    // ---- 逐（源商品 × 目标店铺）搬迁
    Map<UUID, Set<String>> producedByShop = new LinkedHashMap<>();
    List<PreparedItem> prepared = new ArrayList<>();
    for (Record row : page.rows()) {
      UUID listingId = row.get("id", UUID.class);
      SourceContent source = loadSource(tenant, listingId);
      if (source == null) continue;
      for (TargetContext target : targets) {
        prepared.add(prepare(source, target, policy, producedByShop));
      }
    }

    // ---- 目标草稿（草稿态：尚未过平台审核）
    List<PreparedItem> migrated = new ArrayList<>();
    for (PreparedItem item : prepared) {
      if (!"MIGRATED".equals(item.state())) continue;
      migrated.add(item);
      item.targetListingId = createTargetListing(item, tenant, actorId);
      item.publishTaskId = null; // 真正的 publish_task 在下面统一入队后回填
    }

    // ---- 待发布任务：复用 4.3.1.2 的 V183 批次与任务（**不另建平行发布任务表**）
    UUID publishBatchId = null;
    if (!migrated.isEmpty()) {
      publishBatchId = Ids.next();
      UUID[] listingIds =
          migrated.stream().map(PreparedItem::targetListingId).toArray(UUID[]::new);
      sql.execute(
          """
          insert into publish_batch
            (id,tenant_id,state,filter_rule,strategy,listing_ids,limit_count,total_count,pending_count,
             dispatched_count,succeeded_count,failed_count,actor_id,note,created_at,updated_at)
          values (?,?,'RUNNING',?::jsonb,?::jsonb,?,?,?,?,0,0,0,?,?,now(),now())
          """,
          publishBatchId,
          tenant,
          writeJson(
              Map.of(
                  "source", "LISTING_BULK_MIGRATION",
                  "migrationBatchId", batchId.toString(),
                  "sourceShopId", filter.sourceShopId().toString(),
                  "idempotencyKey", idempotencyKey)),
          writeJson(
              Map.of(
                  "mode", "MIGRATED_DRAFT",
                  "titleDifferentiation", "APPLIED",
                  "internalDraftId", "",
                  "internalDraftOwner", "4.2.1.1 刊登编辑器 / 4.2.1.2 批量编辑（未交付）")),
          listingIds,
          listingIds.length,
          listingIds.length,
          listingIds.length,
          actorId,
          text(request.note()));
      int position = 0;
      for (PreparedItem item : migrated) {
        UUID taskId = Ids.next();
        item.publishTaskId = taskId;
        sql.execute(
            """
            insert into publish_task
              (id,tenant_id,batch_id,listing_id,draft_id,platform_id,shop_id,position,state,attempts,
               created_at,updated_at)
            values (?,?,?,?,null,?,?,?,'PENDING',0,now(),now())
            """,
            taskId,
            tenant,
            publishBatchId,
            item.targetListingId(),
            item.platformId(),
            item.targetShopId(),
            position);
        position++;
      }
    }

    // ---- 搬迁结果明细 + 差异化留痕 + 风险评估
    int migratedCount = 0;
    int skippedCount = 0;
    int failedCount = 0;
    for (PreparedItem item : prepared) {
      UUID itemId = Ids.next();
      sql.execute(
          """
          insert into listing_migration_item
            (id,tenant_id,batch_id,source_listing_id,source_shop_id,target_shop_id,target_marketplace_id,
             target_listing_id,source_title,target_title,state,skip_reason,failure_reason,duplicate_level,
             duplicate_score,publish_task_id,actor_id,created_at,updated_at)
          values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now(),now())
          """,
          itemId,
          tenant,
          batchId,
          item.source().listingId(),
          item.sourceShopId(),
          item.targetShopId(),
          item.targetMarketplaceId(),
          item.targetListingId(),
          item.source().title(),
          item.targetTitle(),
          item.state(),
          item.skipReason(),
          item.failureReason(),
          item.assessment().level(),
          item.assessment().score(),
          item.publishTaskId(),
          actorId);

      // 差异化留痕：逐条规则留痕（原值 → 每一步之后的值）
      int seq = 0;
      for (DiffStep step : item.titleSteps()) {
        insertDiff(tenant, itemId, "TITLE", seq++, step, actorId);
      }
      if (item.bulletStep() != null) insertDiff(tenant, itemId, "BULLETS", seq++, item.bulletStep(), actorId);
      if (item.descriptionStep() != null) {
        insertDiff(tenant, itemId, "DESCRIPTION", seq++, item.descriptionStep(), actorId);
      }

      // 风险评估：跳过与搬迁的项都要留评估（"为什么没搬"和"风险多高"是一回事）
      insertAssessment(tenant, itemId, batchId, item, actorId);

      switch (item.state()) {
        case "MIGRATED" -> migratedCount++;
        case "SKIPPED" -> skippedCount++;
        default -> failedCount++;
      }
    }

    String state = failedCount > 0 && migratedCount == 0 ? "FAILED" : (skippedCount + failedCount > 0 ? "PARTIAL" : "EXECUTED");
    sql.execute(
        """
        update listing_migration_batch
           set state=?, total_count=?, migrated_count=?, skipped_count=?, failed_count=?,
               publish_batch_id=?, updated_at=now()
         where tenant_id=? and id=?
        """,
        state,
        prepared.size(),
        migratedCount,
        skippedCount,
        failedCount,
        publishBatchId,
        tenant,
        batchId);

    return loadBatch(batchId);
  }

  /**
   * 一次搬迁准备的中间结果。
   *
   * <p>刻意写成**可变**的类而不是 record：目标草稿 id 与待发布任务 id 是在准备之后才产生的
   * （要先建草稿、再排发布队列），record 的不可变语义在这里会逼出一堆 copy 出来的新实例。
   */
  private static final class PreparedItem {
    final SourceContent source;
    final UUID sourceShopId;
    final UUID targetShopId;
    final UUID targetMarketplaceId;
    final UUID platformId;
    final String state;
    final String targetTitle;
    final String skipReason;
    final String failureReason;
    final DuplicateAssessment assessment;
    final List<DiffStep> titleSteps;
    final DiffStep bulletStep;
    final DiffStep descriptionStep;
    UUID targetListingId;
    UUID publishTaskId;

    PreparedItem(
        SourceContent source,
        UUID sourceShopId,
        UUID targetShopId,
        UUID targetMarketplaceId,
        UUID platformId,
        String state,
        String targetTitle,
        String skipReason,
        String failureReason,
        DuplicateAssessment assessment,
        List<DiffStep> titleSteps,
        DiffStep bulletStep,
        DiffStep descriptionStep,
        UUID targetListingId,
        UUID publishTaskId) {
      this.source = source;
      this.sourceShopId = sourceShopId;
      this.targetShopId = targetShopId;
      this.targetMarketplaceId = targetMarketplaceId;
      this.platformId = platformId;
      this.state = state;
      this.targetTitle = targetTitle;
      this.skipReason = skipReason;
      this.failureReason = failureReason;
      this.assessment = assessment;
      this.titleSteps = titleSteps;
      this.bulletStep = bulletStep;
      this.descriptionStep = descriptionStep;
      this.targetListingId = targetListingId;
      this.publishTaskId = publishTaskId;
    }

    SourceContent source() {
      return source;
    }

    UUID sourceShopId() {
      return sourceShopId;
    }

    UUID targetShopId() {
      return targetShopId;
    }

    UUID targetMarketplaceId() {
      return targetMarketplaceId;
    }

    UUID platformId() {
      return platformId;
    }

    String targetTitle() {
      return targetTitle;
    }

    String skipReason() {
      return skipReason;
    }

    String failureReason() {
      return failureReason;
    }

    DuplicateAssessment assessment() {
      return assessment;
    }

    List<DiffStep> titleSteps() {
      return titleSteps;
    }

    DiffStep bulletStep() {
      return bulletStep;
    }

    DiffStep descriptionStep() {
      return descriptionStep;
    }

    UUID targetListingId() {
      return targetListingId;
    }

    UUID publishTaskId() {
      return publishTaskId;
    }

    String state() {
      return state;
    }
  }

  /**
   * 决定这一对（源商品 × 目标店铺）的处置。
   *
   * <p>顺序：已搬迁 → 已被认领 → 重复度策略 → 搬迁。
   */
  private PreparedItem prepare(
      SourceContent source,
      TargetContext target,
      DuplicatePolicy policy,
      Map<UUID, Set<String>> producedByShop) {
    UUID tenant = currentTenant();
    Set<String> produced =
        producedByShop.computeIfAbsent(target.shopId(), key -> new java.util.HashSet<>());
    Set<String> existing = existingTitles(target.shopId());

    TitlePlan plan;
    try {
      plan = differentiateTitle(source, target, policy.titleTemplate(), existing, produced);
    } catch (ResponseStatusException e) {
      DuplicateAssessment assessment =
          assessDuplicate(source, target.shopId(), policy.maxComparables());
      return new PreparedItem(
          source,
          sourceShopId(source),
          target.shopId(),
          target.marketplaceId(),
          target.platformId(),
          "FAILED",
          "",
          null,
          e.getMessage() == null ? "标题差异化失败" : e.getMessage(),
          assessment,
          List.of(),
          null,
          null,
          null,
          null);
    }
    produced.add(normalizeTitle(plan.differentiated()));

    DuplicateAssessment assessment = assessDuplicate(source, target.shopId(), policy.maxComparables());

    // ---- 已搬迁
    if (alreadyMigrated(source.listingId(), target.shopId())) {
      return new PreparedItem(
          source,
          sourceShopId(source),
          target.shopId(),
          target.marketplaceId(),
          target.platformId(),
          "SKIPPED",
          plan.differentiated(),
          "ALREADY_MIGRATED：这一对（源商品 × 目标店铺）已经搬迁过，搬第二次会形成重复 listing。",
          null,
          assessment,
          plan.steps(),
          null,
          null,
          null,
          null);
    }
    // ---- 已被 4.4.2.1 认领过去
    if (alreadyClaimed(source.listingId(), target.shopId())) {
      return new PreparedItem(
          source,
          sourceShopId(source),
          target.shopId(),
          target.marketplaceId(),
          target.platformId(),
          "SKIPPED",
          plan.differentiated(),
          "ALREADY_CLAIMED：4.4.2.1 已经把这个商品认领到该店铺了，重复搬迁会造成重复铺货。",
          null,
          assessment,
          plan.steps(),
          null,
          null,
          null,
          null);
    }
    // ---- 重复度策略
    if (policy.skips(assessment.level())) {
      return new PreparedItem(
          source,
          sourceShopId(source),
          target.shopId(),
          target.marketplaceId(),
          target.platformId(),
          "SKIPPED",
          plan.differentiated(),
          "DUPLICATE_RISK：重复铺货风险 " + assessment.level() + "（分值 " + assessment.score()
              + "），达到本次控制参数的上限 " + policy.maxRiskLevel() + " ⇒ 本次不搬。",
          null,
          assessment,
          plan.steps(),
          null,
          null,
          null,
          null);
    }

    DiffStep bulletStep = differentiateBullets(source, target);
    DiffStep descriptionStep = differentiateDescription(source, target);
    return new PreparedItem(
        source,
        sourceShopId(source),
        target.shopId(),
        target.marketplaceId(),
        target.platformId(),
        "MIGRATED",
        plan.differentiated(),
        null,
        null,
        assessment,
        plan.steps(),
        bulletStep,
        descriptionStep,
        null,
        null);
  }

  private UUID sourceShopId(SourceContent source) {
    return uuidOf(
        """
        select shop_id from listing
        where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid and id=?
        """,
        source.listingId());
  }

  /** 目标草稿：草稿态、尚未过平台审核、平台事实全部为 NULL。 */
  private UUID createTargetListing(PreparedItem item, UUID tenant, UUID actorId) {
    UUID targetListingId = Ids.next();
    String targetExternalItemId = "MIGRATION-PENDING:" + targetListingId;
    sql.execute(
        """
        insert into listing
          (id,tenant_id,shop_id,marketplace_id,external_item_id,title,category_path,
           platform_category_id,status,review_status,review_message,platform_stock_state,
           platform_stock_qty,rating,rating_count,currency,launched_at,last_synced_at,
           created_at,updated_at)
        values (?,?,?,?,?,?,?,?,'DRAFT','NOT_SUBMITTED','','UNKNOWN',
                null,null,null,null,null,null,now(),now())
        """,
        targetListingId,
        tenant,
        item.targetShopId(),
        item.targetMarketplaceId(),
        targetExternalItemId,
        item.targetTitle(),
        item.source().categoryPath(),
        item.source().platformCategoryId());
    // 内容快照：差异化后的标题 / 卖点 / 描述（V175 既有事实表，不是新表）
    UUID snapshotId = Ids.next();
    sql.execute(
        """
        insert into listing_content_snapshot
          (id,tenant_id,listing_id,language,title,bullet_points,description,images,video_url,
           a_plus_modules,category_attributes,compliance_statements,source,is_current,
           captured_at,created_at,updated_at)
        values (?,?,?,?,?,?::jsonb,?,?::jsonb,'',?::jsonb,?::jsonb,?::jsonb,'MANUAL',true,now(),now(),now())
        """,
        snapshotId,
        tenant,
        targetListingId,
        item.source().language().isEmpty() ? "en-US" : item.source().language(),
        item.targetTitle(),
        item.bulletStep() == null ? "[]" : item.bulletStep().valueAfter(),
        item.descriptionStep() == null ? "" : item.descriptionStep().valueAfter(),
        "[]",
        "[]",
        item.source().attributes() == null ? "{}" : item.source().attributes().toString(),
        "{}");
    // 变体与映射：复用源的内部 SKU 逻辑 id（03 商品主数据的跨服务纪律：不建 FK、不复制 catalog 任何一列）
    copyVariantsAndMappings(tenant, item.source().listingId(), targetListingId);
    return targetListingId;
  }

  /** 复制源 listing 的变体与映射到目标 listing（照抄 4.4.2.1 的同一手法）。 */
  private void copyVariantsAndMappings(UUID tenant, UUID sourceListingId, UUID targetListingId) {
    List<Record> variants =
        sql.fetch(
            """
            select id, external_variant_id, attributes, mapping_status, internal_sku_id,
                   component_quantity
            from listing_variant where tenant_id=? and listing_id=? order by id
            """,
            tenant,
            sourceListingId);
    List<Record> mappings =
        sql.fetch(
            """
            select m.listing_variant_id, m.relation_type, m.mapping_status, m.internal_sku_id,
                   m.component_quantity
            from listing_mapping m
            where m.tenant_id=? and m.listing_id=? and m.status='ACTIVE'
            """,
            tenant,
            sourceListingId);
    Map<UUID, Record> mappingByVariant = new LinkedHashMap<>();
    for (Record mapping : mappings) {
      mappingByVariant.put(mapping.get("listing_variant_id", UUID.class), mapping);
    }
    for (Record variant : variants) {
      UUID newVariantId = Ids.next();
      sql.execute(
          """
          insert into listing_variant
            (id,tenant_id,listing_id,external_variant_id,attributes,mapping_status,internal_sku_id,
             component_quantity,created_at,updated_at)
          values (?,?,?,?,?::jsonb,'UNMAPPED',null,null,now(),now())
          """,
          newVariantId,
          tenant,
          targetListingId,
          variant.get("external_variant_id", String.class),
          jsonText(variant.get("attributes")));
      Record mapping = mappingByVariant.get(variant.get("id", UUID.class));
      String relationType;
      String mappingStatus;
      UUID internalSkuId;
      BigDecimal quantity;
      if (mapping != null) {
        relationType = mapping.get("relation_type", String.class);
        mappingStatus = mapping.get("mapping_status", String.class);
        internalSkuId = mapping.get("internal_sku_id", UUID.class);
        quantity = mapping.get("component_quantity", BigDecimal.class);
      } else {
        mappingStatus = variant.get("mapping_status", String.class);
        if (mappingStatus == null || "UNMAPPED".equals(mappingStatus)) continue;
        relationType = "BUNDLE".equals(mappingStatus) ? "ONE_TO_MANY" : "ONE_TO_ONE";
        internalSkuId = variant.get("internal_sku_id", UUID.class);
        quantity = variant.get("component_quantity", BigDecimal.class);
      }
      UUID newMappingId = Ids.next();
      sql.execute(
          """
          insert into listing_mapping
            (id,tenant_id,listing_id,listing_variant_id,relation_type,status,mapping_status,
             internal_sku_id,component_quantity,current_version_id,version,created_at,updated_at)
          values (?,?,?,?,?,'ACTIVE',?,?,?,null,0,now(),now())
          """,
          newMappingId,
          tenant,
          targetListingId,
          newVariantId,
          relationType,
          mappingStatus,
          internalSkuId,
          quantity);
      UUID newVersionId = Ids.next();
      sql.execute(
          """
          insert into listing_mapping_version
            (id,tenant_id,mapping_id,version_no,relation_type,mapping_status,internal_sku_id,
             component_quantity,effective_from,effective_to,change_reason,change_source,created_at)
          values (?,?,?,1,?,?,?,?,?::timestamptz,null,?,'MANUAL',now())
          """,
          newVersionId,
          tenant,
          newMappingId,
          relationType,
          mappingStatus,
          internalSkuId,
          quantity,
          transactionNow(),
          "批量搬迁自源 listing " + sourceListingId + "（4.4.2.2 批量搬迁）");
      sql.execute(
          "update listing_mapping set current_version_id=? where tenant_id=? and id=?",
          newVersionId,
          tenant,
          newMappingId);
    }
  }

  private void insertDiff(UUID tenant, UUID itemId, String field, int seq, DiffStep step, UUID actorId) {
    sql.execute(
        """
        insert into listing_migration_content_diff
          (id,tenant_id,item_id,field,seq,source_value,target_value,rule_id,rule_params,actor_id,
           created_at,updated_at)
        values (?,?,?,?,?,?,?,?,?::jsonb,?,now(),now())
        """,
        Ids.next(),
        tenant,
        itemId,
        field,
        seq,
        step.valueBefore(),
        step.valueAfter(),
        step.ruleId(),
        writeJson(
            Map.of(
                "storeName", nullToEmpty(step.params().storeName()),
                "shopCode", nullToEmpty(step.params().shopCode()),
                "marketplaceCode", nullToEmpty(step.params().marketplaceCode()),
                "template", nullToEmpty(step.params().template()),
                "sequence", step.params().sequence() == null ? "" : step.params().sequence(),
                "reason", nullToEmpty(step.params().reason()))),
        actorId);
  }

  private void insertAssessment(
      UUID tenant, UUID itemId, UUID batchId, PreparedItem item, UUID actorId) {
    DuplicateAssessment assessment = item.assessment();
    MigrationDuplicateBasis basis = assessment.basis();
    sql.execute(
        """
        insert into listing_migration_duplicate
          (id,tenant_id,item_id,source_listing_id,target_shop_id,score,level,comparable_listing_id,
           comparable_title,comparable_count,title_similarity,category_similarity,attribute_similarity,
           basis,evaluated_at,created_at,updated_at)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,now(),now(),now())
        """,
        Ids.next(),
        tenant,
        itemId,
        item.source().listingId(),
        item.targetShopId(),
        assessment.score(),
        assessment.level(),
        assessment.comparableListingId(),
        assessment.comparableTitle(),
        assessment.comparableCount(),
        assessment.titleSimilarity(),
        assessment.categorySimilarity(),
        assessment.attributeSimilarity(),
        writeJson(
            map(
                "evaluatedTitle", nullToEmpty(basis.evaluatedTitle()),
                "titleWeight", basis.titleWeight(),
                "categoryWeight", basis.categoryWeight(),
                "attributeWeight", basis.attributeWeight(),
                "criticalThreshold", basis.criticalThreshold(),
                "highThreshold", basis.highThreshold(),
                "mediumThreshold", basis.mediumThreshold(),
                "comparableCount", basis.comparableCount(),
                "bestListingId", basis.bestListingId() == null ? "" : basis.bestListingId().toString(),
                "bestTitle", nullToEmpty(basis.bestTitle()),
                "sharedBigrams", basis.sharedBigrams() == null ? 0 : basis.sharedBigrams(),
                "unionBigrams", basis.unionBigrams() == null ? 0 : basis.unionBigrams(),
                "matchedAttributes", basis.matchedAttributes(),
                "conflictingAttributes", basis.conflictingAttributes(),
                "explanation", nullToEmpty(basis.explanation()))),
        actorId);
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  // ==================================================================
  // 重复度控制参数
  // ==================================================================

  /** 归一化后的重复度控制参数。 */
  private record DuplicatePolicy(
      String maxRiskLevel, String onUnknown, int maxComparables, String titleTemplate) {
    boolean skips(String level) {
      if ("UNKNOWN".equals(level)) return "SKIP".equals(onUnknown);
      if ("NONE".equals(maxRiskLevel)) return false;
      return rank(level) >= rank(maxRiskLevel);
    }
  }

  private static int rank(String level) {
    return switch (level) {
      case "CRITICAL" -> 4;
      case "HIGH" -> 3;
      case "MEDIUM" -> 2;
      case "LOW" -> 1;
      default -> 0;
    };
  }

  private DuplicatePolicy normalizePolicy(MigrationDuplicatePolicy policy) {
    if (policy == null) {
      return new DuplicatePolicy("HIGH", "PROCEED", DEFAULT_MAX_COMPARABLES, null);
    }
    String maxRiskLevel = text(policy.maxRiskLevel()).toUpperCase(Locale.ROOT);
    if (maxRiskLevel.isEmpty()) maxRiskLevel = "HIGH";
    if (!MAX_RISK_LEVELS.contains(maxRiskLevel)) {
      throw unprocessable(
          "未知的重复度控制参数：" + policy.maxRiskLevel() + "。可选：" + String.join(" / ", MAX_RISK_LEVELS));
    }
    String onUnknown = text(policy.onUnknown()).toUpperCase(Locale.ROOT);
    if (onUnknown.isEmpty()) onUnknown = "PROCEED";
    if (!UNKNOWN_RISK_ACTIONS.contains(onUnknown)) {
      throw unprocessable(
          "未知的未知风险处置：" + policy.onUnknown() + "。可选：" + String.join(" / ", UNKNOWN_RISK_ACTIONS));
    }
    int maxComparables =
        policy.maxComparables() == null ? DEFAULT_MAX_COMPARABLES : Math.max(1, Math.min(policy.maxComparables(), 1000));
    String template = blankToNull(policy.titleTemplate());
    if (template != null && template.length() > 200) {
      throw unprocessable("标题模板不能超过 200 个字符。");
    }
    return new DuplicatePolicy(maxRiskLevel, onUnknown, maxComparables, template);
  }

  // ==================================================================
  // 幂等键
  // ==================================================================

  /**
   * 幂等键：调用方给了就用；没给就按请求内容派生。
   *
   * <p>派生口径（规范化后取摘要）：源店铺 + 目标店铺集合（排序）+ 筛选规则 + 重复度参数。
   * ⇒ 同一个请求重复提交天然幂等。
   */
  private String idempotencyKey(
      MigrationExecuteRequest request,
      NormalizedFilter filter,
      List<TargetContext> targets,
      DuplicatePolicy policy) {
    String explicit = blankToNull(request.idempotencyKey());
    if (explicit != null) return explicit;
    Map<String, String> canonical = new TreeMap<>();
    canonical.put("sourceShopId", filter.sourceShopId().toString());
    canonical.put(
        "targets",
        targets.stream()
            .map(t -> t.shopId() + "@" + t.marketplaceId())
            .sorted()
            .reduce((a, b) -> a + "," + b)
            .orElse(""));
    canonical.put("categoryPaths", String.join("|", filter.categoryPaths()));
    canonical.put("platformCategoryIds", String.join("|", filter.platformCategoryIds()));
    canonical.put("priceMin", filter.priceMin() == null ? "" : filter.priceMin().toPlainString());
    canonical.put("priceMax", filter.priceMax() == null ? "" : filter.priceMax().toPlainString());
    canonical.put("salesMin", filter.salesMin() == null ? "" : filter.salesMin().toPlainString());
    canonical.put("salesMax", filter.salesMax() == null ? "" : filter.salesMax().toPlainString());
    canonical.put("unknownPolicy", filter.unknownPolicy());
    canonical.put("maxRiskLevel", policy.maxRiskLevel());
    canonical.put("onUnknown", policy.onUnknown());
    canonical.put("titleTemplate", policy.titleTemplate() == null ? "" : policy.titleTemplate());
    String payload = writeJson(canonical);
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : hash) hex.append(String.format("%02x", b));
      return "derived:" + hex;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ==================================================================
  // 读取：批次 / 明细 / 风险 / 已搬迁
  // ==================================================================

  /** 批次列表（真分页 + 状态与源店铺筛选）。 */
  public List<ListingMigrationBatchRow> listBatches(String state, String sourceShopCode, int page, int size) {
    int limit = clampSize(size);
    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    where.add("b.tenant_id=?");
    binds.add(currentTenant());
    if (hasText(state)) {
      where.add("b.state=?");
      binds.add(text(state).toUpperCase(Locale.ROOT));
    }
    if (hasText(sourceShopCode)) {
      where.add("s.code=?");
      binds.add(text(sourceShopCode));
    }
    List<Record> rows =
        sql.fetch(
            """
            select b.*, s.code as shop_code, s.name as shop_name
            from listing_migration_batch b
            join shop s on s.tenant_id=b.tenant_id and s.id=b.source_shop_id
            where"""
                + " "
                + String.join(" and ", where)
                + " order by b.created_at desc, b.id desc limit ? offset ?",
            pageBinds(binds, limit, (long) Math.max(0, page) * limit));
    return rows.stream().map(this::batchRow).toList();
  }

  private ListingMigrationBatchRow batchRow(Record row) {
    UUID[] targets = row.get("target_shop_ids", UUID[].class);
    List<String> detail = new ArrayList<>();
    try {
      JsonNode node = json.readTree(jsonText(row.get("target_detail")));
      if (node != null && node.isArray()) {
        for (JsonNode element : node) {
          detail.add(
              element.path("shopCode").asText("")
                  + " / "
                  + element.path("marketplaceCode").asText("")
                  + "（"
                  + element.path("shopName").asText("")
                  + "）");
        }
      }
    } catch (Exception e) {
      // 目标明细解析失败不该让整行读不出来：原文仍在 target_detail 里
      detail.add(jsonText(row.get("target_detail")));
    }
    return new ListingMigrationBatchRow(
        row.get("id", UUID.class),
        row.get("idempotency_key", String.class),
        row.get("source_shop_id", UUID.class),
        row.get("shop_code", String.class),
        row.get("shop_name", String.class),
        targets == null ? List.of() : List.of(targets),
        detail,
        jsonText(row.get("filter_rule")),
        jsonText(row.get("duplicate_policy")),
        row.get("state", String.class),
        row.get("candidate_count", Integer.class),
        row.get("total_count", Integer.class),
        row.get("migrated_count", Integer.class),
        row.get("skipped_count", Integer.class),
        row.get("failed_count", Integer.class),
        row.get("publish_batch_id", UUID.class),
        row.get("actor_id", UUID.class),
        row.get("note", String.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  /** 单个批次详情。 */
  public ListingMigrationBatchRow batch(String rawId) {
    UUID tenant = currentTenant();
    Record row =
        sql.fetchOne(
            """
            select b.*, s.code as shop_code, s.name as shop_name
            from listing_migration_batch b
            join shop s on s.tenant_id=b.tenant_id and s.id=b.source_shop_id
            where b.tenant_id=? and b.id=?
            """,
            tenant,
            parseId(rawId));
    if (row == null) throw notFound();
    return batchRow(row);
  }

  private ListingMigrationBatchRow loadBatch(UUID batchId) {
    return batch(batchId.toString());
  }

  /** 搬迁结果明细（真分页 + 状态筛选），带差异化留痕与风险依据。 */
  public List<ListingMigrationItemRow> items(String rawBatchId, String state, int page, int size) {
    UUID tenant = currentTenant();
    UUID batchId = parseId(rawBatchId);
    int limit = clampSize(size);
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    binds.add(batchId);
    StringBuilder where = new StringBuilder("i.tenant_id=? and i.batch_id=?");
    if (hasText(state)) {
      where.append(" and i.state=?");
      binds.add(text(state).toUpperCase(Locale.ROOT));
    }
    List<Record> rows =
        sql.fetch(
            """
            select i.*, s.code as shop_code, s.name as shop_name, l.external_item_id as source_external_item_id
            from listing_migration_item i
            join shop s on s.tenant_id=i.tenant_id and s.id=i.target_shop_id
            join listing l on l.tenant_id=i.tenant_id and l.id=i.source_listing_id
            where"""
                + " "
                + where
                + " order by i.created_at desc, i.id desc limit ? offset ?",
            pageBinds(binds, limit, (long) Math.max(0, page) * limit));
    return itemRows(rows);
  }

  private List<ListingMigrationItemRow> itemRows(List<Record> rows) {
    if (rows.isEmpty()) return List.of();
    UUID tenant = currentTenant();
    List<UUID> itemIds = rows.stream().map(r -> r.get("id", UUID.class)).toList();
    String placeholders =
        String.join(",", java.util.Collections.nCopies(itemIds.size(), "?"));
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    binds.addAll(itemIds);

    Map<UUID, List<ListingMigrationContentDiffRow>> diffs = new LinkedHashMap<>();
    List<Record> diffRows =
        sql.fetch(
            """
            select id,item_id,field,seq,source_value,target_value,rule_id,rule_params,actor_id,created_at
            from listing_migration_content_diff
            where tenant_id=? and item_id in ("""
                + placeholders
                + ") order by item_id, field, seq",
            binds.toArray());
    for (Record diff : diffRows) {
      diffs.computeIfAbsent(diff.get("item_id", UUID.class), key -> new ArrayList<>())
          .add(
              new ListingMigrationContentDiffRow(
                  diff.get("field", String.class),
                  diff.get("source_value", String.class),
                  diff.get("target_value", String.class),
                  diff.get("rule_id", String.class),
                  ruleParams(diff.get("rule_params")),
                  diff.get("actor_id", UUID.class),
                  diff.get("created_at", OffsetDateTime.class)));
    }

    Map<UUID, Record> assessments = new LinkedHashMap<>();
    List<Record> assessmentRows =
        sql.fetch(
            """
            select * from listing_migration_duplicate
            where tenant_id=? and item_id in ("""
                + placeholders
                + ") order by item_id",
            binds.toArray());
    for (Record assessment : assessmentRows) {
      assessments.put(assessment.get("item_id", UUID.class), assessment);
    }

    List<ListingMigrationItemRow> out = new ArrayList<>();
    for (Record row : rows) {
      UUID itemId = row.get("id", UUID.class);
      Record assessment = assessments.get(itemId);
      UUID targetListingId = row.get("target_listing_id", UUID.class);
      String targetExternalItemId = "";
      if (targetListingId != null) {
        targetExternalItemId =
            one(
                String.class,
                "select external_item_id from listing where tenant_id=? and id=?",
                tenant,
                targetListingId);
      }
      out.add(
          new ListingMigrationItemRow(
              itemId,
              row.get("batch_id", UUID.class),
              row.get("source_listing_id", UUID.class),
              row.get("source_title", String.class),
              row.get("source_external_item_id", String.class),
              row.get("source_shop_id", UUID.class),
              row.get("shop_code", String.class),
              row.get("target_shop_id", UUID.class),
              row.get("shop_code", String.class),
              row.get("shop_name", String.class),
              row.get("target_marketplace_id", UUID.class),
              marketplaceCode(row.get("target_marketplace_id", UUID.class)),
              targetListingId,
              targetExternalItemId == null ? "" : targetExternalItemId,
              row.get("source_title", String.class),
              row.get("target_title", String.class),
              !normalizeTitle(row.get("source_title", String.class))
                  .equals(normalizeTitle(row.get("target_title", String.class))),
              row.get("state", String.class),
              row.get("skip_reason", String.class),
              row.get("failure_reason", String.class),
              row.get("duplicate_level", String.class),
              row.get("duplicate_score", BigDecimal.class),
              assessment == null ? null : basis(assessment),
              row.get("publish_task_id", UUID.class),
              row.get("actor_id", UUID.class),
              row.get("created_at", OffsetDateTime.class),
              diffs.getOrDefault(itemId, List.of())));
    }
    return out;
  }

  private MigrationRuleParams ruleParams(Object raw) {
    JsonNode node = jsonNode(raw);
    if (node == null) return new MigrationRuleParams("", "", "", "", null, "");
    String sequence = node.path("sequence").asText("");
    Integer seq = sequence.isEmpty() ? null : Integer.valueOf(sequence);
    return new MigrationRuleParams(
        node.path("storeName").asText(""),
        node.path("shopCode").asText(""),
        node.path("marketplaceCode").asText(""),
        node.path("template").asText(""),
        seq,
        node.path("reason").asText(""));
  }

  private MigrationDuplicateBasis basis(Record row) {
    JsonNode node = jsonNode(row.get("basis"));
    List<String> matched = new ArrayList<>();
    List<String> conflicting = new ArrayList<>();
    if (node != null) {
      for (JsonNode element : node.path("matchedAttributes")) matched.add(element.asText());
      for (JsonNode element : node.path("conflictingAttributes")) conflicting.add(element.asText());
    }
    return new MigrationDuplicateBasis(
        node == null ? "" : node.path("evaluatedTitle").asText(""),
        node == null ? 0 : node.path("titleWeight").asDouble(),
        node == null ? 0 : node.path("categoryWeight").asDouble(),
        node == null ? 0 : node.path("attributeWeight").asDouble(),
        node == null ? 0 : node.path("criticalThreshold").asDouble(),
        node == null ? 0 : node.path("highThreshold").asDouble(),
        node == null ? 0 : node.path("mediumThreshold").asDouble(),
        node == null ? 0 : node.path("comparableCount").asInt(),
        node == null || node.path("bestListingId").asText("").isEmpty()
            ? null
            : UUID.fromString(node.path("bestListingId").asText()),
        node == null ? "" : node.path("bestTitle").asText(""),
        row.get("title_similarity", BigDecimal.class),
        row.get("category_similarity", BigDecimal.class),
        row.get("attribute_similarity", BigDecimal.class),
        node == null ? 0 : node.path("sharedBigrams").asInt(),
        node == null ? 0 : node.path("unionBigrams").asInt(),
        matched,
        conflicting,
        node == null ? "" : node.path("explanation").asText(""));
  }

  private String marketplaceCode(UUID marketplaceId) {
    if (marketplaceId == null) return "";
    String code = one(String.class, "select code from marketplace where id=?", marketplaceId);
    return code == null ? "" : code;
  }

  /** 「重复铺货风险」查询（与「已搬迁」分开：两种事实、两种处置）。 */
  public List<ListingMigrationDuplicateRow> duplicateRisks(
      String level, UUID targetShopId, int page, int size) {
    int limit = clampSize(size);
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    StringBuilder where = new StringBuilder("d.tenant_id=?");
    if (hasText(level)) {
      where.append(" and d.level=?");
      binds.add(text(level).toUpperCase(Locale.ROOT));
    }
    if (targetShopId != null) {
      where.append(" and d.target_shop_id=?");
      binds.add(targetShopId);
    }
    List<Record> rows =
        sql.fetch(
            """
            select d.*, i.batch_id, i.source_title, s.code as shop_code
            from listing_migration_duplicate d
            join listing_migration_item i on i.tenant_id=d.tenant_id and i.id=d.item_id
            join shop s on s.tenant_id=d.tenant_id and s.id=d.target_shop_id
            where"""
                + " "
                + where
                + " order by d.evaluated_at desc, d.id desc limit ? offset ?",
            pageBinds(binds, limit, (long) Math.max(0, page) * limit));
    List<ListingMigrationDuplicateRow> out = new ArrayList<>();
    for (Record row : rows) {
      out.add(
          new ListingMigrationDuplicateRow(
              row.get("id", UUID.class),
              row.get("item_id", UUID.class),
              row.get("batch_id", UUID.class),
              row.get("source_listing_id", UUID.class),
              row.get("source_title", String.class),
              row.get("target_shop_id", UUID.class),
              row.get("shop_code", String.class),
              row.get("score", BigDecimal.class),
              row.get("level", String.class),
              row.get("comparable_listing_id", UUID.class),
              row.get("comparable_title", String.class),
              row.get("comparable_count", Integer.class),
              row.get("title_similarity", BigDecimal.class),
              row.get("category_similarity", BigDecimal.class),
              row.get("attribute_similarity", BigDecimal.class),
              basis(row),
              row.get("evaluated_at", OffsetDateTime.class)));
    }
    return out;
  }

  /** 「已搬迁」查询（与「重复铺货风险」分开）。 */
  public List<ListingMigrationMigratedRow> migrated(
      UUID sourceListingId, UUID targetShopId, int page, int size) {
    int limit = clampSize(size);
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    StringBuilder where = new StringBuilder("i.tenant_id=? and i.state='MIGRATED'");
    if (sourceListingId != null) {
      where.append(" and i.source_listing_id=?");
      binds.add(sourceListingId);
    }
    if (targetShopId != null) {
      where.append(" and i.target_shop_id=?");
      binds.add(targetShopId);
    }
    List<Record> rows =
        sql.fetch(
            """
            select i.*, s.code as shop_code
            from listing_migration_item i
            join shop s on s.tenant_id=i.tenant_id and s.id=i.target_shop_id
            where"""
                + " "
                + where
                + " order by i.created_at desc, i.id desc limit ? offset ?",
            pageBinds(binds, limit, (long) Math.max(0, page) * limit));
    List<ListingMigrationMigratedRow> out = new ArrayList<>();
    for (Record row : rows) {
      out.add(
          new ListingMigrationMigratedRow(
              row.get("id", UUID.class),
              row.get("batch_id", UUID.class),
              row.get("source_listing_id", UUID.class),
              row.get("source_title", String.class),
              row.get("target_shop_id", UUID.class),
              row.get("shop_code", String.class),
              row.get("target_listing_id", UUID.class),
              row.get("target_title", String.class),
              row.get("duplicate_level", String.class),
              row.get("duplicate_score", BigDecimal.class),
              row.get("created_at", OffsetDateTime.class)));
    }
    return out;
  }

  // ==================================================================
  // 筛选选项（来自真实数据）
  // ==================================================================

  /** 筛选面板的选项：可作为源店铺的店铺 + 源店铺下真实存在的类目。 */
  public MigrationFilterOptions filterOptions(UUID sourceShopId) {
    UUID tenant = currentTenant();
    List<String> shops =
        sql.fetch(
                """
                select code from shop where tenant_id=? order by code
                """,
                tenant)
            .stream()
            .map(row -> row.get("code", String.class))
            .toList();
    List<MigrationCategoryOption> categories = new ArrayList<>();
    if (sourceShopId != null) {
      List<Record> rows =
          sql.fetch(
              """
              select category_path, platform_category_id, count(*) as n
              from listing
              where tenant_id=? and shop_id=?
              group by category_path, platform_category_id
              order by n desc, category_path
              """,
              tenant,
              sourceShopId);
      for (Record row : rows) {
        categories.add(
            new MigrationCategoryOption(
                row.get("category_path", String.class),
                row.get("platform_category_id", String.class),
                row.get("n", Long.class)));
      }
    }
    return new MigrationFilterOptions(shops, categories);
  }
}
