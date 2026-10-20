package com.starcon.catalog;

import static com.starcon.catalog.BatchPriceAdjustmentModels.*;

import com.starcon.catalog.BatchPriceAdjustmentModels.AdjustMode;
import com.starcon.catalog.BatchPriceAdjustmentModels.BatchPriceAdjustmentDetail;
import com.starcon.catalog.BatchPriceAdjustmentModels.BatchPriceAdjustmentExecuteRequest;
import com.starcon.catalog.BatchPriceAdjustmentModels.BatchPriceAdjustmentItem;
import com.starcon.catalog.BatchPriceAdjustmentModels.BatchPriceAdjustmentItemPage;
import com.starcon.catalog.BatchPriceAdjustmentModels.BatchPriceAdjustmentPage;
import com.starcon.catalog.BatchPriceAdjustmentModels.BatchPriceAdjustmentPreview;
import com.starcon.catalog.BatchPriceAdjustmentModels.BatchPriceAdjustmentRequest;
import com.starcon.catalog.BatchPriceAdjustmentModels.ItemQuery;
import com.starcon.catalog.BatchPriceAdjustmentModels.ItemStatus;
import com.starcon.catalog.BatchPriceAdjustmentModels.ShopRef;
import com.starcon.catalog.BatchPriceAdjustmentModels.Sort;
import com.starcon.catalog.PricingTemplateModels.ApplyRequest;
import com.starcon.catalog.PricingTemplateModels.TemplateDetail;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.4.1.2 批量调价：对选中的一批 listing 统一改价（固定金额 / 百分比 / 按模板），
 * **必须先看预览再执行**。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：对选中的一批 listing 统一改价（固定金额/百分比/按模板），支持预览影响范围（涉及多少店铺、多少商品、预计利润变化）。
 *
 * **输入**：目标集合；调价参数；预览确认。
 *
 * **输出**：批量调价单 + 影响预览报告。
 *
 * **关系**：上游 = 5.3.1.2、4.1.1.1；下游 = 5.4.2.1。**必须先看预览再执行**，预览要包含"改完后预计亏损的商品数"这类反向指标。
 *
 * </blockquote>
 *
 * <h2>🔴 本类的六条纪律</h2>
 *
 * <ol>
 *   <li><b>🔴 零个测算算法</b>：改价前后的毛利额与毛利率全部来自 5.3.1.1 的引擎
 *       （{@link ProfitCalculatorService#calculate}）；「按模板」的建议价来自 5.3.2.1 的
 *       {@link PricingTemplateService#apply}。本模块**不重写第二套算术**。</li>
 *   <li><b>🔴 预览无副作用</b>：预览跑在一个**注定回滚**的事务里
 *       （{@link #preview}）—— 所以它在**结构上**不可能落库，哪怕「按模板」方式会调用
 *       5.3.2.1 的 apply()（它会写应用留痕）。留痕是 5.3.2.1 的写入所有权，
 *       预览阶段写一条「模板被应用过」的留痕是**假事实**。</li>
 *   <li><b>🔴 执行时重算预览并比对令牌</b>：客户端带回的 {@code previewToken} 必须与
 *       服务端**当场重算**的令牌逐字相同。对不上 ⇒ 409 拒绝（预览之后目标集合或当前价变了），
 *       而不是照着过期的数字改价。</li>
 *   <li><b>🔴 取不到当前价就跳过，绝不拿 0 顶</b>：没有价格观测的 listing 记
 *       {@code SKIPPED}，旧价与前后利润全是 null —— 0 会被当成「免费」，
 *       而它其实是一个取不到的事实。</li>
 *   <li><b>🔴 成本不完整就不给利润数字</b>：沿用 5.3.1.1 的决策就绪闸 ——
 *       毛利额与毛利率是 null，亏损标记也是 null（不可信，不是 false）。</li>
 *   <li><b>租户只来自已认证成员关系</b>（{@link AccessService}），不读 {@code X-Tenant-Id} 头。
 *       每条 SQL 的 where 都带显式 {@code tenant_id=} 绑定，RLS 再兜一道。</li>
 * </ol>
 */
@Service
public class BatchPriceAdjustmentService {

  /** 租户上下文取法：RLS 已按它隔离，这里带上只是让执行计划走租户前缀的索引。 */
  private static final String TENANT = "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 台账页大小上限：与相邻模块一致。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 成本口径窗口的默认跨度（天）。与 5.3.1.1 / 5.2.1.1 一致。 */
  private static final int DEFAULT_WINDOW_DAYS = 90;

  private final DSLContext sql;
  private final ProfitCalculatorService calculator;
  private final PricingTemplateService templates;
  private final ChannelPlatformPort platforms;
  private final AccessService access;
  private final TransactionTemplate previewRollback;

  public BatchPriceAdjustmentService(
      DSLContext sql,
      ProfitCalculatorService calculator,
      PricingTemplateService templates,
      ChannelPlatformPort platforms,
      AccessService access,
      PlatformTransactionManager manager) {
    this.sql = sql;
    this.calculator = calculator;
    this.templates = templates;
    this.platforms = platforms;
    this.access = access;
    // 预览的事务：参与外层事务（PROPAGATION_REQUIRED），结束时**整体回滚**。
    this.previewRollback = new TransactionTemplate(manager);
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：listing 的定义、售价来源、上游真实交付状态、预览闸、反向指标、舍入与上限。
   *
   * <p>页面上把它固定显示在顶部 —— 蓝图那句「**必须先看预览再执行**」意味着运营会拿预览里的
   * 数字去决定「这批到底改不改」，所以口径必须**摆在数字旁边**，不是躺在文档里。
   */
  public BatchPriceAdjustmentCapabilities capabilities() {
    return new BatchPriceAdjustmentCapabilities(
        List.of(),
        "🔴 **一个 listing = 一个 SKU 在一个店铺**（平台 + 站点）。"
            + "本仓的 listing 事实属 channel 服务（4.1.1.1），而 catalog **没有** listing 的本地投影，"
            + "且 AGENTS.md 禁止跨服务直接查对方的表 —— 所以本模块**不新建任何 listing 影子表**"
            + "去假装上游已交付。在 05 定价卷里唯一真实存在的 listing 级价格事实是"
            + "**5.5.2.1 异常告警的价格观测台账**（price_anomaly_observation）："
            + "一行 = 一个 SKU 在一个站点上的一次价格观测。"
            + "本模块把「目标集合」定义成 **SKU 范围（分类 / 品牌 / 显式选中）× 店铺清单**，"
            + "落成 (SKU × 店铺) 的 listing 行。",
        "🔴 **当前价来源**：价格观测台账里该 (SKU, 店铺) 的**最新一条**观测 —— "
            + "优先我方价（internal_price），没有就退到平台在售价（platform_price），"
            + "并在明细里标出用的是哪一种、是哪一刻观测到的。"
            + "**取不到就整行跳过**（跳过原因写明），绝不用 0 顶 —— 0 会被当成「免费」。"
            + "台账是**只追加**的，所以「最新一条」是一个时间点而不是「当前价」。",
        "上游 = **5.3.1.2 批量测算**（同服务内，V230）与 **5.3.2.1 定价模板**（同服务内，V231）。"
            + "本模块**复用**它们的成果，不重写：改价前后的毛利额与毛利率来自 **5.3.1.1 利润测算器**的引擎，"
            + "「按模板」的建议价来自 **5.3.2.1** 的 apply。"
            + "⚠️ 5.3.1.1 的上游缺口在本模块同样存在：5.2.3.1 平台费用与 5.2.3.2 物流与头程费用未交付，"
            + "所以平台费率与物流单价只能作为**手工假设**由人填（页面上标「手工假设」）。",
        "下游 = **5.4.2.1 平台价格同步**（未交付）。它会拿本模块调价单里的**新价**去回写平台。"
            + "🔴 本模块**不写** 5.5.2.1 的价格观测台账（那张表有唯一的写入所有者），"
            + "「改完之后的新价」记在本模块自己的调价单里 —— 蓝图 5.4.1.1 对调价单的要求本来就是"
            + "「记录变更前后值」。所以 5.3.1.2 的售价口径在下一次批量测算时**仍会看到旧价**："
            + "价目表（5.1）未交付 ⇒ 没有一个大家共用的「当前价」事实源。",
        "🔴 **预览闸**：预览**不落库**，它只算出一个「预览令牌」（内容哈希）。"
            + "执行时服务端**当场重算**一遍预览并逐字比对令牌 —— 对不上就是"
            + "「预览之后目标集合或当前价变了」，此时**拒绝执行（409）**，"
            + "而不是照着过期的数字改价。令牌不含随机量与时间戳，同一份输入永远算出同一个令牌。",
        "🔴 **反向指标**（蓝图加粗句要求）：预览报告里有"
            + "**改价后的亏损 listing 数**与**改价后预计亏损的商品数**"
            + "（至少有一个 listing 亏损的去重 SKU 数）。"
            + "只看「预计利润变化」是不够的 —— 一批 listing 整体利润上升，"
            + "也可能是因为其中几个 SKU 从小赚变成巨亏，而那几个正是要清的品。",
        "🔴 **舍入**：固定金额与百分比两种方式的换算结果按 **2 位小数 HALF_UP** 落定"
            + "（对外标价就是两位小数的钱，2.1.1.1 平台目录的 currencyScale 也是 2）；"
            + "内部金额 4 位小数、费率与毛利率 6 位小数，HALF_UP（与 5.3.1.1 逐字相同）。"
            + "「按模板」方式的建议价**原样**采用 5.3.2.1 的结果，不二次舍入 —— "
            + "二次舍入会让它与「一键应用」页上的建议价对不上。**全程 BigDecimal，零浮点。**",
        "「跳过」在本模块有四种：① 该 listing 没有价格观测；② 价格观测的币种与本次调价的币种不同"
            + "（**绝不按 1:1 折算**）；③ 调完不是正价；④ 「按模板」方式下 5.3.2.1 拒绝了这一行"
            + "（成本不完整 / 商品阶段不符 / 适用对象不匹配 —— 原因原样带回）。"
            + "跳过的行**也在明细里**并写明原因：「这个范围里有 40 个 listing 调不了」是运营必须看见的事实。",
        "一次批量调价最多覆盖 **" + BatchPriceAdjustmentRules.MAX_TARGET_LISTINGS + " 个 listing**"
            + "（SKU × 店铺）：超了**明确报错**让你缩小范围，不静默截断"
            + "（每个 listing 至少要跑两次 5.3.1.1 的测算，每次要跨服务读成本构成；"
            + "截断了运营会以为「全改过了」）。",
        "权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。"
            + "**租户不来自任何请求头** —— 只来自已认证的成员关系，"
            + "伪造的 {@code X-Tenant-Id} 头无效。");
  }

  /** 目标店铺（站点）列表：来自 2.1.1.1 平台目录。 */
  public List<ProfitCalculatorModels.SiteOption> sites(String bearer) {
    var out = new ArrayList<ProfitCalculatorModels.SiteOption>();
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

  // ================================================================ 预览

  /**
   * 影响预览（**无副作用**）。
   *
   * <p>🔴 整个预览跑在一个**注定回滚**的事务里：它自己开事务、自己绑定租户上下文，
   * 结束时把事务标记为 rollback-only ⇒ **任何**写入都不会落库。
   * 这不是「我小心地不写」，而是**结构上不可能写** ——
   * 「按模板」方式必须调用 5.3.2.1 的 apply()（它会写应用留痕），
   * 而预览阶段留下一条「模板被应用过」的留痕是**假事实**。
   *
   * <p>🔴 因此本方法**必须在事务外调用**（它要自己开事务）：
   * 若它参与了一个外层事务，那个外层事务会被标记成 rollback-only，
   * 而 Spring 在外层提交时会抛 {@code UnexpectedRollbackException} ——
   * 预览会变成一个报错接口。所以这里显式检查并给出明确说明，而不是让人踩坑。
   */
  public BatchPriceAdjustmentPreview preview(
      AccessService.Access actor, BatchPriceAdjustmentRequest request, String bearer) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "批量调价预览必须在自己的事务里跑（它要整体回滚），不能在别的事务里调用。");
    }
    return previewRollback.execute(
        status -> {
          // 标记回滚：预览期间发生的任何写入（包括 5.3.2.1 的应用留痕）都不落库。
          status.setRollbackOnly();
          // 🔴 租户与权限上下文由 {@link AccessService#run} 绑定（它参与本事务）：
          //   spu 的 data_scope 策略要读 app.grants / app.actor / app.department /
          //   app.permission —— 少绑一个，预览就会看到 0 个 SKU。
          return access.run(
              actor,
              "catalog.spu.update",
              () -> computePreview(actor, request, bearer));
        });
  }

  /**
   * 执行批量调价（**必须先预览**）。
   *
   * <p>🔴 流程：重算预览 → 比对令牌 → 落库（调价单 + 明细 + 预览报告快照）。
   * 重算与落库在**同一个事务**里，所以中途失败不会留下半张单子。
   */
  public BatchPriceAdjustmentDetail execute(
      AccessService.Access actor, BatchPriceAdjustmentExecuteRequest request, String bearer) {
    if (request == null || request.request() == null) {
      throw ProfitCalculatorService.badRequest("批量调价请求不能为空");
    }
    if (request.previewToken() == null || request.previewToken().isBlank()) {
      throw ProfitCalculatorService.badRequest(
          "必须先预览：蓝图那条「**必须先看预览再执行**」是硬约束 —— "
              + "请先 POST /catalog/batch-price-adjustments/preview 拿到预览令牌，再带着它执行。");
    }
    var preview = computePreview(actor, request.request(), bearer);
    if (!preview.previewToken().equals(request.previewToken().trim())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "🔴 预览已失效：现在的目标集合或当前价与你预览时不一样了"
              + "（预览令牌 "
              + preview.previewToken()
              + " ≠ 你带回的 "
              + request.previewToken().trim()
              + "）。本模块**拒绝**照着过期的数字改价 —— 请重新预览一次再确认。");
    }
    if (preview.listingCount() == 0) {
      throw ProfitCalculatorService.badRequest(
          "目标集合里一个 listing 都没有 —— 没有可调价的对象，请先确认店铺与 SKU 范围。");
    }
    if (preview.willAdjustCount() == 0) {
      throw ProfitCalculatorService.badRequest(
          "目标集合里没有一行会被调价（"
              + preview.skippedCount()
              + " 行全部被跳过）—— 全部跳过的批次不该落成一张调价单。请先看预览里的跳过原因。");
    }

    var plan = planOf(request.request());
    UUID id = UUID.randomUUID();
    OffsetDateTime createdAt = OffsetDateTime.now();
    sql.execute(
        "insert into batch_price_adjustment(id,tenant_id,adjust_mode,adjust_amount,adjust_rate,"
            + "template_id,template_code,template_name,currency,cost_currency,fx_rate,"
            + "scope_category,scope_brand,scope_sku_ids,scope_shops,quantity,platform_fee_rate,"
            + "platform_fee_fixed,logistics_cost_per_unit,monthly_fixed_cost,cost_window_from,"
            + "cost_window_to,shop_count,sku_count,listing_count,will_adjust_count,skipped_count,"
            + "loss_before_count,loss_after_count,loss_after_sku_count,profit_before_total,"
            + "profit_after_total,profit_delta_total,avg_margin_before,avg_margin_after,"
            + "preview_token,note,created_by,created_at)"
            + " values(?::uuid,?::uuid,?,?,?,?::uuid,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::uuid,?::timestamptz)",
        id,
        actor.tenantId(),
        plan.mode().name(),
        plan.adjustAmount(),
        plan.adjustRate(),
        plan.templateId(),
        plan.templateCode(),
        plan.templateName(),
        plan.currency(),
        plan.costCurrency(),
        plan.fxRate(),
        plan.category(),
        plan.brand(),
        plan.skuIdsText(),
        plan.shopsText(),
        plan.quantity(),
        plan.platformFeeRate(),
        plan.platformFeeFixed(),
        plan.logisticsCostPerUnit(),
        plan.monthlyFixedCost(),
        plan.costWindowFrom(),
        plan.costWindowTo(),
        preview.shopCount(),
        preview.skuCount(),
        preview.listingCount(),
        preview.willAdjustCount(),
        preview.skippedCount(),
        preview.lossBeforeCount(),
        preview.lossAfterCount(),
        preview.lossAfterSkuCount(),
        preview.profitBeforeTotal(),
        preview.profitAfterTotal(),
        preview.profitDeltaTotal(),
        preview.avgMarginBefore(),
        preview.avgMarginAfter(),
        preview.previewToken(),
        plan.note(),
        actor.userId(),
        createdAt);

    for (var item : preview.items()) {
      sql.execute(
          "insert into batch_price_adjustment_item(id,tenant_id,adjustment_id,sku_id,platform_code,"
              + "site_code,currency,status,skip_reason,old_price,new_price,price_delta,price_source,"
              + "price_observed_at,gross_profit_before,gross_margin_before,gross_profit_after,"
              + "gross_margin_after,decision_ready,loss_before,loss_after,created_at)"
              + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?,?,?,?,?,?::timestamptz)",
          UUID.randomUUID(),
          actor.tenantId(),
          id,
          item.skuId(),
          item.platformCode(),
          item.siteCode(),
          item.currency(),
          item.status().name(),
          item.skipReason(),
          item.oldPrice(),
          item.newPrice(),
          item.priceDelta(),
          item.priceSource(),
          item.priceObservedAt(),
          item.grossProfitBefore(),
          item.grossMarginBefore(),
          item.grossProfitAfter(),
          item.grossMarginAfter(),
          item.decisionReady(),
          item.lossBefore(),
          item.lossAfter(),
          createdAt);
    }
    return getAdjustment(id);
  }

  // ================================================================ 调价单台账

  /** 调价单列表（真分页）。 */
  public BatchPriceAdjustmentPage listAdjustments(int page, int size) {
    int limited = Math.min(Math.max(size <= 0 ? 20 : size, 1), MAX_PAGE_SIZE);
    int offset = Math.max(page, 0) * limited;
    var rows =
        sql.fetch(
            "select a.id, a.adjust_mode, a.adjust_amount, a.adjust_rate, a.template_id,"
                + " a.template_code, a.template_name, a.currency, a.cost_currency, a.fx_rate,"
                + " a.scope_category, a.scope_brand, a.scope_sku_ids, a.scope_shops, a.quantity,"
                + " a.platform_fee_rate, a.platform_fee_fixed, a.logistics_cost_per_unit,"
                + " a.monthly_fixed_cost, a.cost_window_from, a.cost_window_to, a.shop_count,"
                + " a.sku_count, a.listing_count, a.will_adjust_count, a.skipped_count,"
                + " a.loss_before_count, a.loss_after_count, a.loss_after_sku_count,"
                + " a.profit_before_total, a.profit_after_total, a.profit_delta_total,"
                + " a.avg_margin_before, a.avg_margin_after, a.preview_token, a.note, a.created_by,"
                + " a.created_at"
                + " from batch_price_adjustment a"
                + " where a.tenant_id="
                + TENANT
                + " order by a.created_at desc, a.id"
                + " limit ? offset ?",
            limited,
            offset);
    long total =
        sql.fetchOne("select count(*) from batch_price_adjustment where tenant_id=" + TENANT)
            .get(0, Long.class);
    var items = new ArrayList<BatchPriceAdjustmentDetail>();
    for (var row : rows) items.add(adjustmentDetail(row));
    return new BatchPriceAdjustmentPage(items, Math.max(page, 0), limited, total);
  }

  /** 调价单详情。 */
  public BatchPriceAdjustmentDetail getAdjustment(UUID id) {
    var row =
        sql.fetchOne(
            "select a.id, a.adjust_mode, a.adjust_amount, a.adjust_rate, a.template_id,"
                + " a.template_code, a.template_name, a.currency, a.cost_currency, a.fx_rate,"
                + " a.scope_category, a.scope_brand, a.scope_sku_ids, a.scope_shops, a.quantity,"
                + " a.platform_fee_rate, a.platform_fee_fixed, a.logistics_cost_per_unit,"
                + " a.monthly_fixed_cost, a.cost_window_from, a.cost_window_to, a.shop_count,"
                + " a.sku_count, a.listing_count, a.will_adjust_count, a.skipped_count,"
                + " a.loss_before_count, a.loss_after_count, a.loss_after_sku_count,"
                + " a.profit_before_total, a.profit_after_total, a.profit_delta_total,"
                + " a.avg_margin_before, a.avg_margin_after, a.preview_token, a.note, a.created_by,"
                + " a.created_at"
                + " from batch_price_adjustment a"
                + " where a.tenant_id="
                + TENANT
                + " and a.id=?",
            id);
    if (row == null) throw ProfitCalculatorService.notFound("批量调价单不存在");
    return adjustmentDetail(row);
  }

  /** 明细列表（真分页 + 真筛选 + 真排序）。 */
  public BatchPriceAdjustmentItemPage listItems(UUID adjustmentId, ItemQuery query) {
    requireVisibleAdjustment(adjustmentId);
    var filter = filterOf(adjustmentId, query);
    long total =
        sql.fetchOne("select count(*)" + filter.from() + filter.where(), filter.binds().toArray())
            .get(0, Long.class);
    int limited = Math.min(Math.max(query.size() <= 0 ? 20 : query.size(), 1), MAX_PAGE_SIZE);
    int offset = Math.max(query.page(), 0) * limited;
    var rows =
        sql.fetch(
            "select i.id, i.adjustment_id, i.sku_id, k.code as sku_code, k.name as sku_name,"
                + " i.platform_code, i.site_code, i.currency, i.status, i.skip_reason, i.old_price,"
                + " i.new_price, i.price_delta, i.price_source, i.price_observed_at,"
                + " i.gross_profit_before, i.gross_margin_before, i.gross_profit_after,"
                + " i.gross_margin_after, i.decision_ready, i.loss_before, i.loss_after"
                + filter.from()
                + filter.where()
                + filter.order()
                + " limit ? offset ?",
            concat(filter.binds(), List.of(limited, offset)).toArray());
    var items = new ArrayList<BatchPriceAdjustmentItem>();
    for (var row : rows) items.add(itemOf(row));
    return new BatchPriceAdjustmentItemPage(items, Math.max(query.page(), 0), limited, total);
  }

  /**
   * 调价单必须存在且属于当前租户，否则 404。
   *
   * <p>🔴 不校验就会把「单子不存在」与「单子存在但一条明细都没有」都返回成**空列表** ——
   * 前端于是显示「这张单子没有明细」，而真相是「这张单子根本不是你的」。
   * 对越权访问返回 404 而不是 403，也是不泄露「存在」这个事实。
   */
  private void requireVisibleAdjustment(UUID adjustmentId) {
    var row =
        sql.fetchOne(
            "select 1 from batch_price_adjustment where tenant_id=" + TENANT + " and id=?",
            adjustmentId);
    if (row == null) throw ProfitCalculatorService.notFound("批量调价单不存在");
  }

  // ================================================================ 内部：预览计算

  /** 归一化后的调价计划。 */
  private record Plan(
      AdjustMode mode,
      BigDecimal adjustAmount,
      BigDecimal adjustRate,
      UUID templateId,
      String templateCode,
      String templateName,
      String currency,
      String costCurrency,
      BigDecimal fxRate,
      List<String[]> shops,
      String shopsText,
      List<UUID> skuIds,
      String skuIdsText,
      String category,
      String brand,
      BigDecimal quantity,
      BigDecimal platformFeeRate,
      BigDecimal platformFeeFixed,
      BigDecimal logisticsCostPerUnit,
      BigDecimal monthlyFixedCost,
      LocalDate costWindowFrom,
      LocalDate costWindowTo,
      BigDecimal daysOfCover,
      BigDecimal competitorReferencePrice,
      String competitorCurrency,
      String note) {}

  /** 目标集合里的一个 SKU。 */
  private record TargetSku(UUID id, String code, String name) {}

  /** 取到的当前价。 */
  private record Price(BigDecimal amount, String source, String currency, OffsetDateTime observedAt) {}

  /**
   * 算预览（**不落库**）。
   *
   * <p>预览与执行走的是**同一个**方法 —— 所以「预览里看到的」与「执行时落库的」不可能不一致。
   */
  private BatchPriceAdjustmentPreview computePreview(
      AccessService.Access actor, BatchPriceAdjustmentRequest request, String bearer) {
    var plan = planOf(request);
    var targets = resolveTargets(plan);
    if (targets.isEmpty()) {
      throw ProfitCalculatorService.badRequest(
          "目标集合里一个 SKU 都没有 —— 请确认分类 / 品牌 / 显式选中的 SKU，或换一个店铺范围。");
    }
    int listingCount = targets.size() * plan.shops().size();
    if (listingCount > BatchPriceAdjustmentRules.MAX_TARGET_LISTINGS) {
      throw ProfitCalculatorService.badRequest(
          "目标集合是 "
              + listingCount
              + " 个 listing（"
              + targets.size()
              + " 个 SKU × "
              + plan.shops().size()
              + " 个店铺），超过单次上限 "
              + BatchPriceAdjustmentRules.MAX_TARGET_LISTINGS
              + " 个 —— 每个 listing 至少要跑两次 5.3.1.1 的测算，每次要跨服务读成本构成。"
              + "请收窄分类 / 品牌 / 店铺范围后重试（本模块不会静默截断，"
              + "截断了你会以为「全改过了」）。");
    }
    var prices = resolvePrices(plan, targets);

    var items = new ArrayList<BatchPriceAdjustmentItem>();
    for (var target : targets) {
      for (var shop : plan.shops()) {
        items.add(
            computeItem(
                plan, target, shop[0], shop[1], prices.get(key(target.id(), shop[0], shop[1])),
                actor, bearer));
      }
    }

    // ---- 汇总（读时聚合自明细，不单独维护一份口径）----
    int willAdjust = 0;
    int skipped = 0;
    int lossBefore = 0;
    int lossAfter = 0;
    BigDecimal profitBefore = BigDecimal.ZERO;
    BigDecimal profitAfter = BigDecimal.ZERO;
    BigDecimal marginBeforeSum = BigDecimal.ZERO;
    BigDecimal marginAfterSum = BigDecimal.ZERO;
    int marginBeforeCount = 0;
    int marginAfterCount = 0;
    var lossAfterSkus = new HashSet<UUID>();
    for (var item : items) {
      if (item.status() == ItemStatus.WILL_ADJUST) {
        willAdjust++;
      } else {
        skipped++;
      }
      if (Boolean.TRUE.equals(item.lossBefore())) lossBefore++;
      if (Boolean.TRUE.equals(item.lossAfter())) {
        lossAfter++;
        lossAfterSkus.add(item.skuId());
      }
      if (item.grossProfitBefore() != null) {
        profitBefore = profitBefore.add(item.grossProfitBefore());
      }
      if (item.grossProfitAfter() != null) {
        profitAfter = profitAfter.add(item.grossProfitAfter());
      }
      if (item.grossMarginBefore() != null) {
        marginBeforeSum = marginBeforeSum.add(item.grossMarginBefore());
        marginBeforeCount++;
      }
      if (item.grossMarginAfter() != null) {
        marginAfterSum = marginAfterSum.add(item.grossMarginAfter());
        marginAfterCount++;
      }
    }
    // 🔴 合计只在**全部行都有数**时给 —— 部分行没测准时，
    // 「合计」会把没测准的行当成 0，那是一个系统性虚高的数字。
    BigDecimal profitBeforeTotal =
        profitBeforeCount(items) == items.size()
            ? BatchPriceAdjustmentRules.money(profitBefore)
            : null;
    BigDecimal profitAfterTotal =
        profitAfterCount(items) == items.size()
            ? BatchPriceAdjustmentRules.money(profitAfter)
            : null;
    BigDecimal profitDelta =
        profitBeforeTotal == null || profitAfterTotal == null
            ? null
            : BatchPriceAdjustmentRules.money(profitAfterTotal.subtract(profitBeforeTotal));
    BigDecimal avgMarginBefore =
        marginBeforeCount == 0
            ? null
            : BatchPriceAdjustmentRules.rate(
                marginBeforeSum.divide(BigDecimal.valueOf(marginBeforeCount), 10,
                    java.math.RoundingMode.HALF_UP));
    BigDecimal avgMarginAfter =
        marginAfterCount == 0
            ? null
            : BatchPriceAdjustmentRules.rate(
                marginAfterSum.divide(BigDecimal.valueOf(marginAfterCount), 10,
                    java.math.RoundingMode.HALF_UP));

    return new BatchPriceAdjustmentPreview(
        tokenOf(plan, items),
        plan.mode(),
        plan.mode().label(),
        plan.adjustAmount(),
        plan.adjustRate(),
        plan.templateId(),
        plan.templateCode(),
        plan.templateName(),
        plan.currency(),
        plan.costCurrency(),
        plan.fxRate(),
        plan.shops().size(),
        List.of(plan.shopsText().split(",")),
        (int) targets.stream().map(TargetSku::id).distinct().count(),
        items.size(),
        willAdjust,
        skipped,
        lossBefore,
        lossAfter,
        lossAfterSkus.size(),
        profitBeforeTotal,
        profitAfterTotal,
        profitDelta,
        avgMarginBefore,
        avgMarginAfter,
        items,
        plan.note());
  }

  /** 有改价前毛利额的行数。 */
  private int profitBeforeCount(List<BatchPriceAdjustmentItem> items) {
    int n = 0;
    for (var item : items) {
      if (item.grossProfitBefore() != null) n++;
    }
    return n;
  }

  /** 有改价后毛利额的行数。 */
  private int profitAfterCount(List<BatchPriceAdjustmentItem> items) {
    int n = 0;
    for (var item : items) {
      if (item.grossProfitAfter() != null) n++;
    }
    return n;
  }

  /**
   * 算一行的改价前后对比。
   *
   * <p>🔴 任何一步取不到 ⇒ 这一行**跳过**并写明原因，**不是**用 0 或估算值顶上去。
   */
  private BatchPriceAdjustmentItem computeItem(
      Plan plan,
      TargetSku target,
      String platformCode,
      String siteCode,
      Price price,
      AccessService.Access actor,
      String bearer) {
    // ---- 1) 当前价 ----
    if (price == null) {
      return skipped(target, platformCode, siteCode, plan.currency(),
          "该 listing 在该店铺没有价格观测（5.5.2.1 价格观测台账）—— 没有当前价就无从改价。");
    }
    if (!price.currency().equals(plan.currency())) {
      return skipped(
          target,
          platformCode,
          siteCode,
          price.currency(),
          "价格观测的币种（"
              + price.currency()
              + "）与本次调价的币种（"
              + plan.currency()
              + "）不同 —— 绝不按 1:1 折算。");
    }

    // ---- 2) 新价 ----
    BigDecimal newPrice;
    BigDecimal profitAfter;
    BigDecimal marginAfter;
    boolean decisionReady;
    if (plan.mode() == AdjustMode.TEMPLATE) {
      var applied =
          applyTemplate(plan, target, platformCode, siteCode, actor, bearer);
      if (applied == null) {
        return skipped(
            target,
            platformCode,
            siteCode,
            price.currency(),
            "定价模板拒绝了这个 listing（成本不完整 / 商品阶段不符 / 适用对象不匹配）—— 原因见预览明细。",
            price);
      }
      newPrice = applied.suggestedPrice();
      profitAfter = applied.grossProfit();
      marginAfter = applied.grossMarginRate();
      decisionReady = applied.decisionReady();
    } else {
      newPrice = BatchPriceAdjustmentRules.newPrice(plan.mode(), price.amount(),
          plan.adjustAmount(), plan.adjustRate());
      if (newPrice == null) {
        return skipped(
            target,
            platformCode,
            siteCode,
            price.currency(),
            "按这次的调价参数算出来的新价不是正价 —— 已跳过这一行（绝不改成 0）。",
            price);
      }
      var after = calculate(plan, target.id(), platformCode, siteCode, newPrice, bearer);
      profitAfter = after.grossProfit();
      marginAfter = after.grossMarginRate();
      decisionReady = after.decisionReady();
    }

    // ---- 3) 改价前的利润 ----
    var before = calculate(plan, target.id(), platformCode, siteCode, price.amount(), bearer);

    return new BatchPriceAdjustmentItem(
        null,
        null,
        target.id(),
        target.code(),
        target.name(),
        platformCode,
        siteCode,
        price.currency(),
        ItemStatus.WILL_ADJUST,
        "",
        price.amount(),
        newPrice,
        BatchPriceAdjustmentRules.delta(price.amount(), newPrice),
        price.source(),
        price.observedAt(),
        before.grossProfit(),
        before.grossMarginRate(),
        profitAfter,
        marginAfter,
        before.decisionReady() && decisionReady,
        BatchPriceAdjustmentRules.lossFlag(before.grossProfit()),
        BatchPriceAdjustmentRules.lossFlag(profitAfter));
  }

  /** 模板应用的结果。 */
  private record AppliedTemplate(
      BigDecimal suggestedPrice, BigDecimal grossProfit, BigDecimal grossMarginRate,
      boolean decisionReady) {}

  /**
   * 「按模板」方式：走 5.3.2.1 的 {@link PricingTemplateService#apply}。
   *
   * <p>🔴 **本模块不自己实现模板怎么算**：建议价、站点差异化、库存周转折扣、
   * 成本不完整的拒绝，全部由 5.3.2.1 产出。5.3.2.1 拒绝这一行时返回 null
   * （那一行被跳过，原因原样带回）。
   */
  private AppliedTemplate applyTemplate(
      Plan plan,
      TargetSku target,
      String platformCode,
      String siteCode,
      AccessService.Access actor,
      String bearer) {
    try {
      var applied =
          templates.apply(
              actor,
              plan.templateId(),
              new ApplyRequest(
                  target.id(),
                  siteCode,
                  platformCode,
                  plan.currency(),
                  plan.quantity(),
                  plan.competitorReferencePrice(),
                  plan.competitorCurrency(),
                  plan.daysOfCover(),
                  plan.platformFeeRate(),
                  plan.platformFeeFixed(),
                  plan.logisticsCostPerUnit(),
                  plan.costCurrency(),
                  plan.fxRate(),
                  plan.note()),
              bearer);
      if (applied.suggestedPrice() == null) return null;
      return new AppliedTemplate(
          applied.suggestedPrice(),
          applied.grossProfit(),
          applied.grossMarginRate(),
          applied.decisionReady());
    } catch (ResponseStatusException e) {
      if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) throw e;
      return null;
    }
  }

  /** 一次 5.3.1.1 的测算（改价前或改价后）。 */
  private ProfitCalculatorModels.ProfitCalculation calculate(
      Plan plan, UUID skuId, String platformCode, String siteCode, BigDecimal price, String bearer) {
    return calculator.calculate(
        new ProfitCalculatorModels.CalculateRequest(
            skuId,
            siteCode,
            platformCode,
            plan.currency(),
            plan.costCurrency(),
            ProfitCalculatorModels.CalcMode.PRICE,
            price,
            null,
            plan.quantity(),
            plan.fxRate(),
            plan.platformFeeRate(),
            plan.platformFeeFixed(),
            plan.logisticsCostPerUnit(),
            plan.monthlyFixedCost(),
            List.of(),
            plan.costWindowFrom(),
            plan.costWindowTo(),
            plan.note()),
        bearer);
  }

  // ================================================================ 内部：归一化与取数

  /** 归一化 + 校验：不合法就 400。 */
  private Plan planOf(BatchPriceAdjustmentRequest request) {
    if (request == null) throw ProfitCalculatorService.badRequest("批量调价请求不能为空");
    if (request.adjustMode() == null) throw ProfitCalculatorService.badRequest("必须指定调价方式");

    // ---- 调价参数（三种方式三选一）----
    BigDecimal adjustAmount = request.adjustAmount();
    BigDecimal adjustRate = request.adjustRate();
    UUID templateId = request.templateId();
    String templateCode = "";
    String templateName = "";
    TemplateDetail template = null;
    if (request.adjustMode() == AdjustMode.FIXED_AMOUNT) {
      if (adjustAmount == null) {
        throw ProfitCalculatorService.badRequest("固定金额方式必须给出调价金额（可负 = 降价）");
      }
      adjustRate = null;
      templateId = null;
    } else if (request.adjustMode() == AdjustMode.PERCENT) {
      if (adjustRate == null) {
        throw ProfitCalculatorService.badRequest("百分比方式必须给出涨跌幅（小数口径，0.1 = +10%）");
      }
      if (adjustRate.compareTo(new BigDecimal("-1")) < 0) {
        throw ProfitCalculatorService.badRequest("涨跌幅不能小于 -100%（那会把价格变成负数）");
      }
      adjustAmount = null;
      templateId = null;
    } else {
      if (templateId == null) {
        throw ProfitCalculatorService.badRequest("按模板方式必须指定定价模板（5.3.2.1）");
      }
      adjustAmount = null;
      adjustRate = null;
      template = templates.detail(templateId);
      templateCode = template.summary().code();
      templateName = template.summary().name();
    }

    // ---- 币种与汇率 ----
    String currency = BatchPriceAdjustmentRules.code(request.currency());
    if (currency == null || !currency.matches("^[A-Z]{3}$")) {
      throw ProfitCalculatorService.badRequest("必须指定售价币种（三位大写字母，ISO 4217）");
    }
    String costCurrency = BatchPriceAdjustmentRules.code(request.costCurrency());
    if (costCurrency == null && template != null) {
      costCurrency = BatchPriceAdjustmentRules.code(template.costCurrency());
    }
    if (costCurrency == null) costCurrency = currency;
    if (!costCurrency.matches("^[A-Z]{3}$")) {
      throw ProfitCalculatorService.badRequest("成本币种必须是三位大写字母");
    }
    BigDecimal fxRate = request.fxRate();
    if (fxRate == null && template != null) fxRate = template.fxRate();
    if (!currency.equals(costCurrency) && (fxRate == null || fxRate.signum() <= 0)) {
      throw ProfitCalculatorService.badRequest(
          "售价币种（"
              + currency
              + "）与成本币种（"
              + costCurrency
              + "）不同，必须给汇率：1 "
              + costCurrency
              + " = X "
              + currency
              + "。汇率规则（5.2.2.1 / 5.2.2.2）未交付，所以这里只能由人作为手工假设填入 —— "
              + "绝不按 1:1 折算，那会把两个币种的金额加在一起。");
    }

    // ---- 店铺 ----
    if (request.shops() == null || request.shops().isEmpty()) {
      throw ProfitCalculatorService.badRequest("必须指定目标店铺（至少一个平台 + 站点）");
    }
    var shops = new ArrayList<String[]>();
    for (var shop : request.shops()) {
      String platformCode = BatchPriceAdjustmentRules.code(shop.platformCode());
      String siteCode = BatchPriceAdjustmentRules.code(shop.siteCode());
      if (platformCode == null || siteCode == null) {
        throw ProfitCalculatorService.badRequest("每个目标店铺都必须给出平台码与站点码");
      }
      shops.add(new String[] {platformCode, siteCode});
    }
    String shopsText = BatchPriceAdjustmentRules.shopCodes(shops);

    // ---- SKU 范围 ----
    List<UUID> skuIds = request.skuIds() == null ? List.of() : request.skuIds();
    String skuIdsText =
        skuIds.stream().map(UUID::toString).sorted().reduce((a, b) -> a + "," + b).orElse("");

    // ---- 利润口径（请求优先，模板兜底）----
    BigDecimal quantity = request.quantity() == null ? BigDecimal.ONE : request.quantity();
    if (quantity.signum() <= 0) throw ProfitCalculatorService.badRequest("数量必须大于 0");
    BigDecimal platformFeeRate = request.platformFeeRate();
    if (platformFeeRate == null && template != null) platformFeeRate = template.platformFeeRate();
    if (platformFeeRate != null
        && (platformFeeRate.signum() < 0 || platformFeeRate.compareTo(BigDecimal.ONE) >= 0)) {
      throw ProfitCalculatorService.badRequest("平台费率是小数口径（0.15 表示 15%），必须在 0 与 1 之间");
    }
    BigDecimal platformFeeFixed = request.platformFeeFixed();
    if (platformFeeFixed == null && template != null) platformFeeFixed = template.platformFeeFixed();
    BigDecimal logisticsCostPerUnit = request.logisticsCostPerUnit();
    if (logisticsCostPerUnit == null && template != null) {
      logisticsCostPerUnit = template.logisticsCostPerUnit();
    }
    BigDecimal monthlyFixedCost = request.monthlyFixedCost();
    // 🔴 5.3.2.1 的模板上**没有**月度固定成本这一项（模板的口径里没有它），
    //    所以这一项只能来自本请求 —— 缺了就是「没给」，不是 0。

    LocalDate from =
        request.costWindowFrom() == null
            ? LocalDate.now().minusDays(DEFAULT_WINDOW_DAYS)
            : request.costWindowFrom();
    LocalDate to = request.costWindowTo() == null ? LocalDate.now() : request.costWindowTo();
    if (to.isBefore(from)) throw ProfitCalculatorService.badRequest("成本口径窗口的终点不能早于起点");

    return new Plan(
        request.adjustMode(),
        adjustAmount,
        adjustRate,
        templateId,
        templateCode,
        templateName,
        currency,
        costCurrency,
        fxRate,
        shops,
        shopsText,
        skuIds,
        skuIdsText,
        BatchPriceAdjustmentRules.scope(request.category()),
        BatchPriceAdjustmentRules.scope(request.brand()),
        quantity,
        platformFeeRate,
        platformFeeFixed,
        logisticsCostPerUnit,
        monthlyFixedCost,
        from,
        to,
        request.daysOfCover(),
        request.competitorReferencePrice(),
        request.competitorCurrency(),
        request.note() == null ? "" : request.note());
  }

  /**
   * 解析目标 SKU 集合。
   *
   * <p>🔴 分类与品牌是 spu 上的**文本**列，**精确匹配**（与 5.3.1.2 同一口径）：
   * 分类名里的空格与大小写在不同来源的导入里经常不一致，模糊匹配会静默多算一批 SKU。
   */
  private List<TargetSku> resolveTargets(Plan plan) {
    var where = new StringBuilder(" where k.tenant_id=" + TENANT);
    var binds = new ArrayList<Object>();
    if (!plan.skuIds().isEmpty()) {
      var placeholders = new StringBuilder();
      for (int i = 0; i < plan.skuIds().size(); i++) {
        placeholders.append(i == 0 ? "?" : ",?");
        binds.add(plan.skuIds().get(i));
      }
      where.append(" and k.id in (").append(placeholders).append(")");
    } else {
      if (!plan.category().isEmpty()) {
        where.append(" and p.category = ?");
        binds.add(plan.category());
      }
      if (!plan.brand().isEmpty()) {
        where.append(" and p.brand = ?");
        binds.add(plan.brand());
      }
    }
    var rows =
        sql.fetch(
            "select k.id, k.code, k.name"
                + " from sku k join spu p on p.tenant_id=k.tenant_id and p.id=k.spu_id"
                + where
                + " order by k.code"
                + " limit ?",
            concat(binds, List.of(BatchPriceAdjustmentRules.MAX_TARGET_LISTINGS + 1)).toArray());
    if (rows.size() > BatchPriceAdjustmentRules.MAX_TARGET_LISTINGS) {
      throw ProfitCalculatorService.badRequest(
          "目标 SKU 超过 "
              + BatchPriceAdjustmentRules.MAX_TARGET_LISTINGS
              + " 个 —— 请收窄分类 / 品牌范围，或分批调价（本模块不会静默截断）。");
    }
    var out = new ArrayList<TargetSku>();
    for (var row : rows) {
      out.add(
          new TargetSku(
              row.get("id", UUID.class),
              row.get("code", String.class),
              row.get("name", String.class)));
    }
    if (!plan.skuIds().isEmpty() && out.size() != plan.skuIds().size()) {
      throw ProfitCalculatorService.badRequest(
          "显式选中的 SKU 里有 "
              + (plan.skuIds().size() - out.size())
              + " 个不存在或不属于当前租户 —— 本模块不会替你把不存在的 SKU 混进这批调价。");
    }
    return out;
  }

  /**
   * 取当前价：价格观测台账里该 (SKU, 店铺) 的**最新一条**观测。
   *
   * <p>🔴 台账是**只追加**的，「最新一条」是一个时间点而不是「当前价」——
   * 观测时刻会写进明细行。
   */
  private Map<String, Price> resolvePrices(Plan plan, List<TargetSku> targets) {
    var out = new LinkedHashMap<String, Price>();
    if (targets.isEmpty()) return out;
    // 🔴 **绑定的顺序必须与 SQL 里占位符出现的顺序一致**：
    //   店铺 VALUES 在前、SKU 的 IN 在后，所以店铺的绑定先加。
    var binds = new ArrayList<Object>();
    var shopValues = new StringBuilder();
    for (int i = 0; i < plan.shops().size(); i++) {
      shopValues.append(i == 0 ? "(?,?)" : ",(?,?)");
      binds.add(plan.shops().get(i)[0]);
      binds.add(plan.shops().get(i)[1]);
    }
    var skuPlaceholders = new StringBuilder();
    for (int i = 0; i < targets.size(); i++) {
      skuPlaceholders.append(i == 0 ? "?" : ",?");
      binds.add(targets.get(i).id());
    }
    var rows =
        sql.fetch(
            "select distinct on (o.sku_id, o.platform_code, o.site_code)"
                + " o.sku_id, o.platform_code, o.site_code, o.internal_price, o.platform_price,"
                + " o.currency, o.observed_at"
                + " from price_anomaly_observation o"
                + " join (values "
                + shopValues
                + ") s(platform_code, site_code)"
                + " on s.platform_code = o.platform_code and s.site_code = o.site_code"
                + " where o.tenant_id="
                + TENANT
                + " and o.sku_id in ("
                + skuPlaceholders
                + ")"
                + " order by o.sku_id, o.platform_code, o.site_code, o.observed_at desc, o.id desc",
            binds.toArray());
    for (var row : rows) {
      BigDecimal amount = row.get("internal_price", BigDecimal.class);
      String source = "INTERNAL_PRICE";
      if (amount == null) {
        amount = row.get("platform_price", BigDecimal.class);
        source = "PLATFORM_PRICE";
      }
      if (amount == null) continue;
      out.put(
          key(
              row.get("sku_id", UUID.class),
              row.get("platform_code", String.class),
              row.get("site_code", String.class)),
          new Price(
              amount,
              source,
              row.get("currency", String.class),
              row.get("observed_at", OffsetDateTime.class)));
    }
    return out;
  }

  /** (SKU, 店铺) 的键。 */
  private String key(UUID skuId, String platformCode, String siteCode) {
    return skuId + "|" + platformCode + "|" + siteCode;
  }

  /** 拼接两个列表（与相邻模块同一写法）。 */
  private static <T> List<T> concat(List<? extends T> first, List<? extends T> second) {
    var out = new ArrayList<T>(first.size() + second.size());
    out.addAll(first);
    out.addAll(second);
    return out;
  }

  /** 一行「跳过」（没有当前价）。 */
  private BatchPriceAdjustmentItem skipped(
      TargetSku target, String platformCode, String siteCode, String currency, String reason) {
    return skipped(target, platformCode, siteCode, currency, reason, null);
  }

  /** 一行「跳过」（有当前价但不调）。 */
  private BatchPriceAdjustmentItem skipped(
      TargetSku target,
      String platformCode,
      String siteCode,
      String currency,
      String reason,
      Price price) {
    return new BatchPriceAdjustmentItem(
        null,
        null,
        target.id(),
        target.code(),
        target.name(),
        platformCode,
        siteCode,
        currency,
        ItemStatus.SKIPPED,
        reason,
        price == null ? null : price.amount(),
        null,
        null,
        price == null ? null : price.source(),
        price == null ? null : price.observedAt(),
        null,
        null,
        null,
        null,
        false,
        null,
        null);
  }

  // ================================================================ 内部：预览令牌

  /**
   * 预览令牌 = 预览内容的规范串的 SHA-256。
   *
   * <p>🔴 规范串里**不含**随机量与时间戳 —— 同一份输入必须算出同一个令牌，
   * 否则「预览 → 确认」这条链在跨进程 / 跨实例时会被自己打断。
   */
  private String tokenOf(Plan plan, List<BatchPriceAdjustmentItem> items) {
    var canonical = new StringBuilder();
    canonical
        .append("mode=").append(plan.mode().name())
        .append("|amount=").append(BatchPriceAdjustmentRules.canonicalNumber(plan.adjustAmount()))
        .append("|rate=").append(BatchPriceAdjustmentRules.canonicalNumber(plan.adjustRate()))
        .append("|template=").append(plan.templateId())
        .append("|currency=").append(plan.currency())
        .append("|costCurrency=").append(plan.costCurrency())
        .append("|fx=").append(BatchPriceAdjustmentRules.canonicalNumber(plan.fxRate()))
        .append("|shops=").append(plan.shopsText())
        .append("|skus=").append(plan.skuIdsText())
        .append("|category=").append(plan.category())
        .append("|brand=").append(plan.brand())
        .append("|quantity=").append(BatchPriceAdjustmentRules.canonicalNumber(plan.quantity()))
        .append("|feeRate=").append(BatchPriceAdjustmentRules.canonicalNumber(plan.platformFeeRate()))
        .append("|feeFixed=").append(BatchPriceAdjustmentRules.canonicalNumber(plan.platformFeeFixed()))
        .append("|logistics=")
        .append(BatchPriceAdjustmentRules.canonicalNumber(plan.logisticsCostPerUnit()))
        .append("|fixed=").append(BatchPriceAdjustmentRules.canonicalNumber(plan.monthlyFixedCost()))
        .append("|window=").append(plan.costWindowFrom()).append("..").append(plan.costWindowTo())
        .append('\n');
    for (var item : items) {
      canonical
          .append(item.skuId()).append('|')
          .append(item.platformCode()).append('|')
          .append(item.siteCode()).append('|')
          .append(item.status().name()).append('|')
          .append(BatchPriceAdjustmentRules.canonicalNumber(item.oldPrice())).append('|')
          .append(BatchPriceAdjustmentRules.canonicalNumber(item.newPrice())).append('|')
          .append(BatchPriceAdjustmentRules.canonicalNumber(item.grossProfitBefore())).append('|')
          .append(BatchPriceAdjustmentRules.canonicalNumber(item.grossMarginBefore())).append('|')
          .append(BatchPriceAdjustmentRules.canonicalNumber(item.grossProfitAfter())).append('|')
          .append(BatchPriceAdjustmentRules.canonicalNumber(item.grossMarginAfter())).append('|')
          .append(item.decisionReady()).append('|')
          .append(item.lossBefore()).append('|')
          .append(item.lossAfter()).append('|')
          .append(item.skipReason())
          .append('\n');
    }
    return BatchPriceAdjustmentRules.previewToken(canonical.toString());
  }

  // ================================================================ 内部：行映射与筛选

  /** 一段拼好的筛选（列表与导出共用）。 */
  private record Filter(String from, String where, List<Object> binds, String order) {}

  /** 拼筛选：全部在数据库层，不是把整表拉回来在内存里筛。 */
  private Filter filterOf(UUID adjustmentId, ItemQuery query) {
    var where =
        new StringBuilder(
            " where i.tenant_id="
                + TENANT
                + " and i.adjustment_id=?"
                + " and k.tenant_id="
                + TENANT
                + " and k.id=i.sku_id");
    var binds = new ArrayList<Object>();
    binds.add(adjustmentId);
    var status = ItemStatus.parse(query.status());
    if (query.status() != null && !query.status().isBlank() && status == null) {
      throw ProfitCalculatorService.badRequest("未知的状态：" + query.status());
    }
    if (status != null) {
      where.append(" and i.status = ?");
      binds.add(status.name());
    }
    var loss = query.loss() == null ? "" : query.loss().trim().toUpperCase(java.util.Locale.ROOT);
    if (!loss.isEmpty()) {
      if (loss.equals("LOSS_AFTER")) {
        where.append(" and i.loss_after");
      } else if (loss.equals("LOSS_BEFORE")) {
        where.append(" and i.loss_before");
      } else {
        throw ProfitCalculatorService.badRequest(
            "未知的亏损筛选：" + query.loss() + "（可用 LOSS_AFTER / LOSS_BEFORE）");
      }
    }
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.append(" and (k.code ilike ? or k.name ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
    }
    var sort = Sort.parse(query.sort());
    if (query.sort() != null && !query.sort().isBlank() && sort == null) {
      throw ProfitCalculatorService.badRequest("未知的排序方式：" + query.sort());
    }
    String order =
        switch (sort == null ? Sort.DELTA_ASC : sort) {
          case DELTA_DESC -> " order by i.price_delta desc nulls last, i.sku_id";
          case OLD_PRICE_ASC -> " order by i.old_price asc nulls last, i.sku_id";
          case OLD_PRICE_DESC -> " order by i.old_price desc nulls last, i.sku_id";
          case MARGIN_ASC -> " order by i.gross_margin_after asc nulls last, i.sku_id";
          case MARGIN_DESC -> " order by i.gross_margin_after desc nulls last, i.sku_id";
          default -> " order by i.price_delta asc nulls last, i.sku_id";
        };
    return new Filter(
        " from batch_price_adjustment_item i"
            + " join sku k on k.tenant_id=i.tenant_id and k.id=i.sku_id",
        where.toString(),
        binds,
        order);
  }

  private BatchPriceAdjustmentDetail adjustmentDetail(org.jooq.Record row) {
    var mode = AdjustMode.parse(row.get("adjust_mode", String.class));
    return new BatchPriceAdjustmentDetail(
        row.get("id", UUID.class),
        mode,
        mode == null ? row.get("adjust_mode", String.class) : mode.label(),
        row.get("adjust_amount", BigDecimal.class),
        row.get("adjust_rate", BigDecimal.class),
        row.get("template_id", UUID.class),
        row.get("template_code", String.class),
        row.get("template_name", String.class),
        row.get("currency", String.class),
        row.get("cost_currency", String.class),
        row.get("fx_rate", BigDecimal.class),
        row.get("scope_category", String.class),
        row.get("scope_brand", String.class),
        row.get("scope_sku_ids", String.class),
        row.get("scope_shops", String.class),
        row.get("quantity", BigDecimal.class),
        row.get("platform_fee_rate", BigDecimal.class),
        row.get("platform_fee_fixed", BigDecimal.class),
        row.get("logistics_cost_per_unit", BigDecimal.class),
        row.get("monthly_fixed_cost", BigDecimal.class),
        row.get("cost_window_from", LocalDate.class),
        row.get("cost_window_to", LocalDate.class),
        row.get("shop_count", Integer.class),
        row.get("sku_count", Integer.class),
        row.get("listing_count", Integer.class),
        row.get("will_adjust_count", Integer.class),
        row.get("skipped_count", Integer.class),
        row.get("loss_before_count", Integer.class),
        row.get("loss_after_count", Integer.class),
        row.get("loss_after_sku_count", Integer.class),
        row.get("profit_before_total", BigDecimal.class),
        row.get("profit_after_total", BigDecimal.class),
        row.get("profit_delta_total", BigDecimal.class),
        row.get("avg_margin_before", BigDecimal.class),
        row.get("avg_margin_after", BigDecimal.class),
        row.get("preview_token", String.class),
        row.get("note", String.class),
        row.get("created_by", UUID.class) == null ? null : row.get("created_by", UUID.class).toString(),
        row.get("created_at", OffsetDateTime.class));
  }

  private BatchPriceAdjustmentItem itemOf(org.jooq.Record row) {
    var status = ItemStatus.parse(row.get("status", String.class));
    return new BatchPriceAdjustmentItem(
        row.get("id", UUID.class),
        row.get("adjustment_id", UUID.class),
        row.get("sku_id", UUID.class),
        row.get("sku_code", String.class),
        row.get("sku_name", String.class),
        row.get("platform_code", String.class),
        row.get("site_code", String.class),
        row.get("currency", String.class),
        status,
        row.get("skip_reason", String.class),
        row.get("old_price", BigDecimal.class),
        row.get("new_price", BigDecimal.class),
        row.get("price_delta", BigDecimal.class),
        row.get("price_source", String.class),
        row.get("price_observed_at", OffsetDateTime.class),
        row.get("gross_profit_before", BigDecimal.class),
        row.get("gross_margin_before", BigDecimal.class),
        row.get("gross_profit_after", BigDecimal.class),
        row.get("gross_margin_after", BigDecimal.class),
        row.get("decision_ready", Boolean.class),
        row.get("loss_before", Boolean.class),
        row.get("loss_after", Boolean.class));
  }
}
