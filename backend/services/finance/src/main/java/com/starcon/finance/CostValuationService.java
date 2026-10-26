package com.starcon.finance;

import static com.starcon.finance.CostValuationModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 12.4.1.1 计价方法配置。
 *
 * <p>蓝图 12-finance.md：「配置成本计算方法（先进先出/移动加权平均/批次成本/指定成本法），<b>按法人或品类设置</b>，<b>确定生效时间</b>。」输入：「计价方法选择；生效时间；适用范围。」输出：「生效的成本方法配置。」关系：「上游 = 用户会计政策决定；下游 = 12.4.1.2、13 利润。<b>系统提供选项与试算，不替用户决定政策</b>；<b>切换方法需重算并留版本对比</b>。」
 *
 * <p><b>🔴 本模块只做配置面，不做重算执行。</b>
 *
 * <ul>
 *   <li>本模块产出：<b>方法配置（版本链）</b> + <b>指定单价</b> + <b>试算结果</b>；
 *   <li>本模块<b>不产出</b>：成本层、结转金额、重算批次 —— 那是 12.4.1.2「成本计算执行」的职责，
 *       数据层用 {@code recalculation_status} / {@code recalculation_owner_module='12.4.1.2'} 把这条边界钉死。
 * </ul>
 *
 * <p><b>🔴 系统提供选项与试算，不替用户决定政策。</b>本服务<b>不预置任何一行配置</b>：
 * 四个计价方法是<b>选项</b>（{@link FinanceCostValuationMethodOption}），落库的每一行都是租户自己选的。
 * 试算只把四种方法在<b>同一批真实成本层</b>上的结果并排给出，<b>不替用户选</b>，也<b>不写任何成本事实</b>。
 *
 * <p><b>版本链</b>：切换方法 = 追加新版本（DB 触发器钉死版本号递增 + 生效起点递进 + 历史不可改写），
 * 旧版本被置为 SUPERSEDED 并收口生效区间 ⇒ 「当时生效的是哪一版」永远可定位，版本对比也才有着落。
 *
 * <p><b>试算的数据来源</b>：catalog 的 5.2.1.1 成本构成（真实到货记录派生的成本层），
 * 经 {@link CostValuationLayerPort} 授权只读读取；上游读不到一律 503，**不用空集合冒充「没有成本层」**。
 *
 * <p><b>时间字段</b>：{@code effective_from} / {@code effective_to} / {@code created_at} 是 {@code timestamptz}，
 * 一律 OffsetDateTime + {@code ?::timestamptz}；试算的成本窗口是 {@code date}（日历日），一律 {@code ?::date}。
 */
@Service
public class CostValuationService {

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 金额小数位（与同服务 12.4.2.1 一致）。 */
  static final int MONEY_SCALE = 4;

  /** 数量小数位（AGENTS.md）。 */
  static final int QUANTITY_SCALE = 6;

  /** 成本窗口默认长度（天）：到货记录在窗口内才算成本层。 */
  static final int DEFAULT_WINDOW_DAYS = 365;

  private final DSLContext sql;
  private final CostValuationScopePort scopes;
  private final CostValuationLayerPort layers;

  public CostValuationService(DSLContext sql, CostValuationScopePort scopes, CostValuationLayerPort layers) {
    this.sql = sql;
    this.scopes = scopes;
    this.layers = layers;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**。
   *
   * <p>⚠️ 本模块的缺口必须分开说：12.4.1.2 成本计算执行（下游，**本服务**的后续模块）与
   * 13 利润（**别的**服务）—— 把两者都写成「不可用」，运维会以为要去催重算，
   * 而实际上重算是本服务内部的事，利润才在别处。
   */
  public FinanceCostValuationCapabilities capabilities() {
    var capabilities = new ArrayList<FinanceCostValuationCapability>();
    var gaps = new ArrayList<FinanceCostValuationDownstreamGap>();

    capabilities.add(
        new FinanceCostValuationCapability(
            "12.4.1.1",
            "计价方法配置（按法人 / 品类 + 生效时间 + 版本链）",
            true,
            "finance_cost_valuation_method + DB 层版本 / 生效区间守卫",
            "",
            "蓝图点名的四个方法（先进先出 / 移动加权平均 / 批次成本 / 指定成本法）都在"
                + "finance_cost_valuation_method.valuation_method 的 CHECK 里。"
                + "切换方法 = **追加新版本**（触发器钉死版本号递增 + 生效起点递进 + 历史不可改写），"
                + "旧版本收口生效区间后留作版本对比。"));

    capabilities.add(
        new FinanceCostValuationCapability(
            "12.4.1.1",
            "计价方法选项（系统提供选项，不替用户决定政策）",
            true,
            "服务层只读词表（FinanceCostValuationMethodOption），**不落库、不预置任何一行**",
            "",
            "系统只把四个方法连同各自的**口径说明**摆出来；落库的每一行都是租户自己选的。"
                + "本模块**零预置行** —— 计价方法是租户自己的会计政策，"
                + "系统替用户默认一个方法就是替用户记账。"));

    capabilities.add(
        new FinanceCostValuationCapability(
            "12.4.1.1",
            "指定成本（指定成本法下的单价，按 SKU + 币种）",
            true,
            "finance_cost_valuation_specified_cost（版本化 + 生效区间）",
            "",
            "指定成本法必须有单价才谈得上「指定」，所以单价单独一张表（按 SKU + 币种、同样版本化）。"
                + "单价为 0 在 DB 层被挡住：**「未维护」用「没有这一行」表达，不许写 0 冒充成本为 0。"));

    capabilities.add(
        new FinanceCostValuationCapability(
            "12.4.1.1",
            "试算（用真实成本层把四种方法的结果并排给出）",
            true,
            "finance_cost_valuation_trial + finance_cost_valuation_trial_result；"
                + "成本层来自 catalog 5.2.1.1（CostValuationLayerPort 只读）",
            "",
            "试算读的是 catalog 由**真实到货记录**派生的成本层（到货日 / 数量 / 金额 / 单价），"
                + "不是造出来的示例成本。上游读不到一律 503，写明「读不到」而不是「没有成本层」。"
                + "算不出结果的方法**不给数字**（comparable=false + gapNote 说明原因）。"));

    capabilities.add(
        new FinanceCostValuationCapability(
            "12.4.1.1",
            "版本对比（切换前 / 切换后）",
            true,
            "finance_cost_valuation_method 的版本链（服务层逐字段比对）",
            "",
            "蓝图「切换方法需重算并留版本对比」的**配置面**：逐字段给出差异"
                + "（方法 / 币种 / 生效区间 / 备注），并提示需要 12.4.1.2 重算。"
                + "对比的是配置本身，**不是**重算结果（重算属 12.4.1.2）。"));

    capabilities.add(
        new FinanceCostValuationCapability(
            "17.1.2",
            "法人范围（identity 法人档案）",
            true,
            "identity GET /identity/legal-entities（CostValuationScopePort 只读）",
            "",
            "法人清单**实时**读 identity，本服务不建影子表、不复制身份证明字段；"
                + "配置行只存法人逻辑 ID + 名称快照。读不到时范围下拉显示「读不到」，"
                + "**不是**「这个租户没有法人」。"));

    capabilities.add(
        new FinanceCostValuationCapability(
            "2.2.1",
            "品类范围（catalog 品类树）",
            true,
            "catalog GET /catalog/categories/trees + /trees/{treeId}/nodes（CostValuationScopePort 只读）",
            "",
            "品类清单**实时**读 catalog 品类树；同样不建影子表，配置行只存逻辑 ID + 名称快照。"));

    gaps.add(
        new FinanceCostValuationDownstreamGap(
            "12.4.1.2",
            "成本计算执行（成本层 + 结转金额 + 重算）",
            "finance",
            false,
            "下游",
            "🔴 **未交付**。本模块只产出**配置面**（方法 / 生效区间 / 版本链 / 试算），"
                + "**不产出任何成本层或结转金额**。切换方法后本表只把 "
                + "recalculation_status 置为 PENDING 并写明归属 12.4.1.2，"
                + "真正的重算由 12.4.1.2 执行。"));

    gaps.add(
        new FinanceCostValuationDownstreamGap(
            "12.4.1.3",
            "成本差异分析",
            "finance",
            false,
            "下游",
            "🔴 **未交付**。它的输入是 12.4.1.2 的成本计算结果；本模块**不产出**任何差异数字。"));

    gaps.add(
        new FinanceCostValuationDownstreamGap(
            "13",
            "利润",
            "reporting",
            false,
            "下游",
            "🔴 **未交付**。计价方法会影响成本、进而影响利润，但本模块**不产出任何利润数字**，"
                + "只产出「用的是哪一版方法」这一条口径事实。"));

    return new FinanceCostValuationCapabilities(capabilities, gaps);
  }

  // ================================================================ 选项

  /** 筛选项下拉：方法选项来自本模块的词表（只读，不落库），其余来自真实数据。 */
  public FinanceCostValuationOptions options() {
    var methods = new ArrayList<FinanceCostValuationMethodOption>();
    for (var m : ValuationMethod.values()) {
      methods.add(new FinanceCostValuationMethodOption(m.name(), m.label(), m.basis()));
    }
    var scopeTypes = new ArrayList<String>();
    for (var t : ValuationScopeType.values()) scopeTypes.add(t.name());
    var statuses = new ArrayList<String>();
    for (var s : ValuationStatus.values()) statuses.add(s.name());
    var recalculations = new ArrayList<String>();
    for (var r : RecalculationStatus.values()) recalculations.add(r.name());
    var currencies = sql.fetch(
            "select distinct currency from finance_cost_valuation_method order by currency")
        .stream()
        .map(r -> r.get(0, String.class))
        .filter(java.util.Objects::nonNull)
        .toList();
    return new FinanceCostValuationOptions(methods, scopeTypes, statuses, currencies, recalculations);
  }

  /** 范围选项（法人 + 品类）：实时读上游，读不到就如实说读不到。 */
  public FinanceCostValuationScopeOptions scopeOptions(String bearer) {
    var entities = scopes.legalEntities(bearer);
    var categories = scopes.categories(bearer);
    var entityOptions = new ArrayList<FinanceCostValuationScopeOption>();
    for (var e : entities.items()) {
      entityOptions.add(
          new FinanceCostValuationScopeOption(
              ValuationScopeType.LEGAL_ENTITY.name(), e.id(), e.code(), e.legalName(), e.status(), true, ""));
    }
    var categoryOptions = new ArrayList<FinanceCostValuationScopeOption>();
    for (var c : categories.items()) {
      categoryOptions.add(
          new FinanceCostValuationScopeOption(
              ValuationScopeType.CATEGORY.name(),
              c.id(),
              c.code(),
              c.name(),
              "ACTIVE",
              true,
              c.path() == null || c.path().isBlank() ? "" : c.path()));
    }
    return new FinanceCostValuationScopeOptions(
        entityOptions, categoryOptions, entities.note(), categories.note());
  }

  // ================================================================ 方法配置

  /** 方法配置列表（真分页 + 真筛选）。 */
  public FinanceCostValuationMethodPage methods(
      String scopeType,
      UUID scopeRefId,
      String valuationMethod,
      String status,
      String currency,
      String keyword,
      int page,
      int size) {
    var where = new StringBuilder(" where tenant_id = ?");
    var binds = new ArrayList<Object>();
    binds.add(currentTenant());
    if (scopeType != null && !scopeType.isBlank()) {
      where.append(" and scope_type = ?");
      binds.add(scopeType.trim().toUpperCase(Locale.ROOT));
    }
    if (scopeRefId != null) {
      where.append(" and scope_ref_id = ?");
      binds.add(scopeRefId);
    }
    if (valuationMethod != null && !valuationMethod.isBlank()) {
      where.append(" and valuation_method = ?");
      binds.add(valuationMethod.trim().toUpperCase(Locale.ROOT));
    }
    if (status != null && !status.isBlank()) {
      where.append(" and status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (currency != null && !currency.isBlank()) {
      where.append(" and currency = ?");
      binds.add(currency.trim().toUpperCase(Locale.ROOT));
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (scope_code ilike ? or scope_name ilike ? or note ilike ?)");
      var like = "%" + keyword.trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }

    var total =
        sql.fetchOne("select count(*) from finance_cost_valuation_method" + where, binds.toArray())
            .get(0, Long.class);
    var activeCount =
        sql.fetchOne(
                "select count(*) from finance_cost_valuation_method where tenant_id = ? and status = 'ACTIVE'",
                currentTenant())
            .get(0, Long.class);

    var limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var offset = Math.max(page, 0) * limit;
    binds.add(limit);
    binds.add(offset);
    var rows =
        sql.fetch(
            "select id,scope_type,scope_ref_id,scope_code,scope_name,valuation_method,currency,"
                + " effective_from,effective_to,status,version,supersedes_id,recalculation_status,"
                + " recalculation_owner_module,note,created_by,created_at,updated_at"
                + " from finance_cost_valuation_method"
                + where
                + " order by scope_type,scope_ref_id,version desc limit ? offset ?",
            binds.toArray());
    var items = new ArrayList<FinanceCostValuationMethodRow>();
    for (var row : rows) items.add(methodRow(row));
    return new FinanceCostValuationMethodPage(items, Math.max(page, 0), limit, total, activeCount);
  }

  /**
   * 登记 / 切换计价方法（按「范围」upsert：同范围已有生效版本 ⇒ **追加新版本**）。
   *
   * <p>⚠️ 写库顺序是「先把上一版置为 SUPERSEDED 并收口生效区间，再插入新的一版」——
   * 「同一范围只能有一个生效版本」是部分唯一索引，顺序反了会撞唯一约束。
   */
  public FinanceCostValuationMethodRow switchMethod(
      FinanceCostValuationMethodInput input, AccessService.Access actor) {
    var scopeType = ValuationScopeType.parse(input.scopeType());
    if (scopeType == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "适用范围只能是 LEGAL_ENTITY（按法人）或 CATEGORY（按品类）");
    }
    if (input.scopeRefId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定计价范围（法人或品类）");
    }
    var method = ValuationMethod.parse(input.valuationMethod());
    if (method == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "计价方法只能是 FIFO（先进先出）/ MOVING_AVERAGE（移动加权平均）/ BATCH（批次成本）/ SPECIFIED（指定成本法）");
    }
    var currency = input.currency() == null ? "" : input.currency().trim().toUpperCase(Locale.ROOT);
    if (!currency.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "计价币种必须是三位大写字母（ISO 4217）");
    }
    if (input.effectiveFrom() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "必须给出生效时间：没有生效时间的计价方法等于没有");
    }
    var scopeCode = input.scopeCode() == null ? "" : input.scopeCode().trim();
    if (scopeCode.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出计价范围的编码");
    }

    var tenant = currentTenant();
    var now = OffsetDateTime.now(ZoneOffset.UTC);

    // 上一版（版本号最大的那一行）。
    var last =
        sql.fetchOne(
            "select id,version,valuation_method,effective_from,status from finance_cost_valuation_method"
                + " where tenant_id = ? and scope_type = ? and scope_ref_id = ?"
                + " order by version desc limit 1",
            tenant,
            scopeType.name(),
            input.scopeRefId());

    UUID supersedesId = null;
    int version = 1;
    boolean methodChanged = false;
    if (last != null) {
      supersedesId = last.get("id", UUID.class);
      version = last.get("version", Integer.class) + 1;
      methodChanged = !method.name().equals(last.get("valuation_method", String.class));
      if (!input.effectiveFrom().isAfter(last.get("effective_from", OffsetDateTime.class))) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "新版本的生效时间必须晚于上一版本（"
                + last.get("effective_from", OffsetDateTime.class)
                + "），否则历史成本会被追溯改写");
      }
      // 先收口上一版：置为 SUPERSEDED + 收口生效区间（历史只读，不改方法）。
      sql.execute(
          "update finance_cost_valuation_method set status = 'SUPERSEDED', effective_to = ?::timestamptz,"
              + " updated_at = ?::timestamptz where tenant_id = ? and id = ?",
          input.effectiveFrom(),
          now,
          tenant,
          supersedesId);
    }

    var id = UUID.randomUUID();
    sql.execute(
        "insert into finance_cost_valuation_method(id,tenant_id,scope_type,scope_ref_id,scope_code,"
            + " scope_name,valuation_method,currency,effective_from,effective_to,status,version,"
            + " supersedes_id,recalculation_status,recalculation_owner_module,note,created_by,"
            + " created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?::timestamptz,null,'ACTIVE',?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        tenant,
        scopeType.name(),
        input.scopeRefId(),
        scopeCode,
        input.scopeName() == null ? "" : input.scopeName().trim(),
        method.name(),
        currency,
        input.effectiveFrom(),
        version,
        supersedesId,
        // 切换方法 ⇒ 需要 12.4.1.2 重算；首次配置不需要。
        methodChanged ? RecalculationStatus.PENDING.name() : RecalculationStatus.NOT_REQUIRED.name(),
        "12.4.1.2",
        input.note() == null ? "" : input.note().trim(),
        actor == null ? null : actor.userId(),
        now,
        now);
    return method(id);
  }

  /** 版本历史（新到旧）。 */
  public List<FinanceCostValuationMethodRow> versions(String scopeType, UUID scopeRefId) {
    var type = ValuationScopeType.parse(scopeType);
    if (type == null || scopeRefId == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出范围类型与范围 ID");
    }
    var rows =
        sql.fetch(
            "select id,scope_type,scope_ref_id,scope_code,scope_name,valuation_method,currency,"
                + " effective_from,effective_to,status,version,supersedes_id,recalculation_status,"
                + " recalculation_owner_module,note,created_by,created_at,updated_at"
                + " from finance_cost_valuation_method"
                + " where tenant_id = ? and scope_type = ? and scope_ref_id = ?"
                + " order by version desc",
            currentTenant(),
            type.name(),
            scopeRefId);
    var items = new ArrayList<FinanceCostValuationMethodRow>();
    for (var row : rows) items.add(methodRow(row));
    return items;
  }

  /**
   * 版本对比（蓝图「切换方法需重算并留版本对比」的**配置面**）。
   *
   * <p>⚠️ 对比的是配置本身（方法 / 币种 / 生效区间 / 备注），<b>不是</b>重算结果。
   */
  public FinanceCostValuationVersionComparison compare(
      String scopeType, UUID scopeRefId, int fromVersion, int toVersion) {
    var type = ValuationScopeType.parse(scopeType);
    if (type == null || scopeRefId == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出范围类型与范围 ID");
    }
    var from = versionRow(type, scopeRefId, fromVersion);
    var to = versionRow(type, scopeRefId, toVersion);
    if (from == null || to == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "要对比的版本不存在");
    }
    var differences = new ArrayList<FinanceCostValuationVersionDiff>();
    differences.add(
        diff(
            "valuationMethod",
            "计价方法",
            from.valuationMethod() == null ? "" : from.valuationMethod().name(),
            to.valuationMethod() == null ? "" : to.valuationMethod().name()));
    differences.add(diff("currency", "计价币种", from.currency(), to.currency()));
    differences.add(
        diff("effectiveFrom", "生效起点", String.valueOf(from.effectiveFrom()), String.valueOf(to.effectiveFrom())));
    differences.add(
        diff("effectiveTo", "生效终点", String.valueOf(from.effectiveTo()), String.valueOf(to.effectiveTo())));
    differences.add(diff("status", "状态", from.status(), to.status()));
    differences.add(diff("note", "备注", from.note(), to.note()));
    var changed = from.valuationMethod() != to.valuationMethod();
    var note =
        changed
            ? "计价方法从「"
                + from.valuationMethodLabel()
                + "」切换为「"
                + to.valuationMethodLabel()
                + "」——切换前算出来的成本不会自动改写，需要 12.4.1.2 重算后才能对齐口径。"
            : "两个版本的计价方法相同，其余差异见下表。";
    return new FinanceCostValuationVersionComparison(
        scopeRefId,
        type.name(),
        to.scopeCode(),
        to.scopeName(),
        from,
        to,
        differences,
        changed,
        note);
  }

  // ================================================================ 指定成本

  /** 指定单价列表（真分页 + 真筛选）。 */
  public FinanceCostValuationSpecifiedCostPage specifiedCosts(
      UUID skuId, String currency, String status, int page, int size) {
    var where = new StringBuilder(" where tenant_id = ?");
    var binds = new ArrayList<Object>();
    binds.add(currentTenant());
    if (skuId != null) {
      where.append(" and sku_id = ?");
      binds.add(skuId);
    }
    if (currency != null && !currency.isBlank()) {
      where.append(" and currency = ?");
      binds.add(currency.trim().toUpperCase(Locale.ROOT));
    }
    if (status != null && !status.isBlank()) {
      where.append(" and status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    var total =
        sql.fetchOne("select count(*) from finance_cost_valuation_specified_cost" + where, binds.toArray())
            .get(0, Long.class);
    var limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var offset = Math.max(page, 0) * limit;
    binds.add(limit);
    binds.add(offset);
    var rows =
        sql.fetch(
            "select id,sku_id,sku_code,currency,unit_cost,effective_from,effective_to,status,version,"
                + " note,created_at from finance_cost_valuation_specified_cost"
                + where
                + " order by sku_id,currency,version desc limit ? offset ?",
            binds.toArray());
    var items = new ArrayList<FinanceCostValuationSpecifiedCostRow>();
    for (var row : rows) items.add(specifiedCostRow(row));
    return new FinanceCostValuationSpecifiedCostPage(items, Math.max(page, 0), limit, total);
  }

  /** 维护指定单价（同 SKU + 币种已有生效值 ⇒ 追加新版本）。 */
  public FinanceCostValuationSpecifiedCostRow upsertSpecifiedCost(
      FinanceCostValuationSpecifiedCostInput input, AccessService.Access actor) {
    if (input.skuId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定 SKU");
    }
    var currency = input.currency() == null ? "" : input.currency().trim().toUpperCase(Locale.ROOT);
    if (!currency.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是三位大写字母（ISO 4217）");
    }
    if (input.unitCost() == null || input.unitCost().signum() <= 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "指定单价必须大于 0：成本为 0 与「尚未维护」是两回事");
    }
    if (input.effectiveFrom() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出生效时间");
    }
    var tenant = currentTenant();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var last =
        sql.fetchOne(
            "select id,version,effective_from from finance_cost_valuation_specified_cost"
                + " where tenant_id = ? and sku_id = ? and currency = ? order by version desc limit 1",
            tenant,
            input.skuId(),
            currency);
    UUID supersedesId = null;
    int version = 1;
    if (last != null) {
      supersedesId = last.get("id", UUID.class);
      version = last.get("version", Integer.class) + 1;
      if (!input.effectiveFrom().isAfter(last.get("effective_from", OffsetDateTime.class))) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "新版本的生效时间必须晚于上一版本（"
                + last.get("effective_from", OffsetDateTime.class)
                + "），否则历史成本会被追溯改写");
      }
      sql.execute(
          "update finance_cost_valuation_specified_cost set status = 'SUPERSEDED',"
              + " effective_to = ?::timestamptz, updated_at = ?::timestamptz"
              + " where tenant_id = ? and id = ?",
          input.effectiveFrom(),
          now,
          tenant,
          supersedesId);
    }
    var id = UUID.randomUUID();
    sql.execute(
        "insert into finance_cost_valuation_specified_cost(id,tenant_id,sku_id,sku_code,currency,"
            + " unit_cost,effective_from,effective_to,status,version,note,created_by,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?::timestamptz,null,'ACTIVE',?,?,?,?::timestamptz,?::timestamptz)",
        id,
        tenant,
        input.skuId(),
        input.skuCode() == null ? "" : input.skuCode().trim(),
        currency,
        input.unitCost().setScale(MONEY_SCALE, RoundingMode.HALF_UP),
        input.effectiveFrom(),
        version,
        input.note() == null ? "" : input.note().trim(),
        actor == null ? null : actor.userId(),
        now,
        now);
    return specifiedCost(id);
  }

  // ================================================================ 试算

  /**
   * 发起一次试算：把四种计价方法在<b>同一批真实成本层</b>上的结果并排算出来并留痕。
   *
   * <p>⚠️ 试算**不执行重算**（那是 12.4.1.2）、**不写任何成本事实**，
   * 只把「用这个方法会算出多少」摆出来给人对比。
   */
  public FinanceCostValuationTrialRow trial(
      FinanceCostValuationTrialRequest request, AccessService.Access actor, String bearer) {
    var scopeType = ValuationScopeType.parse(request.scopeType());
    if (scopeType == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "适用范围只能是 LEGAL_ENTITY（按法人）或 CATEGORY（按品类）");
    }
    if (request.scopeRefId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定计价范围（法人或品类）");
    }
    if (request.skuId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定 SKU");
    }
    if (request.issueQuantity() == null || request.issueQuantity().signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "试算数量必须大于 0");
    }
    var effectiveAt = request.effectiveAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : request.effectiveAt();
    var issueQuantity = request.issueQuantity().setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);

    // 上游真实成本层（读不到 ⇒ 503，绝不返回空集合冒充「没有成本层」）。
    var today = effectiveAt.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
    var composition =
        layers.composition(
            request.skuId(),
            request.currency() == null || request.currency().isBlank() ? null : request.currency(),
            today.minusDays(DEFAULT_WINDOW_DAYS),
            today,
            bearer);
    var currency =
        composition.currency() == null || composition.currency().isBlank()
            ? (request.currency() == null ? "" : request.currency().trim().toUpperCase(Locale.ROOT))
            : composition.currency();

    // 当前生效的方法（试算时刻）。
    var effective =
        effectiveMethod(scopeType, request.scopeRefId(), effectiveAt);
    var effectiveMethodName = effective == null ? null : effective.valuationMethod().name();

    // 成本层：同币种 + 数量可折算 + 单价可算。
    var usable = new ArrayList<Layer>();
    var skippedCurrency = 0;
    var skippedQuantity = 0;
    for (var lot : composition.lots() == null ? List.<CostValuationLayerPort.CostLayer>of() : composition.lots()) {
      if (lot == null) continue;
      if (currency != null && !currency.isBlank() && !currency.equals(lot.currency())) {
        skippedCurrency++;
        continue;
      }
      var baseQty = lot.baseQuantity();
      if (baseQty == null || baseQty.signum() <= 0) {
        skippedQuantity++;
        continue;
      }
      var unitCost = lot.unitCost();
      if (unitCost == null) {
        var amount = lot.amount() == null ? BigDecimal.ZERO : lot.amount();
        unitCost = amount.divide(baseQty, MONEY_SCALE, RoundingMode.HALF_UP);
      }
      usable.add(
          new Layer(
              lot.lotRef() == null ? "" : lot.lotRef(),
              lot.receivedDate(),
              baseQty.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP),
              lot.amount() == null ? BigDecimal.ZERO : lot.amount(),
              unitCost.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
              lot.currency() == null ? currency : lot.currency()));
    }
    usable.sort(
        Comparator.comparing((Layer l) -> l.receivedDate() == null ? LocalDate.MIN : l.receivedDate())
            .thenComparing(l -> l.lotRef()));

    var available =
        usable.stream()
            .map(Layer::quantity)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);

    var results = new ArrayList<FinanceCostValuationTrialResultRow>();
    results.add(fifoResult(usable, issueQuantity, available, currency));
    results.add(movingAverageResult(usable, issueQuantity, available, currency));
    results.add(batchResult(usable, issueQuantity, available, currency, request.batchRef()));
    results.add(specifiedResult(request.skuId(), currency, issueQuantity, effectiveAt));

    // 与当前生效方法的差额（覆盖不全时不给差额 —— 半截成本与全成本相减是假的）。
    var effectiveResult =
        effectiveMethodName == null
            ? null
            : results.stream()
                .filter(r -> r.valuationMethod().equals(effectiveMethodName))
                .findFirst()
                .orElse(null);
    var effectiveCost =
        effectiveResult != null && effectiveResult.comparable() && effectiveResult.shortfallQuantity().signum() == 0
            ? effectiveResult.costAmount()
            : null;
    var compared = new ArrayList<FinanceCostValuationTrialResultRow>();
    for (var r : results) {
      var difference =
          effectiveCost == null || !r.comparable() || r.shortfallQuantity().signum() > 0
              ? null
              : r.costAmount().subtract(effectiveCost).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
      compared.add(
          new FinanceCostValuationTrialResultRow(
              r.valuationMethod(),
              r.valuationMethodLabel(),
              r.methodBasis(),
              r.currency(),
              r.coveredQuantity(),
              r.shortfallQuantity(),
              r.unitCost(),
              r.costAmount(),
              difference,
              r.comparable(),
              r.gapNote(),
              r.basisNote()));
    }

    // 留痕：试算批次 + 四个方法的结果。
    var tenant = currentTenant();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var trialId = UUID.randomUUID();
    var sourceNote = new StringBuilder();
    if (composition.excludedDeliveryCount() != null && composition.excludedDeliveryCount() > 0) {
      sourceNote
          .append("上游有 ")
          .append(composition.excludedDeliveryCount())
          .append(" 条到货记录被排除（数量折算不出来）——成本层不完整。");
    }
    if (skippedQuantity > 0) {
      sourceNote.append("另有 ").append(skippedQuantity).append(" 层数量折算不出来，未参与计算。");
    }
    if (skippedCurrency > 0) {
      sourceNote
          .append("另有 ")
          .append(skippedCurrency)
          .append(" 层是其他币种，未参与计算（**绝不跨币种相加**）。");
    }
    if (sourceNote.isEmpty()) sourceNote.append("成本层来自 catalog 5.2.1.1 的真实到货记录。");

    sql.execute(
        "insert into finance_cost_valuation_trial(id,tenant_id,scope_type,scope_ref_id,scope_code,"
            + " scope_name,sku_id,sku_code,currency,issue_quantity,available_quantity,layer_count,"
            + " source_service,source_window_from,source_window_to,source_note,basis_note,effective_method,"
            + " effective_method_cost_amount,created_by,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,'catalog',?::date,?::date,?,?,?,?,?,?::timestamptz)",
        trialId,
        tenant,
        scopeType.name(),
        request.scopeRefId(),
        effective == null ? "" : effective.scopeCode(),
        effective == null ? "" : effective.scopeName(),
        request.skuId(),
        request.skuCode() == null ? "" : request.skuCode().trim(),
        currency,
        issueQuantity,
        available,
        usable.size(),
        today.minusDays(DEFAULT_WINDOW_DAYS),
        today,
        sourceNote.toString(),
        "试算口径：出库数量 " + issueQuantity + "（SKU 基础单位），成本层取 " + today.minusDays(DEFAULT_WINDOW_DAYS)
            + " 至 " + today + " 的真实到货记录；四种方法在同一批成本层上计算。",
        effectiveMethodName,
        effectiveCost,
        actor == null ? null : actor.userId(),
        now);

    for (var r : compared) {
      sql.execute(
          "insert into finance_cost_valuation_trial_result(id,tenant_id,trial_id,valuation_method,currency,"
              + " covered_quantity,shortfall_quantity,unit_cost,cost_amount,difference_to_effective_method,"
              + " comparable,gap_note,basis_note,created_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
          UUID.randomUUID(),
          tenant,
          trialId,
          r.valuationMethod(),
          r.currency(),
          r.coveredQuantity(),
          r.shortfallQuantity(),
          r.unitCost(),
          r.costAmount(),
          r.differenceToEffectiveMethod(),
          r.comparable(),
          r.gapNote(),
          r.basisNote(),
          now);
    }

    return trialRow(trialId);
  }

  /** 试算列表（真分页 + 真筛选）。 */
  public FinanceCostValuationTrialPage trials(UUID skuId, String scopeType, int page, int size) {
    var where = new StringBuilder(" where tenant_id = ?");
    var binds = new ArrayList<Object>();
    binds.add(currentTenant());
    if (skuId != null) {
      where.append(" and sku_id = ?");
      binds.add(skuId);
    }
    if (scopeType != null && !scopeType.isBlank()) {
      where.append(" and scope_type = ?");
      binds.add(scopeType.trim().toUpperCase(Locale.ROOT));
    }
    var total =
        sql.fetchOne("select count(*) from finance_cost_valuation_trial" + where, binds.toArray())
            .get(0, Long.class);
    var limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var offset = Math.max(page, 0) * limit;
    binds.add(limit);
    binds.add(offset);
    var rows =
        sql.fetch(
            "select id from finance_cost_valuation_trial" + where + " order by created_at desc limit ? offset ?",
            binds.toArray());
    var items = new ArrayList<FinanceCostValuationTrialRow>();
    for (var row : rows) items.add(trialRow(row.get("id", UUID.class)));
    return new FinanceCostValuationTrialPage(items, Math.max(page, 0), limit, total);
  }

  /** 试算明细（表头 + 四个方法的结果）。 */
  public FinanceCostValuationTrialRow trialDetail(UUID id) {
    var found =
        sql.fetchOne("select id from finance_cost_valuation_trial where tenant_id = ? and id = ?",
            currentTenant(), id);
    if (found == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "试算记录不存在");
    }
    return trialRow(id);
  }

  // ================================================================ 试算算法

  /** 一层成本（基础单位口径）。 */
  private record Layer(
      String lotRef,
      LocalDate receivedDate,
      BigDecimal quantity,
      BigDecimal amount,
      BigDecimal unitCost,
      String currency) {}

  /** 先进先出：按到货日升序消耗成本层。 */
  private FinanceCostValuationTrialResultRow fifoResult(
      List<Layer> layers, BigDecimal issueQuantity, BigDecimal available, String currency) {
    var method = ValuationMethod.FIFO;
    if (layers.isEmpty()) {
      return empty(method, currency, issueQuantity, "窗口内没有可用成本层（真实到货记录为空或数量折算不出来）");
    }
    var remaining = issueQuantity;
    var cost = BigDecimal.ZERO;
    var covered = BigDecimal.ZERO;
    for (var layer : layers) {
      if (remaining.signum() <= 0) break;
      var take = layer.quantity().min(remaining);
      cost = cost.add(take.multiply(layer.unitCost()).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
      covered = covered.add(take);
      remaining = remaining.subtract(take);
    }
    covered = covered.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    var shortfall = issueQuantity.subtract(covered).setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    cost = cost.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    var unitCost =
        covered.signum() > 0 ? cost.divide(covered, MONEY_SCALE, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    return new FinanceCostValuationTrialResultRow(
        method.name(),
        method.label(),
        method.basis(),
        currency,
        covered,
        shortfall,
        unitCost,
        cost,
        null,
        true,
        shortfall.signum() > 0 ? "库存不足：" + covered + " 件可用，缺口 " + shortfall + " 件" : "",
        "按到货日升序消耗成本层，成本金额 = 各层消耗量 × 该层单价。");
  }

  /** 移动加权平均：全部可用成本层按数量加权平均。 */
  private FinanceCostValuationTrialResultRow movingAverageResult(
      List<Layer> layers, BigDecimal issueQuantity, BigDecimal available, String currency) {
    var method = ValuationMethod.MOVING_AVERAGE;
    if (layers.isEmpty()) {
      return empty(method, currency, issueQuantity, "窗口内没有可用成本层（真实到货记录为空或数量折算不出来）");
    }
    var totalAmount = BigDecimal.ZERO;
    var totalQuantity = BigDecimal.ZERO;
    for (var layer : layers) {
      totalAmount = totalAmount.add(layer.amount());
      totalQuantity = totalQuantity.add(layer.quantity());
    }
    if (totalQuantity.signum() <= 0) {
      return empty(method, currency, issueQuantity, "可用成本层数量为 0，算不出加权平均");
    }
    var unitCost = totalAmount.divide(totalQuantity, MONEY_SCALE, RoundingMode.HALF_UP);
    var covered = issueQuantity.min(available).setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    var shortfall = issueQuantity.subtract(covered).setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    var cost = unitCost.multiply(covered).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    return new FinanceCostValuationTrialResultRow(
        method.name(),
        method.label(),
        method.basis(),
        currency,
        covered,
        shortfall,
        unitCost,
        cost,
        null,
        true,
        shortfall.signum() > 0 ? "库存不足：缺口 " + shortfall + " 件" : "",
        "单位成本 = 窗口内可用成本层总金额 ÷ 总数量；成本金额 = 单位成本 × 覆盖数量。");
  }

  /** 批次成本：按指定批次结转（没指定批次就**算不出来**，如实说）。 */
  private FinanceCostValuationTrialResultRow batchResult(
      List<Layer> layers, BigDecimal issueQuantity, BigDecimal available, String currency, String batchRef) {
    var method = ValuationMethod.BATCH;
    if (batchRef == null || batchRef.isBlank()) {
      return empty(method, currency, issueQuantity, "批次成本法需要指定批次：本次没有指定批次，算不出结果");
    }
    var ref = batchRef.trim();
    Layer hit = null;
    for (var layer : layers) {
      if (ref.equals(layer.lotRef())) {
        hit = layer;
        break;
      }
    }
    if (hit == null) {
      return empty(method, currency, issueQuantity, "指定批次 " + ref + " 不在本次成本窗口的成本层里");
    }
    var covered = issueQuantity.min(hit.quantity()).setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    var shortfall = issueQuantity.subtract(covered).setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    var cost = hit.unitCost().multiply(covered).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    return new FinanceCostValuationTrialResultRow(
        method.name(),
        method.label(),
        method.basis(),
        currency,
        covered,
        shortfall,
        hit.unitCost(),
        cost,
        null,
        true,
        shortfall.signum() > 0 ? "该批次数量不足：缺口 " + shortfall + " 件" : "",
        "批次 " + hit.lotRef() + "（到货日 " + hit.receivedDate() + "）的单位成本 × 覆盖数量。");
  }

  /** 指定成本法：用本模块自己维护的指定单价（没有维护就**算不出来**）。 */
  private FinanceCostValuationTrialResultRow specifiedResult(
      UUID skuId, String currency, BigDecimal issueQuantity, OffsetDateTime effectiveAt) {
    var method = ValuationMethod.SPECIFIED;
    var row =
        sql.fetchOne(
                "select unit_cost from finance_cost_valuation_specified_cost"
                    + " where tenant_id = ? and sku_id = ? and currency = ? and status = 'ACTIVE'"
                    + " and effective_from <= ?::timestamptz"
                    + " and (effective_to is null or effective_to > ?::timestamptz)"
                    + " order by version desc limit 1",
                currentTenant(),
                skuId,
                currency,
                effectiveAt,
                effectiveAt);
    if (row == null) {
      return empty(
          method,
          currency,
          issueQuantity,
          "该 SKU 在 " + currency + " 下没有维护指定成本单价（指定成本法要先维护指定单价）");
    }
    var unitCost = row.get("unit_cost", BigDecimal.class).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    var cost = unitCost.multiply(issueQuantity).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    return new FinanceCostValuationTrialResultRow(
        method.name(),
        method.label(),
        method.basis(),
        currency,
        issueQuantity,
        BigDecimal.ZERO.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP),
        unitCost,
        cost,
        null,
        true,
        "",
        "按本模块维护的指定单价 " + unitCost + " " + currency + " × " + issueQuantity + "。");
  }

  /** 算不出结果的方法：<b>不给数字</b>，只给原因。 */
  private FinanceCostValuationTrialResultRow empty(
      ValuationMethod method, String currency, BigDecimal issueQuantity, String gapNote) {
    return new FinanceCostValuationTrialResultRow(
        method.name(),
        method.label(),
        method.basis(),
        currency,
        BigDecimal.ZERO.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP),
        issueQuantity.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP),
        null,
        null,
        null,
        false,
        gapNote,
        "本次试算在这批真实数据上算不出结果，所以不给数字（显示 0 会被误读成「成本是 0」）。");
  }

  // ================================================================ 读模型

  private FinanceCostValuationMethodRow method(UUID id) {
    var row =
        sql.fetchOne(
                "select id,scope_type,scope_ref_id,scope_code,scope_name,valuation_method,currency,"
                    + " effective_from,effective_to,status,version,supersedes_id,recalculation_status,"
                    + " recalculation_owner_module,note,created_by,created_at,updated_at"
                    + " from finance_cost_valuation_method where tenant_id = ? and id = ?",
                currentTenant(),
                id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "计价方法配置不存在");
    }
    return methodRow(row);
  }

  private FinanceCostValuationMethodRow versionRow(ValuationScopeType type, UUID scopeRefId, int version) {
    var row =
        sql.fetchOne(
                "select id,scope_type,scope_ref_id,scope_code,scope_name,valuation_method,currency,"
                    + " effective_from,effective_to,status,version,supersedes_id,recalculation_status,"
                    + " recalculation_owner_module,note,created_by,created_at,updated_at"
                    + " from finance_cost_valuation_method"
                    + " where tenant_id = ? and scope_type = ? and scope_ref_id = ? and version = ?",
                currentTenant(),
                type.name(),
                scopeRefId,
                version);
    return row == null ? null : methodRow(row);
  }

  /** 某个范围在给定时刻生效的配置。 */
  private FinanceCostValuationMethodRow effectiveMethod(
      ValuationScopeType type, UUID scopeRefId, OffsetDateTime at) {
    var row =
        sql.fetchOne(
                "select id,scope_type,scope_ref_id,scope_code,scope_name,valuation_method,currency,"
                    + " effective_from,effective_to,status,version,supersedes_id,recalculation_status,"
                    + " recalculation_owner_module,note,created_by,created_at,updated_at"
                    + " from finance_cost_valuation_method"
                    + " where tenant_id = ? and scope_type = ? and scope_ref_id = ?"
                    + " and effective_from <= ?::timestamptz"
                    + " and (effective_to is null or effective_to > ?::timestamptz)"
                    + " order by version desc limit 1",
                currentTenant(),
                type.name(),
                scopeRefId,
                at,
                at);
    return row == null ? null : methodRow(row);
  }

  private FinanceCostValuationMethodRow methodRow(Record row) {
    var method = ValuationMethod.parse(row.get("valuation_method", String.class));
    return new FinanceCostValuationMethodRow(
        row.get("id", UUID.class),
        row.get("scope_type", String.class),
        row.get("scope_ref_id", UUID.class),
        row.get("scope_code", String.class),
        row.get("scope_name", String.class),
        method,
        method == null ? "" : method.label(),
        method == null ? "" : method.basis(),
        row.get("currency", String.class),
        row.get("effective_from", OffsetDateTime.class),
        row.get("effective_to", OffsetDateTime.class),
        row.get("status", String.class),
        row.get("version", Integer.class),
        row.get("supersedes_id", UUID.class),
        row.get("recalculation_status", String.class),
        row.get("recalculation_owner_module", String.class),
        row.get("note", String.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private FinanceCostValuationSpecifiedCostRow specifiedCost(UUID id) {
    var row =
        sql.fetchOne(
                "select id,sku_id,sku_code,currency,unit_cost,effective_from,effective_to,status,version,"
                    + " note,created_at from finance_cost_valuation_specified_cost"
                    + " where tenant_id = ? and id = ?",
                currentTenant(),
                id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "指定成本单价不存在");
    }
    return specifiedCostRow(row);
  }

  private FinanceCostValuationSpecifiedCostRow specifiedCostRow(Record row) {
    return new FinanceCostValuationSpecifiedCostRow(
        row.get("id", UUID.class),
        row.get("sku_id", UUID.class),
        row.get("sku_code", String.class),
        row.get("currency", String.class),
        row.get("unit_cost", BigDecimal.class),
        row.get("effective_from", OffsetDateTime.class),
        row.get("effective_to", OffsetDateTime.class),
        row.get("status", String.class),
        row.get("version", Integer.class),
        row.get("note", String.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private FinanceCostValuationTrialRow trialRow(UUID id) {
    var row =
        sql.fetchOne(
                "select id,scope_type,scope_ref_id,scope_code,scope_name,sku_id,sku_code,currency,"
                    + " issue_quantity,available_quantity,layer_count,source_service,source_note,basis_note,"
                    + " effective_method,effective_method_cost_amount,created_at"
                    + " from finance_cost_valuation_trial where tenant_id = ? and id = ?",
                currentTenant(),
                id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "试算记录不存在");
    }
    var resultRows =
        sql.fetch(
            "select valuation_method,currency,covered_quantity,shortfall_quantity,unit_cost,cost_amount,"
                + " difference_to_effective_method,comparable,gap_note,basis_note"
                + " from finance_cost_valuation_trial_result"
                + " where tenant_id = ? and trial_id = ? order by valuation_method",
            currentTenant(),
            id);
    var results = new ArrayList<FinanceCostValuationTrialResultRow>();
    for (var r : resultRows) {
      var method = ValuationMethod.parse(r.get("valuation_method", String.class));
      results.add(
          new FinanceCostValuationTrialResultRow(
              r.get("valuation_method", String.class),
              method == null ? "" : method.label(),
              method == null ? "" : method.basis(),
              r.get("currency", String.class),
              r.get("covered_quantity", BigDecimal.class),
              r.get("shortfall_quantity", BigDecimal.class),
              r.get("unit_cost", BigDecimal.class),
              r.get("cost_amount", BigDecimal.class),
              r.get("difference_to_effective_method", BigDecimal.class),
              r.get("comparable", Boolean.class),
              r.get("gap_note", String.class),
              r.get("basis_note", String.class)));
    }
    var effectiveMethodName = row.get("effective_method", String.class);
    var effectiveCost = row.get("effective_method_cost_amount", BigDecimal.class);
    return new FinanceCostValuationTrialRow(
        row.get("id", UUID.class),
        row.get("scope_type", String.class),
        row.get("scope_ref_id", UUID.class),
        row.get("scope_code", String.class),
        row.get("scope_name", String.class),
        row.get("sku_id", UUID.class),
        row.get("sku_code", String.class),
        row.get("currency", String.class),
        row.get("issue_quantity", BigDecimal.class),
        row.get("available_quantity", BigDecimal.class),
        row.get("layer_count", Integer.class),
        row.get("source_service", String.class),
        row.get("source_note", String.class),
        row.get("basis_note", String.class),
        effectiveMethodName,
        effectiveCost,
        results,
        row.get("created_at", OffsetDateTime.class));
  }

  // ================================================================ 工具

  private FinanceCostValuationVersionDiff diff(
      String field, String label, String from, String to) {
    var a = from == null ? "" : from;
    var b = to == null ? "" : to;
    return new FinanceCostValuationVersionDiff(field, label, a, b, !a.equals(b));
  }

  private UUID currentTenant() {
    var raw =
        sql.fetchOne("select nullif(current_setting('app.current_tenant', true), '')::uuid as t")
            .get("t", UUID.class);
    if (raw == null) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "租户上下文缺失：计价方法配置必须在绑定租户的事务里执行");
    }
    return raw;
  }

}
