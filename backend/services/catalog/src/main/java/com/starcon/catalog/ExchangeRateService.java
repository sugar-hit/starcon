package com.starcon.catalog;

import static com.starcon.catalog.ExchangeRateModels.*;

import com.starcon.catalog.ExchangeRateModels.EntryMode;
import com.starcon.catalog.ExchangeRateModels.FetchLogRow;
import com.starcon.catalog.ExchangeRateModels.FetchRequest;
import com.starcon.catalog.ExchangeRateModels.FetchResult;
import com.starcon.catalog.ExchangeRateModels.FunctionalCurrencyPanel;
import com.starcon.catalog.ExchangeRateModels.FunctionalCurrencyRow;
import com.starcon.catalog.ExchangeRateModels.RateCapabilities;
import com.starcon.catalog.ExchangeRateModels.RateInput;
import com.starcon.catalog.ExchangeRateModels.RateListPage;
import com.starcon.catalog.ExchangeRateModels.RateListQuery;
import com.starcon.catalog.ExchangeRateModels.RateSourceInput;
import com.starcon.catalog.ExchangeRateModels.RateSourceUpdate;
import com.starcon.catalog.ExchangeRateModels.RateSourceView;
import com.starcon.catalog.ExchangeRateModels.RateView;
import com.starcon.catalog.ExchangeRateModels.SourceKind;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.2.2.1 汇率查询。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：查询各币种对各本位币的汇率（来源：中行/央行/平台结算汇率，可配置），展示汇率日期与来源，支持手动录入特定日期的汇率。
 *
 * **输入**：币种对与日期；汇率来源配置；手工汇率录入。
 *
 * **输出**：汇率记录（汇率、方向、生效日期、来源）。
 *
 * **关系**：上游 = 外部汇率源；下游 = 5.2.2.2、5.3 测算、12 财务。**汇率方向必须显式**（1 USD = X CNY 与 1 CNY = X USD 混淆是常见错源）。
 *
 * </blockquote>
 *
 * <p>本类负责**四件事**：
 *
 * <ol>
 *   <li><b>汇率事实</b>：录入 / 查询 / 更正，全部走 jOOQ 显式 SQL（AGENTS.md：不用 JPA 懒加载）。
 *       方向由 {@code base_currency} + {@code quote_currency} 两个字段唯一确定；
 *   <li><b>来源配置</b>：租户级的来源（可配置），来源是汇率事实的指向；
 *   <li><b>外部拉取</b>：真的向配置的地址发 HTTP 请求，失败落日志并给明确错误态；
 *   <li><b>本位币面板</b>：经 {@link FunctionalCurrencyPort} 读 identity 的法人本位币（17.1.2.2）。
 * </ol>
 *
 * <p><b>🔴 汇率事实表归本服务</b>（catalog），12 财务（finance 服务）当前不存在，本模块不建
 * finance 侧的表、不读写 finance。trade 的 {@code fulfillment_dropship_fx_rate} 是另一个服务
 * 的另一个领域，本模块不跨服务读它。
 */
@Service
public class ExchangeRateService {

  /** 租户上下文：RLS 的依据。 */
  private static final String TENANT = "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 默认每页条数。 */
  private static final int DEFAULT_PAGE_SIZE = 20;

  /** 每页上限。 */
  private static final int MAX_PAGE_SIZE = 200;

  /**
   * 拉取日志每页条数上限。
   *
   * <p>日志是排障凭据，不该无限翻；要看更早的去用日期筛选。
   */
  private static final int MAX_LOG_PAGE_SIZE = 100;

  private final DSLContext sql;
  private final ExternalRateSourcePort external;
  private final FunctionalCurrencyPort functionalCurrencies;

  public ExchangeRateService(
      DSLContext sql,
      ExternalRateSourcePort external,
      FunctionalCurrencyPort functionalCurrencies) {
    this.sql = sql;
    this.external = external;
    this.functionalCurrencies = functionalCurrencies;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：来源清单、方向规则、唯一性规则、反向汇率规则、下游状态、数据归属。
   *
   * <p>页面上把它固定显示在顶部 —— 蓝图那句「汇率方向必须显式」靠它落地。
   */
  public RateCapabilities capabilities() {
    var sourceViews = sources();
    var entryModes = new ArrayList<String>();
    for (var kind : SourceKind.values()) {
      entryModes.add(kind.name() + "（" + kind.label() + "）：" + kind.basis());
    }
    return new RateCapabilities(
        sourceViews,
        ExchangeRateRules.DIRECTION_RULE,
        ExchangeRateRules.UNIQUENESS_RULE,
        ExchangeRateRules.INVERSE_RULE,
        entryModes,
        ExchangeRateRules.DOWNSTREAM_NOTE,
        ExchangeRateRules.OWNERSHIP_NOTE,
        ExchangeRateRules.FUNCTIONAL_CURRENCY_NOTE,
        ExchangeRateRules.FETCH_CONTRACT_NOTE);
  }

  // ================================================================ 来源配置

  /** 汇率来源列表（租户级配置）。 */
  public List<RateSourceView> sources() {
    return sql
        .fetch(
            "select s.id,s.code,s.name,s.kind,s.enabled,s.priority,s.endpoint,s.note,s.version,"
                + "s.created_at,s.updated_at,"
                + "(select count(*) from exchange_rate r"
                + "  where r.tenant_id=s.tenant_id and r.source_code=s.code) as rate_count"
                + " from exchange_rate_source s"
                + " where s.tenant_id="
                + TENANT
                + " order by s.enabled desc,s.priority,s.code")
        .map(this::toSourceView)
        .stream()
        .toList();
  }

  private RateSourceView toSourceView(Record r) {
    var kind = SourceKind.parse(r.get("kind", String.class));
    return new RateSourceView(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("kind", String.class),
        kind == null ? r.get("kind", String.class) : kind.label(),
        kind == null ? "" : kind.basis(),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        r.get("priority", Integer.class) == null ? 0 : r.get("priority", Integer.class),
        r.get("endpoint", String.class),
        r.get("note", String.class),
        r.get("rate_count", Long.class) == null ? 0L : r.get("rate_count", Long.class),
        r.get("version", Long.class) == null ? 0L : r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /**
   * 新建汇率来源。
   *
   * <p>来源码在租户内唯一：汇率事实按 {@code source_code} 指向它，重复的码会让「这条汇率来自哪」有多个答案。
   */
  public RateSourceView createSource(AccessService.Access actor, RateSourceInput input) {
    var code = input.code().trim().toUpperCase(Locale.ROOT);
    var name = input.name().trim();
    var kind = SourceKind.parse(input.kind());
    CatalogService.check(kind != null, "来源类型只能是 MANUAL（手工录入）或 EXTERNAL（外部拉取）");
    var endpoint = blankToNull(input.endpoint());
    CatalogService.check(
        kind != SourceKind.MANUAL || endpoint == null,
        "手工录入来源不应配地址：留了就是「配了却用不上」的死配置");
    var priority = input.priority() == null ? 100 : input.priority();

    var existing =
        sql.fetchOne(
            "select id from exchange_rate_source where tenant_id=" + TENANT + " and code=?", code);
    CatalogService.check(existing == null, "汇率来源代码已存在：" + code);

    var id = Ids.next();
    try {
      sql.execute(
          "insert into exchange_rate_source(id,tenant_id,code,name,kind,enabled,priority,endpoint,note,created_by)"
              + " values(?,?,?,?,?,?,?,?,?,?)",
          id,
          actor.tenantId(),
          code,
          name,
          kind.name(),
          input.enabled() == null || input.enabled(),
          priority,
          endpoint,
          input.note() == null ? "" : input.note().trim(),
          actor.userId());
    } catch (RuntimeException e) {
      throw translateWriteFailure(e, "汇率来源「" + code + "」");
    }
    audit(actor, id, "ExchangeRateSourceCreated");
    return sourceByCode(code);
  }

  /**
   * 修改汇率来源（走乐观锁）。
   *
   * <p>改 {@code kind} 时地址的必填性一起跟着变（MANUAL 不配地址、EXTERNAL 必须配地址）——
   * 否则会出现「标成外部拉取却没有地址」的死来源。
   */
  public RateSourceView updateSource(
      AccessService.Access actor, String code, RateSourceUpdate input) {
    var current =
        sql.fetchOne(
            "select id,kind,version from exchange_rate_source where tenant_id="
                + TENANT
                + " and code=?",
            code);
    if (current == null) throw missing("汇率来源不存在：" + code);
    var kind = SourceKind.parse(input.kind());
    CatalogService.check(kind != null, "来源类型只能是 MANUAL（手工录入）或 EXTERNAL（外部拉取）");
    var endpoint = blankToNull(input.endpoint());
    CatalogService.check(
        kind != SourceKind.MANUAL || endpoint == null,
        "手工录入来源不应配地址：留了就是「配了却用不上」的死配置");
    var version = current.get("version", Long.class);
    CatalogService.check(
        version == input.version(), "汇率来源已被他人修改，请刷新后重试");

    int updated;
    try {
      updated =
          sql.execute(
              "update exchange_rate_source set name=?,kind=?,enabled=?,priority=?,endpoint=?,note=?,"
                  + "version=version+1,updated_at=now()"
                  + " where tenant_id="
                  + TENANT
                  + " and code=? and version=?",
              input.name().trim(),
              kind.name(),
              input.enabled(),
              input.priority(),
              endpoint,
              input.note() == null ? "" : input.note().trim(),
              code,
              input.version());
    } catch (RuntimeException e) {
      throw translateWriteFailure(e, "汇率来源「" + code + "」");
    }
    if (updated != 1) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "汇率来源已被他人修改，请刷新后重试");
    }
    audit(actor, current.get("id", UUID.class), "ExchangeRateSourceUpdated");
    return sourceByCode(code);
  }

  /**
   * 初始化**内置来源**：把蓝图点名的三个外部源与手工录入源写成真实行。
   *
   * <p>幂等：已存在的码不覆盖（租户改过的名称与地址不会被这按钮冲掉），只补齐缺失的。
   *
   * <p>为什么要这个动作而不是「代码里写死四个来源」：来源是**租户级配置**
   * （可改名、可停用、可指向自己的平台结算地址），所以它必须落在租户自己的库里，
   * 由这个显式动作写入 —— 而不是每次读的时候从常量里造一份内存对象。
   */
  public List<RateSourceView> initializeSources(AccessService.Access actor) {
    var builtins =
        List.of(
            new BuiltinSource(
                "BANK_OF_CHINA", "中行牌价", SourceKind.EXTERNAL, 10,
                "中国银行外汇牌价。配置好 endpoint 后可由本模块真实拉取；未配置地址时只能手工录入。"),
            new BuiltinSource(
                "PBOC", "央行中间价", SourceKind.EXTERNAL, 20,
                "中国人民银行人民币汇率中间价。配置好 endpoint 后可由本模块真实拉取。"),
            new BuiltinSource(
                "PLATFORM_SETTLEMENT", "平台结算汇率", SourceKind.EXTERNAL, 30,
                "电商平台结算汇率（如各平台公布的结算折算率）。配置好 endpoint 后可由本模块真实拉取。"),
            new BuiltinSource(
                "MANUAL", "手工录入", SourceKind.MANUAL, 100,
                "运营手工登记的汇率。这是本模块**必须**落地的一条真实通路：外部源不可用时，"
                    + "手工录入仍然能查得到历史。"));
    var created = new ArrayList<String>();
    for (var builtin : builtins) {
      var exists =
          sql.fetchOne(
              "select id from exchange_rate_source where tenant_id=" + TENANT + " and code=?",
              builtin.code());
      if (exists != null) continue;
      try {
        sql.execute(
            "insert into exchange_rate_source(id,tenant_id,code,name,kind,enabled,priority,endpoint,note,created_by)"
                + " values(?,?,?,?,?,?,?,?,?,?)",
            Ids.next(),
            actor.tenantId(),
            builtin.code(),
            builtin.name(),
            builtin.kind().name(),
            true,
            builtin.priority(),
            null,
            builtin.note(),
            actor.userId());
      } catch (RuntimeException e) {
        // 并发初始化时另一个请求可能已经插入了同一个码：忽略，不算失败。
        var translated = translateWriteFailure(e, "汇率来源「" + builtin.code() + "」");
        if (translated.getStatusCode() == HttpStatus.CONFLICT) continue;
        throw translated;
      }
      created.add(builtin.code());
    }
    if (!created.isEmpty()) {
      audit(actor, Ids.next(), "ExchangeRateSourcesInitialized");
    }
    return sources();
  }

  private record BuiltinSource(
      String code, String name, SourceKind kind, int priority, String note) {}

  private RateSourceView sourceByCode(String code) {
    var row =
        sql.fetchOne(
            "select s.id,s.code,s.name,s.kind,s.enabled,s.priority,s.endpoint,s.note,s.version,"
                + "s.created_at,s.updated_at,"
                + "(select count(*) from exchange_rate r"
                + "  where r.tenant_id=s.tenant_id and r.source_code=s.code) as rate_count"
                + " from exchange_rate_source s"
                + " where s.tenant_id="
                + TENANT
                + " and s.code=?",
            code);
    if (row == null) throw missing("汇率来源不存在：" + code);
    return toSourceView(row);
  }

  // ================================================================ 汇率台账

  /** 汇率台账：分页 + 币种对 / 来源 / 日期区间 / 关键词筛选。 */
  public RateListPage list(RateListQuery query) {
    int size = query.size() <= 0 ? DEFAULT_PAGE_SIZE : Math.min(query.size(), MAX_PAGE_SIZE);
    int page = Math.max(query.page(), 0);
    var where = new StringBuilder(" where r.tenant_id=" + TENANT);
    var binds = new ArrayList<Object>();
    if (notBlank(query.baseCurrency())) {
      where.append(" and r.base_currency=?");
      binds.add(ExchangeRateRules.normalizeCurrency(query.baseCurrency()));
    }
    if (notBlank(query.quoteCurrency())) {
      where.append(" and r.quote_currency=?");
      binds.add(ExchangeRateRules.normalizeCurrency(query.quoteCurrency()));
    }
    if (notBlank(query.sourceCode())) {
      where.append(" and r.source_code=?");
      binds.add(query.sourceCode().trim().toUpperCase(Locale.ROOT));
    }
    if (query.dateFrom() != null) {
      where.append(" and r.effective_date>=?");
      binds.add(query.dateFrom());
    }
    if (query.dateTo() != null) {
      where.append(" and r.effective_date<=?");
      binds.add(query.dateTo());
    }
    if (notBlank(query.keyword())) {
      where.append(" and (r.base_currency ilike ? or r.quote_currency ilike ? or r.source_code ilike ? or r.source_ref ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    var from = " from exchange_rate r"
        + " left join exchange_rate_source s on s.tenant_id=r.tenant_id and s.code=r.source_code";
    long total = count("select count(*)" + from + where, binds.toArray());
    var records =
        sql.fetch(
            "select r.id,r.base_currency,r.quote_currency,r.rate,r.effective_date,r.source_code,"
                + "s.name as source_name,s.kind as source_kind,r.entry_mode,r.source_ref,r.note,"
                + "r.acquired_at,r.created_at,r.updated_at"
                + from
                + where
                + " order by r.effective_date desc,r.base_currency,r.quote_currency"
                + " limit "
                + size
                + " offset "
                + ((long) page * size),
            binds.toArray());
    var items = new ArrayList<RateView>();
    for (var record : records) items.add(toRateView(record));
    return new RateListPage(
        items,
        total,
        page,
        size,
        "汇率台账按生效日倒序。每条记录的方向由「基准币种 → 报价币种」确定：rate 表示 1 基准币种 = rate 报价币种。");
  }

  /** 单条汇率详情。 */
  public RateView rate(UUID id) {
    var row =
        sql.fetchOne(
            "select r.id,r.base_currency,r.quote_currency,r.rate,r.effective_date,r.source_code,"
                + "s.name as source_name,s.kind as source_kind,r.entry_mode,r.source_ref,r.note,"
                + "r.acquired_at,r.created_at,r.updated_at"
                + " from exchange_rate r"
                + " left join exchange_rate_source s on s.tenant_id=r.tenant_id and s.code=r.source_code"
                + " where r.tenant_id="
                + TENANT
                + " and r.id=?",
            id);
    if (row == null) throw missing("汇率记录不存在或没有访问权限");
    return toRateView(row);
  }

  /**
   * 某个币种对在 {@code asOf}（缺省今天）当天或之前**最新**的一条汇率。
   *
   * <p><b>🔴 查不到就报查不到</b>，不回退到「最近的一版」—— 静默回退会让一次按过期汇率算出的
   * 利润看起来完全正常，这是最贵的一类错误。
   */
  public RateView latest(String baseCurrency, String quoteCurrency, LocalDate asOf) {
    var base = ExchangeRateRules.normalizeCurrency(baseCurrency);
    var quote = ExchangeRateRules.normalizeCurrency(quoteCurrency);
    CatalogService.check(
        ExchangeRateRules.validCurrency(base), "基准币种请用三位大写币种代码（如 USD）");
    CatalogService.check(
        ExchangeRateRules.validCurrency(quote), "报价币种请用三位大写币种代码（如 CNY）");
    var on = asOf == null ? LocalDate.now() : asOf;
    var row =
        sql.fetchOne(
            "select r.id,r.base_currency,r.quote_currency,r.rate,r.effective_date,r.source_code,"
                + "s.name as source_name,s.kind as source_kind,r.entry_mode,r.source_ref,r.note,"
                + "r.acquired_at,r.created_at,r.updated_at"
                + " from exchange_rate r"
                + " left join exchange_rate_source s on s.tenant_id=r.tenant_id and s.code=r.source_code"
                + " where r.tenant_id="
                + TENANT
                + " and r.base_currency=? and r.quote_currency=? and r.effective_date<=?"
                + " order by r.effective_date desc"
                + " limit 1",
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
              + "）。本服务不会拿别的币种对或过期日期的汇率顶替 —— 查不到就是查不到。");
    }
    return toRateView(row);
  }

  private RateView toRateView(Record r) {
    var base = r.get("base_currency", String.class);
    var quote = r.get("quote_currency", String.class);
    var rate = r.get("rate", BigDecimal.class);
    var entryMode = EntryMode.parse(r.get("entry_mode", String.class));
    return new RateView(
        r.get("id", UUID.class),
        base,
        quote,
        rate,
        r.get("effective_date", LocalDate.class),
        r.get("source_code", String.class),
        r.get("source_name", String.class),
        r.get("source_kind", String.class),
        r.get("entry_mode", String.class),
        entryMode == null ? ExchangeRateRules.entryModeLabel(r.get("entry_mode", String.class)) : entryMode.label(),
        r.get("source_ref", String.class),
        r.get("note", String.class),
        r.get("acquired_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class),
        ExchangeRateRules.display(base, quote, rate),
        ExchangeRateRules.inverseRate(rate),
        ExchangeRateRules.inverseDisplay(base, quote, rate),
        true);
  }

  // ================================================================ 手工录入

  /**
   * 手工录入一条汇率。
   *
   * <p><b>🔴 方向是结构</b>：本方法只接受「基准币种 + 报价币种 + 汇率」三者，
   * 方向由前两个字段唯一确定。反向汇率**不许**手工再录一条方向相反的
   * （那是两个互相矛盾的事实）—— 应用层预检 + DB 触发器双重挡住。
   */
  public RateView create(AccessService.Access actor, RateInput input) {
    var source = sourceOf(input.sourceCode());
    var problems =
        ExchangeRateRules.validateManual(input, source.kind(), source.enabled());
    if (!problems.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("；", problems));
    }
    var base = ExchangeRateRules.normalizeCurrency(input.baseCurrency());
    var quote = ExchangeRateRules.normalizeCurrency(input.quoteCurrency());
    var sourceCode = input.sourceCode().trim().toUpperCase(Locale.ROOT);

    guardNoDuplicate(base, quote, input.effectiveDate(), null);
    guardNoInverse(base, quote, input.effectiveDate(), null);

    var id = Ids.next();
    try {
      sql.execute(
          "insert into exchange_rate(id,tenant_id,base_currency,quote_currency,rate,effective_date,"
              + "source_code,entry_mode,source_ref,note,acquired_at,created_by)"
              + " values(?,?,?,?,?,?,?,?,?,?,?::timestamptz,?)",
          id,
          actor.tenantId(),
          base,
          quote,
          input.rate().setScale(ExchangeRateRules.RATE_SCALE, ExchangeRateRules.ROUNDING),
          input.effectiveDate(),
          sourceCode,
          EntryMode.MANUAL.name(),
          input.sourceRef() == null ? "" : input.sourceRef().trim(),
          input.note() == null ? "" : input.note().trim(),
          OffsetDateTime.now(),
          actor.userId());
    } catch (RuntimeException e) {
      throw translateWriteFailure(e, "汇率 " + base + "/" + quote);
    }
    audit(actor, id, "ExchangeRateCreated");
    return rate(id);
  }

  /**
   * 更正一条已登记的汇率（同一生效日的同一条记录）。
   *
   * <p><b>更正 ≠ 新增事实</b>：同一天同一条汇率记错了就改它，不新增第二条 ——
   * 新增第二条会让「今天的汇率」有两个答案。反向守卫在更正时同样生效。
   */
  public RateView update(AccessService.Access actor, UUID id, RateInput input) {
    var existing =
        sql.fetchOne(
            "select id,base_currency,quote_currency,effective_date,entry_mode"
                + " from exchange_rate where tenant_id="
                + TENANT
                + " and id=?",
            id);
    if (existing == null) throw missing("汇率记录不存在或没有访问权限");
    var source = sourceOf(input.sourceCode());
    var problems =
        ExchangeRateRules.validateManual(input, source.kind(), source.enabled());
    if (!problems.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("；", problems));
    }
    var base = ExchangeRateRules.normalizeCurrency(input.baseCurrency());
    var quote = ExchangeRateRules.normalizeCurrency(input.quoteCurrency());
    var sourceCode = input.sourceCode().trim().toUpperCase(Locale.ROOT);
    guardNoDuplicate(base, quote, input.effectiveDate(), id);
    guardNoInverse(base, quote, input.effectiveDate(), id);

    try {
      sql.execute(
          "update exchange_rate set base_currency=?,quote_currency=?,rate=?,effective_date=?,"
              + "source_code=?,source_ref=?,note=?,updated_at=now()"
              + " where tenant_id="
              + TENANT
              + " and id=?",
          base,
          quote,
          input.rate().setScale(ExchangeRateRules.RATE_SCALE, ExchangeRateRules.ROUNDING),
          input.effectiveDate(),
          sourceCode,
          input.sourceRef() == null ? "" : input.sourceRef().trim(),
          input.note() == null ? "" : input.note().trim(),
          id);
    } catch (RuntimeException e) {
      throw translateWriteFailure(e, "汇率 " + base + "/" + quote);
    }
    audit(actor, id, "ExchangeRateUpdated");
    return rate(id);
  }

  /** 来源查找。找不到就报「未登记」，并指出要先配置来源。 */
  private SourceRow sourceOf(String code) {
    var normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    var row =
        sql.fetchOne(
            "select id,code,name,kind,enabled from exchange_rate_source where tenant_id="
                + TENANT
                + " and code=?",
            normalized);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "汇率来源「" + normalized + "」未登记，请先在汇率来源配置里登记该来源");
    }
    return new SourceRow(
        row.get("id", UUID.class),
        row.get("code", String.class),
        row.get("name", String.class),
        SourceKind.parse(row.get("kind", String.class)),
        Boolean.TRUE.equals(row.get("enabled", Boolean.class)));
  }

  private record SourceRow(
      UUID id, String code, String name, SourceKind kind, boolean enabled) {}

  /** 预检：同一 币种对 + 同一生效日 不能有第二条。 */
  private void guardNoDuplicate(String base, String quote, LocalDate date, UUID selfId) {
    var row =
        sql.fetchOne(
            "select id,source_code from exchange_rate where tenant_id="
                + TENANT
                + " and base_currency=? and quote_currency=? and effective_date=?"
                + (selfId == null ? "" : " and id<>?"),
            selfId == null
                ? new Object[] {base, quote, date}
                : new Object[] {base, quote, date, selfId});
    if (row != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "该币种对在 "
              + date
              + " 已有一条生效汇率（来源 "
              + row.get("source_code", String.class)
              + "）：同一 币种对 + 同一生效日 只能有一条生效记录，否则「今天的汇率」有多个答案。"
              + "要更正请修改那一条，不要再录一条。");
    }
  }

  /** 预检：同一生效日不许再录一条方向相反的。 */
  private void guardNoInverse(String base, String quote, LocalDate date, UUID selfId) {
    var row =
        sql.fetchOne(
            "select id,rate from exchange_rate where tenant_id="
                + TENANT
                + " and base_currency=? and quote_currency=? and effective_date=?"
                + (selfId == null ? "" : " and id<>?"),
            selfId == null
                ? new Object[] {quote, base, date}
                : new Object[] {quote, base, date, selfId});
    if (row != null) {
      var inverse = ExchangeRateRules.plain(row.get("rate", BigDecimal.class));
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "同一生效日（"
              + date
              + "）已存在方向相反的汇率：1 "
              + quote
              + " = "
              + inverse
              + " "
              + base
              + "。反向汇率必须由正向显式推导，不许手工再录一条方向相反的（那是两个互相矛盾的事实）。");
    }
  }

  // ================================================================ 外部拉取

  /**
   * 从配置的外部源拉一次汇率。
   *
   * <p><b>真的发 HTTP 请求</b>，失败有明确错误态：
   *   * 连不上 / 超时 / 非 2xx / 响应不认 ⇒ 落 FAILED 日志并返回 {@code status=FAILED}
   *     （端点层映射成 503），**不**静默返回空；
   *   * 拉到但某一行方向冲突 ⇒ 那一行被跳过并在结果里如实报出，其余行照常入库。
   *
   * <p><b>🔴 失败路径不抛异常</b>：抛异常会让外层事务回滚，把失败日志一起回滚掉 ——
   * 而失败日志正是这次拉取唯一留下的痕迹。所以这里返回带 {@code failureStatus} 的结果，
   * 由端点层决定 HTTP 状态码。
   */
  public FetchResult fetch(AccessService.Access actor, FetchRequest request) {
    var source = sourceOf(request.sourceCode());
    var base = ExchangeRateRules.normalizeCurrency(request.baseCurrency());
    var quote = ExchangeRateRules.normalizeCurrency(request.quoteCurrency());
    var date = request.effectiveDate();

    if (source.kind() != SourceKind.EXTERNAL) {
      var message =
          "汇率来源「" + source.code() + "」是「" + source.kind().label() + "」，不支持外部拉取";
      recordFetchLog(actor, source.code(), base, quote, date, "FAILED", null, 0, 0, message);
      return failed(source.code(), message, HttpStatus.BAD_REQUEST.value());
    }
    if (!source.enabled()) {
      var message = "汇率来源「" + source.code() + "」已停用";
      recordFetchLog(actor, source.code(), base, quote, date, "FAILED", null, 0, 0, message);
      return failed(source.code(), message, HttpStatus.BAD_REQUEST.value());
    }
    var endpoint = endpointOf(source.code());
    if (endpoint == null) {
      var message = "汇率来源「" + source.code() + "」没有配置外部地址：先在汇率来源配置里填地址再拉取";
      recordFetchLog(actor, source.code(), base, quote, date, "FAILED", null, 0, 0, message);
      return failed(source.code(), message, HttpStatus.BAD_REQUEST.value());
    }

    List<ExternalRateSourcePort.FetchedRate> fetched;
    Integer httpStatus = null;
    try {
      var outcome = external.fetch(endpoint, base, quote, date);
      fetched = outcome.rates();
      httpStatus = outcome.httpStatus();
    } catch (ExternalRateSourcePort.ExternalRateUnavailableException e) {
      recordFetchLog(actor, source.code(), base, quote, date, "FAILED", null, 0, 0, e.getMessage());
      return failed(source.code(), e.getMessage(), HttpStatus.SERVICE_UNAVAILABLE.value());
    }

    var saved = new ArrayList<RateView>();
    var problems = new ArrayList<String>();
    for (var row : fetched) {
      try {
        var rateBase = ExchangeRateRules.normalizeCurrency(row.baseCurrency());
        var rateQuote = ExchangeRateRules.normalizeCurrency(row.quoteCurrency());
        if (!ExchangeRateRules.validCurrency(rateBase) || !ExchangeRateRules.validCurrency(rateQuote)) {
          problems.add("外部源返回的币种码不合法，已跳过：" + row.baseCurrency() + "/" + row.quoteCurrency());
          continue;
        }
        if (rateBase.equals(rateQuote)) {
          problems.add("外部源返回了本币对本币的汇率，已跳过");
          continue;
        }
        var effective = row.effectiveDate() == null ? date : row.effectiveDate();
        upsertExternal(actor, source.code(), rateBase, rateQuote, row.rate(), effective, row.sourceRef());
        saved.add(rate(findRateId(rateBase, rateQuote, effective)));
      } catch (ResponseStatusException e) {
        // 预检拦下的冲突（方向相反 / 重复）：这一行跳过，其余行照常入库。
        problems.add(e.getReason() == null ? "写入失败" : e.getReason());
      } catch (RuntimeException e) {
        // 🔴 数据库级失败：PG 已把当前事务标记为 aborted，继续写只会连环失败。
        // 此时**不**吞掉异常 —— 让整次拉取失败并回滚（页面会看到明确错误），
        // 而不是报一个「拉到了数据但一条都没入库」的假象。
        throw new ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR, "外部汇率拉取写入失败：" + rootMessage(e));
      }
    }
    if (saved.isEmpty() && !problems.isEmpty()) {
      var message = String.join("；", problems);
      recordFetchLog(actor, source.code(), base, quote, date, "FAILED", httpStatus, fetched.size(), 0, message);
      return failed(source.code(), "外部汇率源拉到了数据但一条都没能入库：" + message, HttpStatus.SERVICE_UNAVAILABLE.value());
    }
    recordFetchLog(
        actor, source.code(), base, quote, date, "SUCCEEDED", httpStatus, fetched.size(), saved.size(),
        String.join("；", problems));
    return new FetchResult(
        "SUCCEEDED",
        source.code(),
        httpStatus,
        fetched.size(),
        saved.size(),
        String.join("；", problems),
        OffsetDateTime.now(),
        saved,
        null);
  }

  /** 失败结果（端点层按 {@code failureStatus} 返回对应状态码）。 */
  private FetchResult failed(String sourceCode, String message, int failureStatus) {
    return new FetchResult(
        "FAILED", sourceCode, null, 0, 0, message, OffsetDateTime.now(), List.of(), failureStatus);
  }

  /** 外部拉取的写入：同一天同一条已存在就更新（重复拉取是常态），否则新增。 */
  private void upsertExternal(
      AccessService.Access actor,
      String sourceCode,
      String base,
      String quote,
      BigDecimal rate,
      LocalDate effective,
      String sourceRef) {
    var existing =
        sql.fetchOne(
            "select id from exchange_rate where tenant_id="
                + TENANT
                + " and base_currency=? and quote_currency=? and effective_date=?",
            base,
            quote,
            effective);
    var scaled = rate.setScale(ExchangeRateRules.RATE_SCALE, ExchangeRateRules.ROUNDING);
    if (existing == null) {
      guardNoInverse(base, quote, effective, null);
      var id = Ids.next();
      sql.execute(
          "insert into exchange_rate(id,tenant_id,base_currency,quote_currency,rate,effective_date,"
              + "source_code,entry_mode,source_ref,note,acquired_at,created_by)"
              + " values(?,?,?,?,?,?,?,?,?,?,?::timestamptz,?)",
          id,
          actor.tenantId(),
          base,
          quote,
          scaled,
          effective,
          sourceCode,
          EntryMode.EXTERNAL.name(),
          sourceRef == null ? "" : sourceRef,
          "",
          OffsetDateTime.now(),
          actor.userId());
      audit(actor, id, "ExchangeRateFetched");
      return;
    }
    // 已存在 ⇒ 更正那一条（不是新增第二条）。方向守卫仍然生效。
    guardNoInverse(base, quote, effective, existing.get("id", UUID.class));
    sql.execute(
        "update exchange_rate set rate=?,source_code=?,entry_mode=?,source_ref=?,acquired_at=?::timestamptz,updated_at=now()"
            + " where tenant_id="
            + TENANT
            + " and id=?",
        scaled,
        sourceCode,
        EntryMode.EXTERNAL.name(),
        sourceRef == null ? "" : sourceRef,
        OffsetDateTime.now(),
        existing.get("id", UUID.class));
    audit(actor, existing.get("id", UUID.class), "ExchangeRateFetched");
  }

  private UUID findRateId(String base, String quote, LocalDate effective) {
    var row =
        sql.fetchOne(
            "select id from exchange_rate where tenant_id="
                + TENANT
                + " and base_currency=? and quote_currency=? and effective_date=?",
            base,
            quote,
            effective);
    if (row == null) throw missing("汇率记录不存在或没有访问权限");
    return row.get("id", UUID.class);
  }

  private String endpointOf(String code) {
    var row =
        sql.fetchOne(
            "select endpoint from exchange_rate_source where tenant_id=" + TENANT + " and code=?",
            code);
    if (row == null) return null;
    return blankToNull(row.get("endpoint", String.class));
  }

  /** 落一条拉取日志（只追加）。 */
  private void recordFetchLog(
      AccessService.Access actor,
      String sourceCode,
      String base,
      String quote,
      LocalDate date,
      String status,
      Integer httpStatus,
      int rowsFetched,
      int rowsSaved,
      String errorMessage) {
    sql.execute(
        "insert into exchange_rate_fetch_log(id,tenant_id,source_code,base_currency,quote_currency,"
            + "requested_date,status,http_status,rows_fetched,rows_saved,error_message,requested_by)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?)",
        Ids.next(),
        actor.tenantId(),
        sourceCode,
        base,
        quote,
        date,
        status,
        httpStatus,
        rowsFetched,
        rowsSaved,
        errorMessage == null ? "" : errorMessage,
        actor.userId());
  }

  /** 拉取日志（只读，倒序）。 */
  public List<FetchLogRow> fetchLog(int page, int size) {
    int limit = size <= 0 ? 20 : Math.min(size, MAX_LOG_PAGE_SIZE);
    int offset = Math.max(page, 0) * limit;
    return sql
        .fetch(
            "select id,source_code,base_currency,quote_currency,requested_date,status,http_status,"
                + "rows_fetched,rows_saved,error_message,requested_at"
                + " from exchange_rate_fetch_log where tenant_id="
                + TENANT
                + " order by requested_at desc,id desc"
                + " limit "
                + limit
                + " offset "
                + offset)
        .map(
            r ->
                new FetchLogRow(
                    r.get("id", UUID.class),
                    r.get("source_code", String.class),
                    r.get("base_currency", String.class),
                    r.get("quote_currency", String.class),
                    r.get("requested_date", LocalDate.class),
                    r.get("status", String.class),
                    r.get("http_status", Integer.class),
                    r.get("rows_fetched", Integer.class) == null ? 0 : r.get("rows_fetched", Integer.class),
                    r.get("rows_saved", Integer.class) == null ? 0 : r.get("rows_saved", Integer.class),
                    r.get("error_message", String.class),
                    r.get("requested_at", OffsetDateTime.class)))
        .stream()
        .toList();
  }

  // ================================================================ 本位币面板

  /**
   * 「各币种对**各本位币**的汇率」面板。
   *
   * <p>本位币来自 identity 的法人档案（17.1.2.2），经 {@link FunctionalCurrencyPort} 读取。
   * 读不到时抛 503（理由写「读不到本位币」），**不用空集合冒充「没有本位币」**。
   */
  public FunctionalCurrencyPanel functionalCurrencyPanel(String bearer) {
    List<FunctionalCurrencyPort.FunctionalCurrency> currencies;
    try {
      currencies = functionalCurrencies.list(bearer);
    } catch (FunctionalCurrencyPort.FunctionalCurrencyUnavailableException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
    var rows = new ArrayList<FunctionalCurrencyRow>();
    for (var currency : currencies) {
      var base = ExchangeRateRules.normalizeCurrency(currency.baseCurrency());
      rows.add(
          new FunctionalCurrencyRow(
              currency.legalEntityId(),
              currency.code(),
              currency.name(),
              base,
              ratesRelatedTo(base)));
    }
    return new FunctionalCurrencyPanel(
        rows,
        "本位币归法人（17.1.2.2，identity 服务），租户默认币种只是展示默认，两者不能混。"
            + "下列每条汇率的方向按记录本身显示；反向汇率由正向推导，不另存一条。");
  }

  /**
   * 与某个本位币相关的已登记汇率（两个方向都取，按记录本身的显示句呈现）。
   *
   * <p>用 {@code distinct on} 取每个币种对的最新一条：台账可能很长，
   * 逐对查会变成 N+1 次查询。
   */
  private List<RateView> ratesRelatedTo(String currency) {
    var records =
        sql.fetch(
            "select distinct on (r.base_currency,r.quote_currency) r.id,r.base_currency,"
                + "r.quote_currency,r.rate,r.effective_date,r.source_code,s.name as source_name,"
                + "s.kind as source_kind,r.entry_mode,r.source_ref,r.note,r.acquired_at,"
                + "r.created_at,r.updated_at"
                + " from exchange_rate r"
                + " left join exchange_rate_source s on s.tenant_id=r.tenant_id and s.code=r.source_code"
                + " where r.tenant_id="
                + TENANT
                + " and (r.quote_currency=? or r.base_currency=?)"
                + " order by r.base_currency,r.quote_currency,r.effective_date desc",
            currency,
            currency);
    var items = new ArrayList<RateView>();
    for (var record : records) items.add(toRateView(record));
    return items;
  }

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

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static ResponseStatusException missing(String what) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what);
  }

  /**
   * 把数据库层的写失败翻译成一句人话。
   *
   * <p>本仓的写路径有两道防线：应用层预检（{@code guardNoDuplicate} / {@code guardNoInverse} /
   * 来源校验）与 DB 层的唯一索引 + 守卫触发器。预检覆盖常规路径，触发器兜住并发竞态。
   * 这里把触发器抛出的原始消息翻译成对应的状态码：
   *
   * <ul>
   *   <li>方向相反 ⇒ 409（两个互相矛盾的事实）；
   *   <li>来源未登记 / 已停用 / 录入方式与来源类型不符 ⇒ 400（输入不合法）；
   *   <li>唯一约束冲突 ⇒ 409（同一币种对 + 同一生效日只能有一条）。
   * </ul>
   */
  private static ResponseStatusException translateWriteFailure(RuntimeException e, String what) {
    var message = rootMessage(e);
    if (message.contains("方向相反")) {
      return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
    if (message.contains("汇率来源")
        && (message.contains("未登记")
            || message.contains("已停用")
            || message.contains("录入方式与来源类型不符"))) {
      return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
    if (message.contains("duplicate key")
        || message.contains("唯一约束")
        || message.contains("exchange_rate_tenant_id_base_currency_quote_currency_effective_date_key")) {
      return new ResponseStatusException(
          HttpStatus.CONFLICT,
          "该 币种对 + 生效日 已有生效汇率：同一 币种对 + 同一生效日 只能有一条生效记录，"
              + "否则「今天的汇率」有多个答案。要更正请修改那一条，不要再录一条。");
    }
    if (message.contains("exchange_rate_source_tenant_id_code_key")) {
      return new ResponseStatusException(
          HttpStatus.CONFLICT, what + "已存在：来源代码在租户内唯一");
    }
    if (message.contains("已被") && message.contains("汇率")) {
      return new ResponseStatusException(HttpStatus.CONFLICT, message);
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

  /** 供前端「按本位币归集」使用的分组视图（保持顺序稳定）。 */
  public Map<String, List<RateView>> groupByQuoteCurrency(List<RateView> rates) {
    var grouped = new LinkedHashMap<String, List<RateView>>();
    for (var rate : rates) {
      grouped
          .computeIfAbsent(rate.quoteCurrency(), key -> new ArrayList<>())
          .add(rate);
    }
    return grouped;
  }
}
