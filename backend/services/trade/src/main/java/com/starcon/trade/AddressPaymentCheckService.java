package com.starcon.trade;

import static com.starcon.trade.AddressPaymentCheckModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 9.2.2.1 地址与支付校验：地址可投递性 + 支付状态一致性 + 风险等级。
 *
 * <p><b>八条实现纪律</b>：
 *
 * <ol>
 *   <li><b>本类里没有、也不该有任何国家数组与国家邮编正则</b>：禁运清单、国别地址格式规则、
 *       支付一致性规则全部是<b>租户可维护的表</b>，判定一律从表里读。
 *       「一个国家一套规则」不是「代码里写好几套 if」，而是「表里每个国家一行」。
 *   <li><b>禁运清单与国别规则是配置不是硬编码</b>：改一次禁运名单不用发版，
 *       而且发版之前那段时间里的禁运名单是错的。
 *   <li><b>支付状态本服务没有这个事实</b>：实查 V99 sales_order **没有 payment_status 列**，
 *       V207 platform_order 也没有，12 财务的支付状态能力未交付。
 *       ⇒ 本模块**不发明**一个支付状态列冒充上游事实（那会让界面显示「未付款」，
 *         而真相是「本服务根本不知道支付进展」—— 与 V100 同一纪律）。
 *       ⇒ 支付状态由<b>调用方随请求提供</b>，本模块只判它与付款方式自不自洽；
 *         调用方没给 ⇒ 该项落 NO_REFERENCE，<b>不是</b>「一致」。
 *   <li><b>计数、风险等级与可投递性由 DB 触发器实算</b>：服务层只负责算出七项各自的结论，
 *       {@code order_address_check_apply_totals} 负责计数与定档。
 *       两处各算一遍的结果迟早会不一致，而「检查上写着命中 1 条，它与七行明细算出来的不一样」
 *       是无法排查的。
 *   <li><b>货到付款独占最高风险档</b>：V210 用
 *       {@code CHECK ((risk_level='CRITICAL') = cod_flagged)} 双向钉死，
 *       本服务层<b>不写</b> risk_level —— 它由触发器从订单的付款方式实算。
 *   <li><b>保险策略不假装有</b>：{@code COD_INSURANCE_POLICY} 恒为 UPSTREAM_UNAVAILABLE
 *       （非货到付款单则是 NOT_APPLICABLE），本模块<b>不</b>算保费、<b>不</b>推荐投保方案。
 *   <li><b>判定基准时刻钉在订单 submitted_at 上</b>，不是 {@code now()}：
 *       同一张单重复跑一万次，七项结论逐位相同。禁运清单的生效期也相对这个时刻判。
 *   <li><b>每条查询都显式带租户谓词</b>：判定查询是「同一收货国」「同一付款方式」的
 *       跨订单宽条件，漏掉租户谓词就是跨租户归因 —— A 家的禁运国清单会拦下 B 家的订单，
 *       而 B 家连自己被拦都不知道。
 * </ol>
 */
@Service
public class AddressPaymentCheckService {

  /** 单页最大行数。校验结果是凭据不是流水，但仍设上限防一次拉爆 JVM。 */
  static final int MAX_PAGE_SIZE = 200;

  private static final int MONEY_SCALE = 4;

  private static final String TENANT =
      " nullif(current_setting('app.current_tenant',true),'')::uuid";

  private final DSLContext sql;

  public AddressPaymentCheckService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：蓝图输入里的「支付方式与状态」中，**支付状态在本服务没有这个事实**；
   * 加粗句里的「保险策略」在蓝图中**没有任何 L4 模块持有**。
   * **哪几项真接通、哪几项靠租户登记、哪几项现在根本判不了，必须由接口说**，
   * 而不是让调用方从 404 或空数组里猜哪一环没通。
   */
  public AddressPaymentCapabilities capabilities() {
    boolean configured = policyOrNull() != null;
    long embargoCount = count("order_address_check_embargo_rule");
    return new AddressPaymentCapabilities(
        List.of(
            new AddressPaymentCapabilityItem(
                "COUNTRY_EMBARGO",
                AddressPaymentItemCode.COUNTRY_EMBARGO.label(),
                "租户可维护的禁运国家清单 order_address_check_embargo_rule + 收货地址快照 country_code",
                true,
                "禁运清单的 embargo_level（BLOCK = 不可发 / REVIEW = 需人工或渠道确认）",
                null,
                null),
            new AddressPaymentCapabilityItem(
                "POSTCODE_FORMAT",
                AddressPaymentItemCode.POSTCODE_FORMAT.label(),
                "租户可维护的国别地址格式规则 order_address_check_country_rule.postcode_pattern"
                    + " + 收货地址快照 postcode",
                true,
                "order_address_check_country_rule.postcode_pattern（**按国家维度**，一个国家一套）",
                null,
                null),
            new AddressPaymentCapabilityItem(
                "STREET_FORMAT",
                AddressPaymentItemCode.STREET_FORMAT.label(),
                "租户可维护的国别地址格式规则 order_address_check_country_rule.street_min_length"
                    + " + 收货地址快照 address_line1",
                true,
                "order_address_check_country_rule.street_required / street_min_length",
                null,
                null),
            new AddressPaymentCapabilityItem(
                "REQUIRED_CONTACT",
                AddressPaymentItemCode.REQUIRED_CONTACT.label(),
                "租户可维护的国别地址格式规则（city / region / phone 是否必填）"
                    + " + 收货地址快照 recipient_name / recipient_phone / city / province",
                true,
                "order_address_check_country_rule.city_required / region_required / phone_required",
                null,
                null),
            new AddressPaymentCapabilityItem(
                "PAYMENT_CONSISTENCY",
                AddressPaymentItemCode.PAYMENT_CONSISTENCY.label(),
                "订单付款方式 sales_order.payment_method（V99，真 SQL）"
                    + " + **调用方随请求提供的支付状态观察值** + 租户可维护的一致性规则表",
                false,
                "order_address_check_payment_rule（付款方式 × 观察状态 → 是否自洽 + 命中严重度）",
                "12",
                "⚠️ **支付状态本服务没有这个事实**：实查 V99 sales_order 没有 payment_status 列，"
                    + "V207 platform_order 也没有；12 财务的支付状态能力**未交付**。"
                    + "⇒ 支付状态由调用方随请求提供，本模块只判它与付款方式自不自洽；"
                    + "调用方没提供 ⇒ 本项落 NO_REFERENCE，**不是**「一致」——"
                    + "「没量到」与「量过了、结论是正常」是两件事。"
                    + "12 财务交付后由同步器按同一形状写入并改来源枚举，表结构与判定逻辑都不用改。"),
            new AddressPaymentCapabilityItem(
                "COD_FULFILLMENT_RISK",
                AddressPaymentItemCode.COD_FULFILLMENT_RISK.label(),
                "订单付款方式 sales_order.payment_method（V99，真 SQL）",
                true,
                "**无阈值**：货到付款恒为最高风险档 CRITICAL（V210 表级 CHECK 双向钉死，不可配置）",
                null,
                null),
            new AddressPaymentCapabilityItem(
                "COD_INSURANCE_POLICY",
                AddressPaymentItemCode.COD_INSURANCE_POLICY.label(),
                "**无**：货到付款保险策略在蓝图中**没有任何 L4 模块持有**",
                false,
                "无阈值（本项恒为 UPSTREAM_UNAVAILABLE，**不假装**能算保费或推荐投保方案）",
                "10.1.1.2",
                "蓝图加粗句「货到付款订单的履约风险最高，需额外风控与保险策略」中，"
                    + "**额外风控**那一半是真接通的（9.2.2.2 风控校验 V150，同服务同库，"
                    + "货到付款单的风险分与建议动作走它），**保险策略**那一半**未交付**："
                    + "卷 10 的 10.1.1.2 渠道配置持有的是**单票保价规则**（渠道维度的保额上限），"
                    + "「按货到付款订单给出保险策略」在蓝图中**没有 L4 归属**。"
                    + "⇒ 本模块只把这一项标成未交付并指明归属，不假装有保险能力；"
                    + "影响是：**货到付款单的风险等级是最高的，但它的保险缺口在本系统里无处可查** ——"
                    + "运营需要知道这一单该投保却没有依据，这是本模块如实暴露的缺口，不是本模块能补的。")),
        "货到付款订单的履约风险最高，需额外风控与保险策略。",
        "CRITICAL（货到付款，无论其它项结论如何）> HIGH（存在 BLOCK 级命中）"
            + " > MEDIUM（存在 WARN 级命中）> LOW（其它）",
        "NOT_DELIVERABLE（地址类四项存在 BLOCK 级命中）> UNDETERMINED"
            + "（地址类有缺参照且策略选了 BLOCK_DECISION）> DELIVERABLE（其它）",
        configured,
        embargoCount > 0,
        "支付状态（12 财务）未交付；货到付款保险策略无 L4 归属；16 合规的国别禁运清单未交付，"
            + "当前禁运清单由租户自维护；10 物流的渠道可用性在下游，由 9.2.1.3 异常拦截消费本模块结论。");
  }

  // ================================================================ 策略

  /** 校验策略读取。没登记过就是 404 —— 不是一份带默认值的策略。 */
  public AddressPaymentPolicy policy() {
    AddressPaymentPolicy found = policyOrNull();
    if (found == null) {
      throw notFound("本租户还没有登记地址与支付校验策略：先 PUT /policy 登记后再跑校验");
    }
    return found;
  }

  /**
   * 登记 / 更新校验策略（upsert，一租户一条）。
   *
   * <p>本模块<b>不提供</b>「未配置时的默认策略」：校验结论会被 9.2.1.3 异常拦截与 10 物流读，
   * 「没配 = 什么都不拦」与「清单位国家真的不在禁运清单里」长得一模一样。
   *
   * <p>登记时顺带<b>幂等</b>装一份基线码表（国别地址格式规则 + 支付一致性规则）：
   * 基线数据写在迁移脚本里（不是 Java 里），落库之后就是**普通的租户行**，
   * 能改、能停用、能加新国家 —— 判定真身是表，不是那段基线。
   */
  @Transactional
  public AddressPaymentPolicy savePolicy(AddressPaymentPolicyInput input) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    UUID tenant = currentTenant();
    Record existing = sql.fetchOne("select id, version from order_address_check_policy where tenant_id = " + TENANT);
    long version = existing == null ? 0L : existing.get("version", Long.class) + 1L;
    sql.execute(
        "insert into order_address_check_policy(id,tenant_id,policy_name,enabled,"
            + "missing_reference_policy,version,created_at,updated_at)"
            + " values(?,?,?,true,?,?,now(),now())"
            + " on conflict (tenant_id) do update"
            + " set policy_name=excluded.policy_name,"
            + " missing_reference_policy=excluded.missing_reference_policy,"
            + " version=excluded.version, updated_at=now()",
        existing == null ? UUID.randomUUID() : existing.get("id", UUID.class),
        tenant,
        input.policyName().trim(),
        input.missingReferencePolicy().name(),
        version);
    // 基线码表幂等安装（ON CONFLICT DO NOTHING ⇒ 租户已改过的行不会被覆盖）。
    // 返回行数：调用方能立刻确认「基线装上了」，而不是「调用没报错 ⇒ 大概装上了」。
    sql.fetchOne("select order_address_check_install_baseline(?)", tenant);
    return policy();
  }

  private AddressPaymentPolicy policyOrNull() {
    Record r = sql.fetchOne("select * from order_address_check_policy where tenant_id = " + TENANT);
    return r == null
        ? null
        : new AddressPaymentPolicy(
            r.get("id", UUID.class),
            r.get("policy_name", String.class),
            r.get("enabled", Boolean.class),
            AddressPaymentMissingReferencePolicy.valueOf(r.get("missing_reference_policy", String.class)),
            r.get("version", Long.class),
            r.get("created_at", OffsetDateTime.class),
            r.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 禁运清单

  /** 禁运国家清单列表（真分页 + 真搜索）。 */
  public AddressPaymentEmbargoRulePage pageEmbargoRules(
      String keyword, String embargoLevel, int page, int size) {
    int pageSize = pageSize(size);
    int pageNo = Math.max(0, page);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = " + TENANT);
    if (notBlank(keyword)) {
      where.add("(country_code ilike ? or reason ilike ?)");
      String k = "%" + keyword.trim() + "%";
      binds.add(k);
      binds.add(k);
    }
    if (notBlank(embargoLevel)) {
      where.add("embargo_level = ?");
      binds.add(embargoLevel.trim().toUpperCase(Locale.ROOT));
    }
    String sqlWhere = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) c from order_address_check_embargo_rule c" + sqlWhere, binds.toArray())
            .get("c", Long.class);
    List<AddressPaymentEmbargoRule> items =
        sql.fetch(
                "select c.* from order_address_check_embargo_rule c"
                    + sqlWhere
                    + " order by c.country_code, c.id desc limit ? offset ?",
                append(binds, pageSize, (long) pageNo * pageSize))
            .map(AddressPaymentCheckService::embargoRow);
    return new AddressPaymentEmbargoRulePage(items, total, pageNo, pageSize, ceil(total, pageSize));
  }

  /**
   * 登记 / 更新一条禁运国家。
   *
   * <p>这是本模块**唯一**的禁运清单数据入口：它不是占位，是真实参与判定的业务数据。
   * 16 合规的正式清单到位后由同步器按同一形状写入，只需把 source 改成
   * MODULE_16_COMPLIANCE，表结构与判定逻辑都不动。
   */
  @Transactional
  public AddressPaymentEmbargoRule saveEmbargoRule(AddressPaymentEmbargoRuleInput input) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    if (input.source() != AddressPaymentRuleSource.TENANT_MAINTAINED
        && !notBlank(input.sourceReference())) {
      throw bad("来源不是「租户自维护」时必须写明 sourceReference：这行是同步器写的还是口头声明");
    }
    UUID tenant = currentTenant();
    String country = input.countryCode().trim().toUpperCase(Locale.ROOT);
    sql.execute(
        "insert into order_address_check_embargo_rule(id,tenant_id,country_code,embargo_level,"
            + "source,source_reference,reason,effective_from,effective_to,enabled,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,now(),now())"
            + " on conflict (tenant_id,country_code) do update"
            + " set embargo_level=excluded.embargo_level, source=excluded.source,"
            + " source_reference=excluded.source_reference, reason=excluded.reason,"
            + " effective_from=excluded.effective_from, effective_to=excluded.effective_to,"
            + " enabled=excluded.enabled, updated_at=now()",
        UUID.randomUUID(),
        tenant,
        country,
        input.embargoLevel().name(),
        input.source().name(),
        nz(input.sourceReference()),
        nz(input.reason()),
        input.effectiveFrom(),
        input.effectiveTo(),
        input.enabled() == null || input.enabled());
    return sql
        .fetchOne(
            "select * from order_address_check_embargo_rule where tenant_id = "
                + TENANT
                + " and country_code = ?",
            country)
        .map(AddressPaymentCheckService::embargoRow);
  }

  private static AddressPaymentEmbargoRule embargoRow(Record r) {
    return new AddressPaymentEmbargoRule(
        r.get("id", UUID.class),
        r.get("country_code", String.class),
        AddressPaymentEmbargoLevel.valueOf(r.get("embargo_level", String.class)),
        AddressPaymentRuleSource.valueOf(r.get("source", String.class)),
        r.get("source_reference", String.class),
        r.get("reason", String.class),
        r.get("effective_from", OffsetDateTime.class),
        r.get("effective_to", OffsetDateTime.class),
        r.get("enabled", Boolean.class),
        r.get("created_at", OffsetDateTime.class));
  }

  // ================================================================ 国别规则

  /** 国别地址格式规则列表（真分页 + 真搜索）。 */
  public AddressPaymentCountryRulePage pageCountryRules(String keyword, int page, int size) {
    int pageSize = pageSize(size);
    int pageNo = Math.max(0, page);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = " + TENANT);
    if (notBlank(keyword)) {
      where.add("(country_code ilike ? or postcode_note ilike ?)");
      String k = "%" + keyword.trim() + "%";
      binds.add(k);
      binds.add(k);
    }
    String sqlWhere = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) c from order_address_check_country_rule c" + sqlWhere, binds.toArray())
            .get("c", Long.class);
    List<AddressPaymentCountryRule> items =
        sql.fetch(
                "select c.* from order_address_check_country_rule c"
                    + sqlWhere
                    + " order by c.country_code, c.id desc limit ? offset ?",
                append(binds, pageSize, (long) pageNo * pageSize))
            .map(AddressPaymentCheckService::countryRow);
    return new AddressPaymentCountryRulePage(items, total, pageNo, pageSize, ceil(total, pageSize));
  }

  /**
   * 登记 / 更新国别地址格式规则。
   *
   * <p>邮编正则在这里<b>先编译一遍</b>：写进去一条编译不过的正则，
   * 会让该国的邮编项**永远**判不了，而界面上它显示为「已配置」——
   * 一个永远不命中的检查比没有检查更糟。
   */
  @Transactional
  public AddressPaymentCountryRule saveCountryRule(AddressPaymentCountryRuleInput input) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    String pattern = nz(input.postcodePattern()).trim();
    if (input.postcodeRequired()) {
      if (pattern.isEmpty()) {
        throw bad("邮编必填就必须给出邮编正则");
      }
      compileOrBad(pattern);
    }
    if (input.streetRequired() && input.streetMinLength() > 300) {
      throw bad("街道最小长度不能超过 300");
    }
    UUID tenant = currentTenant();
    String country = input.countryCode().trim().toUpperCase(Locale.ROOT);
    sql.execute(
        "insert into order_address_check_country_rule(id,tenant_id,country_code,postcode_required,"
            + "postcode_pattern,postcode_note,street_required,street_min_length,city_required,"
            + "region_required,phone_required,enabled,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,now(),now())"
            + " on conflict (tenant_id,country_code) do update"
            + " set postcode_required=excluded.postcode_required,"
            + " postcode_pattern=excluded.postcode_pattern, postcode_note=excluded.postcode_note,"
            + " street_required=excluded.street_required, street_min_length=excluded.street_min_length,"
            + " city_required=excluded.city_required, region_required=excluded.region_required,"
            + " phone_required=excluded.phone_required, enabled=excluded.enabled, updated_at=now()",
        UUID.randomUUID(),
        tenant,
        country,
        input.postcodeRequired(),
        pattern,
        nz(input.postcodeNote()),
        input.streetRequired(),
        input.streetMinLength(),
        input.cityRequired(),
        input.regionRequired(),
        input.phoneRequired(),
        input.enabled() == null || input.enabled());
    return sql
        .fetchOne(
            "select * from order_address_check_country_rule where tenant_id = " + TENANT + " and country_code = ?",
            country)
        .map(AddressPaymentCheckService::countryRow);
  }

  private static AddressPaymentCountryRule countryRow(Record r) {
    return new AddressPaymentCountryRule(
        r.get("id", UUID.class),
        r.get("country_code", String.class),
        r.get("postcode_required", Boolean.class),
        r.get("postcode_pattern", String.class),
        r.get("postcode_note", String.class),
        r.get("street_required", Boolean.class),
        r.get("street_min_length", Integer.class),
        r.get("city_required", Boolean.class),
        r.get("region_required", Boolean.class),
        r.get("phone_required", Boolean.class),
        r.get("enabled", Boolean.class),
        r.get("created_at", OffsetDateTime.class));
  }

  // ================================================================ 支付规则

  /** 支付状态一致性规则列表（真分页 + 真筛选）。 */
  public AddressPaymentPaymentRulePage pagePaymentRules(
      String paymentMethod, String observedStatus, int page, int size) {
    int pageSize = pageSize(size);
    int pageNo = Math.max(0, page);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = " + TENANT);
    if (notBlank(paymentMethod)) {
      where.add("payment_method = ?");
      binds.add(paymentMethod.trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(observedStatus)) {
      where.add("observed_status = ?");
      binds.add(observedStatus.trim().toUpperCase(Locale.ROOT));
    }
    String sqlWhere = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) c from order_address_check_payment_rule c" + sqlWhere, binds.toArray())
            .get("c", Long.class);
    List<AddressPaymentPaymentRule> items =
        sql.fetch(
                "select c.* from order_address_check_payment_rule c"
                    + sqlWhere
                    + " order by c.payment_method, c.observed_status limit ? offset ?",
                append(binds, pageSize, (long) pageNo * pageSize))
            .map(AddressPaymentCheckService::paymentRuleRow);
    return new AddressPaymentPaymentRulePage(items, total, pageNo, pageSize, ceil(total, pageSize));
  }

  /**
   * 登记 / 更新支付状态一致性规则。
   *
   * <p>词表严格自洽：{@code expectedSeverity=OK} 表示该组合自洽，
   * WARN / BLOCK 表示矛盾 —— 两者由 DB 的 CHECK 双向钉死，写反了写不进去。
   */
  @Transactional
  public AddressPaymentPaymentRule savePaymentRule(AddressPaymentPaymentRuleInput input) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    UUID tenant = currentTenant();
    String method = input.paymentMethod().trim().toUpperCase(Locale.ROOT);
    String status = input.observedStatus().trim().toUpperCase(Locale.ROOT);
    boolean consistent = input.expectedSeverity() == AddressPaymentRuleSeverity.OK;
    sql.execute(
        "insert into order_address_check_payment_rule(id,tenant_id,payment_method,observed_status,"
            + "expected_consistent,expected_severity,reason,enabled,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,now(),now())"
            + " on conflict (tenant_id,payment_method,observed_status) do update"
            + " set expected_consistent=excluded.expected_consistent,"
            + " expected_severity=excluded.expected_severity, reason=excluded.reason,"
            + " enabled=excluded.enabled, updated_at=now()",
        UUID.randomUUID(),
        tenant,
        method,
        status,
        consistent,
        input.expectedSeverity().name(),
        nz(input.reason()),
        input.enabled() == null || input.enabled());
    return sql
        .fetchOne(
            "select * from order_address_check_payment_rule where tenant_id = "
                + TENANT
                + " and payment_method = ? and observed_status = ?",
            method,
            status)
        .map(AddressPaymentCheckService::paymentRuleRow);
  }

  private static AddressPaymentPaymentRule paymentRuleRow(Record r) {
    return new AddressPaymentPaymentRule(
        r.get("id", UUID.class),
        r.get("payment_method", String.class),
        r.get("observed_status", String.class),
        r.get("expected_consistent", Boolean.class),
        r.get("expected_severity", String.class),
        r.get("reason", String.class),
        r.get("enabled", Boolean.class),
        r.get("created_at", OffsetDateTime.class));
  }

  // ================================================================ 校验

  /**
   * 对一张订单跑一次地址与支付校验。
   *
   * <p>写入顺序固定为「先插头 → 再写七行明细」：V210 的合计触发器挂在<b>第七行落下</b>时，
   * 头插入那一刻一行明细都还没有。
   *
   * <p>判定基准时刻恒等于订单的 {@code submitted_at}，不是 {@code now()} ——
   * 同一张单重复跑一万次，七项结论逐位相同。禁运清单的生效期也相对这个时刻判。
   */
  @Transactional
  public AddressPaymentCheckSaved assess(AddressPaymentCheckInput input, UUID actorId) {
    if (input == null || input.orderId() == null) {
      throw bad("必须指明要校验的订单（orderId）");
    }
    AddressPaymentPolicy policy = policy();
    OrderFacts facts = orderFacts(input.orderId());
    UUID tenant = currentTenant();
    UUID checkId = UUID.randomUUID();

    // 头行先按「全部缺参照」插入：合计 CHECK 要求插入时就自洽，
    // 而真正的数字由第七行落下时的触发器算完再回填。
    sql.execute(
        "insert into order_address_check(id,tenant_id,order_id,order_no,order_source,order_currency,"
            + "order_amount,order_submitted_at,payment_method,payment_status,payment_status_source,"
            + "payment_status_observed_at,shipping_country_code,shipping_postcode,shipping_street,"
            + "policy_id,policy_version,policy_name,assessed_reference_at,delivery_status,risk_level,"
            + "cod_flagged,item_count,flagged_count,cleared_count,no_reference_count,"
            + "upstream_unavailable_count,not_applicable_count,blocked_count,warn_count,critical_count,"
            + "created_by,created_at)"
            + " values(?,?,?,?,?,?,?,?::timestamptz,?,?,?,?::timestamptz,?,?,?,?,?,?,?::timestamptz,"
            + "'DELIVERABLE','LOW',false,7,0,0,7,0,0,0,0,0,?,now())",
        checkId,
        tenant,
        facts.orderId(),
        facts.orderNo(),
        facts.orderSource(),
        facts.currency(),
        money(facts.amount()),
        facts.submittedAt(),
        facts.paymentMethod(),
        input.paymentStatus() == null ? null : input.paymentStatus().name(),
        input.paymentStatusSource() == null ? null : input.paymentStatusSource().name(),
        input.paymentStatusObservedAt(),
        facts.countryCode(),
        nz(facts.postcode()),
        nz(facts.street()),
        policy.id(),
        policy.version(),
        policy.policyName(),
        facts.submittedAt(),
        actorId);

    // ---- 七项恒定各占一行（缺失与未接通也占行）
    writeItem(checkId, tenant, embargoItem(facts));
    writeItem(checkId, tenant, postcodeItem(facts));
    writeItem(checkId, tenant, streetItem(facts));
    writeItem(checkId, tenant, requiredContactItem(facts));
    writeItem(checkId, tenant, paymentItem(facts, input));
    writeItem(checkId, tenant, codRiskItem(facts));
    writeItem(checkId, tenant, codInsuranceItem(facts));

    AddressPaymentCheckDetail detail = check(checkId);
    return new AddressPaymentCheckSaved(
        detail, "已落一次地址与支付校验：风险等级 " + detail.check().riskLevel() + "，校验结果 "
            + detail.check().deliveryStatus());
  }

  /** 校验结果列表（真分页 + 真筛选），带不受分页影响的汇总。 */
  public AddressPaymentCheckPage pageChecks(
      String keyword, String riskLevel, String deliveryStatus, UUID orderId, int page, int size) {
    int pageSize = pageSize(size);
    int pageNo = Math.max(0, page);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = " + TENANT);
    if (notBlank(keyword)) {
      where.add("(order_no ilike ?)");
      binds.add("%" + keyword.trim() + "%");
    }
    if (notBlank(riskLevel)) {
      where.add("risk_level = ?");
      binds.add(riskLevel.trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(deliveryStatus)) {
      where.add("delivery_status = ?");
      binds.add(deliveryStatus.trim().toUpperCase(Locale.ROOT));
    }
    if (orderId != null) {
      where.add("order_id = ?");
      binds.add(orderId);
    }
    String sqlWhere = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) c from order_address_check c" + sqlWhere, binds.toArray())
            .get("c", Long.class);
    List<AddressPaymentCheck> items =
        sql.fetch(
                "select c.* from order_address_check c"
                    + sqlWhere
                    + " order by c.assessed_reference_at desc, c.id desc limit ? offset ?",
                append(binds, pageSize, (long) pageNo * pageSize))
            .map(AddressPaymentCheckService::checkRow);
    return new AddressPaymentCheckPage(items, total, pageNo, pageSize, ceil(total, pageSize));
  }

  /** 校验详情：头 + 七行明细。跨租户在 RLS 下查不到 → 404。 */
  public AddressPaymentCheckDetail check(UUID checkId) {
    Record head =
        sql.fetchOne("select * from order_address_check where tenant_id = " + TENANT + " and id = ?", checkId);
    if (head == null) {
      throw notFound("校验记录不存在或不属于本租户：" + checkId);
    }
    List<AddressPaymentCheckItem> items =
        sql.fetch(
                "select i.* from order_address_check_item i"
                    + " where i.tenant_id = " + TENANT + " and i.check_id = ?"
                    + " order by i.item_code",
                checkId)
            .map(AddressPaymentCheckService::itemRow);
    return new AddressPaymentCheckDetail(checkRow(head), items);
  }

  /**
   * 某订单**最近一次**校验结果 —— <b>9.2.1.3 异常拦截的读路径</b>。
   *
   * <p>本模块的结果要能被 9.2.1.3 读到，而 9.2.1.3 与本模块在<b>同一个服务、同一个库</b>；
   * 但**跨服务**的消费者（例如 10 物流的渠道可用性）必须走这个<b>授权 API</b>，
   * 而不是跨库读表（AGENTS.md 模块边界纪律：禁止跨服务数据库访问）。
   *
   * <p>返回 404 而不是空对象：该订单**从未校验过**与「校验结果是空」是两件事，
   * 混起来会让调用方把「没查过」当成「查过了、没问题」。
   */
  public AddressPaymentCheckDetail latestForOrder(UUID orderId) {
    Record head =
        sql.fetchOne(
            "select * from order_address_check where tenant_id = " + TENANT + " and order_id = ?"
                + " order by assessed_reference_at desc, id desc limit 1",
            orderId);
    if (head == null) {
      throw notFound("该订单还没有跑过地址与支付校验：" + orderId);
    }
    return check(head.get("id", UUID.class));
  }

  private void writeItem(UUID checkId, UUID tenant, DraftItem draft) {
    sql.execute(
        "insert into order_address_check_item(id,tenant_id,check_id,item_code,status,severity,"
            + "evidence_source,observed_value,threshold_value,match_count,evidence_reference,explanation)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        tenant,
        checkId,
        draft.code().name(),
        draft.status().name(),
        draft.severity().name(),
        draft.source().name(),
        draft.observedValue(),
        draft.thresholdValue(),
        draft.matchCount(),
        nz(draft.evidenceReference()),
        draft.explanation());
  }

  // ------------------------------------------------------------ 七项判定

  /** 收货国是否在禁运清单。 */
  private DraftItem embargoItem(OrderFacts facts) {
    if (facts.countryCode() == null) {
      return DraftItem.notApplicable(
          AddressPaymentItemCode.COUNTRY_EMBARGO,
          AddressPaymentEvidenceSource.ADDRESS_SNAPSHOT,
          "这一单没有收货地址快照：收货国无从判定");
    }
    Record rule =
        sql.fetchOne(
            "select * from order_address_check_embargo_rule where tenant_id = "
                + TENANT
                + " and country_code = ? and enabled"
                + " and (effective_from is null or effective_from <= ?::timestamptz)"
                + " and (effective_to is null or effective_to >= ?::timestamptz)",
            facts.countryCode(),
            facts.submittedAt(),
            facts.submittedAt());
    if (rule == null) {
      return DraftItem.cleared(
          AddressPaymentItemCode.COUNTRY_EMBARGO,
          AddressPaymentEvidenceSource.TENANT_EMBARGO_LIST,
          facts.countryCode() + " 不在本租户的禁运国家清单里");
    }
    AddressPaymentEmbargoLevel level =
        AddressPaymentEmbargoLevel.valueOf(rule.get("embargo_level", String.class));
    return new DraftItem(
        AddressPaymentItemCode.COUNTRY_EMBARGO,
        AddressPaymentItemStatus.FLAGGED,
        level == AddressPaymentEmbargoLevel.BLOCK
            ? AddressPaymentItemSeverity.BLOCK
            : AddressPaymentItemSeverity.WARN,
        AddressPaymentEvidenceSource.TENANT_EMBARGO_LIST,
        BigDecimal.ONE,
        BigDecimal.ZERO,
        1,
        facts.countryCode() + "（禁运强度 " + level.name() + "）",
        "收货国 " + facts.countryCode() + " 在禁运清单里："
            + nz(rule.get("reason", String.class)));
  }

  /** 邮编是否符合该国规则。 */
  private DraftItem postcodeItem(OrderFacts facts) {
    CountryRule rule = countryRule(facts);
    if (rule == null) {
      return DraftItem.noReference(
          AddressPaymentItemCode.POSTCODE_FORMAT,
          AddressPaymentEvidenceSource.TENANT_COUNTRY_RULE,
          facts.countryCode() == null
              ? "这一单没有收货地址快照，且该国没有登记地址格式规则：邮编无从判定"
              : "本租户没有登记 " + facts.countryCode() + " 的地址格式规则：邮编规则缺失，本项判不了");
    }
    if (!rule.postcodeRequired()) {
      return DraftItem.notApplicable(
          AddressPaymentItemCode.POSTCODE_FORMAT,
          AddressPaymentEvidenceSource.TENANT_COUNTRY_RULE,
          facts.countryCode() + " 不使用统一邮编，本项对该国不适用");
    }
    String postcode = nz(facts.postcode()).trim();
    if (postcode.isEmpty()) {
      return new DraftItem(
          AddressPaymentItemCode.POSTCODE_FORMAT,
          AddressPaymentItemStatus.FLAGGED,
          AddressPaymentItemSeverity.BLOCK,
          AddressPaymentEvidenceSource.TENANT_COUNTRY_RULE,
          BigDecimal.ONE,
          BigDecimal.ZERO,
          1,
          "邮编为空",
          "收货国 " + facts.countryCode() + " 要求邮编（" + nz(rule.postcodeNote()) + "），而这一单没有填");
    }
    if (!rule.postcodePattern().matcher(postcode).matches()) {
      return new DraftItem(
          AddressPaymentItemCode.POSTCODE_FORMAT,
          AddressPaymentItemStatus.FLAGGED,
          AddressPaymentItemSeverity.BLOCK,
          AddressPaymentEvidenceSource.TENANT_COUNTRY_RULE,
          BigDecimal.ONE,
          BigDecimal.ZERO,
          1,
          "邮编 " + postcode,
          "邮编 " + postcode + " 不符合 " + facts.countryCode() + " 的邮编规则（"
              + nz(rule.postcodeNote()) + "）：无法按该国规则投递");
    }
    return DraftItem.cleared(
        AddressPaymentItemCode.POSTCODE_FORMAT,
        AddressPaymentEvidenceSource.TENANT_COUNTRY_RULE,
        "邮编 " + postcode + " 符合 " + facts.countryCode() + " 的邮编规则");
  }

  /** 街道是否符合该国规则。 */
  private DraftItem streetItem(OrderFacts facts) {
    CountryRule rule = countryRule(facts);
    if (rule == null) {
      return DraftItem.noReference(
          AddressPaymentItemCode.STREET_FORMAT,
          AddressPaymentEvidenceSource.TENANT_COUNTRY_RULE,
          facts.countryCode() == null
              ? "这一单没有收货地址快照，且该国没有登记地址格式规则：街道无从判定"
              : "本租户没有登记 " + facts.countryCode() + " 的地址格式规则：街道规则缺失，本项判不了");
    }
    if (!rule.streetRequired()) {
      return DraftItem.notApplicable(
          AddressPaymentItemCode.STREET_FORMAT,
          AddressPaymentEvidenceSource.TENANT_COUNTRY_RULE,
          facts.countryCode() + " 的地址不要求街道行，本项对该国不适用");
    }
    String street = nz(facts.street()).trim();
    if (street.isEmpty()) {
      return new DraftItem(
          AddressPaymentItemCode.STREET_FORMAT,
          AddressPaymentItemStatus.FLAGGED,
          AddressPaymentItemSeverity.BLOCK,
          AddressPaymentEvidenceSource.TENANT_COUNTRY_RULE,
          BigDecimal.ONE,
          BigDecimal.ZERO,
          1,
          "街道为空",
          "收货国 " + facts.countryCode() + " 要求街道行，而这一单没有填");
    }
    if (street.length() < rule.streetMinLength()) {
      return new DraftItem(
          AddressPaymentItemCode.STREET_FORMAT,
          AddressPaymentItemStatus.FLAGGED,
          AddressPaymentItemSeverity.BLOCK,
          AddressPaymentEvidenceSource.TENANT_COUNTRY_RULE,
          BigDecimal.ONE,
          BigDecimal.valueOf(rule.streetMinLength()),
          1,
          "街道 " + street.length() + " 个字符",
          "街道只有 " + street.length() + " 个字符，少于该国要求的最小 " + rule.streetMinLength()
              + " 个字符：像是占位符而不是真实地址");
    }
    return DraftItem.cleared(
        AddressPaymentItemCode.STREET_FORMAT,
        AddressPaymentEvidenceSource.TENANT_COUNTRY_RULE,
        "街道 " + street.length() + " 个字符，满足该国最小 " + rule.streetMinLength() + " 个字符的要求");
  }

  /** 必填联系方式是否齐全（按该国规则）。 */
  private DraftItem requiredContactItem(OrderFacts facts) {
    CountryRule rule = countryRule(facts);
    if (rule == null) {
      return DraftItem.noReference(
          AddressPaymentItemCode.REQUIRED_CONTACT,
          AddressPaymentEvidenceSource.TENANT_COUNTRY_RULE,
          facts.countryCode() == null
              ? "这一单没有收货地址快照，且该国没有登记地址格式规则：必填项无从判定"
              : "本租户没有登记 " + facts.countryCode() + " 的地址格式规则：必填项规则缺失，本项判不了");
    }
    List<String> missing = new ArrayList<>();
    if (nz(facts.recipientName()).trim().isEmpty()) {
      missing.add("收件人姓名");
    }
    if (rule.phoneRequired() && nz(facts.recipientPhone()).trim().isEmpty()) {
      missing.add("收件人电话");
    }
    if (rule.cityRequired() && nz(facts.city()).trim().isEmpty()) {
      missing.add("城市");
    }
    if (rule.regionRequired() && nz(facts.province()).trim().isEmpty()) {
      missing.add("省份/州");
    }
    if (missing.isEmpty()) {
      return DraftItem.cleared(
          AddressPaymentItemCode.REQUIRED_CONTACT,
          AddressPaymentEvidenceSource.TENANT_COUNTRY_RULE,
          "收件人姓名" + (rule.phoneRequired() ? "、电话" : "") + (rule.cityRequired() ? "、城市" : "")
              + (rule.regionRequired() ? "、省份/州" : "") + " 齐全");
    }
    return new DraftItem(
        AddressPaymentItemCode.REQUIRED_CONTACT,
        AddressPaymentItemStatus.FLAGGED,
        AddressPaymentItemSeverity.BLOCK,
        AddressPaymentEvidenceSource.TENANT_COUNTRY_RULE,
        BigDecimal.valueOf(missing.size()),
        BigDecimal.ZERO,
        missing.size(),
        String.join("、", missing),
        "缺 " + missing.size() + " 项必填信息（" + String.join("、", missing) + "）：无法按该国规则投递");
  }

  /** 支付状态与付款方式是否自洽。 */
  private DraftItem paymentItem(OrderFacts facts, AddressPaymentCheckInput input) {
    if (input.paymentStatus() == null) {
      return DraftItem.noReference(
          AddressPaymentItemCode.PAYMENT_CONSISTENCY,
          AddressPaymentEvidenceSource.CALLER_PAYMENT_STATUS,
          "本服务没有支付状态这个事实（12 财务未交付），而调用方没有提供支付状态观察值："
              + "本项判不了，**不是**「一致」");
    }
    String method = facts.paymentMethod();
    String status = input.paymentStatus().name();
    Record rule =
        sql.fetchOne(
            "select * from order_address_check_payment_rule where tenant_id = "
                + TENANT
                + " and payment_method = ? and observed_status = ? and enabled",
            method,
            status);
    if (rule == null) {
      return DraftItem.noReference(
          AddressPaymentItemCode.PAYMENT_CONSISTENCY,
          AddressPaymentEvidenceSource.TENANT_PAYMENT_RULE,
          "本租户没有登记 (" + method + ", " + status + ") 这条支付一致性规则：本项判不了，**不是**「一致」");
    }
    if (rule.get("expected_consistent", Boolean.class)) {
      return DraftItem.cleared(
          AddressPaymentItemCode.PAYMENT_CONSISTENCY,
          AddressPaymentEvidenceSource.TENANT_PAYMENT_RULE,
          nz(rule.get("reason", String.class)));
    }
    AddressPaymentItemSeverity severity =
        switch (rule.get("expected_severity", String.class)) {
          case "BLOCK" -> AddressPaymentItemSeverity.BLOCK;
          case "WARN" -> AddressPaymentItemSeverity.WARN;
          default -> AddressPaymentItemSeverity.WARN;
        };
    return new DraftItem(
        AddressPaymentItemCode.PAYMENT_CONSISTENCY,
        AddressPaymentItemStatus.FLAGGED,
        severity,
        AddressPaymentEvidenceSource.TENANT_PAYMENT_RULE,
        BigDecimal.ONE,
        BigDecimal.ZERO,
        1,
        "(" + method + ", " + status + ")",
        nz(rule.get("reason", String.class)));
  }

  /**
   * 货到付款 → 最高履约风险。
   *
   * <p>加粗句「货到付款订单的履约风险最高」的落点：这一项恒为 CRITICAL 命中，
   * 而 CRITICAL 这一档<b>只有</b>这一项能用（V210 表级 CHECK 钉死）。
   */
  private DraftItem codRiskItem(OrderFacts facts) {
    if (!"COD".equals(facts.paymentMethod())) {
      return DraftItem.notApplicable(
          AddressPaymentItemCode.COD_FULFILLMENT_RISK,
          AddressPaymentEvidenceSource.ORDER_PAYMENT_METHOD,
          "这一单的付款方式是 " + facts.paymentMethod() + "，不是货到付款：本项不适用");
    }
    return new DraftItem(
        AddressPaymentItemCode.COD_FULFILLMENT_RISK,
        AddressPaymentItemStatus.FLAGGED,
        AddressPaymentItemSeverity.CRITICAL,
        AddressPaymentEvidenceSource.ORDER_PAYMENT_METHOD,
        BigDecimal.ONE,
        BigDecimal.ZERO,
        1,
        facts.paymentMethod(),
        "货到付款：履约风险最高（收不到钱的风险），风险等级恒为 CRITICAL。"
            + "额外风控走 9.2.2.2 的风控评估，保险策略本系统未交付（见下一项）");
  }

  /**
   * 货到付款保险策略 —— <b>本项恒为未交付</b>。
   *
   * <p>蓝图加粗句要求「需额外风控与保险策略」，而保险策略在蓝图中**没有任何 L4 模块持有**。
   * 本模块**不假装**能算保费、能推荐投保方案 ⇒ 这一项对货到付款单恒为
   * UPSTREAM_UNAVAILABLE，非货到付款单则为 NOT_APPLICABLE。
   */
  private DraftItem codInsuranceItem(OrderFacts facts) {
    if (!"COD".equals(facts.paymentMethod())) {
      return DraftItem.notApplicable(
          AddressPaymentItemCode.COD_INSURANCE_POLICY,
          AddressPaymentEvidenceSource.MODULE_10_INSURANCE_NOT_DELIVERED,
          "这一单不是货到付款，不需要货到付款保险策略：本项不适用");
    }
    return DraftItem.upstreamUnavailable(
        AddressPaymentItemCode.COD_INSURANCE_POLICY,
        AddressPaymentEvidenceSource.MODULE_10_INSURANCE_NOT_DELIVERED,
        "货到付款的保险策略在本系统里**未交付**：蓝图中没有任何 L4 模块持有它"
            + "（卷 10 的 10.1.1.2 持有的是渠道保价规则，不是货到付款的保险策略）。"
            + "⇒ 本项恒为未交付，**不是**「保险没问题」。");
  }

  // ------------------------------------------------------------ 读取订单事实

  /** 订单事实：付款方式来自 sales_order，地址来自收货地址快照（role = SHIPPING）。 */
  private OrderFacts orderFacts(UUID orderId) {
    Record r =
        sql.fetchOne(
            "select o.id, o.order_no, o.order_source, o.currency, o.grand_total_amount,"
                + " o.submitted_at, o.payment_method,"
                + " a.country_code, a.postcode, a.address_line1, a.city, a.province,"
                + " a.recipient_name, a.recipient_phone"
                + " from sales_order o"
                + " left join order_address_snapshot a"
                + "   on a.tenant_id = o.tenant_id and a.order_id = o.id"
                + "   and a.address_role = 'SHIPPING'"
                + " where o.tenant_id = " + TENANT + " and o.id = ?",
            orderId);
    if (r == null) {
      throw notFound("订单不存在或不属于本租户：" + orderId);
    }
    OffsetDateTime submittedAt = r.get("submitted_at", OffsetDateTime.class);
    if (submittedAt == null) {
      throw bad("订单 " + r.get("order_no", String.class) + " 还没提交（submitted_at 为空）："
          + "未提交的订单不参与地址与支付校验");
    }
    return new OrderFacts(
        r.get("id", UUID.class),
        r.get("order_no", String.class),
        r.get("order_source", String.class),
        r.get("currency", String.class),
        r.get("grand_total_amount", BigDecimal.class),
        submittedAt,
        r.get("payment_method", String.class),
        r.get("country_code", String.class),
        r.get("postcode", String.class),
        r.get("address_line1", String.class),
        r.get("city", String.class),
        r.get("province", String.class),
        r.get("recipient_name", String.class),
        r.get("recipient_phone", String.class));
  }

  /** 该收货国的地址格式规则（没有就是 null ⇒ 该项缺参照）。 */
  private CountryRule countryRule(OrderFacts facts) {
    if (facts.countryCode() == null) {
      return null;
    }
    Record r =
        sql.fetchOne(
            "select * from order_address_check_country_rule where tenant_id = "
                + TENANT
                + " and country_code = ? and enabled",
            facts.countryCode());
    if (r == null) {
      return null;
    }
    String pattern = nz(r.get("postcode_pattern", String.class));
    return new CountryRule(
        r.get("country_code", String.class),
        r.get("postcode_required", Boolean.class),
        pattern.isEmpty() ? Pattern.compile("^$") : Pattern.compile(pattern),
        r.get("postcode_note", String.class),
        r.get("street_required", Boolean.class),
        r.get("street_min_length", Integer.class),
        r.get("city_required", Boolean.class),
        r.get("region_required", Boolean.class),
        r.get("phone_required", Boolean.class));
  }

  // ------------------------------------------------------------ 行映射

  private static AddressPaymentCheck checkRow(Record r) {
    return new AddressPaymentCheck(
        r.get("id", UUID.class),
        r.get("order_id", UUID.class),
        r.get("order_no", String.class),
        r.get("order_source", String.class),
        r.get("order_currency", String.class),
        r.get("order_amount", BigDecimal.class),
        r.get("order_submitted_at", OffsetDateTime.class),
        r.get("payment_method", String.class),
        r.get("payment_status", String.class),
        r.get("payment_status_source", String.class),
        r.get("payment_status_observed_at", OffsetDateTime.class),
        r.get("shipping_country_code", String.class),
        r.get("shipping_postcode", String.class),
        r.get("shipping_street", String.class),
        r.get("policy_name", String.class),
        r.get("policy_version", Long.class),
        r.get("assessed_reference_at", OffsetDateTime.class),
        r.get("delivery_status", String.class),
        r.get("risk_level", String.class),
        r.get("cod_flagged", Boolean.class),
        r.get("item_count", Integer.class),
        r.get("flagged_count", Integer.class),
        r.get("cleared_count", Integer.class),
        r.get("no_reference_count", Integer.class),
        r.get("upstream_unavailable_count", Integer.class),
        r.get("not_applicable_count", Integer.class),
        r.get("blocked_count", Integer.class),
        r.get("warn_count", Integer.class),
        r.get("critical_count", Integer.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static AddressPaymentCheckItem itemRow(Record r) {
    AddressPaymentItemCode code = AddressPaymentItemCode.valueOf(r.get("item_code", String.class));
    return new AddressPaymentCheckItem(
        code.name(),
        code.label(),
        r.get("status", String.class),
        r.get("severity", String.class),
        r.get("evidence_source", String.class),
        r.get("observed_value", BigDecimal.class),
        r.get("threshold_value", BigDecimal.class),
        r.get("match_count", Integer.class),
        r.get("evidence_reference", String.class),
        r.get("explanation", String.class));
  }

  // ================================================================ 汇总

  /** 全量汇总（不受筛选与分页影响）。 */
  public AddressPaymentOverview overview() {
    Record r =
        sql.fetchOne(
            "select count(*) total,"
                + " count(*) filter (where cod_flagged) cod,"
                + " count(*) filter (where delivery_status='NOT_DELIVERABLE') not_deliverable,"
                + " count(*) filter (where delivery_status='UNDETERMINED') undetermined,"
                + " count(*) filter (where risk_level='CRITICAL') critical,"
                + " count(*) filter (where risk_level='HIGH') high,"
                + " max(assessed_reference_at) last_at"
                + " from order_address_check where tenant_id = " + TENANT);
    return new AddressPaymentOverview(
        r.get("total", Long.class),
        r.get("cod", Long.class),
        r.get("not_deliverable", Long.class),
        r.get("undetermined", Long.class),
        r.get("critical", Long.class),
        r.get("high", Long.class),
        count("order_address_check_embargo_rule"),
        count("order_address_check_country_rule"),
        count("order_address_check_payment_rule"),
        r.get("last_at", OffsetDateTime.class));
  }

  private long count(String table) {
    return sql.fetchOne("select count(*) c from " + table + " where tenant_id = " + TENANT)
        .get("c", Long.class);
  }

  // ================================================================ 内部模型

  /** 订单事实快照。 */
  private record OrderFacts(
      UUID orderId,
      String orderNo,
      String orderSource,
      String currency,
      BigDecimal amount,
      OffsetDateTime submittedAt,
      String paymentMethod,
      String countryCode,
      String postcode,
      String street,
      String city,
      String province,
      String recipientName,
      String recipientPhone) {}

  /** 收货国的地址格式规则（已编译好的邮编正则）。 */
  private record CountryRule(
      String countryCode,
      boolean postcodeRequired,
      Pattern postcodePattern,
      String postcodeNote,
      boolean streetRequired,
      int streetMinLength,
      boolean cityRequired,
      boolean regionRequired,
      boolean phoneRequired) {}

  /**
   * 待写入的明细行。
   *
   * <p><b>observed_value 的口径</b>：观测到的**问题数**（0 = 没问题），
   * threshold_value = 容许的问题数上限（恒为 0）。
   * 不可用的项 observed_value 为 {@code null} —— 给 0 等于宣称「量过了，结果是 0」。
   */
  private record DraftItem(
      AddressPaymentItemCode code,
      AddressPaymentItemStatus status,
      AddressPaymentItemSeverity severity,
      AddressPaymentEvidenceSource source,
      BigDecimal observedValue,
      BigDecimal thresholdValue,
      int matchCount,
      String evidenceReference,
      String explanation) {

    static DraftItem cleared(
        AddressPaymentItemCode code, AddressPaymentEvidenceSource source, String explanation) {
      return new DraftItem(
          code,
          AddressPaymentItemStatus.CLEARED,
          AddressPaymentItemSeverity.INFO,
          source,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          0,
          "",
          explanation);
    }

    static DraftItem noReference(
        AddressPaymentItemCode code, AddressPaymentEvidenceSource source, String explanation) {
      return new DraftItem(
          code,
          AddressPaymentItemStatus.NO_REFERENCE,
          AddressPaymentItemSeverity.INFO,
          source,
          null,
          null,
          0,
          "",
          explanation);
    }

    static DraftItem notApplicable(
        AddressPaymentItemCode code, AddressPaymentEvidenceSource source, String explanation) {
      return new DraftItem(
          code,
          AddressPaymentItemStatus.NOT_APPLICABLE,
          AddressPaymentItemSeverity.INFO,
          source,
          null,
          null,
          0,
          "",
          explanation);
    }

    static DraftItem upstreamUnavailable(
        AddressPaymentItemCode code, AddressPaymentEvidenceSource source, String explanation) {
      return new DraftItem(
          code,
          AddressPaymentItemStatus.UPSTREAM_UNAVAILABLE,
          AddressPaymentItemSeverity.INFO,
          source,
          null,
          null,
          0,
          "",
          explanation);
    }
  }

  // ================================================================ 工具

  private static int pageSize(int requested) {
    return Math.min(MAX_PAGE_SIZE, Math.max(1, requested <= 0 ? 50 : requested));
  }

  private static int ceil(long total, int size) {
    return (int) Math.ceil(total / (double) size);
  }

  private static Object[] append(List<Object> binds, Object... more) {
    List<Object> all = new ArrayList<>(binds);
    all.addAll(List.of(more));
    return all.toArray();
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  /** 金额刻度：4 位，HALF_UP（与 DB 的 numeric(19,4) 对齐）。 */
  private static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /** 正则先编译一遍：写进去一条编译不过的正则，该国的邮编项会永远判不了。 */
  private static void compileOrBad(String pattern) {
    try {
      Pattern.compile(pattern);
    } catch (PatternSyntaxException e) {
      throw bad("邮编正则编译不过：" + e.getMessage());
    }
  }

  /** 当前 RLS 租户。由 backend/libraries/tenancy 在事务内设置，客户端头改不动它。 */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }
}
