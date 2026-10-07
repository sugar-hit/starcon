package com.starcon.warehouse;

import static com.starcon.warehouse.StocktakeModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 7.4.2.1 盘点单：发起盘点（冻结时点快照）、生成盘点任务、录入实盘数、账面/实盘/差异三列输出。
 *
 * <p><b>本模块的核心不变量：冻结是「数据」不是「查询」</b>。发起盘点的那一刻，范围内的
 * {@code stock_bucket.on_hand} 被**复制成行**写进 {@code stocktake_line}，此后：
 *
 * <ul>
 *   <li>任何出入库改动的是 {@code stock_bucket}，改不动 {@code stocktake_line.book_on_hand}；
 *   <li>V59 的 {@code stocktake_line_frozen} 触发器把账面数与八个维度列钉成不可更新，
 *       拿到连接直接 UPDATE 也改不动 —— 所以「冻结」是数据库不变量，不是应用层承诺；
 *   <li>差异列是 generated column（{@code counted_qty - book_on_hand}），没有第二份真相。
 * </ul>
 *
 * <p>反过来，若只做「实时查当前余额」，盘点期间每卖一件都会改写账面列，盘点员会拿到一个
 * 混着正常出入库的差异 —— 这正是蓝图点名的「边盘边卖产生虚假差异」。
 *
 * <p><b>为什么账面数与今天的库存余额不一样是对的</b>：它回答的是「冻结那一刻有多少」，
 * 不是「现在有多少」。要在冻结之后继续卖买是允许的（那才是真实业务），只是那些变动
 * 不再改写这张单 —— 7.4.2.2 处理差异时会把它与时点差这一类原因对上。
 *
 * <p><b>时间绑定纪律</b>：{@code ?::timestamptz} 一律出现在语句最前面的 {@code frozen} CTE 里，
 * 后面全是普通绑定，cast 永远不在绑定序列末位（与 7.1.2.2 / 7.2.2.1 同一条纪律）。
 * 写入用 {@link OffsetDateTime} 绑定，不走 {@code Timestamp.valueOf} —— 后者按 JVM 本地时区
 * 解释裸本地时间，在东八区会静默偏 −28800s。
 */
@Service
public class StocktakeService {

  /** 单页最大行数：盘点明细是「这次盘了什么」的清单，量级可控，但仍设上限防止一次拉爆 JVM。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 单张盘点单冻结的最大明细行数。范围筛得太宽时会命中它 —— 一次盘 20 万行没法现场执行。 */
  public static final int MAX_LINES = 20000;

  /** numeric(19,4) 口径下的零：避免同一列里混着 scale 不一样的 0。 */
  private static final BigDecimal ZERO = new BigDecimal("0.0000");

  private static final DateTimeFormatter CODE_DAY =
      DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

  private final DSLContext sql;
  private final CatalogProjectionPort catalog;

  public StocktakeService(DSLContext sql, CatalogProjectionPort catalog) {
    this.sql = sql;
    this.catalog = catalog;
  }

  // ============================================================ 范围能力

  /**
   * 盘点范围维度接入情况。
   *
   * <p>ABC 分类在这里**如实报出「没有这个维度」**而不是给一个假实现：全库不存在任何
   * ABC 分类字段（warehouse 与 catalog 两侧迁移 + db/schemas 都查过），也没有动销率之类的
   * 原始列可推导。界面据此禁用该选项并把原因讲给用户，而不是让选项凭空消失。
   */
  public List<StocktakeScopeCapability> capabilities() {
    List<StocktakeScopeCapability> out = new ArrayList<>();
    out.add(
        new StocktakeScopeCapability(
            StocktakeScopeType.WAREHOUSE.name(),
            StocktakeScopeType.WAREHOUSE.label(),
            true,
            "按仓库盘点：范围取 warehouse.id，本服务自有数据，可直接筛出该仓全部库位/批次。"));
    out.add(
        new StocktakeScopeCapability(
            StocktakeScopeType.LOCATION.name(),
            StocktakeScopeType.LOCATION.label(),
            true,
            "按库位盘点：范围取 warehouse_location.id，盘点任务默认按库位下发。"));
    boolean categoryReady = catalog.supports(CatalogProjectionPort.Dimension.CATEGORY);
    out.add(
        new StocktakeScopeCapability(
            StocktakeScopeType.CATEGORY.name(),
            StocktakeScopeType.CATEGORY.label(),
            categoryReady,
            categoryReady
                ? "按类目盘点：范围取 catalog 的类目 id。"
                : "类目与品牌属 03 商品主数据（catalog），sku_ref 刻意不存类目，"
                    + "本服务尚未接入 catalog 的读取投影。已显式拒绝而不是按空范围返回 —— "
                    + "后者会被读成「按这个类目盘不出任何库存」。上游接入后本项自动可用。"));
    out.add(
        new StocktakeScopeCapability(
            "ABC",
            "按 ABC 分类",
            false,
            "库里没有 ABC 分类字段，也没有可推导它的动销率原始列（warehouse 与 catalog "
                + "两侧迁移均已确认）。本模块不臆造一个分位算法冒充已有数据 —— 那会让盘点员"
                + "以为盘的是公司认定的 A 类货，实际是一个没人批准过的猜测。"));
    return out;
  }

  // ============================================================ 列表与详情

  /** 盘点单列表（真分页，进度三个计数由明细聚合得出，不另存列）。 */
  public StocktakeSheetPage page(String status, String scopeType, String keyword, int pageNo, int size) {
    int limit = Math.min(MAX_PAGE_SIZE, Math.max(1, size <= 0 ? 20 : size));
    int offset = Math.max(0, pageNo) * limit;
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (notBlank(status)) {
      where.add("s.status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(scopeType)) {
      where.add("s.scope_type = ?");
      binds.add(scopeType.trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(keyword)) {
      where.add("(s.code ilike ? or s.scope_label ilike ?)");
      String k = "%" + keyword.trim() + "%";
      binds.add(k);
      binds.add(k);
    }
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    String fromAndJoin =
        " from stocktake_sheet s"
            + " left join stocktake_line l on l.tenant_id = s.tenant_id and l.sheet_id = s.id";
    long total = sql.fetchOne("select count(*) from stocktake_sheet s" + sqlWhere, binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            // 拼接顺序：from → join → where → group by → order by。
            // 把 where 放在 left join 之前会拼出「from stocktake_sheet s where ... left join ...」，
            // PG 报「syntax error at or near "left"」，而错误信息完全指向不到真正的那次拼接。
            "select " + SHEET_COLUMNS + ", " + PROGRESS_AGG + fromAndJoin + sqlWhere
                + " group by s.id"
                // 决胜键恒定追加 s.id：同排序值时翻页不会错位或重复。
                + " order by s.created_at desc, s.id"
                + " limit ? offset ?",
            append(binds, limit, (long) offset).toArray());
    return new StocktakeSheetPage(
        rows.stream().map(StocktakeService::sheet).toList(), total, Math.max(0, pageNo), limit);
  }

  /** 盘点单详情 + 三列合计。跨租户在 RLS 下查不到 → 404，不泄露存在性。 */
  public StocktakeDetail detail(UUID id) {
    // 与列表页用**同一条**聚合查询：两个入口给出的进度与合计必须一致，
    // 分成「详情只查头、列表才聚合」会留下一处永远拿不到 line_count 的读法。
    var row =
        sql.fetchOne(
            "select " + SHEET_COLUMNS + ", " + PROGRESS_AGG
                + " from stocktake_sheet s"
                + " left join stocktake_line l on l.tenant_id = s.tenant_id and l.sheet_id = s.id"
                + " where s.id = ? group by s.id",
            id);
    if (row == null) {
      throw notFound(id);
    }
    return new StocktakeDetail(sheet(row), summary(id));
  }

  // ============================================================ 发起盘点（冻结）

  /**
   * 发起盘点：冻结时点快照 + 生成盘点任务。
   *
   * <p>三步在**同一个事务**里：写盘点单头 → 按 task_unit 生成任务 → 把范围内的 bucket
   * 逐行复制成明细（账面数就在这一刻定死）。任何一步失败全滚，不会留下一张没有明细的空单
   * 或一批账面数为零的假明细。
   *
   * <p>范围筛不出任何库存行时**显式报错**而不是建一张空单：一张零明细的盘点单在台账上看起来
   * 像「盘过了、没差异」，那是比报错危险得多的结果。
   */
  @Transactional
  public StocktakeSaved create(StocktakeRequest input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    StocktakeScopeType scope = scopeType(input.scopeType());
    StocktakeTaskUnit unit = taskUnit(input.taskUnit());
    if (input.scopeKey() == null) {
      throw bad(scope.label() + "范围必须给出 scopeKey（仓库或库位 id）");
    }

    // 类目范围：未接入上游投影时显式拒绝（422），这是 7.1.1.1 已建立的同一套约定。
    if (scope == StocktakeScopeType.CATEGORY && !catalog.supports(CatalogProjectionPort.Dimension.CATEGORY)) {
      throw new CatalogProjectionPort.DimensionUnavailableException(CatalogProjectionPort.Dimension.CATEGORY);
    }

    String scopeLabel = resolveScopeLabel(scope, input.scopeKey());
    OffsetDateTime frozenAt = OffsetDateTime.now();

    UUID sheetId = UUID.randomUUID();
    sql.execute(
        "insert into stocktake_sheet(id,tenant_id,code,scope_type,scope_key,scope_label,"
            + "task_unit,frozen_at,status,note,created_by_user_id) "
            + "values (?,?,?,?,?,?,?,?::timestamptz,'OPEN',?,?)",
        sheetId,
        tenant(),
        code(frozenAt),
        scope.name(),
        input.scopeKey(),
        scopeLabel,
        unit.name(),
        frozenAt,
        input.note() == null ? "" : input.note().trim(),
        actorId);

    // 任务：按 task_unit 对范围内的 bucket 去重分组。
    // 去重后的 key 集合就是「要盘的库位」或「要盘的 SKU」，每一项一个任务。
    insertTasks(sheetId, scope, unit, frozenAt);

    // 明细：账面数 = k.on_hand，在这一刻复制成行。
    int inserted = insertLines(sheetId, scope, unit, frozenAt);
    if (inserted == 0) {
      throw bad(
          "范围「"
              + scopeLabel
              + "」在这个时刻没有任何库存行，已取消发起：一张零明细的盘点单在台账上看起来像"
              + "「盘过了、没差异」，那比报错危险得多。请换一个范围，或确认该范围确实已拆到库位。");
    }
    if (inserted > MAX_LINES) {
      throw bad(
          "范围「"
              + scopeLabel
              + "」冻结出 "
              + inserted
              + " 行明细，超过单张盘点单 "
              + MAX_LINES
              + " 行的上限：这么多行没法在一次现场盘点里完成。请按仓库/库位把范围拆开分批盘。");
    }

    int taskCount = sql.fetchOne("select count(*) from stocktake_task where tenant_id = ? and sheet_id = ?", tenant(), sheetId)
        .get(0, Integer.class);
    var sheet = detail(sheetId).sheet();
    return new StocktakeSaved(
        sheet,
        taskCount,
        "已在 "
            + frozenAt
            + " 冻结账面数，共 "
            + inserted
            + " 行明细、"
            + taskCount
            + " 个"
            + unit.label()
            + "任务。冻结之后仓库照常收发，但这张单的账面数不再被改写 —— "
            + "它要回答的是「这一刻有多少」，不是「现在有多少」。差异是实盘减账面算出来的，不接受人工填写。");
  }

  /**
   * 范围可读名：范围指向的仓/库位必须**真实存在**（同租户）。
   *
   * <p>不校验会让 scope_label 变成一句自己编的话，而盘点单要回看「当时盘的是哪儿」。
   */
  private String resolveScopeLabel(StocktakeScopeType scope, UUID key) {
    if (scope == StocktakeScopeType.WAREHOUSE) {
      var r = sql.fetchOne("select code,name from warehouse where id = ?", key);
      if (r == null) {
        throw bad("仓库 " + key + " 不存在（或不属于当前租户）");
      }
      return r.get("code", String.class) + " " + r.get("name", String.class);
    }
    var r =
        sql.fetchOne(
            "select l.code,l.name,w.code as wh_code from warehouse_location l"
                + " join warehouse w on w.tenant_id = l.tenant_id and w.id = l.warehouse_id"
                + " where l.id = ?",
            key);
    if (r == null) {
      throw bad("库位 " + key + " 不存在（或不属于当前租户）");
    }
    return r.get("wh_code", String.class) + "/" + r.get("code", String.class) + " " + r.get("name", String.class);
  }

  /** 盘点单号：ST-<UTC 日期>-<8 位十六进制>。服务端派生，不接受调用方自填。 */
  private String code(OffsetDateTime at) {
    return "ST-"
        + CODE_DAY.format(at)
        + "-"
        + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
  }

  // ============================================================ 明细与录入

  /**
   * 明细分页。{@code filter} 决定看哪些行（未录入 / 相符 / 盘盈 / 盘亏）。
   *
   * <p>筛选与差异列走的是同一个 {@code variance}，所以「界面上算的差异」与「库里存的差异」
   * 永远是同一个数。
   */
  public StocktakeLinePage lines(
      UUID sheetId, String filter, String keyword, int pageNo, int size) {
    requireSheet(sheetId);
    int limit = Math.min(MAX_PAGE_SIZE, Math.max(1, size <= 0 ? 50 : size));
    int offset = Math.max(0, pageNo) * limit;
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("l.tenant_id = ? and l.sheet_id = ?");
    binds.add(tenant());
    binds.add(sheetId);
    StocktakeLineFilter f = lineFilter(filter);
    switch (f) {
      case PENDING -> where.add("l.counted_qty is null");
      case MATCH -> where.add("l.variance = 0");
      case GAIN -> where.add("l.variance > 0");
      case LOSS -> where.add("l.variance < 0");
      case ALL -> {
        // 无附加条件。
      }
    }
    if (notBlank(keyword)) {
      where.add("(l.sku_code ilike ? or l.sku_name ilike ? or l.location_code ilike ? or l.lot_code ilike ? or l.counted_barcode ilike ?)");
      String k = "%" + keyword.trim() + "%";
      binds.add(k);
      binds.add(k);
      binds.add(k);
      binds.add(k);
      binds.add(k);
    }
    String sqlWhere = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from stocktake_line l" + sqlWhere, binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select " + LINE_COLUMNS
                + " from stocktake_line l"
                + sqlWhere
                // 未录入的行排前面（它们是还没干的活），有差异的排中间，账实相符沉底。
                + " order by l.counted_qty is null desc, l.variance is null,"
                + " abs(l.variance) desc nulls last, l.sku_code, l.location_code, l.lot_code, l.id"
                + " limit ? offset ?",
            append(binds, limit, (long) offset).toArray());
    return new StocktakeLinePage(
        rows.stream().map(StocktakeService::line).toList(), total, Math.max(0, pageNo), limit);
  }

  /**
   * 录入实盘数（或撤销录入）。
   *
   * <p>三件事在同一个事务里：写实盘数 → 重算盘点单状态 → 返回这一行。
   * 状态是**重算**而不是"往前推一格"：重算让它不可能与实际录入进度对不上
   * （复盘、撤销录入都会自然落回正确的状态，不需要额外分支）。
   *
   * <p>{@code countedQty} 为 null 表示撤销录入。V59 的 {@code stocktake_line_counted}
   * 触发器要求实盘数/时刻/人三者同时有值或同时为空，所以撤销时三者一起清掉。
   *
   * <p><b>账面数在这一步不会被碰</b>：V59 的 {@code stocktake_line_frozen} 触发器只放行
   * counted_* 那一组。即使本方法写错了列，DB 也会当场拒绝。
   */
  @Transactional
  public StocktakeLine count(UUID sheetId, UUID lineId, StocktakeCountInput input, UUID actorId) {
    var sheet = sql.fetchOne("select " + SHEET_COLUMNS + " from stocktake_sheet s where s.id = ?", sheetId);
    if (sheet == null) {
      throw notFound(sheetId);
    }
    if (input == null) {
      throw bad("请求体不能为空");
    }
    BigDecimal qty = input.countedQty();
    String barcode = blankToNull(input.barcode());
    String note = blankToNull(input.note());
    OffsetDateTime at = qty == null ? null : OffsetDateTime.now();

    // ?::timestamptz 在第 2 位，不在绑定序列末位（末位是 lineId/sheetId 的普通绑定）。
    int affected =
        sql.execute(
            "update stocktake_line set counted_qty = ?, counted_at = ?::timestamptz,"
                + " counted_by_user_id = ?, counted_barcode = ?, counted_note = ?"
                + " where tenant_id = ? and sheet_id = ? and id = ?",
            qty,
            at,
            qty == null ? null : actorId,
            barcode,
            note,
            tenant(),
            sheetId,
            lineId);
    if (affected == 0) {
      // 区分「这张单没有这一行」与「这张单根本没有」：前者是操作错，后者跨租户 → 404。
      if (sql.fetchOne("select id from stocktake_sheet where id = ?", sheetId) == null) {
        throw notFound(sheetId);
      }
      throw bad("盘点单 " + sheetId + " 下没有明细行 " + lineId);
    }

    syncStatus(sheetId);
    var row =
        sql.fetchOne("select " + LINE_COLUMNS + " from stocktake_line l where l.id = ?", lineId);
    return line(row);
  }

  /** 盘点任务列表。状态与三个进度计数全部由该任务下的明细行派生。 */
  public List<StocktakeTask> tasks(UUID sheetId, Boolean onlyOpen) {
    requireSheet(sheetId);
    var rows =
        sql.fetch(
            "select " + TASK_COLUMNS + ", " + PROGRESS_AGG
                + " from stocktake_task t"
                + " left join stocktake_line l on l.tenant_id = t.tenant_id"
                + " and l.sheet_id = t.sheet_id and l.task_id = t.id"
                + " where t.tenant_id = ? and t.sheet_id = ?"
                + " group by t.id"
                + " order by " + (Boolean.TRUE.equals(onlyOpen) ? "count(l.id) filter (where l.counted_qty is null) desc, " : "")
                + "t.task_code, t.id",
            tenant(),
            sheetId);
    return rows.stream().map(StocktakeService::task).toList();
  }

  /**
   * 派工：把一个任务指给某个已认证用户。
   *
   * <p>本服务**无法**校验目标用户是否真是本租户成员 —— identity 才是成员关系的所有者，
   * 跨服务不建 FK 也不查对方的表（与 V56 的 reference_id 同一理由）。代价写在这里：
   * 派错人只影响派工，不会越权读写别人的盘点单（读写仍由 RLS 按租户强制）。
   */
  @Transactional
  public StocktakeTask assign(UUID sheetId, UUID taskId, StocktakeAssignInput input) {
    requireSheet(sheetId);
    UUID assignee = input == null ? null : input.assigneeUserId();
    int affected =
        sql.execute(
            "update stocktake_task set assigned_to_user_id = ? where tenant_id = ? and sheet_id = ? and id = ?",
            assignee,
            tenant(),
            sheetId,
            taskId);
    if (affected == 0) {
      throw bad("盘点单 " + sheetId + " 下没有任务 " + taskId);
    }
    return tasks(sheetId, null).stream()
        .filter(t -> t.id().equals(taskId))
        .findFirst()
        .orElseThrow(() -> bad("盘点单 " + sheetId + " 下没有任务 " + taskId));
  }

  // ============================================================ SQL 与派生

  /** 按明细行重算盘点单状态并写回。这是状态唯一的来源。 */
  private void syncStatus(UUID sheetId) {
    var counts =
        sql.fetchOne(
            "select count(*) as total, count(counted_qty) as done from stocktake_line"
                + " where tenant_id = ? and sheet_id = ?",
            tenant(),
            sheetId);
    long total = counts.get("total", Long.class);
    long done = counts.get("done", Long.class);
    StocktakeSheetStatus next =
        done == 0 ? StocktakeSheetStatus.OPEN
            : done >= total ? StocktakeSheetStatus.COUNTED
            : StocktakeSheetStatus.COUNTING;
    sql.execute(
        "update stocktake_sheet set status = ? where tenant_id = ? and id = ?", next.name(), tenant(), sheetId);
  }

// ============================================================ 冻结写入：两条 SQL

  /**
   * **两个维度是独立的，不要混**：{@code scope} 决定「哪些 bucket 进这次盘点」，
   * {@code unit} 决定「这些 bucket 怎么切成任务」。所以「按仓库盘 + 按 SKU 发任务」是完全合法的
   * 组合，而把两者当成同一个东西会让「按仓库盘、按库位发任务」这种最常见的组合直接写不出来。
   *
   * <p>范围谓词三个分支各写一条而不是一条 {@code (? is null or ...)}：后者既让 PG 反推不出参数
   * 类型，又要把 cast 放到末位绑定 —— 正是 7.1.2.2 踩过的那条纪律。
   *
   * <p><b>{@code on_hand = 0} 的 bucket 也照盘</b>：它在范围内就是范围内的一格，盘点员要确认的
   * 恰恰是「这个货位确实是空的」。过滤掉它们会让「空库位」在盘点单上凭空消失，而它在冻结
   * 之后被一笔入库填上时没有任何人发现过。
   */
  private String scopeWhere(StocktakeScopeType scope) {
    return switch (scope) {
      case WAREHOUSE -> "k.tenant_id = ? and k.warehouse_id = ?";
      case LOCATION -> "k.tenant_id = ? and k.location_id = ?";
      case CATEGORY -> "k.tenant_id = ? and k.sku_id = ?";
    };
  }

  /** 任务键：按库位发任务取库位 id，按 SKU 发任务取 SKU id。 */
  private String taskKeyExpr(StocktakeTaskUnit unit) {
    return unit == StocktakeTaskUnit.BY_SKU ? "k.sku_id" : "k.location_id";
  }

  private String taskCodeExpr(StocktakeTaskUnit unit) {
    return unit == StocktakeTaskUnit.BY_SKU ? "s.code" : "lo.code";
  }

  private String taskNameExpr(StocktakeTaskUnit unit) {
    return unit == StocktakeTaskUnit.BY_SKU ? "s.name" : "lo.name";
  }

  /**
   * 任务写入：把范围内的 bucket 按 task_key 去重，每个 key 一个任务。
   *
   * <p>id 由 {@code gen_random_uuid()} 在库侧生成（PG 13 起是内核函数，不依赖 pgcrypto 扩展）。
   * 一条 INSERT ... SELECT 一次落完，不先查再逐条插 —— 后者在几千个库位上要往返几千次。
   *
   * <p><b>绑定顺序</b>：tenant → sheetId → taskUnit → frozenAt（{@code ?::timestamptz}，第 4 位，
   * 不是末位）→ tenant → scopeKey。cast 不落在末位。
   */
  private void insertTasks(UUID sheetId, StocktakeScopeType scope, StocktakeTaskUnit unit, OffsetDateTime at) {
    sql.execute(
        "insert into stocktake_task(id,tenant_id,sheet_id,task_unit,task_key,task_code,task_name,created_at) "
            + "select gen_random_uuid(),?,?,?, d.k, d.c, d.n, ?::timestamptz "
            + "from (select distinct " + taskKeyExpr(unit) + " as k, " + taskCodeExpr(unit) + " as c, "
            + taskNameExpr(unit) + " as n "
            + " from stock_bucket k "
            + "join warehouse w on w.tenant_id = k.tenant_id and w.id = k.warehouse_id "
            + "join warehouse_location lo on lo.tenant_id = k.tenant_id and lo.id = k.location_id "
            + "join sku_ref s on s.tenant_id = k.tenant_id and s.id = k.sku_id "
            + "where " + scopeWhere(scope) + ") d",
        tenant(),
        sheetId,
        unit.name(),
        at,
        tenant(),
        scopeKey(sheetId));
  }

  /**
   * 明细写入：范围内每一个 bucket 落一行，{@code book_on_hand = k.on_hand}。
   *
   * <p><b>这就是「冻结」的全部实现</b> —— 账面数从 {@code stock_bucket} 被**复制**到
   * {@code stocktake_line}，从此两行再无关系。后续出入库只改 bucket，改不动这里
   * （V59 的 {@code stocktake_line_frozen} 触发器把这条钉在 DB 层）。
   *
   * <p>{@code frozen} CTE 放在最前面，因此 {@code ?::timestamptz} 是整条语句的第 1 个绑定，
   * 后面全是普通绑定，cast 永远不在绑定序列末位（与 7.1.2.2 / 7.2.2.1 同一条纪律）。
   *
   * <p><b>绑定顺序</b>：frozenAt（cast，第 1 位）→ tenant → sheetId → sheetId（任务关联）→
   * tenant → scopeKey。
   *
   * <p>批次名用标量子查询而不是 LEFT JOIN：非批次管理品 {@code lot_id} 为 NULL，
   * {@code coalesce(...,'')} 把批次名归成空串。同一租户内 lot_id 唯一，所以标量子查询
   * 不会因为多行而报错。
   */
  private int insertLines(UUID sheetId, StocktakeScopeType scope, StocktakeTaskUnit unit, OffsetDateTime at) {
    return sql.execute(
        "with frozen as (select ?::timestamptz as at) "
            + "insert into stocktake_line(id,tenant_id,sheet_id,bucket_id,legal_entity_id,sku_id,"
            + "warehouse_id,location_id,lot_id,quality_status_id,sku_code,sku_name,warehouse_code,"
            + "location_code,lot_code,quality_status_code,book_on_hand,task_id,created_at) "
            + "select gen_random_uuid(),?,?, k.id, k.legal_entity_id, k.sku_id, k.warehouse_id,"
            + " k.location_id, k.lot_id, k.quality_status_id, s.code, s.name, w.code, lo.code,"
            + " coalesce((select lt.lot_code from inventory_lot lt"
            + "          where lt.tenant_id = k.tenant_id and lt.id = k.lot_id), ''),"
            + " q.code, k.on_hand, t.id, frozen.at "
            + "from stock_bucket k "
            + "join stocktake_task t on t.tenant_id = k.tenant_id and t.sheet_id = ?"
            + " and t.task_key = " + taskKeyExpr(unit) + " "
            + "join warehouse w on w.tenant_id = k.tenant_id and w.id = k.warehouse_id "
            + "join warehouse_location lo on lo.tenant_id = k.tenant_id and lo.id = k.location_id "
            + "join sku_ref s on s.tenant_id = k.tenant_id and s.id = k.sku_id "
            + "join inventory_quality_status q on q.tenant_id = k.tenant_id and q.id = k.quality_status_id "
            // frozen 写在最后显式 cross join：写成 `from stock_bucket k, frozen join ...` 时，
            // 逗号会先把 k 划到 JOIN 的作用域之外，ON 子句里引用 k 直接报
            // 「invalid reference to FROM-clause entry for table k」—— 一个指向不到真因的错误。
            + "cross join frozen "
            + "where " + scopeWhere(scope),
        at,
        tenant(),
        sheetId,
        sheetId,
        tenant(),
        scopeKey(sheetId));
  }

  /**
   * 范围键从已写入的盘点单读回来，而不是一路当参数传进 SQL 构造。
   *
   * <p>这样每条冻结语句的范围绑定只有 {@code tenant + scopeKey} 两处、顺序固定，
   * 而绑定顺序正是本模块最容易错一格的地方（错一格时 PG 报的却是
   * 「invalid input syntax for type uuid」，完全指向不到真正的原因）。
   * 只有一个来源也不会出现「任务按 A 范围建、明细按 B 范围落」这种前后不一致。
   */
  private UUID scopeKey(UUID sheetId) {
    return sql.fetchOne("select scope_key from stocktake_sheet where tenant_id = ? and id = ?", tenant(), sheetId)
        .get("scope_key", UUID.class);
  }

  private static final String SHEET_COLUMNS =
      "s.id,s.code,s.scope_type,s.scope_key,s.scope_label,s.task_unit,s.frozen_at,s.status,"
          + "s.note,s.created_by_user_id,s.created_at";

  private static final String LINE_COLUMNS =
      "l.id,l.sheet_id,l.bucket_id,l.task_id,l.legal_entity_id,l.sku_id,l.sku_code,l.sku_name,"
          + "l.warehouse_id,l.warehouse_code,l.location_id,l.location_code,l.lot_id,l.lot_code,"
          + "l.quality_status_id,l.quality_status_code,l.book_on_hand,l.counted_qty,l.variance,"
          + "l.counted_at,l.counted_by_user_id,l.counted_barcode,l.counted_note";

  private static final String TASK_COLUMNS =
      "t.id,t.sheet_id,t.task_unit,t.task_key,t.task_code,t.task_name,t.assigned_to_user_id";

  /**
   * 进度聚合片段。
   *
   * <p>三个计数与三列合计**全部现算**，表里没有对应列：进度与合计都是明细行的函数，
   * 另存一份就要在每次录入后同步它们，而漏同步一次就会出现
   * 「盘点单显示已录完但还有行没盘」「合计与明细对不上」这两类说不清的错。
   *
   * <p>{@code variance_total} 只在已录入的行上有意义 —— 未录入行的 variance 是 NULL，
   * sum 会跳过它们，所以合计天然等于「已录入部分的差异合计」，而不是把没盘的算成 0。
   */
  private static final String PROGRESS_AGG =
      "count(l.id) as line_count, count(l.counted_qty) as counted_line_count,"
          + " count(l.variance) filter (where l.variance <> 0) as variance_line_count,"
          + " coalesce(sum(l.book_on_hand),0) as book_total,"
          + " coalesce(sum(l.counted_qty),0) as counted_total,"
          + " coalesce(sum(l.variance),0) as variance_total";

  private static StocktakeSheet sheet(org.jooq.Record r) {
    return new StocktakeSheet(
        r.get("id", UUID.class),
        r.get("code", String.class),
        StocktakeScopeType.valueOf(r.get("scope_type", String.class)),
        r.get("scope_label", String.class),
        StocktakeTaskUnit.valueOf(r.get("task_unit", String.class)),
        r.get("frozen_at", OffsetDateTime.class),
        StocktakeSheetStatus.valueOf(r.get("status", String.class)),
        r.get("line_count", Long.class),
        r.get("counted_line_count", Long.class),
        r.get("variance_line_count", Long.class),
        r.get("note", String.class),
        r.get("created_by_user_id", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static StocktakeLine line(org.jooq.Record r) {
    return new StocktakeLine(
        r.get("id", UUID.class),
        r.get("sheet_id", UUID.class),
        r.get("bucket_id", UUID.class),
        r.get("task_id", UUID.class),
        r.get("legal_entity_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("warehouse_id", UUID.class),
        r.get("warehouse_code", String.class),
        r.get("location_id", UUID.class),
        r.get("location_code", String.class),
        r.get("lot_id", UUID.class),
        r.get("lot_code", String.class),
        r.get("quality_status_id", UUID.class),
        r.get("quality_status_code", String.class),
        r.get("book_on_hand", BigDecimal.class),
        r.get("counted_qty", BigDecimal.class),
        r.get("variance", BigDecimal.class),
        r.get("counted_at", OffsetDateTime.class),
        r.get("counted_by_user_id", UUID.class),
        r.get("counted_barcode", String.class),
        r.get("counted_note", String.class));
  }

  private static StocktakeTask task(org.jooq.Record r) {
    long lineCount = r.get("line_count", Long.class);
    long counted = r.get("counted_line_count", Long.class);
    StocktakeTaskStatus status =
        counted == 0 ? StocktakeTaskStatus.PENDING
            : counted >= lineCount ? StocktakeTaskStatus.DONE
            : StocktakeTaskStatus.COUNTING;
    return new StocktakeTask(
        r.get("id", UUID.class),
        r.get("sheet_id", UUID.class),
        StocktakeTaskUnit.valueOf(r.get("task_unit", String.class)),
        r.get("task_key", UUID.class),
        r.get("task_code", String.class),
        r.get("task_name", String.class),
        r.get("assigned_to_user_id", UUID.class),
        status,
        lineCount,
        counted,
        r.get("book_total", BigDecimal.class),
        r.get("counted_total", BigDecimal.class),
        r.get("variance_total", BigDecimal.class));
  }

  private StocktakeSummary summary(UUID sheetId) {
    var r =
        sql.fetchOne(
            "select " + PROGRESS_AGG
                + " from stocktake_line l where l.tenant_id = ? and l.sheet_id = ?",
            tenant(),
            sheetId);
    return new StocktakeSummary(
        r.get("line_count", Long.class),
        r.get("counted_line_count", Long.class),
        r.get("variance_line_count", Long.class),
        scale(r.get("book_total", BigDecimal.class)),
        scale(r.get("counted_total", BigDecimal.class)),
        scale(r.get("variance_total", BigDecimal.class)));
  }

  /** 盘点单存在性检查：跨租户在 RLS 下查不到 → 404，不泄露存在性。 */
  private void requireSheet(UUID sheetId) {
    if (sql.fetchOne("select id from stocktake_sheet where tenant_id = ? and id = ?", tenant(), sheetId) == null) {
      throw notFound(sheetId);
    }
  }

  // ============================================================ 小工具

  /** 租户来自 RLS 的同一个来源，绝不来自任何请求参数或客户端头（与 7.1.2.2 / 7.2.2.1 同一句）。 */
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

  private static boolean notBlank(String v) {
    return v != null && !v.isBlank();
  }

  private static String blankToNull(String v) {
    return notBlank(v) ? v.trim() : null;
  }

  private static StocktakeScopeType scopeType(String raw) {
    if (!notBlank(raw)) {
      throw bad("必须给出 scopeType：" + java.util.Arrays.stream(StocktakeScopeType.values())
          .map(e -> e.name() + "(" + e.label() + ")")
          .reduce((a, b) -> a + " / " + b)
          .orElse(""));
    }
    try {
      return StocktakeScopeType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的 scopeType：" + raw + "。可用值 " + java.util.Arrays.stream(StocktakeScopeType.values())
          .map(Enum::name).toList());
    }
  }

  private static StocktakeTaskUnit taskUnit(String raw) {
    if (!notBlank(raw)) {
      throw bad("必须给出 taskUnit：BY_LOCATION（按库位）或 BY_SKU（按 SKU）");
    }
    try {
      return StocktakeTaskUnit.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的 taskUnit：" + raw + "。可用值 BY_LOCATION / BY_SKU");
    }
  }

  private static StocktakeLineFilter lineFilter(String raw) {
    if (!notBlank(raw)) {
      return StocktakeLineFilter.ALL;
    }
    try {
      return StocktakeLineFilter.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的 filter：" + raw + "。可用值 " + java.util.Arrays.stream(StocktakeLineFilter.values())
          .map(Enum::name).toList());
    }
  }

  private static ResponseStatusException notFound(UUID id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "盘点单不存在或不属于当前租户：" + id);
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}