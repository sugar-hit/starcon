package com.starcon.trade;

import static com.starcon.trade.ProcessingModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.4.1.1 加工单。
 *
 * <p><b>本服务不碰库存。</b>蓝图关系句「加工是『消耗组件产生成品』的库存动作，
 * 必须在 warehouse 本地事务完成，不能由 trade 直接改库存」是验收项：
 * 本类只产出加工单据（加工单 + 组件领用记录 + 进度事件 + 入库需求通知），
 * 组件出库与成品入库由 warehouse 服务在它自己的一个本地事务里完成
 * （warehouse 侧端点 {@code POST /warehouse/processing-orders}）。
 *
 * <p><b>组件领用量只有一个来源</b>：catalog 3.5.2.1 的规则 R7
 * （{@code 需求量 = 单位用量 × 产量 × (1 + 损耗率/100)}，6 位小数 HALF_UP，
 * 损耗率是<b>百分数</b>不是比率）。本类经 {@link BomVersionPort} 调 catalog 的授权接口
 * 取回该三元组并<b>原样冻结</b>进组件领用记录，
 * 既不重算也不取整 —— 取整成采购量是 3.5.2.2 与 06 采购建议的职责。
 */
@Service
public class ProcessingOrderService {

  /** 数量刻度：与 DB 的 numeric(19,6) 逐字对齐。 */
  private static final int QTY_SCALE = 6;

  /** 损耗率刻度：与 DB 的 numeric(7,4) 对齐（catalog V25 的 scrap_rate 同精度）。 */
  private static final int SCRAP_SCALE = 4;

  private static final int MAX_PAGE_SIZE = 200;

  private static final String ORDER_COLUMNS =
      "o.id,o.order_no,o.factory_id,o.output_sku_id,o.output_sku_code,o.bom_id,o.bom_version_id,"
          + "o.bom_version_no,o.planned_output_quantity,o.completed_quantity,o.due_on,o.status,"
          + "o.released_at,o.completed_at,o.received_at,o.cancel_reason,o.note,o.version,"
          + "o.created_at,f.code,f.name";

  private final DSLContext sql;
  private final BomVersionPort bom;

  public ProcessingOrderService(DSLContext sql, BomVersionPort bom) {
    this.sql = sql;
    this.bom = bom;
  }

  // ================================================================ 能力声明

  /**
   * 能力与上下游可用性。
   *
   * <p>存在的理由与 9.3.2.2 一致：把「哪些输入真有依据」「库存动作归谁」
   * 显式报出来，而不是让调用方从 404 或空数组里去猜哪一环没通。
   */
  public ProcessingCapabilities capabilities() {
    return new ProcessingCapabilities(
        List.of(ProcessingStatus.values()),
        List.of(
            "DRAFT → RELEASED（下达，冻结组件领用量）",
            "DRAFT → CANCELLED（草稿作废，必须给原因）",
            "RELEASED → IN_PROCESS（工厂开工）",
            "RELEASED → CANCELLED",
            "IN_PROCESS → COMPLETED（报完工，必须给数量）",
            "IN_PROCESS → CANCELLED",
            "COMPLETED → RECEIVED（入库实收回写）",
            "RECEIVED 与 CANCELLED 是终态，不可离开"),
        List.of(FactoryKind.values()),
        List.of(InboundRequestStatus.values()),
        true,
        true,
        "上游 3.5.2.1 BOM 属 catalog（V25 已交付）：组件领用量经 catalog 授权接口按规则 R7 取回"
            + "（单位用量 × 产量 × (1 + 损耗率/100)，6 位小数 HALF_UP，损耗率是百分数），"
            + "本服务原样冻结不重算、不取整。库存属 warehouse：组件出库与成品入库在 warehouse 的"
            + "一个本地事务里完成，本服务不建跨库外键、不跨库读写。",
        List.of(
            new ProcessingCapabilities.DownstreamGap(
                "8.4.1.1",
                "组装单",
                "warehouse",
                false,
                "8 仓储作业 · 8.4 组装与加工",
                "仓库现场作业单据未交付：加工单当前只登记入库需求通知与实收数量，"
                    + "仓库内部的拣货/复核/上架现场流程尚未建模。"),
            new ProcessingCapabilities.DownstreamGap(
                "6.4.1.2",
                "BOM 关联",
                "trade",
                false,
                "6.4.1 委外加工",
                "同组兄弟模块未交付：冻结展开结果的独立入口尚未开发。"
                    + "本模块已在下达时把当版 BOM 的组件与用量冻结进组件领用记录，"
                    + "效果上覆盖了「已下达的加工单不受后来 BOM 改动影响」这条硬要求。"),
            new ProcessingCapabilities.DownstreamGap(
                "6.4.2.1",
                "质检单",
                "trade",
                false,
                "6.4.2 质检",
                "来料/成品质检未交付：完工报数量时不能做抽样判定与扣量，"
                    + "不合格量的处置（6.4.2.2）也无下游出口。"),
            new ProcessingCapabilities.DownstreamGap(
                "6.5.1.1",
                "采购对账",
                "trade",
                false,
                "6.5.采购对账",
                "按期间汇总加工用料与供应商对账未交付，加工单暂不参与对账单。")),
        "加工是『消耗组件产生成品』的库存动作：下单与进度在 trade，"
            + "组件出库与成品入库在 warehouse 的本地事务。跨服务只经授权 API，不共享数据库。");
  }

  // ================================================================ 加工厂

  /** 加工厂列表（真分页 + 全量汇总，汇总不受分页影响）。 */
  public FactoryPage pageFactories(FactoryQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    if (query.factoryKind() != null && !query.factoryKind().isBlank()) {
      where.add("factory_kind = ?");
      binds.add(factoryKind(query.factoryKind()).name());
    }
    if (query.status() != null && !query.status().isBlank()) {
      where.add("status = ?");
      binds.add(factoryStatus(query.status()).name());
    }
    if (query.keyword() != null && !query.keyword().isBlank()) {
      // 真实模糊检索：命中编码或名称（warehouse 8.2.1.2 同一口径，不做前缀假搜索）。
      where.add("(code ilike ? or name ilike ?)");
      String pattern = "%" + query.keyword().trim() + "%";
      binds.add(pattern);
      binds.add(pattern);
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total = sql.fetchOne("select count(*) from processing_factory" + clause, binds.toArray())
        .get(0, Long.class);
    Record summary =
        sql.fetchOne(
            "select count(*) filter (where status='ACTIVE') as active,"
                + " count(*) filter (where status='SUSPENDED') as suspended,"
                + " count(*) filter (where factory_kind='IN_HOUSE') as in_house"
                + " from processing_factory");
    List<FactorySaved> items =
        sql.fetch(
                "select id,code,name,factory_kind,status,note,version,created_at,updated_at"
                    + " from processing_factory"
                    + clause
                    + " order by code, id limit ? offset ?",
                append(binds, size, page * size))
            .map(ProcessingOrderService::factoryRow);
    return new FactoryPage(
        items,
        page,
        size,
        total,
        count(summary, "active"),
        count(summary, "suspended"),
        count(summary, "in_house"));
  }

  /** 加工厂详情。跨租户在 RLS 下查不到 → 404。 */
  public FactorySaved factory(UUID factoryId) {
    Record row =
        sql.fetchOne(
            "select id,code,name,factory_kind,status,note,version,created_at,updated_at"
                + " from processing_factory where id = ?",
            factoryId);
    if (row == null) {
      throw notFound("加工厂 " + factoryId + " 不存在或无权查看");
    }
    return factoryRow(row);
  }

  /** 新建 / 更新加工厂。编码建后不可改（与 V25 bom.code 同一纪律）。 */
  @Transactional
  public FactorySaved saveFactory(FactoryInput input) {
    FactoryKind kind = factoryKind(input.factoryKind());
    String code = fit(input.code(), 64, "加工厂编码");
    String name = fit(input.name(), 200, "加工厂名称");
    if (code.isEmpty()) {
      throw bad("加工厂编码不能为空");
    }
    OffsetDateTime now = now();
    if (existsByCode(code)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "加工厂编码「" + code + "」已被本租户其它加工厂占用");
    }
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into processing_factory(id,tenant_id,code,name,factory_kind,status,note,"
            + "created_at,updated_at) values(?,?,?,?,?,'ACTIVE',?,?::timestamptz,?::timestamptz)",
        id, currentTenant(), code, name, kind.name(), fit(input.note(), 2000, "备注"), now, now);
    return factory(id);
  }

  /** 更新加工厂（档案维护：名称 / 厂别 / 备注 / 启停）。编码与租户不可改。 */
  @Transactional
  public FactorySaved updateFactory(UUID factoryId, FactoryInput input) {
    Record head =
        sql.fetchOne("select * from processing_factory where id = ? for update", factoryId);
    if (head == null) {
      throw notFound("加工厂 " + factoryId + " 不存在或无权修改");
    }
    if (input.code() != null && !input.code().isBlank()) {
      String code = fit(input.code(), 64, "加工厂编码");
      if (!code.equals(head.get("code", String.class))) {
        throw bad("加工厂编码建后不可改（它是加工单上的稳定引用）");
      }
    }
    requireVersion(head.get("version", Long.class), input.version());
    FactoryStatus status =
        input.status() == null || input.status().isBlank()
            ? factoryStatus(head.get("status", String.class))
            : factoryStatus(input.status());
    sql.execute(
        "update processing_factory set name=?,factory_kind=?,status=?,note=?,version=version+1,"
            + "updated_at=now() where id=?",
        fit(input.name(), 200, "加工厂名称"),
        factoryKind(input.factoryKind()).name(),
        status.name(),
        fit(input.note(), 2000, "备注"),
        factoryId);
    return factory(factoryId);
  }

  // ================================================================ 组件需求量试算

  /**
   * 组件需求量试算（只读，不落库）：按 catalog 的 R7 算这一版 BOM 要领哪些料。
   *
   * <p>页面在新建加工单之前用它预览「按这一版 BOM，这些组件各要多少」，
   * 下达时落进组件领用记录的是<b>同一口径</b>的数。
   */
  public RequirementPreview previewRequirement(RequirementPreviewInput input, String bearer) {
    BomVersionPort.VersionView version = bom.version(input.bomVersionId(), bearer);
    BomVersionPort.Requirement requirement =
        bom.requirement(input.bomVersionId(), qty(input.outputQuantity()), bearer);
    return new RequirementPreview(
        requirement.bomId(),
        requirement.versionId(),
        requirement.versionNo(),
        requirement.outputSkuId(),
        requirement.outputSkuCode(),
        qty(input.outputQuantity()),
        componentLines(requirement.lines()),
        requirement.singleLevel(),
        "需求量按 catalog 3.5.2.1 的规则 R7 算出（单位用量 × 产量 × (1 + 损耗率/100)，"
            + "6 位小数 HALF_UP）；损耗率是百分数不是比率。仅单层，不含子 BOM 里的料，"
            + "也不取整成采购量 —— 取整与补安全库存是 3.5.2.2 与采购建议的职责。"
            + "（BOM 版本 " + version.versionNo() + "）");
  }

  // ================================================================ 加工单

  /** 加工单列表（真分页 + 全量汇总 + 筛选；排序固定为交期倒序，逾期一眼可见）。 */
  public ProcessingOrderPage pageOrders(ProcessingOrderQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    if (query.factoryId() != null) {
      where.add("o.factory_id = ?");
      binds.add(query.factoryId());
    }
    if (query.outputSkuId() != null) {
      where.add("o.output_sku_id = ?");
      binds.add(query.outputSkuId());
    }
    if (query.status() != null && !query.status().isBlank()) {
      where.add("o.status = ?");
      binds.add(processingStatus(query.status()).name());
    }
    if (Boolean.TRUE.equals(query.overdueOnly())) {
      // 逾期 = 未入库且已过交期（已入库 / 已作废的不算逾期）。
      where.add("o.due_on < current_date and o.status not in ('RECEIVED','CANCELLED')");
    }
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.add("(o.order_no ilike ? or o.output_sku_code ilike ? or o.bom_version_no ilike ?)");
      String pattern = "%" + query.keyword().trim() + "%";
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    String from =
        " from processing_order o join processing_factory f"
            + " on f.tenant_id = o.tenant_id and f.id = o.factory_id";
    long total = sql.fetchOne("select count(*)" + from + clause, binds.toArray()).get(0, Long.class);
    Record summary =
        sql.fetchOne(
            "select count(*) filter (where o.status='DRAFT') as draft,"
                + " count(*) filter (where o.status='RELEASED') as released,"
                + " count(*) filter (where o.status='IN_PROCESS') as in_process,"
                + " count(*) filter (where o.status='COMPLETED') as completed,"
                + " count(*) filter (where o.status='RECEIVED') as received,"
                + " count(*) filter (where o.status='CANCELLED') as cancelled,"
                + " count(*) filter (where o.due_on < current_date"
                + " and o.status not in ('RECEIVED','CANCELLED')) as overdue,"
                + " coalesce(sum(o.planned_output_quantity),0) as total_planned,"
                + " coalesce(sum(o.completed_quantity),0) as total_completed" + from);
    List<ProcessingOrderSaved> items =
        sql.fetch(
                "select " + ORDER_COLUMNS + from + clause
                    + " order by o.due_on, o.id limit ? offset ?",
                append(binds, size, page * size))
            .map(ProcessingOrderService::orderRow);
    return new ProcessingOrderPage(
        items,
        page,
        size,
        total,
        count(summary, "draft"),
        count(summary, "released"),
        count(summary, "in_process"),
        count(summary, "completed"),
        count(summary, "received"),
        count(summary, "cancelled"),
        count(summary, "overdue"),
        qty(summary.get("total_planned", BigDecimal.class)),
        qty(summary.get("total_completed", BigDecimal.class)));
  }

  /**
   * 新建加工单（草稿）。
   *
   * <p>组件领用记录在此**就已按 catalog 的 R7 算好并落库**，而不是等下达时才算：
   * 冻结必须发生在「这一版 BOM 还说得清是谁」的时刻，而草稿与下达之间
   * BOM 可能已经接替了新版本。
   */
  @Transactional
  public ProcessingOrderSaved createOrder(ProcessingOrderInput input, UUID actorId, String bearer) {
    UUID tenantId = currentTenant();
    String orderNo = fit(input.orderNo(), 64, "加工单号");
    if (orderNo.isEmpty()) {
      throw bad("加工单号不能为空");
    }
    if (existsByOrderNo(orderNo)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "加工单号「" + orderNo + "」已被本租户其它加工单占用");
    }
    Record factory = requireActiveFactory(input.factoryId());

    // BOM 版本必须**已发布**：草稿版本可以随手改组件，按它领的料无从回溯。
    BomVersionPort.VersionView version = bom.version(input.bomVersionId(), bearer);
    if (!"PUBLISHED".equals(version.status())) {
      throw bad(
          "BOM 版本「"
              + version.versionNo()
              + "」当前是 "
              + version.status()
              + "，不能用于加工单。加工单是照着领料去仓库的凭据，"
              + "只有**已发布**版本的组件与损耗率是稳定的（3.5.2.1 规则 R2/R3）");
    }
    BigDecimal planned = positiveQty(input.plannedOutputQuantity(), "约定完工数量");
    BomVersionPort.Requirement requirement =
        bom.requirement(input.bomVersionId(), planned, bearer);
    if (requirement.lines().isEmpty()) {
      throw bad(
          "BOM 版本「"
              + version.versionNo()
              + "」没有任何组件，不能据此下加工单：一张零组件的单子等于"
              + "「发到厂里让它凭空变出成品」。请先在 3.5.2.1 维护该版本的组件清单");
    }
    if (input.dueOn().isBefore(LocalDate.now(ZoneOffset.UTC))) {
      throw bad("交期不能早于今天：交期是与加工厂约定的完工日，填过去的日期等于开局就逾期");
    }

    UUID orderId = UUID.randomUUID();
    OffsetDateTime now = now();
    sql.execute(
        "insert into processing_order(id,tenant_id,order_no,factory_id,output_sku_id,"
            + "output_sku_code,bom_id,bom_version_id,bom_version_no,planned_output_quantity,"
            + "completed_quantity,due_on,status,note,version,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,0,?,'DRAFT',?,0,?::timestamptz,?::timestamptz)",
        orderId,
        tenantId,
        orderNo,
        input.factoryId(),
        requirement.outputSkuId(),
        fit(requirement.outputSkuCode(), 64, "成品 SKU 编码"),
        requirement.bomId(),
        requirement.versionId(),
        fit(requirement.versionNo(), 32, "BOM 版本号"),
        planned,
        input.dueOn(),
        fit(input.note(), 2000, "备注"),
        now,
        now);

    // 冻结 R7 三元组（单位用量 / 损耗率 / 需求量）。
    for (BomVersionPort.RequirementLine line : requirement.lines()) {
      sql.execute(
          "insert into processing_order_component(id,tenant_id,order_id,sku_id,sku_code,"
              + "unit_quantity,scrap_rate,required_quantity,issued_quantity,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,0,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          tenantId,
          orderId,
          line.skuId(),
          fit(line.skuCode(), 64, "组件 SKU 编码"),
          qty(line.unitQuantity()),
          scrap(line.scrapRate()),
          qty(line.requiredQuantity()),
          now,
          now);
    }
    appendEvent(orderId, ProcessingEventType.CREATED, "DRAFT", "DRAFT", BigDecimal.ZERO,
        "按 BOM 版本 " + requirement.versionNo() + " 冻结 " + requirement.lines().size()
            + " 个组件的领用量", actorId, now);
    return orderSaved(orderId);
  }

  /** 加工单详情：单头 + 组件领用记录 + 入库需求通知 + 事件流 + 当前状态的合法下一步。 */
  public ProcessingOrderDetail order(UUID orderId) {
    ProcessingOrderSaved order = orderSaved(orderId);
    List<ProcessingComponentLine> components =
        sql.fetch(
                "select id,sku_id,sku_code,unit_quantity,scrap_rate,required_quantity,"
                    + "issued_quantity,role,note from processing_order_component"
                    + " where order_id = ? order by sku_code, id",
                orderId)
            .map(ProcessingOrderService::componentRow);
    // 入库需求通知随「下达」生成，草稿态下还没有 —— fetchOne 返回 null 而不是 Optional，
    // 所以这里显式判空（jOOQ 的 map() 返回普通值，不是 java.util.Optional）。
    Record inboundRow =
        sql.fetchOne(
            "select id,order_id,sku_id,sku_code,requested_quantity,received_quantity,"
                + "inventory_transaction_ref,status,requested_at,received_at,note"
                + " from processing_inbound_request where order_id = ?",
            orderId);
    InboundRequestView inbound = inboundRow == null ? null : inboundRow(inboundRow);
    List<ProcessingEventView> events =
        sql.fetch(
                "select id,event_type,from_status,to_status,quantity,note,actor_id,occurred_at"
                    + " from processing_order_event where order_id = ? order by occurred_at, id",
                orderId)
            .map(ProcessingOrderService::eventRow);
    return new ProcessingOrderDetail(
        order,
        components,
        inbound,
        events,
        nextStatuses(ProcessingStatus.valueOf(order.status())),
        List.of(
            "组件出库与成品入库由 warehouse 在**一个本地事务**里完成；本服务不直接改库存。",
            "组件领用量冻结自 catalog 3.5.2.1 的 BOM 版本（规则 R7），已下达后不可改；"
                + "后来 BOM 接替新版本不影响这张单。",
            "完工数量是工厂**报**的数，实收数量是仓库**收**的数；两者不相等时以实收为准。"));
  }

  /**
   * 推进状态（DRAFT → RELEASED → IN_PROCESS → COMPLETED → RECEIVED）。
   *
   * <p>「下达」这一步同时做三件在蓝图里被点名的事：冻结组件领用量已生效、
   * 产生入库需求通知（入库需求通知 8）、并开始按状态机推进。
   */
  @Transactional
  public ProcessingOrderSaved advanceStatus(
      UUID orderId, ProcessingStatusInput input, UUID actorId) {
    UUID tenantId = currentTenant();
    Record head = lockOrder(orderId);
    requireVersion(head.get("version", Long.class), input.version());
    ProcessingStatus from = ProcessingStatus.valueOf(head.get("status", String.class));
    ProcessingStatus to = processingStatus(input.status());
    if (!from.canMoveTo(to)) {
      throw bad(
          "加工单当前状态「" + from.label() + "」不能推进到「" + to.label() + "」。"
              + "合法下一步是 " + nextLabels(from) + "（已入库与已作废是终态，不可离开）");
    }
    BigDecimal quantity = input.quantity() == null ? BigDecimal.ZERO : qty(input.quantity());
    OffsetDateTime now = now();

    if (to == ProcessingStatus.RELEASED) {
      requireComponentsPresent(orderId);
      release(tenantId, orderId, now, input);
    } else if (to == ProcessingStatus.COMPLETED) {
      if (quantity.signum() <= 0) {
        throw bad("完工必须报数量：报 0 个的「完工」不是完工，加工单会永远停在加工中");
      }
      BigDecimal planned = head.get("planned_output_quantity", BigDecimal.class);
      if (quantity.compareTo(planned) > 0) {
        throw bad(
            "完工数量 " + quantity + " 超过了约定的 " + planned
                + "：超产要么先改约定、要么走第二张单，让完工数超过约定会让良率这个数失去意义");
      }
      BigDecimal done = head.get("completed_quantity", BigDecimal.class);
      sql.execute(
          "update processing_order set status=?,completed_quantity=?,completed_at=?::timestamptz,"
              + "version=version+1,updated_at=now() where id=?",
          to.name(), done.add(quantity), now, orderId);
      appendEvent(orderId, ProcessingEventType.COMPLETED, from.name(), to.name(), quantity,
          nz(input.note()), actorId, now);
      return orderSaved(orderId);
    } else if (to == ProcessingStatus.RECEIVED) {
      markReceived(tenantId, orderId, quantity, now, input);
      appendEvent(orderId, ProcessingEventType.RECEIVED, from.name(), to.name(), quantity,
          nz(input.note()), actorId, now);
      return orderSaved(orderId);
    } else if (to == ProcessingStatus.CANCELLED) {
      String reason = fit(input.reason(), 300, "作废原因");
      if (reason.isEmpty()) {
        throw bad("作废必须填原因：没有原因的作废等于把一张已经发给厂里的单悄悄抹掉");
      }
      sql.execute(
          "update processing_order set status=?,cancel_reason=?,version=version+1,"
              + "updated_at=now() where id=?",
          to.name(), reason, orderId);
      // 入库需求通知随加工单一起作废，否则待办清单里会挂着永远等不到的入库需求。
      sql.execute(
          "update processing_inbound_request set status='CANCELLED',version=version+1,"
              + "updated_at=now() where order_id=? and status in ('PENDING','PARTIAL')",
          orderId);
      appendEvent(orderId, ProcessingEventType.CANCELLED, from.name(), to.name(),
          BigDecimal.ZERO, reason, actorId, now);
      return orderSaved(orderId);
    }

    // RELEASED / IN_PROCESS：不带数量，只推状态。
    sql.execute(
        "update processing_order set status=?,version=version+1,updated_at=now() where id=?",
        to.name(), orderId);
    appendEvent(orderId,
        to == ProcessingStatus.RELEASED ? ProcessingEventType.RELEASED : ProcessingEventType.PROGRESS,
        from.name(), to.name(), BigDecimal.ZERO, nz(input.note()), actorId, now);
    return orderSaved(orderId);
  }

  /** 下达：置下达时刻 + 生成入库需求通知（入库需求通知 8，蓝图输出句的硬输出）。 */
  private void release(UUID tenantId, UUID orderId, OffsetDateTime now, ProcessingStatusInput input) {
    sql.execute(
        "update processing_order set status='RELEASED',released_at=?::timestamptz,"
            + "version=version+1,updated_at=now() where id=?",
        now, orderId);
    Record head = sql.fetchOne("select output_sku_id,output_sku_code,planned_output_quantity"
        + " from processing_order where id=?", orderId);
    sql.execute(
        "insert into processing_inbound_request(id,tenant_id,order_id,sku_id,sku_code,"
            + "requested_quantity,received_quantity,inventory_transaction_ref,status,requested_at,note,"
            + "version,updated_at) values(?,?,?,?,?,?,0,'','PENDING',?::timestamptz,?,0,?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        orderId,
        head.get("output_sku_id", UUID.class),
        head.get("output_sku_code", String.class),
        qty(head.get("planned_output_quantity", BigDecimal.class)),
        now,
        nz(input.note()),
        now);
  }

  /**
   * 入库实收回写：加工单转已入库，入库需求通知按实收数量转部分 / 全部入库。
   *
   * <p>实收不得超过需求（DB CHECK 也钉了这一条）：超收是凭空多出成品。
   */
  private void markReceived(
      UUID tenantId, UUID orderId, BigDecimal quantity, OffsetDateTime now,
      ProcessingStatusInput input) {
    Record request =
        sql.fetchOne(
            "select id,requested_quantity,received_quantity,status from processing_inbound_request"
                + " where order_id = ? for update",
            orderId);
    if (request == null) {
      throw bad("这张加工单没有入库需求通知，无法登记入库：入库需求通知随下达一起生成");
    }
    if ("CANCELLED".equals(request.get("status", String.class))) {
      throw bad("入库需求通知已随加工单作废，不能再登记入库");
    }
    BigDecimal requested = request.get("requested_quantity", BigDecimal.class);
    BigDecimal already = request.get("received_quantity", BigDecimal.class);
    BigDecimal total = already.add(quantity);
    if (total.compareTo(requested) > 0) {
      throw bad(
          "入库实收 " + total + " 超过了待入库量 " + requested
              + "：超收等于凭空多出成品。要多收请先改约定或另开加工单");
    }
    String ref = fit(input.reason(), 64, "入库单号");
    boolean full = total.compareTo(requested) == 0;
    sql.execute(
        "update processing_inbound_request set received_quantity=?,status=?,received_at=?::timestamptz,"
            + "inventory_transaction_ref=?,version=version+1,updated_at=now() where id=?",
        total,
        full ? "RECEIVED" : "PARTIAL",
        now,
        ref,
        request.get("id", UUID.class));
    sql.execute(
        "update processing_order set status='RECEIVED',received_at=?::timestamptz,"
            + "version=version+1,updated_at=now() where id=?",
        now, orderId);
  }

  /** 进度登记：只记「推进到哪、报了多少」，不改生命周期。 */
  @Transactional
  public ProcessingOrderSaved registerProgress(
      UUID orderId, ProcessingProgressInput input, UUID actorId) {
    Record head = lockOrder(orderId);
    requireVersion(head.get("version", Long.class), input.version());
    ProcessingStatus status = ProcessingStatus.valueOf(head.get("status", String.class));
    if (status == ProcessingStatus.DRAFT || status == ProcessingStatus.CANCELLED) {
      throw bad("加工单当前状态「" + status.label() + "」不接受进度登记：还没下达或已作废");
    }
    BigDecimal quantity = input.quantity() == null ? BigDecimal.ZERO : qty(input.quantity());
    OffsetDateTime now = now();
    appendEvent(orderId, ProcessingEventType.PROGRESS, status.name(), status.name(), quantity,
        fit(input.note(), 2000, "进度说明"), actorId, now);
    sql.execute("update processing_order set updated_at=now() where id=?", orderId);
    return orderSaved(orderId);
  }

  /**
   * 登记组件领用（**领用进度**，不是改库存）。
   *
   * <p>仓库那边真正发料、并改掉组件库存余额的动作在 warehouse 的本地事务里完成；
   * 本方法记的是「已领了多少」，加工单因此能算出还欠多少料。
   */
  @Transactional
  public ProcessingOrderSaved issueComponent(UUID orderId, IssueInput input, UUID actorId) {
    Record head = lockOrder(orderId);
    requireVersion(head.get("version", Long.class), input.version());
    ProcessingStatus status = ProcessingStatus.valueOf(head.get("status", String.class));
    if (status != ProcessingStatus.RELEASED && status != ProcessingStatus.IN_PROCESS) {
      throw bad("加工单当前状态「" + status.label() + "」不能登记领料：领料发生在下达之后");
    }
    Record component =
        sql.fetchOne(
            "select id,sku_code,required_quantity,issued_quantity from processing_order_component"
                + " where id = ? and order_id = ? for update",
            input.componentId(), orderId);
    if (component == null) {
      throw notFound("组件领用记录 " + input.componentId() + " 不存在或不属于这张加工单");
    }
    BigDecimal issued = positiveQty(input.issuedQuantity(), "本次领用数量");
    BigDecimal already = component.get("issued_quantity", BigDecimal.class);
    BigDecimal required = component.get("required_quantity", BigDecimal.class);
    BigDecimal total = already.add(issued);
    if (total.compareTo(required) > 0) {
      throw bad(
          "组件「" + component.get("sku_code", String.class) + "」本次领 " + issued
              + " 后累计 " + total + "，超过需求量 " + required
              + "：超领的料没有对应产出，账上会凭空多出组件。要多领请先改 BOM 版本再重下加工单");
    }
    sql.execute(
        "update processing_order_component set issued_quantity=?,note=?,updated_at=now() where id=?",
        total, fit(input.note(), 2000, "备注"), input.componentId());
    appendEvent(orderId, ProcessingEventType.PROGRESS, status.name(), status.name(), BigDecimal.ZERO,
        "组件 " + component.get("sku_code", String.class) + " 领用 " + issued
            + "（累计 " + total + " / 需求 " + required + "）", actorId, now());
    sql.execute("update processing_order set updated_at=now() where id=?", orderId);
    return orderSaved(orderId);
  }

  // ================================================================ 内部

  private ProcessingOrderSaved orderSaved(UUID orderId) {
    Record row =
        sql.fetchOne("select " + ORDER_COLUMNS
            + " from processing_order o join processing_factory f"
            + " on f.tenant_id = o.tenant_id and f.id = o.factory_id where o.id = ?", orderId);
    if (row == null) {
      throw notFound("加工单 " + orderId + " 不存在或无权查看");
    }
    Record rollup =
        sql.fetchOne(
            "select count(*) as component_count,"
                + " count(*) filter (where issued_quantity >= required_quantity) as issued_count,"
                + " coalesce(sum(required_quantity),0) as total_required,"
                + " coalesce(sum(issued_quantity),0) as total_issued"
                + " from processing_order_component where order_id = ?",
            orderId);
    return orderRow(row, rollup);
  }

  /** 已下达前必须已有组件领用记录 —— DB 触发器也会独立核对一次。 */
  private void requireComponentsPresent(UUID orderId) {
    Long count =
        sql.fetchOne("select count(*) from processing_order_component where order_id = ?", orderId)
            .get(0, Long.class);
    if (count == 0) {
      throw bad("这张加工单没有组件领用记录，不能下达");
    }
  }

  private Record requireActiveFactory(UUID factoryId) {
    Record row =
        sql.fetchOne("select id,code,status from processing_factory where id = ?", factoryId);
    if (row == null) {
      throw notFound("加工厂 " + factoryId + " 不存在或无权使用");
    }
    if (!"ACTIVE".equals(row.get("status", String.class))) {
      throw bad("加工厂「" + row.get("code", String.class) + "」已停用，不能下达新加工单");
    }
    return row;
  }

  private Record lockOrder(UUID orderId) {
    Record head =
        sql.fetchOne("select * from processing_order where id = ? for update", orderId);
    if (head == null) {
      throw notFound("加工单 " + orderId + " 不存在或无权操作");
    }
    return head;
  }

  private void appendEvent(
      UUID orderId,
      ProcessingEventType type,
      String from,
      String to,
      BigDecimal quantity,
      String note,
      UUID actorId,
      OffsetDateTime at) {
    sql.execute(
        "insert into processing_order_event(id,tenant_id,order_id,event_type,from_status,to_status,"
            + "quantity,note,actor_id,occurred_at) values(?,?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        orderId,
        type.name(),
        from,
        to,
        quantity == null ? BigDecimal.ZERO : qty(quantity),
        nz(note),
        actorId,
        at);
  }

  private boolean existsByCode(String code) {
    return sql.fetchOne("select 1 from processing_factory where code = ?", code) != null;
  }

  private boolean existsByOrderNo(String orderNo) {
    return sql.fetchOne("select 1 from processing_order where order_no = ?", orderNo) != null;
  }

  // ================================================================ 行映射

  private static FactorySaved factoryRow(Record r) {
    return new FactorySaved(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("factory_kind", String.class),
        r.get("status", String.class),
        nz(r.get("note", String.class)),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class).toString(),
        r.get("updated_at", OffsetDateTime.class).toString());
  }

  private static ProcessingOrderSaved orderRow(Record r) {
    return orderRow(r, null);
  }

  /**
   * 加工单行映射。
   *
   * <p>纯文本 SQL 里的表达式列（count / sum）jOOQ 拿不到列类型，
   * 所以数量一律经 {@link #decimal} 读回（7.3.1.1 同一个坑）。
   */
  private static ProcessingOrderSaved orderRow(Record r, Record rollup) {
    String status = r.get("status", String.class);
    ProcessingStatus parsed = ProcessingStatus.valueOf(status);
    return new ProcessingOrderSaved(
        r.get("id", UUID.class),
        r.get("order_no", String.class),
        r.get("factory_id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("output_sku_id", UUID.class),
        r.get("output_sku_code", String.class),
        r.get("bom_id", UUID.class),
        r.get("bom_version_id", UUID.class),
        r.get("bom_version_no", String.class),
        qty(r.get("planned_output_quantity", BigDecimal.class)),
        qty(r.get("completed_quantity", BigDecimal.class)),
        r.get("due_on", LocalDate.class).toString(),
        status,
        parsed.label(),
        rollup == null ? 0L : count(rollup, "component_count"),
        rollup == null ? 0L : count(rollup, "issued_count"),
        rollup == null ? BigDecimal.ZERO : qty(decimal(rollup, "total_required")),
        rollup == null ? BigDecimal.ZERO : qty(decimal(rollup, "total_issued")),
        nz(r.get("note", String.class)),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class).toString(),
        instant(r, "released_at"),
        instant(r, "completed_at"),
        instant(r, "received_at"),
        nz(r.get("cancel_reason", String.class)));
  }

  private static ProcessingComponentLine componentRow(Record r) {
    BigDecimal required = qty(r.get("required_quantity", BigDecimal.class));
    BigDecimal issued = qty(r.get("issued_quantity", BigDecimal.class));
    return new ProcessingComponentLine(
        r.get("id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        qty(r.get("unit_quantity", BigDecimal.class)),
        scrap(r.get("scrap_rate", BigDecimal.class)),
        required,
        issued,
        required.subtract(issued),
        nz(r.get("role", String.class)),
        nz(r.get("note", String.class)));
  }

  private static InboundRequestView inboundRow(Record r) {    BigDecimal requested = qty(r.get("requested_quantity", BigDecimal.class));
    BigDecimal received = qty(r.get("received_quantity", BigDecimal.class));
    return new InboundRequestView(
        r.get("id", UUID.class),
        r.get("order_id", UUID.class),
        null,
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        requested,
        received,
        requested.subtract(received),
        nz(r.get("inventory_transaction_ref", String.class)),
        r.get("status", String.class),
        r.get("requested_at", OffsetDateTime.class).toString(),
        instant(r, "received_at"),
        nz(r.get("note", String.class)));
  }

  private static ProcessingEventView eventRow(Record r) {
    ProcessingEventType type = ProcessingEventType.valueOf(r.get("event_type", String.class));
    return new ProcessingEventView(
        r.get("id", UUID.class),
        type.name(),
        type.label(),
        r.get("from_status", String.class),
        r.get("to_status", String.class),
        qty(r.get("quantity", BigDecimal.class)),
        nz(r.get("note", String.class)),
        r.get("actor_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class).toString());
  }

  /** catalog 的 R7 三元组 → 组件领用记录（三个数原样搬运，本服务不重算）。 */
  private static List<ProcessingComponentLine> componentLines(
      List<BomVersionPort.RequirementLine> lines) {
    return lines.stream()
        .map(
            line -> {
              BigDecimal required = qty(line.requiredQuantity());
              return new ProcessingComponentLine(
                  null,
                  line.skuId(),
                  line.skuCode(),
                  qty(line.unitQuantity()),
                  scrap(line.scrapRate()),
                  required,
                  BigDecimal.ZERO,
                  required,
                  "",
                  "");
            })
        .collect(Collectors.toList());
  }

  // ================================================================ 工具

  private static List<ProcessingStatus> nextStatuses(ProcessingStatus from) {
    return Arrays.stream(ProcessingStatus.values()).filter(from::canMoveTo).toList();
  }

  private static String nextLabels(ProcessingStatus from) {
    List<String> labels =
        nextStatuses(from).stream().map(s -> s.label() + "（" + s.name() + "）").toList();
    return labels.isEmpty() ? "无（终态）" : String.join(" / ", labels);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static String instant(Record r, String column) {
    OffsetDateTime value = r.get(column, OffsetDateTime.class);
    return value == null ? null : value.toString();
  }

  private static long count(Record r, String column) {
    Object value = r.get(column);
    return value == null ? 0L : ((Number) value).longValue();
  }

  /**
   * 读数量。纯文本 SQL 里的表达式列 jOOQ 拿不到列类型，
   * 收口成不依赖驱动返回类型的读法（7.3.1.1 同一个坑：null 被静默读成 0）。
   */
  private static BigDecimal decimal(Record r, String field) {
    Object value = r.get(field);
    if (value == null) {
      return BigDecimal.ZERO;
    }
    return value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
  }

  /** 数量刻度：与 DB numeric(19,6) 对齐。 */
  private static BigDecimal qty(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value.setScale(QTY_SCALE, RoundingMode.HALF_UP);
  }

  /** 损耗率刻度：与 DB numeric(7,4) 及 catalog V25 的 scrap_rate 对齐。 */
  private static BigDecimal scrap(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value.setScale(SCRAP_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal positiveQty(BigDecimal value, String label) {
    if (value == null || value.signum() <= 0) {
      throw new IllegalArgumentException(label + "必须大于 0");
    }
    if (value.scale() > QTY_SCALE) {
      throw new IllegalArgumentException(
          label + "最多 6 位小数（与 DB numeric(19,6) 及 catalog 规则 R7 的口径一致）");
    }
    return qty(value);
  }

  private static String fit(String value, int max, String label) {
    if (value != null && value.length() > max) {
      throw new IllegalArgumentException(label + "长度不能超过 " + max + " 个字符");
    }
    return value == null ? "" : value.trim();
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static void requireVersion(long actual, long expected) {
    if (actual != expected) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "加工单已被他人修改（当前版本 " + actual + "，你的版本 " + expected
              + "）。请刷新后重试，绝不静默覆盖");
    }
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    var all = new ArrayList<>(binds);
    all.addAll(Arrays.asList(more));
    return all;
  }

  private static FactoryKind factoryKind(String value) {
    try {
      return FactoryKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的厂别「" + value + "」：只能是 OUTSOURCED / IN_HOUSE");
    }
  }

  private static FactoryStatus factoryStatus(String value) {
    try {
      return FactoryStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的加工厂状态「" + value + "」：只能是 ACTIVE / SUSPENDED");
    }
  }

  private static ProcessingStatus processingStatus(String value) {
    try {
      return ProcessingStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad(
          "未知的加工单状态「" + value + "」：只能是 DRAFT / RELEASED / IN_PROCESS / "
              + "COMPLETED / RECEIVED / CANCELLED");
    }
  }

  /**
   * 当前 RLS 租户。由 backend/libraries/tenancy 在事务内设置，客户端头改不动它。
   *
   * <p>表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义
   * GUC **复位成空串**而不是「回到未设置」，直接 cast 会报 uuid 语法错（与 V92 同一句写法）。
   */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }
}
