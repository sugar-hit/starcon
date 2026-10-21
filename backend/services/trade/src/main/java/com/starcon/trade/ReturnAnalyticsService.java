package com.starcon.trade;

import static com.starcon.trade.ReturnAnalyticsModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * 11.5.1.1 退货分析。
 *
 * <p><b>本类是 V271 三张新表的写入所有者</b>（return_analysis_basis /
 * return_analysis_snapshot / return_analysis_event），
 * 对 V156/V157 的 aftersales_case 簇、V99 的 sales_order 簇、V207 的 platform_order、
 * V161 的 rma_authorization 簇、V172 的 return_receipt 簇、V166 的 refund_application、
 * V179 的 return_quality_category 簇、V158 的 aftersales_reason_* 簇<b>只读</b>。
 * 刻意不加外键、不 ALTER 既有表 ⇒ <b>跨车道 DDL 回归面 = 0</b>（同批另有五条车道也在往 trade 落迁移）。
 *
 * <p><b>输入句的两项上游（售后与退货）都在本服务</b>，销售数据（V99）也在本服务，
 * 所以本模块是<b>同库只读聚合</b>，不碰任何跨服务路径 ——
 * 这也正是它能落在 trade 而不是分析服务的原因。
 *
 * <p><b>七条硬纪律</b>：
 *
 * <ol>
 *   <li><b>每一条查询都显式带 {@code tenant_id} 谓词</b>，不依赖 RLS 当唯一防线。
 *       退货率与拒退率是要拿去找平台与供应商谈判的底牌，
 *       漏掉租户谓词就是跨租户泄露对手的退货画像。</li>
 *   <li><b>分子分母必须同币种</b>：报表按币种分组，跨币种<b>既不相加也不相除</b>。
 *       「100 元退货 ÷ 1000 日元销售额 = 10%」是一个没有意义的数。</li>
 *   <li><b>比值是显式算出来的 BigDecimal</b>（标度 4），不让数据库返回 float8，
 *       也不用 double/float。分母为 0 时返回 {@code null} 而不是 0 ——
 *       没有销售额就没有退货率，报 0 会被读成「这个维度退货率 0%，很健康」。</li>
 *   <li><b>整页共用一个参考时刻</b>：{@code at} 在每个报表方法开头取一次，
 *       窗口、行、汇总三处复用同一个值。否则会「筛了本季度、第一行却是去年的」——
 *       而这种错位在报表里<b>不报错</b>，只会让人对不上账。</li>
 *   <li><b>每个比值都带口径标记</b>（单头 / 行级 / 件数）：三者的分子分母不是同一个东西，
 *       混在一张表里不标出来，读的人会把 3% 和 30% 直接比大小。</li>
 *   <li><b>异常阈值逐条报出来</b>：一张说不清判据的异常清单，没人会当真去处理。</li>
 *   <li><b>时间绑定</b>：时间一律 {@code ?::timestamptz} + {@link OffsetDateTime}。
 *       {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s，
 *       而「受理时刻偏 8 小时」会让整条趋势错位一格。</li>
 * </ol>
 */
@Service
public class ReturnAnalyticsService {

  /** 报表行数上限（五个维度共用）。 */
  private static final int MAX_REPORT_ROWS = 200;

  /** 列表单页上限。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 退货率的标度：4 位小数的**比值**（0.0500 = 5%）。 */
  private static final int RATE_SCALE = 4;

  /** 占比的标度：两位小数的百分比。 */
  private static final int SHARE_SCALE = 2;

  private static final String BASIS_PREFIX = "RAB";
  private static final String SNAPSHOT_PREFIX = "RAS";

  /**
   * 复合键的段分隔符。
   *
   * <p>⚠️ **刻意不用 {@code "|"}**：店铺编码 / SKU 编码是平台给的字符串，
   * 一旦里面带了 {@code |}，{@code split} 就会把币种段读错位置 ——
   * 而「币种读错」的后果是把两种币种的金额混进同一行。
   */
  private static final String SEP = "\u0001";

  // ---- 文档化的默认阈值（租户没登记口径时用这一套，并在响应里标 caliberSource=DEFAULT）----
  private static final BigDecimal DEFAULT_RATE_MULTIPLIER = new BigDecimal("2.0000");
  private static final BigDecimal DEFAULT_RATE_MIN_SALES_SHARE = new BigDecimal("0.010000");
  private static final BigDecimal DEFAULT_REJECTION_GAP_PP = new BigDecimal("20.0000");
  private static final long DEFAULT_REJECTION_MIN_CASES = 5L;
  private static final BigDecimal DEFAULT_BATCH_MULTIPLIER = new BigDecimal("3.0000");
  private static final BigDecimal DEFAULT_BATCH_MIN_QTY = new BigDecimal("10.000000");
  private static final BigDecimal DEFAULT_REASON_SHARE = new BigDecimal("0.600000");
  private static final long DEFAULT_REASON_MIN_CASES = 10L;
  private static final BigDecimal DEFAULT_TREND_GAP_PP = new BigDecimal("5.0000");

  /** 售后单的七类原因内联词表（逐字对齐 V156 aftersales_case.reason_code）。 */
  private static final Map<String, String> REASON_LABELS =
      Map.of(
          "QUALITY_DEFECT", "质量问题",
          "DESCRIPTION_MISMATCH", "描述不符",
          "SIZE_MISMATCH", "尺寸不符",
          "LOGISTICS_DAMAGE", "物流破损",
          "BUYER_REASON", "买家原因",
          "MISTAKE_BUY", "买错",
          "FRAUD_RETURN", "欺诈退货");

  private static final String CALIBER_NOTE =
      "**退货率 = 退货金额 ÷ 销售额**，两个数必须**同币种、同窗口**才算得出来（蓝图加粗句）。"
          + "分子取 **aftersales_case.requested_amount**（买家申请退的金额，单头口径），"
          + "分母取 **sales_order.grand_total_amount**（已提交订单的总额）。"
          + "两者<b>不是同一批钱</b>：分子是「要退多少」，分母是「卖了多少」—— "
          + "只看绝对数会误判（退货率从 3% 升到 5% 严重，但绝对数可能没变）。"
          + "本模块刻意<b>不</b>把退货金额与销售额混成一个「净销售额」："
          + "净销售额会把退货率的变化藏起来，正是那条加粗句要防的事。";

  private static final String CURRENCY_NOTE =
      "**按币种分组，一币种一套数字。**跨币种既不相加也不相除："
          + "「100 元退货 ÷ 1000 日元销售额 = 10%」没有意义。"
          + "所以本响应里<b>没有</b>跨币种的合计金额与合计退货率，"
          + "只有与币种无关的<b>计数</b>合计（单数、件数、订单数）。"
          + "租户自己登记的口径（return_analysis_basis）也是<b>每个币种一份</b>："
          + "两个币种两套口径时，「按哪一份算」不能有两个答案。";

  private static final String REJECTION_NOTE =
      "**拒退率 = 拒绝的退货授权单数 ÷ 已做出决定的退货授权单数。**"
          + "分母只含 AUTHORIZED / PARTIALLY_AUTHORIZED / REJECTED 三种已决定的单，"
          + "草稿与超时关闭（EXPIRED）<b>不进分母</b> —— 它们还没有结论，"
          + "把它们算进分母等于把「还没批」当成「批了不批」。"
          + "**部分授权（PARTIALLY_AUTHORIZED）单列**：它是「退了一部分」，"
          + "既不是全退也不是全拒，混进任何一侧都会让拒退率失真。"
          + "另报<b>件数口径的未授权占比</b> =（申请件数 − 授权件数）÷ 申请件数，"
          + "它把「部分没批」也算了进去 —— 两个口径一起看才说得清「到底批了多少」。";

  private static final String PROCESSING_NOTE =
      "**四个时长来自四份不同的单据，互不混算：**"
          + "① 售后单结案时长 = resolved_at − received_at；"
          + "② 退货授权审批时长 = decided_at − created_at；"
          + "③ 买家回寄时长 = 实收时刻 − 登记回寄时刻（只算登记了回寄的单）；"
          + "④ 退款出结果时长 = outcome_recorded_at − submitted_at。"
          + "每个时长都带<b>样本数</b>：没有样本时平均值是 null 而不是 0 —— "
          + "「一件都没测过」和「平均 0 小时」在报表里长得一模一样，而前者更危险。";

  private static final String BATCH_NOTE =
      "**批次维度用件数口径，不是金额口径。**"
          + "分子 = **return_receipt_line.received_qty**（该批次实收件数）；"
          + "分母 = **该批次关联 SKU 在同期卖出的件数**。"
          + "⚠️ 库里**没有「这个批次卖了多少」**（批次是入库批次，不是销售切片），"
          + "所以分母只能取到 SKU 粒度 —— 这是个**近似**，不是精确的批次退货率。"
          + "批次维度<b>不报金额</b>：一笔售后行分批到货时，它的申请金额该算进哪个批次"
          + "没有唯一答案，摊到每个批次都是错的。";

  private static final String CROSS_SERVICE_NOTE =
      "输入句的两项上游（11.1 售后与退货、11.3 退款）**都在 trade 服务**，"
          + "销售数据（V99 sales_order 簇）也在本服务 ⇒ 本模块是同库只读聚合，不经任何跨服务调用。"
          + "⚠️ **店铺维度**来自 V207 的 platform_order（platform_code / shop_code）——"
          + "这是 trade 库里的平台单投影；卷 2 的店铺主数据在 channel 独立库，"
          + "按 AGENTS.md 模块边界纪律**不 join、不建视图、不建跨库 FK**。"
          + "⚠️ **类目维度**来自 V179 的 return_quality_category_sku（trade 侧的 SKU→类目映射）——"
          + "catalog 的正式商品类目在独立库，同样禁止跨库 join；"
          + "没归类的 SKU 落在「未归类」桶里，**不静默消失**。";

  private final DSLContext sql;

  public ReturnAnalyticsService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：职责句点名的每一项分析逐块报可用性、事实来源与口径，
   * 上下游逐个报 delivered 与归属模块，本模块刻意不做的事逐条列出。
   */
  public ReturnAnalysisCapabilities capabilities() {
    return new ReturnAnalysisCapabilities(
        List.of(
            "**11.1.1.x 售后单**（V156/V157，trade 同库，只读）：requested_amount / requested_quantity / "
                + "reason_code / received_at / resolved_at —— 退货金额、件数、原因、结案时长的来源",
            "**11.1.1.x 售后商品行**（V157，只读）：requested_amount / quantity —— "
                + "SKU 与类目下钻的**行级分子**",
            "**9.x 销售订单**（V99，trade 同库，只读）：grand_total_amount / currency / status / "
                + "submitted_at —— **销售额分母的唯一来源**（不自拼分母）",
            "**9.x 销售订单行**（V99，只读）：line_amount / quantity / sku_id / sku_code —— "
                + "SKU 与类目下钻的**行级分母**",
            "**9.1.2.1 platform_order**（V207，只读）：platform_code / shop_code —— 店铺维度",
            "**11.2.1.1 退货授权单**（V161，只读）：rma_status / decided_at / created_at —— "
                + "**拒退率与授权审批时效的唯一来源**",
            "**11.2.2.1 退货实收**（V172，只读）：return_receipt_line.batch_no / received_qty —— "
                + "**批次维度与买家回寄时效的来源**",
            "**11.3.1.1 退款申请**（V166，只读）：refund_amount / currency / outcome_recorded_at —— "
                + "**平均退款金额与退款时效的来源**",
            "**11.2.3.1 质检类目映射**（V179，只读）：return_quality_category_sku —— 类目维度",
            "**11.1.2.1 原因归因**（V158，只读）：aftersales_reason_attribution_set / _item —— "
                + "归因口径的原因分布（与七类内联词表是**两个不同的轴**）",
            "**11.5.1.1 return_analysis_basis**（V271，本模块写入）：口径与异常阈值",
            "**11.5.1.1 return_analysis_snapshot**（V271，本模块写入）：报表快照（追加式）"),
        List.of(
            section(
                "return-rate",
                "退货率",
                "分子 = 售后单申请退货金额，分母 = 已提交订单总额，**同币种同窗口**。"
                    + "支持按店铺/SKU/类目/原因/批次五个维度下钻，"
                    + "**每个维度的分母都是该维度自己的同期销售额** —— "
                    + "拿全店销售额当每个 SKU 的分母，报出来的是「占比」不是「退货率」。"),
            section(
                "amount-trend",
                "退货金额趋势",
                "按 UTC 日/周/月分桶，**每个桶自带同窗口的销售额与退货率**，"
                    + "所以趋势线上能直接看出「率在涨」还是「只是单量在涨」。"),
            section(
                "rejection-rate",
                "拒退率",
                "拒绝的退货授权单数 ÷ 已决定的退货授权单数；部分授权单列，"
                    + "另报件数口径的未授权占比。"),
            section(
                "processing-time",
                "处理时效",
                "四个时长分别来自售后单、退货授权单、实收单、退款单，互不混算，各自带样本数。"),
            section(
                "avg-refund",
                "平均退款金额",
                "已成功退款的平均金额（按币种），同时报出笔数与总额；"
                    + "**只算 SUCCEEDED**：还在审批或执行失败的退款不是「平均退款金额」。"),
            section(
                "reason-distribution",
                "原因分布",
                "七类内联词表（每单必填，口径完整）+ 归因分类树（可能没归因，**必须和覆盖率一起读**）"
                    + "两个不同的轴。"),
            section(
                "anomaly",
                "异常识别",
                "五类规则：退货率倍数异常、拒退率异常、批次件数异常、原因集中、趋势恶化。"
                    + "**阈值逐条报出来**，并支持租户自己登记口径来调阈值。")),
        List.of(
            related(
                "11.1.1.1",
                "售后单列表",
                true,
                null,
                "输入句「售后数据」的来源（V156，trade 同库只读）",
                "/aftersales/cases"),
            related(
                "11.1.2.1",
                "原因分类",
                true,
                null,
                "输入句「售后与退货数据」里的**原因口径**：七类根口径与归因分类树",
                "/aftersales/reasons"),
            related(
                "11.2.1.1",
                "退货授权单",
                true,
                null,
                "输入句「退货数据」的来源之一；**拒退率的唯一来源**（V161，trade 同库只读）",
                "/aftersales/returns"),
            related(
                "11.2.2.1",
                "退货实收",
                true,
                null,
                "输入句「退货数据」的来源之二；**批次维度的唯一来源**（V172，trade 同库只读）",
                "/aftersales/return-receipts"),
            related(
                "11.3.1.1",
                "退款申请",
                true,
                null,
                "输入句「售后与退货数据」里的退款侧；**平均退款金额与退款时效的来源**（V166）",
                "/aftersales/refund-applications"),
            related(
                "9.1.1",
                "销售订单",
                true,
                null,
                "输入句「销售数据」：**销售额分母的唯一来源**（V99，trade 同库只读）",
                null),
            related(
                "13",
                "跨系统经营分析",
                false,
                "reporting",
                "下游之一（蓝图的「下游 = 13 分析」）—— **未交付**，本模块只把口径定义清楚",
                null),
            related(
                "6.1.1.3",
                "供应商评级",
                false,
                "采购",
                "下游之一（蓝图的「下游 = 6 采购（质量改进）」）—— **未交付**，"
                    + "本模块的批次与原因分布是它将来的输入，本模块**不写**它的评级",
                null)),
        List.of(
            action(
                "overview",
                "退货分析总览",
                true,
                "GET",
                "/trade/return-analysis/overview",
                true,
                "按币种分组：退货率、拒退率、时效、平均退款金额、原因分布",
                "trade.aftersales.read"),
            action(
                "breakdown",
                "维度下钻",
                true,
                "GET",
                "/trade/return-analysis/breakdown",
                true,
                "dimension=SHOP|SKU|CATEGORY|REASON|BATCH",
                "trade.aftersales.read"),
            action(
                "trend",
                "退货金额趋势",
                true,
                "GET",
                "/trade/return-analysis/trend",
                true,
                "granularity=DAY|WEEK|MONTH",
                "trade.aftersales.read"),
            action(
                "anomalies",
                "异常识别",
                true,
                "GET",
                "/trade/return-analysis/anomalies",
                true,
                "阈值取自该币种的口径；未登记时用文档化的默认口径",
                "trade.aftersales.read"),
            action(
                "snapshots",
                "报表快照",
                true,
                "GET/POST",
                "/trade/return-analysis/snapshots",
                false,
                "保存的是「当时算出来的数」，追加式不可改写",
                "trade.aftersales.write"),
            action(
                "basis",
                "口径与阈值",
                true,
                "GET/POST",
                "/trade/return-analysis/basis",
                false,
                "每个币种一份生效口径；换版必须留痕",
                "trade.aftersales.write")),
        List.of(
            "**不提供跨系统经营分析**（蓝图的「下游 = 13 分析」未交付 —— reporting 服务不在集成头）。",
            "**不写供应商评级、不发起供应商索赔**（6.1.1.3 / 6.4.2.2 未交付）。",
            "**不 join catalog 的商品类目与店铺主数据**（跨服务独立库，AGENTS.md 禁止跨库 SQL / FK / 视图）。",
            "**不提供「退货率预测」或「最优解」**：本仓没有历史建模与预测设施，"
                + "编一条预测曲线就是在冒充模型能力。"),
        CALIBER_NOTE,
        CURRENCY_NOTE,
        REJECTION_NOTE,
        PROCESSING_NOTE,
        BATCH_NOTE);
  }

  private ReturnAnalysisSection section(String key, String title, String basis) {
    return new ReturnAnalysisSection(key, title, true, "11.5.1.1", basis);
  }

  private ReturnAnalysisRelatedModule related(
      String moduleId, String title, boolean delivered, String ownerSystem, String relation, String path) {
    return new ReturnAnalysisRelatedModule(moduleId, title, delivered, ownerSystem, relation, path);
  }

  private ReturnAnalysisAction action(
      String key, String title, boolean available, String method, String path, boolean readOnly,
      String precondition, String permission) {
    return new ReturnAnalysisAction(
        key, title, available, method, path, readOnly, precondition,
        available ? permission : null);
  }

  // ================================================================ 总览

  /**
   * 退货分析总览：一屏看完「退了多少钱、退货率多少、拒了多少、退得多快」。
   *
   * <p>⚠️ <b>整页共用一个参考时刻</b>（{@code at}）：退货率、拒退率、时效三块
   * 若各取各的 {@code now()}，一次刷新里可能出现「退货单数比拒退率的分母多 1 张」
   * 这种按定义不可能的不自洽 —— 而报表不会报错，只会让读的人怀疑数据。
   */
  public ReturnAnalysisOverview overview(ReturnAnalysisWindow window, String currencyFilter) {
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.ofHours(8));
    UUID tenant = currentTenant();
    Map<String, BasisRow> bases = activeBases(tenant);
    Map<String, Thresholds> thresholds = thresholdsByCurrency(bases);

    Map<String, CaseTypeAgg> cases = caseAggregates(tenant, window);
    Map<String, SalesAgg> sales = salesAggregates(tenant, window);
    Map<String, MoneyAgg> refunds = refundAggregates(tenant, window);
    Map<String, long[]> rejectionByCurrency = rejectionByCurrency(tenant, window);

    List<String> currencies = new ArrayList<>();
    for (CaseTypeAgg agg : cases.values()) {
      if (!currencies.contains(agg.currency())) currencies.add(agg.currency());
    }
    for (String c : sales.keySet()) {
      if (!currencies.contains(c)) currencies.add(c);
    }
    currencies.removeIf(c -> currencyFilter != null && !currencyFilter.equals(c));
    currencies.sort(String::compareTo);

    List<ReturnCurrencySummary> summaries = new ArrayList<>();
    long totalCases = 0;
    BigDecimal totalQty = BigDecimal.ZERO;
    long totalOrders = 0;
    for (String currency : currencies) {
      BasisRow basis = bases.get(currency);
      boolean includeRefundOnly = basis != null && basis.includeRefundOnly();
      long caseCount = 0;
      BigDecimal qty = BigDecimal.ZERO;
      BigDecimal amount = BigDecimal.ZERO;
      for (CaseTypeAgg agg : cases.values()) {
        if (!agg.currency().equals(currency)) continue;
        if (!includeCaseType(agg.currency(), agg.caseType(), bases)) continue;
        caseCount += agg.caseCount();
        qty = qty.add(agg.returnQty());
        amount = amount.add(agg.returnAmount());
      }
      SalesAgg sale = sales.get(currency);
      long orderCount = sale == null ? 0L : sale.orderCount();
      BigDecimal salesAmount = sale == null ? BigDecimal.ZERO : sale.salesAmount();
      MoneyAgg refund = refunds.get(currency);
      long refundCount = refund == null ? 0L : refund.count();
      BigDecimal refundTotal = refund == null ? BigDecimal.ZERO : refund.total();
      BigDecimal avgRefund =
          refundCount == 0 ? null : divide(refundTotal, BigDecimal.valueOf(refundCount), 4);

      long[] rejectionBox = rejectionByCurrency.get(currency);
      long decidedCount = rejectionBox == null ? 0L : rejectionBox[0];
      long rejectedCount = rejectionBox == null ? 0L : rejectionBox[1];
      summaries.add(
          new ReturnCurrencySummary(
              currency,
              basis == null ? CaliberSource.DEFAULT.name() : CaliberSource.TENANT_BASIS.name(),
              basis == null ? "" : basis.basisNo(),
              basis == null ? "默认口径（未登记租户口径）" : basis.basisName(),
              includeRefundOnly,
              caseCount,
              qty,
              amount,
              orderCount,
              salesAmount,
              divide(amount, salesAmount, RATE_SCALE),
              rejectedCount,
              decidedCount,
              divide(BigDecimal.valueOf(rejectedCount), BigDecimal.valueOf(decidedCount), RATE_SCALE),
              refundCount,
              refundTotal,
              avgRefund,
              currencyNote(currency, caseCount, salesAmount, includeRefundOnly)));
      totalCases += caseCount;
      totalQty = totalQty.add(qty);
      totalOrders += orderCount;
    }

    ReturnRejectionSummary rejection = rejectionSummary(tenant, window);
    ReturnProcessingSummary processing = processingSummary(tenant, window);

    List<ReturnReasonSlice> reasonSlices = reasonDistribution(tenant, window, bases);
    List<ReturnAttributionSlice> attributionSlices = attributionDistribution(tenant, window, bases);
    long attributedCases = attributedCaseCount(tenant, window, bases);
    long windowCases = 0;
    for (CaseTypeAgg agg : cases.values()) {
      if (!includeCaseType(agg.currency(), agg.caseType(), bases)) continue;
      windowCases += agg.caseCount();
    }
    BigDecimal attributionCoverage =
        windowCases == 0 ? null : share(attributedCases, windowCases);

    List<ReturnAnomalyThresholds> thresholdRows = new ArrayList<>();
    for (String currency : currencies) {
      // ⚠️ 没有登记口径的币种用**文档化的默认口径**（而不是跳过）——
      //   跳过的话，这个币种的异常阈值就「查不到」，读的人会以为这个币种没有阈值。
      Thresholds t = thresholds.getOrDefault(currency, defaultThresholds(currency));
      thresholdRows.add(
          new ReturnAnomalyThresholds(
              t.currency(),
              t.source(),
              t.rateMultiplier(),
              t.rateMinSalesShare(),
              t.rejectionGapPp(),
              t.rejectionMinCases(),
              t.batchMultiplier(),
              t.batchMinQty(),
              t.reasonShare(),
              t.reasonMinCases(),
              t.trendGapPp()));
    }

    return new ReturnAnalysisOverview(
        at,
        window.note(),
        summaries,
        new ReturnAnalysisTotals(totalCases, totalQty, totalOrders),
        rejection,
        processing,
        reasonSlices,
        attributionSlices,
        attributedCases,
        attributionCoverage,
        thresholdRows,
        "总览的每个数字共用同一个参考时刻 "
            + at
            + "。**异常识别不在本页**（计算量大且是下钻性质），见 "
            + "GET /trade/return-analysis/anomalies —— 那条路径会连同阈值一起报出来。");
  }

  // ================================================================ 维度下钻

  /**
   * 维度下钻 —— 蓝图职责句点名的「按店铺/SKU/类目/原因/批次」。
   *
   * <p>⚠️ <b>每个维度的分母都是该维度自己的同期销售额</b>：
   * SKU 维度的分母是<b>这些 SKU 的行金额</b>，不是全店订单总额 ——
   * 拿全店销售额当每个 SKU 的分母，报出来的是「占比」而不是「退货率」，
   * 而这两个数可以差一个数量级。
   */
  public ReturnBreakdownReport breakdown(
      ReturnAnalysisWindow window, String dimension, String currencyFilter, Integer limit) {
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.ofHours(8));
    UUID tenant = currentTenant();
    ReturnDimension dim = dimensionOrThrow(dimension);
    Map<String, BasisRow> bases = activeBases(tenant);

    List<ReturnBreakdownRow> rows;
    BigDecimal totalReturn = BigDecimal.ZERO;
    BigDecimal totalSales = BigDecimal.ZERO;
    long totalCases = 0;
    BigDecimal coverage = null;
    String caliber;
    /**
     * 参与「合计」的币种集合 —— 合计只在**单币种**时成立。
     *
     * <p>⚠️ 蓝图加粗句「退货率必须与销售额同口径比较」：跨币种的合计退货率
     * （100 元退货 ÷ 1000 日元销售额）不是一个有意义的数。
     * 所以本期出现多个币种时，合计三件一律置 {@code null}，由界面按币种分列呈现。
     */
    Set<String> totalCurrencies = new LinkedHashSet<>();

    switch (dim) {
      case SHOP -> {
        List<ReturnBreakdownRow> shopRows = shopBreakdown(tenant, window, bases, currencyFilter);
        rows = shopRows;
        caliber = ReturnCaliber.CASE_LEVEL.name();
        for (ReturnBreakdownRow r : shopRows) {
          totalReturn = totalReturn.add(r.returnAmount());
          totalSales = totalSales.add(r.salesAmount());
          totalCases += r.caseCount();
          totalCurrencies.add(r.currency());
        }
      }
      case SKU -> {
        List<ReturnBreakdownRow> skuRows = skuBreakdown(tenant, window, bases, currencyFilter);
        rows = skuRows;
        caliber = ReturnCaliber.LINE_LEVEL.name();
        for (ReturnBreakdownRow r : skuRows) {
          totalReturn = totalReturn.add(r.returnAmount());
          totalSales = totalSales.add(r.salesAmount());
          totalCases += r.caseCount();
          totalCurrencies.add(r.currency());
        }
        coverage = lineAmountCoverage(tenant, window, bases);
      }
      case CATEGORY -> {
        List<ReturnBreakdownRow> categoryRows =
            categoryBreakdown(tenant, window, bases, currencyFilter);
        rows = categoryRows;
        caliber = ReturnCaliber.LINE_LEVEL.name();
        for (ReturnBreakdownRow r : categoryRows) {
          totalReturn = totalReturn.add(r.returnAmount());
          totalSales = totalSales.add(r.salesAmount());
          totalCases += r.caseCount();
          totalCurrencies.add(r.currency());
        }
        coverage = lineAmountCoverage(tenant, window, bases);
      }
      case REASON -> {
        List<ReturnBreakdownRow> reasonRows = reasonBreakdown(tenant, window, bases, currencyFilter);
        rows = reasonRows;
        caliber = ReturnCaliber.CASE_LEVEL.name();
        // ⚠️ 原因维度的分母是**全量**同期销售额，而每一行的分母都是同一笔全量 ——
        //   所以合计时**每个币种只能加一次**，按行加会把销售额乘上「原因条数」。
        Map<String, SalesAgg> sales = salesAggregates(tenant, window);
        for (ReturnBreakdownRow r : reasonRows) {
          totalReturn = totalReturn.add(r.returnAmount());
          totalCases += r.caseCount();
        }
        for (SalesAgg s : sales.values()) {
          totalSales = totalSales.add(s.salesAmount());
        }
        // ⚠️ 分母是**全量**销售额，所以参与合计的币种是销售额里出现过的全部币种 ——
        //   哪怕该币种当期没有退货（分子为 0），它的分母也在合计里。
        totalCurrencies.addAll(sales.keySet());
      }
      case BATCH -> {
        List<ReturnBreakdownRow> batchRows = batchBreakdown(tenant, window, currencyFilter);
        rows = batchRows;
        caliber = ReturnCaliber.QTY_LEVEL.name();
        for (ReturnBreakdownRow r : batchRows) {
          totalCases += r.caseCount();
        }
        // ⚠️ 批次维度**没有金额口径**（一笔售后行分批到货时金额归属无唯一答案），
        //   所以合计三件恒为 null，币种集合刻意留空。
      }
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知维度");
    }

    int max = clamp(limit, MAX_REPORT_ROWS);
    List<ReturnBreakdownRow> limited = rows.size() <= max ? rows : rows.subList(0, max);

    BigDecimal totalRate =
        ("QTY_LEVEL".equals(caliber) || totalCurrencies.size() > 1)
            ? null
            : divide(totalReturn, totalSales, RATE_SCALE);
    boolean crossCurrency = totalCurrencies.size() > 1;
    BigDecimal reportReturn = crossCurrency ? null : totalReturn;
    BigDecimal reportSales = crossCurrency ? null : totalSales;

    return new ReturnBreakdownReport(
        at,
        window.note(),
        dim.name(),
        currencyFilter == null ? "" : currencyFilter,
        caliber,
        limited,
        reportReturn,
        reportSales,
        totalRate,
        totalCases,
        coverage,
        breakdownNote(dim, caliber, coverage, rows.size(), max, crossCurrency, totalCurrencies));
  }

  private String breakdownNote(
      ReturnDimension dim,
      String caliber,
      BigDecimal coverage,
      int rowCount,
      int max,
      boolean crossCurrency,
      Set<String> totalCurrencies) {
    StringBuilder s = new StringBuilder();
    s.append("口径：").append(caliberLabel(caliber)).append("。");
    if (dim == ReturnDimension.REASON) {
      s.append("原因的退货率分母是**全量同期销售额** —— 意思是「这个原因占销售额的多少」，"
          + "不是「这个原因内部退了多少」。");
    }
    if (dim == ReturnDimension.BATCH) {
      s.append(BATCH_NOTE);
    }
    if (coverage != null) {
      s.append("**行级金额覆盖率 ")
          .append(plain(coverage))
          .append("%**：分母是期内售后单数，分子是**至少有一条带行级金额的售后行**的单数；"
          + "没有行级金额的售后单（只登记了单头金额）不进入 SKU 与类目下钻，"
          + "所以这两个维度的退货率只覆盖已登记行级金额的那部分。");
    }
    if (crossCurrency) {
      s.append("⚠️ 本期出现 **")
          .append(String.join(" / ", totalCurrencies))
          .append("** 多个币种，按蓝图「退货率必须与销售额同口径比较」的要求，"
              + "**不提供跨币种的合计金额与合计退货率**（合计三件为 null）——"
              + "请按币种逐行读，或在上方选定一个币种后重新查询。");
    }
    if (rowCount > max) {
      s.append("⚠️ 本次只返回前 ").append(max).append(" 行（共 ").append(rowCount).append(" 行）。");
    }
    return s.toString();
  }

  // ================================================================ 趋势

  /**
   * 退货金额与退货率趋势（UTC 分桶）。
   *
   * <p>⚠️ <b>桶边界按 UTC</b>：用会话时区切分桶的话，换一条连接就换一套分桶，
   * 趋势图会凭空多出空桶 —— 而这种错位在图上看起来只是「有几天没退货」。
   */
  public ReturnTrendReport trend(
      ReturnAnalysisWindow window, String granularity, String currencyFilter) {
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.ofHours(8));
    UUID tenant = currentTenant();
    String unit = granularityOrThrow(granularity);
    Map<String, BasisRow> bases = activeBases(tenant);

    Map<String, BucketAgg> buckets = new LinkedHashMap<>();
    StringBuilder where = new StringBuilder();
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    where.append(" and c.tenant_id = ?::uuid");
    if (window.from() != null) {
      where.append(" and c.received_at >= ?::timestamptz");
      binds.add(startOf(window.from()));
    }
    if (window.to() != null) {
      where.append(" and c.received_at < ?::timestamptz");
      binds.add(startOf(window.to().plusDays(1)));
    }
    for (Record r :
        sql.fetch(
            "select date_trunc('" + unit + "', c.received_at at time zone 'utc') as bucket,"
                + " c.currency, c.case_type, count(*) as case_count,"
                + " coalesce(sum(c.requested_quantity),0) as return_qty,"
                + " coalesce(sum(c.requested_amount),0) as return_amount"
                + " from aftersales_case c where true" + where
                + " group by 1,2,3 order by 1",
            binds.toArray())) {
      String currency = r.get("currency", String.class);
      if (currencyFilter != null && !currencyFilter.equals(currency)) continue;
      if (!includeCaseType(currency, r.get("case_type", String.class), bases)) continue;
      String bucket = r.get("bucket", LocalDateTime.class).toString();
      BucketAgg agg =
          buckets.computeIfAbsent(
              bucket + "|" + currency, k -> new BucketAgg(bucket, currency));
      agg.caseCount += r.get("case_count", Long.class);
      agg.returnQty = agg.returnQty.add(r.get("return_qty", BigDecimal.class));
      agg.returnAmount = agg.returnAmount.add(r.get("return_amount", BigDecimal.class));
    }

    List<Object> salesBinds = new ArrayList<>();
    salesBinds.add(tenant);
    StringBuilder salesWhere = new StringBuilder();
    salesWhere.append(" and o.tenant_id = ?::uuid and o.status = 'SUBMITTED'");
    if (window.from() != null) {
      salesWhere.append(" and o.submitted_at >= ?::timestamptz");
      salesBinds.add(startOf(window.from()));
    }
    if (window.to() != null) {
      salesWhere.append(" and o.submitted_at < ?::timestamptz");
      salesBinds.add(startOf(window.to().plusDays(1)));
    }
    for (Record r :
        sql.fetch(
            "select date_trunc('" + unit + "', o.submitted_at at time zone 'utc') as bucket,"
                + " o.currency, count(*) as order_count,"
                + " coalesce(sum(o.grand_total_amount),0) as sales_amount"
                + " from sales_order o where true" + salesWhere
                + " group by 1,2",
            salesBinds.toArray())) {
      String currency = r.get("currency", String.class);
      if (currencyFilter != null && !currencyFilter.equals(currency)) continue;
      String bucket = r.get("bucket", LocalDateTime.class).toString();
      BucketAgg agg =
          buckets.computeIfAbsent(
              bucket + "|" + currency, k -> new BucketAgg(bucket, currency));
      agg.salesOrderCount += r.get("order_count", Long.class);
      agg.salesAmount = agg.salesAmount.add(r.get("sales_amount", BigDecimal.class));
    }

    List<ReturnTrendPoint> points = new ArrayList<>();
    List<String> keys = new ArrayList<>(buckets.keySet());
    keys.sort(
        (a, b) -> {
          int cmp = buckets.get(a).bucket.compareTo(buckets.get(b).bucket);
          return cmp != 0 ? cmp : buckets.get(a).currency.compareTo(buckets.get(b).currency);
        });
    for (String key : keys) {
      BucketAgg agg = buckets.get(key);
      LocalDateTime ldt = LocalDateTime.parse(agg.bucket);
      points.add(
          new ReturnTrendPoint(
              agg.bucket,
              ldt.atOffset(ZoneOffset.UTC),
              agg.currency,
              agg.caseCount,
              agg.returnQty,
              agg.returnAmount,
              agg.salesAmount,
              divide(agg.returnAmount, agg.salesAmount, RATE_SCALE)));
    }

    return new ReturnTrendReport(
        at,
        window.note(),
        unit,
        currencyFilter == null ? "" : currencyFilter,
        points,
        "桶边界按 **UTC** 切（换一条连接就换一套分桶的坑）。"
            + "每个桶自带**同窗口的销售额与退货率**，所以能直接看出「率在涨」还是「只是单量在涨」—— "
            + "只看退货金额的绝对数会误判。分子取售后单**受理时刻**（received_at），"
            + "分母取订单**提交时刻**（submitted_at）：两个是不同的时点，但落在**同一个日历桶**里，"
            + "这正是「同口径」要保证的那一层。");
  }

  // ================================================================ 异常识别

  /**
   * 异常识别 —— 蓝图输出句点名的第三项。
   *
   * <p>五类规则，阈值逐条报出来（{@link ReturnAnomalyThresholds}），
   * 租户可以自己登记口径来调阈值（{@code return_analysis_basis}）。
   */
  public ReturnAnomalyReport anomalies(ReturnAnalysisWindow window, String currencyFilter) {
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.ofHours(8));
    UUID tenant = currentTenant();
    Map<String, BasisRow> bases = activeBases(tenant);
    Map<String, Thresholds> allThresholds = thresholdsByCurrency(bases);

    List<String> currencies = currenciesOf(tenant, window, bases);
    if (currencyFilter != null) {
      currencies.removeIf(c -> !currencyFilter.equals(c));
    }
    if (currencies.isEmpty()) {
      currencies.add(currencyFilter == null ? "USD" : currencyFilter);
    }

    List<ReturnAnomalyRow> rows = new ArrayList<>();
    Thresholds primary = null;
    for (String currency : currencies) {
      Thresholds t = allThresholds.get(currency);
      if (t == null) {
        t = defaultThresholds(currency);
      }
      if (primary == null) primary = t;
      rows.addAll(rateAnomalies(tenant, window, bases, currency, t));
      rows.addAll(rejectionAnomalies(tenant, window, currency, t));
      rows.addAll(batchAnomalies(tenant, window, currency, t));
      rows.addAll(reasonAnomalies(tenant, window, bases, currency, t));
      rows.addAll(trendAnomalies(tenant, window, bases, currency, t));
    }
    if (primary == null) {
      primary = defaultThresholds(currencyFilter == null ? "" : currencyFilter);
    }

    return new ReturnAnomalyReport(
        at,
        window.note(),
        currencyFilter == null ? "" : currencyFilter,
        new ReturnAnomalyThresholds(
            primary.currency(),
            primary.source(),
            primary.rateMultiplier(),
            primary.rateMinSalesShare(),
            primary.rejectionGapPp(),
            primary.rejectionMinCases(),
            primary.batchMultiplier(),
            primary.batchMinQty(),
            primary.reasonShare(),
            primary.reasonMinCases(),
            primary.trendGapPp()),
        rows,
        "五条规则：**退货率倍数异常**（维度率 ≥ 总体率 × 倍数，且维度销售额 ≥ 总体的最小占比）、"
            + "**拒退率异常**（店铺拒退率 ≥ 总体 + 百分点差，且已决定单数 ≥ 最小单数）、"
            + "**批次件数异常**（批次实收件数 ≥ 平均批次件数 × 倍数，且 ≥ 最小件数）、"
            + "**原因集中**（单一原因占比 ≥ 阈值，且案件数 ≥ 最小案件数）、"
            + "**趋势恶化**（本期退货率 − 上期退货率 ≥ 百分点差）。"
            + "阈值取自该币种登记的口径；未登记时用**文档化的默认口径**并在 "
            + "`source` 里标成 DEFAULT。");
  }

  /** 规则一：维度退货率显著高于同口径的总体退货率。 */
  private List<ReturnAnomalyRow> rateAnomalies(
      UUID tenant, ReturnAnalysisWindow window, Map<String, BasisRow> bases, String currency,
      Thresholds t) {
    List<ReturnAnomalyRow> out = new ArrayList<>();
    // 每个维度都与**自己口径的总体率**比：店铺比店铺的总体，SKU 比 SKU 的总体。
    // 跨口径比较（拿行级率比单头率）正是本模块要防的那类错。
    for (ReturnDimension dim : List.of(ReturnDimension.SHOP, ReturnDimension.SKU, ReturnDimension.CATEGORY)) {
      List<ReturnBreakdownRow> rows;
      BigDecimal overallSales;
      BigDecimal overallReturn;
      switch (dim) {
        case SHOP -> {
          rows = shopBreakdown(tenant, window, bases, currency);
          overallSales = BigDecimal.ZERO;
          overallReturn = BigDecimal.ZERO;
          for (ReturnBreakdownRow r : rows) {
            overallSales = overallSales.add(r.salesAmount());
            overallReturn = overallReturn.add(r.returnAmount());
          }
        }
        case SKU -> {
          rows = skuBreakdown(tenant, window, bases, currency);
          overallSales = BigDecimal.ZERO;
          overallReturn = BigDecimal.ZERO;
          for (ReturnBreakdownRow r : rows) {
            overallSales = overallSales.add(r.salesAmount());
            overallReturn = overallReturn.add(r.returnAmount());
          }
        }
        default -> {
          rows = categoryBreakdown(tenant, window, bases, currency);
          overallSales = BigDecimal.ZERO;
          overallReturn = BigDecimal.ZERO;
          for (ReturnBreakdownRow r : rows) {
            overallSales = overallSales.add(r.salesAmount());
            overallReturn = overallReturn.add(r.returnAmount());
          }
        }
      }
      BigDecimal overallRate = divide(overallReturn, overallSales, RATE_SCALE);
      if (overallRate == null) return out;
      BigDecimal minSales = overallSales.multiply(t.rateMinSalesShare());
      BigDecimal threshold = overallRate.multiply(t.rateMultiplier());
      for (ReturnBreakdownRow r : rows) {
        if (r.returnRate() == null) continue;
        if (r.salesAmount().compareTo(minSales) < 0) continue;
        if (r.returnRate().compareTo(threshold) < 0) continue;
        BigDecimal multiple = divide(r.returnRate(), overallRate, 2);
        out.add(
            new ReturnAnomalyRow(
                "RETURN_RATE_SPIKE",
                "退货率异常（" + dim.label() + "）",
                dim.name(),
                r.dimensionKey(),
                r.dimensionLabel(),
                currency,
                multiple != null && multiple.compareTo(new BigDecimal("3")) >= 0 ? "HIGH" : "MEDIUM",
                r.returnRate(),
                overallRate,
                r.returnRate().subtract(overallRate),
                "该" + dim.label() + "退货率 " + plain(r.returnRate())
                    + " 是同口径总体退货率 " + plain(overallRate) + " 的 "
                    + (multiple == null ? "—" : plain(multiple)) + " 倍"
                    + "（阈值 ×" + plain(t.rateMultiplier()) + "，"
                    + "且销售额 " + plain(r.salesAmount()) + " ≥ 总体 " + plain(minSales)
                    + " 的最小占比门槛）。"));
      }
    }
    return out;
  }

  /** 规则二：店铺拒退率显著高于总体拒退率。 */
  private List<ReturnAnomalyRow> rejectionAnomalies(
      UUID tenant, ReturnAnalysisWindow window, String currency, Thresholds t) {
    List<ReturnAnomalyRow> out = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    StringBuilder where = new StringBuilder(" and r.tenant_id = ?::uuid");
    where.append(" and r.rma_status in ('AUTHORIZED','PARTIALLY_AUTHORIZED','REJECTED')");
    if (window.from() != null) {
      where.append(" and r.decided_at >= ?::timestamptz");
      binds.add(startOf(window.from()));
    }
    if (window.to() != null) {
      where.append(" and r.decided_at < ?::timestamptz");
      binds.add(startOf(window.to().plusDays(1)));
    }
    Map<String, long[]> byShop = new LinkedHashMap<>();
    Map<String, String> labels = new LinkedHashMap<>();
    long totalDecided = 0;
    long totalRejected = 0;
    for (Record r :
        sql.fetch(
            "select coalesce(p.platform_code,'') as platform_code,"
                + " coalesce(p.shop_code,'') as shop_code, r.rma_status, count(*) as cnt"
                + " from rma_authorization r"
                + " join aftersales_case c on c.tenant_id = r.tenant_id and c.id = r.case_id"
                + " join sales_order o on o.tenant_id = c.tenant_id and o.id = c.order_id"
                + " left join platform_order p on p.tenant_id = o.tenant_id"
                + "   and p.sales_order_id = o.id"
                + " where true" + where + " group by 1,2,3",
            binds.toArray())) {
      String key = r.get("platform_code", String.class) + "|" + r.get("shop_code", String.class);
      long cnt = r.get("cnt", Long.class);
      String status = r.get("rma_status", String.class);
      long[] box = byShop.computeIfAbsent(key, k -> new long[2]);
      box[0] += cnt;
      totalDecided += cnt;
      if ("REJECTED".equals(status)) {
        box[1] += cnt;
        totalRejected += cnt;
      }
      labels.putIfAbsent(key, shopLabel(r.get("platform_code", String.class), r.get("shop_code", String.class)));
    }
    if (totalDecided == 0) return out;
    BigDecimal overall = divide(BigDecimal.valueOf(totalRejected), BigDecimal.valueOf(totalDecided), RATE_SCALE);
    if (overall == null) return out;
    BigDecimal threshold = overall.add(t.rejectionGapPp().divide(new BigDecimal("100"), RATE_SCALE, RoundingMode.HALF_UP));
    for (Map.Entry<String, long[]> e : byShop.entrySet()) {
      long decided = e.getValue()[0];
      long rejected = e.getValue()[1];
      if (decided < t.rejectionMinCases()) continue;
      BigDecimal rate = divide(BigDecimal.valueOf(rejected), BigDecimal.valueOf(decided), RATE_SCALE);
      if (rate == null) continue;
      if (rate.compareTo(threshold) < 0) continue;
      out.add(
          new ReturnAnomalyRow(
              "REJECTION_RATE_SPIKE",
              "拒退率异常（店铺）",
              ReturnDimension.SHOP.name(),
              e.getKey(),
              labels.get(e.getKey()),
              currency,
              "HIGH",
              rate,
              overall,
              rate.subtract(overall),
              "该店铺拒退率 " + plain(rate) + "，总体拒退率 " + plain(overall)
                  + "，超出 " + plain(t.rejectionGapPp()) + " 个百分点"
                  + "（已决定 " + decided + " 单 ≥ 最小单数 " + t.rejectionMinCases() + "）。"));
    }
    return out;
  }

  /** 规则三：某批次的实收件数显著高于平均批次。 */
  private List<ReturnAnomalyRow> batchAnomalies(
      UUID tenant, ReturnAnalysisWindow window, String currency, Thresholds t) {
    List<ReturnAnomalyRow> out = new ArrayList<>();
    List<ReturnBreakdownRow> rows = batchBreakdown(tenant, window, currency);
    if (rows.isEmpty()) return out;
    BigDecimal total = BigDecimal.ZERO;
    for (ReturnBreakdownRow r : rows) {
      total = total.add(r.receivedQty());
    }
    BigDecimal avg = divide(total, BigDecimal.valueOf(rows.size()), 4);
    if (avg == null) return out;
    BigDecimal threshold = avg.multiply(t.batchMultiplier());
    for (ReturnBreakdownRow r : rows) {
      if (r.receivedQty().compareTo(threshold) < 0) continue;
      if (r.receivedQty().compareTo(t.batchMinQty()) < 0) continue;
      out.add(
          new ReturnAnomalyRow(
              "BATCH_VOLUME_SPIKE",
              "批次件数异常",
              ReturnDimension.BATCH.name(),
              r.dimensionKey(),
              r.dimensionLabel(),
              currency,
              "HIGH",
              r.receivedQty(),
              avg,
              r.receivedQty().subtract(avg),
              "该批次实收 " + plain(r.receivedQty()) + " 件，是平均批次件数 "
                  + plain(avg) + " 的 " + plain(divide(r.receivedQty(), avg, 2)) + " 倍"
                  + "（阈值 ×" + plain(t.batchMultiplier()) + "，且 ≥ 最小件数 "
                  + plain(t.batchMinQty()) + "）。"));
    }
    return out;
  }

  /** 规则四：单一退货原因过度集中。 */
  private List<ReturnAnomalyRow> reasonAnomalies(
      UUID tenant, ReturnAnalysisWindow window, Map<String, BasisRow> bases, String currency,
      Thresholds t) {
    List<ReturnAnomalyRow> out = new ArrayList<>();
    List<ReturnReasonSlice> slices = reasonDistribution(tenant, window, bases);
    long total = 0;
    for (ReturnReasonSlice s : slices) {
      if (!s.currency().equals(currency)) continue;
      total += s.caseCount();
    }
    if (total == 0) return out;
    for (ReturnReasonSlice s : slices) {
      if (!s.currency().equals(currency)) continue;
      if (s.caseCount() < t.reasonMinCases()) continue;
      if (s.share().compareTo(t.reasonShare().multiply(new BigDecimal("100"))) < 0) continue;
      out.add(
          new ReturnAnomalyRow(
              "REASON_CONCENTRATION",
              "退货原因过度集中",
              ReturnDimension.REASON.name(),
              s.reasonCode(),
              s.label(),
              currency,
              "MEDIUM",
              s.share(),
              t.reasonShare().multiply(new BigDecimal("100")),
              s.share().subtract(t.reasonShare().multiply(new BigDecimal("100"))),
              "「" + s.label() + "」占期内退货单 " + plain(s.share()) + "%（"
                  + s.caseCount() + " 单 ≥ 最小案件数 " + t.reasonMinCases()
                  + "），超过集中度阈值 " + plain(t.reasonShare().multiply(new BigDecimal("100"))) + "%。"));
    }
    return out;
  }

  /** 规则五：本期退货率比上一期恶化了若干个百分点。 */
  private List<ReturnAnomalyRow> trendAnomalies(
      UUID tenant, ReturnAnalysisWindow window, Map<String, BasisRow> bases, String currency,
      Thresholds t) {
    List<ReturnAnomalyRow> out = new ArrayList<>();
    if (window.from() == null || window.to() == null) {
      return out;
    }
    long days = java.time.temporal.ChronoUnit.DAYS.between(window.from(), window.to()) + 1;
    if (days <= 0) return out;
    ReturnAnalysisWindow previous =
        new ReturnAnalysisWindow(window.from().minusDays(days), window.from().minusDays(1));

    BigDecimal currentReturn = caseLevelAmount(tenant, window, bases, currency);
    BigDecimal currentSales = caseLevelSales(tenant, window, currency);
    BigDecimal previousReturn = caseLevelAmount(tenant, previous, bases, currency);
    BigDecimal previousSales = caseLevelSales(tenant, previous, currency);

    BigDecimal currentRate = divide(currentReturn, currentSales, RATE_SCALE);
    BigDecimal previousRate = divide(previousReturn, previousSales, RATE_SCALE);
    if (currentRate == null || previousRate == null) return out;
    BigDecimal gapPp = currentRate.subtract(previousRate).multiply(new BigDecimal("100"));
    if (gapPp.compareTo(t.trendGapPp()) < 0) return out;
    out.add(
        new ReturnAnomalyRow(
            "TREND_DETERIORATION",
            "退货率环比恶化",
            "OVERVIEW",
            "TOTAL",
            "总体",
            currency,
            gapPp.compareTo(t.trendGapPp().multiply(new BigDecimal("2"))) >= 0 ? "HIGH" : "MEDIUM",
            currentRate,
            previousRate,
            gapPp,
            "本期退货率 " + plain(currentRate) + "，上一期（" + days + " 天）" + plain(previousRate)
                + "，恶化 " + plain(gapPp) + " 个百分点（阈值 " + plain(t.trendGapPp()) + " 个百分点）。"));
    return out;
  }

  // ================================================================ 快照

  /** 列出已保存的报表快照（追加式，默认按保存时间倒序）。 */
  public ReturnAnalysisSnapshotPage listSnapshots(
      String dimension, String currency, String snapshotNo, int page, int size) {
    UUID tenant = currentTenant();
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    StringBuilder where = new StringBuilder(" where tenant_id = ?::uuid");
    if (dimension != null && !dimension.isBlank()) {
      where.append(" and dimension = ?");
      binds.add(dimension.trim().toUpperCase(Locale.ROOT));
    }
    if (currency != null && !currency.isBlank()) {
      where.append(" and currency = ?");
      binds.add(currency.trim().toUpperCase(Locale.ROOT));
    }
    if (snapshotNo != null && !snapshotNo.isBlank()) {
      where.append(" and snapshot_no = ?");
      binds.add(snapshotNo.trim());
    }
    long total =
        sql.fetchOne("select count(*) as c from return_analysis_snapshot" + where, binds.toArray())
            .get("c", Long.class);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add((long) p * s);
    List<ReturnAnalysisSnapshotRow> rows = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select id, snapshot_no, window_from, window_to, currency, dimension, dimension_key,"
                + " dimension_label, caliber, case_count, return_qty, return_amount, sales_amount,"
                + " return_rate, rejected_count, decided_count, rejection_rate, refund_count,"
                + " refund_total, avg_refund_amount, avg_resolution_hours, anomaly_code, anomaly_note,"
                + " basis_note, computed_at"
                + " from return_analysis_snapshot" + where
                + " order by computed_at desc, snapshot_no desc, dimension, dimension_key"
                + " limit ? offset ?",
            pageBinds.toArray())) {
      rows.add(snapshotRow(r));
    }
    return new ReturnAnalysisSnapshotPage(rows, total, p, s);
  }

  /**
   * 保存一份退货分析报表快照。
   *
   * <p>快照是**追加式**的：算出来的每个维度值原样落库，
   * 三个月后「上个月那张报表是多少」才答得出来。
   */
  public ReturnAnalysisSnapshotPage createSnapshot(
      ReturnAnalysisSnapshotRequest request, UUID userId) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
    }
    String currency = require(request.currency(), "币种", 3).toUpperCase(Locale.ROOT);
    String dimension = require(request.dimension(), "维度", 16).toUpperCase(Locale.ROOT);
    ReturnDimension dim = dimensionOrThrow(dimension);
    ReturnAnalysisWindow window = new ReturnAnalysisWindow(request.from(), request.to());

    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.ofHours(8));
    ReturnBreakdownReport report = breakdown(window, dimension, currency, MAX_REPORT_ROWS);
    ReturnAnalysisOverview overview = overview(window, currency);
    ReturnCurrencySummary summary =
        overview.currencies().isEmpty() ? null : overview.currencies().get(0);

    UUID tenant = currentTenant();
    String snapshotNo = nextNo("return_analysis_snapshot_no_seq", SNAPSHOT_PREFIX);
    String basisNote =
        "口径："
            + report.caliber()
            + "；窗口 "
            + window.note()
            + "；参考时刻 "
            + at
            + "。分子与分母**同币种同窗口**，币种 "
            + currency
            + "。";

    // ⚠️ **先落总体行（OVERVIEW）**：维度行里的拒退率 / 退款额 / 时长是
    //   **总体口径**，维度行不适用（填 0 会被读成「这一行没有拒退」）。
    //   总体行把这些数带上，快照才是自洽的一份报表。
    if (summary != null) {
      // ⚠️ 退货率 > 1 **不拦**（上期卖出的货这期退回来是可能的），
      //   但要在快照里显式标出来 —— 否则这张报表日后会被当成「口径错了」。
      if (summary.returnRate() != null && summary.returnRate().compareTo(BigDecimal.ONE) > 0) {
        basisNote =
            basisNote
                + " ⚠️ **退货率超过 100%**：本期退款金额大于本期销售额 —— "
                + "可能是上期卖出的货这期退回来，也可能是口径取错，请核对。";
      }
      insertSnapshotRow(
          tenant,
          snapshotNo,
          request.from(),
          request.to(),
          currency,
          "OVERVIEW",
          "TOTAL",
          "总体",
          ReturnCaliber.CASE_LEVEL.name(),
          summary.caseCount(),
          summary.returnQty(),
          summary.returnAmount(),
          summary.salesAmount(),
          summary.returnRate(),
          Long.valueOf(summary.rejectedCount()),
          Long.valueOf(summary.decidedCount()),
          summary.rejectionRate(),
          Long.valueOf(summary.refundCount()),
          summary.refundTotal(),
          summary.avgRefundAmount(),
          null,
          "",
          "",
          basisNote + " **本行是总体口径**：拒退率、退款与时效是总体数字，维度行不适用。",
          at,
          userId);
    }

    for (ReturnBreakdownRow row : report.rows()) {
      insertSnapshotRow(
          tenant,
          snapshotNo,
          request.from(),
          request.to(),
          currency,
          dim.name(),
          row.dimensionKey(),
          row.dimensionLabel(),
          row.caliber(),
          row.caseCount(),
          row.returnQty(),
          row.returnAmount(),
          row.salesAmount(),
          row.returnRate(),
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          "",
          "",
          basisNote,
          at,
          userId);
    }

    sql.execute(
        "insert into return_analysis_event(id, tenant_id, event_scope, event_type, subject_id,"
            + " event_note, occurred_by, occurred_at)"
            + " values(?::uuid,?::uuid,'SNAPSHOT','CREATED',?::uuid,?::varchar,?::uuid,?::timestamptz)",
        UUID.randomUUID(),
        tenant,
        UUID.nameUUIDFromBytes(
            ("snapshot:" + snapshotNo).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        "保存退货分析报表快照 " + snapshotNo + "（维度 " + dim.name() + "，币种 " + currency + "）",
        userId,
        at);
    return listSnapshots(null, currency, snapshotNo, 0, MAX_PAGE_SIZE);
  }

  /** 落一行快照。 */
  private void insertSnapshotRow(
      UUID tenant,
      String snapshotNo,
      LocalDate from,
      LocalDate to,
      String currency,
      String dimension,
      String dimensionKey,
      String dimensionLabel,
      String caliber,
      long caseCount,
      BigDecimal returnQty,
      BigDecimal returnAmount,
      BigDecimal salesAmount,
      BigDecimal returnRate,
      Long rejectedCount,
      Long decidedCount,
      BigDecimal rejectionRate,
      Long refundCount,
      BigDecimal refundTotal,
      BigDecimal avgRefundAmount,
      BigDecimal avgResolutionHours,
      String anomalyCode,
      String anomalyNote,
      String basisNote,
      OffsetDateTime at,
      UUID userId) {
    sql.execute(
        "insert into return_analysis_snapshot(id, tenant_id, snapshot_no, window_from, window_to,"
            + " currency, dimension, dimension_key, dimension_label, caliber, case_count,"
            + " return_qty, return_amount, sales_amount, return_rate, rejected_count, decided_count,"
            + " rejection_rate, refund_count, refund_total, avg_refund_amount, avg_resolution_hours,"
            + " anomaly_code, anomaly_note, basis_note, computed_at, created_by)"
            + " values(?::uuid,?::uuid,?,?::date,?::date,?,?,?,?,?,?::bigint,?::numeric,?::numeric,"
            + "?::numeric,?::numeric,?::bigint,?::bigint,?::numeric,?::bigint,?::numeric,?::numeric,"
            + "?::numeric,?,?,?,?::timestamptz,?::uuid)",
        UUID.randomUUID(),
        tenant,
        snapshotNo,
        from,
        to,
        currency,
        dimension,
        dimensionKey,
        dimensionLabel,
        caliber,
        caseCount,
        returnQty,
        returnAmount,
        salesAmount,
        returnRate,
        rejectedCount,
        decidedCount,
        rejectionRate,
        refundCount,
        refundTotal,
        avgRefundAmount,
        avgResolutionHours,
        anomalyCode,
        anomalyNote,
        basisNote,
        at,
        userId);
  }

  private ReturnAnalysisSnapshotRow snapshotRow(Record r) {
    return new ReturnAnalysisSnapshotRow(
        r.get("id", UUID.class),
        r.get("snapshot_no", String.class),
        r.get("window_from", LocalDate.class),
        r.get("window_to", LocalDate.class),
        r.get("currency", String.class),
        r.get("dimension", String.class),
        r.get("dimension_key", String.class),
        r.get("dimension_label", String.class),
        r.get("caliber", String.class),
        r.get("case_count", Long.class),
        r.get("return_qty", BigDecimal.class),
        r.get("return_amount", BigDecimal.class),
        r.get("sales_amount", BigDecimal.class),
        r.get("return_rate", BigDecimal.class),
        r.get("rejected_count", Long.class),
        r.get("decided_count", Long.class),
        r.get("rejection_rate", BigDecimal.class),
        r.get("refund_count", Long.class),
        r.get("refund_total", BigDecimal.class),
        r.get("avg_refund_amount", BigDecimal.class),
        r.get("avg_resolution_hours", BigDecimal.class),
        r.get("anomaly_code", String.class),
        r.get("anomaly_note", String.class),
        r.get("basis_note", String.class),
        r.get("computed_at", OffsetDateTime.class));
  }

  // ================================================================ 口径

  /** 列出本租户登记的口径（默认只列生效版本）。 */
  public ReturnAnalysisBasisPage listBasis(String currency, String status, int page, int size) {
    UUID tenant = currentTenant();
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    StringBuilder where = new StringBuilder(" where tenant_id = ?::uuid");
    if (currency != null && !currency.isBlank()) {
      where.append(" and currency = ?");
      binds.add(currency.trim().toUpperCase(Locale.ROOT));
    }
    if (status != null && !status.isBlank()) {
      where.append(" and basis_status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    long total =
        sql.fetchOne("select count(*) as c from return_analysis_basis" + where, binds.toArray())
            .get("c", Long.class);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add((long) p * s);
    List<ReturnAnalysisBasisRow> rows = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select id, basis_no, basis_name, currency, include_refund_only, rate_alert_multiplier,"
                + " rate_alert_min_sales_share, rejection_alert_gap_pp, rejection_alert_min_cases,"
                + " batch_alert_multiplier, batch_alert_min_qty, reason_alert_share,"
                + " reason_alert_min_cases, trend_alert_gap_pp, basis_status, effective_from,"
                + " effective_to, source_note, version, created_at, superseded_by, superseded_at,"
                + " supersede_reason from return_analysis_basis" + where
                + " order by currency, effective_from desc, basis_no desc limit ? offset ?",
            pageBinds.toArray())) {
      rows.add(
          new ReturnAnalysisBasisRow(
              r.get("id", UUID.class),
              r.get("basis_no", String.class),
              r.get("basis_name", String.class),
              r.get("currency", String.class),
              r.get("include_refund_only", Boolean.class),
              r.get("rate_alert_multiplier", BigDecimal.class),
              r.get("rate_alert_min_sales_share", BigDecimal.class),
              r.get("rejection_alert_gap_pp", BigDecimal.class),
              r.get("rejection_alert_min_cases", Long.class),
              r.get("batch_alert_multiplier", BigDecimal.class),
              r.get("batch_alert_min_qty", BigDecimal.class),
              r.get("reason_alert_share", BigDecimal.class),
              r.get("reason_alert_min_cases", Long.class),
              r.get("trend_alert_gap_pp", BigDecimal.class),
              r.get("basis_status", String.class),
              r.get("effective_from", LocalDate.class),
              r.get("effective_to", LocalDate.class),
              r.get("source_note", String.class),
              r.get("version", Long.class),
              r.get("created_at", OffsetDateTime.class),
              r.get("superseded_by", UUID.class),
              r.get("superseded_at", OffsetDateTime.class),
              r.get("supersede_reason", String.class)));
    }
    return new ReturnAnalysisBasisPage(rows, total, p, s);
  }

  /** 登记一份口径（建行恒为生效，且不预设截止日）。 */
  public ReturnAnalysisBasisRow createBasis(ReturnAnalysisBasisCreate request, UUID userId) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
    }
    String name = require(request.basisName(), "口径名", 128);
    String currency = require(request.currency(), "币种", 3).toUpperCase(Locale.ROOT);
    if (!currency.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是三个大写字母");
    }
    if (request.effectiveFrom() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "口径生效日必填");
    }
    if (sql.fetchOne(
            "select 1 from return_analysis_basis where tenant_id = ?::uuid and currency = ?"
                + " and basis_status = 'ACTIVE'",
            currentTenant(),
            currency)
        != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "币种 " + currency + " 已有一份生效口径 —— 换口径请走「换版」（老版本保留为已换版），"
              + "两个币种两份同时生效会让「按哪一份算」没有唯一答案");
    }
    BigDecimal multiplier = positive(request.rateAlertMultiplier(), "退货率异常倍数");
    BigDecimal minShare = share(request.rateAlertMinSalesShare(), "最小销售额占比");
    BigDecimal rejectionGap = nonNegative(request.rejectionAlertGapPp(), "拒退率异常百分点差");
    long rejectionMinCases = nonNegative(request.rejectionAlertMinCases(), "拒退率异常最小单数");
    BigDecimal batchMultiplier = positive(request.batchAlertMultiplier(), "批次件数异常倍数");
    BigDecimal batchMinQty = nonNegative(request.batchAlertMinQty(), "批次件数异常最小件数");
    BigDecimal reasonShare = share(request.reasonAlertShare(), "原因集中异常占比");
    long reasonMinCases = nonNegative(request.reasonAlertMinCases(), "原因集中异常最小案件数");
    BigDecimal trendGap = nonNegative(request.trendAlertGapPp(), "趋势恶化异常百分点差");
    String sourceNote = optional(request.sourceNote(), 500, "口径说明");

    UUID tenant = currentTenant();
    UUID id = UUID.randomUUID();
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.ofHours(8));
    String basisNo = nextNo("return_analysis_basis_no_seq", BASIS_PREFIX);
    sql.execute(
        "insert into return_analysis_basis(id, tenant_id, basis_no, basis_name, currency,"
            + " include_refund_only, rate_alert_multiplier, rate_alert_min_sales_share,"
            + " rejection_alert_gap_pp, rejection_alert_min_cases, batch_alert_multiplier,"
            + " batch_alert_min_qty, reason_alert_share, reason_alert_min_cases, trend_alert_gap_pp,"
            + " basis_status, effective_from, source_note, created_by, created_at)"
            + " values(?::uuid,?::uuid,?,?,?,?::boolean,?::numeric,?::numeric,?::numeric,?::bigint,"
            + "?::numeric,?::numeric,?::numeric,?::bigint,?::numeric,'ACTIVE',?::date,?::varchar,"
            + "?::uuid,?::timestamptz)",
        id,
        tenant,
        basisNo,
        name,
        currency,
        request.includeRefundOnly(),
        multiplier,
        minShare,
        rejectionGap,
        rejectionMinCases,
        batchMultiplier,
        batchMinQty,
        reasonShare,
        reasonMinCases,
        trendGap,
        request.effectiveFrom(),
        sourceNote,
        userId,
        at);
    sql.execute(
        "insert into return_analysis_event(id, tenant_id, event_scope, event_type, subject_id,"
            + " event_note, occurred_by, occurred_at)"
            + " values(?::uuid,?::uuid,'BASIS','CREATED',?::uuid,?::varchar,?::uuid,?::timestamptz)",
        UUID.randomUUID(),
        tenant,
        id,
        "登记退货分析口径 " + basisNo + "（币种 " + currency + "）",
        userId,
        at);
    return basisRow(id);
  }

  /**
   * 口径换版（原子：老版本置为已换版 + 新版本建成生效，在一个事务里）。
   *
   * <p>⚠️ <b>必须是原子的</b>：分两步（先换版、再新建）会在两步之间留下
   * 「这个币种没有生效口径」的窗口 —— 那段时间里算出来的退货率用的是默认口径，
   * 却没有任何记录说明它用的是默认口径。
   */
  public ReturnAnalysisBasisRow supersedeBasis(
      UUID id, ReturnAnalysisBasisSupersede request, UUID userId) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
    }
    String reason = require(request.supersedeReason(), "换版理由", 1000);
    String name = require(request.basisName(), "口径名", 128);
    String currency = require(request.currency(), "币种", 3).toUpperCase(Locale.ROOT);
    if (!currency.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是三个大写字母");
    }
    if (request.effectiveFrom() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "口径生效日必填");
    }
    BigDecimal multiplier = positive(request.rateAlertMultiplier(), "退货率异常倍数");
    BigDecimal minShare = share(request.rateAlertMinSalesShare(), "最小销售额占比");
    BigDecimal rejectionGap = nonNegative(request.rejectionAlertGapPp(), "拒退率异常百分点差");
    long rejectionMinCases = nonNegative(request.rejectionAlertMinCases(), "拒退率异常最小单数");
    BigDecimal batchMultiplier = positive(request.batchAlertMultiplier(), "批次件数异常倍数");
    BigDecimal batchMinQty = nonNegative(request.batchAlertMinQty(), "批次件数异常最小件数");
    BigDecimal reasonShare = share(request.reasonAlertShare(), "原因集中异常占比");
    long reasonMinCases = nonNegative(request.reasonAlertMinCases(), "原因集中异常最小案件数");
    BigDecimal trendGap = nonNegative(request.trendAlertGapPp(), "趋势恶化异常百分点差");
    String sourceNote = optional(request.sourceNote(), 500, "口径说明");

    UUID tenant = currentTenant();
    Record current =
        sql.fetchOne(
            "select version, basis_status, basis_no, currency from return_analysis_basis"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            tenant,
            id);
    if (current == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "口径不存在或不属于当前租户");
    }
    if (current.get("version", Long.class) != request.version()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "口径已被别人改过（当前版本 " + current.get("version", Long.class) + "，你的版本 "
              + request.version() + "）");
    }
    if (!"ACTIVE".equals(current.get("basis_status", String.class))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "只有生效中的口径才能换版");
    }
    if (!currency.equals(current.get("currency", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "换版只能换同一个币种的口径（当前是 " + current.get("currency", String.class)
              + "，请求是 " + currency + "）—— 换币种就是另一套口径，请直接新建");
    }

    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.ofHours(8));
    UUID newId = UUID.randomUUID();
    String newNo = nextNo("return_analysis_basis_no_seq", BASIS_PREFIX);

    // 留痕**先写**：V271 守卫①在 DB 层要求换版前已经有 BASIS/SUPERSEDED 的留痕。
    sql.execute(
        "insert into return_analysis_event(id, tenant_id, event_scope, event_type, subject_id,"
            + " event_note, occurred_by, occurred_at)"
            + " values(?::uuid,?::uuid,'BASIS','SUPERSEDED',?::uuid,?::varchar,?::uuid,?::timestamptz)",
        UUID.randomUUID(),
        tenant,
        id,
        "口径换版：" + reason + "（新口径 " + newNo + "）",
        userId,
        at);

    int updated =
        sql.execute(
            "update return_analysis_basis set basis_status = 'SUPERSEDED', superseded_by = ?::uuid,"
                + " superseded_at = ?::timestamptz, supersede_reason = ?, version = version + 1"
                + " where tenant_id = ?::uuid and id = ?::uuid and version = ?",
            newId,
            at,
            reason,
            tenant,
            id,
            request.version());
    if (updated == 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "口径已被别人改过，请刷新后重试");
    }

    sql.execute(
        "insert into return_analysis_basis(id, tenant_id, basis_no, basis_name, currency,"
            + " include_refund_only, rate_alert_multiplier, rate_alert_min_sales_share,"
            + " rejection_alert_gap_pp, rejection_alert_min_cases, batch_alert_multiplier,"
            + " batch_alert_min_qty, reason_alert_share, reason_alert_min_cases, trend_alert_gap_pp,"
            + " basis_status, effective_from, source_note, created_by, created_at)"
            + " values(?::uuid,?::uuid,?,?,?,?::boolean,?::numeric,?::numeric,?::numeric,?::bigint,"
            + "?::numeric,?::numeric,?::numeric,?::bigint,?::numeric,'ACTIVE',?::date,?::varchar,"
            + "?::uuid,?::timestamptz)",
        newId,
        tenant,
        newNo,
        name,
        currency,
        request.includeRefundOnly(),
        multiplier,
        minShare,
        rejectionGap,
        rejectionMinCases,
        batchMultiplier,
        batchMinQty,
        reasonShare,
        reasonMinCases,
        trendGap,
        request.effectiveFrom(),
        sourceNote,
        userId,
        at);
    sql.execute(
        "insert into return_analysis_event(id, tenant_id, event_scope, event_type, subject_id,"
            + " event_note, occurred_by, occurred_at)"
            + " values(?::uuid,?::uuid,'BASIS','CREATED',?::uuid,?::varchar,?::uuid,?::timestamptz)",
        UUID.randomUUID(),
        tenant,
        newId,
        "换版生成的新口径 " + newNo + "（币种 " + currency + "）",
        userId,
        at);
    return basisRow(newId);
  }

  private ReturnAnalysisBasisRow basisRow(UUID id) {
    Record r =
        sql.fetchOne(
            "select id, basis_no, basis_name, currency, include_refund_only, rate_alert_multiplier,"
                + " rate_alert_min_sales_share, rejection_alert_gap_pp, rejection_alert_min_cases,"
                + " batch_alert_multiplier, batch_alert_min_qty, reason_alert_share,"
                + " reason_alert_min_cases, trend_alert_gap_pp, basis_status, effective_from,"
                + " effective_to, source_note, version, created_at, superseded_by, superseded_at,"
                + " supersede_reason from return_analysis_basis where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            id);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "口径不存在或不属于当前租户");
    }
    return new ReturnAnalysisBasisRow(
        r.get("id", UUID.class),
        r.get("basis_no", String.class),
        r.get("basis_name", String.class),
        r.get("currency", String.class),
        r.get("include_refund_only", Boolean.class),
        r.get("rate_alert_multiplier", BigDecimal.class),
        r.get("rate_alert_min_sales_share", BigDecimal.class),
        r.get("rejection_alert_gap_pp", BigDecimal.class),
        r.get("rejection_alert_min_cases", Long.class),
        r.get("batch_alert_multiplier", BigDecimal.class),
        r.get("batch_alert_min_qty", BigDecimal.class),
        r.get("reason_alert_share", BigDecimal.class),
        r.get("reason_alert_min_cases", Long.class),
        r.get("trend_alert_gap_pp", BigDecimal.class),
        r.get("basis_status", String.class),
        r.get("effective_from", LocalDate.class),
        r.get("effective_to", LocalDate.class),
        r.get("source_note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("superseded_by", UUID.class),
        r.get("superseded_at", OffsetDateTime.class),
        r.get("supersede_reason", String.class));
  }

  // ================================================================ 聚合查询

  private record CaseTypeAgg(
      String currency, String caseType, long caseCount, BigDecimal returnQty, BigDecimal returnAmount) {}

  private record SalesAgg(String currency, long orderCount, BigDecimal salesAmount, BigDecimal salesQty) {}

  private record MoneyAgg(String currency, long count, BigDecimal total) {}

  /**
   * 可累加的聚合盒。
   *
   * <p>⚠️ 用它而不是 {@code List<Object>}：{@code List.of(0L, BigDecimal.ZERO)} 的元素类型
   * 会被推成 {@code Object}，于是 {@code box.get(1).add(...)} 编译不过 ——
   * 而这类「先算好再装进去」的写法一旦类型退化成 Object，金额就能被当成别的类型加进去。
   */
  private static final class Agg {
    long caseCount;
    long orderCount;
    BigDecimal qty = BigDecimal.ZERO;
    BigDecimal amount = BigDecimal.ZERO;
    BigDecimal salesQty = BigDecimal.ZERO;
    BigDecimal salesAmount = BigDecimal.ZERO;
  }

  /** 趋势上的一个时间桶（可累加）。 */
  private static final class BucketAgg {
    final String bucket;
    final String currency;
    long caseCount;
    long salesOrderCount;
    BigDecimal returnQty = BigDecimal.ZERO;
    BigDecimal returnAmount = BigDecimal.ZERO;
    BigDecimal salesAmount = BigDecimal.ZERO;

    BucketAgg(String bucket, String currency) {
      this.bucket = bucket;
      this.currency = currency;
    }
  }

  /** 售后单按 (币种, 类型) 聚合 —— 分子。 */
  private Map<String, CaseTypeAgg> caseAggregates(UUID tenant, ReturnAnalysisWindow window) {
    Map<String, CaseTypeAgg> out = new LinkedHashMap<>();
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    StringBuilder where = new StringBuilder(" and c.tenant_id = ?::uuid and c.status <> 'REJECTED'");
    if (window.from() != null) {
      where.append(" and c.received_at >= ?::timestamptz");
      binds.add(startOf(window.from()));
    }
    if (window.to() != null) {
      where.append(" and c.received_at < ?::timestamptz");
      binds.add(startOf(window.to().plusDays(1)));
    }
    for (Record r :
        sql.fetch(
            "select c.currency, c.case_type, count(*) as case_count,"
                + " coalesce(sum(c.requested_quantity),0) as return_qty,"
                + " coalesce(sum(c.requested_amount),0) as return_amount"
                + " from aftersales_case c where true" + where + " group by 1,2",
            binds.toArray())) {
      String currency = r.get("currency", String.class);
      out.put(
          currency + "|" + r.get("case_type", String.class),
          new CaseTypeAgg(
              currency,
              r.get("case_type", String.class),
              r.get("case_count", Long.class),
              r.get("return_qty", BigDecimal.class),
              r.get("return_amount", BigDecimal.class)));
    }
    return out;
  }

  /** 已提交订单按币种聚合 —— 分母。 */
  private Map<String, SalesAgg> salesAggregates(UUID tenant, ReturnAnalysisWindow window) {
    Map<String, SalesAgg> out = new LinkedHashMap<>();
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    StringBuilder where = new StringBuilder(" and o.tenant_id = ?::uuid and o.status = 'SUBMITTED'");
    if (window.from() != null) {
      where.append(" and o.submitted_at >= ?::timestamptz");
      binds.add(startOf(window.from()));
    }
    if (window.to() != null) {
      where.append(" and o.submitted_at < ?::timestamptz");
      binds.add(startOf(window.to().plusDays(1)));
    }
    for (Record r :
        sql.fetch(
            "select o.currency, count(*) as order_count,"
                + " coalesce(sum(o.grand_total_amount),0) as sales_amount"
                + " from sales_order o where true" + where + " group by 1",
            binds.toArray())) {
      out.put(
          r.get("currency", String.class),
          new SalesAgg(
              r.get("currency", String.class),
              r.get("order_count", Long.class),
              r.get("sales_amount", BigDecimal.class),
              BigDecimal.ZERO));
    }
    return out;
  }

  /** 已成功退款按币种聚合。 */
  private Map<String, MoneyAgg> refundAggregates(UUID tenant, ReturnAnalysisWindow window) {
    Map<String, MoneyAgg> out = new LinkedHashMap<>();
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    StringBuilder where =
        new StringBuilder(
            " and r.tenant_id = ?::uuid and r.application_status = 'SUCCEEDED'");
    if (window.from() != null) {
      where.append(" and r.outcome_recorded_at >= ?::timestamptz");
      binds.add(startOf(window.from()));
    }
    if (window.to() != null) {
      where.append(" and r.outcome_recorded_at < ?::timestamptz");
      binds.add(startOf(window.to().plusDays(1)));
    }
    for (Record r :
        sql.fetch(
            "select r.currency, count(*) as refund_count,"
                + " coalesce(sum(r.refund_amount),0) as refund_total"
                + " from refund_application r where true" + where + " group by 1",
            binds.toArray())) {
      out.put(
          r.get("currency", String.class),
          new MoneyAgg(
              r.get("currency", String.class),
              r.get("refund_count", Long.class),
              r.get("refund_total", BigDecimal.class)));
    }
    return out;
  }

  /** 拒退率汇总。 */
  private ReturnRejectionSummary rejectionSummary(UUID tenant, ReturnAnalysisWindow window) {
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    StringBuilder where =
        new StringBuilder(
            " and r.tenant_id = ?::uuid"
                + " and r.rma_status in ('AUTHORIZED','PARTIALLY_AUTHORIZED','REJECTED')");
    if (window.from() != null) {
      where.append(" and r.decided_at >= ?::timestamptz");
      binds.add(startOf(window.from()));
    }
    if (window.to() != null) {
      where.append(" and r.decided_at < ?::timestamptz");
      binds.add(startOf(window.to().plusDays(1)));
    }
    long authorized = 0;
    long partial = 0;
    long rejected = 0;
    for (Record r :
        sql.fetch(
            "select r.rma_status, count(*) as cnt from rma_authorization r where true" + where
                + " group by 1",
            binds.toArray())) {
      long cnt = r.get("cnt", Long.class);
      switch (r.get("rma_status", String.class)) {
        case "AUTHORIZED" -> authorized = cnt;
        case "PARTIALLY_AUTHORIZED" -> partial = cnt;
        case "REJECTED" -> rejected = cnt;
        default -> { }
      }
    }
    long decided = authorized + partial + rejected;

    List<Object> qtyBinds = new ArrayList<>();
    qtyBinds.add(tenant);
    StringBuilder qtyWhere = new StringBuilder(" and r.tenant_id = ?::uuid"
        + " and r.rma_status in ('AUTHORIZED','PARTIALLY_AUTHORIZED','REJECTED')");
    if (window.from() != null) {
      qtyWhere.append(" and r.decided_at >= ?::timestamptz");
      qtyBinds.add(startOf(window.from()));
    }
    if (window.to() != null) {
      qtyWhere.append(" and r.decided_at < ?::timestamptz");
      qtyBinds.add(startOf(window.to().plusDays(1)));
    }
    Record qty =
        sql.fetchOne(
            "select coalesce(sum(rl.requested_qty),0) as requested_qty,"
                + " coalesce(sum(rl.authorized_qty),0) as authorized_qty"
                + " from rma_authorization_line rl"
                + " join rma_authorization r on r.tenant_id = rl.tenant_id and r.id = rl.rma_id"
                + " where true" + qtyWhere,
            qtyBinds.toArray());
    BigDecimal requestedQty = nz(qty.get("requested_qty", BigDecimal.class));
    BigDecimal authorizedQty = nz(qty.get("authorized_qty", BigDecimal.class));

    return new ReturnRejectionSummary(
        decided,
        rejected,
        partial,
        authorized,
        divide(BigDecimal.valueOf(rejected), BigDecimal.valueOf(decided), RATE_SCALE),
        requestedQty,
        authorizedQty,
        requestedQty.signum() == 0
            ? null
            : divide(requestedQty.subtract(authorizedQty), requestedQty, RATE_SCALE),
        REJECTION_NOTE);
  }

  /**
   * 逐币种的拒退计数。
   *
   * <p>⚠️ 币种来自**售后单关联的销售订单**（退货授权单上没有币种列）。
   * 拒退率是**计数比**，本身与币种无关，但拆到币种上才读得出
   * 「哪个币种的买家被拒得最多」。
   */
  private Map<String, long[]> rejectionByCurrency(UUID tenant, ReturnAnalysisWindow window) {
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    StringBuilder where =
        new StringBuilder(
            " and r.tenant_id = ?::uuid"
                + " and r.rma_status in ('AUTHORIZED','PARTIALLY_AUTHORIZED','REJECTED')");
    if (window.from() != null) {
      where.append(" and r.decided_at >= ?::timestamptz");
      binds.add(startOf(window.from()));
    }
    if (window.to() != null) {
      where.append(" and r.decided_at < ?::timestamptz");
      binds.add(startOf(window.to().plusDays(1)));
    }
    Map<String, long[]> out = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select o.currency, r.rma_status, count(*) as cnt"
                + " from rma_authorization r"
                + " join aftersales_case c on c.tenant_id = r.tenant_id and c.id = r.case_id"
                + " join sales_order o on o.tenant_id = c.tenant_id and o.id = c.order_id"
                + " where true" + where + " group by 1,2",
            binds.toArray())) {
      String currency = r.get("currency", String.class);
      long[] box = out.computeIfAbsent(currency, k -> new long[2]);
      box[0] += r.get("cnt", Long.class);
      if ("REJECTED".equals(r.get("rma_status", String.class))) {
        box[1] += r.get("cnt", Long.class);
      }
    }
    return out;
  }

  /** 处理时效汇总（四个时长，各自带样本数）。 */
  private ReturnProcessingSummary processingSummary(UUID tenant, ReturnAnalysisWindow window) {
    Record caseTime =
        durationAggregate(
            tenant,
            window,
            "select count(*) as cnt,"
                + " coalesce(avg(extract(epoch from (c.resolved_at - c.received_at))/3600),0) as avg_h,"
                + " coalesce(max(extract(epoch from (c.resolved_at - c.received_at))/3600),0) as max_h"
                + " from aftersales_case c where c.tenant_id = ?::uuid and c.resolved_at is not null",
            "c.resolved_at");
    Record rmaTime =
        durationAggregate(
            tenant,
            window,
            "select count(*) as cnt,"
                + " coalesce(avg(extract(epoch from (r.decided_at - r.created_at))/3600),0) as avg_h"
                + " from rma_authorization r where r.tenant_id = ?::uuid and r.decided_at is not null",
            "r.decided_at");
    Record receiptTime =
        durationAggregate(
            tenant,
            window,
            "select count(*) as cnt,"
                + " coalesce(avg(extract(epoch from (rr.received_at - r.return_shipped_at))/3600),0)"
                + "   as avg_h"
                + " from return_receipt rr"
                + " join rma_authorization r on r.tenant_id = rr.tenant_id and r.id = rr.rma_id"
                + " where rr.tenant_id = ?::uuid and r.return_shipped_at is not null",
            "rr.received_at");
    Record refundTime =
        durationAggregate(
            tenant,
            window,
            "select count(*) as cnt,"
                + " coalesce(avg(extract(epoch from (f.outcome_recorded_at - f.submitted_at))/3600),0)"
                + "   as avg_h"
                + " from refund_application f where f.tenant_id = ?::uuid"
                + " and f.outcome_recorded_at is not null and f.submitted_at is not null",
            "f.outcome_recorded_at");

    long caseCount = caseTime.get("cnt", Long.class);
    long rmaCount = rmaTime.get("cnt", Long.class);
    long receiptCount = receiptTime.get("cnt", Long.class);
    long refundCount = refundTime.get("cnt", Long.class);

    return new ReturnProcessingSummary(
        caseCount == 0 ? null : nz(caseTime.get("avg_h", BigDecimal.class)).setScale(2, RoundingMode.HALF_UP),
        caseCount == 0 ? null : nz(caseTime.get("max_h", BigDecimal.class)).setScale(2, RoundingMode.HALF_UP),
        caseCount,
        rmaCount == 0 ? null : nz(rmaTime.get("avg_h", BigDecimal.class)).setScale(2, RoundingMode.HALF_UP),
        rmaCount,
        receiptCount == 0 ? null : nz(receiptTime.get("avg_h", BigDecimal.class)).setScale(2, RoundingMode.HALF_UP),
        receiptCount,
        refundCount == 0 ? null : nz(refundTime.get("avg_h", BigDecimal.class)).setScale(2, RoundingMode.HALF_UP),
        refundCount,
        PROCESSING_NOTE);
  }

  /**
   * 时长聚合的公共骨架。
   *
   * <p>⚠️ 时间列一律用 {@code ?::timestamptz} + {@link OffsetDateTime} 绑定，
   * 且列名由调用方给的是**完整别名**（{@code c.resolved_at}）——
   * 拼接时间谓词时如果只写 {@code resolved_at}，在多表 join 的查询里会指向别的表。
   */
  private Record durationAggregate(
      UUID tenant, ReturnAnalysisWindow window, String sqlBody, String timeColumn) {
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    StringBuilder where = new StringBuilder();
    if (window.from() != null) {
      where.append(" and ").append(timeColumn).append(" >= ?::timestamptz");
      binds.add(startOf(window.from()));
    }
    if (window.to() != null) {
      where.append(" and ").append(timeColumn).append(" < ?::timestamptz");
      binds.add(startOf(window.to().plusDays(1)));
    }
    return sql.fetchOne(sqlBody + where, binds.toArray());
  }

  /** 退货原因分布（七类内联词表）。 */
  private List<ReturnReasonSlice> reasonDistribution(
      UUID tenant, ReturnAnalysisWindow window, Map<String, BasisRow> bases) {
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    StringBuilder where = new StringBuilder(" and c.tenant_id = ?::uuid and c.status <> 'REJECTED'");
    if (window.from() != null) {
      where.append(" and c.received_at >= ?::timestamptz");
      binds.add(startOf(window.from()));
    }
    if (window.to() != null) {
      where.append(" and c.received_at < ?::timestamptz");
      binds.add(startOf(window.to().plusDays(1)));
    }
    Map<String, Agg> byReason = new LinkedHashMap<>();
    Map<String, Long> byCurrency = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select c.currency, c.reason_code, c.case_type, count(*) as case_count,"
                + " coalesce(sum(c.requested_quantity),0) as return_qty,"
                + " coalesce(sum(c.requested_amount),0) as return_amount"
                + " from aftersales_case c where true" + where + " group by 1,2,3",
            binds.toArray())) {
      String currency = r.get("currency", String.class);
      if (!includeCaseType(currency, r.get("case_type", String.class), bases)) continue;
      String key = currency + SEP + r.get("reason_code", String.class);
      Agg agg = byReason.computeIfAbsent(key, k -> new Agg());
      agg.caseCount += r.get("case_count", Long.class);
      agg.qty = agg.qty.add(r.get("return_qty", BigDecimal.class));
      agg.amount = agg.amount.add(r.get("return_amount", BigDecimal.class));
      byCurrency.merge(currency, r.get("case_count", Long.class), Long::sum);
    }
    List<ReturnReasonSlice> out = new ArrayList<>();
    for (Map.Entry<String, Agg> e : byReason.entrySet()) {
      String[] parts = e.getKey().split(SEP, 2);
      String currency = parts[0];
      String reasonCode = parts[1];
      long total = byCurrency.getOrDefault(currency, 0L);
      out.add(
          new ReturnReasonSlice(
              currency,
              reasonCode,
              REASON_LABELS.getOrDefault(reasonCode, reasonCode),
              e.getValue().caseCount,
              e.getValue().qty,
              e.getValue().amount,
              share(e.getValue().caseCount, total)));
    }
    out.sort(
        (a, b) -> {
          int cmp = a.currency().compareTo(b.currency());
          return cmp != 0 ? cmp : Long.compare(b.caseCount(), a.caseCount());
        });
    return out;
  }

  /** 归因口径的原因分布（当前生效的归因集合）。 */
  private List<ReturnAttributionSlice> attributionDistribution(
      UUID tenant, ReturnAnalysisWindow window, Map<String, BasisRow> bases) {
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    StringBuilder where = new StringBuilder(
        " and s.tenant_id = ?::uuid and s.superseded_at is null and c.status <> 'REJECTED'");
    if (window.from() != null) {
      where.append(" and c.received_at >= ?::timestamptz");
      binds.add(startOf(window.from()));
    }
    if (window.to() != null) {
      where.append(" and c.received_at < ?::timestamptz");
      binds.add(startOf(window.to().plusDays(1)));
    }
    Map<String, long[]> caseCounts = new LinkedHashMap<>();
    Map<String, long[]> primaryCounts = new LinkedHashMap<>();
    Map<String, BigDecimal> weights = new LinkedHashMap<>();
    Map<String, String[]> labels = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select c.currency, c.case_type, cat.category_code, cat.category_name, cat.root_kind,"
                + " count(distinct s.case_id) as case_count,"
                + " count(distinct s.case_id) filter (where i.is_primary) as primary_count,"
                + " coalesce(sum(i.weight),0) as weight_sum"
                + " from aftersales_reason_attribution_set s"
                + " join aftersales_reason_attribution_item i on i.tenant_id = s.tenant_id"
                + "   and i.set_id = s.id"
                + " join aftersales_reason_category cat on cat.tenant_id = i.tenant_id"
                + "   and cat.id = i.category_id"
                + " join aftersales_case c on c.tenant_id = s.tenant_id and c.id = s.case_id"
                + " where true" + where + " group by 1,2,3,4,5",
            binds.toArray())) {
      String currency = r.get("currency", String.class);
      // ⚠️ 归因口径必须与分子口径**对齐**：分子按口径开关排除了「仅退款」，
      //   归因分布若不跟着排，覆盖率的分母与分子就不是同一批单。
      if (!includeCaseType(currency, r.get("case_type", String.class), bases)) continue;
      String code = r.get("category_code", String.class);
      String key = currency + "|" + code;
      caseCounts.put(key, new long[] {r.get("case_count", Long.class)});
      primaryCounts.put(key, new long[] {r.get("primary_count", Long.class)});
      weights.put(key, r.get("weight_sum", BigDecimal.class));
      labels.put(
          key,
          new String[] {
            r.get("category_code", String.class), r.get("category_name", String.class),
            r.get("root_kind", String.class)
          });
    }
    // 归因覆盖率的分母 = 期内售后单数（按币种）。
    Map<String, CaseTypeAgg> cases = caseAggregates(tenant, window);
    Map<String, Long> windowCases = new LinkedHashMap<>();
    for (CaseTypeAgg agg : cases.values()) {
      if (!includeCaseType(agg.currency(), agg.caseType(), bases)) continue;
      windowCases.merge(agg.currency(), agg.caseCount(), Long::sum);
    }
    List<ReturnAttributionSlice> out = new ArrayList<>();
    for (Map.Entry<String, long[]> e : caseCounts.entrySet()) {
      String[] parts = e.getKey().split(SEP, 2);
      String currency = parts[0];
      long primary = primaryCounts.get(e.getKey())[0];
      long denominator = windowCases.getOrDefault(currency, 0L);
      out.add(
          new ReturnAttributionSlice(
              currency,
              labels.get(e.getKey())[0],
              labels.get(e.getKey())[1],
              labels.get(e.getKey())[2],
              e.getValue()[0],
              primary,
              weights.get(e.getKey()),
              share(primary, denominator)));
    }
    out.sort(
        (a, b) -> {
          int cmp = a.currency().compareTo(b.currency());
          return cmp != 0 ? cmp : Long.compare(b.primaryCaseCount(), a.primaryCaseCount());
        });
    return out;
  }

  /** 已归因的售后单数（覆盖率的分母侧）。 */
  private long attributedCaseCount(
      UUID tenant, ReturnAnalysisWindow window, Map<String, BasisRow> bases) {
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    StringBuilder where = new StringBuilder(
        " and s.tenant_id = ?::uuid and s.superseded_at is null and c.status <> 'REJECTED'");
    if (window.from() != null) {
      where.append(" and c.received_at >= ?::timestamptz");
      binds.add(startOf(window.from()));
    }
    if (window.to() != null) {
      where.append(" and c.received_at < ?::timestamptz");
      binds.add(startOf(window.to().plusDays(1)));
    }
    long count = 0;
    for (Record r :
        sql.fetch(
            "select c.currency, c.case_type, count(distinct s.case_id) as cnt"
                + " from aftersales_reason_attribution_set s"
                + " join aftersales_case c on c.tenant_id = s.tenant_id and c.id = s.case_id"
                + " where true" + where + " group by 1,2",
            binds.toArray())) {
      String currency = r.get("currency", String.class);
      if (!includeCaseType(currency, r.get("case_type", String.class), bases)) continue;
      count += r.get("cnt", Long.class);
    }
    return count;
  }

  /** 行级金额覆盖率：有行级金额的售后单数 ÷ 期内售后单数。 */
  private BigDecimal lineAmountCoverage(
      UUID tenant, ReturnAnalysisWindow window, Map<String, BasisRow> bases) {
    Map<String, CaseTypeAgg> cases = caseAggregates(tenant, window);
    long total = 0;
    for (CaseTypeAgg agg : cases.values()) {
      if (!includeCaseType(agg.currency(), agg.caseType(), bases)) continue;
      total += agg.caseCount();
    }
    if (total == 0) return null;
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    StringBuilder where = new StringBuilder(
        " and c.tenant_id = ?::uuid and c.status <> 'REJECTED'");
    if (window.from() != null) {
      where.append(" and c.received_at >= ?::timestamptz");
      binds.add(startOf(window.from()));
    }
    if (window.to() != null) {
      where.append(" and c.received_at < ?::timestamptz");
      binds.add(startOf(window.to().plusDays(1)));
    }
    Record r =
        sql.fetchOne(
            "select count(distinct c.id) as cnt from aftersales_case c where true" + where
                + " and exists (select 1 from aftersales_case_line cl"
                + "   where cl.tenant_id = c.tenant_id and cl.case_id = c.id"
                + "     and cl.requested_amount is not null)",
            binds.toArray());
    return share(r.get("cnt", Long.class), total);
  }

  // ================================================================ 维度下钻的具体实现

  /** 店铺下钻（单头口径）。 */
  private List<ReturnBreakdownRow> shopBreakdown(
      UUID tenant, ReturnAnalysisWindow window, Map<String, BasisRow> bases, String currencyFilter) {
    Map<String, Agg> cases = new LinkedHashMap<>();
    Map<String, String> labels = new LinkedHashMap<>();

    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    StringBuilder where = new StringBuilder(" and c.tenant_id = ?::uuid and c.status <> 'REJECTED'");
    if (window.from() != null) {
      where.append(" and c.received_at >= ?::timestamptz");
      binds.add(startOf(window.from()));
    }
    if (window.to() != null) {
      where.append(" and c.received_at < ?::timestamptz");
      binds.add(startOf(window.to().plusDays(1)));
    }
    for (Record r :
        sql.fetch(
            "select coalesce(p.platform_code,'') as platform_code,"
                + " coalesce(p.shop_code,'') as shop_code, c.currency, c.case_type,"
                + " count(*) as case_count, coalesce(sum(c.requested_quantity),0) as return_qty,"
                + " coalesce(sum(c.requested_amount),0) as return_amount"
                + " from aftersales_case c"
                + " join sales_order o on o.tenant_id = c.tenant_id and o.id = c.order_id"
                + " left join platform_order p on p.tenant_id = o.tenant_id"
                + "   and p.sales_order_id = o.id"
                + " where true" + where + " group by 1,2,3,4",
            binds.toArray())) {
      String currency = r.get("currency", String.class);
      if (currencyFilter != null && !currencyFilter.equals(currency)) continue;
      if (!includeCaseType(currency, r.get("case_type", String.class), bases)) continue;
      String platform = r.get("platform_code", String.class);
      String shop = r.get("shop_code", String.class);
      String key = platform + SEP + shop + SEP + currency;
      labels.putIfAbsent(key, shopLabel(platform, shop));
      Agg agg = cases.computeIfAbsent(key, k -> new Agg());
      agg.caseCount += r.get("case_count", Long.class);
      agg.qty = agg.qty.add(r.get("return_qty", BigDecimal.class));
      agg.amount = agg.amount.add(r.get("return_amount", BigDecimal.class));
    }

    Map<String, Agg> sales = new LinkedHashMap<>();
    List<Object> salesBinds = new ArrayList<>();
    salesBinds.add(tenant);
    StringBuilder salesWhere =
        new StringBuilder(" and o.tenant_id = ?::uuid and o.status = 'SUBMITTED'");
    if (window.from() != null) {
      salesWhere.append(" and o.submitted_at >= ?::timestamptz");
      salesBinds.add(startOf(window.from()));
    }
    if (window.to() != null) {
      salesWhere.append(" and o.submitted_at < ?::timestamptz");
      salesBinds.add(startOf(window.to().plusDays(1)));
    }
    for (Record r :
        sql.fetch(
            "select coalesce(p.platform_code,'') as platform_code,"
                + " coalesce(p.shop_code,'') as shop_code, o.currency, count(*) as order_count,"
                + " coalesce(sum(o.grand_total_amount),0) as sales_amount"
                + " from sales_order o"
                + " left join platform_order p on p.tenant_id = o.tenant_id"
                + "   and p.sales_order_id = o.id"
                + " where true" + salesWhere + " group by 1,2,3",
            salesBinds.toArray())) {
      String currency = r.get("currency", String.class);
      if (currencyFilter != null && !currencyFilter.equals(currency)) continue;
      String key =
          r.get("platform_code", String.class)
              + SEP
              + r.get("shop_code", String.class)
              + SEP
              + currency;
      Agg agg = sales.computeIfAbsent(key, k -> new Agg());
      agg.orderCount += r.get("order_count", Long.class);
      agg.salesAmount = agg.salesAmount.add(r.get("sales_amount", BigDecimal.class));
    }

    List<String> keys = new ArrayList<>();
    for (String k : cases.keySet()) keys.add(k);
    for (String k : sales.keySet()) if (!keys.contains(k)) keys.add(k);
    List<ReturnBreakdownRow> rows = new ArrayList<>();
    for (String key : keys) {
      String[] parts = key.split(SEP, 3);
      String currency = parts[2];
      Agg c = cases.get(key);
      Agg s = sales.get(key);
      long caseCount = c == null ? 0L : c.caseCount;
      BigDecimal qty = c == null ? BigDecimal.ZERO : c.qty;
      BigDecimal amount = c == null ? BigDecimal.ZERO : c.amount;
      long orderCount = s == null ? 0L : s.orderCount;
      BigDecimal salesAmount = s == null ? BigDecimal.ZERO : s.salesAmount;
      rows.add(
          new ReturnBreakdownRow(
              ReturnDimension.SHOP.name(),
              parts[0] + "/" + parts[1],
              labels.getOrDefault(key, shopLabel(parts[0], parts[1])),
              currency,
              ReturnCaliber.CASE_LEVEL.name(),
              caseCount,
              qty,
              amount,
              orderCount,
              salesAmount,
              divide(amount, salesAmount, RATE_SCALE),
              null,
              null,
              0L,
              shopNote(parts[0], parts[1])));
    }
    rows.sort(
        (a, b) -> {
          int cmp = a.currency().compareTo(b.currency());
          return cmp != 0 ? cmp : b.returnAmount().compareTo(a.returnAmount());
        });
    return rows;
  }

  /** SKU 下钻（行级口径）。 */
  private List<ReturnBreakdownRow> skuBreakdown(
      UUID tenant, ReturnAnalysisWindow window, Map<String, BasisRow> bases, String currencyFilter) {
    return skuLikeBreakdown(tenant, window, bases, currencyFilter, false);
  }

  /** 类目下钻（行级口径，类目来自 trade 侧的 SKU→类目映射）。 */
  private List<ReturnBreakdownRow> categoryBreakdown(
      UUID tenant, ReturnAnalysisWindow window, Map<String, BasisRow> bases, String currencyFilter) {
    return skuLikeBreakdown(tenant, window, bases, currencyFilter, true);
  }

  /**
   * SKU 与类目共用的实现。
   *
   * <p>⚠️ 两个维度的<b>分母都是行级</b>（sales_order_line.line_amount）：
   * 拿订单总额当每个 SKU 的分母，报出来的是「占比」而不是「退货率」。
   */
  private List<ReturnBreakdownRow> skuLikeBreakdown(
      UUID tenant, ReturnAnalysisWindow window, Map<String, BasisRow> bases, String currencyFilter,
      boolean byCategory) {
    String dim = byCategory ? ReturnDimension.CATEGORY.name() : ReturnDimension.SKU.name();
    String keyExpr =
        byCategory
            ? "coalesce(qc.category_code,'UNMAPPED')"
            : "l.sku_code";
    String labelExpr =
        byCategory
            ? "coalesce(qc.category_name,'未归类')"
            : "max(l.sku_name)";
    String join =
        byCategory
            ? " left join return_quality_category_sku qcs on qcs.tenant_id = l.tenant_id"
                + "   and qcs.sku_id = l.sku_id"
                + " left join return_quality_category qc on qc.tenant_id = qcs.tenant_id"
                + "   and qc.id = qcs.category_id"
            : "";
    // ⚠️ **两个查询的分组键不一样**：case 查询多一个 case_type 维
    //   （同一维度值下 RETURN_REFUND 与 REFUND_ONLY 要分开算，口径开关才作用得到），
    //   sales 查询没有 case_type。写成同一个字符串的话，case 查询会漏掉 case_type
    //   —— PostgreSQL 直接报「column must appear in the GROUP BY clause」。
    String caseGroupBy =
        byCategory
            ? "group by 1,2,3,4"
            : "group by 1,3,4";
    String salesGroupBy =
        byCategory
            ? "group by 1,2,3"
            : "group by 1,3";

    Map<String, Agg> cases = new LinkedHashMap<>();
    Map<String, String> labels = new LinkedHashMap<>();
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    StringBuilder where = new StringBuilder(" and c.tenant_id = ?::uuid and c.status <> 'REJECTED'");
    if (window.from() != null) {
      where.append(" and c.received_at >= ?::timestamptz");
      binds.add(startOf(window.from()));
    }
    if (window.to() != null) {
      where.append(" and c.received_at < ?::timestamptz");
      binds.add(startOf(window.to().plusDays(1)));
    }
    for (Record r :
        sql.fetch(
            "select " + keyExpr + " as dim_key, " + labelExpr + " as dim_label, c.currency, c.case_type,"
                + " count(distinct c.id) as case_count,"
                + " coalesce(sum(cl.quantity),0) as return_qty,"
                + " coalesce(sum(cl.requested_amount),0) as return_amount"
                + " from aftersales_case_line cl"
                + " join aftersales_case c on c.tenant_id = cl.tenant_id and c.id = cl.case_id"
                + " join sales_order_line l on l.tenant_id = cl.tenant_id and l.id = cl.order_line_id"
                + join
                + " where true" + where + " and cl.requested_amount is not null " + caseGroupBy,
            binds.toArray())) {
      String currency = r.get("currency", String.class);
      if (currencyFilter != null && !currencyFilter.equals(currency)) continue;
      if (!includeCaseType(currency, r.get("case_type", String.class), bases)) continue;
      String key = r.get("dim_key", String.class) + SEP + currency;
      labels.putIfAbsent(key, r.get("dim_label", String.class));
      Agg agg = cases.computeIfAbsent(key, k -> new Agg());
      agg.caseCount += r.get("case_count", Long.class);
      agg.qty = agg.qty.add(r.get("return_qty", BigDecimal.class));
      agg.amount = agg.amount.add(r.get("return_amount", BigDecimal.class));
    }

    Map<String, Agg> sales = new LinkedHashMap<>();
    List<Object> salesBinds = new ArrayList<>();
    salesBinds.add(tenant);
    StringBuilder salesWhere =
        new StringBuilder(" and o.tenant_id = ?::uuid and o.status = 'SUBMITTED'");
    if (window.from() != null) {
      salesWhere.append(" and o.submitted_at >= ?::timestamptz");
      salesBinds.add(startOf(window.from()));
    }
    if (window.to() != null) {
      salesWhere.append(" and o.submitted_at < ?::timestamptz");
      salesBinds.add(startOf(window.to().plusDays(1)));
    }
    for (Record r :
        sql.fetch(
            "select " + keyExpr + " as dim_key, " + labelExpr + " as dim_label, o.currency,"
                + " count(distinct o.id) as order_count, coalesce(sum(l.quantity),0) as sales_qty,"
                + " coalesce(sum(l.line_amount),0) as sales_amount"
                + " from sales_order_line l"
                + " join sales_order o on o.tenant_id = l.tenant_id and o.id = l.order_id"
                + join
                + " where true" + salesWhere + salesGroupBy,
            salesBinds.toArray())) {
      String currency = r.get("currency", String.class);
      if (currencyFilter != null && !currencyFilter.equals(currency)) continue;
      String key = r.get("dim_key", String.class) + SEP + currency;
      labels.putIfAbsent(key, r.get("dim_label", String.class));
      Agg agg = sales.computeIfAbsent(key, k -> new Agg());
      agg.orderCount += r.get("order_count", Long.class);
      agg.salesQty = agg.salesQty.add(r.get("sales_qty", BigDecimal.class));
      agg.salesAmount = agg.salesAmount.add(r.get("sales_amount", BigDecimal.class));
    }

    List<String> keys = new ArrayList<>();
    for (String k : cases.keySet()) keys.add(k);
    for (String k : sales.keySet()) if (!keys.contains(k)) keys.add(k);
    List<ReturnBreakdownRow> rows = new ArrayList<>();
    for (String key : keys) {
      String[] parts = key.split(SEP, 2);
      String currency = parts[1];
      Agg c = cases.get(key);
      Agg s = sales.get(key);
      long caseCount = c == null ? 0L : c.caseCount;
      BigDecimal qty = c == null ? BigDecimal.ZERO : c.qty;
      BigDecimal amount = c == null ? BigDecimal.ZERO : c.amount;
      long orderCount = s == null ? 0L : s.orderCount;
      BigDecimal salesQty = s == null ? BigDecimal.ZERO : s.salesQty;
      BigDecimal salesAmount = s == null ? BigDecimal.ZERO : s.salesAmount;
      rows.add(
          new ReturnBreakdownRow(
              dim,
              parts[0],
              labels.getOrDefault(key, parts[0]),
              currency,
              ReturnCaliber.LINE_LEVEL.name(),
              caseCount,
              qty,
              amount,
              orderCount,
              salesAmount,
              divide(amount, salesAmount, RATE_SCALE),
              null,
              salesQty,
              0L,
              byCategory
                  ? "类目取自 trade 侧的 return_quality_category_sku（SKU→类目映射）；"
                      + "**没归类的 SKU 落在「未归类」桶里**，不静默消失。"
                      + "catalog 的正式商品类目在独立库，禁止跨库 join。"
                  : "SKU 取自 sales_order_line 的**下单快照**（sku_code / sku_name）；"
                      + "商品改名后历史报表仍显示下单当时的编码。"));
    }
    rows.sort(
        (a, b) -> {
          int cmp = a.currency().compareTo(b.currency());
          return cmp != 0 ? cmp : b.returnAmount().compareTo(a.returnAmount());
        });
    return rows;
  }

  /** 原因下钻（单头口径，分母是全量同期销售额）。 */
  private List<ReturnBreakdownRow> reasonBreakdown(
      UUID tenant, ReturnAnalysisWindow window, Map<String, BasisRow> bases, String currencyFilter) {
    Map<String, SalesAgg> sales = salesAggregates(tenant, window);
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    StringBuilder where = new StringBuilder(" and c.tenant_id = ?::uuid and c.status <> 'REJECTED'");
    if (window.from() != null) {
      where.append(" and c.received_at >= ?::timestamptz");
      binds.add(startOf(window.from()));
    }
    if (window.to() != null) {
      where.append(" and c.received_at < ?::timestamptz");
      binds.add(startOf(window.to().plusDays(1)));
    }
    Map<String, Agg> cases = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select c.currency, c.reason_code, c.case_type, count(*) as case_count,"
                + " coalesce(sum(c.requested_quantity),0) as return_qty,"
                + " coalesce(sum(c.requested_amount),0) as return_amount"
                + " from aftersales_case c where true" + where + " group by 1,2,3",
            binds.toArray())) {
      String currency = r.get("currency", String.class);
      if (currencyFilter != null && !currencyFilter.equals(currency)) continue;
      if (!includeCaseType(currency, r.get("case_type", String.class), bases)) continue;
      String key = currency + SEP + r.get("reason_code", String.class);
      Agg agg = cases.computeIfAbsent(key, k -> new Agg());
      agg.caseCount += r.get("case_count", Long.class);
      agg.qty = agg.qty.add(r.get("return_qty", BigDecimal.class));
      agg.amount = agg.amount.add(r.get("return_amount", BigDecimal.class));
    }
    List<ReturnBreakdownRow> rows = new ArrayList<>();
    for (Map.Entry<String, Agg> e : cases.entrySet()) {
      String[] parts = e.getKey().split(SEP, 2);
      String currency = parts[0];
      String reasonCode = parts[1];
      SalesAgg s = sales.get(currency);
      BigDecimal salesAmount = s == null ? BigDecimal.ZERO : s.salesAmount();
      long orderCount = s == null ? 0L : s.orderCount();
      rows.add(
          new ReturnBreakdownRow(
              ReturnDimension.REASON.name(),
              reasonCode,
              REASON_LABELS.getOrDefault(reasonCode, reasonCode),
              currency,
              ReturnCaliber.CASE_LEVEL.name(),
              e.getValue().caseCount,
              e.getValue().qty,
              e.getValue().amount,
              orderCount,
              salesAmount,
              divide(e.getValue().amount, salesAmount, RATE_SCALE),
              null,
              null,
              0L,
              "原因维度的分母是**全量同期销售额**：这个率读作「这个原因占销售额的多少」，"
                  + "不是「这个原因内部退了多少」。"));
    }
    rows.sort(
        (a, b) -> {
          int cmp = a.currency().compareTo(b.currency());
          return cmp != 0 ? cmp : b.returnAmount().compareTo(a.returnAmount());
        });
    return rows;
  }

  /** 批次下钻（件数口径）。 */
  private List<ReturnBreakdownRow> batchBreakdown(
      UUID tenant, ReturnAnalysisWindow window, String currencyFilter) {
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    StringBuilder where = new StringBuilder(
        " and rl.tenant_id = ?::uuid and btrim(rl.batch_no) <> '' and rr.receipt_status = 'POSTED'");
    if (window.from() != null) {
      where.append(" and rr.received_at >= ?::timestamptz");
      binds.add(startOf(window.from()));
    }
    if (window.to() != null) {
      where.append(" and rr.received_at < ?::timestamptz");
      binds.add(startOf(window.to().plusDays(1)));
    }
    String joins =
        " from return_receipt_line rl"
            + " join return_receipt rr on rr.tenant_id = rl.tenant_id and rr.id = rl.receipt_id"
            + " join rma_authorization_line ral on ral.tenant_id = rl.tenant_id"
            + "   and ral.id = rl.rma_authorization_line_id"
            + " join aftersales_case_line cl on cl.tenant_id = ral.tenant_id and cl.id = ral.case_line_id"
            + " join aftersales_case c on c.tenant_id = cl.tenant_id and c.id = cl.case_id"
            + " join sales_order_line l on l.tenant_id = cl.tenant_id and l.id = cl.order_line_id";

    Map<String, Agg> batches = new LinkedHashMap<>();
    Map<String, Map<UUID, BigDecimal>> batchSkus = new LinkedHashMap<>();
    // ⚠️ **两个查询**：实收件数按 (批次, SKU) 聚合（一个批次横跨多个 SKU 时要加总），
    //   关联单数按 (批次) 聚合（一张售后单在同一批次里只能算一次，
    //   把它按 SKU 拆开再加总会把同一张单数两遍）。
    for (Record r :
        sql.fetch(
            "select rl.batch_no, c.currency, l.sku_id, sum(rl.received_qty) as received_qty"
                + joins + " where true" + where + " group by 1,2,3",
            binds.toArray())) {
      String currency = r.get("currency", String.class);
      if (currencyFilter != null && !currencyFilter.equals(currency)) continue;
      String batchNo = r.get("batch_no", String.class);
      String key = batchNo + SEP + currency;
      Agg agg = batches.computeIfAbsent(key, k -> new Agg());
      agg.qty = agg.qty.add(r.get("received_qty", BigDecimal.class));
      batchSkus.computeIfAbsent(key, k -> new LinkedHashMap<>());
      batchSkus.get(key).put(r.get("sku_id", UUID.class), BigDecimal.ONE);
    }
    for (Record r :
        sql.fetch(
            "select rl.batch_no, c.currency, count(distinct c.id) as case_count"
                + joins + " where true" + where + " group by 1,2",
            binds.toArray())) {
      String currency = r.get("currency", String.class);
      if (currencyFilter != null && !currencyFilter.equals(currency)) continue;
      String key = r.get("batch_no", String.class) + SEP + currency;
      Agg agg = batches.computeIfAbsent(key, k -> new Agg());
      agg.caseCount += r.get("case_count", Long.class);
    }

    // 同窗口的 SKU 销量件数（批次的分母只能取到 SKU 粒度，见 BATCH_NOTE）。
    Map<UUID, BigDecimal> salesQtyBySku = new LinkedHashMap<>();
    List<Object> salesBinds = new ArrayList<>();
    salesBinds.add(tenant);
    StringBuilder salesWhere =
        new StringBuilder(" and o.tenant_id = ?::uuid and o.status = 'SUBMITTED'");
    if (window.from() != null) {
      salesWhere.append(" and o.submitted_at >= ?::timestamptz");
      salesBinds.add(startOf(window.from()));
    }
    if (window.to() != null) {
      salesWhere.append(" and o.submitted_at < ?::timestamptz");
      salesBinds.add(startOf(window.to().plusDays(1)));
    }
    for (Record r :
        sql.fetch(
            "select l.sku_id, coalesce(sum(l.quantity),0) as sales_qty"
                + " from sales_order_line l"
                + " join sales_order o on o.tenant_id = l.tenant_id and o.id = l.order_id"
                + " where true" + salesWhere + " group by 1",
            salesBinds.toArray())) {
      salesQtyBySku.put(r.get("sku_id", UUID.class), r.get("sales_qty", BigDecimal.class));
    }

    List<ReturnBreakdownRow> rows = new ArrayList<>();
    for (Map.Entry<String, Agg> e : batches.entrySet()) {
      String[] parts = e.getKey().split(SEP, 2);
      String currency = parts[1];
      BigDecimal receivedQty = e.getValue().qty;
      BigDecimal salesQty = BigDecimal.ZERO;
      for (UUID sku : batchSkus.getOrDefault(e.getKey(), Map.of()).keySet()) {
        salesQty = salesQty.add(salesQtyBySku.getOrDefault(sku, BigDecimal.ZERO));
      }
      rows.add(
          new ReturnBreakdownRow(
              ReturnDimension.BATCH.name(),
              parts[0],
              parts[0],
              currency,
              ReturnCaliber.QTY_LEVEL.name(),
              e.getValue().caseCount,
              receivedQty,
              null,
              0L,
              null,
              divide(receivedQty, salesQty, RATE_SCALE),
              receivedQty,
              salesQty,
              batchSkus.getOrDefault(e.getKey(), Map.of()).size(),
              BATCH_NOTE));
    }
    rows.sort(
        (a, b) -> {
          int cmp = a.currency().compareTo(b.currency());
          return cmp != 0 ? cmp : b.receivedQty().compareTo(a.receivedQty());
        });
    return rows;
  }

  // ================================================================ 口径与阈值

  private record BasisRow(
      String basisNo,
      String basisName,
      String currency,
      boolean includeRefundOnly,
      BigDecimal rateMultiplier,
      BigDecimal rateMinSalesShare,
      BigDecimal rejectionGapPp,
      long rejectionMinCases,
      BigDecimal batchMultiplier,
      BigDecimal batchMinQty,
      BigDecimal reasonShare,
      long reasonMinCases,
      BigDecimal trendGapPp,
      LocalDate effectiveFrom) {}

  private record Thresholds(
      String currency,
      String source,
      BigDecimal rateMultiplier,
      BigDecimal rateMinSalesShare,
      BigDecimal rejectionGapPp,
      long rejectionMinCases,
      BigDecimal batchMultiplier,
      BigDecimal batchMinQty,
      BigDecimal reasonShare,
      long reasonMinCases,
      BigDecimal trendGapPp) {}

  /** 当前生效的口径（每个币种一份）。 */
  private Map<String, BasisRow> activeBases(UUID tenant) {
    Map<String, BasisRow> out = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select basis_no, basis_name, currency, include_refund_only, rate_alert_multiplier,"
                + " rate_alert_min_sales_share, rejection_alert_gap_pp, rejection_alert_min_cases,"
                + " batch_alert_multiplier, batch_alert_min_qty, reason_alert_share,"
                + " reason_alert_min_cases, trend_alert_gap_pp, effective_from"
                + " from return_analysis_basis where tenant_id = ?::uuid and basis_status = 'ACTIVE'",
            tenant)) {
      out.put(
          r.get("currency", String.class),
          new BasisRow(
              r.get("basis_no", String.class),
              r.get("basis_name", String.class),
              r.get("currency", String.class),
              r.get("include_refund_only", Boolean.class),
              r.get("rate_alert_multiplier", BigDecimal.class),
              r.get("rate_alert_min_sales_share", BigDecimal.class),
              r.get("rejection_alert_gap_pp", BigDecimal.class),
              r.get("rejection_alert_min_cases", Long.class),
              r.get("batch_alert_multiplier", BigDecimal.class),
              r.get("batch_alert_min_qty", BigDecimal.class),
              r.get("reason_alert_share", BigDecimal.class),
              r.get("reason_alert_min_cases", Long.class),
              r.get("trend_alert_gap_pp", BigDecimal.class),
              r.get("effective_from", LocalDate.class)));
    }
    return out;
  }

  private Map<String, Thresholds> thresholdsByCurrency(Map<String, BasisRow> bases) {
    Map<String, Thresholds> out = new LinkedHashMap<>();
    for (BasisRow b : bases.values()) {
      out.put(
          b.currency(),
          new Thresholds(
              b.currency(),
              CaliberSource.TENANT_BASIS.name(),
              b.rateMultiplier(),
              b.rateMinSalesShare(),
              b.rejectionGapPp(),
              b.rejectionMinCases(),
              b.batchMultiplier(),
              b.batchMinQty(),
              b.reasonShare(),
              b.reasonMinCases(),
              b.trendGapPp()));
    }
    return out;
  }

  private Thresholds defaultThresholds(String currency) {
    return new Thresholds(
        currency,
        CaliberSource.DEFAULT.name(),
        DEFAULT_RATE_MULTIPLIER,
        DEFAULT_RATE_MIN_SALES_SHARE,
        DEFAULT_REJECTION_GAP_PP,
        DEFAULT_REJECTION_MIN_CASES,
        DEFAULT_BATCH_MULTIPLIER,
        DEFAULT_BATCH_MIN_QTY,
        DEFAULT_REASON_SHARE,
        DEFAULT_REASON_MIN_CASES,
        DEFAULT_TREND_GAP_PP);
  }

  /** 期内出现的币种（售后单 + 已提交订单的并集）。 */
  private List<String> currenciesOf(
      UUID tenant, ReturnAnalysisWindow window, Map<String, BasisRow> bases) {
    List<String> out = new ArrayList<>();
    for (CaseTypeAgg agg : caseAggregates(tenant, window).values()) {
      if (!includeCaseType(agg.currency(), agg.caseType(), bases)) continue;
      if (!out.contains(agg.currency())) out.add(agg.currency());
    }
    for (String c : salesAggregates(tenant, window).keySet()) {
      if (!out.contains(c)) out.add(c);
    }
    out.sort(String::compareTo);
    return out;
  }

  private boolean includeCaseType(
      String currency, String caseType, Map<String, BasisRow> bases) {
    if ("RETURN_REFUND".equals(caseType)) return true;
    if ("REFUND_ONLY".equals(caseType)) {
      BasisRow b = bases.get(currency);
      return b != null && b.includeRefundOnly();
    }
    return false;
  }

  /** 单头口径的退货金额（按币种）。 */
  private BigDecimal caseLevelAmount(
      UUID tenant, ReturnAnalysisWindow window, Map<String, BasisRow> bases, String currency) {
    BigDecimal total = BigDecimal.ZERO;
    for (CaseTypeAgg agg : caseAggregates(tenant, window).values()) {
      if (!agg.currency().equals(currency)) continue;
      if (!includeCaseType(agg.currency(), agg.caseType(), bases)) continue;
      total = total.add(agg.returnAmount());
    }
    return total;
  }

  /** 单头口径的销售额（按币种）。 */
  private BigDecimal caseLevelSales(UUID tenant, ReturnAnalysisWindow window, String currency) {
    SalesAgg s = salesAggregates(tenant, window).get(currency);
    return s == null ? BigDecimal.ZERO : s.salesAmount();
  }

  // ================================================================ 小工具

  private UUID currentTenant() {
    UUID tenant =
        sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
            .get(0, UUID.class);
    if (tenant == null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "租户上下文缺失，拒绝读取退货数据");
    }
    return tenant;
  }

  /** UTC 日界。 */
  private static OffsetDateTime startOf(LocalDate date) {
    return date.atStartOfDay().atOffset(ZoneOffset.UTC);
  }

  /** 显式比值：分母为 0 时返回 null（没有销售额就没有率），标度 4。 */
  private static BigDecimal divide(BigDecimal part, BigDecimal whole, int scale) {
    if (part == null || whole == null || whole.signum() == 0) return null;
    return part.divide(whole, scale, RoundingMode.HALF_UP);
  }

  /** 占比（百分比，两位小数）。 */
  private static BigDecimal share(long part, long whole) {
    if (whole <= 0) return BigDecimal.ZERO.setScale(SHARE_SCALE, RoundingMode.HALF_UP);
    return BigDecimal.valueOf(part)
        .multiply(new BigDecimal("100"))
        .divide(BigDecimal.valueOf(whole), SHARE_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static String plain(BigDecimal value) {
    BigDecimal v = nz(value);
    return v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString();
  }

  private static int clamp(Integer limit, int max) {
    if (limit == null || limit <= 0) return max;
    return Math.min(limit, max);
  }

  private static String caliberLabel(String caliber) {
    return switch (caliber) {
      case "CASE_LEVEL" -> "单头口径（售后单申请金额 ÷ 已提交订单总额）";
      case "LINE_LEVEL" -> "行级口径（售后商品行申请金额 ÷ 同期订单行金额）";
      case "QTY_LEVEL" -> "件数口径（实收件数 ÷ 关联 SKU 同期销量件数）";
      default -> caliber;
    };
  }

  private static String shopLabel(String platformCode, String shopCode) {
    if (shopCode == null || shopCode.isBlank()) return "未关联平台单";
    if (platformCode == null || platformCode.isBlank()) return shopCode;
    return platformCode + " / " + shopCode;
  }

  private static String shopNote(String platformCode, String shopCode) {
    if (shopCode == null || shopCode.isBlank()) {
      return "这张订单**还没有平台单投影**（platform_order 里没有对应行），"
          + "所以归不到任何店铺 —— 归到「未关联平台单」桶里，不静默消失。";
    }
    return "店铺取自 platform_order 的 shop_code（trade 库里的平台单投影）；"
        + "卷 2 的店铺主数据在 channel 独立库，**不跨库 join**。";
  }

  private String currencyNote(
      String currency, long caseCount, BigDecimal salesAmount, boolean includeRefundOnly) {
    StringBuilder s = new StringBuilder();
    s.append("币种 ").append(currency).append("：");
    s.append(caseCount == 0
        ? "期内**没有退货售后单**。"
        : "期内 ").append(caseCount).append(" 张退货售后单。");
    s.append(salesAmount.signum() == 0
        ? "同期**没有已提交订单** ⇒ 退货率算不出来（null），不是 0%。"
        : "同期已提交订单总额 " + plain(salesAmount) + "。");
    if (includeRefundOnly) {
      s.append("⚠️ 本币种的口径**含「仅退款」类售后单**（买家不退货只退钱），"
          + "报出来的退货率里混着没发生退货的钱。");
    }
    return s.toString();
  }

  private static String require(String value, String label, int max) {
    if (value == null || value.trim().isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "必填");
    }
    String v = value.trim();
    if (v.length() > max) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, label + "长度不能超过 " + max + " 个字符");
    }
    return v;
  }

  private static String optional(String value, int max, String label) {
    if (value == null) return "";
    String v = value.trim();
    if (v.length() > max) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, label + "长度不能超过 " + max + " 个字符");
    }
    return v;
  }

  private static BigDecimal positive(BigDecimal value, String label) {
    if (value == null || value.signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "必须大于 0");
    }
    return value;
  }

  private static BigDecimal nonNegative(BigDecimal value, String label) {
    if (value == null || value.signum() < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能小于 0");
    }
    return value;
  }

  private static BigDecimal share(BigDecimal value, String label) {
    if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "必须在 0 到 1 之间");
    }
    return value;
  }

  private static long nonNegative(Long value, String label) {
    if (value == null || value < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能小于 0");
    }
    return value;
  }

  private static ReturnDimension dimensionOrThrow(String value) {
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "维度必填（SHOP / SKU / CATEGORY / REASON / BATCH）");
    }
    try {
      return ReturnDimension.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "维度只能是 SHOP / SKU / CATEGORY / REASON / BATCH（收到 \"" + value.trim() + "\"）");
    }
  }

  private static String granularityOrThrow(String value) {
    String v = value == null || value.isBlank() ? "DAY" : value.trim().toUpperCase(Locale.ROOT);
    if (!List.of("DAY", "WEEK", "MONTH").contains(v)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "粒度只能是 DAY / WEEK / MONTH（收到 \"" + v + "\"）");
    }
    return v;
  }

  /** 单号：序列发号 + 模块前缀。 */
  private String nextNo(String sequence, String prefix) {
    Long seq = sql.fetchOne("select nextval('" + sequence + "') as n").get("n", Long.class);
    return prefix + "-" + String.format("%012d", seq == null ? 0L : seq);
  }
}
