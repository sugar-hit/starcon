package com.starcon.finance;

import static com.starcon.finance.CostCalculationModels.*;
import static com.starcon.finance.CostValuationModels.FinanceCostValuationMethodOption;
import static com.starcon.finance.CostValuationModels.ValuationMethod;
import static com.starcon.finance.CostValuationModels.ValuationScopeType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
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
 * 12.4.1.2 成本计算执行。
 *
 * <p>蓝图 12-finance.md：「执行成本计算：对销售出库、发货、平台仓转出计算应结转成本，生成成本层与成本分录；支持按期间批量重算与差异对比。」输入：「库存流水（7.1.2.1）；计价方法；成本层数据；计算期间。」输出：「成本计算结果（成本层 + 结转金额）+ 差异对比。」关系：「上游 = 7 库存流水、12.4.1.1；下游 = 13 利润、12.5.2 凭证。<b>数量账在 warehouse、价值账在 finance</b>——这是两个服务的职责划分，<b>重算不能写库存</b>。」
 *
 * <p><b>🔴 数量账在 warehouse、价值账在 finance。</b>本服务从 warehouse 只**读**出库数量
 * （{@link CostCalculationFlowPort}），把成本（价值）算出来写进本服务的表；
 * <b>一条库存流水都不改</b> —— 重算改的是「应结转多少成本」，不是「仓库里还剩多少」。
 *
 * <p><b>🔴 计价方法只有一个真相</b>：方法来自 12.4.1.1 的 {@code finance_cost_valuation_method}
 * （同服务同库，直接查），本模块**不另抄一份配置**；只在批次行上记**快照**
 * （方法 + 版本号 + 配置行 ID），这样半年后仍能回答「这批成本当时是用哪一版方法算的」。
 *
 * <p><b>🔴 算不出来 ≠ 0</b>：成本层不够时，出库量被拆成「已覆盖」与「缺口」，
 * 分录状态置 INCOMPLETE、单价留空、缺口原因如实写出 ——
 * 绝不显示 0 冒充「成本是 0」，也不拿半截成本当成完整单价。
 *
 * <p><b>🔴 重算 = 另起批次</b>：旧批次是当时那笔计算的证据，不被改写；
 * 新批次把旧批次挂进重算链，差异对比才有意义。
 *
 * <p><b>金额与数量</b>：金额 {@code numeric(19,4)}、数量 {@code numeric(19,6)}，
 * 全程 BigDecimal，无浮点；时间列 {@code timestamptz} 一律 OffsetDateTime + {@code ?::timestamptz}。
 */
@Service
public class CostCalculationService {

  /** 一页最大条数（与同服务其它模块一致）。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 金额小数位（与同服务 12.4.1.1 / 12.4.2.1 一致）。 */
  static final int MONEY_SCALE = 4;

  /** 数量小数位（AGENTS.md）。 */
  static final int QUANTITY_SCALE = 6;

  /** 成本层窗口默认长度（天）：期初就有的货也会在期间内被卖掉，所以窗口比期间宽。 */
  static final int DEFAULT_WINDOW_DAYS = 365;

  /** 单批次最多处理多少笔出库业务（防止一个期间把服务拖垮；超了**明确报错**，不静默截断）。 */
  static final int MAX_FLOWS_PER_RUN = 500;

  /** 读上游流水时的分页大小（warehouse 台账每页上限 200）。 */
  static final int FLOW_PAGE_SIZE = 200;

  /**
   * 出库类业务类型（warehouse {@code inventory_transaction.kind}）。
   *
   * <p>蓝图点名的三类：销售出库、发货（⇒ kind=SHIPMENT）、平台仓转出（⇒ kind=TRANSFER_OUT）。
   * 入库 / 预占 / 释放 / 调整**不是**成本结转事件，不进本模块。
   */
  static final List<String> OUTBOUND_KINDS = List.of("SHIPMENT", "TRANSFER_OUT");

  /** 发货交接单（{@code ref_type}）：区分「发货」与「销售出库」。 */
  static final String SHIPMENT_HANDOVER_REF_TYPE = "SHIPMENT_HANDOVER";

  private final DSLContext sql;
  private final CostValuationLayerPort layers;
  private final CostCalculationFlowPort flows;

  public CostCalculationService(
      DSLContext sql, CostValuationLayerPort layers, CostCalculationFlowPort flows) {
    this.sql = sql;
    this.layers = layers;
    this.flows = flows;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**。
   *
   * <p>⚠️ 本模块的缺口要说清归谁：12.4.1.3 成本差异分析在**同一个服务**里但未交付；
   * 13 利润在**别的服务**；12.5.2 凭证已交付但**不写凭证**。
   */
  public FinanceCostCalculationCapabilities capabilities() {
    var capabilities = new ArrayList<FinanceCostCalculationCapability>();
    var gaps = new ArrayList<FinanceCostCalculationDownstreamGap>();

    capabilities.add(
        new FinanceCostCalculationCapability(
            "12.4.1.2",
            "成本计算执行（按期间算应结转成本）",
            true,
            "finance_cost_calculation_run + finance_cost_calculation_layer"
                + " + finance_cost_calculation_entry；上游读 warehouse 7.1.2.1 库存流水",
            "",
            "对销售出库、发货、平台仓转出计算应结转成本，逐笔落成成本层与成本分录。"
                + "数量从 warehouse 的真实台账读（只读），成本在本服务算 —— "
                + "**数量账在 warehouse、价值账在 finance**，重算一条库存流水都不改。"));

    capabilities.add(
        new FinanceCostCalculationCapability(
            "12.4.1.2",
            "成本层（按批次扣减的口径）",
            true,
            "finance_cost_calculation_layer（唯一键 = 批次 + SKU + 层序号）",
            "",
            "成本层来自 catalog 5.2.1.1 的**真实到货记录**（经 CostValuationLayerPort 只读），"
                + "不是造出来的示例成本。层行记「可消耗量 / 原始金额 / 本次实际采用的单价 / 实际消耗量」。"
                + "移动加权平均与指定成本法下，**采用的单价 ≠ 该层的原始进价**，"
                + "原始进价在 layer_amount 里查得到。"));

    capabilities.add(
        new FinanceCostCalculationCapability(
            "12.4.1.2",
            "成本分录（按出库记账的口径：应结转成本）",
            true,
            "finance_cost_calculation_entry（唯一键 = 批次 + 流水 + SKU）",
            "",
            "一笔出库的一个 SKU = 一行。覆盖量 + 缺口量 = 出库量由**触发器**钉死；"
                + "有缺口 ⇒ 状态 INCOMPLETE 且**单价留空**、原因如实写出 —— "
                + "半截成本不会被当成完整单价。"));

    capabilities.add(
        new FinanceCostCalculationCapability(
            "12.4.1.2",
            "按期间批量重算",
            true,
            "POST /finance/cost-calculation/runs/{id}/recalculate（重算 = 另起批次）",
            "",
            "重算的单位是**期间**：一次请求把该期间内的全部出库按当前配置的方法重算一遍。"
                + "旧批次**不被改写**（触发器钉死），新批次把旧批次挂进重算链。"));

    capabilities.add(
        new FinanceCostCalculationCapability(
            "12.4.1.2",
            "差异对比（两次批次之间）",
            true,
            "GET /finance/cost-calculation/runs/compare（按 SKU 聚合，服务层现算）",
            "",
            "逐 SKU 对比两次批次的出库量与结转金额，并给出总额差额。"
                + "⚠️ 这是**两次计算批次之间**的对比，**不是** 12.4.1.3 的成本差异分析报表；"
                + "差异数**不落第四张表** —— 再存一份就是第二个真相来源。"));

    capabilities.add(
        new FinanceCostCalculationCapability(
            "7.1.2.1",
            "库存流水（数量账的来源）",
            true,
            "warehouse GET /warehouse/inventory/ledger + /{transactionId}（CostCalculationFlowPort 只读）",
            "",
            "出库数量来自 warehouse 的**真实带符号分录**（取绝对值），"
                + "期间左闭右开。上游读不到一律 503，写明「读不到」而不是「这个期间没有出库」。"));

    capabilities.add(
        new FinanceCostCalculationCapability(
            "12.4.1.1",
            "计价方法（唯一真相）",
            true,
            "finance_cost_valuation_method（同服务同库直接查）+ 指定成本单价表",
            "",
            "方法与生效版本来自 12.4.1.1 的版本链，**本模块不另抄一份配置**，"
                + "只在批次行上记快照。重算成功后把该范围生效版本的 "
                + "recalculation_status 由 PENDING 改回 NOT_REQUIRED（**只改取值，不改表结构**）。"));

    capabilities.add(
        new FinanceCostCalculationCapability(
            "5.2.1.1",
            "成本构成（成本层的来源）",
            true,
            "catalog GET /catalog/sku-cost-compositions/{skuId}（CostValuationLayerPort 只读）",
            "",
            "成本层是 catalog 从**真实到货记录**派生的批次清单（到货日 / 数量 / 金额 / 单价）。"
                + "异币种的层被排除并如实报出 —— **绝不跨币种相加**。"));

    gaps.add(
        new FinanceCostCalculationDownstreamGap(
            "12.4.1.3",
            "成本差异分析",
            "finance",
            false,
            "下游",
            "🔴 **未交付**。本模块的「差异对比」是**两次计算批次之间**的逐 SKU 对比，"
                + "**不产出**任何差异分析报表（透视、口径归因等）。"));

    gaps.add(
        new FinanceCostCalculationDownstreamGap(
            "12.5.2",
            "凭证",
            "finance",
            true,
            "下游",
            "凭证已交付，但**本模块不写凭证** —— 每张事实表只有一个写入所有者。"
                + "凭证取本模块的 cost_amount 作为借方金额。"));

    gaps.add(
        new FinanceCostCalculationDownstreamGap(
            "13",
            "利润",
            "reporting",
            false,
            "下游",
            "🔴 **未交付**。本模块产出**应结转成本**（按 SKU、按期间），"
                + "利润自己去取；本模块**不产出任何利润数字**。"));

    return new FinanceCostCalculationCapabilities(capabilities, gaps);
  }

  // ================================================================ 选项

  /** 筛选项下拉：方法选项复用 12.4.1.1，其余来自本模块的真实数据。 */
  public FinanceCostCalculationOptions options() {
    var methods = new ArrayList<FinanceCostValuationMethodOption>();
    for (var m : ValuationMethod.values()) {
      methods.add(new FinanceCostValuationMethodOption(m.name(), m.label(), m.basis()));
    }
    var businessTypes = new ArrayList<String>();
    for (var t : CalculationBusinessType.values()) businessTypes.add(t.name());
    var statuses = new ArrayList<String>();
    for (var s : CalculationRunStatus.values()) statuses.add(s.name());
    var reasons = new ArrayList<String>();
    for (var r : CalculationReason.values()) reasons.add(r.name());
    var currencies =
        sql.fetch("select distinct currency from finance_cost_calculation_run order by currency")
            .stream()
            .map(r -> r.get(0, String.class))
            .filter(java.util.Objects::nonNull)
            .toList();
    return new FinanceCostCalculationOptions(methods, businessTypes, statuses, reasons, currencies);
  }

  // ================================================================ 执行成本计算

  /**
   * 执行一次成本计算：期间内的销售出库 / 发货 / 平台仓转出 → 成本层 + 成本分录。
   *
   * <p>⚠️ **上游读不到一律 503**（{@link CostCalculationFlowPort} / {@link CostValuationLayerPort}），
   * 绝不返回空结果冒充「这个期间没有出库」。
   */
  public FinanceCostCalculationRunRow calculate(
      FinanceCostCalculationRequest request, AccessService.Access actor, String bearer) {
    var scopeType = ValuationScopeType.parse(request.scopeType());
    if (scopeType == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "适用范围只能是 LEGAL_ENTITY（按法人）或 CATEGORY（按品类）");
    }
    if (request.scopeRefId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定计价范围（法人或品类）");
    }
    if (request.periodFrom() == null || request.periodTo() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出计算期间（起止都要）");
    }
    if (!request.periodTo().isAfter(request.periodFrom())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "计算期间的止必须晚于起（期间是左闭右开的，空区间不是计算）");
    }

    // 计价方法：默认取 12.4.1.1 在**期间起点**生效的那一版；请求里显式给了就以请求为准（覆盖）。
    var configured = effectiveConfig(scopeType, request.scopeRefId(), request.periodFrom());
    var requested = ValuationMethod.parse(request.valuationMethod());
    if (configured == null && requested == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "该范围在期间起点没有生效的计价方法配置：请先在 12.4.1.1 登记计价方法，"
              + "或在本次计算里显式指定计价方法");
    }
    var method = requested != null ? requested : configured.method();
    var overridden = requested != null && configured != null && requested != configured.method();

    // 币种：绝不跨币种相加 —— 配置的币种与请求的币种不一致就是错误，不许静默按其中一个算。
    var currency = request.currency() == null ? "" : request.currency().trim().toUpperCase(Locale.ROOT);
    if (configured != null && !currency.isEmpty() && !currency.equals(configured.currency())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "计价币种与 12.4.1.1 的配置不一致（配置是 "
              + configured.currency()
              + "，本次给的是 "
              + currency
              + "）—— 成本绝不跨币种相加");
    }
    if (currency.isEmpty() && configured != null) currency = configured.currency();
    if (currency.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出计价币种（ISO 4217 三位大写字母）");
    }
    if (!currency.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "计价币种必须是三位大写字母（ISO 4217）");
    }

    var tenant = currentTenant();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var reason = CalculationReason.parse(request.reason());
    if (reason == null) reason = CalculationReason.INITIAL;

    // ---- 1. 读出库流水（warehouse 7.1.2.1，只读）----
    var issues = readOutboundIssues(request.periodFrom(), request.periodTo(), bearer);

    // ---- 2. 逐 SKU 建成本层池（catalog 5.2.1.1）----
    var windowFrom = request.periodFrom().atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
    var windowTo = request.periodTo().atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
    var costWindowFrom = windowFrom.minusDays(DEFAULT_WINDOW_DAYS);
    var pools = new LinkedHashMap<UUID, LayerPool>();
    var notes = new StringBuilder();
    var skippedCurrencyLayers = 0;
    var skusWithoutLayers = 0;

    for (var skuId : issues.stream().map(i -> i.skuId()).distinct().toList()) {
      var composition =
          layers.composition(skuId, currency, costWindowFrom, windowTo, bearer);
      var pool = LayerPool.of(skuId, composition, currency);
      skippedCurrencyLayers += pool.skippedCurrency();
      if (pool.lots().isEmpty()) skusWithoutLayers++;
      pools.put(skuId, pool);
    }

    // ---- 3. 按时间顺序消耗成本层，生成成本分录 ----
    var entries = new ArrayList<FinanceCostCalculationEntryRow>();
    var layerRows = new ArrayList<LayerUsage>();
    for (var issue : issues) {
      var pool = pools.get(issue.skuId());
      var usage = consume(pool, issue.quantity(), method, currency, request.batchRef());
      var costAmount = usage.amount().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
      var covered = usage.quantity().setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
      var issueQty = issue.quantity().setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
      var shortfall = issueQty.subtract(covered).setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
      var gapNote = new StringBuilder();
      if (shortfall.signum() > 0) {
        if (pool.lots().isEmpty() && pool.skippedCurrency() == 0) {
          gapNote.append("该 SKU 在成本窗口内没有可用成本层（上游成本构成为空）。");
        } else if (method == ValuationMethod.BATCH) {
          gapNote.append("批次成本法指定的批次在成本窗口内不存在或数量不足。");
        } else if (method == ValuationMethod.SPECIFIED) {
          gapNote.append("指定成本法未维护该 SKU 的指定单价，或指定单价适用区间不含该期间。");
        } else {
          gapNote.append("可用成本层不足：");
        }
        if (gapNote.length() > 0 && gapNote.charAt(gapNote.length() - 1) == '：') {
          gapNote.append("已结转 ").append(covered).append("，缺口 ").append(shortfall).append("。");
        }
      }
      entries.add(
          new FinanceCostCalculationEntryRow(
              null,
              null,
              issue.transactionId(),
              issue.refType(),
              issue.refId(),
              issue.kind(),
              issue.occurredAt(),
              issue.businessType().name(),
              issue.businessType().label(),
              issue.skuId(),
              issue.skuCode(),
              issueQty,
              covered,
              shortfall,
              // 有缺口 ⇒ 单价留空（半截成本不能当成完整单价）。
              shortfall.signum() > 0 ? null : costAmount.divide(covered, MONEY_SCALE, RoundingMode.HALF_UP),
              costAmount,
              currency,
              usage.layerCount(),
              shortfall.signum() > 0
                  ? CalculationEntryStatus.INCOMPLETE.name()
                  : CalculationEntryStatus.CALCULATED.name(),
              gapNote.toString()));
      layerRows.addAll(usage.layers());
    }

    // ---- 4. 落库：批次（先 PENDING）→ 成本层 → 成本分录 → 完成批次 ----
    var runId = UUID.randomUUID();
    var scopeCode = request.scopeCode() == null ? "" : request.scopeCode().trim();
    var scopeName = request.scopeName() == null ? "" : request.scopeName().trim();
    if (scopeCode.isEmpty() && configured != null) scopeCode = configured.scopeCode();
    if (scopeName.isEmpty() && configured != null) scopeName = configured.scopeName();

    var totalQuantity =
        entries.stream()
            .map(FinanceCostCalculationEntryRow::issueQuantity)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    var totalCovered =
        entries.stream()
            .map(FinanceCostCalculationEntryRow::coveredQuantity)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    var totalShortfall =
        entries.stream()
            .map(FinanceCostCalculationEntryRow::shortfallQuantity)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    var totalCost =
        entries.stream()
            .map(FinanceCostCalculationEntryRow::costAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    var skuCount = (int) entries.stream().map(FinanceCostCalculationEntryRow::skuId).distinct().count();
    var incompleteCount =
        (int)
            entries.stream()
                .filter(e -> CalculationEntryStatus.INCOMPLETE.name().equals(e.status()))
                .count();

    var basisNote =
        "结转口径：期间 "
            + request.periodFrom()
            + " ~ "
            + request.periodTo()
            + "（左闭右开）内 kind∈{SHIPMENT, TRANSFER_OUT} 的出库；计价方法 "
            + method.label()
            + "；成本层取 "
            + costWindowFrom
            + " ~ "
            + windowTo
            + " 的真实到货记录（窗口比期间宽 "
            + DEFAULT_WINDOW_DAYS
            + " 天，期初已有的货也会在期间内被卖掉）；"
            + "移动加权平均按本批次可用成本层的加权平均计价（与 12.4.1.1 试算同口径）。";
    var sourceNote = new StringBuilder();
    sourceNote.append("出库笔数 ").append(issues.size()).append("（来源 warehouse 7.1.2.1 真实台账）。");
    if (overridden) {
      sourceNote
          .append(
              "本次计算**显式指定**了计价方法 "
                  + method.name()
                  + "，与 12.4.1.1 在期间起点生效的 "
                  + configured.method().name()
                  + " 不同（按指定的方法算）。");
    }
    if (configured == null) {
      sourceNote.append("该范围在期间起点没有生效的计价方法配置，方法由本次请求指定。");
    }
    if (skippedCurrencyLayers > 0) {
      sourceNote
          .append("另有 ")
          .append(skippedCurrencyLayers)
          .append(" 层是其他币种，未参与计算（**绝不跨币种相加**）。");
    }
    if (skusWithoutLayers > 0) {
      sourceNote.append(skusWithoutLayers).append(" 个 SKU 在成本窗口内没有可用成本层。");
    }
    if (incompleteCount > 0) {
      sourceNote.append(incompleteCount).append(" 笔出库存在缺口，结转金额不完整。");
    }
    if (request.note() != null && !request.note().isBlank()) {
      sourceNote.append("备注：").append(request.note().trim());
    }

    sql.execute(
        "insert into finance_cost_calculation_run(id,tenant_id,scope_type,scope_ref_id,scope_code,"
            + " scope_name,valuation_method,valuation_method_version,valuation_method_config_id,currency,"
            + " period_from,period_to,recalculation_reason,status,flow_source_service,layer_source_service,"
            + " basis_note,source_note,created_by,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,'PENDING','warehouse','catalog',"
            + " ?,?,?,?::timestamptz)",
        runId,
        tenant,
        scopeType.name(),
        request.scopeRefId(),
        scopeCode,
        scopeName,
        method.name(),
        configured == null ? null : configured.version(),
        configured == null ? null : configured.id(),
        currency,
        request.periodFrom(),
        request.periodTo(),
        reason.name(),
        basisNote,
        sourceNote.toString(),
        actor == null ? null : actor.userId(),
        now);

    // 成本层行：按 (SKU, 层) 聚合本批次里的消耗。
    var aggregated = new LinkedHashMap<String, LayerUsage>();
    for (var usage : layerRows) {
      aggregated.merge(usage.key(), usage, LayerUsage::merge);
    }
    for (var usage : aggregated.values()) {
      sql.execute(
          "insert into finance_cost_calculation_layer(id,tenant_id,run_id,sku_id,sku_code,layer_index,"
              + " lot_ref,received_date,layer_quantity,layer_amount,unit_cost,consumed_quantity,"
              + " consumed_amount,currency,layer_source_service,created_at)"
              + " values(?,?,?,?,?,?,?,?::date,?,?,?,?,?,?,'catalog',?::timestamptz)",
          UUID.randomUUID(),
          tenant,
          runId,
          usage.skuId(),
          usage.skuCode(),
          usage.layerIndex(),
          usage.lotRef(),
          usage.receivedDate(),
          usage.layerQuantity(),
          usage.layerAmount(),
          usage.appliedUnitCost(),
          usage.consumedQuantity(),
          usage.consumedAmount(),
          usage.currency(),
          now);
    }

    for (var entry : entries) {
      sql.execute(
          "insert into finance_cost_calculation_entry(id,tenant_id,run_id,flow_transaction_id,"
              + " flow_ref_type,flow_ref_id,flow_kind,occurred_at,business_type,sku_id,sku_code,"
              + " issue_quantity,covered_quantity,shortfall_quantity,unit_cost,cost_amount,currency,"
              + " layer_count,status,gap_note,created_at)"
              + " values(?,?,?,?,?,?,?,?::timestamptz,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
          UUID.randomUUID(),
          tenant,
          runId,
          entry.flowTransactionId(),
          entry.flowRefType(),
          entry.flowRefId(),
          entry.flowKind(),
          entry.occurredAt(),
          entry.businessType(),
          entry.skuId(),
          entry.skuCode(),
          entry.issueQuantity(),
          entry.coveredQuantity(),
          entry.shortfallQuantity(),
          entry.unitCost(),
          entry.costAmount(),
          entry.currency(),
          entry.layerCount(),
          entry.status(),
          entry.gapNote(),
          now);
    }

    // 完成批次（触发器钉死：完成后的数字不可改写）。
    sql.execute(
        "update finance_cost_calculation_run set status = 'COMPLETED',"
            + " sku_count = ?, layer_count = ?, entry_count = ?, incomplete_entry_count = ?,"
            + " total_quantity = ?, total_covered_quantity = ?, total_shortfall_quantity = ?,"
            + " total_cost_amount = ?, completed_at = ?::timestamptz"
            + " where tenant_id = ? and id = ?",
        skuCount,
        aggregated.size(),
        entries.size(),
        incompleteCount,
        totalQuantity,
        totalCovered,
        totalShortfall,
        totalCost,
        now,
        tenant,
        runId);

    // 重算标记回写：12.4.1.1 的 recalculation_status 由 PENDING 改回 NOT_REQUIRED
    // （**只改取值，不改表结构** —— V355 的 CHECK 只允许这两个值）。
    clearPendingRecalculation(scopeType, request.scopeRefId());

    return run(runId);
  }

  // ================================================================ 重算

  /**
   * 按期间重算：同一个范围、同一个期间，用**当前生效**的计价方法重算一遍。
   *
   * <p>🔴 **重算 = 另起批次**：旧批次是当时那笔计算的证据，**不被改写**（触发器钉死）；
   * 新批次把旧批次挂进重算链（{@code supersedes_run_id}），旧批次回指
   * {@code superseded_by_run_id} ⇒ 差异对比因此有着落。
   */
  public FinanceCostCalculationRunRow recalculate(UUID runId, AccessService.Access actor, String bearer) {
    var previous = run(runId);
    if (!CalculationRunStatus.COMPLETED.name().equals(previous.status())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只能重算已完成的批次（当前批次状态是 " + previous.status() + "）");
    }
    var scopeType = ValuationScopeType.parse(previous.scopeType());
    var configured = effectiveConfig(scopeType, previous.scopeRefId(), previous.periodFrom());
    if (configured == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "该范围在期间起点没有生效的计价方法配置，先在 12.4.1.1 登记后才能重算");
    }
    var request =
        new FinanceCostCalculationRequest(
            previous.scopeType(),
            previous.scopeRefId(),
            previous.scopeCode(),
            previous.scopeName(),
            configured.method().name(),
            configured.currency(),
            previous.periodFrom(),
            previous.periodTo(),
            null,
            CalculationReason.METHOD_SWITCH.name(),
            "由批次 " + previous.id() + " 按当前生效的第 " + configured.version() + " 版方法重算");
    var fresh = calculate(request, actor, bearer);
    // 重算链：旧批次 ← 新批次。旧批次只改 superseded_by_run_id（触发器允许这一列）。
    sql.execute(
        "update finance_cost_calculation_run set superseded_by_run_id = ? where tenant_id = ? and id = ?",
        fresh.id(),
        currentTenant(),
        previous.id());
    sql.execute(
        "update finance_cost_calculation_run set supersedes_run_id = ? where tenant_id = ? and id = ?",
        previous.id(),
        currentTenant(),
        fresh.id());
    // ⚠️ 必须**重读**：`fresh` 是挂重算链之前读出来的行，supersedesRunId 在那时还是空。
    return run(fresh.id());
  }

  // ================================================================ 差异对比

  /**
   * 差异对比：两次批次之间逐 SKU 对比（蓝图「支持按期间批量重算与<strong>差异对比</strong>」）。
   *
   * <p>⚠️ 这是**两次计算批次之间**的对比，<b>不是</b> 12.4.1.3 的成本差异分析。
   */
  public FinanceCostCalculationVarianceComparison variance(UUID fromRunId, UUID toRunId) {
    if (fromRunId == null || toRunId == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出要对比的两个批次");
    }
    if (fromRunId.equals(toRunId)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "要对比的两个批次不能是同一个");
    }
    var from = run(fromRunId);
    var to = run(toRunId);
    if (!CalculationRunStatus.COMPLETED.name().equals(from.status())
        || !CalculationRunStatus.COMPLETED.name().equals(to.status())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "只能对比已完成的批次");
    }

    var fromRows =
        sql.fetch(
            "select sku_id, max(sku_code) as sku_code, sum(issue_quantity) as quantity,"
                + " sum(cost_amount) as cost_amount, count(*) as entry_count"
                + " from finance_cost_calculation_entry"
                + " where tenant_id = ? and run_id = ? group by sku_id",
            currentTenant(),
            fromRunId);
    var toRows =
        sql.fetch(
            "select sku_id, max(sku_code) as sku_code, sum(issue_quantity) as quantity,"
                + " sum(cost_amount) as cost_amount, count(*) as entry_count"
                + " from finance_cost_calculation_entry"
                + " where tenant_id = ? and run_id = ? group by sku_id",
            currentTenant(),
            toRunId);

    var fromMap = new LinkedHashMap<UUID, VarianceAccumulator>();
    for (var row : fromRows) fromMap.put(row.get("sku_id", UUID.class), VarianceAccumulator.of(row));
    var toMap = new LinkedHashMap<UUID, VarianceAccumulator>();
    for (var row : toRows) toMap.put(row.get("sku_id", UUID.class), VarianceAccumulator.of(row));

    var skuIds = new ArrayList<UUID>();
    skuIds.addAll(fromMap.keySet());
    for (var id : toMap.keySet()) if (!skuIds.contains(id)) skuIds.add(id);
    skuIds.sort(Comparator.comparing(UUID::toString));

    var rows = new ArrayList<FinanceCostCalculationVarianceRow>();
    var onlyInFrom = 0;
    var onlyInTo = 0;
    for (var skuId : skuIds) {
      var f = fromMap.get(skuId);
      var t = toMap.get(skuId);
      var fromQty = f == null ? BigDecimal.ZERO : f.quantity();
      var fromCost = f == null ? BigDecimal.ZERO : f.costAmount();
      var toQty = t == null ? BigDecimal.ZERO : t.quantity();
      var toCost = t == null ? BigDecimal.ZERO : t.costAmount();
      var onlyFrom = f != null && t == null;
      var onlyTo = t != null && f == null;
      if (onlyFrom) onlyInFrom++;
      if (onlyTo) onlyInTo++;
      rows.add(
          new FinanceCostCalculationVarianceRow(
              skuId,
              f != null ? f.skuCode() : t.skuCode(),
              fromQty.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP),
              fromCost.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
              f == null ? 0 : f.entryCount(),
              toQty.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP),
              toCost.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
              t == null ? 0 : t.entryCount(),
              toQty.subtract(fromQty).setScale(QUANTITY_SCALE, RoundingMode.HALF_UP),
              toCost.subtract(fromCost).setScale(MONEY_SCALE, RoundingMode.HALF_UP),
              onlyFrom,
              onlyTo));
    }

    var totalDifference =
        to.totalCostAmount().subtract(from.totalCostAmount()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    var note =
        from.periodFrom().equals(to.periodFrom()) && from.periodTo().equals(to.periodTo())
            ? "两次计算覆盖**同一段期间**，逐 SKU 对比如下。"
            : "⚠️ 两次计算的**期间不一致**（"
                + from.periodFrom()
                + " ~ "
                + from.periodTo()
                + " 对 "
                + to.periodFrom()
                + " ~ "
                + to.periodTo()
                + "），差额里含期间差异，不只是方法差异。";
    if (!from.currency().equals(to.currency())) {
      note += " ⚠️ 两次计算的**币种不同**（" + from.currency() + " / " + to.currency() + "），差额不可直接解读。";
    }

    return new FinanceCostCalculationVarianceComparison(
        fromRunId,
        toRunId,
        from.valuationMethod(),
        from.valuationMethodLabel(),
        from.valuationMethodVersion() == null ? 0 : from.valuationMethodVersion(),
        to.valuationMethod(),
        to.valuationMethodLabel(),
        to.valuationMethodVersion() == null ? 0 : to.valuationMethodVersion(),
        from.periodFrom(),
        to.periodFrom(),
        from.totalQuantity(),
        to.totalQuantity(),
        from.totalCostAmount(),
        to.totalCostAmount(),
        totalDifference,
        rows.size(),
        onlyInFrom,
        onlyInTo,
        rows,
        note);
  }

  // ================================================================ 读模型

  /** 批次列表（真分页 + 真筛选）。 */
  public FinanceCostCalculationRunPage runs(
      String scopeType, UUID scopeRefId, String valuationMethod, String status, int page, int size) {
    var where = new StringBuilder(" where tenant_id = ?");
    var binds = new ArrayList<Object>();
    binds.add(currentTenant());
    if (scopeType != null && !scopeType.isBlank()) {
      where.append(" and scope_type = ?");
      binds.add(scopeType.trim().toUpperCase(Locale.ROOT));
    }
    if (scopeRefId != null) {
      where.append(" and scope_ref_id = ?");
      binds.add(scopeRefId);
    }
    if (valuationMethod != null && !valuationMethod.isBlank()) {
      where.append(" and valuation_method = ?");
      binds.add(valuationMethod.trim().toUpperCase(Locale.ROOT));
    }
    if (status != null && !status.isBlank()) {
      where.append(" and status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    var total =
        sql.fetchOne("select count(*) from finance_cost_calculation_run" + where, binds.toArray())
            .get(0, Long.class);
    var completedCount =
        sql.fetchOne(
                "select count(*) from finance_cost_calculation_run"
                    + " where tenant_id = ? and status = 'COMPLETED'",
                currentTenant())
            .get(0, Long.class);
    var limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var offset = Math.max(page, 0) * limit;
    binds.add(limit);
    binds.add(offset);
    var rows =
        sql.fetch(
            "select id from finance_cost_calculation_run" + where + " order by created_at desc limit ? offset ?",
            binds.toArray());
    var items = new ArrayList<FinanceCostCalculationRunRow>();
    for (var row : rows) items.add(run(row.get("id", UUID.class)));
    return new FinanceCostCalculationRunPage(items, Math.max(page, 0), limit, total, completedCount);
  }

  /** 批次明细（表头 + 全部成本层 + 全部成本分录）。 */
  public FinanceCostCalculationRunDetail runDetail(UUID id) {
    var header = run(id);
    var layerRows =
        sql.fetch(
            "select id,run_id,sku_id,sku_code,layer_index,lot_ref,received_date,layer_quantity,"
                + " layer_amount,unit_cost,consumed_quantity,consumed_amount,currency,layer_source_service"
                + " from finance_cost_calculation_layer where tenant_id = ? and run_id = ?"
                + " order by sku_id,layer_index",
            currentTenant(),
            id);
    var layers = new ArrayList<FinanceCostCalculationLayerRow>();
    for (var row : layerRows) layers.add(layerRow(row));
    var entryRows =
        sql.fetch(
            "select id,run_id,flow_transaction_id,flow_ref_type,flow_ref_id,flow_kind,occurred_at,"
                + " business_type,sku_id,sku_code,issue_quantity,covered_quantity,shortfall_quantity,"
                + " unit_cost,cost_amount,currency,layer_count,status,gap_note"
                + " from finance_cost_calculation_entry where tenant_id = ? and run_id = ?"
                + " order by occurred_at,sku_id",
            currentTenant(),
            id);
    var entries = new ArrayList<FinanceCostCalculationEntryRow>();
    for (var row : entryRows) entries.add(entryRow(row));
    return new FinanceCostCalculationRunDetail(header, layers, entries);
  }

  /** 成本层列表（真分页 + 真筛选）。 */
  public FinanceCostCalculationLayerPage layers(UUID runId, UUID skuId, int page, int size) {
    var where = new StringBuilder(" where tenant_id = ?");
    var binds = new ArrayList<Object>();
    binds.add(currentTenant());
    if (runId != null) {
      where.append(" and run_id = ?");
      binds.add(runId);
    }
    if (skuId != null) {
      where.append(" and sku_id = ?");
      binds.add(skuId);
    }
    var total =
        sql.fetchOne("select count(*) from finance_cost_calculation_layer" + where, binds.toArray())
            .get(0, Long.class);
    var limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var offset = Math.max(page, 0) * limit;
    binds.add(limit);
    binds.add(offset);
    var rows =
        sql.fetch(
            "select id,run_id,sku_id,sku_code,layer_index,lot_ref,received_date,layer_quantity,"
                + " layer_amount,unit_cost,consumed_quantity,consumed_amount,currency,layer_source_service"
                + " from finance_cost_calculation_layer"
                + where
                + " order by run_id desc,sku_id,layer_index limit ? offset ?",
            binds.toArray());
    var items = new ArrayList<FinanceCostCalculationLayerRow>();
    for (var row : rows) items.add(layerRow(row));
    return new FinanceCostCalculationLayerPage(items, Math.max(page, 0), limit, total);
  }

  /** 成本分录列表（真分页 + 真筛选 + 汇总）。 */
  public FinanceCostCalculationEntryPage entries(
      UUID runId, UUID skuId, String businessType, String status, int page, int size) {
    var where = new StringBuilder(" where tenant_id = ?");
    var binds = new ArrayList<Object>();
    binds.add(currentTenant());
    if (runId != null) {
      where.append(" and run_id = ?");
      binds.add(runId);
    }
    if (skuId != null) {
      where.append(" and sku_id = ?");
      binds.add(skuId);
    }
    if (businessType != null && !businessType.isBlank()) {
      where.append(" and business_type = ?");
      binds.add(businessType.trim().toUpperCase(Locale.ROOT));
    }
    if (status != null && !status.isBlank()) {
      where.append(" and status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    var total =
        sql.fetchOne("select count(*) from finance_cost_calculation_entry" + where, binds.toArray())
            .get(0, Long.class);
    var sums =
        sql.fetchOne(
            "select coalesce(sum(issue_quantity),0) as quantity,"
                + " coalesce(sum(cost_amount),0) as cost_amount,"
                + " count(*) filter (where status = 'INCOMPLETE') as incomplete"
                + " from finance_cost_calculation_entry" + where,
            binds.toArray());
    var limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var offset = Math.max(page, 0) * limit;
    binds.add(limit);
    binds.add(offset);
    var rows =
        sql.fetch(
            "select id,run_id,flow_transaction_id,flow_ref_type,flow_ref_id,flow_kind,occurred_at,"
                + " business_type,sku_id,sku_code,issue_quantity,covered_quantity,shortfall_quantity,"
                + " unit_cost,cost_amount,currency,layer_count,status,gap_note"
                + " from finance_cost_calculation_entry"
                + where
                + " order by occurred_at desc,id limit ? offset ?",
            binds.toArray());
    var items = new ArrayList<FinanceCostCalculationEntryRow>();
    for (var row : rows) items.add(entryRow(row));
    return new FinanceCostCalculationEntryPage(
        items,
        Math.max(page, 0),
        limit,
        total,
        sums.get("quantity", BigDecimal.class).setScale(QUANTITY_SCALE, RoundingMode.HALF_UP),
        sums.get("cost_amount", BigDecimal.class).setScale(MONEY_SCALE, RoundingMode.HALF_UP),
        sums.get("incomplete", Long.class));
  }

  // ================================================================ 上游读取

  /** 一笔出库的一个 SKU（流水下钻后的聚合结果）。 */
  private record OutboundIssue(
      UUID transactionId,
      String kind,
      String refType,
      UUID refId,
      OffsetDateTime occurredAt,
      CalculationBusinessType businessType,
      UUID skuId,
      String skuCode,
      BigDecimal quantity) {}

  /**
   * 读出库流水并聚合到 SKU 粒度。
   *
   * <p>⚠️ 分页是真分页：翻到 {@code total} 为止，**不静默截断**；
   * 超过 {@link #MAX_FLOWS_PER_RUN} 明确报错让用户缩小期间。
   */
  private List<OutboundIssue> readOutboundIssues(
      OffsetDateTime from, OffsetDateTime to, String bearer) {
    var collected = new ArrayList<CostCalculationFlowPort.FlowRow>();
    long total = 0;
    for (int page = 0; ; page++) {
      var result = flows.ledger(from, to, OUTBOUND_KINDS, bearer, page, FLOW_PAGE_SIZE);
      var rows = result.rows() == null ? List.<CostCalculationFlowPort.FlowRow>of() : result.rows();
      if (rows.isEmpty()) break;
      if (collected.size() + rows.size() > MAX_FLOWS_PER_RUN) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "期间内的出库笔数超过单批次上限 "
                + MAX_FLOWS_PER_RUN
                + " 笔，请把期间拆小后重算（**不静默截断**）");
      }
      collected.addAll(rows);
      total = result.total() == null ? collected.size() : result.total();
      if (collected.size() >= total) break;
    }

    // 按发生时间升序（同一毫秒用流水 ID 决胜，保证顺序稳定可复现）。
    collected.sort(
        Comparator.comparing(
                (CostCalculationFlowPort.FlowRow r) ->
                    r.occurredAt() == null ? OffsetDateTime.MIN : r.occurredAt())
            .thenComparing(r -> r.transactionId() == null ? UUID.randomUUID() : r.transactionId()));

    var issues = new ArrayList<OutboundIssue>();
    for (var flow : collected) {
      if (flow.transactionId() == null) continue;
      var businessType = businessTypeOf(flow.kind(), flow.refType());
      if (businessType == null) continue;
      // 分录下钻 → SKU 级出库量（取带符号分录的绝对值）。
      var bySku = new LinkedHashMap<UUID, BigDecimal>();
      var codes = new LinkedHashMap<UUID, String>();
      for (var entry : flows.drill(flow.transactionId(), bearer)) {
        if (entry.skuId() == null || entry.onHandDelta() == null) continue;
        if (entry.onHandDelta().signum() >= 0) continue; // 只看出库（负数）分录
        var qty = entry.onHandDelta().abs();
        bySku.merge(entry.skuId(), qty, BigDecimal::add);
        codes.putIfAbsent(entry.skuId(), entry.skuCode() == null ? "" : entry.skuCode());
      }
      for (var item : bySku.entrySet()) {
        issues.add(
            new OutboundIssue(
                flow.transactionId(),
                flow.kind(),
                flow.refType() == null ? "" : flow.refType(),
                flow.refId(),
                flow.occurredAt(),
                businessType,
                item.getKey(),
                codes.getOrDefault(item.getKey(), ""),
                item.getValue().setScale(QUANTITY_SCALE, RoundingMode.HALF_UP)));
      }
    }
    return issues;
  }

  /** 业务类型：蓝图点名的三类。 */
  private CalculationBusinessType businessTypeOf(String kind, String refType) {
    if (kind == null) return null;
    if ("SHIPMENT".equalsIgnoreCase(kind)) {
      return SHIPMENT_HANDOVER_REF_TYPE.equalsIgnoreCase(refType)
          ? CalculationBusinessType.SHIPMENT
          : CalculationBusinessType.SALES_OUTBOUND;
    }
    if ("TRANSFER_OUT".equalsIgnoreCase(kind)) {
      return CalculationBusinessType.PLATFORM_TRANSFER_OUT;
    }
    return null;
  }

  // ================================================================ 计价方法

  /** 12.4.1.1 在给定时刻生效的那一版配置。 */
  private record EffectiveConfig(
      UUID id, int version, ValuationMethod method, String currency, String scopeCode, String scopeName) {}

  /**
   * 读 12.4.1.1 的生效配置。
   *
   * <p>⚠️ **刻意不按 {@code status='ACTIVE'} 过滤**：那只是「当前」那一版；
   * 算历史期间时要的是**当时生效**的那一版（早已被收口成 SUPERSEDED）。
   * ⇒ 按生效区间定位（{@code effective_from <= at < effective_to}），
   * 并排除已作废（{@code CANCELLED}）的版本。
   */
  private EffectiveConfig effectiveConfig(ValuationScopeType scopeType, UUID scopeRefId, OffsetDateTime at) {
    var row =
        sql.fetchOne(
            "select id,version,valuation_method,currency,scope_code,scope_name"
                + " from finance_cost_valuation_method"
                + " where tenant_id = ? and scope_type = ? and scope_ref_id = ?"
                + " and status <> 'CANCELLED'"
                + " and effective_from <= ?::timestamptz"
                + " and (effective_to is null or effective_to > ?::timestamptz)"
                + " order by version desc limit 1",
            currentTenant(),
            scopeType.name(),
            scopeRefId,
            at,
            at);
    if (row == null) return null;
    var method = ValuationMethod.parse(row.get("valuation_method", String.class));
    if (method == null) return null;
    return new EffectiveConfig(
        row.get("id", UUID.class),
        row.get("version", Integer.class),
        method,
        row.get("currency", String.class),
        row.get("scope_code", String.class),
        row.get("scope_name", String.class));
  }

  /**
   * 重算成功后把 12.4.1.1 的重算标记改回「无需重算」。
   *
   * <p>🔴 **只改已有列的取值，不改表结构**（V355 的 CHECK 只允许
   * {@code NOT_REQUIRED} / {@code PENDING}；历史不可改写触发器不覆盖这一列）。
   * 「已重算」这个事实本身落在**本模块的批次**里 —— 那才是重算的落点。
   */
  private void clearPendingRecalculation(ValuationScopeType scopeType, UUID scopeRefId) {
    sql.execute(
        "update finance_cost_valuation_method set recalculation_status = 'NOT_REQUIRED',"
            + " updated_at = ?::timestamptz"
            + " where tenant_id = ? and scope_type = ? and scope_ref_id = ?"
            + " and recalculation_status = 'PENDING'",
        OffsetDateTime.now(ZoneOffset.UTC),
        currentTenant(),
        scopeType.name(),
        scopeRefId);
  }

  // ================================================================ 成本层池

  /** 一层成本（上游 catalog 5.2.1.1 的真实到货记录）。 */
  private record Lot(
      int index,
      String lotRef,
      LocalDate receivedDate,
      BigDecimal quantity,
      BigDecimal amount,
      BigDecimal unitCost,
      BigDecimal remainingQuantity) {}

  /** 一次消耗的结果。 */
  private record Consumption(
      BigDecimal quantity, BigDecimal amount, int layerCount, List<LayerUsage> layers) {}

  /** 一层被消耗的记录（落库前的中间形态）。 */
  private record LayerUsage(
      String key,
      UUID skuId,
      String skuCode,
      int layerIndex,
      String lotRef,
      LocalDate receivedDate,
      BigDecimal layerQuantity,
      BigDecimal layerAmount,
      BigDecimal appliedUnitCost,
      BigDecimal consumedQuantity,
      BigDecimal consumedAmount,
      String currency) {

    static LayerUsage merge(LayerUsage a, LayerUsage b) {
      var consumed = a.consumedQuantity().add(b.consumedQuantity());
      var amount = a.consumedAmount().add(b.consumedAmount());
      return new LayerUsage(
          a.key(),
          a.skuId(),
          a.skuCode(),
          a.layerIndex(),
          a.lotRef(),
          a.receivedDate(),
          a.layerQuantity(),
          a.layerAmount(),
          // 采用的单价 = 实际消耗金额 ÷ 实际消耗数量（同一层可能被多次以不同价消耗）。
          consumed.signum() == 0
              ? a.appliedUnitCost()
              : amount.divide(consumed, MONEY_SCALE, RoundingMode.HALF_UP),
          consumed,
          amount,
          a.currency());
    }
  }

  /**
   * 一个 SKU 的成本层池。
   *
   * <p>🔴 **口径与 12.4.1.1 的试算完全一致**，否则试算页显示的数字和实际结转的数字会对不上：
   * <ul>
   *   <li>FIFO：按到货日升序消耗，每层按**自己的进价**计价；
   *   <li>MOVING_AVERAGE：单位成本 = 池内剩余层总金额 ÷ 池内剩余总数量；
   *   <li>BATCH：只消耗指定批次；
   *   <li>SPECIFIED：按 12.4.1.1 维护的指定单价计价。
   * </ul>
   */
private static final class LayerPool {
    private final UUID skuId;
    private final String skuCode;
    private final List<Lot> lots;
    private final int skippedCurrency;
    /** 池内剩余金额（移动加权平均口径下是聚合值，不等于各层剩余金额之和）。 */
    private BigDecimal remainingAmount;

    private LayerPool(UUID skuId, String skuCode, List<Lot> lots, int skippedCurrency) {
      this.skuId = skuId;
      this.skuCode = skuCode;
      this.lots = lots;
      this.skippedCurrency = skippedCurrency;
      this.remainingAmount =
          lots.stream().map(Lot::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 从上游成本构成建池：过滤异币种与数量折算不出来的层，并按到货日排序。 */
    static LayerPool of(UUID skuId, CostValuationLayerPort.CostComposition composition, String currency) {
      var lots = new ArrayList<Lot>();
      var skipped = 0;
      var index = 0;
      var upstreamLots =
          composition.lots() == null ? List.<CostValuationLayerPort.CostLayer>of() : composition.lots();
      for (var lot : upstreamLots) {
        if (lot == null) continue;
        if (!currency.isEmpty() && lot.currency() != null && !currency.equals(lot.currency())) {
          skipped++;
          continue;
        }
        var baseQty = lot.baseQuantity() == null ? lot.quantity() : lot.baseQuantity();
        if (baseQty == null || baseQty.signum() <= 0) continue;
        var amount = lot.amount() == null ? BigDecimal.ZERO : lot.amount();
        var unitCost = lot.unitCost();
        if (unitCost == null) unitCost = amount.divide(baseQty, MONEY_SCALE, RoundingMode.HALF_UP);
        lots.add(
            new Lot(
                index++,
                lot.lotRef() == null ? "" : lot.lotRef(),
                lot.receivedDate(),
                baseQty.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP),
                amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                unitCost.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                baseQty.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP)));
      }
      lots.sort(
          Comparator.comparing((Lot l) -> l.receivedDate() == null ? LocalDate.MIN : l.receivedDate())
              .thenComparing(l -> l.lotRef())
              .thenComparingInt(l -> l.index()));
      return new LayerPool(
          skuId, composition.skuCode() == null ? "" : composition.skuCode(), lots, skipped);
    }

    List<Lot> lots() {
      return lots;
    }

    int skippedCurrency() {
      return skippedCurrency;
    }

    UUID skuId() {
      return skuId;
    }

    String skuCode() {
      return skuCode;
    }

    /** 池内剩余数量。 */
    BigDecimal remainingQuantity() {
      return lots.stream()
          .map(Lot::remainingQuantity)
          .reduce(BigDecimal.ZERO, BigDecimal::add)
          .setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    }

    /** 移动加权平均的单位成本：剩余金额 ÷ 剩余数量。算不出来时返回 null（**不给 0**）。 */
    BigDecimal movingAverageUnitCost() {
      var qty = remainingQuantity();
      if (qty.signum() <= 0) return null;
      return remainingAmount.divide(qty, MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 按到货日升序消耗数量，金额按**应用单价**计价。
     *
     * <p>FIFO 用层自己的进价；移动加权平均用池的加权平均价；指定成本法用指定单价 ——
     * 三者的差别只在「按什么价计」，消耗的**数量**口径一致（先到先出）。
     *
     * @param appliedUnitCost 应用单价（null ⇒ 算不出来，返回零消耗）
     * @param limit 本次要消耗的数量
     */
    Consumption takeInOrder(BigDecimal limit, BigDecimal appliedUnitCost, String currency) {
      var remaining = limit;
      var amount = BigDecimal.ZERO;
      var used = new ArrayList<LayerUsage>();
      for (int i = 0; i < lots.size() && remaining.signum() > 0; i++) {
        var lot = lots.get(i);
        if (lot.remainingQuantity().signum() <= 0) continue;
        var take = lot.remainingQuantity().min(remaining);
        var cost = take.multiply(appliedUnitCost).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        amount = amount.add(cost);
        remaining = remaining.subtract(take);
        lots.set(
            i,
            new Lot(
                lot.index(),
                lot.lotRef(),
                lot.receivedDate(),
                lot.quantity(),
                lot.amount(),
                lot.unitCost(),
                lot.remainingQuantity().subtract(take)));
        used.add(
            new LayerUsage(
                skuId + "#" + lot.index(),
                skuId,
                skuCode,
                lot.index(),
                lot.lotRef(),
                lot.receivedDate(),
                lot.quantity(),
                lot.amount(),
                appliedUnitCost,
                take,
                cost,
                currency));
      }
      remainingAmount = remainingAmount.subtract(amount);
      return new Consumption(
          limit.subtract(remaining).setScale(QUANTITY_SCALE, RoundingMode.HALF_UP),
          amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
          used.size(),
          used);
    }
  }

  /**
   * 按计价方法消耗成本层。
   *
   * <p>⚠️ 算不出结果时**不给数字**：返回的消耗量为 0、金额为 0，
   * 由调用方据「没有可用层 / 没有指定批次 / 没有指定单价」写出缺口原因。
   */
  private Consumption consume(
      LayerPool pool, BigDecimal wanted, ValuationMethod method, String currency, String batchRef) {
    var zeroQuantity = BigDecimal.ZERO.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    var zeroAmount = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    switch (method) {
      case FIFO -> {
        // 每层按**自己的进价**计价（先进先出）。
        return fifoConsume(pool, wanted, currency);
      }
      case MOVING_AVERAGE -> {
        var unitCost = pool.movingAverageUnitCost();
        if (unitCost == null) {
          return new Consumption(zeroQuantity, zeroAmount, 0, List.of());
        }
        return pool.takeInOrder(wanted, unitCost, currency);
      }
      case BATCH -> {
        if (batchRef == null || batchRef.isBlank()) {
          return new Consumption(zeroQuantity, zeroAmount, 0, List.of());
        }
        var ref = batchRef.trim();
        for (int i = 0; i < pool.lots().size(); i++) {
          var lot = pool.lots().get(i);
          if (!ref.equals(lot.lotRef())) continue;
          if (lot.remainingQuantity().signum() <= 0) {
            return new Consumption(zeroQuantity, zeroAmount, 0, List.of());
          }
          var take = lot.remainingQuantity().min(wanted);
          var cost = take.multiply(lot.unitCost()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
          pool.lots.set(
              i,
              new Lot(
                  lot.index(),
                  lot.lotRef(),
                  lot.receivedDate(),
                  lot.quantity(),
                  lot.amount(),
                  lot.unitCost(),
                  lot.remainingQuantity().subtract(take)));
          pool.remainingAmount = pool.remainingAmount.subtract(cost);
          return new Consumption(
              take.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP),
              cost,
              1,
              List.of(
                  new LayerUsage(
                      pool.skuId() + "#" + lot.index(),
                      pool.skuId(),
                      pool.skuCode(),
                      lot.index(),
                      lot.lotRef(),
                      lot.receivedDate(),
                      lot.quantity(),
                      lot.amount(),
                      lot.unitCost(),
                      take,
                      cost,
                      currency)));
        }
        return new Consumption(zeroQuantity, zeroAmount, 0, List.of());
      }
      case SPECIFIED -> {
        var unitCost = specifiedUnitCost(pool.skuId(), currency);
        if (unitCost == null) {
          return new Consumption(zeroQuantity, zeroAmount, 0, List.of());
        }
        return pool.takeInOrder(wanted, unitCost, currency);
      }
      default ->
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知的计价方法");
    }
  }

  /** 先进先出：逐层按**该层自己的进价**消耗（到货日升序）。 */
  private Consumption fifoConsume(LayerPool pool, BigDecimal wanted, String currency) {
    var remaining = wanted;
    var amount = BigDecimal.ZERO;
    var used = new ArrayList<LayerUsage>();
    for (int i = 0; i < pool.lots().size() && remaining.signum() > 0; i++) {
      var lot = pool.lots().get(i);
      if (lot.remainingQuantity().signum() <= 0) continue;
      var take = lot.remainingQuantity().min(remaining);
      var cost = take.multiply(lot.unitCost()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
      amount = amount.add(cost);
      remaining = remaining.subtract(take);
      pool.lots.set(
          i,
          new Lot(
              lot.index(),
              lot.lotRef(),
              lot.receivedDate(),
              lot.quantity(),
              lot.amount(),
              lot.unitCost(),
              lot.remainingQuantity().subtract(take)));
      used.add(
          new LayerUsage(
              pool.skuId() + "#" + lot.index(),
              pool.skuId(),
              pool.skuCode(),
              lot.index(),
              lot.lotRef(),
              lot.receivedDate(),
              lot.quantity(),
              lot.amount(),
              lot.unitCost(),
              take,
              cost,
              currency));
    }
    pool.remainingAmount = pool.remainingAmount.subtract(amount);
    return new Consumption(
        wanted.subtract(remaining).setScale(QUANTITY_SCALE, RoundingMode.HALF_UP),
        amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
        used.size(),
        used);
  }

  /** 12.4.1.1 维护的指定单价（没有维护就返回 null —— 算不出来，不给 0）。 */
  private BigDecimal specifiedUnitCost(UUID skuId, String currency) {
    var row =
        sql.fetchOne(
            "select unit_cost from finance_cost_valuation_specified_cost"
                + " where tenant_id = ? and sku_id = ? and currency = ? and status = 'ACTIVE'"
                + " order by version desc limit 1",
            currentTenant(),
            skuId,
            currency);
    if (row == null) return null;
    return row.get("unit_cost", BigDecimal.class).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  // ================================================================ 读模型映射

  private FinanceCostCalculationRunRow run(UUID id) {
    var row =
        sql.fetchOne(
            "select id,scope_type,scope_ref_id,scope_code,scope_name,valuation_method,"
                + " valuation_method_version,valuation_method_config_id,currency,period_from,period_to,"
                + " recalculation_reason,status,flow_source_service,layer_source_service,basis_note,"
                + " source_note,sku_count,layer_count,entry_count,incomplete_entry_count,total_quantity,"
                + " total_covered_quantity,total_shortfall_quantity,total_cost_amount,supersedes_run_id,"
                + " superseded_by_run_id,created_by,created_at,completed_at"
                + " from finance_cost_calculation_run where tenant_id = ? and id = ?",
            currentTenant(),
            id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "成本计算批次不存在");
    }
    var method = ValuationMethod.parse(row.get("valuation_method", String.class));
    return new FinanceCostCalculationRunRow(
        row.get("id", UUID.class),
        row.get("scope_type", String.class),
        row.get("scope_ref_id", UUID.class),
        row.get("scope_code", String.class),
        row.get("scope_name", String.class),
        row.get("valuation_method", String.class),
        method == null ? "" : method.label(),
        row.get("valuation_method_version", Integer.class),
        row.get("valuation_method_config_id", UUID.class),
        row.get("currency", String.class),
        row.get("period_from", OffsetDateTime.class),
        row.get("period_to", OffsetDateTime.class),
        row.get("recalculation_reason", String.class),
        row.get("status", String.class),
        row.get("flow_source_service", String.class),
        row.get("layer_source_service", String.class),
        row.get("basis_note", String.class),
        row.get("source_note", String.class),
        row.get("sku_count", Integer.class),
        row.get("layer_count", Integer.class),
        row.get("entry_count", Integer.class),
        row.get("incomplete_entry_count", Integer.class),
        row.get("total_quantity", BigDecimal.class),
        row.get("total_covered_quantity", BigDecimal.class),
        row.get("total_shortfall_quantity", BigDecimal.class),
        row.get("total_cost_amount", BigDecimal.class),
        row.get("supersedes_run_id", UUID.class),
        row.get("superseded_by_run_id", UUID.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("completed_at", OffsetDateTime.class));
  }

  private FinanceCostCalculationLayerRow layerRow(Record row) {
    return new FinanceCostCalculationLayerRow(
        row.get("id", UUID.class),
        row.get("run_id", UUID.class),
        row.get("sku_id", UUID.class),
        row.get("sku_code", String.class),
        row.get("layer_index", Integer.class),
        row.get("lot_ref", String.class),
        row.get("received_date", LocalDate.class),
        row.get("layer_quantity", BigDecimal.class),
        row.get("layer_amount", BigDecimal.class),
        row.get("unit_cost", BigDecimal.class),
        row.get("consumed_quantity", BigDecimal.class),
        row.get("consumed_amount", BigDecimal.class),
        row.get("currency", String.class),
        row.get("layer_source_service", String.class));
  }

  private FinanceCostCalculationEntryRow entryRow(Record row) {
    var businessType = CalculationBusinessType.parse(row.get("business_type", String.class));
    return new FinanceCostCalculationEntryRow(
        row.get("id", UUID.class),
        row.get("run_id", UUID.class),
        row.get("flow_transaction_id", UUID.class),
        row.get("flow_ref_type", String.class),
        row.get("flow_ref_id", UUID.class),
        row.get("flow_kind", String.class),
        row.get("occurred_at", OffsetDateTime.class),
        row.get("business_type", String.class),
        businessType == null ? "" : businessType.label(),
        row.get("sku_id", UUID.class),
        row.get("sku_code", String.class),
        row.get("issue_quantity", BigDecimal.class),
        row.get("covered_quantity", BigDecimal.class),
        row.get("shortfall_quantity", BigDecimal.class),
        row.get("unit_cost", BigDecimal.class),
        row.get("cost_amount", BigDecimal.class),
        row.get("currency", String.class),
        row.get("layer_count", Integer.class),
        row.get("status", String.class),
        row.get("gap_note", String.class));
  }

  /** 差异对比里一个 SKU 的累加值。 */
  private record VarianceAccumulator(
      String skuCode, BigDecimal quantity, BigDecimal costAmount, int entryCount) {

    static VarianceAccumulator of(Record row) {
      return new VarianceAccumulator(
          row.get("sku_code", String.class),
          row.get("quantity", BigDecimal.class),
          row.get("cost_amount", BigDecimal.class),
          row.get("entry_count", Integer.class));
    }
  }

  private UUID currentTenant() {
    var raw =
        sql.fetchOne("select nullif(current_setting('app.current_tenant', true), '')::uuid as t")
            .get("t", UUID.class);
    if (raw == null) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "租户上下文缺失：成本计算必须在绑定租户的事务里执行");
    }
    return raw;
  }
}
