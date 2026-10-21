package com.starcon.catalog;

import static com.starcon.catalog.PriceAnomalyModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.catalog.PriceAnomalyModels.AnomalyType;
import com.starcon.catalog.PriceAnomalyModels.EventStatus;
import com.starcon.catalog.PriceAnomalyModels.ObservationSource;
import com.starcon.catalog.PriceAnomalyModels.PriceChangeSource;
import com.starcon.catalog.PriceAnomalyModels.Severity;
import com.starcon.catalog.PriceAnomalyModels.SubjectKind;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * 5.5.2.1 异常告警：价格异常的观测台账、判定规则、异常事件与预警通知。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：监测价格异常：平台价与内部价长期不一致、售价低于成本线、平台自动降价、多站点价格倒挂（同商品不同站点价差导致窜货）。
 *
 * **输入**：平台价回流；内部价；成本线；站点价差规则。
 *
 * **输出**：异常清单 + 预警通知。
 *
 * **关系**：上游 = 4.3.2.2、5.2.1.1；下游 = 1 工作台预警、16 合规（窜货风控）。**站点价差倒挂是跨境特有的风险**：德国站定价低于美国站会引发跨区窜货与合规问题。
 *
 * </blockquote>
 *
 * <h2>本类的八条纪律</h2>
 *
 * <ol>
 *   <li><b>🔴 四类异常的数据来源不同，不能混成一条「价格对不上」</b>。①③ 来自本服务的
 *       <b>观测台账</b>（时间序列）；② 的成本线来自 <b>5.2.1.1 的成本事实</b>（V215，同服务）；
 *       ④ 来自 <b>站点两两比对</b>。每类异常的判定 SQL 各自独立。
 *   <li><b>🔴 「长期」与「发生过」是两种事件</b>：①②④ 是<b>当前状态</b>（不再命中就自动
 *       {@code RESOLVED}）；③ 平台自动降价是<b>发生过的事</b>，不会因检测窗口移走而消失，
 *       只能由人确认或消解。把 ③ 也做成「窗口移走就消失」，等于让「平台自动降过价」这件事
 *       在台账上凭空不见。
 *   <li><b>🔴 站点倒挂必须两两比对</b>。蓝图那句「德国站定价低于美国站」是两个站点之间的判断，
 *       不是「相对全平台平均价」的判断 —— 平均价差会把「德国比美国便宜、但比法国贵」
 *       这种<b>真实</b>倒挂洗掉。比对在 SQL 里做（站点对自连接 + 行值比较），
 *       事件上同时存<b>低的那一边</b>与<b>贵的那一边</b>。
 *   <li><b>🔴 跨币种不编汇率</b>。没有汇率就没有可比的价格；本仓汇率表属 5.2.2.1/5.2.2.2（未交付）。
 *       观测行自带 {@code comparison_currency + fx_rate + fx_rate_date + fx_rate_source}；
 *       没给汇率就只与同币种站点比；跨币种的站点对<b>如实报为不可比</b>，不静默丢弃，也不编汇率。
 *   <li><b>🔴 低于成本线用 5.2.1.1 的成本事实，判定写进 SQL</b>。成本线 =
 *       {@code sku_cost_profile.current_revision_id → sku_cost_revision.total_unit_cost}（V215）。
 *       成本快照不完整时事件标 {@code costComplete=false} —— 那是「已知部分合计」，
 *       不是完整成本。判定语句在 SQL 里（{@code cmp_platform_price < total_unit_cost}），
 *       <b>不让前端拿浮点比</b>。
 *   <li><b>🔴 读不到 ≠ 没有</b>。成本线读不到 ⇒ 该维度不参与判定，
 *       而不是把「读不到成本」当成「成本为 0」（那会让每一件商品都变成「低于成本线」）。
 *   <li><b>🔴 事件是状态，不是快照</b>。{@code fingerprint} 是稳定幂等键，
 *       重复检测更新同一条事件的 {@code last_seen_at} / {@code duration_days}，
 *       不堆重复行；新事件才发预警通知（一个事件一条）。
 *   <li><b>租户只来自已认证成员关系</b>（{@link AccessService}），不读 {@code X-Tenant-Id} 头。
 *       每条 SQL 的 where 都带显式 {@code tenant_id=} 绑定，RLS 再兜一道。
 * </ol>
 */
@Service
public class PriceAnomalyService {

  /** 租户上下文取法：RLS 已按它隔离，这里带上只是让执行计划走租户前缀的索引。 */
  private static final String TENANT = "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 台账页大小上限：与相邻模块一致，防止一次拉爆整个租户的数据。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 检测窗口的默认跨度（天）。 */
  private static final int DEFAULT_WINDOW_DAYS = 90;

  /** 一次检测最多扫描多少条观测。**触顶时不静默截断** —— 响应里会写明只覆盖了最近多少条。 */
  static final int MAX_SCAN_OBSERVATIONS = 20000;

  /** 检测窗口内单次最多列出多少个「不可比站点对」。 */
  static final int MAX_UNCOMPARABLE_PAIRS = 200;

  /** 异常详情里最多带多少条相关观测。 */
  static final int MAX_DETAIL_OBSERVATIONS = 50;

  private static final ObjectMapper JSON = new ObjectMapper();

  private final DSLContext sql;

  public PriceAnomalyService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：四个输入各自的来源、能不能取到、归谁管；四类异常的判定口径。
   *
   * <p>🔴 每个输入都写清「真值在哪、当前取不取得到、缺了会怎样」。
   * 一个只说「平台价来自平台」的声明，等于把「平台价目前靠登记进来」这件事藏起来。
   */
  public PriceAnomalyCapabilities capabilities() {
    var inputs =
        List.of(
            new InputCapability(
                "PLATFORM_PRICE",
                "平台价",
                "PRICE_OBSERVATION_LEDGER",
                true,
                "05 定价与价目（本服务）· price_anomaly_observation（V222）",
                "平台价（平台上实际在售的价）在本仓**没有任何自动来源**：channel 的 listing 表"
                    + "自己写着「无价格列：售价归 05 定价」，而 05 定价整卷此前只交付了成本构成。"
                    + "⇒ 平台价由调用方经**登记观测**入口写入本服务的观测台账；"
                    + "**平台自动回流（4.3.2.2）只回流状态、不回流价格**，所以这里没有跨服务调用。",
                "一行 = 一个 SKU 在一个站点上的一次观测；内部价与平台价都在这一行里，"
                    + "两者之差就是 ① 的判定依据。",
                "观测是**只追加**的：改价不改历史，历史观测留着，"
                    + "所以「这个价不一致持续了几天」才答得出来。"),
            new InputCapability(
                "INTERNAL_PRICE",
                "内部价",
                "PRICE_OBSERVATION_LEDGER",
                true,
                "05 定价与价目（本服务）· price_anomaly_observation（V222）",
                "内部价（我方价目表里的价）的**权威来源是 5.1.1.1 价目表 / 5.1.1.2 价目项**，"
                    + "而它们**未交付**（本仓没有 price_list 表）。⇒ 本模块的内部价由调用方"
                    + "登记进来，**不是** 5.1 的真实读数；5.1 落地后应改为经授权 API 供数。",
                "同一条观测里的 internal_price 字段。",
                "登记时必须同时标 source=PRICE_LIST，页面上会把「登记进来的内部价」与"
                    + "「价目表的权威价」区分开，不混为一谈。"),
            new InputCapability(
                "COST_LINE",
                "成本线",
                "CATALOG_COST_COMPOSITION",
                true,
                "05 定价与价目（本服务）· 5.2.1.1 成本构成（V215 sku_cost_revision）",
                "成本线取 5.2.1.1 写下的**当前版本合计**（sku_cost_profile.current_revision_id → "
                    + "sku_cost_revision.total_unit_cost），**不是拍脑袋的成本线**。"
                    + "该 SKU 没有成本构成时 ⇒ 这一维度**不参与判定**（读不到 ≠ 成本为 0）。",
                "同一服务、同一成本口径（同一张表、同一 currency）。",
                "成本快照不完整（snapshot_complete=false）时，事件标 costComplete=false —— "
                    + "那是「已知部分合计」，不是完整成本。"),
            new InputCapability(
                "SITE_GAP_RULE",
                "站点价差规则",
                "CATALOG_ANOMALY_RULE",
                true,
                "05 定价与价目（本服务）· price_anomaly_rule（V222）",
                "阈值（价差比例、持续天数、严重度）是**配置**，由租户在规则表里定；"
                    + "没有配置时用服务层的内置默认值（页面会标「默认阈值」，不假装租户已同意）。",
                "每种异常类型一条规则；阈值列可空 = 用默认值，显式给 0 是合法的。",
                "规则只影响**判定**，不产生结论；结论在事件表里。"));

    var types =
        List.of(
            new AnomalyCapability(
                "PLATFORM_INTERNAL_DIVERGENCE",
                AnomalyType.PLATFORM_INTERNAL_DIVERGENCE.label(),
                "同一站点的一串观测",
                true,
                "默认差 "
                    + PriceAnomalyRules.DEFAULT_DIVERGENCE_RATIO.toPlainString()
                    + "（5%）且持续 "
                    + PriceAnomalyRules.DEFAULT_DIVERGENCE_MIN_DURATION_DAYS
                    + " 天以上",
                "同一站点上「|平台价 − 内部价| ÷ 内部价」超阈值，且**连续**超阈值的天数达门槛。"
                    + "一次就对不上**不报** —— 蓝图要的是「长期」。",
                "连续段用 SQL 的窗口函数切分（连续命中才算一段），所以中间有一天恢复正常，"
                    + "持续天数就会从那里重新算起。"),
            new AnomalyCapability(
                "BELOW_COST",
                AnomalyType.BELOW_COST.label(),
                "售价 + 5.2.1.1 成本线",
                true,
                "默认容忍 "
                    + PriceAnomalyRules.DEFAULT_BELOW_COST_RATIO.toPlainString()
                    + "（售价低于成本线即命中）",
                "最新观测的售价（平台价 / 内部价各自成一条）低于 5.2.1.1 的成本线。"
                    + "判定写进 SQL：比较的是**折算到成本线币种之后**的金额。",
                "成本线币种与观测币种不一致且没有汇率时 ⇒ **不可比**，不判（不编汇率）。"),
            new AnomalyCapability(
                "PLATFORM_AUTO_DISCOUNT",
                AnomalyType.PLATFORM_AUTO_DISCOUNT.label(),
                "相邻两次观测 + 变动来源",
                true,
                "默认降 "
                    + PriceAnomalyRules.DEFAULT_AUTO_DISCOUNT_RATIO.toPlainString()
                    + "（2%）以上",
                "同一站点的平台价相对上一次**下降**，且这次的变动来源是 "
                    + "AUTOMATIC_PLATFORM（平台自动降的）。",
                "🔴 变动来源是 MANUAL_INTERNAL（我们自己改的）时**不算**平台自动降价 —— "
                    + "把自己的调价说成平台干的，结论最后查无实据。"),
            new AnomalyCapability(
                "CROSS_SITE_INVERSION",
                AnomalyType.CROSS_SITE_INVERSION.label(),
                "站点两两比对",
                true,
                "默认差 "
                    + PriceAnomalyRules.DEFAULT_CROSS_SITE_RATIO.toPlainString()
                    + "（10%）以上",
                "同一 SKU 的<b>两个站点</b>之间，站点价差超过阈值即判倒挂。比对在 SQL 里两两进行，"
                    + "事件上同时存<b>低的那一边</b>与<b>贵的那一边</b>。",
                "🔴 跨币种且没有汇率的站点对**不可比**，如实报出而不是编汇率；"
                    + "没有汇率表（5.2.2.1/5.2.2.2 未交付）时这就是德国站（EUR）与美国站（USD）"
                    + "之间的真实处境。"));

    return new PriceAnomalyCapabilities(
        inputs,
        types,
        effectiveRules(),
        "检测是**显式触发**的（POST /detect）：扫一个时间窗口，把四类异常各自算一遍，"
            + "把命中的写成事件、把不再命中的标成已消解、并给新事件发预警通知。"
            + "事件用 fingerprint 做幂等键 ⇒ 重复检测**不会**堆出一堆重复事件，"
            + "而是把同一条事件的「持续天数」往前推。",
        "上游真值：成本线 = 5.2.1.1 成本构成（V215，同服务）；"
            + "平台价与内部价 = 本服务的观测台账（V222）。"
            + "**4.3.2.2 平台状态回流只回流状态、不回流价格**，channel 的 listing 表也明确"
            + "「无价格列：售价归 05 定价」⇒ 本模块**不跨服务读价**，"
            + "平台价经**登记观测**入口进入，页面上写明这一点。",
        "下游：1 工作台预警（1.3.1.1 通知列表 / 1.3.2.1 预警规则）**当前未交付** ⇒ "
            + "预警通知只落本服务内的 price_anomaly_notification，owner_module 恒为 1.3.1.1、"
            + "delivered 恒为 false；16 合规（窜货风控）当前只有 16.2.2.1 合规预检，"
            + "**没有**跨区窜货判定模块 ⇒ 本模块**不跨服务写**，只在事件证据里留合规提示。",
        "本模块只写自己那四张表（price_anomaly_observation / _rule / _event / _notification）。"
            + "**成本线是读 5.2.1.1 的表，不是本模块另起炉灶的一条成本** —— "
            + "落一份成本副本就会立刻出现两个成本真相。",
        "可做的动作：登记价格观测（内部价 / 平台价 / 变动来源 / 比价口径）、"
            + "保存判定规则、运行检测、查看异常清单与详情、确认（acknowledge）与消解（resolve）异常、"
            + "查看预警通知。**没有**「就地改成本线」的入口 —— 成本要去 5.2.1.1 改。");
  }

  // ================================================================ 规则

  /** 生效规则：租户配置优先，未配置的类型用服务层内置默认值（并标 {@code overridden=false}）。 */
  public List<AnomalyRuleRow> effectiveRules() {
    var configured = new LinkedHashMap<String, Record>();
    for (var record :
        sql.fetch(
            "select id, anomaly_type, name, enabled, ratio_threshold, min_duration_days, severity, note,"
                + " updated_at from price_anomaly_rule where tenant_id = " + TENANT)) {
      configured.put(record.get("anomaly_type", String.class), record);
    }
    var rows = new ArrayList<AnomalyRuleRow>();
    for (var type : AnomalyType.values()) {
      var record = configured.get(type.name());
      if (record == null) {
        rows.add(
            new AnomalyRuleRow(
                null,
                type.name(),
                type.label(),
                PriceAnomalyRules.defaultRuleName(type),
                true,
                PriceAnomalyRules.defaultRatio(type),
                PriceAnomalyRules.defaultMinDurationDays(type),
                PriceAnomalyRules.defaultSeverity(type).name(),
                "未配置：使用服务层内置默认阈值（这是配置默认值，不是租户已同意的阈值）。",
                false,
                null));
        continue;
      }
      rows.add(
          new AnomalyRuleRow(
              record.get("id", UUID.class),
              type.name(),
              type.label(),
              record.get("name", String.class),
              Boolean.TRUE.equals(record.get("enabled", Boolean.class)),
              record.get("ratio_threshold", BigDecimal.class),
              record.get("min_duration_days", Integer.class),
              record.get("severity", String.class),
              record.get("note", String.class),
              true,
              record.get("updated_at", OffsetDateTime.class)));
    }
    return rows;
  }

  /**
   * 保存判定规则（一种异常类型一条）。
   *
   * <p>阈值列**可空 = 用内置默认值**；显式给 0 是合法的（0 是一个真实阈值）。
   */
  public AnomalyRuleRow saveRule(AccessService.Access actor, AnomalyRuleInput input) {
    var type = AnomalyType.parse(input.anomalyType());
    if (type == null) throw badRequest("未知的异常类型：" + input.anomalyType());
    var severity = Severity.parse(input.severity());
    if (severity == null) throw badRequest("未知的严重度：" + input.severity());
    if (input.ratioThreshold() != null
        && (input.ratioThreshold().signum() < 0
            || input.ratioThreshold().compareTo(BigDecimal.ONE) >= 0)) {
      throw badRequest("价差比例阈值必须在 [0,1) 之间：1.0 意味着永远不命中");
    }
    if (input.minDurationDays() != null && input.minDurationDays() < 0) {
      throw badRequest("持续天数门槛不能为负");
    }
    var id = Ids.next();
    sql.execute(
        "insert into price_anomaly_rule(id,tenant_id,anomaly_type,name,enabled,ratio_threshold,"
            + "min_duration_days,severity,note,created_by,created_at,updated_at)"
            + " values(?::uuid,?::uuid,?,?,?,?,?,?,?,?::uuid,now(),now())"
            + " on conflict (tenant_id,anomaly_type) do update set"
            + " name=excluded.name, enabled=excluded.enabled, ratio_threshold=excluded.ratio_threshold,"
            + " min_duration_days=excluded.min_duration_days, severity=excluded.severity,"
            + " note=excluded.note, updated_at=now()",
        id,
        actor.tenantId(),
        type.name(),
        input.name().trim(),
        input.enabled() == null || input.enabled(),
        input.ratioThreshold(),
        input.minDurationDays(),
        severity.name(),
        input.note() == null ? "" : input.note().trim(),
        actor.userId());
    return effectiveRules().stream()
        .filter(r -> r.anomalyType().equals(type.name()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("规则保存后却读不回来：" + type.name()));
  }

  // ================================================================ 观测台账

  /**
   * 价格观测台账。
   *
   * <p>列表以 SKU 为骨架（{@code price_anomaly_observation} JOIN {@code sku}）：
   * 看不到 SKU 就看不到它的观测（RLS 的 data_scope 策略兜底）。
   */
  public ObservationListPage listObservations(ObservationQuery query) {
    int size = query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
    int page = Math.max(query.page(), 0);
    var where = new StringBuilder(" where o.tenant_id = " + TENANT);
    var binds = new ArrayList<Object>();
    if (notBlank(query.keyword())) {
      where.append(" and (s.code ilike ? or s.name ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
    }
    if (notBlank(query.platformCode())) {
      where.append(" and o.platform_code = ?");
      binds.add(query.platformCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.siteCode())) {
      where.append(" and o.site_code = ?");
      binds.add(query.siteCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.currency())) {
      where.append(" and o.currency = ?");
      binds.add(query.currency().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.priceChangeSource())) {
      where.append(" and o.price_change_source = ?");
      binds.add(query.priceChangeSource().trim().toUpperCase(Locale.ROOT));
    }
    if (query.windowFrom() != null) {
      where.append(" and o.observed_at >= ?::timestamptz");
      binds.add(query.windowFrom().atStartOfDay().atOffset(ZoneOffset.UTC));
    }
    if (query.windowTo() != null) {
      where.append(" and o.observed_at < ?::timestamptz");
      binds.add(query.windowTo().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
    }

    String from = " from price_anomaly_observation o"
        + " join sku s on s.tenant_id = o.tenant_id and s.id = o.sku_id";
    long total = count("select count(*)" + from + where, binds.toArray());
    var records =
        sql.fetch(
            "select o.id, o.sku_id, s.code as sku_code, s.name as sku_name, o.platform_code, o.site_code,"
                + " o.currency, o.internal_price, o.platform_price, o.price_change_source, o.source,"
                + " o.comparison_currency, o.fx_rate, o.fx_rate_date, o.fx_rate_source, o.observed_at,"
                + " o.note"
                + from
                + where
                + " order by o.observed_at desc, o.id"
                + " limit "
                + size
                + " offset "
                + ((long) page * size),
            binds.toArray());

    var items = new ArrayList<ObservationRow>();
    for (var record : records) {
      items.add(observationRow(record));
    }
    return new ObservationListPage(
        items,
        total,
        page,
        size,
        "观测台账是**只追加**的：改价不改历史，历史观测留着 —— "
            + "「这个价不一致持续了几天」只有时间序列答得出来。"
            + "跨币种的站点之间要可比，登记时必须同时给 comparisonCurrency 与 fxRate"
            + "（含用哪天的汇率、哪来的汇率）；不给就只能与同币种站点比。");
  }

  /**
   * 登记一次价格观测。
   *
   * <p>🔴 至少要给一个价；两个都没有的观测没有任何信息量。
   *
   * <p>🔴 同一 SKU、同一站点、同一时刻**只允许一条**：重复登记撞唯一键而不是悄悄多一行。
   */
  public ObservationSaved registerObservation(AccessService.Access actor, ObservationInput input) {
    var changeSource = PriceChangeSource.parse(input.priceChangeSource());
    if (changeSource == null) throw badRequest("未知的变动来源：" + input.priceChangeSource());
    var source = ObservationSource.parse(input.source());
    if (source == null) throw badRequest("未知的观测来源：" + input.source());
    if (input.internalPrice() == null && input.platformPrice() == null) {
      throw badRequest("内部价与平台价至少要给一个：两个都没有的观测没有任何信息量");
    }
    if (input.internalPrice() != null && input.internalPrice().signum() < 0) {
      throw badRequest("内部价不能为负");
    }
    if (input.platformPrice() != null && input.platformPrice().signum() < 0) {
      throw badRequest("平台价不能为负");
    }
    if ((input.fxRate() == null) != (input.comparisonCurrency() == null)) {
      throw badRequest("比价币种与汇率必须同时给或同时不给：只给一个，比价口径就是残缺的");
    }
    if (input.fxRate() != null) {
      if (input.fxRate().signum() <= 0) throw badRequest("汇率必须大于 0");
      if (input.fxRateDate() == null || !notBlank(input.fxRateSource())) {
        throw badRequest("给了汇率就必须说清是哪天的汇率、哪来的汇率 —— 否则事后无法复算");
      }
    }
    // SKU 必须真实存在（同租户）：触发器也会兜一道，这里先给一句人话。
    if (!skuExists(actor.tenantId(), input.skuId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SKU 不存在");
    }
    var id = Ids.next();
    try {
      sql.execute(
          "insert into price_anomaly_observation(id,tenant_id,sku_id,platform_code,site_code,currency,"
              + "internal_price,platform_price,price_change_source,source,comparison_currency,fx_rate,"
              + "fx_rate_date,fx_rate_source,observed_at,note,created_by,created_at)"
              + " values(?::uuid,?::uuid,?::uuid,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?::uuid,now())",
          id,
          actor.tenantId(),
          input.skuId(),
          input.platformCode().trim().toUpperCase(Locale.ROOT),
          input.siteCode().trim().toUpperCase(Locale.ROOT),
          input.currency().trim().toUpperCase(Locale.ROOT),
          input.internalPrice(),
          input.platformPrice(),
          changeSource.name(),
          source.name(),
          input.comparisonCurrency() == null
              ? null
              : input.comparisonCurrency().trim().toUpperCase(Locale.ROOT),
          input.fxRate(),
          input.fxRateDate(),
          input.fxRateSource() == null ? "" : input.fxRateSource().trim(),
          input.observedAt(),
          input.note() == null ? "" : input.note().trim(),
          actor.userId());
    } catch (org.springframework.dao.DataIntegrityViolationException e) {
      // 🔴 观测是**只追加**的：同一 SKU、同一站点、同一时刻只允许一条。
      //   重复登记撞唯一键 ⇒ 409 + 一句人话，而不是 500。
      //   （按 SQLState 分支而不是按异常子类：jOOQ / Spring 对同一条 SQLState 的异常类型映射会随版本变动。）
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "同一 SKU、同一站点、同一时刻已经有一条观测了（观测是只追加的，不覆盖）");
    } catch (org.jooq.exception.DataAccessException e) {
      var state = sqlState(e);
      if (state != null && state.startsWith("23")) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "同一 SKU、同一站点、同一时刻已经有一条观测了（观测是只追加的，不覆盖）");
      }
      throw e;
    }
    return new ObservationSaved(
        id,
        input.skuId(),
        input.platformCode().trim().toUpperCase(Locale.ROOT),
        input.siteCode().trim().toUpperCase(Locale.ROOT),
        "观测已登记。历史观测不会被覆盖 —— 同一个时刻重复登记会被拒绝。");
  }

  // ================================================================ 异常清单

  /** 异常清单：分页 + 类型 / 状态 / 严重度 / 站点 / 关键词筛选。 */
  public EventListPage listEvents(EventQuery query) {
    int size = query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
    int page = Math.max(query.page(), 0);
    var where = new StringBuilder(" where e.tenant_id = " + TENANT);
    var binds = new ArrayList<Object>();
    if (notBlank(query.keyword())) {
      where.append(" and (s.code ilike ? or s.name ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
    }
    if (notBlank(query.anomalyType())) {
      where.append(" and e.anomaly_type = ?");
      binds.add(query.anomalyType().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.status())) {
      where.append(" and e.status = ?");
      binds.add(query.status().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.severity())) {
      where.append(" and e.severity = ?");
      binds.add(query.severity().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.platformCode())) {
      where.append(" and e.platform_code = ?");
      binds.add(query.platformCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.siteCode())) {
      where.append(" and (e.site_code = ? or e.related_site_code = ?)");
      binds.add(query.siteCode().trim().toUpperCase(Locale.ROOT));
      binds.add(query.siteCode().trim().toUpperCase(Locale.ROOT));
    }
    if (query.windowFrom() != null) {
      where.append(" and e.last_seen_at >= ?::timestamptz");
      binds.add(query.windowFrom().atStartOfDay().atOffset(ZoneOffset.UTC));
    }
    if (query.windowTo() != null) {
      where.append(" and e.last_seen_at < ?::timestamptz");
      binds.add(query.windowTo().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
    }

    String from = " from price_anomaly_event e"
        + " join sku s on s.tenant_id = e.tenant_id and s.id = e.sku_id";
    long total = count("select count(*)" + from + where, binds.toArray());
    var records =
        sql.fetch(
            "select e.id, e.anomaly_type, e.sku_id, s.code as sku_code, s.name as sku_name,"
                + " e.platform_code, e.site_code, e.related_platform_code, e.related_site_code,"
                + " e.currency, e.subject_kind, e.subject_price, e.reference_price, e.gap_amount,"
                + " e.gap_ratio, e.cost_line, e.cost_currency, e.cost_complete, e.cost_snapshot_at,"
                + " e.first_seen_at, e.last_seen_at, e.duration_days, e.severity, e.status, e.rule_id,"
                + " e.fingerprint, e.evidence, e.updated_at"
                + from
                + where
                + " order by case e.status when 'OPEN' then 0 when 'ACKNOWLEDGED' then 1 else 2 end,"
                + " e.last_seen_at desc, e.id"
                + " limit "
                + size
                + " offset "
                + ((long) page * size),
            binds.toArray());

    var items = new ArrayList<EventRow>();
    for (var record : records) {
      items.add(eventRow(record));
    }
    return new EventListPage(
        items,
        total,
        page,
        size,
        "清单里的事件是**有生命期的事实**：第一次命中到最后一次命中的天数就是「持续了多久」，"
            + "不再命中的会自动标成已消解（③ 平台自动降价除外 —— 它是**发生过的事**，"
            + "不会因窗口移走而消失）。");
  }

  /** 异常详情：事件 + 预警通知 + 相关观测。 */
  public EventDetail eventDetail(UUID eventId) {
    var record =
        sql.fetchOne(
            "select e.id, e.anomaly_type, e.sku_id, s.code as sku_code, s.name as sku_name,"
                + " e.platform_code, e.site_code, e.related_platform_code, e.related_site_code,"
                + " e.currency, e.subject_kind, e.subject_price, e.reference_price, e.gap_amount,"
                + " e.gap_ratio, e.cost_line, e.cost_currency, e.cost_complete, e.cost_snapshot_at,"
                + " e.first_seen_at, e.last_seen_at, e.duration_days, e.severity, e.status, e.rule_id,"
                + " e.fingerprint, e.evidence, e.updated_at"
                + " from price_anomaly_event e"
                + " join sku s on s.tenant_id = e.tenant_id and s.id = e.sku_id"
                + " where e.tenant_id = "
                + TENANT
                + " and e.id = ?::uuid",
            eventId);
    if (record == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "异常事件不存在");
    }
    var event = eventRow(record);

    var notifications = new ArrayList<NotificationRow>();
    for (var notification :
        sql.fetch(
            "select id, event_id, anomaly_type, severity, title, body, channel, recipient_scope,"
                + " owner_module, delivered, delivered_at, created_at from price_anomaly_notification"
                + " where tenant_id = "
                + TENANT
                + " and event_id = ?::uuid order by created_at desc",
            eventId)) {
      notifications.add(notificationRow(notification));
    }

    var observations = new ArrayList<ObservationRow>();
    for (var observation :
        sql.fetch(
            "select o.id, o.sku_id, s.code as sku_code, s.name as sku_name, o.platform_code, o.site_code,"
                + " o.currency, o.internal_price, o.platform_price, o.price_change_source, o.source,"
                + " o.comparison_currency, o.fx_rate, o.fx_rate_date, o.fx_rate_source, o.observed_at,"
                + " o.note from price_anomaly_observation o"
                + " join sku s on s.tenant_id = o.tenant_id and s.id = o.sku_id"
                + " where o.tenant_id = "
                + TENANT
                + " and o.sku_id = ?::uuid and o.platform_code = ? and o.site_code = ?"
                + " order by o.observed_at desc limit "
                + MAX_DETAIL_OBSERVATIONS,
            event.skuId(),
            event.platformCode(),
            event.siteCode())) {
      observations.add(observationRow(observation));
    }

    return new EventDetail(
        event,
        notifications,
        observations,
        basisOf(event),
        "事件详情给出判定依据（阈值、参照值、观测时刻）与相关观测 —— "
            + "「为什么这条是异常」要能从这里直接答出来。");
  }

  /** 事件状态流转：OPEN → ACKNOWLEDGED。 */
  public EventStatusSaved acknowledge(AccessService.Access actor, UUID eventId) {
    return transition(eventId, EventStatus.ACKNOWLEDGED);
  }

  /** 事件状态流转：OPEN / ACKNOWLEDGED → RESOLVED。 */
  public EventStatusSaved resolve(AccessService.Access actor, UUID eventId) {
    return transition(eventId, EventStatus.RESOLVED);
  }

  private EventStatusSaved transition(UUID eventId, EventStatus target) {
    var updated =
        sql.fetchOne(
            "update price_anomaly_event set status = ?::text, updated_at = now()"
                + " where tenant_id = "
                + TENANT
                + " and id = ?::uuid and status <> 'RESOLVED' returning id, status, updated_at",
            target.name(),
            eventId);
    if (updated == null) {
      // 分清「不存在」与「已经是终态」：后者不该被报成 404（它确实存在）。
      var existing =
          sql.fetchOne(
              "select id, status from price_anomaly_event where tenant_id = "
                  + TENANT
                  + " and id = ?::uuid",
              eventId);
      if (existing == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "异常事件不存在");
      }
      throw new ResponseStatusException(HttpStatus.CONFLICT, "异常事件已经是终态（已消解），不再变更");
    }
    return new EventStatusSaved(
        updated.get("id", UUID.class),
        updated.get("status", String.class),
        EventStatus.parse(updated.get("status", String.class)).label(),
        updated.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 通知

  /** 预警通知清单。 */
  public NotificationListPage listNotifications(EventQuery query) {
    int size = query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
    int page = Math.max(query.page(), 0);
    var where = new StringBuilder(" where n.tenant_id = " + TENANT);
    var binds = new ArrayList<Object>();
    if (notBlank(query.anomalyType())) {
      where.append(" and n.anomaly_type = ?");
      binds.add(query.anomalyType().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.severity())) {
      where.append(" and n.severity = ?");
      binds.add(query.severity().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.status())) {
      where.append(" and e.status = ?");
      binds.add(query.status().trim().toUpperCase(Locale.ROOT));
    }
    String from = " from price_anomaly_notification n"
        + " join price_anomaly_event e on e.tenant_id = n.tenant_id and e.id = n.event_id";
    long total = count("select count(*)" + from + where, binds.toArray());
    var records =
        sql.fetch(
            "select n.id, n.event_id, n.anomaly_type, n.severity, n.title, n.body, n.channel,"
                + " n.recipient_scope, n.owner_module, n.delivered, n.delivered_at, n.created_at"
                + from
                + where
                + " order by n.created_at desc, n.id limit "
                + size
                + " offset "
                + ((long) page * size),
            binds.toArray());
    var items = new ArrayList<NotificationRow>();
    for (var record : records) {
      items.add(notificationRow(record));
    }
    return new NotificationListPage(
        items,
        total,
        page,
        size,
        "通知是**本服务内的事实**：下游 1 工作台预警（1.3.1.1）当前未交付 ⇒ "
            + "ownerModule 恒为 1.3.1.1、delivered 恒为 false —— "
            + "本模块没有投递通道，标成「已送达」就是骗人。");
  }

  // ================================================================ 检测

  /**
   * 运行检测：扫一个时间窗口，四类异常各算一遍。
   *
   * <p>🔴 四类异常的判定各自一条 SQL，**站点倒挂是两两比对**（站点对自连接 + 行值比较），
   * 不是「相对全平台平均价」。
   *
   * <p>🔴 ①②④ 是**当前状态**：本轮没命中的旧事件会被标成已消解。
   * ③ 是**发生过的事**：不随窗口移走而消失。
   */
  public DetectionRun detect(AccessService.Access actor, LocalDate from, LocalDate to) {
    var windowFrom = from == null ? LocalDate.now().minusDays(DEFAULT_WINDOW_DAYS) : from;
    var windowTo = to == null ? LocalDate.now() : to;
    if (windowTo.isBefore(windowFrom)) {
      throw badRequest("检测窗口的终点不能早于起点");
    }
    var tenantId = actor.tenantId();
    var rules = ruleSettings();
    var start = windowFrom.atStartOfDay().atOffset(ZoneOffset.UTC);
    var end = windowTo.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

    long scannedCount =
        count(
            "select count(*) from price_anomaly_observation where tenant_id = "
                + TENANT
                + " and observed_at >= ?::timestamptz and observed_at < ?::timestamptz",
            start,
            end);
    int scanned = (int) Math.min(scannedCount, Integer.MAX_VALUE);
    boolean truncated = scannedCount > MAX_SCAN_OBSERVATIONS;

    int opened = 0;
    int updated = 0;
    int resolved = 0;
    int notifications = 0;
    var summary = new ArrayList<DetectionTypeSummary>();

    // ---------- ① 平台价与内部价长期不一致 ----------
    var divergence = detectDivergence(tenantId, rules.get(AnomalyType.PLATFORM_INTERNAL_DIVERGENCE), start, end);
    var divergenceOutcome = persist(divergence);
    opened += divergenceOutcome.opened();
    updated += divergenceOutcome.updated();
    notifications += divergenceOutcome.notifications();
    resolved += divergenceOutcome.resolved();
    summary.add(
        new DetectionTypeSummary(
            AnomalyType.PLATFORM_INTERNAL_DIVERGENCE.name(),
            AnomalyType.PLATFORM_INTERNAL_DIVERGENCE.label(),
            divergence.evaluated(),
            divergence.detected().size(),
            "连续命中才算一段持续；中间有一天恢复正常，持续天数从那里重新算起。"));

    // ---------- ② 售价低于成本线 ----------
    var belowCost = detectBelowCost(tenantId, rules.get(AnomalyType.BELOW_COST), start, end);
    var belowCostOutcome = persist(belowCost);
    opened += belowCostOutcome.opened();
    updated += belowCostOutcome.updated();
    notifications += belowCostOutcome.notifications();
    resolved += belowCostOutcome.resolved();
    summary.add(
        new DetectionTypeSummary(
            AnomalyType.BELOW_COST.name(),
            AnomalyType.BELOW_COST.label(),
            belowCost.evaluated(),
            belowCost.detected().size(),
            "成本线取 5.2.1.1 的当前版本合计；判定比较的是折算到成本线币种之后的金额"
                + "（币种不一致且没有汇率 ⇒ 不可比，不判）。"));

    // ---------- ③ 平台自动降价 ----------
    var discount = detectAutoDiscount(tenantId, rules.get(AnomalyType.PLATFORM_AUTO_DISCOUNT), start, end);
    var discountOutcome = persist(discount);
    opened += discountOutcome.opened();
    updated += discountOutcome.updated();
    notifications += discountOutcome.notifications();
    summary.add(
        new DetectionTypeSummary(
            AnomalyType.PLATFORM_AUTO_DISCOUNT.name(),
            AnomalyType.PLATFORM_AUTO_DISCOUNT.label(),
            discount.evaluated(),
            discount.detected().size(),
            "🔴 本窗口内我们自己改的降价 " + discount.selfInitiatedCount() + " 次 —— "
                + "那些**不算**平台自动降价（把自己的调价说成平台干的，结论最后查无实据）。"));

    // ---------- ④ 多站点价格倒挂 ----------
    var inversion = detectCrossSite(tenantId, rules.get(AnomalyType.CROSS_SITE_INVERSION), start, end);
    var inversionOutcome = persist(inversion);
    opened += inversionOutcome.opened();
    updated += inversionOutcome.updated();
    notifications += inversionOutcome.notifications();
    resolved += inversionOutcome.resolved();
    summary.add(
        new DetectionTypeSummary(
            AnomalyType.CROSS_SITE_INVERSION.name(),
            AnomalyType.CROSS_SITE_INVERSION.label(),
            inversion.evaluated(),
            inversion.detected().size(),
            "🔴 站点**两两**比对（不是全平台平均价差）；跨币种且无汇率的站点对 "
                + inversion.uncomparable().size()
                + " 组不可比 —— 没有汇率表（5.2.2.1/5.2.2.2 未交付）时不编汇率。"));

    return new DetectionRun(
        windowFrom,
        windowTo,
        scanned,
        opened,
        updated,
        resolved,
        notifications,
        summary,
        inversion.uncomparable(),
        truncated,
        truncated
            ? "🔴 观测条数（"
                + scannedCount
                + "）超过单次扫描上限（"
                + MAX_SCAN_OBSERVATIONS
                + "），本次结果只覆盖了最近的一部分 —— 请缩小窗口后重跑。"
            : "检测是一次**显式触发**的扫描：事件用 fingerprint 做幂等键，重复检测不会堆重复事件。");
  }

  /** 把一次检测的候选落库（新事件开事件通知，旧事件更新持续天数，不再命中的自动消解）。 */
  private PersistOutcome persist(DetectionOutcome outcome) {
    int opened = 0;
    int updated = 0;
    int notifications = 0;
    int resolved = 0;
    for (var candidate : outcome.detected()) {
      var result = upsert(candidate);
      if (result.inserted()) {
        opened++;
        raiseNotification(candidate, result.id());
        notifications++;
      } else {
        updated++;
      }
    }
    for (var fingerprint : outcome.resolved()) {
      if (resolveFingerprint(fingerprint) != null) {
        resolved++;
      }
    }
    return new PersistOutcome(opened, updated, notifications, resolved);
  }

  // ------------------------------------------------------------ ① 长期不一致

  private DetectionOutcome detectDivergence(
      UUID tenantId, RuleSettings rule, OffsetDateTime start, OffsetDateTime end) {
    var detected = new ArrayList<Candidate>();
    var resolved = new ArrayList<String>();
    if (!rule.enabled()) {
      return new DetectionOutcome(0, detected, resolved);
    }
    var threshold = rule.ratio();
    var minDays = rule.minDurationDays();
    var records =
        sql.fetch(
            "with obs as ("
                + " select o.sku_id, o.platform_code, o.site_code, o.currency, o.observed_at,"
                + "   case when o.internal_price > 0"
                + "     then round(abs(o.platform_price - o.internal_price) / o.internal_price, 6)"
                + "     else null end as ratio"
                + " from price_anomaly_observation o"
                + " where o.tenant_id = "
                + TENANT
                + "  and o.observed_at >= ?::timestamptz and o.observed_at < ?::timestamptz"
                + "  and o.internal_price is not null and o.platform_price is not null"
                + "  and o.internal_price > 0"
                + "), mark as ("
                + " select obs.*, case when ratio > ? then 1 else 0 end as breach from obs"
                + "), run as ("
                // 🔴 「连续段」必须用**反向**计数器切分：只在「**没有**超阈值」的行上 +1。
                //   若在「超阈值」的行上 +1（sum(breach)），每一行都会拿到不同的组号 ——
                //   连续段就被切成了「每段一个点」，持续天数永远算成 0。
                //   这个坑很隐蔽：SQL 不报错，只是把「持续 28 天」算成「持续 0 天」。
                + " select mark.*, sum(case when breach = 0 then 1 else 0 end) over ("
                + "   partition by sku_id, platform_code, site_code, currency"
                + "   order by observed_at rows unbounded preceding) as grp from mark"
                + "), runs as ("
                + " select sku_id, platform_code, site_code, currency, grp,"
                + "   min(observed_at) as run_start, max(observed_at) as run_end,"
                + "   count(*) as points, max(ratio) as max_ratio"
                + " from run where breach = 1"
                + " group by sku_id, platform_code, site_code, currency, grp"
                + "), keys as ("
                + " select distinct sku_id, platform_code, site_code, currency from obs"
                + "), last as ("
                + " select distinct on (sku_id, platform_code, site_code, currency)"
                + "   sku_id, platform_code, site_code, currency, observed_at"
                + " from obs order by sku_id, platform_code, site_code, currency, observed_at desc"
                + ")"
                + " select k.sku_id, k.platform_code, k.site_code, k.currency,"
                + "   r.run_start, r.run_end, r.points, r.max_ratio,"
                + "   case when r.run_start is null then 0"
                // 🔴 PostgreSQL 没有 date_diff（那是 MySQL / SQL Server 的写法）。
                //    PG 里 date - date 直接得到整数天数。
                + "        else (r.run_end::date - r.run_start::date) end as span_days"
                // 🔴 关键：只看**包含最新那次观测**的连续段。
                //    最新一次观测已经正常了 ⇒ 异常**当前不成立** ⇒ 事件自动消解。
                //    若只看「最近的一段偏离」，最新观测恢复正常时那段偏离仍会被当成现状。
                + " from keys k"
                + " join last l on l.sku_id = k.sku_id and l.platform_code = k.platform_code"
                + "   and l.site_code = k.site_code and l.currency = k.currency"
                + " left join lateral ("
                + "   select rr.* from runs rr"
                + "   where rr.sku_id = k.sku_id and rr.platform_code = k.platform_code"
                + "     and rr.site_code = k.site_code and rr.currency = k.currency"
                + "     and rr.run_end = l.observed_at"
                + "   order by rr.run_end desc limit 1"
                + " ) r on true"
                + " order by k.sku_id, k.platform_code, k.site_code, k.currency",
            start,
            end,
            threshold);

    var detectedFingerprints = new LinkedHashSet<String>();
    for (var record : records) {
      var skuId = record.get("sku_id", UUID.class);
      var platformCode = record.get("platform_code", String.class);
      var siteCode = record.get("site_code", String.class);
      var currency = record.get("currency", String.class);
      var spanDays = record.get("span_days", Integer.class);
      Long points = record.get("points", Long.class);
      var fingerprint =
          PriceAnomalyRules.fingerprint(
              AnomalyType.PLATFORM_INTERNAL_DIVERGENCE,
              skuId,
              platformCode,
              siteCode,
              "",
              "",
              currency,
              null,
              "");
      // 🔴 「长期」的三道闸门：连续段至少 2 个观测点、跨度达门槛、比例超阈值。
      //   一次就对不上不报 —— 那是噪声，不是「长期不一致」。
      //   points 为 null = 最新一次观测已经正常了（没有连续段以它结尾）⇒ 当前不成立。
      if (points == null || points < 2) continue;
      if (spanDays == null || spanDays < minDays) continue;
      detectedFingerprints.add(fingerprint);
      var evidence = new LinkedHashMap<String, Object>();
      evidence.put("threshold", threshold);
      evidence.put("minDurationDays", minDays);
      evidence.put("spanDays", spanDays);
      evidence.put("observationPoints", points);
      evidence.put("maxDivergenceRatio", record.get("max_ratio", BigDecimal.class));
      evidence.put("detectedBy", "同一站点的连续观测段");
      detected.add(
          new Candidate(
              AnomalyType.PLATFORM_INTERNAL_DIVERGENCE,
              tenantId,
              skuId,
              platformCode,
              siteCode,
              "",
              "",
              currency,
              null,
              null,
              null,
              null,
              record.get("max_ratio", BigDecimal.class),
              null,
              "",
              true,
              null,
              record.get("run_start", OffsetDateTime.class),
              record.get("run_end", OffsetDateTime.class),
              spanDays,
              rule.severity(),
              fingerprint,
              evidenceJson(evidence),
              rule.id()));
    }
    // 评估过但本轮没命中的 ⇒ 状态型异常自动消解。
    for (var record : records) {
      var fingerprint =
          PriceAnomalyRules.fingerprint(
              AnomalyType.PLATFORM_INTERNAL_DIVERGENCE,
              record.get("sku_id", UUID.class),
              record.get("platform_code", String.class),
              record.get("site_code", String.class),
              "",
              "",
              record.get("currency", String.class),
              null,
              "");
      if (!detectedFingerprints.contains(fingerprint)) {
        resolved.add(fingerprint);
      }
    }
    return new DetectionOutcome(records.size(), detected, resolved);
  }

  // ------------------------------------------------------------ ② 低于成本线

  private DetectionOutcome detectBelowCost(
      UUID tenantId, RuleSettings rule, OffsetDateTime start, OffsetDateTime end) {
    var detected = new ArrayList<Candidate>();
    var resolved = new ArrayList<String>();
    if (!rule.enabled()) {
      return new DetectionOutcome(0, detected, resolved);
    }
    var tolerance = rule.ratio();
    // 🔴 判定写进 SQL：比较的是折算到成本线币种之后的金额，币种不一致时不可比（不判）。
    var records =
        sql.fetch(
            "with cost as ("
                + " select p.sku_id, r.total_unit_cost, r.currency as cost_currency,"
                + "   r.effective_from, r.snapshot_complete"
                + " from sku_cost_profile p"
                + " join sku_cost_revision r on r.tenant_id = p.tenant_id and r.id = p.current_revision_id"
                + " where p.tenant_id = "
                + TENANT
                + "), latest as ("
                + " select distinct on (o.sku_id, o.platform_code, o.site_code)"
                + "   o.sku_id, o.platform_code, o.site_code, o.currency, o.observed_at,"
                + "   o.internal_price, o.platform_price, o.comparison_currency, o.fx_rate"
                + " from price_anomaly_observation o"
                + " where o.tenant_id = "
                + TENANT
                + "  and o.observed_at >= ?::timestamptz and o.observed_at < ?::timestamptz"
                + " order by o.sku_id, o.platform_code, o.site_code, o.observed_at desc"
                + ")"
                + " select l.sku_id, l.platform_code, l.site_code, l.currency, l.observed_at,"
                + "   l.internal_price, l.platform_price, l.comparison_currency, l.fx_rate,"
                + "   c.total_unit_cost, c.cost_currency, c.effective_from, c.snapshot_complete,"
                + "   coalesce(l.comparison_currency, l.currency) as cmp_currency,"
                + "   case when c.cost_currency = coalesce(l.comparison_currency, l.currency)"
                + "     then case when l.platform_price is null then null"
                + "          when l.fx_rate is null then l.platform_price"
                + "          else round(l.platform_price * l.fx_rate, 4) end"
                + "     else null end as cmp_platform_price,"
                + "   case when c.cost_currency = coalesce(l.comparison_currency, l.currency)"
                + "     then case when l.internal_price is null then null"
                + "          when l.fx_rate is null then l.internal_price"
                + "          else round(l.internal_price * l.fx_rate, 4) end"
                + "     else null end as cmp_internal_price"
                + " from latest l join cost c on c.sku_id = l.sku_id",
            start,
            end);

    var detectedFingerprints = new LinkedHashSet<String>();
    for (var record : records) {
      var skuId = record.get("sku_id", UUID.class);
      var platformCode = record.get("platform_code", String.class);
      var siteCode = record.get("site_code", String.class);
      var currency = record.get("currency", String.class);
      var costLine = record.get("total_unit_cost", BigDecimal.class);
      var costCurrency = record.get("cost_currency", String.class);
      var costComplete = Boolean.TRUE.equals(record.get("snapshot_complete", Boolean.class));
      var costAt = record.get("effective_from", OffsetDateTime.class);
      var cmpPlatform = record.get("cmp_platform_price", BigDecimal.class);
      var cmpInternal = record.get("cmp_internal_price", BigDecimal.class);
      var observedAt = record.get("observed_at", OffsetDateTime.class);
      var floor = costLine == null ? null : costLine.multiply(BigDecimal.ONE.subtract(tolerance));

      var platformFingerprint =
          PriceAnomalyRules.fingerprint(
              AnomalyType.BELOW_COST,
              skuId,
              platformCode,
              siteCode,
              "",
              "",
              currency,
              SubjectKind.PLATFORM_PRICE,
              "");
      var internalFingerprint =
          PriceAnomalyRules.fingerprint(
              AnomalyType.BELOW_COST,
              skuId,
              platformCode,
              siteCode,
              "",
              "",
              currency,
              SubjectKind.INTERNAL_PRICE,
              "");

      var platformHit = cmpPlatform != null && floor != null && cmpPlatform.compareTo(floor) < 0;
      var internalHit = cmpInternal != null && floor != null && cmpInternal.compareTo(floor) < 0;

      if (platformHit) {
        detectedFingerprints.add(platformFingerprint);
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("priceBasis", "PLATFORM_PRICE");
        evidence.put("costLine", costLine);
        evidence.put("costCurrency", costCurrency);
        evidence.put("costSnapshotComplete", costComplete);
        evidence.put("comparedPrice", cmpPlatform);
        evidence.put("tolerance", tolerance);
        evidence.put("detectedBy", "售价（折算到成本线币种后）低于 5.2.1.1 的成本线");
        detected.add(
            new Candidate(
                AnomalyType.BELOW_COST,
                tenantId,
                skuId,
                platformCode,
                siteCode,
                "",
                "",
                currency,
                SubjectKind.PLATFORM_PRICE,
                cmpPlatform,
                costLine,
                cmpPlatform.subtract(costLine),
                null,
                costLine,
                costCurrency,
                costComplete,
                costAt,
                observedAt,
                observedAt,
                0,
                rule.severity(),
                platformFingerprint,
                evidenceJson(evidence),
                rule.id()));
      }
      if (internalHit) {
        detectedFingerprints.add(internalFingerprint);
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("priceBasis", "INTERNAL_PRICE");
        evidence.put("costLine", costLine);
        evidence.put("costCurrency", costCurrency);
        evidence.put("costSnapshotComplete", costComplete);
        evidence.put("comparedPrice", cmpInternal);
        evidence.put("tolerance", tolerance);
        evidence.put("detectedBy", "内部价（折算到成本线币种后）低于 5.2.1.1 的成本线");
        detected.add(
            new Candidate(
                AnomalyType.BELOW_COST,
                tenantId,
                skuId,
                platformCode,
                siteCode,
                "",
                "",
                currency,
                SubjectKind.INTERNAL_PRICE,
                cmpInternal,
                costLine,
                cmpInternal.subtract(costLine),
                null,
                costLine,
                costCurrency,
                costComplete,
                costAt,
                observedAt,
                observedAt,
                0,
                rule.severity(),
                internalFingerprint,
                evidenceJson(evidence),
                rule.id()));
      }
    }
    for (var record : records) {
      var platformFingerprint =
          PriceAnomalyRules.fingerprint(
              AnomalyType.BELOW_COST,
              record.get("sku_id", UUID.class),
              record.get("platform_code", String.class),
              record.get("site_code", String.class),
              "",
              "",
              record.get("currency", String.class),
              SubjectKind.PLATFORM_PRICE,
              "");
      var internalFingerprint =
          PriceAnomalyRules.fingerprint(
              AnomalyType.BELOW_COST,
              record.get("sku_id", UUID.class),
              record.get("platform_code", String.class),
              record.get("site_code", String.class),
              "",
              "",
              record.get("currency", String.class),
              SubjectKind.INTERNAL_PRICE,
              "");
      if (!detectedFingerprints.contains(platformFingerprint)) {
        resolved.add(platformFingerprint);
      }
      if (!detectedFingerprints.contains(internalFingerprint)) {
        resolved.add(internalFingerprint);
      }
    }
    return new DetectionOutcome(records.size(), detected, resolved);
  }

  // ------------------------------------------------------------ ③ 平台自动降价

  private DetectionOutcome detectAutoDiscount(
      UUID tenantId, RuleSettings rule, OffsetDateTime start, OffsetDateTime end) {
    var detected = new ArrayList<Candidate>();
    var resolved = new ArrayList<String>();
    if (!rule.enabled()) {
      return new DetectionOutcome(0, detected, resolved);
    }
    var threshold = rule.ratio();
    var records =
        sql.fetch(
            "with series as ("
                + " select o.sku_id, o.platform_code, o.site_code, o.currency, o.observed_at,"
                + "   o.platform_price, o.price_change_source,"
                + "   lag(o.platform_price) over ("
                + "     partition by o.sku_id, o.platform_code, o.site_code, o.currency"
                + "     order by o.observed_at) as prev_price"
                + " from price_anomaly_observation o"
                + " where o.tenant_id = "
                + TENANT
                + "  and o.observed_at >= ?::timestamptz and o.observed_at < ?::timestamptz"
                + "  and o.platform_price is not null"
                + ")"
                + " select sku_id, platform_code, site_code, currency, observed_at, prev_price,"
                + "   platform_price, price_change_source,"
                + "   case when prev_price > 0"
                + "     then round((prev_price - platform_price) / prev_price, 6) end as drop_ratio"
                + " from series"
                + " where prev_price is not null and prev_price > 0 and platform_price < prev_price"
                + "   and price_change_source = 'AUTOMATIC_PLATFORM'"
                + "   and round((prev_price - platform_price) / prev_price, 6) > ?"
                + " order by observed_at desc",
            start,
            end,
            threshold);

    for (var record : records) {
      var skuId = record.get("sku_id", UUID.class);
      var platformCode = record.get("platform_code", String.class);
      var siteCode = record.get("site_code", String.class);
      var currency = record.get("currency", String.class);
      var observedAt = record.get("observed_at", OffsetDateTime.class);
      var current = record.get("platform_price", BigDecimal.class);
      var previous = record.get("prev_price", BigDecimal.class);
      var fingerprint =
          PriceAnomalyRules.discountFingerprint(
              skuId, platformCode, siteCode, currency, observedAt == null ? "" : observedAt.toString());
      var evidence = new LinkedHashMap<String, Object>();
      evidence.put("threshold", threshold);
      evidence.put("previousPrice", previous);
      evidence.put("currentPrice", current);
      evidence.put("dropRatio", record.get("drop_ratio", BigDecimal.class));
      evidence.put("priceChangeSource", record.get("price_change_source", String.class));
      evidence.put("detectedBy", "同一站点的相邻两次平台价观测，降价且变动来源为平台自动");
      detected.add(
          new Candidate(
              AnomalyType.PLATFORM_AUTO_DISCOUNT,
              tenantId,
              skuId,
              platformCode,
              siteCode,
              "",
              "",
              currency,
              SubjectKind.PLATFORM_PRICE,
              current,
              previous,
              previous.subtract(current),
              record.get("drop_ratio", BigDecimal.class),
              null,
              "",
              true,
              null,
              observedAt,
              observedAt,
              0,
              rule.severity(),
              fingerprint,
              evidenceJson(evidence),
              rule.id()));
    }
    // 同一批窗口里「我们自己改的」降价次数：用于向人证明「平台自动」与「我们自己改的」
    // 确实被区分开了 —— 而不是所有降价都被算成平台自动降价。
    long selfInitiated =
        count(
            "with series as ("
                + " select o.platform_price,"
                + "   lag(o.platform_price) over ("
                + "     partition by o.sku_id, o.platform_code, o.site_code, o.currency"
                + "     order by o.observed_at) as prev_price,"
                + "   o.price_change_source"
                + " from price_anomaly_observation o"
                + " where o.tenant_id = "
                + TENANT
                + "  and o.observed_at >= ?::timestamptz and o.observed_at < ?::timestamptz"
                + "  and o.platform_price is not null"
                + ")"
                + " select count(*) from series"
                + " where prev_price is not null and prev_price > 0 and platform_price < prev_price"
                + "   and price_change_source = 'MANUAL_INTERNAL'"
                + "   and round((prev_price - platform_price) / prev_price, 6) > ?",
            start,
            end,
            threshold);
    return new DetectionOutcome(records.size(), detected, resolved, (int) selfInitiated);
  }

  // ------------------------------------------------------------ ④ 站点倒挂

  private DetectionOutcome detectCrossSite(
      UUID tenantId, RuleSettings rule, OffsetDateTime start, OffsetDateTime end) {
    var detected = new ArrayList<Candidate>();
    var resolved = new ArrayList<String>();
    var uncomparable = new ArrayList<UncomparablePair>();
    if (!rule.enabled()) {
      return new DetectionOutcome(0, detected, resolved);
    }
    var threshold = rule.ratio();
    // 🔴 站点**两两**比对：a 与 b 用行值比较保证每个站点对只出现一次。
    //   比价口径 = coalesce(comparison_currency, currency)，跨币种且无汇率的站点对
    //   在下面的「不可比」查询里如实报出来，不静默丢弃，也不编汇率。
    var records =
        sql.fetch(
            "with latest as ("
                + " select distinct on (o.sku_id, o.platform_code, o.site_code)"
                + "   o.sku_id, o.platform_code, o.site_code, o.currency, o.observed_at,"
                + "   coalesce(o.platform_price, o.internal_price) as price,"
                + "   o.comparison_currency, o.fx_rate"
                + " from price_anomaly_observation o"
                + " where o.tenant_id = "
                + TENANT
                + "  and o.observed_at >= ?::timestamptz and o.observed_at < ?::timestamptz"
                + "  and (o.platform_price is not null or o.internal_price is not null)"
                + " order by o.sku_id, o.platform_code, o.site_code, o.observed_at desc"
                + "), norm as ("
                + " select l.*, coalesce(l.comparison_currency, l.currency) as cmp_currency,"
                + "   case when l.fx_rate is null then l.price"
                + "        else round(l.price * l.fx_rate, 6) end as cmp_price"
                + " from latest l"
                + ")"
                + " select a.sku_id, a.platform_code, a.site_code, a.currency, a.price as a_price,"
                + "   a.cmp_price as a_cmp, a.observed_at,"
                + "   b.platform_code as b_platform, b.site_code as b_site, b.currency as b_currency,"
                + "   b.price as b_price, b.cmp_price as b_cmp"
                + " from norm a join norm b"
                + "   on a.sku_id = b.sku_id and a.cmp_currency = b.cmp_currency"
                + "  and (a.platform_code, a.site_code) < (b.platform_code, b.site_code)"
                + " where b.cmp_price > a.cmp_price"
                + "   and round((b.cmp_price - a.cmp_price) / b.cmp_price, 6) > ?"
                + " order by a.sku_id, a.platform_code, a.site_code",
            start,
            end,
            threshold);

    var detectedFingerprints = new LinkedHashSet<String>();
    for (var record : records) {
      var skuId = record.get("sku_id", UUID.class);
      var platformCode = record.get("platform_code", String.class);
      var siteCode = record.get("site_code", String.class);
      var relatedPlatform = record.get("b_platform", String.class);
      var relatedSite = record.get("b_site", String.class);
      var currency = record.get("currency", String.class);
      var aPrice = record.get("a_price", BigDecimal.class);
      var aCmp = record.get("a_cmp", BigDecimal.class);
      var bPrice = record.get("b_price", BigDecimal.class);
      var bCmp = record.get("b_cmp", BigDecimal.class);
      var observedAt = record.get("observed_at", OffsetDateTime.class);
      var fingerprint =
          PriceAnomalyRules.fingerprint(
              AnomalyType.CROSS_SITE_INVERSION,
              skuId,
              platformCode,
              siteCode,
              relatedPlatform,
              relatedSite,
              currency,
              null,
              "");
      detectedFingerprints.add(fingerprint);
      var gapRatio = PriceAnomalyRules.siteGapRatio(aCmp, bCmp);
      var evidence = new LinkedHashMap<String, Object>();
      evidence.put("threshold", threshold);
      evidence.put("lowerSite", platformCode + "/" + siteCode);
      evidence.put("lowerSitePrice", aPrice);
      evidence.put("lowerSiteComparisonPrice", aCmp);
      evidence.put("higherSite", relatedPlatform + "/" + relatedSite);
      evidence.put("higherSitePrice", bPrice);
      evidence.put("higherSiteComparisonPrice", bCmp);
      evidence.put("comparisonBasis", "同一比价币种下的站点两两比对");
      evidence.put(
          "complianceHint",
          "站点价差倒挂是跨区窜货与合规风险（16 合规）：低价站点可能被跨区购买，"
              + "本模块只落事实与预警，窜货风控由 16 落地后经授权 API / event 衔接。");
      detected.add(
          new Candidate(
              AnomalyType.CROSS_SITE_INVERSION,
              tenantId,
              skuId,
              platformCode,
              siteCode,
              relatedPlatform,
              relatedSite,
              currency,
              null,
              aPrice,
              bPrice,
              aCmp == null || bCmp == null ? null : aCmp.subtract(bCmp),
              gapRatio,
              null,
              "",
              true,
              null,
              observedAt,
              observedAt,
              0,
              rule.severity(),
              fingerprint,
              evidenceJson(evidence),
              rule.id()));
    }

    // 跨币种的站点对：不可比，如实报出（不静默丢弃、不编汇率）。
    for (var record :
        sql.fetch(
            "with latest as ("
                + " select distinct on (o.sku_id, o.platform_code, o.site_code)"
                + "   o.sku_id, o.platform_code, o.site_code, o.currency,"
                + "   coalesce(o.platform_price, o.internal_price) as price,"
                + "   o.comparison_currency, o.fx_rate"
                + " from price_anomaly_observation o"
                + " where o.tenant_id = "
                + TENANT
                + "  and o.observed_at >= ?::timestamptz and o.observed_at < ?::timestamptz"
                + "  and (o.platform_price is not null or o.internal_price is not null)"
                + " order by o.sku_id, o.platform_code, o.site_code, o.observed_at desc"
                + "), norm as ("
                + " select l.*, coalesce(l.comparison_currency, l.currency) as cmp_currency"
                + " from latest l"
                + ")"
                + " select a.sku_id, a.platform_code, a.site_code, a.currency,"
                + "   b.platform_code as b_platform, b.site_code as b_site, b.currency as b_currency"
                + " from norm a join norm b"
                + "   on a.sku_id = b.sku_id"
                + "  and (a.platform_code, a.site_code) < (b.platform_code, b.site_code)"
                + " where a.cmp_currency <> b.cmp_currency"
                + " limit "
                + MAX_UNCOMPARABLE_PAIRS,
            start,
            end)) {
      uncomparable.add(
          new UncomparablePair(
              record.get("sku_id", UUID.class),
              null,
              record.get("platform_code", String.class),
              record.get("site_code", String.class),
              record.get("currency", String.class),
              record.get("b_platform", String.class),
              record.get("b_site", String.class),
              record.get("b_currency", String.class),
              "两个站点的比价币种不同（"
                  + record.get("currency", String.class)
                  + " vs "
                  + record.get("b_currency", String.class)
                  + "），而本服务**没有汇率表**（5.2.2.1 / 5.2.2.2 未交付）"
                  + "⇒ 不折算、不编汇率，也不判倒挂。登记观测时给 comparisonCurrency + fxRate"
                  + "（含用哪天的汇率、哪来的汇率）即可比价。"));
    }

    // 可比但本轮没命中的站点对 ⇒ 状态型异常自动消解。
    for (var record :
        sql.fetch(
            "with latest as ("
                + " select distinct on (o.sku_id, o.platform_code, o.site_code)"
                + "   o.sku_id, o.platform_code, o.site_code, o.currency,"
                + "   coalesce(o.platform_price, o.internal_price) as price,"
                + "   o.comparison_currency, o.fx_rate"
                + " from price_anomaly_observation o"
                + " where o.tenant_id = "
                + TENANT
                + "  and o.observed_at >= ?::timestamptz and o.observed_at < ?::timestamptz"
                + "  and (o.platform_price is not null or o.internal_price is not null)"
                + " order by o.sku_id, o.platform_code, o.site_code, o.observed_at desc"
                + "), norm as ("
                + " select l.*, coalesce(l.comparison_currency, l.currency) as cmp_currency"
                + " from latest l"
                + ")"
                + " select a.sku_id, a.platform_code, a.site_code, a.currency,"
                + "   b.platform_code as b_platform, b.site_code as b_site, b.currency as b_currency"
                + " from norm a join norm b"
                + "   on a.sku_id = b.sku_id and a.cmp_currency = b.cmp_currency"
                + "  and (a.platform_code, a.site_code) < (b.platform_code, b.site_code)",
            start,
            end)) {
      var fingerprint =
          PriceAnomalyRules.fingerprint(
              AnomalyType.CROSS_SITE_INVERSION,
              record.get("sku_id", UUID.class),
              record.get("platform_code", String.class),
              record.get("site_code", String.class),
              record.get("b_platform", String.class),
              record.get("b_site", String.class),
              record.get("currency", String.class),
              null,
              "");
      if (!detectedFingerprints.contains(fingerprint)) {
        resolved.add(fingerprint);
      }
    }
    return new DetectionOutcome(records.size(), detected, resolved, 0, uncomparable);
  }

  // ------------------------------------------------------------ 落库

  /** 幂等 upsert：同一条异常重复命中只更新「持续到什么时候」，不堆重复行。 */
  private UpsertResult upsert(Candidate candidate) {
    var id = Ids.next();
    var record =
        sql.fetchOne(
            "insert into price_anomaly_event(tenant_id,id,anomaly_type,sku_id,platform_code,site_code,"
                + "related_platform_code,related_site_code,currency,subject_kind,subject_price,"
                + "reference_price,gap_amount,gap_ratio,cost_line,cost_currency,cost_complete,"
                + "cost_snapshot_at,first_seen_at,last_seen_at,duration_days,severity,status,rule_id,"
                + "fingerprint,evidence,created_at,updated_at)"
                + " values(?::uuid,?::uuid,?::text,?::uuid,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,"
                + "?::timestamptz,?::timestamptz,?,?::text,'OPEN',?::uuid,?,?::jsonb,now(),now())"
                + " on conflict (tenant_id,fingerprint) do update set"
                + "   last_seen_at = excluded.last_seen_at,"
                + "   duration_days = excluded.duration_days,"
                + "   gap_amount = excluded.gap_amount, gap_ratio = excluded.gap_ratio,"
                + "   subject_price = excluded.subject_price, reference_price = excluded.reference_price,"
                + "   severity = excluded.severity, cost_line = excluded.cost_line,"
                + "   cost_currency = excluded.cost_currency, cost_complete = excluded.cost_complete,"
                + "   cost_snapshot_at = excluded.cost_snapshot_at, evidence = excluded.evidence,"
                + "   status = case when price_anomaly_event.status = 'RESOLVED' then 'OPEN'"
                + "                 else price_anomaly_event.status end,"
                + "   updated_at = now()"
                + " returning id, (xmax = 0) as inserted",
            candidate.tenantId(),
            id,
            candidate.type().name(),
            candidate.skuId(),
            candidate.platformCode(),
            candidate.siteCode(),
            candidate.relatedPlatformCode(),
            candidate.relatedSiteCode(),
            candidate.currency(),
            candidate.subjectKind() == null ? "" : candidate.subjectKind().name(),
            candidate.subjectPrice(),
            candidate.referencePrice(),
            candidate.gapAmount(),
            candidate.gapRatio(),
            candidate.costLine(),
            candidate.costCurrency(),
            candidate.costComplete(),
            candidate.costSnapshotAt(),
            candidate.firstSeenAt(),
            candidate.lastSeenAt(),
            candidate.durationDays(),
            candidate.severity().name(),
            candidate.ruleId(),
            candidate.fingerprint(),
            candidate.evidence());
    return new UpsertResult(
        Boolean.TRUE.equals(record.get("inserted", Boolean.class)),
        record.get("id", UUID.class));
  }

  /** 给新事件发一条预警通知（一个事件一条，重复检测不重复通知）。 */
  private UUID raiseNotification(Candidate candidate, UUID eventId) {
    var id = Ids.next();
    sql.execute(
        "insert into price_anomaly_notification(id,tenant_id,event_id,anomaly_type,severity,title,body,"
            + "channel,recipient_scope,owner_module,delivered,created_at)"
            + " values(?::uuid,?::uuid,?::uuid,?::text,?::text,?,?,'INTERNAL','PRICING_OPERATOR','1.3.1.1',false,now())"
            + " on conflict (tenant_id,event_id) do nothing",
        id,
        candidate.tenantId(),
        eventId,
        candidate.type().name(),
        candidate.severity().name(),
        titleOf(candidate),
        bodyOf(candidate));
    return id;
  }

  /** 不再命中的状态型异常 ⇒ 自动消解（③ 不走这条路：它发生过，不会消失）。 */
  private UUID resolveFingerprint(String fingerprint) {
    var record =
        sql.fetchOne(
            "update price_anomaly_event set status = 'RESOLVED', updated_at = now()"
                + " where tenant_id = "
                + TENANT
                + " and fingerprint = ? and status in ('OPEN','ACKNOWLEDGED')"
                + " returning id",
            fingerprint);
    return record == null ? null : record.get("id", UUID.class);
  }

  // ------------------------------------------------------------ 工具

  /** 规则设置快照：阈值 / 天数 / 严重度 / 启用。 */
  private Map<AnomalyType, RuleSettings> ruleSettings() {
    var settings = new LinkedHashMap<AnomalyType, RuleSettings>();
    for (var row : effectiveRules()) {
      var type = AnomalyType.parse(row.anomalyType());
      if (type == null) continue;
      settings.put(
          type,
          new RuleSettings(
              row.id(),
              row.enabled(),
              row.ratioThreshold() == null
                  ? PriceAnomalyRules.defaultRatio(type)
                  : row.ratioThreshold(),
              row.minDurationDays() == null
                  ? PriceAnomalyRules.defaultMinDurationDays(type)
                  : row.minDurationDays(),
              row.severity() == null
                  ? PriceAnomalyRules.defaultSeverity(type)
                  : Severity.parse(row.severity())));
    }
    return settings;
  }

  private long count(String sqlText, Object... binds) {
    return sql.fetchOne(sqlText, binds).get(0, Long.class);
  }

  private boolean skuExists(UUID tenantId, UUID skuId) {
    return count(
            "select count(*) from sku where tenant_id = ?::uuid and id = ?::uuid", tenantId, skuId)
        > 0;
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  /**
   * 从异常链里找 SQLState。
   *
   * <p>jOOQ 的 {@code DataAccessException} 不直接暴露 SQLState（那是 {@code SQLException} 的），
   * 所以沿 cause 链找一层。
   */
  private static String sqlState(Throwable error) {
    var current = error;
    for (int depth = 0; current != null && depth < 8; depth++) {
      if (current instanceof java.sql.SQLException sql) {
        return sql.getSQLState();
      }
      current = current.getCause();
    }
    return null;
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static String evidenceJson(Map<String, Object> values) {
    try {
      return JSON.writeValueAsString(values);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("异常证据序列化失败", e);
    }
  }

  private ObservationRow observationRow(Record record) {
    var changeSource = record.get("price_change_source", String.class);
    var source = record.get("source", String.class);
    var parsedChange = PriceChangeSource.parse(changeSource);
    var parsedSource = ObservationSource.parse(source);
    return new ObservationRow(
        record.get("id", UUID.class),
        record.get("sku_id", UUID.class),
        record.get("sku_code", String.class),
        record.get("sku_name", String.class),
        record.get("platform_code", String.class),
        record.get("site_code", String.class),
        record.get("currency", String.class),
        record.get("internal_price", BigDecimal.class),
        record.get("platform_price", BigDecimal.class),
        changeSource,
        parsedChange == null ? changeSource : parsedChange.label(),
        source,
        parsedSource == null ? source : parsedSource.label(),
        record.get("comparison_currency", String.class),
        record.get("fx_rate", BigDecimal.class),
        record.get("fx_rate_date", LocalDate.class),
        record.get("fx_rate_source", String.class),
        record.get("observed_at", OffsetDateTime.class),
        record.get("note", String.class));
  }

  private EventRow eventRow(Record record) {
    var type = AnomalyType.parse(record.get("anomaly_type", String.class));
    var severity = Severity.parse(record.get("severity", String.class));
    var status = EventStatus.parse(record.get("status", String.class));
    var evidence = record.get("evidence", String.class);
    return new EventRow(
        record.get("id", UUID.class),
        record.get("anomaly_type", String.class),
        type == null ? record.get("anomaly_type", String.class) : type.label(),
        record.get("sku_id", UUID.class),
        record.get("sku_code", String.class),
        record.get("sku_name", String.class),
        record.get("platform_code", String.class),
        record.get("site_code", String.class),
        record.get("related_platform_code", String.class),
        record.get("related_site_code", String.class),
        record.get("currency", String.class),
        record.get("subject_kind", String.class),
        record.get("subject_price", BigDecimal.class),
        record.get("reference_price", BigDecimal.class),
        record.get("gap_amount", BigDecimal.class),
        record.get("gap_ratio", BigDecimal.class),
        record.get("cost_line", BigDecimal.class),
        record.get("cost_currency", String.class),
        Boolean.TRUE.equals(record.get("cost_complete", Boolean.class)),
        record.get("cost_snapshot_at", OffsetDateTime.class),
        record.get("first_seen_at", OffsetDateTime.class),
        record.get("last_seen_at", OffsetDateTime.class),
        record.get("duration_days", Integer.class),
        record.get("severity", String.class),
        severity == null ? record.get("severity", String.class) : severity.label(),
        record.get("status", String.class),
        status == null ? record.get("status", String.class) : status.label(),
        record.get("rule_id", UUID.class),
        record.get("fingerprint", String.class),
        evidence == null ? "{}" : evidence,
        record.get("updated_at", OffsetDateTime.class));
  }

  private NotificationRow notificationRow(Record record) {
    var type = AnomalyType.parse(record.get("anomaly_type", String.class));
    var severity = Severity.parse(record.get("severity", String.class));
    return new NotificationRow(
        record.get("id", UUID.class),
        record.get("event_id", UUID.class),
        record.get("anomaly_type", String.class),
        type == null ? record.get("anomaly_type", String.class) : type.label(),
        record.get("severity", String.class),
        severity == null ? record.get("severity", String.class) : severity.label(),
        record.get("title", String.class),
        record.get("body", String.class),
        record.get("channel", String.class),
        record.get("recipient_scope", String.class),
        record.get("owner_module", String.class),
        Boolean.TRUE.equals(record.get("delivered", Boolean.class)),
        record.get("delivered_at", OffsetDateTime.class),
        record.get("created_at", OffsetDateTime.class));
  }

  private String titleOf(Candidate candidate) {
    return switch (candidate.type()) {
      case PLATFORM_INTERNAL_DIVERGENCE ->
          "平台价与内部价长期不一致：" + candidate.siteCode();
      case BELOW_COST -> "售价低于成本线：" + candidate.siteCode();
      case PLATFORM_AUTO_DISCOUNT -> "平台自动降价：" + candidate.siteCode();
      case CROSS_SITE_INVERSION ->
          "站点价格倒挂：" + candidate.siteCode() + " 低于 " + candidate.relatedSiteCode();
    };
  }

  private String bodyOf(Candidate candidate) {
    var body = new LinkedHashMap<String, Object>();
    body.put("anomalyType", candidate.type().name());
    body.put("platformCode", candidate.platformCode());
    body.put("siteCode", candidate.siteCode());
    if (!candidate.relatedSiteCode().isEmpty()) {
      body.put("relatedPlatformCode", candidate.relatedPlatformCode());
      body.put("relatedSiteCode", candidate.relatedSiteCode());
    }
    body.put("currency", candidate.currency());
    body.put("subjectPrice", candidate.subjectPrice());
    body.put("referencePrice", candidate.referencePrice());
    if (candidate.costLine() != null) {
      body.put("costLine", candidate.costLine());
      body.put("costCurrency", candidate.costCurrency());
      body.put("costSnapshotComplete", candidate.costComplete());
    }
    body.put("durationDays", candidate.durationDays());
    body.put("severity", candidate.severity().name());
    body.put("ownerModule", "1.3.1.1");
    body.put(
        "note",
        "下游 1 工作台预警（1.3.1.1）当前未交付 ⇒ 本条通知只落在本服务内，尚未投递到任何通道。");
    return evidenceJson(body);
  }

  private String basisOf(EventRow event) {
    var type = AnomalyType.parse(event.anomalyType());
    if (type == null) return "";
    return switch (type) {
      case PLATFORM_INTERNAL_DIVERGENCE ->
          "同一站点上平台价与内部价的偏离比例连续超过阈值，且持续天数达门槛（一次就对不上不报）。";
      case BELOW_COST ->
          "售价（折算到成本线币种后）低于 5.2.1.1 成本构成写下的当前版本合计"
              + (event.costComplete() ? "" : "（🔴 该成本快照不完整，是「已知部分合计」）")
              + "。";
      case PLATFORM_AUTO_DISCOUNT ->
          "同一站点的平台价相对上一次下降，且这次变动来源是「平台自动降价」（我们自己改的不算）。";
      case CROSS_SITE_INVERSION ->
          "同一 SKU 的两个站点之间价差超过阈值（站点两两比对，不是全平台平均价差）。";
    };
  }

  // ------------------------------------------------------------ 内部记录

  /** 一次检测中某一类异常的结果。 */
  private record DetectionOutcome(
      int evaluated,
      List<Candidate> detected,
      List<String> resolved,
      int selfInitiatedCount,
      List<UncomparablePair> uncomparable) {

    DetectionOutcome(int evaluated, List<Candidate> detected, List<String> resolved) {
      this(evaluated, detected, resolved, 0, List.of());
    }

    DetectionOutcome(
        int evaluated, List<Candidate> detected, List<String> resolved, int selfInitiatedCount) {
      this(evaluated, detected, resolved, selfInitiatedCount, List.of());
    }
  }

  /** 一条待落库的异常候选。 */
  private record Candidate(
      AnomalyType type,
      UUID tenantId,
      UUID skuId,
      String platformCode,
      String siteCode,
      String relatedPlatformCode,
      String relatedSiteCode,
      String currency,
      SubjectKind subjectKind,
      BigDecimal subjectPrice,
      BigDecimal referencePrice,
      BigDecimal gapAmount,
      BigDecimal gapRatio,
      BigDecimal costLine,
      String costCurrency,
      boolean costComplete,
      OffsetDateTime costSnapshotAt,
      OffsetDateTime firstSeenAt,
      OffsetDateTime lastSeenAt,
      int durationDays,
      Severity severity,
      String fingerprint,
      String evidence,
      UUID ruleId) {}

  /** 规则设置快照。 */
  private record RuleSettings(
      UUID id, boolean enabled, BigDecimal ratio, int minDurationDays, Severity severity) {}

  /** upsert 结果：是不是新插入的一行。 */
  private record UpsertResult(boolean inserted, UUID id) {}

  /** 一次检测落库后的计数。 */
  private record PersistOutcome(int opened, int updated, int notifications, int resolved) {}
}
