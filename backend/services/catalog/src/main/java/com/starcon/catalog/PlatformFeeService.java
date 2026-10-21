package com.starcon.catalog;

import static com.starcon.catalog.PlatformFeeModels.*;

import com.starcon.catalog.PlatformFeePort.PlatformDirectoryUnavailableException;
import com.starcon.catalog.PlatformFeeRules.RuleView;
import com.starcon.catalog.PlatformFeeRules.TierView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
 * 5.2.3.1 平台费用：费用规则（事前测算口径）与实际费用（事后对账口径），以及两者的差额。
 *
 * <p><b>🔴 本服务最重要的两条纪律</b>：
 *
 * <ol>
 *   <li><b>规则与实际是两个独立的事实</b>（{@code platform_fee_rule} /
 *       {@code platform_fee_actual} 两张表）。测算用规则，差异用「实际 − 按规则测算」。
 *       两者永不合并成一个字段 —— 合并之后差异恒等于 0，
 *       蓝图加粗句要的「显性展示费用差异」就无处落地了。
 *   <li><b>算不出来就是算不出来</b>：没有实际费用、没有生效规则、规则不适用，
 *       一律表达成 {@code null} + 一句原因，**绝不**用 0 冒充。
 * </ol>
 *
 * <p><b>跨服务只读</b>：平台 / 站点的唯一真相在 channel 服务的全局码表里，
 * 本服务经 {@link PlatformFeePort} 走**授权只读 HTTP 端点**取数
 * （AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图）。取不到时抛异常 → 503，
 * 不返回空集合冒充「这个平台没有费率」。
 */
@Service
public class PlatformFeeService {

  /** RLS 租户上下文。与本服务其余服务类的写法一致。 */
  private static final String TENANT = "nullif(current_setting('app.current_tenant',true),'')::uuid";

  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;
  private final PlatformFeePort directory;

  public PlatformFeeService(DSLContext sql, PlatformFeePort directory) {
    this.sql = sql;
    this.directory = directory;
  }

  // ================================================================ 查询入参

  /** 规则台账的筛选条件。 */
  public record RuleQuery(
      String keyword,
      String platformCode,
      String siteCode,
      String feeType,
      Boolean enabled,
      int page,
      int size) {}

  /** 测算入参。 */
  public record EstimateQuery(
      String platformCode,
      String siteCode,
      UUID internalCategoryId,
      UUID platformCategoryId,
      BigDecimal saleAmount,
      BigDecimal quantity,
      String currency,
      OffsetDateTime at) {}

  /** 实际费用台账的筛选条件。 */
  public record ActualQuery(
      String platformCode, String siteCode, String feeType, LocalDate from, LocalDate to, int page, int size) {}

  /** 费用差异对照入参。 */
  public record VarianceQuery(
      String platformCode, String siteCode, String feeType, LocalDate from, LocalDate to) {}

  // ================================================================ 内部结构

  /** 一条规则（连阶梯），供算式与匹配使用。 */
  private record RuleRecord(
      UUID id,
      String platformCode,
      String siteCode,
      UUID internalCategoryId,
      UUID platformCategoryId,
      String feeType,
      ChargeBasis chargeBasis,
      BigDecimal rate,
      BigDecimal fixedAmount,
      String currency,
      BigDecimal periodTotalAmount,
      String periodCurrency,
      AllocationBasis allocationBasis,
      BigDecimal allocationDivisor,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveTo,
      boolean enabled,
      String note,
      String internalCategoryName,
      String platformCategoryName,
      List<TierView> tiers) {

    RuleView toView() {
      return new RuleView(
          id,
          feeType,
          chargeBasis,
          rate,
          fixedAmount,
          currency,
          periodTotalAmount,
          periodCurrency,
          allocationBasis,
          allocationDivisor,
          tiers);
    }

    int specificity() {
      return PlatformFeeRules.specificity(siteCode, internalCategoryId, platformCategoryId);
    }
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：费用类型 / 计价形态 / 分摊口径三张词表 + 六个维度（逐个写明取不到没有 / 归谁管）。
   *
   * <p>🔴 页面固定显示它 —— 蓝图加粗句要的是「差额要显性展示」，
   * 而「实际费用现在来自哪里」必须写在脸上，否则运营会把人工登记的数当成 12 的对账结果。
   */
  public PlatformFeeCapabilities capabilities() {
    var feeTypes = new ArrayList<FeeTypeOption>();
    for (FeeType type : FeeType.values()) {
      feeTypes.add(new FeeTypeOption(type.name(), type.label(), type.basis()));
    }
    var chargeBases = new ArrayList<ChargeBasisOption>();
    for (ChargeBasis basis : ChargeBasis.values()) {
      chargeBases.add(new ChargeBasisOption(basis.name(), basis.label(), basis.basis()));
    }
    var allocationBases = new ArrayList<FeeTypeOption>();
    for (AllocationBasis basis : AllocationBasis.values()) {
      allocationBases.add(new FeeTypeOption(basis.name(), basis.label(), basis.basis()));
    }

    var dimensions = new ArrayList<FeeCapabilityDimension>();
    dimensions.add(
        new FeeCapabilityDimension(
            "platform_directory",
            "平台 / 站点目录",
            true,
            "2 平台目录（channel 服务）",
            "平台费用规则挂在「平台 / 站点 / 类目」三个维度上，读不到目录就没法确认平台码与站点码真实存在。",
            "经 channel 的授权只读端点 GET /channel/platforms 取数，租户由 channel 从已认证成员关系解析。",
            "本服务不在库里留平台 / 站点副本（AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图）。"));
    dimensions.add(
        new FeeCapabilityDimension(
            "fee_rules",
            "平台费用规则（事前测算口径）",
            true,
            "5.2.3.1（本模块）",
            "没有规则就测算不出平台费用，利润会偏高，而页面上看不出任何异常。",
            "佣金比例或阶梯、订阅费分摊、Referral Fee、交易费、罚款类型，按平台 / 站点 / 类目维护。",
            "费率是精确值（numeric(19,8) 的小数口径，0.15 = 15%），全程 BigDecimal，禁浮点。"));
    dimensions.add(
        new FeeCapabilityDimension(
            "actual_fee_reconciliation",
            "实际费用对账（事后对账口径）",
            false,
            "12 财务（finance 服务）",
            "🔴 缺了它，测算与实际的差额无法解释：现在只能靠人工登记平台账单观察值来校准，"
                + "拿不到 12 的结算对账结果，就没法回答「这个月多扣的那笔钱到底扣在哪」。",
            "12 财务的结算对账结果按蓝图关系句是实际费用的唯一权威来源。",
            "finance 服务在本仓尚不存在，本模块不编一个假的「历史实际费用」数据源去冒充它；"
                + "实际费用当前只有人工登记（MANUAL_OBSERVATION）一种来源，页面上如实标注。"));
    dimensions.add(
        new FeeCapabilityDimension(
            "fee_variance",
            "费用差异（规则测算 vs 实际）",
            true,
            "5.2.3.1（本模块）",
            "没有差异对照，测算就永远不准却不知道为什么。",
            "差异 = 实际费用 − 按规则测算；两个口径是**两个独立的事实**，差额由服务层现算，不落库（"
                + "落库就会多出第三份可能与前两份不一致的副本）。",
            "算不出来时（没有实际费用 / 没有生效规则 / 规则不适用）返回「算不出来」+ 原因，**绝不**返回 0。"));
    dimensions.add(
        new FeeCapabilityDimension(
            "logistics_fee_rules",
            "物流与头程费用规则",
            false,
            "5.2.3.2",
            "🔴 5.2.3.2 **未交付**：物流与头程的计费规则（按重量 / 体积 / 件数、燃油附加、旺季附加）"
                + "与本模块的平台费用规则不是同一套数据，本模块**不代它开形状**。",
            "上游 = 10 物流与头程；下游 = 5.3.1.1 测算。",
            "本模块只产出平台侧费用规则。"));
    dimensions.add(
        new FeeCapabilityDimension(
            "profit_consumer",
            "利润报表（13）",
            false,
            "13 利润",
            "13 未交付时，本模块的差异只能在本模块页面里看；利润报表里的「费用差异」要等 13 来读。",
            "下游 = 13 利润、14 广告、5.3.1.1 测算。",
            "本模块已把差异做成可展开的对照结构，13 落地后直接消费。"));

    return new PlatformFeeCapabilities(
        List.copyOf(feeTypes),
        List.copyOf(chargeBases),
        List.copyOf(allocationBases),
        List.copyOf(dimensions),
        "规则是**事前测算口径**：回答「这笔单按规则应该扣多少」。"
            + "它挂在「平台 / 站点 / 类目」三个维度上，特异度高的规则优先"
            + "（站点 +8、内部分类 +4、平台类目 +2），没写的维度是平台级 / 全类目兜底。",
        "实际费用是**事后对账口径**：回答「平台实际扣了多少」。"
            + "它与规则是**两个独立的事实**，各自独立落库、独立追溯。",
        "费用差异 = 实际 − 按规则测算。两条纪律："
            + "① 两个口径永不合并成一个字段；② 算不出来时显示「算不出来」+ 原因，"
            + "**绝不显示 0.00** —— 显示 0 是在说「没有差异」，而真实情况是「算不出差异」。",
        "平台 / 站点目录来自 2 平台目录（channel 服务），跨服务只读，走授权 HTTP 端点，"
            + "Authorization 头原样透传，租户由 channel 从成员关系解析。",
        "下游 = 5.3.1.1 测算（消费本模块的费用项）、13 利润（消费费用差异）、14 广告。"
            + "13 与 5.3.1.1 当前未交付。",
        "本模块的动作：维护费用规则、维护阶梯、登记实际费用、作废实际费用、"
            + "测算费用项、查看费用差异。规则可改（乐观锁），实际费用只追加（作废留理由）。");
  }

  // ================================================================ 平台目录（上游 2）

  /**
   * 平台 / 站点目录。上游读不到时抛 503，**不**返回空清单。
   */
  public PlatformDirectory directory(String bearer) {
    try {
      return directory.directory(bearer);
    } catch (PlatformDirectoryUnavailableException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
    }
  }

  // ================================================================ 规则台账

  /** 规则台账：分页 + 关键词 / 平台 / 站点 / 费用类型 / 是否启用筛选。 */
  public FeeRuleListPage list(RuleQuery query) {
    int size = query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
    int page = Math.max(query.page(), 0);
    var where = new StringBuilder(" where r.tenant_id = " + TENANT);
    var binds = new ArrayList<Object>();
    if (notBlank(query.keyword())) {
      where.append(" and (r.platform_code ilike ? or r.site_code ilike ? or r.note ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    if (notBlank(query.platformCode())) {
      where.append(" and r.platform_code = ?");
      binds.add(query.platformCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.siteCode())) {
      where.append(" and r.site_code = ?");
      binds.add(query.siteCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.feeType())) {
      where.append(" and r.fee_type = ?");
      binds.add(query.feeType().trim().toUpperCase(Locale.ROOT));
    }
    if (query.enabled() != null) {
      where.append(" and r.enabled = ?");
      binds.add(query.enabled());
    }
    String from =
        " from platform_fee_rule r"
            + " left join product_category ic on ic.tenant_id = r.tenant_id and ic.id = r.internal_category_id"
            + " left join product_category pc on pc.tenant_id = r.tenant_id and pc.id = r.platform_category_id";
    long total = count("select count(*)" + from + where, binds.toArray());
    var records =
        sql.fetch(
            "select r.id, r.platform_code, r.site_code, r.internal_category_id, r.platform_category_id,"
                + " r.fee_type, r.charge_basis, r.rate, r.fixed_amount, r.currency,"
                + " r.period_total_amount, r.period_currency, r.allocation_basis, r.allocation_divisor,"
                + " r.effective_from, r.effective_to, r.enabled, r.note, r.version, r.created_at, r.updated_at,"
                + " ic.name as internal_category_name, pc.name as platform_category_name,"
                + " (select count(*) from platform_fee_rule_tier t"
                + "   where t.tenant_id = r.tenant_id and t.rule_id = r.id) as tier_count"
                + from
                + where
                + " order by r.updated_at desc, r.platform_code, r.fee_type, r.id"
                + " limit "
                + size
                + " offset "
                + ((long) page * size),
            binds.toArray());
    var items = new ArrayList<FeeRuleRow>();
    for (var record : records) {
      items.add(ruleRow(record));
    }
    return new FeeRuleListPage(
        List.copyOf(items),
        total,
        page,
        size,
        "规则是事前测算口径：一条规则 = 一个（平台 / 站点 / 类目 × 费用类型）的费率口径，"
            + "按生效区间取用。特异度高的规则优先（站点 > 内部分类 > 平台类目）。");
  }

  /** 规则明细：规则 + 阶梯 + 作用域口径。 */
  public FeeRuleDetail detail(UUID ruleId) {
    var records =
        sql.fetch(
            "select r.id, r.platform_code, r.site_code, r.internal_category_id, r.platform_category_id,"
                + " r.fee_type, r.charge_basis, r.rate, r.fixed_amount, r.currency,"
                + " r.period_total_amount, r.period_currency, r.allocation_basis, r.allocation_divisor,"
                + " r.effective_from, r.effective_to, r.enabled, r.note, r.version, r.created_at, r.updated_at,"
                + " ic.name as internal_category_name, pc.name as platform_category_name,"
                + " (select count(*) from platform_fee_rule_tier t"
                + "   where t.tenant_id = r.tenant_id and t.rule_id = r.id) as tier_count"
                + " from platform_fee_rule r"
                + " left join product_category ic on ic.tenant_id = r.tenant_id and ic.id = r.internal_category_id"
                + " left join product_category pc on pc.tenant_id = r.tenant_id and pc.id = r.platform_category_id"
                + " where r.tenant_id = "
                + TENANT
                + " and r.id = ?",
            ruleId);
    if (records.isEmpty()) throw notFound("费用规则不存在");
    var record = records.get(0);
    var row = ruleRow(record);
    var tiers = tiersOf(ruleId);
    return new FeeRuleDetail(
        row,
        tiers,
        PlatformFeeRules.scopeBasis(
            row.platformCode(),
            row.siteCode(),
            row.internalCategoryName(),
            row.platformCategoryName()),
        "阶梯的选档口径：下界含、上界不含（最后一档无上界）；"
            + "销售额低于最低档下界时该规则**不适用**，而不是按 0 扣。");
  }

  // ================================================================ 保存规则

  /**
   * 新建 / 修改一条费用规则。
   *
   * @param ruleId null ⇒ 新建；非 null ⇒ 修改（乐观锁）
   */
  public FeeRuleSaved save(
      AccessService.Access actor, UUID ruleId, FeeRuleInput input, String bearer) {
    var problems = PlatformFeeRules.validateRule(input);
    if (!problems.isEmpty()) {
      throw badRequest("费用规则没通过校验：" + String.join("；", problems));
    }
    // 平台 / 站点必须是真实存在的：唯一真相在 channel 的全局码表里。
    requireKnownScope(input.platformCode(), input.siteCode(), bearer);
    requireKnownCategories(input.platformCode(), input.internalCategoryId(), input.platformCategoryId());

    String platformCode = input.platformCode().trim().toUpperCase(Locale.ROOT);
    String siteCode = blankToNull(input.siteCode() == null ? null : input.siteCode().trim().toUpperCase(Locale.ROOT));
    String feeType = input.feeType().trim().toUpperCase(Locale.ROOT);
    boolean enabled = input.enabled() == null || input.enabled();

    if (ruleId == null) {
      requireNoOverlap(null, platformCode, siteCode, input.internalCategoryId(),
          input.platformCategoryId(), feeType, input.effectiveFrom(), input.effectiveTo());
      var id = UUID.randomUUID();
      sql.execute(
          "insert into platform_fee_rule (tenant_id, id, platform_code, site_code,"
              + " internal_category_id, platform_category_id, fee_type, charge_basis, rate, fixed_amount,"
              + " currency, period_total_amount, period_currency, allocation_basis, allocation_divisor,"
              + " effective_from, effective_to, enabled, note, version, created_by, created_at, updated_at)"
              + " values ("
              + TENANT
              + ", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
              + " ?::timestamptz, ?::timestamptz, ?, ?, 0, ?, now(), now())",
          id,
          platformCode,
          siteCode,
          input.internalCategoryId(),
          input.platformCategoryId(),
          feeType,
          input.chargeBasis().trim().toUpperCase(Locale.ROOT),
          input.rate(),
          input.fixedAmount(),
          blankToNull(input.currency() == null ? null : input.currency().trim().toUpperCase(Locale.ROOT)),
          input.periodTotalAmount(),
          blankToNull(input.periodCurrency() == null ? null : input.periodCurrency().trim().toUpperCase(Locale.ROOT)),
          blankToNull(input.allocationBasis() == null ? null : input.allocationBasis().trim().toUpperCase(Locale.ROOT)),
          input.allocationDivisor(),
          input.effectiveFrom(),
          input.effectiveTo(),
          enabled,
          input.note() == null ? "" : input.note().trim(),
          actor.userId());
      return new FeeRuleSaved(
          id.toString(), feeType, input.chargeBasis().trim().toUpperCase(Locale.ROOT),
          input.effectiveFrom().toString(), 0L);
    }

    // 修改：乐观锁。
    var current =
        sql.fetchOne(
            "select version from platform_fee_rule where tenant_id = " + TENANT + " and id = ? for update",
            ruleId);
    if (current == null) throw notFound("费用规则不存在");
    long currentVersion = current.get(0, Long.class);
    if (input.version() == null || input.version() != currentVersion) {
      throw conflict(
          "这条规则已经被别人改过了（当前版本 "
              + currentVersion
              + "，你提交的是 "
              + input.version()
              + "）。请刷新后重新提交 —— 覆盖别人的改动会让费率对不上账。");
    }
    requireNoOverlap(ruleId, platformCode, siteCode, input.internalCategoryId(),
        input.platformCategoryId(), feeType, input.effectiveFrom(), input.effectiveTo());
    sql.execute(
        "update platform_fee_rule set platform_code = ?, site_code = ?, internal_category_id = ?,"
            + " platform_category_id = ?, fee_type = ?, charge_basis = ?, rate = ?, fixed_amount = ?,"
            + " currency = ?, period_total_amount = ?, period_currency = ?, allocation_basis = ?,"
            + " allocation_divisor = ?, effective_from = ?::timestamptz, effective_to = ?::timestamptz,"
            + " enabled = ?, note = ?, version = version + 1, updated_at = now()"
            + " where tenant_id = "
            + TENANT
            + " and id = ?",
        platformCode,
        siteCode,
        input.internalCategoryId(),
        input.platformCategoryId(),
        feeType,
        input.chargeBasis().trim().toUpperCase(Locale.ROOT),
        input.rate(),
        input.fixedAmount(),
        blankToNull(input.currency() == null ? null : input.currency().trim().toUpperCase(Locale.ROOT)),
        input.periodTotalAmount(),
        blankToNull(input.periodCurrency() == null ? null : input.periodCurrency().trim().toUpperCase(Locale.ROOT)),
        blankToNull(input.allocationBasis() == null ? null : input.allocationBasis().trim().toUpperCase(Locale.ROOT)),
        input.allocationDivisor(),
        input.effectiveFrom(),
        input.effectiveTo(),
        enabled,
        input.note() == null ? "" : input.note().trim(),
        ruleId);
    return new FeeRuleSaved(
        ruleId.toString(), feeType, input.chargeBasis().trim().toUpperCase(Locale.ROOT),
        input.effectiveFrom().toString(), currentVersion + 1);
  }

  /**
   * 整体替换一条规则的阶梯。
   *
   * <p><b>整体替换而不是增删单档</b>：阶梯是「一条规则的一个整体口径」，
   * 增删单档会让「这条规则现在的阶梯是什么」在中间态下说不清
   * （第 2 档改了、第 3 档还没改的半成品规则）。整体替换在**同一事务**里删 + 插，
   * 要么全换要么全不换。
   */
  public FeeRuleDetail replaceTiers(AccessService.Access actor, UUID ruleId, List<FeeTierInput> tiers) {
    var list = tiers == null ? List.<FeeTierInput>of() : tiers;
    var problems = PlatformFeeRules.validateTiers(list);
    if (!problems.isEmpty()) {
      throw badRequest("阶梯没通过校验：" + String.join("；", problems));
    }
    var exists =
        sql.fetchOne(
            "select charge_basis from platform_fee_rule where tenant_id = " + TENANT + " and id = ?",
            ruleId);
    if (exists == null) throw notFound("费用规则不存在");
    String chargeBasis = exists.get(0, String.class);
    if (!list.isEmpty() && !"TIERED_PERCENTAGE".equals(chargeBasis)) {
      throw badRequest("只有「阶梯比例」形态的规则才有阶梯；这条规则的计价形态是 " + chargeBasis);
    }
    sql.execute(
        "delete from platform_fee_rule_tier where tenant_id = " + TENANT + " and rule_id = ?", ruleId);
    for (var tier : list) {
      sql.execute(
          "insert into platform_fee_rule_tier (tenant_id, id, rule_id, seq, min_amount, max_amount, rate)"
              + " values ("
              + TENANT
              + ", ?, ?, ?, ?, ?, ?)",
          UUID.randomUUID(),
          ruleId,
          tier.seq(),
          tier.minAmount(),
          tier.maxAmount(),
          tier.rate());
    }
    sql.execute(
        "update platform_fee_rule set updated_at = now() where tenant_id = " + TENANT + " and id = ?",
        ruleId);
    return detail(ruleId);
  }

  // ================================================================ 测算（费用项）

  /**
   * 测算一次销售会产生的平台费用项。
   *
   * <p>🔴 **算不出来就是算不出来**：没有生效规则、规则落不进任何档，
   * 一律 {@code applicable=false} + {@code amount=null}，**绝不**当 0。
   */
  public FeeEstimate estimate(EstimateQuery query) {
    if (notBlank(query.platformCode()) == false) {
      throw badRequest("平台必填：平台费用规则挂在平台维度上");
    }
    String platformCode = query.platformCode().trim().toUpperCase(Locale.ROOT);
    String siteCode = blankToNull(query.siteCode() == null ? null : query.siteCode().trim().toUpperCase(Locale.ROOT));
    BigDecimal saleAmount = query.saleAmount() == null ? BigDecimal.ZERO : query.saleAmount();
    BigDecimal quantity = query.quantity() == null ? BigDecimal.ZERO : query.quantity();
    String currency = blankToNull(query.currency() == null ? null : query.currency().trim().toUpperCase(Locale.ROOT));
    OffsetDateTime at = query.at() == null ? OffsetDateTime.now() : query.at();

    var candidates = rulesOfPlatform(platformCode);
    var paths = categoryPaths(
        List.of(
            query.internalCategoryId() == null ? UUID.randomUUID() : query.internalCategoryId(),
            query.platformCategoryId() == null ? UUID.randomUUID() : query.platformCategoryId()));

    var items = new ArrayList<FeeEstimateItem>();
    var total = BigDecimal.ZERO;
    boolean complete = true;
    String totalNote = "";
    for (FeeType type : FeeType.values()) {
      var matched =
          bestMatch(
              candidates, siteCode, query.internalCategoryId(), query.platformCategoryId(),
              type.name(), at, paths);
      if (matched == null) {
        complete = false;
        totalNote = "有费用类型没有生效规则，合计是不完整的";
        items.add(
            new FeeEstimateItem(
                type.name(),
                type.label(),
                null,
                null,
                null,
                PlatformFeeRules.scopeBasis(platformCode, siteCode, null, null),
                null,
                currency,
                null,
                "该平台 / 站点 / 类目下没有生效的" + type.label() + "规则",
                false,
                "没有规则 ≠ 这笔费用是 0：规则没配就等于漏算"));
        continue;
      }
      var applied =
          PlatformFeeRules.apply(
              matched.toView(), matched.tiers(), saleAmount, quantity, currency);
      if (!applied.applicable()) {
        complete = false;
        totalNote = "有费用项算不出来，合计是不完整的";
      }
      if (applied.amount() != null && applied.currency() != null) {
        if (applied.currency().equals(currency)) {
          total = total.add(applied.amount());
        } else {
          complete = false;
          totalNote = "存在与销售额不同币种的费用项（跨币种绝不相加），合计算不出来";
        }
      } else {
        complete = false;
        if (totalNote.isEmpty()) totalNote = "有费用项算不出来，合计是不完整的";
      }
      items.add(
          new FeeEstimateItem(
              matched.feeType(),
              PlatformFeeRules.feeTypeLabel(matched.feeType()),
              matched.chargeBasis().name(),
              matched.chargeBasis().label(),
              matched.id(),
              PlatformFeeRules.scopeBasis(
                  matched.platformCode(),
                  matched.siteCode(),
                  matched.internalCategoryName(),
                  matched.platformCategoryName()),
              PlatformFeeRules.text(applied.amount()),
              applied.currency(),
              PlatformFeeRules.text(applied.rate()),
              applied.basis(),
              applied.applicable(),
              applied.note()));
    }
    return new FeeEstimate(
        platformCode,
        siteCode,
        saleAmount.toPlainString(),
        quantity.toPlainString(),
        at.toString(),
        List.copyOf(items),
        complete ? PlatformFeeRules.text(PlatformFeeRules.money(total)) : null,
        currency,
        complete,
        complete
            ? "全部费用项都算得出来，合计是完整的（同一币种内相加）。"
            : totalNote + " —— **合计是不完整的**，拿去算利润会偏低。");
  }

  // ================================================================ 实际费用

  /** 实际费用台账：分页 + 平台 / 站点 / 费用类型 / 区间筛选。 */
  public FeeActualListPage listActuals(ActualQuery query) {
    int size = query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
    int page = Math.max(query.page(), 0);
    var where = new StringBuilder(" where a.tenant_id = " + TENANT);
    var binds = new ArrayList<Object>();
    if (notBlank(query.platformCode())) {
      where.append(" and a.platform_code = ?");
      binds.add(query.platformCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.siteCode())) {
      where.append(" and a.site_code = ?");
      binds.add(query.siteCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.feeType())) {
      where.append(" and a.fee_type = ?");
      binds.add(query.feeType().trim().toUpperCase(Locale.ROOT));
    }
    if (query.from() != null) {
      where.append(" and a.occurred_on >= ?");
      binds.add(query.from());
    }
    if (query.to() != null) {
      where.append(" and a.occurred_on <= ?");
      binds.add(query.to());
    }
    String from = " from platform_fee_actual a";
    long total = count("select count(*)" + from + where, binds.toArray());
    var records =
        sql.fetch(
            "select a.id, a.platform_code, a.site_code, a.internal_category_id, a.platform_category_id,"
                + " a.fee_type, a.amount, a.currency, a.sale_amount, a.quantity, a.occurred_on,"
                + " a.source, a.source_ref, a.note, a.is_void, a.void_reason, a.voided_at, a.created_at"
                + from
                + where
                + " order by a.occurred_on desc, a.created_at desc, a.id"
                + " limit "
                + size
                + " offset "
                + ((long) page * size),
            binds.toArray());
    var items = new ArrayList<FeeActualRow>();
    for (var record : records) {
      items.add(actualRow(record));
    }
    return new FeeActualListPage(
        List.copyOf(items),
        total,
        page,
        size,
        "实际费用是**事后对账口径**，与规则（事前测算口径）是两个独立的事实。"
            + "当前唯一来源是人工登记的平台账单观察值（不是 12 财务的结算对账结果 —— 12 尚未交付）；"
            + "只追加，录错了作废（要写理由）。");
  }

  /** 登记一条实际费用。 */
  public FeeActualSaved recordActual(AccessService.Access actor, FeeActualInput input, String bearer) {
    if (input.feeType() == null || input.feeType().isBlank()) {
      throw badRequest("费用类型必填");
    }
    String feeType = input.feeType().trim().toUpperCase(Locale.ROOT);
    if (PlatformFeeRules.feeTypeLabel(feeType).equals(feeType)) {
      throw badRequest("费用类型不认识：" + feeType);
    }
    String platformCode = input.platformCode().trim().toUpperCase(Locale.ROOT);
    String siteCode = blankToNull(input.siteCode() == null ? null : input.siteCode().trim().toUpperCase(Locale.ROOT));
    requireKnownScope(platformCode, siteCode, bearer);
    requireKnownCategories(platformCode, input.internalCategoryId(), input.platformCategoryId());
    var id = UUID.randomUUID();
    sql.execute(
        "insert into platform_fee_actual (tenant_id, id, platform_code, site_code, internal_category_id,"
            + " platform_category_id, fee_type, amount, currency, sale_amount, quantity, occurred_on,"
            + " source, source_ref, note, is_void, void_reason, created_by, created_at)"
            + " values ("
            + TENANT
            + ", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL_OBSERVATION', ?, ?, false, '', ?, now())",
        id,
        platformCode,
        siteCode,
        input.internalCategoryId(),
        input.platformCategoryId(),
        feeType,
        input.amount(),
        input.currency().trim().toUpperCase(Locale.ROOT),
        input.saleAmount(),
        input.quantity(),
        input.occurredOn(),
        input.sourceRef() == null ? "" : input.sourceRef().trim(),
        input.note() == null ? "" : input.note().trim(),
        actor.userId());
    return new FeeActualSaved(
        id.toString(),
        feeType,
        input.amount().toPlainString(),
        input.currency().trim().toUpperCase(Locale.ROOT),
        input.occurredOn().toString());
  }

  /** 作废一条实际费用（必须写理由）。作废不是删除：旧行留在库里，历史可追溯。 */
  public FeeActualSaved voidActual(AccessService.Access actor, UUID actualId, String reason) {
    if (reason == null || reason.isBlank()) {
      throw badRequest("作废必须写理由：没有理由的作废会让「平台费为什么变了」无法解释");
    }
    var existing =
        sql.fetchOne(
            "select fee_type, amount, currency, occurred_on, is_void"
                + " from platform_fee_actual where tenant_id = "
                + TENANT
                + " and id = ?",
            actualId);
    if (existing == null) throw notFound("实际费用不存在");
    if (Boolean.TRUE.equals(existing.get("is_void", Boolean.class))) {
      throw conflict("这条实际费用已经作废了");
    }
    sql.execute(
        "update platform_fee_actual set is_void = true, void_reason = ?, voided_by = ?, voided_at = now()"
            + " where tenant_id = "
            + TENANT
            + " and id = ?",
        reason.trim(),
        actor.userId(),
        actualId);
    return new FeeActualSaved(
        actualId.toString(),
        existing.get("fee_type", String.class),
        PlatformFeeRules.text(existing.get("amount", BigDecimal.class)),
        existing.get("currency", String.class),
        existing.get("occurred_on", LocalDate.class).toString());
  }

  // ================================================================ 费用差异

  /**
   * 费用差异对照：实际费用 vs 按规则测算。
   *
   * <p>🔴 **这是蓝图加粗句的落点**。三条纪律：
   *
   * <ol>
   *   <li>两个口径是**两个独立的事实**（规则表 / 实际费用表），差异现算、不落库 ——
   *       落库就会多出第三份可能与前两份不一致的副本。
   *   <li>**算不出来就是算不出来**：没有实际费用 ⇒ 整份报告 {@code available=false}；
   *       某笔实际费用找不到生效规则 ⇒ 该组 {@code varianceAmount=null}。
   *   <li>**跨币种绝不相加**：按（费用类型 × 币种）分组。
   * </ol>
   */
  public FeeVarianceReport variance(VarianceQuery query) {
    if (notBlank(query.platformCode()) == false) {
      throw badRequest("平台必填：费用差异是某个平台上的规则与实际的对照");
    }
    String platformCode = query.platformCode().trim().toUpperCase(Locale.ROOT);
    String siteCode = blankToNull(query.siteCode() == null ? null : query.siteCode().trim().toUpperCase(Locale.ROOT));
    LocalDate from = query.from() == null ? LocalDate.now().minusDays(30) : query.from();
    LocalDate to = query.to() == null ? LocalDate.now() : query.to();
    if (to.isBefore(from)) {
      throw badRequest("区间终点不能早于起点");
    }

    var where = new StringBuilder(
        " where a.tenant_id = " + TENANT + " and a.is_void = false and a.occurred_on >= ? and a.occurred_on <= ?");
    var binds = new ArrayList<Object>();
    binds.add(from);
    binds.add(to);
    where.append(" and a.platform_code = ?");
    binds.add(platformCode);
    if (siteCode != null) {
      where.append(" and a.site_code = ?");
      binds.add(siteCode);
    }
    if (notBlank(query.feeType())) {
      where.append(" and a.fee_type = ?");
      binds.add(query.feeType().trim().toUpperCase(Locale.ROOT));
    }
    var records =
        sql.fetch(
            "select a.id, a.platform_code, a.site_code, a.internal_category_id, a.platform_category_id,"
                + " a.fee_type, a.amount, a.currency, a.sale_amount, a.quantity, a.occurred_on"
                + " from platform_fee_actual a"
                + where
                + " order by a.occurred_on, a.id",
            binds.toArray());

    String note =
        "费用差异 = 实际费用 − 按规则测算。两个口径是两个独立的事实，差异现算不落库；"
            + "按（费用类型 × 币种）分组，跨币种绝不相加。";
    if (records.isEmpty()) {
      return new FeeVarianceReport(
          false,
          "这个区间（"
              + from
              + " ~ "
              + to
              + "）没有实际费用记录，费用差异**算不出来**。"
              + "这不是「差异为 0」—— 0 表示「平台按规则收了钱」，而这里的事实是「没有可比的实际费用」。",
          platformCode,
          siteCode,
          from.toString(),
          to.toString(),
          List.of(),
          note);
    }

    var candidates = rulesOfPlatform(platformCode);
    var targetIds = new ArrayList<UUID>();
    for (var record : records) {
      UUID internal = record.get("internal_category_id", UUID.class);
      UUID platform = record.get("platform_category_id", UUID.class);
      if (internal != null) targetIds.add(internal);
      if (platform != null) targetIds.add(platform);
    }
    var paths = categoryPaths(targetIds);

    // 按（费用类型 × 币种）分组。
    var groups = new LinkedHashMap<String, List<org.jooq.Record>>();
    for (var record : records) {
      String key = record.get("fee_type", String.class) + "|" + record.get("currency", String.class);
      groups.computeIfAbsent(key, k -> new ArrayList<>()).add(record);
    }

    var out = new ArrayList<FeeVarianceGroup>();
    for (var entry : groups.entrySet()) {
      var groupRecords = entry.getValue();
      String feeType = groupRecords.get(0).get("fee_type", String.class);
      String currency = groupRecords.get(0).get("currency", String.class);
      BigDecimal actualTotal = BigDecimal.ZERO;
      BigDecimal estimatedTotal = BigDecimal.ZERO;
      BigDecimal comparableActual = BigDecimal.ZERO;
      int comparable = 0;
      int unmatched = 0;
      String unmatchedNote = "";
      for (var record : groupRecords) {
        BigDecimal amount = record.get("amount", BigDecimal.class);
        actualTotal = actualTotal.add(amount);
        LocalDate occurredOn = record.get("occurred_on", LocalDate.class);
        UUID internal = record.get("internal_category_id", UUID.class);
        UUID platform = record.get("platform_category_id", UUID.class);
        String actualSite = record.get("site_code", String.class);
        // 对照口径：按费用发生日**当天收盘**时刻的生效规则（occurred_on 23:59:59.999999 UTC）。
        OffsetDateTime instant = occurredOn.atTime(23, 59, 59, 999_999_000).atOffset(ZoneOffset.UTC);
        var matched =
            bestMatch(
                candidates, actualSite, internal, platform, feeType, instant, paths);
        if (matched == null) {
          unmatched++;
          if (unmatchedNote.isEmpty()) {
            unmatchedNote = "有 " + unmatched + " 笔实际费用在该日期没有对应的生效规则";
          } else {
            unmatchedNote = "有 " + unmatched + " 笔实际费用在该日期没有对应的生效规则";
          }
          continue;
        }
        var applied =
            PlatformFeeRules.apply(
                matched.toView(),
                matched.tiers(),
                record.get("sale_amount", BigDecimal.class),
                record.get("quantity", BigDecimal.class),
                currency);
        if (!applied.applicable() || applied.amount() == null) {
          unmatched++;
          unmatchedNote =
              "有 "
                  + unmatched
                  + " 笔实际费用按规则算不出来（规则不适用该金额）";
          continue;
        }
        comparable++;
        comparableActual = comparableActual.add(amount);
        estimatedTotal = estimatedTotal.add(applied.amount());
      }
      String groupNote;
      BigDecimal varianceAmount;
      BigDecimal estimatedAmount;
      if (comparable == 0) {
        groupNote =
            unmatchedNote.isEmpty()
                ? "没有可对照的实际费用，差异算不出来"
                : unmatchedNote + "，差异算不出来";
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
        estimatedAmount = PlatformFeeRules.money(estimatedTotal);
      } else {
        varianceAmount = PlatformFeeRules.money(comparableActual.subtract(estimatedTotal));
        estimatedAmount = PlatformFeeRules.money(estimatedTotal);
        groupNote =
            varianceAmount.signum() == 0
                ? "实际与规则测算一致（差异 0.00）"
                : (varianceAmount.signum() > 0
                    ? "实际比规则测算多收了 " + varianceAmount.abs().toPlainString()
                    : "实际比规则测算少收了 " + varianceAmount.abs().toPlainString());
      }
      out.add(
          new FeeVarianceGroup(
              feeType,
              PlatformFeeRules.feeTypeLabel(feeType),
              currency,
              PlatformFeeRules.text(PlatformFeeRules.money(actualTotal)),
              PlatformFeeRules.text(estimatedAmount),
              PlatformFeeRules.text(varianceAmount),
              groupRecords.size(),
              comparable,
              groupNote));
    }
    return new FeeVarianceReport(
        true,
        "",
        platformCode,
        siteCode,
        from.toString(),
        to.toString(),
        List.copyOf(out),
        note);
  }

  // ================================================================ 匹配与取数（内部）

  /**
   * 在候选规则里挑出**最具体**的那一条。
   *
   * <p>口径：特异度高的赢（站点 +8 / 内部分类 +4 / 平台类目 +2）；
   * 同特异度时生效起点更晚的赢；再同则按 id 定序 —— <b>确定性的</b>，
   * 不靠数据库返回顺序。
   */
  private RuleRecord bestMatch(
      List<RuleRecord> candidates,
      String siteCode,
      UUID internalCategoryId,
      UUID platformCategoryId,
      String feeType,
      OffsetDateTime at,
      Map<UUID, UUID[]> paths) {
    RuleRecord best = null;
    for (var rule : candidates) {
      if (!rule.feeType().equals(feeType)) continue;
      if (!rule.enabled()) continue;
      if (rule.effectiveFrom().isAfter(at)) continue;
      if (rule.effectiveTo() != null && !rule.effectiveTo().isAfter(at)) continue;
      if (siteCode == null) {
        if (rule.siteCode() != null) continue;
      } else if (rule.siteCode() != null && !rule.siteCode().equals(siteCode)) {
        continue;
      }
      if (!categoryMatches(rule.internalCategoryId(), internalCategoryId, paths)) continue;
      if (!categoryMatches(rule.platformCategoryId(), platformCategoryId, paths)) continue;
      if (best == null || compare(rule, best) < 0) best = rule;
    }
    return best;
  }

  /** 规则 a 是否比 b 更具体（负数 = a 更该被选中）。 */
  private static int compare(RuleRecord a, RuleRecord b) {
    int bySpecificity = Integer.compare(b.specificity(), a.specificity());
    if (bySpecificity != 0) return bySpecificity;
    int byEffective = b.effectiveFrom().compareTo(a.effectiveFrom());
    if (byEffective != 0) return byEffective;
    return a.id().compareTo(b.id());
  }

  /**
   * 类目维度是否命中。
   *
   * <p>规则的类目命中**请求类目的整条祖先链**（含自己）：
   * 「这个类目及其子类目都按这个费率」是平台费率的常态
   * （某个类目的佣金更高），只做精确匹配会让子类目漏掉费率。
   */
  private static boolean categoryMatches(
      UUID ruleCategoryId, UUID targetCategoryId, Map<UUID, UUID[]> paths) {
    if (ruleCategoryId == null) return true;
    if (targetCategoryId == null) return false;
    var path = paths.get(targetCategoryId);
    if (path == null) return false;
    return Arrays.asList(path).contains(ruleCategoryId);
  }

  /** 取一批类目的祖先链（含自己）。 */
  private Map<UUID, UUID[]> categoryPaths(List<UUID> ids) {
    var map = new LinkedHashMap<UUID, UUID[]>();
    if (ids == null || ids.isEmpty()) return map;
    var unique = new ArrayList<UUID>();
    for (UUID id : ids) {
      if (id != null && !unique.contains(id)) unique.add(id);
    }
    if (unique.isEmpty()) return map;
    var records =
        sql.fetch(
            "select id, path_ids from product_category where tenant_id = " + TENANT
                + " and id in (" + placeholders(unique) + ")",
            unique.toArray());
    for (var record : records) {
      map.put(record.get("id", UUID.class), record.get("path_ids", UUID[].class));
    }
    return map;
  }

  /** 取某个平台下的全部规则（含阶梯）。规则的量级很小（每个平台几条到几十条）。 */
  private List<RuleRecord> rulesOfPlatform(String platformCode) {
    var records =
        sql.fetch(
            "select r.id, r.platform_code, r.site_code, r.internal_category_id, r.platform_category_id,"
                + " r.fee_type, r.charge_basis, r.rate, r.fixed_amount, r.currency,"
                + " r.period_total_amount, r.period_currency, r.allocation_basis, r.allocation_divisor,"
                + " r.effective_from, r.effective_to, r.enabled, r.note,"
                + " ic.name as internal_category_name, pc.name as platform_category_name"
                + " from platform_fee_rule r"
                + " left join product_category ic on ic.tenant_id = r.tenant_id and ic.id = r.internal_category_id"
                + " left join product_category pc on pc.tenant_id = r.tenant_id and pc.id = r.platform_category_id"
                + " where r.tenant_id = "
                + TENANT
                + " and r.platform_code = ?",
            platformCode);
    var out = new ArrayList<RuleRecord>();
    for (var record : records) {
      out.add(
          new RuleRecord(
              record.get("id", UUID.class),
              record.get("platform_code", String.class),
              record.get("site_code", String.class),
              record.get("internal_category_id", UUID.class),
              record.get("platform_category_id", UUID.class),
              record.get("fee_type", String.class),
              chargeBasisOf(record.get("charge_basis", String.class)),
              record.get("rate", BigDecimal.class),
              record.get("fixed_amount", BigDecimal.class),
              record.get("currency", String.class),
              record.get("period_total_amount", BigDecimal.class),
              record.get("period_currency", String.class),
              allocationBasisOf(record.get("allocation_basis", String.class)),
              record.get("allocation_divisor", BigDecimal.class),
              record.get("effective_from", OffsetDateTime.class),
              record.get("effective_to", OffsetDateTime.class),
              Boolean.TRUE.equals(record.get("enabled", Boolean.class)),
              record.get("note", String.class),
              record.get("internal_category_name", String.class),
              record.get("platform_category_name", String.class),
              List.of()));
    }
    // 阶梯一次性取回来（避免 N+1）。
    if (!out.isEmpty()) {
      var ids = new ArrayList<UUID>();
      for (var rule : out) ids.add(rule.id());
      var tiers =
          sql.fetch(
              "select rule_id, min_amount, max_amount, rate from platform_fee_rule_tier"
                  + " where tenant_id = "
                  + TENANT
                  + " and rule_id in ("
                  + placeholders(ids)
                  + ") order by rule_id, seq",
              ids.toArray());
      var byRule = new LinkedHashMap<UUID, List<TierView>>();
      for (var tier : tiers) {
        byRule
            .computeIfAbsent(tier.get("rule_id", UUID.class), k -> new ArrayList<>())
            .add(
                new TierView(
                    tier.get("min_amount", BigDecimal.class),
                    tier.get("max_amount", BigDecimal.class),
                    tier.get("rate", BigDecimal.class)));
      }
      var rebuilt = new ArrayList<RuleRecord>();
      for (var rule : out) {
        rebuilt.add(
            new RuleRecord(
                rule.id(), rule.platformCode(), rule.siteCode(), rule.internalCategoryId(),
                rule.platformCategoryId(), rule.feeType(), rule.chargeBasis(), rule.rate(),
                rule.fixedAmount(), rule.currency(), rule.periodTotalAmount(), rule.periodCurrency(),
                rule.allocationBasis(), rule.allocationDivisor(), rule.effectiveFrom(),
                rule.effectiveTo(), rule.enabled(), rule.note(), rule.internalCategoryName(),
                rule.platformCategoryName(), byRule.getOrDefault(rule.id(), List.of())));
      }
      out.clear();
      out.addAll(rebuilt);
    }
    return out;
  }

  /** 取一条规则的阶梯。 */
  private List<FeeTierRow> tiersOf(UUID ruleId) {
    var records =
        sql.fetch(
            "select id, seq, min_amount, max_amount, rate from platform_fee_rule_tier"
                + " where tenant_id = "
                + TENANT
                + " and rule_id = ? order by seq",
            ruleId);
    var out = new ArrayList<FeeTierRow>();
    for (var record : records) {
      out.add(
          new FeeTierRow(
              record.get("id", UUID.class),
              record.get("seq", Integer.class),
              PlatformFeeRules.text(record.get("min_amount", BigDecimal.class)),
              PlatformFeeRules.text(record.get("max_amount", BigDecimal.class)),
              PlatformFeeRules.text(record.get("rate", BigDecimal.class))));
    }
    return List.copyOf(out);
  }

  /** 校验平台 / 站点真实存在（唯一真相在 channel 的全局码表里）。 */
  private void requireKnownScope(String platformCode, String siteCode, String bearer) {
    var catalog = directory(bearer);
    for (var platform : catalog.platforms()) {
      if (!platform.code().equals(platformCode)) continue;
      if (siteCode == null) return;
      for (var site : platform.marketplaces()) {
        if (site.code().equals(siteCode)) return;
      }
      throw badRequest(
          "站点 " + siteCode + " 不在平台 " + platformCode + " 的站点清单里（渠道服务返回的站点："
              + platform.marketplaces().stream().map(PlatformFeeModels.DirectoryMarketplace::code).toList()
              + "）");
    }
    throw badRequest(
        "平台 "
            + platformCode
            + " 不在渠道服务的平台清单里（可用的平台："
            + catalog.platforms().stream().map(PlatformFeeModels.DirectoryPlatform::code).toList()
            + "）");
  }

  /** 校验类目真实存在且归属正确（内部分类 / 平台类目的树种类由 DB 触发器兜底，这里给可读原因）。 */
  private void requireKnownCategories(String platformCode, UUID internalCategoryId, UUID platformCategoryId) {
    if (internalCategoryId == null && platformCategoryId == null) return;
    var ids = new ArrayList<UUID>();
    if (internalCategoryId != null) ids.add(internalCategoryId);
    if (platformCategoryId != null) ids.add(platformCategoryId);
    var records =
        sql.fetch(
            "select c.id, c.name, t.tree_kind, t.platform_code from product_category c"
                + " join product_category_tree t on t.tenant_id = c.tenant_id and t.id = c.tree_id"
                + " where c.tenant_id = "
                + TENANT
                + " and c.id in ("
                + placeholders(ids)
                + ")",
            ids.toArray());
    var found = new LinkedHashMap<UUID, org.jooq.Record>();
    for (var record : records) found.put(record.get("id", UUID.class), record);
    if (internalCategoryId != null) {
      var record = found.get(internalCategoryId);
      if (record == null) throw badRequest("内部分类不存在");
      if (!"INTERNAL".equals(record.get("tree_kind", String.class))) {
        throw badRequest("内部分类必须来自 INTERNAL 分类树，这条是平台类目树");
      }
    }
    if (platformCategoryId != null) {
      var record = found.get(platformCategoryId);
      if (record == null) throw badRequest("平台类目不存在");
      if (!"PLATFORM".equals(record.get("tree_kind", String.class))) {
        throw badRequest("平台类目必须来自 PLATFORM 分类树，这条是内部分类树");
      }
      if (!platformCode.equals(record.get("platform_code", String.class))) {
        throw badRequest(
            "该平台类目属于平台 "
                + record.get("platform_code", String.class)
                + "，与规则声明的平台 "
                + platformCode
                + " 不一致");
      }
    }
  }

  /**
   * 同一作用域下不得有生效区间重叠的两条规则。
   *
   * <p>重叠时「用哪一条」没有答案，而服务层只会安静地挑一条（挑哪条取决于查询顺序，
   * 不可复现）—— 所以在写库前就拒绝，并说清与哪一条撞了。
   */
  private void requireNoOverlap(
      UUID ruleId,
      String platformCode,
      String siteCode,
      UUID internalCategoryId,
      UUID platformCategoryId,
      String feeType,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveTo) {
    var where = new StringBuilder(
        " where tenant_id = "
            + TENANT
            + " and platform_code = ?"
            + " and coalesce(site_code,'') = coalesce(?,'')"
            + " and coalesce(internal_category_id::text,'') = coalesce(?::text,'')"
            + " and coalesce(platform_category_id::text,'') = coalesce(?::text,'')"
            + " and fee_type = ?"
            + " and effective_from < coalesce(?::timestamptz, 'infinity'::timestamptz)"
            + " and coalesce(effective_to, 'infinity'::timestamptz) > ?::timestamptz");
    var binds = new ArrayList<Object>();
    binds.add(platformCode);
    binds.add(siteCode);
    binds.add(internalCategoryId);
    binds.add(platformCategoryId);
    binds.add(feeType);
    binds.add(effectiveTo);
    binds.add(effectiveFrom);
    if (ruleId != null) {
      where.append(" and id <> ?");
      binds.add(ruleId);
    }
    var clash =
        sql.fetchOne(
            "select id, effective_from, effective_to from platform_fee_rule" + where + " limit 1",
            binds.toArray());
    if (clash != null) {
      throw conflict(
          "同一（平台 / 站点 / 类目 × "
              + PlatformFeeRules.feeTypeLabel(feeType)
              + "）下已经有生效区间重叠的规则（"
              + clash.get("effective_from", OffsetDateTime.class)
              + " ~ "
              + (clash.get("effective_to", OffsetDateTime.class) == null
                  ? "长期有效"
                  : clash.get("effective_to", OffsetDateTime.class))
              + "）。两条规则同时生效时「用哪一条」没有答案。"
              + "请先把旧规则的生效终点设成新规则的生效起点。");
    }
  }

  // ================================================================ 行映射与小工具

  private FeeRuleRow ruleRow(org.jooq.Record record) {
    String feeType = record.get("fee_type", String.class);
    String chargeBasis = record.get("charge_basis", String.class);
    return new FeeRuleRow(
        record.get("id", UUID.class),
        record.get("platform_code", String.class),
        record.get("site_code", String.class),
        record.get("internal_category_id", UUID.class),
        record.get("internal_category_name", String.class),
        record.get("platform_category_id", UUID.class),
        record.get("platform_category_name", String.class),
        feeType,
        PlatformFeeRules.feeTypeLabel(feeType),
        chargeBasis,
        PlatformFeeRules.chargeBasisLabel(chargeBasis),
        PlatformFeeRules.text(record.get("rate", BigDecimal.class)),
        PlatformFeeRules.text(record.get("fixed_amount", BigDecimal.class)),
        record.get("currency", String.class),
        PlatformFeeRules.text(record.get("period_total_amount", BigDecimal.class)),
        record.get("period_currency", String.class),
        record.get("allocation_basis", String.class),
        PlatformFeeRules.text(record.get("allocation_divisor", BigDecimal.class)),
        record.get("effective_from", OffsetDateTime.class) == null
            ? null
            : record.get("effective_from", OffsetDateTime.class).toString(),
        record.get("effective_to", OffsetDateTime.class) == null
            ? null
            : record.get("effective_to", OffsetDateTime.class).toString(),
        Boolean.TRUE.equals(record.get("enabled", Boolean.class)),
        record.get("note", String.class),
        record.get("version", Long.class) == null ? 0L : record.get("version", Long.class),
        record.get("tier_count", Integer.class) == null ? 0 : record.get("tier_count", Integer.class),
        record.get("created_at", OffsetDateTime.class) == null
            ? null
            : record.get("created_at", OffsetDateTime.class).toString(),
        record.get("updated_at", OffsetDateTime.class) == null
            ? null
            : record.get("updated_at", OffsetDateTime.class).toString());
  }

  private FeeActualRow actualRow(org.jooq.Record record) {
    String feeType = record.get("fee_type", String.class);
    String source = record.get("source", String.class);
    return new FeeActualRow(
        record.get("id", UUID.class),
        record.get("platform_code", String.class),
        record.get("site_code", String.class),
        record.get("internal_category_id", UUID.class),
        record.get("platform_category_id", UUID.class),
        feeType,
        PlatformFeeRules.feeTypeLabel(feeType),
        PlatformFeeRules.text(record.get("amount", BigDecimal.class)),
        record.get("currency", String.class),
        PlatformFeeRules.text(record.get("sale_amount", BigDecimal.class)),
        PlatformFeeRules.text(record.get("quantity", BigDecimal.class)),
        record.get("occurred_on", LocalDate.class) == null
            ? null
            : record.get("occurred_on", LocalDate.class).toString(),
        source,
        PlatformFeeRules.actualSourceLabel(source),
        record.get("source_ref", String.class),
        record.get("note", String.class),
        Boolean.TRUE.equals(record.get("is_void", Boolean.class)),
        record.get("void_reason", String.class),
        record.get("voided_at", OffsetDateTime.class) == null
            ? null
            : record.get("voided_at", OffsetDateTime.class).toString(),
        record.get("created_at", OffsetDateTime.class) == null
            ? null
            : record.get("created_at", OffsetDateTime.class).toString());
  }

  private long count(String sqlText, Object... params) {
    return sql.fetchOne(sqlText, params).get(0, Long.class);
  }

  /**
   * 拼出一串 {@code ?,?,?} 占位符。
   *
   * <p>不用 {@code = any(?::uuid[])}：jOOQ 把 {@code UUID[]} 绑成**一个** uuid 参数（而不是 uuid[]），
   * 整条语句会在 prepare 阶段报 bad SQL grammar。与 {@link BrandService} / {@link CategoryTreeService}
   * 同一做法。调用方必须先判空。
   */
  private static String placeholders(List<UUID> ids) {
    return String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
  }

  private static ChargeBasis chargeBasisOf(String value) {
    if (value == null) return null;
    for (ChargeBasis basis : ChargeBasis.values()) {
      if (basis.name().equals(value)) return basis;
    }
    return null;
  }

  private static AllocationBasis allocationBasisOf(String value) {
    if (value == null) return null;
    for (AllocationBasis basis : AllocationBasis.values()) {
      if (basis.name().equals(value)) return basis;
    }
    return null;
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
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

  /** 规则排序（台账用）：先按平台，再按费用类型，再按生效起点。 */
  static Comparator<FeeRuleRow> ruleOrder() {
    return Comparator.comparing(FeeRuleRow::platformCode)
        .thenComparing(FeeRuleRow::feeType)
        .thenComparing(FeeRuleRow::effectiveFrom, Comparator.nullsLast(Comparator.naturalOrder()));
  }
}
