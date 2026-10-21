package com.starcon.trade;

import static com.starcon.trade.PurchaseRequestModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.2.2.1 采购申请（trade 服务）。
 *
 * <p>蓝图原话：「把计划行/补货建议转成采购申请，走审批，确认供应商与数量后转采购单。」
 *
 * <p><b>🔴 三条不可让步的纪律</b>：
 *
 * <ol>
 *   <li><b>审批不自造</b>：要不要审、谁来审、批不批，全部由 17 组织（identity）裁定。
 *       本服务<b>没有</b>本地批准端点 —— 本地批准会把审批流本地化，identity 那边什么都不知道
 *       还留一条没人管的待办。审批服务不可达 ⇒ <b>停在待审</b>并把失败原因写进
 *       {@code approval_sync_error}，<b>绝不</b>自动跳到已批。</li>
 *   <li><b>转单是事实，不是假采购单</b>：6.3.1.2 采购单<b>未交付</b>，所以「已转单」这一步
 *       把「转成采购单时的供应商 / 数量 / 交期 / 目标仓」如实记进
 *       {@code purchase_request_conversion}，并可追溯到源计划行。
 *       本服务<b>绝不</b>造一张占位的假采购单表。</li>
 *   <li><b>上游只读</b>：6.2.1.3 的 {@code purchase_plan} / {@code purchase_plan_line} 与
 *       6.2.1.2 的 {@code replenishment_suggestion} 只 SELECT，一个字节都不改，
 *       也不建到它们的跨表外键（写入所有权分别是 6.2.1.3 与 6.2.1.2）。</li>
 * </ol>
 *
 * <p><b>租户</b>：一律来自已认证成员关系（{@code AccessService}），本服务从头到尾没有读过
 * {@code X-Tenant-Id}；RLS 在 DB 层强制隔离。
 */
@Service
public class PurchaseRequestService {

  /** 数量标度（基础单位）。 */
  private static final int QUANTITY_SCALE = 6;
  /** 金额 / 单价标度。 */
  private static final int AMOUNT_SCALE = 4;
  /** 每页最多多少条。 */
  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;
  private final PurchaseRequestApprovalPort approvals;
  private final PurchaseRequestSupplierPort supplierDirectory;
  private final PurchasePlanWarehousePort warehouseReference;

  public PurchaseRequestService(
      DSLContext sql,
      PurchaseRequestApprovalPort approvals,
      PurchaseRequestSupplierPort supplierDirectory,
      PurchasePlanWarehousePort warehouseReference) {
    this.sql = sql;
    this.approvals = approvals;
    this.supplierDirectory = supplierDirectory;
    this.warehouseReference = warehouseReference;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>🔴 采购单（6.3.1.2）**未交付** ⇒ {@code purchaseOrderDelivered=false}，
   * 并把「本模块只产出转单事实、不产出采购单」写清楚 —— 页面据此显示，
   * 而不是让用户以为转单之后系统就真的下了单。
   */
  public PurchaseRequestCapabilities capabilities() {
    return new PurchaseRequestCapabilities(
        true,
        "6.2.1.3 采购计划（trade 服务，已交付）",
        "计划行读 6.2.1.3 的 purchase_plan / purchase_plan_line —— 本模块只 SELECT，"
            + "不改它一个字，也不建到它的外键。要把计划行转成申请单，走 POST "
            + "/trade/purchase-requests（sourceType=PLAN + sourceLineIds）。",
        true,
        "6.2.1.2 补货建议（trade 服务，已交付）",
        "补货建议读 6.2.1.2 的 replenishment_suggestion —— 本模块只 SELECT。"
            + "建议转申请单时取**处置后的数量**（decided_quantity），没处置过才退回建议量；"
            + "两者都没有（建议被忽略 / 量不出来）就不该变成一张要下单的申请行。",
        supplierDirectory.available(),
        "6.1.1.x 供应商主数据（catalog / party）",
        supplierDirectory.available()
            ? "供应商经 catalog 的授权只读接口（GET /catalog/supply/vendors）读取。"
                + "本服务不建供应商影子表 —— 供应商是 catalog 的事实。选中的供应商会随"
                + "申请行一起留痕，转单时再确认一次（转单事实里记的是确认后的供应商）。"
            : supplierDirectory.unavailableNote(),
        true,
        "7.x 目标仓与法人（warehouse 服务）",
        "目标仓与法人经 warehouse 的授权只读接口（GET /warehouse/warehouses、"
            + "GET /warehouse/legal-entities）读取。本服务不建仓库 / 法人的影子表。"
            + "一张申请单只对一个目标仓与一个法人 —— 从计划转单时若选中的计划行跨了仓，会被拒绝。",
        true,
        "POST /identity/approval-rules/evaluate + POST /identity/approval-instances"
            + "（17 组织，identity 服务）；结论拉取式同步：GET /identity/approval-records/{id}",
        "17 审批（identity 服务）",
        "审批结论由 17 组织裁定，本服务只如实记录：要不要审（求值）、命中了哪条规则、"
            + "走了哪条流程、审批实例号、结论同步有没有出错。单据类型用出厂注册的 "
            + "PURCHASE_ORDER（identity V83），本模块**没有**本地批准端点 —— "
            + "审批服务不可达时申请单停在「待审」并把原因写进 approval_sync_error，"
            + "**绝不**自动跳到已批。",
        false,
        "6.3.1.2 采购单创建与编辑",
        "6.3.1.2 采购单**未交付** ⇒ 本模块的「已转单」是一个**事实**："
            + "把转成采购单时的供应商 / 数量 / 交期 / 目标仓如实记进 purchase_request_conversion，"
            + "并可追溯到源计划行。本模块**不会**造一张占位的假采购单表；"
            + "6.3.1.2 交付后由它读这份转单事实去建真正的采购单。",
        PurchaseRequestApprovalPort.DOCUMENT_TYPE,
        PurchaseRequestApprovalPort.SOURCE_SYSTEM,
        "草稿 → 待审 / 已批；待审 → 已批 / 已驳回；已批 → 已转单。"
            + "非法转换由 DB 层触发器 guard_purchase_request_status 直接拒。",
        "本模块的职责边界：把计划行 / 补货建议转成申请单 → 选供应商 → 调整数量与交期"
            + "（留痕）→ 提交审批 → 确认供应商与数量后转单（记转单事实）。"
            + "申请是**内部决策单据**，采购单才是对供应商的正式承诺 —— 两者分离，审批留痕与事后追溯才有意义。");
  }

  // ================================================================ 参照下拉

  /**
   * 建申请单要用的下拉项：供应商 / 目标仓 / 法人。
   *
   * <p>🔴 供应商读不到时**如实报出**（{@code supplierNote} 非空 + 清单为空），
   * 页面据此显示「未接线 / 暂时读不到」，<b>绝不</b>显示一个「这个租户没有供应商」的假下拉。
   */
  public PurchaseRequestOptions options(String bearer) {
    List<PurchaseRequestSupplierOption> supplierRows = new ArrayList<>();
    String supplierNote = "";
    try {
      for (SupplierPartyQueryPort.SupplierView view : supplierDirectory.suppliers(null, bearer)) {
        supplierRows.add(
            new PurchaseRequestSupplierOption(
                view.roleId(), view.partyCode(), view.partyName(), view.partyStatus(), false));
      }
    } catch (ResponseStatusException e) {
      supplierNote = e.getReason() == null ? "供应商清单暂时读不到" : e.getReason();
    }

    List<PurchaseRequestWarehouseOption> warehouseRows = new ArrayList<>();
    List<PurchaseRequestLegalEntityOption> legalRows = new ArrayList<>();
    String warehouseNote = "";
    try {
      for (PurchasePlanWarehousePort.WarehouseView view :
          warehouseReference.warehouses(bearer)) {
        warehouseRows.add(
            new PurchaseRequestWarehouseOption(
                view.id(), view.code(), view.name(), view.countryCode(), view.status()));
      }
      for (PurchasePlanWarehousePort.LegalEntityView view :
          warehouseReference.legalEntities(bearer)) {
        legalRows.add(
            new PurchaseRequestLegalEntityOption(
                view.id(), view.code(), view.name(), view.status()));
      }
    } catch (ResponseStatusException e) {
      warehouseNote = e.getReason() == null ? "仓库参照暂时读不到" : e.getReason();
    }

    return new PurchaseRequestOptions(
        supplierRows, warehouseRows, legalRows, supplierNote, warehouseNote,
        "供应商来自 catalog（party）、目标仓与法人来自 warehouse —— 都是跨服务的授权只读接口，"
            + "本服务不建它们的影子表。哪一项读不到，下面会写明原因。");
  }

  // ================================================================ 清单

  /** 申请单列表：真分页 + 真筛选 + 真排序。 */
  public PurchaseRequestPage pageRequests(
      String status,
      String sourceType,
      UUID sourcePlanId,
      UUID targetWarehouseId,
      UUID supplierId,
      String keyword,
      String sort,
      int page,
      int size) {
    UUID tenantId = currentTenant();
    var where = new StringBuilder(" where r.tenant_id = ?::uuid");
    var binds = new ArrayList<Object>();
    binds.add(tenantId);
    if (status != null && !status.isBlank()) {
      where.append(" and r.status = ?::varchar");
      binds.add(status.trim());
    }
    if (sourceType != null && !sourceType.isBlank()) {
      where.append(" and r.source_type = ?::varchar");
      binds.add(sourceType.trim());
    }
    if (sourcePlanId != null) {
      where.append(" and r.source_plan_id = ?::uuid");
      binds.add(sourcePlanId);
    }
    if (targetWarehouseId != null) {
      where.append(" and r.target_warehouse_id = ?::uuid");
      binds.add(targetWarehouseId);
    }
    if (supplierId != null) {
      where.append(
          " and exists (select 1 from purchase_request_line sl"
              + " where sl.tenant_id = r.tenant_id and sl.request_id = r.id and sl.supplier_id = ?::uuid)");
      binds.add(supplierId);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(
          " and (r.request_no ilike ?::varchar or r.note ilike ?::varchar"
              + " or exists (select 1 from purchase_request_line kl"
              + " where kl.tenant_id = r.tenant_id and kl.request_id = r.id"
              + " and (kl.sku_code ilike ?::varchar or kl.sku_name ilike ?::varchar)))");
      String like = "%" + keyword.trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }

    long total =
        sql.fetchOne("select count(*) from purchase_request r" + where, binds.toArray()).get(0, Long.class);
    int limit = pageSize(size);
    int offset = Math.max(page, 0) * limit;
    var ordered = new ArrayList<>(binds);
    ordered.add(limit);
    ordered.add(offset);
    List<PurchaseRequestRow> items =
        sql.fetch(REQUEST_COLUMNS + where + orderBy(sort) + " limit ? offset ?", ordered.toArray())
            .map(this::toRow);
    return new PurchaseRequestPage(items, total, Math.max(page, 0), limit);
  }

  /** 排序白名单：拼错却安静地给出另一种顺序，比报错更难查。 */
  private static String orderBy(String sort) {
    return switch (sort == null ? "" : sort.trim()) {
      case "NEWEST" -> " order by r.created_at desc, r.id desc";
      case "OLDEST" -> " order by r.created_at asc, r.id asc";
      case "ARRIVAL" -> " order by r.expected_arrival asc nulls last, r.id desc";
      case "AMOUNT_DESC" -> " order by r.estimated_amount desc nulls last, r.id desc";
      case "STATUS" -> " order by r.status asc, r.created_at desc, r.id desc";
      default -> throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "排序键需为 NEWEST / OLDEST / ARRIVAL / AMOUNT_DESC / STATUS");
    };
  }

  // ================================================================ 详情

  /**
   * 申请单详情：头 + 行 + 调整留痕 + 转单事实。
   *
   * @param refreshApproval true 时顺带**拉一次**审批状态（读详情即校验收到的结论）
   */
  @Transactional
  public PurchaseRequestDetail requestDetail(UUID id, boolean refreshApproval, String bearer) {
    if (refreshApproval) {
      try {
        var refreshed = refreshApproval(id, null, bearer);
        if (refreshed.failed()) {
          // 拉取失败**不让整个详情挂掉**：详情页要照常显示「停在待审 + 同步失败原因」。
          return requestDetail(id, false, bearer);
        }
      } catch (ResponseStatusException e) {
        // 同上：拉取失败**不让整个详情挂掉**。
      }
    }
    UUID tenantId = currentTenant();
    var request = requestRow(id, tenantId);
    List<PurchaseRequestLineRow> lines =
        sql.fetch(
                "select * from purchase_request_line where tenant_id = ?::uuid and request_id = ?::uuid"
                    + " order by sort_rank, id",
                tenantId,
                id)
            .map(this::toLineRow);
    List<PurchaseRequestAdjustmentRow> adjustments =
        sql.fetch(
                "select a.*, l.sku_code from purchase_request_adjustment a"
                    + " join purchase_request_line l"
                    + "   on l.tenant_id = a.tenant_id and l.id = a.request_line_id"
                    + " where a.tenant_id = ?::uuid and l.request_id = ?::uuid"
                    + " order by a.adjusted_at desc, a.id desc",
                tenantId,
                id)
            .map(this::toAdjustmentRow);
    List<PurchaseRequestConversionRow> conversions =
        sql.fetch(
                "select c.*, l.sku_code from purchase_request_conversion c"
                    + " join purchase_request_line l"
                    + "   on l.tenant_id = c.tenant_id and l.id = c.request_line_id"
                    + " where c.tenant_id = ?::uuid and c.request_id = ?::uuid"
                    + " order by c.converted_at desc, c.id desc",
                tenantId,
                id)
            .map(this::toConversionRow);
    return new PurchaseRequestDetail(request, lines, adjustments, conversions);
  }

  // ================================================================ 建单

  /**
   * 建一张采购申请单（草稿）。
   *
   * <p>三种来源：
   *
   * <ul>
   *   <li>{@code PLAN} —— 从 6.2.1.3 的<b>计划行</b>转过来。目标仓与法人**继承计划头**；
   *       若选中的计划行跨了目标仓 ⇒ 拒绝（一张申请单只对一个仓）。</li>
   *   <li>{@code SUGGESTION} —— 从 6.2.1.2 的<b>补货建议</b>转过来。目标仓与法人由调用方给
   *       （建议本身没有仓的概念）。</li>
   *   <li>{@code MANUAL} —— 手工建单，行内容全部由调用方给。</li>
   * </ul>
   */
  @Transactional
  public PurchaseRequestRow create(
      PurchaseRequestCreateInput input, UUID actorId, String bearer) {
    UUID tenantId = currentTenant();
    PurchaseRequestSourceType sourceType =
        switch (input.sourceType() == null ? "" : input.sourceType().trim()) {
          case "PLAN" -> PurchaseRequestSourceType.PLAN;
          case "SUGGESTION" -> PurchaseRequestSourceType.SUGGESTION;
          case "MANUAL" -> PurchaseRequestSourceType.MANUAL;
          default -> throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "来源类型只能是 PLAN / SUGGESTION / MANUAL");
        };

    UUID targetWarehouseId = input.targetWarehouseId();
    UUID legalEntityId = input.legalEntityId();
    String warehouseCode = "";
    String warehouseName = "";
    String entityCode = "";
    String entityName = "";
    UUID sourcePlanId = input.sourcePlanId();
    UUID sourceBatchId = input.sourceBatchId();
    String currency = blankToNull(input.currencyCode());
    List<NewLine> newLines = new ArrayList<>();

    switch (sourceType) {
      case PLAN -> {
        if (sourcePlanId == null) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "从采购计划转申请单时必须给出来源计划 id");
        }
        var plan =
            sql.fetchOne(
                "select id, plan_no, target_warehouse_id, target_warehouse_code,"
                    + " target_warehouse_name, legal_entity_id, legal_entity_code, legal_entity_name"
                    + " from purchase_plan where tenant_id = ?::uuid and id = ?::uuid",
                tenantId,
                sourcePlanId);
        if (plan == null) {
          throw new ResponseStatusException(HttpStatus.NOT_FOUND, "来源采购计划不存在或不属于当前租户");
        }
        List<UUID> lineIds = requireIds(input.sourceLineIds(), "必须指定要转成申请行的计划行");
        var rows =
            sql.fetch(
                "select id, sku_id, sku_code, sku_name, plan_quantity, expected_arrival_date,"
                    + " priority, target_warehouse_id, target_warehouse_code, target_warehouse_name"
                    + " from purchase_plan_line where tenant_id = ?::uuid and plan_id = ?::uuid"
                    + " and id = any(?::uuid[])",
                tenantId,
                sourcePlanId,
                lineIds.toArray(new UUID[0]));
        Map<UUID, Record> byId = new HashMap<>();
        for (Record row : rows) byId.put(row.get("id", UUID.class), row);
        for (UUID wanted : lineIds) {
          if (!byId.containsKey(wanted)) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND, "计划行 " + wanted + " 不存在于这张计划（或不属于当前租户）");
          }
        }
        // 一张申请单只对一个目标仓：跨仓的计划行必须分批建单，否则货会走错仓。
        for (Record row : rows) {
          UUID lineWarehouse = row.get("target_warehouse_id", UUID.class);
          UUID planWarehouse = plan.get("target_warehouse_id", UUID.class);
          if (lineWarehouse != null && !lineWarehouse.equals(planWarehouse)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "选中的计划行里有跨目标仓的行（"
                    + row.get("sku_code", String.class)
                    + " 属于另一个仓）—— 一张采购申请单只对一个目标仓，请按仓分批建单");
          }
        }
        targetWarehouseId = plan.get("target_warehouse_id", UUID.class);
        legalEntityId = plan.get("legal_entity_id", UUID.class);
        warehouseCode = orEmpty(plan.get("target_warehouse_code", String.class));
        warehouseName = orEmpty(plan.get("target_warehouse_name", String.class));
        entityCode = orEmpty(plan.get("legal_entity_code", String.class));
        entityName = orEmpty(plan.get("legal_entity_name", String.class));
        int rank = 0;
        for (UUID wanted : lineIds) {
          Record row = byId.get(wanted);
          BigDecimal quantity = row.get("plan_quantity", BigDecimal.class);
          if (quantity == null || quantity.signum() <= 0) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "计划行 " + row.get("sku_code", String.class) + " 没有有效的计划量");
          }
          newLines.add(
              new NewLine(
                  row.get("sku_id", UUID.class),
                  row.get("sku_code", String.class),
                  row.get("sku_name", String.class),
                  quantity,
                  null,
                  row.get("expected_arrival_date", LocalDate.class),
                  orEmpty(row.get("priority", String.class)),
                  wanted,
                  sourcePlanId,
                  null,
                  rank++,
                  ""));
        }
      }
      case SUGGESTION -> {
        List<UUID> suggestionIds =
            requireIds(input.sourceLineIds(), "必须指定要转成申请行的补货建议");
        var rows =
            sql.fetch(
                "select id, batch_id, sku_id, sku_code, sku_name, decided_quantity,"
                    + " suggested_quantity, expected_arrival_date, advice, status, currency"
                    + " from replenishment_suggestion where tenant_id = ?::uuid"
                    + " and id = any(?::uuid[])",
                tenantId,
                suggestionIds.toArray(new UUID[0]));
        Map<UUID, Record> byId = new HashMap<>();
        for (Record row : rows) byId.put(row.get("id", UUID.class), row);
        for (UUID wanted : suggestionIds) {
          if (!byId.containsKey(wanted)) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND, "补货建议 " + wanted + " 不存在或不属于当前租户");
          }
        }
        if (targetWarehouseId == null || legalEntityId == null) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "从补货建议转申请单时必须给目标仓与法人（建议本身没有仓的概念）");
        }
        int rank = 0;
        for (UUID wanted : suggestionIds) {
          Record row = byId.get(wanted);
          // 处置后的数量优先：运营在上游改过的量才是真正要下单的量。
          BigDecimal quantity = row.get("decided_quantity", BigDecimal.class);
          if (quantity == null) quantity = row.get("suggested_quantity", BigDecimal.class);
          if (quantity == null || quantity.signum() <= 0) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "补货建议 " + row.get("sku_code", String.class)
                    + " 还没有可下单的数量（它还没被处置，或本身不是一条真建议）");
          }
          String suggestionCurrency = row.get("currency", String.class);
          if (currency == null && suggestionCurrency != null && !suggestionCurrency.isBlank()) {
            currency = suggestionCurrency.trim();
          }
          newLines.add(
              new NewLine(
                  row.get("sku_id", UUID.class),
                  row.get("sku_code", String.class),
                  row.get("sku_name", String.class),
                  quantity,
                  null,
                  row.get("expected_arrival_date", LocalDate.class),
                  "NORMAL",
                  null,
                  null,
                  wanted,
                  rank++,
                  ""));
        }
        sourceBatchId =
            sourceBatchId != null
                ? sourceBatchId
                : newLines.isEmpty()
                    ? null
                    : byId.get(suggestionIds.get(0)).get("batch_id", UUID.class);
      }
      case MANUAL -> {
        if (targetWarehouseId == null || legalEntityId == null) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "手工建单时必须给目标仓与法人");
        }
        List<PurchaseRequestLineInput> lines = input.lines();
        if (lines == null || lines.isEmpty()) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "手工建单时必须至少给一行");
        }
        int rank = 0;
        for (PurchaseRequestLineInput line : lines) {
          newLines.add(
              new NewLine(
                  line.skuId(),
                  line.skuCode(),
                  line.skuName(),
                  line.requestedQuantity(),
                  line.unitPrice(),
                  line.requestedArrivalDate(),
                  line.priority() == null ? "NORMAL" : line.priority().trim(),
                  null,
                  null,
                  null,
                  rank++,
                  ""));
        }
      }
    }

    // 目标仓与法人的展示快照：跨服务只读，读不到就留空 + 如实记下原因，不编造。
    String note = input.note() == null ? "" : input.note().trim();
    return persistRequest(
        sourceType,
        sourcePlanId,
        sourceBatchId,
        targetWarehouseId,
        warehouseCode,
        warehouseName,
        legalEntityId,
        entityCode,
        entityName,
        currency,
        newLines,
        note,
        actorId,
        bearer);
  }

  /**
   * 从「建议」建申请单，但**申请行由调用方直接给**。
   *
   * <p>用途：6.1.2.2 同款货源推荐的候选不是补货建议（行来自货源快照，不是
   * {@code replenishment_suggestion}），但同属「系统建议、运营确认」这一类 ——
   * 所以用同一个 {@code source_type='SUGGESTION'}，并把来源批次（{@code sourceBatchId}）
   * 与来源说明（{@code note}）一起带上，单据上答得出「这张申请是从哪来的」。
   *
   * <p>🔴 **写入口只有本模块**：purchase_request / purchase_request_line 的写入所有者是
   * 6.2.2.1，别的模块要建单必须走这里 —— 一张事实表只有一个写入所有者。
   */
  @Transactional
  public PurchaseRequestRow createFromSuggestion(
      PurchaseRequestSuggestionInput input, UUID actorId, String bearer) {
    UUID tenantId = currentTenant();
    if (input.targetWarehouseId() == null || input.legalEntityId() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "从建议建申请单时必须给目标仓与法人（建议本身没有仓的概念）");
    }
    List<PurchaseRequestSuggestionLineInput> lines = input.lines();
    if (lines == null || lines.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "从建议建申请单时必须至少给一行");
    }
    int rank = 0;
    List<NewLine> newLines = new ArrayList<>();
    for (PurchaseRequestSuggestionLineInput line : lines) {
      BigDecimal quantity = line.requestedQuantity();
      if (quantity == null || quantity.signum() <= 0) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "申请行 " + line.skuCode() + " 没有有效的申请数量");
      }
      newLines.add(
          new NewLine(
              line.skuId(),
              line.skuCode(),
              line.skuName(),
              quantity,
              line.unitPrice(),
              line.requestedArrivalDate(),
              line.priority() == null || line.priority().isBlank() ? "NORMAL" : line.priority().trim(),
              null,
              null,
              null,
              rank++,
              line.note() == null ? "" : line.note()));
    }
    PurchaseRequestRow row =
        persistRequest(
            PurchaseRequestSourceType.SUGGESTION,
            null,
            input.sourceBatchId(),
            input.targetWarehouseId(),
            "",
            "",
            input.legalEntityId(),
            "",
            "",
            input.currencyCode(),
            newLines,
            input.note() == null ? "" : input.note().trim(),
            actorId,
            bearer);
    // 供应商写回行上（申请行允许先不定供应商：走审批时供应商往往还没定，这里是「建议里已经定了」）。
    for (PurchaseRequestSuggestionLineInput line : lines) {
      if (line.supplierId() == null) continue;
      sql.execute(
          "update purchase_request_line set supplier_id = ?::uuid, supplier_code = ?::varchar,"
              + " supplier_name = ?::varchar, updated_at = ?::timestamptz"
              + " where tenant_id = ?::uuid and request_id = ?::uuid and sku_id = ?::uuid",
          line.supplierId(),
          line.supplierCode() == null ? "" : line.supplierCode(),
          line.supplierName() == null ? "" : line.supplierName(),
          OffsetDateTime.now(ZoneOffset.UTC),
          tenantId,
          row.id(),
          line.skuId());
    }
    return requestRow(row.id(), tenantId);
  }

  /**
   * 落库一张申请单（头 + 行 + 金额重算）。
   *
   * <p>把「建单」这段从 {@link #create} 里抽出来，是为了让**来源不同的建议**共用同一条落库路径：
   * 补货建议（6.2.1.2）、同款货源推荐（6.1.2.2）…… 落库口径必须只有一份，
   * 否则两处各写一遍插入语句，早晚会长出两套不一致的单据规则。
   */
  private PurchaseRequestRow persistRequest(
      PurchaseRequestSourceType sourceType,
      UUID sourcePlanId,
      UUID sourceBatchId,
      UUID targetWarehouseId,
      String warehouseCode,
      String warehouseName,
      UUID legalEntityId,
      String entityCode,
      String entityName,
      String currency,
      List<NewLine> newLines,
      String note,
      UUID actorId,
      String bearer) {
    UUID tenantId = currentTenant();
    if (targetWarehouseId != null && warehouseCode.isEmpty() && warehouseName.isEmpty()) {
      var resolved = resolveWarehouse(targetWarehouseId, bearer);
      if (resolved != null) {
        warehouseCode = resolved.code();
        warehouseName = resolved.name();
      } else {
        note = appendNote(note, "目标仓 " + targetWarehouseId + " 在仓库服务里查不到（可能已停用）");
      }
    }
    if (legalEntityId != null && entityCode.isEmpty() && entityName.isEmpty()) {
      var resolved = resolveLegalEntity(legalEntityId, bearer);
      if (resolved != null) {
        entityCode = resolved.code();
        entityName = resolved.name();
      } else {
        note = appendNote(note, "法人 " + legalEntityId + " 在仓库服务里查不到（可能已停用）");
      }
    }

    UUID id = UUID.randomUUID();
    String requestNo =
        "PR-"
            + LocalDate.now(ZoneOffset.UTC).toString().replace("-", "")
            + "-"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into purchase_request(id,tenant_id,request_no,source_type,source_plan_id,"
            + " source_batch_id,target_warehouse_id,target_warehouse_code,target_warehouse_name,"
            + " legal_entity_id,legal_entity_code,legal_entity_name,currency_code,estimated_amount,"
            + " status,approval_required,approval_decision,approval_source,approval_reason,"
            + " approval_sync_error,note,created_by,created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::varchar,?::varchar,?::uuid,?::uuid,?::uuid,?::varchar,"
            + " ?::varchar,?::uuid,?::varchar,?::varchar,?::varchar,?::numeric,?::varchar,false,"
            + " '','','','',?::varchar,?::uuid,?::timestamptz,?::timestamptz)",
        id,
        tenantId,
        requestNo,
        sourceType.name(),
        sourcePlanId,
        sourceBatchId,
        targetWarehouseId,
        warehouseCode,
        warehouseName,
        legalEntityId,
        entityCode,
        entityName,
        currency,
        null,
        PurchaseRequestStatus.DRAFT.name(),
        note,
        actorId,
        now,
        now);

    int rank = 0;
    for (NewLine line : newLines) {
      insertLine(tenantId, id, line, rank++);
    }
    recomputeAmount(tenantId, id);
    return requestRow(id, tenantId);
  }

  /** 申请行真分页。 */
  public PurchaseRequestLinePage pageLines(
      UUID requestId, String skuCode, String keyword, UUID supplierId, int page, int size) {
    UUID tenantId = currentTenant();
    requireRequest(requestId, tenantId);
    var where = new StringBuilder(" where tenant_id = ?::uuid and request_id = ?::uuid");
    var binds = new ArrayList<Object>();
    binds.add(tenantId);
    binds.add(requestId);
    if (skuCode != null && !skuCode.isBlank()) {
      where.append(" and sku_code ilike ?::varchar");
      binds.add("%" + skuCode.trim() + "%");
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (sku_code ilike ?::varchar or sku_name ilike ?::varchar)");
      binds.add("%" + keyword.trim() + "%");
      binds.add("%" + keyword.trim() + "%");
    }
    if (supplierId != null) {
      where.append(" and supplier_id = ?::uuid");
      binds.add(supplierId);
    }
    long total =
        sql.fetchOne(
                "select count(*) from purchase_request_line" + where, binds.toArray())
            .get(0, Long.class);
    int limit = pageSize(size);
    var ordered = new ArrayList<>(binds);
    ordered.add(limit);
    ordered.add(Math.max(page, 0) * limit);
    List<PurchaseRequestLineRow> items =
        sql.fetch(
                "select * from purchase_request_line" + where + " order by sort_rank, id limit ? offset ?",
                ordered.toArray())
            .map(this::toLineRow);
    return new PurchaseRequestLinePage(items, total, Math.max(page, 0), limit);
  }

  // ================================================================ 改草稿

  /** 更新草稿（目标仓、法人、币种、备注）。只有草稿可改。 */
  @Transactional
  public PurchaseRequestRow updateDraft(
      UUID id, PurchaseRequestUpdateInput input, UUID actorId, String bearer) {
    UUID tenantId = currentTenant();
    var request = requireRequest(id, tenantId);
    requireDraft(request, "修改");
    String entityCode = null;
    String entityName = null;
    if (input.legalEntityId() != null) {
      var resolved = resolveLegalEntity(input.legalEntityId(), bearer);
      entityCode = resolved == null ? "" : resolved.code();
      entityName = resolved == null ? "" : resolved.name();
    }
    sql.execute(
        "update purchase_request set legal_entity_id = coalesce(?::uuid, legal_entity_id),"
            + " legal_entity_code = coalesce(?::varchar, legal_entity_code),"
            + " legal_entity_name = coalesce(?::varchar, legal_entity_name),"
            + " currency_code = coalesce(?::varchar, currency_code),"
            + " note = coalesce(?::varchar, note), updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        input.legalEntityId(),
        entityCode,
        entityName,
        blankToNull(input.currencyCode()),
        input.note() == null ? null : input.note().trim(),
        OffsetDateTime.now(ZoneOffset.UTC),
        tenantId,
        id);
    recomputeAmount(tenantId, id);
    return requestRow(id, tenantId);
  }

  /** 给草稿加一行（手工）。 */
  @Transactional
  public PurchaseRequestLineRow addLine(
      UUID id, PurchaseRequestLineInput input, UUID actorId, String bearer) {
    UUID tenantId = currentTenant();
    var request = requireRequest(id, tenantId);
    requireDraft(request, "加行");
    int rank =
        sql.fetchOne(
                "select coalesce(max(sort_rank),-1)+1 from purchase_request_line"
                    + " where tenant_id = ?::uuid and request_id = ?::uuid",
                tenantId,
                id)
            .get(0, Integer.class);
    BigDecimal quantity = input.requestedQuantity().setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    BigDecimal unitPrice =
        input.unitPrice() == null
            ? null
            : input.unitPrice().setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    UUID lineId = UUID.randomUUID();
    try {
      sql.execute(
          "insert into purchase_request_line(id,tenant_id,request_id,sku_id,sku_code,sku_name,"
              + " requested_quantity,unit_price,line_amount,requested_arrival_date,priority,note,"
              + " sort_rank,created_at,updated_at)"
              + " values (?::uuid,?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::numeric,?::numeric,"
              + " ?::numeric,?::date,?::varchar,?::varchar,?,?::timestamptz,?::timestamptz)",
          lineId,
          tenantId,
          id,
          input.skuId(),
          input.skuCode().trim(),
          input.skuName() == null ? "" : input.skuName().trim(),
          quantity,
          unitPrice,
          unitPrice == null ? null : unitPrice.multiply(quantity).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP),
          input.requestedArrivalDate(),
          input.priority() == null ? "NORMAL" : input.priority().trim(),
          input.note() == null ? "" : input.note().trim(),
          rank,
          OffsetDateTime.now(ZoneOffset.UTC),
          OffsetDateTime.now(ZoneOffset.UTC));
    } catch (org.jooq.exception.DataAccessException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "这张申请单里已经有同一个 SKU（" + input.skuCode().trim() + "）了");
    }
    recomputeAmount(tenantId, id);
    return lineRow(tenantId, lineId);
  }

  /**
   * 调整一行的数量 / 交期 / 单价 / 优先级。理由必填。
   *
   * <p>🔴 每次改动都落一条 append-only 的调整留痕（{@code purchase_request_adjustment}）：
   * 「当时申请的是多少、后来改成了多少、为什么改」是审批留痕的一部分。
   */
  @Transactional
  public PurchaseRequestLineRow adjustLine(
      UUID id, UUID lineId, PurchaseRequestLineAdjustInput input, UUID actorId) {
    UUID tenantId = currentTenant();
    var request = requireRequest(id, tenantId);
    requireDraft(request, "调整申请行");
    var line = requireLine(tenantId, id, lineId);
    List<String[]> changes = new ArrayList<>();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    if (input.requestedQuantity() != null) {
      BigDecimal before = line.get("requested_quantity", BigDecimal.class);
      BigDecimal after = input.requestedQuantity().setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
      if (before == null || before.compareTo(after) != 0) {
        changes.add(new String[] {"requested_quantity", plain(before), plain(after)});
        sql.execute(
            "update purchase_request_line set requested_quantity = ?::numeric, updated_at = ?::timestamptz"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            after, now, tenantId, lineId);
      }
    }
    if (input.requestedArrivalDate() != null) {
      LocalDate before = line.get("requested_arrival_date", LocalDate.class);
      if (!input.requestedArrivalDate().equals(before)) {
        changes.add(
            new String[] {
              "requested_arrival_date", before == null ? "" : before.toString(),
              input.requestedArrivalDate().toString()
            });
        sql.execute(
            "update purchase_request_line set requested_arrival_date = ?::date, updated_at = ?::timestamptz"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            input.requestedArrivalDate(), now, tenantId, lineId);
      }
    }
    if (input.unitPrice() != null) {
      BigDecimal before = line.get("unit_price", BigDecimal.class);
      BigDecimal after = input.unitPrice().setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
      if (before == null || before.compareTo(after) != 0) {
        changes.add(new String[] {"unit_price", plain(before), plain(after)});
        BigDecimal quantity = line.get("requested_quantity", BigDecimal.class);
        sql.execute(
            "update purchase_request_line set unit_price = ?::numeric, line_amount = ?::numeric,"
                + " updated_at = ?::timestamptz where tenant_id = ?::uuid and id = ?::uuid",
            after,
            quantity == null ? null : after.multiply(quantity).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP),
            now,
            tenantId,
            lineId);
      }
    }
    if (input.priority() != null) {
      String before = line.get("priority", String.class);
      if (!input.priority().trim().equals(before)) {
        changes.add(new String[] {"priority", before, input.priority().trim()});
        sql.execute(
            "update purchase_request_line set priority = ?::varchar, updated_at = ?::timestamptz"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            input.priority().trim(), now, tenantId, lineId);
      }
    }
    if (input.note() != null) {
      String before = line.get("note", String.class);
      String after = input.note().trim();
      if (!after.equals(before)) {
        changes.add(new String[] {"note", before, after});
        sql.execute(
            "update purchase_request_line set note = ?::varchar, updated_at = ?::timestamptz"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            after, now, tenantId, lineId);
      }
    }
    for (String[] change : changes) {
      insertAdjustment(tenantId, lineId, change[0], change[1], change[2], input.reason(), actorId, now);
    }
    recomputeAmount(tenantId, id);
    return lineRow(tenantId, lineId);
  }

  /**
   * 给一行选供应商。
   *
   * <p>🔴 供应商必须**真的存在**：本模块经 catalog 的授权只读接口确认它，
   * 绝不把调用方传来的一个 id 原样写进单据 —— 那是「假供应商」。
   */
  @Transactional
  public PurchaseRequestLineRow selectSupplier(
      UUID id, UUID lineId, PurchaseRequestSupplierInput input, UUID actorId, String bearer) {
    UUID tenantId = currentTenant();
    var request = requireRequest(id, tenantId);
    requireDraft(request, "选供应商");
    var line = requireLine(tenantId, id, lineId);
    var supplier = supplierDirectory.require(input.supplierId(), bearer);
    String before = line.get("supplier_id", UUID.class) == null
        ? ""
        : line.get("supplier_id", UUID.class).toString();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update purchase_request_line set supplier_id = ?::uuid, supplier_code = ?::varchar,"
            + " supplier_name = ?::varchar, updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        supplier.roleId(),
        supplier.partyCode(),
        supplier.partyName(),
        now,
        tenantId,
        lineId);
    insertAdjustment(
        tenantId,
        lineId,
        "supplier_id",
        before,
        supplier.roleId() + "（" + supplier.partyName() + "）",
        input.reason() == null || input.reason().isBlank() ? "选择供应商" : input.reason().trim(),
        actorId,
        now);
    return lineRow(tenantId, lineId);
  }

  // ================================================================ 审批

  /**
   * 提交审批（草稿 → 待审 / 已批）。
   *
   * <p>🔴 三条不可让步的分支：
   *
   * <ul>
   *   <li>免审（{@code decision=EXEMPT}）或本租户未配规则（{@code source=UNCONTROLLED}）
   *       ⇒ 直通进已批。</li>
   *   <li>需审 ⇒ 提交审批实例（幂等：重复提交不会造出第二条待办），停在<b>待审</b>。</li>
   *   <li>审批服务不可达 ⇒ <b>停在待审</b>并把失败原因写进 {@code approval_sync_error}，
   *       <b>绝不</b>自动跳到已批 —— 那等于把一张没审过的采购申请当成可以下单的决策。</li>
   * </ul>
   */
  @Transactional
  public PurchaseRequestApprovalResult submit(UUID id, UUID actorId, String bearer) {
    UUID tenantId = currentTenant();
    var request = requireRequest(id, tenantId);
    requireDraft(request, "提交审批");
    int lineCount =
        sql.fetchOne(
                "select count(*) from purchase_request_line where tenant_id = ?::uuid and request_id = ?::uuid",
                tenantId,
                id)
            .get(0, Integer.class);
    if (lineCount == 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有申请行的申请单提交不了审批");
    }
    return submitForApproval(tenantId, id, request, actorId, bearer);
  }

  /**
   * 提交审批的共同实现（首次提交与刷新重投共用）。
   *
   * <p>🔴 审批服务不可达时**先记下痕迹再返回**，**不抛异常** ——
   * 抛异常会把痕迹一起回滚掉（整条链路在一个事务里），那样这张单就变成
   * 「什么都没发生过」，而不是「停在待审 + 同步失败」。
   */
  private PurchaseRequestApprovalResult submitForApproval(
      UUID tenantId, UUID id, Record request, UUID actorId, String bearer) {
    String currency = request.get("currency_code", String.class);
    BigDecimal amount = request.get("estimated_amount", BigDecimal.class);
    // 🔴 **归一**：identity 的审批提交要求「金额与币种同时有或同时没有」——
    // 只有金额没有币种，阈值分支匹配没有唯一答案。所以这一次**不拿**那个没有口径的
    // 数字去命中阈值（当作「这次没有金额」），而不是把金额丢掉或拿 0 去顶。
    String approvalCurrency = currency == null ? null : currency;
    BigDecimal approvalAmount = currency == null ? null : amount;
    PurchaseRequestApprovalPort.Evaluation evaluation;
    try {
      evaluation =
          approvals.evaluate(
              new PurchaseRequestApprovalPort.EvaluationInput(
                  PurchaseRequestApprovalPort.DOCUMENT_TYPE, approvalCurrency, approvalAmount),
              bearer);
    } catch (PurchaseRequestApprovalPort.ApprovalUnavailableException e) {
      // 审批服务不可达 ⇒ 停在待审 + 如实记下失败原因。**不**跳到已批。
      markSyncError(tenantId, id, e.getMessage(), actorId);
      return failed(requestRow(id, tenantId), HttpStatus.SERVICE_UNAVAILABLE.value(), e.getMessage());
    } catch (ResponseStatusException e) {
      markSyncError(tenantId, id, e.getReason(), actorId);
      return failed(
          requestRow(id, tenantId),
          e.getStatusCode().value(),
          e.getReason() == null ? "审批服务不可达" : e.getReason());
    }

    if (!evaluation.requiresApproval()) {
      // 免审直通（EXEMPT）或本租户未配规则（UNCONTROLLED ⇒ decision=EXEMPT）。
      sql.execute(
          "update purchase_request set status = ?::varchar, approval_required = false,"
              + " approval_decision = ?::varchar, approval_source = ?::varchar,"
              + " approval_rule_id = ?::uuid, approval_rule_name = ?::varchar,"
              + " approval_flow_id = ?::uuid, approval_flow_name = ?::varchar,"
              + " approval_reason = ?::varchar, approval_sync_error = '',"
              + " approval_submitted_at = ?::timestamptz, updated_at = ?::timestamptz"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          PurchaseRequestStatus.APPROVED.name(),
          orEmpty(evaluation.decision()),
          orEmpty(evaluation.source()),
          evaluation.matchedRuleId(),
          orEmpty(evaluation.matchedRuleName()),
          evaluation.targetFlowId(),
          orEmpty(evaluation.targetFlowName()),
          orEmpty(evaluation.reason()),
          OffsetDateTime.now(ZoneOffset.UTC),
          OffsetDateTime.now(ZoneOffset.UTC),
          tenantId,
          id);
      return ok(requestRow(id, tenantId));
    }

    if (evaluation.targetFlowId() == null) {
      markSyncError(
          tenantId, id, "审批规则要求送审，但没有给出可用的审批流（targetFlowId 为空）", actorId);
      return failed(
          requestRow(id, tenantId),
          HttpStatus.CONFLICT.value(),
          "审批规则要求送审，但没有给出可用的审批流，请检查租户的审批流配置");
    }

    String requestNo = request.get("request_no", String.class);
    try {
      var submitted =
          approvals.submit(
              new PurchaseRequestApprovalPort.SubmitInput(
                  evaluation.targetFlowId(),
                  PurchaseRequestApprovalPort.SOURCE_SYSTEM,
                  id.toString(),
                  "采购申请 " + requestNo,
                  approvalCurrency,
                  approvalAmount),
              bearer);
      sql.execute(
          "update purchase_request set status = ?::varchar, approval_required = true,"
              + " approval_decision = ?::varchar, approval_source = ?::varchar,"
              + " approval_rule_id = ?::uuid, approval_rule_name = ?::varchar,"
              + " approval_flow_id = ?::uuid, approval_flow_name = ?::varchar,"
              + " approval_reason = ?::varchar, approval_instance_id = ?::uuid,"
              + " approval_instance_no = ?::varchar, approval_submitted_at = ?::timestamptz,"
              + " approval_sync_error = '', updated_at = ?::timestamptz"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          PurchaseRequestStatus.PENDING_APPROVAL.name(),
          orEmpty(evaluation.decision()),
          orEmpty(evaluation.source()),
          evaluation.matchedRuleId(),
          orEmpty(evaluation.matchedRuleName()),
          evaluation.targetFlowId(),
          orEmpty(evaluation.targetFlowName()),
          orEmpty(evaluation.reason()),
          submitted.id(),
          orEmpty(submitted.instanceNo()),
          OffsetDateTime.now(ZoneOffset.UTC),
          OffsetDateTime.now(ZoneOffset.UTC),
          tenantId,
          id);
    } catch (PurchaseRequestApprovalPort.ApprovalConflictException e) {
      // 幂等命中：同一来源单据已有一条在办实例 ⇒ 回读它，**不**重投。
      var live = approvals.findLive(id.toString(), bearer);
      if (live == null || live.id() == null) {
        markSyncError(
            tenantId,
            id,
            "审批实例已存在但实例号暂时读不回来（幂等命中）：" + e.getMessage(),
            actorId);
        return failed(
            requestRow(id, tenantId),
            HttpStatus.CONFLICT.value(),
            "审批实例已存在但实例号暂时读不回来，请稍后刷新");
      }
      sql.execute(
          "update purchase_request set status = ?::varchar, approval_required = true,"
              + " approval_decision = ?::varchar, approval_source = ?::varchar,"
              + " approval_rule_id = ?::uuid, approval_rule_name = ?::varchar,"
              + " approval_flow_id = ?::uuid, approval_flow_name = ?::varchar,"
              + " approval_reason = ?::varchar, approval_instance_id = ?::uuid,"
              + " approval_instance_no = ?::varchar, approval_submitted_at = ?::timestamptz,"
              + " approval_sync_error = '', updated_at = ?::timestamptz"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          PurchaseRequestStatus.PENDING_APPROVAL.name(),
          orEmpty(evaluation.decision()),
          orEmpty(evaluation.source()),
          evaluation.matchedRuleId(),
          orEmpty(evaluation.matchedRuleName()),
          evaluation.targetFlowId(),
          orEmpty(evaluation.targetFlowName()),
          orEmpty(evaluation.reason()),
          live.id(),
          orEmpty(live.instanceNo()),
          live.submittedAt() == null
              ? OffsetDateTime.now(ZoneOffset.UTC)
              : live.submittedAt().atOffset(ZoneOffset.UTC),
          OffsetDateTime.now(ZoneOffset.UTC),
          tenantId,
          id);
    } catch (PurchaseRequestApprovalPort.ApprovalUnavailableException e) {
      markSyncError(tenantId, id, e.getMessage(), actorId);
      return failed(requestRow(id, tenantId), HttpStatus.SERVICE_UNAVAILABLE.value(), e.getMessage());
    }
    return ok(requestRow(id, tenantId));
  }

  /**
   * 拉取审批结论（拉取式同步：本仓没有事件总线）。
   *
   * <p>实例还没建出来（上次提交没成功）⇒ 重新走一次求值 + 提交。
   */
  @Transactional
  public PurchaseRequestApprovalResult refreshApproval(UUID id, UUID actorId, String bearer) {
    UUID tenantId = currentTenant();
    var request = requireRequest(id, tenantId);
    String status = request.get("status", String.class);
    if (PurchaseRequestStatus.DRAFT.name().equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "这张申请单还在草稿，先提交审批再刷新结论");
    }
    UUID instanceId = request.get("approval_instance_id", UUID.class);
    if (instanceId == null) {
      if (PurchaseRequestStatus.PENDING_APPROVAL.name().equals(status)) {
        return submitForApproval(tenantId, id, request, actorId, bearer);
      }
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "这张申请单没有审批实例（免审直通或已驳回），没有结论可拉取");
    }
    PurchaseRequestApprovalPort.InstanceStatus instance;
    try {
      instance = approvals.status(instanceId, bearer);
    } catch (PurchaseRequestApprovalPort.ApprovalUnavailableException e) {
      markSyncError(tenantId, id, e.getMessage(), actorId);
      return failed(requestRow(id, tenantId), HttpStatus.SERVICE_UNAVAILABLE.value(), e.getMessage());
    } catch (ResponseStatusException e) {
      markSyncError(tenantId, id, e.getReason(), actorId);
      return failed(
          requestRow(id, tenantId),
          e.getStatusCode().value(),
          e.getReason() == null ? "审批服务不可达" : e.getReason());
    }
    if (instance == null || instance.status() == null) {
      markSyncError(tenantId, id, "审批记录查询没有返回实例状态", actorId);
      return failed(
          requestRow(id, tenantId),
          HttpStatus.SERVICE_UNAVAILABLE.value(),
          "审批记录查询没有返回实例状态");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    if ("APPROVED".equals(instance.status())) {
      sql.execute(
          "update purchase_request set status = ?::varchar, approval_decided_at = ?::timestamptz,"
              + " approval_decided_by = ?::uuid, approval_sync_error = '', updated_at = ?::timestamptz"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          PurchaseRequestStatus.APPROVED.name(), now, actorId, now, tenantId, id);
    } else if ("REJECTED".equals(instance.status())) {
      sql.execute(
          "update purchase_request set status = ?::varchar, approval_decided_at = ?::timestamptz,"
              + " approval_decided_by = ?::uuid, approval_sync_error = '', updated_at = ?::timestamptz"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          PurchaseRequestStatus.REJECTED.name(), now, actorId, now, tenantId, id);
    } else {
      // 还在审：状态不变，只把「同步过一次」这件事记下来（updated_at 变了）。
      sql.execute(
          "update purchase_request set approval_sync_error = '', updated_at = ?::timestamptz"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          now, tenantId, id);
    }
    return ok(requestRow(id, tenantId));
  }

  /** 成功结果。 */
  private static PurchaseRequestApprovalResult ok(PurchaseRequestRow request) {
    return new PurchaseRequestApprovalResult(request, false, 200, "");
  }

  /** 失败结果（痕迹已经落库，由控制器在事务提交后翻成 HTTP 状态码）。 */
  private static PurchaseRequestApprovalResult failed(
      PurchaseRequestRow request, int errorStatus, String message) {
    return new PurchaseRequestApprovalResult(request, true, errorStatus, message);
  }

  /** 审批同步失败：停在待审 + 如实记下原因。 */
  private void markSyncError(UUID tenantId, UUID id, String reason, UUID actorId) {
    sql.execute(
        "update purchase_request set status = ?::varchar, approval_required = true,"
            + " approval_sync_error = ?::varchar, updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid and status = ?::varchar",
        PurchaseRequestStatus.PENDING_APPROVAL.name(),
        reason == null ? "审批服务不可达（原因未知）" : reason,
        OffsetDateTime.now(ZoneOffset.UTC),
        tenantId,
        id,
        PurchaseRequestStatus.DRAFT.name());
  }

  // ================================================================ 转单

  /**
   * 转单（已批 → 已转单）：把「转成采购单时的参数」记成事实。
   *
   * <p>🔴 6.3.1.2 采购单<b>未交付</b> ⇒ 本方法**不**建采购单，只写
   * {@code purchase_request_conversion}：供应商 + 数量 + 交期 + 目标仓 + 源计划行。
   *
   * <p>转单前**每一行都必须有供应商与交期** —— 「确认供应商与数量后转采购单」，
   * 缺一个就不许转（那不是确认，是猜）。
   */
  @Transactional
  public PurchaseRequestDetail convert(
      UUID id, PurchaseRequestConvertInput input, UUID actorId, String bearer) {
    UUID tenantId = currentTenant();
    var request = requireRequest(id, tenantId);
    if (!PurchaseRequestStatus.APPROVED.name().equals(request.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "只有已批的采购申请单能转单（当前状态："
              + statusLabel(request.get("status", String.class))
              + "）—— 采购单是对供应商的正式承诺，没审过的申请不能直接转出去");
    }
    var lines =
        sql.fetch(
                "select * from purchase_request_line where tenant_id = ?::uuid and request_id = ?::uuid"
                    + " order by sort_rank, id",
                tenantId,
                id)
            .map(this::toLineRow);
    if (lines.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有申请行的申请单转不了单");
    }
    Map<UUID, PurchaseRequestConvertLineInput> overrides = new HashMap<>();
    if (input != null && input.lines() != null) {
      for (var line : input.lines()) overrides.put(line.lineId(), line);
    }

    List<String> missing = new ArrayList<>();
    for (var line : lines) {
      var override = overrides.get(line.id());
      UUID supplierId = override != null && override.supplierId() != null
          ? override.supplierId()
          : line.supplierId();
      LocalDate arrival = override != null && override.arrivalDate() != null
          ? override.arrivalDate()
          : line.requestedArrivalDate();
      if (supplierId == null) missing.add(line.skuCode() + "（还没选供应商）");
      if (arrival == null) missing.add(line.skuCode() + "（还没有交期）");
    }
    if (!missing.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "转单前必须确认每一行的供应商与交期，还差：" + String.join("、", missing));
    }

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    for (var line : lines) {
      var override = overrides.get(line.id());
      UUID supplierId = override != null && override.supplierId() != null
          ? override.supplierId()
          : line.supplierId();
      LocalDate arrival = override != null && override.arrivalDate() != null
          ? override.arrivalDate()
          : line.requestedArrivalDate();
      BigDecimal quantity =
          override != null && override.quantity() != null
              ? override.quantity().setScale(QUANTITY_SCALE, RoundingMode.HALF_UP)
              : line.requestedQuantity();
      var supplier = supplierDirectory.require(supplierId, bearer);
      sql.execute(
          "insert into purchase_request_conversion(id,tenant_id,request_id,request_line_id,"
              + " supplier_id,supplier_code,supplier_name,converted_quantity,converted_arrival_date,"
              + " target_warehouse_id,target_warehouse_code,target_warehouse_name,"
              + " source_plan_line_id,source_plan_id,source_suggestion_id,note,converted_by,"
              + " converted_at,created_at)"
              + " values (?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::numeric,"
              + " ?::date,?::uuid,?::varchar,?::varchar,?::uuid,?::uuid,?::uuid,?::varchar,?::uuid,"
              + " ?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          tenantId,
          id,
          line.id(),
          supplier.roleId(),
          supplier.partyCode(),
          supplier.partyName(),
          quantity,
          arrival,
          request.get("target_warehouse_id", UUID.class),
          orEmpty(request.get("target_warehouse_code", String.class)),
          orEmpty(request.get("target_warehouse_name", String.class)),
          line.sourcePlanLineId(),
          line.sourcePlanId(),
          line.sourceSuggestionId(),
          override != null && override.note() != null ? override.note().trim() : "",
          actorId,
          now,
          now);
    }
    sql.execute(
        "update purchase_request set status = ?::varchar, converted_at = ?::timestamptz,"
            + " converted_by = ?::uuid, updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        PurchaseRequestStatus.CONVERTED.name(), now, actorId, now, tenantId, id);
    return requestDetail(id, false, bearer);
  }

  // ================================================================ 内部

  /** 申请单头部的取数列（含行数 / 总量 / 供应商数的聚合）。 */
  private static final String REQUEST_COLUMNS =
      """
      select r.id, r.request_no, r.source_type, r.source_plan_id, r.source_batch_id,
             r.target_warehouse_id, r.target_warehouse_code, r.target_warehouse_name,
             r.legal_entity_id, r.legal_entity_code, r.legal_entity_name,
             r.currency_code, r.estimated_amount, r.status,
             r.approval_required, r.approval_decision, r.approval_source,
             r.approval_rule_id, r.approval_rule_name, r.approval_flow_id, r.approval_flow_name,
             r.approval_reason, r.approval_instance_id, r.approval_instance_no,
             r.approval_submitted_at, r.approval_decided_at, r.approval_decided_by,
             r.approval_sync_error, r.converted_at, r.converted_by,
             r.note, r.created_by, r.created_at, r.updated_at,
             (select count(*) from purchase_request_line l
               where l.tenant_id = r.tenant_id and l.request_id = r.id) as line_count,
             (select coalesce(sum(l.requested_quantity),0) from purchase_request_line l
               where l.tenant_id = r.tenant_id and l.request_id = r.id) as total_quantity,
             (select count(distinct l.supplier_id) from purchase_request_line l
               where l.tenant_id = r.tenant_id and l.request_id = r.id
                 and l.supplier_id is not null) as supplier_count
        from purchase_request r
      """;

  private PurchaseRequestRow requestRow(UUID id, UUID tenantId) {
    var row =
        sql.fetchOne(REQUEST_COLUMNS + " where r.tenant_id = ?::uuid and r.id = ?::uuid", tenantId, id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "采购申请单不存在或不属于当前租户");
    }
    return toRow(row);
  }

  private Record requireRequest(UUID id, UUID tenantId) {
    var row =
        sql.fetchOne(
            "select * from purchase_request where tenant_id = ?::uuid and id = ?::uuid", tenantId, id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "采购申请单不存在或不属于当前租户");
    }
    return row;
  }

  private Record requireLine(UUID tenantId, UUID requestId, UUID lineId) {
    var row =
        sql.fetchOne(
            "select * from purchase_request_line where tenant_id = ?::uuid and request_id = ?::uuid"
                + " and id = ?::uuid",
            tenantId,
            requestId,
            lineId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "申请行不存在或不属于这张申请单");
    }
    return row;
  }

  private PurchaseRequestLineRow lineRow(UUID tenantId, UUID lineId) {
    var row =
        sql.fetchOne(
            "select * from purchase_request_line where tenant_id = ?::uuid and id = ?::uuid",
            tenantId,
            lineId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "申请行不存在");
    }
    return toLineRow(row);
  }

  private void requireDraft(Record request, String action) {
    String status = request.get("status", String.class);
    if (!PurchaseRequestStatus.DRAFT.name().equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "这张申请单已提交（当前状态："
              + statusLabel(status)
              + "），不能再"
              + action
              + " —— 审批留痕要能指回「当时送审的那一版」，改了就对不上");
    }
  }

  /** 重新汇总申请单的预估金额。有一行没有单价 ⇒ 金额**算不出来**（NULL，不是 0）。 */
  private void recomputeAmount(UUID tenantId, UUID requestId) {
    var row =
        sql.fetchOne(
            "select count(*) as total, count(unit_price) as priced,"
                + " coalesce(sum(line_amount),0) as amount"
                + " from purchase_request_line where tenant_id = ?::uuid and request_id = ?::uuid",
            tenantId,
            requestId);
    int total = row.get("total", Integer.class);
    int priced = row.get("priced", Integer.class);
    BigDecimal amount = row.get("amount", BigDecimal.class);
    BigDecimal value = (total > 0 && priced == total) ? amount : null;
    sql.execute(
        "update purchase_request set estimated_amount = ?::numeric, updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        value, OffsetDateTime.now(ZoneOffset.UTC), tenantId, requestId);
  }

  private void insertLine(UUID tenantId, UUID requestId, NewLine line, int rank) {
    BigDecimal quantity = line.quantity().setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    BigDecimal unitPrice =
        line.unitPrice() == null
            ? null
            : line.unitPrice().setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    sql.execute(
        "insert into purchase_request_line(id,tenant_id,request_id,sku_id,sku_code,sku_name,"
            + " source_plan_line_id,source_plan_id,source_suggestion_id,requested_quantity,"
            + " unit_price,line_amount,requested_arrival_date,priority,note,sort_rank,"
            + " created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::uuid,?::uuid,?::uuid,"
            + " ?::numeric,?::numeric,?::numeric,?::date,?::varchar,?::varchar,?,?::timestamptz,"
            + " ?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        requestId,
        line.skuId(),
        line.skuCode(),
        line.skuName() == null ? "" : line.skuName(),
        line.sourcePlanLineId(),
        line.sourcePlanId(),
        line.sourceSuggestionId(),
        quantity,
        unitPrice,
        unitPrice == null
            ? null
            : unitPrice.multiply(quantity).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP),
        line.arrivalDate(),
        line.priority() == null || line.priority().isBlank() ? "NORMAL" : line.priority(),
        line.note() == null ? "" : line.note(),
        rank,
        OffsetDateTime.now(ZoneOffset.UTC),
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  private void insertAdjustment(
      UUID tenantId,
      UUID lineId,
      String field,
      String before,
      String after,
      String reason,
      UUID actorId,
      OffsetDateTime at) {
    sql.execute(
        "insert into purchase_request_adjustment(id,tenant_id,request_line_id,field,before_value,"
            + " after_value,reason,adjusted_by,adjusted_at,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::varchar,?::uuid,"
            + " ?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        lineId,
        field,
        before,
        after,
        reason,
        actorId,
        at,
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  private PurchasePlanWarehousePort.WarehouseView resolveWarehouse(UUID id, String bearer) {
    try {
      for (var view : warehouseReference.warehouses(bearer)) {
        if (view.id().equals(id)) return view;
      }
    } catch (ResponseStatusException e) {
      // 读不到就留空 + 如实记下原因，不编造。
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

  private PurchaseRequestRow toRow(Record r) {
    String status = r.get("status", String.class);
    return new PurchaseRequestRow(
        r.get("id", UUID.class),
        r.get("request_no", String.class),
        r.get("source_type", String.class),
        sourceTypeLabel(r.get("source_type", String.class)),
        r.get("source_plan_id", UUID.class),
        r.get("source_batch_id", UUID.class),
        r.get("target_warehouse_id", UUID.class),
        orEmpty(r.get("target_warehouse_code", String.class)),
        orEmpty(r.get("target_warehouse_name", String.class)),
        r.get("legal_entity_id", UUID.class),
        orEmpty(r.get("legal_entity_code", String.class)),
        orEmpty(r.get("legal_entity_name", String.class)),
        r.get("currency_code", String.class),
        r.get("estimated_amount", BigDecimal.class),
        status,
        statusLabel(status),
        Boolean.TRUE.equals(r.get("approval_required", Boolean.class)),
        orEmpty(r.get("approval_decision", String.class)),
        orEmpty(r.get("approval_source", String.class)),
        r.get("approval_rule_id", UUID.class),
        orEmpty(r.get("approval_rule_name", String.class)),
        r.get("approval_flow_id", UUID.class),
        orEmpty(r.get("approval_flow_name", String.class)),
        orEmpty(r.get("approval_reason", String.class)),
        r.get("approval_instance_id", UUID.class),
        orEmpty(r.get("approval_instance_no", String.class)),
        r.get("approval_submitted_at", OffsetDateTime.class),
        r.get("approval_decided_at", OffsetDateTime.class),
        r.get("approval_decided_by", UUID.class),
        orEmpty(r.get("approval_sync_error", String.class)),
        r.get("converted_at", OffsetDateTime.class),
        r.get("converted_by", UUID.class),
        r.get("line_count", Integer.class) == null ? 0 : r.get("line_count", Integer.class),
        r.get("total_quantity", BigDecimal.class),
        r.get("supplier_count", Long.class) == null ? 0L : r.get("supplier_count", Long.class),
        orEmpty(r.get("note", String.class)),
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private PurchaseRequestLineRow toLineRow(Record r) {
    String priority = orEmpty(r.get("priority", String.class));
    return new PurchaseRequestLineRow(
        r.get("id", UUID.class),
        r.get("request_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        orEmpty(r.get("sku_name", String.class)),
        r.get("source_plan_line_id", UUID.class),
        r.get("source_plan_id", UUID.class),
        r.get("source_suggestion_id", UUID.class),
        r.get("requested_quantity", BigDecimal.class),
        r.get("unit_price", BigDecimal.class),
        r.get("line_amount", BigDecimal.class),
        r.get("supplier_id", UUID.class),
        orEmpty(r.get("supplier_code", String.class)),
        orEmpty(r.get("supplier_name", String.class)),
        r.get("requested_arrival_date", LocalDate.class),
        priority,
        priorityLabel(priority),
        orEmpty(r.get("note", String.class)),
        r.get("sort_rank", Integer.class) == null ? 0 : r.get("sort_rank", Integer.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private PurchaseRequestAdjustmentRow toAdjustmentRow(Record r) {
    return new PurchaseRequestAdjustmentRow(
        r.get("id", UUID.class),
        r.get("request_line_id", UUID.class),
        orEmpty(r.get("sku_code", String.class)),
        r.get("field", String.class),
        r.get("before_value", String.class),
        r.get("after_value", String.class),
        r.get("reason", String.class),
        r.get("adjusted_by", UUID.class),
        r.get("adjusted_at", OffsetDateTime.class));
  }

  private PurchaseRequestConversionRow toConversionRow(Record r) {
    return new PurchaseRequestConversionRow(
        r.get("id", UUID.class),
        r.get("request_id", UUID.class),
        r.get("request_line_id", UUID.class),
        orEmpty(r.get("sku_code", String.class)),
        r.get("supplier_id", UUID.class),
        orEmpty(r.get("supplier_code", String.class)),
        orEmpty(r.get("supplier_name", String.class)),
        r.get("converted_quantity", BigDecimal.class),
        r.get("converted_arrival_date", LocalDate.class),
        r.get("target_warehouse_id", UUID.class),
        orEmpty(r.get("target_warehouse_code", String.class)),
        orEmpty(r.get("target_warehouse_name", String.class)),
        r.get("source_plan_line_id", UUID.class),
        r.get("source_plan_id", UUID.class),
        r.get("source_suggestion_id", UUID.class),
        orEmpty(r.get("note", String.class)),
        r.get("converted_by", UUID.class),
        r.get("converted_at", OffsetDateTime.class));
  }

  // ================================================================ 小工具

  /** 建单时用来暂存一行（还没落库）。 */
  private record NewLine(
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal quantity,
      BigDecimal unitPrice,
      LocalDate arrivalDate,
      String priority,
      UUID sourcePlanLineId,
      UUID sourcePlanId,
      UUID sourceSuggestionId,
      int rank,
      String note) {}

  private static List<UUID> requireIds(List<UUID> ids, String message) {
    if (ids == null || ids.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    return ids;
  }

  private static String statusLabel(String status) {
    if (status == null) return "";
    try {
      return PurchaseRequestStatus.valueOf(status).label();
    } catch (IllegalArgumentException e) {
      return status;
    }
  }

  private static String sourceTypeLabel(String sourceType) {
    if (sourceType == null) return "";
    try {
      return PurchaseRequestSourceType.valueOf(sourceType).label();
    } catch (IllegalArgumentException e) {
      return sourceType;
    }
  }

  private static String priorityLabel(String priority) {
    if (priority == null) return "";
    try {
      return PurchaseRequestPriority.valueOf(priority).label();
    } catch (IllegalArgumentException e) {
      return priority;
    }
  }

  private static String plain(BigDecimal value) {
    return value == null ? "" : value.stripTrailingZeros().toPlainString();
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String appendNote(String base, String extra) {
    if (extra == null || extra.isBlank()) return base;
    return base.isBlank() ? extra : base + "；" + extra;
  }

  private static int pageSize(int size) {
    if (size <= 0) return 50;
    return Math.min(size, MAX_PAGE_SIZE);
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
