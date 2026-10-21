package com.starcon.trade;

import static com.starcon.trade.PurchaseDemandMergeModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.2.2.2 需求合并与批量（trade 服务）。
 *
 * <p>蓝图原话：「把多个 SKU 的需求合并成一张采购单（同一供应商），支持批量生成、批量修改数量与供应商、批量打印。」
 * 输入「多行需求；供应商选择（相同供应商才能合并）」。
 * 输出「合并后的采购单草稿；未合并行说明原因」。
 *
 * <p><b>🔴 四条不可让步的纪律</b>：
 *
 * <ol>
 *   <li><b>合并只在同一供应商内进行</b>（蓝图加粗句）：
 *       「跨供应商合并会导致对账与付款错误」。本服务按**供应商分组**，
 *       跨供应商的行进 {@code purchase_demand_merge_unmerged} 并带**明确原因** ——
 *       既不静默丢弃，也不偷偷归到第一个供应商。同一个 SKU 出现在多个供应商下时
 *       **各自成行**（分组键含供应商 ⇒ 天然各成一行）。</li>
 *   <li><b>草稿 ≠ 采购单</b>：6.3.1.2 采购单<b>未交付</b>，本模块交付的是
 *       <b>采购单草稿</b>，落在本服务自己的表上，6.3.1.2 交付后由它读这份草稿去建真正的采购单。
 *       本服务<b>绝不</b>造一张占位的假采购单表。</li>
 *   <li><b>批量动作整体生效或整体拒绝</b>：批量改数量 / 批量改供应商都在**一个事务**里，
 *       任一步不满足 ⇒ 整体回滚，库里不留「改了一半」的中间态。
 *       改供应商之后**必须重新校验分组**（每张草稿的供应商必须唯一）。</li>
 *   <li><b>上游只读</b>：6.2.2.1 的 {@code purchase_request} / {@code purchase_request_line}
 *       只 SELECT，一个字节都不改，也不建到它们的跨表外键。</li>
 * </ol>
 *
 * <p><b>租户</b>：一律来自已认证成员关系（{@code AccessService}），本服务从头到尾没有读过
 * {@code X-Tenant-Id}；RLS 在 DB 层强制隔离。
 */
@Service
public class PurchaseDemandMergeService {

  /** 数量标度（基础单位）。 */
  private static final int QUANTITY_SCALE = 6;
  /** 金额 / 单价标度。 */
  private static final int AMOUNT_SCALE = 4;
  /** 每页最多多少条。 */
  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;
  private final PurchaseRequestSupplierPort supplierDirectory;
  private final PurchasePlanWarehousePort warehouseReference;

  public PurchaseDemandMergeService(
      DSLContext sql,
      PurchaseRequestSupplierPort supplierDirectory,
      PurchasePlanWarehousePort warehouseReference) {
    this.sql = sql;
    this.supplierDirectory = supplierDirectory;
    this.warehouseReference = warehouseReference;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>🔴 采购单（6.3.1.2）**未交付** ⇒ {@code purchaseOrderDelivered=false}，
   * 并把「本模块只产出采购单**草稿**、不产出采购单」写清楚 —— 页面据此显示，
   * 而不是让用户以为合并之后系统就真的对供应商下了单。
   */
  public PurchaseDemandMergeCapabilities capabilities() {
    return new PurchaseDemandMergeCapabilities(
        true,
        "6.2.2.1 采购申请（trade 服务，已交付）",
        "需求行读 6.2.2.1 的 purchase_request / purchase_request_line —— 本模块只 SELECT，"
            + "不改它一个字，也不建到它的外键。要合并的行必须来自**已批**的申请单："
            + "没审过的需求不该变成一张要对供应商下单的采购单草稿。",
        supplierDirectory.available(),
        "6.1.1.x 供应商主数据（catalog / party）",
        supplierDirectory.available()
            ? "供应商经 catalog 的授权只读接口（GET /catalog/supply/vendors）读取。"
                + "本服务不建供应商影子表 —— 供应商是 catalog 的事实。"
                + "批量改供应商时服务端会确认新供应商真的存在。"
            : supplierDirectory.unavailableNote(),
        true,
        "7.x 目标仓与法人（warehouse 服务）",
        "目标仓与法人经 warehouse 的授权只读接口（GET /warehouse/warehouses、"
            + "GET /warehouse/legal-entities）读取。本服务不建仓库 / 法人的影子表。",
        false,
        "6.3.1.2 采购单创建与编辑",
        "6.3.1.2 采购单**未交付** ⇒ 本模块交付的是**采购单草稿**"
            + "（purchase_demand_merge_draft + _line + _line_source），"
            + "**不是**采购单。6.3.1.2 交付后由它读这份草稿去建真正的采购单"
            + "（并把草稿置为 CONVERTED）。本模块**不会**造一张占位的假采购单表。",
        "合并只在同一供应商内进行：按供应商分组，一个供应商一张采购单草稿；"
            + "跨供应商的需求行进「未合并行」并写明原因（不静默丢弃、不偷偷归到第一个供应商）。",
        "合并批次 OPEN → FINALIZED（定稿后内容冻结，DB 触发器钉死）；"
            + "采购单草稿 DRAFT（本模块唯一产出）→ CONVERTED（由 6.3.1.2 置位）。",
        "本模块的职责边界：把 6.2.2.1 的多行需求按供应商合并成采购单**草稿** → "
            + "批量改数量 / 批量改供应商（整体生效或整体拒绝）→ 批量打印 → 定稿。"
            + "采购单草稿是对供应商下单前的最后一道内部整理，它还不是采购单。");
  }

  // ================================================================ 参照下拉

  /**
   * 合并要用的下拉项：供应商 / 目标仓 / 法人。
   *
   * <p>🔴 供应商读不到时**如实报出**（{@code supplierNote} 非空 + 清单为空），
   * 页面据此显示「未接线 / 暂时读不到」，<b>绝不</b>显示一个「这个租户没有供应商」的假下拉。
   */
  public PurchaseDemandMergeOptions options(String bearer) {
    List<PurchaseDemandMergeSupplierOption> supplierRows = new ArrayList<>();
    String supplierNote = "";
    try {
      for (SupplierPartyQueryPort.SupplierView view : supplierDirectory.suppliers(null, bearer)) {
        supplierRows.add(
            new PurchaseDemandMergeSupplierOption(
                view.roleId(), view.partyCode(), view.partyName(), view.partyStatus(), false));
      }
    } catch (ResponseStatusException e) {
      supplierNote = e.getReason() == null ? "供应商清单暂时读不到" : e.getReason();
    }

    List<PurchaseDemandMergeWarehouseOption> warehouseRows = new ArrayList<>();
    List<PurchaseDemandMergeLegalEntityOption> legalRows = new ArrayList<>();
    String warehouseNote = "";
    try {
      for (PurchasePlanWarehousePort.WarehouseView view : warehouseReference.warehouses(bearer)) {
        warehouseRows.add(
            new PurchaseDemandMergeWarehouseOption(
                view.id(), view.code(), view.name(), view.countryCode(), view.status()));
      }
      for (PurchasePlanWarehousePort.LegalEntityView view :
          warehouseReference.legalEntities(bearer)) {
        legalRows.add(
            new PurchaseDemandMergeLegalEntityOption(
                view.id(), view.code(), view.name(), view.status()));
      }
    } catch (ResponseStatusException e) {
      warehouseNote = e.getReason() == null ? "仓库参照暂时读不到" : e.getReason();
    }

    return new PurchaseDemandMergeOptions(
        supplierRows,
        warehouseRows,
        legalRows,
        supplierNote,
        warehouseNote,
        "供应商来自 catalog（party）、目标仓与法人来自 warehouse —— 都是跨服务的授权只读接口，"
            + "本服务不建它们的影子表。哪一项读不到，下面会写明原因。");
  }

  // ================================================================ 清单

  /** 合并批次列表：真分页 + 真筛选 + 真排序。 */
  public PurchaseDemandMergeBatchPage pageBatches(
      String status, String keyword, String sort, int page, int size) {
    UUID tenantId = currentTenant();
    var where = new StringBuilder(" where b.tenant_id = ?::uuid");
    var binds = new ArrayList<Object>();
    binds.add(tenantId);
    if (status != null && !status.isBlank()) {
      where.append(" and b.status = ?::varchar");
      binds.add(status.trim());
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(
          " and (b.batch_no ilike ?::varchar or b.note ilike ?::varchar"
              + " or exists (select 1 from purchase_demand_merge_draft d"
              + " where d.tenant_id = b.tenant_id and d.batch_id = b.id"
              + " and (d.draft_no ilike ?::varchar or d.supplier_name ilike ?::varchar"
              + " or d.supplier_code ilike ?::varchar)))");
      String like = "%" + keyword.trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }

    long total =
        sql.fetchOne("select count(*) from purchase_demand_merge_batch b" + where, binds.toArray())
            .get(0, Long.class);
    int limit = pageSize(size);
    var ordered = new ArrayList<>(binds);
    ordered.add(limit);
    ordered.add(Math.max(page, 0) * limit);
    List<PurchaseDemandMergeBatchRow> items =
        sql.fetch(BATCH_COLUMNS + where + orderBy(sort) + " limit ? offset ?", ordered.toArray())
            .map(this::toBatchRow);
    return new PurchaseDemandMergeBatchPage(items, total, Math.max(page, 0), limit);
  }

  /** 排序白名单：拼错却安静地给出另一种顺序，比报错更难查。 */
  private static String orderBy(String sort) {
    return switch (sort == null ? "" : sort.trim()) {
      case "NEWEST" -> " order by b.created_at desc, b.id desc";
      case "OLDEST" -> " order by b.created_at asc, b.id asc";
      case "DRAFTS" -> " order by b.draft_count desc, b.created_at desc, b.id desc";
      case "STATUS" -> " order by b.status asc, b.created_at desc, b.id desc";
      default -> throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "排序键需为 NEWEST / OLDEST / DRAFTS / STATUS");
    };
  }

  // ================================================================ 批量生成（合并）

  /**
   * 批量生成：把多行需求按供应商合并成采购单**草稿**。
   *
   * <p>🔴 分组键 = **供应商**（蓝图硬规则）。每一条没合上的行都会进
   * {@code purchase_demand_merge_unmerged} 并带上**明确原因** ——
   * 「静默丢弃」会让用户以为合掉了，「偷偷归到第一个供应商」会让付款付错人。
   *
   * <p>逐行判定顺序（先命中先记原因）：
   *
   * <ol>
   *   <li>源申请单的状态：只有**已批**（{@code APPROVED}）的需求行能进采购单草稿。</li>
   *   <li>这行是不是已经被别的批次合并过（一条需求行只能被合并一次）。</li>
   *   <li>这行有没有供应商。</li>
   *   <li>跨供应商（本次只合并某个供应商时它不属于那个供应商）。</li>
   *   <li>目标仓 / 法人 / 币种是否与本次合并一致。</li>
   *   <li>同一 SKU 在同一供应商下单价冲突（合成一行会把其中一条需求的价格算错）。</li>
   * </ol>
   */
  @Transactional
  public PurchaseDemandMergeDetail merge(
      PurchaseDemandMergeCreateInput input, UUID actorId, String bearer) {
    UUID tenantId = currentTenant();
    String currency = input.currencyCode().trim().toUpperCase(Locale.ROOT);
    if (!currency.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是三位大写字母，例如 USD");
    }

    // ---- 目标属性（仓 / 法人）：跨服务只读，读不到就留空 + 如实记下原因，不编造 ----
    String note = input.note() == null ? "" : input.note().trim();
    String warehouseCode = "";
    String warehouseName = "";
    var warehouse = resolveWarehouse(input.targetWarehouseId(), bearer);
    if (warehouse != null) {
      warehouseCode = warehouse.code();
      warehouseName = warehouse.name();
    } else {
      note = appendNote(note, "目标仓 " + input.targetWarehouseId() + " 在仓库服务里查不到（可能已停用）");
    }
    String entityCode = "";
    String entityName = "";
    var entity = resolveLegalEntity(input.legalEntityId(), bearer);
    if (entity != null) {
      entityCode = entity.code();
      entityName = entity.name();
    } else {
      note = appendNote(note, "法人 " + input.legalEntityId() + " 在仓库服务里查不到（可能已停用）");
    }

    // ---- 目标供应商（可选）：必须真的存在于 catalog ----
    SupplierPartyQueryPort.SupplierView targetSupplier = null;
    if (input.targetSupplierId() != null) {
      targetSupplier = supplierDirectory.require(input.targetSupplierId(), bearer);
    }

    // ---- 源申请单（6.2.2.1，只读） ----
    List<UUID> requestIds = distinct(input.sourceRequestIds());
    var requests =
        sql.fetch(
            "select id, request_no, status, target_warehouse_id, legal_entity_id, currency_code"
                + " from purchase_request where tenant_id = ?::uuid and id = any(?::uuid[])",
            tenantId,
            requestIds.toArray(new UUID[0]));
    if (requests.size() != requestIds.size()) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "源采购申请单不存在或不属于当前租户（合并只认本租户的需求）");
    }
    Map<UUID, Record> requestById = new LinkedHashMap<>();
    for (Record r : requests) requestById.put(r.get("id", UUID.class), r);

    // ---- 源需求行 ----
    var lines = loadSourceLines(tenantId, requestIds, input.sourceLineIds());
    if (lines.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "选中的申请单里没有可合并的需求行");
    }

    // ---- 已被别的批次合并过的行（一条需求行只能被合并一次） ----
    List<UUID> sourceLineIds = new ArrayList<>();
    for (Record line : lines) sourceLineIds.add(line.get("id", UUID.class));
    var merged =
        sql.fetch(
            "select source_request_line_id, draft_line_id from purchase_demand_merge_line_source"
                + " where tenant_id = ?::uuid and source_request_line_id = any(?::uuid[])",
            tenantId,
            sourceLineIds.toArray(new UUID[0]));
    Map<UUID, UUID> mergedBefore = new LinkedHashMap<>();
    for (Record r : merged) {
      mergedBefore.put(r.get("source_request_line_id", UUID.class), r.get("draft_line_id", UUID.class));
    }

    // ---- 逐行判定 ----
    UUID batchId = UUID.randomUUID();
    String batchNo = "DM-" + today() + "-" + shortId();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into purchase_demand_merge_batch(id,tenant_id,batch_no,status,target_warehouse_id,"
            + " target_warehouse_code,target_warehouse_name,legal_entity_id,legal_entity_code,"
            + " legal_entity_name,currency_code,source_request_ids,note,created_by,created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::varchar,'OPEN',?::uuid,?::varchar,?::varchar,?::uuid,?::varchar,"
            + " ?::varchar,?::varchar,?::uuid[],?::varchar,?::uuid,?::timestamptz,?::timestamptz)",
        batchId,
        tenantId,
        batchNo,
        input.targetWarehouseId(),
        warehouseCode,
        warehouseName,
        input.legalEntityId(),
        entityCode,
        entityName,
        currency,
        requestIds.toArray(new UUID[0]),
        note,
        actorId,
        now,
        now);

    // 按供应商分组（🔴 蓝图硬规则：分组键 = 供应商）。
    Map<UUID, List<Record>> bySupplier = new LinkedHashMap<>();
    List<Record> unmergeable = new ArrayList<>();
    for (Record line : lines) {
      String reason = rejectionReason(line, requestById, targetSupplier, mergedBefore, input, currency);
      if (reason != null) {
        unmergeable.add(line);
        continue;
      }
      bySupplier.computeIfAbsent(line.get("supplier_id", UUID.class), k -> new ArrayList<>()).add(line);
    }

    // ---- 每个供应商一张草稿 ----
    for (var entry : bySupplier.entrySet()) {
      UUID supplierId = entry.getKey();
      List<Record> group = entry.getValue();
      var supplier = supplierDirectory.require(supplierId, bearer);
      UUID draftId = UUID.randomUUID();
      sql.execute(
          "insert into purchase_demand_merge_draft(id,tenant_id,batch_id,draft_no,supplier_id,"
              + " supplier_code,supplier_name,target_warehouse_id,target_warehouse_code,"
              + " target_warehouse_name,legal_entity_id,legal_entity_code,legal_entity_name,"
              + " currency_code,status,note,created_by,created_at,updated_at)"
              + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::uuid,?::varchar,?::varchar,?::uuid,"
              + " ?::varchar,?::varchar,?::uuid,?::varchar,?::varchar,?::varchar,'DRAFT',?::varchar,"
              + " ?::uuid,?::timestamptz,?::timestamptz)",
          draftId,
          tenantId,
          batchId,
          "PD-" + today() + "-" + shortId(),
          supplier.roleId(),
          supplier.partyCode(),
          supplier.partyName(),
          input.targetWarehouseId(),
          warehouseCode,
          warehouseName,
          input.legalEntityId(),
          entityCode,
          entityName,
          currency,
          "",
          actorId,
          now,
          now);
      for (Record line : group) {
        BigDecimal quantity = line.get("requested_quantity", BigDecimal.class);
        BigDecimal price = line.get("unit_price", BigDecimal.class);
        BigDecimal scaledQuantity = quantity.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
        BigDecimal scaledPrice = price == null ? null : price.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        LocalDate arrival = line.get("requested_arrival_date", LocalDate.class);
        UUID skuId = line.get("sku_id", UUID.class);

        var existing =
            sql.fetchOne(
                "select * from purchase_demand_merge_line where tenant_id = ?::uuid"
                    + " and draft_id = ?::uuid and sku_id = ?::uuid",
                tenantId,
                draftId,
                skuId);
        if (existing != null) {
          // 同一 SKU 已在草稿里：数量相加。
          // 🔴 单价冲突**不合成一行** —— 合成会把其中一条需求的价格算错。
          BigDecimal existingPrice = existing.get("unit_price", BigDecimal.class);
          if (existingPrice != null && scaledPrice != null && existingPrice.compareTo(scaledPrice) != 0) {
            unmergeable.add(line);
            continue;
          }
          BigDecimal mergedPrice = existingPrice != null ? existingPrice : scaledPrice;
          BigDecimal newQuantity =
              existing
                  .get("merged_quantity", BigDecimal.class)
                  .add(scaledQuantity)
                  .setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
          BigDecimal newSourceQuantity =
              existing
                  .get("source_quantity", BigDecimal.class)
                  .add(scaledQuantity)
                  .setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
          LocalDate newArrival = earliest(existing.get("arrival_date", LocalDate.class), arrival);
          sql.execute(
              "update purchase_demand_merge_line set merged_quantity = ?::numeric,"
                  + " source_quantity = ?::numeric, unit_price = ?::numeric, line_amount = ?::numeric,"
                  + " arrival_date = coalesce(?::date, arrival_date), source_request_count = source_request_count + 1,"
                  + " updated_at = ?::timestamptz where tenant_id = ?::uuid and id = ?::uuid",
              newQuantity,
              newSourceQuantity,
              mergedPrice,
              mergedPrice == null
                  ? null
                  : mergedPrice.multiply(newQuantity).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP),
              newArrival,
              now,
              tenantId,
              existing.get("id", UUID.class));
          insertLineSource(
              tenantId,
              existing.get("id", UUID.class),
              line.get("request_id", UUID.class),
              line.get("id", UUID.class),
              skuId,
              line.get("sku_code", String.class),
              scaledQuantity,
              now);
        } else {
          UUID lineId = UUID.randomUUID();
          sql.execute(
              "insert into purchase_demand_merge_line(id,tenant_id,draft_id,sku_id,sku_code,sku_name,"
                  + " supplier_id,merged_quantity,source_quantity,unit_price,line_amount,arrival_date,"
                  + " source_request_count,sort_rank,note,created_at,updated_at)"
                  + " values (?::uuid,?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::uuid,?::numeric,"
                  + " ?::numeric,?::numeric,?::numeric,?::date,1,?::varchar,?::varchar,?::timestamptz,"
                  + " ?::timestamptz)",
              lineId,
              tenantId,
              draftId,
              skuId,
              line.get("sku_code", String.class),
              orEmpty(line.get("sku_name", String.class)),
              supplierId,
              scaledQuantity,
              scaledQuantity,
              scaledPrice,
              scaledPrice == null
                  ? null
                  : scaledPrice.multiply(scaledQuantity).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP),
              arrival,
              "",
              "",
              now,
              now);
          insertLineSource(
              tenantId,
              lineId,
              line.get("request_id", UUID.class),
              line.get("id", UUID.class),
              skuId,
              line.get("sku_code", String.class),
              scaledQuantity,
              now);
        }
      }
    }

    // ---- 未合并行：逐条写明原因（不静默丢弃） ----
    for (Record line : unmergeable) {
      var reason =
          rejection(
              line,
              requestById,
              targetSupplier,
              mergedBefore,
              input,
              currency);
      sql.execute(
          "insert into purchase_demand_merge_unmerged(id,tenant_id,batch_id,source_request_id,"
              + " source_request_line_id,source_sku_id,source_sku_code,source_sku_name,source_quantity,"
              + " source_supplier_id,source_supplier_name,reason_code,reason,created_at)"
              + " values (?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,"
              + " ?::numeric,?::uuid,?::varchar,?::varchar,?::varchar,?::timestamptz)",
          UUID.randomUUID(),
          tenantId,
          batchId,
          line.get("request_id", UUID.class),
          line.get("id", UUID.class),
          line.get("sku_id", UUID.class),
          line.get("sku_code", String.class),
          orEmpty(line.get("sku_name", String.class)),
          line.get("requested_quantity", BigDecimal.class),
          line.get("supplier_id", UUID.class),
          orEmpty(line.get("supplier_name", String.class)),
          reason.code(),
          reason.text(),
          now);
    }

    recomputeDrafts(tenantId, batchId);
    recomputeBatch(tenantId, batchId);
    return batchDetail(batchId);
  }

  /**
   * 一行为什么**不能**合并（返回 null = 可以合并）。
   *
   * <p>判定顺序即「先命中先记原因」：上游状态 → 是否已合并 → 供应商 → 跨供应商 → 目标属性。
   */
  private String rejectionReason(
      Record line,
      Map<UUID, Record> requestById,
      SupplierPartyQueryPort.SupplierView targetSupplier,
      Map<UUID, UUID> mergedBefore,
      PurchaseDemandMergeCreateInput input,
      String currency) {
    Record request = requestById.get(line.get("request_id", UUID.class));
    if (request == null) return "REQUEST_NOT_APPROVED";
    String status = orEmpty(request.get("status", String.class));
    if (!"APPROVED".equals(status)) {
      return switch (status) {
        case "REJECTED" -> "REQUEST_REJECTED";
        case "CONVERTED" -> "REQUEST_ALREADY_CONVERTED";
        default -> "REQUEST_NOT_APPROVED";
      };
    }
    if (mergedBefore.containsKey(line.get("id", UUID.class))) return "ALREADY_MERGED";
    if (line.get("supplier_id", UUID.class) == null) return "SUPPLIER_MISSING";
    if (targetSupplier != null && !targetSupplier.roleId().equals(line.get("supplier_id", UUID.class))) {
      return "SUPPLIER_MISMATCH";
    }
    if (!input.targetWarehouseId().equals(request.get("target_warehouse_id", UUID.class))) {
      return "TARGET_MISMATCH";
    }
    if (!input.legalEntityId().equals(request.get("legal_entity_id", UUID.class))) {
      return "TARGET_MISMATCH";
    }
    String requestCurrency = request.get("currency_code", String.class);
    if (requestCurrency != null && !requestCurrency.trim().equalsIgnoreCase(currency)) {
      return "CURRENCY_MISMATCH";
    }
    return null;
  }

  /** 未合并行的原因码与原因原文。 */
  private record Rejection(String code, String text) {}

  /** 未合并行的原因码 + 中文说明（原文写清「为什么没合上」）。 */
  private Rejection rejection(
      Record line,
      Map<UUID, Record> requestById,
      SupplierPartyQueryPort.SupplierView targetSupplier,
      Map<UUID, UUID> mergedBefore,
      PurchaseDemandMergeCreateInput input,
      String currency) {
    Record request = requestById.get(line.get("request_id", UUID.class));
    String sku = orEmpty(line.get("sku_code", String.class));
    String requestNo = request == null ? "（申请单已不可见）" : orEmpty(request.get("request_no", String.class));
    String code = rejectionReason(line, requestById, targetSupplier, mergedBefore, input, currency);
    if (code == null) {
      // 走到这里说明是**单价冲突**（同 SKU 已在草稿里且单价不同）。
      code = "PRICE_CONFLICT";
      return new Rejection(
          code,
          sku + " 在同一供应商下已经有单价不同的行（同一 SKU 两个单价不能合成一行，"
              + "合成会把其中一条需求的价格算错）—— 请先统一单价再合并");
    }
    String text =
        switch (code) {
          case "REQUEST_NOT_APPROVED" -> sku + " 来自申请单 " + requestNo + "，还没审 —— "
              + "没审过的需求不该变成一张要对供应商下单的采购单草稿";
          case "REQUEST_REJECTED" -> sku + " 来自已被驳回的申请单 " + requestNo + " —— 驳回的需求不合并";
          case "REQUEST_ALREADY_CONVERTED" -> sku + " 来自已转单的申请单 " + requestNo
              + " —— 已经转过单了，再合并会多下一单";          case "SUPPLIER_MISSING" -> sku + " 还没选供应商 —— 不知道该合并到哪张采购单";
          case "SUPPLIER_MISMATCH" -> sku + " 的供应商是「"
              + orEmpty(line.get("supplier_name", String.class)) + "」，与本次要合并的供应商「"
              + (targetSupplier == null ? "" : targetSupplier.partyName())
              + "」不是同一个 —— 跨供应商合并会导致对账与付款错误";
          case "TARGET_MISMATCH" -> sku + " 来自申请单 " + requestNo
              + "，它的目标仓 / 法人与本次合并的目标不一致 —— 一张采购单草稿只对一个仓与一个法人";
          case "CURRENCY_MISMATCH" -> sku + " 来自申请单 " + requestNo + "（币种 "
              + (request == null ? "" : orEmpty(request.get("currency_code", String.class)))
              + "），与本次合并的币种 " + currency + " 不一致";
          case "ALREADY_MERGED" -> sku + " 已经被别的合并批次合过了 —— 一条需求行只能被合并一次";
          default -> sku + " 没有合并";
        };
    return new Rejection(code, text);
  }

  // ================================================================ 详情

  /** 合并批次详情：批次 + 草稿 + 行 + 来源 + 未合并行 + 批量修改留痕。 */
  public PurchaseDemandMergeDetail batchDetail(UUID batchId) {
    UUID tenantId = currentTenant();
    var batch = requireBatch(batchId, tenantId);
    List<PurchaseDemandMergeDraftRow> drafts =
        sql.fetch(
                DRAFT_COLUMNS
                    + " where d.tenant_id = ?::uuid and d.batch_id = ?::uuid order by d.supplier_name, d.id",
                tenantId,
                batchId)
            .map(this::toDraftRow);
    List<PurchaseDemandMergeLineRow> lines =
        sql.fetch(
                LINE_COLUMNS
                    + " where l.tenant_id = ?::uuid and d.batch_id = ?::uuid"
                    + " order by d.supplier_name, l.sort_rank, l.id",
                tenantId,
                batchId)
            .map(this::toLineRow);
    List<PurchaseDemandMergeLineSourceRow> sources =
        sql.fetch(
                "select s.* from purchase_demand_merge_line_source s"
                    + " join purchase_demand_merge_line l"
                    + "   on l.tenant_id = s.tenant_id and l.id = s.draft_line_id"
                    + " join purchase_demand_merge_draft d"
                    + "   on d.tenant_id = l.tenant_id and d.id = l.draft_id"
                    + " where s.tenant_id = ?::uuid and d.batch_id = ?::uuid"
                    + " order by s.merged_at, s.id",
                tenantId,
                batchId)
            .map(this::toLineSourceRow);
    List<PurchaseDemandMergeUnmergedRow> unmerged =
        sql.fetch(
                "select * from purchase_demand_merge_unmerged where tenant_id = ?::uuid"
                    + " and batch_id = ?::uuid order by created_at, id",
                tenantId,
                batchId)
            .map(this::toUnmergedRow);
    List<PurchaseDemandBatchAdjustmentRow> adjustments =
        sql.fetch(
                "select * from purchase_demand_batch_adjustment where tenant_id = ?::uuid"
                    + " and batch_id = ?::uuid order by adjusted_at desc, id desc",
                tenantId,
                batchId)
            .map(this::toAdjustmentRow);
    return new PurchaseDemandMergeDetail(
        toBatchRow(batch), drafts, lines, sources, unmerged, adjustments);
  }

  // ================================================================ 草稿与行

  /** 草稿清单（真分页 + 真筛选）。 */
  public PurchaseDemandMergeDraftPage pageDrafts(
      UUID batchId, UUID supplierId, int page, int size) {
    UUID tenantId = currentTenant();
    requireBatch(batchId, tenantId);
    var where = new StringBuilder(" where d.tenant_id = ?::uuid and d.batch_id = ?::uuid");
    var binds = new ArrayList<Object>();
    binds.add(tenantId);
    binds.add(batchId);
    if (supplierId != null) {
      where.append(" and d.supplier_id = ?::uuid");
      binds.add(supplierId);
    }
    long total =
        sql.fetchOne("select count(*) from purchase_demand_merge_draft d" + where, binds.toArray())
            .get(0, Long.class);
    int limit = pageSize(size);
    var ordered = new ArrayList<>(binds);
    ordered.add(limit);
    ordered.add(Math.max(page, 0) * limit);
    List<PurchaseDemandMergeDraftRow> items =
        sql.fetch(
                DRAFT_COLUMNS + where + " order by d.supplier_name, d.id limit ? offset ?",
                ordered.toArray())
            .map(this::toDraftRow);
    return new PurchaseDemandMergeDraftPage(items, total, Math.max(page, 0), limit);
  }

  /** 草稿详情：草稿 + 行 + 行来源。 */
  public PurchaseDemandMergeDraftDetail draftDetail(UUID batchId, UUID draftId) {
    UUID tenantId = currentTenant();
    requireBatch(batchId, tenantId);
    var draft =
        sql.fetchOne(
            DRAFT_COLUMNS + " where d.tenant_id = ?::uuid and d.batch_id = ?::uuid and d.id = ?::uuid",
            tenantId,
            batchId,
            draftId);
    if (draft == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "采购单草稿不存在或不属于这个合并批次");
    }
    List<PurchaseDemandMergeLineRow> lines =
        sql.fetch(
                LINE_COLUMNS + " where l.tenant_id = ?::uuid and l.draft_id = ?::uuid"
                    + " order by l.sort_rank, l.id",
                tenantId,
                draftId)
            .map(this::toLineRow);
    List<PurchaseDemandMergeLineSourceRow> sources =
        sql.fetch(
                "select * from purchase_demand_merge_line_source where tenant_id = ?::uuid"
                    + " and draft_line_id in (select id from purchase_demand_merge_line"
                    + " where tenant_id = ?::uuid and draft_id = ?::uuid) order by merged_at, id",
                tenantId,
                tenantId,
                draftId)
            .map(this::toLineSourceRow);
    return new PurchaseDemandMergeDraftDetail(toDraftRow(draft), lines, sources);
  }

  /** 草稿行真分页（可按草稿 / 供应商 / 关键字筛）。 */
  public PurchaseDemandMergeLinePage pageLines(
      UUID batchId, UUID draftId, UUID supplierId, String keyword, int page, int size) {
    UUID tenantId = currentTenant();
    requireBatch(batchId, tenantId);
    var where = new StringBuilder(" where l.tenant_id = ?::uuid and d.batch_id = ?::uuid");
    var binds = new ArrayList<Object>();
    binds.add(tenantId);
    binds.add(batchId);
    if (draftId != null) {
      where.append(" and l.draft_id = ?::uuid");
      binds.add(draftId);
    }
    if (supplierId != null) {
      where.append(" and l.supplier_id = ?::uuid");
      binds.add(supplierId);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (l.sku_code ilike ?::varchar or l.sku_name ilike ?::varchar)");
      binds.add("%" + keyword.trim() + "%");
      binds.add("%" + keyword.trim() + "%");
    }
    long total =
        sql.fetchOne(
                "select count(*) from purchase_demand_merge_line l"
                    + " join purchase_demand_merge_draft d"
                    + "   on d.tenant_id = l.tenant_id and d.id = l.draft_id" + where,
                binds.toArray())
            .get(0, Long.class);
    int limit = pageSize(size);
    var ordered = new ArrayList<>(binds);
    ordered.add(limit);
    ordered.add(Math.max(page, 0) * limit);
    List<PurchaseDemandMergeLineRow> items =
        sql.fetch(
                LINE_COLUMNS + where + " order by d.supplier_name, l.sort_rank, l.id limit ? offset ?",
                ordered.toArray())
            .map(this::toLineRow);
    return new PurchaseDemandMergeLinePage(items, total, Math.max(page, 0), limit);
  }

  /** 未合并行真分页（可按原因码筛）。 */
  public PurchaseDemandMergeUnmergedPage pageUnmerged(
      UUID batchId, String reasonCode, int page, int size) {
    UUID tenantId = currentTenant();
    requireBatch(batchId, tenantId);
    var where = new StringBuilder(" where tenant_id = ?::uuid and batch_id = ?::uuid");
    var binds = new ArrayList<Object>();
    binds.add(tenantId);
    binds.add(batchId);
    if (reasonCode != null && !reasonCode.isBlank()) {
      where.append(" and reason_code = ?::varchar");
      binds.add(reasonCode.trim());
    }
    long total =
        sql.fetchOne(
                "select count(*) from purchase_demand_merge_unmerged" + where, binds.toArray())
            .get(0, Long.class);
    int limit = pageSize(size);
    var ordered = new ArrayList<>(binds);
    ordered.add(limit);
    ordered.add(Math.max(page, 0) * limit);
    List<PurchaseDemandMergeUnmergedRow> items =
        sql.fetch(
                "select * from purchase_demand_merge_unmerged" + where
                    + " order by created_at, id limit ? offset ?",
                ordered.toArray())
            .map(this::toUnmergedRow);
    return new PurchaseDemandMergeUnmergedPage(items, total, Math.max(page, 0), limit);
  }

  // ================================================================ 批量改数量

  /**
   * 批量改数量。
   *
   * <p>🔴 **整体生效或整体拒绝**：任一行不合法（不属于本批次 / 数量不合法）
   * ⇒ 整批回滚，库里不留「改了一半」的中间态。每次改动都落一条 append-only 的批量修改留痕。
   */
  @Transactional
  public PurchaseDemandMergeDetail adjustQuantity(
      UUID batchId, PurchaseDemandBatchQuantityInput input, UUID actorId) {
    UUID tenantId = currentTenant();
    requireOpenBatch(batchId, tenantId, "批量改数量");
    List<UUID> lineIds = distinct(input.lineIds());
    Map<UUID, Record> lines = loadBatchLines(tenantId, batchId, lineIds);

    boolean multiply = "MULTIPLY".equalsIgnoreCase(input.mode().trim());
    BigDecimal value =
        input.value().setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    if (multiply && value.signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "乘数必须大于 0");
    }
    if (!multiply && value.signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标数量必须大于 0");
    }

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    for (UUID lineId : lineIds) {
      Record line = lines.get(lineId);
      BigDecimal before = line.get("merged_quantity", BigDecimal.class);
      BigDecimal after =
          multiply
              ? before.multiply(value).setScale(QUANTITY_SCALE, RoundingMode.HALF_UP)
              : value;
      if (after.signum() <= 0) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "批量改数量后数量必须大于 0（SKU " + line.get("sku_code", String.class) + "）");
      }
      BigDecimal price = line.get("unit_price", BigDecimal.class);
      sql.execute(
          "update purchase_demand_merge_line set merged_quantity = ?::numeric, line_amount = ?::numeric,"
              + " updated_at = ?::timestamptz where tenant_id = ?::uuid and id = ?::uuid",
          after,
          price == null ? null : price.multiply(after).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP),
          now,
          tenantId,
          lineId);
      insertAdjustment(
          tenantId,
          batchId,
          "QUANTITY",
          lineId,
          line.get("draft_id", UUID.class),
          orEmpty(line.get("sku_code", String.class)),
          "merged_quantity",
          plain(before),
          plain(after),
          input.reason(),
          lineIds.size(),
          actorId,
          now);
    }
    recomputeDrafts(tenantId, batchId);
    recomputeBatch(tenantId, batchId);
    return batchDetail(batchId);
  }

  // ================================================================ 批量改供应商

  /**
   * 批量改供应商。
   *
   * <p>🔴 这会**破坏分组不变式**（「一张草稿只属于一个供应商」），所以：
   *
   * <ol>
   *   <li>行被**搬到**新供应商的草稿（草稿的供应商不可就地改写 —— DB 触发器钉死）；</li>
   *   <li>目标草稿里已有同一 SKU ⇒ 数量相加（单价冲突 ⇒ 整批拒绝）；</li>
   *   <li>搬空的老草稿被清掉（空草稿不是凭据）；</li>
   *   <li>改完**重新校验分组**：每张草稿的供应商必须唯一，不满足 ⇒ 整批回滚。</li>
   * </ol>
   *
   * <p>整个动作在**一个事务**里 —— 不许出现「改了一半」的中间态留在库里。
   */
  @Transactional
  public PurchaseDemandMergeDetail adjustSupplier(
      UUID batchId, PurchaseDemandBatchSupplierInput input, UUID actorId, String bearer) {
    UUID tenantId = currentTenant();
    requireOpenBatch(batchId, tenantId, "批量改供应商");
    List<UUID> lineIds = distinct(input.lineIds());
    Map<UUID, Record> lines = loadBatchLines(tenantId, batchId, lineIds);
    // 🔴 供应商必须**真的存在**：绝不把调用方传来的一个 id 原样写进单据（那是「假供应商」）。
    var supplier = supplierDirectory.require(input.supplierId(), bearer);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    for (UUID lineId : lineIds) {
      Record line = lines.get(lineId);
      UUID oldSupplierId = line.get("supplier_id", UUID.class);
      if (supplier.roleId().equals(oldSupplierId)) {
        // 已经是这个供应商 —— 没什么可改的，不写留痕（留痕只记真实发生的改动）。
        continue;
      }
      UUID targetDraftId = findOrCreateDraft(tenantId, batchId, supplier, now, actorId);
      BigDecimal quantity = line.get("merged_quantity", BigDecimal.class);
      BigDecimal price = line.get("unit_price", BigDecimal.class);
      BigDecimal sourceQuantity = line.get("source_quantity", BigDecimal.class);
      LocalDate arrival = line.get("arrival_date", LocalDate.class);
      UUID skuId = line.get("sku_id", UUID.class);

      var existing =
          sql.fetchOne(
              "select * from purchase_demand_merge_line where tenant_id = ?::uuid"
                  + " and draft_id = ?::uuid and sku_id = ?::uuid",
              tenantId,
              targetDraftId,
              skuId);
      if (existing != null) {
        BigDecimal existingPrice = existing.get("unit_price", BigDecimal.class);
        if (existingPrice != null && price != null && existingPrice.compareTo(price) != 0) {
          throw new ResponseStatusException(
              HttpStatus.CONFLICT,
              "批量改供应商被整体拒绝：SKU "
                  + line.get("sku_code", String.class)
                  + " 在目标供应商的草稿里已经有单价不同的行（同一 SKU 两个单价不能合成一行，"
                  + "合成会把其中一条需求的价格算错）—— 请先统一单价。本次操作**没有**改掉任何一行");
        }
        BigDecimal mergedPrice = existingPrice != null ? existingPrice : price;
        BigDecimal newQuantity =
            existing
                .get("merged_quantity", BigDecimal.class)
                .add(quantity)
                .setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
        BigDecimal newSourceQuantity =
            existing
                .get("source_quantity", BigDecimal.class)
                .add(sourceQuantity)
                .setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
        sql.execute(
            "update purchase_demand_merge_line set merged_quantity = ?::numeric,"
                + " source_quantity = ?::numeric, unit_price = ?::numeric, line_amount = ?::numeric,"
                + " arrival_date = coalesce(?::date, arrival_date), source_request_count = source_request_count + 1,"
                + " updated_at = ?::timestamptz where tenant_id = ?::uuid and id = ?::uuid",
            newQuantity,
            newSourceQuantity,
            mergedPrice,
            mergedPrice == null
                ? null
                : mergedPrice.multiply(newQuantity).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP),
            arrival,
            now,
            tenantId,
            existing.get("id", UUID.class));
        // 来源行改挂到既有行上（可追溯：这行由哪几条需求合成）。
        sql.execute(
            "update purchase_demand_merge_line_source set draft_line_id = ?::uuid"
                + " where tenant_id = ?::uuid and draft_line_id = ?::uuid",
            existing.get("id", UUID.class),
            tenantId,
            lineId);
        // 被吸收掉的那一行：来源行已改挂 ⇒ 可以安全删掉。
        sql.execute(
            "delete from purchase_demand_merge_line where tenant_id = ?::uuid and id = ?::uuid",
            tenantId,
            lineId);
      } else {
        // 搬行：draft_id 与 supplier_id 一起改（DB 触发器要求二者与草稿一致）。
        sql.execute(
            "update purchase_demand_merge_line set draft_id = ?::uuid, supplier_id = ?::uuid,"
                + " updated_at = ?::timestamptz where tenant_id = ?::uuid and id = ?::uuid",
            targetDraftId,
            supplier.roleId(),
            now,
            tenantId,
            lineId);
      }
      insertAdjustment(
          tenantId,
          batchId,
          "SUPPLIER",
          lineId,
          line.get("draft_id", UUID.class),
          orEmpty(line.get("sku_code", String.class)),
          "supplier_id",
          oldSupplierId == null ? "" : oldSupplierId.toString(),
          supplier.roleId() + "（" + supplier.partyName() + "）",
          input.reason(),
          lineIds.size(),
          actorId,
          now);
    }

    // 被搬空的老草稿清掉（空草稿不是凭据）。
    sql.execute(
        "delete from purchase_demand_merge_draft where tenant_id = ?::uuid and batch_id = ?::uuid"
            + " and not exists (select 1 from purchase_demand_merge_line l"
            + " where l.tenant_id = purchase_demand_merge_draft.tenant_id"
            + " and l.draft_id = purchase_demand_merge_draft.id)",
        tenantId,
        batchId);

    // 🔴 **重新校验分组**：每张草稿的供应商必须唯一。
    //    不满足 ⇒ 抛异常 ⇒ 整个事务回滚（库里不留「改了一半」的中间态）。
    var broken =
        sql.fetch(
            "select d.id, d.draft_no from purchase_demand_merge_draft d"
                + " join purchase_demand_merge_line l"
                + "   on l.tenant_id = d.tenant_id and l.draft_id = d.id"
                + " where d.tenant_id = ?::uuid and d.batch_id = ?::uuid"
                + " group by d.id, d.draft_no having count(distinct l.supplier_id) > 1",
            tenantId,
            batchId);
    if (!broken.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "批量改供应商被整体拒绝：改完之后草稿 "
              + broken.get(0).get("draft_no", String.class)
              + " 里出现了多个供应商 —— 合并只在同一供应商内进行。本次操作**没有**改掉任何一行");
    }

    recomputeDrafts(tenantId, batchId);
    recomputeBatch(tenantId, batchId);
    return batchDetail(batchId);
  }

  // ================================================================ 定稿

  /**
   * 定稿（OPEN → FINALIZED）：内容冻结，6.3.1.2 才能读这份草稿去建真正的采购单。
   *
   * <p>🔴 定稿前必须没有未合并行 —— 有未合并行就定稿，等于把没合上的需求悄悄带过去。
   */
  @Transactional
  public PurchaseDemandMergeBatchRow finalizeBatch(UUID batchId, UUID actorId) {
    UUID tenantId = currentTenant();
    requireOpenBatch(batchId, tenantId, "定稿");
    int unmerged =
        sql.fetchOne(
                "select count(*) from purchase_demand_merge_unmerged where tenant_id = ?::uuid"
                    + " and batch_id = ?::uuid",
                tenantId,
                batchId)
            .get(0, Integer.class);
    if (unmerged > 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "还有 " + unmerged + " 行没有合并（见「未合并行」清单，写明了原因）—— 定稿前请先处理掉");
    }
    int drafts =
        sql.fetchOne(
                "select count(*) from purchase_demand_merge_draft where tenant_id = ?::uuid"
                    + " and batch_id = ?::uuid",
                tenantId,
                batchId)
            .get(0, Integer.class);
    if (drafts == 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有采购单草稿的合并批次定不了稿");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update purchase_demand_merge_batch set status = ?::varchar, finalized_at = ?::timestamptz,"
            + " finalized_by = ?::uuid, updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        PurchaseDemandMergeBatchStatus.FINALIZED.name(),
        now,
        actorId,
        now,
        tenantId,
        batchId);
    return batchRow(batchId, tenantId);
  }

  // ================================================================ 批量打印

  /**
   * 批量打印：把选中的采购单草稿渲染成可打印的文本。
   *
   * <p>🔴 打印的是**草稿**：页面上不出现「采购单已创建」的字样 ——
   * 6.3.1.2 未交付，这只是一张「合并后待下单」的草稿。
   */
  public PurchaseDemandPrintResult print(List<UUID> draftIds) {
    UUID tenantId = currentTenant();
    List<UUID> ids = distinct(draftIds);
    var drafts =
        sql.fetch(
            DRAFT_COLUMNS + " where d.tenant_id = ?::uuid and d.id = any(?::uuid[])"
                + " order by d.supplier_name, d.id",
            tenantId,
            ids.toArray(new UUID[0]));
    if (drafts.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "选中的采购单草稿不存在或不属于当前租户");
    }
    OffsetDateTime printedAt = OffsetDateTime.now(ZoneOffset.UTC);
    List<PurchaseDemandPrintSheet> sheets = new ArrayList<>();
    int lineCount = 0;
    BigDecimal totalQuantity = BigDecimal.ZERO;
    BigDecimal totalAmount = BigDecimal.ZERO;
    boolean amountComplete = true;
    for (Record draft : drafts) {
      UUID draftId = draft.get("id", UUID.class);
      var lines =
          sql.fetch(
                  "select * from purchase_demand_merge_line where tenant_id = ?::uuid"
                      + " and draft_id = ?::uuid order by sort_rank, id",
                  tenantId,
                  draftId)
              .map(this::toLineRow);
      List<PurchaseDemandPrintLine> printLines = new ArrayList<>();
      BigDecimal sheetQuantity = BigDecimal.ZERO;
      BigDecimal sheetAmount = BigDecimal.ZERO;
      boolean sheetComplete = true;
      for (var line : lines) {
        printLines.add(
            new PurchaseDemandPrintLine(
                line.skuCode(),
                line.skuName(),
                line.supplierName(),
                line.mergedQuantity(),
                line.unitPrice(),
                line.lineAmount(),
                line.arrivalDate(),
                line.note()));
        sheetQuantity = sheetQuantity.add(line.mergedQuantity());
        if (line.lineAmount() == null) {
          sheetComplete = false;
        } else {
          sheetAmount = sheetAmount.add(line.lineAmount());
        }
      }
      BigDecimal sheetTotal = sheetComplete ? sheetAmount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP) : null;
      sheets.add(
          new PurchaseDemandPrintSheet(
              draftId,
              draft.get("draft_no", String.class),
              orEmpty(draft.get("supplier_code", String.class)),
              orEmpty(draft.get("supplier_name", String.class)),
              orEmpty(draft.get("target_warehouse_code", String.class)),
              orEmpty(draft.get("target_warehouse_name", String.class)),
              orEmpty(draft.get("legal_entity_code", String.class)),
              orEmpty(draft.get("legal_entity_name", String.class)),
              draft.get("currency_code", String.class),
              printLines,
              printLines.size(),
              sheetQuantity.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP),
              sheetTotal,
              orEmpty(draft.get("note", String.class)),
              renderSheet(draft, printLines, sheetQuantity, sheetTotal)));
      lineCount += printLines.size();
      totalQuantity = totalQuantity.add(sheetQuantity);
      if (sheetTotal == null) {
        amountComplete = false;
      } else {
        totalAmount = totalAmount.add(sheetTotal);
      }
    }
    return new PurchaseDemandPrintResult(
        printedAt,
        sheets.size(),
        lineCount,
        totalQuantity.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP),
        amountComplete ? totalAmount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP) : null,
        sheets,
        "打印的是**采购单草稿**：6.3.1.2 采购单未交付，本模块只把合并后的草稿渲染成可打印的文本，"
            + "页面上不会出现「采购单已创建」的字样。");
  }

  /** 渲染一张草稿的可打印文本（中文，纯文本，可直接丢进打印窗口）。 */
  private String renderSheet(
      Record draft,
      List<PurchaseDemandPrintLine> lines,
      BigDecimal totalQuantity,
      BigDecimal totalAmount) {
    StringBuilder text = new StringBuilder();
    text.append("采购单草稿 ").append(draft.get("draft_no", String.class)).append('\n');
    text.append("供应商：")
        .append(orEmpty(draft.get("supplier_name", String.class)))
        .append("（")
        .append(orEmpty(draft.get("supplier_code", String.class)))
        .append("）\n");
    text.append("目标仓：")
        .append(orEmpty(draft.get("target_warehouse_name", String.class)))
        .append("（")
        .append(orEmpty(draft.get("target_warehouse_code", String.class)))
        .append("）\n");
    text.append("法人：")
        .append(orEmpty(draft.get("legal_entity_name", String.class)))
        .append("（")
        .append(orEmpty(draft.get("legal_entity_code", String.class)))
        .append("）\n");
    text.append("币种：").append(draft.get("currency_code", String.class)).append('\n');
    text.append("----------------------------------------\n");
    for (var line : lines) {
      text
          .append(line.skuCode())
          .append('\t')
          .append(line.skuName())
          .append('\t')
          .append(plain(line.mergedQuantity()))
          .append('\t')
          .append(line.unitPrice() == null ? "无数据" : plain(line.unitPrice()))
          .append('\t')
          .append(line.lineAmount() == null ? "无数据" : plain(line.lineAmount()))
          .append('\t')
          .append(line.arrivalDate() == null ? "未定" : line.arrivalDate().toString())
          .append('\n');
    }
    text.append("----------------------------------------\n");
    text.append("合计：").append(lines.size()).append(" 行");
    text.append(" / 数量 ").append(plain(totalQuantity));
    text.append(" / 金额 ").append(totalAmount == null ? "无数据" : plain(totalAmount));
    text.append(' ').append(draft.get("currency_code", String.class)).append('\n');
    String note = orEmpty(draft.get("note", String.class));
    if (!note.isBlank()) text.append("备注：").append(note).append('\n');
    return text.toString();
  }

  // ================================================================ 内部

  /** 合并批次头部的取数列。 */
  private static final String BATCH_COLUMNS =
      """
      select b.id, b.batch_no, b.status, b.target_warehouse_id, b.target_warehouse_code,
             b.target_warehouse_name, b.legal_entity_id, b.legal_entity_code, b.legal_entity_name,
             b.currency_code, b.draft_count, b.line_count, b.unmerged_count, b.total_quantity,
             b.estimated_amount, b.source_request_ids, b.note, b.created_by, b.created_at, b.updated_at,
             b.finalized_at, b.finalized_by
        from purchase_demand_merge_batch b
      """;

  /** 草稿头部的取数列。 */
  private static final String DRAFT_COLUMNS =
      """
      select d.id, d.batch_id, d.draft_no, d.supplier_id, d.supplier_code, d.supplier_name,
             d.target_warehouse_id, d.target_warehouse_code, d.target_warehouse_name,
             d.legal_entity_id, d.legal_entity_code, d.legal_entity_name, d.currency_code,
             d.line_count, d.total_quantity, d.estimated_amount, d.status, d.note, d.created_by,
             d.created_at, d.updated_at
        from purchase_demand_merge_draft d
      """;

  /** 草稿行的取数列（带草稿的供应商展示快照）。 */
  private static final String LINE_COLUMNS =
      """
      select l.id, l.draft_id, l.sku_id, l.sku_code, l.sku_name, l.supplier_id, d.supplier_code,
             d.supplier_name, l.merged_quantity, l.source_quantity, l.unit_price, l.line_amount,
             l.arrival_date, l.source_request_count, l.sort_rank, l.note, l.created_at, l.updated_at
        from purchase_demand_merge_line l
        join purchase_demand_merge_draft d
          on d.tenant_id = l.tenant_id and d.id = l.draft_id
      """;

  /** 加载源需求行（连上源申请单的状态与目标属性）。 */
  private List<Record> loadSourceLines(
      UUID tenantId, List<UUID> requestIds, List<UUID> sourceLineIds) {
    var binds = new ArrayList<Object>();
    binds.add(tenantId);
    binds.add(requestIds.toArray(new UUID[0]));
    var where = new StringBuilder(
        " where l.tenant_id = ?::uuid and l.request_id = any(?::uuid[])");
    if (sourceLineIds != null && !sourceLineIds.isEmpty()) {
      where.append(" and l.id = any(?::uuid[])");
      binds.add(sourceLineIds.toArray(new UUID[0]));
    }
    var rows =
        sql.fetch(
            "select l.id, l.request_id, l.sku_id, l.sku_code, l.sku_name, l.requested_quantity,"
                + " l.unit_price, l.supplier_id, l.supplier_code, l.supplier_name,"
                + " l.requested_arrival_date, l.sort_rank,"
                + " r.status as request_status, r.request_no, r.target_warehouse_id,"
                + " r.legal_entity_id, r.currency_code"
                + " from purchase_request_line l"
                + " join purchase_request r on r.tenant_id = l.tenant_id and r.id = l.request_id"
                + where
                + " order by l.request_id, l.sort_rank, l.id",
            binds.toArray());
    if (sourceLineIds != null && !sourceLineIds.isEmpty() && rows.size() != sourceLineIds.size()) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "选中的需求行不存在，或不属于你选中的那些采购申请单");
    }
    return rows;
  }

  /** 加载本批次的若干行（必须都属于本批次）。 */
  private Map<UUID, Record> loadBatchLines(UUID tenantId, UUID batchId, List<UUID> lineIds) {
    var rows =
        sql.fetch(
            "select l.* from purchase_demand_merge_line l"
                + " join purchase_demand_merge_draft d"
                + "   on d.tenant_id = l.tenant_id and d.id = l.draft_id"
                + " where l.tenant_id = ?::uuid and d.batch_id = ?::uuid and l.id = any(?::uuid[])",
            tenantId,
            batchId,
            lineIds.toArray(new UUID[0]));
    Map<UUID, Record> byId = new LinkedHashMap<>();
    for (Record r : rows) byId.put(r.get("id", UUID.class), r);
    for (UUID wanted : lineIds) {
      if (!byId.containsKey(wanted)) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "行 " + wanted + " 不属于这个合并批次（或不属于当前租户）");
      }
    }
    return byId;
  }

  /** 找到（或建出）某供应商在本批次的草稿。 */
  private UUID findOrCreateDraft(
      UUID tenantId,
      UUID batchId,
      SupplierPartyQueryPort.SupplierView supplier,
      OffsetDateTime now,
      UUID actorId) {
    var existing =
        sql.fetchOne(
            "select id from purchase_demand_merge_draft where tenant_id = ?::uuid and batch_id = ?::uuid"
                + " and supplier_id = ?::uuid",
            tenantId,
            batchId,
            supplier.roleId());
    if (existing != null) return existing.get("id", UUID.class);
    var batch =
        sql.fetchOne(
            "select target_warehouse_id, target_warehouse_code, target_warehouse_name,"
                + " legal_entity_id, legal_entity_code, legal_entity_name, currency_code"
                + " from purchase_demand_merge_batch where tenant_id = ?::uuid and id = ?::uuid",
            tenantId,
            batchId);
    UUID draftId = UUID.randomUUID();
    sql.execute(
        "insert into purchase_demand_merge_draft(id,tenant_id,batch_id,draft_no,supplier_id,"
            + " supplier_code,supplier_name,target_warehouse_id,target_warehouse_code,"
            + " target_warehouse_name,legal_entity_id,legal_entity_code,legal_entity_name,"
            + " currency_code,status,note,created_by,created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::uuid,?::varchar,?::varchar,?::uuid,"
            + " ?::varchar,?::varchar,?::uuid,?::varchar,?::varchar,?::varchar,'DRAFT',?::varchar,"
            + " ?::uuid,?::timestamptz,?::timestamptz)",
        draftId,
        tenantId,
        batchId,
        "PD-" + today() + "-" + shortId(),
        supplier.roleId(),
        supplier.partyCode(),
        supplier.partyName(),
        batch.get("target_warehouse_id", UUID.class),
        orEmpty(batch.get("target_warehouse_code", String.class)),
        orEmpty(batch.get("target_warehouse_name", String.class)),
        batch.get("legal_entity_id", UUID.class),
        orEmpty(batch.get("legal_entity_code", String.class)),
        orEmpty(batch.get("legal_entity_name", String.class)),
        batch.get("currency_code", String.class),
        actorId,
        now,
        now);
    return draftId;
  }

  private void insertLineSource(
      UUID tenantId,
      UUID draftLineId,
      UUID sourceRequestId,
      UUID sourceRequestLineId,
      UUID sourceSkuId,
      String sourceSkuCode,
      BigDecimal sourceQuantity,
      OffsetDateTime mergedAt) {
    sql.execute(
        "insert into purchase_demand_merge_line_source(id,tenant_id,draft_line_id,source_request_id,"
            + " source_request_line_id,source_sku_id,source_sku_code,source_quantity,merged_at,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::varchar,?::numeric,"
            + " ?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        draftLineId,
        sourceRequestId,
        sourceRequestLineId,
        sourceSkuId,
        sourceSkuCode,
        sourceQuantity,
        mergedAt,
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  private void insertAdjustment(
      UUID tenantId,
      UUID batchId,
      String scope,
      UUID lineId,
      UUID draftId,
      String skuCode,
      String field,
      String before,
      String after,
      String reason,
      int affectedLineCount,
      UUID actorId,
      OffsetDateTime at) {
    sql.execute(
        "insert into purchase_demand_batch_adjustment(id,tenant_id,batch_id,scope,line_id,draft_id,"
            + " sku_code,field,before_value,after_value,reason,affected_line_count,adjusted_by,"
            + " adjusted_at,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::uuid,?::uuid,?::varchar,?::varchar,"
            + " ?::varchar,?::varchar,?::varchar,?,?::uuid,?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        batchId,
        scope,
        lineId,
        draftId,
        skuCode,
        field,
        before,
        after,
        reason,
        affectedLineCount,
        actorId,
        at,
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  /** 重算批次内每张草稿的统计。 */
  private void recomputeDrafts(UUID tenantId, UUID batchId) {
    var drafts =
        sql.fetch(
            "select id from purchase_demand_merge_draft where tenant_id = ?::uuid and batch_id = ?::uuid",
            tenantId,
            batchId);
    for (Record d : drafts) recomputeDraft(tenantId, d.get("id", UUID.class));
  }

  /** 重算一张草稿的统计（有一行没单价 ⇒ 金额算不出来 = NULL，不是 0）。 */
  private void recomputeDraft(UUID tenantId, UUID draftId) {
    var row =
        sql.fetchOne(
            "select count(*) as total, count(unit_price) as priced,"
                + " coalesce(sum(merged_quantity),0) as qty, coalesce(sum(line_amount),0) as amount"
                + " from purchase_demand_merge_line where tenant_id = ?::uuid and draft_id = ?::uuid",
            tenantId,
            draftId);
    int total = row.get("total", Integer.class);
    int priced = row.get("priced", Integer.class);
    BigDecimal amount = row.get("amount", BigDecimal.class);
    BigDecimal quantity = row.get("qty", BigDecimal.class);
    BigDecimal value = (total > 0 && priced == total) ? amount : null;
    sql.execute(
        "update purchase_demand_merge_draft set line_count = ?, total_quantity = ?::numeric,"
            + " estimated_amount = ?::numeric, updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        total,
        quantity,
        value,
        OffsetDateTime.now(ZoneOffset.UTC),
        tenantId,
        draftId);
  }

  /** 重算批次的统计。 */
  private void recomputeBatch(UUID tenantId, UUID batchId) {
    var row =
        sql.fetchOne(
            "select count(*) as draft_count, coalesce(sum(line_count),0) as line_count,"
                + " coalesce(sum(total_quantity),0) as qty, count(estimated_amount) as priced,"
                + " coalesce(sum(estimated_amount),0) as amount"
                + " from purchase_demand_merge_draft where tenant_id = ?::uuid and batch_id = ?::uuid",
            tenantId,
            batchId);
    int drafts = row.get("draft_count", Integer.class);
    int lines = row.get("line_count", Integer.class);
    int priced = row.get("priced", Integer.class);
    BigDecimal amount = row.get("amount", BigDecimal.class);
    BigDecimal quantity = row.get("qty", BigDecimal.class);
    int unmerged =
        sql.fetchOne(
                "select count(*) from purchase_demand_merge_unmerged where tenant_id = ?::uuid"
                    + " and batch_id = ?::uuid",
                tenantId,
                batchId)
            .get(0, Integer.class);
    BigDecimal value = (drafts > 0 && priced == drafts) ? amount : null;
    sql.execute(
        "update purchase_demand_merge_batch set draft_count = ?, line_count = ?, unmerged_count = ?,"
            + " total_quantity = ?::numeric, estimated_amount = ?::numeric, updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        drafts,
        lines,
        unmerged,
        quantity,
        value,
        OffsetDateTime.now(ZoneOffset.UTC),
        tenantId,
        batchId);
  }

  private Record requireBatch(UUID batchId, UUID tenantId) {
    var row =
        sql.fetchOne(
            "select * from purchase_demand_merge_batch where tenant_id = ?::uuid and id = ?::uuid",
            tenantId,
            batchId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "合并批次不存在或不属于当前租户");
    }
    return row;
  }

  private void requireOpenBatch(UUID batchId, UUID tenantId, String action) {
    var batch = requireBatch(batchId, tenantId);
    String status = orEmpty(batch.get("status", String.class));
    if (!PurchaseDemandMergeBatchStatus.OPEN.name().equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "合并批次已定稿（当前状态："
              + batchStatusLabel(status)
              + "），不能再"
              + action
              + " —— 定稿后内容冻结，6.3.1.2 要读一份不再变动的草稿");
    }
  }

  private PurchaseDemandMergeBatchRow batchRow(UUID batchId, UUID tenantId) {
    var row = sql.fetchOne(BATCH_COLUMNS + " where b.tenant_id = ?::uuid and b.id = ?::uuid", tenantId, batchId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "合并批次不存在或不属于当前租户");
    }
    return toBatchRow(row);
  }

  // ================================================================ 行映射

  private PurchaseDemandMergeBatchRow toBatchRow(Record r) {
    String status = orEmpty(r.get("status", String.class));
    return new PurchaseDemandMergeBatchRow(
        r.get("id", UUID.class),
        r.get("batch_no", String.class),
        status,
        batchStatusLabel(status),
        r.get("target_warehouse_id", UUID.class),
        orEmpty(r.get("target_warehouse_code", String.class)),
        orEmpty(r.get("target_warehouse_name", String.class)),
        r.get("legal_entity_id", UUID.class),
        orEmpty(r.get("legal_entity_code", String.class)),
        orEmpty(r.get("legal_entity_name", String.class)),
        r.get("currency_code", String.class),
        r.get("draft_count", Integer.class) == null ? 0 : r.get("draft_count", Integer.class),
        r.get("line_count", Integer.class) == null ? 0 : r.get("line_count", Integer.class),
        r.get("unmerged_count", Integer.class) == null ? 0 : r.get("unmerged_count", Integer.class),
        r.get("total_quantity", BigDecimal.class),
        r.get("estimated_amount", BigDecimal.class),
        readUuidArray(r, "source_request_ids"),
        orEmpty(r.get("note", String.class)),
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class),
        r.get("finalized_at", OffsetDateTime.class),
        r.get("finalized_by", UUID.class));
  }

  private PurchaseDemandMergeDraftRow toDraftRow(Record r) {
    String status = orEmpty(r.get("status", String.class));
    return new PurchaseDemandMergeDraftRow(
        r.get("id", UUID.class),
        r.get("batch_id", UUID.class),
        r.get("draft_no", String.class),
        r.get("supplier_id", UUID.class),
        orEmpty(r.get("supplier_code", String.class)),
        orEmpty(r.get("supplier_name", String.class)),
        r.get("target_warehouse_id", UUID.class),
        orEmpty(r.get("target_warehouse_code", String.class)),
        orEmpty(r.get("target_warehouse_name", String.class)),
        r.get("legal_entity_id", UUID.class),
        orEmpty(r.get("legal_entity_code", String.class)),
        orEmpty(r.get("legal_entity_name", String.class)),
        r.get("currency_code", String.class),
        r.get("line_count", Integer.class) == null ? 0 : r.get("line_count", Integer.class),
        r.get("total_quantity", BigDecimal.class),
        r.get("estimated_amount", BigDecimal.class),
        status,
        draftStatusLabel(status),
        orEmpty(r.get("note", String.class)),
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private PurchaseDemandMergeLineRow toLineRow(Record r) {
    return new PurchaseDemandMergeLineRow(
        r.get("id", UUID.class),
        r.get("draft_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        orEmpty(r.get("sku_name", String.class)),
        r.get("supplier_id", UUID.class),
        orEmpty(r.get("supplier_code", String.class)),
        orEmpty(r.get("supplier_name", String.class)),
        r.get("merged_quantity", BigDecimal.class),
        r.get("source_quantity", BigDecimal.class),
        r.get("unit_price", BigDecimal.class),
        r.get("line_amount", BigDecimal.class),
        r.get("arrival_date", LocalDate.class),
        r.get("source_request_count", Integer.class) == null ? 0 : r.get("source_request_count", Integer.class),
        r.get("sort_rank", Integer.class) == null ? 0 : r.get("sort_rank", Integer.class),
        orEmpty(r.get("note", String.class)),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private PurchaseDemandMergeLineSourceRow toLineSourceRow(Record r) {
    return new PurchaseDemandMergeLineSourceRow(
        r.get("id", UUID.class),
        r.get("draft_line_id", UUID.class),
        r.get("source_request_id", UUID.class),
        r.get("source_request_line_id", UUID.class),
        r.get("source_sku_id", UUID.class),
        orEmpty(r.get("source_sku_code", String.class)),
        r.get("source_quantity", BigDecimal.class),
        r.get("merged_at", OffsetDateTime.class));
  }

  private PurchaseDemandMergeUnmergedRow toUnmergedRow(Record r) {
    String code = orEmpty(r.get("reason_code", String.class));
    return new PurchaseDemandMergeUnmergedRow(
        r.get("id", UUID.class),
        r.get("batch_id", UUID.class),
        r.get("source_request_id", UUID.class),
        r.get("source_request_line_id", UUID.class),
        r.get("source_sku_id", UUID.class),
        orEmpty(r.get("source_sku_code", String.class)),
        orEmpty(r.get("source_sku_name", String.class)),
        r.get("source_quantity", BigDecimal.class),
        r.get("source_supplier_id", UUID.class),
        orEmpty(r.get("source_supplier_name", String.class)),
        code,
        reasonCodeLabel(code),
        orEmpty(r.get("reason", String.class)),
        r.get("created_at", OffsetDateTime.class));
  }

  private PurchaseDemandBatchAdjustmentRow toAdjustmentRow(Record r) {
    return new PurchaseDemandBatchAdjustmentRow(
        r.get("id", UUID.class),
        r.get("batch_id", UUID.class),
        orEmpty(r.get("scope", String.class)),
        r.get("line_id", UUID.class),
        r.get("draft_id", UUID.class),
        orEmpty(r.get("sku_code", String.class)),
        orEmpty(r.get("field", String.class)),
        r.get("before_value", String.class),
        r.get("after_value", String.class),
        orEmpty(r.get("reason", String.class)),
        r.get("affected_line_count", Integer.class) == null ? 0 : r.get("affected_line_count", Integer.class),
        r.get("adjusted_by", UUID.class),
        r.get("adjusted_at", OffsetDateTime.class));
  }

  // ================================================================ 小工具

  private PurchasePlanWarehousePort.WarehouseView resolveWarehouse(UUID id, String bearer) {
    try {
      for (var view : warehouseReference.warehouses(bearer)) {
        if (view.id().equals(id)) return view;
      }
    } catch (ResponseStatusException e) {
      return null;
    }
    return null;
  }

  private PurchasePlanWarehousePort.LegalEntityView resolveLegalEntity(UUID id, String bearer) {
    try {
      for (var view : warehouseReference.legalEntities(bearer)) {
        if (view.id().equals(id)) return view;
      }
    } catch (ResponseStatusException e) {
      return null;
    }
    return null;
  }

  private static List<UUID> distinct(List<UUID> ids) {
    List<UUID> out = new ArrayList<>();
    for (UUID id : ids) {
      if (id != null && !out.contains(id)) out.add(id);
    }
    if (out.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "至少要指定一行");
    }
    return out;
  }

  /** 读 uuid[] 列（jOOQ 对 PG 数组的返回形态有几种，统一处理）。 */
  private static List<UUID> readUuidArray(Record r, String column) {
    Object raw = r.get(column);
    List<UUID> out = new ArrayList<>();
    if (raw == null) return out;
    if (raw instanceof UUID[] arr) {
      out.addAll(Arrays.asList(arr));
      return out;
    }
    if (raw instanceof Object[] arr) {
      for (Object o : arr) out.add(UUID.fromString(String.valueOf(o)));
      return out;
    }
    if (raw instanceof List<?> list) {
      for (Object o : list) out.add(UUID.fromString(String.valueOf(o)));
      return out;
    }
    return out;
  }

  private static LocalDate earliest(LocalDate a, LocalDate b) {
    if (a == null) return b;
    if (b == null) return a;
    return a.isBefore(b) ? a : b;
  }

  private static String batchStatusLabel(String status) {
    if (status == null) return "";
    try {
      return PurchaseDemandMergeBatchStatus.valueOf(status).label();
    } catch (IllegalArgumentException e) {
      return status;
    }
  }

  private static String draftStatusLabel(String status) {
    if (status == null) return "";
    try {
      return PurchaseDemandMergeDraftStatus.valueOf(status).label();
    } catch (IllegalArgumentException e) {
      return status;
    }
  }

  private static String reasonCodeLabel(String code) {
    if (code == null) return "";
    try {
      return PurchaseDemandMergeReasonCode.valueOf(code).label();
    } catch (IllegalArgumentException e) {
      return code;
    }
  }

  private static String plain(BigDecimal value) {
    return value == null ? "" : value.stripTrailingZeros().toPlainString();
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String appendNote(String base, String extra) {
    if (extra == null || extra.isBlank()) return base;
    return base.isBlank() ? extra : base + "；" + extra;
  }

  private static int pageSize(int size) {
    if (size <= 0) return 50;
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private static String today() {
    return LocalDate.now(ZoneOffset.UTC).toString().replace("-", "");
  }

  private static String shortId() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }

  /** 当前租户（来自已认证成员关系绑定的 RLS 上下文）。 */
  private UUID currentTenant() {
    var row =
        sql.fetchOne("select nullif(current_setting('app.current_tenant', true), '')::uuid as t");
    UUID tenant = row == null ? null : row.get("t", UUID.class);
    if (tenant == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    return tenant;
  }
}
