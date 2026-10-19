package com.starcon.trade;

import static com.starcon.trade.QualityAnalysisModels.*;

import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 11.5.2.1 质量分析。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「质量问题<b>专项分析</b>：缺陷类型分布、批次质量趋势、
 * 供应商质量对比、缺陷照片聚类、<b>支持开箱率与质量成本估算</b>。」
 * 输出：「<b>质量报表 + 责任分布 + 改进清单</b>。」
 * 加粗句：「<b>质量成本（退货物流 + 损耗 + 差评影响）应量化</b>，否则改进动力不足。」
 *
 * <p><b>命名空间独立于 {@code /trade/return-quality}</b>（11.2.3.1 质检单）
 * 与 {@code /trade/product-attribution}</b>（11.1.2.2 归因单）：
 * 那两个是<b>单据</b>，本模块是<b>聚合报表</b>。质检单判「这批货怎么样」、
 * 归因单判「这事该谁负责」，本模块答「整体坏得怎么样、钱花了多少、接下来先动哪几个批次」。
 * 三者形状不同，硬塞进一个命名空间只会让「单据列表」和「分析报表」互相污染。
 *
 * <p><b>权限码沿用</b> {@code trade.aftersales.read} / {@code trade.aftersales.write}：
 * 本模块是 11.5 售后分析锚点下的动作，与售后单同一业务面。
 *
 * <p><b>刻意不提供</b>：供应商评级写入（6.1.1.3）、索赔发起（6.4.2.2）、
 * 跨系统经营分析（13）—— 三者<b>均未交付</b>，capabilities 逐条报 delivered=false 与归属模块。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} / {@link #write} 先向 identity 确认已认证成员关系，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 报表类端点<b>没有按 id 直达的路径</b>（除缺陷观察的作废外）——
 * 报表的粒度是租户自己的数据，拿别的租户的缺陷观察 id 来作废，
 * 得到的是与「不存在」完全相同的错误。
 */
@RestController
@RequestMapping("/trade/quality-analysis")
public class QualityAnalysisController {

  private static final String READ = "aftersales.read";
  private static final String WRITE = "aftersales.write";

  private final AccessService access;
  private final QualityAnalysisService analysis;

  public QualityAnalysisController(AccessService access, QualityAnalysisService analysis) {
    this.access = access;
    this.analysis = analysis;
  }

  /** 统一读入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + READ);
    return access.run(actor, "trade." + READ, () -> work.apply(actor));
  }

  /** 写入口：需要 {@code trade.aftersales.write}。租户来源与读入口完全一致。 */
  private <T> T write(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + WRITE);
    return access.run(actor, "trade." + WRITE, () -> work.apply(actor));
  }

  /** 期间参数。三个筛选项（from / to / skuCode）逐个读，避免一串 {@code String} 参数传错位。 */
  private QualityWindow window(
      String from, String to, String skuCode, UUID categoryId) {
    return new QualityWindow(
        parseDate(from, "from"), parseDate(to, "to"), skuCode, categoryId);
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：职责句的六项分析逐块报可用性与口径，开箱率 / 成本 / 照片聚类三条口径说明原样报出，
   * 上下游逐个报 delivered 与归属模块。
   */
  @GetMapping("/capabilities")
  public QualityAnalysisCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> analysis.capabilities());
  }

  // ================================================================ 报表（只读）

  /** 质量总览：一屏看完「坏多少、坏在谁手上、发现了多少钱」。 */
  @GetMapping("/overview")
  public QualityOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String skuCode) {
    return call(auth, actor -> analysis.overview(window(from, to, skuCode, null)));
  }

  /** 缺陷类型分布（分母恒等于 FAIL 检项条数）。 */
  @GetMapping("/defect-distribution")
  public DefectDistributionReport defectDistribution(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String skuCode,
      @RequestParam(required = false) UUID categoryId,
      @RequestParam(required = false) Integer limit) {
    return call(
        auth,
        actor -> analysis.defectDistribution(window(from, to, skuCode, categoryId), limit));
  }

  /** 批次质量趋势（UTC 分桶）。 */
  @GetMapping("/lot-trend")
  public LotTrendReport lotTrend(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String skuCode,
      @RequestParam(required = false) String granularity,
      @RequestParam(required = false) String batchNo,
      @RequestParam(required = false) Integer limit) {
    return call(
        auth,
        actor ->
            analysis.lotTrend(window(from, to, skuCode, null), granularity, batchNo, limit));
  }

  /** 供应商质量对比（一行 = 一个供应商引用，不是行 = 一个批次）。 */
  @GetMapping("/supplier-comparison")
  public SupplierComparisonReport supplierComparison(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String skuCode,
      @RequestParam(required = false) Integer limit) {
    return call(
        auth, actor -> analysis.supplierComparison(window(from, to, skuCode, null), limit));
  }

  /**
   * 缺陷照片聚类（<b>内容指纹</b>口径，不是图像相似度）。
   *
   * <p>⚠️ 响应里<b>没有</b> {@code similarity} 这样的数字，也不可能有：
   * 本仓无图像库、无模型、无第三方视觉服务接入点。
   */
  @GetMapping("/photo-clusters")
  public PhotoClusterReport photoClusters(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) Integer minClusterSize,
      @RequestParam(required = false) Integer limit) {
    return call(auth, actor -> analysis.photoClusters(minClusterSize, limit));
  }

  /** 开箱率（<b>必须和覆盖率一起读</b>）。 */
  @GetMapping("/open-box-rate")
  public OpenBoxReport openBoxRate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String skuCode,
      @RequestParam(required = false) Integer limit) {
    return call(auth, actor -> analysis.openBoxRate(window(from, to, skuCode, null), limit));
  }

  /** 质量成本估算（逐项标依据等级：实测 / 按参数估算 / 参数未登记）。 */
  @GetMapping("/quality-cost")
  public QualityCostReport qualityCost(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String skuCode) {
    return call(auth, actor -> analysis.qualityCost(window(from, to, skuCode, null)));
  }

  /** 责任分布 + 改进清单（只列当前生效归因）。 */
  @GetMapping("/improvements")
  public ImprovementReport improvements(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String stage,
      @RequestParam(required = false) String lotCode,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        actor ->
            analysis.improvements(
                new QualityWindow(null, null, null, null),
                stage,
                lotCode,
                page == null ? 0 : page,
                size == null ? 20 : size));
  }

  // ================================================================ 缺陷观察

  /** 缺陷观察列表（默认含已作废行 —— 作废是留痕）。 */
  @GetMapping("/defect-observations")
  public DefectObservationPage observations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String inspectionId,
      @RequestParam(required = false) String stage,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        actor ->
            analysis.listObservations(
                inspectionId,
                stage,
                dimension,
                status,
                keyword,
                page == null ? 0 : page,
                size == null ? 20 : size));
  }

  /**
   * 登记缺陷观察（开箱率的唯一数据来源）。
   *
   * <p>⚠️ 请求体里<b>没有</b> {@code observationStatus} / {@code observationNo}：
   * 建行恒为 RECORDED，单号由服务层生成，作废只走 {@code /void} 一个入口。
   */
  @PostMapping("/defect-observations")
  public DefectObservationRow recordObservation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody DefectObservationCreate request) {
    return write(auth, actor -> analysis.recordObservation(request, actor.userId()));
  }

  /** 作废缺陷观察（终态：业务列冻结，判错了请另记一条）。 */
  @PostMapping("/defect-observations/{id}/void")
  public DefectObservationRow voidObservation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody DefectObservationVoid request) {
    return write(auth, actor -> analysis.voidObservation(id, request, actor.userId()));
  }

  // ================================================================ 成本参数

  @GetMapping("/cost-parameters")
  public CostParameterPage costParameters(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        actor ->
            analysis.listCostParameters(
                status, page == null ? 0 : page, size == null ? 20 : size));
  }

  /**
   * 建成本参数。
   *
   * <p>⚠️ 请求体里<b>没有</b> {@code parameterStatus} / {@code effectiveTo}：
   * 建行恒为 ACTIVE 且不预设截止日，截止日由换版时写。
   */
  @PostMapping("/cost-parameters")
  public CostParameterRow createCostParameter(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody CostParameterCreate request) {
    return write(auth, actor -> analysis.createCostParameter(request, actor.userId()));
  }

  /** 成本参数换版（终态：老版本保留为 SUPERSEDED）。 */
  @PostMapping("/cost-parameters/{id}/supersede")
  public CostParameterRow supersedeCostParameter(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody CostParameterSupersede request) {
    return write(auth, actor -> analysis.supersedeCostParameter(id, request, actor.userId()));
  }

  // ================================================================ 供应商引用

  @GetMapping("/supplier-refs")
  public SupplierRefPage supplierRefs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        actor ->
            analysis.listSupplierRefs(keyword, page == null ? 0 : page, size == null ? 20 : size));
  }

  /**
   * 登记 / 更新供应商引用。
   *
   * <p>⚠️ <b>刻意没有 DELETE 端点</b>：停用是留痕的、删除不是 ——
   * 同步器按同一形状写回来时，删掉的行会立刻复活，于是「明明停用了却又生效」。
   */
  @PostMapping("/supplier-refs")
  public SupplierRefRow upsertSupplierRef(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody SupplierRefUpsert request) {
    return write(auth, actor -> analysis.upsertSupplierRef(request, actor.userId()));
  }

  /** 供应商引用启停（停用是留痕的，删除不是）。 */
  @PostMapping("/supplier-refs/{id}/status")
  public SupplierRefRow setSupplierRefStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody SupplierRefStatus request) {
    return write(auth, actor -> analysis.setSupplierRefStatus(id, request, actor.userId()));
  }

  // ================================================================ 小工具

  /**
   * 期间参数的解析。
   *
   * <p>⚠️ 解析失败返回 400 而不是静默忽略：一个打错的 {@code from=2026-13-01}
   * 被当成「不筛期间」，用户会拿到一份全量报表还以为自己筛过了。
   */
  private static LocalDate parseDate(String value, String label) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    try {
      return LocalDate.parse(value.trim());
    } catch (RuntimeException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          label + " 必须是 yyyy-MM-dd 格式（收到 \"" + value.trim() + "\"）");
    }
  }
}
