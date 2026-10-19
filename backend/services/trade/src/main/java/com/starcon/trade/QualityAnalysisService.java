package com.starcon.trade;

import static com.starcon.trade.QualityAnalysisModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import com.starcon.trade.ProductIssueAttributionModels.AttributionStage;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 11.5.2.1 质量分析。
 *
 * <p><b>本类是 V185 四张新表的写入所有者</b>（quality_defect_observation /
 * quality_cost_parameter / quality_supplier_ref / quality_analysis_event），
 * 对 V179 的 return_quality_* 簇、V180 的 product_issue_* 簇、
 * V172 的 return_receipt_line <b>只读</b>。刻意不加外键、不 ALTER 既有表 ⇒
 * <b>跨车道 DDL 回归面 = 0</b>（同批另有三条车道也在往 trade 落迁移）。
 *
 * <p><b>输入句的两项上游都在本服务</b>（11.2.3.1 质检结论 = V179、11.1.2.2 归因 = V180），
 * 所以本模块是<b>同库只读聚合</b>，不碰任何跨服务路径 ——
 * 这也正是它能落在 trade 而不是分析服务的原因。
 *
 * <p><b>六条硬纪律</b>：
 *
 * <ol>
 *   <li><b>每一条查询都显式带 {@code tenant_id} 谓词</b>，不依赖 RLS 当唯一防线。
 *       质量报表是拿去跟供应商谈索赔的底牌，漏掉租户谓词就是跨租户泄露对手的退货画像。</li>
 *   <li><b>整页共用一个参考时刻</b>：{@code at} 在每个报表方法开头取一次，
 *       窗口、行、汇总三处复用同一个值。否则会「筛了本季度、第一行却是去年的」——
 *       而这种错位在报表里<b>不报错</b>，只会让人对不上账。</li>
 *   <li><b>成本分项逐项标依据等级</b>（实测 / 按参数估算 / 参数未登记）：
 *       只有实测项与估算项可加，未登记项<b>被排除在合计之外</b>。
 *       把「算不出来」当成 0 参与合计，报出来的总成本会偏小，而偏小的质量成本
 *       正好是「改进动力不足」的那个方向。</li>
 *   <li><b>开箱率必须同时报覆盖率</b>：分子分母都在已登记观察范围内，
 *       覆盖率低于 100% 时开箱率只是「已登记那部分」的开箱率。</li>
 *   <li><b>照片聚类是内容指纹口径</b>：sha256 完全相同才算同一簇，
 *       <b>没有「相似度」这个数</b>。本仓无图像库、无模型、无视觉服务接入点，
 *       编一个 0.87 就是在冒充图像识别能力（与 11.1.2.2 同一决定）。</li>
 *   <li><b>时间绑定</b>：时间一律 {@code ?::timestamptz} + {@link OffsetDateTime}。
 *       {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s，
 *       而「发现时刻偏 8 小时」会让开箱率的整个时间序列错位一格。</li>
 * </ol>
 */
@Service
public class QualityAnalysisService {

  /** 列表单页上限。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 报表行数上限（分布 / 对比 / 聚类 / 下钻共用）。 */
  private static final int MAX_REPORT_ROWS = 200;

  /** 观察单号前缀。 */
  private static final String OBSERVATION_PREFIX = "QDO";

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  /** 占比统一保留两位小数；分母为 0 时返回 0 而不是 100（见 {@link #share}）。 */
  private static final int SHARE_SCALE = 2;

  private static final String OPEN_BOX_NOTE =
      "开箱率 = 「发现环节记为**买家开箱即发现**的件数」÷「已登记缺陷观察的件数合计」。"
          + "**签收时发现刻意不计入**：货到买家手上时有没有被拆开验过没有任何证据，"
          + "记在签收环节的件可能是整箱签收后没拆，把它算进来会凭空抬高分子。"
          + "分母**只取已登记观察**而不是全部 FAIL 检项 —— 所以必须同时看**覆盖率**："
          + "覆盖率 100% 时的开箱率才是完整的开箱率；"
          + "覆盖率 40% 时这个数只代表「已登记的那 40%」，剩下的沉默<b>不能</b>被读成「没开箱问题」。";

  private static final String COST_NOTE =
      "质量成本三项的依据等级**不同**，逐项标注、不合并成单一来源的「总成本」："
          + "① **损耗 = 原值 − 残值**（V179 的 original_value / salvage_amount 两个真列）⇒ **实测**；"
          + "② **退货物流**：本仓**任何表都没有买家回寄运费金额**"
          + "（V161 rma_authorization 只有 carrier / tracking_no / shipped_at，V165 rma_label 只有重量长宽高）"
          + "⇒ **按参数估算**（租户自维护单价 × **实收件数**；实收是全部实收，因为买家寄回就发生了运费，与好坏无关）；"
          + "③ **差评影响**：本仓**根本没有商品评价表**（全库无 product_review / rating）"
          + "⇒ **按参数估算**（租户自维护单价 × **已登记缺陷件数**）。"
          + "参数未登记时标 NOT_REGISTERED 并**排除在合计之外** —— 按 0 算出来的总成本会被读成「质量没花钱」。";

  private static final String PHOTO_CLUSTER_NOTE =
      "聚类键是 **content_sha256 内容指纹完全相同**，不是图像相似度。"
          + "本仓没有图像库、没有模型、没有第三方视觉服务接入点，"
          + "所以「同一张图」在本模块的定义只能是「字节完全一样」。"
          + "同一件商品在不同光线角度下拍的两张照片指纹必然不同 —— "
          + "把它们判成同一簇就是在冒充图像识别能力。"
          + "因此本报表的读法是「**哪些照片被反复上传**」（跨归因单复用同一张图），"
          + "而不是「外观相似的缺陷有多少类」。";

  private static final String CROSS_SERVICE_NOTE =
      "输入句的两项上游（11.2.3.1 质检结论、11.1.2.2 归因）**都在 trade 服务**，"
          + "本模块是同库只读聚合，不经任何跨服务调用。"
          + "**供应商主数据在 catalog 独立库**（party_supplier_profile / party_role）——"
          + "AGENTS.md 禁止跨服务 SQL / FK / 共享视图，所以本模块<b>不 join</b> 它，"
          + "改用 trade 侧的 quality_supplier_ref 镜像表"
          + "（沿用 11.1.2.2 处理 platform_enum_mapping 的同一条既有先例）。"
          + "批次真值（inventory_lot）在 warehouse 独立库，"
          + "本模块只用 11.1.2.2 已经写进 trade 的 lot_code / lot_supplier_ref_id 快照列，"
          + "**不读也不写 warehouse 的表**。";

  private final DSLContext sql;

  public QualityAnalysisService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：职责句点名的六项分析逐块报可用性、事实来源与口径，
   * 上下游逐个报 delivered 与归属模块，本模块刻意不做的事逐条列出。
   */
  public QualityAnalysisCapabilities capabilities() {
    return new QualityAnalysisCapabilities(
        List.of(
            "**11.2.3.1 质检与判定**（V179，trade 同库，只读）：return_quality_inspection 的"
                + " verdict / original_value / salvage_amount / batch_no / concluded_at —— "
                + "缺陷类型分布、批次质量趋势、损耗成本的唯一来源",
            "**11.2.3.1 return_quality_check_result**（V179，只读）：item_dimension / item_code / "
                + "check_outcome —— 缺陷类型分布的**事实来源**（判 FAIL 才是缺陷，PASS 不是）",
            "**11.1.2.2 商品问题归因**（V180，trade 同库，只读）：attributed_stage / lot_code / "
                + "lot_supplier_ref_id / improvement_suggestion —— 责任分布、供应商对比、改进清单的来源",
            "**11.1.2.2 product_issue_photo**（V180，只读）：content_sha256 —— 缺陷照片聚类的聚类键",
            "**11.1.2.2 product_issue_lot_flag**（V180，只读）：flag_status —— 批次是否已被标记为质量问题",
            "**11.2.2.1 return_receipt_line**（V172，trade 同库，只读）：received_qty —— "
                + "退货物流成本的件数基数",
            "**11.5.2.1 quality_defect_observation**（V185，本模块写入）：discovery_stage —— "
                + "**开箱率的唯一来源**（上游 V179/V180 都不记「缺陷是什么时候被发现的」）",
            "**11.5.2.1 quality_cost_parameter**（V185，本模块写入）：退货物流与差评影响的单价"),
        List.of(
            section(
                "defect-distribution",
                "缺陷类型分布",
                "return_quality_check_result 中 **check_outcome='FAIL'** 的检项，"
                    + "按 (维度, item_code) 聚合。**分母恒等于 FAIL 检项数** —— "
                    + "PASS 的检项不进分布，「判过」不等于「坏了」。"),
            section(
                "lot-trend",
                "批次质量趋势",
                "已判定质检单按 concluded_at 的时间桶聚合，非可再销售率 = "
                    + "「DEFECTIVE + SCRAPPED 件数」÷「实检件数」。**未限定批次时按批次聚合全部**，"
                    + "并报出被排序限定的批次数 —— 不说清楚「这是前 N 个批次的趋势」，"
                    + "读的人会以为全量。"),
            section(
                "supplier-comparison",
                "供应商质量对比",
                "按 11.1.2.2 已落库的 **lot_supplier_ref_id** 聚合。"
                    + "**名称来自 trade 侧镜像表**，未登记时显示「未登记供应商」而不是把 uuid 当名字。"),
            section(
                "photo-clusters",
                "缺陷照片聚类",
                "按 **content_sha256 完全相同**聚类（内容指纹口径，不是图像相似度）。"),
            section(
                "open-box-rate",
                "开箱率",
                "分子 = 发现环节为「买家开箱即发现」的件数；分母 = **已登记观察的件数**。"
                    + "**必须同时报覆盖率**（已登记观察数 ÷ 期间内 FAIL 检项数）。"),
            section(
                "quality-cost",
                "质量成本估算",
                "三项**依据等级不同**、逐项标注：损耗实测、退货物流与差评影响按参数估算、"
                    + "参数未登记则排除在合计之外。"),
            section(
                "responsibility",
                "责任分布",
                "11.1.2.2 当前生效归因（attribution_status='ATTRIBUTED'）的 attributed_stage 六值分布，"
                    + "按 **involved_qty 件数**而不是单张数占比。"),
            section(
                "improvements",
                "改进清单",
                "11.1.2.2 归因单上的**必填人写** improvement_suggestion，"
                    + "叠加批次质量标记，**带批次标记状态的改进项排前面**。")),
        List.of(
            related(
                "11.2.3.1",
                "质检与判定",
                true,
                null,
                "输入句「质检结论」的来源（V179，trade 同库只读）",
                "/aftersales/return-quality"),
            related(
                "11.1.2.2",
                "商品问题归因",
                true,
                null,
                "输入句「退货原因」的归因侧来源（V180，trade 同库只读）；"
                    + "责任分布、供应商对比、改进清单、照片聚类四项都挂在它上面",
                "/aftersales/attributions"),
            related(
                "11.2.2.1",
                "退货入库",
                true,
                null,
                "退货物流成本的件数基数来源（V172 return_receipt_line.received_qty）",
                "/aftersales/return-receipts"),
            related(
                "6.1.1.3",
                "供应商评级",
                false,
                "6 采购",
                "**未交付** —— 本模块提供对比数据（生产责任占比、被标记批次、改进条数），"
                    + "评级打分与升降档不在这里",
                null),
            related(
                "13",
                "分析",
                false,
                "13 分析",
                "**未交付** —— 本模块是售后侧的质量专项分析，跨系统经营分析与看板不在这里",
                null),
            related(
                "11.5.1.1",
                "退货分析",
                true,
                null,
                "**已交付的邻居**：退货分析管「退了多少」（按销售额同口径算退货率），"
                    + "本模块管「退回来的东西坏在哪」。两者共用同一批退货事实，"
                    + "**但比率口径刻意不同**：11.5.1.1 的退货率对销售额，本模块的质量成本对件数与原值",
                "/aftersales/returns")),
        List.of(
            action(
                "overview",
                "质量总览",
                true,
                "GET",
                "/trade/quality-analysis/overview",
                true,
                null),
            action(
                "defect-distribution",
                "缺陷类型分布",
                true,
                "GET",
                "/trade/quality-analysis/defect-distribution",
                true,
                null),
            action(
                "lot-trend",
                "批次质量趋势",
                true,
                "GET",
                "/trade/quality-analysis/lot-trend",
                true,
                null),
            action(
                "supplier-comparison",
                "供应商质量对比",
                true,
                "GET",
                "/trade/quality-analysis/supplier-comparison",
                true,
                null),
            action(
                "photo-clusters",
                "缺陷照片聚类",
                true,
                "GET",
                "/trade/quality-analysis/photo-clusters",
                true,
                null),
            action(
                "open-box-rate",
                "开箱率",
                true,
                "GET",
                "/trade/quality-analysis/open-box-rate",
                true,
                null),
            action(
                "quality-cost",
                "质量成本估算",
                true,
                "GET",
                "/trade/quality-analysis/quality-cost",
                true,
                null),
            action(
                "improvements",
                "责任分布 + 改进清单",
                true,
                "GET",
                "/trade/quality-analysis/improvements",
                true,
                null),
            action(
                "list-observations",
                "缺陷观察列表",
                true,
                "GET",
                "/trade/quality-analysis/defect-observations",
                true,
                null),
            action(
                "record-observation",
                "登记缺陷观察",
                false,
                "POST",
                "/trade/quality-analysis/defect-observations",
                false,
                "检项必须**判 FAIL** 且质检单**已判定**；影响件数不得超过实检数量；"
                    + "标为「买家开箱」时发现时刻不得晚于仓内判定完成时刻（V185 守卫②④⑤⑥）"),
            action(
                "void-observation",
                "作废缺陷观察",
                false,
                "POST",
                "/trade/quality-analysis/defect-observations/{id}/void",
                false,
                "作废即终态：业务列冻结（V185 守卫①），判错了请另记一条"),
            action(
                "cost-parameters",
                "质量成本参数",
                true,
                "GET",
                "/trade/quality-analysis/cost-parameters",
                true,
                null),
            action(
                "set-cost-parameter",
                "建成本参数",
                false,
                "POST",
                "/trade/quality-analysis/cost-parameters",
                false,
                "每币种同时只能有一份生效参数（部分唯一索引）；换版必须留痕（V185 守卫⑦）"),
            action(
                "supersede-cost-parameter",
                "成本参数换版",
                false,
                "POST",
                "/trade/quality-analysis/cost-parameters/{id}/supersede",
                false,
                "换版即终态：老版本标 SUPERSEDED 并保留，三个月后再看报表能答出「上次是多少」"),
            action(
                "supplier-refs",
                "供应商引用",
                true,
                "GET",
                "/trade/quality-analysis/supplier-refs",
                true,
                null),
            action(
                "upsert-supplier-ref",
                "登记 / 更新供应商引用",
                false,
                "POST",
                "/trade/quality-analysis/supplier-refs",
                false,
                "同步器来源（MODULE_6_PURCHASE_SYNC）必须带 sourceReference；"
                    + "**本模块不提供删除入口**，停用是留痕的、删除不是"),
            action(
                "set-supplier-ref-status",
                "供应商引用启停",
                false,
                "POST",
                "/trade/quality-analysis/supplier-refs/{id}/status",
                false,
                "只允许 ACTIVE / DISABLED 切换")),
        List.of(
            "**不写供应商评级**（6.1.1.3，未交付）—— 本模块只出对比数据",
            "**不发供应商索赔**（6.4.2.2，未交付）—— 举证材料在 11.1.2.2 那边",
            "**不产出「差评」这个事实** —— 本仓全库没有商品评价表，"
                + "差评影响只能按租户参数**估算**并如实标注，不能在发现环节里造一个 NEGATIVE_REVIEW 取值",
            "**不做图像相似度聚类** —— 没有图像库与模型接入点，"
                + "「相似度 0.87」这种数一律不出现在本模块的任何响应里",
            "**不把未登记的供应商参数按 0 算进成本合计** —— 算不出来就标 NOT_REGISTERED"),
        OPEN_BOX_NOTE,
        COST_NOTE,
        PHOTO_CLUSTER_NOTE,
        CROSS_SERVICE_NOTE);
  }

  // ================================================================ 能力声明的小构造

  /** 一块分析。职责句里点名的每一项都要在这里出现一次 —— 少一块就是少做一个承诺。 */
  private QualityAnalysisSection section(String key, String title, String basis) {
    return new QualityAnalysisSection(key, title, true, "11.5.2.1", basis, "11 售后");
  }

  private QualityAnalysisRelatedModule related(
      String moduleId, String title, boolean delivered, String ownerSystem, String relation,
      String path) {
    return new QualityAnalysisRelatedModule(moduleId, title, delivered, ownerSystem, relation, path);
  }

  private QualityAnalysisAction action(String key, String title, boolean available, String method, String path,
      boolean readOnly, String precondition) {
    return new QualityAnalysisAction(key, title, available, method, path, readOnly, precondition, null,
        available ? "trade.aftersales." + (readOnly ? "read" : "write") : null);
  }

  // ================================================================ 总览

  /**
   * 质量总览：一屏看完「退回来的东西坏多少、坏在谁手上、发现了多少钱」。
   *
   * <p>⚠️ <b>整页共用一个参考时刻</b>（{@code at}）：开箱率、成本、责任分布三块
   * 若各取各的 {@code now()}，一次刷新里可能出现「质检单数比开箱率的分母多 1 张」
   * 这种按定义不可能的不自洽 —— 而报表不会报错，只会让读的人怀疑数据。
   */
  public QualityOverview overview(QualityWindow window) {
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.ofHours(8));
    List<Object> binds = windowBinds(window);

    Record base =
        sql.fetchOne(
            "select count(*) as inspections,"
                + " coalesce(sum(i.inspected_qty),0) as inspected,"
                + " coalesce(sum(i.inspected_qty) filter (where i.verdict <> 'RESALABLE'),0)"
                + "   as non_resalable,"
                + " coalesce(sum(i.original_value),0) as original_value"
                + inspectionFrom(window)
                + " group by ()",
            binds.toArray());

    long inspections = base.get("inspections", Long.class);
    BigDecimal inspected = nz(base.get("inspected", BigDecimal.class));
    BigDecimal nonResalable = nz(base.get("non_resalable", BigDecimal.class));

    Record defects =
        sql.fetchOne(
            "select count(*) as fail_checks,"
                + " count(distinct c.inspection_id) as affected_inspections,"
                + " coalesce(sum(i.inspected_qty),0) as affected_qty"
                + failCheckWhere(window)
                + " group by ()",
            binds.toArray());

    OpenBoxReport openBox = openBoxRate(window);
    List<ResponsibilitySlice> responsibility = responsibilityDistribution(null);
    LotFlagCounts lotFlags = lotFlagCounts();

    List<CostParameterRow> params = effectiveCostParameters();
    Map<String, CostCurrencyBreakdown> costs = costBreakdowns(window, params);

    List<CostTotalRow> totals = new ArrayList<>();
    for (Map.Entry<String, CostCurrencyBreakdown> e : costs.entrySet()) {
      CostCurrencyBreakdown b = e.getValue();
      totals.add(
          new CostTotalRow(
              e.getKey(), b.total(), b.excludedItemCount(), weakestBasis(b), weakestBasisLabel(b)));
    }

    return new QualityOverview(
        at,
        window.note(),
        inspections,
        inspected,
        nz(defects.get("affected_qty", BigDecimal.class)),
        nonResalable,
        rate(nonResalable, inspected),
        openBox.openBoxRate(),
        openBox.observationCoverage(),
        responsibility,
        lotFlags,
        totals,
        "总览的所有数字共用同一个参考时刻 "
            + at
            + "；非可再销售率 = （DEFECTIVE + SCRAPPED 件数）÷ 实检件数，"
            + "**分母是件数不是单数** —— 一张单退 10 件与十张单各退 1 件在单数口径下一样是 10，"
            + "在件数口径下前者是「一个单子 10 件」，后者是「十个单子 10 件」，"
            + "这两种情况的严重程度完全不同。");
  }

  // ================================================================ 缺陷类型分布

  /**
   * 缺陷类型分布（职责句第一项）。
   *
   * <p>事实来源是 <b>V179 的 FAIL 检项</b>，不是本模块自己的观察表 ——
   * 「缺陷是什么」由质检结论回答，「缺陷什么时候被发现」才由观察表回答。
   * 两者混成一张表，分布的分母就会随登记进度漂移。
   */
  public DefectDistributionReport defectDistribution(QualityWindow window, Integer limit) {
    int rows = clamp(limit, 50);
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.ofHours(8));
    List<Object> binds = windowBinds(window);

    Record totals =
        sql.fetchOne(
            "select count(*) as inspections,"
                + " coalesce(sum(i.original_value),0) as original_value,"
                + " coalesce(sum(i.original_value) filter (where i.verdict <> 'RESALABLE'),0)"
                + "   as defective_value"
                + inspectionFrom(window)
                + " group by ()",
            binds.toArray());
    long inspectionCount = totals.get("inspections", Long.class);
    BigDecimal originalValue = nz(totals.get("original_value", BigDecimal.class));
    BigDecimal defectiveValue = nz(totals.get("defective_value", BigDecimal.class));

    Record fail =
        sql.fetchOne(
            "select count(*) as fail_checks,"
                + " count(distinct c.inspection_id) as affected_inspections,"
                + " coalesce(sum(i.inspected_qty),0) as affected_qty"
                + failCheckWhere(window)
                + " group by ()",
            binds.toArray());
    long failCheckCount = fail.get("fail_checks", Long.class);

    List<Object> dimBinds = new ArrayList<>(binds);
    List<DefectDimensionSlice> dimensions = new ArrayList<>();
    for (Record r :
        sql.fetch(
                "select c.item_dimension as dimension, count(*) as cnt"
                    + failCheckWhere(window)
                    + " group by c.item_dimension order by count(*) desc, c.item_dimension",
                dimBinds.toArray())) {
      DefectDimension d = dimensionOf(r.get("dimension", String.class));
      dimensions.add(
          new DefectDimensionSlice(d.name(), d.label(), r.get("cnt", Long.class), share(r.get("cnt", Long.class), failCheckCount)));
    }

    List<Object> rowBinds = new ArrayList<>(binds);
    rowBinds.add(rows);
    List<DefectTypeRow> types = new ArrayList<>();
    for (Record r :
        sql.fetch(
                "select c.item_dimension as dimension, c.item_code as item_code,"
                    + " c.item_name as item_name, count(*) as fail_count,"
                    + " count(distinct c.inspection_id) as inspection_count,"
                    + " coalesce(sum(i.inspected_qty),0) as affected_qty"
                    + failCheckWhere(window)
                    + " group by c.item_dimension, c.item_code, c.item_name"
                    + " order by count(*) desc, c.item_dimension, c.item_code limit ?",
                rowBinds.toArray())) {
      DefectDimension d = dimensionOf(r.get("dimension", String.class));
      types.add(
          new DefectTypeRow(
              d.name(),
              d.label(),
              r.get("item_code", String.class),
              r.get("item_name", String.class),
              r.get("fail_count", Long.class),
              r.get("inspection_count", Long.class),
              nz(r.get("affected_qty", BigDecimal.class)),
              share(r.get("fail_count", Long.class), failCheckCount),
              openBoxQtyOfType(r.get("item_code", String.class)),
              topSeverityOfType(r.get("item_code", String.class))));
    }

    return new DefectDistributionReport(
        at,
        window.note(),
        "分布的**分母是 FAIL 检项条数**：判过 != 坏了，PASS 的检项不进来。"
            + "涉及件数按「该检项所在质检单的实检件数」累计 —— "
            + "同一条 FAIL 检项覆盖该单的全部实检件数，所以件数会**大于**缺陷项数，"
            + "这是刻意的：一单里可能只有 1 项不合格，但整单都是问题货。"
            + "占比保留两位小数，分母为 0 时报 0.00 而不是 100.00。",
        new DefectDistributionTotals(
            inspectionCount,
            failCheckCount,
            fail.get("affected_inspections", Long.class),
            nz(fail.get("affected_qty", BigDecimal.class)),
            originalValue,
            share(defectiveValue, originalValue)),
        dimensions,
        types);
  }

  // ================================================================ 批次质量趋势

  /**
   * 批次质量趋势（职责句第二项）。
   *
   * <p>⚠️ {@code granularity} 用 {@code date_trunc} 在 SQL 层分桶，
   * 时区固定 {@code UTC} —— 让数据库按会话时区分桶的话，
   * 同一份数据换个连接池连接就可能落进不同的桶，趋势图会莫名其妙多出一个空桶。
   */
  public LotTrendReport lotTrend(
      QualityWindow window, String granularity, String batchNo, Integer lotLimit) {
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.ofHours(8));
    String bucket = bucketExpression(granularity);
    List<Object> binds = windowBinds(window);

    String lotClause = "";
    if (isSet(batchNo)) {
      lotClause = " and i.batch_no ilike ?";
      binds.add("%" + batchNo.trim() + "%");
    }

    // 总计不带子查询 —— 少一张表就少一个绑值，少一处数错的机会。
    List<Object> totalBinds = new ArrayList<>(binds.subList(0, binds.size() - (isSet(batchNo) ? 1 : 0)));
    Record totals =
        sql.fetchOne(
            "select count(*) as inspections, coalesce(sum(i.inspected_qty),0) as inspected,"
                + " coalesce(sum(i.inspected_qty) filter (where i.verdict <> 'RESALABLE'),0)"
                + "   as non_resalable"
                + inspectionFrom(window, lotClause)
                + " group by ()",
            totalBinds.toArray());

    long lotCount =
        sql.fetchOne(
                "select count(distinct nullif(btrim(i.batch_no),'')) as c"
                    + inspectionFrom(window, lotClause)
                    + " group by ()",
                totalBinds.toArray())
            .get("c", Long.class);

    // 分桶那条要 LEFT JOIN「每单 FAIL 条数」子查询，**多一个租户绑值**（子查询自己的）。
    // ⚠️ 顺序：**子查询的租户在前**，外层 where 的租户随后（trendFrom 里就是这个顺序）。
    //   把两个租户绑反了不报错，只会让外层查询读不到任何行 —— 趋势图变成一片空白。
    List<Object> bucketBinds = new ArrayList<>();
    bucketBinds.add(currentTenant());
    bucketBinds.addAll(totalBinds);
    List<LotTrendBucket> buckets = new ArrayList<>();
    for (Record r :
        sql.fetch(
                "select " + bucket + " as bucket_start, count(*) as inspections,"
                    + " coalesce(sum(i.inspected_qty),0) as inspected,"
                    + " coalesce(sum(i.inspected_qty) filter (where i.verdict <> 'RESALABLE'),0)"
                    + "   as non_resalable,"
                    + " count(distinct nullif(btrim(i.batch_no),'')) as lots,"
                    + " coalesce(sum(f.fail_qty),0) as defective"
                    + trendFrom(window, lotClause)
                    + " group by " + bucket
                    + " order by bucket_start",
                bucketBinds.toArray())) {
      LocalDate start = r.get("bucket_start", LocalDate.class);
      BigDecimal inspected = nz(r.get("inspected", BigDecimal.class));
      BigDecimal nonResalable = nz(r.get("non_resalable", BigDecimal.class));
      buckets.add(
          new LotTrendBucket(
              start,
              bucketLabel(start, granularity),
              r.get("inspections", Long.class),
              inspected,
              nonResalable,
              rate(nonResalable, inspected),
              r.get("lots", Long.class),
              nz(r.get("defective", BigDecimal.class))));
    }

    BigDecimal inspected = nz(totals.get("inspected", BigDecimal.class));
    BigDecimal nonResalable = nz(totals.get("non_resalable", BigDecimal.class));

    return new LotTrendReport(
        at,
        window.note(),
        "时间桶按 **UTC** 分（date_trunc + UTC），"
            + "不用会话时区：换一条连接池连接就换一套分桶，趋势图会凭空多出空桶。"
            + "非可再销售率 = （DEFECTIVE + SCRAPPED 件数）÷ 实检件数，分母是**件数**。"
            + "「缺陷件数」= 该桶内**判 FAIL 的检项条数**，"
            + "所以它与「非可再销售件数」**不是同一个东西**（一个数检项、一个数商品件）："
            + "判了可再销售但仍有项判 FAIL 的单子，会进前者不进后者 —— "
            + "这正是「检项不合格但整单还能卖」的诚实表达。"
            + (isSet(batchNo) ? "本次**按批次编码筛选**（" + batchNo.trim() + "）。" : "本次**不限定批次**。")
            + "「涉及批次数」是期间内出现过的批次总数，"
            + "「limit」只截断**返回行数**（时间桶），不截断统计范围 —— "
            + "把被截断的统计说成全量，是报表最常见的一种误导。",
        normalizeGranularity(granularity),
        (int) lotCount,
        new LotTrendTotals(
            totals.get("inspections", Long.class), inspected, nonResalable, rate(nonResalable, inspected)),
        buckets);
  }

  // ================================================================ 供应商质量对比

  /**
   * 供应商质量对比（职责句第三项）。
   *
   * <p>⚠️ <b>按 {@code lot_supplier_ref_id} 聚合</b>而不是按 lot_code ——
   * 同一个供应商的不同批次必须合成一行，否则「这个供应商到底行不行」这个问题答不出来。
   *
   * <p>⚠️ <b>未登记名称时返回 {@code supplierName=null}</b> 并由前端显示「未登记供应商」：
   * 把 uuid 直接当名字渲染出来，运营会以为那就是供应商名。
   */
  public SupplierComparisonReport supplierComparison(QualityWindow window, Integer limit) {
    int rows = clamp(limit, 50);
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.ofHours(8));
    List<Object> binds = windowBinds(window);
    binds.add(rows);

    List<SupplierQualityRow> result = new ArrayList<>();
    int unnamed = 0;
    for (Record r :
        sql.fetch(
                "select a.lot_supplier_ref_id as supplier_ref_id,"
                    + " count(distinct a.lot_id) as lot_count,"
                    + " coalesce(sum(a.attributed_qty) filter (where a.attributed_stage='PRODUCTION'),0)"
                    + "   as production,"
                    + " coalesce(sum(a.attributed_qty) filter (where a.attributed_stage='TRANSPORT'),0)"
                    + "   as transport,"
                    + " coalesce(sum(a.attributed_qty) filter (where a.attributed_stage='WAREHOUSE'),0)"
                    + "   as warehouse,"
                    + " coalesce(sum(a.attributed_qty) filter (where a.attributed_stage='DESCRIPTION'),0)"
                    + "   as description,"
                    + " coalesce(sum(a.attributed_qty) filter (where a.attributed_stage in"
                    + "   ('BUYER_FACTOR','UNDETERMINED')),0) as other,"
                    + " coalesce(sum(a.attributed_qty),0) as total,"
                    + " count(*) filter (where f.flag_status in ('SUSPECT','CONFIRMED')) as flagged"
                    + " from product_issue_attribution a"
                    + " left join product_issue_lot_flag f"
                    + "   on f.tenant_id = a.tenant_id and f.lot_id = a.lot_id"
                    + supplierWhere(window)
                    + " group by a.lot_supplier_ref_id"
                    + " order by coalesce(sum(a.attributed_qty) filter"
                    + "   (where a.attributed_stage='PRODUCTION'),0) desc,"
                    + " coalesce(sum(a.attributed_qty),0) desc, a.lot_supplier_ref_id limit ?",
                binds.toArray())) {
      UUID refId = r.get("supplier_ref_id", UUID.class);
      SupplierRefRow ref = refOf(refId);
      boolean registered = ref != null;
      String name = registered ? ref.supplierName() : null;
      if (!registered) {
        unnamed++;
      }
      BigDecimal production = nz(r.get("production", BigDecimal.class));
      BigDecimal total = nz(r.get("total", BigDecimal.class));
      result.add(
          new SupplierQualityRow(
              refId,
              registered ? ref.supplierCode() : "",
              name,
              registered,
              r.get("lot_count", Long.class),
              production,
              nz(r.get("transport", BigDecimal.class)),
              nz(r.get("warehouse", BigDecimal.class)),
              nz(r.get("description", BigDecimal.class)),
              nz(r.get("other", BigDecimal.class)),
              share(production, total),
              r.get("flagged", Long.class),
              improvementCount(refId, window)));
    }

    return new SupplierComparisonReport(
        at,
        window.note(),
        "对比行 = **一个供应商引用（lot_supplier_ref_id）**，不是一行一个批次。"
            + "按 lot_code 出表的话，同一供应商的 5 个批次会占 5 行，"
            + "「这个供应商到底行不行」这个问题根本答不出来。"
            + "生产责任占比 = 归到生产的件数 ÷ 该供应商全部归因件数（分母含买家因素与待定，"
            + "因为它们同样是**没有算在供应商头上**的责任）。"
            + "名称来自 trade 侧镜像表，**未登记时 supplierName 返回 null** —— "
            + "把 uuid 当名字渲染，运营会以为那就是供应商名。",
        result.size(),
        unnamed,
        result);
  }

  // ================================================================ 缺陷照片聚类

  /**
   * 缺陷照片聚类（职责句第四项）。
   *
   * <p>⚠️ <b>按 sha256 完全相同聚类</b>，不提供任何相似度数字。
   * 本仓没有图像库、没有模型、没有第三方视觉服务接入点。
   * 同一件商品在不同光线角度下拍的两张照片指纹必然不同，
   * 把它们判成同一簇就是在冒充图像识别能力（与 11.1.2.2 同一决定）。
   *
   * <p>所以这个报表的诚实读法是「<b>哪些照片被反复上传</b>」，
   * 而不是「外观相似的缺陷有多少类」。
   */
  public PhotoClusterReport photoClusters(Integer minClusterSize, Integer limit) {
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.ofHours(8));
    int min = minClusterSize == null ? 2 : Math.max(1, minClusterSize);
    int rows = clamp(limit, 50);

    List<PhotoClusterRow> clusters = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select p.content_sha256 as sha, count(*) as photo_count,"
                + " count(distinct p.attribution_id) as attribution_count,"
                + " count(distinct nullif(btrim(a.lot_code),'')) as lot_count,"
                + " count(distinct a.sku_id) as sku_count,"
                + " string_agg(distinct a.attributed_stage, ',' order by a.attributed_stage)"
                + "   as stages,"
                + " min(p.captured_at) as first_captured,"
                + " (select string_agg(x.object_uri, ' | ' order by x.object_uri)"
                + "    from (select pp.object_uri from product_issue_photo pp"
                + "          where pp.tenant_id = p.tenant_id and pp.content_sha256 = p.content_sha256"
                + "          order by pp.object_uri limit 5) x) as sample_uris"
                + " from product_issue_photo p"
                + " join product_issue_attribution a"
                + "   on a.tenant_id = p.tenant_id and a.id = p.attribution_id"
                + " where p.tenant_id = ?::uuid"
                + "   and a.attribution_status <> 'SUPERSEDED'"
                + "   and p.content_sha256 in ("
                + "     select p2.content_sha256 from product_issue_photo p2"
                + "      where p2.tenant_id = ?::uuid group by p2.content_sha256 having count(*) >= ?)"
                + " group by p.tenant_id, p.content_sha256"
                + " order by count(*) desc, p.content_sha256 limit ?",
            currentTenant(),
            currentTenant(),
            min,
            rows)) {
      long photoCount = r.get("photo_count", Long.class);
      clusters.add(
          new PhotoClusterRow(
              r.get("sha", String.class),
              (int) photoCount,
              r.get("attribution_count", Long.class),
              r.get("lot_count", Long.class),
              r.get("sku_count", Long.class),
              r.get("stages", String.class),
              r.get("attribution_count", Long.class) > 1,
              r.get("first_captured", OffsetDateTime.class),
              r.get("sample_uris", String.class)));
    }

    long totalPhotos =
        sql.fetchOne(
                "select count(*) from product_issue_photo where tenant_id = ?::uuid",
                currentTenant())
            .get(0, Long.class);
    long repeated =
        sql.fetchOne(
                "select count(*) from (select content_sha256 from product_issue_photo"
                    + " where tenant_id = ?::uuid group by content_sha256 having count(*) >= 2) x",
                currentTenant())
            .get(0, Long.class);

    return new PhotoClusterReport(
        at,
        PHOTO_CLUSTER_NOTE,
        totalPhotos,
        clusters.size(),
        (int) repeated,
        clusters);
  }

  // ================================================================ 开箱率

  /**
   * 开箱率（职责句第五项前半）。
   *
   * <p>⚠️ <b>必须和覆盖率一起读</b>：分子分母都在已登记观察范围内，
   * 而「已登记观察数 ÷ 期间内 FAIL 检项数」才是「这个数有多完整」。
   * 只报开箱率不报覆盖率，等于让「还没登记」的沉默被读成「没开箱问题」。
   */
  public OpenBoxReport openBoxRate(QualityWindow window) {
    return openBoxRate(window, null);
  }

  /**
   * 开箱率（带 SKU 下钻数量上限）。
   *
   * <p>⚠️ <b>下钻的覆盖率要单独算</b>：总览覆盖率是全体的，
   * 而某个 SKU 的覆盖率可能低得多 —— 只报总览覆盖率会让「这个 SKU 从没登记过」
   * 看起来像「这个 SKU 的开箱率很低」。
   */
  public OpenBoxReport openBoxRate(QualityWindow window, Integer skuLimit) {
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.ofHours(8));
    int rows = clamp(skuLimit, 50);
    List<Object> binds = windowBinds(window);

    long failChecks =
        sql.fetchOne("select count(*)" + failCheckWhere(window) + " group by ()", binds.toArray())
            .get(0, Long.class);

    Record obs =
        sql.fetchOne(
            "select count(*) as observations,"
                + " coalesce(sum(o.affected_qty),0) as observed_qty,"
                + " coalesce(sum(o.affected_qty) filter (where o.discovery_stage='BUYER_UNBOXING'),0)"
                + "   as open_box_qty"
                + observationFrom(window)
                + " group by ()",
            binds.toArray());
    long observationCount = obs.get("observations", Long.class);
    BigDecimal observedQty = nz(obs.get("observed_qty", BigDecimal.class));
    BigDecimal openBoxQty = nz(obs.get("open_box_qty", BigDecimal.class));

    List<Object> stageBinds = new ArrayList<>(binds);
    List<DiscoveryStageSlice> stages = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select o.discovery_stage as stage, count(*) as cnt,"
                + " coalesce(sum(o.affected_qty),0) as qty"
                + observationFrom(window)
                + " group by o.discovery_stage order by o.discovery_stage",
            stageBinds.toArray())) {
      DiscoveryStage s = stageOf(r.get("stage", String.class));
      stages.add(
          new DiscoveryStageSlice(
              s.name(),
              s.label(),
              r.get("cnt", Long.class),
              nz(r.get("qty", BigDecimal.class)),
              share(nz(r.get("qty", BigDecimal.class)), observedQty)));
    }

    // SKU 下钻：分子分母各自算，覆盖率也各自算。
    List<Object> skuBinds = new ArrayList<>(binds);
    skuBinds.add(rows);
    List<OpenBoxSkuRow> bySku = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select i.sku_id as sku_id, coalesce(nullif(btrim(i.sku_code),''),'(未填)') as sku_code,"
                + " coalesce(nullif(btrim(i.sku_name),''),'(未填)') as sku_name,"
                + " count(*) as observations, coalesce(sum(o.affected_qty),0) as observed_qty,"
                + " coalesce(sum(o.affected_qty) filter (where o.discovery_stage='BUYER_UNBOXING'),0)"
                + "   as open_box_qty"
                + observationFrom(window)
                + " group by i.sku_id, coalesce(nullif(btrim(i.sku_code),''),'(未填)'),"
                + "   coalesce(nullif(btrim(i.sku_name),''),'(未填)')"
                + " order by coalesce(sum(o.affected_qty) filter"
                + "   (where o.discovery_stage='BUYER_UNBOXING'),0) desc, count(*) desc limit ?",
            skuBinds.toArray())) {
      long perSkuObs = r.get("observations", Long.class);
      BigDecimal perSkuObserved = nz(r.get("observed_qty", BigDecimal.class));
      long perSkuFail = perSkuFailChecks(window, r.get("sku_id", UUID.class));
      bySku.add(
          new OpenBoxSkuRow(
              r.get("sku_id", UUID.class),
              r.get("sku_code", String.class),
              r.get("sku_name", String.class),
              perSkuObs,
              perSkuObserved,
              nz(r.get("open_box_qty", BigDecimal.class)),
              rate(nz(r.get("open_box_qty", BigDecimal.class)), perSkuObserved),
              share(perSkuObs, perSkuFail)));
    }

    return new OpenBoxReport(
        at,
        window.note(),
        OPEN_BOX_NOTE,
        failChecks,
        observationCount,
        share(observationCount, failChecks),
        observedQty,
        openBoxQty,
        rate(openBoxQty, observedQty),
        stages,
        bySku);
  }

  // ================================================================ 质量成本估算

  /**
   * 质量成本估算（职责句第五项后半）。
   *
   * <p>蓝图加粗句要求「质量成本（退货物流 + 损耗 + 差评影响）<b>应量化</b>」，
   * 而量化不等于把三种来源不同的数字加成一个数：
   * <b>损耗是实测</b>（原值 − 残值两个真列），<b>退货物流与差评影响是按参数估算</b>
   * （库里根本没有这两个金额），<b>参数未登记时排除在合计之外</b>。
   */
  public QualityCostReport qualityCost(QualityWindow window) {
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.ofHours(8));
    List<CostParameterRow> params = effectiveCostParameters();
    Map<String, CostCurrencyBreakdown> breakdowns = costBreakdowns(window, params);

    Record loss =
        sql.fetchOne(
            // ⚠️ **FILTER 的括号位置**：必须是 `sum(<整个表达式>) filter (where …)`，
            //   也就是右括号要**先**闭合 sum 的参数列表。
            //   写成 `sum(a - b filter (where …))` 时 filter 会挂到 `b` 那一项上，
            //   PostgreSQL 报 `syntax error at or near "filter"` ——
            //   看着像 SQL 写坏了，实际只是括号错了一位。
            "select coalesce(sum(i.original_value - i.salvage_amount),0) as amount,"
                + " coalesce(sum(i.original_value - i.salvage_amount)"
                + "   filter (where i.salvage_currency = 'USD'),0) as usd"
                + inspectionFrom(window)
                + " group by ()",
            windowBinds(window).toArray());

    List<CostCurrencyBreakdown> currencies = new ArrayList<>(breakdowns.values());
    if (currencies.isEmpty()) {
      // 没有任何币种有损耗事实时，仍把 USD 的实测行报出来 —— 否则「全为 0」会被读成「没损失」。
      currencies.add(
          new CostCurrencyBreakdown(
              "USD",
              new CostItem(
                  "loss",
                  "损耗",
                  CostBasis.MEASURED.name(),
                  CostBasis.MEASURED.label(),
                  nz(loss.get("usd", BigDecimal.class)),
                  null,
                  "原值 − 残值，逐条已判定质检单相减；**不按件数摊** —— "
                      + "摊到件上会让人以为损耗是「每件亏多少」，而它其实是这批货少赚的钱",
                  null,
                  "Σ(original_value − salvage_amount)"),
              notRegistered("return-logistics", "退货物流", "实收件数"),
              notRegistered("review-impact", "差评影响", "已登记缺陷件数"),
              nz(loss.get("usd", BigDecimal.class)),
              2));
    }

    return new QualityCostReport(
        at,
        window.note(),
        COST_NOTE,
        params,
        currencies,
        "本仓**没有商品评价表**（全库无 product_review / rating），"
            + "所以本模块**不产出「买家给了差评」这个事实** —— "
            + "发现环节枚举里刻意没有 NEGATIVE_REVIEW 这种取值，"
            + "造一个就是在声称我们读到过买家评价。"
            + "「差评影响」只能是**按租户自维护单价 × 已登记缺陷件数的估算**，"
            + "且必须与实测的损耗分开标注、合并时必须报出混合了两种依据。");
  }

  // ================================================================ 责任分布 + 改进清单

  /**
   * 责任分布 + 改进清单（输出句的后两样）。
   *
   * <p>⚠️ <b>只统计当前生效归因</b>（{@code attribution_status='ATTRIBUTED'}）：
   * SUPERSEDED 那版是改判留痕，把它算进来会让同一个问题被计两次
   * —— 11.1.2.2 的部分唯一索引正是为此建的。
   */
  public ImprovementReport improvements(
      QualityWindow window, String stage, String lotCode, int page, int size) {
    int p = Math.max(0, page);
    int s = size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.ofHours(8));

    StringBuilder where = new StringBuilder(" where a.tenant_id = ?::uuid");
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    where.append(" and a.attribution_status = 'ATTRIBUTED'");
    if (isSet(stage)) {
      AttributionStage st = attributionStageOrThrow(stage);
      where.append(" and a.attributed_stage = ?");
      binds.add(st.name());
    }
    if (isSet(lotCode)) {
      where.append(" and a.lot_code ilike ?");
      binds.add("%" + lotCode.trim() + "%");
    }
    String clause = where.toString();

    long total =
        sql.fetchOne(
                "select count(*) from product_issue_attribution a" + clause + " group by ()",
                binds.toArray())
            .get(0, Long.class);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add((long) p * s);
    List<ImprovementRow> items = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select a.id, a.attribution_no, a.lot_code, a.sku_code, a.sku_name,"
                + " a.attributed_stage, a.attributed_qty, a.attribution_note,"
                + " a.improvement_suggestion, f.flag_status"
                + " from product_issue_attribution a"
                + " left join product_issue_lot_flag f"
                + "   on f.tenant_id = a.tenant_id and f.lot_id = a.lot_id"
                + clause
                // 带批次标记的排前面：改进清单要先动已经在冒烟的那几个批次。
                + " order by (f.flag_status in ('SUSPECT','CONFIRMED')) desc,"
                + " a.attributed_qty desc, a.attributed_at desc, a.attribution_no"
                + " limit ? offset ?",
            pageBinds.toArray())) {
      String st = r.get("attributed_stage", String.class);
      String flagStatus = r.get("flag_status", String.class);
      items.add(
          new ImprovementRow(
              r.get("id", UUID.class),
              r.get("attribution_no", String.class),
              r.get("lot_code", String.class),
              r.get("sku_code", String.class),
              r.get("sku_name", String.class),
              st,
              stageLabelOf(st),
              nz(r.get("attributed_qty", BigDecimal.class)),
              r.get("attribution_note", String.class),
              r.get("improvement_suggestion", String.class),
              flagStatus == null ? null : !"NONE".equals(flagStatus),
              flagStatus));
    }

    return new ImprovementReport(
        at,
        "改进清单**只列当前生效归因**（ATTRIBUTED）：SUPERSEDED 那版是改判留痕，"
            + "算进来会让同一个问题被计两次。"
            + "排序 = **被标记为质量问题的批次排前面**，其次按涉及件数降序 —— "
            + "改进清单要先动已经在冒烟的那几个批次。"
            + "改进建议逐字取自 11.1.2.2 的**必填人写文字**"
            + "（本仓无模型接入点，所以没有「AI 生成」按钮，也不会出现这种标注）。",
        total,
        items.size(),
        responsibilityDistribution(window),
        items);
  }

  // ================================================================ 缺陷观察：写

  /**
   * 登记缺陷观察（开箱率的唯一数据来源）。
   *
   * <p>⚠️ <b>服务层与 DB 触发器各判一次</b>（V185 守卫②-⑥）：
   * 只判一次的话，绕过服务层直连 SQL 就能把一条 PASS 检项记成缺陷、
   * 或把「入库发现」记成「买家开箱」—— 后者能凭空把开箱率从 2% 抬到 30%。
   */
  public DefectObservationRow recordObservation(DefectObservationCreate request, UUID userId) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
    }
    String code = require(request.defectCode(), "缺陷编码", 48);
    String name = require(request.defectName(), "缺陷名称", 128);
    String note = optional(request.observationNote(), 1000, "备注");
    if (request.inspectionId() == null || request.checkResultId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "质检单 id 与检项 id 都必填");
    }
    if (request.discoveredAt() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "发现时刻必填（事实发生的那一刻）");
    }
    if (request.affectedQty() == null || request.affectedQty().signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "影响件数必须大于 0");
    }
    DefectDimension dimension = dimensionOrThrow(request.defectDimension());
    DiscoveryStage discovery = discoveryStageOrThrow(request.discoveryStage());
    DefectSeverity severity = severityOrThrow(request.severity());

    // 服务层先真查一次（人话原因），DB 守卫再钉一次。
    Record cr =
        sql.fetchOne(
            "select c.check_outcome, c.item_dimension, i.inspection_status, i.inspected_qty,"
                + " i.concluded_at, i.inspection_no"
                + " from return_quality_check_result c"
                + " join return_quality_inspection i on i.id = c.inspection_id"
                + "   and i.tenant_id = c.tenant_id"
                + " where c.id = ?::uuid and c.tenant_id = ?::uuid and c.inspection_id = ?::uuid",
            request.checkResultId(),
            currentTenant(),
            request.inspectionId());
    if (cr == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "检项不存在或不属于当前租户（也没挂在这张质检单上）");
    }
    if (!"FAIL".equals(cr.get("check_outcome", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "只有判 FAIL 的检项才能登记为缺陷（当前检项结论为 "
              + cr.get("check_outcome", String.class)
              + "）—— 判过不等于坏了");
    }
    if (!cr.get("item_dimension", String.class).equals(dimension.name())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "缺陷维度必须与检项维度一致（检项为 "
              + cr.get("item_dimension", String.class)
              + "，本次填 "
              + dimension.name()
              + "）");
    }
    if (!"CONCLUDED".equals(cr.get("inspection_status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "质检单尚未判定（当前 "
              + cr.get("inspection_status", String.class)
              + "），不能登记缺陷观察");
    }
    BigDecimal inspected = nz(cr.get("inspected_qty", BigDecimal.class));
    if (request.affectedQty().compareTo(inspected) > 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "影响件数 " + plain(request.affectedQty()) + " 超过该质检单的实检数量 " + plain(inspected));
    }
    OffsetDateTime concluded = cr.get("concluded_at", OffsetDateTime.class);
    if (discovery.openBox() && concluded != null && request.discoveredAt().isAfter(concluded)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "买家开箱发现的时刻（"
              + request.discoveredAt()
              + "）不可能晚于仓内判定完成时刻（"
              + concluded
              + "）");
    }
    if (request.attributionId() != null
        && sql.fetchOne(
                "select 1 from product_issue_attribution where tenant_id = ?::uuid and id = ?::uuid",
                currentTenant(),
                request.attributionId())
            == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "归因单不存在或不属于当前租户");
    }

    UUID id = UUID.randomUUID();
    String observationNo = nextObservationNo();
    sql.execute(
        "insert into quality_defect_observation(id,tenant_id,observation_no,inspection_id,"
            + "check_result_id,attribution_id,defect_code,defect_name,defect_dimension,"
            + "discovery_stage,discovered_at,affected_qty,severity,observation_note,"
            + "observation_status,recorded_by,recorded_at,version,created_at)"
            + " values(?::uuid,?::uuid,?,?::uuid,?::uuid,?::uuid,?,?,?,?,?::timestamptz,"
            + "?::numeric,?,?,'RECORDED',?::uuid,?::timestamptz,0,?::timestamptz)",
        id,
        currentTenant(),
        observationNo,
        request.inspectionId(),
        request.checkResultId(),
        request.attributionId(),
        code,
        name,
        dimension.name(),
        discovery.name(),
        request.discoveredAt(),
        request.affectedQty(),
        severity.name(),
        note,
        userId,
        OffsetDateTime.now(ZoneOffset.ofHours(8)),
        OffsetDateTime.now(ZoneOffset.ofHours(8)));

    writeEvent("DEFECT_OBSERVATION", "RECORDED", id, "发现环节 " + discovery.label() + "；" + note, userId);
    return observationOf(id);
  }

  /** 作废缺陷观察（终态：业务列冻结，判错了请另记一条）。 */
  public DefectObservationRow voidObservation(
      UUID id, DefectObservationVoid request, UUID userId) {
    if (request == null || !isSet(request.voidReason())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "作废理由必填");
    }
    String reason = require(request.voidReason(), "作废理由", 1000);
    Record current =
        sql.fetchOne(
            "select observation_status, version from quality_defect_observation"
                + " where id = ?::uuid and tenant_id = ?::uuid",
            id,
            currentTenant());
    if (current == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "缺陷观察不存在或不属于当前租户");
    }
    if (!current.get("version", Long.class).equals(request.version())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "版本号不一致（当前 " + current.get("version", Long.class) + "）");
    }
    if ("VOID".equals(current.get("observation_status", String.class))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该观察已作废，作废是终态");
    }
    sql.execute(
        "update quality_defect_observation"
            + " set observation_status = 'VOID', void_reason = ?, voided_by = ?::uuid,"
            + "     voided_at = ?::timestamptz, version = version + 1"
            + " where id = ?::uuid and tenant_id = ?::uuid",
        reason,
        userId,
        OffsetDateTime.now(ZoneOffset.ofHours(8)),
        id,
        currentTenant());
    writeEvent("DEFECT_OBSERVATION", "VOIDED", id, reason, userId);
    return observationOf(id);
  }

  /** 缺陷观察列表（真分页 + 多维筛选）。 */
  public DefectObservationPage listObservations(
      String inspectionId, String stage, String dimension, String status, String keyword,
      int page, int size) {
    int p = Math.max(0, page);
    int s = size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.ofHours(8));

    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("o.tenant_id = ?::uuid");
    binds.add(currentTenant());
    if (isSet(inspectionId)) {
      where.add("o.inspection_id = ?::uuid");
      binds.add(UUID.fromString(inspectionId.trim()));
    }
    if (isSet(stage)) {
      where.add("o.discovery_stage = ?");
      binds.add(discoveryStageOrThrow(stage).name());
    }
    if (isSet(dimension)) {
      where.add("o.defect_dimension = ?");
      binds.add(dimensionOrThrow(dimension).name());
    }
    if (isSet(status)) {
      where.add("o.observation_status = ?");
      binds.add(observationStatusOrThrow(status).name());
    }
    if (isSet(keyword)) {
      where.add(
          "(o.defect_name ilike ? or o.defect_code ilike ? or i.inspection_no ilike ?"
              + " or i.sku_code ilike ?)");
      for (int i = 0; i < 4; i++) {
        binds.add("%" + keyword.trim() + "%");
      }
    }
    String clause = " where " + String.join(" and ", where);
    String from =
        " from quality_defect_observation o"
            + " join return_quality_inspection i on i.id = o.inspection_id"
            + "   and i.tenant_id = o.tenant_id";

    long total = sql.fetchOne("select count(*)" + from + clause, binds.toArray()).get(0, Long.class);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add((long) p * s);
    List<DefectObservationRow> rows = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select o.*, i.inspection_no, c.item_code, c.item_name, i.sku_code"
                + from
                + " left join return_quality_check_result c on c.id = o.check_result_id"
                + clause
                + " order by o.discovery_stage, o.discovered_at desc, o.observation_no"
                + " limit ? offset ?",
            pageBinds.toArray())) {
      rows.add(toObservationRow(r));
    }

    return new DefectObservationPage(
        rows,
        p,
        s,
        total,
        "列表默认**含已作废行**（作废是留痕，抹掉就看不出这里曾经记错过什么）；"
            + "筛选 observationStatus=RECORDED 才只看有效行 —— "
            + "缺陷类型分布与开箱率两个口径**都只认有效行**。",
        at);
  }

  // ================================================================ 成本参数：写

  /** 建成本参数（每币种同时只能有一份生效）。 */
  public CostParameterRow createCostParameter(CostParameterCreate request, UUID userId) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
    }
    String currency = require(request.currency(), "币种", 3).toUpperCase(Locale.ROOT);
    if (!currency.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是三字母大写代码");
    }
    if (request.returnLogisticsPerCase() == null || request.returnLogisticsPerCase().signum() < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每件退货物流成本不能为负");
    }
    if (request.reviewImpactPerDefect() == null || request.reviewImpactPerDefect().signum() < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每件缺陷的差评影响估算不能为负");
    }
    if (request.effectiveFrom() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "生效起始日必填");
    }
    if (sql.fetchOne(
            "select 1 from quality_cost_parameter"
                + " where tenant_id = ?::uuid and currency = ? and parameter_status = 'ACTIVE'",
            currentTenant(),
            currency)
        != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          currency + " 已有生效参数 —— 每币种同时只能有一份生效参数，"
              + "两份同时生效时「按哪一份算成本」没有唯一答案。请先换版。");
    }

    UUID id = UUID.randomUUID();
    sql.execute(
        // ⚠️ VALUES 有 **10** 个占位符（version 是字面量 0，不占位）：
        //   少给一个值 jOOQ 报的是「未设定参数值 N」或
        //   「invalid input syntax for type uuid: \<某个时间戳\>」——
        //   后者尤其误导：看起来像 UUID 格式错，实际是**往前错位了一格**。
        "insert into quality_cost_parameter(id,tenant_id,currency,return_logistics_per_case,"
            + "review_impact_per_defect,parameter_status,effective_from,source_note,"
            + "created_by,created_at,version)"
            + " values(?::uuid,?::uuid,?,?::numeric,?::numeric,'ACTIVE',?::date,?,?::uuid,"
            + "?::timestamptz,0)",
        id,
        currentTenant(),
        currency,
        request.returnLogisticsPerCase(),
        request.reviewImpactPerDefect(),
        request.effectiveFrom(),
        optional(request.sourceNote(), 200, "口径说明"),
        userId,
        OffsetDateTime.now(ZoneOffset.ofHours(8)),
        OffsetDateTime.now(ZoneOffset.ofHours(8)));
    writeEvent("COST_PARAMETER", "CREATED", id, currency + " 生效参数建立", userId);
    return parameterOf(id);
  }

  /** 成本参数换版（终态：老版本标 SUPERSEDED 并保留）。 */
  public CostParameterRow supersedeCostParameter(
      UUID id, CostParameterSupersede request, UUID userId) {
    if (request == null || !isSet(request.supersedeReason())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "换版理由必填");
    }
    String reason = require(request.supersedeReason(), "换版理由", 1000);
    Record current =
        sql.fetchOne(
            "select parameter_status, version from quality_cost_parameter"
                + " where id = ?::uuid and tenant_id = ?::uuid",
            id,
            currentTenant());
    if (current == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "成本参数不存在或不属于当前租户");
    }
    if (!current.get("version", Long.class).equals(request.version())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "版本号不一致（当前 " + current.get("version", Long.class) + "）");
    }
    if (!"ACTIVE".equals(current.get("parameter_status", String.class))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该参数已被换过版，换版是终态");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.ofHours(8));
    // ⚠️ 留痕**先写**再改状态：V185 守卫⑦在 UPDATE 时检查留痕已存在，
    //   顺序反了会被守卫当场拒。
    writeEvent("COST_PARAMETER", "SUPERSEDED", id, reason, userId);
    sql.execute(
        "update quality_cost_parameter set parameter_status = 'SUPERSEDED',"
            + " superseded_by = ?::uuid, superseded_at = ?::timestamptz, supersede_reason = ?,"
            + " effective_to = ?::date, version = version + 1"
            + " where id = ?::uuid and tenant_id = ?::uuid and parameter_status = 'ACTIVE'",
        userId, now, reason, now.atZoneSameInstant(ZoneOffset.UTC).toLocalDate(), id, currentTenant());
    return parameterOf(id);
  }

  /** 成本参数列表。 */
  public CostParameterPage listCostParameters(String status, int page, int size) {
    int p = Math.max(0, page);
    int s = size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?::uuid");
    binds.add(currentTenant());
    if (isSet(status)) {
      where.add("parameter_status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    String clause = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from quality_cost_parameter" + clause, binds.toArray())
            .get(0, Long.class);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add((long) p * s);
    List<CostParameterRow> rows = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select * from quality_cost_parameter" + clause
                + " order by parameter_status, currency, effective_from desc limit ? offset ?",
            pageBinds.toArray())) {
      rows.add(toParameterRow(r));
    }
    return new CostParameterPage(
        rows,
        p,
        s,
        total,
        "参数按币种**每份只有一个生效版本**（部分唯一索引），"
            + "老版本保留为 SUPERSEDED —— 成本汇总会因为换了一版参数而整页数字不同，"
            + "三个月后再看这张报表，答不出「上次是多少」的话这个数字就再也不能用来做改进决策了。");
  }

  // ================================================================ 供应商引用：写

  /** 登记 / 更新供应商引用（upsert；刻意没有删除入口）。 */
  public SupplierRefRow upsertSupplierRef(SupplierRefUpsert request, UUID userId) {
    if (request == null || request.supplierRefId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "供应商引用 id 必填");
    }
    String code = optional(request.supplierCode(), 64, "供应商编码");
    String name = optional(request.supplierName(), 200, "供应商名称");
    if (!isSet(code) && !isSet(name)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "编码与名称至少填一个 —— 两个都空的登记等于什么都没登记");
    }
    String source = require(request.refSource(), "来源", 32).toUpperCase(Locale.ROOT);
    if (!source.equals("TENANT_MAINTAINED") && !source.equals("MODULE_6_PURCHASE_SYNC")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "来源只能是 TENANT_MAINTAINED / MODULE_6_PURCHASE_SYNC");
    }
    String reference = optional(request.sourceReference(), 128, "来源引用");
    if (source.equals("MODULE_6_PURCHASE_SYNC") && !isSet(reference)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "同步器来源必须带 sourceReference —— 否则「这行是同步器写的」只是口头声明");
    }
    UUID refId = request.supplierRefId();
    Record existing =
        sql.fetchOne(
            "select id, ref_status from quality_supplier_ref where tenant_id = ?::uuid"
                + " and supplier_ref_id = ?::uuid",
            currentTenant(),
            refId);
    if (existing == null) {
      UUID id = UUID.randomUUID();
      sql.execute(
          "insert into quality_supplier_ref(id,tenant_id,supplier_ref_id,supplier_code,"
              + "supplier_name,ref_source,source_reference,ref_status,note,created_by,"
              + "created_at,updated_at)"
              + " values(?::uuid,?::uuid,?::uuid,?,?,?,?,'ACTIVE',?,?::uuid,?::timestamptz,"
              + "?::timestamptz)",
          id,
          currentTenant(),
          refId,
          code,
          name,
          source,
          reference,
          optional(request.note(), 1000, "备注"),
          userId,
          OffsetDateTime.now(ZoneOffset.ofHours(8)),
          OffsetDateTime.now(ZoneOffset.ofHours(8)));
      writeEvent("SUPPLIER_REF", "REGISTERED", id, refId + " 登记", userId);
      return supplierRefOf(id);
    }
    UUID id = existing.get("id", UUID.class);
    sql.execute(
        "update quality_supplier_ref set supplier_code = ?, supplier_name = ?, ref_source = ?,"
            + " source_reference = ?, note = ?, updated_at = ?::timestamptz"
            + " where id = ?::uuid and tenant_id = ?::uuid",
        code, name, source, reference, optional(request.note(), 1000, "备注"),
        OffsetDateTime.now(ZoneOffset.ofHours(8)), id, currentTenant());
    writeEvent("SUPPLIER_REF", "UPDATED", id, refId + " 更新", userId);
    return supplierRefOf(id);
  }

  /** 供应商引用启停（停用是留痕的，删除不是）。 */
  public SupplierRefRow setSupplierRefStatus(UUID id, SupplierRefStatus request, UUID userId) {
    if (request == null || !isSet(request.refStatus())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "状态必填");
    }
    String status = request.refStatus().trim().toUpperCase(Locale.ROOT);
    if (!status.equals("ACTIVE") && !status.equals("DISABLED")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "状态只能是 ACTIVE / DISABLED");
    }
    if (sql.fetchOne(
            "select 1 from quality_supplier_ref where id = ?::uuid and tenant_id = ?::uuid",
            id, currentTenant())
        == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "供应商引用不存在或不属于当前租户");
    }
    sql.execute(
        "update quality_supplier_ref set ref_status = ?, updated_at = ?::timestamptz"
            + " where id = ?::uuid and tenant_id = ?::uuid",
        status, OffsetDateTime.now(ZoneOffset.ofHours(8)), id, currentTenant());
    writeEvent("SUPPLIER_REF", status, id, "状态切到 " + status, userId);
    return supplierRefOf(id);
  }

  /** 供应商引用列表。 */
  public SupplierRefPage listSupplierRefs(String keyword, int page, int size) {
    int p = Math.max(0, page);
    int s = size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?::uuid");
    binds.add(currentTenant());
    if (isSet(keyword)) {
      where.add("(supplier_code ilike ? or supplier_name ilike ? or supplier_ref_id::text like ?)");
      for (int i = 0; i < 3; i++) {
        binds.add("%" + keyword.trim() + "%");
      }
    }
    String clause = " where " + String.join(" and ", where);
    long total = sql.fetchOne("select count(*) from quality_supplier_ref" + clause, binds.toArray())
        .get(0, Long.class);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add((long) p * s);
    List<SupplierRefRow> rows = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select * from quality_supplier_ref" + clause
                + " order by supplier_name, supplier_code limit ? offset ?",
            pageBinds.toArray())) {
      rows.add(toSupplierRefRow(r));
    }
    return new SupplierRefPage(
        rows,
        p,
        s,
        total,
        "供应商**主数据在 catalog 独立库**，本表只是 trade 侧的显示镜像"
            + "（沿用 11.1.2.2 对 platform_enum_mapping 的同一决定）。"
            + "因此本模块**没有删除入口**：同步器按同一形状写回来时，删掉的行会立刻复活，"
            + "于是「明明停用了却又生效」。停用是留痕的，删除不是。");
  }

  // ================================================================ 内部聚合

  /**
   * 责任分布（归因环节 × 件数）。
   *
   * <p>⚠️ <b>按件数而不是单张数占比</b>：一个归因单可以覆盖 10 件，
   * 十个归因单也可能各覆盖 1 件 —— 按张数算会让「一个批次问题很大」被稀释成
   * 「十个各 1 件的小问题」，而这两件事的处置方式完全不同。
   */
  private List<ResponsibilitySlice> responsibilityDistribution(QualityWindow window) {
    List<ResponsibilitySlice> slices = new ArrayList<>();
    Map<String, long[]> counts = new LinkedHashMap<>();
    Map<String, BigDecimal> qty = new LinkedHashMap<>();
    BigDecimal total = BigDecimal.ZERO;

    for (AttributionStage stage : AttributionStage.values()) {
      counts.put(stage.name(), new long[2]);
      qty.put(stage.name(), BigDecimal.ZERO);
    }

    List<Object> binds = new ArrayList<>();
    StringBuilder clause = new StringBuilder(" where a.tenant_id = ?::uuid");
    binds.add(currentTenant());
    clause.append(" and a.attribution_status = 'ATTRIBUTED'");
    if (window != null) {
      if (window.from() != null) {
        clause.append(" and a.attributed_at >= ?::timestamptz");
      }
      if (window.to() != null) {
        clause.append(" and a.attributed_at < ?::timestamptz");
      }
      for (Object b : window.binds()) {
        binds.add(b);
      }
    }
    for (Record r :
        sql.fetch(
            "select a.attributed_stage as stage, count(*) as cnt,"
                + " coalesce(sum(a.attributed_qty),0) as qty"
                + " from product_issue_attribution a"
                + clause
                + " group by a.attributed_stage",
            binds.toArray())) {
      String stage = r.get("stage", String.class);
      long[] slot = counts.get(stage);
      if (slot != null) {
        slot[0] = r.get("cnt", Long.class);
        BigDecimal q = nz(r.get("qty", BigDecimal.class));
        qty.put(stage, q);
        total = total.add(q);
      }
    }

    for (AttributionStage stage : AttributionStage.values()) {
      long[] slot = counts.get(stage.name());
      slices.add(
          new ResponsibilitySlice(
              stage.name(), stage.label(), slot[0], qty.get(stage.name()), share(qty.get(stage.name()), total)));
    }
    return slices;
  }

  /** 批次标记计数。 */
  private LotFlagCounts lotFlagCounts() {
    Record r =
        sql.fetchOne(
            "select count(*) filter (where flag_status in ('SUSPECT','CONFIRMED')) as flagged,"
                + " count(*) filter (where flag_status = 'SUSPECT') as suspect,"
                + " count(*) filter (where flag_status = 'CONFIRMED') as confirmed"
                + " from product_issue_lot_flag where tenant_id = ?::uuid",
            currentTenant());
    return new LotFlagCounts(
        r.get("flagged", Long.class), r.get("suspect", Long.class), r.get("confirmed", Long.class));
  }

  /** 生效中的成本参数（按币种）。 */
  private List<CostParameterRow> effectiveCostParameters() {
    List<CostParameterRow> rows = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select * from quality_cost_parameter where tenant_id = ?::uuid"
                + " and parameter_status = 'ACTIVE' order by currency",
            currentTenant())) {
      rows.add(toParameterRow(r));
    }
    return rows;
  }

  /** 逐币种的成本分项与合计。 */
  private Map<String, CostCurrencyBreakdown> costBreakdowns(
      QualityWindow window, List<CostParameterRow> params) {
    Map<String, CostParameterRow> byCurrency = new LinkedHashMap<>();
    for (CostParameterRow p : params) {
      byCurrency.put(p.currency(), p);
    }

    List<Object> binds = windowBinds(window);
    Map<String, BigDecimal> lossByCurrency = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select i.salvage_currency as currency,"
                + " coalesce(sum(i.original_value - i.salvage_amount),0) as amount"
                + inspectionFrom(window)
                + " group by i.salvage_currency",
            binds.toArray())) {
      lossByCurrency.put(r.get("currency", String.class), nz(r.get("amount", BigDecimal.class)));
    }

    // 退货物流的件数基数 = **全部实收件数**：买家寄回就发生了运费，与好坏无关。
    // ⚠️ join 方向：inspection 侧持有 **receipt_line_id**（V179），
    //   不是「质检单 id = 实收行 id」—— 写反了会把每一行实收都配成一张假质检单。
    List<Object> receiptBinds = windowBinds(window);
    Map<String, BigDecimal> receivedByCurrency = new LinkedHashMap<>();
    String currencyExpr = "coalesce(nullif(btrim(i.salvage_currency),''),'USD')";
    for (Record r :
        sql.fetch(
            "select " + currencyExpr + " as currency, coalesce(sum(rrl.received_qty),0) as qty"
                + " from return_receipt_line rrl"
                + " join return_quality_inspection i on i.tenant_id = rrl.tenant_id"
                + "   and i.receipt_line_id = rrl.id"
                + receiptWhere(window)
                + " group by " + currencyExpr,
            receiptBinds.toArray())) {
      receivedByCurrency.put(r.get("currency", String.class), nz(r.get("qty", BigDecimal.class)));
    }

    // 差评影响的件数基数 = **已登记观察的缺陷件数**。
    List<Object> defectBinds = windowBinds(window);
    Map<String, BigDecimal> defectByCurrency = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select " + currencyExpr + " as currency, coalesce(sum(o.affected_qty),0) as qty"
                + observationFrom(window)
                + " group by " + currencyExpr,
            defectBinds.toArray())) {
      defectByCurrency.put(r.get("currency", String.class), nz(r.get("qty", BigDecimal.class)));
    }

    // 并集：损耗出现过的币种 ∪ 参数声明过的币种。
    java.util.TreeSet<String> currencies = new java.util.TreeSet<>();
    currencies.addAll(lossByCurrency.keySet());
    currencies.addAll(byCurrency.keySet());

    Map<String, CostCurrencyBreakdown> result = new LinkedHashMap<>();
    for (String currency : currencies) {
      CostParameterRow param = byCurrency.get(currency);
      BigDecimal received = receivedByCurrency.getOrDefault(currency, BigDecimal.ZERO);
      BigDecimal defectQty = defectByCurrency.getOrDefault(currency, BigDecimal.ZERO);

      CostItem loss =
          new CostItem(
              "loss",
              "损耗",
              CostBasis.MEASURED.name(),
              CostBasis.MEASURED.label(),
              lossByCurrency.getOrDefault(currency, BigDecimal.ZERO),
              null,
              "原值 − 残值，逐条已判定质检单相减；**不按件数摊** —— "
                  + "摊到件上会让人以为损耗是「每件亏多少」，而它其实是这批货少赚的钱",
              null,
              "Σ(original_value − salvage_amount)");

      CostItem logistics =
          param == null
              ? notRegistered(
                  "return-logistics",
                  "退货物流",
                  "实收件数 " + plain(received) + " 件",
                  "退货物流单价未登记（该币种没有生效成本参数）")
              : new CostItem(
                  "return-logistics",
                  "退货物流",
                  CostBasis.ESTIMATED.name(),
                  CostBasis.ESTIMATED.label(),
                  received.multiply(param.returnLogisticsPerCase()).setScale(4, RoundingMode.HALF_UP),
                  received,
                  "**全部实收件数**（不是缺陷件数）：买家寄回就发生了运费，与好坏无关。"
                      + "只按缺陷件算，等于让「退货里没问题的那些」白花的钱从报表上消失",
                  param.returnLogisticsPerCase(),
                  "实收件数 × 单价 " + plain(param.returnLogisticsPerCase()) + " / 件");

      CostItem review =
          param == null
              ? notRegistered(
                  "review-impact",
                  "差评影响",
                  "已登记缺陷件数 " + plain(defectQty) + " 件",
                  "差评影响单价未登记（该币种没有生效成本参数）")
              : new CostItem(
                  "review-impact",
                  "差评影响",
                  CostBasis.ESTIMATED.name(),
                  CostBasis.ESTIMATED.label(),
                  defectQty.multiply(param.reviewImpactPerDefect()).setScale(4, RoundingMode.HALF_UP),
                  defectQty,
                  "**已登记缺陷观察的件数**。注意这是「已登记」的量："
                      + "登记得少，这一项就会偏小 —— 它是估算，且分母本身受登记进度影响",
                  param.reviewImpactPerDefect(),
                  "已登记缺陷件数 × 单价 " + plain(param.reviewImpactPerDefect()) + " / 件");

      int excluded = (logistics.basis().equals(CostBasis.NOT_REGISTERED.name()) ? 1 : 0)
          + (review.basis().equals(CostBasis.NOT_REGISTERED.name()) ? 1 : 0);
      // ⚠️ **只加可加项**：NOT_REGISTERED 的 amount 是 null（算不出来），
      //   直接 add 会 NPE；而 `amount().add(null)` 在 BigDecimal 上是静默的 NPE，
      //   报错位置还在 BigDecimal 内部，看不出是哪一项没登记。
      BigDecimal total = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
      for (CostItem item : List.of(loss, logistics, review)) {
        if (CostBasis.valueOf(item.basis()).summable() && item.amount() != null) {
          total = total.add(item.amount());
        }
      }
      total = total.setScale(4, RoundingMode.HALF_UP);

      result.put(
          currency,
          new CostCurrencyBreakdown(currency, loss, logistics, review, total, excluded));
    }
    return result;
  }

  /** 未登记参数的占位项：<b>金额为 null 而不是 0</b>。 */
  private CostItem notRegistered(String key, String label, String basisQtyNote) {
    return notRegistered(key, label, basisQtyNote, "单价未登记");
  }

  private CostItem notRegistered(
      String key, String label, String basisQtyNote, String reason) {
    return new CostItem(
        key,
        label,
        CostBasis.NOT_REGISTERED.name(),
        CostBasis.NOT_REGISTERED.label(),
        null,
        null,
        basisQtyNote + "（**无法计价**：" + reason + "）",
        null,
        "无法计算 —— " + reason);
  }

  /** 混合依据时取最弱的那个（估算 < 实测在可信度上分两档：实测最强）。 */
  private String weakestBasis(CostCurrencyBreakdown b) {
    List<CostItem> items = List.of(b.loss(), b.returnLogistics(), b.reviewImpact());
    boolean notRegistered = items.stream().anyMatch(i -> !CostBasis.valueOf(i.basis()).summable());
    boolean estimated =
        items.stream().anyMatch(i -> CostBasis.ESTIMATED.name().equals(i.basis()));
    if (notRegistered) {
      return CostBasis.NOT_REGISTERED.name();
    }
    return estimated ? CostBasis.ESTIMATED.name() : CostBasis.MEASURED.name();
  }

  private String weakestBasisLabel(CostCurrencyBreakdown b) {
    return CostBasis.valueOf(weakestBasis(b)).label();
  }

  /** 某个缺陷类型上「买家开箱发现」的件数（已登记观察范围内）。 */
  private BigDecimal openBoxQtyOfType(String itemCode) {
    return nz(
        sql.fetchOne(
                "select coalesce(sum(o.affected_qty) filter"
                    + " (where o.discovery_stage = 'BUYER_UNBOXING'),0) as qty"
                    + " from quality_defect_observation o"
                    + " join return_quality_check_result c on c.id = o.check_result_id"
                    + "   and c.tenant_id = o.tenant_id"
                    + " where o.tenant_id = ?::uuid and o.observation_status = 'RECORDED'"
                    + "   and c.item_code = ?",
                currentTenant(), itemCode)
            .get("qty", BigDecimal.class));
  }

  /**
   * 某个缺陷类型上最重的严重度（没有观察时返回 null，不编一个「轻微」出来）。
   *
   * <p>⚠️ {@code fetchOne} 在**零行**时返回 {@code null}（不是空 Record）：
   * 直接 {@code .get(0, …)} 会 NPE，而「这个缺陷类型还没有登记观察」
   * 是完全正常的业务状态，不是异常。
   */
  private String topSeverityOfType(String itemCode) {
    Record found =
        sql.fetchOne(
                "select o.severity from quality_defect_observation o"
                    + " join return_quality_check_result c on c.id = o.check_result_id"
                    + "   and c.tenant_id = o.tenant_id"
                    + " where o.tenant_id = ?::uuid and o.observation_status = 'RECORDED'"
                    + "   and c.item_code = ?"
                    + " order by array_position("
                    + "   array['CRITICAL','MAJOR','MODERATE','MINOR'], o.severity) limit 1",
                currentTenant(), itemCode);
    String severity = found == null ? null : found.get(0, String.class);
    return severity == null ? null : severityOrThrow(severity).label();
  }

  /** 某供应商的改进建议条数（改进建议逐字取自 11.1.2.2 的必填人写文字）。 */
  private long improvementCount(UUID supplierRefId, QualityWindow window) {
    if (supplierRefId == null) {
      return 0;
    }
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    binds.add(supplierRefId);
    StringBuilder extra = new StringBuilder();
    if (window != null) {
      if (window.from() != null) {
        extra.append(" and a.attributed_at >= ?::timestamptz");
      }
      if (window.to() != null) {
        extra.append(" and a.attributed_at < ?::timestamptz");
      }
      // 顺序与 extra 的追加顺序一致：from 先、to 后。
      binds.addAll(window.binds());
    }
    return sql.fetchOne(
            "select count(*) from product_issue_attribution a"
                + " where a.tenant_id = ?::uuid and a.lot_supplier_ref_id = ?::uuid"
                + "   and a.attribution_status = 'ATTRIBUTED'" + extra + " group by ()",
            binds.toArray())
        .get(0, Long.class);
  }

  /** 某个 SKU 的 FAIL 检项条数（用于 SKU 级覆盖率）。 */
  private long perSkuFailChecks(QualityWindow window, UUID skuId) {
    if (skuId == null) {
      return 0;
    }
    List<Object> binds = windowBinds(window);
    binds.add(skuId);
    // ⚠️ 只用 failCheckFrom：**不要**再手写一遍 FROM ——
    //   failCheckWhere 自带 FROM，叠一次就会拼出两个 FROM，
    //   报出来的却是 BadSqlGrammar，看不出是哪一层多写了。
    return sql.fetchOne(
            "select count(*)" + failCheckFrom(window) + " and i.sku_id = ?::uuid group by ()",
            binds.toArray())
        .get(0, Long.class);
  }

  // ================================================================ SQL 片段
  //
  // ⚠️ **每段 where 都恰好绑定一次租户**（{@link #windowBinds} 第一个绑值）。
  //   早期版本给 join 两侧各写了一次 `tenant_id = ?`，于是同一段 SQL 要绑两个租户值，
  //   而不同片段要绑的个数还不一样 —— 占位符顺序错位**不报错**，
  //   只会把租户值绑到 from/to 上，表现为「期间筛选莫名其妙按 1970 年算」。
  //   现在统一：**租户只从主表别名上取一次**，join 条件本身已经带了 tenant 等值约束。

  /** 已判定质检单的窗口条件（租户谓词 + 状态 + 期间）。 */
  private String inspectionWhere(QualityWindow window) {
    return inspectionWhere(window, "");
  }

  private String inspectionWhere(QualityWindow window, String extra) {
    return " where i.tenant_id = ?::uuid"
        + "   and i.inspection_status = 'CONCLUDED'"
        + window.sql()
        + extra;
  }

  /**
   * 已判定质检单的 FROM + WHERE。
   *
   * <p>⚠️ 与 {@link #inspectionWhere} 分开是有原因的：where 片段**不含 FROM**，
   * 任何 {@code "select ..." + inspectionWhere(...)} 的写法都会少掉 FROM，
   * 而 PostgreSQL 报的是 {@code DataAccessException} → 统一错误翻译成 **409 Conflict**，
   * 看起来像「数据冲突」而不是「SQL 写错了」—— 排查方向会被直接带偏。
   */
  private String inspectionFrom(QualityWindow window) {
    return inspectionFrom(window, "");
  }

  private String inspectionFrom(QualityWindow window, String extra) {
    return " from return_quality_inspection i" + inspectionWhere(window, extra);
  }

  /** 缺陷观察的 FROM + WHERE（observation → inspection）。 */
  private String observationFrom(QualityWindow window) {
    return " from quality_defect_observation o"
        + " join return_quality_inspection i on i.tenant_id = o.tenant_id"
        + "   and i.id = o.inspection_id"
        + observationWhere(window);
  }

  /** FAIL 检项的 FROM + WHERE。 */
  private String failCheckFrom(QualityWindow window) {
    return failCheckWhere(window);
  }

  /** FAIL 检项的窗口条件。租户只绑一次（从 i 上取，c 已由 join 约束）。 */
  private String failCheckWhere(QualityWindow window) {
    return " from return_quality_check_result c"
        + " join return_quality_inspection i on i.id = c.inspection_id"
        + "   and i.tenant_id = c.tenant_id"
        + " where i.tenant_id = ?::uuid"
        + "   and i.inspection_status = 'CONCLUDED' and c.check_outcome = 'FAIL'"
        + window.sql();
  }

  /** 有效缺陷观察的窗口条件。 */
  private String observationWhere(QualityWindow window) {
    return " where o.tenant_id = ?::uuid and o.observation_status = 'RECORDED'"
        + "   and i.inspection_status = 'CONCLUDED'"
        + window.sql();
  }

  /** 供应商对比的窗口条件（按 attributed_at 过滤，租户只绑一次）。 */
  private String supplierWhere(QualityWindow window) {
    return " where a.tenant_id = ?::uuid and a.lot_supplier_ref_id is not null"
        + "   and a.attribution_status = 'ATTRIBUTED'"
        + (window.from() == null ? "" : " and a.attributed_at >= ?::timestamptz")
        + (window.to() == null ? "" : " and a.attributed_at < ?::timestamptz");
  }

  /**
   * 批次趋势分桶：显式 LEFT JOIN 一张「每张质检单的 FAIL 条数」子查询。
   *
   * <p>⚠️ 刻意**不用**字符串替换拼 where —— 那样每多一个租户谓词就要重新数一遍
   * 绑值个数，而数错了 jOOQ 不报错，只会把 from/to 的值绑到租户上。
   */
  private String trendFrom(QualityWindow window, String extra) {
    return " from return_quality_inspection i"
        + " left join (select inspection_id, count(*) as fail_qty"
        + "   from return_quality_check_result"
        + "   where tenant_id = ?::uuid and check_outcome = 'FAIL'"
        + "   group by inspection_id) f on f.inspection_id = i.id"
        + inspectionWhere(window, extra);
  }

  /** 退货物流件数基数的窗口条件：实收行 → 质检单（inspection 侧持有 receipt_line_id）。 */
  private String receiptWhere(QualityWindow window) {
    return " where rrl.tenant_id = ?::uuid and i.inspection_status = 'CONCLUDED'"
        + window.sql();
  }

  /** 窗口绑值：**第一个永远是租户**，随后按 from → to 的顺序追加。 */
  private List<Object> windowBinds(QualityWindow window) {
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    binds.addAll(window.binds());
    return binds;
  }

  private String bucketExpression(String granularity) {
    String unit = normalizeGranularity(granularity);
    return "date_trunc('" + unit + "', i.concluded_at at time zone 'UTC')";
  }

  private String normalizeGranularity(String granularity) {
    if (!isSet(granularity)) {
      return "month";
    }
    String g = granularity.trim().toLowerCase(Locale.ROOT);
    if (!g.equals("week") && !g.equals("month") && !g.equals("day") && !g.equals("quarter")) {
      throw new IllegalArgumentException("时间桶只能是 day / week / month / quarter");
    }
    return g;
  }

  private String bucketLabel(LocalDate start, String granularity) {
    String unit = isSet(granularity) ? granularity.trim().toLowerCase(Locale.ROOT) : "month";
    return switch (unit) {
      case "day" -> start.toString();
      case "week" -> start + " 当周";
      case "quarter" -> start.getYear() + " Q" + ((start.getMonthValue() - 1) / 3 + 1);
      default -> start.getYear() + "-" + String.format("%02d", start.getMonthValue());
    };
  }

  // ================================================================ 映射与小工具

  private DefectObservationRow observationOf(UUID id) {
    Record r =
        sql.fetchOne(
            "select o.*, i.inspection_no, c.item_code, c.item_name, i.sku_code"
                + " from quality_defect_observation o"
                + " join return_quality_inspection i on i.id = o.inspection_id"
                + "   and i.tenant_id = o.tenant_id"
                + " left join return_quality_check_result c on c.id = o.check_result_id"
                + " where o.id = ?::uuid and o.tenant_id = ?::uuid",
            id,
            currentTenant());
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "缺陷观察不存在或不属于当前租户");
    }
    return toObservationRow(r);
  }

  private DefectObservationRow toObservationRow(Record r) {
    DefectDimension dimension = dimensionOf(r.get("defect_dimension", String.class));
    DiscoveryStage discovery = stageOf(r.get("discovery_stage", String.class));
    ObservationStatus status = observationStatusOf(r.get("observation_status", String.class));
    DefectSeverity severity =
        r.get("severity", String.class) == null
            ? null
            : severityOrThrow(r.get("severity", String.class));
    return new DefectObservationRow(
        r.get("id", UUID.class),
        r.get("observation_no", String.class),
        r.get("inspection_id", UUID.class),
        r.get("inspection_no", String.class),
        r.get("check_result_id", UUID.class),
        r.get("item_code", String.class),
        r.get("item_name", String.class),
        r.get("sku_code", String.class),
        r.get("defect_code", String.class),
        r.get("defect_name", String.class),
        dimension.name(),
        dimension.label(),
        discovery.name(),
        discovery.label(),
        r.get("discovered_at", OffsetDateTime.class),
        nz(r.get("affected_qty", BigDecimal.class)),
        severity == null ? null : severity.name(),
        severity == null ? null : severity.label(),
        status.name(),
        status.label(),
        r.get("observation_note", String.class),
        r.get("void_reason", String.class),
        r.get("version", Long.class));
  }

  private CostParameterRow parameterOf(UUID id) {
    Record r =
        sql.fetchOne(
            "select * from quality_cost_parameter where id = ?::uuid and tenant_id = ?::uuid",
            id,
            currentTenant());
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "成本参数不存在或不属于当前租户");
    }
    return toParameterRow(r);
  }

  private CostParameterRow toParameterRow(Record r) {
    boolean active = "ACTIVE".equals(r.get("parameter_status", String.class));
    return new CostParameterRow(
        r.get("id", UUID.class),
        r.get("currency", String.class).trim(),
        nz(r.get("return_logistics_per_case", BigDecimal.class)),
        nz(r.get("review_impact_per_defect", BigDecimal.class)),
        r.get("parameter_status", String.class),
        active ? "生效中" : "已换版",
        r.get("effective_from", LocalDate.class),
        r.get("effective_to", LocalDate.class),
        r.get("source_note", String.class),
        r.get("supersede_reason", String.class),
        r.get("version", Long.class));
  }

  private SupplierRefRow supplierRefOf(UUID id) {
    Record r =
        sql.fetchOne(
            "select * from quality_supplier_ref where id = ?::uuid and tenant_id = ?::uuid",
            id,
            currentTenant());
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "供应商引用不存在或不属于当前租户");
    }
    return toSupplierRefRow(r);
  }

  private SupplierRefRow toSupplierRefRow(Record r) {
    boolean active = "ACTIVE".equals(r.get("ref_status", String.class));
    return new SupplierRefRow(
        r.get("id", UUID.class),
        r.get("supplier_ref_id", UUID.class),
        r.get("supplier_code", String.class),
        r.get("supplier_name", String.class),
        r.get("ref_source", String.class),
        r.get("source_reference", String.class),
        r.get("ref_status", String.class),
        active ? "启用" : "停用",
        r.get("note", String.class));
  }

  private SupplierRefRow refOf(UUID supplierRefId) {
    if (supplierRefId == null) {
      return null;
    }
    Record r =
        sql.fetchOne(
            "select * from quality_supplier_ref where tenant_id = ?::uuid"
                + " and supplier_ref_id = ?::uuid",
            currentTenant(),
            supplierRefId);
    return r == null ? null : toSupplierRefRow(r);
  }

  private void writeEvent(String scope, String type, UUID subject, String note, UUID userId) {
    sql.execute(
        "insert into quality_analysis_event(id,tenant_id,event_scope,event_type,subject_id,"
            + "event_note,occurred_by,occurred_at,created_at)"
            + " values(?::uuid,?::uuid,?,?,?::uuid,?,?::uuid,?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        scope,
        type,
        subject,
        note == null ? "" : (note.length() > 1000 ? note.substring(0, 1000) : note),
        userId,
        OffsetDateTime.now(ZoneOffset.ofHours(8)),
        OffsetDateTime.now(ZoneOffset.ofHours(8)));
  }

  private UUID currentTenant() {
    UUID tenant =
        sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
            .get(0, UUID.class);
    if (tenant == null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "租户上下文缺失，拒绝读取质量数据");
    }
    return tenant;
  }

  private String nextObservationNo() {
    // 单号里带毫秒时间戳：同一租户并发登记时不会撞号（唯一索引是真正的兜底）。
    String stamp = String.valueOf(System.currentTimeMillis());
    return OBSERVATION_PREFIX + stamp.substring(stamp.length() - 9);
  }

  private static boolean isSet(String value) {
    return value != null && !value.trim().isEmpty();
  }

  private static String require(String value, String label, int max) {
    if (!isSet(value)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "必填");
    }
    String trimmed = value.trim();
    if (trimmed.length() > max) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, label + "长度不能超过 " + max + " 个字符");
    }
    return trimmed;
  }

  private static String optional(String value, int max, String label) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    if (trimmed.length() > max) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, label + "长度不能超过 " + max + " 个字符");
    }
    return trimmed;
  }

  private static int clamp(Integer value, int fallback) {
    if (value == null) {
      return fallback;
    }
    return Math.max(1, Math.min(value, MAX_REPORT_ROWS));
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  /**
   * 金额 / 件数的可读写法。
   *
   * <p>⚠️ <b>零值必须提前短路</b>：{@code BigDecimal.ZERO.stripTrailingZeros()}
   * 在部分 JDK 上会留下 scale 为 {@code Integer.MIN_VALUE} 的内部表示，
   * 后续任何 {@code add} 都会在 {@code BigDecimal} 内部抛
   * {@code Cannot read field "intCompact" because "augend" is null} ——
   * 报错指向 BigDecimal，看不出真正的原因是「这里传了个 0」。
   */
  private static String plain(BigDecimal value) {
    BigDecimal v = nz(value);
    return v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString();
  }

  /** 占比（0-100，两位小数）。分母为 0 时返回 0 而不是 100 —— 「没有数据」不是「占全部」。 */
  private static BigDecimal share(Number part, Number whole) {
    BigDecimal p = new BigDecimal(String.valueOf(part == null ? 0 : part));
    BigDecimal w = new BigDecimal(String.valueOf(whole == null ? 0 : whole));
    if (w.signum() == 0) {
      return BigDecimal.ZERO.setScale(SHARE_SCALE, RoundingMode.HALF_UP);
    }
    return p.multiply(HUNDRED)
        .divide(w, SHARE_SCALE, RoundingMode.HALF_UP);
  }

  /** 比率（0-100，两位小数），与 {@link #share} 同口径。 */
  private static BigDecimal rate(BigDecimal part, BigDecimal whole) {
    if (whole == null || whole.signum() == 0) {
      return BigDecimal.ZERO.setScale(SHARE_SCALE, RoundingMode.HALF_UP);
    }
    return nz(part)
        .multiply(HUNDRED)
        .divide(whole, SHARE_SCALE, RoundingMode.HALF_UP);
  }

  private static DefectDimension dimensionOf(String value) {
    try {
      return DefectDimension.valueOf(value);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("未知缺陷维度：" + value);
    }
  }

  private static DefectDimension dimensionOrThrow(String value) {
    if (!isSet(value)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺陷维度必填");
    }
    try {
      return DefectDimension.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "缺陷维度只能是 APPEARANCE / FUNCTION / ACCESSORY / PACKAGING（逐字对齐质检检项维度）");
    }
  }

  private static DiscoveryStage stageOf(String value) {
    try {
      return DiscoveryStage.valueOf(value);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("未知发现环节：" + value);
    }
  }

  private static DiscoveryStage discoveryStageOrThrow(String value) {
    if (!isSet(value)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "发现环节必填");
    }
    try {
      return DiscoveryStage.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "发现环节只能是 BUYER_UNBOXING / DELIVERY_SIGNATURE / WAREHOUSE_INTAKE / POST_USE");
    }
  }

  private static DefectSeverity severityOrThrow(String value) {
    if (!isSet(value)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "严重度必填");
    }
    try {
      return DefectSeverity.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "严重度只能是 MINOR / MODERATE / MAJOR / CRITICAL");
    }
  }

  private static ObservationStatus observationStatusOf(String value) {
    try {
      return ObservationStatus.valueOf(value);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("未知观察状态：" + value);
    }
  }

  private static ObservationStatus observationStatusOrThrow(String value) {
    try {
      return ObservationStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "状态只能是 RECORDED / VOID");
    }
  }

  private static AttributionStage attributionStageOf(String value) {
    return AttributionStage.valueOf(value.trim().toUpperCase(Locale.ROOT));
  }

  private static AttributionStage attributionStageOrThrow(String value) {
    try {
      return attributionStageOf(value);
    } catch (RuntimeException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "归因环节只能是 PRODUCTION / TRANSPORT / WAREHOUSE / DESCRIPTION / BUYER_FACTOR /"
              + " UNDETERMINED（逐字取自 11.1.2.2）");
    }
  }

  private static String stageLabelOf(String value) {
    if (value == null) {
      return "";
    }
    try {
      return attributionStageOf(value).label();
    } catch (RuntimeException e) {
      return value;
    }
  }
}
