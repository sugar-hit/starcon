package com.starcon.trade;

import static com.starcon.trade.ProductionTrialModels.*;
import static com.starcon.trade.ProductionTrialRules.*;

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
 * 6.6.2.1 试产单。
 *
 * <p>蓝图原话：「管理试产：小批量试产（数量、BOM 版本、组件需求、产线/加工厂、质检标准、试产成本与良率），
 * 试产结果记录并对比量产目标，输出转量产建议。」输入「试产申请（BOM 版本、目标数量）；质检方案；成本数据」。
 * 输出「试产单 + 组件领用需求（→ 7 库存）；良率与成本结果；转量产建议」。
 *
 * <p><b>本模块只做一件事：把「这一轮要小批量试产」变成一份有凭据、状态受控、
 * 组件需求可追、良率与成本可比、并且给出转量产建议的试产单。</b>
 *
 * <p><b>🔴 十四条实现纪律</b>：
 *
 * <ol>
 *   <li><b>状态机在服务层 + 数据库层两处钉死</b>：Java 侧
 *       {@link ProductionTrialRules#allowedTargets}，DB 侧 V264 迁移的
 *       {@code guard_production_trial_order_status()}。客户端<b>传什么状态不算数</b>。</li>
 *   <li><b>良率是数据库推导出来的</b>：{@code guard_production_trial_result()} 按
 *       合格 ÷ 产出 算出良率、按 产出 − 合格 算出不良数量，客户端报上来的一律被覆盖。</li>
 *   <li><b>🔴 组件领用需求是「需求」，不是库存</b>：本模块**不建库存扣减表**、
 *       <b>不改 warehouse 的库存</b>。蓝图加粗句「试产消耗真实组件库存，需走 8 加工单
 *       而非直接改库存」的落点在这里 —— 需求由本模块产出，领用由 8.4.1 组装单承接，
 *       跨服务衔接留待授权 API / event。</li>
 *   <li><b>BOM 版本（catalog 独立库）逻辑引用 + 展示快照</b>：{@code bom_version_id} +
 *       版本号 / BOM 编码 / 成品 SKU 快照，<b>零跨库 FK、零跨库视图、零跨服务直连</b>。</li>
 *   <li><b>组件需求量原样搬运 catalog 的 R6/R7</b>：本服务**不重算**，
 *       否则两处各实现一遍必然漂移，而漂移的后果是「按不同口径领料」。</li>
 *   <li><b>打样单（6.6.1.2，同一个 trade 库）只读引用 + 快照</b>，零外键、零 ALTER。</li>
 *   <li><b>一张试产单一个币种</b>：明细行币种必须与成本头一致（DB 触发器钉死），
 *       不同币种的数绝不相加。</li>
 *   <li><b>「试产成本 vs 量产目标成本」只在同一币种内可比</b>：跨币种按币种分组，
 *       <b>不做固定汇率折算</b>；量产目标成本取不到时是 {@code null}（<b>不是 0</b>）。</li>
 *   <li><b>「建议转量产」需要举证</b>：良率门槛与成本门槛都达标才允许给
 *       {@code GO}；{@code NO_GO} 不需要举证（关键质检项不合格也足以否掉量产）。</li>
 *   <li><b>转量产建议是「建议事实」</b>：6.6.2.2 未开发 ⇒ 本模块<b>不建 SKU</b>、
 *       不调商品服务写接口，只落一条建议（结论 + 文本 + 生成条件 + 实测快照）。</li>
 *   <li><b>开产前组件领用需求必须已提交</b>：DB 守卫会挡。没有领用需求就开产，
 *       等于凭空消耗了一批组件而没人知道该领什么。</li>
 *   <li><b>完成前必须有试产结果且质检项全部判定完毕</b>：DB 守卫会挡。</li>
 *   <li><b>时间一律 UTC 口径</b>：{@code timestamptz} 列用 {@link OffsetDateTime} 绑定，
 *       values 位置写 {@code ?::timestamptz}。</li>
 * </ol>
 */
@Service
public class ProductionTrialService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 真实库存扣减的归管模块（8.4.1 组装单，warehouse 独立库）。 */
  static final String INVENTORY_OWNER_MODULE = "8.4.1 组装单";

  /** 转量产与建档的归管模块（6.6.2.2，未开发）。 */
  static final String MASS_PRODUCTION_OWNER_MODULE = "6.6.2.2 转量产与建档";

  /** 汇率查询的归管模块（5.2.2.1，未交付 ⇒ 不做固定汇率折算）。 */
  static final String EXCHANGE_RATE_OWNER_MODULE = "5.2.2.1 汇率查询";

  /**
   * 留痕里的「操作人姓名」占位。
   *
   * <p>🔴 本服务<b>拿不到</b>操作人的显示名：{@code AccessService.Access} 只有 userId / tenantId /
   * membershipId / departmentId，显示名在 identity 服务里。<b>不去猜、不去编</b> ——
   * 留痕的<b>主体是 actor_id</b>（谁做的），姓名留空比编一个强。
   */
  static final String ACTOR_NAME_UNKNOWN = "";

  private final DSLContext sql;
  private final ProductionTrialBomPort bom;
  private final BomVersionPort bomVersion;

  public ProductionTrialService(
      DSLContext sql, ProductionTrialBomPort bom, BomVersionPort bomVersion) {
    this.sql = sql;
    this.bom = bom;
    this.bomVersion = bomVersion;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：BOM 版本（3.5.2.1，catalog）<b>已交付</b> ⇒ 可用；
   * 打样单（6.6.1.2，同库）<b>已交付</b> ⇒ 可用；而<b>真实库存扣减</b>（8.4.1 组装单）、
   * <b>转量产建档</b>（6.6.2.2）、<b>汇率</b>（5.2.2.1）三件事<b>做不到</b> ——
   * 哪一个接通了必须由接口自己报出。
   */
  public ProductionTrialCapabilities capabilities() {
    return new ProductionTrialCapabilities(
        true,
        "3.5.2.1 BOM 版本管理（catalog 服务，独立库，已交付 V25）",
        "bom_version_id 是**逻辑引用**：本模块**零跨库 FOREIGN KEY、零跨库视图、零跨服务直连**，"
            + "只经 catalog 的授权 HTTP 接口读（GET /catalog/bom-versions/{id} 与 /requirement），"
            + "并把版本号 / BOM 编码 / 成品 SKU 存成**展示快照** —— catalog 改版后仍能显示当时用的是哪一版。",
        true,
        "3.5.2.1 的 GET /catalog/bom-versions/{id}/requirement（catalog 规则 R6/R7）",
        "组件需求量**原样搬运 catalog 算好的三元组**（单位用量 / 损耗率 / 需求量），本服务**不重算、不取整** —— "
            + "两处各实现一遍 R7 必然漂移，而漂移的后果是「按不同口径领料」。",
        false,
        INVENTORY_OWNER_MODULE,
        "🔴 蓝图加粗句「**试产消耗真实组件库存，需走 8 加工单而非直接改库存**」——"
            + "本模块**只产出组件领用需求**（组件清单 + 需求量 + 关联试产单），"
            + "**不建库存扣减表、不改 warehouse 的库存**：真正的领用由 8.4.1 组装单承接。",
        false,
        INVENTORY_OWNER_MODULE,
        "本模块与 8.4.1 组装单之间**尚无自动衔接**（授权 API / event 未落地）："
            + "组件领用需求提交后停在「已提交领用需求」，"
            + "**真实领用与库存扣减由 warehouse 侧承接**；"
            + "组装单承接后由调用方回填「已由加工单承接」（逻辑引用 + 组装单号快照），本模块**不跨库写**。",
        false,
        MASS_PRODUCTION_OWNER_MODULE,
        "本模块产出的是**转量产建议事实**（建议结论 + 建议文本 + 生成条件 + 实测快照），"
            + "**不是**真的建 SKU：量产 BOM 定版、批量采购计划与正式 SKU 建档归 6.6.2.2，"
            + "本模块**不建 SKU 表、不调商品服务写接口**。",
        true,
        "6.6.1.2 打样与样品管理（trade 服务，同一个库，已交付 V261）",
        "打样单**只读引用 + 展示快照**（development_sample_order_id + 单号快照）："
            + "**只 SELECT，不建指向它的外键、不加约束、不 ALTER** —— 打样的事实归 6.6.1.2 所有。",
        false,
        EXCHANGE_RATE_OWNER_MODULE,
        "「试产成本 vs 量产目标成本」**只在同一币种内可比**：跨币种按币种分组，"
            + "**不做固定汇率折算**（5.2.2.1 未交付）；量产目标成本取不到时如实标「不可比」，"
            + "**不拿 0 顶替**。",
        "🔴 本模块的边界：试产单 + 组件领用需求 + 质检标准 + 良率与成本结果 + 转量产建议。"
            + "**组件的库存扣减不在这里**（归 8.4.1 组装单），**量产建档不在这里**（归 6.6.2.2）。");
  }

  // ================================================================ 下拉项

  /** 状态下拉（含每个状态允许迁到哪些状态）、需求状态、判定结果、成本类型、建议结论。 */
  public ProductionTrialOptions options() {
    List<ProductionTrialStatusOption> statuses = new ArrayList<>();
    for (ProductionTrialStatus status : ProductionTrialStatus.values()) {
      statuses.add(
          new ProductionTrialStatusOption(
              status.name(),
              status.label(),
              isTerminalStatus(status),
              allowedTargets(status).stream().map(Enum::name).toList()));
    }
    List<ProductionTrialRequirementStatusOption> requirementStatuses = new ArrayList<>();
    for (ProductionTrialRequirementStatus status : ProductionTrialRequirementStatus.values()) {
      requirementStatuses.add(
          new ProductionTrialRequirementStatusOption(status.name(), status.label()));
    }
    List<ProductionTrialJudgmentOption> judgments = new ArrayList<>();
    for (ProductionTrialJudgment judgment : ProductionTrialJudgment.values()) {
      judgments.add(new ProductionTrialJudgmentOption(judgment.name(), judgment.label()));
    }
    List<ProductionTrialCostTypeOption> costTypes = new ArrayList<>();
    for (ProductionTrialCostType type : ProductionTrialCostType.values()) {
      costTypes.add(new ProductionTrialCostTypeOption(type.name(), type.label()));
    }
    List<ProductionTrialMassProductionDecisionOption> decisions = new ArrayList<>();
    for (ProductionTrialMassProductionDecision decision : ProductionTrialMassProductionDecision.values()) {
      decisions.add(new ProductionTrialMassProductionDecisionOption(decision.name(), decision.label()));
    }
    return new ProductionTrialOptions(statuses, requirementStatuses, judgments, costTypes, decisions);
  }

  // ================================================================ 概览

  /** 按状态分组的概览 + 按币种分组的成本与成本对比。 */
  public ProductionTrialSummary summary() {
    UUID tenantId = currentTenant();
    long totalTrials = count("select count(*) from production_trial_order where tenant_id = ?::uuid", tenantId);
    long totalRequirements =
        count(
            "select count(*) from production_trial_component_requirement where tenant_id = ?::uuid",
            tenantId);
    long pendingRequirements =
        count(
            "select count(*) from production_trial_component_requirement where tenant_id = ?::uuid"
                + " and requirement_status = 'DRAFT'",
            tenantId);
    long totalInspectionItems =
        count("select count(*) from production_trial_inspection_item where tenant_id = ?::uuid", tenantId);
    long pendingInspectionItems =
        count(
            "select count(*) from production_trial_inspection_item where tenant_id = ?::uuid"
                + " and judgment = 'PENDING'",
            tenantId);
    long completedTrials =
        count(
            "select count(*) from production_trial_order where tenant_id = ?::uuid"
                + " and status = 'COMPLETED'",
            tenantId);
    long recommendationCount =
        count(
            "select count(*) from production_trial_mass_production_recommendation"
                + " where tenant_id = ?::uuid",
            tenantId);

    Map<String, Long> statusCounts = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select status, count(*) as c from production_trial_order where tenant_id = ?::uuid"
                + " group by status",
            tenantId)) {
      statusCounts.put(row.get("status", String.class), row.get("c", Long.class));
    }
    List<ProductionTrialStatusCount> statuses = new ArrayList<>();
    for (ProductionTrialStatus status : ProductionTrialStatus.values()) {
      statuses.add(
          new ProductionTrialStatusCount(
              status.name(), status.label(), statusCounts.getOrDefault(status.name(), 0L)));
    }

    // 🔴 成本合计：一张单一个币种 ⇒ 跨币种绝不相加，逐币种分组返回。
    List<ProductionTrialCostCurrencyTotal> costByCurrency = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select currency, sum(total_amount) as t, count(*) as c from production_trial_cost"
                + " where tenant_id = ?::uuid group by currency order by currency",
            tenantId)) {
      costByCurrency.add(
          new ProductionTrialCostCurrencyTotal(
              row.get("currency", String.class),
              row.get("t", BigDecimal.class),
              row.get("c", Long.class)));
    }

    List<ProductionTrialCostComparisonRow> comparison = summaryComparison(tenantId);

    List<ProductionTrialOrderRow> recent = new ArrayList<>();
    for (Record row :
        sql.fetch(
            ORDER_SELECT + " where o.tenant_id = ?::uuid order by o.created_at desc, o.id desc"
                + " limit 10",
            tenantId)) {
      recent.add(orderRow(row));
    }
    return new ProductionTrialSummary(
        totalTrials,
        totalRequirements,
        pendingRequirements,
        totalInspectionItems,
        pendingInspectionItems,
        completedTrials,
        recommendationCount,
        statuses,
        costByCurrency,
        comparison,
        recent);
  }

  /**
   * 概览里的成本对比（**按币种分组**）。
   *
   * <p>🔴 对比组 = 「<b>该币种下有量产目标成本的那些试产单</b>」——
   * 把没有目标成本的试产单混进试产成本里再和目标成本相减，是拿不同集合做比较。
   * 该币种下**一张都没有**目标成本时，标 {@code comparable=false} 并说明原因。
   */
  private List<ProductionTrialCostComparisonRow> summaryComparison(UUID tenantId) {
    List<ProductionTrialCostComparisonRow> rows = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select currency,"
                + " sum(total_amount) as all_trial_cost,"
                + " sum(case when mass_target_cost is not null then total_amount else 0 end)"
                + "   as comparable_trial_cost,"
                + " sum(mass_target_cost) as mass_target_cost,"
                + " count(*) as c,"
                + " count(mass_target_cost) as with_target"
                + " from production_trial_cost where tenant_id = ?::uuid group by currency"
                + " order by currency",
            tenantId)) {
      String currency = row.get("currency", String.class);
      BigDecimal allTrialCost = row.get("all_trial_cost", BigDecimal.class);
      BigDecimal comparableTrialCost = row.get("comparable_trial_cost", BigDecimal.class);
      BigDecimal massTargetCost = row.get("mass_target_cost", BigDecimal.class);
      long withTarget = row.get("with_target", Long.class);
      if (withTarget == 0 || massTargetCost == null) {
        rows.add(
            new ProductionTrialCostComparisonRow(
                currency,
                allTrialCost,
                null,
                null,
                null,
                false,
                currency
                    + " 这个币种下**没有任何一张试产单填了量产目标成本** ⇒ 试产成本与量产目标成本不可比。"
                    + "本服务**不做固定汇率折算**（5.2.2.1 未交付），也不用 0 顶替目标成本。"));
        continue;
      }
      BigDecimal varianceAmount = costVarianceAmount(comparableTrialCost, massTargetCost);
      BigDecimal varianceRate = costVarianceRate(comparableTrialCost, massTargetCost);
      rows.add(
          new ProductionTrialCostComparisonRow(
              currency,
              allTrialCost,
              massTargetCost,
              varianceAmount,
              varianceRate,
              true,
              "试产成本取自该币种下**有量产目标成本的 "
                  + withTarget
                  + " 张**试产单（同一币种才可比）；差额 = 试产成本 − 量产目标成本，"
                  + "偏差率 = 差额 ÷ 量产目标成本（比例，不是百分数）。"));
    }
    return rows;
  }

  // ================================================================ 试产单清单

  /** 试产单清单（真分页 + 真筛选）。 */
  public ProductionTrialOrderPage listOrders(
      String status, UUID bomVersionId, String keyword, Integer page, Integer size) {
    int pageNo = page == null || page < 0 ? 0 : page;
    int pageSize = pageSize(size);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("o.tenant_id = ?::uuid");
    binds.add(currentTenant());
    if (status != null && !status.isBlank()) {
      where.add("o.status = ?::varchar");
      binds.add(status.trim());
    }
    if (bomVersionId != null) {
      where.add("o.bom_version_id = ?::uuid");
      binds.add(bomVersionId);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.add(
          "(o.trial_no ilike ?::varchar or o.bom_version_no ilike ?::varchar"
              + " or o.bom_code ilike ?::varchar or o.output_sku_code ilike ?::varchar"
              + " or o.production_line_name ilike ?::varchar or o.factory_name ilike ?::varchar)");
      String like = "%" + keyword.trim() + "%";
      for (int i = 0; i < 6; i++) {
        binds.add(like);
      }
    }
    String clause = String.join(" and ", where);
    long total =
        count("select count(*) from production_trial_order o where " + clause, binds.toArray());
    List<Record> rows =
        sql.fetch(
            ORDER_SELECT + " where " + clause + " order by o.created_at desc, o.id desc"
                + " limit ? offset ?",
            append(binds, pageSize, (long) pageNo * pageSize).toArray());
    List<ProductionTrialOrderRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(orderRow(row));
    }
    return new ProductionTrialOrderPage(items, total, pageNo, pageSize);
  }

  // ================================================================ 试产单

  /**
   * 建试产单。初始状态恒为「已申请」。
   *
   * <p>🔴 建单时同步产出<b>组件领用需求</b>（从 catalog 的 BOM 版本需求量算）与
   * <b>成本头</b>（一个币种 + 可选的量产目标成本）。
   */
  @Transactional
  public ProductionTrialOrderDetail createOrder(
      ProductionTrialCreateInput input, UUID actorId, String bearer) {
    UUID tenantId = currentTenant();
    String trialNo = input.trialNo().trim();
    var exists =
        sql.fetchOne(
            "select id from production_trial_order where tenant_id = ?::uuid"
                + " and trial_no = ?::varchar",
            tenantId,
            trialNo);
    if (exists != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "试产单号已存在：" + trialNo + "（单号在租户内唯一）");
    }
    String currency = normalizeCurrency(input.currency());
    requirePositiveQuantity(input.targetQuantity());

    // 上游 3.5.2.1 BOM 版本（catalog 独立库）：**逻辑引用 + 展示快照**，零跨库 FK。
    ProductionTrialBomPort.VersionDetail version =
        bom.detail(input.bomVersionId(), bearer);
    if (!"PUBLISHED".equals(version.status())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "BOM 版本「" + version.versionNo() + "」当前是「" + version.statusLabel() + "」，"
              + "试产必须用**已发布**的 BOM 版本（草稿版本的组件清单随时会变，按它领料等于按一份会变的清单领料）");
    }

    // 组件需求量：原样搬运 catalog 的 R6/R7，本服务不重算。
    BomVersionPort.Requirement requirement =
        bomVersion.requirement(input.bomVersionId(), input.targetQuantity(), bearer);
    if (requirement.lines().isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "BOM 版本「" + version.versionNo() + "」没有组件行，算不出组件领用需求 —— "
              + "一个没有组件的 BOM 不可能拿来试产");
    }

    // 上游 6.6.1.2 打样单（同一个 trade 库）：**只读引用 + 快照**。
    if (input.developmentSampleOrderId() != null) {
      requireSampleOrderSnapshot(input.developmentSampleOrderId());
    }

    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into production_trial_order(id,tenant_id,trial_no,bom_version_id,bom_version_no,"
            + " bom_code,output_sku_code,output_sku_name,development_sample_order_id,"
            + " development_sample_order_no,target_quantity,production_line_code,production_line_name,"
            + " factory_code,factory_name,quality_standard_code,quality_standard_note,status,currency,"
            + " requester_id,requester_name,requested_at,planned_start_at,planned_end_at,purpose_note,"
            + " note,created_by,created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::varchar,?::uuid,?::varchar,?::varchar,?::varchar,?::varchar,"
            + " ?::uuid,?::varchar,?::numeric,?::varchar,?::varchar,?::varchar,?::varchar,?::varchar,"
            + " ?::varchar,'REQUESTED',?::varchar,?::uuid,?::varchar,?::timestamptz,?::timestamptz,"
            + " ?::timestamptz,?::varchar,?::varchar,?::uuid,now(),now())",
        id,
        tenantId,
        trialNo,
        input.bomVersionId(),
        version.versionNo(),
        version.bomCode(),
        version.outputSkuCode(),
        version.outputSkuName(),
        input.developmentSampleOrderId(),
        blankToEmpty(input.developmentSampleOrderNo()),
        input.targetQuantity(),
        blankToEmpty(input.productionLineCode()),
        blankToEmpty(input.productionLineName()),
        blankToEmpty(input.factoryCode()),
        blankToEmpty(input.factoryName()),
        blankToEmpty(input.qualityStandardCode()),
        blankToEmpty(input.qualityStandardNote()),
        currency,
        input.requesterId() == null ? actorId : input.requesterId(),
        blankToEmpty(input.requesterName()),
        input.requestedAt(),
        input.plannedStartAt(),
        input.plannedEndAt(),
        blankToEmpty(input.purposeNote()),
        blankToEmpty(input.note()),
        actorId);

    // 🔴 组件领用需求：初始状态一律 DRAFT（**还没交出去，更没扣库存**）。
    int lineNo = 0;
    for (BomVersionPort.RequirementLine line : requirement.lines()) {
      lineNo++;
      sql.execute(
          "insert into production_trial_component_requirement(id,tenant_id,trial_order_id,line_no,"
              + " sku_id,sku_code,sku_name,spec_snapshot,unit_quantity,scrap_rate,required_quantity,"
              + " unit,requirement_status,note,created_by,created_at,updated_at)"
              + " values (?::uuid,?::uuid,?::uuid,?::integer,?::uuid,?::varchar,?::varchar,"
              + " ?::varchar,?::numeric,?::numeric,?::numeric,?::varchar,'DRAFT',?::varchar,?::uuid,"
              + " now(),now())",
          UUID.randomUUID(),
          tenantId,
          id,
          lineNo,
          line.skuId(),
          line.skuCode(),
          "",
          "",
          line.unitQuantity(),
          line.scrapRate(),
          line.requiredQuantity(),
          "",
          "",
          actorId);
    }

    // 质检方案（逐项登记）。
    if (input.inspectionItems() != null) {
      int sort = 0;
      for (ProductionTrialInspectionItemInput item : input.inspectionItems()) {
        sort++;
        sql.execute(
            "insert into production_trial_inspection_item(id,tenant_id,trial_order_id,item_code,"
                + " item_name,standard_value,inspection_method,unit,critical_item,sort_order,"
                + " judgment,note,created_by,created_at,updated_at)"
                + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::varchar,"
                + " ?::varchar,?::boolean,?::integer,'PENDING',?::varchar,?::uuid,now(),now())",
            UUID.randomUUID(),
            tenantId,
            id,
            item.itemCode().trim(),
            item.itemName().trim(),
            blankToEmpty(item.standardValue()),
            blankToEmpty(item.inspectionMethod()),
            blankToEmpty(item.unit()),
            item.criticalItem(),
            item.sortOrder() == null ? sort : item.sortOrder(),
            blankToEmpty(item.note()),
            actorId);
      }
    }

    // 成本头（一个币种）+ 量产目标成本（**取不到就留 null，不拿 0 顶替**）。
    sql.execute(
        "insert into production_trial_cost(id,tenant_id,trial_order_id,currency,total_amount,"
            + " mass_target_cost,note,created_by,created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,0,?::numeric,?::varchar,?::uuid,now(),now())",
        UUID.randomUUID(),
        tenantId,
        id,
        currency,
        input.massTargetCost(),
        "",
        actorId);

    return orderDetail(id);
  }

  /** 试产单详情：试产单 + 组件领用需求 + 质检标准 + 结果 + 成本 + 转量产建议 + 迁移历史。 */
  public ProductionTrialOrderDetail orderDetail(UUID orderId) {
    Record row =
        sql.fetchOne(
            ORDER_SELECT + " where o.tenant_id = ?::uuid and o.id = ?::uuid",
            currentTenant(),
            orderId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "试产单不存在或无权查看：" + orderId);
    }
    ProductionTrialOrderRow order = orderRow(row);
    return new ProductionTrialOrderDetail(
        order,
        listRequirements(orderId),
        listInspectionItems(orderId),
        resultView(orderId),
        costView(orderId),
        recommendationView(orderId),
        listTransitions(orderId),
        allowedTargets(ProductionTrialStatus.valueOf(order.status())).stream()
            .map(Enum::name)
            .toList());
  }

  /**
   * 改试产单（<b>整体替换</b>语义：请求体就是期望的最终状态）。
   *
   * <p>🔴 **BOM 版本、目标数量与币种不可改**：改了它们，已经算好的组件领用需求与
   * 成本对比就全部对不上了 —— 所以它们<b>根本不在改单输入里</b>。
   */
  @Transactional
  public ProductionTrialOrderDetail updateOrder(UUID orderId, ProductionTrialUpdateInput input) {
    UUID tenantId = currentTenant();
    Record row =
        sql.fetchOne(
            "select status from production_trial_order where tenant_id = ?::uuid and id = ?::uuid",
            tenantId,
            orderId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "试产单不存在或无权查看：" + orderId);
    }
    ProductionTrialStatus status = ProductionTrialStatus.valueOf(row.get("status", String.class));
    if (isTerminalStatus(status)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "试产单已是「" + status.label() + "」（终态，不可改）：终态不提供恢复");
    }
    sql.execute(
        "update production_trial_order set production_line_code = ?::varchar,"
            + " production_line_name = ?::varchar, factory_code = ?::varchar,"
            + " factory_name = ?::varchar, quality_standard_code = ?::varchar,"
            + " quality_standard_note = ?::varchar, planned_start_at = ?::timestamptz,"
            + " planned_end_at = ?::timestamptz, purpose_note = ?::varchar, note = ?::varchar,"
            + " updated_at = now() where tenant_id = ?::uuid and id = ?::uuid",
        blankToEmpty(input.productionLineCode()),
        blankToEmpty(input.productionLineName()),
        blankToEmpty(input.factoryCode()),
        blankToEmpty(input.factoryName()),
        blankToEmpty(input.qualityStandardCode()),
        blankToEmpty(input.qualityStandardNote()),
        input.plannedStartAt(),
        input.plannedEndAt(),
        blankToEmpty(input.purposeNote()),
        blankToEmpty(input.note()),
        tenantId,
        orderId);
    return orderDetail(orderId);
  }

  /**
   * 推进试产单状态。
   *
   * <p>🔴 只能沿状态机上存在的边走；已关闭 / 已取消是终态；
   * 每次迁移都会落一条不可改写的事件。
   */
  @Transactional
  public ProductionTrialOrderDetail transitionOrder(
      UUID orderId, ProductionTrialTransitionInput input, UUID actorId) {
    UUID tenantId = currentTenant();
    Record row =
        sql.fetchOne(
            "select status from production_trial_order where tenant_id = ?::uuid and id = ?::uuid",
            tenantId,
            orderId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "试产单不存在或无权查看：" + orderId);
    }
    ProductionTrialStatus from = ProductionTrialStatus.valueOf(row.get("status", String.class));
    requireTransition(from, input.toStatus());

    // 🔴 这两条前置检查与 V264 的 DB 守卫**同构**，但服务层先挡一道 ——
    //    让调用方读到「还有 2 条组件领用需求未提交」这样的可读原因，
    //    而不是 DB 约束冲突（23514）被翻译成一句笼统的「数据与既有记录冲突」。
    if (input.toStatus() == ProductionTrialStatus.IN_PROGRESS) {
      requireAllRequirementsSubmitted(orderId);
    }
    if (input.toStatus() == ProductionTrialStatus.COMPLETED) {
      requireResultRecorded(orderId);
      requireAllInspectionItemsJudged(orderId);
    }

    // 先落事件，再改状态 —— DB 守卫会检查「有没有对应事件」。
    sql.execute(
        "insert into production_trial_order_transition(id,tenant_id,trial_order_id,from_status,"
            + " to_status,reason,actor_id,actor_name,occurred_at,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::uuid,?::varchar,"
            + " ?::timestamptz,now())",
        UUID.randomUUID(),
        tenantId,
        orderId,
        from.name(),
        input.toStatus().name(),
        input.reason().trim(),
        actorId,
        ACTOR_NAME_UNKNOWN,
        OffsetDateTime.now());
    sql.execute(
        "update production_trial_order set status = ?::varchar, updated_at = now()"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        input.toStatus().name(),
        tenantId,
        orderId);
    return orderDetail(orderId);
  }

  // ================================================================ 组件领用需求

  /** 组件领用需求清单（🔴 需求，不是库存）。 */
  public List<ProductionTrialComponentRequirementRow> listRequirements(UUID orderId) {
    requireOrderExists(orderId);
    List<ProductionTrialComponentRequirementRow> rows = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id,trial_order_id,line_no,sku_id,sku_code,sku_name,spec_snapshot,unit_quantity,"
                + " scrap_rate,required_quantity,unit,requirement_status,assembly_order_ref_id,"
                + " assembly_order_no,note,created_by,created_at,updated_at"
                + " from production_trial_component_requirement"
                + " where tenant_id = ?::uuid and trial_order_id = ?::uuid order by line_no, id",
            currentTenant(),
            orderId)) {
      rows.add(requirementRow(row));
    }
    return rows;
  }

  /**
   * 提交一条组件领用需求（DRAFT → SUBMITTED）。
   *
   * <p>🔴 <b>这一步不扣库存</b>：它只是把「要领什么、领多少」交出去，
   * 真实领用由 8.4.1 组装单承接。
   */
  @Transactional
  public List<ProductionTrialComponentRequirementRow> submitRequirement(
      UUID orderId, UUID requirementId, UUID actorId) {
    UUID tenantId = currentTenant();
    Record row = requireRequirement(orderId, requirementId);
    String status = row.get("requirement_status", String.class);
    if (!ProductionTrialRequirementStatus.DRAFT.name().equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "组件领用需求当前是「"
              + ProductionTrialRequirementStatus.valueOf(status).label()
              + "」，只有「草稿」状态可以提交领用需求");
    }
    requireWorkingStage(orderId);
    sql.execute(
        "update production_trial_component_requirement set requirement_status = 'SUBMITTED',"
            + " updated_at = now() where tenant_id = ?::uuid and id = ?::uuid",
        tenantId,
        requirementId);
    return listRequirements(orderId);
  }

  /**
   * 记录「已由加工单承接」（SUBMITTED → RELEASED）。
   *
   * <p>🔴 这里存的是 8.4.1 组装单的<b>逻辑引用 + 展示快照</b>，
   * <b>不跨库写</b>：warehouse 的组装单与库存是它们的事实。
   */
  @Transactional
  public List<ProductionTrialComponentRequirementRow> releaseRequirement(
      UUID orderId, UUID requirementId, ProductionTrialRequirementReleaseInput input, UUID actorId) {
    UUID tenantId = currentTenant();
    requireRequirement(orderId, requirementId);
    String status =
        sql.fetchOne(
                "select requirement_status from production_trial_component_requirement"
                    + " where tenant_id = ?::uuid and id = ?::uuid",
                tenantId,
                requirementId)
            .get("requirement_status", String.class);
    if (!ProductionTrialRequirementStatus.SUBMITTED.name().equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "组件领用需求当前是「"
              + ProductionTrialRequirementStatus.valueOf(status).label()
              + "」，只有「已提交领用需求」才能记录加工单承接");
    }
    requireWorkingStage(orderId);
    sql.execute(
        "update production_trial_component_requirement set requirement_status = 'RELEASED',"
            + " assembly_order_ref_id = ?::uuid, assembly_order_no = ?::varchar, note = ?::varchar,"
            + " updated_at = now() where tenant_id = ?::uuid and id = ?::uuid",
        input.assemblyOrderRefId(),
        input.assemblyOrderNo().trim(),
        blankToEmpty(input.note()),
        tenantId,
        requirementId);
    return listRequirements(orderId);
  }

  // ================================================================ 质检标准

  /** 质检标准项清单（含判定结果）。 */
  public List<ProductionTrialInspectionItemRow> listInspectionItems(UUID orderId) {
    requireOrderExists(orderId);
    List<ProductionTrialInspectionItemRow> rows = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id,trial_order_id,item_code,item_name,standard_value,inspection_method,unit,"
                + " critical_item,sort_order,judgment,measured_value,judged_at,judged_by,note,"
                + " created_by,created_at,updated_at from production_trial_inspection_item"
                + " where tenant_id = ?::uuid and trial_order_id = ?::uuid"
                + " order by sort_order, item_code, id",
            currentTenant(),
            orderId)) {
      rows.add(inspectionItemRow(row));
    }
    return rows;
  }

  /** 登记一条质检标准项。 */
  @Transactional
  public List<ProductionTrialInspectionItemRow> createInspectionItem(
      UUID orderId, ProductionTrialInspectionItemInput input, UUID actorId) {
    UUID tenantId = currentTenant();
    requireWorkingStage(orderId);
    var exists =
        sql.fetchOne(
            "select id from production_trial_inspection_item where tenant_id = ?::uuid"
                + " and trial_order_id = ?::uuid and item_code = ?::varchar",
            tenantId,
            orderId,
            input.itemCode().trim());
    if (exists != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "质检标准项编号「" + input.itemCode().trim() + "」在这张试产单上已经存在");
    }
    sql.execute(
        "insert into production_trial_inspection_item(id,tenant_id,trial_order_id,item_code,"
            + " item_name,standard_value,inspection_method,unit,critical_item,sort_order,judgment,"
            + " note,created_by,created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::varchar,?::varchar,"
            + " ?::boolean,?::integer,'PENDING',?::varchar,?::uuid,now(),now())",
        UUID.randomUUID(),
        tenantId,
        orderId,
        input.itemCode().trim(),
        input.itemName().trim(),
        blankToEmpty(input.standardValue()),
        blankToEmpty(input.inspectionMethod()),
        blankToEmpty(input.unit()),
        input.criticalItem(),
        input.sortOrder() == null ? 0 : input.sortOrder(),
        "",
        actorId);
    return listInspectionItems(orderId);
  }

  /**
   * 记录一条质检判定。
   *
   * <p>🔴 判定<b>不可撤回成「待判定」</b> —— 撤回等于把「当时判过不合格」这段证据抹掉。
   */
  @Transactional
  public List<ProductionTrialInspectionItemRow> judgeInspectionItem(
      UUID orderId, UUID itemId, ProductionTrialInspectionJudgmentInput input, UUID actorId) {
    UUID tenantId = currentTenant();
    requireWorkingStage(orderId);
    requireJudgment(input.judgment());
    var row =
        sql.fetchOne(
            "select id from production_trial_inspection_item where tenant_id = ?::uuid"
                + " and trial_order_id = ?::uuid and id = ?::uuid",
            tenantId,
            orderId,
            itemId);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "质检标准项不存在或无权查看：" + itemId);
    }
    sql.execute(
        "update production_trial_inspection_item set judgment = ?::varchar,"
            + " measured_value = ?::varchar, judged_at = ?::timestamptz, judged_by = ?::uuid,"
            + " note = ?::varchar, updated_at = now()"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        input.judgment().name(),
        blankToEmpty(input.measuredValue()),
        OffsetDateTime.now(),
        actorId,
        blankToEmpty(input.note()),
        tenantId,
        itemId);
    return listInspectionItems(orderId);
  }

  // ================================================================ 试产结果

  /** 试产结果（良率）。没记录时返回 null（**「没记录」与「良率 0」是两件事**）。 */
  public ProductionTrialResultRow resultView(UUID orderId) {
    Record row =
        sql.fetchOne(
            "select id,trial_order_id,output_quantity,qualified_quantity,defective_quantity,"
                + " yield_rate,conclusion,recorded_at,recorded_by,note,created_at,updated_at"
                + " from production_trial_result where tenant_id = ?::uuid and trial_order_id = ?::uuid",
            currentTenant(),
            orderId);
    return row == null ? null : resultRow(row);
  }

  /**
   * 记录试产结果（产出 / 合格数量 + 结论）。
   *
   * <p>🔴 <b>良率不收</b>：由数据库触发器按 合格 ÷ 产出 推导（比例，6 位小数）。
   */
  @Transactional
  public ProductionTrialResultRow recordResult(
      UUID orderId, ProductionTrialResultInput input, UUID actorId) {
    UUID tenantId = currentTenant();
    requireWorkingStage(orderId);
    requirePositiveQuantity(input.outputQuantity());
    // 良率的可算性先在服务层挡一道（DB 触发器还会再挡一次）。
    yieldRate(input.qualifiedQuantity(), input.outputQuantity());

    var exists =
        sql.fetchOne(
            "select id from production_trial_result where tenant_id = ?::uuid"
                + " and trial_order_id = ?::uuid",
            tenantId,
            orderId);
    if (exists == null) {
      sql.execute(
          "insert into production_trial_result(id,tenant_id,trial_order_id,output_quantity,"
              + " qualified_quantity,defective_quantity,yield_rate,conclusion,recorded_at,recorded_by,"
              + " note,created_at,updated_at)"
              + " values (?::uuid,?::uuid,?::uuid,?::numeric,?::numeric,0,0,?::varchar,?::timestamptz,"
              + " ?::uuid,?::varchar,now(),now())",
          UUID.randomUUID(),
          tenantId,
          orderId,
          input.outputQuantity(),
          input.qualifiedQuantity(),
          input.conclusion().trim(),
          input.recordedAt(),
          actorId,
          blankToEmpty(input.note()));
    } else {
      sql.execute(
          "update production_trial_result set output_quantity = ?::numeric,"
              + " qualified_quantity = ?::numeric, conclusion = ?::varchar,"
              + " recorded_at = ?::timestamptz, recorded_by = ?::uuid, note = ?::varchar,"
              + " updated_at = now() where tenant_id = ?::uuid and trial_order_id = ?::uuid",
          input.outputQuantity(),
          input.qualifiedQuantity(),
          input.conclusion().trim(),
          input.recordedAt(),
          actorId,
          blankToEmpty(input.note()),
          tenantId,
          orderId);
    }
    return resultView(orderId);
  }

  // ================================================================ 试产成本

  /** 试产成本（成本头 + 明细行）。 */
  public ProductionTrialCostView costView(UUID orderId) {
    Record head =
        sql.fetchOne(
            "select id,currency,total_amount,mass_target_cost from production_trial_cost"
                + " where tenant_id = ?::uuid and trial_order_id = ?::uuid",
            currentTenant(),
            orderId);
    if (head == null) {
      return null;
    }
    UUID costId = head.get("id", UUID.class);
    String currency = head.get("currency", String.class);
    BigDecimal massTarget = head.get("mass_target_cost", BigDecimal.class);
    List<ProductionTrialCostLineRow> lines = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id,cost_id,cost_type,amount,currency,description,incurred_at,created_by,created_at"
                + " from production_trial_cost_line where tenant_id = ?::uuid and cost_id = ?::uuid"
                + " order by incurred_at, id",
            currentTenant(),
            costId)) {
      lines.add(costLineRow(row));
    }
    return new ProductionTrialCostView(
        costId,
        orderId,
        currency,
        head.get("total_amount", BigDecimal.class),
        massTarget,
        massTarget != null,
        massTarget == null
            ? "这张试产单**没有填量产目标成本** ⇒ 试产成本与量产目标成本不可比。"
                + "本服务**不做固定汇率折算**（5.2.2.1 未交付），也不拿 0 顶替目标成本。"
            : "试产成本与量产目标成本**同一币种**（" + currency + "）才可比；"
                + "差额 = 试产成本 − 量产目标成本，偏差率 = 差额 ÷ 量产目标成本（比例）。",
        lines);
  }

  /** 追加一条成本明细行。 */
  @Transactional
  public ProductionTrialCostView addCostLine(
      UUID orderId, ProductionTrialCostLineInput input, UUID actorId) {
    UUID tenantId = currentTenant();
    requireWorkingStage(orderId);
    requirePositiveAmount(input.amount());
    Record head =
        sql.fetchOne(
            "select c.id,c.currency,o.currency as order_currency from production_trial_cost c"
                + " join production_trial_order o on o.tenant_id = c.tenant_id"
                + "   and o.id = c.trial_order_id"
                + " where c.tenant_id = ?::uuid and c.trial_order_id = ?::uuid",
            tenantId,
            orderId);
    if (head == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "这张试产单还没有成本头，无法追加成本明细行");
    }
    String currency = head.get("currency", String.class);
    // 🔴 一张试产单一个币种：成本头的币种必须与试产单一致，明细行再与成本头一致
    //    （DB 触发器 {@code guard_production_trial_cost_line_currency()} 还会再挡一道）。
    requireSameCurrency(head.get("order_currency", String.class), currency);
    sql.execute(
        "insert into production_trial_cost_line(id,tenant_id,cost_id,trial_order_id,cost_type,amount,"
            + " currency,description,incurred_at,created_by,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::uuid,?::varchar,?::numeric,?::varchar,?::varchar,"
            + " ?::timestamptz,?::uuid,now())",
        UUID.randomUUID(),
        tenantId,
        head.get("id", UUID.class),
        orderId,
        input.costType().name(),
        input.amount(),
        currency,
        input.description().trim(),
        input.incurredAt(),
        actorId);
    return costView(orderId);
  }

  /** 删一条成本明细行（合计金额由数据库触发器自动重算）。 */
  @Transactional
  public ProductionTrialCostView deleteCostLine(UUID orderId, UUID lineId, UUID actorId) {
    UUID tenantId = currentTenant();
    requireWorkingStage(orderId);
    var row =
        sql.fetchOne(
            "select id from production_trial_cost_line where tenant_id = ?::uuid"
                + " and trial_order_id = ?::uuid and id = ?::uuid",
            tenantId,
            orderId,
            lineId);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "成本明细行不存在或无权查看：" + lineId);
    }
    sql.execute(
        "delete from production_trial_cost_line where tenant_id = ?::uuid and id = ?::uuid",
        tenantId,
        lineId);
    return costView(orderId);
  }

  /**
   * 「试产成本 vs 量产目标成本」的对比（**同一张单、同一个币种**）。
   *
   * <p>🔴 量产目标成本取不到 ⇒ {@code comparable=false} 且目标成本为 {@code null}
   * （<b>不是 0</b>），**不做固定汇率折算**。
   */
  public ProductionTrialCostComparisonRow costComparison(UUID orderId) {
    Record head =
        sql.fetchOne(
            "select currency,total_amount,mass_target_cost from production_trial_cost"
                + " where tenant_id = ?::uuid and trial_order_id = ?::uuid",
            currentTenant(),
            orderId);
    if (head == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "这张试产单还没有成本记录，无从比较试产成本与量产目标成本");
    }
    String currency = head.get("currency", String.class);
    BigDecimal trialCost = head.get("total_amount", BigDecimal.class);
    BigDecimal massTarget = head.get("mass_target_cost", BigDecimal.class);
    if (massTarget == null) {
      return new ProductionTrialCostComparisonRow(
          currency,
          trialCost,
          null,
          null,
          null,
          false,
          "这张试产单**没有填量产目标成本** ⇒ 不可比。本服务**不做固定汇率折算**，也不拿 0 顶替目标成本。");
    }
    return new ProductionTrialCostComparisonRow(
        currency,
        trialCost,
        massTarget,
        costVarianceAmount(trialCost, massTarget),
        costVarianceRate(trialCost, massTarget),
        true,
        "同一币种（" + currency + "）内可比：差额 = 试产成本 − 量产目标成本，"
            + "偏差率 = 差额 ÷ 量产目标成本（比例，不是百分数）。");
  }

  // ================================================================ 转量产建议

  /** 转量产建议。没记录过时返回 null。 */
  public ProductionTrialMassProductionRecommendationRow recommendationView(UUID orderId) {
    Record row =
        sql.fetchOne(
            "select id,trial_order_id,decision,rationale,condition_note,min_yield_threshold,"
                + " max_cost_variance_rate,observed_yield_rate,observed_cost_variance_rate,"
                + " observed_trial_cost,observed_mass_target_cost,observed_currency,created_by,"
                + " created_at from production_trial_mass_production_recommendation"
                + " where tenant_id = ?::uuid and trial_order_id = ?::uuid",
            currentTenant(),
            orderId);
    return row == null ? null : recommendationRow(row);
  }

  /**
   * 记录一条转量产建议（**建议事实**，不是 SKU 建档）。
   *
   * <p>🔴 「建议转量产」需要<b>举证</b>：良率达标 + 成本门槛达标；
   * 「暂不建议转量产」不需要举证（关键质检项不合格也足以否掉量产）。
   */
  @Transactional
  public ProductionTrialMassProductionRecommendationRow recordRecommendation(
      UUID orderId, ProductionTrialMassProductionRecommendationInput input, UUID actorId) {
    UUID tenantId = currentTenant();
    ProductionTrialStatus status = requireOrderStatus(orderId);
    requireRecommendationStage(status);

    var existing =
        sql.fetchOne(
            "select id from production_trial_mass_production_recommendation"
                + " where tenant_id = ?::uuid and trial_order_id = ?::uuid",
            tenantId,
            orderId);
    if (existing != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "这张试产单已经有一条转量产建议了 —— 建议是不可改写的留痕，"
              + "要改结论请作废本单另开一次试产，而不是把旧建议抹掉");
    }

    // 良率来自已记录的试产结果（**不是报上来的**）。
    Record result =
        sql.fetchOne(
            "select yield_rate from production_trial_result where tenant_id = ?::uuid"
                + " and trial_order_id = ?::uuid",
            tenantId,
            orderId);
    if (result == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "这张试产单还没有试产结果（良率与产出 / 合格数量）—— 建议必须建立在实测结果之上");
    }
    BigDecimal observedYield = result.get("yield_rate", BigDecimal.class);

    // 成本对比：同一币种内比；目标成本取不到 ⇒ 偏差率取不到（不折算、不顶 0）。
    Record cost =
        sql.fetchOne(
            "select currency,total_amount,mass_target_cost from production_trial_cost"
                + " where tenant_id = ?::uuid and trial_order_id = ?::uuid",
            tenantId,
            orderId);
    String currency = cost == null ? currencyOf(orderId) : cost.get("currency", String.class);
    BigDecimal trialCost = cost == null ? null : cost.get("total_amount", BigDecimal.class);
    BigDecimal massTarget = cost == null ? null : cost.get("mass_target_cost", BigDecimal.class);
    BigDecimal observedVariance = costVarianceRate(trialCost, massTarget);

    BigDecimal minYield = requireRate(input.minYieldThreshold(), "良率门槛");
    BigDecimal maxVariance =
        input.maxCostVarianceRate() == null
            ? null
            : requireRate(input.maxCostVarianceRate(), "成本偏差上限");
    requireRecommendationEvidence(
        input.decision(), observedYield, minYield, observedVariance, maxVariance);

    sql.execute(
        "insert into production_trial_mass_production_recommendation(id,tenant_id,trial_order_id,"
            + " decision,rationale,condition_note,min_yield_threshold,max_cost_variance_rate,"
            + " observed_yield_rate,observed_cost_variance_rate,observed_trial_cost,"
            + " observed_mass_target_cost,observed_currency,created_by,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::numeric,"
            + " ?::numeric,?::numeric,?::numeric,?::numeric,?::numeric,?::varchar,?::uuid,now())",
        UUID.randomUUID(),
        tenantId,
        orderId,
        input.decision().name(),
        input.rationale().trim(),
        input.conditionNote().trim(),
        minYield,
        maxVariance,
        observedYield,
        observedVariance,
        trialCost,
        massTarget,
        currency,
        actorId);
    return recommendationView(orderId);
  }

  // ================================================================ BOM 版本候选

  /** catalog 的 BOM 版本候选（跨服务只读）。 */
  public List<ProductionTrialBomVersionCandidateRow> bomVersionCandidates(
      String keyword, Integer page, Integer size, String bearer) {
    int pageNo = page == null || page < 0 ? 0 : page;
    int pageSize = pageSize(size);
    ProductionTrialBomPort.VersionPage result =
        bom.candidates(keyword, null, pageNo, pageSize, bearer);
    List<ProductionTrialBomVersionCandidateRow> rows = new ArrayList<>();
    for (ProductionTrialBomPort.VersionRow row : result.items()) {
      rows.add(
          new ProductionTrialBomVersionCandidateRow(
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

  // ================================================================ 内部工具

  /** 试产单的公共 select 片段。 */
  private static final String ORDER_SELECT =
      "select o.id,o.trial_no,o.bom_version_id,o.bom_version_no,o.bom_code,o.output_sku_code,"
          + " o.output_sku_name,o.development_sample_order_id,o.development_sample_order_no,"
          + " o.target_quantity,o.production_line_code,o.production_line_name,o.factory_code,"
          + " o.factory_name,o.quality_standard_code,o.quality_standard_note,o.status,o.currency,"
          + " o.requester_id,o.requester_name,o.requested_at,o.planned_start_at,o.planned_end_at,"
          + " o.purpose_note,o.note,o.created_by,o.created_at,o.updated_at,"
          + " (select count(*) from production_trial_component_requirement r"
          + "   where r.tenant_id = o.tenant_id and r.trial_order_id = o.id) as requirement_count,"
          + " (select count(*) from production_trial_component_requirement r"
          + "   where r.tenant_id = o.tenant_id and r.trial_order_id = o.id"
          + "   and r.requirement_status = 'DRAFT') as pending_requirement_count,"
          + " (select count(*) from production_trial_inspection_item i"
          + "   where i.tenant_id = o.tenant_id and i.trial_order_id = o.id) as inspection_item_count,"
          + " (select count(*) from production_trial_inspection_item i"
          + "   where i.tenant_id = o.tenant_id and i.trial_order_id = o.id"
          + "   and i.judgment = 'PENDING') as pending_inspection_item_count,"
          + " (select r.yield_rate from production_trial_result r"
          + "   where r.tenant_id = o.tenant_id and r.trial_order_id = o.id) as yield_rate,"
          + " (select c.total_amount from production_trial_cost c"
          + "   where c.tenant_id = o.tenant_id and c.trial_order_id = o.id) as trial_cost_total,"
          + " (select c.mass_target_cost from production_trial_cost c"
          + "   where c.tenant_id = o.tenant_id and c.trial_order_id = o.id) as mass_target_cost,"
          + " (select rec.decision from production_trial_mass_production_recommendation rec"
          + "   where rec.tenant_id = o.tenant_id and rec.trial_order_id = o.id) as recommendation_decision"
          + " from production_trial_order o";

  /** 状态迁移事件清单（详情页时间线）。 */
  public List<ProductionTrialTransitionRow> listTransitions(UUID orderId) {
    List<ProductionTrialTransitionRow> rows = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id,trial_order_id,from_status,to_status,reason,actor_id,actor_name,occurred_at,"
                + " applied_at from production_trial_order_transition"
                + " where tenant_id = ?::uuid and trial_order_id = ?::uuid"
                + " order by occurred_at desc, id desc",
            currentTenant(),
            orderId)) {
      rows.add(
          new ProductionTrialTransitionRow(
              row.get("id", UUID.class),
              row.get("trial_order_id", UUID.class),
              row.get("from_status", String.class),
              ProductionTrialStatus.valueOf(row.get("from_status", String.class)).label(),
              row.get("to_status", String.class),
              ProductionTrialStatus.valueOf(row.get("to_status", String.class)).label(),
              row.get("reason", String.class),
              row.get("actor_id", UUID.class),
              row.get("actor_name", String.class),
              row.get("occurred_at", OffsetDateTime.class),
              row.get("applied_at", OffsetDateTime.class)));
    }
    return rows;
  }

  /** 上游 6.6.1.2 打样单：**只读引用 + 快照**（同一个 trade 库 ⇒ 只 SELECT）。 */
  private void requireSampleOrderSnapshot(UUID sampleOrderId) {
    var row =
        sql.fetchOne(
            "select order_no from development_sample_order where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            sampleOrderId);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "打样单不存在或无权查看：" + sampleOrderId);
    }
  }

  /** 这张单当前是不是可写状态（终态不可写）。 */
  private void requireWorkingStage(UUID orderId) {
    ProductionTrialStatus status = requireOrderStatus(orderId);
    if (isTerminalStatus(status)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "试产单已是「" + status.label() + "」（终态，不可写）：终态不提供恢复");
    }
  }

  /**
   * 开产前：组件领用需求必须<b>全部</b>提交。
   *
   * <p>🔴 与 V264 的 {@code guard_production_trial_order_status()} 里那条「DRAFT 不许开产」
   * <b>同构</b> —— 服务层先挡一道给出可读原因，DB 层再挡一道挡住绕过服务的写入。
   */
  private void requireAllRequirementsSubmitted(UUID orderId) {
    var row =
        sql.fetchOne(
            "select count(*) as c from production_trial_component_requirement"
                + " where tenant_id = ?::uuid and trial_order_id = ?::uuid"
                + " and requirement_status = 'DRAFT'",
            currentTenant(),
            orderId);
    long draft = row == null ? 0L : row.get("c", Long.class);
    if (draft > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "还有 " + draft + " 条组件领用需求未提交，不能开产："
              + "试产消耗真实组件库存，领用需求必须先提交（提交**不等于**扣库存，"
              + "真实领用由 8.4.1 组装单承接）");
    }
  }

  /** 完成前：必须有试产结果（良率与产出 / 合格数量）。 */
  private void requireResultRecorded(UUID orderId) {
    var row =
        sql.fetchOne(
            "select count(*) as c from production_trial_result"
                + " where tenant_id = ?::uuid and trial_order_id = ?::uuid",
            currentTenant(),
            orderId);
    long results = row == null ? 0L : row.get("c", Long.class);
    if (results == 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "试产单还没有试产结果（良率与产出 / 合格数量），不能标记为已完成");
    }
  }

  /** 完成前：已登记的质检标准项必须<b>全部</b>判定完毕。 */
  private void requireAllInspectionItemsJudged(UUID orderId) {
    var row =
        sql.fetchOne(
            "select count(*) as c from production_trial_inspection_item"
                + " where tenant_id = ?::uuid and trial_order_id = ?::uuid"
                + " and judgment = 'PENDING'",
            currentTenant(),
            orderId);
    long pending = row == null ? 0L : row.get("c", Long.class);
    if (pending > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "还有 " + pending + " 项质检标准未判定，不能标记为已完成");
    }
  }

  private ProductionTrialStatus requireOrderStatus(UUID orderId) {
    Record row =
        sql.fetchOne(
            "select status from production_trial_order where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            orderId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "试产单不存在或无权查看：" + orderId);
    }
    return ProductionTrialStatus.valueOf(row.get("status", String.class));
  }

  private String currencyOf(UUID orderId) {
    Record row =
        sql.fetchOne(
            "select currency from production_trial_order where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            orderId);
    return row == null ? "" : row.get("currency", String.class);
  }

  private void requireOrderExists(UUID orderId) {
    requireOrderStatus(orderId);
  }

  private Record requireRequirement(UUID orderId, UUID requirementId) {
    Record row =
        sql.fetchOne(
            "select id,requirement_status from production_trial_component_requirement"
                + " where tenant_id = ?::uuid and trial_order_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            orderId,
            requirementId);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "组件领用需求不存在或无权查看：" + requirementId);
    }
    return row;
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
    all.addAll(List.of(extra));
    return all;
  }

  private static String blankToEmpty(String value) {
    return value == null ? "" : value.trim();
  }

  private static ProductionTrialOrderRow orderRow(Record row) {
    String status = row.get("status", String.class);
    String decision = row.get("recommendation_decision", String.class);
    BigDecimal yieldRate = row.get("yield_rate", BigDecimal.class);
    return new ProductionTrialOrderRow(
        row.get("id", UUID.class),
        row.get("trial_no", String.class),
        row.get("bom_version_id", UUID.class),
        row.get("bom_version_no", String.class),
        row.get("bom_code", String.class),
        row.get("output_sku_code", String.class),
        row.get("output_sku_name", String.class),
        row.get("development_sample_order_id", UUID.class),
        row.get("development_sample_order_no", String.class),
        row.get("target_quantity", BigDecimal.class),
        row.get("production_line_code", String.class),
        row.get("production_line_name", String.class),
        row.get("factory_code", String.class),
        row.get("factory_name", String.class),
        row.get("quality_standard_code", String.class),
        row.get("quality_standard_note", String.class),
        status,
        ProductionTrialStatus.valueOf(status).label(),
        row.get("currency", String.class),
        row.get("requester_id", UUID.class),
        row.get("requester_name", String.class),
        row.get("requested_at", OffsetDateTime.class),
        row.get("planned_start_at", OffsetDateTime.class),
        row.get("planned_end_at", OffsetDateTime.class),
        row.get("requirement_count", Integer.class),
        row.get("pending_requirement_count", Integer.class),
        row.get("inspection_item_count", Integer.class),
        row.get("pending_inspection_item_count", Integer.class),
        yieldRate != null,
        yieldRate,
        row.get("trial_cost_total", BigDecimal.class),
        row.get("mass_target_cost", BigDecimal.class),
        decision != null,
        decision,
        decision == null
            ? null
            : ProductionTrialMassProductionDecision.valueOf(decision).label(),
        row.get("purpose_note", String.class),
        row.get("note", String.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private static ProductionTrialComponentRequirementRow requirementRow(Record row) {
    String status = row.get("requirement_status", String.class);
    return new ProductionTrialComponentRequirementRow(
        row.get("id", UUID.class),
        row.get("trial_order_id", UUID.class),
        row.get("line_no", Integer.class),
        row.get("sku_id", UUID.class),
        row.get("sku_code", String.class),
        row.get("sku_name", String.class),
        row.get("spec_snapshot", String.class),
        row.get("unit_quantity", BigDecimal.class),
        row.get("scrap_rate", BigDecimal.class),
        row.get("required_quantity", BigDecimal.class),
        row.get("unit", String.class),
        status,
        ProductionTrialRequirementStatus.valueOf(status).label(),
        row.get("assembly_order_ref_id", UUID.class),
        row.get("assembly_order_no", String.class),
        false,
        INVENTORY_OWNER_MODULE,
        row.get("note", String.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private static ProductionTrialInspectionItemRow inspectionItemRow(Record row) {
    String judgment = row.get("judgment", String.class);
    return new ProductionTrialInspectionItemRow(
        row.get("id", UUID.class),
        row.get("trial_order_id", UUID.class),
        row.get("item_code", String.class),
        row.get("item_name", String.class),
        row.get("standard_value", String.class),
        row.get("inspection_method", String.class),
        row.get("unit", String.class),
        row.get("critical_item", Boolean.class),
        row.get("sort_order", Integer.class),
        judgment,
        ProductionTrialJudgment.valueOf(judgment).label(),
        row.get("measured_value", String.class),
        row.get("judged_at", OffsetDateTime.class),
        row.get("judged_by", UUID.class),
        row.get("note", String.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private static ProductionTrialResultRow resultRow(Record row) {
    return new ProductionTrialResultRow(
        row.get("id", UUID.class),
        row.get("trial_order_id", UUID.class),
        row.get("output_quantity", BigDecimal.class),
        row.get("qualified_quantity", BigDecimal.class),
        row.get("defective_quantity", BigDecimal.class),
        row.get("yield_rate", BigDecimal.class),
        row.get("conclusion", String.class),
        row.get("recorded_at", OffsetDateTime.class),
        row.get("recorded_by", UUID.class),
        row.get("note", String.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private static ProductionTrialCostLineRow costLineRow(Record row) {
    String costType = row.get("cost_type", String.class);
    return new ProductionTrialCostLineRow(
        row.get("id", UUID.class),
        row.get("cost_id", UUID.class),
        costType,
        ProductionTrialCostType.valueOf(costType).label(),
        row.get("amount", BigDecimal.class),
        row.get("currency", String.class),
        row.get("description", String.class),
        row.get("incurred_at", OffsetDateTime.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private static ProductionTrialMassProductionRecommendationRow recommendationRow(Record row) {
    String decision = row.get("decision", String.class);
    return new ProductionTrialMassProductionRecommendationRow(
        row.get("id", UUID.class),
        row.get("trial_order_id", UUID.class),
        decision,
        ProductionTrialMassProductionDecision.valueOf(decision).label(),
        row.get("rationale", String.class),
        row.get("condition_note", String.class),
        row.get("min_yield_threshold", BigDecimal.class),
        row.get("max_cost_variance_rate", BigDecimal.class),
        row.get("observed_yield_rate", BigDecimal.class),
        row.get("observed_cost_variance_rate", BigDecimal.class),
        row.get("observed_trial_cost", BigDecimal.class),
        row.get("observed_mass_target_cost", BigDecimal.class),
        row.get("observed_currency", String.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class));
  }
}
