package com.starcon.catalog;

import static com.starcon.catalog.CostAllocationModels.*;

import com.starcon.catalog.CostAllocationModels.BasisRule;
import com.starcon.catalog.CostAllocationModels.SourceType;
import com.starcon.catalog.CostAllocationPort.Consignment;
import com.starcon.catalog.CostAllocationPort.ConsignmentLine;
import com.starcon.catalog.CostAllocationPort.ConsignmentPage;
import com.starcon.catalog.CostAllocationPort.CostAllocationUnavailableException;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.2.1.3 成本分摊：把混合成本的货件按重量/体积/金额/数量规则分摊到各 SKU，支持人工微调并留痕。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：把混合成本的货件（一次采购多个 SKU 共用头程）按重量/体积/金额/数量规则分摊到各 SKU，支持人工微调并留痕。
 *
 * **输入**：货件成本总额；分摊规则；人工调整值与原因。
 *
 * **输出**：分摊明细（每 SKU 分摊金额）；重算与差异对比。
 *
 * **关系**：上游 = 10 头程费用、12 landed_cost_allocation；下游 = 5.2.1.1 成本构成、13 利润。**分摊规则变更会导致历史利润变化**，因此调整必须留历史版本，不能就地覆盖。
 *
 * </blockquote>
 *
 * <h2>本类的七条纪律</h2>
 *
 * <ol>
 *   <li><b>🔴 换规则 = 追加新版本，不就地覆盖</b>。蓝图加粗句是本模块唯一不可让步的一条：
 *       规则一换，13 利润拿到的每个 SKU 的成本就变了，而历史利润已经用旧规则算过了。
 *       所以 {@code cost_allocation_revision} 是<b>只追加</b>的，
 *       {@code cost_allocation.current_revision_id} 是「当前」的<b>指针</b>，历史一行不动。
 *   <li><b>🔴 逐行分摊额之和严格等于货件成本总额</b>。分摊是拆分不是缩水 ——
 *       用最大余数法（{@link CostAllocationRules}）保证逐分相等，DB 层再加一道延迟约束触发器。
 *   <li><b>🔴 基数取不到 ≠ 分摊到 0</b>。取不到的行<b>不参与分母</b>，并明确标「基数不可得」；
 *       所有行都取不到时<b>拒绝分摊</b>，不摊出一张全 0 的单。
 *   <li><b>🔴 人工微调必须带原因，且差额必须看得见</b>。微调是逐行的增减（可正可负），
 *       原因必填（DB 层也有 CHECK 兜底）。微调<b>故意</b>会让「逐行最终额之和」≠「货件成本总额」，
 *       那个差额是<b>人工决定的结果</b>，必须显示出来而不是藏起来。
 *   <li><b>🔴 合计不落表</b>。版本快照里没有任何「合计」列；逐行金额是唯一的事实，
 *       合计一律查询时 SUM 现算。落一份副本就多一处可能与逐行明细对不上的地方。
 *   <li><b>🔴 读不到 ≠ 没有</b>。上游不可达 ⇒ 读端点返回 <b>503</b>，理由写「读不到上游」；
 *       绝不返回空集合冒充「没有可分摊的货件」。
 *   <li><b>租户只来自已认证成员关系</b>（{@link AccessService}），不读 {@code X-Tenant-Id} 头。
 *       每条 SQL 的 where 都带显式 {@code tenant_id=} 绑定，RLS 再兜一道。
 * </ol>
 */
@Service
public class CostAllocationService {

  /** 租户上下文取法：RLS 已按它隔离，这里带上只是让执行计划走租户前缀的索引。 */
  private static final String TENANT = "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 台账页大小上限：与相邻模块一致，防止一次拉爆整个租户的数据。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 历史版本一次取多少条。历史不可变，页面上按版本号倒序翻。 */
  private static final int MAX_REVISIONS = 100;

  /** 千克 → 克。 */
  private static final BigDecimal GRAMS_PER_KG = new BigDecimal("1000");

  /** 立方米 → 立方厘米。 */
  private static final BigDecimal CM3_PER_CBM = new BigDecimal("1000000");

  /** 立方毫米 → 立方厘米。 */
  private static final BigDecimal CM3_PER_MM3 = new BigDecimal("1000");

  private final DSLContext sql;
  private final CostAllocationPort port;

  public CostAllocationService(DSLContext sql, CostAllocationPort port) {
    this.sql = sql;
    this.port = port;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：四种规则、两种来源、四个基数的取数口径、版本纪律、上下游关系。
   *
   * <p>🔴 每一条都写清「真值在哪、当前取不取得到、缺了会怎样」。
   * 一个只说「按重量分摊」的声明，等于把「重量取的是货件行的申报毛重」这件事藏起来。
   */
  public PricingCostAllocationCapabilities capabilities() {
    var rules =
        List.of(
            new PricingCostAllocationRuleOption("WEIGHT", BasisRule.WEIGHT.label(), BasisRule.WEIGHT.basis()),
            new PricingCostAllocationRuleOption("VOLUME", BasisRule.VOLUME.label(), BasisRule.VOLUME.basis()),
            new PricingCostAllocationRuleOption("AMOUNT", BasisRule.AMOUNT.label(), BasisRule.AMOUNT.basis()),
            new PricingCostAllocationRuleOption("QUANTITY", BasisRule.QUANTITY.label(), BasisRule.QUANTITY.basis()));
    var sources =
        List.of(
            new PricingCostAllocationSourceOption(
                "FIRST_MILE_SHIPMENT",
                SourceType.FIRST_MILE_SHIPMENT.label(),
                SourceType.FIRST_MILE_SHIPMENT.basis()),
            new PricingCostAllocationSourceOption("MANUAL", SourceType.MANUAL.label(), SourceType.MANUAL.basis()));

    return new PricingCostAllocationCapabilities(
        rules,
        sources,
        List.of("USD", "EUR", "CNY", "GBP", "JPY"),
        "上游真值：可分摊的货件 = warehouse 的头程发运单（first_mile_shipment，V80）—— "
            + "**一张发运单里出现多个 SKU，就是蓝图说的「一次采购多个 SKU 共用头程」的混合成本货件**。"
            + "本服务经 warehouse 的授权只读端点取数（AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图，"
            + "本库不存发运单副本），调用方自己的 Authorization 头原样透传，"
            + "租户由 warehouse 从已认证成员关系解析。上游读不到一律 **503 报「读不到」**，"
            + "**绝不返回空集合冒充「没有可分摊的货件」**。"
            + "🔴 **12 财务（finance 服务）在本仓不存在** ⇒ 蓝图里的 12 landed_cost_allocation 没有来源；"
            + "本模块的分摊单与明细**由本服务自持**，这是**本服务口径，不是 12 财务的核算结果**，"
            + "与 12 财务的最终对账留待 finance 落地后经授权 API / event 衔接。",
        "四种基数的取数来源：数量 = 货件行数量（上游口径；发运行没有计量单位字段，因此不做单位折算）；"
            + "毛重 = 货件行申报毛重（千克 → 克），为 0 时回落 catalog 的 SKU 包装毛重；"
            + "体积 = 货件行申报体积（立方米 → 立方厘米），为 0 时回落 SKU 包装外形尺寸换算；"
            + "货值 = 货件行申报货值，为 0 时回落 5.2.1.1 成本构成的采购单价 × 数量"
            + "（**币种必须与本单一致，不折算** —— 本仓没有汇率表，汇率是 12 财务的资产）。"
            + "🔴 **基数取不到的行不参与分母**，并明确标「基数不可得」—— "
            + "它不是「摊到 0」，是「没基数可分」。所有行都取不到时**拒绝分摊**。",
        "🔴 **分摊规则变更会导致历史利润变化，因此调整必须留历史版本，不能就地覆盖。** "
            + "换规则 / 改总额 / 人工微调都会**追加一个新版本**，旧版本的明细行原样留在库里；"
            + "「当前的分摊明细」= 当前版本的那一组行，**没有 UPDATE 端点，也永远不会有**。"
            + "逐行的计算分摊额之和**严格等于**货件成本总额（最大余数法 + 数据库延迟约束触发器双保险）；"
            + "**合计一律不落表，查询时 SUM 现算**。",
        "下游：5.2.1.1 成本构成（它的「头程分摊」分项）与 13 利润 —— 两者读的是本模块的**当前版本**，"
            + "而历史利润是用**当时的版本**算出来的，所以留版本不只是为了好看。"
            + "⚠️ 5.2.1.1 当前仍按自己的口径（上游发运单按行数量摊）派生头程分摊；"
            + "本模块**不改**它的代码 —— 两者对齐需要改 5.2.1.1，那是另一条车道的写入范围。",
        "可做的动作：新建分摊单（选货件 + 选规则 + 填货件成本总额）、"
            + "试算重算（不落库，只看差异）、应用重算（追加一个新版本）、"
            + "查看版本历史与任意两版的差异对比、逐行人工微调（**原因必填**）。"
            + "**没有**「就地改某一版明细」的入口 —— 改就是改出新版本。");
  }

  // ================================================================ 来源候选

  /**
   * 可分摊的货件候选（头程发运单）。
   *
   * <p>🔴 上游读不到 ⇒ **503**，不是空清单。返回空清单会让页面显示「没有可分摊的货件」，
   * 而真实情况可能是「仓库服务挂了」。
   */
  public PricingCostAllocationSourceCandidatePage sources(String bearer) {
    var page = upstreamConsignments(bearer);
    var allocated = new LinkedHashMap<String, Boolean>();
    for (var record :
        sql.fetch(
            "select source_ref from cost_allocation where tenant_id="
                + TENANT
                + " and source_type='FIRST_MILE_SHIPMENT'")) {
      allocated.put(record.get(0, String.class), Boolean.TRUE);
    }
    var items = new ArrayList<PricingCostAllocationSourceCandidate>();
    for (var ref : page.items()) {
      items.add(
          new PricingCostAllocationSourceCandidate(
              ref.id().toString(),
              ref.code(),
              ref.currency(),
              ref.totalCost(),
              ref.lineCount(),
              ref.createdAt(),
              allocated.containsKey(ref.id().toString())));
    }
    String note =
        "候选来自 warehouse 的头程发运单（授权只读端点，调用方的 Authorization 头原样透传，"
            + "租户由 warehouse 从已认证成员关系解析）。"
            + "**一张发运单里多个 SKU 才是「混合成本的货件」** —— 单 SKU 的发运单没有分摊的意义。";
    if (page.truncated()) {
      note =
          "🔴 上游发运单数超过本端口的覆盖上限，候选清单只覆盖了最近的一批 —— "
              + "看不到某张单不代表它不存在。";
    }
    return new PricingCostAllocationSourceCandidatePage(items, items.size(), note, page.truncated());
  }

  // ================================================================ 列表

  /**
   * 分摊单台账。
   *
   * <p>列表<b>不实时连上游</b>：分摊明细是本服务自持的事实，读取不需要跨服务调用。
   * 上游只在「新建 / 重算」时读一次（读货件的行）。
   */
  public PricingCostAllocationListPage list(PricingCostAllocationListQuery query) {
    int size = query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
    int page = Math.max(query.page(), 0);
    var where = new StringBuilder(" where a.tenant_id = " + TENANT);
    var binds = new ArrayList<Object>();
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.append(" and (a.source_ref ilike ? or a.source_label ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
    }
    if (query.sourceType() != null && !query.sourceType().isBlank()) {
      where.append(" and a.source_type = ?");
      binds.add(query.sourceType().trim().toUpperCase(Locale.ROOT));
    }
    if (query.currency() != null && !query.currency().isBlank()) {
      where.append(" and r.currency = ?");
      binds.add(query.currency().trim().toUpperCase(Locale.ROOT));
    }
    if (query.basisRule() != null && !query.basisRule().isBlank()) {
      where.append(" and r.basis_rule = ?");
      binds.add(query.basisRule().trim().toUpperCase(Locale.ROOT));
    }

    String from =
        " from cost_allocation a"
            + " left join cost_allocation_revision r on r.tenant_id=a.tenant_id and r.id=a.current_revision_id"
            + " left join lateral ("
            + "   select count(*)::int as line_count,"
            + "          sum(l.allocated_amount) as allocated_total,"
            + "          sum(l.adjustment_amount) as adjustment_total,"
            + "          sum(l.allocated_amount + l.adjustment_amount) as final_total"
            + "   from cost_allocation_revision_line l"
            + "   where l.tenant_id=a.tenant_id and l.revision_id=r.id"
            + " ) t on true";
    long total = count("select count(*)" + from + where, binds.toArray());
    var records =
        sql.fetch(
            "select a.id as allocation_id, a.source_type, a.source_ref, a.source_label, a.updated_at,"
                + " r.revision_no, r.basis_rule, r.total_cost, r.currency, r.effective_from,"
                + " (select count(*)::int from cost_allocation_revision x"
                + "   where x.tenant_id=a.tenant_id and x.allocation_id=a.id) as revision_count,"
                + " coalesce(t.line_count,0) as line_count,"
                + " t.allocated_total, t.adjustment_total, t.final_total"
                + from
                + where
                + " order by coalesce(a.updated_at, a.created_at) desc, a.source_ref"
                + " limit "
                + size
                + " offset "
                + ((long) page * size),
            binds.toArray());

    var items = new ArrayList<PricingCostAllocationRow>();
    for (var record : records) {
      var rule = record.get("basis_rule", String.class);
      var ruleEnum = rule == null ? null : BasisRule.parse(rule);
      var sourceType = record.get("source_type", String.class);
      var sourceEnum = sourceType == null ? null : SourceType.parse(sourceType);
      items.add(
          new PricingCostAllocationRow(
              record.get("allocation_id", UUID.class),
              sourceType,
              sourceEnum == null ? sourceType : sourceEnum.label(),
              record.get("source_ref", String.class),
              record.get("source_label", String.class),
              record.get("currency", String.class),
              record.get("total_cost", BigDecimal.class),
              rule,
              ruleEnum == null ? rule : ruleEnum.label(),
              record.get("revision_no", Integer.class),
              intOrZero(record.get("revision_count", Integer.class)),
              intOrZero(record.get("line_count", Integer.class)),
              record.get("allocated_total", BigDecimal.class),
              record.get("adjustment_total", BigDecimal.class),
              record.get("final_total", BigDecimal.class),
              variance(
                  record.get("allocated_total", BigDecimal.class),
                  record.get("adjustment_total", BigDecimal.class),
                  record.get("total_cost", BigDecimal.class)),
              record.get("effective_from", OffsetDateTime.class),
              record.get("updated_at", OffsetDateTime.class)));
    }
    return new PricingCostAllocationListPage(
        items,
        total,
        page,
        size,
        "台账列的是**当前版本**的口径；历史版本在明细页的版本历史里。"
            + "「差额」= 逐行最终分摊额之和 − 货件成本总额，它是**人工微调造成**的，不是错误。");
  }

  // ================================================================ 新建

  /**
   * 新建分摊单（并落第一个版本）。
   *
   * <p>🔴 一次写入 = 一张分摊单 + 一个版本 + 若干明细行，**全在同一个事务里** ——
   * 数据库的延迟约束触发器要在提交时校验「逐行之和 == 货件成本总额」。
   */
  public PricingCostAllocationSaved create(
      AccessService.Access actor, PricingCostAllocationCreateInput input, String bearer) {
    var sourceType = SourceType.parse(input.sourceType());
    if (sourceType == null) {
      throw badRequest("来源类型只能是 FIRST_MILE_SHIPMENT（头程发运单）或 MANUAL（人工货件）");
    }
    var rule = BasisRule.parse(input.basisRule());
    if (rule == null) {
      throw badRequest("分摊规则只能是 WEIGHT / VOLUME / AMOUNT / QUANTITY");
    }
    if (input.changeReason() == null || input.changeReason().isBlank()) {
      throw badRequest("变更原因必填：没有理由的历史等于没有历史，半年后没人知道当时为什么换规则");
    }
    if (input.effectiveFrom() == null) {
      throw badRequest("生效时间必填：这一版从哪一刻开始算数");
    }
    if (input.sourceRef() == null || input.sourceRef().isBlank()) {
      throw badRequest("来源标识必填");
    }

    var source = loadSource(sourceType, input.sourceRef(), input.lines(), input.currency(), bearer);
    if (input.currency() != null && !input.currency().isBlank()) {
      var wanted = input.currency().trim().toUpperCase(Locale.ROOT);
      if (!wanted.matches("^[A-Z]{3}$")) {
        throw badRequest("计价币种请用 ISO 4217 三位字母码：" + input.currency());
      }
      if (sourceType == SourceType.FIRST_MILE_SHIPMENT && !wanted.equals(source.currency())) {
        throw badRequest(
            "这批发运单的计价币种是 "
                + source.currency()
                + "，与填的 "
                + wanted
                + " 不一致。**跨币种绝不相加** —— 改币种等于换了一批钱。");
      }
    }
    BigDecimal totalCost = input.totalCost() != null ? input.totalCost() : source.totalCost();
    if (sourceType == SourceType.MANUAL && input.totalCost() == null) {
      throw badRequest("人工货件必须填货件成本总额（12 财务的 landed_cost_allocation 未交付，本服务自持）");
    }
    if (totalCost == null || totalCost.signum() < 0) {
      throw badRequest("货件成本总额不能为负");
    }
    if (totalCost.stripTrailingZeros().scale() > CostAllocationRules.MONEY_SCALE) {
      throw badRequest("货件成本总额最多 4 位小数（分摊额的精度就是 4 位）");
    }

    var sourceLines = buildSourceLines(source, source.currency());
    var outcome = allocate(totalCost, sourceLines, rule);
    var adjustments = readAdjustments(input.adjustments(), sourceLines);
    var persist = persistLines(sourceLines, outcome, rule, adjustments);

    UUID allocationId = Ids.next();
    try {
      sql.execute(
          "insert into cost_allocation(id,tenant_id,source_type,source_ref,source_label,"
              + "current_revision_id,version,created_by,created_at,updated_at)"
              + " values(?,?,?,?,?,null,0,?,now(),now())",
          allocationId,
          actor.tenantId(),
          sourceType.name(),
          input.sourceRef().trim(),
          input.sourceLabel() == null ? "" : input.sourceLabel().trim(),
          actor.userId());
    } catch (org.springframework.dao.DuplicateKeyException e) {
      throw conflict("这个货件已经有一张分摊单了（一个货件只能摊一次）");
    }

    var revision =
        appendRevision(
            actor,
            allocationId,
            rule,
            totalCost,
            source.currency(),
            input.effectiveFrom(),
            input.changeReason(),
            persist);
    sql.execute(
        "update cost_allocation set current_revision_id=?, version=version+1, updated_at=now()"
            + " where tenant_id=? and id=?",
        revision.id(),
        actor.tenantId(),
        allocationId);

    return saved(
        allocationId,
        revision,
        rule,
        persist,
        totalCost,
        source.currency(),
        input.effectiveFrom());
  }

  // ================================================================ 明细

  /** 分摊单明细：当前版本 + 当前版本的行 + 与上一版的差异 + 版本历史。 */
  public PricingCostAllocationDetail detail(UUID allocationId) {
    var header = requireAllocation(allocationId);
    var current = currentRevision(allocationId);
    var lines = current == null ? List.<PricingCostAllocationLineRow>of() : lines(current.id(), current.basisRule());
    var revisions = revisionRows(allocationId);
    PricingCostAllocationDiff lastDiff = null;
    if (revisions.size() >= 2) {
      lastDiff = diffOf(revisions.get(1), revisions.get(0));
    }
    return new PricingCostAllocationDetail(
        header.id(),
        header.sourceType(),
        header.sourceTypeLabel(),
        header.sourceRef(),
        header.sourceLabel(),
        current == null ? null : toRevisionRow(current),
        lines,
        lastDiff,
        revisions,
        "「当前的分摊明细」是**当前版本**的快照。历史版本不会被覆盖 —— "
            + "规则一换，13 利润拿到的每个 SKU 的成本就变了，所以每一步都留版本。");
  }

  /** 版本历史（新到旧）。 */
  public List<PricingCostAllocationRevisionRow> revisions(UUID allocationId) {
    requireAllocation(allocationId);
    return revisionRows(allocationId);
  }

  /** 某一个版本的明细。 */
  public PricingCostAllocationRevisionDetail revision(UUID allocationId, int revisionNo) {
    requireAllocation(allocationId);
    var aggregate = revisionAggregate(allocationId, revisionNo);
    if (aggregate == null) {
      throw notFound("分摊单 " + allocationId + " 没有版本 " + revisionNo);
    }
    var lines = lines(aggregate.id(), aggregate.basisRule());
    return new PricingCostAllocationRevisionDetail(
        toRevisionRow(aggregate),
        lines,
        "这是**当时的快照**：规则、总额、逐行基数与分摊额都按那一刻的值冻结，不可变。");
  }

  /** 两个版本之间的差异对比（from / to 缺省 = 最近两版）。 */
  public PricingCostAllocationDiff diff(UUID allocationId, Integer from, Integer to) {
    requireAllocation(allocationId);
    var revisions = revisionRows(allocationId);
    if (revisions.size() < 2) {
      throw badRequest("这张分摊单只有 1 个版本，还没有可比的两版（改一次规则或微调一次就有了）");
    }
    PricingCostAllocationRevisionRow before;
    PricingCostAllocationRevisionRow after;
    if (from == null && to == null) {
      after = revisions.get(0);
      before = revisions.get(1);
    } else {
      int fromNo = from == null ? revisions.get(1).revisionNo() : from;
      int toNo = to == null ? revisions.get(0).revisionNo() : to;
      before = findRevision(revisions, fromNo);
      after = findRevision(revisions, toNo);
    }
    return diffOf(before, after);
  }

  // ================================================================ 重算

  /**
   * 试算重算（**不落库**）：给出新规则 / 新总额 / 新微调下的分摊明细与差异。
   *
   * <p>🔴 试算不写任何东西 —— 「重算与差异对比」是蓝图的输出，而**落库**是另一个动作
   * （{@link #applyRecalculation}）。两者分开，运营才能先看清「换规则会差多少」再决定要不要改。
   */
  public PricingCostAllocationPreview recalculate(UUID allocationId, PricingCostAllocationRecalculateInput input, String bearer) {
    var header = requireAllocation(allocationId);
    var current = currentRevision(allocationId);
    if (current == null) {
      throw badRequest("这张分摊单还没有版本，无法重算");
    }
    var plan = planRecalculation(header, current, input, bearer);
    var currentLines = lines(current.id(), current.basisRule());
    var diff = CostAllocationRules.diff(currentLines, plan.lines());
    int changed = 0;
    for (var row : diff) {
      if (!"SAME".equals(row.change())) changed++;
    }
    var currentTotal = sumOf(currentLines, "final");
    var finalTotal = sumOf(plan.lines(), "final");
    return new PricingCostAllocationPreview(
        plan.rule().name(),
        plan.rule().label(),
        plan.totalCost(),
        plan.currency(),
        plan.lines(),
        sumOf(plan.lines(), "allocated"),
        sumOf(plan.lines(), "adjustment"),
        finalTotal,
        variance(sumOf(plan.lines(), "allocated"), sumOf(plan.lines(), "adjustment"), plan.totalCost()),
        new PricingCostAllocationDiff(
            !plan.rule().name().equals(current.basisRule()),
            current.basisRule(),
            plan.rule().name(),
            currentTotal,
            finalTotal,
            finalTotal.subtract(currentTotal),
            changed,
            diff,
            "这是**试算**：没有落库。确认无误后用「应用重算」把它追加成新版本。"),
        "试算结果与当前版本的差异。**逐行的计算分摊额之和仍严格等于货件成本总额**；"
            + "「差额」来自人工微调，是人工决定的结果。");
  }

  /**
   * 应用重算：追加一个新版本，旧版本原样留着。
   *
   * <p>🔴 蓝图加粗句的物理落点：这里**没有** UPDATE 任何一行旧明细。
   */
  public PricingCostAllocationSaved applyRecalculation(
      AccessService.Access actor, UUID allocationId, PricingCostAllocationRecalculateInput input, String bearer) {
    var header = requireAllocation(allocationId);
    var current = currentRevision(allocationId);
    if (current == null) {
      throw badRequest("这张分摊单还没有版本，无法重算");
    }
    if (input.changeReason() == null || input.changeReason().isBlank()) {
      throw badRequest("变更原因必填：没有理由的历史等于没有历史");
    }
    if (input.effectiveFrom() == null) {
      throw badRequest("生效时间必填：这一版从哪一刻开始算数");
    }
    var plan = planRecalculation(header, current, input, bearer);
    var revision =
        appendRevision(
            actor,
            allocationId,
            plan.rule(),
            plan.totalCost(),
            plan.currency(),
            input.effectiveFrom(),
            input.changeReason(),
            plan.persist());
    sql.execute(
        "update cost_allocation set current_revision_id=?, version=version+1, updated_at=now()"
            + " where tenant_id=? and id=?",
        revision.id(),
        actor.tenantId(),
        allocationId);
    return saved(
        allocationId,
        revision,
        plan.rule(),
        plan.persist(),
        plan.totalCost(),
        plan.currency(),
        input.effectiveFrom());
  }

  // ================================================================ 内部：重算计划

  /** 一次重算算出来的东西（还没落库）。 */
  private record RecalcPlan(
      BasisRule rule,
      BigDecimal totalCost,
      String currency,
      List<PersistLine> persist,
      List<PricingCostAllocationLineRow> lines) {}

  /** 重算计划：读来源、算分摊、应用人工微调。 */
  private RecalcPlan planRecalculation(
      AllocationHeader header,
      RevisionAggregate current,
      PricingCostAllocationRecalculateInput input,
      String bearer) {
    var rule =
        input.basisRule() == null || input.basisRule().isBlank()
            ? BasisRule.parse(current.basisRule())
            : BasisRule.parse(input.basisRule());
    if (rule == null) {
      throw badRequest("分摊规则只能是 WEIGHT / VOLUME / AMOUNT / QUANTITY");
    }
    BigDecimal totalCost = input.totalCost() != null ? input.totalCost() : current.totalCost();
    if (totalCost == null || totalCost.signum() < 0) {
      throw badRequest("货件成本总额不能为负");
    }
    if (totalCost.stripTrailingZeros().scale() > CostAllocationRules.MONEY_SCALE) {
      throw badRequest("货件成本总额最多 4 位小数（分摊额的精度就是 4 位）");
    }

    var sourceType = SourceType.parse(header.sourceType());
    var source =
        sourceType == SourceType.FIRST_MILE_SHIPMENT
            ? consignmentToSource(upstreamConsignment(bearer, header.sourceRef()))
            : manualSource(header, current, input);

    var sourceLines = buildSourceLines(source, current.currency());
    var outcome = allocate(totalCost, sourceLines, rule);
    var adjustments = readAdjustments(input.adjustments(), sourceLines);
    var persist = persistLines(sourceLines, outcome, rule, adjustments);
    return new RecalcPlan(
        rule, totalCost, current.currency(), persist, toLineRows(persist, rule));
  }

  /** 人工货件的来源：优先用请求里带的行，没有就用当前版本里冻结的行。 */
  private Source manualSource(
      AllocationHeader header, RevisionAggregate current, PricingCostAllocationRecalculateInput input) {
    var lines = new ArrayList<ConsignmentLine>();
    if (input.lines() != null && !input.lines().isEmpty()) {
      var meta = skuMeta(input.lines().stream().map(PricingCostAllocationManualLineInput::skuId).toList());
      for (var line : input.lines()) {
        var info = meta.get(line.skuId());
        if (info == null) {
          throw badRequest("明细行里的 SKU 不存在或不属于本租户：" + line.skuId());
        }
        lines.add(
            new ConsignmentLine(
                line.skuId(),
                info.code(),
                info.name(),
                line.quantity(),
                BigDecimal.ZERO,
                line.amountBasis(),
                kg(line.weightGrams()),
                cbm(line.volumeCm3())));
      }
    } else {
      for (var line : lines(current.id(), current.basisRule())) {
        lines.add(
            new ConsignmentLine(
                line.skuId(),
                line.skuCode(),
                line.skuName(),
                line.baseQuantity(),
                BigDecimal.ZERO,
                line.amountBasis(),
                kg(line.weightGrams()),
                cbm(line.volumeCm3())));
      }
    }
    if (lines.isEmpty()) {
      throw badRequest("这个人工货件没有明细行，无法分摊");
    }
    return new Source(
        current.currency(), current.totalCost(), header.sourceLabel(), lines);
  }

  /** 上游的发运单 → 本模块的来源。 */
  private Source consignmentToSource(Consignment consignment) {
    return new Source(
        consignment.currency() == null ? null : consignment.currency().trim().toUpperCase(Locale.ROOT),
        consignment.totalCost(),
        consignment.code(),
        consignment.lines());
  }

  // ================================================================ 内部：来源与基数

  /** 一个货件来源（头程发运单或人工货件）。 */
  private record Source(
      String currency, BigDecimal totalCost, String label, List<ConsignmentLine> lines) {}

  /** 读来源。头程来源走 warehouse 的授权只读端点；人工来源用请求里的行。 */
  private Source loadSource(
      SourceType sourceType,
      String sourceRef,
      List<PricingCostAllocationManualLineInput> manualLines,
      String currency,
      String bearer) {
    if (sourceType == SourceType.FIRST_MILE_SHIPMENT) {
      var consignment = upstreamConsignment(bearer, sourceRef);
      if (consignment.lines().isEmpty()) {
        throw badRequest("这批发运单没有行，没有可分摊的货件");
      }
      if (consignment.currency() == null || consignment.currency().isBlank()) {
        throw badRequest("这批发运单没有计价币种，无法分摊（跨币种绝不相加）");
      }
      return new Source(
          consignment.currency().trim().toUpperCase(Locale.ROOT),
          consignment.totalCost(),
          consignment.code(),
          consignment.lines());
    }
    if (manualLines == null || manualLines.isEmpty()) {
      throw badRequest("人工货件必须提供明细行（至少一行：SKU + 数量）");
    }
    if (currency == null || currency.isBlank()) {
      throw badRequest("人工货件必须指定计价币种（跨币种绝不相加）");
    }
    var meta = skuMeta(manualLines.stream().map(PricingCostAllocationManualLineInput::skuId).toList());
    var lines = new ArrayList<ConsignmentLine>();
    for (var line : manualLines) {
      var info = meta.get(line.skuId());
      if (info == null) {
        throw badRequest("明细行里的 SKU 不存在或不属于本租户：" + line.skuId());
      }
      lines.add(
          new ConsignmentLine(
              line.skuId(),
              info.code(),
              info.name(),
              line.quantity(),
              BigDecimal.ZERO,
              line.amountBasis(),
              kg(line.weightGrams()),
              cbm(line.volumeCm3())));
    }
    return new Source(currency.trim().toUpperCase(Locale.ROOT), null, "", lines);
  }

  /** 上游：可分摊的货件候选。读不到 ⇒ **503**（不返回空集合冒充「没有货件」）。 */
  private ConsignmentPage upstreamConsignments(String bearer) {
    try {
      return port.consignments(bearer);
    } catch (CostAllocationUnavailableException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
    }
  }

  /** 上游：一张货件。读不到 ⇒ **503**。 */
  private Consignment upstreamConsignment(String bearer, String sourceRef) {
    try {
      return port.consignment(bearer, sourceRef);
    } catch (CostAllocationUnavailableException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
    }
  }

  /**
   * 一行货件（已把四种基数的取值与来源都算好）。
   *
   * @param weightGrams 毛重（克）；null = 不可得
   * @param weightSource 毛重的来源说明
   * @param volumeCm3 体积（立方厘米）；null = 不可得
   * @param volumeSource 体积的来源说明
   * @param amountBasis 货值；null = 不可得
   * @param amountSource 货值的来源说明
   */
  private record SourceLine(
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal quantity,
      BigDecimal weightGrams,
      String weightSource,
      BigDecimal volumeCm3,
      String volumeSource,
      BigDecimal amountBasis,
      String amountSource) {}

  /** SKU 的编码与名称。 */
  private record SkuMeta(String code, String name) {}

  /**
   * 把货件的行变成带基数的行。
   *
   * <p>🔴 四种基数的取值顺序（**上游优先，catalog 回落**）：
   *
   * <ol>
   *   <li>数量 = 货件行数量（上游口径；发运行没有计量单位字段，不做单位折算）。
   *   <li>毛重 = 货件行申报毛重（千克 → 克）；为 0 时回落 catalog 的 SKU 包装毛重（克）。
   *   <li>体积 = 货件行申报体积（立方米 → 立方厘米）；为 0 时回落 SKU 包装外形尺寸（毫米 → 立方厘米）。
   *   <li>货值 = 货件行申报货值；为 0 时回落 5.2.1.1 成本构成的采购单价 × 数量
   *       （**币种必须与本单一致，不折算** —— 本仓没有汇率表）。
   * </ol>
   *
   * <p>每一条回落都在 {@code basis_note} 里写明用的是哪一个 —— 页面上必须能回答
   * 「这一行的重量到底是哪来的」。
   */
  private List<SourceLine> buildSourceLines(Source source, String currency) {
    var skuIds = source.lines().stream().map(ConsignmentLine::skuId).distinct().toList();
    var packages = packageInfo(skuIds);
    var costs = purchaseCosts(skuIds);

    var lines = new ArrayList<SourceLine>();
    for (var line : source.lines()) {
      BigDecimal quantity = nz(line.shippedQuantity());
      if (quantity.signum() <= 0) quantity = nz(line.plannedQuantity());

      BigDecimal weightGrams = null;
      String weightSource = null;
      if (line.grossWeightKg() != null && line.grossWeightKg().signum() > 0) {
        weightGrams = line.grossWeightKg().multiply(GRAMS_PER_KG);
        weightSource = "取自货件行的申报毛重";
      } else {
        var pack = packages.get(line.skuId());
        if (pack != null && pack.grossWeightG() != null && pack.grossWeightG().signum() > 0) {
          weightGrams = pack.grossWeightG();
          weightSource = "货件行未申报毛重，改用 SKU 包装毛重";
        }
      }

      BigDecimal volumeCm3 = null;
      String volumeSource = null;
      if (line.volumeCbm() != null && line.volumeCbm().signum() > 0) {
        volumeCm3 = line.volumeCbm().multiply(CM3_PER_CBM);
        volumeSource = "取自货件行的申报体积";
      } else {
        var pack = packages.get(line.skuId());
        if (pack != null
            && pack.lengthMm() != null
            && pack.widthMm() != null
            && pack.heightMm() != null) {
          BigDecimal mm3 = pack.lengthMm().multiply(pack.widthMm()).multiply(pack.heightMm());
          volumeCm3 = mm3.divide(CM3_PER_MM3, 6, RoundingMode.HALF_UP);
          volumeSource = "货件行未申报体积，改用 SKU 包装外形尺寸换算";
        }
      }

      BigDecimal amountBasis = null;
      String amountSource = null;
      if (line.declaredValue() != null && line.declaredValue().signum() > 0) {
        amountBasis = line.declaredValue();
        amountSource = "取自货件行的申报货值";
      } else {
        var cost = costs.get(line.skuId());
        if (cost != null && cost.purchaseCost() != null && cost.purchaseCost().signum() > 0) {
          if (cost.currency() != null && cost.currency().equals(currency)) {
            amountBasis = cost.purchaseCost().multiply(quantity).setScale(4, RoundingMode.HALF_UP);
            amountSource =
                "货件行未申报货值，改用 5.2.1.1 成本构成的采购单价 × 货件行数量"
                    + "（行数量未必是基础单位，金额为估算）";
          } else {
            amountSource =
                "货件行未申报货值，而该 SKU 的成本构成币种是 "
                    + (cost.currency() == null ? "未知" : cost.currency())
                    + "，本单摊的是 "
                    + currency
                    + "，**不折算**（汇率表属 12 财务，本仓没有）";
          }
        } else {
          amountSource = "货件行未申报货值，该 SKU 也还没有维护成本构成（5.2.1.1）";
        }
      }

      lines.add(
          new SourceLine(
              line.skuId(),
              line.skuCode() == null ? "" : line.skuCode(),
              line.skuName() == null ? "" : line.skuName(),
              quantity,
              weightGrams,
              weightSource,
              volumeCm3,
              volumeSource,
              amountBasis,
              amountSource));
    }
    return lines;
  }

  /** SKU 包装信息（毛重与外形尺寸）。 */
  private record PackageInfo(
      BigDecimal grossWeightG, BigDecimal lengthMm, BigDecimal widthMm, BigDecimal heightMm) {}

  /** 5.2.1.1 成本构成里的采购单价（与本单同币种才可用）。 */
  private record PurchaseCost(BigDecimal purchaseCost, String currency) {}

  private Map<UUID, PackageInfo> packageInfo(List<UUID> skuIds) {
    var out = new LinkedHashMap<UUID, PackageInfo>();
    if (skuIds.isEmpty()) return out;
    var placeholders = String.join(",", java.util.Collections.nCopies(skuIds.size(), "?"));
    var binds = new ArrayList<Object>();
    binds.addAll(skuIds);
    for (var record :
        sql.fetch(
            "select sku_id, gross_weight_g, length_mm, width_mm, height_mm from sku_package"
                + " where tenant_id="
                + TENANT
                + " and sku_id in ("
                + placeholders
                + ")",
            binds.toArray())) {
      out.put(
          record.get("sku_id", UUID.class),
          new PackageInfo(
              record.get("gross_weight_g", BigDecimal.class),
              record.get("length_mm", BigDecimal.class),
              record.get("width_mm", BigDecimal.class),
              record.get("height_mm", BigDecimal.class)));
    }
    return out;
  }

  private Map<UUID, PurchaseCost> purchaseCosts(List<UUID> skuIds) {
    var out = new LinkedHashMap<UUID, PurchaseCost>();
    if (skuIds.isEmpty()) return out;
    var placeholders = String.join(",", java.util.Collections.nCopies(skuIds.size(), "?"));
    var binds = new ArrayList<Object>();
    binds.addAll(skuIds);
    for (var record :
        sql.fetch(
            "select p.sku_id, r.purchase_cost, r.currency from sku_cost_profile p"
                + " join sku_cost_revision r on r.tenant_id=p.tenant_id and r.id=p.current_revision_id"
                + " where p.tenant_id="
                + TENANT
                + " and p.sku_id in ("
                + placeholders
                + ")",
            binds.toArray())) {
      out.put(
          record.get("sku_id", UUID.class),
          new PurchaseCost(
              record.get("purchase_cost", BigDecimal.class), record.get("currency", String.class)));
    }
    return out;
  }

  private Map<UUID, SkuMeta> skuMeta(List<UUID> skuIds) {
    var out = new LinkedHashMap<UUID, SkuMeta>();
    if (skuIds.isEmpty()) return out;
    var placeholders = String.join(",", java.util.Collections.nCopies(skuIds.size(), "?"));
    var binds = new ArrayList<Object>();
    binds.addAll(skuIds);
    for (var record :
        sql.fetch(
            "select id, code, name from sku where tenant_id="
                + TENANT
                + " and id in ("
                + placeholders
                + ")",
            binds.toArray())) {
      out.put(
          record.get("id", UUID.class),
          new SkuMeta(record.get("code", String.class), record.get("name", String.class)));
    }
    return out;
  }

  // ================================================================ 内部：人工微调

  /** 一条人工微调。 */
  private record Adjustment(BigDecimal amount, String reason) {}

  private Map<UUID, Adjustment> readAdjustments(
      List<PricingCostAllocationAdjustmentInput> inputs, List<SourceLine> sourceLines) {
    var out = new LinkedHashMap<UUID, Adjustment>();
    if (inputs == null) return out;
    for (var adjustment : inputs) {
      if (adjustment.amount() == null) {
        throw badRequest("人工微调金额必填");
      }
      if (adjustment.reason() == null || adjustment.reason().isBlank()) {
        throw badRequest("人工微调原因必填：没有理由的微调会让「成本为什么突然变了」永远答不上来");
      }
      if (adjustment.amount().signum() == 0) {
        throw badRequest("人工微调金额不能为 0（金额为 0 的微调行没有信息量）");
      }
      boolean known =
          sourceLines.stream().anyMatch(line -> line.skuId().equals(adjustment.skuId()));
      if (!known) {
        throw badRequest("人工微调指向的 SKU 不在这个货件里：" + adjustment.skuId());
      }
      if (out.containsKey(adjustment.skuId())) {
        throw badRequest("同一个 SKU 只能有一条人工微调（要调两次请合成一条并写清原因）");
      }
      out.put(adjustment.skuId(), new Adjustment(adjustment.amount(), adjustment.reason().trim()));
    }
    return out;
  }

  // ================================================================ 内部：分摊与落库

  /** 一行待落库的分摊明细。 */
  private record PersistLine(
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal quantity,
      BigDecimal weightGrams,
      BigDecimal volumeCm3,
      BigDecimal amountBasis,
      boolean basisAvailable,
      String basisNote,
      BigDecimal allocatedAmount,
      BigDecimal adjustmentAmount,
      String adjustmentReason) {}

  /** 把来源行 + 分摊结果 + 人工微调变成待落库的行。 */
  private List<PersistLine> persistLines(
      List<SourceLine> sourceLines,
      CostAllocationRules.AllocationOutcome outcome,
      BasisRule rule,
      Map<UUID, Adjustment> adjustments) {
    var allocatedBySku = new LinkedHashMap<UUID, CostAllocationRules.AllocatedLine>();
    for (var line : outcome.lines()) allocatedBySku.put(line.skuId(), line);

    var rows = new ArrayList<PersistLine>();
    for (var source : sourceLines) {
      var allocated = allocatedBySku.get(source.skuId());
      boolean available = allocated != null && allocated.basisAvailable();
      var adjustment = adjustments.get(source.skuId());
      rows.add(
          new PersistLine(
              source.skuId(),
              source.skuCode(),
              source.skuName(),
              source.quantity(),
              source.weightGrams(),
              source.volumeCm3(),
              source.amountBasis(),
              available,
              noteFor(rule, source, available),
              allocated == null ? BigDecimal.ZERO : allocated.allocatedAmount(),
              adjustment == null ? BigDecimal.ZERO : adjustment.amount(),
              adjustment == null ? "" : adjustment.reason()));
    }
    return rows;
  }

  /** 基数说明：可用时说清「取自哪里」，不可用时说清「为什么没有」。 */
  private String noteFor(BasisRule rule, SourceLine source, boolean available) {
    return switch (rule) {
      case WEIGHT ->
          available
              ? source.weightSource()
              : (source.weightSource() == null
                  ? "货件行没有申报毛重，SKU 包装也没有维护毛重"
                  : source.weightSource());
      case VOLUME ->
          available
              ? source.volumeSource()
              : (source.volumeSource() == null
                  ? "货件行没有申报体积，SKU 包装也没有维护外形尺寸"
                  : source.volumeSource());
      case AMOUNT ->
          available
              ? source.amountSource()
              : (source.amountSource() == null
                  ? "货件行没有申报货值"
                  : source.amountSource());
      case QUANTITY ->
          source.quantity() == null || source.quantity().signum() <= 0
              ? "这一行没有数量"
              : "取自货件行的数量（上游口径；发运行没有计量单位字段，不做单位折算）";
    };
  }

  /** 调分摊。基数全都没有 ⇒ 拒绝分摊（不是摊成全 0）。 */
  private CostAllocationRules.AllocationOutcome allocate(
      BigDecimal totalCost, List<SourceLine> sourceLines, BasisRule rule) {
    var basisLines = new ArrayList<CostAllocationRules.BasisLine>();
    for (var line : sourceLines) {
      // 基数是否可得由**所选规则**决定：重量规则看毛重，金额规则看货值。
      boolean available = basisAvailable(rule, line);
      basisLines.add(
          new CostAllocationRules.BasisLine(
              line.skuId(),
              line.quantity(),
              line.weightGrams(),
              line.volumeCm3(),
              line.amountBasis(),
              available,
              noteFor(rule, line, available)));
    }
    try {
      return CostAllocationRules.allocate(totalCost, basisLines, rule);
    } catch (CostAllocationRules.BasisUnavailableException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
    }
  }

  private boolean basisAvailable(BasisRule rule, SourceLine line) {
    return switch (rule) {
      case WEIGHT -> line.weightGrams() != null && line.weightGrams().signum() > 0;
      case VOLUME -> line.volumeCm3() != null && line.volumeCm3().signum() > 0;
      case AMOUNT -> line.amountBasis() != null && line.amountBasis().signum() > 0;
      case QUANTITY -> line.quantity() != null && line.quantity().signum() > 0;
    };
  }

  /** 追加一个版本（版本号 + 逐行明细）。 */
  private RevisionRef appendRevision(
      AccessService.Access actor,
      UUID allocationId,
      BasisRule rule,
      BigDecimal totalCost,
      String currency,
      OffsetDateTime effectiveFrom,
      String changeReason,
      List<PersistLine> lines) {
    int revisionNo =
        sql.fetchOne(
                "select coalesce(max(revision_no),0)+1 from cost_allocation_revision"
                    + " where tenant_id="
                    + TENANT
                    + " and allocation_id=?",
                allocationId)
            .get(0, Integer.class);
    UUID revisionId = Ids.next();
    try {
      sql.execute(
          "insert into cost_allocation_revision(id,tenant_id,allocation_id,revision_no,basis_rule,"
              + "total_cost,currency,effective_from,change_reason,created_by,created_at)"
              + " values(?,?,?,?,?,?,?,?::timestamptz,?,?,now())",
          revisionId,
          actor.tenantId(),
          allocationId,
          revisionNo,
          rule.name(),
          totalCost,
          currency,
          effectiveFrom,
          changeReason,
          actor.userId());
    } catch (org.springframework.dao.DuplicateKeyException e) {
      // 🔴 版本号撞了（并发写入同一张分摊单）。PostgreSQL 里一条失败语句会**中止整个事务**，
      // 所以**不能**在同一个事务里重试 —— 这里明确报冲突，让调用方重试整个请求。
      throw conflict("分摊版本写入冲突（有人在同时改这张分摊单），请重试");
    }
    for (var line : lines) {
      sql.execute(
          "insert into cost_allocation_revision_line(id,tenant_id,revision_id,allocation_id,sku_id,"
              + "sku_code,sku_name,base_quantity,weight_grams,volume_cm3,amount_basis,"
              + "basis_available,basis_note,allocated_amount,adjustment_amount,adjustment_reason)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          Ids.next(),
          actor.tenantId(),
          revisionId,
          allocationId,
          line.skuId(),
          line.skuCode(),
          line.skuName(),
          line.quantity(),
          line.weightGrams(),
          line.volumeCm3(),
          line.amountBasis(),
          line.basisAvailable(),
          line.basisNote(),
          line.allocatedAmount(),
          line.adjustmentAmount(),
          line.adjustmentReason());
    }
    return new RevisionRef(revisionId, revisionNo);
  }

  private record RevisionRef(UUID id, int revisionNo) {}

  // ================================================================ 内部：读取

  /** 分摊单表头。 */
  private record AllocationHeader(
      UUID id,
      String sourceType,
      String sourceTypeLabel,
      String sourceRef,
      String sourceLabel) {}

  private AllocationHeader requireAllocation(UUID allocationId) {
    var record =
        sql.fetchOne(
            "select id, source_type, source_ref, source_label from cost_allocation"
                + " where tenant_id="
                + TENANT
                + " and id=?",
            allocationId);
    if (record == null) {
      throw notFound("分摊单不存在或不属于本租户：" + allocationId);
    }
    var sourceType = record.get("source_type", String.class);
    var sourceEnum = SourceType.parse(sourceType);
    return new AllocationHeader(
        record.get("id", UUID.class),
        sourceType,
        sourceEnum == null ? sourceType : sourceEnum.label(),
        record.get("source_ref", String.class),
        record.get("source_label", String.class));
  }

  /** 一个版本的聚合（逐行金额现算 ⇒ 合计不落表）。 */
  private record RevisionAggregate(
      UUID id,
      int revisionNo,
      String basisRule,
      BigDecimal totalCost,
      String currency,
      OffsetDateTime effectiveFrom,
      String changeReason,
      OffsetDateTime createdAt,
      int lineCount,
      int eligibleLineCount,
      BigDecimal basisTotal,
      BigDecimal allocatedTotal,
      BigDecimal adjustmentTotal,
      BigDecimal finalTotal) {}

  /** 版本聚合的公共 SELECT 片段（**合计全部现算**，快照里没有合计列）。 */
  private static final String REVISION_AGGREGATE_SQL =
      "select r.id, r.revision_no, r.basis_rule, r.total_cost, r.currency, r.effective_from,"
          + " r.change_reason, r.created_at,"
          + " (select count(*)::int from cost_allocation_revision_line l"
          + "   where l.tenant_id=r.tenant_id and l.revision_id=r.id) as line_count,"
          + " (select count(*)::int from cost_allocation_revision_line l"
          + "   where l.tenant_id=r.tenant_id and l.revision_id=r.id and l.basis_available)"
          + "   as eligible_count,"
          + " coalesce((select sum(case when l.basis_available then"
          + "   case r.basis_rule"
          + "     when 'WEIGHT' then l.weight_grams"
          + "     when 'VOLUME' then l.volume_cm3"
          + "     when 'AMOUNT' then l.amount_basis"
          + "     else l.base_quantity end end)"
          + "   from cost_allocation_revision_line l"
          + "   where l.tenant_id=r.tenant_id and l.revision_id=r.id), 0) as basis_total,"
          + " coalesce((select sum(l.allocated_amount) from cost_allocation_revision_line l"
          + "   where l.tenant_id=r.tenant_id and l.revision_id=r.id), 0) as allocated_total,"
          + " coalesce((select sum(l.adjustment_amount) from cost_allocation_revision_line l"
          + "   where l.tenant_id=r.tenant_id and l.revision_id=r.id), 0) as adjustment_total,"
          + " coalesce((select sum(l.allocated_amount + l.adjustment_amount)"
          + "   from cost_allocation_revision_line l"
          + "   where l.tenant_id=r.tenant_id and l.revision_id=r.id), 0) as final_total"
          + " from cost_allocation_revision r"
          + " where r.tenant_id="
          + TENANT
          + " and r.allocation_id=?";

  private RevisionAggregate toAggregate(Record record) {
    if (record == null) return null;
    return new RevisionAggregate(
        record.get("id", UUID.class),
        record.get("revision_no", Integer.class),
        record.get("basis_rule", String.class),
        record.get("total_cost", BigDecimal.class),
        record.get("currency", String.class),
        record.get("effective_from", OffsetDateTime.class),
        record.get("change_reason", String.class),
        record.get("created_at", OffsetDateTime.class),
        intOrZero(record.get("line_count", Integer.class)),
        intOrZero(record.get("eligible_count", Integer.class)),
        record.get("basis_total", BigDecimal.class),
        record.get("allocated_total", BigDecimal.class),
        record.get("adjustment_total", BigDecimal.class),
        record.get("final_total", BigDecimal.class));
  }

  private RevisionAggregate revisionAggregate(UUID allocationId, int revisionNo) {
    return toAggregate(
        sql.fetchOne(REVISION_AGGREGATE_SQL + " and r.revision_no=?", allocationId, revisionNo));
  }

  private RevisionAggregate currentRevision(UUID allocationId) {
    return toAggregate(
        sql.fetchOne(
            REVISION_AGGREGATE_SQL
                + " and r.id=(select current_revision_id from cost_allocation a"
                + "   where a.tenant_id="
                + TENANT
                + " and a.id=?)",
            allocationId,
            allocationId));
  }

  /** 某个版本的明细行。 */
  private List<PricingCostAllocationLineRow> lines(UUID revisionId, String basisRule) {
    var records =
        sql.fetch(
            "select sku_id, sku_code, sku_name, base_quantity, weight_grams, volume_cm3, amount_basis,"
                + " basis_available, basis_note, allocated_amount, adjustment_amount, adjustment_reason"
                + " from cost_allocation_revision_line"
                + " where tenant_id="
                + TENANT
                + " and revision_id=?"
                + " order by allocated_amount desc, sku_id",
            revisionId);
    var rows = new ArrayList<PricingCostAllocationLineRow>();
    for (var record : records) {
      rows.add(toLineRow(record, basisRule));
    }
    return rows;
  }

  private PricingCostAllocationLineRow toLineRow(Record record, String basisRule) {
    var allocated = record.get("allocated_amount", BigDecimal.class);
    var adjustment = record.get("adjustment_amount", BigDecimal.class);
    var basisValue = basisValueOf(basisRule, record);
    return new PricingCostAllocationLineRow(
        record.get("sku_id", UUID.class),
        record.get("sku_code", String.class),
        record.get("sku_name", String.class),
        record.get("base_quantity", BigDecimal.class),
        record.get("weight_grams", BigDecimal.class),
        record.get("volume_cm3", BigDecimal.class),
        record.get("amount_basis", BigDecimal.class),
        basisValue,
        record.get("basis_available", Boolean.class),
        record.get("basis_note", String.class),
        allocated,
        adjustment,
        record.get("adjustment_reason", String.class),
        CostAllocationRules.finalAmount(allocated, adjustment));
  }

  private List<PricingCostAllocationLineRow> toLineRows(List<PersistLine> persist, BasisRule rule) {
    var rows = new ArrayList<PricingCostAllocationLineRow>();
    for (var line : persist) {
      BigDecimal basisValue =
          switch (rule) {
            case WEIGHT -> line.weightGrams();
            case VOLUME -> line.volumeCm3();
            case AMOUNT -> line.amountBasis();
            case QUANTITY -> line.quantity();
          };
      rows.add(
          new PricingCostAllocationLineRow(
              line.skuId(),
              line.skuCode(),
              line.skuName(),
              line.quantity(),
              line.weightGrams(),
              line.volumeCm3(),
              line.amountBasis(),
              line.basisAvailable() ? basisValue : null,
              line.basisAvailable(),
              line.basisNote(),
              line.allocatedAmount(),
              line.adjustmentAmount(),
              line.adjustmentReason(),
              CostAllocationRules.finalAmount(line.allocatedAmount(), line.adjustmentAmount())));
    }
    rows.sort(
        Comparator.comparing(PricingCostAllocationLineRow::finalAmount, Comparator.reverseOrder())
            .thenComparing(PricingCostAllocationLineRow::skuId));
    return rows;
  }

  private BigDecimal basisValueOf(String basisRule, Record record) {
    BigDecimal value =
        switch (basisRule == null ? "" : basisRule) {
          case "WEIGHT" -> record.get("weight_grams", BigDecimal.class);
          case "VOLUME" -> record.get("volume_cm3", BigDecimal.class);
          case "AMOUNT" -> record.get("amount_basis", BigDecimal.class);
          case "QUANTITY" -> record.get("base_quantity", BigDecimal.class);
          default -> null;
        };
    if (value == null || value.signum() <= 0) return null;
    return value;
  }

  /** 版本历史（新到旧）。一条 SQL 取回全部版本（不逐版再查一次）。 */
  private List<PricingCostAllocationRevisionRow> revisionRows(UUID allocationId) {
    var records =
        sql.fetch(
            REVISION_AGGREGATE_SQL + " order by r.revision_no desc limit " + MAX_REVISIONS,
            allocationId);
    var rows = new ArrayList<PricingCostAllocationRevisionRow>();
    for (var record : records) {
      var aggregate = toAggregate(record);
      if (aggregate == null) continue;
      rows.add(toRevisionRow(aggregate));
    }
    return rows;
  }

  private PricingCostAllocationRevisionRow toRevisionRow(RevisionAggregate aggregate) {
    var rule = BasisRule.parse(aggregate.basisRule());
    return new PricingCostAllocationRevisionRow(
        aggregate.id(),
        aggregate.revisionNo(),
        aggregate.basisRule(),
        rule == null ? aggregate.basisRule() : rule.label(),
        aggregate.totalCost(),
        aggregate.currency(),
        aggregate.lineCount(),
        aggregate.eligibleLineCount(),
        aggregate.basisTotal(),
        aggregate.allocatedTotal(),
        aggregate.adjustmentTotal(),
        aggregate.finalTotal(),
        variance(aggregate.allocatedTotal(), aggregate.adjustmentTotal(), aggregate.totalCost()),
        aggregate.effectiveFrom(),
        aggregate.changeReason(),
        aggregate.createdAt());
  }

  private PricingCostAllocationRevisionRow findRevision(List<PricingCostAllocationRevisionRow> revisions, int revisionNo) {
    for (var row : revisions) {
      if (row.revisionNo() == revisionNo) return row;
    }
    throw notFound("分摊单没有版本 " + revisionNo);
  }

  /** 两版之间的差异。 */
  private PricingCostAllocationDiff diffOf(PricingCostAllocationRevisionRow before, PricingCostAllocationRevisionRow after) {
    var beforeLines = lines(before.revisionId(), before.basisRule());
    var afterLines = lines(after.revisionId(), after.basisRule());
    var rows = CostAllocationRules.diff(beforeLines, afterLines);
    int changed = 0;
    for (var row : rows) {
      if (!"SAME".equals(row.change())) changed++;
    }
    var beforeTotal = nz(before.finalTotal());
    var afterTotal = nz(after.finalTotal());
    return new PricingCostAllocationDiff(
        !java.util.Objects.equals(before.basisRule(), after.basisRule()),
        before.basisRule(),
        after.basisRule(),
        beforeTotal,
        afterTotal,
        afterTotal.subtract(beforeTotal),
        changed,
        rows,
        "规则变更会让**历史利润的解释方式**改变（13 利润读的是当时的版本），"
            + "所以两版都原样留着，谁也没被覆盖。");
  }

  // ================================================================ 内部：工具

  /** 差额 = 逐行最终分摊额之和 − 货件成本总额（人工微调造成的，不是错误）。 */
  private BigDecimal variance(
      BigDecimal allocatedTotal, BigDecimal adjustmentTotal, BigDecimal totalCost) {
    if (allocatedTotal == null || totalCost == null) return null;
    return nz(allocatedTotal)
        .add(nz(adjustmentTotal))
        .subtract(totalCost)
        .setScale(CostAllocationRules.MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /** 逐行求和（kind = allocated / adjustment / final）。 */
  private BigDecimal sumOf(List<PricingCostAllocationLineRow> lines, String kind) {
    BigDecimal total = BigDecimal.ZERO;
    for (var line : lines) {
      total =
          total.add(
              switch (kind) {
                case "allocated" -> nz(line.allocatedAmount());
                case "adjustment" -> nz(line.adjustmentAmount());
                default -> nz(line.finalAmount());
              });
    }
    return total.setScale(CostAllocationRules.MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private PricingCostAllocationSaved saved(
      UUID allocationId,
      RevisionRef revision,
      BasisRule rule,
      List<PersistLine> persist,
      BigDecimal totalCost,
      String currency,
      OffsetDateTime effectiveFrom) {
    BigDecimal allocated = BigDecimal.ZERO;
    BigDecimal adjustment = BigDecimal.ZERO;
    for (var line : persist) {
      allocated = allocated.add(nz(line.allocatedAmount()));
      adjustment = adjustment.add(nz(line.adjustmentAmount()));
    }
    allocated = allocated.setScale(CostAllocationRules.MONEY_SCALE, RoundingMode.HALF_UP);
    adjustment = adjustment.setScale(CostAllocationRules.MONEY_SCALE, RoundingMode.HALF_UP);
    var finalTotal = allocated.add(adjustment);
    var version =
        sql.fetchOne(
                "select version from cost_allocation where tenant_id=" + TENANT + " and id=?",
                allocationId)
            .get(0, Long.class);
    return new PricingCostAllocationSaved(
        allocationId,
        revision.id(),
        revision.revisionNo(),
        rule == null ? null : rule.name(),
        totalCost,
        currency,
        persist.size(),
        allocated,
        finalTotal,
        finalTotal.subtract(totalCost).setScale(CostAllocationRules.MONEY_SCALE, RoundingMode.HALF_UP),
        effectiveFrom,
        version);
  }

  private long count(String sqlText, Object... binds) {
    return sql.fetchOne(sqlText, binds).get(0, Long.class);
  }

  private static int intOrZero(Integer value) {
    return value == null ? 0 : value;
  }

  /** 克 → 千克（人工货件的行重量是克，上游是千克）。 */
  private static BigDecimal kg(BigDecimal grams) {
    if (grams == null) return null;
    return grams.divide(GRAMS_PER_KG, 6, RoundingMode.HALF_UP);
  }

  /** 立方厘米 → 立方米。 */
  private static BigDecimal cbm(BigDecimal cm3) {
    if (cm3 == null) return null;
    return cm3.divide(CM3_PER_CBM, 6, RoundingMode.HALF_UP);
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
