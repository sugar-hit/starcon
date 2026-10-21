package com.starcon.catalog;

import static com.starcon.catalog.PriceAdjustmentModels.*;

import com.starcon.catalog.PriceAdjustmentModels.PriceAdjustmentCurrentPrice;
import com.starcon.catalog.PriceAdjustmentModels.PriceAdjustmentExpectedVersionRequest;
import com.starcon.catalog.PriceAdjustmentModels.OrderQuery;
import com.starcon.catalog.PriceAdjustmentModels.PriceAdjustmentDetail;
import com.starcon.catalog.PriceAdjustmentModels.PriceAdjustmentOrderItem;
import com.starcon.catalog.PriceAdjustmentModels.PriceAdjustmentPage;
import com.starcon.catalog.PriceAdjustmentModels.PriceAdjustmentRequest;
import com.starcon.catalog.PriceAdjustmentModels.PriceAdjustmentWriteback;
import com.starcon.catalog.PriceAdjustmentModels.PriceAdjustmentWritebackPage;
import com.starcon.catalog.PriceAdjustmentModels.PriceSource;
import com.starcon.catalog.PriceAdjustmentModels.PriceAdjustmentRejectRequest;
import com.starcon.catalog.PriceAdjustmentModels.Sort;
import com.starcon.catalog.PriceAdjustmentModels.Status;
import com.starcon.catalog.PriceAdjustmentModels.PriceAdjustmentWritebackAckRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.4.1.1 调价单：发起价格变更（选择 SKU 与目标店铺、输入新旧价、说明原因、提交审批），
 * 记录变更前后值；生效后产出「待回写」事实给 5.4.2.1。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：发起价格变更：选择 SKU 与目标店铺、输入新旧价、说明原因、提交审批（若启用）；记录变更前后值。
 *
 * **输入**：调价请求；`expected_version`；审批要求（17 组织的审批规则）。
 *
 * **输出**：调价单（草稿/待审/已批/已执行/已驳回）；生效后触发平台回写。
 *
 * **关系**：上游 = 5.3.1.1、5.3.2.2；下游 = 5.4.2.1、17 审批。**调价是受控动作**：大额或大幅调价走审批，普通调整可配置免审。
 *
 * </blockquote>
 *
 * <h2>🔴 本类的六条纪律</h2>
 *
 * <ol>
 *   <li><b>🔴 零个测算算法</b>：改价前后的毛利额与毛利率全部来自 5.3.1.1 的引擎
 *       （{@link ProfitCalculatorService#calculate}）。本模块**不重写第二套算术**。</li>
 *   <li><b>🔴 审批由 17 组织裁定</b>：要不要审走 identity 的
 *       {@code POST /identity/approval-rules/evaluate}（单据类型 {@code PRICE_CHANGE}），
 *       批不批由 identity 那边的人做，结论**拉取**回来。本模块**不发明审批流**，
 *       也**不**在审批服务不可达时把单子当成已批。</li>
 *   <li><b>🔴 取不到当前价就整单拒绝，绝不拿 0 顶</b>：0 会被当成「免费」，
 *       而它其实是一个取不到的事实。跨币种同样**绝不按 1:1 折算**。</li>
 *   <li><b>🔴 成本不完整就不给利润数字</b>：沿用 5.3.1.1 的决策就绪闸 ——
 *       毛利额与毛利率是 null，亏损标记也是 null（不可信，不是 false）。</li>
 *   <li><b>🔴 expected_version 是硬闸</b>：状态流转语句的 where 带 {@code and version = ?}，
 *       影响行数为 0 ⇒ <b>409</b>。</li>
 *   <li><b>🔴 租户只来自已认证成员关系</b>（{@link AccessService}），不读 {@code X-Tenant-Id} 头。
 *       每条 SQL 的 where 都带显式 {@code tenant_id=} 绑定，RLS 再兜一道。</li>
 * </ol>
 */
@Service
public class PriceAdjustmentService {

  /** 租户上下文取法：RLS 已按它隔离，这里带上只是让执行计划走租户前缀的索引。 */
  private static final String TENANT = "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 台账页大小上限：与相邻模块一致。 */
  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;
  private final ProfitCalculatorService calculator;
  private final PriceAdjustmentApprovalPort approvals;
  private final AccessService access;

  public PriceAdjustmentService(
      DSLContext sql,
      ProfitCalculatorService calculator,
      PriceAdjustmentApprovalPort approvals,
      AccessService access) {
    this.sql = sql;
    this.calculator = calculator;
    this.approvals = approvals;
    this.access = access;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：listing 的定义、当前价来源、审批口径、状态机、版本闸、舍入与上下游。
   *
   * <p>页面上把它固定显示在顶部 —— 蓝图那句「**调价是受控动作**」意味着运营会拿这页的
   * 口径去决定「这张单要不要提、提了会不会被卡」，所以口径必须**摆在数字旁边**。
   */
  public PriceAdjustmentCapabilities capabilities() {
    return new PriceAdjustmentCapabilities(
        "🔴 **一张调价单 = 一个 SKU 在一个店铺上的一次价格变更**。"
            + "本仓 catalog **没有** listing 的本地投影（listing 事实属 channel 的 4.1.1.1），"
            + "且 AGENTS.md 禁止跨服务直接查对方的表 —— 所以本模块**不新建任何 listing 影子表**"
            + "去假装上游已交付。调价单里的「一个 listing」= (SKU × 店铺)。"
            + "「一批 listing 统一改价」是 5.4.1.2 批量调价的职责，它的表在 V237，本模块不碰。",
        "🔴 **当前价来源**：catalog 内**已有**的价格事实，按时间取最新的一条 —— "
            + "① 5.5.2.1 异常告警的价格观测台账（price_anomaly_observation：该 SKU 在该店铺的"
            + "最新一条观测，优先我方价 internal_price、退到平台在售价 platform_price）；"
            + "② 5.4.1.2 批量调价明细里记下的**新价**（它可能比观测更新）。"
            + "**取不到就整单拒绝**（原因写明），**绝不用 0 顶** —— 0 会被当成「免费」。"
            + "**币种不同 ⇒ 整单拒绝**，绝不按 1:1 折算。",
        "🔴 **审批由 17 组织（identity 服务）裁定**，本模块**不发明审批流**："
            + "提交前调 `POST /identity/approval-rules/evaluate`（单据类型 `PRICE_CHANGE`，"
            + "identity V83 出厂注册），命中规则 ⇒ 需审批；`EXEMPT` 或本租户未配规则"
            + "（`UNCONTROLLED`）⇒ **免审直执行** —— 这正是蓝图那句「普通调整可配置免审」的实现处。"
            + "需审批时提交审批实例（`POST /identity/approval-instances`，幂等：同一张单不会产生第二条待办）。"
            + "🔴 **审批服务不可达时不会自动当成已批**：调价单停在「待审」并如实标出错误。",
        "🔴 **审批状态是拉取式的，不是事件推送**：本仓还没有从 identity 推送到本服务的事件通道。"
            + "审批人在 identity 那边决定后，本模块通过 `POST /catalog/price-adjustments/{id}/approval/refresh`"
            + "（以及读详情时顺带刷新）把结论取回来，读详情也会把当前审批状态带上。"
            + "需要 identity 侧权限：求值要 `identity.approval_rule.read`，查实例状态要 `identity.approval_record.read`。",
        "状态机（合法路径写在 `PriceAdjustmentRules.ALLOWED`，非法转换一律拒绝）："
            + "**草稿 → 待审 → 已批 → 已执行**；**草稿 / 待审 → 已驳回**。"
            + "免审时**草稿 → 已批**（决策镜像里写明 `decision=EXEMPT`）。"
            + "**已执行与已驳回是终态** —— 要再调价请另发一张单。",
        "🔴 **乐观锁**：调价单带一个 `version` 列，每次状态流转都 +1。"
            + "提交 / 执行 / 驳回都要带上你读到的 `expectedVersion`，对不上 ⇒ **409 冲突**"
            + "（别人已经动过这张单，或它已经不在你以为的状态上了）。详情与台账都带出当前版本。",
        "🔴 **改价前后的利润全部来自 5.3.1.1 的引擎**（`ProfitCalculatorService.calculate`）—— "
            + "本模块**零个测算算法**。⚠️ 5.3.1.1 的上游缺口在本模块同样存在："
            + "5.2.3.1 平台费用与 5.2.3.2 物流与头程费用未交付，"
            + "所以平台费率与物流单价只能作为**手工假设**由人填。",
        "🔴 **舍入**：对外标价按 **2 位小数 HALF_UP** 落定（2.1.1.1 平台目录的 currencyScale 也是 2）；"
            + "内部金额 4 位小数、费率与毛利率 6 位小数，HALF_UP（与 5.3.1.1 逐字一致）。"
            + "**全程 BigDecimal，零浮点。**",
        "上游 = **5.3.1.1 利润测算器**（同服务内，V227）与 **5.3.2.2 价格规则**（同服务内，V236）。"
            + "本模块是**人工调价单**；5.3.2.2 产出的「建议调价列表」是它的上游输入，不是本模块的产出。",
        "下游 = **5.4.2.1 平台价格同步**（**未交付**）与 **17 审批**（已交付，见上）。"
            + "🔴 本模块在单子**执行**时只产出一条「**待回写**」的事实（写进 `price_adjustment_writeback`），"
            + "**绝不自己调平台 API** —— 平台定价规则（最低价 / 自动比价）与「被平台调整后的实际价」"
            + "都归 5.4.2.1 记。",
        "权限沿用商品主数据域：读 `catalog.spu.read`，写 `catalog.spu.update`。"
            + "**租户不来自任何请求头** —— 只来自已认证的成员关系，伪造的 `X-Tenant-Id` 头无效。");
  }

  // ================================================================ 当前价

  /**
   * 解析「SKU 在某店铺的当前价」（给页面在填单之前显示旧价用）。
   *
   * <p>🔴 **取不到就返回 {@code price=null} 并在 {@code reason} 里说清为什么** ——
   * 绝不返回 0（0 会被当成「免费」）。
   */
  public PriceAdjustmentCurrentPrice currentPrice(
      UUID skuId, String platformCode, String siteCode, String currency) {
    if (skuId == null) {
      throw ProfitCalculatorService.badRequest("必须指定 SKU");
    }
    String platform = PriceAdjustmentRules.code(platformCode);
    String site = PriceAdjustmentRules.code(siteCode);
    if (platform == null || site == null) {
      throw ProfitCalculatorService.badRequest("必须指定平台与站点");
    }
    var price = resolvePrice(skuId, platform, site);
    if (price == null) {
      return new PriceAdjustmentCurrentPrice(
          skuId,
          platform,
          site,
          currency,
          null,
          null,
          null,
          "该 SKU 在该店铺没有任何价格事实 —— 既没有 5.5.2.1 价格观测台账里的观测，"
              + "也没有 5.4.1.2 批量调价记下的新价。没有当前价就没有「变更前后值」，"
              + "本模块**不会**拿 0 顶（0 会被当成「免费」）。");
    }
    return new PriceAdjustmentCurrentPrice(
        skuId,
        platform,
        site,
        price.currency(),
        price.amount(),
        price.source(),
        price.observedAt(),
        "");
  }

  // ================================================================ 创建

  /**
   * 创建一张调价单（**草稿**）。
   *
   * <p>🔴 流程：归一化 → 取当前价 → 比对你看到的旧价 → 跑两次 5.3.1.1 的测算 → 落库。
   * 取不到当前价、币种不同、或者你看到的旧价已经过期，都会**明确拒绝**。
   */
  public PriceAdjustmentDetail create(
      AccessService.Access actor, PriceAdjustmentRequest request, String bearer) {
    var plan = planOf(request);

    // ---- 目标 SKU 必须在本租户存在且可见 ----
    var sku = requireSku(plan.skuId());

    // ---- 当前价 ----
    var price = resolvePrice(plan.skuId(), plan.platformCode(), plan.siteCode());
    if (price == null) {
      throw ProfitCalculatorService.badRequest(
          "该 SKU 在 " + plan.platformCode() + "/" + plan.siteCode()
              + " 没有任何价格事实（5.5.2.1 价格观测台账里没有观测，5.4.1.2 批量调价也没记过新价）—— "
              + "没有当前价就没有「变更前后值」，本模块**不会**拿 0 顶（0 会被当成「免费」）。"
              + "请先登记一次价格观测，或换一个已经有过价格事实的店铺。");
    }
    if (plan.currency() != null && !plan.currency().equals(price.currency())) {
      throw ProfitCalculatorService.badRequest(
          "当前价的币种（" + price.currency() + "）与本次调价的币种（" + plan.currency() + "）不同 —— "
              + "本模块**绝不按 1:1 折算**，请按店铺的真实计价币种重填。");
    }
    // 🔴 你看到的旧价必须与当前真实价一致 —— 对不上说明表单是过期的。
    if (plan.oldPrice() != null
        && plan.oldPrice().setScale(PriceAdjustmentRules.PRICE_SCALE, PriceAdjustmentRules.ROUNDING)
            .compareTo(price.amount().setScale(PriceAdjustmentRules.PRICE_SCALE, PriceAdjustmentRules.ROUNDING))
            != 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "🔴 你看到的旧价（" + plan.oldPrice() + "）已经不是当前价了（现在是 " + price.amount() + " "
              + price.currency() + "，观测于 " + price.observedAt() + "）。"
              + "请刷新页面重新确认后再提交 —— 本模块不会照着一个过期的旧价建单。");
    }

    var oldPrice = price.amount();
    var newPrice = PriceAdjustmentRules.newPrice(plan.newPrice());
    if (newPrice == null) {
      throw ProfitCalculatorService.badRequest(
          "新价落定后不是正价 —— 把价格改成 0 就是「免费」，那不是调价。");
    }
    var delta = PriceAdjustmentRules.delta(oldPrice, newPrice);
    var rate = PriceAdjustmentRules.rate(oldPrice, newPrice);

    // ---- 改价前后的利润：全部来自 5.3.1.1 的引擎（本模块零算法）----
    var before = calculate(plan, sku.id(), oldPrice, bearer);
    var after = calculate(plan, sku.id(), newPrice, bearer);

    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    // 🔴 标题进 identity 的审批实例（subjectTitle，@Size(max=200)）—— 这里先截到 200。
    String title =
        trim(
            plan.title() != null && !plan.title().isBlank()
                ? plan.title().trim()
                : "调价 " + sku.code() + " " + plan.platformCode() + "/" + plan.siteCode() + " "
                    + oldPrice.toPlainString() + " → " + newPrice.toPlainString() + " "
                    + price.currency(),
            200);

    sql.execute(
        "insert into price_adjustment_order(id,tenant_id,status,version,sku_id,platform_code,"
            + "site_code,currency,title,reason,note,quantity,cost_currency,fx_rate,"
            + "platform_fee_rate,platform_fee_fixed,logistics_cost_per_unit,monthly_fixed_cost,"
            + "cost_window_from,cost_window_to,approval_required,approval_reason,"
            + "created_by,created_at,updated_at)"
            + " values(?::uuid,?::uuid,'DRAFT',1,?::uuid,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,false,'',?::uuid,"
            + "?::timestamptz,?::timestamptz)",
        id,
        actor.tenantId(),
        sku.id(),
        plan.platformCode(),
        plan.siteCode(),
        price.currency(),
        title,
        plan.reason(),
        plan.note(),
        plan.quantity(),
        plan.costCurrency(),
        plan.fxRate(),
        plan.platformFeeRate(),
        plan.platformFeeFixed(),
        plan.logisticsCostPerUnit(),
        plan.monthlyFixedCost(),
        plan.costWindowFrom(),
        plan.costWindowTo(),
        actor.userId(),
        now,
        now);

    sql.execute(
        "insert into price_adjustment_order_item(id,tenant_id,order_id,sku_id,platform_code,"
            + "site_code,currency,old_price,new_price,price_delta,price_rate,price_source,"
            + "price_observed_at,gross_profit_before,gross_margin_before,gross_profit_after,"
            + "gross_margin_after,decision_ready,loss_before,loss_after,created_at)"
            + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?,?,?,?,?,"
            + "?::timestamptz)",
        UUID.randomUUID(),
        actor.tenantId(),
        id,
        sku.id(),
        plan.platformCode(),
        plan.siteCode(),
        price.currency(),
        oldPrice,
        newPrice,
        delta,
        rate,
        price.source().name(),
        price.observedAt(),
        before.grossProfit(),
        before.grossMarginRate(),
        after.grossProfit(),
        after.grossMarginRate(),
        before.decisionReady() && after.decisionReady(),
        PriceAdjustmentRules.lossFlag(before.grossProfit()),
        PriceAdjustmentRules.lossFlag(after.grossProfit()),
        now);

    return get(id, false, bearer);
  }

  // ================================================================ 台账与详情

  /** 调价单列表（真分页 + 真筛选 + 真排序）。 */
  public PriceAdjustmentPage list(OrderQuery query) {
    var filter = orderFilter(query);
    long total =
        sql.fetchOne("select count(*)" + filter.from() + filter.where(), filter.binds().toArray())
            .get(0, Long.class);
    int limited = Math.min(Math.max(query.size() <= 0 ? 20 : query.size(), 1), MAX_PAGE_SIZE);
    int offset = Math.max(query.page(), 0) * limited;
    var rows =
        sql.fetch(
            "select o.id, o.status, o.version, o.sku_id, k.code as sku_code, k.name as sku_name,"
                + " o.platform_code, o.site_code, o.currency, o.title, o.reason, o.note,"
                + " o.quantity, o.cost_currency, o.fx_rate, o.platform_fee_rate,"
                + " o.platform_fee_fixed, o.logistics_cost_per_unit, o.monthly_fixed_cost,"
                + " o.cost_window_from, o.cost_window_to, o.approval_required,"
                + " o.approval_decision, o.approval_source, o.approval_rule_id,"
                + " o.approval_rule_name, o.approval_flow_id, o.approval_flow_name,"
                + " o.approval_reason, o.approval_instance_id, o.approval_instance_no,"
                + " o.approval_submitted_at, o.approval_decided_at, o.approval_decided_by,"
                + " o.approval_sync_error, o.submitted_at, o.executed_at, o.rejected_at,"
                + " o.rejected_by, o.reject_reason, o.created_by, o.created_at, o.updated_at,"
                + " i.old_price, i.new_price, i.price_delta, i.price_rate, i.price_source,"
                + " i.price_observed_at, i.gross_profit_before, i.gross_margin_before,"
                + " i.gross_profit_after, i.gross_margin_after, i.decision_ready, i.loss_before,"
                + " i.loss_after, i.id as item_id"
                + filter.from()
                + filter.where()
                + filter.order()
                + " limit ? offset ?",
            concat(filter.binds(), List.of(limited, offset)).toArray());
    var items = new ArrayList<PriceAdjustmentDetail>();
    for (var row : rows) items.add(detailOf(row));
    return new PriceAdjustmentPage(items, Math.max(query.page(), 0), limited, total);
  }

  /**
   * 调价单详情。
   *
   * @param refreshApproval true 时顺带**拉一次**审批状态（读详情即校验收到的结论）
   * @param bearer 调用方的会话（拉审批状态时要透传给 identity）
   */
  public PriceAdjustmentDetail get(UUID id, boolean refreshApproval, String bearer) {
    if (refreshApproval) {
      tryRefreshApproval(id, bearer);
    }
    var row =
        sql.fetchOne(
            "select o.id, o.status, o.version, o.sku_id, k.code as sku_code, k.name as sku_name,"
                + " o.platform_code, o.site_code, o.currency, o.title, o.reason, o.note,"
                + " o.quantity, o.cost_currency, o.fx_rate, o.platform_fee_rate,"
                + " o.platform_fee_fixed, o.logistics_cost_per_unit, o.monthly_fixed_cost,"
                + " o.cost_window_from, o.cost_window_to, o.approval_required,"
                + " o.approval_decision, o.approval_source, o.approval_rule_id,"
                + " o.approval_rule_name, o.approval_flow_id, o.approval_flow_name,"
                + " o.approval_reason, o.approval_instance_id, o.approval_instance_no,"
                + " o.approval_submitted_at, o.approval_decided_at, o.approval_decided_by,"
                + " o.approval_sync_error, o.submitted_at, o.executed_at, o.rejected_at,"
                + " o.rejected_by, o.reject_reason, o.created_by, o.created_at, o.updated_at,"
                + " i.old_price, i.new_price, i.price_delta, i.price_rate, i.price_source,"
                + " i.price_observed_at, i.gross_profit_before, i.gross_margin_before,"
                + " i.gross_profit_after, i.gross_margin_after, i.decision_ready, i.loss_before,"
                + " i.loss_after, i.id as item_id"
                + " from price_adjustment_order o"
                + " join sku k on k.tenant_id=o.tenant_id and k.id=o.sku_id"
                + " join price_adjustment_order_item i on i.tenant_id=o.tenant_id and i.order_id=o.id"
                + " where o.tenant_id="
                + TENANT
                + " and o.id=?",
            id);
    if (row == null) throw ProfitCalculatorService.notFound("调价单不存在");
    return detailOf(row);
  }

  // ================================================================ 状态流转

  /**
   * 提交（草稿 → 待审 / 已批）。
   *
   * <p>🔴 提交前先向 17 组织求值：命中规则 ⇒ 提交审批实例并停在「待审」；
   * 免审 / 未配规则 ⇒ 直接进「已批」。
   *
   * <p>🔴 **审批服务不可达 ⇒ 停在「待审」并如实标出错误**，**绝不**自动当成已批。
   */
  public PriceAdjustmentDetail submit(
      AccessService.Access actor, UUID id, PriceAdjustmentExpectedVersionRequest request, String bearer) {
    if (request == null || request.expectedVersion() == null) {
      throw ProfitCalculatorService.badRequest("必须带上你读到的版本号（expectedVersion）");
    }
    var order = requireOrder(id, bearer);
    requireVersion(order, request.expectedVersion());
    if (order.status() != Status.DRAFT) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "只有草稿可以提交，这张单当前是「" + order.status().label() + "」。");
    }
    var item = order.items().get(0);
    BigDecimal amount = PriceAdjustmentRules.amount(item.oldPrice(), item.newPrice());
    OffsetDateTime now = OffsetDateTime.now();

    PriceAdjustmentApprovalPort.Evaluation evaluation;
    try {
      evaluation =
          approvals.evaluate(
              new PriceAdjustmentApprovalPort.EvaluationInput(
                  PriceAdjustmentApprovalPort.DOCUMENT_TYPE, order.currency(), amount),
              bearer);
    } catch (PriceAdjustmentApprovalPort.ApprovalUnavailableException e) {
      // 🔴 如实降级：停在待审 + 记下「审批服务不可达」，**不**当成已批。
      sql.execute(
          "update price_adjustment_order set status='PENDING_APPROVAL',"
              + " approval_required=true, approval_decision='SUBMIT_FOR_APPROVAL',"
              + " approval_source='RULE', approval_sync_error=?,"
              + " submitted_at=coalesce(submitted_at,?::timestamptz),"
              + " updated_at=?::timestamptz, version=version+1"
              + " where tenant_id="
              + TENANT
              + " and id=? and version=?",
          trim(e.getMessage(), 500),
          now,
          now,
          id,
          request.expectedVersion());
      return get(id, false, bearer);
    }

    boolean requiresApproval = evaluation.requiresApproval();
    String reason = trim(evaluation.reason(), 500);
    if (!requiresApproval) {
      // 免审（含 UNCONTROLLED：本租户没配规则 ⇒ 默认放行）。
      PriceAdjustmentRules.transition(Status.DRAFT, Status.APPROVED);
      sql.execute(
          "update price_adjustment_order set status='APPROVED', approval_required=false,"
              + " approval_decision=?, approval_source=?, approval_rule_id=?::uuid,"
              + " approval_rule_name=?, approval_flow_id=?::uuid, approval_flow_name=?,"
              + " approval_reason=?, approval_sync_error='', submitted_at=?::timestamptz,"
              + " updated_at=?::timestamptz, version=version+1"
              + " where tenant_id="
              + TENANT
              + " and id=? and version=?",
          "EXEMPT",
          evaluation.source(),
          evaluation.matchedRuleId(),
          trim(evaluation.matchedRuleName(), 100),
          evaluation.targetFlowId(),
          trim(evaluation.targetFlowName(), 100),
          reason,
          now,
          now,
          id,
          request.expectedVersion());
      return get(id, false, bearer);
    }

    // 需要审批：把结论与目标流程先记上，再投实例。
    sql.execute(
        "update price_adjustment_order set status='PENDING_APPROVAL', approval_required=true,"
            + " approval_decision='SUBMIT_FOR_APPROVAL', approval_source=?, approval_rule_id=?::uuid,"
            + " approval_rule_name=?, approval_flow_id=?::uuid, approval_flow_name=?,"
            + " approval_reason=?, approval_sync_error='', submitted_at=?::timestamptz,"
            + " updated_at=?::timestamptz, version=version+1"
            + " where tenant_id="
            + TENANT
            + " and id=? and version=?",
        evaluation.source(),
        evaluation.matchedRuleId(),
        trim(evaluation.matchedRuleName(), 100),
        evaluation.targetFlowId(),
        trim(evaluation.targetFlowName(), 100),
        reason,
        now,
        now,
        id,
        request.expectedVersion());

    if (evaluation.targetFlowId() == null) {
      sql.execute(
          "update price_adjustment_order set approval_sync_error=?, updated_at=?::timestamptz"
              + " where tenant_id=" + TENANT + " and id=?",
          "命中的审批规则没有指定目标审批流程，审批无法发起 —— 请在 17 组织侧为该规则补上目标流程。"
              + "本模块**不会**在没有流程的情况下把单子当成已批。",
          now,
          id);
      return get(id, false, bearer);
    }

    submitApprovalInstance(actor, order, evaluation, bearer, now);
    return get(id, false, bearer);
  }

  /**
   * 执行（已批 → 已执行）：新价生效，并产出「待回写」事实给 5.4.2.1。
   *
   * <p>🔴 本模块**绝不**自己去调平台 API —— 平台定价规则（最低价 / 自动比价）
   * 与「被平台调整后的实际价」都归 5.4.2.1。
   */
  public PriceAdjustmentDetail execute(
      AccessService.Access actor, UUID id, PriceAdjustmentExpectedVersionRequest request, String bearer) {
    if (request == null || request.expectedVersion() == null) {
      throw ProfitCalculatorService.badRequest("必须带上你读到的版本号（expectedVersion）");
    }
    var order = requireOrder(id, bearer);
    requireVersion(order, request.expectedVersion());
    if (order.status() != Status.APPROVED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "只有已批的单子可以执行，这张单当前是「" + order.status().label() + "」。"
              + (order.status() == Status.PENDING_APPROVAL
                  ? "它还在等 17 组织那边的审批结论 —— 请先刷新审批状态。"
                  : ""));
    }
    PriceAdjustmentRules.transition(Status.APPROVED, Status.EXECUTED);
    var item = order.items().get(0);
    OffsetDateTime now = OffsetDateTime.now();

    sql.execute(
        "update price_adjustment_order set status='EXECUTED', executed_at=?::timestamptz,"
            + " updated_at=?::timestamptz, version=version+1"
            + " where tenant_id="
            + TENANT
            + " and id=? and version=?",
        now,
        now,
        id,
        request.expectedVersion());

    // 🔴 产出「待回写」事实：这是本模块对下游 5.4.2.1 的**全部**交付 ——
    //   本模块不调平台 API，平台侧的事实归下游。
    sql.execute(
        "insert into price_adjustment_writeback(id,tenant_id,order_id,order_item_id,sku_id,"
            + "platform_code,site_code,currency,expected_price,previous_price,status,"
            + "idempotency_key,created_at)"
            + " values(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?,?,'PENDING',?,?::timestamptz)"
            + " on conflict (tenant_id, idempotency_key) do nothing",
        UUID.randomUUID(),
        actor.tenantId(),
        id,
        item.id(),
        item.skuId(),
        item.platformCode(),
        item.siteCode(),
        item.currency(),
        item.newPrice(),
        item.oldPrice(),
        "price-adjustment:" + id,
        now);
    return get(id, false, bearer);
  }

  /**
   * 驳回（草稿 → 已驳回 / 待审 → 已驳回）。
   *
   * <p>🔴 **待审且已有在办实例时，本模块不代劳驳回**：identity 那边没有撤销实例的端点，
   * 在本地单方面撤掉会让那边留下一条**永远没人管的待办**。这种情况明确拒绝，
   * 并告诉用户去 17 组织的待办里驳回。
   */
  public PriceAdjustmentDetail reject(
      AccessService.Access actor, UUID id, PriceAdjustmentRejectRequest request, String bearer) {
    if (request == null || request.expectedVersion() == null) {
      throw ProfitCalculatorService.badRequest("必须带上你读到的版本号（expectedVersion）");
    }
    if (request.reason() == null || request.reason().isBlank()) {
      throw ProfitCalculatorService.badRequest("必须写明驳回原因");
    }
    var order = requireOrder(id, bearer);
    requireVersion(order, request.expectedVersion());
    if (order.status() == Status.PENDING_APPROVAL && order.approvalInstanceId() != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "🔴 这张单已经在 17 组织那边有一条在办的审批实例（"
              + order.approvalInstanceNo()
              + "）—— 请去那边的待办里驳回它。本服务不能只在自己这边撤掉："
              + "那边会留下一条永远没人管的待办。");
    }
    if (!PriceAdjustmentRules.canTransition(order.status(), Status.REJECTED)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "这张单当前是「" + order.status().label() + "」，不能驳回。");
    }
    PriceAdjustmentRules.transition(order.status(), Status.REJECTED);
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update price_adjustment_order set status='REJECTED', rejected_at=?::timestamptz,"
            + " rejected_by=?::uuid, reject_reason=?, updated_at=?::timestamptz, version=version+1"
            + " where tenant_id="
            + TENANT
            + " and id=? and version=?",
        now,
        actor.userId(),
        request.reason().trim(),
        now,
        id,
        request.expectedVersion());
    return get(id, false, bearer);
  }

  /**
   * 刷新审批状态（**拉取式**同步）。
   *
   * <p>🔴 审批人在 identity 那边决定，本模块**不发明审批流** ——
   * 这里只把 identity 侧的结论取回来，落到「已批 / 已驳回」上。
   */
  public PriceAdjustmentDetail refreshApproval(
      AccessService.Access actor, UUID id, String bearer) {
    var order = requireOrder(id, bearer);
    if (order.status() != Status.PENDING_APPROVAL) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "只有待审的单子需要刷新审批状态，这张单当前是「" + order.status().label() + "」。");
    }
    OffsetDateTime now = OffsetDateTime.now();
    if (order.approvalInstanceId() == null) {
      // 还没有实例：重新求值一次（可能是上次提交时审批服务不可达）。
      var amount =
          PriceAdjustmentRules.amount(
              order.items().get(0).oldPrice(), order.items().get(0).newPrice());
      try {
        var evaluation =
            approvals.evaluate(
                new PriceAdjustmentApprovalPort.EvaluationInput(
                    PriceAdjustmentApprovalPort.DOCUMENT_TYPE, order.currency(), amount),
                bearer);
        if (!evaluation.requiresApproval()) {
          sql.execute(
              "update price_adjustment_order set status='APPROVED', approval_required=false,"
                  + " approval_decision='EXEMPT', approval_source=?, approval_reason=?,"
                  + " approval_sync_error='', updated_at=?::timestamptz, version=version+1"
                  + " where tenant_id=" + TENANT + " and id=?",
              evaluation.source(),
              trim(evaluation.reason(), 500),
              now,
              id);
          return get(id, false, bearer);
        }
        submitApprovalInstance(actor, order, evaluation, bearer, now);
        return get(id, false, bearer);
      } catch (PriceAdjustmentApprovalPort.ApprovalUnavailableException e) {
        sql.execute(
            "update price_adjustment_order set approval_sync_error=?, updated_at=?::timestamptz"
                + " where tenant_id=" + TENANT + " and id=?",
            trim(e.getMessage(), 500),
            now,
            id);
        return get(id, false, bearer);
      }
    }

    try {
      var status = approvals.status(order.approvalInstanceId(), bearer);
      String instanceStatus = status.status() == null ? "PENDING" : status.status();
      if ("APPROVED".equals(instanceStatus)) {
        PriceAdjustmentRules.transition(Status.PENDING_APPROVAL, Status.APPROVED);
        sql.execute(
            "update price_adjustment_order set status='APPROVED', approval_decided_at=?::timestamptz,"
                + " approval_sync_error='', updated_at=?::timestamptz, version=version+1"
                + " where tenant_id=" + TENANT + " and id=?",
            now,
            now,
            id);
      } else if ("REJECTED".equals(instanceStatus)) {
        PriceAdjustmentRules.transition(Status.PENDING_APPROVAL, Status.REJECTED);
        sql.execute(
            "update price_adjustment_order set status='REJECTED', rejected_at=?::timestamptz,"
                + " reject_reason=?, approval_decided_at=?::timestamptz, approval_sync_error='',"
                + " updated_at=?::timestamptz, version=version+1"
                + " where tenant_id=" + TENANT + " and id=?",
            now,
            "17 组织那边驳回了这张调价单（审批实例 " + order.approvalInstanceNo() + "）。",
            now,
            now,
            id);
      } else {
        sql.execute(
            "update price_adjustment_order set approval_sync_error='', updated_at=?::timestamptz"
                + " where tenant_id=" + TENANT + " and id=?",
            now,
            id);
      }
    } catch (PriceAdjustmentApprovalPort.ApprovalUnavailableException e) {
      sql.execute(
          "update price_adjustment_order set approval_sync_error=?, updated_at=?::timestamptz"
              + " where tenant_id=" + TENANT + " and id=?",
          trim(e.getMessage(), 500),
          now,
          id);
    }
    return get(id, false, bearer);
  }

  // ================================================================ 待回写事实

  /** 待回写事实列表（真分页 + 真筛选）。下游 5.4.2.1 消费它。 */
  public PriceAdjustmentWritebackPage listWritebacks(
      String status, UUID skuId, String platformCode, String siteCode, int page, int size) {
    var where = new StringBuilder(" where w.tenant_id=" + TENANT);
    var binds = new ArrayList<Object>();
    if (status != null && !status.isBlank()) {
      where.append(" and w.status=?");
      binds.add(status.trim().toUpperCase(java.util.Locale.ROOT));
    }
    if (skuId != null) {
      where.append(" and w.sku_id=?::uuid");
      binds.add(skuId);
    }
    if (platformCode != null && !platformCode.isBlank()) {
      where.append(" and w.platform_code=?");
      binds.add(PriceAdjustmentRules.code(platformCode));
    }
    if (siteCode != null && !siteCode.isBlank()) {
      where.append(" and w.site_code=?");
      binds.add(PriceAdjustmentRules.code(siteCode));
    }
    long total =
        sql.fetchOne("select count(*) from price_adjustment_writeback w" + where, binds.toArray())
            .get(0, Long.class);
    int limited = Math.min(Math.max(size <= 0 ? 20 : size, 1), MAX_PAGE_SIZE);
    int offset = Math.max(page, 0) * limited;
    var rows =
        sql.fetch(
            "select w.id, w.order_id, w.order_item_id, w.sku_id, k.code as sku_code,"
                + " w.platform_code, w.site_code, w.currency, w.expected_price, w.previous_price,"
                + " w.status, w.idempotency_key, w.acknowledged_at, w.ack_note, w.created_at"
                + " from price_adjustment_writeback w"
                + " join sku k on k.tenant_id=w.tenant_id and k.id=w.sku_id"
                + where
                + " order by w.created_at, w.id limit ? offset ?",
            concat(binds, List.of(limited, offset)).toArray());
    var items = new ArrayList<PriceAdjustmentWriteback>();
    for (var row : rows) {
      items.add(
          new PriceAdjustmentWriteback(
              row.get("id", UUID.class),
              row.get("order_id", UUID.class),
              row.get("order_item_id", UUID.class),
              row.get("sku_id", UUID.class),
              row.get("sku_code", String.class),
              row.get("platform_code", String.class),
              row.get("site_code", String.class),
              row.get("currency", String.class),
              row.get("expected_price", BigDecimal.class),
              row.get("previous_price", BigDecimal.class),
              row.get("status", String.class),
              row.get("idempotency_key", String.class),
              row.get("acknowledged_at", OffsetDateTime.class),
              row.get("ack_note", String.class),
              row.get("created_at", OffsetDateTime.class)));
    }
    return new PriceAdjustmentWritebackPage(items, Math.max(page, 0), limited, total);
  }

  /** 待回写事实的回执（由下游 5.4.2.1 调用）。 */
  public PriceAdjustmentWriteback ackWriteback(UUID id, PriceAdjustmentWritebackAckRequest request) {
    var row =
        sql.fetchOne(
            "select id from price_adjustment_writeback where tenant_id=" + TENANT + " and id=?",
            id);
    if (row == null) throw ProfitCalculatorService.notFound("待回写事实不存在");
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update price_adjustment_writeback set status='ACKNOWLEDGED', acknowledged_at=?::timestamptz,"
            + " ack_note=? where tenant_id=" + TENANT + " and id=? and status='PENDING'",
        now,
        request == null ? "" : trim(request.note(), 500),
        id);
    var updated =
        sql.fetchOne(
            "select w.id, w.order_id, w.order_item_id, w.sku_id, k.code as sku_code,"
                + " w.platform_code, w.site_code, w.currency, w.expected_price, w.previous_price,"
                + " w.status, w.idempotency_key, w.acknowledged_at, w.ack_note, w.created_at"
                + " from price_adjustment_writeback w"
                + " join sku k on k.tenant_id=w.tenant_id and k.id=w.sku_id"
                + " where w.tenant_id=" + TENANT + " and w.id=?",
            id);
    return new PriceAdjustmentWriteback(
        updated.get("id", UUID.class),
        updated.get("order_id", UUID.class),
        updated.get("order_item_id", UUID.class),
        updated.get("sku_id", UUID.class),
        updated.get("sku_code", String.class),
        updated.get("platform_code", String.class),
        updated.get("site_code", String.class),
        updated.get("currency", String.class),
        updated.get("expected_price", BigDecimal.class),
        updated.get("previous_price", BigDecimal.class),
        updated.get("status", String.class),
        updated.get("idempotency_key", String.class),
        updated.get("acknowledged_at", OffsetDateTime.class),
        updated.get("ack_note", String.class),
        updated.get("created_at", OffsetDateTime.class));
  }

  // ================================================================ 内部

  /** 归一化后的调价计划。 */
  private record Plan(
      UUID skuId,
      String platformCode,
      String siteCode,
      String currency,
      String costCurrency,
      BigDecimal fxRate,
      BigDecimal newPrice,
      BigDecimal oldPrice,
      BigDecimal quantity,
      BigDecimal platformFeeRate,
      BigDecimal platformFeeFixed,
      BigDecimal logisticsCostPerUnit,
      BigDecimal monthlyFixedCost,
      LocalDate costWindowFrom,
      LocalDate costWindowTo,
      String title,
      String reason,
      String note) {}

  /** 取到的当前价。 */
  private record Price(
      BigDecimal amount, PriceSource source, String currency, OffsetDateTime observedAt) {}

  /** 目标 SKU。 */
  private record SkuRow(UUID id, String code, String name) {}

  /** 归一化 + 校验：不合法就 400。 */
  private Plan planOf(PriceAdjustmentRequest request) {
    if (request == null) throw ProfitCalculatorService.badRequest("调价请求不能为空");
    if (request.skuId() == null) throw ProfitCalculatorService.badRequest("必须指定 SKU");

    String platformCode = PriceAdjustmentRules.code(request.platformCode());
    String siteCode = PriceAdjustmentRules.code(request.siteCode());
    if (platformCode == null || siteCode == null) {
      throw ProfitCalculatorService.badRequest("必须指定平台与店铺（站点）");
    }

    String currency = PriceAdjustmentRules.code(request.currency());
    if (currency == null || !currency.matches("^[A-Z]{3}$")) {
      throw ProfitCalculatorService.badRequest("必须指定售价币种（三位大写字母，ISO 4217）");
    }
    String costCurrency = PriceAdjustmentRules.code(request.costCurrency());
    if (costCurrency == null) costCurrency = currency;
    if (!costCurrency.matches("^[A-Z]{3}$")) {
      throw ProfitCalculatorService.badRequest("成本币种必须是三位大写字母");
    }
    BigDecimal fxRate = request.fxRate();
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
              + "。绝不按 1:1 折算，那会把两个币种的金额加在一起。");
    }

    if (request.newPrice() == null) throw ProfitCalculatorService.badRequest("必须输入新价");
    BigDecimal quantity = request.quantity() == null ? BigDecimal.ONE : request.quantity();
    if (quantity.signum() <= 0) throw ProfitCalculatorService.badRequest("数量必须大于 0");
    if (request.platformFeeRate() != null
        && (request.platformFeeRate().signum() < 0
            || request.platformFeeRate().compareTo(BigDecimal.ONE) >= 0)) {
      throw ProfitCalculatorService.badRequest("平台费率是小数口径（0.15 表示 15%），必须在 0 与 1 之间");
    }
    if (request.platformFeeFixed() != null && request.platformFeeFixed().signum() < 0) {
      throw ProfitCalculatorService.badRequest("平台固定费不能为负");
    }
    if (request.logisticsCostPerUnit() != null && request.logisticsCostPerUnit().signum() < 0) {
      throw ProfitCalculatorService.badRequest("物流每单位运费不能为负");
    }
    if (request.monthlyFixedCost() != null && request.monthlyFixedCost().signum() < 0) {
      throw ProfitCalculatorService.badRequest("月度固定成本不能为负");
    }

    LocalDate from =
        request.costWindowFrom() == null
            ? LocalDate.now().minusDays(PriceAdjustmentRules.DEFAULT_WINDOW_DAYS)
            : request.costWindowFrom();
    LocalDate to = request.costWindowTo() == null ? LocalDate.now() : request.costWindowTo();
    if (to.isBefore(from)) throw ProfitCalculatorService.badRequest("成本口径窗口的终点不能早于起点");

    String reason = PriceAdjustmentRules.trimToEmpty(request.reason());
    if (reason.isEmpty()) throw ProfitCalculatorService.badRequest("必须说明调价原因");

    return new Plan(
        request.skuId(),
        platformCode,
        siteCode,
        currency,
        costCurrency,
        fxRate,
        request.newPrice(),
        request.oldPrice(),
        quantity,
        request.platformFeeRate(),
        request.platformFeeFixed(),
        request.logisticsCostPerUnit(),
        request.monthlyFixedCost(),
        from,
        to,
        PriceAdjustmentRules.trimToNull(request.title()),
        reason,
        PriceAdjustmentRules.trimToEmpty(request.note()));
  }

  /** 目标 SKU 必须在本租户存在且可见。 */
  private SkuRow requireSku(UUID skuId) {
    var row =
        sql.fetchOne(
            "select k.id, k.code, k.name from sku k"
                + " join spu p on p.tenant_id=k.tenant_id and p.id=k.spu_id"
                + " where k.tenant_id="
                + TENANT
                + " and k.id=?",
            skuId);
    if (row == null) throw ProfitCalculatorService.notFound("SKU 不存在或不属于当前租户");
    return new SkuRow(
        row.get("id", UUID.class), row.get("code", String.class), row.get("name", String.class));
  }

  /**
   * 取当前价：catalog 内**已有**的价格事实里时间最新的一条。
   *
   * <p>🔴 两个来源：① 5.5.2.1 的价格观测台账（最新一条观测，优先我方价）；
   * ② 5.4.1.2 批量调价明细里记下的新价（它可能比观测更新）。
   *
   * <p>🔴 **本模块只读这两张表**（它们各有唯一的写入所有者），取不到就返回 null。
   */
  private Price resolvePrice(UUID skuId, String platformCode, String siteCode) {
    var observation =
        sql.fetchOne(
            "select distinct on (o.sku_id, o.platform_code, o.site_code)"
                + " o.sku_id, o.platform_code, o.site_code, o.internal_price, o.platform_price,"
                + " o.currency, o.observed_at"
                + " from price_anomaly_observation o"
                + " where o.tenant_id="
                + TENANT
                + " and o.sku_id=?::uuid and o.platform_code=? and o.site_code=?"
                + " order by o.sku_id, o.platform_code, o.site_code, o.observed_at desc, o.id desc",
            skuId,
            platformCode,
            siteCode);
    Price fromObservation = null;
    if (observation != null) {
      BigDecimal amount = observation.get("internal_price", BigDecimal.class);
      PriceSource source = PriceSource.PRICE_OBSERVATION;
      if (amount == null) {
        amount = observation.get("platform_price", BigDecimal.class);
      }
      if (amount != null) {
        fromObservation =
            new Price(
                amount,
                source,
                observation.get("currency", String.class),
                observation.get("observed_at", OffsetDateTime.class));
      }
    }

    var batch =
        sql.fetchOne(
            "select i.new_price, i.currency, i.created_at from batch_price_adjustment_item i"
                + " where i.tenant_id="
                + TENANT
                + " and i.sku_id=?::uuid and i.platform_code=? and i.site_code=?"
                + " and i.status='WILL_ADJUST' and i.new_price is not null"
                + " order by i.created_at desc, i.id desc limit 1",
            skuId,
            platformCode,
            siteCode);
    Price fromBatch = null;
    if (batch != null) {
      fromBatch =
          new Price(
              batch.get("new_price", BigDecimal.class),
              PriceSource.BATCH_ADJUSTMENT,
              batch.get("currency", String.class),
              batch.get("created_at", OffsetDateTime.class));
    }

    if (fromObservation == null) return fromBatch;
    if (fromBatch == null) return fromObservation;
    // 🔴 谁的时刻更晚就用谁；同一时刻以**我方自己改过的价**为准（那是一次已发生的变更，
    //   而观测可能只是看到了变更前的价）。
    if (fromBatch.observedAt() != null && fromObservation.observedAt() != null) {
      return fromBatch.observedAt().isAfter(fromObservation.observedAt())
          ? fromBatch
          : fromObservation;
    }
    return fromBatch.observedAt() != null ? fromBatch : fromObservation;
  }

  /** 一次 5.3.1.1 的测算（改价前或改价后）。 */
  private ProfitCalculatorModels.ProfitCalculation calculate(
      Plan plan, UUID skuId, BigDecimal price, String bearer) {
    return calculator.calculate(
        new ProfitCalculatorModels.CalculateRequest(
            skuId,
            plan.siteCode(),
            plan.platformCode(),
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
            "调价单 5.4.1.1 改价前后测算"),
        bearer);
  }

  /** 调价单必须存在且属于当前租户，否则 404（不泄露「存在」这个事实）。 */
  private PriceAdjustmentDetail requireOrder(UUID id, String bearer) {
    return get(id, false, bearer);
  }

  /** 乐观锁：版本对不上 ⇒ 409。 */
  private void requireVersion(PriceAdjustmentDetail order, int expectedVersion) {
    if (order.version() != expectedVersion) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "🔴 版本冲突：你带的是 " + expectedVersion + "，这张单当前是 " + order.version()
              + "（有人已经动过它，或者它已经不在你以为的状态上了）。"
              + "请刷新后用新的版本号重试 —— 本模块不会拿过期的版本号继续改。");
    }
  }

  /** 提交审批实例（幂等：409 当作幂等命中处理）。 */
  private void submitApprovalInstance(
      AccessService.Access actor,
      PriceAdjustmentDetail order,
      PriceAdjustmentApprovalPort.Evaluation evaluation,
      String bearer,
      OffsetDateTime now) {
    var input =
        new PriceAdjustmentApprovalPort.SubmitInput(
            evaluation.targetFlowId(),
            PriceAdjustmentApprovalPort.SOURCE_SYSTEM,
            order.id().toString(),
            order.title(),
            order.currency(),
            PriceAdjustmentRules.amount(
                order.items().get(0).oldPrice(), order.items().get(0).newPrice()));
    try {
      var submitted = approvals.submit(input, bearer);
      sql.execute(
          "update price_adjustment_order set approval_instance_id=?::uuid,"
              + " approval_instance_no=?, approval_submitted_at=?::timestamptz,"
              + " approval_sync_error='', updated_at=?::timestamptz where tenant_id="
              + TENANT
              + " and id=?",
          submitted.id(),
          submitted.instanceNo(),
          now,
          now,
          order.id());
    } catch (PriceAdjustmentApprovalPort.ApprovalConflictException e) {
      // 🔴 409 = 同一来源单据已有一条在办实例 ⇒ **幂等命中**，不是错误。
      //   绝不重投一条（那会在 17 组织那边造出第二条待办）。
      var existing = approvals.findLive(order.id().toString(), bearer);
      if (existing == null) {
        sql.execute(
            "update price_adjustment_order set approval_sync_error=?, updated_at=?::timestamptz"
                + " where tenant_id=" + TENANT + " and id=?",
            "审批实例已存在（幂等命中），但实例号暂时读不回来 —— 请稍后再刷新一次审批状态。",
            now,
            order.id());
        return;
      }
      sql.execute(
          "update price_adjustment_order set approval_instance_id=?::uuid,"
              + " approval_instance_no=?, approval_submitted_at=coalesce(approval_submitted_at,"
              + "?::timestamptz), approval_sync_error='', updated_at=?::timestamptz where tenant_id="
              + TENANT
              + " and id=?",
          existing.id(),
          existing.instanceNo(),
          now,
          now,
          order.id());
    } catch (PriceAdjustmentApprovalPort.ApprovalUnavailableException e) {
      // 🔴 如实降级：停在待审 + 标出「审批服务不可达」，**绝不**当成已批。
      sql.execute(
          "update price_adjustment_order set approval_sync_error=?, updated_at=?::timestamptz"
              + " where tenant_id=" + TENANT + " and id=?",
          trim(e.getMessage(), 500),
          now,
          order.id());
    }
  }

  /** 读详情时的顺带刷新：失败**如实**记进 {@code approval_sync_error}，不改状态。 */
  private void tryRefreshApproval(UUID id, String bearer) {
    var row =
        sql.fetchOne(
            "select status, approval_instance_id from price_adjustment_order"
                + " where tenant_id=" + TENANT + " and id=?",
            id);
    if (row == null) return;
    if (!"PENDING_APPROVAL".equals(row.get("status", String.class))) return;
    UUID instanceId = row.get("approval_instance_id", UUID.class);
    if (instanceId == null) return;
    try {
      var status = approvals.status(instanceId, bearer);
      String instanceStatus = status.status() == null ? "PENDING" : status.status();
      OffsetDateTime now = OffsetDateTime.now();
      if ("APPROVED".equals(instanceStatus)) {
        sql.execute(
            "update price_adjustment_order set status='APPROVED', approval_decided_at=?::timestamptz,"
                + " approval_sync_error='', updated_at=?::timestamptz, version=version+1"
                + " where tenant_id=" + TENANT + " and id=? and status='PENDING_APPROVAL'",
            now,
            now,
            id);
      } else if ("REJECTED".equals(instanceStatus)) {
        sql.execute(
            "update price_adjustment_order set status='REJECTED', rejected_at=?::timestamptz,"
                + " reject_reason=?, approval_decided_at=?::timestamptz, approval_sync_error='',"
                + " updated_at=?::timestamptz, version=version+1"
                + " where tenant_id=" + TENANT + " and id=? and status='PENDING_APPROVAL'",
            now,
            "17 组织那边驳回了这张调价单。",
            now,
            now,
            id);
      }
    } catch (RuntimeException e) {
      // 读路径上的失败**如实**记下来，但不改变状态、更不报错 ——
      // 读一张单子不该因为审批服务抖动而读不出来。
      sql.execute(
          "update price_adjustment_order set approval_sync_error=?, updated_at=?::timestamptz"
              + " where tenant_id=" + TENANT + " and id=?",
          trim(e.getMessage(), 500),
          OffsetDateTime.now(),
          id);
    }
  }

  /** 一行的明细。 */
  private PriceAdjustmentOrderItem itemOf(org.jooq.Record row) {
    var source = PriceSource.valueOf(row.get("price_source", String.class));
    return new PriceAdjustmentOrderItem(
        row.get("item_id", UUID.class),
        row.get("id", UUID.class),
        row.get("sku_id", UUID.class),
        row.get("sku_code", String.class),
        row.get("sku_name", String.class),
        row.get("platform_code", String.class),
        row.get("site_code", String.class),
        row.get("currency", String.class),
        row.get("old_price", BigDecimal.class),
        row.get("new_price", BigDecimal.class),
        row.get("price_delta", BigDecimal.class),
        row.get("price_rate", BigDecimal.class),
        source,
        source.label(),
        row.get("price_observed_at", OffsetDateTime.class),
        row.get("gross_profit_before", BigDecimal.class),
        row.get("gross_margin_before", BigDecimal.class),
        row.get("gross_profit_after", BigDecimal.class),
        row.get("gross_margin_after", BigDecimal.class),
        Boolean.TRUE.equals(row.get("decision_ready", Boolean.class)),
        row.get("loss_before", Boolean.class),
        row.get("loss_after", Boolean.class));
  }

  /** 一张调价单。 */
  private PriceAdjustmentDetail detailOf(org.jooq.Record row) {
    var status = Status.parse(row.get("status", String.class));
    var source = PriceSource.valueOf(row.get("price_source", String.class));
    return new PriceAdjustmentDetail(
        row.get("id", UUID.class),
        status,
        status == null ? "" : status.label(),
        row.get("version", Integer.class),
        row.get("sku_id", UUID.class),
        row.get("sku_code", String.class),
        row.get("sku_name", String.class),
        row.get("platform_code", String.class),
        row.get("site_code", String.class),
        row.get("currency", String.class),
        row.get("title", String.class),
        row.get("reason", String.class),
        row.get("note", String.class),
        row.get("quantity", BigDecimal.class),
        row.get("cost_currency", String.class),
        row.get("fx_rate", BigDecimal.class),
        row.get("platform_fee_rate", BigDecimal.class),
        row.get("platform_fee_fixed", BigDecimal.class),
        row.get("logistics_cost_per_unit", BigDecimal.class),
        row.get("monthly_fixed_cost", BigDecimal.class),
        row.get("cost_window_from", LocalDate.class),
        row.get("cost_window_to", LocalDate.class),
        row.get("old_price", BigDecimal.class),
        row.get("new_price", BigDecimal.class),
        row.get("price_delta", BigDecimal.class),
        row.get("price_rate", BigDecimal.class),
        source,
        source.label(),
        row.get("price_observed_at", OffsetDateTime.class),
        row.get("gross_profit_before", BigDecimal.class),
        row.get("gross_margin_before", BigDecimal.class),
        row.get("gross_profit_after", BigDecimal.class),
        row.get("gross_margin_after", BigDecimal.class),
        Boolean.TRUE.equals(row.get("decision_ready", Boolean.class)),
        row.get("loss_before", Boolean.class),
        row.get("loss_after", Boolean.class),
        Boolean.TRUE.equals(row.get("approval_required", Boolean.class)),
        row.get("approval_decision", String.class),
        row.get("approval_source", String.class),
        row.get("approval_rule_id", UUID.class),
        row.get("approval_rule_name", String.class),
        row.get("approval_flow_id", UUID.class),
        row.get("approval_flow_name", String.class),
        row.get("approval_reason", String.class),
        row.get("approval_instance_id", UUID.class),
        row.get("approval_instance_no", String.class),
        row.get("approval_submitted_at", OffsetDateTime.class),
        row.get("approval_decided_at", OffsetDateTime.class),
        row.get("approval_decided_by", UUID.class),
        row.get("approval_sync_error", String.class),
        row.get("submitted_at", OffsetDateTime.class),
        row.get("executed_at", OffsetDateTime.class),
        row.get("rejected_at", OffsetDateTime.class),
        row.get("rejected_by", UUID.class),
        row.get("reject_reason", String.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class),
        List.of(itemOf(row)));
  }

  /** 台账筛选：真筛选在数据库层。 */
  private record Filter(String from, String where, String order, List<Object> binds) {}

  private Filter orderFilter(OrderQuery query) {
    var where = new StringBuilder(" where o.tenant_id=" + TENANT);
    var binds = new ArrayList<Object>();
    if (query.status() != null && !query.status().isBlank()) {
      var status = Status.parse(query.status());
      if (status == null) {
        throw ProfitCalculatorService.badRequest(
            "状态筛选只能是 DRAFT / PENDING_APPROVAL / APPROVED / EXECUTED / REJECTED");
      }
      where.append(" and o.status=?");
      binds.add(status.name());
    }
    if (query.skuId() != null) {
      where.append(" and o.sku_id=?::uuid");
      binds.add(query.skuId());
    }
    if (query.platformCode() != null && !query.platformCode().isBlank()) {
      where.append(" and o.platform_code=?");
      binds.add(PriceAdjustmentRules.code(query.platformCode()));
    }
    if (query.siteCode() != null && !query.siteCode().isBlank()) {
      where.append(" and o.site_code=?");
      binds.add(PriceAdjustmentRules.code(query.siteCode()));
    }
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.append(
          " and (k.code ilike ? or k.name ilike ? or o.title ilike ? or o.reason ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    var sort = Sort.parse(query.sort());
    String order =
        switch (sort == null ? Sort.NEWEST : sort) {
          case OLDEST -> " order by o.created_at, o.id";
          case AMOUNT_DESC -> " order by abs(i.price_delta) desc nulls last, o.created_at desc, o.id";
          case AMOUNT_ASC -> " order by abs(i.price_delta) asc nulls last, o.created_at desc, o.id";
          default -> " order by o.created_at desc, o.id";
        };
    return new Filter(
        " from price_adjustment_order o"
            + " join sku k on k.tenant_id=o.tenant_id and k.id=o.sku_id"
            + " join price_adjustment_order_item i on i.tenant_id=o.tenant_id and i.order_id=o.id",
        where.toString(),
        order,
        binds);
  }

  /** 截断到 DB 列宽。 */
  private static String trim(String value, int max) {
    if (value == null) return "";
    return value.length() <= max ? value : value.substring(0, max);
  }

  /** 拼绑定参数。 */
  @SafeVarargs
  private static List<Object> concat(List<Object> head, List<Object>... tail) {
    var out = new ArrayList<Object>(head);
    for (var part : tail) out.addAll(part);
    return out;
  }
}
