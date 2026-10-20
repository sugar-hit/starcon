package com.starcon.catalog;

import static com.starcon.catalog.PricingRuleModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.3.2.2 价格规则：定义自动调价规则（触发条件 + 动作 + 优先级 + 生效时段），
 * 并产出**命中记录**与**建议调价列表**。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：定义自动调价规则：触发条件（库存低于阈值/竞品降价/成本上涨/毛利低于下限/库龄超期）+ 动作（降价/涨价/暂停促销/下架），支持优先级与生效时段。
 *
 * **输入**：规则条件与动作；作用范围；生效时段。
 *
 * **输出**：规则记录 + 命中记录 + 建议调价列表。
 *
 * **关系**：上游 = 7 库存、5.3.2.1；下游 = 5.4.1.1。**自动调价必须先"建议"再"执行"**，高风险规则（降价超过 X%）需人工确认，避免错误规则批量改价造成损失。
 *
 * </blockquote>
 *
 * <h2>🔴 本类的六条纪律</h2>
 *
 * <ol>
 *   <li><b>🔴 先建议，再执行</b>。本类只产出<b>建议调价</b>；改价是 5.4.1.1 调价单的写入所有权。
 *       「执行」在本模块里 = 把建议放行（READY / CONFIRMED），本类<b>永远不写价格</b>。</li>
 *   <li><b>🔴 高风险不自动放行</b>：降价幅度超过规则阈值、成本不完整（复核不了毛利）、
 *       或非价格动作（暂停促销 / 下架）—— 三者任一成立都进 {@code PENDING_CONFIRMATION}，
 *       等人工确认。</li>
 *   <li><b>🔴 取不到数就不判定</b>。库存 / 库龄 / 价格观测 / 成本版本取不到时，
 *       记一条<b>未命中</b>的留痕并写明原因，<b>不</b>当成 0、<b>不</b>猜一个阈值。</li>
 *   <li><b>🔴 毛利只能来自 5.3.1.1</b>。本类不重写任何一套扣减公式：
 *       「毛利低于下限」与建议价的毛利复核都走 {@link ProfitCalculatorService}。</li>
 *   <li><b>🔴 跨服务只读走 HTTP</b>：库存与库龄在 warehouse 的库里，本库里没有它们的副本；
 *       本类只经 warehouse 的授权端点取数（AGENTS.md「模块边界纪律」）。</li>
 *   <li><b>🔴 租户只来自已认证成员关系</b>（{@link AccessService}），不读 {@code X-Tenant-Id} 头。
 *       每条 SQL 的 where 都带显式 {@code tenant_id=} 绑定，RLS 再兜一道。</li>
 * </ol>
 */
@Service
public class PricingRuleService {

  /** 租户上下文取法：RLS 已按它隔离，这里带上只是让执行计划走租户前缀的索引。 */
  private static final String TENANT = "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 台账页大小上限：与相邻模块一致。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 一次评估的 SKU 数上限：评估要跨服务取数（库存 / 库龄），规模必须封顶。 */
  private static final int MAX_EVALUATE_SKUS = 50;

  /** 一次评估的「规则 × SKU」判定对数上限：每对至少留一条留痕，规模必须封顶。 */
  private static final int MAX_EVALUATE_PAIRS = 500;

  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  private final DSLContext sql;
  private final ProfitCalculatorService calculator;
  private final InventoryAvailabilityService availability;
  private final InventoryAgingPort aging;

  public PricingRuleService(
      DSLContext sql,
      ProfitCalculatorService calculator,
      InventoryAvailabilityService availability,
      InventoryAgingPort aging) {
    this.sql = sql;
    this.calculator = calculator;
    this.availability = availability;
    this.aging = aging;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：触发条件 / 动作 / 作用范围 / 状态四张词表 + 口径说明。
   *
   * <p>页面固定显示它 —— 蓝图那句「自动调价必须先"建议"再"执行"」必须写在脸上，
   * 否则运营会把「建议调价」当成「已经改好的价」。
   */
  public PricingRuleCapabilities capabilities() {
    var triggers =
        List.of(
            new PricingRuleCapabilityItem(
                "INVENTORY_BELOW_THRESHOLD",
                "库存低于阈值",
                "可售量低于阈值 ⇒ 命中。可售量来自 7 库存的授权端点（warehouse）；"
                    + "本库里没有库存副本，取不到就不判定（不是当成 0）。",
                List.of("thresholdQuantity（件）")),
            new PricingRuleCapabilityItem(
                "COMPETITOR_PRICE_DROP",
                "竞品降价",
                "🔴 本仓**没有**竞品价采集模块 ⇒ 本模块用**平台在售价（platform_price）的观测下降幅度**"
                    + "作为可测口径：那是我们这条 listing 在平台上的在售价的下降，**不是竞品价**。"
                    + "需要同一站点上相邻两次观测才判得出来（只有一次 ⇒ 不判定）。"
                    + "竞品价模块落地后应改为经授权 API 供数。",
                List.of("thresholdRate（小数口径，0.1 = 10%）")),
            new PricingRuleCapabilityItem(
                "COST_INCREASE",
                "成本上涨",
                "单位成本合计相对上一版的涨幅 ≥ 阈值 ⇒ 命中。成本版本历史来自 5.2.1.1"
                    + "（sku_cost_revision）；两版快照必须都是**完整快照**，"
                    + "否则不判定（部分成本算出来的涨幅是假的）。",
                List.of("thresholdRate（小数口径，0.1 = 10%）")),
            new PricingRuleCapabilityItem(
                "MARGIN_BELOW_FLOOR",
                "毛利低于下限",
                "当前价下的毛利率低于下限 ⇒ 命中。毛利率来自 5.3.1.1 的真实测算（本模块不重写公式）；"
                    + "成本不完整时 5.3.1.1 不给毛利率 ⇒ 本模块**不判定**"
                    + "（不是判定成「没低于下限」）。",
                List.of("marginFloorRate（小数口径，0.2 = 20%）")),
            new PricingRuleCapabilityItem(
                "AGING_OVER_THRESHOLD",
                "库龄超期",
                "该 SKU **最老的一批**的库龄 > 阈值 ⇒ 命中。库龄来自 7.6.1.1 的呆滞清单"
                    + "（warehouse，跨服务只读）；本库里没有库龄副本。",
                List.of("agingDaysThreshold（天）")));
    var actions =
        List.of(
            new PricingRuleCapabilityItem(
                "LOWER_PRICE",
                "降价",
                "建议价 = 当前价 × (1 − 幅度)。**建议价**，不是生效价 —— 改价是 5.4.1.1 的写入所有权。",
                List.of("actionRate（小数口径，0.05 = 5%）")),
            new PricingRuleCapabilityItem(
                "RAISE_PRICE",
                "涨价",
                "建议价 = 当前价 × (1 + 幅度)。同样是**建议价**。",
                List.of("actionRate（小数口径，0.05 = 5%）")),
            new PricingRuleCapabilityItem(
                "PAUSE_PROMOTION",
                "暂停促销",
                "非价格动作：只产出一条建议，不产生建议价。🔴 非价格动作**一律要人工确认** —— "
                    + "规则写错一次就是一批商品被误伤。",
                List.of()),
            new PricingRuleCapabilityItem(
                "DELIST",
                "下架",
                "非价格动作：只产出一条建议，不产生建议价。同样**一律要人工确认**。",
                List.of()));
    var scopes =
        List.of(
            new PricingRuleCapabilityItem(
                "CATEGORY", "分类", "内部分类码（product_category.code）。", List.of("ref")),
            new PricingRuleCapabilityItem(
                "BRAND", "品牌", "品牌码（SPU 的品牌字段存的就是品牌编码）。", List.of("ref")),
            new PricingRuleCapabilityItem(
                "SHOP", "店铺", "站点码（2.1.1.1 平台目录里的 marketplace code）。",
                List.of("ref", "platformCode（可空）")),
            new PricingRuleCapabilityItem(
                "SKU", "SKU", "单个 SKU（sku.code，租户内唯一）。", List.of("ref")));
    var statuses =
        List.of(
            new PricingRuleCapabilityItem(
                "PENDING_CONFIRMATION",
                "待人工确认",
                "高风险建议：降价幅度超阈值 / 成本不完整 / 非价格动作。它<b>不会</b>自动流下去。",
                List.of()),
            new PricingRuleCapabilityItem(
                "READY", "已放行", "低风险建议：可直接被 5.4.1.1 消费。", List.of()),
            new PricingRuleCapabilityItem(
                "CONFIRMED", "已确认", "人工确认放行。", List.of()),
            new PricingRuleCapabilityItem(
                "REJECTED", "已驳回", "人工驳回。", List.of()));
    return new PricingRuleCapabilities(
        triggers,
        actions,
        scopes,
        statuses,
        "上游 = 7 库存（warehouse 授权端点：可用量 / 呆滞清单）、5.3.1.1 利润测算器"
            + "（同服务）、5.2.1.1 成本版本历史、5.5.2.1 价格观测台账。"
            + "5.3.2.1 定价模板与本模块共用 L3 锚点 5.3.2，但**不共用端点**："
            + "模板是「怎么算」，本模块是「什么时候改、改成多少」。",
        "下游 = 5.4.1.1 调价单：本模块产出的是**建议调价**，不是生效价。"
            + "5.4.1.1 要先看 requiresConfirmation —— true 时必须有人确认过才动价。",
        "🔴 **自动调价必须先「建议」再「执行」**（蓝图加粗句）。"
            + "本模块的「执行」= 把建议放行给下游（READY / CONFIRMED）；"
            + "本模块**不写任何价格** —— 价目表是 5.1.1.2 的写入所有权，改价是 5.4.1.1 的写入所有权。",
        "🔴 **「竞品降价」的可测口径**：本仓**没有**竞品价采集模块（2.x 卷里没有采集竞品价的模块），"
            + "所以本模块用**平台在售价观测的下降幅度**作为可测口径 —— 它不是竞品价，"
            + "页面上标成「市场价观测」。真竞品价落地后应改为经授权 API 供数，"
            + "ownerModule = 2.x 竞品价采集（未交付）。",
        "🔴 **优先级数字小者优先**（与本仓既有约定一致）。同一 SKU 上**先命中者优先**："
            + "只有优先级最高的那条命中规则产出建议调价，其余命中记录标注"
            + "「已被更高优先级的规则覆盖」。优先级是「同一 SKU 上到底听谁的」的唯一答案。",
        "评估请求里的 platformFeeRate / platformFeeFixed / logisticsCostPerUnit / fxRate 是"
            + "**5.3.1.1 的手工假设口径**（5.2.3.1 / 5.2.3.2 / 5.2.2.x 未并入测算路径），"
            + "它们不是「留空就按 0 处理」：缺了它们，5.3.1.1 判不出毛利，"
            + "于是「毛利低于下限」判不了、建议价的毛利也复核不了 —— "
            + "后者会让高风险建议**无法自动放行**（这是故意的）。",
        "当前价的唯一来源是 5.5.2.1 的**价格观测台账**（price_anomaly_observation，只读）："
            + "本仓的价目表（5.1）**未交付**，没有 price_list 表。"
            + "取最新一条观测（优先我方价 internal_price，没有就退到平台在售价 platform_price），"
            + "取不到 ⇒ 不产出建议价（不是用 0 顶）。",
        "🔴 **只追加**：规则可改，命中记录与建议调价只追加（建议的状态只能从"
            + "「待人工确认」推进到「已确认 / 已驳回」，不能回退 —— "
            + "改判历史等于把留痕洗掉）。");
  }

  // ================================================================ 目录

  /** 作用范围目录：内部分类 + 品牌（店铺在 2.1.1.1 平台目录，跨服务只读，不在本目录里）。 */
  public PricingRuleDirectory directory() {
    var categories = new ArrayList<PricingRuleCategoryOption>();
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
          new PricingRuleCategoryOption(
              row.get("code", String.class),
              row.get("name", String.class),
              row.get("parent_code", String.class)));
    }
    var brands = new ArrayList<PricingRuleBrandOption>();
    for (var row :
        sql.fetch(
            "select code, name, authorization_status from brand where tenant_id="
                + TENANT
                + " order by code")) {
      brands.add(
          new PricingRuleBrandOption(
              row.get("code", String.class),
              row.get("name", String.class),
              row.get("authorization_status", String.class)));
    }
    return new PricingRuleDirectory(
        categories,
        brands,
        "分类与品牌来自 catalog 本地事实（3.3.1.1 分类树 / 3.1.4.1 品牌）；"
            + "店铺（目标站点）来自 2.1.1.1 平台目录（channel 服务，跨服务只读），"
            + "本目录不代它列。");
  }

  // ================================================================ 规则 CRUD

  /** 规则台账（真分页 + 关键词 / 触发条件 / 动作 / 作用范围筛选）。 */
  public PricingRulePage list(RuleQuery query) {
    int limited = Math.min(Math.max(query.size() <= 0 ? 20 : query.size(), 1), MAX_PAGE_SIZE);
    int offset = Math.max(query.page(), 0) * limited;
    var where = new StringBuilder(" where r.tenant_id=" + TENANT);
    var params = new ArrayList<Object>();
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.append(" and (r.code ilike ? or r.name ilike ?)");
      var like = "%" + query.keyword().trim() + "%";
      params.add(like);
      params.add(like);
    }
    if (query.triggerType() != null) {
      where.append(" and r.trigger_type=?");
      params.add(query.triggerType().name());
    }
    if (query.actionType() != null) {
      where.append(" and r.action_type=?");
      params.add(query.actionType().name());
    }
    if (query.enabled() != null) {
      where.append(" and r.enabled=?");
      params.add(query.enabled());
    }
    if (query.scopeType() != null && query.scopeRef() != null && !query.scopeRef().isBlank()) {
      where.append(
          " and exists (select 1 from pricing_rule_scope s where s.tenant_id=r.tenant_id"
              + " and s.rule_id=r.id and s.scope_type=? and s.scope_ref=?)");
      params.add(query.scopeType().name());
      params.add(query.scopeRef().trim());
    }
    var rows =
        sql.fetch(
            "select r.id, r.code, r.name, r.trigger_type, r.threshold_quantity, r.threshold_rate,"
                + " r.margin_floor_rate, r.aging_days_threshold, r.action_type, r.action_rate,"
                + " r.priority, r.currency, r.max_auto_price_drop_rate, r.enabled,"
                + " r.effective_from, r.effective_to, r.created_by, r.updated_at,"
                + " (select count(*) from pricing_rule_scope s"
                + "   where s.tenant_id=r.tenant_id and s.rule_id=r.id) as scope_count"
                + " from pricing_rule r"
                + where
                + " order by r.updated_at desc, r.id"
                + " limit ? offset ?",
            concat(params, limited, offset));
    long total =
        sql.fetchOne("select count(*) from pricing_rule r" + where, params.toArray()).get(0, Long.class);
    var items = new ArrayList<PricingRuleSummary>();
    for (var row : rows) {
      items.add(summaryOf(row));
    }
    return new PricingRulePage(items, Math.max(query.page(), 0), limited, total);
  }

  /** 规则详情 = 规则本身 + 作用范围。 */
  public PricingRuleDetail detail(UUID id) {
    var row = ruleRow(id);
    if (row == null) throw notFound("价格规则不存在");
    return detailOf(row);
  }

  /** 新建 / 修改规则（修改走乐观锁）。作用范围整体替换。 */
  public PricingRuleDetail save(AccessService.Access actor, UUID id, PricingRuleInput input) {
    var normalized = normalize(input);
    if (id == null) {
      var duplicate =
          sql.fetchOne(
              "select id from pricing_rule where tenant_id=" + TENANT + " and code=?",
              normalized.code());
      if (duplicate != null) throw conflict("规则编码 " + normalized.code() + " 已存在");
      UUID newId = UUID.randomUUID();
      sql.execute(
          "insert into pricing_rule(id,tenant_id,code,name,description,trigger_type,threshold_quantity,"
              + "threshold_rate,margin_floor_rate,aging_days_threshold,action_type,action_rate,priority,"
              + "currency,max_auto_price_drop_rate,enabled,effective_from,effective_to,note,created_by,"
              + "created_at,updated_at)"
              + " values(?::uuid,?::uuid,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?::uuid,now(),now())",
          newId,
          actor.tenantId(),
          normalized.code(),
          normalized.name(),
          normalized.description(),
          normalized.triggerType().name(),
          normalized.thresholdQuantity(),
          normalized.thresholdRate(),
          normalized.marginFloorRate(),
          normalized.agingDaysThreshold(),
          normalized.actionType().name(),
          normalized.actionRate(),
          normalized.priority(),
          normalized.currency(),
          normalized.maxAutoPriceDropRate(),
          normalized.enabled(),
          normalized.effectiveFrom(),
          normalized.effectiveTo(),
          normalized.note(),
          actor.userId());
      replaceScopes(newId, actor, normalized);
      return detail(newId);
    }
    var current =
        sql.fetchOne(
            "select version from pricing_rule where tenant_id=" + TENANT + " and id=? for update",
            id);
    if (current == null) throw notFound("价格规则不存在");
    long currentVersion = current.get(0, Long.class);
    if (input.version() == null || input.version() != currentVersion) {
      throw conflict(
          "这条规则已经被别人改过了（当前版本 "
              + currentVersion
              + "，你提交的是 "
              + input.version()
              + "）。请刷新后重新提交 —— 覆盖别人的改动会让规则与建议调价对不上账。");
    }
    var duplicate =
        sql.fetchOne(
            "select id from pricing_rule where tenant_id=" + TENANT + " and code=? and id<>?",
            normalized.code(),
            id);
    if (duplicate != null) throw conflict("规则编码 " + normalized.code() + " 已存在");
    sql.execute(
        "update pricing_rule set code=?, name=?, description=?, trigger_type=?, threshold_quantity=?,"
            + " threshold_rate=?, margin_floor_rate=?, aging_days_threshold=?, action_type=?,"
            + " action_rate=?, priority=?, currency=?, max_auto_price_drop_rate=?, enabled=?,"
            + " effective_from=?::timestamptz, effective_to=?::timestamptz, note=?,"
            + " version=version+1, updated_at=now()"
            + " where tenant_id="
            + TENANT
            + " and id=?",
        normalized.code(),
        normalized.name(),
        normalized.description(),
        normalized.triggerType().name(),
        normalized.thresholdQuantity(),
        normalized.thresholdRate(),
        normalized.marginFloorRate(),
        normalized.agingDaysThreshold(),
        normalized.actionType().name(),
        normalized.actionRate(),
        normalized.priority(),
        normalized.currency(),
        normalized.maxAutoPriceDropRate(),
        normalized.enabled(),
        normalized.effectiveFrom(),
        normalized.effectiveTo(),
        normalized.note(),
        id);
    replaceScopes(id, actor, normalized);
    return detail(id);
  }

  /** 删除规则（连同它的作用范围）。命中记录与建议调价是只追加的事实，不跟着删。 */
  public void delete(UUID id) {
    int removed =
        sql.execute("delete from pricing_rule where tenant_id=" + TENANT + " and id=?", id);
    if (removed == 0) throw notFound("价格规则不存在");
    sql.execute("delete from pricing_rule_scope where tenant_id=" + TENANT + " and rule_id=?", id);
  }

  // ================================================================ 评估

  /**
   * 跑一次规则评估：产出**命中记录**与**建议调价列表**（蓝图输出句）。
   *
   * <p>🔴 本方法<b>不改价</b>：产出的是建议调价。「执行」是 5.4.1.1 调价单的事。
   */
  public EvaluationResult evaluate(
      AccessService.Access actor, EvaluateRequest request, String bearer) {
    var skuIds = request.skuIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
    if (skuIds.isEmpty()) throw badRequest("至少给一个 SKU");
    if (skuIds.size() > MAX_EVALUATE_SKUS) {
      throw badRequest(
          "一次最多评估 "
              + MAX_EVALUATE_SKUS
              + " 个 SKU（这次给了 "
              + skuIds.size()
              + " 个）：评估要跨服务取数（库存 / 库龄），规模不封顶会把上游打挂。"
              + "请分批评估。");
    }
    var currency = upper(request.currency());
    var costCurrency = upper(request.costCurrency());
    if (costCurrency == null) costCurrency = currency;
    var quantity = request.quantity() == null ? BigDecimal.ONE : request.quantity();
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
              + "。汇率规则（5.2.2.1 / 5.2.2.2）未交付 ⇒ 这里只能由人作为手工假设填入，"
              + "绝不按 1:1 折算。");
    }
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var rules = activeRules(now, request.ruleId(), currency);
    if (rules.isEmpty()) {
      return new EvaluationResult(
          0,
          0,
          0,
          0,
          0,
          List.of(),
          List.of(),
          "当前没有生效的规则（enabled 且落在生效时段内、币种与本次评估一致）—— "
              + "没有规则就没有建议，本次评估没有改任何价。");
    }
    if ((long) rules.size() * skuIds.size() > MAX_EVALUATE_PAIRS) {
      throw badRequest(
          "评估规模过大（"
              + rules.size()
              + " 条规则 × "
              + skuIds.size()
              + " 个 SKU）：每一对判定至少留一条留痕，请缩小 SKU 集合或先停用部分规则。");
    }
    var facts = productFacts(skuIds);
    var context = collectContext(rules, skuIds, request, costCurrency, bearer);

    var hits = new ArrayList<HitView>();
    var suggestions = new ArrayList<SuggestionView>();
    int hitCount = 0;
    for (var skuId : skuIds) {
      var product = facts.get(skuId);
      RuleView winner = null;
      for (var rule : rules) {
        if (!scopeMatches(rule, product, request)) continue;
        var outcome = evaluateTrigger(rule, product, request, context, bearer);
        if (outcome.matched() && winner == null) {
          winner = rule;
          var plan = planSuggestion(rule, product, request, context, bearer);
          var hitId =
              insertHit(
                  actor,
                  rule,
                  product,
                  true,
                  false,
                  null,
                  outcome.reason() + (plan.produced() ? "" : "；" + plan.note()),
                  outcome.evidence());
          hits.add(hitView(hitId));
          hitCount++;
          if (plan.produced()) {
            var suggestion = insertSuggestion(actor, rule, product, request, hitId, plan);
            suggestions.add(suggestion);
          }
        } else if (outcome.matched()) {
          var hitId =
              insertHit(
                  actor,
                  rule,
                  product,
                  true,
                  true,
                  winner.id(),
                  outcome.reason()
                      + "；已被优先级更高的规则 "
                      + winner.code()
                      + " 覆盖（本条不产出建议调价）",
                  outcome.evidence());
          hits.add(hitView(hitId));
          hitCount++;
        } else {
          var hitId =
              insertHit(actor, rule, product, false, false, null, outcome.reason(), outcome.evidence());
          hits.add(hitView(hitId));
          hitCount++;
        }
      }
    }
    int pending = 0;
    for (var suggestion : suggestions) {
      if (suggestion.requiresConfirmation()) pending++;
    }
    return new EvaluationResult(
        skuIds.size(),
        rules.size(),
        hitCount,
        suggestions.size(),
        pending,
        hits,
        suggestions,
        request.note() == null || request.note().isBlank() ? "规则评估" : request.note().trim());
  }

  // ================================================================ 命中记录与建议调价

  /** 命中记录（真分页 + 规则 / SKU / 是否命中筛选）。 */
  public HitPage listHits(HitQuery query) {
    int limited = Math.min(Math.max(query.size() <= 0 ? 20 : query.size(), 1), MAX_PAGE_SIZE);
    int offset = Math.max(query.page(), 0) * limited;
    var where = new StringBuilder(" where h.tenant_id=" + TENANT);
    var params = new ArrayList<Object>();
    if (query.ruleId() != null) {
      where.append(" and h.rule_id=?::uuid");
      params.add(query.ruleId());
    }
    if (query.skuId() != null) {
      where.append(" and h.sku_id=?::uuid");
      params.add(query.skuId());
    }
    if (query.matched() != null) {
      where.append(" and h.matched=?");
      params.add(query.matched());
    }
    var from = " from pricing_rule_hit h"
        + " join sku s on s.tenant_id=h.tenant_id and s.id=h.sku_id"
        + " join pricing_rule r on r.tenant_id=h.tenant_id and r.id=h.rule_id";
    long total = sql.fetchOne("select count(*)" + from + where, params.toArray()).get(0, Long.class);
    var rows =
        sql.fetch(
            "select h.id, h.rule_id, r.code as rule_code, r.name as rule_name, h.sku_id,"
                + " s.code as sku_code, s.name as sku_name, h.spu_id, h.trigger_type, h.matched,"
                + " h.superseded, h.superseded_by_rule_id, h.reason, h.evidence, h.evaluated_at"
                + from
                + where
                + " order by h.evaluated_at desc, h.id"
                + " limit ? offset ?",
            concat(params, limited, offset));
    var items = new ArrayList<HitView>();
    for (var row : rows) {
      items.add(hitViewOf(row));
    }
    return new HitPage(items, Math.max(query.page(), 0), limited, total);
  }

  /** 建议调价列表（真分页 + 规则 / SKU / 状态 / 是否需人工确认筛选）。 */
  public SuggestionPage listSuggestions(SuggestionQuery query) {
    int limited = Math.min(Math.max(query.size() <= 0 ? 20 : query.size(), 1), MAX_PAGE_SIZE);
    int offset = Math.max(query.page(), 0) * limited;
    var where = new StringBuilder(" where g.tenant_id=" + TENANT);
    var params = new ArrayList<Object>();
    if (query.ruleId() != null) {
      where.append(" and g.rule_id=?::uuid");
      params.add(query.ruleId());
    }
    if (query.skuId() != null) {
      where.append(" and g.sku_id=?::uuid");
      params.add(query.skuId());
    }
    if (query.status() != null) {
      where.append(" and g.status=?");
      params.add(query.status().name());
    }
    if (query.requiresConfirmation() != null) {
      where.append(" and g.requires_confirmation=?");
      params.add(query.requiresConfirmation());
    }
    var from = " from pricing_rule_suggestion g"
        + " join sku s on s.tenant_id=g.tenant_id and s.id=g.sku_id"
        + " join pricing_rule r on r.tenant_id=g.tenant_id and r.id=g.rule_id";
    long total = sql.fetchOne("select count(*)" + from + where, params.toArray()).get(0, Long.class);
    var rows =
        sql.fetch(
            "select g.id, g.rule_id, r.code as rule_code, r.name as rule_name, g.hit_id, g.sku_id,"
                + " s.code as sku_code, s.name as sku_name, g.spu_id, g.site_code, g.platform_code,"
                + " g.currency, g.action_type, g.current_price, g.suggested_price, g.price_change_rate,"
                + " g.requires_confirmation, g.status, g.decision_ready, g.gross_margin_rate,"
                + " g.gross_profit, g.reason, g.decided_by, g.decided_at, g.decision_note, g.created_at"
                + from
                + where
                + " order by g.created_at desc, g.id"
                + " limit ? offset ?",
            concat(params, limited, offset));
    var items = new ArrayList<SuggestionView>();
    for (var row : rows) {
      items.add(suggestionViewOf(row));
    }
    return new SuggestionPage(items, Math.max(query.page(), 0), limited, total);
  }

  /**
   * 人工确认 / 驳回一条建议调价（「执行」这一步的人工闸）。
   *
   * <p>🔴 只有「待人工确认」的建议可以改判；已经放行 / 确认 / 驳回的不能再改 ——
   * 建议列表是只追加的事实，改判历史等于把留痕洗掉。
   */
  public SuggestionView decide(AccessService.Access actor, UUID suggestionId, DecideRequest request) {
    var row =
        sql.fetchOne(
            "select status from pricing_rule_suggestion where tenant_id=" + TENANT + " and id=?",
            suggestionId);
    if (row == null) throw notFound("建议调价不存在");
    var status = SuggestionStatus.parse(row.get("status", String.class));
    if (status != SuggestionStatus.PENDING_CONFIRMATION) {
      throw conflict(
          "这条建议当前是「"
              + (status == null ? "未知" : status.label())
              + "」，不能改判 —— 只有「待人工确认」的建议可以确认或驳回。");
    }
    var decision = request.decision();
    var next = decision == Decision.CONFIRM ? SuggestionStatus.CONFIRMED : SuggestionStatus.REJECTED;
    var note = request.note() == null ? "" : request.note().trim();
    if (decision == Decision.REJECT && note.isEmpty()) {
      throw badRequest("驳回建议必须写明原因：规则批量误伤时，「为什么这条被驳回」是唯一的复盘线索。");
    }
    sql.execute(
        "update pricing_rule_suggestion set status=?, decided_by=?::uuid, decided_at=?::timestamptz,"
            + " decision_note=? where tenant_id="
            + TENANT
            + " and id=?",
        next.name(),
        actor.userId(),
        OffsetDateTime.now(ZoneOffset.UTC),
        note,
        suggestionId);
    return suggestion(suggestionId);
  }

  /** 单条建议调价。 */
  public SuggestionView suggestion(UUID id) {
    var row =
        sql.fetchOne(
            "select g.id, g.rule_id, r.code as rule_code, r.name as rule_name, g.hit_id, g.sku_id,"
                + " s.code as sku_code, s.name as sku_name, g.spu_id, g.site_code, g.platform_code,"
                + " g.currency, g.action_type, g.current_price, g.suggested_price, g.price_change_rate,"
                + " g.requires_confirmation, g.status, g.decision_ready, g.gross_margin_rate,"
                + " g.gross_profit, g.reason, g.decided_by, g.decided_at, g.decision_note, g.created_at"
                + " from pricing_rule_suggestion g"
                + " join sku s on s.tenant_id=g.tenant_id and s.id=g.sku_id"
                + " join pricing_rule r on r.tenant_id=g.tenant_id and r.id=g.rule_id"
                + " where g.tenant_id="
                + TENANT
                + " and g.id=?",
            id);
    if (row == null) throw notFound("建议调价不存在");
    return suggestionViewOf(row);
  }

  // ================================================================ 内部：规则查询

  /** 当前生效的规则（enabled + 落在生效时段内 + 币种一致），按优先级升序。 */
  private List<RuleView> activeRules(OffsetDateTime now, UUID ruleId, String currency) {
    var where = new StringBuilder(" where r.tenant_id=" + TENANT);
    var params = new ArrayList<Object>();
    where.append(" and r.enabled=true");
    where.append(" and r.effective_from<=?::timestamptz");
    params.add(now);
    where.append(" and (r.effective_to is null or r.effective_to>?::timestamptz)");
    params.add(now);
    where.append(" and r.currency=?");
    params.add(currency);
    if (ruleId != null) {
      where.append(" and r.id=?::uuid");
      params.add(ruleId);
    }
    var rows =
        sql.fetch(
            "select r.id, r.code, r.name, r.trigger_type, r.threshold_quantity, r.threshold_rate,"
                + " r.margin_floor_rate, r.aging_days_threshold, r.action_type, r.action_rate,"
                + " r.priority, r.currency, r.max_auto_price_drop_rate, r.enabled,"
                + " r.effective_from, r.effective_to"
                + " from pricing_rule r"
                + where
                + " order by r.priority asc, r.id",
            params.toArray());
    var rules = new ArrayList<RuleView>();
    for (var row : rows) {
      rules.add(ruleViewOf(row));
    }
    var scopes = scopesOf(rules.stream().map(RuleView::id).toList());
    var out = new ArrayList<RuleView>();
    for (var rule : rules) {
      out.add(new RuleView(
          rule.id(),
          rule.code(),
          rule.name(),
          rule.triggerType(),
          rule.actionType(),
          rule.thresholdQuantity(),
          rule.thresholdRate(),
          rule.marginFloorRate(),
          rule.agingDaysThreshold(),
          rule.actionRate(),
          rule.priority(),
          rule.currency(),
          rule.maxAutoPriceDropRate(),
          rule.enabled(),
          rule.effectiveFrom(),
          rule.effectiveTo(),
          scopes.getOrDefault(rule.id(), List.of())));
    }
    return out;
  }

  /** 规则的原始行（详情 / 修改用）。 */
  private org.jooq.Record ruleRow(UUID id) {
    return sql.fetchOne(
        "select r.id, r.code, r.name, r.trigger_type, r.threshold_quantity, r.threshold_rate,"
            + " r.margin_floor_rate, r.aging_days_threshold, r.action_type, r.action_rate,"
            + " r.priority, r.currency, r.max_auto_price_drop_rate, r.enabled,"
            + " r.effective_from, r.effective_to, r.created_by, r.updated_at"
            + " from pricing_rule r where r.tenant_id="
            + TENANT
            + " and r.id=?",
        id);
  }

  /** 一批规则的作用范围。 */
  private Map<UUID, List<PricingRuleScopeView>> scopesOf(List<UUID> ruleIds) {
    var out = new HashMap<UUID, List<PricingRuleScopeView>>();
    if (ruleIds.isEmpty()) return out;
    var placeholders = placeholders(ruleIds.size());
    var params = new ArrayList<Object>();
    for (var id : ruleIds) params.add(id);
    for (var row :
        sql.fetch(
            "select id, rule_id, scope_type, scope_ref, platform_code from pricing_rule_scope"
                + " where tenant_id="
                + TENANT
                + " and rule_id in ("
                + placeholders
                + ")"
                + " order by scope_type, scope_ref",
            params.toArray())) {
      var ruleId = row.get("rule_id", UUID.class);
      var list = out.computeIfAbsent(ruleId, k -> new ArrayList<>());
      var type = ScopeType.parse(row.get("scope_type", String.class));
      list.add(
          new PricingRuleScopeView(
              row.get("id", UUID.class),
              type,
              type == null ? row.get("scope_type", String.class) : type.label(),
              row.get("scope_ref", String.class),
              row.get("platform_code", String.class)));
    }
    return out;
  }

  /** 规则视图（内部）。 */
  private record RuleView(
      UUID id,
      String code,
      String name,
      TriggerType triggerType,
      ActionType actionType,
      BigDecimal thresholdQuantity,
      BigDecimal thresholdRate,
      BigDecimal marginFloorRate,
      Integer agingDaysThreshold,
      BigDecimal actionRate,
      int priority,
      String currency,
      BigDecimal maxAutoPriceDropRate,
      boolean enabled,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveTo,
      List<PricingRuleScopeView> scopes) {}

  // ================================================================ 内部：商品与数据

  /** 商品事实：SKU / SPU / 主分类码 / 品牌码。 */
  private record ProductFacts(
      UUID skuId, String code, String name, UUID spuId, String categoryCode, String brandCode) {}

  private Map<UUID, ProductFacts> productFacts(List<UUID> skuIds) {
    var placeholders = placeholders(skuIds.size());
    var params = new ArrayList<Object>();
    for (var id : skuIds) params.add(id);
    var out = new LinkedHashMap<UUID, ProductFacts>();
    for (var row :
        sql.fetch(
            "select s.id, s.code, s.name, s.spu_id, p.brand,"
                + " (select c.code from spu_category sc"
                + "    join product_category c on c.tenant_id=sc.tenant_id and c.id=sc.category_id"
                + "   where sc.tenant_id=s.tenant_id and sc.spu_id=p.id and sc.role='PRIMARY'"
                + "   limit 1) as category_code"
                + " from sku s join spu p on p.tenant_id=s.tenant_id and p.id=s.spu_id"
                + " where s.tenant_id="
                + TENANT
                + " and s.id in ("
                + placeholders
                + ")",
            params.toArray())) {
      out.put(
          row.get("id", UUID.class),
          new ProductFacts(
              row.get("id", UUID.class),
              row.get("code", String.class),
              row.get("name", String.class),
              row.get("spu_id", UUID.class),
              row.get("category_code", String.class),
              row.get("brand", String.class)));
    }
    if (out.size() != skuIds.size()) {
      var missing = skuIds.stream().filter(id -> !out.containsKey(id)).findFirst().orElse(null);
      throw notFound("SKU 不存在（或不属于当前租户）：" + missing);
    }
    return out;
  }

  /** 价格观测（最新两条）。 */
  private record PriceObservation(
      BigDecimal internalPrice,
      BigDecimal platformPrice,
      String currency,
      OffsetDateTime observedAt) {}

  /** 成本版本（最新两条）。 */
  private record CostRevision(
      int revisionNo, BigDecimal totalUnitCost, boolean snapshotComplete, OffsetDateTime effectiveFrom) {}

  /** 一次评估取到的全部外部事实。 */
  private static final class EvalContext {
    Map<UUID, BigDecimal> available = new HashMap<>();
    Map<UUID, Integer> agingDays = new HashMap<>();
    Map<UUID, List<CostRevision>> costs = new HashMap<>();
    Map<UUID, List<PriceObservation>> prices = new HashMap<>();
  }

  /**
   * 按规则需要取数。
   *
   * <p>🔴 只取用得上的：没有库存类规则就不打库存服务，没有库龄类规则就不打呆滞清单 ——
   * 每次评估都把所有上游叫一遍既慢又会打挂上游。
   */
  private EvalContext collectContext(
      List<RuleView> rules,
      List<UUID> skuIds,
      EvaluateRequest request,
      String costCurrency,
      String bearer) {
    var context = new EvalContext();
    var needInventory = false;
    var needAging = false;
    var needCost = false;
    var needPrice = false;
    for (var rule : rules) {
      switch (rule.triggerType()) {
        case INVENTORY_BELOW_THRESHOLD -> needInventory = true;
        case AGING_OVER_THRESHOLD -> needAging = true;
        case COST_INCREASE -> needCost = true;
        case COMPETITOR_PRICE_DROP, MARGIN_BELOW_FLOOR -> needPrice = true;
        default -> {
          // 其它触发条件不额外取数。
        }
      }
      if (rule.actionType().priceAction()) needPrice = true;
    }
    if (needInventory) {
      try {
        var result = availability.availability(bearer, skuIds, null);
        for (var row : result.rows()) {
          context.available.put(row.skuId(), row.available());
        }
      } catch (InventoryAvailabilityPort.AvailabilityUnavailableException e) {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
      }
    }
    if (needAging) {
      try {
        for (var row : aging.aging(bearer, skuIds)) {
          if (row.agingDays() == null) continue;
          context.agingDays.merge(row.skuId(), row.agingDays(), Math::max);
        }
      } catch (InventoryAgingPort.AgingUnavailableException e) {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
      }
    }
    if (needCost) {
      var placeholders = placeholders(skuIds.size());
      var params = new ArrayList<Object>();
      params.add(costCurrency);
      for (var id : skuIds) params.add(id);
      for (var row :
          sql.fetch(
              "select r.sku_id, r.revision_no, r.total_unit_cost, r.snapshot_complete, r.effective_from"
                  + " from sku_cost_revision r"
                  + " where r.tenant_id="
                  + TENANT
                  + " and r.currency=? and r.sku_id in ("
                  + placeholders
                  + ")"
                  + " order by r.sku_id, r.revision_no desc",
              params.toArray())) {
        var list =
            context.costs.computeIfAbsent(row.get("sku_id", UUID.class), k -> new ArrayList<>());
        if (list.size() < 2) {
          list.add(
              new CostRevision(
                  row.get("revision_no", Integer.class),
                  row.get("total_unit_cost", BigDecimal.class),
                  Boolean.TRUE.equals(row.get("snapshot_complete", Boolean.class)),
                  row.get("effective_from", OffsetDateTime.class)));
        }
      }
    }
    if (needPrice) {
      if (request.siteCode() == null
          || request.siteCode().isBlank()
          || request.platformCode() == null
          || request.platformCode().isBlank()) {
        throw badRequest(
            "本次评估必须给目标店铺（siteCode）与目标平台（platformCode）："
                + "当前价来自 5.5.2.1 的价格观测台账，它是按「平台 + 站点 + SKU」登记的。"
                + "没有这两个值就取不到价，也就给不出建议价。");
      }
      var placeholders = placeholders(skuIds.size());
      var params = new ArrayList<Object>();
      params.add(request.platformCode());
      params.add(request.siteCode());
      for (var id : skuIds) params.add(id);
      for (var row :
          sql.fetch(
              "select o.sku_id, o.internal_price, o.platform_price, o.currency, o.observed_at"
                  + " from price_anomaly_observation o"
                  + " where o.tenant_id="
                  + TENANT
                  + " and o.platform_code=? and o.site_code=? and o.sku_id in ("
                  + placeholders
                  + ")"
                  + " order by o.sku_id, o.observed_at desc",
              params.toArray())) {
        var list =
            context.prices.computeIfAbsent(row.get("sku_id", UUID.class), k -> new ArrayList<>());
        if (list.size() < 2) {
          list.add(
              new PriceObservation(
                  row.get("internal_price", BigDecimal.class),
                  row.get("platform_price", BigDecimal.class),
                  row.get("currency", String.class),
                  row.get("observed_at", OffsetDateTime.class)));
        }
      }
    }
    return context;
  }

  /** 当前价：最新一条观测，优先我方价（internal_price），没有就退到平台在售价（platform_price）。 */
  private BigDecimal currentPrice(UUID skuId, EvalContext context) {
    var list = context.prices.get(skuId);
    if (list == null || list.isEmpty()) return null;
    var latest = list.get(0);
    if (latest.internalPrice() != null && latest.internalPrice().signum() > 0) {
      return latest.internalPrice();
    }
    if (latest.platformPrice() != null && latest.platformPrice().signum() > 0) {
      return latest.platformPrice();
    }
    return null;
  }

  /** 判定结果。 */
  private record TriggerOutcome(boolean matched, String reason, Map<String, Object> evidence) {}

  /**
   * 判定一条规则在一个 SKU 上是否命中。
   *
   * <p>🔴 取不到数 ⇒ {@code matched=false} 且 reason 写明「取不到」——
   * 绝不把「读不到」当成「没超阈值」（那会让规则的沉默看起来像「判定过且没问题」）。
   */
  private TriggerOutcome evaluateTrigger(
      RuleView rule,
      ProductFacts product,
      EvaluateRequest request,
      EvalContext context,
      String bearer) {
    var evidence = new LinkedHashMap<String, Object>();
    evidence.put("triggerType", rule.triggerType().name());
    evidence.put("ruleId", rule.id().toString());
    evidence.put("ruleCode", rule.code());
    switch (rule.triggerType()) {
      case INVENTORY_BELOW_THRESHOLD -> {
        var available = context.available.get(product.skuId());
        evidence.put("available", available);
        evidence.put("thresholdQuantity", rule.thresholdQuantity());
        evidence.put("source", "7 库存（warehouse 授权端点）");
        var matched = PricingRuleRules.inventoryBelow(available, rule.thresholdQuantity());
        if (matched == null) {
          return new TriggerOutcome(
              false,
              "库存服务没有返回这个 SKU 的可用量 ⇒ **不判定**（取不到 ≠ 没超阈值）",
              evidence);
        }
        if (matched) {
          return new TriggerOutcome(
              true,
              "可售量 "
                  + available.stripTrailingZeros().toPlainString()
                  + " 低于阈值 "
                  + rule.thresholdQuantity().stripTrailingZeros().toPlainString()
                  + " ⇒ 命中",
              evidence);
        }
        return new TriggerOutcome(
            false,
            "可售量 "
                + available.stripTrailingZeros().toPlainString()
                + " 未低于阈值 "
                + rule.thresholdQuantity().stripTrailingZeros().toPlainString()
                + " ⇒ 未命中",
            evidence);
      }
      case COMPETITOR_PRICE_DROP -> {
        var list = context.prices.get(product.skuId());
        PriceObservation latest = null;
        PriceObservation previous = null;
        if (list != null && !list.isEmpty()) {
          latest = list.get(0);
          previous = list.size() > 1 ? list.get(1) : null;
        }
        var latestPlatform = latest == null ? null : latest.platformPrice();
        var previousPlatform = previous == null ? null : previous.platformPrice();
        evidence.put("latestPlatformPrice", latestPlatform);
        evidence.put("previousPlatformPrice", previousPlatform);
        evidence.put("thresholdRate", rule.thresholdRate());
        evidence.put(
            "source",
            "5.5.2.1 价格观测台账（platform_price = 平台在售价，**不是竞品价**）");
        var drop = PricingRuleRules.priceDropRate(previousPlatform, latestPlatform);
        if (drop == null) {
          return new TriggerOutcome(
              false,
              "价格观测台账里没有这个 SKU 在该站点的相邻两次观测 ⇒ **不判定**"
                  + "（一次观测说明不了「降价」）",
              evidence);
        }
        if (drop.compareTo(rule.thresholdRate()) >= 0) {
          return new TriggerOutcome(
              true,
              "平台在售价从 "
                  + previousPlatform.stripTrailingZeros().toPlainString()
                  + " 降到 "
                  + latestPlatform.stripTrailingZeros().toPlainString()
                  + "（降幅 "
                  + PricingRuleRules.percentText(drop)
                  + "，阈值 "
                  + PricingRuleRules.percentText(rule.thresholdRate())
                  + "）⇒ 命中",
              evidence);
        }
        return new TriggerOutcome(
            false,
            "平台在售价降幅 "
                + PricingRuleRules.percentText(drop)
                + " 未达到阈值 "
                + PricingRuleRules.percentText(rule.thresholdRate())
                + " ⇒ 未命中",
            evidence);
      }
      case COST_INCREASE -> {
        var list = context.costs.get(product.skuId());
        CostRevision latest = null;
        CostRevision previous = null;
        if (list != null && !list.isEmpty()) {
          latest = list.get(0);
          previous = list.size() > 1 ? list.get(1) : null;
        }
        evidence.put("latestTotalUnitCost", latest == null ? null : latest.totalUnitCost());
        evidence.put("previousTotalUnitCost", previous == null ? null : previous.totalUnitCost());
        evidence.put("thresholdRate", rule.thresholdRate());
        evidence.put("source", "5.2.1.1 成本版本历史（sku_cost_revision）");
        if (latest == null || previous == null) {
          return new TriggerOutcome(
              false,
              "这个 SKU 的成本版本历史不足两版 ⇒ **不判定**（说明不了「上涨」）",
              evidence);
        }
        if (!latest.snapshotComplete() || !previous.snapshotComplete()) {
          return new TriggerOutcome(
              false,
              "成本快照不完整（有一版缺分项）⇒ **不判定**（部分成本算出来的涨幅是假的）",
              evidence);
        }
        var increase = PricingRuleRules.costIncreaseRate(latest.totalUnitCost(), previous.totalUnitCost());
        if (increase == null) {
          return new TriggerOutcome(
              false, "上一版单位成本不可用 ⇒ **不判定**", evidence);
        }
        if (increase.compareTo(rule.thresholdRate()) >= 0) {
          return new TriggerOutcome(
              true,
              "单位成本从 "
                  + previous.totalUnitCost().stripTrailingZeros().toPlainString()
                  + " 涨到 "
                  + latest.totalUnitCost().stripTrailingZeros().toPlainString()
                  + "（涨幅 "
                  + PricingRuleRules.percentText(increase)
                  + "，阈值 "
                  + PricingRuleRules.percentText(rule.thresholdRate())
                  + "）⇒ 命中",
              evidence);
        }
        return new TriggerOutcome(
            false,
            "单位成本涨幅 "
                + PricingRuleRules.percentText(increase)
                + " 未达到阈值 "
                + PricingRuleRules.percentText(rule.thresholdRate())
                + " ⇒ 未命中",
            evidence);
      }
      case MARGIN_BELOW_FLOOR -> {
        var price = currentPrice(product.skuId(), context);
        evidence.put("currentPrice", price);
        evidence.put("marginFloorRate", rule.marginFloorRate());
        evidence.put("source", "5.3.1.1 利润测算器（同服务）");
        if (price == null) {
          return new TriggerOutcome(
              false,
              "取不到当前价（价格观测台账里没有这个 SKU 在该站点的观测）⇒ **不判定**",
              evidence);
        }
        var calculation = calculateAt(product.skuId(), price, request, bearer, "价格规则的毛利判定");
        if (calculation == null || !calculation.decisionReady()) {
          return new TriggerOutcome(
              false,
              "5.3.1.1 的测算不完整（"
                  + (calculation == null
                      ? "测算失败"
                      : gapSummary(calculation.blockingGaps()))
                  + "）⇒ **不判定**毛利（没有可信的毛利率就没有「低于下限」可言）",
              evidence);
        }
        var margin = calculation.grossMarginRate();
        evidence.put("grossMarginRate", margin);
        if (margin.compareTo(rule.marginFloorRate()) < 0) {
          return new TriggerOutcome(
              true,
              "当前价 "
                  + price.stripTrailingZeros().toPlainString()
                  + " 下的毛利率 "
                  + PricingRuleRules.percentText(margin)
                  + " 低于下限 "
                  + PricingRuleRules.percentText(rule.marginFloorRate())
                  + " ⇒ 命中",
              evidence);
        }
        return new TriggerOutcome(
            false,
            "当前价下的毛利率 "
                + PricingRuleRules.percentText(margin)
                + " 未低于下限 "
                + PricingRuleRules.percentText(rule.marginFloorRate())
                + " ⇒ 未命中",
            evidence);
      }
      case AGING_OVER_THRESHOLD -> {
        var days = context.agingDays.get(product.skuId());
        evidence.put("agingDays", days);
        evidence.put("agingDaysThreshold", rule.agingDaysThreshold());
        evidence.put("source", "7.6.1.1 库龄与呆滞（warehouse 授权端点）");
        var matched = PricingRuleRules.agingOver(days, rule.agingDaysThreshold());
        if (matched == null) {
          return new TriggerOutcome(
              false,
              "库存服务没有返回这个 SKU 的库龄 ⇒ **不判定**（取不到 ≠ 没超期）",
              evidence);
        }
        if (matched) {
          return new TriggerOutcome(
              true,
              "最老的一批库龄 "
                  + days
                  + " 天超过阈值 "
                  + rule.agingDaysThreshold()
                  + " 天 ⇒ 命中",
              evidence);
        }
        return new TriggerOutcome(
            false,
            "最老的一批库龄 " + days + " 天未超过阈值 " + rule.agingDaysThreshold() + " 天 ⇒ 未命中",
            evidence);
      }
      default -> {
        return new TriggerOutcome(false, "未知触发条件 ⇒ 不判定", evidence);
      }
    }
  }

  /** 建议方案（产出建议前的全部算术）。 */
  private record SuggestionPlan(
      boolean produced,
      BigDecimal currentPrice,
      BigDecimal suggestedPrice,
      BigDecimal priceChangeRate,
      boolean decisionReady,
      BigDecimal grossMarginRate,
      BigDecimal grossProfit,
      String note) {}

  /**
   * 算出一条建议（算不出来就不产出）。
   *
   * <p>🔴 毛利复核走 5.3.1.1：建议价必须用 5.3.1.1 再算一遍，才知道这个价还剩多少毛利。
   * 成本不完整 ⇒ {@code decisionReady=false} ⇒ 毛利为 null（不是 0），而且这条建议
   * **不会**自动放行（见纪律二）。
   */
  private SuggestionPlan planSuggestion(
      RuleView rule,
      ProductFacts product,
      EvaluateRequest request,
      EvalContext context,
      String bearer) {
    var price = currentPrice(product.skuId(), context);
    if (rule.actionType().priceAction()) {
      if (price == null) {
        return new SuggestionPlan(
            false,
            null,
            null,
            null,
            false,
            null,
            null,
            "取不到当前价（价格观测台账里没有这个 SKU 在该站点的观测）⇒ **不产出建议价**");
      }
      var suggested = PricingRuleRules.suggestedPrice(price, rule.actionType(), rule.actionRate());
      if (suggested == null) {
        return new SuggestionPlan(
            false,
            price,
            null,
            null,
            false,
            null,
            null,
            "按当前价与调价幅度算不出一个正的建议价 ⇒ **不产出建议价**");
      }
      var change = PricingRuleRules.priceChangeRate(price, suggested);
      var calculation = calculateAt(product.skuId(), suggested, request, bearer, "价格规则的建议价复核");
      var ready = calculation != null && calculation.decisionReady();
      return new SuggestionPlan(
          true,
          price,
          suggested,
          change,
          ready,
          ready ? calculation.grossMarginRate() : null,
          ready ? calculation.grossProfit() : null,
          "");
    }
    // 非价格动作：用当前价复核一次毛利（没有建议价，但建议行仍要带上这个价下的毛利）。
    var calculation =
        price == null
            ? null
            : calculateAt(product.skuId(), price, request, bearer, "价格规则的当前价复核");
    var ready = calculation != null && calculation.decisionReady();
    return new SuggestionPlan(
        true,
        price,
        null,
        null,
        ready,
        ready ? calculation.grossMarginRate() : null,
        ready ? calculation.grossProfit() : null,
        "");
  }

  /**
   * 调 5.3.1.1 算一次。
   *
   * @return null = 测算抛错（SKU 不存在 / 参数非法）⇒ 复核不了 ⇒ 不给毛利率
   */
  private ProfitCalculatorModels.ProfitCalculation calculateAt(
      UUID skuId,
      BigDecimal price,
      EvaluateRequest request,
      String bearer,
      String note) {
    var calcRequest =
        new ProfitCalculatorModels.CalculateRequest(
            skuId,
            request.siteCode(),
            request.platformCode(),
            upper(request.currency()),
            upper(request.costCurrency()) == null ? upper(request.currency()) : upper(request.costCurrency()),
            ProfitCalculatorModels.CalcMode.PRICE,
            price,
            null,
            request.quantity() == null ? BigDecimal.ONE : request.quantity(),
            request.fxRate(),
            request.platformFeeRate(),
            request.platformFeeFixed(),
            request.logisticsCostPerUnit(),
            null,
            List.of(),
            null,
            null,
            note);
    try {
      return calculator.calculate(calcRequest, bearer);
    } catch (RuntimeException e) {
      return null;
    }
  }

  // ================================================================ 内部：落库

  private UUID insertHit(
      AccessService.Access actor,
      RuleView rule,
      ProductFacts product,
      boolean matched,
      boolean superseded,
      UUID supersededByRuleId,
      String reason,
      Map<String, Object> evidence) {
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into pricing_rule_hit(id,tenant_id,rule_id,sku_id,spu_id,trigger_type,matched,"
            + "evidence,reason,superseded,superseded_by_rule_id,evaluated_at,created_by)"
            + " values(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?,?,?::jsonb,?,?,?,?::timestamptz,?::uuid)",
        id,
        actor.tenantId(),
        rule.id(),
        product.skuId(),
        product.spuId(),
        rule.triggerType().name(),
        matched,
        evidenceJson(evidence),
        reason == null ? "" : reason,
        superseded,
        supersededByRuleId,
        OffsetDateTime.now(ZoneOffset.UTC),
        actor.userId());
    return id;
  }

  private SuggestionView insertSuggestion(
      AccessService.Access actor,
      RuleView rule,
      ProductFacts product,
      EvaluateRequest request,
      UUID hitId,
      SuggestionPlan plan) {
    var requiresConfirmation =
        PricingRuleRules.requiresConfirmation(
            rule.actionType(), plan.priceChangeRate(), rule.maxAutoPriceDropRate(), plan.decisionReady());
    var status =
        requiresConfirmation ? SuggestionStatus.PENDING_CONFIRMATION : SuggestionStatus.READY;
    var reason =
        "建议"
            + rule.actionType().label()
            + (rule.actionType().priceAction()
                ? " "
                    + PricingRuleRules.percentText(rule.actionRate())
                    + "（"
                    + (plan.priceChangeRate() == null
                        ? "—"
                        : PricingRuleRules.percentText(plan.priceChangeRate().abs()))
                    + " 相对当前价）"
                : "")
            + "；"
            + (requiresConfirmation
                ? "🔴 高风险 ⇒ 需人工确认"
                : "低风险 ⇒ 已放行，可被 5.4.1.1 消费");
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into pricing_rule_suggestion(id,tenant_id,rule_id,hit_id,sku_id,spu_id,site_code,"
            + "platform_code,currency,action_type,current_price,suggested_price,price_change_rate,"
            + "requires_confirmation,status,decision_ready,gross_margin_rate,gross_profit,reason,"
            + "created_at)"
            + " values(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?,?,?,?,?,?,?,?,?,?,now())",
        id,
        actor.tenantId(),
        rule.id(),
        hitId,
        product.skuId(),
        product.spuId(),
        request.siteCode() == null ? "" : request.siteCode(),
        request.platformCode() == null ? "" : request.platformCode(),
        rule.currency(),
        rule.actionType().name(),
        plan.currentPrice(),
        plan.suggestedPrice(),
        plan.priceChangeRate(),
        requiresConfirmation,
        status.name(),
        plan.decisionReady(),
        plan.grossMarginRate(),
        plan.grossProfit(),
        reason);
    return suggestion(id);
  }

  /** 整体替换作用范围（同一事务里删 + 插：要么全换要么全不换）。 */
  private void replaceScopes(UUID ruleId, AccessService.Access actor, PricingRuleInput input) {
    sql.execute("delete from pricing_rule_scope where tenant_id=" + TENANT + " and rule_id=?", ruleId);
    for (var scope : input.scopes()) {
      sql.execute(
          "insert into pricing_rule_scope(id,tenant_id,rule_id,scope_type,scope_ref,platform_code,"
              + "created_at) values(?::uuid,?::uuid,?::uuid,?,?,?,now())",
          UUID.randomUUID(),
          actor.tenantId(),
          ruleId,
          scope.type().name(),
          scope.ref().trim(),
          scope.type() == ScopeType.SHOP && scope.platformCode() != null
              ? scope.platformCode().trim().toUpperCase(Locale.ROOT)
              : null);
    }
  }

  // ================================================================ 内部：归一化与视图

  /** 归一化 + 校验（越界一律 400，不写库）。 */
  private PricingRuleInput normalize(PricingRuleInput input) {
    var code = input.code() == null ? "" : input.code().trim();
    if (code.isEmpty()) throw badRequest("规则编码不能为空");
    var name = input.name() == null ? "" : input.name().trim();
    if (name.isEmpty()) throw badRequest("规则名称不能为空");
    var trigger = input.triggerType();
    var action = input.actionType();
    if (trigger == null) throw badRequest("必须选触发条件");
    if (action == null) throw badRequest("必须选动作");
    var currency = upper(input.currency());
    if (currency == null) throw badRequest("必须给币种（建议价的币种）");
    var thresholdQuantity = input.thresholdQuantity();
    var thresholdRate = input.thresholdRate();
    var marginFloor = input.marginFloorRate();
    var agingDays = input.agingDaysThreshold();
    switch (trigger) {
      case INVENTORY_BELOW_THRESHOLD -> {
        if (thresholdQuantity == null || thresholdQuantity.signum() < 0) {
          throw badRequest("库存低于阈值必须给库存阈值（≥ 0 的件数）");
        }
        thresholdRate = null;
        marginFloor = null;
        agingDays = null;
      }
      case COMPETITOR_PRICE_DROP, COST_INCREASE -> {
        if (thresholdRate == null || thresholdRate.signum() <= 0 || thresholdRate.compareTo(BigDecimal.ONE) >= 0) {
          throw badRequest(
              (trigger == TriggerType.COMPETITOR_PRICE_DROP ? "竞品降价" : "成本上涨")
                  + "必须给涨跌幅阈值（0 与 1 之间的小数口径：0.1 = 10%）");
        }
        thresholdQuantity = null;
        marginFloor = null;
        agingDays = null;
      }
      case MARGIN_BELOW_FLOOR -> {
        if (marginFloor == null
            || marginFloor.signum() < 0
            || marginFloor.compareTo(BigDecimal.ONE) >= 0) {
          throw badRequest("毛利低于下限必须给毛利下限（0 与 1 之间的小数口径：0.2 = 20%）");
        }
        thresholdQuantity = null;
        thresholdRate = null;
        agingDays = null;
      }
      case AGING_OVER_THRESHOLD -> {
        if (agingDays == null || agingDays < 0) {
          throw badRequest("库龄超期必须给库龄阈值（≥ 0 的天数）");
        }
        thresholdQuantity = null;
        thresholdRate = null;
        marginFloor = null;
      }
      default -> {
        // 词表封闭，走不到这里。
      }
    }
    var actionRate = input.actionRate();
    if (action.priceAction()) {
      if (actionRate == null || actionRate.signum() <= 0 || actionRate.compareTo(BigDecimal.ONE) >= 0) {
        throw badRequest(
            "「" + action.label() + "」必须给调价幅度（0 与 1 之间的小数口径：0.05 = 5%）");
      }
    } else {
      actionRate = null;
    }
    var maxAuto = input.maxAutoPriceDropRate();
    if (maxAuto == null) maxAuto = BigDecimal.ZERO;
    if (maxAuto.signum() < 0 || maxAuto.compareTo(BigDecimal.ONE) >= 0) {
      throw badRequest("自动放行的最大降价幅度必须落在 0 与 1 之间（小数口径：0.1 = 10%）");
    }
    if (input.effectiveTo() != null && !input.effectiveTo().isAfter(input.effectiveFrom())) {
      throw badRequest("生效终点必须晚于生效起点");
    }
    var scopes = input.scopes() == null ? List.<PricingRuleScopeInput>of() : input.scopes();
    var seen = new java.util.HashSet<String>();
    for (var scope : scopes) {
      if (scope == null || scope.type() == null) throw badRequest("作用范围缺类型");
      var ref = scope.ref() == null ? "" : scope.ref().trim();
      if (ref.isEmpty()) throw badRequest("作用范围必须给取值（分类码 / 品牌码 / 站点码 / SKU 编码）");
      var key = scope.type().name() + "|" + ref + "|" + (scope.platformCode() == null ? "" : scope.platformCode());
      if (!seen.add(key)) throw badRequest("作用范围重复：" + scope.type().label() + " " + ref);
    }
    return new PricingRuleInput(
        code,
        name,
        input.description() == null ? "" : input.description().trim(),
        trigger,
        thresholdQuantity,
        thresholdRate,
        marginFloor,
        agingDays,
        action,
        actionRate,
        input.priority() == null ? 100 : input.priority(),
        currency,
        maxAuto,
        input.enabled() == null ? Boolean.TRUE : input.enabled(),
        input.effectiveFrom(),
        input.effectiveTo(),
        input.note() == null ? "" : input.note().trim(),
        scopes,
        input.version());
  }

  private PricingRuleSummary summaryOf(org.jooq.Record row) {
    return summaryOf(row, null);
  }

  /**
   * 规则摘要。
   *
   * @param scopeCountOverride 作用范围条数；null = 从行里读（列表查询带这个子查询列）
   */
  private PricingRuleSummary summaryOf(org.jooq.Record row, Integer scopeCountOverride) {
    var trigger = TriggerType.parse(row.get("trigger_type", String.class));
    var action = ActionType.parse(row.get("action_type", String.class));
    var scopeCount =
        scopeCountOverride != null ? scopeCountOverride : number(row, "scope_count");
    return new PricingRuleSummary(
        row.get("id", UUID.class),
        row.get("code", String.class),
        row.get("name", String.class),
        trigger,
        trigger == null ? row.get("trigger_type", String.class) : trigger.label(),
        action,
        action == null ? row.get("action_type", String.class) : action.label(),
        row.get("threshold_quantity", BigDecimal.class),
        row.get("threshold_rate", BigDecimal.class),
        row.get("margin_floor_rate", BigDecimal.class),
        row.get("aging_days_threshold", Integer.class),
        row.get("action_rate", BigDecimal.class),
        row.get("priority", Integer.class) == null ? 0 : row.get("priority", Integer.class),
        row.get("currency", String.class),
        row.get("max_auto_price_drop_rate", BigDecimal.class),
        Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
        row.get("effective_from", OffsetDateTime.class),
        row.get("effective_to", OffsetDateTime.class),
        scopeCount == null ? 0 : scopeCount.intValue(),
        row.get("created_by", UUID.class) == null ? null : row.get("created_by", UUID.class).toString(),
        row.get("updated_at", OffsetDateTime.class));
  }

  private PricingRuleDetail detailOf(org.jooq.Record row) {
    var id = row.get("id", UUID.class);
    var scopes = scopesOf(List.of(id)).getOrDefault(id, List.of());
    var rule = ruleViewOf(row);
    var meta =
        sql.fetchOne(
            "select description, note, version, created_at from pricing_rule where tenant_id="
                + TENANT
                + " and id=?",
            id);
    return new PricingRuleDetail(
        summaryOf(row, scopes.size()),
        meta.get("description", String.class),
        rule.triggerType(),
        rule.thresholdQuantity(),
        rule.thresholdRate(),
        rule.marginFloorRate(),
        rule.agingDaysThreshold(),
        rule.actionType(),
        rule.actionRate(),
        rule.priority(),
        rule.currency(),
        rule.maxAutoPriceDropRate(),
        rule.enabled(),
        rule.effectiveFrom(),
        rule.effectiveTo(),
        meta.get("note", String.class),
        meta.get("version", Long.class),
        scopes,
        meta.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private RuleView ruleViewOf(org.jooq.Record row) {
    var trigger = TriggerType.parse(row.get("trigger_type", String.class));
    var action = ActionType.parse(row.get("action_type", String.class));
    return new RuleView(
        row.get("id", UUID.class),
        row.get("code", String.class),
        row.get("name", String.class),
        trigger,
        action,
        row.get("threshold_quantity", BigDecimal.class),
        row.get("threshold_rate", BigDecimal.class),
        row.get("margin_floor_rate", BigDecimal.class),
        row.get("aging_days_threshold", Integer.class),
        row.get("action_rate", BigDecimal.class),
        row.get("priority", Integer.class) == null ? 100 : row.get("priority", Integer.class),
        row.get("currency", String.class),
        row.get("max_auto_price_drop_rate", BigDecimal.class),
        Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
        row.get("effective_from", OffsetDateTime.class),
        row.get("effective_to", OffsetDateTime.class),
        List.of());
  }

  /** 作用范围是否命中这个商品。 */
  private boolean scopeMatches(RuleView rule, ProductFacts product, EvaluateRequest request) {
    if (rule.scopes().isEmpty()) return true; // 没配作用范围 = 适用于全租户（显式语义）
    for (var scope : rule.scopes()) {
      if (scope.type() == ScopeType.CATEGORY
          && scope.ref().equalsIgnoreCase(nullToEmpty(product.categoryCode()))) {
        return true;
      }
      if (scope.type() == ScopeType.BRAND
          && scope.ref().equalsIgnoreCase(nullToEmpty(product.brandCode()))) {
        return true;
      }
      if (scope.type() == ScopeType.SHOP
          && scope.ref().equalsIgnoreCase(nullToEmpty(request.siteCode()))) {
        if (scope.platformCode() == null
            || scope.platformCode().equalsIgnoreCase(nullToEmpty(request.platformCode()))) {
          return true;
        }
      }
      if (scope.type() == ScopeType.SKU && scope.ref().equalsIgnoreCase(product.code())) {
        return true;
      }
    }
    return false;
  }

  private HitView hitView(UUID id) {
    var row =
        sql.fetchOne(
            "select h.id, h.rule_id, r.code as rule_code, r.name as rule_name, h.sku_id,"
                + " s.code as sku_code, s.name as sku_name, h.spu_id, h.trigger_type, h.matched,"
                + " h.superseded, h.superseded_by_rule_id, h.reason, h.evidence, h.evaluated_at"
                + " from pricing_rule_hit h"
                + " join sku s on s.tenant_id=h.tenant_id and s.id=h.sku_id"
                + " join pricing_rule r on r.tenant_id=h.tenant_id and r.id=h.rule_id"
                + " where h.tenant_id="
                + TENANT
                + " and h.id=?",
            id);
    if (row == null) throw notFound("命中记录不存在");
    return hitViewOf(row);
  }

  private HitView hitViewOf(org.jooq.Record row) {
    var trigger = TriggerType.parse(row.get("trigger_type", String.class));
    var supersededBy = row.get("superseded_by_rule_id", UUID.class);
    String supersededByCode = null;
    if (supersededBy != null) {
      var code =
          sql.fetchOne(
              "select code from pricing_rule where tenant_id=" + TENANT + " and id=?", supersededBy);
      supersededByCode = code == null ? null : code.get(0, String.class);
    }
    return new HitView(
        row.get("id", UUID.class),
        row.get("rule_id", UUID.class),
        row.get("rule_code", String.class),
        row.get("rule_name", String.class),
        row.get("sku_id", UUID.class),
        row.get("sku_code", String.class),
        row.get("sku_name", String.class),
        row.get("spu_id", UUID.class),
        trigger,
        trigger == null ? row.get("trigger_type", String.class) : trigger.label(),
        Boolean.TRUE.equals(row.get("matched", Boolean.class)),
        Boolean.TRUE.equals(row.get("superseded", Boolean.class)),
        supersededBy,
        supersededByCode,
        row.get("reason", String.class),
        evidenceMap(row.get("evidence", String.class)),
        row.get("evaluated_at", OffsetDateTime.class));
  }

  private SuggestionView suggestionViewOf(org.jooq.Record row) {
    var action = ActionType.parse(row.get("action_type", String.class));
    var status = SuggestionStatus.parse(row.get("status", String.class));
    var decidedBy = row.get("decided_by", UUID.class);
    return new SuggestionView(
        row.get("id", UUID.class),
        row.get("rule_id", UUID.class),
        row.get("rule_code", String.class),
        row.get("rule_name", String.class),
        row.get("hit_id", UUID.class),
        row.get("sku_id", UUID.class),
        row.get("sku_code", String.class),
        row.get("sku_name", String.class),
        row.get("spu_id", UUID.class),
        row.get("site_code", String.class),
        row.get("platform_code", String.class),
        row.get("currency", String.class),
        action,
        action == null ? row.get("action_type", String.class) : action.label(),
        row.get("current_price", BigDecimal.class),
        row.get("suggested_price", BigDecimal.class),
        row.get("price_change_rate", BigDecimal.class),
        Boolean.TRUE.equals(row.get("requires_confirmation", Boolean.class)),
        status,
        status == null ? row.get("status", String.class) : status.label(),
        Boolean.TRUE.equals(row.get("decision_ready", Boolean.class)),
        row.get("gross_margin_rate", BigDecimal.class),
        row.get("gross_profit", BigDecimal.class),
        row.get("reason", String.class),
        decidedBy == null ? null : decidedBy.toString(),
        row.get("decided_at", OffsetDateTime.class),
        row.get("decision_note", String.class),
        row.get("created_at", OffsetDateTime.class));
  }

  // ================================================================ 查询参数

  /** 规则列表的查询参数。 */
  public record RuleQuery(
      String keyword,
      TriggerType triggerType,
      ActionType actionType,
      Boolean enabled,
      ScopeType scopeType,
      String scopeRef,
      int page,
      int size) {}

  /** 命中记录的查询参数。 */
  public record HitQuery(UUID ruleId, UUID skuId, Boolean matched, int page, int size) {}

  /** 建议调价的查询参数。 */
  public record SuggestionQuery(
      UUID ruleId, UUID skuId, SuggestionStatus status, Boolean requiresConfirmation, int page, int size) {}

  // ================================================================ 小工具

  private static String placeholders(int count) {
    return String.join(",", java.util.Collections.nCopies(count, "?"));
  }

  /**
   * 取一个数值列（列可能不存在 —— 例如详情行没有 scope_count）。
   *
   * @return null = 列不存在或值为空
   */
  private static Number number(org.jooq.Record row, String name) {
    for (var field : row.fields()) {
      if (field.getName().equals(name)) {
        var value = row.get(name);
        return value instanceof Number n ? n : null;
      }
    }
    return null;
  }

  private static Object[] concat(List<Object> params, Object... extra) {
    var all = new ArrayList<Object>(params);
    for (var value : extra) {
      all.add(value);
    }
    return all.toArray();
  }

  private static String upper(String value) {
    if (value == null || value.isBlank()) return null;
    return value.trim().toUpperCase(Locale.ROOT);
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String evidenceJson(Map<String, Object> values) {
    try {
      return JSON.writeValueAsString(values);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("判定依据序列化失败", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> evidenceMap(String json) {
    if (json == null || json.isBlank()) return Map.of();
    try {
      return JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (JsonProcessingException e) {
      return Map.of("raw", json);
    }
  }

  /** 缺口清单摘要（5.3.1.1 不给毛利率时告诉页面缺哪几项、归谁管）。 */
  private String gapSummary(List<ProfitCalculatorModels.BlockingGap> gaps) {
    if (gaps == null || gaps.isEmpty()) return "（5.3.1.1 没有给出缺口清单）";
    var parts = new ArrayList<String>();
    for (var gap : gaps) {
      parts.add(gap.label() + "（归 " + gap.ownerModule() + " 管：" + gap.impact() + "）");
    }
    return String.join("；", parts);
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
