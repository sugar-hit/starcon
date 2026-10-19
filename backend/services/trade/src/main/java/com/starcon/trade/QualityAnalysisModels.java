package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 11.5.2.1 质量分析的传输模型。
 *
 * <p>蓝图 11-aftersales-and-returns.md 原文：
 *
 * <blockquote>
 * 职责：质量问题<b>专项分析</b>：缺陷类型分布、批次质量趋势、供应商质量对比、缺陷照片聚类、
 * <b>支持开箱率与质量成本估算</b>。
 * 输入：质检结论与退货原因；供应商与批次维度。
 * 输出：<b>质量报表 + 责任分布 + 改进清单</b>。
 * 关系：上游 = 11.1.2.2、11.2.3.1；下游 = 6.1.1.3 评级、13 分析。
 * <b>质量成本（退货物流 + 损耗 + 差评影响）应量化</b>，否则改进动力不足。
 * </blockquote>
 *
 * <p><b>本文件最重要的四件事</b>：
 *
 * <ol>
 *   <li><b>每一张报表都带 {@code generatedAt} 与 {@code basisNote}</b>。
 *       质量报表最容易被误读成「这个月的数字」，而「件数按哪段期间、
 *       分母是谁、损耗按哪一列」不定死，同一张表今天和下个月导出就会对不上。
 *       {@link QualityWindow} 把参考时刻<b>整页共用一个</b>：{@code at} 在每个报表方法
 *       开头取一次，行与汇总复用同一个值 —— 否则会「筛了本季度、第一行却是去年的」。</li>
 *   <li><b>成本分项必须带 {@link CostBasis} 三值</b>（实测 / 按参数估算 / 参数未登记）。
 *       蓝图加粗句要求「质量成本应量化」，而量化不等于编造：
 *       <b>损耗</b>（原值 − 残值）是 V179 里真实存在的账，标 {@code MEASURED}；
 *       <b>退货物流</b>与<b>差评影响</b>在库里<b>没有金额列</b>（全库无商品评价表，
 *       V161 的 rma_authorization 也只有 carrier / tracking_no 没有金额），
 *       只能按租户自维护参数乘出来，标 {@code ESTIMATED}；
 *       参数没登记时标 {@code NOT_REGISTERED} 而不是<b>悄悄按 0 算</b> ——
 *       按 0 算出来的「总成本」会被读成「质量没花钱」，那是反向造假。</li>
 *   <li><b>开箱率必须同时报覆盖率</b>（{@link OpenBoxReport#observationCoverage}）。
 *       分子是 {@code quality_defect_observation} 里标记为「买家开箱」的件数，
 *       分母是<b>已登记观察的件数</b>；而「已登记观察数 / FAIL 检项总数」这个覆盖率
 *       低于 100% 时，开箱率只是<b>已登记那部分的</b>开箱率。
 *       只报一个开箱率数字而不报覆盖率，等于让「还没登记」的沉默被读成「没开箱问题」。</li>
 *   <li><b>照片聚类不提供「相似度」</b>（{@link PhotoClusterRow}）。
 *       口径是<b>内容指纹（sha256）完全相同</b>，不是图像相似度：本仓没有图像库、
 *       没有模型、没有第三方视觉服务接入点。11.1.2.2 已经在
 *       {@code product_issue_photo_comparison} 上把这件事钉过一次，这里逐字沿用。</li>
 * </ol>
 *
 * <p><b>命名。</b>统一用 {@code Quality*} / {@code DefectObservation*} / {@code CostParameter*}
 * 前缀，与 {@code ReturnQuality*}（11.2.3.1）、{@code ProductIssue*}（11.1.2.2）零重叠 ——
 * 契约被 codegen 合并进一个扁平文件、同名后者覆盖前者且不报冲突。
 */
public final class QualityAnalysisModels {

  private QualityAnalysisModels() {}

  // ================================================================ 枚举

  /**
   * 缺陷维度 —— **逐字对齐** V179 的 {@code return_quality_check_result.item_dimension}。
   *
   * <p>刻意只保留这四个：V185 守卫③在 DB 层强制观察行的维度与检项<b>逐字一致</b>，
   * 任何新增取值都必须先回到 11.2.3.1 改模板，否则分布表会多出一个无法解释的桶。
   */
  public enum DefectDimension {
    APPEARANCE("外观"),
    FUNCTION("功能"),
    ACCESSORY("配件"),
    PACKAGING("包装");

    private final String label;

    DefectDimension(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 发现环节 —— 开箱率的唯一分子来源。
   *
   * <p>四个取值对应缺陷被发现的四个时点，<b>顺序就是时间先后</b>：
   * 买家开箱 → 签收 → 我们入库开箱 → 使用后。
   * 「买家开箱」排第一不是随意排：只有它早于实物回到卖家，
   * 也只有它能被称作「到手即损」，改进动作（换供应商 / 改包装 / 改设计）才找得到对象。
   */
  public enum DiscoveryStage {
    BUYER_UNBOXING("买家开箱即发现"),
    DELIVERY_SIGNATURE("签收时发现"),
    WAREHOUSE_INTAKE("入库开箱发现"),
    POST_USE("使用后发现");

    private final String label;

    DiscoveryStage(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /**
     * 是否计入开箱率分子。
     *
     * <p>{@code DELIVERY_SIGNATURE} <b>刻意不算</b>：货已经到买家手上，
     * 但它有没有被拆开验过没有任何证据 —— 记在签收环节的件可能是整箱签收后没拆，
     * 也可能是拆开才发现。把它算进开箱率会凭空抬高分子。
     */
    public boolean openBox() {
      return this == BUYER_UNBOXING;
    }

    /**
     * 是否算「到手即损」责任（与开箱率同一口径，供责任分布交叉引用）。
     *
     * <p>本仓<b>没有</b>平台评价表，所以「差评」这个事实本模块**不产出**，
     * 只在质量成本里按租户参数<b>估算其影响</b>。枚举里刻意不给「差评」一个取值 ——
     * 造一个 {@code NEGATIVE_REVIEW} 发现环节，等于声称我们读到过买家评价。
     */
    public boolean preArrival() {
      return this == BUYER_UNBOXING;
    }
  }

  /** 缺陷严重度。分四档而不是「有问题 / 没问题」两档：改进清单要按严重度排序。 */
  public enum DefectSeverity {
    MINOR("轻微"),
    MODERATE("一般"),
    MAJOR("严重"),
    CRITICAL("致命");

    private final String label;

    DefectSeverity(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 缺陷观察状态。作废是终态：判错了不要改，另记一条。 */
  public enum ObservationStatus {
    RECORDED("已登记"),
    VOID("已作废");

    private final String label;

    ObservationStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 只有有效行进统计；作废行只在留痕与明细里出现。 */
    public boolean counted() {
      return this == RECORDED;
    }
  }

  /**
   * 成本分项的依据等级 —— 这是本模块<b>最要紧的一个枚举</b>。
   *
   * <p>蓝图加粗句「质量成本（退货物流 + 损耗 + 差评影响）<b>应量化</b>」，
   * 但「量化」不等于「把三种来源不同的数字加在一起报一个总数」。
   * 前端必须按这个枚举给每一项打上不同的标签，
   * 否则一个把估算混进实测的总数，会被当成「财务确认过的损失」拿去谈供应商索赔。
   */
  public enum CostBasis {
    /** 实测：库里真有这两个数，相减即得（损耗 = 原值 − 残值，V179 真列）。 */
    MEASURED("实测"),

    /** 按参数估算：库里没有金额列，用租户自维护的单价乘件数算出来。 */
    ESTIMATED("按参数估算"),

    /** 参数未登记：<b>不是 0</b>，是「算不出来」，报表如实标出来而不是悄悄按 0 参与合计。 */
    NOT_REGISTERED("参数未登记");

    private final String label;

    CostBasis(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 是否允许参与合计。只有实测与估算可加；未登记必须被排除在合计之外。 */
    public boolean summable() {
      return this != NOT_REGISTERED;
    }
  }

  /** 报表的期间过滤（半开区间：[from, to)）。 */
  public record QualityWindow(
      @Schema(description = "起始日（含），null 表示不限")
          LocalDate from,
      @Schema(description = "截止日（不含），null 表示不限")
          LocalDate to,
      @Schema(description = "SKU 编码精确匹配，null 表示全部")
          String skuCode,
      @Schema(description = "质检类目 id，null 表示全部")
          UUID categoryId) {

    /** 半开区间 [from, to) —— 用「不含」而不是「不含或等于」，避免 to 与 from 同日时的零宽区间。 */
    public String sql() {
      StringBuilder s = new StringBuilder();
      if (from != null) {
        s.append(" and i.concluded_at >= ?::timestamptz");
      }
      if (to != null) {
        s.append(" and i.concluded_at < ?::timestamptz");
      }
      return s.isEmpty() ? "" : s.toString();
    }

    /**
     * 绑值顺序：先 from 再 to，缺哪个都不占位。
     *
     * <p>⚠️ 刻意返回 {@code List<Object>} 而不是 {@code Object[]}：
     * {@code List.of(Object[])} 遇到数组里的 {@code null} 会直接抛 NPE，
     * 而「只给了截止日」是完全合法的查询。
     */
    public List<Object> binds() {
      List<Object> binds = new ArrayList<>();
      if (from != null) {
        binds.add(from.atStartOfDay().atOffset(java.time.ZoneOffset.UTC).toInstant());
      }
      if (to != null) {
        binds.add(to.atStartOfDay().atOffset(java.time.ZoneOffset.UTC).toInstant());
      }
      return binds;
    }

    /** 期间的人话描述，原样出现在每个响应的 {@code windowNote} 里。 */
    public String note() {
      if (from == null && to == null) {
        return "**未限定期间** —— 统计范围是本租户全部已判定质检单。";
      }
      String f = from == null ? "最早" : from.toString();
      String t = to == null ? "至今" : to.toString() + "（不含）";
      return "统计期间 " + f + " → " + t + "（半开区间，**截止日不含**，按 UTC 日界）。";
    }
  }

  // ================================================================ 请求

  /**
   * 登记缺陷观察。
   *
   * <p>⚠️ <b>不传</b> {@code observationStatus} / {@code observationNo} / {@code recordedBy}：
   * 建行恒为 {@code RECORDED}，单号由服务层生成，作废只走
   * {@code /defect-observations/{id}/void} 一个入口。
   *
   * <p>⚠️ <b>不传</b> {@code defectName} 之外的任何统计列：影响件数是输入（人填的），
   * 但<b>它不得超过该质检单的实检数量</b>（V185 守卫⑤），
   * 也不得把一条 PASS 检项记成缺陷（守卫②）—— 这两条 DB 与服务层各判一次。
   */
  public record DefectObservationCreate(
      @Schema(description = "质检单 id（11.2.3.1）") UUID inspectionId,
      @Schema(description = "判 FAIL 的检项 id；V185 守卫②在 DB 层再钉一次") UUID checkResultId,
      @Schema(description = "可选：关联归因单（11.1.2.2）") UUID attributionId,
      @Schema(description = "缺陷编码") String defectCode,
      @Schema(description = "缺陷名称") String defectName,
      @Schema(description = "缺陷维度，逐字对齐检项维度", required = true) String defectDimension,
      @Schema(description = "发现环节", required = true) String discoveryStage,
      @Schema(description = "发现时刻（事实发生的那一刻，不是录入时刻）", required = true)
          OffsetDateTime discoveredAt,
      @Schema(description = "影响件数（不得超过实检数量）", required = true)
          BigDecimal affectedQty,
      @Schema(description = "严重度 MINOR/MODERATE/MAJOR/CRITICAL", required = true) String severity,
      @Schema(description = "备注") String observationNote) {}

  /** 作废缺陷观察（终态：作废后业务列冻结，另记一条即可）。 */
  public record DefectObservationVoid(
      @Schema(description = "作废理由（必填）", required = true) String voidReason,
      @Schema(description = "乐观锁版本号", required = true) long version) {}

  /**
   * 建质量成本参数。
   *
   * <p>⚠️ <b>不传</b> {@code parameterStatus} / {@code supersededBy}：
   * 建行恒为 {@code ACTIVE}，且 V185 用<b>部分唯一索引</b>保证每币种同时只有一份生效参数 ——
   * 两份同时生效时「按哪一份算」没有唯一答案。
   *
   * <p>⚠️ <b>不传</b> {@code effectiveTo}：截止日由换版时写，不在建行时预设。
   */
  public record CostParameterCreate(
      @Schema(description = "币种（三字母大写）", required = true) String currency,
      @Schema(description = "每一件实收退货的买家回寄物流成本", required = true)
          BigDecimal returnLogisticsPerCase,
      @Schema(description = "每一件缺陷的差评影响估算金额", required = true)
          BigDecimal reviewImpactPerDefect,
      @Schema(description = "生效起始日", required = true) LocalDate effectiveFrom,
      @Schema(description = "口径说明（这个单价是怎么来的）") String sourceNote) {}

  /** 成本参数换版（终态：换版即留痕，V185 守卫⑦）。 */
  public record CostParameterSupersede(
      @Schema(description = "换版理由（必填）", required = true) String supersedeReason,
      @Schema(description = "乐观锁版本号", required = true) long version) {}

  /**
   * 登记 / 更新供应商引用。
   *
   * <p>⚠️ <b>刻意不接</b> supplier 编码的「新增/删除」语义：
   * 供应商主数据在 catalog 独立库，本表只是<b>显示用的镜像</b>，
   * 所以服务层只做 upsert + 停用，<b>不提供删除</b>（V185 已把 DELETE 从运行角色收回）。
   */
  public record SupplierRefUpsert(
      @Schema(description = "批次上的供应商引用 id（与 product_issue_* 的 lot_supplier_ref_id 同值）",
              required = true)
          UUID supplierRefId,
      @Schema(description = "供应商编码") String supplierCode,
      @Schema(description = "供应商名称") String supplierName,
      @Schema(
              description = "来源 TENANT_MAINTAINED / MODULE_6_PURCHASE_SYNC；后者必须带 sourceReference",
              required = true)
          String refSource,
      @Schema(description = "来源引用（同步器必填）") String sourceReference,
      @Schema(description = "备注") String note) {}

  /** 停用 / 启用供应商引用（停用是留痕的，删除不是）。 */
  public record SupplierRefStatus(
      @Schema(description = "ACTIVE / DISABLED", required = true) String refStatus) {}

  // ================================================================ 响应：报表

  /** 能力声明。职责句的六项分析逐块报可用性 + 归属模块 + 口径说明。 */
  public record QualityAnalysisCapabilities(
      @Schema(description = "上游数据源（模块号 + 落库位置）") List<String> dataSources,
      @Schema(description = "职责句六项逐块可用性") List<QualityAnalysisSection> sections,
      @Schema(description = "上下游模块") List<QualityAnalysisRelatedModule> relatedModules,
      @Schema(description = "本模块端点") List<QualityAnalysisAction> actions,
      @Schema(description = "本模块刻意不做的事") List<String> boundaries,
      @Schema(description = "开箱率口径说明") String openBoxNote,
      @Schema(description = "质量成本口径说明（哪几项实测、哪几项估算）") String costNote,
      @Schema(description = "照片聚类口径说明") String photoClusterNote,
      @Schema(description = "跨服务边界说明") String crossServiceNote) {}

  @Schema(description = "职责句的一块分析")
  public record QualityAnalysisSection(
      @Schema(description = "块 key") String key,
      @Schema(description = "块名") String title,
      @Schema(description = "是否已交付") boolean available,
      @Schema(description = "归属模块号") String ownerModule,
      @Schema(description = "事实来源 / 口径") String basis,
      @Schema(description = "归属系统") String ownerSystem) {}

  @Schema(description = "上下游模块")
  public record QualityAnalysisRelatedModule(
      @Schema(description = "模块号") String moduleId,
      @Schema(description = "模块名") String title,
      @Schema(description = "是否已交付") boolean delivered,
      @Schema(description = "归属系统（未交付时必填）") String ownerSystem,
      @Schema(description = "与本模块的关系") String relation,
      @Schema(description = "页面入口（已交付且同应用时填）") String path) {}

  @Schema(description = "本模块的一个端点")
  public record QualityAnalysisAction(
      @Schema(description = "动作 key") String key,
      @Schema(description = "动作名") String title,
      @Schema(description = "是否已交付") boolean available,
      @Schema(description = "HTTP 方法") String method,
      @Schema(description = "路径") String path,
      @Schema(description = "是否只读") boolean readOnly,
      @Schema(description = "前置条件") String precondition,
      @Schema(description = "拒绝理由（权限不足时填）") String denialHint,
      @Schema(description = "被谁授权") String permission) {}

  // ---------------------------------------------------------------- 总览

  @Schema(description = "质量总览")
  public record QualityOverview(
      @Schema(description = "参考时刻（整页共用）") OffsetDateTime generatedAt,
      @Schema(description = "统计期间的人话描述") String windowNote,
      @Schema(description = "已判定质检单数") long concludedInspections,
      @Schema(description = "已判定质检单覆盖的件数") BigDecimal inspectedQty,
      @Schema(description = "缺陷件数（FAIL 检项涉及的单件数合计）") BigDecimal defectiveQty,
      @Schema(description = "非可再销售件数（DEFECTIVE + SCRAPPED）") BigDecimal nonResalableQty,
      @Schema(description = "非可再销售率（0-100，两位小数）") BigDecimal nonResalableRate,
      @Schema(description = "开箱率（0-100，两位小数；仅已登记观察范围内）") BigDecimal openBoxRate,
      @Schema(description = "缺陷观察覆盖率（0-100，两位小数）") BigDecimal observationCoverage,
      @Schema(description = "责任分布（六环节）") List<ResponsibilitySlice> responsibility,
      @Schema(description = "批次标记 SUSPECT / CONFIRMED 数") LotFlagCounts lotFlags,
      @Schema(description = "成本合计（只含可加项，逐币种）") List<CostTotalRow> costTotals,
      @Schema(description = "总览口径说明") String basisNote) {}

  @Schema(description = "责任分布的一个环节切片")
  public record ResponsibilitySlice(
      @Schema(description = "归因环节（逐字取自 V180 的六值）") String stage,
      @Schema(description = "环节中文名") String stageLabel,
      @Schema(description = "归因单数") long attributionCount,
      @Schema(description = "涉及件数") BigDecimal attributedQty,
      @Schema(description = "占比（0-100，两位小数）") BigDecimal share) {}

  @Schema(description = "批次标记计数")
  public record LotFlagCounts(
      @Schema(description = "有标记的批次数") long flaggedLots,
      @Schema(description = "其中 SUSPECT") long suspectLots,
      @Schema(description = "其中 CONFIRMED") long confirmedLots) {}

  // ---------------------------------------------------------------- 缺陷类型分布

  @Schema(description = "缺陷类型分布")
  public record DefectDistributionReport(
      @Schema(description = "参考时刻（整页共用）") OffsetDateTime generatedAt,
      @Schema(description = "统计期间的人话描述") String windowNote,
      @Schema(description = "分布口径说明") String basisNote,
      @Schema(description = "总计") DefectDistributionTotals totals,
      List<DefectDimensionSlice> dimensions,
      List<DefectTypeRow> types) {}

  @Schema(description = "缺陷分布总计")
  public record DefectDistributionTotals(
      @Schema(description = "参与统计的已判定质检单数") long inspectionCount,
      @Schema(description = "FAIL 检项总条数") long failCheckCount,
      @Schema(description = "涉及的质检单数（去重）") long affectedInspectionCount,
      @Schema(description = "失败检项涉及件数") BigDecimal affectedQty,
      @Schema(description = "缺陷原值合计（已判定质检单的原值）") BigDecimal originalValue,
      @Schema(description = "不合格检项原值占比（0-100，两位小数）") BigDecimal defectiveValueShare) {}

  @Schema(description = "按维度的切片")
  public record DefectDimensionSlice(
      @Schema(description = "维度") String dimension,
      @Schema(description = "维度中文名") String dimensionLabel,
      @Schema(description = "FAIL 检项条数") long failCheckCount,
      @Schema(description = "占比（0-100，两位小数）") BigDecimal share) {}

  @Schema(description = "一个缺陷类型（检项粒度）")
  public record DefectTypeRow(
      @Schema(description = "维度") String dimension,
      @Schema(description = "维度中文名") String dimensionLabel,
      @Schema(description = "检项编码（逐字取自 V179 的 item_code）") String itemCode,
      @Schema(description = "检项名称") String itemName,
      @Schema(description = "FAIL 条数") long failCount,
      @Schema(description = "涉及质检单数") long inspectionCount,
      @Schema(description = "涉及件数") BigDecimal affectedQty,
      @Schema(description = "占全部 FAIL 条数比（0-100，两位小数）") BigDecimal share,
      @Schema(description = "开箱发现件数（已登记观察范围内）") BigDecimal openBoxQty,
      @Schema(description = "责任人建议：按严重度最高的观察给一条") String topSeverity) {}

  // ---------------------------------------------------------------- 批次质量趋势

  @Schema(description = "批次质量趋势")
  public record LotTrendReport(
      @Schema(description = "参考时刻（整页共用）") OffsetDateTime generatedAt,
      @Schema(description = "统计期间的人话描述") String windowNote,
      @Schema(description = "趋势口径说明") String basisNote,
      @Schema(description = "时间桶粒度") String granularity,
      @Schema(description = "被排序限定的批次数") int lotCount,
      @Schema(description = "总计") LotTrendTotals totals,
      List<LotTrendBucket> buckets) {}

  @Schema(description = "批次趋势总计")
  public record LotTrendTotals(
      @Schema(description = "已判定质检单数") long inspectionCount,
      @Schema(description = "件数") BigDecimal inspectedQty,
      @Schema(description = "非可再销售件数") BigDecimal nonResalableQty,
      @Schema(description = "非可再销售率（0-100，两位小数）") BigDecimal nonResalableRate) {}

  @Schema(description = "一个时间桶")
  public record LotTrendBucket(
      @Schema(description = "桶起点（含）") LocalDate bucketStart,
      @Schema(description = "桶标签") String label,
      @Schema(description = "已判定质检单数") long inspectionCount,
      @Schema(description = "件数") BigDecimal inspectedQty,
      @Schema(description = "非可再销售件数") BigDecimal nonResalableQty,
      @Schema(description = "非可再销售率（0-100，两位小数）") BigDecimal nonResalableRate,
      @Schema(description = "涉及批次数") long lotCount,
      @Schema(description = "判 FAIL 的检项条数（**不是商品件数**）") BigDecimal defectiveQty) {}

  // ---------------------------------------------------------------- 供应商质量对比

  @Schema(description = "供应商质量对比")
  public record SupplierComparisonReport(
      @Schema(description = "参考时刻（整页共用）") OffsetDateTime generatedAt,
      @Schema(description = "统计期间的人话描述") String windowNote,
      @Schema(description = "对比口径说明") String basisNote,
      @Schema(description = "纳入对比的供应商数") int supplierCount,
      @Schema(description = "名称未登记的供应商数（显示为「未登记供应商」）") int unnamedCount,
      List<SupplierQualityRow> suppliers) {}

  @Schema(description = "一个供应商的质量对比行")
  public record SupplierQualityRow(
      @Schema(description = "供应商引用 id（批次上的 lot_supplier_ref_id）") UUID supplierRefId,
      @Schema(description = "供应商编码（未登记时为空串）") String supplierCode,
      @Schema(
              nullable = true,
              description = "供应商名称；未登记时为 null 而非 uuid —— 把 uuid 当名字显示会被当成供应商名")
          String supplierName,
      @Schema(description = "引用是否已登记") boolean registered,
      @Schema(description = "涉及批次数") long lotCount,
      @Schema(description = "归因到生产的件数") BigDecimal productionQty,
      @Schema(description = "归因到运输的件数") BigDecimal transportQty,
      @Schema(description = "归因到仓储的件数") BigDecimal warehouseQty,
      @Schema(description = "归因到描述的件数") BigDecimal descriptionQty,
      @Schema(description = "归因到买家因素/待定的件数") BigDecimal otherQty,
      @Schema(description = "生产责任占比（0-100，两位小数）") BigDecimal productionShare,
      @Schema(description = "被标记为质量问题的批次数（SUSPECT + CONFIRMED）") long flaggedLots,
      @Schema(description = "改进建议条数") long improvementCount) {}

  // ---------------------------------------------------------------- 缺陷照片聚类

  @Schema(description = "缺陷照片聚类")
  public record PhotoClusterReport(
      @Schema(description = "参考时刻（整页共用）") OffsetDateTime generatedAt,
      @Schema(description = "聚类口径说明") String basisNote,
      @Schema(description = "参与聚类的照片数") long photoCount,
      @Schema(description = "聚类簇数") int clusterCount,
      @Schema(description = "被两张及以上照片共享的簇数（真正的「重复出现」）") int repeatedClusterCount,
      List<PhotoClusterRow> clusters) {}

  @Schema(description = "一个指纹簇")
  public record PhotoClusterRow(
      @Schema(description = "内容指纹（sha256，聚类键）") String contentSha256,
      @Schema(description = "簇内照片数") int photoCount,
      @Schema(description = "簇内归因单数") long attributionCount,
      @Schema(description = "涉及批次数") long lotCount,
      @Schema(description = "涉及 SKU 数") long skuCount,
      @Schema(description = "涉及环节（逗号分隔，取自 V180 六值）") String stages,
      @Schema(description = "是否跨归因单重复出现（同一张图被用在多个单子上）") boolean repeated,
      @Schema(description = "簇内最早拍摄时刻") OffsetDateTime firstCapturedAt,
      @Schema(description = "簇内对象存储定位符（逗号分隔，最多 5 个）") String sampleUris) {}

  // ---------------------------------------------------------------- 开箱率

  @Schema(description = "开箱率（到手即损）")
  public record OpenBoxReport(
      @Schema(description = "参考时刻（整页共用）") OffsetDateTime generatedAt,
      @Schema(description = "统计期间的人话描述") String windowNote,
      @Schema(description = "开箱率口径说明") String basisNote,
      @Schema(description = "期间内已判定质检单的 FAIL 检项条数") long failCheckCount,
      @Schema(description = "已登记观察条数") long observationCount,
      @Schema(description = "观察覆盖率（0-100，两位小数）") BigDecimal observationCoverage,
      @Schema(description = "已登记观察的件数合计") BigDecimal observedQty,
      @Schema(description = "其中买家开箱发现的件数") BigDecimal openBoxQty,
      @Schema(description = "开箱率（0-100，两位小数；分母=已登记观察件数）") BigDecimal openBoxRate,
      @Schema(description = "分环节件数分布") List<DiscoveryStageSlice> stages,
      @Schema(description = "按 SKU 下钻") List<OpenBoxSkuRow> bySku) {}

  @Schema(description = "一个发现环节的切片")
  public record DiscoveryStageSlice(
      @Schema(description = "发现环节") String discoveryStage,
      @Schema(description = "环节中文名") String stageLabel,
      @Schema(description = "观察条数") long observationCount,
      @Schema(description = "件数") BigDecimal affectedQty,
      @Schema(description = "占已登记观察件数比（0-100，两位小数）") BigDecimal share) {}

  @Schema(description = "按 SKU 的开箱率下钻")
  public record OpenBoxSkuRow(
      @Schema(description = "SKU id") UUID skuId,
      @Schema(description = "SKU 编码") String skuCode,
      @Schema(description = "SKU 名称") String skuName,
      @Schema(description = "已登记观察条数") long observationCount,
      @Schema(description = "已登记观察件数") BigDecimal observedQty,
      @Schema(description = "买家开箱件数") BigDecimal openBoxQty,
      @Schema(description = "开箱率（0-100，两位小数）") BigDecimal openBoxRate,
      @Schema(description = "覆盖率（0-100，两位小数）") BigDecimal observationCoverage) {}

  // ---------------------------------------------------------------- 质量成本估算

  @Schema(description = "质量成本估算")
  public record QualityCostReport(
      @Schema(description = "参考时刻（整页共用）") OffsetDateTime generatedAt,
      @Schema(description = "统计期间的人话描述") String windowNote,
      @Schema(description = "成本口径说明") String basisNote,
      @Schema(description = "已登记的生效成本参数（按币种）") List<CostParameterRow> parameters,
      @Schema(description = "逐币种分项与合计") List<CostCurrencyBreakdown> currencies,
      @Schema(description = "本模块不产出「差评」这个事实的原因说明") String reviewNote) {}

  @Schema(description = "一个币种的成本分项")
  public record CostCurrencyBreakdown(
      @Schema(description = "币种") String currency,
      @Schema(description = "损耗（实测）") CostItem loss,
      @Schema(description = "退货物流（按参数估算）") CostItem returnLogistics,
      @Schema(description = "差评影响（按参数估算）") CostItem reviewImpact,
      @Schema(description = "可加项合计（NOT_REGISTERED 项被排除在外）") BigDecimal total,
      @Schema(description = "被排除在合计外的项数") int excludedItemCount) {}

  /**
   * 一个成本分项。
   *
   * <p>⚠️ {@code amount} / {@code basisQty} / {@code unitPrice} 三个字段<b>刻意可空</b>，
   * 且必须在契约里标出来：{@code NOT_REGISTERED}（参数未登记）时金额是
   * <b>null 而不是 0</b> —— 「算不出来」与「算出来是 0」是完全不同的两件事。
   * 契约里写成非空 number，前端就只会被迫把它当 0 渲染，
   * 于是「质量没花钱」这句话从一个 bug 变成了界面上的事实。
   */
  @Schema(description = "一个成本分项")
  public record CostItem(
      @Schema(description = "分项 key") String key,
      @Schema(description = "分项中文名") String label,
      @Schema(description = "依据等级（实测 / 按参数估算 / 参数未登记）") String basis,
      @Schema(description = "依据中文名") String basisLabel,
      @Schema(nullable = true, description = "金额；参数未登记时为 **null 而不是 0**")
          BigDecimal amount,
      @Schema(nullable = true, description = "计算基数") BigDecimal basisQty,
      @Schema(description = "基数口径的人话说明") String basisQtyNote,
      @Schema(nullable = true, description = "单价（估算项才有）") BigDecimal unitPrice,
      @Schema(description = "公式（人话）") String formula) {}

  @Schema(description = "成本合计行")
  public record CostTotalRow(
      @Schema(description = "币种") String currency,
      @Schema(description = "可加项合计") BigDecimal amount,
      @Schema(description = "被排除的项数") int excludedItemCount,
      @Schema(description = "本币种成本的依据等级（混合时取最弱的那个）") String basis,
      @Schema(description = "依据中文名") String basisLabel) {}

  // ---------------------------------------------------------------- 责任分布 + 改进清单

  @Schema(description = "责任分布 + 改进清单")
  public record ImprovementReport(
      @Schema(description = "参考时刻（整页共用）") OffsetDateTime generatedAt,
      @Schema(description = "清单口径说明") String basisNote,
      @Schema(description = "归因单数（当前生效版本）") long attributionCount,
      @Schema(description = "改进清单条数") int itemCount,
      @Schema(description = "责任分布") List<ResponsibilitySlice> responsibility,
      List<ImprovementRow> items) {}

  @Schema(description = "一条改进清单项")
  public record ImprovementRow(
      @Schema(description = "归因单 id") UUID attributionId,
      @Schema(description = "归因单号") String attributionNo,
      @Schema(description = "批次编码") String lotCode,
      @Schema(description = "SKU 编码") String skuCode,
      @Schema(description = "SKU 名称") String skuName,
      @Schema(description = "归因环节") String stage,
      @Schema(description = "环节中文名") String stageLabel,
      @Schema(description = "涉及件数") BigDecimal attributedQty,
      @Schema(description = "归因理由") String attributionNote,
      @Schema(description = "改进建议（逐字取自 11.1.2.2 的必填人写文字）")
          String improvementSuggestion,
      @Schema(nullable = true, description = "该批次是否已被标记为质量问题")
          Boolean lotFlagged,
      @Schema(nullable = true, description = "批次标记状态（未标记时为 null）")
          String lotFlagStatus) {}

  // ---------------------------------------------------------------- 明细：缺陷观察

  @Schema(description = "缺陷观察列表")
  public record DefectObservationPage(
      List<DefectObservationRow> rows,
      @Schema(description = "页号（0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "总条数") long total,
      @Schema(description = "统计口径说明") String basisNote,
      @Schema(description = "参考时刻") OffsetDateTime generatedAt) {}

  @Schema(description = "一条缺陷观察")
  public record DefectObservationRow(
      @Schema(description = "观察 id") UUID id,
      @Schema(description = "观察单号") String observationNo,
      @Schema(description = "质检单 id") UUID inspectionId,
      @Schema(description = "质检单号") String inspectionNo,
      @Schema(description = "检项 id") UUID checkResultId,
      @Schema(description = "检项编码") String itemCode,
      @Schema(description = "检项名称") String itemName,
      @Schema(description = "SKU 编码") String skuCode,
      @Schema(description = "缺陷编码") String defectCode,
      @Schema(description = "缺陷名称") String defectName,
      @Schema(description = "缺陷维度") String defectDimension,
      @Schema(description = "维度中文名") String defectDimensionLabel,
      @Schema(description = "发现环节") String discoveryStage,
      @Schema(description = "环节中文名") String discoveryStageLabel,
      @Schema(description = "发现时刻") OffsetDateTime discoveredAt,
      @Schema(description = "影响件数") BigDecimal affectedQty,
      @Schema(nullable = true, description = "严重度") String severity,
      @Schema(nullable = true, description = "严重度中文名") String severityLabel,
      @Schema(description = "状态") String observationStatus,
      @Schema(description = "状态中文名") String observationStatusLabel,
      @Schema(description = "备注") String observationNote,
      @Schema(description = "作废理由") String voidReason,
      @Schema(description = "版本号") long version) {}

  // ---------------------------------------------------------------- 明细：成本参数

  @Schema(description = "成本参数列表")
  public record CostParameterPage(
      List<CostParameterRow> rows, int page, int size, long total, String basisNote) {}

  @Schema(description = "一条成本参数")
  public record CostParameterRow(
      @Schema(description = "参数 id") UUID id,
      @Schema(description = "币种") String currency,
      @Schema(description = "每件退货物流成本") BigDecimal returnLogisticsPerCase,
      @Schema(description = "每件缺陷的差评影响估算") BigDecimal reviewImpactPerDefect,
      @Schema(description = "状态") String parameterStatus,
      @Schema(description = "状态中文名") String parameterStatusLabel,
      @Schema(description = "生效起始日") LocalDate effectiveFrom,
      @Schema(description = "生效截止日") LocalDate effectiveTo,
      @Schema(description = "口径说明") String sourceNote,
      @Schema(description = "换版理由") String supersedeReason,
      @Schema(description = "版本号") long version) {}

  // ---------------------------------------------------------------- 明细：供应商引用

  @Schema(description = "供应商引用列表")
  public record SupplierRefPage(
      List<SupplierRefRow> rows, int page, int size, long total, String basisNote) {}

  @Schema(description = "一条供应商引用")
  public record SupplierRefRow(
      @Schema(description = "记录 id") UUID id,
      @Schema(description = "供应商引用 id（批次上的 lot_supplier_ref_id）") UUID supplierRefId,
      @Schema(description = "供应商编码") String supplierCode,
      @Schema(description = "供应商名称") String supplierName,
      @Schema(description = "来源") String refSource,
      @Schema(description = "来源引用") String sourceReference,
      @Schema(description = "状态") String refStatus,
      @Schema(description = "状态中文名") String refStatusLabel,
      @Schema(description = "备注") String note) {}
}
