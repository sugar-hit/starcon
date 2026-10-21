package com.starcon.catalog;

import static com.starcon.catalog.ProfitCalculatorModels.*;

import com.starcon.catalog.ProfitCalculatorModels.BlockingGap;
import com.starcon.catalog.ProfitCalculatorModels.CalculateRequest;
import com.starcon.catalog.ProfitCalculatorModels.Deduction;
import com.starcon.catalog.ProfitCalculatorModels.DeductionCapability;
import com.starcon.catalog.ProfitCalculatorModels.DeductionSource;
import com.starcon.catalog.ProfitCalculatorModels.DecisionBasis;
import com.starcon.catalog.ProfitCalculatorModels.FxView;
import com.starcon.catalog.ProfitCalculatorModels.LogisticsBand;
import com.starcon.catalog.ProfitCalculatorModels.ProfitCalculation;
import com.starcon.catalog.ProfitCalculatorModels.ProfitCalculatorCapabilities;
import com.starcon.catalog.ProfitCalculatorModels.ScenarioDetail;
import com.starcon.catalog.ProfitCalculatorModels.ScenarioPage;
import com.starcon.catalog.ProfitCalculatorModels.ScenarioSummary;
import com.starcon.catalog.ProfitCalculatorModels.SourceDetail;
import com.starcon.catalog.ProfitCalculatorModels.TierInput;
import com.starcon.catalog.ProfitCalculatorModels.WaterfallLine;
import com.starcon.catalog.SkuCostCompositionModels.CostComponentRow;
import com.starcon.catalog.SkuCostCompositionModels.CostDetail;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.3.1.1 利润测算器：把「售价 → 每一项扣减 → 毛利」的完整拆解算出来，并落成可复算的方案。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：输入 SKU + 目标店铺 + 售价，输出完整利润拆解：售价 → 平台费用 → 物流成本 → 采购成本 → 头程 → 其他 → 毛利额与毛利率；反向支持输入目标毛利率倒推可承受售价。
 *
 * **输入**：SKU、店铺、币种、售价或目标毛利率、数量（用于阶梯与运费）；成本、费用规则、汇率快照。
 *
 * **输出**：利润瀑布图 + 保本价 + 目标毛利价 + 盈亏平衡销量；参数可分享为方案。
 *
 * **关系**：上游 = 5.2 全部、6 采购成本、2 平台参数；下游 = 5.3.2.1 模板、5.4 调价。**这是定价决策的核心工具**，必须显式列出每一项扣减并允许展开查看来源，不能只给一个毛利率数字。
 *
 * </blockquote>
 *
 * <h2>🔴 本类的六条纪律</h2>
 *
 * <ol>
 *   <li><b>🔴 缺项就不给毛利率数字</b>。上游 5.2.3.1（平台费用）与 5.2.3.2（物流费用）未交付，
 *       缺了它们的毛利率是<b>系统性虚高</b>的。所以扣减不全时
 *       {@code grossProfit} / {@code grossMarginRate} 是 <b>null</b>，不是 0、不是估算值。
 *       <b>这条纪律落在服务层而不是页面上</b>：下游 5.3.2.1 模板与 5.4 调价会程序化消费本模块的输出，
 *       页面上的一句提示拦不住一个自动改价的规则。
 *   <li><b>🔴 每一项扣减都显式列出，且都能展开看来源</b>。来源四要素齐全：
 *       哪张表 / 哪个上游模块 / 什么口径 / 什么生效时间。缺项那一行<b>也在</b>——
 *       把它从瀑布图里删掉，就等于假装它不存在。
 *   <li><b>🔴 正向与反向走同一套结构</b>。正向（售价 → 毛利）与反向（目标毛利率 → 售价）
 *       是 {@link ProfitCalculatorRules} 里同一个方程（见该类注释），不存在第二套近似公式。
 *   <li><b>🔴 手工假设必须显名</b>。上游规则模块未交付时，允许人填平台费率/物流单价/汇率，
 *       但它们在响应里标 {@code MANUAL_ASSUMPTION} 并点名将来由谁接管，
 *       能力声明里对应扣减项的 {@code available} 仍然是 {@code false}。
 *   <li><b>🔴 跨币种绝不相加</b>。售价币种与成本币种不同就必须要汇率，
 *       方向显式（1 成本币种 = X 售价币种）；没有汇率就<b>拒绝测算</b>，不按 1:1 折算。
 *   <li><b>租户只来自已认证成员关系</b>（{@link AccessService}），不读 {@code X-Tenant-Id} 头。
 *       每条 SQL 的 where 都带显式 {@code tenant_id=} 绑定，RLS 再兜一道。
 * </ol>
 */
@Service
public class ProfitCalculatorService {

  /** 租户上下文取法：RLS 已按它隔离，这里带上只是让执行计划走租户前缀的索引。 */
  private static final String TENANT = "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 台账页大小上限：与相邻模块一致。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 成本口径窗口的默认跨度（天）。与 5.2.1.1 成本构成页同一默认值。 */
  private static final int DEFAULT_WINDOW_DAYS = 90;

  private final DSLContext sql;
  private final SkuCostCompositionService costs;
  private final ChannelPlatformPort platforms;

  public ProfitCalculatorService(
      DSLContext sql, SkuCostCompositionService costs, ChannelPlatformPort platforms) {
    this.sql = sql;
    this.costs = costs;
    this.platforms = platforms;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：七个扣减项各自「能不能取到、归谁管、缺了会怎样」。
   *
   * <p>页面上把它固定显示在顶部 —— 蓝图那句「必须显式列出每一项扣减」的第一层落点在这里。
   */
  public ProfitCalculatorCapabilities capabilities() {
    var deductions =
        List.of(
            new DeductionCapability(
                "PLATFORM_FEE",
                "平台费用",
                "05 定价与价目 · 平台费用规则（5.2.3.1）",
                "RULE",
                false,
                "5.2.3.1 平台费用",
                "🔴 缺平台佣金时毛利率是**系统性虚高**的（平台佣金通常是售价的 8%~15%），"
                    + "保本价与目标毛利价会同时偏低 ⇒ 直接拿去改价会改错。",
                "佣金比例（或阶梯）+ 每单固定费 + 订阅费分摊，按平台/站点/类目取规则。",
                "5.2.3.1 未交付 ⇒ 本模块不建任何平台费用规则表。"
                    + "在它交付之前，平台费只能由人作为**手工假设**填入（显式标注 ownerModule=5.2.3.1），"
                    + "不填则这一项在瀑布图上显示「未交付 / 该扣减未计入」，且**不输出毛利率数字**。"),
            new DeductionCapability(
                "LOGISTICS",
                "物流成本",
                "05 定价与价目 · 物流与头程费用（5.2.3.2）",
                "RULE",
                false,
                "5.2.3.2 物流与头程费用",
                "🔴 缺尾程物流时单位成本偏低、毛利率虚高（每单几美元量级），"
                    + "而且「满额免运费」这类阶梯会让同一 SKU 在不同数量下差出一大截。",
                "按重量/体积/件数的渠道计费规则 + 燃油/旺季/偏远附加 + 头程运输方式费用。",
                "5.2.3.2 未开发 ⇒ 本模块不建任何计费规则表。"
                    + "在此之前，物流单价只能作为**手工假设**填入（可带数量阶梯），"
                    + "不填则这一项在瀑布图上显示「未交付 / 该扣减未计入」，且**不输出毛利率数字**。"),
            new DeductionCapability(
                "PURCHASE",
                "采购成本",
                "05 定价与价目 · 成本构成（5.2.1.1）· catalog 同库 vendor_delivery_record",
                "DERIVED",
                true,
                "5.2.1.1 成本构成",
                "采购成本取不到时单位成本偏低、毛利率虚高。",
                "按所选成本法（移动加权/先进先出/批次）对窗口内的实际到货金额加权。",
                "6.3 采购单未交付 ⇒ 取的是 catalog 同库的**实际到货金额**（已到货事实），"
                    + "不是采购单成交价；供应商报价只作只读参考，不计入成本。"),
            new DeductionCapability(
                "FIRST_MILE",
                "头程分摊",
                "10 物流与头程（warehouse）· first_mile_shipment / _line（经授权只读端点）",
                "DERIVED",
                true,
                "5.2.1.1 成本构成",
                "头程取不到时单位成本偏低、毛利率虚高（头程在海运口径下往往不是小数目）。",
                "一张发运单的运费 + 保险费 + 操作费按各行数量摊到 SKU 上。",
                "🔴 5.2.1.3 成本分摊未交付 ⇒ 头程当前是**按数量**摊的，"
                    + "不是按重量/体积/金额规则摊的。混合货件的分摊差异会直接体现在这一项上。"),
            new DeductionCapability(
                "PACKAGING",
                "包装成本",
                "05 定价与价目 · 成本构成（5.2.1.1）· sku_cost_profile.packaging_cost",
                "PARAMETER",
                true,
                "5.2.1.1 成本构成",
                "包装成本未维护时单位成本偏低、毛利率虚高。",
                "人工参数：由人维护的单位包装成本。",
                "全仓没有包装成本事实（包材目录无成本列）⇒ 它是**参数**。"
                    + "可空 = 尚未维护，**不是 0**：未维护时这一项显示「未维护」而不是 0.00。"
                    + "补齐办法是到 5.2.1.1 成本构成页保存构成时填上。"),
            new DeductionCapability(
                "DUTY",
                "关税",
                "10 物流与头程（warehouse）· duty_tax_estimate / _line（经授权只读端点）",
                "DERIVED",
                true,
                "5.2.1.1 成本构成",
                "关税取不到时单位成本偏低、毛利率虚高。",
                "按报关口径数量折算到 SKU 基础单位后的单位关税（增值税不并入关税）。",
                "🔴 12 财务（finance 服务）当前不存在 ⇒ 这是**本服务口径**，不是 12 财务的核算结果。"),
            new DeductionCapability(
                "OTHER_DIRECT",
                "其他直接成本",
                "05 定价与价目 · 成本构成（5.2.1.1）· sku_cost_adjustment",
                "MANUAL",
                true,
                "5.2.1.1 成本构成",
                "其他直接成本取不到时单位成本偏低、毛利率虚高。",
                "手工调整项累加（每一笔都带原因，追加式）。",
                "5.2.1.1 已交付 ⇒ 这一项是本服务自持的事实，**没有上游缺口**。"));

    return new ProfitCalculatorCapabilities(
        deductions,
        List.of(),
        "🔴 汇率（5.2.2.1 汇率查询 / 5.2.2.2 汇率快照）**未交付**：本服务没有汇率表，"
            + "所以售价币种与成本币种不同时**必须**由人作为手工假设填入汇率（方向显式：1 成本币种 = X 售价币种），"
            + "不填则**拒绝测算** —— 绝不按 1:1 折算，那会把两个币种的金额加在一起。",
        "🔴 **决策就绪闸**：只要有一项扣减取不到，本模块就**不输出毛利率数字**"
            + "（grossProfit / grossMarginRate 为 null，不是 0 也不是估算值），"
            + "只给「售价减掉已知扣减后还剩多少可用于覆盖缺失扣减」—— 那是余额，不是毛利。"
            + "这条闸落在服务层：下游 5.3.2.1 模板与 5.4 调价会程序化消费本模块的输出，"
            + "页面上的一句提示拦不住一个自动改价的规则。",
        "成本口径取 5.2.1.1 成本构成的**单位成本构成**（采购 + 头程分摊 + 包装 + 关税 + 其他直接成本），"
            + "🔴 不是 5.2.1.2 到岸成本口径（到岸成本未交付）—— 两者不是一回事："
            + "到岸成本是「货到目标仓可售」的总成本，含国际头程与清关；"
            + "本页用的是「单位成本构成」，头程与关税来自 10 的发运单与关税估算事实。"
            + "海外仓与自发货的口径差异要等 5.2.1.2 落地后由它接管。",
        "上游 = 5.2 全部（**当前只有 5.2.1.1 已交付**）、6 采购成本（经 catalog 同库的到货事实）、"
            + "2 平台参数（2.1.1.1 平台目录，跨服务只读）。"
            + "🔴 蓝图里的「5.2 全部」是**卷级引用**，本模块逐项标注了卷内每个模块的真实交付状态。",
        "下游 = 5.3.2.1 定价模板、5.4 调价。两者都会**拿测算结果直接改价**，"
            + "所以它们必须先看 decisionReady / decisionBasis："
            + "INCOMPLETE 的结果里**没有**毛利率数字可读，只有缺口清单。",
        "每一项扣减都带来源四要素（哪张表 / 哪个上游模块 / 什么口径 / 什么生效时间），"
            + "页面上逐项可展开。金额全程 BigDecimal：金额 4 位小数、费率与毛利率 6 位小数，"
            + "统一 HALF_UP；页面上写明。",
        "两种测算方向：按售价测算（PRICE）与按目标毛利率倒推售价（TARGET_MARGIN）。"
            + "🔴 两者走**同一套扣减结构**（同一个方程解两次），不是两套近似公式。",
        "测算结果可保存为**方案**（含参数、手工假设、物流阶梯与成本窗口），"
            + "方案可复算：同租户拿到同一个方案能算出同一个结果。"
            + "🔴 方案是**租户内**的：分享链接仍然要求登录并落在分享者自己的租户上下文里 ——"
            + "定价参数（成本、费率、汇率）跨租户可见是越权，不是协作。");
  }

  // ================================================================ 站点（目标店铺）

  /**
   * 目标店铺（站点）列表：来自 2.1.1.1 平台目录。
   *
   * <p>🔴 上游不可达时返回 <b>503</b>，不返回空列表冒充「这个平台没有站点」。
   */
  public List<SiteOption> sites(String bearer) {
    List<SiteOption> out = new ArrayList<>();
    for (var site : platforms.sites(bearer)) {
      out.add(
          new SiteOption(
              site.platformCode(),
              site.platformName(),
              site.siteCode(),
              site.siteName(),
              site.countryCode(),
              site.defaultCurrency(),
              site.vatRequired()));
    }
    return out;
  }

  // ================================================================ 测算

  /** 一次测算（不落库）。 */
  public ProfitCalculation calculate(CalculateRequest request, String bearer) {
    var normalized = normalize(request);
    var detail = costDetail(normalized, bearer);
    return compute(normalized, detail);
  }

  // ================================================================ 方案

  /** 保存测算方案（参数 + 手工假设 + 物流阶梯 + 成本窗口），并返回复算结果。 */
  public ScenarioDetail saveScenario(
      AccessService.Access actor, CalculateRequest request, String bearer) {
    var normalized = normalize(request);
    var detail = costDetail(normalized, bearer);
    var calculation = compute(normalized, detail);

    UUID id = UUID.randomUUID();
    var tiers = normalized.logisticsTiers();
    sql.execute(
        "insert into profit_calculator_scenario(id,tenant_id,sku_id,platform_code,site_code,"
            + "currency,cost_currency,mode,input_price,target_margin_rate,quantity,"
            + "cost_window_from,cost_window_to,fx_rate,platform_fee_rate,platform_fee_fixed,"
            + "logistics_cost_per_unit,monthly_fixed_cost,decision_basis,note,created_by,"
            + "created_at,updated_at)"
            + " values(?::uuid,?::uuid,?::uuid,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::uuid,now(),now())",
        id,
        actor.tenantId(),
        normalized.skuId(),
        normalized.platformCode(),
        normalized.siteCode(),
        normalized.currency(),
        normalized.costCurrency(),
        normalized.mode().name(),
        normalized.inputPrice(),
        normalized.targetMarginRate(),
        normalized.quantity(),
        normalized.costWindowFrom(),
        normalized.costWindowTo(),
        normalized.fxRate(),
        normalized.platformFeeRate(),
        normalized.platformFeeFixed(),
        normalized.logisticsCostPerUnit(),
        normalized.monthlyFixedCost(),
        calculation.decisionBasis().name(),
        normalized.note(),
        actor.userId());
    int seq = 1;
    for (var tier : tiers) {
      sql.execute(
          "insert into profit_calculator_logistics_tier(id,tenant_id,scenario_id,seq,up_to_quantity,"
              + "per_unit_cost,created_at) values(?::uuid,?::uuid,?::uuid,?,?,?,now())",
          UUID.randomUUID(),
          actor.tenantId(),
          id,
          seq++,
          tier.upToQuantity(),
          tier.perUnitCost());
    }
    var sku = skuInfo(normalized.skuId());
    var summary =
        new ScenarioSummary(
            id,
            normalized.skuId(),
            sku == null ? null : sku.code(),
            sku == null ? null : sku.name(),
            normalized.platformCode(),
            normalized.siteCode(),
            normalized.currency(),
            normalized.costCurrency(),
            normalized.mode(),
            normalized.inputPrice(),
            normalized.targetMarginRate(),
            normalized.quantity(),
            calculation.decisionBasis(),
            actor.userId() == null ? null : actor.userId().toString(),
            OffsetDateTime.now(),
            OffsetDateTime.now());
    return new ScenarioDetail(
        summary,
        normalized,
        tiers,
        normalized.platformFeeRate(),
        normalized.platformFeeFixed(),
        normalized.logisticsCostPerUnit(),
        normalized.fxRate(),
        normalized.monthlyFixedCost(),
        normalized.note(),
        calculation);
  }

  /** 方案列表（分页）。 */
  public ScenarioPage listScenarios(int page, int size) {
    int limited = Math.min(Math.max(size <= 0 ? 20 : size, 1), MAX_PAGE_SIZE);
    int offset = Math.max(page, 0) * limited;
    var rows =
        sql.fetch(
            "select s.id, s.sku_id, k.code as sku_code, k.name as sku_name, s.platform_code,"
                + " s.site_code, s.currency, s.cost_currency, s.mode, s.input_price,"
                + " s.target_margin_rate, s.quantity, s.decision_basis, s.created_by,"
                + " s.created_at, s.updated_at"
                + " from profit_calculator_scenario s"
                + " left join sku k on k.tenant_id=s.tenant_id and k.id=s.sku_id"
                + " where s.tenant_id="
                + TENANT
                + " order by s.updated_at desc, s.id"
                + " limit ? offset ?",
            limited,
            offset);
    long total =
        sql.fetchOne("select count(*) from profit_calculator_scenario where tenant_id=" + TENANT)
            .get(0, Long.class);
    var items = new ArrayList<ScenarioSummary>();
    for (var row : rows) {
      items.add(
          new ScenarioSummary(
              row.get("id", UUID.class),
              row.get("sku_id", UUID.class),
              row.get("sku_code", String.class),
              row.get("sku_name", String.class),
              row.get("platform_code", String.class),
              row.get("site_code", String.class),
              row.get("currency", String.class),
              row.get("cost_currency", String.class),
              CalcMode.parse(row.get("mode", String.class)),
              row.get("input_price", BigDecimal.class),
              row.get("target_margin_rate", BigDecimal.class),
              row.get("quantity", BigDecimal.class),
              decisionBasisOf(row.get("decision_basis", String.class)),
              row.get("created_by", UUID.class) == null
                  ? null
                  : row.get("created_by", UUID.class).toString(),
              row.get("created_at", OffsetDateTime.class),
              row.get("updated_at", OffsetDateTime.class)));
    }
    return new ScenarioPage(items, Math.max(page, 0), limited, total);
  }

  /** 方案详情：用它复算一遍。 */
  public ScenarioDetail getScenario(UUID id, String bearer) {
    var row =
        sql.fetchOne(
            "select id, sku_id, platform_code, site_code, currency, cost_currency, mode,"
                + " input_price, target_margin_rate, quantity, cost_window_from, cost_window_to,"
                + " fx_rate, platform_fee_rate, platform_fee_fixed, logistics_cost_per_unit,"
                + " monthly_fixed_cost, decision_basis, note, created_by, created_at, updated_at"
                + " from profit_calculator_scenario where tenant_id="
                + TENANT
                + " and id=?",
            id);
    if (row == null) throw notFound("测算方案不存在");
    var tiers = new ArrayList<TierInput>();
    for (var tier :
        sql.fetch(
            "select up_to_quantity, per_unit_cost from profit_calculator_logistics_tier"
                + " where tenant_id="
                + TENANT
                + " and scenario_id=? order by seq",
            id)) {
      tiers.add(
          new TierInput(
              tier.get("up_to_quantity", BigDecimal.class),
              tier.get("per_unit_cost", BigDecimal.class)));
    }
    var request =
        new CalculateRequest(
            row.get("sku_id", UUID.class),
            row.get("site_code", String.class),
            row.get("platform_code", String.class),
            row.get("currency", String.class),
            row.get("cost_currency", String.class),
            CalcMode.parse(row.get("mode", String.class)),
            row.get("input_price", BigDecimal.class),
            row.get("target_margin_rate", BigDecimal.class),
            row.get("quantity", BigDecimal.class),
            row.get("fx_rate", BigDecimal.class),
            row.get("platform_fee_rate", BigDecimal.class),
            row.get("platform_fee_fixed", BigDecimal.class),
            row.get("logistics_cost_per_unit", BigDecimal.class),
            row.get("monthly_fixed_cost", BigDecimal.class),
            tiers,
            row.get("cost_window_from", LocalDate.class),
            row.get("cost_window_to", LocalDate.class),
            row.get("note", String.class));
    var normalized = normalize(request);
    var detail = costDetail(normalized, bearer);
    var calculation = compute(normalized, detail);
    var sku = skuInfo(normalized.skuId());
    var summary =
        new ScenarioSummary(
            id,
            normalized.skuId(),
            sku == null ? null : sku.code(),
            sku == null ? null : sku.name(),
            normalized.platformCode(),
            normalized.siteCode(),
            normalized.currency(),
            normalized.costCurrency(),
            normalized.mode(),
            normalized.inputPrice(),
            normalized.targetMarginRate(),
            normalized.quantity(),
            calculation.decisionBasis(),
            row.get("created_by", UUID.class) == null
                ? null
                : row.get("created_by", UUID.class).toString(),
            row.get("created_at", OffsetDateTime.class),
            row.get("updated_at", OffsetDateTime.class));
    return new ScenarioDetail(
        summary,
        normalized,
        tiers,
        normalized.platformFeeRate(),
        normalized.platformFeeFixed(),
        normalized.logisticsCostPerUnit(),
        normalized.fxRate(),
        normalized.monthlyFixedCost(),
        normalized.note(),
        calculation);
  }

  /** 删除方案（连同它的物流阶梯）。 */
  public void deleteScenario(UUID id) {
    int removed =
        sql.execute(
            "delete from profit_calculator_scenario where tenant_id=" + TENANT + " and id=?", id);
    if (removed == 0) throw notFound("测算方案不存在");
  }

  // ================================================================ 内部：归一化与校验

  /** 归一化 + 校验：把请求变成一个内部可用的形状，不合法就 400。 */
  private CalculateRequest normalize(CalculateRequest request) {
    if (request == null) throw badRequest("测算请求不能为空");
    if (request.skuId() == null) throw badRequest("必须指定 SKU");
    if (request.mode() == null) throw badRequest("必须指定测算方向（PRICE 或 TARGET_MARGIN）");
    String currency = upper(request.currency());
    if (currency == null) throw badRequest("必须指定售价币种");
    String costCurrency = upper(request.costCurrency());
    if (costCurrency == null) costCurrency = currency;
    BigDecimal quantity = request.quantity() == null ? BigDecimal.ONE : request.quantity();
    if (quantity.signum() <= 0) throw badRequest("数量必须大于 0");
    if (request.mode() == CalcMode.PRICE
        && (request.inputPrice() == null || request.inputPrice().signum() <= 0)) {
      throw badRequest("按售价测算时必须给一个大于 0 的售价");
    }
    if (request.mode() == CalcMode.TARGET_MARGIN && request.targetMarginRate() == null) {
      throw badRequest("按目标毛利率倒推时必须给目标毛利率");
    }
    if (request.targetMarginRate() != null
        && (request.targetMarginRate().signum() < 0
            || request.targetMarginRate().compareTo(BigDecimal.ONE) >= 0)) {
      throw badRequest("目标毛利率是小数口径（0.25 表示 25%），必须在 0 与 1 之间");
    }
    if (request.platformFeeRate() != null
        && (request.platformFeeRate().signum() < 0
            || request.platformFeeRate().compareTo(BigDecimal.ONE) >= 0)) {
      throw badRequest("平台费率是小数口径（0.15 表示 15%），必须在 0 与 1 之间");
    }
    // 🔴 跨币种必须要汇率：没有就拒绝，**绝不按 1:1 折算**。
    if (!currency.equals(costCurrency) && (request.fxRate() == null || request.fxRate().signum() <= 0)) {
      throw badRequest(
          "售价币种（"
              + currency
              + "）与成本币种（"
              + costCurrency
              + "）不同，必须给汇率：1 "
              + costCurrency
              + " = X "
              + currency
              + "。汇率规则（5.2.2.1 汇率查询 / 5.2.2.2 汇率快照）未交付，"
              + "所以这里只能由人作为手工假设填入 —— 绝不按 1:1 折算，"
              + "那会把两个币种的金额当成同一个币种加在一起");
    }
    // 物流阶梯：段序升序、段容量为正、单价不为负、最后一段必须无上限。
    var tiers = request.logisticsTiers() == null ? List.<TierInput>of() : request.logisticsTiers();
    BigDecimal previous = BigDecimal.ZERO;
    for (var tier : tiers) {
      if (tier == null || tier.perUnitCost() == null) throw badRequest("物流阶梯的每单位运费必填");
      if (tier.perUnitCost().signum() < 0) throw badRequest("物流阶梯的每单位运费不能为负");
      if (tier.upToQuantity() != null) {
        if (tier.upToQuantity().signum() <= 0) throw badRequest("物流阶梯的段容量必须大于 0");
        if (tier.upToQuantity().compareTo(previous) <= 0) {
          throw badRequest("物流阶梯必须按数量升序排列，且每段容量严格大于上一段");
        }
        previous = tier.upToQuantity();
      }
    }
    if (!tiers.isEmpty() && tiers.get(tiers.size() - 1).upToQuantity() != null) {
      throw badRequest(
          "物流阶梯的最后一段必须是无上限的（不填「覆盖到第几单」），"
              + "否则超出阶梯的数量没有运费定义 —— 本模块不会拿前面几段的平均值糊过去");
    }
    LocalDate from =
        request.costWindowFrom() == null
            ? LocalDate.now().minusDays(DEFAULT_WINDOW_DAYS)
            : request.costWindowFrom();
    LocalDate to = request.costWindowTo() == null ? LocalDate.now() : request.costWindowTo();
    if (to.isBefore(from)) throw badRequest("成本口径窗口的终点不能早于起点");
    return new CalculateRequest(
        request.skuId(),
        nz(request.siteCode()),
        nz(request.platformCode()),
        currency,
        costCurrency,
        request.mode(),
        request.inputPrice(),
        request.targetMarginRate(),
        quantity,
        request.fxRate(),
        request.platformFeeRate(),
        request.platformFeeFixed(),
        request.logisticsCostPerUnit(),
        request.monthlyFixedCost(),
        tiers,
        from,
        to,
        request.note());
  }

  /** 取成本构成（5.2.1.1 口径）。取不到就是取不到 —— 上游不可达时它自己抛 503。 */
  private CostDetail costDetail(CalculateRequest request, String bearer) {
    return costs.detail(
        request.skuId(),
        request.costCurrency(),
        request.costWindowFrom(),
        request.costWindowTo(),
        bearer);
  }

  // ================================================================ 内部：算

  /** 核心：把成本构成 + 手工假设组装成瀑布图并跑引擎。 */
  private ProfitCalculation compute(CalculateRequest request, CostDetail detail) {
    var gaps = new ArrayList<BlockingGap>();
    var manualComponents = new ArrayList<String>();

    // ---- 汇率 ----
    boolean sameCurrency = request.currency().equals(request.costCurrency());
    BigDecimal fxRate = sameCurrency ? BigDecimal.ONE : request.fxRate();
    var fx =
        new FxView(
            request.costCurrency(),
            request.currency(),
            fxRate,
            "1 "
                + request.costCurrency()
                + " = "
                + (fxRate == null ? "—" : fxRate.toPlainString())
                + " "
                + request.currency(),
            sameCurrency ? "SAME_CURRENCY" : "MANUAL_ASSUMPTION",
            sameCurrency ? "—" : "5.2.2.1 汇率查询 / 5.2.2.2 汇率快照",
            fxRate != null,
            sameCurrency
                ? "售价币种与成本币种相同，不需要换算。"
                : "🔴 汇率规则（5.2.2.1 汇率查询 / 5.2.2.2 汇率快照）**未交付**："
                    + "本服务没有汇率表，这个汇率是**手工假设**，方向显式（1 成本币种 = X 售价币种）。");
    if (!sameCurrency) {
      manualComponents.add("汇率");
      gaps.add(
          new BlockingGap(
              "FX",
              "汇率",
              "5.2.2.1 汇率查询 / 5.2.2.2 汇率快照",
              "汇率口径不是系统取数：5.2.2.1 汇率查询与 5.2.2.2 汇率快照都未交付，"
                  + "所以这个汇率是**手工假设**。方向写明了（1 成本币种 = X 售价币种），"
                  + "但它没有历史留痕 —— 汇率变了，当年的利润会跟着变。",
              "在本页手工填入汇率，或等 5.2.2.1 交付后由系统取数（5.2.2.2 会把汇率冻结到单据上）。",
              true));
    }

    // ---- 成本分项（来自 5.2.1.1）----
    var byCode = new LinkedHashMap<String, CostComponentRow>();
    for (var component : detail.components()) {
      byCode.put(component.code(), component);
    }
    var costInputs = new ArrayList<ProfitCalculatorRules.CostInput>();
    for (var code :
        List.of(
            Deduction.PURCHASE,
            Deduction.FIRST_MILE,
            Deduction.PACKAGING,
            Deduction.DUTY,
            Deduction.OTHER_DIRECT)) {
      var component = byCode.get(code.name());
      BigDecimal amount = null;
      boolean available = false;
      boolean partial = false;
      String reason = null;
      if (component != null) {
        available = component.available();
        partial = component.partial();
        reason = component.unavailableReason();
        if (available && component.amount() != null) {
          amount = component.amount();
          if (!sameCurrency && fxRate != null) {
            amount = ProfitCalculatorRules.money(amount.multiply(fxRate));
          }
        }
      } else {
        reason = "成本构成里没有这一项（5.2.1.1 未返回）。";
      }
      costInputs.add(
          new ProfitCalculatorRules.CostInput(code.name(), available, partial, amount, reason));
      if (!available) {
        gaps.add(
            new BlockingGap(
                code.name(),
                code.label(),
                "5.2.1.1 成本构成",
                "这一项取不到，单位成本就偏低，毛利率会虚高。",
                "到 5.2.1.1 成本构成页补齐（保存构成 / 维护参数），或等上游事实补齐后重算。",
                false));
      } else if (partial) {
        gaps.add(
            new BlockingGap(
                code.name(),
                code.label(),
                "5.2.1.1 成本构成",
                "这一项只取到了部分事实（"
                    + detail.excludedDeliveryCount()
                    + " 条到货记录被排除），合计只是「已知部分合计」。",
                "到 5.2.1.1 成本构成页看被排除的记录（多为采购单位折算不出基础单位）。",
                false));
      }
    }

    // ---- 平台费用（5.2.3.1 未交付 ⇒ 只能手工假设）----
    boolean platformFeeKnown = request.platformFeeRate() != null;
    if (platformFeeKnown) manualComponents.add("平台费用");
    gaps.add(
        platformFeeKnown
            ? new BlockingGap(
                "PLATFORM_FEE",
                "平台费用",
                "5.2.3.1 平台费用",
                "口径提醒：🔴 5.2.3.1 平台费用规则**未交付**，"
                    + "这一项是**手工假设**（将来由 5.2.3.1 的规则接管），"
                    + "不是按平台/站点/类目取到的佣金规则。",
                "等 5.2.3.1 交付后由系统取数；在那之前，人填的费率就是唯一口径，请写进方案备注。",
                true)
            : new BlockingGap(
                "PLATFORM_FEE",
                "平台费用",
                "5.2.3.1 平台费用",
                "🔴 缺平台佣金时毛利率是**系统性虚高**的（平台佣金通常是售价的 8%~15%），"
                    + "保本价与目标毛利价会同时偏低 ⇒ 直接拿去改价会改错。",
                "在本页手工填入平台费率与固定费（显式标为手工假设），或等 5.2.3.1 交付后由系统取数。",
                false));

    // ---- 物流成本（5.2.3.2 未开发 ⇒ 只能手工假设）----
    var tierSpecs = new ArrayList<ProfitCalculatorRules.TierSpec>();
    for (var tier : request.logisticsTiers()) {
      tierSpecs.add(new ProfitCalculatorRules.TierSpec(tier.upToQuantity(), tier.perUnitCost()));
    }
    boolean tierComplete = ProfitCalculatorRules.tierCoverageComplete(tierSpecs, request.quantity());
    BigDecimal logisticsPerUnit =
        ProfitCalculatorRules.logisticsPerUnit(
            tierSpecs, request.quantity(), request.logisticsCostPerUnit());
    boolean logisticsKnown = logisticsPerUnit != null;
    if (logisticsKnown) manualComponents.add("物流成本");
    gaps.add(
        logisticsKnown
            ? new BlockingGap(
                "LOGISTICS",
                "物流成本",
                "5.2.3.2 物流与头程费用",
                "口径提醒：🔴 5.2.3.2 物流与头程费用**未开发**，"
                    + "这一项是**手工假设**（将来由 5.2.3.2 的渠道计费规则接管），"
                    + "不是按重量/体积/件数算出来的真实运费。",
                "等 5.2.3.2 交付后由系统取数；在那之前请把运费口径写进方案备注。",
                true)
            : new BlockingGap(
                "LOGISTICS",
                "物流成本",
                "5.2.3.2 物流与头程费用",
                "🔴 缺尾程物流时单位成本偏低、毛利率虚高（每单几美元量级）。",
                "在本页手工填入物流单价（可带数量阶梯），或等 5.2.3.2 交付后由系统取数。",
                false));

    // ---- 引擎 ----
    var engineInput =
        new ProfitCalculatorRules.Input(
            request.mode() == CalcMode.PRICE ? request.inputPrice() : null,
            request.targetMarginRate(),
            request.quantity(),
            request.platformFeeRate(),
            request.platformFeeFixed(),
            logisticsPerUnit,
            request.monthlyFixedCost(),
            tierSpecs,
            costInputs);
    var output = ProfitCalculatorRules.evaluate(request.mode(), engineInput);

    // ---- 决策就绪度 ----
    var basis =
        output.complete()
            ? (manualComponents.isEmpty()
                ? DecisionBasis.COMPLETE_FROM_RULES
                : DecisionBasis.COMPLETE_FROM_MANUAL_ASSUMPTIONS)
            : DecisionBasis.INCOMPLETE;

    // ---- 瀑布图（收入 → 每一项扣减 → 毛利）----
    var lines = new ArrayList<WaterfallLine>();
    lines.add(revenueLine(request, output));
    for (var code :
        List.of(
            Deduction.PURCHASE,
            Deduction.FIRST_MILE,
            Deduction.PACKAGING,
            Deduction.DUTY,
            Deduction.OTHER_DIRECT)) {
      var component = byCode.get(code.name());
      lines.add(costLine(code, component, request, detail, sameCurrency, fxRate));
    }
    lines.add(platformFeeLine(request, output));
    lines.add(logisticsLine(request, tierSpecs, output, tierComplete));
    lines.add(grossProfitLine(request, output));

    // ---- 售价减已知扣减后还剩多少（**不是毛利**）----
    BigDecimal revenueLeft = null;
    if (output.revenue() != null) {
      BigDecimal deducted = output.knownCostTotal();
      if (output.platformFeeAmount() != null) deducted = deducted.add(output.platformFeeAmount());
      if (output.logisticsAmount() != null) deducted = deducted.add(output.logisticsAmount());
      revenueLeft = ProfitCalculatorRules.money(output.revenue().subtract(deducted));
    }

    var bands =
        ProfitCalculatorRules.bands(tierSpecs, request.quantity(), request.logisticsCostPerUnit());

    return new ProfitCalculation(
        request.skuId(),
        detail.skuCode(),
        detail.skuName(),
        request.platformCode(),
        request.siteCode(),
        request.currency(),
        request.costCurrency(),
        request.mode(),
        request.quantity(),
        basis,
        output.complete(),
        gaps,
        fx,
        lines,
        output.revenue(),
        output.knownCostTotal(),
        revenueLeft,
        output.grossProfit(),
        output.grossMarginRate(),
        output.breakEvenPrice(),
        output.targetMarginPrice(),
        output.breakEvenVolume(),
        output.unitContribution(),
        bands,
        detail.costMethodLabel(),
        detail.effectiveFrom(),
        output.complete()
            ? null
            : "🔴 成本不完整，毛利率不可信：缺项未计入时算出来的毛利率是虚高的，"
                + "而下游 5.3.2.1 模板与 5.4 调价会拿它直接改价。"
                + "本模块因此**不输出**毛利率数字，只给「剩余可覆盖缺失扣减的金额」。",
        output.breakEvenPrice() == null
            ? "成本不完整 ⇒ 保本价算不出来（它依赖每一项扣减，尤其是平台费率）。"
            : "保本价 = 毛利恰好为 0 的售价：("
                + money(output.platformFeeAmount())
                + " 固定费 + "
                + money(output.logisticsAmount())
                + " 物流 + "
                + money(output.knownCostTotal())
                + " 已知成本) ÷ (1 − 平台费率)。",
        output.targetMarginPrice() == null
            ? (request.targetMarginRate() == null
                ? "没有给目标毛利率 ⇒ 不输出目标毛利价（PRICE 模式下目标毛利率是可选输入）。"
                : "成本不完整 ⇒ 目标毛利价算不出来。")
            : "目标毛利价 = 毛利率恰好为目标值的售价，与保本价走**同一个方程**（只换右端的毛利率）。",
        output.breakEvenVolume() == null
            ? (request.monthlyFixedCost() == null
                ? "没有给月度固定成本 ⇒ 不输出盈亏平衡销量（固定费用基数是它的分母）。"
                : "单位贡献毛利不为正 ⇒ 再多也赚不回来，没有盈亏平衡点。")
            : "盈亏平衡销量 = 月度固定成本 ÷ 单位贡献毛利（按物流阶梯分段精确求解，不是简单相除）。",
        decisionNote(basis, gaps, manualComponents));
  }

  // ================================================================ 内部：瀑布图的行

  private WaterfallLine revenueLine(CalculateRequest request, ProfitCalculatorRules.Output output) {
    var source =
        new DeductionSource(
            "—",
            "catalog",
            "—",
            "INPUT",
            "售价由人在本次测算里给出（PRICE 模式），或由目标毛利率反解（TARGET_MARGIN 模式）。",
            null,
            "货币："
                + request.currency()
                + "；数量："
                + request.quantity().toPlainString()
                + "（用于阶梯与运费）。",
            List.of(
                new SourceDetail("售价币种", request.currency()),
                new SourceDetail("数量", request.quantity().toPlainString()),
                new SourceDetail(
                    "口径",
                    "毛利率 = 毛利额 ÷ 售价；金额 4 位小数、费率与毛利率 6 位小数，统一 HALF_UP")));
    return new WaterfallLine(
        "REVENUE",
        "售价",
        false,
        output.revenue() != null,
        false,
        "INPUT",
        "—",
        "本次测算的售价（输入或反解）。",
        output.revenue() == null
            ? "没有售价：按目标毛利率倒推时需要每一项扣减都取得到，否则解出来的售价是错的。"
            : null,
        output.revenue(),
        output.revenue() == null
            ? null
            : ProfitCalculatorRules.money(output.revenue().multiply(request.quantity())),
        request.currency(),
        source);
  }

  private WaterfallLine grossProfitLine(CalculateRequest request, ProfitCalculatorRules.Output output) {
    var source =
        new DeductionSource(
            "—",
            "catalog",
            "—",
            "DERIVED",
            "毛利额 = 售价 − 平台费用 − 物流成本 − 采购成本 − 头程 − 包装 − 关税 − 其他直接成本。",
            null,
            output.complete()
                ? "每一项扣减都取到了 ⇒ 这是可用的毛利率。"
                : "🔴 成本不完整 ⇒ 这里**不给数字**。给一个虚高的毛利率比不给更危险。",
            List.of(
                new SourceDetail(
                    "扣减合计",
                    output.totalDeductions() == null ? "取不到" : output.totalDeductions().toPlainString()),
                new SourceDetail(
                    "毛利率",
                    output.grossMarginRate() == null
                        ? "不可信（成本不完整）"
                        : ProfitCalculatorRules.percent(output.grossMarginRate()))));
    return new WaterfallLine(
        "GROSS_PROFIT",
        "毛利",
        false,
        output.complete(),
        false,
        "DERIVED",
        "—",
        "售价减掉全部扣减之后的毛利额与毛利率。",
        output.complete() ? null : "成本不完整，毛利率不可信：缺项未计入时算出来的毛利率是虚高的。",
        output.grossProfit(),
        output.grossProfit() == null
            ? null
            : ProfitCalculatorRules.money(output.grossProfit().multiply(request.quantity())),
        request.currency(),
        source);
  }

  private WaterfallLine platformFeeLine(CalculateRequest request, ProfitCalculatorRules.Output output) {
    var details = new ArrayList<SourceDetail>();
    details.add(new SourceDetail("费率口径", "售价的小数比例（0.15 = 15%）"));
    details.add(
        new SourceDetail(
            "固定费",
            request.platformFeeFixed() == null
                ? "未提供 ⇒ 按 0 计（没有订阅费分摊的口径）"
                : request.platformFeeFixed().toPlainString()));
    var source =
        new DeductionSource(
            "5.2.3.1 平台费用",
            "catalog",
            "—（5.2.3.1 的规则表未交付，本模块不建它的副本）",
            "MANUAL_ASSUMPTION",
            "平台费 = 售价 × 费率 + 每单固定费。规则口径是「按平台/站点/类目取佣金比例或阶梯 + 订阅费分摊」。",
            null,
            "🔴 5.2.3.1 未交付 ⇒ 这个费率是**手工假设**，将来由 5.2.3.1 的规则接管。"
                + "该站点的增值税是否需要单独扣一行，也取决于 5.2.3.1 的规则口径。",
            details);
    boolean known = output.platformFeeAmount() != null;
    return new WaterfallLine(
        "PLATFORM_FEE",
        "平台费用",
        true,
        known,
        false,
        "MANUAL_ASSUMPTION",
        "5.2.3.1 平台费用",
        "售价 × 费率 + 固定费（固定费未提供时按 0 计）。",
        known ? null : "未交付：该扣减未计入（5.2.3.1 平台费用规则未交付）",
        output.platformFeeAmount(),
        output.platformFeeAmount() == null
            ? null
            : ProfitCalculatorRules.money(output.platformFeeAmount().multiply(request.quantity())),
        request.currency(),
        source);
  }

  private WaterfallLine logisticsLine(
      CalculateRequest request,
      List<ProfitCalculatorRules.TierSpec> tiers,
      ProfitCalculatorRules.Output output,
      boolean tierComplete) {
    var details = new ArrayList<SourceDetail>();
    if (tiers.isEmpty()) {
      details.add(
          new SourceDetail(
              "每单位运费",
              request.logisticsCostPerUnit() == null
                  ? "未提供"
                  : request.logisticsCostPerUnit().toPlainString()));
    } else {
      for (var tier : tiers) {
        details.add(
            new SourceDetail(
                tier.upToQuantity() == null
                    ? "超出上一段之后"
                    : "第 1 ~ " + tier.upToQuantity().toPlainString() + " 单",
                tier.perUnitCost().toPlainString() + " / 单位"));
      }
    }
    details.add(new SourceDetail("测算数量", request.quantity().toPlainString()));
    var source =
        new DeductionSource(
            "5.2.3.2 物流与头程费用",
            "catalog",
            "—（5.2.3.2 的计费规则表未开发，本模块不建它的副本）",
            "MANUAL_ASSUMPTION",
            "物流成本按数量阶梯计算：每段每单位运费 × 该段单量，按测算数量加权平均成每单位运费。",
            null,
            "🔴 5.2.3.2 未开发 ⇒ 这个运费是**手工假设**，将来由 5.2.3.2 的渠道计费规则接管"
                + "（按重量/体积/件数 + 燃油/旺季/偏远附加）。",
            details);
    boolean known = output.logisticsAmount() != null;
    return new WaterfallLine(
        "LOGISTICS",
        "物流成本",
        true,
        known,
        known && !tierComplete,
        "MANUAL_ASSUMPTION",
        "5.2.3.2 物流与头程费用",
        tiers.isEmpty()
            ? "每单位运费（手工假设）。"
            : "按数量阶梯加权平均的每单位运费（手工假设）。",
        known
            ? (tierComplete ? null : "阶梯没有覆盖到测算数量，超出部分没有运费定义。")
            : "未交付：该扣减未计入（5.2.3.2 物流与头程费用规则未开发）",
        output.logisticsAmount(),
        output.logisticsAmount() == null
            ? null
            : ProfitCalculatorRules.money(output.logisticsAmount().multiply(request.quantity())),
        request.currency(),
        source);
  }

  private WaterfallLine costLine(
      Deduction code,
      CostComponentRow component,
      CalculateRequest request,
      CostDetail detail,
      boolean sameCurrency,
      BigDecimal fxRate) {
    var details = new ArrayList<SourceDetail>();
    details.add(
        new SourceDetail(
            "成本法",
            (detail.costMethodLabel() == null ? "—" : detail.costMethodLabel())
                + "（"
                + (detail.costMethod() == null ? "—" : detail.costMethod())
                + "）"));
    details.add(
        new SourceDetail(
            "成本口径", detail.costMethodBasis() == null ? "—" : detail.costMethodBasis()));
    details.add(
        new SourceDetail(
            "生效时间", detail.effectiveFrom() == null ? "—" : detail.effectiveFrom().toString()));
    details.add(new SourceDetail("成本窗口", detail.windowFrom() + " ~ " + detail.windowTo()));
    if (!sameCurrency && fxRate != null) {
      details.add(
          new SourceDetail(
              "币种换算",
              "1 "
                  + request.costCurrency()
                  + " = "
                  + fxRate.toPlainString()
                  + " "
                  + request.currency()
                  + "（手工假设；5.2.2.1 未交付）"));
    }
    if (component != null) {
      details.add(new SourceDetail("来源码", component.source()));
      if (component.note() != null && !component.note().isBlank()) {
        details.add(new SourceDetail("口径备注", component.note()));
      }
    }
    var source =
        new DeductionSource(
            "5.2.1.1 成本构成",
            "catalog",
            tableOf(component),
            component == null ? "DERIVED" : component.kind(),
            component == null ? "—" : (component.basis() == null ? "—" : component.basis()),
            detail.effectiveFrom() == null ? null : detail.effectiveFrom().toString(),
            "同一份成本构成（5.2.1.1）在 /pricing/cost 页面上可逐项展开查看来源；"
                + "本行用的是**同一口径**，不存在两套算法。",
            details);
    BigDecimal amount = null;
    if (component != null && component.available() && component.amount() != null) {
      amount = component.amount();
      if (!sameCurrency && fxRate != null) {
        amount = ProfitCalculatorRules.money(amount.multiply(fxRate));
      }
    }
    return new WaterfallLine(
        code.name(),
        code.label(),
        true,
        component != null && component.available(),
        component != null && component.partial(),
        component == null ? "DERIVED" : component.kind(),
        "5.2.1.1 成本构成",
        component == null ? "—" : (component.basis() == null ? "—" : component.basis()),
        component == null
            ? "成本构成里没有这一项。"
            : (component.unavailableReason() == null ? null : component.unavailableReason()),
        amount,
        amount == null ? null : ProfitCalculatorRules.money(amount.multiply(request.quantity())),
        request.currency(),
        source);
  }

  /** 来源码 → 真实表名（蓝图要求「哪张表」可展开看到）。 */
  private static String tableOf(CostComponentRow component) {
    if (component == null) return "—";
    return switch (component.source()) {
      case "CATALOG_VENDOR_DELIVERY" -> "vendor_delivery_record（V137）· sku_cost_revision（V215）";
      case "WAREHOUSE_FIRST_MILE" -> "warehouse: first_mile_shipment / first_mile_shipment_line";
      case "WAREHOUSE_DUTY_TAX" -> "warehouse: duty_tax_estimate / duty_tax_estimate_line";
      case "CATALOG_PARAMETER" -> "sku_cost_profile（V215）";
      case "CATALOG_ADJUSTMENT" -> "sku_cost_adjustment（V215）";
      default -> "—";
    };
  }

  // ================================================================ 内部：杂项

  private String decisionNote(DecisionBasis basis, List<BlockingGap> gaps, List<String> manual) {
    if (basis == DecisionBasis.INCOMPLETE) {
      return "🔴 **成本不完整，毛利率不可信**：还有 "
          + gaps.stream().filter(g -> !g.manualAssumptionAccepted()).count()
          + " 项扣减取不到（"
          + gaps.stream()
              .filter(g -> !g.manualAssumptionAccepted())
              .map(BlockingGap::label)
              .toList()
          + "）。本模块已**不输出**毛利率数字 —— 缺项算出来的毛利率是虚高的，"
          + "而下游 5.3.2.1 模板与 5.4 调价会拿它直接改价。补齐办法见每一项的「归谁管」。";
    }
    if (basis == DecisionBasis.COMPLETE_FROM_MANUAL_ASSUMPTIONS) {
      return "⚠️ 每一项扣减都有值，但其中 "
          + manual
          + " 来自**手工假设**（上游规则模块未交付）。"
          + "这个结果可用于 what-if 讨论，**不等于规则口径**；"
          + "5.2.3.1 / 5.2.3.2 / 5.2.2.1 交付后会自动换成系统取数。";
    }
    return "✅ 每一项扣减都来自上游规则与事实，结果可直接用于定价决策。";
  }

  private record SkuRow(String code, String name) {}

  private SkuRow skuInfo(UUID skuId) {
    var row =
        sql.fetchOne(
            "select code, name from sku where tenant_id=" + TENANT + " and id=?", skuId);
    return row == null ? null : new SkuRow(row.get("code", String.class), row.get("name", String.class));
  }

  private static DecisionBasis decisionBasisOf(String raw) {
    if (raw == null) return null;
    for (var basis : DecisionBasis.values()) {
      if (basis.name().equals(raw)) return basis;
    }
    return null;
  }

  private static String upper(String value) {
    if (value == null || value.isBlank()) return null;
    return value.trim().toUpperCase(Locale.ROOT);
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static String money(BigDecimal value) {
    return value == null ? "—" : value.toPlainString();
  }

  static ResponseStatusException badRequest(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }

  static ResponseStatusException notFound(String reason) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, reason);
  }
}
