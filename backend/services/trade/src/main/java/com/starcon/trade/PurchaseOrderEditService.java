package com.starcon.trade;

import static com.starcon.trade.PurchaseOrderEditModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.3.1.2 采购单创建与编辑（卷 06 采购与供应 / L3 6.3.1 采购单的第二格）。
 *
 * <p>蓝图 6.3.1.2 原文：「创建/编辑采购单：选择供应商、添加 SKU 行（带出报价与库存快照）、
 * 填写数量/采购单位/单价/币种、约定交期与发货地址、备注与附件。支持草稿与提交。」
 * 输入「表单数据；{@code expected_version}；SKU 与供应商报价快照」，
 * 输出「采购单（草稿/已提交）；金额与税额计算结果」。
 *
 * <p><b>本模块与 6.3.1.1（锚点）的关系</b>：采购单这个聚合（头 + 行 + 留痕）归 6.3.1.1 所有
 * （V254 的三张表）。本模块**不改那三张表的结构**，只做三件事：
 * 建单、编辑（**只有草稿能改**）、提交（草稿 → 待审批，冻结快照），
 * 另加四张**本模块自己**的表承载「提交时冻结的快照 / 发货地址 / 附件 / 草稿来源」（V258）。
 *
 * <p><b>四个最容易做错的口径，本类显式钉死：</b>
 *
 * <ol>
 *   <li><b>汇率快照在提交那一刻冻结</b>（蓝图加粗句）。草稿期间一律是 <b>NULL</b>，
 *       取不到汇率也是 <b>NULL</b> —— <b>绝不</b>按 1:1 或固定汇率填一个数进去：
 *       假汇率会让「跨币种合计」变成一个看起来能对账、其实对不上的数。</li>
 *   <li><b>税额本模块不算</b>：税率的权威在 12 财务，而 finance 服务当前不存在 ⇒
 *       taxRate / taxAmount 恒为 null，<b>不造税率表</b>、不跨库读 finance。</li>
 *   <li><b>歧义报价不替用户挑</b>：带出报价时并列的候选逐条列出来；
 *       提交时 catalog 的严格取价 409 ⇒ 直接报 409，<b>绝不</b>静默取第一条。</li>
 *   <li><b>单位换算（箱规）不猜成 1</b>：取不到就是 UNKNOWN + null ——
 *       猜成 1 会让订量差一个数量级（1 箱 = 12 件时差 12 倍）。</li>
 * </ol>
 *
 * <p><b>时间绑定</b>：{@code timestamptz} 一律 {@code ?::timestamptz} + {@link OffsetDateTime}。
 * {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s。承诺到货日是 {@code date}
 * （业务日历口径），不存时刻。
 *
 * <p><b>金额</b>：一律 BigDecimal + DB 的 numeric(19,6)，禁浮点。头金额与行数**由 DB 触发器
 * 从行表重算**（V254 的 refresh_purchase_order_totals）⇒ 本类<b>不</b>在 Java 侧算完再写进去
 * （写了也会被覆盖，且会掩盖口径错误）。本类只**读**回 DB 算出来的值。
 */
@Service
public class PurchaseOrderEditService {

  /** 读权限码（与 6.3.1.1 / 6.3.2.2 同一套，identity 零改动）。 */
  public static final String READ_PERMISSION = "trade.purchase.read";

  /** 写权限码。 */
  public static final String UPDATE_PERMISSION = "trade.purchase.update";

  /** 数据范围：全租户。 */
  public static final String SCOPE_ALL = "ALL";

  /** 数据范围：本部门。 */
  public static final String SCOPE_DEPARTMENT = "DEPARTMENT";

  /** 数据范围：本人负责。 */
  public static final String SCOPE_SELF = "SELF";

  /** 采购单状态：草稿（唯一可编辑的状态）。 */
  public static final String STATUS_DRAFT = "DRAFT";

  /** 采购单状态：待审批（提交之后）。 */
  public static final String STATUS_PENDING_APPROVAL = "PENDING_APPROVAL";

  /** 本位币合计的折算精度（与 6.5.2.1 采购分析同一口径）。 */
  private static final int FX_SCALE = 8;

  private final DSLContext sql;

  /** catalog 的报价（3.6.2.1）与单位换算（3.1.2.4）。 */
  private final ReplenishmentCatalogPort catalog;

  /** catalog 的汇率台账（5.2.2.2）与法人本位币。 */
  private final PurchaseAnalyticsCatalogPort rates;

  /** warehouse 的库存（7.1.1.3）—— 库存快照的来源。 */
  private final StockoutInventoryPort inventory;

  /** warehouse 的目标仓与法人参照。 */
  private final PurchasePlanWarehousePort warehouse;

  /** catalog 的供应商（party）参照。 */
  private final SupplierPartyQueryPort suppliers;

  /** catalog 的媒体库（3.4.1.1）—— 附件元数据的来源。 */
  private final PurchaseOrderMediaPort media;

  public PurchaseOrderEditService(
      DSLContext sql,
      ReplenishmentCatalogPort catalog,
      PurchaseAnalyticsCatalogPort rates,
      StockoutInventoryPort inventory,
      PurchasePlanWarehousePort warehouse,
      SupplierPartyQueryPort suppliers,
      PurchaseOrderMediaPort media) {
    this.sql = sql;
    this.catalog = catalog;
    this.rates = rates;
    this.inventory = inventory;
    this.warehouse = warehouse;
    this.suppliers = suppliers;
    this.media = media;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：逐条说清本模块**真的**能做什么、哪些维度归别的模块。
   *
   * <p>存在的理由与 6.3.1.1 一致：让界面能说清「为什么没有审批结论、为什么没有税额」，
   * 而不是让它们显示成空白让人以为「这单没有这些值」。
   */
  public PurchaseOrderEditCapabilities capabilities() {
    return new PurchaseOrderEditCapabilities(
        List.of(
            new PurchaseOrderEditCapability(
                "manualCreate",
                "手工建单（草稿）",
                true,
                "6.3.1.2 采购单创建与编辑",
                "选供应商、添加 SKU 行、填数量/采购单位/单价/币种、约定到货日与发货地址、"
                    + "备注与附件，落成一张草稿（source_type=MANUAL）。"),
            new PurchaseOrderEditCapability(
                "editDraft",
                "编辑草稿",
                true,
                "6.3.1.2 采购单创建与编辑",
                "只有草稿能改（增行、改行、删行都在一份表单里整体替换）；"
                    + "已提交的采购单要改数量与单价得走变更单或红冲重下。"),
            new PurchaseOrderEditCapability(
                "submit",
                "提交（草稿 → 待审批）",
                true,
                "6.3.1.2 采购单创建与编辑",
                "提交那一刻冻结单价、汇率与单位换算快照；提交后草稿即冻结。"),
            new PurchaseOrderEditCapability(
                "quoteSnapshot",
                "带出报价",
                true,
                "3.6.2.1 供应商报价（catalog）",
                "按 SKU + 供应商带出有效报价与阶梯价；同一优先级多条有效报价时并列列出、"
                    + "**不替用户挑**，提交时直接 409 要求先在报价台账里定优先级。"),
            new PurchaseOrderEditCapability(
                "stockSnapshot",
                "库存快照",
                true,
                "08 仓储作业（warehouse）",
                "取 08 的分 SKU 可用量做快照；取不到就是「没有快照」，不填 0 冒充「一件都没有」。"),
            new PurchaseOrderEditCapability(
                "unitConversionSnapshot",
                "单位换算（箱规）快照",
                true,
                "3.1.2.4 单位换算（catalog）",
                "提交那一刻冻结「1 个采购单位 = 多少基础单位」；取不到就是 UNKNOWN，"
                    + "**绝不猜成 1**（猜成 1 会让订量差一个数量级）。"),
            new PurchaseOrderEditCapability(
                "fxSnapshot",
                "汇率快照（本位币折算）",
                true,
                "5.2.2.2 汇率台账（catalog）",
                "提交那一刻按「发生日当天或之前最新的一条」冻结；**取不到就是没有**，"
                    + "绝不按 1:1 或固定汇率凑一个数出来。"),
            new PurchaseOrderEditCapability(
                "shipAddress",
                "发货地址",
                true,
                "6.3.1.2 采购单创建与编辑",
                "地址是表单内容（草稿期可改，提交后冻结）；本仓的地址台账 3.6.3.1 在 catalog 独立库，"
                    + "warehouse 的仓档案里也没有街道地址字段 ⇒ 本模块不引用外部地址源。"),
            new PurchaseOrderEditCapability(
                "attachment",
                "附件",
                true,
                "3.4.1.1 媒体库（catalog）",
                "逻辑引用 + 元数据快照（文件名 / 类型 / 大小 / 存储键），不建跨库外键、不跨库写。"),
            new PurchaseOrderEditCapability(
                "convertFromDemandMerge",
                "由 6.2.2.2 的采购单草稿建单",
                true,
                "6.2.2.2 需求合并与批量（trade）",
                "读 6.2.2.2 的合并草稿建真正的采购单，并把草稿置为 CONVERTED；"
                    + "草稿 id 记在本模块自己的 purchase_order_draft_source 表，"
                    + "**不**塞进 purchase_order_line.source_conversion_id（那一列指向 6.2.2.1 的转单事实）。"),
            new PurchaseOrderEditCapability(
                "approval",
                "审批（通过 / 驳回 / 批量）",
                false,
                "6.3.1.3 采购单审批",
                "6.3.1.3 未交付。purchase_order.status 里有 PENDING_APPROVAL / APPROVED / REJECTED，"
                    + "但**没有任何审批留痕**（谁审的、依据哪条规则、什么时候审的）—— "
                    + "本模块只把单推到 PENDING_APPROVAL，不冒充「审批状态」这个概念。"),
            new PurchaseOrderEditCapability(
                "taxAmount",
                "税额计算",
                false,
                "12 财务（finance 服务当前不存在）",
                "税率的权威在 12 财务，而 backend/services/ 下没有 finance 服务 ⇒ "
                    + "本模块**不造税率表**、不跨库读 finance，taxRate / taxAmount 恒为 null，"
                    + "只算**本位币折算快照**（有汇率就算折算，不算税）。"),
            new PurchaseOrderEditCapability(
                "payableCreation",
                "生成应付",
                false,
                "12 财务（finance 服务当前不存在）",
                "应付由 6.3.1.3 审批通过后触发 12；本模块不越界造应付。"),
            new PurchaseOrderEditCapability(
                "lineDelete",
                "删除采购单行",
                true,
                "6.3.1.2 采购单创建与编辑",
                "只有**草稿**能删行（V258 的 guard_purchase_order_line_delete_guard 钉死）；"
                    + "已提交的单改数量与单价要��变更单或红冲重下。"
                    + "⚠️ 采购单**本体**不可删除（6.3.1.1 的保护原封不动）。")),
        "本模块的能力声明按「职责句」逐块列出：能做的标 true 并写清前提，"
            + "归别的模块的标 false 并点名归属模块（6.3.1.3 审批 / 12 财务应付与税额）。");
  }

  // ================================================================ 建单参照

  /**
   * 建单参照（供应商 / 目标仓 / 法人 / 币种）。
   *
   * <p>四样都来自**真实数据**，没有一个是硬编码词表：供应商来自 catalog（party）、
   * 目标仓与法人来自 warehouse、币种来自「本租户已有采购单用过的币种」+「法人本位币」。
   */
  public PurchaseOrderEditOptions options(String bearer) {
    List<PurchaseOrderEditSupplierOption> supplierOptions = new ArrayList<>();
    for (SupplierPartyQueryPort.SupplierView s : suppliers.suppliers(null, bearer)) {
      supplierOptions.add(
          new PurchaseOrderEditSupplierOption(s.roleId(), s.partyCode(), s.partyName(), s.partyStatus()));
    }

    List<PurchaseOrderEditWarehouseOption> warehouseOptions = new ArrayList<>();
    for (PurchasePlanWarehousePort.WarehouseView w : warehouse.warehouses(bearer)) {
      warehouseOptions.add(
          new PurchaseOrderEditWarehouseOption(w.id(), w.code(), w.name(), w.countryCode(), w.status()));
    }

    // 法人 + 本位币（本位币来自 catalog 的法人档案；取不到就如实留空）。
    Map<UUID, String> functionalByLegalEntity = new LinkedHashMap<>();
    try {
      for (PurchaseAnalyticsCatalogPort.FunctionalCurrencyRow row : rates.functionalCurrencies(bearer)) {
        if (row.legalEntityId() != null && row.baseCurrency() != null) {
          functionalByLegalEntity.put(row.legalEntityId(), row.baseCurrency());
        }
      }
    } catch (RuntimeException e) {
      // 本位币读不到不阻断建单参照：法人下拉仍然可用，只是没有本位币一栏。
      functionalByLegalEntity = new LinkedHashMap<>();
    }
    List<PurchaseOrderEditLegalEntityOption> legalEntityOptions = new ArrayList<>();
    for (PurchasePlanWarehousePort.LegalEntityView e : warehouse.legalEntities(bearer)) {
      legalEntityOptions.add(
          new PurchaseOrderEditLegalEntityOption(
              e.id(), e.code(), e.name(), e.status(), functionalByLegalEntity.get(e.id())));
    }

    // 币种：本租户已有采购单用过的 + 法人本位币（都是真实数据，不是词表）。
    Map<String, Long> currencyCounts = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select currency, count(*) as c from purchase_order"
                + " where tenant_id = nullif(current_setting('app.current_tenant', true),'')::uuid"
                + " group by currency order by currency")) {
      currencyCounts.merge(r.get("currency", String.class), r.get("c", Long.class), Long::sum);
    }
    for (String functional : functionalByLegalEntity.values()) {
      currencyCounts.putIfAbsent(functional, 0L);
    }
    List<PurchaseOrderEditCurrencyOption> currencyOptions = new ArrayList<>();
    for (Map.Entry<String, Long> entry : currencyCounts.entrySet()) {
      currencyOptions.add(
          new PurchaseOrderEditCurrencyOption(
              entry.getKey(), entry.getValue() > 0 ? "本租户已有采购单" : "法人本位币", entry.getValue()));
    }

    return new PurchaseOrderEditOptions(
        supplierOptions,
        warehouseOptions,
        legalEntityOptions,
        currencyOptions,
        "供应商来自 catalog（party）、目标仓与法人来自 warehouse、币种来自本租户已有采购单与法人本位币 —— "
            + "四样都是真实数据，没有一个是硬编码词表。");
  }

  // ================================================================ 带出报价与库存

  /**
   * 带出报价与库存快照（建单表单预览）。
   *
   * <p>🔴 <b>歧义不替用户挑</b>：同一优先级多条有效报价时，{@code selected} 是 <b>null</b>，
   * 并列的候选逐条列在 {@code candidates} 里 —— 用户自己选，或者去报价台账改优先级。
   *
   * <p>⚠️ 这里的报价与库存是**预览**（当下的事实），<b>不是冻结快照</b>；
   * 真正的冻结发生在提交那一刻（{@link #submit}）。
   */
  public PurchaseOrderEditQuotePreview quotePreview(PurchaseOrderEditQuotePreviewRequest request, String bearer) {
    if (request == null || request.skuId() == null) {
      throw new IllegalArgumentException("必须指定 SKU");
    }
    if (request.supplierRefId() == null) {
      throw new IllegalArgumentException("必须指定供应商：一张采购单只对一个供应商");
    }
    LocalDate at = request.at() == null ? dbToday() : request.at();

    // ---- SKU 与单位换算（3.1.2.4）----
    ReplenishmentCatalogPort.SkuView sku = catalog.sku(request.skuId(), bearer);
    BigDecimal quantityPerBase = null;
    String purchaseUnitCode = "";
    for (ReplenishmentCatalogPort.ConversionView conversion : sku.unitConversions()) {
      if (conversion == null || !"PURCHASE".equals(conversion.role())) {
        continue;
      }
      if (conversion.quantityPerBase() == null || conversion.quantityPerBase().signum() <= 0) {
        continue;
      }
      quantityPerBase = conversion.quantityPerBase();
      purchaseUnitCode = conversion.unitCode() == null ? "" : conversion.unitCode();
      break;
    }
    String conversionStatus = quantityPerBase == null ? "UNKNOWN" : "CATALOG_UNIT_CONVERSION";
    BigDecimal baseQuantity =
        quantityPerBase == null || request.quantity() == null
            ? null
            : request.quantity().multiply(quantityPerBase).setScale(6, RoundingMode.HALF_UP);

    // ---- 报价（3.6.2.1）：角色 id → 主体 id 换算后按供应商取价 ----
    UUID partyId = suppliers.partyId(request.supplierRefId(), bearer);
    ReplenishmentCatalogPort.EffectiveResult effective =
        catalog.effective(request.skuId(), partyId, at, request.quantity(), bearer);

    List<PurchaseOrderEditQuoteCandidate> candidates = new ArrayList<>();
    for (ReplenishmentCatalogPort.EffectiveQuoteView q : effective.candidates()) {
      candidates.add(
          new PurchaseOrderEditQuoteCandidate(
              q.id(),
              q.supplierId(),
              q.supplierName(),
              q.currency(),
              q.purchaseUnitCode(),
              q.moqQuantity(),
              q.leadTimeDays(),
              q.priority(),
              tierPrice(effective, q.id()),
              tierMin(effective, q.id()),
              q.selectionState()));
    }
    PurchaseOrderEditQuoteCandidate selected = null;
    if (effective.selected() != null) {
      selected =
          new PurchaseOrderEditQuoteCandidate(
              effective.selected().id(),
              effective.selected().supplierId(),
              effective.selected().supplierName(),
              effective.selected().currency(),
              effective.selected().purchaseUnitCode(),
              effective.selected().moqQuantity(),
              effective.selected().leadTimeDays(),
              effective.selected().priority(),
              effective.selectedTier() == null ? null : effective.selectedTier().unitPrice(),
              effective.selectedTier() == null ? null : effective.selectedTier().minQuantity(),
              effective.selected().selectionState());
    }

    // ---- 库存快照（08 仓储）----
    BigDecimal available = null;
    BigDecimal sellable = null;
    String stockStatus = "UNAVAILABLE";
    String stockNote = "库存快照取不到（08 仓储不可达或该 SKU 没有库存账）—— 快照是「没有」，不是 0。";
    try {
      StockoutInventoryPort.SellableRow row = inventory.sellableForSku(request.skuId(), bearer);
      if (row != null) {
        available = row.available();
        sellable = row.sellable();
        stockStatus = "CAPTURED";
        stockNote = "库存快照取自 08 仓储的分 SKU 可用量（available / sellable）。";
      }
    } catch (RuntimeException e) {
      // 库存是辅助信息：取不到就标 UNAVAILABLE，**不**把整次预览变成失败。
      stockStatus = "UNAVAILABLE";
    }

    return new PurchaseOrderEditQuotePreview(
        request.skuId(),
        sku.code(),
        sku.name(),
        sku.baseUnit(),
        purchaseUnitCode,
        quantityPerBase,
        baseQuantity,
        conversionStatus,
        candidates,
        selected,
        effective.ambiguous(),
        effective.ambiguityProblem(),
        available,
        sellable,
        stockStatus,
        stockNote,
        effective.ambiguous()
            ? "存在同一优先级的多条有效报价：候选已逐条列出，请选一条或先在报价台账里定下优先级。"
            : "报价候选已按供应商内部优先级列出；selected 为 null 表示没有唯一可自动采用的报价。");
  }

  // ================================================================ 新建草稿

  /**
   * 新建采购单（草稿）。
   *
   * <p>{@code source_type} 写 <b>'MANUAL'}</b>（V254 明写「留给 6.3.1.2 手工建单」）。
   * 写错成转单来源会把两套来源混成一套，事后分不清这单是人开的还是系统转的。
   */
  public PurchaseOrderEditForm create(
      PurchaseOrderEditCreateRequest request, UUID actorId, UUID actorDeptId, String bearer) {
    PurchaseOrderEditOrderInput input = request == null ? null : request.order();
    if (input == null) {
      throw new IllegalArgumentException("必须给出采购单表单");
    }
    validateOrder(input);

    UUID orderId = UUID.randomUUID();
    UUID tenantId = currentTenant();
    OffsetDateTime now = dbNow();
    String orderNo = "PO-" + sql.fetchOne("select nextval('purchase_order_no_seq')").get(0, Long.class);

    sql.execute(
        "insert into purchase_order(id,tenant_id,order_no,source_type,source_request_id,source_request_no,"
            + " supplier_ref_id,supplier_code,supplier_name,legal_entity_id,legal_entity_code,legal_entity_name,"
            + " currency,functional_currency,fx_snapshot_at,status,promised_arrival_date,"
            + " target_warehouse_id,target_warehouse_code,target_warehouse_name,"
            + " owner_user_id,owner_department_id,urge_count,last_urged_at,closed_at,close_reason,"
            + " note,created_by,created_at,updated_at,version)"
            + " values (?::uuid,?::uuid,?::varchar,'MANUAL',null,'',"
            + " ?::uuid,?::varchar,?::varchar,?::uuid,?::varchar,?::varchar,"
            + " ?::varchar,null,null,'DRAFT',?::date,"
            + " ?::uuid,?::varchar,?::varchar,"
            + " ?::uuid,?::uuid,0,null,null,'',?::varchar,?::uuid,?::timestamptz,?::timestamptz,0)",
        orderId,
        tenantId,
        orderNo,
        input.supplierRefId(),
        nz(input.supplierCode()),
        nz(input.supplierName()),
        input.legalEntityId(),
        nz(input.legalEntityCode()),
        nz(input.legalEntityName()),
        input.currency(),
        input.promisedArrivalDate(),
        input.targetWarehouseId(),
        nz(input.targetWarehouseCode()),
        nz(input.targetWarehouseName()),
        actorId,
        actorDeptId,
        nz(input.note()),
        actorId,
        now,
        now);

    writeLines(orderId, input.lines());
    writeAddress(orderId, input.shipAddress());
    writeAttachments(orderId, input.attachments(), bearer);

    appendEvent(orderId, "CREATED", null, STATUS_DRAFT, "手工建单（6.3.1.2）", actorId, now);

    return loadForm(orderId, actorId, actorDeptId, SCOPE_ALL, bearer);
  }

  // ================================================================ 读表单

  /**
   * 读一张采购单的编辑表单（头 + 行 + 冻结快照 + 地址 + 附件 + 金额结果）。
   *
   * <p>数据范围由服务端从<b>已认证身份</b>与角色授权推导，<b>不接受请求参数传入</b>。
   */
  public PurchaseOrderEditForm form(
      UUID orderId, UUID actorId, UUID actorDeptId, String scope, String bearer) {
    return loadForm(orderId, actorId, actorDeptId, scope, bearer);
  }

  // ================================================================ 编辑保存

  /**
   * 编辑保存（<b>只有草稿能改</b>）。
   *
   * <p><b>整体替换语义</b>：表单给的是这一版的完整行集合，
   * 少传一行 = 删掉那一行（不是「保持原样」）。删行由 V258 的触发器兜住
   * （只有草稿能删行）。
   */
  public PurchaseOrderEditForm update(
      UUID orderId,
      PurchaseOrderEditUpdateRequest request,
      UUID actorId,
      UUID actorDeptId,
      String scope,
      String bearer) {
    if (request == null) {
      throw new IllegalArgumentException("必须给出表单");
    }
    if (request.expectedVersion() == null) {
      throw new IllegalArgumentException("必须带 expected_version（乐观锁）");
    }
    PurchaseOrderEditOrderInput input = request.order();
    if (input == null) {
      throw new IllegalArgumentException("必须给出采购单表单");
    }
    validateOrder(input);

    Record order = requireOrder(orderId, actorId, actorDeptId, scope);
    requireDraft(order, "编辑");
    requireVersion(order, request.expectedVersion());

    OffsetDateTime now = dbNow();
    int updated =
        sql.execute(
            "update purchase_order set supplier_ref_id=?::uuid,supplier_code=?::varchar,supplier_name=?::varchar,"
                + " legal_entity_id=?::uuid,legal_entity_code=?::varchar,legal_entity_name=?::varchar,"
                + " currency=?::varchar,promised_arrival_date=?::date,target_warehouse_id=?::uuid,"
                + " target_warehouse_code=?::varchar,target_warehouse_name=?::varchar,note=?::varchar,"
                + " updated_at=?::timestamptz,version=version+1"
                + " where id=?::uuid and version=?",
            input.supplierRefId(),
            nz(input.supplierCode()),
            nz(input.supplierName()),
            input.legalEntityId(),
            nz(input.legalEntityCode()),
            nz(input.legalEntityName()),
            input.currency(),
            input.promisedArrivalDate(),
            input.targetWarehouseId(),
            nz(input.targetWarehouseCode()),
            nz(input.targetWarehouseName()),
            nz(input.note()),
            now,
            orderId,
            request.expectedVersion());
    if (updated == 0) {
      throw conflict("这张采购单已被别人改过，请刷新后重试");
    }

    replaceLines(orderId, input.lines());
    replaceAddress(orderId, input.shipAddress());
    replaceAttachments(orderId, input.attachments(), bearer);

    return loadForm(orderId, actorId, actorDeptId, scope, bearer);
  }

  // ================================================================ 提交

  /**
   * 提交（草稿 → 待审批）：<b>冻结快照</b>就在这一刻。
   *
   * <p>蓝图加粗句：「单价、汇率、单位换算在提交时冻结快照，之后改报价不影响已提交采购单的成本核算。」
   * ⇒ 本方法做四件事：
   *
   * <ol>
   *   <li>逐行冻结<b>单位换算</b>（3.1.2.4）、<b>报价</b>（3.6.2.1，严格取价，歧义 409）、
   *       <b>库存</b>（08 仓储）；</li>
   *   <li>冻结<b>本位币与汇率</b>（5.2.2.2）—— 取不到就是 NULL，<b>绝不</b>按 1:1 填；</li>
   *   <li>把 {@code purchase_order.status} 推到 PENDING_APPROVAL（DB 状态机钉死的合法跃迁）；</li>
   *   <li>留痕（{@code purchase_order_event.STATUS_CHANGED}）。</li>
   * </ol>
   *
   * <p>🔴 <b>不审批</b>：6.3.1.3 未交付，本模块只把单推到待审批。
   */
  public PurchaseOrderEditForm submit(
      UUID orderId,
      PurchaseOrderEditSubmitRequest request,
      UUID actorId,
      UUID actorDeptId,
      String scope,
      String bearer) {
    if (request == null || request.expectedVersion() == null) {
      throw new IllegalArgumentException("必须带 expected_version（乐观锁）");
    }
    Record order = requireOrder(orderId, actorId, actorDeptId, scope);
    requireDraft(order, "提交");
    requireVersion(order, request.expectedVersion());

    List<Record> lines =
        sql.fetch(
            "select * from purchase_order_line where order_id=?::uuid order by line_no", orderId);
    if (lines.isEmpty()) {
      throw new IllegalArgumentException("这张采购单还没有行，先加行再提交");
    }

    UUID tenantId = currentTenant();
    OffsetDateTime now = dbNow();
    String currency = order.get("currency", String.class);
    UUID supplierRefId = order.get("supplier_ref_id", UUID.class);
    UUID legalEntityId = order.get("legal_entity_id", UUID.class);

    // ---- 逐行冻结：单位换算 + 报价 + 库存 ----
    UUID partyId = null;
    try {
      partyId = suppliers.partyId(supplierRefId, bearer);
    } catch (RuntimeException e) {
      partyId = null;
    }
    List<PurchaseOrderEditLineSnapshot> snapshots = new ArrayList<>();
    for (Record line : lines) {
      snapshots.add(
          freezeLine(line, partyId, currency, now, bearer));
    }

    // ---- 单级冻结：本位币与汇率 ----
    FxSnapshot fx = captureFx(currency, legalEntityId, bearer);

    for (int i = 0; i < lines.size(); i++) {
      Record line = lines.get(i);
      PurchaseOrderEditLineSnapshot snapshot = snapshots.get(i);
      sql.execute(
          "insert into purchase_order_line_snapshot(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
              + " base_unit_code,purchase_unit_code,quantity_per_base,base_quantity,conversion_status,"
              + " quote_id,quote_supplier_id,quote_supplier_name,quote_currency,quote_purchase_unit_code,"
              + " quote_moq_quantity,quote_lead_time_days,quote_unit_price,quote_valid_from,quote_valid_to,"
              + " price_source,stock_available_qty,stock_sellable_qty,stock_snapshot_at,stock_status,"
              + " tax_rate,tax_amount,tax_status,snapshot_at,created_at)"
              + " values (?::uuid,?::uuid,?::uuid,?::integer,?::uuid,?::varchar,?::varchar,"
              + " ?::varchar,?::varchar,?::numeric,?::numeric,?::varchar,"
              + " ?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,"
              + " ?::numeric,?::integer,?::numeric,?::date,?::date,"
              + " ?::varchar,?::numeric,?::numeric,?::timestamptz,?::varchar,"
              + " null,null,'NOT_AVAILABLE',?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          tenantId,
          orderId,
          line.get("line_no", Integer.class),
          line.get("sku_id", UUID.class),
          nz(line.get("sku_code", String.class)),
          nz(line.get("sku_name", String.class)),
          snapshot.baseUnitCode(),
          snapshot.purchaseUnitCode(),
          snapshot.quantityPerBase(),
          snapshot.baseQuantity(),
          snapshot.conversionStatus(),
          snapshot.quoteId(),
          supplierRefId,
          snapshot.quoteSupplierName(),
          snapshot.quoteCurrency(),
          "",
          snapshot.quoteMoqQuantity(),
          snapshot.quoteLeadTimeDays(),
          snapshot.quoteUnitPrice(),
          snapshot.quoteValidFrom(),
          snapshot.quoteValidTo(),
          nz(line.get("price_source", String.class)),
          snapshot.stockAvailableQty(),
          snapshot.stockSellableQty(),
          snapshot.stockSnapshotAt(),
          snapshot.stockStatus(),
          now,
          now);
    }

    // ---- 提交快照（追加式）----
    sql.execute(
        "insert into purchase_order_submit_snapshot(id,tenant_id,order_id,functional_currency,"
            + " fx_rate_to_functional,fx_rate_base_currency,fx_rate_effective_date,fx_status,"
            + " tax_rate,tax_amount,tax_status,tax_note,from_status,to_status,submitted_by,submitted_at,"
            + " note,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::numeric,?::varchar,?::date,?::varchar,"
            + " null,null,'NOT_AVAILABLE',?::varchar,?::varchar,?::varchar,?::uuid,?::timestamptz,"
            + " ?::varchar,?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        orderId,
        fx.functionalCurrency(),
        fx.rate(),
        fx.captured() ? currency : null,
        fx.effectiveDate(),
        fx.status(),
        TAX_NOTE,
        STATUS_DRAFT,
        STATUS_PENDING_APPROVAL,
        actorId,
        now,
        nz(request.note()),
        now);

    // ---- 推进状态（DB 状态机钉死 DRAFT → PENDING_APPROVAL 是合法跃迁）----
    int updated =
        sql.execute(
            "update purchase_order set status=?::varchar,functional_currency=?::varchar,"
                + " fx_snapshot_at=?::timestamptz,updated_at=?::timestamptz,version=version+1"
                + " where id=?::uuid and version=? and status='DRAFT'",
            STATUS_PENDING_APPROVAL,
            fx.functionalCurrency(),
            fx.captured() ? now : null,
            now,
            orderId,
            request.expectedVersion());
    if (updated == 0) {
      throw conflict("这张采购单已被别人改过（或已不是草稿），请刷新后重试");
    }

    appendEvent(
        orderId,
        "STATUS_CHANGED",
        STATUS_DRAFT,
        STATUS_PENDING_APPROVAL,
        nz(request.note()),
        actorId,
        now);

    return loadForm(orderId, actorId, actorDeptId, scope, bearer);
  }

  // ================================================================ 由 6.2.2.2 草稿建单

  /**
   * 由 6.2.2.2 的**采购单草稿**（合并草稿）建真正的采购单，并把草稿置为 CONVERTED。
   *
   * <p>V253 第 68 行明写「6.3.1.2 交付后由它读这份草稿去建真正的采购单（并把草稿置为 CONVERTED）」。
   * 草稿的 status → CONVERTED 是被上游触发器显式允许的
   * （{@code guard_demand_merge_draft_guard} 只拦供应商 / 批次 / 目标仓 / 法人 / 币种改写）。
   *
   * <p>🔴 <b>草稿 id 绝不写进 purchase_order_line.source_conversion_id</b>：
   * 那一列指向 6.2.2.1 的转单事实，且带唯一键 ⇒ 混写会既混淆语义又可能误撞。
   * 草稿来源只记在本模块自己的 {@code purchase_order_draft_source}。
   */
  public PurchaseOrderEditFromDraftResult fromDrafts(
      PurchaseOrderEditFromDraftRequest request,
      UUID actorId,
      UUID actorDeptId,
      String bearer) {
    List<UUID> draftIds =
        request == null || request.draftIds() == null ? List.of() : request.draftIds();
    if (draftIds.isEmpty()) {
      throw new IllegalArgumentException("至少要给一张 6.2.2.2 的采购单草稿");
    }

    List<PurchaseOrderEditCreatedOrder> created = new ArrayList<>();
    List<PurchaseOrderEditRejectedDraft> rejected = new ArrayList<>();

    for (UUID draftId : draftIds) {
      Record draft =
          sql.fetchOne("select * from purchase_demand_merge_draft where id=?::uuid", draftId);
      if (draft == null) {
        rejected.add(
            new PurchaseOrderEditRejectedDraft(
                draftId, "DRAFT_NOT_FOUND", "合并草稿不存在或不属于当前租户"));
        continue;
      }
      if (!STATUS_DRAFT.equals(draft.get("status", String.class))) {
        rejected.add(
            new PurchaseOrderEditRejectedDraft(
                draftId, "DRAFT_ALREADY_CONVERTED", "这张合并草稿已经转成采购单了，不能再转一次"));
        continue;
      }
      Record batch =
          sql.fetchOne(
              "select * from purchase_demand_merge_batch where id=?::uuid",
              draft.get("batch_id", UUID.class));
      if (batch == null || !"FINALIZED".equals(batch.get("status", String.class))) {
        rejected.add(
            new PurchaseOrderEditRejectedDraft(
                draftId,
                "BATCH_NOT_FINALIZED",
                "合并批次还没定稿：定稿后草稿内容才冻结，才能转成真正的采购单"));
        continue;
      }
      boolean used =
          sql.fetchOne(
                  "select count(*) from purchase_order_draft_source where source_draft_id=?::uuid",
                  draftId)
              .get(0, Long.class)
          > 0;
      if (used) {
        rejected.add(
            new PurchaseOrderEditRejectedDraft(
                draftId, "DRAFT_ALREADY_CONVERTED", "这张合并草稿已经转成采购单了，不能再转一次"));
        continue;
      }

      List<Record> lines =
          sql.fetch(
              "select * from purchase_demand_merge_line where draft_id=?::uuid order by sort_rank, id",
              draftId);
      if (lines.isEmpty()) {
        rejected.add(
            new PurchaseOrderEditRejectedDraft(
                draftId, "DRAFT_EMPTY", "这张合并草稿没有行，没有东西可以转成采购单"));
        continue;
      }
      boolean missingPrice = false;
      LocalDate promised = null;
      for (Record line : lines) {
        if (line.get("unit_price", BigDecimal.class) == null) {
          missingPrice = true;
        }
        LocalDate arrival = line.get("arrival_date", LocalDate.class);
        if (arrival != null && (promised == null || arrival.isBefore(promised))) {
          promised = arrival;
        }
      }
      if (missingPrice) {
        rejected.add(
            new PurchaseOrderEditRejectedDraft(
                draftId,
                "UNIT_PRICE_MISSING",
                "草稿里有行没有单价，转不成采购单（采购单必须有金额，否则列表上的总金额是假的）"));
        continue;
      }
      if (promised == null) {
        rejected.add(
            new PurchaseOrderEditRejectedDraft(
                draftId,
                "NO_PROMISED_DATE",
                "草稿的行没有期望到货日，采购单必须有承诺到货日（请先在合并草稿上补上日期）"));
        continue;
      }

      // ---- 建单（一张草稿一个供应商 ⇒ 一张采购单）----
      UUID orderId = UUID.randomUUID();
      UUID tenantId = currentTenant();
      OffsetDateTime now = dbNow();
      String orderNo = "PO-" + sql.fetchOne("select nextval('purchase_order_no_seq')").get(0, Long.class);
      String currency = draft.get("currency_code", String.class);

      // 合并草稿背后可能跨多张 6.2.2.1 申请单 ⇒ 申请单 id 只在**唯一一张**时写进源头。
      List<UUID> requestIds =
          sql.fetch(
                  "select distinct source_request_id from purchase_demand_merge_line_source"
                      + " where tenant_id = nullif(current_setting('app.current_tenant', true),'')::uuid"
                      + " and draft_line_id in (select id from purchase_demand_merge_line"
                      + " where draft_id=?::uuid)",
                  draftId)
              .stream()
              .map(r -> r.get("source_request_id", UUID.class))
              .filter(java.util.Objects::nonNull)
              .distinct()
              .toList();
      UUID sourceRequestId = requestIds.size() == 1 ? requestIds.get(0) : null;
      String sourceRequestNo = "";
      if (sourceRequestId != null) {
        Record requestRow =
            sql.fetchOne(
                "select request_no from purchase_request where id=?::uuid", sourceRequestId);
        if (requestRow != null) {
          sourceRequestNo = nz(requestRow.get("request_no", String.class));
        }
      }

      sql.execute(
          "insert into purchase_order(id,tenant_id,order_no,source_type,source_request_id,source_request_no,"
              + " supplier_ref_id,supplier_code,supplier_name,legal_entity_id,legal_entity_code,legal_entity_name,"
              + " currency,functional_currency,fx_snapshot_at,status,promised_arrival_date,"
              + " target_warehouse_id,target_warehouse_code,target_warehouse_name,"
              + " owner_user_id,owner_department_id,urge_count,last_urged_at,closed_at,close_reason,"
              + " note,created_by,created_at,updated_at,version)"
              + " values (?::uuid,?::uuid,?::varchar,'PURCHASE_REQUEST_CONVERSION',?::uuid,?::varchar,"
              + " ?::uuid,?::varchar,?::varchar,?::uuid,?::varchar,?::varchar,"
              + " ?::varchar,null,null,'DRAFT',?::date,"
              + " ?::uuid,?::varchar,?::varchar,"
              + " ?::uuid,?::uuid,0,null,null,'',?::varchar,?::uuid,?::timestamptz,?::timestamptz,0)",
          orderId,
          tenantId,
          orderNo,
          sourceRequestId,
          sourceRequestNo,
          draft.get("supplier_id", UUID.class),
          nz(draft.get("supplier_code", String.class)),
          nz(draft.get("supplier_name", String.class)),
          draft.get("legal_entity_id", UUID.class),
          nz(draft.get("legal_entity_code", String.class)),
          nz(draft.get("legal_entity_name", String.class)),
          currency,
          promised,
          draft.get("target_warehouse_id", UUID.class),
          nz(draft.get("target_warehouse_code", String.class)),
          nz(draft.get("target_warehouse_name", String.class)),
          actorId,
          actorDeptId,
          "由 6.2.2.2 的合并草稿 " + nz(draft.get("draft_no", String.class)) + " 转单",
          actorId,
          now,
          now);

      int lineNo = 0;
      BigDecimal total = BigDecimal.ZERO;
      for (Record line : lines) {
        lineNo++;
        BigDecimal quantity = line.get("merged_quantity", BigDecimal.class);
        BigDecimal price = line.get("unit_price", BigDecimal.class);
        total = total.add(quantity.multiply(price));
        sql.execute(
            "insert into purchase_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                + " source_conversion_id,source_request_line_id,quantity,unit_code,unit_price,line_amount,"
                + " fx_rate_to_functional,fx_rate_base_currency,fx_rate_effective_date,price_source,note,"
                + " created_at,updated_at)"
                + " values (?::uuid,?::uuid,?::uuid,?::integer,?::uuid,?::varchar,?::varchar,"
                + " null,null,?::numeric,'',?::numeric,?::numeric,"
                + " null,null,null,'REQUEST_ESTIMATE',?::varchar,?::timestamptz,?::timestamptz)",
            UUID.randomUUID(),
            tenantId,
            orderId,
            lineNo,
            line.get("sku_id", UUID.class),
            nz(line.get("sku_code", String.class)),
            nz(line.get("sku_name", String.class)),
            quantity,
            price,
            quantity.multiply(price),
            nz(line.get("note", String.class)),
            now,
            now);
      }

      // ---- 草稿来源（追加式）+ 把草稿置为 CONVERTED ----
      sql.execute(
          "insert into purchase_order_draft_source(id,tenant_id,order_id,source_draft_id,source_draft_no,"
              + " source_batch_id,source_batch_no,source_request_ids,converted_by,converted_at,created_at)"
              + " values (?::uuid,?::uuid,?::uuid,?::uuid,?::varchar,?::uuid,?::varchar,?::uuid[],?::uuid,"
              + " ?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          tenantId,
          orderId,
          draftId,
          nz(draft.get("draft_no", String.class)),
          draft.get("batch_id", UUID.class),
          nz(batch.get("batch_no", String.class)),
          requestIds.toArray(new UUID[0]),
          actorId,
          now,
          now);
      sql.execute(
          "update purchase_demand_merge_draft set status='CONVERTED',updated_at=?::timestamptz"
              + " where id=?::uuid and status='DRAFT'",
          now,
          draftId);

      appendEvent(
          orderId,
          "CREATED",
          null,
          STATUS_DRAFT,
          "由 6.2.2.2 的合并草稿 " + nz(draft.get("draft_no", String.class)) + " 转单",
          actorId,
          now);

      created.add(
          new PurchaseOrderEditCreatedOrder(
              orderId,
              orderNo,
              draftId,
              nz(draft.get("draft_no", String.class)),
              lineNo,
              total,
              currency));
    }

    return new PurchaseOrderEditFromDraftResult(
        created,
        rejected);
  }

  // ================================================================ 内部：校验

  /** 表单校验：把「一定会让这单没法用」的输入挡在写库之前。 */
  private void validateOrder(PurchaseOrderEditOrderInput input) {
    if (input.supplierRefId() == null) {
      throw new IllegalArgumentException("必须选择供应商");
    }
    if (isBlank(input.currency())) {
      throw new IllegalArgumentException("必须选择币种（一张采购单一个币种）");
    }
    if (!input.currency().matches("^[A-Z]{3}$")) {
      throw new IllegalArgumentException("币种必须是三位大写字母的 ISO 4217 代码");
    }
    if (input.targetWarehouseId() == null) {
      throw new IllegalArgumentException("必须选择目标仓");
    }
    if (input.promisedArrivalDate() == null) {
      throw new IllegalArgumentException("必须约定承诺到货日");
    }
    if (input.lines() == null || input.lines().isEmpty()) {
      throw new IllegalArgumentException("至少要加一行 SKU");
    }
    int no = 0;
    for (PurchaseOrderEditLineInput line : input.lines()) {
      no++;
      if (line.quantity() == null || line.quantity().signum() <= 0) {
        throw new IllegalArgumentException("第 " + no + " 行的数量必须大于 0");
      }
      if (line.unitPrice() == null || line.unitPrice().signum() < 0) {
        throw new IllegalArgumentException("第 " + no + " 行的单价不能为空也不能为负");
      }
      if (isBlank(line.skuCode())) {
        throw new IllegalArgumentException("第 " + no + " 行必须有 SKU");
      }
      String source = isBlank(line.priceSource()) ? "MANUAL" : line.priceSource().trim().toUpperCase();
      if (!"QUOTE".equals(source) && !"MANUAL".equals(source)) {
        throw new IllegalArgumentException("第 " + no + " 行的单价来源只能是 QUOTE 或 MANUAL");
      }
      if ("QUOTE".equals(source) && line.quoteId() == null) {
        throw new IllegalArgumentException(
            "第 " + no + " 行的单价来自报价，必须带上是哪条报价（quoteId），否则没法冻结报价快照");
      }
    }
    if (input.shipAddress() != null) {
      PurchaseOrderEditAddressInput address = input.shipAddress();
      if (isBlank(address.recipientName())) {
        throw new IllegalArgumentException("发货地址必须有收件人");
      }
      if (isBlank(address.detail())) {
        throw new IllegalArgumentException("发货地址必须有详细地址");
      }
      if (!isBlank(address.countryCode()) && !address.countryCode().matches("^[A-Z]{2}$")) {
        throw new IllegalArgumentException("发货地址的国家代码必须是两位大写字母");
      }
    }
    if (input.attachments() != null) {
      for (PurchaseOrderEditAttachmentInput attachment : input.attachments()) {
        if (attachment.mediaId() == null) {
          throw new IllegalArgumentException("附件必须指定素材");
        }
        String type = isBlank(attachment.attachmentType()) ? "OTHER" : attachment.attachmentType().trim();
        if (!Set.of("CONTRACT", "QUOTE", "ORDER", "OTHER").contains(type)) {
          throw new IllegalArgumentException("附件类型只能是 CONTRACT / QUOTE / ORDER / OTHER");
        }
      }
    }
  }

  /** 读订单（带租户与数据范围校验）。 */
  private Record requireOrder(UUID orderId, UUID actorId, UUID actorDeptId, String scope) {
    if (orderId == null) {
      throw new IllegalArgumentException("必须指定采购单");
    }
    Record order = sql.fetchOne("select * from purchase_order where id=?::uuid", orderId);
    if (order == null) {
      // RLS 之下读不到 = 不存在或不属于当前租户（**不泄露别的租户的单是否存在**）。
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "采购单不存在或不属于当前租户");
    }
    if (!withinScope(order, actorId, actorDeptId, scope)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "这张采购单不在你的数据范围内（当前范围：" + scope + "）");
    }
    return order;
  }

  /** 只有草稿能改。 */
  private void requireDraft(Record order, String action) {
    if (!STATUS_DRAFT.equals(order.get("status", String.class))) {
      throw conflict(
          "只有草稿能"
              + action
              + "：这张采购单当前是「"
              + order.get("status", String.class)
              + "」。已提交的采购单要改数量与单价，请走变更单或红冲重下。");
    }
  }

  /** 乐观锁。 */
  private void requireVersion(Record order, Long expectedVersion) {
    long actual = order.get("version", Long.class);
    if (expectedVersion != actual) {
      throw conflict(
          "这张采购单已被别人改过（当前版本 " + actual + "，你拿到的是 " + expectedVersion + "），请刷新后重试");
    }
  }

  /** 数据范围（ALL / DEPARTMENT / SELF）。 */
  private boolean withinScope(Record order, UUID actorId, UUID actorDeptId, String scope) {
    if (SCOPE_ALL.equals(scope)) {
      return true;
    }
    if (SCOPE_DEPARTMENT.equals(scope)) {
      UUID dept = order.get("owner_department_id", UUID.class);
      return dept != null && dept.equals(actorDeptId);
    }
    return actorId != null && actorId.equals(order.get("owner_user_id", UUID.class));
  }

  // ================================================================ 内部：写库

  /** 新建时的行写入。 */
  private void writeLines(UUID orderId, List<PurchaseOrderEditLineInput> lines) {
    UUID tenantId = currentTenant();
    OffsetDateTime now = dbNow();
    int lineNo = 0;
    for (PurchaseOrderEditLineInput line : lines) {
      lineNo++;
      sql.execute(
          "insert into purchase_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
              + " source_conversion_id,source_request_line_id,quantity,unit_code,unit_price,line_amount,"
              + " fx_rate_to_functional,fx_rate_base_currency,fx_rate_effective_date,price_source,note,"
              + " created_at,updated_at)"
              + " values (?::uuid,?::uuid,?::uuid,?::integer,?::uuid,?::varchar,?::varchar,"
              + " null,null,?::numeric,?::varchar,?::numeric,?::numeric,"
              + " null,null,null,?::varchar,?::varchar,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          tenantId,
          orderId,
          lineNo,
          line.skuId(),
          nz(line.skuCode()),
          nz(line.skuName()),
          line.quantity(),
          nz(line.unitCode()),
          line.unitPrice(),
          line.unitPrice().multiply(line.quantity()),
          priceSource(line),
          nz(line.note()),
          now,
          now);
    }
  }

  /**
   * 行的整体替换：按数组顺序，第 i 行对应第 i 行。
   *
   * <p>多出来的行删掉（V258 的触发器保证只有草稿能删行）；
   * 少的行补上；行数不变的按位置改。
   */
  private void replaceLines(UUID orderId, List<PurchaseOrderEditLineInput> lines) {
    UUID tenantId = currentTenant();
    OffsetDateTime now = dbNow();
    List<Record> existing =
        sql.fetch(
            "select * from purchase_order_line where order_id=?::uuid order by line_no", orderId);

    for (int i = 0; i < lines.size(); i++) {
      PurchaseOrderEditLineInput line = lines.get(i);
      int lineNo = i + 1;
      if (i < existing.size()) {
        Record current = existing.get(i);
        sql.execute(
            "update purchase_order_line set line_no=?::integer,sku_id=?::uuid,sku_code=?::varchar,"
                + " sku_name=?::varchar,quantity=?::numeric,unit_code=?::varchar,unit_price=?::numeric,"
                + " price_source=?::varchar,note=?::varchar,updated_at=?::timestamptz where id=?::uuid",
            lineNo,
            line.skuId(),
            nz(line.skuCode()),
            nz(line.skuName()),
            line.quantity(),
            nz(line.unitCode()),
            line.unitPrice(),
            priceSource(line),
            nz(line.note()),
            now,
            current.get("id", UUID.class));
      } else {
        sql.execute(
            "insert into purchase_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                + " source_conversion_id,source_request_line_id,quantity,unit_code,unit_price,line_amount,"
                + " fx_rate_to_functional,fx_rate_base_currency,fx_rate_effective_date,price_source,note,"
                + " created_at,updated_at)"
                + " values (?::uuid,?::uuid,?::uuid,?::integer,?::uuid,?::varchar,?::varchar,"
                + " null,null,?::numeric,?::varchar,?::numeric,?::numeric,"
                + " null,null,null,?::varchar,?::varchar,?::timestamptz,?::timestamptz)",
            UUID.randomUUID(),
            tenantId,
            orderId,
            lineNo,
            line.skuId(),
            nz(line.skuCode()),
            nz(line.skuName()),
            line.quantity(),
            nz(line.unitCode()),
            line.unitPrice(),
            line.unitPrice().multiply(line.quantity()),
            priceSource(line),
            nz(line.note()),
            now,
            now);
      }
    }
    for (int i = lines.size(); i < existing.size(); i++) {
      // ⚠️ 删行只对草稿成立（V258 的 guard_purchase_order_line_delete_guard 钉死）。
      sql.execute(
          "delete from purchase_order_line where id=?::uuid", existing.get(i).get("id", UUID.class));
    }
  }

  /** 发货地址写入（新建时）。 */
  private void writeAddress(UUID orderId, PurchaseOrderEditAddressInput address) {
    if (address == null) {
      return;
    }
    sql.execute(
        "insert into purchase_order_ship_address(id,tenant_id,order_id,recipient_name,recipient_phone,"
            + " country_code,province,city,district,detail,postal_code,note,created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::varchar,?::varchar,"
            + " ?::varchar,?::varchar,?::varchar,?::varchar,?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        orderId,
        address.recipientName(),
        nz(address.recipientPhone()),
        nz(address.countryCode()),
        nz(address.province()),
        nz(address.city()),
        nz(address.district()),
        address.detail(),
        nz(address.postalCode()),
        nz(address.note()),
        dbNow(),
        dbNow());
  }

  /** 发货地址替换（编辑时）：有就改，没有就删。 */
  private void replaceAddress(UUID orderId, PurchaseOrderEditAddressInput address) {
    Record existing =
        sql.fetchOne(
            "select id from purchase_order_ship_address where order_id=?::uuid", orderId);
    if (address == null) {
      if (existing != null) {
        sql.execute(
            "delete from purchase_order_ship_address where id=?::uuid",
            existing.get("id", UUID.class));
      }
      return;
    }
    if (existing == null) {
      writeAddress(orderId, address);
      return;
    }
    sql.execute(
        "update purchase_order_ship_address set recipient_name=?::varchar,recipient_phone=?::varchar,"
            + " country_code=?::varchar,province=?::varchar,city=?::varchar,district=?::varchar,"
            + " detail=?::varchar,postal_code=?::varchar,note=?::varchar,updated_at=?::timestamptz"
            + " where id=?::uuid",
        address.recipientName(),
        nz(address.recipientPhone()),
        nz(address.countryCode()),
        nz(address.province()),
        nz(address.city()),
        nz(address.district()),
        address.detail(),
        nz(address.postalCode()),
        nz(address.note()),
        dbNow(),
        existing.get("id", UUID.class));
  }

  /** 附件写入（新建时）。 */
  private void writeAttachments(
      UUID orderId, List<PurchaseOrderEditAttachmentInput> attachments, String bearer) {
    if (attachments == null || attachments.isEmpty()) {
      return;
    }
    for (PurchaseOrderEditAttachmentInput attachment : attachments) {
      PurchaseOrderMediaPort.MediaView view = media.media(attachment.mediaId(), bearer);
      sql.execute(
          "insert into purchase_order_attachment(id,tenant_id,order_id,media_id,media_name,media_type,"
              + " mime_type,byte_size,storage_key,original_filename,attachment_type,note,created_at,updated_at)"
              + " values (?::uuid,?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::numeric,"
              + " ?::varchar,?::varchar,?::varchar,?::varchar,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          currentTenant(),
          orderId,
          view.id(),
          nz(view.name()),
          nz(view.mediaType()),
          nz(view.mimeType()),
          view.byteSize(),
          nz(view.storageKey()),
          nz(view.originalFilename()),
          isBlank(attachment.attachmentType()) ? "OTHER" : attachment.attachmentType().trim(),
          nz(attachment.note()),
          dbNow(),
          dbNow());
    }
  }

  /** 附件替换（编辑时）：同一个素材保留并改说明，多余的删掉，新增的补上。 */
  private void replaceAttachments(
      UUID orderId, List<PurchaseOrderEditAttachmentInput> attachments, String bearer) {
    List<Record> existing =
        sql.fetch(
            "select * from purchase_order_attachment where order_id=?::uuid order by created_at, id",
            orderId);
    if (attachments == null) {
      for (Record row : existing) {
        sql.execute(
            "delete from purchase_order_attachment where id=?::uuid", row.get("id", UUID.class));
      }
      return;
    }
    Set<UUID> kept = new LinkedHashSet<>();
    for (PurchaseOrderEditAttachmentInput attachment : attachments) {
      PurchaseOrderMediaPort.MediaView view = media.media(attachment.mediaId(), bearer);
      Record match = null;
      for (Record row : existing) {
        if (attachment.mediaId().equals(row.get("media_id", UUID.class))
            && !kept.contains(row.get("media_id", UUID.class))) {
          match = row;
          break;
        }
      }
      if (match != null) {
        kept.add(match.get("media_id", UUID.class));
        sql.execute(
            "update purchase_order_attachment set media_name=?::varchar,media_type=?::varchar,"
                + " mime_type=?::varchar,byte_size=?::numeric,storage_key=?::varchar,"
                + " original_filename=?::varchar,attachment_type=?::varchar,note=?::varchar,"
                + " updated_at=?::timestamptz where id=?::uuid",
            nz(view.name()),
            nz(view.mediaType()),
            nz(view.mimeType()),
            view.byteSize(),
            nz(view.storageKey()),
            nz(view.originalFilename()),
            isBlank(attachment.attachmentType()) ? "OTHER" : attachment.attachmentType().trim(),
            nz(attachment.note()),
            dbNow(),
            match.get("id", UUID.class));
      } else {
        kept.add(attachment.mediaId());
        sql.execute(
            "insert into purchase_order_attachment(id,tenant_id,order_id,media_id,media_name,media_type,"
                + " mime_type,byte_size,storage_key,original_filename,attachment_type,note,created_at,updated_at)"
                + " values (?::uuid,?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::numeric,"
                + " ?::varchar,?::varchar,?::varchar,?::varchar,?::timestamptz,?::timestamptz)",
            UUID.randomUUID(),
            currentTenant(),
            orderId,
            view.id(),
            nz(view.name()),
            nz(view.mediaType()),
            nz(view.mimeType()),
            view.byteSize(),
            nz(view.storageKey()),
            nz(view.originalFilename()),
            isBlank(attachment.attachmentType()) ? "OTHER" : attachment.attachmentType().trim(),
            nz(attachment.note()),
            dbNow(),
            dbNow());
      }
    }
    for (Record row : existing) {
      if (!kept.contains(row.get("media_id", UUID.class))) {
        sql.execute(
            "delete from purchase_order_attachment where id=?::uuid", row.get("id", UUID.class));
      }
    }
  }

  /** 留痕（追加式；6.3.1.1 的表，INSERT 权限是上游授予的）。 */
  private void appendEvent(
      UUID orderId, String eventType, String fromStatus, String toStatus, String note, UUID actorId,
      OffsetDateTime now) {
    sql.execute(
        "insert into purchase_order_event(id,tenant_id,order_id,event_type,from_status,to_status,note,"
            + " actor_id,occurred_at,created_at) values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,"
            + "?::varchar,?::varchar,?::uuid,?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        orderId,
        eventType,
        fromStatus,
        toStatus,
        nz(note),
        actorId,
        now,
        now);
  }

  // ================================================================ 内部：快照

  /**
   * 冻结一行：单位换算 + 报价 + 库存。
   *
   * <p>🔴 <b>报价歧义直接 409</b>（catalog 的严格取价出口）—— 采购单不能带着一条
   * 「系统随便选的价」发出去。
   */
  private PurchaseOrderEditLineSnapshot freezeLine(
      Record line, UUID partyId, String currency, OffsetDateTime now, String bearer) {
    UUID skuId = line.get("sku_id", UUID.class);
    BigDecimal quantity = line.get("quantity", BigDecimal.class);
    String unitCode = nz(line.get("unit_code", String.class));
    String priceSource = nz(line.get("price_source", String.class));

    // ---- 单位换算（3.1.2.4）----
    String baseUnit = "";
    String purchaseUnit = unitCode;
    BigDecimal quantityPerBase = null;
    BigDecimal baseQuantity = null;
    String conversionStatus = "UNKNOWN";
    if (skuId != null) {
      try {
        ReplenishmentCatalogPort.SkuView sku = catalog.sku(skuId, bearer);
        baseUnit = nz(sku.baseUnit());
        for (ReplenishmentCatalogPort.ConversionView conversion : sku.unitConversions()) {
          if (conversion == null || !"PURCHASE".equals(conversion.role())) {
            continue;
          }
          if (conversion.quantityPerBase() == null || conversion.quantityPerBase().signum() <= 0) {
            continue;
          }
          if (!unitCode.isEmpty()
              && conversion.unitCode() != null
              && !unitCode.equals(conversion.unitCode())) {
            continue;
          }
          quantityPerBase = conversion.quantityPerBase();
          if (conversion.unitCode() != null) {
            purchaseUnit = conversion.unitCode();
          }
          break;
        }
        if (quantityPerBase != null) {
          conversionStatus = "CATALOG_UNIT_CONVERSION";
          baseQuantity = quantity.multiply(quantityPerBase).setScale(6, RoundingMode.HALF_UP);
        }
      } catch (RuntimeException e) {
        // 取不到就是 UNKNOWN + null：**绝不猜成 1**。
        conversionStatus = "UNKNOWN";
      }
    }

    // ---- 报价（3.6.2.1，严格取价）----
    UUID quoteId = null;
    String quoteSupplierName = "";
    String quoteCurrency = null;
    BigDecimal quoteUnitPrice = null;
    BigDecimal quoteMoq = null;
    Integer quoteLeadTime = null;
    LocalDate quoteValidFrom = null;
    LocalDate quoteValidTo = null;
    if ("QUOTE".equals(priceSource) && skuId != null && partyId != null) {
      ReplenishmentCatalogPort.EffectiveResult resolved =
          catalog.resolve(skuId, partyId, dbToday(), quantity, bearer);
      if (resolved.selected() != null) {
        quoteId = resolved.selected().id();
        quoteSupplierName = nz(resolved.selected().supplierName());
        quoteCurrency = resolved.selected().currency();
        quoteMoq = resolved.selected().moqQuantity();
        quoteLeadTime = resolved.selected().leadTimeDays();
        quoteValidFrom = resolved.selected().validFrom();
        quoteValidTo = resolved.selected().validTo();
        if (resolved.selectedTier() != null) {
          quoteUnitPrice = resolved.selectedTier().unitPrice();
        }
      }
    }

    // ---- 库存（08 仓储）----
    BigDecimal available = null;
    BigDecimal sellable = null;
    OffsetDateTime stockAt = null;
    String stockStatus = "UNAVAILABLE";
    if (skuId != null) {
      try {
        StockoutInventoryPort.SellableRow row = inventory.sellableForSku(skuId, bearer);
        if (row != null) {
          available = row.available();
          sellable = row.sellable();
          stockAt = now;
          stockStatus = "CAPTURED";
        }
      } catch (RuntimeException e) {
        stockStatus = "UNAVAILABLE";
      }
    }

    return new PurchaseOrderEditLineSnapshot(
        baseUnit,
        purchaseUnit,
        quantityPerBase,
        baseQuantity,
        conversionStatus,
        quoteId,
        quoteSupplierName,
        quoteCurrency,
        quoteUnitPrice,
        quoteMoq,
        quoteLeadTime,
        quoteValidFrom,
        quoteValidTo,
        available,
        sellable,
        stockAt,
        stockStatus,
        null,
        null,
        "NOT_AVAILABLE",
        now);
  }

  /** 汇率快照捕获结果。 */
  private record FxSnapshot(
      String functionalCurrency, BigDecimal rate, LocalDate effectiveDate, String status) {

    boolean captured() {
      return functionalCurrency != null && rate != null;
    }
  }

  /**
   * 捕获本位币与汇率快照（<b>提交那一刻</b>冻结）。
   *
   * <p>口径照 6.5.2.1 采购分析与 6.3.1.1 的先例：本位币来自 catalog 的法人档案，
   * 汇率取「发生日当天或之前最新的一条」。
   *
   * <p>⚠️ <b>取不到就明确记 UNAVAILABLE，绝不按 1:1 或固定汇率填一个数</b> ——
   * 一个假汇率会让「跨币种合计」变成一个看起来能对账、其实对不上的数。
   * 也不会因为汇率服务不可达就<b>拒绝提交</b>：采购单本身是对供应商的承诺，
   * 它不该被一个「算合计用」的辅助能力卡住。
   */
  private FxSnapshot captureFx(String currency, UUID legalEntityId, String bearer) {
    if (legalEntityId == null) {
      return new FxSnapshot(null, null, null, "NOT_APPLICABLE");
    }
    String functional;
    try {
      functional =
          rates.functionalCurrencies(bearer).stream()
              .filter(r -> legalEntityId.equals(r.legalEntityId()))
              .map(r -> r.baseCurrency())
              .findFirst()
              .orElse(null);
    } catch (RuntimeException e) {
      return new FxSnapshot(null, null, null, "UNAVAILABLE");
    }
    if (functional == null) {
      return new FxSnapshot(null, null, null, "UNAVAILABLE");
    }
    if (functional.equals(currency)) {
      // 本位币就是本单币种：不需要折算，汇率恒为 1（这不是「固定汇率」，是恒等式）。
      return new FxSnapshot(functional, BigDecimal.ONE.setScale(FX_SCALE, RoundingMode.HALF_UP), dbToday(), "CAPTURED");
    }
    LocalDate today = dbToday();
    try {
      List<PurchaseAnalyticsCatalogPort.RateView> found =
          rates.rates(currency, functional, today.minusDays(30), today, bearer);
      return found.stream()
          .filter(r -> r.effectiveDate() != null && !r.effectiveDate().isAfter(today))
          .max((a, b) -> a.effectiveDate().compareTo(b.effectiveDate()))
          .map(
              r ->
                  new FxSnapshot(
                      functional,
                      r.rate().setScale(FX_SCALE, RoundingMode.HALF_UP),
                      r.effectiveDate(),
                      "CAPTURED"))
          .orElseGet(() -> new FxSnapshot(functional, null, null, "UNAVAILABLE"));
    } catch (RuntimeException e) {
      return new FxSnapshot(functional, null, null, "UNAVAILABLE");
    }
  }

  // ================================================================ 内部：读模型

  /** 组装编辑表单。 */
  private PurchaseOrderEditForm loadForm(
      UUID orderId, UUID actorId, UUID actorDeptId, String scope, String bearer) {
    Record order = requireOrder(orderId, actorId, actorDeptId, scope);
    UUID tenantId = currentTenant();

    // ---- 行 + 冻结快照 ----
    Map<Integer, Record> snapshots = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select * from purchase_order_line_snapshot where order_id=?::uuid order by line_no",
            orderId)) {
      snapshots.put(r.get("line_no", Integer.class), r);
    }
    List<PurchaseOrderEditLine> lines = new ArrayList<>();
    BigDecimal totalQuantity = BigDecimal.ZERO;
    for (Record line :
        sql.fetch(
            "select * from purchase_order_line where order_id=?::uuid order by line_no", orderId)) {
      int lineNo = line.get("line_no", Integer.class);
      BigDecimal quantity = line.get("quantity", BigDecimal.class);
      totalQuantity = totalQuantity.add(quantity);
      Record snapshot = snapshots.get(lineNo);
      lines.add(
          new PurchaseOrderEditLine(
              lineNo,
              line.get("sku_id", UUID.class),
              nz(line.get("sku_code", String.class)),
              nz(line.get("sku_name", String.class)),
              quantity,
              nz(line.get("unit_code", String.class)),
              line.get("unit_price", BigDecimal.class),
              line.get("line_amount", BigDecimal.class),
              nz(line.get("price_source", String.class)),
              snapshot == null ? null : snapshot.get("quote_id", UUID.class),
              snapshot == null ? null : toSnapshot(snapshot)));
    }

    // ---- 地址 ----
    Record address =
        sql.fetchOne("select * from purchase_order_ship_address where order_id=?::uuid", orderId);
    PurchaseOrderEditAddress shipAddress =
        address == null
            ? null
            : new PurchaseOrderEditAddress(
                nz(address.get("recipient_name", String.class)),
                nz(address.get("recipient_phone", String.class)),
                nz(address.get("country_code", String.class)),
                nz(address.get("province", String.class)),
                nz(address.get("city", String.class)),
                nz(address.get("district", String.class)),
                nz(address.get("detail", String.class)),
                nz(address.get("postal_code", String.class)),
                nz(address.get("note", String.class)));

    // ---- 附件 ----
    List<PurchaseOrderEditAttachment> attachments = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select * from purchase_order_attachment where order_id=?::uuid order by created_at, id",
            orderId)) {
      attachments.add(
          new PurchaseOrderEditAttachment(
              r.get("id", UUID.class),
              r.get("media_id", UUID.class),
              nz(r.get("media_name", String.class)),
              nz(r.get("media_type", String.class)),
              nz(r.get("mime_type", String.class)),
              r.get("byte_size", BigDecimal.class),
              nz(r.get("storage_key", String.class)),
              nz(r.get("original_filename", String.class)),
              nz(r.get("attachment_type", String.class)),
              nz(r.get("note", String.class))));
    }

    // ---- 提交快照（未提交时没有）----
    Record submit =
        sql.fetchOne("select * from purchase_order_submit_snapshot where order_id=?::uuid", orderId);
    String status = order.get("status", String.class);
    boolean editable = STATUS_DRAFT.equals(status);
    BigDecimal totalAmount = order.get("total_amount", BigDecimal.class);
    String currency = order.get("currency", String.class);
    BigDecimal rate =
        submit == null ? null : submit.get("fx_rate_to_functional", BigDecimal.class);
    BigDecimal functionalAmount =
        rate == null ? null : totalAmount.multiply(rate).setScale(6, RoundingMode.HALF_UP);

    PurchaseOrderEditAmounts amounts =
        new PurchaseOrderEditAmounts(
            totalAmount,
            currency,
            order.get("line_count", Integer.class),
            totalQuantity,
            null,
            null,
            "NOT_AVAILABLE",
            TAX_NOTE,
            order.get("functional_currency", String.class),
            rate,
            submit == null ? null : submit.get("fx_rate_effective_date", LocalDate.class),
            submit == null ? "NOT_APPLICABLE" : nz(submit.get("fx_status", String.class)),
            functionalAmount,
            submit == null
                ? "还没提交：单价、汇率与单位换算的快照在**提交那一刻**才冻结（蓝图加粗句）。"
                : functionalAmount == null
                    ? "总额按原币种报；本位币折算取不到汇率快照，**不给一个凑出来的数**。"
                    : "总额按原币种报；本位币折算用提交那一刻冻结的汇率快照。");

    // ---- 草稿来源（6.2.2.2）----
    Record source =
        sql.fetchOne("select * from purchase_order_draft_source where order_id=?::uuid", orderId);

    return new PurchaseOrderEditForm(
        order.get("id", UUID.class),
        order.get("order_no", String.class),
        status,
        statusLabel(status),
        nz(order.get("source_type", String.class)),
        source == null ? null : nz(source.get("source_draft_no", String.class)),
        nz(order.get("source_request_no", String.class)),
        order.get("supplier_ref_id", UUID.class),
        nz(order.get("supplier_code", String.class)),
        nz(order.get("supplier_name", String.class)),
        order.get("legal_entity_id", UUID.class),
        nz(order.get("legal_entity_code", String.class)),
        nz(order.get("legal_entity_name", String.class)),
        currency,
        order.get("target_warehouse_id", UUID.class),
        nz(order.get("target_warehouse_code", String.class)),
        nz(order.get("target_warehouse_name", String.class)),
        order.get("promised_arrival_date", LocalDate.class),
        nz(order.get("note", String.class)),
        order.get("version", Long.class),
        editable,
        editable
            ? "草稿可改：增行、改行、删行都在这一份表单里整体替换。"
            : "已提交的采购单不可直接改数量与单价（6.3.1.3 口径）：要改请走变更单或红冲重下。",
        amounts,
        lines,
        shipAddress,
        attachments,
        order.get("created_at", OffsetDateTime.class),
        order.get("updated_at", OffsetDateTime.class));
  }

  /** 快照行 → 传输模型。 */
  private PurchaseOrderEditLineSnapshot toSnapshot(Record r) {
    return new PurchaseOrderEditLineSnapshot(
        nz(r.get("base_unit_code", String.class)),
        nz(r.get("purchase_unit_code", String.class)),
        r.get("quantity_per_base", BigDecimal.class),
        r.get("base_quantity", BigDecimal.class),
        nz(r.get("conversion_status", String.class)),
        r.get("quote_id", UUID.class),
        nz(r.get("quote_supplier_name", String.class)),
        r.get("quote_currency", String.class),
        r.get("quote_unit_price", BigDecimal.class),
        r.get("quote_moq_quantity", BigDecimal.class),
        r.get("quote_lead_time_days", Integer.class),
        r.get("quote_valid_from", LocalDate.class),
        r.get("quote_valid_to", LocalDate.class),
        r.get("stock_available_qty", BigDecimal.class),
        r.get("stock_sellable_qty", BigDecimal.class),
        r.get("stock_snapshot_at", OffsetDateTime.class),
        nz(r.get("stock_status", String.class)),
        r.get("tax_rate", BigDecimal.class),
        r.get("tax_amount", BigDecimal.class),
        nz(r.get("tax_status", String.class)),
        r.get("snapshot_at", OffsetDateTime.class));
  }

  // ================================================================ 内部：工具

  /** 命中候选的阶梯价（catalog 只给 selected 的阶梯，这里按 quoteId 对齐）。 */
  private BigDecimal tierPrice(ReplenishmentCatalogPort.EffectiveResult result, UUID quoteId) {
    if (result.selected() != null && quoteId.equals(result.selected().id())) {
      return result.selectedTier() == null ? null : result.selectedTier().unitPrice();
    }
    return null;
  }

  private BigDecimal tierMin(ReplenishmentCatalogPort.EffectiveResult result, UUID quoteId) {
    if (result.selected() != null && quoteId.equals(result.selected().id())) {
      return result.selectedTier() == null ? null : result.selectedTier().minQuantity();
    }
    return null;
  }

  private String priceSource(PurchaseOrderEditLineInput line) {
    String source = isBlank(line.priceSource()) ? "MANUAL" : line.priceSource().trim().toUpperCase();
    return "QUOTE".equals(source) ? "QUOTE" : "MANUAL";
  }

  private String statusLabel(String status) {
    return switch (status) {
      case "DRAFT" -> "草稿";
      case "PENDING_APPROVAL" -> "待审批";
      case "APPROVED" -> "已审批";
      case "REJECTED" -> "已驳回";
      case "IN_FULFILLMENT" -> "执行中";
      case "CLOSED" -> "已关闭";
      default -> status;
    };
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private OffsetDateTime dbNow() {
    return sql.fetchOne("select now()").get(0, OffsetDateTime.class);
  }

  /** 数据库口径的今天（UTC 日历日）。 */
  private LocalDate dbToday() {
    return sql.fetchOne("select (now() at time zone 'UTC')::date").get(0, LocalDate.class);
  }

  /** 当前绑定的租户（由 tenancy 库在事务内设置，<b>不是</b>客户端头里的那个）。 */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant', true),'')::uuid")
        .get(0, UUID.class);
  }

  private static boolean isBlank(String v) {
    return v == null || v.isBlank();
  }

  private static String nz(String v) {
    return v == null ? "" : v;
  }

  /** 税额的说明（口径钉死在代码里，界面上原样透出）。 */
  private static final String TAX_NOTE =
      "税额由 12 财务计算：税率的权威在财务侧，而当前仓库里**没有 finance 服务**"
          + "（backend/services/ 下只有 identity / catalog / channel / crm / trade / warehouse）"
          + "⇒ 本模块不造税率表、不跨库读财务，只算**本位币折算快照**（有汇率就算折算，不算税）。";
}
