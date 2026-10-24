package com.starcon.channel;

import static com.starcon.channel.VatIossModels.*;
import static com.starcon.channel.VatIossRules.*;

import com.starcon.channel.FinanceTaxPort.TaxResolution;
import com.starcon.channel.TradeOrderPort.PlatformOrderRow;
import com.starcon.channel.TradeOrderPort.SalesOrderAddressRow;
import com.starcon.channel.TradeOrderPort.SalesOrderDetail;
import com.starcon.channel.TradeOrderPort.SalesOrderLineRow;
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
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 16.3.2.1 VAT/IOSS。
 *
 * <p>蓝图：「管理 VAT 与 IOSS 制度：适用店铺与商品、申报周期、税率适用、IOSS 订单识别与代收代缴统计、
 * 与财务申报数据的对接。输入：IOSS 配置；订单税务数据；12.6.1.2 申报底稿。
 * 输出：IOSS 订单清单与金额统计；申报状态。
 * 关系：上游 = 12.6.1.1 税率、9 订单；下游 = 12.6.1.2。
 * <b>IOSS 订单与一般 VAT 订单必须区分统计</b>，混在一起申报是最常见的错误。」
 *
 * <p>🔴🔴 本类里有七处<b>结构</b>上的不变量：
 *
 * <ol>
 *   <li><b>分档是数据，不是颜色</b>：{@code vat_ioss_order.filing_scope} 是一列受控枚举，
 *       每一行订单都有一个取值；统计一律 {@code group by filing_scope, currency}。
 *       页面上的颜色只是显示，<b>取数本身</b>就是分开的。</li>
 *   <li><b>「判不了」是独立的一档</b>：{@code UNRESOLVED}（没有收货国家 / 汇率读不到折不出门槛）
 *       <b>不</b>被塞进 IOSS，也<b>不</b>被塞进「不在范围」—— 塞进去就是假结论。</li>
 *   <li><b>税率读不到 ⇒ 税率为 null</b>：绝不退化成 0% 或「无税」；
 *       {@code tax_rate_source='UNAVAILABLE'} 把这件事写进数据本身。</li>
 *   <li><b>金额按币种分组</b>：EUR + USD 不是钱 ⇒ 任何汇总都不跨币种相加；
 *       汇率只用来做「门槛比较」这一个判断，<b>不进任何金额合计</b>。</li>
 *   <li><b>申报状态只能前进</b>：DRAFT → DRAFTED → CONFIRMED（DB 触发器钉死）；
 *       本系统<b>不</b>代用户向税务机构提交申报 ⇒ 刻意没有「已提交」状态。</li>
 *   <li><b>上游只读</b>：税率经 finance 的授权 API 读、订单经 trade 的授权 API 读、
 *       汇率经 catalog 的授权 API 读、辖区码表经<b>同库只读</b>读 ——
 *       <b>零跨库 SQL / 零跨库 FK / 零跨库视图</b>。</li>
 *   <li><b>下游缺口如实报</b>：12.6.1.2 申报底稿<b>尚未交付</b> ⇒ 能力声明与统计结果里
 *       {@code filingDraftAvailable=false} + 归谁 + 影响说明，<b>不编造</b>它的底稿。</li>
 * </ol>
 */
@Service
public class VatIossService {

  /** 判定口径（能力声明与页面共用同一句话）。 */
  private static final String CLASSIFICATION_SEMANTICS =
      "🔴 **IOSS 订单与一般 VAT 订单必须区分统计** —— 混在一起申报是最常见的错误。"
          + "本模块把这件事落成**数据**：每一张订单行都有 `filing_scope`（"
          + "IOSS / GENERAL_VAT / OUT_OF_SCOPE / UNRESOLVED），"
          + "统计一律 `group by filing_scope, currency` ⇒ **取数层面**就是分开的，"
          + "不靠页面颜色、不靠标签、不靠人眼分辨。"
          + "🔴 **第四档 UNRESOLVED 是「判不了」**：没有收货国家、或者汇率读不到折不出免征门槛 —— "
          + "这两类**不**塞进 IOSS，也**不**塞进「不在范围」，塞进去就是假结论。"
          + "判不了的单子**必须**出现在清单与汇总里，否则「少算了」看起来像「没有」。";

  /** 适用店铺与商品的口径。 */
  private static final String SCOPE_SEMANTICS =
      "🔴 **适用店铺与商品是登记的「范围」，不是登记的「归属」** ⇒ 登记行不挂在任何一家店下面。"
          + "店铺用 platform_code + shop_code 记（与 trade 的 platform_order.shop_code 同一口径，"
          + "**零跨库 FK**）；商品用 SKU 逐个登记。"
          + "🔴 **应税消费品（酒 / 烟 / 能源）不适用 IOSS** —— 哪怕货值没超门槛也走一般 VAT。"
          + "系统不能从商品标题里猜「这是不是酒」⇒ 由租户在登记里逐个 SKU 标注；"
          + "**没登记的 SKU 按非应税消费品处理**（标注是显式动作）。";

  /** 申报状态的口径。 */
  private static final String FILING_SEMANTICS =
      "🔴 申报状态三档，**只能前进**（DB 触发器钉死，不许回退、不许跳过）："
          + "**DRAFT 待提交**（还没出底稿）→ **DRAFTED 已出底稿**（底稿已按 IOSS / 非 IOSS 分开生成，"
          + "等待人工核对）→ **CONFIRMED 已确认**（人工已确认本期数据完整可核）。"
          + "🔴 刻意**没有**「已向税务机构提交」这一档 —— 本系统只保证数据完整可核，"
          + "**不代用户向税务机构提交申报**；那个动作由用户在税务机构门户完成。";

  /** 金额口径。 */
  private static final String CURRENCY_SEMANTICS =
      "🔴 金额一律 BigDecimal + NUMERIC（**禁浮点**），**币种必带**。"
          + "汇总**按币种分组** —— **不同币种绝不直接相加**（EUR + USD 不是钱）。"
          + "汇率（catalog 5.2.2.2）**只**用于「免征门槛比较」这一个判断，**不进任何金额合计**；"
          + "汇率读不到时该单判成「判不了」，**不许**拿猜的汇率把单子归档进 IOSS。";

  /** 上游说明。 */
  private static final String UPSTREAM_NOTE =
      "上游 = 12.6.1.1 税率（**finance 服务，跨服务只读端口** `FinanceTaxPort` → "
          + "`GET /finance/tax-rates/resolve`，**按生效时间取税率**，命中不到一律报错）、"
          + "9 订单（**trade 服务，跨服务只读端口** `TradeOrderPort` → "
          + "`GET /trade/platform-orders/orders` + `GET /trade/sales-orders/{id}`）、"
          + "5.2.2.2 汇率（**catalog 服务，跨服务只读端口** `CatalogExchangeRatePort` → "
          + "`GET /catalog/exchange-rates/latest`）、"
          + "16.3.1.1 辖区码表（**channel 同库只读** `tax_registration_jurisdiction`）。"
          + "🔴 **租户不由端口传递**：转发的是调用方自己的 Authorization bearer，"
          + "上游侧仍按已认证成员关系解析租户并各自强制 RLS。"
          + "🔴 **零跨库 SQL / 零跨库 FK / 零跨库视图**。";

  /** 下游说明。 */
  private static final String DOWNSTREAM_NOTE =
      "下游 = 12.6.1.2 VAT/IOSS 申报数据（🔴 **同批另一条车道开发，集成头里尚未交付**）。"
          + "方向上**本模块在上游**：本模块输出的「IOSS 订单清单与金额统计」是 12.6.1.2 的**输入**。"
          + "🔴 因此本模块**读不到**它的申报底稿 —— 能力声明与统计结果里 "
          + "`filingDraftAvailable=false` + 归谁 + 影响说明，**如实显示「读不到申报底稿」**，"
          + "**绝不编造**一份底稿。"
          + "16.3.1.2 申报日历未交付 ⇒ 本模块**只算**申报期与状态，**不做**日历与截止提醒。"
          + "16.3.2.3 销售税**已交付** ⇒ **美国州级销售税归它**，本模块不碰、不重复建表。";

  /** 总口径说明。 */
  private static final String OVERALL_NOTE =
      "🔴 本模块的**输出**只有两样：**IOSS 订单清单与金额统计**、**申报状态**。"
          + "它**不**替用户向税务机构提交申报，**不**做财务记账，**不**做申报日历。"
          + "🔴 订单口径 = trade 的**平台单投影**（platform_order）；trade 的**手工单**"
          + "（没有平台投影）没有店铺归属，**判不了**「适用店铺」，因此**不在本模块口径内**。";

  /** 同步时最多翻多少页上游（每页 200 单）。 */
  static final int SYNC_MAX_PAGES = 5;

  private final DSLContext sql;
  private final FinanceTaxPort financeTax;
  private final TradeOrderPort tradeOrders;
  private final CatalogExchangeRatePort exchangeRates;

  public VatIossService(
      DSLContext sql,
      FinanceTaxPort financeTax,
      TradeOrderPort tradeOrders,
      CatalogExchangeRatePort exchangeRates) {
    this.sql = sql;
    this.financeTax = financeTax;
    this.tradeOrders = tradeOrders;
    this.exchangeRates = exchangeRates;
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

  /** 🔴 <b>同源 DB 时钟</b>：判定时刻 / 创建时刻一律从这里取。 */
  private OffsetDateTime dbNow() {
    Object raw = sql.fetchValue("select now()");
    return raw instanceof OffsetDateTime value ? value : OffsetDateTime.now(ZoneOffset.UTC);
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

  /** 🔴 解析带偏移量的 ISO 时间串（时间列是 {@code timestamptz}）。 */
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

  /** 🔴 解析日期（申报期边界）。 */
  private static LocalDate parseDate(String raw, String field) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return LocalDate.parse(value);
    } catch (RuntimeException e) {
      throw unprocessable(field + " 不是合法日期（应为 yyyy-MM-dd）");
    }
  }

  private static BigDecimal money(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  // ==================================================================
  // 内部形状
  // ==================================================================

  /** 一个辖区事实（16.3.1.1 的**全局码表**，不带 tenant_id）。 */
  private record JurisdictionFact(
      String code, String name, String countryCode, String region, String taxRegime, boolean vatRequired) {}

  /** 一条登记（含它的范围）。 */
  private record RegistrationRow(
      UUID id,
      String registrationNo,
      String status,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveTo,
      String intermediaryName,
      BigDecimal thresholdAmount,
      String thresholdCurrency,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      List<VatIossShopScope> shopScopes,
      List<VatIossGoodsScope> goodsScopes) {}

  // ==================================================================
  // 参考数据
  // ==================================================================

  /** 辖区码表（**只读**，不写上游）。 */
  private List<JurisdictionFact> jurisdictions() {
    List<JurisdictionFact> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select code, name, country_code, region, tax_regime, vat_required
            from tax_registration_jurisdiction
            order by position, code
            """)) {
      items.add(
          new JurisdictionFact(
              row.get("code", String.class),
              row.get("name", String.class),
              text(row.get("country_code", String.class)),
              row.get("region", String.class),
              row.get("tax_regime", String.class),
              Boolean.TRUE.equals(row.get("vat_required", Boolean.class))));
    }
    return items;
  }

  /** 收货国家 → 辖区事实（欧盟成员国才有辖区码；非欧盟返回 null）。 */
  private JurisdictionFact jurisdictionForCountry(String countryCode) {
    String cc = text(countryCode).toUpperCase(Locale.ROOT);
    if (cc.isEmpty()) {
      return null;
    }
    for (JurisdictionFact fact : jurisdictions()) {
      if (fact.countryCode().equalsIgnoreCase(cc) && "EUROPEAN_UNION".equals(fact.region())) {
        return fact;
      }
    }
    return null;
  }

  /** 辖区码 → 辖区事实。 */
  private JurisdictionFact jurisdictionByCode(String code) {
    String value = text(code);
    if (value.isEmpty()) {
      return null;
    }
    for (JurisdictionFact fact : jurisdictions()) {
      if (fact.code().equalsIgnoreCase(value)) {
        return fact;
      }
    }
    return null;
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /** 能力声明：未交付的下游逐条列出，上游逐条报「读得到 / 读不到」。 */
  public VatIossCapabilities capabilities() {
    List<VatIossCapabilityGap> gaps =
        List.of(
            new VatIossCapabilityGap(
                "12.6.1.2",
                "VAT/IOSS 申报数据",
                "12.6.1.2",
                false,
                "🔴 **申报底稿在本仓尚未交付**（同批另一条车道）。"
                    + "方向上**本模块在上游** —— 本模块输出的「IOSS 订单清单与金额统计」是它的**输入**。"
                    + "所以本模块**读不到**申报底稿：统计结果里 `filingDraftAvailable=false`，"
                    + "界面显示「**读不到申报底稿**」。"
                    + "🔴 本模块**绝不编造**一份底稿出来 —— 编出来的底稿比没有底稿危险得多。"),
            new VatIossCapabilityGap(
                "16.3.1.2",
                "申报日历",
                "16.3.1.2",
                false,
                "申报日历 **未交付** ⇒ 本模块**只算**申报期与申报状态，"
                    + "**不做**日历视图、**不做**截止日提醒、**不做**提交状态跟踪。"),
            new VatIossCapabilityGap(
                "13",
                "利润与经营分析",
                "13",
                false,
                "报表与分析服务在本仓**尚未落地** ⇒ 本模块只产出**按币种 + 按分档**的原始统计，"
                    + "不做透视、不做折算、不做跨期对比。"));

    List<VatIossUpstream> upstreams =
        List.of(
            new VatIossUpstream(
                "12.6.1.1",
                "税率配置（finance 服务）",
                true,
                "12.6.1.1",
                "税率**按生效时间**取（`GET /finance/tax-rates/resolve`），命中不到上游会报错。"
                    + "🔴 读不到时本模块把 `tax_rate` 留 **null** 并标 `tax_rate_source='UNAVAILABLE'` —— "
                    + "**绝不退化成 0%**（0% 看起来像免税，而实际是「没读到」）。"),
            new VatIossUpstream(
                "9",
                "订单（trade 服务）",
                true,
                "9",
                "订单事实归 trade，本模块**只读**：平台单投影（`/trade/platform-orders/orders`）"
                    + "提供店铺与金额，统一销售订单详情（`/trade/sales-orders/{id}`）提供收货国家、"
                    + "买家类型与行明细。"
                    + "🔴 订单口径 = **平台单投影**；trade 的**手工单**没有店铺归属，"
                    + "判不了「适用店铺」，因此**不在本模块口径内**。"),
            new VatIossUpstream(
                "5.2.2.2",
                "汇率快照（catalog 服务）",
                true,
                "5.2.2.2",
                "**只**用于「免征门槛比较」（门槛是欧元口径，订单可能是别的币种）。"
                    + "🔴 汇率读不到 ⇒ 该单判成「**判不了**」，**不许**拿猜的汇率把单子归档进 IOSS。"),
            new VatIossUpstream(
                "16.3.1.1",
                "税号与辖区（channel 同库）",
                true,
                "16.3.1.1",
                "辖区码表 `tax_registration_jurisdiction` 是**全局码表**，本模块**只读** —— "
                    + "**不写** `tax_registration_record`、**不另建**辖区表、**不写死**欧盟 27 国清单。"));

    List<String> currencies = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select distinct currency from vat_ioss_order where tenant_id = ? order by currency",
            currentTenant())) {
      currencies.add(row.get("currency", String.class));
    }

    return new VatIossCapabilities(
        gaps,
        upstreams,
        FILING_SCOPES,
        REASON_CODES,
        FILING_STATUSES,
        FILING_CYCLES,
        TAX_TYPES,
        CLASSIFICATION_SEMANTICS,
        SCOPE_SEMANTICS,
        FILING_SEMANTICS,
        CURRENCY_SEMANTICS,
        UPSTREAM_NOTE,
        DOWNSTREAM_NOTE,
        OVERALL_NOTE);
  }

  // ==================================================================
  // 筛选项
  // ==================================================================

  /** 筛选项（前端下拉直接用）。 */
  public VatIossOptions options() {
    List<VatIossJurisdictionOption> jurisdictionOptions = new ArrayList<>();
    for (JurisdictionFact fact : jurisdictions()) {
      if (!"EUROPEAN_UNION".equals(fact.region())) {
        continue;
      }
      jurisdictionOptions.add(
          new VatIossJurisdictionOption(
              fact.code(),
              fact.name(),
              fact.countryCode(),
              fact.region(),
              fact.taxRegime(),
              fact.vatRequired()));
    }
    Set<String> currencies = new LinkedHashSet<>();
    for (Record row :
        sql.fetch(
            "select distinct currency from vat_ioss_order where tenant_id = ? order by currency",
            currentTenant())) {
      currencies.add(row.get("currency", String.class));
    }
    currencies.add("EUR");
    return new VatIossOptions(
        FILING_SCOPES,
        REASON_CODES,
        FILING_STATUSES,
        FILING_CYCLES,
        TAX_TYPES,
        jurisdictionOptions,
        new ArrayList<>(currencies));
  }

  // ==================================================================
  // 店铺目录
  // ==================================================================

  /** 店铺目录（读本服务自己的 {@code shop} 表）+ 「哪些店落在 IOSS 覆盖范围内」。 */
  public VatIossShopList shops(String keyword, String platformCode) {
    UUID tenant = currentTenant();

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
      if (!country.isEmpty()) {
        List<String> list = countries.computeIfAbsent(shopId, key -> new ArrayList<>());
        if (!list.contains(country)) {
          list.add(country);
        }
      }
    }

    // 当前生效的 IOSS 登记覆盖了哪些店
    RegistrationRow active = activeRegistration();
    Set<String> coveredShops = new LinkedHashSet<>();
    String activeRegistrationId = "";
    String activeRegistrationNo = "";
    if (active != null) {
      activeRegistrationId = active.id().toString();
      activeRegistrationNo = active.registrationNo();
      for (VatIossShopScope scope : active.shopScopes()) {
        coveredShops.add(scope.platformCode() + "|" + scope.shopCode());
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

    List<VatIossShopView> items = new ArrayList<>();
    for (Record row : sql.fetch(statement.toString(), params.toArray())) {
      UUID id = row.get("id", UUID.class);
      String platform = text(row.get("platform_code", String.class));
      String shop = text(row.get("code", String.class));
      items.add(
          new VatIossShopView(
              id.toString(),
              shop,
              row.get("name", String.class),
              platform,
              row.get("platform_name", String.class),
              List.copyOf(marketplaces.getOrDefault(id, List.of())),
              List.copyOf(countries.getOrDefault(id, List.of())),
              coveredShops.contains(platform + "|" + shop) ? activeRegistrationId : "",
              coveredShops.contains(platform + "|" + shop) ? activeRegistrationNo : "",
              coveredShops.contains(platform + "|" + shop)));
    }
    return new VatIossShopList(
        items,
        items.size(),
        "🔴 店铺清单**直接读本服务自己的店铺目录**，不写死店铺常量、不造平行店铺表。"
            + "「这家店在不在 IOSS 覆盖范围内」= 当前生效的 IOSS 登记的**适用店铺**里有没有它。");
  }

  // ==================================================================
  // IOSS 登记
  // ==================================================================

  /** 当前生效的登记（没有则 null）。 */
  private RegistrationRow activeRegistration() {
    List<RegistrationRow> rows =
        loadRegistrations(
            "select id, registration_no, status, effective_from, effective_to, intermediary_name,"
                + " threshold_amount, threshold_currency, note, created_at, updated_at"
                + " from vat_ioss_registration where tenant_id = ? and effective_to is null"
                + " order by effective_from desc",
            currentTenant());
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** 载入登记（带范围）。 */
  private List<RegistrationRow> loadRegistrations(String where, Object... params) {
    Map<UUID, List<VatIossShopScope>> shops = new LinkedHashMap<>();
    Map<UUID, List<VatIossGoodsScope>> goods = new LinkedHashMap<>();
    UUID tenant = currentTenant();
    for (Record row :
        sql.fetch(
            "select registration_id, platform_code, shop_code from vat_ioss_registration_shop"
                + " where tenant_id = ? order by platform_code, shop_code",
            tenant)) {
      shops
          .computeIfAbsent(row.get("registration_id", UUID.class), key -> new ArrayList<>())
          .add(
              new VatIossShopScope(
                  row.get("platform_code", String.class), row.get("shop_code", String.class)));
    }
    for (Record row :
        sql.fetch(
            "select registration_id, sku_code, goods_name, excise_goods"
                + " from vat_ioss_registration_goods where tenant_id = ? order by sku_code",
            tenant)) {
      goods
          .computeIfAbsent(row.get("registration_id", UUID.class), key -> new ArrayList<>())
          .add(
              new VatIossGoodsScope(
                  row.get("sku_code", String.class),
                  row.get("goods_name", String.class),
                  Boolean.TRUE.equals(row.get("excise_goods", Boolean.class))));
    }

    List<RegistrationRow> items = new ArrayList<>();
    for (Record row : sql.fetch(where, params)) {
      UUID id = row.get("id", UUID.class);
      items.add(
          new RegistrationRow(
              id,
              row.get("registration_no", String.class),
              row.get("status", String.class),
              row.get("effective_from", OffsetDateTime.class),
              row.get("effective_to", OffsetDateTime.class),
              row.get("intermediary_name", String.class),
              row.get("threshold_amount", BigDecimal.class),
              row.get("threshold_currency", String.class),
              row.get("note", String.class),
              row.get("created_at", OffsetDateTime.class),
              row.get("updated_at", OffsetDateTime.class),
              List.copyOf(shops.getOrDefault(id, List.of())),
              List.copyOf(goods.getOrDefault(id, List.of()))));
    }
    return items;
  }

  private static VatIossRegistrationView registrationView(RegistrationRow row) {
    List<String> excise = new ArrayList<>();
    for (VatIossGoodsScope scope : row.goodsScopes()) {
      if (scope.exciseGoods()) {
        excise.add(scope.skuCode());
      }
    }
    return new VatIossRegistrationView(
        row.id().toString(),
        row.registrationNo(),
        row.status(),
        row.effectiveFrom() == null ? "" : row.effectiveFrom().toString(),
        row.effectiveTo() == null ? "" : row.effectiveTo().toString(),
        row.effectiveTo() == null,
        row.intermediaryName(),
        money(row.thresholdAmount()).toPlainString(),
        row.thresholdCurrency(),
        row.shopScopes(),
        row.goodsScopes(),
        excise,
        row.note(),
        row.createdAt() == null ? "" : row.createdAt().toString(),
        row.updatedAt() == null ? "" : row.updatedAt().toString());
  }

  /** IOSS 登记清单（真分页）。 */
  public VatIossRegistrationPage registrations(int page, int size) {
    UUID tenant = currentTenant();
    int limit = clampSize(size);
    int offset = Math.max(page, 0) * limit;
    long total =
        ((Number) sql.fetchValue("select count(*) from vat_ioss_registration where tenant_id = ?", tenant))
            .longValue();
    List<RegistrationRow> rows =
        loadRegistrations(
            "select id, registration_no, status, effective_from, effective_to, intermediary_name,"
                + " threshold_amount, threshold_currency, note, created_at, updated_at"
                + " from vat_ioss_registration where tenant_id = ?"
                + " order by effective_from desc limit ? offset ?",
            tenant,
            limit,
            offset);
    List<VatIossRegistrationView> items = new ArrayList<>();
    for (RegistrationRow row : rows) {
      items.add(registrationView(row));
    }
    return new VatIossRegistrationPage(items, (int) total, Math.max(page, 0), limit);
  }

  /** 单条登记详情（跨租户与不存在返回逐字相同的 404）。 */
  public VatIossRegistrationView registration(String id) {
    UUID registrationId = parseFilterUuid(id);
    List<RegistrationRow> rows =
        loadRegistrations(
            "select id, registration_no, status, effective_from, effective_to, intermediary_name,"
                + " threshold_amount, threshold_currency, note, created_at, updated_at"
                + " from vat_ioss_registration where tenant_id = ? and id = ?",
            currentTenant(),
            registrationId);
    if (rows.isEmpty()) {
      throw notFound();
    }
    return registrationView(rows.get(0));
  }

  /** 登记一条 IOSS 登记（一个租户一个 IOSS 号）。 */
  public VatIossRegistrationView create(VatIossRegistrationInput input) {
    UUID tenant = currentTenant();
    String registrationNo = text(input.registrationNo());
    if (registrationNo.isEmpty()) {
      throw unprocessable("IOSS 登记号不能为空");
    }
    if (activeRegistration() != null) {
      throw conflict("本租户已有一条生效中的 IOSS 登记 —— 先关闭它（填写生效止），再登记新的。");
    }
    OffsetDateTime effectiveFrom = parseTime(input.effectiveFrom(), "生效时刻");
    if (effectiveFrom == null) {
      effectiveFrom = dbNow();
    }
    OffsetDateTime effectiveTo = parseTime(input.effectiveTo(), "生效止");
    if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
      throw unprocessable("生效止必须晚于生效时刻");
    }
    BigDecimal threshold = thresholdOf(input.thresholdAmount());
    String thresholdCurrency = currencyOf(input.thresholdCurrency());
    String status = statusOf(input.status(), "ACTIVE", List.of("ACTIVE", "DISABLED"));

    UUID id = Ids.next();
    sql.execute(
        "insert into vat_ioss_registration(id,tenant_id,registration_no,status,effective_from,"
            + " effective_to,intermediary_name,threshold_amount,threshold_currency,note,recorded_by,"
            + " created_at,updated_at) values(?,?,?,?,?::timestamptz,?::timestamptz,?,?,?,?,?,now(),now())",
        id,
        tenant,
        registrationNo,
        status,
        effectiveFrom,
        effectiveTo,
        text(input.intermediaryName()),
        threshold,
        thresholdCurrency,
        text(input.note()),
        currentActor());

    replaceScopes(tenant, id, input.shopScopes(), input.goodsScopes());
    return registration(id.toString());
  }

  /**
   * 改一条登记。
   *
   * <p>🔴 **登记号与生效时刻不可改**（DB 触发器钉死）：改了等于「把当初申报时用的号偷偷换掉」。
   * 想换号 = 关闭当前登记 + 登记新的。适用范围（店铺 / 商品）是**整组替换**。
   */
  public VatIossRegistrationView update(String id, VatIossRegistrationInput input) {
    UUID tenant = currentTenant();
    UUID registrationId = parseFilterUuid(id);
    RegistrationRow existing = findRegistration(registrationId);
    if (existing == null) {
      throw notFound();
    }
    if (existing.effectiveTo() != null) {
      throw unprocessable("这条登记已经关闭 —— 已关闭的登记内容冻结，不许改写。");
    }
    String registrationNo = text(input.registrationNo());
    if (!registrationNo.isEmpty() && !registrationNo.equals(existing.registrationNo())) {
      throw unprocessable(
          "IOSS 登记号不可改 —— 请关闭当前登记（填写生效止）后另登记一条；"
              + "就地改号等于把当初申报用的号偷偷换掉。");
    }
    OffsetDateTime effectiveFrom = parseTime(input.effectiveFrom(), "生效时刻");
    // 🔴 比的是**时刻**不是带偏移量的字符串：DB 读回来带的是 JVM 本地偏移量，
    //    同一个瞬间在 +08:00 与 Z 下是两个不相等的 OffsetDateTime ⇒ 用 toInstant() 比。
    if (effectiveFrom != null
        && !effectiveFrom.toInstant().equals(existing.effectiveFrom().toInstant())) {
      throw unprocessable("生效时刻不可改 —— 请关闭当前登记（填写生效止）后另登记一条。");
    }
    OffsetDateTime effectiveTo = parseTime(input.effectiveTo(), "生效止");
    if (effectiveTo != null && !effectiveTo.isAfter(existing.effectiveFrom())) {
      throw unprocessable("生效止必须晚于生效时刻");
    }
    BigDecimal threshold = thresholdOf(input.thresholdAmount());
    String thresholdCurrency = currencyOf(input.thresholdCurrency());
    String status = statusOf(input.status(), existing.status(), List.of("ACTIVE", "DISABLED"));

    sql.execute(
        "update vat_ioss_registration set status = ?, effective_to = ?::timestamptz,"
            + " intermediary_name = ?, threshold_amount = ?, threshold_currency = ?, note = ?,"
            + " updated_at = now() where tenant_id = ? and id = ?",
        status,
        effectiveTo,
        text(input.intermediaryName()),
        threshold,
        thresholdCurrency,
        text(input.note()),
        tenant,
        registrationId);

    replaceScopes(tenant, registrationId, input.shopScopes(), input.goodsScopes());
    return registration(id);
  }

  private RegistrationRow findRegistration(UUID registrationId) {
    List<RegistrationRow> rows =
        loadRegistrations(
            "select id, registration_no, status, effective_from, effective_to, intermediary_name,"
                + " threshold_amount, threshold_currency, note, created_at, updated_at"
                + " from vat_ioss_registration where tenant_id = ? and id = ?",
            currentTenant(),
            registrationId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** 整组替换适用范围（店铺 + 商品）。 */
  private void replaceScopes(
      UUID tenant,
      UUID registrationId,
      List<VatIossShopScope> shopScopes,
      List<VatIossGoodsScope> goodsScopes) {
    sql.execute(
        "delete from vat_ioss_registration_shop where tenant_id = ? and registration_id = ?",
        tenant,
        registrationId);
    sql.execute(
        "delete from vat_ioss_registration_goods where tenant_id = ? and registration_id = ?",
        tenant,
        registrationId);
    if (shopScopes != null) {
      for (VatIossShopScope scope : shopScopes) {
        String platformCode = text(scope.platformCode());
        String shopCode = text(scope.shopCode());
        if (platformCode.isEmpty() || shopCode.isEmpty()) {
          continue;
        }
        sql.execute(
            "insert into vat_ioss_registration_shop(tenant_id,registration_id,platform_code,shop_code)"
                + " values(?,?,?,?)",
            tenant,
            registrationId,
            platformCode,
            shopCode);
      }
    }
    if (goodsScopes != null) {
      for (VatIossGoodsScope scope : goodsScopes) {
        String skuCode = text(scope.skuCode());
        if (skuCode.isEmpty()) {
          continue;
        }
        sql.execute(
            "insert into vat_ioss_registration_goods(tenant_id,registration_id,sku_code,goods_name,"
                + " excise_goods) values(?,?,?,?,?)",
            tenant,
            registrationId,
            skuCode,
            text(scope.goodsName()),
            scope.exciseGoods());
      }
    }
  }

  private static BigDecimal thresholdOf(String raw) {
    if (text(raw).isEmpty()) {
      return new BigDecimal("150.000000");
    }
    BigDecimal value;
    try {
      value = parseDecimal(raw, "免征门槛", 6);
    } catch (IllegalArgumentException e) {
      throw unprocessable(e.getMessage());
    }
    if (value.signum() <= 0) {
      throw unprocessable("免征门槛必须大于 0");
    }
    return value;
  }

  private static String currencyOf(String raw) {
    String value = text(raw).toUpperCase(Locale.ROOT);
    if (value.isEmpty()) {
      return "EUR";
    }
    if (!value.matches("^[A-Z]{3}$")) {
      throw unprocessable("币种必须是三位大写字母");
    }
    return value;
  }

  private static String statusOf(String raw, String fallback, List<String> allowed) {
    String value = text(raw).toUpperCase(Locale.ROOT);
    if (value.isEmpty()) {
      return fallback;
    }
    if (!allowed.contains(value)) {
      throw unprocessable("状态只能是 " + String.join(" / ", allowed));
    }
    return value;
  }

  private static int clampSize(int size) {
    if (size <= 0) {
      return 20;
    }
    return Math.min(size, 200);
  }

  // ==================================================================
  // 申报周期配置
  // ==================================================================

  /** 申报周期配置清单（真分页 + 真筛选）。 */
  public VatIossFilingCyclePage filingCycles(String jurisdictionCode, int page, int size) {
    UUID tenant = currentTenant();
    int limit = clampSize(size);
    int offset = Math.max(page, 0) * limit;

    StringBuilder where = new StringBuilder(" where tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    if (!text(jurisdictionCode).isEmpty()) {
      where.append(" and jurisdiction_code = ?");
      params.add(text(jurisdictionCode).toUpperCase(Locale.ROOT));
    }
    long total =
        ((Number)
                sql.fetchValue(
                    "select count(*) from vat_ioss_filing_cycle" + where, params.toArray()))
            .longValue();
    params.add(limit);
    params.add(offset);

    List<VatIossFilingCycleView> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id, jurisdiction_code, filing_cycle, filing_start_month, deadline_day, status,"
                + " note, created_at, updated_at from vat_ioss_filing_cycle"
                + where
                + " order by jurisdiction_code, filing_cycle limit ? offset ?",
            params.toArray())) {
      JurisdictionFact fact = jurisdictionByCode(row.get("jurisdiction_code", String.class));
      items.add(
          new VatIossFilingCycleView(
              row.get("id", UUID.class).toString(),
              row.get("jurisdiction_code", String.class),
              fact == null ? "" : fact.name(),
              fact == null ? "" : fact.countryCode(),
              row.get("filing_cycle", String.class),
              filingCycleLabel(row.get("filing_cycle", String.class)),
              row.get("filing_start_month", Integer.class),
              row.get("deadline_day", Integer.class),
              row.get("status", String.class),
              row.get("note", String.class),
              row.get("created_at", OffsetDateTime.class) == null
                  ? ""
                  : row.get("created_at", OffsetDateTime.class).toString(),
              row.get("updated_at", OffsetDateTime.class) == null
                  ? ""
                  : row.get("updated_at", OffsetDateTime.class).toString()));
    }
    return new VatIossFilingCyclePage(items, (int) total, Math.max(page, 0), limit);
  }

  /** 登记一条申报周期配置。 */
  public VatIossFilingCycleView createFilingCycle(VatIossFilingCycleInput input) {
    UUID tenant = currentTenant();
    String jurisdictionCode = text(input.jurisdictionCode()).toUpperCase(Locale.ROOT);
    if (jurisdictionCode.isEmpty()) {
      throw unprocessable("辖区码不能为空");
    }
    if (jurisdictionByCode(jurisdictionCode) == null) {
      throw unprocessable("辖区码 " + jurisdictionCode + " 不在 16.3.1.1 的辖区码表里");
    }
    String cycle = text(input.filingCycle()).toUpperCase(Locale.ROOT);
    if (!FILING_CYCLES.contains(cycle)) {
      throw unprocessable("申报周期只能是 " + String.join(" / ", FILING_CYCLES));
    }
    Integer startMonth = input.filingStartMonth();
    if (startMonth == null || startMonth < 1 || startMonth > 12) {
      throw unprocessable("申报期起始月必须在 1..12 之间");
    }
    Integer deadlineDay = input.deadlineDay();
    if (deadlineDay == null || deadlineDay < 1 || deadlineDay > 28) {
      throw unprocessable("申报截止日必须在 1..28 之间");
    }
    String status = statusOf(input.status(), "ACTIVE", List.of("ACTIVE", "DISABLED"));

    Object existing =
        sql.fetchValue(
            "select id from vat_ioss_filing_cycle where tenant_id = ? and jurisdiction_code = ?"
                + " and filing_cycle = ?",
            tenant,
            jurisdictionCode,
            cycle);
    if (existing != null) {
      throw conflict("该辖区已经有一条 " + cycle + " 的周期配置 —— 改配置请直接改那一条。");
    }

    UUID id = Ids.next();
    sql.execute(
        "insert into vat_ioss_filing_cycle(id,tenant_id,jurisdiction_code,filing_cycle,"
            + " filing_start_month,deadline_day,status,note,recorded_by,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,now(),now())",
        id,
        tenant,
        jurisdictionCode,
        cycle,
        startMonth,
        deadlineDay,
        status,
        text(input.note()),
        currentActor());
    return findFilingCycle(id);
  }

  /** 改一条申报周期配置。 */
  public VatIossFilingCycleView updateFilingCycle(String id, VatIossFilingCycleInput input) {
    UUID tenant = currentTenant();
    UUID cycleId = parseFilterUuid(id);
    Object exists =
        sql.fetchValue(
            "select id from vat_ioss_filing_cycle where tenant_id = ? and id = ?", tenant, cycleId);
    if (exists == null) {
      throw notFound();
    }
    Integer startMonth = input.filingStartMonth();
    if (startMonth == null || startMonth < 1 || startMonth > 12) {
      throw unprocessable("申报期起始月必须在 1..12 之间");
    }
    Integer deadlineDay = input.deadlineDay();
    if (deadlineDay == null || deadlineDay < 1 || deadlineDay > 28) {
      throw unprocessable("申报截止日必须在 1..28 之间");
    }
    String status = statusOf(input.status(), "ACTIVE", List.of("ACTIVE", "DISABLED"));
    sql.execute(
        "update vat_ioss_filing_cycle set filing_start_month = ?, deadline_day = ?, status = ?,"
            + " note = ?, updated_at = now() where tenant_id = ? and id = ?",
        startMonth,
        deadlineDay,
        status,
        text(input.note()),
        tenant,
        cycleId);
    return findFilingCycle(cycleId);
  }

  private VatIossFilingCycleView findFilingCycle(UUID cycleId) {
    for (Record row :
        sql.fetch(
            "select id, jurisdiction_code, filing_cycle, filing_start_month, deadline_day, status,"
                + " note, created_at, updated_at from vat_ioss_filing_cycle"
                + " where tenant_id = ? and id = ?",
            currentTenant(),
            cycleId)) {
      JurisdictionFact fact = jurisdictionByCode(row.get("jurisdiction_code", String.class));
      return new VatIossFilingCycleView(
          row.get("id", UUID.class).toString(),
          row.get("jurisdiction_code", String.class),
          fact == null ? "" : fact.name(),
          fact == null ? "" : fact.countryCode(),
          row.get("filing_cycle", String.class),
          filingCycleLabel(row.get("filing_cycle", String.class)),
          row.get("filing_start_month", Integer.class),
          row.get("deadline_day", Integer.class),
          row.get("status", String.class),
          row.get("note", String.class),
          row.get("created_at", OffsetDateTime.class) == null
              ? ""
              : row.get("created_at", OffsetDateTime.class).toString(),
          row.get("updated_at", OffsetDateTime.class) == null
              ? ""
              : row.get("updated_at", OffsetDateTime.class).toString());
    }
    throw notFound();
  }

  // ==================================================================
  // 申报期与申报状态
  // ==================================================================

  /** 申报期清单（真分页 + 真筛选）。 */
  public VatIossFilingPeriodPage filingPeriods(
      String jurisdictionCode, String status, int page, int size) {
    UUID tenant = currentTenant();
    int limit = clampSize(size);
    int offset = Math.max(page, 0) * limit;

    StringBuilder where = new StringBuilder(" where tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    if (!text(jurisdictionCode).isEmpty()) {
      where.append(" and jurisdiction_code = ?");
      params.add(text(jurisdictionCode).toUpperCase(Locale.ROOT));
    }
    if (!text(status).isEmpty()) {
      where.append(" and status = ?");
      params.add(text(status).toUpperCase(Locale.ROOT));
    }
    long total =
        ((Number)
                sql.fetchValue(
                    "select count(*) from vat_filing_period" + where, params.toArray()))
            .longValue();

    List<VatIossFilingPeriodView> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id, jurisdiction_code, period_start, period_end, status, drafted_at,"
                + " confirmed_at, confirmed_by, note, created_at, updated_at from vat_filing_period"
                + where
                + " order by period_start desc, jurisdiction_code limit ? offset ?",
            append(params, limit, offset).toArray())) {
      items.add(filingPeriodView(row));
    }

    Map<String, Long> counts = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select status, count(*) as n from vat_filing_period where tenant_id = ? group by status",
            tenant)) {
      counts.put(row.get("status", String.class), ((Number) row.get("n")).longValue());
    }
    return new VatIossFilingPeriodPage(
        items,
        (int) total,
        Math.max(page, 0),
        limit,
        counts.getOrDefault("DRAFT", 0L),
        counts.getOrDefault("DRAFTED", 0L),
        counts.getOrDefault("CONFIRMED", 0L),
        FILING_SEMANTICS);
  }

  private static List<Object> append(List<Object> base, Object... extra) {
    List<Object> out = new ArrayList<>(base);
    out.addAll(List.of(extra));
    return out;
  }

  private VatIossFilingPeriodView filingPeriodView(Record row) {
    String jurisdictionCode = row.get("jurisdiction_code", String.class);
    JurisdictionFact fact = jurisdictionByCode(jurisdictionCode);
    OffsetDateTime draftedAt = row.get("drafted_at", OffsetDateTime.class);
    OffsetDateTime confirmedAt = row.get("confirmed_at", OffsetDateTime.class);
    UUID confirmedBy = row.get("confirmed_by", UUID.class);
    LocalDate periodStart = row.get("period_start", LocalDate.class);
    LocalDate periodEnd = row.get("period_end", LocalDate.class);
    String status = row.get("status", String.class);
    return new VatIossFilingPeriodView(
        row.get("id", UUID.class).toString(),
        jurisdictionCode,
        fact == null ? "" : fact.name(),
        fact == null ? "" : fact.countryCode(),
        periodStart == null ? "" : periodStart.toString(),
        periodEnd == null ? "" : periodEnd.toString(),
        status,
        filingStatusLabel(status),
        draftedAt == null ? "" : draftedAt.toString(),
        confirmedAt == null ? "" : confirmedAt.toString(),
        confirmedBy == null ? "" : confirmedBy.toString(),
        row.get("note", String.class),
        row.get("created_at", OffsetDateTime.class) == null
            ? ""
            : row.get("created_at", OffsetDateTime.class).toString(),
        row.get("updated_at", OffsetDateTime.class) == null
            ? ""
            : row.get("updated_at", OffsetDateTime.class).toString());
  }

  /** 开一期申报（区间必须落在该辖区已配置的申报周期里）。 */
  public VatIossFilingPeriodView createFilingPeriod(VatIossFilingPeriodInput input) {
    UUID tenant = currentTenant();
    String jurisdictionCode = text(input.jurisdictionCode()).toUpperCase(Locale.ROOT);
    if (jurisdictionCode.isEmpty()) {
      throw unprocessable("辖区码不能为空");
    }
    if (jurisdictionByCode(jurisdictionCode) == null) {
      throw unprocessable("辖区码 " + jurisdictionCode + " 不在 16.3.1.1 的辖区码表里");
    }
    LocalDate periodStart = parseDate(input.periodStart(), "申报期起");
    LocalDate periodEnd = parseDate(input.periodEnd(), "申报期止");
    if (periodStart == null || periodEnd == null) {
      throw unprocessable("申报期起止都必须填");
    }
    if (periodEnd.isBefore(periodStart)) {
      throw unprocessable("申报期止不能早于申报期起");
    }
    if (periodStart.getDayOfMonth() != 1) {
      throw unprocessable("申报期起必须是某月的 1 号");
    }
    validatePeriodAgainstCycle(tenant, jurisdictionCode, periodStart, periodEnd);

    Object exists =
        sql.fetchValue(
            "select id from vat_filing_period where tenant_id = ? and jurisdiction_code = ?"
                + " and period_start = ? and period_end = ?",
            tenant,
            jurisdictionCode,
            periodStart,
            periodEnd);
    if (exists != null) {
      throw conflict("该辖区这一段区间已经开过一期 —— 同一段数据开两期就会被报两次。");
    }

    UUID id = Ids.next();
    sql.execute(
        "insert into vat_filing_period(id,tenant_id,jurisdiction_code,period_start,period_end,"
            + " status,note,recorded_by,created_at,updated_at)"
            + " values(?,?,?,?,?,'DRAFT',?,?,now(),now())",
        id,
        tenant,
        jurisdictionCode,
        periodStart,
        periodEnd,
        text(input.note()),
        currentActor());
    return filingPeriod(id.toString());
  }

  /** 🔴 申报期必须落在该辖区**已配置**的申报周期里（区间长度 + 起始月对齐）。 */
  private void validatePeriodAgainstCycle(
      UUID tenant, String jurisdictionCode, LocalDate periodStart, LocalDate periodEnd) {
    List<Record> cycles =
        sql.fetch(
            "select filing_cycle, filing_start_month from vat_ioss_filing_cycle"
                + " where tenant_id = ? and jurisdiction_code = ? and status = 'ACTIVE'",
            tenant,
            jurisdictionCode);
    if (cycles.isEmpty()) {
      throw unprocessable(
          "该辖区还没有生效的申报周期配置 —— 先登记一条申报周期（申报周期配置 ≠ 申报期本身）。");
    }
    for (Record row : cycles) {
      String cycle = row.get("filing_cycle", String.class);
      Integer startMonth = row.get("filing_start_month", Integer.class);
      int cycleMonths = cycleMonths(cycle);
      int offset = Math.floorMod(periodStart.getMonthValue() - (startMonth == null ? 1 : startMonth), 12);
      boolean aligned = offset % cycleMonths == 0;
      long days = periodEnd.toEpochDay() - periodStart.toEpochDay() + 1;
      if (aligned && days <= cycleMonths * 31L + 1) {
        return;
      }
    }
    throw unprocessable(
        "申报期区间与该辖区已配置的申报周期对不上 —— 区间长度必须等于一个申报周期，"
            + "且起始月必须与配置的申报期起始月对齐。");
  }

  private static int cycleMonths(String cycle) {
    return switch (cycle == null ? "" : cycle) {
      case "MONTHLY" -> 1;
      case "QUARTERLY" -> 3;
      case "SEMI_ANNUAL" -> 6;
      case "ANNUAL" -> 12;
      default -> 1;
    };
  }

  /** 一期申报的详情。 */
  public VatIossFilingPeriodView filingPeriod(String id) {
    UUID periodId = parseFilterUuid(id);
    for (Record row :
        sql.fetch(
            "select id, jurisdiction_code, period_start, period_end, status, drafted_at,"
                + " confirmed_at, confirmed_by, note, created_at, updated_at from vat_filing_period"
                + " where tenant_id = ? and id = ?",
            currentTenant(),
            periodId)) {
      return filingPeriodView(row);
    }
    throw notFound();
  }

  /** 🔴 出底稿：DRAFT → DRAFTED。 */
  public VatIossFilingPeriodView draftFilingPeriod(String id) {
    UUID periodId = parseFilterUuid(id);
    String status = currentPeriodStatus(periodId);
    if (!"DRAFT".equals(status)) {
      throw unprocessable(
          "只有「待提交」的申报期能出底稿，当前状态是「"
              + filingStatusLabel(status)
              + "」—— 申报状态**只能前进**（DRAFT → DRAFTED → CONFIRMED）。");
    }
    sql.execute(
        "update vat_filing_period set status = 'DRAFTED', drafted_at = now(), updated_at = now()"
            + " where tenant_id = ? and id = ?",
        currentTenant(),
        periodId);
    return filingPeriod(id);
  }

  /** 🔴 确认：DRAFTED → CONFIRMED（**本系统不代用户向税务机构提交申报**）。 */
  public VatIossFilingPeriodView confirmFilingPeriod(String id) {
    UUID periodId = parseFilterUuid(id);
    String status = currentPeriodStatus(periodId);
    if (!"DRAFTED".equals(status)) {
      throw unprocessable(
          "只有「已出底稿」的申报期能确认，当前状态是「"
              + filingStatusLabel(status)
              + "」—— 申报状态**只能前进**（DRAFT → DRAFTED → CONFIRMED）。");
    }
    sql.execute(
        "update vat_filing_period set status = 'CONFIRMED', confirmed_at = now(), confirmed_by = ?,"
            + " updated_at = now() where tenant_id = ? and id = ?",
        currentActor(),
        currentTenant(),
        periodId);
    return filingPeriod(id);
  }

  private String currentPeriodStatus(UUID periodId) {
    Object raw =
        sql.fetchValue(
            "select status from vat_filing_period where tenant_id = ? and id = ?",
            currentTenant(),
            periodId);
    if (raw == null) {
      throw notFound();
    }
    return String.valueOf(raw);
  }

  // ==================================================================
  // 统计（🔴 按分档 × 币种分组）
  // ==================================================================

  /**
   * 一期申报的底稿统计（**这正是 12.6.1.2 要的输入**）。
   *
   * <p>🔴 按 {@code filing_scope × currency} 分组 ⇒ IOSS 与一般 VAT 天然分开。
   */
  public VatIossStatistics filingPeriodStatistics(String id) {
    UUID periodId = parseFilterUuid(id);
    Record period =
        sql.fetch(
                "select jurisdiction_code, period_start, period_end from vat_filing_period"
                    + " where tenant_id = ? and id = ?",
                currentTenant(),
                periodId)
            .stream()
            .findFirst()
            .orElseThrow(VatIossService::notFound);
    String jurisdictionCode = period.get("jurisdiction_code", String.class);
    LocalDate periodStart = period.get("period_start", LocalDate.class);
    LocalDate periodEnd = period.get("period_end", LocalDate.class);
    return statistics(
        jurisdictionCode, null, null, null, periodStart, periodEnd);
  }

  /** 全量统计（**按分档 × 币种**分组）。 */
  public VatIossStatistics statistics(
      String jurisdictionCode,
      String filingScope,
      String currency,
      String shopCode,
      LocalDate dateFrom,
      LocalDate dateTo) {
    UUID tenant = currentTenant();
    StringBuilder where = new StringBuilder(" where tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    if (!text(jurisdictionCode).isEmpty()) {
      where.append(" and jurisdiction_code = ?");
      params.add(text(jurisdictionCode).toUpperCase(Locale.ROOT));
    }
    if (!text(filingScope).isEmpty()) {
      where.append(" and filing_scope = ?");
      params.add(text(filingScope).toUpperCase(Locale.ROOT));
    }
    if (!text(currency).isEmpty()) {
      where.append(" and currency = ?");
      params.add(text(currency).toUpperCase(Locale.ROOT));
    }
    if (!text(shopCode).isEmpty()) {
      where.append(" and shop_code = ?");
      params.add(text(shopCode));
    }
    if (dateFrom != null) {
      where.append(" and order_date >= ?");
      params.add(dateFrom);
    }
    if (dateTo != null) {
      where.append(" and order_date <= ?");
      params.add(dateTo);
    }

    List<VatIossStatisticsRow> rows = new ArrayList<>();
    long totalOrders = 0;
    for (Record row :
        sql.fetch(
            "select filing_scope, currency, count(*) as n, sum(goods_amount) as goods,"
                + " sum(shipping_amount) as shipping, sum(tax_base) as base, sum(tax_amount) as tax"
                + " from vat_ioss_order"
                + where
                + " group by filing_scope, currency order by filing_scope, currency",
            params.toArray())) {
      String scope = row.get("filing_scope", String.class);
      rows.add(
          new VatIossStatisticsRow(
              scope,
              scopeLabel(scope),
              row.get("currency", String.class),
              ((Number) row.get("n")).longValue(),
              money(row.get("goods", BigDecimal.class)).toPlainString(),
              money(row.get("shipping", BigDecimal.class)).toPlainString(),
              money(row.get("base", BigDecimal.class)).toPlainString(),
              money(row.get("tax", BigDecimal.class)).toPlainString()));
      totalOrders += ((Number) row.get("n")).longValue();
    }

    return new VatIossStatistics(
        rows,
        totalOrders,
        false,
        "12.6.1.2",
        "🔴 **读不到申报底稿**：12.6.1.2 VAT/IOSS 申报数据在本仓尚未交付（方向上它是本模块的下游）。"
            + "本页的统计是本模块**自己算出来**的原始口径（按 IOSS / 非 IOSS 分档、按币种分组），"
            + "**不是** 12.6.1.2 的申报底稿 —— 本模块**不编造**它。",
        CURRENCY_SEMANTICS
            + " 🔴 取数时**先按分档分开**：IOSS 一组、一般 VAT 一组，"
            + "**不要**把它们加在一起报 —— 那正是蓝图加粗句说的最常见的错误。");
  }

  // ==================================================================
  // 订单清单
  // ==================================================================

  /** IOSS 订单清单（真分页 + 真筛选）。 */
  public VatIossOrderPage orders(
      String filingScope,
      String reasonCode,
      String currency,
      String jurisdictionCode,
      String shopCode,
      String keyword,
      LocalDate dateFrom,
      LocalDate dateTo,
      int page,
      int size) {
    UUID tenant = currentTenant();
    int limit = clampSize(size);
    int offset = Math.max(page, 0) * limit;

    StringBuilder where = new StringBuilder(" where tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    if (!text(filingScope).isEmpty()) {
      where.append(" and filing_scope = ?");
      params.add(text(filingScope).toUpperCase(Locale.ROOT));
    }
    if (!text(reasonCode).isEmpty()) {
      where.append(" and reason_code = ?");
      params.add(text(reasonCode).toUpperCase(Locale.ROOT));
    }
    if (!text(currency).isEmpty()) {
      where.append(" and currency = ?");
      params.add(text(currency).toUpperCase(Locale.ROOT));
    }
    if (!text(jurisdictionCode).isEmpty()) {
      where.append(" and jurisdiction_code = ?");
      params.add(text(jurisdictionCode).toUpperCase(Locale.ROOT));
    }
    if (!text(shopCode).isEmpty()) {
      where.append(" and shop_code = ?");
      params.add(text(shopCode));
    }
    if (!text(keyword).isEmpty()) {
      where.append(" and (source_order_no ilike '%' || ? || '%' or external_order_no ilike '%' || ? || '%')");
      params.add(text(keyword));
      params.add(text(keyword));
    }
    if (dateFrom != null) {
      where.append(" and order_date >= ?");
      params.add(dateFrom);
    }
    if (dateTo != null) {
      where.append(" and order_date <= ?");
      params.add(dateTo);
    }

    long total =
        ((Number) sql.fetchValue("select count(*) from vat_ioss_order" + where, params.toArray()))
            .longValue();

    List<VatIossOrderView> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id, source_order_id, source_sales_order_id, source_order_no, platform_code,"
                + " shop_code, order_source, order_status, customer_type, destination_country,"
                + " jurisdiction_code, order_date, currency, goods_amount, shipping_amount, tax_base,"
                + " tax_amount, tax_rate, tax_rate_source, filing_scope, reason_code, reason_detail,"
                + " synced_at from vat_ioss_order"
                + where
                + " order by order_date desc, source_order_no limit ? offset ?",
            append(params, limit, offset).toArray())) {
      items.add(orderView(row));
    }

    Map<String, Long> counts = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select filing_scope, count(*) as n from vat_ioss_order where tenant_id = ?"
                + " group by filing_scope",
            tenant)) {
      counts.put(row.get("filing_scope", String.class), ((Number) row.get("n")).longValue());
    }
    return new VatIossOrderPage(
        items,
        (int) total,
        Math.max(page, 0),
        limit,
        counts.getOrDefault("IOSS", 0L),
        counts.getOrDefault("GENERAL_VAT", 0L),
        counts.getOrDefault("OUT_OF_SCOPE", 0L),
        counts.getOrDefault("UNRESOLVED", 0L),
        "🔴 清单默认**不分档混排**：用 filingScope 筛选就能把 IOSS 与一般 VAT 分开取数；"
            + "四档计数是**全量**的分档计数（不受当前筛选影响）。");
  }

  private static VatIossOrderView orderView(Record row) {
    String scope = row.get("filing_scope", String.class);
    String reason = row.get("reason_code", String.class);
    BigDecimal rate = row.get("tax_rate", BigDecimal.class);
    LocalDate orderDate = row.get("order_date", LocalDate.class);
    OffsetDateTime syncedAt = row.get("synced_at", OffsetDateTime.class);
    return new VatIossOrderView(
        row.get("id", UUID.class).toString(),
        row.get("source_order_id", UUID.class).toString(),
        row.get("source_sales_order_id", UUID.class).toString(),
        row.get("source_order_no", String.class),
        row.get("platform_code", String.class),
        row.get("shop_code", String.class),
        row.get("order_source", String.class),
        row.get("order_status", String.class),
        row.get("customer_type", String.class),
        row.get("destination_country", String.class),
        row.get("jurisdiction_code", String.class),
        orderDate == null ? "" : orderDate.toString(),
        row.get("currency", String.class),
        money(row.get("goods_amount", BigDecimal.class)).toPlainString(),
        money(row.get("shipping_amount", BigDecimal.class)).toPlainString(),
        money(row.get("tax_base", BigDecimal.class)).toPlainString(),
        money(row.get("tax_amount", BigDecimal.class)).toPlainString(),
        rate == null ? "" : rate.toPlainString(),
        row.get("tax_rate_source", String.class),
        scope,
        scopeLabel(scope),
        reason,
        reasonLabel(reason),
        row.get("reason_detail", String.class),
        syncedAt == null ? "" : syncedAt.toString());
  }

  /** 单笔订单详情（判定 + 行明细）。 */
  public VatIossOrderDetail order(String id) {
    UUID orderId = parseFilterUuid(id);
    Record row =
        sql.fetch(
                "select id, source_order_id, source_sales_order_id, source_order_no, platform_code,"
                    + " shop_code, order_source, order_status, customer_type, destination_country,"
                    + " jurisdiction_code, order_date, currency, goods_amount, shipping_amount,"
                    + " tax_base, tax_amount, tax_rate, tax_rate_source, filing_scope, reason_code,"
                    + " reason_detail, synced_at from vat_ioss_order where tenant_id = ? and id = ?",
                currentTenant(),
                orderId)
            .stream()
            .findFirst()
            .orElseThrow(VatIossService::notFound);

    Set<String> exciseSkus = exciseSkuCodes();
    List<VatIossOrderLineView> lines = new ArrayList<>();
    for (Record line :
        sql.fetch(
            "select line_no, sku_code, sku_name, quantity, unit_price, line_amount, tax_amount,"
                + " tax_rate from vat_ioss_order_line where tenant_id = ? and order_id = ?"
                + " order by line_no",
            currentTenant(),
            orderId)) {
      BigDecimal lineRate = line.get("tax_rate", BigDecimal.class);
      lines.add(
          new VatIossOrderLineView(
              line.get("line_no", Integer.class),
              line.get("sku_code", String.class),
              line.get("sku_name", String.class),
              money(line.get("quantity", BigDecimal.class)).toPlainString(),
              money(line.get("unit_price", BigDecimal.class)).toPlainString(),
              money(line.get("line_amount", BigDecimal.class)).toPlainString(),
              money(line.get("tax_amount", BigDecimal.class)).toPlainString(),
              lineRate == null ? "" : lineRate.toPlainString(),
              exciseSkus.contains(text(line.get("sku_code", String.class)))));
    }
    return new VatIossOrderDetail(orderView(row), lines);
  }

  /** 当前生效登记里标为应税消费品的 SKU 集合。 */
  private Set<String> exciseSkuCodes() {
    Set<String> skus = new LinkedHashSet<>();
    RegistrationRow active = activeRegistration();
    if (active == null) {
      return skus;
    }
    for (VatIossGoodsScope scope : active.goodsScopes()) {
      if (scope.exciseGoods()) {
        skus.add(scope.skuCode());
      }
    }
    return skus;
  }

  // ==================================================================
  // 税率适用（读 finance 12.6.1.1）
  // ==================================================================

  /** 🔴 税率适用（**读不到就如实说读不到**，绝不退化成 0%）。 */
  public VatIossTaxRateView taxRate(
      String jurisdictionCode, String shopCode, LocalDate asOf, String bearer) {
    String code = text(jurisdictionCode).toUpperCase(Locale.ROOT);
    if (code.isEmpty()) {
      throw unprocessable("辖区码不能为空");
    }
    JurisdictionFact fact = jurisdictionByCode(code);
    if (fact == null) {
      throw unprocessable("辖区码 " + code + " 不在 16.3.1.1 的辖区码表里");
    }
    UUID shopId = null;
    if (!text(shopCode).isEmpty()) {
      Object raw =
          sql.fetchValue(
              "select id from shop where tenant_id = ? and code = ?",
              currentTenant(),
              text(shopCode));
      if (raw != null) {
        shopId = (UUID) raw;
      }
    }
    OffsetDateTime moment =
        asOf == null
            ? dbNow()
            : asOf.atStartOfDay().atOffset(ZoneOffset.UTC);
    TaxResolution resolution = financeTax.resolve("VAT", moment, shopId, code, bearer);
    return new VatIossTaxRateView(
        resolution.available(),
        resolution.taxType(),
        resolution.jurisdictionCode(),
        resolution.jurisdictionName(),
        resolution.rate() == null ? "" : resolution.rate().toPlainString(),
        resolution.resolvedBy(),
        resolution.asOf() == null ? "" : resolution.asOf().toString(),
        resolution.impact());
  }

  // ==================================================================
  // 🔴 IOSS 订单识别（从 trade 拉真实订单 → 判定 → 落库）
  // ==================================================================

  /** 🔴 识别一批订单（**上游读不到就整体失败，一条都不写**）。 */
  public VatIossOrderSyncResult syncOrders(String shopCode, String bearer) {
    UUID tenant = currentTenant();
    TradeOrderPort.PlatformOrderScan scan = tradeOrders.scan(text(shopCode), bearer);

    RegistrationRow active = activeRegistration();
    Set<String> exciseSkus = exciseSkuCodes();
    Map<String, UUID> shopIds = shopIdsByCode();
    Map<CachedRateKey, TaxResolution> rateCache = new LinkedHashMap<>();

    long ioss = 0;
    long generalVat = 0;
    long outOfScope = 0;
    long unresolved = 0;

    for (PlatformOrderRow row : scan.items()) {
      SalesOrderDetail detail = tradeOrders.detail(row.salesOrderId(), bearer);
      String destinationCountry = shippingCountry(detail);
      JurisdictionFact destination =
          jurisdictionForCountry(destinationCountry);
      String customerType =
          detail.customer() == null || detail.customer().customerType() == null
              ? ""
              : detail.customer().customerType();

      BigDecimal goodsAmount =
          money(row.platformOriginalAmount()).subtract(money(row.platformDiscountAmount()));
      if (goodsAmount.signum() < 0) {
        goodsAmount = BigDecimal.ZERO;
      }
      BigDecimal shippingAmount = money(row.platformShippingAmount());
      BigDecimal taxBase = goodsAmount.add(shippingAmount);
      BigDecimal taxAmount = money(row.platformTaxAmount());
      String currency = text(row.currency()).toUpperCase(Locale.ROOT);
      LocalDate orderDate =
          row.platformPlacedAt() == null
              ? LocalDate.now(ZoneOffset.UTC)
              : row.platformPlacedAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDate();

      // ---- 适用店铺 ----
      boolean shopInScope =
          active != null
              && active.shopScopes().stream()
                  .anyMatch(
                      scope ->
                          scope.platformCode().equalsIgnoreCase(text(row.platformCode()))
                              && scope.shopCode().equalsIgnoreCase(text(row.shopCode())));

      // ---- 免征门槛比较（门槛币种 ≠ 订单币种时要折算；读不到汇率 ⇒ 判不了）----
      BigDecimal threshold =
          active == null ? new BigDecimal("150.000000") : money(active.thresholdAmount());
      String thresholdCurrency = active == null ? "EUR" : active.thresholdCurrency();
      boolean thresholdComparable;
      BigDecimal intrinsicInThresholdCurrency;
      if (currency.equalsIgnoreCase(thresholdCurrency)) {
        thresholdComparable = true;
        intrinsicInThresholdCurrency = taxBase;
      } else {
        BigDecimal rate =
            exchangeRates.rate(currency, thresholdCurrency, orderDate, bearer);
        if (rate == null) {
          thresholdComparable = false;
          intrinsicInThresholdCurrency = null;
        } else {
          thresholdComparable = true;
          intrinsicInThresholdCurrency = convert(taxBase, rate);
        }
      }

      // ---- 应税消费品 ----
      boolean hasExcise = false;
      if (detail.lines() != null) {
        for (SalesOrderLineRow line : detail.lines()) {
          if (exciseSkus.contains(text(line.skuCode()))) {
            hasExcise = true;
            break;
          }
        }
      }

      // ---- 判定 ----
      VatIossRules.Decision decision =
          classify(
              new Inputs(
                  "CANCELLED".equalsIgnoreCase(text(row.normalizedStatus())),
                  destinationCountry,
                  destination != null,
                  customerType,
                  active != null,
                  shopInScope,
                  hasExcise,
                  intrinsicInThresholdCurrency,
                  threshold,
                  thresholdComparable));

      // ---- 税率（读不到 ⇒ tax_rate 留 null，**不是 0%**）----
      TaxResolution rateResolution = null;
      if (destination != null) {
        CachedRateKey key =
            new CachedRateKey(destination.code(), shopIds.get(text(row.shopCode())), orderDate);
        rateResolution = rateCache.get(key);
        if (rateResolution == null) {
          rateResolution =
              financeTax.resolve(
                  "VAT",
                  orderDate.atStartOfDay().atOffset(ZoneOffset.UTC),
                  shopIds.get(text(row.shopCode())),
                  destination.code(),
                  bearer);
          rateCache.put(key, rateResolution);
        }
      }

      UUID orderId = upsertOrder(
          tenant,
          row,
          destinationCountry,
          destination,
          customerType,
          orderDate,
          currency,
          goodsAmount,
          shippingAmount,
          taxBase,
          taxAmount,
          rateResolution,
          decision);

      replaceLines(tenant, orderId, detail);

      switch (decision.filingScope()) {
        case "IOSS" -> ioss++;
        case "GENERAL_VAT" -> generalVat++;
        case "OUT_OF_SCOPE" -> outOfScope++;
        default -> unresolved++;
      }
    }

    return new VatIossOrderSyncResult(
        scan.items().size(),
        scan.items().size(),
        ioss,
        generalVat,
        outOfScope,
        unresolved,
        scan.truncated(),
        scan.pagesFetched(),
        "🔴 订单来自 trade 的**平台单投影**（真实上游，不是本模块造的数据）。"
            + "每一条都按十步判定表归档，分档写进 `filing_scope` 列 ⇒ **取数层面**就能把 IOSS 与一般 VAT 分开。"
            + (scan.truncated()
                ? " 🔴 **本次扫描撞到了翻页上限而没扫完** —— 统计结果**不是全量**，"
                    + "请按提示继续翻页或缩小店铺范围。"
                : ""));
  }

  /** 判定缓存的键。 */
  private record CachedRateKey(String jurisdictionCode, UUID shopId, LocalDate orderDate) {}

  /** 收货地址的国家码（收货地址没有就退到开票地址，再没有就是空）。 */
  private static String shippingCountry(SalesOrderDetail detail) {
    if (detail.addresses() == null) {
      return "";
    }
    String fallback = "";
    for (SalesOrderAddressRow address : detail.addresses()) {
      String country = text(address.countryCode()).toUpperCase(Locale.ROOT);
      if ("SHIPPING".equalsIgnoreCase(text(address.addressRole()))) {
        return country;
      }
      if (fallback.isEmpty()) {
        fallback = country;
      }
    }
    return fallback;
  }

  /** 店铺码 → 店铺 ID（本服务自己的 shop 表；找不到就是 null）。 */
  private Map<String, UUID> shopIdsByCode() {
    Map<String, UUID> ids = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select id, code from shop where tenant_id = ? and status <> 'ARCHIVED'",
            currentTenant())) {
      ids.put(text(row.get("code", String.class)), row.get("id", UUID.class));
    }
    return ids;
  }

  /** 写入 / 更新一笔订单的判定结果。 */
  private UUID upsertOrder(
      UUID tenant,
      PlatformOrderRow row,
      String destinationCountry,
      JurisdictionFact destination,
      String customerType,
      LocalDate orderDate,
      String currency,
      BigDecimal goodsAmount,
      BigDecimal shippingAmount,
      BigDecimal taxBase,
      BigDecimal taxAmount,
      TaxResolution rateResolution,
      VatIossRules.Decision decision) {
    boolean rateAvailable = rateResolution != null && rateResolution.available();
    UUID id =
        (UUID)
            sql.fetchValue(
                "select id from vat_ioss_order where tenant_id = ? and source_order_id = ?",
                tenant,
                row.id());
    if (id == null) {
      id = Ids.next();
    }
    sql.execute(
        "insert into vat_ioss_order(id,tenant_id,source_order_id,source_sales_order_id,"
            + " source_order_no,platform_code,shop_code,order_source,order_status,customer_type,"
            + " destination_country,jurisdiction_code,order_date,currency,goods_amount,"
            + " shipping_amount,tax_base,tax_amount,tax_rate,tax_rate_source,filing_scope,"
            + " reason_code,reason_detail,synced_at,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,now(),now())"
            + " on conflict (tenant_id, source_order_id) do update set"
            + " source_sales_order_id = excluded.source_sales_order_id,"
            + " source_order_no = excluded.source_order_no,"
            + " platform_code = excluded.platform_code, shop_code = excluded.shop_code,"
            + " order_source = excluded.order_source, order_status = excluded.order_status,"
            + " customer_type = excluded.customer_type,"
            + " destination_country = excluded.destination_country,"
            + " jurisdiction_code = excluded.jurisdiction_code, order_date = excluded.order_date,"
            + " currency = excluded.currency, goods_amount = excluded.goods_amount,"
            + " shipping_amount = excluded.shipping_amount, tax_base = excluded.tax_base,"
            + " tax_amount = excluded.tax_amount, tax_rate = excluded.tax_rate,"
            + " tax_rate_source = excluded.tax_rate_source, filing_scope = excluded.filing_scope,"
            + " reason_code = excluded.reason_code, reason_detail = excluded.reason_detail,"
            + " synced_at = excluded.synced_at, updated_at = now()",
        id,
        tenant,
        row.id(),
        row.salesOrderId(),
        text(row.orderNo()),
        text(row.platformCode()),
        text(row.shopCode()),
        text(row.orderSource()),
        text(row.normalizedStatus()),
        customerType.isEmpty() ? "UNKNOWN" : customerType,
        destinationCountry,
        destination == null ? "" : destination.code(),
        orderDate,
        currency,
        goodsAmount,
        shippingAmount,
        taxBase,
        taxAmount,
        rateAvailable ? rateResolution.rate() : null,
        rateAvailable ? "FINANCE" : "UNAVAILABLE",
        decision.filingScope(),
        decision.reasonCode(),
        decision.reasonDetail(),
        dbNow());
    return id;
  }

  /** 整组替换订单行明细。 */
  private void replaceLines(UUID tenant, UUID orderId, SalesOrderDetail detail) {
    sql.execute(
        "delete from vat_ioss_order_line where tenant_id = ? and order_id = ?", tenant, orderId);
    if (detail.lines() == null) {
      return;
    }
    for (SalesOrderLineRow line : detail.lines()) {
      BigDecimal quantity = money(line.quantity());
      if (quantity.signum() <= 0) {
        continue;
      }
      sql.execute(
          "insert into vat_ioss_order_line(id,tenant_id,order_id,line_no,sku_code,sku_name,"
              + " quantity,unit_price,line_amount,tax_amount,tax_rate,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,now(),now())",
          Ids.next(),
          tenant,
          orderId,
          line.lineNo(),
          text(line.skuCode()),
          text(line.skuName()),
          quantity,
          money(line.unitPrice()),
          money(line.lineAmount()),
          money(line.taxAmount()),
          null);
    }
  }

}
