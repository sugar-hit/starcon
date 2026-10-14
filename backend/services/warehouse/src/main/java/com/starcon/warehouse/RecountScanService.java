package com.starcon.warehouse;

import static com.starcon.warehouse.RecountModels.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
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
 * 8.3.3.1 复核扫描：开单、逐件扫描、结算判定、异常转人工。
 *
 * <p>蓝图 08 卷 8.3.3.1 的四问逐条对应：
 *
 * <ul>
 *   <li><b>职责</b>：逐件扫描商品与订单核对 → {@link #scan}；检出漏件/错件/多件 →
 *       {@link #complete}（结算时逐行比订单明细）；支持开箱复核 → {@link #openBox}。
 *   <li><b>输入</b>：扫描记录（条码/序列号）→ {@link #scan}；订单明细 → 开单时读
 *       {@code outbound_order_line}（V72）冻结成 {@code recount_line}；复核规则 →
 *       {@link #createRule} 与开单时选的 {@code ruleId}。
 *   <li><b>输出</b>：复核通过/异常结果 → {@link #complete}；异常单转人工处理 →
 *       {@link #escalate} 与 {@link #resolveException}。
 *   <li><b>关系</b>：上游 8.3.2.1 拣货任务（读 {@code pick_task*} 三张表接手）与
 *       8.3.2.2 波次（经 pick_task → pick_wave_task 读出，不直接挂外键）；
 *       下游 8.3.3.2 打包（通过时把出库单推到「待打包」，<b>不是已发货</b>）。
 * </ul>
 *
 * <p><b>「复核是错发拦截的最后机会」在本模块的落点</b>：{@link #complete} 判通过要
 * 同时满足四件事 —— 每一行都核过、序列号集合相等、没有未处理的异常、出库单本来就在
 * 「待复核」。这四条同时在 DB 层（V117 的 {@code guard_recount_session_status_fn}）
 * 拒一遍，所以「先放行再补异常」这条路绕开服务层也走不通。
 *
 * <p><b>复核不动库存</b>：货在打包区里 on_hand / reserved 一动不动，
 * <b>不写 inventory_entry</b>，预占不释放。抓到拣错/漏件时的出口是
 * <b>退回拣货</b>（outbound_order 走 PENDING_RECHECK → PICKING，由 8.3.2.1 的 reopen
 * 接住），不是本模块偷偷改库存。这与 8.3.2.1「拣货只是把货搬到打包区」同一纪律。
 *
 * <p><b>序列号为什么不能只比件数</b>：应发 3 件、实拣 3 件、实扫 3 件 —— 件数全对就
 * 放行？不行：扫的可能是 S1、S2、S9。所以 {@link #serialMatch} 用两个
 * <b>差集查询</b>判集合相等（{@code pick_task_serial} 减 {@code recount_scan}，
 * 反向再减一次），不是 {@code count} 相等。
 */
@Service
public class RecountScanService {

  private static final int MAX_PAGE_SIZE = 200;

  /** 排序白名单。与 PickTaskService / PickWaveService 同一纪律。 */
  private static final List<String> SORTS =
      List.of("STARTED_AT", "SESSION_CODE", "BASELINE_QUANTITY", "STATUS");

  /** 状态词表，与 V117 的 CHECK 逐字一致。 */
  private static final List<String> STATUSES =
      List.of("OPEN", "PASSED", "FAILED", "CANCELLED");

  /** 幂等锚点：V1 的 inventory_transaction.ref_type 语义（本模块不进 inventory_transaction）。 */
  private static final String REF_TYPE = "RECOUNT_SCAN";

  /**
   * 复核口径原文。随响应返回（{@code RecountCapabilities.verifyBasis} 与每张复核单的
   * {@code verifyBasis}），不只写在注释里 —— 看复核单的人有权知道
   * 「这一单是按什么口径核的」。
   */
  public static final String VERIFY_BASIS =
      "逐行以订单明细（outbound_order_line.quantity，开单时冻结）为应发基线，"
          + "实扫按 scanned_value 去重累计（重扫不重复计件，另记重复扫异常）；"
          + "实拣登记过序列号且规则要求逐件核对的行，必须逐个序列号集合相等才判核过，"
          + "件数相等而序列号集合不相等一律判「序列号不符」；"
          + "复核不写库存分录、不释放预占，检出拣错/漏件时的出口是退回拣货。";

  /**
   * 高价值口径原文。
   *
   * <p><b>刻意说清它不是金额</b>：出库单上没有金额列（V72 §1 明写「不存类目/售价/成本」，
   * 金额的 owner 是 9 订单履约侧，跨服务读表被模块边界纪律禁止），
   * 所以本模块的「高价值」以<b>应发件数</b>作唯一有账可依的代理口径。
   */
  public static final String HIGH_VALUE_BASIS =
      "「高价值订单」在本服务只能以应发件数作代理口径：出库单上没有金额列"
          + "（outbound_order / outbound_order_line 不存售价与成本，COPY 语义），"
          + "金额的 owner 是 9 订单履约侧，跨服务读表被模块边界纪律禁止。"
          + "因此高价值阈值取的是 recount_rule.high_value_quantity_threshold（件数），"
          + "不是金额；等 9 侧提供授权口径后再换列，而不是现在造一个算不出来的金额。";

  private final DSLContext sql;

  public RecountScanService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明。
   *
   * <p>与既有 warehouse 模块一致：把「哪些输入真有依据」「哪条不能填默认值」
   * 显式报出来，而不是让调用方从 400 或空数组里猜。
   */
  public RecountCapabilities capabilities() {
    return new RecountCapabilities(
        REF_TYPE,
        true,
        true,
        true,
        true,
        true,
        true,
        true,
        true,
        true,
        false,
        "8.3.2.1 拣货任务（复核只读不搬，货与预占都还在打包区）",
        true,
        true,
        false,
        "8.3.3.2 打包与称重",
        STATUSES,
        List.of(
            "MISSING_ITEM", "WRONG_ITEM", "EXTRA_ITEM",
            "DUPLICATE_SCAN", "SERIAL_MISMATCH", "UNKNOWN_BARCODE"),
        List.of("PENDING", "MANUAL", "CORRECTED", "ACCEPTED", "IGNORED"),
        SORTS,
        VERIFY_BASIS,
        HIGH_VALUE_BASIS,
        List.of(
            new RecountUpstreamModule(
                "8.3.2.1", "拣货任务", "warehouse", true,
                "按 pick_task_id 真外键接手：开单时读 pick_task / pick_task_line / "
                    + "pick_task_pick / pick_task_serial 取实拣事实与序列号，"
                    + "并要求该拣货任务已完成（未完成的货还没到打包区，核了也核不准）。"),
            new RecountUpstreamModule(
                "8.3.2.2", "波次与合并拣货", "warehouse", true,
                "经 pick_task → pick_wave_task 读出所属波次用于展示，不在复核单上挂外键："
                    + "波次是排班且成员会被 detach/rebind，钉一列会静默过期。"
                    + "合并不改变订单粒度，复核始终按出库单逐张做。"),
            new RecountUpstreamModule(
                "8.3.1.1", "出库单列表", "warehouse", true,
                "读 outbound_order_line.quantity 作应发基线并冻结成 recount_line；"
                    + "复核单只允许在出库单处于「待复核」（PENDING_RECHECK）时开。"
                    + "V72 §4.3 的状态机把 PENDING_RECHECK → PENDING_PACKING 留给本模块。")),
        List.of(
            new RecountDownstreamModule(
                "8.3.3.2", "打包与称重", "warehouse", false,
                "复核通过时本模块把出库单推到「待打包」（PENDING_PACKING）并结束。"
                    + "打包与称重尚未交付 —— capabilities 如实报 live=false，"
                    + "通过的单会停在「待打包」等人接手，不会假装已经打了包。"),
            new RecountDownstreamModule(
                "7 库存", "库存与预占", "warehouse", true,
                "本模块**不动** inventory_balance / stock_bucket / inventory_entry，"
                    + "也不释放预占：复核是只读核对动作，货与占用都还在打包区。"
                    + "释放预占是发货（8.3.4.1）或取消时 7 库存的事。"),
            new RecountDownstreamModule(
                "8.3.2.1", "拣货任务（返工）", "warehouse", true,
                "复核判异常时可以把出库单退回「拣货中」（PENDING_RECHECK → PICKING），"
                    + "由 8.3.2.1 的 reopen 接住重拣。**该动作刻意不在本模块的服务层**："
                    + "退回拣货会作废本次复核单的基线归属，是一次跨模块的状态回退，"
                    + "由调用方显式发起，不由复核单自动触发。")));
  }

  // ================================================================ 复核规则

  @Transactional
  public RecountRuleRow createRule(RecountRuleInput input, UUID actorId) {
    UUID tenant = currentTenant();
    String code = text(input.ruleCode());
    if (code.isEmpty()) throw bad("ruleCode 不能为空");
    String name = text(input.name());
    if (name.isEmpty()) throw bad("name 不能为空");
    BigDecimal threshold = input.highValueQuantityThreshold();
    if (threshold != null && threshold.signum() <= 0)
      throw bad("highValueQuantityThreshold 必须大于 0");
    if (input.warehouseId() != null)
      requireWarehouse(tenant, input.warehouseId());
    if (exists(tenant, "select 1 from recount_rule where tenant_id=? and rule_code=?", code))
      throw conflict("复核规则编码 " + code + " 已存在（规则编码在租户内唯一）");

    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into recount_rule(id,tenant_id,rule_code,name,warehouse_id,require_serial_check,"
            + "high_value_quantity_threshold,open_box_note,created_by)"
            + " values(?,?,?,?,?,?,cast(? as numeric),?,?)",
        id, tenant, code, name, input.warehouseId(),
        input.requireSerialCheck() == null || input.requireSerialCheck(),
        threshold == null ? null : threshold.toPlainString(),
        text(input.openBoxNote()), actorId);
    return ruleOf(tenant, id);
  }

  /** 规则列表（真分页 + 真筛选）。 */
  public RecountRulePage rules(UUID warehouseId, String status, String keyword, int page, int size) {
    UUID tenant = currentTenant();
    int limit = pageSize(size);
    var where = new StringBuilder(" where tenant_id=?");
    var args = new ArrayList<Object>();
    args.add(tenant);
    if (warehouseId != null) {
      where.append(" and (warehouse_id=? or warehouse_id is null)");
      args.add(warehouseId);
    }
    if (text(status).isEmpty()) {
      where.append(" and status='ACTIVE'");
    } else {
      requireOneOf(status, ruleStatuses(), "status");
      where.append(" and status=?");
      args.add(status.trim());
    }
    String kw = text(keyword);
    if (!kw.isEmpty()) {
      where.append(" and (rule_code like ? or name like ?)");
      args.add("%" + kw + "%");
      args.add("%" + kw + "%");
    }
    long total =
        sql.fetchOne("select count(*) as c from recount_rule" + where, args.toArray())
            .get("c", Long.class);
    var items = new ArrayList<RecountRuleRow>();
    var rows =
        sql.fetch(
            "select * from recount_rule" + where + " order by rule_code limit ? offset ?",
            all(args, limit, limit * (long) page));
    for (Record r : rows) items.add(ruleRow(r));
    return new RecountRulePage(items, page, limit, total);
  }

  public RecountRuleDetail rule(String ruleId) {
    UUID tenant = currentTenant();
    // **先转 UUID 再绑参**：直接绑字符串会让 PG 报「uuid = character varying」——
    // 那是类型错，不是「规则不存在」，两者对调用方是完全不同的信号。
    UUID id = uuid(ruleId);
    Record row = one(tenant, "select * from recount_rule where tenant_id=? and id=?", id);
    long sessions =
        sql.fetchOne(
                "select count(*) as c from recount_session where tenant_id=? and rule_id=?",
                tenant, id)
            .get("c", Long.class);
    var statuses =
        sql.fetch(
                "select distinct status from recount_session where tenant_id=? and rule_id=?"
                    + " order by status",
                tenant, id)
            .stream()
            .map(r -> r.get(0, String.class))
            .toList();
    return new RecountRuleDetail(ruleRow(row), sessions, statuses);
  }

  /** 停用规则。停用后不能复活（口径的历史必须可追），DB 层有守卫。 */
  @Transactional
  public RecountRuleRow retireRule(String ruleId, RecountRetireInput input, UUID actorId) {
    UUID tenant = currentTenant();
    UUID id = uuid(ruleId);
    String reason = text(input.reason());
    if (reason.isEmpty()) throw bad("停用复核规则必须写原因");
    int n =
        sql.execute(
            "update recount_rule set status='RETIRED', retired_at=?::timestamptz,"
                + " retire_reason=?, updated_at=now()"
                + " where tenant_id=? and id=? and status='ACTIVE'",
            OffsetDateTime.now(), reason, tenant, id);
    if (n == 0) {
      // 分清「不存在」与「已停用」：前者 404，后者 409 —— 操作者要知道的不是同一条话。
      if (!exists(tenant, "select 1 from recount_rule where tenant_id=? and id=?", id))
        throw notFound("复核规则不存在");
      throw conflict("复核规则已停用，停用后不能复活");
    }
    return ruleOf(tenant, id);
  }

  private static List<String> ruleStatuses() {
    return List.of("ACTIVE", "RETIRED");
  }

  private RecountRuleRow ruleRow(Record r) {
    return new RecountRuleRow(
        r.get("id", UUID.class),
        r.get("rule_code", String.class),
        r.get("name", String.class),
        r.get("warehouse_id", UUID.class),
        r.get("warehouse_id", UUID.class) == null
            ? null
            : textOf(
                "select code from warehouse where tenant_id=? and id=?",
                r.get("tenant_id", UUID.class),
                r.get("warehouse_id", UUID.class)),
        r.get("require_serial_check", Boolean.class),
        decimalOrNull(r, "high_value_quantity_threshold"),
        r.get("open_box_note", String.class),
        r.get("status", String.class),
        r.get("retired_at", OffsetDateTime.class),
        r.get("retire_reason", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private RecountRuleRow ruleOf(UUID tenant, UUID id) {
    return ruleRow(one(tenant, "select * from recount_rule where tenant_id=? and id=?", id));
  }

  // ================================================================ 开单

  /**
   * 开一份复核单：从 8.3.2.1 的拣货任务接手，把订单明细冻结成复核基线。
   *
   * <p>三道校验缺一不可：
   *
   * <ol>
   *   <li>出库单必须在<b>待复核</b>（PENDING_RECHECK）——没拣的单不该出现在复核队列里；
   *   <li>拣货任务必须<b>已完成</b> —— 未完成的货还没到打包区，核了也核不准；
   *   <li>基线至少一行 —— 一行都没有的复核单没有可核的东西（DB 守卫也会拒判通过）。
   * </ol>
   */
  @Transactional
  public RecountCreated open(RecountOpenInput input, UUID actorId) {
    UUID tenant = currentTenant();
    if (input.outboundOrderId() == null) throw bad("outboundOrderId 不能为空");

    Record order = one(tenant, "select * from outbound_order where tenant_id=? and id=?", input.outboundOrderId());
    if (!"PENDING_RECHECK".equals(order.get("status", String.class)))
      throw conflict(
          "出库单 " + order.get("order_code", String.class) + " 当前是 "
              + order.get("status", String.class) + "，只有「待复核」（PENDING_RECHECK）才能开复核单");

    // 一张出库单同时只能有一个在复核中的会话（V117 的部分唯一索引兜底，
    // 这里先查一次是为了给一句人读得懂的话，而不是把 23505 抛到界面上）。
    Record openOne =
        sql.fetchOne(
            "select session_code from recount_session where tenant_id=? and outbound_order_id=?"
                + " and status='OPEN'",
            tenant, input.outboundOrderId());
    if (openOne != null)
      throw conflict(
          "出库单 " + order.get("order_code", String.class) + " 已经有一张在复核中的复核单 "
              + openOne.get("session_code", String.class)
              + "。先把那张做完（完成或取消），再开新的一张 —— 复核失败退回拣货后的第二轮"
              + "是**新会话**，不能把上一轮改成通过。");

    // 上游交接：按 (tenant_id, outbound_order_id) 读 8.3.2.1 的那张拣货任务。
    Record task =
        maybeOne(
            tenant,
            "select * from pick_task where tenant_id=? and outbound_order_id=? and status='COMPLETED'",
            input.outboundOrderId());
    if (task == null)
      throw conflict(
          "出库单 " + order.get("order_code", String.class)
              + " 没有「已完成」的拣货任务。复核接的是已拣到打包区的货，"
              + "拣货未完成时核不准（请先在 8.3.2.1 完成拣货或补拣）。");

    UUID warehouseId = task.get("warehouse_id", UUID.class);
    Record rule = null;
    if (input.ruleId() != null) rule = one(tenant, "select * from recount_rule where tenant_id=? and id=?", input.ruleId());
    else rule = defaultRule(tenant, warehouseId);
    boolean requireSerial = rule == null || rule.get("require_serial_check", Boolean.class);
    BigDecimal threshold =
        rule == null ? null : decimalOrNull(rule, "high_value_quantity_threshold");

    // 基线：订单明细逐行冻结。
    var lines =
        sql.fetch(
            "select l.id, l.line_no, l.sku_id, l.sku_code, l.sku_name, l.quantity,"
                + " (select coalesce(sum(pl.picked_quantity),0) from pick_task_line pl"
                + "   where pl.tenant_id=l.tenant_id and pl.outbound_line_id=l.id) as picked_qty,"
                + " (select count(*) from pick_task_serial ps"
                + "   join pick_task_line pl2 on pl2.tenant_id=ps.tenant_id and pl2.id=ps.line_id"
                + "   where ps.tenant_id=l.tenant_id and pl2.outbound_line_id=l.id) as serial_count"
                + " from outbound_order_line l"
                + " where l.tenant_id=? and l.order_id=? order by l.line_no",
            tenant, input.outboundOrderId());

    if (lines.isEmpty())
      throw conflict("出库单没有明细行，没有可核的基线");

    UUID sessionId = UUID.randomUUID();
    String code = sessionCode();
    String basis = verifyBasisOf(rule, requireSerial, threshold, lines.size());
    boolean highValue = threshold != null && totalExpected(lines).compareTo(threshold) >= 0;

    sql.execute(
        "insert into recount_session(id,tenant_id,session_code,outbound_order_id,pick_task_id,"
            + "warehouse_id,rule_id,verify_basis,outbound_status_before,reviewer_user_id,started_at,"
            + "created_by) values(?,?,?,?,?,?,?,?,?,?,?::timestamptz,?)",
        sessionId, tenant, code, input.outboundOrderId(), task.get("id", UUID.class), warehouseId,
        input.ruleId(), basis, "PENDING_RECHECK", input.reviewerUserId(), OffsetDateTime.now(),
        actorId);

    int seq = 0;
    for (Record l : lines) {
      seq++;
      int expectedSerials = l.get("serial_count", Integer.class) == null ? 0 : l.get("serial_count", Integer.class);
      boolean lineRequiresSerial = requireSerial && expectedSerials > 0;
      sql.execute(
          "insert into recount_line(id,tenant_id,session_id,outbound_line_id,line_no,sku_id,"
              + "sku_code,sku_name,expected_quantity,picked_quantity,expected_serial_count,"
              + "requires_serial) values(?,?,?,?,?,?,?,?,cast(? as numeric),cast(? as numeric),?,?)",
          UUID.randomUUID(), tenant, sessionId, l.get("id", UUID.class), seq,
          l.get("sku_id", UUID.class), l.get("sku_code", String.class), l.get("sku_name", String.class),
          l.get("quantity", BigDecimal.class).toPlainString(),
          plain(l.get("picked_qty", BigDecimal.class)),
          expectedSerials, lineRequiresSerial);
    }
    // 开箱建议：件数达到高价值阈值时，把「需要开箱」如实写进口径与事件，
    // 但**不自动开箱** —— 开箱要人做、要理由（蓝图「支持开箱复核」是给人用的开关）。
    event(tenant, sessionId, "OPENED", null, "OPEN", actorId, OffsetDateTime.now(),
        "开复核单 " + code + "：基线 " + lines.size() + " 行 / " + plain(totalExpected(lines))
            + " 件" + (highValue
                ? "；应发件数已达高价值阈值 " + plain(threshold) + "，建议开箱复核"
                : "") + "。");
    if (highValue) {
      event(tenant, sessionId, "OPEN_BOX", "OPEN", "OPEN", actorId, OffsetDateTime.now(),
          "系统提示：本单应发件数达到高价值阈值 " + plain(threshold) + "，建议开箱复核（需人工确认并写明原因）。");
    }
    return created(tenant, sessionId);
  }

  private Record defaultRule(UUID tenant, UUID warehouseId) {
    // 全仓通用规则优先于单仓规则：一张仓网只有一套口径时，它就该在所有仓生效。
    return sql.fetchOne(
        "select * from recount_rule where tenant_id=? and status='ACTIVE'"
            + " and (warehouse_id is null or warehouse_id=?)"
            + " order by (warehouse_id is not null) desc, rule_code limit 1",
        tenant, warehouseId);
  }

  private String verifyBasisOf(Record rule, boolean requireSerial, BigDecimal threshold, int lineCount) {
    StringBuilder sb = new StringBuilder();
    if (rule == null) {
      sb.append("本次无复核规则，使用内置默认口径：要求序列号逐件核对、不按件数强制开箱。");
    } else {
      sb.append("规则 ").append(rule.get("rule_code", String.class)).append("（")
          .append(rule.get("name", String.class)).append("）：序列号")
          .append(requireSerial ? "必须" : "不强制").append("逐件核对；高价值阈值取")
          .append(threshold == null ? "未设（不按件数强制开箱）" : "应发件数 " + plain(threshold))
          .append("。");
    }
    sb.append(' ').append(VERIFY_BASIS);
    sb.append(" 本单基线 ").append(lineCount).append(" 行。");
    return trim(sb.toString(), 600);
  }

  private static BigDecimal totalExpected(List<Record> lines) {
    BigDecimal total = BigDecimal.ZERO;
    for (Record l : lines) total = total.add(l.get("quantity", BigDecimal.class));
    return total;
  }

  // ================================================================ 列表 / 详情

  /** 复核单列表（真分页 + 真筛选 + 排序白名单 + 汇总，汇总不受筛选影响）。 */
  public RecountPage page(
      UUID warehouseId,
      UUID outboundOrderId,
      String status,
      UUID reviewerUserId,
      boolean openBoxOnly,
      boolean exceptionsOnly,
      String keyword,
      String sort,
      int page,
      int size) {
    UUID tenant = currentTenant();
    int limit = pageSize(size);
    // 白名单只在**给了值**时校验：不给值 = 不按这个维度筛，是正常用法，
    // 不是非法输入。给了但不在词表里才是 400。
    if (!text(sort).isEmpty()) requireOneOf(sort, SORTS, "sort");
    if (!text(status).isEmpty()) requireOneOf(status, STATUSES, "status");

    var where = new StringBuilder(" where s.tenant_id=?");
    var args = new ArrayList<Object>();
    args.add(tenant);
    if (warehouseId != null) {
      where.append(" and s.warehouse_id=?");
      args.add(warehouseId);
    }
    if (outboundOrderId != null) {
      where.append(" and s.outbound_order_id=?");
      args.add(outboundOrderId);
    }
    if (!text(status).isEmpty()) {
      where.append(" and s.status=?");
      args.add(status.trim());
    }
    if (reviewerUserId != null) {
      where.append(" and s.reviewer_user_id=?");
      args.add(reviewerUserId);
    }
    if (openBoxOnly) where.append(" and s.open_box");
    if (exceptionsOnly) where.append(" and s.exception_count>0");
    String kw = text(keyword);
    if (!kw.isEmpty()) {
      where.append(" and (s.session_code like ? or o.order_code like ? or t.task_code like ?)");
      args.add("%" + kw + "%");
      args.add("%" + kw + "%");
      args.add("%" + kw + "%");
    }
    String from =
        " from recount_session s"
            + " join outbound_order o on o.tenant_id=s.tenant_id and o.id=s.outbound_order_id"
            + " join pick_task t on t.tenant_id=s.tenant_id and t.id=s.pick_task_id"
            + " join warehouse w on w.tenant_id=s.tenant_id and w.id=s.warehouse_id"
            // 左连接：复核单可以没有规则（用内置默认口径），内连接会把那种单整张查不出来。
            + " left join recount_rule r on r.tenant_id=s.tenant_id and r.id=s.rule_id";
    long total = sql.fetchOne("select count(*) as c" + from + where, args.toArray()).get("c", Long.class);

    String order =
        switch (sort.trim().isEmpty() ? "STARTED_AT" : sort.trim()) {
          case "SESSION_CODE" -> " order by s.session_code";
          case "BASELINE_QUANTITY" -> " order by s.baseline_quantity desc, s.id";
          case "STATUS" -> " order by s.status, s.started_at desc, s.id";
          default -> " order by s.started_at desc, s.id";
        };
    // offset 刻意用 **int**（与 PickTaskService / PickWaveService 逐字一致）：
    // jOOQ 对纯文本 SQL 的 `limit ? offset ?` 靠实参类型推断参数类型，
    // 传 long 会让它绑成 int8 而不是 bigint，PG 直接拒。
    int offset = Math.max(page, 0) * limit;
    var rows =
        sql.fetch(
            "select s.*, o.order_code, o.status as outbound_status, t.task_code,"
                + " w.code as warehouse_code, r.rule_code" + from + where + order + " limit ? offset ?",
            all(args, limit, offset));
    var items = new ArrayList<RecountRow>();
    for (Record row : rows) items.add(sessionRow(row));
    return new RecountPage(items, page, limit, total, summary(tenant, warehouseId));
  }

  /** 汇总：不受筛选影响（与 8.3.2.1 / 8.3.2.2 同一纪律）。 */
  private RecountSummary summary(UUID tenant, UUID warehouseId) {
    var row =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where s.status='OPEN') as open,"
                + " count(*) filter (where s.status='PASSED') as passed,"
                + " count(*) filter (where s.status='FAILED') as failed,"
                + " count(*) filter (where s.status='CANCELLED') as cancelled,"
                + " count(*) filter (where s.open_box) as open_box,"
                + " coalesce(sum(s.pending_exception_count),0) as pending_ex,"
                + " coalesce(sum(s.baseline_quantity),0) as baseline_qty,"
                + " coalesce(sum(s.scanned_quantity),0) as scanned_qty"
                + " from recount_session s where s.tenant_id=?"
                + (warehouseId == null ? "" : " and s.warehouse_id=?"),
            warehouseId == null ? new Object[] {tenant} : new Object[] {tenant, warehouseId});
    var kinds =
        sql.fetch(
            "select e.kind, count(*) as c from recount_exception e"
                + " join recount_session s on s.tenant_id=e.tenant_id and s.id=e.session_id"
                + " where e.tenant_id=?"
                + (warehouseId == null ? "" : " and s.warehouse_id=?")
                + " group by e.kind",
            warehouseId == null ? new Object[] {tenant} : new Object[] {tenant, warehouseId});
    Map<String, Long> byKind = new LinkedHashMap<>();
    for (Record r : kinds) byKind.put(r.get("kind", String.class), r.get("c", Long.class));
    long manual =
        sql.fetchOne(
                "select count(*) as c from recount_exception e"
                    + " join recount_session s on s.tenant_id=e.tenant_id and s.id=e.session_id"
                    + " where e.tenant_id=? and e.resolution='MANUAL'"
                    + (warehouseId == null ? "" : " and s.warehouse_id=?"),
                warehouseId == null ? new Object[] {tenant} : new Object[] {tenant, warehouseId})
            .get("c", Long.class);
    return new RecountSummary(
        row.get("total", Long.class),
        row.get("open", Long.class),
        row.get("passed", Long.class),
        row.get("failed", Long.class),
        row.get("cancelled", Long.class),
        row.get("open_box", Long.class),
        row.get("pending_ex", Long.class),
        manual,
        byKind.getOrDefault("MISSING_ITEM", 0L),
        byKind.getOrDefault("WRONG_ITEM", 0L),
        byKind.getOrDefault("EXTRA_ITEM", 0L),
        byKind.getOrDefault("SERIAL_MISMATCH", 0L),
        plain(row.get("baseline_qty", BigDecimal.class)),
        plain(row.get("scanned_qty", BigDecimal.class)));
  }

  public RecountDetail detail(String sessionId) {
    UUID tenant = currentTenant();
    UUID id = uuid(sessionId);
    Record row = sessionRecord(tenant, id);
    return new RecountDetail(
        sessionRow(row),
        lines(tenant, id),
        scans(tenant, id, 200),
        exceptions(tenant, id),
        timeline(tenant, id),
        verdict(tenant, id));
  }

  // ================================================================ 开箱复核

  /**
   * 开箱复核（高价值订单）。
   *
   * <p>「高价值」只给<b>建议</b>不给自动开箱：开箱要人做、要理由，
   * 而且开箱之后可能发现包裹里多塞了东西 —— 那正是要人工确认的理由。
   */
  @Transactional
  public RecountCreated openBox(String sessionId, RecountOpenBoxInput input, UUID actorId) {
    UUID tenant = currentTenant();
    UUID id = uuid(sessionId);
    Record session = sessionRecord(tenant, id);
    requireOpen(session, "开箱");
    String reason = text(input.reason());
    if (reason.isEmpty()) throw bad("开箱复核必须写原因（为什么这一单要开箱）");
    sql.execute(
        "update recount_session set open_box=true, open_box_reason=?, opened_at=?::timestamptz,"
            + " opened_by=?, updated_at=now() where tenant_id=? and id=?",
        reason, OffsetDateTime.now(), actorId, tenant, id);
    event(tenant, id, "OPEN_BOX", "OPEN", "OPEN", actorId, OffsetDateTime.now(),
        "开箱复核：" + reason);
    return created(tenant, id);
  }

  // ================================================================ 扫描

  /**
   * 扫一件 —— 逐件核对的核心动作。
   *
   * <p>判定顺序（每一步都有真实依据，没有一步是猜的）：
   *
   * <ol>
   *   <li><b>解析</b>：序列号去 {@code inventory_serial} 查（顺带拿到绑定的 SKU），
   *       条码去 {@code sku_ref} 按 code 查。查不到 → verdict=UNKNOWN 并记异常，
   *       <b>不猜一个 SKU 填上去</b>。
   *   <li><b>重复</b>：由 DB 触发器判（同一值本会话第二次起只能是 DUPLICATE），
   *       本层据此记一条 DUPLICATE_SCAN 异常。
   *   <li><b>对行</b>：解析出的 SKU 在本单基线里找对应行；找到 → 命中该行；
   *       找不到 → <b>多件</b>（这张单压根没有这个货），记 EXTRA_ITEM。
   *   <li><b>序列号是否该属于这一行</b>：扫到的序列号如果被 8.3.2.1 记在了别的行上
   *       → <b>错件</b>，记 WRONG_ITEM 而不是命中。
   * </ol>
   */
  @Transactional
  public RecountScanRow scan(String sessionId, RecountScanInput input, UUID actorId) {
    UUID tenant = currentTenant();
    UUID id = uuid(sessionId);
    Record session = sessionRecord(tenant, id);
    requireOpen(session, "扫描");

    String type = text(input.scanType()).toUpperCase();
    requireOneOf(type, List.of("BARCODE", "SERIAL"), "scanType");
    String value = text(input.scannedValue());
    if (value.isEmpty()) throw bad("scannedValue 不能为空");
    OffsetDateTime at = instant(input.scannedAt(), "scannedAt");

    // 1. 解析
    UUID skuId = null;
    UUID serialId = null;
    String skuCode = "";
    String skuName = "";
    String serialNumber = null;
    if ("SERIAL".equals(type)) {
      Record serial =
          sql.fetchOne(
              "select id, sku_id, serial_number from inventory_serial where tenant_id=?"
                  + " and serial_number=?",
              tenant, value);
      if (serial == null)
        throw bad("序列号 " + value + " 不在本租户的序列号台账里（inventory_serial）。"
            + "扫不出来的东西不能当成「核过了」——请确认是不是拿错了单。");
      serialId = serial.get("id", UUID.class);
      skuId = serial.get("sku_id", UUID.class);
      serialNumber = serial.get("serial_number", String.class);
      var sku = sql.fetchOne("select code, name from sku_ref where id=?", skuId);
      if (sku != null) {
        skuCode = sku.get("code", String.class);
        skuName = sku.get("name", String.class);
      }
    } else {
      Record sku = sql.fetchOne("select id, code, name from sku_ref where tenant_id=? and code=?", tenant, value);
      if (sku != null) {
        skuId = sku.get("id", UUID.class);
        skuCode = sku.get("code", String.class);
        skuName = sku.get("name", String.class);
      }
    }

    // 2. 对行：这一单基线里有没有这个 SKU
    Record line = null;
    if (skuId != null)
      line =
          sql.fetchOne(
              "select * from recount_line where tenant_id=? and session_id=? and sku_id=?",
              tenant, id, skuId);

    // 3. 序列号是否被记在别的行上（错件的判据）
    UUID pickId = null;
    if (serialId != null) {
      Record picked =
          sql.fetchOne(
              "select ps.pick_id, ps.line_id, pl.outbound_line_id"
                  + " from pick_task_serial ps"
                  + " join pick_task_line pl on pl.tenant_id=ps.tenant_id and pl.id=ps.line_id"
                  + " where ps.tenant_id=? and ps.serial_id=? and pl.task_id=?",
              tenant, serialId, session.get("pick_task_id", UUID.class));
      if (picked != null) {
        pickId = picked.get("pick_id", UUID.class);
        UUID pickedLine = picked.get("outbound_line_id", UUID.class);
        if (line == null || !pickedLine.equals(line.get("outbound_line_id", UUID.class))) {
          // 扫到的这台机器拣给了另一行（或这张单压根没有）→ 错件
          line = line; // 保留基线行用于展示，但判定为 WRONG_SKU
        }
      }
    }

    boolean wrongSerialOwner = false;
    if (serialId != null && line != null) {
      long owned =
          sql.fetchOne(
                  "select count(*) as c from pick_task_serial ps"
                      + " join pick_task_line pl on pl.tenant_id=ps.tenant_id and pl.id=ps.line_id"
                      + " where ps.tenant_id=? and ps.serial_id=? and pl.outbound_line_id=?",
                  tenant, serialId, line.get("outbound_line_id", UUID.class))
              .get("c", Long.class);
      wrongSerialOwner = owned == 0;
    }

    String verdict;
    UUID lineId;
    if (skuId == null) verdict = "UNKNOWN";
    else if (line == null) verdict = "WRONG_SKU";
    else if (wrongSerialOwner) verdict = "WRONG_SKU";
    else verdict = "MATCHED";
    lineId = line == null ? null : line.get("id", UUID.class);

    // 4. 落扫描记录（scan_occurrence 与 DUPLICATE 由 DB 触发器定）
    UUID scanId = UUID.randomUUID();
    sql.execute(
        "insert into recount_scan(id,tenant_id,session_id,scan_type,scanned_value,sku_id,"
            + "sku_code,sku_name,serial_id,serial_number,pick_id,outbound_line_id,quantity,"
            + "verdict,operator_user_id,scanned_at,note)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,1,?,?,?::timestamptz,?)",
        scanId, tenant, id, type, value, skuId, skuCode, skuName, serialId, serialNumber,
        pickId, line == null ? null : line.get("outbound_line_id", UUID.class), verdict,
        actorId, at, text(input.note()));
    Record stored = sql.fetchOne("select * from recount_scan where id=?", scanId);
    String storedVerdict = stored.get("verdict", String.class);

    // 5. 即时异常：重复扫 / 多件 / 错件 / 未知码。漏件不在这里报 ——
    //    「还没扫」和「扫完发现少了」是两件事，漏件由 complete() 结算。
    event(tenant, id, "SCANNED", "OPEN", "OPEN", actorId, at,
        "扫一件 " + (type.equals("SERIAL") ? "序列号 " : "条码 ") + value + " → " + storedVerdict);
    if ("DUPLICATE".equals(storedVerdict))
      raise(
          tenant, id, "DUPLICATE_SCAN", scanId, line == null ? null : line.get("outbound_line_id", UUID.class),
          skuId, skuCode, skuName, serialId, serialNumber, null, BigDecimal.ONE,
          "同一个" + (type.equals("SERIAL") ? "序列号 " : "条码 ") + value
              + " 在本复核单里重复扫到（第 " + stored.get("scan_occurrence", Integer.class)
              + " 次）。要么是同一件塞了两遍，要么是扫错了重新来。",
          at, actorId);
    else if ("UNKNOWN".equals(storedVerdict))
      raise(tenant, id, "UNKNOWN_BARCODE", scanId, null, null, value, "", null, null, null,
          BigDecimal.ONE, "条码 " + value + " 在本仓解析不出 SKU，无法核对。", at, actorId);
    else if ("WRONG_SKU".equals(storedVerdict))
      raise(
          tenant, id, line == null ? "EXTRA_ITEM" : "WRONG_ITEM", scanId,
          line == null ? null : line.get("outbound_line_id", UUID.class), skuId, skuCode, skuName,
          serialId, serialNumber, null, BigDecimal.ONE,
          line == null
              ? "扫到 " + (skuCode.isEmpty() ? value : skuCode) + "，但这张单压根没有这个货 —— 多件。"
              : "扫到 " + (skuCode.isEmpty() ? value : skuCode)
                  + "（序列号 " + (serialNumber == null ? "—" : serialNumber)
                  + "），但 8.3.2.1 把它拣给了别的行 —— 错件。",
          at, actorId);

    // 6. 命中的一行：刷新序列号集合相等性（两个差集，不是 count 相等）
    if (lineId != null) refreshSerialMatch(tenant, id, lineId);
    return scanRow(stored);
  }

  /**
   * 序列号集合相等性：两个差集都空才算相等。
   *
   * <p>正向 = 实拣登记的序列号 − 实扫到的序列号；反向 = 实扫到的 − 实拣登记的。
   * 只做正向的话，「扫的 3 个号里有 2 个根本不是这单的」会被漏成核过。
   */
  private void refreshSerialMatch(UUID tenant, UUID sessionId, UUID lineId) {
    Record line = one(tenant, "select * from recount_line where tenant_id=? and id=?", lineId);
    if (!line.get("requires_serial", Boolean.class)) return;
    UUID outboundLineId = line.get("outbound_line_id", UUID.class);
    long missing =
        sql.fetchOne(
                "select count(*) as c from ("
                    + " select ps.serial_id from pick_task_serial ps"
                    + "  join pick_task_line pl on pl.tenant_id=ps.tenant_id and pl.id=ps.line_id"
                    + "  where ps.tenant_id=? and pl.outbound_line_id=?"
                    + "  except"
                    + "  select s.serial_id from recount_scan s"
                    + "   where s.tenant_id=? and s.session_id=? and s.serial_id is not null"
                    + "     and s.outbound_line_id=? and s.verdict<>'DUPLICATE'"
                    + ") x", tenant, outboundLineId, tenant, sessionId, outboundLineId)
            .get("c", Long.class);
    long extra =
        sql.fetchOne(
                "select count(*) as c from ("
                    + " select s.serial_id from recount_scan s"
                    + "  where s.tenant_id=? and s.session_id=? and s.serial_id is not null"
                    + "    and s.outbound_line_id=? and s.verdict<>'DUPLICATE'"
                    + "  except"
                    + "  select ps.serial_id from pick_task_serial ps"
                    + "  join pick_task_line pl on pl.tenant_id=ps.tenant_id and pl.id=ps.line_id"
                    + "  where ps.tenant_id=? and pl.outbound_line_id=?"
                    + ") x", tenant, sessionId, outboundLineId, tenant, outboundLineId)
            .get("c", Long.class);
    sql.execute(
        "update recount_line set serial_match=? where tenant_id=? and id=?",
        missing == 0 && extra == 0, tenant, lineId);
  }

  // ================================================================ 结算

  /**
   * 结算复核：逐行比订单明细，检出漏件/多件，更新序列号结论，给出通过/异常判定。
   *
   * <p><b>漏件与多件在结算时才报</b>：扫描过程中「还没扫齐」和「扫完发现少了」
   * 根本区分不出来 —— 只有停下来结算，才知道哪一行永远少了一件。
   */
  @Transactional
  public RecountVerdictReport settle(String sessionId, UUID actorId) {
    UUID tenant = currentTenant();
    UUID id = uuid(sessionId);
    Record session = sessionRecord(tenant, id);
    requireOpen(session, "结算");

    // 逐行刷新序列号集合相等性（可能刚被批量改过）。
    for (Record line : sql.fetch("select id from recount_line where tenant_id=? and session_id=?", tenant, id))
      refreshSerialMatch(tenant, id, line.get("id", UUID.class));

    // 漏件结算：scanned < expected 的行报 MISSING_ITEM（一次一单，不会刷屏）。
    // 已存在同种同行的未处置异常就不重复报 —— 同一件事报两遍只会让人以为有两件问题。
    var lines = sql.fetch("select * from recount_line where tenant_id=? and session_id=? order by line_no", tenant, id);
    OffsetDateTime now = OffsetDateTime.now();
    for (Record l : lines) {
      BigDecimal expected = l.get("expected_quantity", BigDecimal.class);
      BigDecimal scanned = l.get("scanned_quantity", BigDecimal.class);
      // 同一行同一会话只报一次漏件：**不按 resolution 过滤**。
      // 「转人工」是处置，不是「这条问题消失了」；按 PENDING 过滤的话，
      // 每次结算都会因为上一条已转人工而再报一条同样的漏件 —— 同一件事报两遍
      // 只会让人以为有两件问题。
      boolean alreadyRaised =
          sql.fetchOne(
                  "select count(*) as c from recount_exception where tenant_id=? and session_id=?"
                      + " and kind='MISSING_ITEM' and outbound_line_id=?",
                  tenant, id, l.get("outbound_line_id", UUID.class))
              .get("c", Long.class)
              > 0;
      if (scanned.compareTo(expected) < 0 && !alreadyRaised) {
        raise(
            tenant, id, "MISSING_ITEM", null, l.get("outbound_line_id", UUID.class),
            l.get("sku_id", UUID.class), l.get("sku_code", String.class), l.get("sku_name", String.class),
            null, null, expected, scanned,
            "漏件：" + l.get("sku_code", String.class) + " 应发 " + plain(expected) + " 件，实扫 "
                + plain(scanned) + " 件，差 " + plain(expected.subtract(scanned)) + " 件。",
            now, actorId);
      }
      // 序列号集合不符单独报一次（可能与漏件并存：少扫了一个、或换成了别的机器）。
      boolean mismatch = l.get("requires_serial", Boolean.class) && !l.get("serial_match", Boolean.class);
      boolean mismatchRaised =
          sql.fetchOne(
                  "select count(*) as c from recount_exception where tenant_id=? and session_id=?"
                      + " and kind='SERIAL_MISMATCH' and outbound_line_id=?",
                  tenant, id, l.get("outbound_line_id", UUID.class))
              .get("c", Long.class)
              > 0;
      if (mismatch && !mismatchRaised) {
        var missingSerials = missingSerialNumbers(tenant, l.get("outbound_line_id", UUID.class), id);
        var extraSerials = extraSerialNumbers(tenant, l.get("outbound_line_id", UUID.class), id);
        raise(
            tenant, id, "SERIAL_MISMATCH", null, l.get("outbound_line_id", UUID.class),
            l.get("sku_id", UUID.class), l.get("sku_code", String.class), l.get("sku_name", String.class),
            null, null, null, null,
            "序列号集合不符："
                + (missingSerials.isEmpty() ? "" : "没扫到 " + missingSerials)
                + (!missingSerials.isEmpty() && !extraSerials.isEmpty() ? "；" : "")
                + (extraSerials.isEmpty() ? "" : "多扫到 " + extraSerials)
                + "。件数对得上也不算数 —— 序列号商品必须逐件核对。",
            now, actorId);
      }
    }
    return verdict(tenant, id);
  }

  /**
   * 完成复核：结算 + 判定。
   *
   * <p>判通过的四个前提（V117 §7.1 在 DB 层再拒一遍）：
   * 每一行核过、序列号集合相等、没有未处置异常、出库单本来就在待复核。
   *
   * <p><b>通过时把出库单推到「待打包」</b>（PENDING_PACKING，8.3.3.2 的入口），
   * <b>从不碰 SHIPPED</b> —— 发货是 8.3.4.1 的事。
   */
  @Transactional
  public RecountVerdictReport complete(String sessionId, UUID actorId) {
    UUID tenant = currentTenant();
    UUID id = uuid(sessionId);
    Record session = sessionRecord(tenant, id);
    requireOpen(session, "完成");

    settle(sessionId, actorId);
    // 重读：结算可能刚写了几条异常。
    session = sessionRecord(tenant, id);
    boolean clean = pendingExceptions(tenant, id) == 0 && badLines(tenant, id) == 0;
    String status = clean ? "PASSED" : "FAILED";
    OffsetDateTime now = OffsetDateTime.now();

    sql.execute(
        "update recount_session set status=?, reviewer_user_id=coalesce(reviewer_user_id,?),"
            + " completed_at=?::timestamptz, updated_at=now() where tenant_id=? and id=?",
        status, actorId, now, tenant, id);

    if (clean) {
      // 交给下游 8.3.3.2：出库单 PENDING_RECHECK → PENDING_PACKING。
      sql.execute(
          "update outbound_order set status='PENDING_PACKING', updated_at=now()"
              + " where tenant_id=? and id=? and status='PENDING_RECHECK'",
          tenant, session.get("outbound_order_id", UUID.class));
      event(tenant, id, "PASSED", "OPEN", "PASSED", actorId, now,
          "复核通过：每一行都核过且无未处置异常。出库单已交到「待打包」（8.3.3.2）。"
              + "复核不写库存分录、不释放预占 —— 货与预占都还在打包区。");
    } else {
      event(tenant, id, "FAILED", "OPEN", "FAILED", actorId, now,
          "复核异常：还有 " + badLines(tenant, id) + " 行没核过、" + pendingExceptions(tenant, id)
              + " 条异常未处理。检出拣错/漏件时的出口是退回拣货（出库单 PENDING_RECHECK → PICKING，"
              + "由 8.3.2.1 的 reopen 接住），不是本模块改库存。");
    }
    return verdict(tenant, id);
  }

  // ================================================================ 异常处置

  /** 转人工处理（蓝图输出原话「异常单转人工处理」）。处置意见必填。 */
  @Transactional
  public RecountExceptionRow escalate(String sessionId, RecountEscalateInput input, UUID actorId) {
    UUID tenant = currentTenant();
    UUID id = uuid(sessionId);
    Record session = sessionRecord(tenant, id);
    if ("CANCELLED".equals(session.get("status", String.class)))
      throw conflict("复核单已取消，不能再转人工");
    String note = text(input.note());
    if (note.isEmpty()) throw bad("转人工必须写处理意见（转给谁、要怎么处理）");
    List<Record> pendings =
        sql.fetch(
            "select * from recount_exception where tenant_id=? and session_id=? and resolution='PENDING'"
                + " order by raised_at, id",
            tenant, id);
    if (pendings.isEmpty()) throw conflict("这张复核单没有待处理的异常，没什么可转人工");
    for (Record e : pendings) {
      sql.execute(
          "update recount_exception set resolution='MANUAL', manual_note=?, resolved_at=?::timestamptz,"
              + " resolved_by=? where tenant_id=? and id=?",
          note, OffsetDateTime.now(), actorId, tenant, e.get("id", UUID.class));
    }
    event(tenant, id, "ESCALATED", session.get("status", String.class), session.get("status", String.class),
        actorId, OffsetDateTime.now(),
        pendings.size() + " 条异常转人工处理：" + note);
    var after = sql.fetch(
        "select * from recount_exception where tenant_id=? and session_id=? order by raised_at, id",
        tenant, id);
    return after.isEmpty() ? null : exceptionRow(after.get(0));
  }

  /** 处置单条异常。已处置的不许再改（DB 守卫，事实只能处置一次）。 */
  @Transactional
  public RecountExceptionRow resolveException(
      String sessionId, String exceptionId, RecountResolveInput input, UUID actorId) {
    UUID tenant = currentTenant();
    UUID sid = uuid(sessionId);
    UUID eid = uuid(exceptionId);
    sessionRecord(tenant, sid);
    String resolution = text(input.resolution()).toUpperCase();
    requireOneOf(resolution, List.of("MANUAL", "CORRECTED", "ACCEPTED", "IGNORED"), "resolution");
    String note = text(input.note());
    if (note.isEmpty())
      throw bad("处置异常必须写处理意见（怎么处理或为什么接受）——「已处理」而不写怎么处理，"
          + "半年后回看等于没处理");
    int n =
        sql.execute(
            "update recount_exception set resolution=?, manual_note=?, resolved_at=?::timestamptz,"
                + " resolved_by=? where tenant_id=? and id=? and resolution='PENDING'",
            resolution, note, OffsetDateTime.now(), actorId, tenant, eid);
    if (n == 0) {
      if (!exists(tenant, "select 1 from recount_exception where tenant_id=? and id=?", eid))
        throw notFound("复核异常不存在");
      throw conflict("这条异常已经处置过，事实只能处置一次");
    }
    event(tenant, sid, "RESOLVED", null, null, actorId, OffsetDateTime.now(),
        "处置异常 " + eid + " → " + resolution + "：" + note);
    return exceptionRow(
        one(tenant, "select * from recount_exception where tenant_id=? and id=?", eid));
  }

  // ================================================================ 取消

  /** 取消复核。库存不动、货不动 —— 复核本来就不碰库存。 */
  @Transactional
  public RecountCreated cancel(String sessionId, RecountCancelInput input, UUID actorId) {
    UUID tenant = currentTenant();
    UUID id = uuid(sessionId);
    Record session = sessionRecord(tenant, id);
    requireOpen(session, "取消");
    String reason = text(input.reason());
    if (reason.isEmpty()) throw bad("取消复核必须写原因");
    sql.execute(
        "update recount_session set status='CANCELLED', cancelled_at=?::timestamptz,"
            + " cancel_reason=?, updated_at=now() where tenant_id=? and id=?",
        OffsetDateTime.now(), reason, tenant, id);
    event(tenant, id, "CANCELLED", "OPEN", "CANCELLED", actorId, OffsetDateTime.now(),
        "取消复核：" + reason + "。货与预占都还在打包区，库存未动。");
    return created(tenant, id);
  }

  // ================================================================ 结论与读模型

  /**
   * 复核结论。{@code blocking} 是「能不能放行给 8.3.3.2」的唯一判据，
   * 由后端算好返回 —— 界面上的「通过」按钮不该由前端自己判断该不该可点。
   */
  public RecountVerdictReport verdict(UUID tenant, UUID sessionId) {
    Record s = one(tenant, "select * from recount_session where tenant_id=? and id=?", sessionId);
    long bad = badLines(tenant, sessionId);
    long pending = pendingExceptions(tenant, sessionId);
    var reasons = new ArrayList<String>();
    if (bad > 0) reasons.add("还有 " + bad + " 行没核过（漏件/多件/序列号不符）");
    if (pending > 0) reasons.add("还有 " + pending + " 条异常没人处理");
    if (!"PENDING_RECHECK".equals(s.get("outbound_status_before", String.class)))
      reasons.add("接手时出库单不在「待复核」，这一单不该走复核放行");
    if (reasons.isEmpty() && "OPEN".equals(s.get("status", String.class)))
      reasons.add("还没结算完成（点「完成复核」才会定论）");

    boolean openBoxRequired = false;
    if (s.get("rule_id", UUID.class) != null) {
      var rule = sql.fetchOne(
          "select high_value_quantity_threshold from recount_rule where tenant_id=? and id=?",
          tenant, s.get("rule_id", UUID.class));
      if (rule != null && rule.get("high_value_quantity_threshold", BigDecimal.class) != null)
        openBoxRequired =
            s.get("baseline_quantity", BigDecimal.class)
                    .compareTo(rule.get("high_value_quantity_threshold", BigDecimal.class))
                >= 0;
    }
    if (openBoxRequired && !s.get("open_box", Boolean.class) && reasons.isEmpty())
      reasons.add("应发件数达到高价值阈值，建议开箱复核后再放行");

    // 判定看的是「有没有拦路的东西」，不是「有没有点完成」：
    // 结算完且干净的单，结论就是「通过」，哪怕还没点「完成复核」正式放行。
    // （blocking 在**所有**理由都加完之后才算，否则 openBox 那条理由会加得晚一步、
    //   出现「有理由但 blocking=false」的自相矛盾形状。）
    boolean clean = bad == 0 && pending == 0
        && "PENDING_RECHECK".equals(s.get("outbound_status_before", String.class));
    boolean blocking = !reasons.isEmpty();
    String verdictValue = clean ? "PASSED" : (pending > 0 || bad > 0 ? "FAILED" : "OPEN");

    return new RecountVerdictReport(
        verdictValue,
        label(verdictValue),
        blocking,
        reasons,
        s.get("baseline_line_count", Integer.class),
        plain(s.get("baseline_quantity", BigDecimal.class)),
        s.get("scanned_line_count", Integer.class),
        plain(s.get("scanned_quantity", BigDecimal.class)),
        s.get("matched_line_count", Integer.class),
        s.get("mismatch_line_count", Integer.class),
        s.get("serial_checked_count", Integer.class),
        s.get("pending_exception_count", Integer.class),
        s.get("open_box", Boolean.class),
        openBoxRequired,
        s.get("verify_basis", String.class));
  }

  private long badLines(UUID tenant, UUID sessionId) {
    return sql.fetchOne(
            "select count(*) as c from recount_line where tenant_id=? and session_id=?"
                + " and line_status<>'MATCHED'",
            tenant, sessionId)
        .get("c", Long.class);
  }

  private long pendingExceptions(UUID tenant, UUID sessionId) {
    return sql.fetchOne(
            "select count(*) as c from recount_exception where tenant_id=? and session_id=?"
                + " and resolution='PENDING'",
            tenant, sessionId)
        .get("c", Long.class);
  }

  private List<String> missingSerialNumbers(UUID tenant, UUID outboundLineId, UUID sessionId) {
    return sql.fetch(
            "select s.serial_number from ("
                + " select ps.serial_id from pick_task_serial ps"
                + "  join pick_task_line pl on pl.tenant_id=ps.tenant_id and pl.id=ps.line_id"
                + "  where ps.tenant_id=? and pl.outbound_line_id=?"
                + "  except"
                + "  select cs.serial_id from recount_scan cs"
                + "   where cs.tenant_id=? and cs.session_id=? and cs.serial_id is not null"
                + "     and cs.outbound_line_id=? and cs.verdict<>'DUPLICATE'"
                + ") x join inventory_serial s on s.tenant_id=? and s.id=x.serial_id"
                + " order by s.serial_number",
            tenant, outboundLineId, tenant, sessionId, outboundLineId, tenant)
        .stream()
        .map(r -> r.get("serial_number", String.class))
        .toList();
  }

  private List<String> extraSerialNumbers(UUID tenant, UUID outboundLineId, UUID sessionId) {
    return sql.fetch(
            "select s.serial_number from ("
                + " select cs.serial_id from recount_scan cs"
                + "  where cs.tenant_id=? and cs.session_id=? and cs.serial_id is not null"
                + "    and cs.outbound_line_id=? and cs.verdict<>'DUPLICATE'"
                + "  except"
                + "  select ps.serial_id from pick_task_serial ps"
                + "  join pick_task_line pl on pl.tenant_id=ps.tenant_id and pl.id=ps.line_id"
                + "  where ps.tenant_id=? and pl.outbound_line_id=?"
                + ") x join inventory_serial s on s.tenant_id=? and s.id=x.serial_id"
                + " order by s.serial_number",
            tenant, sessionId, outboundLineId, tenant, outboundLineId, tenant)
        .stream()
        .map(r -> r.get("serial_number", String.class))
        .toList();
  }

  private List<RecountLineRow> lines(UUID tenant, UUID sessionId) {
    var out = new ArrayList<RecountLineRow>();
    for (Record r :
        sql.fetch(
            "select * from recount_line where tenant_id=? and session_id=? order by line_no",
            tenant, sessionId)) {
      UUID outboundLineId = r.get("outbound_line_id", UUID.class);
      out.add(
          new RecountLineRow(
              r.get("id", UUID.class),
              r.get("line_no", Integer.class),
              outboundLineId,
              r.get("sku_id", UUID.class),
              r.get("sku_code", String.class),
              r.get("sku_name", String.class),
              plain(r.get("expected_quantity", BigDecimal.class)),
              plain(r.get("picked_quantity", BigDecimal.class)),
              r.get("requires_serial", Boolean.class),
              r.get("expected_serial_count", Integer.class),
              r.get("scanned_serial_count", Integer.class),
              r.get("serial_match", Boolean.class),
              plain(r.get("scanned_quantity", BigDecimal.class)),
              r.get("line_status", String.class),
              lineLabel(r.get("line_status", String.class)),
              outboundLineId == null
                  ? List.of()
                  : sql.fetch(
                          "select s.serial_number from pick_task_serial ps"
                              + " join pick_task_line pl on pl.tenant_id=ps.tenant_id and pl.id=ps.line_id"
                              + " join inventory_serial s on s.tenant_id=ps.tenant_id and s.id=ps.serial_id"
                              + " where ps.tenant_id=? and pl.outbound_line_id=? order by s.serial_number",
                          tenant, outboundLineId)
                      .stream()
                      .map(x -> x.get("serial_number", String.class))
                      .toList(),
              outboundLineId == null
                  ? List.of()
                  : sql.fetch(
                          "select distinct s.serial_number from recount_scan cs"
                              + " join inventory_serial s on s.tenant_id=cs.tenant_id and s.id=cs.serial_id"
                              + " where cs.tenant_id=? and cs.session_id=? and cs.outbound_line_id=?"
                              + " and cs.serial_id is not null and cs.verdict<>'DUPLICATE'"
                              + " order by s.serial_number",
                          tenant, sessionId, outboundLineId)
                      .stream()
                      .map(x -> x.get("serial_number", String.class))
                      .toList()));
    }
    return out;
  }

  private List<RecountScanRow> scans(UUID tenant, UUID sessionId, int limit) {
    var out = new ArrayList<RecountScanRow>();
    for (Record r :
        sql.fetch(
            "select * from recount_scan where tenant_id=? and session_id=?"
                + " order by scanned_at desc, id desc limit ?",
            tenant, sessionId, limit))
      out.add(scanRow(r));
    return out;
  }

  private List<RecountExceptionRow> exceptions(UUID tenant, UUID sessionId) {
    var out = new ArrayList<RecountExceptionRow>();
    for (Record r :
        sql.fetch(
            "select * from recount_exception where tenant_id=? and session_id=?"
                + " order by resolution='PENDING' desc, raised_at, id",
            tenant, sessionId))
      out.add(exceptionRow(r));
    return out;
  }

  private List<RecountEventRow> timeline(UUID tenant, UUID sessionId) {
    var out = new ArrayList<RecountEventRow>();
    for (Record r :
        sql.fetch(
            "select * from recount_event where tenant_id=? and session_id=?"
                + " order by occurred_at, id",
            tenant, sessionId))
      out.add(
          new RecountEventRow(
              r.get("id", UUID.class),
              r.get("event_type", String.class),
              eventLabel(r.get("event_type", String.class)),
              r.get("from_status", String.class),
              r.get("to_status", String.class),
              r.get("actor_user_id", UUID.class),
              r.get("occurred_at", OffsetDateTime.class),
              r.get("detail", String.class)));
    return out;
  }

  private RecountScanRow scanRow(Record r) {
    String verdict = r.get("verdict", String.class);
    return new RecountScanRow(
        r.get("id", UUID.class),
        r.get("scan_type", String.class),
        r.get("scanned_value", String.class),
        r.get("scan_occurrence", Integer.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("serial_id", UUID.class),
        r.get("serial_number", String.class),
        r.get("outbound_line_id", UUID.class),
        plain(r.get("quantity", BigDecimal.class)),
        verdict,
        verdictLabel(verdict),
        r.get("operator_user_id", UUID.class),
        r.get("scanned_at", OffsetDateTime.class),
        r.get("note", String.class));
  }

  private RecountExceptionRow exceptionRow(Record r) {
    String kind = r.get("kind", String.class);
    String resolution = r.get("resolution", String.class);
    return new RecountExceptionRow(
        r.get("id", UUID.class),
        r.get("scan_id", UUID.class),
        kind,
        kindLabel(kind),
        r.get("outbound_line_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("serial_id", UUID.class),
        r.get("serial_number", String.class),
        decimalOrNullText(r, "expected_quantity"),
        decimalOrNullText(r, "scanned_quantity"),
        resolution,
        resolutionLabel(resolution),
        r.get("manual_note", String.class),
        r.get("detail", String.class),
        r.get("raised_at", OffsetDateTime.class),
        r.get("resolved_at", OffsetDateTime.class),
        r.get("resolved_by", UUID.class));
  }

  private Record sessionRecord(UUID tenant, UUID id) {
    Record r =
        sql.fetchOne(
            "select s.*, o.order_code, o.status as outbound_status, t.task_code,"
                + " w.code as warehouse_code, r.rule_code"
                + " from recount_session s"
                + " join outbound_order o on o.tenant_id=s.tenant_id and o.id=s.outbound_order_id"
                + " join pick_task t on t.tenant_id=s.tenant_id and t.id=s.pick_task_id"
                + " join warehouse w on w.tenant_id=s.tenant_id and w.id=s.warehouse_id"
                + " left join recount_rule r on r.tenant_id=s.tenant_id and r.id=s.rule_id"
                + " where s.tenant_id=? and s.id=?",
            tenant, id);
    if (r == null) throw notFound("复核单不存在");
    return r;
  }

  private RecountRow sessionRow(Record r) {
    String status = r.get("status", String.class);
    String outboundStatus = r.get("outbound_status", String.class);
    return new RecountRow(
        r.get("id", UUID.class),
        r.get("session_code", String.class),
        r.get("outbound_order_id", UUID.class),
        r.get("order_code", String.class),
        outboundStatus,
        outboundStatusLabel(outboundStatus),
        r.get("pick_task_id", UUID.class),
        r.get("task_code", String.class),
        r.get("warehouse_id", UUID.class),
        r.get("warehouse_code", String.class),
        r.get("rule_id", UUID.class),
        r.get("rule_code", String.class),
        status,
        label(status),
        r.get("open_box", Boolean.class),
        r.get("open_box_reason", String.class),
        r.get("baseline_quantity", BigDecimal.class) != null
            && ruleThreshold(r) != null
            && r.get("baseline_quantity", BigDecimal.class).compareTo(ruleThreshold(r)) >= 0,
        r.get("baseline_line_count", Integer.class),
        plain(r.get("baseline_quantity", BigDecimal.class)),
        r.get("scanned_line_count", Integer.class),
        plain(r.get("scanned_quantity", BigDecimal.class)),
        r.get("matched_line_count", Integer.class),
        r.get("mismatch_line_count", Integer.class),
        r.get("serial_checked_count", Integer.class),
        r.get("exception_count", Integer.class),
        r.get("pending_exception_count", Integer.class),
        r.get("reviewer_user_id", UUID.class),
        r.get("outbound_status_before", String.class),
        r.get("verify_basis", String.class),
        r.get("started_at", OffsetDateTime.class),
        r.get("completed_at", OffsetDateTime.class),
        r.get("cancelled_at", OffsetDateTime.class));
  }

  private BigDecimal ruleThreshold(Record sessionRow) {
    UUID ruleId = sessionRow.get("rule_id", UUID.class);
    if (ruleId == null) return null;
    Record rule = sql.fetchOne(
        "select high_value_quantity_threshold from recount_rule where tenant_id=? and id=?",
        sessionRow.get("tenant_id", UUID.class), ruleId);
    return rule == null ? null : rule.get("high_value_quantity_threshold", BigDecimal.class);
  }

  private RecountCreated created(UUID tenant, UUID id) {
    Record r = sessionRecord(tenant, id);
    String status = r.get("status", String.class);
    return new RecountCreated(
        r.get("id", UUID.class),
        r.get("session_code", String.class),
        r.get("outbound_order_id", UUID.class),
        r.get("order_code", String.class),
        r.get("pick_task_id", UUID.class),
        r.get("task_code", String.class),
        r.get("warehouse_id", UUID.class),
        textOrNull(r, "rule_id"),
        status,
        label(status),
        r.get("open_box", Boolean.class),
        r.get("open_box_reason", String.class),
        r.get("baseline_line_count", Integer.class),
        plain(r.get("baseline_quantity", BigDecimal.class)),
        r.get("matched_line_count", Integer.class),
        r.get("mismatch_line_count", Integer.class),
        r.get("exception_count", Integer.class),
        r.get("pending_exception_count", Integer.class),
        r.get("verify_basis", String.class),
        r.get("started_at", OffsetDateTime.class),
        r.get("completed_at", OffsetDateTime.class));
  }

  // ================================================================ 内部动作

  private void raise(
      UUID tenant,
      UUID sessionId,
      String kind,
      UUID scanId,
      UUID outboundLineId,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID serialId,
      String serialNumber,
      BigDecimal expected,
      BigDecimal scanned,
      String detail,
      OffsetDateTime at,
      UUID actorId) {
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into recount_exception(id,tenant_id,session_id,scan_id,kind,outbound_line_id,sku_id,"
            + "sku_code,sku_name,serial_id,serial_number,expected_quantity,scanned_quantity,detail,"
            + "raised_at) values(?,?,?,?,?,?,?,?,?,?,?,cast(? as numeric),cast(? as numeric),?,?::timestamptz)",
        id, tenant, sessionId, scanId, kind, outboundLineId, skuId,
        text(skuCode), text(skuName), serialId, serialNumber,
        expected == null ? null : expected.toPlainString(),
        scanned == null ? null : scanned.toPlainString(),
        trim(detail, 500), at);
    event(tenant, sessionId, "EXCEPTION_RAISED", null, null, actorId, at,
        kindLabel(kind) + "：" + detail);
  }

  private void event(
      UUID tenant,
      UUID sessionId,
      String type,
      String from,
      String to,
      UUID actorId,
      OffsetDateTime at,
      String detail) {
    sql.execute(
        "insert into recount_event(id,tenant_id,session_id,event_type,from_status,to_status,"
            + "actor_user_id,occurred_at,detail) values(?,?,?,?,?,?,?,?::timestamptz,?)",
        UUID.randomUUID(), tenant, sessionId, type, from, to, actorId, at, detail);
  }

  private void requireOpen(Record session, String what) {
    if (!"OPEN".equals(session.get("status", String.class)))
      throw conflict("复核单 " + session.get("session_code", String.class) + " 当前是 "
          + label(session.get("status", String.class)) + "，不能再" + what);
  }

  private void requireWarehouse(UUID tenant, UUID warehouseId) {
    if (!exists(tenant, "select 1 from warehouse where tenant_id=? and id=?", warehouseId))
      throw notFound("仓库不存在");
  }

  private String sessionCode() {
    return "RS-"
        + OffsetDateTime.now(ZoneOffset.UTC).toLocalDate().toString().replace("-", "")
        + "-"
        + UUID.randomUUID().toString().substring(0, 8);
  }

  /** 查不到就返回 null（调用方自己决定说 404 还是说人话）。 */
  private Record maybeOne(UUID tenant, String query, Object... args) {
    return sql.fetchOne(query, all(new ArrayList<>(List.of(tenant)), args));
  }

  private Record one(UUID tenant, String query, Object... args) {
    Record r = sql.fetchOne(query, all(new ArrayList<>(List.of(tenant)), args));
    if (r == null) throw notFound("对象不存在");
    return r;
  }

  private boolean exists(UUID tenant, String query, Object... args) {
    return sql.fetchOne(query, all(new ArrayList<>(List.of(tenant)), args)) != null;
  }

  private String textOf(String query, Object... args) {
    Record row = sql.fetchOne(query, args);
    return row == null ? null : row.get(0, String.class);
  }

  private UUID uuid(String raw) {
    try {
      return UUID.fromString(raw == null ? "" : raw.trim());
    } catch (IllegalArgumentException e) {
      throw bad("id 格式不合法：" + raw);
    }
  }

  private static Object[] all(List<Object> args, Object... extra) {
    var out = new ArrayList<Object>(args);
    out.addAll(List.of(extra));
    return out.toArray();
  }

  private int pageSize(int size) {
    if (size <= 0) throw bad("size 必须大于 0");
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private static void requireOneOf(String value, List<String> allowed, String field) {
    if (text(value).isEmpty()) throw bad(field + " 不能为空");
    if (!allowed.contains(value.trim()))
      throw bad(field + " 取值不合法，允许：" + String.join(" / ", allowed));
  }

  private UUID currentTenant() {
    return UUID.fromString(
        sql.fetchOne("select current_setting('app.current_tenant', true)").get(0, String.class));
  }

  private static BigDecimal decimalOrNull(Record r, String field) {
    return r.get(field, BigDecimal.class);
  }

  private static String decimalOrNullText(Record r, String field) {
    BigDecimal v = r.get(field, BigDecimal.class);
    return v == null ? null : plain(v);
  }

  private static String plain(BigDecimal v) {
    return v == null ? "0" : v.stripTrailingZeros().toPlainString();
  }

  private static String text(String s) {
    return s == null ? "" : s.trim();
  }

  private static String textOrNull(Record r, String field) {
    UUID v = r.get(field, UUID.class);
    return v == null ? null : v.toString();
  }

  private static String trim(String s, int max) {
    if (s == null) return "";
    String t = s.trim();
    return t.length() <= max ? t : t.substring(0, max);
  }

  /**
   * 时刻解析：<b>显式带偏移量</b>，不走 {@code Timestamp.valueOf(LocalDateTime)} ——
   * 那条路会把东八区的时间静默偏 −28800 秒（PDA 时钟比 DB 时钟靠前几个小时时，
   * 扫描时间会「早到凌晨」，且没有任何报错）。
   */
  private static OffsetDateTime instant(String raw, String what) {
    if (raw == null || raw.isBlank()) return OffsetDateTime.now();
    try {
      return OffsetDateTime.parse(raw.trim());
    } catch (DateTimeParseException e) {
      try {
        return java.time.LocalDateTime.parse(raw.trim())
            .atZone(java.time.ZoneId.systemDefault())
            .toOffsetDateTime();
      } catch (DateTimeParseException e2) {
        throw bad(what + " 不是合法时刻：" + raw);
      }
    }
  }

  private static String label(String status) {
    for (RecountStatus s : RecountStatus.values()) if (s.name().equals(status)) return s.label();
    return status;
  }

  private static String lineLabel(String status) {
    for (RecountLineStatus s : RecountLineStatus.values())
      if (s.name().equals(status)) return s.label();
    return status;
  }

  private static String verdictLabel(String v) {
    for (RecountVerdict s : RecountVerdict.values()) if (s.name().equals(v)) return s.label();
    return v;
  }

  private static String kindLabel(String k) {
    for (RecountExceptionKind s : RecountExceptionKind.values())
      if (s.name().equals(k)) return s.label();
    return k;
  }

  private static String resolutionLabel(String r) {
    for (RecountResolution s : RecountResolution.values()) if (s.name().equals(r)) return s.label();
    return r;
  }

  private static String eventLabel(String t) {
    for (RecountEventType s : RecountEventType.values()) if (s.name().equals(t)) return s.label();
    return t;
  }

  /**
   * 出库单状态的中文标签。
   *
   * <p>刻意复用 {@code OutboundOrderModels.OutboundOrderStatus} 这个既有词表（8.3.1.1 交付的），
   * 而不是在本类里再抄一份七态字符串：抄一份的话，哪一边改了中文名，
   * 「复核作业台」页与「出库单列表」页就会对同一个状态说两种话。
   */
  private static String outboundStatusLabel(String status) {
    for (OutboundOrderModels.OutboundOrderStatus s :
        OutboundOrderModels.OutboundOrderStatus.values())
      if (s.name().equals(status)) return s.label();
    return status;
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}