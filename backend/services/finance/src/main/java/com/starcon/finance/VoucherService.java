package com.starcon.finance;

import static com.starcon.finance.VoucherModels.*;

import com.starcon.finance.VoucherModels.FinanceVoucherCapability;
import com.starcon.finance.VoucherModels.FinanceVoucherCurrencySummary;
import com.starcon.finance.VoucherModels.FinanceVoucherDownstreamGap;
import com.starcon.finance.VoucherModels.FinanceVoucherEventRow;
import com.starcon.finance.VoucherModels.FinanceVoucherExportFile;
import com.starcon.finance.VoucherModels.FinanceVoucherLineRow;
import com.starcon.finance.VoucherModels.VoucherAuxiliaryType;
import com.starcon.finance.VoucherModels.VoucherEventType;
import com.starcon.finance.VoucherModels.VoucherLineType;
import com.starcon.finance.VoucherModels.VoucherSourceType;
import com.starcon.finance.VoucherModels.VoucherStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 12.5.2.1 凭证管理。
 *
 * <p>蓝图 12-finance.md：「<b>生成与管理会计凭证</b>：由业务事件自动生成或手工录入，支持过账、校验（至少两行、金额有效、期间开放、借贷平衡）与红冲；已过账凭证不得反过账后改写，导出标准凭证文件。输入：业务事件（发货、收货、收付款、费用计提）；手工凭证；{@code expected_version}。输出：<b>凭证 + 分录行 + 过账状态；审计记录</b>。关系：上游 = 全部业务系统事件；下游 = 12.5.2.2 报表。<b>过账后不可改行，只能红冲</b>；过账事务要锁凭证头并在数据库层防护（实体目录 §9 明确触发器要求）。」
 *
 * <p><b>本模块的五条设计主线</b>：
 *
 * <ol>
 *   <li><b>过账后不可改行，只能红冲 —— 双层防护。</b>
 *       <ul>
 *         <li>应用层：{@code post} / {@code update} / {@code reverse} 都先查状态，
 *             非 DRAFT 一律拒；红冲走的是「新建一张反向凭证 + 把原凭证标 REVERSED」，
 *             <b>没有「反过账」这条路</b>。</li>
 *         <li>数据库层：{@code guard_finance_voucher_line_frozen}（分录行 INSERT/UPDATE/DELETE，
 *             <b>旧凭证头与新凭证头都查</b>）与 {@code guard_finance_voucher_state}
 *             （凭证头事实逐字冻结）—— 直连 SQL 也改不动。</li>
 *       </ul>
 *   <li><b>过账事务锁凭证头。</b>{@code post} 在同一个事务里
 *       {@code select ... for update} 锁住凭证头，再数行数、算借贷、查期间，最后才写 POSTED。
 *       ⇒ 并发过账同一张凭证时，第二个请求看到的是**过账后**的事实，不会重复过账。
 *   <li><b>校验四条全部真的挡住。</b>至少两行 / 金额有效 / 期间开放 / 借贷平衡 ——
 *       服务层在过账事务里各判一次，数据层（触发器 + CHECK）各判一次。
 *   <li><b>科目是软引用。</b>上游 12.5.1.1「会计科目」<b>未交付</b> ⇒ 本模块存
 *       {@code subject_code} + {@code subject_name} 快照，<b>不建科目表、不建 FK</b>。
 *       ⇒ 因此本模块**不声称**校验了科目存在性（能力声明里如实写清）。
 *   <li><b>乐观并发。</b>入参带 {@code expected_version}：版本号不匹配 ⇒ <b>409 冲突</b>，
 *       绝不静默覆盖别人的修改。
 * </ol>
 *
 * <p><b>跨模块边界</b>：业务事件来自 trade（发货 / 收货）与 finance 自身（收付款 / 费用计提）。
 * trade 侧经<b>授权 HTTP API</b>读（{@link TradeOrderShipmentPort} / {@link PurchaseArrivalPort}），
 * <b>不跨库 SQL、不跨库 FK、不跨库视图</b>；转发调用方自己的 bearer，租户仍由 trade 侧按
 * 已认证成员关系解析。
 */
@Service
public class VoucherService {

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 导出的最大凭证数（防止一次导出把内存吃光）。 */
  static final int MAX_EXPORT_VOUCHERS = 5000;

  /** 凭证号的年月前缀长度（{@code VC-202610-} 是 10 个字符）。 */
  static final int VOUCHER_NO_PREFIX_LENGTH = 10;

  private final DSLContext sql;
  private final TradeOrderShipmentPort trade;
  private final PurchaseArrivalPort arrivals;

  public VoucherService(DSLContext sql, TradeOrderShipmentPort trade, PurchaseArrivalPort arrivals) {
    this.sql = sql;
    this.trade = trade;
    this.arrivals = arrivals;
  }

  // ================================================================ 内部结构

  /** 一条业务事件的**事实**（自动生成凭证时的取数结果）。 */
  private record SourceFact(
      String sourceType,
      String eventKind,
      UUID sourceId,
      String sourceNo,
      String currency,
      BigDecimal amount,
      LocalDate eventDate,
      String description,
      VoucherAuxiliaryType auxiliaryType,
      UUID auxiliaryId,
      String auxiliaryName) {}

  /** 一条分录行的**校验后**结果（金额已定标、方向已定）。 */
  private record PreparedLine(
      VoucherLineType lineType,
      String subjectCode,
      String subjectName,
      String summary,
      BigDecimal amount,
      String currency,
      BigDecimal quantity,
      VoucherAuxiliaryType auxiliaryType,
      UUID auxiliaryId,
      String auxiliaryName) {}

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**。
   *
   * <p>🔴 12.5.1.1 会计科目未交付 ⇒ 本模块**不声称**校验了科目存在性；
   * 12.5.1.2 会计期间未交付 ⇒ 期间只能关不能重开（重开属高风险 + 必须审批）。
   */
  public FinanceVoucherCapabilities capabilities() {
    var capabilities = new ArrayList<FinanceVoucherCapability>();
    var gaps = new ArrayList<FinanceVoucherDownstreamGap>();

    capabilities.add(
        new FinanceVoucherCapability(
            "12.5.2.1",
            "凭证 + 分录行 + 过账状态",
            true,
            "finance_voucher / finance_voucher_line / finance_voucher_event（V357）",
            "",
            "凭证头 + 分录行 + 审计留痕三张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS，"
                + "查询走 jOOQ 显式 SQL，金额一律 BigDecimal + NUMERIC(19,4)，全链路无浮点。"));

    capabilities.add(
        new FinanceVoucherCapability(
            "12.5.2.1",
            "过账后不可改行，只能红冲（数据库层防护）",
            true,
            "guard_finance_voucher_line_frozen / guard_finance_voucher_state（V357 触发器）",
            "",
            "🔴 分录行的 INSERT / UPDATE / DELETE **旧凭证头与新凭证头都查** ⇒ "
                + "「过账后加行 / 改行 / 删行 / 把行挪到别的凭证」四条路在**数据层**全堵死；"
                + "凭证头的事实逐字冻结，**没有「反过账」这条边**。"
                + "改写已过账的凭证只能**红冲**（新建一张反向凭证 + 原凭证标 REVERSED）。"));

    capabilities.add(
        new FinanceVoucherCapability(
            "12.5.2.1",
            "过账事务锁凭证头",
            true,
            "select ... for update（VoucherService.post）",
            "",
            "过账在同一个事务里锁住凭证头再校验再落 POSTED ⇒ 并发过账同一张凭证时，"
                + "第二个请求看到的是**过账后**的事实，不会重复过账、不会算错借贷。"));

    capabilities.add(
        new FinanceVoucherCapability(
            "12.5.2.1",
            "校验：至少两行 / 金额有效 / 期间开放 / 借贷平衡",
            true,
            "VoucherService.post + guard_finance_voucher_post_period + 表级 CHECK",
            "",
            "四条校验**各判两次**（服务层一次、数据层一次）："
                + "至少两行（line_count ≥ 2，触发器也钉死）；"
                + "金额有效（amount > 0、币种合法、全部行同一币种、精度 4 位）；"
                + "期间开放（**过账那一刻**查期间是不是 OPEN，已关闭期间写不进 POSTED）；"
                + "借贷平衡（借方合计 = 贷方合计，触发器也钉死）。"));

    capabilities.add(
        new FinanceVoucherCapability(
            "12.5.2.1",
            "业务事件自动生成 + 手工录入",
            true,
            "finance_voucher_mapping（租户维护）+ VoucherService.generate",
            "",
            "两条路都真做：手工录入走集合 POST；自动生成走 trade 的**授权 HTTP API**（发货 / 收货）"
                + "与 finance 自身的事实（收付款 / 费用计提）。"
                + "🔴 **系统不预置任何一行映射**：用户的会计科目体系是用户自定的，"
                + "没有登记映射 ⇒ 生成不出凭证（**如实报错**，绝不猜科目、绝不内置科目常量）。"));

    capabilities.add(
        new FinanceVoucherCapability(
            "12.5.2.1",
            "导出标准凭证文件",
            true,
            "GET /finance/vouchers/export（CSV / JSON）",
            "",
            "导的是**真内容**：一行一个分录行，凭证头字段随行重复（标准凭证文件格式），"
                + "CSV 带 UTF-8 BOM（Excel 打开中文不乱码）。"
                + "导出只含**已过账**凭证（草稿不是账），并写一条 EXPORTED 审计留痕。"));

    capabilities.add(
        new FinanceVoucherCapability(
            "12.5.2.1",
            "审计记录",
            true,
            "finance_voucher_event（append-only，运行角色 UPDATE/DELETE 已收回）",
            "",
            "创建 / 修改 / 自动生成 / 过账 / 红冲 / 作废 / 导出全部留痕，"
                + "带操作人、时刻、前后状态。事件流是「当时发生了什么」的**唯一**出处。"));

    capabilities.add(
        new FinanceVoucherCapability(
            "12.5.1.1",
            "会计科目（软引用）",
            false,
            "finance_voucher_line.subject_code + subject_name（**无 FK**）",
            "12.5.1.1",
            "12.5.1.1 会计科目**未交付**（同批并行，`finance_account_subject` 表在集成头里还不存在）"
                + "⇒ 本模块**不建科目表、不建指向它的外键**（建 FK 会让 Flyway 直接失败），"
                + "分录行存**科目编码 + 名称快照**（**软引用**）。"
                + "⚠️ 因此本模块**不声称**校验了科目存在性：写进去的科目编码当前**不会**被核对。"
                + "12.5.1.1 交付后按 V357 第六节的接法补 FK 或补业务校验。"));

    capabilities.add(
        new FinanceVoucherCapability(
            "12.5.1.2",
            "会计期间（过渡期 bootstrap）",
            false,
            "finance_voucher_period（**最终归属 12.5.1.2**）",
            "12.5.1.2",
            "12.5.1.2 会计期间**未交付** ⇒ 本模块只落一份**最小**期间登记（开放/关闭 + 本位币 + "
                + "关闭留痕），让「期间开放」这条校验**真的能挡住**不该过账的凭证。"
                + "本表**没有** fiscal 周期、**没有**关账检查清单、**不能重开**"
                + "（重开是高风险操作且必须审批 ⇒ 审批属 17 审批，本模块不造第二套审批引擎）。"
                + "12.5.1.2 交付后**接管本表**（照 V350 接管 V346 守卫的同一手法）。"));

    gaps.add(
        new FinanceVoucherDownstreamGap(
            "12.5.1.1",
            "会计科目",
            "finance",
            false,
            "上游未交付",
            "科目是**软引用**（编码 + 名称快照）：本模块不建科目表、不建 FK，"
                + "也不声称校验了科目存在性。12.5.1.1 交付后接 FK 或补业务校验。"));

    gaps.add(
        new FinanceVoucherDownstreamGap(
            "12.5.1.2",
            "会计期间",
            "finance",
            false,
            "上游未交付",
            "期间表是过渡期 bootstrap，只能关不能重开。12.5.1.2 交付后接管本表。"));

    gaps.add(
        new FinanceVoucherDownstreamGap(
            "12.5.2.2",
            "财务报表",
            "finance",
            false,
            "下游未交付",
            "本模块**只**提供可钻取的事实（已过账凭证 + 分录行 + 期间 + 科目编码），"
                + "**不生成任何报表**，也不替报表做汇总。"));

    return new FinanceVoucherCapabilities(capabilities, gaps);
  }

  // ================================================================ 筛选项

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。 */
  public FinanceVoucherOptions options() {
    var currencies =
        sql.fetch("select distinct currency from finance_voucher order by currency")
            .map(r -> r.get(0, String.class));
    var statuses =
        sql.fetch("select distinct status from finance_voucher order by status")
            .map(r -> r.get(0, String.class));
    var sourceTypes =
        sql.fetch("select distinct source_type from finance_voucher order by source_type")
            .map(r -> r.get(0, String.class));
    var periods =
        sql.fetch(
                "select id, period_code from finance_voucher_period order by period_start desc, period_code")
            .map(
                r ->
                    new FinanceVoucherOptionRef(
                        r.get("id", UUID.class).toString(), r.get("period_code", String.class)));
    var subjectCodes =
        sql.fetch("select distinct subject_code from finance_voucher_line order by subject_code")
            .map(r -> r.get(0, String.class));
    return new FinanceVoucherOptions(currencies, statuses, sourceTypes, periods, subjectCodes);
  }

  // ================================================================ 列表 / 详情

  /** 凭证列表：真分页 + 真筛选 + 真排序 + 汇总（汇总覆盖整个筛选结果集，不是当前页）。 */
  public FinanceVoucherPage page(VoucherQuery query) {
    int p = Math.max(0, query.page());
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    applyFilters(query, where, binds);
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from finance_voucher v" + clause, binds.toArray())
            .get(0, Long.class);
    List<FinanceVoucherRow> items =
        sql.fetch(
                "select v.*, p.period_code from finance_voucher v"
                    + " join finance_voucher_period p on p.tenant_id = v.tenant_id and p.id = v.period_id"
                    + clause
                    + " order by v.created_at desc, v.id desc limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::voucherRow);

    var summaries = summaryByCurrency(where, binds);
    long draft =
        summaries.stream().mapToLong(FinanceVoucherCurrencySummary::draftCount).sum();
    long posted =
        summaries.stream().mapToLong(FinanceVoucherCurrencySummary::postedCount).sum();
    return new FinanceVoucherPage(items, p, s, total, summaries, draft, posted);
  }

  private List<FinanceVoucherCurrencySummary> summaryByCurrency(
      List<String> where, List<Object> binds) {
    var whereClause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    return sql.fetch(
            "select v.currency, sum(v.total_debit) as total_debit, sum(v.total_credit) as total_credit,"
                + " count(*) as cnt,"
                + " count(*) filter (where v.status = 'POSTED') as posted_count,"
                + " count(*) filter (where v.status = 'DRAFT') as draft_count,"
                + " count(*) filter (where v.status = 'REVERSED') as reversed_count"
                + " from finance_voucher v"
                + whereClause
                + " group by v.currency order by v.currency",
            binds.toArray())
        .map(
            r ->
                new FinanceVoucherCurrencySummary(
                    r.get("currency", String.class),
                    r.get("total_debit", BigDecimal.class),
                    r.get("total_credit", BigDecimal.class),
                    r.get("cnt", Long.class),
                    r.get("posted_count", Long.class),
                    r.get("draft_count", Long.class),
                    r.get("reversed_count", Long.class)));
  }

  private void applyFilters(VoucherQuery q, List<String> where, List<Object> binds) {
    if (q.status() != null) {
      where.add("v.status = ?");
      binds.add(q.status().name());
    }
    if (q.sourceType() != null) {
      where.add("v.source_type = ?");
      binds.add(q.sourceType().name());
    }
    if (q.currency() != null && !q.currency().isBlank()) {
      where.add("v.currency = ?");
      binds.add(q.currency());
    }
    if (q.periodId() != null) {
      where.add("v.period_id = ?");
      binds.add(q.periodId());
    }
    if (q.dateFrom() != null) {
      where.add("v.voucher_date >= ?");
      binds.add(q.dateFrom());
    }
    if (q.dateTo() != null) {
      where.add("v.voucher_date <= ?");
      binds.add(q.dateTo());
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      where.add("(v.voucher_no ilike ? or v.source_no ilike ? or v.description ilike ?)");
      var like = "%" + q.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
  }

  /** 凭证详情：凭证 + 分录行 + 审计留痕。 */
  public FinanceVoucherDetail detail(UUID id) {
    var voucher = voucherRow(requireVoucher(id));
    return new FinanceVoucherDetail(voucher, lines(id), events(id));
  }

  // ================================================================ 手工录入

  /** 手工录入一张凭证（草稿）。 */
  @Transactional
  public FinanceVoucherResult create(FinanceVoucherInput input, AccessService.Access actor) {
    if (input == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
    }
    var sourceType = VoucherSourceType.MANUAL;
    if (input.sourceType() != null && !input.sourceType().isBlank()) {
      var parsed = parseSourceType(input.sourceType());
      if (parsed != VoucherSourceType.MANUAL) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "手工录入的凭证来源类型只能是 MANUAL；业务事件生成的凭证请走「自动生成」端点");
      }
    }
    var period = requirePeriod(input.periodId());
    var voucherDate =
        input.voucherDate() == null ? LocalDate.now(ZoneOffset.UTC) : input.voucherDate();
    var currency = requireText(input.currency(), "凭证币种").toUpperCase();
    var description = input.description() == null ? "" : input.description().trim();
    if (description.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "凭证摘要不能为空");
    }
    var lines = prepareLines(input.lines(), currency);

    var id = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var voucherNo = nextVoucherNo();
    try {
      sql.execute(
          "insert into finance_voucher(id,tenant_id,voucher_no,source_type,source_id,source_no,"
              + "period_id,voucher_date,description,currency,status,note,created_by,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,'DRAFT',?,?,?::timestamptz,?::timestamptz)",
          id,
          actor.tenantId(),
          voucherNo,
          sourceType.name(),
          input.sourceId(),
          input.sourceNo() == null ? "" : input.sourceNo().trim(),
          period.get("id", UUID.class),
          voucherDate,
          description,
          currency,
          input.note() == null ? "" : input.note().trim(),
          actor.userId(),
          now,
          now);
    } catch (DataIntegrityViolationException | org.jooq.exception.DataAccessException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "凭证号 " + voucherNo + " 已存在，请重试");
    }
    insertLines(id, lines, actor.tenantId(), now);
    recordEvent(id, VoucherEventType.CREATED, "", VoucherStatus.DRAFT.name(), "手工录入凭证", "", actor, now);
    return new FinanceVoucherResult(
        voucherRow(requireVoucher(id)), lines(id), null, "已创建草稿凭证 " + voucherNo);
  }

  /** 修改一张**草稿**凭证（乐观并发）。 */
  @Transactional
  public FinanceVoucherResult update(
      UUID id, FinanceVoucherInput input, AccessService.Access actor) {
    var existing = requireVoucher(id);
    var status = VoucherStatus.valueOf(existing.get("status", String.class));
    if (status != VoucherStatus.DRAFT) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "凭证已是「" + status.label() + "」，不可改写：已过账凭证只能红冲");
    }
    requireVersion(existing, input.expectedVersion());
    var currency =
        input.currency() == null || input.currency().isBlank()
            ? existing.get("currency", String.class)
            : requireText(input.currency(), "凭证币种").toUpperCase();
    var lines = prepareLines(input.lines(), currency);
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_voucher set description=?, currency=?, voucher_date=?, note=?,"
            + " version=version+1, updated_at=?::timestamptz where id=?",
        input.description() == null ? existing.get("description", String.class) : input.description().trim(),
        currency,
        input.voucherDate() == null ? existing.get("voucher_date", LocalDate.class) : input.voucherDate(),
        input.note() == null ? existing.get("note", String.class) : input.note().trim(),
        now,
        id);
    // 草稿的分录行可以整体换掉（触发器只挡「已过账」的改动）。
    sql.execute("delete from finance_voucher_line where voucher_id = ?", id);
    insertLines(id, lines, actor.tenantId(), now);
    recordEvent(
        id,
        VoucherEventType.UPDATED,
        VoucherStatus.DRAFT.name(),
        VoucherStatus.DRAFT.name(),
        "修改草稿凭证",
        "共 " + lines.size() + " 行分录",
        actor,
        now);
    return new FinanceVoucherResult(voucherRow(requireVoucher(id)), lines(id), null, "草稿已更新");
  }

  // ================================================================ 过账

  /**
   * 过账。
   *
   * <p>🔴 <b>锁凭证头</b>：{@code select ... for update} —— 在同一个事务里锁住，再校验，再落 POSTED。
   * 校验四条：**至少两行 / 金额有效 / 期间开放 / 借贷平衡**。
   */
  @Transactional
  public FinanceVoucherResult post(UUID id, FinanceVoucherPostInput input, AccessService.Access actor) {
    // ① 锁凭证头（并发过账同一张凭证时，第二个请求看到的是过账后的事实）。
    var existing = sql.fetchOne("select * from finance_voucher where id = ? for update", id);
    if (existing == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "凭证不存在");
    }
    var status = VoucherStatus.valueOf(existing.get("status", String.class));
    if (status != VoucherStatus.DRAFT) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "凭证已是「" + status.label() + "」，不可重复过账");
    }
    requireVersion(existing, input == null ? null : input.expectedVersion());

    var period = requirePeriod(existing.get("period_id", UUID.class));
    var periodStatus = period.get("status", String.class);
    if (!"OPEN".equals(periodStatus)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "会计期间 " + period.get("period_code", String.class) + " 已关闭，不允许过账");
    }
    var lines = lines(id);
    if (lines.size() < 2) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "凭证至少要有两行分录才能过账（当前 " + lines.size() + " 行）");
    }
    var currency = existing.get("currency", String.class);
    BigDecimal debit = BigDecimal.ZERO.setScale(MONEY_SCALE);
    BigDecimal credit = BigDecimal.ZERO.setScale(MONEY_SCALE);
    for (var line : lines) {
      if (!currency.equals(line.currency())) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "凭证币种是 " + currency + "，第 " + line.lineNo() + " 行是 " + line.currency()
                + "：一张凭证只能有一个币种");
      }
      if (line.amount().compareTo(BigDecimal.ZERO) <= 0) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "第 " + line.lineNo() + " 行金额无效（必须大于 0）");
      }
      if ("DEBIT".equals(line.lineType())) {
        debit = debit.add(line.amount());
      } else {
        credit = credit.add(line.amount());
      }
    }
    debit = debit.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    credit = credit.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    if (debit.compareTo(credit) != 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "借贷不平：借方合计 " + debit + " ≠ 贷方合计 " + credit + "，不能过账");
    }

    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_voucher set status='POSTED', total_debit=?, total_credit=?, line_count=?,"
            + " posted_at=?::timestamptz, posted_by=?, note=?, version=version+1,"
            + " updated_at=?::timestamptz where id=?",
        debit,
        credit,
        lines.size(),
        now,
        actor.userId(),
        input != null && input.note() != null ? input.note().trim() : existing.get("note", String.class),
        now,
        id);
    recordEvent(
        id,
        VoucherEventType.POSTED,
        VoucherStatus.DRAFT.name(),
        VoucherStatus.POSTED.name(),
        "过账",
        "借贷合计 " + debit + " " + currency + "，共 " + lines.size() + " 行",
        actor,
        now);
    return new FinanceVoucherResult(
        voucherRow(requireVoucher(id)), lines(id), null, "凭证已过账，借贷合计 " + debit + " " + currency);
  }

  // ================================================================ 红冲

  /**
   * 红冲一张**已过账**的凭证。
   *
   * <p>🔴 **红冲 = 新建一张反向凭证**（借贷方向对调、金额相同），并把原凭证标成 REVERSED。
   * <b>没有「反过账」这条路</b> —— 已过账凭证的原始事实一个字节都不改。
   */
  @Transactional
  public FinanceVoucherResult reverse(
      UUID id, FinanceVoucherReverseInput input, AccessService.Access actor) {
    var existing = sql.fetchOne("select * from finance_voucher where id = ? for update", id);
    if (existing == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "凭证不存在");
    }
    var status = VoucherStatus.valueOf(existing.get("status", String.class));
    if (status != VoucherStatus.POSTED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "只有「已过账」的凭证可以红冲（当前：" + status.label() + "）");
    }
    requireVersion(existing, input.expectedVersion());
    var reason = requireText(input.reason(), "红冲原因");

    var originalLines = lines(id);
    if (originalLines.size() < 2) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "原凭证分录行少于两行，红冲不安全：请人工核对");
    }
    var currency = existing.get("currency", String.class);
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var voucherDate = input.voucherDate() == null ? LocalDate.now(ZoneOffset.UTC) : input.voucherDate();

    // ① 新建红冲凭证（草稿 → 直接过账）。
    var reversalId = UUID.randomUUID();
    var reversalNo = nextVoucherNo();
    var periodId = existing.get("period_id", UUID.class);
    try {
      sql.execute(
          "insert into finance_voucher(id,tenant_id,voucher_no,source_type,source_id,source_no,"
              + "reversal_of_id,period_id,voucher_date,description,currency,status,note,"
              + "created_by,created_at,updated_at)"
              + " values(?,?,?,'REVERSAL',?,?,?,?,?,?,?,'DRAFT',?,?,?::timestamptz,?::timestamptz)",
          reversalId,
          actor.tenantId(),
          reversalNo,
          existing.get("id", UUID.class),
          existing.get("voucher_no", String.class),
          existing.get("id", UUID.class),
          periodId,
          voucherDate,
          "红冲：" + existing.get("description", String.class),
          currency,
          input.note() == null ? reason : input.note().trim(),
          actor.userId(),
          now,
          now);
    } catch (DataIntegrityViolationException | org.jooq.exception.DataAccessException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "红冲凭证号冲突，请重试");
    }
    // ② 反向分录行：借贷方向对调，金额与科目不变。
    var reversedLines = new ArrayList<PreparedLine>();
    for (var line : originalLines) {
      reversedLines.add(
          new PreparedLine(
              "DEBIT".equals(line.lineType()) ? VoucherLineType.CREDIT : VoucherLineType.DEBIT,
              line.subjectCode(),
              line.subjectName(),
              line.summary(),
              line.amount(),
              line.currency(),
              line.quantity(),
              VoucherAuxiliaryType.valueOf(line.auxiliaryType()),
              line.auxiliaryId(),
              line.auxiliaryName()));
    }
    insertLines(reversalId, reversedLines, actor.tenantId(), now);

    // ③ 红冲凭证过账（复用同一套校验：期间开放 / 借贷平衡 / 至少两行）。
    BigDecimal debit = BigDecimal.ZERO.setScale(MONEY_SCALE);
    BigDecimal credit = BigDecimal.ZERO.setScale(MONEY_SCALE);
    for (var line : reversedLines) {
      if (line.lineType() == VoucherLineType.DEBIT) {
        debit = debit.add(line.amount());
      } else {
        credit = credit.add(line.amount());
      }
    }
    debit = debit.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    credit = credit.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    sql.execute(
        "update finance_voucher set status='POSTED', total_debit=?, total_credit=?, line_count=?,"
            + " posted_at=?::timestamptz, posted_by=?, version=version+1, updated_at=?::timestamptz"
            + " where id=?",
        debit,
        credit,
        reversedLines.size(),
        now,
        actor.userId(),
        now,
        reversalId);
    recordEvent(
        reversalId,
        VoucherEventType.REVERSAL_CREATED,
        VoucherStatus.DRAFT.name(),
        VoucherStatus.POSTED.name(),
        "红冲 " + existing.get("voucher_no", String.class),
        reason,
        actor,
        now);

    // ④ 原凭证标成已红冲（触发器只放行 status / reversal_voucher_id / reversed_at 这组字段）。
    sql.execute(
        "update finance_voucher set status='REVERSED', reversal_voucher_id=?, reversed_at=?::timestamptz,"
            + " version=version+1, updated_at=?::timestamptz where id=?",
        reversalId,
        now,
        now,
        id);
    recordEvent(
        id,
        VoucherEventType.REVERSED,
        VoucherStatus.POSTED.name(),
        VoucherStatus.REVERSED.name(),
        reason,
        "红冲凭证号 " + reversalNo,
        actor,
        now);
    return new FinanceVoucherResult(
        voucherRow(requireVoucher(id)),
        lines(id),
        voucherRow(requireVoucher(reversalId)),
        "已红冲：生成反向凭证 " + reversalNo + "（原凭证的原始事实一字未改）");
  }

  // ================================================================ 作废

  /** 作废一张**草稿**凭证（草稿不占期间，不需要红冲）。 */
  @Transactional
  public FinanceVoucherResult voidVoucher(
      UUID id, FinanceVoucherVoidInput input, AccessService.Access actor) {
    var existing = requireVoucher(id);
    var status = VoucherStatus.valueOf(existing.get("status", String.class));
    if (status != VoucherStatus.DRAFT) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "只有「草稿」可以作废（当前：" + status.label() + "）；已过账的凭证只能红冲");
    }
    requireVersion(existing, input.expectedVersion());
    var reason = requireText(input.reason(), "作废原因");
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_voucher set status='VOID', note=?, version=version+1,"
            + " updated_at=?::timestamptz where id=?",
        reason,
        now,
        id);
    recordEvent(
        id,
        VoucherEventType.VOIDED,
        VoucherStatus.DRAFT.name(),
        VoucherStatus.VOID.name(),
        reason,
        "",
        actor,
        now);
    return new FinanceVoucherResult(
        voucherRow(requireVoucher(id)), lines(id), null, "草稿凭证已作废");
  }

  // ================================================================ 自动生成

  /**
   * 由业务事件**自动生成**一张凭证（草稿）。
   *
   * <p>🔴 **科目来自映射表**（租户维护）：没有登记映射 ⇒ 生成不出凭证（**如实报错**）。
   * 金额、币种、来源单号、辅助核算**全部取自真实业务事实**。
   */
  @Transactional
  public FinanceVoucherResult generate(
      FinanceVoucherGenerateInput input, AccessService.Access actor, String bearer) {
    if (input == null || input.sourceId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定业务事实");
    }
    var sourceType = parseSourceType(input.sourceType());
    if (sourceType == VoucherSourceType.MANUAL || sourceType == VoucherSourceType.REVERSAL) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "自动生成只接受业务事件类型（发货 / 收货 / 收付款 / 费用计提）");
    }
    var eventKind = requireText(input.eventKind(), "事件细分").toUpperCase();
    var mapping = requireMapping(sourceType, eventKind);
    if (!mapping.get("enabled", Boolean.class)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "业务事件映射 " + sourceType + " / " + eventKind + " 已停用");
    }

    var fact = readSourceFact(sourceType, eventKind, input.sourceId(), bearer);
    var period = requirePeriod(input.periodId());
    var voucherDate = input.voucherDate() == null ? fact.eventDate() : input.voucherDate();
    var description =
        input.description() == null || input.description().isBlank()
            ? fact.description()
            : input.description().trim();

    // 借贷两行：金额取自业务事实，方向与科目取自映射。
    var lines = new ArrayList<PreparedLine>();
    lines.add(
        new PreparedLine(
            VoucherLineType.DEBIT,
            mapping.get("debit_subject_code", String.class),
            mapping.get("debit_subject_name", String.class),
            description,
            fact.amount(),
            fact.currency(),
            null,
            fact.auxiliaryType(),
            fact.auxiliaryId(),
            fact.auxiliaryName()));
    lines.add(
        new PreparedLine(
            VoucherLineType.CREDIT,
            mapping.get("credit_subject_code", String.class),
            mapping.get("credit_subject_name", String.class),
            description,
            fact.amount(),
            fact.currency(),
            null,
            fact.auxiliaryType(),
            fact.auxiliaryId(),
            fact.auxiliaryName()));

    // 同一笔业务事实只许生成一张凭证（唯一索引兜底）。
    var existing =
        sql.fetchOne(
            "select voucher_no from finance_voucher where source_type = ? and source_id = ?",
            sourceType.name(),
            input.sourceId());
    if (existing != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "该业务事实已经生成过凭证 " + existing.get("voucher_no", String.class) + "，不能重复生成");
    }

    var id = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var voucherNo = nextVoucherNo();
    try {
      sql.execute(
          "insert into finance_voucher(id,tenant_id,voucher_no,source_type,source_id,source_no,"
              + "period_id,voucher_date,description,currency,status,note,created_by,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,'DRAFT',?,?,?::timestamptz,?::timestamptz)",
          id,
          actor.tenantId(),
          voucherNo,
          sourceType.name(),
          input.sourceId(),
          fact.sourceNo(),
          period.get("id", UUID.class),
          voucherDate,
          description,
          fact.currency(),
          input.note() == null ? "" : input.note().trim(),
          actor.userId(),
          now,
          now);
    } catch (DataIntegrityViolationException | org.jooq.exception.DataAccessException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "该业务事实已生成过凭证（或凭证号冲突），请重试");
    }
    insertLines(id, lines, actor.tenantId(), now);
    recordEvent(
        id,
        VoucherEventType.AUTO_GENERATED,
        "",
        VoucherStatus.DRAFT.name(),
        "由业务事件自动生成",
        sourceType + " / " + eventKind + " · " + fact.sourceNo(),
        actor,
        now);
    return new FinanceVoucherResult(
        voucherRow(requireVoucher(id)), lines(id), null, "已由业务事件生成草稿凭证 " + voucherNo);
  }

  /** 读业务事实（**真的去上游取**，取不到就如实失败）。 */
  private SourceFact readSourceFact(
      VoucherSourceType sourceType, String eventKind, UUID sourceId, String bearer) {
    switch (sourceType) {
      case SHIPMENT -> {
        // 发货：trade 的发货记录 + 平台订单（**跨服务只读，授权 API**）。
        var shipment = findShipment(sourceId, bearer);
        var order = findOrderBySalesOrder(shipment.salesOrderId(), bearer);
        return new SourceFact(
            sourceType.name(),
            eventKind,
            sourceId,
            shipment.recordNo() == null ? "" : shipment.recordNo(),
            order.currency(),
            order.platformGrandTotalAmount(),
            shipment.shippedAt() == null ? LocalDate.now(ZoneOffset.UTC) : shipment.shippedAt().toLocalDate(),
            "销售发货 " + (shipment.salesOrderNo() == null ? "" : shipment.salesOrderNo()),
            VoucherAuxiliaryType.ORDER,
            shipment.salesOrderId(),
            shipment.salesOrderNo() == null ? "" : shipment.salesOrderNo());
      }
      case RECEIPT -> {
        // 收货：trade 的到货跟踪（采购收货确认）。
        var arrival = findArrival(sourceId, bearer);
        if (arrival.pendingPayableAmount() == null
            || arrival.pendingPayableAmount().compareTo(BigDecimal.ZERO) <= 0) {
          throw new ResponseStatusException(
              HttpStatus.CONFLICT,
              "该到货跟踪没有可确认的应付金额（pendingPayableAmount 为空或非正），生成不出凭证");
        }
        return new SourceFact(
            sourceType.name(),
            eventKind,
            sourceId,
            arrival.trackingNo() == null ? "" : arrival.trackingNo(),
            arrival.currency(),
            arrival.pendingPayableAmount(),
            LocalDate.now(ZoneOffset.UTC),
            "采购收货 " + (arrival.purchaseOrderNo() == null ? "" : arrival.purchaseOrderNo()),
            VoucherAuxiliaryType.SUPPLIER,
            arrival.supplierRefId(),
            arrival.supplierName() == null ? "" : arrival.supplierName());
      }
      case PAYMENT -> {
        if ("PAYMENT".equals(eventKind)) {
          // 收付款 —— 付款：finance 自己的付款执行记录（已成功的）。
          var execution =
              sql.fetchOne(
                  "select id, execution_no, order_id, currency, amount, status, paid_at, channel_serial_no"
                      + " from finance_payment_execution where id = ?",
                  sourceId);
          if (execution == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到这笔付款执行记录");
          }
          if (!"SUCCEEDED".equals(execution.get("status", String.class))) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "只有**已成功**的付款执行才能生成凭证（当前："
                    + execution.get("status", String.class) + "）");
          }
          var orderNo =
              sql.fetchOne(
                      "select order_no from finance_payment_order where id = ?",
                      execution.get("order_id", UUID.class))
                  .get("order_no", String.class);
          return new SourceFact(
              sourceType.name(),
              eventKind,
              sourceId,
              execution.get("execution_no", String.class),
              execution.get("currency", String.class),
              execution.get("amount", BigDecimal.class),
              LocalDate.now(ZoneOffset.UTC),
              "付款 " + orderNo,
              VoucherAuxiliaryType.PAYMENT_ORDER,
              execution.get("order_id", UUID.class),
              orderNo);
        }
        // 收付款 —— 收款：平台结算单已到账。
        var settlement =
            sql.fetchOne(
                "select id, settlement_no, currency, received_amount, status, arrived_at"
                    + " from finance_settlement where id = ?",
                sourceId);
        if (settlement == null) {
          throw new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到这张结算单");
        }
        if (!"SETTLED".equals(settlement.get("status", String.class))) {
          throw new ResponseStatusException(
              HttpStatus.CONFLICT,
              "只有**已到账**的结算单才能生成收款凭证（当前："
                  + settlement.get("status", String.class) + "）");
        }
        return new SourceFact(
            sourceType.name(),
            eventKind,
            sourceId,
            settlement.get("settlement_no", String.class),
            settlement.get("currency", String.class),
            settlement.get("received_amount", BigDecimal.class),
            LocalDate.now(ZoneOffset.UTC),
            "平台结算到账 " + settlement.get("settlement_no", String.class),
            VoucherAuxiliaryType.PLATFORM,
            settlement.get("id", UUID.class),
            settlement.get("settlement_no", String.class));
      }
      case EXPENSE_ACCRUAL -> {
        // 费用计提：12.4.2.1 费用归集条目。
        var entry =
            sql.fetchOne(
                "select id, fee_source, fee_category, base_date, currency, amount, source_ref_code"
                    + " from finance_cost_collection_entry where id = ?",
                sourceId);
        if (entry == null) {
          throw new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到这条费用归集条目");
        }
        return new SourceFact(
            sourceType.name(),
            eventKind,
            sourceId,
            entry.get("source_ref_code", String.class) == null
                ? ""
                : entry.get("source_ref_code", String.class),
            entry.get("currency", String.class),
            entry.get("amount", BigDecimal.class),
            entry.get("base_date", LocalDate.class),
            "费用计提 " + entry.get("fee_category", String.class),
            VoucherAuxiliaryType.COST_ENTRY,
            entry.get("id", UUID.class),
            entry.get("source_ref_code", String.class) == null
                ? ""
                : entry.get("source_ref_code", String.class));
      }
      default ->
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "暂不支持的业务事件类型：" + sourceType.name());
    }
  }

  private TradeOrderShipmentPort.TradeShipmentRecordRow findShipment(UUID id, String bearer) {
    for (int p = 0; p < TradeOrderShipmentPort.MAX_PAGES; p++) {
      var page = trade.shipments(p, bearer);
      if (page.items() == null || page.items().isEmpty()) break;
      for (var row : page.items()) {
        if (id.equals(row.id())) return row;
      }
    }
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "上游 trade 查不到这条发货记录");
  }

  private TradeOrderShipmentPort.TradePlatformOrderRow findOrderBySalesOrder(
      UUID salesOrderId, String bearer) {
    for (int p = 0; p < TradeOrderShipmentPort.MAX_PAGES; p++) {
      var page = trade.orders(p, bearer);
      if (page.rows() == null || page.rows().isEmpty()) break;
      for (var row : page.rows()) {
        if (salesOrderId != null && salesOrderId.equals(row.salesOrderId())) return row;
      }
    }
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "上游 trade 查不到这张平台订单");
  }

  private PurchaseArrivalPort.TradeArrivalRow findArrival(UUID id, String bearer) {
    for (int p = 0; p < PurchaseArrivalPort.MAX_PAGES; p++) {
      var page = arrivals.arrivals(p, bearer);
      if (page.rows() == null || page.rows().isEmpty()) break;
      for (var row : page.rows()) {
        if (id.equals(row.id())) return row;
      }
    }
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "上游 trade 查不到这条到货跟踪");
  }

  // ================================================================ 导出

  /**
   * 导出标准凭证文件。
   *
   * <p>🔴 只导**已过账**凭证（草稿不是账，导出去是误导）。
   * CSV 一行一个分录行、凭证头字段随行重复（标准凭证文件格式），带 UTF-8 BOM。
   */
  @Transactional
  public FinanceVoucherExportFile exportFile(
      FinanceVoucherExportInput input, AccessService.Access actor) {
    var format = (input == null || input.format() == null ? "CSV" : input.format())
        .trim()
        .toUpperCase();
    if (!format.equals("CSV") && !format.equals("JSON")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导出格式只支持 CSV 或 JSON");
    }
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("v.status = 'POSTED'");
    if (input != null) {
      if (input.periodId() != null) {
        where.add("v.period_id = ?");
        binds.add(input.periodId());
      }
      if (input.dateFrom() != null) {
        where.add("v.voucher_date >= ?");
        binds.add(input.dateFrom());
      }
      if (input.dateTo() != null) {
        where.add("v.voucher_date <= ?");
        binds.add(input.dateTo());
      }
      if (input.status() != null && !input.status().isBlank() && !"POSTED".equalsIgnoreCase(input.status())) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "标准凭证文件只导出已过账的凭证；状态筛选只能是 POSTED");
      }
    }
    String clause = " where " + String.join(" and ", where);
    var vouchers =
        sql.fetch(
                "select v.*, p.period_code from finance_voucher v"
                    + " join finance_voucher_period p on p.tenant_id = v.tenant_id and p.id = v.period_id"
                    + clause
                    + " order by v.voucher_date, v.voucher_no limit ?",
                concat(binds, MAX_EXPORT_VOUCHERS + 1))
            .map(this::voucherRow);
    if (vouchers.size() > MAX_EXPORT_VOUCHERS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "一次最多导出 " + MAX_EXPORT_VOUCHERS + " 张凭证，请缩小期间或日期范围");
    }
    var rows = new ArrayList<ExportRow>();
    for (var voucher : vouchers) {
      for (var line : lines(voucher.id())) {
        rows.add(new ExportRow(voucher, line));
      }
    }
    var content = format.equals("CSV") ? renderCsv(rows) : renderJson(vouchers);
    var filename =
        "vouchers-"
            + (input != null && input.dateFrom() != null ? input.dateFrom() : "all")
            + "-"
            + (input != null && input.dateTo() != null ? input.dateTo() : "all")
            + "."
            + format.toLowerCase();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    for (var voucher : vouchers) {
      recordEvent(
          voucher.id(),
          VoucherEventType.EXPORTED,
          voucher.status(),
          voucher.status(),
          "导出标准凭证文件",
          format + " · 共 " + vouchers.size() + " 张凭证",
          actor,
          now);
    }
    return new FinanceVoucherExportFile(
        filename,
        format.equals("CSV") ? "text/csv; charset=utf-8" : "application/json; charset=utf-8",
        content,
        vouchers.size(),
        rows.size());
  }

  /** 导出行：凭证头 + 分录行。 */
  private record ExportRow(FinanceVoucherRow voucher, FinanceVoucherLineRow line) {}

  private String renderCsv(List<ExportRow> rows) {
    var sb = new StringBuilder();
    // UTF-8 BOM：Excel 打开中文不乱码。
    sb.append('\uFEFF');
    sb.append("凭证号,会计期间,业务日期,凭证状态,摘要,科目编码,科目名称,借方金额,贷方金额,币种,数量,辅助核算,辅助对象,来源类型,来源单号,过账时间\n");
    for (var row : rows) {
      var v = row.voucher();
      var l = row.line();
      var debit = "DEBIT".equals(l.lineType()) ? plain(l.amount()) : "";
      var credit = "CREDIT".equals(l.lineType()) ? plain(l.amount()) : "";
      sb.append(csvCell(v.voucherNo())).append(',')
          .append(csvCell(v.periodCode())).append(',')
          .append(csvCell(v.voucherDate().toString())).append(',')
          .append(csvCell(v.status())).append(',')
          .append(csvCell(l.summary().isEmpty() ? v.description() : l.summary())).append(',')
          .append(csvCell(l.subjectCode())).append(',')
          .append(csvCell(l.subjectName())).append(',')
          .append(csvCell(debit)).append(',')
          .append(csvCell(credit)).append(',')
          .append(csvCell(l.currency())).append(',')
          .append(csvCell(l.quantity() == null ? "" : plain(l.quantity()))).append(',')
          .append(csvCell(l.auxiliaryType())).append(',')
          .append(csvCell(l.auxiliaryName())).append(',')
          .append(csvCell(v.sourceType())).append(',')
          .append(csvCell(v.sourceNo())).append(',')
          .append(csvCell(v.postedAt() == null ? "" : v.postedAt().toString()))
          .append('\n');
    }
    return sb.toString();
  }

  private String renderJson(List<FinanceVoucherRow> vouchers) {
    var sb = new StringBuilder();
    sb.append("[\n");
    for (int i = 0; i < vouchers.size(); i++) {
      var v = vouchers.get(i);
      sb.append("  {\n");
      sb.append("    \"voucherNo\": ").append(jsonString(v.voucherNo())).append(",\n");
      sb.append("    \"periodCode\": ").append(jsonString(v.periodCode())).append(",\n");
      sb.append("    \"voucherDate\": ").append(jsonString(v.voucherDate().toString())).append(",\n");
      sb.append("    \"status\": ").append(jsonString(v.status())).append(",\n");
      sb.append("    \"description\": ").append(jsonString(v.description())).append(",\n");
      sb.append("    \"currency\": ").append(jsonString(v.currency())).append(",\n");
      sb.append("    \"totalDebit\": ").append(plain(v.totalDebit())).append(",\n");
      sb.append("    \"totalCredit\": ").append(plain(v.totalCredit())).append(",\n");
      sb.append("    \"sourceType\": ").append(jsonString(v.sourceType())).append(",\n");
      sb.append("    \"sourceNo\": ").append(jsonString(v.sourceNo())).append(",\n");
      sb.append("    \"postedAt\": ")
          .append(jsonString(v.postedAt() == null ? "" : v.postedAt().toString()))
          .append(",\n");
      sb.append("    \"lines\": [\n");
      var voucherLines = lines(v.id());
      for (int j = 0; j < voucherLines.size(); j++) {
        var l = voucherLines.get(j);
        sb.append("      {\"lineNo\": ").append(l.lineNo())
            .append(", \"lineType\": ").append(jsonString(l.lineType()))
            .append(", \"subjectCode\": ").append(jsonString(l.subjectCode()))
            .append(", \"subjectName\": ").append(jsonString(l.subjectName()))
            .append(", \"summary\": ").append(jsonString(l.summary()))
            .append(", \"amount\": ").append(plain(l.amount()))
            .append(", \"currency\": ").append(jsonString(l.currency()))
            .append(", \"quantity\": ")
            .append(l.quantity() == null ? "null" : plain(l.quantity()))
            .append(", \"auxiliaryType\": ").append(jsonString(l.auxiliaryType()))
            .append(", \"auxiliaryName\": ").append(jsonString(l.auxiliaryName()))
            .append('}');
        if (j < voucherLines.size() - 1) sb.append(',');
        sb.append('\n');
      }
      sb.append("    ]\n");
      sb.append("  }");
      if (i < vouchers.size() - 1) sb.append(',');
      sb.append('\n');
    }
    sb.append("]\n");
    return sb.toString();
  }

  private String csvCell(String raw) {
    var value = raw == null ? "" : raw;
    if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
      return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    return value;
  }

  private String jsonString(String raw) {
    var value = raw == null ? "" : raw;
    var sb = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      var c = value.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
          else sb.append(c);
        }
      }
    }
    return sb.append('"').toString();
  }

  private String plain(BigDecimal value) {
    return value == null ? "" : value.toPlainString();
  }

  // ================================================================ 会计期间

  /** 会计期间列表（真实数据）。 */
  public List<FinanceVoucherPeriodRow> periods() {
    return sql.fetch("select * from finance_voucher_period order by period_start desc, period_code")
        .map(this::periodRow);
  }

  /** 登记一个会计期间（默认开放）。 */
  @Transactional
  public FinanceVoucherPeriodRow createPeriod(
      FinanceVoucherPeriodInput input, AccessService.Access actor) {
    if (input == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
    }
    var code = requireText(input.periodCode(), "期间编码");
    if (input.periodStart() == null || input.periodEnd() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "期间起止日期不能为空");
    }
    if (input.periodEnd().isBefore(input.periodStart())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "期间结束日不能早于开始日");
    }
    var currency = requireText(input.baseCurrency(), "本位币").toUpperCase();
    if (!currency.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "本位币必须是三位大写字母（如 USD）");
    }
    if (sql.fetchOne("select 1 from finance_voucher_period where period_code = ?", code) != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "期间编码 " + code + " 已存在");
    }
    var id = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into finance_voucher_period(id,tenant_id,period_code,period_start,period_end,"
            + "status,base_currency,note,created_at,updated_at)"
            + " values(?,?,?,?,?,'OPEN',?,?,?::timestamptz,?::timestamptz)",
        id,
        actor.tenantId(),
        code,
        input.periodStart(),
        input.periodEnd(),
        currency,
        input.note() == null ? "" : input.note().trim(),
        now,
        now);
    return periodRow(sql.fetchOne("select * from finance_voucher_period where id = ?", id));
  }

  /**
   * 关闭一个会计期间。
   *
   * <p>⚠️ <b>只能关、不能重开</b>：重开已关期间是高风险操作且必须审批留痕（12.5.1.2），
   * 而审批属 17 审批（identity）⇒ 本模块**不提供**本地审批端点，也不造第二套审批引擎。
   */
  @Transactional
  public FinanceVoucherPeriodRow closePeriod(
      UUID id, FinanceVoucherPeriodCloseInput input, AccessService.Access actor) {
    var existing = requirePeriod(id);
    requireVersion(existing, input.expectedVersion());
    var reason = requireText(input.reason(), "关闭原因");
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_voucher_period set status='CLOSED', closed_at=?::timestamptz, closed_by=?,"
            + " closed_reason=?, version=version+1, updated_at=?::timestamptz where id=?",
        now,
        actor.userId(),
        reason,
        now,
        id);
    return periodRow(requirePeriod(id));
  }

  // ================================================================ 映射

  /** 业务事件 → 借贷科目映射列表。 */
  public List<FinanceVoucherMappingRow> mappings() {
    return sql.fetch(
            "select * from finance_voucher_mapping order by source_type, event_kind")
        .map(this::mappingRow);
  }

  /** 登记一条映射。 */
  @Transactional
  public FinanceVoucherMappingRow createMapping(
      FinanceVoucherMappingInput input, AccessService.Access actor) {
    if (input == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
    }
    var sourceType = parseSourceType(input.sourceType());
    if (sourceType == VoucherSourceType.MANUAL || sourceType == VoucherSourceType.REVERSAL) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "映射只针对业务事件类型（发货 / 收货 / 收付款 / 费用计提）");
    }
    var eventKind = requireText(input.eventKind(), "事件细分").toUpperCase();
    if (sql.fetchOne(
            "select 1 from finance_voucher_mapping where source_type = ? and event_kind = ?",
            sourceType.name(),
            eventKind)
        != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, sourceType.name() + " / " + eventKind + " 的映射已存在");
    }
    var id = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into finance_voucher_mapping(id,tenant_id,source_type,event_kind,debit_subject_code,"
            + "debit_subject_name,credit_subject_code,credit_subject_name,enabled,note,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        actor.tenantId(),
        sourceType.name(),
        eventKind,
        requireText(input.debitSubjectCode(), "借方科目编码"),
        requireText(input.debitSubjectName(), "借方科目名称"),
        requireText(input.creditSubjectCode(), "贷方科目编码"),
        requireText(input.creditSubjectName(), "贷方科目名称"),
        input.enabled() == null ? Boolean.TRUE : input.enabled(),
        input.note() == null ? "" : input.note().trim(),
        now,
        now);
    return mappingRow(sql.fetchOne("select * from finance_voucher_mapping where id = ?", id));
  }

  /** 修改一条映射。 */
  @Transactional
  public FinanceVoucherMappingRow updateMapping(
      UUID id, FinanceVoucherMappingInput input, AccessService.Access actor) {
    var existing = requireMappingRow(id);
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_voucher_mapping set debit_subject_code=?, debit_subject_name=?,"
            + " credit_subject_code=?, credit_subject_name=?, enabled=?, note=?, version=version+1,"
            + " updated_at=?::timestamptz where id=?",
        input.debitSubjectCode() == null
            ? existing.get("debit_subject_code", String.class)
            : requireText(input.debitSubjectCode(), "借方科目编码"),
        input.debitSubjectName() == null
            ? existing.get("debit_subject_name", String.class)
            : requireText(input.debitSubjectName(), "借方科目名称"),
        input.creditSubjectCode() == null
            ? existing.get("credit_subject_code", String.class)
            : requireText(input.creditSubjectCode(), "贷方科目编码"),
        input.creditSubjectName() == null
            ? existing.get("credit_subject_name", String.class)
            : requireText(input.creditSubjectName(), "贷方科目名称"),
        input.enabled() == null ? existing.get("enabled", Boolean.class) : input.enabled(),
        input.note() == null ? existing.get("note", String.class) : input.note().trim(),
        now,
        id);
    return mappingRow(sql.fetchOne("select * from finance_voucher_mapping where id = ?", id));
  }

  // ================================================================ 内部工具

  /** 分录行入参 → 校验后的行。 */
  private List<PreparedLine> prepareLines(List<FinanceVoucherLineInput> raw, String currency) {
    if (raw == null || raw.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "凭证至少要有一行分录");
    }
    var prepared = new ArrayList<PreparedLine>();
    for (var line : raw) {
      if (line == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分录行不能为空");
      }
      var lineType = parseLineType(line.lineType());
      var subjectCode = requireText(line.subjectCode(), "科目编码");
      var subjectName = requireText(line.subjectName(), "科目名称");
      var amount = line.amount();
      if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分录行金额必须大于 0");
      }
      if (amount.scale() > MONEY_SCALE) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "分录行金额最多 " + MONEY_SCALE + " 位小数");
      }
      var lineCurrency =
          line.currency() == null || line.currency().isBlank()
              ? currency
              : line.currency().trim().toUpperCase();
      if (!lineCurrency.equals(currency)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "凭证币种是 " + currency + "，分录行币种是 " + lineCurrency + "：一张凭证只能有一个币种");
      }
      BigDecimal quantity = null;
      if (line.quantity() != null) {
        if (line.quantity().scale() > QUANTITY_SCALE) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "数量最多 " + QUANTITY_SCALE + " 位小数");
        }
        if (line.quantity().compareTo(BigDecimal.ZERO) == 0) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数量不能为 0");
        }
        quantity = line.quantity();
      }
      var auxiliaryType =
          line.auxiliaryType() == null || line.auxiliaryType().isBlank()
              ? VoucherAuxiliaryType.NONE
              : parseAuxiliaryType(line.auxiliaryType());
      UUID auxiliaryId = line.auxiliaryId();
      if (auxiliaryType == VoucherAuxiliaryType.NONE) {
        auxiliaryId = null;
      } else if (auxiliaryId == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "辅助核算维度「" + auxiliaryType.label() + "」必须给出引用 id");
      }
      prepared.add(
          new PreparedLine(
              lineType,
              subjectCode,
              subjectName,
              line.summary() == null ? "" : line.summary().trim(),
              amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
              lineCurrency,
              quantity,
              auxiliaryType,
              auxiliaryId,
              line.auxiliaryName() == null ? "" : line.auxiliaryName().trim()));
    }
    return prepared;
  }

  private void insertLines(UUID voucherId, List<PreparedLine> lines, UUID tenantId, OffsetDateTime now) {
    int seq = 0;
    for (var line : lines) {
      seq++;
      sql.execute(
          "insert into finance_voucher_line(id,tenant_id,voucher_id,line_no,line_type,subject_code,"
              + "subject_name,summary,amount,currency,quantity,auxiliary_type,auxiliary_id,"
              + "auxiliary_name,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          tenantId,
          voucherId,
          seq,
          line.lineType().name(),
          line.subjectCode(),
          line.subjectName(),
          line.summary(),
          line.amount(),
          line.currency(),
          line.quantity(),
          line.auxiliaryType().name(),
          line.auxiliaryId(),
          line.auxiliaryName(),
          now,
          now);
    }
  }

  private void recordEvent(
      UUID voucherId,
      VoucherEventType type,
      String fromStatus,
      String toStatus,
      String reason,
      String detail,
      AccessService.Access actor,
      OffsetDateTime occurredAt) {
    sql.execute(
        "insert into finance_voucher_event(id,tenant_id,voucher_id,event_type,from_status,to_status,"
            + "reason,detail,actor_id,occurred_at)"
            + " values(?,?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        actor.tenantId(),
        voucherId,
        type.name(),
        fromStatus,
        toStatus,
        reason,
        detail,
        actor.userId(),
        occurredAt);
  }

  /** 凭证号：{@code VC-yyyyMM-0001}（同租户内唯一，撞号由唯一约束兜住）。 */
  private String nextVoucherNo() {
    var prefix =
        "VC-"
            + OffsetDateTime.now(ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"))
            + "-";
    var next =
        sql.fetchOne(
                "select coalesce(max(substring(voucher_no from " + VOUCHER_NO_PREFIX_LENGTH + ")::int), 0) + 1"
                    + " from finance_voucher where voucher_no like ? and voucher_no ~ '^VC-[0-9]{6}-[0-9]{4,}$'",
                prefix + "%")
            .get(0, Integer.class);
    return prefix + String.format("%04d", next);
  }

  /** 乐观并发：版本号不匹配 ⇒ 409（不静默覆盖）。 */
  private void requireVersion(Record row, Integer expectedVersion) {
    if (expectedVersion == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "必须带上 expected_version（乐观并发控制）");
    }
    var current = row.get("version", Integer.class);
    if (current != expectedVersion) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "凭证已被别人改动（当前版本 " + current + "，你拿到的是 " + expectedVersion
              + "）：请刷新后重试");
    }
  }

  private Record requireVoucher(UUID id) {
    // ⚠️ **必须连出 period_code**：voucherRow 会读这一列，`select *` 不带它 ⇒
    //    jOOQ 读一个 ResultSet 里不存在的字段名会抛 IllegalArgumentException ⇒
    //    被统一错误处理翻译成「400 字段格式不正确」，极难从现象反推（见 docs/backlog）。
    var row =
        sql.fetchOne(
            "select v.*, p.period_code from finance_voucher v"
                + " join finance_voucher_period p on p.tenant_id = v.tenant_id and p.id = v.period_id"
                + " where v.id = ?",
            id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "凭证不存在");
    }
    return row;
  }

  private Record requirePeriod(UUID id) {
    if (id == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定会计期间");
    }
    var row = sql.fetchOne("select * from finance_voucher_period where id = ?", id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会计期间不存在");
    }
    return row;
  }

  private Record requireMapping(VoucherSourceType sourceType, String eventKind) {
    var row =
        sql.fetchOne(
            "select * from finance_voucher_mapping where source_type = ? and event_kind = ?",
            sourceType.name(),
            eventKind);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "没有登记「" + sourceType.label() + " / " + eventKind + "」的科目映射："
              + "先在「业务事件映射」里登记借贷科目，才能自动生成凭证");
    }
    return row;
  }

  private Record requireMappingRow(UUID id) {
    var row = sql.fetchOne("select * from finance_voucher_mapping where id = ?", id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "映射不存在");
    }
    return row;
  }

  private List<FinanceVoucherLineRow> lines(UUID voucherId) {
    return sql.fetch(
            "select * from finance_voucher_line where voucher_id = ? order by line_no", voucherId)
        .map(this::lineRow);
  }

  private List<FinanceVoucherEventRow> events(UUID voucherId) {
    return sql.fetch(
            "select * from finance_voucher_event where voucher_id = ? order by occurred_at, id",
            voucherId)
        .map(this::eventRow);
  }

  private FinanceVoucherRow voucherRow(Record r) {
    var sourceType = r.get("source_type", String.class);
    var status = r.get("status", String.class);
    return new FinanceVoucherRow(
        r.get("id", UUID.class),
        r.get("voucher_no", String.class),
        sourceType,
        VoucherSourceType.valueOf(sourceType).label(),
        r.get("source_id", UUID.class),
        r.get("source_no", String.class),
        r.get("reversal_of_id", UUID.class),
        r.get("reversal_voucher_id", UUID.class),
        r.get("period_id", UUID.class),
        r.get("period_code", String.class),
        r.get("voucher_date", LocalDate.class),
        r.get("description", String.class),
        r.get("currency", String.class),
        status,
        VoucherStatus.valueOf(status).label(),
        r.get("total_debit", BigDecimal.class),
        r.get("total_credit", BigDecimal.class),
        r.get("line_count", Integer.class),
        r.get("posted_at", OffsetDateTime.class),
        r.get("posted_by", UUID.class),
        r.get("reversed_at", OffsetDateTime.class),
        r.get("note", String.class),
        r.get("version", Integer.class),
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private FinanceVoucherLineRow lineRow(Record r) {
    var lineType = r.get("line_type", String.class);
    return new FinanceVoucherLineRow(
        r.get("id", UUID.class),
        r.get("line_no", Integer.class),
        lineType,
        VoucherLineType.valueOf(lineType).label(),
        r.get("subject_code", String.class),
        r.get("subject_name", String.class),
        r.get("summary", String.class),
        r.get("amount", BigDecimal.class),
        r.get("currency", String.class),
        r.get("quantity", BigDecimal.class),
        r.get("auxiliary_type", String.class),
        r.get("auxiliary_id", UUID.class),
        r.get("auxiliary_name", String.class));
  }

  private FinanceVoucherEventRow eventRow(Record r) {
    var eventType = r.get("event_type", String.class);
    return new FinanceVoucherEventRow(
        r.get("id", UUID.class),
        eventType,
        VoucherEventType.valueOf(eventType).label(),
        r.get("from_status", String.class),
        r.get("to_status", String.class),
        r.get("reason", String.class),
        r.get("detail", String.class),
        r.get("actor_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class));
  }

  private FinanceVoucherPeriodRow periodRow(Record r) {
    return new FinanceVoucherPeriodRow(
        r.get("id", UUID.class),
        r.get("period_code", String.class),
        r.get("period_start", LocalDate.class),
        r.get("period_end", LocalDate.class),
        r.get("status", String.class),
        r.get("base_currency", String.class),
        r.get("closed_at", OffsetDateTime.class),
        r.get("closed_by", UUID.class),
        r.get("closed_reason", String.class),
        r.get("note", String.class),
        r.get("version", Integer.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private FinanceVoucherMappingRow mappingRow(Record r) {
    return new FinanceVoucherMappingRow(
        r.get("id", UUID.class),
        r.get("source_type", String.class),
        r.get("event_kind", String.class),
        r.get("debit_subject_code", String.class),
        r.get("debit_subject_name", String.class),
        r.get("credit_subject_code", String.class),
        r.get("credit_subject_name", String.class),
        r.get("enabled", Boolean.class),
        r.get("note", String.class),
        r.get("version", Integer.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static String requireText(String raw, String label) {
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能为空");
    }
    return raw.trim();
  }

  private static VoucherSourceType parseSourceType(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "来源类型不能为空");
    }
    try {
      return VoucherSourceType.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "来源类型取值不合法：" + raw);
    }
  }

  private static VoucherLineType parseLineType(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分录行方向不能为空");
    }
    try {
      return VoucherLineType.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分录行方向取值不合法：" + raw);
    }
  }

  private static VoucherAuxiliaryType parseAuxiliaryType(String raw) {
    try {
      return VoucherAuxiliaryType.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "辅助核算维度取值不合法：" + raw);
    }
  }

  /** 把可变长参数拼成 jOOQ 可用的数组。 */
  private static Object[] concat(List<Object> base, Object... extra) {
    var all = new ArrayList<Object>(base);
    for (var item : extra) {
      all.add(item);
    }
    return all.toArray();
  }
}
