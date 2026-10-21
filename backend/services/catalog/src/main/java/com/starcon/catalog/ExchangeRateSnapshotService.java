package com.starcon.catalog;

import static com.starcon.catalog.ExchangeRateSnapshotModels.*;

import com.starcon.catalog.ExchangeRateSnapshotModels.ConversionDirection;
import com.starcon.catalog.ExchangeRateSnapshotModels.DocumentType;
import com.starcon.catalog.ExchangeRateSnapshotModels.ReplayRequest;
import com.starcon.catalog.ExchangeRateSnapshotModels.RoundingModeOption;
import com.starcon.catalog.ExchangeRateSnapshotModels.RoundingRuleInput;
import com.starcon.catalog.ExchangeRateSnapshotModels.RoundingRuleUpdate;
import com.starcon.catalog.ExchangeRateSnapshotModels.RoundingRuleView;
import com.starcon.catalog.ExchangeRateSnapshotModels.SnapshotCapabilities;
import com.starcon.catalog.ExchangeRateSnapshotModels.SnapshotInput;
import com.starcon.catalog.ExchangeRateSnapshotModels.SnapshotListPage;
import com.starcon.catalog.ExchangeRateSnapshotModels.SnapshotListQuery;
import com.starcon.catalog.ExchangeRateSnapshotModels.SnapshotReplayReport;
import com.starcon.catalog.ExchangeRateSnapshotModels.SnapshotReplayRow;
import com.starcon.catalog.ExchangeRateSnapshotModels.SnapshotView;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.2.2.2 汇率快照。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：在单据/定价生效时冻结所用汇率，保留换算过程（原价、汇率、舍入方式、目标币金额），保证历史利润可复算。
 *
 * **输入**：定价/单据生成时的汇率；舍入规则配置。
 *
 * **输出**：汇率快照记录；利润复算能力。
 *
 * **关系**：上游 = 5.2.2.1；下游 = 09 订单、12 财务、13 利润。**汇率快照是利润可追溯的前提**：今天汇率变了不能让上个月的利润报表数字跟着变。
 *
 * </blockquote>
 *
 * <p>本类负责**三件事**：
 *
 * <ol>
 *   <li><b>冻结</b>：把 5.2.2.1 的汇率事实 + 舍入规则 + 原价，算成一个**不可改写**的快照行
 *       （jOOQ 显式 SQL，AGENTS.md：不用 JPA 懒加载）；
 *   <li><b>复算</b>：拿快照里冻结的四个值重新算一遍，与冻结时写下的目标币金额逐笔比对；
 *   <li><b>舍入规则配置</b>：租户级的模式 + 小数位 + 适用币种，可改可删（改规则不动历史快照）。
 * </ol>
 *
 * <p><b>🔴 冻结的是「哪一条」也是事实</b>：快照必须指到 {@code exchange_rate.id}，
 * 数据库触发器逐项核对冻结值与那一行一致 —— 「快照上写着一个汇率表里根本不存在的汇率」
 * 这种行直连 SQL 也写不进来。
 */
@Service
public class ExchangeRateSnapshotService {

  /** 租户上下文：RLS 的依据。 */
  private static final String TENANT = "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 默认每页条数。 */
  private static final int DEFAULT_PAGE_SIZE = 20;

  /** 每页上限。 */
  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;

  public ExchangeRateSnapshotService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：单据类型、舍入方式、舍入规则、方向规则、冻结规则、下游与数据归属。
   *
   * <p>页面上把它固定显示在顶部 —— 蓝图那句「今天汇率变了不能让上个月的利润报表数字跟着变」靠它落地。
   */
  public SnapshotCapabilities capabilities() {
    var documentTypes = new ArrayList<DocumentTypeOption>();
    for (var type : DocumentType.values()) {
      documentTypes.add(new DocumentTypeOption(type.name(), type.label(), type.basis()));
    }
    var roundingModes = new ArrayList<RoundingModeInfo>();
    for (var option : RoundingModeOption.values()) {
      roundingModes.add(new RoundingModeInfo(option.name(), option.label(), option.basis()));
    }
    return new SnapshotCapabilities(
        documentTypes,
        roundingModes,
        roundingRules(),
        ExchangeRateSnapshotRules.DIRECTION_RULE,
        ExchangeRateSnapshotRules.IMMUTABILITY_RULE,
        ExchangeRateSnapshotRules.ROUNDING_RULE,
        ExchangeRateSnapshotRules.UPSTREAM_NOTE,
        ExchangeRateSnapshotRules.DOWNSTREAM_NOTE,
        ExchangeRateSnapshotRules.OWNERSHIP_NOTE,
        ExchangeRateSnapshotRules.REPLAY_CONTRACT_NOTE);
  }

  // ================================================================ 快照台账

  /**
   * 快照台账：分页 + 单据 / 币种对 / 生效日区间 / 关键词筛选。
   *
   * <p>日期筛的是**汇率生效日**（与 5.2.2.1 的台账同一口径）。
   */
  public SnapshotListPage list(SnapshotListQuery query) {
    int size = query.size() <= 0 ? DEFAULT_PAGE_SIZE : Math.min(query.size(), MAX_PAGE_SIZE);
    int page = Math.max(query.page(), 0);
    var where = new StringBuilder(" where s.tenant_id=" + TENANT);
    var binds = new ArrayList<Object>();
    if (notBlank(query.documentType())) {
      where.append(" and s.document_type=?");
      binds.add(query.documentType().trim().toUpperCase(Locale.ROOT));
    }
    if (query.documentId() != null) {
      where.append(" and s.document_id=?");
      binds.add(query.documentId());
    }
    if (notBlank(query.sourceCurrency())) {
      where.append(" and s.source_currency=?");
      binds.add(ExchangeRateRules.normalizeCurrency(query.sourceCurrency()));
    }
    if (notBlank(query.targetCurrency())) {
      where.append(" and s.target_currency=?");
      binds.add(ExchangeRateRules.normalizeCurrency(query.targetCurrency()));
    }
    if (query.dateFrom() != null) {
      where.append(" and s.rate_effective_date>=?");
      binds.add(query.dateFrom());
    }
    if (query.dateTo() != null) {
      where.append(" and s.rate_effective_date<=?");
      binds.add(query.dateTo());
    }
    if (notBlank(query.keyword())) {
      where.append(
          " and (s.document_no ilike ? or s.line_key ilike ? or s.rate_source_code ilike ?"
              + " or s.source_currency ilike ? or s.target_currency ilike ?)");
      var like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    long total = count("select count(*) from exchange_rate_snapshot s" + where, binds.toArray());
    var records =
        sql.fetch(
            "select s.id,s.document_type,s.document_id,s.document_no,s.line_key,"
                + "s.source_currency,s.source_amount,s.target_currency,s.target_amount,"
                + "s.rate_id,s.rate_base_currency,s.rate_quote_currency,s.rate,"
                + "s.conversion_direction,s.rate_effective_date,s.rate_source_code,"
                + "s.rounding_rule_code,s.rounding_mode,s.rounding_scale,"
                + "s.frozen_at,s.created_at,s.note"
                + " from exchange_rate_snapshot s"
                + where
                + " order by s.frozen_at desc,s.id desc"
                + " limit "
                + size
                + " offset "
                + ((long) page * size),
            binds.toArray());
    var items = new ArrayList<SnapshotView>();
    for (var record : records) items.add(toSnapshotView(record));
    return new SnapshotListPage(
        items,
        total,
        page,
        size,
        "快照台账按冻结时刻倒序。日期筛选针对**汇率生效日**（与 5.2.2.1 台账同一口径）。"
            + "每行都带完整方向与整条换算过程 —— 裸数字加上「汇率：7.12」这种文案正是方向混淆的来源。");
  }

  /** 单条快照详情。 */
  public SnapshotView snapshot(UUID id) {
    var row = fetchSnapshotRow(id);
    if (row == null) throw missing("汇率快照不存在或没有访问权限");
    return toSnapshotView(row);
  }

  private Record fetchSnapshotRow(UUID id) {
    return sql.fetchOne(
        "select s.id,s.document_type,s.document_id,s.document_no,s.line_key,"
            + "s.source_currency,s.source_amount,s.target_currency,s.target_amount,"
            + "s.rate_id,s.rate_base_currency,s.rate_quote_currency,s.rate,"
            + "s.conversion_direction,s.rate_effective_date,s.rate_source_code,"
            + "s.rounding_rule_code,s.rounding_mode,s.rounding_scale,"
            + "s.frozen_at,s.created_at,s.note"
            + " from exchange_rate_snapshot s"
            + " where s.tenant_id="
            + TENANT
            + " and s.id=?",
        id);
  }

  private SnapshotView toSnapshotView(Record r) {
    var documentType = DocumentType.parse(r.get("document_type", String.class));
    var direction = ConversionDirection.parse(r.get("conversion_direction", String.class));
    var mode = RoundingModeOption.parse(r.get("rounding_mode", String.class));
    var base = r.get("rate_base_currency", String.class);
    var quote = r.get("rate_quote_currency", String.class);
    var rate = r.get("rate", BigDecimal.class);
    var sourceCurrency = r.get("source_currency", String.class);
    var targetCurrency = r.get("target_currency", String.class);
    var scale = r.get("rounding_scale", Integer.class) == null ? 0 : r.get("rounding_scale", Integer.class);
    var sourceAmount = r.get("source_amount", BigDecimal.class);
    var targetAmount = r.get("target_amount", BigDecimal.class);
    return new SnapshotView(
        r.get("id", UUID.class),
        r.get("document_type", String.class),
        documentType == null ? r.get("document_type", String.class) : documentType.label(),
        r.get("document_id", UUID.class),
        r.get("document_no", String.class),
        r.get("line_key", String.class),
        sourceCurrency,
        sourceAmount,
        targetCurrency,
        targetAmount,
        ExchangeRateRules.display(base, quote, rate),
        r.get("rate_id", UUID.class),
        base,
        quote,
        rate,
        r.get("conversion_direction", String.class),
        direction == null ? r.get("conversion_direction", String.class) : direction.label(),
        direction == null ? "" : direction.basis(),
        r.get("rate_effective_date", LocalDate.class),
        r.get("rate_source_code", String.class),
        r.get("rounding_rule_code", String.class),
        r.get("rounding_mode", String.class),
        mode == null ? r.get("rounding_mode", String.class) : mode.label(),
        scale,
        ExchangeRateSnapshotRules.conversionDisplay(
            sourceAmount, sourceCurrency, rate, targetCurrency, direction, scale,
            mode == null ? null : mode.mode()),
        r.get("frozen_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("note", String.class));
  }

  // ================================================================ 冻结

  /**
   * 冻结一条汇率快照。
   *
   * <p>流程：解析汇率事实 → 定方向 → 解析舍入规则 → 算目标币金额 → 落库。
   *
   * <p><b>🔴 查不到就查不到</b>：汇率解析不到时返回 404 并写明原因，
   * **不**回退到「最近的一版」—— 静默回退会让一次按过期汇率算出的利润看起来完全正常。
   */
  public SnapshotView create(AccessService.Access actor, SnapshotInput input) {
    var shapeProblems = ExchangeRateSnapshotRules.validateSnapshot(input, null, null);
    if (!shapeProblems.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("；", shapeProblems));
    }
    var documentType = DocumentType.parse(input.documentType());
    var rate = resolveRate(input);
    var direction =
        ExchangeRateSnapshotRules.resolveDirection(rate.base(), rate.quote(), input.sourceCurrency());
    if (direction == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          ExchangeRateSnapshotRules.directionProblem(rate.base(), rate.quote(), input.sourceCurrency()));
    }
    var targetCurrency = direction == ConversionDirection.BASE_TO_QUOTE ? rate.quote() : rate.base();
    var rule = resolveRoundingRule(input.roundingRuleCode(), targetCurrency);

    var sourceAmount = input.sourceAmount();
    var targetAmount =
        ExchangeRateSnapshotRules.convert(
            sourceAmount, rate.rate(), direction, rule.scale(), rule.mode());
    if (targetAmount == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "换算结果为空：请检查原价与汇率");
    }

    var id = Ids.next();
    try {
      sql.execute(
          "insert into exchange_rate_snapshot(id,tenant_id,document_type,document_id,document_no,line_key,"
              + "source_currency,source_amount,target_currency,target_amount,"
              + "rate_id,rate_base_currency,rate_quote_currency,rate,conversion_direction,"
              + "rate_effective_date,rate_source_code,rounding_rule_code,rounding_mode,rounding_scale,"
              + "frozen_at,created_by,note)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?)",
          id,
          actor.tenantId(),
          documentType.name(),
          input.documentId(),
          input.documentNo() == null ? "" : input.documentNo().trim(),
          input.lineKey() == null ? "" : input.lineKey().trim(),
          ExchangeRateRules.normalizeCurrency(input.sourceCurrency()),
          sourceAmount,
          targetCurrency,
          targetAmount,
          rate.id(),
          rate.base(),
          rate.quote(),
          rate.rate(),
          direction.name(),
          rate.effectiveDate(),
          rate.sourceCode(),
          rule.code(),
          rule.mode().name(),
          rule.scale(),
          OffsetDateTime.now(),
          actor.userId(),
          input.note() == null ? "" : input.note().trim());
    } catch (RuntimeException e) {
      throw translateWriteFailure(e, "汇率快照");
    }
    audit(actor, id, "ExchangeRateSnapshotFrozen");
    return snapshot(id);
  }

  /**
   * 解析要冻结的汇率事实（上游 5.2.2.1 的 {@code exchange_rate}）。
   *
   * <p>两条路径：
   *
   * <ul>
   *   <li>{@code rateId}：直接指到那一行（最明确）；
   *   <li>{@code baseCurrency + quoteCurrency + asOf}：取 asOf 当天或之前**最新**的一条
   *       （与 5.2.2.1 的 {@code /latest} 同一口径）。
   * </ul>
   *
   * <p><b>🔴 只查正向</b>：反向汇率由正向推导（V225 第二节），所以查不到
   * 「1 CNY = ? USD」时**不回退**到 USD→CNY 那条，而是报错并说明该把方向按已登记的方式填。
   */
  private RateRow resolveRate(SnapshotInput input) {
    if (input.rateId() != null) {
      var row =
          sql.fetchOne(
              "select id,base_currency,quote_currency,rate,effective_date,source_code"
                  + " from exchange_rate where tenant_id="
                  + TENANT
                  + " and id=?",
              input.rateId());
      if (row == null) {
        throw missing(
            "汇率记录不存在或没有访问权限（rate_id="
                + input.rateId()
                + "）。请先在 5.2.2.1 汇率查询里登记这条汇率，再冻结快照。");
      }
      return toRateRow(row);
    }
    var base = ExchangeRateRules.normalizeCurrency(input.baseCurrency());
    var quote = ExchangeRateRules.normalizeCurrency(input.quoteCurrency());
    var on = input.asOf() == null ? LocalDate.now() : input.asOf();
    var row =
        sql.fetchOne(
            "select id,base_currency,quote_currency,rate,effective_date,source_code"
                + " from exchange_rate where tenant_id="
                + TENANT
                + " and base_currency=? and quote_currency=? and effective_date<=?"
                + " order by effective_date desc limit 1",
            base,
            quote,
            on);
    if (row == null) {
      throw missing(
          "没有登记 1 "
              + base
              + " = ? "
              + quote
              + " 的汇率（截至 "
              + on
              + "）。本服务不会拿别的币种对或过期日期的汇率顶替 —— 查不到就是查不到。"
              + "反向汇率由正向推导、不另存一条：请把基准/报价币种按已登记的方向填，或用 rateId 指定那一条。");
    }
    return toRateRow(row);
  }

  private RateRow toRateRow(Record r) {
    return new RateRow(
        r.get("id", UUID.class),
        r.get("base_currency", String.class),
        r.get("quote_currency", String.class),
        r.get("rate", BigDecimal.class),
        r.get("effective_date", LocalDate.class),
        r.get("source_code", String.class));
  }

  private record RateRow(
      UUID id,
      String base,
      String quote,
      BigDecimal rate,
      LocalDate effectiveDate,
      String sourceCode) {}

  // ================================================================ 复算

  /**
   * 复算：拿快照里冻结的原价、汇率、方向、舍入方式重新算一遍目标币金额。
   *
   * <p>这是蓝图输出里的「**利润复算能力**」：13 利润与 12 财务拿它验证历史数字仍然算得出来。
   *
   * <p>复算**不写库** —— 它是一次纯计算，读的是快照行里冻结的那几个值。
   */
  public SnapshotReplayReport replay(AccessService.Access actor, ReplayRequest request) {
    if (request == null
        || (request.snapshotId() == null
            && (notBlank(request.documentType()) == false || request.documentId() == null))) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "复算需要指定 snapshotId，或 documentType + documentId");
    }
    var rows = new ArrayList<SnapshotReplayRow>();
    if (request.snapshotId() != null) {
      var record = fetchSnapshotRow(request.snapshotId());
      if (record == null) throw missing("汇率快照不存在或没有访问权限");
      rows.add(toReplayRow(record));
    } else {
      var documentType = DocumentType.parse(request.documentType());
      if (documentType == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "单据类型不合法：只能是 PRICE_LIST / PRICE_LIST_ITEM / PRICING_TEMPLATE / PROFIT_CALCULATION / ORDER / SETTLEMENT");
      }
      var records =
          sql.fetch(
              "select s.id,s.document_type,s.document_id,s.document_no,s.line_key,"
                  + "s.source_currency,s.source_amount,s.target_currency,s.target_amount,"
                  + "s.rate_id,s.rate_base_currency,s.rate_quote_currency,s.rate,"
                  + "s.conversion_direction,s.rate_effective_date,s.rate_source_code,"
                  + "s.rounding_rule_code,s.rounding_mode,s.rounding_scale,"
                  + "s.frozen_at,s.created_at,s.note"
                  + " from exchange_rate_snapshot s"
                  + " where s.tenant_id="
                  + TENANT
                  + " and s.document_type=? and s.document_id=?"
                  + " order by s.line_key,s.frozen_at",
              documentType.name(),
              request.documentId());
      for (var record : records) rows.add(toReplayRow(record));
    }
    var matched = 0;
    for (var row : rows) {
      if (row.matched()) matched++;
    }
    var mismatched = rows.size() - matched;
    return new SnapshotReplayReport(
        rows,
        rows.size(),
        matched,
        mismatched,
        rows.isEmpty()
            ? "这份单据上还没有冻结过任何汇率快照：没有可复算的对象，复算结果不是「通过」而是「无从复算」。"
            : "复算 = 拿快照里冻结的原价、汇率、方向、舍入方式重新算一遍，与冻结时写下的目标币金额逐笔比对。"
                + (mismatched == 0
                    ? "全部一致：这批历史利润仍然算得出来。"
                    : "有 " + mismatched + " 笔对不上，说明冻结值被改动过 —— 快照受数据库触发器保护，正常不会出现这种情况。"));
  }

  private SnapshotReplayRow toReplayRow(Record r) {
    var direction = ConversionDirection.parse(r.get("conversion_direction", String.class));
    var mode = RoundingModeOption.parse(r.get("rounding_mode", String.class));
    var scale = r.get("rounding_scale", Integer.class) == null ? 0 : r.get("rounding_scale", Integer.class);
    var sourceAmount = r.get("source_amount", BigDecimal.class);
    var targetAmount = r.get("target_amount", BigDecimal.class);
    var rate = r.get("rate", BigDecimal.class);
    var recomputed =
        ExchangeRateSnapshotRules.convert(
            sourceAmount, rate, direction, scale, mode == null ? null : mode.mode());
    var matched = recomputed != null && recomputed.compareTo(targetAmount) == 0;
    var difference = recomputed == null || targetAmount == null ? null : recomputed.subtract(targetAmount);
    var base = r.get("rate_base_currency", String.class);
    var quote = r.get("rate_quote_currency", String.class);
    return new SnapshotReplayRow(
        r.get("id", UUID.class),
        r.get("document_type", String.class),
        r.get("document_id", UUID.class),
        r.get("document_no", String.class),
        r.get("line_key", String.class),
        r.get("source_currency", String.class),
        sourceAmount,
        r.get("target_currency", String.class),
        targetAmount,
        recomputed,
        matched,
        difference,
        ExchangeRateRules.display(base, quote, rate),
        r.get("conversion_direction", String.class),
        r.get("rounding_mode", String.class),
        mode == null ? r.get("rounding_mode", String.class) : mode.label(),
        scale,
        r.get("rounding_rule_code", String.class),
        r.get("rate_effective_date", LocalDate.class),
        r.get("rate_source_code", String.class),
        matched
            ? "复算一致：拿冻结的原价、汇率与舍入方式重算，得到同一个目标币金额。"
            : "复算不一致：重算结果与冻结时写下的目标币金额对不上，差额 " + difference + "。");
  }

  // ================================================================ 舍入规则配置

  /** 舍入规则列表（租户级配置）。 */
  public List<RoundingRuleView> roundingRules() {
    return sql
        .fetch(
            "select id,code,name,target_currency,rounding_mode,rounding_scale,enabled,priority,note,"
                + "version,created_at,updated_at"
                + " from exchange_rate_rounding_rule where tenant_id="
                + TENANT
                + " order by target_currency nulls first,priority,code")
        .map(this::toRoundingRuleView)
        .stream()
        .toList();
  }

  private RoundingRuleView toRoundingRuleView(Record r) {
    var mode = RoundingModeOption.parse(r.get("rounding_mode", String.class));
    return new RoundingRuleView(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("target_currency", String.class),
        r.get("rounding_mode", String.class),
        mode == null ? r.get("rounding_mode", String.class) : mode.label(),
        mode == null ? "" : mode.basis(),
        r.get("rounding_scale", Integer.class) == null ? 0 : r.get("rounding_scale", Integer.class),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        r.get("priority", Integer.class) == null ? 0 : r.get("priority", Integer.class),
        r.get("note", String.class),
        r.get("version", Long.class) == null ? 0L : r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /**
   * 解析要用的舍入规则。
   *
   * <p><b>🔴 先具体后通用</b>：先找适用币种正好等于目标币种的规则（同组内按 priority 升序），
   * 找不到再找通用兜底规则。一步到位按 priority 抓会让通用规则盖掉针对 JPY 的零位规则 ——
   * 日元按两位小数入账是真实存在的账务事故。
   */
  private ResolvedRule resolveRoundingRule(String code, String targetCurrency) {
    if (notBlank(code)) {
      var normalized = code.trim().toUpperCase(Locale.ROOT);
      var row = roundingRuleByCode(normalized);
      if (row == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "舍入规则「" + normalized + "」不存在，请先在舍入规则配置里登记");
      }
      if (!row.enabled()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "舍入规则「" + normalized + "」已停用：停用只挡住「再用它」，已冻结的快照仍可读");
      }
      if (row.targetCurrency() != null && !row.targetCurrency().equals(targetCurrency)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "舍入规则「"
                + normalized
                + "」只适用于目标币种 "
                + row.targetCurrency()
                + "，与本次换算的目标币种 "
                + targetCurrency
                + " 不符");
      }
      return new ResolvedRule(
          row.code(),
          RoundingModeOption.parse(row.roundingMode()).mode(),
          row.roundingScale());
    }
    var specific =
        sql.fetchOne(
            "select code,rounding_mode,rounding_scale from exchange_rate_rounding_rule"
                + " where tenant_id="
                + TENANT
                + " and enabled and target_currency=?"
                + " order by priority,code limit 1",
            targetCurrency);
    if (specific != null) {
      return new ResolvedRule(
          specific.get("code", String.class),
          RoundingModeOption.parse(specific.get("rounding_mode", String.class)).mode(),
          specific.get("rounding_scale", Integer.class));
    }
    var generic =
        sql.fetchOne(
            "select code,rounding_mode,rounding_scale from exchange_rate_rounding_rule"
                + " where tenant_id="
                + TENANT
                + " and enabled and target_currency is null"
                + " order by priority,code limit 1");
    if (generic == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "没有可用的舍入规则（目标币种 "
              + targetCurrency
              + "）：先在舍入规则配置里初始化内置规则或新建一条。"
              + "本服务不会偷偷按默认口径舍入 —— 舍入方式必须落在快照里才能复算。");
    }
    return new ResolvedRule(
        generic.get("code", String.class),
        RoundingModeOption.parse(generic.get("rounding_mode", String.class)).mode(),
        generic.get("rounding_scale", Integer.class));
  }

  private record ResolvedRule(String code, java.math.RoundingMode mode, int scale) {}

  private RoundingRuleView roundingRuleByCode(String code) {
    var row =
        sql.fetchOne(
            "select id,code,name,target_currency,rounding_mode,rounding_scale,enabled,priority,note,"
                + "version,created_at,updated_at"
                + " from exchange_rate_rounding_rule where tenant_id="
                + TENANT
                + " and code=?",
            code);
    return row == null ? null : toRoundingRuleView(row);
  }

  /** 新建舍入规则。 */
  public RoundingRuleView createRoundingRule(AccessService.Access actor, RoundingRuleInput input) {
    var problems =
        ExchangeRateSnapshotRules.validateRoundingRule(
            input.code(), input.name(), input.targetCurrency(), input.roundingMode(), input.roundingScale());
    if (!problems.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("；", problems));
    }
    var code = input.code().trim().toUpperCase(Locale.ROOT);
    var mode = RoundingModeOption.parse(input.roundingMode());
    var currency = ExchangeRateRules.normalizeCurrency(input.targetCurrency());
    var existing =
        sql.fetchOne(
            "select id from exchange_rate_rounding_rule where tenant_id=" + TENANT + " and code=?",
            code);
    CatalogService.check(existing == null, "舍入规则代码已存在：" + code);

    var id = Ids.next();
    try {
      sql.execute(
          "insert into exchange_rate_rounding_rule(id,tenant_id,code,name,target_currency,rounding_mode,"
              + "rounding_scale,enabled,priority,note,created_by)"
              + " values(?,?,?,?,?,?,?,?,?,?,?)",
          id,
          actor.tenantId(),
          code,
          input.name().trim(),
          currency,
          mode.name(),
          input.roundingScale(),
          input.enabled() == null || input.enabled(),
          input.priority() == null ? 100 : input.priority(),
          input.note() == null ? "" : input.note().trim(),
          actor.userId());
    } catch (RuntimeException e) {
      throw translateWriteFailure(e, "舍入规则「" + code + "」");
    }
    audit(actor, id, "ExchangeRateRoundingRuleCreated");
    return roundingRuleByCode(code);
  }

  /**
   * 修改舍入规则（走乐观锁）。
   *
   * <p><b>🔴 改规则不动历史快照</b>：快照里冻的是当时的模式与小数位，
   * 所以这里可以放心改配置 —— 历史利润不会跟着变。
   */
  public RoundingRuleView updateRoundingRule(
      AccessService.Access actor, String code, RoundingRuleUpdate input) {
    var normalized = code.trim().toUpperCase(Locale.ROOT);
    var current = roundingRuleByCode(normalized);
    if (current == null) throw missing("舍入规则不存在：" + normalized);
    var problems =
        ExchangeRateSnapshotRules.validateRoundingRule(
            normalized, input.name(), input.targetCurrency(), input.roundingMode(), input.roundingScale());
    if (!problems.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("；", problems));
    }
    var mode = RoundingModeOption.parse(input.roundingMode());
    var currency = ExchangeRateRules.normalizeCurrency(input.targetCurrency());
    CatalogService.check(
        current.version() == input.version(), "舍入规则已被他人修改，请刷新后重试");

    int updated;
    try {
      updated =
          sql.execute(
              "update exchange_rate_rounding_rule set name=?,target_currency=?,rounding_mode=?,"
                  + "rounding_scale=?,enabled=?,priority=?,note=?,version=version+1,updated_at=now()"
                  + " where tenant_id="
                  + TENANT
                  + " and code=? and version=?",
              input.name().trim(),
              currency,
              mode.name(),
              input.roundingScale(),
              input.enabled(),
              input.priority(),
              input.note() == null ? "" : input.note().trim(),
              normalized,
              input.version());
    } catch (RuntimeException e) {
      throw translateWriteFailure(e, "舍入规则「" + normalized + "」");
    }
    if (updated != 1) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "舍入规则已被他人修改，请刷新后重试");
    }
    audit(actor, current.id(), "ExchangeRateRoundingRuleUpdated");
    return roundingRuleByCode(normalized);
  }

  /**
   * 初始化**内置舍入规则**（通用两位 + 常见币种 + 日元/韩元零位）。
   *
   * <p>幂等：已存在的码不覆盖（租户改过的名称与位数不会被这按钮冲掉），只补齐缺失的。
   */
  public List<RoundingRuleView> initializeRoundingRules(AccessService.Access actor) {
    var builtins =
        List.of(
            new BuiltinRule("DEFAULT", "通用两位小数", null, "HALF_UP", 2, 100,
                "通用兜底规则：任何目标币种都没单独配规则时用它（两位小数、四舍五入）。"),
            new BuiltinRule("CNY", "人民币两位小数", "CNY", "HALF_UP", 2, 10,
                "人民币结算口径：两位小数、四舍五入。"),
            new BuiltinRule("USD", "美元两位小数", "USD", "HALF_UP", 2, 10,
                "美元结算口径：两位小数、四舍五入。"),
            new BuiltinRule("EUR", "欧元两位小数", "EUR", "HALF_UP", 2, 10,
                "欧元结算口径：两位小数、四舍五入。"),
            new BuiltinRule("GBP", "英镑两位小数", "GBP", "HALF_UP", 2, 10,
                "英镑结算口径：两位小数、四舍五入。"),
            new BuiltinRule("JPY", "日元零位小数", "JPY", "HALF_UP", 0, 10,
                "日元结算口径：**零位小数**（无小数货币）。按两位小数入账是真实存在的账务事故。"),
            new BuiltinRule("KRW", "韩元零位小数", "KRW", "HALF_UP", 0, 10,
                "韩元结算口径：**零位小数**（无小数货币）。"));
    for (var builtin : builtins) {
      var exists =
          sql.fetchOne(
              "select id from exchange_rate_rounding_rule where tenant_id=" + TENANT + " and code=?",
              builtin.code());
      if (exists != null) continue;
      try {
        sql.execute(
            "insert into exchange_rate_rounding_rule(id,tenant_id,code,name,target_currency,rounding_mode,"
                + "rounding_scale,enabled,priority,note,created_by)"
                + " values(?,?,?,?,?,?,?,true,?,?,?)",
            Ids.next(),
            actor.tenantId(),
            builtin.code(),
            builtin.name(),
            builtin.currency(),
            builtin.mode(),
            builtin.scale(),
            builtin.priority(),
            builtin.note(),
            actor.userId());
      } catch (RuntimeException e) {
        // 并发初始化时另一个请求可能已经插入了同一个码：忽略，不算失败。
        var translated = translateWriteFailure(e, "舍入规则「" + builtin.code() + "」");
        if (translated.getStatusCode() == HttpStatus.CONFLICT) continue;
        throw translated;
      }
    }
    return roundingRules();
  }

  private record BuiltinRule(
      String code, String name, String currency, String mode, int scale, int priority, String note) {}

  // ================================================================ 工具

  private long count(String sqlText, Object... params) {
    return sql.fetchOne(sqlText, params).get(0, Long.class);
  }

  private void audit(AccessService.Access actor, UUID resourceId, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(),
        actor.tenantId(),
        actor.userId(),
        resourceId,
        action);
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static ResponseStatusException missing(String what) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what);
  }

  /**
   * 把数据库层的写失败翻译成一句人话。
   *
   * <p>预检覆盖常规路径，DB 层的唯一索引与触发器兜住并发竞态：
   *
   * <ul>
   *   <li>冻结值与汇率记录不一致 ⇒ 400（冻结的必须是那一条汇率的真实值）；
   *   <li>同一 单据行 + 币种对 已有快照 ⇒ 409（只能有一条，要更正请换行标识另冻一条）。
   * </ul>
   */
  private static ResponseStatusException translateWriteFailure(RuntimeException e, String what) {
    var message = rootMessage(e);
    if (message.contains("冻结的汇率") || message.contains("汇率记录不存在")) {
      return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
    if (message.contains("不允许修改")) {
      return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
    if (message.contains("exchange_rate_snapshot_tenant_id_document_type")) {
      return new ResponseStatusException(
          HttpStatus.CONFLICT,
          "这份单据的这一行已经有冻结的汇率快照了：同一 单据行 + 同一 币种对 只能有一条快照。"
              + "要更正请换单据行标识另冻一条（原快照原样保留）。");
    }
    if (message.contains("exchange_rate_rounding_rule_one_generic")) {
      return new ResponseStatusException(
          HttpStatus.CONFLICT, "一个租户只能有一条通用舍入规则（适用币种为空的那条）");
    }
    if (message.contains("exchange_rate_rounding_rule_tenant_id_code_key")) {
      return new ResponseStatusException(HttpStatus.CONFLICT, what + "已存在：规则代码在租户内唯一");
    }
    if (message.contains("duplicate key") || message.contains("唯一约束")) {
      return new ResponseStatusException(HttpStatus.CONFLICT, what + "写入失败：违反唯一约束");
    }
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, what + "写入失败：" + message);
  }

  /** 取最内层的异常消息（触发器的原始信息在嵌套的 cause 里）。 */
  private static String rootMessage(Throwable e) {
    var current = e;
    var text = e == null ? "" : String.valueOf(e.getMessage());
    while (current != null && current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
      var causeMessage = String.valueOf(current.getMessage());
      if (causeMessage != null && !causeMessage.isBlank() && !causeMessage.contains("null")) {
        text = causeMessage;
      }
    }
    return text == null ? "" : text;
  }
}
