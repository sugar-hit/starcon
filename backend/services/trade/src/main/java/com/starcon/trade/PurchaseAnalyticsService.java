package com.starcon.trade;

import static com.starcon.trade.PurchaseAnalyticsModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.5.2.1 采购分析。
 *
 * <p>蓝图 06-purchase-and-supply.md 6.5.2.1 原文：
 *
 * <blockquote>
 *
 * 职责：采购侧报表：采购金额趋势、供应商集中度与贡献、采购价格波动、到货准时率、
 * 采购成本占销售额比、按类目/店铺维度的采购效率。
 * 输入：采购单与收货数据；时间范围与维度筛选。
 * 输出：报表图表 + 可导出明细；下钻到具体采购单。
 * </blockquote>
 *
 * <p><b>报表基数的来源</b>（全部是**已交付**事实，逐条可查；「采购单原单」6.3.1.x 未交付 ⇒ 不建影子表）：
 *
 * <ul>
 *   <li>6.3.2.2 到货与逾期（V235）—— 采购金额、供应商、单价、币种、承诺到货日、准时与否。</li>
 *   <li>6.2.1.3 采购计划（V242）—— SKU、计划量、期望到货日、目标仓、大促店铺。</li>
 *   <li>8.2.1.1 收货单（warehouse，授权只读端口）—— 实收量。</li>
 *   <li>11.2.3.3 供应商索赔（V188）—— 已生效的采购成本冲减。</li>
 * </ul>
 *
 * <p><b>口径纪律</b>（每条都在响应里写死，不让读的人猜）：
 *
 * <ul>
 *   <li><b>跨币种不合并</b>：金额按币种分列；折算到本位币的行带<b>汇率快照</b>；
 *       汇率取不到的行单列「未折算」桶，<b>绝不按 1:1 折算</b>。</li>
 *   <li><b>准时率的分母是「承诺到货行」</b>（承诺到货日落在窗口内、且已发生过到货事实的跟踪行），
 *       不是收货行；行内任一次到货晚于承诺日 ⇒ 该行判 LATE。承诺日当天不算晚。</li>
 *   <li><b>集中度给两把尺</b>：top-N 占比 + HHI（0–1 标度，写明标度）。</li>
 *   <li><b>价格波动是环比</b>（窗口末月 vs 上一月），不是同比。</li>
 *   <li><b>算不出来的不是 0</b>：采购成本占销售额比的销售分母在本仓没有可用来源 ⇒
 *       {@code available=false} + {@code ownerModule} + 影响说明，<b>绝不拿采购额当分母造一个假的比率</b>。</li>
 * </ul>
 */
@Service
public class PurchaseAnalyticsService {

  private static final int MAX_PAGE_SIZE = 200;
  private static final int QUANTITY_SCALE = 6;
  private static final int AMOUNT_SCALE = 4;
  private static final int RATE_SCALE = 8;
  private static final int PERCENT_SCALE = 6;
  /** 一次同步最多取多少张收货单（触顶即截断并如实报出，不假装扫完全量）。 */
  private static final int MAX_RECEIPTS_PER_SYNC = 200;

  private final DSLContext sql;
  private final AccessService access;
  private final PurchaseAnalyticsCatalogPort catalog;
  private final PurchaseAnalyticsWarehousePort warehouse;

  public PurchaseAnalyticsService(
      DSLContext sql,
      AccessService access,
      PurchaseAnalyticsCatalogPort catalog,
      PurchaseAnalyticsWarehousePort warehouse) {
    this.sql = sql;
    this.access = access;
    this.catalog = catalog;
    this.warehouse = warehouse;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：到货与逾期在 6.3.2.2、采购计划在 6.2.1.3、实收量在 warehouse 的 8.2.1.1、
   * 索赔在 11.2.3.3、类目与汇率在 catalog、利润与经营分析在 13（**未交付**）。
   * 哪一个接通了必须由接口自己报出。
   */
  public PurchaseAnalyticsCapabilities capabilities() {
    List<PurchaseAnalyticsCapabilitySection> sections = new ArrayList<>();
    sections.add(
        ok("ARRIVAL_RECEIPT", "到货流水（采购金额 / 供应商 / 单价 / 币种 / 准时与否）", "6.3.2.2 到货与逾期（trade 服务，集成头已交付，迁移 V235）。"
                + "本模块**只 SELECT** purchase_arrival_tracking / purchase_arrival_receipt / "
                + "purchase_arrival_payable_confirmation，不改它们一个字。"
                + "金额口径 = 单价快照 × 本次到货数量（V235 的 DB 触发器已按同一口径算过，"
                + "本模块沿用它的 confirmation_amount，不另起一套算法）。", "V235 迁移 + PurchaseArrivalService / PurchaseArrivalController"));
    sections.add(
        ok("PURCHASE_PLAN_LINE", "采购计划行（SKU / 计划量 / 期望到货日 / 目标仓 / 大促店铺）", "6.2.1.3 采购计划（trade 服务，集成头已交付，迁移 V242）。"
                + "计划行**没有金额**（V242 里就没有价格列）⇒ 计划只进「量」的口径，不进金额口径。", "V242 迁移 + PurchasePlanService / PurchasePlanController"));
    sections.add(
        ok("GOODS_RECEIPT", "收货单实收量（8.2.1.1，warehouse 服务）", "实收量经 warehouse 的**授权只读端口**读（GET /warehouse/goods-receipts 与 "
                + "GET /warehouse/goods-receipts/{receiptId}），转发调用方自己的 bearer，"
                + "租户仍由 warehouse 按已认证成员关系解析（两道 RLS）。"
                + "不跨服务 SQL / FK / 视图。已知代价：端口内部是 1+N 次 HTTP 调用，"
                + "且一次同步最多取 "
                + MAX_RECEIPTS_PER_SYNC
                + " 张收货单（触顶会显式报「不是全量」）。", "PurchaseAnalyticsWarehousePort（授权只读端口）"));
    sections.add(
        ok("SUPPLIER_CLAIM", "供应商索赔冲减（11.2.3.3，trade 服务）", "只统计**财务已确认生效**的冲减（applied_offset_amount > 0，即 SETTLED）。"
                + "协商中 / 已达成但未确认的索赔不计入 —— 钱还没动，报表上就不该有。", "V188 迁移 + SupplierClaimService"));
    sections.add(
        blocked("PURCHASE_ORDER", "采购单原单（列表 / 创建编辑 / 审批）", "6.3.1.1 / 6.3.1.2 / 6.3.1.3", "实查集成头：backend/services/trade 下没有采购单表，也没有 PurchaseOrder 类。", "本模块**不建采购单影子表**（那会造出第二个写入所有者）。"
                + "影响：① 下钻只能落到**到货跟踪单号 + 采购单号（逻辑引用）**，"
                + "点不到采购单原单；② 到货跟踪单是**单级**的（一张采购单一行，"
                + "不分 SKU），所以价格波动只能按**供应商**维度做，做不到 SKU 维度。", "6.3.1.x 未交付"));
    sections.add(
        blocked("QUALITY_INSPECTION", "来料质检（扣量 / 不良成本）", "6.4.2.1", "6.4.2.1 质检单与本车道同批并行开发，此刻在本仓不存在。", "质检扣量与质检相关成本**不计入**采购金额口径。"
                + "影响：采购金额是**毛额**（到货口径），不含质检扣减 —— "
                + "等质检交付后需要重算一次口径，本模块已在事实表里留出 source_type 位。", "6.4.2.1 未交付"));
    sections.add(
        blocked("PROCESSING_COST", "委外加工费（6.4.1.1 加工单）", "6.4.1.1", "6.4.1.1 加工单已交付（迁移 V97），但 processing_order **没有单价 / 币种字段**。", "不是漏了，是**上游没有金额事实** ⇒ 委外加工费不计入采购金额口径，"
                + "也不拿加工单的组件数量冒充采购量。", "V97 迁移"));
    sections.add(
        blocked("SALES_DENOMINATOR", "销售额分母（采购成本占销售额比的另一半）", "13 利润与经营分析", "本仓**没有**区间销售额的可用来源："
                + "① 13 利润与经营分析没有对应服务；"
                + "② 5.3.1.1 利润测算器是**单 SKU 情景测算器**（入参是手工假设的售价与费率），"
                + "拿它当区间销售额就是把假设当实绩；"
                + "③ trade 本库 sales_order 只有 DRAFT / SUBMITTED 的手工销售单，未确认收入，"
                + "汇总它就是伪造销售额。", "采购成本占销售额比**算不出来**（available=false）：分子照算并随响应返回，"
                + "分母不拿采购额凑。等 13 交付后本端点直接亮起，无需改前端。", "13 未交付"));
    sections.add(
        ok("CATEGORY_DIMENSION", "类目维度（catalog 的 SKU 主数据）", "类目经 catalog 的**授权只读端口**读（GET /catalog/skus/{id}），"
                + "解析结果**冻结**在 purchase_analytics_sku_dimension 里（类目 + 采购单位箱规）。"
                + "解析不到就标 NOT_FOUND / UNAVAILABLE，**不猜类目**。", "PurchaseAnalyticsCatalogPort"));
    sections.add(
        ok("FX_SNAPSHOT", "汇率快照（catalog 的汇率台账 5.2.2.2）", "折算用的汇率在**事实物化那一刻**从 catalog 取到并冻结进事实行"
                + "（fx_rate / fx_rate_id / fx_rate_effective_date）。"
                + "之后 catalog 改了汇率，历史报表的数字不会跟着漂。"
                + "汇率取不到 ⇒ fx_status='UNSET'，该行**排除**在本位币合计之外，"
                + "**绝不按 1:1 折算**。", "PurchaseAnalyticsCatalogPort"));
    sections.add(
        ok("SHOP_DIMENSION", "店铺维度（大促日历）", "店铺来自 6.2.1.3 自带的大促日历（purchase_plan_campaign.shop_code），"
                + "**只覆盖大促计划**；日常计划计入「全店铺」桶。"
                + "收货单不带店铺 ⇒ 店铺维度**只有计划侧口径**（计划量 / 计划行数），"
                + "不给达成率 —— 分子无法按店铺归属时给比率就是编数。", "V242 迁移"));
    sections.add(
        blocked("DOWNSTREAM_PROFIT", "下游：13 利润与经营分析", "13 利润与经营分析", "本仓没有对应服务（backend/services/ 下只有 identity / catalog / channel / "
                + "warehouse / trade / crm）。", "本模块是**上游**：它产出采购成本口径供 13 消费，但**不代 13 建任何报表**，"
                + "也不把采购数据写进 13 的库。", "13 未交付"));

    return new PurchaseAnalyticsCapabilities(
        sections,
        "6.5.2.1 采购分析（trade 服务）。前端路由 /purchase/analytics，"
            + "后端命名空间 /trade/purchase-analytics。",
        "每个报表块都带 basis（口径原文）+ 窗口 + 币种：集中度 = top-N 占比 + HHI（0–1 标度）；"
            + "准时率分母 = 承诺到货日落在窗口内且已发生过到货事实的**到货跟踪行**（不是收货行）；"
            + "价格波动 = **环比**（窗口末月 vs 上一月），不是同比；"
            + "采购成本占销售额比 = 到货采购金额 ÷ 区间销售额（分母本仓不可用 ⇒ 显式标算不出）。",
        "金额一律 BigDecimal + NUMERIC（金额 numeric(19,4)、数量 numeric(19,6)、汇率 numeric(19,8)）。"
            + "**跨币种绝不按 1:1 折算**：报表按币种分列，本位币口径只统计带汇率快照的行，"
            + "未折算的行单列一个桶并计入「未折算金额」。",
        "每个指标都能下钻到事实行（GET /trade/purchase-analytics/facts，"
            + "带供应商 / 币种 / 类目 / 店铺 / 来源 / 准时状态 / 关键词筛选），"
            + "事实行带来源单号与来源单 id；但**采购单原单未交付**，"
            + "所以下钻的终点是**到货跟踪单 / 收货单 / 计划行**，不是采购单原单。",
        "导出走 GET /trade/purchase-analytics/export（StreamingResponseBody 分批写出，"
            + "峰值内存与结果集大小无关），导出内容与当前筛选完全一致。",
        "同步走 POST /trade/purchase-analytics/facts/sync：把已交付的上游事实物化进"
            + "purchase_analytics_fact（按事实键幂等 upsert，可重跑），"
            + "每次同步落一条 purchase_analytics_sync_run 留痕。");
  }

  /** 已接通的能力：owner / blockedReason / impact 三个字段一律为空。 */
  private static PurchaseAnalyticsCapabilitySection ok(
      String key, String label, String detail, String evidence) {
    return new PurchaseAnalyticsCapabilitySection(
        key, label, true, null, null, null, detail, evidence);
  }

  /** 未接通的能力：<b>必须点名归属模块</b>并写清对本模块的影响。 */
  private static PurchaseAnalyticsCapabilitySection blocked(
      String key, String label, String owner, String detail, String impact, String evidence) {
    return new PurchaseAnalyticsCapabilitySection(
        key,
        label,
        false,
        owner,
        "上游模块 " + owner + " 未交付（或上游没有这个事实）",
        impact,
        detail,
        evidence);
  }

  // ================================================================ 参照下拉

  /** 筛选下拉项（全部来自**已物化的真实事实**）。 */
  public PurchaseAnalyticsOptions options() {
    UUID tenantId = currentTenant();
    List<PurchaseAnalyticsOptionItem> suppliers = optionItems(tenantId, "supplier_code", "supplier_name", "supplier_ref_id");
    List<PurchaseAnalyticsOptionItem> currencies = optionItems(tenantId, "currency", "currency", null);
    List<PurchaseAnalyticsOptionItem> categories = optionItems(tenantId, "category_code", "category_name", null);
    List<PurchaseAnalyticsOptionItem> shops = optionItems(tenantId, "shop_code", "shop_code", null);
    List<PurchaseAnalyticsOptionItem> sourceTypes = optionItems(tenantId, "source_type", "source_type", null);
    String lastSync = lastSyncNote(tenantId);
    return new PurchaseAnalyticsOptions(
        suppliers,
        currencies,
        categories,
        shops,
        sourceTypes,
        List.of("COST", "RECEIPT", "PLAN"),
        List.of("ON_TIME", "LATE", "PENDING", "NOT_APPLICABLE"),
        lastSync,
        "下拉项来自已物化的事实台账（还没有同步过就是空的 —— "
            + "先跑一次「同步上游事实」，页面上的图表与下拉才有数据）。");
  }

  private List<PurchaseAnalyticsOptionItem> optionItems(
      UUID tenantId, String codeColumn, String labelColumn, String idColumn) {
    StringBuilder sqlText = new StringBuilder();
    sqlText
        .append("select ")
        .append(codeColumn)
        .append(" as code, ")
        .append(labelColumn)
        .append(" as label, count(*) as fact_count from purchase_analytics_fact")
        .append(" where tenant_id = ?::uuid")
        .append(codeColumn.equals("currency") ? " and currency is not null" : "")
        .append(codeColumn.equals("category_code") ? " and category_code <> ''" : "")
        .append(codeColumn.equals("shop_code") ? " and shop_code <> ''" : "")
        .append(" group by ")
        .append(codeColumn)
        .append(", ")
        .append(labelColumn)
        .append(" order by fact_count desc, code asc limit 200");
    List<PurchaseAnalyticsOptionItem> items = new ArrayList<>();
    for (Record row : sql.fetch(sqlText.toString(), tenantId)) {
      String code = row.get("code", String.class);
      if (code == null || code.isBlank()) {
        continue;
      }
      items.add(
          new PurchaseAnalyticsOptionItem(
              code, row.get("label", String.class), row.get("fact_count", Long.class)));
    }
    return items;
  }

  private String lastSyncNote(UUID tenantId) {
    var row =
        sql.fetchOne(
            "select run_no, status, finished_at from purchase_analytics_sync_run"
                + " where tenant_id = ?::uuid order by started_at desc, id desc limit 1",
            tenantId);
    if (row == null) {
      return "还没有同步过上游事实 —— 报表此刻是空的（不是「没有采购」，是「还没取数」）。";
    }
    return "最近一次同步："
        + row.get("run_no", String.class)
        + "（"
        + row.get("status", String.class)
        + "，"
        + (row.get("finished_at", OffsetDateTime.class) == null
            ? "未结束"
            : row.get("finished_at", OffsetDateTime.class).toString())
        + "）。";
  }

  // ================================================================ 同步

  /** 把已交付的上游事实物化进事实台账（幂等：同一事实键只更新、不新增）。 */
  public PurchaseAnalyticsSyncResult sync(
      PurchaseAnalyticsSyncRequest request, UUID actorId, String bearer) {
    UUID tenantId = currentTenant();
    LocalDate to = request.to() == null ? today() : request.to();
    LocalDate from = request.from() == null ? to.minusDays(89) : request.from();
    if (from.isAfter(to)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "窗口起点不能晚于窗口终点");
    }
    String sourceType = normalizeSourceType(request.sourceType());
    String runNo = runNo();
    insertSyncRun(runNo, sourceType, from, to, actorId);

    List<String> notes = new ArrayList<>();
    int[] counters = new int[6]; // scanned / created / updated / skipped / unresolvedSku / unconverted
    boolean anyFailed = false;
    boolean anySucceeded = false;

    // 本位币：折算的锚。读不到 ⇒ 一律不折算（UNSET），绝不按 1:1。
    String baseCurrency = null;
    try {
      baseCurrency = resolveBaseCurrency(bearer, notes);
    } catch (ResponseStatusException e) {
      notes.add("本位币读不到：" + reason(e) + " ⇒ 本次物化的跨币种金额一律标「未折算」，不按 1:1 折算。");
    }

    // SKU 维度缓存（同一批同步里同一个 SKU 只解析一次）。
    Map<UUID, SkuDimension> dimensions = new HashMap<>();

    if (sourceType.equals("ALL") || sourceType.equals("ARRIVAL_RECEIPT")) {
      try {
        anySucceeded = true;
        syncArrivalReceipts(
            tenantId, bearer, baseCurrency, from, to, dimensions, counters, notes);
      } catch (RuntimeException e) {
        anyFailed = true;
        notes.add("到货流水（6.3.2.2）同步失败：" + reason(e));
      }
    }
    if (sourceType.equals("ALL") || sourceType.equals("PURCHASE_PLAN_LINE")) {
      try {
        anySucceeded = true;
        syncPlanLines(tenantId, bearer, from, to, dimensions, counters, notes);
      } catch (RuntimeException e) {
        anyFailed = true;
        notes.add("采购计划行（6.2.1.3）同步失败：" + reason(e));
      }
    }
    if (sourceType.equals("ALL") || sourceType.equals("GOODS_RECEIPT")) {
      try {
        anySucceeded = true;
        syncGoodsReceipts(tenantId, bearer, from, to, dimensions, counters, notes);
      } catch (RuntimeException e) {
        anyFailed = true;
        notes.add("收货单（8.2.1.1）同步失败：" + reason(e));
      }
    }
    if (sourceType.equals("ALL") || sourceType.equals("SUPPLIER_CLAIM")) {
      try {
        anySucceeded = true;
        syncSupplierClaims(tenantId, bearer, baseCurrency, from, to, counters, notes);
      } catch (RuntimeException e) {
        anyFailed = true;
        notes.add("供应商索赔（11.2.3.3）同步失败：" + reason(e));
      }
    }

    String status = anyFailed ? (anySucceeded ? "PARTIAL" : "FAILED") : "SUCCESS";
    String message =
        status.equals("SUCCESS")
            ? "已把窗口内的上游事实物化进采购分析台账。"
            : "部分来源没有同步成功（见 notes）—— 报表上这些来源的数字**不完整**，界面必须显示。";
    finishSyncRun(runNo, status, counters, message);

    return new PurchaseAnalyticsSyncResult(
        runNo,
        sourceType,
        from,
        to,
        status,
        counters[0],
        counters[1],
        counters[2],
        counters[3],
        counters[4],
        counters[5],
        notes,
        message);
  }

  /** 到货流水（6.3.2.2）→ 成本事实。 */
  private void syncArrivalReceipts(
      UUID tenantId,
      String bearer,
      String baseCurrency,
      LocalDate from,
      LocalDate to,
      Map<UUID, SkuDimension> dimensions,
      int[] counters,
      List<String> notes) {
    OffsetDateTime start = from.atStartOfDay().atOffset(ZoneOffset.UTC);
    OffsetDateTime end = to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
    List<Record> rows =
        sql.fetch(
            "select r.id as receipt_id, r.arrived_at, r.arrived_qty, r.arrival_seq,"
                + " t.tracking_no, t.purchase_order_no, t.supplier_ref_id, t.supplier_code,"
                + " t.supplier_name, t.unit_price, t.currency, t.promised_arrival_date,"
                + " pc.confirmation_amount"
                + " from purchase_arrival_receipt r"
                + " join purchase_arrival_tracking t"
                + "   on t.tenant_id = r.tenant_id and t.id = r.tracking_id"
                + " left join purchase_arrival_payable_confirmation pc"
                + "   on pc.tenant_id = r.tenant_id and pc.receipt_id = r.id"
                + " where r.tenant_id = ?::uuid"
                + "   and r.arrived_at >= ?::timestamptz and r.arrived_at < ?::timestamptz"
                + " order by r.arrived_at, r.id",
            tenantId,
            start,
            end);
    Map<String, List<PurchaseAnalyticsCatalogPort.RateView>> rates =
        loadRates(bearer, baseCurrency, rows, counters);
    for (Record row : rows) {
      counters[0]++;
      UUID receiptId = row.get("receipt_id", UUID.class);
      OffsetDateTime arrivedAt = row.get("arrived_at", OffsetDateTime.class);
      LocalDate arrivedDate = arrivedAt == null ? null : arrivedAt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
      BigDecimal quantity = row.get("arrived_qty", BigDecimal.class);
      BigDecimal unitPrice = row.get("unit_price", BigDecimal.class);
      String currency = row.get("currency", String.class);
      BigDecimal amount = row.get("confirmation_amount", BigDecimal.class);
      if (amount == null && unitPrice != null && quantity != null) {
        amount = unitPrice.multiply(quantity).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
      }
      LocalDate promised = row.get("promised_arrival_date", LocalDate.class);
      String onTime = "NOT_APPLICABLE";
      if (arrivedDate != null && promised != null) {
        onTime = arrivedDate.isAfter(promised) ? "LATE" : "ON_TIME";
      }
      Fx fx = fxFor(currency, arrivedDate, baseCurrency, rates, counters);
      upsertFact(
          tenantId,
          new Fact(
              "ARRIVAL_RECEIPT:" + receiptId,
              "COST",
              "ARRIVAL_RECEIPT",
              row.get("tracking_no", String.class),
              receiptId,
              row.get("purchase_order_no", String.class),
              arrivedAt,
              arrivedDate,
              row.get("supplier_ref_id", UUID.class),
              row.get("supplier_code", String.class),
              row.get("supplier_name", String.class),
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              quantity,
              "",
              null,
              null,
              unitPrice,
              amount,
              currency,
              fx,
              promised,
              onTime,
              "到货流水（6.3.2.2）。计量单位未在上游记录 ⇒ 数量按记录值展示，未折算到基础单位。"),
          counters);
    }
  }

  /** 采购计划行（6.2.1.3）→ 计划量事实（**没有金额**）。 */
  private void syncPlanLines(
      UUID tenantId,
      String bearer,
      LocalDate from,
      LocalDate to,
      Map<UUID, SkuDimension> dimensions,
      int[] counters,
      List<String> notes) {
    List<Record> rows =
        sql.fetch(
            "select l.id as line_id, l.sku_id, l.sku_code, l.sku_name, l.plan_quantity,"
                + " l.expected_arrival_date, l.priority, p.plan_no, p.plan_type,"
                + " p.target_warehouse_code, c.shop_code, c.code as campaign_code"
                + " from purchase_plan_line l"
                + " join purchase_plan p on p.tenant_id = l.tenant_id and p.id = l.plan_id"
                + " left join purchase_plan_campaign c"
                + "   on c.tenant_id = p.tenant_id and c.id = p.campaign_id"
                + " where l.tenant_id = ?::uuid"
                + "   and l.expected_arrival_date >= ?::date and l.expected_arrival_date <= ?::date"
                + " order by l.expected_arrival_date, l.id",
            tenantId,
            from,
            to);
    for (Record row : rows) {
      counters[0]++;
      UUID skuId = row.get("sku_id", UUID.class);
      SkuDimension dimension = skuDimension(skuId, bearer, dimensions, counters, notes);
      LocalDate expected = row.get("expected_arrival_date", LocalDate.class);
      BigDecimal quantity = row.get("plan_quantity", BigDecimal.class);
      String unitCode = dimension == null ? "" : dimension.baseUnit();
      BigDecimal unitFactor = dimension == null ? null : BigDecimal.ONE;
      BigDecimal baseQuantity = quantity == null ? null : quantity;
      upsertFact(
          tenantId,
          new Fact(
              "PURCHASE_PLAN_LINE:" + row.get("line_id", UUID.class),
              "PLAN",
              "PURCHASE_PLAN_LINE",
              row.get("plan_no", String.class),
              row.get("line_id", UUID.class),
              row.get("campaign_code", String.class),
              expected == null ? null : expected.atStartOfDay().atOffset(ZoneOffset.UTC),
              expected,
              null,
              "",
              "",
              skuId,
              row.get("sku_code", String.class),
              row.get("sku_name", String.class),
              dimension == null ? "" : dimension.categoryCode(),
              dimension == null ? "" : dimension.categoryName(),
              row.get("shop_code", String.class),
              row.get("target_warehouse_code", String.class),
              quantity,
              unitCode,
              unitFactor,
              baseQuantity,
              null,
              null,
              null,
              Fx.notApplicable(),
              expected,
              "NOT_APPLICABLE",
              "采购计划行（6.2.1.3）。计划量按 SKU 基础单位记录（箱规系数来自 catalog 单位换算）。"),
          counters);
    }
  }

  /** 收货单（8.2.1.1，warehouse 授权只读端口）→ 实收量事实。 */
  private void syncGoodsReceipts(
      UUID tenantId,
      String bearer,
      LocalDate from,
      LocalDate to,
      Map<UUID, SkuDimension> dimensions,
      int[] counters,
      List<String> notes) {
    PurchaseAnalyticsWarehousePort.ReceiptScan scan =
        warehouse.receiptLines(bearer, MAX_RECEIPTS_PER_SYNC);
    if (scan.truncated()) {
      notes.add(scan.note());
      counters[3]++;
    }
    for (PurchaseAnalyticsWarehousePort.ReceiptLineFact line : scan.lines()) {
      if (line.occurredAt() == null) {
        continue;
      }
      LocalDate occurredDate = line.occurredAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
      if (occurredDate.isBefore(from) || occurredDate.isAfter(to)) {
        continue;
      }
      counters[0]++;
      SkuDimension dimension = skuDimension(line.skuId(), bearer, dimensions, counters, notes);
      BigDecimal quantity = line.receivedQuantity();
      String unitCode = dimension == null ? "" : dimension.baseUnit();
      upsertFact(
          tenantId,
          new Fact(
              "GOODS_RECEIPT:" + line.lineId(),
              "RECEIPT",
              "GOODS_RECEIPT",
              line.receiptCode(),
              line.receiptId(),
              line.warehouseCode(),
              line.occurredAt(),
              occurredDate,
              null,
              "",
              "",
              line.skuId(),
              line.skuCode(),
              line.skuName(),
              dimension == null ? "" : dimension.categoryCode(),
              dimension == null ? "" : dimension.categoryName(),
              "",
              line.warehouseCode(),
              quantity,
              unitCode,
              dimension == null ? null : BigDecimal.ONE,
              quantity,
              null,
              null,
              null,
              Fx.notApplicable(),
              null,
              "NOT_APPLICABLE",
              "收货单明细（8.2.1.1）。收货单不记单位 ⇒ 按 SKU 基础单位口径记录。"),
          counters);
    }
  }

  /** 供应商索赔（11.2.3.3）→ 已生效的成本冲减事实。 */
  private void syncSupplierClaims(
      UUID tenantId,
      String bearer,
      String baseCurrency,
      LocalDate from,
      LocalDate to,
      int[] counters,
      List<String> notes) {
    OffsetDateTime start = from.atStartOfDay().atOffset(ZoneOffset.UTC);
    OffsetDateTime end = to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
    List<Record> rows =
        sql.fetch(
            "select c.id as claim_id, c.claim_no, c.supplier_ref_id, c.supplier_code,"
                + " c.supplier_name, c.claim_currency, c.applied_offset_amount,"
                + " coalesce(c.submitted_at, c.created_at) as occurred_at"
                + " from supplier_claim c"
                + " where c.tenant_id = ?::uuid"
                + "   and c.applied_offset_amount > 0"
                + "   and coalesce(c.submitted_at, c.created_at) >= ?::timestamptz"
                + "   and coalesce(c.submitted_at, c.created_at) < ?::timestamptz"
                + " order by 7, c.id",
            tenantId,
            start,
            end);
    Map<String, List<PurchaseAnalyticsCatalogPort.RateView>> rates =
        loadRates(bearer, baseCurrency, rows, counters);
    for (Record row : rows) {
      counters[0]++;
      OffsetDateTime occurredAt = row.get("occurred_at", OffsetDateTime.class);
      LocalDate occurredDate = occurredAt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
      String currency = row.get("claim_currency", String.class);
      BigDecimal amount =
          row.get("applied_offset_amount", BigDecimal.class).negate().setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
      Fx fx = fxFor(currency, occurredDate, baseCurrency, rates, counters);
      upsertFact(
          tenantId,
          new Fact(
              "SUPPLIER_CLAIM:" + row.get("claim_id", UUID.class),
              "COST",
              "SUPPLIER_CLAIM",
              row.get("claim_no", String.class),
              row.get("claim_id", UUID.class),
              "",
              occurredAt,
              occurredDate,
              row.get("supplier_ref_id", UUID.class),
              row.get("supplier_code", String.class),
              row.get("supplier_name", String.class),
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              "",
              null,
              null,
              null,
              amount,
              currency,
              fx,
              null,
              "NOT_APPLICABLE",
              "供应商索赔（11.2.3.3）已生效冲减，按提交日归期。"),
          counters);
    }
  }

  // ================================================================ 事实台账

  /** 事实台账分页（真分页 + 真筛选 + 真排序）—— 也是所有指标的下钻出口。 */
  public PurchaseAnalyticsFactPage pageFacts(PurchaseAnalyticsQuery query) {
    UUID tenantId = currentTenant();
    int pageNo = Math.max(0, query.page());
    int pageSize = pageSize(query.size());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    appendFactFilter(where, binds, tenantId, query);
    String clause = String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from purchase_analytics_fact where " + clause, binds.toArray())
            .get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            "select id, fact_key, fact_kind, source_type, source_doc_no, source_doc_id,"
                + " source_ref_no, occurred_at, occurred_date, supplier_ref_id, supplier_code,"
                + " supplier_name, sku_ref_id, sku_code, sku_name, category_code, category_name,"
                + " shop_code, warehouse_code, quantity, unit_code, unit_factor, base_quantity,"
                + " unit_price, amount, currency, base_currency, fx_rate, base_amount, fx_status,"
                + " fx_rate_id, fx_rate_effective_date, promised_arrival_date, on_time_status, note"
                + " from purchase_analytics_fact where "
                + clause
                + " order by "
                + factSortClause(query.sort())
                + " limit ? offset ?",
            append(binds, pageSize, (long) pageNo * pageSize).toArray());
    List<PurchaseAnalyticsFactRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(factRow(row));
    }
    String note =
        items.isEmpty()
            ? "这一页没有符合条件的事实 —— 先确认跑过一次「同步上游事实」，或把筛选条件放宽。"
            : "";
    return new PurchaseAnalyticsFactPage(items, total, pageNo, pageSize, note);
  }

  /** 事实详情。 */
  public PurchaseAnalyticsFactDetail factDetail(UUID factId) {
    UUID tenantId = currentTenant();
    Record row =
        sql.fetchOne(
            "select id, fact_key, fact_kind, source_type, source_doc_no, source_doc_id,"
                + " source_ref_no, occurred_at, occurred_date, supplier_ref_id, supplier_code,"
                + " supplier_name, sku_ref_id, sku_code, sku_name, category_code, category_name,"
                + " shop_code, warehouse_code, quantity, unit_code, unit_factor, base_quantity,"
                + " unit_price, amount, currency, base_currency, fx_rate, base_amount, fx_status,"
                + " fx_rate_id, fx_rate_effective_date, promised_arrival_date, on_time_status, note"
                + " from purchase_analytics_fact where tenant_id = ?::uuid and id = ?::uuid",
            tenantId,
            factId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "这条采购分析事实不存在（或不属于当前租户）");
    }
    String sourceType = row.get("source_type", String.class);
    return new PurchaseAnalyticsFactDetail(
        factRow(row),
        sourceModule(sourceType),
        sourceDescription(sourceType, row),
        "下钻的终点是**来源单据**（到货跟踪单 / 收货单 / 计划行 / 索赔单）；"
            + "采购单原单（6.3.1.x）未交付，所以这里给不出采购单原单的链接。",
        row.get("note", String.class));
  }

  private static String sourceModule(String sourceType) {
    return switch (sourceType) {
      case "ARRIVAL_RECEIPT" -> "6.3.2.2 到货与逾期（trade）";
      case "PURCHASE_PLAN_LINE" -> "6.2.1.3 采购计划（trade）";
      case "GOODS_RECEIPT" -> "8.2.1.1 收货单（warehouse）";
      case "SUPPLIER_CLAIM" -> "11.2.3.3 供应商索赔（trade）";
      default -> "未知来源";
    };
  }

  private static String sourceDescription(String sourceType, Record row) {
    return switch (sourceType) {
      case "ARRIVAL_RECEIPT" ->
          "到货跟踪单 "
              + row.get("source_doc_no", String.class)
              + "（采购单号 "
              + row.get("source_ref_no", String.class)
              + "，逻辑引用；采购单原单未交付）";
      case "PURCHASE_PLAN_LINE" ->
          "采购计划 "
              + row.get("source_doc_no", String.class)
              + " 的计划行"
              + (row.get("source_ref_no", String.class).isBlank()
                  ? ""
                  : "（大促档期 " + row.get("source_ref_no", String.class) + "）");
      case "GOODS_RECEIPT" ->
          "收货单 " + row.get("source_doc_no", String.class) + "（仓库 " + row.get("warehouse_code", String.class) + "）";
      case "SUPPLIER_CLAIM" -> "供应商索赔单 " + row.get("source_doc_no", String.class);
      default -> "";
    };
  }

  // ================================================================ 总览

  /** 总览 KPI。 */
  public PurchaseAnalyticsSummary summary(PurchaseAnalyticsQuery query) {
    UUID tenantId = currentTenant();
    Window window = window(query);
    List<PurchaseAnalyticsCurrencyAmount> purchase = purchaseAmounts(tenantId, window, query);
    List<PurchaseAnalyticsCurrencyAmount> claims = claimOffsets(tenantId, window, query);
    BigDecimal total = null;
    String currencyNote;
    if (purchase.size() == 1) {
      total = purchase.get(0).amount();
      currencyNote = "窗口内只有一种币种（" + purchase.get(0).currency() + "），合计按原币给出。";
    } else if (purchase.isEmpty()) {
      currencyNote = "窗口内没有到货采购事实 —— 可能是没同步过，也可能这段时间真的没有到货。";
    } else {
      currencyNote =
          "窗口内有 "
              + purchase.size()
              + " 种币种，**不合并成一个总数**（按 1:1 折算出来的总数是假数）—— 请按币种分别看。";
    }
    BigDecimal unconverted = BigDecimal.ZERO;
    for (PurchaseAnalyticsCurrencyAmount item : purchase) {
      if (item.baseAmount() == null) {
        unconverted = unconverted.add(item.amount());
      }
    }
    int costFacts = countFacts(tenantId, window, query, "COST", null);
    int receiptFacts = countFacts(tenantId, window, query, "RECEIPT", null);
    int planFacts = countFacts(tenantId, window, query, "PLAN", null);
    int supplierCount =
        sql.fetchOne(
                "select count(distinct supplier_ref_id) from purchase_analytics_fact"
                    + " where tenant_id = ?::uuid and fact_kind = 'COST'"
                    + " and source_type = 'ARRIVAL_RECEIPT' and occurred_date >= ?::date"
                    + " and occurred_date <= ?::date",
                tenantId,
                window.from(),
                window.to())
            .get(0, Integer.class);
    return new PurchaseAnalyticsSummary(
        window.asModel("DAY"),
        purchase,
        total,
        currencyNote,
        costFacts,
        receiptFacts,
        planFacts,
        supplierCount,
        claims,
        unconverted,
        unconverted.compareTo(BigDecimal.ZERO) == 0
            ? "窗口内没有「汇率未登记」的金额。"
            : "有 " + unconverted.stripTrailingZeros().toPlainString()
                + " 的采购金额**没有汇率快照**，未计入本位币口径（绝不按 1:1 折算）。",
        "采购金额 = 到货流水口径（6.3.2.2）：单价快照 × 本次到货数量，"
            + "按币种分列；索赔冲减**单独列示**，不与采购金额相抵成一个净数。",
        "供应商索赔冲减只统计财务已确认生效的（applied_offset_amount > 0）。");
  }

  private List<PurchaseAnalyticsCurrencyAmount> purchaseAmounts(
      UUID tenantId, Window window, PurchaseAnalyticsQuery query) {
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?::uuid");
    binds.add(tenantId);
    where.add("fact_kind = 'COST'");
    where.add("source_type = 'ARRIVAL_RECEIPT'");
    where.add("occurred_date >= ?::date and occurred_date <= ?::date");
    binds.add(window.from());
    binds.add(window.to());
    if (notBlank(query.currency())) {
      where.add("currency = ?::varchar");
      binds.add(query.currency().trim());
    }
    if (notBlank(query.supplierRefId())) {
      where.add("supplier_ref_id = ?::uuid");
      binds.add(query.supplierRefId().trim());
    }
    List<PurchaseAnalyticsCurrencyAmount> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select currency, sum(amount) as amount, sum(base_amount) as base_amount,"
                + " base_currency, count(*) as fact_count,"
                + " sum(case when fx_status = 'UNSET' then 1 else 0 end) as unconverted_count"
                + " from purchase_analytics_fact where "
                + String.join(" and ", where)
                + " group by currency, base_currency order by currency",
            binds.toArray())) {
      items.add(
          new PurchaseAnalyticsCurrencyAmount(
              row.get("currency", String.class),
              row.get("amount", BigDecimal.class),
              row.get("base_amount", BigDecimal.class),
              row.get("base_currency", String.class),
              row.get("fact_count", Long.class),
              row.get("unconverted_count", Long.class)));
    }
    return items;
  }

  private List<PurchaseAnalyticsCurrencyAmount> claimOffsets(
      UUID tenantId, Window window, PurchaseAnalyticsQuery query) {
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?::uuid");
    binds.add(tenantId);
    where.add("fact_kind = 'COST'");
    where.add("source_type = 'SUPPLIER_CLAIM'");
    where.add("occurred_date >= ?::date and occurred_date <= ?::date");
    binds.add(window.from());
    binds.add(window.to());
    List<PurchaseAnalyticsCurrencyAmount> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select currency, sum(amount) as amount, sum(base_amount) as base_amount,"
                + " base_currency, count(*) as fact_count,"
                + " sum(case when fx_status = 'UNSET' then 1 else 0 end) as unconverted_count"
                + " from purchase_analytics_fact where "
                + String.join(" and ", where)
                + " group by currency, base_currency order by currency",
            binds.toArray())) {
      items.add(
          new PurchaseAnalyticsCurrencyAmount(
              row.get("currency", String.class),
              row.get("amount", BigDecimal.class),
              row.get("base_amount", BigDecimal.class),
              row.get("base_currency", String.class),
              row.get("fact_count", Long.class),
              row.get("unconverted_count", Long.class)));
    }
    return items;
  }

  private int countFacts(
      UUID tenantId,
      Window window,
      PurchaseAnalyticsQuery query,
      String factKind,
      String sourceType) {
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?::uuid");
    binds.add(tenantId);
    where.add("fact_kind = ?::varchar");
    binds.add(factKind);
    if (sourceType != null) {
      where.add("source_type = ?::varchar");
      binds.add(sourceType);
    }
    where.add("occurred_date >= ?::date and occurred_date <= ?::date");
    binds.add(window.from());
    binds.add(window.to());
    return sql.fetchOne(
            "select count(*) from purchase_analytics_fact where " + String.join(" and ", where),
            binds.toArray())
        .get(0, Integer.class);
  }

  // ================================================================ 金额趋势

  /** 采购金额趋势（按币种分列）。 */
  public PurchaseAnalyticsTrend trend(PurchaseAnalyticsQuery query, String granularity) {
    UUID tenantId = currentTenant();
    Window window = window(query);
    String grain = normalizeGranularity(granularity);
    String periodExpr =
        switch (grain) {
          case "WEEK" -> "to_char(date_trunc('week', occurred_date), 'YYYY-MM-DD')";
          case "MONTH" -> "to_char(occurred_date, 'YYYY-MM')";
          default -> "to_char(occurred_date, 'YYYY-MM-DD')";
        };
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?::uuid");
    binds.add(tenantId);
    where.add("fact_kind = 'COST'");
    where.add("source_type = 'ARRIVAL_RECEIPT'");
    where.add("occurred_date >= ?::date and occurred_date <= ?::date");
    binds.add(window.from());
    binds.add(window.to());
    if (notBlank(query.currency())) {
      where.add("currency = ?::varchar");
      binds.add(query.currency().trim());
    }
    if (notBlank(query.supplierRefId())) {
      where.add("supplier_ref_id = ?::uuid");
      binds.add(query.supplierRefId().trim());
    }
    List<Record> rows =
        sql.fetch(
            "select "
                + periodExpr
                + " as period, currency, sum(amount) as amount, count(*) as fact_count"
                + " from purchase_analytics_fact where "
                + String.join(" and ", where)
                + " group by period, currency order by period, currency",
            binds.toArray());
    List<String> currencies = new ArrayList<>();
    for (Record row : rows) {
      String currency = row.get("currency", String.class);
      if (currency != null && !currencies.contains(currency)) {
        currencies.add(currency);
      }
    }
    List<PurchaseAnalyticsTrendPoint> points = new ArrayList<>();
    for (Record row : rows) {
      BigDecimal amount = row.get("amount", BigDecimal.class);
      long count = row.get("fact_count", Long.class);
      points.add(
          new PurchaseAnalyticsTrendPoint(
              row.get("period", String.class),
              row.get("currency", String.class),
              amount,
              count,
              count == 0 ? null : amount.divide(BigDecimal.valueOf(count), AMOUNT_SCALE, RoundingMode.HALF_UP)));
    }
    return new PurchaseAnalyticsTrend(
        window.asModel(grain),
        currencies,
        points,
        "采购金额趋势 = 到货流水口径（6.3.2.2）的金额按发生日归期，**按币种分列**；"
            + "跨币种不相加（本位币口径请看总览里的 baseAmount）。"
            + "分组粒度："
            + grainLabel(grain)
            + "（UTC 日历日）。",
        points.isEmpty()
            ? "这个窗口里没有到货采购事实 —— 可能是没同步过，也可能是这段时间真的没有到货。"
            : "");
  }

  // ================================================================ 供应商集中度

  /** 供应商集中度与贡献（按币种分段）。 */
  public PurchaseAnalyticsConcentration supplierConcentration(PurchaseAnalyticsQuery query, int topN) {
    UUID tenantId = currentTenant();
    Window window = window(query);
    int top = topN <= 0 ? 5 : Math.min(topN, 50);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?::uuid");
    binds.add(tenantId);
    where.add("fact_kind = 'COST'");
    where.add("source_type = 'ARRIVAL_RECEIPT'");
    where.add("occurred_date >= ?::date and occurred_date <= ?::date");
    binds.add(window.from());
    binds.add(window.to());
    if (notBlank(query.currency())) {
      where.add("currency = ?::varchar");
      binds.add(query.currency().trim());
    }
    if (notBlank(query.supplierRefId())) {
      where.add("supplier_ref_id = ?::uuid");
      binds.add(query.supplierRefId().trim());
    }
    List<Record> rows =
        sql.fetch(
            "select supplier_ref_id, supplier_code, supplier_name, currency,"
                + " sum(amount) as amount, count(*) as fact_count, sum(quantity) as quantity"
                + " from purchase_analytics_fact where "
                + String.join(" and ", where)
                + " group by supplier_ref_id, supplier_code, supplier_name, currency"
                + " order by currency, amount desc, supplier_code",
            binds.toArray());
    Map<String, List<Record>> byCurrency = new LinkedHashMap<>();
    for (Record row : rows) {
      byCurrency.computeIfAbsent(row.get("currency", String.class), k -> new ArrayList<>()).add(row);
    }
    List<PurchaseAnalyticsConcentrationBucket> buckets = new ArrayList<>();
    for (Map.Entry<String, List<Record>> entry : byCurrency.entrySet()) {
      BigDecimal total = BigDecimal.ZERO;
      for (Record row : entry.getValue()) {
        total = total.add(row.get("amount", BigDecimal.class));
      }
      List<PurchaseAnalyticsSupplierRow> suppliers = new ArrayList<>();
      BigDecimal hhi = BigDecimal.ZERO;
      BigDecimal cumulative = BigDecimal.ZERO;
      int index = 0;
      for (Record row : entry.getValue()) {
        BigDecimal amount = row.get("amount", BigDecimal.class);
        BigDecimal share =
            total.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : amount.divide(total, PERCENT_SCALE, RoundingMode.HALF_UP);
        hhi = hhi.add(share.multiply(share));
        cumulative = cumulative.add(share);
        suppliers.add(
            new PurchaseAnalyticsSupplierRow(
                row.get("supplier_ref_id", UUID.class),
                row.get("supplier_code", String.class),
                row.get("supplier_name", String.class),
                amount,
                share,
                cumulative,
                row.get("fact_count", Long.class),
                row.get("quantity", BigDecimal.class),
                null,
                entry.getKey()));
        index++;
      }
      BigDecimal topShare = BigDecimal.ZERO;
      for (int i = 0; i < Math.min(top, suppliers.size()); i++) {
        topShare = topShare.add(suppliers.get(i).share());
      }
      buckets.add(
          new PurchaseAnalyticsConcentrationBucket(
              entry.getKey(),
              suppliers,
              total,
              topShare,
              top,
              hhi.setScale(PERCENT_SCALE, RoundingMode.HALF_UP),
              "0–1 标度（乘 10000 即传统 HHI 标度；0 = 完全分散，1 = 全部押在一个供应商上）",
              suppliers.size(),
              null));
    }
    return new PurchaseAnalyticsConcentration(
        window.asModel("DAY"),
        buckets,
        "集中度 = 窗口内**同一币种**的采购金额份额："
            + "top-N 占比（top N = "
            + top
            + "）与 HHI（Σ 各供应商份额²）。"
            + "分母 = 该币种窗口内的采购金额合计；**跨币种不合并**。"
            + "供应商集中度是采购风险的一级指标：占比越高，供应商出问题时采购越被动。",
        buckets.isEmpty()
            ? "这个窗口里没有到货采购事实 —— 集中度算不出来（不是 0）。"
            : "");
  }

  // ================================================================ 价格波动

  /** 采购价格波动（**环比**：窗口末月 vs 上一月）。 */
  public PurchaseAnalyticsPriceVariance priceVariance(PurchaseAnalyticsQuery query, String dimension) {
    UUID tenantId = currentTenant();
    Window window = window(query);
    String dim = notBlank(dimension) && dimension.trim().equalsIgnoreCase("SKU") ? "SKU" : "SUPPLIER";
    LocalDate currentMonth = window.to().withDayOfMonth(1);
    LocalDate previousMonth = currentMonth.minusMonths(1);

    List<String> where = new ArrayList<>();
    List<Object> whereBinds = new ArrayList<>();
    where.add("tenant_id = ?::uuid");
    whereBinds.add(tenantId);
    where.add("fact_kind = 'COST'");
    where.add("source_type = 'ARRIVAL_RECEIPT'");
    where.add("occurred_date >= ?::date and occurred_date <= ?::date");
    whereBinds.add(previousMonth);
    whereBinds.add(currentMonth.plusMonths(1).minusDays(1));
    if (notBlank(query.currency())) {
      where.add("currency = ?::varchar");
      whereBinds.add(query.currency().trim());
    }
    if (notBlank(query.supplierRefId())) {
      where.add("supplier_ref_id = ?::uuid");
      whereBinds.add(query.supplierRefId().trim());
    }
    String keyExpr = dim.equals("SKU") ? "sku_code" : "supplier_code";
    String labelExpr = dim.equals("SKU") ? "sku_name" : "supplier_name";
    // ⚠️ 绑定顺序必须与 SQL 里的占位符顺序一致：SELECT 里的四个 CASE 占位符**在 WHERE 之前**。
    List<Object> binds = new ArrayList<>();
    binds.add(currentMonth);
    binds.add(currentMonth);
    binds.add(currentMonth);
    binds.add(currentMonth);
    binds.addAll(whereBinds);
    List<Record> rows =
        sql.fetch(
            "select "
                + keyExpr
                + " as dimension_key, "
                + labelExpr
                + " as dimension_label, currency,"
                + " sum(case when occurred_date >= ?::date then amount else 0 end) as current_amount,"
                + " sum(case when occurred_date >= ?::date then quantity else 0 end) as current_quantity,"
                + " sum(case when occurred_date < ?::date then amount else 0 end) as previous_amount,"
                + " sum(case when occurred_date < ?::date then quantity else 0 end) as previous_quantity,"
                + " count(*) as fact_count"
                + " from purchase_analytics_fact where "
                + String.join(" and ", where)
                + " group by dimension_key, dimension_label, currency"
                + " order by current_amount desc nulls last, dimension_key",
            binds.toArray());

    List<PurchaseAnalyticsPriceVarianceRow> items = new ArrayList<>();
    BigDecimal currentTotalAmount = BigDecimal.ZERO;
    BigDecimal currentTotalQuantity = BigDecimal.ZERO;
    BigDecimal previousTotalAmount = BigDecimal.ZERO;
    BigDecimal previousTotalQuantity = BigDecimal.ZERO;
    for (Record row : rows) {
      BigDecimal currentAmount = row.get("current_amount", BigDecimal.class);
      BigDecimal currentQuantity = row.get("current_quantity", BigDecimal.class);
      BigDecimal previousAmount = row.get("previous_amount", BigDecimal.class);
      BigDecimal previousQuantity = row.get("previous_quantity", BigDecimal.class);
      BigDecimal currentPrice =
          currentQuantity == null || currentQuantity.compareTo(BigDecimal.ZERO) == 0
              ? null
              : currentAmount.divide(currentQuantity, 6, RoundingMode.HALF_UP);
      BigDecimal previousPrice =
          previousQuantity == null || previousQuantity.compareTo(BigDecimal.ZERO) == 0
              ? null
              : previousAmount.divide(previousQuantity, 6, RoundingMode.HALF_UP);
      BigDecimal changeRate = null;
      String status;
      if (previousPrice == null) {
        status = "NO_PREVIOUS_DATA";
      } else if (currentPrice == null) {
        status = "NO_CURRENT_DATA";
      } else {
        changeRate =
            currentPrice
                .subtract(previousPrice)
                .divide(previousPrice, PERCENT_SCALE, RoundingMode.HALF_UP);
        status = changeRate.compareTo(BigDecimal.ZERO) > 0 ? "UP" : (changeRate.compareTo(BigDecimal.ZERO) < 0 ? "DOWN" : "FLAT");
      }
      items.add(
          new PurchaseAnalyticsPriceVarianceRow(
              row.get("dimension_key", String.class),
              row.get("dimension_label", String.class),
              row.get("currency", String.class),
              currentQuantity,
              currentAmount,
              currentPrice,
              previousQuantity,
              previousAmount,
              previousPrice,
              changeRate,
              row.get("fact_count", Long.class),
              status));
      currentTotalAmount = currentTotalAmount.add(currentAmount);
      currentTotalQuantity = currentTotalQuantity.add(currentQuantity);
      previousTotalAmount = previousTotalAmount.add(previousAmount);
      previousTotalQuantity = previousTotalQuantity.add(previousQuantity);
    }
    BigDecimal currentAverage =
        currentTotalQuantity.compareTo(BigDecimal.ZERO) == 0
            ? null
            : currentTotalAmount.divide(currentTotalQuantity, 6, RoundingMode.HALF_UP);
    BigDecimal previousAverage =
        previousTotalQuantity.compareTo(BigDecimal.ZERO) == 0
            ? null
            : previousTotalAmount.divide(previousTotalQuantity, 6, RoundingMode.HALF_UP);
    BigDecimal overall =
        currentAverage == null || previousAverage == null
            ? null
            : currentAverage.subtract(previousAverage).divide(previousAverage, PERCENT_SCALE, RoundingMode.HALF_UP);
    return new PurchaseAnalyticsPriceVariance(
        window.asModel("MONTH"),
        notBlank(query.currency()) ? query.currency().trim() : null,
        "环比（本期 = 窗口末月 " + currentMonth + " 起的那个月；上期 = 它的上一个自然月）",
        items,
        currentMonth.toString(),
        previousMonth.toString(),
        currentAverage,
        previousAverage,
        overall,
        "单价 = 金额 ÷ 数量（加权平均，同一币种内比较）；"
            + "变动率 =（本期单价 − 上期单价）÷ 上期单价。"
            + "**环比不是同比**；上期没有数据时状态是 NO_PREVIOUS_DATA，变动率给「无数据」而不是 0。"
            + "维度默认按**供应商** —— 到货跟踪是单级事实（一张采购单一行，不分 SKU），"
            + "采购单原单（6.3.1.x）未交付，所以 SKU 维度的价格波动在当前交付状态下**不可得**。",
        items.isEmpty()
            ? "这个窗口里没有到货采购事实 —— 价格波动算不出来（不是「价格没变」）。"
            : "");
  }

  // ================================================================ 到货准时率

  /**
   * 到货准时率。
   *
   * <p><b>分母是「承诺到货行」</b>：承诺到货日落在窗口内、且**已发生过到货事实**的
   * 到货跟踪行。行内任一次到货晚于承诺日 ⇒ 该行判 LATE（货没按时到就是没按时到）。
   * 承诺到货日当天不算晚（与 6.3.2.2 的日历日口径同源）。
   */
  public PurchaseAnalyticsOnTimeRate onTimeRate(PurchaseAnalyticsQuery query) {
    UUID tenantId = currentTenant();
    Window window = window(query);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("t.tenant_id = ?::uuid");
    binds.add(tenantId);
    where.add("t.promised_arrival_date >= ?::date and t.promised_arrival_date <= ?::date");
    binds.add(window.from());
    binds.add(window.to());
    where.add("t.arrival_status <> 'CANCELLED'");
    if (notBlank(query.supplierRefId())) {
      where.add("t.supplier_ref_id = ?::uuid");
      binds.add(query.supplierRefId().trim());
    }
    if (notBlank(query.currency())) {
      where.add("t.currency = ?::varchar");
      binds.add(query.currency().trim());
    }
    List<Record> rows =
        sql.fetch(
            "select t.id, t.tracking_no, t.purchase_order_no, t.supplier_name,"
                + " t.promised_arrival_date, t.currency,"
                + " max(r.arrived_at) as last_arrival, count(r.id) as receipt_count,"
                + " sum(r.arrived_qty) as arrived_qty,"
                + " sum(coalesce(pc.confirmation_amount, 0)) as amount"
                + " from purchase_arrival_tracking t"
                + " left join purchase_arrival_receipt r"
                + "   on r.tenant_id = t.tenant_id and r.tracking_id = t.id"
                + " left join purchase_arrival_payable_confirmation pc"
                + "   on pc.tenant_id = r.tenant_id and pc.receipt_id = r.id"
                + " where "
                + String.join(" and ", where)
                + " group by t.id, t.tracking_no, t.purchase_order_no, t.supplier_name,"
                + " t.promised_arrival_date, t.currency"
                + " order by t.promised_arrival_date, t.id",
            binds.toArray());
    long denominator = 0;
    long onTime = 0;
    long late = 0;
    long pending = 0;
    List<PurchaseAnalyticsOnTimeRow> lateRows = new ArrayList<>();
    for (Record row : rows) {
      long receiptCount = row.get("receipt_count", Long.class);
      LocalDate promised = row.get("promised_arrival_date", LocalDate.class);
      OffsetDateTime lastArrival = row.get("last_arrival", OffsetDateTime.class);
      if (receiptCount == 0) {
        pending++;
        continue;
      }
      denominator++;
      LocalDate lastDate =
          lastArrival == null ? null : lastArrival.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
      if (lastDate == null || promised == null || lastDate.isAfter(promised)) {
        late++;
        int delayDays = (lastDate == null || promised == null) ? 0 : (int) ChronoUnit.DAYS.between(promised, lastDate);
        lateRows.add(
            new PurchaseAnalyticsOnTimeRow(
                row.get("id", UUID.class),
                row.get("tracking_no", String.class),
                row.get("purchase_order_no", String.class),
                row.get("supplier_name", String.class),
                promised,
                lastArrival,
                row.get("arrived_qty", BigDecimal.class),
                row.get("currency", String.class),
                row.get("amount", BigDecimal.class),
                delayDays));
      } else {
        onTime++;
      }
    }
    BigDecimal rate =
        denominator == 0
            ? null
            : BigDecimal.valueOf(onTime)
                .divide(BigDecimal.valueOf(denominator), PERCENT_SCALE, RoundingMode.HALF_UP);
    return new PurchaseAnalyticsOnTimeRate(
        window.asModel("DAY"),
        denominator,
        onTime,
        late,
        pending,
        rate,
        "分母 = 承诺到货日落在窗口内、且已发生过到货事实的**到货跟踪行**（不是收货行）；"
            + "取消的跟踪单不计入；到货流水（分批到货）按**行**归并，行内任一次到货晚于承诺日即判 LATE。",
        "承诺到货日当天不算晚（与 6.3.2.2 的日历日口径同源，按 UTC 日历日比较）。",
        lateRows,
        "到货准时率 = 准时到货行 ÷ 承诺到货行。分子分母都写在响应里，不让读的人猜。",
        denominator == 0
            ? "窗口内没有可判定的承诺到货行 —— 准时率算不出来（不是 100%，也不是 0%）。"
            + "另有 "
            + pending
            + " 行承诺在窗口内但至今没有到货事实（未计入分母）。"
            : "另有 " + pending + " 行承诺在窗口内但至今没有到货事实（未计入分母）。");
  }

  // ================================================================ 采购成本占销售额比

  /**
   * 采购成本占销售额比。
   *
   * <p><b>分母在本仓不可用</b>：13 利润与经营分析未交付；5.3.1.1 是单 SKU 情景测算器；
   * trade 本库 sales_order 只有 DRAFT / SUBMITTED 的手工销售单。
   * ⇒ {@code available=false} + ownerModule + 影响说明，**不拿采购额当分母造一个假的比率**。
   */
  public PurchaseAnalyticsCostToSales costToSales(PurchaseAnalyticsQuery query) {
    UUID tenantId = currentTenant();
    Window window = window(query);
    List<PurchaseAnalyticsCurrencyAmount> purchase = purchaseAmounts(tenantId, window, query);
    return new PurchaseAnalyticsCostToSales(
        window.asModel("DAY"),
        false,
        "13 利润与经营分析",
        "采购成本（分子）已经算出来了，销售分母（分母）在本仓没有可用来源 ⇒ 这个比率**算不出来**。"
            + "等 13 利润与经营分析交付后，本端点直接亮起，前端不用改。"
            + "在此之前，界面必须显示「算不出」与原因，**不能**拿采购额当分母造一个假的比率。",
        null,
        null,
        purchase,
        null,
        "销售额分母不可用：① 13 利润与经营分析在本仓没有对应服务；"
            + "② 5.3.1.1 利润测算器是单 SKU 情景测算器（入参是手工假设的售价与费率），"
            + "拿它当区间销售额就是把假设当实绩；"
            + "③ trade 本库 sales_order 只有 DRAFT / SUBMITTED 的手工销售单，未确认收入。",
        "采购成本占销售额比 = 区间采购成本 ÷ 区间销售额；分子与分母必须**同币种**（或都带汇率快照折算到同一本位币），"
            + "否则不出比值。",
        "分子（采购成本，按币种分列）如下；分母缺失，所以比值是「无数据」，不是 0%。");
  }

  // ================================================================ 采购效率

  /** 按类目 / 店铺维度的采购效率。 */
  public PurchaseAnalyticsEfficiency efficiency(PurchaseAnalyticsQuery query, String dimension) {
    UUID tenantId = currentTenant();
    Window window = window(query);
    String dim = notBlank(dimension) && dimension.trim().equalsIgnoreCase("SHOP") ? "SHOP" : "CATEGORY";
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?::uuid");
    binds.add(tenantId);
    where.add("occurred_date >= ?::date and occurred_date <= ?::date");
    binds.add(window.from());
    binds.add(window.to());
    if (notBlank(query.categoryCode())) {
      where.add("category_code = ?::varchar");
      binds.add(query.categoryCode().trim());
    }
    if (notBlank(query.shopCode())) {
      where.add("shop_code = ?::varchar");
      binds.add(query.shopCode().trim());
    }
    String dimExpr = dim.equals("SHOP") ? "shop_code" : "category_code";
    String labelExpr = dim.equals("SHOP") ? "shop_code" : "category_name";
    List<Record> rows =
        sql.fetch(
            "select "
                + dimExpr
                + " as dimension_code, coalesce(nullif("
                + labelExpr
                + ", ''), '（未归类）') as dimension_label,"
                + " sum(case when fact_kind = 'PLAN' then quantity else 0 end) as planned_quantity,"
                + " sum(case when fact_kind = 'PLAN' then 1 else 0 end) as plan_line_count,"
                + " sum(case when fact_kind = 'RECEIPT' then quantity else 0 end) as received_quantity,"
                + " sum(case when fact_kind = 'RECEIPT' then 1 else 0 end) as receipt_fact_count,"
                + " max(case when fact_kind = 'PLAN' then unit_code else '' end) as unit_code"
                + " from purchase_analytics_fact where "
                + String.join(" and ", where)
                + " group by dimension_code, dimension_label"
                + " order by planned_quantity desc nulls last, dimension_code",
            binds.toArray());
    List<PurchaseAnalyticsEfficiencyRow> items = new ArrayList<>();
    for (Record row : rows) {
      BigDecimal planned = row.get("planned_quantity", BigDecimal.class);
      BigDecimal received = row.get("received_quantity", BigDecimal.class);
      String unit = row.get("unit_code", String.class);
      if (dim.equals("SHOP")) {
        items.add(
            new PurchaseAnalyticsEfficiencyRow(
                row.get("dimension_code", String.class),
                row.get("dimension_label", String.class),
                planned,
                null,
                null,
                row.get("plan_line_count", Long.class),
                row.get("receipt_fact_count", Long.class),
                unit.isBlank() ? null : unit,
                "PLAN_ONLY",
                "店铺维度只有计划侧口径：收货单不带店铺，实收量无法按店铺归属 ⇒ 不给达成率。"));
        continue;
      }
      BigDecimal rate = null;
      String status;
      if (planned == null || planned.compareTo(BigDecimal.ZERO) == 0) {
        status = "NO_PLAN";
      } else if (received == null) {
        status = "NO_RECEIPT";
      } else {
        rate = received.divide(planned, PERCENT_SCALE, RoundingMode.HALF_UP);
        status = rate.compareTo(BigDecimal.ONE) >= 0 ? "COMPLETED" : "PARTIAL";
      }
      items.add(
          new PurchaseAnalyticsEfficiencyRow(
              row.get("dimension_code", String.class),
              row.get("dimension_label", String.class),
              planned,
              received,
              rate,
              row.get("plan_line_count", Long.class),
              row.get("receipt_fact_count", Long.class),
              unit.isBlank() ? null : unit,
              status,
              "达成率 = 窗口内实收量 ÷ 窗口内计划量（按 SKU 聚合）。"
                  + "⚠️ 采购单原单（6.3.1.x）未交付 ⇒ 计划与收货**没有单据级关联**，"
                  + "这是 SKU × 窗口的履约近似，不是单据级达成率。"));
    }
    return new PurchaseAnalyticsEfficiency(
        window.asModel("DAY"),
        dim,
        items,
        dim.equals("CATEGORY") ? "按类目（catalog 的 SKU 主数据快照）" : "按店铺（大促日历；日常计划计入全店铺）",
        dim.equals("CATEGORY")
            ? "分母 = 窗口内采购计划量（6.2.1.3 计划行，按 SKU 基础单位）；"
                + "分子 = 窗口内实收量（8.2.1.1 收货单明细）；达成率 = 分子 ÷ 分母。"
                + "类目来自 catalog 的 SKU 主数据快照（解析不到的 SKU 计入「未归类」）。"
                + "⚠️ 采购单原单未交付 ⇒ 没有单据级关联，这是 SKU × 窗口的履约近似。"
            : "分母 = 窗口内采购计划量（6.2.1.3 计划行）；店铺来自大促日历，只有大促计划带店铺，"
                + "日常计划计入「（未归类）/ 全店铺」桶。收货单不带店铺 ⇒ 店铺维度**只给计划侧口径**。",
        items.isEmpty()
            ? "这个窗口里没有计划或收货事实 —— 效率算不出来（不是「效率为 0」）。"
            : "");
  }

  // ================================================================ 导出

  /**
   * 导出 CSV（分批写出，峰值内存与结果集大小无关）。
   *
   * <p>每批在流式线程里重新开租户事务 —— 租户上下文是事务级 GUC，controller 那个事务
   * 在响应开始前就结束了，跨线程复用它的连接会丢掉租户绑定。
   */
  public void streamCsv(
      AccessService.Access actor,
      PurchaseAnalyticsQuery query,
      java.util.function.Consumer<String> emit) {
    int size = 200;
    int offset = 0;
    emit.accept(
        "﻿事实键,事实种类,来源,来源单号,来源引用,发生时刻,发生日,供应商编码,供应商名称,"
            + "SKU编码,SKU名称,类目,店铺,仓库,数量,单位,单位系数,基础单位数量,单价,金额,币种,"
            + "本位币金额,汇率,汇率状态,汇率生效日,承诺到货日,准时状态,备注\n");
    while (true) {
      int at = offset;
      // 每批都在**自己的租户事务**里取：租户上下文是事务级 GUC，
      // 写流的那个线程上并没有 controller 那个事务的绑定。
      List<Record> batch =
          access.run(
              actor,
              "trade.purchase-analytics.read",
              () -> {
                List<String> where = new ArrayList<>();
                List<Object> binds = new ArrayList<>();
                appendFactFilter(where, binds, actor.tenantId(), query);
                return sql.fetch(
                    factSelectClause()
                        + " where "
                        + String.join(" and ", where)
                        + " order by "
                        + factSortClause(query.sort())
                        + " limit ? offset ?",
                    append(binds, size, (long) at * size).toArray());
              });
      if (batch.isEmpty()) {
        break;
      }
      var sb = new StringBuilder();
      for (Record row : batch) {
        List<String> cells = new ArrayList<>();
        cells.add(row.get("fact_key", String.class));
        cells.add(row.get("fact_kind", String.class));
        cells.add(row.get("source_type", String.class));
        cells.add(row.get("source_doc_no", String.class));
        cells.add(row.get("source_ref_no", String.class));
        cells.add(text(row.get("occurred_at", OffsetDateTime.class)));
        cells.add(text(row.get("occurred_date", LocalDate.class)));
        cells.add(row.get("supplier_code", String.class));
        cells.add(row.get("supplier_name", String.class));
        cells.add(row.get("sku_code", String.class));
        cells.add(row.get("sku_name", String.class));
        cells.add(row.get("category_code", String.class));
        cells.add(row.get("shop_code", String.class));
        cells.add(row.get("warehouse_code", String.class));
        cells.add(quantityText(row.get("quantity", BigDecimal.class)));
        cells.add(row.get("unit_code", String.class));
        cells.add(quantityText(row.get("unit_factor", BigDecimal.class)));
        cells.add(quantityText(row.get("base_quantity", BigDecimal.class)));
        cells.add(quantityText(row.get("unit_price", BigDecimal.class)));
        cells.add(quantityText(row.get("amount", BigDecimal.class)));
        cells.add(row.get("currency", String.class));
        cells.add(quantityText(row.get("base_amount", BigDecimal.class)));
        cells.add(quantityText(row.get("fx_rate", BigDecimal.class)));
        cells.add(row.get("fx_status", String.class));
        cells.add(text(row.get("fx_rate_effective_date", LocalDate.class)));
        cells.add(text(row.get("promised_arrival_date", LocalDate.class)));
        cells.add(row.get("on_time_status", String.class));
        cells.add(row.get("note", String.class));
        sb.append(String.join(",", cells.stream().map(PurchaseAnalyticsService::escape).toList()));
        sb.append('\n');
      }
      emit.accept(sb.toString());
      offset += batch.size();
    }
  }

  private String factSelectClause() {
    return "select id, fact_key, fact_kind, source_type, source_doc_no, source_doc_id,"
        + " source_ref_no, occurred_at, occurred_date, supplier_ref_id, supplier_code,"
        + " supplier_name, sku_ref_id, sku_code, sku_name, category_code, category_name,"
        + " shop_code, warehouse_code, quantity, unit_code, unit_factor, base_quantity,"
        + " unit_price, amount, currency, base_currency, fx_rate, base_amount, fx_status,"
        + " fx_rate_id, fx_rate_effective_date, promised_arrival_date, on_time_status, note"
        + " from purchase_analytics_fact";
  }

  // ================================================================ 内部结构

  /** 窗口（起止都含）。 */
  private record Window(LocalDate from, LocalDate to) {
    PurchaseAnalyticsWindow asModel(String granularity) {
      return new PurchaseAnalyticsWindow(from, to, granularity);
    }
  }

  /** 汇率快照。 */
  private record Fx(
      String status,
      BigDecimal rate,
      UUID rateId,
      LocalDate effectiveDate,
      String baseCurrency,
      BigDecimal baseAmount) {

    static Fx notApplicable() {
      return new Fx("NOT_APPLICABLE", null, null, null, null, null);
    }
  }

  /** SKU 维度快照（类目 + 单位换算）。 */
  private record SkuDimension(
      UUID skuId,
      String skuCode,
      String skuName,
      String categoryCode,
      String categoryName,
      String baseUnit,
      String purchaseUnit,
      BigDecimal unitFactor) {}

  /** 一条待落库的事实（字段名与表列一一对应，避免位置参数传错列）。 */
  private record Fact(
      String factKey,
      String factKind,
      String sourceType,
      String sourceDocNo,
      UUID sourceDocId,
      String sourceRefNo,
      OffsetDateTime occurredAt,
      LocalDate occurredDate,
      UUID supplierRefId,
      String supplierCode,
      String supplierName,
      UUID skuRefId,
      String skuCode,
      String skuName,
      String categoryCode,
      String categoryName,
      String shopCode,
      String warehouseCode,
      BigDecimal quantity,
      String unitCode,
      BigDecimal unitFactor,
      BigDecimal baseQuantity,
      BigDecimal unitPrice,
      BigDecimal amount,
      String currency,
      Fx fx,
      LocalDate promisedArrivalDate,
      String onTimeStatus,
      String note) {}

  // ================================================================ 内部实现

  private void appendFactFilter(
      List<String> where, List<Object> binds, UUID tenantId, PurchaseAnalyticsQuery query) {
    where.add("tenant_id = ?::uuid");
    binds.add(tenantId);
    Window window = window(query);
    where.add("occurred_date >= ?::date and occurred_date <= ?::date");
    binds.add(window.from());
    binds.add(window.to());
    if (notBlank(query.factKind())) {
      where.add("fact_kind = ?::varchar");
      binds.add(query.factKind().trim());
    }
    if (notBlank(query.sourceType())) {
      where.add("source_type = ?::varchar");
      binds.add(query.sourceType().trim());
    }
    if (notBlank(query.currency())) {
      where.add("currency = ?::varchar");
      binds.add(query.currency().trim());
    }
    if (notBlank(query.supplierRefId())) {
      where.add("supplier_ref_id = ?::uuid");
      binds.add(query.supplierRefId().trim());
    }
    if (notBlank(query.categoryCode())) {
      where.add("category_code = ?::varchar");
      binds.add(query.categoryCode().trim());
    }
    if (notBlank(query.shopCode())) {
      where.add("shop_code = ?::varchar");
      binds.add(query.shopCode().trim());
    }
    if (notBlank(query.onTimeStatus())) {
      where.add("on_time_status = ?::varchar");
      binds.add(query.onTimeStatus().trim());
    }
    if (notBlank(query.keyword())) {
      where.add(
          "(source_doc_no ilike ?::varchar or source_ref_no ilike ?::varchar"
              + " or supplier_name ilike ?::varchar or sku_code ilike ?::varchar"
              + " or sku_name ilike ?::varchar)");
      String like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
  }

  /** 排序白名单：调用方不能塞任意 SQL 进来。 */
  private static String factSortClause(String sort) {
    String key = sort == null ? "" : sort.trim().toLowerCase();
    return switch (key) {
      case "amount" -> "amount desc nulls last, occurred_at desc, id desc";
      case "quantity" -> "quantity desc nulls last, occurred_at desc, id desc";
      case "supplier" -> "supplier_code asc, occurred_at desc, id desc";
      case "sku" -> "sku_code asc, occurred_at desc, id desc";
      case "occurred_asc" -> "occurred_at asc, id asc";
      default -> "occurred_at desc, id desc";
    };
  }

  private Window window(PurchaseAnalyticsQuery query) {
    LocalDate to = query.to() == null ? today() : query.to();
    LocalDate from = query.from() == null ? to.minusDays(89) : query.from();
    if (from.isAfter(to)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "窗口起点不能晚于窗口终点");
    }
    return new Window(from, to);
  }

  private static LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  /** 本位币（catalog 的法人本位币面板）。读不到 ⇒ 不折算。 */
  private String resolveBaseCurrency(String bearer, List<String> notes) {
    List<PurchaseAnalyticsCatalogPort.FunctionalCurrencyRow> rows =
        catalog.functionalCurrencies(bearer);
    String currency = null;
    int distinct = 0;
    for (PurchaseAnalyticsCatalogPort.FunctionalCurrencyRow row : rows) {
      String base = row.baseCurrency();
      if (base == null || base.isBlank()) {
        continue;
      }
      if (currency == null) {
        currency = base;
      } else if (!currency.equals(base)) {
        distinct++;
      }
    }
    if (currency == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "本位币读不到（catalog 的法人本位币面板为空）");
    }
    if (distinct > 0) {
      notes.add(
          "本租户有多个法人本位币，本次按 "
              + currency
              + " 折算 ⇒ 跨法人的本位币汇总是近似（到货事实不带法人）。");
    }
    return currency;
  }

  /** 取汇率台账（每个币种一条链，按发生日 as-of 取「当天或之前最新的一条」）。 */
  private Map<String, List<PurchaseAnalyticsCatalogPort.RateView>> loadRates(
      String bearer,
      String baseCurrency,
      List<Record> rows,
      int[] counters) {
    Map<String, List<PurchaseAnalyticsCatalogPort.RateView>> rates = new HashMap<>();
    if (baseCurrency == null) {
      return rates;
    }
    java.util.TreeSet<String> currencies = new java.util.TreeSet<>();
    for (Record row : rows) {
      String currency = row.get("currency", String.class);
      if (currency != null && !currency.isBlank() && !currency.equals(baseCurrency)) {
        currencies.add(currency);
      }
    }
    for (String currency : currencies) {
      try {
        rates.put(
            currency,
            catalog.rates(currency, baseCurrency, null, null, bearer));
      } catch (RuntimeException e) {
        counters[5]++;
      }
    }
    return rates;
  }

  /** 按发生日取汇率快照；取不到 ⇒ UNSET（绝不按 1:1 折算）。 */
  private Fx fxFor(
      String currency,
      LocalDate occurredDate,
      String baseCurrency,
      Map<String, List<PurchaseAnalyticsCatalogPort.RateView>> rates,
      int[] counters) {
    if (currency == null || currency.isBlank() || baseCurrency == null) {
      counters[5]++;
      return new Fx("UNSET", null, null, null, baseCurrency, null);
    }
    if (currency.equals(baseCurrency)) {
      return new Fx("SAME_CURRENCY", null, null, null, baseCurrency, null);
    }
    List<PurchaseAnalyticsCatalogPort.RateView> chain = rates.get(currency);
    if (chain == null || chain.isEmpty()) {
      counters[5]++;
      return new Fx("UNSET", null, null, null, baseCurrency, null);
    }
    PurchaseAnalyticsCatalogPort.RateView chosen = null;
    for (PurchaseAnalyticsCatalogPort.RateView rate : chain) {
      LocalDate effective = rate.effectiveDate();
      if (effective == null || occurredDate == null || effective.isAfter(occurredDate)) {
        continue;
      }
      if (chosen == null
          || (chosen.effectiveDate() != null && effective.isAfter(chosen.effectiveDate()))) {
        chosen = rate;
      }
    }
    if (chosen == null) {
      counters[5]++;
      return new Fx("UNSET", null, null, null, baseCurrency, null);
    }
    return new Fx(
        "APPLIED",
        chosen.rate(),
        chosen.id(),
        chosen.effectiveDate(),
        baseCurrency,
        null);
  }

  /** SKU 维度（解析一次，缓存在本批同步里；解析结果冻结进维度表）。 */
  private SkuDimension skuDimension(
      UUID skuId,
      String bearer,
      Map<UUID, SkuDimension> cache,
      int[] counters,
      List<String> notes) {
    if (skuId == null) {
      return null;
    }
    if (cache.containsKey(skuId)) {
      return cache.get(skuId);
    }
    SkuDimension dimension = null;
    try {
      PurchaseAnalyticsCatalogPort.SkuView view = catalog.sku(skuId, bearer);
      String category = view.category() == null ? "" : view.category();
      String purchaseUnit = "";
      BigDecimal unitFactor = null;
      if (view.unitConversions() != null) {
        for (PurchaseAnalyticsCatalogPort.ConversionView conversion : view.unitConversions()) {
          if (conversion != null
              && "PURCHASE".equalsIgnoreCase(conversion.role())
              && conversion.quantityPerBase() != null
              && conversion.quantityPerBase().compareTo(BigDecimal.ZERO) > 0) {
            purchaseUnit = conversion.unitCode() == null ? "" : conversion.unitCode();
            unitFactor = conversion.quantityPerBase();
            break;
          }
        }
      }
      dimension =
          new SkuDimension(
              view.id(),
              view.code() == null ? "" : view.code(),
              view.name() == null ? "" : view.name(),
              category,
              category,
              view.baseUnit() == null ? "" : view.baseUnit(),
              purchaseUnit,
              unitFactor);
      saveSkuDimension(skuId, dimension, "RESOLVED", "");
    } catch (ResponseStatusException e) {
      counters[4]++;
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        saveSkuDimension(skuId, null, "NOT_FOUND", reason(e));
      } else {
        saveSkuDimension(skuId, null, "UNAVAILABLE", reason(e));
        notes.add("类目解析暂时不可用（catalog 读不到）：" + reason(e) + " ⇒ 相关事实的类目留空，界面按「未归类」显示。");
      }
    }
    cache.put(skuId, dimension);
    return dimension;
  }

  private void saveSkuDimension(UUID skuId, SkuDimension dimension, String status, String note) {
    sql.execute(
        "insert into purchase_analytics_sku_dimension"
            + " (id, tenant_id, sku_id, sku_code, sku_name, category_code, category_name,"
            + " base_unit, purchase_unit, unit_factor, resolve_status, note, resolved_at,"
            + " created_at, updated_at)"
            + " values (?::uuid, ?::uuid, ?::uuid, ?::varchar, ?::varchar, ?::varchar, ?::varchar,"
            + " ?::varchar, ?::varchar, ?::numeric, ?::varchar, ?::varchar, ?::timestamptz,"
            + " now(), now())"
            + " on conflict (tenant_id, sku_id) do update set"
            + " sku_code = excluded.sku_code, sku_name = excluded.sku_name,"
            + " category_code = excluded.category_code, category_name = excluded.category_name,"
            + " base_unit = excluded.base_unit, purchase_unit = excluded.purchase_unit,"
            + " unit_factor = excluded.unit_factor, resolve_status = excluded.resolve_status,"
            + " note = excluded.note, resolved_at = excluded.resolved_at, updated_at = now()",
        UUID.randomUUID(),
        currentTenant(),
        skuId,
        dimension == null ? "" : dimension.skuCode(),
        dimension == null ? "" : dimension.skuName(),
        dimension == null ? "" : dimension.categoryCode(),
        dimension == null ? "" : dimension.categoryName(),
        dimension == null ? "" : dimension.baseUnit(),
        dimension == null ? "" : dimension.purchaseUnit(),
        dimension == null ? null : dimension.unitFactor(),
        status,
        note,
        OffsetDateTime.now());
  }

  /** 事实落库（幂等 upsert）。 */
  private void upsertFact(UUID tenantId, Fact fact, int[] counters) {
    BigDecimal baseAmount = null;
    if ("APPLIED".equals(fact.fx().status()) && fact.fx().rate() != null && fact.amount() != null) {
      baseAmount = fact.amount().multiply(fact.fx().rate()).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    } else if ("SAME_CURRENCY".equals(fact.fx().status()) && fact.amount() != null) {
      baseAmount = fact.amount();
    }
    // `returning (xmax = 0) as inserted`：PG 里**新插入**的行 xmax 为 0，被 upsert 更新的行不为 0
    // ⇒ 一次往返就能分清「新增」与「更新」，同步计数因此是真的（重跑一次不会把新增数说成新增）。
    boolean inserted =
        sql.fetchOne(
                "insert into purchase_analytics_fact"
                    + " (id, tenant_id, fact_key, fact_kind, source_type, source_doc_no, source_doc_id,"
                    + " source_ref_no, occurred_at, occurred_date, supplier_ref_id, supplier_code,"
                    + " supplier_name, sku_ref_id, sku_code, sku_name, category_code, category_name,"
                    + " shop_code, warehouse_code, quantity, unit_code, unit_factor, base_quantity,"
                    + " unit_price, amount, currency, base_currency, fx_rate, base_amount, fx_status,"
                    + " fx_rate_id, fx_rate_effective_date, promised_arrival_date, on_time_status, note,"
                    + " created_at, updated_at)"
                    + " values (?::uuid, ?::uuid, ?::varchar, ?::varchar, ?::varchar, ?::varchar,"
                    + " ?::uuid, ?::varchar, ?::timestamptz, ?::date, ?::uuid, ?::varchar, ?::varchar,"
                    + " ?::uuid, ?::varchar, ?::varchar, ?::varchar, ?::varchar, ?::varchar, ?::varchar,"
                    + " ?::numeric, ?::varchar, ?::numeric, ?::numeric, ?::numeric, ?::numeric, ?::varchar,"
                    + " ?::varchar, ?::numeric, ?::numeric, ?::varchar, ?::uuid, ?::date, ?::date,"
                    + " ?::varchar, ?::varchar, now(), now())"
                    + " on conflict (tenant_id, fact_key) do update set"
                    + " fact_kind = excluded.fact_kind, source_type = excluded.source_type,"
                    + " source_doc_no = excluded.source_doc_no, source_doc_id = excluded.source_doc_id,"
                    + " source_ref_no = excluded.source_ref_no, occurred_at = excluded.occurred_at,"
                    + " occurred_date = excluded.occurred_date,"
                    + " supplier_ref_id = excluded.supplier_ref_id,"
                    + " supplier_code = excluded.supplier_code, supplier_name = excluded.supplier_name,"
                    + " sku_ref_id = excluded.sku_ref_id, sku_code = excluded.sku_code,"
                    + " sku_name = excluded.sku_name, category_code = excluded.category_code,"
                    + " category_name = excluded.category_name, shop_code = excluded.shop_code,"
                    + " warehouse_code = excluded.warehouse_code, quantity = excluded.quantity,"
                    + " unit_code = excluded.unit_code, unit_factor = excluded.unit_factor,"
                    + " base_quantity = excluded.base_quantity, unit_price = excluded.unit_price,"
                    + " amount = excluded.amount, currency = excluded.currency,"
                    + " base_currency = excluded.base_currency, fx_rate = excluded.fx_rate,"
                    + " base_amount = excluded.base_amount, fx_status = excluded.fx_status,"
                    + " fx_rate_id = excluded.fx_rate_id,"
                    + " fx_rate_effective_date = excluded.fx_rate_effective_date,"
                    + " promised_arrival_date = excluded.promised_arrival_date,"
                    + " on_time_status = excluded.on_time_status, note = excluded.note,"
                    + " updated_at = now()"
                    + " returning (xmax = 0) as inserted",
                UUID.randomUUID(),
                tenantId,
                fact.factKey(),
                fact.factKind(),
                fact.sourceType(),
                fact.sourceDocNo() == null ? "" : fact.sourceDocNo(),
                fact.sourceDocId(),
                fact.sourceRefNo() == null ? "" : fact.sourceRefNo(),
                fact.occurredAt(),
                fact.occurredDate(),
                fact.supplierRefId(),
                fact.supplierCode() == null ? "" : fact.supplierCode(),
                fact.supplierName() == null ? "" : fact.supplierName(),
                fact.skuRefId(),
                fact.skuCode() == null ? "" : fact.skuCode(),
                fact.skuName() == null ? "" : fact.skuName(),
                fact.categoryCode() == null ? "" : fact.categoryCode(),
                fact.categoryName() == null ? "" : fact.categoryName(),
                fact.shopCode() == null ? "" : fact.shopCode(),
                fact.warehouseCode() == null ? "" : fact.warehouseCode(),
                fact.quantity() == null ? BigDecimal.ZERO : fact.quantity(),
                fact.unitCode() == null ? "" : fact.unitCode(),
                fact.unitFactor(),
                fact.baseQuantity(),
                fact.unitPrice(),
                fact.amount(),
                fact.currency(),
                fact.fx().baseCurrency(),
                fact.fx().rate(),
                baseAmount,
                fact.fx().status(),
                fact.fx().rateId(),
                fact.fx().effectiveDate(),
                fact.promisedArrivalDate(),
                fact.onTimeStatus(),
                fact.note())
            .get("inserted", Boolean.class);
    if (inserted) {
      counters[1]++;
    } else {
      counters[2]++;
    }
  }

  private void insertSyncRun(
      String runNo, String sourceType, LocalDate from, LocalDate to, UUID actorId) {
    sql.execute(
        "insert into purchase_analytics_sync_run"
            + " (id, tenant_id, run_no, source_type, window_start, window_end, status,"
            + " triggered_by, started_at, created_at)"
            + " values (?::uuid, ?::uuid, ?::varchar, ?::varchar, ?::date, ?::date, 'RUNNING',"
            + " ?::uuid, ?::timestamptz, now())",
        UUID.randomUUID(),
        currentTenant(),
        runNo,
        sourceType,
        from,
        to,
        actorId,
        OffsetDateTime.now());
  }

  private void finishSyncRun(String runNo, String status, int[] counters, String message) {
    sql.execute(
        "update purchase_analytics_sync_run set status = ?::varchar, scanned_count = ?,"
            + " created_count = ?, updated_count = ?, skipped_count = ?, unresolved_sku_count = ?,"
            + " unconverted_count = ?, message = ?::varchar, finished_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and run_no = ?::varchar",
        status,
        counters[0],
        counters[1],
        counters[2],
        counters[3],
        counters[4],
        counters[5],
        message,
        OffsetDateTime.now(),
        currentTenant(),
        runNo);
  }

  /** 同步批次留痕（真分页）。 */
  public PurchaseAnalyticsSyncRunPage syncRuns(int page, int size) {
    UUID tenantId = currentTenant();
    int pageNo = Math.max(0, page);
    int pageSize = pageSize(size);
    long total =
        sql.fetchOne(
                "select count(*) from purchase_analytics_sync_run where tenant_id = ?::uuid",
                tenantId)
            .get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            "select id, run_no, source_type, window_start, window_end, status, scanned_count,"
                + " created_count, updated_count, skipped_count, unresolved_sku_count,"
                + " unconverted_count, message, started_at, finished_at"
                + " from purchase_analytics_sync_run where tenant_id = ?::uuid"
                + " order by started_at desc, id desc limit ? offset ?",
            tenantId,
            pageSize,
            (long) pageNo * pageSize);
    List<PurchaseAnalyticsSyncRunRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(
          new PurchaseAnalyticsSyncRunRow(
              row.get("id", UUID.class),
              row.get("run_no", String.class),
              row.get("source_type", String.class),
              row.get("window_start", LocalDate.class),
              row.get("window_end", LocalDate.class),
              row.get("status", String.class),
              row.get("scanned_count", Integer.class),
              row.get("created_count", Integer.class),
              row.get("updated_count", Integer.class),
              row.get("skipped_count", Integer.class),
              row.get("unresolved_sku_count", Integer.class),
              row.get("unconverted_count", Integer.class),
              row.get("message", String.class),
              row.get("started_at", OffsetDateTime.class),
              row.get("finished_at", OffsetDateTime.class)));
    }
    return new PurchaseAnalyticsSyncRunPage(
        items,
        total,
        pageNo,
        pageSize,
        items.isEmpty() ? "还没有同步过上游事实 —— 报表此刻是空的。" : "");
  }

  // ================================================================ 映射与工具

  private PurchaseAnalyticsFactRow factRow(Record row) {
    return new PurchaseAnalyticsFactRow(
        row.get("id", UUID.class),
        row.get("fact_key", String.class),
        row.get("fact_kind", String.class),
        row.get("source_type", String.class),
        row.get("source_doc_no", String.class),
        row.get("source_doc_id", UUID.class),
        row.get("source_ref_no", String.class),
        row.get("occurred_at", OffsetDateTime.class),
        row.get("occurred_date", LocalDate.class),
        row.get("supplier_ref_id", UUID.class),
        row.get("supplier_code", String.class),
        row.get("supplier_name", String.class),
        row.get("sku_ref_id", UUID.class),
        row.get("sku_code", String.class),
        row.get("sku_name", String.class),
        row.get("category_code", String.class),
        row.get("category_name", String.class),
        row.get("shop_code", String.class),
        row.get("warehouse_code", String.class),
        row.get("quantity", BigDecimal.class),
        row.get("unit_code", String.class),
        row.get("unit_factor", BigDecimal.class),
        row.get("base_quantity", BigDecimal.class),
        row.get("unit_price", BigDecimal.class),
        row.get("amount", BigDecimal.class),
        row.get("currency", String.class),
        row.get("base_currency", String.class),
        row.get("fx_rate", BigDecimal.class),
        row.get("base_amount", BigDecimal.class),
        row.get("fx_status", String.class),
        row.get("fx_rate_id", UUID.class),
        row.get("fx_rate_effective_date", LocalDate.class),
        row.get("promised_arrival_date", LocalDate.class),
        row.get("on_time_status", String.class),
        row.get("note", String.class));
  }

  private static String normalizeSourceType(String sourceType) {
    String value = sourceType == null ? "" : sourceType.trim().toUpperCase();
    if (value.isEmpty()) {
      return "ALL";
    }
    if (!List.of("ALL", "ARRIVAL_RECEIPT", "PURCHASE_PLAN_LINE", "GOODS_RECEIPT", "SUPPLIER_CLAIM")
        .contains(value)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "sourceType 只能是 ALL / ARRIVAL_RECEIPT / PURCHASE_PLAN_LINE / GOODS_RECEIPT / SUPPLIER_CLAIM");
    }
    return value;
  }

  private static String normalizeGranularity(String granularity) {
    String value = granularity == null ? "" : granularity.trim().toUpperCase();
    return switch (value) {
      case "DAY" -> "DAY";
      case "WEEK" -> "WEEK";
      case "MONTH" -> "MONTH";
      default -> "MONTH";
    };
  }

  private static String grainLabel(String grain) {
    return switch (grain) {
      case "DAY" -> "按日";
      case "WEEK" -> "按周（周一起）";
      default -> "按月";
    };
  }

  private static String runNo() {
    return "PA-" + OffsetDateTime.now(ZoneOffset.UTC).toLocalDate().toString().replace("-", "")
        + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
  }

  private static String reason(RuntimeException e) {
    if (e instanceof ResponseStatusException status && status.getReason() != null) {
      return status.getReason();
    }
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static int pageSize(int size) {
    if (size <= 0) {
      return 50;
    }
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private static List<Object> append(List<Object> binds, Object... extra) {
    List<Object> all = new ArrayList<>(binds);
    all.addAll(List.of(extra));
    return all;
  }

  private static String text(Object value) {
    return value == null ? "" : value.toString();
  }

  /** 数量的 CSV 文本：null 一律给「无数据」—— 空格会被读成「数量是 0」。 */
  private static String quantityText(BigDecimal value) {
    return value == null ? "无数据" : value.stripTrailingZeros().toPlainString();
  }

  /** CSV 渲染：字段按 RFC 4180 转义，中文表头让采购员拿到文件就能看懂。 */
  private static String escape(String value) {
    String text = value == null ? "" : value;
    // 前导 =/+/-/@ 会被 Excel 当成公式执行：加前缀单引号切断，这是 CSV 注入的常规处置。
    if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) {
      text = "'" + text;
    }
    return '"' + text.replace("\"", "\"\"") + '"';
  }

  /** 当前租户（来自数据库里绑定的 {@code app.current_tenant}）。 */
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
