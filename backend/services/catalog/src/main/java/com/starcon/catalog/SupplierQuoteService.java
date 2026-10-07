package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static com.starcon.catalog.SupplierQuoteRules.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.6.2.1 供应商报价：供应商对 SKU 的报价（供应商货号、采购单位、币种、MOQ、交期、
 * 有效期、优先级、阶梯价），支持同一供应商同一 SKU 多个报价版本，
 * 以及蓝图点名的输出「有效报价列表（默认取优先级最高且在有效期内的版本）」。
 *
 * <p>本类的纪律：
 *
 * <ol>
 *   <li><b>报价是一版一行，不是 sku 上一个可覆盖的价</b>。蓝图那句「支持同一供应商同一 SKU
 *       多个报价版本」否掉了单行表：单行表下「上周的报价是多少」<b>没有答案</b> ——
 *       不是查不到，是根本没存过。理由见 {@code V32__supplier_quote.sql} 第二节。
 *   <li><b>「默认取」是一个可以失败的状态</b>（规则 <b>R2</b>）。同一优先级有多条有效报价时
 *       {@link #effective} 的 {@code selected} 返回 <b>null</b> 而不是随便一条，
 *       {@link #resolve} 直接 409 并附候选清单。这是蓝图那句
 *       「同一优先级多条有效报价必须报错让用户选，不能随机取一条」的字面兑现 ——
 *       <b>服务端任何一处都不许替用户挑</b>。
 *   <li><b>选择状态只在一处计算</b>。{@link SupplierQuoteRules#selectionStates} 是唯一实现，
 *       列表、有效报价、严格取价三条读出口都调它 —— 两处各判一次的症状是
 *       「列表说需人工选择、采购单自动带出了某一条」。
 *   <li><b>报价的 MOQ 与 3.6.1.2 档案的 MOQ 不是一回事</b>。这里是 SKU 级门槛（必填），
 *       那边是供应商级门槛（可空）。刻意不建外键（档案是 3.6.1.2 的写入所有权）；
 *       档案缺失时本模块给<b>提示</b>而不是拒绝写入。见 V32 第一节。
 *   <li><b>币种同口径</b>。阶梯的 {@code unit_price} 没有 currency 列（规则 <b>R3</b>），
 *       逐档设币种会造出「100 件档 USD、500 件档 EUR」这种在 06 采购单上无法结算的行。
 *   <li><b>阶梯整体替换而不是增量合并</b>。报价的阶梯是一个整体，
 *       合并写入会让「删掉一档」这个动作无法表达（规则 <b>R5</b>）。
 * </ol>
 *
 * <p>权限口径：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}，
 * 与 3.6.1.1 / 3.6.1.2 完全一致 —— 报价是商品主数据域里往来单位报价的那一半，
 * 不新造权限词表（那要改 identity 服务的迁移，属于另一个服务的写入所有权）。
 *
 * <p>租户一律来自已认证成员关系经 RLS 生效的连接上下文，<b>不读 {@code X-Tenant-Id} 头</b>。
 */
@Service
public class SupplierQuoteService {
  /** 单页最大行数。报价是租户级主数据，行数量级在千位，与 PartyService 同口径。 */
  private static final int MAX_PAGE_SIZE = 200;

  /**
   * 「即将到期」的门槛（天）：报价台账的治理提示窗口。
   *
   * <p>30 天是"够运营完成一轮询价与确认"的量级。它只是一个<b>提醒</b>口径
   * （{@code expiringSoonQuotes}），不是有效性判定 —— 判定只看 valid_to 与 at（规则见 V32 第五节）。
   */
  private static final int EXPIRY_WARNING_DAYS = 30;

  /**
   * 报价 + 供应商 + SKU + 采购单位名的联合查询。
   *
   * <p>{@code party} 与 {@code sku} 走<b>内连接</b>：它们都在 RLS 之下，
   * 删掉的主体与商品读不到（3.6.1.1 对 {@code party} 是软删除、{@code sku} 走状态），
   * 所以报价行在主体被软删之后自然不出现在台账里 —— 与 PartyRoleProfileService 同一口径。
   * {@code unit_of_measure} 走**左连接**：单位被停用时历史报价仍要显示（V32 第五节的取舍）。
   */
  private static final String BASE_FROM =
      " from supplier_quote q"
          + " join party_role r on r.tenant_id=q.tenant_id and r.id=q.supplier_role_id"
          + " join party p on p.tenant_id=r.tenant_id and p.id=r.party_id and p.deleted_at is null"
          + " join sku s on s.tenant_id=q.tenant_id and s.id=q.sku_id"
          + " left join unit_of_measure u on u.tenant_id=q.tenant_id and u.code=q.purchase_unit_code";

  private static final String BASE_COLUMNS =
      "q.id,q.tenant_id,q.supplier_role_id,q.sku_id,q.supplier_sku_code,q.purchase_unit_code,"
          + "q.currency,q.moq_quantity,q.lead_time_days,q.valid_from,q.valid_to,q.priority,"
          + "q.status,q.note,q.version,q.created_at,q.updated_at,"
          + "r.party_id as supplier_id,r.status as role_status,"
          + "p.code as supplier_code,p.name as supplier_name,"
          + "s.code as sku_code,s.name as sku_name,u.name as purchase_unit_name";

  private final DSLContext sql;

  public SupplierQuoteService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 概览

  /**
   * 报价概览：台账顶部的治理数字。
   *
   * <p>{@code ambiguousQuotes} 是本模块最该被盯住的数字：它是蓝图点名的那条规则的落地计数 ——
   * 「同一优先级多条有效报价」必须由人解决，而"有 N 条待解决"是唯一能让人去解决的信号。
   * 它正常应该为 0。
   */
  public SupplierQuoteOverview overview() {
    UUID tenantId = currentTenantId();
    long total = scalar("select count(*) from supplier_quote where tenant_id=?", tenantId);
    long effective =
        scalar(
            "select count(*) from supplier_quote where tenant_id=? and status='ACTIVE'"
                + " and valid_from<=current_date and (valid_to is null or valid_to>=current_date)",
            tenantId);
    long disabled =
        scalar(
            "select count(*) from supplier_quote where tenant_id=? and status='DISABLED'",
            tenantId);
    long ambiguous = ambiguousQuoteCount(tenantId, null);
    long suppliers =
        scalar("select count(distinct supplier_role_id) from supplier_quote where tenant_id=?", tenantId);
    long skus = scalar("select count(distinct sku_id) from supplier_quote where tenant_id=?", tenantId);
    long expiringSoon =
        scalar(
            "select count(*) from supplier_quote where tenant_id=? and status='ACTIVE'"
                + " and valid_to is not null and valid_to>=current_date"
                + " and valid_to<=current_date + ?",
            tenantId, EXPIRY_WARNING_DAYS);
    return new SupplierQuoteOverview(
        total,
        effective,
        ambiguous,
        disabled,
        suppliers,
        skus,
        expiringSoon,
        "有效报价 = 启用中 + 落在有效期内（闭区间，结束日当天仍然有效）。"
            + "ambiguousQuotes 是蓝图点名的那条规则的治理计数：同一优先级有多条有效报价时"
            + "系统不会替用户随机选，必须由人把其中一条的优先级调开，所以它正常应为 0。"
            + "expiringSoonQuotes 是 " + EXPIRY_WARNING_DAYS + " 天内到期的条数 —— 提前重新询价，"
            + "而不是等过期后才发现没价可下。");
  }

  // ================================================================ 词表选项

  /**
   * 词表选项：前端下拉的数据源。
   *
   * <p>与 {@link SupplierQuoteRules} 的词表同源（枚举只增不改这条承诺要在**一处**兑现）。
   * 币种与采购单位刻意不在这里给列表：那是 12 财务与 3.3.3 的资产，
   * 在本模块硬编码一份等于复制了别人的写入所有权（规则 **R3**）。
   */
  public SupplierQuoteOptions options() {
    return new SupplierQuoteOptions(
        statusOptions(),
        selectionStateOptions(),
        "币种与采购单位刻意不在这里给列表：ISO 4217 词表是 12 财务的资产、计量单位是 3.3.3 的资产，"
            + "在本模块硬编码一份就等于复制了别人的写入所有权，而两份列表迟早会不一样。"
            + "这两项走自由输入 + 服务层格式校验（币种校验 ISO 4217 三位码格式，"
            + "采购单位校验是否已在计量单位登记）。");
  }

  // ================================================================ 列表

  /**
   * 报价台账列表：分页 + 关键词 / SKU / 供应商 / 币种 / 状态 / 采购单位 /
   * 「只看有效」「只看需人工选择」筛选。
   *
   * <p>计数与取行引<b>同一处</b>筛选条件构造（{@link #conditions}），而不是各拼一遍 ——
   * 两处各拼一遍的症状是「页面说筛出 7 条，翻到第 2 页是空的」。
   *
   * <p>每行的 {@code selectionState} 在<b>同一个 sku 批次内</b>计算：
   * 优先级只在「同一个 SKU 的多条报价之间」才有可比性，
   * 所以一次列表查询返回多行时必须<b>按 sku 分组</b>再标状态，
   * 否则 A-SKU 的优先级 1 会把 B-SKU 的优先级 2 判成"备选"（跨 SKU 比优先级是错的）。
   */
  public SupplierQuotePage list(SupplierQuoteQuery query) {
    int page = query.page();
    int size = Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);
    LocalDate at = query.at() == null ? currentDate() : query.at();

    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    conditions(query, at, where, binds);
    String sqlWhere = where.isEmpty() ? "" : " and " + String.join(" and ", where);
    String from = BASE_FROM + " where q.tenant_id=?" + sqlWhere;
    List<Object> head = concat(List.<Object>of(currentTenantId()), binds);

    long total = sql.fetchOne("select count(*)" + from, head.toArray()).get(0, Long.class);
    var rows =
        sql.fetch(
            "select " + BASE_COLUMNS + from + " order by s.code,q.priority,q.valid_from desc,q.id"
                + " limit ? offset ?",
            concat(head, List.of(size, (long) page * size)).toArray());
    List<SupplierQuote> items = withSelectionStates(toQuotes(rows, at), at);
    // 「只看需人工选择」这条筛选依赖 selectionState，而它是算出来的而不是列 ——
    // 所以只能在装配之后过滤。计数同样要过这道闸，否则分页总数与实际行数对不上。
    if (Boolean.TRUE.equals(query.ambiguousOnly())) {
      List<SupplierQuote> kept = new ArrayList<>();
      for (SupplierQuote item : items)
        if ("AMBIGUOUS".equals(item.selectionState())) kept.add(item);
      // 歧义的定义与选择状态的分组口径**逐字同源**：同一个 (SKU, 供应商) 下
      // 最高优先级那一档上有 >1 条有效报价。少掉"供应商"这一维就会把
      // 「星尘的优先级 1」与「宏远的优先级 1」当成并列，那是两家供应商在竞价。
      long ambiguousTotal = ambiguousQuoteCount(currentTenantId(), at);
      return new SupplierQuotePage(
          kept, ambiguousTotal, page, size,
          "「只看需人工选择」是在装配之后过滤的（选择状态是算出来的，不是库里的列），"
              + "总数同口径统计。当前页的条数可能少于 size —— 翻页时请以总数为准。");
    }
    return new SupplierQuotePage(
        items, total, page, size,
        "计数与本页取行引同一处筛选条件，所以「筛出来的总数」与「翻页翻到底」一定对得上。"
            + "选择状态按 SKU 分组计算：优先级只在同一个 SKU 的多条报价之间才有可比性，"
            + "跨 SKU 比优先级是错的。");
  }

  /**
   * 每条查询都带的那一段：租户。
   *
   * <p>与筛选条件<b>分开</b>且放在最前面：租户是安全边界（RLS 之外的第二道闸），
   * 放进可变的 {@code where} 拼装里早晚会被一次重构挪到别处，而那种挪动不报错，
   * 只是隔离口径悄悄变了一条。与 PartyRoleProfileService 同一理由。
   */
  private void conditions(
      SupplierQuoteQuery query, LocalDate at, List<String> where, List<Object> binds) {
    if (query.keyword() != null && !query.keyword().isBlank()) {
      // 四列各绑一次：四个 OR 分支各自能用上索引。
      where.add(
          "(q.supplier_sku_code ilike ? escape '!' or p.code ilike ? escape '!'"
              + " or p.name ilike ? escape '!' or s.code ilike ? escape '!'"
              + " or s.name ilike ? escape '!')");
      String pattern = "%" + escapeLike(query.keyword().trim()) + "%";
      for (int i = 0; i < 5; i++) binds.add(pattern);
    }
    if (query.skuId() != null) {
      where.add("q.sku_id=?");
      binds.add(query.skuId());
    }
    if (query.supplierId() != null) {
      where.add("r.party_id=?");
      binds.add(query.supplierId());
    }
    if (query.currency() != null && !query.currency().isBlank()) {
      CatalogService.check(currencyProblem(query.currency()) == null, "币种请用 ISO 4217 三位代码");
      where.add("q.currency=?");
      binds.add(normalizeCurrency(query.currency()));
    }
    if (query.status() != null && !query.status().isBlank()) {
      String status = normalizeEnum(query.status());
      CatalogService.check(statusProblem(query.status()) == null, "报价状态不在词表里");
      where.add("q.status=?");
      binds.add(status);
    }
    if (query.purchaseUnitCode() != null && !query.purchaseUnitCode().isBlank()) {
      where.add("q.purchase_unit_code=?");
      binds.add(normalizeUnitCode(query.purchaseUnitCode()));
    }
    // 「只看有效」：三条与有效报价定义<b>逐字同源</b>（status / valid_from / valid_to）。
    if (Boolean.TRUE.equals(query.effectiveOnly())) {
      where.add("q.status='ACTIVE' and q.valid_from<=? and (q.valid_to is null or q.valid_to>=?)");
      binds.add(at);
      binds.add(at);
    }
  }

  // ================================================================ 详情

  /**
   * 一条报价的详情（带阶梯）。
   *
   * <p>不存在 → 404。这一条与 3.6.1.2 的档案详情不同：报价是<b>独立实体</b>（有自己的生命周期与
   * 版本），所以"没有这条报价"确实就是 404，不需要像档案那样用 {@code profileFound=false}
   * 去区分"没指派身份"与"没填档案"两种状态。
   */
  public SupplierQuote detail(UUID quoteId) {
    var row = sql.fetchOne("select " + BASE_COLUMNS + BASE_FROM + " where q.tenant_id=? and q.id=?",
        currentTenantId(), quoteId);
    if (row == null) throw CatalogService.missing();
    LocalDate at = currentDate();
    List<SupplierQuote> one = withSelectionStates(toQuotes(java.util.List.of(row), at), at);
    return one.get(0);
  }

  /**
   * 某条报价的阶梯明细（独立端点，供"编辑报价"面板单独刷新阶梯用）。
   */
  public List<SupplierQuotePriceTier> tiersOf(UUID quoteId) {
    detail(quoteId);
    return tiersOf(currentTenantId(), quoteId);
  }

  // ================================================================ 有效报价（蓝图点名的输出）

  /**
   * 有效报价解析：某个 SKU（可选限定某个供应商）在 {@code at} 那天的有效报价，
   * 以及**唯一**可自动采用的那一条（规则 <b>R2</b>）。
   *
   * <p>这就是蓝图写的「有效报价列表（默认取优先级最高且在有效期内的版本）」。
   * 「默认取」在本方法里<b>可以失败</b>：歧义时 {@code selected} 是 null、
   * {@code ambiguous=true}、{@code candidates} 给出并列的那几条 ——
   * <b>服务端任何一处都不替用户挑</b>。
   *
   * <p>不限定 {@code supplierId} 时（05 定价读全市场成本参考、13 利润看成本波动）：
   * 返回该 SKU 下全部有效报价，但 {@code selected} 仍然只在
   * 「<b>同一个供应商</b>内部比优先级」才有意义 —— 跨供应商比优先级是错的
   * （星尘的优先级 1 与宏远的优先级 1 之间没有可比性，那是两个供应商在竞价）。
   * 所以不限定供应商时 {@code selected} 恒为 null，{@code ambiguous} 恒为 false，
   * 只把候选与状态给出来。写清这一点是为了防止下游误读"selected 为 null = 没报价"。
   */
  public SupplierQuoteEffectiveResult effective(
      UUID skuId, UUID supplierId, LocalDate at, BigDecimal quantity) {
    CatalogService.check(skuId != null, "必须指定 SKU");
    LocalDate day = at == null ? currentDate() : at;
    var sku = skuRow(skuId);

    List<SupplierQuote> candidates;
    if (supplierId != null) {
      candidates = effectiveOf(skuId, supplierId, day);
    } else {
      candidates = effectiveOfSku(skuId, day);
    }

    List<String> warnings = new ArrayList<>();
    boolean ambiguous = false;
    String ambiguity = null;
    SupplierQuote selected = null;

    if (supplierId != null) {
      // 限定了供应商：优先级在这个供应商内部可比，可以选出唯一默认。
      List<Integer> priorities = candidates.stream().map(SupplierQuote::priority).toList();
      List<String> states = selectionStates(priorities);
      for (int i = 0; i < candidates.size(); i++)
        candidates.set(i, withState(candidates.get(i), states.get(i)));
      int best = priorities.stream().mapToInt(Integer::intValue).min().orElse(Integer.MAX_VALUE);
      int bestCount = (int) priorities.stream().filter(p -> p == best).count();
      ambiguity = ambiguityProblem(best, bestCount);
      ambiguous = ambiguity != null;
      if (!ambiguous && bestCount == 1)
        for (SupplierQuote q : candidates)
          if (q.priority() == best) selected = q;
    } else {
      // 不限定供应商：逐 (SKU, 供应商) 分组各自标状态（优先级只在供应商内部可比），
      // 但 selected 恒为 null —— 见本方法 javadoc 的说明。
      candidates = withSelectionStates(candidates, day);
    }

    if (candidates.isEmpty()) {
      warnings.add("该 SKU 在 " + day + " 没有有效报价（都已停用或不在有效期内），"
          + "06 采购单建单时会报错要求重新询价");
    }
    if (supplierId != null && ambiguous) {
      warnings.add("存在优先级并列的有效报价，必须由人指定用哪一条（规则 R2：系统不随机选）");
    }
    if (!candidates.isEmpty() && !profileExists(candidates.get(0).supplierRoleId())) {
      warnings.add("该供应商还没建角色档案（3.6.1.2），取不到账期与合作等级，"
          + "06 采购单需要人工补齐默认值");
    }

    SupplierQuotePriceTier selectedTier =
        selected == null || quantity == null
            ? null
            : applicableTierOf(selected, quantity);
    if (selected != null && quantity != null && selectedTier == null) {
      String unit =
          selected.purchaseUnitName() == null ? selected.purchaseUnitCode() : selected.purchaseUnitName();
      warnings.add("数量 " + quantityLabel(quantity) + " " + unit
          + " 低于最低档起订量（" + quantityLabel(selected.moqQuantity()) + "），"
          + "没有适用阶梯价 —— 请与供应商确认该数量的价格");
    }

    return new SupplierQuoteEffectiveResult(
        skuId,
        sku.get("code", String.class),
        sku.get("name", String.class),
        supplierId,
        supplierId == null ? null : supplierName(supplierId),
        day,
        selected,
        selectedTier,
        candidates,
        ambiguous,
        ambiguity,
        warnings,
        "candidates 是该时刻的全部有效报价，逐条带 selectionState："
            + "SELECTED = 唯一默认采用、AMBIGUOUS = 与另一条并列最高优先级（需人工选择）、"
            + "LOWER_PRIORITY = 有效备选。"
            + "同优先级多条有效报价时 selected 恒为 null（规则 R2：绝不随机取一条）；"
            + "优先级只在同一个供应商内部可比，所以不限定供应商时 selected 恒为 null。");
  }

  /**
   * 严格取价出口：{@code GET /catalog/supplier-quotes/effective/resolve}。
   *
   * <p>06 采购单带出价格走这里。它与 {@link #effective} 的差别只有一个：
   * <b>歧义时抛 409 而不是返回 ambiguous=true</b>。理由是"报错让用户选"必须发生在
   * 建单的那一刻 —— 采购单不能带着一条"系统随便选的价"发出去，而一个 200 + null
   * 的响应很容易被前端当成"没报价"（症状是提示文案答非所问，而真正的原因是歧义）。
   *
   * <p>409 的 body 里带上候选清单的 id 列表（{@code body} 由
   * {@link ApiErrorHandler} 渲染），运营据此去改优先级。
   */
  public SupplierQuoteEffectiveResult resolve(
      UUID skuId, UUID supplierId, LocalDate at, BigDecimal quantity) {
    SupplierQuoteEffectiveResult result = effective(skuId, supplierId, at, quantity);
    if (result.ambiguous()) {
      throw new SupplierQuoteAmbiguityException(result.ambiguityProblem(), result);
    }
    if (result.selected() == null) {
      CatalogService.check(supplierId != null, "严格取价必须指定供应商");
      throw CatalogService.missing();
    }
    return result;
  }

  // ================================================================ 写入

  /**
   * 新建报价（规则 <b>R1</b>~<b>R5</b>）。
   *
   * <p>{@code supplierId} 是主体 id，服务层从它解析 SUPPLIER 角色 ——
   * 前端手上只有主体（页面上点的是某个供应商），而"这条报价属于供应商身份"这层判断
   * 不能交给客户端（它完全可能传来一个 CUSTOMER 角色的 id）。
   */
  public SupplierQuote create(AccessService.Access actor, SupplierQuoteInput input) {
    Validated validated = validate(input, null);
    UUID tenantId = actor.tenantId();
    UUID quoteId = Ids.next();
    sql.execute(
        "insert into supplier_quote(id,tenant_id,supplier_role_id,sku_id,supplier_sku_code,"
            + "purchase_unit_code,currency,moq_quantity,lead_time_days,valid_from,valid_to,"
            + "priority,status,note) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        quoteId,
        tenantId,
        validated.roleId(),
        input.skuId(),
        validated.supplierSkuCode(),
        validated.purchaseUnit(),
        validated.currency(),
        validated.moq(),
        validated.leadTimeDays(),
        Date.valueOf(validated.validFrom()),
        validated.validTo() == null ? null : Date.valueOf(validated.validTo()),
        validated.priority(),
        validated.status(),
        validated.note());
    replaceTiers(tenantId, quoteId, validated.tiers());
    audit(actor, quoteId, "SupplierQuoteCreated");
    return detail(quoteId);
  }

  /**
   * 改报价（乐观锁 + 阶梯整体替换）。
   *
   * <p>更新分支与插入分支<b>分开写</b>而不是 {@code on conflict} upsert：
   * 乐观锁要区分「这一行还不存在（version 只能是 0）」与「这一行存在（version 必须相等）」，
   * 而 upsert 只有一个出口 —— 它会把并发的两个"都以为自己在新建"的请求静默合并，
   * 后到的那条覆盖先到的那条而没有人收到  409。与 PartyRoleProfileService 同理由。
   *
   * <p>阶梯整体替换：先删后插，而不是 {@code on conflict} 逐档 upsert ——
   * "删掉一档"在 upsert 里无法表达，而合并写入会让阶梯越改越多。
   */
  public SupplierQuote update(AccessService.Access actor, UUID quoteId, SupplierQuoteInput input) {
    var existing =
        sql.fetchOne("select version,supplier_role_id from supplier_quote where tenant_id=? and id=?",
            currentTenantId(), quoteId);
    if (existing == null) throw CatalogService.missing();
    long version = existing.get("version", Long.class);
    if (version != input.version())
      throw conflict("报价已被他人修改，请刷新后重试");

    Validated validated = validate(input, quoteId);
    UUID tenantId = actor.tenantId();
    int updated =
        sql.execute(
            "update supplier_quote set supplier_sku_code=?,purchase_unit_code=?,currency=?,"
                + "moq_quantity=?,lead_time_days=?,valid_from=?,valid_to=?,priority=?,status=?,"
                + "note=?,version=version+1,updated_at=now()"
                + " where tenant_id=? and id=? and version=?",
            validated.supplierSkuCode(),
            validated.purchaseUnit(),
            validated.currency(),
            validated.moq(),
            validated.leadTimeDays(),
            Date.valueOf(validated.validFrom()),
            validated.validTo() == null ? null : Date.valueOf(validated.validTo()),
            validated.priority(),
            validated.status(),
            validated.note(),
            tenantId,
            quoteId,
            version);
    if (updated != 1) throw conflict("报价已被他人修改，请刷新后重试");
    replaceTiers(tenantId, quoteId, validated.tiers());
    audit(actor, quoteId, "SupplierQuoteUpdated");
    return detail(quoteId);
  }

  /**
   * 删掉一条报价（规则 <b>R6</b>）。
   *
   * <p>阶梯由 {@code ON DELETE CASCADE} 跟着删（V32 第六节）——
   * 留下孤儿阶梯时，按量取价的查询读不到它而单独读阶梯表的查询读得到，
   * 两种读法给出<b>不一致的价格</b>，那是最难查的一类错。
   *
   * <p>删掉之后 06 采购退回"该 SKU 无有效报价"（建单时报错要求重新询价），
   * 这是一个有意义且可逆的状态。已发生的采购单带的是自己的冻结快照，不读本表。
   */
  public void delete(AccessService.Access actor, UUID quoteId, long version) {
    var existing =
        sql.fetchOne("select version from supplier_quote where tenant_id=? and id=?",
            currentTenantId(), quoteId);
    if (existing == null) throw CatalogService.missing();
    if (existing.get("version", Long.class) != version)
      throw conflict("报价已被他人修改，请刷新后重试");
    sql.execute("delete from supplier_quote where tenant_id=? and id=? and version=?",
        currentTenantId(), quoteId, version);
    audit(actor, quoteId, "SupplierQuoteDeleted");
  }

  // ================================================================ 写入校验与公共路径

  /** 校验后的入参（规范化后的形态）。 */
  private record Validated(
      UUID roleId,
      String supplierSkuCode,
      String purchaseUnit,
      String currency,
      BigDecimal moq,
      int leadTimeDays,
      LocalDate validFrom,
      LocalDate validTo,
      int priority,
      String status,
      String note,
      List<Tier> tiers) {}

  /** 校验后的一档阶梯。 */
  private record Tier(BigDecimal minQuantity, BigDecimal unitPrice, String note) {}

  /**
   * 保存前的全部判定（规则 <b>R1</b>~<b>R5</b>）。
   *
   * <p>顺序是刻意的：<b>纯格式判定先做，碰库的判定后做</b>。理由是纯规则不查库，
   * 运营一次提交里填错三项时先看到三条格式错误，比先跑一趟库再报第一条要快；
   * 更重要的是纯规则与库无关，可以离线单测穷举（见 {@link SupplierQuoteRules}）。
   *
   * <p>{@code tiers} 已按 {@code minQuantity} 升序排好并判过重复 ——
   * 档位顺序是写入前确定的，不靠 DB 排序保证（V32 第三节）。
   */
  private Validated validate(SupplierQuoteInput input, UUID quoteId) {
    CatalogService.check(input.skuId() != null, "必须指定 SKU");
    // SKU 必须在本租户可见 —— 先判存在再写。
    // 少了这一句，跨租户 SKU 会一路走到 INSERT 才被 supplier_quote 的复合外键挡下，
    // 而外键违例被 ApiErrorHandler 渲染成 409「编码或规格组合重复，或记录仍被引用」，
    // 那句文案与"你填了一个别的租户的 SKU"毫无关系，运营会照着去查重复编码。
    skuRow(input.skuId());

    String supplierCode = normalizeText(input.supplierSkuCode());
    String unit = normalizeUnitCode(input.purchaseUnitCode());
    String currency = normalizeCurrency(input.currency());
    BigDecimal moq = normalizeQuantity(input.moqQuantity());
    LocalDate from = input.validFrom();
    LocalDate to = input.validTo();
    int priority = normalizePriority(input.priority());
    String status = normalizeStatus(input.status());
    String note = normalizeText(input.note());

    List<Tier> tiers = new ArrayList<>();
    for (SupplierQuotePriceTierInput tier : input.tiers()) {
      CatalogService.check(
          tierProblem(tier.minQuantity(), tier.unitPrice()) == null,
          firstNonNull(
              tierProblem(tier.minQuantity(), tier.unitPrice()),
              "第 " + (tiers.size() + 1) + " 档阶梯不合法"));
      tiers.add(
          new Tier(
              normalizeQuantity(tier.minQuantity()),
              normalizePrice(tier.unitPrice()),
              normalizeText(tier.note())));
    }
    // 排序后判严格递增（规则 R5）。先排序再判，这样"顺序填反了"也能救回来 ——
    // 运营按金额从小到大录档是很自然的事，而报错让他自己重排没有任何价值。
    tiers.sort(java.util.Comparator.comparing(Tier::minQuantity));
    List<BigDecimal> mins = tiers.stream().map(Tier::minQuantity).toList();

    String problem =
        firstNonNull(
            supplierSkuCodeProblem(input.supplierSkuCode()),
            purchaseUnitProblem(input.purchaseUnitCode()),
            currencyProblem(input.currency()),
            moqProblem(input.moqQuantity()),
            leadTimeProblem(input.leadTimeDays()),
            validityProblem(from, to),
            priorityProblem(input.priority()),
            statusProblem(input.status()),
            tiersProblem(mins, tiers.size()));
    CatalogService.check(problem == null, problem);
    CatalogService.check(note.length() <= MAX_NOTE_LENGTH, "备注最多 " + MAX_NOTE_LENGTH + " 个字符");
    for (Tier tier : tiers)
      CatalogService.check(tier.note().length() <= MAX_TIER_NOTE_LENGTH,
          "阶梯备注最多 " + MAX_TIER_NOTE_LENGTH + " 个字符");

    // 采购单位必须已登记 / 启用 / 允许采购（规则 R3 的单位侧）。
    // 同样要**先判后写**：少了这一句，未登记的单位会一路走到 INSERT 才被外键挡下，
    // 而外键违例的文案不会告诉运营"请先在 3.3.3 登记这个单位"。
    // quoteId 为 null（新建）时没有"已写着的单位"，所以第二个参数传 null。
    checkPurchaseUnit(unit, quoteId == null ? null : currentPurchaseUnit(quoteId));

    return new Validated(
        requireSupplierRole(input.supplierId()),
        supplierCode,
        unit,
        currency,
        moq,
        normalizeLeadTime(input.leadTimeDays()),
        from,
        to,
        priority,
        status,
        note,
        tiers);
  }

  /**
   * 采购单位的写入前置检查：必须已登记、必须启用、必须允许采购。
   *
   * <p><b>已经写着的那个单位可以继续留着</b>（哪怕它后来被停用）：
   * 否则停用一个单位之后，所有正在用它报价的 SKU 都会变成改不动的东西
   * —— 改一个备注都保存不了，而运营完全看不出原因。与 PartyRoleProfileService 同取舍。
   *
   * <p>量纲不可换算这一条由 3.3.3 的 V17 触发器兜底，本类不重复。
   */
  private void checkPurchaseUnit(String unit, String storedUnit) {
    var row =
        sql.fetchOne(
            "select code,enabled,allow_purchase from unit_of_measure where tenant_id=? and code=?",
            currentTenantId(), unit);
    if (row == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "采购单位「" + unit + "」没有在计量单位里登记（3.3.3），请先登记该单位再录报价");
    if (unit.equals(storedUnit)) return;
    if (!Boolean.TRUE.equals(row.get("enabled", Boolean.class)))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "采购单位「" + unit + "」已停用，换一个启用的单位；"
              + "停用只影响「新建」，已经用它报的历史报价照常保留");
    if (!Boolean.TRUE.equals(row.get("allow_purchase", Boolean.class)))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "计量单位「" + unit + "」不允许用于采购（allow_purchase=false），换一个允许采购的单位");
  }

  /** 该报价当前存的采购单位（改档时"已写着的单位可以留着"的判定用）。 */
  private String currentPurchaseUnit(UUID quoteId) {
    var row =
        sql.fetchOne("select purchase_unit_code from supplier_quote where tenant_id=? and id=?",
            currentTenantId(), quoteId);
    return row == null ? null : row.get("purchase_unit_code", String.class);
  }

  /**
   * 取某个主体的 SUPPLIER 角色 id；没有就给出可读的 409（规则 <b>R1</b>）。
   *
   * <p>文案是「先指派供应商身份」而不是「报价不存在」：运营录报价时如果他还没指派供应商身份，
   * 他要做的是回到上面去指派身份，而不是以为自己已经指派过了。
   */
  private UUID requireSupplierRole(UUID partyId) {
    var row =
        sql.fetchOne(
            "select id from party_role where tenant_id=? and party_id=? and role_type=?",
            currentTenantId(), partyId, ROLE_SUPPLIER);
    if (row == null) throw conflict(supplierRoleMissingProblem(partyId));
    return row.get("id", UUID.class);
  }

  /** 「这个主体还没被指派供应商身份」的可读文案，并把它现在已指派的身份列出来。 */
  private String supplierRoleMissingProblem(UUID partyId) {
    var party =
        sql.fetchOne("select code,name from party where tenant_id=? and id=? and deleted_at is null",
            currentTenantId(), partyId);
    if (party == null) throw CatalogService.missing();
    List<String> assigned = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select role_type from party_role where tenant_id=? and party_id=? order by role_type",
            currentTenantId(), partyId))
      assigned.add(PartyRules.roleLabel(r.get("role_type", String.class)));
    String who = party.get("code", String.class) + "（" + party.get("name", String.class) + "）";
    return assigned.isEmpty()
        ? "主体 " + who + " 还没有被指派任何身份。请先在 3.6.1.1 给它指派「供应商」身份，再录报价 —— "
            + "报价挂在供应商身份上（V32 第五节），挂在主体上的话，同一家既是供应商又是客户时，"
            + "它的客户身份会把供应商报价一起混进来。"
        : "主体 " + who + " 已指派的身份是「" + String.join(" / ", assigned)
            + "」，还没有「供应商」身份。请先在 3.6.1.1 指派供应商身份，再录报价。";
  }

  /** 阶梯整体替换：先删后插（规则 **R5**）。 */
  private void replaceTiers(UUID tenantId, UUID quoteId, List<Tier> tiers) {
    sql.execute("delete from supplier_quote_price_tier where tenant_id=? and quote_id=?",
        tenantId, quoteId);
    for (Tier tier : tiers)
      sql.execute(
          "insert into supplier_quote_price_tier(tenant_id,quote_id,min_quantity,unit_price,note)"
              + " values(?,?,?,?,?)",
          tenantId, quoteId, tier.minQuantity(), tier.unitPrice(), tier.note());
  }

  private boolean profileExists(UUID roleId) {
    return sql.fetchOne("select 1 from party_supplier_profile where tenant_id=? and role_id=?",
            currentTenantId(), roleId) != null;
  }

  private Record skuRow(UUID skuId) {
    var row =
        sql.fetchOne("select id,code,name from sku where tenant_id=? and id=?", currentTenantId(), skuId);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  private String supplierName(UUID partyId) {
    var row = sql.fetchOne("select name from party where tenant_id=? and id=?", currentTenantId(), partyId);
    return row == null ? null : row.get("name", String.class);
  }

  // ================================================================ 有效报价查询

  /**
   * 某个 SKU 在某个供应商下的有效报价，按优先级升序（越优先越靠前）。
   *
   * <p>有效条件与 {@link #overview} 里 {@code ambiguousQuotes} 的子查询、
   * 与列表的 {@code effectiveOnly} 筛选<b>逐字同源</b>：status='ACTIVE'
   * + {@code valid_from<=at} + （{@code valid_to} 为空或 {@code >=at}）。三处不同源的症状是
   * 「台账说 2 条有效、列表筛出 3 条、歧义数是 1」。
   */
  private List<SupplierQuote> effectiveOf(UUID skuId, UUID supplierId, LocalDate at) {
    var rows =
        sql.fetch(
            "select " + BASE_COLUMNS + BASE_FROM
                + " where q.tenant_id=? and q.sku_id=? and r.party_id=? and q.status='ACTIVE'"
                + " and q.valid_from<=? and (q.valid_to is null or q.valid_to>=?)"
                + " order by q.priority,q.valid_from desc,q.id",
            currentTenantId(), skuId, supplierId, Date.valueOf(at), Date.valueOf(at));
    return toQuotes(rows, at);
  }

  /** 某个 SKU 下全部供应商的有效报价（05 定价 / 13 利润用）。 */
  private List<SupplierQuote> effectiveOfSku(UUID skuId, LocalDate at) {
    var rows =
        sql.fetch(
            "select " + BASE_COLUMNS + BASE_FROM
                + " where q.tenant_id=? and q.sku_id=? and q.status='ACTIVE'"
                + " and q.valid_from<=? and (q.valid_to is null or q.valid_to>=?)"
                + " order by p.code,q.priority,q.valid_from desc,q.id",
            currentTenantId(), skuId, Date.valueOf(at), Date.valueOf(at));
    return toQuotes(rows, at);
  }

  // ================================================================ 装配

  /**
   * 把一行装配成 {@link SupplierQuote}，阶梯另查一次。
   *
   * <p>阶梯按 {@code min_quantity} 升序取（与写入时的排序同一口径）。
   */
  private SupplierQuote toQuote(Record r, LocalDate at) {
    UUID tenantId = r.get("tenant_id", UUID.class);
    UUID quoteId = r.get("id", UUID.class);
    LocalDate from = toLocalDate(r.get("valid_from", LocalDate.class));
    LocalDate to = toLocalDate(r.get("valid_to", LocalDate.class));
    int leadTime = intOf(r.get("lead_time_days", Integer.class));
    String status = r.get("status", String.class);
    int priority = intOf(r.get("priority", Integer.class));
    BigDecimal moq = r.get("moq_quantity", BigDecimal.class);
    String unitName = emptyToNull(r.get("purchase_unit_name", String.class));
    String unitCode = r.get("purchase_unit_code", String.class);
    boolean effective =
        "ACTIVE".equals(status) && withinValidity(at, from, to);
    return new SupplierQuote(
        quoteId,
        r.get("supplier_role_id", UUID.class),
        r.get("supplier_id", UUID.class),
        r.get("supplier_code", String.class),
        r.get("supplier_name", String.class),
        r.get("role_status", String.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("supplier_sku_code", String.class),
        unitCode,
        unitName,
        r.get("currency", String.class),
        moq,
        moq == null ? null : "起订 " + quantityLabel(moq) + " " + (unitName == null ? unitCode : unitName),
        leadTime,
        leadTimeLabel(leadTime),
        from,
        to,
        validityLabel(from, to),
        to == null ? Long.MAX_VALUE : daysUntilExpiry(at, to),
        priority,
        priorityLabel(priority),
        status,
        statusLabel(status),
        // selectionState 由 withSelectionStates 统一覆盖（它需要同 SKU 的批次上下文）。
        effective ? "LOWER_PRIORITY" : null,
        effective ? selectionStateLabel("LOWER_PRIORITY") : null,
        effective,
        tiersOf(tenantId, quoteId),
        emptyToNull(r.get("note", String.class)),
        longOf(r.get("version", Long.class)),
        warningsFor(r, effective, at, to),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  private List<SupplierQuote> toQuotes(java.util.Collection<? extends Record> rows, LocalDate at) {
    List<SupplierQuote> list = new ArrayList<>(rows.size());
    for (Record r : rows) list.add(toQuote(r, at));
    return list;
  }

  /**
   * 按 SKU 分组标 {@code selectionState}（规则 <b>R2</b>）。
   *
   * <p>分组是必须的：优先级只在<b>同一个 SKU 的同一个供应商</b>内部可比，
   * 跨供应商比优先级是错的（星尘的优先级 1 与宏远的优先级 1 之间没有可比性）。
   *
   * <p>不在有效报价集合里的行（停用 / 已过期）拿到 {@code null} ——
   * 它们的 {@code effective=false}，前端据此不参与"需人工选择"的筛选。
   */
  private List<SupplierQuote> withSelectionStates(List<SupplierQuote> quotes, LocalDate at) {
    List<SupplierQuote> out = new ArrayList<>(quotes.size());
    java.util.Map<String, List<Integer>> byGroup = new java.util.LinkedHashMap<>();
    // 先按 (sku, supplier) 分组收集有效报价的优先级
    List<SupplierQuote> effective = new ArrayList<>();
    for (SupplierQuote q : quotes)
      if (q.effective()) {
        effective.add(q);
        byGroup.computeIfAbsent(q.skuId() + "|" + q.supplierId(), k -> new ArrayList<>())
            .add(q.priority());
      }
    java.util.Map<SupplierQuote, String> stateById = new java.util.IdentityHashMap<>();
    for (var entry : byGroup.entrySet()) {
      List<String> states = selectionStates(entry.getValue());
      int i = 0;
      for (SupplierQuote q : effective)
        if ((q.skuId() + "|" + q.supplierId()).equals(entry.getKey()))
          stateById.put(q, states.get(i++));
    }
    for (SupplierQuote q : quotes)
      out.add(q.effective() ? withState(q, stateById.get(q)) : q);
    return out;
  }

  private SupplierQuote withState(SupplierQuote q, String state) {
    return new SupplierQuote(
        q.id(), q.supplierRoleId(), q.supplierId(), q.supplierCode(), q.supplierName(),
        q.supplierRoleStatus(), q.skuId(), q.skuCode(), q.skuName(), q.supplierSkuCode(),
        q.purchaseUnitCode(), q.purchaseUnitName(), q.currency(), q.moqQuantity(), q.moqLabel(),
        q.leadTimeDays(), q.leadTimeLabel(), q.validFrom(), q.validTo(), q.validityLabel(),
        q.daysToExpiry(), q.priority(), q.priorityLabel(), q.status(), q.statusLabel(),
        state, selectionStateLabel(state), q.effective(), q.tiers(), q.note(), q.version(),
        q.warnings(), q.createdAt(), q.updatedAt());
  }

  /** 阶梯明细（按起订量升序，与写入时的排序同一口径）。 */
  private List<SupplierQuotePriceTier> tiersOf(UUID tenantId, UUID quoteId) {
    List<SupplierQuotePriceTier> tiers = new ArrayList<>();
    var rows =
        sql.fetch(
            "select min_quantity,unit_price,note from supplier_quote_price_tier"
                + " where tenant_id=? and quote_id=? order by min_quantity",
            tenantId, quoteId);
    for (Record r : rows) {
      BigDecimal min = r.get("min_quantity", BigDecimal.class);
      BigDecimal price = r.get("unit_price", BigDecimal.class);
      tiers.add(new SupplierQuotePriceTier(min, price, tierRangeLabel(tiers.size(), min, price),
          emptyToNull(r.get("note", String.class))));
    }
    return tiers;
  }

  /** 档位展示串：第 1 档（≥ 1）、第 2 档（≥ 100）。阶梯是开口的，最后一档无上限（V32 第三节）。 */
  private String tierRangeLabel(int index, BigDecimal min, BigDecimal price) {
    return "第 " + (index + 1) + " 档（≥ " + quantityLabel(min) + "）："
        + price.stripTrailingZeros().toPlainString();
  }

  /** 按数量在一条报价的阶梯里取适用档（null = 低于第一档，见 {@link SupplierQuoteRules#applicableTier}）。 */
  private SupplierQuotePriceTier applicableTierOf(SupplierQuote quote, BigDecimal quantity) {
    List<BigDecimal> mins = quote.tiers().stream().map(SupplierQuotePriceTier::minQuantity).toList();
    return applicableTier(mins, quote.tiers(), quantity);
  }

  /**
   * 读出口的提示（不是校验错误）。
   *
   * <p>与 {@code PartyRoleProfileService.warnings} 同一纪律：这类信息在页面上是提醒，
   * 在 06 建单时是"别按这条提示处理"。三处读出口共用它，不让下游各判一次。
   */
  private List<String> warningsFor(Record r, boolean effective, LocalDate at, LocalDate to) {
    List<String> warnings = new ArrayList<>();
    if (!effective && to != null && to.isBefore(at))
      warnings.add("该报价已于 " + to + " 过期，不参与有效报价判定");
    if (!effective && "DISABLED".equals(r.get("status", String.class)))
      warnings.add("该报价已停用，不参与有效报价判定");
    if (to != null) {
      long days = daysUntilExpiry(at, to);
      if (effective && days >= 0 && days <= EXPIRY_WARNING_DAYS)
        warnings.add("该报价还有 " + days + " 天到期（" + to + "），请提前重新询价");
    }
    if (to == null && effective)
      warnings.add("该报价没有设置失效日期（长期有效），请确认这是协议价而不是漏填");
    return warnings;
  }

  // ================================================================ 小工具

  /** 当前租户从 RLS 生效的连接上下文取（本服务的每个查询都在 {@code AccessService.run} 包裹里）。 */
  private UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  /** 库当前日期（有效报价判定与台账筛选的缺省「今天」）。 */
  private LocalDate currentDate() {
    return sql.fetchOne("select current_date").get(0, LocalDate.class);
  }

  private long scalar(String statement, Object... binds) {
    return sql.fetchOne(statement, binds).get(0, Long.class);
  }

  /**
   * 「需要人工选择」的有效报价条数（规则 <b>R2</b> 的治理计数）。
   *
   * <p>口径：同一个 (供应商角色, SKU, 优先级) 上有<b>多于一条</b>有效报价，
   * 则该组里的<b>每一条</b>都算"需人工选择"。
   *
   * <p>两个容易数错的地方：
   *
   * <ul>
   *   <li><b>分组要带 supplier_role_id</b>。少掉这一维就会把「星尘的优先级 1」与
   *       「宏远的优先级 1」当成并列 —— 那是两家供应商在竞价，不是歧义。
   *   <li><b>数的是报价条数而不是分组数</b>。三条并列时分组数是 1，而需要人处理的是 3 条；
   *       用 {@code count(*) from (…group by … having count(*)>1)} 会报 1，
   *       运营看到"1"会以为只有一处要处理，然后漏掉另外两条。
   * </ul>
   *
   * <p>所以这里数"落在歧义组里的行"而不是"歧义组"本身。
   *
   * @param at 判定日；null 表示用库当前日期（概览走这个，列表传显式 at）
   */
  private long ambiguousQuoteCount(UUID tenantId, LocalDate at) {
    String dayExpr = at == null ? "current_date" : "?";
    // 有效条件与 effectiveOf / effectiveOnly 逐字同源（status / valid_from / valid_to）。
    String valid =
        " where tenant_id=? and status='ACTIVE' and valid_from<="
            + dayExpr
            + " and (valid_to is null or valid_to>="
            + dayExpr
            + ")";
    // 绑定顺序必须与占位符出现的顺序**逐字对应**：外层租户 → 外层日期 → 内层租户 → 内层日期。
    // 写反了不会编译报错，只会在运行时报"未设定参数值 N 的内容"（而 N 指向的那个参数
    // 恰好是另一个合法值的那种错位最难查）。
    List<Object> binds = new ArrayList<>();
    binds.add(tenantId);
    if (at != null) binds.add(Date.valueOf(at));
    if (at != null) binds.add(Date.valueOf(at));
    binds.add(tenantId);
    if (at != null) binds.add(Date.valueOf(at));
    if (at != null) binds.add(Date.valueOf(at));
    // 刻意不叫 sql：那个名字是本类的 DSLContext 字段，局部变量同名会让下面的
    // sql.fetchOne 解析到 String 上（编译不过，而报错信息指向的却是"找不到 fetchOne"）。
    String statement =
        "select count(*) from supplier_quote q where q.tenant_id=? and q.status='ACTIVE'"
            + " and q.valid_from<="
            + dayExpr
            + " and (q.valid_to is null or q.valid_to>="
            + dayExpr
            + ")"
            + " and (q.supplier_role_id,q.sku_id,q.priority) in ("
            + " select supplier_role_id,sku_id,priority from supplier_quote"
            + valid
            + " group by supplier_role_id,sku_id,priority having count(*)>1)";
    return sql.fetchOne(statement, binds.toArray()).get(0, Long.class);
  }

  private static LocalDate toLocalDate(Object value) {
    if (value == null) return null;
    if (value instanceof LocalDate localDate) return localDate;
    if (value instanceof java.sql.Date sqlDate) return sqlDate.toLocalDate();
    return null;
  }

  private static int intOf(Integer value) {
    return value == null ? 0 : value;
  }

  private static long longOf(Long value) {
    return value == null ? 0L : value;
  }

  private static String emptyToNull(String value) {
    if (value == null) return null;
    String text = value.trim();
    return text.isEmpty() ? null : text;
  }

  /**
   * LIKE 模式里的通配符转义。
   *
   * <p>与 {@code escape '!'} 成对：不转义的话运营搜 {@code 100%} 会变成「匹配所有」，
   * 而界面上看不出来 —— 那一页返回几百条看起来还挺像对的。与 PartyService 同一份。
   */
  private static String escapeLike(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  private static List<Object> concat(List<Object> first, List<Object> second) {
    var all = new ArrayList<Object>(first.size() + second.size());
    all.addAll(first);
    all.addAll(second);
    return all;
  }

  private void audit(AccessService.Access actor, UUID resourceId, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(), actor.tenantId(), actor.userId(), resourceId, action);
  }

  /** {@code timestamptz} 读出口径：统一转成 UTC 的 ISO-8601 串（带 {@code Z}），不交给前端猜时区。 */
  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  /**
   * 歧义的 409（规则 **R2**）。
   *
   * <p>单独一个异常类型而不是直接抛 {@link ResponseStatusException}：
   * {@link ApiErrorHandler} 需要在 body 里附上候选清单的 id，运营才知道该去改哪几条的优先级
   * —— 「报错让用户选」的后半句是"让用户选"，而选不了就等于只报错。
   *
   * <p>刻意继承 {@link RuntimeException} 而不是 {@link ResponseStatusException}：
   * 与本类族里另外两个结构化异常（{@code VariantCombinationService.CombinationConflictException}、
   * {@code SkuAttributeOverrideService.RequiredMissingException}）一致，
   * 状态码由 {@code ApiErrorHandler} 统一决定，不在抛出点各写一遍。
   */
  static final class SupplierQuoteAmbiguityException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient SupplierQuoteEffectiveResult result;

    SupplierQuoteAmbiguityException(String reason, SupplierQuoteEffectiveResult result) {
      super(reason);
      this.result = result;
    }

    /** 并列候选清单（由 {@link ApiErrorHandler} 渲染成 409 ProblemDetail 的 candidates 属性）。 */
    SupplierQuoteEffectiveResult result() {
      return result;
    }
  }
}
