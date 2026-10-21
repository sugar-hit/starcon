package com.starcon.catalog;

import static com.starcon.catalog.LogisticsFreightModels.*;
import static com.starcon.catalog.LogisticsFreightRules.*;

import com.starcon.catalog.LogisticsFreightRules.AppliedHeadComponent;
import com.starcon.catalog.LogisticsFreightRules.AppliedSurcharge;
import com.starcon.catalog.LogisticsFreightRules.BaseFreight;
import com.starcon.catalog.LogisticsFreightRules.ChargeInput;
import com.starcon.catalog.LogisticsFreightRules.Chargeable;
import com.starcon.catalog.LogisticsFreightRules.HeadComponentView;
import com.starcon.catalog.LogisticsFreightRules.RuleView;
import com.starcon.catalog.LogisticsFreightRules.SurchargeView;
import com.starcon.catalog.LogisticsFreightRules.TierView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.2.3.2 物流与头程费用：渠道计费规则（事前测算口径）、附加费参数、头程费用结构，
 * 与实际运费（事后对账口径）—— 四个独立事实，差额现算不落库。
 *
 * <p><b>🔴 本服务最重要的三条纪律</b>：
 *
 * <ol>
 *   <li><b>规则与实际是两个独立的事实</b>（{@code logistics_freight_rule} /
 *       {@code logistics_freight_actual} 两张表）。测算用规则，差异用「实际 − 按规则测算」。
 *       两者永不合并成一个字段 —— 合并之后差异恒等于 0，
 *       「运费占成本比」就永远看不出是哪一块口径错了。
 *   <li><b>算不出来就是算不出来</b>：没有生效规则、规则落不进档、附加费币种不一致，
 *       一律表达成 {@code null} + 一句原因，**绝不**用 0 冒充。
 *   <li><b>已生效的规则与档位不可改写</b>：改规则 = 新增一条新版本 + 停用旧版本
 *       （DB 层触发器挡 UPDATE，本服务只提供「新版本」与「停用」两个动作）。
 * </ol>
 *
 * <p><b>跨服务只读 = 零</b>：渠道 / 承运商 / 运输方式都归 10 物流与头程，
 * 本服务只存**逻辑引用码 + 名称快照**，不建跨库 FK / 视图 / SQL
 * （AGENTS.md 禁止跨服务数据库访问）。上游没有实际运费结算事实，
 * 实际运费当前只有**人工登记**一种来源，能力声明里如实标注。
 */
@Service
public class LogisticsFreightService {

  /** RLS 租户上下文。与本服务其余服务类的写法一致。 */
  private static final String TENANT = "nullif(current_setting('app.current_tenant',true),'')::uuid";

  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;

  public LogisticsFreightService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 查询入参

  /** 渠道计费规则台账的筛选条件。 */
  public record RuleQuery(
      String keyword,
      String channelCode,
      String carrierCode,
      String shippingMethodCode,
      String chargeBasis,
      String status,
      int page,
      int size) {}

  /** 附加费台账的筛选条件。 */
  public record SurchargeQuery(
      String keyword,
      String channelCode,
      String carrierCode,
      String shippingMethodCode,
      String surchargeType,
      String status,
      int page,
      int size) {}

  /** 头程费用结构台账的筛选条件。 */
  public record HeadStructureQuery(
      String keyword,
      String shippingMethodCode,
      String originCode,
      String destinationCode,
      String status,
      int page,
      int size) {}

  /** 渠道运费测算入参。 */
  public record EstimateQuery(
      String channelCode,
      String carrierCode,
      String shippingMethodCode,
      String chargeBasis,
      BigDecimal weightKg,
      BigDecimal lengthCm,
      BigDecimal widthCm,
      BigDecimal heightCm,
      BigDecimal pieces,
      OffsetDateTime at) {}

  /** 头程费测算入参。 */
  public record HeadEstimateQuery(
      String shippingMethodCode,
      String originCode,
      String destinationCode,
      BigDecimal shipments,
      BigDecimal cbm,
      BigDecimal containers,
      BigDecimal weightKg,
      OffsetDateTime at) {}

  /** 实际运费台账的筛选条件。 */
  public record ActualQuery(
      String channelCode,
      String carrierCode,
      String shippingMethodCode,
      LocalDate from,
      LocalDate to,
      int page,
      int size) {}

  /** 运费差异对照入参。 */
  public record VarianceQuery(
      String channelCode, String carrierCode, String shippingMethodCode, LocalDate from, LocalDate to) {}

  // ================================================================ 内部结构

  /** 一条规则（连阶梯），供算式与匹配使用。 */
  private record RuleRecord(
      UUID id,
      String channelCode,
      String carrierCode,
      String shippingMethodCode,
      String chargeBasis,
      String currency,
      BigDecimal volumetricDivisor,
      BigDecimal minimumCharge,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveTo,
      int versionNo,
      boolean carrierSpecific,
      List<TierView> tiers) {

    RuleView toView() {
      return new RuleView(
          id, chargeBasis, currency, volumetricDivisor, minimumCharge, tiers);
    }
  }

  /** 一条附加费。 */
  private record SurchargeRecord(
      UUID id,
      String surchargeType,
      String calcBasis,
      BigDecimal rate,
      BigDecimal fixedAmount,
      String currency) {

    SurchargeView toView() {
      return new SurchargeView(id, surchargeType, calcBasis, rate, fixedAmount, currency);
    }
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：四张词表 + 六个维度（逐个写明取不到没有 / 归谁管）+ 七条口径说明。
   *
   * <p>🔴 页面固定显示它 —— 「上游没有实际运费结算事实」这一条必须写在脸上，
   * 否则运营会把人工登记的数当成物流商的结算结果。
   */
  public LogisticsFreightCapabilities capabilities() {
    var chargeBases = new ArrayList<FreightOption>();
    for (ChargeBasis basis : ChargeBasis.values()) {
      chargeBases.add(new FreightOption(basis.name(), basis.label(), basis.basis()));
    }
    var surchargeTypes = new ArrayList<FreightOption>();
    for (SurchargeType type : SurchargeType.values()) {
      surchargeTypes.add(new FreightOption(type.name(), type.label(), type.basis()));
    }
    var surchargeBases = new ArrayList<FreightOption>();
    for (SurchargeBasis basis : SurchargeBasis.values()) {
      surchargeBases.add(new FreightOption(basis.name(), basis.label(), basis.basis()));
    }
    var priceModes = new ArrayList<FreightOption>();
    for (PriceMode mode : PriceMode.values()) {
      priceModes.add(new FreightOption(mode.name(), mode.label(), mode.basis()));
    }
    var headComponentTypes = new ArrayList<FreightOption>();
    for (HeadComponentType type : HeadComponentType.values()) {
      headComponentTypes.add(new FreightOption(type.name(), type.label(), type.basis()));
    }
    var headCalcBases = new ArrayList<FreightOption>();
    for (HeadCalcBasis basis : HeadCalcBasis.values()) {
      headCalcBases.add(new FreightOption(basis.name(), basis.label(), basis.basis()));
    }

    var dimensions = new ArrayList<LogisticsFreightCapabilityDimension>();
    dimensions.add(
        new LogisticsFreightCapabilityDimension(
            "channel_rate_rules",
            "物流渠道计费规则（按重量 / 体积 / 件数）",
            true,
            "5.2.3.2（本模块）",
            "没有规则就测算不出运费，运费占成本比只能靠猜 —— 而运费是跨境电商成本里最容易失控的一块。",
            "规则挂在「渠道 / 承运商 / 运输方式 / 计费口径」四个维度上，"
                + "档位用「首段加续段」或「整档一口价」表达。",
            "渠道标识只存逻辑引用码 + 名称快照（10 物流与头程是卷级引用，"
                + "AGENTS.md 禁止跨服务数据库访问 / 跨库 FK / 共享视图）。"));
    dimensions.add(
        new LogisticsFreightCapabilityDimension(
            "surcharge_parameters",
            "附加费参数（燃油 / 旺季 / 偏远）",
            true,
            "5.2.3.2（本模块）",
            "缺了附加费，旺季与油价上浮那几笔会在测算里凭空消失 —— 页面上看不出任何异常。",
            "同一时刻同一类型只能命中一条附加费（DB 层守卫触发器保证）；"
                + "附加费一律加在**基础运费**上，不含其它附加费。",
            "燃油按基础运费的比例，旺季按每单固定额，偏远按每件 / 每单固定额。"));
    dimensions.add(
        new LogisticsFreightCapabilityDimension(
            "head_freight_structures",
            "头程费用结构",
            true,
            "5.2.3.2（本模块）",
            "缺了头程费用结构，到岸成本里的头程那一段就是空的 —— 运费占成本比会被系统性低估。",
            "结构头（运输方式 / 起运地 / 目的地）+ 成本项（主运费 / 报关 / 拖车 / 保险…），"
                + "每个成本项有自己的计价基数与币种。",
            "头程与渠道派送是**两笔不同的钱**，独立成表，不塞进渠道计费规则。"));
    dimensions.add(
        new LogisticsFreightCapabilityDimension(
            "actual_freight_reconciliation",
            "实际运费对账（事后对账口径）",
            false,
            "10 物流与头程",
            "🔴 缺了它，测算与实际的差额无法解释：现在只能靠人工登记物流商账单观察值来校准，"
                + "拿不到 10 的结算事实，就没法回答「这个月多付的那笔运费到底多在哪」。",
            "上游的真实结算事实才是实际运费的唯一权威来源。",
            "本仓上游当前**没有**可读的实际运费结算事实（channel 的 freight_quote.cost_basis "
                + "恒为 RULE_ESTIMATE —— 那是按规则估的报价，不是结算单）；"
                + "本模块不编一个假的数据源去冒充它，实际运费当前只有人工登记一种来源，页面上如实标注。"));
    dimensions.add(
        new LogisticsFreightCapabilityDimension(
            "freight_variance",
            "运费差异（规则测算 vs 实际）",
            true,
            "5.2.3.2（本模块）",
            "没有差异对照，测算就永远不准却不知道为什么。",
            "差异 = 实际运费 − 按规则测算；两个口径是**两个独立的事实**，差额由服务层现算，不落库"
                + "（落库就会多出第三份可能与前两份不一致的副本）。",
            "算不出来时（没有实际运费 / 没有生效规则 / 规则不适用 / 币种不一致）"
                + "返回「算不出来」+ 原因，**绝不**返回 0。"));
    dimensions.add(
        new LogisticsFreightCapabilityDimension(
            "profit_consumer",
            "利润测算（5.3.1.1）",
            false,
            "5.3.1.1 利润测算器",
            "5.3.1.1 未交付时，运费占成本比只能在本模块页面里看；"
                + "利润报表里的运费占比要等 5.3.1.1 来读。",
            "下游 = 5.3.1.1 测算（本模块产出的测算输入）。",
            "本模块已把测算做成可展开的逐步拆解结构，5.3.1.1 落地后直接消费。"));

    return new LogisticsFreightCapabilities(
        List.copyOf(chargeBases),
        List.copyOf(surchargeTypes),
        List.copyOf(surchargeBases),
        List.copyOf(priceModes),
        List.copyOf(headComponentTypes),
        List.copyOf(headCalcBases),
        List.copyOf(dimensions),
        "渠道计费规则是**事前测算口径**：回答「这票按规则应该付多少运费」。"
            + "它挂在「渠道 / 承运商 / 运输方式 / 计费口径」四个维度上；"
            + "承运商写空 = 该渠道的通用费率，写了 = 只对这家承运商生效（更具体，优先命中）。"
            + "计费口径（按重量 / 体积 / 件数）互斥：同时有两个口径生效时必须显式指定，否则**算不出来**。",
        "附加费是**独立的事实**，带自己的生效区间与版本链。"
            + "同一时刻同一类型只能命中一条；三种类型（燃油 / 旺季 / 偏远）可以同时命中。",
        "头程费用结构独立建模：结构头（运输方式 / 起运地 / 目的地）+ 成本项。"
            + "成本项可以有不同的币种，而**跨币种绝不相加** ⇒ 混币时头程费合计是「算不出来」+ 原因。",
        "实际运费是**事后对账口径**：回答「物流商实际收了多少」。"
            + "它与规则是**两个独立的事实**，各自独立落库、独立追溯；"
            + "当前唯一来源是人工登记的物流商账单观察值（不是 10 的结算结果 —— 上游没有那个事实）。",
        "运费差异 = 实际 − 按规则测算。两条纪律："
            + "① 两个口径永不合并成一个字段；② 算不出来时显示「算不出来」+ 原因，"
            + "**绝不显示 0.00** —— 显示 0 是在说「物流商完全按规则收」，而真实情况是「算不出差异」。",
        "上游 = 10 物流与头程（**卷级引用**：本服务只存逻辑引用码 + 名称快照，"
            + "不建跨库 FK / 视图 / SQL，也不留渠道目录副本）。",
        "下游 = 5.3.1.1 测算（消费本模块的测算输入，算运费占成本比）。",
        "本模块的动作：维护渠道计费规则（新增 / 新增版本 / 停用）、维护阶梯档位、"
            + "维护附加费、维护头程费用结构、测算渠道运费、测算头程费、"
            + "登记实际运费、作废实际运费、查看运费差异。"
            + "🔴 已生效的规则与档位**不可改写**：改规则 = 新增一条新版本 + 停用旧版本。");
  }

  // ================================================================ 渠道计费规则

  /** 规则台账：分页 + 关键词 / 渠道 / 承运商 / 运输方式 / 计费口径 / 状态筛选。 */
  public FreightRuleListPage listRules(RuleQuery query) {
    int size = query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
    int page = Math.max(query.page(), 0);
    var where = new StringBuilder(" where r.tenant_id = " + TENANT);
    var binds = new ArrayList<Object>();
    if (notBlank(query.keyword())) {
      where.append(" and (r.channel_code ilike ? or r.channel_name ilike ? or r.carrier_code ilike ?"
          + " or r.shipping_method_code ilike ? or r.shipping_method_name ilike ? or r.note ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      for (int i = 0; i < 6; i++) binds.add(like);
    }
    if (notBlank(query.channelCode())) {
      where.append(" and r.channel_code = ?");
      binds.add(query.channelCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.carrierCode())) {
      where.append(" and r.carrier_code = ?");
      binds.add(query.carrierCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.shippingMethodCode())) {
      where.append(" and r.shipping_method_code = ?");
      binds.add(query.shippingMethodCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.chargeBasis())) {
      where.append(" and r.charge_basis = ?");
      binds.add(query.chargeBasis().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.status())) {
      where.append(" and r.status = ?");
      binds.add(query.status().trim().toUpperCase(Locale.ROOT));
    }
    long total = count("select count(*) from logistics_freight_rule r" + where, binds.toArray());
    var records =
        sql.fetch(
            "select r.id, r.channel_code, r.channel_name, r.carrier_code, r.carrier_name,"
                + " r.shipping_method_code, r.shipping_method_name, r.charge_basis, r.currency,"
                + " r.volumetric_divisor, r.minimum_charge, r.effective_from, r.effective_to,"
                + " r.version_no, r.supersedes_id, r.superseded_by_id, r.status, r.disable_reason,"
                + " r.note, r.created_at,"
                + " (select count(*) from logistics_freight_rule_tier t"
                + "   where t.tenant_id = r.tenant_id and t.rule_id = r.id) as tier_count"
                + " from logistics_freight_rule r"
                + where
                + " order by r.created_at desc, r.channel_code, r.shipping_method_code, r.id"
                + " limit "
                + size
                + " offset "
                + ((long) page * size),
            binds.toArray());
    var items = new ArrayList<FreightRuleRow>();
    for (var record : records) {
      items.add(ruleRow(record));
    }
    return new FreightRuleListPage(
        List.copyOf(items),
        total,
        page,
        size,
        "规则是事前测算口径：一条规则 = 一个（渠道 / 承运商 / 运输方式 / 计费口径）的费率口径，"
            + "按生效区间取用。🔴 已生效的规则**不可改写** —— 改规则 = 新增一条新版本 + 停用旧版本。");
  }

  /** 规则明细：规则 + 阶梯 + 作用域口径。 */
  public FreightRuleDetail ruleDetail(UUID ruleId) {
    var records =
        sql.fetch(
            "select r.id, r.channel_code, r.channel_name, r.carrier_code, r.carrier_name,"
                + " r.shipping_method_code, r.shipping_method_name, r.charge_basis, r.currency,"
                + " r.volumetric_divisor, r.minimum_charge, r.effective_from, r.effective_to,"
                + " r.version_no, r.supersedes_id, r.superseded_by_id, r.status, r.disable_reason,"
                + " r.note, r.created_at,"
                + " (select count(*) from logistics_freight_rule_tier t"
                + "   where t.tenant_id = r.tenant_id and t.rule_id = r.id) as tier_count"
                + " from logistics_freight_rule r"
                + " where r.tenant_id = "
                + TENANT
                + " and r.id = ?",
            ruleId);
    if (records.isEmpty()) throw notFound("物流计费规则不存在");
    var row = ruleRow(records.get(0));
    return new FreightRuleDetail(
        row,
        tiersOf(ruleId),
        scopeBasis(
            row.channelCode(),
            row.channelName(),
            row.carrierCode(),
            row.shippingMethodCode(),
            row.shippingMethodName(),
            row.chargeBasis()),
        "档位的选档口径：下界含、上界不含（最后一档无上界）；"
            + "计费量低于最低档下界时该规则**不适用**，而不是按 0 运费计。");
  }

  /** 新建一条渠道计费规则（规则与阶梯同事务写入）。 */
  public FreightRuleSaved createRule(AccessService.Access actor, FreightRuleInput input) {
    var problems = validateRule(input);
    if (!problems.isEmpty()) {
      throw badRequest("渠道计费规则没通过校验：" + String.join("；", problems));
    }
    var id = UUID.randomUUID();
    insertRule(actor, id, input, null, 1);
    insertTiers(id, input.tiers());
    return new FreightRuleSaved(id.toString(), 1, "ACTIVE", input.effectiveFrom().toString());
  }

  /**
   * 新增一个版本并取代旧版本（🔴 不可变优先：已生效的规则不许改写）。
   *
   * <p>同事务内两步：先把旧版本置为「已被取代」（关掉它的生效区间），
   * 再插入新版本。顺序反了的话，DB 层的「同一作用域不得有生效区间重叠」守卫会挡下新版本。
   */
  public FreightRuleSaved supersedeRule(
      AccessService.Access actor, UUID ruleId, FreightRuleInput input) {
    var problems = validateRule(input);
    if (!problems.isEmpty()) {
      throw badRequest("渠道计费规则没通过校验：" + String.join("；", problems));
    }
    var current =
        sql.fetchOne(
            "select version_no, status, effective_from from logistics_freight_rule"
                + " where tenant_id = "
                + TENANT
                + " and id = ? for update",
            ruleId);
    if (current == null) throw notFound("物流计费规则不存在");
    String status = current.get("status", String.class);
    if (!"ACTIVE".equals(status)) {
      throw conflict(
          "只有生效中的规则版本可以被取代（当前状态："
              + statusLabel(status)
              + "）。已生效的规则**不可改写** —— 请新建一条规则或先停用旧版本。");
    }
    int versionNo = current.get("version_no", Integer.class) + 1;
    OffsetDateTime oldFrom = current.get("effective_from", OffsetDateTime.class);
    if (!input.effectiveFrom().isAfter(oldFrom)) {
      throw badRequest(
          "新版本的生效起点必须晚于旧版本的生效起点（旧版本自 "
              + oldFrom
              + " 起生效）");
    }
    var newId = UUID.randomUUID();
    sql.execute(
        "update logistics_freight_rule set status = 'SUPERSEDED', superseded_by_id = ?,"
            + " effective_to = ?::timestamptz"
            + " where tenant_id = "
            + TENANT
            + " and id = ?",
        newId,
        input.effectiveFrom(),
        ruleId);
    insertRule(actor, newId, input, ruleId, versionNo);
    insertTiers(newId, input.tiers());
    return new FreightRuleSaved(
        newId.toString(), versionNo, "ACTIVE", input.effectiveFrom().toString());
  }

  /** 停用一条规则版本（必须写理由；停用不是删除，旧版本仍在库里可追溯）。 */
  public FreightRuleSaved disableRule(
      AccessService.Access actor, UUID ruleId, String reason) {
    if (reason == null || reason.isBlank()) {
      throw badRequest("停用必须写理由：没有理由的停用会让「为什么这条渠道费率失效了」无法解释");
    }
    var current =
        sql.fetchOne(
            "select version_no, status, effective_from from logistics_freight_rule"
                + " where tenant_id = "
                + TENANT
                + " and id = ? for update",
            ruleId);
    if (current == null) throw notFound("物流计费规则不存在");
    String status = current.get("status", String.class);
    if (!"ACTIVE".equals(status)) {
      throw conflict("只有生效中的规则版本可以被停用（当前状态：" + statusLabel(status) + "）");
    }
    OffsetDateTime from = current.get("effective_from", OffsetDateTime.class);
    OffsetDateTime now = OffsetDateTime.now();
    // 生效终点必须晚于生效起点（DB CHECK）：规则还没生效就被停用时，生效终点留空。
    // 关掉生效区间：停用之后不再命中；还没生效就停用 ⇒ 空区间（effective_to == effective_from）。
    OffsetDateTime effectiveTo = now.isAfter(from) ? now : from;
    sql.execute(
        "update logistics_freight_rule set status = 'DISABLED', disable_reason = ?,"
            + " effective_to = ?::timestamptz"
            + " where tenant_id = "
            + TENANT
            + " and id = ?",
        reason.trim(),
        effectiveTo,
        ruleId);
    return new FreightRuleSaved(
        ruleId.toString(),
        current.get("version_no", Integer.class),
        "DISABLED",
        from.toString());
  }

  // ================================================================ 附加费

  /** 附加费台账：分页 + 关键词 / 渠道 / 承运商 / 运输方式 / 类型 / 状态筛选。 */
  public FreightSurchargeListPage listSurcharges(SurchargeQuery query) {
    int size = query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
    int page = Math.max(query.page(), 0);
    var where = new StringBuilder(" where s.tenant_id = " + TENANT);
    var binds = new ArrayList<Object>();
    if (notBlank(query.keyword())) {
      where.append(" and (s.channel_code ilike ? or s.channel_name ilike ? or s.carrier_code ilike ?"
          + " or s.shipping_method_code ilike ? or s.shipping_method_name ilike ? or s.note ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      for (int i = 0; i < 6; i++) binds.add(like);
    }
    if (notBlank(query.channelCode())) {
      where.append(" and s.channel_code = ?");
      binds.add(query.channelCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.carrierCode())) {
      where.append(" and s.carrier_code = ?");
      binds.add(query.carrierCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.shippingMethodCode())) {
      where.append(" and s.shipping_method_code = ?");
      binds.add(query.shippingMethodCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.surchargeType())) {
      where.append(" and s.surcharge_type = ?");
      binds.add(query.surchargeType().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.status())) {
      where.append(" and s.status = ?");
      binds.add(query.status().trim().toUpperCase(Locale.ROOT));
    }
    long total = count("select count(*) from logistics_freight_surcharge s" + where, binds.toArray());
    var records =
        sql.fetch(
            "select s.id, s.channel_code, s.channel_name, s.carrier_code, s.carrier_name,"
                + " s.shipping_method_code, s.shipping_method_name, s.surcharge_type, s.calc_basis,"
                + " s.rate, s.fixed_amount, s.currency, s.effective_from, s.effective_to, s.version_no,"
                + " s.supersedes_id, s.superseded_by_id, s.status, s.disable_reason, s.note, s.created_at"
                + " from logistics_freight_surcharge s"
                + where
                + " order by s.created_at desc, s.channel_code, s.surcharge_type, s.id"
                + " limit "
                + size
                + " offset "
                + ((long) page * size),
            binds.toArray());
    var items = new ArrayList<FreightSurchargeRow>();
    for (var record : records) {
      items.add(surchargeRow(record));
    }
    return new FreightSurchargeListPage(
        List.copyOf(items),
        total,
        page,
        size,
        "附加费是独立的事实：同一时刻同一类型只能命中一条（DB 层守卫触发器保证），"
            + "三种类型（燃油 / 旺季 / 偏远）可以同时命中。"
            + "附加费一律加在**基础运费**上，不含其它附加费。");
  }

  /** 登记一条附加费。 */
  public FreightSurchargeSaved createSurcharge(
      AccessService.Access actor, FreightSurchargeInput input) {
    var problems = validateSurcharge(input);
    if (!problems.isEmpty()) {
      throw badRequest("附加费没通过校验：" + String.join("；", problems));
    }
    var id = UUID.randomUUID();
    sql.execute(
        "insert into logistics_freight_surcharge (tenant_id, id, channel_code, channel_name,"
            + " carrier_code, carrier_name, shipping_method_code, shipping_method_name,"
            + " surcharge_type, calc_basis, rate, fixed_amount, currency, effective_from, effective_to,"
            + " version_no, supersedes_id, superseded_by_id, status, disable_reason, note, created_by, created_at)"
            + " values ("
            + TENANT
            + ", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz,"
            + " 1, null, null, 'ACTIVE', '', ?, ?, now())",
        id,
        input.channelCode().trim().toUpperCase(Locale.ROOT),
        blankToEmpty(input.channelName()),
        blankToNull(
            input.carrierCode() == null ? null : input.carrierCode().trim().toUpperCase(Locale.ROOT)),
        blankToEmpty(input.carrierName()),
        input.shippingMethodCode().trim().toUpperCase(Locale.ROOT),
        blankToEmpty(input.shippingMethodName()),
        input.surchargeType().trim().toUpperCase(Locale.ROOT),
        input.calcBasis().trim().toUpperCase(Locale.ROOT),
        input.rate(),
        input.fixedAmount(),
        input.currency().trim().toUpperCase(Locale.ROOT),
        input.effectiveFrom(),
        input.effectiveTo(),
        input.note() == null ? "" : input.note().trim(),
        actor.userId());
    return new FreightSurchargeSaved(
        id.toString(),
        input.surchargeType().trim().toUpperCase(Locale.ROOT),
        1,
        "ACTIVE",
        input.effectiveFrom().toString());
  }

  /** 停用一条附加费（必须写理由）。 */
  public FreightSurchargeSaved disableSurcharge(
      AccessService.Access actor, UUID surchargeId, String reason) {
    if (reason == null || reason.isBlank()) {
      throw badRequest("停用必须写理由：没有理由的停用会让「为什么这条附加费失效了」无法解释");
    }
    var current =
        sql.fetchOne(
            "select version_no, status, effective_from from logistics_freight_surcharge"
                + " where tenant_id = "
                + TENANT
                + " and id = ? for update",
            surchargeId);
    if (current == null) throw notFound("附加费不存在");
    String status = current.get("status", String.class);
    if (!"ACTIVE".equals(status)) {
      throw conflict("只有生效中的附加费可以被停用（当前状态：" + statusLabel(status) + "）");
    }
    OffsetDateTime from = current.get("effective_from", OffsetDateTime.class);
    OffsetDateTime now = OffsetDateTime.now();
    // 关掉生效区间：停用之后不再命中；还没生效就停用 ⇒ 空区间（effective_to == effective_from）。
    OffsetDateTime effectiveTo = now.isAfter(from) ? now : from;
    sql.execute(
        "update logistics_freight_surcharge set status = 'DISABLED', disable_reason = ?,"
            + " effective_to = ?::timestamptz"
            + " where tenant_id = "
            + TENANT
            + " and id = ?",
        reason.trim(),
        effectiveTo,
        surchargeId);
    return new FreightSurchargeSaved(
        surchargeId.toString(),
        current.get("surcharge_type", String.class),
        current.get("version_no", Integer.class),
        "DISABLED",
        from.toString());
  }

  // ================================================================ 头程费用结构

  /** 头程费用结构台账：分页 + 关键词 / 运输方式 / 起运地 / 目的地 / 状态筛选。 */
  public HeadFreightStructureListPage listHeadStructures(HeadStructureQuery query) {
    int size = query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
    int page = Math.max(query.page(), 0);
    var where = new StringBuilder(" where h.tenant_id = " + TENANT);
    var binds = new ArrayList<Object>();
    if (notBlank(query.keyword())) {
      where.append(" and (h.shipping_method_code ilike ? or h.shipping_method_name ilike ?"
          + " or h.origin_code ilike ? or h.origin_name ilike ? or h.destination_code ilike ?"
          + " or h.destination_name ilike ? or h.note ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      for (int i = 0; i < 7; i++) binds.add(like);
    }
    if (notBlank(query.shippingMethodCode())) {
      where.append(" and h.shipping_method_code = ?");
      binds.add(query.shippingMethodCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.originCode())) {
      where.append(" and h.origin_code = ?");
      binds.add(query.originCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.destinationCode())) {
      where.append(" and h.destination_code = ?");
      binds.add(query.destinationCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.status())) {
      where.append(" and h.status = ?");
      binds.add(query.status().trim().toUpperCase(Locale.ROOT));
    }
    long total =
        count("select count(*) from logistics_head_freight_structure h" + where, binds.toArray());
    var records =
        sql.fetch(
            "select h.id, h.shipping_method_code, h.shipping_method_name, h.origin_code, h.origin_name,"
                + " h.destination_code, h.destination_name, h.currency, h.effective_from, h.effective_to,"
                + " h.version_no, h.supersedes_id, h.superseded_by_id, h.status, h.disable_reason,"
                + " h.note, h.created_at,"
                + " (select count(*) from logistics_head_freight_component c"
                + "   where c.tenant_id = h.tenant_id and c.structure_id = h.id) as component_count"
                + " from logistics_head_freight_structure h"
                + where
                + " order by h.created_at desc, h.shipping_method_code, h.destination_code, h.id"
                + " limit "
                + size
                + " offset "
                + ((long) page * size),
            binds.toArray());
    var items = new ArrayList<HeadFreightStructureRow>();
    for (var record : records) {
      items.add(headStructureRow(record));
    }
    return new HeadFreightStructureListPage(
        List.copyOf(items),
        total,
        page,
        size,
        "头程费用结构独立建模：结构头（运输方式 / 起运地 / 目的地）+ 成本项"
            + "（主运费 / 报关 / 拖车 / 保险…）。成本项可以有不同的币种，"
            + "而跨币种绝不相加 ⇒ 混币时头程费合计是「算不出来」。");
  }

  /** 头程费用结构明细（结构 + 成本项）。 */
  public HeadFreightStructureDetail headStructureDetail(UUID structureId) {
    var records =
        sql.fetch(
            "select h.id, h.shipping_method_code, h.shipping_method_name, h.origin_code, h.origin_name,"
                + " h.destination_code, h.destination_name, h.currency, h.effective_from, h.effective_to,"
                + " h.version_no, h.supersedes_id, h.superseded_by_id, h.status, h.disable_reason,"
                + " h.note, h.created_at,"
                + " (select count(*) from logistics_head_freight_component c"
                + "   where c.tenant_id = h.tenant_id and c.structure_id = h.id) as component_count"
                + " from logistics_head_freight_structure h"
                + " where h.tenant_id = "
                + TENANT
                + " and h.id = ?",
            structureId);
    if (records.isEmpty()) throw notFound("头程费用结构不存在");
    return new HeadFreightStructureDetail(
        headStructureRow(records.get(0)),
        headComponentsOf(structureId),
        "成本项的计价基数：每票 / 每立方米 / 每柜 / 每公斤。缺了对应的数量时，"
            + "该项**算不出来**（不是 0），头程费合计也就不完整。");
  }

  /** 新建一条头程费用结构（结构与成本项同事务写入）。 */
  public HeadFreightStructureSaved createHeadStructure(
      AccessService.Access actor, HeadFreightStructureInput input) {
    var problems = validateHeadStructure(input);
    if (!problems.isEmpty()) {
      throw badRequest("头程费用结构没通过校验：" + String.join("；", problems));
    }
    var id = UUID.randomUUID();
    sql.execute(
        "insert into logistics_head_freight_structure (tenant_id, id, shipping_method_code,"
            + " shipping_method_name, origin_code, origin_name, destination_code, destination_name,"
            + " currency, effective_from, effective_to, version_no, supersedes_id, superseded_by_id,"
            + " status, disable_reason, note, created_by, created_at)"
            + " values ("
            + TENANT
            + ", ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, 1, null, null,"
            + " 'ACTIVE', '', ?, ?, now())",
        id,
        input.shippingMethodCode().trim().toUpperCase(Locale.ROOT),
        blankToEmpty(input.shippingMethodName()),
        input.originCode().trim().toUpperCase(Locale.ROOT),
        blankToEmpty(input.originName()),
        input.destinationCode().trim().toUpperCase(Locale.ROOT),
        blankToEmpty(input.destinationName()),
        input.currency().trim().toUpperCase(Locale.ROOT),
        input.effectiveFrom(),
        input.effectiveTo(),
        input.note() == null ? "" : input.note().trim(),
        actor.userId());
    for (var component : input.components()) {
      sql.execute(
          "insert into logistics_head_freight_component (tenant_id, id, structure_id, seq,"
              + " component_type, label, calc_basis, unit_amount, currency, created_at)"
              + " values ("
              + TENANT
              + ", ?, ?, ?, ?, ?, ?, ?, ?, now())",
          UUID.randomUUID(),
          id,
          component.seq(),
          component.componentType().trim().toUpperCase(Locale.ROOT),
          blankToEmpty(component.label()),
          component.calcBasis().trim().toUpperCase(Locale.ROOT),
          component.unitAmount(),
          component.currency().trim().toUpperCase(Locale.ROOT));
    }
    return new HeadFreightStructureSaved(
        id.toString(), 1, "ACTIVE", input.effectiveFrom().toString());
  }

  /** 停用一条头程费用结构（必须写理由）。 */
  public HeadFreightStructureSaved disableHeadStructure(
      AccessService.Access actor, UUID structureId, String reason) {
    if (reason == null || reason.isBlank()) {
      throw badRequest("停用必须写理由：没有理由的停用会让「为什么这条航线头程费失效了」无法解释");
    }
    var current =
        sql.fetchOne(
            "select version_no, status, effective_from from logistics_head_freight_structure"
                + " where tenant_id = "
                + TENANT
                + " and id = ? for update",
            structureId);
    if (current == null) throw notFound("头程费用结构不存在");
    String status = current.get("status", String.class);
    if (!"ACTIVE".equals(status)) {
      throw conflict("只有生效中的头程费用结构可以被停用（当前状态：" + statusLabel(status) + "）");
    }
    OffsetDateTime from = current.get("effective_from", OffsetDateTime.class);
    OffsetDateTime now = OffsetDateTime.now();
    // 关掉生效区间：停用之后不再命中；还没生效就停用 ⇒ 空区间（effective_to == effective_from）。
    OffsetDateTime effectiveTo = now.isAfter(from) ? now : from;
    sql.execute(
        "update logistics_head_freight_structure set status = 'DISABLED', disable_reason = ?,"
            + " effective_to = ?::timestamptz"
            + " where tenant_id = "
            + TENANT
            + " and id = ?",
        reason.trim(),
        effectiveTo,
        structureId);
    return new HeadFreightStructureSaved(
        structureId.toString(),
        current.get("version_no", Integer.class),
        "DISABLED",
        from.toString());
  }

  // ================================================================ 测算（渠道运费）

  /**
   * 测算一次发运的渠道运费（逐步拆解）。
   *
   * <p>🔴 **算不出来就是算不出来**：没有生效规则、规则落不进档、附加费币种不一致，
   * 一律 {@code applicable=false} + {@code amount=null}，**绝不**当 0。
   */
  public FreightEstimate estimate(EstimateQuery query) {
    if (!notBlank(query.channelCode())) {
      throw badRequest("渠道必填：渠道计费规则挂在渠道维度上");
    }
    if (!notBlank(query.shippingMethodCode())) {
      throw badRequest("运输方式必填：同一渠道的不同运输方式费率不同");
    }
    String channelCode = query.channelCode().trim().toUpperCase(Locale.ROOT);
    String carrierCode =
        blankToNull(
            query.carrierCode() == null ? null : query.carrierCode().trim().toUpperCase(Locale.ROOT));
    String shippingMethodCode = query.shippingMethodCode().trim().toUpperCase(Locale.ROOT);
    String chargeBasis =
        blankToNull(
            query.chargeBasis() == null ? null : query.chargeBasis().trim().toUpperCase(Locale.ROOT));
    OffsetDateTime at = query.at() == null ? OffsetDateTime.now() : query.at();

    var steps = new ArrayList<FreightEstimateStep>();
    var candidates = rulesOfScope(channelCode, carrierCode, shippingMethodCode, at);

    // 选定计费口径：显式指定优先；没指定时按「最具体的命中」推断，推断不出来就说算不出来。
    var resolved = resolveChargeBasis(candidates, chargeBasis);
    if (resolved.ambiguous()) {
      steps.add(
          new FreightEstimateStep(
              "rule",
              "命中规则",
              "同一（渠道 / 承运商 / 运输方式）下同时有 "
                  + String.join("、", resolved.bases())
                  + " 两种计费口径的生效规则 —— 「这一票按什么计」没有答案。"
                  + "请显式指定计费口径。",
              null,
              null,
              false,
              "算不出来：计费口径不唯一"));
      return new FreightEstimate(
          channelCode,
          carrierCode,
          shippingMethodCode,
          resolved.bases().isEmpty() ? null : String.join("/", resolved.bases()),
          null,
          at.toString(),
          null,
          null,
          null,
          List.copyOf(steps),
          null,
          null,
          null,
          false,
          "算不出来：同一渠道有多个计费口径的生效规则，必须显式指定后才能测算。");
    }
    if (resolved.basis() == null) {
      steps.add(
          new FreightEstimateStep(
              "rule",
              "命中规则",
              "该（渠道 / 承运商 / 运输方式）下没有生效中的计费规则 —— "
                  + "**没有规则 ≠ 运费是 0**：规则没配就等于漏算。",
              null,
              null,
              false,
              "没有生效规则"));
      return new FreightEstimate(
          channelCode,
          carrierCode,
          shippingMethodCode,
          chargeBasis,
          null,
          at.toString(),
          null,
          null,
          null,
          List.copyOf(steps),
          null,
          null,
          null,
          false,
          "算不出来：这个渠道 / 运输方式没有生效中的计费规则。");
    }

    var rule = resolved.rule();
    String basis = resolved.basis();
    var chargeable =
        chargeable(
            basis,
            new ChargeInput(
                query.weightKg(),
                query.lengthCm(),
                query.widthCm(),
                query.heightCm(),
                query.pieces(),
                rule == null ? null : rule.volumetricDivisor()));
    steps.add(
        new FreightEstimateStep(
            "chargeable",
            "计费量",
            chargeable.basis().isEmpty() ? chargeable.reason() : chargeable.basis(),
            null,
            null,
            chargeable.computable(),
            chargeable.computable() ? "" : chargeable.reason()));
    if (!chargeable.computable()) {
      return new FreightEstimate(
          channelCode,
          carrierCode,
          shippingMethodCode,
          basis,
          rule == null ? null : rule.currency(),
          at.toString(),
          null,
          chargeable.unit(),
          null,
          List.copyOf(steps),
          null,
          null,
          null,
          false,
          "算不出来：" + chargeable.reason());
    }

    steps.add(
        new FreightEstimateStep(
            "rule",
            "命中规则",
            "命中 "
                + scopeBasis(
                    rule.channelCode(),
                    null,
                    rule.carrierCode(),
                    rule.shippingMethodCode(),
                    null,
                    rule.chargeBasis())
                + " 的第 "
                + rule.versionNo()
                + " 版（生效自 "
                + rule.effectiveFrom()
                + "）",
            null,
            rule.currency(),
            true,
            ""));

    BaseFreight base =
        baseFreight(rule.toView(), chargeable.quantity());
    steps.add(
        new FreightEstimateStep(
            "base",
            "基础运费",
            base.basis().isEmpty() ? base.note() : base.basis(),
            text(base.amount()),
            base.currency(),
            base.applicable(),
            base.note()));

    BigDecimal baseAmount = base.amount();
    BigDecimal surchargeTotal = BigDecimal.ZERO;
    boolean surchargeComplete = true;
    String surchargeNote = "";
    for (var surcharge : surchargesOfScope(channelCode, carrierCode, shippingMethodCode, at)) {
      AppliedSurcharge applied =
          applySurcharge(surcharge.toView(), baseAmount, query.pieces());
      steps.add(
          new FreightEstimateStep(
              "surcharge:" + surcharge.surchargeType(),
              surchargeTypeLabel(surcharge.surchargeType()) + "附加",
              applied.basis().isEmpty() ? applied.note() : applied.basis(),
              text(applied.amount()),
              applied.currency(),
              applied.applicable(),
              applied.note()));
      if (!applied.applicable() || applied.amount() == null) {
        surchargeComplete = false;
        if (surchargeNote.isEmpty()) {
          surchargeNote = "有附加费算不出来，合计是不完整的";
        }
        continue;
      }
      if (!applied.currency().equals(rule.currency())) {
        surchargeComplete = false;
        surchargeNote =
            "附加费的币种（"
                + applied.currency()
                + "）与基础运费的币种（"
                + rule.currency()
                + "）不同，跨币种绝不相加";
        continue;
      }
      surchargeTotal = surchargeTotal.add(applied.amount());
    }

    boolean complete = base.applicable() && surchargeComplete;
    BigDecimal total = null;
    if (complete) {
      total = money(baseAmount.add(surchargeTotal));
    }
    steps.add(
        new FreightEstimateStep(
            "total",
            "运费合计",
            complete
                ? "基础运费 "
                    + baseAmount.toPlainString()
                    + " + 附加费 "
                    + surchargeTotal.toPlainString()
                    + " = "
                    + total.toPlainString()
                : "有步骤算不出来，合计**不完整**",
            complete ? total.toPlainString() : null,
            complete ? rule.currency() : null,
            complete,
            complete ? "" : (surchargeNote.isEmpty() ? "基础运费算不出来" : surchargeNote)));

    return new FreightEstimate(
        channelCode,
        carrierCode,
        shippingMethodCode,
        basis,
        rule.currency(),
        at.toString(),
        quantity(chargeable.quantity()).toPlainString(),
        chargeable.unit(),
        query.pieces() == null ? null : quantity(query.pieces()).toPlainString(),
        List.copyOf(steps),
        base.applicable() ? baseAmount.toPlainString() : null,
        surchargeComplete ? money(surchargeTotal).toPlainString() : null,
        total == null ? null : total.toPlainString(),
        complete,
        complete
            ? "全部步骤都算得出来，运费合计是完整的（同一币种内相加）。"
            : (surchargeNote.isEmpty() ? "有步骤算不出来，合计是不完整的" : surchargeNote)
                + " —— **合计是不完整的**，拿去算成本会偏低。");
  }

  // ================================================================ 测算（头程费）

  /**
   * 测算一次头程的费用（逐步拆解成本项）。
   *
   * <p>🔴 成本项可以有不同币种，而**跨币种绝不相加** ⇒ 混币时
   * {@code totalAmount=null} + {@code complete=false} + 一句原因。
   */
  public HeadFreightEstimate headEstimate(HeadEstimateQuery query) {
    if (!notBlank(query.shippingMethodCode())) throw badRequest("运输方式必填");
    if (!notBlank(query.originCode())) throw badRequest("起运地必填");
    if (!notBlank(query.destinationCode())) throw badRequest("目的地必填");
    String method = query.shippingMethodCode().trim().toUpperCase(Locale.ROOT);
    String origin = query.originCode().trim().toUpperCase(Locale.ROOT);
    String destination = query.destinationCode().trim().toUpperCase(Locale.ROOT);
    OffsetDateTime at = query.at() == null ? OffsetDateTime.now() : query.at();

    var records =
        sql.fetch(
            "select h.id from logistics_head_freight_structure h"
                + " where h.tenant_id = "
                + TENANT
                + " and h.shipping_method_code = ? and h.origin_code = ? and h.destination_code = ?"
                + " and h.effective_from <= ?::timestamptz"
                + " and coalesce(h.effective_to, 'infinity'::timestamptz) > ?::timestamptz"
                + " order by h.effective_from desc, h.id limit 1",
            method,
            origin,
            destination,
            at,
            at);
    var steps = new ArrayList<HeadFreightEstimateStep>();
    if (records.isEmpty()) {
      steps.add(
          new HeadFreightEstimateStep(
              "structure",
              "命中费用结构",
              "该（运输方式 / 起运地 / 目的地）下没有生效中的头程费用结构 —— "
                  + "**没有结构 ≠ 头程费是 0**。",
              null,
              null,
              false,
              "没有生效结构"));
      return new HeadFreightEstimate(
          method,
          origin,
          destination,
          at.toString(),
          List.copyOf(steps),
          null,
          null,
          false,
          "算不出来：这个航线没有生效中的头程费用结构。");
    }
    UUID structureId = records.get(0).get("id", UUID.class);
    var components = headComponentsOf(structureId);

    BigDecimal total = BigDecimal.ZERO;
    String totalCurrency = null;
    boolean complete = true;
    String note = "";
    for (var component : components) {
      AppliedHeadComponent applied =
          applyHeadComponent(
              toView(component),
              query.shipments(),
              query.cbm(),
              query.containers(),
              query.weightKg());
      steps.add(
          new HeadFreightEstimateStep(
              "component:" + component.seq(),
              (component.label() == null || component.label().isBlank()
                      ? headComponentTypeLabel(component.componentType())
                      : component.label())
                  + "（"
                  + headComponentTypeLabel(component.componentType())
                  + "）",
              applied.basis().isEmpty() ? applied.note() : applied.basis(),
              text(applied.amount()),
              applied.currency(),
              applied.applicable(),
              applied.note()));
      if (!applied.applicable() || applied.amount() == null) {
        complete = false;
        if (note.isEmpty()) note = "有成本项算不出来，合计是不完整的";
        continue;
      }
      if (totalCurrency == null) {
        totalCurrency = applied.currency();
      } else if (!totalCurrency.equals(applied.currency())) {
        complete = false;
        note =
            "成本项的币种不一致（"
                + totalCurrency
                + " / "
                + applied.currency()
                + "），跨币种绝不相加，合计算不出来";
        continue;
      }
      total = total.add(applied.amount());
    }
    BigDecimal totalAmount = complete ? money(total) : null;
    steps.add(
        new HeadFreightEstimateStep(
            "total",
            "头程费合计",
            complete
                ? "各项成本相加 = " + totalAmount.toPlainString() + " " + totalCurrency
                : "有成本项算不出来，合计**不完整**",
            totalAmount == null ? null : totalAmount.toPlainString(),
            totalCurrency,
            complete,
            complete ? "" : note));
    return new HeadFreightEstimate(
        method,
        origin,
        destination,
        at.toString(),
        List.copyOf(steps),
        totalAmount == null ? null : totalAmount.toPlainString(),
        totalCurrency,
        complete,
        complete
            ? "全部成本项都算得出来，头程费合计是完整的（同一币种内相加）。"
            : note + " —— **合计是不完整的**。");
  }

  // ================================================================ 实际运费

  /** 实际运费台账：分页 + 渠道 / 承运商 / 运输方式 / 区间筛选。 */
  public FreightActualListPage listActuals(ActualQuery query) {
    int size = query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
    int page = Math.max(query.page(), 0);
    var where = new StringBuilder(" where a.tenant_id = " + TENANT);
    var binds = new ArrayList<Object>();
    if (notBlank(query.channelCode())) {
      where.append(" and a.channel_code = ?");
      binds.add(query.channelCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.carrierCode())) {
      where.append(" and a.carrier_code = ?");
      binds.add(query.carrierCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.shippingMethodCode())) {
      where.append(" and a.shipping_method_code = ?");
      binds.add(query.shippingMethodCode().trim().toUpperCase(Locale.ROOT));
    }
    if (query.from() != null) {
      where.append(" and a.occurred_on >= ?");
      binds.add(query.from());
    }
    if (query.to() != null) {
      where.append(" and a.occurred_on <= ?");
      binds.add(query.to());
    }
    long total = count("select count(*) from logistics_freight_actual a" + where, binds.toArray());
    var records =
        sql.fetch(
            "select a.id, a.channel_code, a.channel_name, a.carrier_code, a.carrier_name,"
                + " a.shipping_method_code, a.shipping_method_name, a.charge_basis, a.amount,"
                + " a.base_amount, a.surcharge_amount, a.currency, a.chargeable_quantity, a.quantity,"
                + " a.occurred_on, a.source, a.source_ref, a.note, a.is_void, a.void_reason,"
                + " a.voided_at, a.created_at"
                + " from logistics_freight_actual a"
                + where
                + " order by a.occurred_on desc, a.created_at desc, a.id"
                + " limit "
                + size
                + " offset "
                + ((long) page * size),
            binds.toArray());
    var items = new ArrayList<FreightActualRow>();
    for (var record : records) {
      items.add(actualRow(record));
    }
    return new FreightActualListPage(
        List.copyOf(items),
        total,
        page,
        size,
        "实际运费是**事后对账口径**，与规则（事前测算口径）是两个独立的事实。"
            + "当前唯一来源是人工登记的物流商账单观察值（不是 10 的结算结果 —— 上游没有那个事实）；"
            + "只追加，录错了作废（要写理由）。");
  }

  /** 登记一条实际运费。 */
  public FreightActualSaved recordActual(AccessService.Access actor, FreightActualInput input) {
    if (isBlank(input.chargeBasis())) throw badRequest("计费口径必填");
    String basis = input.chargeBasis().trim().toUpperCase(Locale.ROOT);
    if (chargeBasisLabel(basis).equals(basis)) {
      throw badRequest("计费口径不认识：" + basis);
    }
    var id = UUID.randomUUID();
    sql.execute(
        "insert into logistics_freight_actual (tenant_id, id, channel_code, channel_name,"
            + " carrier_code, carrier_name, shipping_method_code, shipping_method_name, charge_basis,"
            + " amount, base_amount, surcharge_amount, currency, chargeable_quantity, quantity,"
            + " occurred_on, source, source_ref, note, is_void, void_reason, created_by, created_at)"
            + " values ("
            + TENANT
            + ", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL_OBSERVATION', ?, ?, false, '', ?, now())",
        id,
        input.channelCode().trim().toUpperCase(Locale.ROOT),
        blankToEmpty(input.channelName()),
        blankToNull(
            input.carrierCode() == null ? null : input.carrierCode().trim().toUpperCase(Locale.ROOT)),
        blankToEmpty(input.carrierName()),
        input.shippingMethodCode().trim().toUpperCase(Locale.ROOT),
        blankToEmpty(input.shippingMethodName()),
        basis,
        input.amount(),
        input.baseAmount(),
        input.surchargeAmount(),
        input.currency().trim().toUpperCase(Locale.ROOT),
        input.chargeableQuantity(),
        input.quantity(),
        input.occurredOn(),
        input.sourceRef() == null ? "" : input.sourceRef().trim(),
        input.note() == null ? "" : input.note().trim(),
        actor.userId());
    return new FreightActualSaved(
        id.toString(),
        input.amount().toPlainString(),
        input.currency().trim().toUpperCase(Locale.ROOT),
        input.occurredOn().toString());
  }

  /** 作废一条实际运费（必须写理由）。作废不是删除：旧行留在库里，历史可追溯。 */
  public FreightActualSaved voidActual(
      AccessService.Access actor, UUID actualId, String reason) {
    if (reason == null || reason.isBlank()) {
      throw badRequest("作废必须写理由：没有理由的作废会让「运费为什么变了」无法解释");
    }
    var existing =
        sql.fetchOne(
            "select amount, currency, occurred_on, is_void from logistics_freight_actual"
                + " where tenant_id = "
                + TENANT
                + " and id = ?",
            actualId);
    if (existing == null) throw notFound("实际运费不存在");
    if (Boolean.TRUE.equals(existing.get("is_void", Boolean.class))) {
      throw conflict("这条实际运费已经作废了");
    }
    sql.execute(
        "update logistics_freight_actual set is_void = true, void_reason = ?, voided_by = ?,"
            + " voided_at = now()"
            + " where tenant_id = "
            + TENANT
            + " and id = ?",
        reason.trim(),
        actor.userId(),
        actualId);
    return new FreightActualSaved(
        actualId.toString(),
        text(existing.get("amount", BigDecimal.class)),
        existing.get("currency", String.class),
        existing.get("occurred_on", LocalDate.class).toString());
  }

  // ================================================================ 运费差异

  /**
   * 运费差异对照：实际运费 vs 按规则测算。
   *
   * <p>🔴 三条纪律：
   *
   * <ol>
   *   <li>两个口径是**两个独立的事实**，差异现算、不落库。
   *   <li>**算不出来就是算不出来**：没有实际运费 ⇒ 整份报告 {@code available=false}；
   *       某笔实际运费找不到生效规则 ⇒ 该组 {@code varianceAmount=null}。
   *   <li>**跨币种绝不相加**：按（渠道 / 运输方式 × 币种）分组。
   * </ol>
   */
  public FreightVarianceReport variance(VarianceQuery query) {
    LocalDate from = query.from() == null ? LocalDate.now().minusDays(30) : query.from();
    LocalDate to = query.to() == null ? LocalDate.now() : query.to();
    if (to.isBefore(from)) {
      throw badRequest("区间终点不能早于起点");
    }
    var where = new StringBuilder(
        " where a.tenant_id = "
            + TENANT
            + " and a.is_void = false and a.occurred_on >= ? and a.occurred_on <= ?");
    var binds = new ArrayList<Object>();
    binds.add(from);
    binds.add(to);
    if (notBlank(query.channelCode())) {
      where.append(" and a.channel_code = ?");
      binds.add(query.channelCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.carrierCode())) {
      where.append(" and a.carrier_code = ?");
      binds.add(query.carrierCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.shippingMethodCode())) {
      where.append(" and a.shipping_method_code = ?");
      binds.add(query.shippingMethodCode().trim().toUpperCase(Locale.ROOT));
    }
    var records =
        sql.fetch(
            "select a.id, a.channel_code, a.carrier_code, a.shipping_method_code, a.charge_basis,"
                + " a.amount, a.currency, a.chargeable_quantity, a.quantity, a.occurred_on"
                + " from logistics_freight_actual a"
                + where
                + " order by a.occurred_on, a.id",
            binds.toArray());

    String note =
        "运费差异 = 实际运费 − 按规则测算。两个口径是两个独立的事实，差异现算不落库；"
            + "按（渠道 / 运输方式 × 币种）分组，跨币种绝不相加。";
    if (records.isEmpty()) {
      return new FreightVarianceReport(
          false,
          "这个区间（"
              + from
              + " ~ "
              + to
              + "）没有实际运费记录，运费差异**算不出来**。"
              + "这不是「差异为 0」—— 0 表示「物流商完全按规则收」，而这里的事实是「没有可比的实际运费」。",
          from.toString(),
          to.toString(),
          List.of(),
          note);
    }

    // 按（渠道 / 承运商 / 运输方式 × 币种）分组。
    var groups = new LinkedHashMap<String, List<org.jooq.Record>>();
    for (var record : records) {
      String key =
          record.get("channel_code", String.class)
              + "|"
              + nullSafe(record.get("carrier_code", String.class))
              + "|"
              + record.get("shipping_method_code", String.class)
              + "|"
              + record.get("currency", String.class);
      groups.computeIfAbsent(key, k -> new ArrayList<>()).add(record);
    }

    var out = new ArrayList<FreightVarianceGroup>();
    for (var entry : groups.entrySet()) {
      var groupRecords = entry.getValue();
      var first = groupRecords.get(0);
      String channelCode = first.get("channel_code", String.class);
      String carrierCode = first.get("carrier_code", String.class);
      String shippingMethodCode = first.get("shipping_method_code", String.class);
      String currency = first.get("currency", String.class);
      BigDecimal actualTotal = BigDecimal.ZERO;
      BigDecimal estimatedTotal = BigDecimal.ZERO;
      BigDecimal comparableActual = BigDecimal.ZERO;
      int comparable = 0;
      String unmatchedNote = "";
      for (var record : groupRecords) {
        BigDecimal amount = record.get("amount", BigDecimal.class);
        actualTotal = actualTotal.add(amount);
        LocalDate occurredOn = record.get("occurred_on", LocalDate.class);
        // 对照口径：按费用发生日**当天收盘**时刻的生效规则。
        OffsetDateTime instant = occurredOn.atTime(23, 59, 59, 999_999_000).atOffset(ZoneOffset.UTC);
        var candidates = rulesOfScope(channelCode, carrierCode, shippingMethodCode, instant);
        var resolved = resolveChargeBasis(candidates, record.get("charge_basis", String.class));
        if (resolved.rule() == null) {
          unmatchedNote = "有 " + (comparable + countUnmatched(groupRecords, comparable))
              + " 笔实际运费在该日期没有对应的生效规则";
          unmatchedNote = "有实际运费在该日期没有对应的生效规则";
          continue;
        }
        var rule = resolved.rule();
        if (!rule.currency().equals(currency)) {
          unmatchedNote =
              "有实际运费的币种（"
                  + currency
                  + "）与规则币种（"
                  + rule.currency()
                  + "）不同，跨币种绝不相加";
          continue;
        }
        BaseFreight base =
            baseFreight(rule.toView(), record.get("chargeable_quantity", BigDecimal.class));
        if (!base.applicable() || base.amount() == null) {
          unmatchedNote = "有实际运费按规则算不出来（规则不适用于该计费量）";
          continue;
        }
        BigDecimal surchargeTotal = BigDecimal.ZERO;
        boolean surchargeOk = true;
        for (var surcharge :
            surchargesOfScope(channelCode, carrierCode, shippingMethodCode, instant)) {
          AppliedSurcharge applied =
              applySurcharge(
                  surcharge.toView(), base.amount(), record.get("quantity", BigDecimal.class));
          if (!applied.applicable() || applied.amount() == null) {
            surchargeOk = false;
            break;
          }
          if (!applied.currency().equals(currency)) {
            surchargeOk = false;
            break;
          }
          surchargeTotal = surchargeTotal.add(applied.amount());
        }
        if (!surchargeOk) {
          unmatchedNote = "有实际运费按规则算不出来（附加费算不出来或币种不一致）";
          continue;
        }
        comparable++;
        comparableActual = comparableActual.add(amount);
        estimatedTotal = estimatedTotal.add(base.amount().add(surchargeTotal));
      }
      String groupNote;
      BigDecimal varianceAmount;
      BigDecimal estimatedAmount;
      if (comparable == 0) {
        groupNote =
            unmatchedNote.isEmpty() ? "没有可对照的实际运费，差异算不出来" : unmatchedNote + "，差异算不出来";
        varianceAmount = null;
        estimatedAmount = null;
      } else if (comparable < groupRecords.size()) {
        groupNote =
            unmatchedNote
                + "（共 "
                + groupRecords.size()
                + " 笔，只有 "
                + comparable
                + " 笔能对照），差额算不出来 —— 拿不全的数相减没有意义";
        varianceAmount = null;
        estimatedAmount = money(estimatedTotal);
      } else {
        varianceAmount = money(comparableActual.subtract(estimatedTotal));
        estimatedAmount = money(estimatedTotal);
        groupNote =
            varianceAmount.signum() == 0
                ? "实际与规则测算一致（差异 0.00）"
                : (varianceAmount.signum() > 0
                    ? "实际比规则测算多收了 " + varianceAmount.abs().toPlainString()
                    : "实际比规则测算少收了 " + varianceAmount.abs().toPlainString());
      }
      out.add(
          new FreightVarianceGroup(
              channelCode,
              shippingMethodCode,
              currency,
              money(actualTotal).toPlainString(),
              estimatedAmount == null ? null : estimatedAmount.toPlainString(),
              varianceAmount == null ? null : varianceAmount.toPlainString(),
              groupRecords.size(),
              comparable,
              groupNote));
    }
    return new FreightVarianceReport(
        true, "", from.toString(), to.toString(), List.copyOf(out), note);
  }

  // ================================================================ 匹配与取数（内部）

  /** 计费口径的解析结果。 */
  private record BasisResolution(
      String basis, RuleRecord rule, List<String> bases, boolean ambiguous) {}

  /**
   * 在候选规则里定出计费口径与命中的那一条。
   *
   * <p>口径（写死在这里，服务层与测试共用）：
   *
   * <ul>
   *   <li>指定了承运商时，**该承运商专属**的规则优先于**渠道通用**规则；
   *   <li>同特异度时生效起点更晚的赢；再同则版本号更大（更新）的赢；再同按 id 定序 ——
   *       <b>确定性的</b>，不靠数据库返回顺序；
   *   <li>没指定计费口径时，若**最具体那一层**上有多个口径的规则并存，
   *       「这一票按什么计」就没有答案 ⇒ 报算不出来（而不是安静地挑一个）。
   * </ul>
   */
  private BasisResolution resolveChargeBasis(List<RuleRecord> candidates, String requestedBasis) {
    if (candidates.isEmpty()) {
      return new BasisResolution(requestedBasis, null, List.of(), false);
    }
    var pool = new ArrayList<RuleRecord>();
    if (notBlank(requestedBasis)) {
      for (var candidate : candidates) {
        if (candidate.chargeBasis().equals(requestedBasis)) pool.add(candidate);
      }
      if (pool.isEmpty()) {
        return new BasisResolution(requestedBasis, null, List.of(), false);
      }
      var best = pool.get(0);
      for (var candidate : pool) {
        if (compare(candidate, best) < 0) best = candidate;
      }
      return new BasisResolution(best.chargeBasis(), best, List.of(best.chargeBasis()), false);
    }
    int maxSpecificity = candidates.stream().mapToInt(c -> c.carrierSpecific() ? 1 : 0).max().orElse(0);
    var bases = new java.util.LinkedHashSet<String>();
    for (var candidate : candidates) {
      if ((candidate.carrierSpecific() ? 1 : 0) == maxSpecificity) {
        bases.add(candidate.chargeBasis());
      }
    }
    if (bases.size() > 1) {
      return new BasisResolution(null, null, List.copyOf(bases), true);
    }
    var pool2 = new ArrayList<RuleRecord>();
    for (var candidate : candidates) {
      if ((candidate.carrierSpecific() ? 1 : 0) == maxSpecificity) pool2.add(candidate);
    }
    var best = pool2.get(0);
    for (var candidate : pool2) {
      if (compare(candidate, best) < 0) best = candidate;
    }
    return new BasisResolution(best.chargeBasis(), best, List.copyOf(bases), false);
  }

  /** 规则 a 是否比 b 更该被选中（负数 = a 优先）。 */
  private static int compare(RuleRecord a, RuleRecord b) {
    int bySpecificity = Integer.compare(b.carrierSpecific() ? 1 : 0, a.carrierSpecific() ? 1 : 0);
    if (bySpecificity != 0) return bySpecificity;
    int byEffective = b.effectiveFrom().compareTo(a.effectiveFrom());
    if (byEffective != 0) return byEffective;
    int byVersion = Integer.compare(b.versionNo(), a.versionNo());
    if (byVersion != 0) return byVersion;
    return a.id().compareTo(b.id());
  }

  /** 取某个（渠道 / 承运商 / 运输方式）下在给定时刻生效的规则（含阶梯）。 */
  private List<RuleRecord> rulesOfScope(
      String channelCode, String carrierCode, String shippingMethodCode, OffsetDateTime at) {
    var records =
        sql.fetch(
            "select r.id, r.channel_code, r.carrier_code, r.shipping_method_code, r.charge_basis,"
                + " r.currency, r.volumetric_divisor, r.minimum_charge, r.effective_from, r.effective_to,"
                + " r.version_no"
                + " from logistics_freight_rule r"
                + " where r.tenant_id = "
                + TENANT
                + " and r.channel_code = ?"
                + " and r.shipping_method_code = ?"
                + " and (r.carrier_code is null or r.carrier_code = ?)"
                + " and r.effective_from <= ?::timestamptz"
                + " and coalesce(r.effective_to, 'infinity'::timestamptz) > ?::timestamptz",
            channelCode,
            shippingMethodCode,
            carrierCode,
            at,
            at);
    var out = new ArrayList<RuleRecord>();
    var ids = new ArrayList<UUID>();
    for (var record : records) {
      UUID id = record.get("id", UUID.class);
      ids.add(id);
      out.add(
          new RuleRecord(
              id,
              record.get("channel_code", String.class),
              record.get("carrier_code", String.class),
              record.get("shipping_method_code", String.class),
              record.get("charge_basis", String.class),
              record.get("currency", String.class),
              record.get("volumetric_divisor", BigDecimal.class),
              record.get("minimum_charge", BigDecimal.class),
              record.get("effective_from", OffsetDateTime.class),
              record.get("effective_to", OffsetDateTime.class),
              record.get("version_no", Integer.class),
              record.get("carrier_code", String.class) != null,
              List.of()));
    }
    var byRule = tiersByRule(ids);
    var rebuilt = new ArrayList<RuleRecord>();
    for (var rule : out) {
      rebuilt.add(
          new RuleRecord(
              rule.id(), rule.channelCode(), rule.carrierCode(), rule.shippingMethodCode(),
              rule.chargeBasis(), rule.currency(), rule.volumetricDivisor(), rule.minimumCharge(),
              rule.effectiveFrom(), rule.effectiveTo(), rule.versionNo(), rule.carrierSpecific(),
              byRule.getOrDefault(rule.id(), List.of())));
    }
    out.clear();
    out.addAll(rebuilt);
    return out;
  }

  /** 取某个（渠道 / 承运商 / 运输方式）下在给定时刻生效的附加费。 */
  private List<SurchargeRecord> surchargesOfScope(
      String channelCode, String carrierCode, String shippingMethodCode, OffsetDateTime at) {
    var records =
        sql.fetch(
            "select s.id, s.surcharge_type, s.calc_basis, s.rate, s.fixed_amount, s.currency"
                + " from logistics_freight_surcharge s"
                + " where s.tenant_id = "
                + TENANT
                + " and s.channel_code = ?"
                + " and s.shipping_method_code = ?"
                + " and (s.carrier_code is null or s.carrier_code = ?)"
                + " and s.effective_from <= ?::timestamptz"
                + " and coalesce(s.effective_to, 'infinity'::timestamptz) > ?::timestamptz"
                + " order by s.surcharge_type, s.effective_from desc, s.id",
            channelCode,
            shippingMethodCode,
            carrierCode,
            at,
            at);
    var out = new ArrayList<SurchargeRecord>();
    for (var record : records) {
      out.add(
          new SurchargeRecord(
              record.get("id", UUID.class),
              record.get("surcharge_type", String.class),
              record.get("calc_basis", String.class),
              record.get("rate", BigDecimal.class),
              record.get("fixed_amount", BigDecimal.class),
              record.get("currency", String.class)));
    }
    return out;
  }

  /** 一次性取一批规则的阶梯（避免 N+1）。 */
  private Map<UUID, List<TierView>> tiersByRule(List<UUID> ruleIds) {
    var map = new LinkedHashMap<UUID, List<TierView>>();
    if (ruleIds == null || ruleIds.isEmpty()) return map;
    var records =
        sql.fetch(
            "select rule_id, seq, min_value, max_value, price_mode, flat_price, first_limit,"
                + " first_price, additional_price"
                + " from logistics_freight_rule_tier"
                + " where tenant_id = "
                + TENANT
                + " and rule_id in ("
                + placeholders(ruleIds)
                + ") order by rule_id, seq",
            ruleIds.toArray());
    for (var record : records) {
      map.computeIfAbsent(record.get("rule_id", UUID.class), k -> new ArrayList<>())
          .add(
              new TierView(
                  record.get("seq", Integer.class),
                  record.get("min_value", BigDecimal.class),
                  record.get("max_value", BigDecimal.class),
                  record.get("price_mode", String.class),
                  record.get("flat_price", BigDecimal.class),
                  record.get("first_limit", BigDecimal.class),
                  record.get("first_price", BigDecimal.class),
                  record.get("additional_price", BigDecimal.class)));
    }
    return map;
  }

  /** 取一条规则的阶梯。 */
  private List<FreightTierRow> tiersOf(UUID ruleId) {
    var records =
        sql.fetch(
            "select id, seq, min_value, max_value, price_mode, flat_price, first_limit, first_price,"
                + " additional_price"
                + " from logistics_freight_rule_tier"
                + " where tenant_id = "
                + TENANT
                + " and rule_id = ? order by seq",
            ruleId);
    var out = new ArrayList<FreightTierRow>();
    for (var record : records) {
      out.add(
          new FreightTierRow(
              record.get("id", UUID.class),
              record.get("seq", Integer.class),
              text(record.get("min_value", BigDecimal.class)),
              text(record.get("max_value", BigDecimal.class)),
              record.get("price_mode", String.class),
              priceModeLabel(record.get("price_mode", String.class)),
              text(record.get("flat_price", BigDecimal.class)),
              text(record.get("first_limit", BigDecimal.class)),
              text(record.get("first_price", BigDecimal.class)),
              text(record.get("additional_price", BigDecimal.class))));
    }
    return List.copyOf(out);
  }

  /** 取一条头程结构的成本项。 */
  private List<HeadFreightComponentRow> headComponentsOf(UUID structureId) {
    var records =
        sql.fetch(
            "select id, seq, component_type, label, calc_basis, unit_amount, currency"
                + " from logistics_head_freight_component"
                + " where tenant_id = "
                + TENANT
                + " and structure_id = ? order by seq",
            structureId);
    var out = new ArrayList<HeadFreightComponentRow>();
    for (var record : records) {
      String type = record.get("component_type", String.class);
      String calcBasis = record.get("calc_basis", String.class);
      out.add(
          new HeadFreightComponentRow(
              record.get("id", UUID.class),
              record.get("seq", Integer.class),
              type,
              headComponentTypeLabel(type),
              record.get("label", String.class),
              calcBasis,
              headCalcBasisLabel(calcBasis),
              text(record.get("unit_amount", BigDecimal.class)),
              record.get("currency", String.class)));
    }
    return List.copyOf(out);
  }

  /** 头程成本项的算式视图。 */
  private static HeadComponentView toView(HeadFreightComponentRow row) {
    return new HeadComponentView(
        row.seq(),
        row.componentType(),
        row.label(),
        row.calcBasis(),
        row.unitAmount() == null ? BigDecimal.ZERO : new BigDecimal(row.unitAmount()),
        row.currency());
  }

  /** 插入一条规则。 */
  private void insertRule(
      AccessService.Access actor, UUID id, FreightRuleInput input, UUID supersedesId, int versionNo) {
    sql.execute(
        "insert into logistics_freight_rule (tenant_id, id, channel_code, channel_name,"
            + " carrier_code, carrier_name, shipping_method_code, shipping_method_name,"
            + " charge_basis, currency, volumetric_divisor, minimum_charge, effective_from, effective_to,"
            + " version_no, supersedes_id, superseded_by_id, status, disable_reason, note, created_by, created_at)"
            + " values ("
            + TENANT
            + ", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz,"
            + " ?, ?, null, 'ACTIVE', '', ?, ?, now())",
        id,
        input.channelCode().trim().toUpperCase(Locale.ROOT),
        blankToEmpty(input.channelName()),
        blankToNull(
            input.carrierCode() == null ? null : input.carrierCode().trim().toUpperCase(Locale.ROOT)),
        blankToEmpty(input.carrierName()),
        input.shippingMethodCode().trim().toUpperCase(Locale.ROOT),
        blankToEmpty(input.shippingMethodName()),
        input.chargeBasis().trim().toUpperCase(Locale.ROOT),
        input.currency().trim().toUpperCase(Locale.ROOT),
        input.volumetricDivisor(),
        input.minimumCharge(),
        input.effectiveFrom(),
        input.effectiveTo(),
        versionNo,
        supersedesId,
        input.note() == null ? "" : input.note().trim(),
        actor.userId());
  }

  /** 插入一条规则的阶梯。 */
  private void insertTiers(UUID ruleId, List<FreightTierInput> tiers) {
    if (tiers == null) return;
    for (var tier : tiers) {
      sql.execute(
          "insert into logistics_freight_rule_tier (tenant_id, id, rule_id, seq, min_value, max_value,"
              + " price_mode, flat_price, first_limit, first_price, additional_price, created_at)"
              + " values ("
              + TENANT
              + ", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())",
          UUID.randomUUID(),
          ruleId,
          tier.seq(),
          tier.minValue(),
          tier.maxValue(),
          tier.priceMode().trim().toUpperCase(Locale.ROOT),
          tier.flatPrice(),
          tier.firstLimit(),
          tier.firstPrice(),
          tier.additionalPrice());
    }
  }

  // ================================================================ 行映射与小工具

  private FreightRuleRow ruleRow(org.jooq.Record record) {
    String basis = record.get("charge_basis", String.class);
    String status = record.get("status", String.class);
    return new FreightRuleRow(
        record.get("id", UUID.class),
        record.get("channel_code", String.class),
        record.get("channel_name", String.class),
        record.get("carrier_code", String.class),
        record.get("carrier_name", String.class),
        record.get("shipping_method_code", String.class),
        record.get("shipping_method_name", String.class),
        basis,
        chargeBasisLabel(basis),
        record.get("currency", String.class),
        text(record.get("volumetric_divisor", BigDecimal.class)),
        text(record.get("minimum_charge", BigDecimal.class)),
        instantText(record.get("effective_from", OffsetDateTime.class)),
        instantText(record.get("effective_to", OffsetDateTime.class)),
        record.get("version_no", Integer.class) == null ? 1 : record.get("version_no", Integer.class),
        record.get("supersedes_id", UUID.class),
        record.get("superseded_by_id", UUID.class),
        status,
        statusLabel(status),
        record.get("disable_reason", String.class),
        record.get("note", String.class),
        record.get("tier_count", Integer.class) == null ? 0 : record.get("tier_count", Integer.class),
        instantText(record.get("created_at", OffsetDateTime.class)));
  }

  private FreightSurchargeRow surchargeRow(org.jooq.Record record) {
    String type = record.get("surcharge_type", String.class);
    String calcBasis = record.get("calc_basis", String.class);
    String status = record.get("status", String.class);
    return new FreightSurchargeRow(
        record.get("id", UUID.class),
        record.get("channel_code", String.class),
        record.get("channel_name", String.class),
        record.get("carrier_code", String.class),
        record.get("carrier_name", String.class),
        record.get("shipping_method_code", String.class),
        record.get("shipping_method_name", String.class),
        type,
        surchargeTypeLabel(type),
        calcBasis,
        surchargeBasisLabel(calcBasis),
        text(record.get("rate", BigDecimal.class)),
        text(record.get("fixed_amount", BigDecimal.class)),
        record.get("currency", String.class),
        instantText(record.get("effective_from", OffsetDateTime.class)),
        instantText(record.get("effective_to", OffsetDateTime.class)),
        record.get("version_no", Integer.class) == null ? 1 : record.get("version_no", Integer.class),
        record.get("supersedes_id", UUID.class),
        record.get("superseded_by_id", UUID.class),
        status,
        statusLabel(status),
        record.get("disable_reason", String.class),
        record.get("note", String.class),
        instantText(record.get("created_at", OffsetDateTime.class)));
  }

  private HeadFreightStructureRow headStructureRow(org.jooq.Record record) {
    String status = record.get("status", String.class);
    return new HeadFreightStructureRow(
        record.get("id", UUID.class),
        record.get("shipping_method_code", String.class),
        record.get("shipping_method_name", String.class),
        record.get("origin_code", String.class),
        record.get("origin_name", String.class),
        record.get("destination_code", String.class),
        record.get("destination_name", String.class),
        record.get("currency", String.class),
        instantText(record.get("effective_from", OffsetDateTime.class)),
        instantText(record.get("effective_to", OffsetDateTime.class)),
        record.get("version_no", Integer.class) == null ? 1 : record.get("version_no", Integer.class),
        record.get("supersedes_id", UUID.class),
        record.get("superseded_by_id", UUID.class),
        status,
        statusLabel(status),
        record.get("disable_reason", String.class),
        record.get("note", String.class),
        record.get("component_count", Integer.class) == null
            ? 0
            : record.get("component_count", Integer.class),
        instantText(record.get("created_at", OffsetDateTime.class)));
  }

  private FreightActualRow actualRow(org.jooq.Record record) {
    String basis = record.get("charge_basis", String.class);
    String source = record.get("source", String.class);
    return new FreightActualRow(
        record.get("id", UUID.class),
        record.get("channel_code", String.class),
        record.get("channel_name", String.class),
        record.get("carrier_code", String.class),
        record.get("carrier_name", String.class),
        record.get("shipping_method_code", String.class),
        record.get("shipping_method_name", String.class),
        basis,
        chargeBasisLabel(basis),
        text(record.get("amount", BigDecimal.class)),
        text(record.get("base_amount", BigDecimal.class)),
        text(record.get("surcharge_amount", BigDecimal.class)),
        record.get("currency", String.class),
        text(record.get("chargeable_quantity", BigDecimal.class)),
        text(record.get("quantity", BigDecimal.class)),
        record.get("occurred_on", LocalDate.class) == null
            ? null
            : record.get("occurred_on", LocalDate.class).toString(),
        source,
        actualSourceLabel(source),
        record.get("source_ref", String.class),
        record.get("note", String.class),
        Boolean.TRUE.equals(record.get("is_void", Boolean.class)),
        record.get("void_reason", String.class),
        instantText(record.get("voided_at", OffsetDateTime.class)),
        instantText(record.get("created_at", OffsetDateTime.class)));
  }

  private long count(String sqlText, Object... params) {
    return sql.fetchOne(sqlText, params).get(0, Long.class);
  }

  /**
   * 拼出一串 {@code ?,?,?} 占位符。
   *
   * <p>不用 {@code = any(?::uuid[])}：jOOQ 把 {@code UUID[]} 绑成**一个** uuid 参数（而不是 uuid[]），
   * 整条语句会在 prepare 阶段报 bad SQL grammar。与 {@code BrandService} / {@code CategoryTreeService}
   * 同一做法。调用方必须先判空。
   */
  private static String placeholders(List<UUID> ids) {
    return String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
  }

  private static int countUnmatched(List<org.jooq.Record> records, int comparable) {
    return records.size() - comparable;
  }

  private static String nullSafe(String value) {
    return value == null ? "" : value;
  }

  private static String instantText(OffsetDateTime value) {
    return value == null ? null : value.toString();
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /**
   * 名称快照列：DB 层是 {@code NOT NULL DEFAULT ''}，
   * 所以这里给空串而不是 null（null 会撞 not-null 约束）。
   */
  private static String blankToEmpty(String value) {
    return value == null ? "" : value.trim();
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
