package com.starcon.trade;

import static com.starcon.trade.SupplierRatingModels.*;
import static com.starcon.trade.SupplierRatingRules.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
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
 * 6.1.1.3 供应商评级。
 *
 * <p>蓝图原文：「**职责**：按周期（默认月度）自动统计供应商表现：交期达成率、质量合格率、
 * 响应时长、价格竞争力、售后配合度，综合打分并支持人工调整权重。**输入**：收货事件时间与质检结果；
 * 采购单沟通记录；权重配置。**输出**：评级结果与排名；评级变动历史；影响采购建议的默认供应商优先级。
 * **关系**：上游 = 6.4.2 质检、6.3.2.1 跟单；下游 = 3.6.2 报价优先级、6.2.1.2 补货建议
 * （优先选高分供应商）。**评级必须由客观数据算出**，人工加权分需单独标记，
 * 否则会变成纯主观打分。」
 *
 * <p><b>本类的五条纪律</b>：
 *
 * <ol>
 *   <li><b>客观分与人工分分开存</b>：{@code objective_score} 只由数据算出，
 *       {@code manual_adjustment} 是人工调整量，{@code final_score} 是两者之和。
 *       DB 层 CHECK 钉死「最终分 = 客观分 + 人工调整」，应用层想混都混不了。</li>
 *   <li><b>不可用 ≠ 0 分</b>：五个维度的分母为 0 时该维度<b>不参与合成</b>，
 *       并把「为什么不可用」写进结果行。</li>
 *   <li><b>合成按可用维度归一</b>：只算出三个维度时综合分仍是 0~100 的可比值，
 *       同时报出 {@code availableWeight}（覆盖了多少权重）。</li>
 *   <li><b>跨服务只经授权 API</b>：市场报价在 catalog 的独立库，只经
 *       {@link SupplierRatingQuotePort} 读，不跨库 SQL、不跨库 FK、不跨库视图。</li>
 *   <li><b>金额与比率一律 BigDecimal</b>，时间一律 {@code OffsetDateTime} 绑定
 *       （values 位置写 {@code ?::timestamptz}）。</li>
 * </ol>
 */
@Service
public class SupplierRatingService {

  /** 读权限。 */
  public static final String READ_PERMISSION = "trade.ratings.read";

  /** 写权限（算评级、改权重、人工加权分）。 */
  public static final String UPDATE_PERMISSION = "trade.ratings.update";

  /** 单页最大条数。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 结果行的读出口字段（列表与详情共用一份定义，免得两处漂移）。 */
  private static final String RESULT_COLUMNS =
      "r.id,r.period_type,r.period_start,r.period_end,r.supplier_ref_id,r.supplier_code,r.supplier_name,"
          + "r.objective_score,r.manual_adjustment,r.final_score,r.grade,r.rank_in_period,r.available_weight,"
          + "r.unavailable_dimensions,r.unavailable_reason,"
          + "r.delivery_score,r.quality_score,r.response_score,r.price_score,r.service_score,"
          + "r.delivery_on_time_rate,r.delivery_arrived_count,r.quality_pass_rate,"
          + "r.quality_inspected_quantity,r.quality_qualified_quantity,"
          + "r.response_avg_hours,r.response_sample_count,r.price_index,r.price_sample_count,"
          + "r.price_comparable_quantity,r.service_cooperation_rate,r.service_claim_count,"
          + "r.manually_adjusted,r.manual_adjusted_by,r.manual_adjusted_at,r.manual_adjustment_reason,"
          + "r.computed_at,r.version";

  /** 变更历史的读出口字段。 */
  private static final String CHANGE_COLUMNS =
      "c.id,c.supplier_ref_id,c.supplier_code,c.supplier_name,c.period_type,c.period_start,c.period_end,"
          + "c.previous_grade,c.previous_score,c.current_grade,c.current_score,c.score_delta,"
          + "c.grade_changed,c.change_type,c.reason,c.operator_id,c.occurred_at";

  /** 调整明细的读出口字段。 */
  private static final String ADJUSTMENT_COLUMNS =
      "a.id,a.result_id,a.supplier_ref_id,a.period_type,a.period_start,a.period_end,a.objective_score,"
          + "a.previous_score,a.adjusted_score,a.adjustment,a.reason,a.adjusted_by,a.adjusted_at";

  private final DSLContext sql;
  private final SupplierRatingQuotePort quotes;

  public SupplierRatingService(DSLContext sql, SupplierRatingQuotePort quotes) {
    this.sql = sql;
    this.quotes = quotes;
  }

  // ================================================================ 能力声明 / 词表

  /**
   * 能力声明：五个维度各自有没有依据、上下游接上了没有。
   *
   * <p>存在的理由与 6.1.2.3 一致：把「哪些输入真有依据」「哪些下游还没接上」「口径是什么」
   * 显式报出来，而不是让调用方从 404 或空数组里去猜。
   */
  public SupplierRatingCapabilities capabilities() {
    return new SupplierRatingCapabilities(
        List.of(
            new SupplierRatingCapability(
                "DELIVERY",
                "交期达成率",
                true,
                null,
                "数据源：6.3.2.2 的到货流水 + 6.3.2.1 的交期变更（承诺日口径与跟单延迟统计逐字同源）"),
            new SupplierRatingCapability(
                "QUALITY",
                "质量合格率",
                true,
                null,
                "数据源：6.4.2.1 的来料质检单（按数量比算，只算已判定完成的单）"),
            new SupplierRatingCapability(
                "RESPONSE",
                "响应时长",
                true,
                null,
                "数据源：6.3.2.1 的跟单记录（口径：上一次未答复 → 本次答复 的等待窗口）"),
            new SupplierRatingCapability(
                "PRICE",
                "价格竞争力",
                true,
                null,
                "数据源：catalog 3.6.2 报价台账（跨服务只读）+ 6.3.1.1 采购单行；只比同币种"),
            new SupplierRatingCapability(
                "SERVICE",
                "售后配合度",
                true,
                null,
                "数据源：11.2.3.3 的供应商索赔与协商轮次（分子 = 供应商侧参与过协商的索赔数）"),
            new SupplierRatingCapability(
                "WEIGHT_POLICY",
                "权重配置",
                true,
                null,
                "租户级权重（五个维度之和精确为 1）+ 响应目标小时 + 等级分档，可改"),
            new SupplierRatingCapability(
                "MANUAL_ADJUSTMENT",
                "人工加权分",
                true,
                null,
                "人工调整单独留痕（追加式、理由必填），且**不改客观分**"),
            new SupplierRatingCapability(
                "RANKING",
                "评级结果与排名",
                true,
                null,
                "每供应商每周期一行，排名按最终分倒序、同分按供应商 id 升序（稳定）"),
            new SupplierRatingCapability(
                "CHANGE_HISTORY",
                "评级变动历史",
                true,
                null,
                "跨周期滚动 / 同周期重算 / 人工调整三类来源，追加式留痕"),
            new SupplierRatingCapability(
                "PRIORITY_OUTPUT",
                "默认供应商优先级",
                true,
                "6.2.1.2",
                "本模块产出优先级清单（供补货建议消费）；补货建议还要看 MOQ / 箱规 / 起订额，"
                    + "所以本模块**不替它选供应商**")),
        List.of("DELIVERY", "QUALITY", "RESPONSE", "PRICE", "SERVICE"),
        List.of(
            "评级是**按周期**算的：默认月度，周期区间是闭区间（含首尾两天）。",
            "维度不可用时它**不参与合成**，且响应里逐条报出「为什么不可用」；"
                + "「不可用」与「0 分」在界面上必须长得不一样。",
            "人工加权分**单独标记**：客观分只由数据算出，人工只改最终分，两者在结果行上并存。",
            "价格竞争力**只比同币种**：本服务没有汇率表（汇率是 12 财务的资产），"
                + "跨币种比大小是错的。"));
  }

  /** 词表选项（前端下拉的数据源，与后端同源）。 */
  public SupplierRatingOptions options() {
    List<SupplierRatingOption> periodTypes = new ArrayList<>();
    for (SupplierRatingPeriodType t : SupplierRatingPeriodType.values()) {
      periodTypes.add(new SupplierRatingOption(t.name(), t.label(), "评级周期"));
    }
    List<SupplierRatingOption> dimensions = new ArrayList<>();
    for (SupplierRatingDimension d : SupplierRatingDimension.values()) {
      dimensions.add(new SupplierRatingOption(d.name(), d.label(), "评级维度"));
    }
    List<SupplierRatingOption> grades = new ArrayList<>();
    for (SupplierRatingGrade g : SupplierRatingGrade.values()) {
      grades.add(new SupplierRatingOption(g.name(), g.label(), "评级等级"));
    }
    List<SupplierRatingOption> changeTypes = new ArrayList<>();
    for (SupplierRatingChangeType c : SupplierRatingChangeType.values()) {
      changeTypes.add(new SupplierRatingOption(c.name(), c.label(), "变动来源"));
    }
    return new SupplierRatingOptions(periodTypes, dimensions, grades, changeTypes);
  }

  // ================================================================ 权重配置

  /**
   * 权重配置。
   *
   * <p>租户**没配**时返回系统默认口径并标 {@code configured=false} ——
   * 「用默认值」与「我们配的就是这个值」在界面上必须能分开看见。
   */
  public SupplierRatingPolicy policy(SupplierRatingPeriodType periodType) {
    SupplierRatingPeriodType type = periodType == null ? SupplierRatingPeriodType.MONTHLY : periodType;
    Record r =
        sql.fetchOne(
            "select delivery_weight,quality_weight,response_weight,price_weight,service_weight,"
                + "response_target_hours,grade_a_min,grade_b_min,grade_c_min,note,version"
                + " from supplier_rating_weight_policy where tenant_id=?::uuid and period_type=?",
            currentTenant(),
            type.name());
    if (r == null) {
      return new SupplierRatingPolicy(
          type,
          DEFAULT_DELIVERY_WEIGHT,
          DEFAULT_QUALITY_WEIGHT,
          DEFAULT_RESPONSE_WEIGHT,
          DEFAULT_PRICE_WEIGHT,
          DEFAULT_SERVICE_WEIGHT,
          DEFAULT_RESPONSE_TARGET_HOURS,
          DEFAULT_GRADE_A_MIN,
          DEFAULT_GRADE_B_MIN,
          DEFAULT_GRADE_C_MIN,
          false,
          DEFAULT_DELIVERY_WEIGHT,
          DEFAULT_QUALITY_WEIGHT,
          DEFAULT_RESPONSE_WEIGHT,
          DEFAULT_PRICE_WEIGHT,
          DEFAULT_SERVICE_WEIGHT,
          0L,
          "租户还没有配置权重，当前用的是系统默认口径");
    }
    return new SupplierRatingPolicy(
        type,
        weight(r.get("delivery_weight", BigDecimal.class)),
        weight(r.get("quality_weight", BigDecimal.class)),
        weight(r.get("response_weight", BigDecimal.class)),
        weight(r.get("price_weight", BigDecimal.class)),
        weight(r.get("service_weight", BigDecimal.class)),
        r.get("response_target_hours", Integer.class) == null
            ? DEFAULT_RESPONSE_TARGET_HOURS
            : r.get("response_target_hours", Integer.class),
        r.get("grade_a_min", BigDecimal.class),
        r.get("grade_b_min", BigDecimal.class),
        r.get("grade_c_min", BigDecimal.class),
        true,
        DEFAULT_DELIVERY_WEIGHT,
        DEFAULT_QUALITY_WEIGHT,
        DEFAULT_RESPONSE_WEIGHT,
        DEFAULT_PRICE_WEIGHT,
        DEFAULT_SERVICE_WEIGHT,
        r.get("version", Long.class) == null ? 0L : r.get("version", Long.class),
        r.get("note", String.class));
  }

  /**
   * 修改权重配置（租户级）。
   *
   * <p>权重和必须精确等于 1：服务层与 DB CHECK 各拦一道 ——
   * 只判一次的话绕过服务层直连 SQL 就能写进一组让综合分失真的权重。
   */
  public SupplierRatingPolicy putPolicy(SupplierRatingPolicyInput input) {
    if (input == null) throw bad("权重配置不能为空");
    SupplierRatingPeriodType type =
        input.periodType() == null ? SupplierRatingPeriodType.MONTHLY : input.periodType();
    Weights w =
        new Weights(
            input.deliveryWeight(),
            input.qualityWeight(),
            input.responseWeight(),
            input.priceWeight(),
            input.serviceWeight());
    List<String> problems = validateWeights(w);
    if (!problems.isEmpty()) throw bad(String.join("；", problems));
    if (input.responseTargetHours() == null || input.responseTargetHours() < 1) {
      throw bad("响应目标小时数必须大于 0");
    }
    BigDecimal a = input.gradeAMin() == null ? DEFAULT_GRADE_A_MIN : input.gradeAMin();
    BigDecimal b = input.gradeBMin() == null ? DEFAULT_GRADE_B_MIN : input.gradeBMin();
    BigDecimal c = input.gradeCMin() == null ? DEFAULT_GRADE_C_MIN : input.gradeCMin();
    if (!(a.compareTo(b) > 0 && b.compareTo(c) > 0)) {
      throw bad("等级分档必须严格递减：A 线 > B 线 > C 线，否则会出现既是 A 又是 C 的重叠区间");
    }
    int rows =
        sql.execute(
            "insert into supplier_rating_weight_policy(id,tenant_id,period_type,delivery_weight,"
                + "quality_weight,response_weight,price_weight,service_weight,response_target_hours,"
                + "grade_a_min,grade_b_min,grade_c_min,note,version,created_at,updated_at)"
                + " values (?::uuid,?::uuid,?::varchar,?::numeric,?::numeric,?::numeric,?::numeric,?::numeric,"
                + "?::int,?::numeric,?::numeric,?::numeric,?::varchar,0,now(),now())"
                + " on conflict (tenant_id,period_type) do update set"
                + " delivery_weight=excluded.delivery_weight,quality_weight=excluded.quality_weight,"
                + "response_weight=excluded.response_weight,price_weight=excluded.price_weight,"
                + "service_weight=excluded.service_weight,response_target_hours=excluded.response_target_hours,"
                + "grade_a_min=excluded.grade_a_min,grade_b_min=excluded.grade_b_min,"
                + "grade_c_min=excluded.grade_c_min,note=excluded.note,"
                + "version=supplier_rating_weight_policy.version+1,updated_at=now()",
            UUID.randomUUID(),
            currentTenant(),
            type.name(),
            w.delivery(),
            w.quality(),
            w.response(),
            w.price(),
            w.service(),
            input.responseTargetHours(),
            a,
            b,
            c,
            input.note() == null ? "" : input.note());
    if (rows <= 0) throw bad("权重配置没写进去");
    return policy(type);
  }

  // ================================================================ 概览

  /** 概览（不受筛选与分页影响）。 */
  public SupplierRatingOverview overview(SupplierRatingPeriodType periodType, LocalDate periodStart) {
    SupplierRatingPeriodType type = periodType == null ? SupplierRatingPeriodType.MONTHLY : periodType;
    Period period = periodOf(type, periodStart == null ? today() : periodStart);
    Record r =
        sql.fetchOne(
            "select count(*) as rated_count,"
                + " count(*) filter (where grade='A') as grade_a,"
                + " count(*) filter (where grade='B') as grade_b,"
                + " count(*) filter (where grade='C') as grade_c,"
                + " count(*) filter (where grade='D') as grade_d,"
                + " avg(final_score) as avg_score, avg(objective_score) as avg_objective,"
                + " count(*) filter (where manually_adjusted) as adjusted_count,"
                + " count(*) filter (where available_weight < 1) as partial_count"
                + " from supplier_rating_result"
                + " where tenant_id=?::uuid and period_type=? and period_start=?::date",
            currentTenant(),
            type.name(),
            period.start());
    if (r == null) {
      return new SupplierRatingOverview(
          type,
          period.start(),
          period.end(),
          0,
          0,
          0,
          0,
          0,
          null,
          null,
          0,
          0,
          "本期还没有算过评级：先执行一次「计算本期评级」");
    }
    BigDecimal avg = r.get("avg_score", BigDecimal.class);
    BigDecimal avgObjective = r.get("avg_objective", BigDecimal.class);
    return new SupplierRatingOverview(
        type,
        period.start(),
        period.end(),
        count(r, "rated_count"),
        count(r, "grade_a"),
        count(r, "grade_b"),
        count(r, "grade_c"),
        count(r, "grade_d"),
        avg == null ? null : avg.setScale(2, RoundingMode.HALF_UP),
        avgObjective == null ? null : avgObjective.setScale(2, RoundingMode.HALF_UP),
        count(r, "adjusted_count"),
        count(r, "partial_count"),
        "概览只统计**已算出来**的评级行；「覆盖权重不足 100%」的家数单列出来，"
            + "因为它们的分数是按可用维度归一后的可比值");
  }

  // ================================================================ 列表 / 详情

  /** 评级列表（真分页 + 真筛选 + 真排序）。 */
  public SupplierRatingPage pageRatings(SupplierRatingQuery query) {
    int page = Math.max(query.page(), 0);
    int size = Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);
    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where r.tenant_id=?::uuid");
    binds.add(currentTenant());
    if (query.periodType() != null) {
      where.append(" and r.period_type=?::varchar");
      binds.add(query.periodType().name());
    }
    if (query.periodStart() != null) {
      where.append(" and r.period_start=?::date");
      binds.add(query.periodStart());
    }
    if (query.supplierRefId() != null) {
      where.append(" and r.supplier_ref_id=?::uuid");
      binds.add(query.supplierRefId());
    }
    if (query.grade() != null) {
      where.append(" and r.grade=?::varchar");
      binds.add(query.grade().name());
    }
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.append(" and (r.supplier_code ilike ? escape '!' or r.supplier_name ilike ? escape '!')");
      String kw = "%" + query.keyword().trim().replace("!", "!!") + "%";
      binds.add(kw);
      binds.add(kw);
    }
    // 排序键是闭集白名单：排序键直接拼进 SQL，拼错一个字母就是 500 而不是 400。
    String sort = sortColumn(query.sort());
    String direction = Boolean.TRUE.equals(query.desc()) ? "desc" : "asc";
    long total =
        sql.fetchOne("select count(*) from supplier_rating_result r" + where, binds.toArray())
            .get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            "select " + RESULT_COLUMNS + " from supplier_rating_result r" + where
                + " order by " + sort + " " + direction + ", r.supplier_ref_id asc"
                + " limit ? offset ?",
            concat(binds, List.of(size, (long) page * size)).toArray());
    List<SupplierRatingRow> items = new ArrayList<>();
    for (Record r : rows) items.add(toRow(r));
    return new SupplierRatingPage(
        items,
        total,
        page,
        size,
        "计数与本页取行引同一处筛选条件；排序键是闭集白名单（不接受任意表达式）。");
  }

  /** 单个评级详情：结果 + 人工调整留痕 + 变动历史。 */
  public SupplierRatingDetail rating(UUID resultId) {
    Record r =
        sql.fetchOne(
            "select " + RESULT_COLUMNS + " from supplier_rating_result r"
                + " where r.tenant_id=?::uuid and r.id=?::uuid",
            currentTenant(),
            resultId);
    if (r == null) throw notFound("评级结果不存在");
    UUID supplierRefId = r.get("supplier_ref_id", UUID.class);
    List<SupplierRatingAdjustmentRow> adjustments = new ArrayList<>();
    for (Record a :
        sql.fetch(
            "select " + ADJUSTMENT_COLUMNS + " from supplier_rating_adjustment a"
                + " where a.tenant_id=?::uuid and a.result_id=?::uuid"
                + " order by a.adjusted_at desc, a.id desc",
            currentTenant(),
            resultId)) {
      adjustments.add(toAdjustment(a));
    }
    List<SupplierRatingChangeRow> changes = new ArrayList<>();
    for (Record c :
        sql.fetch(
            "select " + CHANGE_COLUMNS + " from supplier_rating_change c"
                + " where c.tenant_id=?::uuid and c.supplier_ref_id=?::uuid"
                + " order by c.occurred_at desc, c.id desc limit 50",
            currentTenant(),
            supplierRefId)) {
      changes.add(toChange(c));
    }
    return new SupplierRatingDetail(
        toRow(r),
        adjustments,
        changes,
        "调整留痕与变动历史都是追加式的：它们回答「当时发生了什么」，不是「现在是什么」。");
  }

  /** 评级变动历史（真分页 + 真筛选）。 */
  public SupplierRatingChangePage changes(
      SupplierRatingPeriodType periodType,
      UUID supplierRefId,
      SupplierRatingChangeType changeType,
      int page,
      int size) {
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where c.tenant_id=?::uuid");
    binds.add(currentTenant());
    if (periodType != null) {
      where.append(" and c.period_type=?::varchar");
      binds.add(periodType.name());
    }
    if (supplierRefId != null) {
      where.append(" and c.supplier_ref_id=?::uuid");
      binds.add(supplierRefId);
    }
    if (changeType != null) {
      where.append(" and c.change_type=?::varchar");
      binds.add(changeType.name());
    }
    long total =
        sql.fetchOne("select count(*) from supplier_rating_change c" + where, binds.toArray())
            .get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            "select " + CHANGE_COLUMNS + " from supplier_rating_change c" + where
                + " order by c.occurred_at desc, c.id desc limit ? offset ?",
            concat(binds, List.of(s, (long) p * s)).toArray());
    List<SupplierRatingChangeRow> items = new ArrayList<>();
    for (Record c : rows) items.add(toChange(c));
    return new SupplierRatingChangePage(
        items, total, p, s, "变动历史只记「真的变了」的：分数与等级都没动时不留行。");
  }

  // ================================================================ 默认供应商优先级

  /**
   * 默认供应商优先级（供 6.2.1.2 补货建议消费）。
   *
   * <p>只给「排序 + 依据」，<b>不替下游选供应商</b>：补货建议还要看 MOQ / 箱规 / 起订额 / 交期，
   * 替它选完就是越界。
   */
  public SupplierRatingPriorityList priorities(
      SupplierRatingPeriodType periodType, LocalDate periodStart) {
    SupplierRatingPeriodType type = periodType == null ? SupplierRatingPeriodType.MONTHLY : periodType;
    Period period = periodOf(type, periodStart == null ? today() : periodStart);
    List<Record> rows =
        sql.fetch(
            "select " + RESULT_COLUMNS + " from supplier_rating_result r"
                + " where r.tenant_id=?::uuid and r.period_type=?::varchar and r.period_start=?::date"
                + " order by r.final_score desc, r.supplier_ref_id asc",
            currentTenant(),
            type.name(),
            period.start());
    List<SupplierRatingPriorityRow> items = new ArrayList<>();
    int rank = 0;
    for (Record r : rows) {
      rank++;
      SupplierRatingRow row = toRow(r);
      items.add(
          new SupplierRatingPriorityRow(
              rank,
              row.supplierRefId(),
              row.supplierCode(),
              row.supplierName(),
              row.finalScore(),
              row.objectiveScore(),
              row.grade(),
              row.manuallyAdjusted(),
              row.periodStart(),
              row.periodEnd(),
              row.unavailableDimensions()));
    }
    return new SupplierRatingPriorityList(
        type,
        period.start(),
        period.end(),
        items,
        "优先级 = 本期最终分从高到低（同分按供应商 id 升序，稳定）。"
            + "带「人工调整」标记的行在消费方必须能看见：那是**被人改过**的分数。");
  }

  // ================================================================ 计算

  /**
   * 计算一个周期的评级。
   *
   * <p>幂等：同一周期重算<b>覆盖</b>同一行（唯一键 = 租户 + 周期类型 + 周期起日 + 供应商），
   * 而不是堆出第二份事实。分数或等级真的变了才留一行变动历史。
   *
   * <p>⚠️ <b>重算不抹掉人工调整</b>：人工调整是一条**长期有效的调整量**，
   * 重算只改客观分，最终分 = 新客观分 + 原调整量（DB 层 CHECK 钉死这个等式）。
   * 抹掉它等于「谁改过就白改了」，而人工调整明细表里还留着那一行。
   */
  public SupplierRatingComputeResult compute(
      SupplierRatingPeriodType periodType, LocalDate periodStart, String bearer) {
    SupplierRatingPeriodType type = periodType == null ? SupplierRatingPeriodType.MONTHLY : periodType;
    Period period = periodOf(type, periodStart == null ? today() : periodStart);
    SupplierRatingPolicy policy = policy(type);
    Weights weights =
        new Weights(
            policy.deliveryWeight(),
            policy.qualityWeight(),
            policy.responseWeight(),
            policy.priceWeight(),
            policy.serviceWeight());
    List<String> problems = validateWeights(weights);
    if (!problems.isEmpty()) {
      throw bad("权重配置不合法，评级算不了：" + String.join("；", problems));
    }

    OffsetDateTime from = period.start().atStartOfDay().atOffset(ZoneOffset.UTC);
    OffsetDateTime to = period.end().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

    Map<UUID, SupplierFacts> facts = collectSuppliers(from, to);

    // 价格维度的市场报价：读不到就**只把价格这一维标成不可用**，不拖垮整次评级。
    SupplierRatingQuotePort.QuoteSnapshot snapshot = null;
    String priceUnavailableReason = null;
    try {
      snapshot = quotes.effectiveQuotes(period.end(), bearer);
    } catch (RuntimeException e) {
      priceUnavailableReason = e.getMessage();
    }

    List<Composed> composed = new ArrayList<>();
    for (SupplierFacts f : facts.values()) {
      composed.add(compose(f, weights, policy, snapshot, priceUnavailableReason));
    }

    int created = 0;
    int updated = 0;
    int changes = 0;
    for (Composed c : composed) {
      if (c.objectiveScore() == null) {
        // 一个维度都算不出来 ⇒ 没有分数就没有评级，不写行（写了也是一行假事实）。
        continue;
      }
      // 先取「被覆盖掉的那一行」：变动历史要比的就是它。
      Record samePeriodOld =
          sql.fetchOne(
              "select final_score, grade, manual_adjustment from supplier_rating_result"
                  + " where tenant_id=?::uuid and period_type=?::varchar and period_start=?::date"
                  + "   and supplier_ref_id=?::uuid",
              currentTenant(), type.name(), period.start(), c.facts().supplierRefId());
      BigDecimal manualAdjustment =
          samePeriodOld == null
              ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
              : samePeriodOld.get("manual_adjustment", BigDecimal.class);
      if (manualAdjustment == null) manualAdjustment = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
      BigDecimal finalScore = adjustedFinalScore(c.objectiveScore(), manualAdjustment);
      SupplierRatingGrade grade =
          grade(finalScore, policy.gradeAMin(), policy.gradeBMin(), policy.gradeCMin());
      boolean isNew = writeResult(c, period, type, finalScore, grade, manualAdjustment);
      if (isNew) {
        created++;
      } else {
        updated++;
      }
      changes += recordChange(c, period, type, grade, samePeriodOld);
    }
    // 排名统一在最后刷一次：保证「名次」与「本期的行」永远一一对应。
    rerank(period, type);

    List<String> unavailable = new ArrayList<>();
    for (Composed c : composed) {
      for (SupplierRatingDimension d : c.unavailable()) {
        if (!unavailable.contains(d.name())) unavailable.add(d.name());
      }
    }
    return new SupplierRatingComputeResult(
        type,
        period.start(),
        period.end(),
        composed.size(),
        created,
        updated,
        changes,
        unavailable,
        "评级是**按周期**算的，本次覆盖 " + period.start() + " ~ " + period.end() + "（闭区间）。"
            + "没有依据的维度被排除在合成之外（不是 0 分），排除清单见 unavailableDimensions。");
  }

  /** 人工加权分（理由必填；客观分不变，只改最终分）。 */
  public SupplierRatingRow adjust(
      UUID resultId, SupplierRatingAdjustmentInput input, UUID operatorId) {
    if (input == null) throw bad("人工调整不能为空");
    if (input.reason() == null || input.reason().isBlank()) {
      throw bad("人工调整必须写理由：没有理由的人工调整等于没留痕，三个月后无法解释这个名次");
    }
    if (input.adjustment() == null || input.adjustment().signum() == 0) {
      throw bad("调整量不能为 0：一次「调整了 0 分」是噪声，会让人以为有人评过这家供应商");
    }
    Record r =
        sql.fetchOne(
            "select " + RESULT_COLUMNS + " from supplier_rating_result r"
                + " where r.tenant_id=?::uuid and r.id=?::uuid for update",
            currentTenant(),
            resultId);
    if (r == null) throw notFound("评级结果不存在");
    BigDecimal objective = r.get("objective_score", BigDecimal.class);
    BigDecimal previous = r.get("final_score", BigDecimal.class);
    BigDecimal finalScore = adjustedFinalScore(objective, input.adjustment());
    SupplierRatingPolicy policy = policy(periodTypeOf(r.get("period_type", String.class)));
    SupplierRatingGrade newGrade =
        grade(finalScore, policy.gradeAMin(), policy.gradeBMin(), policy.gradeCMin());
    SupplierRatingGrade previousGrade = gradeOf(r.get("grade", String.class));
    UUID operator = operatorId == null ? UUID.randomUUID() : operatorId;

    sql.execute(
        "insert into supplier_rating_adjustment(id,tenant_id,result_id,supplier_ref_id,period_type,"
            + "period_start,period_end,objective_score,previous_score,adjusted_score,adjustment,reason,"
            + "adjusted_by,adjusted_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::uuid,?::varchar,?::date,?::date,?::numeric,?::numeric,"
            + "?::numeric,?::numeric,?::varchar,?::uuid,now())",
        UUID.randomUUID(),
        currentTenant(),
        resultId,
        r.get("supplier_ref_id", UUID.class),
        r.get("period_type", String.class),
        r.get("period_start", LocalDate.class),
        r.get("period_end", LocalDate.class),
        objective,
        previous,
        finalScore,
        input.adjustment(),
        input.reason().trim(),
        operator);

    sql.execute(
        "update supplier_rating_result set final_score=?::numeric, grade=?::varchar,"
            + "manual_adjustment=?::numeric,manually_adjusted=true,manual_adjusted_by=?::uuid,"
            + "manual_adjusted_at=now(),manual_adjustment_reason=?::varchar,version=version+1,"
            + "updated_at=now() where tenant_id=?::uuid and id=?::uuid",
        finalScore,
        newGrade == null ? null : newGrade.name(),
        input.adjustment(),
        operator,
        input.reason().trim(),
        currentTenant(),
        resultId);

    // 人工调整也要进变动历史（否则「名次为什么变了」在变动历史里看不到）。
    sql.execute(
        "insert into supplier_rating_change(id,tenant_id,supplier_ref_id,supplier_code,supplier_name,"
            + "period_type,period_start,period_end,previous_grade,previous_score,current_grade,"
            + "current_score,score_delta,grade_changed,change_type,reason,operator_id,occurred_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::date,?::date,"
            + "?::varchar,?::numeric,?::varchar,?::numeric,?::numeric,?::boolean,'MANUAL_ADJUSTMENT',"
            + "?::varchar,?::uuid,now())",
        UUID.randomUUID(),
        currentTenant(),
        r.get("supplier_ref_id", UUID.class),
        r.get("supplier_code", String.class),
        r.get("supplier_name", String.class),
        r.get("period_type", String.class),
        r.get("period_start", LocalDate.class),
        r.get("period_end", LocalDate.class),
        previousGrade == null ? null : previousGrade.name(),
        previous,
        newGrade == null ? null : newGrade.name(),
        finalScore,
        finalScore.subtract(previous),
        previousGrade != newGrade,
        input.reason().trim(),
        operator);

    Record after =
        sql.fetchOne(
            "select " + RESULT_COLUMNS + " from supplier_rating_result r"
                + " where r.tenant_id=?::uuid and r.id=?::uuid",
            currentTenant(),
            resultId);
    return toRow(after);
  }

  // ================================================================ 采集：各维度的事实

  /** 一个供应商在周期内的原始事实。 */
  private record SupplierFacts(
      UUID supplierRefId,
      String supplierCode,
      String supplierName,
      int arrivedCount,
      int onTimeCount,
      BigDecimal qualifiedQty,
      BigDecimal inspectedQty,
      BigDecimal responseAvgHours,
      int responseSamples,
      List<PriceSample> priceSamples,
      int claimCount,
      int claimParticipated) {}

  /** 价格维度的一个样本（SKU + 币种 + 参考数量）。 */
  private record PriceSample(UUID skuId, String currency, BigDecimal quantity) {}

  /** 一次算出来的评级（还没落库）。 */
  private record Composed(
      SupplierFacts facts,
      Map<SupplierRatingDimension, BigDecimal> scores,
      List<SupplierRatingDimension> unavailable,
      BigDecimal objectiveScore,
      BigDecimal availableWeight,
      BigDecimal deliveryOnTimeRate,
      BigDecimal qualityPassRate,
      BigDecimal responseAvgHours,
      BigDecimal priceIndex,
      int priceSamples,
      BigDecimal priceComparableQuantity,
      BigDecimal serviceCooperationRate,
      String unavailableReason) {}

  /** 采集本周期内有活动的供应商与五个维度的原始事实。 */
  private Map<UUID, SupplierFacts> collectSuppliers(OffsetDateTime from, OffsetDateTime to) {
    UUID tenant = currentTenant();
    // 供应商集合 = 五个维度的数据源里在本周期出现过的供应商的并集。
    // 「本周期什么都没发生的供应商」不参与评级：没有依据就没有分数。
    List<Record> rows =
        sql.fetch(
            "select s.supplier_ref_id,"
                + " coalesce(max(nullif(s.supplier_code,'')), '') as supplier_code,"
                + " max(s.supplier_name) as supplier_name"
                + " from ("
                + "  select t.supplier_ref_id, t.supplier_code, t.supplier_name"
                + "    from purchase_arrival_tracking t"
                + "    join purchase_arrival_receipt r"
                + "      on r.tenant_id=t.tenant_id and r.tracking_id=t.id"
                + "   where t.tenant_id=?::uuid and r.arrived_at>=?::timestamptz and r.arrived_at<?::timestamptz"
                + "  union all"
                // 质检单**没有供应商编码列**（只有 id + 名称快照）⇒ 这一支的编码留空，
                // 绝不能拿名称冒充编码（界面上「编码」列会被当成可检索的稳定标识）。
                + "  select q.supplier_id, ''::varchar, q.supplier_name"
                + "    from quality_inspection_order q"
                + "   where q.tenant_id=?::uuid and q.status='COMPLETED'"
                + "     and q.completed_at>=?::timestamptz and q.completed_at<?::timestamptz"
                + "  union all"
                + "  select f.supplier_ref_id, f.supplier_code, f.supplier_name"
                + "    from purchase_followup_record f"
                + "   where f.tenant_id=?::uuid and f.occurred_at>=?::timestamptz and f.occurred_at<?::timestamptz"
                + "  union all"
                + "  select c.supplier_ref_id, c.supplier_code, c.supplier_name"
                + "    from supplier_claim c"
                + "   where c.tenant_id=?::uuid and c.submitted_at>=?::timestamptz and c.submitted_at<?::timestamptz"
                + "  union all"
                + "  select o.supplier_ref_id, o.supplier_code, o.supplier_name"
                + "    from purchase_order o"
                + "   where o.tenant_id=?::uuid and o.created_at>=?::timestamptz and o.created_at<?::timestamptz"
                + ") s group by s.supplier_ref_id",
            tenant, from, to, tenant, from, to, tenant, from, to, tenant, from, to, tenant, from, to);
    Map<UUID, SupplierFacts> out = new LinkedHashMap<>();
    for (Record r : rows) {
      UUID supplier = r.get("supplier_ref_id", UUID.class);
      if (supplier == null) continue;
      out.put(
          supplier,
          new SupplierFacts(
              supplier,
              nz(r.get("supplier_code", String.class)),
              nz(r.get("supplier_name", String.class)),
              0,
              0,
              null,
              null,
              null,
              0,
              List.of(),
              0,
              0));
    }
    // 逐维度回填（每个维度一条聚合 SQL，按供应商分组）。
    applyDelivery(out, from, to);
    applyQuality(out, from, to);
    applyResponse(out, from, to);
    applyPriceSamples(out, from, to);
    applyService(out, from, to);
    return out;
  }

  /**
   * D1 交期达成率。
   *
   * <p>承诺日口径与 6.3.2.1「跟单与交期」的延迟统计<b>逐字同源</b>：
   * 「到货时刻生效的承诺日」= 到货时刻之前最后一次交期变更的 to_promised_date，
   * 没有变更则取采购单上的 promised_arrival_date。
   */
  private void applyDelivery(
      Map<UUID, SupplierFacts> facts, OffsetDateTime from, OffsetDateTime to) {
    UUID tenant = currentTenant();
    List<Record> rows =
        sql.fetch(
            "with tracked as ("
                + "  select t.purchase_order_no, t.supplier_ref_id,"
                + "         (select max(r.arrived_at) from purchase_arrival_receipt r"
                + "           where r.tenant_id=t.tenant_id and r.tracking_id=t.id) as arrived_at"
                + "    from purchase_arrival_tracking t where t.tenant_id=?::uuid"
                + "), arrived as ("
                + "  select tr.supplier_ref_id, tr.purchase_order_no, tr.arrived_at,"
                + "         (tr.arrived_at at time zone 'UTC')::date as arrived_date"
                + "    from tracked tr"
                + "   where tr.arrived_at is not null"
                + "     and tr.arrived_at>=?::timestamptz and tr.arrived_at<?::timestamptz"
                + ")"
                + " select a.supplier_ref_id, count(*) as arrived_count,"
                + "   count(*) filter (where (a.arrived_date - coalesce("
                + "     (select c.to_promised_date from purchase_delivery_change c"
                + "       where c.tenant_id=?::uuid and c.purchase_order_no=a.purchase_order_no"
                + "         and c.changed_at<=a.arrived_at"
                + "       order by c.changed_at desc, c.id desc limit 1),"
                + "     (select o.promised_arrival_date from purchase_order o"
                + "       where o.tenant_id=?::uuid and o.order_no=a.purchase_order_no)"
                + "   )) <= 0) as on_time_count"
                + "  from arrived a group by a.supplier_ref_id",
            tenant, from, to, tenant, tenant);
    for (Record r : rows) {
      UUID supplier = r.get("supplier_ref_id", UUID.class);
      SupplierFacts f = facts.get(supplier);
      if (f == null) continue;
      facts.put(supplier, withDelivery(f, count(r, "arrived_count"), count(r, "on_time_count")));
    }
  }

  /** D2 质量合格率：按数量比算，只算已判定完成的单。 */
  private void applyQuality(
      Map<UUID, SupplierFacts> facts, OffsetDateTime from, OffsetDateTime to) {
    List<Record> rows =
        sql.fetch(
            "select supplier_id, sum(qualified_quantity) as qualified_qty, sum(lot_size) as inspected_qty"
                + " from quality_inspection_order"
                + " where tenant_id=?::uuid and status='COMPLETED' and result is not null"
                + "   and completed_at>=?::timestamptz and completed_at<?::timestamptz"
                + " group by supplier_id",
            currentTenant(), from, to);
    for (Record r : rows) {
      UUID supplier = r.get("supplier_id", UUID.class);
      SupplierFacts f = facts.get(supplier);
      if (f == null) continue;
      facts.put(
          supplier,
          withQuality(
              f, r.get("qualified_qty", BigDecimal.class), r.get("inspected_qty", BigDecimal.class)));
    }
  }

  /**
   * D3 响应时长。
   *
   * <p>样本 = 「有供应商答复」且「同一采购单上更早的一条没有答复」的跟单记录；
   * 等待窗口 = 答复时刻 − 上一条未答复时刻。
   */
  private void applyResponse(
      Map<UUID, SupplierFacts> facts, OffsetDateTime from, OffsetDateTime to) {
    List<Record> rows =
        sql.fetch(
            "with ordered as ("
                + "  select f.supplier_ref_id, f.occurred_at,"
                + "         (btrim(f.supplier_reply) <> '') as answered,"
                + "         lag(f.occurred_at) over (partition by f.purchase_order_no"
                + "           order by f.occurred_at, f.id) as prev_at,"
                + "         lag(btrim(f.supplier_reply) <> '') over (partition by f.purchase_order_no"
                + "           order by f.occurred_at, f.id) as prev_answered"
                + "    from purchase_followup_record f where f.tenant_id=?::uuid"
                + ")"
                + " select supplier_ref_id,"
                + "   count(*) filter (where answered and prev_at is not null and not prev_answered)"
                + "     as sample_count,"
                + "   avg(extract(epoch from (occurred_at - prev_at))/3600)"
                + "     filter (where answered and prev_at is not null and not prev_answered) as avg_hours"
                + "  from ordered"
                + " where occurred_at>=?::timestamptz and occurred_at<?::timestamptz"
                + " group by supplier_ref_id",
            currentTenant(), from, to);
    for (Record r : rows) {
      UUID supplier = r.get("supplier_ref_id", UUID.class);
      SupplierFacts f = facts.get(supplier);
      if (f == null) continue;
      facts.put(
          supplier, withResponse(f, count(r, "sample_count"), r.get("avg_hours", BigDecimal.class)));
    }
  }

  /** D4 价格竞争力的样本：本期采购单行（SKU + 币种 + 数量）。 */
  private void applyPriceSamples(
      Map<UUID, SupplierFacts> facts, OffsetDateTime from, OffsetDateTime to) {
    List<Record> rows =
        sql.fetch(
            "select o.supplier_ref_id, l.sku_id, o.currency, sum(l.quantity) as quantity"
                + " from purchase_order o"
                + " join purchase_order_line l on l.tenant_id=o.tenant_id and l.order_id=o.id"
                + " where o.tenant_id=?::uuid and o.created_at>=?::timestamptz and o.created_at<?::timestamptz"
                + "   and l.sku_id is not null"
                + " group by o.supplier_ref_id, l.sku_id, o.currency",
            currentTenant(), from, to);
    for (Record r : rows) {
      UUID supplier = r.get("supplier_ref_id", UUID.class);
      SupplierFacts f = facts.get(supplier);
      if (f == null) continue;
      UUID skuId = r.get("sku_id", UUID.class);
      if (skuId == null) continue;
      List<PriceSample> samples = new ArrayList<>(f.priceSamples());
      samples.add(
          new PriceSample(skuId, nz(r.get("currency", String.class)), r.get("quantity", BigDecimal.class)));
      facts.put(supplier, withPriceSamples(f, samples));
    }
  }

  /** D5 售后配合度：期内提交的索赔里，供应商侧参与过协商的占比。 */
  private void applyService(
      Map<UUID, SupplierFacts> facts, OffsetDateTime from, OffsetDateTime to) {
    List<Record> rows =
        sql.fetch(
            "select c.supplier_ref_id, count(*) as claim_count,"
                + " count(*) filter (where exists ("
                + "   select 1 from supplier_claim_negotiation n"
                + "    where n.tenant_id=c.tenant_id and n.claim_id=c.id"
                + "      and n.participant_role='SUPPLIER')) as participated"
                + " from supplier_claim c"
                + " where c.tenant_id=?::uuid and c.submitted_at>=?::timestamptz and c.submitted_at<?::timestamptz"
                + " group by c.supplier_ref_id",
            currentTenant(), from, to);
    for (Record r : rows) {
      UUID supplier = r.get("supplier_ref_id", UUID.class);
      SupplierFacts f = facts.get(supplier);
      if (f == null) continue;
      facts.put(supplier, withService(f, count(r, "claim_count"), count(r, "participated")));
    }
  }

  // ================================================================ 合成

  /** 把一个供应商的事实合成成评级。 */
  private Composed compose(
      SupplierFacts f,
      Weights weights,
      SupplierRatingPolicy policy,
      SupplierRatingQuotePort.QuoteSnapshot snapshot,
      String priceUnavailableReason) {
    Map<SupplierRatingDimension, BigDecimal> scores = new EnumMap<>(SupplierRatingDimension.class);
    List<String> reasons = new ArrayList<>();

    // D1 交期达成率
    BigDecimal onTimeRate = null;
    if (f.arrivedCount() > 0) {
      onTimeRate =
          rate(
              BigDecimal.valueOf(f.onTimeCount())
                  .divide(BigDecimal.valueOf(f.arrivedCount()), 6, RoundingMode.HALF_UP));
    } else {
      reasons.add("交期达成率：本期没有到货记录");
    }
    scores.put(SupplierRatingDimension.DELIVERY, rateScore(onTimeRate));

    // D2 质量合格率
    BigDecimal qualityRate = null;
    if (f.inspectedQty() != null && f.inspectedQty().signum() > 0 && f.qualifiedQty() != null) {
      qualityRate = rate(f.qualifiedQty().divide(f.inspectedQty(), 6, RoundingMode.HALF_UP));
    } else {
      reasons.add("质量合格率：本期没有已判定的来料质检单");
    }
    scores.put(SupplierRatingDimension.QUALITY, rateScore(qualityRate));

    // D3 响应时长
    BigDecimal response = responseScore(f.responseAvgHours(), policy.responseTargetHours());
    if (response == null) {
      reasons.add("响应时长：本期没有「答复型」跟单样本（可测的等待窗口为零）");
    }
    scores.put(SupplierRatingDimension.RESPONSE, response);

    // D4 价格竞争力
    BigDecimal priceIndex = null;
    int priceSamples = 0;
    BigDecimal comparableQty = BigDecimal.ZERO;
    if (snapshot == null) {
      reasons.add("价格竞争力：市场报价读不到（" + nz(priceUnavailableReason) + "）");
    } else {
      PriceOutcome outcome = priceIndex(f, snapshot);
      priceIndex = outcome.index();
      priceSamples = outcome.samples();
      comparableQty = outcome.quantity();
      if (priceIndex == null) {
        reasons.add("价格竞争力：本期采购的 SKU 在报价台账里没有可比的同币种报价");
      } else if (snapshot.truncated()) {
        reasons.add("价格竞争力：报价台账被翻页上限截断，样本只覆盖了部分市场");
      }
    }
    scores.put(SupplierRatingDimension.PRICE, rateScore(priceIndex));

    // D5 售后配合度
    BigDecimal serviceRate = null;
    if (f.claimCount() > 0) {
      serviceRate =
          rate(
              BigDecimal.valueOf(f.claimParticipated())
                  .divide(BigDecimal.valueOf(f.claimCount()), 6, RoundingMode.HALF_UP));
    } else {
      reasons.add("售后配合度：本期没有提交过的供应商索赔（不是 0 分，是没有依据）");
    }
    scores.put(SupplierRatingDimension.SERVICE, rateScore(serviceRate));

    Composite composite = composite(weights, scores);
    return new Composed(
        f,
        scores,
        composite.unavailable(),
        composite.score(),
        composite.availableWeight(),
        onTimeRate,
        qualityRate,
        f.responseAvgHours(),
        priceIndex,
        priceSamples,
        comparableQty,
        serviceRate,
        String.join("；", reasons));
  }

  /** 价格竞争力的计算结果。 */
  private record PriceOutcome(BigDecimal index, int samples, BigDecimal quantity) {}

  /**
   * 价格竞争力指数：按采购量加权的「同币种最优价 / 本供应商价」的平均。
   *
   * <p>⚠️ <b>只比同币种</b>：本服务没有汇率表（汇率是 12 财务的资产），
   * 100 USD 与 90 EUR 直接比大小是错的。没有同币种对照的 SKU 不进样本。
   * ⚠️ <b>报价在该数量处不适用（未达起订量）也不进样本</b>：拿别人的入场价比我们的
   * 实际采购量，条件根本不同。
   */
  private PriceOutcome priceIndex(SupplierFacts f, SupplierRatingQuotePort.QuoteSnapshot snapshot) {
    BigDecimal weighted = BigDecimal.ZERO;
    BigDecimal totalQty = BigDecimal.ZERO;
    int samples = 0;
    for (PriceSample sample : f.priceSamples()) {
      List<SupplierRatingQuotePort.QuoteView> quotes = snapshot.bySku().get(sample.skuId());
      if (quotes == null || quotes.isEmpty()) continue;
      BigDecimal ourPrice = null;
      BigDecimal bestOther = null;
      for (SupplierRatingQuotePort.QuoteView q : quotes) {
        if (!sample.currency().equalsIgnoreCase(nz(q.currency()))) continue;
        BigDecimal price = tierPrice(q.tiers(), sample.quantity());
        if (price == null || price.signum() <= 0) continue;
        if (q.supplierRoleId().equals(f.supplierRefId())) {
          if (ourPrice == null || price.compareTo(ourPrice) < 0) ourPrice = price;
        } else if (bestOther == null || price.compareTo(bestOther) < 0) {
          bestOther = price;
        }
      }
      if (ourPrice == null || bestOther == null) continue;
      BigDecimal contribution = priceContribution(ourPrice, bestOther);
      if (contribution == null) continue;
      BigDecimal qty = sample.quantity() == null ? BigDecimal.ONE : sample.quantity();
      weighted = weighted.add(contribution.multiply(qty));
      totalQty = totalQty.add(qty);
      samples++;
    }
    if (totalQty.signum() == 0) {
      return new PriceOutcome(null, 0, BigDecimal.ZERO);
    }
    return new PriceOutcome(
        rate(weighted.divide(totalQty, 6, RoundingMode.HALF_UP)), samples, totalQty);
  }

  // ================================================================ 落库

  /**
   * 写入 / 覆盖一条评级结果。
   *
   * @return true = 新建，false = 覆盖既有行
   */
  private boolean writeResult(
      Composed c,
      Period period,
      SupplierRatingPeriodType type,
      BigDecimal finalScore,
      SupplierRatingGrade grade,
      BigDecimal manualAdjustment) {
    SupplierFacts f = c.facts();
    boolean exists =
        sql.fetchOne(
                "select 1 from supplier_rating_result where tenant_id=?::uuid and period_type=?::varchar"
                    + " and period_start=?::date and supplier_ref_id=?::uuid",
                currentTenant(), type.name(), period.start(), f.supplierRefId())
            != null;
    if (!exists) {
      sql.execute(
          "insert into supplier_rating_result(id,tenant_id,period_type,period_start,period_end,"
              + "supplier_ref_id,supplier_code,supplier_name,objective_score,manual_adjustment,"
              + "final_score,grade,available_weight,unavailable_dimensions,unavailable_reason,"
              + "delivery_score,quality_score,response_score,price_score,service_score,"
              + "delivery_on_time_rate,delivery_arrived_count,quality_pass_rate,"
              + "quality_inspected_quantity,quality_qualified_quantity,response_avg_hours,"
              + "response_sample_count,price_index,price_sample_count,price_comparable_quantity,"
              + "service_cooperation_rate,service_claim_count,manually_adjusted,manual_adjusted_by,"
              + "manual_adjusted_at,manual_adjustment_reason,computed_at,version,created_at,updated_at)"
              + " values (?::uuid,?::uuid,?::varchar,?::date,?::date,?::uuid,?::varchar,?::varchar,"
              + "?::numeric,?::numeric,?::numeric,?::varchar,?::numeric,?::varchar,?::varchar,"
              + "?::numeric,?::numeric,?::numeric,?::numeric,?::numeric,"
              + "?::numeric,?::int,?::numeric,?::numeric,?::numeric,?::numeric,?::int,?::numeric,?::int,"
              + "?::numeric,?::numeric,?::int,false,null,null,'',now(),0,now(),now())",
          UUID.randomUUID(),
          currentTenant(),
          type.name(),
          period.start(),
          period.end(),
          f.supplierRefId(),
          f.supplierCode(),
          f.supplierName(),
          c.objectiveScore(),
          manualAdjustment,
          finalScore,
          grade == null ? null : grade.name(),
          c.availableWeight(),
          dimensionKeys(c.unavailable()),
          c.unavailableReason(),
          c.scores().get(SupplierRatingDimension.DELIVERY),
          c.scores().get(SupplierRatingDimension.QUALITY),
          c.scores().get(SupplierRatingDimension.RESPONSE),
          c.scores().get(SupplierRatingDimension.PRICE),
          c.scores().get(SupplierRatingDimension.SERVICE),
          c.deliveryOnTimeRate(),
          f.arrivedCount(),
          c.qualityPassRate(),
          f.inspectedQty() == null ? BigDecimal.ZERO : f.inspectedQty(),
          f.qualifiedQty() == null ? BigDecimal.ZERO : f.qualifiedQty(),
          c.responseAvgHours(),
          f.responseSamples(),
          c.priceIndex(),
          c.priceSamples(),
          c.priceComparableQuantity(),
          c.serviceCooperationRate(),
          f.claimCount());
      return true;
    }
    // 覆盖既有行：**人工调整保留**（它是长期有效的调整量），客观分与五个维度刷成最新的。
    sql.execute(
        "update supplier_rating_result set period_end=?::date,supplier_code=?::varchar,"
            + "supplier_name=?::varchar,objective_score=?::numeric,manual_adjustment=?::numeric,"
            + "final_score=?::numeric,grade=?::varchar,available_weight=?::numeric,"
            + "unavailable_dimensions=?::varchar,unavailable_reason=?::varchar,"
            + "delivery_score=?::numeric,quality_score=?::numeric,response_score=?::numeric,"
            + "price_score=?::numeric,service_score=?::numeric,delivery_on_time_rate=?::numeric,"
            + "delivery_arrived_count=?::int,quality_pass_rate=?::numeric,"
            + "quality_inspected_quantity=?::numeric,quality_qualified_quantity=?::numeric,"
            + "response_avg_hours=?::numeric,response_sample_count=?::int,price_index=?::numeric,"
            + "price_sample_count=?::int,price_comparable_quantity=?::numeric,"
            + "service_cooperation_rate=?::numeric,service_claim_count=?::int,computed_at=now(),"
            + "version=version+1,updated_at=now()"
            + " where tenant_id=?::uuid and period_type=?::varchar and period_start=?::date"
            + "   and supplier_ref_id=?::uuid",
        period.end(),
        f.supplierCode(),
        f.supplierName(),
        c.objectiveScore(),
        manualAdjustment,
        finalScore,
        grade == null ? null : grade.name(),
        c.availableWeight(),
        dimensionKeys(c.unavailable()),
        c.unavailableReason(),
        c.scores().get(SupplierRatingDimension.DELIVERY),
        c.scores().get(SupplierRatingDimension.QUALITY),
        c.scores().get(SupplierRatingDimension.RESPONSE),
        c.scores().get(SupplierRatingDimension.PRICE),
        c.scores().get(SupplierRatingDimension.SERVICE),
        c.deliveryOnTimeRate(),
        f.arrivedCount(),
        c.qualityPassRate(),
        f.inspectedQty() == null ? BigDecimal.ZERO : f.inspectedQty(),
        f.qualifiedQty() == null ? BigDecimal.ZERO : f.qualifiedQty(),
        c.responseAvgHours(),
        f.responseSamples(),
        c.priceIndex(),
        c.priceSamples(),
        c.priceComparableQuantity(),
        c.serviceCooperationRate(),
        f.claimCount(),
        currentTenant(),
        type.name(),
        period.start(),
        f.supplierRefId());
    return false;
  }

  /**
   * 变动历史：分数或等级真的变了才留一行。
   *
   * <p>「上一次的评级」= 同周期重算时被覆盖掉的那一行；同周期还没有行时，
   * 取该供应商**更早周期**的评级行（跨周期滚动）。
   */
  private int recordChange(
      Composed c,
      Period period,
      SupplierRatingPeriodType type,
      SupplierRatingGrade grade,
      Record samePeriodOld) {
    SupplierFacts f = c.facts();
    Record before = samePeriodOld;
    String changeType = "RECOMPUTE";
    String reason = "同周期重算：重算后的分数与上一次计算不同";
    if (before == null) {
      before =
          sql.fetchOne(
              "select final_score, grade from supplier_rating_result"
                  + " where tenant_id=?::uuid and supplier_ref_id=?::uuid and period_start<?::date"
                  + " order by period_start desc limit 1",
              currentTenant(), f.supplierRefId(), period.start());
      changeType = "PERIOD_ROLL";
      reason = "跨周期评级滚动：本期分数与上期相比变了";
    }
    if (before == null) {
      // 首次评级：没有「变动」可言，不留行。
      return 0;
    }
    BigDecimal previousScore = before.get("final_score", BigDecimal.class);
    SupplierRatingGrade previousGrade = gradeOf(before.get("grade", String.class));
    boolean gradeChanged = previousGrade != grade;
    boolean scoreChanged =
        previousScore == null || c.objectiveScore() == null
            ? previousScore != c.objectiveScore()
            : previousScore.compareTo(adjustedFinalScore(c.objectiveScore(), BigDecimal.ZERO)) != 0;
    if (!gradeChanged && !scoreChanged) {
      return 0;
    }
    BigDecimal currentScore = adjustedFinalScore(c.objectiveScore(), BigDecimal.ZERO);
    sql.execute(
        "insert into supplier_rating_change(id,tenant_id,supplier_ref_id,supplier_code,supplier_name,"
            + "period_type,period_start,period_end,previous_grade,previous_score,current_grade,"
            + "current_score,score_delta,grade_changed,change_type,reason,operator_id,occurred_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::date,?::date,"
            + "?::varchar,?::numeric,?::varchar,?::numeric,?::numeric,?::boolean,?::varchar,?::varchar,"
            + "?::uuid,now())",
        UUID.randomUUID(),
        currentTenant(),
        f.supplierRefId(),
        f.supplierCode(),
        f.supplierName(),
        type.name(),
        period.start(),
        period.end(),
        previousGrade == null ? null : previousGrade.name(),
        previousScore,
        grade == null ? null : grade.name(),
        currentScore,
        previousScore == null ? null : currentScore.subtract(previousScore),
        gradeChanged,
        changeType,
        reason,
        UUID.randomUUID());
    return 1;
  }

  /** 统一刷新本期的名次：名次与「本期的行」永远一一对应。 */
  private void rerank(Period period, SupplierRatingPeriodType type) {
    sql.execute(
        "update supplier_rating_result r set rank_in_period = k.rn"
            + " from (select id, row_number() over (order by final_score desc, supplier_ref_id asc) as rn"
            + "         from supplier_rating_result"
            + "        where tenant_id=?::uuid and period_type=?::varchar and period_start=?::date) k"
            + " where r.id = k.id and r.tenant_id=?::uuid",
        currentTenant(), type.name(), period.start(), currentTenant());
  }

  // ================================================================ 读出口

  private SupplierRatingRow toRow(Record r) {
    return new SupplierRatingRow(
        r.get("id", UUID.class),
        periodTypeOf(r.get("period_type", String.class)),
        r.get("period_start", LocalDate.class),
        r.get("period_end", LocalDate.class),
        r.get("supplier_ref_id", UUID.class),
        nz(r.get("supplier_code", String.class)),
        nz(r.get("supplier_name", String.class)),
        r.get("objective_score", BigDecimal.class),
        r.get("manual_adjustment", BigDecimal.class),
        r.get("final_score", BigDecimal.class),
        gradeOf(r.get("grade", String.class)),
        r.get("rank_in_period", Integer.class),
        r.get("available_weight", BigDecimal.class),
        nz(r.get("unavailable_dimensions", String.class)),
        nz(r.get("unavailable_reason", String.class)),
        r.get("delivery_score", BigDecimal.class),
        r.get("quality_score", BigDecimal.class),
        r.get("response_score", BigDecimal.class),
        r.get("price_score", BigDecimal.class),
        r.get("service_score", BigDecimal.class),
        r.get("delivery_on_time_rate", BigDecimal.class),
        count(r, "delivery_arrived_count"),
        r.get("quality_pass_rate", BigDecimal.class),
        r.get("quality_inspected_quantity", BigDecimal.class),
        r.get("quality_qualified_quantity", BigDecimal.class),
        r.get("response_avg_hours", BigDecimal.class),
        count(r, "response_sample_count"),
        r.get("price_index", BigDecimal.class),
        count(r, "price_sample_count"),
        r.get("price_comparable_quantity", BigDecimal.class),
        r.get("service_cooperation_rate", BigDecimal.class),
        count(r, "service_claim_count"),
        Boolean.TRUE.equals(r.get("manually_adjusted", Boolean.class)),
        r.get("manual_adjusted_by", UUID.class),
        r.get("manual_adjusted_at", OffsetDateTime.class),
        nz(r.get("manual_adjustment_reason", String.class)),
        r.get("computed_at", OffsetDateTime.class),
        r.get("version", Long.class) == null ? 0L : r.get("version", Long.class));
  }

  private SupplierRatingAdjustmentRow toAdjustment(Record r) {
    return new SupplierRatingAdjustmentRow(
        r.get("id", UUID.class),
        r.get("result_id", UUID.class),
        r.get("supplier_ref_id", UUID.class),
        periodTypeOf(r.get("period_type", String.class)),
        r.get("period_start", LocalDate.class),
        r.get("period_end", LocalDate.class),
        r.get("objective_score", BigDecimal.class),
        r.get("previous_score", BigDecimal.class),
        r.get("adjusted_score", BigDecimal.class),
        r.get("adjustment", BigDecimal.class),
        nz(r.get("reason", String.class)),
        r.get("adjusted_by", UUID.class),
        r.get("adjusted_at", OffsetDateTime.class));
  }

  private SupplierRatingChangeRow toChange(Record r) {
    return new SupplierRatingChangeRow(
        r.get("id", UUID.class),
        r.get("supplier_ref_id", UUID.class),
        nz(r.get("supplier_code", String.class)),
        nz(r.get("supplier_name", String.class)),
        periodTypeOf(r.get("period_type", String.class)),
        r.get("period_start", LocalDate.class),
        r.get("period_end", LocalDate.class),
        gradeOf(r.get("previous_grade", String.class)),
        r.get("previous_score", BigDecimal.class),
        gradeOf(r.get("current_grade", String.class)),
        r.get("current_score", BigDecimal.class),
        r.get("score_delta", BigDecimal.class),
        Boolean.TRUE.equals(r.get("grade_changed", Boolean.class)),
        changeTypeOf(r.get("change_type", String.class)),
        nz(r.get("reason", String.class)),
        r.get("operator_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class));
  }

  // ================================================================ 工具

  /** 事实的不可变改写助手。 */
  private SupplierFacts withDelivery(SupplierFacts f, int arrived, int onTime) {
    return new SupplierFacts(
        f.supplierRefId(),
        f.supplierCode(),
        f.supplierName(),
        arrived,
        onTime,
        f.qualifiedQty(),
        f.inspectedQty(),
        f.responseAvgHours(),
        f.responseSamples(),
        f.priceSamples(),
        f.claimCount(),
        f.claimParticipated());
  }

  private SupplierFacts withQuality(
      SupplierFacts f, BigDecimal qualified, BigDecimal inspected) {
    return new SupplierFacts(
        f.supplierRefId(),
        f.supplierCode(),
        f.supplierName(),
        f.arrivedCount(),
        f.onTimeCount(),
        qualified,
        inspected,
        f.responseAvgHours(),
        f.responseSamples(),
        f.priceSamples(),
        f.claimCount(),
        f.claimParticipated());
  }

  private SupplierFacts withResponse(
      SupplierFacts f, int samples, BigDecimal avgHours) {
    return new SupplierFacts(
        f.supplierRefId(),
        f.supplierCode(),
        f.supplierName(),
        f.arrivedCount(),
        f.onTimeCount(),
        f.qualifiedQty(),
        f.inspectedQty(),
        avgHours,
        samples,
        f.priceSamples(),
        f.claimCount(),
        f.claimParticipated());
  }

  private SupplierFacts withPriceSamples(SupplierFacts f, List<PriceSample> samples) {
    return new SupplierFacts(
        f.supplierRefId(),
        f.supplierCode(),
        f.supplierName(),
        f.arrivedCount(),
        f.onTimeCount(),
        f.qualifiedQty(),
        f.inspectedQty(),
        f.responseAvgHours(),
        f.responseSamples(),
        samples,
        f.claimCount(),
        f.claimParticipated());
  }

  private SupplierFacts withService(SupplierFacts f, int claims, int participated) {
    return new SupplierFacts(
        f.supplierRefId(),
        f.supplierCode(),
        f.supplierName(),
        f.arrivedCount(),
        f.onTimeCount(),
        f.qualifiedQty(),
        f.inspectedQty(),
        f.responseAvgHours(),
        f.responseSamples(),
        f.priceSamples(),
        claims,
        participated);
  }

  /** 排序键白名单。 */
  private String sortColumn(String sort) {
    if (sort == null || sort.isBlank()) return "r.final_score";
    return switch (sort) {
      case "finalScore" -> "r.final_score";
      case "objectiveScore" -> "r.objective_score";
      case "grade" -> "r.grade";
      case "supplierName" -> "r.supplier_name";
      case "periodStart" -> "r.period_start";
      default -> "r.final_score";
    };
  }

  private SupplierRatingPeriodType periodTypeOf(String raw) {
    if (raw == null) return SupplierRatingPeriodType.MONTHLY;
    try {
      return SupplierRatingPeriodType.valueOf(raw);
    } catch (IllegalArgumentException e) {
      return SupplierRatingPeriodType.MONTHLY;
    }
  }

  private SupplierRatingGrade gradeOf(String raw) {
    if (raw == null) return null;
    try {
      return SupplierRatingGrade.valueOf(raw);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private SupplierRatingChangeType changeTypeOf(String raw) {
    if (raw == null) return SupplierRatingChangeType.RECOMPUTE;
    try {
      return SupplierRatingChangeType.valueOf(raw);
    } catch (IllegalArgumentException e) {
      return SupplierRatingChangeType.RECOMPUTE;
    }
  }

  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private LocalDate today() {
    return sql.fetchOne("select (current_timestamp at time zone 'UTC')::date").get(0, LocalDate.class);
  }

  private static int count(Record r, String column) {
    Integer v = r.get(column, Integer.class);
    return v == null ? 0 : v;
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }

  private static List<Object> concat(List<Object> a, List<Object> b) {
    List<Object> out = new ArrayList<>(a.size() + b.size());
    out.addAll(a);
    out.addAll(b);
    return out;
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }
}
