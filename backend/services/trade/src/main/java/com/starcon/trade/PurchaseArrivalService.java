package com.starcon.trade;

import static com.starcon.trade.PurchaseArrivalModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.3.2.2 到货与逾期。
 *
 * <p><b>本类是 V235 五张新表的写入所有者</b>（purchase_arrival_tracking /
 * purchase_arrival_receipt / purchase_arrival_payable_confirmation /
 * purchase_arrival_disposition / purchase_arrival_event），
 * 对 warehouse 的 8.2.1.1 收货单与 10.4.1.2 在途跟踪**只经授权端口读**。
 * 刻意不加对外外键、不 ALTER 既有表 ⇒ 跨车道 DDL 回归面 = 0。
 *
 * <p><b>八条硬纪律</b>：
 *
 * <ol>
 *   <li><b>每一条查询都显式带 {@code tenant_id} 谓词</b>，不依赖 RLS 当唯一防线。
 *       跟踪单里同时有供应商名、采购单号、单价与应付金额；
 *       端点又大多是<b>按 id 直达</b>的 URL —— 漏掉租户谓词就是跨租户泄露。</li>
 *   <li><b>已收 / 未收数量不由本服务算</b>：DB 守卫① 永远从到货流水重算
 *       （V235 第二节 ①）。本服务只写「到货流水」，数量是它的<b>派生结果</b>。</li>
 *   <li><b>应付部分确认不由本服务算</b>：金额 = 单价快照 × 本次到货数量，
 *       由 DB 守卫③ / ④ 算。传输层<b>没有</b>金额字段 —— 传进来也传不进来。</li>
 *   <li><b>「没有财务确认就没有生效」</b>：登记时生效金额恒为 0；
 *       本服务<b>刻意没有</b>「直接置为已确认应付」的旁路。</li>
 *   <li><b>逾期用数据库时钟判</b>：判定时刻取 {@code now()}（DB 时钟），
 *       不取应用机本地时钟 —— 跨时钟域偏差会把没逾期的单子判成逾期。</li>
 *   <li><b>在途不判逾期</b>：{@code IN_TRANSIT} 既不会被落成 {@code OVERDUE}，
 *       也不会被算成逾期天数（V235 第三节）。</li>
 *   <li><b>处置建议由事实推导</b>：依据里带具体数字（逾期天数 / 缺口 / 在途滞留天数 /
 *       该供应商在本租户下的逾期单数），不是一段写死的话。</li>
 *   <li><b>时间绑定</b>：时间一律 {@code ?::timestamptz} + {@link OffsetDateTime}，
 *       日期一律 {@code ?::date} + {@link LocalDate}。</li>
 * </ol>
 */
@Service
public class PurchaseArrivalService {

  /** 列表单页上限。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 数量刻度：6 位（AGENTS.md：数量 NUMERIC(19,6)）。 */
  private static final int QTY_SCALE = 6;

  /** 金额刻度：6 位（与本模块的数量刻度一致，金额一律 NUMERIC(19,6)）。 */
  private static final int MONEY_SCALE = 6;

  /**
   * **在途滞留预警阈值（天）**：在途轨迹最后一个节点距今超过这个天数就进预警看板。
   *
   * <p>⚠️ 这**不是**逾期阈值：滞留预警的记录状态仍然是「已发货（在途）」，
   * 逾期天数恒为 0 —— 两个口径刻意不混，混了「逾期天数」这个数字就失真了。
   */
  public static final int IN_TRANSIT_STALL_DAYS = 7;

  /** **建议改供应商的逾期天数阈值**：本单逾期到这个天数就建议改供应商。 */
  public static final int SWITCH_SUPPLIER_OVERDUE_DAYS = 14;

  /** **建议改供应商的供应商逾期单数阈值**：该供应商在本租户下逾期到这个单数就建议改供应商。 */
  public static final int SWITCH_SUPPLIER_OVERDUE_COUNT = 2;

  /** **严重档逾期天数阈值**。 */
  public static final int CRITICAL_OVERDUE_DAYS = 14;

  /** **严重档在途滞留天数阈值**。 */
  public static final int CRITICAL_DWELL_DAYS = 21;

  /**
   * 数据库时钟下的「今天」（UTC 日历日）。
   *
   * <p>刻意用 {@code (now() at time zone 'UTC')::date} 而不是 {@code current_date}：
   * {@code current_date} 跟着会话时区走，同一份数据在两个时区的库里会差一天。
   */
  private static final String TODAY = "(now() at time zone 'UTC')::date";

  /**
   * **生效状态**（与 V235 守卫① 的白名单同口径）。
   *
   * <p>终态与在途豁免在前，逾期判定在后 —— 顺序不能换。
   */
  private static final String EFFECTIVE_STATUS =
      "case when t.arrival_status in ('FULLY_RECEIVED','CANCELLED') then t.arrival_status"
          + " when t.arrival_status = 'IN_TRANSIT' then 'IN_TRANSIT'"
          + " when t.promised_arrival_date < "
          + TODAY
          + " then 'OVERDUE'"
          + " else t.arrival_status end";

  /** **逾期天数**（在途与终态恒为 0）。 */
  private static final String OVERDUE_DAYS =
      "case when t.arrival_status in ('FULLY_RECEIVED','CANCELLED','IN_TRANSIT') then 0"
          + " when t.promised_arrival_date < "
          + TODAY
          + " then (("
          + TODAY
          + ") - t.promised_arrival_date)"
          + " else 0 end";

  /**
   * 跟踪单头的取数形状：**表列 + 四个派生列**。
   *
   * <p>派生列（生效状态 / 逾期天数 / 距承诺日天数 / 到货次数）在这里统一算一次，
   * 列表、详情、看板三处逐字共用同一段 SQL —— 三处各写一遍 SQL 就会出现
   * 「列表写逾期、详情不写逾期」这种对不上的情况。
   */
  private static final String HEAD_SELECT =
      "select t.*, "
          + EFFECTIVE_STATUS
          + " as effective_status, "
          + OVERDUE_DAYS
          + " as overdue_days,"
          + " (t.promised_arrival_date - "
          + TODAY
          + ") as days_until_promise,"
          + " (select count(*) from purchase_arrival_receipt r"
          + "    where r.tenant_id = t.tenant_id and r.tracking_id = t.id) as arrival_count"
          + " from purchase_arrival_tracking t";

  private final DSLContext sql;
  private final WarehouseReceiptQueryPort receipts;
  private final FirstMileTrackingQueryPort transit;

  public PurchaseArrivalService(
      DSLContext sql, WarehouseReceiptQueryPort receipts, FirstMileTrackingQueryPort transit) {
    this.sql = sql;
    this.receipts = receipts;
    this.transit = transit;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：职责句的五个动词逐块报可用性，上游逐个报 delivered 与归属模块号、
   * 本模块<b>刻意不做</b>的事、两条硬规则与状态机的口径。
   */
  public PurchaseArrivalCapabilities capabilities() {
    return new PurchaseArrivalCapabilities(
        List.of(
            "到货跟踪单 purchase_arrival_tracking（V235）—— 本模块拥有这份事实",
            "到货流水 purchase_arrival_receipt（V235，追加式，序号由 DB 排）",
            "应付部分确认 purchase_arrival_payable_confirmation（V235，**trade 侧自有事实记录**）",
            "处置建议 purchase_arrival_disposition（V235，依据由事实推导）",
            "留痕 purchase_arrival_event（V235，追加式，运行角色 UPDATE/DELETE 已收回）",
            "8.2.1.1 收货单 / 10.4.1.2 在途跟踪（warehouse 独立库）—— **只经授权只读端口**，本地只存逻辑引用与快照"),
        List.of(
            section(
                "arrivalStatus",
                "到货状态跟踪",
                "六态显式状态机：待发货 / 已发货（在途）/ 部分到货 / 全部到货 / 逾期未到 / 取消。"
                    + "合法流转在 Java 枚举与 DB 守卫① 两处逐条对齐，非法跃迁一律拒绝。",
                "PurchaseArrivalStatus.canTransitionTo + V235 守卫①"),
            section(
                "partialArrival",
                "部分到货即时更新已收数量并触发应付部分确认",
                "**落成数据库不变量而不是应用层约定**：插入一条到货流水 ⇒ 同一事务里"
                    + "①DB 按流水重算已收 / 未收数量并推进状态；"
                    + "②DB 按「单价快照 × 本次到货数量」落一笔应付部分确认；③追加留痕。"
                    + "「登记了到货却没有应付部分确认」在本模块没有代码路径。",
                "V235 守卫① / 守卫③"),
            section(
                "overdueAlert",
                "逾期自动预警",
                "逾期 = 当前时间 > 承诺到货日，且尚未全部到货。判定时刻取**数据库时钟**并逐字返回"
                    + "（evaluatedAt）—— 逾期天数是「按哪个时刻算的」，不给这个时刻数字就没有意义。"
                    + "日历日口径：承诺日当天不算逾期，次日 00:00 起算。",
                "V235 承诺到货日快照 + 本模块 evaluatedAt"),
            section(
                "inTransitExempt",
                "已发货在途不判逾期",
                "还在路上判逾期会误伤。IN_TRANSIT 既不会落成逾期，逾期天数也恒为 0，"
                    + "DB 守卫① 显式拒绝这条跃迁并写明理由。",
                "V235 守卫①"),
            section(
                "dispositionAdvice",
                "处置建议（催货 / 改供应商 / 调库存 / 延期售卖）",
                "每条建议都带**推导依据**（人话）与 **basis_facts**（同一句话里的数字，jsonb）。"
                    + "依据由事实推导：逾期天数、缺口数量、已收比例、在途滞留天数、"
                    + "该供应商在本租户下的逾期单数与累计逾期天数。"
                    + "在途滞留超过 "
                    + IN_TRANSIT_STALL_DAYS
                    + " 天会单独进预警看板（alertType=IN_TRANSIT_STALLED），"
                    + "但**状态仍是「已发货（在途）」、逾期天数仍是 0** —— 两个口径不混。",
                "本模块 deriveDispositions + V235 处置建议表"),
            section(
                "payableConfirmation",
                "应付部分确认",
                "⚠️ **12 财务（finance 服务）当前不存在**（backend/services/ 下只有 identity / catalog / "
                    + "channel / warehouse / trade / crm）⇒ 本模块落的是**trade 侧自有事实记录**，"
                    + "登记时状态恒为「待财务确认」、生效金额恒为 0，"
                    + "**绝不返回「已触发财务确认」这种假回执**。金额由 DB 按单价快照 × 本次到货数量算。",
                "purchase_arrival_payable_confirmation + V235 守卫④"),
            section(
                "warehouseReceipt",
                "8 仓储收货事件",
                "已交付（8.2.1.1 / 8.2.1.2，warehouse 独立库）⇒ **只经授权只读端口**读，"
                    + "本库只存逻辑引用与快照（收货单号 / 实收量 / 同步时刻），"
                    + "不建跨库 FK、不建跨库视图、不写跨库 SQL。"
                    + "⚠️ 仓库侧当前**不接受 PURCHASE 来源的收货单**（它自己的能力声明写明 6 采购未交付），"
                    + "本模块交付的是 6.3.2.2 到货跟踪、不是 6.3.1.x 采购单 ⇒ 那条限制依然成立。",
                "WarehouseReceiptQueryPort（GET /warehouse/goods-receipts）"),
            section(
                "logisticsInTransit",
                "10 物流在途状态",
                "已交付（10.4.1.2 在途跟踪，warehouse 独立库）⇒ 同上，只经授权只读端口读。"
                    + "**滞留天数**取自「最后一个轨迹节点距今多少天」（节点事件流在详情里），"
                    + "不用 ETA 口径 —— 拿 ETA 当滞留会让「建议催货」的依据是错的。",
                "FirstMileTrackingQueryPort（GET /warehouse/transit-trackings）"),
            section(
                "followUp",
                "6.3.2.1 跟单与交期",
                false,
                "6.3.2.1",
                "上游 6.3.2.1 跟单与交期**未交付**。",
                "本模块用**逻辑引用**表示跟单来源"
                    + "（promise_source 跟单号 / source_doc_no 来源单据号），"
                    + "**不建指向不存在的表的 FK**。承诺到货日是跟单 / 采购单带来的**输入事实**，"
                    + "本模块只引用不拥有。",
                null),
            section(
                "purchaseOrder",
                "6.3.1.x 采购单",
                false,
                "6.3.1.1 / 6.3.1.2 / 6.3.1.3",
                "上游 6.3.1.x 采购单**未交付**。",
                "本模块用**逻辑引用**定位被跟踪的采购单"
                    + "（采购单号 + 来源单据号 + 供应商标识），**不建指向不存在的采购单表的 FK**。",
                null),
            section(
                "stockoutRisk",
                "6.2.1.1 断货风险与补货建议（下游）",
                false,
                "6.2.1.1",
                "下游 6.2.1.1 断货风险与补货建议**未交付**。",
                "本模块不产出断货风险结论；「调库存」「延期售卖」两条建议"
                    + "只依据**本单**的缺口与已收比例推导，依据里会写明这条限制。",
                null),
            section(
                "financeService",
                "12 财务（应付入账）",
                false,
                "12 财务",
                "**finance 服务当前不存在**（backend/services/ 下只有 identity / catalog /"
                    + " channel / warehouse / trade / crm）。",
                "本模块**不写跨库分录**，"
                    + "应付部分确认以 trade 侧自有事实记录落地，"
                    + "finance 服务落地后经**授权 API / event** 衔接。",
                null)),
        stateMachineNote(),
        overdueRuleNote(),
        quantityRuleNote(),
        dispositionRuleNote(),
        payableRuleNote(),
        "上游边界：8 仓储收货事件与 10 物流在途状态在 **warehouse 服务的独立库**里 ⇒ "
            + "本模块只经**授权只读端口**访问，本库只存逻辑引用与快照；"
            + "6.3.1.x 采购单与 6.3.2.1 跟单**未交付** ⇒ 逻辑引用，不建 FK；"
            + "12 财务**不存在** ⇒ 应付部分确认是 trade 侧自有事实记录，不是跨服务调用。");
  }

  private static PurchaseArrivalCapabilitySection section(
      String key,
      String label,
      boolean available,
      String owner,
      String blockedReason,
      String detail,
      String evidence) {
    return new PurchaseArrivalCapabilitySection(key, label, available, owner, blockedReason, detail, evidence);
  }

  /** 可用的一段（无归属模块号、无阻塞原因）。 */
  private static PurchaseArrivalCapabilitySection section(
      String key, String label, String detail, String evidence) {
    return new PurchaseArrivalCapabilitySection(key, label, true, null, null, detail, evidence);
  }

  /** 未交付的一段（报出归属模块号与不可用原因）。 */
  private static PurchaseArrivalCapabilitySection section(
      String key, String label, String owner, String blockedReason, String detail) {
    return new PurchaseArrivalCapabilitySection(key, label, false, owner, blockedReason, detail, null);
  }

  private static String stateMachineNote() {
    return "六态状态机：待发货 → 已发货（在途）/ 部分到货 / 全部到货 / 逾期未到 / 取消。"
        + "合法流转：待发货 → 在途|部分到货|全部到货|逾期未到|取消；"
        + "在途 → 部分到货|全部到货|取消（**不能 → 逾期未到**）；"
        + "部分到货 → 部分到货|全部到货|逾期未到|取消；"
        + "逾期未到 → 在途|部分到货|全部到货|取消（逾期之后才发货是常事）；"
        + "全部到货与取消是**终态**，不可再流转。";
  }

  private static String overdueRuleNote() {
    return "逾期 = 当前时间 > 承诺到货日，且尚未全部到货。"
        + "判定时刻取**数据库时钟**并逐字返回（evaluatedAt），逾期天数按它算。"
        + "日历日口径（UTC）：**承诺日当天不算逾期，次日 00:00 起算**。"
        + "**已发货但在途不算逾期**（还在路上，判逾期会误伤）—— 在途单据的逾期天数恒为 0，"
        + "但会在途滞留超过 "
        + IN_TRANSIT_STALL_DAYS
        + " 天时单独进预警看板（alertType=IN_TRANSIT_STALLED）。";
  }

  private static String quantityRuleNote() {
    return "数量三件套：可收 / 已收 / 未收。**已收数量永远由 DB 从到货流水重算**，"
        + "本模块的端点没有「直接写已收数量」的入口；表级 CHECK 再兜一道"
        + "（已收 + 未收 = 可收）。每次到货都单独留一行流水（序号由 DB 排），"
        + "所以「这单收了几次、每次收了多少、累计多少」都能逐项展开。"
        + "数量与金额一律 NUMERIC(19,6) + BigDecimal，**禁浮点**。";
  }

  private static String dispositionRuleNote() {
    return "四类建议的推导依据（全部来自事实，不是写死的一段话）："
        + "① **催货**：已逾期且仍有缺口 —— 依据写明逾期天数、已收 / 可收、缺口数量；"
        + "在途滞留超阈值时依据改写为「在途轨迹显示已滞留 N 天」。"
        + "② **改供应商**：本单逾期达 "
        + SWITCH_SUPPLIER_OVERDUE_DAYS
        + " 天，或该供应商在本租户下逾期单数达 "
        + SWITCH_SUPPLIER_OVERDUE_COUNT
        + " 单 —— 依据写明该供应商的逾期单数与累计逾期天数。"
        + "③ **调库存**：已收到一部分但仍有缺口 —— 依据写明缺口数量与缺口率，"
        + "并写明 6.2.1.1 断货风险未交付、本建议只依据本单事实。"
        + "④ **延期售卖**：逾期且一件未到 —— 依据写明逾期天数与「已收 0 / 可收 N」。";
  }

  private static String payableRuleNote() {
    return "应付部分确认：**金额、币种、单价、数量全部由 DB 取**"
        + "（单价快照 × 本次到货数量），调用方传不进来。"
        + "登记时状态恒为「待财务确认」、**生效金额恒为 0** —— "
        + "下游 12 财务（finance 服务）当前不存在，本模块落的是 trade 侧自有事实记录，"
        + "**不返回「已触发财务确认」这种假回执**；"
        + "财务确认走独立动作 + 独立权限，确认为终态后整行冻结。";
  }

  // ================================================================ 列表

  /** 到货跟踪列表：真分页 + 多维筛选 + 排序白名单 + 跨全表汇总。 */
  public PurchaseArrivalPage list(PurchaseArrivalQuery query) {
    sweepOverdue();
    int p = query.page() == null || query.page() < 0 ? 0 : query.page();
    int s = query.size() == null || query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);

    List<Object> binds = new ArrayList<>();
    List<String> where = conditions(query, binds);
    String clause = " where " + String.join(" and ", where);

    long total =
        sql.fetchOne(
                "select count(*) from purchase_arrival_tracking t" + clause, binds.toArray())
            .get(0, Long.class);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add((long) p * s);
    List<PurchaseArrivalRow> rows =
        sql.fetch(
                HEAD_SELECT
                    + clause
                    + " order by "
                    + sortSql(query.sort())
                    + " limit ? offset ?",
                pageBinds.toArray())
            .map(this::mapRow);

    return new PurchaseArrivalPage(
        rows,
        p,
        s,
        total,
        summary(clause, binds),
        dbNow(),
        overdueRuleNote(),
        quantityRuleNote(),
        sortNote());
  }

  /** 列表的 where 条件与绑定。 */
  private List<String> conditions(PurchaseArrivalQuery query, List<Object> binds) {
    List<String> where = new ArrayList<>();
    where.add("t.tenant_id = ?::uuid");
    binds.add(currentTenant());

    if (isSet(query.status())) {
      where.add("t.arrival_status = ?");
      binds.add(parseEnum(PurchaseArrivalStatus.class, query.status(), "到货状态").name());
    }
    if (query.supplierRefId() != null) {
      where.add("t.supplier_ref_id = ?::uuid");
      binds.add(query.supplierRefId());
    }
    if (query.overdueOnly() != null && query.overdueOnly()) {
      // ⚠️ 用**生效状态**判逾期，而不是「承诺日已过」——
      //   后者会把在途单也算进来（在途不判逾期）。
      where.add("(" + EFFECTIVE_STATUS + ") = 'OVERDUE'");
    }
    if (query.inTransitOnly() != null && query.inTransitOnly()) {
      where.add("t.arrival_status = 'IN_TRANSIT'");
    }
    if (isSet(query.keyword())) {
      where.add(
          "(t.purchase_order_no ilike ? or t.tracking_no ilike ? or t.source_doc_no ilike ?"
              + " or t.supplier_code ilike ? or t.supplier_name ilike ? or t.promise_source ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      for (int i = 0; i < 6; i++) {
        binds.add(like);
      }
    }
    if (query.promisedBefore() != null) {
      where.add("t.promised_arrival_date <= ?::date");
      binds.add(query.promisedBefore());
    }
    return where;
  }

  /** 排序白名单。 */
  private static String sortSql(String sort) {
    String key = sort == null ? "" : sort.trim();
    return switch (key) {
      case "promise_asc" -> "t.promised_arrival_date asc, t.created_at desc, t.id desc";
      case "overdue_desc" -> "overdue_days desc, t.promised_arrival_date asc, t.id desc";
      case "supplier_asc" -> "t.supplier_code asc, t.created_at desc, t.id desc";
      case "updated_desc" -> "t.updated_at desc, t.id desc";
      default -> "t.created_at desc, t.id desc";
    };
  }

  private static String sortNote() {
    return "排序白名单五个键（created_desc / promise_asc / overdue_desc / supplier_asc / updated_desc）；"
        + "其余取值落回 created_desc，不接受任意表达式。";
  }

  /** 页汇总：跨全表算，不随分页变小。 */
  private PurchaseArrivalSummary summary(String clause, List<Object> binds) {
    Record r =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where t.arrival_status = 'PENDING_SHIPMENT') as pending,"
                + " count(*) filter (where t.arrival_status = 'IN_TRANSIT') as transit,"
                + " count(*) filter (where t.arrival_status = 'PARTIALLY_RECEIVED') as partial,"
                + " count(*) filter (where t.arrival_status = 'FULLY_RECEIVED') as full,"
                + " count(*) filter (where t.arrival_status = 'OVERDUE') as overdue,"
                + " count(*) filter (where t.arrival_status = 'CANCELLED') as cancelled,"
                + " coalesce(sum(case when (" + EFFECTIVE_STATUS + ") = 'OVERDUE'"
                + "   then t.outstanding_qty else 0 end), 0) as overdue_outstanding,"
                + " coalesce(sum(t.pending_payable_amount), 0) as pending_payable,"
                + " coalesce(sum(t.confirmed_payable_amount), 0) as confirmed_payable"
                + " from purchase_arrival_tracking t" + clause,
            binds.toArray());
    return new PurchaseArrivalSummary(
        r.get("total", Long.class),
        r.get("pending", Long.class),
        r.get("transit", Long.class),
        r.get("partial", Long.class),
        r.get("full", Long.class),
        r.get("overdue", Long.class),
        r.get("cancelled", Long.class),
        r.get("overdue_outstanding", BigDecimal.class),
        r.get("pending_payable", BigDecimal.class),
        r.get("confirmed_payable", BigDecimal.class));
  }

  // ================================================================ 详情

  /** 到货跟踪全景：头 + 到货流水 + 应付部分确认 + 处置建议 + 留痕 + 可执行动作。 */
  public PurchaseArrivalDetail detail(UUID id) {
    sweepOverdueFor(id);
    Record head = requireTracking(id);
    OffsetDateTime evaluatedAt = dbNow();

    List<PurchaseArrivalReceiptRow> receipts =
        sql.fetch(
                "select * from purchase_arrival_receipt where tenant_id = ?::uuid and tracking_id = ?::uuid"
                    + " order by arrival_seq",
                currentTenant(),
                id)
            .map(this::mapReceipt);

    List<PurchaseArrivalPayableRow> payable =
        sql.fetch(
                "select * from purchase_arrival_payable_confirmation"
                    + " where tenant_id = ?::uuid and tracking_id = ?::uuid"
                    + " order by created_at, id",
                currentTenant(),
                id)
            .map(this::mapPayable);

    List<PurchaseArrivalDispositionRow> dispositions = dispositionsOf(head, evaluatedAt);

    List<PurchaseArrivalEventRow> events =
        sql.fetch(
                "select * from purchase_arrival_event where tenant_id = ?::uuid and tracking_id = ?::uuid"
                    + " order by occurred_at, id",
                currentTenant(),
                id)
            .map(this::mapEvent);

    return new PurchaseArrivalDetail(
        mapRow(head),
        receipts,
        payable,
        dispositions,
        events,
        actionsOf(head),
        evaluatedAt,
        overdueRuleNote(),
        dispositionRuleNote(),
        "上游边界：8 仓储收货事件与 10 物流在途状态在 warehouse 独立库，"
            + "本模块只经授权只读端口读并冻结快照；"
            + "6.3.1.x 采购单与 6.3.2.1 跟单未交付 ⇒ 逻辑引用；"
            + "12 财务不存在 ⇒ 应付部分确认是 trade 侧自有事实记录。");
  }

  // ================================================================ 建单

  /** 建跟踪单（恒为「待发货」）。 */
  public PurchaseArrivalDetail create(PurchaseArrivalCreate request, UUID actorId) {
    if (request == null) {
      throw new IllegalArgumentException("必须给出建单内容");
    }
    String purchaseOrderNo = requireText(request.purchaseOrderNo(), "采购单号");
    UUID supplierRefId =
        request.supplierRefId() == null
            ? throwIllegal("必须给出供应商标识（供应商主数据在 catalog 独立库，这里只存逻辑引用）")
            : request.supplierRefId();
    BigDecimal receivableQty = qty(request.receivableQty(), "可收数量");
    BigDecimal unitPrice = money(request.unitPrice(), "单价");
    String currency = requireCurrency(request.currency());
    LocalDate promised = request.promisedArrivalDate();
    if (promised == null) {
      throw new IllegalArgumentException(
          "必须给出承诺到货日（跟单 / 采购单带来的输入事实，逾期判定全靠它）");
    }
    String sourceDocType =
        isSet(request.sourceDocType())
            ? parseEnum(
                    SourceDocType.class, request.sourceDocType(), "来源单据类型").name()
            : "PURCHASE_ORDER";

    UUID id = UUID.randomUUID();
    long seq = sql.fetchOne("select nextval('purchase_arrival_no_seq')").get(0, Long.class);
    String trackingNo = "PA-" + seq;

    sql.execute(
        "insert into purchase_arrival_tracking("
            + "id,tenant_id,tracking_no,purchase_order_no,purchase_order_ref_id,source_doc_no,"
            + "source_doc_type,supplier_ref_id,supplier_code,supplier_name,"
            + "receivable_qty,unit_price,currency,promised_arrival_date,promise_source,"
            + "promise_updated_at,arrival_status,created_by,created_at,updated_at,version)"
            + " values(?::uuid,?::uuid,?,?,?::uuid,?,?,?::uuid,?,?,?::numeric,?::numeric,?,?::date,?,"
            + "?::timestamptz,'PENDING_SHIPMENT',?::uuid,now(),now(),0)",
        id,
        currentTenant(),
        trackingNo,
        purchaseOrderNo,
        request.purchaseOrderRefId(),
        trimOrEmpty(request.sourceDocNo()),
        sourceDocType,
        supplierRefId,
        trimOrEmpty(request.supplierCode()),
        trimOrEmpty(request.supplierName()),
        receivableQty,
        unitPrice,
        currency,
        promised,
        trimOrEmpty(request.promiseSource()),
        dbNow(),
        actorId);

    appendEvent(id, "CREATED", null, "PENDING_SHIPMENT", null, null, "建跟踪单：" + trackingNo, actorId, dbNow());
    refreshDispositions(id, actorId);
    return detail(id);
  }

  // ================================================================ 已发货

  /** 标记已发货（在途）。 */
  public PurchaseArrivalDetail ship(UUID id, PurchaseArrivalShip request, UUID actorId) {
    if (request == null) {
      throw new IllegalArgumentException("必须给出发货信息");
    }
    Record head = requireTracking(id);
    PurchaseArrivalStatus current = statusOf(head);
    if (!current.canTransitionTo(PurchaseArrivalStatus.IN_TRANSIT)) {
      throw new IllegalArgumentException(
          current.transitionBlockedReason(PurchaseArrivalStatus.IN_TRANSIT));
    }
    requireVersion(head, request.expectedVersion());

    OffsetDateTime shippedAt = request.shippedAt() == null ? dbNow() : request.shippedAt();
    LocalDate promised = request.promisedArrivalDate();
    if (promised == null) {
      promised = head.get("promised_arrival_date", LocalDate.class);
    }

    sql.execute(
        "update purchase_arrival_tracking set arrival_status = 'IN_TRANSIT', shipped_at = ?::timestamptz,"
            + " promised_arrival_date = ?::date, promise_source = ?,"
            + " promise_updated_at = case when ?::date is distinct from promised_arrival_date"
            + "   then ?::timestamptz else promise_updated_at end,"
            + " updated_at = ?::timestamptz, version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        shippedAt,
        promised,
        isSet(request.promiseSource()) ? request.promiseSource().trim() : head.get("promise_source", String.class),
        promised,
        dbNow(),
        dbNow(),
        currentTenant(),
        id);

    appendEvent(
        id,
        "SHIPPED",
        current.name(),
        PurchaseArrivalStatus.IN_TRANSIT.name(),
        null,
        null,
        "已发货（在途）"
            + (isSet(request.shipmentCode()) ? "，头程发运单 " + request.shipmentCode().trim() : ""),
        actorId,
        shippedAt);

    if (isSet(request.shipmentCode()) || isSet(request.waybillNo())) {
      sql.execute(
          "update purchase_arrival_tracking set in_transit_shipment_code = coalesce(nullif(?,''),"
              + "   in_transit_shipment_code), in_transit_waybill_no = coalesce(nullif(?,''),"
              + "   in_transit_waybill_no), updated_at = ?::timestamptz, version = version + 1"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          isSet(request.shipmentCode()) ? request.shipmentCode().trim() : "",
          isSet(request.waybillNo()) ? request.waybillNo().trim() : "",
          dbNow(),
          currentTenant(),
          id);
    }

    refreshDispositions(id, actorId);
    return detail(id);
  }

  // ================================================================ 登记到货

  /**
   * 登记到货（部分 / 全部）。
   *
   * <p>⚠️ **本方法只写「到货流水」**：已收 / 未收数量、状态、应付部分确认**全部由 DB 触发器**
   * 在同一事务里完成（V235 守卫② / ③）—— 本模块没有「先写数量再写流水」这种写法。
   */
  public PurchaseArrivalDetail registerArrival(
      UUID id, PurchaseArrivalArrival request, UUID actorId, String bearer) {
    if (request == null) {
      throw new IllegalArgumentException("必须给出到货信息");
    }
    Record head = requireTracking(id);
    PurchaseArrivalStatus current = statusOf(head);
    if (current.terminal()) {
      throw new IllegalArgumentException(
          current.transitionBlockedReason(PurchaseArrivalStatus.PARTIALLY_RECEIVED));
    }
    requireVersion(head, request.expectedVersion());

    BigDecimal arrivedQty = qty(request.arrivedQty(), "本次到货数量");
    BigDecimal outstanding = head.get("outstanding_qty", BigDecimal.class);
    if (arrivedQty.compareTo(outstanding) > 0) {
      throw new IllegalArgumentException(
          "本次到货数量（" + arrivedQty.toPlainString() + "）不得超过未收数量（"
              + outstanding.toPlainString() + "）：可收 "
              + head.get("receivable_qty", BigDecimal.class).toPlainString()
              + "，已收 " + head.get("received_qty", BigDecimal.class).toPlainString());
    }

    // ---- 上游 8.2.1.1 收货单：给了单号就去 warehouse 授权核对，核对不到就报错 ----
    UUID receiptId = null;
    String receiptCode = "";
    BigDecimal receiptReceivedQty = null;
    OffsetDateTime receiptSyncedAt = null;
    if (isSet(request.warehouseReceiptCode())) {
      receiptCode = request.warehouseReceiptCode().trim();
      var found = receipts.findByCode(receiptCode, bearer);
      if (found == null) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "仓库里没有这张收货单：" + receiptCode);
      }
      receiptId = found.id();
      receiptReceivedQty = found.receivedQuantity();
      receiptSyncedAt = dbNow();
    }

    OffsetDateTime arrivedAt = request.arrivedAt() == null ? dbNow() : request.arrivedAt();
    UUID receiptRowId = UUID.randomUUID();

    sql.execute(
        "insert into purchase_arrival_receipt("
            + "id,tenant_id,tracking_id,arrival_seq,arrived_qty,arrival_type,arrived_at,"
            + "warehouse_receipt_id,warehouse_receipt_code,warehouse_receipt_received_qty,"
            + "warehouse_receipt_synced_at,waybill_no,in_transit_tracking_id,note,recorded_by)"
            + " values(?::uuid,?::uuid,?::uuid,1,?::numeric,'PARTIAL',?::timestamptz,?::uuid,?,?::numeric,"
            + "?::timestamptz,?,?::uuid,?,?::uuid)",
        receiptRowId,
        currentTenant(),
        id,
        arrivedQty,
        arrivedAt,
        receiptId,
        receiptCode,
        receiptReceivedQty,
        receiptSyncedAt,
        isSet(request.waybillNo())
            ? request.waybillNo().trim()
            : head.get("in_transit_waybill_no", String.class),
        head.get("in_transit_tracking_id", UUID.class),
        trimOrEmpty(request.note()),
        actorId);

    refreshDispositions(id, actorId);
    return detail(id);
  }

  // ================================================================ 取消

  /** 取消跟踪单。必须写理由。 */
  public PurchaseArrivalDetail cancel(UUID id, PurchaseArrivalCancel request, UUID actorId) {
    if (request == null) {
      throw new IllegalArgumentException("必须给出取消信息");
    }
    String reason = requireText(request.reason(), "取消理由");
    Record head = requireTracking(id);
    PurchaseArrivalStatus current = statusOf(head);
    if (!current.canTransitionTo(PurchaseArrivalStatus.CANCELLED)) {
      throw new IllegalArgumentException(
          current.transitionBlockedReason(PurchaseArrivalStatus.CANCELLED));
    }
    requireVersion(head, request.expectedVersion());

    sql.execute(
        "update purchase_arrival_tracking set arrival_status = 'CANCELLED', cancelled_at = ?::timestamptz,"
            + " cancel_reason = ?, updated_at = ?::timestamptz, version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        dbNow(),
        reason,
        dbNow(),
        currentTenant(),
        id);

    appendEvent(
        id,
        "CANCELLED",
        current.name(),
        PurchaseArrivalStatus.CANCELLED.name(),
        null,
        null,
        reason,
        actorId,
        dbNow());
    refreshDispositions(id, actorId);
    return detail(id);
  }

  // ================================================================ 在途同步

  /**
   * 同步 10.4.1.2 在途状态快照。
   *
   * <p>⚠️ **滞留天数**取自「最后一个轨迹节点距今多少天」（节点事件流在详情里），
   * 不用 ETA 口径 —— 拿 ETA 当滞留会让「建议催货」的依据是错的。
   */
  public PurchaseArrivalDetail syncInTransit(UUID id, UUID actorId, String bearer) {
    Record head = requireTracking(id);
    String shipmentCode = head.get("in_transit_shipment_code", String.class);
    if (shipmentCode == null || shipmentCode.isBlank()) {
      throw new IllegalArgumentException(
          "这张跟踪单还没有登记头程发运单号（在途跟踪按发运单号定位），"
              + "先在「标记已发货」时填入再同步");
    }
    var tracking = transit.findByShipmentCode(shipmentCode, bearer);
    if (tracking == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "仓库里没有这一票在途记录：" + shipmentCode);
    }
    var detail = transit.detail(tracking.id(), bearer);

    OffsetDateTime evaluatedAt = dbNow();
    OffsetDateTime lastNode = null;
    if (detail.events() != null) {
      for (var event : detail.events()) {
        if (event.occurredAt() != null && (lastNode == null || event.occurredAt().isAfter(lastNode))) {
          lastNode = event.occurredAt();
        }
      }
    }
    int dwellDays =
        lastNode == null ? 0 : (int) Math.max(0, ChronoUnit.DAYS.between(lastNode, evaluatedAt));

    sql.execute(
        "update purchase_arrival_tracking set in_transit_tracking_id = ?::uuid,"
            + " in_transit_shipment_code = ?, in_transit_waybill_no = ?,"
            + " in_transit_dwell_days = ?, in_transit_eta = ?::timestamptz,"
            + " in_transit_last_node_at = ?::timestamptz, in_transit_synced_at = ?::timestamptz,"
            + " updated_at = ?::timestamptz, version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        tracking.id(),
        tracking.shipmentCode(),
        tracking.bookingNo() == null ? "" : tracking.bookingNo(),
        dwellDays,
        tracking.effectiveEta(),
        lastNode,
        evaluatedAt,
        evaluatedAt,
        currentTenant(),
        id);

    appendEvent(
        id,
        "IN_TRANSIT_SYNCED",
        null,
        null,
        null,
        null,
        "同步在途状态：发运单 " + tracking.shipmentCode() + "，滞留 " + dwellDays + " 天",
        actorId,
        evaluatedAt);
    refreshDispositions(id, actorId);
    return detail(id);
  }

  // ================================================================ 逾期看板

  /** 逾期预警看板：逾期未到 + 在途滞留两类预警，各自带处置建议。 */
  public PurchaseArrivalAlertBoard alertBoard(
      String alertType, UUID supplierRefId, Integer minOverdueDays, Integer page, Integer size) {
    sweepOverdue();
    int p = page == null || page < 0 ? 0 : page;
    int s = size == null || size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
    int minDays = minOverdueDays == null ? 0 : Math.max(0, minOverdueDays);

    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    where.add("t.tenant_id = ?::uuid");
    binds.add(currentTenant());
    where.add(
        "((" + EFFECTIVE_STATUS + ") = 'OVERDUE'"
            + " or (t.arrival_status = 'IN_TRANSIT' and t.in_transit_dwell_days >= ?))");
    binds.add(IN_TRANSIT_STALL_DAYS);
    if (supplierRefId != null) {
      where.add("t.supplier_ref_id = ?::uuid");
      binds.add(supplierRefId);
    }
    if ("OVERDUE".equals(alertType)) {
      where.add("((" + EFFECTIVE_STATUS + ") = 'OVERDUE')");
    } else if ("IN_TRANSIT_STALLED".equals(alertType)) {
      where.add("(t.arrival_status = 'IN_TRANSIT' and t.in_transit_dwell_days >= ?)");
      binds.add(IN_TRANSIT_STALL_DAYS);
    }
    where.add("((" + EFFECTIVE_STATUS + ") <> 'OVERDUE' or (" + OVERDUE_DAYS + ") >= ?)");
    binds.add(minDays);

    String clause = " where " + String.join(" and ", where);

    long total =
        sql.fetchOne(
                "select count(*) from purchase_arrival_tracking t" + clause, binds.toArray())
            .get(0, Long.class);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add((long) p * s);
    List<Record> records =
        sql.fetch(
            HEAD_SELECT
                + clause
                + " order by overdue_days desc, t.promised_arrival_date asc, t.id desc"
                + " limit ? offset ?",
            pageBinds.toArray());

    OffsetDateTime evaluatedAt = dbNow();
    Map<UUID, SupplierOverdueStat> stats = supplierOverdueStats(records);

    List<PurchaseArrivalAlertRow> rows = new ArrayList<>();
    int critical = 0;
    int warn = 0;
    int overdueCount = 0;
    int stalledCount = 0;
    int maxOverdueDays = 0;
    BigDecimal overdueOutstanding = BigDecimal.ZERO.setScale(QTY_SCALE);
    for (Record record : records) {
      String status = record.get("effective_status", String.class);
      int overdueDays = record.get("overdue_days", Integer.class);
      boolean overdue = "OVERDUE".equals(status);
      if (overdue) {
        overdueCount++;
        maxOverdueDays = Math.max(maxOverdueDays, overdueDays);
        overdueOutstanding =
            overdueOutstanding.add(record.get("outstanding_qty", BigDecimal.class));
      } else {
        stalledCount++;
      }
      List<PurchaseArrivalDispositionRow> dispositions = dispositionsOf(record, evaluatedAt);
      boolean severe = dispositions.stream().anyMatch(d -> "CRITICAL".equals(d.severity()));
      boolean warning = dispositions.stream().anyMatch(d -> "WARN".equals(d.severity()));
      if (severe) {
        critical++;
      } else if (warning) {
        warn++;
      }
      BigDecimal receivable = record.get("receivable_qty", BigDecimal.class);
      BigDecimal outstanding = record.get("outstanding_qty", BigDecimal.class);
      rows.add(
          new PurchaseArrivalAlertRow(
              record.get("id", UUID.class),
              record.get("tracking_no", String.class),
              record.get("purchase_order_no", String.class),
              record.get("supplier_code", String.class),
              record.get("supplier_name", String.class),
              overdue ? "OVERDUE" : "IN_TRANSIT_STALLED",
              overdue ? "逾期未到" : "在途滞留",
              status,
              statusLabel(status),
              overdueDays,
              record.get("in_transit_dwell_days", Integer.class),
              record.get("promised_arrival_date", LocalDate.class),
              receivable,
              record.get("received_qty", BigDecimal.class),
              outstanding,
              ratio(outstanding, receivable),
              dispositions));
    }

    return new PurchaseArrivalAlertBoard(
        rows,
        p,
        s,
        total,
        new PurchaseArrivalAlertSummary(
            total,
            overdueCount,
            stalledCount,
            critical,
            warn,
            overdueOutstanding,
            maxOverdueDays),
        evaluatedAt,
        overdueRuleNote(),
        dispositionRuleNote());
  }

  // ================================================================ 应付部分确认

  /** 财务确认应付部分确认。 */
  public PurchaseArrivalDetail confirmPayable(
      UUID id, UUID confirmationId, PurchaseArrivalPayableConfirm request, UUID actorId) {
    Record confirmation = requireConfirmation(id, confirmationId);
    String reference =
        request == null ? "" : trimOrEmpty(request.financeReference());
    if (reference.isBlank()) {
      throw new IllegalArgumentException("必须给出财务单据号（应付单号）");
    }
    OffsetDateTime now = dbNow();
    sql.execute(
        "update purchase_arrival_payable_confirmation set confirmation_status = 'CONFIRMED',"
            + " finance_reference = ?, confirmed_by = ?::uuid, confirmed_at = ?::timestamptz,"
            + " updated_at = ?::timestamptz, version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        reference,
        actorId,
        now,
        now,
        currentTenant(),
        confirmationId);
    recomputePayableTotals(id);
    appendEvent(
        id,
        "PAYABLE_CONFIRMED",
        null,
        null,
        confirmation.get("confirmation_amount", BigDecimal.class),
        null,
        "财务确认应付部分确认：" + confirmation.get("confirmation_no", String.class),
        actorId,
        now);
    return detail(id);
  }

  /** 财务驳回应付部分确认。必须写理由。 */
  public PurchaseArrivalDetail rejectPayable(
      UUID id, UUID confirmationId, PurchaseArrivalPayableReject request, UUID actorId) {
    String reason = request == null ? "" : requireText(request.reason(), "驳回理由");
    requireConfirmation(id, confirmationId);
    OffsetDateTime now = dbNow();
    sql.execute(
        "update purchase_arrival_payable_confirmation set confirmation_status = 'REJECTED',"
            + " rejected_by = ?::uuid, rejected_at = ?::timestamptz, reject_reason = ?,"
            + " updated_at = ?::timestamptz, version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        actorId,
        now,
        reason,
        currentTenant(),
        confirmationId);
    recomputePayableTotals(id);
    appendEvent(
        id,
        "PAYABLE_REJECTED",
        null,
        null,
        null,
        null,
        "财务驳回应付部分确认：" + reason,
        actorId,
        now);
    return detail(id);
  }

  // ================================================================ 处置建议处理

  /** 处置建议的处理（采纳 / 忽略）。 */
  public PurchaseArrivalDetail resolveDisposition(
      UUID id, UUID dispositionId, PurchaseArrivalDispositionResolve request, UUID actorId) {
    if (request == null) {
      throw new IllegalArgumentException("必须给出处理结果");
    }
    DispositionStatus status =
        parseEnum(DispositionStatus.class, request.dispositionStatus(), "处理结果");
    if (status == DispositionStatus.OPEN) {
      throw new IllegalArgumentException("处理结果只能是已采纳（ADOPTED）或已忽略（DISMISSED）");
    }
    String note = requireText(request.note(), "处理说明");
    int updated =
        sql.execute(
                "update purchase_arrival_disposition set disposition_status = ?, resolve_note = ?,"
                    + " resolved_by = ?::uuid, resolved_at = ?::timestamptz,"
                    + " updated_at = ?::timestamptz, version = version + 1"
                    + " where tenant_id = ?::uuid and id = ?::uuid and tracking_id = ?::uuid",
                status.name(),
                note,
                actorId,
                dbNow(),
                dbNow(),
                currentTenant(),
                dispositionId,
                id);
    if (updated == 0) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "处置建议不存在或不属于当前租户：" + dispositionId);
    }
    appendEvent(
        id,
        "DISPOSITION_RESOLVED",
        null,
        null,
        null,
        null,
        status.label() + "：" + note,
        actorId,
        dbNow());
    return detail(id);
  }

  // ================================================================ 逾期落库

  /**
   * 逾期落库（全租户）：把满足条件的行推到「逾期未到」。
   *
   * <p>⚠️ <b>刻意不 bump {@code version}</b>：逾期是时间推动的系统判定，不是用户编辑，
   * bump 了会让客户端手里的 expectedVersion 无故失效。
   *
   * <p>⚠️ <b>在途被排除在外</b>：{@code IN_TRANSIT} 不在可逾期状态集合里 ——
   * 还在路上判逾期会误伤。
   */
  private void sweepOverdue() {
    sql.execute(
        "update purchase_arrival_tracking set arrival_status = 'OVERDUE', updated_at = now()"
            + " where tenant_id = ?::uuid"
            + " and arrival_status in ('PENDING_SHIPMENT','PARTIALLY_RECEIVED')"
            + " and promised_arrival_date < " + TODAY,
        currentTenant());
  }

  /** 逾期落库（单张）。 */
  private void sweepOverdueFor(UUID id) {
    sql.execute(
        "update purchase_arrival_tracking set arrival_status = 'OVERDUE', updated_at = now()"
            + " where tenant_id = ?::uuid and id = ?::uuid"
            + " and arrival_status in ('PENDING_SHIPMENT','PARTIALLY_RECEIVED')"
            + " and promised_arrival_date < " + TODAY,
        currentTenant(),
        id);
  }

  // ================================================================ 处置建议（事实推导）

  /** 一条推导出来的建议。 */
  private record DerivedDisposition(
      DispositionActionType actionType, DispositionSeverity severity, String basisNote, String basisFacts) {}

  /** 一个供应商在本租户下的逾期统计。 */
  private record SupplierOverdueStat(long count, long daysSum, int maxDays) {}

  /**
   * **处置建议的推导**（纯函数：同样的事实 ⇒ 同样的建议与依据）。
   *
   * <p>四条规则各自独立，可同时命中；每条依据里都带具体数字。
   */
  private List<DerivedDisposition> derive(Record head, int overdueDays, SupplierOverdueStat stat) {
    List<DerivedDisposition> out = new ArrayList<>();
    BigDecimal receivable = head.get("receivable_qty", BigDecimal.class);
    BigDecimal received = head.get("received_qty", BigDecimal.class);
    BigDecimal outstanding = head.get("outstanding_qty", BigDecimal.class);
    LocalDate promised = head.get("promised_arrival_date", LocalDate.class);
    int dwellDays = head.get("in_transit_dwell_days", Integer.class);
    String supplierCode = head.get("supplier_code", String.class);
    String supplierName = head.get("supplier_name", String.class);
    String status = head.get("effective_status", String.class);

    String facts =
        json(
            "overdueDays", overdueDays,
            "inTransitDwellDays", dwellDays,
            "receivableQty", receivable,
            "receivedQty", received,
            "outstandingQty", outstanding,
            "gapRatio", ratio(outstanding, receivable),
            "promisedArrivalDate", promised == null ? "" : promised.toString(),
            "arrivalStatus", status,
            "supplierOverdueCount", stat.count(),
            "supplierOverdueDays", stat.daysSum(),
            "supplierMaxOverdueDays", stat.maxDays());

    boolean overdue = overdueDays > 0;
    boolean stalled = dwellDays >= IN_TRANSIT_STALL_DAYS;

    // ---- ① 催货 ----
    if ((overdue && outstanding.signum() > 0) || stalled) {
      StringBuilder basis = new StringBuilder();
      if (overdue) {
        basis
            .append("已逾期 ")
            .append(overdueDays)
            .append(" 天（承诺到货日 ")
            .append(promised)
            .append("，判定时刻取数据库时钟），已收 ")
            .append(received.toPlainString())
            .append(" / 可收 ")
            .append(receivable.toPlainString())
            .append("，缺口 ")
            .append(outstanding.toPlainString())
            .append(" 件");
      } else {
        basis
            .append("承诺到货日 ")
            .append(promised)
            .append(" 未到，缺口 ")
            .append(outstanding.toPlainString())
            .append(" 件");
      }
      if (stalled) {
        basis
            .append("；在途轨迹显示已滞留 ")
            .append(dwellDays)
            .append(" 天（10.4.1.2 快照，最后一个轨迹节点 ")
            .append(head.get("in_transit_last_node_at", OffsetDateTime.class))
            .append("）");
      }
      DispositionSeverity severity =
          (overdueDays >= CRITICAL_OVERDUE_DAYS || dwellDays >= CRITICAL_DWELL_DAYS)
              ? DispositionSeverity.CRITICAL
              : (overdue || stalled ? DispositionSeverity.WARN : DispositionSeverity.INFO);
      out.add(
          new DerivedDisposition(
              DispositionActionType.URGE_SUPPLIER, severity, basis.toString(), facts));
    }

    // ---- ② 改供应商 ----
    if (overdueDays >= SWITCH_SUPPLIER_OVERDUE_DAYS || stat.count() >= SWITCH_SUPPLIER_OVERDUE_COUNT) {
      String basis =
          "供应商 "
              + (supplierCode == null || supplierCode.isBlank() ? "(未填编码)" : supplierCode)
              + (supplierName == null || supplierName.isBlank() ? "" : "（" + supplierName + "）")
              + " 在本租户下有 "
              + stat.count()
              + " 单逾期，累计逾期 "
              + stat.daysSum()
              + " 天（同一口径：当前时刻 − 承诺到货日）；本单已逾期 "
              + overdueDays
              + " 天，缺口 "
              + outstanding.toPlainString()
              + " 件";
      DispositionSeverity severity =
          (stat.count() >= SWITCH_SUPPLIER_OVERDUE_COUNT + 1
                  || overdueDays >= CRITICAL_OVERDUE_DAYS + 7)
              ? DispositionSeverity.CRITICAL
              : DispositionSeverity.WARN;
      out.add(
          new DerivedDisposition(
              DispositionActionType.SWITCH_SUPPLIER, severity, basis, facts));
    }

    // ---- ③ 调库存 ----
    if (outstanding.signum() > 0 && received.signum() > 0) {
      String basis =
          "缺口 "
              + outstanding.toPlainString()
              + " 件未到（可收 "
              + receivable.toPlainString()
              + "，已收 "
              + received.toPlainString()
              + "，缺口率 "
              + percent(ratio(outstanding, receivable))
              + "）；已到部分不足以补齐。⚠️ 6.2.1.1 断货风险与补货建议尚未交付，"
              + "本条建议只依据本单的缺口与已收比例推导";
      DispositionSeverity severity =
          ratio(outstanding, receivable).compareTo(new BigDecimal("0.5")) >= 0
              ? DispositionSeverity.WARN
              : DispositionSeverity.INFO;
      out.add(
          new DerivedDisposition(
              DispositionActionType.ADJUST_STOCK, severity, basis, facts));
    }

    // ---- ④ 延期售卖 ----
    if (overdue && received.signum() == 0) {
      String basis =
          "已逾期 "
              + overdueDays
              + " 天且一件未到（已收 0 / 可收 "
              + receivable.toPlainString()
              + "），按承诺到货日 "
              + promised
              + " 推算的可售窗口已过；继续按原计划上架售卖会出现缺货";
      DispositionSeverity severity =
          overdueDays >= CRITICAL_OVERDUE_DAYS
              ? DispositionSeverity.CRITICAL
              : DispositionSeverity.WARN;
      out.add(
          new DerivedDisposition(
              DispositionActionType.DELAY_SALE, severity, basis, facts));
    }
    return out;
  }

  /** 读出这张跟踪单的建议（派生依据 + 已持久化的处理状态）。 */
  private List<PurchaseArrivalDispositionRow> dispositionsOf(Record head, OffsetDateTime evaluatedAt) {
    UUID id = head.get("id", UUID.class);
    int overdueDays = overdueDaysOf(head, evaluatedAt);
    SupplierOverdueStat stat = supplierOverdueStat(head.get("supplier_ref_id", UUID.class));
    List<DerivedDisposition> derived = derive(head, overdueDays, stat);

    Map<String, Record> persisted = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select * from purchase_arrival_disposition where tenant_id = ?::uuid and tracking_id = ?::uuid"
                + " order by action_type",
            currentTenant(),
            id)) {
      persisted.put(r.get("action_type", String.class), r);
    }

    List<PurchaseArrivalDispositionRow> out = new ArrayList<>();
    for (DerivedDisposition d : derived) {
      Record stored = persisted.remove(d.actionType().name());
      out.add(
          new PurchaseArrivalDispositionRow(
              stored == null ? null : stored.get("id", UUID.class),
              d.actionType().name(),
              d.actionType().label(),
              d.severity().name(),
              d.severity().label(),
              d.basisNote(),
              d.basisFacts(),
              stored == null ? "OPEN" : stored.get("disposition_status", String.class),
              stored == null
                  ? DispositionStatus.OPEN.label()
                  : statusLabel(stored.get("disposition_status", String.class)),
              stored == null ? "" : stored.get("resolve_note", String.class),
              stored == null ? null : stored.get("resolved_by", UUID.class),
              stored == null ? null : stored.get("resolved_at", OffsetDateTime.class),
              stored == null ? evaluatedAt : stored.get("created_at", OffsetDateTime.class),
              stored == null ? evaluatedAt : stored.get("updated_at", OffsetDateTime.class)));
    }
    // 已经不再命中的建议（事实变了）但人已经处理过的，保留在清单里 —— 处理记录是凭据。
    for (Record stored : persisted.values()) {
      String actionType = stored.get("action_type", String.class);
      DispositionActionType type;
      try {
        type = DispositionActionType.valueOf(actionType);
      } catch (IllegalArgumentException e) {
        continue;
      }
      out.add(
          new PurchaseArrivalDispositionRow(
              stored.get("id", UUID.class),
              actionType,
              type.label(),
              stored.get("severity", String.class),
              statusLabel(stored.get("severity", String.class)),
              stored.get("basis_note", String.class),
              stored.get("basis_facts", String.class),
              stored.get("disposition_status", String.class),
              statusLabel(stored.get("disposition_status", String.class)),
              stored.get("resolve_note", String.class),
              stored.get("resolved_by", UUID.class),
              stored.get("resolved_at", OffsetDateTime.class),
              stored.get("created_at", OffsetDateTime.class),
              stored.get("updated_at", OffsetDateTime.class)));
    }
    return out;
  }

  /**
   * 刷新落库建议（派生结果落审计痕迹；处理状态不被刷新覆盖）。
   *
   * <p>只在写路径上跑（建单 / 发货 / 到货 / 取消 / 在途同步），读路径只算不落 ——
   * 读一次就写一次库不是好设计。
   */
  private void refreshDispositions(UUID id, UUID actorId) {
    // 先把逾期落库（幂等），再推导建议 —— 否则「承诺日已过但刚落库」的单子
    // 这一次刷新拿到的还是旧状态，建议会慢一拍。
    sweepOverdueFor(id);
    Record head = requireTracking(id);
    OffsetDateTime evaluatedAt = dbNow();
    int overdueDays = overdueDaysOf(head, evaluatedAt);
    SupplierOverdueStat stat = supplierOverdueStat(head.get("supplier_ref_id", UUID.class));
    List<DerivedDisposition> derived = derive(head, overdueDays, stat);

    for (DerivedDisposition d : derived) {
      sql.execute(
          "insert into purchase_arrival_disposition("
              + "id,tenant_id,tracking_id,action_type,severity,basis_note,basis_facts,"
              + "disposition_status,created_at,updated_at,version)"
              + " values(?::uuid,?::uuid,?::uuid,?,?,?,?::jsonb,'OPEN',?::timestamptz,?::timestamptz,0)"
              + " on conflict (tenant_id,tracking_id,action_type) do update"
              + " set severity = excluded.severity, basis_note = excluded.basis_note,"
              + "   basis_facts = excluded.basis_facts, updated_at = excluded.updated_at,"
              + "   version = purchase_arrival_disposition.version + 1",
          UUID.randomUUID(),
          currentTenant(),
          id,
          d.actionType().name(),
          d.severity().name(),
          d.basisNote(),
          d.basisFacts(),
          evaluatedAt,
          evaluatedAt);
    }
    // 已经不再命中的「待处理」建议清掉；已被采纳 / 忽略的保留（处理记录是凭据）。
    for (String stale :
        List.of(
            DispositionActionType.URGE_SUPPLIER.name(),
            DispositionActionType.SWITCH_SUPPLIER.name(),
            DispositionActionType.ADJUST_STOCK.name(),
            DispositionActionType.DELAY_SALE.name())) {
      boolean stillDerived =
          derived.stream().anyMatch(d -> d.actionType().name().equals(stale));
      if (!stillDerived) {
        sql.execute(
            "delete from purchase_arrival_disposition where tenant_id = ?::uuid and tracking_id = ?::uuid"
                + " and action_type = ? and disposition_status = 'OPEN'",
            currentTenant(),
            id,
            stale);
      }
    }
    appendEvent(
        id,
        "DISPOSITION_REFRESHED",
        null,
        null,
        null,
        null,
        "刷新处置建议：" + derived.size() + " 条",
        actorId,
        evaluatedAt);
  }

  /** 单个供应商在本租户下的逾期统计（真实聚合，不是写死的数）。 */
  private SupplierOverdueStat supplierOverdueStat(UUID supplierRefId) {
    if (supplierRefId == null) {
      return new SupplierOverdueStat(0, 0, 0);
    }
    Record r =
        sql.fetchOne(
            "select count(*) as cnt, coalesce(sum(d), 0) as days_sum, coalesce(max(d), 0) as max_days"
                + " from (select (" + OVERDUE_DAYS + ") as d from purchase_arrival_tracking t"
                + "   where t.tenant_id = ?::uuid and t.supplier_ref_id = ?::uuid) s"
                + " where s.d > 0",
            currentTenant(),
            supplierRefId);
    return new SupplierOverdueStat(
        r.get("cnt", Long.class), r.get("days_sum", Long.class), r.get("max_days", Integer.class));
  }

  /** 一批记录涉及的供应商逾期统计（一次查完，避免 N+1）。 */
  private Map<UUID, SupplierOverdueStat> supplierOverdueStats(List<Record> records) {
    Map<UUID, SupplierOverdueStat> out = new LinkedHashMap<>();
    for (Record r : records) {
      UUID supplier = r.get("supplier_ref_id", UUID.class);
      if (supplier != null && !out.containsKey(supplier)) {
        out.put(supplier, supplierOverdueStat(supplier));
      }
    }
    return out;
  }

  /** 这张跟踪单的逾期天数（与 SQL 口径同源：DB 时钟 + UTC 日历日）。 */
  private int overdueDaysOf(Record head, OffsetDateTime evaluatedAt) {
    String status = head.get("arrival_status", String.class);
    if ("FULLY_RECEIVED".equals(status)
        || "CANCELLED".equals(status)
        || "IN_TRANSIT".equals(status)) {
      return 0;
    }
    LocalDate promised = head.get("promised_arrival_date", LocalDate.class);
    if (promised == null) {
      return 0;
    }
    LocalDate today = evaluatedAt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
    return (int) Math.max(0, ChronoUnit.DAYS.between(promised, today));
  }

  // ================================================================ 映射

  private PurchaseArrivalRow mapRow(Record r) {
    String status = r.get("effective_status", String.class);
    int overdueDays = r.get("overdue_days", Integer.class);
    BigDecimal receivable = r.get("receivable_qty", BigDecimal.class);
    BigDecimal received = r.get("received_qty", BigDecimal.class);
    BigDecimal outstanding = r.get("outstanding_qty", BigDecimal.class);
    return new PurchaseArrivalRow(
        r.get("id", UUID.class),
        r.get("tracking_no", String.class),
        r.get("purchase_order_no", String.class),
        r.get("source_doc_no", String.class),
        r.get("source_doc_type", String.class),
        r.get("supplier_ref_id", UUID.class),
        r.get("supplier_code", String.class),
        r.get("supplier_name", String.class),
        receivable,
        received,
        outstanding,
        ratio(received, receivable),
        r.get("unit_price", BigDecimal.class),
        r.get("currency", String.class),
        r.get("promised_arrival_date", LocalDate.class),
        r.get("promise_source", String.class),
        status,
        statusLabel(status),
        "OVERDUE".equals(status),
        overdueDays,
        r.get("days_until_promise", Integer.class),
        r.get("in_transit_dwell_days", Integer.class),
        r.get("in_transit_shipment_code", String.class),
        r.get("in_transit_waybill_no", String.class),
        r.get("in_transit_eta", OffsetDateTime.class),
        r.get("in_transit_synced_at", OffsetDateTime.class),
        r.get("arrival_count", Long.class),
        r.get("pending_payable_amount", BigDecimal.class),
        r.get("confirmed_payable_amount", BigDecimal.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class),
        r.get("version", Long.class));
  }

  private PurchaseArrivalReceiptRow mapReceipt(Record r) {
    String type = r.get("arrival_type", String.class);
    return new PurchaseArrivalReceiptRow(
        r.get("id", UUID.class),
        r.get("arrival_seq", Integer.class),
        type,
        "FULL".equals(type) ? "全部到货" : "部分到货",
        r.get("arrived_qty", BigDecimal.class),
        r.get("arrived_at", OffsetDateTime.class),
        r.get("recorded_at", OffsetDateTime.class),
        r.get("warehouse_receipt_code", String.class),
        r.get("warehouse_receipt_received_qty", BigDecimal.class),
        r.get("warehouse_receipt_synced_at", OffsetDateTime.class),
        r.get("waybill_no", String.class),
        r.get("note", String.class),
        r.get("recorded_by", UUID.class));
  }

  private PurchaseArrivalPayableRow mapPayable(Record r) {
    String status = r.get("confirmation_status", String.class);
    return new PurchaseArrivalPayableRow(
        r.get("id", UUID.class),
        r.get("receipt_id", UUID.class),
        r.get("confirmation_no", String.class),
        status,
        payableStatusLabel(status),
        r.get("confirmation_amount", BigDecimal.class),
        r.get("currency", String.class),
        r.get("arrived_qty", BigDecimal.class),
        r.get("unit_price", BigDecimal.class),
        r.get("basis_note", String.class),
        r.get("effective_amount", BigDecimal.class),
        r.get("finance_reference", String.class),
        r.get("confirmed_by", UUID.class),
        r.get("confirmed_at", OffsetDateTime.class),
        r.get("rejected_by", UUID.class),
        r.get("rejected_at", OffsetDateTime.class),
        r.get("reject_reason", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private PurchaseArrivalEventRow mapEvent(Record r) {
    return new PurchaseArrivalEventRow(
        r.get("id", UUID.class),
        r.get("event_type", String.class),
        eventLabel(r.get("event_type", String.class)),
        r.get("from_status", String.class),
        r.get("to_status", String.class),
        r.get("amount", BigDecimal.class),
        r.get("quantity", BigDecimal.class),
        r.get("reason_note", String.class),
        r.get("occurred_by", UUID.class),
        r.get("occurred_at", OffsetDateTime.class));
  }

  // ================================================================ 可执行动作

  private List<PurchaseArrivalAction> actionsOf(Record head) {
    UUID id = head.get("id", UUID.class);
    PurchaseArrivalStatus status = statusOf(head);
    List<PurchaseArrivalAction> actions = new ArrayList<>();
    boolean terminal = status.terminal();

    actions.add(
        action(
            "REGISTER_ARRIVAL",
            "登记到货",
            !terminal,
            "POST",
            "/trade/purchase-arrivals/" + id + "/arrivals",
            true,
            "本次到货数量；部分还是全部由 DB 按缺口判定，不用填",
            terminal ? "「" + status.label() + "」是终态，不能再登记到货" : null));
    actions.add(
        action(
            "MARK_SHIPPED",
            "标记已发货（在途）",
            status == PurchaseArrivalStatus.PENDING_SHIPMENT
                || status == PurchaseArrivalStatus.OVERDUE,
            "POST",
            "/trade/purchase-arrivals/" + id + "/ship",
            true,
            "头程发运单号 / 运单号 / 发货时刻；可同时更新承诺到货日",
            status == PurchaseArrivalStatus.PARTIALLY_RECEIVED
                ? "已部分到货的跟踪单保持「部分到货」状态；剩余部分的在途信息请用「同步在途状态」登记"
                : (terminal ? "「" + status.label() + "」是终态，不能再改发货信息" : null)));
    actions.add(
        action(
            "SYNC_IN_TRANSIT",
            "同步在途状态",
            isSet(head.get("in_transit_shipment_code", String.class)),
            "POST",
            "/trade/purchase-arrivals/" + id + "/sync-in-transit",
            false,
            "从 10.4.1.2 在途跟踪读滞留天数与 ETA 并冻结快照",
            isSet(head.get("in_transit_shipment_code", String.class))
                ? null
                : "还没登记头程发运单号（在途跟踪按发运单号定位）"));
    actions.add(
        action(
            "CANCEL",
            "取消跟踪单",
            !terminal,
            "POST",
            "/trade/purchase-arrivals/" + id + "/cancel",
            true,
            "必须写理由",
            terminal ? "「" + status.label() + "」是终态，不能取消" : null));
    return actions;
  }

  private static PurchaseArrivalAction action(
      String name,
      String label,
      boolean enabled,
      String method,
      String path,
      boolean needsBody,
      String requirement,
      String blockedReason) {
    return new PurchaseArrivalAction(
        name, label, enabled, method, path, needsBody, requirement, blockedReason);
  }

  // ================================================================ 内部工具

  private Record requireTracking(UUID id) {
    Record r =
        sql.fetchOne(
            HEAD_SELECT + " where t.tenant_id = ?::uuid and t.id = ?::uuid", currentTenant(), id);
    if (r == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "到货跟踪单不存在或不属于当前租户：" + id);
    }
    return r;
  }

  private Record requireConfirmation(UUID trackingId, UUID confirmationId) {
    Record r =
        sql.fetchOne(
            "select * from purchase_arrival_payable_confirmation"
                + " where tenant_id = ?::uuid and id = ?::uuid and tracking_id = ?::uuid",
            currentTenant(),
            confirmationId,
            trackingId);
    if (r == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "应付部分确认不存在或不属于当前租户：" + confirmationId);
    }
    return r;
  }

  private void recomputePayableTotals(UUID trackingId) {
    sql.execute(
        "update purchase_arrival_tracking t set"
            + " pending_payable_amount = (select coalesce(sum(c.confirmation_amount),0)"
            + "   from purchase_arrival_payable_confirmation c"
            + "   where c.tenant_id = t.tenant_id and c.tracking_id = t.id"
            + "     and c.confirmation_status = 'PENDING_FINANCE_CONFIRM'),"
            + " confirmed_payable_amount = (select coalesce(sum(c.effective_amount),0)"
            + "   from purchase_arrival_payable_confirmation c"
            + "   where c.tenant_id = t.tenant_id and c.tracking_id = t.id"
            + "     and c.confirmation_status = 'CONFIRMED'),"
            + " updated_at = ?::timestamptz, version = version + 1"
            + " where t.tenant_id = ?::uuid and t.id = ?::uuid",
        dbNow(),
        currentTenant(),
        trackingId);
  }

  private void appendEvent(
      UUID trackingId,
      String eventType,
      String fromStatus,
      String toStatus,
      BigDecimal amount,
      BigDecimal quantity,
      String note,
      UUID actorId,
      OffsetDateTime occurredAt) {
    sql.execute(
        "insert into purchase_arrival_event("
            + "id,tenant_id,tracking_id,event_type,from_status,to_status,amount,quantity,"
            + "reason_note,occurred_by,occurred_at,created_at)"
            + " values(?::uuid,?::uuid,?::uuid,?,?,?,?::numeric,?::numeric,?,?::uuid,?::timestamptz,now())",
        UUID.randomUUID(),
        currentTenant(),
        trackingId,
        eventType,
        fromStatus,
        toStatus,
        amount,
        quantity,
        note == null ? "" : note,
        actorId,
        occurredAt);
  }

  private void requireVersion(Record head, Long expectedVersion) {
    long current = head.get("version", Long.class);
    if (expectedVersion == null) {
      throw new IllegalArgumentException("必须给出 expectedVersion（乐观锁，不接受静默覆盖）");
    }
    if (expectedVersion != current) {
      throw new IllegalArgumentException(
          "数据已被他人修改（当前版本 " + current + "，你看到的是 " + expectedVersion + "），请刷新后重试");
    }
  }

  private UUID currentTenant() {
    return UUID.fromString(
        sql.fetchOne("select current_setting('app.current_tenant')").get(0, String.class));
  }

  /** 数据库时钟（判定时刻一律用它，不取应用机本地时钟）。 */
  private OffsetDateTime dbNow() {
    return sql.fetchOne("select now()").get(0, OffsetDateTime.class);
  }

  private static PurchaseArrivalStatus statusOf(Record head) {
    return PurchaseArrivalStatus.valueOf(head.get("arrival_status", String.class));
  }

  /** 数量：6 位小数，禁浮点。 */
  private static BigDecimal qty(BigDecimal value, String label) {
    if (value == null) {
      throw new IllegalArgumentException("必须给出" + label);
    }
    BigDecimal scaled = value.setScale(QTY_SCALE, RoundingMode.HALF_UP);
    if (scaled.signum() <= 0) {
      throw new IllegalArgumentException(label + "必须大于 0：" + value);
    }
    return scaled;
  }

  /** 金额：6 位小数，禁浮点。 */
  private static BigDecimal money(BigDecimal value, String label) {
    if (value == null) {
      throw new IllegalArgumentException("必须给出" + label);
    }
    BigDecimal scaled = value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    if (scaled.signum() < 0) {
      throw new IllegalArgumentException(label + "不能为负：" + value);
    }
    return scaled;
  }

  private static String requireCurrency(String raw) {
    if (!isSet(raw)) {
      throw new IllegalArgumentException("必须给出币种（金额一律 NUMERIC(19,6)，禁浮点）");
    }
    String currency = raw.trim().toUpperCase(java.util.Locale.ROOT);
    if (!currency.matches("^[A-Z]{3}$")) {
      throw new IllegalArgumentException("币种必须是三位大写字母：" + raw);
    }
    return currency;
  }

  private static String requireText(String raw, String label) {
    if (!isSet(raw)) {
      throw new IllegalArgumentException("必须给出" + label);
    }
    return raw.trim();
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String label) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("必须给出" + label);
    }
    try {
      return Enum.valueOf(type, raw.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          label
              + "取值非法："
              + raw
              + "（可选："
              + java.util.Arrays.toString(type.getEnumConstants())
              + "）");
    }
  }

  private static boolean isSet(String value) {
    return value != null && !value.isBlank();
  }

  private static String trimOrEmpty(String value) {
    return isSet(value) ? value.trim() : "";
  }

  private static BigDecimal ratio(BigDecimal part, BigDecimal whole) {
    if (whole == null || whole.signum() == 0) {
      return BigDecimal.ZERO.setScale(QTY_SCALE, RoundingMode.HALF_UP);
    }
    return part.divide(whole, QTY_SCALE, RoundingMode.HALF_UP);
  }

  private static String percent(BigDecimal ratio) {
    return ratio.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP).toPlainString()
        + "%";
  }

  /** 扁平 JSON 片段（值只有数字 / 布尔 / 无引号字符串，够用且不引第三方库）。 */
  private static String json(Object... kv) {
    StringBuilder sb = new StringBuilder("{");
    for (int i = 0; i + 1 < kv.length; i += 2) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append('"').append(escape(String.valueOf(kv[i]))).append("\":");
      Object v = kv[i + 1];
      if (v == null) {
        sb.append("null");
      } else if (v instanceof BigDecimal bd) {
        sb.append(bd.toPlainString());
      } else if (v instanceof Number || v instanceof Boolean) {
        sb.append(v);
      } else {
        sb.append('"').append(escape(String.valueOf(v))).append('"');
      }
    }
    return sb.append('}').toString();
  }

  private static String escape(String raw) {
    return raw.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String statusLabel(String status) {
    if (status == null) {
      return "";
    }
    try {
      return PurchaseArrivalStatus.valueOf(status).label();
    } catch (IllegalArgumentException e) {
      return status;
    }
  }

  private static String payableStatusLabel(String status) {
    if (status == null) {
      return "";
    }
    try {
      return PayableConfirmationStatus.valueOf(status).label();
    } catch (IllegalArgumentException e) {
      return status;
    }
  }

  private static String eventLabel(String code) {
    if (code == null) {
      return "";
    }
    return switch (code) {
      case "CREATED" -> "建跟踪单";
      case "SHIPPED" -> "已发货（在途）";
      case "ARRIVAL_RECORDED" -> "登记到货";
      case "FULLY_RECEIVED" -> "全部到货";
      case "OVERDUE_MARKED" -> "落逾期";
      case "IN_TRANSIT_SYNCED" -> "同步在途状态";
      case "CANCELLED" -> "取消跟踪单";
      case "PAYABLE_REGISTERED" -> "登记应付部分确认";
      case "PAYABLE_CONFIRMED" -> "财务确认应付部分确认";
      case "PAYABLE_REJECTED" -> "财务驳回应付部分确认";
      case "DISPOSITION_REFRESHED" -> "刷新处置建议";
      case "DISPOSITION_RESOLVED" -> "处理处置建议";
      default -> code;
    };
  }

  /** 来源单据类型（逻辑引用，不是 FK）。 */
  public enum SourceDocType {
    PURCHASE_ORDER,
    PURCHASE_REQUEST,
    FOLLOW_UP
  }

  private static <T> T throwIllegal(String message) {
    throw new IllegalArgumentException(message);
  }
}
