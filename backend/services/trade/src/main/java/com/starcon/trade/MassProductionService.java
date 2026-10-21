package com.starcon.trade;

import static com.starcon.trade.MassProductionModels.*;
import static com.starcon.trade.MassProductionRules.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
 * 6.6.2.2 转量产与建档。
 *
 * <p>蓝图原话：「试产通过后转正式量产：确定量产 BOM、批量采购/生产计划、目标成本、
 * 正式 SKU 建档（含编码/条码/属性/报关属性），并把量产参数带入采购与价格模型。」
 * 输入「试产结果；量产决策；BOM 定版」。输出「量产计划；正式 SKU（3.1.2.1）；量产 BOM 版本；采购需求（6.2.1.2）」。
 *
 * <p><b>本模块只做一件事：把「这一轮试产通过了，要转正式量产」变成一份有凭据、
 * 状态受控、BOM 定过版、SKU 真的建到商品中心、并且把量产参数如实交给采购与价格模型的量产计划。</b>
 *
 * <p><b>🔴 十一条实现纪律</b>：
 *
 * <ol>
 *   <li><b>状态机在服务层 + 数据库层两处钉死</b>：Java 侧
 *       {@link MassProductionRules#allowedTargets}，DB 侧 V268 迁移的
 *       {@code guard_mass_production_plan_status()}。客户端<b>传什么状态不算数</b>。</li>
 *   <li><b>正式 SKU 不在本服务建</b>：写入所有权在 catalog（3.1.2.1，
 *       {@code POST /catalog/products/{id}/skus}）⇒ 本模块**不建 SKU 表、不跨库写 SKU**，
 *       只落<b>建档请求事实</b>（目标系统 + 目标键 + 载荷 + 幂等键 + 状态机），
 *       SKU id 是<b>从 catalog 读回来的</b>。</li>
 *   <li><b>建档请求的「结果未知」是一种独立状态</b>：catalog 的建档端点<b>不接受幂等键</b>，
 *       超时 / 断连时无法判断它到底建没建 ⇒ 盲目重试可能建出重复 SKU，
 *       所以 {@code UNKNOWN} 状态<b>禁止</b>再次提交。</li>
 *   <li><b>量产 BOM 组件行原样搬运 catalog 的 R6/R7</b>：本服务<b>不重算</b>，
 *       否则两处各实现一遍必然漂移，而漂移的后果是「按不同口径采购」。</li>
 *   <li><b>上游 6.6.2.1 只读引用 + 快照</b>：试产单与转量产建议是**它**的事实，
 *       本模块**零外键、零 ALTER、零写入**。</li>
 *   <li><b>采购需求只做「重算触发」，不写它的表</b>：6.2.1.2 的起订额与交期是人工维护的策略，
 *       本模块<b>不顶掉</b>它 ⇒ 交接留痕如实标 {@code massProductionInputApplied=false}。</li>
 *   <li><b>价格模型不 mock</b>：5.3.1.1 未交付 ⇒ 交接留痕如实标 {@code available=false} + 缺口形态。</li>
 *   <li><b>一个量产计划一个币种</b>：跨币种<b>绝不相加</b>，<b>不做固定汇率折算</b>
 *       （5.2.2.1 未交付）；目标成本取不到时是 {@code null}（<b>不是 0</b>）。</li>
 *   <li><b>量产决策必须举证</b>：理由必填；上游建议是 {@code NO_GO} 而本计划要 {@code GO} 时，
 *       <b>必须</b>写覆盖理由。</li>
 *   <li><b>生产计划下达（8.4.1 组装单）不在本模块</b>：warehouse 独立库，授权 API / event 未落地，
 *       如实标缺口。</li>
 *   <li><b>时间一律 UTC 口径</b>：{@code timestamptz} 列用 {@link OffsetDateTime} 绑定，
 *       values 位置写 {@code ?::timestamptz}。</li>
 * </ol>
 */
@Service
public class MassProductionService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 正式 SKU 建档的归管模块（3.1.2.1 SKU 列表 / 商品档案，catalog 独立库）。 */
  static final String SKU_FILING_OWNER_MODULE = "3.1.2.1 SKU 列表（catalog 商品档案）";

  /** 采购需求的归管模块（6.2.1.2 补货建议，trade 同一个库）。 */
  static final String PURCHASE_DEMAND_OWNER_MODULE = "6.2.1.2 补货建议";

  /** 价格模型的归管模块（5.3.1.1 目标定价，**未开发**）。 */
  static final String PRICING_OWNER_MODULE = "5.3.1.1 目标定价";

  /** 生产计划下达的归管模块（8.4.1 组装单，warehouse 独立库）。 */
  static final String ASSEMBLY_ORDER_OWNER_MODULE = "8.4.1 组装单";

  /** 汇率查询的归管模块（5.2.2.1，未交付 ⇒ 不做固定汇率折算）。 */
  static final String EXCHANGE_RATE_OWNER_MODULE = "5.2.2.1 汇率查询";

  /** 租户没配默认覆盖策略时的兜底覆盖天数（与 6.3.2.1 同一口径）。 */
  private static final int FALLBACK_COVERAGE_DAYS = 30;

  /**
   * 留痕里的「操作人姓名」占位。
   *
   * <p>🔴 本服务<b>拿不到</b>操作人的显示名（显示名在 identity 服务里）。<b>不去猜、不去编</b> ——
   * 留痕的<b>主体是 actor_id</b>（谁做的），姓名留空比编一个强。
   */
  static final String ACTOR_NAME_UNKNOWN = "";

  private final DSLContext sql;
  private final MassProductionCatalogPort catalog;
  private final ProductionTrialBomPort bom;
  private final BomVersionPort bomVersion;
  private final ReplenishmentSuggestionService replenishment;

  public MassProductionService(
      DSLContext sql,
      MassProductionCatalogPort catalog,
      ProductionTrialBomPort bom,
      BomVersionPort bomVersion,
      ReplenishmentSuggestionService replenishment) {
    this.sql = sql;
    this.catalog = catalog;
    this.bom = bom;
    this.bomVersion = bomVersion;
    this.replenishment = replenishment;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：<b>正式 SKU 建档</b>（catalog 的 {@code POST /catalog/products/{id}/skus}）
   * <b>确实有入口</b> ⇒ 可用；<b>采购需求</b>（6.2.1.2，同库）只能<b>重算触发</b> ⇒ 可用但参数进不去；
   * <b>价格模型</b>（5.3.1.1，未开发）与<b>生产计划下达</b>（8.4.1 组装单）<b>做不到</b> ——
   * 哪一个接通了必须由接口自己报出。
   */
  public MassProductionCapabilities capabilities() {
    return new MassProductionCapabilities(
        true,
        "3.5.2.1 BOM 版本管理（catalog 服务，独立库，已交付 V25）",
        "量产 BOM 版本是**逻辑引用**：本模块**零跨库 FOREIGN KEY、零跨库视图、零跨服务直连**，"
            + "只经 catalog 的授权 HTTP 接口读（GET /catalog/bom-versions/{id} 与 /requirement），"
            + "并把版本号 / BOM 编码 / 成品 SKU 存成**展示快照**。",
        true,
        SKU_FILING_OWNER_MODULE,
        "正式 SKU 的**写入所有权在 catalog**（POST /catalog/products/{id}/skus，权限 catalog.sku.create；"
            + "报关属性 PUT /catalog/customs-attributes/skus/{skuId}，权限 catalog.compliance.update）"
            + "⇒ 本模块**不建 SKU 表、不跨库写 SKU**，只落**建档请求事实**"
            + "（目标系统 + 目标键 + 载荷 + 幂等键 + 状态机），SKU id 是**从 catalog 读回来的**。"
            + "🔴 catalog 的建档端点**不接受幂等键** ⇒ 本模块把「超时 / 断连」标成"
            + "**结果未知**并**禁止**盲目重试（重试可能建出重复 SKU）。",
        true,
        PURCHASE_DEMAND_OWNER_MODULE,
        "采购需求（6.2.1.2）与本模块**在同一个 trade 库**，但那些表是**它的**事实 ⇒ "
            + "本模块**绝不 INSERT / UPDATE 它的表**，只**调用它自己的服务方法触发重算**"
            + "（POST /trade/replenishment-suggestions/batches），"
            + "并如实标 **massProductionInputApplied=false**：它的起订额与交期是人工维护的策略"
            + "（replenishment_order_policy），本模块**不顶掉**它的人工起订额与币种。",
        false,
        PRICING_OWNER_MODULE,
        "🔴 **仓里没有 pricing 服务**，5.3.1.1 目标定价**未开发** ⇒ 本模块**不 mock 算价**。"
            + "量产计划上的目标成本是**本模块自己的事实**（可为空 = 取不到），"
            + "但它**没有**被写进任何价格模型；交接留痕如实标 available=false。",
        false,
        ASSEMBLY_ORDER_OWNER_MODULE,
        "量产「生产计划」的下达（加工单）归 8.4.1 组装单（warehouse 独立库），"
            + "授权 API / event **未落地** ⇒ 本模块**不跨库建加工单**，如实标缺口。",
        "🔴 本模块的边界：量产计划 + 量产 BOM 定版快照 + 正式 SKU 建档请求与结果 + 下游交接留痕。"
            + "**组件的库存扣减不在这里**（归 8.4.1 组装单），"
            + "**采购建议的策略不在这里**（归 6.2.1.2），**价格模型不在这里**（归 5.3.1.1，未开发）。");
  }

  // ================================================================ 下拉项

  /** 状态下拉（含每个状态允许迁到哪些状态）、决策结论、决策来源、建档状态、属性状态。 */
  public MassProductionOptions options() {
    List<MassProductionStatusOption> statuses = new ArrayList<>();
    for (MassProductionPlanStatus status : MassProductionPlanStatus.values()) {
      statuses.add(
          new MassProductionStatusOption(
              status.name(),
              status.label(),
              isTerminalStatus(status),
              allowedTargets(status).stream().map(Enum::name).toList()));
    }
    List<MassProductionDecisionOption> decisions = new ArrayList<>();
    for (MassProductionDecision decision : MassProductionDecision.values()) {
      decisions.add(new MassProductionDecisionOption(decision.name(), decision.label()));
    }
    List<MassProductionDecisionSourceOption> sources = new ArrayList<>();
    for (MassProductionDecisionSource source : MassProductionDecisionSource.values()) {
      sources.add(new MassProductionDecisionSourceOption(source.name(), source.label()));
    }
    List<MassProductionFilingStatusOption> filingStatuses = new ArrayList<>();
    for (MassProductionFilingStatus status : MassProductionFilingStatus.values()) {
      filingStatuses.add(new MassProductionFilingStatusOption(status.name(), status.label()));
    }
    List<MassProductionAttributeStateOption> attributeStates = new ArrayList<>();
    for (MassProductionAttributeState state : MassProductionAttributeState.values()) {
      attributeStates.add(new MassProductionAttributeStateOption(state.name(), state.label()));
    }
    return new MassProductionOptions(statuses, decisions, sources, filingStatuses, attributeStates);
  }

  // ================================================================ 概览

  /** 按状态分组的概览 + 按币种分组的目标成本。 */
  public MassProductionSummary summary() {
    UUID tenantId = currentTenant();
    long totalPlans = count("select count(*) from mass_production_plan where tenant_id = ?::uuid", tenantId);
    long decidedPlans =
        count(
            "select count(*) from mass_production_plan where tenant_id = ?::uuid"
                + " and status in ('DECIDED','BOM_FROZEN','SKU_FILED','RELEASED','CLOSED')",
            tenantId);
    long frozenBomPlans =
        count(
            "select count(*) from mass_production_plan where tenant_id = ?::uuid"
                + " and status in ('BOM_FROZEN','SKU_FILED','RELEASED','CLOSED')",
            tenantId);
    long filedSkuPlans =
        count(
            "select count(*) from mass_production_plan where tenant_id = ?::uuid"
                + " and status in ('SKU_FILED','RELEASED','CLOSED')",
            tenantId);
    long releasedPlans =
        count(
            "select count(*) from mass_production_plan where tenant_id = ?::uuid"
                + " and status in ('RELEASED','CLOSED')",
            tenantId);
    long pendingFilings =
        count(
            "select count(*) from mass_production_sku_filing where tenant_id = ?::uuid"
                + " and status in ('PENDING','SUBMITTED')",
            tenantId);
    long failedFilings =
        count(
            "select count(*) from mass_production_sku_filing where tenant_id = ?::uuid"
                + " and status in ('FAILED','PARTIAL','UNKNOWN')",
            tenantId);

    Map<String, Long> statusCounts = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select status, count(*) as c from mass_production_plan where tenant_id = ?::uuid"
                + " group by status",
            tenantId)) {
      statusCounts.put(row.get("status", String.class), row.get("c", Long.class));
    }
    List<MassProductionStatusCount> statuses = new ArrayList<>();
    for (MassProductionPlanStatus status : MassProductionPlanStatus.values()) {
      statuses.add(
          new MassProductionStatusCount(
              status.name(), status.label(), statusCounts.getOrDefault(status.name(), 0L)));
    }

    // 🔴 目标成本按币种分组：一个计划一个币种 ⇒ 跨币种绝不相加。
    List<MassProductionTargetCostCurrencyTotal> targetCostByCurrency = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select currency, sum(target_cost) as t, count(*) as c from mass_production_plan"
                + " where tenant_id = ?::uuid and target_cost is not null group by currency"
                + " order by currency",
            tenantId)) {
      targetCostByCurrency.add(
          new MassProductionTargetCostCurrencyTotal(
              row.get("currency", String.class),
              row.get("t", BigDecimal.class),
              row.get("c", Long.class)));
    }

    List<MassProductionPlanRow> recent = new ArrayList<>();
    for (Record row :
        sql.fetch(
            PLAN_SELECT + " where p.tenant_id = ?::uuid order by p.created_at desc, p.id desc limit 10",
            tenantId)) {
      recent.add(planRow(row));
    }
    return new MassProductionSummary(
        totalPlans,
        decidedPlans,
        frozenBomPlans,
        filedSkuPlans,
        releasedPlans,
        pendingFilings,
        failedFilings,
        statuses,
        targetCostByCurrency,
        recent);
  }

  // ================================================================ 候选

  /** 可转量产的试产单候选（上游 6.6.2.1，同库只读）。 */
  public List<MassProductionTrialCandidateRow> trialCandidates() {
    List<MassProductionTrialCandidateRow> rows = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select t.id as trial_order_id, t.trial_no, t.status as trial_status,"
                + " t.bom_version_id, t.bom_version_no, t.bom_code, t.output_sku_code,"
                + " t.output_sku_name, r.decision as recommendation_decision,"
                + " c.total_amount as trial_cost, c.currency, c.mass_target_cost"
                + " from production_trial_order t"
                + " left join production_trial_mass_production_recommendation r"
                + "   on r.tenant_id = t.tenant_id and r.trial_order_id = t.id"
                + " left join production_trial_cost c"
                + "   on c.tenant_id = t.tenant_id and c.trial_order_id = t.id"
                + " where t.tenant_id = ?::uuid and t.status in ('COMPLETED','CLOSED')"
                + " order by t.created_at desc, t.id desc",
            currentTenant())) {
      String decision = blankToEmpty(row.get("recommendation_decision", String.class));
      String trialStatus = row.get("trial_status", String.class);
      rows.add(
          new MassProductionTrialCandidateRow(
              row.get("trial_order_id", UUID.class),
              row.get("trial_no", String.class),
              trialStatus,
              ProductionTrialModels.ProductionTrialStatus.valueOf(trialStatus).label(),
              decision,
              decision.isEmpty()
                  ? "（上游没有出转量产建议）"
                  : ProductionTrialModels.ProductionTrialMassProductionDecision.valueOf(decision).label(),
              row.get("trial_cost", BigDecimal.class),
              row.get("currency", String.class),
              row.get("mass_target_cost", BigDecimal.class),
              row.get("bom_version_id", UUID.class),
              row.get("bom_version_no", String.class),
              row.get("bom_code", String.class),
              row.get("output_sku_code", String.class),
              row.get("output_sku_name", String.class)));
    }
    return rows;
  }

  /** catalog 的 BOM 版本候选（跨服务只读；量产 BOM 要选的就是它）。 */
  public List<ProductionTrialModels.ProductionTrialBomVersionCandidateRow> bomVersionCandidates(
      String keyword, int page, int size, String bearer) {
    ProductionTrialBomPort.VersionPage result = bom.candidates(keyword, null, page, size, bearer);
    List<ProductionTrialModels.ProductionTrialBomVersionCandidateRow> rows = new ArrayList<>();
    for (ProductionTrialBomPort.VersionRow row : result.items()) {
      rows.add(
          new ProductionTrialModels.ProductionTrialBomVersionCandidateRow(
              row.id(),
              row.versionNo(),
              row.name(),
              row.bomCode(),
              row.outputSkuCode(),
              row.outputSkuName(),
              row.status(),
              row.statusLabel(),
              row.componentCount(),
              row.effective()));
    }
    return rows;
  }

  /** catalog 的 SPU 候选（SKU 建档的「目标键」）。 */
  public List<MassProductionCatalogSpuRow> spuCandidates(String keyword, String bearer) {
    List<MassProductionCatalogSpuRow> rows = new ArrayList<>();
    for (MassProductionCatalogPort.SpuView view : catalog.spus(keyword, bearer)) {
      rows.add(
          new MassProductionCatalogSpuRow(
              view.id(), view.code(), view.name(), view.brand(), view.category(), view.status()));
    }
    return rows;
  }

  /** catalog 的商品属性候选（建档载荷里「属性」那一段的取值范围）。 */
  public List<MassProductionCatalogAttributeRow> spuAttributes(UUID spuId, String bearer) {
    var detail = catalog.spuDetail(spuId, bearer);
    List<MassProductionCatalogAttributeRow> rows = new ArrayList<>();
    for (MassProductionCatalogPort.AttributeView attribute : detail.attributes()) {
      rows.add(
          new MassProductionCatalogAttributeRow(
              attribute.id(),
              attribute.code(),
              attribute.name(),
              attribute.dataType(),
              attribute.valueMode(),
              attribute.multiple(),
              attribute.required(),
              attribute.variantAxis(),
              attribute.unit(),
              attribute.groupName(),
              attribute.options(),
              attribute.defaultValues()));
    }
    return rows;
  }

  // ================================================================ 量产计划清单

  /** 量产计划清单（真分页 + 真筛选）。 */
  public MassProductionPlanPage listPlans(
      String status, UUID trialOrderId, String keyword, Integer page, Integer size) {
    UUID tenantId = currentTenant();
    int pageNo = page == null || page < 0 ? 0 : page;
    int pageSize = pageSize(size);
    List<Object> binds = new ArrayList<>();
    binds.add(tenantId);
    StringBuilder where = new StringBuilder(" where p.tenant_id = ?::uuid");
    if (status != null && !status.isBlank()) {
      where.append(" and p.status = ?::varchar");
      binds.add(status.trim());
    }
    if (trialOrderId != null) {
      where.append(" and p.trial_order_id = ?::uuid");
      binds.add(trialOrderId);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(
          " and (p.plan_no ilike ?::varchar or p.trial_no ilike ?::varchar"
              + " or p.bom_code ilike ?::varchar or p.output_sku_code ilike ?::varchar)");
      String like = "%" + keyword.trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    long total =
        count(
            "select count(*) from mass_production_plan p" + where,
            binds.toArray());
    List<MassProductionPlanRow> rows = new ArrayList<>();
    for (Record row :
        sql.fetch(
            PLAN_SELECT + where + " order by p.created_at desc, p.id desc limit ?::integer"
                + " offset ?::integer",
            append(binds, pageSize, pageNo * pageSize).toArray())) {
      rows.add(planRow(row));
    }
    int totalPages = (int) Math.ceil((double) total / pageSize);
    return new MassProductionPlanPage(rows, pageNo, pageSize, total, totalPages);
  }

  // ================================================================ 建量产计划

  /**
   * 建量产计划。
   *
   * <p>🔴 上游试产单（6.6.2.1，同库）**只读引用 + 快照**；BOM 版本（catalog 独立库）
   * **逻辑引用 + 展示快照**；组件需求量**不在这里算**（定版那一步才从 catalog 搬）。
   */
  @Transactional
  public MassProductionPlanDetail createPlan(
      MassProductionCreateInput input, UUID actorId, String bearer) {
    UUID tenantId = currentTenant();
    String planNo = input.planNo().trim();
    var exists =
        sql.fetchOne(
            "select id from mass_production_plan where tenant_id = ?::uuid and plan_no = ?::varchar",
            tenantId,
            planNo);
    if (exists != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "量产计划号已存在：" + planNo + "（单号在租户内唯一）");
    }
    String currency = normalizeCurrency(input.currency());
    requirePositiveQuantity(input.planQuantity());
    if (input.targetCost() != null) {
      requirePositiveAmount(input.targetCost(), "量产目标成本");
    }

    // 上游 6.6.2.1 试产单：只读引用 + 快照。
    Record trial =
        sql.fetchOne(
            "select t.id, t.trial_no, t.status, t.bom_version_id, t.bom_version_no, t.bom_code,"
                + " t.output_sku_code, t.output_sku_name,"
                + " r.decision as recommendation_decision"
                + " from production_trial_order t"
                + " left join production_trial_mass_production_recommendation r"
                + "   on r.tenant_id = t.tenant_id and r.trial_order_id = t.id"
                + " where t.tenant_id = ?::uuid and t.id = ?::uuid",
            tenantId,
            input.trialOrderId());
    if (trial == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "试产单不存在或不在当前租户内：" + input.trialOrderId());
    }
    String trialStatus = trial.get("status", String.class);
    if (!"COMPLETED".equals(trialStatus) && !"CLOSED".equals(trialStatus)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "试产单「"
              + trial.get("trial_no", String.class)
              + "」当前是「"
              + ProductionTrialModels.ProductionTrialStatus.valueOf(trialStatus).label()
              + "」：只有已完成的试产才能转量产 —— 没做完的试产没有结果、没有良率、没有成本对比");
    }

    // 上游 3.5.2.1 BOM 版本（catalog 独立库）：逻辑引用 + 展示快照。
    ProductionTrialBomPort.VersionDetail version = bom.detail(input.bomVersionId(), bearer);
    if (!"PUBLISHED".equals(version.status())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "BOM 版本「"
              + version.versionNo()
              + "」当前是「"
              + version.statusLabel()
              + "」：量产必须用**已发布**的 BOM 版本（草稿版本的组件清单随时会变，"
              + "按它采购等于按一份会变的清单采购）");
    }

    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into mass_production_plan(id,tenant_id,plan_no,trial_order_id,trial_no,"
            + " trial_recommendation_decision,bom_version_id,bom_version_no,bom_code,"
            + " output_sku_code,output_sku_name,plan_quantity,target_cost,currency,"
            + " production_line_code,production_line_name,factory_code,factory_name,status,"
            + " decision,decision_source,decision_rationale,override_reason,decided_by,decided_at,"
            + " planned_start_at,planned_end_at,note,created_by,created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::varchar,?::uuid,?::varchar,?::varchar,?::uuid,?::varchar,"
            + " ?::varchar,?::varchar,?::varchar,?::numeric,?::numeric,?::varchar,?::varchar,"
            + " ?::varchar,?::varchar,?::varchar,'DRAFT','','','','',null,null,?::timestamptz,"
            + " ?::timestamptz,?::varchar,?::uuid,now(),now())",
        id,
        tenantId,
        planNo,
        input.trialOrderId(),
        trial.get("trial_no", String.class),
        blankToEmpty(trial.get("recommendation_decision", String.class)),
        input.bomVersionId(),
        version.versionNo(),
        version.bomCode(),
        version.outputSkuCode(),
        version.outputSkuName(),
        input.planQuantity(),
        input.targetCost(),
        currency,
        blankToEmpty(input.productionLineCode()),
        blankToEmpty(input.productionLineName()),
        blankToEmpty(input.factoryCode()),
        blankToEmpty(input.factoryName()),
        input.plannedStartAt(),
        input.plannedEndAt(),
        blankToEmpty(input.note()),
        actorId);
    return planDetail(id);
  }

  // ================================================================ 详情与改单

  /** 量产计划详情：计划 + BOM 快照 + 建档请求 + 交接留痕 + 迁移历史 + 成本对比。 */
  public MassProductionPlanDetail planDetail(UUID planId) {
    Record row =
        sql.fetchOne(
            PLAN_SELECT + " where p.tenant_id = ?::uuid and p.id = ?::uuid",
            currentTenant(),
            planId);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "量产计划不存在或无权查看：" + planId);
    }
    MassProductionPlanRow plan = planRow(row);
    return new MassProductionPlanDetail(
        plan,
        listBomLines(planId),
        filingOf(planId),
        listHandoffs(planId),
        listTransitions(planId),
        allowedTargets(MassProductionPlanStatus.valueOf(plan.status())).stream()
            .map(Enum::name)
            .toList(),
        costComparison(planId));
  }

  /**
   * 改量产计划（<b>整体替换</b>语义：请求体就是期望的最终状态）。
   *
   * <p>🔴 <b>上游试产单与 BOM 版本不可改</b>：改了它们，已定版的组件快照与成本对比就全对不上了 ——
   * 所以它们<b>根本不在改单输入里</b>。
   */
  @Transactional
  public MassProductionPlanDetail updatePlan(UUID planId, MassProductionUpdateInput input) {
    UUID tenantId = currentTenant();
    Record row =
        sql.fetchOne(
            "select status from mass_production_plan where tenant_id = ?::uuid and id = ?::uuid",
            tenantId,
            planId);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "量产计划不存在或无权查看：" + planId);
    }
    MassProductionPlanStatus status =
        MassProductionPlanStatus.valueOf(row.get("status", String.class));
    if (isTerminalStatus(status)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "量产计划已是「" + status.label() + "」（终态，不可改）：终态不提供恢复");
    }
    requirePositiveQuantity(input.planQuantity());
    if (input.targetCost() != null) {
      requirePositiveAmount(input.targetCost(), "量产目标成本");
    }
    String currency = normalizeCurrency(input.currency());
    sql.execute(
        "update mass_production_plan set plan_quantity = ?::numeric, target_cost = ?::numeric,"
            + " currency = ?::varchar, production_line_code = ?::varchar,"
            + " production_line_name = ?::varchar, factory_code = ?::varchar,"
            + " factory_name = ?::varchar, planned_start_at = ?::timestamptz,"
            + " planned_end_at = ?::timestamptz, note = ?::varchar, updated_at = now()"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        input.planQuantity(),
        input.targetCost(),
        currency,
        blankToEmpty(input.productionLineCode()),
        blankToEmpty(input.productionLineName()),
        blankToEmpty(input.factoryCode()),
        blankToEmpty(input.factoryName()),
        input.plannedStartAt(),
        input.plannedEndAt(),
        blankToEmpty(input.note()),
        tenantId,
        planId);
    return planDetail(planId);
  }

  // ================================================================ 推进状态

  /**
   * 推进量产计划状态。
   *
   * <p>🔴 只能沿状态机上存在的边走；已关闭 / 已取消是终态；
   * 每次迁移都会落一条不可改写的事件（<b>先落事件、再改状态</b> —— DB 守卫会检查这件事）。
   */
  @Transactional
  public MassProductionPlanDetail transitionPlan(
      UUID planId, MassProductionTransitionInput input, UUID actorId, String bearer) {
    UUID tenantId = currentTenant();
    Record row =
        sql.fetchOne(
            "select status, trial_order_id, bom_version_id, plan_quantity, currency,"
                + " trial_recommendation_decision from mass_production_plan"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            tenantId,
            planId);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "量产计划不存在或无权查看：" + planId);
    }
    MassProductionPlanStatus from =
        MassProductionPlanStatus.valueOf(row.get("status", String.class));
    MassProductionPlanStatus to;
    try {
      to = MassProductionPlanStatus.valueOf(input.targetStatus().trim());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "未知的量产计划状态：" + input.targetStatus());
    }
    requireTransition(from, to);

    String reason = blankToEmpty(input.reason());
    switch (to) {
      case DECIDED -> {
        MassProductionDecision decision = parseDecision(input.decision());
        MassProductionDecisionSource source = parseDecisionSource(input.decisionSource());
        requireDecisionEvidence(
            decision,
            source,
            input.decisionRationale(),
            input.overrideReason(),
            row.get("trial_recommendation_decision", String.class));
        sql.execute(
            "update mass_production_plan set decision = ?::varchar, decision_source = ?::varchar,"
                + " decision_rationale = ?::varchar, override_reason = ?::varchar,"
                + " decided_by = ?::uuid, decided_at = ?::timestamptz, updated_at = now()"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            decision.name(),
            source.name(),
            input.decisionRationale().trim(),
            blankToEmpty(input.overrideReason()),
            actorId,
            OffsetDateTime.now(),
            tenantId,
            planId);
      }
      case BOM_FROZEN -> freezeBom(planId, row, bearer, actorId);
      case SKU_FILED -> requireConfirmedFiling(planId);
      case RELEASED -> {
        // 下游交接在状态推进**之后**单独留痕（成功/失败/跳过都留一行）。
        requireFrozenBom(planId);
      }
      case CLOSED, CANCELLED -> {
        // 无额外前置条件。
      }
      default -> {
        // 其余状态不是合法的目标状态（requireTransition 已经挡过）。
      }
    }

    // 先落事件，再改状态 —— DB 守卫会检查「有没有对应事件」。
    sql.execute(
        "insert into mass_production_plan_transition(id,tenant_id,plan_id,from_status,"
            + " to_status,reason,actor_id,occurred_at,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::uuid,"
            + " clock_timestamp(),now())",
        UUID.randomUUID(),
        tenantId,
        planId,
        from.name(),
        to.name(),
        reason,
        actorId);
    sql.execute(
        "update mass_production_plan set status = ?::varchar, updated_at = now()"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        to.name(),
        tenantId,
        planId);

    // 🔴 下达之后的**下游交接不在这里做**：交接可能失败（上游不可达），
    //    而「已下达」是硬事实 —— 交接失败**不能**回滚下达。
    //    ⇒ 交接在**它自己的事务**里做（见控制器的两段式调用，抄 6.3.2.1 跟单交期的做法）。
    return planDetail(planId);
  }

  /**
   * 量产 BOM 定版：把 catalog 算好的组件需求量**原样搬过来**存成快照。
   *
   * <p>🔴 本服务<b>不重算 R6/R7</b>：两处各实现一遍必然漂移，而漂移的后果是「按不同口径采购」。
   */
  private void freezeBom(UUID planId, Record plan, String bearer, UUID actorId) {
    UUID tenantId = currentTenant();
    UUID bomVersionId = plan.get("bom_version_id", UUID.class);
    BigDecimal planQuantity = plan.get("plan_quantity", BigDecimal.class);

    BomVersionPort.Requirement requirement =
        bomVersion.requirement(bomVersionId, planQuantity, bearer);
    if (requirement.lines().isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "BOM 版本「"
              + plan.get("bom_version_no", String.class)
              + "」没有组件行，算不出量产物料需求 —— 一个没有组件的 BOM 不可能拿来量产");
    }
    // 定版前先清掉旧快照（重复定版不留两份）。
    sql.execute(
        "delete from mass_production_bom_line where tenant_id = ?::uuid and plan_id = ?::uuid",
        tenantId,
        planId);
    int lineNo = 0;
    for (BomVersionPort.RequirementLine line : requirement.lines()) {
      lineNo++;
      sql.execute(
          "insert into mass_production_bom_line(id,tenant_id,plan_id,line_no,sku_id,sku_code,"
              + " sku_name,unit_quantity,scrap_rate,required_quantity,unit,created_at)"
              + " values (?::uuid,?::uuid,?::uuid,?::integer,?::uuid,?::varchar,?::varchar,"
              + " ?::numeric,?::numeric,?::numeric,?::varchar,now())",
          UUID.randomUUID(),
          tenantId,
          planId,
          lineNo,
          line.skuId(),
          line.skuCode(),
          "",
          line.unitQuantity(),
          line.scrapRate(),
          line.requiredQuantity(),
          "",
          actorId);
    }
  }

  /** SKU_FILED 的前置：必须有一条**已确认**（catalog 已返回 SKU id）的建档请求。 */
  private void requireConfirmedFiling(UUID planId) {
    var row =
        sql.fetchOne(
            "select status from mass_production_sku_filing where tenant_id = ?::uuid"
                + " and plan_id = ?::uuid",
            currentTenant(),
            planId);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "这个量产计划还没有 SKU 建档请求：正式 SKU 的写入所有权在商品中心（3.1.2.1），"
              + "本模块不凭空造一个 SKU");
    }
    MassProductionFilingStatus status =
        MassProductionFilingStatus.valueOf(row.get("status", String.class));
    if (!isFilingConfirmed(status)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "SKU 建档请求当前是「"
              + status.label()
              + "」，还不能标记为「SKU 已建档」：建档请求只是交出去，"
              + "**确认**（商品中心返回了 SKU id）才是建成了");
    }
  }

  /** RELEASED 的前置：量产 BOM 必须已经定版。 */
  private void requireFrozenBom(UUID planId) {
    var row =
        sql.fetchOne(
            "select count(*) as c from mass_production_bom_line where tenant_id = ?::uuid"
                + " and plan_id = ?::uuid",
            currentTenant(),
            planId);
    long lines = row == null ? 0L : row.get("c", Long.class);
    if (lines == 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "量产 BOM 还没定版，不能下达：没有定版就下达，等于按一份会变的清单去采购");
    }
  }

  // ================================================================ 量产 BOM 组件行

  /** 量产 BOM 组件行快照。 */
  public List<MassProductionBomLineRow> listBomLines(UUID planId) {
    requirePlanExists(planId);
    List<MassProductionBomLineRow> rows = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id,plan_id,line_no,sku_id,sku_code,sku_name,unit_quantity,scrap_rate,"
                + " required_quantity,unit,created_at from mass_production_bom_line"
                + " where tenant_id = ?::uuid and plan_id = ?::uuid order by line_no, id",
            currentTenant(),
            planId)) {
      rows.add(
          new MassProductionBomLineRow(
              row.get("id", UUID.class),
              row.get("plan_id", UUID.class),
              row.get("line_no", Integer.class),
              row.get("sku_id", UUID.class),
              row.get("sku_code", String.class),
              row.get("sku_name", String.class),
              row.get("unit_quantity", BigDecimal.class),
              row.get("scrap_rate", BigDecimal.class),
              row.get("required_quantity", BigDecimal.class),
              row.get("unit", String.class),
              row.get("created_at", OffsetDateTime.class)));
    }
    return rows;
  }

  // ================================================================ SKU 建档请求

  /** 建 SKU 建档请求（**本服务侧的待办事实**，不是 SKU 本身）。 */
  public MassProductionSkuFilingRow filingOf(UUID planId) {
    // 🔴 先确认计划在当前租户的数据范围内：否则「读别人的建档请求」会变成一个跨租户探测口。
    requirePlanExists(planId);
    Record row =
        sql.fetchOne(
            "select id,filing_no,plan_id,target_system,target_endpoint,target_spu_id,"
                + " target_spu_code,target_spu_name,sku_code,sku_name,barcode,base_unit,"
                + " sku_status,idempotency_key,status,target_sku_id,target_sku_code,"
                + " customs_hs_code,customs_origin_country_code,customs_declared_name,"
                + " customs_declared_value_reference,customs_statutory_unit_code,"
                + " customs_preferential_origin_eligible,customs_preference_agreement,"
                + " customs_note,error_message,submitted_at,confirmed_at,created_by,created_at,"
                + " updated_at from mass_production_sku_filing"
                + " where tenant_id = ?::uuid and plan_id = ?::uuid",
            currentTenant(),
            planId);
    if (row == null) {
      return null;
    }
    return filingRow(row);
  }

  /**
   * 建一条 SKU 建档请求。
   *
   * <p>🔴 本模块**不建 SKU**：这里只落一条**待办事实**（目标系统 + 目标键 + 载荷 + 幂等键 + 状态机），
   * 真正的建档在 {@link #submitFiling} 里经 catalog 的授权接口完成。
   */
  @Transactional
  public MassProductionSkuFilingRow createFiling(
      UUID planId, MassProductionSkuFilingInput input, UUID actorId, String bearer) {
    UUID tenantId = currentTenant();
    requirePlanExists(planId);
    Record plan =
        sql.fetchOne(
            "select status from mass_production_plan where tenant_id = ?::uuid and id = ?::uuid",
            tenantId,
            planId);
    MassProductionPlanStatus status =
        MassProductionPlanStatus.valueOf(plan.get("status", String.class));
    if (isTerminalStatus(status)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "量产计划已是「" + status.label() + "」（终态，不可改）：终态不提供恢复");
    }
    var existing =
        sql.fetchOne(
            "select id from mass_production_sku_filing where tenant_id = ?::uuid and plan_id = ?::uuid",
            tenantId,
            planId);
    if (existing != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "这个量产计划已经有一条 SKU 建档请求：两条建档请求 = 两个 SKU。"
              + "请改已有那条（或先让它失败后再改），不要新开一条");
    }

    // 目标键：SKU 建在哪个 SPU 下面（catalog 独立库 ⇒ 逻辑引用 + 编码快照）。
    var spu = catalog.spuDetail(input.targetSpuId(), bearer);
    String spuCode = spu.product().code();
    String spuName = spu.product().name();

    // 属性行的形状必须与 catalog 一致（SET 必须带值；CLEAR / INHERIT 必须不带值）。
    for (MassProductionSkuFilingAttributeInput attribute : input.attributes()) {
      requireAttributeValues(attribute.valueState(), attribute.values());
    }
    // 报关申报要素的位次必须唯一（catalog 的跨行不变量）。
    Map<Integer, Boolean> ordinals = new LinkedHashMap<>();
    for (MassProductionCustomsElementInput element : input.customsElements()) {
      if (ordinals.put(element.ordinal(), Boolean.TRUE) != null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "报关申报要素的位次重复：" + element.ordinal() + "（位次必须唯一）");
      }
    }

    UUID id = UUID.randomUUID();
    // 🔴 编号与幂等键都要**装得进列宽**（varchar(64)）：单号带计划号前缀（截到 40 字符）
    //    + 12 位 id 后缀；幂等键只带计划号（租户内一个计划只有一条建档请求 ⇒ 已经唯一）。
    String planNo = planNoOf(planId);
    String planNoPart = planNo.length() > 40 ? planNo.substring(0, 40) : planNo;
    String filingNo = "SKF-" + planNoPart + "-" + id.toString().substring(0, 12);
    // 🔴 幂等键：catalog 的建档端点不接受幂等键 ⇒ 本模块自己扛「重复提交建出两个 SKU」。
    String idempotencyKey = "mp-sku:" + planId;
    sql.execute(
        "insert into mass_production_sku_filing(id,tenant_id,filing_no,plan_id,target_system,"
            + " target_endpoint,target_spu_id,target_spu_code,target_spu_name,sku_code,sku_name,"
            + " barcode,base_unit,sku_status,customs_hs_code,customs_origin_country_code,"
            + " customs_declared_name,customs_declared_value_reference,customs_statutory_unit_code,"
            + " customs_preferential_origin_eligible,customs_preference_agreement,customs_note,"
            + " idempotency_key,status,target_sku_id,target_sku_code,error_message,submitted_at,"
            + " confirmed_at,created_by,created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::varchar,?::uuid,'catalog',?::varchar,?::uuid,?::varchar,"
            + " ?::varchar,?::varchar,?::varchar,?::varchar,?::varchar,?::varchar,?::varchar,"
            + " ?::varchar,?::varchar,?::numeric,?::varchar,?::boolean,?::varchar,?::varchar,"
            + " ?::varchar,'PENDING',null,'','',null,null,?::uuid,now(),now())",
        id,
        tenantId,
        filingNo,
        planId,
        "POST /catalog/products/{id}/skus",
        input.targetSpuId(),
        spuCode,
        spuName,
        input.skuCode().trim(),
        input.skuName().trim(),
        input.barcode().trim(),
        input.baseUnit().trim(),
        input.skuStatus().trim(),
        blankToEmpty(input.customsHsCode()),
        blankToEmpty(input.customsOriginCountryCode()),
        blankToEmpty(input.customsDeclaredName()),
        input.customsDeclaredValueReference(),
        blankToEmpty(input.customsStatutoryUnitCode()),
        input.customsPreferentialOriginEligible(),
        blankToEmpty(input.customsPreferenceAgreement()),
        blankToEmpty(input.customsNote()),
        idempotencyKey,
        actorId);

    int lineNo = 0;
    for (MassProductionSkuFilingAttributeInput attribute : input.attributes()) {
      lineNo++;
      UUID attributeRowId = UUID.randomUUID();
      sql.execute(
          "insert into mass_production_sku_filing_attribute(id,tenant_id,filing_id,line_no,"
              + " attribute_id,attribute_code,attribute_name,value_state,created_at)"
              + " values (?::uuid,?::uuid,?::uuid,?::integer,?::uuid,?::varchar,?::varchar,"
              + " ?::varchar,now())",
          attributeRowId,
          tenantId,
          id,
          lineNo,
          attribute.attributeId(),
          blankToEmpty(attribute.attributeCode()),
          blankToEmpty(attribute.attributeName()),
          attribute.valueState().trim());
      int valueOrdinal = 0;
      for (String value : attribute.values()) {
        if (value == null || value.isBlank()) {
          continue;
        }
        valueOrdinal++;
        sql.execute(
            "insert into mass_production_sku_filing_attribute_value(id,tenant_id,attribute_row_id,"
                + " value_ordinal,value_text,created_at)"
                + " values (?::uuid,?::uuid,?::uuid,?::integer,?::varchar,now())",
            UUID.randomUUID(),
            tenantId,
            attributeRowId,
            valueOrdinal,
            value.trim());
      }
    }

    for (MassProductionCustomsElementInput element : input.customsElements()) {
      sql.execute(
          "insert into mass_production_customs_element(id,tenant_id,filing_id,ordinal,"
              + " element_code,element_name,element_value,required,created_at)"
              + " values (?::uuid,?::uuid,?::uuid,?::integer,?::varchar,?::varchar,?::varchar,"
              + " ?::boolean,now())",
          UUID.randomUUID(),
          tenantId,
          id,
          element.ordinal(),
          element.elementCode().trim(),
          element.elementName().trim(),
          blankToEmpty(element.elementValue()),
          element.required());
    }
    return filingOf(planId);
  }

  /**
   * 提交 SKU 建档（<b>真调 catalog</b>）。
   *
   * <p>🔴 <b>幂等纪律</b>：{@code CONFIRMED}（已建成）与 {@code UNKNOWN}（结果未知）都<b>禁止</b>再提交 ——
   * catalog 的建档端点不接受幂等键，盲目重试会建出重复 SKU。
   * <br>
   * 🔴 <b>部分完成</b>：SKU 建成了但报关属性没写进去 ⇒ 状态是 {@code PARTIAL}，
   * SKU id **照样记下来**（它是真的），再提交一次只补报关属性。
   */
  @Transactional
  public MassProductionSkuFilingRow submitFiling(
      UUID planId, UUID filingId, UUID actorId, String bearer) {
    UUID tenantId = currentTenant();
    Record row =
        sql.fetchOne(
            "select * from mass_production_sku_filing where tenant_id = ?::uuid and plan_id = ?::uuid"
                + " and id = ?::uuid",
            tenantId,
            planId,
            filingId);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "SKU 建档请求不存在或无权查看：" + filingId);
    }
    MassProductionFilingStatus status =
        MassProductionFilingStatus.valueOf(row.get("status", String.class));
    requireFilingResubmittable(status);

    UUID existingSkuId = row.get("target_sku_id", UUID.class);
    if (existingSkuId == null) {
      // ---- 第一步：在 catalog 建 SKU ----
      var skuInput =
          new MassProductionCatalogPort.SkuInput(
              row.get("sku_code", String.class),
              row.get("sku_name", String.class),
              row.get("barcode", String.class),
              row.get("base_unit", String.class),
              row.get("sku_status", String.class),
              attributeValueInputs(filingId),
              0L);
      var outcome =
          catalog.createSku(row.get("target_spu_id", UUID.class), skuInput, bearer);
      if (outcome instanceof MassProductionCatalogPort.CreateSkuOutcome.Created created) {
        sql.execute(
            "update mass_production_sku_filing set status = ?::varchar, target_sku_id = ?::uuid,"
                + " target_sku_code = ?::varchar, error_message = '', submitted_at = ?::timestamptz,"
                + " updated_at = now() where tenant_id = ?::uuid and id = ?::uuid",
            MassProductionFilingStatus.SUBMITTED.name(),
            created.skuId(),
            created.skuCode(),
            OffsetDateTime.now(),
            tenantId,
            filingId);
        existingSkuId = created.skuId();
      } else if (outcome instanceof MassProductionCatalogPort.CreateSkuOutcome.Rejected rejected) {
        // 🔴 记录失败**但不抛异常**：抛异常会回滚事务，把这条失败记录一起抹掉。
        //    「交掉了没有」必须是**落库的事实**，状态码由控制器按状态映射。
        recordFilingFailure(filingId, MassProductionFilingStatus.FAILED, rejected.reason());
        return filingOf(planId);
      } else if (outcome instanceof MassProductionCatalogPort.CreateSkuOutcome.Unavailable unavailable) {
        recordFilingFailure(filingId, MassProductionFilingStatus.FAILED, unavailable.reason());
        return filingOf(planId);
      } else {
        var unknown = (MassProductionCatalogPort.CreateSkuOutcome.Unknown) outcome;
        recordFilingFailure(
            filingId,
            MassProductionFilingStatus.UNKNOWN,
            unknown.reason()
                + "。🔴 商品中心的建档端点**不接受幂等键**，本次建档请求已被标成「结果未知」"
                + "并**禁止**再次提交：盲目重试可能建出重复 SKU。"
                + "请先到商品中心核对该编码是否已存在，再决定怎么收口。");
        return filingOf(planId);
      }
    }

    // ---- 第二步：写报关属性（catalog 3.7.1.1） ----
    boolean hasCustoms =
        !blankToEmpty(row.get("customs_hs_code", String.class)).isEmpty();
    if (!hasCustoms) {
      sql.execute(
          "update mass_production_sku_filing set status = ?::varchar, error_message = '',"
              + " confirmed_at = ?::timestamptz, updated_at = now()"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          MassProductionFilingStatus.CONFIRMED.name(),
          OffsetDateTime.now(),
          tenantId,
          filingId);
      return filingOf(planId);
    }

    var customsInput =
        new MassProductionCatalogPort.CustomsInput(
            row.get("customs_hs_code", String.class),
            row.get("customs_origin_country_code", String.class),
            row.get("customs_declared_name", String.class),
            row.get("customs_declared_value_reference", BigDecimal.class),
            row.get("customs_statutory_unit_code", String.class),
            Boolean.TRUE.equals(row.get("customs_preferential_origin_eligible", Boolean.class)),
            row.get("customs_preference_agreement", String.class),
            row.get("customs_note", String.class),
            customsElementInputs(filingId),
            0L);
    var customsOutcome = catalog.saveCustoms(existingSkuId, customsInput, bearer);
    if (customsOutcome instanceof MassProductionCatalogPort.SaveCustomsOutcome.Saved) {
      sql.execute(
          "update mass_production_sku_filing set status = ?::varchar, error_message = '',"
              + " confirmed_at = ?::timestamptz, updated_at = now() where tenant_id = ?::uuid"
              + " and id = ?::uuid",
          MassProductionFilingStatus.CONFIRMED.name(),
          OffsetDateTime.now(),
          tenantId,
          filingId);
    } else {
      String reason =
          customsOutcome instanceof MassProductionCatalogPort.SaveCustomsOutcome.Rejected r
              ? r.reason()
              : customsOutcome instanceof MassProductionCatalogPort.SaveCustomsOutcome.Unavailable u
                  ? u.reason()
                  : ((MassProductionCatalogPort.SaveCustomsOutcome.Unknown) customsOutcome).reason();
      recordFilingFailure(
          filingId,
          MassProductionFilingStatus.PARTIAL,
          "SKU 已经建到商品中心了（SKU id "
              + existingSkuId
              + "），但报关属性没写进去："
              + reason
              + "。建档请求已标成「部分完成」，再提交一次只补报关属性。");
    }
    return filingOf(planId);
  }

  /**
   * 记一条建档失败 / 部分完成 / 结果未知。
   *
   * <p>🔴 <b>不抛异常</b>：抛异常会回滚事务，把这条记录一起抹掉。
   * 「建档到底成没成」必须是**落库的事实**；HTTP 状态码由控制器按状态映射。
   */
  private void recordFilingFailure(
      UUID filingId, MassProductionFilingStatus status, String reason) {
    sql.execute(
        "update mass_production_sku_filing set status = ?::varchar, error_message = ?::varchar,"
            + " updated_at = now() where tenant_id = ?::uuid and id = ?::uuid",
        status.name(),
        truncate(reason, 1000),
        currentTenant(),
        filingId);
  }

  /** 建档载荷的属性取值（catalog 的 ValueInput 形状）。 */
  private List<MassProductionCatalogPort.ValueInput> attributeValueInputs(UUID filingId) {
    List<MassProductionCatalogPort.ValueInput> values = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select a.id, a.attribute_id, a.value_state from mass_production_sku_filing_attribute a"
                + " where a.tenant_id = ?::uuid and a.filing_id = ?::uuid order by a.line_no, a.id",
            currentTenant(),
            filingId)) {
      List<String> texts = new ArrayList<>();
      for (Record value :
          sql.fetch(
              "select v.value_text from mass_production_sku_filing_attribute_value v"
                  + " where v.tenant_id = ?::uuid and v.attribute_row_id = ?::uuid"
                  + " order by v.value_ordinal, v.id",
              currentTenant(),
              row.get("id", UUID.class))) {
        texts.add(value.get("value_text", String.class));
      }
      values.add(
          new MassProductionCatalogPort.ValueInput(
              row.get("attribute_id", UUID.class), row.get("value_state", String.class), texts));
    }
    return values;
  }

  /** 建档载荷的报关申报要素。 */
  private List<MassProductionCatalogPort.DeclarationElementInput> customsElementInputs(UUID filingId) {
    List<MassProductionCatalogPort.DeclarationElementInput> elements = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select ordinal, element_code, element_name, element_value, required"
                + " from mass_production_customs_element where tenant_id = ?::uuid"
                + " and filing_id = ?::uuid order by ordinal, id",
            currentTenant(),
            filingId)) {
      elements.add(
          new MassProductionCatalogPort.DeclarationElementInput(
              row.get("ordinal", Integer.class),
              row.get("element_code", String.class),
              row.get("element_name", String.class),
              row.get("element_value", String.class),
              Boolean.TRUE.equals(row.get("required", Boolean.class))));
    }
    return elements;
  }

  // ================================================================ 下游交接

  /** 下游交接留痕。 */
  public List<MassProductionHandoffRow> listHandoffs(UUID planId) {
    requirePlanExists(planId);
    List<MassProductionHandoffRow> rows = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id,plan_id,target_module,target_endpoint,owner_module,available,"
                + " mass_production_input_applied,status,result_ref,result_summary,error_message,"
                + " impact,attempted_at from mass_production_downstream_handoff"
                + " where tenant_id = ?::uuid and plan_id = ?::uuid"
                + " order by target_module, attempted_at desc, id",
            currentTenant(),
            planId)) {
      rows.add(
          new MassProductionHandoffRow(
              row.get("id", UUID.class),
              row.get("plan_id", UUID.class),
              row.get("target_module", String.class),
              row.get("target_endpoint", String.class),
              row.get("owner_module", String.class),
              Boolean.TRUE.equals(row.get("available", Boolean.class)),
              Boolean.TRUE.equals(row.get("mass_production_input_applied", Boolean.class)),
              row.get("status", String.class),
              row.get("result_ref", String.class),
              row.get("result_summary", String.class),
              row.get("error_message", String.class),
              row.get("impact", String.class),
              row.get("attempted_at", OffsetDateTime.class)));
    }
    return rows;
  }

  /**
   * 手动重新触发 6.2.1.2 补货建议重算。
   *
   * <p>🔴 这仍然只是<b>重算触发</b>：量产参数<b>没有</b>进到 6.2.1.2 的计算里
   * （它的起订额与交期是人工维护的策略），如实标 {@code massProductionInputApplied=false}。
   */
  public List<MassProductionHandoffRow> triggerReplenishment(UUID planId, UUID actorId, String bearer) {
    requirePlanExists(planId);
    writeDownstreamHandoffs(planId, actorId, bearer);
    return listHandoffs(planId);
  }

  /**
   * 下达时把量产参数交给下游（成功 / 失败 / 跳过都留一行）。
   *
   * <p>🔴 <b>本方法在它自己的事务里跑</b>（控制器在状态迁移**提交之后**才调它）：
   * 交接可能失败（上游不可达），而「已下达」是硬事实 —— 交接失败**不能**回滚下达。
   * 与 6.3.2.1 跟单交期的做法<b>同构</b>。
   */
  @Transactional
  public void writeDownstreamHandoffs(UUID planId, UUID actorId, String bearer) {
    requirePlanExists(planId);
    // 🔴 5.3.1.1 目标定价**未开发**（仓里没有 pricing 服务）⇒ 如实标缺口，不 mock 算价。
    insertPricingGapHandoff(planId, actorId);
    writeHandoffToReplenishment(planId, actorId, bearer);
  }

  /**
   * 记一条「交接失败」的留痕（<b>在失败之后的新事务里</b>调用）。
   *
   * <p>🔴 失败也必须留痕：「交掉了没有」是可查的事实，不是一句返回值。
   * 而失败时原事务已经回滚 ⇒ 必须开一个新事务来记。
   */
  @Transactional
  public void recordHandoffFailure(UUID planId, UUID actorId, RuntimeException failure) {
    requirePlanExists(planId);
    insertPricingGapHandoff(planId, actorId);
    String reason = failure == null ? "未知原因" : String.valueOf(failure.getMessage());
    insertHandoff(
        planId,
        "6.2.1.2",
        "POST /trade/replenishment-suggestions/batches",
        PURCHASE_DEMAND_OWNER_MODULE,
        false,
        false,
        "FAILED",
        "",
        "",
        truncate(reason, 1000),
        "本次交接未完成；量产计划的下达本身**已落库**（下达是硬事实，交接是派生动作，"
            + "交接失败不回滚下达）。",
        actorId);
  }

  /** 5.3.1.1 价格模型的缺口留痕（如实标 unavailable + 不 mock 算价）。 */
  private void insertPricingGapHandoff(UUID planId, UUID actorId) {
    insertHandoff(
        planId,
        "5.3.1.1",
        "（无入口：5.3.1.1 目标定价未交付）",
        PRICING_OWNER_MODULE,
        false,
        false,
        "SKIPPED",
        "",
        "量产计划 "
            + planNoOf(planId)
            + " 的目标成本 = "
            + targetCostText(planId)
            + "，未写进任何价格模型。",
        "",
        "⚠️ 5.3.1.1 目标定价**未交付**（仓里没有 pricing 服务）⇒ 本模块**不 mock 算价**。"
            + "量产目标成本只是本模块自己的事实（可为空 = 取不到），"
            + "它**没有**被写进任何价格模型。",
        actorId);
  }

  /**
   * 交接给 6.2.1.2 补货建议：<b>只触发重算</b>，不写它的表。
   *
   * <p>🔴 与 6.3.2.1 跟单交期<b>同一口径</b>：6.2.1.2 的供应商交期取自 catalog 报价与它<b>自有</b>的
   * 采购配置（{@code replenishment_order_policy}），本模块的量产批量**没有**合法的参数通道进到它那里 ——
   * 唯一能写交期 / 起订额的入口写的是<b>人工策略</b>，且它会<b>顶掉</b>既有那行
   * （含人工维护的起订额与币种）⇒ 本模块**不自动写人工策略**，
   * 只触发重算并把这个限制<b>如实报出</b>。
   */
  private void writeHandoffToReplenishment(UUID planId, UUID actorId, String bearer) {
    UUID tenantId = currentTenant();
    int coverageDays = defaultCoverageDays();
    boolean fromPolicy = configuredCoverageDays() != null;
    String note =
        "6.6.2.2 转量产触发重算：量产计划 "
            + planNoOf(planId)
            + " 的计划批量 "
            + planQuantityText(planId)
            + "，目标成本 "
            + targetCostText(planId);
    try {
      var result =
          replenishment.compute(
              new ReplenishmentSuggestionModels.ReplenishmentComputeInput(null, coverageDays, note),
              actorId,
              bearer);
      insertHandoff(
          planId,
          "6.2.1.2",
          "POST /trade/replenishment-suggestions/batches",
          PURCHASE_DEMAND_OWNER_MODULE,
          true,
          false,
          "SUCCEEDED",
          result.batchNo() == null ? "" : result.batchNo(),
          "补货建议批次 "
              + result.batchNo()
              + "：建议 "
              + result.itemCount()
              + " 项，需补货 "
              + result.replenishCount()
              + " 项，无需补 "
              + result.holdCount()
              + " 项，无销量数据 "
              + result.noSalesDataCount()
              + " 项",
          "",
          "⚠️ 6.2.1.2 的供应商交期取自 catalog 报价与它自有的采购配置"
              + "（replenishment_order_policy），本模块的量产批量**无法**参数化它"
              + "（唯一入口写的是人工策略，其 upsert 会顶掉既有起订额）"
              + "⇒ 本次交接是**重算触发**，量产参数**未**进入 6.2.1.2。"
              + "覆盖天数 "
              + coverageDays
              + " 天来自"
              + (fromPolicy ? "租户配置的默认覆盖策略。" : "调用方兜底值（租户未配置默认覆盖策略）。"),
          actorId);
    } catch (RuntimeException e) {
      // 🔴 失败也必须留痕：「交掉了没有」是可查的事实，不是一句返回值。
      String reason = e == null ? "未知原因" : String.valueOf(e.getMessage());
      insertHandoff(
          planId,
          "6.2.1.2",
          "POST /trade/replenishment-suggestions/batches",
          PURCHASE_DEMAND_OWNER_MODULE,
          false,
          false,
          "FAILED",
          "",
          "",
          truncate(reason, 1000),
          "本次交接未完成；量产计划的下达本身**已落库**（下达是硬事实，交接是派生动作，"
              + "交接失败不回滚下达）。",
          actorId);
    }
  }

  /** 写一条下游交接留痕。 */
  private void insertHandoff(
      UUID planId,
      String targetModule,
      String targetEndpoint,
      String ownerModule,
      boolean available,
      boolean inputApplied,
      String status,
      String resultRef,
      String resultSummary,
      String errorMessage,
      String impact,
      UUID actorId) {
    sql.execute(
        "insert into mass_production_downstream_handoff(id,tenant_id,plan_id,target_module,"
            + " target_endpoint,owner_module,available,mass_production_input_applied,status,"
            + " result_ref,result_summary,error_message,impact,attempted_at,created_by,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::boolean,?::boolean,"
            + " ?::varchar,?::varchar,?::varchar,?::varchar,?::varchar,clock_timestamp(),?::uuid,now())",
        UUID.randomUUID(),
        currentTenant(),
        planId,
        targetModule,
        targetEndpoint,
        ownerModule,
        available,
        inputApplied,
        status,
        resultRef == null ? "" : resultRef,
        resultSummary == null ? "" : resultSummary,
        errorMessage == null ? "" : errorMessage,
        impact == null ? "" : impact,
        actorId);
  }

  /** 租户配置的默认覆盖天数（6.2.1.2 自己的配置表；**只读**）。没配则返回 null。 */
  private Integer configuredCoverageDays() {
    Record r =
        sql.fetchOne(
            "select coverage_days from replenishment_coverage_policy"
                + " where tenant_id = ?::uuid and scope = 'DEFAULT'"
                + "   and effective_from <= (current_timestamp at time zone 'UTC')::date"
                + "   and (effective_to is null or effective_to > (current_timestamp at time zone 'UTC')::date)"
                + " order by effective_from desc limit 1",
            currentTenant());
    return r == null ? null : r.get("coverage_days", Integer.class);
  }

  private int defaultCoverageDays() {
    Integer configured = configuredCoverageDays();
    return configured == null ? FALLBACK_COVERAGE_DAYS : configured;
  }

  // ================================================================ 成本对比

  /**
   * 量产目标成本 vs 上游试产实测成本。
   *
   * <p>🔴 <b>只在同一币种内可比</b>；跨币种<b>不做固定汇率折算</b>（5.2.2.1 未交付）；
   * 目标成本取不到 ⇒ {@code comparable=false} 且各金额字段为 {@code null}（<b>不是 0</b>）。
   */
  public MassProductionCostComparisonRow costComparison(UUID planId) {
    Record plan =
        sql.fetchOne(
            "select p.currency, p.target_cost, p.trial_order_id,"
                + " c.total_amount as trial_cost, c.currency as trial_currency"
                + " from mass_production_plan p"
                + " left join production_trial_cost c"
                + "   on c.tenant_id = p.tenant_id and c.trial_order_id = p.trial_order_id"
                + " where p.tenant_id = ?::uuid and p.id = ?::uuid",
            currentTenant(),
            planId);
    if (plan == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "量产计划不存在或无权查看：" + planId);
    }
    String currency = plan.get("currency", String.class);
    BigDecimal targetCost = plan.get("target_cost", BigDecimal.class);
    BigDecimal trialCost = plan.get("trial_cost", BigDecimal.class);
    String trialCurrency = plan.get("trial_currency", String.class);

    if (targetCost == null) {
      return new MassProductionCostComparisonRow(
          currency,
          trialCost,
          null,
          null,
          null,
          false,
          "量产目标成本**未填**（取不到就是取不到，不是 0）⇒ 试产实测成本与量产目标成本不可比。");
    }
    if (trialCost == null) {
      return new MassProductionCostComparisonRow(
          currency,
          null,
          targetCost,
          null,
          null,
          false,
          "上游试产单还没有成本数据 ⇒ 试产实测成本与量产目标成本不可比。");
    }
    if (trialCurrency == null || !trialCurrency.equals(currency)) {
      return new MassProductionCostComparisonRow(
          currency,
          trialCost,
          targetCost,
          null,
          null,
          false,
          "上游试产单的币种是「"
              + (trialCurrency == null ? "（未知）" : trialCurrency)
              + "」，本计划的币种是「"
              + currency
              + "」：跨币种**不可比**，本服务**不做固定汇率折算**（5.2.2.1 汇率查询未交付）。");
    }
    return new MassProductionCostComparisonRow(
        currency,
        trialCost,
        targetCost,
        targetCostVarianceAmount(trialCost, targetCost),
        targetCostVarianceRate(trialCost, targetCost),
        true,
        "差额 = 试产实测成本 − 量产目标成本，偏差率 = 差额 ÷ 量产目标成本（比例，不是百分数）。"
            + "🔴 跨币种不可比，本服务不做固定汇率折算（5.2.2.1 未交付）。");
  }

  // ================================================================ 迁移历史

  /** 状态迁移事件清单（详情页时间线）。 */
  public List<MassProductionPlanTransitionRow> listTransitions(UUID planId) {
    List<MassProductionPlanTransitionRow> rows = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id,plan_id,from_status,to_status,reason,actor_id,occurred_at"
                + " from mass_production_plan_transition where tenant_id = ?::uuid and plan_id = ?::uuid"
                + " order by occurred_at desc, id desc",
            currentTenant(),
            planId)) {
      String from = row.get("from_status", String.class);
      String to = row.get("to_status", String.class);
      rows.add(
          new MassProductionPlanTransitionRow(
              row.get("id", UUID.class),
              row.get("plan_id", UUID.class),
              from,
              MassProductionPlanStatus.valueOf(from).label(),
              to,
              MassProductionPlanStatus.valueOf(to).label(),
              row.get("reason", String.class),
              row.get("actor_id", UUID.class),
              row.get("occurred_at", OffsetDateTime.class)));
    }
    return rows;
  }

  // ================================================================ 内部：行与条件

  private static final String PLAN_SELECT =
      "select p.id,p.plan_no,p.trial_order_id,p.trial_no,p.trial_recommendation_decision,"
          + " p.bom_version_id,p.bom_version_no,p.bom_code,p.output_sku_code,p.output_sku_name,"
          + " p.plan_quantity,p.target_cost,p.currency,p.production_line_code,p.production_line_name,"
          + " p.factory_code,p.factory_name,p.status,p.decision,p.decision_source,"
          + " p.decision_rationale,p.override_reason,p.decided_by,p.decided_at,"
          + " p.planned_start_at,p.planned_end_at,p.note,p.created_by,p.created_at,p.updated_at,"
          + " (select count(*) from mass_production_bom_line l"
          + "   where l.tenant_id = p.tenant_id and l.plan_id = p.id) as bom_line_count,"
          + " (select f.status from mass_production_sku_filing f"
          + "   where f.tenant_id = p.tenant_id and f.plan_id = p.id) as filing_status,"
          + " (select f.target_sku_id from mass_production_sku_filing f"
          + "   where f.tenant_id = p.tenant_id and f.plan_id = p.id) as filed_sku_id,"
          + " (select f.target_sku_code from mass_production_sku_filing f"
          + "   where f.tenant_id = p.tenant_id and f.plan_id = p.id) as filed_sku_code"
          + " from mass_production_plan p";

  private static MassProductionPlanRow planRow(Record row) {
    String status = row.get("status", String.class);
    String decision = row.get("decision", String.class);
    String decisionSource = row.get("decision_source", String.class);
    // 🔴 没有建档请求时这个子查询返回 NULL ⇒ 归一成空串，不让 NPE 从数据层冒出来。
    String filingStatus = blankToEmpty(row.get("filing_status", String.class));
    return new MassProductionPlanRow(
        row.get("id", UUID.class),
        row.get("plan_no", String.class),
        row.get("trial_order_id", UUID.class),
        row.get("trial_no", String.class),
        row.get("trial_recommendation_decision", String.class),
        row.get("bom_version_id", UUID.class),
        row.get("bom_version_no", String.class),
        row.get("bom_code", String.class),
        row.get("output_sku_code", String.class),
        row.get("output_sku_name", String.class),
        row.get("plan_quantity", BigDecimal.class),
        row.get("target_cost", BigDecimal.class),
        row.get("currency", String.class),
        row.get("production_line_code", String.class),
        row.get("production_line_name", String.class),
        row.get("factory_code", String.class),
        row.get("factory_name", String.class),
        status,
        MassProductionPlanStatus.valueOf(status).label(),
        decision,
        decision.isEmpty() ? null : MassProductionDecision.valueOf(decision).label(),
        decisionSource,
        decisionSource.isEmpty() ? null : MassProductionDecisionSource.valueOf(decisionSource).label(),
        row.get("decision_rationale", String.class),
        row.get("override_reason", String.class),
        row.get("decided_by", UUID.class),
        row.get("decided_at", OffsetDateTime.class),
        row.get("bom_line_count", Integer.class),
        filingStatus,
        filingStatus.isEmpty() ? null : MassProductionFilingStatus.valueOf(filingStatus).label(),
        row.get("filed_sku_id", UUID.class),
        row.get("filed_sku_code", String.class),
        row.get("planned_start_at", OffsetDateTime.class),
        row.get("planned_end_at", OffsetDateTime.class),
        row.get("note", String.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private MassProductionSkuFilingRow filingRow(Record row) {
    String status = row.get("status", String.class);
    UUID filingId = row.get("id", UUID.class);
    List<MassProductionSkuFilingAttributeRow> attributes = new ArrayList<>();
    for (Record attribute :
        sql.fetch(
            "select a.id, a.filing_id, a.line_no, a.attribute_id, a.attribute_code,"
                + " a.attribute_name, a.value_state from mass_production_sku_filing_attribute a"
                + " where a.tenant_id = ?::uuid and a.filing_id = ?::uuid"
                + " order by a.line_no, a.id",
            currentTenant(),
            filingId)) {
      List<String> values = new ArrayList<>();
      for (Record value :
          sql.fetch(
              "select v.value_text from mass_production_sku_filing_attribute_value v"
                  + " where v.tenant_id = ?::uuid and v.attribute_row_id = ?::uuid"
                  + " order by v.value_ordinal, v.id",
              currentTenant(),
              attribute.get("id", UUID.class))) {
        values.add(value.get("value_text", String.class));
      }
      String state = attribute.get("value_state", String.class);
      attributes.add(
          new MassProductionSkuFilingAttributeRow(
              attribute.get("id", UUID.class),
              attribute.get("filing_id", UUID.class),
              attribute.get("line_no", Integer.class),
              attribute.get("attribute_id", UUID.class),
              attribute.get("attribute_code", String.class),
              attribute.get("attribute_name", String.class),
              state,
              MassProductionAttributeState.valueOf(state).label(),
              values));
    }
    List<MassProductionCustomsElementRow> elements = new ArrayList<>();
    for (Record element :
        sql.fetch(
            "select id, filing_id, ordinal, element_code, element_name, element_value, required"
                + " from mass_production_customs_element where tenant_id = ?::uuid"
                + " and filing_id = ?::uuid order by ordinal, id",
            currentTenant(),
            filingId)) {
      elements.add(
          new MassProductionCustomsElementRow(
              element.get("id", UUID.class),
              element.get("filing_id", UUID.class),
              element.get("ordinal", Integer.class),
              element.get("element_code", String.class),
              element.get("element_name", String.class),
              element.get("element_value", String.class),
              Boolean.TRUE.equals(element.get("required", Boolean.class))));
    }
    return new MassProductionSkuFilingRow(
        filingId,
        row.get("filing_no", String.class),
        row.get("plan_id", UUID.class),
        row.get("target_system", String.class),
        row.get("target_endpoint", String.class),
        row.get("target_spu_id", UUID.class),
        row.get("target_spu_code", String.class),
        row.get("target_spu_name", String.class),
        row.get("sku_code", String.class),
        row.get("sku_name", String.class),
        row.get("barcode", String.class),
        row.get("base_unit", String.class),
        row.get("sku_status", String.class),
        row.get("idempotency_key", String.class),
        status,
        MassProductionFilingStatus.valueOf(status).label(),
        row.get("target_sku_id", UUID.class),
        row.get("target_sku_code", String.class),
        row.get("customs_hs_code", String.class),
        row.get("customs_origin_country_code", String.class),
        row.get("customs_declared_name", String.class),
        row.get("customs_declared_value_reference", BigDecimal.class),
        row.get("customs_statutory_unit_code", String.class),
        Boolean.TRUE.equals(row.get("customs_preferential_origin_eligible", Boolean.class)),
        row.get("customs_preference_agreement", String.class),
        row.get("customs_note", String.class),
        row.get("error_message", String.class),
        row.get("submitted_at", OffsetDateTime.class),
        row.get("confirmed_at", OffsetDateTime.class),
        attributes,
        elements,
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private MassProductionDecision parseDecision(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "迁到「已决策」时必须给决策结论（转量产 / 暂缓量产）");
    }
    try {
      return MassProductionDecision.valueOf(raw.trim());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "未知的决策结论：" + raw + "（只能是 GO / HOLD）");
    }
  }

  private MassProductionDecisionSource parseDecisionSource(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "迁到「已决策」时必须给决策来源（跟随上游建议 / 人工覆盖）");
    }
    try {
      return MassProductionDecisionSource.valueOf(raw.trim());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "未知的决策来源：" + raw);
    }
  }

  private void requirePlanExists(UUID planId) {
    var row =
        sql.fetchOne(
            "select id from mass_production_plan where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            planId);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "量产计划不存在或无权查看：" + planId);
    }
  }

  private String planNoOf(UUID planId) {
    var row =
        sql.fetchOne(
            "select plan_no from mass_production_plan where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            planId);
    return row == null ? "" : row.get("plan_no", String.class);
  }

  private String planQuantityText(UUID planId) {
    var row =
        sql.fetchOne(
            "select plan_quantity from mass_production_plan where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            planId);
    return row == null ? "" : String.valueOf(row.get("plan_quantity", BigDecimal.class));
  }

  private String targetCostText(UUID planId) {
    var row =
        sql.fetchOne(
            "select target_cost, currency from mass_production_plan where tenant_id = ?::uuid"
                + " and id = ?::uuid",
            currentTenant(),
            planId);
    if (row == null) {
      return "";
    }
    BigDecimal cost = row.get("target_cost", BigDecimal.class);
    return cost == null
        ? "（未填）"
        : cost + " " + row.get("currency", String.class);
  }

  private UUID currentTenant() {
    var row =
        sql.fetchOne("select nullif(current_setting('app.current_tenant', true), '')::uuid as t");
    UUID tenant = row == null ? null : row.get("t", UUID.class);
    if (tenant == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    return tenant;
  }

  private long count(String query, Object... binds) {
    var row = sql.fetchOne(query, binds);
    return row == null ? 0L : row.get(0, Long.class);
  }

  private static int pageSize(int size) {
    if (size <= 0) {
      return 50;
    }
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private static List<Object> append(List<Object> binds, Object... extra) {
    List<Object> all = new ArrayList<>(binds);
    for (Object value : extra) {
      all.add(value);
    }
    return all;
  }

  private static String blankToEmpty(String value) {
    return value == null ? "" : value.trim();
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return "";
    }
    return value.length() > max ? value.substring(0, max) : value;
  }
}
