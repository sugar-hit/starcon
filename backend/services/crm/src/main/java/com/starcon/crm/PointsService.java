package com.starcon.crm;

import static com.starcon.crm.PointsModels.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.crm.PointsOrderFactsPort.OrderFact;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 15.6.1.2 积分与权益。
 *
 * <p>蓝图 15 卷 15.6.1.2 原话：
 *
 * <blockquote>
 * 职责：管理积分与权益：积分获取（消费/评价/签到）与消耗（抵扣/兑换）、积分余额与有效期、权益核销；积分负债需财务确认。
 * 输入：积分规则；获取/消耗事件；权益核销。
 * 输出：积分账户与流水；权益核销记录；积分负债汇总（→ 12）。
 * 关系：上游 = 15.6.1.1、9 订单；下游 = 5 定价（积分抵扣）、12 财务（积分负债）。**未兑付积分是负债**，需在财务侧有对应科目或备忘，不能只记在业务库。
 * </blockquote>
 *
 * <p><b>本类落六条纪律</b>：
 *
 * <ol>
 *   <li><b>积分比率是租户数据</b>：拿多少分、抵多少比例、单积分值多少，
 *       全部落在 {@code crm_points_rule}。<b>代码里没有任何写死的积分比率。</b></li>
 *   <li><b>扣减在显式 SQL 里看得见</b>：流水行带「扣前余额 → 扣后余额」，
 *       余额扣减用<b>条件更新</b>（{@code where balance >= ?}）——
 *       并发扣减时余额不足的那一方<b>更新 0 行</b>，而不是两个都扣成功。</li>
 *   <li><b>有效期按绝对时刻判定</b>：批次带 {@code expires_at}（timestamptz），
 *       消耗时<b>先到期先消耗</b>；到期由结算动作把剩余量清零并留一条 EXPIRE 流水。</li>
 *   <li><b>不跨币种算</b>：抵扣上限要拿订单金额乘抵扣率，订单币种与积分估值币种
 *       不一致时<b>直接拒绝</b>，而不是偷偷折算。</li>
 *   <li><b>判定基于「拉取到的事实」</b>：订单事实经 {@link PointsOrderFactsPort}
 *       从 trade 的授权 HTTP API 读；读不到就<b>显式失败</b>（503），
 *       绝不返回空集合冒充「这个租户没有订单」。</li>
 *   <li><b>负债如实标注</b>：本类只<b>输出</b>负债汇总，
 *       <b>不代劳</b>在财务侧建科目、<b>不假装</b>已经入账（见 {@link #liability()}）。</li>
 * </ol>
 */
@Service
public class PointsService {

  // ================================================================ 常量

  /** 列表页最大条数（与本服务其它模块同一上限）。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 积分的标度（numeric(19,6)）。 */
  static final int POINTS_SCALE = 6;

  /** 金额出参的**最少小数位**（与 15.6.1.1 同一口径）。 */
  static final int MONEY_MIN_SCALE = 2;

  /** 「即将过期」的观察窗口（天）—— 负债汇总里的「30 天内到期」。 */
  static final int EXPIRING_WINDOW_DAYS = 30;

  /**
   * 时刻出参格式：**统一换算成 UTC**，秒必写，毫秒/微秒有值才写，结尾恒为 {@code Z}。
   *
   * <p>🔴 为什么换算成 UTC：同一个绝对时刻用不同偏移量写出来会是两个字符串
   * （{@code 2026-10-10T08:00+08:00} 与 {@code 2026-10-10T00:00Z} 是同一时刻），
   * 拿字符串比大小 / 比相等都会得出「不是同一个时刻」的错结论。
   */
  private static final DateTimeFormatter MOMENT_TEXT =
      new DateTimeFormatterBuilder()
          .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
          .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
          .appendLiteral('Z')
          .toFormatter()
          .withZone(ZoneOffset.UTC);

  private final DSLContext sql;
  private final PointsOrderFactsPort orders;

  public PointsService(DSLContext sql, PointsOrderFactsPort orders) {
    this.sql = sql;
    this.orders = orders;
  }

  /** 快照 JSON 的 mapper：BigDecimal 按原样写出（不转成浮点）。 */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  // ================================================================ 能力声明

  /**
   * 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 三个维度刻意是 {@code available=false}：
   *
   * <ul>
   *   <li>{@code finance_liability_booking} —— <b>12 财务侧未交付积分负债科目</b>：
   *       蓝图加粗句「未兑付积分是负债，需在财务侧有对应科目或备忘，不能只记在业务库」。
   *       本模块<b>只输出</b>负债汇总，<b>不代劳</b>建科目、<b>不假装</b>已经入账。</li>
   *   <li>{@code pricing_deduction_integration} —— <b>5 定价侧未交付积分抵扣写入</b>：
   *       本模块只输出抵扣口径（抵扣率上限 / 单积分价值 / 可用余额），不改价目表。</li>
   *   <li>{@code points_reversal} —— <b>积分冲正（退货回退）本轮未交付</b>：
   *       本模块只交付蓝图点名的获取 / 消耗 / 有效期 / 核销 / 负债汇总五件事。</li>
   * </ul>
   */
  public CrmPointsCapabilities capabilities() {
    List<CrmPointsCapabilityDimension> dimensions =
        List.of(
            new CrmPointsCapabilityDimension(
                "points_rules",
                "积分规则（拿多少分 / 抵多少比例 / 单积分值多少 / 积分有效期）",
                true,
                "本服务 V442 crm_points_rule（**租户数据**，不是代码常量）",
                "",
                "三种规则：EARN 获取 / SPEND 消耗（抵扣率上限）/ VALUATION 估值（单积分价值）。"
                    + "**代码里没有任何写死的积分比率**。一个租户同时只能有一条开着的估值规则 —— "
                    + "否则负债汇总会没有唯一答案"),
            new CrmPointsCapabilityDimension(
                "points_accounts",
                "积分账户（余额 / 累计获取 / 累计消耗 / 累计过期）",
                true,
                "本服务 V442 crm_points_account + crm_membership_profile（**只读** 15.6.1.1）",
                "15.6.1.1",
                "积分挂在**真实会员身份**上（member_key）；DB 触发器拒绝给不存在的会员建账户。"
                    + "余额 DB 层硬保证**不为负**"),
            new CrmPointsCapabilityDimension(
                "points_earning",
                "积分获取（消费 / 评价 / 签到 / 人工）",
                true,
                "消费读上游 trade 9.1.1.1（授权 HTTP API 只读）；评价读本服务 V294 crm_product_review（**只读**）",
                "9 / 15.2.2.1",
                "上游读不到 ⇒ **503**，绝不返回空集合冒充「这个租户没有订单」。"
                    + "同一笔订单 / 同一条评价**只允许发一次积分**（DB 层部分唯一索引）"),
            new CrmPointsCapabilityDimension(
                "points_spending",
                "积分消耗（订单抵扣 / 权益兑换）",
                true,
                "本服务 V442 crm_points_ledger + crm_points_lot",
                "5",
                "抵扣上限 = 订单金额 × 抵扣率上限 ÷ 单积分价值。"
                    + "🔴 订单币种与估值币种不一致时**直接拒绝**，不跨币种折算。"
                    + "余额扣减走**条件更新**，并发扣减时余额不足的一方**更新 0 行**"),
            new CrmPointsCapabilityDimension(
                "points_expiry",
                "积分余额与有效期（先到期先消耗 / 到期结算）",
                true,
                "本服务 V442 crm_points_lot（expires_at = timestamptz，**绝对时刻**）",
                "",
                "有效期按**绝对时刻**判定；消耗时先扣到期最早的批次；"
                    + "到期由结算动作清零剩余量并留一条 EXPIRE 流水（余额与批次同步减）"),
            new CrmPointsCapabilityDimension(
                "entitlement_redemption",
                "权益目录与权益核销（兑换 → 核销）",
                true,
                "本服务 V442 crm_points_entitlement + crm_points_redemption",
                "",
                "兑换内容以**快照**落库（之后改权益目录不改写已发生的兑换）；"
                    + "核销状态**单向** REDEEMED → USED，不许回退"),
            new CrmPointsCapabilityDimension(
                "points_liability_summary",
                "积分负债汇总（未兑付积分 × 单积分价值）",
                true,
                "本服务 V442 crm_points_account + crm_points_rule（VALUATION）",
                "12",
                "这是**交给 12 财务的汇总**，不是账。汇总口径：Σ 账户余额 × 单积分价值"),
            new CrmPointsCapabilityDimension(
                "finance_liability_booking",
                "在 12 财务侧建立积分负债科目 / 备忘并入账",
                false,
                "**12 财务侧未交付积分负债科目**",
                "12",
                "🔴 本模块**只输出汇总**，**不代劳**在财务侧建科目，**不假装**已经入账 —— "
                    + "业务库里有一张表 ≠ 账上有一笔负债。未兑付积分在账上仍是负债，"
                    + "差额由 12 财务侧补科目后消化"),
            new CrmPointsCapabilityDimension(
                "pricing_deduction_integration",
                "5 定价侧按积分抵扣金额改价目 / 落单",
                false,
                "**5 定价侧未交付积分抵扣写入**",
                "5",
                "本模块**只输出**抵扣口径（抵扣率上限 / 单积分价值 / 可用余额），"
                    + "**不改**价目表（跨服务禁止）"),
            new CrmPointsCapabilityDimension(
                "points_reversal",
                "积分冲正（退货 / 退款后回退已发积分）",
                false,
                "积分冲正本轮未交付",
                "15.6.1.2",
                "本模块只交付蓝图点名的获取 / 消耗 / 有效期 / 核销 / 负债汇总五件事；"
                    + "冲正需要一条**反向**流水与批次回补口径，未交付"));
    return new CrmPointsCapabilities(
        dimensions,
        java.util.Arrays.stream(PointsRuleType.values()).map(Enum::name).toList(),
        java.util.Arrays.stream(PointsEarnSource.values()).map(Enum::name).toList(),
        java.util.Arrays.stream(PointsDirection.values()).map(Enum::name).toList(),
        "🔴 **积分比率是租户数据**（crm_points_rule），可配、带生效区间。"
            + "代码里**没有任何写死的积分比率**：不配规则就没有积分。"
            + "获取规则决定「拿多少分 + 积分有效期（valid_months）+ 每日上限（max_per_day，按 UTC 自然日）」；"
            + "消耗规则决定「订单金额最多能用积分抵掉多少比例（max_deduct_rate）+ 单笔最少抵扣（min_deduct_points）」；"
            + "估值规则决定「每 1 积分折算多少金额（point_value_rate）+ 负债币种」—— "
            + "**一个租户同时只能有一条开着的估值规则**，否则积分负债汇总会没有唯一答案。",
        "🔴 **未兑付积分是负债**（蓝图加粗句）。本模块只**输出**负债汇总"
            + "（Σ 账户余额 × 单积分价值），**不代劳**在 12 财务侧建科目，也**不假装**已经入账 —— "
            + "能力声明里 `finance_liability_booking` 这一维是 `available=false`、归属模块 12。"
            + "本模块里的汇总数字是**业务侧读数**，不是账。",
        "上游 9 订单在 trade —— 本服务**只经授权 HTTP API 读**，"
            + "一条跨库 SQL / 跨库 FK / 跨库视图都没有。"
            + "会员身份读本服务 15.6.1.1 的会员档案（**只读**）；"
            + "评价读本服务 15.2.2.1 的评价表（**只读**）。",
        "「未兑付积分是负债」在本模块落三层："
            + "① 账户余额 DB 层硬保证**不为负**（CHECK + 条件更新）；"
            + "② 每一行流水都带**扣前余额 → 扣后余额**，余额是流水推出来的、流水**只追加**；"
            + "③ 负债汇总是**显式输出**且带 `bookedInFinance=false` —— "
            + "**不假装**财务侧已经入账。");
  }

  // ================================================================ 积分账户

  /** 积分账户列表：真分页（{@code LIMIT/OFFSET} + 稳定全序）+ 与分页无关的统计。 */
  public CrmPointsAccountPage accountPage(
      String memberKey,
      String sourceShop,
      String keyword,
      boolean onlyWithBalance,
      int page,
      int size,
      AccessService.Access actor) {

    List<Object> binds = new ArrayList<>();
    String where = accountWhere(memberKey, sourceShop, keyword, onlyWithBalance, binds);

    long total =
        sql.fetchOne("select count(*) from crm_points_account a" + where, binds.toArray())
            .get(0, Long.class);

    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);

    List<Record> records =
        sql.fetch(
            "select a.* from crm_points_account a"
                + where
                + " order by a.balance desc, a.account_key asc"
                + " limit ? offset ?",
            pageBinds.toArray());

    CrmPointsStats stats = accountStats();
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmPointsAccountPage(
        accountRows(records),
        safePage,
        safeSize,
        total,
        totalPages,
        stats,
        dataScopeOf(actor),
        "积分账户是**租户级**的（没有「归属人」这一维），所以 SELF / DEPARTMENT 两种范围"
            + "在积分账户上退化为租户级 —— 积分不按人切分。");
  }

  /** 账户列表的 WHERE 片段（列表与统计**共用同一份**，保证「统计的就是筛出来的」）。 */
  private String accountWhere(
      String memberKey,
      String sourceShop,
      String keyword,
      boolean onlyWithBalance,
      List<Object> binds) {
    StringBuilder where = new StringBuilder(" where a.tenant_id = ?");
    binds.add(currentTenant());
    if (blankToNull(memberKey) != null) {
      where.append(" and a.member_key = ?");
      binds.add(memberKey.trim());
    }
    if (blankToNull(sourceShop) != null) {
      where.append(" and a.source_shop = ?");
      binds.add(sourceShop.trim());
    }
    if (blankToNull(keyword) != null) {
      where.append(
          " and (strpos(lower(a.customer_ref), lower(?)) > 0"
              + " or strpos(lower(a.customer_name), lower(?)) > 0"
              + " or strpos(lower(a.account_key), lower(?)) > 0)");
      String kw = keyword.trim();
      binds.add(kw);
      binds.add(kw);
      binds.add(kw);
    }
    if (onlyWithBalance) {
      where.append(" and a.balance > 0");
    }
    return where.toString();
  }

  /**
   * 积分统计：与分页无关（单独一条统计查询，不是拿当前页聚合出来的）。
   *
   * <p>🔴 统计与列表**共用同一份 WHERE**：统计的就是筛出来的。
   */
  private CrmPointsStats accountStats() {
    Record row =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where balance > 0) as with_balance,"
                + " coalesce(sum(balance),0) as outstanding"
                + " from crm_points_account where tenant_id = ?",
            currentTenant());
    OffsetDateTime horizon = now().plusDays(EXPIRING_WINDOW_DAYS);
    Record expiring =
        sql.fetchOne(
            "select count(*) as lots, coalesce(sum(remaining),0) as points"
                + " from crm_points_lot where tenant_id = ? and remaining > 0"
                + " and expires_at is not null and expires_at <= ?::timestamptz",
            currentTenant(),
            horizon);
    Record ledger =
        sql.fetchOne(
            "select count(*) as total from crm_points_ledger where tenant_id = ?", currentTenant());
    return new CrmPointsStats(
        row.get("total", Long.class),
        row.get("with_balance", Long.class),
        pointsText(row.get("outstanding", BigDecimal.class)),
        expiring.get("lots", Long.class),
        pointsText(expiring.get("points", BigDecimal.class)),
        ledger.get("total", Long.class),
        "🔴 未兑付积分（Σ 账户余额）是**负债**，需在 12 财务侧有对应科目或备忘 —— "
            + "本模块只输出汇总，**不代劳**在财务侧入账。"
            + "余额来自积分流水（只追加），不是就地改出来的。");
  }

  /** 单个积分账户详情（账户 + 批次 + 最近流水）。 */
  public CrmPointsAccountDetail accountDetail(UUID id) {
    Record record =
        sql.fetchOne(
            "select * from crm_points_account where tenant_id = ? and id = ?",
            currentTenant(),
            id);
    if (record == null) throw notFound("积分账户不存在：" + id);
    OffsetDateTime at = now();
    List<CrmPointsLotRow> lots =
        sql.fetch(
                "select * from crm_points_lot where tenant_id = ? and account_id = ?"
                    + " order by (expires_at is null), expires_at asc, id asc",
                currentTenant(),
                id)
            .stream()
            .map(r -> lotRow(r, at))
            .toList();
    List<CrmPointsLedgerRow> ledger =
        sql.fetch(
                "select l.*, a.customer_name, r.rule_code from crm_points_ledger l"
                    + " join crm_points_account a on a.id = l.account_id and a.tenant_id = l.tenant_id"
                    + " left join crm_points_rule r on r.id = l.rule_id and r.tenant_id = l.tenant_id"
                    + " where l.tenant_id = ? and l.account_id = ?"
                    + " order by l.occurred_at desc, l.id desc limit 50",
                currentTenant(),
                id)
            .stream()
            .map(this::ledgerRow)
            .toList();
    return new CrmPointsAccountDetail(accountRow(record), lots, ledger);
  }

  // ================================================================ 积分规则

  /** 积分规则列表（真分页 + 真筛选）。 */
  public CrmPointsRulePage rulePage(String ruleType, String ruleCode, int page, int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where tenant_id = ?");
    binds.add(currentTenant());
    if (blankToNull(ruleType) != null) {
      where.append(" and rule_type = ?");
      binds.add(ruleType.trim().toUpperCase(Locale.ROOT));
    }
    if (blankToNull(ruleCode) != null) {
      where.append(" and rule_code = ?");
      binds.add(ruleCode.trim().toUpperCase(Locale.ROOT));
    }
    long total =
        sql.fetchOne("select count(*) from crm_points_rule" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<Record> records =
        sql.fetch(
            "select * from crm_points_rule"
                + where
                + " order by rule_type asc, effective_from desc, id desc"
                + " limit ? offset ?",
            pageBinds.toArray());
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmPointsRulePage(ruleRows(records), safePage, safeSize, total, totalPages);
  }

  /** 单条积分规则。 */
  public CrmPointsRuleRow rule(UUID id) {
    Record record =
        sql.fetchOne(
            "select * from crm_points_rule where tenant_id = ? and id = ?",
            currentTenant(),
            id);
    if (record == null) throw notFound("积分规则不存在：" + id);
    return ruleRow(record);
  }

  /**
   * 新建一条积分规则。
   *
   * <p>🔴 请求体**不接受** tenantId / userId：租户来自已认证成员关系。
   */
  @Transactional
  public CrmPointsRuleRow createRule(CrmPointsRuleInput input) {
    String type = input.ruleType().trim().toUpperCase(Locale.ROOT);
    String code = input.ruleCode().trim().toUpperCase(Locale.ROOT);
    if (code.isEmpty()) throw bad("积分规则代码不能为空。");
    PointsRuleType ruleType;
    try {
      ruleType = PointsRuleType.valueOf(type);
    } catch (IllegalArgumentException e) {
      throw bad("积分规则类型只能是 EARN（获取）/ SPEND（消耗）/ VALUATION（估值）。");
    }
    OffsetDateTime effectiveFrom = parseMoment(input.effectiveFrom(), "生效起点");
    OffsetDateTime effectiveTo =
        blankToNull(input.effectiveTo()) == null ? null : parseMoment(input.effectiveTo(), "生效终点");
    if (effectiveTo != null && !effectiveFrom.isBefore(effectiveTo)) {
      throw bad("生效终点必须晚于生效起点。");
    }
    // 🔴 门槛与币种**同生共死**（跨币种的金额不能比）。
    if ((input.thresholdAmount() == null) != (blankToNull(input.thresholdCurrency()) == null)) {
      throw bad("消费门槛与门槛币种必须**同时**给或同时不给（跨币种的金额不能比）。");
    }
    if ((input.pointValueRate() == null) != (blankToNull(input.liabilityCurrency()) == null)) {
      throw bad("单积分价值与负债币种必须**同时**给或同时不给。");
    }
    if (ruleType == PointsRuleType.VALUATION
        && (input.pointValueRate() == null || blankToNull(input.liabilityCurrency()) == null)) {
      throw bad("估值规则必须给「每 1 积分折算多少金额」与「负债币种」—— 积分负债汇总依赖它。");
    }
    if (ruleType == PointsRuleType.SPEND && input.maxDeductRate() == null) {
      throw bad("消耗规则必须给「抵扣率上限」—— 否则「最多能抵多少」没有答案。");
    }
    if (ruleType == PointsRuleType.EARN
        && input.fixedPoints() == null && input.pointsPerUnit() == null) {
      throw bad("获取规则必须给「固定积分」或「每单位金额换多少积分」—— 否则发不出积分。");
    }
    String earnSource =
        ruleType == PointsRuleType.EARN
            ? (blankToNull(input.earnSource()) == null
                ? "MANUAL"
                : input.earnSource().trim().toUpperCase(Locale.ROOT))
            : null;
    if (ruleType == PointsRuleType.EARN) {
      try {
        PointsEarnSource.valueOf(earnSource);
      } catch (IllegalArgumentException e) {
        throw bad("获取来源只能是 PURCHASE（消费）/ REVIEW（评价）/ CHECKIN（签到）/ MANUAL（人工）。");
      }
      if ("PURCHASE".equals(earnSource) && input.pointsPerUnit() == null) {
        throw bad("消费获取必须给「每单位金额换多少积分」—— 消费没有金额就换不出积分。");
      }
    }
    UUID id = UUID.randomUUID();
    try {
      sql.execute(
          "insert into crm_points_rule"
              + "(id,tenant_id,rule_code,rule_name,rule_type,earn_source,points_per_unit,fixed_points,"
              + " threshold_amount,threshold_currency,valid_months,max_per_day,max_deduct_rate,"
              + " min_deduct_points,point_value_rate,liability_currency,enabled,effective_from,"
              + " effective_to,note,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,"
              + " ?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          code,
          input.ruleName().trim(),
          ruleType.name(),
          earnSource,
          input.pointsPerUnit(),
          input.fixedPoints(),
          input.thresholdAmount(),
          blankToNull(input.thresholdCurrency()) == null
              ? null
              : blankToNull(input.thresholdCurrency()).toUpperCase(Locale.ROOT),
          input.validMonths(),
          input.maxPerDay(),
          input.maxDeductRate(),
          input.minDeductPoints(),
          input.pointValueRate(),
          blankToNull(input.liabilityCurrency()) == null
              ? null
              : blankToNull(input.liabilityCurrency()).toUpperCase(Locale.ROOT),
          input.enabled() == null || input.enabled(),
          effectiveFrom,
          effectiveTo,
          input.note() == null ? "" : input.note().trim(),
          now(),
          now());
    } catch (DataIntegrityViolationException e) {
      // 🔴 **别把所有约束违同一律说成「已经有一条规则了」**：那会把「NOT NULL 没给」
      //   这类真问题盖成一句误导人的话，运营会去查错的地方。
      //   这里按 DB 的真实理由分两种说法。
      String reason = dbReason(e);
      if (reason.contains("duplicate key")) {
        throw conflict(
            "积分规则「"
                + code
                + "」已经有一条生效中的规则了（同一代码同时只能有一条开着的规则）。"
                + "要改口径请新建一条（新的生效起点）。");
      }
      throw conflict("积分规则「" + code + "」保存失败：" + reason);
    }
    return rule(id);
  }

  /**
   * 改一条积分规则。
   *
   * <p>🔴 <b>口径列不可就地修改</b>（不可变优先）：改了它，之前按它发过的积分就解释不通了。
   * 这里只允许改「是否启用 / 备注 / 生效终点」。
   */
  @Transactional
  public CrmPointsRuleRow updateRule(UUID id, CrmPointsRuleInput input) {
    CrmPointsRuleRow existing = rule(id);
    if (!existing.ruleCode().equals(input.ruleCode().trim().toUpperCase(Locale.ROOT))) {
      throw conflict("积分规则代码不可就地修改：请新建一条规则。");
    }
    if (!existing.ruleType().equals(input.ruleType().trim().toUpperCase(Locale.ROOT))) {
      throw conflict("积分规则类型不可就地修改：请新建一条规则。");
    }
    OffsetDateTime effectiveTo =
        blankToNull(input.effectiveTo()) == null ? null : parseMoment(input.effectiveTo(), "生效终点");
    sql.execute(
        "update crm_points_rule set enabled = ?, effective_to = ?::timestamptz, note = ?,"
            + " updated_at = ?::timestamptz where tenant_id = ? and id = ?",
        input.enabled() == null || input.enabled(),
        effectiveTo,
        input.note() == null ? existing.note() : input.note().trim(),
        now(),
        currentTenant(),
        id);
    return rule(id);
  }

  // ================================================================ 权益目录

  /** 权益目录列表（真分页 + 真筛选）。 */
  public CrmPointsEntitlementPage entitlementPage(String entitlementCode, int page, int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where tenant_id = ?");
    binds.add(currentTenant());
    if (blankToNull(entitlementCode) != null) {
      where.append(" and entitlement_code = ?");
      binds.add(entitlementCode.trim().toUpperCase(Locale.ROOT));
    }
    long total =
        sql.fetchOne("select count(*) from crm_points_entitlement" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<Record> records =
        sql.fetch(
            "select * from crm_points_entitlement"
                + where
                + " order by effective_from desc, id desc"
                + " limit ? offset ?",
            pageBinds.toArray());
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmPointsEntitlementPage(
        entitlementRows(records), safePage, safeSize, total, totalPages);
  }

  /** 新建一项权益。 */
  @Transactional
  public CrmPointsEntitlementRow createEntitlement(CrmPointsEntitlementInput input) {
    String code = input.entitlementCode().trim().toUpperCase(Locale.ROOT);
    if (code.isEmpty()) throw bad("权益代码不能为空。");
    PointsBenefitType benefitType;
    try {
      benefitType = PointsBenefitType.valueOf(input.benefitType().trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("权益形态只能是 DISCOUNT（折扣）/ FREE_SHIPPING（免邮）/ PHYSICAL_GIFT（实物）/ COUPON（优惠券）。");
    }
    if ((input.benefitValue() == null) != (blankToNull(input.benefitCurrency()) == null)) {
      throw bad("权益内容与币种必须**同时**给或同时不给。");
    }
    if (benefitType != PointsBenefitType.FREE_SHIPPING && input.benefitValue() == null) {
      throw bad("除免邮外，权益必须给内容（折扣率 / 面额）。");
    }
    OffsetDateTime effectiveFrom = parseMoment(input.effectiveFrom(), "生效起点");
    OffsetDateTime effectiveTo =
        blankToNull(input.effectiveTo()) == null ? null : parseMoment(input.effectiveTo(), "生效终点");
    if (effectiveTo != null && !effectiveFrom.isBefore(effectiveTo)) {
      throw bad("生效终点必须晚于生效起点。");
    }
    UUID id = UUID.randomUUID();
    try {
      sql.execute(
          "insert into crm_points_entitlement"
              + "(id,tenant_id,entitlement_code,entitlement_name,point_cost,benefit_type,benefit_value,"
              + " benefit_currency,stock,per_member_limit,enabled,effective_from,effective_to,note,"
              + " created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?::timestamptz,"
              + " ?::timestamptz)",
          id,
          currentTenant(),
          code,
          input.entitlementName().trim(),
          input.pointCost(),
          benefitType.name(),
          input.benefitValue(),
          blankToNull(input.benefitCurrency()) == null
              ? null
              : blankToNull(input.benefitCurrency()).toUpperCase(Locale.ROOT),
          input.stock(),
          input.perMemberLimit(),
          input.enabled() == null || input.enabled(),
          effectiveFrom,
          effectiveTo,
          input.note() == null ? "" : input.note().trim(),
          now(),
          now());
    } catch (DataIntegrityViolationException e) {
      String reason = dbReason(e);
      if (reason.contains("duplicate key")) {
        throw conflict(
            "权益「"
                + code
                + "」已经有一条生效中的权益了（同一代码同时只能有一条开着的权益）。"
                + "要改内容请新建一条（新的生效起点）。");
      }
      throw conflict("权益「" + code + "」保存失败：" + reason);
    }
    return entitlement(id);
  }

  /** 单项权益。 */
  public CrmPointsEntitlementRow entitlement(UUID id) {
    Record record =
        sql.fetchOne(
            "select * from crm_points_entitlement where tenant_id = ? and id = ?",
            currentTenant(),
            id);
    if (record == null) throw notFound("权益不存在：" + id);
    return entitlementRow(record);
  }

  // ================================================================ 积分流水

  /** 积分流水列表（真分页 + 真筛选）。 */
  public CrmPointsLedgerPage ledgerPage(
      String memberKey, String direction, String reasonCode, int page, int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where l.tenant_id = ?");
    binds.add(currentTenant());
    if (blankToNull(memberKey) != null) {
      where.append(" and l.member_key = ?");
      binds.add(memberKey.trim());
    }
    if (blankToNull(direction) != null) {
      where.append(" and l.direction = ?");
      binds.add(direction.trim().toUpperCase(Locale.ROOT));
    }
    if (blankToNull(reasonCode) != null) {
      where.append(" and l.reason_code = ?");
      binds.add(reasonCode.trim().toUpperCase(Locale.ROOT));
    }
    long total =
        sql.fetchOne("select count(*) from crm_points_ledger l" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<Record> records =
        sql.fetch(
            "select l.*, a.customer_name, r.rule_code from crm_points_ledger l"
                + " join crm_points_account a on a.id = l.account_id and a.tenant_id = l.tenant_id"
                + " left join crm_points_rule r on r.id = l.rule_id and r.tenant_id = l.tenant_id"
                + where
                + " order by l.occurred_at desc, l.id desc limit ? offset ?",
            pageBinds.toArray());
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmPointsLedgerPage(ledgerRows(records), safePage, safeSize, total, totalPages);
  }

  // ================================================================ 积分获取

  /**
   * 积分获取（消费 / 评价 / 签到 / 人工）。
   *
   * <p>🔴 五条纪律：
   *
   * <ol>
   *   <li>上游订单事实读不到 ⇒ <b>抛 503</b>，一条积分都不发
   *       （绝不返回空集合冒充「这个租户没有订单」）。</li>
   *   <li>积分<b>只能</b>由规则算出：没有匹配的生效规则 ⇒ 拒绝，代码里没有写死的比率。</li>
   *   <li>同一笔订单 / 同一条评价<b>只允许发一次</b>（DB 层部分唯一索引兜底）。</li>
   *   <li>积分挂在<b>真实会员</b>上（DB 触发器拒绝给不存在的会员建账户）。</li>
   *   <li>有效期按<b>绝对时刻</b>算（{@code occurredAt + valid_months}）。</li>
   * </ol>
   */
  @Transactional
  public CrmPointsEarnResult earn(CrmPointsEarnInput input, UUID actorId, String bearer) {
    String memberKey = input.memberKey().trim();
    String source = input.earnSource().trim().toUpperCase(Locale.ROOT);
    PointsEarnSource earnSource;
    try {
      earnSource = PointsEarnSource.valueOf(source);
    } catch (IllegalArgumentException e) {
      throw bad("获取来源只能是 PURCHASE（消费）/ REVIEW（评价）/ CHECKIN（签到）/ MANUAL（人工）。");
    }
    OffsetDateTime occurredAt =
        blankToNull(input.occurredAt()) == null ? now() : parseMoment(input.occurredAt(), "事件发生时刻");

    // ---- 1. 会员档案（15.6.1.1，只读）—— 积分挂在真实会员身份上
    Record member =
        sql.fetchOne(
            "select * from crm_membership_profile where tenant_id = ? and member_key = ?",
            currentTenant(),
            memberKey);
    if (member == null) {
      throw notFound(
          "会员档案不存在："
              + memberKey
              + "（积分挂在**真实会员**身份上，先由 15.6.1.1 建档再发积分）。");
    }

    // ---- 2. 生效中的获取规则（**租户数据**）
    RuleView rule = earnRule(source, blankToNull(input.ruleCode()), occurredAt);
    if (rule == null) {
      throw conflict(
          "还没有配置生效中的「"
              + labelOf(earnSource)
              + "」积分规则：积分规则决定拿多少分、积分有效期与每日上限，"
              + "**代码里没有写死的比率**。");
    }

    // ---- 3. 算积分 + 核对外部事实
    BigDecimal points = null;
    String referenceType = "";
    String referenceId = "";
    String note = input.note() == null ? "" : input.note().trim();
    switch (earnSource) {
      case PURCHASE -> {
        String orderId = blankToNull(input.referenceId());
        if (orderId == null) throw bad("消费获取必须给订单 id（referenceId）—— 积分依据真实订单发放。");
        OrderFact order = orders.orderById(orderId, bearer);
        if (order == null) {
          throw notFound(
              "订单不存在："
                  + orderId
                  + "（已扫完上游的全部分页，**确实没有**这一笔）。");
        }
        if (!"SUBMITTED".equalsIgnoreCase(nz(order.documentStatus()))) {
          throw bad("只有**已提交**的订单才能发积分（当前单据状态：" + nz(order.documentStatus()) + "）。");
        }
        assertOrderBelongsToMember(member, order);
        BigDecimal amount = order.grandTotalAmount();
        if (amount == null || amount.signum() <= 0) {
          throw bad("订单金额不可用（" + amount + "），不能据此发放积分。");
        }
        String currency = nz(order.currency()).trim().toUpperCase(Locale.ROOT);
        if (rule.thresholdAmount() != null) {
          if (rule.thresholdCurrency() == null
              || !rule.thresholdCurrency().equalsIgnoreCase(currency)) {
            throw bad(
                "订单币种（"
                    + currency
                    + "）与消费门槛的币种（"
                    + nz(rule.thresholdCurrency())
                    + "）不一致，跨币种的金额不能比。");
          }
          if (amount.compareTo(rule.thresholdAmount()) < 0) {
            throw bad(
                "订单金额 "
                    + amount.toPlainString()
                    + " "
                    + currency
                    + " 低于消费门槛 "
                    + rule.thresholdAmount().toPlainString()
                    + " "
                    + rule.thresholdCurrency()
                    + "。");
          }
        }
        BigDecimal perUnit = rule.pointsPerUnit();
        if (perUnit == null) {
          throw bad("消费获取规则必须给「每单位金额换多少积分」（points_per_unit）。");
        }
        points = points(amount.multiply(perUnit));
        referenceType = "ORDER";
        referenceId = orderId;
        if (note.isEmpty()) {
          note =
              "消费获取：订单 "
                  + nz(order.orderNo())
                  + "（"
                  + amount.toPlainString()
                  + " "
                  + currency
                  + "）× "
                  + perUnit.toPlainString()
                  + " 分/单位";
        }
      }
      case REVIEW -> {
        String reviewNo = blankToNull(input.referenceId());
        if (reviewNo == null) throw bad("评价获取必须给评价号（referenceId）。");
        Record review =
            sql.fetchOne(
                "select * from crm_product_review where tenant_id = ? and review_no = ?",
                currentTenant(),
                reviewNo);
        if (review == null) {
          throw notFound("评价不存在：" + reviewNo + "（评价获取的依据是**真实评价**）。");
        }
        String buyerRef = nz(review.get("buyer_ref", String.class)).trim();
        String customerRef = nz(member.get("customer_ref", String.class)).trim();
        if (!buyerRef.isEmpty() && !customerRef.isEmpty() && !buyerRef.equalsIgnoreCase(customerRef)) {
          throw bad(
              "评价的买家标识（"
                  + buyerRef
                  + "）与该会员的客户标识（"
                  + customerRef
                  + "）不一致，不能给这位会员发积分。");
        }
        if (rule.fixedPoints() == null) {
          throw bad("评价获取规则必须给「固定积分」（fixed_points）。");
        }
        points = points(rule.fixedPoints());
        referenceType = "REVIEW";
        referenceId = reviewNo;
        if (note.isEmpty()) {
          note = "评价获取：评价 " + reviewNo + "（" + review.get("rating", Integer.class) + " 星）";
        }
      }
      case CHECKIN -> {
        if (rule.fixedPoints() == null) {
          throw bad("签到获取规则必须给「固定积分」（fixed_points）。");
        }
        points = points(rule.fixedPoints());
        if (note.isEmpty()) note = "签到获取";
      }
      case MANUAL -> {
        if (input.points() == null) {
          throw bad("人工发放必须明确给出积分数（points）—— 没有写死的额度。");
        }
        points = points(input.points());
        if (note.isEmpty()) {
          throw bad("人工发放必须写明理由（note）—— 人工发的积分要能复核。");
        }
      }
    }

    if (points == null || points.signum() <= 0) {
      throw bad("按当前规则算出来的积分是 0 —— 没有可发放的积分。");
    }

    // ---- 4. 每日上限（同一会员同一 **UTC 自然日**）
    if (rule.maxPerDay() != null) {
      BigDecimal earnedToday = earnedOnUtcDay(memberKey, occurredAt);
      if (earnedToday.add(points).compareTo(rule.maxPerDay()) > 0) {
        throw conflict(
            "超出每日上限："
                + labelOf(earnSource)
                + " 今日已发 "
                + pointsText(earnedToday)
                + " 分，本次要发 "
                + pointsText(points)
                + " 分，上限 "
                + pointsText(rule.maxPerDay())
                + " 分（按 UTC 自然日统计）。");
      }
    }

    // ---- 5. 账户（不存在则按会员档案建一个）
    Record account = loadOrCreateAccount(member);

    // ---- 6. 余额增加（**条件更新**：余额永不为负由 DB 的 CHECK 兜底）
    OffsetDateTime at = now();
    Record afterRow =
        sql.fetchOne(
            "update crm_points_account set balance = balance + ?, earned_total = earned_total + ?,"
                + " last_earned_at = ?::timestamptz, updated_at = ?::timestamptz"
                + " where id = ? and tenant_id = ? returning balance",
            points,
            points,
            occurredAt,
            at,
            account.get("id", UUID.class),
            currentTenant());
    if (afterRow == null) {
      throw conflict("积分账户已不存在，无法发放积分。");
    }
    BigDecimal balanceAfter = afterRow.get("balance", BigDecimal.class);
    BigDecimal balanceBefore = balanceAfter.subtract(points);

    // ---- 7. 流水（**只追加**；带扣前 → 扣后余额）
    UUID ledgerId = UUID.randomUUID();
    sql.execute(
        "insert into crm_points_ledger"
            + "(id,tenant_id,account_id,account_key,member_key,direction,amount,balance_before,"
            + " balance_after,rule_id,reason_code,reference_type,reference_id,entitlement_id,"
            + " occurred_at,operator_id,note,created_at)"
            + " values (?,?,?,?,?,'EARN',?,?,?,?,?,?,?,null,?::timestamptz,?,?,?::timestamptz)",
        ledgerId,
        currentTenant(),
        account.get("id", UUID.class),
        account.get("account_key", String.class),
        memberKey,
        points,
        balanceBefore,
        balanceAfter,
        rule.id(),
        reasonOf(earnSource),
        referenceType,
        referenceId,
        occurredAt,
        actorId,
        note,
        at);

    // ---- 8. 批次（有效期载体）：**到期时刻 = 事件时刻 + 有效期月数**（绝对时刻）
    OffsetDateTime expiresAt = rule.validMonths() == null ? null : occurredAt.plusMonths(rule.validMonths());
    sql.execute(
        "insert into crm_points_lot"
            + "(id,tenant_id,account_id,account_key,member_key,source_ledger_id,amount,remaining,"
            + " expires_at,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        account.get("id", UUID.class),
        account.get("account_key", String.class),
        memberKey,
        ledgerId,
        points,
        points,
        expiresAt,
        at,
        at);

    return new CrmPointsEarnResult(
        account.get("id", UUID.class),
        account.get("account_key", String.class),
        memberKey,
        pointsText(points),
        pointsText(balanceBefore),
        pointsText(balanceAfter),
        momentText(expiresAt),
        rule.code(),
        "已发放 "
            + pointsText(points)
            + " 分（"
            + labelOf(earnSource)
            + "）。"
            + (expiresAt == null
                ? "该笔积分**永不过期**。"
                : "该笔积分于 " + momentText(expiresAt) + " 到期（按绝对时刻判定）。")
            + "余额 "
            + pointsText(balanceBefore)
            + " → "
            + pointsText(balanceAfter)
            + "。");
  }

  // ================================================================ 积分消耗（抵扣）

  /**
   * 积分消耗：订单抵扣。
   *
   * <p>🔴 四条纪律：
   *
   * <ol>
   *   <li>订单事实<b>回源核对</b>：抵扣必须落在**真实订单**上，且订单属于该会员。</li>
   *   <li>抵扣上限 = 订单金额 × 抵扣率上限 ÷ 单积分价值；请求超过上限 ⇒ <b>拒绝并给出两个数</b>。</li>
   *   <li>🔴 <b>不跨币种算</b>：订单币种与积分估值币种不一致 ⇒ 直接拒绝。</li>
   *   <li>扣减走<b>条件更新</b>：余额不足时更新 0 行 ⇒ 并发扣减不会都扣成功。</li>
   * </ol>
   */
  @Transactional
  public CrmPointsSpendResult spend(CrmPointsSpendInput input, UUID actorId, String bearer) {
    String memberKey = input.memberKey().trim();
    String orderId = input.orderId().trim();
    OffsetDateTime occurredAt =
        blankToNull(input.occurredAt()) == null ? now() : parseMoment(input.occurredAt(), "事件发生时刻");

    Record member =
        sql.fetchOne(
            "select * from crm_membership_profile where tenant_id = ? and member_key = ?",
            currentTenant(),
            memberKey);
    if (member == null) {
      throw notFound("会员档案不存在：" + memberKey + "（积分挂在**真实会员**身份上）。");
    }

    OrderFact order = orders.orderById(orderId, bearer);
    if (order == null) {
      throw notFound("订单不存在：" + orderId + "（已扫完上游的全部分页，**确实没有**这一笔）。");
    }
    if (!"SUBMITTED".equalsIgnoreCase(nz(order.documentStatus()))) {
      throw bad("只有**已提交**的订单才能用积分抵扣（当前单据状态：" + nz(order.documentStatus()) + "）。");
    }
    assertOrderBelongsToMember(member, order);
    BigDecimal amount = order.grandTotalAmount();
    if (amount == null || amount.signum() <= 0) {
      throw bad("订单金额不可用（" + amount + "），不能计算抵扣上限。");
    }
    String orderCurrency = nz(order.currency()).trim().toUpperCase(Locale.ROOT);

    // ---- 估值规则（单积分价值 + 负债币种）
    RuleView valuation = valuationRule(occurredAt);
    if (valuation == null) {
      throw conflict(
          "还没有配置生效中的**积分估值规则**（每 1 积分折算多少金额 + 负债币种）："
              + "抵扣上限与积分负债汇总都依赖它。");
    }
    if (valuation.pointValueRate() == null || valuation.pointValueRate().signum() <= 0) {
      throw conflict("积分估值规则的单积分价值必须大于 0。");
    }
    // 🔴 跨币种不能比：抵扣率作用在**订单币种**的金额上，折成积分需要**同币种**的单积分价值。
    if (!orderCurrency.equalsIgnoreCase(nz(valuation.liabilityCurrency()).trim())) {
      throw bad(
          "订单币种（"
              + orderCurrency
              + "）与积分估值币种（"
              + nz(valuation.liabilityCurrency())
              + "）不一致，跨币种不能算抵扣上限 —— "
              + "请把该币种的订单配一条同币种的估值规则，或改用同币种订单。");
    }

    // ---- 消耗规则（抵扣率上限）
    RuleView spendRule = spendRule(blankToNull(input.ruleCode()), occurredAt);
    if (spendRule == null) {
      throw conflict("还没有配置生效中的**积分消耗规则**（抵扣率上限）—— 抵扣上限是租户数据。");
    }
    if (spendRule.maxDeductRate() == null) {
      throw conflict("消耗规则必须给「抵扣率上限」（max_deduct_rate）。");
    }

    BigDecimal maxDeductAmount = amount.multiply(spendRule.maxDeductRate());
    BigDecimal maxDeductPoints =
        points(maxDeductAmount.divide(valuation.pointValueRate(), POINTS_SCALE, RoundingMode.DOWN));

    Record account = loadOrCreateAccount(member);
    BigDecimal available = availablePoints(account.get("id", UUID.class));

    // 🔴 **没给积分数 = 「能抵多少抵多少」**：取「抵扣率上限」与「当前可用积分」的较小值。
    //   显式给了积分数就照给的来 —— 显式请求满足不了必须拒绝（那是调用方的意思，
    //   悄悄少抵一部分会让人以为订单已经抵满了）。
    BigDecimal requested =
        input.points() == null ? maxDeductPoints.min(available) : points(input.points());
    if (requested.signum() <= 0) {
      throw conflict("该会员当前没有可用积分，抵扣不成立。");
    }
    if (requested.compareTo(maxDeductPoints) > 0) {
      throw conflict(
          "抵扣积分超过该订单允许的上限：要抵扣 "
              + pointsText(requested)
              + " 分，但上限是 "
              + pointsText(maxDeductPoints)
              + " 分（订单金额 "
              + amount.toPlainString()
              + " "
              + orderCurrency
              + " × 抵扣率上限 "
              + spendRule.maxDeductRate().toPlainString()
              + " ÷ 单积分价值 "
              + valuation.pointValueRate().toPlainString()
              + "）。");
    }
    if (spendRule.minDeductPoints() != null
        && requested.compareTo(spendRule.minDeductPoints()) < 0) {
      throw bad(
          "低于单笔最少抵扣积分（"
              + pointsText(spendRule.minDeductPoints())
              + " 分），这次抵扣不成立。");
    }

    DeductResult deduct =
        deduct(
            account,
            requested,
            "DEDUCT",
            spendRule.id(),
            "ORDER",
            orderId,
            null,
            occurredAt,
            actorId,
            input.note() == null ? "" : input.note().trim());

    return new CrmPointsSpendResult(
        account.get("id", UUID.class),
        account.get("account_key", String.class),
        memberKey,
        pointsText(deduct.points()),
        pointsText(deduct.balanceBefore()),
        pointsText(deduct.balanceAfter()),
        orderId,
        pointsText(maxDeductPoints),
        "已抵扣 "
            + pointsText(deduct.points())
            + " 分（订单 "
            + nz(order.orderNo())
            + "，抵扣率上限 "
            + spendRule.maxDeductRate().toPlainString()
            + "，单积分价值 "
            + valuation.pointValueRate().toPlainString()
            + " "
            + nz(valuation.liabilityCurrency())
            + "）。余额 "
            + pointsText(deduct.balanceBefore())
            + " → "
            + pointsText(deduct.balanceAfter())
            + "。"
            + (deduct.points().compareTo(maxDeductPoints) < 0
                ? "（抵扣率上限是 "
                    + pointsText(maxDeductPoints)
                    + " 分，但该会员当前只有 "
                    + pointsText(available)
                    + " 分可用，按可用积分抵扣。）"
                : ""));
  }

  // ================================================================ 权益兑换与核销

  /**
   * 权益兑换：用积分换一份权益（落一条核销记录，状态 REDEEMED）。
   *
   * <p>🔴 扣积分与落核销记录在**同一个事务**里：扣了积分却没有记录，
   * 或有记录却没扣积分，都是「账实不符」。
   */
  @Transactional
  public CrmPointsRedeemResult redeem(CrmPointsRedeemInput input, UUID actorId) {
    String memberKey = input.memberKey().trim();
    String entitlementCode = input.entitlementCode().trim().toUpperCase(Locale.ROOT);
    OffsetDateTime occurredAt =
        blankToNull(input.occurredAt()) == null ? now() : parseMoment(input.occurredAt(), "兑换时刻");

    Record member =
        sql.fetchOne(
            "select * from crm_membership_profile where tenant_id = ? and member_key = ?",
            currentTenant(),
            memberKey);
    if (member == null) {
      throw notFound("会员档案不存在：" + memberKey + "（积分挂在**真实会员**身份上）。");
    }

    Record entitlement =
        sql.fetchOne(
            "select * from crm_points_entitlement where tenant_id = ? and entitlement_code = ?"
                + " and enabled = true and effective_from <= ?::timestamptz"
                + " and (effective_to is null or effective_to > ?::timestamptz)",
            currentTenant(),
            entitlementCode,
            occurredAt,
            occurredAt);
    if (entitlement == null) {
      throw notFound("没有生效中的权益「" + entitlementCode + "」。");
    }
    Integer stock = entitlement.get("stock", Integer.class);
    if (stock != null && stock <= 0) {
      throw conflict("权益「" + entitlementCode + "」已兑完（库存 0）。");
    }
    Integer perMemberLimit = entitlement.get("per_member_limit", Integer.class);
    if (perMemberLimit != null) {
      long used =
          sql.fetchOne(
                  "select count(*) from crm_points_redemption"
                      + " where tenant_id = ? and member_key = ? and entitlement_id = ?",
                  currentTenant(),
                  memberKey,
                  entitlement.get("id", UUID.class))
              .get(0, Long.class);
      if (used >= perMemberLimit) {
        throw conflict(
            "该会员已兑换 "
                + used
                + " 份权益「"
                + entitlementCode
                + "」，达到每人限兑 "
                + perMemberLimit
                + " 份的上限。");
      }
    }

    BigDecimal pointCost = points(entitlement.get("point_cost", BigDecimal.class));
    Record account = loadOrCreateAccount(member);
    DeductResult deduct =
        deduct(
            account,
            pointCost,
            "REDEEM",
            null,
            "ENTITLEMENT",
            entitlementCode,
            entitlement.get("id", UUID.class),
            occurredAt,
            actorId,
            input.note() == null ? "" : input.note().trim());

    Map<String, Object> snapshot = benefitSnapshot(entitlement);
    String redemptionNo = nextRedemptionNo();
    UUID redemptionId = UUID.randomUUID();
    sql.execute(
        "insert into crm_points_redemption"
            + "(id,tenant_id,redemption_no,account_id,account_key,member_key,entitlement_id,"
            + " entitlement_code,entitlement_name,point_cost,benefit_snapshot,status,redeemed_at,"
            + " used_at,order_ref,operator_id,note,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?::jsonb,'REDEEMED',?::timestamptz,null,'',?,?,"
            + " ?::timestamptz,?::timestamptz)",
        redemptionId,
        currentTenant(),
        redemptionNo,
        account.get("id", UUID.class),
        account.get("account_key", String.class),
        memberKey,
        entitlement.get("id", UUID.class),
        nz(entitlement.get("entitlement_code", String.class)),
        nz(entitlement.get("entitlement_name", String.class)),
        pointCost,
        toJson(snapshot),
        occurredAt,
        actorId,
        input.note() == null ? "" : input.note().trim(),
        now(),
        now());

    // 库存扣减（**条件更新**：库存不足时更新 0 行 ⇒ 并发兑换不会超卖）
    if (stock != null) {
      Record stockRow =
          sql.fetchOne(
              "update crm_points_entitlement set stock = stock - 1, updated_at = ?::timestamptz"
                  + " where id = ? and tenant_id = ? and stock > 0 returning stock",
              now(),
              entitlement.get("id", UUID.class),
              currentTenant());
      if (stockRow == null) {
        throw conflict("权益「" + entitlementCode + "」刚刚被兑完了，请重试。");
      }
    }

    return new CrmPointsRedeemResult(
        redemptionId,
        redemptionNo,
        account.get("id", UUID.class),
        account.get("account_key", String.class),
        memberKey,
        nz(entitlement.get("entitlement_code", String.class)),
        nz(entitlement.get("entitlement_name", String.class)),
        pointsText(pointCost),
        pointsText(deduct.balanceBefore()),
        pointsText(deduct.balanceAfter()),
        snapshot,
        "已兑换权益「"
            + nz(entitlement.get("entitlement_name", String.class))
            + "」，消耗 "
            + pointsText(pointCost)
            + " 分（核销号 "
            + redemptionNo
            + "，状态：已兑换待核销）。余额 "
            + pointsText(deduct.balanceBefore())
            + " → "
            + pointsText(deduct.balanceAfter())
            + "。");
  }

  /** 权益核销：把已兑换的权益标成已核销（**单向**：REDEEMED → USED）。 */
  @Transactional
  public CrmPointsRedemptionRow use(UUID redemptionId, CrmPointsUseInput input, UUID actorId) {
    Record record =
        sql.fetchOne(
            "select * from crm_points_redemption where tenant_id = ? and id = ?",
            currentTenant(),
            redemptionId);
    if (record == null) throw notFound("权益核销记录不存在：" + redemptionId);
    if ("USED".equalsIgnoreCase(nz(record.get("status", String.class)))) {
      throw conflict("该权益已核销过，不能重复核销（核销号 " + nz(record.get("redemption_no", String.class)) + "）。");
    }
    OffsetDateTime at = now();
    // 🔴 **参数必须显式 cast**：写成 `case when ? is null then ...` 时，
    //   PostgreSQL **无法推断**那个占位符的类型（"could not determine data type of parameter"），
    //   整个语句会失败。`?::varchar` 把类型钉死，null 才有意义。
    sql.execute(
        "update crm_points_redemption set status = 'USED', used_at = ?::timestamptz,"
            + " order_ref = coalesce(?::varchar, order_ref),"
            + " note = coalesce(nullif(?::varchar, ''), note),"
            + " updated_at = ?::timestamptz where tenant_id = ? and id = ?",
        at,
        blankToNull(input == null ? null : input.orderRef()),
        blankToNull(input == null ? null : input.note()),
        at,
        currentTenant(),
        redemptionId);
    // 🔴 **重新读一次**：上面那条 record 是更新**前**的快照，直接返回它的话
    //   状态还是 REDEEMED，调用方会以为没核销成功。
    Record updated =
        sql.fetchOne(
            "select * from crm_points_redemption where tenant_id = ? and id = ?",
            currentTenant(),
            redemptionId);
    return redemptionRow(updated == null ? record : updated);
  }

  /** 权益核销记录列表（真分页 + 真筛选）。 */
  public CrmPointsRedemptionPage redemptionPage(
      String memberKey, String entitlementCode, String status, int page, int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where tenant_id = ?");
    binds.add(currentTenant());
    if (blankToNull(memberKey) != null) {
      where.append(" and member_key = ?");
      binds.add(memberKey.trim());
    }
    if (blankToNull(entitlementCode) != null) {
      where.append(" and entitlement_code = ?");
      binds.add(entitlementCode.trim().toUpperCase(Locale.ROOT));
    }
    if (blankToNull(status) != null) {
      where.append(" and status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    long total =
        sql.fetchOne("select count(*) from crm_points_redemption" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<Record> records =
        sql.fetch(
            "select * from crm_points_redemption"
                + where
                + " order by redeemed_at desc, id desc limit ? offset ?",
            pageBinds.toArray());
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmPointsRedemptionPage(
        redemptionRows(records), safePage, safeSize, total, totalPages);
  }

  // ================================================================ 到期结算

  /**
   * 到期结算：把**已过期**批次的剩余积分清零，并留一条 EXPIRE 流水。
   *
   * <p>🔴 有效期按**绝对时刻**判定（{@code expires_at <= now()}，两者都是 timestamptz）。
   * 结算时账户余额与批次剩余量**同步减**，两者始终相等。
   */
  @Transactional
  public CrmPointsExpireResult expire(UUID actorId) {
    OffsetDateTime at = now();
    List<Record> lots =
        sql.fetch(
            "select l.* from crm_points_lot l"
                + " join crm_points_account a on a.id = l.account_id and a.tenant_id = l.tenant_id"
                + " where l.tenant_id = ? and l.remaining > 0"
                + " and l.expires_at is not null and l.expires_at <= ?::timestamptz"
                + " order by l.expires_at asc, l.id asc",
            currentTenant(),
            at);
    int count = 0;
    BigDecimal totalExpired = BigDecimal.ZERO;
    for (Record lot : lots) {
      BigDecimal remaining = lot.get("remaining", BigDecimal.class);
      if (remaining == null || remaining.signum() <= 0) continue;
      UUID accountId = lot.get("account_id", UUID.class);
      String accountKey = nz(lot.get("account_key", String.class));
      String memberKey = nz(lot.get("member_key", String.class));
      // 账户余额扣减（**条件更新**：余额不足 ⇒ 账实不符，直接报错而不是悄悄核销掉）
      Record afterRow =
          sql.fetchOne(
              "update crm_points_account set balance = balance - ?, expired_total = expired_total + ?,"
                  + " updated_at = ?::timestamptz"
                  + " where id = ? and tenant_id = ? and balance >= ? returning balance",
              remaining,
              remaining,
              at,
              accountId,
              currentTenant(),
              remaining);
      if (afterRow == null) {
        throw conflict(
            "积分账户「"
                + accountKey
                + "」的余额与积分批次对不上（余额不足 "
                + remaining.toPlainString()
                + " 分）：账实不符，不能静默核销。");
      }
      BigDecimal balanceAfter = afterRow.get("balance", BigDecimal.class);
      BigDecimal balanceBefore = balanceAfter.add(remaining);
      sql.execute(
          "update crm_points_lot set remaining = 0, updated_at = ?::timestamptz"
              + " where id = ? and tenant_id = ?",
          at,
          lot.get("id", UUID.class),
          currentTenant());
      sql.execute(
          "insert into crm_points_ledger"
              + "(id,tenant_id,account_id,account_key,member_key,direction,amount,balance_before,"
              + " balance_after,rule_id,reason_code,reference_type,reference_id,entitlement_id,"
              + " occurred_at,operator_id,note,created_at)"
              + " values (?,?,?,?,?,'EXPIRE',?,?,?,null,'EXPIRE','','',null,?::timestamptz,?,?,"
              + " ?::timestamptz)",
          UUID.randomUUID(),
          currentTenant(),
          accountId,
          accountKey,
          memberKey,
          remaining,
          balanceBefore,
          balanceAfter,
          at,
          actorId,
          "到期结算：批次于 " + momentText(lot.get("expires_at", OffsetDateTime.class)) + " 过期",
          at);
      count++;
      totalExpired = totalExpired.add(remaining);
    }
    BigDecimal lifetimeExpired =
        sql.fetchOne(
                "select coalesce(sum(expired_total),0) from crm_points_account where tenant_id = ?",
                currentTenant())
            .get(0, BigDecimal.class);
    return new CrmPointsExpireResult(
        count,
        pointsText(totalExpired),
        pointsText(lifetimeExpired),
        count == 0
            ? "没有到期的积分批次（有效期按**绝对时刻**判定）。"
            : "已结算 "
                + count
                + " 个到期批次，共 "
                + pointsText(totalExpired)
                + " 分过期。余额与批次剩余量同步减，两者始终相等。");
  }

  // ================================================================ 积分负债汇总

  /**
   * 积分负债汇总 —— **这是交给 12 财务的东西**。
   *
   * <p>🔴 蓝图加粗句：「**未兑付积分是负债**，需在财务侧有对应科目或备忘，不能只记在业务库」。
   * 本模块<b>只输出汇总</b>：{@code bookedInFinance} 恒为 {@code false} ——
   * <b>不代劳</b>在财务侧建科目，<b>不假装</b>已经入账。
   */
  public CrmPointsLiability liability() {
    OffsetDateTime at = now();
    RuleView valuation = valuationRule(at);
    Record totals =
        sql.fetchOne(
            "select coalesce(sum(balance),0) as outstanding,"
                + " count(*) filter (where balance > 0) as accounts"
                + " from crm_points_account where tenant_id = ?",
            currentTenant());
    BigDecimal outstanding = totals.get("outstanding", BigDecimal.class);
    long accounts = totals.get("accounts", Long.class);
    OffsetDateTime horizon = at.plusDays(EXPIRING_WINDOW_DAYS);
    Record expiring =
        sql.fetchOne(
            "select coalesce(sum(remaining),0) as points from crm_points_lot"
                + " where tenant_id = ? and remaining > 0"
                + " and expires_at is not null and expires_at <= ?::timestamptz",
            currentTenant(),
            horizon);
    BigDecimal expiringPoints = expiring.get("points", BigDecimal.class);

    BigDecimal rate = valuation == null ? null : valuation.pointValueRate();
    String currency = valuation == null ? null : nz(valuation.liabilityCurrency()).trim();
    BigDecimal amount =
        rate == null || outstanding == null
            ? null
            : outstanding.multiply(rate).setScale(MONEY_MIN_SCALE, RoundingMode.HALF_UP);

    return new CrmPointsLiability(
        momentText(at),
        rate == null ? null : rate.toPlainString(),
        currency,
        pointsText(outstanding),
        amount == null ? null : amount.toPlainString(),
        accounts,
        pointsText(expiringPoints),
        EXPIRING_WINDOW_DAYS + " 天内到期",
        false,
        "🔴 12 财务侧**未交付**积分负债科目 / 备忘：本模块**只输出汇总**，"
            + "**未**在财务侧建科目、**未**入账。业务库里有一张表 ≠ 账上有一笔负债 —— "
            + "未兑付积分在账上仍是负债，需要 12 财务侧补科目后消化。",
        valuation == null
            ? "🔴 还没有配置生效中的**积分估值规则**（每 1 积分折算多少金额 + 负债币种），"
                + "所以负债**金额**算不出来（未兑付积分数 " + pointsText(outstanding) + " 仍然有）。"
                + "配了估值规则之后这个汇总才有金额。"
            : "未兑付积分 "
                + pointsText(outstanding)
                + " 分 × 单积分价值 "
                + rate.toPlainString()
                + " "
                + currency
                + " = 负债 "
                + (amount == null ? "—" : amount.toPlainString())
                + " "
                + currency
                + "（"
                + accounts
                + " 个账户有余额；另有 "
                + pointsText(expiringPoints)
                + " 分在 "
                + EXPIRING_WINDOW_DAYS
                + " 天内到期）。");
  }

  // ================================================================ 扣减内核

  /** 一次扣减的结果（扣前余额 → 扣后余额）。 */
  private record DeductResult(
      BigDecimal points, BigDecimal balanceBefore, BigDecimal balanceAfter) {}

  /**
   * 扣减内核 —— <b>扣减在显式 SQL 里看得见</b>。
   *
   * <p>🔴 三层保证：
   *
   * <ol>
   *   <li><b>条件更新</b>：{@code update ... where balance >= ?} ——
   *       余额不足时<b>更新 0 行</b>。PostgreSQL 在行锁上重新判定谓词 ⇒
   *       两个并发请求不会都扣成功（这是并发扣减的 DB 层保证）。</li>
   *   <li><b>表级 CHECK</b>：{@code balance >= 0} —— 任何绕过条件更新的写法也进不来。</li>
   *   <li><b>批次先到期先消耗</b>：{@code remaining} 只减不增（DB 触发器）。</li>
   * </ol>
   */
  private DeductResult deduct(
      Record account,
      BigDecimal points,
      String reasonCode,
      UUID ruleId,
      String referenceType,
      String referenceId,
      UUID entitlementId,
      OffsetDateTime occurredAt,
      UUID actorId,
      String note) {
    UUID accountId = account.get("id", UUID.class);
    String accountKey = account.get("account_key", String.class);
    String memberKey = account.get("member_key", String.class);

    // ---- 1. 预检（给一句人话；真正的保证在第 3 步的条件更新）
    BigDecimal available = availablePoints(accountId);
    if (available.compareTo(points) < 0) {
      throw conflict(
          "可用积分不足：需要 "
              + pointsText(points)
              + " 分，当前可用 "
              + pointsText(available)
              + " 分（余额 "
              + pointsText(account.get("balance", BigDecimal.class))
              + " 分）。");
    }

    // ---- 2. 账户余额扣减（**条件更新** = 并发扣减的 DB 层保证）
    //   🔴 **先锁账户行，再动批次**：所有改余额的路径都先锁账户行 ⇒
    //      ① 批次状态此刻是稳定的（没有别的扣减能插进来）；
    //      ② 不会出现「批次被扣光、余额却没扣」的中间态；
    //      ③ 所有路径的加锁顺序一致 ⇒ 不会死锁。
    //   余额不足 ⇒ **更新 0 行** ⇒ 拒绝（PostgreSQL 在行锁上重新判定谓词，
    //   所以两个并发请求不会都扣成功）。
    OffsetDateTime at = now();
    Record afterRow =
        sql.fetchOne(
            "update crm_points_account set balance = balance - ?, spent_total = spent_total + ?,"
                + " last_spent_at = ?::timestamptz, updated_at = ?::timestamptz"
                + " where id = ? and tenant_id = ? and balance >= ? returning balance",
            points,
            points,
            occurredAt,
            at,
            accountId,
            currentTenant(),
            points);
    if (afterRow == null) {
      throw conflict("积分余额不足或已被并发扣减，请重试。");
    }
    BigDecimal balanceAfter = afterRow.get("balance", BigDecimal.class);
    BigDecimal balanceBefore = balanceAfter.add(points);

    // ---- 3. 批次消耗（**先到期先消耗**）
    BigDecimal left = points;
    List<Record> lots =
        sql.fetch(
            "select * from crm_points_lot where tenant_id = ? and account_id = ? and remaining > 0"
                + " order by (expires_at is null), expires_at asc, id asc",
            currentTenant(),
            accountId);
    for (Record lot : lots) {
      if (left.signum() <= 0) break;
      BigDecimal remaining = lot.get("remaining", BigDecimal.class);
      BigDecimal take = remaining.min(left);
      int updated =
          sql.execute(
              "update crm_points_lot set remaining = remaining - ?, updated_at = ?::timestamptz"
                  + " where id = ? and tenant_id = ? and remaining >= ?",
              take,
              now(),
              lot.get("id", UUID.class),
              currentTenant(),
              take);
      if (updated == 0) {
        // 🔴 扣不到就是扣不到：不能把 left 照减（那会让流水与批次对不上）。
        throw conflict("积分批次已被并发消耗，请重试。");
      }
      left = left.subtract(take);
    }
    if (left.signum() > 0) {
      throw conflict(
          "积分批次与账户余额对不上：还有 "
              + pointsText(left)
              + " 分没能从批次里扣掉（账实不符）。");
    }

    // ---- 4. 流水（**只追加**，带扣前 → 扣后余额）
    sql.execute(
        "insert into crm_points_ledger"
            + "(id,tenant_id,account_id,account_key,member_key,direction,amount,balance_before,"
            + " balance_after,rule_id,reason_code,reference_type,reference_id,entitlement_id,"
            + " occurred_at,operator_id,note,created_at)"
            + " values (?,?,?,?,?,'SPEND',?,?,?,?,?,?,?,?,?::timestamptz,?,?,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        accountId,
        accountKey,
        memberKey,
        points,
        balanceBefore,
        balanceAfter,
        ruleId,
        reasonCode,
        referenceType,
        referenceId,
        entitlementId,
        occurredAt,
        actorId,
        note,
        at);

    return new DeductResult(points, balanceBefore, balanceAfter);
  }

  /** 账户的可用积分（= 各批次剩余量之和）。 */
  private BigDecimal availablePoints(UUID accountId) {
    return sql.fetchOne(
            "select coalesce(sum(remaining),0) from crm_points_lot"
                + " where tenant_id = ? and account_id = ? and remaining > 0",
            currentTenant(),
            accountId)
        .get(0, BigDecimal.class);
  }

  /** 同一会员同一 **UTC 自然日**已发放的积分（每日上限口径）。 */
  private BigDecimal earnedOnUtcDay(String memberKey, OffsetDateTime at) {
    OffsetDateTime start = at.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS);
    OffsetDateTime end = start.plusDays(1);
    return sql.fetchOne(
            "select coalesce(sum(amount),0) from crm_points_ledger"
                + " where tenant_id = ? and member_key = ? and direction = 'EARN'"
                + " and occurred_at >= ?::timestamptz and occurred_at < ?::timestamptz",
            currentTenant(),
            memberKey,
            start,
            end)
        .get(0, BigDecimal.class);
  }

  /** 取（或建）会员的积分账户。 */
  private Record loadOrCreateAccount(Record member) {
    String memberKey = nz(member.get("member_key", String.class));
    Record existing =
        sql.fetchOne(
            "select * from crm_points_account where tenant_id = ? and account_key = ?",
            currentTenant(),
            memberKey);
    if (existing != null) return existing;
    UUID id = UUID.randomUUID();
    OffsetDateTime at = now();
    // 🔴 **on conflict do nothing**：两个并发请求给同一位会员首次发积分时，
    //   只有一个能建出账户，另一个安静地复用它 ——
    //   靠 catch 唯一约束冲突来兜底是**不行**的（PostgreSQL 里事务一旦报错就整体回滚）。
    sql.execute(
        "insert into crm_points_account"
            + "(id,tenant_id,account_key,member_key,customer_ref,source_shop,customer_name,"
            + " balance,earned_total,spent_total,expired_total,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,0,0,0,0,?::timestamptz,?::timestamptz)"
            + " on conflict (tenant_id, account_key) do nothing",
        id,
        currentTenant(),
        memberKey,
        memberKey,
        nz(member.get("customer_ref", String.class)),
        nz(member.get("source_shop", String.class)),
        nz(member.get("customer_name", String.class)),
        at,
        at);
    return sql.fetchOne(
        "select * from crm_points_account where tenant_id = ? and account_key = ?",
        currentTenant(),
        memberKey);
  }

  /** 订单必须属于该会员（按**来源店铺**核对 —— 合并后的会员有多家店铺）。 */
  private void assertOrderBelongsToMember(Record member, OrderFact order) {
    List<String> shops = readStringList(member.get("source_shops", String.class));
    String shop = nz(order.orderSource()).trim();
    if (shop.isEmpty() || shops.isEmpty()) return;
    for (String candidate : shops) {
      if (candidate.equalsIgnoreCase(shop)) return;
    }
    throw bad(
        "这笔订单来自店铺「"
            + shop
            + "」，不属于该会员的来源店铺（"
            + String.join(" / ", shops)
            + "）—— 积分只能记在真正买过的人名下。");
  }

  // ================================================================ 规则解析

  /** 生效中的获取规则（按来源 + 可选规则代码）。 */
  private RuleView earnRule(String source, String ruleCode, OffsetDateTime at) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where =
        new StringBuilder(
            " where tenant_id = ? and rule_type = 'EARN' and enabled = true and earn_source = ?"
                + " and effective_from <= ?::timestamptz"
                + " and (effective_to is null or effective_to > ?::timestamptz)");
    binds.add(currentTenant());
    binds.add(source);
    binds.add(at);
    binds.add(at);
    if (ruleCode != null) {
      where.append(" and rule_code = ?");
      binds.add(ruleCode.trim().toUpperCase(Locale.ROOT));
    }
    Record row =
        sql.fetchOne(
            "select * from crm_points_rule" + where + " order by effective_from desc, id asc limit 1",
            binds.toArray());
    return row == null ? null : ruleView(row);
  }

  /** 生效中的消耗规则（抵扣率上限）。 */
  private RuleView spendRule(String ruleCode, OffsetDateTime at) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where =
        new StringBuilder(
            " where tenant_id = ? and rule_type = 'SPEND' and enabled = true"
                + " and effective_from <= ?::timestamptz"
                + " and (effective_to is null or effective_to > ?::timestamptz)");
    binds.add(currentTenant());
    binds.add(at);
    binds.add(at);
    if (ruleCode != null) {
      where.append(" and rule_code = ?");
      binds.add(ruleCode.trim().toUpperCase(Locale.ROOT));
    }
    Record row =
        sql.fetchOne(
            "select * from crm_points_rule" + where + " order by effective_from desc, id asc limit 1",
            binds.toArray());
    return row == null ? null : ruleView(row);
  }

  /**
   * 生效中的估值规则（单积分价值 + 负债币种）。
   *
   * <p>DB 层保证一个租户同时只有一条开着的估值规则（部分唯一索引）⇒ 口径唯一。
   */
  private RuleView valuationRule(OffsetDateTime at) {
    Record row =
        sql.fetchOne(
            "select * from crm_points_rule where tenant_id = ? and rule_type = 'VALUATION'"
                + " and enabled = true and effective_from <= ?::timestamptz"
                + " and (effective_to is null or effective_to > ?::timestamptz)"
                + " order by effective_from desc, id asc limit 1",
            currentTenant(),
            at,
            at);
    return row == null ? null : ruleView(row);
  }

  /** 规则行 → 视图对象。 */
  private RuleView ruleView(Record r) {
    return new RuleView(
        r.get("id", UUID.class),
        nz(r.get("rule_code", String.class)),
        nz(r.get("rule_name", String.class)),
        nz(r.get("rule_type", String.class)),
        blankToNull(r.get("earn_source", String.class)),
        r.get("points_per_unit", BigDecimal.class),
        r.get("fixed_points", BigDecimal.class),
        r.get("threshold_amount", BigDecimal.class),
        blankToNull(r.get("threshold_currency", String.class)),
        r.get("valid_months", Integer.class),
        r.get("max_per_day", BigDecimal.class),
        r.get("max_deduct_rate", BigDecimal.class),
        r.get("min_deduct_points", BigDecimal.class),
        r.get("point_value_rate", BigDecimal.class),
        blankToNull(r.get("liability_currency", String.class)));
  }

  /** 一条积分规则的内部视图。 */
  private record RuleView(
      UUID id,
      String code,
      String name,
      String type,
      String earnSource,
      BigDecimal pointsPerUnit,
      BigDecimal fixedPoints,
      BigDecimal thresholdAmount,
      String thresholdCurrency,
      Integer validMonths,
      BigDecimal maxPerDay,
      BigDecimal maxDeductRate,
      BigDecimal minDeductPoints,
      BigDecimal pointValueRate,
      String liabilityCurrency) {}

  // ================================================================ 行映射

  private List<CrmPointsAccountRow> accountRows(List<Record> records) {
    return records.stream().map(this::accountRow).toList();
  }

  private CrmPointsAccountRow accountRow(Record r) {
    return new CrmPointsAccountRow(
        r.get("id", UUID.class),
        nz(r.get("account_key", String.class)),
        nz(r.get("member_key", String.class)),
        nz(r.get("customer_ref", String.class)),
        nz(r.get("source_shop", String.class)),
        nz(r.get("customer_name", String.class)),
        pointsText(r.get("balance", BigDecimal.class)),
        pointsText(r.get("earned_total", BigDecimal.class)),
        pointsText(r.get("spent_total", BigDecimal.class)),
        pointsText(r.get("expired_total", BigDecimal.class)),
        momentText(r.get("last_earned_at", OffsetDateTime.class)),
        momentText(r.get("last_spent_at", OffsetDateTime.class)),
        momentText(r.get("created_at", OffsetDateTime.class)));
  }

  private CrmPointsLotRow lotRow(Record r, OffsetDateTime at) {
    OffsetDateTime expiresAt = r.get("expires_at", OffsetDateTime.class);
    return new CrmPointsLotRow(
        r.get("id", UUID.class),
        nz(r.get("account_key", String.class)),
        pointsText(r.get("amount", BigDecimal.class)),
        pointsText(r.get("remaining", BigDecimal.class)),
        momentText(expiresAt),
        expiresAt != null && !expiresAt.isAfter(at),
        momentText(r.get("created_at", OffsetDateTime.class)));
  }

  private List<CrmPointsLedgerRow> ledgerRows(List<Record> records) {
    return records.stream().map(this::ledgerRow).toList();
  }

  private CrmPointsLedgerRow ledgerRow(Record r) {
    String direction = nz(r.get("direction", String.class));
    String reason = nz(r.get("reason_code", String.class));
    return new CrmPointsLedgerRow(
        r.get("id", UUID.class),
        nz(r.get("account_key", String.class)),
        nz(r.get("member_key", String.class)),
        nz(r.get("customer_name", String.class)),
        direction,
        directionLabel(direction),
        pointsText(r.get("amount", BigDecimal.class)),
        pointsText(r.get("balance_before", BigDecimal.class)),
        pointsText(r.get("balance_after", BigDecimal.class)),
        blankToNull(r.get("rule_code", String.class)),
        reason,
        reasonLabel(reason),
        blankToNull(r.get("reference_type", String.class)),
        blankToNull(r.get("reference_id", String.class)),
        momentText(r.get("occurred_at", OffsetDateTime.class)),
        blankToNull(
            r.get("operator_id", UUID.class) == null ? null : r.get("operator_id", UUID.class).toString()),
        nz(r.get("note", String.class)));
  }

  private List<CrmPointsRuleRow> ruleRows(List<Record> records) {
    OffsetDateTime at = now();
    return records.stream().map(r -> ruleRow(r, at)).toList();
  }

  private CrmPointsRuleRow ruleRow(Record r) {
    return ruleRow(r, now());
  }

  private CrmPointsRuleRow ruleRow(Record r, OffsetDateTime at) {
    OffsetDateTime from = r.get("effective_from", OffsetDateTime.class);
    OffsetDateTime to = r.get("effective_to", OffsetDateTime.class);
    boolean effectiveNow =
        from != null && !from.isAfter(at) && (to == null || to.isAfter(at));
    return new CrmPointsRuleRow(
        r.get("id", UUID.class),
        nz(r.get("rule_code", String.class)),
        nz(r.get("rule_name", String.class)),
        nz(r.get("rule_type", String.class)),
        blankToNull(r.get("earn_source", String.class)),
        rateText(r.get("points_per_unit", BigDecimal.class)),
        pointsText(r.get("fixed_points", BigDecimal.class)),
        moneyText(r.get("threshold_amount", BigDecimal.class)),
        blankToNull(r.get("threshold_currency", String.class)),
        r.get("valid_months", Integer.class),
        pointsText(r.get("max_per_day", BigDecimal.class)),
        rateText(r.get("max_deduct_rate", BigDecimal.class)),
        pointsText(r.get("min_deduct_points", BigDecimal.class)),
        rateText(r.get("point_value_rate", BigDecimal.class)),
        blankToNull(r.get("liability_currency", String.class)),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        momentText(from),
        momentText(to),
        effectiveNow,
        nz(r.get("note", String.class)),
        momentText(r.get("created_at", OffsetDateTime.class)));
  }

  private List<CrmPointsEntitlementRow> entitlementRows(List<Record> records) {
    OffsetDateTime at = now();
    return records.stream().map(r -> entitlementRow(r, at)).toList();
  }

  private CrmPointsEntitlementRow entitlementRow(Record r) {
    return entitlementRow(r, now());
  }

  private CrmPointsEntitlementRow entitlementRow(Record r, OffsetDateTime at) {
    OffsetDateTime from = r.get("effective_from", OffsetDateTime.class);
    OffsetDateTime to = r.get("effective_to", OffsetDateTime.class);
    boolean effectiveNow =
        from != null && !from.isAfter(at) && (to == null || to.isAfter(at));
    String benefitType = nz(r.get("benefit_type", String.class));
    return new CrmPointsEntitlementRow(
        r.get("id", UUID.class),
        nz(r.get("entitlement_code", String.class)),
        nz(r.get("entitlement_name", String.class)),
        pointsText(r.get("point_cost", BigDecimal.class)),
        benefitType,
        benefitTypeLabel(benefitType),
        rateText(r.get("benefit_value", BigDecimal.class)),
        blankToNull(r.get("benefit_currency", String.class)),
        r.get("stock", Integer.class),
        r.get("per_member_limit", Integer.class),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        momentText(from),
        momentText(to),
        effectiveNow,
        nz(r.get("note", String.class)),
        momentText(r.get("created_at", OffsetDateTime.class)));
  }

  private List<CrmPointsRedemptionRow> redemptionRows(List<Record> records) {
    return records.stream().map(this::redemptionRow).toList();
  }

  private CrmPointsRedemptionRow redemptionRow(Record r) {
    String status = nz(r.get("status", String.class));
    return new CrmPointsRedemptionRow(
        r.get("id", UUID.class),
        nz(r.get("redemption_no", String.class)),
        nz(r.get("account_key", String.class)),
        nz(r.get("member_key", String.class)),
        nz(r.get("entitlement_code", String.class)),
        nz(r.get("entitlement_name", String.class)),
        pointsText(r.get("point_cost", BigDecimal.class)),
        readJsonObject(r.get("benefit_snapshot", String.class)),
        status,
        redemptionStatusLabel(status),
        momentText(r.get("redeemed_at", OffsetDateTime.class)),
        momentText(r.get("used_at", OffsetDateTime.class)),
        blankToNull(r.get("order_ref", String.class)),
        nz(r.get("note", String.class)));
  }

  /** 权益内容快照（兑换当时是什么，就是什么）。 */
  private Map<String, Object> benefitSnapshot(Record entitlement) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("benefitType", nz(entitlement.get("benefit_type", String.class)));
    BigDecimal value = entitlement.get("benefit_value", BigDecimal.class);
    snapshot.put("benefitValue", value == null ? null : value.toPlainString());
    snapshot.put(
        "benefitCurrency", blankToNull(entitlement.get("benefit_currency", String.class)));
    snapshot.put("entitlementCode", nz(entitlement.get("entitlement_code", String.class)));
    snapshot.put("entitlementName", nz(entitlement.get("entitlement_name", String.class)));
    return snapshot;
  }

  // ================================================================ 工具

  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  /**
   * 当前时刻。
   *
   * <p>🔴 取自**数据库的 now()**：相对时间口径（规则是否在生效期内、积分是否到期）
   * 一律用 DB 时钟，不拿 JVM 墙钟与 DB 时钟比大小。
   */
  private OffsetDateTime now() {
    return sql.fetchOne("select now()").get(0, OffsetDateTime.class);
  }

  /** 核销号：PR-yyyyMMdd-000001（序列单独授权，见 V442 授权段）。 */
  private String nextRedemptionNo() {
    var row =
        sql.fetchOne(
            "select nextval('crm_points_redemption_no_seq') as seq,"
                + " to_char(now() at time zone 'utc','YYYYMMDD') as day");
    long seq = row.get("seq", Long.class);
    String day = row.get("day", String.class);
    return "PR-" + day + "-" + String.format("%06d", seq);
  }

  private static String nz(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static String blankToNull(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /**
   * 时刻 → 出参文本（<b>统一换算成 UTC</b>，秒必写，秒以下有值才写）。
   */
  private static String momentText(OffsetDateTime moment) {
    return moment == null ? null : MOMENT_TEXT.format(moment);
  }

  private static OffsetDateTime parseMoment(String value, String label) {
    try {
      return OffsetDateTime.parse(value.trim());
    } catch (RuntimeException e) {
      throw bad(label + "必须是 ISO-8601 带偏移量的时刻（例：2026-10-10T08:00:00+08:00）。");
    }
  }

  /** 积分 → 统一标度（numeric(19,6)）。 */
  private static BigDecimal points(BigDecimal value) {
    return value == null ? null : value.setScale(POINTS_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 积分 → 十进制文本（去尾零；<b>不四舍五入</b>）。
   *
   * <p>出参显示的必须是「库里那个数」：{@code 300.000000} → {@code 300}、{@code 0.500000} → {@code 0.5}。
   */
  private static String pointsText(BigDecimal value) {
    if (value == null) return null;
    BigDecimal stripped = value.stripTrailingZeros();
    if (stripped.scale() < 0) stripped = stripped.setScale(0);
    return stripped.toPlainString();
  }

  /**
   * 金额 → 十进制文本（去尾零 + <b>至少两位小数</b>，<b>不四舍五入</b>）。
   */
  private static String moneyText(BigDecimal value) {
    if (value == null) return null;
    BigDecimal stripped = value.stripTrailingZeros();
    int scale = Math.max(stripped.scale(), MONEY_MIN_SCALE);
    return stripped.setScale(scale, RoundingMode.UNNECESSARY).toPlainString();
  }

  /** 比率 → 十进制文本（去尾零）。 */
  private static String rateText(BigDecimal value) {
    if (value == null) return null;
    BigDecimal stripped = value.stripTrailingZeros();
    if (stripped.signum() == 0) return "0";
    return stripped.toPlainString();
  }

  /**
   * 约束违例的**真实理由**（DB 说的那句，不是我们猜的那句）。
   *
   * <p>为什么要它：{@code DataIntegrityViolationException} 把「唯一键冲突」、
   * 「NOT NULL 没给」、「CHECK 不满足」全装在同一个异常里。服务层若一律说成
   * 「已经有一条了」，就会把真问题盖成误导人的话 —— 运营会去查错的地方。
   * 这里把 DB 的原话带出来（截断到 300 字，避免把整条堆栈塞进响应体）。
   */
  private static String dbReason(DataIntegrityViolationException e) {
    Throwable cause = e.getMostSpecificCause();
    String message = cause == null ? e.getMessage() : cause.getMessage();
    if (message == null || message.isBlank()) return "触发了数据库约束";
    String text = message.replaceAll("\\s+", " ").trim();
    return text.length() <= 300 ? text : text.substring(0, 300) + "…";
  }

  private static String toJson(Object value) {    try {
      return JSON.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> readJsonObject(String raw) {
    if (raw == null || raw.isBlank()) return Map.of();
    try {
      JsonNode root = JSON.readTree(raw);
      if (root == null || !root.isObject()) return Map.of();
      Map<String, Object> out = new LinkedHashMap<>();
      root.fields().forEachRemaining(e -> out.put(e.getKey(), toPlainValue(e.getValue())));
      return out;
    } catch (Exception e) {
      return Map.of();
    }
  }

  /** jsonb 节点 → Java 值（**数字一律 BigDecimal，绝不 double**）。 */
  private static Object toPlainValue(JsonNode node) {
    if (node == null || node.isNull()) return null;
    if (node.isNumber()) return node.decimalValue();
    if (node.isTextual()) return node.textValue();
    if (node.isBoolean()) return node.booleanValue();
    if (node.isArray()) {
      List<Object> out = new ArrayList<>();
      for (JsonNode item : node) out.add(toPlainValue(item));
      return out;
    }
    if (node.isObject()) {
      Map<String, Object> out = new LinkedHashMap<>();
      node.fields().forEachRemaining(e -> out.put(e.getKey(), toPlainValue(e.getValue())));
      return out;
    }
    return node.asText();
  }

  private static List<String> readStringList(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    try {
      JsonNode parsed = JSON.readTree(raw);
      if (parsed == null || !parsed.isArray()) return List.of();
      List<String> out = new ArrayList<>();
      for (JsonNode item : parsed) {
        if (item != null && !item.isNull()) out.add(item.asText());
      }
      return out;
    } catch (Exception e) {
      return List.of();
    }
  }

  private static String directionLabel(String direction) {
    return switch (direction) {
      case "EARN" -> "获取";
      case "SPEND" -> "消耗";
      case "EXPIRE" -> "过期";
      default -> direction;
    };
  }

  private static String reasonLabel(String reason) {
    return switch (reason) {
      case "PURCHASE" -> "消费获取";
      case "REVIEW" -> "评价获取";
      case "CHECKIN" -> "签到获取";
      case "MANUAL" -> "人工发放";
      case "DEDUCT" -> "订单抵扣";
      case "REDEEM" -> "权益兑换";
      case "EXPIRE" -> "到期过期";
      default -> reason;
    };
  }

  private static String benefitTypeLabel(String benefitType) {
    return switch (benefitType) {
      case "DISCOUNT" -> "折扣";
      case "FREE_SHIPPING" -> "免邮";
      case "PHYSICAL_GIFT" -> "实物";
      case "COUPON" -> "优惠券";
      default -> benefitType;
    };
  }

  private static String redemptionStatusLabel(String status) {
    return switch (status) {
      case "REDEEMED" -> "已兑换待核销";
      case "USED" -> "已核销";
      default -> status;
    };
  }

  private static String labelOf(PointsEarnSource source) {
    return source.label();
  }

  private static String reasonOf(PointsEarnSource source) {
    return switch (source) {
      case PURCHASE -> "PURCHASE";
      case REVIEW -> "REVIEW";
      case CHECKIN -> "CHECKIN";
      case MANUAL -> "MANUAL";
    };
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static String dataScopeOf(AccessService.Access actor) {
    String scope = "ALL";
    if (actor.grants() != null) {
      for (AccessService.Grant grant : actor.grants()) {
        if ("ALL".equals(grant.scopeMode())) return "ALL";
        if ("SELF".equals(grant.scopeMode())) scope = "SELF";
        else if ("DEPARTMENT".equals(grant.scopeMode()) && "ALL".equals(scope)) scope = "DEPARTMENT";
      }
    }
    return scope;
  }
}
