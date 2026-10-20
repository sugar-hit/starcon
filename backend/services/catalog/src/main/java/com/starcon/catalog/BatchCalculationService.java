package com.starcon.catalog;

import static com.starcon.catalog.BatchCalculationModels.*;
import static com.starcon.catalog.ProfitCalculatorModels.*;

import com.starcon.catalog.BatchCalculationModels.BatchCalculationCapabilities;
import com.starcon.catalog.BatchCalculationModels.BatchCalculationRequest;
import com.starcon.catalog.BatchCalculationModels.BatchResultPage;
import com.starcon.catalog.BatchCalculationModels.BatchResultRow;
import com.starcon.catalog.BatchCalculationModels.BatchRunDetail;
import com.starcon.catalog.BatchCalculationModels.BatchRunPage;
import com.starcon.catalog.BatchCalculationModels.BatchSummary;
import com.starcon.catalog.BatchCalculationModels.Flag;
import com.starcon.catalog.BatchCalculationModels.ResultQuery;
import com.starcon.catalog.BatchCalculationModels.ResultStatus;
import com.starcon.catalog.BatchCalculationModels.Sort;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 5.3.1.2 批量测算：对一批 SKU 批量跑利润测算，输出利润表 + 异常标记，支持筛选与导出。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：对一批 SKU 批量测算，按当前成本与规则输出利润表，标记亏损品与低毛利品，支持导出并筛选（毛利率 &lt; X%、售价 &lt; Y）。
 *
 * **输入**：SKU 范围（按分类/品牌/店铺）；测算参数。
 *
 * **输出**：批量测算结果表 + 异常标记。
 *
 * **关系**：上游 = 5.3.1.1；下游 = 5.4.1.2。**批量测算是选品与清库存决策的依据**：亏损品要么涨价要么清仓，人工逐个测算不可行。
 *
 * </blockquote>
 *
 * <h2>🔴 本类的六条纪律</h2>
 *
 * <ol>
 *   <li><b>🔴 复用 5.3.1.1 的测算引擎，不写第二套算术。</b>
 *       批量测算 = 把 {@link ProfitCalculatorService#calculate} 跑 N 次，每次换一个 SKU 与它的售价。
 *       金额精度、费率精度、决策就绪闸全部沿用 —— 下游 5.4.1.2 批量调价会拿这里的数字直接改价，
 *       两套口径必然改错价。</li>
 *   <li><b>🔴 取不到售价就跳过，绝不拿 0 顶。</b>
 *       5.1 价目表未交付 ⇒ 唯一的真实售价来源是 5.5.2.1 异常告警的**价格观测台账**
 *       （V222 {@code price_anomaly_observation}）。没有观测的 SKU 记 {@code SKIPPED_NO_PRICE}，
 *       它的售价、毛利、毛利率全是 null，标记全是 false ——
 *       用 0 顶会被算成「亏损品」，那是凭空造出来的清库存依据。</li>
 *   <li><b>🔴 成本不完整就不给毛利率数字。</b>
 *       沿用 5.3.1.1 的决策就绪闸：缺项时 {@code grossProfit} / {@code grossMarginRate} 是 null，
 *       不是 0、不是估算值；这样的行也**不会**被标成低毛利品
 *       —— 它是「没测准」，不是「毛利低」。</li>
 *   <li><b>🔴 跨币种绝不相加。</b>售价币种与成本币种不同必须要汇率（方向显式）；
 *       价格观测的币种与本次测算的币种不一致时**整行跳过**，不做 1:1 折算。</li>
 *   <li><b>🔴 汇总数字读时聚合，不落库。</b>落库会与结果表漂移，
 *       而且落库的汇总做不到「随当前可见范围变化」。</li>
 *   <li><b>租户只来自已认证成员关系</b>（{@link AccessService}），不读 {@code X-Tenant-Id} 头。
 *       每条 SQL 的 where 都带显式 {@code tenant_id=} 绑定，RLS 再兜一道。</li>
 * </ol>
 */
@Service
public class BatchCalculationService {

  /** 租户上下文取法：RLS 已按它隔离，这里带上只是让执行计划走租户前缀的索引。 */
  private static final String TENANT = "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 台账页大小上限：与相邻模块一致。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 成本口径窗口的默认跨度（天）。与 5.3.1.1 / 5.2.1.1 一致。 */
  private static final int DEFAULT_WINDOW_DAYS = 90;

  private final DSLContext sql;
  private final ProfitCalculatorService calculator;
  private final ChannelPlatformPort platforms;

  public BatchCalculationService(
      DSLContext sql, ProfitCalculatorService calculator, ChannelPlatformPort platforms) {
    this.sql = sql;
    this.calculator = calculator;
    this.platforms = platforms;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：口径、上游真实交付状态、跳过规则、筛选与导出的边界。
   *
   * <p>页面上把它固定显示在顶部 —— 运营要拿这一页的数字决定「涨价还是清仓」，
   * 所以口径必须**摆在数字旁边**，不是躺在文档里。
   */
  public BatchCalculationCapabilities capabilities() {
    return new BatchCalculationCapabilities(
        List.of(),
        "🔴 **售价来源**：本仓的价目表（5.1）**未交付**，所以这里唯一的真实售价来源是"
            + "5.5.2.1 异常告警的**价格观测台账**（price_anomaly_observation）——"
            + "取该 SKU 在该店铺**最新一条**观测：优先取我方价（internal_price），"
            + "没有就退到平台在售价（platform_price），并在结果里标出用的是哪一种。"
            + "**取不到就整行跳过**（跳过：没有价格观测），绝不用 0 顶 —— "
            + "0 会被算成「亏损品」，那是凭空造出来的清库存依据。"
            + "价格台账是**只追加**的，所以「最新一条」是一个时间点而不是「当前价」："
            + "结果里的「价格观测时间」列写明了用的是哪一刻的价。",
        "上游 = **5.3.1.1 利润测算器**（本服务内，V227）。本模块**不重写**它的利润拆解算法，"
            + "而是复用它的引擎跑 N 次（每个 SKU 一次），因此金额精度（4 位小数）、"
            + "费率精度（6 位小数）、成本口径与决策就绪闸与 5.3.1.1 **逐字相同**。"
            + "⚠️ 5.3.1.1 的上游缺口在本模块同样存在：5.2.3.1 平台费用与 5.2.3.2 物流与头程费用未交付，"
            + "所以平台费率与物流单价只能作为**手工假设**由人填（页面上标「手工假设」）。",
        "下游 = **5.4.1.2 批量调价**。它会拿本模块的利润表直接改价，"
            + "所以它必须先看每行的 decisionReady：成本不完整的行**没有**毛利率数字可读，"
            + "只有缺口清单。5.4.1.2 也不该把「跳过（无价格观测）」的行当成「毛利率 0 的亏损品」。",
        "🔴 **决策就绪闸（沿用 5.3.1.1）**：只要有一项扣减取不到（平台费用 / 物流成本 / 汇率等），"
            + "这一行的毛利额与毛利率就是 **null**，不是 0、不是估算值。"
            + "这样的行**不会**被标成亏损品或低毛利品 —— 它是「没测准」，不是「毛利低」。"
            + "这条闸落在服务层：下游批量调价会程序化消费这些数字，页面上的一句提示拦不住一个自动改价的规则。",
        "SKU 范围按**分类 / 品牌 / 店铺**三个维度：分类与品牌取 spu 上的文本列并**精确匹配**"
            + "（不是模糊匹配 —— 分类名里的空格与大小写在不同来源的导入里经常不一致，"
            + "模糊匹配会静默多算一批 SKU）；店铺 = 平台码 + 站点码，"
            + "来自 2.1.1.1 平台目录（跨服务只读），并且决定取价与币种。"
            + "一次批量测算最多覆盖 **" + BatchCalculationRules.MAX_SCOPE_SIZE + " 个 SKU**："
            + "超了**明确报错**让你缩小范围，不静默截断（每个 SKU 的成本构成要跨服务读 warehouse）。",
        "异常标记有两个，**互斥**：**亏损品**（毛利额 &lt; 0）与**低毛利品**"
            + "（还没亏，但毛利率低于你在本次测算里设的阈值，默认 15%）。"
            + "两者在数值上本来是重叠的（亏 1% 的品毛利率也是负的，当然也低于阈值），"
            + "不互斥的话同一个 SKU 会被计两次，「问题品总数」就多算了 —— "
            + "而那个数正是拿去决定清库存范围的那个数。",
        "结果表支持**真筛选**（筛选与排序都在数据库层，不是把整表拉回来在内存里筛）："
            + "毛利率 &lt; X（毛利率低于 X%）、售价 &lt; Y、异常标记（亏损品 / 低毛利品）、"
            + "状态（已测算 / 跳过）、SKU 编码或名称关键字。"
            + "导出的就是**当前筛选命中的那些行** —— 导出与列表共用同一套筛选拼装。",
        "导出是 **CSV 原样透传**：后端生成字节流（RFC 4180 转义 + Excel 公式注入切断），"
            + "前端原样下载，不在浏览器里二次拼表格。"
            + "单次导出上限 **" + BatchCalculationRules.MAX_EXPORT_ROWS + " 行**，超了**明确报错**，"
            + "不静默截断（截断的 CSV 会让人以为数据完整）。",
        "「跳过」在本模块有**一种**：没有可用售价（价格观测台账里这个 SKU 在这个店铺没有观测）。"
            + "另有一种「测不准」：测出来了，但成本不完整（decisionReady=false）—— "
            + "那种行有售价、有成本，但**没有毛利率数字**，标记也是空。"
            + "两种都不是「亏损品」，页面上分开显示。",
        "汇总数字（亏损品数 / 低毛利品数 / 平均毛利率 / 售价合计）**读时聚合**，不落库："
            + "落库会与结果表漂移，而且落库的汇总做不到「随当前可见范围变化」。",
        "金额全程 BigDecimal：金额 4 位小数、费率与毛利率 6 位小数，统一 HALF_UP；"
            + "数量 6 位小数。**零浮点**。页面上写明。");
  }

  /** 目标店铺（站点）列表：来自 2.1.1.1 平台目录。 */
  public List<SiteOption> sites(String bearer) {
    var out = new ArrayList<SiteOption>();
    for (var site : platforms.sites(bearer)) {
      out.add(
          new SiteOption(
              site.platformCode(),
              site.platformName(),
              site.siteCode(),
              site.siteName(),
              site.countryCode(),
              site.defaultCurrency(),
              site.vatRequired()));
    }
    return out;
  }

  // ================================================================ 发起批量测算

  /**
   * 发起一次批量测算：解析范围 → 取价 → 逐 SKU 跑 5.3.1.1 的测算 → 落库。
   *
   * <p>整个动作在**一个事务**里（{@link AccessService#run} 绑定租户上下文与事务）：
   * 中途失败不留半个批次。
   */
  public BatchRunDetail createRun(
      AccessService.Access actor, BatchCalculationRequest request, String bearer) {
    var plan = normalize(request);

    // ---- 1) 解析 SKU 范围 ----
    var scope = resolveScope(plan);

    // ---- 2) 取价（价格观测台账）----
    var prices = resolvePrices(plan, scope);

    // ---- 3) 落批次头 ----
    UUID runId = UUID.randomUUID();
    OffsetDateTime createdAt = OffsetDateTime.now();
    sql.execute(
        "insert into batch_calculation_run(id,tenant_id,platform_code,site_code,currency,cost_currency,"
            + "scope_category,scope_brand,price_source,quantity,fx_rate,platform_fee_rate,"
            + "platform_fee_fixed,logistics_cost_per_unit,monthly_fixed_cost,cost_window_from,"
            + "cost_window_to,low_margin_threshold,note,created_by,created_at)"
            + " values(?::uuid,?::uuid,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::uuid,?::timestamptz)",
        runId,
        actor.tenantId(),
        plan.platformCode(),
        plan.siteCode(),
        plan.currency(),
        plan.costCurrency(),
        plan.scopeCategory(),
        plan.scopeBrand(),
        "LATEST_OBSERVATION",
        plan.quantity(),
        plan.fxRate(),
        plan.platformFeeRate(),
        plan.platformFeeFixed(),
        plan.logisticsCostPerUnit(),
        plan.monthlyFixedCost(),
        plan.costWindowFrom(),
        plan.costWindowTo(),
        plan.lowMarginThreshold(),
        plan.note(),
        actor.userId(),
        createdAt);

    // ---- 4) 逐 SKU 测算并落结果 ----
    for (var sku : scope) {
      var price = prices.get(sku.id());
      if (price == null) {
        insertSkipped(runId, actor, sku, "该 SKU 在该店铺没有价格观测（5.5.2.1 价格观测台账）—— 无售价无法测算。");
        continue;
      }
      if (!price.currency().equals(plan.currency())) {
        insertSkipped(
            runId,
            actor,
            sku,
            "价格观测的币种（"
                + price.currency()
                + "）与本次测算的币种（"
                + plan.currency()
                + "）不同 —— 绝不按 1:1 折算。");
        continue;
      }
      var request0 =
          new CalculateRequest(
              sku.id(),
              plan.siteCode(),
              plan.platformCode(),
              plan.currency(),
              plan.costCurrency(),
              CalcMode.PRICE,
              price.amount(),
              null,
              plan.quantity(),
              plan.fxRate(),
              plan.platformFeeRate(),
              plan.platformFeeFixed(),
              plan.logisticsCostPerUnit(),
              plan.monthlyFixedCost(),
              List.of(),
              plan.costWindowFrom(),
              plan.costWindowTo(),
              plan.note());
      var calculation = calculator.calculate(request0, bearer);
      var flags =
          BatchCalculationRules.flags(
              calculation.grossProfit(), calculation.grossMarginRate(), plan.lowMarginThreshold());
      var gapCodes =
          calculation.blockingGaps().stream()
              .filter(gap -> !gap.manualAssumptionAccepted())
              .map(gap -> gap.code())
              .toList();
      sql.execute(
          "insert into batch_calculation_result(id,tenant_id,run_id,sku_id,status,skip_reason,"
              + "unit_price,price_source,price_observed_at,currency,known_cost_total,"
              + "platform_fee_amount,logistics_amount,total_deductions,gross_profit,"
              + "gross_margin_rate,break_even_price,decision_basis,decision_ready,is_loss,"
              + "is_low_margin,blocking_gap_codes,created_at)"
              + " values(?::uuid,?::uuid,?::uuid,?::uuid,'CALCULATED','',?,?,?::timestamptz,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
          UUID.randomUUID(),
          actor.tenantId(),
          runId,
          sku.id(),
          price.amount(),
          price.source(),
          price.observedAt(),
          plan.currency(),
          calculation.knownDeductionTotal(),
          waterfallAmount(calculation, "PLATFORM_FEE"),
          waterfallAmount(calculation, "LOGISTICS"),
          calculation.knownDeductionTotal() == null
              ? null
              : calculation.knownDeductionTotal()
                  .add(zeroIfNull(waterfallAmount(calculation, "PLATFORM_FEE")))
                  .add(zeroIfNull(waterfallAmount(calculation, "LOGISTICS"))),
          calculation.grossProfit(),
          calculation.grossMarginRate(),
          calculation.breakEvenPrice(),
          calculation.decisionBasis().name(),
          calculation.decisionReady(),
          flags.loss(),
          flags.lowMargin(),
          String.join(",", gapCodes),
          createdAt);
    }
    return getRun(runId);
  }

  // ================================================================ 批次台账

  /** 批次列表（真分页 + 读时汇总）。 */
  public BatchRunPage listRuns(int page, int size) {
    int limited = Math.min(Math.max(size <= 0 ? 20 : size, 1), MAX_PAGE_SIZE);
    int offset = Math.max(page, 0) * limited;
    var rows =
        sql.fetch(
            "select r.id, r.platform_code, r.site_code, r.currency, r.cost_currency,"
                + " r.scope_category, r.scope_brand, r.price_source, r.quantity, r.fx_rate,"
                + " r.platform_fee_rate, r.platform_fee_fixed, r.logistics_cost_per_unit,"
                + " r.monthly_fixed_cost, r.low_margin_threshold, r.cost_window_from,"
                + " r.cost_window_to, r.note, r.created_by, r.created_at,"
                + " coalesce(a.total,0) as total, coalesce(a.calculated,0) as calculated,"
                + " coalesce(a.skipped_no_price,0) as skipped_no_price,"
                + " coalesce(a.not_ready,0) as not_ready, coalesce(a.loss,0) as loss,"
                + " coalesce(a.low_margin,0) as low_margin, a.avg_gross_margin_rate,"
                + " a.total_unit_price, a.total_gross_profit"
                + " from batch_calculation_run r"
                + " left join lateral ("
                + "   select count(*) as total,"
                + "     count(*) filter (where x.status='CALCULATED') as calculated,"
                + "     count(*) filter (where x.status='SKIPPED_NO_PRICE') as skipped_no_price,"
                + "     count(*) filter (where x.status='CALCULATED' and not x.decision_ready) as not_ready,"
                + "     count(*) filter (where x.is_loss) as loss,"
                + "     count(*) filter (where x.is_low_margin) as low_margin,"
                + "     avg(x.gross_margin_rate) as avg_gross_margin_rate,"
                + "     sum(x.unit_price) as total_unit_price,"
                + "     sum(x.gross_profit) as total_gross_profit"
                + "   from batch_calculation_result x"
                + "   where x.tenant_id=r.tenant_id and x.run_id=r.id"
                + " ) a on true"
                + " where r.tenant_id="
                + TENANT
                + " order by r.created_at desc, r.id"
                + " limit ? offset ?",
            limited,
            offset);
    long total =
        sql.fetchOne("select count(*) from batch_calculation_run where tenant_id=" + TENANT)
            .get(0, Long.class);
    var items = new ArrayList<BatchRunDetail>();
    for (var row : rows) items.add(runDetail(row));
    return new BatchRunPage(items, Math.max(page, 0), limited, total);
  }

  /** 批次详情 = 批次本身 + 读时汇总。 */
  public BatchRunDetail getRun(UUID id) {
    var row =
        sql.fetchOne(
            "select r.id, r.platform_code, r.site_code, r.currency, r.cost_currency,"
                + " r.scope_category, r.scope_brand, r.price_source, r.quantity, r.fx_rate,"
                + " r.platform_fee_rate, r.platform_fee_fixed, r.logistics_cost_per_unit,"
                + " r.monthly_fixed_cost, r.low_margin_threshold, r.cost_window_from,"
                + " r.cost_window_to, r.note, r.created_by, r.created_at,"
                + " coalesce(a.total,0) as total, coalesce(a.calculated,0) as calculated,"
                + " coalesce(a.skipped_no_price,0) as skipped_no_price,"
                + " coalesce(a.not_ready,0) as not_ready, coalesce(a.loss,0) as loss,"
                + " coalesce(a.low_margin,0) as low_margin, a.avg_gross_margin_rate,"
                + " a.total_unit_price, a.total_gross_profit"
                + " from batch_calculation_run r"
                + " left join lateral ("
                + "   select count(*) as total,"
                + "     count(*) filter (where x.status='CALCULATED') as calculated,"
                + "     count(*) filter (where x.status='SKIPPED_NO_PRICE') as skipped_no_price,"
                + "     count(*) filter (where x.status='CALCULATED' and not x.decision_ready) as not_ready,"
                + "     count(*) filter (where x.is_loss) as loss,"
                + "     count(*) filter (where x.is_low_margin) as low_margin,"
                + "     avg(x.gross_margin_rate) as avg_gross_margin_rate,"
                + "     sum(x.unit_price) as total_unit_price,"
                + "     sum(x.gross_profit) as total_gross_profit"
                + "   from batch_calculation_result x"
                + "   where x.tenant_id=r.tenant_id and x.run_id=r.id"
                + " ) a on true"
                + " where r.tenant_id="
                + TENANT
                + " and r.id=?",
            id);
    if (row == null) throw ProfitCalculatorService.notFound("批量测算批次不存在");
    return runDetail(row);
  }

  // ================================================================ 结果表

  /** 结果列表（真分页 + 真筛选 + 真排序）。 */
  public BatchResultPage listResults(UUID runId, ResultQuery query) {
    requireVisibleRun(runId);
    var filter = filterOf(runId, query);
    long total =
        sql.fetchOne("select count(*)" + filter.from() + filter.where(), filter.binds().toArray())
            .get(0, Long.class);
    int limited = Math.min(Math.max(query.size() <= 0 ? 20 : query.size(), 1), MAX_PAGE_SIZE);
    int offset = Math.max(query.page(), 0) * limited;
    var rows =
        sql.fetch(
            "select x.id, x.run_id, x.sku_id, k.code as sku_code, k.name as sku_name,"
                + " p.category, p.brand, x.status, x.skip_reason, x.unit_price, x.price_source,"
                + " x.price_observed_at, x.currency, x.known_cost_total, x.platform_fee_amount,"
                + " x.logistics_amount, x.total_deductions, x.gross_profit, x.gross_margin_rate,"
                + " x.break_even_price, x.decision_basis, x.decision_ready, x.is_loss,"
                + " x.is_low_margin, x.blocking_gap_codes"
                + filter.from()
                + filter.where()
                + filter.order()
                + " limit ? offset ?",
            concat(filter.binds(), List.of(limited, offset)).toArray());
    var items = new ArrayList<BatchResultRow>();
    for (var row : rows) items.add(resultRow(row));
    return new BatchResultPage(items, Math.max(query.page(), 0), limited, total);
  }

  /**
   * 导出 CSV：与列表**共用同一套筛选** —— 导出的就是查到的那些行。
   *
   * <p>🔴 超上限**明确报错**，不静默截断。
   */
  public byte[] exportCsv(UUID runId, ResultQuery query) {
    requireVisibleRun(runId);
    var filter = filterOf(runId, query);
    long total =
        sql.fetchOne("select count(*)" + filter.from() + filter.where(), filter.binds().toArray())
            .get(0, Long.class);
    if (total > BatchCalculationRules.MAX_EXPORT_ROWS) {
      throw ProfitCalculatorService.badRequest(
          "命中 "
              + total
              + " 行，超过单次导出上限 "
              + BatchCalculationRules.MAX_EXPORT_ROWS
              + " 行，请收窄筛选范围（毛利率 / 售价 / 异常标记）后重试");
    }
    var rows =
        sql.fetch(
            "select k.code as sku_code, k.name as sku_name, p.category, p.brand, x.status,"
                + " x.skip_reason, x.unit_price, x.price_source, x.price_observed_at, x.currency,"
                + " x.known_cost_total, x.platform_fee_amount, x.logistics_amount,"
                + " x.total_deductions, x.gross_profit, x.gross_margin_rate, x.break_even_price,"
                + " x.decision_basis, x.decision_ready, x.is_loss, x.is_low_margin,"
                + " x.blocking_gap_codes"
                + filter.from()
                + filter.where()
                + filter.order(),
            filter.binds().toArray());
    var out = new StringBuilder("﻿");
    out.append(
        BatchCalculationRules.csvRow(
            List.of(
                "SKU编码",
                "SKU名称",
                "分类",
                "品牌",
                "状态",
                "售价",
                "售价来源",
                "价格观测时间",
                "币种",
                "已知成本",
                "平台费用",
                "物流成本",
                "扣减合计",
                "毛利额",
                "毛利率",
                "保本价",
                "决策就绪度",
                "异常标记",
                "缺口项",
                "跳过原因")));
    out.append('\n');
    for (var row : rows) {
      var flags = new ArrayList<String>();
      if (row.get("is_loss", Boolean.class)) flags.add(Flag.LOSS.label());
      if (row.get("is_low_margin", Boolean.class)) flags.add(Flag.LOW_MARGIN.label());
      out.append(
          BatchCalculationRules.csvRow(
              List.of(
                  row.get("sku_code", String.class),
                  row.get("sku_name", String.class),
                  row.get("category", String.class),
                  row.get("brand", String.class),
                  ResultStatus.parse(row.get("status", String.class)).label(),
                  BatchCalculationRules.csvMoney(row.get("unit_price", BigDecimal.class)),
                  row.get("price_source", String.class),
                  row.get("price_observed_at", OffsetDateTime.class) == null
                      ? ""
                      : row.get("price_observed_at", OffsetDateTime.class).toString(),
                  row.get("currency", String.class),
                  BatchCalculationRules.csvMoney(row.get("known_cost_total", BigDecimal.class)),
                  BatchCalculationRules.csvMoney(row.get("platform_fee_amount", BigDecimal.class)),
                  BatchCalculationRules.csvMoney(row.get("logistics_amount", BigDecimal.class)),
                  BatchCalculationRules.csvMoney(row.get("total_deductions", BigDecimal.class)),
                  BatchCalculationRules.csvMoney(row.get("gross_profit", BigDecimal.class)),
                  BatchCalculationRules.csvRate(row.get("gross_margin_rate", BigDecimal.class)),
                  BatchCalculationRules.csvMoney(row.get("break_even_price", BigDecimal.class)),
                  row.get("decision_ready", Boolean.class) ? "已就绪" : "未就绪",
                  String.join(" / ", flags),
                  row.get("blocking_gap_codes", String.class),
                  row.get("skip_reason", String.class))));
      out.append('\n');
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * 批次必须存在且属于当前租户，否则 404。
   *
   * <p>🔴 不校验就会把「批次不存在」与「批次存在但一条结果都没有」都返回成**空列表** ——
   * 前端于是显示「这个批次没有亏损品」，而真相是「这个批次根本不是你的」。
   * 对越权访问返回 404 而不是 403，也是不泄露「存在」这个事实。
   */
  private void requireVisibleRun(UUID runId) {
    var row =
        sql.fetchOne(
            "select 1 from batch_calculation_run where tenant_id=" + TENANT + " and id=?", runId);
    if (row == null) throw ProfitCalculatorService.notFound("批量测算批次不存在");
  }

  // ================================================================ 内部：范围与取价

  /** 范围里的一个 SKU。 */
  private record ScopeSku(UUID id, String code, String name, String category, String brand) {}

  /** 取到的价。 */
  private record Price(BigDecimal amount, String source, String currency, OffsetDateTime observedAt) {}

  /**
   * 解析 SKU 范围：按分类 / 品牌精确匹配（店铺在取价时生效）。
   *
   * <p>🔴 超过 {@link BatchCalculationRules#MAX_SCOPE_SIZE} **明确报错**，不静默截断。
   */
  private List<ScopeSku> resolveScope(Plan plan) {
    var where = new StringBuilder(" where k.tenant_id=" + TENANT);
    var binds = new ArrayList<Object>();
    if (!plan.scopeCategory().isEmpty()) {
      where.append(" and p.category = ?");
      binds.add(plan.scopeCategory());
    }
    if (!plan.scopeBrand().isEmpty()) {
      where.append(" and p.brand = ?");
      binds.add(plan.scopeBrand());
    }
    var rows =
        sql.fetch(
            "select k.id, k.code, k.name, p.category, p.brand"
                + " from sku k join spu p on p.tenant_id=k.tenant_id and p.id=k.spu_id"
                + where
                + " order by k.code"
                + " limit ?",
            concat(binds, List.of(BatchCalculationRules.MAX_SCOPE_SIZE + 1)).toArray());
    if (rows.size() > BatchCalculationRules.MAX_SCOPE_SIZE) {
      throw ProfitCalculatorService.badRequest(
          "范围内的 SKU 超过 "
              + BatchCalculationRules.MAX_SCOPE_SIZE
              + " 个（每个 SKU 的成本构成要跨服务读 warehouse，一次批量测算的上限是"
              + BatchCalculationRules.MAX_SCOPE_SIZE
              + " 个）。请收窄分类或品牌范围后重试 —— 本模块不会静默截断，"
              + "截断了你会以为全测过了。");
    }
    var out = new ArrayList<ScopeSku>();
    for (var row : rows) {
      out.add(
          new ScopeSku(
              row.get("id", UUID.class),
              row.get("code", String.class),
              row.get("name", String.class),
              row.get("category", String.class),
              row.get("brand", String.class)));
    }
    return out;
  }

  /**
   * 取价：价格观测台账里该店铺下每个 SKU 的**最新一条**观测。
   *
   * <p>🔴 台账是**只追加**的，「最新一条」是一个时间点而不是「当前价」——
   * 观测时刻会写进结果行，导出时也带出去。
   */
  private Map<UUID, Price> resolvePrices(Plan plan, List<ScopeSku> scope) {
    var out = new LinkedHashMap<UUID, Price>();
    if (scope.isEmpty()) return out;
    var placeholders = new StringBuilder();
    var binds = new ArrayList<Object>();
    binds.add(plan.platformCode());
    binds.add(plan.siteCode());
    for (int i = 0; i < scope.size(); i++) {
      placeholders.append(i == 0 ? "?" : ",?");
      binds.add(scope.get(i).id());
    }
    var rows =
        sql.fetch(
            "select distinct on (o.sku_id) o.sku_id, o.internal_price, o.platform_price,"
                + " o.currency, o.observed_at"
                + " from price_anomaly_observation o"
                + " where o.tenant_id="
                + TENANT
                + " and o.platform_code = ? and o.site_code = ?"
                + " and o.sku_id in ("
                + placeholders
                + ")"
                + " order by o.sku_id, o.observed_at desc, o.id desc",
            binds.toArray());
    for (var row : rows) {
      BigDecimal amount = row.get("internal_price", BigDecimal.class);
      String source = "INTERNAL_PRICE";
      if (amount == null) {
        amount = row.get("platform_price", BigDecimal.class);
        source = "PLATFORM_PRICE";
      }
      if (amount == null) continue;
      out.put(
          row.get("sku_id", UUID.class),
          new Price(
              amount,
              source,
              row.get("currency", String.class),
              row.get("observed_at", OffsetDateTime.class)));
    }
    return out;
  }

  /** 落一行「跳过」。 */
  private void insertSkipped(
      UUID runId, AccessService.Access actor, ScopeSku sku, String reason) {
    sql.execute(
        "insert into batch_calculation_result(id,tenant_id,run_id,sku_id,status,skip_reason,"
            + "decision_basis,decision_ready,is_loss,is_low_margin,blocking_gap_codes,created_at)"
            + " values(?::uuid,?::uuid,?::uuid,?::uuid,'SKIPPED_NO_PRICE',?,'INCOMPLETE',false,"
            + "false,false,'',?::timestamptz)",
        UUID.randomUUID(),
        actor.tenantId(),
        runId,
        sku.id(),
        reason,
        OffsetDateTime.now());
  }

  // ================================================================ 内部：归一化与筛选

  /** 归一化后的请求。 */
  private record Plan(
      String platformCode,
      String siteCode,
      String currency,
      String costCurrency,
      String scopeCategory,
      String scopeBrand,
      BigDecimal quantity,
      BigDecimal fxRate,
      BigDecimal platformFeeRate,
      BigDecimal platformFeeFixed,
      BigDecimal logisticsCostPerUnit,
      BigDecimal monthlyFixedCost,
      BigDecimal lowMarginThreshold,
      LocalDate costWindowFrom,
      LocalDate costWindowTo,
      String note) {}

  /** 归一化 + 校验：不合法就 400。 */
  private Plan normalize(BatchCalculationRequest request) {
    if (request == null) throw ProfitCalculatorService.badRequest("批量测算请求不能为空");
    String platformCode = BatchCalculationRules.code(request.platformCode());
    if (platformCode == null) throw ProfitCalculatorService.badRequest("必须指定平台");
    String siteCode = BatchCalculationRules.code(request.siteCode());
    if (siteCode == null) throw ProfitCalculatorService.badRequest("必须指定店铺（站点）");
    String currency = BatchCalculationRules.code(request.currency());
    if (currency == null || !currency.matches("^[A-Z]{3}$")) {
      throw ProfitCalculatorService.badRequest("必须指定售价币种（三位大写字母，ISO 4217）");
    }
    String costCurrency = BatchCalculationRules.code(request.costCurrency());
    if (costCurrency == null) costCurrency = currency;
    if (!costCurrency.matches("^[A-Z]{3}$")) {
      throw ProfitCalculatorService.badRequest("成本币种必须是三位大写字母");
    }
    BigDecimal quantity =
        request.quantity() == null ? BigDecimal.ONE : request.quantity();
    if (quantity.signum() <= 0) throw ProfitCalculatorService.badRequest("数量必须大于 0");
    if (request.platformFeeRate() != null
        && (request.platformFeeRate().signum() < 0
            || request.platformFeeRate().compareTo(BigDecimal.ONE) >= 0)) {
      throw ProfitCalculatorService.badRequest("平台费率是小数口径（0.15 表示 15%），必须在 0 与 1 之间");
    }
    BigDecimal lowMargin =
        request.lowMarginThreshold() == null
            ? BatchCalculationRules.DEFAULT_LOW_MARGIN_THRESHOLD
            : request.lowMarginThreshold();
    if (lowMargin.signum() < 0 || lowMargin.compareTo(BigDecimal.ONE) >= 0) {
      throw ProfitCalculatorService.badRequest("低毛利阈值是小数口径（0.15 表示 15%），必须在 0 与 1 之间");
    }
    // 🔴 跨币种必须要汇率：没有就拒绝，**绝不按 1:1 折算**。
    if (!currency.equals(costCurrency) && (request.fxRate() == null || request.fxRate().signum() <= 0)) {
      throw ProfitCalculatorService.badRequest(
          "售价币种（"
              + currency
              + "）与成本币种（"
              + costCurrency
              + "）不同，必须给汇率：1 "
              + costCurrency
              + " = X "
              + currency
              + "。汇率规则（5.2.2.1 汇率查询 / 5.2.2.2 汇率快照）未交付，"
              + "所以这里只能由人作为手工假设填入 —— 绝不按 1:1 折算，"
              + "那会把两个币种的金额当成同一个币种加在一起");
    }
    LocalDate from =
        request.costWindowFrom() == null
            ? LocalDate.now().minusDays(DEFAULT_WINDOW_DAYS)
            : request.costWindowFrom();
    LocalDate to = request.costWindowTo() == null ? LocalDate.now() : request.costWindowTo();
    if (to.isBefore(from)) throw ProfitCalculatorService.badRequest("成本口径窗口的终点不能早于起点");
    return new Plan(
        platformCode,
        siteCode,
        currency,
        costCurrency,
        BatchCalculationRules.scope(request.category()),
        BatchCalculationRules.scope(request.brand()),
        quantity,
        request.fxRate(),
        request.platformFeeRate(),
        request.platformFeeFixed(),
        request.logisticsCostPerUnit(),
        request.monthlyFixedCost(),
        lowMargin,
        from,
        to,
        request.note() == null ? "" : request.note());
  }

  /** 一段拼好的筛选（列表与导出共用）。 */
  private record Filter(String from, String where, List<Object> binds, String order) {}

  /** 拼筛选：全部在数据库层，不是把整表拉回来在内存里筛。 */
  private Filter filterOf(UUID runId, ResultQuery query) {
    var where =
        new StringBuilder(
            " where x.tenant_id="
                + TENANT
                + " and x.run_id=?"
                + " and k.tenant_id="
                + TENANT
                + " and k.id=x.sku_id");
    var binds = new ArrayList<Object>();
    binds.add(runId);
    if (query.marginBelow() != null) {
      where.append(" and x.gross_margin_rate < ?");
      binds.add(query.marginBelow());
    }
    if (query.priceBelow() != null) {
      where.append(" and x.unit_price < ?");
      binds.add(query.priceBelow());
    }
    var flag = BatchCalculationRules.parseFlag(query.flag());
    if (query.flag() != null && !query.flag().isBlank() && flag == null) {
      throw ProfitCalculatorService.badRequest("未知的异常标记：" + query.flag());
    }
    if (flag == Flag.LOSS) where.append(" and x.is_loss");
    if (flag == Flag.LOW_MARGIN) where.append(" and x.is_low_margin");
    var status = BatchCalculationRules.parseStatus(query.status());
    if (query.status() != null && !query.status().isBlank() && status == null) {
      throw ProfitCalculatorService.badRequest("未知的结果状态：" + query.status());
    }
    if (status != null) {
      where.append(" and x.status = ?");
      binds.add(status.name());
    }
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.append(" and (k.code ilike ? or k.name ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
    }
    var sort = BatchCalculationRules.parseSort(query.sort());
    if (query.sort() != null && !query.sort().isBlank() && sort == null) {
      throw ProfitCalculatorService.badRequest("未知的排序方式：" + query.sort());
    }
    String order =
        switch (sort == null ? Sort.MARGIN_ASC : sort) {
          case MARGIN_DESC -> " order by x.gross_margin_rate desc nulls last, x.sku_id";
          case PRICE_ASC -> " order by x.unit_price asc nulls last, x.sku_id";
          case PRICE_DESC -> " order by x.unit_price desc nulls last, x.sku_id";
          default -> " order by x.gross_margin_rate asc nulls last, x.sku_id";
        };
    return new Filter(
        " from batch_calculation_result x join sku k on k.tenant_id=x.tenant_id and k.id=x.sku_id"
            + " left join spu p on p.tenant_id=k.tenant_id and p.id=k.spu_id",
        where.toString(),
        binds,
        order);
  }

  // ================================================================ 内部：行映射

  private BatchRunDetail runDetail(org.jooq.Record row) {
    var summary =
        new BatchSummary(
            row.get("total", Long.class),
            row.get("calculated", Long.class),
            row.get("skipped_no_price", Long.class),
            row.get("not_ready", Long.class),
            row.get("loss", Long.class),
            row.get("low_margin", Long.class),
            row.get("loss", Long.class) + row.get("low_margin", Long.class),
            row.get("avg_gross_margin_rate", BigDecimal.class) == null
                ? null
                : BatchCalculationRules.rate(row.get("avg_gross_margin_rate", BigDecimal.class)),
            row.get("total_unit_price", BigDecimal.class) == null
                ? null
                : BatchCalculationRules.money(row.get("total_unit_price", BigDecimal.class)),
            row.get("total_gross_profit", BigDecimal.class) == null
                ? null
                : BatchCalculationRules.money(row.get("total_gross_profit", BigDecimal.class)));
    return new BatchRunDetail(
        row.get("id", UUID.class),
        row.get("platform_code", String.class),
        row.get("site_code", String.class),
        row.get("currency", String.class),
        row.get("cost_currency", String.class),
        row.get("scope_category", String.class),
        row.get("scope_brand", String.class),
        row.get("price_source", String.class),
        row.get("quantity", BigDecimal.class),
        row.get("fx_rate", BigDecimal.class),
        row.get("platform_fee_rate", BigDecimal.class),
        row.get("platform_fee_fixed", BigDecimal.class),
        row.get("logistics_cost_per_unit", BigDecimal.class),
        row.get("monthly_fixed_cost", BigDecimal.class),
        row.get("low_margin_threshold", BigDecimal.class),
        row.get("cost_window_from", LocalDate.class),
        row.get("cost_window_to", LocalDate.class),
        row.get("note", String.class),
        row.get("created_by", UUID.class) == null ? null : row.get("created_by", UUID.class).toString(),
        row.get("created_at", OffsetDateTime.class),
        summary);
  }

  private BatchResultRow resultRow(org.jooq.Record row) {
    var gaps = new ArrayList<String>();
    var raw = row.get("blocking_gap_codes", String.class);
    if (raw != null && !raw.isBlank()) {
      for (var code : raw.split(",")) {
        if (!code.isBlank()) gaps.add(code.trim());
      }
    }
    return new BatchResultRow(
        row.get("id", UUID.class),
        row.get("run_id", UUID.class),
        row.get("sku_id", UUID.class),
        row.get("sku_code", String.class),
        row.get("sku_name", String.class),
        row.get("category", String.class),
        row.get("brand", String.class),
        ResultStatus.parse(row.get("status", String.class)),
        row.get("skip_reason", String.class),
        row.get("unit_price", BigDecimal.class),
        row.get("price_source", String.class),
        row.get("price_observed_at", OffsetDateTime.class),
        row.get("currency", String.class),
        row.get("known_cost_total", BigDecimal.class),
        row.get("platform_fee_amount", BigDecimal.class),
        row.get("logistics_amount", BigDecimal.class),
        row.get("total_deductions", BigDecimal.class),
        row.get("gross_profit", BigDecimal.class),
        row.get("gross_margin_rate", BigDecimal.class),
        row.get("break_even_price", BigDecimal.class),
        row.get("decision_basis", String.class),
        row.get("decision_ready", Boolean.class),
        row.get("is_loss", Boolean.class),
        row.get("is_low_margin", Boolean.class),
        gaps);
  }

  /** 从瀑布图里取一项扣减的金额（每单位口径）。 */
  private static BigDecimal waterfallAmount(ProfitCalculation calculation, String code) {
    for (var line : calculation.waterfall()) {
      if (line.code().equals(code)) return line.amount();
    }
    return null;
  }

  private static BigDecimal zeroIfNull(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static <T> List<T> concat(List<? extends T> first, List<? extends T> second) {
    var out = new ArrayList<T>(first.size() + second.size());
    out.addAll(first);
    out.addAll(second);
    return out;
  }
}
