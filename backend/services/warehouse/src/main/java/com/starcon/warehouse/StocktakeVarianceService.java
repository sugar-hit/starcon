package com.starcon.warehouse;

import static com.starcon.warehouse.StocktakeVarianceModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 7.4.2.2 盘点差异处理：归因（五类原因）→ 处理决定（三选）→ 调整单 → 审批 → 入账 → 完成报告。
 *
 * <p>蓝图原话：「处理盘点差异：按差异原因（损耗、错发、偷盗、未入账、时点差）分类，生成调整单
 * 走审批，盘盈盘亏入账并可追责。」以及本模块的红线：
 * 「**差异不直接改余额**，必须经调整单与审批，这是实体目录明确的红线。」
 *
 * <p><b>本模块的入账路径只有一条，而且每一段都被数据库检查过</b>：
 *
 * <pre>
 *   差异明细（读 7.4.2.1 的 stocktake_line，本模块不重算）
 *      └─ 归因（原因 × 决定）─ ADJUST → 调整单 DRAFT
 *                                   └─ submit  → PENDING_APPROVAL
 *                                        └─ approve（提交人≠审批人）→ APPROVED
 *                                             └─ post → inventory_transaction(ADJUSTMENT)
 *                                                       + inventory_entry（带符号分录）
 *                                                       + inventory_balance / stock_bucket
 *                                               = POSTED
 * </pre>
 *
 * <p>红线为什么不能只写在注释里：V62 的三个触发器分别保证
 * （1）调整单状态机只认 {@code APPROVED → POSTED}，
 * （2）{@code inventory_entry} 拒绝对未审批的盘点调整单写分录（入账闸门），
 * （3）Σ分录 必须等于 Σ调整单行（不许少记/多记/只入一部分）。
 * 拿到连接直接 INSERT 也绕不过去 —— 而绕过的那条路正是「任意改账」。
 *
 * <p><b>调整单落成实体而不是等 7.4.1.1</b>：蓝图把 7.4.1.1 列为下游而它尚未交付。
 * 没有下游，差异就会永远挂着，账面与实物越差越远。所以本模块自落地一个调整单走完闭环，
 * 并在 {@code /capabilities} 与报告里如实声明它是 7.4.2.2 的调整单、不是 7.4.1.1 的实现；
 * 交接靠 V1 既有的定位键 {@code inventory_transaction(ref_type='STOCKTAKE_VARIANCE', ref_id=单号id)}，
 * 跨服务不建 FK、不查对方的表。
 *
 * <p><b>审批没有接入 17.4</b>：审批中心尚未交付，本模块用的是可审计的替代机制 ——
 * DB 级状态机 + 「提交人 ≠ 审批人」职责分离 + append-only 审计表，三样都落在库里。
 * 17.4 交付后只需把 approve/reject 的判定换成调用审批中心，表结构与状态机不动。
 *
 * <p><b>入账改的是余额与库位，不改账面数</b>：{@code stocktake_line.book_on_hand} 由 V59 的
 * {@code stocktake_line_frozen} 触发器钉死，本模块连碰都不碰它。差异是一次性修正，
 * 用带符号增量入账（{@code on_hand_delta}）而不是把 bucket 覆盖成 counted ——
 * 冻结之后仓库照常收发，把 bucket 直接改成 counted 会把盘点期间那些正常交易一起抹掉。
 *
 * <p><b>时间绑定纪律</b>：{@code ?::timestamptz} 一律出现在语句最前面的 {@code t} CTE 里，
 * 后面全是普通绑定，cast 永远不在绑定序列末位（与 V59 / 7.1.2.2 / 7.2.2.1 同一条纪律）。
 * 写入用 {@link OffsetDateTime} 绑定，不走 {@code Timestamp.valueOf} —— 后者按 JVM 本地时区
 * 解释裸本地时间，在东八区会静默偏 −28800s。
 */
@Service
public class StocktakeVarianceService {

  /** 单页最大行数：差异明细是「这次要处理什么」的清单，量级可控但仍设上限。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 单张调整单最多收多少行差异。差异逐条追责，一次打包上万行会让审批变成点一下。 */
  public static final int MAX_ADJUSTMENT_LINES = 2000;

  /** 审计时间线单次返回上限。追责要看全量，导出走分页。 */
  static final int MAX_AUDIT_ROWS = 500;

  /** numeric(19,4) 口径下的零：避免同一列里混着 scale 不一样的 0。 */
  private static final BigDecimal ZERO = new BigDecimal("0.0000");

  /** 比例的精度：百分比保留 4 位小数（0.1234%），够读且可复核。 */
  private static final int RATE_SCALE = 4;

  private static final DateTimeFormatter CODE_DAY =
      DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

  /**
   * 定位键：本模块入账时写进 {@code inventory_transaction} 的 ref_type。
   *
   * <p>它同时是交给 7.4.1.1 的对接键 —— 对方按 {@code (ref_type, ref_id)} 把这批
   * 已入账单据读回，而不需要访问本服务的表。
   */
  static final String REF_TYPE = "STOCKTAKE_VARIANCE";

  private final DSLContext sql;

  public StocktakeVarianceService(DSLContext sql) {
    this.sql = sql;
  }

  // ============================================================ 能力

  /**
   * 差异处理能力：五类原因、三种决定、五种筛选，以及三条必须如实讲清楚的说明。
   *
   * <p>前端据此渲染选项与提示，而不是把规则硬编码在界面上 —— 规则改了就该只改一处。
   */
  public StocktakeVarianceCapabilities capabilities() {
    List<VarianceHandlingOption> reasons = new ArrayList<>();
    for (var r : StocktakeVarianceReason.values()) {
      reasons.add(new VarianceHandlingOption(r.name(), r.label(), r.description(), true, true));
    }
    List<VarianceHandlingOption> decisions = new ArrayList<>();
    for (var d : StocktakeVarianceDecision.values()) {
      decisions.add(
          new VarianceHandlingOption(
              d.name(),
              d.label(),
              d.description(),
              true,
              d == StocktakeVarianceDecision.ADJUST));
    }
    List<String> filters = new ArrayList<>();
    for (var f : StocktakeVarianceLineFilter.values()) {
      filters.add(f.name());
    }
    return new StocktakeVarianceCapabilities(
        reasons,
        decisions,
        filters,
        false,
        "金额口径的差异率/盘盈率**算不出来**：全库（warehouse 全部迁移 + catalog 全部迁移）"
            + "不存在任何库存成本列，catalog 侧只有供应商报价分层 —— 那是跨服务数据且不是库存成本，"
            + "按 AGENTS.md 不能跨服务查它的表。本报告因此只出**数量**口径的率。"
            + "编一个金额数比不给出金额数危险得多：它会被当成财务已认可的价值。",
        "本模块**没有接入 17.4 审批中心**（它尚未交付），用的是可审计的替代机制："
            + "数据库级状态机（未 APPROVED 不可能变成 POSTED）+ 提交人≠审批人的职责分离 + "
            + "append-only 审计表。17.4 交付后把 approve/reject 的判定换成调用审批中心即可，"
            + "表结构与状态机不动。",
        "本模块的调整单是 7.4.2.2 为了走完「差异 → 审批 → 入账」闭环而落地的，"
            + "**不是**蓝图下游 7.4.1.1 的实现。交接靠 inventory_transaction 的 "
            + "ref_type='STOCKTAKE_VARIANCE' + ref_id=<调整单 id> 这对定位键："
            + "7.4.1.1 交付后按这对键把这批已入账单据读回即可，跨服务不建 FK、不查对方的表。");
  }

  // ============================================================ 差异明细

  /**
   * 差异明细分页 = 7.4.2.1 的三列 + 本模块的处置。
   *
   * <p>差异三列**直接读 {@code stocktake_line}**，本模块一个数都不自己算：
   * 差异是上游的 generated column（{@code counted_qty − book_on_hand}），
   * 这里再算一遍就会出现「界面上算的差异」与「库里存的差异」两套数。
   */
  public StocktakeVarianceLinePage lines(
      UUID sheetId, String filter, String reason, String decision, String keyword, int pageNo, int size) {
    requireSheet(sheetId);
    int limit = Math.min(MAX_PAGE_SIZE, Math.max(1, size <= 0 ? 50 : size));
    int offset = Math.max(0, pageNo) * limit;
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("l.tenant_id = ? and l.sheet_id = ?");
    binds.add(tenant());
    binds.add(sheetId);
    // 只有「已录入且真的不相符」的行才是差异。未录入行的 variance 是 NULL，
    // 把它算成 0 会让一张还没盘完的单看起来「全部账实相符」。
    where.add("l.counted_qty is not null and l.variance is not null and l.variance <> 0");
    switch (lineFilter(filter)) {
      case UNATTRIBUTED -> where.add("a.id is null");
      case IN_ADJUSTMENT -> where.add("a.decision = 'ADJUST' and a.resolved_at is null");
      case POSTED -> where.add("al.posted_at is not null");
      case RECOUNTED -> where.add("a.decision = 'RECOUNT'");
      case ALL -> {
        // 无附加条件。
      }
    }
    if (notBlank(reason)) {
      where.add("a.reason = ?");
      binds.add(reason.trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(decision)) {
      where.add("a.decision = ?");
      binds.add(decision.trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(keyword)) {
      where.add(
          "(l.sku_code ilike ? or l.sku_name ilike ? or l.location_code ilike ?"
              + " or l.lot_code ilike ? or l.counted_barcode ilike ? or a.note ilike ?)");
      String k = "%" + keyword.trim() + "%";
      for (int i = 0; i < 6; i++) {
        binds.add(k);
      }
    }
    String sqlWhere = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from " + LINE_FROM + sqlWhere, binds.toArray()).get(0, Long.class);
    var rows =
        sql.fetch(
            "select " + LINE_COLUMNS + " from " + LINE_FROM + sqlWhere
                // 未归因（还没干的活）排前面，已入账沉底；同组内按差异绝对值从大到小。
                + " order by (a.id is null) desc, a.decision, abs(l.variance) desc, l.sku_code,"
                + " l.location_code, l.lot_code, l.id"
                + " limit ? offset ?",
            append(binds, limit, (long) offset).toArray());
    return new StocktakeVarianceLinePage(
        rows.stream().map(StocktakeVarianceService::line).toList(), total, Math.max(0, pageNo), limit);
  }

  // ============================================================ 归因

  /**
   * 归因一条差异：五类原因 × 三种决定 + 说明。
   *
   * <p>三种决定的后果各自不同，不是记一笔备注：
   *
   * <ul>
   *   <li>{@code ADJUST} —— 落一条**未结**归因，等它被收进调整单；此时不改任何余额。
   *   <li>{@code RECOUNT} —— 把这一行的实盘数退回未录（counted_qty/at/by 三列一起清空），
   *       差异列随之回到 NULL，这一行重新变成「还没盘」。同时重算盘点单状态：
   *       COUNTED 退回 COUNTING，因为确实又出现了一行没录的。
   *   <li>{@code IGNORE} —— 直接结案（resolved_by='IGNORED'）。忽略过的行**不允许再被归因**
   *       （V62 触发器），否则「先忽略再偷偷改成调整」就是消音器的标准变体。
   * </ul>
   *
   * <p>归因要求这条行真的有差异：variance 为 NULL 是未录入、variance = 0 是账实相符，
   * 两者都不是差异处理的对象。
   */
  @Transactional
  public StocktakeVarianceLine attribute(UUID sheetId, UUID lineId, StocktakeVarianceAttributionInput input, UUID actorId) {
    requireSheet(sheetId);
    if (input == null) {
      throw bad("请求体不能为空");
    }
    StocktakeVarianceReason reason = reason(input.reason());
    StocktakeVarianceDecision decision = decision(input.decision());
    String note = requireNote(decision, input.note());

    var line =
        sql.fetchOne(
            "select id,counted_qty,variance,counted_at,counted_by_user_id,counted_barcode,counted_note"
                + " from stocktake_line where tenant_id = ? and sheet_id = ? and id = ?",
            tenant(),
            sheetId,
            lineId);
    if (line == null) {
      throw notFound("盘点差异明细", lineId);
    }
    if (line.get("counted_qty", BigDecimal.class) == null) {
      throw bad("这一行还没有实盘数，不能归因：未录入不是差异。请先在盘点单上录入实盘数。");
    }
    if (line.get("variance", BigDecimal.class).signum() == 0) {
      throw bad("这一行账实相符，没有差异可处理。差异处理的对象是实盘 ≠ 账面的行。");
    }

    OffsetDateTime now = OffsetDateTime.now();
    if (decision == StocktakeVarianceDecision.RECOUNT) {
      return recount(sheetId, lineId, reason, note, actorId, now);
    }

    boolean ignore = decision == StocktakeVarianceDecision.IGNORE;
    sql.execute(
        "insert into stocktake_variance_attribution(id,tenant_id,sheet_id,line_id,reason,decision,note,"
            + "attributed_by_user_id,attributed_at,resolved_at,resolved_by) "
            // 两个 timestamptz 都显式 cast：纯文本语句里没有列上下文的绑定会被 jOOQ
            // 当成 varchar 送进去，PG 直接报「expression is of type character varying」。
            // 传 null 时它不报错（非 NULL 才炸），所以这一条必须两个槽位都写。
            + "values(?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?)",
        UUID.randomUUID(),
        tenant(),
        sheetId,
        lineId,
        reason.name(),
        decision.name(),
        note,
        actorId,
        now,
        ignore ? now : null,
        ignore ? "IGNORED" : null);
    audit(
        sheetId,
        lineId,
        null,
        ignore ? "IGNORED" : "ATTRIBUTED",
        actorId,
        now,
        (ignore ? "忽略" : "归因") + "：" + reason.label() + " · " + decision.label() + " · " + note
            + "（差异 " + line.get("variance", BigDecimal.class).toPlainString() + "）");

    return requireLine(sheetId, lineId);
  }

  /**
   * 复盘：把实盘数退回未录，这一行重新变成「还没盘」。
   *
   * <p>清空 {@code counted_qty} 必须同时清 {@code counted_at} 与 {@code counted_by_user_id}：
   * V59 的 {@code stocktake_line_counted} 触发器要求三者同进同出，缺一半就意味着
   * 「谁在什么时候盘的」丢了 —— 而那一栏正是差异追责时要问的第一个问题。
   *
   * <p>账面数一个字都不碰：{@code stocktake_line_frozen} 触发器钉死了它，
   * 而本模块也**不应该**碰它 —— 复盘要重新数的是实物，不是账。
   */
  private StocktakeVarianceLine recount(
      UUID sheetId, UUID lineId, StocktakeVarianceReason reason, String note, UUID actorId, OffsetDateTime now) {
    // ?::timestamptz 不在绑定序列末位：三个 null 绑定排在它后面（cleared 标记的绑定顺序见下）。
    sql.execute(
        "update stocktake_line set counted_qty = null, counted_at = null, counted_by_user_id = null,"
            + " counted_barcode = null, counted_note = null"
            + " where tenant_id = ? and sheet_id = ? and id = ? and counted_qty is not null",
        tenant(),
        sheetId,
        lineId);
    sql.execute(
        "insert into stocktake_variance_attribution(id,tenant_id,sheet_id,line_id,reason,decision,note,"
            + "attributed_by_user_id,attributed_at,resolved_at,resolved_by) "
            + "values(?,?,?,?,?,'RECOUNT',?,?,?::timestamptz,?::timestamptz,'RECOUNTED')",
        UUID.randomUUID(),
        tenant(),
        sheetId,
        lineId,
        reason.name(),
        note,
        actorId,
        now,
        now);
    audit(
        sheetId,
        lineId,
        null,
        "RECOUNTED",
        actorId,
        now,
        "复盘：" + reason.label() + " · " + note + " —— 实盘数已退回未录，这一行要重新盘一次");
    // 盘点单状态重算：出现了新的未录行，COUNTED 必须退回 COUNTING。
    // 这里**不复用** StocktakeService 的私有方法 —— 那是 7.4.2.1 的文件，本模块不改别人的文件。
    // 四行聚合，重算而不是「往前推一格」，复盘/再次录入都会自然落回正确状态。
    var counts =
        sql.fetchOne(
            "select count(*) as total, count(counted_qty) as done from stocktake_line"
                + " where tenant_id = ? and sheet_id = ?",
            tenant(),
            sheetId);
    long total = counts.get("total", Long.class);
    long done = counts.get("done", Long.class);
    String next = done == 0 ? "OPEN" : done >= total ? "COUNTED" : "COUNTING";
    sql.execute(
        "update stocktake_sheet set status = ? where tenant_id = ? and id = ?", next, tenant(), sheetId);
    return requireLine(sheetId, lineId);
  }

  // ============================================================ 调整单

  /**
   * 生成调整单（草稿），把已归因为「调整」且还没进任何单据的差异收进来。
   *
   * <p>只收**未结的 ADJUST 归因**，且每行都要真的查到该行 —— 收进来却查不到归因，
   * 意味着有人传了一个没归因的行 id，那会让「谁判的、为什么」这条链断掉。
   *
   * <p>零行不建单：台账上一张零行单据看起来像「处理过了」，那比报错危险得多。
   * 数量 {@code qty_delta} 一律**读** {@code stocktake_line.variance} 写进快照，
   * 不接受请求参数传入 —— 让人手填调整量等于允许调整量与差异量对不上。
   */
  @Transactional
  public StocktakeVarianceAdjustmentDetail createAdjustment(
      UUID sheetId, StocktakeVarianceAdjustCreateInput input, UUID actorId) {
    requireSheet(sheetId);
    if (input == null) {
      throw bad("请求体不能为空");
    }
    var sheet = sql.fetchOne("select code from stocktake_sheet where tenant_id = ? and id = ?", tenant(), sheetId);
    OffsetDateTime now = OffsetDateTime.now();
    String code = "ADJ-ST-" + CODE_DAY.format(now) + "-"
        + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);

    // 先建单头（下面立即插行），行数校验在插行后做：V62 的行表有 adjustment 外键，头必须在。
    UUID adjustmentId = UUID.randomUUID();
    sql.execute(
        "insert into stocktake_variance_adjustment(id,tenant_id,sheet_id,code,status,note,created_by_user_id,"
            + "created_at) values(?,?,?,?,'DRAFT',?,?,?::timestamptz)",
        adjustmentId,
        tenant(),
        sheetId,
        code,
        input.note() == null ? "" : input.note().trim(),
        actorId,
        now);

    boolean all = Boolean.TRUE.equals(input.allAttributed());
    List<UUID> explicit = input.lineIds() == null ? List.of() : input.lineIds();
    if (!all && explicit.isEmpty()) {
      throw bad("要么给出 lineIds（要调整哪些差异行），要么给 allAttributed=true 收走全部已归因的待调整行。");
    }

    int inserted;
    if (all) {
      inserted = sql.execute(
          "insert into stocktake_variance_adjustment_line(id,tenant_id,adjustment_id,sheet_id,line_id,"
              + "bucket_id,balance_id,sku_id,sku_code,sku_name,warehouse_code,location_id,location_code,"
              + "lot_id,lot_code,quality_status_code,qty_delta,reason,note,created_at) "
              + "select gen_random_uuid(), l.tenant_id, ?, l.sheet_id, l.id, l.bucket_id, k.balance_id,"
              + " l.sku_id, l.sku_code, l.sku_name, l.warehouse_code, l.location_id, l.location_code,"
              + " l.lot_id, l.lot_code, l.quality_status_code, l.variance, a.reason, a.note, l.created_at "
              + "from stocktake_line l "
              + "join stock_bucket k on k.tenant_id = l.tenant_id and k.id = l.bucket_id "
              + "join stocktake_variance_attribution a on a.tenant_id = l.tenant_id and a.line_id = l.id "
              + "where l.tenant_id = ? and l.sheet_id = ? and l.counted_qty is not null and l.variance <> 0"
              + " and a.decision = 'ADJUST' and a.resolved_at is null and a.adjustment_id is null",
          adjustmentId,
          tenant(),
          sheetId);
      sql.execute(
          "update stocktake_variance_attribution a set adjustment_id = ? "
              + "from stocktake_line l "
              + "where a.tenant_id = l.tenant_id and a.line_id = l.id and a.tenant_id = ? and l.sheet_id = ?"
              + " and a.decision = 'ADJUST' and a.resolved_at is null and a.adjustment_id is null",
          adjustmentId,
          tenant(),
          sheetId);
    } else {
      inserted = insertExplicitLines(adjustmentId, sheetId, explicit);
    }
    if (inserted == 0) {
      throw bad(
          "这张盘点单下没有可调整的差异行（需要：已录入、有差异、归因为「调整」、"
              + "且还没进过任何调整单）。如果差异都归因成了「复盘」或「忽略」，"
              + "或者已经在别的单据里，就不会有可收的行。");
    }
    if (inserted > MAX_ADJUSTMENT_LINES) {
      throw bad("一次要调整 " + inserted + " 行差异，超过单张调整单 " + MAX_ADJUSTMENT_LINES
          + " 行的上限：差异是逐条追责的，这么多行打包在一起审批等于没有审批。请分批处理。");
    }
    audit(
        sheetId,
        null,
        adjustmentId,
        "ADJUSTMENT_CREATED",
        actorId,
        now,
        "生成调整单 " + code + "，收进 " + inserted + " 行差异");
    return detail(adjustmentId);
  }

  /**
   * 显式指定行的写入。逐行插而不是一条 INSERT ... SELECT：
   * 这样每行都能校验「确实有未结的 ADJUST 归因」，缺哪一行当场说得出是哪一行。
   */
  private int insertExplicitLines(UUID adjustmentId, UUID sheetId, List<UUID> lineIds) {
    int n = 0;
    for (UUID lineId : lineIds) {
      var row =
          sql.fetchOne(
              "select l.id,l.sheet_id,l.bucket_id,k.balance_id,l.sku_id,l.sku_code,l.sku_name,"
                  + "l.warehouse_code,l.location_id,l.location_code,l.lot_id,l.lot_code,"
                  + "l.quality_status_code,l.variance,a.reason,a.note "
                  + "from stocktake_line l "
                  + "join stock_bucket k on k.tenant_id = l.tenant_id and k.id = l.bucket_id "
                  + "left join stocktake_variance_attribution a on a.tenant_id = l.tenant_id"
                  + " and a.line_id = l.id and a.resolved_at is null "
                  + "where l.tenant_id = ? and l.sheet_id = ? and l.id = ?",
              tenant(),
              sheetId,
              lineId);
      if (row == null) {
        throw notFound("盘点差异明细", lineId);
      }
      if (row.get("balance_id", UUID.class) == null) {
        throw bad("差异行 " + lineId + " 对应的库位库存行已不可定位，无法入账（余额行缺失）。");
      }
      if (row.get("variance", BigDecimal.class) == null || row.get("variance", BigDecimal.class).signum() == 0) {
        throw bad("差异行 " + lineId + " 没有差异可调整（未录入或账实相符）。");
      }
      String reason = row.get("reason", String.class);
      if (reason == null) {
        throw bad("差异行 " + lineId + " 还没有归因，不能收进调整单。请先归因（原因 × 处理决定）。");
      }
      sql.execute(
          "insert into stocktake_variance_adjustment_line(id,tenant_id,adjustment_id,sheet_id,line_id,"
              + "bucket_id,balance_id,sku_id,sku_code,sku_name,warehouse_code,location_id,location_code,"
              + "lot_id,lot_code,quality_status_code,qty_delta,reason,note,created_at) "
              + "values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
          UUID.randomUUID(),
          tenant(),
          adjustmentId,
          sheetId,
          lineId,
          row.get("bucket_id", UUID.class),
          row.get("balance_id", UUID.class),
          row.get("sku_id", UUID.class),
          row.get("sku_code", String.class),
          row.get("sku_name", String.class),
          row.get("warehouse_code", String.class),
          row.get("location_id", UUID.class),
          row.get("location_code", String.class),
          row.get("lot_id", UUID.class),
          row.get("lot_code", String.class),
          row.get("quality_status_code", String.class),
          row.get("variance", BigDecimal.class),
          reason,
          row.get("note", String.class) == null ? "" : row.get("note", String.class),
          OffsetDateTime.now());
      sql.execute(
          "update stocktake_variance_attribution set adjustment_id = ? where tenant_id = ? and line_id = ?"
              + " and resolved_at is null",
          adjustmentId,
          tenant(),
          lineId);
      n++;
    }
    return n;
  }

  /** 提交审批：DRAFT → PENDING_APPROVAL。 */
  @Transactional
  public StocktakeVarianceAdjustment submit(UUID adjustmentId, StocktakeVarianceDecisionInput input, UUID actorId) {
    var a = requireAdjustment(adjustmentId);
    if (a.status() != StocktakeVarianceAdjustmentStatus.DRAFT) {
      throw conflict("调整单 " + a.code() + " 当前是「" + a.status().label() + "」，只有草稿可以提交审批。");
    }
    long lines = countLines(adjustmentId);
    if (lines == 0) {
      throw bad("调整单 " + a.code() + " 没有任何行，不能提交审批。");
    }
    OffsetDateTime now = OffsetDateTime.now();
    // ?::timestamptz 在第 4 位，后面还有两个普通绑定，cast 不落在末位。
    sql.execute(
        "update stocktake_variance_adjustment set status = 'PENDING_APPROVAL', submitted_by_user_id = ?,"
            + " submitted_at = ?::timestamptz, note = ? where tenant_id = ? and id = ? and status = 'DRAFT'",
        actorId,
        now,
        input == null || input.note() == null ? "" : input.note().trim(),
        tenant(),
        adjustmentId);
    audit(
        a.sheetId(),
        null,
        adjustmentId,
        "SUBMITTED",
        actorId,
        now,
        "提交审批：" + a.code() + "，共 " + lines + " 行差异"
            + (input == null || input.note() == null || input.note().isBlank()
                ? ""
                : " · " + input.note().trim()));
    return requireAdjustment(adjustmentId);
  }

  /**
   * 批准：PENDING_APPROVAL → APPROVED。
   *
   * <p><b>提交人不能审批自己提的单</b>：蓝图 7.4.1.2 点名「库存调整是舞弊高发区
   * （无审批的调整 = 任意改账）」，而审批的全部价值就在「另一个人看过」。
   * 一个人自提自批的审批与没有审批是同一件事。
   */
  @Transactional
  public StocktakeVarianceAdjustment approve(UUID adjustmentId, StocktakeVarianceDecisionInput input, UUID actorId) {
    var a = requirePending(adjustmentId, "批准");
    if (a.submittedByUserId() != null && a.submittedByUserId().equals(actorId)) {
      throw conflict(
          "不能审批自己提交的调整单 " + a.code() + "：盘点差异可能追责到人，自提自批的审批"
              + "等于没有审批。请换一位有 warehouse.inventory.update 权限的同事批准。");
    }
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update stocktake_variance_adjustment set status = 'APPROVED', decided_by_user_id = ?,"
            + " decided_at = ?::timestamptz, decision_note = ?"
            + " where tenant_id = ? and id = ? and status = 'PENDING_APPROVAL'",
        actorId,
        now,
        input == null || input.note() == null ? "" : input.note().trim(),
        tenant(),
        adjustmentId);
    audit(
        a.sheetId(),
        null,
        adjustmentId,
        "APPROVED",
        actorId,
        now,
        "批准调整单 " + a.code() + (input == null || input.note() == null || input.note().isBlank()
            ? ""
            : " · " + input.note().trim()) + "。注意：批准只是允许入账，**余额此刻仍未改动**，入账要再走一步。");
    return requireAdjustment(adjustmentId);
  }

  /** 驳回：PENDING_APPROVAL → REJECTED，意见必填；同时释放归因，让处理人可以重新归因。 */
  @Transactional
  public StocktakeVarianceAdjustment reject(UUID adjustmentId, StocktakeVarianceDecisionInput input, UUID actorId) {
    var a = requirePending(adjustmentId, "驳回");
    String note = requireText(input == null ? null : input.note(), "驳回必须写意见：只给一个 REJECTED 状态，提交的人无法知道该改什么。");
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update stocktake_variance_adjustment set status = 'REJECTED', decided_by_user_id = ?,"
            + " decided_at = ?::timestamptz, decision_note = ?"
            + " where tenant_id = ? and id = ? and status = 'PENDING_APPROVAL'",
        actorId,
        now,
        note,
        tenant(),
        adjustmentId);
    releaseAttributions(adjustmentId, "REJECTED", now);
    audit(
        a.sheetId(),
        null,
        adjustmentId,
        "REJECTED",
        actorId,
        now,
        "驳回调整单 " + a.code() + " · " + note + "。相关差异行已释放，可重新归因后再次生成调整单。");
    return requireAdjustment(adjustmentId);
  }

  /** 作废：任何未过账状态 → CANCELLED。已过账的单据不能作废（账已经动了）。 */
  @Transactional
  public StocktakeVarianceAdjustment cancel(UUID adjustmentId, StocktakeVarianceDecisionInput input, UUID actorId) {
    var a = requireAdjustment(adjustmentId);
    if (a.status() == StocktakeVarianceAdjustmentStatus.POSTED) {
      throw conflict("调整单 " + a.code() + " 已经过账入账，不能作废：账已经动了。冲正请发起新的调整单。");
    }
    OffsetDateTime now = OffsetDateTime.now();
    String note = input == null || input.note() == null || input.note().isBlank()
        ? "未写原因"
        : input.note().trim();
    sql.execute(
        "update stocktake_variance_adjustment set status = 'CANCELLED', cancelled_by_user_id = ?,"
            + " cancelled_at = ?::timestamptz, decision_note = coalesce(nullif(decision_note,''), ?)"
            + " where tenant_id = ? and id = ? and status <> 'POSTED'",
        actorId,
        now,
        note,
        tenant(),
        adjustmentId);
    releaseAttributions(adjustmentId, "CANCELLED", now);
    audit(
        a.sheetId(),
        null,
        adjustmentId,
        "CANCELLED",
        actorId,
        now,
        "作废调整单 " + a.code() + " · " + note + "。相关差异行已释放，可重新归因。");
    return requireAdjustment(adjustmentId);
  }

  // ============================================================ 入账（红线所在）

  /**
   * 入账：APPROVED → POSTED。**这是本模块唯一会改库存余额的路径。**
   *
   * <p>一个事务里做五件事：写业务单（kind='ADJUSTMENT'、ref_type 定位键）→ 按行写带符号分录
   * → 改余额行 → 改库位明细 → 标记行与归因已结案、写审计。
   *
   * <p><b>为什么用带符号增量而不是把 bucket 覆盖成 counted</b>：冻结之后仓库照常收发，
   * bucket 现在的数已经包含盘点期间的正常出入库。直接覆盖成实盘数会把那些正常交易一起抹掉，
   * 账面反而错得更多。差异是一次性修正，所以是 {@code on_hand_delta = variance}。
   *
   * <p><b>会把余额做成负数的调整单在这里被挡下</b>：{@code inventory_balance} 有
   * {@code CHECK(on_hand>=0)}，而那句报错完全指向不到「是这条盘点差异的调整单把它扣成负的」。
   * 所以先自己判一次，给出能读懂的话。
   *
   * <p><b>入账数量守恒</b>由 V62 的延迟约束触发器保证：Σ分录 必须等于 Σ调整单行，
   * 不许少记/多记/只入一部分。
   */
  @Transactional
  public StocktakeVariancePostResult post(UUID adjustmentId, StocktakeVarianceDecisionInput input, UUID actorId) {
    var a = requireAdjustment(adjustmentId);
    if (a.status() != StocktakeVarianceAdjustmentStatus.APPROVED) {
      throw conflict(
          "调整单 " + a.code() + " 当前是「" + a.status().label() + "」，只有**已批待入账**的单据才能入账。"
              + "未审批的调整单不动余额 —— 差异不直接改余额，必须经调整单与审批。");
    }
    var lines = sql.fetch(
        "select id,line_id,bucket_id,balance_id,qty_delta,sku_code,location_code,lot_code,reason"
            + " from stocktake_variance_adjustment_line where tenant_id = ? and adjustment_id = ?"
            + " order by id",
        tenant(),
        adjustmentId);
    if (lines.isEmpty()) {
      throw bad("调整单 " + a.code() + " 没有任何行，入账会导致「入账数量 0 = 明细合计 0」的空过账。");
    }

    OffsetDateTime now = OffsetDateTime.now();
    // t CTE 让 ?::timestamptz 成为第 1 个绑定，cast 不落在绑定序列末位。
    UUID transactionId = UUID.randomUUID();
    String note = input != null && input.note() != null && !input.note().isBlank()
        ? input.note().trim()
        : (a.note() == null || a.note().isBlank() ? "盘点差异调整单 " + a.code() : a.note());
    sql.execute(
        "with t as (select ?::timestamptz as at) "
            + "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id,"
            + "notes,created_at) select ?,?,'ADJUSTMENT',?,?,t.at,?,?,t.at from t",
        now,
        transactionId,
        tenant(),
        REF_TYPE,
        adjustmentId,
        actorId,
        note);

    // 同一余额行可能被本单的多个库位行命中：按余额聚合一次 UPDATE，
    // 逐行 UPDATE 会让同一行 on_hand 被反复读写，中途的守恒核对也失去意义。
    var byBalance = new LinkedHashMap<UUID, BigDecimal>();
    for (var r : lines) {
      UUID balanceId = r.get("balance_id", UUID.class);
      BigDecimal delta = r.get("qty_delta", BigDecimal.class);
      byBalance.merge(balanceId, delta, BigDecimal::add);
    }
    for (var e : byBalance.entrySet()) {
      guardNotNegative("inventory_balance", e.getKey(), e.getValue());
      sql.execute(
          "update inventory_balance set on_hand = on_hand + ?::numeric, version = version + 1"
              + " where tenant_id = ? and id = ?",
          e.getValue(),
          tenant(),
          e.getKey());
    }

    int entries = 0;
    for (var r : lines) {
      UUID balanceId = r.get("balance_id", UUID.class);
      BigDecimal delta = r.get("qty_delta", BigDecimal.class);
      UUID bucketId = r.get("bucket_id", UUID.class);
      guardNotNegative("stock_bucket", bucketId, delta);
      sql.execute(
          "update stock_bucket set on_hand = on_hand + ?::numeric, last_movement_at = ?::timestamptz"
              + " where tenant_id = ? and id = ?",
          delta,
          now,
          tenant(),
          bucketId);
      // 一行一条分录：Σ分录 == Σ调整单行 是 V62 的约束，所以分行写才对得上账。
      UUID entryId = UUID.randomUUID();
      sql.execute(
          "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,reserved_delta)"
              + " values(?,?,?,?,?,0::numeric)",
          entryId,
          tenant(),
          transactionId,
          balanceId,
          delta);
      sql.execute(
          "update stocktake_variance_adjustment_line set posted_at = ?::timestamptz, posted_by_user_id = ?,"
              + " entry_id = ?, transaction_id = ? where tenant_id = ? and id = ?",
          now,
          actorId,
          entryId,
          transactionId,
          tenant(),
          r.get("id", UUID.class));
      entries++;
    }

    sql.execute(
        "update stocktake_variance_attribution set resolved_at = ?::timestamptz, resolved_by = 'POSTED'"
            + " where tenant_id = ? and adjustment_id = ? and resolved_at is null",
        now,
        tenant(),
        adjustmentId);
    sql.execute(
        "update stocktake_variance_adjustment set status = 'POSTED', posted_by_user_id = ?,"
            + " posted_at = ?::timestamptz where tenant_id = ? and id = ? and status = 'APPROVED'",
        actorId,
        now,
        tenant(),
        adjustmentId);
    audit(
        a.sheetId(),
        null,
        adjustmentId,
        "POSTED",
        actorId,
        now,
        "入账 " + a.code() + "：写 " + entries + " 条 ADJUSTMENT 分录（ref_type=" + REF_TYPE
            + "），净调整 " + a.netQty().toPlainString() + "。差异至此才真正改变库存余额。");

    return new StocktakeVariancePostResult(
        requireAdjustment(adjustmentId),
        transactionId,
        entries,
        List.copyOf(byBalance.keySet()),
        a.netQty(),
        "已入账：写 " + entries + " 条库存分录，净调整 " + a.netQty().toPlainString()
            + "（盘盈 " + a.gainQty().toPlainString() + " / 盘亏 " + a.lossQty().toPlainString() + "）。"
            + "业务单 " + transactionId + " 的 ref_type=" + REF_TYPE + "、ref_id=" + adjustmentId
            + "，这是将来交给 7.4.1.1 调整单管理的定位键。");
  }

  /**
   * 余额 / 库位不许被调成负数。
   *
   * <p>DB 有 CHECK，但那句报错完全指向不到「是这张盘点调整单把它扣成负的」。
   * 这里先判一次，把该说的话说清楚：盘亏的量超过了现在的在手，说明冻结之后这批货已经
   * 卖出/发出去了，这不是差异处理能解决的问题。
   */
  private void guardNotNegative(String table, UUID id, BigDecimal delta) {
    if (delta.signum() >= 0) {
      return;
    }
    var row = sql.fetchOne("select on_hand from " + table + " where tenant_id = ? and id = ?", tenant(), id);
    if (row == null) {
      throw bad("入账目标 " + table + " " + id + " 不存在（或不属于当前租户）。");
    }
    BigDecimal after = row.get("on_hand", BigDecimal.class).add(delta);
    if (after.signum() < 0) {
      throw conflict(
          "这条盘亏调整会把 " + table + " 的在手从 " + row.get("on_hand", BigDecimal.class).toPlainString()
              + " 扣成 " + after.toPlainString() + "（负数），已阻止入账。"
              + "冻结之后这批货已经卖出或发出去了：请先核对该库位冻结之后的流水，"
              + "或把差异归因成「时点差」而不是盘亏。");
    }
  }

  // ============================================================ 查询

  /** 调整单列表（真分页；行数与三个数量由明细聚合得出，不另存列）。 */
  public StocktakeVarianceAdjustmentPage adjustments(
      String status, UUID sheetId, String keyword, int pageNo, int size) {
    int limit = Math.min(MAX_PAGE_SIZE, Math.max(1, size <= 0 ? 20 : size));
    int offset = Math.max(0, pageNo) * limit;
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("a.tenant_id = ?");
    binds.add(tenant());
    if (notBlank(status)) {
      where.add("a.status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (sheetId != null) {
      where.add("a.sheet_id = ?");
      binds.add(sheetId);
    }
    if (notBlank(keyword)) {
      where.add("(a.code ilike ? or a.note ilike ? or s.code ilike ?)");
      String k = "%" + keyword.trim() + "%";
      binds.add(k);
      binds.add(k);
      binds.add(k);
    }
    String sqlWhere = " where " + String.join(" and ", where);
    String from = ADJUSTMENT_FROM;
    long total = sql.fetchOne("select count(*) from " + from + sqlWhere, binds.toArray()).get(0, Long.class);
    var rows =
        sql.fetch(
            "select " + ADJUSTMENT_COLUMNS + ", " + ADJUST_AGG + " from " + from + sqlWhere
                + " group by a.id, s.code"
                + " order by a.created_at desc, a.id"
                + " limit ? offset ?",
            append(binds, limit, (long) offset).toArray());
    return new StocktakeVarianceAdjustmentPage(
        rows.stream().map(StocktakeVarianceService::adjustment).toList(), total, Math.max(0, pageNo), limit);
  }

  /** 调整单详情 = 头部 + 全部行 + 这张单上的完整审计时间线。跨租户 → 404。 */
  public StocktakeVarianceAdjustmentDetail detail(UUID adjustmentId) {
    var head =
        sql.fetchOne(
            "select " + ADJUSTMENT_COLUMNS + ", " + ADJUST_AGG + " from " + ADJUSTMENT_FROM
                + " where a.tenant_id = ? and a.id = ? group by a.id, s.code",
            tenant(),
            adjustmentId);
    if (head == null) {
      throw notFound("调整单", adjustmentId);
    }
    var lines =
        sql.fetch(
            "select " + ADJUST_LINE_COLUMNS + " from stocktake_variance_adjustment_line l"
                + " left join stocktake_variance_attribution a on a.tenant_id = l.tenant_id"
                + " and a.line_id = l.line_id and a.adjustment_id = l.adjustment_id"
                + " where l.tenant_id = ? and l.adjustment_id = ? order by l.id",
            tenant(),
            adjustmentId);
    var audit =
        sql.fetch(
            "select " + AUDIT_COLUMNS + " from stocktake_variance_audit where tenant_id = ?"
                + " and adjustment_id = ? order by occurred_at, id",
            tenant(),
            adjustmentId);
    return new StocktakeVarianceAdjustmentDetail(
        adjustment(head), lines.stream().map(StocktakeVarianceService::adjustmentLine).toList(),
        audit.stream().map(StocktakeVarianceService::auditEntry).toList());
  }

  /** 审计时间线（按盘点单查，跨单据看整张盘点单的完整处理过程）。 */
  public List<StocktakeVarianceAuditEntry> auditTrail(UUID sheetId, Integer limit) {
    requireSheet(sheetId);
    int n = Math.min(MAX_AUDIT_ROWS, Math.max(1, limit == null || limit <= 0 ? 200 : limit));
    return sql.fetch(
            "select " + AUDIT_COLUMNS + " from stocktake_variance_audit where tenant_id = ? and sheet_id = ?"
                + " order by occurred_at desc, id desc limit ?",
            tenant(),
            sheetId,
            n)
        .stream()
        .map(StocktakeVarianceService::auditEntry)
        .toList();
  }

  // ============================================================ 完成报告

  /**
   * 盘点完成报告：盘盈率、差异率、归因完成度、按原因与按决定的分布。
   *
   * <p><b>所有指标现算，报告不落库</b>：它是某个时点对明细的读数，而明细随时可能变
   * （复盘会退回未录、迟到的归因会补上）。把报告存下来就会出现
   * 「报告说已全部处理、明细里还有未归因」这种说不清的矛盾。
   *
   * <p><b>率的分母口径写在 rateBasis 上</b>，并按数量算：
   * 全库不存在任何库存成本列，金额口径算不出来（见 capabilities 的如实说明）。
   *
   * <p><b>分母为 0 时率是 null 而不是 0</b>：0 会被读成「差异率 0%」，
   * 而「账面合计为 0，盘不出任何率」与「盘完了没有差异」是两件完全不同的事。
   */
  public StocktakeVarianceReport report(UUID sheetId) {
    var sheet =
        sql.fetchOne(
            "select id,code,scope_type,scope_label,frozen_at,status from stocktake_sheet"
                + " where tenant_id = ? and id = ?",
            tenant(),
            sheetId);
    if (sheet == null) {
      throw notFound("盘点单", sheetId);
    }
    var caps = capabilities();

    // 三列合计：直接聚合 7.4.2.1 的明细行，差异是它的 generated column。
    var agg =
        sql.fetchOne(
            "select count(*)::bigint as line_count,"
                + " count(counted_qty)::bigint as counted_line_count,"
                + " count(*) filter (where variance <> 0)::bigint as variance_line_count,"
                + " count(*) filter (where variance = 0)::bigint as match_line_count,"
                + " coalesce(sum(book_on_hand),0)::numeric(19,4) as book_total,"
                + " coalesce(sum(counted_qty),0)::numeric(19,4) as counted_total,"
                + " coalesce(sum(variance) filter (where variance > 0),0)::numeric(19,4) as gain_qty,"
                + " coalesce(-sum(variance) filter (where variance < 0),0)::numeric(19,4) as loss_qty,"
                + " coalesce(sum(variance),0)::numeric(19,4) as net_variance_qty "
                + "from stocktake_line where tenant_id = ? and sheet_id = ?",
            tenant(),
            sheetId);
    long lineCount = agg.get("line_count", Long.class);
    long counted = agg.get("counted_line_count", Long.class);
    long varianceLines = agg.get("variance_line_count", Long.class);
    BigDecimal bookTotal = scale(agg.get("book_total", BigDecimal.class));
    BigDecimal countedTotal = scale(agg.get("counted_total", BigDecimal.class));
    BigDecimal gainQty = scale(agg.get("gain_qty", BigDecimal.class));
    BigDecimal lossQty = scale(agg.get("loss_qty", BigDecimal.class));
    BigDecimal netQty = scale(agg.get("net_variance_qty", BigDecimal.class));

    // 归因进度：只在「已录入且有差异」的行上算，否则未录行会稀释完成度。
    var attributed =
        sql.fetchOne(
            "select count(*) filter (where a.id is not null)::bigint as attributed_line_count,"
                + " count(*) filter (where a.id is not null and a.resolved_at is null)::bigint as open_line_count "
                + "from stocktake_line l "
                + "left join stocktake_variance_attribution a on a.id = ("
                + "  select a2.id from stocktake_variance_attribution a2 where a2.tenant_id = l.tenant_id"
                + "   and a2.line_id = l.id order by a2.attributed_at desc, a2.id desc limit 1) "
                + "where l.tenant_id = ? and l.sheet_id = ? and l.counted_qty is not null and l.variance <> 0",
            tenant(),
            sheetId);
    long attributedCount = attributed.get("attributed_line_count", Long.class);
    long openCount = attributed.get("open_line_count", Long.class);

    // 已过账的差异行数 + 还在流程里的调整单数。
    var posting =
        sql.fetchOne(
            "select count(*) filter (where posted_at is not null)::bigint as posted_line_count"
                + " from stocktake_variance_adjustment_line where tenant_id = ? and sheet_id = ?",
            tenant(),
            sheetId);
    long postedLines = posting.get("posted_line_count", Long.class);
    long pendingAdjustments =
        sql.fetchOne(
                "select count(*) from stocktake_variance_adjustment where tenant_id = ? and sheet_id = ?"
                    + " and status in ('DRAFT','PENDING_APPROVAL','APPROVED')",
                tenant(),
                sheetId)
            .get(0, Long.class);

    BigDecimal absVariance = gainQty.add(lossQty);
    var metrics =
        new StocktakeVarianceMetrics(
            lineCount,
            counted,
            lineCount - counted,
            varianceLines,
            agg.get("match_line_count", Long.class),
            bookTotal,
            countedTotal,
            gainQty,
            lossQty,
            netQty,
            rate(varianceLines, counted),
            rate(absVariance, bookTotal),
            rate(gainQty, bookTotal),
            rate(lossQty, bookTotal),
            attributedCount,
            openCount,
            rate(attributedCount, varianceLines),
            pendingAdjustments,
            postedLines);

    return new StocktakeVarianceReport(
        sheet.get("id", UUID.class),
        sheet.get("code", String.class),
        sheet.get("scope_type", String.class),
        sheet.get("scope_label", String.class),
        sheet.get("frozen_at", OffsetDateTime.class),
        sheet.get("status", String.class),
        metrics,
        reasonBreakdown(sheetId),
        decisionBreakdown(sheetId),
        "全部按**数量**口径：行差异率 = 有差异行数 ÷ 已盘行数；数量差异率 = (盘盈量 + 盘亏量) ÷ 账面合计；"
            + "盘盈率 = 盘盈量 ÷ 账面合计；盘亏率 = 盘亏量 ÷ 账面合计；归因完成度 = 已归因差异行数 ÷ 差异行数。"
            + "账面合计 = 冻结那一刻的账面数合计（V59 钉死不可改写），不是今天的库存余额。分母为 0 时该率为 null。",
        false,
        caps.amountBasisNote(),
        caps.approvalNote(),
        caps.adjustmentHandoffNote(),
        "差异率与盘盈率只按数量出：库里没有库存成本列，金额口径算不出来（详见 amountBasisNote）。"
            + "差异**不会**直接改余额 —— 只有已审批的调整单过账后才会入账，"
            + "而入库账这一步由数据库闸门强制（未审批写不进分录）。");
  }

  /**
   * 按差异原因分组。**五种原因恒定各占一行**，没有差异的那一类计数为 0。
   *
   * <p>用 {@code values} 造五行的左连接，而不是 group by 现有的行：
   * 漏掉的那类会显示成 0 而不是从表里消失 —— 「盘完了没有一条错发」与
   * 「盘完了错发还没归因」在报告上是完全不同的两件事。
   *
   * <p>口径：只统计**仍有差异**（variance 非空）的行。复盘后的行 variance 归 NULL，
   * 它已经回到「还没盘」，不该继续算成一条待处理差异。
   */
  private List<StocktakeVarianceReasonBreakdown> reasonBreakdown(UUID sheetId) {
    var rows =
        sql.fetch(
            "with latest as ("
                + "  select distinct on (a.tenant_id,a.line_id) a.*"
                + "  from stocktake_variance_attribution a"
                + "  where a.tenant_id = ? and a.sheet_id = ?"
                + "  order by a.tenant_id,a.line_id,a.attributed_at desc,a.id desc) "
                + "select r.r as reason, count(l.id)::bigint as line_count,"
                + " count(l.id) filter (where l.variance > 0)::bigint as gain_line_count,"
                + " count(l.id) filter (where l.variance < 0)::bigint as loss_line_count,"
                + " coalesce(sum(l.variance) filter (where l.variance > 0),0)::numeric(19,4) as gain_qty,"
                + " coalesce(-sum(l.variance) filter (where l.variance < 0),0)::numeric(19,4) as loss_qty,"
                + " coalesce(sum(l.variance),0)::numeric(19,4) as net_qty,"
                + " count(l.id) filter (where latest.decision = 'ADJUST' and latest.resolved_at is null)::bigint"
                + "   as in_adjustment_count,"
                + " count(al.id) filter (where al.posted_at is not null)::bigint as posted_count "
                + "from (values ('SHRINKAGE'),('MISSHIP'),('THEFT'),('UNRECORDED'),('TIMING')) as r(r) "
                + "left join latest on latest.reason = r.r "
                + "left join stocktake_line l on l.tenant_id = latest.tenant_id and l.id = latest.line_id "
                + "   and l.counted_qty is not null and l.variance is not null and l.variance <> 0 "
                + "left join stocktake_variance_adjustment_line al on al.tenant_id = latest.tenant_id"
                + "   and al.adjustment_id = latest.adjustment_id and al.line_id = latest.line_id "
                + "group by r.r order by r.r",
            tenant(),
            sheetId);
    Map<String, org.jooq.Record> byReason = new LinkedHashMap<>();
    for (var r : rows) {
      byReason.put(r.get("reason", String.class), r);
    }
    List<StocktakeVarianceReasonBreakdown> out = new ArrayList<>();
    for (var reason : StocktakeVarianceReason.values()) {
      var r = byReason.get(reason.name());
      out.add(
          new StocktakeVarianceReasonBreakdown(
              reason,
              reason.label(),
              r == null ? 0L : r.get("line_count", Long.class),
              r == null ? 0L : r.get("gain_line_count", Long.class),
              r == null ? 0L : r.get("loss_line_count", Long.class),
              scale(r == null ? null : r.get("gain_qty", BigDecimal.class)),
              scale(r == null ? null : r.get("loss_qty", BigDecimal.class)),
              scale(r == null ? null : r.get("net_qty", BigDecimal.class)),
              r == null ? 0L : r.get("in_adjustment_count", Long.class),
              r == null ? 0L : r.get("posted_count", Long.class)));
    }
    return out;
  }

  /**
   * 按处理决定分组。**三种决定恒定各占一行**。
   *
   * <p>口径与按原因不同：这里统计的是**做过的决定**（含已复盘、已忽略的终态），
   * 所以复盘与忽略的计数在这里才看得见 —— 它们在按原因那张表里刻意不出现，
   * 因为复盘后的行已经没有差异了。
   */
  private List<StocktakeVarianceDecisionBreakdown> decisionBreakdown(UUID sheetId) {
    var rows =
        sql.fetch(
            "with latest as ("
                + "  select distinct on (a.tenant_id,a.line_id) a.*"
                + "  from stocktake_variance_attribution a"
                + "  where a.tenant_id = ? and a.sheet_id = ?"
                + "  order by a.tenant_id,a.line_id,a.attributed_at desc,a.id desc) "
                + "select d.d as decision, count(latest.id)::bigint as line_count,"
                + " count(latest.id) filter (where latest.resolved_at is null)::bigint as open_line_count "
                + "from (values ('ADJUST'),('RECOUNT'),('IGNORE')) as d(d) "
                + "left join latest on latest.decision = d.d "
                + "group by d.d order by d.d",
            tenant(),
            sheetId);
    Map<String, org.jooq.Record> byDecision = new LinkedHashMap<>();
    for (var r : rows) {
      byDecision.put(r.get("decision", String.class), r);
    }
    List<StocktakeVarianceDecisionBreakdown> out = new ArrayList<>();
    for (var d : StocktakeVarianceDecision.values()) {
      var r = byDecision.get(d.name());
      out.add(
          new StocktakeVarianceDecisionBreakdown(
              d, d.label(), r == null ? 0L : r.get("line_count", Long.class),
              r == null ? 0L : r.get("open_line_count", Long.class)));
    }
    return out;
  }

  // ============================================================ SQL 与派生

  /**
   * 差异明细的 FROM：明细 + 库位余额 + **最新一条**归因 + 那条归因所在调整单 + 是否已过账。
   *
   * <p>「最新一条归因」用子查询取而不是直接 join：复盘后可以重新归因，一行会有多条归因记录，
   * 直接 join 会把同一行差异复制成多行 —— 那会让分页的 total 与行数对不上。
   */
  private static final String LINE_FROM =
      "stocktake_line l "
          + "join stock_bucket k on k.tenant_id = l.tenant_id and k.id = l.bucket_id "
          + "left join stocktake_variance_attribution a on a.id = ("
          + "  select a2.id from stocktake_variance_attribution a2 where a2.tenant_id = l.tenant_id"
          + "   and a2.line_id = l.id order by a2.attributed_at desc, a2.id desc limit 1) "
          + "left join stocktake_variance_adjustment adj on adj.tenant_id = a.tenant_id and adj.id = a.adjustment_id "
          + "left join stocktake_variance_adjustment_line al on al.tenant_id = a.tenant_id "
          + "   and al.adjustment_id = a.adjustment_id and al.line_id = l.id ";

  private static final String LINE_COLUMNS =
      "l.id,l.sheet_id,l.bucket_id,k.balance_id,l.sku_id,l.sku_code,l.sku_name,l.warehouse_code,"
          + "l.location_id,l.location_code,l.lot_id,l.lot_code,l.quality_status_code,l.book_on_hand,"
          + "l.counted_qty,l.variance,l.counted_at,l.counted_by_user_id,"
          + "a.reason,a.decision,a.note,a.attributed_by_user_id,a.attributed_at,a.adjustment_id,"
          + "adj.status as adjustment_status,a.resolved_at,a.resolved_by,al.posted_at";

  /** 调整单查询的 FROM：头 + 盘点单号 + 明细行（行数与三个数量都由它聚合出来）。 */
  private static final String ADJUSTMENT_FROM =
      "stocktake_variance_adjustment a "
          + "left join stocktake_sheet s on s.tenant_id = a.tenant_id and s.id = a.sheet_id "
          + "left join stocktake_variance_adjustment_line al on al.tenant_id = a.tenant_id "
          + "   and al.adjustment_id = a.id ";

  private static final String ADJUSTMENT_COLUMNS =
      "a.id,a.sheet_id,s.code as sheet_code,a.code,a.status,a.note,a.submitted_by_user_id,a.submitted_at,"
          + "a.decided_by_user_id,a.decided_at,a.decision_note,a.posted_by_user_id,a.posted_at,"
          + "a.created_by_user_id,a.created_at";

  /** 调整单的数量全部现算，表里没有对应列（漏同步一次就会出现「显示已入账却还差几行」）。 */
  private static final String ADJUST_AGG =
      "count(al.id) as line_count,"
          + " count(al.id) filter (where al.qty_delta > 0) as gain_line_count,"
          + " count(al.id) filter (where al.qty_delta < 0) as loss_line_count,"
          + " coalesce(sum(al.qty_delta) filter (where al.qty_delta > 0),0)::numeric(19,4) as gain_qty,"
          + " coalesce(-sum(al.qty_delta) filter (where al.qty_delta < 0),0)::numeric(19,4) as loss_qty,"
          + " coalesce(sum(al.qty_delta),0)::numeric(19,4) as net_qty";

  private static final String ADJUST_LINE_COLUMNS =
      "l.id,l.line_id,l.bucket_id,l.balance_id,l.sku_code,l.sku_name,l.warehouse_code,l.location_code,"
          + "l.lot_code,l.quality_status_code,l.qty_delta,l.reason,l.note,"
          + "a.attributed_by_user_id,a.attributed_at,l.posted_at,l.entry_id,l.transaction_id";

  private static final String AUDIT_COLUMNS =
      "id,line_id,adjustment_id,action,actor_user_id,occurred_at,detail";

  private static StocktakeVarianceLine line(org.jooq.Record r) {
    return new StocktakeVarianceLine(
        r.get("id", UUID.class),
        r.get("sheet_id", UUID.class),
        r.get("bucket_id", UUID.class),
        r.get("balance_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("warehouse_code", String.class),
        r.get("location_id", UUID.class),
        r.get("location_code", String.class),
        r.get("lot_id", UUID.class),
        r.get("lot_code", String.class),
        r.get("quality_status_code", String.class),
        r.get("book_on_hand", BigDecimal.class),
        r.get("counted_qty", BigDecimal.class),
        r.get("variance", BigDecimal.class),
        r.get("counted_at", OffsetDateTime.class),
        r.get("counted_by_user_id", UUID.class),
        r.get("reason", String.class),
        r.get("decision", String.class),
        r.get("note", String.class),
        r.get("attributed_by_user_id", UUID.class),
        r.get("attributed_at", OffsetDateTime.class),
        r.get("adjustment_id", UUID.class),
        r.get("adjustment_status", String.class),
        r.get("resolved_at", OffsetDateTime.class),
        r.get("resolved_by", String.class),
        r.get("posted_at", OffsetDateTime.class));
  }

  private static StocktakeVarianceAdjustment adjustment(org.jooq.Record r) {
    return new StocktakeVarianceAdjustment(
        r.get("id", UUID.class),
        r.get("sheet_id", UUID.class),
        r.get("sheet_code", String.class),
        r.get("code", String.class),
        StocktakeVarianceAdjustmentStatus.valueOf(r.get("status", String.class)),
        r.get("line_count", Long.class),
        r.get("gain_line_count", Long.class),
        r.get("loss_line_count", Long.class),
        scale(r.get("gain_qty", BigDecimal.class)),
        scale(r.get("loss_qty", BigDecimal.class)),
        scale(r.get("net_qty", BigDecimal.class)),
        r.get("note", String.class),
        r.get("submitted_by_user_id", UUID.class),
        r.get("submitted_at", OffsetDateTime.class),
        r.get("decided_by_user_id", UUID.class),
        r.get("decided_at", OffsetDateTime.class),
        r.get("decision_note", String.class),
        r.get("posted_by_user_id", UUID.class),
        r.get("posted_at", OffsetDateTime.class),
        r.get("created_by_user_id", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static StocktakeVarianceAdjustmentLine adjustmentLine(org.jooq.Record r) {
    return new StocktakeVarianceAdjustmentLine(
        r.get("id", UUID.class),
        r.get("line_id", UUID.class),
        r.get("bucket_id", UUID.class),
        r.get("balance_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("warehouse_code", String.class),
        r.get("location_code", String.class),
        r.get("lot_code", String.class),
        r.get("quality_status_code", String.class),
        r.get("qty_delta", BigDecimal.class),
        r.get("reason") == null ? null : StocktakeVarianceReason.valueOf(r.get("reason", String.class)),
        r.get("note", String.class),
        r.get("attributed_by_user_id", UUID.class),
        r.get("attributed_at", OffsetDateTime.class),
        r.get("posted_at", OffsetDateTime.class),
        r.get("entry_id", UUID.class),
        r.get("transaction_id", UUID.class));
  }

  private static StocktakeVarianceAuditEntry auditEntry(org.jooq.Record r) {
    String action = r.get("action", String.class);
    return new StocktakeVarianceAuditEntry(
        r.get("id", UUID.class),
        r.get("line_id", UUID.class),
        r.get("adjustment_id", UUID.class),
        action,
        actionLabel(action),
        r.get("actor_user_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("detail", String.class));
  }

  private static String actionLabel(String action) {
    return switch (action) {
      case "ATTRIBUTED" -> "归因";
      case "ADJUSTMENT_CREATED" -> "生成调整单";
      case "SUBMITTED" -> "提交审批";
      case "APPROVED" -> "批准";
      case "REJECTED" -> "驳回";
      case "POSTED" -> "入账";
      case "RECOUNTED" -> "复盘";
      case "IGNORED" -> "忽略";
      case "CANCELLED" -> "作废";
      default -> action;
    };
  }

  private void audit(
      UUID sheetId,
      UUID lineId,
      UUID adjustmentId,
      String action,
      UUID actorId,
      OffsetDateTime at,
      String detail) {
    // occurred_at 在第 5 位，created_at 用同一次绑定（同一个数组派生两列，不手数占位符）。
    sql.execute(
        "insert into stocktake_variance_audit(id,tenant_id,sheet_id,line_id,adjustment_id,action,"
            + "actor_user_id,occurred_at,detail,created_at) "
            + "values(?,?,?,?,?,?,?,?::timestamptz,?,?::timestamptz)",
        UUID.randomUUID(),
        tenant(),
        sheetId,
        lineId,
        adjustmentId,
        action,
        actorId,
        at,
        detail,
        at);
  }

  /** 释放归因（驳回/作废）：让这些差异行可以重新归因，而不是永远挂在一张废单上。 */
  private void releaseAttributions(UUID adjustmentId, String resolvedBy, OffsetDateTime at) {
    sql.execute(
        "update stocktake_variance_attribution set resolved_at = ?::timestamptz, resolved_by = ?"
            + " where tenant_id = ? and adjustment_id = ? and resolved_at is null",
        at,
        resolvedBy,
        tenant(),
        adjustmentId);
  }

  private long countLines(UUID adjustmentId) {
    return sql.fetchOne(
            "select count(*) from stocktake_variance_adjustment_line where tenant_id = ? and adjustment_id = ?",
            tenant(),
            adjustmentId)
        .get(0, Long.class);
  }

  private StocktakeVarianceAdjustment requireAdjustment(UUID adjustmentId) {
    var row =
        sql.fetchOne(
            "select " + ADJUSTMENT_COLUMNS + ", " + ADJUST_AGG + " from " + ADJUSTMENT_FROM
                + " where a.tenant_id = ? and a.id = ? group by a.id, s.code",
            tenant(),
            adjustmentId);
    if (row == null) {
      throw notFound("调整单", adjustmentId);
    }
    return adjustment(row);
  }

  private StocktakeVarianceAdjustment requirePending(UUID adjustmentId, String action) {
    var a = requireAdjustment(adjustmentId);
    if (a.status() != StocktakeVarianceAdjustmentStatus.PENDING_APPROVAL) {
      throw conflict(
          "调整单 " + a.code() + " 当前是「" + a.status().label() + "」，只有「待审批」的单据才能" + action + "。");
    }
    return a;
  }

  private StocktakeVarianceLine requireLine(UUID sheetId, UUID lineId) {
    var row =
        sql.fetchOne(
            "select " + LINE_COLUMNS + " from " + LINE_FROM + " where l.tenant_id = ? and l.sheet_id = ? and l.id = ?",
            tenant(),
            sheetId,
            lineId);
    if (row == null) {
      throw notFound("盘点差异明细", lineId);
    }
    return line(row);
  }

  /** 盘点单存在性检查：跨租户在 RLS 下查不到 → 404，不泄露存在性。 */
  private void requireSheet(UUID sheetId) {
    if (sql.fetchOne("select id from stocktake_sheet where tenant_id = ? and id = ?", tenant(), sheetId) == null) {
      throw notFound("盘点单", sheetId);
    }
  }

  // ============================================================ 小工具

  /** 租户来自 RLS 的同一个来源，绝不来自任何请求参数或客户端头（与 7.4.2.1 / 7.1.2.2 同一句）。 */
  private UUID tenant() {
    return sql.fetchOne("select current_setting('app.current_tenant',true)::uuid as t").get("t", UUID.class);
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    binds.addAll(List.of(more));
    return binds;
  }

  private static BigDecimal scale(BigDecimal v) {
    return v == null ? ZERO : v.setScale(4, RoundingMode.HALF_UP);
  }

  /**
   * 率 = 分子 ÷ 分母，分母为 0 时返回 {@code null}。
   *
   * <p>返回 null 而不是 0：账面合计为 0 时「盘不出率」与「差异率 0%」是两件事，
   * 把前者画成后者就是在报告上说谎。
   */
  private static BigDecimal rate(BigDecimal numerator, BigDecimal denominator) {
    if (denominator == null || denominator.signum() == 0) {
      return null;
    }
    return percent(numerator, denominator);
  }

  private static BigDecimal rate(long numerator, long denominator) {
    if (denominator == 0) {
      return null;
    }
    return percent(BigDecimal.valueOf(numerator), BigDecimal.valueOf(denominator));
  }

  /** 比例 → 百分比（×100），保留 4 位小数，HALF_UP。分母为 0 返回 null。 */
  private static BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
    if (denominator == null || denominator.signum() == 0 || numerator == null) {
      return null;
    }
    return numerator
        .multiply(new BigDecimal("100"))
        .divide(denominator, RATE_SCALE, RoundingMode.HALF_UP);
  }

  private static boolean notBlank(String v) {
    return v != null && !v.isBlank();
  }

  private static String requireText(String raw, String message) {
    String v = raw == null ? "" : raw.trim();
    if (v.isEmpty()) {
      throw bad(message);
    }
    return v;
  }

  /**
   * 说明必填，且「忽略」至少 4 个字符。
   *
   * <p>4 个字符是「一个短语」的下限：单字「无」「错」写下来等于没写，
   * 而忽略是最需要留下理由的决定（它让一条差异永远不入账）。
   */
  private static String requireNote(StocktakeVarianceDecision decision, String raw) {
    String v = raw == null ? "" : raw.trim();
    if (v.isEmpty()) {
      throw bad(
          "处理决定「" + decision.label() + "」必须写说明："
              + (decision == StocktakeVarianceDecision.IGNORE
                  ? "忽略是最需要留下理由的决定 —— 它让这条差异永远不入账。"
                  : "差异可能要追责到人，「谁因为什么这么判」不能只有枚举值。"));
    }
    if (decision == StocktakeVarianceDecision.IGNORE && v.length() < 4) {
      throw bad("忽略必须写清楚理由（至少 4 个字）：「无」「错」这样写等于没写，"
          + "而忽略会让这条差异永远不入账、也永远不会被追到人。");
    }
    if (v.length() > 500) {
      throw bad("说明超过 500 字，请写结论与依据，不要整段粘贴。");
    }
    return v;
  }

  private static StocktakeVarianceReason reason(String raw) {
    if (!notBlank(raw)) {
      throw bad("必须给出差异原因：" + java.util.Arrays.stream(StocktakeVarianceReason.values())
          .map(e -> e.name() + "(" + e.label() + ")")
          .reduce((x, y) -> x + " / " + y)
          .orElse(""));
    }
    try {
      return StocktakeVarianceReason.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的差异原因：" + raw + "。可用值 " + java.util.Arrays.stream(StocktakeVarianceReason.values())
          .map(Enum::name).toList());
    }
  }

  private static StocktakeVarianceDecision decision(String raw) {
    if (!notBlank(raw)) {
      throw bad("必须给出处理决定：ADJUST（调整）/ RECOUNT（复盘）/ IGNORE（忽略）");
    }
    try {
      return StocktakeVarianceDecision.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的处理决定：" + raw + "。可用值 ADJUST / RECOUNT / IGNORE");
    }
  }

  private static StocktakeVarianceLineFilter lineFilter(String raw) {
    if (!notBlank(raw)) {
      return StocktakeVarianceLineFilter.ALL;
    }
    try {
      return StocktakeVarianceLineFilter.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的 filter：" + raw + "。可用值 " + java.util.Arrays.stream(StocktakeVarianceLineFilter.values())
          .map(Enum::name).toList());
    }
  }

  private static ResponseStatusException notFound(String what, UUID id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what + "不存在或不属于当前租户：" + id);
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  /** 状态冲突用 409：它和「你填错了」(400) 是两类问题，混在一起前端无法给出不同提示。 */
  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
