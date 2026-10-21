package com.starcon.trade;

import static com.starcon.trade.DevelopmentSampleModels.*;
import static com.starcon.trade.DevelopmentSampleRules.*;

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
 * 6.6.1.2 打样与样品管理。
 *
 * <p>蓝图原话：「管理打样与样品：样品申请、样品来源（供应商/自有产）、样品物流、确认结果（通过/修改/放弃）、
 * 样品成本；与达人寄样（14.5.1.2）区分打样与营销样品。」输入「打样申请；供应商/工厂；样品信息与图片」。
 * 输出「打样单 + 样品台账 + 确认记录；样品成本」。
 *
 * <p><b>本模块只做一件事：把「这一轮要打一个样」变成一份有凭据、状态受控、成本可查的打样单与样品台账。</b>
 *
 * <p><b>🔴 「打样单」与「样品台账」的基数</b>：打样单是**一次打样活动**，
 * 样品台账是**样品实体**（一个样品一件）—— <b>1 张打样单 : N 件样品</b>。
 * 两者的归属关系是 {@code development_sample.order_id → development_sample_order.id}
 * （本模块自己的表 ⇒ 正常外键）。**不把两者糊进一张表**：糊进去就没法回答
 * 「这张单打了几个样、其中几个确认通过」，而这正是打样确认要回答的问题。
 *
 * <p><b>十二条实现纪律</b>：
 *
 * <ol>
 *   <li><b>状态机在服务层 + 数据库层两处钉死</b>：Java 侧
 *       {@link DevelopmentSampleRules#allowedOrderTargets} / {@code allowedSampleTargets}，
 *       DB 侧 V261 迁移的 {@code guard_development_sample_order_status()} 与
 *       {@code guard_development_sample_status()}。客户端**传什么状态不算数**。</li>
 *   <li><b>确认结果（通过 / 修改 / 放弃）必须有一条不可改写的确认记录</b>：
 *       确认类状态走 {@code POST .../confirmations}，服务层先落记录再改状态，
 *       DB 守卫再挡一道。「改了状态却没有确认记录」在数据库层就做不到。</li>
 *   <li><b>「修改」不是终态</b>：{@code REVISING} 可以回到 {@code MAKING} 重新打样，
 *       也可以直接 {@code CONFIRMED}（改完就通过）。「已确认 / 已放弃」才是终态。</li>
 *   <li><b>打样 vs 达人寄样</b>：来源（供应商 / 自有产 / 档口）与用途（**只有开发打样**）
 *       是**两个维度**。用途词表里**没有「其它」** —— 达人寄样（14.5.1.2，14 卷）
 *       是营销样品，不在本模块；混进来会污染开发成本。</li>
 *   <li><b>开发立项（6.6.1.1）只读引用 + 展示快照</b>：存 {@code development_project_id}
 *       + {@code project_no} / {@code project_name} 快照，**零外键、零 ALTER**。</li>
 *   <li><b>档口（6.1.2.1）只读引用 + 展示快照</b>：同一个 trade 库，
 *       但它的事实归 6.1.2.1 所有 ⇒ **只 SELECT**，不建外键、不加约束。</li>
 *   <li><b>供应商（catalog 独立库）逻辑引用 + 快照</b>：{@code supplier_ref_id} +
 *       {@code supplier_code} / {@code supplier_name}，**无跨库 FK**；
 *       走 {@link SupplierPartyQueryPort} 读 catalog 的授权只读端点。</li>
 *   <li><b>样品图片（catalog 媒体库）逻辑引用 + 快照</b>：{@code media_ref_id} + 元数据快照，
 *       **无跨库 FK、不跨库写**；走 {@link SampleMediaQueryPort}。</li>
 *   <li><b>物流只记快照</b>：真实物流轨迹在 10 卷，本模块**没有**可读来源 ⇒
 *       {@code tracking_available=false} + {@code tracking_owner_module='10 物流'}。
 *       **不硬造一个「物流已接入」的假接口。**</li>
 *   <li><b>成本落本库，但「入账」如实标不可用</b>：12 费用（finance 服务）**在本仓不存在**
 *       ⇒ {@code expense_posted=false} + {@code expense_owner_module='12 费用'}。
 *       **不假装已经入账**，也**不造一张「费用」表**去冒充 12 财务的交付物。</li>
 *   <li><b>一张打样单一个币种</b>：成本明细行的币种必须与成本头一致（DB 触发器钉死），
 *       不同币种的数绝不相加。</li>
 *   <li><b>时间一律 UTC 口径</b>：{@code timestamptz} 列用 {@link OffsetDateTime} 绑定，
 *       values 位置写 {@code ?::timestamptz}。</li>
 * </ol>
 */
@Service
public class DevelopmentSampleService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 进费用归集做不到时点名的归管模块（12 费用，finance 服务在本仓不存在）。 */
  static final String EXPENSE_OWNER_MODULE = "12 费用";

  /** 真实物流轨迹取不到时点名的归管模块（10 物流）。 */
  static final String LOGISTICS_OWNER_MODULE = "10 物流";

  /** 试产衔接未交付时点名的归管模块。 */
  static final String PILOT_OWNER_MODULE = "6.6.2.1 试产单";

  /** 达人寄样归管模块（14.5.1.2，14 卷）—— 本模块**不**代管。 */
  static final String MARKETING_OWNER_MODULE = "14.5.1.2 达人寄样";

  /**
   * 留痕里的「操作人姓名」占位。
   *
   * <p>🔴 本服务**拿不到**操作人的显示名：{@code AccessService.Access} 只有 userId / tenantId /
   * membershipId / departmentId，显示名在 identity 服务里。**不去猜、不去编** ——
   * 留痕的**主体是 actor_id**（谁做的），姓名留空比编一个强。
   */
  static final String ACTOR_NAME_UNKNOWN = "";

  private final DSLContext sql;
  private final SupplierPartyQueryPort suppliers;
  private final SampleMediaQueryPort media;

  public DevelopmentSampleService(
      DSLContext sql, SupplierPartyQueryPort suppliers, SampleMediaQueryPort media) {
    this.sql = sql;
    this.suppliers = suppliers;
    this.media = media;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：开发立项（6.6.1.1）与档口（6.1.2.1）**同库已交付** ⇒ 可用；
   * 供应商与媒体库在 catalog **已交付** ⇒ 可用；而**进费用归集（12 财务）**、
   * **试产衔接（6.6.2.1）**、**真实物流轨迹（10 物流）**三件事**做不到** ——
   * 哪一个接通了必须由接口自己报出。
   */
  public DevelopmentSampleCapabilities capabilities() {
    return new DevelopmentSampleCapabilities(
        true,
        "6.6.1.1 开发立项（trade 服务，同库，已交付）",
        "打样单挂到开发立项上：**只读 + 逻辑引用 + 展示快照**（development_project_id + project_no / project_name），"
            + "**不建外键、不加约束、不改它的触发器** —— 立项的事实归 6.6.1.1 所有。"
            + "已终止的立项不再接受新打样单。",
        true,
        "6.1.2.1 档口与货源池（trade 服务，同库，已交付）",
        "档口是**只读引用 + 快照**（stall_id + stall_code / stall_name）："
            + "**只 SELECT，不建指向它的外键、不加约束、不 ALTER** —— 档口的事实归 6.1.2.1 所有。",
        true,
        "catalog 的 6.1.1.x 供应商列表（GET /catalog/supply/vendors）",
        "supplier_ref_id 是**逻辑引用**，**不建跨库 FK**；走 trade 已有的 SupplierPartyQueryPort "
            + "读 catalog 的授权只读端点，**快照取自 catalog**（不采信客户端自报的供应商名）。",
        true,
        "catalog 的 3.4.1.1 媒体库（GET /catalog/media）",
        "media_ref_id 是**逻辑引用**，**不建跨库 FK、不跨库写** —— media_* 表的唯一写入所有者是 catalog；"
            + "本库只存一份元数据快照，catalog 改名 / 换存储键后仍能显示当时挂的是哪一张。",
        false,
        EXPENSE_OWNER_MODULE,
        "🔴 蓝图加粗句「打样费用是新品开发的真实成本，需进费用归集，否则新品利润被高估」——"
            + "本模块**如实记下成本事实**（成本头 + 明细行，落在 trade 本库），"
            + "但**进费用归集 / 入账这一步做不到**：12 费用（finance 服务）**在本仓不存在**"
            + "（backend/services/ 下只有 catalog / channel / crm / identity / trade / warehouse）。"
            + "⇒ expense_posted 恒为 false（数据库 CHECK 钉死），ownerModule 点名 '12 费用'。"
            + "**不假装已经入账**，也**不造一张「费用」表**去冒充 12 财务的交付物。",
        false,
        LOGISTICS_OWNER_MODULE,
        "🔴 样品物流**没有现成的上游**：物流在 10 卷（channel + trade + warehouse），"
            + "本模块**只记物流快照**（发货单号 / 承运商 / 物流状态 / 发出时刻），"
            + "**真实物流轨迹取不到** ⇒ tracking_available 恒为 false + ownerModule 点名 '10 物流'。"
            + "**不硬造一个「物流已接入」的假接口。**",
        false,
        PILOT_OWNER_MODULE,
        "6.6.2.1 试产单**未交付** ⇒ 本模块**不代它建单**，只在本模块的样品台账里保留"
            + "「已确认」的样品事实，待其交付后由下游消费。",
        false,
        MARKETING_OWNER_MODULE,
        "🔴 达人寄样（14.5.1.2）属 **14 卷**（channel + reporting），是**营销样品，不是本模块的范围**。"
            + "它同样来自供应商，光看「来源」分不出来 —— 所以本模块另有「用途」维度，"
            + "词表里**只有「开发打样」一个值**，数据库 CHECK 在存储层拒绝其它用途。"
            + "**不提供「其它」桶**：留一个「其它」就等于把营销寄样塞进来，"
            + "而那正是蓝图担心的「营销费用污染开发成本 ⇒ 新品利润被高估」。",
        "🔴 **来源 / 用途是两个维度，缺一不可**：来源说「这个样品从哪来」"
            + "（供应商打样 / 自有产打样 / 档口打样），用途说「它是干什么的」（开发打样）。"
            + "达人寄样靠**用途**排除，不靠来源。",
        "打样单（一次打样活动）单向推进：已申请 → 进行中 → 已完成 → 已关闭；"
            + "任意非终态可已取消。**已关闭 / 已取消是终态，不提供「恢复」**。"
            + "标记为「已完成」前，该单下的样品必须全部落到终态（已确认 / 已放弃）。",
        "样品（一个样品一件）单向推进：已申请 → 打样中 → 已发出 → 已到样；"
            + "到样后由**确认记录**驱动：已确认（通过）/ 修改中（修改）/ 已放弃（放弃）。"
            + "**修改中不是终态**（可重新打样，也可改完直接通过）；**已确认 / 已放弃是终态**。",
        "样品成本 = 成本头（一个打样单一张、**一个币种**）+ 明细行（打样费 / 物料费 / 模具费 / 物流费 / 检品费，"
            + "**封闭词表无「其它」**）。合计金额由数据库触发器按明细行自动维护，"
            + "**不同币种的数绝不相加**。成本**只在本模块记事实**，不代 12 费用入账。",
        "本模块的职责边界：建打样单 → 登记样品台账 → 推进制作进度 → 确认结果（通过 / 修改 / 放弃）"
            + "→ 记物流快照与图片 → 记样品成本。"
            + "**不**建 SKU、**不**建试产单、**不**代 12 费用入账、**不**管达人寄样、"
            + "**不**写 catalog / channel / warehouse 的表、**不**改 6.6.1.1 与 6.1.2.1 的任何一行。");
  }

  /** 建单参照项（下拉）。 */
  public DevelopmentSampleOptions options() {
    List<DevelopmentSampleSourceOption> sources = new ArrayList<>();
    for (DevelopmentSampleSourceType type : DevelopmentSampleSourceType.values()) {
      sources.add(new DevelopmentSampleSourceOption(type.name(), type.label()));
    }
    List<DevelopmentSampleUsageOption> usages = new ArrayList<>();
    for (DevelopmentSampleUsageType type : DevelopmentSampleUsageType.values()) {
      usages.add(new DevelopmentSampleUsageOption(type.name(), type.label()));
    }
    List<DevelopmentSampleOrderStatusOption> orderStatuses = new ArrayList<>();
    for (DevelopmentSampleOrderStatus status : DevelopmentSampleOrderStatus.values()) {
      orderStatuses.add(
          new DevelopmentSampleOrderStatusOption(
              status.name(),
              status.label(),
              isTerminalOrderStatus(status),
              allowedOrderTargets(status).stream().map(Enum::name).toList()));
    }
    List<DevelopmentSampleStatusOption> sampleStatuses = new ArrayList<>();
    for (DevelopmentSampleStatus status : DevelopmentSampleStatus.values()) {
      sampleStatuses.add(
          new DevelopmentSampleStatusOption(
              status.name(),
              status.label(),
              isTerminalSampleStatus(status),
              allowedSampleTargets(status).stream().map(Enum::name).toList()));
    }
    List<DevelopmentSampleConfirmationOption> results = new ArrayList<>();
    for (DevelopmentSampleConfirmationResult result : DevelopmentSampleConfirmationResult.values()) {
      results.add(new DevelopmentSampleConfirmationOption(result.name(), result.label()));
    }
    List<DevelopmentSampleCostTypeOption> costTypes = new ArrayList<>();
    for (DevelopmentSampleCostType type : DevelopmentSampleCostType.values()) {
      costTypes.add(new DevelopmentSampleCostTypeOption(type.name(), type.label()));
    }
    List<DevelopmentSampleLogisticsOption> logistics = new ArrayList<>();
    for (DevelopmentSampleLogisticsStatus status : DevelopmentSampleLogisticsStatus.values()) {
      logistics.add(new DevelopmentSampleLogisticsOption(status.name(), status.label()));
    }
    return new DevelopmentSampleOptions(
        sources, usages, orderStatuses, sampleStatuses, results, costTypes, logistics);
  }

  // ================================================================ 概览

  /** 按状态分组的概览。 */
  public DevelopmentSampleSummary summary() {
    UUID tenantId = currentTenant();
    long totalOrders =
        sql.fetchOne(
                "select count(*) from development_sample_order where tenant_id = ?::uuid", tenantId)
            .get(0, Long.class);
    long totalSamples =
        sql.fetchOne(
                "select count(*) from development_sample where tenant_id = ?::uuid", tenantId)
            .get(0, Long.class);
    Map<String, Long> orderCounts = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select status, count(*) as c from development_sample_order where tenant_id = ?::uuid"
                + " group by status",
            tenantId)) {
      orderCounts.put(row.get("status", String.class), row.get("c", Long.class));
    }
    List<DevelopmentSampleOrderStatusCount> orderStatuses = new ArrayList<>();
    for (DevelopmentSampleOrderStatus status : DevelopmentSampleOrderStatus.values()) {
      orderStatuses.add(
          new DevelopmentSampleOrderStatusCount(
              status.name(), status.label(), orderCounts.getOrDefault(status.name(), 0L)));
    }
    Map<String, Long> sampleCounts = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select status, count(*) as c from development_sample where tenant_id = ?::uuid"
                + " group by status",
            tenantId)) {
      sampleCounts.put(row.get("status", String.class), row.get("c", Long.class));
    }
    List<DevelopmentSampleStatusCount> sampleStatuses = new ArrayList<>();
    for (DevelopmentSampleStatus status : DevelopmentSampleStatus.values()) {
      sampleStatuses.add(
          new DevelopmentSampleStatusCount(
              status.name(), status.label(), sampleCounts.getOrDefault(status.name(), 0L)));
    }
    // 🔴 成本合计：**一张单一个币种** ⇒ 跨币种绝不相加。
    //    这里**按币种分组**返回，界面上不给一个把不同币种加起来的「总数」。
    List<DevelopmentSampleCostCurrencyTotal> costByCurrency = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select currency, sum(total_amount) as t, count(*) as c from development_sample_cost"
                + " where tenant_id = ?::uuid group by currency order by currency",
            tenantId)) {
      costByCurrency.add(
          new DevelopmentSampleCostCurrencyTotal(
              row.get("currency", String.class),
              row.get("t", BigDecimal.class),
              row.get("c", Long.class)));
    }
    String costCurrency = costByCurrency.isEmpty() ? "" : costByCurrency.get(0).currency();
    BigDecimal totalCost =
        costByCurrency.isEmpty() ? BigDecimal.ZERO : costByCurrency.get(0).totalAmount();
    List<DevelopmentSampleOrderRow> recent = new ArrayList<>();
    for (Record row :
        sql.fetch(
            ORDER_SELECT + " where o.tenant_id = ?::uuid order by o.created_at desc, o.id desc"
                + " limit 10",
            tenantId)) {
      recent.add(orderRow(row));
    }
    return new DevelopmentSampleSummary(
        totalOrders,
        totalSamples,
        totalCost,
        costCurrency,
        costByCurrency,
        orderStatuses,
        sampleStatuses,
        recent);
  }

  // ================================================================ 打样单清单

  /** 打样单清单（真分页 + 真筛选）。 */
  public DevelopmentSampleOrderPage listOrders(
      String status,
      String sourceType,
      UUID developmentProjectId,
      String keyword,
      Integer page,
      Integer size) {
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
    if (sourceType != null && !sourceType.isBlank()) {
      where.add("o.source_type = ?::varchar");
      binds.add(sourceType.trim());
    }
    if (developmentProjectId != null) {
      where.add("o.development_project_id = ?::uuid");
      binds.add(developmentProjectId);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.add(
          "(o.order_no ilike ?::varchar or o.project_no ilike ?::varchar"
              + " or o.project_name ilike ?::varchar or o.supplier_name ilike ?::varchar"
              + " or o.stall_name ilike ?::varchar)");
      String like = "%" + keyword.trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    String clause = String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from development_sample_order o where " + clause, binds.toArray())
            .get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            ORDER_SELECT + " where " + clause + " order by o.created_at desc, o.id desc"
                + " limit ? offset ?",
            append(binds, pageSize, (long) pageNo * pageSize).toArray());
    List<DevelopmentSampleOrderRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(orderRow(row));
    }
    return new DevelopmentSampleOrderPage(items, total, pageNo, pageSize);
  }

  // ================================================================ 样品台账（跨单）

  /** 样品台账清单（真分页 + 真筛选）。 */
  public DevelopmentSamplePage listSamples(
      String status,
      String sourceType,
      UUID developmentProjectId,
      UUID orderId,
      String keyword,
      Integer page,
      Integer size) {
    int pageNo = page == null || page < 0 ? 0 : page;
    int pageSize = pageSize(size);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("s.tenant_id = ?::uuid");
    binds.add(currentTenant());
    if (status != null && !status.isBlank()) {
      where.add("s.status = ?::varchar");
      binds.add(status.trim());
    }
    if (sourceType != null && !sourceType.isBlank()) {
      where.add("s.source_type = ?::varchar");
      binds.add(sourceType.trim());
    }
    if (developmentProjectId != null) {
      where.add("s.development_project_id = ?::uuid");
      binds.add(developmentProjectId);
    }
    if (orderId != null) {
      where.add("s.order_id = ?::uuid");
      binds.add(orderId);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.add(
          "(s.sample_no ilike ?::varchar or s.spec_name ilike ?::varchar"
              + " or s.spec_value ilike ?::varchar or s.project_no ilike ?::varchar"
              + " or s.project_name ilike ?::varchar)");
      String like = "%" + keyword.trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    String clause = String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from development_sample s where " + clause, binds.toArray())
            .get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            SAMPLE_SELECT + " where " + clause + " order by s.created_at desc, s.id desc"
                + " limit ? offset ?",
            append(binds, pageSize, (long) pageNo * pageSize).toArray());
    List<DevelopmentSampleRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(sampleRow(row));
    }
    return new DevelopmentSamplePage(items, total, pageNo, pageSize);
  }

  // ================================================================ 打样单

  /** 打样单详情：打样单 + 样品台账 + 迁移历史 + 成本。 */
  public DevelopmentSampleOrderDetail orderDetail(UUID orderId) {
    DevelopmentSampleOrderRow order = orderRow(requireOrder(orderId));
    return new DevelopmentSampleOrderDetail(
        order,
        listSamplesOfOrder(orderId),
        listOrderTransitions(orderId),
        costView(orderId),
        allowedOrderTargets(DevelopmentSampleOrderStatus.valueOf(order.status())).stream()
            .map(Enum::name)
            .toList());
  }

  /** 建打样单。初始状态恒为「已申请」。 */
  @Transactional
  public DevelopmentSampleOrderDetail createOrder(
      DevelopmentSampleOrderCreateInput input, UUID actorId, String bearer) {
    UUID tenantId = currentTenant();
    String orderNo = input.orderNo().trim();
    var exists =
        sql.fetchOne(
            "select id from development_sample_order where tenant_id = ?::uuid"
                + " and order_no = ?::varchar",
            tenantId,
            orderNo);
    if (exists != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "打样单号已存在：" + orderNo + "（单号在租户内唯一）");
    }
    requireUsageType(input.usageType());
    String currency = normalizeCurrency(input.currency());
    requireSourceConsistency(input.sourceType(), input.supplierRefId(), input.stallId());

    // 上游 6.6.1.1 开发立项：**只读引用 + 快照**（零外键、零 ALTER）。
    ProjectSnapshot project = requireProjectSnapshot(input.developmentProjectId());
    if ("TERMINATED".equals(project.status())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "开发立项「" + project.projectNo() + "」已终止，不再接受新打样单 —— 终止是终态，不提供恢复");
    }
    // 上游 6.1.2.1 档口：**只读引用 + 快照**（只 SELECT，不建外键、不加约束）。
    StallSnapshot stall =
        input.stallId() == null ? null : requireStallSnapshot(input.stallId());
    // 上游 catalog 供应商：**逻辑引用 + 快照**（快照取自 catalog，不采信客户端自报的名字）。
    SupplierSnapshot supplier =
        input.supplierRefId() == null ? null : requireSupplierSnapshot(input.supplierRefId(), bearer);

    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into development_sample_order(id,tenant_id,order_no,development_project_id,"
            + " project_no,project_name,source_type,usage_type,supplier_ref_id,supplier_code,"
            + " supplier_name,stall_id,stall_code,stall_name,status,currency,requester_id,"
            + " requester_name,requested_at,expected_delivery_at,purpose_note,note,created_by,"
            + " created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::varchar,?::uuid,?::varchar,?::varchar,?::varchar,?::varchar,"
            + " ?::uuid,?::varchar,?::varchar,?::uuid,?::varchar,?::varchar,'REQUESTED',?::varchar,"
            + " ?::uuid,?::varchar,?::timestamptz,?::timestamptz,?::varchar,?::varchar,?::uuid,"
            + " now(),now())",
        id,
        tenantId,
        orderNo,
        input.developmentProjectId(),
        project.projectNo(),
        project.name(),
        input.sourceType().name(),
        input.usageType().name(),
        supplier == null ? null : supplier.refId(),
        supplier == null ? "" : supplier.code(),
        supplier == null ? "" : supplier.name(),
        stall == null ? null : stall.id(),
        stall == null ? "" : stall.code(),
        stall == null ? "" : stall.name(),
        currency,
        input.requesterId(),
        blankToEmpty(input.requesterName()),
        input.requestedAt(),
        input.expectedDeliveryAt(),
        blankToEmpty(input.purposeNote()),
        blankToEmpty(input.note()),
        actorId);
    return orderDetail(id);
  }

  /** 改打样单（**整体替换**语义：请求体就是期望的最终状态，null 表示「清空」）。 */
  @Transactional
  public DevelopmentSampleOrderDetail updateOrder(UUID orderId, DevelopmentSampleOrderUpdateInput input) {
    requireOrder(orderId);
    requireUsageType(input.usageType());
    String currency = normalizeCurrency(input.currency());
    requireSourceConsistency(input.sourceType(), input.supplierRefId(), input.stallId());

    String supplierCode = blankToEmpty(input.supplierCode());
    String supplierName = blankToEmpty(input.supplierName());
    if (input.supplierRefId() != null && supplierName.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "挂了供应商就必须有供应商名称快照（只挂一个裸 id，界面上显示什么？）");
    }
    String stallCode = blankToEmpty(input.stallCode());
    String stallName = blankToEmpty(input.stallName());
    if (input.stallId() != null && stallName.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "挂了档口就必须有档口名称快照（只挂一个裸 id，界面上显示什么？）");
    }
    sql.execute(
        "update development_sample_order set source_type = ?::varchar, usage_type = ?::varchar,"
            + " supplier_ref_id = ?::uuid, supplier_code = ?::varchar, supplier_name = ?::varchar,"
            + " stall_id = ?::uuid, stall_code = ?::varchar, stall_name = ?::varchar,"
            + " currency = ?::varchar, requester_id = ?::uuid, requester_name = ?::varchar,"
            + " expected_delivery_at = ?::timestamptz, purpose_note = ?::varchar, note = ?::varchar,"
            + " updated_at = now() where tenant_id = ?::uuid and id = ?::uuid",
        input.sourceType().name(),
        input.usageType().name(),
        input.supplierRefId(),
        supplierCode,
        supplierName,
        input.stallId(),
        stallCode,
        stallName,
        currency,
        input.requesterId(),
        blankToEmpty(input.requesterName()),
        input.expectedDeliveryAt(),
        blankToEmpty(input.purposeNote()),
        blankToEmpty(input.note()),
        currentTenant(),
        orderId);
    // 币种变了 ⇒ 样品与成本明细行的币种也必须跟着走（一张单一个币种）。
    syncOrderCurrency(orderId, currency);
    return orderDetail(orderId);
  }

  /**
   * 推进打样单状态。
   *
   * <p>🔴 **事件先落、状态后改**：迁移事件先 insert（不可改写），再 update 状态 ——
   * 数据库守卫 {@code guard_development_sample_order_status()} 会检查「有没有对应事件」，
   * 没有就**直接拒绝这次状态变更**。
   */
  @Transactional
  public DevelopmentSampleOrderDetail transitionOrder(
      UUID orderId, DevelopmentSampleOrderTransitionInput input, UUID actorId) {
    DevelopmentSampleOrderStatus from =
        DevelopmentSampleOrderStatus.valueOf(requireOrder(orderId).get("status", String.class));
    DevelopmentSampleOrderStatus to = input.toStatus();
    requireOrderTransition(from, to);

    UUID tenantId = currentTenant();
    OffsetDateTime occurredAt =
        input.occurredAt() == null ? OffsetDateTime.now() : input.occurredAt();
    sql.execute(
        "insert into development_sample_order_transition(id,tenant_id,order_id,from_status,"
            + " to_status,reason,actor_id,actor_name,occurred_at,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::uuid,?::varchar,"
            + " ?::timestamptz,now())",
        UUID.randomUUID(),
        tenantId,
        orderId,
        from.name(),
        to.name(),
        input.reason().trim(),
        actorId,
        ACTOR_NAME_UNKNOWN,
        occurredAt);
    sql.execute(
        "update development_sample_order set status = ?::varchar, updated_at = now()"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        to.name(),
        tenantId,
        orderId);
    return orderDetail(orderId);
  }

  // ================================================================ 样品

  /** 某个打样单下的样品台账。 */
  public List<DevelopmentSampleRow> listSamplesOfOrder(UUID orderId) {
    requireOrder(orderId);
    List<DevelopmentSampleRow> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            SAMPLE_SELECT + " where s.tenant_id = ?::uuid and s.order_id = ?::uuid"
                + " order by s.created_at, s.id",
            currentTenant(),
            orderId)) {
      items.add(sampleRow(row));
    }
    return items;
  }

  /** 样品详情：样品 + 进度事件 + 确认记录 + 物流快照 + 图片。 */
  public DevelopmentSampleDetail sampleDetail(UUID orderId, UUID sampleId) {
    DevelopmentSampleRow sample = sampleRow(requireSample(orderId, sampleId));
    return new DevelopmentSampleDetail(
        sample,
        listSampleTransitions(sampleId),
        listConfirmations(sampleId),
        listShipments(sampleId),
        listMedia(sampleId),
        allowedSampleTargets(DevelopmentSampleStatus.valueOf(sample.status())).stream()
            .map(Enum::name)
            .toList());
  }

  /** 登记样品（一个样品一件）。 */
  @Transactional
  public DevelopmentSampleDetail createSample(
      UUID orderId, DevelopmentSampleCreateInput input, UUID actorId) {
    Record order = requireOrder(orderId);
    UUID tenantId = currentTenant();
    String sampleNo = input.sampleNo().trim();
    var exists =
        sql.fetchOne(
            "select id from development_sample where tenant_id = ?::uuid and sample_no = ?::varchar",
            tenantId,
            sampleNo);
    if (exists != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "样品编号已存在：" + sampleNo + "（编号在租户内唯一）");
    }
    requirePositiveQuantity(input.quantity());
    sql.execute(
        "insert into development_sample(id,tenant_id,sample_no,order_id,development_project_id,"
            + " project_no,project_name,source_type,usage_type,status,spec_name,spec_value,quantity,"
            + " currency,revision_round,remark,created_by,created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::varchar,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,"
            + " ?::varchar,'REQUESTED',?::varchar,?::varchar,?::numeric,?::varchar,1,?::varchar,"
            + " ?::uuid,now(),now())",
        UUID.randomUUID(),
        tenantId,
        sampleNo,
        orderId,
        order.get("development_project_id", UUID.class),
        order.get("project_no", String.class),
        order.get("project_name", String.class),
        order.get("source_type", String.class),
        order.get("usage_type", String.class),
        blankToEmpty(input.specName()),
        blankToEmpty(input.specValue()),
        input.quantity(),
        order.get("currency", String.class),
        blankToEmpty(input.remark()),
        actorId);
    return sampleDetail(orderId, requireSampleByNo(tenantId, sampleNo));
  }

  /** 改样品（**整体替换**语义：规格 / 数量 / 备注）。 */
  @Transactional
  public DevelopmentSampleDetail updateSample(
      UUID orderId, UUID sampleId, DevelopmentSampleUpdateInput input) {
    requireSample(orderId, sampleId);
    requirePositiveQuantity(input.quantity());
    sql.execute(
        "update development_sample set spec_name = ?::varchar, spec_value = ?::varchar,"
            + " quantity = ?::numeric, remark = ?::varchar, updated_at = now()"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        blankToEmpty(input.specName()),
        blankToEmpty(input.specValue()),
        input.quantity(),
        blankToEmpty(input.remark()),
        currentTenant(),
        sampleId);
    return sampleDetail(orderId, sampleId);
  }

  /**
   * 推进样品<b>制作进度</b>（已申请 → 打样中 → 已发出 → 已到样；修改中 → 重新打样）。
   *
   * <p>🔴 **确认类状态（已确认 / 修改中 / 已放弃）不走这个端点** —— 它们必须带一条
   * **确认记录**，走 {@code POST .../confirmations}。
   */
  @Transactional
  public DevelopmentSampleDetail transitionSample(
      UUID orderId,
      UUID sampleId,
      DevelopmentSampleTransitionInput input,
      UUID actorId) {
    DevelopmentSampleStatus from =
        DevelopmentSampleStatus.valueOf(requireSample(orderId, sampleId).get("status", String.class));
    DevelopmentSampleStatus to = input.toStatus();
    if (isConfirmationEdge(to)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "「"
              + to.label()
              + "」是**确认结果**（通过 / 修改 / 放弃），必须走确认端点留一条不可改写的确认记录，"
              + "不能当作制作进度直接推进");
    }
    requireSampleTransition(from, to);

    UUID tenantId = currentTenant();
    OffsetDateTime occurredAt =
        input.occurredAt() == null ? OffsetDateTime.now() : input.occurredAt();
    sql.execute(
        "insert into development_sample_transition(id,tenant_id,sample_id,from_status,to_status,"
            + " reason,actor_id,actor_name,occurred_at,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::uuid,?::varchar,"
            + " ?::timestamptz,now())",
        UUID.randomUUID(),
        tenantId,
        sampleId,
        from.name(),
        to.name(),
        input.reason().trim(),
        actorId,
        ACTOR_NAME_UNKNOWN,
        occurredAt);
    // 「修改中 → 重新打样」⇒ 确认轮次 +1（这一轮是第几轮打样）。
    // 🔴 只在**重新**打样时 +1：第一次 REQUESTED → MAKING 也是「打样」，
    //    但它是**第一轮**，不该被当成第二轮。
    if (from == DevelopmentSampleStatus.REVISING && to == DevelopmentSampleStatus.MAKING) {
      sql.execute(
          "update development_sample set revision_round = revision_round + 1, updated_at = now()"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          tenantId,
          sampleId);
    }
    sql.execute(
        "update development_sample set status = ?::varchar, updated_at = now()"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        to.name(),
        tenantId,
        sampleId);
    return sampleDetail(orderId, sampleId);
  }

  // ================================================================ 确认记录

  /** 确认记录清单。 */
  public List<DevelopmentSampleConfirmationRow> listConfirmations(UUID sampleId) {
    List<DevelopmentSampleConfirmationRow> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id,sample_id,result,confirm_seq,reason,issue_note,actor_id,actor_name,"
                + " confirmed_at,applied_at from development_sample_confirmation"
                + " where tenant_id = ?::uuid and sample_id = ?::uuid"
                + " order by confirmed_at desc, id desc",
            currentTenant(),
            sampleId)) {
      items.add(confirmationRow(row));
    }
    return items;
  }

  /**
   * 记一条确认结果（通过 / 修改 / 放弃），并把样品推进到对应状态。
   *
   * <p>🔴 **记录先落、状态后改**：数据库守卫 {@code guard_development_sample_status()}
   * 会检查「有没有对应的确认记录」，没有就**直接拒绝这次状态变更**。
   */
  @Transactional
  public DevelopmentSampleDetail confirmSample(
      UUID orderId,
      UUID sampleId,
      DevelopmentSampleConfirmationInput input,
      UUID actorId) {
    DevelopmentSampleStatus from =
        DevelopmentSampleStatus.valueOf(requireSample(orderId, sampleId).get("status", String.class));
    requireConfirmation(input.result(), input.reason(), input.issueNote());
    DevelopmentSampleStatus to = targetStatusFor(input.result());
    requireSampleTransition(from, to);

    UUID tenantId = currentTenant();
    OffsetDateTime confirmedAt =
        input.confirmedAt() == null ? OffsetDateTime.now() : input.confirmedAt();
    // 🔴 **第几次确认**（不是「第几轮打样」）：同一样品在「修改中 → 直接通过」这条路上
    //    会在**同一轮打样**里确认两次 —— 两个轮次混用一个名字必然对不上。
    int confirmSeq = nextConfirmSeq(sampleId);
    sql.execute(
        "insert into development_sample_confirmation(id,tenant_id,sample_id,result,confirm_seq,"
            + " reason,issue_note,actor_id,actor_name,confirmed_at,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::int,?::varchar,?::varchar,?::uuid,"
            + " ?::varchar,?::timestamptz,now())",
        UUID.randomUUID(),
        tenantId,
        sampleId,
        input.result().name(),
        confirmSeq,
        input.reason().trim(),
        blankToEmpty(input.issueNote()),
        actorId,
        ACTOR_NAME_UNKNOWN,
        confirmedAt);
    sql.execute(
        "update development_sample set status = ?::varchar, updated_at = now()"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        to.name(),
        tenantId,
        sampleId);
    return sampleDetail(orderId, sampleId);
  }

  // ================================================================ 物流快照

  /** 物流快照清单。 */
  public List<DevelopmentSampleShipmentRow> listShipments(UUID sampleId) {
    List<DevelopmentSampleShipmentRow> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id,sample_id,tracking_no,carrier_code,carrier_name,logistics_status,shipped_at,"
                + " expected_arrival_at,delivered_at,tracking_available,tracking_owner_module,note,"
                + " created_by,created_at from development_sample_shipment"
                + " where tenant_id = ?::uuid and sample_id = ?::uuid"
                + " order by shipped_at desc, id desc",
            currentTenant(),
            sampleId)) {
      items.add(shipmentRow(row));
    }
    return items;
  }

  /** 登记一条物流快照（**人工登记**，不是平台轨迹）。 */
  @Transactional
  public List<DevelopmentSampleShipmentRow> createShipment(
      UUID orderId, UUID sampleId, DevelopmentSampleShipmentInput input, UUID actorId) {
    requireSample(orderId, sampleId);
    if (input.logisticsStatus() == DevelopmentSampleLogisticsStatus.DELIVERED
        && input.deliveredAt() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "物流状态是「已签收」时必须填签收时刻（签了收却没有时刻，等于没签收）");
    }
    var exists =
        sql.fetchOne(
            "select id from development_sample_shipment where tenant_id = ?::uuid"
                + " and sample_id = ?::uuid and tracking_no = ?::varchar",
            currentTenant(),
            sampleId,
            input.trackingNo().trim());
    if (exists != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "该样品已登记过这个发货单号：" + input.trackingNo().trim() + "（重发要换单号）");
    }
    sql.execute(
        "insert into development_sample_shipment(id,tenant_id,sample_id,tracking_no,carrier_code,"
            + " carrier_name,logistics_status,shipped_at,expected_arrival_at,delivered_at,"
            + " tracking_available,tracking_owner_module,note,created_by,created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::varchar,?::timestamptz,"
            + " ?::timestamptz,?::timestamptz,false,?::varchar,?::varchar,?::uuid,now(),now())",
        UUID.randomUUID(),
        currentTenant(),
        sampleId,
        input.trackingNo().trim(),
        blankToEmpty(input.carrierCode()),
        blankToEmpty(input.carrierName()),
        input.logisticsStatus().name(),
        input.shippedAt(),
        input.expectedArrivalAt(),
        input.deliveredAt(),
        LOGISTICS_OWNER_MODULE,
        blankToEmpty(input.note()),
        actorId);
    return listShipments(sampleId);
  }

  // ================================================================ 样品图片

  /** 样品图片清单。 */
  public List<DevelopmentSampleMediaRow> listMedia(UUID sampleId) {
    List<DevelopmentSampleMediaRow> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id,sample_id,media_ref_id,media_name,media_type,file_format,mime_type,"
                + " content_sha256,byte_size,width_px,height_px,storage_key,uploaded_at,note,"
                + " created_by,created_at from development_sample_media"
                + " where tenant_id = ?::uuid and sample_id = ?::uuid order by created_at, id",
            currentTenant(),
            sampleId)) {
      items.add(mediaRow(row));
    }
    return items;
  }

  /** 挂一张样品图片（**引用** catalog 媒体库的对象，快照取自 catalog）。 */
  @Transactional
  public List<DevelopmentSampleMediaRow> createMedia(
      UUID orderId, UUID sampleId, DevelopmentSampleMediaInput input, UUID actorId, String bearer) {
    requireSample(orderId, sampleId);
    var mediaView = media.require(input.mediaRefId(), bearer);
    var exists =
        sql.fetchOne(
            "select id from development_sample_media where tenant_id = ?::uuid"
                + " and sample_id = ?::uuid and media_ref_id = ?::uuid",
            currentTenant(),
            sampleId,
            input.mediaRefId());
    if (exists != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "该样品已挂过这个素材（同一个样品挂同一个媒体对象只有一条）");
    }
    sql.execute(
        "insert into development_sample_media(id,tenant_id,sample_id,media_ref_id,media_name,"
            + " media_type,file_format,mime_type,content_sha256,byte_size,width_px,height_px,"
            + " storage_key,uploaded_at,note,created_by,created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::varchar,"
            + " ?::varchar,?::numeric,?::int,?::int,?::varchar,now(),?::varchar,?::uuid,now(),now())",
        UUID.randomUUID(),
        currentTenant(),
        sampleId,
        mediaView.id(),
        mediaView.name(),
        mediaView.mediaType(),
        mediaView.fileFormat(),
        mediaView.mimeType(),
        mediaView.contentSha256(),
        mediaView.byteSize(),
        mediaView.widthPx(),
        mediaView.heightPx(),
        mediaView.storageKey(),
        blankToEmpty(input.note()),
        actorId);
    return listMedia(sampleId);
  }

  /** catalog 媒体库的候选（跨服务只读读回来的形状）。 */
  public List<DevelopmentSampleMediaCandidateRow> mediaCandidates(String keyword, String bearer) {
    List<DevelopmentSampleMediaCandidateRow> items = new ArrayList<>();
    for (SampleMediaQueryPort.MediaView view : media.candidates(keyword, bearer)) {
      items.add(
          new DevelopmentSampleMediaCandidateRow(
              view.id(),
              view.name(),
              view.mediaType(),
              view.fileFormat(),
              view.mimeType(),
              view.byteSize(),
              view.widthPx(),
              view.heightPx(),
              view.contentSha256(),
              view.storageKey()));
    }
    return items;
  }

  // ================================================================ 样品成本

  /** 样品成本视图（成本头 + 明细行）。 */
  public DevelopmentSampleCostView costView(UUID orderId) {
    Record order = requireOrder(orderId);
    String currency = order.get("currency", String.class);
    Record cost =
        sql.fetchOne(
            "select id,currency,total_amount,expense_posted,expense_owner_module,expense_note,"
                + " posted_at from development_sample_cost where tenant_id = ?::uuid and order_id = ?::uuid",
            currentTenant(),
            orderId);
    List<DevelopmentSampleCostLineRow> lines = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id,cost_id,cost_type,amount,currency,description,incurred_at,created_by,created_at"
                + " from development_sample_cost_line where tenant_id = ?::uuid and order_id = ?::uuid"
                + " order by incurred_at, id",
            currentTenant(),
            orderId)) {
      lines.add(costLineRow(row));
    }
    if (cost == null) {
      // 还没有成本行：返回一张「零成本、尚未入账」的空视图（**不是**「已入账」）。
      return new DevelopmentSampleCostView(
          null,
          orderId,
          currency,
          BigDecimal.ZERO,
          false,
          EXPENSE_OWNER_MODULE,
          "本打样单还没有登记任何成本行。",
          null,
          lines);
    }
    return new DevelopmentSampleCostView(
        cost.get("id", UUID.class),
        orderId,
        cost.get("currency", String.class),
        cost.get("total_amount", BigDecimal.class),
        Boolean.TRUE.equals(cost.get("expense_posted", Boolean.class)),
        cost.get("expense_owner_module", String.class),
        cost.get("expense_note", String.class),
        cost.get("posted_at", OffsetDateTime.class),
        lines);
  }

  /**
   * 追加一条成本明细行。
   *
   * <p>🔴 **「进费用归集 / 入账」这一步做不到**：12 费用（finance 服务）在本仓不存在 ⇒
   * 成本头恒为 {@code expense_posted=false} + {@code ownerModule='12 费用'}。
   * **不假装已经入账。**
   */
  @Transactional
  public DevelopmentSampleCostView addCostLine(
      UUID orderId, DevelopmentSampleCostLineInput input, UUID actorId) {
    Record order = requireOrder(orderId);
    requirePositiveAmount(input.amount());
    UUID tenantId = currentTenant();
    String currency = order.get("currency", String.class);
    var existingCost =
        sql.fetchOne(
            "select id from development_sample_cost where tenant_id = ?::uuid and order_id = ?::uuid",
            tenantId,
            orderId);
    UUID costId =
        existingCost == null
            ? createCostHeader(orderId, currency, actorId)
            : existingCost.get("id", UUID.class);    sql.execute(
        "insert into development_sample_cost_line(id,tenant_id,cost_id,order_id,cost_type,amount,"
            + " currency,description,incurred_at,created_by,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::uuid,?::varchar,?::numeric,?::varchar,?::varchar,"
            + " ?::timestamptz,?::uuid,now())",
        UUID.randomUUID(),
        tenantId,
        costId,
        orderId,
        input.costType().name(),
        input.amount(),
        currency,
        blankToEmpty(input.description()),
        input.incurredAt(),
        actorId);
    return costView(orderId);
  }

  /** 删一条成本明细行（合计金额由数据库触发器自动重算）。 */
  @Transactional
  public DevelopmentSampleCostView deleteCostLine(UUID orderId, UUID lineId, UUID actorId) {
    requireOrder(orderId);
    var line =
        sql.fetchOne(
            "select id from development_sample_cost_line where tenant_id = ?::uuid"
                + " and order_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            orderId,
            lineId);
    if (line == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "成本明细行不存在");
    }
    sql.execute(
        "delete from development_sample_cost_line where tenant_id = ?::uuid and id = ?::uuid",
        currentTenant(),
        lineId);
    return costView(orderId);
  }

  // ================================================================ 内部工具

  /** 打样单的公共 select 片段。 */
  private static final String ORDER_SELECT =
      "select o.id,o.order_no,o.development_project_id,o.project_no,o.project_name,o.source_type,"
          + " o.usage_type,o.status,o.supplier_ref_id,o.supplier_code,o.supplier_name,o.stall_id,"
          + " o.stall_code,o.stall_name,o.currency,o.requester_id,o.requester_name,o.requested_at,"
          + " o.expected_delivery_at,o.purpose_note,o.note,o.created_by,o.created_at,o.updated_at,"
          + " (select count(*) from development_sample s where s.tenant_id = o.tenant_id"
          + "   and s.order_id = o.id) as sample_count,"
          + " (select count(*) from development_sample s where s.tenant_id = o.tenant_id"
          + "   and s.order_id = o.id and s.status = 'CONFIRMED') as confirmed_count,"
          + " (select c.total_amount from development_sample_cost c where c.tenant_id = o.tenant_id"
          + "   and c.order_id = o.id) as cost_total"
          + " from development_sample_order o";

  /** 样品的公共 select 片段。 */
  private static final String SAMPLE_SELECT =
      "select s.id,s.order_id,s.sample_no,s.development_project_id,s.project_no,s.project_name,"
          + " s.source_type,s.usage_type,s.status,s.spec_name,s.spec_value,s.quantity,s.currency,"
          + " s.revision_round,s.remark,s.created_by,s.created_at,s.updated_at,"
          + " (select count(*) from development_sample_confirmation c where c.tenant_id = s.tenant_id"
          + "   and c.sample_id = s.id) as confirmation_count,"
          + " (select count(*) from development_sample_shipment sh where sh.tenant_id = s.tenant_id"
          + "   and sh.sample_id = s.id) as shipment_count,"
          + " (select count(*) from development_sample_media m where m.tenant_id = s.tenant_id"
          + "   and m.sample_id = s.id) as media_count,"
          + " (select sh.tracking_no from development_sample_shipment sh"
          + "   where sh.tenant_id = s.tenant_id and sh.sample_id = s.id"
          + "   order by sh.shipped_at desc, sh.id desc limit 1) as latest_tracking_no,"
          + " (select sh.logistics_status from development_sample_shipment sh"
          + "   where sh.tenant_id = s.tenant_id and sh.sample_id = s.id"
          + "   order by sh.shipped_at desc, sh.id desc limit 1) as latest_logistics_status"
          + " from development_sample s";

  private Record requireOrder(UUID orderId) {
    var row =
        sql.fetchOne(
            ORDER_SELECT + " where o.tenant_id = ?::uuid and o.id = ?::uuid", currentTenant(), orderId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "打样单不存在");
    }
    return row;
  }

  private Record requireSample(UUID orderId, UUID sampleId) {
    var row =
        sql.fetchOne(
            SAMPLE_SELECT + " where s.tenant_id = ?::uuid and s.order_id = ?::uuid and s.id = ?::uuid",
            currentTenant(),
            orderId,
            sampleId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "样品不存在");
    }
    return row;
  }

  private UUID requireSampleByNo(UUID tenantId, String sampleNo) {
    var row =
        sql.fetchOne(
            "select id from development_sample where tenant_id = ?::uuid and sample_no = ?::varchar",
            tenantId,
            sampleNo);
    return row.get("id", UUID.class);
  }

  /** 打样单状态迁移历史。 */
  public List<DevelopmentSampleOrderTransitionRow> listOrderTransitions(UUID orderId) {
    List<DevelopmentSampleOrderTransitionRow> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id,order_id,from_status,to_status,reason,actor_id,actor_name,occurred_at,applied_at"
                + " from development_sample_order_transition where tenant_id = ?::uuid and order_id = ?::uuid"
                + " order by occurred_at desc, id desc",
            currentTenant(),
            orderId)) {
      items.add(orderTransitionRow(row));
    }
    return items;
  }

  /** 样品制作进度事件。 */
  public List<DevelopmentSampleTransitionRow> listSampleTransitions(UUID sampleId) {
    List<DevelopmentSampleTransitionRow> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id,sample_id,from_status,to_status,reason,actor_id,actor_name,occurred_at,applied_at"
                + " from development_sample_transition where tenant_id = ?::uuid and sample_id = ?::uuid"
                + " order by occurred_at desc, id desc",
            currentTenant(),
            sampleId)) {
      items.add(sampleTransitionRow(row));
    }
    return items;
  }

  /** 立项快照（**只读** 6.6.1.1 的表：零外键、零 ALTER）。 */
  private ProjectSnapshot requireProjectSnapshot(UUID projectId) {
    var row =
        sql.fetchOne(
            "select id, project_no, name, status from development_project"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            projectId);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "开发立项不存在（id=" + projectId + "）：打样单必须挂在 6.6.1.1 开发立项上");
    }
    return new ProjectSnapshot(
        row.get("id", UUID.class),
        row.get("project_no", String.class),
        row.get("name", String.class),
        row.get("status", String.class));
  }

  /** 档口快照（**只读** 6.1.2.1 的表：零外键、零 ALTER）。 */
  private StallSnapshot requireStallSnapshot(UUID stallId) {
    var row =
        sql.fetchOne(
            "select id, code, name from supplier_source_stall"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            stallId);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "档口不存在（id=" + stallId + "）：档口是 6.1.2.1 的事实，请先建档口");
    }
    return new StallSnapshot(
        row.get("id", UUID.class), row.get("code", String.class), row.get("name", String.class));
  }

  /** 供应商快照（**逻辑引用**，跨服务读 catalog；快照取自 catalog，不采信客户端自报的名字）。 */
  private SupplierSnapshot requireSupplierSnapshot(UUID supplierRefId, String bearer) {
    var view = suppliers.require(supplierRefId, bearer);
    return new SupplierSnapshot(view.roleId(), view.partyCode(), view.partyName());
  }

  /** 币种变了 ⇒ 样品与成本明细行的币种跟着走（一张单一个币种）。 */
  private void syncOrderCurrency(UUID orderId, String currency) {
    sql.execute(
        "update development_sample set currency = ?::varchar, updated_at = now()"
            + " where tenant_id = ?::uuid and order_id = ?::uuid",
        currency,
        currentTenant(),
        orderId);
    sql.execute(
        "update development_sample_cost set currency = ?::varchar, updated_at = now()"
            + " where tenant_id = ?::uuid and order_id = ?::uuid",
        currency,
        currentTenant(),
        orderId);
    sql.execute(
        "update development_sample_cost_line set currency = ?::varchar"
            + " where tenant_id = ?::uuid and order_id = ?::uuid",
        currency,
        currentTenant(),
        orderId);
  }

  private UUID createCostHeader(UUID orderId, String currency, UUID actorId) {
    UUID tenantId = currentTenant();
    UUID costId = UUID.randomUUID();
    sql.execute(
        "insert into development_sample_cost(id,tenant_id,order_id,currency,total_amount,"
            + " expense_posted,expense_owner_module,expense_note,posted_at,created_by,created_at,"
            + " updated_at) values (?::uuid,?::uuid,?::uuid,?::varchar,0,false,?::varchar,?::varchar,"
            + " null,?::uuid,now(),now())",
        costId,
        tenantId,
        orderId,
        currency,
        EXPENSE_OWNER_MODULE,
        "🔴 样品成本**尚未进费用归集**：12 费用（finance 服务）在本仓不存在，"
            + "本模块只记成本事实，**不假装已经入账**。",
        actorId);
    return costId;
  }

  /** 下一次确认的序号（第几次确认）。 */
  private int nextConfirmSeq(UUID sampleId) {
    var row =
        sql.fetchOne(
            "select count(*) as c from development_sample_confirmation"
                + " where tenant_id = ?::uuid and sample_id = ?::uuid",
            currentTenant(),
            sampleId);
    Long count = row == null ? null : row.get("c", Long.class);
    return (count == null ? 0 : count.intValue()) + 1;
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

  private static DevelopmentSampleOrderRow orderRow(Record row) {
    String status = row.get("status", String.class);
    String sourceType = row.get("source_type", String.class);
    String usageType = row.get("usage_type", String.class);
    return new DevelopmentSampleOrderRow(
        row.get("id", UUID.class),
        row.get("order_no", String.class),
        row.get("development_project_id", UUID.class),
        row.get("project_no", String.class),
        row.get("project_name", String.class),
        sourceType,
        DevelopmentSampleSourceType.valueOf(sourceType).label(),
        usageType,
        DevelopmentSampleUsageType.valueOf(usageType).label(),
        status,
        DevelopmentSampleOrderStatus.valueOf(status).label(),
        row.get("supplier_ref_id", UUID.class),
        row.get("supplier_code", String.class),
        row.get("supplier_name", String.class),
        row.get("stall_id", UUID.class),
        row.get("stall_code", String.class),
        row.get("stall_name", String.class),
        row.get("currency", String.class),
        row.get("requester_id", UUID.class),
        row.get("requester_name", String.class),
        row.get("requested_at", OffsetDateTime.class),
        row.get("expected_delivery_at", OffsetDateTime.class),
        countOf(row, "sample_count"),
        countOf(row, "confirmed_count"),
        row.get("cost_total", BigDecimal.class),
        row.get("purpose_note", String.class),
        row.get("note", String.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private static DevelopmentSampleRow sampleRow(Record row) {
    String status = row.get("status", String.class);
    String sourceType = row.get("source_type", String.class);
    String usageType = row.get("usage_type", String.class);
    return new DevelopmentSampleRow(
        row.get("id", UUID.class),
        row.get("order_id", UUID.class),
        row.get("sample_no", String.class),
        row.get("development_project_id", UUID.class),
        row.get("project_no", String.class),
        row.get("project_name", String.class),
        sourceType,
        DevelopmentSampleSourceType.valueOf(sourceType).label(),
        usageType,
        DevelopmentSampleUsageType.valueOf(usageType).label(),
        status,
        DevelopmentSampleStatus.valueOf(status).label(),
        row.get("spec_name", String.class),
        row.get("spec_value", String.class),
        row.get("quantity", BigDecimal.class),
        row.get("currency", String.class),
        intOf(row, "revision_round"),
        countOf(row, "confirmation_count"),
        countOf(row, "shipment_count"),
        countOf(row, "media_count"),
        row.get("latest_tracking_no", String.class),
        row.get("latest_logistics_status", String.class),
        row.get("remark", String.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private static DevelopmentSampleOrderTransitionRow orderTransitionRow(Record row) {
    String from = row.get("from_status", String.class);
    String to = row.get("to_status", String.class);
    return new DevelopmentSampleOrderTransitionRow(
        row.get("id", UUID.class),
        row.get("order_id", UUID.class),
        from,
        DevelopmentSampleOrderStatus.valueOf(from).label(),
        to,
        DevelopmentSampleOrderStatus.valueOf(to).label(),
        row.get("reason", String.class),
        row.get("actor_id", UUID.class),
        row.get("actor_name", String.class),
        row.get("occurred_at", OffsetDateTime.class),
        row.get("applied_at", OffsetDateTime.class));
  }

  private static DevelopmentSampleTransitionRow sampleTransitionRow(Record row) {
    String from = row.get("from_status", String.class);
    String to = row.get("to_status", String.class);
    return new DevelopmentSampleTransitionRow(
        row.get("id", UUID.class),
        row.get("sample_id", UUID.class),
        from,
        DevelopmentSampleStatus.valueOf(from).label(),
        to,
        DevelopmentSampleStatus.valueOf(to).label(),
        row.get("reason", String.class),
        row.get("actor_id", UUID.class),
        row.get("actor_name", String.class),
        row.get("occurred_at", OffsetDateTime.class),
        row.get("applied_at", OffsetDateTime.class));
  }

  private static DevelopmentSampleConfirmationRow confirmationRow(Record row) {
    String result = row.get("result", String.class);
    return new DevelopmentSampleConfirmationRow(
        row.get("id", UUID.class),
        row.get("sample_id", UUID.class),
        result,
        DevelopmentSampleConfirmationResult.valueOf(result).label(),
        intOf(row, "confirm_seq"),
        row.get("reason", String.class),
        row.get("issue_note", String.class),
        row.get("actor_id", UUID.class),
        row.get("actor_name", String.class),
        row.get("confirmed_at", OffsetDateTime.class),
        row.get("applied_at", OffsetDateTime.class));
  }

  private static DevelopmentSampleShipmentRow shipmentRow(Record row) {
    String status = row.get("logistics_status", String.class);
    return new DevelopmentSampleShipmentRow(
        row.get("id", UUID.class),
        row.get("sample_id", UUID.class),
        row.get("tracking_no", String.class),
        row.get("carrier_code", String.class),
        row.get("carrier_name", String.class),
        status,
        DevelopmentSampleLogisticsStatus.valueOf(status).label(),
        row.get("shipped_at", OffsetDateTime.class),
        row.get("expected_arrival_at", OffsetDateTime.class),
        row.get("delivered_at", OffsetDateTime.class),
        Boolean.TRUE.equals(row.get("tracking_available", Boolean.class)),
        row.get("tracking_owner_module", String.class),
        row.get("note", String.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private static DevelopmentSampleMediaRow mediaRow(Record row) {
    return new DevelopmentSampleMediaRow(
        row.get("id", UUID.class),
        row.get("sample_id", UUID.class),
        row.get("media_ref_id", UUID.class),
        row.get("media_name", String.class),
        row.get("media_type", String.class),
        row.get("file_format", String.class),
        row.get("mime_type", String.class),
        row.get("content_sha256", String.class),
        row.get("byte_size", BigDecimal.class),
        row.get("width_px", Integer.class),
        row.get("height_px", Integer.class),
        row.get("storage_key", String.class),
        row.get("uploaded_at", OffsetDateTime.class),
        row.get("note", String.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private static DevelopmentSampleCostLineRow costLineRow(Record row) {
    String costType = row.get("cost_type", String.class);
    return new DevelopmentSampleCostLineRow(
        row.get("id", UUID.class),
        row.get("cost_id", UUID.class),
        costType,
        DevelopmentSampleCostType.valueOf(costType).label(),
        row.get("amount", BigDecimal.class),
        row.get("currency", String.class),
        row.get("description", String.class),
        row.get("incurred_at", OffsetDateTime.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private static int countOf(Record row, String column) {
    Long value = row.get(column, Long.class);
    return value == null ? 0 : value.intValue();
  }

  private static int intOf(Record row, String column) {
    Integer value = row.get(column, Integer.class);
    return value == null ? 0 : value;
  }

  // ---- 内部快照（跨模块事实的展示快照） ----

  /** 6.6.1.1 开发立项的只读快照。 */
  private record ProjectSnapshot(UUID id, String projectNo, String name, String status) {}

  /** 6.1.2.1 档口的只读快照。 */
  private record StallSnapshot(UUID id, String code, String name) {}

  /** catalog 供应商的只读快照。 */
  private record SupplierSnapshot(UUID refId, String code, String name) {}
}
