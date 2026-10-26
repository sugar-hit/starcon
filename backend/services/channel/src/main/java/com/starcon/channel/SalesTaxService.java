package com.starcon.channel;

import static com.starcon.channel.SalesTaxModels.*;
import static com.starcon.channel.SalesTaxRules.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 * 16.3.2.3 销售税与 Marketplace Facilitator。
 *
 * <p>蓝图：「处理美国各州销售税与 Marketplace Facilitator 制度：应缴/代缴状态、州级阈值、免征额、
 * 按平台的代扣代缴说明与对账。输入：店铺所在州数据；平台代扣代缴报表；自营应税销售额。
 * 输出：各州税务状态 + 代扣代缴对账结果。关系：上游 = 2 店铺、12.3.1.2；下游 = 12.6 申报。
 * <b>平台代扣代缴不等于免税</b>，卖家的申报义务依然存在，只是由平台代收代缴，需要正确对账。」
 *
 * <p>🔴🔴 本类里有八处<b>结构</b>上的不变量：
 *
 * <ol>
 *   <li><b>应缴与已代缴是两条线</b>：{@code expectedTax}（卖家欠税局的）与
 *       {@code facilitatorTax}（平台替收的）分开存、差异单独存 ——
 *       <b>不许压成一个净额</b>：压了就看不出「平台少收了要补缴」与「平台多收了要退款」。</li>
 *   <li><b>税率 / 阈值 / 免征额是数据不是常量</b>：它们落在 {@code sales_tax_rate_rule}
 *       （按州 × 生效期间版本化），本类<b>不持有任何一张州税率表</b>。</li>
 *   <li><b>规则版本快照</b>：对账结果记下当时用的版本号与税率 ⇒ 事后改规则改不动历史判定
 *       （蓝图「关键约束」第 2 条）。</li>
 *   <li><b>判不了有三态</b>：没有生效规则（{@code RULE_MISSING}）、
 *       该州没登记店铺销售税联系（{@code NEXUS_MISSING}）——
 *       <b>都不许默认按「应缴 0」算</b>，那是最贵的假结论。</li>
 *   <li><b>上游读不到就不产出结论</b>：自营应税销售额来自 12.3.1.2 的<b>授权 HTTP API</b>
 *       （{@link SalesTaxTransactionPort}）；读不到 ⇒ 显式失败，
 *       绝不拿 0 当「这段时间没卖货」糊弄过去。</li>
 *   <li><b>跨服务只走授权 API</b>：本服务<b>不</b>读 finance 的表、<b>不</b>建跨库 FK / 视图；
 *       上游 16.3.1.1 的 {@code tax_registration_record} 是<b>同库只读</b>，本模块<b>不写</b>。</li>
 *   <li><b>报表是审计凭据</b>：代扣代缴报表的原文、内容指纹、解析版本一旦落库不可改写
 *       （DB 触发器钉死）；改一次 = 上传新版本（{@code parse_version + 1}）。</li>
 *   <li><b>本模块不含申报</b>：12.6 申报未交付 ⇒ 只产出「应缴 / 已代缴 / 差异」的结论，
 *       <b>不建申报表、不做申报提交、不声称已申报</b>。</li>
 * </ol>
 */
@Service
public class SalesTaxService {

  /** 税率规则语义（能力声明与页面共用同一句话）。 */
  private static final String RATE_SEMANTICS =
      "🔴 税率、州级阈值与免征额是**可配置、可版本化的规则数据**（`sales_tax_rate_rule`），"
          + "按州 × 生效期间记录，一个州可以有多版（`rule_version` + `effective_from`/`effective_to`）。"
          + "**代码里不写死任何一张州税率表** —— 规则会变、也因州因时而异，"
          + "写死在代码里等于「改一次规则发一次版」，而且事后查不出「当时用的是哪一版」。"
          + "生效区间**不许重叠**（DB 触发器钉死）；已封版（SUPERSEDED）的版本**内容冻结**；"
          + "对账结果**快照**当时用的版本号与税率 ⇒ 事后改规则**改不动历史判定**。";

  /** 对账语义（两条金额线）。 */
  private static final String RECONCILIATION_SEMANTICS =
      "🔴 **平台代扣代缴不等于免税**：卖家的申报义务依然存在，只是由平台代收代缴。"
          + "所以本模块**同时存两条金额线** —— `expectedTax`（应缴，卖家欠税局的）与 "
          + "`facilitatorTax`（已由平台代扣代缴），差异 `variance` = 应缴 − 已代缴"
          + "（**正数 = 平台少收了，卖家要补缴**；负数 = 平台多收了）。"
          + "**不存「销售税净额」**：压成净额就看不出该补缴还是该退款。"
          + "`settlementTax` 是结算单口径的平台代扣税，**只作交叉验证**，不参与差异计算。";

  /** 上游说明。 */
  private static final String UPSTREAM_NOTE =
      "上游 = 2 渠道与店铺（**同库只读** `shop` / `platform`，本模块**不写**、不造平行店铺表）"
          + "+ 16.3.1.1 税号与辖区（**同库只读** `tax_registration_record`，本模块**不写**上游的登记档案，"
          + "也**不把**上游的辖区级登记号复制一份当自己的权威源）"
          + "+ 12.3.1.2 交易明细（**跨服务授权 HTTP API**：finance 服务 `GET /finance/transactions`，"
          + "转发调用方自己的 Authorization bearer、**绝不传租户号头**，租户由上游按已认证成员关系解析，"
          + "两侧各一道 RLS；**读不到就显式说读不到**，绝不返回空集合冒充「上游没有销售」）。";

  /** 下游说明。 */
  private static final String DOWNSTREAM_NOTE =
      "下游 = 12.6 申报（**未交付** ⇒ 本模块**不建申报表、不做申报提交、不声称已申报**；"
          + "只产出「各州应缴 / 平台已代缴 / 差异」的对账结论与申报义务视图，供其接手）、"
          + "16.3.2.1 VAT/IOSS（**未交付** ⇒ 本模块**不编造** IOSS 订单清单与代收代缴统计）。";

  private final DSLContext sql;
  private final SalesTaxTransactionPort transactions;
  private final ObjectMapper json;

  public SalesTaxService(
      DSLContext sql, SalesTaxTransactionPort transactions, ObjectMapper json) {
    this.sql = sql;
    this.transactions = transactions;
    this.json = json;
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

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(String.valueOf(raw));
  }

  /** 🔴 <b>同源 DB 时钟</b>：创建 / 更新 / 计算时刻一律从这里取。 */
  private OffsetDateTime dbNow() {
    Object raw = sql.fetchValue("select now()");
    return raw instanceof OffsetDateTime value ? value : OffsetDateTime.now(ZoneOffset.UTC);
  }

  /** 🔴 <b>同源 DB 日期</b>：规则的「今天」也走 DB，避免应用与数据库跨时区。 */
  private LocalDate dbToday() {
    Object raw = sql.fetchValue("select current_date");
    return raw instanceof LocalDate value ? value : LocalDate.now(ZoneOffset.UTC);
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

  private static LocalDate parseDate(String raw, String field) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return LocalDate.parse(value);
    } catch (RuntimeException e) {
      throw unprocessable(field + " 不是合法日期（应为 ISO-8601 日期，例如 2026-01-31）");
    }
  }

  /**
   * 🔴 金额解析：**只接受十进制字符串**，接受浮点写法一律拒。
   *
   * <p>「0.1 + 0.2 != 0.3」在销售税上意味着申报金额差一分钱 —— 而申报金额差一分钱
   * 会被税局当成「算错了」。所以本模块的金额一律 {@code BigDecimal}，输入侧不许浮点。
   */
  private static BigDecimal parseAmount(String raw, String field, boolean allowNegative) {
    String value = text(raw);
    if (value.isEmpty()) {
      return BigDecimal.ZERO.setScale(MONEY_SCALE, java.math.RoundingMode.HALF_UP);
    }
    if (value.matches(".*[eE].*")) {
      throw unprocessable(field + " 不接受科学计数法，请写成普通十进制：" + value);
    }
    BigDecimal parsed;
    try {
      parsed = new BigDecimal(value);
    } catch (RuntimeException e) {
      throw unprocessable(field + " 不是合法金额：" + value);
    }
    if (!allowNegative && parsed.signum() < 0) {
      throw unprocessable(field + " 不能为负：" + value);
    }
    if (parsed.stripTrailingZeros().scale() > MONEY_SCALE) {
      throw unprocessable(field + " 最多 " + MONEY_SCALE + " 位小数：" + value);
    }
    return money(parsed);
  }

  private static String parseCurrency(String raw, String field) {
    String value = text(raw).toUpperCase(Locale.ROOT);
    if (value.isEmpty()) {
      return null;
    }
    if (!value.matches("[A-Z]{3}")) {
      throw unprocessable(field + " 必须是三位大写字母：" + raw);
    }
    return value;
  }

  private static String parseEnum(String raw, List<String> allowed, String field) {
    String value = text(raw).toUpperCase(Locale.ROOT);
    if (value.isEmpty()) {
      return null;
    }
    if (!allowed.contains(value)) {
      throw unprocessable(field + " 取值不合法：" + raw + "（可选：" + String.join(" / ", allowed) + "）");
    }
    return value;
  }

  private static int parseInt(String raw, int fallback) {
    String value = text(raw);
    if (value.isEmpty()) {
      return fallback;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw unprocessable("分页参数不合法：" + raw);
    }
  }

  private static int clampPage(int page) {
    return Math.max(0, page);
  }

  private static int clampSize(int size) {
    return Math.min(200, Math.max(1, size));
  }

  // ==================================================================
  // 内部形状
  // ==================================================================

  /** 一条州内的店铺统计。 */
  private record ShopStateAgg(int shopCount, int registeredShopCount, List<String> shopCodes) {}

  /** 一个州在某币种下归集到的三条线。 */
  private record CurrencyLines(
      BigDecimal selfSales,
      BigDecimal facilitatorTax,
      BigDecimal settlementTax,
      Set<String> shopCodes) {}

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 能力声明：哪些下游未交付、哪些上游读得到、判定的口径是什么。
   */
  public SalesTaxCapabilities capabilities() {
    List<SalesTaxCapabilityGap> gaps =
        List.of(
            new SalesTaxCapabilityGap(
                "12.6",
                "税务申报",
                "12.6",
                false,
                "12.6 申报未交付 ⇒ 本模块只产出「各州应缴 / 平台已代缴 / 差异」的对账结论与申报义务视图，"
                    + "**不建申报表、不做申报提交、不声称已申报**。"),
            new SalesTaxCapabilityGap(
                "16.3.2.1",
                "VAT/IOSS",
                "16.3.2.1",
                false,
                "16.3.2.1 VAT/IOSS 未交付 ⇒ 本模块**不编造** IOSS 订单清单与代收代缴统计。"),
            new SalesTaxCapabilityGap(
                "16.3.1.2",
                "申报日历",
                "16.3.1.2",
                false,
                "16.3.1.2 申报日历未交付 ⇒ 本模块**不做日历、不做截止日提醒**，只给出申报义务结论。"));
    List<SalesTaxUpstream> upstreams =
        List.of(
            new SalesTaxUpstream(
                "2",
                "渠道与店铺",
                true,
                "channel",
                "同库只读 `shop` / `platform`：店铺目录与店铺所在州（本模块的 nexus 事实）。"),
            new SalesTaxUpstream(
                "16.3.1.1",
                "税号与辖区",
                true,
                "channel",
                "同库只读 `tax_registration_record`（辖区级登记档案）与 "
                    + "`tax_registration_jurisdiction`（州码表）—— 本模块**不写**上游的登记档案。"),
            new SalesTaxUpstream(
                "12.3.1.2",
                "交易明细",
                true,
                "finance",
                "经 finance 的授权 HTTP API 读（`GET /finance/transactions`）："
                    + "ORDER_SALES = 自营应税销售额，WITHHOLDING_TAX = 结算单口径的平台代扣税。"));
    return new SalesTaxCapabilities(
        gaps,
        upstreams,
        NEXUS_TYPES,
        FILING_CYCLES,
        RECONCILIATION_STATUSES,
        FILING_OBLIGATIONS,
        RATE_SEMANTICS,
        RECONCILIATION_SEMANTICS,
        UPSTREAM_NOTE,
        DOWNSTREAM_NOTE);
  }

  // ==================================================================
  // 州码表
  // ==================================================================

  /** 州码 → 州名（读 16.3.1.1 的辖区码表，**不写死州名常量表**）。 */
  private Map<String, String> stateNames() {
    Map<String, String> names = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select code, name from tax_registration_jurisdiction"
                + " where tax_regime = 'SALES_TAX' and subdivision_code <> '' order by code")) {
      names.put(row.get("code", String.class), row.get("name", String.class));
    }
    return names;
  }

  /** 州码是否真实存在（存在性由 DB 触发器再钉一次）。 */
  private boolean knownState(String stateCode) {
    Object count =
        sql.fetchValue(
            "select count(*) from tax_registration_jurisdiction"
                + " where code = ? and tax_regime = 'SALES_TAX' and subdivision_code <> ''",
            stateCode);
    return count instanceof Long value && value == 1L;
  }

  private String requireKnownState(String raw) {
    String stateCode = text(raw).toUpperCase(Locale.ROOT);
    if (stateCode.isEmpty()) {
      throw unprocessable("必须指定州码");
    }
    if (!knownState(stateCode)) {
      throw unprocessable("州码不存在：" + stateCode + "（必须是已登记的美国州销售税辖区码，例如 US-CA）");
    }
    return stateCode;
  }

  // ==================================================================
  // 各州税务状态
  // ==================================================================

  /**
   * 各州税务状态（真分页 + 真筛选）。
   *
   * @param stateCode 州码筛选（空 = 全部）
   * @param registered 是否有店已登记销售税账号（可空 = 不筛）
   * @param ruleStatus 规则状态筛选（RULE_FOUND / RULE_MISSING，可空 = 不筛）
   * @param asOf 判定「当前生效规则」的日期（空 = DB 今天）
   */
  public SalesTaxStatePage states(
      String stateCode, Boolean registered, String ruleStatus, String asOf, int page, int size) {
    UUID tenant = currentTenant();
    LocalDate asOfDate = asOf == null || asOf.isBlank() ? dbToday() : parseDate(asOf, "判定日期");
    String stateFilter = text(stateCode).toUpperCase(Locale.ROOT);
    String ruleFilter = text(ruleStatus).toUpperCase(Locale.ROOT);
    int p = clampPage(page);
    int s = clampSize(size);

    Map<String, ShopStateAgg> shopAggs = shopStateAggregates(tenant);
    List<String> universe = stateUniverse(tenant);
    List<String> matched = new ArrayList<>();
    for (String code : universe) {
      if (!stateFilter.isEmpty() && !stateFilter.equals(code)) {
        continue;
      }
      if (registered != null) {
        ShopStateAgg agg = shopAggs.getOrDefault(code, new ShopStateAgg(0, 0, List.of()));
        if (registered != (agg.registeredShopCount() > 0)) {
          continue;
        }
      }
      if (!ruleFilter.isEmpty()) {
        boolean found = SalesTaxRules.effectiveRule(loadRules(tenant, code), asOfDate) != null;
        boolean wantFound = SalesTaxRules.RULE_FOUND.equals(ruleFilter);
        if (found != wantFound) {
          continue;
        }
      }
      matched.add(code);
    }
    long total = matched.size();

    Map<String, List<SalesTaxRateRuleView>> rules = new LinkedHashMap<>();
    Map<String, Integer> scopeCounts = facilitatorScopeCounts(tenant);
    Map<String, SalesTaxReconciliationView> latest = latestReconciliations(tenant);
    Map<String, String> names = stateNames();

    List<SalesTaxStateRow> items = new ArrayList<>();
    for (String code :
        matched.subList(
            Math.min(p * s, matched.size()), Math.min((p + 1) * s, matched.size()))) {
      ShopStateAgg agg = shopAggs.getOrDefault(code, new ShopStateAgg(0, 0, List.of()));
      rules.computeIfAbsent(code, key -> loadRules(tenant, key));
      SalesTaxRateRuleView rule = SalesTaxRules.effectiveRule(rules.get(code), asOfDate);
      SalesTaxReconciliationView reconciliation = latest.get(code);
      items.add(
          buildStateRow(
              code,
              names.getOrDefault(code, code),
              agg,
              rule,
              scopeCounts.getOrDefault(code, 0),
              reconciliation));
    }

    return new SalesTaxStatePage(
        items,
        total,
        p,
        s,
        "州清单 = 本租户有销售税联系的州 ∪ 有代扣代缴报表的州 ∪ 已有对账结果的州 ∪ 已配税率规则的州。"
            + "金额按币种分开，**不同币种绝不直接相加**。");
  }

  /**
   * 州清单（四个来源取并集）。
   *
   * <p>🔴 **税率规则也在里面**：租户给某个州配了规则，说明这个州对它是相关的；
   * 而「配了规则却一家店的所在州都没登记」本身就是最该被看见的缺口 ——
   * 少列一个州，这条提示就永远不出现。
   */
  private List<String> stateUniverse(UUID tenant) {
    Set<String> codes = new LinkedHashSet<>();
    for (Record row :
        sql.fetch(
            "select distinct state_code from sales_tax_shop_state where tenant_id = ?"
                + " union select distinct state_code from sales_tax_facilitator_line where tenant_id = ?"
                + " union select distinct state_code from sales_tax_reconciliation where tenant_id = ?"
                + " union select distinct state_code from sales_tax_rate_rule where tenant_id = ?"
                + " order by 1",
            tenant,
            tenant,
            tenant,
            tenant)) {
      codes.add(row.get("state_code", String.class));
    }
    return List.copyOf(codes);
  }

  /** 每个州的店铺统计。 */
  private Map<String, ShopStateAgg> shopStateAggregates(UUID tenant) {
    Map<String, ShopStateAgg> result = new LinkedHashMap<>();
    Map<String, Set<String>> codesByState = new LinkedHashMap<>();
    Map<String, Integer> countByState = new LinkedHashMap<>();
    Map<String, Integer> registeredByState = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select ss.state_code, ss.shop_id, ss.registered, s.code as shop_code"
                + " from sales_tax_shop_state ss"
                + " join shop s on s.tenant_id = ss.tenant_id and s.id = ss.shop_id"
                + " where ss.tenant_id = ?"
                + " order by ss.state_code, s.code",
            tenant)) {
      String state = row.get("state_code", String.class);
      countByState.merge(state, 1, Integer::sum);
      codesByState
          .computeIfAbsent(state, key -> new LinkedHashSet<>())
          .add(row.get("shop_code", String.class));
      if (Boolean.TRUE.equals(row.get("registered", Boolean.class))) {
        registeredByState.merge(state, 1, Integer::sum);
      }
    }
    countByState.forEach(
        (state, count) ->
            result.put(
                state,
                new ShopStateAgg(
                    count,
                    registeredByState.getOrDefault(state, 0),
                    List.copyOf(codesByState.getOrDefault(state, Set.of())))));
    return result;
  }

  /** 每个州登记了几条「平台 × 代扣代缴说明」。 */
  private Map<String, Integer> facilitatorScopeCounts(UUID tenant) {
    Map<String, Integer> result = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select state_code, count(*) as n from sales_tax_facilitator_scope"
                + " where tenant_id = ? group by state_code",
            tenant)) {
      result.put(row.get("state_code", String.class), row.get("n", Integer.class));
    }
    return result;
  }

  /** 每个州最近一次对账结果（按期间止排序取最新）。 */
  private Map<String, SalesTaxReconciliationView> latestReconciliations(UUID tenant) {
    Map<String, SalesTaxReconciliationView> result = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select distinct on (state_code) * from sales_tax_reconciliation"
                + " where tenant_id = ? order by state_code, period_end desc, period_start desc",
            tenant)) {
      result.put(row.get("state_code", String.class), readReconciliation(row));
    }
    return result;
  }

  /** 组装一个州的状态行。 */
  private SalesTaxStateRow buildStateRow(
      String stateCode,
      String stateName,
      ShopStateAgg agg,
      SalesTaxRateRuleView rule,
      int scopeCount,
      SalesTaxReconciliationView reconciliation) {
    List<String> notes = new ArrayList<>();
    String registrationStatus;
    if (agg.shopCount() == 0) {
      registrationStatus = SalesTaxRules.REGISTRATION_NOT_REGISTERED;
    } else if (agg.registeredShopCount() == agg.shopCount()) {
      registrationStatus = SalesTaxRules.REGISTRATION_REGISTERED;
    } else {
      registrationStatus = SalesTaxRules.REGISTRATION_PARTIAL;
    }
    if (agg.shopCount() == 0) {
      notes.add("该州没有登记任何店铺的销售税联系 —— 自营应税销售额无法归集。");
    } else if (agg.registeredShopCount() < agg.shopCount()) {
      notes.add(
          "有 "
              + (agg.shopCount() - agg.registeredShopCount())
              + " 家店在该州还没登记销售税账号。");
    }
    if (rule == null) {
      notes.add("该州没有生效的税率规则 —— 应缴**判不了**（不许默认按 0 税率算）。");
    }
    if (scopeCount == 0) {
      notes.add("该州还没有登记「平台 × 代扣代缴说明」。");
    }

    String reconciliationStatus = reconciliation == null ? null : reconciliation.status();
    String filingObligation = filingObligationOf(registrationStatus, reconciliation);
    String status = stateStatusOf(registrationStatus, rule, reconciliation);
    String statusLabel = stateStatusLabel(status);

    return new SalesTaxStateRow(
        stateCode,
        stateName,
        agg.shopCount(),
        agg.registeredShopCount(),
        registrationStatus,
        rule == null ? SalesTaxRules.RULE_MISSING : SalesTaxRules.RULE_FOUND,
        rule == null ? null : rule.ruleVersion(),
        rule == null ? null : rule.baseRate(),
        rule == null ? null : rule.thresholdAmount(),
        rule == null ? null : rule.exemptionAmount(),
        rule == null ? "" : rule.filingCycle(),
        rule != null && rule.marketplaceFacilitator(),
        scopeCount,
        reconciliation == null ? null : reconciliation.id(),
        reconciliation == null ? null : reconciliation.periodStart(),
        reconciliation == null ? null : reconciliation.periodEnd(),
        reconciliation == null ? null : reconciliation.currency(),
        reconciliation == null ? null : reconciliation.selfTaxableSales(),
        reconciliation == null ? null : reconciliation.expectedTax(),
        reconciliation == null ? null : reconciliation.facilitatorTax(),
        reconciliation == null ? null : reconciliation.variance(),
        reconciliationStatus,
        filingObligation,
        status,
        statusLabel,
        notes);
  }

  /** 州的汇总状态（供筛选与展示）。 */
  private static String stateStatusOf(
      String registrationStatus, SalesTaxRateRuleView rule, SalesTaxReconciliationView reconciliation) {
    if (reconciliation != null) {
      String status = reconciliation.status();
      if (SalesTaxRules.RECON_RULE_MISSING.equals(status)
          || SalesTaxRules.RECON_NEXUS_MISSING.equals(status)) {
        return "INDETERMINATE";
      }
      if (SalesTaxRules.RECON_VARIANCE.equals(status)) {
        return "VARIANCE";
      }
      if (SalesTaxRules.RECON_BALANCED.equals(status)) {
        return "BALANCED";
      }
      if (SalesTaxRules.RECON_BELOW_THRESHOLD.equals(status)) {
        return "BELOW_THRESHOLD";
      }
      return "NO_ACTIVITY";
    }
    if (rule == null) {
      return "INDETERMINATE";
    }
    if (SalesTaxRules.REGISTRATION_NOT_REGISTERED.equals(registrationStatus)) {
      return "NOT_REGISTERED";
    }
    return "PENDING";
  }

  private static String stateStatusLabel(String status) {
    return switch (status) {
      case "BALANCED" -> "已对平";
      case "VARIANCE" -> "有差异";
      case "BELOW_THRESHOLD" -> "未达州级阈值";
      case "NOT_REGISTERED" -> "未登记销售税账号";
      case "PENDING" -> "待对账";
      case "NO_ACTIVITY" -> "期间无销售无代缴";
      case "INDETERMINATE" -> "判不了";
      default -> "未知";
    };
  }

  // ==================================================================
  // 店铺目录
  // ==================================================================

  /** 店铺目录（读本服务自己的 `shop`）+ 它在哪些州有销售税联系（真分页 + 真筛选）。 */
  public SalesTaxShopPage shops(String keyword, String platformCode, int page, int size) {
    UUID tenant = currentTenant();
    int p = clampPage(page);
    int s = clampSize(size);
    String kw = text(keyword);
    String platform = text(platformCode);

    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("s.tenant_id = ?");
    binds.add(tenant);
    if (!kw.isEmpty()) {
      where.add("(s.code ilike ? or s.name ilike ?)");
      binds.add("%" + kw + "%");
      binds.add("%" + kw + "%");
    }
    if (!platform.isEmpty()) {
      where.add("p.code = ?");
      binds.add(platform);
    }
    String clause = " where " + String.join(" and ", where);

    long total =
        ((Long)
                sql.fetchValue(
                    "select count(*) from shop s join platform p on p.id = s.platform_id" + clause,
                    binds.toArray()))
            .longValue();

    Map<String, List<String>> statesByShop = new LinkedHashMap<>();
    Map<String, Integer> registeredByShop = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select ss.shop_id, ss.state_code, ss.registered from sales_tax_shop_state ss"
                + " where ss.tenant_id = ? order by ss.state_code",
            tenant)) {
      String shopId = row.get("shop_id", UUID.class).toString();
      statesByShop
          .computeIfAbsent(shopId, key -> new ArrayList<>())
          .add(row.get("state_code", String.class));
      if (Boolean.TRUE.equals(row.get("registered", Boolean.class))) {
        registeredByShop.merge(shopId, 1, Integer::sum);
      }
    }

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);
    List<SalesTaxShopView> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select s.id, s.code, s.name, s.status, s.operating_mode,"
                + " p.code as platform_code, p.name as platform_name"
                + " from shop s join platform p on p.id = s.platform_id"
                + clause
                + " order by s.code limit ? offset ?",
            pageBinds.toArray())) {
      String shopId = row.get("id", UUID.class).toString();
      items.add(
          new SalesTaxShopView(
              shopId,
              row.get("code", String.class),
              row.get("name", String.class),
              row.get("platform_code", String.class),
              row.get("platform_name", String.class),
              row.get("status", String.class),
              row.get("operating_mode", String.class),
              List.copyOf(statesByShop.getOrDefault(shopId, List.of())),
              registeredByShop.getOrDefault(shopId, 0)));
    }
    return new SalesTaxShopPage(
        items, total, p, s, "店铺目录读本服务自己的 `shop` 表，**不造平行店铺表**。");
  }

  // ==================================================================
  // 税率规则
  // ==================================================================

  /** 某州的全规则版本（按生效起始日倒序）。 */
  private List<SalesTaxRateRuleView> loadRules(UUID tenant, String stateCode) {
    List<SalesTaxRateRuleView> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select * from sales_tax_rate_rule where tenant_id = ? and state_code = ?"
                + " order by effective_from desc, rule_version desc",
            tenant,
            stateCode)) {
      items.add(readRule(row));
    }
    return items;
  }

  private SalesTaxRateRuleView readRule(Record row) {
    return readRule(row, stateNames());
  }

  /** 读一行规则（州名走传入的码表，避免逐行查一次）。 */
  private SalesTaxRateRuleView readRule(Record row, Map<String, String> names) {
    String stateCode = row.get("state_code", String.class);
    return new SalesTaxRateRuleView(
        row.get("id", UUID.class).toString(),
        stateCode,
        names.getOrDefault(stateCode, stateCode),
        row.get("rule_version", Integer.class),
        row.get("effective_from", LocalDate.class),
        row.get("effective_to", LocalDate.class),
        row.get("base_rate", BigDecimal.class),
        row.get("threshold_amount", BigDecimal.class),
        row.get("exemption_amount", BigDecimal.class),
        Boolean.TRUE.equals(row.get("marketplace_facilitator", Boolean.class)),
        row.get("filing_cycle", String.class),
        row.get("status", String.class),
        row.get("note", String.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  /** 税率规则清单（真分页 + 真筛选）。 */
  public SalesTaxRateRulePage rateRules(String stateCode, String status, int page, int size) {
    UUID tenant = currentTenant();
    int p = clampPage(page);
    int s = clampSize(size);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?");
    binds.add(tenant);
    String state = text(stateCode).toUpperCase(Locale.ROOT);
    if (!state.isEmpty()) {
      where.add("state_code = ?");
      binds.add(state);
    }
    String statusFilter = text(status).toUpperCase(Locale.ROOT);
    if (!statusFilter.isEmpty()) {
      where.add("status = ?");
      binds.add(statusFilter);
    }
    String clause = " where " + String.join(" and ", where);
    long total = ((Long) sql.fetchValue("select count(*) from sales_tax_rate_rule" + clause, binds.toArray())).longValue();

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);
    List<SalesTaxRateRuleView> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select * from sales_tax_rate_rule" + clause
                + " order by state_code, effective_from desc, rule_version desc limit ? offset ?",
            pageBinds.toArray())) {
      items.add(readRule(row));
    }
    return new SalesTaxRateRulePage(
        items, total, p, s, "规则是**可配置、可版本化**的数据；生效区间左闭右开且不许重叠。");
  }

  /** 某一刻生效的那一版规则（可解释性）。 */
  public SalesTaxEffectiveRule effectiveRule(String stateCode, String asOf) {
    UUID tenant = currentTenant();
    String state = requireKnownState(stateCode);
    LocalDate date = asOf == null || asOf.isBlank() ? dbToday() : parseDate(asOf, "判定日期");
    List<SalesTaxRateRuleView> rules = loadRules(tenant, state);
    SalesTaxRateRuleView rule = SalesTaxRules.effectiveRule(rules, date);
    return new SalesTaxEffectiveRule(
        state,
        stateNames().getOrDefault(state, state),
        date,
        rule != null,
        rule,
        rule == null
            ? "该州在 " + date + " 没有生效的税率规则 —— **判不了**（不许默认按 0 税率算）。"
            : "生效的是第 " + rule.ruleVersion() + " 版（自 " + rule.effectiveFrom() + " 起）。");
  }

  /** 单条规则详情。 */
  public SalesTaxRateRuleView rateRule(String id) {
    UUID tenant = currentTenant();
    UUID ruleId = parseUuid(id);
    if (ruleId == null) {
      throw notFound();
    }
    for (Record row :
        sql.fetch("select * from sales_tax_rate_rule where tenant_id = ? and id = ?", tenant, ruleId)) {
      return readRule(row);
    }
    throw notFound();
  }

  /** 录入一版税率规则。 */
  public SalesTaxRateRuleView createRateRule(SalesTaxRateRuleInput input) {
    UUID tenant = currentTenant();
    if (input == null) {
      throw unprocessable("请求体不能为空");
    }
    String state = requireKnownState(input.stateCode());
    LocalDate effectiveFrom = parseDate(input.effectiveFrom(), "生效起始日");
    if (effectiveFrom == null) {
      throw unprocessable("必须指定生效起始日");
    }
    LocalDate effectiveTo = parseDate(input.effectiveTo(), "生效终止日");
    if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
      throw unprocessable("生效终止日必须晚于生效起始日");
    }
    BigDecimal baseRate = parseAmount(input.baseRate(), "州基准税率", false);
    if (baseRate.compareTo(BigDecimal.ONE) > 0) {
      throw unprocessable("州基准税率必须在 0 与 1 之间（小数率，0.0725 = 7.25%）");
    }
    BigDecimal threshold = parseAmount(input.thresholdAmount(), "州级阈值", false);
    BigDecimal exemption = parseAmount(input.exemptionAmount(), "免征额", false);
    String filingCycle =
        input.filingCycle() == null || input.filingCycle().isBlank()
            ? CYCLE_QUARTERLY
            : parseEnum(input.filingCycle(), FILING_CYCLES, "申报周期");
    boolean facilitator =
        input.marketplaceFacilitator() != null && input.marketplaceFacilitator();

    int nextVersion =
        ((Number)
                sql.fetchValue(
                        "select coalesce(max(rule_version),0) + 1 from sales_tax_rate_rule"
                            + " where tenant_id = ? and state_code = ?",
                        tenant,
                        state))
            .intValue();
    UUID id = Ids.next();
    String status = effectiveTo == null ? "ACTIVE" : "SUPERSEDED";
    try {
      sql.execute(
          "insert into sales_tax_rate_rule"
              + " (id, tenant_id, state_code, rule_version, effective_from, effective_to,"
              + "  base_rate, threshold_amount, exemption_amount, marketplace_facilitator,"
              + "  filing_cycle, status, note, created_at, updated_at)"
              + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
          id,
          tenant,
          state,
          nextVersion,
          effectiveFrom,
          effectiveTo,
          rate(baseRate),
          threshold,
          exemption,
          facilitator,
          filingCycle,
          status,
          text(input.note()));
    } catch (org.jooq.exception.DataAccessException e) {
      throw conflict(
          "这版规则与该州已有版本的生效区间重叠了 —— 同一州不允许两版规则同时生效。");
    }
    return rateRule(id.toString());
  }

  /**
   * 🔴 **发新版本**：把当前生效版封版 + 插入新版（**不回填**历史区间）。
   *
   * <p>「改一个数字就等于说谎」的防线：历史版本的内容在 DB 层被触发器冻结。
   */
  public SalesTaxRateRuleView updateRateRule(String id, SalesTaxRateRuleInput input) {
    UUID tenant = currentTenant();
    if (input == null) {
      throw unprocessable("请求体不能为空");
    }
    UUID ruleId = parseUuid(id);
    if (ruleId == null) {
      throw notFound();
    }
    SalesTaxRateRuleView current = rateRule(id);
    if (!"ACTIVE".equals(current.status())) {
      throw unprocessable("只能对**当前生效**的规则版本发新版（该版本已封版或已撤下）");
    }
    LocalDate effectiveFrom = parseDate(input.effectiveFrom(), "新版本生效起始日");
    if (effectiveFrom == null) {
      throw unprocessable("必须指定新版本的生效起始日");
    }
    if (!effectiveFrom.isAfter(current.effectiveFrom())) {
      throw unprocessable("新版本的生效起始日必须晚于当前版本的生效起始日（不许回填）");
    }
    LocalDate effectiveTo = parseDate(input.effectiveTo(), "新版本生效终止日");
    if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
      throw unprocessable("生效终止日必须晚于生效起始日");
    }
    BigDecimal baseRate = parseAmount(input.baseRate(), "州基准税率", false);
    if (baseRate.compareTo(BigDecimal.ONE) > 0) {
      throw unprocessable("州基准税率必须在 0 与 1 之间（小数率，0.0725 = 7.25%）");
    }
    BigDecimal threshold = parseAmount(input.thresholdAmount(), "州级阈值", false);
    BigDecimal exemption = parseAmount(input.exemptionAmount(), "免征额", false);
    String filingCycle =
        input.filingCycle() == null || input.filingCycle().isBlank()
            ? CYCLE_QUARTERLY
            : parseEnum(input.filingCycle(), FILING_CYCLES, "申报周期");
    boolean facilitator =
        input.marketplaceFacilitator() != null && input.marketplaceFacilitator();
    String state = current.stateCode();

    // 先封旧版（关窗口），再插新版 —— 顺序反了会撞上「生效区间重叠」。
    UUID newId = Ids.next();
    try {
      sql.execute(
          "update sales_tax_rate_rule set effective_to = ?, status = 'SUPERSEDED', updated_at = now()"
              + " where tenant_id = ? and id = ? and status = 'ACTIVE'",
          effectiveFrom,
          tenant,
          ruleId);
      sql.execute(
          "insert into sales_tax_rate_rule"
              + " (id, tenant_id, state_code, rule_version, effective_from, effective_to,"
              + "  base_rate, threshold_amount, exemption_amount, marketplace_facilitator,"
              + "  filing_cycle, status, note, created_at, updated_at)"
              + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
          newId,
          tenant,
          state,
          current.ruleVersion() + 1,
          effectiveFrom,
          effectiveTo,
          rate(baseRate),
          threshold,
          exemption,
          facilitator,
          filingCycle,
          effectiveTo == null ? "ACTIVE" : "SUPERSEDED",
          text(input.note()));
    } catch (org.jooq.exception.DataAccessException e) {
      throw conflict(
          "这版规则与该州已有版本的生效区间重叠了 —— 同一州不允许两版规则同时生效。");
    }
    return rateRule(newId.toString());
  }

  /** 撤下一版规则（**不物理删除**：规则是审计凭据）。 */
  public SalesTaxRateRuleView archiveRateRule(String id) {
    UUID tenant = currentTenant();
    UUID ruleId = parseUuid(id);
    if (ruleId == null) {
      throw notFound();
    }
    SalesTaxRateRuleView current = rateRule(id);
    if ("ARCHIVED".equals(current.status())) {
      return current;
    }
    sql.execute(
        "update sales_tax_rate_rule set status = 'ARCHIVED', updated_at = now()"
            + " where tenant_id = ? and id = ?",
        tenant,
        ruleId);
    return rateRule(id);
  }

  // ==================================================================
  // 店铺州联系
  // ==================================================================

  /** 店铺州联系清单（真分页 + 真筛选）。 */
  public SalesTaxShopStatePage shopStates(
      String shopId, String stateCode, String nexusType, int page, int size) {
    UUID tenant = currentTenant();
    int p = clampPage(page);
    int s = clampSize(size);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("ss.tenant_id = ?");
    binds.add(tenant);
    UUID shop = parseFilterUuid(shopId);
    if (shop != null) {
      where.add("ss.shop_id = ?");
      binds.add(shop);
    }
    String state = text(stateCode).toUpperCase(Locale.ROOT);
    if (!state.isEmpty()) {
      where.add("ss.state_code = ?");
      binds.add(state);
    }
    String nexus = text(nexusType).toUpperCase(Locale.ROOT);
    if (!nexus.isEmpty()) {
      where.add("ss.nexus_type = ?");
      binds.add(nexus);
    }
    String clause = " where " + String.join(" and ", where);
    long total =
        ((Long) sql.fetchValue("select count(*) from sales_tax_shop_state ss" + clause, binds.toArray()))
            .longValue();

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);
    List<SalesTaxShopStateView> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select ss.*, s.code as shop_code, s.name as shop_name"
                + " from sales_tax_shop_state ss"
                + " join shop s on s.tenant_id = ss.tenant_id and s.id = ss.shop_id"
                + clause
                + " order by ss.state_code, s.code limit ? offset ?",
            pageBinds.toArray())) {
      items.add(readShopState(row));
    }
    return new SalesTaxShopStatePage(
        items, total, p, s, "这是「店铺所在州」的事实来源 —— 自营应税销售额按它归集到州。");
  }

  private SalesTaxShopStateView readShopState(Record row) {
    String stateCode = row.get("state_code", String.class);
    return new SalesTaxShopStateView(
        row.get("id", UUID.class).toString(),
        row.get("shop_id", UUID.class).toString(),
        row.get("shop_code", String.class),
        row.get("shop_name", String.class),
        stateCode,
        stateNames().getOrDefault(stateCode, stateCode),
        row.get("nexus_type", String.class),
        Boolean.TRUE.equals(row.get("registered", Boolean.class)),
        row.get("registration_no", String.class),
        row.get("registered_from", LocalDate.class),
        row.get("note", String.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  /** 登记一家店在一个州的销售税联系。 */
  public SalesTaxShopStateView createShopState(SalesTaxShopStateInput input) {
    UUID tenant = currentTenant();
    if (input == null) {
      throw unprocessable("请求体不能为空");
    }
    UUID shopId = parseUuid(input.shopId());
    if (shopId == null) {
      throw unprocessable("必须指定店铺");
    }
    String state = requireKnownState(input.stateCode());
    String nexus = parseEnum(input.nexusType(), NEXUS_TYPES, "销售税联系类型");
    if (nexus == null) {
      throw unprocessable("必须指定销售税联系类型");
    }
    boolean registered = input.registered() != null && input.registered();
    LocalDate registeredFrom = parseDate(input.registeredFrom(), "登记生效日");

    UUID id = Ids.next();
    try {
      sql.execute(
          "insert into sales_tax_shop_state"
              + " (id, tenant_id, shop_id, state_code, nexus_type, registered, registration_no,"
              + "  registered_from, note, created_at, updated_at)"
              + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
          id,
          tenant,
          shopId,
          state,
          nexus,
          registered,
          text(input.registrationNo()),
          registeredFrom,
          text(input.note()));
    } catch (org.jooq.exception.DataAccessException e) {
      String message = String.valueOf(e.getMessage());
      if (message.contains("sales_tax_shop_state_shop_ref")) {
        throw unprocessable("该店铺不在当前租户的店铺目录里");
      }
      if (message.contains("sales_tax_shop_state_state_ref")) {
        throw unprocessable("州码不存在：" + state);
      }
      throw conflict("这家店在这个州已经有一条联系记录了（重录请改那一行）");
    }
    return shopState(id.toString());
  }

  /** 改一条店铺州联系。 */
  public SalesTaxShopStateView updateShopState(String id, SalesTaxShopStateInput input) {
    UUID tenant = currentTenant();
    if (input == null) {
      throw unprocessable("请求体不能为空");
    }
    UUID stateId = parseUuid(id);
    if (stateId == null) {
      throw notFound();
    }
    shopState(id);
    String nexus = parseEnum(input.nexusType(), NEXUS_TYPES, "销售税联系类型");
    if (nexus == null) {
      throw unprocessable("必须指定销售税联系类型");
    }
    boolean registered = input.registered() != null && input.registered();
    LocalDate registeredFrom = parseDate(input.registeredFrom(), "登记生效日");
    try {
      sql.execute(
          "update sales_tax_shop_state set nexus_type = ?, registered = ?, registration_no = ?,"
              + " registered_from = ?, note = ?, updated_at = now()"
              + " where tenant_id = ? and id = ?",
          nexus,
          registered,
          text(input.registrationNo()),
          registeredFrom,
          text(input.note()),
          tenant,
          stateId);
    } catch (org.jooq.exception.DataAccessException e) {
      throw conflict("这条联系记录改不了（可能已不在当前租户内）");
    }
    return shopState(id);
  }

  private SalesTaxShopStateView shopState(String id) {
    UUID tenant = currentTenant();
    UUID stateId = parseUuid(id);
    if (stateId == null) {
      throw notFound();
    }
    for (Record row :
        sql.fetch(
            "select ss.*, s.code as shop_code, s.name as shop_name"
                + " from sales_tax_shop_state ss"
                + " join shop s on s.tenant_id = ss.tenant_id and s.id = ss.shop_id"
                + " where ss.tenant_id = ? and ss.id = ?",
            tenant,
            stateId)) {
      return readShopState(row);
    }
    throw notFound();
  }

  // ==================================================================
  // 按平台的代扣代缴说明
  // ==================================================================

  /** 代扣代缴说明清单（真分页 + 真筛选）。 */
  public SalesTaxFacilitatorScopePage facilitatorScopes(
      String stateCode, String platformCode, int page, int size) {
    UUID tenant = currentTenant();
    int p = clampPage(page);
    int s = clampSize(size);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?");
    binds.add(tenant);
    String state = text(stateCode).toUpperCase(Locale.ROOT);
    if (!state.isEmpty()) {
      where.add("state_code = ?");
      binds.add(state);
    }
    String platform = text(platformCode);
    if (!platform.isEmpty()) {
      where.add("platform_code = ?");
      binds.add(platform);
    }
    String clause = " where " + String.join(" and ", where);
    long total =
        ((Long)
                sql.fetchValue(
                    "select count(*) from sales_tax_facilitator_scope" + clause, binds.toArray()))
            .longValue();
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);
    List<SalesTaxFacilitatorScopeView> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select * from sales_tax_facilitator_scope" + clause
                + " order by state_code, platform_code limit ? offset ?",
            pageBinds.toArray())) {
      items.add(readScope(row));
    }
    return new SalesTaxFacilitatorScopePage(
        items, total, p, s, "逐（州 × 平台）的代扣代缴说明：平台代不代收、汇给谁、要不要单独报表。");
  }

  private SalesTaxFacilitatorScopeView readScope(Record row) {
    String stateCode = row.get("state_code", String.class);
    return new SalesTaxFacilitatorScopeView(
        row.get("id", UUID.class).toString(),
        stateCode,
        stateNames().getOrDefault(stateCode, stateCode),
        row.get("platform_code", String.class),
        Boolean.TRUE.equals(row.get("facilitator_enabled", Boolean.class)),
        row.get("remit_to", String.class),
        Boolean.TRUE.equals(row.get("report_required", Boolean.class)),
        row.get("note", String.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  /** 登记一条代扣代缴说明。 */
  public SalesTaxFacilitatorScopeView createFacilitatorScope(SalesTaxFacilitatorScopeInput input) {
    UUID tenant = currentTenant();
    if (input == null) {
      throw unprocessable("请求体不能为空");
    }
    String state = requireKnownState(input.stateCode());
    String platform = text(input.platformCode());
    if (platform.isEmpty()) {
      throw unprocessable("必须指定平台编码");
    }
    boolean enabled = input.facilitatorEnabled() != null && input.facilitatorEnabled();
    boolean reportRequired = input.reportRequired() == null || input.reportRequired();
    UUID id = Ids.next();
    try {
      sql.execute(
          "insert into sales_tax_facilitator_scope"
              + " (id, tenant_id, state_code, platform_code, facilitator_enabled, remit_to,"
              + "  report_required, note, created_at, updated_at)"
              + " values (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
          id,
          tenant,
          state,
          platform,
          enabled,
          text(input.remitTo()),
          reportRequired,
          text(input.note()));
    } catch (org.jooq.exception.DataAccessException e) {
      String message = String.valueOf(e.getMessage());
      if (message.contains("sales_tax_facilitator_scope_state_ref")) {
        throw unprocessable("州码不存在：" + state);
      }
      throw conflict("这个州的这个平台已经登记过说明了（重录请改那一行）");
    }
    return facilitatorScope(id.toString());
  }

  /** 改一条代扣代缴说明。 */
  public SalesTaxFacilitatorScopeView updateFacilitatorScope(
      String id, SalesTaxFacilitatorScopeInput input) {
    UUID tenant = currentTenant();
    if (input == null) {
      throw unprocessable("请求体不能为空");
    }
    UUID scopeId = parseUuid(id);
    if (scopeId == null) {
      throw notFound();
    }
    facilitatorScope(id);
    boolean enabled = input.facilitatorEnabled() != null && input.facilitatorEnabled();
    boolean reportRequired = input.reportRequired() == null || input.reportRequired();
    sql.execute(
        "update sales_tax_facilitator_scope set facilitator_enabled = ?, remit_to = ?,"
            + " report_required = ?, note = ?, updated_at = now()"
            + " where tenant_id = ? and id = ?",
        enabled,
        text(input.remitTo()),
        reportRequired,
        text(input.note()),
        tenant,
        scopeId);
    return facilitatorScope(id);
  }

  private SalesTaxFacilitatorScopeView facilitatorScope(String id) {
    UUID tenant = currentTenant();
    UUID scopeId = parseUuid(id);
    if (scopeId == null) {
      throw notFound();
    }
    for (Record row :
        sql.fetch(
            "select * from sales_tax_facilitator_scope where tenant_id = ? and id = ?",
            tenant,
            scopeId)) {
      return readScope(row);
    }
    throw notFound();
  }

  // ==================================================================
  // 代扣代缴报表
  // ==================================================================

  /** 报表清单（真分页 + 真筛选）。 */
  public SalesTaxFacilitatorReportPage facilitatorReports(
      String platformCode, String stateCode, String periodStart, String periodEnd, int page, int size) {
    UUID tenant = currentTenant();
    int p = clampPage(page);
    int s = clampSize(size);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("r.tenant_id = ?");
    binds.add(tenant);
    String platform = text(platformCode);
    if (!platform.isEmpty()) {
      where.add("r.platform_code = ?");
      binds.add(platform);
    }
    LocalDate from = parseDate(periodStart, "期间起");
    if (from != null) {
      where.add("r.period_end >= ?");
      binds.add(from);
    }
    LocalDate to = parseDate(periodEnd, "期间止");
    if (to != null) {
      where.add("r.period_start <= ?");
      binds.add(to);
    }
    String clause = " where " + String.join(" and ", where);
    long total =
        ((Long)
                sql.fetchValue(
                    "select count(*) from sales_tax_facilitator_report r" + clause, binds.toArray()))
            .longValue();
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);
    List<SalesTaxFacilitatorReportView> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select r.id, r.tenant_id, r.platform_code, r.file_key, r.file_name, r.content_type,"
                + " r.content_hash, r.byte_size, r.parse_version, r.parse_status,"
                + " r.parse_warnings::text as parse_warnings_text, r.period_start, r.period_end,"
                + " r.uploaded_by, r.parsed_at, r.created_at,"
                + " (select count(*) from sales_tax_facilitator_line l"
                + "  where l.tenant_id = r.tenant_id and l.report_id = r.id) as line_count"
                + " from sales_tax_facilitator_report r"
                + clause
                + " order by r.period_end desc, r.created_at desc limit ? offset ?",
            pageBinds.toArray())) {
      items.add(readReport(row));
    }
    return new SalesTaxFacilitatorReportPage(
        items, total, p, s, "报表原文一字不改地留着；同一份 file_key 每重新解析一次，版本号 +1。");
  }

  private SalesTaxFacilitatorReportView readReport(Record row) {
    List<String> warnings = new ArrayList<>();
    String raw = row.get("parse_warnings_text", String.class);
    if (raw != null && !raw.isBlank()) {
      try {
        warnings.addAll(
            json.readValue(
                raw, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}));
      } catch (Exception e) {
        warnings.add("解析告警读不出来（原文仍可查）：" + e.getMessage());
      }
    }
    return new SalesTaxFacilitatorReportView(
        row.get("id", UUID.class).toString(),
        row.get("platform_code", String.class),
        row.get("file_key", String.class),
        row.get("file_name", String.class),
        row.get("content_type", String.class),
        row.get("content_hash", String.class),
        row.get("byte_size", Long.class),
        row.get("parse_version", Integer.class),
        row.get("parse_status", String.class),
        warnings,
        row.get("period_start", LocalDate.class),
        row.get("period_end", LocalDate.class),
        row.get("uploaded_by", UUID.class) == null
            ? null
            : row.get("uploaded_by", UUID.class).toString(),
        row.get("parsed_at", OffsetDateTime.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("line_count", Integer.class) == null ? 0 : row.get("line_count", Integer.class));
  }

  /** 一份报表的详情（报表本体 + 逐行解析结果）。 */
  public SalesTaxFacilitatorReportDetail facilitatorReport(String id) {
    UUID tenant = currentTenant();
    UUID reportId = parseUuid(id);
    if (reportId == null) {
      throw notFound();
    }
    for (Record row :
        sql.fetch(
            "select r.id, r.tenant_id, r.platform_code, r.file_key, r.file_name, r.content_type,"
                + " r.content_hash, r.byte_size, r.parse_version, r.parse_status,"
                + " r.parse_warnings::text as parse_warnings_text, r.period_start, r.period_end,"
                + " r.uploaded_by, r.parsed_at, r.created_at,"
                + " (select count(*) from sales_tax_facilitator_line l"
                + "  where l.tenant_id = r.tenant_id and l.report_id = r.id) as line_count"
                + " from sales_tax_facilitator_report r where r.tenant_id = ? and r.id = ?",
            tenant,
            reportId)) {
      List<SalesTaxFacilitatorLineView> lines = new ArrayList<>();
      for (Record line :
          sql.fetch(
              "select * from sales_tax_facilitator_line where tenant_id = ? and report_id = ?"
                  + " order by source_line_no",
              tenant,
              reportId)) {
        String stateCode = line.get("state_code", String.class);
        lines.add(
            new SalesTaxFacilitatorLineView(
                line.get("id", UUID.class).toString(),
                stateCode,
                stateNames().getOrDefault(stateCode, stateCode),
                line.get("platform_code", String.class),
                line.get("shop_code", String.class),
                line.get("taxable_amount", BigDecimal.class),
                line.get("tax_collected", BigDecimal.class),
                line.get("order_count", Long.class),
                line.get("currency", String.class),
                line.get("source_line_no", Integer.class),
                line.get("remark", String.class)));
      }
      return new SalesTaxFacilitatorReportDetail(readReport(row), lines);
    }
    throw notFound();
  }

  /**
   * 🔴 上传一份平台代扣代缴报表：**真上传 + 真解析 + 真落库**。
   *
   * <p>两级错误分层：
   * <ul>
   *   <li><b>文件级错误</b>（CSV 缺必需列 / JSON 不是数组 / 引号没闭合）⇒ 整个请求失败（400），
   *       <b>一条都不导入</b>。</li>
   *   <li><b>行级错误</b>（金额不是数字 / 州码不认识 / 币种不对）⇒ <b>只跳过那一行</b>，
   *       并把「第几行 + 为什么」写进返回的 warnings。</li>
   * </ul>
   */
  public SalesTaxFacilitatorImportResult importFacilitatorReport(
      SalesTaxFacilitatorImportInput input) {
    UUID tenant = currentTenant();
    if (input == null) {
      throw unprocessable("请求体不能为空");
    }
    String platform = text(input.platformCode());
    if (platform.isEmpty()) {
      throw unprocessable("必须指定平台编码");
    }
    String fileKey = text(input.fileKey());
    if (fileKey.isEmpty()) {
      throw unprocessable("必须指定报表身份（fileKey）");
    }
    String contentType = text(input.contentType()).toUpperCase(Locale.ROOT);
    if (!contentType.equals("CSV") && !contentType.equals("JSON")) {
      throw unprocessable("文件类型只能是 CSV 或 JSON：" + input.contentType());
    }
    LocalDate periodStart = parseDate(input.periodStart(), "申报期间起");
    LocalDate periodEnd = parseDate(input.periodEnd(), "申报期间止");
    if (periodStart == null || periodEnd == null) {
      throw unprocessable("必须指定申报期间起止日");
    }
    if (periodEnd.isBefore(periodStart)) {
      throw unprocessable("申报期间止不能早于起");
    }
    String content = input.content() == null ? "" : input.content();

    var parser = new SalesTaxFacilitatorImportParser(json);
    var parsed = parser.parse(contentType, content);

    // 州码是否真实存在：按码表校验，不认识的州码逐行跳过并写明原因。
    Set<String> known = stateNames().keySet();
    List<SalesTaxFacilitatorImportParser.ImportRow> usable = new ArrayList<>();
    List<String> warnings = new ArrayList<>(parsed.warnings());
    for (var row : parsed.rows()) {
      if (!known.contains(row.stateCode())) {
        warnings.add("第 " + row.lineNo() + " 行的州码 " + row.stateCode() + " 不是已登记的州，已跳过");
        continue;
      }
      usable.add(row);
    }

    int nextVersion =
        ((Number)
                sql.fetchValue(
                        "select coalesce(max(parse_version),0) + 1 from sales_tax_facilitator_report"
                            + " where tenant_id = ? and file_key = ?",
                        tenant,
                        fileKey))
            .intValue();
    UUID reportId = Ids.next();
    String parseStatus = usable.isEmpty() ? "REJECTED" : "PARSED";
    String contentHash = sha256(content);
    long byteSize = content.getBytes(StandardCharsets.UTF_8).length;
    String warningsJson;
    try {
      warningsJson = json.writeValueAsString(warnings);
    } catch (Exception e) {
      throw new IllegalStateException("解析告警序列化失败", e);
    }

    sql.execute(
        "insert into sales_tax_facilitator_report"
            + " (id, tenant_id, platform_code, file_key, file_name, content_type, raw_content,"
            + "  content_hash, byte_size, parse_version, parse_status, parse_warnings,"
            + "  period_start, period_end, uploaded_by, parsed_at, created_at)"
            + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::timestamptz, now())",
        reportId,
        tenant,
        platform,
        fileKey,
        text(input.fileName()),
        contentType,
        content,
        contentHash,
        byteSize,
        nextVersion,
        parseStatus,
        warningsJson,
        periodStart,
        periodEnd,
        currentActor(),
        dbNow());

    for (var row : usable) {
      sql.execute(
          "insert into sales_tax_facilitator_line"
              + " (id, tenant_id, report_id, state_code, platform_code, shop_code, taxable_amount,"
              + "  tax_collected, order_count, currency, source_line_no, remark, created_at)"
              + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())",
          Ids.next(),
          tenant,
          reportId,
          row.stateCode(),
          row.platformCode().isEmpty() ? platform : row.platformCode(),
          row.shopCode(),
          row.taxableAmount(),
          row.taxCollected(),
          row.orderCount(),
          row.currency(),
          row.lineNo(),
          row.remark());
    }

    return new SalesTaxFacilitatorImportResult(
        facilitatorReport(reportId.toString()).report(),
        parsed.scanned(),
        usable.size(),
        parsed.scanned() - usable.size(),
        warnings);
  }

  private static String sha256(String content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(bytes.length * 2);
      for (byte b : bytes) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  // ==================================================================
  // 对账
  // ==================================================================

  /** 对账结果清单（真分页 + 真筛选）。 */
  public SalesTaxReconciliationPage reconciliations(
      String stateCode, String periodStart, String periodEnd, String currency, int page, int size) {
    UUID tenant = currentTenant();
    int p = clampPage(page);
    int s = clampSize(size);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?");
    binds.add(tenant);
    String state = text(stateCode).toUpperCase(Locale.ROOT);
    if (!state.isEmpty()) {
      where.add("state_code = ?");
      binds.add(state);
    }
    LocalDate from = parseDate(periodStart, "期间起");
    if (from != null) {
      where.add("period_end >= ?");
      binds.add(from);
    }
    LocalDate to = parseDate(periodEnd, "期间止");
    if (to != null) {
      where.add("period_start <= ?");
      binds.add(to);
    }
    String currencyFilter = parseCurrency(currency, "币种");
    if (currencyFilter != null) {
      where.add("currency = ?");
      binds.add(currencyFilter);
    }
    String clause = " where " + String.join(" and ", where);
    long total =
        ((Long)
                sql.fetchValue(
                    "select count(*) from sales_tax_reconciliation" + clause, binds.toArray()))
            .longValue();
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);
    List<SalesTaxReconciliationView> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select * from sales_tax_reconciliation" + clause
                + " order by period_end desc, state_code, currency limit ? offset ?",
            pageBinds.toArray())) {
      items.add(readReconciliation(row));
    }
    return new SalesTaxReconciliationPage(
        items, total, p, s, RECONCILIATION_SEMANTICS);
  }

  private SalesTaxReconciliationView readReconciliation(Record row) {
    String stateCode = row.get("state_code", String.class);
    String status = row.get("status", String.class);
    return new SalesTaxReconciliationView(
        row.get("id", UUID.class).toString(),
        stateCode,
        stateNames().getOrDefault(stateCode, stateCode),
        row.get("period_start", LocalDate.class),
        row.get("period_end", LocalDate.class),
        row.get("currency", String.class),
        row.get("self_taxable_sales", BigDecimal.class),
        row.get("exempt_sales", BigDecimal.class),
        row.get("taxable_base", BigDecimal.class),
        row.get("expected_tax", BigDecimal.class),
        row.get("facilitator_tax", BigDecimal.class),
        row.get("settlement_tax", BigDecimal.class),
        row.get("variance", BigDecimal.class),
        row.get("rule_version", Integer.class),
        row.get("base_rate", BigDecimal.class),
        row.get("threshold_amount", BigDecimal.class),
        row.get("exemption_amount", BigDecimal.class),
        Boolean.TRUE.equals(row.get("rate_changed_in_period", Boolean.class)),
        status,
        reconciliationStatusLabel(status),
        row.get("shop_count", Integer.class),
        row.get("computed_at", OffsetDateTime.class));
  }

  /**
   * 🔴 **算一次对账**（真算）：自营应税销售额从 12.3.1.2 拉，平台代扣代缴从已上传的报表读，
   * 税率从生效规则读，三条线各自独立，**不压成净额**。
   *
   * <p>⚠️ 上游读不到 ⇒ <b>整个请求失败</b>（503），一条都不写 ——
   * 拿 0 当「这段时间没卖货」算出来的「应缴 0」是本模块最贵的错误。
   */
  public List<SalesTaxReconciliationView> computeReconciliations(
      SalesTaxReconciliationRequest request, String bearer) {
    UUID tenant = currentTenant();
    if (request == null) {
      throw unprocessable("请求体不能为空");
    }
    LocalDate periodStart = parseDate(request.periodStart(), "期间起");
    LocalDate periodEnd = parseDate(request.periodEnd(), "期间止");
    if (periodStart == null || periodEnd == null) {
      throw unprocessable("必须指定对账期间起止日");
    }
    if (periodEnd.isBefore(periodStart)) {
      throw unprocessable("对账期间止不能早于起");
    }
    String currencyFilter = parseCurrency(request.currency(), "币种");

    // 目标州：指定州就只算那个州；不指定就算本租户州清单里的全部。
    List<String> states = new ArrayList<>();
    String stateFilter = text(request.stateCode()).toUpperCase(Locale.ROOT);
    if (!stateFilter.isEmpty()) {
      states.add(requireKnownState(stateFilter));
    } else {
      states.addAll(stateUniverse(tenant));
    }
    if (states.isEmpty()) {
      throw unprocessable("本租户还没有任何销售税州 —— 先登记店铺所在州，或直接指定州码");
    }

    // 🔴 上游读不到就整体失败（不产出「应缴 0」的假结论）。
    var salesScan = transactions.scan(periodStart, periodEnd, "ORDER_SALES", bearer);
    var withholdingScan = transactions.scan(periodStart, periodEnd, "WITHHOLDING_TAX", bearer);
    if (salesScan.truncated() || withholdingScan.truncated()) {
      throw unprocessable(
          "上游交易明细超过本次可扫的上限（"
              + SalesTaxTransactionPort.MAX_PAGES
              + " 页 × "
              + SalesTaxTransactionPort.PAGE_SIZE
              + " 行）—— 请缩小对账期间后重算，**不要**拿截断的数当全量去申报。");
    }

    Map<String, ShopStateAgg> shopAggs = shopStateAggregates(tenant);
    Map<String, List<SalesTaxRateRuleView>> rules = new LinkedHashMap<>();
    Map<String, Map<String, CurrencyLines>> linesByState =
        facilitatorLinesByState(tenant, periodStart, periodEnd);
    OffsetDateTime computedAt = dbNow();
    UUID actor = currentActor();

    List<String> computedKeys = new ArrayList<>();
    for (String state : states) {
      ShopStateAgg agg = shopAggs.getOrDefault(state, new ShopStateAgg(0, 0, List.of()));
      rules.computeIfAbsent(state, key -> loadRules(tenant, key));
      SalesTaxRateRuleView rule = SalesTaxRules.effectiveRule(rules.get(state), periodEnd);
      boolean rateChanged =
          rules.get(state).stream()
              .anyMatch(
                  candidate ->
                      candidate.ruleVersion() != (rule == null ? -1 : rule.ruleVersion())
                          && !candidate.effectiveFrom().isBefore(periodStart)
                          && !candidate.effectiveFrom().isAfter(periodEnd));

      Set<String> shopCodes = new LinkedHashSet<>(agg.shopCodes());
      Map<String, CurrencyLines> merged = new LinkedHashMap<>();

      // ① 自营应税销售额（上游 12.3.1.2 的 ORDER_SALES 行，按店铺归集到州）
      for (var row : salesScan.items()) {
        String shopCode = text(row.shopCode());
        if (!shopCodes.contains(shopCode)) {
          continue;
        }
        mergeLine(
            merged,
            row.currency(),
            row.amount(),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            shopCode);
      }
      // ② 结算单口径的平台代扣税（上游的 WITHHOLDING_TAX 行，只作交叉验证）
      for (var row : withholdingScan.items()) {
        String shopCode = text(row.shopCode());
        if (!shopCodes.contains(shopCode)) {
          continue;
        }
        mergeLine(
            merged,
            row.currency(),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            row.amount(),
            shopCode);
      }
      // ③ 平台代扣代缴（**已上传的平台代扣代缴报表口径** —— 对账的主口径）
      Map<String, CurrencyLines> reported = linesByState.getOrDefault(state, Map.of());
      for (var entry : reported.entrySet()) {
        CurrencyLines line = entry.getValue();
        mergeLine(
            merged,
            entry.getKey(),
            BigDecimal.ZERO,
            line.facilitatorTax(),
            BigDecimal.ZERO,
            null);
      }

      // 期间完全没有任何金额的州：指定了币种就落一条「无活动」的对账行
      // （否则这个州连「算过没有」都看不出来）；没指定币种时不猜 —— 没有金额就没有可对账的东西。
      if (merged.isEmpty() && currencyFilter != null) {
        merged.put(
            currencyFilter,
            new CurrencyLines(
                BigDecimal.ZERO.setScale(MONEY_SCALE, java.math.RoundingMode.HALF_UP),
                BigDecimal.ZERO.setScale(MONEY_SCALE, java.math.RoundingMode.HALF_UP),
                BigDecimal.ZERO.setScale(MONEY_SCALE, java.math.RoundingMode.HALF_UP),
                Set.of()));
      }

      for (var entry : merged.entrySet()) {
        String currency = entry.getKey();
        if (currencyFilter != null && !currencyFilter.equals(currency)) {
          continue;
        }
        CurrencyLines line = entry.getValue();        var outcome =
            SalesTaxRules.compute(
                new SalesTaxRules.ReconciliationInput(
                    line.selfSales(),
                    line.facilitatorTax(),
                    rule == null ? BigDecimal.ZERO : rule.thresholdAmount(),
                    rule == null ? BigDecimal.ZERO : rule.exemptionAmount(),
                    rule == null ? null : rule.baseRate(),
                    rule == null ? null : rule.ruleVersion(),
                    agg.shopCount()));
        UUID id = Ids.next();
        sql.execute(
            "insert into sales_tax_reconciliation"
                + " (id, tenant_id, state_code, period_start, period_end, currency,"
                + "  self_taxable_sales, exempt_sales, taxable_base, expected_tax, facilitator_tax,"
                + "  settlement_tax, variance, rule_version, base_rate, threshold_amount,"
                + "  exemption_amount, rate_changed_in_period, status, shop_count, computed_at,"
                + "  computed_by, created_at)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?, now())"
                + " on conflict (tenant_id, state_code, period_start, period_end, currency)"
                + " do update set self_taxable_sales = excluded.self_taxable_sales,"
                + "  exempt_sales = excluded.exempt_sales, taxable_base = excluded.taxable_base,"
                + "  expected_tax = excluded.expected_tax, facilitator_tax = excluded.facilitator_tax,"
                + "  settlement_tax = excluded.settlement_tax, variance = excluded.variance,"
                + "  rule_version = excluded.rule_version, base_rate = excluded.base_rate,"
                + "  threshold_amount = excluded.threshold_amount, exemption_amount = excluded.exemption_amount,"
                + "  rate_changed_in_period = excluded.rate_changed_in_period, status = excluded.status,"
                + "  shop_count = excluded.shop_count, computed_at = excluded.computed_at,"
                + "  computed_by = excluded.computed_by",
            id,
            tenant,
            state,
            periodStart,
            periodEnd,
            currency,
            money(line.selfSales()),
            money(outcome.exemptSales()),
            money(outcome.taxableBase()),
            money(outcome.expectedTax()),
            money(outcome.facilitatorTax()),
            money(line.settlementTax()),
            money(outcome.variance()),
            rule == null ? 0 : rule.ruleVersion(),
            rule == null ? BigDecimal.ZERO.setScale(RATE_SCALE, java.math.RoundingMode.HALF_UP) : rule.baseRate(),
            rule == null ? BigDecimal.ZERO.setScale(MONEY_SCALE, java.math.RoundingMode.HALF_UP) : rule.thresholdAmount(),
            rule == null ? BigDecimal.ZERO.setScale(MONEY_SCALE, java.math.RoundingMode.HALF_UP) : rule.exemptionAmount(),
            rateChanged,
            outcome.status(),
            agg.shopCount(),
            computedAt,
            actor);
        computedKeys.add(state + "|" + currency);
      }
    }

    // 读回本次算出来的结果（upsert 可能保留旧行的 id，所以按唯一键读回而不是按新 id）。
    List<SalesTaxReconciliationView> results = new ArrayList<>();
    for (String key : computedKeys) {
      String[] parts = key.split("\\|", 2);
      for (Record row :
          sql.fetch(
              "select * from sales_tax_reconciliation"
                  + " where tenant_id = ? and state_code = ? and period_start = ?"
                  + " and period_end = ? and currency = ?",
              tenant,
              parts[0],
              periodStart,
              periodEnd,
              parts[1])) {
        results.add(readReconciliation(row));
      }
    }
    return results;
  }

  /** 把一条金额并进某个币种的汇总里。 */
  private static void mergeLine(
      Map<String, CurrencyLines> merged,
      String currency,
      BigDecimal selfSales,
      BigDecimal facilitator,
      BigDecimal settlement,
      String shopCode) {
    String code = text(currency).toUpperCase(Locale.ROOT);
    if (code.isEmpty()) {
      return;
    }
    CurrencyLines current = merged.get(code);
    Set<String> shops =
        current == null
            ? new LinkedHashSet<>()
            : new LinkedHashSet<>(current.shopCodes());
    if (shopCode != null && !shopCode.isBlank()) {
      shops.add(shopCode);
    }
    merged.put(
        code,
        new CurrencyLines(
            money(current == null ? selfSales : current.selfSales().add(selfSales)),
            money(current == null ? facilitator : current.facilitatorTax().add(facilitator)),
            money(current == null ? settlement : current.settlementTax().add(settlement)),
            shops));
  }

  /**
   * 每个州、每个币种的**平台代扣代缴**（来自已上传的报表）。
   *
   * @param periodStart 对账期间起
   * @param periodEnd 对账期间止
   *     报表按「期间与对账期间有交集」计入 —— 平台报表给的是「这一个申报期」，
   *     跨期的对账要按交集算，全含或全不含都会算错。
   */
  private Map<String, Map<String, CurrencyLines>> facilitatorLinesByState(
      UUID tenant, LocalDate periodStart, LocalDate periodEnd) {
    Map<String, Map<String, CurrencyLines>> result = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select state_code, currency, sum(tax_collected) as tax, sum(taxable_amount) as taxable,"
                + " count(*) as n"
                + " from sales_tax_facilitator_line"
                + " join sales_tax_facilitator_report r"
                + "   on r.tenant_id = sales_tax_facilitator_line.tenant_id"
                + "  and r.id = sales_tax_facilitator_line.report_id"
                + " where sales_tax_facilitator_line.tenant_id = ?"
                + "   and r.parse_status = 'PARSED'"
                + "   and r.period_end >= ? and r.period_start <= ?"
                + " group by state_code, currency",
            tenant,
            periodStart,
            periodEnd)) {
      String state = row.get("state_code", String.class);
      String currency = text(row.get("currency", String.class));
      result
          .computeIfAbsent(state, key -> new LinkedHashMap<>())
          .put(
              currency,
              new CurrencyLines(
                  money(row.get("taxable", BigDecimal.class)),
                  money(row.get("tax", BigDecimal.class)),
                  BigDecimal.ZERO.setScale(MONEY_SCALE, java.math.RoundingMode.HALF_UP),
                  Set.of()));
    }
    return result;
  }

  // ==================================================================
  // 申报义务（下游 12.6 接手用）
  // ==================================================================

  /** 申报义务清单（真分页 + 真筛选）。 */
  public SalesTaxObligationPage obligations(
      String stateCode, String periodStart, String periodEnd, String currency, int page, int size) {
    UUID tenant = currentTenant();
    int p = clampPage(page);
    int s = clampSize(size);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("r.tenant_id = ?");
    binds.add(tenant);
    String state = text(stateCode).toUpperCase(Locale.ROOT);
    if (!state.isEmpty()) {
      where.add("r.state_code = ?");
      binds.add(state);
    }
    LocalDate from = parseDate(periodStart, "期间起");
    if (from != null) {
      where.add("r.period_end >= ?");
      binds.add(from);
    }
    LocalDate to = parseDate(periodEnd, "期间止");
    if (to != null) {
      where.add("r.period_start <= ?");
      binds.add(to);
    }
    String currencyFilter = parseCurrency(currency, "币种");
    if (currencyFilter != null) {
      where.add("r.currency = ?");
      binds.add(currencyFilter);
    }
    String clause = " where " + String.join(" and ", where);
    long total =
        ((Long)
                sql.fetchValue(
                    "select count(*) from sales_tax_reconciliation r" + clause, binds.toArray()))
            .longValue();
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);

    Map<String, ShopStateAgg> shopAggs = shopStateAggregates(tenant);
    Map<String, List<SalesTaxRateRuleView>> rules = new LinkedHashMap<>();
    LocalDate today = dbToday();

    List<SalesTaxObligationRow> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select r.* from sales_tax_reconciliation r" + clause
                + " order by r.period_end desc, r.state_code, r.currency limit ? offset ?",
            pageBinds.toArray())) {
      String rowState = row.get("state_code", String.class);
      ShopStateAgg agg = shopAggs.getOrDefault(rowState, new ShopStateAgg(0, 0, List.of()));
      String registrationStatus =
          agg.shopCount() == 0
              ? SalesTaxRules.REGISTRATION_NOT_REGISTERED
              : agg.registeredShopCount() == agg.shopCount()
                  ? SalesTaxRules.REGISTRATION_REGISTERED
                  : SalesTaxRules.REGISTRATION_PARTIAL;
      rules.computeIfAbsent(rowState, key -> loadRules(tenant, key));
      SalesTaxRateRuleView rule = SalesTaxRules.effectiveRule(rules.get(rowState), today);
      String reconciliationStatus = row.get("status", String.class);
      String obligation =
          filingObligationOf(registrationStatus, readReconciliation(row));
      String note =
          switch (reconciliationStatus) {
            case SalesTaxRules.RECON_VARIANCE ->
                "应缴与平台已代缴不一致 —— 差异 "
                    + row.get("variance", BigDecimal.class)
                    + "（正数 = 平台少收了，卖家要补缴）。";
            case SalesTaxRules.RECON_BALANCED -> "应缴与平台已代缴一致。";
            case SalesTaxRules.RECON_BELOW_THRESHOLD -> "未达州级阈值，期间无申报义务。";
            case SalesTaxRules.RECON_RULE_MISSING -> "没有生效的税率规则 —— 判不了，别按 0 申报。";
            case SalesTaxRules.RECON_NEXUS_MISSING -> "该州没登记店铺销售税联系 —— 判不了。";
            default -> "期间无销售也无代缴。";
          };
      items.add(
          new SalesTaxObligationRow(
              row.get("id", UUID.class).toString(),
              rowState,
              stateNames().getOrDefault(rowState, rowState),
              row.get("period_start", LocalDate.class),
              row.get("period_end", LocalDate.class),
              row.get("currency", String.class),
              row.get("expected_tax", BigDecimal.class),
              row.get("facilitator_tax", BigDecimal.class),
              row.get("variance", BigDecimal.class),
              rule == null ? "" : rule.filingCycle(),
              registrationStatus,
              reconciliationStatus,
              obligation,
              note));
    }
    return new SalesTaxObligationPage(
        items,
        total,
        p,
        s,
        "🔴 **平台代扣代缴不等于免税** —— 这张表里每一行都同时给出「应缴」与「已由平台代缴」，"
            + "申报义务不因为平台代收而消失。12.6 申报未交付 ⇒ 本模块只给结论，不做申报。");
  }

  /** 申报义务结论。 */
  private static String filingObligationOf(
      String registrationStatus, SalesTaxReconciliationView reconciliation) {
    if (reconciliation == null) {
      return SalesTaxRules.OBLIGATION_NO_ACTIVITY;
    }
    String status = reconciliation.status();
    if (SalesTaxRules.RECON_RULE_MISSING.equals(status)) {
      return SalesTaxRules.OBLIGATION_RULE_MISSING;
    }
    if (SalesTaxRules.RECON_NEXUS_MISSING.equals(status)) {
      return SalesTaxRules.OBLIGATION_NOT_REGISTERED;
    }
    if (SalesTaxRules.RECON_BELOW_THRESHOLD.equals(status)) {
      return SalesTaxRules.OBLIGATION_BELOW_THRESHOLD;
    }
    if (SalesTaxRules.RECON_NO_ACTIVITY.equals(status)) {
      return SalesTaxRules.OBLIGATION_NO_ACTIVITY;
    }
    if (SalesTaxRules.REGISTRATION_NOT_REGISTERED.equals(registrationStatus)) {
      return SalesTaxRules.OBLIGATION_NOT_REGISTERED;
    }
    return SalesTaxRules.OBLIGATION_FILING_REQUIRED;
  }
}
