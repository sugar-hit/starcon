package com.starcon.trade;

import static com.starcon.trade.DevelopmentProjectModels.*;
import static com.starcon.trade.DevelopmentProjectRules.*;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 6.6.1.1 开发立项。
 *
 * <p>蓝图原话：「管理新品开发立项：立项编号、品类、目标市场与平台、目标售价与毛利、开发负责人、
 * 打样与量产时间计划、状态（评估/立项/开发中/打样/试产/量产/终止）。立项通过后可派生打样单与试产单，
 * 并作为 SKU 建档的前置。」输入「立项申请与评审结论；目标定价与成本估算（5.3.1.1）」。
 * 输出「开发单（立项记录 + 里程碑计划）；可转 SKU 建档的候选参数」。
 *
 * <p><b>本模块只做一件事：把「要开发一个新品」变成一份有凭据、状态受控、可交接的开发单。</b>
 *
 * <p><b>十条实现纪律</b>：
 *
 * <ol>
 *   <li><b>状态机在服务层 + 数据库层两处钉死</b>：Java 侧
 *       {@link DevelopmentProjectRules#allowedTargets}，DB 侧 V257 迁移的
 *       {@code guard_development_project_status()} 守卫触发器。
 *       客户端**传什么状态不算数** —— 只认状态机上存在的边。</li>
 *   <li><b>「终止」是终态</b>：没有出边，不提供「恢复」。</li>
 *   <li><b>跳过打样 / 试产必须留豁免记录</b>：豁免码 + 豁免说明成对；DB 守卫也会挡。</li>
 *   <li><b>每次状态迁移都落一条不可改写的事件</b>：谁、何时、从哪到哪、为什么。
 *       事件先落库、状态后改 —— DB 守卫要求「没有事件的状态变更」直接被拒。</li>
 *   <li><b>目标毛利率一律服务端算</b>：{@code (售价 − 成本) ÷ 售价}，单次 HALF_UP 到 6 位；
 *       任何输入模型里都**没有**毛利率字段。</li>
 *   <li><b>成本取不到就标 unavailable</b>：{@code cost_available=false} + {@code cost_owner_module}，
 *       毛利率为 null。**绝不**拿 0 顶替（成本 0 的毛利率是 100%）。</li>
 *   <li><b>不建 14 广告的市场规模数据</b>：reporting 服务在本仓**不存在** ⇒ 系统侧市场数据
 *       {@code available=false} + {@code ownerModule='14 广告'}；租户手工录入的观察值
 *       <b>必须**标明来源与采集时间。</b></li>
 *   <li><b>只产出交接单，不代下游建单</b>：6.6.1.2 打样 / 6.6.2.1 试产都未交付，
 *       3.1.1.1 建档归 catalog —— 本模块产候选参数 + 交接单，
 *       <b>不建 SKU、不写 catalog 的表</b>。</li>
 *   <li><b>时间一律 UTC 口径</b>：{@code timestamptz} 列用 {@code OffsetDateTime} 绑定，
 *       values 位置写 {@code ?::timestamptz}；计划时间**没有「今天」默认值**。</li>
 *   <li><b>本模块的表全部 FORCE RLS</b>：租户由 {@code backend/libraries/tenancy} 在事务内设置，
 *       客户端的 {@code X-Tenant-Id} 头进不来也改不动它。</li>
 * </ol>
 */
@Service
public class DevelopmentProjectService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 成本取不到时点名的归管模块（蓝图关系句里的 5 定价）。 */
  static final String COST_OWNER_MODULE = "5.3.1.1 利润测算器";

  /** 市场数据取不到时点名的归管模块（蓝图关系句里的 14 广告）。 */
  static final String MARKET_DATA_OWNER_MODULE = "14 广告";

  private final DSLContext sql;
  private final DevelopmentCostEstimatePort costEstimate;
  private final ObjectMapper json;

  public DevelopmentProjectService(
      DSLContext sql, DevelopmentCostEstimatePort costEstimate, ObjectMapper json) {
    this.sql = sql;
    this.costEstimate = costEstimate;
    this.json = json;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>输入分属两个地方：目标成本在 5.3.1.1（catalog，**已交付**）、市场数据在 14 广告
   * （**reporting 服务在本仓不存在**）。下游三个模块本模块**只产出交接单**。
   */
  public DevelopmentProjectCapabilities capabilities() {
    return new DevelopmentProjectCapabilities(
        true,
        "5.3.1.1 利润测算器（catalog 服务，集成头已交付）",
        "目标成本的唯一系统来源是 catalog 的 5.3.1.1 利润测算器"
            + "（POST /catalog/profit-calculator/calculate），本服务**只经授权只读端点**读它，"
            + "不直连对方库、不建跨库视图、不建跨库外键。取到的成本在本库落一份快照，"
            + "快照里记来源（CATALOG_ESTIMATE）与口径。取不到时如实标 available=false + "
            + "ownerModule，**绝不**拿 0 顶替（成本 0 的毛利率是 100%，是假数据）。",
        false,
        MARKET_DATA_OWNER_MODULE,
        "🔴 卷 14 的主责服务是 channel + reporting，而 **reporting 服务在本仓不存在**"
            + "（backend/services/ 下只有 catalog / channel / crm / identity / trade / warehouse）"
            + "⇒ 类目规模 / 竞品价位 / 搜索热度的**系统侧**市场数据取不到。"
            + "本模块**不建任何市场规模表**（那等于编一个不存在的上游）。"
            + "租户**手工录入**的市场数据允许存在，但**必须标明来源与采集时间**。",
        false,
        "6.6.1.2 打样与样品管理**未交付** ⇒ 本模块只产出「打样单」**交接单**（含候选参数），"
            + "不代它建打样单、不代它记样品成本。",
        false,
        "6.6.2.1 试产单**未交付** ⇒ 本模块只产出「试产单」**交接单**，不代它建试产单。",
        true,
        "3.1.1.1 SKU 建档在 **catalog 服务**（已交付）⇒ 本模块**只产出候选参数**"
            + "（品类 / 目标市场与平台 / 目标售价区间 / 规格候选 / 上市时间目标），"
            + "**不建 SKU、不写 catalog 的表**。正式的建档动作属 6.6.2.2 转量产与建档。",
        "七个状态单向推进：评估 → 立项 → 开发中 → 打样 → 试产 → 量产；"
            + "任意非终态可终止。**终止是终态，不提供「恢复」**。"
            + "量产之前**不许跳过打样 / 试产** —— 要跳必须给出显式的豁免记录（豁免码 + 豁免说明）。"
            + "合法迁移在**服务层与数据库层两处**都钉死，客户端传什么状态不算数。",
        "毛利 = (目标售价 − 目标成本) ÷ 目标售价",
        "单次 HALF_UP 舍入到 " + MARGIN_RATE_SCALE + " 位小数（小数口径，0.253456 = 25.3456%），"
            + "与 5.3.1.1 利润测算器的毛利率口径**一致** —— 同一个立项在定价页与立项页不会显示两个毛利。"
            + "百分比的两位小数只是显示格式，是同一个 canonical 值的格式化结果，不是第二次计算。",
        "本模块的职责边界：立一个开发单 → 维护目标市场与平台 / 规格候选 / 里程碑计划 →"
            + "受控推进状态（每次迁移留事件）→ 产出下游交接单（打样 / 试产 / SKU 建档）。"
            + "**不**建 SKU、**不**建打样单、**不**建试产单、**不**写 catalog / channel / warehouse 的表。");
  }

  /** 建单参照项（下拉）。 */
  public DevelopmentProjectOptions options() {
    List<DevelopmentStatusOption> statuses = new ArrayList<>();
    for (DevelopmentStatus status : DevelopmentStatus.values()) {
      statuses.add(
          new DevelopmentStatusOption(
              status.name(),
              status.label(),
              isTerminal(status),
              allowedTargets(status).stream().map(Enum::name).toList()));
    }
    List<MilestoneOption> milestones = new ArrayList<>();
    for (DevelopmentMilestoneType type : DevelopmentMilestoneType.values()) {
      milestones.add(new MilestoneOption(type.name(), type.label()));
    }
    List<CostSourceOption> sources = new ArrayList<>();
    for (DevelopmentCostSource source : DevelopmentCostSource.values()) {
      sources.add(new CostSourceOption(source.name(), source.label()));
    }
    List<HandoffOption> handoffs = new ArrayList<>();
    for (DevelopmentHandoffType type : DevelopmentHandoffType.values()) {
      handoffs.add(new HandoffOption(type.name(), type.label(), type.targetModule()));
    }
    return new DevelopmentProjectOptions(statuses, milestones, sources, handoffs);
  }

  // ================================================================ 列表

  /** 立项清单（真分页 + 真筛选）。 */
  public DevelopmentProjectPage list(
      String status, String category, UUID ownerId, String keyword, Integer page, Integer size) {
    int pageNo = page == null || page < 0 ? 0 : page;
    int pageSize = pageSize(size);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("p.tenant_id = ?::uuid");
    binds.add(currentTenant());
    if (status != null && !status.isBlank()) {
      where.add("p.status = ?::varchar");
      binds.add(status.trim());
    }
    if (category != null && !category.isBlank()) {
      where.add("p.category ilike ?::varchar");
      binds.add("%" + category.trim() + "%");
    }
    if (ownerId != null) {
      where.add("p.owner_id = ?::uuid");
      binds.add(ownerId);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.add("(p.project_no ilike ?::varchar or p.name ilike ?::varchar or p.category ilike ?::varchar)");
      String like = "%" + keyword.trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    String clause = String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from development_project p where " + clause, binds.toArray())
            .get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            PROJECT_SELECT + " where " + clause + " order by p.created_at desc, p.id desc"
                + " limit ? offset ?",
            append(binds, pageSize, (long) pageNo * pageSize).toArray());
    List<DevelopmentProjectRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(projectRow(row));
    }
    return new DevelopmentProjectPage(items, total, pageNo, pageSize);
  }

  /** 按状态分组的概览。 */
  public DevelopmentProjectSummary summary() {
    UUID tenantId = currentTenant();
    long total =
        sql.fetchOne(
                "select count(*) from development_project where tenant_id = ?::uuid", tenantId)
            .get(0, Long.class);
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select status, count(*) as c from development_project where tenant_id = ?::uuid"
                + " group by status",
            tenantId)) {
      counts.put(row.get("status", String.class), row.get("c", Long.class));
    }
    List<DevelopmentStatusCount> statuses = new ArrayList<>();
    for (DevelopmentStatus status : DevelopmentStatus.values()) {
      statuses.add(
          new DevelopmentStatusCount(status.name(), status.label(), counts.getOrDefault(status.name(), 0L)));
    }
    List<Record> recent =
        sql.fetch(
            PROJECT_SELECT + " where p.tenant_id = ?::uuid order by p.created_at desc, p.id desc"
                + " limit 10",
            tenantId);
    List<DevelopmentProjectRow> rows = new ArrayList<>();
    for (Record row : recent) {
      rows.add(projectRow(row));
    }
    return new DevelopmentProjectSummary(total, statuses, rows);
  }

  // ================================================================ 详情

  /** 立项详情：立项记录 + 里程碑 + 目标市场 + 规格候选 + 市场观察 + 迁移历史 + 交接单。 */
  public DevelopmentProjectDetail detail(UUID id) {
    DevelopmentProjectRow project = projectRow(requireProject(id));
    return new DevelopmentProjectDetail(
        project,
        listMilestones(id),
        listMarkets(id),
        listSpecCandidates(id),
        listMarketObservations(id),
        listTransitions(id),
        listHandoffs(id),
        allowedTargets(DevelopmentStatus.valueOf(project.status())).stream()
            .map(Enum::name)
            .toList());
  }

  // ================================================================ 写

  /** 立项（建单）。 */
  @Transactional
  public DevelopmentProjectDetail create(DevelopmentProjectCreateInput input, UUID actorId) {
    UUID tenantId = currentTenant();
    String projectNo = input.projectNo().trim();
    var exists =
        sql.fetchOne(
            "select id from development_project where tenant_id = ?::uuid and project_no = ?::varchar",
            tenantId,
            projectNo);
    if (exists != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "立项编号已存在：" + projectNo + "（编号在租户内唯一）");
    }
    String currency = normalizeCurrency(input.currency());
    requirePositivePrice(input.targetPrice());
    requireNonNegativeCost(input.targetCost());

    // 🔴 成本来源：给了手工估算 ⇒ MANUAL_ESTIMATE；没给 ⇒ UNAVAILABLE + 点名归谁管。
    boolean manualCost = input.targetCost() != null;
    BigDecimal cost = manualCost ? input.targetCost() : null;
    BigDecimal margin = manualCost ? targetMarginRate(input.targetPrice(), cost) : null;
    String marginBasis =
        manualCost
            ? marginBasis(input.targetPrice(), cost, currency)
            : unavailableMarginBasis(
                COST_OWNER_MODULE, "本立项没有目标成本，也没有挂参考 SKU 可供 5.3.1.1 估算");

    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into development_project(id,tenant_id,project_no,name,category,status,owner_id,"
            + " owner_name,target_price,currency,target_cost,cost_currency,cost_available,cost_source,"
            + " cost_owner_module,cost_basis,cost_estimated_at,target_margin_rate,margin_basis,"
            + " decision_note,reference_sku_id,reference_sku_code,launch_target_at,note,created_by,"
            + " created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,'EVALUATION',?::uuid,"
            + " ?::varchar,?::numeric,?::varchar,?::numeric,?::varchar,?::boolean,?::varchar,"
            + " ?::varchar,?::varchar,?::timestamptz,?::numeric,?::varchar,?::varchar,?::uuid,"
            + " ?::varchar,?::timestamptz,?::varchar,?::uuid,now(),now())",
        id,
        tenantId,
        projectNo,
        input.name().trim(),
        input.category().trim(),
        input.ownerId(),
        blankToEmpty(input.ownerName()),
        input.targetPrice(),
        currency,
        cost,
        manualCost ? currency : null,
        manualCost,
        manualCost ? DevelopmentCostSource.MANUAL_ESTIMATE.name() : DevelopmentCostSource.UNAVAILABLE.name(),
        manualCost ? "" : COST_OWNER_MODULE,
        manualCost ? blankToEmpty(input.costBasis()) : "",
        manualCost
            ? (input.costEstimatedAt() == null ? OffsetDateTime.now() : input.costEstimatedAt())
            : null,
        margin,
        marginBasis,
        blankToEmpty(input.decisionNote()),
        input.referenceSkuId(),
        blankToEmpty(input.referenceSkuCode()),
        input.launchTargetAt(),
        blankToEmpty(input.note()),
        actorId);
    return detail(id);
  }

  /**
   * 改立项（<b>整体替换</b>语义：请求体就是期望的最终状态，null 表示「清空」）。
   *
   * <p>🔴 目标售价或目标成本变了 ⇒ <b>目标毛利率由服务端重算</b>，调用方**不能**自己报毛利率。
   * 🔴 目标成本被清空（null）⇒ 成本变为「取不到」+ 点名归谁管，毛利率随之变 null
   * （**绝不**用 0 顶替）。
   */
  @Transactional
  public DevelopmentProjectDetail update(UUID id, DevelopmentProjectUpdateInput input) {
    requireProject(id);
    String currency = normalizeCurrency(input.currency());
    requirePositivePrice(input.targetPrice());
    requireNonNegativeCost(input.targetCost());

    boolean manualCost = input.targetCost() != null;
    BigDecimal cost = manualCost ? input.targetCost() : null;
    BigDecimal margin = manualCost ? targetMarginRate(input.targetPrice(), cost) : null;
    String marginBasis =
        manualCost
            ? marginBasis(input.targetPrice(), cost, currency)
            : unavailableMarginBasis(
                COST_OWNER_MODULE, "目标成本已被清空，且没有挂参考 SKU 可供 5.3.1.1 估算");
    sql.execute(
        "update development_project set name = ?::varchar, category = ?::varchar,"
            + " owner_id = ?::uuid, owner_name = ?::varchar, target_price = ?::numeric,"
            + " currency = ?::varchar, target_cost = ?::numeric, cost_currency = ?::varchar,"
            + " cost_available = ?::boolean, cost_source = ?::varchar, cost_owner_module = ?::varchar,"
            + " cost_basis = ?::varchar, cost_estimated_at = ?::timestamptz,"
            + " target_margin_rate = ?::numeric, margin_basis = ?::varchar, decision_note = ?::varchar,"
            + " reference_sku_id = ?::uuid, reference_sku_code = ?::varchar,"
            + " launch_target_at = ?::timestamptz, note = ?::varchar, updated_at = now()"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        input.name().trim(),
        input.category().trim(),
        input.ownerId(),
        blankToEmpty(input.ownerName()),
        input.targetPrice(),
        currency,
        cost,
        manualCost ? currency : null,
        manualCost,
        manualCost ? DevelopmentCostSource.MANUAL_ESTIMATE.name() : DevelopmentCostSource.UNAVAILABLE.name(),
        manualCost ? "" : COST_OWNER_MODULE,
        manualCost ? blankToEmpty(input.costBasis()) : "",
        manualCost
            ? (input.costEstimatedAt() == null ? OffsetDateTime.now() : input.costEstimatedAt())
            : null,
        margin,
        marginBasis,
        blankToEmpty(input.decisionNote()),
        input.referenceSkuId(),
        blankToEmpty(input.referenceSkuCode()),
        input.launchTargetAt(),
        blankToEmpty(input.note()),
        currentTenant(),
        id);
    return detail(id);
  }

  /**
   * 推进状态。
   *
   * <p>🔴 **事件先落、状态后改**：迁移事件先 insert（不可改写），再 update 状态 ——
   * 数据库守卫 {@code guard_development_project_status()} 会检查「有没有对应事件」，
   * 没有就**直接拒绝这次状态变更**。这让「改了状态却没有事件」在数据库层就做不到。
   */
  @Transactional
  public DevelopmentProjectDetail transition(
      UUID id, DevelopmentTransitionInput input, UUID actorId) {
    DevelopmentStatus from = DevelopmentStatus.valueOf(requireProject(id).get("status", String.class));
    DevelopmentStatus to = input.toStatus();
    requireTransition(from, to, input.exemptionCode(), input.exemptionNote());

    UUID tenantId = currentTenant();
    OffsetDateTime occurredAt = input.occurredAt() == null ? OffsetDateTime.now() : input.occurredAt();
    sql.execute(
        "insert into development_project_transition(id,tenant_id,project_id,from_status,to_status,"
            + " reason,exemption_code,exemption_note,actor_id,occurred_at,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::varchar,?::varchar,"
            + " ?::uuid,?::timestamptz,now())",
        UUID.randomUUID(),
        tenantId,
        id,
        from.name(),
        to.name(),
        input.reason().trim(),
        blankToNull(input.exemptionCode()),
        blankToEmpty(input.exemptionNote()),
        actorId,
        occurredAt);
    try {
      sql.execute(
          "update development_project set status = ?::varchar, updated_at = now()"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          to.name(),
          tenantId,
          id);
    } catch (org.jooq.exception.DataAccessException e) {
      // 数据库守卫的拒绝（非法迁移 / 缺豁免 / 缺事件）翻译成可读的 400。
      String message = e.getMessage() == null ? "" : e.getMessage();
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "状态迁移被数据库守卫拒绝："
              + (message.contains("开发立项") ? message : "非法状态迁移")
              + "（服务层与数据库层都只认状态机上存在的边）");
    }
    return detail(id);
  }

  // ================================================================ 里程碑

  /** 里程碑计划清单。 */
  public List<DevelopmentMilestoneRow> listMilestones(UUID projectId) {
    List<DevelopmentMilestoneRow> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id,project_id,milestone_type,planned_at,actual_at,status,note"
                + " from development_project_milestone where tenant_id = ?::uuid and project_id = ?::uuid"
                + " order by planned_at,id",
            currentTenant(),
            projectId)) {
      items.add(milestoneRow(row));
    }
    return items;
  }

  /** 登记一条里程碑计划。 */
  @Transactional
  public List<DevelopmentMilestoneRow> createMilestone(
      UUID projectId, DevelopmentMilestoneInput input) {
    requireProject(projectId);
    var exists =
        sql.fetchOne(
            "select id from development_project_milestone where tenant_id = ?::uuid"
                + " and project_id = ?::uuid and milestone_type = ?::varchar",
            currentTenant(),
            projectId,
            input.milestoneType().name());
    if (exists != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "该立项已有「" + input.milestoneType().label() + "」里程碑（同一立项一种里程碑只有一行）");
    }
    DevelopmentMilestoneStatus status =
        input.status() == null ? DevelopmentMilestoneStatus.PLANNED : input.status();
    OffsetDateTime actualAt = input.actualAt();
    if (status == DevelopmentMilestoneStatus.PLANNED) {
      actualAt = null;
    }
    if (status == DevelopmentMilestoneStatus.REACHED && actualAt == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "里程碑标记为「已达成」时必须给出实际时刻 —— 不知道什么时候达成等于没达成");
    }
    sql.execute(
        "insert into development_project_milestone(id,tenant_id,project_id,milestone_type,planned_at,"
            + " actual_at,status,note,created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::timestamptz,?::timestamptz,?::varchar,"
            + " ?::varchar,now(),now())",
        UUID.randomUUID(),
        currentTenant(),
        projectId,
        input.milestoneType().name(),
        input.plannedAt(),
        actualAt,
        status.name(),
        blankToEmpty(input.note()));
    return listMilestones(projectId);
  }

  /** 改里程碑计划。 */
  @Transactional
  public List<DevelopmentMilestoneRow> updateMilestone(
      UUID projectId, UUID milestoneId, DevelopmentMilestoneInput input) {
    requireProject(projectId);
    requireMilestone(projectId, milestoneId);
    DevelopmentMilestoneStatus status =
        input.status() == null ? DevelopmentMilestoneStatus.PLANNED : input.status();
    OffsetDateTime actualAt = status == DevelopmentMilestoneStatus.PLANNED ? null : input.actualAt();
    if (status == DevelopmentMilestoneStatus.REACHED && actualAt == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "里程碑标记为「已达成」时必须给出实际时刻");
    }
    sql.execute(
        "update development_project_milestone set planned_at = ?::timestamptz, actual_at = ?::timestamptz,"
            + " status = ?::varchar, note = ?::varchar, updated_at = now()"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        input.plannedAt(),
        actualAt,
        status.name(),
        blankToEmpty(input.note()),
        currentTenant(),
        milestoneId);
    return listMilestones(projectId);
  }

  /** 删里程碑计划。 */
  @Transactional
  public List<DevelopmentMilestoneRow> deleteMilestone(UUID projectId, UUID milestoneId) {
    requireProject(projectId);
    requireMilestone(projectId, milestoneId);
    sql.execute(
        "delete from development_project_milestone where tenant_id = ?::uuid and id = ?::uuid",
        currentTenant(),
        milestoneId);
    return listMilestones(projectId);
  }

  // ================================================================ 迁移历史

  /** 状态迁移事件清单（不可改写的留痕，按发生时刻倒序）。 */
  public List<DevelopmentTransitionRow> listTransitions(UUID projectId) {
    List<DevelopmentTransitionRow> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id,project_id,from_status,to_status,reason,exemption_code,exemption_note,"
                + " actor_id,occurred_at,applied_at from development_project_transition"
                + " where tenant_id = ?::uuid and project_id = ?::uuid"
                + " order by occurred_at desc,id desc",
            currentTenant(),
            projectId)) {
      String from = row.get("from_status", String.class);
      String to = row.get("to_status", String.class);
      items.add(
          new DevelopmentTransitionRow(
              row.get("id", UUID.class),
              row.get("project_id", UUID.class),
              from,
              DevelopmentStatus.valueOf(from).label(),
              to,
              DevelopmentStatus.valueOf(to).label(),
              row.get("reason", String.class),
              row.get("exemption_code", String.class),
              row.get("exemption_note", String.class),
              row.get("actor_id", UUID.class),
              row.get("occurred_at", OffsetDateTime.class),
              row.get("applied_at", OffsetDateTime.class)));
    }
    return items;
  }

  // ================================================================ 目标市场

  /** 目标市场与平台清单。 */
  public List<DevelopmentMarketRow> listMarkets(UUID projectId) {
    List<DevelopmentMarketRow> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id,project_id,market_code,platform_code,target_price,currency,priority,sort_rank"
                + " from development_project_market where tenant_id = ?::uuid and project_id = ?::uuid"
                + " order by priority,sort_rank,id",
            currentTenant(),
            projectId)) {
      items.add(marketRow(row));
    }
    return items;
  }

  /** 登记一个目标市场与平台。 */
  @Transactional
  public List<DevelopmentMarketRow> createMarket(UUID projectId, DevelopmentMarketInput input) {
    requireProject(projectId);
    String currency = normalizeCurrency(input.currency());
    requirePositivePrice(input.targetPrice());
    var exists =
        sql.fetchOne(
            "select id from development_project_market where tenant_id = ?::uuid"
                + " and project_id = ?::uuid and market_code = ?::varchar and platform_code = ?::varchar",
            currentTenant(),
            projectId,
            input.marketCode().trim(),
            input.platformCode().trim());
    if (exists != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "该立项已登记过「"
              + input.marketCode().trim()
              + " × "
              + input.platformCode().trim()
              + "」这一组目标市场与平台");
    }
    sql.execute(
        "insert into development_project_market(id,tenant_id,project_id,market_code,platform_code,"
            + " target_price,currency,priority,sort_rank,created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::numeric,?::varchar,?::int,"
            + " ?::int,now(),now())",
        UUID.randomUUID(),
        currentTenant(),
        projectId,
        input.marketCode().trim(),
        input.platformCode().trim(),
        input.targetPrice(),
        currency,
        input.priority() == null ? 1 : input.priority(),
        input.sortRank() == null ? 0 : input.sortRank());
    return listMarkets(projectId);
  }

  /** 删一个目标市场与平台。 */
  @Transactional
  public List<DevelopmentMarketRow> deleteMarket(UUID projectId, UUID marketId) {
    requireProject(projectId);
    var row =
        sql.fetchOne(
            "select id from development_project_market where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            marketId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "目标市场与平台不存在");
    }
    sql.execute(
        "delete from development_project_market where tenant_id = ?::uuid and id = ?::uuid",
        currentTenant(),
        marketId);
    return listMarkets(projectId);
  }

  // ================================================================ 规格候选

  /** 规格候选清单。 */
  public List<DevelopmentSpecCandidateRow> listSpecCandidates(UUID projectId) {
    List<DevelopmentSpecCandidateRow> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id,project_id,spec_name,spec_value,sort_rank"
                + " from development_project_spec_candidate where tenant_id = ?::uuid"
                + " and project_id = ?::uuid order by sort_rank,id",
            currentTenant(),
            projectId)) {
      items.add(
          new DevelopmentSpecCandidateRow(
              row.get("id", UUID.class),
              row.get("project_id", UUID.class),
              row.get("spec_name", String.class),
              row.get("spec_value", String.class),
              row.get("sort_rank", Integer.class)));
    }
    return items;
  }

  /** 登记一条规格候选。 */
  @Transactional
  public List<DevelopmentSpecCandidateRow> createSpecCandidate(
      UUID projectId, DevelopmentSpecCandidateInput input) {
    requireProject(projectId);
    var exists =
        sql.fetchOne(
            "select id from development_project_spec_candidate where tenant_id = ?::uuid"
                + " and project_id = ?::uuid and spec_name = ?::varchar and spec_value = ?::varchar",
            currentTenant(),
            projectId,
            input.specName().trim(),
            input.specValue().trim());
    if (exists != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "该立项已有同名同值的规格候选：" + input.specName().trim());
    }
    sql.execute(
        "insert into development_project_spec_candidate(id,tenant_id,project_id,spec_name,spec_value,"
            + " sort_rank,created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::int,now(),now())",
        UUID.randomUUID(),
        currentTenant(),
        projectId,
        input.specName().trim(),
        input.specValue().trim(),
        input.sortRank() == null ? 0 : input.sortRank());
    return listSpecCandidates(projectId);
  }

  /** 删一条规格候选。 */
  @Transactional
  public List<DevelopmentSpecCandidateRow> deleteSpecCandidate(UUID projectId, UUID candidateId) {
    requireProject(projectId);
    var row =
        sql.fetchOne(
            "select id from development_project_spec_candidate where tenant_id = ?::uuid"
                + " and id = ?::uuid",
            currentTenant(),
            candidateId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "规格候选不存在");
    }
    sql.execute(
        "delete from development_project_spec_candidate where tenant_id = ?::uuid and id = ?::uuid",
        currentTenant(),
        candidateId);
    return listSpecCandidates(projectId);
  }

  // ================================================================ 市场观察

  /** 手工录入的市场数据清单。 */
  public List<DevelopmentMarketObservationRow> listMarketObservations(UUID projectId) {
    List<DevelopmentMarketObservationRow> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id,project_id,market_code,platform_code,metric_code,metric_value,value_unit,"
                + " source,collected_at,note from development_project_market_observation"
                + " where tenant_id = ?::uuid and project_id = ?::uuid"
                + " order by collected_at desc,id desc",
            currentTenant(),
            projectId)) {
      items.add(
          new DevelopmentMarketObservationRow(
              row.get("id", UUID.class),
              row.get("project_id", UUID.class),
              row.get("market_code", String.class),
              row.get("platform_code", String.class),
              row.get("metric_code", String.class),
              row.get("metric_value", BigDecimal.class),
              row.get("value_unit", String.class),
              row.get("source", String.class),
              row.get("collected_at", OffsetDateTime.class),
              row.get("note", String.class)));
    }
    return items;
  }

  /**
   * 登记一条手工录入的市场数据。
   *
   * <p>🔴 **来源与采集时间必填**（DB 层也有 CHECK）：没有来源的市场数据比没有市场数据更危险 ——
   * 它会被当成系统的结论。
   */
  @Transactional
  public List<DevelopmentMarketObservationRow> createMarketObservation(
      UUID projectId, DevelopmentMarketObservationInput input) {
    requireProject(projectId);
    sql.execute(
        "insert into development_project_market_observation(id,tenant_id,project_id,market_code,"
            + " platform_code,metric_code,metric_value,value_unit,source,collected_at,note,created_by,"
            + " created_at) values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::numeric,"
            + " ?::varchar,?::varchar,?::timestamptz,?::varchar,?::uuid,now())",
        UUID.randomUUID(),
        currentTenant(),
        projectId,
        input.marketCode().trim(),
        blankToEmpty(input.platformCode()),
        input.metricCode().trim(),
        input.metricValue(),
        blankToEmpty(input.valueUnit()),
        input.source().trim(),
        input.collectedAt(),
        blankToEmpty(input.note()),
        currentActorOrThrow());
    return listMarketObservations(projectId);
  }

  // ================================================================ 成本估算

  /** 预览目标成本估算（**不落库**）。 */
  public DevelopmentCostEstimateView previewCostEstimate(
      UUID id, UUID referenceSkuId, String bearer) {
    Record project = requireProject(id);
    UUID skuId = referenceSkuId == null ? project.get("reference_sku_id", UUID.class) : referenceSkuId;
    BigDecimal price = project.get("target_price", BigDecimal.class);
    String currency = project.get("currency", String.class);
    return costEstimate.estimate(skuId, price, currency, bearer);
  }

  /**
   * 应用目标成本估算（落库快照 + 服务端重算目标毛利率）。
   *
   * <p>🔴 取不到就**明确失败**（503 / 404），**不**把已有的成本悄悄清成「取不到」——
   * 静默清空会让页面上的毛利无声地变成「无数据」，而人以为成本还在。
   */
  @Transactional
  public DevelopmentProjectDetail applyCostEstimate(
      UUID id, UUID referenceSkuId, String referenceSkuCode, String bearer) {
    Record project = requireProject(id);
    UUID skuId = referenceSkuId == null ? project.get("reference_sku_id", UUID.class) : referenceSkuId;
    BigDecimal price = project.get("target_price", BigDecimal.class);
    String currency = project.get("currency", String.class);
    DevelopmentCostEstimateView estimate = costEstimate.estimate(skuId, price, currency, bearer);
    if (!estimate.available()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "目标成本取不到（归 "
              + estimate.ownerModule()
              + " 管）："
              + estimate.unavailableReason()
              + "。已保留原有成本，不做静默清空。");
    }
    BigDecimal margin = targetMarginRate(price, estimate.unitCost());
    sql.execute(
        "update development_project set target_cost = ?::numeric, cost_currency = ?::varchar,"
            + " cost_available = true, cost_source = ?::varchar, cost_owner_module = '',"
            + " cost_basis = ?::varchar, cost_estimated_at = ?::timestamptz, target_margin_rate = ?::numeric,"
            + " margin_basis = ?::varchar, reference_sku_id = ?::uuid, reference_sku_code = ?::varchar,"
            + " updated_at = now() where tenant_id = ?::uuid and id = ?::uuid",
        estimate.unitCost(),
        currency,
        DevelopmentCostSource.CATALOG_ESTIMATE.name(),
        blankToEmpty(estimate.basis()),
        estimate.estimatedAt() == null ? OffsetDateTime.now() : estimate.estimatedAt(),
        margin,
        marginBasis(price, estimate.unitCost(), currency),
        skuId,
        referenceSkuCode == null ? "" : referenceSkuCode.trim(),
        currentTenant(),
        id);
    return detail(id);
  }

  // ================================================================ SKU 建档候选

  /** 可转 SKU 建档的候选参数（蓝图输出句逐字对应）。 */
  public DevelopmentSkuCandidate skuCandidate(UUID id) {
    DevelopmentProjectDetail detail = detail(id);
    DevelopmentProjectRow project = detail.project();
    List<DevelopmentMarketRow> markets = detail.markets();
    BigDecimal min = null;
    BigDecimal max = null;
    for (DevelopmentMarketRow market : markets) {
      min = min == null || market.targetPrice().compareTo(min) < 0 ? market.targetPrice() : min;
      max = max == null || market.targetPrice().compareTo(max) > 0 ? market.targetPrice() : max;
    }
    if (min == null) {
      min = project.targetPrice();
      max = project.targetPrice();
    }
    OffsetDateTime sampling = null;
    OffsetDateTime massProduction = null;
    for (DevelopmentMilestoneRow milestone : detail.milestones()) {
      if (milestone.milestoneType().equals(DevelopmentMilestoneType.SAMPLING.name())) {
        sampling = milestone.plannedAt();
      }
      if (milestone.milestoneType().equals(DevelopmentMilestoneType.MASS_PRODUCTION.name())) {
        massProduction = milestone.plannedAt();
      }
    }
    DevelopmentStatus status = DevelopmentStatus.valueOf(project.status());
    boolean ready = status != DevelopmentStatus.EVALUATION && status != DevelopmentStatus.TERMINATED;
    return new DevelopmentSkuCandidate(
        project.id(),
        project.projectNo(),
        project.name(),
        project.category(),
        markets,
        min,
        max,
        project.currency(),
        detail.specCandidates(),
        project.launchTargetAt(),
        sampling,
        massProduction,
        project.status(),
        project.statusLabel(),
        DevelopmentHandoffType.SKU_ONBOARDING.name(),
        DevelopmentHandoffType.SKU_ONBOARDING.targetModule(),
        ready,
        "本模块**只产出候选参数**，不建 SKU、不写 catalog 的表 —— SKU 的唯一写入所有者是 catalog"
            + "（3.1.1.1），正式的建档动作属 6.6.2.2 转量产与建档。"
            + (ready
                ? "本立项已通过立项评审，候选参数可交接。"
                : "本立项尚未通过立项评审（当前是「"
                    + status.label()
                    + "」），候选参数暂不可交接。"));
  }

  // ================================================================ 交接单

  /** 交接单清单。 */
  public List<DevelopmentHandoffRow> listHandoffs(UUID projectId) {
    List<DevelopmentHandoffRow> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id,project_id,handoff_type,target_module,status,payload,note,created_by,created_at"
                + " from development_project_handoff where tenant_id = ?::uuid and project_id = ?::uuid"
                + " order by created_at desc,id desc",
            currentTenant(),
            projectId)) {
      items.add(handoffRow(row));
    }
    return items;
  }

  /**
   * 产出一条下游交接单（打样单 / 试产单 / SKU 建档）。
   *
   * <p>🔴 本模块**不代下游建单**：6.6.1.2 与 6.6.2.1 未交付，3.1.1.1 归 catalog。
   * 交接单里带的是**候选参数**，等下游交付后由下游消费。
   */
  @Transactional
  public DevelopmentHandoffRow createHandoff(
      UUID projectId, DevelopmentHandoffInput input, UUID actorId) {
    DevelopmentProjectDetail detail = detail(projectId);
    DevelopmentStatus status = DevelopmentStatus.valueOf(detail.project().status());
    if (status == DevelopmentStatus.EVALUATION || status == DevelopmentStatus.TERMINATED) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "立项当前是「"
              + status.label()
              + "」，尚未通过立项评审（已终止的立项也不再交接）——"
              + "蓝图说的是「立项通过后可派生打样单与试产单」");
    }
    var exists =
        sql.fetchOne(
            "select id from development_project_handoff where tenant_id = ?::uuid"
                + " and project_id = ?::uuid and handoff_type = ?::varchar",
            currentTenant(),
            projectId,
            input.handoffType().name());
    if (exists != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "该立项已产出过「" + input.handoffType().label() + "」交接单（每种交接单一条）");
    }
    String payload = handoffPayload(detail, input.handoffType());
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into development_project_handoff(id,tenant_id,project_id,handoff_type,target_module,"
            + " status,payload,note,created_by,created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,'PENDING',?::jsonb,?::varchar,"
            + " ?::uuid,now(),now())",
        id,
        currentTenant(),
        projectId,
        input.handoffType().name(),
        input.handoffType().targetModule(),
        payload,
        blankToEmpty(input.note()),
        actorId);
    return listHandoffs(projectId).stream()
        .filter(row -> row.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "交接单写入后读不到"));
  }

  /** 交接单载荷（候选参数）。 */
  private String handoffPayload(DevelopmentProjectDetail detail, DevelopmentHandoffType type) {
    DevelopmentProjectRow project = detail.project();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("projectNo", project.projectNo());
    payload.put("name", project.name());
    payload.put("category", project.category());
    payload.put("status", project.status());
    payload.put("currency", project.currency());
    payload.put("targetPrice", project.targetPrice());
    payload.put(
        "targetMarkets",
        detail.markets().stream()
            .map(
                market ->
                    Map.of(
                        "marketCode", market.marketCode(),
                        "platformCode", market.platformCode(),
                        "targetPrice", market.targetPrice(),
                        "currency", market.currency(),
                        "priority", market.priority()))
            .toList());
    payload.put(
        "specCandidates",
        detail.specCandidates().stream()
            .map(spec -> Map.of("specName", spec.specName(), "specValue", spec.specValue()))
            .toList());
    payload.put("launchTargetAt", project.launchTargetAt() == null ? null : project.launchTargetAt().toString());
    payload.put(
        "milestones",
        detail.milestones().stream()
            .map(
                milestone ->
                    Map.of(
                        "milestoneType", milestone.milestoneType(),
                        "plannedAt", milestone.plannedAt().toString()))
            .toList());
    payload.put("producedBy", "6.6.1.1 开发立项");
    payload.put(
        "downstreamNote",
        "本载荷由 6.6.1.1 开发立项产出，只含**候选参数**；"
            + type.label()
            + "由 "
            + type.targetModule()
            + " 消费，本模块不代它建单。");
    try {
      return json.writeValueAsString(payload);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("交接单载荷序列化失败", e);
    }
  }

  // ================================================================ 内部工具

  /** 立项记录的公共 select 片段。 */
  private static final String PROJECT_SELECT =
      "select p.id,p.project_no,p.name,p.category,p.status,p.owner_id,p.owner_name,p.target_price,"
          + " p.currency,p.target_cost,p.cost_currency,p.cost_available,p.cost_source,"
          + " p.cost_owner_module,p.target_margin_rate,p.margin_basis,p.launch_target_at,p.note,"
          + " p.created_by,p.created_at,p.updated_at,"
          + " (select count(*) from development_project_market m where m.tenant_id = p.tenant_id"
          + "   and m.project_id = p.id) as market_count,"
          + " (select count(*) from development_project_milestone ms where ms.tenant_id = p.tenant_id"
          + "   and ms.project_id = p.id) as milestone_count,"
          + " (select ms.planned_at from development_project_milestone ms"
          + "   where ms.tenant_id = p.tenant_id and ms.project_id = p.id"
          + "   and ms.milestone_type = 'SAMPLING') as sampling_planned_at,"
          + " (select ms.planned_at from development_project_milestone ms"
          + "   where ms.tenant_id = p.tenant_id and ms.project_id = p.id"
          + "   and ms.milestone_type = 'MASS_PRODUCTION') as mass_production_planned_at"
          + " from development_project p";

  private Record requireProject(UUID id) {
    var row =
        sql.fetchOne(
            PROJECT_SELECT + " where p.tenant_id = ?::uuid and p.id = ?::uuid",
            currentTenant(),
            id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "开发立项不存在");
    }
    return row;
  }

  private void requireMilestone(UUID projectId, UUID milestoneId) {
    var row =
        sql.fetchOne(
            "select id from development_project_milestone where tenant_id = ?::uuid"
                + " and project_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            projectId,
            milestoneId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "里程碑不存在");
    }
  }

  private static DevelopmentProjectRow projectRow(Record row) {
    String status = row.get("status", String.class);
    String costSource = row.get("cost_source", String.class);
    return new DevelopmentProjectRow(
        row.get("id", UUID.class),
        row.get("project_no", String.class),
        row.get("name", String.class),
        row.get("category", String.class),
        status,
        DevelopmentStatus.valueOf(status).label(),
        row.get("owner_id", UUID.class),
        row.get("owner_name", String.class),
        row.get("target_price", BigDecimal.class),
        row.get("currency", String.class),
        row.get("target_cost", BigDecimal.class),
        row.get("cost_currency", String.class),
        Boolean.TRUE.equals(row.get("cost_available", Boolean.class)),
        costSource,
        DevelopmentCostSource.valueOf(costSource).label(),
        row.get("target_margin_rate", BigDecimal.class),
        row.get("margin_basis", String.class),
        countOf(row, "market_count"),
        countOf(row, "milestone_count"),
        row.get("launch_target_at", OffsetDateTime.class),
        row.get("sampling_planned_at", OffsetDateTime.class),
        row.get("mass_production_planned_at", OffsetDateTime.class),
        row.get("note", String.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private static DevelopmentMilestoneRow milestoneRow(Record row) {
    String type = row.get("milestone_type", String.class);
    return new DevelopmentMilestoneRow(
        row.get("id", UUID.class),
        row.get("project_id", UUID.class),
        type,
        DevelopmentMilestoneType.valueOf(type).label(),
        row.get("planned_at", OffsetDateTime.class),
        row.get("actual_at", OffsetDateTime.class),
        row.get("status", String.class),
        row.get("note", String.class));
  }

  private static DevelopmentMarketRow marketRow(Record row) {
    return new DevelopmentMarketRow(
        row.get("id", UUID.class),
        row.get("project_id", UUID.class),
        row.get("market_code", String.class),
        row.get("platform_code", String.class),
        row.get("target_price", BigDecimal.class),
        row.get("currency", String.class),
        row.get("priority", Integer.class),
        row.get("sort_rank", Integer.class));
  }

  private static DevelopmentHandoffRow handoffRow(Record row) {
    String type = row.get("handoff_type", String.class);
    return new DevelopmentHandoffRow(
        row.get("id", UUID.class),
        row.get("project_id", UUID.class),
        type,
        DevelopmentHandoffType.valueOf(type).label(),
        row.get("target_module", String.class),
        row.get("status", String.class),
        row.get("payload", String.class),
        row.get("note", String.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private static int countOf(Record row, String column) {
    Long value = row.get(column, Long.class);
    return value == null ? 0 : value.intValue();
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

  /** 当前操作人（用于「谁登记的」这类留痕）。取不到就拒 —— 留痕必须有主体。 */
  private UUID currentActorOrThrow() {
    var row =
        sql.fetchOne("select nullif(current_setting('app.actor', true), '')::uuid as a");
    UUID actor = row == null ? null : row.get("a", UUID.class);
    if (actor == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    return actor;
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

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
