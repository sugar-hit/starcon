package com.starcon.catalog;

import static com.starcon.catalog.SkuCostCompositionModels.*;

import com.starcon.catalog.CostBasisPort.CostBasisResult;
import com.starcon.catalog.CostBasisPort.CostBasisUnavailableException;
import com.starcon.catalog.CostBasisPort.DutyAllocation;
import com.starcon.catalog.CostBasisPort.FirstMileAllocation;
import com.starcon.catalog.SkuCostCompositionModels.CostComponent;
import com.starcon.catalog.SkuCostCompositionModels.CostMethod;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * 5.2.1.1 成本构成：SKU 的单位成本构成（分项 + 合计 + 生效时间）与成本变动历史。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：展示并维护 SKU 的单位成本构成：采购成本、头程分摊、包装成本、关税、其他直接成本。采购成本来自 06 采购的实际单价（按成本法加权），其他项来自参数或手工调整。
 *
 * **输入**：成本法选择（移动加权/先进先出/批次）；12 财务的成本核算结果；手工调整项及原因。
 *
 * **输出**：单位成本明细（分项 + 合计 + 生效时间）；成本变动历史。
 *
 * **关系**：上游 = 06 采购、12 财务、10 头程费用；下游 = 5.3.1.1 测算、13 利润。**成本口径必须在页面上明示用了哪种成本法**，否则同一 SKU 今天显示毛利 30% 明天 25% 无法解释。
 *
 * </blockquote>
 *
 * <h2>本类的七条纪律</h2>
 *
 * <ol>
 *   <li><b>🔴 成本法参与算术，不是一个下拉框</b>。同一份到货事实，三种成本法算出**三个不同的**单位成本
 *       （{@link SkuCostCompositionRules}）。列表、明细、历史快照**共用**这一份实现 ——
 *       三处各算一次的话，迟早出现「明细写 12.00、历史快照却是 12.50」这种没法解释的对不上。
 *   <li><b>🔴 采购成本是派生值，不是列</b>。它由 catalog 同库 {@code vendor_delivery_record}
 *       （V137 的**实际到货事实**）按成本法实时算出。落库就会变成第二个事实来源。
 *       而「实际成交价」的真身（6.3 采购单）当前未交付 —— 所以这一项的来源必须**在页面上写明**，
 *       并且在**没有到货事实时标「取不到」而不是填 0**。
 *   <li><b>🔴 报价不是实际价</b>。{@code supplier_quote}（V32）只作为 {@link QuoteReference}
 *       只读参考单列，<b>永不计入合计</b>。V32 自己写着「05 定价取成本参考」——参考就是参考。
 *   <li><b>🔴 跨币种绝不相加</b>。一个 SKU 在一个币种下有一条构成；上游的异币种金额单列在
 *       {@code foreignAmounts} 且**不折算**（本库没有汇率表，汇率是 12 财务的资产）。
 *   <li><b>🔴 读不到 ≠ 没有</b>。上游不可达 ⇒ 读端点返回 <b>503</b>，理由写「读不到上游」；
 *       写端点不失败，但历史快照里如实标 {@code snapshot_complete=false} 并点名缺哪几项 ——
 *       变更本身是本地事实，不该因为上游读不到就保存不了。
 *   <li><b>🔴 换成本法留历史</b>。每一次变更追加一条 {@code sku_cost_revision} 快照
 *       （含当时的成本法、币种、五个分项的当时值、当时的合计、生效时间、变更原因）。
 *       旧版本原样留着，没有 UPDATE 端点。历史是 5.2.1.3 成本分摊回溯的依据，不可变。
 *   <li><b>租户只来自已认证成员关系</b>（{@link AccessService}），不读 {@code X-Tenant-Id} 头。
 *       每条 SQL 的 where 都带显式 {@code tenant_id=} 绑定，RLS 再兜一道。
 * </ol>
 */
@Service
public class SkuCostCompositionService {

  /** 租户上下文取法：RLS 已按它隔离，这里带上只是让执行计划走租户前缀的索引。 */
  private static final String TENANT = "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 台账页大小上限：与相邻模块一致，防止一次拉爆整个租户的数据。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 成本口径窗口的默认跨度（天）。窗口太宽会把去年的价格拖进今天的成本里。 */
  private static final int DEFAULT_WINDOW_DAYS = 90;

  /** 历史快照一次取多少条。历史不可变，页面上按版本号倒序翻。 */
  private static final int MAX_REVISIONS = 50;

  /** 同一个 SKU 的构成身份是 (sku, 币种)，历史版本号也按这个维度递增。 */
  private static final int REVISION_RETRY = 3;

  private final DSLContext sql;
  private final CostBasisPort costBasis;

  public SkuCostCompositionService(DSLContext sql, CostBasisPort costBasis) {
    this.sql = sql;
    this.costBasis = costBasis;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：五个分项各自的来源、能不能取到、归谁管。
   *
   * <p>页面上把它固定显示在顶部 —— 蓝图那句「成本口径必须在页面上明示」靠它落地。
   *
   * <p>🔴 每个分项都写清「真值在哪、当前取不取得到、缺了会怎样」。
   * 一个只说「采购成本来自采购」的声明，等于把「其实用的是到货金额」这件事藏起来。
   */
  public CostCapabilities capabilities() {
    var components =
        List.of(
            new ComponentCapability(
                "PURCHASE",
                "采购成本",
                "CATALOG_VENDOR_DELIVERY",
                "DERIVED",
                true,
                "05 定价与价目（本服务）· 数据源 6.1.1.1 供应商列表的 vendor_delivery_record（V137）",
                "蓝图要求的是 06 采购的**实际成交价**；6.3 采购单当前未交付，本项口径改为 catalog 同库的"
                    + "**实际到货金额**（已到货事实）按所选成本法加权派生。供应商报价（supplier_quote）"
                    + "只作为只读参考单列在详情里，**不计入成本、不计入合计**。",
                "由该 SKU 窗口内的有效到货记录按成本法加权，金额折算到 SKU 基础单位后相除。",
                "到货记录按采购单号 + 实际到货日可逐条追溯到 vendor_delivery_record。"),
            new ComponentCapability(
                "FIRST_MILE",
                "头程分摊",
                "WAREHOUSE_FIRST_MILE",
                "DERIVED",
                true,
                "10 物流与头程（warehouse 服务）· first_mile_shipment / _line",
                "跨服务只读，经 warehouse 的授权 HTTP 端点读取（AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图，"
                    + "本库不存头程副本）。**上游不可达时读端点返回 503**，不会用 0 冒充「没有头程成本」。",
                "一张发运单的运费 + 保险费 + 操作费按各行数量摊到 SKU 上，再折成单位成本。",
                "上游单据数超过本端口的覆盖上限时，响应里会写明只覆盖了最近多少张单。"),
            new ComponentCapability(
                "PACKAGING",
                "包装成本",
                "CATALOG_PARAMETER",
                "PARAMETER",
                true,
                "05 定价与价目（本服务）· sku_cost_profile.packaging_cost",
                "全仓没有包装成本事实（catalog 的 sku_package 只有长宽高重量，warehouse 的 packing_material "
                    + "只有尺寸自重，都**没有成本列**），所以它是**人工参数**：可空 = 尚未维护，"
                    + "**不是 0**。显示 0.00 等于宣称「这个 SKU 不花钱包装」。",
                "由人维护的单位包装成本，保存时写进构成并留一条变更历史。",
                "包材单价待 06 采购或 12 财务补齐后，可改为派生。"),
            new ComponentCapability(
                "DUTY",
                "关税",
                "WAREHOUSE_DUTY_TAX",
                "DERIVED",
                true,
                "10.5.1.3 关税与税费（warehouse 服务）· duty_tax_estimate / _line",
                "蓝图写的是「12 财务的成本核算结果」，而 **12 财务（finance 服务）在本仓不存在** —— "
                    + "本项因此取自 warehouse 的关税估算（10.5.1.3 已交付）。**这是本服务口径，不是 12 财务的核算结果**，"
                    + "与 12 财务的最终对账留待 finance 落地后经授权 API / event 衔接。",
                "按估算单的关税行金额（不含增值税/GST）折成单位成本；增值税与 GST 单列展示，归 5.2.1.2 到岸成本。",
                "上游估算单的计量单位与 SKU 基础单位之间没有换算版本时，这一项**取不到**（不给错的单位成本）。"),
            new ComponentCapability(
                "OTHER_DIRECT",
                "其他直接成本",
                "CATALOG_ADJUSTMENT",
                "MANUAL",
                true,
                "05 定价与价目（本服务）· sku_cost_adjustment（只追加）",
                "手工调整项**原因必填**（蓝图输入句「手工调整项及原因」），DB 层也有 CHECK 兜底。"
                    + "改一条 = 作废旧行 + 追加新行，旧行留在库里，所以「这个 SKU 的成本历史上被改过几次」可追溯。",
                "该 SKU 在本币种下未作废的调整项金额之和。",
                "调整项的币种必须与构成一致，跨币种绝不相加。"));

    var methods =
        List.of(
            new MethodOption("MOVING_AVERAGE", CostMethod.MOVING_AVERAGE.label(), CostMethod.MOVING_AVERAGE.basis()),
            new MethodOption("FIFO", CostMethod.FIFO.label(), CostMethod.FIFO.basis()),
            new MethodOption("LOT", CostMethod.LOT.label(), CostMethod.LOT.basis()));

    return new CostCapabilities(
        components,
        methods,
        List.of("USD", "EUR", "CNY", "GBP", "JPY"),
        "同一份到货事实，三种成本法会算出**三个不同的**单位成本 —— 所以成本构成记录**必须带成本法**，"
            + "页面上也必须显式显示当前用的是哪一种。只有这样，「同一 SKU 今天显示毛利 30% 明天 25%」"
            + "才是可解释的：不是成本变了，是口径变了，而口径的每一次变更都留在成本变动历史里。",
        "上游真值：采购成本 = catalog 同库的实际到货事实（vendor_delivery_record，V137）；"
            + "头程分摊 = warehouse 头程发运单；关税 = warehouse 关税估算单（10.5.1.3）。"
            + "**06 采购单（6.3）与 12 财务（finance 服务）当前均未交付** —— 采购成本因此是到货口径，"
            + "关税因此是本服务口径，两处都在页面上写明。",
        "下游：5.3.1.1 利润测算、13 利润与经营分析。它们读的是本模块的**历史版本**"
            + "（sku_cost_revision），所以历史不可变；口径变更会改变历史利润的解释方式，"
            + "因此每一次变更都必须留版本。",
        "成本构成只写本服务自有的三张表（sku_cost_profile / sku_cost_adjustment / sku_cost_revision）。"
            + "派生分项（采购 / 头程 / 关税）**不落库**，每次读取都按当时的成本法现算 —— "
            + "落库就等于制造第二个事实来源，而成本正确性恰恰依赖「成本 = 当前事实按所选成本法算出来的值」。",
        "可做的动作：保存构成（改成本法 / 计价币种 / 包装成本参数，每次都留历史）、"
            + "追加手工调整项、作废调整项（要填理由）、查看成本变动历史。"
            + "**没有**「就地改派生分项」的入口 —— 采购成本、头程、关税要改，去改上游的单据。");
  }

  // ================================================================ 列表

  /**
   * 成本构成台账。
   *
   * <p><b>列表不实时连上游</b>：头程分摊与关税要经 {@link CostBasisPort} 取 warehouse，
   * 一页几十行逐行连上游会把一次列表查询变成几十次跨服务调用。所以列表只给
   * <b>本服务自有</b>的部分 + 同库派生的采购成本；头程与关税在明细里算。
   *
   * <p>列表以 SKU 为骨架（{@code sku} LEFT JOIN {@code sku_cost_profile}）：
   * 一个 SKU 在一个币种下有一条构成 ⇒ 有几条构成就出几行；**还没维护构成的 SKU 也出���**
   * （{@code maintained=false}、币种为空），因为「哪些 SKU 还没维护成本构成」是成本治理页的待办入口。
   */
  public CostListPage list(CostListQuery query) {
    int size = query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
    int page = Math.max(query.page(), 0);
    var where = new StringBuilder(" where s.tenant_id = " + TENANT);
    var binds = new ArrayList<Object>();
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.append(" and (s.code ilike ? or s.name ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
    }
    if (query.costMethod() != null && !query.costMethod().isBlank()) {
      where.append(" and p.cost_method = ?");
      binds.add(query.costMethod().trim().toUpperCase(Locale.ROOT));
    }
    if (query.currency() != null && !query.currency().isBlank()) {
      where.append(" and p.currency = ?");
      binds.add(query.currency().trim().toUpperCase(Locale.ROOT));
    }
    if (query.maintained() != null) {
      where.append(query.maintained() ? " and p.id is not null" : " and p.id is null");
    }

    String from = " from sku s"
        + " join spu sp on sp.tenant_id=s.tenant_id and sp.id=s.spu_id"
        + " left join sku_cost_profile p on p.tenant_id=s.tenant_id and p.sku_id=s.id";
    long total = count("select count(*)" + from + where, binds.toArray());
    var records =
        sql.fetch(
            "select s.id as sku_id, s.code as sku_code, s.name as sku_name, sp.code as spu_code,"
                + " s.base_unit, s.status as sku_status, p.id as profile_id, p.cost_method, p.currency,"
                + " p.packaging_cost, p.effective_from, p.updated_at, p.version"
                + from
                + where
                + " order by coalesce(p.updated_at, s.updated_at) desc, s.code"
                + " limit "
                + size
                + " offset "
                + ((long) page * size),
            binds.toArray());

    // 采购成本：一次把本页涉及的 SKU 的到货记录全取回来，在内存里按成本法加权。
    // 逐行查会变成 N+1（一页 20 行 = 20 次查询），而到货记录是**批量**事实。
    var skuIds = new ArrayList<UUID>();
    for (var record : records) {
      var id = record.get("sku_id", UUID.class);
      if (id != null && !skuIds.contains(id)) skuIds.add(id);
    }
    var deliveryFacts =
        skuIds.isEmpty()
            ? new DeliveryFacts(Map.of(), Map.of())
            : deliveries(skuIds, windowFrom(query), windowTo(query));
    var lotsByKey = deliveryFacts.lots();

    // 其他直接成本：同一条批量聚合。
    var otherDirect = otherDirectByCurrency(skuIds);

    var items = new ArrayList<CostRow>();
    for (var record : records) {
      var skuId = record.get("sku_id", UUID.class);
      var profileId = record.get("profile_id", UUID.class);
      var currency = record.get("currency", String.class);
      var method = methodOf(record.get("cost_method", String.class));
      var maintained = profileId != null;
      BigDecimal purchaseCost = null;
      Integer deliveryCount = null;
      if (currency != null) {
        var lots = lotsByKey.getOrDefault(lotKey(skuId, currency), List.of());
        deliveryCount = lots.size();
        var weighted = SkuCostCompositionRules.weighted(method, lots);
        purchaseCost = weighted.unitCost();
      } else {
        // 未维护构成 ⇒ 计价币种未定 ⇒ 不能给采购成本（给一个「随便哪个币种」的数就是在编）。
        // 只报「到过几次货」这个不依赖币种的事实。
        deliveryCount = 0;
        for (var entry : lotsByKey.entrySet()) {
          if (entry.getKey().startsWith(skuId + "|")) deliveryCount += entry.getValue().size();
        }
      }
      items.add(
          new CostRow(
              skuId,
              record.get("sku_code", String.class),
              record.get("sku_name", String.class),
              record.get("spu_code", String.class),
              record.get("base_unit", String.class),
              record.get("sku_status", String.class),
              profileId,
              maintained,
              method.name(),
              method.label(),
              currency,
              record.get("packaging_cost", BigDecimal.class),
              currency == null
                  ? null
                  : otherDirect.getOrDefault(lotKey(skuId, currency), BigDecimal.ZERO),
              purchaseCost,
              deliveryCount,
              currency == null ? 0 : revisionCount(skuId, currency),
              record.get("effective_from", OffsetDateTime.class),
              record.get("updated_at", OffsetDateTime.class)));
    }

    return new CostListPage(
        items,
        total,
        page,
        size,
        windowNote(query),
        "列表给的是**本服务自有**的部分 + 同库派生的采购成本；头程分摊与关税取自 warehouse，"
            + "在明细里实时计算。计价币种未定的 SKU（未维护构成）不显示采购成本 —— "
            + "换一个币种报一个数就是编数据。");
  }

  // ================================================================ 明细

  /**
   * 成本构成明细：五个分项 + 合计 + 生效时间 + 变更历史 + 批次 + 手工调整项。
   *
   * @param currency 计价币种；不传时用该 SKU 已有的构成（多个时取第一个）。
   * @param bearer 调用者的 Authorization 头 —— **透传给 warehouse**，租户由它从成员关系解析。
   */
  public CostDetail detail(
      UUID skuId, String currency, LocalDate from, LocalDate to, String bearer) {
    var sku = skuInfo(skuId);
    if (sku == null) throw missing("SKU 不存在");
    String cur = resolveCurrency(skuId, currency);
    var profile = profile(skuId, cur);
    boolean maintained = profile != null;
    var method = maintained ? methodOf(profile.costMethod()) : CostMethod.MOVING_AVERAGE;

    // 采购成本：同库派生。**没有到货事实就是取不到，不是 0**。
    var deliveryFacts = deliveries(List.of(skuId), from, to);
    var lots = deliveryFacts.lots().getOrDefault(lotKey(skuId, cur), List.of());
    var weighted = SkuCostCompositionRules.weighted(method, lots);
    int excluded = deliveryFacts.excluded().getOrDefault(skuId, 0);

    // 头程与关税：跨服务只读。**读不到就 503**，不用空集合冒充「上游没有这笔成本」。
    CostBasisResult upstream = readUpstream(skuId, from, to, bearer);

    var firstMile = firstMileComponent(upstream, skuId, cur);
    var duty = dutyComponent(upstream, skuId, cur, sku.baseUnit());

    // 包装成本：参数。可空 = 尚未维护。
    BigDecimal packaging = maintained ? profile.packagingCost() : null;
    var packagingComponent =
        packaging == null
            ? new CostComponentRow(
                "PACKAGING",
                CostComponent.PACKAGING.label(),
                "CATALOG_PARAMETER",
                "PARAMETER",
                false,
                false,
                "包装成本是人工参数，**尚未维护**（包材目录没有成本列，待 06 采购 / 12 财务补齐）。"
                    + "这里显示「未维护」而不是 0.00 —— 0.00 是「维护成了 0」，不是「没人填过」。",
                null,
                List.of(),
                "人工参数：保存构成时填写的单位包装成本。",
                "")
            : new CostComponentRow(
                "PACKAGING",
                CostComponent.PACKAGING.label(),
                "CATALOG_PARAMETER",
                "PARAMETER",
                true,
                false,
                null,
                packaging,
                List.of(new AmountRow(cur, packaging)),
                "人工参数：保存构成时填写的单位包装成本。",
                "");

    // 其他直接成本：手工调整项累加。无调整记录 = 0（这份台账本身就是事实源，空即零）。
    var adjustments = adjustments(skuId);
    BigDecimal otherDirect = BigDecimal.ZERO;
    for (var adjustment : adjustments) {
      if (adjustment.isVoid()) continue;
      if (!cur.equals(adjustment.currency())) continue;
      otherDirect = otherDirect.add(adjustment.amount());
    }
    otherDirect = otherDirect.setScale(SkuCostCompositionRules.MONEY_SCALE, SkuCostCompositionRules.ROUNDING);
    var otherDirectComponent =
        new CostComponentRow(
            "OTHER_DIRECT",
            CostComponent.OTHER_DIRECT.label(),
            "CATALOG_ADJUSTMENT",
            "MANUAL",
            true,
            false,
            null,
            otherDirect,
            List.of(new AmountRow(cur, otherDirect)),
            "手工调整项累加：每一笔都带原因，追加式（改 = 作废旧行 + 追加新行）。",
            adjustments.stream().filter(a -> !a.isVoid()).count() + " 笔未作废调整");

    var purchasePartial = excluded > 0;
    var purchaseComponent =
        new CostComponentRow(
            "PURCHASE",
            CostComponent.PURCHASE.label(),
            "CATALOG_VENDOR_DELIVERY",
            "DERIVED",
            weighted.unitCost() != null,
            purchasePartial,
            weighted.unitCost() == null
                ? "窗口内没有该 SKU 在 "
                    + cur
                    + " 下的有效到货记录 —— 实际成交价取不到（6.3 采购单未交付），"
                    + "所以这一项**取不到**，不是 0。"
                : null,
            weighted.unitCost(),
            weighted.unitCost() == null
                ? List.of()
                : List.of(
                    new AmountRow(
                        cur,
                        weighted.unitCost().setScale(
                            SkuCostCompositionRules.MONEY_SCALE, SkuCostCompositionRules.ROUNDING))),
            "来源：catalog 同库的实际到货事实（vendor_delivery_record，V137），按「"
                + method.label()
                + "」加权，金额折算到基础单位「"
                + sku.baseUnit()
                + "」。"
                + "**不是 06 采购单的成交价**（6.3 采购单未交付）；供应商报价另作只读参考，不计入本项。",
            purchasePartial
                ? "🔴 有 "
                    + excluded
                    + " 条到货记录折算不出基础数量（采购单位与基础单位之间没有换算版本），已被排除，"
                    + "所以这一项只是「已知部分」。"
                : "参与计算的到货记录 " + weighted.lotCount() + " 条，合计金额 "
                    + weighted.totalAmount() + " / 基础数量 " + weighted.totalBaseQuantity());

    var components = List.of(purchaseComponent, firstMile, duty, packagingComponent, otherDirectComponent);

    // 合计：只加**本币种**的可用分项。缺项时是「已知部分合计」，并点名缺哪几项。
    var amounts = new ArrayList<BigDecimal>();
    var missing = new ArrayList<String>();
    var partial = new ArrayList<String>();
    for (var component : components) {
      if (!component.available()) {
        missing.add(component.code());
        continue;
      }
      if (component.partial()) partial.add(component.code());
      if (component.amount() != null) amounts.add(component.amount());
    }
    var total = SkuCostCompositionRules.total(amounts);
    boolean complete = missing.isEmpty() && partial.isEmpty();

    // 上游的异币种金额：单列、**不折算**（本库没有汇率表）。
    var foreign = foreignAmounts(upstream, skuId, cur);

    var lotRows = new ArrayList<LotRow>();
    for (var lot : SkuCostCompositionRules.orderedLots(lots)) {
      boolean selected =
          weighted.selectedLot() != null && weighted.selectedLot().id().equals(lot.id());
      lotRows.add(
          new LotRow(
              lot.deliveryRef(),
              lot.receivedDate(),
              lot.quantity(),
              lot.baseQuantity(),
              lot.amount(),
              lot.unitCost(),
              lot.currency(),
              selected));
    }

    return new CostDetail(
        sku.id(),
        sku.code(),
        sku.name(),
        sku.spuCode(),
        sku.baseUnit(),
        maintained,
        method.name(),
        method.label(),
        method.basis(),
        cur,
        maintained ? profile.effectiveFrom() : null,
        components,
        List.of(new AmountRow(cur, total)),
        total,
        complete,
        missing,
        partial,
        excluded,
        foreign,
        adjustments,
        revisions(skuId, cur),
        lotRows,
        quoteReference(skuId),
        from,
        to,
        "成本口径 = 「"
            + method.label()
            + "」，计价币种 "
            + cur
            + "，单位 = 每 "
            + sku.baseUnit()
            + "。"
            + (maintained
                ? "生效时间 " + profile.effectiveFrom() + "。"
                : "**尚未维护构成**：当前按默认口径（移动加权）显示，保存后按你选的成本法固化。"),
        complete
            ? "合计是五个分项之和。"
            : "🔴 合计是**已知部分合计**：缺 " + missing + (partial.isEmpty() ? "" : "，部分 " + partial)
                + "。这些分项取不到或只覆盖了一部分事实，**不要**把这个数直接拿去算利润。");
  }

  // ================================================================ 写：保存构成

  /**
   * 保存成本构成（成本法 / 计价币种 / 包装成本参数 / 生效时间）。
   *
   * <p><b>每一次保存都追加一条历史快照</b>：换成本法是换口径，
   * 「这个 SKU 昨天的成本 12.00 是移动加权算的，今天变成了 10.50（先进先出）」——
   * 如果就地覆盖，历史利润就没有解释力了。
   */
  public CostSaved save(AccessService.Access actor, UUID skuId, ProfileInput input, String bearer) {
    var method = CostMethod.parse(input.costMethod());
    if (method == null) {
      throw badRequest("成本法必须是 MOVING_AVERAGE（移动加权）/ FIFO（先进先出）/ LOT（批次）之一");
    }
    String currency = input.currency().trim().toUpperCase(Locale.ROOT);
    if (skuInfo(skuId) == null) throw missing("SKU 不存在");

    var existing = profile(skuId, currency);
    UUID profileId;
    long version;
    if (existing == null) {
      profileId = Ids.next();
      version = 0;
      sql.execute(
          "insert into sku_cost_profile(id,tenant_id,sku_id,cost_method,currency,packaging_cost,"
              + "effective_from,version,created_by,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?::timestamptz,0,?,now(),now())",
          profileId,
          actor.tenantId(),
          skuId,
          method.name(),
          currency,
          input.packagingCost(),
          input.effectiveFrom(),
          actor.userId());
    } else {
      profileId = existing.id();
      version = existing.version();
      // 🔴 客户端回传的版本必须与当前版本一致（与本仓其它模块同一套乐观锁写法：
      // 报价 / SPU / SKU 都是「version 不等于当前值 ⇒ 409，请刷新后重试」）。
      // 只在 SQL 的 where 里带 version 是不够的 —— 那只能挡住「读取之后被别人改过」，
      // 挡不住「客户端拿着一个早就过期的版本照样覆盖别人」。
      // 成本构成是定价的输入，静默覆盖会让「页面上显示的成本」与「用户当时看到的」不是同一个。
      if (version != input.version()) {
        throw conflict("成本构成已被他人修改（版本冲突），请刷新后重试");
      }
      int updated =
          sql.execute(
              // 🔴 effective_from 必须写 ?::timestamptz：jOOQ 的纯 SQL 绑定会把 OffsetDateTime
              // 当作 character varying 送过去，PG 直接报
              // 「column "effective_from" is of type timestamp with time zone but expression is of
              // type character varying」。写成 ?（不带转换）时**更新构成会整条失败**，
              // 而 INSERT 那边因为有 values(?::timestamptz,...) 才没暴露出来 ——
              // 同一个字段两处写法不一致，是这类 bug 最难找的地方。
              "update sku_cost_profile set cost_method=?, packaging_cost=?, effective_from=?::timestamptz,"
                  + " version=version+1, updated_at=now()"
                  + " where tenant_id="
                  + TENANT
                  + " and id=? and version=?",
              method.name(),
              input.packagingCost(),
              input.effectiveFrom(),
              profileId,
              version);
      if (updated == 0) {
        throw conflict("成本构成已被他人修改（版本冲突），请刷新后重试");
      }
      version = version + 1;
    }

    var snapshot = snapshot(actor, skuId, currency, method, input.packagingCost(), input.effectiveFrom(), bearer);
    var revision = appendRevision(actor, skuId, currency, method, snapshot, input.changeReason(), input.effectiveFrom());
    sql.execute(
        "update sku_cost_profile set current_revision_id=? where tenant_id=" + TENANT + " and id=?",
        revision.id(),
        profileId);

    return new CostSaved(profileId, revision.id(), revision.revisionNo(), currency, input.effectiveFrom(), version);
  }

  // ================================================================ 写：手工调整项

  /**
   * 追加手工调整项。
   *
   * <p>原因**必填**（蓝图输入句「手工调整项及原因」），DB 层还有 CHECK 兜底。
   * 追加后同样落一条历史快照 —— 「成本突然多了 3 块」必须能查到是哪一笔。
   */
  public CostSaved addAdjustment(
      AccessService.Access actor, UUID skuId, AdjustmentInput input, String bearer) {
    var component = CostComponent.parse(input.component());
    if (component == null) {
      throw badRequest("调整项只能作用在 PACKAGING（包装成本）或 OTHER_DIRECT（其他直接成本）上");
    }
    if (component == CostComponent.PURCHASE
        || component == CostComponent.FIRST_MILE
        || component == CostComponent.DUTY) {
      throw badRequest(
          "采购成本是派生值、头程与关税是上游事实 —— 这三个分项不能在这里改，要改去改上游的单据");
    }
    if (input.amount() == null || input.amount().signum() == 0) {
      throw badRequest("调整金额不能为 0（金额为 0 的调整行没有信息量）");
    }
    // 🔴 原因必填：HTTP 层有 @NotBlank 兜底，但**服务层自己也要挡** ——
    // 否则非 HTTP 调用方（定时任务 / 内部编排）会一路撞到 DB 的 CHECK，
    // 拿到一个 500 的 DataIntegrityViolationException，而不是一句「原因不能为空」。
    // DB 层的 CHECK 仍然保留（它挡的是绕过服务层的直连写入）。
    if (input.reason() == null || input.reason().isBlank()) {
      throw badRequest(
          "手工调整项必须填原因：没有原因的调整会让「这个 SKU 的其他直接成本为什么突然变了」永远答不上来");
    }
    String currency = input.currency().trim().toUpperCase(Locale.ROOT);
    if (skuInfo(skuId) == null) throw missing("SKU 不存在");
    var profile = profile(skuId, currency);
    if (profile == null) {
      throw badRequest(
          "该 SKU 还没有 " + currency + " 的成本构成，无法追加调整项 —— 请先保存一条构成（成本法 + 计价币种）");
    }

    sql.execute(
        "insert into sku_cost_adjustment(id,tenant_id,sku_id,component,amount,currency,reason,"
            + "effective_from,is_void,void_reason,created_by,created_at)"
            + " values(?,?,?,?,?,?,?,?::timestamptz,false,'',?,now())",
        Ids.next(),
        actor.tenantId(),
        skuId,
        component.name(),
        input.amount(),
        currency,
        input.reason(),
        input.effectiveFrom(),
        actor.userId());

    var method = methodOf(profile.costMethod());
    var snapshot = snapshot(actor, skuId, currency, method, profile.packagingCost(), input.effectiveFrom(), bearer);
    var revision =
        appendRevision(actor, skuId, currency, method, snapshot, "追加手工调整项：" + input.reason(), input.effectiveFrom());
    sql.execute(
        "update sku_cost_profile set current_revision_id=? where tenant_id=" + TENANT + " and id=?",
        revision.id(),
        profile.id());

    return new CostSaved(profile.id(), revision.id(), revision.revisionNo(), currency, input.effectiveFrom(), profile.version());
  }

  /**
   * 作废一条调整项（**必须给理由**）。
   *
   * <p>作废而不是删除：与 {@code vendor_delivery_record} 同一纪律 ——
   * 「这个 SKU 的成本历史上被改过几次、每次改了什么」必须可追溯。
   */
  public CostSaved voidAdjustment(
      AccessService.Access actor, UUID skuId, UUID adjustmentId, VoidInput input, String bearer) {
    var adjustment = adjustment(skuId, adjustmentId);
    if (adjustment == null) throw missing("调整项不存在");
    if (adjustment.isVoid()) throw badRequest("这条调整项已经作废过了");
    // 🔴 作废理由必填（与调整原因同一纪律）：DB 层的 CHECK
    // 「NOT is_void OR btrim(void_reason) <> ''」会挡住空理由，但撞到它会得到 500 而不是 400。
    if (input.reason() == null || input.reason().isBlank()) {
      throw badRequest("作废必须填理由：没有理由的作废会让「成本突然降了」无法解释");
    }
    int updated =
        sql.execute(
            "update sku_cost_adjustment set is_void=true, void_reason=?, voided_by=?, voided_at=now()"
                + " where tenant_id="
                + TENANT
                + " and id=? and is_void=false",
            input.reason(),
            actor.userId(),
            adjustmentId);
    if (updated == 0) throw conflict("调整项状态已变化，请刷新后重试");

    var profile = profile(skuId, adjustment.currency());
    if (profile == null) throw missing("该 SKU 的成本构成不存在");
    var method = methodOf(profile.costMethod());
    var snapshot =
        snapshot(actor, skuId, adjustment.currency(), method, profile.packagingCost(), adjustment.effectiveFrom(), bearer);
    var revision =
        appendRevision(
            actor,
            skuId,
            adjustment.currency(),
            method,
            snapshot,
            "作废手工调整项：" + input.reason(),
            adjustment.effectiveFrom());
    sql.execute(
        "update sku_cost_profile set current_revision_id=? where tenant_id=" + TENANT + " and id=?",
        revision.id(),
        profile.id());

    return new CostSaved(
        profile.id(), revision.id(), revision.revisionNo(), adjustment.currency(), adjustment.effectiveFrom(), profile.version());
  }

  // ================================================================ 历史

  /** 成本变动历史（新到旧）。历史不可变 —— 没有 UPDATE 端点，也永远不会。 */
  public List<RevisionRow> revisions(UUID skuId, String currency) {
    var where = new StringBuilder(" where tenant_id=" + TENANT + " and sku_id=?");
    var binds = new ArrayList<Object>();
    binds.add(skuId);
    if (currency != null && !currency.isBlank()) {
      where.append(" and currency=?");
      binds.add(currency.trim().toUpperCase(Locale.ROOT));
    }
    var records =
        sql.fetch(
            "select id, revision_no, cost_method, currency, purchase_cost, first_mile_allocation,"
                + " packaging_cost, duty_cost, other_direct_cost, total_unit_cost, snapshot_complete,"
                + " missing_components, effective_from, change_reason, created_at"
                + " from sku_cost_revision"
                + where
                + " order by revision_no desc limit "
                + MAX_REVISIONS,
            binds.toArray());
    var out = new ArrayList<RevisionRow>();
    for (var record : records) {
      var method = methodOf(record.get("cost_method", String.class));
      out.add(
          new RevisionRow(
              record.get("id", UUID.class),
              record.get("revision_no", Integer.class),
              method.name(),
              method.label(),
              record.get("currency", String.class),
              record.get("purchase_cost", BigDecimal.class),
              record.get("first_mile_allocation", BigDecimal.class),
              record.get("packaging_cost", BigDecimal.class),
              record.get("duty_cost", BigDecimal.class),
              record.get("other_direct_cost", BigDecimal.class),
              record.get("total_unit_cost", BigDecimal.class),
              Boolean.TRUE.equals(record.get("snapshot_complete", Boolean.class)),
              record.get("missing_components", String.class),
              record.get("effective_from", OffsetDateTime.class),
              record.get("change_reason", String.class),
              record.get("created_at", OffsetDateTime.class)));
    }
    return out;
  }

  // ================================================================ 内部：快照

  /** 一次变更时的五个分项取值（历史快照的内容）。 */
  private record Snapshot(
      BigDecimal purchase,
      BigDecimal firstMile,
      BigDecimal duty,
      BigDecimal packaging,
      BigDecimal otherDirect,
      BigDecimal total,
      boolean complete,
      String missingComponents) {}

  /**
   * 算一次变更时的分项取值。
   *
   * <p>🔴 上游读不到时**不抛错**：变更本身是本地事实（成本法、参数、调整项都是本服务自有的），
   * 不该因为 warehouse 暂时不可达就保存不了。此时快照如实标 {@code complete=false}
   * 并点名缺哪几项 —— 「当时的完整成本」与「当时读到的成本」是两件事，
   * 历史里必须能分清。**读端点**才是硬失败（503）。
   *
   * <p><b>🔴 快照的口径窗口 = 截止 {@code effectiveFrom} 的回溯窗口，与明细页同口径</b>。
   *
   * <p>这一点很容易写错，而且写错了**历史就整个失去意义**：三种成本法都是**窗口口径**的算法
   * （移动加权是窗口内全部到货的加权平均，先进先出是窗口内按到货日消耗，批次是窗口内最新一批），
   * 所以「某一时刻的采购成本」= 「以那一刻为终点回溯一个窗口后算出来的值」。
   *
   * <p>如果这里只取 {@code effectiveFrom} **那一天**（窗口长度 = 1 天），那么绝大多数变更快照的
   * {@code purchase_cost} 都会是 null —— 因为那一天通常没有到货。后果是：
   * 快照永远 {@code complete=false}、{@code missing_components} 永远含 PURCHASE，
   * 而**「换成本法之后历史里能不能看出两个不同的采购成本」这条蓝图加粗句就永远验证不了**：
   * 三条历史里都是 null，看起来完全一样。
   *
   * <p>⇒ 这里用与明细页相同的默认回溯窗口（{@link #DEFAULT_WINDOW_DAYS} 天），
   * 保证「快照里的采购成本」与「明细页当时显示的采购成本」是同一个数 ——
   * 否则用户翻历史看到的数和当时的页面对不上，那比没有历史更糟。
   */
  private Snapshot snapshot(
      AccessService.Access actor,
      UUID skuId,
      String currency,
      CostMethod method,
      BigDecimal packaging,
      OffsetDateTime effectiveFrom,
      String bearer) {
    // 口径窗口：以生效时间为终点的回溯窗口（与明细页同口径，见上面的说明）。
    LocalDate windowEnd = effectiveFrom.toLocalDate();
    LocalDate windowStart = windowEnd.minusDays(DEFAULT_WINDOW_DAYS);
    var deliveryFacts = deliveries(List.of(skuId), windowStart, windowEnd);
    var lots = deliveryFacts.lots().getOrDefault(lotKey(skuId, currency), List.of());
    var weighted = SkuCostCompositionRules.weighted(method, lots);

    BigDecimal firstMile = null;
    BigDecimal duty = null;
    var missing = new ArrayList<String>();
    try {
      var upstream = readUpstream(skuId, windowStart, windowEnd, bearer);
      firstMile = firstMileUnit(upstream, skuId, currency);
      duty = dutyUnit(upstream, skuId, currency);
      if (firstMile == null) missing.add("FIRST_MILE");
      if (duty == null) missing.add("DUTY");
    } catch (ResponseStatusException e) {
      missing.add("FIRST_MILE");
      missing.add("DUTY");
    }

    BigDecimal otherDirect = BigDecimal.ZERO;
    for (var adjustment : adjustments(skuId)) {
      if (adjustment.isVoid()) continue;
      if (!currency.equals(adjustment.currency())) continue;
      otherDirect = otherDirect.add(adjustment.amount());
    }
    otherDirect = otherDirect.setScale(SkuCostCompositionRules.MONEY_SCALE, SkuCostCompositionRules.ROUNDING);

    if (weighted.unitCost() == null) missing.add("PURCHASE");
    if (packaging == null) missing.add("PACKAGING");

    var amounts = new ArrayList<BigDecimal>();
    if (weighted.unitCost() != null) amounts.add(weighted.unitCost());
    if (firstMile != null) amounts.add(firstMile);
    if (duty != null) amounts.add(duty);
    if (packaging != null) amounts.add(packaging);
    amounts.add(otherDirect);
    var total = SkuCostCompositionRules.total(amounts);

    return new Snapshot(
        weighted.unitCost(),
        firstMile,
        duty,
        packaging,
        otherDirect,
        total,
        missing.isEmpty(),
        String.join(",", missing));
  }

  /** 追加一条历史快照。版本号在同一事务里取 max+1，靠 UNIQUE 兜底，撞了就重试。 */
  private RevisionRef appendRevision(
      AccessService.Access actor,
      UUID skuId,
      String currency,
      CostMethod method,
      Snapshot snapshot,
      String changeReason,
      OffsetDateTime effectiveFrom) {
    for (int attempt = 0; attempt < REVISION_RETRY; attempt++) {
      int revisionNo =
          sql.fetchOne(
                  "select coalesce(max(revision_no),0)+1 from sku_cost_revision where tenant_id="
                      + TENANT
                      + " and sku_id=? and currency=?",
                  skuId,
                  currency)
              .get(0, Integer.class);
      UUID revisionId = Ids.next();
      try {
        sql.execute(
            "insert into sku_cost_revision(id,tenant_id,sku_id,currency,revision_no,cost_method,"
                + "purchase_cost,first_mile_allocation,packaging_cost,duty_cost,other_direct_cost,"
                + "total_unit_cost,snapshot_complete,missing_components,effective_from,change_reason,"
                + "created_by,created_at)"
                + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,now())",
            revisionId,
            actor.tenantId(),
            skuId,
            currency,
            revisionNo,
            method.name(),
            snapshot.purchase(),
            snapshot.firstMile(),
            snapshot.packaging(),
            snapshot.duty(),
            snapshot.otherDirect(),
            snapshot.total(),
            snapshot.complete(),
            snapshot.missingComponents(),
            effectiveFrom,
            changeReason,
            actor.userId());
        return new RevisionRef(revisionId, revisionNo);
      } catch (org.springframework.dao.DuplicateKeyException e) {
        // 版本号撞了（并发写入）：重新取 max+1 再试。
      }
    }
    throw conflict("成本变动历史写入冲突，请重试");
  }

  private record RevisionRef(UUID id, int revisionNo) {}

  // ================================================================ 内部：组件计算

  /** 读上游。**读不到就 503** —— 理由写「读不到上游」，不是「没有数据」。 */
  private CostBasisResult readUpstream(UUID skuId, LocalDate from, LocalDate to, String bearer) {
    try {
      return costBasis.allocations(bearer, List.of(skuId), from, to);
    } catch (CostBasisUnavailableException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
    }
  }

  /** 头程分摊分项：单位成本 = 分摊金额 ÷ 基础数量。 */
  private CostComponentRow firstMileComponent(CostBasisResult upstream, UUID skuId, String currency) {
    var unit = firstMileUnit(upstream, skuId, currency);
    var amounts = new ArrayList<AmountRow>();
    for (var allocation : upstream.firstMile()) {
      if (!allocation.skuId().equals(skuId)) continue;
      amounts.add(new AmountRow(allocation.currency(), allocation.amount()));
    }
    return new CostComponentRow(
        "FIRST_MILE",
        CostComponent.FIRST_MILE.label(),
        "WAREHOUSE_FIRST_MILE",
        "DERIVED",
        unit != null,
        false,
        unit == null
            ? "头程分摊取不到：warehouse 的授权只读端点没有返回该 SKU 在 "
                + currency
                + " 下的可分摊成本（窗口内没有已发出的头程发运单，或该单没有可分摊的费用）。"
            : null,
        unit,
        amounts,
        "来源：warehouse 头程发运单（运费 + 保险费 + 操作费，不含关税）按行数量摊到 SKU，再折成单位成本。",
        upstream.truncated()
            ? "🔴 上游单据数超过本端口的覆盖上限，结果只覆盖了最近的一批发运单。"
            : "");
  }

  /** 头程单位成本（同币种的那一条）。 */
  private BigDecimal firstMileUnit(CostBasisResult upstream, UUID skuId, String currency) {
    for (var allocation : upstream.firstMile()) {
      if (!allocation.skuId().equals(skuId)) continue;
      if (!currency.equals(allocation.currency())) continue;
      if (allocation.baseQuantity() == null || allocation.baseQuantity().signum() == 0) continue;
      return allocation
          .amount()
          .divide(
              allocation.baseQuantity(),
              SkuCostCompositionRules.MONEY_SCALE,
              SkuCostCompositionRules.ROUNDING);
    }
    return null;
  }

  /**
   * 关税分项。
   *
   * <p>🔴 计量单位：上游的关税行带自己的单位（报关口径），未必是 SKU 基础单位。
   * 折算不出来就**不给这一项** —— 宁可标「取不到」，也不给一个错的单位成本。
   */
  private CostComponentRow dutyComponent(
      CostBasisResult upstream, UUID skuId, String currency, String baseUnit) {
    var unit = dutyUnit(upstream, skuId, currency);
    var amounts = new ArrayList<AmountRow>();
    for (var allocation : upstream.duty()) {
      if (!allocation.skuId().equals(skuId)) continue;
      amounts.add(new AmountRow(allocation.currency(), allocation.dutyAmount()));
    }
    String reason = null;
    if (unit == null) {
      boolean hasOtherCurrency = false;
      boolean hasSameCurrency = false;
      for (var allocation : upstream.duty()) {
        if (!allocation.skuId().equals(skuId)) continue;
        if (currency.equals(allocation.currency())) hasSameCurrency = true;
        else hasOtherCurrency = true;
      }
      if (hasSameCurrency) {
        reason =
            "关税折不出单位成本：上游估算行的计量单位与 SKU 基础单位「"
                + baseUnit
                + "」之间没有换算版本（sku_unit_conversion role='SALE'）。";
      } else if (hasOtherCurrency) {
        reason =
            "关税取不到：warehouse 返回的关税估算在别的币种下，本构成计价 "
                + currency
                + "，本库没有汇率表，**不折算**（汇率是 12 财务的资产）。";
      } else {
        reason =
            "关税取不到：warehouse 的授权只读端点没有返回该 SKU 在 "
                + currency
                + " 下的关税估算（窗口内没有已锁定的估算单）。";
      }
    }
    return new CostComponentRow(
        "DUTY",
        CostComponent.DUTY.label(),
        "WAREHOUSE_DUTY_TAX",
        "DERIVED",
        unit != null,
        false,
        reason,
        unit,
        amounts,
        "来源：warehouse 关税估算单（10.5.1.3）的**关税**部分；增值税 / GST 不并入本项（归 5.2.1.2 到岸成本）。"
            + "**这是本服务口径，不是 12 财务的核算结果**（finance 服务当前不存在）。",
        "");
  }

  /** 关税单位成本：把上游按单位分组的数量折算到基础单位后相除。 */
  private BigDecimal dutyUnit(CostBasisResult upstream, UUID skuId, String currency) {
    BigDecimal dutyAmount = null;
    BigDecimal baseQuantity = BigDecimal.ZERO;
    boolean convertible = true;
    for (var allocation : upstream.duty()) {
      if (!allocation.skuId().equals(skuId)) continue;
      if (!currency.equals(allocation.currency())) continue;
      var factor = conversionFactor(skuId, allocation.uom());
      if (factor == null) {
        convertible = false;
        continue;
      }
      dutyAmount = (dutyAmount == null ? BigDecimal.ZERO : dutyAmount).add(allocation.dutyAmount());
      baseQuantity =
          baseQuantity.add(
              SkuCostCompositionRules.toBaseQuantity(allocation.quantity(), factor));
    }
    if (!convertible || dutyAmount == null || baseQuantity.signum() == 0) return null;
    return dutyAmount.divide(baseQuantity, SkuCostCompositionRules.MONEY_SCALE, SkuCostCompositionRules.ROUNDING);
  }

  /**
   * 计量单位 → 基础单位的换算系数。
   *
   * <p>单位等于基础单位时系数是 1；否则查 {@code sku_unit_conversion}（role='SALE'）。
   * <b>查不到就返回 null</b> —— 调用方据此把这一项标成「取不到」，而不是硬算一个数。
   */
  private BigDecimal conversionFactor(UUID skuId, String uom) {
    if (uom == null || uom.isBlank()) return null;
    var sku = skuInfo(skuId);
    if (sku != null && uom.equals(sku.baseUnit())) return BigDecimal.ONE;
    var record =
        sql.fetchOne(
            "select quantity_per_base from sku_unit_conversion where tenant_id="
                + TENANT
                + " and sku_id=? and role='SALE' and unit_code=?"
                + " and effective_from <= current_date"
                + " and (effective_to is null or effective_to >= current_date)"
                + " order by effective_from desc limit 1",
            skuId,
            uom);
    return record == null ? null : record.get(0, BigDecimal.class);
  }

  /** 上游的异币种金额：单列、**不折算**。 */
  private List<ForeignAmount> foreignAmounts(CostBasisResult upstream, UUID skuId, String currency) {
    var out = new ArrayList<ForeignAmount>();
    for (var allocation : upstream.firstMile()) {
      if (!allocation.skuId().equals(skuId)) continue;
      if (currency.equals(allocation.currency())) continue;
      out.add(
          new ForeignAmount(
              "FIRST_MILE",
              CostComponent.FIRST_MILE.label(),
              allocation.currency(),
              allocation.amount(),
              "未折算：汇率表属 12 财务（finance 服务），本服务不持有汇率，异币种金额不并入合计。"));
    }
    for (var allocation : upstream.duty()) {
      if (!allocation.skuId().equals(skuId)) continue;
      if (currency.equals(allocation.currency())) continue;
      out.add(
          new ForeignAmount(
              "DUTY",
              CostComponent.DUTY.label(),
              allocation.currency(),
              allocation.dutyAmount(),
              "未折算：汇率表属 12 财务（finance 服务），本服务不持有汇率，异币种金额不并入合计。"));
    }
    return out;
  }

  // ================================================================ 内部：数据读取

  private record SkuInfo(UUID id, String code, String name, String spuCode, String baseUnit) {}

  private SkuInfo skuInfo(UUID skuId) {
    var record =
        sql.fetchOne(
            "select s.id, s.code, s.name, sp.code as spu_code, s.base_unit"
                + " from sku s join spu sp on sp.tenant_id=s.tenant_id and sp.id=s.spu_id"
                + " where s.tenant_id="
                + TENANT
                + " and s.id=?",
            skuId);
    if (record == null) return null;
    return new SkuInfo(
        record.get("id", UUID.class),
        record.get("code", String.class),
        record.get("name", String.class),
        record.get("spu_code", String.class),
        record.get("base_unit", String.class));
  }

  private record ProfileRow(
      UUID id, String costMethod, String currency, BigDecimal packagingCost,
      OffsetDateTime effectiveFrom, OffsetDateTime updatedAt, long version) {}

  private ProfileRow profile(UUID skuId, String currency) {
    var record =
        sql.fetchOne(
            "select id, cost_method, currency, packaging_cost, effective_from, updated_at, version"
                + " from sku_cost_profile where tenant_id="
                + TENANT
                + " and sku_id=? and currency=?",
            skuId,
            currency);
    if (record == null) return null;
    return new ProfileRow(
        record.get("id", UUID.class),
        record.get("cost_method", String.class),
        record.get("currency", String.class),
        record.get("packaging_cost", BigDecimal.class),
        record.get("effective_from", OffsetDateTime.class),
        record.get("updated_at", OffsetDateTime.class),
        record.get("version", Long.class));
  }

  /** 该 SKU 的所有构成（用于「没传币种时取一个」与「币种必须已存在」的判定）。 */
  private List<ProfileRow> profiles(UUID skuId) {
    var records =
        sql.fetch(
            "select id, cost_method, currency, packaging_cost, effective_from, updated_at, version"
                + " from sku_cost_profile where tenant_id="
                + TENANT
                + " and sku_id=? order by currency",
            skuId);
    var out = new ArrayList<ProfileRow>();
    for (var record : records) {
      out.add(
          new ProfileRow(
              record.get("id", UUID.class),
              record.get("cost_method", String.class),
              record.get("currency", String.class),
              record.get("packaging_cost", BigDecimal.class),
              record.get("effective_from", OffsetDateTime.class),
              record.get("updated_at", OffsetDateTime.class),
              record.get("version", Long.class)));
    }
    return out;
  }

  /** 计价币种：显式指定优先；否则用该 SKU 已有的构成（多个时取第一个并说清）。 */
  private String resolveCurrency(UUID skuId, String currency) {
    if (currency != null && !currency.isBlank()) {
      return currency.trim().toUpperCase(Locale.ROOT);
    }
    var existing = profiles(skuId);
    if (existing.isEmpty()) {
      throw badRequest(
          "该 SKU 还没有成本构成：请先保存一条构成（成本法 + 计价币种），"
              + "或用 ?currency= 指定一个计价币种");
    }
    return existing.get(0).currency();
  }

  /**
   * 到货记录 → 批次（含基础单位折算）。
   *
   * <p>折算不出来的行**单独计数**，不静默丢弃：丢掉几行却报一个完整数字，
   * 会让成本看起来比实际低。计数的行会挂到 {@code excluded} 里，
   * 采购成本分项因此被标为「部分」，合计随之成为「已知部分合计」。
   */
  private DeliveryFacts deliveries(List<UUID> skuIds, LocalDate from, LocalDate to) {
    var lots = new LinkedHashMap<String, List<SkuCostCompositionRules.DeliveryLot>>();
    var excluded = new LinkedHashMap<UUID, Integer>();
    if (skuIds.isEmpty()) return new DeliveryFacts(lots, excluded);
    var records =
        sql.fetch(
            "select vd.sku_id, vd.id, vd.delivery_ref, vd.received_date, vd.currency, vd.quantity,"
                + " vd.amount, vd.purchase_unit_code, s.base_unit,"
                + " (select c.quantity_per_base from sku_unit_conversion c"
                + "   where c.tenant_id=vd.tenant_id and c.sku_id=vd.sku_id and c.role='PURCHASE'"
                + "     and c.unit_code=vd.purchase_unit_code"
                + "     and c.effective_from <= vd.received_date"
                + "     and (c.effective_to is null or c.effective_to >= vd.received_date)"
                + "   order by c.effective_from desc limit 1) as quantity_per_base"
                + " from vendor_delivery_record vd"
                + " join sku s on s.tenant_id=vd.tenant_id and s.id=vd.sku_id"
                + " where vd.tenant_id="
                + TENANT
                + " and vd.sku_id in ("
                + placeholders(skuIds.size())
                + ") and not vd.is_void"
                + " and vd.received_date between ? and ?"
                + " order by vd.received_date, vd.id",
            concat(skuIds, from, to));
    for (var record : records) {
      var skuId = record.get("sku_id", UUID.class);
      var currency = record.get("currency", String.class);
      var quantity = record.get("quantity", BigDecimal.class);
      var baseUnit = record.get("base_unit", String.class);
      var purchaseUnit = record.get("purchase_unit_code", String.class);
      BigDecimal factor = null;
      if (purchaseUnit != null && !purchaseUnit.isBlank()) {
        if (purchaseUnit.equals(baseUnit)) {
          factor = BigDecimal.ONE;
        } else {
          factor = record.get("quantity_per_base", BigDecimal.class);
        }
      }
      var baseQuantity = SkuCostCompositionRules.toBaseQuantity(quantity, factor);
      if (baseQuantity == null) {
        // 折不出基础数量 ⇒ 这一行不能用（不是「0 成本」，是「算不出」）。
        excluded.merge(skuId, 1, Integer::sum);
        continue;
      }
      lots.computeIfAbsent(lotKey(skuId, currency), k -> new ArrayList<>())
          .add(
              new SkuCostCompositionRules.DeliveryLot(
                  record.get("id", UUID.class),
                  record.get("delivery_ref", String.class),
                  record.get("received_date", LocalDate.class),
                  quantity,
                  baseQuantity,
                  record.get("amount", BigDecimal.class),
                  currency));
    }
    return new DeliveryFacts(lots, excluded);
  }

  /** 到货事实：可用的批次 + 被排除的记录数。 */
  private record DeliveryFacts(
      Map<String, List<SkuCostCompositionRules.DeliveryLot>> lots, Map<UUID, Integer> excluded) {}

  // ================================================================ 内部：报价参考

  /**
   * 供应商报价参考（**只读参考，不是采购成本，不计入合计**）。
   *
   * <p>V32 的纪律：同一优先级有多条有效报价时**必须报错让用户选，不随机取一条**。
   * 这里照办 —— 有歧义时 {@code available=false} 并说明，不替用户挑。
   */
  private QuoteReference quoteReference(UUID skuId) {
    var records =
        sql.fetch(
            "select q.supplier_role_id, q.currency, q.purchase_unit_code, q.status,"
                + " q.valid_from, q.valid_to, q.priority,"
                + " (select t.unit_price from supplier_quote_price_tier t"
                + "   where t.tenant_id=q.tenant_id and t.quote_id=q.id"
                + "   order by t.min_quantity asc limit 1) as first_unit_price,"
                + " (select t.min_quantity from supplier_quote_price_tier t"
                + "   where t.tenant_id=q.tenant_id and t.quote_id=q.id"
                + "   order by t.min_quantity asc limit 1) as first_min_quantity"
                + " from supplier_quote q where q.tenant_id="
                + TENANT
                + " and q.sku_id=? and q.status='ACTIVE'"
                + " and q.valid_from <= current_date"
                + " and (q.valid_to is null or q.valid_to >= current_date)"
                + " order by q.priority, q.valid_from desc",
            skuId);
    if (records.isEmpty()) {
      return new QuoteReference(
          false,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          "该 SKU 没有有效报价（supplier_quote）。",
          "**报价不是采购成本**：报价是「还没下单时谈下来的价」，实际成交价来自 06 采购（6.3，当前未交付）。"
              + "这里只作参考展示，不计入任何合计。");
    }
    var best = records.get(0);
    int bestPriority = best.get("priority", Integer.class);
    long samePriority = records.stream().filter(r -> r.get("priority", Integer.class).equals(bestPriority)).count();
    if (samePriority > 1) {
      return new QuoteReference(
          false,
          best.get("currency", String.class),
          null,
          null,
          best.get("purchase_unit_code", String.class),
          best.get("supplier_role_id", UUID.class).toString(),
          best.get("status", String.class),
          best.get("valid_from", java.sql.Date.class) == null
              ? null
              : best.get("valid_from", java.sql.Date.class).toString(),
          null,
          "有 " + samePriority + " 条同优先级的有效报价 —— 按 3.6.2.1 的规则必须由人选择，本模块不替你挑。",
          "**报价不是采购成本**：报价是「还没下单时谈下来的价」，实际成交价来自 06 采购（6.3，当前未交付）。"
              + "这里只作参考展示，不计入任何合计。");
    }
    return new QuoteReference(
        true,
        best.get("currency", String.class),
        best.get("first_unit_price", BigDecimal.class),
        best.get("first_min_quantity", BigDecimal.class),
        best.get("purchase_unit_code", String.class),
        best.get("supplier_role_id", UUID.class).toString(),
        best.get("status", String.class),
        best.get("valid_from", java.sql.Date.class) == null
            ? null
            : best.get("valid_from", java.sql.Date.class).toString(),
        best.get("valid_to", java.sql.Date.class) == null
            ? null
            : best.get("valid_to", java.sql.Date.class).toString(),
        null,
        "**报价不是采购成本**：这是供应商的报价（起订量档的单价），实际成交价来自 06 采购"
            + "（6.3，当前未交付）。本项**只作参考，不计入任何合计**。");
  }

  // ================================================================ 内部：工具

  private List<AdjustmentRow> adjustments(UUID skuId) {
    var records =
        sql.fetch(
            "select id, component, amount, currency, reason, effective_from, is_void, void_reason,"
                + " voided_at, created_at from sku_cost_adjustment where tenant_id="
                + TENANT
                + " and sku_id=? order by effective_from desc, created_at desc",
            skuId);
    var out = new ArrayList<AdjustmentRow>();
    for (var record : records) {
      var component = CostComponent.parse(record.get("component", String.class));
      out.add(
          new AdjustmentRow(
              record.get("id", UUID.class),
              record.get("component", String.class),
              component == null ? record.get("component", String.class) : component.label(),
              record.get("amount", BigDecimal.class),
              record.get("currency", String.class),
              record.get("reason", String.class),
              record.get("effective_from", OffsetDateTime.class),
              Boolean.TRUE.equals(record.get("is_void", Boolean.class)),
              record.get("void_reason", String.class),
              record.get("voided_at", OffsetDateTime.class),
              record.get("created_at", OffsetDateTime.class)));
    }
    return out;
  }

  private AdjustmentRow adjustment(UUID skuId, UUID adjustmentId) {
    for (var adjustment : adjustments(skuId)) {
      if (adjustment.id().equals(adjustmentId)) return adjustment;
    }
    return null;
  }

  private int revisionCount(UUID skuId, String currency) {
    return sql.fetchOne(
            "select count(*) from sku_cost_revision where tenant_id="
                + TENANT
                + " and sku_id=? and currency=?",
            skuId,
            currency)
        .get(0, Integer.class);
  }

  private Map<String, BigDecimal> otherDirectByCurrency(List<UUID> skuIds) {
    var out = new LinkedHashMap<String, BigDecimal>();
    if (skuIds.isEmpty()) return out;
    var records =
        sql.fetch(
            "select sku_id, currency, sum(amount) as total from sku_cost_adjustment where tenant_id="
                + TENANT
                + " and not is_void and sku_id in ("
                + placeholders(skuIds.size())
                + ") group by sku_id, currency",
            skuIds.toArray());
    for (var record : records) {
      out.put(
          lotKey(record.get("sku_id", UUID.class), record.get("currency", String.class)),
          record.get("total", BigDecimal.class));
    }
    return out;
  }

  private long count(String sqlText, Object... params) {
    return sql.fetchOne(sqlText, params).get(0, Long.class);
  }

  private static String placeholders(int n) {
    return String.join(",", java.util.Collections.nCopies(n, "?"));
  }

  private static Object[] concat(List<UUID> skuIds, Object... rest) {
    var out = new ArrayList<Object>();
    out.addAll(skuIds);
    out.addAll(List.of(rest));
    return out.toArray();
  }

  private static String lotKey(UUID skuId, String currency) {
    return skuId + "|" + currency;
  }

  private static CostMethod methodOf(String value) {
    var method = CostMethod.parse(value);
    return method == null ? CostMethod.MOVING_AVERAGE : method;
  }

  private static LocalDate windowFrom(CostListQuery query) {
    return query.windowFrom() == null ? LocalDate.now().minusDays(DEFAULT_WINDOW_DAYS) : query.windowFrom();
  }

  private static LocalDate windowTo(CostListQuery query) {
    return query.windowTo() == null ? LocalDate.now() : query.windowTo();
  }

  private static String windowNote(CostListQuery query) {
    return "成本口径窗口："
        + windowFrom(query)
        + " ~ "
        + windowTo(query)
        + "（默认最近 "
        + DEFAULT_WINDOW_DAYS
        + " 天）。窗口变了，同一个 SKU 的采购成本就会变 —— 页面上必须显示窗口。";
  }

  private static ResponseStatusException missing(String what) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what);
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
