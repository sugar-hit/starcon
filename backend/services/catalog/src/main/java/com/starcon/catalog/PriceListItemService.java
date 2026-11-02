package com.starcon.catalog;

import static com.starcon.catalog.PriceListItemModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.catalog.PriceListItemExcelService.ExportRow;
import com.starcon.catalog.PriceListItemRules.Candidate;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.1.1.2 价目项批量维护。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：在价目表内维护 SKU 价格，支持按分类/品牌/SKU 范围批量设价、阶梯价（按数量门槛）、Excel 导入导出，保存前预览影响范围。
 *
 * **输入**：价目项录入/批量操作（固定值、按百分比、按类目统一）；`price_list_item` 记录。
 *
 * **输出**：价目项记录（SKU、单价、货币、数量门槛、生效区间）；导入校验报告。
 *
 * **关系**：上游 = 5.1.1.1、3.1.2.1 SKU；下游 = 5.3 测算、5.4 调价。**同优先级多条有效价目必须报错让用户选**，不随机取（实体目录 catalog 价格规则）。
 *
 * </blockquote>
 *
 * <h2>🔴 本类的七条纪律</h2>
 *
 * <ol>
 *   <li><b>🔴 一价目一币种（下游落法）</b>：价目项**没有**币种列（V431 第三节的接口约定），
 *       币种只能来自所属价目表 ⇒ 价目项在结构上**无法跨币种**。
 *       Excel 导入里的「币种」列只用于**校验**：与本表币种不一致 ⇒ 该行判错、整批不落库。
 *   <li><b>🔴 同优先级多条有效价目 ⇒ 报错让用户选，绝不随机取</b>：
 *       {@link #resolve} 在歧义时抛 {@link PriceAmbiguityException}，
 *       body 里带**候选清单**（与 3.6.2.1 供应商报价的同一手法）。
 *   <li><b>🔴 阶梯不是冲突</b>：解析先**选档**（数量 → {@code min_quantity} 最大的一档），
 *       再**选优先级**。顺序反了会把阶梯价判成冲突。
 *   <li><b>🔴 批量先预览再落库</b>：{@link #preview} 与 {@link #apply} 走**同一个**计划函数
 *       （{@link #plan}）⇒ 预览看到的就是执行会做的。
 *   <li><b>🔴 导入先报后改</b>：任何一行有错 ⇒ **整批不落库**（{@link #importFile}），
 *       报告逐行写明「哪一行、为什么」。
 *   <li><b>🔴 生效区间是半开区间</b>：{@code [validFrom, validTo)} —— 起点含、终点不含。
 *   <li><b>🔴 租户只来自已认证成员关系</b>（{@link AccessService}），不读 {@code X-Tenant-Id} 头。
 *       每条 SQL 的 where 都带显式 {@code tenant_id=} 绑定，RLS 再兜一道。
 * </ol>
 */
@Service
public class PriceListItemService {

  /** 租户上下文取法：RLS 已按它隔离，这里带上只是让执行计划走租户前缀的索引。 */
  private static final String TENANT = "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 台账页大小上限：与相邻模块一致。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 批量预览最多返回多少行明细（命中总数照实报，明细截断会写明）。 */
  private static final int PREVIEW_LINE_LIMIT = 500;

  /** 「按 SKU 清单」批量时最多接受多少个 SKU 编码。 */
  private static final int MAX_SKU_SET = 5000;

  /** 一次 Excel 导入最多处理多少行。 */
  private static final int MAX_IMPORT_ROWS = 5000;

  private final DSLContext sql;
  private final PriceListItemExcelService excel;
  private final ObjectMapper json;

  public PriceListItemService(DSLContext sql, PriceListItemExcelService excel, ObjectMapper json) {
    this.sql = sql;
    this.excel = excel;
    this.json = json;
  }

  // ================================================================ 能力声明

  /** 能力声明：三种批量模式 + 三种批量范围 + 八条口径说明。 */
  public PriceListItemCapabilities capabilities() {
    var notes =
        List.of(
            new CapabilityNote(
                "价目项没有自己的币种",
                "一张价目表只有一个币种（5.1.1.1 的硬约束），价目项**不自带币种列** —— "
                    + "它的币种只能来自所属价目表。所以「同一个 SKU 在同一张表里出现两种币种」"
                    + "在结构上就写不进去。Excel 导入文件里如果带了币种列，那一列只用于**校验**："
                    + "与本表币种不一致 ⇒ 那一行判错、整批不落库。"),
            new CapabilityNote(
                "阶梯价 = 一条一档",
                "一条价目项 = 一个阶梯档位，`数量门槛` 是该档的起始数量，0 = 基础档。"
                    + "同一个 SKU 在同一张表里可以有多行 —— 那是阶梯价，**不是冲突**。"),
            new CapabilityNote(
                "同优先级多条有效价目 ⇒ 报错让用户选",
                "蓝图加粗句的落地：解析「这个 SKU 此刻该卖多少」时，"
                    + "先按数量选档（门槛 ≤ 数量里门槛最大的那一档），再在档内比优先级"
                    + "（**数字越小越优先**）。若最小的优先级命中多条 ⇒ 判定为歧义，"
                    + "接口返回 **409** 并把**候选清单**逐条带出来 —— "
                    + "系统**绝不**随机挑一条，也不会折成一条。"),
            new CapabilityNote(
                "跨价目表的优先级不是本模块",
                "「同 SKU + 同店铺 + 同站点 + 同时段生效的多个价目」的优先级与冲突是 "
                    + "**5.1.2.1** 的职责（它是本表的下游守门员）。"
                    + "本模块只管**同一张价目表内部**的歧义，不建跨表优先级结构。"),
            new CapabilityNote(
                "批量先预览再落库",
                "预览与执行走**同一个**计划函数 ⇒ 预览里的「新增 N 条 / 覆盖 N 条 / 跳过 N 个 / "
                    + "会造成 N 个冲突」就是执行的结果。冲突不会拦住写入 —— "
                    + "拦住写入是 5.1.2.1 的职责；本模块的职责是**把冲突摆出来**。"),
            new CapabilityNote(
                "导入先报后改",
                "Excel 导入先只出**校验报告**（哪一行错、为什么），有错就**整批不落库**。"
                    + "报告逐行写明：SKU 不存在 / 单价不是数字 / 币种与价目表不一致 / "
                    + "生效区间非法 / 文件内重复。改完文件重新上传即可。"),
            new CapabilityNote(
                "生效区间是半开区间",
                "[生效起点, 生效终点)：起点**含**、终点**不含**，终点为空 = 长期有效。"
                    + "两条首尾相接的价目项不会在交界那一刻同时生效 —— 这是冲突判定能成立的前提。"),
            new CapabilityNote(
                "金额与数量一律十进制",
                "单价与数量门槛在库里都是 `NUMERIC(19,6)`，端到端 BigDecimal，"
                    + "**禁浮点**。按百分比换算时只有最后一步才定标到 6 位（中途定标会累计误差）。"));
    return new PriceListItemCapabilities(
        List.of(BatchMode.values()),
        List.of(BatchScope.values()),
        List.of(SelectionState.values()),
        notes,
        "价目项不得自带币种列 —— 币种来自所属价目表（price_list.currency）。",
        "一条价目项 = 一个阶梯档位；数量门槛 0 = 基础档。",
        "数字越小优先级越高（与 3.6.2.1 供应商报价同一约定）。",
        "同优先级多条有效价目 ⇒ 409 + 候选清单，系统不随机取。",
        "导入先出校验报告，有错整批不落库（先报后改）。",
        "导出是真实的 xlsx 工作簿（Apache POI 生成），时间列写带偏移的 ISO-8601。",
        "上游 = 5.1.1.1 价目表（币种与生效区间）+ 3.1.2.1 SKU（编码、名称、分类、品牌）。",
        "下游 = 5.3 测算、5.4 调价（按本表的解析结果出单与调价）。");
  }

  // ================================================================ 内部形状

  /** 价目表头（币种与生效区间都从这里来）。 */
  record PriceListHeader(
      UUID id, String code, String name, String currency, OffsetDateTime validFrom,
      OffsetDateTime validTo, boolean enabled) {}

  /** 一个 SKU（分类与品牌从 spu 带过来）。 */
  record SkuRef(UUID id, String code, String name, String category, String brand, String status) {}

  /** 批量计划里的一行（预览与执行共用）。 */
  record PlanEntry(
      SkuRef sku,
      BigDecimal newPrice,
      BigDecimal basePrice,
      UUID updateItemId,
      BigDecimal minQuantity,
      int priority,
      OffsetDateTime validFrom,
      OffsetDateTime validTo,
      boolean enabled,
      String note,
      String action,
      String reason,
      boolean conflict) {}

  /** 批量计划。 */
  record BatchPlan(List<PlanEntry> entries, List<String> warnings, int matched, int created, int updated,
      int skipped, int conflictSkus) {}

  // ================================================================ 读

  /** 价目项列表的筛选条件。 */
  public record ItemQuery(String keyword, Boolean enabled, Boolean effectiveNow, String skuCode, int page, int size) {}

  /** 价目项台账：真分页 + 关键词 / 启用状态 / 此刻是否生效筛选。 */
  public PriceListItemPage list(UUID priceListId, ItemQuery query) {
    var header = requirePriceList(priceListId);
    int limited = Math.min(Math.max(query.size() <= 0 ? 20 : query.size(), 1), MAX_PAGE_SIZE);
    int offset = Math.max(query.page(), 0) * limited;
    var where = new StringBuilder(" where i.tenant_id=" + TENANT + " and i.price_list_id=?");
    var params = new ArrayList<Object>();
    params.add(priceListId);
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.append(" and (s.code ilike ? or s.name ilike ?)");
      var pattern = "%" + query.keyword().trim() + "%";
      params.add(pattern);
      params.add(pattern);
    }
    if (query.skuCode() != null && !query.skuCode().isBlank()) {
      where.append(" and s.code=?");
      params.add(query.skuCode().trim().toUpperCase(Locale.ROOT));
    }
    if (query.enabled() != null) {
      where.append(" and i.enabled=?");
      params.add(query.enabled());
    }
    if (query.effectiveNow() != null) {
      where.append(
          query.effectiveNow()
              ? " and i.valid_from <= now() and (i.valid_to is null or i.valid_to > now())"
              : " and (i.valid_from > now() or (i.valid_to is not null and i.valid_to <= now()))");
    }
    var rows =
        sql.fetch(
            "select i.id,i.price_list_id,i.sku_id,s.code as sku_code,s.name as sku_name,"
                + " i.unit_price,i.min_quantity,i.priority,i.valid_from,i.valid_to,i.enabled,"
                + " i.note,i.version,i.created_by,i.created_at,i.updated_at,"
                + " (i.valid_from <= now() and (i.valid_to is null or i.valid_to > now())) as effective_now"
                + " from price_list_item i"
                + " join sku s on s.tenant_id=i.tenant_id and s.id=i.sku_id"
                + where
                + " order by i.updated_at desc, i.id"
                + " limit ? offset ?",
            concat(params, limited, offset));
    long total =
        sql.fetchOne(
                "select count(*) from price_list_item i"
                    + " join sku s on s.tenant_id=i.tenant_id and s.id=i.sku_id"
                    + where,
                params.toArray())
            .get(0, Long.class);
    var items = new ArrayList<PriceListItemView>();
    for (var row : rows) {
      items.add(viewOf(row, header));
    }
    return new PriceListItemPage(items, Math.max(query.page(), 0), limited, total);
  }

  /** 单条价目项。 */
  public PriceListItemView detail(UUID priceListId, UUID itemId) {
    var header = requirePriceList(priceListId);
    var row =
        sql.fetchOne(
            "select i.id,i.price_list_id,i.sku_id,s.code as sku_code,s.name as sku_name,"
                + " i.unit_price,i.min_quantity,i.priority,i.valid_from,i.valid_to,i.enabled,"
                + " i.note,i.version,i.created_by,i.created_at,i.updated_at,"
                + " (i.valid_from <= now() and (i.valid_to is null or i.valid_to > now())) as effective_now"
                + " from price_list_item i"
                + " join sku s on s.tenant_id=i.tenant_id and s.id=i.sku_id"
                + " where i.tenant_id="
                + TENANT
                + " and i.price_list_id=? and i.id=?",
            priceListId,
            itemId);
    if (row == null) throw notFound("价目项不存在");
    return viewOf(row, header);
  }

  // ================================================================ 写

  /**
   * 新建 / 修改价目项（修改走乐观锁）。
   *
   * <p>🔴 币种不接受入参：价目项的币种来自所属价目表。
   */
  public PriceListItemView save(
      AccessService.Access actor, UUID priceListId, UUID itemId, PriceListItemInput input) {
    var header = requirePriceList(priceListId);
    var normalized = normalize(input);
    var sku = requireSku(normalized.skuCode());
    if (itemId == null) {
      var duplicate =
          sql.fetchOne(
              "select id from price_list_item where tenant_id="
                  + TENANT
                  + " and price_list_id=? and sku_id=? and min_quantity=? and valid_from=?::timestamptz",
              priceListId,
              sku.id(),
              normalized.minQuantity(),
              normalized.validFrom());
      if (duplicate != null) {
        throw conflict(
            "这个 SKU 在这一档、同一个生效起点上已经有一条价目项了 —— "
                + "同一个瞬间、同一档、两条价目在结构上就是歧义，请改那一条或者换一个生效起点");
      }
      var id = UUID.randomUUID();
      sql.execute(
          "insert into price_list_item(id,tenant_id,price_list_id,sku_id,unit_price,min_quantity,"
              + "priority,valid_from,valid_to,enabled,note,version,created_by,created_at,updated_at)"
              + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?::timestamptz,?::timestamptz,?,?,0,?::uuid,now(),now())",
          id,
          actor.tenantId(),
          priceListId,
          sku.id(),
          normalized.unitPrice(),
          normalized.minQuantity(),
          normalized.priority(),
          normalized.validFrom(),
          normalized.validTo(),
          normalized.enabled(),
          normalized.note(),
          actor.userId());
      return detail(priceListId, id);
    }
    var current =
        sql.fetchOne(
            "select version from price_list_item where tenant_id="
                + TENANT
                + " and price_list_id=? and id=? for update",
            priceListId,
            itemId);
    if (current == null) throw notFound("价目项不存在");
    long currentVersion = current.get(0, Long.class);
    if (input.version() == null || input.version() != currentVersion) {
      throw conflict(
          "这条价目项已经被别人改过了（当前版本 "
              + currentVersion
              + "，你提交的是 "
              + input.version()
              + "）。请刷新后重新提交 —— 覆盖别人的改动会让价格对不上账");
    }
    var duplicate =
        sql.fetchOne(
            "select id from price_list_item where tenant_id="
                + TENANT
                + " and price_list_id=? and sku_id=? and min_quantity=? and valid_from=?::timestamptz and id<>?",
            priceListId,
            sku.id(),
            normalized.minQuantity(),
            normalized.validFrom(),
            itemId);
    if (duplicate != null) {
      throw conflict(
          "这个 SKU 在这一档、同一个生效起点上已经有一条价目项了 —— "
              + "同一个瞬间、同一档、两条价目在结构上就是歧义");
    }
    sql.execute(
        "update price_list_item set sku_id=?,unit_price=?,min_quantity=?,priority=?,"
            + " valid_from=?::timestamptz, valid_to=?::timestamptz, enabled=?, note=?,"
            + " version=version+1, updated_at=now()"
            + " where tenant_id="
            + TENANT
            + " and price_list_id=? and id=?",
        sku.id(),
        normalized.unitPrice(),
        normalized.minQuantity(),
        normalized.priority(),
        normalized.validFrom(),
        normalized.validTo(),
        normalized.enabled(),
        normalized.note(),
        priceListId,
        itemId);
    return detail(priceListId, itemId);
  }

  /** 删除价目项。 */
  public void delete(UUID priceListId, UUID itemId) {
    var deleted =
        sql.execute(
            "delete from price_list_item where tenant_id=" + TENANT + " and price_list_id=? and id=?",
            priceListId,
            itemId);
    if (deleted == 0) throw notFound("价目项不存在");
  }

  // ================================================================ 冲突与解析

  /**
   * 本表内的冲突清单：同一个 SKU、同一数量门槛、同一优先级，且生效区间重叠的多条有效价目。
   *
   * <p>🔴 这正是蓝图加粗句「同优先级多条有效价目必须报错让用户选」的具象：
   * 在报出的重叠窗口里，这个 SKU 的这一档**无法唯一解析**。
   */
  public List<PriceListItemConflict> conflicts(UUID priceListId) {
    requirePriceList(priceListId);
    var pairs =
        sql.fetch(
            "select a.sku_id, a.min_quantity, a.priority,"
                + " a.id as a_id, a.valid_from as a_from, a.valid_to as a_to,"
                + " b.id as b_id, b.valid_from as b_from, b.valid_to as b_to"
                + " from price_list_item a"
                + " join price_list_item b on b.tenant_id=a.tenant_id and b.price_list_id=a.price_list_id"
                + "   and b.sku_id=a.sku_id and b.min_quantity=a.min_quantity and b.priority=a.priority"
                + "   and b.id>a.id"
                + " where a.tenant_id="
                + TENANT
                + " and a.price_list_id=? and a.enabled and b.enabled"
                + " and a.valid_from < coalesce(b.valid_to,'infinity'::timestamptz)"
                + " and b.valid_from < coalesce(a.valid_to,'infinity'::timestamptz)"
                + " order by a.sku_id, a.min_quantity, a.priority, a.id, b.id",
            priceListId);
    if (pairs.isEmpty()) return List.of();
    // 按 (sku, 档位, 优先级) 归组：组内所有互相重叠的价目项一起报出来。
    var groups = new LinkedHashMap<String, List<Record>>();
    var windows = new LinkedHashMap<String, OffsetDateTime[]>();
    for (var pair : pairs) {
      var skuId = pair.get("sku_id", UUID.class);
      var minQuantity = pair.get("min_quantity", BigDecimal.class);
      var priority = pair.get("priority", Integer.class);
      var key = skuId + "|" + minQuantity + "|" + priority;
      groups.computeIfAbsent(key, k -> new ArrayList<>()).add(pair);
      var overlap =
          PriceListItemRules.overlapOf(
              pair.get("a_from", OffsetDateTime.class),
              pair.get("a_to", OffsetDateTime.class),
              pair.get("b_from", OffsetDateTime.class),
              pair.get("b_to", OffsetDateTime.class));
      var existing = windows.get(key);
      if (existing == null) {
        windows.put(key, overlap);
      } else {
        // 窗口取「所有重叠对里最新的起点」与「最早的终点」——
        // 这样报出来的窗口一定落在某一对真正的重叠区间内（不会报出一个并不重叠的窗口）。
        windows.put(
            key,
            new OffsetDateTime[] {
              existing[0].isAfter(overlap[0]) ? existing[0] : overlap[0],
              earlier(existing[1], overlap[1])
            });
      }
    }
    var out = new ArrayList<PriceListItemConflict>();
    for (var entry : groups.entrySet()) {
      var key = entry.getKey();
      var ids = new LinkedHashSet<UUID>();
      for (var pair : entry.getValue()) {
        ids.add(pair.get("a_id", UUID.class));
        ids.add(pair.get("b_id", UUID.class));
      }
      var first = entry.getValue().get(0);
      var sku = skuById(first.get("sku_id", UUID.class));
      var candidates = new ArrayList<PriceListItemCandidate>();
      for (var id : ids) {
        candidates.add(candidateOf(id, SelectionState.AMBIGUOUS.name()));
      }
      var window = windows.get(key);
      out.add(
          new PriceListItemConflict(
              first.get("sku_id", UUID.class),
              sku == null ? "" : sku.code(),
              sku == null ? "" : sku.name(),
              first.get("min_quantity", BigDecimal.class),
              first.get("priority", Integer.class),
              window == null ? null : window[0],
              window == null ? null : window[1],
              candidates));
    }
    return out;
  }

  /**
   * 解析「这个 SKU 在这张价目表此刻该卖多少」。
   *
   * <p>🔴 命中多条**同优先级**的有效价目 ⇒ 抛 {@link PriceAmbiguityException}（409 + 候选清单），
   * **绝不随便挑一条**。
   *
   * <p>没有生效价目项 ⇒ 正常返回（{@code selected = null} + 说明）：
   * 「还没定价」是常态，不是错误。
   */
  public PriceListItemResolution resolve(
      UUID priceListId, String skuCode, BigDecimal quantity, OffsetDateTime at) {
    var header = requirePriceList(priceListId);
    var sku = requireSku(skuCode);
    var moment = at == null ? now() : at;
    var rows =
        sql.fetch(
            "select id, unit_price, min_quantity, priority, valid_from, valid_to, enabled"
                + " from price_list_item where tenant_id="
                + TENANT
                + " and price_list_id=? and sku_id=? and enabled"
                + " and valid_from <= ?::timestamptz and (valid_to is null or valid_to > ?::timestamptz)"
                + " order by min_quantity, priority, valid_from, id",
            priceListId,
            sku.id(),
            moment,
            moment);
    var candidates = new ArrayList<Candidate>();
    for (var row : rows) {
      candidates.add(
          new Candidate(
              row.get("id", UUID.class),
              row.get("min_quantity", BigDecimal.class),
              row.get("priority", Integer.class),
              row.get("unit_price", BigDecimal.class)));
    }
    var resolution = PriceListItemRules.resolve(candidates, quantity);
    var views = new ArrayList<PriceListItemCandidate>();
    for (int i = 0; i < rows.size(); i++) {
      var row = rows.get(i);
      views.add(
          new PriceListItemCandidate(
              row.get("id", UUID.class),
              sku.code(),
              sku.name(),
              row.get("unit_price", BigDecimal.class),
              row.get("min_quantity", BigDecimal.class),
              row.get("priority", Integer.class),
              row.get("valid_from", OffsetDateTime.class),
              row.get("valid_to", OffsetDateTime.class),
              resolution.states().get(i),
              SelectionState.of(resolution.states().get(i)) == null
                  ? resolution.states().get(i)
                  : SelectionState.of(resolution.states().get(i)).label()));
    }
    var warnings = new ArrayList<String>();
    if (candidates.isEmpty()) {
      warnings.add(
          "SKU " + sku.code() + " 在 " + header.code() + " 这张价目表里此刻没有生效的价目项（还没定价，或都已停用 / 不在有效期内）");
    } else if (resolution.pool().isEmpty()) {
      var lowest =
          candidates.stream()
              .map(c -> c.minQuantity() == null ? BigDecimal.ZERO : c.minQuantity())
              .min(BigDecimal::compareTo)
              .orElse(BigDecimal.ZERO);
      warnings.add(
          "该 SKU 只有阶梯价（最低门槛 "
              + PriceListItemRules.quantityText(lowest)
              + "），本次数量下没有适用档位 —— 请给出数量再解析");
    }
    PriceListItemView selected = null;
    BigDecimal unitPrice = null;
    if (resolution.selected() != null) {
      selected = detail(priceListId, resolution.selected().id());
      unitPrice = resolution.selected().unitPrice();
    }
    var ambiguity =
        resolution.ambiguous()
            ? PriceListItemRules.ambiguityProblem(
                sku.code(),
                resolution.appliedMinQuantity(),
                resolution.pool().size())
            : null;
    var result =
        new PriceListItemResolution(
            header.id(),
            header.code(),
            header.currency(),
            sku.id(),
            sku.code(),
            sku.name(),
            moment,
            quantity,
            selected,
            unitPrice,
            views,
            resolution.ambiguous(),
            ambiguity,
            warnings,
            "解析顺序：先按数量选档（门槛 ≤ 数量里门槛最大的那一档），再在档内比优先级"
                + "（数字越小越优先）。同优先级多条有效价目时 selected 恒为 null —— 系统不随机取一条。");
    if (resolution.ambiguous()) {
      throw new PriceAmbiguityException(ambiguity, result);
    }
    return result;
  }

  /**
   * 歧义异常：同优先级多条有效价目。
   *
   * <p>🔴 「报错让用户选」的后半句要求错误里**带得出候选** ——
   * 只回一句「存在多条有效价目」的话，运营不知道该去改哪几条。
   */
  public static final class PriceAmbiguityException extends RuntimeException {
    private final PriceListItemResolution resolution;

    PriceAmbiguityException(String reason, PriceListItemResolution resolution) {
      super(reason);
      this.resolution = resolution;
    }

    public PriceListItemResolution resolution() {
      return resolution;
    }
  }

  // ================================================================ 批量

  /**
   * 批量设价的**影响范围预览**（蓝图职责句：「保存前预览影响范围」）。
   *
   * <p>🔴 与 {@link #apply} 走**同一个**计划函数 ⇒ 预览即执行。
   */
  public PriceListItemBatchPreview preview(UUID priceListId, PriceListItemBatchRequest request) {
    var header = requirePriceList(priceListId);
    var plan = plan(priceListId, header, request);
    var lines = new ArrayList<PriceListItemBatchLine>();
    int limit = Math.min(PREVIEW_LINE_LIMIT, Math.max(plan.entries().size(), 1));
    for (int i = 0; i < plan.entries().size() && i < limit; i++) {
      var entry = plan.entries().get(i);
      lines.add(
          new PriceListItemBatchLine(
              entry.sku().id(),
              entry.sku().code(),
              entry.sku().name(),
              entry.sku().category(),
              entry.sku().brand(),
              request.mode() == BatchMode.PERCENTAGE ? entry.basePrice() : null,
              entry.newPrice(),
              entry.action(),
              entry.reason(),
              entry.conflict(),
              entry.minQuantity(),
              entry.priority()));
    }
    var conflicts = plan.conflictSkus() > 0 ? conflictPlan(priceListId, plan) : List.<PriceListItemConflict>of();
    return new PriceListItemBatchPreview(
        request.mode().name(),
        request.mode().label(),
        request.scope() == null ? "" : request.scope().name(),
        request.scope() == null ? "" : request.scope().label(),
        header.currency(),
        plan.matched(),
        plan.created(),
        plan.updated(),
        plan.skipped(),
        plan.conflictSkus(),
        lines,
        limit,
        plan.entries().size() > limit,
        conflicts,
        plan.warnings(),
        "命中 " + plan.matched() + " 个 SKU：新增 " + plan.created() + " 条、覆盖 " + plan.updated()
            + " 条、跳过 " + plan.skipped() + " 个；其中 " + plan.conflictSkus()
            + " 个 SKU 会造成同优先级冲突（写入不会被拦，但解析时会报歧义 —— "
            + "要去 5.1.2.1 那一步或者调整优先级）");
  }

  /** 执行批量设价。 */
  public PriceListItemBatchResult apply(
      AccessService.Access actor, UUID priceListId, PriceListItemBatchRequest request) {
    var header = requirePriceList(priceListId);
    var plan = plan(priceListId, header, request);
    for (var entry : plan.entries()) {
      if ("SKIP".equals(entry.action())) continue;
      if (entry.updateItemId() != null) {
        sql.execute(
            "update price_list_item set unit_price=?, priority=?, valid_from=?::timestamptz,"
                + " valid_to=?::timestamptz, enabled=?, note=?, version=version+1, updated_at=now()"
                + " where tenant_id="
                + TENANT
                + " and price_list_id=? and id=?",
            entry.newPrice(),
            entry.priority(),
            entry.validFrom(),
            entry.validTo(),
            entry.enabled(),
            entry.note(),
            priceListId,
            entry.updateItemId());
      } else {
        sql.execute(
            "insert into price_list_item(id,tenant_id,price_list_id,sku_id,unit_price,min_quantity,"
                + "priority,valid_from,valid_to,enabled,note,version,created_by,created_at,updated_at)"
                + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?::timestamptz,?::timestamptz,?,?,0,?::uuid,now(),now())",
            UUID.randomUUID(),
            actor.tenantId(),
            priceListId,
            entry.sku().id(),
            entry.newPrice(),
            entry.minQuantity(),
            entry.priority(),
            entry.validFrom(),
            entry.validTo(),
            entry.enabled(),
            entry.note(),
            actor.userId());
      }
    }
    var conflicts = plan.conflictSkus() > 0 ? conflictPlan(priceListId, plan) : List.<PriceListItemConflict>of();
    return new PriceListItemBatchResult(
        request.mode().name(),
        header.currency(),
        plan.matched(),
        plan.created(),
        plan.updated(),
        plan.skipped(),
        plan.conflictSkus(),
        conflicts,
        plan.warnings(),
        "已落库：新增 " + plan.created() + " 条、覆盖 " + plan.updated() + " 条、跳过 " + plan.skipped()
            + " 个 SKU；其中 " + plan.conflictSkus() + " 个 SKU 存在同优先级冲突（解析时会报歧义）");
  }

  /**
   * 算出批量计划（**预览与执行共用**）。
   *
   * <p>🔴 槽位规则：一条价目项的身份是 {@code (SKU, 数量门槛, 生效起点)}
   * —— 与库里的唯一约束逐字一致。已有同槽位 ⇒ **覆盖**；没有 ⇒ **新增**。
   * 这样批量设价**不会**莫名其妙改掉别的行，也不会留下「旧行还开着、新行又插进来」的空洞。
   */
  BatchPlan plan(UUID priceListId, PriceListHeader header, PriceListItemBatchRequest request) {
    var mode = request.mode();
    if (mode == null) throw badRequest("必须指定批量模式（固定值 / 按百分比 / 按类目统一）");
    if (request.validFrom() == null) throw badRequest("生效起点必填");
    if (!PriceListItemRules.validInterval(request.validFrom(), request.validTo())) {
      throw badRequest("生效终点必须晚于生效起点（生效区间是半开区间 [起点, 终点)，终点为空 = 长期有效）");
    }
    var minQuantity = quantity(request.minQuantity(), "数量门槛");
    var priority = PriceListItemRules.normalizePriority(request.priority());
    var enabled = request.enabled() == null ? Boolean.TRUE : request.enabled();
    var note = request.note() == null ? "" : request.note().trim();
    var warnings = new ArrayList<String>();
    var entries = new ArrayList<PlanEntry>();

    if (mode == BatchMode.CATEGORY_UNIFIED) {
      var prices = request.categoryPrices();
      if (prices == null || prices.isEmpty()) {
        throw badRequest("「按类目统一」必须给分类与价格清单（categoryPrices）");
      }
      for (var price : prices) {
        if (price == null || price.category() == null || price.category().isBlank()) {
          throw badRequest("「按类目统一」的分类必填");
        }
        var category = price.category().trim();
        var unitPrice = price(price.unitPrice(), "单价");
        var skus = skusOfCategory(category);
        if (skus.isEmpty()) {
          warnings.add("分类「" + category + "」下没有找到 SKU —— 分类名取自 spu.category，请核对写法");
        }
        for (var sku : skus) {
          entries.add(
              planEntry(
                  priceListId, sku, unitPrice, minQuantity, priority, request, enabled, note, null));
        }
      }
    } else {
      var scope = request.scope();
      if (scope == null) throw badRequest("必须指定批量范围（按分类 / 按品牌 / 按 SKU 清单）");
      BigDecimal fixedPrice = null;
      BigDecimal percent = null;
      if (mode == BatchMode.FIXED) {
        fixedPrice = price(request.unitPrice(), "单价");
      } else {
        percent = request.percent();
        if (percent == null) throw badRequest("「按百分比」必须给涨跌幅百分比（10 = 涨 10%，-10 = 降 10%）");
      }
      var skus = new ArrayList<SkuRef>();
      switch (scope) {
        case CATEGORY -> {
          if (request.category() == null || request.category().isBlank()) {
            throw badRequest("范围是「按分类」时必须给分类（分类名取自 spu.category）");
          }
          var category = request.category().trim();
          skus.addAll(skusOfCategory(category));
          if (skus.isEmpty()) {
            warnings.add("分类「" + category + "」下没有找到 SKU —— 分类名取自 spu.category，请核对写法");
          }
        }
        case BRAND -> {
          if (request.brand() == null || request.brand().isBlank()) {
            throw badRequest("范围是「按品牌」时必须给品牌（品牌名取自 spu.brand）");
          }
          var brand = request.brand().trim();
          skus.addAll(skusOfBrand(brand));
          if (skus.isEmpty()) {
            warnings.add("品牌「" + brand + "」下没有找到 SKU —— 品牌名取自 spu.brand，请核对写法");
          }
        }
        case SKU_SET -> {
          var codes = request.skuCodes();
          if (codes == null || codes.isEmpty()) {
            throw badRequest("范围是「按 SKU 清单」时必须给 SKU 编码清单");
          }
          if (codes.size() > MAX_SKU_SET) {
            throw badRequest("一次最多处理 " + MAX_SKU_SET + " 个 SKU（你给了 " + codes.size() + " 个）");
          }
          var found = new LinkedHashMap<String, SkuRef>();
          for (var code : codes) {
            var normalizedCode = PriceListItemRules.normalizeSkuCode(code);
            var sku = skuByCode(normalizedCode);
            if (sku == null) {
              entries.add(
                  new PlanEntry(
                      new SkuRef(null, normalizedCode, "", null, null, "MISSING"),
                      null,
                      null,
                      null,
                      minQuantity,
                      priority,
                      request.validFrom(),
                      request.validTo(),
                      enabled,
                      note,
                      "SKIP",
                      "SKU 不存在（编码 " + normalizedCode + "）",
                      false));
              continue;
            }
            found.put(normalizedCode, sku);
          }
          skus.addAll(found.values());
        }
      }
      for (var sku : skus) {
        if (mode == BatchMode.FIXED) {
          entries.add(
              planEntry(
                  priceListId, sku, fixedPrice, minQuantity, priority, request, enabled, note, null));
        } else {
          var base = basePrice(priceListId, sku.id(), minQuantity, request.validFrom());
          if (base == null) {
            entries.add(
                new PlanEntry(
                    sku,
                    null,
                    null,
                    null,
                    minQuantity,
                    priority,
                    request.validFrom(),
                    request.validTo(),
                    enabled,
                    note,
                    "SKIP",
                    "没有基准价 —— 这张价目表里该 SKU 在 " + request.validFrom() + " 还没有生效的价目项，无法按百分比换算",
                    false));
            continue;
          }
          BigDecimal newPrice;
          try {
            newPrice = PriceListItemRules.applyPercent(base, percent);
          } catch (IllegalArgumentException e) {
            entries.add(
                new PlanEntry(
                    sku,
                    null,
                    base,
                    null,
                    minQuantity,
                    priority,
                    request.validFrom(),
                    request.validTo(),
                    enabled,
                    note,
                    "SKIP",
                    e.getMessage(),
                    false));
            continue;
          }
          entries.add(
              planEntry(
                  priceListId, sku, newPrice, minQuantity, priority, request, enabled, note, base));
        }
      }
    }
    int created = 0;
    int updated = 0;
    int skipped = 0;
    int matched = 0;
    var conflictSkus = new LinkedHashSet<UUID>();
    for (var entry : entries) {
      if ("SKIP".equals(entry.action())) {
        skipped++;
        continue;
      }
      matched++;
      if (entry.updateItemId() != null) updated++;
      else created++;
      if (entry.conflict()) conflictSkus.add(entry.sku().id());
    }
    return new BatchPlan(entries, warnings, matched, created, updated, skipped, conflictSkus.size());
  }

  /** 计划里的一行：定动作（新增 / 覆盖）、定冲突。 */
  private PlanEntry planEntry(
      UUID priceListId,
      SkuRef sku,
      BigDecimal newPrice,
      BigDecimal minQuantity,
      int priority,
      PriceListItemBatchRequest request,
      boolean enabled,
      String note,
      BigDecimal base) {
    var slot =
        sql.fetchOne(
            "select id from price_list_item where tenant_id="
                + TENANT
                + " and price_list_id=? and sku_id=? and min_quantity=? and valid_from=?::timestamptz",
            priceListId,
            sku.id(),
            minQuantity,
            request.validFrom());
    var updateId = slot == null ? null : slot.get(0, UUID.class);
    // 冲突判定：同 SKU、同档位、**同优先级**、生效区间重叠的另一条有效价目项。
    var others =
        sql.fetchOne(
            "select id from price_list_item where tenant_id="
                + TENANT
                + " and price_list_id=? and sku_id=? and min_quantity=? and priority=? and enabled"
                + " and id<>?"
                + " and valid_from < coalesce(?::timestamptz,'infinity'::timestamptz)"
                + " and ?::timestamptz < coalesce(valid_to,'infinity'::timestamptz)",
            priceListId,
            sku.id(),
            minQuantity,
            priority,
            updateId == null ? UUID.randomUUID() : updateId,
            request.validTo(),
            request.validFrom());
    var conflict = others != null;
    var reason =
        conflict
            ? "会造成同优先级冲突：这档上已有一条同优先级且生效区间重叠的价目项"
            : null;
    return new PlanEntry(
        sku,
        newPrice,
        base,
        updateId,
        minQuantity,
        priority,
        request.validFrom(),
        request.validTo(),
        enabled,
        note,
        updateId == null ? "CREATE" : "UPDATE",
        reason,
        conflict);
  }

  /** 计划涉及的冲突清单（把计划里的 SKU 真正去库里查一遍冲突）。 */
  private List<PriceListItemConflict> conflictPlan(UUID priceListId, BatchPlan plan) {
    var skuIds = plan.entries().stream()
        .filter(e -> !"SKIP".equals(e.action()))
        .map(e -> e.sku().id())
        .distinct()
        .toList();
    if (skuIds.isEmpty()) return List.of();
    var all = conflicts(priceListId);
    return all.stream().filter(c -> skuIds.contains(c.skuId())).toList();
  }

  /** 基准价：优先取同一档位的价目项，其次取此刻生效的最高档。 */
  private BigDecimal basePrice(
      UUID priceListId, UUID skuId, BigDecimal minQuantity, OffsetDateTime at) {
    var row =
        sql.fetchOne(
            "select unit_price from price_list_item where tenant_id="
                + TENANT
                + " and price_list_id=? and sku_id=? and enabled"
                + " and valid_from <= ?::timestamptz and (valid_to is null or valid_to > ?::timestamptz)"
                + " order by (min_quantity = ?) desc, min_quantity desc limit 1",
            priceListId,
            skuId,
            at,
            at,
            minQuantity);
    return row == null ? null : row.get(0, BigDecimal.class);
  }

  // ================================================================ Excel

  /** 导出成 xlsx 字节（真实工作簿，Apache POI 生成）。 */
  public byte[] exportXlsx(UUID priceListId) {
    var header = requirePriceList(priceListId);
    var rows =
        sql.fetch(
            "select s.code as sku_code, s.name as sku_name, i.unit_price, i.min_quantity, i.priority,"
                + " i.valid_from, i.valid_to, i.enabled, i.note"
                + " from price_list_item i"
                + " join sku s on s.tenant_id=i.tenant_id and s.id=i.sku_id"
                + " where i.tenant_id="
                + TENANT
                + " and i.price_list_id=?"
                + " order by s.code, i.min_quantity, i.valid_from, i.id",
            priceListId);
    var data = new ArrayList<ExportRow>();
    for (var row : rows) {
      data.add(
          new ExportRow(
              row.get("sku_code", String.class),
              row.get("sku_name", String.class),
              row.get("unit_price", BigDecimal.class),
              row.get("min_quantity", BigDecimal.class),
              row.get("priority", Integer.class),
              row.get("valid_from", OffsetDateTime.class),
              row.get("valid_to", OffsetDateTime.class),
              Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
              row.get("note", String.class)));
    }
    return excel.export(data, header.currency(), header.code());
  }

  /** 导出文件名。 */
  public String exportFileName(UUID priceListId) {
    var header = requirePriceList(priceListId);
    return excel.exportFileName(header.code());
  }

  /**
   * Excel 导入：**先出校验报告**，有错**整批不落库**。
   *
   * <p>🔴 {@code apply=false}（默认）时只校验；{@code apply=true} 时校验通过才落库。
   * 有任何一行有错 ⇒ 状态 REJECTED、**什么都没写** —— 「先报后改」。
   */
  public PriceListItemImportReport importFile(
      AccessService.Access actor, UUID priceListId, PriceListItemImportRequest request) {
    var header = requirePriceList(priceListId);
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(request.contentBase64().trim());
    } catch (IllegalArgumentException e) {
      throw badRequest("文件内容不是合法的 Base64 —— 浏览器上传的是文件字节的 Base64 编码");
    }
    var parsed = excel.parse(bytes);
    if (!parsed.errors().isEmpty()) {
      var batchId = UUID.randomUUID();
      sql.execute(
          "insert into price_list_import_batch(id,tenant_id,price_list_id,file_name,status,"
              + "total_rows,ok_rows,error_rows,warning_rows,applied_rows,actor_id,created_at)"
              + " values(?::uuid,?::uuid,?::uuid,?, 'REJECTED',0,0,0,0,0,?::uuid,now())",
          batchId,
          actor.tenantId(),
          priceListId,
          request.fileName(),
          actor.userId());
      return new PriceListItemImportReport(
          batchId,
          request.fileName(),
          "REJECTED",
          0,
          0,
          0,
          0,
          0,
          false,
          List.of(),
          "文件整体不可用：" + String.join("；", parsed.errors()) + "（没有落库任何数据）");
    }
    if (parsed.rows().size() > MAX_IMPORT_ROWS) {
      throw badRequest(
          "一次最多导入 " + MAX_IMPORT_ROWS + " 行（文件里有 " + parsed.rows().size() + " 行）");
    }
    var batchId = UUID.randomUUID();
    // 🔴 批次行**先**落库 —— 明细行的触发器要求批次已存在（零外键的引用校验）。
    sql.execute(
        "insert into price_list_import_batch(id,tenant_id,price_list_id,file_name,status,"
            + "total_rows,ok_rows,error_rows,warning_rows,applied_rows,actor_id,created_at)"
            + " values(?::uuid,?::uuid,?::uuid,?, 'PREVIEW',0,0,0,0,0,?::uuid,now())",
        batchId,
        actor.tenantId(),
        priceListId,
        request.fileName(),
        actor.userId());
    var reports = new ArrayList<PriceListItemImportRowReport>();
    var seen = new LinkedHashMap<String, Integer>();
    var okCount = 0;
    var errorCount = 0;
    var warningCount = 0;
    for (var raw : parsed.rows()) {
      var messages = new ArrayList<String>();
      var skuCode = raw.skuCode() == null ? "" : raw.skuCode().trim();
      String skuName = null;
      UUID skuId = null;
      BigDecimal unitPrice = null;
      BigDecimal minQuantity = BigDecimal.ZERO;
      int priority = 0;
      OffsetDateTime validFrom = null;
      OffsetDateTime validTo = null;
      var declaredCurrency = raw.currency() == null ? "" : raw.currency().trim().toUpperCase(Locale.ROOT);
      var enabled = true;

      if (skuCode.isEmpty()) {
        messages.add("SKU 编码为空");
      } else {
        try {
          skuCode = PriceListItemRules.normalizeSkuCode(skuCode);
        } catch (IllegalArgumentException e) {
          messages.add(e.getMessage());
        }
        var sku = skuCode.isEmpty() ? null : skuByCode(skuCode);
        if (sku == null && skuCode.isEmpty() == false && messages.isEmpty()) {
          messages.add("SKU 不存在（编码 " + skuCode + "）");
        }
        if (sku != null) {
          skuId = sku.id();
          skuName = sku.name();
        }
      }
      if (raw.unitPrice() == null || raw.unitPrice().isBlank()) {
        messages.add("单价为空");
      } else {
        var number = decimal(raw.unitPrice());
        if (number == null) {
          messages.add("单价不是数字（" + raw.unitPrice().trim() + "）");
        } else {
          try {
            unitPrice = PriceListItemRules.normalizePrice(number, "单价");
          } catch (IllegalArgumentException e) {
            messages.add(e.getMessage());
          }
        }
      }
      if (!declaredCurrency.isEmpty() && !declaredCurrency.equals(header.currency())) {
        messages.add(
            "币种与价目表不一致（这张表是 "
                + header.currency()
                + "，这一行写的是 "
                + declaredCurrency
                + "）—— 价目项不能跨币种");
      }
      if (raw.minQuantity() != null && !raw.minQuantity().isBlank()) {
        var number = decimal(raw.minQuantity());
        if (number == null) {
          messages.add("数量门槛不是数字（" + raw.minQuantity().trim() + "）");
        } else {
          try {
            minQuantity = PriceListItemRules.normalizeQuantity(number, "数量门槛");
          } catch (IllegalArgumentException e) {
            messages.add(e.getMessage());
          }
        }
      }
      if (raw.priority() != null && !raw.priority().isBlank()) {
        try {
          priority = Integer.parseInt(raw.priority().trim());
        } catch (NumberFormatException e) {
          messages.add("优先级不是整数（" + raw.priority().trim() + "）");
        }
      }
      if (raw.validFrom() == null || raw.validFrom().isBlank()) {
        messages.add("生效起点为空");
      } else {
        validFrom = instant(raw.validFrom().trim());
        if (validFrom == null) {
          messages.add("生效起点格式认不出来（" + raw.validFrom().trim() + "）—— 用 ISO-8601，例如 2026-01-01T00:00:00+08:00");
        }
      }
      if (raw.validTo() != null && !raw.validTo().isBlank()) {
        validTo = instant(raw.validTo().trim());
        if (validTo == null) {
          messages.add("生效终点格式认不出来（" + raw.validTo().trim() + "）—— 用 ISO-8601，或留空表示长期有效");
        }
      }
      if (validFrom != null && validTo != null && !validTo.isAfter(validFrom)) {
        messages.add("生效终点必须晚于生效起点（生效区间是半开区间 [起点, 终点)）");
      }
      if (raw.enabled() != null && !raw.enabled().isBlank()) {
        var text = raw.enabled().trim().toLowerCase(Locale.ROOT);
        if (text.equals("是") || text.equals("true") || text.equals("1") || text.equals("yes")) {
          enabled = true;
        } else if (text.equals("否") || text.equals("false") || text.equals("0") || text.equals("no")) {
          enabled = false;
        } else {
          messages.add("启用列认不出来（" + raw.enabled().trim() + "）—— 填「是 / 否」");
        }
      }
      var slotKey = skuCode + "|" + minQuantity + "|" + (validFrom == null ? "" : validFrom.toString());
      var previous = seen.get(slotKey);
      if (previous != null) {
        messages.add("文件内重复（第 " + previous + " 行与第 " + raw.rowNo() + " 行是同一个 SKU、同一档位、同一个生效起点）");
      } else {
        seen.put(slotKey, raw.rowNo());
      }
      // 与既有价目项的冲突：同优先级 + 区间重叠 ⇒ **警告**（不拦落库，但必须摆出来）
      var warnings = new ArrayList<String>();
      if (skuId != null && validFrom != null && messages.isEmpty()) {
        var overlapping =
            sql.fetchOne(
                "select id from price_list_item where tenant_id="
                    + TENANT
                    + " and price_list_id=? and sku_id=? and min_quantity=? and priority=? and enabled"
                    + " and not (valid_from = ?::timestamptz)"
                    + " and valid_from < coalesce(?::timestamptz,'infinity'::timestamptz)"
                    + " and ?::timestamptz < coalesce(valid_to,'infinity'::timestamptz)",
                priceListId,
                skuId,
                minQuantity,
                priority,
                validFrom,
                validTo,
                validFrom);
        if (overlapping != null) {
          warnings.add(
              "与已有价目项 "
                  + overlapping.get(0, UUID.class)
                  + " 在生效区间上重叠且同优先级 —— 落库后这个 SKU 这一档会出现歧义，需要调整优先级或关掉旧的那条");
        }
      }
      var status = messages.isEmpty() ? "OK" : "ERROR";
      if (messages.isEmpty()) okCount++;
      else errorCount++;
      if (!warnings.isEmpty()) warningCount++;
      var all = new ArrayList<String>(messages);
      all.addAll(warnings);
      reports.add(
          new PriceListItemImportRowReport(
              raw.rowNo(),
              skuCode,
              skuName,
              unitPrice,
              minQuantity,
              priority,
              validFrom,
              validTo,
              declaredCurrency.isEmpty() ? null : declaredCurrency,
              enabled,
              status,
              all));
      // 落库这一行的解析结果（**不重新解析文件** —— 落库的就是刚才校验过的那批值）
      sql.execute(
          "insert into price_list_import_row(id,tenant_id,batch_id,row_no,sku_code,sku_name,unit_price,"
              + "min_quantity,priority,valid_from,valid_to,declared_currency,enabled,note,status,messages,created_at)"
              + " values(?::uuid,?::uuid,?::uuid,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?,?,?::jsonb,now())",
          UUID.randomUUID(),
          actor.tenantId(),
          batchId,
          raw.rowNo(),
          skuCode,
          skuName == null ? "" : skuName,
          unitPrice,
          minQuantity,
          priority,
          validFrom,
          validTo,
          declaredCurrency.isEmpty() ? null : declaredCurrency,
          enabled,
          raw.note() == null ? "" : raw.note().trim(),
          status,
          messagesJson(all));
    }
    boolean apply = Boolean.TRUE.equals(request.apply());
    boolean applied = false;
    String status;
    if (errorCount > 0) {
      status = "REJECTED";
    } else if (apply) {
      status = "APPLIED";
      applied = true;
    } else {
      status = "PREVIEW";
    }
    if (applied) {
      applyRows(priceListId, actor, batchId);
    }
    sql.execute(
        "update price_list_import_batch set status=?, total_rows=?, ok_rows=?, error_rows=?,"
            + " warning_rows=?, applied_rows=?, applied_at="
            + (applied ? "now()" : "null")
            + " where tenant_id="
            + TENANT
            + " and id=?",
        status,
        reports.size(),
        okCount,
        errorCount,
        warningCount,
        applied ? okCount : 0,
        batchId);
    var summary =
        errorCount > 0
            ? "共 "
                + reports.size()
                + " 行，其中 "
                + errorCount
                + " 行有错 —— **整批没有落库**（先报后改：请按报告改完文件再重新上传）"
            : apply
                ? "共 " + reports.size() + " 行，全部校验通过，已落库 " + okCount + " 条价目项"
                : "共 " + reports.size() + " 行，全部校验通过（这是**预览**，还没有落库；确认无误后请再传一次并勾选「落库」）";
    return new PriceListItemImportReport(
        batchId,
        request.fileName(),
        status,
        reports.size(),
        okCount,
        errorCount,
        warningCount,
        applied ? okCount : 0,
        applied,
        reports,
        summary);
  }

  /** 把一个批次的 OK 行落成价目项。 */
  private void applyRows(UUID priceListId, AccessService.Access actor, UUID batchId) {
    var rows =
        sql.fetch(
            "select row_no, sku_code, unit_price, min_quantity, priority, valid_from, valid_to, enabled, note"
                + " from price_list_import_row where tenant_id="
                + TENANT
                + " and batch_id=? and status='OK' order by row_no",
            batchId);
    for (var row : rows) {
      var sku = skuByCode(row.get("sku_code", String.class));
      if (sku == null) continue;
      var validFrom = row.get("valid_from", OffsetDateTime.class);
      var slot =
          sql.fetchOne(
              "select id from price_list_item where tenant_id="
                  + TENANT
                  + " and price_list_id=? and sku_id=? and min_quantity=? and valid_from=?::timestamptz",
              priceListId,
              sku.id(),
              row.get("min_quantity", BigDecimal.class),
              validFrom);
      if (slot != null) {
        sql.execute(
            "update price_list_item set unit_price=?, priority=?, valid_to=?::timestamptz, enabled=?,"
                + " note=?, version=version+1, updated_at=now()"
                + " where tenant_id=" + TENANT + " and id=?",
            row.get("unit_price", BigDecimal.class),
            row.get("priority", Integer.class),
            row.get("valid_to", OffsetDateTime.class),
            Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
            row.get("note", String.class),
            slot.get(0, UUID.class));
      } else {
        sql.execute(
            "insert into price_list_item(id,tenant_id,price_list_id,sku_id,unit_price,min_quantity,"
                + "priority,valid_from,valid_to,enabled,note,version,created_by,created_at,updated_at)"
                + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?::timestamptz,?::timestamptz,?,?,0,?::uuid,now(),now())",
            UUID.randomUUID(),
            actor.tenantId(),
            priceListId,
            sku.id(),
            row.get("unit_price", BigDecimal.class),
            row.get("min_quantity", BigDecimal.class),
            row.get("priority", Integer.class),
            validFrom,
            row.get("valid_to", OffsetDateTime.class),
            Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
            row.get("note", String.class),
            actor.userId());
      }
    }
  }

  // ================================================================ 取数与工具

  /** 价目表头（不存在 ⇒ 404）。 */
  private PriceListHeader requirePriceList(UUID priceListId) {
    var row =
        sql.fetchOne(
            "select id, code, name, currency, valid_from, valid_to, enabled from price_list where tenant_id="
                + TENANT
                + " and id=?",
            priceListId);
    if (row == null) throw notFound("价目表不存在");
    return new PriceListHeader(
        row.get("id", UUID.class),
        row.get("code", String.class),
        row.get("name", String.class),
        row.get("currency", String.class),
        row.get("valid_from", OffsetDateTime.class),
        row.get("valid_to", OffsetDateTime.class),
        Boolean.TRUE.equals(row.get("enabled", Boolean.class)));
  }

  /** SKU（不存在 ⇒ 404）。 */
  private SkuRef requireSku(String skuCode) {
    var code = PriceListItemRules.normalizeSkuCode(skuCode);
    var sku = skuByCode(code);
    if (sku == null) throw notFound("SKU 不存在（编码 " + code + "）");
    return sku;
  }

  private SkuRef skuByCode(String code) {
    var row =
        sql.fetchOne(
            "select s.id, s.code, s.name, p.category, p.brand, s.status"
                + " from sku s join spu p on p.tenant_id=s.tenant_id and p.id=s.spu_id"
                + " where s.tenant_id="
                + TENANT
                + " and s.code=?",
            code);
    return row == null ? null : skuOf(row);
  }

  private SkuRef skuById(UUID id) {
    var row =
        sql.fetchOne(
            "select s.id, s.code, s.name, p.category, p.brand, s.status"
                + " from sku s join spu p on p.tenant_id=s.tenant_id and p.id=s.spu_id"
                + " where s.tenant_id="
                + TENANT
                + " and s.id=?",
            id);
    return row == null ? null : skuOf(row);
  }

  private static SkuRef skuOf(Record row) {
    return new SkuRef(
        row.get("id", UUID.class),
        row.get("code", String.class),
        row.get("name", String.class),
        row.get("category", String.class),
        row.get("brand", String.class),
        row.get("status", String.class));
  }

  /** 某个分类下的 SKU（分类取自 {@code spu.category}）。 */
  private List<SkuRef> skusOfCategory(String category) {
    var rows =
        sql.fetch(
            "select s.id, s.code, s.name, p.category, p.brand, s.status"
                + " from sku s join spu p on p.tenant_id=s.tenant_id and p.id=s.spu_id"
                + " where s.tenant_id="
                + TENANT
                + " and lower(p.category)=lower(?)"
                + " order by s.code",
            category);
    var out = new ArrayList<SkuRef>();
    for (var row : rows) out.add(skuOf(row));
    return out;
  }

  /** 某个品牌下的 SKU（品牌取自 {@code spu.brand}）。 */
  private List<SkuRef> skusOfBrand(String brand) {
    var rows =
        sql.fetch(
            "select s.id, s.code, s.name, p.category, p.brand, s.status"
                + " from sku s join spu p on p.tenant_id=s.tenant_id and p.id=s.spu_id"
                + " where s.tenant_id="
                + TENANT
                + " and lower(p.brand)=lower(?)"
                + " order by s.code",
            brand);
    var out = new ArrayList<SkuRef>();
    for (var row : rows) out.add(skuOf(row));
    return out;
  }

  /** 一条价目项的候选视图。 */
  private PriceListItemCandidate candidateOf(UUID id, String state) {
    var row =
        sql.fetchOne(
            "select i.id, i.unit_price, i.min_quantity, i.priority, i.valid_from, i.valid_to,"
                + " s.code as sku_code, s.name as sku_name"
                + " from price_list_item i join sku s on s.tenant_id=i.tenant_id and s.id=i.sku_id"
                + " where i.tenant_id="
                + TENANT
                + " and i.id=?",
            id);
    if (row == null) {
      return new PriceListItemCandidate(id, "", "", null, BigDecimal.ZERO, 0, null, null, state, state);
    }
    var selection = SelectionState.of(state);
    return new PriceListItemCandidate(
        row.get("id", UUID.class),
        row.get("sku_code", String.class),
        row.get("sku_name", String.class),
        row.get("unit_price", BigDecimal.class),
        row.get("min_quantity", BigDecimal.class),
        row.get("priority", Integer.class),
        row.get("valid_from", OffsetDateTime.class),
        row.get("valid_to", OffsetDateTime.class),
        state,
        selection == null ? state : selection.label());
  }

  /** 价目项视图。 */
  private PriceListItemView viewOf(Record row, PriceListHeader header) {
    return new PriceListItemView(
        row.get("id", UUID.class),
        row.get("price_list_id", UUID.class),
        header.code(),
        header.currency(),
        row.get("sku_id", UUID.class),
        row.get("sku_code", String.class),
        row.get("sku_name", String.class),
        row.get("unit_price", BigDecimal.class),
        row.get("min_quantity", BigDecimal.class),
        row.get("priority", Integer.class),
        PriceListItemRules.priorityLabel(row.get("priority", Integer.class)),
        row.get("valid_from", OffsetDateTime.class),
        row.get("valid_to", OffsetDateTime.class),
        Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
        Boolean.TRUE.equals(row.get("effective_now", Boolean.class)),
        row.get("note", String.class),
        row.get("version", Long.class) == null ? 0L : row.get("version", Long.class),
        row.get("created_by", UUID.class) == null
            ? null
            : row.get("created_by", UUID.class).toString(),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  /** 归一化 + 校验一条价目项入参。 */
  private PriceListItemInput normalize(PriceListItemInput input) {
    if (input == null) throw badRequest("价目项不能为空");
    String skuCode;
    BigDecimal unitPrice;
    BigDecimal minQuantity;
    try {
      skuCode = PriceListItemRules.normalizeSkuCode(input.skuCode());
      unitPrice = PriceListItemRules.normalizePrice(input.unitPrice(), "单价");
      minQuantity = PriceListItemRules.normalizeQuantity(input.minQuantity(), "数量门槛");
    } catch (IllegalArgumentException e) {
      throw badRequest(e.getMessage());
    }
    if (input.validFrom() == null) throw badRequest("生效起点必填");
    if (!PriceListItemRules.validInterval(input.validFrom(), input.validTo())) {
      throw badRequest(
          "生效终点必须晚于生效起点（生效区间是半开区间 [起点, 终点)，终点为空 = 长期有效）");
    }
    return new PriceListItemInput(
        skuCode,
        unitPrice,
        minQuantity,
        PriceListItemRules.normalizePriority(input.priority()),
        input.validFrom(),
        input.validTo(),
        input.enabled() == null ? Boolean.TRUE : input.enabled(),
        input.note() == null ? "" : input.note().trim(),
        input.version());
  }

  private static BigDecimal quantity(BigDecimal raw, String label) {
    try {
      return PriceListItemRules.normalizeQuantity(raw, label);
    } catch (IllegalArgumentException e) {
      throw badRequest(e.getMessage());
    }
  }

  private static BigDecimal price(BigDecimal raw, String label) {
    try {
      return PriceListItemRules.normalizePrice(raw, label);
    } catch (IllegalArgumentException e) {
      throw badRequest(e.getMessage());
    }
  }

  /** 解析一个十进制数（认不出来 ⇒ null，调用方据此报错）。 */
  private static BigDecimal decimal(String raw) {
    if (raw == null) return null;
    var text = raw.trim().replace(",", "");
    if (text.isEmpty()) return null;
    try {
      return new BigDecimal(text);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** 解析一个 ISO-8601 时刻（认不出来 ⇒ null）。 */
  private static OffsetDateTime instant(String raw) {
    if (raw == null || raw.isBlank()) return null;
    var text = raw.trim();
    try {
      return OffsetDateTime.parse(text);
    } catch (RuntimeException e) {
      // 退一步：接受本地日期（2026-01-01）—— 那是运营手写表里最常见的写法。
      try {
        return java.time.LocalDate.parse(text).atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime();
      } catch (RuntimeException ignored) {
        return null;
      }
    }
  }

  /** 数据库时钟（不混用 JVM 墙钟 —— 那是跨时钟域比较）。 */
  private OffsetDateTime now() {
    return sql.fetchOne("select now()").get(0, OffsetDateTime.class);
  }

  private static OffsetDateTime earlier(OffsetDateTime a, OffsetDateTime b) {
    if (a == null) return b;
    if (b == null) return a;
    return a.isBefore(b) ? a : b;
  }

  private static Object[] concat(List<Object> params, Object... extra) {
    var all = new ArrayList<Object>(params);
    for (var value : extra) all.add(value);
    return all.toArray();
  }

  /** 把一批消息序列化成 jsonb（消息都是普通文本，序列化失败属于程序错误）。 */
  private String messagesJson(List<String> messages) {
    try {
      return json.writeValueAsString(messages);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("导入校验消息序列化失败：" + e.getMessage(), e);
    }
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
