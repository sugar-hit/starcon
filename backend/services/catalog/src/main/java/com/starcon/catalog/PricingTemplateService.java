package com.starcon.catalog;

import static com.starcon.catalog.PricingTemplateModels.*;

import com.starcon.catalog.PricingTemplateModels.TemplateAdjustmentInput;
import com.starcon.catalog.PricingTemplateModels.AdjustmentType;
import com.starcon.catalog.PricingTemplateModels.AdjustmentView;
import com.starcon.catalog.PricingTemplateModels.AppliedAdjustment;
import com.starcon.catalog.PricingTemplateModels.ApplyRequest;
import com.starcon.catalog.PricingTemplateModels.ApplyResult;
import com.starcon.catalog.PricingTemplateModels.ApplicationPage;
import com.starcon.catalog.PricingTemplateModels.ApplicationSummary;
import com.starcon.catalog.PricingTemplateModels.BaseMethod;
import com.starcon.catalog.PricingTemplateModels.BrandOption;
import com.starcon.catalog.PricingTemplateModels.CapabilityItem;
import com.starcon.catalog.PricingTemplateModels.CategoryOption;
import com.starcon.catalog.PricingTemplateModels.PricingTemplateCapabilities;
import com.starcon.catalog.PricingTemplateModels.ProductStage;
import com.starcon.catalog.PricingTemplateModels.ScopeInput;
import com.starcon.catalog.PricingTemplateModels.ScopeType;
import com.starcon.catalog.PricingTemplateModels.ScopeView;
import com.starcon.catalog.PricingTemplateModels.TemplateDetail;
import com.starcon.catalog.PricingTemplateModels.TemplateDirectory;
import com.starcon.catalog.PricingTemplateModels.TemplateInput;
import com.starcon.catalog.PricingTemplateModels.TemplatePage;
import com.starcon.catalog.PricingTemplateModels.TemplateSummary;
import com.starcon.catalog.ProfitCalculatorModels.BlockingGap;
import com.starcon.catalog.ProfitCalculatorModels.CalcMode;
import com.starcon.catalog.ProfitCalculatorModels.CalculateRequest;
import com.starcon.catalog.ProfitCalculatorModels.ProfitCalculation;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.3.2.1 定价模板：把定价策略沉淀成模板，并「一键应用到商品生成建议价」。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：沉淀定价策略模板：成本加成、固定毛利、竞品跟随（若允许）、按站点差异化、按库存周转调整，新品与老品用不同模板。
 *
 * **输入**：模板定义（基准 + 调整项）；适用对象（分类/品牌/店铺）。
 *
 * **输出**：模板记录；一键应用到商品生成建议价。
 *
 * **关系**：上游 = 5.3.1.1；下游 = 5.4.1.1。**模板与价目表的关系**：模板是"怎么算"，价目表是"算完是多少"。
 *
 * </blockquote>
 *
 * <h2>🔴 本类的六条纪律</h2>
 *
 * <ol>
 *   <li><b>🔴 建议价必须来自 5.3.1.1 的真实测算</b>。本类**不重写**任何一套扣减公式：
 *       固定毛利走 {@link ProfitCalculatorService} 的目标毛利求解；
 *       成本加成的基数取 5.3.1.1 瀑布图里的单位成本合计（5 项成本分项 + 物流每单位）；
 *       竞品跟随以人工登记的竞品价为起点，再由 5.3.1.1 复核毛利。
 *       <b>「编一个建议价」在本类里没有位置。</b>
 *   <li><b>🔴 成本不完整 ⇒ 拒绝给建议价</b>（不是给一个虚高的价）。5.3.1.1 在扣减不全时
 *       把毛利率 / 目标毛利价置 null，本类照抄这条纪律并把它变成 <b>409</b>：
 *       缺口清单原样带回，让页面知道缺哪几项、归谁管。
 *   <li><b>🔴 模板是「怎么算」，价目表是「算完是多少」</b>。本类只落模板与一次应用留痕，
 *       <b>不建价目表</b>（5.1.1.2 的写入所有权），<b>不改价</b>（5.4.1.1 的写入所有权）。
 *       产出的永远是<b>建议价</b>。
 *   <li><b>🔴 新品与老品用不同模板</b>：模板带商品阶段（NEW / EXISTING），
 *       商品阶段由 <b>SPU 生命周期状态机</b>（3.1.1.3）判定（未上架 = 新品）。
 *       阶段不符 ⇒ 409，不静默套用。
 *   <li><b>🔴 竞品跟随（若允许）的「若允许」</b>：本仓没有竞品价模块 ⇒ 竞品价只能人工登记，
 *       登记的值在响应里标成手工假设。本类<b>不建</b>任何竞品价表来假装上游已交付。
 *   <li><b>租户只来自已认证成员关系</b>（{@link AccessService}），不读 {@code X-Tenant-Id} 头。
 *       每条 SQL 的 where 都带显式 {@code tenant_id=} 绑定，RLS 再兜一道。
 * </ol>
 */
@Service
public class PricingTemplateService {

  /** 租户上下文取法：RLS 已按它隔离，这里带上只是让执行计划走租户前缀的索引。 */
  private static final String TENANT = "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 台账页大小上限：与相邻模块一致。 */
  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;
  private final ProfitCalculatorService calculator;
  private final ChannelPlatformPort platforms;

  public PricingTemplateService(
      DSLContext sql, ProfitCalculatorService calculator, ChannelPlatformPort platforms) {
    this.sql = sql;
    this.calculator = calculator;
    this.platforms = platforms;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：基准方法 / 调整项 / 适用对象 / 商品阶段四张词表 + 七条口径说明。
   *
   * <p>页面固定显示它 —— 蓝图那句「模板是"怎么算"，价目表是"算完是多少"」必须写在脸上，
   * 否则运营会把「建议价」当成「已经改好的价」。
   */
  public PricingTemplateCapabilities capabilities() {
    var baseMethods =
        List.of(
            new CapabilityItem(
                "COST_PLUS",
                "成本加成",
                "建议价 = 单位成本合计 × (1 + 加成率)。加成基数取 5.3.1.1 瀑布图里的单位成本合计"
                    + "（采购 + 头程 + 包装 + 关税 + 其他直接成本 + 物流每单位）；"
                    + "平台费是售价的比例，**不进加成基数**（它在建议价算出来的毛利里）。",
                List.of("baseRate（加成率，小数口径）")),
            new CapabilityItem(
                "FIXED_MARGIN",
                "固定毛利",
                "建议价 = 5.3.1.1 按目标毛利率解出来的目标毛利价（同一个方程解一次，"
                    + "本模块不重写公式）。目标毛利率 ≥ 100% 时方程无解 ⇒ 拒绝给价。",
                List.of("baseRate（目标毛利率，小数口径）")),
            new CapabilityItem(
                "COMPETITOR_FOLLOW",
                "竞品跟随",
                "建议价 = 竞品基准价 × (1 + 跟随率)。🔴 竞品基准价是**人工登记**的值："
                    + "本仓没有竞品价模块，系统不会凭空生成一个竞品价。",
                List.of(
                    "baseRate（跟随率，可为负 = 比竞品便宜）",
                    "competitorReferencePrice",
                    "competitorCurrency")));
    var adjustmentTypes =
        List.of(
            new CapabilityItem(
                "SITE_DIFFERENTIAL",
                "按站点差异化",
                "给某个站点的基准率加一个增量（+0.02 = 该站点比基准紧/松 2 个百分点）。"
                    + "加在**基准率**上而不是乘在价格上：三个基准方法的基准率含义不同，"
                    + "只有加法能让「同一套站点规则」在三种基准下保持同一语义。",
                List.of("siteCode", "deltaRate")),
            new CapabilityItem(
                "INVENTORY_TURNOVER",
                "按库存周转调整",
                "当前可售天数超过阈值 ⇒ 判定滞销 ⇒ 建议价打折扣率（乘在建议价上）。"
                    + "🔴 可售天数依赖 7 库存与 09 订单，这两个上游没有给本服务可读端点 ⇒ "
                    + "由人填当前可售天数（显式的手工假设）；没填时**拒绝**应用，不按「不打折」糊过去。",
                List.of("daysCoverThreshold", "discountRate")));
    var scopeTypes =
        List.of(
            new CapabilityItem(
                "CATEGORY", "分类", "内部分类码（product_category.code）。", List.of("ref")),
            new CapabilityItem(
                "BRAND", "品牌", "品牌码（brand.code，SPU 的品牌字段存的就是品牌编码）。",
                List.of("ref")),
            new CapabilityItem(
                "SHOP",
                "店铺",
                "站点码（2.1.1.1 平台目录里的 marketplace code），可带平台码。",
                List.of("ref", "platformCode（可空）")));
    var stages =
        List.of(
            new CapabilityItem(
                "NEW",
                "新品",
                "所属 SPU 尚未上架（DRAFT / PENDING_LISTING）。判定依据是 3.1.1.3 的 SPU 生命周期状态机。",
                List.of()),
            new CapabilityItem(
                "EXISTING",
                "老品",
                "所属 SPU 已上架 / 暂停 / 停产（ON_SALE / SUSPENDED / DISCONTINUED）。",
                List.of()));
    return new PricingTemplateCapabilities(
        baseMethods,
        adjustmentTypes,
        scopeTypes,
        stages,
        "上游 = 5.3.1.1 利润测算器（catalog 服务内）：**建议价的每一个数字都来自它**，"
            + "本模块不重写任何一套扣减公式。5.3.1.1 的决策就绪闸（扣减不全 ⇒ 不给毛利率）"
            + "在本模块原样延续：成本不完整时**拒绝**产出建议价（409），并把缺口清单带回。",
        "下游 = 5.4.1.1 调价单：本模块产出的是**建议价**，不是生效价。"
            + "5.4.1.1 要先看 decisionReady —— false 时建议价没有可用的毛利率，"
            + "拿它批量改价会同时改错价与错利润。",
        "🔴 **模板是「怎么算」，价目表是「算完是多少」**（蓝图加粗句）。"
            + "本模块只落模板与一次应用留痕，**不建价目表**（5.1.1.2 的写入所有权），"
            + "**不改价**（5.4.1.1 的写入所有权）。",
        "竞品跟随的「若允许」：本仓**没有**竞品价模块（2.x 卷里没有采集竞品价的模块）⇒ "
            + "竞品基准价由人登记，是**手工假设**。本模块不建任何竞品价表来假装上游已交付。",
        "库存周转调整的输入（当前可售天数）依赖 7 库存与 09 订单，"
            + "这两个上游没有给本服务可读端点 ⇒ 由人填（显式的手工假设，ownerModule = 7 库存 / 09 订单）。"
            + "模板配了这条规则而没填可售天数 ⇒ **拒绝**应用（不按「不打折」糊过去）。",
        "模板上的平台费率 / 平台固定费 / 物流单价 / 汇率是**手工假设**（5.3.1.1 的同一套机制）："
            + "5.2.3.1 平台费用与 5.2.3.2 物流费用尚未接入 5.3.1.1 的测算路径 ⇒ "
            + "填了才有一个可比的建议价。留空 = 「取不到」，**不是 0**。",
        "新品与老品的判定依据是 **3.1.1.3 的 SPU 生命周期状态机**（未上架 = 新品）："
            + "全仓没有「首次上架时间」这个事实，编一个出来就是假数据。"
            + "模板的商品阶段与商品阶段不符 ⇒ 409 拒绝，不静默套用。",
        "「一键应用」= 用模板算一次并产出**建议价 + 留痕**，不是改价。"
            + "改价走 5.4.1.1 调价单（含审批与平台回写）。",
        "金额全程 BigDecimal：金额 4 位小数、费率 6 位小数，统一 HALF_UP；"
            + "跨币种必须要汇率（方向显式：1 成本币种 = X 售价币种），**绝不按 1:1 折算**。");
  }

  // ================================================================ 目录

  /** 适用对象目录：内部分类 + 品牌（店铺见 {@link #sites}）。 */
  public TemplateDirectory directory() {
    var categories = new ArrayList<CategoryOption>();
    for (var row :
        sql.fetch(
            "select c.code, c.name, p.code as parent_code"
                + " from product_category c"
                + " join product_category_tree t on t.tenant_id=c.tenant_id and t.id=c.tree_id"
                + " left join product_category p on p.tenant_id=c.tenant_id and p.id=c.parent_id"
                + " where c.tenant_id="
                + TENANT
                + " and t.tree_kind='INTERNAL'"
                + " order by c.code")) {
      categories.add(
          new CategoryOption(row.get("code", String.class), row.get("name", String.class),
              row.get("parent_code", String.class)));
    }
    var brands = new ArrayList<BrandOption>();
    for (var row :
        sql.fetch(
            "select code, name, authorization_status from brand where tenant_id="
                + TENANT
                + " order by code")) {
      brands.add(
          new BrandOption(
              row.get("code", String.class),
              row.get("name", String.class),
              row.get("authorization_status", String.class)));
    }
    return new TemplateDirectory(
        categories,
        brands,
        "分类与品牌来自 catalog 本地事实（3.3.1.1 分类树 / 3.1.4.1 品牌）；"
            + "店铺（目标站点）来自 2.1.1.1 平台目录（channel 服务，跨服务只读）—— 走 /sites 端点。");
  }

  /** 目标店铺（站点）列表：来自 2.1.1.1 平台目录。上游不可达 ⇒ 503，不返回空列表。 */
  public List<ProfitCalculatorModels.SiteOption> sites(String bearer) {
    List<ProfitCalculatorModels.SiteOption> out = new ArrayList<>();
    for (var site : platforms.sites(bearer)) {
      out.add(
          new ProfitCalculatorModels.SiteOption(
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

  // ================================================================ 模板 CRUD

  /** 模板列表（真分页 + 筛选）。 */
  public TemplatePage list(TemplateQuery query) {
    int limited = Math.min(Math.max(query.size() <= 0 ? 20 : query.size(), 1), MAX_PAGE_SIZE);
    int offset = Math.max(query.page(), 0) * limited;
    var where = new StringBuilder(" where t.tenant_id=" + TENANT);
    var params = new ArrayList<Object>();
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.append(" and (t.code ilike ? or t.name ilike ?)");
      var like = "%" + query.keyword().trim() + "%";
      params.add(like);
      params.add(like);
    }
    if (query.productStage() != null) {
      where.append(" and t.product_stage=?");
      params.add(query.productStage().name());
    }
    if (query.baseMethod() != null) {
      where.append(" and t.base_method=?");
      params.add(query.baseMethod().name());
    }
    if (query.enabled() != null) {
      where.append(" and t.enabled=?");
      params.add(query.enabled());
    }
    if (query.scopeType() != null && query.scopeRef() != null && !query.scopeRef().isBlank()) {
      where.append(
          " and exists (select 1 from pricing_template_scope s where s.tenant_id=t.tenant_id"
              + " and s.template_id=t.id and s.scope_type=? and s.scope_ref=?)");
      params.add(query.scopeType().name());
      params.add(query.scopeRef().trim());
    }
    var rows =
        sql.fetch(
            "select t.id, t.code, t.name, t.base_method, t.base_rate, t.product_stage, t.currency,"
                + " t.competitor_reference_price, t.enabled, t.effective_from, t.effective_to,"
                + " t.created_by, t.updated_at,"
                + " (select count(*) from pricing_template_adjustment a"
                + "   where a.tenant_id=t.tenant_id and a.template_id=t.id) as adjustment_count,"
                + " (select count(*) from pricing_template_scope s"
                + "   where s.tenant_id=t.tenant_id and s.template_id=t.id) as scope_count"
                + " from pricing_template t"
                + where
                + " order by t.updated_at desc, t.id"
                + " limit ? offset ?",
            concat(params, limited, offset));
    long total =
        sql.fetchOne("select count(*) from pricing_template t" + where, params.toArray())
            .get(0, Long.class);
    var items = new ArrayList<TemplateSummary>();
    for (var row : rows) {
      items.add(summaryOf(row));
    }
    return new TemplatePage(items, Math.max(query.page(), 0), limited, total);
  }

  /** 模板详情 = 模板本身 + 调整项 + 适用对象。 */
  public TemplateDetail detail(UUID id) {
    var row = templateRow(id);
    if (row == null) throw notFound("定价模板不存在");
    return detailOf(row);
  }

  /** 新建 / 修改模板（修改走乐观锁）。调整项与适用对象整体替换。 */
  public TemplateDetail save(AccessService.Access actor, UUID id, TemplateInput input) {
    var normalized = normalize(input);
    if (id == null) {
      var duplicate =
          sql.fetchOne(
              "select id from pricing_template where tenant_id=" + TENANT + " and code=?",
              normalized.code());
      if (duplicate != null) throw conflict("模板编码 " + normalized.code() + " 已存在");
      UUID newId = UUID.randomUUID();
      sql.execute(
          "insert into pricing_template(id,tenant_id,code,name,description,base_method,base_rate,"
              + "competitor_reference_price,competitor_currency,product_stage,currency,"
              + "platform_fee_rate,platform_fee_fixed,logistics_cost_per_unit,cost_currency,fx_rate,"
              + "enabled,effective_from,effective_to,note,created_by,created_at,updated_at)"
              + " values(?::uuid,?::uuid,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?::uuid,now(),now())",
          newId,
          actor.tenantId(),
          normalized.code(),
          normalized.name(),
          normalized.description(),
          normalized.baseMethod().name(),
          normalized.baseRate(),
          normalized.competitorReferencePrice(),
          normalized.competitorCurrency(),
          normalized.productStage().name(),
          normalized.currency(),
          normalized.platformFeeRate(),
          normalized.platformFeeFixed(),
          normalized.logisticsCostPerUnit(),
          normalized.costCurrency(),
          normalized.fxRate(),
          normalized.enabled(),
          normalized.effectiveFrom(),
          normalized.effectiveTo(),
          normalized.note(),
          actor.userId());
      replaceChildren(newId, actor, normalized);
      return detail(newId);
    }
    var current =
        sql.fetchOne(
            "select version from pricing_template where tenant_id=" + TENANT + " and id=? for update",
            id);
    if (current == null) throw notFound("定价模板不存在");
    long currentVersion = current.get(0, Long.class);
    if (input.version() == null || input.version() != currentVersion) {
      throw conflict(
          "这条模板已经被别人改过了（当前版本 "
              + currentVersion
              + "，你提交的是 "
              + input.version()
              + "）。请刷新后重新提交 —— 覆盖别人的改动会让定价策略对不上账。");
    }
    var duplicate =
        sql.fetchOne(
            "select id from pricing_template where tenant_id=" + TENANT + " and code=? and id<>?",
            normalized.code(),
            id);
    if (duplicate != null) throw conflict("模板编码 " + normalized.code() + " 已存在");
    sql.execute(
        "update pricing_template set code=?, name=?, description=?, base_method=?, base_rate=?,"
            + " competitor_reference_price=?, competitor_currency=?, product_stage=?, currency=?,"
            + " platform_fee_rate=?, platform_fee_fixed=?, logistics_cost_per_unit=?, cost_currency=?,"
            + " fx_rate=?, enabled=?, effective_from=?::timestamptz, effective_to=?::timestamptz,"
            + " note=?, version=version+1, updated_at=now()"
            + " where tenant_id="
            + TENANT
            + " and id=?",
        normalized.code(),
        normalized.name(),
        normalized.description(),
        normalized.baseMethod().name(),
        normalized.baseRate(),
        normalized.competitorReferencePrice(),
        normalized.competitorCurrency(),
        normalized.productStage().name(),
        normalized.currency(),
        normalized.platformFeeRate(),
        normalized.platformFeeFixed(),
        normalized.logisticsCostPerUnit(),
        normalized.costCurrency(),
        normalized.fxRate(),
        normalized.enabled(),
        normalized.effectiveFrom(),
        normalized.effectiveTo(),
        normalized.note(),
        id);
    replaceChildren(id, actor, normalized);
    return detail(id);
  }

  /** 删除模板（连同它的调整项、适用对象）。 */
  public void delete(UUID id) {
    int removed =
        sql.execute("delete from pricing_template where tenant_id=" + TENANT + " and id=?", id);
    if (removed == 0) throw notFound("定价模板不存在");
    sql.execute("delete from pricing_template_adjustment where tenant_id=" + TENANT + " and template_id=?", id);
    sql.execute("delete from pricing_template_scope where tenant_id=" + TENANT + " and template_id=?", id);
  }

  // ================================================================ 一键应用（建议价）

  /**
   * 一键应用到商品，生成**建议价**。
   *
   * <p>🔴 这不是改价：改价是 5.4.1.1 调价单的写入所有权。本方法只做「用模板算一次 + 留痕」。
   *
   * <p>🔴 建议价的每一个数字都来自 5.3.1.1（{@link ProfitCalculatorService}）：
   * 固定毛利走它的目标毛利求解；成本加成的基数取它瀑布图里的单位成本合计；
   * 竞品跟随以人工登记的竞品价为起点，再由它复核毛利。
   *
   * <p>🔴 成本不完整 ⇒ <b>409</b>，不给价（纪律二）。
   */
  public ApplyResult apply(
      AccessService.Access actor, UUID templateId, ApplyRequest request, String bearer) {
    var row = templateRow(templateId);
    if (row == null) throw notFound("定价模板不存在");
    var template = detailOf(row);
    var adjustments = template.adjustments();

    // ---- 归一化：请求覆盖模板，模板兜底 ----
    var currency = upper(request.currency());
    if (currency == null) currency = template.currency();
    var quantity = request.quantity() == null ? BigDecimal.ONE : request.quantity();
    var competitorPrice =
        request.competitorReferencePrice() == null
            ? template.competitorReferencePrice()
            : request.competitorReferencePrice();
    var competitorCurrency =
        upper(request.competitorCurrency()) == null
            ? template.competitorCurrency()
            : upper(request.competitorCurrency());
    var platformFeeRate =
        request.platformFeeRate() == null ? template.platformFeeRate() : request.platformFeeRate();
    var platformFeeFixed =
        request.platformFeeFixed() == null ? template.platformFeeFixed() : request.platformFeeFixed();
    var logisticsPerUnit =
        request.logisticsCostPerUnit() == null
            ? template.logisticsCostPerUnit()
            : request.logisticsCostPerUnit();
    var costCurrency =
        upper(request.costCurrency()) == null ? template.costCurrency() : upper(request.costCurrency());
    if (costCurrency == null) costCurrency = currency;
    var fxRate = request.fxRate() == null ? template.fxRate() : request.fxRate();
    if (!currency.equals(costCurrency) && (fxRate == null || fxRate.signum() <= 0)) {
      throw badRequest(
          "售价币种（"
              + currency
              + "）与成本币种（"
              + costCurrency
              + "）不同，必须给汇率：1 "
              + costCurrency
              + " = X "
              + currency
              + "。汇率规则（5.2.2.1 / 5.2.2.2）未交付 ⇒ 这里只能由人作为手工假设填入，"
              + "绝不按 1:1 折算。");
    }

    // ---- 商品事实（SKU / 阶段 / 分类 / 品牌）----
    var product = productFacts(request.skuId());
    if (!template.productStage().equals(product.stage())) {
      throw conflict(
          "这条模板适用"
              + template.productStage().label()
              + "（"
              + template.productStage().name()
              + "），而商品 "
              + product.code()
              + " 是"
              + product.stage().label()
              + "（SPU 状态 "
              + product.spuStatus()
              + "）—— 新品与老品用不同模板，这里不静默套用。");
    }
    requireScopeMatch(template.scopes(), product, request);

    // ---- 基准与调整项 ----
    var specs = new ArrayList<PricingTemplateRules.AdjustmentSpec>();
    for (var adjustment : adjustments) {
      specs.add(
          new PricingTemplateRules.AdjustmentSpec(
              adjustment.type(),
              adjustment.siteCode(),
              adjustment.deltaRate(),
              adjustment.daysCoverThreshold(),
              adjustment.discountRate()));
    }
    var siteEffect = PricingTemplateRules.siteDifferential(specs, request.siteCode());
    var turnover = PricingTemplateRules.inventoryTurnover(specs, request.daysOfCover());
    if (hasTurnoverRule(specs) && request.daysOfCover() == null) {
      throw badRequest(
          "这条模板配了按库存周转调整的规则，但当前可售天数没有填："
              + "上游 7 库存 / 09 订单没有给本服务可读端点，可售天数只能由人填。"
              + "本模块不会默认「不打折」—— 那等于让模板的一条策略静默失效。");
    }
    var effectiveRate = PricingTemplateRules.effectiveRate(template.baseRate(), siteEffect.rateDelta());
    if (template.baseMethod() == BaseMethod.FIXED_MARGIN
        && (effectiveRate.signum() < 0 || effectiveRate.compareTo(BigDecimal.ONE) >= 0)) {
      throw badRequest(
          "生效后的目标毛利率是 "
              + PricingTemplateRules.percentText(effectiveRate)
              + "，必须在 0 与 100% 之间（基准率 "
              + PricingTemplateRules.percentText(template.baseRate())
              + " 加上站点增量 "
              + PricingTemplateRules.percentText(siteEffect.rateDelta())
              + "）。目标毛利率 ≥ 100% 时 5.3.1.1 的方程无解 ⇒ 不给价。");
    }
    if (template.baseMethod() == BaseMethod.COMPETITOR_FOLLOW
        && (competitorPrice == null || competitorPrice.signum() <= 0)) {
      throw badRequest(
          "竞品跟随时必须有竞品基准价：模板上没有登记、本次也没有传。"
              + "本仓没有竞品价模块，系统不会凭空生成一个竞品价 —— 请登记或临时传入。");
    }

    // ---- 探测调用：拿 5.3.1.1 的成本结构 ----
    BigDecimal competitorBased = null;
    if (template.baseMethod() == BaseMethod.COMPETITOR_FOLLOW) {
      competitorBased =
          PricingTemplateRules.competitorPrice(competitorPrice, effectiveRate);
      if (competitorBased == null) {
        throw badRequest(
            "按竞品价 "
                + competitorPrice.toPlainString()
                + " 与生效跟随率 "
                + PricingTemplateRules.percentText(effectiveRate)
                + " 算不出一个正的建议价 —— 请检查竞品价与跟随率。");
      }
    }
    var probeRequest =
        new CalculateRequest(
            request.skuId(),
            request.siteCode(),
            request.platformCode(),
            currency,
            costCurrency,
            template.baseMethod() == BaseMethod.COMPETITOR_FOLLOW ? CalcMode.PRICE : CalcMode.TARGET_MARGIN,
            competitorBased,
            // 目标毛利率只在固定毛利基准下有意义：成本加成用 0（探测成本结构），
            // 竞品跟随走按售价测算（**跟随率不是毛利率**，塞进去会被 5.3.1.1 拒掉）。
            template.baseMethod() == BaseMethod.FIXED_MARGIN
                ? effectiveRate
                : (template.baseMethod() == BaseMethod.COST_PLUS ? BigDecimal.ZERO : null),
            quantity,
            fxRate,
            platformFeeRate,
            platformFeeFixed,
            logisticsPerUnit,
            null,
            List.of(),
            null,
            null,
            "定价模板 " + template.summary().code() + " 的探测测算");
    var probe = calculator.calculate(probeRequest, bearer);

    // 🔴 纪律二：成本不完整 ⇒ 拒绝给价。
    if (!probe.decisionReady()) {
      throw conflict(
          "🔴 5.3.1.1 的测算不完整，所以本模块**不给建议价**（不是给一个虚高的价）："
              + gapSummary(probe.blockingGaps()));
    }

    // ---- 算建议价 ----
    BigDecimal suggested;
    if (template.baseMethod() == BaseMethod.FIXED_MARGIN) {
      suggested = probe.targetMarginPrice();
      if (suggested == null) {
        throw conflict(
            "🔴 5.3.1.1 没有解出目标毛利价（"
                + gapSummary(probe.blockingGaps())
                + "）⇒ 本模块不给建议价。");
      }
    } else if (template.baseMethod() == BaseMethod.COST_PLUS) {
      var costBase = unitCostBase(probe);
      if (costBase == null) {
        throw conflict(
            "🔴 加成基数（单位成本合计）取不到，所以本模块**不给建议价**："
                + gapSummary(probe.blockingGaps()));
      }
      suggested = PricingTemplateRules.costPlusPrice(costBase, effectiveRate);
      if (suggested == null) {
        throw badRequest(
            "按加成基数 "
                + costBase.toPlainString()
                + " 与生效加成率 "
                + PricingTemplateRules.percentText(effectiveRate)
                + " 算不出一个正的建议价 —— 请检查加成率。");
      }
    } else {
      suggested = competitorBased;
    }

    // ---- 滞销折扣（乘在建议价上）----
    var discounted = PricingTemplateRules.applyDiscount(suggested, turnover.discountRate());
    if (discounted == null) {
      throw badRequest("滞销折扣率不合法（必须落在 0 与 100% 之间）⇒ 不给建议价。");
    }
    suggested = discounted;

    // ---- 复核：用建议价再跑一次 5.3.1.1，拿真实的毛利 ----
    var verifyRequest =
        new CalculateRequest(
            request.skuId(),
            request.siteCode(),
            request.platformCode(),
            currency,
            costCurrency,
            CalcMode.PRICE,
            suggested,
            null,
            quantity,
            fxRate,
            platformFeeRate,
            platformFeeFixed,
            logisticsPerUnit,
            null,
            List.of(),
            null,
            null,
            "定价模板 " + template.summary().code() + " 的建议价复核");
    var verification = calculator.calculate(verifyRequest, bearer);

    // ---- 留痕 ----
    var applied = new ArrayList<AppliedAdjustment>();
    applied.add(
        new AppliedAdjustment(
            AdjustmentType.SITE_DIFFERENTIAL,
            AdjustmentType.SITE_DIFFERENTIAL.label(),
            request.siteCode(),
            siteEffect.rateDelta(),
            null,
            null,
            siteEffect.applied(),
            siteEffect.reason()));
    applied.add(
        new AppliedAdjustment(
            AdjustmentType.INVENTORY_TURNOVER,
            AdjustmentType.INVENTORY_TURNOVER.label(),
            null,
            null,
            null,
            turnover.discountRate(),
            turnover.applied(),
            turnover.reason()));
    var note =
        (request.note() == null || request.note().isBlank() ? template.note() : request.note().trim());
    UUID applicationId = UUID.randomUUID();
    sql.execute(
        "insert into pricing_template_application(id,tenant_id,template_id,sku_id,site_code,"
            + "platform_code,currency,quantity,base_method,effective_rate,suggested_price,"
            + "decision_ready,gross_margin_rate,gross_profit,note,applied_by,created_at)"
            + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?,?,?,?,?,?,?,?,?::uuid,now())",
        applicationId,
        actor.tenantId(),
        templateId,
        request.skuId(),
        request.siteCode() == null ? "" : request.siteCode(),
        request.platformCode() == null ? "" : request.platformCode(),
        currency,
        quantity,
        template.baseMethod().name(),
        effectiveRate,
        suggested,
        verification.decisionReady(),
        verification.grossMarginRate(),
        verification.grossProfit(),
        note == null ? "" : note,
        actor.userId());

    return new ApplyResult(
        applicationId,
        templateId,
        template.summary().code(),
        template.summary().name(),
        request.skuId(),
        product.code(),
        product.name(),
        request.siteCode(),
        request.platformCode(),
        currency,
        quantity,
        template.baseMethod(),
        template.baseMethod().label(),
        effectiveRate,
        applied,
        suggested,
        verification.decisionReady(),
        verification.grossMarginRate(),
        verification.grossProfit(),
        verification,
        note == null ? "" : note,
        OffsetDateTime.now());
  }

  /** 应用留痕（分页）。 */
  public ApplicationPage listApplications(ApplicationQuery query) {
    int limited = Math.min(Math.max(query.size() <= 0 ? 20 : query.size(), 1), MAX_PAGE_SIZE);
    int offset = Math.max(query.page(), 0) * limited;
    var where = new StringBuilder(" where a.tenant_id=" + TENANT);
    var params = new ArrayList<Object>();
    if (query.templateId() != null) {
      where.append(" and a.template_id=?");
      params.add(query.templateId());
    }
    if (query.skuId() != null) {
      where.append(" and a.sku_id=?");
      params.add(query.skuId());
    }
    var rows =
        sql.fetch(
            "select a.id, a.template_id, t.code as template_code, a.sku_id, k.code as sku_code,"
                + " a.site_code, a.currency, a.quantity, a.base_method, a.effective_rate,"
                + " a.suggested_price, a.decision_ready, a.gross_margin_rate, a.created_at, a.applied_by"
                + " from pricing_template_application a"
                + " join pricing_template t on t.tenant_id=a.tenant_id and t.id=a.template_id"
                + " left join sku k on k.tenant_id=a.tenant_id and k.id=a.sku_id"
                + where
                + " order by a.created_at desc, a.id"
                + " limit ? offset ?",
            concat(params, limited, offset));
    long total =
        sql.fetchOne(
                "select count(*) from pricing_template_application a" + where, params.toArray())
            .get(0, Long.class);
    var items = new ArrayList<ApplicationSummary>();
    for (var row : rows) {
      items.add(
          new ApplicationSummary(
              row.get("id", UUID.class),
              row.get("template_id", UUID.class),
              row.get("template_code", String.class),
              row.get("sku_id", UUID.class),
              row.get("sku_code", String.class),
              row.get("site_code", String.class),
              row.get("currency", String.class),
              row.get("quantity", BigDecimal.class),
              BaseMethod.parse(row.get("base_method", String.class)),
              row.get("effective_rate", BigDecimal.class),
              row.get("suggested_price", BigDecimal.class),
              row.get("decision_ready", Boolean.class),
              row.get("gross_margin_rate", BigDecimal.class),
              row.get("created_at", OffsetDateTime.class),
              row.get("applied_by", UUID.class) == null
                  ? null
                  : row.get("applied_by", UUID.class).toString()));
    }
    return new ApplicationPage(items, Math.max(query.page(), 0), limited, total);
  }

  // ================================================================ 查询对象

  /** 模板列表的筛选条件。 */
  public record TemplateQuery(
      String keyword,
      ProductStage productStage,
      BaseMethod baseMethod,
      Boolean enabled,
      ScopeType scopeType,
      String scopeRef,
      int page,
      int size) {}

  /** 应用留痕的筛选条件。 */
  public record ApplicationQuery(UUID templateId, UUID skuId, int page, int size) {}

  // ================================================================ 内部

  /** 模板行（列表与详情共用）。 */
  private org.jooq.Record templateRow(UUID id) {
    return sql.fetchOne(
        "select t.id, t.code, t.name, t.description, t.base_method, t.base_rate,"
            + " t.competitor_reference_price, t.competitor_currency, t.product_stage, t.currency,"
            + " t.platform_fee_rate, t.platform_fee_fixed, t.logistics_cost_per_unit, t.cost_currency,"
            + " t.fx_rate, t.enabled, t.effective_from, t.effective_to, t.note, t.version, t.created_by,"
            + " t.created_at, t.updated_at"
            + " from pricing_template t where t.tenant_id="
            + TENANT
            + " and t.id=?",
        id);
  }

  private TemplateSummary summaryOf(org.jooq.Record row) {
    return summaryOf(
        row,
        row.get("adjustment_count", Integer.class) == null ? 0 : row.get("adjustment_count", Integer.class),
        row.get("scope_count", Integer.class) == null ? 0 : row.get("scope_count", Integer.class));
  }

  private TemplateSummary summaryOf(org.jooq.Record row, int adjustmentCount, int scopeCount) {
    var method = BaseMethod.parse(row.get("base_method", String.class));
    var stage = ProductStage.parse(row.get("product_stage", String.class));
    return new TemplateSummary(
        row.get("id", UUID.class),
        row.get("code", String.class),
        row.get("name", String.class),
        method,
        method == null ? null : method.label(),
        row.get("base_rate", BigDecimal.class),
        stage,
        stage == null ? null : stage.label(),
        row.get("currency", String.class),
        row.get("competitor_reference_price", BigDecimal.class),
        Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
        row.get("effective_from", OffsetDateTime.class),
        row.get("effective_to", OffsetDateTime.class),
        adjustmentCount,
        scopeCount,
        row.get("created_by", UUID.class) == null ? null : row.get("created_by", UUID.class).toString(),
        row.get("updated_at", OffsetDateTime.class));
  }

  /** 模板详情（模板 + 调整项 + 适用对象）。 */
  private TemplateDetail detailOf(org.jooq.Record row) {
    UUID id = row.get("id", UUID.class);
    var adjustments = new ArrayList<AdjustmentView>();
    for (var adjustment :
        sql.fetch(
            "select id, adjustment_type, site_code, delta_rate, days_cover_threshold, discount_rate, note"
                + " from pricing_template_adjustment where tenant_id="
                + TENANT
                + " and template_id=? order by seq",
            id)) {
      var type = AdjustmentType.parse(adjustment.get("adjustment_type", String.class));
      adjustments.add(
          new AdjustmentView(
              adjustment.get("id", UUID.class),
              type,
              type == null ? null : type.label(),
              adjustment.get("site_code", String.class),
              adjustment.get("delta_rate", BigDecimal.class),
              adjustment.get("days_cover_threshold", BigDecimal.class),
              adjustment.get("discount_rate", BigDecimal.class),
              adjustment.get("note", String.class)));
    }
    var scopes = new ArrayList<ScopeView>();
    for (var scope :
        sql.fetch(
            "select id, scope_type, scope_ref, platform_code from pricing_template_scope where tenant_id="
                + TENANT
                + " and template_id=? order by scope_type, scope_ref",
            id)) {
      var type = ScopeType.parse(scope.get("scope_type", String.class));
      scopes.add(
          new ScopeView(
              scope.get("id", UUID.class),
              type,
              type == null ? null : type.label(),
              scope.get("scope_ref", String.class),
              scope.get("platform_code", String.class)));
    }
    var method = BaseMethod.parse(row.get("base_method", String.class));
    var stage = ProductStage.parse(row.get("product_stage", String.class));
    return new TemplateDetail(
        summaryOf(row, adjustments.size(), scopes.size()),
        row.get("description", String.class),
        method,
        row.get("base_rate", BigDecimal.class),
        row.get("competitor_reference_price", BigDecimal.class),
        row.get("competitor_currency", String.class),
        stage,
        row.get("currency", String.class),
        row.get("platform_fee_rate", BigDecimal.class),
        row.get("platform_fee_fixed", BigDecimal.class),
        row.get("logistics_cost_per_unit", BigDecimal.class),
        row.get("cost_currency", String.class),
        row.get("fx_rate", BigDecimal.class),
        Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
        row.get("effective_from", OffsetDateTime.class),
        row.get("effective_to", OffsetDateTime.class),
        row.get("note", String.class),
        row.get("version", Long.class) == null ? 0L : row.get("version", Long.class),
        adjustments,
        scopes,
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  /** 归一化 + 校验：把请求变成内部可用的形状，不合法就 400。 */
  private TemplateInput normalize(TemplateInput input) {
    if (input == null) throw badRequest("模板定义不能为空");
    var code = input.code() == null ? "" : input.code().trim().toUpperCase(Locale.ROOT);
    if (code.isEmpty()) throw badRequest("模板编码必填");
    if (!code.matches("^[A-Z0-9][A-Z0-9_-]{0,63}$")) {
      throw badRequest("模板编码只能是大写字母、数字、短横线与下划线（最多 64 位）");
    }
    var name = input.name() == null ? "" : input.name().trim();
    if (name.isEmpty()) throw badRequest("模板名称必填");
    var method = input.baseMethod();
    if (method == null) throw badRequest("必须选一个基准方法（成本加成 / 固定毛利 / 竞品跟随）");
    var stage = input.productStage();
    if (stage == null) throw badRequest("必须选商品阶段（新品 / 老品）—— 新品与老品用不同模板");
    var currency = upper(input.currency());
    if (currency == null) throw badRequest("必须指定建议价币种");
    var costCurrency = upper(input.costCurrency());
    var fxRate = input.fxRate();
    if (!currency.equals(costCurrency == null ? currency : costCurrency)
        && (fxRate == null || fxRate.signum() <= 0)) {
      throw badRequest(
          "售价币种与成本币种不同，必须给汇率（1 成本币种 = X 售价币种）—— 绝不按 1:1 折算");
    }
    // 基准方法各自的必填列（DB 层还有同样的 CHECK，这里给出可读的原因）。
    var baseRate = input.baseRate();
    if (method == BaseMethod.FIXED_MARGIN) {
      if (baseRate == null) throw badRequest("固定毛利必须给目标毛利率（小数口径，0.25 = 25%）");
      if (baseRate.signum() < 0 || baseRate.compareTo(BigDecimal.ONE) >= 0) {
        throw badRequest("目标毛利率是小数口径，必须落在 0 与 1 之间（目标毛利率 ≥ 100% 时方程无解）");
      }
    } else if (method == BaseMethod.COST_PLUS) {
      if (baseRate == null) throw badRequest("成本加成必须给加成率（���数口径，0.3 = 加 30%）");
      if (baseRate.signum() < 0 || baseRate.compareTo(BigDecimal.TEN) >= 0) {
        throw badRequest("加成率必须落在 0 与 10 之间（1000% 的加成不是定价策略）");
      }
    } else {
      if (baseRate == null) throw badRequest("竞品跟随必须给跟随率（可为负：−0.02 = 比竞品低 2%）");
      if (baseRate.compareTo(BigDecimal.ONE.negate()) <= 0 || baseRate.compareTo(BigDecimal.ONE) >= 0) {
        throw badRequest("跟随率必须落在 −100% 与 100% 之间");
      }
      if (input.competitorReferencePrice() == null
          || input.competitorReferencePrice().signum() <= 0) {
        throw badRequest(
            "竞品跟随必须给竞品基准价（人工登记）：本仓没有竞品价模块，系统不会凭空生成一个竞品价");
      }
      if (upper(input.competitorCurrency()) == null) throw badRequest("竞品价必须给币种");
    }
    if (method != BaseMethod.COMPETITOR_FOLLOW
        && (input.competitorReferencePrice() != null || input.competitorCurrency() != null)) {
      throw badRequest("竞品基准价只属于竞品跟随 —— 基准方法与它的列必须一一对应");
    }
    if (input.effectiveFrom() == null) throw badRequest("生效起点必填");
    if (input.effectiveTo() != null && !input.effectiveTo().isAfter(input.effectiveFrom())) {
      throw badRequest("生效终点必须晚于生效起点");
    }
    // 调整项：两类各自的必填列。
    var adjustments = input.adjustments() == null ? List.<TemplateAdjustmentInput>of() : input.adjustments();
    for (var adjustment : adjustments) {
      if (adjustment == null || adjustment.type() == null) throw badRequest("调整项缺类型");
      if (adjustment.type() == AdjustmentType.SITE_DIFFERENTIAL) {
        if (adjustment.siteCode() == null || adjustment.siteCode().isBlank()) {
          throw badRequest("按站点差异化的调整项必须给站点码");
        }
        if (adjustment.deltaRate() == null) throw badRequest("按站点差异化的调整项必须给增量");
      } else {
        if (adjustment.daysCoverThreshold() == null || adjustment.daysCoverThreshold().signum() < 0) {
          throw badRequest("按库存周转调整的调整项必须给可售天数阈值（≥ 0）");
        }
        if (adjustment.discountRate() == null
            || adjustment.discountRate().signum() < 0
            || adjustment.discountRate().compareTo(BigDecimal.ONE) > 0) {
          throw badRequest("按库存周转调整的调整项必须给折扣率（0 与 1 之间的小数口径）");
        }
      }
    }
    var scopes = input.scopes() == null ? List.<ScopeInput>of() : input.scopes();
    var seen = new java.util.HashSet<String>();
    for (var scope : scopes) {
      if (scope == null || scope.type() == null) throw badRequest("适用对象缺类型");
      var ref = scope.ref() == null ? "" : scope.ref().trim();
      if (ref.isEmpty()) throw badRequest("适用对象必须给取值（分类码 / 品牌码 / 站点码）");
      var key = scope.type().name() + "|" + ref + "|" + (scope.platformCode() == null ? "" : scope.platformCode());
      if (!seen.add(key)) throw badRequest("适用对象重复：" + scope.type().label() + " " + ref);
    }
    return new TemplateInput(
        code,
        name,
        input.description() == null ? "" : input.description().trim(),
        method,
        baseRate,
        input.competitorReferencePrice(),
        upper(input.competitorCurrency()),
        stage,
        currency,
        input.platformFeeRate(),
        input.platformFeeFixed(),
        input.logisticsCostPerUnit(),
        costCurrency,
        fxRate,
        input.enabled() == null ? Boolean.TRUE : input.enabled(),
        input.effectiveFrom(),
        input.effectiveTo(),
        input.note() == null ? "" : input.note().trim(),
        adjustments,
        scopes,
        input.version());
  }

  /** 整体替换调整项与适用对象（同一事务里删 + 插：要么全换要么全不换）。 */
  private void replaceChildren(UUID templateId, AccessService.Access actor, TemplateInput input) {
    sql.execute(
        "delete from pricing_template_adjustment where tenant_id=" + TENANT + " and template_id=?",
        templateId);
    sql.execute(
        "delete from pricing_template_scope where tenant_id=" + TENANT + " and template_id=?",
        templateId);
    int seq = 1;
    for (var adjustment : input.adjustments()) {
      sql.execute(
          "insert into pricing_template_adjustment(id,tenant_id,template_id,seq,adjustment_type,"
              + "site_code,delta_rate,days_cover_threshold,discount_rate,note,created_at)"
              + " values(?::uuid,?::uuid,?::uuid,?,?,?,?,?,?,?,now())",
          UUID.randomUUID(),
          actor.tenantId(),
          templateId,
          seq++,
          adjustment.type().name(),
          adjustment.type() == AdjustmentType.SITE_DIFFERENTIAL ? adjustment.siteCode() : null,
          adjustment.type() == AdjustmentType.SITE_DIFFERENTIAL ? adjustment.deltaRate() : null,
          adjustment.type() == AdjustmentType.INVENTORY_TURNOVER
              ? adjustment.daysCoverThreshold()
              : null,
          adjustment.type() == AdjustmentType.INVENTORY_TURNOVER ? adjustment.discountRate() : null,
          adjustment.note() == null ? "" : adjustment.note().trim());
    }
    for (var scope : input.scopes()) {
      sql.execute(
          "insert into pricing_template_scope(id,tenant_id,template_id,scope_type,scope_ref,"
              + "platform_code,created_at) values(?::uuid,?::uuid,?::uuid,?,?,?,now())",
          UUID.randomUUID(),
          actor.tenantId(),
          templateId,
          scope.type().name(),
          scope.ref().trim(),
          scope.type() == ScopeType.SHOP && scope.platformCode() != null
              ? scope.platformCode().trim().toUpperCase(Locale.ROOT)
              : null);
    }
  }

  /** 商品事实：SKU / SPU 状态（⇒ 商品阶段）/ 主分类码 / 品牌码。 */
  private record ProductFacts(
      UUID skuId,
      String code,
      String name,
      String spuStatus,
      ProductStage stage,
      String categoryCode,
      String brandCode) {}

  private ProductFacts productFacts(UUID skuId) {
    var row =
        sql.fetchOne(
            "select s.id, s.code, s.name, p.status as spu_status, p.brand,"
                + " (select c.code from spu_category sc"
                + "    join product_category c on c.tenant_id=sc.tenant_id and c.id=sc.category_id"
                + "   where sc.tenant_id=s.tenant_id and sc.spu_id=p.id and sc.role='PRIMARY'"
                + "   limit 1) as category_code"
                + " from sku s join spu p on p.tenant_id=s.tenant_id and p.id=s.spu_id"
                + " where s.tenant_id="
                + TENANT
                + " and s.id=?",
            skuId);
    if (row == null) throw notFound("SKU 不存在");
    var spuStatus = row.get("spu_status", String.class);
    var stage =
        "DRAFT".equals(spuStatus) || "PENDING_LISTING".equals(spuStatus)
            ? ProductStage.NEW
            : ProductStage.EXISTING;
    return new ProductFacts(
        row.get("id", UUID.class),
        row.get("code", String.class),
        row.get("name", String.class),
        spuStatus,
        stage,
        row.get("category_code", String.class),
        row.get("brand", String.class));
  }

  /** 适用对象校验：模板没配适用对象 = 适用于全租户；配了 ⇒ 商品必须命中其中一条。 */
  private void requireScopeMatch(List<ScopeView> scopes, ProductFacts product, ApplyRequest request) {
    if (scopes.isEmpty()) return;
    for (var scope : scopes) {
      if (scope.type() == ScopeType.CATEGORY
          && scope.ref().equalsIgnoreCase(nullToEmpty(product.categoryCode()))) {
        return;
      }
      if (scope.type() == ScopeType.BRAND
          && scope.ref().equalsIgnoreCase(nullToEmpty(product.brandCode()))) {
        return;
      }
      if (scope.type() == ScopeType.SHOP
          && scope.ref().equalsIgnoreCase(nullToEmpty(request.siteCode()))) {
        if (scope.platformCode() == null
            || scope.platformCode().equalsIgnoreCase(nullToEmpty(request.platformCode()))) {
          return;
        }
      }
    }
    var wanted = new ArrayList<String>();
    for (var scope : scopes) {
      wanted.add(scope.typeLabel() + " " + scope.ref());
    }
    throw conflict(
        "商品 "
            + product.code()
            + " 不在这条模板的适用对象里（"
            + String.join("、", wanted)
            + "）—— 模板是「给谁定的价」，套错对象会让定价策略串味。");
  }

  /** 是否有按库存周转调整的规则。 */
  private static boolean hasTurnoverRule(List<PricingTemplateRules.AdjustmentSpec> specs) {
    for (var spec : specs) {
      if (spec.type() == AdjustmentType.INVENTORY_TURNOVER) return true;
    }
    return false;
  }

  /**
   * 加成基数 = 单位成本合计（5 项成本分项 + 物流每单位），全部取自 5.3.1.1 的瀑布图。
   *
   * <p>🔴 缺任何一项 ⇒ 返回 null（**不给价**），而不是用已知部分凑一个基数 ——
   * 缺项凑出来的基数会让加成价偏低（单位成本偏低 ⇒ 售价偏低 ⇒ 越卖越亏）。
   */
  private BigDecimal unitCostBase(ProfitCalculation calculation) {
    var total = BigDecimal.ZERO;
    for (var code :
        List.of(
            ProfitCalculatorModels.Deduction.PURCHASE.name(),
            ProfitCalculatorModels.Deduction.FIRST_MILE.name(),
            ProfitCalculatorModels.Deduction.PACKAGING.name(),
            ProfitCalculatorModels.Deduction.DUTY.name(),
            ProfitCalculatorModels.Deduction.OTHER_DIRECT.name(),
            ProfitCalculatorModels.Deduction.LOGISTICS.name())) {
      WaterfallLineAmount line = null;
      for (var candidate : calculation.waterfall()) {
        if (code.equals(candidate.code())) {
          line = new WaterfallLineAmount(candidate.available(), candidate.partial(), candidate.amount());
          break;
        }
      }
      if (line == null || !line.available() || line.partial() || line.amount() == null) return null;
      total = total.add(line.amount());
    }
    return PricingTemplateRules.money(total);
  }

  /** 瀑布图一行的三个要紧字段。 */
  private record WaterfallLineAmount(boolean available, boolean partial, BigDecimal amount) {}

  /** 缺口清单摘要（拒绝给价时告诉页面缺哪几项、归谁管）。 */
  private String gapSummary(List<BlockingGap> gaps) {
    if (gaps == null || gaps.isEmpty()) return "（5.3.1.1 没有给出缺口清单）";
    var parts = new ArrayList<String>();
    for (var gap : gaps) {
      parts.add(gap.label() + "（归 " + gap.ownerModule() + " 管：" + gap.impact() + "）");
    }
    return String.join("；", parts);
  }

  private static String upper(String value) {
    if (value == null || value.isBlank()) return null;
    return value.trim().toUpperCase(Locale.ROOT);
  }

  /** 把参数列表与额外的定值拼成一个参数数组（jOOQ 不吃可变参数列表）。 */
  private static Object[] concat(List<Object> params, Object... extra) {
    var all = new ArrayList<Object>(params);
    for (var value : extra) {
      all.add(value);
    }
    return all.toArray();
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
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
