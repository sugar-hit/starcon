package com.starcon.channel;

import static com.starcon.channel.ListingCollectBoxModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 4.4.1.3 采集箱。
 *
 * <p>蓝图：「采集结果的暂存区，按平台/来源/标签分组，支持勾选后批量认领为内部商品（生成 SPU/SKU 草稿并预填内容）、批量删除、批量打标。输入：勾选操作；认领规则（目标分类、品牌映射、命名规则）。输出：认领后的商品草稿；采集箱状态更新。关系：上游 = 4.4.1.1/4.4.1.2；下游 = 3.1.1.1 商品建档、4.2.1.1 编辑器。<b>认领时必须做品牌与分类映射</b>，否则采集来的商品归属混乱，后续无法按品牌做合规与绩效分析。」
 *
 * <p>🔴 <b>归属 channel 而不是 catalog</b>：本模块的输入是**采集草稿**（4.4.1.1 的
 * {@code listing_collect_draft}，4.4.1.2 的结果也落同一张表），输出是**认领结果**（商品草稿载荷）。
 * 中间不碰任何一张 catalog 的事实表 —— 认领要「生成 SPU/SKU 草稿」，但**建档归 03 catalog
 * （3.1.1.1）**，AGENTS.md 禁止跨服务数据库访问，04 卷边界也明写「不做：不新建 SKU（引导到 03）」。
 * ⇒ 本类**只写** {@code listing_collect_box_*} 与 {@code listing_collect_claim_*} 四张表，
 * **没有一条** insert/update 打向 catalog 的 spu / sku / attribute 表。
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>采集结果的暂存区</b> → {@code listing_collect_box_item}：把挑中的采集草稿收进箱子。
 *   <li><b>按平台/来源/标签分组</b> → {@link #groups()}：按来源平台、按标签、按认领状态三份真聚合。
 *   <li><b>勾选后批量认领</b> → {@link #claim}：逐条做品牌与分类映射 + 命名规则，产出商品草稿。
 *   <li><b>批量删除</b> → {@link #remove}：移出采集箱；上游草稿在没有被批量采集结果清单引用时一并清理。
 *   <li><b>批量打标</b> → {@link #tag}：挂标签 / 摘标签。
 *   <li><b>认领规则（目标分类、品牌映射、命名规则）</b> → {@code listing_collect_claim_rule}：
 *       三者缺一就**建不出规则**（DB CHECK），认领时更不可能通过。
 * </ul>
 *
 * <p>🔴 <b>「认领时必须做品牌与分类映射」在这里是硬约束，不是注释</b>：
 *
 * <ol>
 *   <li>规则表上 {@code brand_mapping} 非空是 <b>DB 级 CHECK</b>，{@code target_category_code} /
 *       {@code naming_template} 也非空 —— 缺映射的规则<b>存不进去</b>。
 *   <li>认领时逐条解析：草稿的品牌属性 → 查 {@code brand_mapping} → 查不到就<b>这一条认领失败</b>
 *       并写明「品牌 X 未在规则中映射」；类目同理（{@code category_mapping} 优先，
 *       否则用 {@code target_category_code}）。**没有映射就没有认领结果**。
 *   <li>认领结果里同时存下**映射依据**（原始品牌 / 原始类目）与**映射结果**（目标品牌 / 目标分类），
 *       事后能复核「为什么这个商品归到这个品牌下」。
 * </ol>
 */
@Service
public class ListingCollectBoxService {

  private static final int MAX_PAGE_SIZE = 200;

  /** 批量操作一次最多处理多少条（防一次拖走整个箱子）。 */
  private static final int MAX_BATCH = 200;

  /** 取型号的候选属性键（命名规则的 {@code {model}} 占位符用）。 */
  private static final List<String> MODEL_KEYS = List.of("型号", "model", "Model", "MODEL");

  private final DSLContext sql;
  private final ObjectMapper json;

  public ListingCollectBoxService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ==================================================================
  // 小工具
  // ==================================================================

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(String value) {
    String trimmed = text(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  /** 当前时刻（截断到微秒，与 PostgreSQL 的 timestamptz 精度一致）。 */
  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  }

  private static int clampSize(int size) {
    return Math.max(1, Math.min(size, MAX_PAGE_SIZE));
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
  }

  private static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static UUID parseId(String raw) {
    try {
      return UUID.fromString(text(raw));
    } catch (IllegalArgumentException e) {
      // 跨租户与不存在必须返回**逐字相同**的 404，所以畸形 id 也走同一条路
      throw notFound();
    }
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 在进入事务时绑定，不来自任何客户端头）。 */
  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(String.valueOf(raw));
  }

  /** 当前用户的 id（app.actor；由 AccessService 在事务内设置）。 */
  private UUID currentActor() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.actor',true),'')");
    if (raw == null) return null;
    String value = String.valueOf(raw);
    return value.isBlank() ? null : UUID.fromString(value);
  }

  /** 当前用户的部门（数据范围用；没有部门就是 null，与 V1 shop 同一纪律）。 */
  private UUID currentDepartment() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.department',true),'')");
    if (raw == null) return null;
    String value = String.valueOf(raw);
    return value.isBlank() ? null : UUID.fromString(value);
  }

  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** jsonb 列 → Map&lt;String,String&gt;（保持落库时的顺序；非对象一律当空）。 */
  private Map<String, String> readMap(Object raw) {
    if (raw == null) return new LinkedHashMap<>();
    String value = raw instanceof org.jooq.JSONB jsonb ? jsonb.data() : String.valueOf(raw);
    try {
      var node = json.readTree(value);
      if (node == null || !node.isObject()) return new LinkedHashMap<>();
      Map<String, String> out = new LinkedHashMap<>();
      node.fields()
          .forEachRemaining(entry -> out.put(entry.getKey(), entry.getValue().asText()));
      return out;
    } catch (Exception e) {
      return new LinkedHashMap<>();
    }
  }

  /** jsonb 列 → 键值项列表（保持落库时的顺序）。 */
  private List<ListingCollectBoxKeyValue> readKeyValues(Object raw) {
    List<ListingCollectBoxKeyValue> out = new ArrayList<>();
    readMap(raw).forEach((k, v) -> out.add(new ListingCollectBoxKeyValue(k, v)));
    return out;
  }

  /** jsonb 列 → 字符串列表（非数组一律当空）。 */
  private List<String> readStringList(Object raw) {
    if (raw == null) return List.of();
    String value = raw instanceof org.jooq.JSONB jsonb ? jsonb.data() : String.valueOf(raw);
    try {
      var node = json.readTree(value);
      if (node == null || !node.isArray()) return List.of();
      List<String> out = new ArrayList<>();
      node.forEach(item -> out.add(item.asText()));
      return out;
    } catch (Exception e) {
      return List.of();
    }
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 🔴 逐维度能力声明。
   *
   * <p>未交付段一律 {@code available=false} + ownerModule + impact：**商品草稿的落库**（03 建档）、
   * **媒体库**（03）、**我方售价**（05）、**内部可售量**（07）。
   */
  public ListingCollectBoxCapabilities capabilities() {
    return new ListingCollectBoxCapabilities(
        List.of(
            new ListingCollectBoxCapabilityDimension(
                "collect_box",
                "🔴 采集箱（把采集草稿收进暂存区，按平台/来源/标签分组）",
                true,
                "listing_collect_box_item + listing_collect_draft（4.4.1.1）",
                "",
                "缺了它采集草稿只能躺在采集历史里 —— 没有暂存区就没有「挑一批一起处理」这回事"),
            new ListingCollectBoxCapabilityDimension(
                "grouping",
                "🔴 分组（按平台/来源、按标签、按认领状态）",
                true,
                "listing_collect_box_item / listing_collect_box_item_tag（真聚合）",
                "",
                "缺了它箱子就是一堆平铺的草稿 —— 运营分不出「这批 1688 的」和「那批插件采的」"),
            new ListingCollectBoxCapabilityDimension(
                "bulk_tag",
                "🔴 批量打标（挂标签 / 摘标签）",
                true,
                "listing_collect_box_tag + listing_collect_box_item_tag",
                "",
                "没有标签就只剩「按平台分」这一维 —— 运营自己的分类（待议价 / 已谈好）无处安放"),
            new ListingCollectBoxCapabilityDimension(
                "bulk_delete",
                "🔴 批量删除（移出采集箱）",
                true,
                "listing_collect_box_item（+ 无引用时连带清理上游草稿）",
                "",
                "不删就只能让采集箱越堆越满 —— 垃圾草稿会淹没真正要处理的那几条"),
            new ListingCollectBoxCapabilityDimension(
                "claim_rule",
                "🔴 认领规则（目标分类 + 品牌映射 + 命名规则，三者缺一不可）",
                true,
                "listing_collect_claim_rule",
                "",
                "🔴 蓝图加粗句：认领时必须做品牌与分类映射 —— 没有规则的认领会把商品归属搅乱，"
                    + "后续按品牌做合规与绩效分析全部失真"),
            new ListingCollectBoxCapabilityDimension(
                "claim_to_spu",
                "🔴 认领为内部商品（生成 SPU / SKU 草稿并预填内容）",
                true,
                "listing_collect_claim_result（草稿载荷）",
                "",
                "本模块产出的是**草稿载荷**（已映射、已命名、已预填内容），不是商品库里的商品 —— "
                    + "落库归 03 建档"),
            new ListingCollectBoxCapabilityDimension(
                "product_draft_delivery",
                "🔴 商品草稿落库（交由 03 的建档流程建 SPU / SKU）",
                false,
                "—",
                "3.1.1.1 商品建档（catalog）",
                "🔴 04 卷边界明写「不做：不新建 SKU（引导到 03）」，且 AGENTS.md **禁止跨服务数据库访问** —— "
                    + "本模块**不写** catalog 的 spu / sku 表。"
                    + "认领结果停在 buildState=PENDING_BUILD：SPU 编码要由 03 的编号规则给、"
                    + "SKU 的属性字典 id 与计量单位是 03 的事实（3.2.1.1 / 3.1.2.4），"
                    + "本模块**不编造**这些 id。"
                    + "消费方式：03 建档时按本模块产出的草稿载荷建档。"),
            new ListingCollectBoxCapabilityDimension(
                "media_library",
                "🔴 图片下载入媒体库",
                false,
                "—",
                "03 商品主数据（catalog）媒体库",
                "🔴 媒体库归 03 catalog，跨服务写表被 AGENTS.md 禁止 —— 本模块**只透传图片 URL**。"),
            new ListingCollectBoxCapabilityDimension(
                "price",
                "我方售价与币种",
                false,
                "—",
                "05 定价与价目系统",
                "卷 05 整卷未交付 ⇒ 采集箱**不产出我方售价**。"
                    + "条目上的 sourcePriceAmount 是**外部页面原文标价**，不是我们的定价"),
            new ListingCollectBoxCapabilityDimension(
                "internal_stock",
                "内部可售量",
                false,
                "—",
                "07 库存系统",
                "卷 07 未交付 ⇒ 采集箱不带任何库存事实（外部页面的库存数也不是我方可售量）")),
        ITEM_STATES,
        ORIGINS,
        NAMING_PLACEHOLDERS,
        true,
        "🔴 **认领必须做品牌与分类映射**：草稿的品牌属性要能在认领规则的「品牌映射」里查到，"
            + "类目要能在「分类映射」里查到或落到规则的「目标分类」上。"
            + "查不到的那一条**认领失败**并写明原因，不会悄悄认领成一个无品牌无分类的商品。",
        true,
        "🔴 认领产出的是**商品草稿载荷**（已映射品牌与分类、已按命名规则命名、已预填内容），"
            + "不是商品库里的商品 —— 落库归 3.1.1.1 商品建档。",
        false,
        "🔴 认领结果停在 **PENDING_BUILD**：本模块**不跨服务写** catalog 的表，"
            + "SPU 编码、SKU 的属性字典与计量单位都由 03 建档补齐。",
        false,
        "🔴 图片**只透传外部地址**（主图 + 附图），不下载二进制、不写媒体表 —— "
            + "媒体库归 03 catalog，跨服务写表被禁止。",
        "上游 = 4.4.1.1 链接采集 / 4.4.1.2 批量·插件采集（两者都落 listing_collect_draft）；"
            + "下游 = 3.1.1.1 商品建档（消费认领结果里的草稿载荷）、4.2.1.1 编辑器（预填内容）。"
            + "本模块**不跨服务读表、不跨服务写表**。");
  }

  // ==================================================================
  // 读取：采集箱条目
  // ==================================================================

  /** 采集箱条目列表（真分页 + 真筛选：平台/来源、标签、认领状态、标题关键字）。 */
  public ListingCollectBoxPage listItems(
      String sourceCode, String tagId, String state, String q, int page, int size) {
    int limit = clampSize(size);
    int offset = Math.max(0, page) * limit;
    String normalizedState = blankToNull(state);
    if (normalizedState != null && !ITEM_STATES.contains(normalizedState.toUpperCase(Locale.ROOT))) {
      throw unprocessable("未知的条目状态：" + state + "。可选：" + String.join(" / ", ITEM_STATES));
    }
    String normalizedSource = blankToNull(sourceCode);
    UUID normalizedTag = parseOptionalUuid(tagId, "标签 id");
    String keyword = blankToNull(q);

    List<Object> binds = new ArrayList<>();
    String where = " where i.tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid";
    if (normalizedSource != null) {
      where += " and s.code=?";
      binds.add(normalizedSource);
    }
    if (normalizedState != null) {
      where += " and i.state=?";
      binds.add(normalizedState.toUpperCase(Locale.ROOT));
    }
    if (keyword != null) {
      where += " and d.title ilike ?";
      binds.add("%" + keyword + "%");
    }
    if (normalizedTag != null) {
      where +=
          " and exists(select 1 from listing_collect_box_item_tag it"
              + " where it.tenant_id=i.tenant_id and it.item_id=i.id and it.tag_id=?)";
      binds.add(normalizedTag);
    }

    String from =
        " from listing_collect_box_item i"
            + " join listing_collect_draft d on d.tenant_id=i.tenant_id and d.id=i.draft_id"
            + " join listing_collect_source s on s.id=d.source_id";

    long total =
        ((Number) sql.fetchValue("select count(*)" + from + where, binds.toArray())).longValue();

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(limit);
    pageBinds.add(offset);

    List<Record> rows =
        sql.fetch(
            "select i.id, i.draft_id, i.origin, i.state, i.claim_error, i.claimed_at, i.created_at,"
                + " d.title, d.main_image_url, d.source_url, d.source_price_amount,"
                + " d.source_price_currency, d.captured_at,"
                + " s.code as source_code, s.name as source_name"
                + from
                + where
                + " order by i.created_at desc, i.id desc limit ? offset ?",
            pageBinds.toArray());

    List<ListingCollectBoxItemRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(itemRow(row, List.of()));
    }
    attachTags(items);
    return new ListingCollectBoxPage(items, total, Math.max(0, page), limit);
  }

  /** 单个采集箱条目详情（含草稿内容、标签、认领结果）。 */
  public ListingCollectBoxItemRow itemDetail(String id) {
    UUID itemId = parseId(id);
    Record row =
        sql.fetchOne(
            "select i.id, i.draft_id, i.origin, i.state, i.claim_error, i.claimed_at, i.created_at,"
                + " d.title, d.main_image_url, d.source_url, d.source_price_amount,"
                + " d.source_price_currency, d.captured_at,"
                + " s.code as source_code, s.name as source_name"
                + " from listing_collect_box_item i"
                + " join listing_collect_draft d on d.tenant_id=i.tenant_id and d.id=i.draft_id"
                + " join listing_collect_source s on s.id=d.source_id"
                + " where i.tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and i.id=?",
            itemId);
    if (row == null) throw notFound();
    // 🔴 用可变 List 装：attachTags 会回填标签与认领结果 id（record 不可变，只能换掉）
    List<ListingCollectBoxItemRow> holder = new ArrayList<>();
    holder.add(itemRow(row, List.of()));
    attachTags(holder);
    return holder.get(0);
  }

  /** 分组统计（按平台/来源、按标签、按认领状态）—— 真聚合，不是前端自己数。 */
  public ListingCollectBoxGroups groups() {
    List<ListingCollectBoxGroupRow> bySource = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select s.code, s.name, count(*) as c"
                + " from listing_collect_box_item i"
                + " join listing_collect_draft d on d.tenant_id=i.tenant_id and d.id=i.draft_id"
                + " join listing_collect_source s on s.id=d.source_id"
                + " group by s.code, s.name order by count(*) desc, s.code")) {
      bySource.add(
          new ListingCollectBoxGroupRow(
              row.get("code", String.class),
              row.get("name", String.class),
              ((Number) row.get("c")).longValue()));
    }

    List<ListingCollectBoxGroupRow> byTag = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select t.id, t.name, count(distinct it.item_id) as c"
                + " from listing_collect_box_item_tag it"
                + " join listing_collect_box_tag t on t.tenant_id=it.tenant_id and t.id=it.tag_id"
                + " group by t.id, t.name order by count(distinct it.item_id) desc, t.name")) {
      byTag.add(
          new ListingCollectBoxGroupRow(
              row.get("id", UUID.class).toString(),
              row.get("name", String.class),
              ((Number) row.get("c")).longValue()));
    }

    List<ListingCollectBoxGroupRow> byState = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select i.state, count(*) as c from listing_collect_box_item i"
                + " group by i.state order by i.state")) {
      byState.add(
          new ListingCollectBoxGroupRow(
              row.get("state", String.class),
              row.get("state", String.class),
              ((Number) row.get("c")).longValue()));
    }

    long total =
        ((Number)
                sql.fetchValue(
                    "select count(*) from listing_collect_box_item"
                        + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"))
            .longValue();
    return new ListingCollectBoxGroups(bySource, byTag, byState, total);
  }

  /** 还没进箱的采集草稿（4.4.1.1 / 4.4.1.2 的产物）—— 收录时的候选来源。 */
  public ListingCollectBoxCandidatePage candidates(String sourceCode, String q, int page, int size) {
    int limit = clampSize(size);
    int offset = Math.max(0, page) * limit;
    String normalizedSource = blankToNull(sourceCode);
    String keyword = blankToNull(q);

    List<Object> binds = new ArrayList<>();
    String where =
        " where d.tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
            + " and not exists(select 1 from listing_collect_box_item i"
            + " where i.tenant_id=d.tenant_id and i.draft_id=d.id)";
    if (normalizedSource != null) {
      where += " and s.code=?";
      binds.add(normalizedSource);
    }
    if (keyword != null) {
      where += " and d.title ilike ?";
      binds.add("%" + keyword + "%");
    }

    String from =
        " from listing_collect_draft d join listing_collect_source s on s.id=d.source_id";

    long total =
        ((Number) sql.fetchValue("select count(*)" + from + where, binds.toArray())).longValue();

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(limit);
    pageBinds.add(offset);

    List<ListingCollectBoxCandidateRow> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select d.id, d.title, d.main_image_url, d.source_url, d.source_price_amount,"
                + " d.source_price_currency, d.captured_at, d.created_at,"
                + " s.code as source_code, s.name as source_name"
                + from
                + where
                + " order by d.created_at desc, d.id desc limit ? offset ?",
            pageBinds.toArray())) {
      items.add(
          new ListingCollectBoxCandidateRow(
              row.get("id", UUID.class),
              row.get("title", String.class),
              row.get("main_image_url", String.class),
              row.get("source_url", String.class),
              row.get("source_code", String.class),
              row.get("source_name", String.class),
              row.get("source_price_amount", BigDecimal.class),
              row.get("source_price_currency", String.class),
              row.get("captured_at", OffsetDateTime.class),
              row.get("created_at", OffsetDateTime.class)));
    }
    return new ListingCollectBoxCandidatePage(items, total, Math.max(0, page), limit);
  }

  // ==================================================================
  // 写入：收录
  // ==================================================================

  /** 收录：把选中的采集草稿放进采集箱（一份草稿最多入箱一次）。 */
  public ListingCollectBoxEnrollResult enroll(List<UUID> draftIds) {
    if (draftIds == null || draftIds.isEmpty()) {
      throw badRequest("请至少选择一条采集草稿");
    }
    if (draftIds.size() > MAX_BATCH) {
      throw badRequest("一次最多收录 " + MAX_BATCH + " 条，请分批操作");
    }
    UUID tenant = currentTenant();
    UUID actor = currentActor();
    UUID department = currentDepartment();

    List<ListingCollectBoxEnrollOutcome> outcomes = new ArrayList<>();
    int succeeded = 0;
    for (UUID draftId : draftIds) {
      Record draft =
          sql.fetchOne(
              "select d.id, d.source_id from listing_collect_draft d"
                  + " where d.tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
                  + " and d.id=?",
              draftId);
      if (draft == null) {
        outcomes.add(new ListingCollectBoxEnrollOutcome(draftId, false, "采集草稿不存在或不可见", null));
        continue;
      }
      Record existing =
          sql.fetchOne(
              "select id from listing_collect_box_item"
                  + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
                  + " and draft_id=?",
              draftId);
      if (existing != null) {
        outcomes.add(
            new ListingCollectBoxEnrollOutcome(
                draftId, false, "这份采集草稿已经在采集箱里了", existing.get("id", UUID.class)));
        continue;
      }
      String origin = detectOrigin(tenant, draftId);
      UUID itemId = Ids.next();
      sql.execute(
          "insert into listing_collect_box_item"
              + " (id,tenant_id,draft_id,origin,state,claim_error,actor_id,owner_id,department_id,"
              + " created_at,updated_at)"
              + " values (?,?,?,?,'PENDING','',?,?,?,now(),now())",
          itemId,
          tenant,
          draftId,
          origin,
          actor,
          actor,
          department);
      succeeded++;
      outcomes.add(new ListingCollectBoxEnrollOutcome(draftId, true, "", itemId));
    }
    return new ListingCollectBoxEnrollResult(
        draftIds.size(), succeeded, draftIds.size() - succeeded, outcomes);
  }

  /**
   * 条目来源判定：链接采集 / 批量采集 / 插件上报。
   *
   * <p>🔴 判据是**查 4.4.1.2 的结果清单**（{@code listing_bulk_collect_result.draft_id}），
   * 不是猜的：批量与插件产出的草稿都会在结果清单里留一条 FETCHED。
   */
  private String detectOrigin(UUID tenant, UUID draftId) {
    Object raw =
        sql.fetchValue(
            "select r.origin from listing_bulk_collect_result r"
                + " where r.tenant_id=? and r.draft_id=? and r.state='FETCHED'"
                + " order by r.created_at desc limit 1",
            tenant,
            draftId);
    String origin = raw == null ? "" : String.valueOf(raw);
    return ORIGINS.contains(origin) ? origin : "LINK";
  }

  // ==================================================================
  // 写入：标签
  // ==================================================================

  /** 标签列表（带每个标签挂着的条目数）。 */
  public List<ListingCollectBoxTagRow> listTags() {
    List<ListingCollectBoxTagRow> out = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select t.id, t.name, t.color, t.created_at,"
                + " (select count(*) from listing_collect_box_item_tag it"
                + "  where it.tenant_id=t.tenant_id and it.tag_id=t.id) as c"
                + " from listing_collect_box_tag t"
                + " where t.tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " order by t.position, t.created_at desc, t.id")) {
      out.add(
          new ListingCollectBoxTagRow(
              row.get("id", UUID.class),
              row.get("name", String.class),
              row.get("color", String.class),
              ((Number) row.get("c")).longValue(),
              row.get("created_at", OffsetDateTime.class)));
    }
    return out;
  }

  /** 建标签（同一租户内标签名唯一）。 */
  public ListingCollectBoxTagRow createTag(CreateBoxTagRequest request) {
    UUID tenant = currentTenant();
    UUID actor = currentActor();
    UUID department = currentDepartment();
    String name = text(request.name());
    if (name.isEmpty()) throw badRequest("标签名不能为空");
    Record clash =
        sql.fetchOne(
            "select id from listing_collect_box_tag"
                + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and name=?",
            name);
    if (clash != null) {
      throw conflict("标签「" + name + "」已经存在了");
    }
    UUID id = Ids.next();
    sql.execute(
        "insert into listing_collect_box_tag"
            + " (id,tenant_id,name,color,position,actor_id,owner_id,department_id,created_at,updated_at)"
            + " values (?,?,?,?,0,?,?,?,now(),now())",
        id,
        tenant,
        name,
        text(request.color()),
        actor,
        actor,
        department);
    return new ListingCollectBoxTagRow(
        id, name, text(request.color()), 0L, now());
  }

  /** 删标签（连带摘掉所有条目上的这个标签）。 */
  public void deleteTag(String id) {
    UUID tagId = parseId(id);
    int deleted =
        sql.execute(
            "delete from listing_collect_box_tag"
                + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and id=?",
            tagId);
    if (deleted == 0) throw notFound();
  }

  /** 批量打标：挂标签（ADD）或摘标签（REMOVE）。 */
  public ListingCollectBoxTagResult tag(TagBoxItemsRequest request) {
    String action = text(request.action()).toUpperCase(Locale.ROOT);
    if (!TAG_ACTIONS.contains(action)) {
      throw unprocessable("未知的打标动作：" + request.action() + "。可选：" + String.join(" / ", TAG_ACTIONS));
    }
    if (request.itemIds() == null || request.itemIds().isEmpty()) {
      throw badRequest("请至少选择一条采集箱条目");
    }
    if (request.itemIds().size() > MAX_BATCH) {
      throw badRequest("一次最多打标 " + MAX_BATCH + " 条，请分批操作");
    }
    UUID tenant = currentTenant();
    UUID actor = currentActor();

    Record tag =
        sql.fetchOne(
            "select id from listing_collect_box_tag"
                + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and id=?",
            request.tagId());
    if (tag == null) {
      throw notFound();
    }

    List<ListingCollectBoxTagOutcome> outcomes = new ArrayList<>();
    int succeeded = 0;
    for (UUID itemId : request.itemIds()) {
      Record item =
          sql.fetchOne(
              "select id from listing_collect_box_item"
                  + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
                  + " and id=?",
              itemId);
      if (item == null) {
        outcomes.add(new ListingCollectBoxTagOutcome(itemId, false, "采集箱条目不存在或不可见"));
        continue;
      }
      if ("ADD".equals(action)) {
        sql.execute(
            "insert into listing_collect_box_item_tag"
                + " (id,tenant_id,item_id,tag_id,actor_id,created_at,updated_at)"
                + " values (?,?,?,?,?,now(),now())"
                + " on conflict (tenant_id,item_id,tag_id) do nothing",
            Ids.next(),
            tenant,
            itemId,
            request.tagId(),
            actor);
      } else {
        sql.execute(
            "delete from listing_collect_box_item_tag"
                + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and item_id=? and tag_id=?",
            itemId,
            request.tagId());
      }
      succeeded++;
      outcomes.add(new ListingCollectBoxTagOutcome(itemId, true, ""));
    }
    return new ListingCollectBoxTagResult(
        request.itemIds().size(), succeeded, request.itemIds().size() - succeeded, outcomes);
  }

  // ==================================================================
  // 写入：批量删除（移出采集箱）
  // ==================================================================

  /**
   * 批量删除：把条目移出采集箱。
   *
   * <p>🔴 上游草稿的清理是**有条件的**：{@code listing_bulk_collect_result.draft_id} 外键引用着草稿
   * （V202 建表时没有 ON DELETE CASCADE）—— 被引用时删草稿会破坏 4.4.1.2 的结果清单事实，
   * 因此保留草稿并在响应里写明。没有被引用的草稿会一并清理（V202 把草稿的清理权交给本模块）。
   */
  public ListingCollectBoxRemoveResult remove(RemoveBoxItemsRequest request) {
    if (request.itemIds() == null || request.itemIds().isEmpty()) {
      throw badRequest("请至少选择一条采集箱条目");
    }
    if (request.itemIds().size() > MAX_BATCH) {
      throw badRequest("一次最多删除 " + MAX_BATCH + " 条，请分批操作");
    }
    UUID tenant = currentTenant();

    List<ListingCollectBoxRemoveOutcome> outcomes = new ArrayList<>();
    int succeeded = 0;
    for (UUID itemId : request.itemIds()) {
      Record item =
          sql.fetchOne(
              "select id, draft_id from listing_collect_box_item"
                  + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
                  + " and id=?",
              itemId);
      if (item == null) {
        outcomes.add(new ListingCollectBoxRemoveOutcome(itemId, false, "采集箱条目不存在或不可见", false));
        continue;
      }
      UUID draftId = item.get("draft_id", UUID.class);
      // 条目删除会连带清掉它的标签关联与认领结果
      sql.execute(
          "delete from listing_collect_box_item"
              + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
              + " and id=?",
          itemId);

      boolean referenced =
          ((Number)
                  sql.fetchValue(
                      "select count(*) from listing_bulk_collect_result"
                          + " where tenant_id=? and draft_id=?",
                      tenant,
                      draftId))
              .longValue()
          > 0;
      boolean draftRemoved = false;
      String reason = "";
      if (referenced) {
        reason =
            "🔴 已移出采集箱；上游采集草稿被批量采集结果清单引用（listing_bulk_collect_result.draft_id），"
                + "保留草稿以免破坏 4.4.1.2 的结果清单事实。";
      } else {
        int removed =
            sql.execute(
                "delete from listing_collect_draft"
                    + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
                    + " and id=?",
                draftId);
        draftRemoved = removed > 0;
        reason =
            draftRemoved
                ? "已移出采集箱，并清理了上游采集草稿。"
                : "已移出采集箱；上游采集草稿已不存在。";
      }
      succeeded++;
      outcomes.add(new ListingCollectBoxRemoveOutcome(itemId, true, reason, draftRemoved));
    }
    return new ListingCollectBoxRemoveResult(
        request.itemIds().size(), succeeded, request.itemIds().size() - succeeded, outcomes);
  }

  // ==================================================================
  // 认领规则
  // ==================================================================

  /** 认领规则列表（真分页）。 */
  public ListingCollectBoxClaimRulePage listRules(int page, int size) {
    int limit = clampSize(size);
    int offset = Math.max(0, page) * limit;
    long total =
        ((Number)
                sql.fetchValue(
                    "select count(*) from listing_collect_claim_rule"
                        + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"))
            .longValue();
    List<ListingCollectBoxClaimRuleRow> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id, name, target_category_code, category_mapping, brand_mapping,"
                + " naming_template, brand_attribute_key, category_attribute_key, state, created_at"
                + " from listing_collect_claim_rule"
                + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " order by created_at desc, id limit ? offset ?",
            limit,
            offset)) {
      items.add(ruleRow(row));
    }
    return new ListingCollectBoxClaimRulePage(items, total, Math.max(0, page), limit);
  }

  /**
   * 新建认领规则。
   *
   * <p>🔴 <b>没有品牌映射就不让建</b>（蓝图加粗句）：品牌映射为空 ⇒ 直接 400，
   * 不是「先存着以后再补」。
   */
  public ListingCollectBoxClaimRuleRow createRule(CreateClaimRuleRequest request) {
    UUID tenant = currentTenant();
    UUID actor = currentActor();
    UUID department = currentDepartment();
    String name = text(request.name());
    if (name.isEmpty()) throw badRequest("规则名不能为空");

    Map<String, String> brandMapping = cleanMapping(request.brandMapping());
    if (brandMapping.isEmpty()) {
      throw badRequest(
          "🔴 认领规则必须有**品牌映射**（源品牌名 → 目标品牌引用）："
              + "蓝图要求「认领时必须做品牌与分类映射」，没有映射的规则不能建。");
    }
    Map<String, String> categoryMapping = cleanMapping(request.categoryMapping());
    String targetCategory = text(request.targetCategoryCode());
    if (categoryMapping.isEmpty() && targetCategory.isEmpty()) {
      throw badRequest(
          "🔴 认领规则必须有**目标分类**或**分类映射**（源类目名 → 目标分类编码）："
              + "蓝图要求「认领时必须做品牌与分类映射」，没有映射的规则不能建。");
    }
    String template = text(request.namingTemplate());
    if (template.isEmpty()) {
      throw badRequest("🔴 认领规则必须有**命名规则**（模板），可用占位符：" + String.join(" ", NAMING_PLACEHOLDERS));
    }
    if (NAMING_PLACEHOLDERS.stream().noneMatch(template::contains)) {
      throw badRequest(
          "🔴 命名规则里至少要有一个占位符（" + String.join(" ", NAMING_PLACEHOLDERS) + "），"
              + "否则不管采来什么商品都会得到同一个名字。");
    }
    Record clash =
        sql.fetchOne(
            "select id from listing_collect_claim_rule"
                + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and name=?",
            name);
    if (clash != null) {
      throw conflict("认领规则「" + name + "」已经存在了");
    }
    UUID id = Ids.next();
    sql.execute(
        "insert into listing_collect_claim_rule"
            + " (id,tenant_id,name,target_category_code,category_mapping,brand_mapping,"
            + " naming_template,brand_attribute_key,category_attribute_key,state,actor_id,owner_id,"
            + " department_id,created_at,updated_at)"
            + " values (?,?,?,?,?::jsonb,?::jsonb,?,?,?,'ACTIVE',?,?,?,now(),now())",
        id,
        tenant,
        name,
        targetCategory,
        writeJson(categoryMapping),
        writeJson(brandMapping),
        template,
        text(request.brandAttributeKey()).isEmpty() ? "品牌" : text(request.brandAttributeKey()),
        text(request.categoryAttributeKey()).isEmpty() ? "类目" : text(request.categoryAttributeKey()),
        actor,
        actor,
        department);
    return new ListingCollectBoxClaimRuleRow(
        id,
        name,
        targetCategory,
        toKeyValues(categoryMapping),
        toKeyValues(brandMapping),
        template,
        text(request.brandAttributeKey()).isEmpty() ? "品牌" : text(request.brandAttributeKey()),
        text(request.categoryAttributeKey()).isEmpty() ? "类目" : text(request.categoryAttributeKey()),
        "ACTIVE",
        now());
  }

  /** 删认领规则（已经被认领结果引用的规则不许删 —— 认领结果必须能回溯用的是哪条规则）。 */
  public void deleteRule(String id) {
    UUID ruleId = parseId(id);
    Record rule =
        sql.fetchOne(
            "select id from listing_collect_claim_rule"
                + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and id=?",
            ruleId);
    if (rule == null) throw notFound();
    long used =
        ((Number)
                sql.fetchValue(
                    "select count(*) from listing_collect_claim_result"
                        + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
                        + " and rule_id=?",
                    ruleId))
            .longValue();
    if (used > 0) {
      throw conflict(
          "🔴 这条认领规则已经被 " + used + " 条认领结果引用，删掉就查不到「当初用的是哪条规则」了 —— 请停用它而不是删掉。");
    }
    sql.execute(
        "delete from listing_collect_claim_rule"
            + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
            + " and id=?",
        ruleId);
  }

  // ==================================================================
  // 认领（核心动作）
  // ==================================================================

  /**
   * 🔴 批量认领：勾选后按认领规则生成商品草稿。
   *
   * <p>顺序是刻意的：
   *
   * <ol>
   *   <li><b>先校验后写</b>：先把每一条的映射结果算出来（品牌 / 分类 / 名称），
   *       算不出来的**这一条**失败并写明原因；算得出来的才落库。
   *       这样一条失败不会拖垮整批。
   *   <li><b>映射不过就不认领</b>：草稿的品牌属性在规则的「品牌映射」里查不到 ⇒ 这一条失败。
   *       蓝图加粗句「认领时必须做品牌与分类映射」在这里落地。
   *   <li><b>产出的是草稿载荷</b>：落到 {@code listing_collect_claim_result}，
   *       {@code buildState} 恒为 {@code PENDING_BUILD} —— 落库归 03 建档（3.1.1.1），
   *       本模块**不跨服务写 catalog 的表**。
   * </ol>
   */
  public ListingCollectBoxClaimResult claim(List<UUID> itemIds, String ruleId) {
    if (itemIds == null || itemIds.isEmpty()) {
      throw badRequest("请至少选择一条采集箱条目");
    }
    if (itemIds.size() > MAX_BATCH) {
      throw badRequest("一次最多认领 " + MAX_BATCH + " 条，请分批操作");
    }
    UUID tenant = currentTenant();
    UUID actor = currentActor();
    UUID ruleUuid = parseId(ruleId);

    Record ruleRow =
        sql.fetchOne(
            "select id, name, target_category_code, category_mapping, brand_mapping,"
                + " naming_template, brand_attribute_key, category_attribute_key, state, created_at"
                + " from listing_collect_claim_rule"
                + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and id=?",
            ruleUuid);
    if (ruleRow == null) throw notFound();
    ListingCollectBoxClaimRuleRow rule = ruleRow(ruleRow);
    if (!"ACTIVE".equals(rule.state())) {
      throw unprocessable("认领规则「" + rule.name() + "」已停用，不能用它认领。");
    }

    // 规则里的映射摊成 Map 供逐条查（对外仍是键值项列表 —— 见 ListingCollectBoxClaimRuleRow）
    Map<String, String> brandMapping = new LinkedHashMap<>();
    for (ListingCollectBoxKeyValue pair : rule.brandMapping()) {
      brandMapping.put(pair.name(), pair.value());
    }
    Map<String, String> categoryMapping = new LinkedHashMap<>();
    for (ListingCollectBoxKeyValue pair : rule.categoryMapping()) {
      categoryMapping.put(pair.name(), pair.value());
    }

    List<ListingCollectBoxClaimOutcome> outcomes = new ArrayList<>();
    int succeeded = 0;

    for (UUID itemId : itemIds) {
      // ---- 取条目与它的采集草稿
      Record item =
          sql.fetchOne(
              "select i.id, i.state, i.draft_id, d.title, d.main_image_url, d.image_urls, d.specs,"
                  + " d.attributes, d.description, d.source_url, d.captured_at,"
                  + " s.code as source_code, s.name as source_name"
                  + " from listing_collect_box_item i"
                  + " join listing_collect_draft d on d.tenant_id=i.tenant_id and d.id=i.draft_id"
                  + " join listing_collect_source s on s.id=d.source_id"
                  + " where i.tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
                  + " and i.id=?",
              itemId);
      if (item == null) {
        outcomes.add(
            new ListingCollectBoxClaimOutcome(itemId, false, "采集箱条目不存在或不可见", null));
        continue;
      }
      if ("CLAIMED".equals(item.get("state", String.class))) {
        outcomes.add(
            new ListingCollectBoxClaimOutcome(itemId, false, "这一条已经认领过了", null));
        continue;
      }

      // ---- 品牌映射（🔴 蓝图加粗句：没有映射就不能认领）
      Map<String, String> attributes = readMap(item.get("attributes"));
      Map<String, String> specs = readMap(item.get("specs"));
      String sourceBrand = lookupValue(attributes, specs, rule.brandAttributeKey());
      if (sourceBrand.isEmpty()) {
        outcomes.add(
            new ListingCollectBoxClaimOutcome(
                itemId,
                false,
                "🔴 草稿里没有「" + rule.brandAttributeKey() + "」属性，无法做品牌映射 —— "
                    + "认领必须有品牌映射。",
                null));
        continue;
      }
      String targetBrand = brandMapping.get(sourceBrand);
      if (targetBrand == null || targetBrand.isBlank()) {
        outcomes.add(
            new ListingCollectBoxClaimOutcome(
                itemId,
                false,
                "🔴 品牌「" + sourceBrand + "」未在认领规则「" + rule.name() + "」的品牌映射里 —— "
                    + "认领必须有品牌映射。",
                null));
        continue;
      }

      // ---- 分类映射（分类映射优先，否则用规则的目标分类）
      String sourceCategory = lookupValue(attributes, specs, rule.categoryAttributeKey());
      String targetCategory = null;
      if (!sourceCategory.isEmpty()) {
        targetCategory = categoryMapping.get(sourceCategory);
      }
      if (targetCategory == null || targetCategory.isBlank()) {
        targetCategory = rule.targetCategoryCode();
      }
      if (targetCategory == null || targetCategory.isBlank()) {
        outcomes.add(
            new ListingCollectBoxClaimOutcome(
                itemId,
                false,
                "🔴 类目「"
                    + (sourceCategory.isEmpty() ? "（空）" : sourceCategory)
                    + "」没有映射到目标分类 —— 认领必须有分类映射或目标分类。",
                null));
        continue;
      }

      // ---- 命名规则
      String title = text(item.get("title", String.class));
      String model = lookupFirst(specs, attributes, MODEL_KEYS);
      String sourceName = text(item.get("source_name", String.class));
      String spuName =
          applyNaming(
              rule.namingTemplate(), targetBrand, title, model, targetCategory, sourceName);
      if (spuName.isEmpty()) {
        outcomes.add(
            new ListingCollectBoxClaimOutcome(
                itemId,
                false,
                "🔴 命名规则「" + rule.namingTemplate() + "」没有产出名称（占位符都取不到值）。",
                null));
        continue;
      }

      // ---- 草稿载荷（🔴 是草稿，不是商品；图片只透传 URL）
      ListingCollectBoxSpuDraft spuDraft =
          new ListingCollectBoxSpuDraft(
              spuName,
              targetCategory,
              targetBrand,
              text(item.get("description", String.class)),
              title,
              text(item.get("main_image_url", String.class)),
              readStringList(item.get("image_urls")),
              readKeyValues(item.get("specs")),
              readKeyValues(item.get("attributes")),
              text(item.get("source_url", String.class)),
              sourceName,
              item.get("captured_at", OffsetDateTime.class));

      List<ListingCollectBoxSkuDraft> skuDrafts = new ArrayList<>();
      String buildNote;
      if (specs.isEmpty()) {
        buildNote =
            "🔴 草稿没有规格信息，SKU 行由 03 建档时按规格轴生成；"
                + "SPU 编码由 03 的编号规则给，本模块不编造。";
      } else {
        skuDrafts.add(
            new ListingCollectBoxSkuDraft(
                spuName, readKeyValues(item.get("specs")), text(item.get("source_url", String.class))));
        buildNote =
            "🔴 上游草稿每个规格轴只有一个取值 ⇒ 只产出**一个观察到的组合**；"
                + "多组合展开、SKU 编码、属性字典 id 与计量单位都由 03 建档补齐。";
      }

      // ---- 落库
      UUID resultId = Ids.next();
      OffsetDateTime claimedAt = now();
      sql.execute(
          "insert into listing_collect_claim_result"
              + " (id,tenant_id,item_id,rule_id,spu_name,target_category_code,target_brand_code,"
              + " source_brand,source_category,spu_draft,sku_drafts,sku_draft_count,build_state,"
              + " build_note,claimed_at,claimed_by,actor_id,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,'PENDING_BUILD',?,?::timestamptz,?,?,"
              + " now(),now())",
          resultId,
          tenant,
          itemId,
          ruleUuid,
          spuName,
          targetCategory,
          targetBrand,
          sourceBrand,
          sourceCategory,
          writeJson(spuDraft),
          writeJson(skuDrafts),
          skuDrafts.size(),
          buildNote,
          claimedAt,
          actor,
          actor);

      sql.execute(
          "update listing_collect_box_item"
              + " set state='CLAIMED', claim_error='', claimed_at=?::timestamptz, updated_at=now()"
              + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
              + " and id=?",
          claimedAt,
          itemId);

      succeeded++;
      outcomes.add(
          new ListingCollectBoxClaimOutcome(
              itemId,
              true,
              "",
              new ListingCollectBoxClaimResultRow(
                  resultId,
                  itemId,
                  ruleUuid,
                  rule.name(),
                  spuName,
                  targetCategory,
                  targetBrand,
                  sourceBrand,
                  sourceCategory,
                  spuDraft,
                  skuDrafts,
                  skuDrafts.size(),
                  "PENDING_BUILD",
                  buildNote,
                  claimedAt)));
    }

    // 失败的那几条要把原因写到条目上 —— 「为什么这一条没认领成功」必须看得见
    for (ListingCollectBoxClaimOutcome outcome : outcomes) {
      if (!outcome.ok()) {
        sql.execute(
            "update listing_collect_box_item"
                + " set claim_error=?, updated_at=now()"
                + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and id=?",
            outcome.reason(),
            outcome.itemId());
      }
    }

    return new ListingCollectBoxClaimResult(
        rule, itemIds.size(), succeeded, itemIds.size() - succeeded, outcomes);
  }

  // ==================================================================
  // 内部
  // ==================================================================

  /** 命名规则：把占位符替换成实值，收敛空白；取不到就留空。 */
  static String applyNaming(
      String template, String brand, String title, String model, String category, String source) {
    String out = template == null ? "" : template;
    out = out.replace("{brand}", brand == null ? "" : brand);
    out = out.replace("{title}", title == null ? "" : title);
    out = out.replace("{model}", model == null ? "" : model);
    out = out.replace("{category}", category == null ? "" : category);
    out = out.replace("{source}", source == null ? "" : source);
    return out.replaceAll("\\s+", " ").trim();
  }

  /** 按属性键取值：先看属性表，再看规格表（同一份草稿里两处都可能有）。 */
  private static String lookupValue(Map<String, String> attributes, Map<String, String> specs, String key) {
    if (key == null || key.isBlank()) return "";
    String value = attributes.get(key);
    if (value == null || value.isBlank()) value = specs.get(key);
    return value == null ? "" : value.trim();
  }

  /** 按候选键列表取值（第一个非空的）。 */
  private static String lookupFirst(
      Map<String, String> primary, Map<String, String> secondary, List<String> keys) {
    for (String key : keys) {
      String value = lookupValue(primary, secondary, key);
      if (!value.isEmpty()) return value;
    }
    return "";
  }

  /** 清理映射：去空白、丢空值、空的键也丢（键值项列表 → Map）。 */
  private static Map<String, String> cleanMapping(List<ListingCollectBoxKeyValue> raw) {
    Map<String, String> out = new LinkedHashMap<>();
    if (raw == null) return out;
    for (ListingCollectBoxKeyValue pair : raw) {
      if (pair == null) continue;
      String key = text(pair.name());
      String value = text(pair.value());
      if (!key.isEmpty() && !value.isEmpty()) out.put(key, value);
    }
    return out;
  }

  /** Map → 键值项列表（保持落库时的顺序；前端按具名键值项渲染，不按 Map 索引）。 */
  private static List<ListingCollectBoxKeyValue> toKeyValues(Map<String, String> raw) {
    List<ListingCollectBoxKeyValue> out = new ArrayList<>();
    raw.forEach((k, v) -> out.add(new ListingCollectBoxKeyValue(k, v)));
    return out;
  }

  private static UUID parseOptionalUuid(String raw, String label) {
    String value = blankToNull(raw);
    if (value == null) return null;
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw badRequest("不是合法的" + label + "：" + raw);
    }
  }

  private ListingCollectBoxItemRow itemRow(Record row, List<ListingCollectBoxTagRef> tags) {
    return new ListingCollectBoxItemRow(
        row.get("id", UUID.class),
        row.get("draft_id", UUID.class),
        row.get("origin", String.class),
        row.get("state", String.class),
        row.get("title", String.class),
        row.get("main_image_url", String.class),
        row.get("source_url", String.class),
        row.get("source_code", String.class),
        row.get("source_name", String.class),
        row.get("source_price_amount", BigDecimal.class),
        row.get("source_price_currency", String.class),
        row.get("captured_at", OffsetDateTime.class),
        tags,
        row.get("claim_error", String.class),
        row.get("claimed_at", OffsetDateTime.class),
        null,
        row.get("created_at", OffsetDateTime.class));
  }

  /**
   * 给一批条目补上**标签**与**认领结果 id**（各一次查询，不 N+1）。
   *
   * <p>🔴 两条补充数据都是「一条读多条」的形状 —— 逐行查会把列表页的查询数变成 O(行数 × 2)，
   * 箱子大一点就能把连接池占满。
   */
  private void attachTags(List<ListingCollectBoxItemRow> items) {
    if (items.isEmpty()) return;
    List<UUID> itemIds = new ArrayList<>();
    for (ListingCollectBoxItemRow item : items) {
      itemIds.add(item.itemId());
    }

    Map<UUID, List<ListingCollectBoxTagRef>> tagsByItem = new LinkedHashMap<>();
    // 🔴 逐个占位符而不是 `= any(?::uuid[])`：jOOQ 3.19 会把数组绑成**单个** uuid 参数
    //    （PG 直接拒："cannot cast type uuid to uuid[]"），所以这里自己拼 IN 列表。
    String inClause = itemIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
    List<Object> tagBinds = new ArrayList<>();
    tagBinds.addAll(itemIds);
    for (Record row :
        sql.fetch(
            "select it.item_id, t.id as tag_id, t.name"
                + " from listing_collect_box_item_tag it"
                + " join listing_collect_box_tag t on t.tenant_id=it.tenant_id and t.id=it.tag_id"
                + " where it.tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and it.item_id in (" + inClause + ")"
                + " order by t.position, t.name",
            tagBinds.toArray())) {
      tagsByItem
          .computeIfAbsent(row.get("item_id", UUID.class), k -> new ArrayList<>())
          .add(new ListingCollectBoxTagRef(row.get("tag_id", UUID.class), row.get("name", String.class)));
    }

    Map<UUID, UUID> claimByItem = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select item_id, id from listing_collect_claim_result"
                + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and item_id in (" + inClause + ")",
            tagBinds.toArray())) {
      claimByItem.put(row.get("item_id", UUID.class), row.get("id", UUID.class));
    }

    for (int index = 0; index < items.size(); index++) {
      ListingCollectBoxItemRow item = items.get(index);
      items.set(
          index,
          new ListingCollectBoxItemRow(
              item.itemId(),
              item.draftId(),
              item.origin(),
              item.state(),
              item.title(),
              item.mainImageUrl(),
              item.sourceUrl(),
              item.sourceCode(),
              item.sourceName(),
              item.sourcePriceAmount(),
              item.sourcePriceCurrency(),
              item.capturedAt(),
              tagsByItem.getOrDefault(item.itemId(), List.of()),
              item.claimError(),
              item.claimedAt(),
              claimByItem.get(item.itemId()),
              item.createdAt()));
    }
  }

  private ListingCollectBoxClaimRuleRow ruleRow(Record row) {
    return new ListingCollectBoxClaimRuleRow(
        row.get("id", UUID.class),
        row.get("name", String.class),
        row.get("target_category_code", String.class),
        toKeyValues(readMap(row.get("category_mapping"))),
        toKeyValues(readMap(row.get("brand_mapping"))),
        row.get("naming_template", String.class),
        row.get("brand_attribute_key", String.class),
        row.get("category_attribute_key", String.class),
        row.get("state", String.class),
        row.get("created_at", OffsetDateTime.class));
  }
}
