package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 16.3.2.1 VAT/IOSS 的传输模型。
 *
 * <p>蓝图：「管理 VAT 与 IOSS 制度：适用店铺与商品、申报周期、税率适用、IOSS 订单识别与代收代缴统计、
 * 与财务申报数据的对接。输入：IOSS 配置；订单税务数据；12.6.1.2 申报底稿。
 * 输出：IOSS 订单清单与金额统计；申报状态。
 * 关系：上游 = 12.6.1.1 税率、9 订单；下游 = 12.6.1.2。
 * <b>IOSS 订单与一般 VAT 订单必须区分统计</b>，混在一起申报是最常见的错误。」
 *
 * <p>🔴 八个形状各自回答一个不同的问题：
 *
 * <ul>
 *   <li>{@link VatIossCapabilities} —— 「哪些上游真读得到、哪些下游没交付、判定的口径是什么」。</li>
 *   <li>{@link VatIossRegistrationView} —— 「这家店的 IOSS 登记覆盖了哪些店、哪些商品」。</li>
 *   <li>{@link VatIossFilingCycleView} —— 「这个辖区按什么周期申报」。</li>
 *   <li>{@link VatIossFilingPeriodView} —— 「这一期报到哪一步了」（申报状态）。</li>
 *   <li>{@link VatIossOrderView} —— 「这一单判成了 IOSS 还是一般 VAT、为什么」。</li>
 *   <li>{@link VatIossStatisticsRow} —— 「**这一档、这个币种**一共多少钱」（统计按币种分组）。</li>
 *   <li>{@link VatIossTaxRateView} —— 「这个辖区当期适用多少税率」（读 finance 12.6.1.1）。</li>
 *   <li>{@link VatIossOrderSyncResult} —— 「这次识别扫了多少单、跑了多少页、有没有截断」。</li>
 * </ul>
 *
 * <p>🔴 <b>「读不到」与「没有」是两个状态</b>：上游读不到时 {@code available=false} + 原因，
 * <b>不许</b>被显示成「不存在」「0%」或「未注册」。
 *
 * <p>🔴 <b>类型名带 {@code VatIoss} 前缀不是啰嗦</b>：全仓契约被 codegen 合并进
 * <b>一个扁平文件</b>，同名 key 后者覆盖前者且不报冲突 —— 叫 {@code Capabilities}
 * 会静默覆盖别的模块正在用的同名类型。
 */
public final class VatIossModels {

  private VatIossModels() {}

  // ---------------------------------------------------------------- 能力声明

  /**
   * 一个未交付的下游模块。
   *
   * @param moduleId 模块号（如 12.6.1.2）
   * @param moduleName 模块名
   * @param ownerModule 归谁（available=false 时必填）
   * @param available 当前是否已交付
   * @param impact 未交付 / 量不到的影响说明（界面原样显示）
   */
  public record VatIossCapabilityGap(
      String moduleId, String moduleName, String ownerModule, boolean available, String impact) {}

  /**
   * 一个上游的读取状态。
   *
   * @param moduleId 上游模块号
   * @param moduleName 上游模块名
   * @param available 本服务当前**读得到**吗（false ⇒ 界面显示「读不到」，**不是**「没有」）
   * @param ownerModule 该上游的归属模块
   * @param impact 读不到的影响说明
   */
  public record VatIossUpstream(
      String moduleId, String moduleName, boolean available, String ownerModule, String impact) {}

  /**
   * 能力声明。
   *
   * @param gaps 未交付的下游清单（逐条写清归谁、影响什么）
   * @param upstreams 上游读取状态
   * @param filingScopes 🔴 统计分档的**受控值域**（IOSS / 一般 VAT / 不在范围 / 判不了）
   * @param reasonCodes 判定原因词表
   * @param filingStatuses 申报状态词表
   * @param filingCycles 申报周期词表
   * @param taxTypes 税种词表
   * @param classificationSemantics 🔴 **判定口径**（IOSS 与一般 VAT 为什么必须分开）
   * @param scopeSemantics 适用店铺与商品的口径
   * @param filingSemantics 申报状态口径
   * @param currencySemantics 金额口径（按币种分组，不同币种绝不相加）
   * @param upstreamNote 上游说明
   * @param downstreamNote 下游说明
   * @param note 总口径说明
   */
  public record VatIossCapabilities(
      List<VatIossCapabilityGap> gaps,
      List<VatIossUpstream> upstreams,
      List<String> filingScopes,
      List<String> reasonCodes,
      List<String> filingStatuses,
      List<String> filingCycles,
      List<String> taxTypes,
      String classificationSemantics,
      String scopeSemantics,
      String filingSemantics,
      String currencySemantics,
      String upstreamNote,
      String downstreamNote,
      String note) {}

  // ---------------------------------------------------------------- 参考数据

  /**
   * 一个筛选项集合（前端下拉直接用，**不写死在前端**）。
   *
   * @param filingScopes 统计分档
   * @param reasonCodes 判定原因
   * @param filingStatuses 申报状态
   * @param filingCycles 申报周期
   * @param taxTypes 税种
   * @param jurisdictions 辖区选项（来自 16.3.1.1 码表，只读）
   * @param currencies 币种（本租户已出现的订单币种 + 门槛币种）
   */
  public record VatIossOptions(
      List<String> filingScopes,
      List<String> reasonCodes,
      List<String> filingStatuses,
      List<String> filingCycles,
      List<String> taxTypes,
      List<VatIossJurisdictionOption> jurisdictions,
      List<String> currencies) {}

  /**
   * 一个辖区选项（**实时读 16.3.1.1 的辖区码表**，不写死欧盟 27 国清单）。
   *
   * @param code 辖区码（如 EU-DE）
   * @param name 辖区名
   * @param countryCode 国家码
   * @param region 区域
   * @param taxRegime 税制
   * @param vatRequired 是否要求 VAT 登记号
   */
  public record VatIossJurisdictionOption(
      String code, String name, String countryCode, String region, String taxRegime, boolean vatRequired) {}

  // ---------------------------------------------------------------- IOSS 登记

  /**
   * 登记的适用店铺（一行一个）。
   *
   * @param platformCode 平台码
   * @param shopCode 店铺码（与 trade 的 platform_order.shop_code 同一口径）
   */
  public record VatIossShopScope(String platformCode, String shopCode) {}

  /**
   * 登记的适用商品（一行一个 SKU）。
   *
   * @param skuCode SKU 编码
   * @param goodsName 商品名
   * @param exciseGoods 是否应税消费品（酒 / 烟 / 能源）—— true ⇒ 该 SKU **不适用 IOSS**
   */
  public record VatIossGoodsScope(String skuCode, String goodsName, boolean exciseGoods) {}

  /**
   * 新建 / 修改一条 IOSS 登记的入参。
   *
   * @param registrationNo IOSS 登记号
   * @param status 状态（ACTIVE / DISABLED）
   * @param effectiveFrom 生效时刻（ISO-8601 带偏移量）
   * @param effectiveTo 生效止（空 = 当前生效；**填写即关闭当前登记**）
   * @param intermediaryName 中介服务机构（空 = 自己申报）
   * @param thresholdAmount 免征门槛金额（十进制字符串，**不是浮点**）
   * @param thresholdCurrency 门槛币种（默认 EUR）
   * @param shopScopes 适用店铺
   * @param goodsScopes 适用商品
   * @param note 备注
   */
  public record VatIossRegistrationInput(
      String registrationNo,
      String status,
      String effectiveFrom,
      String effectiveTo,
      String intermediaryName,
      String thresholdAmount,
      String thresholdCurrency,
      List<VatIossShopScope> shopScopes,
      List<VatIossGoodsScope> goodsScopes,
      String note) {}

  /**
   * 一条 IOSS 登记。
   *
   * @param id 登记 ID
   * @param registrationNo IOSS 登记号
   * @param status 状态
   * @param effectiveFrom 生效起
   * @param effectiveTo 生效止（空 = 当前生效）
   * @param current 是否当前生效的登记
   * @param intermediaryName 中介服务机构
   * @param thresholdAmount 免征门槛金额
   * @param thresholdCurrency 门槛币种
   * @param shopScopes 适用店铺
   * @param goodsScopes 适用商品
   * @param exciseSkuCodes 应税消费品 SKU 清单（**不适用 IOSS** 的那些）
   * @param note 备注
   * @param createdAt 创建时刻
   * @param updatedAt 更新时刻
   */
  public record VatIossRegistrationView(
      String id,
      String registrationNo,
      String status,
      String effectiveFrom,
      String effectiveTo,
      boolean current,
      String intermediaryName,
      String thresholdAmount,
      String thresholdCurrency,
      List<VatIossShopScope> shopScopes,
      List<VatIossGoodsScope> goodsScopes,
      List<String> exciseSkuCodes,
      String note,
      String createdAt,
      String updatedAt) {}

  /** IOSS 登记清单（真分页）。 */
  public record VatIossRegistrationPage(
      List<VatIossRegistrationView> items, int total, int page, int size) {}

  // ---------------------------------------------------------------- 申报周期

  /**
   * 登记 / 修改申报周期配置的入参。
   *
   * @param jurisdictionCode 辖区码（必须落在 16.3.1.1 码表里）
   * @param filingCycle 申报周期（MONTHLY / QUARTERLY / SEMI_ANNUAL / ANNUAL）
   * @param filingStartMonth 申报期起始月（1..12）
   * @param deadlineDay 申报截止日（1..28）
   * @param status 状态（ACTIVE / DISABLED）
   * @param note 备注
   */
  public record VatIossFilingCycleInput(
      String jurisdictionCode,
      String filingCycle,
      Integer filingStartMonth,
      Integer deadlineDay,
      String status,
      String note) {}

  /**
   * 一条申报周期配置。
   *
   * @param id 配置 ID
   * @param jurisdictionCode 辖区码
   * @param jurisdictionName 辖区名
   * @param countryCode 国家码
   * @param filingCycle 申报周期
   * @param filingCycleLabel 申报周期的人话
   * @param filingStartMonth 申报期起始月
   * @param deadlineDay 申报截止日
   * @param status 状态
   * @param note 备注
   * @param createdAt 创建时刻
   * @param updatedAt 更新时刻
   */
  public record VatIossFilingCycleView(
      String id,
      String jurisdictionCode,
      String jurisdictionName,
      String countryCode,
      String filingCycle,
      String filingCycleLabel,
      Integer filingStartMonth,
      Integer deadlineDay,
      String status,
      String note,
      String createdAt,
      String updatedAt) {}

  /** 申报周期配置清单（真分页）。 */
  public record VatIossFilingCyclePage(
      List<VatIossFilingCycleView> items, int total, int page, int size) {}

  // ---------------------------------------------------------------- 申报期

  /**
   * 开一期的入参。
   *
   * @param jurisdictionCode 辖区码
   * @param periodStart 申报期起（yyyy-MM-dd）
   * @param periodEnd 申报期止（yyyy-MM-dd，含）
   * @param note 备注
   */
  public record VatIossFilingPeriodInput(
      String jurisdictionCode, String periodStart, String periodEnd, String note) {}

  /**
   * 一期申报。
   *
   * @param id 申报期 ID
   * @param jurisdictionCode 辖区码
   * @param jurisdictionName 辖区名
   * @param countryCode 国家码
   * @param periodStart 申报期起
   * @param periodEnd 申报期止
   * @param status 申报状态（DRAFT 待提交 / DRAFTED 已出底稿 / CONFIRMED 已确认）
   * @param statusLabel 申报状态的人话
   * @param draftedAt 出底稿时刻
   * @param confirmedAt 确认时刻
   * @param confirmedBy 确认人
   * @param note 备注
   * @param createdAt 创建时刻
   * @param updatedAt 更新时刻
   */
  public record VatIossFilingPeriodView(
      String id,
      String jurisdictionCode,
      String jurisdictionName,
      String countryCode,
      String periodStart,
      String periodEnd,
      String status,
      String statusLabel,
      String draftedAt,
      String confirmedAt,
      String confirmedBy,
      String note,
      String createdAt,
      String updatedAt) {}

  /** 申报期清单（真分页 + 状态计数）。 */
  public record VatIossFilingPeriodPage(
      List<VatIossFilingPeriodView> items,
      int total,
      int page,
      int size,
      long draftCount,
      long draftedCount,
      long confirmedCount,
      String note) {}

  // ---------------------------------------------------------------- 订单

  /**
   * 一笔订单的 IOSS 判定结果。
   *
   * @param id 本模块的判定台账行 ID
   * @param sourceOrderId 上游 trade 平台单 ID（逻辑引用）
   * @param sourceSalesOrderId 上游统一销售订单 ID（逻辑引用）
   * @param sourceOrderNo 上游订单号
   * @param platformCode 平台码
   * @param shopCode 店铺码
   * @param orderSource 订单来源
   * @param orderStatus 平台单归一状态
   * @param customerType 买家类型（INDIVIDUAL / ORGANIZATION）
   * @param destinationCountry 收货国家
   * @param jurisdictionCode 辖区码（空 = 不是本模块跟踪的辖区）
   * @param orderDate 订单日期
   * @param currency 币种
   * @param goodsAmount 货值
   * @param shippingAmount 运费
   * @param taxBase 计税基数
   * @param taxAmount 🔴 代收代缴税额
   * @param taxRate 🔴 适用税率（**空 = 读不到，不是 0%**）
   * @param taxRateSource 税率来源（FINANCE / UNAVAILABLE）
   * @param filingScope 🔴 **统计分档**（IOSS / GENERAL_VAT / OUT_OF_SCOPE / UNRESOLVED）
   * @param filingScopeLabel 分档的人话
   * @param reasonCode 判定原因
   * @param reasonLabel 判定原因的人话
   * @param reasonDetail 判定说明（**界面上直接显示**）
   * @param syncedAt 识别时刻
   */
  public record VatIossOrderView(
      String id,
      String sourceOrderId,
      String sourceSalesOrderId,
      String sourceOrderNo,
      String platformCode,
      String shopCode,
      String orderSource,
      String orderStatus,
      String customerType,
      String destinationCountry,
      String jurisdictionCode,
      String orderDate,
      String currency,
      String goodsAmount,
      String shippingAmount,
      String taxBase,
      String taxAmount,
      @Schema(description = "适用税率；空 = **读不到**，绝不是 0%") String taxRate,
      String taxRateSource,
      String filingScope,
      String filingScopeLabel,
      String reasonCode,
      String reasonLabel,
      String reasonDetail,
      String syncedAt) {}

  /** 订单清单（真分页 + 分档计数）。 */
  public record VatIossOrderPage(
      List<VatIossOrderView> items,
      int total,
      int page,
      int size,
      long iossCount,
      long generalVatCount,
      long outOfScopeCount,
      long unresolvedCount,
      String note) {}

  /**
   * 一行订单明细（商品粒度）。
   *
   * @param lineNo 行号
   * @param skuCode SKU 编码
   * @param skuName 商品名
   * @param quantity 数量
   * @param unitPrice 单价
   * @param lineAmount 行金额
   * @param taxAmount 行税额
   * @param taxRate 行税率（空 = 上游没给，**不是 0%**）
   * @param exciseGoods 是否应税消费品（**不适用 IOSS**）
   */
  public record VatIossOrderLineView(
      int lineNo,
      String skuCode,
      String skuName,
      String quantity,
      String unitPrice,
      String lineAmount,
      String taxAmount,
      String taxRate,
      boolean exciseGoods) {}

  /** 单笔订单详情（判定 + 行明细）。 */
  public record VatIossOrderDetail(VatIossOrderView order, List<VatIossOrderLineView> lines) {}

  /**
   * 一次识别（同步）的结果。
   *
   * @param scanned 扫到的订单条数
   * @param classified 判定出的订单条数
   * @param iossCount 判成 IOSS 的条数
   * @param generalVatCount 判成一般 VAT 的条数
   * @param outOfScopeCount 判成不在范围的条数
   * @param unresolvedCount 判不了的条数
   * @param truncated 是否撞到翻页上限而没扫完（**如实报，不悄悄少算**）
   * @param pagesFetched 翻了多少页
   * @param note 口径说明
   */
  public record VatIossOrderSyncResult(
      long scanned,
      long classified,
      long iossCount,
      long generalVatCount,
      long outOfScopeCount,
      long unresolvedCount,
      boolean truncated,
      int pagesFetched,
      String note) {}

  // ---------------------------------------------------------------- 统计

  /**
   * 🔴 一组统计：**一个统计分档 × 一个币种**一行。
   *
   * <p>🔴 **不同币种绝不直接相加**（EUR + USD 不是钱）⇒ 币种是分组的**第一维**。
   *
   * @param filingScope 统计分档（IOSS / GENERAL_VAT / OUT_OF_SCOPE / UNRESOLVED）
   * @param filingScopeLabel 分档的人话
   * @param currency 币种
   * @param orderCount 订单条数
   * @param goodsAmount 货值合计
   * @param shippingAmount 运费合计
   * @param taxBase 计税基数合计
   * @param taxAmount 🔴 代收代缴税额合计
   */
  public record VatIossStatisticsRow(
      String filingScope,
      String filingScopeLabel,
      String currency,
      long orderCount,
      String goodsAmount,
      String shippingAmount,
      String taxBase,
      String taxAmount) {}

  /**
   * 🔴 统计结果（**分档 × 币种**分组 + 口径说明）。
   *
   * @param rows 分组明细
   * @param totalOrders 总条数
   * @param filingDraftAvailable 🔴 12.6.1.2 申报底稿**读得到**吗（false ⇒ 显示「读不到申报底稿」）
   * @param filingDraftOwnerModule 申报底稿归谁
   * @param filingDraftImpact 读不到的影响说明
   * @param note 口径说明
   */
  public record VatIossStatistics(
      List<VatIossStatisticsRow> rows,
      long totalOrders,
      boolean filingDraftAvailable,
      String filingDraftOwnerModule,
      String filingDraftImpact,
      String note) {}

  // ---------------------------------------------------------------- 税率

  /**
   * 一条税率解析结果（**来自 finance 12.6.1.1**，本模块只读）。
   *
   * @param available 🔴 读得到吗（false ⇒ {@code rate} 为空，**不是 0%**）
   * @param taxType 税种
   * @param jurisdictionCode 辖区码
   * @param jurisdictionName 辖区名
   * @param rate 税率（十进制字符串；空 = 读不到）
   * @param resolvedBy 命中方式（SHOP_BINDING / JURISDICTION）
   * @param asOf 取税率的时点（按生效时间取）
   * @param impact 读不到 / 命中不到的影响说明
   */
  public record VatIossTaxRateView(
      boolean available,
      String taxType,
      String jurisdictionCode,
      String jurisdictionName,
      String rate,
      String resolvedBy,
      String asOf,
      String impact) {}

  // ---------------------------------------------------------------- 店铺目录

  /**
   * 店铺目录里的一行（**直接读本服务自己的 {@code shop} 表**，不写死店铺常量）。
   *
   * @param shopId 店铺 ID
   * @param code 店铺码
   * @param name 店铺名
   * @param platformCode 平台码
   * @param platformName 平台名
   * @param marketplaceCodes 启用的站点编码
   * @param countryCodes 启用的站点国家
   * @param iossRegistrationId 命中的 IOSS 登记 ID（空 = 没有登记覆盖这家店）
   * @param iossRegistrationNo 命中的 IOSS 登记号（空 = 没有）
   * @param inIossScope 这家店是否落在当前生效的 IOSS 登记覆盖范围内
   */
  public record VatIossShopView(
      String shopId,
      String code,
      String name,
      String platformCode,
      String platformName,
      List<String> marketplaceCodes,
      List<String> countryCodes,
      String iossRegistrationId,
      String iossRegistrationNo,
      boolean inIossScope) {}

  /** 店铺目录清单。 */
  public record VatIossShopList(List<VatIossShopView> items, int total, String note) {}
}
