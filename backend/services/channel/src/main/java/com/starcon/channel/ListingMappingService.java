package com.starcon.channel;

import static com.starcon.channel.ListingMappingModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * 4.1.2.1 映射管理。
 *
 * <p>蓝图：「建立并维护"平台变体 → 内部 SKU/套装"的映射，支持一对一、一对多（套装）、多对一；支持批量映射（按规则：SKU 编码匹配、条码匹配、标题相似度推荐但需人工确认）。输入：映射建立操作；匹配规则；批量候选。输出：{@code listing_mapping_version} 记录（当前生效版本 + 历史版本）；未映射清单回流 02 映射缺失队列。关系：上游 = 4.1.1.1、02 同步到的 listing；下游 = 09 订单（订单行按映射解析 SKU）、07 库存（回写可售量）。<b>映射未建立时订单进入待映射状态，绝不按名称猜 SKU</b>（实体目录 §6）。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>建立并维护映射 / 三种基数</b> → {@link #save} + {@link #remove}：{@code ONE_TO_ONE} /
 *       {@code MANY_TO_ONE} 一行一条 SKU；{@code ONE_TO_MANY}（套装）额外必须有
 *       {@code componentQuantity > 0}。多对一有独立的读出口 {@link #skuUsage}。
 *   <li><b>批量映射（三条规则）</b> → {@link #batchCandidates}：候选集<b>真的</b>来自 catalog
 *       授权 API（{@link SkuCandidateQueryPort}），标题相似度由 PostgreSQL {@code pg_trgm} 真算。
 *   <li><b>「需人工确认」</b> → {@link #decideCandidate}：候选落库时<b>不写任何映射行</b>，
 *       只有 CONFIRMED 才建映射并生成版本；DB 层另有 CHECK 钉住标题相似度恒需确认。
 *   <li><b>listing_mapping_version（当前生效版本 + 历史版本）</b> → {@link #load} 的
 *       {@link MappingDetail}；4.1.2.2 的 {@code effectiveFrom} / {@code changeReason} 已写入，
 *       但<b>受影响订单范围</b>不在这里（09 订单未交付，capabilities 里显式 available=false）。
 *   <li><b>未映射清单</b> → {@link #unmapped}；回流目标 02 映射缺失队列（2.4.2.3）未交付，
 *       端点显式返回 backflowDelivered=false 而不是假装已回流。
 * </ul>
 *
 * <p>🔴 蓝图加粗句「映射未建立时订单进入待映射状态，<b>绝不按名称猜 SKU</b>」在代码里是
 * {@link #resolve} 的一个<b>两态返回</b>：没有生效映射就返回 {@code PENDING_MAPPING} 且
 * {@code internalSkuId = null}，<b>没有第三种「疑似匹配」</b>。这与「不建组件表、只指向套装 SKU」
 * 是同一纪律：这个模块<b>只认已建立的映射</b>，任何"看起来像"的推断都不做。
 *
 * <p>🔴 数据来源纪律：平台侧（listing / variant）<b>同库同服务</b>读（V171），
 * 03 的 SKU <b>跨服务只经授权 HTTP API</b>读（{@link SkuCandidateQueryPort}），
 * 本类<b>一条 SQL 都不碰 catalog 的库</b>，也<b>不复制 catalog 的任何一列</b> ——
 * 候选行里的 code / name 是<b>推荐当时的展示快照</b>，不参与匹配计算（见 V176 第三节）。
 */
@Service
public class ListingMappingService {

  /** 三条匹配规则（蓝图职责句逐字对应）。 */
  static final String SKU_CODE = "SKU_CODE";

  static final String BARCODE = "BARCODE";
  static final String TITLE_SIMILARITY = "TITLE_SIMILARITY";

  private static final Set<String> RULES = Set.of(SKU_CODE, BARCODE, TITLE_SIMILARITY);
  private static final Set<String> RELATIONS = Set.of("ONE_TO_ONE", "ONE_TO_MANY", "MANY_TO_ONE");
  private static final Set<String> DECISIONS = Set.of("CONFIRMED", "REJECTED");

  /**
   * 🔴 单次批量候选的硬上限。
   *
   * <p>批量候选要为每个变体向 catalog 拉 SKU 并在本库算相似度，是本模块最重的一个动作。
   * 上限存在的理由不是"跑不动"，而是<b>不做上限就会让"只比对了前 N 条"看起来像"扫完了"</b> ——
   * 超限时 {@code truncated=true} 会一路带回响应，运营看得见。
   */
  static final int MAX_VARIANTS_PER_BATCH = 200;

  /** 从 catalog 单次拉取的候选 SKU 条数上限（见上）。 */
  static final int MAX_CANDIDATE_SKUS = 500;

  /** 标题相似度收录下限：低于此分不作为候选推荐（宁可少推，不要让运营被噪音淹没）。 */
  static final double SIMILARITY_THRESHOLD = 0.25;

  /** 每条 listing 默认返回几个候选。 */
  static final int DEFAULT_TOP_PER_LISTING = 5;

  private static final String TENANT_FILTER =
      " where {alias}.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid";

  /**
   * 映射行的主查询骨架（列表与单条详情共用）。
   *
   * <p>🔴 抽出来是为了让「按 id 取一条」**不依赖列表分页**：早先的实现是取第一页再在
   * 内存里找那一条，映射条数超过单页上限时详情端点会答 422 —— 一条合法的映射因为
   * 「排在第 201 位」而读不到，那是分页实现漏到了详情路径上。
   */
  private static final String MAPPING_FROM =
      " from listing_mapping m"
          + " join listing l on l.tenant_id=m.tenant_id and l.id=m.listing_id"
          + " join shop s on s.tenant_id=l.tenant_id and s.id=l.shop_id"
          + " join listing_variant v on v.tenant_id=m.tenant_id and v.id=m.listing_variant_id";

  private static final String MAPPING_COLUMNS =
      """
      select m.id,m.tenant_id,m.listing_id,m.listing_variant_id,m.relation_type,m.status,
             m.mapping_status,m.internal_sku_id,m.component_quantity,m.current_version_id,
             m.created_at,m.updated_at,
             l.external_item_id,l.title,l.last_synced_at,l.created_at as listing_created_at,
             s.code as shop_code,v.external_variant_id,
             (select v2.version_no from listing_mapping_version v2
               where v2.tenant_id=m.tenant_id and v2.id=m.current_version_id) as current_version_no,
             (select v2.effective_from from listing_mapping_version v2
               where v2.tenant_id=m.tenant_id and v2.id=m.current_version_id) as effective_from,
             (select count(*) from listing_mapping_version v3
               where v3.tenant_id=m.tenant_id and v3.mapping_id=m.id) as version_count,
             (select count(*) from listing_variant v4
                join listing_mapping m4 on m4.tenant_id=v4.tenant_id and m4.listing_variant_id=v4.id
               where v4.tenant_id=m.tenant_id and v4.internal_sku_id=m.internal_sku_id
                 and m4.status='ACTIVE' and v4.id<>m.listing_variant_id) as shared_with_variants
      """;

  private final DSLContext sql;
  private final ObjectMapper json;
  private final SkuCandidateQueryPort skus;

  public ListingMappingService(DSLContext sql, ObjectMapper json, SkuCandidateQueryPort skus) {
    this.sql = sql;
    this.json = json;
    this.skus = skus;
  }

  // ==================================================================
  // 小工具（照抄 ListingService 的同名写法）
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

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
  }

  private static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static String escape(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    return UUID.fromString(String.valueOf(raw));
  }

  private UUID currentActor() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.actor',true),'')");
    return raw == null ? null : UUID.fromString(String.valueOf(raw));
  }

  private static <T> List<T> safe(List<T> values) {
    return values == null ? List.of() : values;
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
   * <p>最关键的一条是 {@code affected_orders}：4.1.2.2 的「按版本回溯受影响订单」需要 09 订单的
   * 真实来源，本仓没有。返回一个空数组或 0 会让人以为"按版本查订单"已经能用了。
   */
  public ListingMappingCapabilities capabilities() {
    return new ListingMappingCapabilities(
        List.of(
            new MappingCapabilityDimension(
                "mapping_crud",
                "映射的建立 / 修改 / 解除（一对一、一对多（套装）、多对一）",
                true,
                "listing_mapping（V176）+ listing_variant（V171）",
                "",
                "没有它，订单行无法把平台变体落到内部 SKU，07 也无从回写可售量"),
            new MappingCapabilityDimension(
                "mapping_version",
                "🔴 映射版本：当前生效版本 + 历史版本（表与生效区间语义）",
                true,
                "listing_mapping_version（V176）",
                "",
                "改映射不留历史就无法回答「这笔订单当时用的是哪条映射」"),
            new MappingCapabilityDimension(
                "affected_orders",
                "🔴 按版本回溯受影响订单范围",
                false,
                "—",
                "4.1.2.2 映射版本",
                "无法回答「改这条映射会波及哪些已产生的订单」；本模块**不返回空数组冒充**——"
                    + "09 订单侧尚无任何表能让这件事有真实来源，建一张空引用表只会让界面看起来已交付"),
            new MappingCapabilityDimension(
                "batch_candidates",
                "批量映射候选（SKU 编码匹配 / 条码匹配 / 标题相似度推荐）",
                true,
                "catalog GET /catalog/skus（跨服务授权 API）+ 本库 pg_trgm similarity()",
                "",
                "没有批量候选，逐条人工建立映射在几百个变体上不可行"),
            new MappingCapabilityDimension(
                "human_confirmation",
                "🔴 标题相似度推荐需人工确认（候选在确认前不写任何映射行）",
                true,
                "listing_mapping_candidate（V176 CHECK）",
                "",
                "自动按相似度落映射 = 正是蓝图禁止的「按名称猜 SKU」"),
            new MappingCapabilityDimension(
                "unmapped_backflow",
                "未映射清单回流 02 映射缺失队列",
                false,
                "listing_variant（V171）+ listing（V171）",
                "2.4.2.3 映射缺失队列",
                "未映射的变体不会自动进入运营的待办队列；本模块提供清单本身"
                    + "（GET /unmapped），回流入队那一跳未交付，端点显式返回 backflowDelivered=false"),
            new MappingCapabilityDimension(
                "order_line_resolve",
                "🔴 订单行按映射解析 SKU（未建立映射时进待映射，绝不猜）",
                true,
                "listing_mapping_version（V176）",
                "",
                "09 订单无法把平台变体落到内部 SKU；本模块先把口径与端点立起来，09 交付后直接接"),
            new MappingCapabilityDimension(
                "bundle_components",
                "套装组件 SKU 逐条明细与可售性校验",
                false,
                "—",
                "4.1.2.3 套装组件映射 + catalog 3.5.1.x",
                "套装只能指到套装 SKU 并记组件数量，逐组件明细与「缺任一组件则整套装不可售」"
                    + "的校验不在本模块；在这里建组件表等于抢下一个模块的写入所有权"),
            new MappingCapabilityDimension(
                "sku_existence_check",
                "内部 SKU 的存在性与租户归属强校验",
                false,
                "—",
                "03 商品 / catalog 侧目录接口",
                "本模块只存 03 的逻辑 id，**跨服务不建 FK**（与 V171、V1 shop.legal_entity_id 同一"
                    + "纪律）；批量候选里的 SKU 来自 catalog 授权 API 因此是可信的，但**手工填的 "
                    + "internalSkuId 无强校验** —— 填一个不存在的 id 会得到一条看起来成功的映射"),
            new MappingCapabilityDimension(
                "many_to_one_reverse",
                "多对一反查：哪些平台变体共用同一个内部 SKU",
                true,
                "listing_mapping（V176）",
                "",
                "无法回答「改这条映射会波及哪几个平台商品」")),
        List.of("MANY_TO_ONE", "ONE_TO_MANY", "ONE_TO_ONE"),
        List.of("BUNDLE", "MAPPED"),
        List.of(BARCODE, SKU_CODE, TITLE_SIMILARITY),
        List.of("CONFIRMED", "PENDING", "REJECTED"),
        List.of("PENDING_MAPPING", "RESOLVED"),
        MAX_VARIANTS_PER_BATCH,
        MAX_CANDIDATE_SKUS,
        SIMILARITY_THRESHOLD,
        "🔴 本批次未交付段：① 「按版本回溯受影响订单」（4.1.2.2）需要 09 订单的真实来源，"
            + "本仓尚无 —— **不返回空数组冒充**；② 「未映射清单回流 02 映射缺失队列」（2.4.2.3）"
            + "未交付，本模块提供清单本身（GET /unmapped）但回流入队那一跳未接，"
            + "端点显式返回 backflowDelivered=false；③ 「套装组件 SKU 逐条明细与可售性校验」归 "
            + "4.1.2.3 与 catalog 3.5.1.x，本模块只把平台变体指向套装 SKU 并记组件数量；"
            + "④ 手工填写的 internalSkuId **没有跨服务存在性强校验**（跨服务不建 FK），"
            + "填错会得到一条看起来成功的映射 —— 这条能力缺失必须显示出来而不是藏起来。",
        "上游：4.1.1.1 的 listing / listing_variant（channel V171，**同库同服务**读，零复制）。"
            + "03 商品的 SKU **跨服务只经 catalog 授权 HTTP API 读**（GET /catalog/skus，"
            + "转发调用方自己的 bearer，catalog 侧仍按已认证成员关系解析租户并强制 RLS），"
            + "本模块一条 SQL 都不碰 catalog 的库、不复制它的任何一列；候选行里的 code / name "
            + "是推荐当时的**展示快照**，不参与匹配计算。下游：09 订单（本模块交付 /resolve 作为"
            + "解析入口，未建立映射时进待映射状态）、07 库存（按映射回写可售量，跨服务不写）。");
  }

  public MappingFilterOptions filterOptions() {
    return new MappingFilterOptions(
        RELATIONS.stream().sorted().toList(),
        List.of("BUNDLE", "MAPPED"),
        List.of("ACTIVE", "REMOVED"),
        RULES.stream().sorted().toList(),
        List.of("CONFIRMED", "PENDING", "REJECTED"),
        List.of("RECENT", "VERSION", "SKEW"),
        MAX_VARIANTS_PER_BATCH);
  }

  // ==================================================================
  // 映射 CRUD
  // ==================================================================

  /** 分页列表。筛选维度全部来自职责句与列表页需要：基数 / 状态 / 存续 / SKU / 店铺 / 关键词。 */
  public MappingPage list(
      String relationType,
      String mappingStatus,
      String status,
      String internalSkuId,
      String shopCode,
      String search,
      String sort,
      int page,
      int size) {

    List<Object> binds = new ArrayList<>();
    StringBuilder where =
        new StringBuilder(
            MAPPING_FROM
                + " where m.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid");

    String relation = blankToNull(relationType);
    if (relation != null) {
      if (!RELATIONS.contains(relation)) {
        throw unprocessable(
            "未知的映射基数：" + relation + "。可选：" + String.join(" / ", RELATIONS.stream().sorted().toList()));
      }
      where.append(" and m.relation_type = ?");
      binds.add(relation);
    }

    String mstatus = blankToNull(mappingStatus);
    if (mstatus != null) {
      if (!Set.of("MAPPED", "BUNDLE", "UNMAPPED").contains(mstatus)) {
        throw unprocessable("未知的映射状态：" + mstatus + "。可选：MAPPED / BUNDLE / UNMAPPED");
      }
      where.append(" and m.mapping_status = ?");
      binds.add(mstatus);
    }

    String st = blankToNull(status);
    if (st != null) {
      if (!Set.of("ACTIVE", "REMOVED").contains(st)) {
        throw unprocessable("未知的映射存续状态：" + st + "。可选：ACTIVE / REMOVED");
      }
      where.append(" and m.status = ?");
      binds.add(st);
    }

    String sku = blankToNull(internalSkuId);
    if (sku != null) {
      where.append(" and m.internal_sku_id = ?::uuid");
      binds.add(sku);
    }

    String shop = blankToNull(shopCode);
    if (shop != null) {
      where.append(" and s.code = ?");
      binds.add(shop);
    }

    String q = blankToNull(search);
    if (q != null) {
      where.append(
          " and (l.title ilike ? escape '!' or l.external_item_id ilike ? escape '!'"
              + " or v.external_variant_id ilike ? escape '!')");
      String like = "%" + escape(q) + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }

    long total = count("select count(*)" + where, binds.toArray());

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    String orderBy =
        switch (blankToNull(sort) == null ? "RECENT" : sort) {
          case "VERSION" -> " order by m.version desc, m.id desc";
          case "SKEW" -> " order by m.relation_type desc, m.updated_at desc, m.id desc";
          default -> " order by m.updated_at desc, m.id desc";
        };

    List<MappingRow> items =
        fetchRows(where.toString(), orderBy, binds, safeSize, safePage * safeSize);

    int removed = (int) items.stream().filter(r -> "REMOVED".equals(r.status())).count();
    return new MappingPage(items, total, safePage, safeSize, removed);
  }

  /** 映射行查询：where 片段 + 排序 + 真分页。列表与单条详情共用同一条 SQL。 */
  private List<MappingRow> fetchRows(
      String where, String orderBy, List<Object> binds, int limit, int offset) {
    return sql.fetch(
            MAPPING_COLUMNS + where + orderBy + " limit ? offset ?",
            concat(binds, limit, offset))
        .stream()
        .map(this::mappingRow)
        .toList();
  }

  private MappingRow mappingRow(Record r) {
    OffsetDateTime synced = r.get("last_synced_at", OffsetDateTime.class);
    OffsetDateTime listed = r.get("listing_created_at", OffsetDateTime.class);
    return new MappingRow(
        r.get("id", UUID.class),
        r.get("listing_id", UUID.class),
        r.get("external_item_id", String.class),
        r.get("title", String.class),
        r.get("shop_code", String.class),
        r.get("listing_variant_id", UUID.class),
        r.get("external_variant_id", String.class),
        r.get("relation_type", String.class),
        r.get("status", String.class),
        r.get("mapping_status", String.class),
        r.get("internal_sku_id", UUID.class),
        r.get("component_quantity", BigDecimal.class),
        r.get("current_version_id", UUID.class),
        r.get("current_version_no", Integer.class),
        r.get("effective_from", OffsetDateTime.class),
        orZero(r.get("shared_with_variants", Integer.class)),
        orZero(r.get("version_count", Integer.class)),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static int orZero(Integer value) {
    return value == null ? 0 : value;
  }

  /**
   * 建立 / 修改映射。
   *
   * <p>🔴 <b>改映射生成新版本，绝不原地改</b>（蓝图 4.1.2.2 的加粗句「改映射不影响已产生的订单」
   * 的物理落点）：本方法只往 {@code listing_mapping_version} 追加一行，并把上一版的
   * {@code effective_to} 关上。已关闭的版本行由 DB 触发器拒绝改写与删除。
   *
   * <p>🔴 变体上的现状位（{@code mapping_status} / {@code internal_sku_id} /
   * {@code component_quantity}）<b>不交给这里写</b>：由 V176 的 AFTER 触发器
   * {@code project_listing_mapping_to_variant} 就地投影，服务层漏不掉。
   */
  public MappingDetail save(SaveMappingRequest request) {
    UUID tenant = currentTenant();

    String relation = text(request.relationType()).trim().toUpperCase(Locale.ROOT);
    if (!RELATIONS.contains(relation)) {
      throw unprocessable(
          "未知的映射基数："
              + request.relationType()
              + "。可选："
              + String.join(" / ", RELATIONS.stream().sorted().toList()));
    }
    // 服务层先自查，DB 触发器 guard_listing_mapping_consistency 再兜一道（纪律与 V171 一致）
    boolean bundle = "ONE_TO_MANY".equals(relation);
    if (bundle && request.componentQuantity() == null) {
      throw unprocessable("一对多（套装）映射必须给出大于 0 的 componentQuantity");
    }
    if (!bundle && request.componentQuantity() != null) {
      throw unprocessable("componentQuantity 只对一对多（套装）映射有意义");
    }

    String reason = text(request.changeReason());
    if (reason.isEmpty()) {
      throw unprocessable("变更原因必填：没有原因的版本历史无法回溯「当时为什么这么改」");
    }

    // 变体必须在本租户可见范围内（RLS + 复合 FK 双重挡住跨租户引用）
    Record variant =
        sql.fetchOne(
            """
            select v.id,v.listing_id,v.external_variant_id,v.mapping_status
            from listing_variant v
            where v.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and v.id = ?::uuid
            """,
            request.listingVariantId());
    if (variant == null) {
      throw unprocessable("平台变体不存在或不在可见范围内：" + request.listingVariantId());
    }

    OffsetDateTime effectiveFrom =
        request.effectiveFrom() == null ? OffsetDateTime.now().withNano(0) : request.effectiveFrom();

    UUID mappingId = request.id();

    if (mappingId == null) {
      // 变体已有生效中的映射时，**改这一条**而不是再插一条（否则同变体两条生效映射，
      // 订单解析时"取哪一条"没有答案）
      Record existing =
          sql.fetchOne(
              "select id,current_version_id from listing_mapping"
                  + TENANT_FILTER.replace("{alias}", "listing_mapping")
                  + " and listing_variant_id=? and status='ACTIVE' for update",
              request.listingVariantId());
      if (existing != null) mappingId = existing.get("id", UUID.class);
    }

    Record mapping =
        mappingId == null
            ? null
            : sql.fetchOne(
                "select id,current_version_id,status,version,listing_variant_id from listing_mapping"
                    + TENANT_FILTER.replace("{alias}", "listing_mapping")
                    + " and id=? for update",
                mappingId);
    if (mappingId != null && mapping == null) throw notFound();
    if (mapping != null && "REMOVED".equals(mapping.get("status", String.class))) {
      throw conflict("这条映射已被解除；已解除的映射不可复活，请重新建立（新版本号从 1 起）");
    }
    if (mapping != null
        && !request.listingVariantId().equals(mapping.get("listing_variant_id", UUID.class))) {
      // 🔴 改这一条映射时不能顺手把它挪到另一个变体上：映射的版本历史是按 mapping_id 串起来的，
      //   换变体等于让历史挂到不相干的平台上。不一致就说清楚，不静默改。
      throw unprocessable(
          "这条映射挂在另一个平台变体上（"
              + mapping.get("listing_variant_id", UUID.class)
              + "），不能改成 "
              + request.listingVariantId()
              + "；请对目标变体新建一条映射。");
    }
    if (mapping != null && request.expectedVersionNo() != null) {
      Integer current =
          one(
              Integer.class,
              "select version_no from listing_mapping_version"
                  + TENANT_FILTER.replace("{alias}", "listing_mapping_version")
                  + " and id=?",
              mapping.get("current_version_id", UUID.class));
      // 🔴 用 equals 而不是 `!=`：Integer 是引用比较，版本号超过 127 之后
      //   `current != expected` 会永远为真，把正常的乐观锁判成冲突。
      if (current == null || !current.equals(request.expectedVersionNo())) {
        throw conflict(
            "乐观锁冲突：当前版本号是 " + current + "，请求里写的是 " + request.expectedVersionNo());
      }
    }

    String mappingStatus = bundle ? "BUNDLE" : "MAPPED";
    if (mapping == null) {
      mappingId = Ids.next();
      sql.execute(
          """
          insert into listing_mapping
            (id,tenant_id,listing_id,listing_variant_id,relation_type,status,mapping_status,
             internal_sku_id,component_quantity,created_at,updated_at)
          values (?,?,?,?,?,'ACTIVE',?,?,?,now(),now())
          """,
          mappingId, tenant, variant.get("listing_id", UUID.class), request.listingVariantId(),
          relation, mappingStatus, request.internalSkuId(), request.componentQuantity());
    } else {
      sql.execute(
          """
          update listing_mapping
             set relation_type=?, mapping_status=?, internal_sku_id=?, component_quantity=?,
                 version=version+1, updated_at=now()
           where tenant_id=? and id=?
          """,
          relation, mappingStatus, request.internalSkuId(), request.componentQuantity(),
          tenant, mappingId);
    }

    UUID versionId = appendVersion(tenant, mappingId, relation, mappingStatus,
        request.internalSkuId(), request.componentQuantity(), effectiveFrom, reason, "MANUAL");

    sql.execute(
        "update listing_mapping set current_version_id=? where tenant_id=? and id=?",
        versionId, tenant, mappingId);

    return load(mappingId.toString());
  }

  /**
   * 解除映射。
   *
   * <p>🔴 解除<b>不是删除</b>：变体退回未映射（订单行重新进入待映射状态），但历史版本
   * 原样留在 {@code listing_mapping_version} 里 —— 「这条映射曾经存在过、什么时候撤的、
   * 为什么撤」是一条必须可回溯的事实。
   *
   * <p>已关闭的版本行由 DB 触发器拒绝删除，所以这里的"解除"是唯一的退场方式。
   */
  public MappingDetail remove(String rawId, RemoveMappingRequest request) {
    UUID tenant = currentTenant();
    UUID id = parseId(rawId);

    String reason = text(request.changeReason());
    if (reason.isEmpty()) {
      throw unprocessable("解除原因必填：变体会退回未映射、订单会重新进入待映射状态");
    }

    Record mapping =
        sql.fetchOne(
            "select id,status,relation_type,current_version_id from listing_mapping"
                + TENANT_FILTER.replace("{alias}", "listing_mapping")
                + " and id=? for update",
            id);
    if (mapping == null) throw notFound();
    if ("REMOVED".equals(mapping.get("status", String.class))) {
      throw conflict("这条映射已经是解除状态");
    }

    OffsetDateTime now = OffsetDateTime.now().withNano(0);

    // 关闭当前版本（[from, to) 半开区间），这一版就是历史
    UUID currentVersion = mapping.get("current_version_id", UUID.class);
    if (currentVersion != null) {
      sql.execute(
          "update listing_mapping_version set effective_to=?::timestamptz"
              + " where tenant_id=? and id=? and effective_to is null",
          now, tenant, currentVersion);
    }

    // 解除本身也是一版历史：记一条 UNMAPPED 的版本，说明"从此刻起没有映射了"
    UUID removedVersion =
        appendVersion(tenant, id, mapping.get("relation_type", String.class), "UNMAPPED",
            null, null, now, reason, "MANUAL");

    sql.execute(
        """
        update listing_mapping
           set status='REMOVED', mapping_status='UNMAPPED', internal_sku_id=NULL,
               component_quantity=NULL, current_version_id=?, version=version+1, updated_at=now()
         where tenant_id=? and id=?
        """,
        removedVersion, tenant, id);

    return load(id.toString());
  }

  /**
   * 追加一个版本行，并把它设为当前生效版本。
   *
   * <p>「至多一个当前生效版本」由 V176 的部分唯一索引 + 触发器双重保证；这里先关旧版再插新版，
   * 顺序与部分唯一索引一致，不会自撞。
   */
  private UUID appendVersion(
      UUID tenant,
      UUID mappingId,
      String relation,
      String mappingStatus,
      UUID internalSkuId,
      BigDecimal componentQuantity,
      OffsetDateTime effectiveFrom,
      String reason,
      String source) {

    Integer nextNo =
        one(
            Integer.class,
            "select coalesce(max(version_no),0)+1 from listing_mapping_version"
                + TENANT_FILTER.replace("{alias}", "listing_mapping_version")
                + " and mapping_id=?",
            mappingId);

    OffsetDateTime previousFrom = null;
    if ("MAPPED".equals(mappingStatus) || "BUNDLE".equals(mappingStatus)) {
      Record current =
          sql.fetchOne(
              "select effective_from from listing_mapping_version"
                  + TENANT_FILTER.replace("{alias}", "listing_mapping_version")
                  + " and mapping_id=? and effective_to is null",
              mappingId);
      if (current != null) previousFrom = current.get("effective_from", OffsetDateTime.class);
    }

    // 关闭上一版。预约生效（effectiveFrom 在将来）时不能把当前版关掉 ——
    // 提前关掉会让「此刻生效的是哪一版」在预约生效之前出现空档。
    if (previousFrom != null) {
      OffsetDateTime now = OffsetDateTime.now().withNano(0);
      if (effectiveFrom.isAfter(now)) {
        throw unprocessable(
            "预约生效（生效时间点在将来）暂不支持：当前版本会在新版本生效前失去「当前生效版本」"
                + "的身份，订单解析就会出现没有版本可用的时间窗。生效时间点请传不晚于此刻的值。");
      }
      if (effectiveFrom.isBefore(previousFrom)) {
        // 🔴 补生效时间早于当前版本的生效时间 ⇒ 上一版闭不掉（闭了就变成空区间），
        //   而上一版还开着就插新版本会撞上「至多一个当前生效版本」的部分唯一索引。
        //   那会变成一条看不懂的 500，所以在这里说清楚：补生效只能落在当前版本之后。
        throw unprocessable(
            "生效时间点不能早于当前版本的生效时间点（"
                + previousFrom
                + "）：补生效到更早的时间会让当前版本没有可用的时间区间，"
                + "订单解析会落到一个空档上。");
      }
      sql.execute(
          "update listing_mapping_version set effective_to=?::timestamptz"
              + " where tenant_id=? and mapping_id=? and effective_to is null and effective_from<=?::timestamptz",
          effectiveFrom, tenant, mappingId, effectiveFrom);
    }

    UUID versionId = Ids.next();
    sql.execute(
        """
        insert into listing_mapping_version
          (id,tenant_id,mapping_id,version_no,relation_type,mapping_status,internal_sku_id,
           component_quantity,effective_from,effective_to,change_reason,change_source,actor_id,created_at)
        values (?,?,?,?,?,?,?,?,?::timestamptz,NULL,?,?,?,now())
        """,
        versionId, tenant, mappingId, nextNo, relation, mappingStatus, internalSkuId,
        componentQuantity, effectiveFrom, reason, source, currentActor());
    return versionId;
  }

  /** 按 id 取详情：当前映射 + 完整版本历史（新 → 旧）。 */
  public MappingDetail load(String rawId) {
    UUID id = parseId(rawId);

    // 🔴 直接按 id 取，不走列表分页：一条排在第 201 位的映射必须照样能读到详情。
    //   跨租户与不存在都表现为「取不到」，所以走同一条 notFound()。
    List<MappingRow> rows =
        fetchRows(
            MAPPING_FROM
                + " where m.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and m.id = ?::uuid",
            " order by m.updated_at desc, m.id desc",
            List.of(id),
            1,
            0);
    if (rows.isEmpty()) throw notFound();
    return new MappingDetail(rows.get(0), versions(id), affectedOrdersNote());
  }

  private String affectedOrdersNote() {
    return "🔴 4.1.2.2「按版本回溯受影响订单」未交付：09 订单侧尚无任何表能让「受影响订单」有真实来源，"
        + "本模块**不返回空数组冒充**已交付。生效时间点（effectiveFrom / effectiveTo）与变更原因"
        + "（changeReason）已按 4.1.2.2 的字段口径写入，这里逐版返回。";
  }

  private List<MappingVersionView> versions(UUID mappingId) {
    return sql.fetch(
            """
            select id,version_no,relation_type,internal_sku_id,component_quantity,
                   effective_from,effective_to,change_reason,change_source,actor_id,created_at
            from listing_mapping_version
            where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and mapping_id = ?
            order by version_no desc
            """,
            mappingId)
        .stream()
        .map(
            v ->
                new MappingVersionView(
                    v.get("id", UUID.class),
                    v.get("version_no", Integer.class),
                    v.get("relation_type", String.class),
                    v.get("internal_sku_id", UUID.class),
                    v.get("component_quantity", BigDecimal.class),
                    v.get("effective_from", OffsetDateTime.class),
                    v.get("effective_to", OffsetDateTime.class),
                    v.get("change_reason", String.class),
                    v.get("change_source", String.class),
                    v.get("actor_id", UUID.class),
                    v.get("created_at", OffsetDateTime.class)))
        .toList();
  }

  // ==================================================================
  // 批量候选（三条规则）
  // ==================================================================

  /**
   * 批量映射候选。
   *
   * <p>🔴 <b>候选真的来自 catalog</b>（{@link SkuCandidateQueryPort}），不是硬编码数组。
   * 三条规则各自的检索口径：
   *
   * <ul>
   *   <li>{@code SKU_CODE} —— 用平台变体的编码型标识（变体 ID + 属性里的编码）做关键字检索，
   *       <b>精确命中</b>（候选 code 与检索词逐字相等）才算，score=1.0；
   *   <li>{@code BARCODE} —— 从平台变体属性里取条码值做<b>精确</b>条码检索，score=1.0；
   *   <li>{@code TITLE_SIMILARITY} —— 拿平台标题做关键字拉候选，再用 PostgreSQL
   *       {@code pg_trgm similarity()} <b>真算</b>分数，恒 {@code requiresConfirmation=true}。
   * </ul>
   *
   * <p>🔴 候选<b>只落候选表</b>：{@link #batchCandidates} 一个字节的映射行都不写。
   * 只有 {@link #decideCandidate} 确认后才建映射（DB CHECK 钉住标题相似度恒需确认）。
   */
  public BatchCandidateResult batchCandidates(BatchCandidateRequest request, String bearer) {
    UUID tenant = currentTenant();

    String rule = text(request.rule()).trim().toUpperCase(Locale.ROOT);
    if (!RULES.contains(rule)) {
      throw unprocessable(
          "未知的匹配规则：" + request.rule() + "。可选：" + String.join(" / ", RULES.stream().sorted().toList()));
    }

    List<UUID> requested = new LinkedHashSet<>(request.listingIds()).stream().toList();

    // 数据范围过滤：只处理调用者可见的 listing
    List<UUID> visible = new ArrayList<>();
    for (UUID id : requested) {
      UUID found =
          one(
              UUID.class,
              "select id from listing" + TENANT_FILTER.replace("{alias}", "listing") + " and id=?",
              id);
      if (found != null) visible.add(found);
    }
    if (visible.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "选中的 " + requested.size() + " 条 listing 没有一条在当前数据范围内（跨租户与不存在都表现为这一条）");
    }

    // 展开成变体（映射挂在变体上，不是 listing 上 —— V171 的 listing_variant）
    // 🔴 多取一行用来判断是否被上限截断：`limit MAX` 之后 size 永远 <= MAX，
    //    那样 `truncated` 永远是 false，"只处理了前 200 个变体"就会被显示成"全部处理了"。
    List<Record> variants =
        sql.fetch(
            """
            select v.id,v.listing_id,v.external_variant_id,v.attributes,
                   l.title,l.external_item_id
            from listing_variant v
            join listing l on l.tenant_id=v.tenant_id and l.id=v.listing_id
            where v.tenant_id = ? and v.listing_id = ANY(?::uuid[])
            order by v.listing_id, v.external_variant_id
            limit ?::bigint
            """,
            tenant, visible.toArray(UUID[]::new), MAX_VARIANTS_PER_BATCH + 1);

    boolean truncated = variants.size() > MAX_VARIANTS_PER_BATCH;
    List<Record> scoped =
        variants.subList(0, Math.min(variants.size(), MAX_VARIANTS_PER_BATCH));

    UUID batchTaskId = Ids.next();
    // 🔴 去重：同一条 (variant, sku, rule) 只留分最高的一条。
    //    用 LinkedHashMap 而不是"收集完再过滤"，是因为候选数上限（每变体 top N）要在
    //    去重**之后**算 —— 否则同一个 SKU 被多条检索词命中会占掉多个名额，
    //    真正的不同候选就被挤出 limit 了。
    Map<String, MappingCandidate> best = new LinkedHashMap<>();
    int scannedSkus = 0;

    for (Record v : scoped) {
      UUID variantId = v.get("id", UUID.class);
      UUID listingId = v.get("listing_id", UUID.class);
      String externalVariantId = orEmpty(v.get("external_variant_id", String.class));
      String title = orEmpty(v.get("title", String.class));
      String attributes = jsonText(v.get("attributes", Object.class));

      List<Hit> hits =
          switch (rule) {
            case SKU_CODE -> bySkuCode(externalVariantId, attributes, bearer);
            case BARCODE -> byBarcode(attributes, bearer);
            default -> byTitleSimilarity(title, request.minSimilarity(), bearer);
          };
      for (Hit hit : hits) {
        scannedSkus = Math.max(scannedSkus, hit.scanned());
        String key = variantId + "|" + hit.skuId() + "|" + rule;
        MappingCandidate incoming =
            new MappingCandidate(
                null, batchTaskId, listingId, variantId, externalVariantId,
                rule, hit.skuId(), hit.code(), hit.name(), hit.matchedText(), hit.score(),
                TITLE_SIMILARITY.equals(rule), "PENDING", null, null, "", null);
        MappingCandidate existing = best.get(key);
        if (existing == null || incoming.score().compareTo(existing.score()) > 0) {
          best.put(key, incoming);
        }
      }
    }

    // 每个变体只保留分数最高的 N 条
    int limit = request.topPerListing() == null ? DEFAULT_TOP_PER_LISTING : request.topPerListing();
    List<MappingCandidate> finalRows = new ArrayList<>();
    for (Record v : scoped) {
      UUID variantId = v.get("id", UUID.class);
      best.values().stream()
          .filter(c -> c.listingVariantId().equals(variantId))
          .sorted(
              (a, b) -> {
                int byScore = b.score().compareTo(a.score());
                return byScore != 0 ? byScore : a.internalSkuId().compareTo(b.internalSkuId());
              })
          .limit(limit)
          .forEach(finalRows::add);
    }

    String reason =
        "规则 "
            + rule
            + "：在 "
            + scoped.size()
            + " 个平台变体上检索 catalog SKU 目录（本次参与比对 "
            + scannedSkus
            + " 条"
            + (truncated ? "，**变体数已达上限 " + MAX_VARIANTS_PER_BATCH + " 被截断**" : "")
            + "）";
    if (rule.equals(TITLE_SIMILARITY)) {
      reason += "；相似度由 PostgreSQL pg_trgm similarity() 真算，**全部需人工确认**";
    }

    sql.execute(
        """
        insert into listing_mapping_batch_task
          (id,tenant_id,rule,listing_ids,requested_count,candidate_count,outcome,scanned_skus,
           truncated,reason,actor_id,created_at)
        values (?,?,?,?::uuid[],?,?,'COMPLETED',?,?,?,?,now())
        """,
        batchTaskId, tenant, rule, visible.toArray(UUID[]::new), requested.size(),
        finalRows.size(), scannedSkus, truncated, reason, currentActor());

    for (MappingCandidate c : finalRows) {
      sql.execute(
          """
          insert into listing_mapping_candidate
            (id,tenant_id,batch_task_id,listing_id,listing_variant_id,external_variant_id,rule,
             internal_sku_id,internal_sku_code,internal_sku_name,matched_text,score,
             requires_confirmation,decision,created_at)
          values (?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',now())
          """,
          Ids.next(), tenant, batchTaskId, c.listingId(), c.listingVariantId(),
          c.externalVariantId(), rule, c.internalSkuId(), c.internalSkuCode(), c.internalSkuName(),
          c.matchedText(), c.score(), c.requiresConfirmation());
    }

    List<MappingCandidate> stored = candidatesOf(batchTaskId);
    int confirmations =
        (int) stored.stream().filter(MappingCandidate::requiresConfirmation).count();

    return new BatchCandidateResult(
        batchTaskId, rule, requested.size(), scoped.size(), stored.size(), scannedSkus,
        truncated, confirmations, stored, reason);
  }

  /** 规则命中：SKU 编码精确匹配。检索词来自变体 ID 与属性里的编码型取值。 */
  private List<Hit> bySkuCode(String externalVariantId, String attributes, String bearer) {
    List<Hit> hits = new ArrayList<>();
    for (String token : codeTokens(externalVariantId, attributes)) {
      var page = skus.skusByKeyword(token, 50, bearer);
      for (var row : page.items()) {
        UUID id = SkuCandidateQueryPort.parseId(row.id());
        if (id == null) continue;
        // 🔴 精确命中才算：catalog 的 q 是 ILIKE，模糊命中不构成「SKU 编码匹配」。
        //   code 为 null 的上游行（脏数据）直接跳过，不要让一个 NPE 把整批推荐带走。
        if (row.code() == null || !row.code().equals(token)) continue;
        hits.add(new Hit(id, row.code(), row.name(), token, BigDecimal.ONE, page.items().size()));
        break;
      }
    }
    return hits;
  }

  /** 规则命中：条码精确匹配。条码从平台变体属性里取（平台把条码放在变体属性里是常见形态）。 */
  private List<Hit> byBarcode(String attributes, String bearer) {
    List<Hit> hits = new ArrayList<>();
    for (String barcode : barcodeValues(attributes)) {
      var page = skus.skusByBarcode(barcode, 20, bearer);
      for (var row : page.items()) {
        UUID id = SkuCandidateQueryPort.parseId(row.id());
        if (id == null) continue;
        if (row.barcode() == null || !barcode.equals(row.barcode())) continue;
        hits.add(new Hit(id, row.code(), row.name(), barcode, BigDecimal.ONE, page.items().size()));
      }
    }
    return hits;
  }

  /**
   * 规则命中：标题相似度推荐。
   *
   * <p>🔴 <b>分数由 PostgreSQL {@code pg_trgm similarity()} 真算</b>，不是服务层编的：
   * 候选（id / code / name）作为 VALUES 传进本库，一条 {@code select similarity(...)} 出分数。
   * 这样"当时推荐的那个 0.62"可以随时在库里复算，对得上账。
   *
   * <p>🔴 <b>恒 {@code requiresConfirmation=true}</b>（蓝图「标题相似度推荐但需人工确认」），
   * 且 DB 层有 CHECK 兜底 —— 服务层写错了也存不进去。
   */
  private List<Hit> byTitleSimilarity(String title, Double minSimilarity, String bearer) {
    String q = text(title);
    if (q.isEmpty()) return List.of();

    // 关键字太长会让 catalog 侧的 ILIKE 全不命中；截前 60 字符作为检索词
    String keyword = q.length() > 60 ? q.substring(0, 60) : q;
    SkuCandidateQueryPort.SkuPage page = skus.skusByKeyword(keyword, MAX_CANDIDATE_SKUS, bearer);
    List<SkuCandidateQueryPort.SkuRow> rows = page.items();

    // 候选池作为 (id, code, name) VALUES 传进本库，一次往返算出全部真算分数。
    // 🔴 绑定顺序必须与占位符出现顺序**逐个对应**：先 ?::uuid,?::text,?::text（每行三列），
    //   再是外层的两个标题占位符（similarity 的比较基准与阈值）。
    List<Object> rowBinds = new ArrayList<>();
    StringBuilder values = new StringBuilder();
    int scanned = 0;
    for (var row : rows) {
      UUID id = SkuCandidateQueryPort.parseId(row.id());
      if (id == null) continue;
      if (scanned > 0) values.append(",");
      values.append("(?::uuid,?::text,?::text)");
      rowBinds.add(id);
      rowBinds.add(row.code() == null ? "" : row.code());
      rowBinds.add(row.name() == null ? "" : row.name());
      scanned++;
    }
    if (scanned == 0) return List.of();

    double threshold = minSimilarity == null ? SIMILARITY_THRESHOLD : minSimilarity;

    List<Object> binds = new ArrayList<>(rowBinds);
    binds.add(q);
    binds.add(q);
    binds.add(threshold);

    List<Record> scored =
        sql.fetch(
            """
            with candidate(id,code,name) as (values
            """
                + values
                + """
            )
            select c.id,c.code,c.name,
                   similarity(lower(?::text), lower(c.name)) as score
            from candidate c
            where similarity(lower(?::text), lower(c.name)) >= ?::double precision
            order by score desc, c.id
            """,
            concat(binds));

    List<Hit> hits = new ArrayList<>();
    for (Record r : scored) {
      BigDecimal raw = r.get("score", BigDecimal.class);
      BigDecimal score = raw == null ? BigDecimal.ZERO : raw.setScale(6, RoundingMode.HALF_UP);
      hits.add(
          new Hit(
              r.get("id", UUID.class),
              orEmpty(r.get("code", String.class)),
              orEmpty(r.get("name", String.class)),
              q,
              score,
              scanned));
    }
    return hits;
  }

  /** 变体上的编码型取值：变体 ID 本身 + 属性里像编码的字符串。 */
  private static List<String> codeTokens(String externalVariantId, String attributes) {
    Set<String> tokens = new LinkedHashSet<>();
    String variant = text(externalVariantId);
    // 变体 ID 常带卖家编码前缀（如 SC-12345），截出编码段
    for (String part : variant.split("[^A-Za-z0-9._-]+")) {
      if (part.length() >= 3) tokens.add(part);
    }
    // 属性里长度 3~64 的字符串都是潜在编码
    for (String v : stringValues(attributes)) {
      String value = text(v);
      if (value.length() >= 3 && value.length() <= 64) tokens.add(value);
    }
    return tokens.stream().limit(20).toList();
  }

  /** 属性里的条码型取值：纯数字且 8~14 位（EAN-8/EAN-13/UPC-A 的常见长度），或含条码关键字。 */
  private static List<String> barcodeValues(String attributes) {
    Set<String> values = new LinkedHashSet<>();
    for (String v : stringValues(attributes)) {
      String value = text(v).replaceAll("[\\s-]", "");
      if (value.isEmpty()) continue;
      boolean digits = value.chars().allMatch(Character::isDigit);
      if (digits && value.length() >= 8 && value.length() <= 14) values.add(value);
    }
    return values.stream().limit(10).toList();
  }

  /** 从 json 文本里抽出一级字符串值（不递归：平台属性是一层对象，深了就该让上游给出明确字段）。 */
  private static List<String> stringValues(String json) {
    List<String> values = new ArrayList<>();
    try {
      var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
      if (node.isObject()) {
        node.elements()
            .forEachRemaining(
                n -> {
                  if (n.isTextual()) values.add(n.asText());
                });
      }
    } catch (Exception ignored) {
      // 属性读不出来时按"没有编码型取值"处理，不让一条脏属性毁掉整批推荐
    }
    return values;
  }

  private record Hit(UUID skuId, String code, String name, String matchedText,
                     BigDecimal score, int scanned) {}

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  // ==================================================================
  // 候选裁决（人工确认）
  // ==================================================================

  /**
   * 裁决候选：CONFIRMED 建映射 + 生成版本；REJECTED 只记否决。
   *
   * <p>🔴 <b>标题相似度只有这一条路能变成映射</b>：候选表与映射表物理分离，
   * {@link #batchCandidates} 一个字节的映射行都不写。DB 层 CHECK 钉住
   * {@code TITLE_SIMILARITY ⇒ requires_confirmation}，服务层写错也存不进去。
   *
   * <p>🔴 「一个候选只裁决一次」：已裁决的候选再裁决返回 409，而不是静默改写已落库的映射。
   */
  public CandidateDecisionResult decideCandidate(String rawId, CandidateDecisionRequest request) {
    UUID tenant = currentTenant();
    UUID id = parseId(rawId);

    String decision = text(request.decision()).trim().toUpperCase(Locale.ROOT);
    if (!DECISIONS.contains(decision)) {
      throw unprocessable("未知的裁决：" + request.decision() + "。可选：CONFIRMED / REJECTED");
    }

    Record candidate =
        sql.fetchOne(
            "select * from listing_mapping_candidate"
                + TENANT_FILTER.replace("{alias}", "listing_mapping_candidate")
                + " and id=? for update",
            id);
    if (candidate == null) throw notFound();
    if (!"PENDING".equals(candidate.get("decision", String.class))) {
      throw conflict("这个候选已经裁决过了（decision=" + candidate.get("decision", String.class) + "），不可重复裁决");
    }

    OffsetDateTime now = OffsetDateTime.now().withNano(0);

    sql.execute(
        """
        update listing_mapping_candidate
           set decision=?, decided_by=?::uuid, decided_at=?::timestamptz, decision_reason=?
         where tenant_id=? and id=?
        """,
        decision, currentActor(), now, text(request.reason()), tenant, id);

    if ("REJECTED".equals(decision)) {
      return new CandidateDecisionResult(
          id, decision, null, null, null,
          "已否决：平台变体保持未映射状态，订单行会继续进入待映射（**不会**按标题相似度猜 SKU）");
    }

    UUID variantId = candidate.get("listing_variant_id", UUID.class);
    UUID skuId = candidate.get("internal_sku_id", UUID.class);
    String code = orEmpty(candidate.get("internal_sku_code", String.class));

    // 确认的候选建立映射。默认按 SKU 编码那条规则的关系走：套装要人工改基数，
    // 候选本身不替人决定"这是不是一个套装" —— 猜错基数的代价是订单展开错。
    Record variant =
        sql.fetchOne(
            "select listing_id from listing_variant"
                + TENANT_FILTER.replace("{alias}", "listing_variant")
                + " and id=?",
            variantId);
    if (variant == null) throw unprocessable("候选对应的平台变体已不存在");

    String reason =
        text(request.reason()).isEmpty()
            ? "批量候选人工确认（规则 " + candidate.get("rule", String.class) + "，SKU 编码 " + code + "）"
            : text(request.reason());

    Record existing =
        sql.fetchOne(
            "select id from listing_mapping"
                + TENANT_FILTER.replace("{alias}", "listing_mapping")
                + " and listing_variant_id=? and status='ACTIVE' for update",
            variantId);
    UUID mappingId = existing == null ? null : existing.get("id", UUID.class);
    if (mappingId == null) {
      mappingId = Ids.next();
      sql.execute(
          """
          insert into listing_mapping
            (id,tenant_id,listing_id,listing_variant_id,relation_type,status,mapping_status,
             internal_sku_id,component_quantity,created_at,updated_at)
          values (?,?,?,?,'ONE_TO_ONE','ACTIVE','MAPPED',?,NULL,now(),now())
          """,
          mappingId, tenant, variant.get("listing_id", UUID.class), variantId, skuId);
    } else {
      // 🔴 变体已经有生效映射时，确认候选等于**改这一条**：
      //   映射行的 internal_sku_id 必须跟着版本一起改，否则「当前生效版本指向 SKU-B、
      //   映射行仍指向 SKU-A」—— 列表读映射行、订单解析读版本，两边会给出不同的答案。
      //   改动本身留痕在版本历史里（changeSource=BATCH_CONFIRMED + 裁决理由）。
      sql.execute(
          """
          update listing_mapping
             set relation_type='ONE_TO_ONE', mapping_status='MAPPED', internal_sku_id=?,
                 component_quantity=NULL, version=version+1, updated_at=now()
           where tenant_id=? and id=?
          """,
          skuId, tenant, mappingId);
    }

    UUID versionId =
        appendVersion(tenant, mappingId, "ONE_TO_ONE", "MAPPED", skuId, null, now, reason,
            "BATCH_CONFIRMED");
    sql.execute(
        "update listing_mapping set current_version_id=? where tenant_id=? and id=?",
        versionId, tenant, mappingId);

    Integer versionNo =
        one(
            Integer.class,
            "select version_no from listing_mapping_version"
                + TENANT_FILTER.replace("{alias}", "listing_mapping_version")
                + " and id=?",
            versionId);

    return new CandidateDecisionResult(
        id, decision, mappingId, versionId, versionNo,
        "已确认：建立映射 " + mappingId + " 的第 " + versionNo + " 版（changeSource=BATCH_CONFIRMED）");
  }

  /** 候选列表（可按批次 / 变体 / 裁决状态筛）。 */
  public CandidatePage candidates(String batchTaskId, UUID listingVariantId, String decision,
      int page, int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where =
        new StringBuilder(
            " where c.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid");
    String batch = blankToNull(batchTaskId);
    if (batch != null) {
      where.append(" and c.batch_task_id = ?::uuid");
      binds.add(batch);
    }
    if (listingVariantId != null) {
      where.append(" and c.listing_variant_id = ?::uuid");
      binds.add(listingVariantId);
    }
    String dec = blankToNull(decision);
    if (dec != null) {
      if (!Set.of("PENDING", "CONFIRMED", "REJECTED").contains(dec)) {
        throw unprocessable("未知的裁决状态：" + dec + "。可选：PENDING / CONFIRMED / REJECTED");
      }
      where.append(" and c.decision = ?");
      binds.add(dec);
    }

    long total = count("select count(*) from listing_mapping_candidate c" + where, binds.toArray());
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    List<MappingCandidate> items =
        sql.fetch(
                """
                select c.id,c.batch_task_id,c.listing_id,c.listing_variant_id,c.external_variant_id,
                       c.rule,c.internal_sku_id,c.internal_sku_code,c.internal_sku_name,c.matched_text,
                       c.score,c.requires_confirmation,c.decision,c.decided_by,c.decided_at,
                       c.decision_reason,c.created_at
                from listing_mapping_candidate c
                """
                    + where
                    + " order by c.created_at desc, c.score desc, c.id desc limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::candidateRow)
            .toList();

    return new CandidatePage(items, total, safePage, safeSize);
  }

  private List<MappingCandidate> candidatesOf(UUID batchTaskId) {
    return sql.fetch(
            """
            select id,batch_task_id,listing_id,listing_variant_id,external_variant_id,rule,
                   internal_sku_id,internal_sku_code,internal_sku_name,matched_text,score,
                   requires_confirmation,decision,decided_by,decided_at,decision_reason,created_at
            from listing_mapping_candidate
            where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and batch_task_id = ?
            order by listing_variant_id, score desc, id
            """,
            batchTaskId)
        .stream()
        .map(this::candidateRow)
        .toList();
  }

  private MappingCandidate candidateRow(Record r) {
    return new MappingCandidate(
        r.get("id", UUID.class),
        r.get("batch_task_id", UUID.class),
        r.get("listing_id", UUID.class),
        r.get("listing_variant_id", UUID.class),
        orEmpty(r.get("external_variant_id", String.class)),
        r.get("rule", String.class),
        r.get("internal_sku_id", UUID.class),
        orEmpty(r.get("internal_sku_code", String.class)),
        orEmpty(r.get("internal_sku_name", String.class)),
        orEmpty(r.get("matched_text", String.class)),
        r.get("score", BigDecimal.class),
        Boolean.TRUE.equals(r.get("requires_confirmation", Boolean.class)),
        r.get("decision", String.class),
        r.get("decided_by", UUID.class),
        r.get("decided_at", OffsetDateTime.class),
        orEmpty(r.get("decision_reason", String.class)),
        r.get("created_at", OffsetDateTime.class));
  }

  // ==================================================================
  // 未映射清单（回流 02 映射缺失队列的数据源）
  // ==================================================================

  /**
   * 未映射变体清单。
   *
   * <p>🔴 回流目标 02 映射缺失队列（2.4.2.3）**未交付**，所以本端点返回
   * {@code backflowDelivered=false} + ownerModule + 说明。返回一个空队列或假装已回流
   * 都比"清单在、但那一跳没接"更糟 —— 前者会让运营以为没有未映射商品。
   */
  public UnmappedPage unmapped(String shopCode, String search, int page, int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where =
        new StringBuilder(
            """
             from listing_variant v
             join listing l on l.tenant_id=v.tenant_id and l.id=v.listing_id
             join shop s on s.tenant_id=l.tenant_id and s.id=l.shop_id
             where v.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
               and not exists (select 1 from listing_mapping m
                                where m.tenant_id=v.tenant_id and m.listing_variant_id=v.id
                                  and m.status='ACTIVE')
            """);

    String shop = blankToNull(shopCode);
    if (shop != null) {
      where.append(" and s.code = ?");
      binds.add(shop);
    }
    String q = blankToNull(search);
    if (q != null) {
      where.append(" and (l.title ilike ? escape '!' or v.external_variant_id ilike ? escape '!')");
      String like = "%" + escape(q) + "%";
      binds.add(like);
      binds.add(like);
    }

    long total = count("select count(*)" + where, binds.toArray());
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    List<UnmappedVariantRow> items =
        sql.fetch(
                """
                select v.id,v.listing_id,v.external_variant_id,v.attributes,
                       l.external_item_id,l.title,l.last_synced_at,l.created_at as listing_created_at,
                       s.code as shop_code,
                       (select count(*) from listing_variant v0
                         where v0.tenant_id=v.tenant_id and v0.listing_id=v.listing_id
                           and v0.id < v.id) as ordinal
                """
                    + where
                    + " order by v.listing_id, v.external_variant_id limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::unmappedRow)
            .toList();

    return new UnmappedPage(items, total, safePage, safeSize, false, "2.4.2.3 映射缺失队列",
        "🔴 回流目标 02 映射缺失队列（2.4.2.3）**未交付**：本端点提供的是**清单本身**，"
            + "「把清单送进队列」那一跳尚未接通（backflowDelivered=false）。"
            + "队列落地后本端点可直接作为其数据源，不需要改清单口径。");
  }

  private UnmappedVariantRow unmappedRow(Record r) {
    OffsetDateTime synced = r.get("last_synced_at", OffsetDateTime.class);
    OffsetDateTime created = r.get("listing_created_at", OffsetDateTime.class);
    OffsetDateTime since = synced != null ? synced : created;
    long days =
        since == null ? 0L : Math.max(0L, ChronoUnit.DAYS.between(since, OffsetDateTime.now()));
    return new UnmappedVariantRow(
        r.get("id", UUID.class),
        r.get("listing_id", UUID.class),
        r.get("external_item_id", String.class),
        r.get("title", String.class),
        r.get("shop_code", String.class),
        orEmpty(r.get("external_variant_id", String.class)),
        jsonText(r.get("attributes", Object.class)),
        orZero(r.get("ordinal", Integer.class)),
        days);
  }

  // ==================================================================
  // 订单行解析（09 订单的入口）
  // ==================================================================

  /**
   * 按映射解析平台变体 → 内部 SKU。
   *
   * <p>🔴 蓝图加粗句「映射未建立时订单进入待映射状态，<b>绝不按名称猜 SKU</b>」的落点：
   * 没有生效映射就返回 {@code PENDING_MAPPING} + {@code internalSkuId=null}，
   * <b>没有第三种「疑似匹配」</b>。哪怕候选表里有一个 0.92 的标题相似度候选，
   * 这里也<b>不会</b>用它 —— 候选没有人工确认就不是映射。
   *
   * <p>{@code at} 支持按时刻回溯：传历史时刻就取当时生效的版本（4.1.2.2 的生效时间点语义）。
   */
  public List<MappingResolveResult> resolve(MappingResolveRequest request) {
    UUID tenant = currentTenant();
    OffsetDateTime at = request.at() == null ? OffsetDateTime.now() : request.at();

    List<UUID> variantIds = new LinkedHashSet<>(request.listingVariantIds()).stream().toList();
    if (variantIds.isEmpty()) return List.of();

    List<Record> variants =
        sql.fetch(
            """
            select id,external_variant_id from listing_variant
            where tenant_id = ? and id = ANY(?::uuid[])
            """,
            tenant, variantIds.toArray(UUID[]::new));
    Map<UUID, Record> byVariant = new LinkedHashMap<>();
    for (Record r : variants) byVariant.put(r.get("id", UUID.class), r);

    List<MappingResolveResult> results = new ArrayList<>();
    for (UUID variantId : variantIds) {
      Record v = byVariant.get(variantId);
      String externalVariantId = v == null ? "" : orEmpty(v.get("external_variant_id", String.class));

      // 该时刻生效的版本：映射 ACTIVE + 版本区间覆盖 at
      Record resolved =
          sql.fetchOne(
              """
              select m.id as mapping_id,m.relation_type,m.component_quantity,
                     ver.version_no,ver.effective_from,ver.internal_sku_id
              from listing_mapping m
              join listing_mapping_version ver
                on ver.tenant_id=m.tenant_id and ver.mapping_id=m.id
              where m.tenant_id = ? and m.listing_variant_id = ? and m.status='ACTIVE'
                and ver.effective_from <= ?::timestamptz
                and (ver.effective_to is null or ver.effective_to > ?::timestamptz)
              order by ver.version_no desc
              limit 1
              """,
              tenant, variantId, at, at);

      if (resolved == null) {
        results.add(
            new MappingResolveResult(
                variantId, externalVariantId, "PENDING_MAPPING", null, null, null, null, null,
                null,
                v == null
                    ? "平台变体不存在或不在可见范围内"
                    : "该变体在此时刻没有生效的映射。**绝不按名称或标题相似度猜 SKU**"
                        + "（蓝图 4.1.2.1 加粗句 / 实体目录 §6）—— 请先建立映射或确认一条批量候选。"));
      } else {
        results.add(
            new MappingResolveResult(
                variantId,
                externalVariantId,
                "RESOLVED",
                resolved.get("internal_sku_id", UUID.class),
                resolved.get("relation_type", String.class),
                resolved.get("component_quantity", BigDecimal.class),
                resolved.get("mapping_id", UUID.class),
                resolved.get("version_no", Integer.class),
                resolved.get("effective_from", OffsetDateTime.class),
                ""));
      }
    }
    return results;
  }

  // ==================================================================
  // 多对一反查
  // ==================================================================

  /**
   * 多对一（MANY_TO_ONE）的读出口：哪些平台变体共用这个内部 SKU。
   *
   * <p>「多对一」如果不留读出口就只是一句声明 —— 没有端点能证明「改这条映射会波及哪几个平台商品」。
   */
  public SkuUsage skuUsage(String rawInternalSkuId) {
    UUID skuId = parseId(rawInternalSkuId);

    List<MappedVariantRow> variants =
        sql.fetch(
                """
                select m.id as mapping_id,m.listing_id,m.listing_variant_id,m.relation_type,
                       m.component_quantity,v.external_variant_id,l.external_item_id,s.code as shop_code,
                       (select v2.version_no from listing_mapping_version v2
                         where v2.tenant_id=m.tenant_id and v2.id=m.current_version_id) as version_no
                from listing_mapping m
                join listing_variant v on v.tenant_id=m.tenant_id and v.id=m.listing_variant_id
                join listing l on l.tenant_id=m.tenant_id and l.id=m.listing_id
                join shop s on s.tenant_id=l.tenant_id and s.id=l.shop_id
                where m.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
                  and m.internal_sku_id = ? and m.status='ACTIVE'
                order by s.code, l.external_item_id, v.external_variant_id
                """,
                skuId)
            .stream()
            .map(
                r ->
                    new MappedVariantRow(
                        r.get("mapping_id", UUID.class),
                        r.get("listing_id", UUID.class),
                        r.get("listing_variant_id", UUID.class),
                        orEmpty(r.get("external_variant_id", String.class)),
                        r.get("external_item_id", String.class),
                        r.get("shop_code", String.class),
                        r.get("relation_type", String.class),
                        r.get("component_quantity", BigDecimal.class),
                        r.get("version_no", Integer.class)))
            .toList();

    return new SkuUsage(
        skuId,
        variants.size(),
        variants,
        "本模块只按 03 的逻辑 id 反查，**不复制 catalog 的编码 / 名称**（跨服务不建 FK / 不跨库 SELECT）。"
            + "要看到内部 SKU 自身的编码与规格，请到 03 商品侧。");
  }

  private static UUID parseId(String raw) {
    try {
      return UUID.fromString(text(raw));
    } catch (IllegalArgumentException e) {
      // 跨租户与不存在必须返回**逐字相同**的 404，所以畸形 id 也走同一条路
      throw notFound();
    }
  }

  /** 仅供测试与文档引用：把蓝图原文的四问保留在代码里，避免实现漂移。 */
  static final String BLUEPRINT = "4.1.2.1 映射管理";
}
