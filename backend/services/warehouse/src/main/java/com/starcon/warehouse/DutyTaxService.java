package com.starcon.warehouse;

import static com.starcon.warehouse.DutyTaxModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 10.5.1.3 关税与税费。
 *
 * <p>蓝图：「计算与管理进口税费：关税（按 HS 编码税率）、增值税/GST、关税承担方（DDP/DDU）、
 * 到岸成本构成，供成本核算与报价参考」。输入是「HS 编码税率表；目的国税则；申报价值；贸易条款」，
 * 输出是「税费估算 + 实际缴纳记录；到岸成本明细」。
 *
 * <p>七条实现纪律：
 *
 * <ol>
 *   <li><b>估算与实缴分别留痕，这是蓝图加粗的硬要求</b>：{@code DutyTaxEstimate}（按税率表算出的
 *       「应该有」）、{@code DutyTaxPayment}（海关实收，append-only）、{@code DutyTaxVariance}（差额 +
 *       归因 + 复核痕迹）三张表互相不覆盖。DB 触发器 {@code duty_payment_has_variance} 钉死
 *       「有实缴就必须有差异记录」，绕过服务层直接写 SQL 同样被挡 ——
 *       蓝图原文「**税费估算与实际缴纳差异要记录**，长期差异大说明 HS 归类或税率配置有误」。
 *   <li><b>差异是逐次对账的不可变快照</b>：实缴逐次发生，差异也逐次对出来 ——
 *       第 1 次缴款留下「对到第 1 次为止差多少」，第 2 次再留一条新的。
 *       所以归因在建差异那一刻就写进去（由缴款请求携带），**不提供事后改归因的端点**：
 *       已归因的差异是纠错闭环的证据，要重新判定就再录一笔缴款对账出新的快照。
 *   <li><b>税率是按生效期检索的落库数据</b>：税则会变，代码常量记税率等于「改税率就改了历史」。
 *       每条税率带 {@code effective_from/effective_to}，估算明细把命中的税率值<b>抄进自己的行</b>，
 *       所以税率表那条将来被改，本行仍能回答「当时按 3.25% 算的」。
 *   <li><b>0% 税率合法，「没匹配到税率」不合法</b>：英国零税率、免税供货都是真实 0% 配置；
 *       但带 {@code NO_MATCH} 关税行或未配 VAT 税率的行<b>锁不了</b>估算单
 *       （{@code duty_estimate_lock_completeness}）—— 否则「关税 0 元」会直接进报价与成本核算。
 *   <li><b>DDP/DDU 改变成本归属，不改变税率</b>：DDU 下卖方到岸成本不含关税与目的国税费
 *       （{@code duty_landed_cost} 钉死）。关税由谁付不影响税率是多少。
 *   <li><b>逐行算税，不合并成一个总税率</b>：不同 HS 编码税率不同，合并算出来的「总关税」
 *       既无法归因也无法纠错。蓝图那句「差异大说明 HS 归类或税率配置有误」要能落到
 *       <b>具体哪一行</b>，就必须是逐行的。
 *   <li><b>未交付依赖显式声明，不建假上游</b>：3.7.1.1（HS 编码字典）与 16.3.1.1（税号与辖区/税则）
 *       在本项目里**没有可调用的上游**（product_compliance 服务不存在，台账记为阻塞），
 *       所以税率由本租户维护并用 {@code taxSource} 如实标注来源；
 *       外部海关缴款接口同样未交付，缴款由人工回填并以 {@code sourceSystem='MANUAL_ENTRY'} 标注。
 *   <li><b>多租户不可省</b>：所有 SQL 走 RLS，租户由 backend/libraries/tenancy 在事务内设置，
 *       客户端伪造 {@code X-Tenant-Id} 进不来也改不动它。
 * </ol>
 *
 * <p>状态机只有一处真相：{@link EstimateStatus#allowedNext}。
 */
@Service
public class DutyTaxService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  /**
   * 结算口径：金额一律 HALF_UP 到<b>分</b>。
   *
   * <p>海关把关税与进口增值税报到分，所以这一层必须在**算的时候**定死而不是等展示时再定 ——
   * 「算出来 32.505，展示成 32.51」意味着账上与票上差半分钱，而差的这半分永远对不上。
   * DB 侧用 {@code round(x,2)} 表达同一口径，延迟约束触发器在提交时复核，两侧漂移即报错。
   */
  static final int MONEY_SCALE = 2;

  private static final List<String> ESTIMATE_SORTS =
      List.of("UPDATED_AT", "CREATED_AT", "ESTIMATED_TAX", "LANDED_COST", "ESTIMATE_CODE");

  private static final List<String> RATE_SORTS =
      List.of("CREATED_AT", "EFFECTIVE_FROM", "AD_VALOREM_RATE", "DESTINATION_COUNTRY", "HS_CODE_PREFIX");

  private static final BigDecimal ZERO6 = BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
  private static final BigDecimal ZERO4 = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
  private static final BigDecimal ZERO2 = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

  private final DSLContext sql;

  public DutyTaxService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力

  /**
   * 关税与税费能力清单：税种、承担方、贸易条款、状态机、归因词表、**未交付依赖逐条声明**
   * 与四条必须讲清的口径。
   *
   * <p>本车道对集成头实际查证的结论：3.7.1.1（HS 编码字典）与 16.3.1.1（税号与辖区/税则）被
   * product_compliance 服务缺失阻塞，未交付；外部海关缴款/税则接口在本项目里没有可调用的系统
   * 与凭证，未交付。两者都显式 {@code available=false} + 原因 + {@code ownerModule} + {@code href}，
   * <b>不建占位表、不返回空集冒充成功</b>。
   */
  public DutyTaxCapabilities capabilities() {
    List<DutyTaxDimension> dependencies =
        List.of(
            new DutyTaxDimension(
                "HS_CODE_DICTIONARY",
                "HS 编码字典",
                false,
                "3.7.1.1",
                "/products/hs-codes",
                "3.7.1.1 HS 编码与申报要素未交付（台账记为阻塞：product_compliance 复制自 "
                    + "product_research，装不了 HS 编码）。因此本模块的 hsCode 接受自由码，"
                    + "**没有**编码字典可校验 —— 不假装有字典，也不给一个点下去没反应的选择器。"
                    + "缺 HS 码的行会被禁止锁定，因为蓝图要求关税「按 HS 编码税率」算。"),
            new DutyTaxDimension(
                "TARIFF_TAX_BASIS",
                "税则与税号辖区",
                false,
                "16.3.1.1",
                "/compliance/tax-jurisdictions",
                "16.3.1.1 税号与辖区未交付（台账为未开发），本项目里也没有可调用的国家海关官方税则接口。"
                    + "所以税率表由**本租户维护**并用 taxSource 如实标注「这条是人工查来的还是合规模块给的」，"
                    + "将来 16.3.1.1 交付后由它写 taxSource='MODULE_16_3_1_1'，结构不变。"),
            new DutyTaxDimension(
                "VAT_IOSS_REGISTRATION",
                "VAT 税号注册与 IOSS",
                false,
                "16.3.2.1",
                "/compliance/vat-ioss",
                "16.3.2.1 VAT/IOSS 未交付。本模块只负责**按目的国税则算进口增值税**并留痕，"
                    + "不管理 IOSS 注册与递延申报 —— 那是另一件事，混在一起会让「算出来的税」"
                    + "与「能不能申报」互相污染。"),
            new DutyTaxDimension(
                "CUSTOMS_PAYMENT_API",
                "海关缴款接口",
                false,
                "10.5.1.3",
                "/logistics/duty-taxes",
                "外部海关缴款接口未交付：本项目里没有可调用的海关系统与凭证，"
                    + "所以实缴记录与缴款凭证由人工回填，并以 sourceSystem='MANUAL_ENTRY' "
                    + "如实标注「这条是人抄进来的，不是机器对接回来的」。"
                    + "将来接了真实接口由它写 CUSTOMS_FEED，结构不变。"));

    List<DutyTaxKindOption> kinds = new ArrayList<>();
    for (var k : TaxKind.values()) {
      kinds.add(new DutyTaxKindOption(k.name(), k.label()));
    }
    List<DutyTaxBearerOption> bearers = new ArrayList<>();
    for (var b : DutyBearer.values()) {
      bearers.add(new DutyTaxBearerOption(b.name(), b.label()));
    }
    List<DutyTaxIncotermOption> incoterms = new ArrayList<>();
    for (var i : Incoterm.values()) {
      incoterms.add(new DutyTaxIncotermOption(i.name(), i.label()));
    }
    List<DutyTaxCauseOption> causes = new ArrayList<>();
    for (var c : VarianceCause.values()) {
      causes.add(new DutyTaxCauseOption(c.name(), c.label()));
    }
    List<DutyTaxResolutionOption> resolutions = new ArrayList<>();
    for (var r : VarianceResolution.values()) {
      resolutions.add(new DutyTaxResolutionOption(r.name(), r.label()));
    }
    List<DutyTaxMatchStatusOption> matchStatuses = new ArrayList<>();
    for (var m : RateMatchStatus.values()) {
      matchStatuses.add(new DutyTaxMatchStatusOption(m.name(), m.label()));
    }
    List<ScopeOption> scopes = new ArrayList<>();
    for (var s : RateScope.values()) {
      scopes.add(new ScopeOption(s.name(), s.label()));
    }

    return new DutyTaxCapabilities(
        dependencies,
        transitions(),
        statusOptions(),
        kinds,
        bearers,
        incoterms,
        causes,
        resolutions,
        matchStatuses,
        scopes,
        "估算与实缴分别留痕，这是蓝图的硬要求：蓝图原文「税费估算与实际缴纳差异要记录，"
            + "长期差异大说明 HS 归类或税率配置有误」。所以估算（estimate）、实缴（payment）、"
            + "差异（variance）三张表互不覆盖，而且**有实缴就必须有差异记录**由 DB 触发器钉死 —— "
            + "只存最终一个数的话，差异永远无法被回答，「HS 归类错了」这个结论也就没有证据。"
            + "实缴与差异都是 append-only：缴错了走新增退汇/调整行，不改旧行；"
            + "差异归因在建差异那一刻写入，不提供事后改归因 —— 已归因的差异是纠错闭环的证据。",
        "税率按生效期检索，不是代码常量：税则会变，用常量记税率等于「改了税率就改了历史」。"
            + "每条税率带生效/失效日期，估算明细把命中的税率值抄进自己的行里，"
            + "所以税率表那条将来被改或失效，本行仍能回答「当时按 3.25% 算的」。"
            + "同一目的国同一适用范围/HS 前缀同一税种在同一生效日只能有一条税率，否则算出来的账不可复现。",
        "0% 税率是合法配置（英国零税率商品、免税供货），但「没匹配到税率」不合法："
            + "带未匹配关税行、或没配到目的国增值税税率的行**锁不了**这张估算单 —— "
            + "按 0 税锁定的估算单会让报价与到岸成本少算税，而这正是「HS 归类或税率配置有误」"
            + "在账上最常见的表现形态。零税率国家配一条 0% 的国别税率行即可。",
        "DDP/DDU 改变的是成本归属，不是税率：DDU 下关税与目的国税费由买方承担，"
            + "卖方到岸成本**不含**它们（DB 触发器钉死）。关税由谁付不影响税率是多少 —— "
            + "把 DDU 的税算进卖方成本，就是在给一个不属于自己的成本记账。",
        "到岸成本是报价依据，所以汇率与精度刻度是快照：汇率、刻度、汇率日期一起落库，"
            + "运行时**不**去取当前汇率。事后复盘要能回答「当时按哪个汇率算的」。"
            + "估算单锁定之后连承担方都不许改 —— 改了就等于把 DDP 的成本挪成 DDU 的成本，"
            + "而已发出的报价失去了依据。");
  }

  private static List<DutyTaxStatusOption> statusOptions() {
    List<DutyTaxStatusOption> out = new ArrayList<>();
    for (var s : EstimateStatus.values()) {
      out.add(new DutyTaxStatusOption(s.name(), s.label()));
    }
    return out;
  }

  /** 状态机的合法转换，前端据此只给合法的按钮。 */
  public List<DutyTaxTransition> transitions() {
    List<DutyTaxTransition> out = new ArrayList<>();
    for (var from : EstimateStatus.values()) {
      for (var to : EstimateStatus.allowedNext(from)) {
        out.add(
            new DutyTaxTransition(
                from.name(),
                from.label(),
                to.name(),
                to.label(),
                actionOf(from, to),
                noteOf(from, to)));
      }
    }
    return out;
  }

  private static String actionOf(EstimateStatus from, EstimateStatus to) {
    if (from == to) {
      return "UPDATE";
    }
    return switch (to) {
      case ESTIMATED -> "CALCULATE";
      case LOCKED -> "LOCK";
      case VOID -> "VOID";
      case DRAFT -> "REVISE";
    };
  }

  private static String noteOf(EstimateStatus from, EstimateStatus to) {
    if (from == to) {
      return "改基础金额与承担方（锁定后不可改）";
    }
    return switch (to) {
      case ESTIMATED -> "按税率表逐行重算关税与增值税/GST";
      case LOCKED -> "锁定为对外报价/成本依据：之后只读，且必须所有行都配到税率";
      case VOID -> "作废终态：之后不能再录缴款与差异";
      case DRAFT -> "退回草稿改基础（改完要重新算）";
    };
  }

  // ================================================================ 税率表

  /**
   * 税则税率表分页。税率是**真实落库的数据**，检索按目的国 + 税种 + 生效日，不走任何代码常量。
   *
   * @param effectiveOn 按哪一天取当时生效的税率，null 表示不过滤生效期
   */
  public List<DutyRateRow> rates(UUID tenantId, DutyRateQuery query) {
    StringBuilder where = new StringBuilder(" where tenant_id=?");
    List<Object> args = new ArrayList<>();
    args.add(tenantId);
    if (notBlank(query.destinationCountryCode())) {
      where.append(" and destination_country_code=?");
      args.add(query.destinationCountryCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.taxKind())) {
      where.append(" and tax_kind=?");
      args.add(TaxKind.parse(query.taxKind()).name());
    }
    if (notBlank(query.rateScope())) {
      where.append(" and rate_scope=?");
      args.add(rateScope(query.rateScope()).name());
    }
    if (notBlank(query.hsCodePrefix())) {
      where.append(" and hs_code_prefix=?");
      args.add(query.hsCodePrefix().trim());
    }
    if (Boolean.TRUE.equals(query.preferentialOnly())) {
      where.append(" and preferential");
    }
    if (query.effectiveOn() != null) {
      // 「当时生效」：生效日 ≤ 当日 ≤ 失效日（失效日可空表示长期有效）
      where.append(" and effective_from<=? and (effective_to is null or effective_to>=?)");
      args.add(query.effectiveOn());
      args.add(query.effectiveOn());
    }
    if (notBlank(query.keyword())) {
      where.append(" and (hs_code_prefix ilike ? or treaty_code ilike ? or source_reference ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      args.add(like);
      args.add(like);
      args.add(like);
    }
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    return sql.fetch(
            "select * from duty_tax_rate" + where + " order by " + rateSortOf(query.sort())
                + " limit ? offset ?",
            concat(args, size, pageNo * size))
        .stream()
        .map(DutyTaxService::rate)
        .toList();
  }

  @Transactional
  public DutyRateSaved createRate(UUID tenantId, DutyRateCreateRequest request) {
    RateScope scope = rateScope(request.rateScope());
    String country = requireCountry(request.destinationCountryCode(), "目的国/地区");
    TaxKind kind = TaxKind.parse(request.taxKind());
    String prefix = normalizePrefix(scope, request.hsCodePrefix());
    BigDecimal adValorem = nonNegative(request.adValoremRate(), "从价税率");
    BigDecimal specific =
        request.specificRatePerUnit() == null ? ZERO4 : nonNegative(request.specificRatePerUnit(), "从量税率");
    boolean preferential = Boolean.TRUE.equals(request.preferential());
    String treaty = fit(request.treatyCode(), 32, "协定代码");
    if (preferential && treaty.isEmpty()) {
      throw new IllegalArgumentException("优惠税率必须给协定代码：没有依据的「优惠」就是错税率");
    }
    LocalDate from = request.effectiveFrom() == null ? LocalDate.now() : request.effectiveFrom();
    LocalDate to = request.effectiveTo();
    if (to != null && to.isBefore(from)) {
      throw new IllegalArgumentException("失效日期不能早于生效日期");
    }
    // 来源必填：税率必须能追到出处（哪份税则/公告查来的）。
    // 没有出处的税率无法复核，而税率算错是要按海关核定补税的。
    String source = fit(request.sourceReference(), 64, "税率出处");
    if (source.isEmpty()) {
      throw new IllegalArgumentException("必须填税率出处（哪份税则/公告查来的）：没有出处的税率无法复核");
    }
    // taxSource 只能取两个合法值：人工查来的，或 16.3.1.1 交付后由它写。
    String taxSource = taxSource(request.taxSource());
    String sourceSystem = notBlank(request.sourceSystem())
        ? requireOneOf(request.sourceSystem(), "录入方式", "MANUAL_ENTRY", "TARIFF_FEED", "COMPLIANCE_API")
        : "MANUAL_ENTRY";
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into duty_tax_rate(id,tenant_id,destination_country_code,rate_scope,hs_code_prefix,tax_kind,"
            + "ad_valorem_rate,specific_rate_per_unit,specific_uom,preferential,treaty_code,effective_from,"
            + "effective_to,tax_source,tax_rate_verified,source_system,source_reference,notes,created_by)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        tenantId,
        country,
        scope.name(),
        prefix,
        kind.name(),
        adValorem.setScale(6, RoundingMode.HALF_UP),
        specific.setScale(4, RoundingMode.HALF_UP),
        uom(request.specificUom()),
        preferential,
        treaty,
        from,
        to,
        taxSource,
        Boolean.TRUE.equals(request.taxRateVerified()),
        sourceSystem,
        source,
        fit(request.notes(), 2000, "备注"),
        currentActor());
    return new DutyRateSaved(
        id, "ACTIVE", "税率已录入。估算时按目的国 + 税率适用范围 + 生效日取当时生效的税率。");
  }

  @Transactional
  public DutyRateSaved updateRate(UUID tenantId, UUID rateId, DutyRateUpdateRequest request) {
    Record rate = requireRate(tenantId, rateId);
    if (request.expectedVersion() != null
        && request.expectedVersion() != rate.get("version", Long.class)) {
      throw new IllegalStateException(
          "税率已被他人修改（期望版本 "
              + request.expectedVersion()
              + "，当前版本 "
              + rate.get("version", Long.class)
              + "），请刷新后重试");
    }
    BigDecimal adValorem =
        request.adValoremRate() == null
            ? nonNegative(rate.get("ad_valorem_rate", BigDecimal.class), "从价税率")
            : nonNegative(request.adValoremRate(), "从价税率");
    BigDecimal specific =
        request.specificRatePerUnit() == null
            ? nonNegative(rate.get("specific_rate_per_unit", BigDecimal.class), "从量税率")
            : nonNegative(request.specificRatePerUnit(), "从量税率");
    boolean preferential =
        request.preferential() == null
            ? Boolean.TRUE.equals(rate.get("preferential", Boolean.class))
            : request.preferential();
    String treaty =
        request.treatyCode() == null
            ? rate.get("treaty_code", String.class)
            : fit(request.treatyCode(), 32, "协定代码");
    if (preferential && treaty.isEmpty()) {
      throw new IllegalArgumentException("优惠税率必须给协定代码：没有依据的「优惠」就是错税率");
    }
    LocalDate from = rate.get("effective_from", LocalDate.class);
    LocalDate to =
        request.effectiveTo() == null ? rate.get("effective_to", LocalDate.class) : request.effectiveTo();
    if (to != null && to.isBefore(from)) {
      throw new IllegalArgumentException("失效日期不能早于生效日期");
    }
    String source =
        request.sourceReference() == null
            ? rate.get("source_reference", String.class)
            : fit(request.sourceReference(), 64, "税率出处");
    if (source.isEmpty()) {
      throw new IllegalArgumentException("必须填税率出处：没有出处的税率无法复核");
    }
    sql.execute(
        "update duty_tax_rate set ad_valorem_rate=?,specific_rate_per_unit=?,specific_uom=?,"
            + "preferential=?,treaty_code=?,effective_to=?,tax_rate_verified=?,source_reference=?,"
            + "notes=?,version=version+1,updated_at=now() where id=?",
        adValorem.setScale(6, RoundingMode.HALF_UP),
        specific.setScale(4, RoundingMode.HALF_UP),
        request.specificUom() == null ? rate.get("specific_uom", String.class) : uom(request.specificUom()),
        preferential,
        treaty,
        to,
        request.taxRateVerified() == null
            ? Boolean.TRUE.equals(rate.get("tax_rate_verified", Boolean.class))
            : request.taxRateVerified(),
        source,
        request.notes() == null ? rate.get("notes", String.class) : fit(request.notes(), 2000, "备注"),
        rateId);
    return new DutyRateSaved(
        rateId,
        "ACTIVE",
        "税率已更新。注意：已算出的估算单明细行里**抄着自己当时用的税率值**，不受本次改动影响 —— "
            + "那正是「当时按 3.25% 算的」能被回答的原因。");
  }

  // ================================================================ 估算单

  /** 估算单列表 + 全量汇总（**合计不受分页影响**）。 */
  public DutyEstimatePage page(UUID tenantId, DutyEstimateQuery query) {
    String where = estimateWhere(tenantId, query);
    List<Object> args = estimateArgs(query);
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    var rows =
        sql.fetch(
            "select e.*,"
                + " (select coalesce(sum(p.paid_amount),0) from duty_tax_payment p"
                + "   where p.tenant_id=e.tenant_id and p.estimate_id=e.id and p.tax_kind='DUTY') paid_duty,"
                + " (select coalesce(sum(p.paid_amount),0) from duty_tax_payment p"
                + "   where p.tenant_id=e.tenant_id and p.estimate_id=e.id and p.tax_kind<>'DUTY') paid_vat,"
                + " (select coalesce(sum(v.variance_amount),0) from duty_tax_variance v"
                + "   join duty_tax_payment p2 on p2.tenant_id=v.tenant_id and p2.estimate_id=v.estimate_id"
                + "     and p2.tax_kind=v.tax_kind and p2.payment_sequence=v.payment_sequence"
                + "   where v.tenant_id=e.tenant_id and v.estimate_id=e.id) variance_total,"
                + " (select count(*) from duty_tax_estimate_line l where l.tenant_id=e.tenant_id"
                + "   and l.estimate_id=e.id and (l.rate_match_status<>'MATCHED' or not l.vat_rate_matched)) unmatched"
                + " from duty_tax_estimate e" + where + " order by " + estimateSortOf(query.sort())
                + " limit ? offset ?",
            concat(args, size, pageNo * size));
    // 总数与汇总都按**同一组 where**算，不受分页影响：报价与成本核算要的是全量口径。
    long total =
        sql.fetchOne("select count(*) c from duty_tax_estimate e" + where, args.toArray(new Object[0]))
            .get("c", Long.class);
    Record sums =
        sql.fetchOne(
            "select count(*) filter (where e.status='DRAFT') draft,"
                + " count(*) filter (where e.status='ESTIMATED') estimated,"
                + " count(*) filter (where e.status='LOCKED') locked,"
                + " count(*) filter (where e.status='VOID') voided,"
                + " coalesce(sum(e.estimated_total_tax),0) tax_total"
                + " from duty_tax_estimate e" + where,
            args.toArray(new Object[0]));
    // LEFT JOIN 必须写在 WHERE 之前：把 variance 的 join 接到 where 后面是语法错误。
    // 只取**最新一次对账快照**（append-only，历史快照不重复计入）。
    String paidJoin =
        " left join duty_tax_variance v on v.tenant_id=p.tenant_id and v.estimate_id=p.estimate_id"
            + " and v.tax_kind=p.tax_kind and v.payment_sequence=p.payment_sequence";
    String paidWhere =
        where
            + " and p.payment_sequence=(select max(p3.payment_sequence) from duty_tax_payment p3"
            + "   where p3.tenant_id=p.tenant_id and p3.estimate_id=p.estimate_id and p3.tax_kind=p.tax_kind)";
    Record paid =
        sql.fetchOne(
            "select coalesce(sum(p.paid_amount),0) paid,"
                + " count(*) filter (where v.id is not null and v.resolution='OPEN') open_variance"
                + " from duty_tax_payment p join duty_tax_estimate e on e.tenant_id=p.tenant_id"
                + " and e.id=p.estimate_id"
                + paidJoin
                + paidWhere,
            args.toArray(new Object[0]));
    int unmatched =
        sql.fetchOne(
                "select count(*) c from duty_tax_estimate_line l where l.tenant_id=?"
                    + " and (l.rate_match_status<>'MATCHED' or not l.vat_rate_matched)"
                    + " and l.estimate_id in (select e.id from duty_tax_estimate e" + where + ")",
                prepend(tenantId, args))
            .get("c", Integer.class);
    BigDecimal taxTotal = sums.get("tax_total", BigDecimal.class);
    BigDecimal paidTotal = paid.get("paid", BigDecimal.class);
    int openVariance = paid.get("open_variance", Integer.class) == null ? 0 : paid.get("open_variance", Integer.class);
    return new DutyEstimatePage(
        rows.stream().map(DutyTaxService::estimate).toList(),
        new DutyEstimateSummary(
            (int) total,
            sums.get("draft", Integer.class),
            sums.get("estimated", Integer.class),
            sums.get("locked", Integer.class),
            sums.get("voided", Integer.class),
            taxTotal,
            paidTotal,
            paidTotal.subtract(taxTotal),
            unmatched,
            openVariance),
        pageNo,
        size,
        (int) Math.max(1, (total + size - 1) / size));
  }

  private String estimateWhere(UUID tenantId, DutyEstimateQuery query) {
    StringBuilder where = new StringBuilder(" where e.tenant_id=?");
    if (query.status() != null && !query.status().isEmpty()) {
      List<String> parsed = new ArrayList<>();
      for (var s : query.status()) {
        parsed.add(EstimateStatus.parse(s).name());
      }
      where.append(" and e.status in (" + placeholders(parsed.size()) + ")");
    }
    if (notBlank(query.destinationCountryCode())) {
      where.append(" and e.destination_country_code=?");
    }
    if (notBlank(query.dutyBearer())) {
      where.append(" and e.duty_bearer=?");
    }
    if (notBlank(query.incoterm())) {
      where.append(" and e.incoterm=?");
    }
    if (query.declarationId() != null) {
      where.append(" and e.declaration_id=?");
    }
    if (Boolean.TRUE.equals(query.unmatchedRateOnly())) {
      // 「没配到税率」的估算单必须能被一眼筛出来：它们是最容易把错价发出去的那批。
      where.append(" and exists (select 1 from duty_tax_estimate_line l where l.tenant_id=e.tenant_id"
          + " and l.estimate_id=e.id and (l.rate_match_status<>'MATCHED' or not l.vat_rate_matched))");
    }
    if (Boolean.TRUE.equals(query.varianceOnly())) {
      where.append(" and exists (select 1 from duty_tax_variance v where v.tenant_id=e.tenant_id"
          + " and v.estimate_id=e.id and v.variance_amount<>0)");
    }
    if (notBlank(query.keyword())) {
      where.append(" and (e.estimate_code ilike ? or e.declaration_code ilike ?)");
    }
    return where.toString();
  }

  private List<Object> estimateArgs(DutyEstimateQuery query) {
    List<Object> args = new ArrayList<>();
    args.add(currentTenant());
    if (query.status() != null && !query.status().isEmpty()) {
      for (var s : query.status()) {
        args.add(EstimateStatus.parse(s).name());
      }
    }
    if (notBlank(query.destinationCountryCode())) {
      args.add(query.destinationCountryCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.dutyBearer())) {
      args.add(DutyBearer.parse(query.dutyBearer()).name());
    }
    if (notBlank(query.incoterm())) {
      args.add(Incoterm.parse(query.incoterm()).name());
    }
    if (query.declarationId() != null) {
      args.add(query.declarationId());
    }
    if (notBlank(query.keyword())) {
      String like = "%" + query.keyword().trim() + "%";
      args.add(like);
      args.add(like);
    }
    return args;
  }

  /**
   * 估算单详情：头 + 逐行明细 + 实缴 + 差异 + 状态机下一步 + 口径说明。
   *
   * <p>跨租户在 RLS 下查不到 → 404（403 会泄露「这张单存在」）。
   */
  public DutyEstimateDetail detail(UUID tenantId, UUID estimateId) {
    Record head = requireEstimate(tenantId, estimateId);
    EstimateStatus status = EstimateStatus.parse(head.get("status", String.class));
    BigDecimal paidDuty = paidTotal(tenantId, estimateId, "= 'DUTY'");
    BigDecimal paidVat = paidTotal(tenantId, estimateId, "<> 'DUTY'");
    BigDecimal varianceTotal = varianceTotal(tenantId, estimateId);
    int unmatched = unmatchedCount(tenantId, estimateId);
    return new DutyEstimateDetail(
        estimateOf(head, paidDuty, paidVat, paidDuty.add(paidVat), varianceTotal, unmatched),
        lines(tenantId, estimateId),
        payments(tenantId, estimateId),
        variances(tenantId, estimateId),
        transitionsFor(status),
        "到岸成本 = 货值 + 运费 + 保险 + 其他 + 关税 + 增值税/GST（"
            + (status == EstimateStatus.VOID
                ? "估算已作废"
                : Incoterm.parse(head.get("incoterm", String.class)).name().equals("DDU")
                    ? "DDU 下关税与目的国税费由买方承担，卖方到岸成本不含关税与税费"
                    : "卖方到岸成本含全部税费")
            + "）。估算与实缴分别留痕，差异见差异区 —— 蓝图要求差异必须记录。");
  }

  public List<DutyEstimateLineRow> lines(UUID tenantId, UUID estimateId) {
    return sql.fetch(
            "select * from duty_tax_estimate_line where tenant_id=? and estimate_id=? order by line_no",
            tenantId,
            estimateId)
        .stream()
        .map(DutyTaxService::estimateLine)
        .toList();
  }

  public List<DutyPaymentRow> payments(UUID tenantId, UUID estimateId) {
    return sql.fetch(
            "select * from duty_tax_payment where tenant_id=? and estimate_id=?"
                + " order by tax_kind, payment_sequence, paid_at",
            tenantId,
            estimateId)
        .stream()
        .map(DutyTaxService::payment)
        .toList();
  }

  public List<DutyVarianceRow> variances(UUID tenantId, UUID estimateId) {
    return sql.fetch(
            "select * from duty_tax_variance where tenant_id=? and estimate_id=?"
                + " order by tax_kind, payment_sequence",
            tenantId,
            estimateId)
        .stream()
        .map(DutyTaxService::variance)
        .toList();
  }

  /**
   * 建估算单：取报关单的申报价值快照 → 逐行匹配税率 → 算关税与增值税/GST → 算到岸成本。
   *
   * <p>关税税率按（目的国 + HS 编码<b>最长前缀</b> + 生效日）取<b>当时</b>的税率；
   * 取不到就落 {@code NO_MATCH} 而不是按 0 税静默算过去 —— 这一行会被禁止锁定，
   * 必须先补税率或修 HS 归类。
   */
  @Transactional
  public DutySaved create(UUID tenantId, DutyEstimateCreateRequest request) {
    if (request.declarationId() == null) {
      throw new IllegalArgumentException("必须给出报关单 id：税费估算挂在报关单上，不能凭空建");
    }
    Record declaration =
        sql.fetchOne(
            "select * from customs_declaration where tenant_id=? and id=?",
            tenantId,
            request.declarationId());
    if (declaration == null) {
      throw new IllegalArgumentException("报关单不存在或无权访问：" + request.declarationId());
    }
    if ("CANCELLED".equals(declaration.get("status", String.class))) {
      throw new IllegalArgumentException("报关单已取消，不能对它估算税费");
    }
    List<Record> declLines =
        sql.fetch(
            "select * from customs_declaration_line where tenant_id=? and declaration_id=? order by line_no",
            tenantId,
            request.declarationId());
    if (declLines.isEmpty()) {
      throw new IllegalArgumentException("报关单没有明细行，无法逐行算税：没有明细就没有按 HS 编码算税的依据");
    }

    String incoterm = Incoterm.parse(request.incoterm() == null ? "DDU" : request.incoterm()).name();
    DutyBearer bearer = dutyBearerOf(request.dutyBearer(), incoterm);
    String country =
        requireCountry(
            firstNonBlank(request.destinationCountryCode(), declaration.get("destination_country_code", String.class)),
            "目的国/地区");
    String currency =
        moneyOrDefault(
            request.estimateCurrency(), declaration.get("declaration_currency", String.class), "计价币种");
    String base =
        moneyOrDefault(request.baseCurrency(), declaration.get("base_currency", String.class), "本位币种");
    BigDecimal rate =
        request.exchangeRate() == null
            ? positive(declaration.get("exchange_rate", BigDecimal.class), "汇率")
            : positive(request.exchangeRate(), "汇率");
    int scale = request.exchangeRateScale() == null ? 6 : request.exchangeRateScale();
    if (scale < 0 || scale > 10) {
      throw new IllegalArgumentException("汇率精度刻度必须在 0–10 之间，收到 " + scale);
    }
    LocalDate rateDate = request.rateDate() == null ? LocalDate.now() : request.rateDate();
    LocalDate fxDate = request.exchangeRateDate() == null ? rateDate : request.exchangeRateDate();
    UUID legalEntity =
        request.legalEntityId() == null
            ? declaration.get("legal_entity_id", UUID.class)
            : request.legalEntityId();

    // 计价基础：默认取报关单的申报货值作为完税价格。低报/高报完税价格是真实且常见的申报操作，
    // 所以它可以单独覆盖，而且事后必须能答出「当时按哪个完税价格算的」。
    BigDecimal goodsValue = amount(request.goodsValue(), declaration.get("declaration_value", BigDecimal.class), "货值");
    BigDecimal customsValue = amount(request.customsValue(), goodsValue, "完税价格");
    BigDecimal freight = amount(request.freightAmount(), null, "运费");
    BigDecimal insurance = amount(request.insuranceAmount(), null, "保险费");
    BigDecimal other = amount(request.otherChargeAmount(), null, "其他费用");

    UUID id = UUID.randomUUID();
    long seq = sql.fetchOne("select nextval('duty_tax_estimate_code_seq')").get(0, Long.class);
    LocalDate today = sql.fetchOne("select current_date").get(0, LocalDate.class);
    String code = "DTE-" + today.toString().replace("-", "") + "-" + String.format("%06d", seq);

    // 明细行触发器要读单头状态，所以必须先写头再写行；单头合计先落 0，写完行再回填
    // （折算自洽由 DB 延迟约束在提交时核对）。
    sql.execute(
        "insert into duty_tax_estimate(id,tenant_id,estimate_code,declaration_id,declaration_code,incoterm,"
            + "duty_bearer,legal_entity_id,destination_country_code,estimate_currency,base_currency,"
            + "exchange_rate,exchange_rate_scale,exchange_rate_date,customs_value,goods_value,"
            + "freight_amount,insurance_amount,other_charge_amount,status,notes,created_by)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?,?)",
        id,
        tenantId,
        code,
        request.declarationId(),
        declaration.get("declaration_code", String.class),
        incoterm,
        bearer.name(),
        legalEntity,
        country,
        currency,
        base,
        rate,
        scale,
        fxDate,
        customsValue,
        goodsValue,
        freight,
        insurance,
        other,
        fit(request.notes(), 2000, "备注"),
        currentActor());

    writeLines(tenantId, id, declLines, country, rateDate, customsValue, freight, insurance, other);
    BigDecimal duty = sumLines(tenantId, id, "estimated_duty_amount");
    BigDecimal vat = sumLines(tenantId, id, "estimated_vat_amount");
    applyTotals(tenantId, id, goodsValue, freight, insurance, other, duty, vat, bearer, true);

    int unmatched = unmatchedCount(tenantId, id);
    return new DutySaved(
        id,
        "ESTIMATED",
        unmatched == 0
            ? "税费估算已按税率表算出。核对后可锁定，锁定后作为对外报价与到岸成本依据。"
            : "税费估算已算出，但还有 " + unmatched
                + " 行没配到税率（关税或目的国增值税）。这些行必须先补税率或修 HS 归类，否则锁不了 —— "
                + "按 0 税锁定的估算单会让报价与成本核算少算税。");
  }

  /**
   * 逐行写明细：匹配税率 → 算关税 → 算增值税 → 按货值比例分摊运费保险与完税价。
   *
   * <p>分摊按申报货值比例，<b>最后一行吃掉余数</b>，保证「逐行分摊合计 = 单头金额」精确成立 ——
   * 差一分钱也会让到岸成本明细与单头对不上，而对不上的明细没人敢拿来报价。
   */
  private void writeLines(
      UUID tenantId,
      UUID estimateId,
      List<Record> declLines,
      String country,
      LocalDate rateDate,
      BigDecimal customsValue,
      BigDecimal freight,
      BigDecimal insurance,
      BigDecimal other) {
    BigDecimal totalValue = BigDecimal.ZERO;
    for (var line : declLines) {
      totalValue = totalValue.add(line.get("declared_value", BigDecimal.class));
    }
    BigDecimal usedCustoms = ZERO2;
    BigDecimal usedFreight = ZERO2;
    BigDecimal usedInsurance = ZERO2;
    BigDecimal usedOther = ZERO2;
    int index = 0;
    for (var line : declLines) {
      index++;
      boolean last = index == declLines.size();
      BigDecimal declared = line.get("declared_value", BigDecimal.class);
      // 逐行分摊；最后一行 = 总额 − 已分摊，保证合计精确等于单头金额。
      BigDecimal lineCustoms = last ? customsValue.subtract(usedCustoms) : share(customsValue, declared, totalValue);
      BigDecimal lineFreight = last ? freight.subtract(usedFreight) : share(freight, declared, totalValue);
      BigDecimal lineInsurance = last ? insurance.subtract(usedInsurance) : share(insurance, declared, totalValue);
      BigDecimal lineOther = last ? other.subtract(usedOther) : share(other, declared, totalValue);
      usedCustoms = usedCustoms.add(lineCustoms);
      usedFreight = usedFreight.add(lineFreight);
      usedInsurance = usedInsurance.add(lineInsurance);
      usedOther = usedOther.add(lineOther);

      String hsCode = fit(line.get("hs_code", String.class), 32, "HS 编码");
      BigDecimal quantity = line.get("quantity", BigDecimal.class);

      // 关税：按目的国 + HS 编码**最长前缀** + 生效日取当时税率。
      RateMatch dutyRate = matchDutyRate(tenantId, country, hsCode, rateDate, null);
      BigDecimal dutyAmount =
          round2(dutyRate.adValorem().multiply(lineCustoms).add(dutyRate.specific().multiply(quantity)));

      // 目的国增值税/GST：按国别税率（绝大多数国家 VAT 不按 HS 细分）。
      RateMatch vatRate = matchCountryRate(tenantId, country, "VAT", rateDate);
      if (vatRate == null) {
        vatRate = matchCountryRate(tenantId, country, "GST", rateDate);
      }
      // 进口增值税对关税计征：计税基础 = 完税价格 + 关税 + 运费 + 保险。
      BigDecimal vatBase = lineCustoms.add(dutyAmount).add(lineFreight).add(lineInsurance);
      BigDecimal vatAmount = vatRate == null ? ZERO2 : round2(vatRate.adValorem().multiply(vatBase));
      BigDecimal vatRateValue = vatRate == null ? ZERO6 : vatRate.adValorem();

      String note =
          dutyRate.status() == RateMatchStatus.NO_MATCH
              ? "税率表里没有匹配到 " + country + " / HS " + (hsCode.isEmpty() ? "（本行没有 HS 编码）" : hsCode)
                  + " 在 " + rateDate + " 生效的关税税率：按 0 税估算，必须补税率或修 HS 归类后才能锁定"
              : (vatRate == null
                  ? "没配到 " + country + " 的增值税/GST 税率：进口增值税按 0 估算，必须补税率后才能锁定"
                  : "");

      sql.execute(
          "insert into duty_tax_estimate_line(id,tenant_id,estimate_id,line_no,declaration_line_id,"
              + "source_line_no,sku_id,sku_code,hs_code,origin_country_code,uom,quantity,declared_value,"
              + "line_customs_value,allocated_freight,allocated_insurance,allocated_other_charge,"
              + "duty_rate_id,duty_ad_valorem_rate,duty_specific_rate,duty_rate_prefix,rate_match_status,"
              + "vat_base_amount,vat_rate,vat_rate_matched,estimated_duty_amount,estimated_vat_amount,"
              + "estimated_line_total_tax,preferential_applied,treaty_code,notes)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID(),
          tenantId,
          estimateId,
          index,
          line.get("id", UUID.class),
          line.get("line_no", Integer.class) == null ? index : line.get("line_no", Integer.class),
          line.get("sku_id", UUID.class),
          fit(line.get("sku_code", String.class), 64, "SKU 编码快照"),
          hsCode,
          fit(line.get("origin_country_code", String.class), 2, "原产国"),
          line.get("uom", String.class),
          quantity,
          declared,
          lineCustoms,
          lineFreight,
          lineInsurance,
          lineOther,
          dutyRate.id(),
          dutyRate.adValorem(),
          dutyRate.specific(),
          dutyRate.prefix(),
          dutyRate.status().name(),
          vatBase,
          vatRateValue,
          vatRate != null,
          dutyAmount,
          vatAmount,
          round2(dutyAmount.add(vatAmount)),
          dutyRate.preferential(),
          dutyRate.treaty(),
          note);
    }
  }

  /** 把逐行汇总与到岸成本写回单头（DB 延迟约束在提交时按同一口径复核）。 */
  private void applyTotals(
      UUID tenantId,
      UUID estimateId,
      BigDecimal goodsValue,
      BigDecimal freight,
      BigDecimal insurance,
      BigDecimal other,
      BigDecimal duty,
      BigDecimal vat,
      DutyBearer bearer,
      boolean advanceStatus) {
    BigDecimal baseAmount = goodsValue.add(freight).add(insurance).add(other);
    BigDecimal landedTotal = round2(baseAmount.add(duty).add(vat));
    // **DDU（BUYER）下关税与目的国税费由买方承担，卖方到岸成本不含它们**（DB 触发器也钉这一条）；
    // DDP（SELLER）下卖方承担，含全部税费。这就是「DDP/DDU 改变成本归属，不改变税率」的实现处。
    BigDecimal landedSeller = bearer == DutyBearer.BUYER ? round2(baseAmount) : landedTotal;
    // 参数按 SQL 里的 ? 顺序平铺：这里**不能**把 Object[] 再当 varargs 传一层，
    // 那样 jOOQ 会把整个数组当成一个参数（表现为「未设定参数值 N 的内容」）。
    List<Object> bind = new ArrayList<>();
    bind.add(duty);
    bind.add(vat);
    bind.add(round2(duty.add(vat)));
    bind.add(landedTotal);
    bind.add(landedSeller);
    if (advanceStatus) {
      bind.add(currentActor());
    }
    bind.add(estimateId);
    sql.execute(
        "update duty_tax_estimate set estimated_duty_amount=?,estimated_vat_amount=?,"
            + "estimated_total_tax=?,landed_cost_total=?,landed_cost_seller=?,"
            + (advanceStatus ? "status='ESTIMATED',estimated_at=now(),estimated_by=?," : "")
            + "version=version+1,updated_at=now() where id=?",
        bind.toArray());
  }

  /** 改估算单基础：仅草稿与已估算可改。已锁定后基础与承担方都不许改（DB 触发器也钉这一条）。 */
  @Transactional
  public DutySaved update(UUID tenantId, UUID estimateId, DutyEstimateUpdateRequest request) {
    Record head = requireEstimate(tenantId, estimateId);
    EstimateStatus current = EstimateStatus.parse(head.get("status", String.class));
    if (current == EstimateStatus.LOCKED || current == EstimateStatus.VOID) {
      throw new IllegalArgumentException(
          "估算单处于「" + current.label() + "」状态，不允许改基础：要改请作废重估 —— 锁定后再改等于让已发出的报价失去依据");
    }
    if (request.expectedVersion() != null
        && request.expectedVersion() != head.get("version", Long.class)) {
      throw new IllegalStateException(
          "估算单已被他人修改（期望版本 "
              + request.expectedVersion()
              + "，当前版本 "
              + head.get("version", Long.class)
              + "），请刷新后重试");
    }
    String incoterm =
        request.incoterm() == null ? head.get("incoterm", String.class) : Incoterm.parse(request.incoterm()).name();
    DutyBearer bearer =
        request.dutyBearer() == null
            ? DutyBearer.parse(head.get("duty_bearer", String.class))
            : DutyBearer.parse(request.dutyBearer());
    checkBearerAgainstIncoterm(incoterm, bearer);
    BigDecimal goods = orElse(request.goodsValue(), head.get("goods_value", BigDecimal.class), "货值");
    BigDecimal customs = orElse(request.customsValue(), head.get("customs_value", BigDecimal.class), "完税价格");
    BigDecimal freight = orElse(request.freightAmount(), head.get("freight_amount", BigDecimal.class), "运费");
    BigDecimal insurance = orElse(request.insuranceAmount(), head.get("insurance_amount", BigDecimal.class), "保险费");
    BigDecimal other = orElse(request.otherChargeAmount(), head.get("other_charge_amount", BigDecimal.class), "其他费用");
    BigDecimal fx = orElse(request.exchangeRate(), head.get("exchange_rate", BigDecimal.class), "汇率");
    if (fx.signum() <= 0) {
      throw new IllegalArgumentException("汇率必须大于 0，收到 " + fx);
    }

    sql.execute(
        "update duty_tax_estimate set incoterm=?,duty_bearer=?,customs_value=?,goods_value=?,"
            + "freight_amount=?,insurance_amount=?,other_charge_amount=?,exchange_rate=?,"
            + "landed_cost_total=0,landed_cost_seller=0,status='DRAFT',"
            + "notes=coalesce(nullif(?,''),notes),version=version+1,updated_at=now() where id=?",
        incoterm,
        bearer.name(),
        customs,
        goods,
        freight,
        insurance,
        other,
        fx,
        fit(request.notes(), 2000, "备注"),
        estimateId);
    // 基础改了，逐行分摊与税额必须重算，否则单头与明细立刻对不上。
    return recalculate(tenantId, estimateId, null);
  }

  /**
   * 重算：按当前（或指定日期的）税率表重新逐行算税。
   *
   * <p>生效日可以指定 —— 因为「当时按哪个税率算的」必须可复现、可举证。
   */
  @Transactional
  public DutySaved recalculate(UUID tenantId, UUID estimateId, LocalDate rateDate) {
    Record head = requireEstimate(tenantId, estimateId);
    EstimateStatus current = EstimateStatus.parse(head.get("status", String.class));
    if (current == EstimateStatus.LOCKED || current == EstimateStatus.VOID) {
      throw new IllegalArgumentException("估算单处于「" + current.label() + "」状态，不允许重算：要改请作废重估");
    }
    String country = head.get("destination_country_code", String.class);
    LocalDate on = rateDate == null ? LocalDate.now() : rateDate;
    // 明细是可重算的派生结果：整段重写。税率值已抄进行里，重算时按当前税率表整体覆盖。
    sql.execute("delete from duty_tax_estimate_line where tenant_id=? and estimate_id=?", tenantId, estimateId);
    List<Record> declLines =
        sql.fetch(
            "select l.* from customs_declaration_line l"
                + " join customs_declaration d on d.tenant_id=l.tenant_id and d.id=l.declaration_id"
                + " where l.tenant_id=? and d.id=? order by l.line_no",
            tenantId,
            head.get("declaration_id", UUID.class));
    writeLines(
        tenantId,
        estimateId,
        declLines,
        country,
        on,
        head.get("customs_value", BigDecimal.class),
        head.get("freight_amount", BigDecimal.class),
        head.get("insurance_amount", BigDecimal.class),
        head.get("other_charge_amount", BigDecimal.class));
    BigDecimal duty = sumLines(tenantId, estimateId, "estimated_duty_amount");
    BigDecimal vat = sumLines(tenantId, estimateId, "estimated_vat_amount");
    applyTotals(
        tenantId,
        estimateId,
        head.get("goods_value", BigDecimal.class),
        head.get("freight_amount", BigDecimal.class),
        head.get("insurance_amount", BigDecimal.class),
        head.get("other_charge_amount", BigDecimal.class),
        duty,
        vat,
        DutyBearer.parse(head.get("duty_bearer", String.class)),
        true);
    int unmatched = unmatchedCount(tenantId, estimateId);
    return new DutySaved(
        estimateId,
        "ESTIMATED",
        unmatched == 0
            ? "已按 " + on + " 生效的税率重算。"
            : "已重算，但还有 " + unmatched + " 行没配到税率，锁定前必须补齐。");
  }

  /**
   * 锁定：把估算固定为对外报价/成本依据。
   *
   * <p>DB 触发器 {@code duty_estimate_lock_completeness} 会拒绝带未匹配关税税率或未配 VAT 税率的锁定；
   * 服务层这里先查一遍是为了把「缺哪条税率」讲成人话，而不是只回一句 23514。
   */
  @Transactional
  public DutySaved lock(UUID tenantId, UUID estimateId, DutyLockRequest request) {
    Record head = requireEstimate(tenantId, estimateId);
    EstimateStatus current = EstimateStatus.parse(head.get("status", String.class));
    if (!EstimateStatus.allowedNext(current).contains(EstimateStatus.LOCKED)) {
      throw new IllegalArgumentException(
          "估算单处于「" + current.label() + "」状态，不允许锁定：只有「已估算」可以锁定");
    }
    int unmatchedDuty = lineCount(tenantId, estimateId, " and rate_match_status<>'MATCHED'");
    if (unmatchedDuty > 0) {
      throw new IllegalArgumentException(
          "还有 " + unmatchedDuty
              + " 行没匹配到关税税率，不能锁定：按 0 税锁定的估算单会让报价与到岸成本少算关税，"
              + "而这正是「HS 归类或税率配置有误」在账上最常见的表现。请先补税率表或修正 HS 归类");
    }
    int missingVat = lineCount(tenantId, estimateId, " and not vat_rate_matched");
    if (missingVat > 0) {
      throw new IllegalArgumentException(
          "还有 " + missingVat
              + " 行没配到目的国增值税/GST 税率，不能锁定：漏掉进口增值税的到岸成本会系统性偏低。"
              + "请为该目的国补一条国别税率（零税率国家配 0% 即可）");
    }
    sql.execute(
        "update duty_tax_estimate set status='LOCKED',locked_at=now(),locked_by=?,"
            + "notes=coalesce(nullif(?,''),notes),version=version+1,updated_at=now() where id=?",
        currentActor(),
        fit(request.notes(), 2000, "备注"),
        estimateId);
    return new DutySaved(
        estimateId,
        "LOCKED",
        "估算已锁定。锁定后基础、承担方与税率都不再允许改动 —— 要改只能作废重估，"
            + "这样已发出的报价始终有一份可举证的依据。");
  }

  /** 作废：终态。之后不能再录缴款与差异（DB 触发器钉死），已有实缴的也不许作废。 */
  @Transactional
  public DutySaved voidEstimate(UUID tenantId, UUID estimateId, DutyVoidRequest request) {
    Record head = requireEstimate(tenantId, estimateId);
    EstimateStatus current = EstimateStatus.parse(head.get("status", String.class));
    if (!EstimateStatus.allowedNext(current).contains(EstimateStatus.VOID)) {
      throw new IllegalArgumentException("估算单处于「" + current.label() + "」状态，不允许作废");
    }
    String reason = requireText(request.reason(), "作废原因");
    BigDecimal paid =
        scalar(
            "select coalesce(sum(paid_amount),0) from duty_tax_payment where tenant_id=? and estimate_id=?",
            BigDecimal.class,
            tenantId,
            estimateId);
    if (paid.signum() != 0) {
      throw new IllegalArgumentException(
          "这张估算单已有实缴记录（合计 " + paid + "），不能作废：钱已经付出去了，"
              + "作废估算会让「这票货缴了多少税」在账上凭空消失");
    }
    sql.execute(
        "update duty_tax_estimate set status='VOID',voided_at=now(),void_reason=?,"
            + "version=version+1,updated_at=now() where id=?",
        fit(reason, 200, "作废原因"),
        estimateId);
    return new DutySaved(estimateId, "VOID", "估算已作废（原因：" + reason + "）。");
  }

  // ================================================================ 实缴与差异

  /**
   * 记录实际缴纳，并在**同一事务里**写出对应的差异快照。
   *
   * <p>蓝图原文：「**税费估算与实际缴纳差异要记录**」。这里不是「顺手记一下」——
   * DB 触发器 {@code duty_payment_has_variance} 在提交时核对：有实缴却没有对应差异记录就报 23514。
   * 所以「先存实缴、差异以后再补」在这个模块里**做不到**，这正是蓝图要的效果。
   *
   * <p>差异快照记的是<b>对到本次缴款为止的累计差额</b>：补缴之后再留一条新的，
   * 于是「第 1 次缴款时差多少」这个历史瞬间仍然可查（实缴与差异都是 append-only）。
   * 归因也在这一刻写入（请求里带）—— 已归因的差异不允许事后改写，
   * 要重新判定就再录一笔缴款对账出新的快照，纠错过程本身就是证据链。
   */
  @Transactional
  public DutySaved recordPayment(UUID tenantId, UUID estimateId, DutyPaymentCreateRequest request) {
    Record head = requireEstimate(tenantId, estimateId);
    EstimateStatus status = EstimateStatus.parse(head.get("status", String.class));
    if (status == EstimateStatus.VOID) {
      throw new IllegalArgumentException("估算单已作废，不允许再录缴款");
    }
    TaxKind kind = TaxKind.parse(request.taxKind());
    PaymentKind paymentKind = PaymentKind.parse(request.paymentKind());
    if (request.paidAmount() == null || request.paidAmount().signum() == 0) {
      throw new IllegalArgumentException("缴款金额不能为 0：缴款事实要么有金额要么不记");
    }
    String payer = DutyPayer.parse(request.payer()).name();
    String reference = requireText(request.paymentReference(), "缴款凭证号");
    OffsetDateTime paidAt = request.paidAt() == null ? OffsetDateTime.now() : request.paidAt();
    String currency = moneyOrDefault(request.currency(), head.get("estimate_currency", String.class), "缴款币种");
    int sequence =
        sql.fetchOne(
                "select coalesce(max(payment_sequence),0)+1 s from duty_tax_payment"
                    + " where tenant_id=? and estimate_id=? and tax_kind=?",
                tenantId,
                estimateId,
                kind.name())
            .get("s", Integer.class);

    sql.execute(
        "insert into duty_tax_payment(id,tenant_id,estimate_id,declaration_id,payment_sequence,tax_kind,"
            + "payment_kind,paid_amount,currency,payer,payment_reference,paid_at,payer_name,source_system,"
            + "source_reference,notes,created_by) values(?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?,?,?)",
        UUID.randomUUID(),
        tenantId,
        estimateId,
        head.get("declaration_id", UUID.class),
        sequence,
        kind.name(),
        paymentKind.name(),
        request.paidAmount().setScale(4, RoundingMode.HALF_UP),
        currency,
        payer,
        fit(reference, 64, "缴款凭证号"),
        paidAt,
        fit(request.payerName(), 200, "付款方名称"),
        notBlank(request.sourceSystem())
            ? requireOneOf(request.sourceSystem(), "录入方式", "MANUAL_ENTRY", "CUSTOMS_FEED", "BROKER_API")
            : "MANUAL_ENTRY",
        fit(request.sourceReference(), 64, "来源单号"),
        fit(request.notes(), 2000, "备注"),
        currentActor());

    // ===== 差异快照：估算 vs 对到本次为止的实缴合计 =====
    BigDecimal estimated =
        kind == TaxKind.DUTY
            ? sumLines(tenantId, estimateId, "estimated_duty_amount")
            : sumLines(tenantId, estimateId, "estimated_vat_amount");
    BigDecimal actual = paidTotal(tenantId, estimateId, "= '" + kind.name() + "'");
    BigDecimal variance = actual.subtract(estimated);
    // 估算为 0 而实缴非 0 时差异率**无意义**（除零），用 rate_calculable=false 如实标记 ——
    // 那正是「税率没配」最典型的表现，比一个随手填的 0 有用得多。
    boolean calculable = estimated.signum() != 0;
    VarianceCause cause = request.varianceCause() == null
        ? guessCause(estimated, actual)
        : VarianceCause.parse(request.varianceCause());
    VarianceResolution resolution = request.varianceResolution() == null
        ? VarianceResolution.OPEN
        : VarianceResolution.parse(request.varianceResolution());
    // 「已处理」必须有复核痕迹（DB CHECK 也钉这一条）。
    boolean reviewed = resolution != VarianceResolution.OPEN;
    sql.execute(
        "insert into duty_tax_variance(id,tenant_id,estimate_id,tax_kind,payment_sequence,declaration_line_id,"
            + "estimate_line_id,estimated_amount,actual_amount,variance_amount,variance_rate,rate_calculable,"
            + "currency,variance_cause,cause_note,resolution,reviewed_at,reviewed_by,review_note,source_reference)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        tenantId,
        estimateId,
        kind.name(),
        sequence,
        request.declarationLineId(),
        estimateLineOf(tenantId, estimateId, request.declarationLineId()),
        estimated,
        actual,
        variance,
        calculable ? variance.divide(estimated.abs(), 6, RoundingMode.HALF_UP) : null,
        calculable,
        currency,
        cause.name(),
        fit(request.causeNote(), 2000, "差异归因说明"),
        resolution.name(),
        reviewed ? OffsetDateTime.now() : null,
        reviewed ? currentActor() : null,
        fit(request.reviewNote(), 2000, "复核备注"),
        fit(request.sourceReference(), 64, "来源单号"));

    return new DutySaved(
        estimateId,
        status.name(),
        "已记录" + kind.label() + "实缴 " + request.paidAmount() + " " + currency
            + "（第 " + sequence + " 次），并同时留下差异快照：估算 " + estimated
            + " / 实缴累计 " + actual + " / 差额 " + variance
            + "。差异必须记录 —— 这是蓝图的硬要求，DB 层也会核对。");
  }

  /**
   * 差异分析 —— 蓝图那句「长期差异大说明 HS 归类或税率配置有误」的**查询出口**。
   *
   * <p>三个维度：
   *
   * <ul>
   *   <li>{@code byCause}：差异主要集中在哪一类根因上（HS 归类 / 税率配置 / 完税价格 / 汇率 / 滞纳金…）
   *   <li>{@code byHsCode}：哪几个 HS 码反复出差异（只在差异指名了明细行时归到 HS 码上）
   *   <li>{@code unmatchedRateByHsCode}：<b>领先指标</b> —— 税率没配的行一旦被锁定就已经
   *       错在报价里了，要等海关实缴下来才看得出差异，那时报价早就发出去了
   * </ul>
   */
  public DutyVarianceAnalysis varianceAnalysis(UUID tenantId, String country, LocalDate from, LocalDate to) {
    List<Object> args = new ArrayList<>();
    args.add(tenantId);
    StringBuilder scope = new StringBuilder(" where v.tenant_id=?");
    if (notBlank(country)) {
      scope.append(" and e.destination_country_code=?");
      args.add(country.trim().toUpperCase(Locale.ROOT));
    }
    if (from != null) {
      scope.append(" and v.created_at >= ?::timestamptz");
      args.add(from.atStartOfDay().atOffset(java.time.ZoneOffset.UTC));
    }
    if (to != null) {
      scope.append(" and v.created_at < (?::date + interval '1 day')");
      args.add(to);
    }
    // 只看**最新一次对账快照**：append-only 的历史快照不重复计入，否则补缴次数多的税种会虚增。
    String head =
        " from duty_tax_variance v"
            + " join duty_tax_estimate e on e.tenant_id=v.tenant_id and e.id=v.estimate_id"
            + scope
            + " and v.payment_sequence=(select max(v2.payment_sequence) from duty_tax_variance v2"
            + "   where v2.tenant_id=v.tenant_id and v2.estimate_id=v.estimate_id and v2.tax_kind=v.tax_kind)";

    Record totals =
        sql.fetchOne(
            "select count(*) c, count(distinct v.estimate_id) estimates,"
                + " count(*) filter (where v.resolution='OPEN') open_v,"
                + " coalesce(sum(v.estimated_amount),0) est, coalesce(sum(v.actual_amount),0) act" + head,
            args.toArray(new Object[0]));
    BigDecimal est = totals.get("est", BigDecimal.class);
    BigDecimal act = totals.get("act", BigDecimal.class);
    BigDecimal variance = act.subtract(est);
    BigDecimal absRate =
        est.signum() == 0 ? null : variance.abs().divide(est.abs(), 6, RoundingMode.HALF_UP);

    List<DutyVarianceCauseSummary> byCause = new ArrayList<>();
    for (var r :
        sql.fetch(
            "select v.variance_cause c, count(*) n, count(distinct v.estimate_id) e,"
                + " coalesce(sum(v.estimated_amount),0) est, coalesce(sum(v.actual_amount),0) act" + head
                + " group by v.variance_cause"
                + " order by abs(coalesce(sum(v.actual_amount),0) - coalesce(sum(v.estimated_amount),0)) desc"
                + " limit 50",
            args.toArray(new Object[0]))) {
      String cause = r.get("c", String.class);
      byCause.add(
          new DutyVarianceCauseSummary(
              cause,
              VarianceCause.parse(cause).label(),
              r.get("e", Integer.class),
              r.get("n", Integer.class),
              r.get("est", BigDecimal.class),
              r.get("act", BigDecimal.class),
              r.get("act", BigDecimal.class).subtract(r.get("est", BigDecimal.class)),
              cause));
    }

    List<DutyVarianceCauseSummary> byHsCode = new ArrayList<>();
    for (var r :
        sql.fetch(
            "select l.hs_code hs, count(*) n, count(distinct v.estimate_id) e,"
                + " coalesce(sum(v.estimated_amount),0) est, coalesce(sum(v.actual_amount),0) act,"
                + " (array_agg(v.variance_cause order by abs(v.variance_amount) desc))[1] top_cause"
                + " from duty_tax_variance v"
                + " join duty_tax_estimate e on e.tenant_id=v.tenant_id and e.id=v.estimate_id"
                + " join duty_tax_estimate_line l on l.tenant_id=v.tenant_id and l.id=v.estimate_line_id"
                + scope
                + " and v.payment_sequence=(select max(v2.payment_sequence) from duty_tax_variance v2"
                + "   where v2.tenant_id=v.tenant_id and v2.estimate_id=v.estimate_id and v2.tax_kind=v.tax_kind)"
                + " and btrim(l.hs_code)<>'' group by l.hs_code"
                + " order by abs(coalesce(sum(v.actual_amount),0) - coalesce(sum(v.estimated_amount),0)) desc"
                + " limit 50",
            args.toArray(new Object[0]))) {
      byHsCode.add(
          new DutyVarianceCauseSummary(
              r.get("hs", String.class),
              "HS " + r.get("hs", String.class),
              r.get("e", Integer.class),
              r.get("n", Integer.class),
              r.get("est", BigDecimal.class),
              r.get("act", BigDecimal.class),
              r.get("act", BigDecimal.class).subtract(r.get("est", BigDecimal.class)),
              VarianceCause.parse(r.get("top_cause", String.class)).label()));
    }

    List<Object> unmatchedArgs = new ArrayList<>();
    unmatchedArgs.add(tenantId);
    StringBuilder unmatchedWhere = new StringBuilder(" where l.tenant_id=? and l.rate_match_status<>'MATCHED'");
    if (notBlank(country)) {
      unmatchedWhere.append(" and e.destination_country_code=?");
      unmatchedArgs.add(country.trim().toUpperCase(Locale.ROOT));
    }
    List<DutyUnmatchedRateSummary> unmatched = new ArrayList<>();
    for (var r :
        sql.fetch(
            "select l.hs_code hs, count(*) n, count(distinct e.id) e,"
                + " string_agg(distinct e.destination_country_code, ',') countries"
                + " from duty_tax_estimate_line l"
                + " join duty_tax_estimate e on e.tenant_id=l.tenant_id and e.id=l.estimate_id"
                + unmatchedWhere
                + " group by l.hs_code order by count(*) desc limit 50",
            unmatchedArgs.toArray(new Object[0]))) {
      unmatched.add(
          new DutyUnmatchedRateSummary(
              r.get("hs", String.class),
              r.get("n", Integer.class),
              r.get("e", Integer.class),
              r.get("countries", String.class),
              0));
    }

    return new DutyVarianceAnalysis(
        notBlank(country) ? country.trim().toUpperCase(Locale.ROOT) : "",
        from,
        to,
        totals.get("estimates", Integer.class),
        totals.get("c", Integer.class),
        totals.get("open_v", Integer.class),
        est,
        act,
        variance,
        absRate,
        byCause,
        byHsCode,
        unmatched,
        "按蓝图口径读这张表：差异大先看 byCause（根因分布）与 unmatchedRateByHsCode"
            + "（税率没配的 HS 码 —— 这是领先指标，比等实缴回来更早暴露问题）。"
            + "定位到具体行后，修税率配置走税率表，修归类走报关单明细的 HS 编码。");
  }

  // ================================================================ 税率匹配

  /** 命中的税率（{@code id} 为 null 表示没匹配到）。 */
  private record RateMatch(
      UUID id, BigDecimal adValorem, BigDecimal specific, String prefix, RateMatchStatus status,
      boolean preferential, String treaty) {}

  private static final RateMatch NO_RATE =
      new RateMatch(null, ZERO6, ZERO4, "", RateMatchStatus.NO_MATCH, false, "");

  /**
   * 匹配关税税率：目的国 + {@code rate_scope='HS'} + 最长前缀 + 当日生效。
   *
   * <p>「最长前缀优先」是与海关实际归类一致的：越具体的税率优先于章级税率。
   * 优惠税率只在明确指定协定时才用 —— FTA 适用性需要业务确认（蓝图 10.5.2.1 原文点名
   * 「实质性改变」等条件要业务确认），所以默认只取非优惠行。
   */
  private RateMatch matchDutyRate(UUID tenantId, String country, String hsCode, LocalDate on, String treaty) {
    if (hsCode == null || hsCode.isBlank()) {
      return NO_RATE;
    }
    boolean useTreaty = notBlank(treaty);
    var rate =
        sql.fetchOne(
            "select * from duty_tax_rate where tenant_id=? and destination_country_code=?"
                + " and tax_kind='DUTY' and rate_scope='HS'"
                + " and effective_from<=? and (effective_to is null or effective_to>=?)"
                + " and left(?, length(hs_code_prefix))=hs_code_prefix"
                + (useTreaty ? " and (treaty_code=? or not preferential)" : " and not preferential")
                + " order by length(hs_code_prefix) desc, effective_from desc limit 1",
            useTreaty
                ? new Object[] {tenantId, country, on, on, hsCode, treaty}
                : new Object[] {tenantId, country, on, on, hsCode});
    if (rate == null) {
      return NO_RATE;
    }
    return new RateMatch(
        rate.get("id", UUID.class),
        rate.get("ad_valorem_rate", BigDecimal.class),
        rate.get("specific_rate_per_unit", BigDecimal.class),
        rate.get("hs_code_prefix", String.class),
        RateMatchStatus.MATCHED,
        Boolean.TRUE.equals(rate.get("preferential", Boolean.class)),
        rate.get("treaty_code", String.class));
  }

  /** 匹配国别税率（增值税/GST）。返回 null 表示该目的国没配 —— 调用方落 {@code vat_rate_matched=false}。 */
  private RateMatch matchCountryRate(UUID tenantId, String country, String taxKind, LocalDate on) {
    var rate =
        sql.fetchOne(
            "select * from duty_tax_rate where tenant_id=? and destination_country_code=?"
                + " and tax_kind=? and rate_scope='COUNTRY'"
                + " and effective_from<=? and (effective_to is null or effective_to>=?)"
                + " and not preferential order by effective_from desc limit 1",
            tenantId,
            country,
            taxKind,
            on,
            on);
    if (rate == null) {
      return null;
    }
    return new RateMatch(
        rate.get("id", UUID.class),
        rate.get("ad_valorem_rate", BigDecimal.class),
        rate.get("specific_rate_per_unit", BigDecimal.class),
        "",
        RateMatchStatus.MATCHED,
        false,
        "");
  }

  /** 归因的**自动初判**：只判能从数据本身看出来的，其余一律 PENDING 等人工确认。 */
  private static VarianceCause guessCause(BigDecimal estimated, BigDecimal actual) {
    // 估 0 却真收到了税：最典型就是「税率根本没配」。
    return estimated.signum() == 0 && actual.signum() != 0 ? VarianceCause.RATE_CONFIG : VarianceCause.PENDING;
  }

  private UUID estimateLineOf(UUID tenantId, UUID estimateId, UUID declarationLineId) {
    if (declarationLineId == null) {
      return null;
    }
    Record r =
        sql.fetchOne(
            "select id from duty_tax_estimate_line where tenant_id=? and estimate_id=? and declaration_line_id=?",
            tenantId,
            estimateId,
            declarationLineId);
    return r == null ? null : r.get("id", UUID.class);
  }

  // ================================================================ 映射与工具

  private static DutyRateRow rate(Record r) {
    return new DutyRateRow(
        r.get("id", UUID.class),
        r.get("destination_country_code", String.class),
        r.get("rate_scope", String.class),
        r.get("hs_code_prefix", String.class),
        r.get("tax_kind", String.class),
        r.get("ad_valorem_rate", BigDecimal.class),
        r.get("specific_rate_per_unit", BigDecimal.class),
        r.get("specific_uom", String.class),
        Boolean.TRUE.equals(r.get("preferential", Boolean.class)),
        r.get("treaty_code", String.class),
        r.get("effective_from", LocalDate.class),
        r.get("effective_to", LocalDate.class),
        r.get("tax_source", String.class),
        Boolean.TRUE.equals(r.get("tax_rate_verified", Boolean.class)),
        r.get("source_system", String.class),
        r.get("source_reference", String.class),
        r.get("notes", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static DutyEstimateRow estimate(Record r) {
    BigDecimal duty = orZero(r.get("paid_duty", BigDecimal.class));
    BigDecimal vat = orZero(r.get("paid_vat", BigDecimal.class));
    return estimateOf(
        r, duty, vat, duty.add(vat), orZero(r.get("variance_total", BigDecimal.class)),
        r.get("unmatched", Integer.class));
  }

  private static DutyEstimateRow estimateOf(Record r, BigDecimal paidDuty, BigDecimal paidVat,
      BigDecimal paidTotal, BigDecimal varianceTotal, int unmatched) {
    return new DutyEstimateRow(
        r.get("id", UUID.class),
        r.get("estimate_code", String.class),
        r.get("declaration_id", UUID.class),
        r.get("declaration_code", String.class),
        r.get("incoterm", String.class),
        r.get("duty_bearer", String.class),
        r.get("legal_entity_id", UUID.class),
        r.get("destination_country_code", String.class),
        r.get("estimate_currency", String.class),
        r.get("base_currency", String.class),
        r.get("exchange_rate", BigDecimal.class),
        r.get("exchange_rate_scale", Integer.class),
        r.get("exchange_rate_date", LocalDate.class),
        r.get("customs_value", BigDecimal.class),
        r.get("goods_value", BigDecimal.class),
        r.get("freight_amount", BigDecimal.class),
        r.get("insurance_amount", BigDecimal.class),
        r.get("other_charge_amount", BigDecimal.class),
        r.get("estimated_duty_amount", BigDecimal.class),
        r.get("estimated_vat_amount", BigDecimal.class),
        r.get("estimated_total_tax", BigDecimal.class),
        r.get("landed_cost_total", BigDecimal.class),
        r.get("landed_cost_seller", BigDecimal.class),
        r.get("status", String.class),
        r.get("estimated_at", OffsetDateTime.class),
        r.get("locked_at", OffsetDateTime.class),
        paidDuty,
        paidVat,
        paidTotal,
        varianceTotal,
        unmatched,
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static DutyEstimateLineRow estimateLine(Record r) {
    return new DutyEstimateLineRow(
        r.get("id", UUID.class),
        r.get("line_no", Integer.class),
        r.get("declaration_line_id", UUID.class),
        r.get("source_line_no", Integer.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("hs_code", String.class),
        r.get("origin_country_code", String.class),
        r.get("uom", String.class),
        r.get("quantity", BigDecimal.class),
        r.get("declared_value", BigDecimal.class),
        r.get("line_customs_value", BigDecimal.class),
        r.get("allocated_freight", BigDecimal.class),
        r.get("allocated_insurance", BigDecimal.class),
        r.get("allocated_other_charge", BigDecimal.class),
        r.get("duty_rate_id", UUID.class),
        r.get("duty_ad_valorem_rate", BigDecimal.class),
        r.get("duty_specific_rate", BigDecimal.class),
        r.get("duty_rate_prefix", String.class),
        r.get("rate_match_status", String.class),
        Boolean.TRUE.equals(r.get("vat_rate_matched", Boolean.class)),
        Boolean.TRUE.equals(r.get("preferential_applied", Boolean.class)),
        r.get("treaty_code", String.class),
        r.get("estimated_duty_amount", BigDecimal.class),
        r.get("vat_base_amount", BigDecimal.class),
        r.get("vat_rate", BigDecimal.class),
        r.get("estimated_vat_amount", BigDecimal.class),
        r.get("estimated_line_total_tax", BigDecimal.class),
        r.get("notes", String.class));
  }

  private static DutyPaymentRow payment(Record r) {
    return new DutyPaymentRow(
        r.get("id", UUID.class),
        r.get("estimate_id", UUID.class),
        r.get("declaration_id", UUID.class),
        r.get("payment_sequence", Integer.class),
        r.get("tax_kind", String.class),
        r.get("payment_kind", String.class),
        r.get("paid_amount", BigDecimal.class),
        r.get("currency", String.class),
        r.get("payer", String.class),
        r.get("payment_reference", String.class),
        r.get("paid_at", OffsetDateTime.class),
        r.get("payer_name", String.class),
        r.get("source_system", String.class),
        r.get("source_reference", String.class),
        r.get("notes", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static DutyVarianceRow variance(Record r) {
    return new DutyVarianceRow(
        r.get("id", UUID.class),
        r.get("estimate_id", UUID.class),
        r.get("tax_kind", String.class),
        r.get("payment_sequence", Integer.class),
        r.get("declaration_line_id", UUID.class),
        r.get("estimate_line_id", UUID.class),
        r.get("estimated_amount", BigDecimal.class),
        r.get("actual_amount", BigDecimal.class),
        r.get("variance_amount", BigDecimal.class),
        r.get("variance_rate", BigDecimal.class),
        Boolean.TRUE.equals(r.get("rate_calculable", Boolean.class)),
        r.get("currency", String.class),
        r.get("variance_cause", String.class),
        r.get("cause_note", String.class),
        r.get("resolution", String.class),
        r.get("reviewed_at", OffsetDateTime.class),
        r.get("reviewed_by", UUID.class),
        r.get("review_note", String.class),
        r.get("source_reference", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private List<DutyTaxTransition> transitionsFor(EstimateStatus status) {
    List<DutyTaxTransition> out = new ArrayList<>();
    for (var to : EstimateStatus.allowedNext(status)) {
      out.add(
          new DutyTaxTransition(
              status.name(), status.label(), to.name(), to.label(), actionOf(status, to), noteOf(status, to)));
    }
    return out;
  }

  private Record requireEstimate(UUID tenantId, UUID estimateId) {
    Record r =
        sql.fetchOne("select * from duty_tax_estimate where tenant_id=? and id=?", tenantId, estimateId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "估算单不存在或无权访问：" + estimateId);
    }
    return r;
  }

  private Record requireRate(UUID tenantId, UUID rateId) {
    Record r = sql.fetchOne("select * from duty_tax_rate where tenant_id=? and id=?", tenantId, rateId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "税率不存在或无权访问：" + rateId);
    }
    return r;
  }

  private int lineCount(UUID tenantId, UUID estimateId, String extra) {
    return sql.fetchOne(
            "select count(*) c from duty_tax_estimate_line where tenant_id=? and estimate_id=?" + extra,
            tenantId,
            estimateId)
        .get("c", Integer.class);
  }

  private int unmatchedCount(UUID tenantId, UUID estimateId) {
    return lineCount(tenantId, estimateId, " and (rate_match_status<>'MATCHED' or not vat_rate_matched)");
  }

  private BigDecimal paidTotal(UUID tenantId, UUID estimateId, String taxFilter) {
    return scalar(
        "select coalesce(sum(paid_amount),0) from duty_tax_payment where tenant_id=? and estimate_id=?"
            + " and tax_kind " + taxFilter,
        BigDecimal.class,
        tenantId,
        estimateId);
  }

  private BigDecimal varianceTotal(UUID tenantId, UUID estimateId) {
    return scalar(
        "select coalesce(sum(v.variance_amount),0) from duty_tax_variance v where v.tenant_id=?"
            + " and v.estimate_id=? and v.payment_sequence=(select max(v2.payment_sequence)"
            + " from duty_tax_variance v2 where v2.tenant_id=v.tenant_id and v2.estimate_id=v.estimate_id"
            + " and v2.tax_kind=v.tax_kind)",
        BigDecimal.class,
        tenantId,
        estimateId);
  }

  private BigDecimal sumLines(UUID tenantId, UUID estimateId, String column) {
    // column 来自本类的固定调用点（estimated_duty_amount / estimated_vat_amount），
    // 绝不接受外部输入，所以不存在 SQL 注入面。
    return scalar(
        "select coalesce(sum(" + column + "),0) from duty_tax_estimate_line where tenant_id=? and estimate_id=?",
        BigDecimal.class,
        tenantId,
        estimateId);
  }

  private <T> T scalar(String sqlText, Class<T> type, Object... args) {
    Record r = sql.fetchOne(sqlText, args);
    return r == null ? null : r.get(0, type);
  }

  private static Object[] concat(List<Object> args, Object... more) {
    Object[] out = new Object[args.size() + more.length];
    for (int i = 0; i < args.size(); i++) {
      out[i] = args.get(i);
    }
    for (int i = 0; i < more.length; i++) {
      out[args.size() + i] = more[i];
    }
    return out;
  }

  private static Object[] prepend(Object first, List<Object> rest) {
    return concat(new ArrayList<>(java.util.List.of(first)), rest.toArray());
  }

  private static String placeholders(int n) {
    return String.join(",", java.util.Collections.nCopies(n, "?"));
  }

  private static String estimateSortOf(String raw) {
    String sort = sortKey(ESTIMATE_SORTS, raw, "UPDATED_AT");
    return switch (sort) {
      case "ESTIMATED_TAX" -> "e.estimated_total_tax desc, e.updated_at desc";
      case "LANDED_COST" -> "e.landed_cost_total desc, e.updated_at desc";
      case "ESTIMATE_CODE" -> "e.estimate_code asc";
      case "CREATED_AT" -> "e.created_at desc";
      default -> "e.updated_at desc";
    };
  }

  private static String rateSortOf(String raw) {
    String sort = sortKey(RATE_SORTS, raw, "CREATED_AT");
    return switch (sort) {
      case "EFFECTIVE_FROM" -> "effective_from desc, destination_country_code, hs_code_prefix";
      case "AD_VALOREM_RATE" -> "ad_valorem_rate desc, destination_country_code";
      case "DESTINATION_COUNTRY" -> "destination_country_code, tax_kind, hs_code_prefix";
      case "HS_CODE_PREFIX" -> "hs_code_prefix, tax_kind";
      default -> "created_at desc";
    };
  }

  /** 校验并归一排序键：不在白名单里就报错，不静默回落到默认值（静默回落会让人以为排序生效了）。 */
  private static String sortKey(List<String> allowed, String raw, String fallback) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    String upper = raw.trim().toUpperCase(Locale.ROOT);
    if (!allowed.contains(upper)) {
      throw new IllegalArgumentException("不支持的排序：" + raw + "，可选：" + String.join("、", allowed));
    }
    return upper;
  }

  private static int pageSize(int size) {
    if (size <= 0) {
      return 50;
    }
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private static BigDecimal share(BigDecimal total, BigDecimal part, BigDecimal all) {
    if (all == null || all.signum() == 0) {
      return ZERO2;
    }
    return total.multiply(part).divide(all, MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /** 结算口径：HALF_UP 到分。DB 侧用 {@code round(x,2)} 表达同一口径，提交时由约束触发器复核。 */
  static BigDecimal round2(BigDecimal value) {
    return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static RateScope rateScope(String raw) {
    if (raw == null || raw.isBlank()) {
      return RateScope.HS;
    }
    for (var s : RateScope.values()) {
      if (s.name().equalsIgnoreCase(raw)) {
        return s;
      }
    }
    throw new IllegalArgumentException(
        "未知的税率适用范围：" + raw + "，可选：COUNTRY（国别）/ HS（按 HS 编码）");
  }

  private static String normalizePrefix(RateScope scope, String raw) {
    String prefix = raw == null ? "" : raw.trim();
    if (scope == RateScope.COUNTRY) {
      if (!prefix.isEmpty()) {
        throw new IllegalArgumentException("国别税率没有 HS 编码前缀：增值税/GST 通常按国征收，不按 HS 细分");
      }
      return "";
    }
    if (!prefix.matches("[0-9]{2,10}")) {
      throw new IllegalArgumentException(
          "按 HS 编码的税率必须给 2–10 位数字前缀（如 6109 表示章级税率），收到：" + prefix);
    }
    return prefix;
  }

  private static void checkBearerAgainstIncoterm(String incoterm, DutyBearer bearer) {
    if (incoterm.equals("DDP") && bearer != DutyBearer.SELLER) {
      throw new IllegalArgumentException(
          "DDP（完税后交货）下关税与目的国税费由卖方承担，不能设成" + bearer.label());
    }
    if (incoterm.equals("DDU") && bearer != DutyBearer.BUYER) {
      throw new IllegalArgumentException(
          "DDU（未完税交货）下关税与目的国税费由买方承担，不能设成" + bearer.label());
    }
  }

  private static DutyBearer dutyBearerOf(String raw, String incoterm) {
    if (raw == null || raw.isBlank()) {
      // DDP 默认卖方承担、DDU 默认买方承担：与贸易条款的默认语义一致。
      return incoterm.equals("DDP") ? DutyBearer.SELLER : DutyBearer.BUYER;
    }
    DutyBearer bearer = DutyBearer.parse(raw);
    checkBearerAgainstIncoterm(incoterm, bearer);
    return bearer;
  }

  private static BigDecimal amount(BigDecimal given, BigDecimal fallback, String label) {
    return nonNegative(given == null ? (fallback == null ? ZERO4 : fallback) : given, label);
  }

  private static BigDecimal orElse(BigDecimal given, BigDecimal existing, String label) {
    return nonNegative(given == null ? existing : given, label);
  }

  private static BigDecimal nonNegative(BigDecimal value, String label) {
    if (value == null) {
      throw new IllegalArgumentException("缺少必填项：" + label);
    }
    if (value.signum() < 0) {
      throw new IllegalArgumentException(label + "不能为负数，收到 " + value);
    }
    return value;
  }

  private static BigDecimal positive(BigDecimal value, String label) {
    if (value == null) {
      throw new IllegalArgumentException("缺少必填项：" + label);
    }
    if (value.signum() <= 0) {
      throw new IllegalArgumentException(label + "必须大于 0，收到 " + value);
    }
    return value;
  }

  private static BigDecimal orZero(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  private static String taxSource(String raw) {
    return notBlank(raw)
        ? requireOneOf(raw, "税率来源", "MANUAL", "MODULE_16_3_1_1")
        : "MANUAL";
  }

  private static String uom(String raw) {
    if (raw == null || raw.isBlank()) {
      return "PCS";
    }
    String v = raw.trim().toUpperCase(Locale.ROOT);
    if (!v.matches("[A-Z]{1,8}")) {
      throw new IllegalArgumentException("计量单位必须是 1–8 位大写字母（如 PCS / KG），收到：" + v);
    }
    return v;
  }

  private static String requireOneOf(String raw, String label, String... allowed) {
    String v = raw.trim();
    for (var a : allowed) {
      if (a.equalsIgnoreCase(v)) {
        return a;
      }
    }
    throw new IllegalArgumentException(
        label + "只能是 " + String.join(" / ", allowed) + " 之一，收到：" + raw);
  }

  private static String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("缺少必填项：" + label);
    }
    return value.trim();
  }

  private static String requireCountry(String value, String label) {
    String v = requireText(value, label).toUpperCase(Locale.ROOT);
    if (v.length() != 2) {
      throw new IllegalArgumentException(label + "必须是两位国家/地区代码（如 CN / US），收到：" + v);
    }
    return v;
  }

  private static String money(String value, String label) {
    String v = requireText(value, label).toUpperCase(Locale.ROOT);
    if (!v.matches("[A-Z]{3}")) {
      throw new IllegalArgumentException(label + "必须是三位大写币种代码（如 USD / CNY），收到：" + v);
    }
    return v;
  }

  private static String moneyOrDefault(String value, String fallback, String label) {
    return notBlank(value) ? money(value, label) : money(fallback, label);
  }

  private static String fit(String value, int width, String label) {
    if (value == null) {
      return "";
    }
    String v = value.trim();
    if (v.length() > width) {
      throw new IllegalArgumentException(
          label + "长度超过 " + width + " 字符（当前 " + v.length() + "）。字段被悄悄截断会让账与票对不上。");
    }
    return v;
  }

  private static String firstNonBlank(String a, String b) {
    return notBlank(a) ? a : b;
  }

  private static boolean notBlank(String v) {
    return v != null && !v.isBlank();
  }

  /** 当前 RLS 租户。由 backend/libraries/tenancy 在事务内设置，客户端头改不动它。 */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  /** 当前操作人。设置名是 {@code app.actor}（AccessService 在事务里绑定），不是 app.current_user。 */
  private UUID currentActor() {
    Record r = sql.fetchOne("select nullif(current_setting('app.actor',true),'')::uuid as actor");
    UUID actor = r.get("actor", UUID.class);
    return actor == null ? currentTenant() : actor;
  }
}
