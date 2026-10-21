package com.starcon.trade;

import static com.starcon.trade.PurchaseOrderListModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.3.1.1 采购单列表（卷 06 采购与供应 / L3 6.3.1 采购单的锚点模块）。
 *
 * <p><b>本模块拥有「采购单」这个聚合</b>（头 + 行 + 留痕，见 V254）。理由：6.3.1.2 / 6.3.1.3
 * 均未交付，本仓此前不存在任何采购单实体；6.2.2.1（V246）交付时刻意不造占位的假采购单表，
 * 只把转单参数记在 {@code purchase_request_conversion} 并写明「由 6.3.1.2 交付后建真正的采购单」。
 * ⇒ <b>采购单号的产出方是本模块</b>，口径钉死为
 * {@code purchase_order.order_no} ↔ 6.3.2.2 的 {@code purchase_arrival_tracking.purchase_order_no}。
 *
 * <p><b>三个最容易做错的口径，本类显式钉死：</b>
 *
 * <ol>
 *   <li><b>已收数量 / 比例</b> = 本库 6.3.2.2 的到货跟踪（{@code purchase_arrival_tracking}，
 *       由 DB 触发器从到货流水重算）。08 仓储的收货单在 warehouse 服务的独立库里，
 *       AGENTS.md 禁止跨服务 SQL ⇒ 只把 warehouse 收货单号当<b>来源快照</b>透出，
 *       <b>不</b>另算一遍「已收」。没进到货跟踪的单，已收是 <b>null</b> 不是 0。
 *   <li><b>总金额</b> = 一单一币种；跨币种合计<b>按币种分组</b>，<b>绝不</b> 1:1 折算。
 *       本位币合计用<b>建单时冻结的汇率快照</b>算，冻结不到就不给这个数并写明原因。
 *   <li><b>数据范围</b> = 由服务端从<b>已认证身份</b>与角色授权推导（ALL / DEPARTMENT / SELF），
 *       <b>不接受请求参数传入</b>；租户来自已认证成员关系，<b>不信任</b> {@code X-Tenant-Id}。
 * </ol>
 *
 * <p><b>时间绑定</b>：时间筛选一律 {@code ?::timestamptz} + {@link OffsetDateTime}。
 * {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s（本项目所在时区），
 * 「按下单时间筛选」会因此筛出错的区间。
 *
 * <p><b>分页是真分页</b>：{@code LIMIT ? OFFSET ?} + 一条独立的 {@code count(*)} 总数查询。
 * 汇总条与分页无关（统计的是<b>命中筛选条件</b>的全部行，不是当前页）。
 */
@Service
public class PurchaseOrderListService {

  private static final int MAX_PAGE_SIZE = 200;
  /** 导出上限：超过这个行数就截断并如实报出（不静默少给）。 */
  private static final int MAX_EXPORT_ROWS = 10000;

  /**
   * 「今天」的<b>数据库</b>口径。
   *
   * <p>刻意用 {@code (now() at time zone 'UTC')::date} 而不是 {@code current_date}：
   * {@code current_date} 跟着会话时区走，同一份数据在两个时区的库里会差一天
   * （与 6.3.2.2 到货逾期同一口径，逐字对齐）。
   */
  private static final String TODAY = "(now() at time zone 'UTC')::date";

  /**
   * 逾期判定表达式（列表口径）。
   *
   * <p>与 6.3.2.2 的规则<b>同一条日历日口径</b>：承诺到货日<b>当天不算逾期</b>，次日 00:00 起算；
   * 尚未全部到货才算逾期；已关闭的单不算逾期。
   *
   * <p>⚠️ **承诺日取本单自己的 {@code purchase_order.promised_arrival_date}**
   * （采购单是对供应商承诺的归属方），6.3.2.2 的 {@code arrival_status} 原样透传供对照 ——
   * 两边若不一致（跟单改了交期而本单没更新），以<b>本单</b>为准并在能力声明里说明。
   */
  private static final String OVERDUE =
      "(o.status <> 'CLOSED'"
          + " and o.promised_arrival_date < "
          + TODAY
          + " and (t.received_qty is null or t.received_qty < t.receivable_qty))";

  private final DSLContext sql;

  /** 复用 6.5.2.1 采购分析已交付的 catalog 端口（5.2.2.1 汇率查询 / 5.2.2.2 汇率台账）。 */
  private final PurchaseAnalyticsCatalogPort catalog;

  public PurchaseOrderListService(DSLContext sql, PurchaseAnalyticsCatalogPort catalog) {
    this.sql = sql;
    this.catalog = catalog;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：哪些维度真有依据、哪些批量操作能落地、下游接上了没有。
   *
   * <p>存在的理由与 9.1.1.1 SalesOrderList 一致：让界面能说清「为什么没有审批状态」，
   * 而不是让它们显示成空白让人以为「这单没有这些值」。
   */
  public PurchaseOrderListCapabilities capabilities() {
    return new PurchaseOrderListCapabilities(
        List.of(
            "采购单号 purchase_order.order_no",
            "供应商 purchase_order.supplier_code + supplier_name（catalog 逻辑引用 + 展示快照）",
            "SKU 行数 purchase_order.line_count / purchase_order_line",
            "总金额与币种 purchase_order.total_amount + currency（一单一币种）",
            "单据状态 purchase_order.status（草稿/待审批/已审批/已驳回/执行中/已关闭）",
            "承诺到货日 purchase_order.promised_arrival_date",
            "已收数量与比例 purchase_arrival_tracking.received_qty / receivable_qty（6.3.2.2）",
            "逾期标记（承诺日 + 数据库时钟 + 尚未全部到货）",
            "目标仓 purchase_order.target_warehouse_id / code / name",
            "负责人与部门 purchase_order.owner_user_id / owner_department_id（数据范围收敛）",
            "催单次数 purchase_order.urge_count + last_urged_at"),
        List.of(
            new PurchaseOrderListDimension(
                "approvalStatus",
                "审批状态",
                false,
                "6.3.1.3 采购单审批",
                "6.3.1.3 未交付。purchase_order.status 里有 PENDING_APPROVAL / APPROVED / REJECTED 三个值，"
                    + "但**没有任何审批留痕**（谁审的、依据哪条规则、什么时候审的）—— "
                    + "本模块如实返回 status，但不冒充「审批状态」这个概念。"),
            new PurchaseOrderListDimension(
                "unitConversionSnapshot",
                "单位换算快照",
                false,
                "6.3.1.2 采购单创建与编辑",
                "采购单位箱规在 catalog 的 3.1.2.4 单位换算里，6.3.1.2 交付时才在提交那一刻冻结。"
                    + "本模块只报基础单位数量，不猜箱规是 1。"),
            new PurchaseOrderListDimension(
                "payableStatus",
                "应付状态",
                false,
                "12 财务（finance 服务当前不存在）",
                "本仓 backend/services/ 下只有 identity / catalog / channel / warehouse / trade / crm，"
                    + "**没有 finance 服务**。应付由 6.3.1.3 审批通过后触发，本模块不越界编一个应付状态。")),
        List.of(
            new PurchaseOrderListBulkOperation(
                "URGE",
                "催单",
                true,
                "6.3.1.1（本模块）",
                "无（采购单存在且未关闭即可）",
                "催单在本模块落地为「一次催单留痕 + 催单计数 + 最近催单时刻」，"
                    + "写进 purchase_order_event。催单**不**改承诺到货日 —— 交期变更历史归 6.3.2.1 跟单。"),
            new PurchaseOrderListBulkOperation(
                "CLOSE",
                "关闭",
                true,
                "6.3.1.1（本模块）",
                "采购单存在、在当前数据范围内、且未关闭",
                "关闭要求填理由（DB 层 CHECK + 触发器双保险）。关闭后整单冻结："
                    + "数量、单价、供应商、承诺日都不可再改。"),
            new PurchaseOrderListBulkOperation(
                "EXPORT",
                "导出",
                true,
                "6.3.1.1（本模块）",
                "无",
                "导出与列表<b>同一套筛选条件</b>（真导出当前筛选结果，不是导出一份全量）。"
                    + "金额<b>按币种分列</b>，不做跨币种折算。")),
        List.of(PurchaseOrderListSort.values()).stream()
            .map(s -> new PurchaseOrderListSortOption(s.name(), s.label()))
            .toList(),
        "可筛选维度：供应商 / 状态 / 目标仓 / 币种 / SKU / 创建时间区间 / 承诺到货日区间 /"
            + " 是否逾期 / 关键字（单号、供应商编码或名称）。",
        "导出走 GET /trade/purchase-orders/export，与列表同一套筛选条件；"
            + "超过 10000 行截断，并在响应头 X-Export-Truncated 里如实标出。",
        "已收数量与比例的唯一口径是**本库 6.3.2.2 的到货跟踪**（purchase_arrival_tracking）。"
            + "08 仓储的收货单在 warehouse 服务的独立库里，跨服务只走授权只读端口（已有"
            + " WarehouseReceiptQueryPort），本模块**只把 warehouse 收货单号当来源快照透出**，"
            + "不另算一遍已收。还没进到货跟踪的单，已收是 null（不是 0）——"
            + "「还没登记到货」与「一件都没到」对采购员的动作含义相反。");
  }

  // ================================================================ 列表

  /** 采购单主列表：真分页 + 与分页无关的全量汇总（只统计命中筛选条件的行）。 */
  public PurchaseOrderListPage list(PurchaseOrderListQuery query) {
    int page = Math.max(0, query.page() == null ? 0 : query.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size() == null ? 50 : query.size()));
    PurchaseOrderListSort sort = PurchaseOrderListSort.parse(query.sort());

    List<Object> binds = new ArrayList<>();
    List<String> where = conditions(query, binds);
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    // ⚠️ **筛选条件必须拼在 FROM 之后** —— 漏拼会得到 `where where ...`，
    //    而且每个占位符的绑定位全部错位（LIMIT 收到的是筛选值，报的是
    //    「argument of LIMIT must be type bigint」这种看不懂的错）。
    String from = fromClause() + clause;

    LocalDate today = dbToday();
    long total = sql.fetchOne("select count(distinct o.id)" + from, binds.toArray()).get(0, Long.class);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(size);
    pageBinds.add((long) page * size);
    List<PurchaseOrderListRow> rows =
        sql.fetch(
                "select o.id, o.order_no, o.status,"
                    + " o.supplier_ref_id, o.supplier_code, o.supplier_name,"
                    + " o.line_count, o.total_amount, o.currency,"
                    + " o.promised_arrival_date,"
                    + " o.target_warehouse_id, o.target_warehouse_code, o.target_warehouse_name,"
                    + " o.owner_user_id, o.owner_department_id,"
                    + " o.urge_count, o.last_urged_at,"
                    + " o.created_at, o.updated_at,"
                    + " t.receivable_qty, t.received_qty, t.arrival_status,"
                    + " lr.warehouse_receipt_code,"
                    + " (select count(distinct l.sku_id) from purchase_order_line l"
                    + "   where l.tenant_id = o.tenant_id and l.order_id = o.id) as sku_count,"
                    + " " + OVERDUE + " as overdue"
                    + from
                    + " order by "
                    + sort.sql()
                    + " limit ? offset ?",
                pageBinds.toArray())
            .map(r -> row(r, today));

    return new PurchaseOrderListPage(
        rows,
        page,
        size,
        total,
        summary(from, clause, binds, today),
        query.dataScope() == null ? "ALL" : query.dataScope(),
        scopeNote(query.dataScope()));
  }

  /** 筛选项下拉用的租户级词表（不随时间筛选变化，避免下拉随翻页漂移）。 */
  public PurchaseOrderListFilterOptions filterOptions() {
    List<String> currencies = new ArrayList<>();
    for (Record r : sql.fetch("select distinct o.currency from purchase_order o order by o.currency")) {
      currencies.add(r.get("currency", String.class));
    }

    List<PurchaseOrderListSupplierOption> suppliers = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select o.supplier_ref_id, o.supplier_code, o.supplier_name, count(*) as cnt"
                + " from purchase_order o group by o.supplier_ref_id, o.supplier_code, o.supplier_name"
                + " order by o.supplier_code, o.supplier_name")) {
      suppliers.add(
          new PurchaseOrderListSupplierOption(
              r.get("supplier_ref_id", UUID.class),
              r.get("supplier_code", String.class),
              r.get("supplier_name", String.class),
              r.get("cnt", Long.class)));
    }

    List<PurchaseOrderListWarehouseOption> warehouses = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select o.target_warehouse_id, o.target_warehouse_code, o.target_warehouse_name, count(*) as cnt"
                + " from purchase_order o"
                + " group by o.target_warehouse_id, o.target_warehouse_code, o.target_warehouse_name"
                + " order by o.target_warehouse_code, o.target_warehouse_name")) {
      warehouses.add(
          new PurchaseOrderListWarehouseOption(
              r.get("target_warehouse_id", UUID.class),
              r.get("target_warehouse_code", String.class),
              r.get("target_warehouse_name", String.class),
              r.get("cnt", Long.class)));
    }

    return new PurchaseOrderListFilterOptions(
        Arrays.stream(PurchaseOrderListStatus.values()).map(Enum::name).toList(),
        currencies,
        suppliers,
        warehouses,
        Arrays.stream(PurchaseOrderListSort.values())
            .map(s -> new PurchaseOrderListSortOption(s.name(), s.label()))
            .toList());
  }

  // ================================================================ 导出

  /**
   * 导出当前筛选结果（CSV）。
   *
   * <p>与列表<b>同一套筛选条件</b>（真导出筛选后的结果，不是导出一份全量）。
   * 金额<b>按币种分列</b>（本单币种 + 本位币列），<b>不做跨币种折算</b>；
   * 本位币列用<b>建单时冻结的汇率快照</b>，冻结不到的行本位币列<b>留空</b>而不是填 0 ——
   * 填 0 会被当成「这单金额是 0」。
   *
   * @return 导出行数与是否被截断
   */
  public PurchaseOrderExport exportCsv(PurchaseOrderListQuery query) {
    List<Object> binds = new ArrayList<>();
    List<String> where = conditions(query, binds);
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(MAX_EXPORT_ROWS + 1);
    List<Record> records =
        sql.fetch(
            "select o.order_no, o.status, o.supplier_code, o.supplier_name,"
                + " o.line_count, o.total_amount, o.currency, o.promised_arrival_date,"
                + " t.receivable_qty, t.received_qty,"
                + " " + OVERDUE + " as overdue,"
                + " o.target_warehouse_code, o.target_warehouse_name,"
                + " o.urge_count, o.last_urged_at, o.created_at,"
                + " fx.functional_currency, fx.rate"
                + fromClause()
                + " join lateral (select o.functional_currency as functional_currency,"
                + "   case when count(distinct l.fx_rate_to_functional) = 1"
                + "         and count(l.fx_rate_to_functional) = count(*)"
                + "    then min(l.fx_rate_to_functional) end as rate"
                + "   from purchase_order_line l"
                + "   where l.tenant_id = o.tenant_id and l.order_id = o.id) fx on true"
                + clause
                + " order by o.created_at desc, o.id desc"
                + " limit ?",
            pageBinds.toArray());

    boolean truncated = records.size() > MAX_EXPORT_ROWS;
    List<Record> kept = truncated ? records.subList(0, MAX_EXPORT_ROWS) : records;

    LocalDate today = dbToday();
    StringBuilder csv = new StringBuilder();
    csv.append(
        "采购单号,状态,供应商编码,供应商名称,SKU行数,总金额,币种,承诺到货日,"
            + "可收数量,已收数量,已收比例,逾期,逾期天数,目标仓编码,目标仓名称,"
            + "已催单次数,最近催单时间,创建时间,本位币,本位币金额\n");
    for (Record r : kept) {
      BigDecimal receivable = r.get("receivable_qty", BigDecimal.class);
      BigDecimal received = r.get("received_qty", BigDecimal.class);
      BigDecimal ratio = ratio(received, receivable);
      boolean overdue = Boolean.TRUE.equals(r.get("overdue", Boolean.class));
      LocalDate promised = r.get("promised_arrival_date", LocalDate.class);
      String functional = r.get("functional_currency", String.class);
      BigDecimal rate = r.get("rate", BigDecimal.class);
      BigDecimal functionalAmount =
          (functional == null || rate == null) ? null : r.get("total_amount", BigDecimal.class).multiply(rate);

      csv.append(cell(r.get("order_no", String.class))).append(',')
          .append(cell(r.get("status", String.class))).append(',')
          .append(cell(r.get("supplier_code", String.class))).append(',')
          .append(cell(r.get("supplier_name", String.class))).append(',')
          .append(String.valueOf(r.get("line_count", Integer.class))).append(',')
          .append(plain(r.get("total_amount", BigDecimal.class))).append(',')
          .append(cell(r.get("currency", String.class))).append(',')
          .append(promised == null ? "" : promised.toString()).append(',')
          .append(plain(receivable)).append(',')
          .append(plain(received)).append(',')
          .append(ratio == null ? "" : plain(ratio)).append(',')
          .append(overdue ? "是" : "否").append(',')
          .append(overdue ? String.valueOf(ChronoUnit.DAYS.between(promised, today)) : "").append(',')
          .append(cell(r.get("target_warehouse_code", String.class))).append(',')
          .append(cell(r.get("target_warehouse_name", String.class))).append(',')
          .append(String.valueOf(r.get("urge_count", Integer.class))).append(',')
          .append(plain(r.get("last_urged_at", OffsetDateTime.class))).append(',')
          .append(plain(r.get("created_at", OffsetDateTime.class))).append(',')
          .append(cell(functional)).append(',')
          .append(plain(functionalAmount))
          .append('\n');
    }
    return new PurchaseOrderExport(csv.toString(), kept.size(), truncated);
  }

  /** 导出结果：CSV 文本 + 行数 + 是否被截断。 */
  public record PurchaseOrderExport(String csv, int rowCount, boolean truncated) {}

  // ================================================================ 批量操作：催单

  /**
   * 批量催单。
   *
   * <p>逐单成败：有的单能催、有的不能催（已关闭 / 不在数据范围内）是常事。
   * 返回逐单结论，而不是整批一个布尔 —— 整批一个布尔会让人重试整批，
   * 于是把已经催过的单又催一遍。
   */
  public PurchaseOrderListBatchResult urge(
      List<UUID> orderIds, String note, UUID actorId, UUID actorDepartmentId, String scope) {
    List<UUID> ids = normalizeIds(orderIds);
    List<PurchaseOrderListBatchFailure> failures = new ArrayList<>();
    int ok = 0;
    OffsetDateTime now = dbNow();

    for (UUID id : ids) {
      Record head = headForUpdate(id, scope, actorId, actorDepartmentId);
      if (head == null) {
        failures.add(new PurchaseOrderListBatchFailure(id, "", "采购单不存在或不在当前数据范围内"));
        continue;
      }
      String orderNo = head.get("order_no", String.class);
      String status = head.get("status", String.class);
      if ("CLOSED".equals(status)) {
        failures.add(new PurchaseOrderListBatchFailure(id, orderNo, "已关闭的采购单不再催单"));
        continue;
      }
      sql.execute(
          "update purchase_order set urge_count = urge_count + 1, last_urged_at = ?::timestamptz,"
              + " updated_at = now(), version = version + 1"
              + " where id = ?::uuid and tenant_id = ?::uuid",
          now,
          id,
          currentTenant());
      appendEvent(id, "URGED", status, status, note, actorId, now);
      ok++;
    }
    return new PurchaseOrderListBatchResult(
        ids.size(),
        ok,
        failures,
        "催单只留痕（催单次数 + 最近催单时刻 + 留痕），**不改承诺到货日** —— "
            + "交期变更历史归 6.3.2.1 跟单。");
  }

  // ================================================================ 批量操作：关闭

  /**
   * 批量关闭。
   *
   * <p>关闭<b>必须填理由</b>（DB 层 CHECK + 触发器双保险）：「关掉了但不知道为什么」等于没关。
   * 关闭后整单冻结（数量、单价、供应商、承诺日不可再改），由 V254 的触发器保证。
   */
  public PurchaseOrderListBatchResult close(
      List<UUID> orderIds, String reason, UUID actorId, UUID actorDepartmentId, String scope) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("关闭采购单必须填理由：「关掉了但不知道为什么」等于没关");
    }
    List<UUID> ids = normalizeIds(orderIds);
    List<PurchaseOrderListBatchFailure> failures = new ArrayList<>();
    int ok = 0;
    OffsetDateTime now = dbNow();

    for (UUID id : ids) {
      Record head = headForUpdate(id, scope, actorId, actorDepartmentId);
      if (head == null) {
        failures.add(new PurchaseOrderListBatchFailure(id, "", "采购单不存在或不在当前数据范围内"));
        continue;
      }
      String orderNo = head.get("order_no", String.class);
      String status = head.get("status", String.class);
      if ("CLOSED".equals(status)) {
        failures.add(new PurchaseOrderListBatchFailure(id, orderNo, "这张采购单已经关闭了"));
        continue;
      }
      sql.execute(
          "update purchase_order set status = 'CLOSED', closed_at = ?::timestamptz,"
              + " close_reason = ?::varchar, updated_at = now(), version = version + 1"
              + " where id = ?::uuid and tenant_id = ?::uuid",
          now,
          reason,
          id,
          currentTenant());
      appendEvent(id, "CLOSED", status, "CLOSED", reason, actorId, now);
      ok++;
    }
    return new PurchaseOrderListBatchResult(
        ids.size(),
        ok,
        failures,
        "关闭后整单冻结：数量、单价、供应商、承诺到货日都不可再改（DB 触发器强制）。");
  }

  // ================================================================ 建采购单（上游 6.2.2.1 转单事实）

  /**
   * 由 6.2.2.1 的转单事实生成采购单。
   *
   * <p>为什么本模块有写入口：6.3.1.2（创建与编辑）未交付 ⇒ 本仓**没有任何采购单产生路径**，
   * 列表会永远是空的（那不是「本模块做完了」，那是「本模块没通」）。而 6.2.2.1 的
   * {@code purchase_request_conversion} 正是「转成采购单时的参数」的事实，
   * V246 明写「6.3.1.2 交付后，由它读这份转单事实去建真正的采购单」——
   * 本模块把这条路径先落地，6.3.1.2 交付后接手<b>手工建单与编辑</b>。
   *
   * <p>⚠️ **不静默丢弃**：被拒的转单事实逐条带原因码返回（丢掉的行等于少订的货）。
   *
   * <p>⚠️ **跨供应商不合单**：与 6.2.2.2「合并只在同一供应商内进行」同一口径 ——
   * 跨供应商合并会导致对账与付款错误。目标仓不同同理（一张采购单进一个仓）。
   */
  public PurchaseOrderListConversionResult createFromConversions(
      List<UUID> conversionIds, UUID actorId, UUID actorDepartmentId, String bearer) {
    List<UUID> ids = normalizeIds(conversionIds);
    if (ids.isEmpty()) {
      throw new IllegalArgumentException("至少要给一条 6.2.2.1 的转单事实");
    }

    List<Object> idBinds = new ArrayList<>();
    StringBuilder placeholders = new StringBuilder();
    for (int i = 0; i < ids.size(); i++) {
      placeholders.append(i == 0 ? "?" : ",?").append("::uuid");
      idBinds.add(ids.get(i));
    }

    List<Record> facts =
        sql.fetch(
            "select c.id as conversion_id, c.request_id, c.request_line_id,"
                + " c.supplier_id, c.supplier_code, c.supplier_name,"
                + " c.converted_quantity, c.converted_arrival_date,"
                + " c.target_warehouse_id, c.target_warehouse_code, c.target_warehouse_name,"
                + " l.sku_id, l.sku_code, l.sku_name, l.unit_price,"
                + " r.request_no, r.currency_code,"
                + " r.legal_entity_id, r.legal_entity_code, r.legal_entity_name"
                + " from purchase_request_conversion c"
                + " join purchase_request_line l on l.tenant_id = c.tenant_id and l.id = c.request_line_id"
                + " join purchase_request r on r.tenant_id = c.tenant_id and r.id = c.request_id"
                + " where c.id in ("
                + placeholders
                + ") order by c.converted_at, c.id",
            idBinds.toArray());

    List<PurchaseOrderListRejectedConversion> rejected = new ArrayList<>();
    Map<UUID, Record> byId = new LinkedHashMap<>();
    for (Record r : facts) {
      byId.put(r.get("conversion_id", UUID.class), r);
    }

    // 找不到的（不存在 / 不属于本租户）也要如实报出来，不静默忽略。
    for (UUID id : ids) {
      if (!byId.containsKey(id)) {
        rejected.add(
            new PurchaseOrderListRejectedConversion(
                id, "CONVERSION_NOT_FOUND", "转单事实不存在或不属于当前租户"));
      }
    }

    // ⚠️ **一条转单事实只能被消费一次**：重复转单 = 对供应商承诺两次。
    //   DB 上有唯一约束兜底（purchase_order_line.source_conversion_id），
    //   但那会炸成一条看不懂的 SQL 错误 ⇒ 这里先查出来，如实报「已转过」。
    java.util.Set<UUID> alreadyUsed = new java.util.LinkedHashSet<>();
    if (!byId.isEmpty()) {
      List<Object> usedBinds = new ArrayList<>();
      StringBuilder usedPlaceholders = new StringBuilder();
      for (int i = 0; i < byId.size(); i++) {
        usedPlaceholders.append(i == 0 ? "?" : ",?").append("::uuid");
        usedBinds.add(byId.keySet().toArray(new UUID[0])[i]);
      }
      for (Record used :
          sql.fetch(
              "select distinct l.source_conversion_id from purchase_order_line l"
                  + " where l.source_conversion_id in ("
                  + usedPlaceholders
                  + ")",
              usedBinds.toArray())) {
        UUID cid = used.get("source_conversion_id", UUID.class);
        rejected.add(
            new PurchaseOrderListRejectedConversion(
                cid,
                "CONVERSION_ALREADY_USED",
                "这条转单事实已经转成过采购单了（一行只能转一次单：重复转单会造出两份对供应商的承诺）"));
        alreadyUsed.add(cid);
      }
    }

    // 基准供应商 / 目标仓 / 币种取第一条**通过基础校验**的事实。
    Record base = null;
    for (Record r : facts) {
      if (alreadyUsed.contains(r.get("conversion_id", UUID.class))) {
        continue;
      }
      if (r.get("unit_price", BigDecimal.class) == null) {
        rejected.add(
            new PurchaseOrderListRejectedConversion(
                r.get("conversion_id", UUID.class),
                "UNIT_PRICE_MISSING",
                "申请行没有预估单价，转不成采购单（采购单必须有金额，否则列表上的总金额是假的）"));
        continue;
      }
      if (r.get("currency_code", String.class) == null) {
        rejected.add(
            new PurchaseOrderListRejectedConversion(
                r.get("conversion_id", UUID.class),
                "CURRENCY_MISSING",
                "采购申请单没有币种，转不成采购单（一张采购单一个币种，币种缺失会破坏跨币种汇总）"));
        continue;
      }
      base = r;
      break;
    }
    if (base == null) {
      return new PurchaseOrderListConversionResult(
          null, null, 0, null, null, null, "NOT_APPLICABLE", rejected);
    }

    UUID supplierId = base.get("supplier_id", UUID.class);
    UUID warehouseId = base.get("target_warehouse_id", UUID.class);
    String currency = base.get("currency_code", String.class);

    List<Record> accepted = new ArrayList<>();
    for (Record r : facts) {
      UUID cid = r.get("conversion_id", UUID.class);
      if (alreadyUsed.contains(cid)) {
        continue;
      }
      if (!supplierId.equals(r.get("supplier_id", UUID.class))) {
        rejected.add(
            new PurchaseOrderListRejectedConversion(
                cid,
                "SUPPLIER_MISMATCH",
                "与本单供应商不同，不能合单（跨供应商合并会导致对账与付款错误）"));
        continue;
      }
      if (!warehouseId.equals(r.get("target_warehouse_id", UUID.class))) {
        rejected.add(
            new PurchaseOrderListRejectedConversion(
                cid,
                "WAREHOUSE_MISMATCH",
                "与本单目标仓不同，不能合单（一张采购单进一个仓）"));
        continue;
      }
      if (!currency.equals(r.get("currency_code", String.class))) {
        rejected.add(
            new PurchaseOrderListRejectedConversion(
                cid, "CURRENCY_MISMATCH", "与本单币种不同，不能合单（一张采购单一个币种）"));
        continue;
      }
      if (r.get("unit_price", BigDecimal.class) == null
          || r.get("currency_code", String.class) == null) {
        continue; // 已在上面报过原因
      }
      accepted.add(r);
    }
    if (accepted.isEmpty()) {
      return new PurchaseOrderListConversionResult(
          null, null, 0, null, null, null, "NOT_APPLICABLE", rejected);
    }

    // ---- 汇率快照（建单那一刻冻结；取不到就是没有，绝不 1:1）----
    FxSnapshot fx = captureFx(currency, base.get("legal_entity_id", UUID.class), bearer);

    LocalDate promised = accepted.stream()
        .map(r -> r.get("converted_arrival_date", LocalDate.class))
        .min(LocalDate::compareTo)
        .orElseThrow();

    UUID orderId = UUID.randomUUID();
    long seq = sql.fetchOne("select nextval('purchase_order_no_seq')").get(0, Long.class);
    String orderNo = "PO-" + seq;
    UUID tenantId = currentTenant();
    OffsetDateTime now = dbNow();

    sql.execute(
        "insert into purchase_order(id,tenant_id,order_no,source_type,source_request_id,source_request_no,"
            + " supplier_ref_id,supplier_code,supplier_name,legal_entity_id,legal_entity_code,legal_entity_name,"
            + " currency,functional_currency,fx_snapshot_at,status,promised_arrival_date,"
            + " target_warehouse_id,target_warehouse_code,target_warehouse_name,"
            + " owner_user_id,owner_department_id,urge_count,last_urged_at,closed_at,close_reason,"
            + " note,created_by,created_at,updated_at,version)"
            + " values (?::uuid,?::uuid,?::varchar,'PURCHASE_REQUEST_CONVERSION',?::uuid,?::varchar,"
            + " ?::uuid,?::varchar,?::varchar,?::uuid,?::varchar,?::varchar,"
            + " ?::varchar,?::varchar,?::timestamptz,'DRAFT',?::date,"
            + " ?::uuid,?::varchar,?::varchar,"
            + " ?::uuid,?::uuid,0,null,null,'',?::varchar,?::uuid,?::timestamptz,?::timestamptz,0)",
        orderId,
        tenantId,
        orderNo,
        base.get("request_id", UUID.class),
        base.get("request_no", String.class),
        supplierId,
        base.get("supplier_code", String.class),
        base.get("supplier_name", String.class),
        base.get("legal_entity_id", UUID.class),
        base.get("legal_entity_code", String.class),
        base.get("legal_entity_name", String.class),
        currency,
        fx.functionalCurrency(),
        fx.captured() ? now : null,
        promised,
        warehouseId,
        base.get("target_warehouse_code", String.class),
        base.get("target_warehouse_name", String.class),
        actorId,
        actorDepartmentId,
        "由 6.2.2.1 的转单事实生成",
        actorId,
        now,
        now);

    int lineNo = 0;
    BigDecimal total = BigDecimal.ZERO;
    for (Record r : accepted) {
      lineNo++;
      BigDecimal qty = r.get("converted_quantity", BigDecimal.class);
      BigDecimal price = r.get("unit_price", BigDecimal.class);
      total = total.add(qty.multiply(price));
      sql.execute(
          "insert into purchase_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
              + " source_conversion_id,source_request_line_id,quantity,unit_code,unit_price,line_amount,"
              + " fx_rate_to_functional,fx_rate_base_currency,fx_rate_effective_date,price_source,note,"
              + " created_at,updated_at)"
              + " values (?::uuid,?::uuid,?::uuid,?::integer,?::uuid,?::varchar,?::varchar,"
              + " ?::uuid,?::uuid,?::numeric,'',?::numeric,?::numeric,"
              + " ?::numeric,?::varchar,?::date,'REQUEST_ESTIMATE','',?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          tenantId,
          orderId,
          lineNo,
          r.get("sku_id", UUID.class),
          r.get("sku_code", String.class),
          r.get("sku_name", String.class),
          r.get("conversion_id", UUID.class),
          r.get("request_line_id", UUID.class),
          qty,
          price,
          qty.multiply(price),
          fx.rate(),
          fx.captured() ? currency : null,
          fx.effectiveDate(),
          now,
          now);
    }

    appendEvent(orderId, "CREATED", null, "DRAFT", "由 6.2.2.1 转单事实生成", actorId, now);

    return new PurchaseOrderListConversionResult(
        orderId,
        orderNo,
        lineNo,
        total,
        currency,
        fx.functionalCurrency(),
        fx.status(),
        rejected);
  }

  /** 汇率快照捕获结果。 */
  private record FxSnapshot(
      String functionalCurrency,
      BigDecimal rate,
      LocalDate effectiveDate,
      String status) {

    boolean captured() {
      return functionalCurrency != null && rate != null;
    }
  }

  /**
   * 捕获本位币与汇率快照（建单那一刻冻结）。
   *
   * <p>口径照 6.5.2.1 采购分析的先例（{@link PurchaseAnalyticsCatalogPort}）：
   * 本位币来自 catalog 的法人档案（5.2.2.2 的 functional-currencies 端点），
   * 汇率取「发生日当天或之前最新的一条」（5.2.2.2 的汇率台账）。
   *
   * <p>⚠️ <b>取不到就明确记 UNAVAILABLE</b>，<b>绝不</b>按 1:1 或固定汇率填一个数 ——
   * 一个假汇率会让「跨币种合计」变成一个看起来能对账、其实对不上的数。
   * 也不会因为汇率服务不可达就<b>拒绝建单</b>：采购单本身是对供应商的承诺，
   * 它不该被一个「算合计用」的辅助能力卡住。
   */
  private FxSnapshot captureFx(String currency, UUID legalEntityId, String bearer) {
    if (legalEntityId == null) {
      return new FxSnapshot(null, null, null, "NOT_APPLICABLE");
    }
    String functional;
    try {
      functional =
          catalog.functionalCurrencies(bearer).stream()
              .filter(r -> legalEntityId.equals(r.legalEntityId()))
              .map(r -> r.baseCurrency())
              .findFirst()
              .orElse(null);
    } catch (RuntimeException e) {
      return new FxSnapshot(null, null, null, "UNAVAILABLE");
    }
    if (functional == null) {
      return new FxSnapshot(null, null, null, "UNAVAILABLE");
    }
    if (functional.equals(currency)) {
      // 本位币就是本单币种：不需要折算，汇率恒为 1（这不是「固定汇率」，是恒等式）。
      return new FxSnapshot(functional, BigDecimal.ONE, dbToday(), "CAPTURED");
    }
    LocalDate today = dbToday();
    try {
      List<PurchaseAnalyticsCatalogPort.RateView> rates =
          catalog.rates(currency, functional, today.minusDays(30), today, bearer);
      return rates.stream()
          .filter(r -> r.effectiveDate() != null && !r.effectiveDate().isAfter(today))
          .max((a, b) -> a.effectiveDate().compareTo(b.effectiveDate()))
          .map(r -> new FxSnapshot(functional, r.rate(), r.effectiveDate(), "CAPTURED"))
          .orElseGet(() -> new FxSnapshot(functional, null, null, "UNAVAILABLE"));
    } catch (RuntimeException e) {
      return new FxSnapshot(functional, null, null, "UNAVAILABLE");
    }
  }

  // ================================================================ 内部

  /** FROM 子句：采购单 + 6.3.2.2 到货跟踪 + 最近一次到货登记的 warehouse 收货单号快照。 */
  private static String fromClause() {
    return " from purchase_order o"
        + " left join purchase_arrival_tracking t"
        + "   on t.tenant_id = o.tenant_id and t.purchase_order_no = o.order_no"
        + " left join lateral ("
        + "   select r.warehouse_receipt_code, r.arrival_seq"
        + "     from purchase_arrival_receipt r"
        + "    where r.tenant_id = o.tenant_id and r.tracking_id = t.id"
        + "    order by r.arrival_seq desc limit 1"
        + " ) lr on true";
  }

  /** WHERE 条件。**每一条都对应一个真实存在的列或一条真实存在的聚合。 */
  private List<String> conditions(PurchaseOrderListQuery q, List<Object> binds) {
    List<String> where = new ArrayList<>();
    if (q.supplierRefId() != null) {
      where.add("o.supplier_ref_id = ?::uuid");
      binds.add(q.supplierRefId());
    }
    if (isSet(q.status())) {
      where.add("o.status = ?::varchar");
      binds.add(parseStatus(q.status()));
    }
    if (q.targetWarehouseId() != null) {
      where.add("o.target_warehouse_id = ?::uuid");
      binds.add(q.targetWarehouseId());
    }
    if (isSet(q.currency())) {
      String currency = q.currency().trim().toUpperCase();
      if (!currency.matches("^[A-Z]{3}$")) {
        throw new IllegalArgumentException("币种必须是三位大写字母");
      }
      where.add("o.currency = ?::varchar");
      binds.add(currency);
    }
    if (q.skuId() != null) {
      where.add(
          "exists (select 1 from purchase_order_line l"
              + " where l.tenant_id = o.tenant_id and l.order_id = o.id and l.sku_id = ?::uuid)");
      binds.add(q.skuId());
    }
    // 时间筛选：?::timestamptz + OffsetDateTime，禁 Timestamp.valueOf（静默偏 −28800s）。
    if (q.createdFrom() != null) {
      where.add("o.created_at >= ?::timestamptz");
      binds.add(q.createdFrom());
    }
    if (q.createdTo() != null) {
      where.add("o.created_at <= ?::timestamptz");
      binds.add(q.createdTo());
    }
    if (q.promisedFrom() != null) {
      where.add("o.promised_arrival_date >= ?::date");
      binds.add(q.promisedFrom());
    }
    if (q.promisedTo() != null) {
      where.add("o.promised_arrival_date <= ?::date");
      binds.add(q.promisedTo());
    }
    if (Boolean.TRUE.equals(q.overdueOnly())) {
      where.add(OVERDUE);
    }
    if (isSet(q.keyword())) {
      // 真实模糊检索：采购单号、供应商编码或供应商名。
      // 刻意**不含**金额 / 日期的模糊匹配 —— 那会让「搜 100」返回一堆无关单。
      String pattern = "%" + q.keyword().trim() + "%";
      where.add(
          "(o.order_no ilike ?::varchar or o.supplier_code ilike ?::varchar"
              + " or o.supplier_name ilike ?::varchar)");
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }
    // 数据范围：ALL / DEPARTMENT / SELF。**不接受请求参数传入**。
    String scope = q.dataScope() == null ? "ALL" : q.dataScope();
    if ("DEPARTMENT".equals(scope)) {
      if (q.actorDepartmentId() == null) {
        throw new IllegalArgumentException("数据范围限定为部门时必须能解析出当前成员所属部门");
      }
      where.add("o.owner_department_id = ?::uuid");
      binds.add(q.actorDepartmentId());
    } else if ("SELF".equals(scope)) {
      if (q.actorId() == null) {
        throw new IllegalArgumentException("数据范围限定为本人时必须能解析出操作人");
      }
      where.add("o.owner_user_id = ?::uuid");
      binds.add(q.actorId());
    }
    return where;
  }

  /**
   * 汇总条：总数、逾期数、各状态单数、<b>按币种分组</b>的金额、本位币合计。
   *
   * <p>统计的是<b>命中筛选条件的全部行</b>（与分页无关），而不是当前页。
   */
  private PurchaseOrderListSummary summary(
      String from, String clause, List<Object> binds, LocalDate today) {
    Record head =
        sql.fetchOne(
            "select count(distinct o.id) as total,"
                + " count(distinct o.id) filter (where " + OVERDUE + ") as overdue,"
                + " count(distinct o.id) filter (where o.status = 'DRAFT') as draft,"
                + " count(distinct o.id) filter (where o.status = 'PENDING_APPROVAL') as pending_approval,"
                + " count(distinct o.id) filter (where o.status = 'APPROVED') as approved,"
                + " count(distinct o.id) filter (where o.status = 'REJECTED') as rejected,"
                + " count(distinct o.id) filter (where o.status = 'IN_FULFILLMENT') as in_fulfillment,"
                + " count(distinct o.id) filter (where o.status = 'CLOSED') as closed"
                + from,
            binds.toArray());

    List<PurchaseOrderListStatusCount> byStatus = new ArrayList<>();
    for (PurchaseOrderListStatus s : PurchaseOrderListStatus.values()) {
      String column =
          switch (s) {
            case DRAFT -> "draft";
            case PENDING_APPROVAL -> "pending_approval";
            case APPROVED -> "approved";
            case REJECTED -> "rejected";
            case IN_FULFILLMENT -> "in_fulfillment";
            case CLOSED -> "closed";
          };
      byStatus.add(new PurchaseOrderListStatusCount(s.name(), s.label(), head.get(column, Long.class)));
    }

    List<PurchaseOrderListCurrencyAmount> amounts = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select o.currency, sum(o.total_amount) as amount, count(distinct o.id) as cnt"
                + from
                + " group by o.currency order by o.currency",
            binds.toArray())) {
      amounts.add(
          new PurchaseOrderListCurrencyAmount(
              r.get("currency", String.class), r.get("amount", BigDecimal.class), r.get("cnt", Long.class)));
    }

    PurchaseOrderListFunctionalTotal functional = functionalTotal(clause, binds);

    return new PurchaseOrderListSummary(
        head.get("total", Long.class),
        head.get("overdue", Long.class),
        byStatus,
        amounts,
        functional,
        "总金额**按币种分组**：USD 与 EUR 加成一个数没有意义（那不是任何一个金额）。"
            + "跨币种的合计只在能拿到建单时冻结的汇率快照时才给（见 functionalTotal），"
            + "拿不到就只报原币种，绝不按 1:1 或固定汇率折算。");
  }

  /**
   * 本位币合计：用<b>建单时冻结的汇率快照</b>换算。
   *
   * <p>冻结不到汇率快照、或各单本位币不一致的单<b>不参与</b>合计，并如实报出被排除的数量与币种。
   */
  private PurchaseOrderListFunctionalTotal functionalTotal(String clause, List<Object> binds) {
    // ⚠️ LATERAL 子查询必须挂在 **FROM 之后、WHERE 之前** —— 拼在 where 后面是语法错误。
    String from =
        fromClause()
            + " join lateral (select"
            + "   case when count(distinct l.fx_rate_to_functional) = 1"
            + "         and count(l.fx_rate_to_functional) = count(*)"
            + "    then min(l.fx_rate_to_functional) end as rate"
            + "   from purchase_order_line l"
            + "   where l.tenant_id = o.tenant_id and l.order_id = o.id) fx on true"
            + clause;
    Record r =
        sql.fetchOne(
            "select count(distinct o.functional_currency) as currency_variants,"
                + " min(o.functional_currency) as functional_currency,"
                + " coalesce(sum(case when fx.rate is not null then o.total_amount * fx.rate else 0 end), 0)"
                + "   as amount,"
                + " count(distinct o.id) filter (where fx.rate is not null) as included,"
                + " count(distinct o.id) filter (where fx.rate is null) as excluded,"
                + " coalesce(string_agg(distinct o.currency, ',' order by o.currency)"
                + "   filter (where fx.rate is null), '') as excluded_currencies"
                + from,
            binds.toArray());

    long variants = r.get("currency_variants", Long.class);
    String functional = r.get("functional_currency", String.class);
    long included = r.get("included", Long.class);
    long excluded = r.get("excluded", Long.class);
    List<String> excludedCurrencies = splitCsv(r.get("excluded_currencies", String.class));

    if (variants > 1) {
      return null;
    }
    if (functional == null) {
      return null;
    }
    if (excluded > 0) {
      return new PurchaseOrderListFunctionalTotal(
          functional,
          r.get("amount", BigDecimal.class),
          included,
          excluded,
          excludedCurrencies,
          "有 " + excluded + " 张采购单在建单时**没冻结到汇率快照**（汇率服务当时不可用或缺当天的汇率），"
              + "它们没有参与本位币合计 —— 宁可少报一个合计，也不报一个用固定汇率凑出来的数。"
              + "涉及币种：" + (excludedCurrencies.isEmpty() ? "（无）" : String.join("/", excludedCurrencies)));
    }
    return new PurchaseOrderListFunctionalTotal(
        functional,
        r.get("amount", BigDecimal.class),
        included,
        0,
        List.of(),
        "本位币合计用**建单那一刻冻结的汇率快照**换算（不是列表读取时的实时汇率），"
            + "所以历史数字不会因为汇率变动而漂移。");
  }

  /** 读一张采购单（按当前数据范围），读不到就是「不存在或不在范围内」。 */
  private Record headForUpdate(
      UUID id, String scope, UUID actorId, UUID actorDepartmentId) {
    List<Object> binds = new ArrayList<>();
    binds.add(id);
    String clause = "";
    if ("DEPARTMENT".equals(scope) && actorDepartmentId != null) {
      clause = " and o.owner_department_id = ?::uuid";
      binds.add(actorDepartmentId);
    } else if ("SELF".equals(scope)) {
      clause = " and o.owner_user_id = ?::uuid";
      binds.add(actorId);
    }
    return sql.fetchOne(
        "select o.id, o.order_no, o.status, o.version from purchase_order o where o.id = ?::uuid" + clause,
        binds.toArray());
  }

  /** 追加一条留痕。 */
  private void appendEvent(
      UUID orderId, String type, String fromStatus, String toStatus, String note, UUID actorId,
      OffsetDateTime occurredAt) {
    sql.execute(
        "insert into purchase_order_event(id,tenant_id,order_id,event_type,from_status,to_status,"
            + " note,actor_id,occurred_at,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,"
            + " ?::varchar,?::uuid,?::timestamptz,now())",
        UUID.randomUUID(),
        currentTenant(),
        orderId,
        type,
        fromStatus,
        toStatus,
        note == null ? "" : note,
        actorId,
        occurredAt);
  }

  private static PurchaseOrderListRow row(Record r, LocalDate today) {
    BigDecimal receivable = r.get("receivable_qty", BigDecimal.class);
    BigDecimal received = r.get("received_qty", BigDecimal.class);
    boolean tracked = r.get("arrival_status", String.class) != null || receivable != null;
    boolean overdue = Boolean.TRUE.equals(r.get("overdue", Boolean.class));
    LocalDate promised = r.get("promised_arrival_date", LocalDate.class);
    Integer overdueDays =
        overdue && promised != null ? (int) ChronoUnit.DAYS.between(promised, today) : null;

    return new PurchaseOrderListRow(
        r.get("id", UUID.class),
        r.get("order_no", String.class),
        r.get("status", String.class),
        r.get("supplier_ref_id", UUID.class),
        r.get("supplier_code", String.class),
        r.get("supplier_name", String.class),
        r.get("line_count", Integer.class),
        r.get("sku_count", Integer.class),
        r.get("total_amount", BigDecimal.class),
        r.get("currency", String.class),
        promised,
        receivable,
        received,
        ratio(received, receivable),
        tracked ? "ARRIVAL_TRACKING" : "NOT_TRACKED",
        r.get("arrival_status", String.class),
        r.get("warehouse_receipt_code", String.class),
        overdue,
        overdueDays,
        r.get("target_warehouse_id", UUID.class),
        r.get("target_warehouse_code", String.class),
        r.get("target_warehouse_name", String.class),
        r.get("owner_user_id", UUID.class),
        r.get("owner_department_id", UUID.class),
        r.get("urge_count", Integer.class),
        r.get("last_urged_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /** 已收比例（0~1，scale 4，HALF_UP）。没跟踪或没数得出来就是 null，不是 0。 */
  private static BigDecimal ratio(BigDecimal received, BigDecimal receivable) {
    if (received == null || receivable == null || receivable.signum() == 0) {
      return null;
    }
    return received.divide(receivable, 4, RoundingMode.HALF_UP);
  }

  private static String parseStatus(String raw) {
    try {
      return PurchaseOrderListStatus.valueOf(raw.trim().toUpperCase()).name();
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "采购单状态只能是 "
              + Arrays.stream(PurchaseOrderListStatus.values()).map(Enum::name).toList());
    }
  }

  private static List<UUID> normalizeIds(List<UUID> ids) {
    List<UUID> out = new ArrayList<>();
    if (ids == null) {
      return out;
    }
    LinkedHashSet<UUID> seen = new LinkedHashSet<>();
    for (UUID id : ids) {
      if (id != null) {
        seen.add(id);
      }
    }
    out.addAll(seen);
    return out;
  }

  private static List<String> splitCsv(String raw) {
    List<String> out = new ArrayList<>();
    if (raw == null || raw.isBlank()) {
      return out;
    }
    for (String s : raw.split(",")) {
      if (!s.isBlank()) {
        out.add(s.trim());
      }
    }
    return out;
  }

  private static String scopeNote(String scope) {
    return switch (scope == null ? "ALL" : scope) {
      case "ALL" -> "数据范围 ALL：本租户全部采购单。多租户隔离由 RLS 在数据库层强制，与本范围无关。";
      case "DEPARTMENT" -> "数据范围 DEPARTMENT：只显示当前成员所属部门的采购单"
          + "（purchase_order.owner_department_id）。部门来自已认证的成员关系，不接受请求参数传入。";
      default -> "数据范围 SELF：只显示本人负责的采购单（purchase_order.owner_user_id = 本人）。"
          + "本人身份来自已认证的成员关系，不接受请求参数传入。";
    };
  }

  /** CSV 单元格转义（RFC 4180）+ **公式注入防护**。 */
  private static String cell(String value) {
    if (value == null) {
      return "";
    }
    String v = value;
    // 公式注入：以 = + - @ 开头的单元格在 Excel 里会被当公式执行。
    if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) {
      v = "'" + v;
    }
    if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
      return '"' + v.replace("\"", "\"\"") + '"';
    }
    return v;
  }

  /** 数值 / 时间的纯文本形式（不加引号，避免 Excel 把它当公式）。 */
  private static String plain(Object value) {
    if (value == null) {
      return "";
    }
    return value.toString();
  }

  /** 数据库时钟（UTC）。 */
  private OffsetDateTime dbNow() {
    return sql.fetchOne("select now()").get(0, OffsetDateTime.class);
  }

  /** 数据库口径的今天（UTC 日历日）。 */
  private LocalDate dbToday() {
    return sql.fetchOne("select (now() at time zone 'UTC')::date").get(0, LocalDate.class);
  }

  /** 当前绑定的租户（由 tenancy 库在事务内设置，<b>不是</b>客户端头里的那个）。 */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant', true),'')::uuid")
        .get(0, UUID.class);
  }

  private static boolean isSet(String v) {
    return v != null && !v.isBlank();
  }

  /** 供控制器复用的权限与范围常量。 */
  public static final String READ_PERMISSION = "trade.purchase.read";
  public static final String UPDATE_PERMISSION = "trade.purchase.update";

  /** 数据范围常量。 */
  public static final String SCOPE_ALL = "ALL";
  public static final String SCOPE_DEPARTMENT = "DEPARTMENT";
  public static final String SCOPE_SELF = "SELF";

  /** 兜底：把未知异常翻译成 400（参数问题），避免 500 泄漏栈信息。 */
  static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
