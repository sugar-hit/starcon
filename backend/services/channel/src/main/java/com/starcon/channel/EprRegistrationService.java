package com.starcon.channel;

import static com.starcon.channel.EprRegistrationModels.*;
import static com.starcon.channel.EprRegistrationRules.*;

import com.starcon.channel.EprRegistrationRules.FilingWindow;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 16.3.2.2 EPR/包装法。
 *
 * <p>蓝图：「管理 EPR 与包装法合规（德国 LUCID、包装注册、电池法、WEEE 等）：注册号、适用国家与品类、
 * 申报周期与关联费用。输入：注册信息；适用规则；申报数据。输出：EPR 档案 + 合规状态 + 费用记录。
 * 关系：上游 = 16.3.1.1；下游 = 12 财务（费用）、4 刊登（是否允许在相关国家销售）。
 * <b>EPR 未注册的国家应直接限制刊登</b>，这是避免下架与罚款最有效的做法。」
 *
 * <p>🔴🔴 本类里有六处<b>结构</b>上的不变量：
 *
 * <ol>
 *   <li><b>判定只有一个来源</b>：{@code epr_gate_findings(tenant, country, category)}
 *       是 DB 里的一个函数，服务层的 {@code publish-gate} 端点与 DB 层的刊登触发器
 *       <b>调同一个函数</b> ⇒ 页面上的结论与真正拦人的那道闸门不可能各说各话。</li>
 *   <li><b>「不适用」与「未注册」是两件事</b>：某国不要求某制度 ⇒ 放行；
 *       要求了但没注册 ⇒ 阻断。混为一谈会把不该拦的全拦了。</li>
 *   <li><b>「判不了」不许默认放行</b>：没记有效期、没有生效版本、登记已停用 ⇒
 *       第三态（INDETERMINATE）⇒ <b>仍然阻断</b>，但原因与「明确未注册」分开列。</li>
 *   <li><b>登记是版本化的</b>：改一条登记 = 关掉当前版本 + 插入新版本 ⇒
 *       「历史判定按当时那版算」物理成立；<b>不许回填</b>（生效时刻不得早于当下）。</li>
 *   <li><b>上游 16.3.1.1 只读</b>：本模块**不写** {@code tax_registration_record}，
 *       也**不把**上游那个辖区级 {@code epr_registration_no} 复制一份当自己的权威源 ——
 *       两者是「粗 / 细」两层。</li>
 *   <li><b>本模块不含财务凭证</b>：费用只是**本服务自己的费用记录**；
 *       12 财务在本仓尚未落地 ⇒ 不建凭证表、不声称已入账；金额一律 BigDecimal + 按币种分组。</li>
 * </ol>
 */
@Service
public class EprRegistrationService {

  /** 判定口径（能力声明与合规状态共用同一句话）。 */
  private static final String APPLICABILITY_SEMANTICS =
      "🔴 判定有**三种**结果，不是两种："
          + "**NOT_APPLICABLE（不适用）** —— 该国根本不要求该制度（不在参考表里），"
          + "或租户显式声明「该制度在我的业务里不适用」（有理由、有生效时间）⇒ **放行**；"
          + "**NOT_REGISTERED（未注册）** —— 该国要求该制度但没注册 ⇒ **阻断**；"
          + "**判不了（INDETERMINATE）** —— 登记没记有效期 / 当前没有生效版本 / 登记已停用 "
          + "⇒ **仍然阻断**（不许默认放行），但原因单独列，界面能说清「这是判不了，不是已知违规」。"
          + "把 NOT_APPLICABLE 当成未注册，会把不该拦的全拦了。";

  /** 制度语义（制度级 vs 上游辖区级）。 */
  private static final String REGIME_SEMANTICS =
      "🔴 制度的值域**钉死在 DB 的 CHECK 里**（LUCID / PACKAGING_REGISTRATION / BATTERY / WEEE），"
          + "登记档案上的 regime_code 由触发器校验必须落在码表里 —— **不留自由文本**。"
          + "本模块的粒度是**制度 × 适用国家 × 适用品类**：同一个国家下 LUCID / 包装注册 / 电池法 / WEEE "
          + "各有各的注册号、各有各的申报周期与费用。";

  /** 申报期语义。 */
  private static final String FILING_SEMANTICS =
      "🔴 申报周期是**受控枚举**（月度 / 季度 / 年度 / 一次性），"
          + "配合**起止月份**（可跨年，例 11..2）算出「当前是否在申报期内」+「距下一期还有几天」。"
          + "没记起止月份是**判不了**（UNKNOWN），**不是**「不在申报期内」。"
          + "16.3.1.2 申报日历未交付 ⇒ 本模块只算窗口，**不做日历、不做截止日提醒**。";

  /** 费用语义。 */
  private static final String FEE_SEMANTICS =
      "🔴 费用是**本服务自己的费用记录**（谁付的、哪个周期、多少钱、什么币种），"
          + "**不是**财务凭证 —— 12 财务在本仓**尚未落地** ⇒ 本模块**不建凭证表、不声称费用已入账**。"
          + "金额一律 BigDecimal + NUMERIC（**禁浮点**），**币种必带**，"
          + "汇总**按币种分组** —— **不同币种绝不直接相加**（EUR + USD 不是钱）。";

  /** 上游说明。 */
  private static final String UPSTREAM_NOTE =
      "上游 = 16.3.1.1 税号与辖区（**同库只读**）。"
          + "🔴 本模块**不写** `tax_registration_record`（上游写所有权不变），"
          + "也**不把**上游那个辖区级 `epr_registration_no` 复制一份当自己的权威源 —— "
          + "上游是**辖区级**（一个辖区一个号）的**粗**粒度登记，本模块是**制度级**的**细**粒度档案，"
          + "两者是**粗 / 细两层**，不是同一份事实的两个副本。"
          + "本模块另外只读本服务自己的 `shop` / `shop_marketplace` / `marketplace`（店铺与站点目录），"
          + "**不写死店铺常量、不造平行店铺表**。";

  /** 下游说明。 */
  private static final String DOWNSTREAM_NOTE =
      "下游 = 12 财务（**finance 服务在本仓尚未落地** ⇒ 本模块**不建财务凭证表**、不声称费用已入账；"
          + "费用只是本服务自己的费用记录）、"
          + "4 刊登（**已交付** ⇒ 闸门在**本服务**的两个真实放行口上落地，见 enforcementNote）。"
          + "16.3.2.1 VAT/IOSS 与 16.3.2.3 销售税**未交付** ⇒ 本模块**不编造**它们的清单与统计。";

  /** 落地说明（闸门挂在哪两个放行口上）。 */
  private static final String ENFORCEMENT_NOTE =
      "🔴 「EPR 未注册的国家应直接限制刊登」落成**两道**："
          + "① **服务端闸门**：`POST /channel/epr-registration/publish-gate` 真算一遍，"
          + "结论 `allowed=false` + 逐条原因（哪个制度 / 哪个国家 / 没注册 / 有效期已过）；"
          + "② **DB 层强制**：BEFORE 触发器挂在 4 刊登的两个**真实放行口**上 —— "
          + "`listing_publish_job_target`（4.3.1.1 一键刊登 → SUBMITTED = 真发给平台）与 "
          + "`publish_task`（4.3.1.2 批量发布 → DISPATCHED = 真派发）。"
          + "两道**共用同一个判定函数** `epr_gate_findings` ⇒ 页面上的结论与真正拦人的闸门不可能各说各话。"
          + "**前端显示不是防线**：绕过前端直接调刊登端点，数据库也不会让内容进发布通道。";

  private final DSLContext sql;

  public EprRegistrationService(DSLContext sql) {
    this.sql = sql;
  }

  // ==================================================================
  // 小工具
  // ==================================================================

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
  }

  private static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(String.valueOf(raw));
  }

  /** 🔴 <b>同源 DB 时钟</b>：判定时刻 / 创建时刻 / 申报期计算一律从这里取。 */
  private OffsetDateTime dbNow() {
    Object raw = sql.fetchValue("select now()");
    return raw instanceof OffsetDateTime value ? value : OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static UUID parseUuid(String raw) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** 🔴 非法 UUID 按「筛不出东西」处理而不是报错（筛选参数是用户敲进来的）。 */
  private static final UUID NO_SUCH_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

  private static UUID parseFilterUuid(String raw) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    UUID parsed = parseUuid(value);
    return parsed == null ? NO_SUCH_ID : parsed;
  }

  /**
   * 🔴 解析带偏移量的 ISO 时间串。
   *
   * <p>时间列是 {@code timestamptz}，值用 {@code OffsetDateTime} 绑定并在 values 位置写
   * {@code ?::timestamptz} —— 用 {@code Timestamp.valueOf(LocalDateTime...)} 会静默偏 −28800s。
   */
  private static OffsetDateTime parseTime(String raw, String field) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value);
    } catch (RuntimeException e) {
      throw unprocessable(
          field + " 不是合法时间（应为带偏移量的 ISO-8601，例如 2026-12-31T23:59:59+08:00）");
    }
  }

  private UUID currentActor() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.actor',true),'')");
    if (raw == null || String.valueOf(raw).isEmpty()) {
      return null;
    }
    try {
      return UUID.fromString(String.valueOf(raw));
    } catch (RuntimeException e) {
      return null;
    }
  }

  // ==================================================================
  // 内部形状
  // ==================================================================

  /** 一条登记版本行（含其适用国家与适用品类）。 */
  private record RegistrationRow(
      UUID id,
      UUID registrationKey,
      int versionNo,
      String regimeCode,
      String registrationNo,
      String status,
      String validityKind,
      OffsetDateTime validFrom,
      OffsetDateTime validUntil,
      String filingCycle,
      Integer filingStartMonth,
      Integer filingEndMonth,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveTo,
      List<String> countryCodes,
      List<String> categoryCodes,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 制度 + 它在哪些国家适用。 */
  private record RegimeFact(
      String code,
      String name,
      String authority,
      String defaultFilingCycle,
      List<String> categoryCodes) {}

  /** 一条判定结果（DB 函数 epr_gate_findings 的一行）。 */
  private record Finding(
      String regimeCode,
      String regimeName,
      String countryCode,
      String countryName,
      String categoryCode,
      String reasonCode,
      String detail) {}

  // ==================================================================
  // 参考数据
  // ==================================================================

  /** 制度码表（含「在哪些国家适用、适用哪些品类」）。 */
  private List<EprRegimeView> loadRegimes() {
    Map<String, RegimeFact> regimes = new LinkedHashMap<>();
    for (Record row :
        sql.fetch("select code, name, authority, default_filing_cycle from epr_regime order by position, code")) {
      regimes.put(
          row.get("code", String.class),
          new RegimeFact(
              row.get("code", String.class),
              row.get("name", String.class),
              row.get("authority", String.class),
              row.get("default_filing_cycle", String.class),
              List.of()));
    }

    Map<String, List<String>> categoriesByCountryRegime = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            """
            select cc.country_regime_id, cc.category_code
            from epr_country_regime_category cc
            order by cc.country_regime_id, cc.category_code
            """)) {
      categoriesByCountryRegime
          .computeIfAbsent(row.get("country_regime_id", UUID.class).toString(), key -> new ArrayList<>())
          .add(row.get("category_code", String.class));
    }

    Map<String, List<EprCountryRegimeView>> countriesByRegime = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            """
            select c.id, c.country_code, c.country_name, c.regime_code, c.authority
            from epr_country_regime c
            order by c.position, c.country_code, c.regime_code
            """)) {
      String regimeCode = row.get("regime_code", String.class);
      List<String> categoryCodes =
          List.copyOf(
              categoriesByCountryRegime.getOrDefault(
                  row.get("id", UUID.class).toString(), List.of()));
      countriesByRegime
          .computeIfAbsent(regimeCode, key -> new ArrayList<>())
          .add(
              new EprCountryRegimeView(
                  text(row.get("country_code", String.class)),
                  row.get("country_name", String.class),
                  regimeCode,
                  regimes.containsKey(regimeCode) ? regimes.get(regimeCode).name() : regimeCode,
                  row.get("authority", String.class),
                  categoryCodes));
    }

    List<EprRegimeView> items = new ArrayList<>();
    for (RegimeFact regime : regimes.values()) {
      items.add(
          new EprRegimeView(
              regime.code(),
              regime.name(),
              regime.authority(),
              regime.defaultFilingCycle(),
              List.copyOf(countriesByRegime.getOrDefault(regime.code(), List.of()))));
    }
    return items;
  }

  /** 制度码表（国家适用事实从 epr_country_regime 读，**不写死常量**）。 */
  private Map<String, RegimeFact> regimeFacts() {
    Map<String, RegimeFact> regimes = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select code, name, authority, default_filing_cycle from epr_regime order by position, code")) {
      regimes.put(
          row.get("code", String.class),
          new RegimeFact(
              row.get("code", String.class),
              row.get("name", String.class),
              row.get("authority", String.class),
              row.get("default_filing_cycle", String.class),
              List.of()));
    }
    return regimes;
  }

  /** 适用品类码表。 */
  private List<EprCategoryView> loadCategories() {
    List<EprCategoryView> items = new ArrayList<>();
    for (Record row : sql.fetch("select code, name from epr_category order by position, code")) {
      items.add(new EprCategoryView(row.get("code", String.class), row.get("name", String.class)));
    }
    return items;
  }

  /** 需要 EPR 的国家集合（参考表里出现过该国的国家码）。 */
  private Set<String> eprCountries() {
    Set<String> countries = new LinkedHashSet<>();
    for (Record row : sql.fetch("select distinct country_code from epr_country_regime")) {
      countries.add(text(row.get("country_code", String.class)));
    }
    return countries;
  }

  // ==================================================================
  // 登记档案
  // ==================================================================

  /** 载入登记版本行（可选只取当前生效版本）。 */
  private List<RegistrationRow> loadRegistrations(UUID tenant, boolean currentOnly) {
    Map<UUID, List<String>> countries = new LinkedHashMap<>();
    Map<UUID, List<String>> categories = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select registration_id, country_code from epr_registration_country where tenant_id = ? order by country_code",
            tenant)) {
      countries
          .computeIfAbsent(row.get("registration_id", UUID.class), key -> new ArrayList<>())
          .add(text(row.get("country_code", String.class)));
    }
    for (Record row :
        sql.fetch(
            "select registration_id, category_code from epr_registration_category where tenant_id = ? order by category_code",
            tenant)) {
      categories
          .computeIfAbsent(row.get("registration_id", UUID.class), key -> new ArrayList<>())
          .add(row.get("category_code", String.class));
    }

    StringBuilder statement =
        new StringBuilder(
            """
            select id, registration_key, version_no, regime_code, registration_no, status,
                   validity_kind, valid_from, valid_until, filing_cycle,
                   filing_start_month, filing_end_month, effective_from, effective_to,
                   note, created_at, updated_at
            from epr_registration
            where tenant_id = ?
            """);
    if (currentOnly) {
      statement.append(" and effective_to is null");
    }
    statement.append(" order by regime_code, registration_key, version_no desc");

    List<RegistrationRow> items = new ArrayList<>();
    for (Record row : sql.fetch(statement.toString(), tenant)) {
      UUID id = row.get("id", UUID.class);
      items.add(
          new RegistrationRow(
              id,
              row.get("registration_key", UUID.class),
              row.get("version_no", Integer.class),
              row.get("regime_code", String.class),
              row.get("registration_no", String.class),
              row.get("status", String.class),
              row.get("validity_kind", String.class),
              row.get("valid_from", OffsetDateTime.class),
              row.get("valid_until", OffsetDateTime.class),
              row.get("filing_cycle", String.class),
              row.get("filing_start_month", Integer.class),
              row.get("filing_end_month", Integer.class),
              row.get("effective_from", OffsetDateTime.class),
              row.get("effective_to", OffsetDateTime.class),
              List.copyOf(countries.getOrDefault(id, List.of())),
              List.copyOf(categories.getOrDefault(id, List.of())),
              row.get("note", String.class),
              row.get("created_at", OffsetDateTime.class),
              row.get("updated_at", OffsetDateTime.class)));
    }
    return items;
  }

  private static String offsetText(OffsetDateTime value) {
    return value == null ? "" : value.toString();
  }

  /** 距有效期止还有几天（负数 = 已过期；null = 长期有效 / 没记有效期）。 */
  private static Integer daysToExpiry(RegistrationRow row, OffsetDateTime now) {
    if (VALIDITY_UNKNOWN.equals(row.validityKind()) || row.validUntil() == null) {
      return null;
    }
    return (int) java.time.temporal.ChronoUnit.DAYS.between(now, row.validUntil());
  }

  private EprRegistrationView view(
      RegistrationRow row, Map<String, RegimeFact> regimes, LocalDate today, OffsetDateTime now) {
    RegimeFact regime = regimes.get(row.regimeCode());
    FilingWindow window =
        filingWindow(
            row.filingCycle(), row.filingStartMonth(), row.filingEndMonth(), today);
    return new EprRegistrationView(
        row.id().toString(),
        row.registrationKey().toString(),
        row.versionNo(),
        row.regimeCode(),
        regime == null ? row.regimeCode() : regime.name(),
        regime == null ? "" : regime.authority(),
        row.registrationNo(),
        row.status(),
        row.validityKind(),
        offsetText(row.validFrom()),
        offsetText(row.validUntil()),
        row.filingCycle(),
        filingCycleLabel(row.filingCycle()),
        row.filingStartMonth(),
        row.filingEndMonth(),
        window.status(),
        filingStatusLabel(window.status()),
        filingPeriodLabel(window),
        window.daysUntilNext(),
        offsetText(row.effectiveFrom()),
        offsetText(row.effectiveTo()),
        row.effectiveTo() == null,
        row.countryCodes(),
        row.categoryCodes(),
        daysToExpiry(row, now),
        row.note(),
        offsetText(row.createdAt()),
        offsetText(row.updatedAt()));
  }

  // ==================================================================
  // 判定（唯一的来源：DB 函数 epr_gate_findings）
  // ==================================================================

  /** 🔴 调 DB 里的判定函数 —— 服务层与 DB 层触发器**共用**它。 */
  private List<Finding> gateFindings(UUID tenant, String countryCode, String categoryCode) {
    List<Finding> findings = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select regime_code, regime_name, country_code, country_name, category_code,"
                + " reason_code, detail from epr_gate_findings(?, ?, ?)",
            tenant,
            countryCode,
            categoryCode)) {
      findings.add(
          new Finding(
              row.get("regime_code", String.class),
              row.get("regime_name", String.class),
              text(row.get("country_code", String.class)),
              row.get("country_name", String.class),
              text(row.get("category_code", String.class)),
              row.get("reason_code", String.class),
              row.get("detail", String.class)));
    }
    return findings;
  }

  private static EprGateReason toReason(Finding finding) {
    return new EprGateReason(
        finding.regimeCode(),
        finding.regimeName(),
        finding.countryCode(),
        finding.countryName(),
        finding.categoryCode(),
        finding.reasonCode(),
        reasonLabel(finding.reasonCode()),
        finding.detail(),
        blocking(finding.reasonCode()));
  }

  /**
   * 🔴 闸门结论：**三态**。
   *
   * <ul>
   *   <li>全部 OK / EXEMPTED / 无义务 ⇒ ALLOWED。</li>
   *   <li>存在「判不了」的原因（有效期判不了 / 没有生效版本）⇒ INDETERMINATE（**仍然不放行**）。</li>
   *   <li>其余阻断原因 ⇒ BLOCKED。</li>
   * </ul>
   */
  private static String conclusionOf(List<Finding> findings) {
    boolean indeterminate = false;
    boolean blocked = false;
    for (Finding finding : findings) {
      if (!blocking(finding.reasonCode())) {
        continue;
      }
      if (REASON_VALIDITY_UNKNOWN.equals(finding.reasonCode())
          || REASON_NOT_EFFECTIVE_YET.equals(finding.reasonCode())) {
        indeterminate = true;
      } else {
        blocked = true;
      }
    }
    if (indeterminate) {
      return CONCLUSION_INDETERMINATE;
    }
    return blocked ? CONCLUSION_BLOCKED : CONCLUSION_ALLOWED;
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 能力声明：哪些下游未交付、量不到归谁、判定的口径是什么。
   *
   * <p>🔴 如实报出：12 财务（本仓尚未落地）、16.3.2.1 VAT/IOSS（未交付）、
   * 16.3.2.3 销售税（未交付）、16.3.1.2 申报日历（未交付）。
   */
  public EprCapabilities capabilities() {
    List<EprCapabilityGap> gaps =
        List.of(
            new EprCapabilityGap(
                "12",
                "财务",
                "12",
                false,
                "finance 服务在本仓**尚未落地** ⇒ 本模块**不建财务凭证表**、**不声称费用已入账**。"
                    + "`epr_fee` 只是**本服务自己的费用记录**（谁付的、哪个周期、多少钱、什么币种）；"
                    + "要做凭证、要对账、要入账，都得等 12 财务落地。"),
            new EprCapabilityGap(
                "16.3.2.1",
                "VAT/IOSS",
                "16.3.2.1",
                false,
                "VAT/IOSS **未交付** ⇒ 本模块**不编造** IOSS 订单清单、代收代缴统计与申报状态。"
                    + "本模块只回答「EPR 这一层有没有注册、能不能刊登」。"),
            new EprCapabilityGap(
                "16.3.2.3",
                "销售税与 marketplace facilitator",
                "16.3.2.3",
                false,
                "销售税 **未交付** ⇒ 本模块不碰销售税登记、代扣代缴与 marketplace facilitator 判定。"),
            new EprCapabilityGap(
                "16.3.1.2",
                "申报日历",
                "16.3.1.2",
                false,
                "申报日历 **未交付** ⇒ 本模块**只算**「当前是否在申报期内」+「距下一期还有几天」，"
                    + "**不做**申报日历、不做截止日提醒、不做「未开始 / 已提交 / 已确认」的状态跟踪。"));

    List<EprUpstream> upstreams =
        List.of(
            new EprUpstream(
                "16.3.1.1",
                "税号与辖区（channel 同库）",
                true,
                "16.3.1.1",
                "上游是**同库只读**：本模块**不写** `tax_registration_record`（上游写所有权不变），"
                    + "也**不把**上游那个**辖区级** `epr_registration_no` 复制一份当自己的权威源 —— "
                    + "上游是「一个辖区一个号」的**粗**粒度登记，本模块是「一个制度 × 国家 × 品类一个号」的"
                    + "**细**粒度档案，**粗 / 细两层**，不是同一份事实的两个副本。"),
            new EprUpstream(
                "2",
                "店铺与站点目录（channel 自家库）",
                true,
                "2",
                "店铺与站点目录**直接读本服务自己的 `shop` / `shop_marketplace` / `marketplace`** —— "
                    + "「这家店在哪些国家需要 EPR」就是从站点目录推出来的，"
                    + "**不写死店铺常量、不造平行店铺表**。"));

    List<String> categories = new ArrayList<>();
    for (EprCategoryView category : loadCategories()) {
      categories.add(category.code());
    }

    return new EprCapabilities(
        gaps,
        upstreams,
        REGIME_TYPES,
        categories,
        FILING_CYCLES,
        VALIDITY_KINDS,
        FEE_TYPES,
        FEE_STATUSES,
        PAID_BY,
        List.of(
            REASON_OK,
            REASON_NOT_APPLICABLE,
            REASON_EXEMPTED,
            REASON_NOT_REGISTERED,
            REASON_EXPIRED,
            REASON_VALIDITY_UNKNOWN,
            REASON_NOT_EFFECTIVE_YET,
            REASON_DISABLED,
            REASON_CATEGORY_NOT_COVERED),
        CONCLUSIONS,
        FILING_STATUSES,
        REGIME_SEMANTICS,
        APPLICABILITY_SEMANTICS,
        FILING_SEMANTICS,
        FEE_SEMANTICS,
        UPSTREAM_NOTE,
        DOWNSTREAM_NOTE,
        ENFORCEMENT_NOTE);
  }

  // ==================================================================
  // 参考数据端点
  // ==================================================================

  public EprRegimeList regimes() {
    List<EprRegimeView> items = loadRegimes();
    return new EprRegimeList(
        items,
        items.size(),
        "🔴 制度值域钉死在 DB 的 CHECK 里（新增制度走 DB 迁移加值域，不许运行时塞字符串）。"
            + "每个制度带「在哪些国家适用、适用哪些品类」—— **不在这里的 (国家, 制度) 是 NOT_APPLICABLE ⇒ 放行**。");
  }

  public EprCategoryList categories() {
    List<EprCategoryView> items = loadCategories();
    return new EprCategoryList(
        items,
        items.size(),
        "🔴 这是 **EPR 制度的适用品类维度**（包装 / 电子 / 电池 …），"
            + "**不是**平台侧商品类目（刊登入口的平台类目路径无法可靠映射到它）。");
  }

  // ==================================================================
  // 店铺目录
  // ==================================================================

  public EprShopList shops(String keyword, String platformCode) {
    UUID tenant = currentTenant();
    Set<String> eprCountries = eprCountries();

    Map<UUID, List<String>> marketplaces = new LinkedHashMap<>();
    Map<UUID, List<String>> countries = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            """
            select sm.shop_id, m.code as marketplace_code, trim(m.country_code) as country_code
            from shop_marketplace sm
            join marketplace m on m.id = sm.marketplace_id
            where sm.tenant_id = ? and sm.status = 'ACTIVE'
            order by sm.shop_id, m.code
            """,
            tenant)) {
      UUID shopId = row.get("shop_id", UUID.class);
      marketplaces
          .computeIfAbsent(shopId, key -> new ArrayList<>())
          .add(row.get("marketplace_code", String.class));
      String country = text(row.get("country_code", String.class)).toUpperCase(Locale.ROOT);
      if (!country.isEmpty() && eprCountries.contains(country)) {
        List<String> list = countries.computeIfAbsent(shopId, key -> new ArrayList<>());
        if (!list.contains(country)) {
          list.add(country);
        }
      }
    }

    StringBuilder statement =
        new StringBuilder(
            """
            select s.id, s.code, s.name, p.code as platform_code, p.name as platform_name
            from shop s join platform p on p.id = s.platform_id
            where s.tenant_id = ? and s.status <> 'ARCHIVED'
            """);
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    if (!text(platformCode).isEmpty()) {
      statement.append(" and p.code = ?");
      params.add(text(platformCode).toUpperCase(Locale.ROOT));
    }
    if (!text(keyword).isEmpty()) {
      statement.append(" and (s.code ilike '%' || ? || '%' or s.name ilike '%' || ? || '%')");
      params.add(text(keyword));
      params.add(text(keyword));
    }
    statement.append(" order by s.code");

    List<EprShopView> items = new ArrayList<>();
    for (Record row : sql.fetch(statement.toString(), params.toArray())) {
      UUID id = row.get("id", UUID.class);
      items.add(
          new EprShopView(
              id.toString(),
              row.get("code", String.class),
              row.get("name", String.class),
              row.get("platform_code", String.class),
              row.get("platform_name", String.class),
              List.copyOf(marketplaces.getOrDefault(id, List.of())),
              List.copyOf(countries.getOrDefault(id, List.of()))));
    }
    return new EprShopList(
        items,
        items.size(),
        "🔴 店铺清单**直接读本服务自己的店铺目录**，不写死店铺常量、不造平行店铺表。"
            + "「这家店在哪些国家需要 EPR」由**启用的站点**推导：站点国家出现在 EPR 制度参考表里才算。");
  }

  // ==================================================================
  // 登记档案端点
  // ==================================================================

  public EprRegistrationPage registrations(
      String regimeCode,
      String status,
      String validityKind,
      String countryCode,
      int page,
      int size) {
    UUID tenant = currentTenant();
    int limit = Math.max(1, Math.min(size, 200));
    String regime = text(regimeCode).toUpperCase(Locale.ROOT);
    if (!regime.isEmpty() && !REGIME_TYPES.contains(regime)) {
      throw unprocessable("制度只能是 " + String.join(" / ", REGIME_TYPES));
    }
    String lifecycle = text(status).toUpperCase(Locale.ROOT);
    if (!lifecycle.isEmpty() && !List.of("ACTIVE", "DISABLED").contains(lifecycle)) {
      throw unprocessable("状态只能是 ACTIVE / DISABLED");
    }
    String kind = text(validityKind).toUpperCase(Locale.ROOT);
    if (!kind.isEmpty() && !VALIDITY_KINDS.contains(kind)) {
      throw unprocessable("有效期形态只能是 " + String.join(" / ", VALIDITY_KINDS));
    }
    String country = text(countryCode).toUpperCase(Locale.ROOT);

    Map<String, RegimeFact> regimes = regimeFacts();
    LocalDate today = dbNow().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
    OffsetDateTime now = dbNow();

    List<RegistrationRow> filtered = new ArrayList<>();
    for (RegistrationRow row : loadRegistrations(tenant, true)) {
      if (!regime.isEmpty() && !regime.equals(row.regimeCode())) {
        continue;
      }
      if (!lifecycle.isEmpty() && !lifecycle.equals(row.status())) {
        continue;
      }
      if (!kind.isEmpty() && !kind.equals(row.validityKind())) {
        continue;
      }
      if (!country.isEmpty() && !row.countryCodes().contains(country)) {
        continue;
      }
      filtered.add(row);
    }

    int offset = Math.max(0, page) * limit;
    List<EprRegistrationView> items = new ArrayList<>();
    for (int index = offset; index < Math.min(offset + limit, filtered.size()); index++) {
      items.add(view(filtered.get(index), regimes, today, now));
    }
    return new EprRegistrationPage(items, filtered.size(), Math.max(0, page), limit);
  }

  public EprRegistrationView registration(String id) {
    UUID tenant = currentTenant();
    UUID target = parseUuid(id);
    if (target == null) {
      throw notFound();
    }
    Map<String, RegimeFact> regimes = regimeFacts();
    LocalDate today = dbNow().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
    for (RegistrationRow row : loadRegistrations(tenant, false)) {
      if (row.id().equals(target)) {
        return view(row, regimes, today, dbNow());
      }
    }
    throw notFound();
  }

  /** 版本历史（同一逻辑登记的各个生效版本）。 */
  public EprVersionList versions(String id) {
    UUID tenant = currentTenant();
    UUID target = parseUuid(id);
    if (target == null) {
      throw notFound();
    }
    List<RegistrationRow> all = loadRegistrations(tenant, false);
    UUID key = null;
    for (RegistrationRow row : all) {
      if (row.id().equals(target)) {
        key = row.registrationKey();
        break;
      }
    }
    if (key == null) {
      throw notFound();
    }
    List<EprVersionView> items = new ArrayList<>();
    for (RegistrationRow row : all) {
      if (!row.registrationKey().equals(key)) {
        continue;
      }
      items.add(
          new EprVersionView(
              row.id().toString(),
              row.versionNo(),
              row.registrationNo(),
              row.status(),
              row.validityKind(),
              offsetText(row.validFrom()),
              offsetText(row.validUntil()),
              row.filingCycle(),
              offsetText(row.effectiveFrom()),
              offsetText(row.effectiveTo()),
              row.countryCodes(),
              row.categoryCodes(),
              row.note(),
              offsetText(row.createdAt())));
    }
    items.sort(Comparator.comparingInt(EprVersionView::versionNo).reversed());
    return new EprVersionList(
        key.toString(),
        items,
        "🔴 改一条登记 = **关掉当前版本 + 插入新版本**（生效窗口划开），"
            + "所以「历史判定按当时那版算」是物理成立的；**生效时刻不许回填**。");
  }

  /**
   * 新建一条 EPR 登记。
   *
   * <p>🔴 校验顺序：值域（制度 / 品类 / 申报周期 / 有效期形态）先钉死，
   * 再钉「适用国家与品类至少各有一个」（一条什么都不适用的登记没有意义）。
   */
  public EprRegistrationView create(EprRegistrationInput input) {
    UUID tenant = currentTenant();
    if (input == null) {
      throw unprocessable("请求体不能为空");
    }
    Normalized normalized = normalize(input, null);
    UUID key = Ids.next();
    UUID id = Ids.next();
    OffsetDateTime now = dbNow();
    OffsetDateTime effectiveFrom =
        normalized.effectiveFrom() == null ? now : normalized.effectiveFrom();
    if (effectiveFrom.isBefore(now.minusSeconds(60))) {
      throw unprocessable("生效时刻不能早于当下 —— **不许回填**（改了就是新的生效版本，历史按当时那版算）");
    }

    sql.execute(
        """
        insert into epr_registration
          (id, tenant_id, registration_key, version_no, supersedes_id, regime_code,
           registration_no, status, validity_kind, valid_from, valid_until,
           filing_cycle, filing_start_month, filing_end_month,
           effective_from, effective_to, note, recorded_by, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz,
                ?, ?, ?, ?::timestamptz, ?, ?, ?, now(), now())
        """,
        id,
        tenant,
        key,
        1,
        null,
        normalized.regimeCode(),
        normalized.registrationNo(),
        normalized.status(),
        normalized.validityKind(),
        normalized.validFrom(),
        normalized.validUntil(),
        normalized.filingCycle(),
        normalized.filingStartMonth(),
        normalized.filingEndMonth(),
        effectiveFrom,
        null,
        normalized.note(),
        currentActor());

    writeScope(tenant, id, normalized.countryCodes(), normalized.categoryCodes());
    return registration(id.toString());
  }

  /**
   * 改一条登记 —— **版本化**：关掉当前版本 + 插入新版本。
   *
   * <p>🔴 不允许就地改事实：DB 触发器 {@code guard_epr_registration_version_immutable}
   * 会把「就地改当前版本事实」直接拒掉；本方法走的是「关旧 + 建新」这条路。
   */
  public EprRegistrationView update(String id, EprRegistrationInput input) {
    UUID tenant = currentTenant();
    UUID target = parseUuid(id);
    if (target == null) {
      throw notFound();
    }
    RegistrationRow existing = null;
    for (RegistrationRow row : loadRegistrations(tenant, false)) {
      if (row.id().equals(target)) {
        existing = row;
        break;
      }
    }
    if (existing == null) {
      throw notFound();
    }
    if (existing.effectiveTo() != null) {
      throw unprocessable("这条版本已经关闭了 —— 历史版本不可改，要改请在当前版本上改");
    }
    Normalized normalized = normalize(input, existing);
    OffsetDateTime now = dbNow();
    OffsetDateTime effectiveFrom =
        normalized.effectiveFrom() == null ? now : normalized.effectiveFrom();
    if (effectiveFrom.isBefore(now.minusSeconds(60))) {
      throw unprocessable("生效时刻不能早于当下 —— **不许回填**（改了就是新的生效版本，历史按当时那版算）");
    }

    // 关掉当前版本
    sql.execute(
        "update epr_registration set effective_to = ?::timestamptz, updated_at = now()"
            + " where tenant_id = ? and id = ? and effective_to is null",
        effectiveFrom,
        tenant,
        target);

    // 新版本
    UUID newId = Ids.next();
    sql.execute(
        """
        insert into epr_registration
          (id, tenant_id, registration_key, version_no, supersedes_id, regime_code,
           registration_no, status, validity_kind, valid_from, valid_until,
           filing_cycle, filing_start_month, filing_end_month,
           effective_from, effective_to, note, recorded_by, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz,
                ?, ?, ?, ?::timestamptz, ?, ?, ?, now(), now())
        """,
        newId,
        tenant,
        existing.registrationKey(),
        existing.versionNo() + 1,
        existing.id(),
        normalized.regimeCode(),
        normalized.registrationNo(),
        normalized.status(),
        normalized.validityKind(),
        normalized.validFrom(),
        normalized.validUntil(),
        normalized.filingCycle(),
        normalized.filingStartMonth(),
        normalized.filingEndMonth(),
        effectiveFrom,
        null,
        normalized.note(),
        currentActor());

    writeScope(tenant, newId, normalized.countryCodes(), normalized.categoryCodes());
    return registration(newId.toString());
  }

  /** 写入版本行的适用范围子表。 */
  private void writeScope(
      UUID tenant, UUID registrationId, List<String> countryCodes, List<String> categoryCodes) {
    for (String country : countryCodes) {
      sql.execute(
          "insert into epr_registration_country (tenant_id, registration_id, country_code)"
              + " values (?, ?, ?) on conflict do nothing",
          tenant,
          registrationId,
          country);
    }
    for (String category : categoryCodes) {
      sql.execute(
          "insert into epr_registration_category (tenant_id, registration_id, category_code)"
              + " values (?, ?, ?) on conflict do nothing",
          tenant,
          registrationId,
          category);
    }
  }

  /** 归一化后的入参。 */
  private record Normalized(
      String regimeCode,
      String registrationNo,
      String status,
      String validityKind,
      OffsetDateTime validFrom,
      OffsetDateTime validUntil,
      String filingCycle,
      Integer filingStartMonth,
      Integer filingEndMonth,
      OffsetDateTime effectiveFrom,
      List<String> countryCodes,
      List<String> categoryCodes,
      String note) {}

  /** 🔴 入参归一化 + 值域校验（**不留自由文本**）。 */
  private Normalized normalize(EprRegistrationInput input, RegistrationRow existing) {
    String regime = text(input.regimeCode()).toUpperCase(Locale.ROOT);
    if (regime.isEmpty() && existing != null) {
      regime = existing.regimeCode();
    }
    if (!REGIME_TYPES.contains(regime)) {
      throw unprocessable("制度只能是 " + String.join(" / ", REGIME_TYPES));
    }
    Object knownRegime = sql.fetchValue("select 1 from epr_regime where code = ?", regime);
    if (knownRegime == null) {
      throw unprocessable("制度 " + regime + " 不在受控值域里（请先在制度码表里选）");
    }

    String number = text(input.registrationNo());
    if (number.isEmpty() && existing != null) {
      number = existing.registrationNo();
    }
    if (number.isEmpty()) {
      throw unprocessable("注册号不能为空");
    }

    String lifecycle = text(input.status()).toUpperCase(Locale.ROOT);
    if (lifecycle.isEmpty()) {
      lifecycle = existing == null ? "ACTIVE" : existing.status();
    }
    if (!List.of("ACTIVE", "DISABLED").contains(lifecycle)) {
      throw unprocessable("状态只能是 ACTIVE / DISABLED");
    }

    String kind = text(input.validityKind()).toUpperCase(Locale.ROOT);
    if (kind.isEmpty()) {
      kind = existing == null ? VALIDITY_UNKNOWN : existing.validityKind();
    }
    if (!VALIDITY_KINDS.contains(kind)) {
      throw unprocessable("有效期形态只能是 " + String.join(" / ", VALIDITY_KINDS));
    }

    OffsetDateTime validFrom =
        input.validFrom() == null || input.validFrom().isBlank()
            ? (existing == null ? null : existing.validFrom())
            : parseTime(input.validFrom(), "有效期起");
    OffsetDateTime validUntil =
        input.validUntil() == null || input.validUntil().isBlank()
            ? (existing == null ? null : existing.validUntil())
            : parseTime(input.validUntil(), "有效期止");
    if (validFrom != null && validUntil != null && validUntil.isBefore(validFrom)) {
      throw unprocessable("有效期止不能早于起");
    }
    if (VALIDITY_FIXED.equals(kind) && (validFrom == null || validUntil == null)) {
      throw unprocessable("有效期形态是 FIXED 时，有效期起与止**都必须填**");
    }
    if (VALIDITY_OPEN_ENDED.equals(kind) && validFrom == null) {
      throw unprocessable("有效期形态是 OPEN_ENDED 时，有效期起**必须填**（长期有效也得有个起点）");
    }
    if (VALIDITY_UNKNOWN.equals(kind) && validUntil != null) {
      throw unprocessable("有效期形态是 UNKNOWN 时不能填有效期止（那是两种形态混着写）");
    }

    String cycle = text(input.filingCycle()).toUpperCase(Locale.ROOT);
    if (cycle.isEmpty()) {
      cycle = existing == null ? "ANNUAL" : existing.filingCycle();
    }
    if (!FILING_CYCLES.contains(cycle)) {
      throw unprocessable("申报周期只能是 " + String.join(" / ", FILING_CYCLES));
    }

    Integer startMonth =
        input.filingStartMonth() != null
            ? input.filingStartMonth()
            : (existing == null ? null : existing.filingStartMonth());
    Integer endMonth =
        input.filingEndMonth() != null
            ? input.filingEndMonth()
            : (existing == null ? null : existing.filingEndMonth());
    if ((startMonth == null) != (endMonth == null)) {
      throw unprocessable("申报期的起止月份**必须同时填或同时不填**");
    }
    if (startMonth != null && (startMonth < 1 || startMonth > 12)) {
      throw unprocessable("申报期起始月必须是 1..12");
    }
    if (endMonth != null && (endMonth < 1 || endMonth > 12)) {
      throw unprocessable("申报期结束月必须是 1..12");
    }

    OffsetDateTime effectiveFrom =
        input.effectiveFrom() == null || input.effectiveFrom().isBlank()
            ? null
            : parseTime(input.effectiveFrom(), "生效时刻");

    List<String> countryCodes = normalizeCountries(input.countryCodes(), existing);
    List<String> categoryCodes = normalizeCategories(input.categoryCodes(), existing);
    if (countryCodes.isEmpty()) {
      throw unprocessable("至少要选一个适用国家 —— 一条什么都不适用的登记没有意义");
    }
    if (categoryCodes.isEmpty()) {
      throw unprocessable("至少要选一个适用品类 —— 否则这条登记在判定里不覆盖任何品类");
    }

    return new Normalized(
        regime,
        number,
        lifecycle,
        kind,
        validFrom,
        validUntil,
        cycle,
        startMonth,
        endMonth,
        effectiveFrom,
        countryCodes,
        categoryCodes,
        text(input.note()));
  }

  private List<String> normalizeCountries(List<String> raw, RegistrationRow existing) {
    Set<String> result = new LinkedHashSet<>();
    if (raw == null) {
      if (existing != null) {
        result.addAll(existing.countryCodes());
      }
      return List.copyOf(result);
    }
    for (String value : raw) {
      String country = text(value).toUpperCase(Locale.ROOT);
      if (country.isEmpty()) {
        continue;
      }
      if (!validCountryCode(country)) {
        throw unprocessable("国家码 " + country + " 不合法（应为 ISO 3166-1 alpha-2 两位大写字母）");
      }
      result.add(country);
    }
    return List.copyOf(result);
  }

  private List<String> normalizeCategories(List<String> raw, RegistrationRow existing) {
    Set<String> result = new LinkedHashSet<>();
    if (raw == null) {
      if (existing != null) {
        result.addAll(existing.categoryCodes());
      }
      return List.copyOf(result);
    }
    for (String value : raw) {
      String category = text(value).toUpperCase(Locale.ROOT);
      if (category.isEmpty()) {
        continue;
      }
      Object known = sql.fetchValue("select 1 from epr_category where code = ?", category);
      if (known == null) {
        throw unprocessable("品类 " + category + " 不在受控值域里（请先在适用品类码表里选）");
      }
      result.add(category);
    }
    return List.copyOf(result);
  }

  // ==================================================================
  // 覆盖情况（国家 × 品类 的反查）
  // ==================================================================

  /**
   * 🔴 覆盖情况：**「这个国家这个品类有没有覆盖」的反查**。
   *
   * <p>判定来源与刊登闸门**同一个函数**，所以覆盖视图与真正拦人的闸门不会各说各话。
   */
  public EprCoveragePage coverage(
      String countryCode, String regimeCode, String categoryCode, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.max(1, Math.min(size, 200));
    String country = text(countryCode).toUpperCase(Locale.ROOT);
    String regime = text(regimeCode).toUpperCase(Locale.ROOT);
    String category = text(categoryCode).toUpperCase(Locale.ROOT);
    if (!regime.isEmpty() && !REGIME_TYPES.contains(regime)) {
      throw unprocessable("制度只能是 " + String.join(" / ", REGIME_TYPES));
    }
    if (!category.isEmpty()) {
      Object known = sql.fetchValue("select 1 from epr_category where code = ?", category);
      if (known == null) {
        throw unprocessable("品类 " + category + " 不在受控值域里");
      }
    }

    List<EprCoverageRow> all = computeCoverage(tenant, country, regime, category);

    int offset = Math.max(0, page) * limit;
    List<EprCoverageRow> items = new ArrayList<>();
    for (int index = offset; index < Math.min(offset + limit, all.size()); index++) {
      items.add(all.get(index));
    }
    long covered = 0;
    long blocked = 0;
    long notApplicable = 0;
    for (EprCoverageRow row : all) {
      if (!row.applicable()) {
        notApplicable++;
      } else if (row.covered()) {
        covered++;
      } else {
        blocked++;
      }
    }
    return new EprCoveragePage(
        items,
        all.size(),
        Math.max(0, page),
        limit,
        covered,
        blocked,
        notApplicable,
        "🔴 覆盖情况是**反查**出来的：适用国家与适用品类是**多对多**，"
            + "一条登记适用多个国家 + 多个品类 ⇒ 「这个国家这个品类有没有覆盖」必须能查出来。"
            + "判定与刊登闸门**共用同一个判定函数** ⇒ 覆盖视图与真正拦人的闸门不会各说各话。");
  }

  /** 算出覆盖行（国家 × 制度）。 */
  private List<EprCoverageRow> computeCoverage(
      UUID tenant, String country, String regime, String category) {
    // 参考表里的 (国家, 制度) 全集
    List<Record> reference =
        sql.fetch(
            """
            select c.country_code, c.country_name, c.regime_code, r.name as regime_name, c.id
            from epr_country_regime c
            join epr_regime r on r.code = c.regime_code
            order by c.position, c.country_code, c.regime_code
            """);
    Map<String, List<String>> categoriesById = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select country_regime_id, category_code from epr_country_regime_category"
                + " order by country_regime_id, category_code")) {
      categoriesById
          .computeIfAbsent(row.get("country_regime_id", UUID.class).toString(), key -> new ArrayList<>())
          .add(row.get("category_code", String.class));
    }

    // 按国家分组判定一次（判定函数按国家返回该国全部制度的结论）
    Map<String, List<Finding>> findingsByCountry = new LinkedHashMap<>();
    Set<String> countries = new LinkedHashSet<>();
    for (Record row : reference) {
      String code = text(row.get("country_code", String.class));
      if (!country.isEmpty() && !country.equals(code)) {
        continue;
      }
      if (!regime.isEmpty() && !regime.equals(row.get("regime_code", String.class))) {
        continue;
      }
      countries.add(code);
    }
    for (String code : countries) {
      findingsByCountry.put(code, gateFindings(tenant, code, category.isEmpty() ? null : category));
    }

    // 命中的登记（给界面显示「命中了哪一条」）：**当前生效版本**优先，且优先 ACTIVE
    Map<String, String> registrationByKey = new LinkedHashMap<>();
    Map<String, String> registrationNoByKey = new LinkedHashMap<>();
    for (RegistrationRow row : loadRegistrations(tenant, true)) {
      if (!"ACTIVE".equals(row.status())) {
        continue;
      }
      for (String code : row.countryCodes()) {
        String key = code + "|" + row.regimeCode();
        registrationByKey.putIfAbsent(key, row.id().toString());
        registrationNoByKey.putIfAbsent(key, row.registrationNo());
      }
    }
    for (RegistrationRow row : loadRegistrations(tenant, true)) {
      for (String code : row.countryCodes()) {
        String key = code + "|" + row.regimeCode();
        registrationByKey.putIfAbsent(key, row.id().toString());
        registrationNoByKey.putIfAbsent(key, row.registrationNo());
      }
    }

    List<EprCoverageRow> items = new ArrayList<>();
    for (Record row : reference) {
      String code = text(row.get("country_code", String.class));
      String regimeCode = row.get("regime_code", String.class);
      if (!country.isEmpty() && !country.equals(code)) {
        continue;
      }
      if (!regime.isEmpty() && !regime.equals(regimeCode)) {
        continue;
      }
      List<String> regimeCategories =
          categoriesOf(categoriesById.getOrDefault(row.get("id", UUID.class).toString(), List.of()), category);
      boolean applicable = !regimeCategories.isEmpty();
      Finding finding = null;
      for (Finding candidate : findingsByCountry.getOrDefault(code, List.of())) {
        if (candidate.regimeCode().equals(regimeCode)) {
          finding = candidate;
          break;
        }
      }
      String reasonCode;
      String message;
      if (!applicable) {
        reasonCode = REASON_NOT_APPLICABLE;
        message =
            category.isEmpty()
                ? "该制度在本国没有任何适用品类 ⇒ 不构成任何 (国家, 品类) 的登记义务 ⇒ 放行。"
                : "该制度在本国**不适用**这个品类 ⇒ 不构成登记义务 ⇒ 放行。";
      } else if (finding == null) {
        reasonCode = REASON_NOT_APPLICABLE;
        message = "该制度在本国**不适用**这个品类 ⇒ 不构成登记义务 ⇒ 放行。";
      } else {
        reasonCode = finding.reasonCode();
        message = finding.detail();
      }
      boolean covered = REASON_OK.equals(reasonCode) || REASON_EXEMPTED.equals(reasonCode);
      String key = code + "|" + regimeCode;
      items.add(
          new EprCoverageRow(
              code,
              row.get("country_name", String.class),
              regimeCode,
              row.get("regime_name", String.class),
              applicable,
              regimeCategories,
              registrationByKey.getOrDefault(key, ""),
              registrationNoByKey.getOrDefault(key, ""),
              covered,
              reasonCode,
              reasonLabel(reasonCode),
              message));
    }
    return items;
  }

  // ==================================================================
  // 合规状态
  // ==================================================================

  /**
   * 合规状态：按国家看「能不能刊登」。
   *
   * <p>国家清单来自**这家店启用的站点**（真实的上游事实），不是写死的常量。
   */
  public EprComplianceStatus complianceStatus(String countryCode) {
    UUID tenant = currentTenant();
    String filter = text(countryCode).toUpperCase(Locale.ROOT);

    List<String> countries = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select distinct trim(m.country_code) as country_code
            from shop_marketplace sm
            join marketplace m on m.id = sm.marketplace_id
            where sm.tenant_id = ? and sm.status = 'ACTIVE'
            order by 1
            """,
            tenant)) {
      String code = text(row.get("country_code", String.class)).toUpperCase(Locale.ROOT);
      if (code.isEmpty()) {
        continue;
      }
      if (!filter.isEmpty() && !filter.equals(code)) {
        continue;
      }
      if (!countries.contains(code)) {
        countries.add(code);
      }
    }

    List<EprCountryStatus> items = new ArrayList<>();
    long compliant = 0;
    long nonCompliant = 0;
    long indeterminate = 0;
    for (String code : countries) {
      List<Finding> findings = gateFindings(tenant, code, null);
      List<EprGateReason> reasons = new ArrayList<>();
      int applicable = 0;
      int coveredCount = 0;
      int blockedCount = 0;
      boolean hasIndeterminate = false;
      for (Finding finding : findings) {
        reasons.add(toReason(finding));
        if (REASON_OK.equals(finding.reasonCode()) || REASON_EXEMPTED.equals(finding.reasonCode())) {
          coveredCount++;
        } else {
          applicable++;
          blockedCount++;
          if (REASON_VALIDITY_UNKNOWN.equals(finding.reasonCode())
              || REASON_NOT_EFFECTIVE_YET.equals(finding.reasonCode())) {
            hasIndeterminate = true;
          }
        }
      }
      String status;
      if (findings.isEmpty()) {
        status = "NOT_APPLICABLE";
      } else if (blockedCount == 0) {
        status = "COMPLIANT";
        compliant++;
      } else if (hasIndeterminate) {
        status = "INDETERMINATE";
        indeterminate++;
      } else {
        status = "NON_COMPLIANT";
        nonCompliant++;
      }
      String label =
          switch (status) {
            case "COMPLIANT" -> "已覆盖";
            case "NON_COMPLIANT" -> "未覆盖（阻断刊登）";
            case "INDETERMINATE" -> "判不了（仍不放行）";
            default -> "不适用（该国不要求 EPR）";
          };
      items.add(
          new EprCountryStatus(
              code,
              countryName(code),
              applicable,
              coveredCount,
              blockedCount,
              status,
              label,
              reasons));
    }

    return new EprComplianceStatus(
        items,
        items.size(),
        compliant,
        nonCompliant,
        indeterminate,
        "🔴 国家清单来自**店铺启用的站点**（真实的上游事实），不是写死的常量。"
            + "判定与刊登闸门共用同一个判定函数 ⇒ 这里显示的「未覆盖」就是真会被拦的。");
  }

  /** 国家名（从参考表读；读不到就显示代码本身，**不编一个国家名**）。 */
  private String countryName(String countryCode) {
    Object name =
        sql.fetchValue(
            "select min(country_name) from epr_country_regime where country_code = ?", countryCode);
    return name == null ? countryCode : String.valueOf(name);
  }

  // ==================================================================
  // 刊登闸门
  // ==================================================================

  /**
   * 🔴 刊登闸门：**服务端真算**的「这份内容能不能发」。
   *
   * <p>这不是前端的提示，是服务端对「EPR 未注册的国家应直接限制刊登」这一条的执行：
   * 有阻断项 ⇒ {@code allowed=false} + 逐条原因。DB 层还有一道触发器把 4 刊登的两个
   * 真实放行口直接拒掉（见 V314 末尾），两道**共用同一个判定函数**。
   *
   * <p>🔴 <b>口径一致性</b>：不带品类时，本结论与 DB 层触发器**逐字同口径**（国家维度）。
   * 带品类时给的是**更细**的判断（只算适用于该品类的制度），所以响应里额外带一份
   * {@code countryScope} —— 那才是 DB 层真正强制的口径，界面必须把两份都显示出来，
   * 不能只报一份「放行」。
   */
  public EprPublishGate publishGate(EprPublishGateRequest request) {
    UUID tenant = currentTenant();
    if (request == null) {
      throw unprocessable("请求体不能为空");
    }
    String country = text(request.countryCode()).toUpperCase(Locale.ROOT);
    if (!validCountryCode(country)) {
      throw unprocessable("国家码不合法（应为 ISO 3166-1 alpha-2 两位大写字母，例如 DE）");
    }
    String category = text(request.categoryCode()).toUpperCase(Locale.ROOT);
    if (!category.isEmpty()) {
      Object known = sql.fetchValue("select 1 from epr_category where code = ?", category);
      if (known == null) {
        throw unprocessable("品类 " + category + " 不在受控值域里");
      }
    }

    // 🔴 国家维度：这就是 DB 层刊登触发器强制的口径（永远算一次）
    List<Finding> countryFindings = gateFindings(tenant, country, null);
    List<EprGateReason> countryReasons = new ArrayList<>();
    for (Finding finding : countryFindings) {
      countryReasons.add(toReason(finding));
    }
    String countryConclusion = conclusionOf(countryFindings);

    // 品类维度（可选）：只算适用于该品类的制度
    List<Finding> findings = category.isEmpty() ? countryFindings : gateFindings(tenant, country, category);
    List<EprGateReason> reasons = new ArrayList<>();
    for (Finding finding : findings) {
      reasons.add(toReason(finding));
    }
    String conclusion = conclusionOf(findings);

    String note =
        findings.isEmpty()
            ? "🔴 该国**不要求任何 EPR 制度** ⇒ NOT_APPLICABLE ⇒ 放行（不是「查不到」，是「本来就不适用」）。"
            : "🔴 判定由 DB 函数 `epr_gate_findings` 算出，与刊登触发器**同一份判定** —— "
                + "页面显示的结论与真正拦人的那道闸门不可能各说各话。";
    if (!category.isEmpty()) {
      note +=
          " 🔴 本次带了品类（" + category + "）⇒ 给的是**品类维度**的更细判断；"
              + "DB 层刊登触发器强制的是**国家维度**（刊登入口的平台类目路径无法可靠映射到 EPR 品类）"
              + "⇒ 两份结论都在下面，界面必须都显示。";
    }

    return new EprPublishGate(
        CONCLUSION_ALLOWED.equals(conclusion),
        conclusion,
        conclusionLabel(conclusion),
        country,
        countryName(country),
        category,
        reasons,
        new EprGateScope(
            CONCLUSION_ALLOWED.equals(countryConclusion),
            countryConclusion,
            conclusionLabel(countryConclusion),
            countryReasons),
        dbNow().toString(),
        note);
  }

  /** 批量判定（一次问多个目标）。 */
  public EprPublishGateBatch publishGateBatch(EprPublishGateBatchRequest request) {
    if (request == null || request.requests() == null || request.requests().isEmpty()) {
      throw unprocessable("至少要有一个待判定的目标");
    }
    List<EprPublishGate> gates = new ArrayList<>();
    long blocked = 0;
    for (EprPublishGateRequest single : request.requests()) {
      EprPublishGate gate = publishGate(single);
      gates.add(gate);
      if (!gate.allowed()) {
        blocked++;
      }
    }
    return new EprPublishGateBatch(
        blocked == 0,
        gates,
        blocked,
        dbNow().toString(),
        "🔴 批量判定：只要有**一个**目标被阻断，整批就不放行。");
  }

  // ==================================================================
  // 申报期
  // ==================================================================

  /** 申报期状态清单（真分页 + 真筛选）。 */
  public EprFilingPage filings(String regimeCode, String inPeriod, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.max(1, Math.min(size, 200));
    String regime = text(regimeCode).toUpperCase(Locale.ROOT);
    if (!regime.isEmpty() && !REGIME_TYPES.contains(regime)) {
      throw unprocessable("制度只能是 " + String.join(" / ", REGIME_TYPES));
    }
    Boolean onlyInPeriod =
        text(inPeriod).isEmpty() ? null : Boolean.valueOf(text(inPeriod).toLowerCase(Locale.ROOT));

    Map<String, RegimeFact> regimes = regimeFacts();
    LocalDate today = dbNow().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();

    List<EprFilingRow> filtered = new ArrayList<>();
    for (RegistrationRow row : loadRegistrations(tenant, true)) {
      if (!regime.isEmpty() && !regime.equals(row.regimeCode())) {
        continue;
      }
      FilingWindow window =
          filingWindow(row.filingCycle(), row.filingStartMonth(), row.filingEndMonth(), today);
      if (onlyInPeriod != null && onlyInPeriod != FILING_IN_PERIOD.equals(window.status())) {
        continue;
      }
      RegimeFact fact = regimes.get(row.regimeCode());
      filtered.add(
          new EprFilingRow(
              row.id().toString(),
              row.registrationKey().toString(),
              row.regimeCode(),
              fact == null ? row.regimeCode() : fact.name(),
              row.registrationNo(),
              row.filingCycle(),
              filingCycleLabel(row.filingCycle()),
              row.filingStartMonth(),
              row.filingEndMonth(),
              window.status(),
              filingStatusLabel(window.status()),
              filingPeriodLabel(window),
              window.daysUntilNext(),
              row.countryCodes(),
              row.categoryCodes()));
    }
    long inPeriodCount =
        filtered.stream().filter(row -> FILING_IN_PERIOD.equals(row.filingStatus())).count();

    int offset = Math.max(0, page) * limit;
    List<EprFilingRow> items = new ArrayList<>();
    for (int index = offset; index < Math.min(offset + limit, filtered.size()); index++) {
      items.add(filtered.get(index));
    }
    return new EprFilingPage(
        items,
        filtered.size(),
        Math.max(0, page),
        limit,
        inPeriodCount,
        "🔴 申报期由**周期 + 起止月份**算出（可跨年，例 11..2）；没记起止月份是**判不了**（UNKNOWN），"
            + "**不是**「不在申报期内」。16.3.1.2 申报日历未交付 ⇒ 只算窗口，不做日历与提醒。");
  }

  // ==================================================================
  // 不适用声明
  // ==================================================================

  /** 不适用声明清单（真分页）。 */
  public EprExemptionPage exemptions(String countryCode, String regimeCode, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.max(1, Math.min(size, 200));
    String country = text(countryCode).toUpperCase(Locale.ROOT);
    String regime = text(regimeCode).toUpperCase(Locale.ROOT);

    StringBuilder statement =
        new StringBuilder(
            """
            select id, country_code, regime_code, status, effective_from, effective_to, reason, note, created_at
            from epr_exemption
            where tenant_id = ?
            """);
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    if (!country.isEmpty()) {
      statement.append(" and country_code = ?");
      params.add(country);
    }
    if (!regime.isEmpty()) {
      statement.append(" and regime_code = ?");
      params.add(regime);
    }
    statement.append(" order by effective_from desc, id");

    List<EprExemptionView> items = new ArrayList<>();
    long active = 0;
    for (Record row : sql.fetch(statement.toString(), params.toArray())) {
      String code = text(row.get("country_code", String.class));
      String regimeCodeValue = row.get("regime_code", String.class);
      items.add(
          new EprExemptionView(
              row.get("id", UUID.class).toString(),
              code,
              countryName(code),
              regimeCodeValue,
              regimeLabel(regimeCodeValue),
              row.get("status", String.class),
              offsetText(row.get("effective_from", OffsetDateTime.class)),
              offsetText(row.get("effective_to", OffsetDateTime.class)),
              row.get("reason", String.class),
              row.get("note", String.class),
              offsetText(row.get("created_at", OffsetDateTime.class))));
      if ("ACTIVE".equals(row.get("status", String.class))
          && row.get("effective_to", OffsetDateTime.class) == null) {
        active++;
      }
    }
    int offset = Math.max(0, page) * limit;
    List<EprExemptionView> paged = new ArrayList<>();
    for (int index = offset; index < Math.min(offset + limit, items.size()); index++) {
      paged.add(items.get(index));
    }
    return new EprExemptionPage(
        paged,
        items.size(),
        Math.max(0, page),
        limit,
        active,
        "🔴 「本制度在本国不适用我的业务」是**租户声明**，不是事实（事实是「有没有登记」）。"
            + "理由**必填** —— 没有理由的「不适用」就是漏登记的遮羞布。撤销只关窗口，不删行。");
  }

  /** 新建一条不适用声明。 */
  public EprExemptionView createExemption(EprExemptionInput input) {
    UUID tenant = currentTenant();
    if (input == null) {
      throw unprocessable("请求体不能为空");
    }
    String country = text(input.countryCode()).toUpperCase(Locale.ROOT);
    if (!validCountryCode(country)) {
      throw unprocessable("国家码不合法（应为 ISO 3166-1 alpha-2 两位大写字母）");
    }
    String regime = text(input.regimeCode()).toUpperCase(Locale.ROOT);
    if (!REGIME_TYPES.contains(regime)) {
      throw unprocessable("制度只能是 " + String.join(" / ", REGIME_TYPES));
    }
    String reason = text(input.reason());
    if (reason.isEmpty()) {
      throw unprocessable("理由**必填** —— 没有理由的「不适用」就是漏登记的遮羞布");
    }
    OffsetDateTime now = dbNow();
    OffsetDateTime effectiveFrom =
        input.effectiveFrom() == null || input.effectiveFrom().isBlank()
            ? now
            : parseTime(input.effectiveFrom(), "生效时刻");
    if (effectiveFrom.isBefore(now.minusSeconds(60))) {
      throw unprocessable("生效时刻不能早于当下 —— **不许回填**");
    }
    UUID id = Ids.next();
    sql.execute(
        """
        insert into epr_exemption
          (id, tenant_id, country_code, regime_code, status, effective_from, effective_to,
           reason, note, recorded_by, created_at, updated_at)
        values (?, ?, ?, ?, 'ACTIVE', ?::timestamptz, ?, ?, ?, ?, now(), now())
        """,
        id,
        tenant,
        country,
        regime,
        effectiveFrom,
        null,
        reason,
        text(input.note()),
        currentActor());
    return findExemption(tenant, id);
  }

  /** 撤销一条不适用声明（关窗口，不删行）。 */
  public EprExemptionView revokeExemption(String id) {
    UUID tenant = currentTenant();
    UUID target = parseUuid(id);
    if (target == null) {
      throw notFound();
    }
    findExemption(tenant, target);
    OffsetDateTime now = dbNow();
    sql.execute(
        "update epr_exemption set status = 'REVOKED', effective_to = ?::timestamptz, updated_at = now()"
            + " where tenant_id = ? and id = ? and status = 'ACTIVE'",
        now,
        tenant,
        target);
    return findExemption(tenant, target);
  }

  private EprExemptionView findExemption(UUID tenant, UUID id) {
    Record row =
        sql.fetchOne(
            "select id, country_code, regime_code, status, effective_from, effective_to, reason, note,"
                + " created_at from epr_exemption where tenant_id = ? and id = ?",
            tenant,
            id);
    if (row == null) {
      throw notFound();
    }
    String code = text(row.get("country_code", String.class));
    String regimeCode = row.get("regime_code", String.class);
    return new EprExemptionView(
        row.get("id", UUID.class).toString(),
        code,
        countryName(code),
        regimeCode,
        regimeLabel(regimeCode),
        row.get("status", String.class),
        offsetText(row.get("effective_from", OffsetDateTime.class)),
        offsetText(row.get("effective_to", OffsetDateTime.class)),
        row.get("reason", String.class),
        row.get("note", String.class),
        offsetText(row.get("created_at", OffsetDateTime.class)));
  }

  // ==================================================================
  // 费用
  // ==================================================================

  /** 费用清单（真分页 + 真筛选）。 */
  public EprFeePage fees(
      String registrationKey,
      String currency,
      String feeType,
      String billingCycle,
      String status,
      int page,
      int size) {
    UUID tenant = currentTenant();
    int limit = Math.max(1, Math.min(size, 200));
    String key = text(registrationKey);
    String currencyFilter = text(currency).toUpperCase(Locale.ROOT);
    String typeFilter = text(feeType).toUpperCase(Locale.ROOT);
    String cycleFilter = text(billingCycle).toUpperCase(Locale.ROOT);
    String statusFilter = text(status).toUpperCase(Locale.ROOT);
    if (!typeFilter.isEmpty() && !FEE_TYPES.contains(typeFilter)) {
      throw unprocessable("费用类型只能是 " + String.join(" / ", FEE_TYPES));
    }
    if (!cycleFilter.isEmpty() && !FILING_CYCLES.contains(cycleFilter)) {
      throw unprocessable("计费周期只能是 " + String.join(" / ", FILING_CYCLES));
    }
    if (!statusFilter.isEmpty() && !FEE_STATUSES.contains(statusFilter)) {
      throw unprocessable("费用状态只能是 " + String.join(" / ", FEE_STATUSES));
    }
    if (!currencyFilter.isEmpty() && !validCurrency(currencyFilter)) {
      throw unprocessable("币种只能是三个大写字母（例如 EUR）");
    }

    Map<UUID, RegistrationRow> registrations = new LinkedHashMap<>();
    for (RegistrationRow row : loadRegistrations(tenant, false)) {
      registrations.put(row.registrationKey(), row);
    }

    StringBuilder statement =
        new StringBuilder(
            """
            select id, registration_key, fee_type, amount, currency, billing_cycle,
                   period_start, period_end, status, paid_by, note, created_at
            from epr_fee
            where tenant_id = ?
            """);
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    if (!key.isEmpty()) {
      statement.append(" and registration_key = ?");
      params.add(parseFilterUuid(key));
    }
    if (!currencyFilter.isEmpty()) {
      statement.append(" and currency = ?");
      params.add(currencyFilter);
    }
    if (!typeFilter.isEmpty()) {
      statement.append(" and fee_type = ?");
      params.add(typeFilter);
    }
    if (!cycleFilter.isEmpty()) {
      statement.append(" and billing_cycle = ?");
      params.add(cycleFilter);
    }
    if (!statusFilter.isEmpty()) {
      statement.append(" and status = ?");
      params.add(statusFilter);
    }
    statement.append(" order by created_at desc, id");

    List<EprFeeView> all = new ArrayList<>();
    for (Record row : sql.fetch(statement.toString(), params.toArray())) {
      all.add(feeView(row, registrations));
    }
    int offset = Math.max(0, page) * limit;
    List<EprFeeView> items = new ArrayList<>();
    for (int index = offset; index < Math.min(offset + limit, all.size()); index++) {
      items.add(all.get(index));
    }
    return new EprFeePage(
        items,
        all.size(),
        Math.max(0, page),
        limit,
        "🔴 金额是**十进制字符串**（BigDecimal + NUMERIC，**禁浮点**）；币种**必带**。"
            + "这是**本服务自己的费用记录**，不是财务凭证（12 财务在本仓尚未落地）。");
  }

  private EprFeeView feeView(Record row, Map<UUID, RegistrationRow> registrations) {
    UUID key = row.get("registration_key", UUID.class);
    RegistrationRow registration = registrations.get(key);
    String regimeCode = registration == null ? "" : registration.regimeCode();
    String regimeName = registration == null ? "" : regimeLabel(regimeCode);
    String registrationNo = registration == null ? "" : registration.registrationNo();
    String feeType = row.get("fee_type", String.class);
    String billingCycle = row.get("billing_cycle", String.class);
    String feeStatus = row.get("status", String.class);
    String paidBy = row.get("paid_by", String.class);
    return new EprFeeView(
        row.get("id", UUID.class).toString(),
        key.toString(),
        regimeCode,
        regimeName,
        registrationNo,
        feeType,
        feeTypeLabel(feeType),
        decimalText(row.get("amount", BigDecimal.class)),
        text(row.get("currency", String.class)),
        billingCycle,
        filingCycleLabel(billingCycle),
        offsetText(row.get("period_start", OffsetDateTime.class)),
        offsetText(row.get("period_end", OffsetDateTime.class)),
        feeStatus,
        feeStatusLabel(feeStatus),
        paidBy,
        paidByLabel(paidBy),
        row.get("note", String.class),
        offsetText(row.get("created_at", OffsetDateTime.class)));
  }

  /** 🔴 金额一律输出**十进制字符串**（不经浮点、不丢精度）。 */
  private static String decimalText(BigDecimal value) {
    return value == null ? "0" : value.toPlainString();
  }

  /** 新建一笔费用。 */
  public EprFeeView createFee(EprFeeInput input) {
    UUID tenant = currentTenant();
    if (input == null) {
      throw unprocessable("请求体不能为空");
    }
    UUID key = parseUuid(input.registrationKey());
    if (key == null) {
      throw unprocessable("登记 ID 不合法");
    }
    Object known =
        sql.fetchValue(
            "select 1 from epr_registration where tenant_id = ? and registration_key = ?", tenant, key);
    if (known == null) {
      throw unprocessable("这条登记不存在或不在当前租户下");
    }
    String feeType = text(input.feeType()).toUpperCase(Locale.ROOT);
    if (!FEE_TYPES.contains(feeType)) {
      throw unprocessable("费用类型只能是 " + String.join(" / ", FEE_TYPES));
    }
    BigDecimal amount = parseAmount(input.amount());
    String currency = text(input.currency()).toUpperCase(Locale.ROOT);
    if (!validCurrency(currency)) {
      throw unprocessable("币种只能是三个大写字母（例如 EUR）");
    }
    String cycle = text(input.billingCycle()).toUpperCase(Locale.ROOT);
    if (cycle.isEmpty()) {
      cycle = CYCLE_ANNUAL;
    }
    if (!FILING_CYCLES.contains(cycle)) {
      throw unprocessable("计费周期只能是 " + String.join(" / ", FILING_CYCLES));
    }
    OffsetDateTime periodStart = parseTime(input.periodStart(), "归属周期起");
    OffsetDateTime periodEnd = parseTime(input.periodEnd(), "归属周期止");
    if (periodStart != null && periodEnd != null && periodEnd.isBefore(periodStart)) {
      throw unprocessable("归属周期止不能早于起");
    }
    String status = text(input.status()).toUpperCase(Locale.ROOT);
    if (status.isEmpty()) {
      status = FEE_STATUS_RECORDED;
    }
    if (!FEE_STATUSES.contains(status)) {
      throw unprocessable("费用状态只能是 " + String.join(" / ", FEE_STATUSES));
    }
    String paidBy = text(input.paidBy()).toUpperCase(Locale.ROOT);
    if (paidBy.isEmpty()) {
      paidBy = PAID_BY_SELLER;
    }
    if (!PAID_BY.contains(paidBy)) {
      throw unprocessable("承担方只能是 " + String.join(" / ", PAID_BY));
    }

    UUID id = Ids.next();
    sql.execute(
        """
        insert into epr_fee
          (id, tenant_id, registration_key, fee_type, amount, currency, billing_cycle,
           period_start, period_end, status, paid_by, note, recorded_by, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?, ?, ?, ?, now(), now())
        """,
        id,
        tenant,
        key,
        feeType,
        amount,
        currency,
        cycle,
        periodStart,
        periodEnd,
        status,
        paidBy,
        text(input.note()),
        currentActor());
    return findFee(tenant, id);
  }

  /** 改一笔费用。 */
  public EprFeeView updateFee(String id, EprFeeInput input) {
    UUID tenant = currentTenant();
    UUID target = parseUuid(id);
    if (target == null) {
      throw notFound();
    }
    findFee(tenant, target);
    if (input == null) {
      throw unprocessable("请求体不能为空");
    }
    String feeType = text(input.feeType()).toUpperCase(Locale.ROOT);
    if (!FEE_TYPES.contains(feeType)) {
      throw unprocessable("费用类型只能是 " + String.join(" / ", FEE_TYPES));
    }
    BigDecimal amount = parseAmount(input.amount());
    String currency = text(input.currency()).toUpperCase(Locale.ROOT);
    if (!validCurrency(currency)) {
      throw unprocessable("币种只能是三个大写字母（例如 EUR）");
    }
    String cycle = text(input.billingCycle()).toUpperCase(Locale.ROOT);
    if (!FILING_CYCLES.contains(cycle)) {
      throw unprocessable("计费周期只能是 " + String.join(" / ", FILING_CYCLES));
    }
    OffsetDateTime periodStart = parseTime(input.periodStart(), "归属周期起");
    OffsetDateTime periodEnd = parseTime(input.periodEnd(), "归属周期止");
    if (periodStart != null && periodEnd != null && periodEnd.isBefore(periodStart)) {
      throw unprocessable("归属周期止不能早于起");
    }
    String status = text(input.status()).toUpperCase(Locale.ROOT);
    if (!FEE_STATUSES.contains(status)) {
      throw unprocessable("费用状态只能是 " + String.join(" / ", FEE_STATUSES));
    }
    String paidBy = text(input.paidBy()).toUpperCase(Locale.ROOT);
    if (!PAID_BY.contains(paidBy)) {
      throw unprocessable("承担方只能是 " + String.join(" / ", PAID_BY));
    }
    sql.execute(
        """
        update epr_fee
           set fee_type = ?, amount = ?, currency = ?, billing_cycle = ?,
               period_start = ?::timestamptz, period_end = ?::timestamptz,
               status = ?, paid_by = ?, note = ?, updated_at = now()
         where tenant_id = ? and id = ?
        """,
        feeType,
        amount,
        currency,
        cycle,
        periodStart,
        periodEnd,
        status,
        paidBy,
        text(input.note()),
        tenant,
        target);
    return findFee(tenant, target);
  }

  /** 🔴 金额解析：**只接受十进制**，不接受科学计数法与浮点字面量。 */
  private static BigDecimal parseAmount(String raw) {
    String value = text(raw);
    if (value.isEmpty()) {
      throw unprocessable("金额不能为空");
    }
    if (value.contains("e") || value.contains("E")) {
      throw unprocessable("金额必须写成十进制（例如 12.50），不接受科学计数法");
    }
    BigDecimal amount;
    try {
      amount = new BigDecimal(value);
    } catch (RuntimeException e) {
      throw unprocessable("金额不合法（应为十进制，例如 12.50）");
    }
    if (amount.signum() < 0) {
      throw unprocessable("金额不能是负数");
    }
    if (amount.scale() > 4) {
      throw unprocessable("金额最多 4 位小数（列是 numeric(19,4)）");
    }
    return amount;
  }

  private EprFeeView findFee(UUID tenant, UUID id) {
    Record row =
        sql.fetchOne(
            "select id, registration_key, fee_type, amount, currency, billing_cycle,"
                + " period_start, period_end, status, paid_by, note, created_at"
                + " from epr_fee where tenant_id = ? and id = ?",
            tenant,
            id);
    if (row == null) {
      throw notFound();
    }
    Map<UUID, RegistrationRow> registrations = new LinkedHashMap<>();
    for (RegistrationRow registration : loadRegistrations(tenant, false)) {
      registrations.put(registration.registrationKey(), registration);
    }
    return feeView(row, registrations);
  }

  /**
   * 🔴 费用汇总：**按币种分组**。
   *
   * <p>不同币种**绝不直接相加** —— EUR + USD 不是钱。所以这里返回的是「一组一组的合计」。
   */
  public EprFeeSummary feeSummary(String registrationKey, String currency, String feeType) {
    UUID tenant = currentTenant();
    String key = text(registrationKey);
    String currencyFilter = text(currency).toUpperCase(Locale.ROOT);
    String typeFilter = text(feeType).toUpperCase(Locale.ROOT);

    StringBuilder statement =
        new StringBuilder(
            "select currency, sum(amount) as total, count(*) as cnt from epr_fee where tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    if (!key.isEmpty()) {
      statement.append(" and registration_key = ?");
      params.add(parseFilterUuid(key));
    }
    if (!currencyFilter.isEmpty()) {
      statement.append(" and currency = ?");
      params.add(currencyFilter);
    }
    if (!typeFilter.isEmpty()) {
      statement.append(" and fee_type = ?");
      params.add(typeFilter);
    }
    statement.append(" group by currency order by currency");

    List<EprFeeTotal> totals = new ArrayList<>();
    long records = 0;
    List<String> currencies = new ArrayList<>();
    for (Record row : sql.fetch(statement.toString(), params.toArray())) {
      String code = text(row.get("currency", String.class));
      totals.add(
          new EprFeeTotal(
              code,
              decimalText(row.get("total", BigDecimal.class)),
              row.get("cnt", Long.class) == null ? 0L : row.get("cnt", Long.class)));
      records += row.get("cnt", Long.class) == null ? 0L : row.get("cnt", Long.class);
      currencies.add(code);
    }
    return new EprFeeSummary(
        totals,
        records,
        currencies,
        "🔴 汇总**按币种分组** —— **不同币种绝不直接相加**（EUR + USD 不是钱）。"
            + "金额是十进制字符串（BigDecimal + NUMERIC，**禁浮点**）。"
            + "这是**本服务自己的费用记录**，不是财务凭证（12 财务在本仓尚未落地 ⇒ 未入账）。");
  }
}
