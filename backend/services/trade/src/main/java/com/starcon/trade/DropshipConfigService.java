package com.starcon.trade;

import static com.starcon.trade.DropshipConfigModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 9.6.1.1 代发模式配置：店铺/SKU 的代发标记、供应商、结算方式、可售校验与刊登策略。
 *
 * <p><b>五条实现纪律</b>（照抄 9.3.2.1 的写法，不另起风格）：
 *
 * <ol>
 *   <li><b>判定只有一处</b>：{@link #evaluate} 是唯一的可售判定实现； 页面预览与落库结论不一致时，运营看到的和系统做的是两件事。
 *   <li><b>物流与货权互不推导</b>：本服务**不**计算「是否占我方库存」—— {@code counts_against_own_inventory} / {@code
 *       pure_agency_exempt} 是 DB 生成列（V118 第 3 节），这里只读出来如实报给 9.6.1.2。
 *       在服务层再写一遍等价判断，就等于有了第二份可能与服务层漂移的真相。
 *   <li><b>金额只有一处口径</b>：差价抽成全程 {@code BigDecimal}， {@code expectedMarginAmount} 等三个金额取自 DB
 *       生成列，服务层不另算。
 *   <li><b>时间绑定</b>：{@code ?::timestamptz} 写在 values 的对应位置上， cast 不出现在绑定序列末位。
 *   <li><b>不用 JPA 懒加载</b>：全部 jOOQ 显式 SQL。
 * </ol>
 *
 * <p><b>跨服务一律不读表</b>：2.3.1 店铺（channel）、3.6.2 供应商报价（catalog）、 7.1.1.3 可售量（warehouse）分属三个独立数据库，按
 * AGENTS.md 禁止跨库读取。 本服务自维护三张带 {@code source} 标注的只读投影/观测表（理由见 V118 文件头第二节）。
 */
@Service
public class DropshipConfigService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 数量刻度：与 DB 的 numeric(19,6) 对齐。 */
  private static final int QTY_SCALE = 6;

  /** 金额刻度：与 DB 的 numeric(19,4) 对齐。 */
  private static final int MONEY_SCALE = 4;

  /** 比例刻度：与 DB 的 numeric(9,6) 对齐。 */
  private static final int RATE_SCALE = 6;

  private static final BigDecimal ZERO_QTY = new BigDecimal("0.000000");
  private static final BigDecimal ZERO_MONEY = new BigDecimal("0.0000");
  private static final BigDecimal ZERO_RATE = new BigDecimal("0.000000");
  private static final BigDecimal ONE = BigDecimal.ONE;

  private static final String CONFIG_COLUMNS =
      "id,config_code,config_name,scope_kind,shop_code,sku_ref,fulfillment_path,supplier_code,"
          + "ownership_mode,counts_against_own_inventory,pure_agency_exempt,settlement_timing,"
          + "settlement_basis,margin_share_rate,currency_code,supplier_cost,reference_sell_price,"
          + "gross_spread,expected_margin_amount,expected_supplier_amount,availability_check_mode,"
          + "min_supplier_stock_buffer,listing_policy,priority,status,effective_from,effective_to,"
          + "ownership_confirmed_at,ownership_confirmed_note,ownership_reviewed_with,note,version,"
          + "created_at,updated_at";

  private static final String LINE_COLUMNS =
      "id,config_id,sku_ref,sku_name,supplier_cost_override,reference_sell_price_override,"
          + "active,note,created_at,updated_at";

  private static final String EVAL_COLUMNS =
      "id,config_id,config_line_id,sku_ref,shop_code,supplier_code,requested_qty,required_qty,"
          + "supplier_available_qty,sellable,listable,reason_code,reason_detail,settlement_basis,"
          + "expected_margin_amount,counts_against_own_inventory,evaluated_at";

  private final DSLContext sql;

  public DropshipConfigService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上游可用性。
   *
   * <p>{@code crossServiceReadAvailable=false} 与 {@code reservesOwnInventory=false}
   * 都是如实报出：三个上游分属三个独立数据库，本模块只维护带来源标注的投影； 而「纯代理不占我方库存」是**判定结论**不是预占动作 —— 真正预占要调 warehouse 的授权
   * API，属下游 9.6.1.2 职责。现在假装预占，两边会同时以为占了同一批货。
   */
  public DropshipCapabilities capabilities() {
    return new DropshipCapabilities(
        List.of(
            new DropshipCapabilities.DropshipCapabilityDimension(
                "SHOP",
                "店铺（2.3.1）",
                true,
                "2.3.1 店铺（channel 服务）→ 本服务只读投影 fulfillment_dropship_shop_ref",
                null),
            new DropshipCapabilities.DropshipCapabilityDimension(
                "SUPPLIER_QUOTE",
                "供应商报价（3.6.2）",
                true,
                "3.6.2 供应商报价（catalog 服务）→ 本服务只读投影 " + "fulfillment_dropship_supplier_ref",
                null),
            new DropshipCapabilities.DropshipCapabilityDimension(
                "SUPPLIER_STOCK",
                "供应商可供货量",
                true,
                "7.1.1.3 可用量（warehouse 服务）→ 本服务观测表 " + "fulfillment_dropship_supplier_stock",
                null),
            new DropshipCapabilities.DropshipCapabilityDimension(
                "OWN_STOCK",
                "我方自有库存",
                false,
                "7.1.1.3 可用量（warehouse 服务）",
                "蓝图明写「代发**不查**自有库存」：代发要问的是供应商能不能供货，"
                    + "不是我有几个。因此本模块不去取我方可售量，也**不预占**我方库存 —— "
                    + "是否占我方库存由配置里的货权模式判定（见 ownership 维度）"),
            new DropshipCapabilities.DropshipCapabilityDimension(
                "ORDER_HANDOFF",
                "下单给供应商（9.6.1.2）",
                false,
                "9.6.1.2 代发单与供应商下单（未开发）",
                "本模块只产出可售标记，不向供应商下单。供应商涨价或无货的告警"
                    + "在 9.6.1.2 落地；本层能提前拦的是「差价为负」与「供应商库存不足」，"
                    + "两者都会让 listable 落 false")),
        List.of(DropshipScopeKind.values()),
        List.of(DropshipFulfillmentPath.values()),
        List.of(DropshipOwnershipMode.values()),
        List.of(DropshipSettlementTiming.values()),
        List.of(DropshipSettlementBasis.values()),
        List.of(DropshipAvailabilityCheckMode.values()),
        List.of(DropshipListingPolicy.values()),
        List.of(DropshipConfigStatus.values()),
        List.of(DropshipSellReasonCode.values()),
        false,
        false,
        "2.3.1 店铺（channel）、3.6.2 供应商报价（catalog）、7.1.1.3 可用量（warehouse）分属三个"
            + "独立数据库，按模块边界纪律不得跨库读取；本模块把三者登记为带 source 标注的真实表"
            + "（shop_ref / supplier_ref / supplier_stock）。将来同步器按同一形状往这三张表写、"
            + "把 source 从 TENANT_MAINTAINED 改成 MODULE_* 即可，本模块的表结构与判定逻辑不变。",
        "物流路径与货权**分开判断**，这是本模块唯一不可让步的一条。"
            + "fulfillmentPath 回答「谁发货」，ownershipMode 回答「结算前货算谁的」，两者互不推导。"
            + "只有「供应商直发 + 纯代理」这一种组合不占用自有仓库存（pureAgencyExempt=true）；"
            + "「供应商直发 + 我方货权」（VMI 型）仍然占用，且货权与结算必须单独确认过 —— "
            + "这两个结论都是 DB 生成列，服务层改不了，也不另算第二份。",
        "可售（sellable）与可刊登（listable）是**两列**：不可售不必然要下架。"
            + "「没有供应商库存记录」是可售=null 的缺口，「供应商确实没货」是 0，"
            + "两者在读模型里分开报出，前端不得画成同一个 0。");
  }

  // ================================================================ 店铺投影

  /** 店铺投影列表（真分页）。 */
  public DropshipShopRefPage pageShopRefs(DropshipShopRefQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (notBlank(query.shopCode())) {
      where.add("shop_code = ?");
      binds.add(query.shopCode().trim());
    }
    if (notBlank(query.platformCode())) {
      where.add("platform_code = ?");
      binds.add(query.platformCode().trim().toUpperCase(Locale.ROOT));
    }
    if (Boolean.TRUE.equals(query.activeOnly())) {
      where.add("active");
    }
    if (notBlank(query.keyword())) {
      where.add("(shop_code ilike ? or shop_name ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      binds.add(k);
      binds.add(k);
    }
    String sqlWhere = whereClause(where);
    long total =
        sql.fetchOne(
                "select count(*) c from fulfillment_dropship_shop_ref" + sqlWhere, binds.toArray())
            .get("c", Long.class);
    List<DropshipShopRefRow> items =
        sql.fetch(
                "select id,shop_code,shop_name,platform_code,region_code,active,source_system,"
                    + "synced_at,updated_at from fulfillment_dropship_shop_ref"
                    + sqlWhere
                    + " order by shop_code limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(DropshipConfigService::shopRefRow);
    return new DropshipShopRefPage(items, total, pageNo, size);
  }

  /** 登记 / 更新一个店铺投影。 */
  @Transactional
  public DropshipShopRefSaved saveShopRef(DropshipShopRefInput input) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    String source = shopSource(input.sourceSystem()).name();
    boolean active = input.active() == null || input.active();
    OffsetDateTime now = OffsetDateTime.now();
    var existing =
        sql.fetchOne(
            "select id from fulfillment_dropship_shop_ref where shop_code = ?",
            input.shopCode().trim());
    boolean created = existing == null;
    UUID id = created ? UUID.randomUUID() : existing.get(0, UUID.class);
    if (created) {
      sql.execute(
          "insert into fulfillment_dropship_shop_ref(id,tenant_id,shop_code,shop_name,"
              + "platform_code,region_code,active,source_system,synced_at,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          input.shopCode().trim(),
          input.shopName().trim(),
          input.platformCode().trim().toUpperCase(Locale.ROOT),
          blankTo(input.regionCode(), "*"),
          active,
          source,
          input.syncedAt(),
          now,
          now);
    } else {
      sql.execute(
          "update fulfillment_dropship_shop_ref set shop_name = ?, platform_code = ?,"
              + " region_code = ?, active = ?, source_system = ?, synced_at = ?::timestamptz,"
              + " updated_at = ?::timestamptz where id = ?",
          input.shopName().trim(),
          input.platformCode().trim().toUpperCase(Locale.ROOT),
          blankTo(input.regionCode(), "*"),
          active,
          source,
          input.syncedAt(),
          now,
          id);
    }
    return new DropshipShopRefSaved(id, input.shopCode().trim(), created);
  }

  // ================================================================ 供应商报价投影

  /** 供应商报价投影列表（真分页）。 */
  public DropshipSupplierRefPage pageSupplierRefs(DropshipSupplierRefQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (notBlank(query.supplierCode())) {
      where.add("supplier_code = ?");
      binds.add(query.supplierCode().trim());
    }
    if (notBlank(query.quoteSource())) {
      where.add("quote_source = ?");
      binds.add(supplierSource(query.quoteSource()).name());
    }
    if (Boolean.TRUE.equals(query.activeOnly())) {
      where.add("active");
    }
    if (Boolean.TRUE.equals(query.quoteValidOnly())) {
      where.add("quote_valid_to is null or quote_valid_to >= current_date");
    }
    if (notBlank(query.keyword())) {
      where.add("(supplier_code ilike ? or supplier_name ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      binds.add(k);
      binds.add(k);
    }
    String sqlWhere = whereClause(where);
    long total =
        sql.fetchOne(
                "select count(*) c from fulfillment_dropship_supplier_ref" + sqlWhere,
                binds.toArray())
            .get("c", Long.class);
    List<DropshipSupplierRefRow> items =
        sql.fetch(
                "select id,supplier_code,supplier_name,currency_code,quote_valid_from,"
                    + "quote_valid_to,quote_source,lead_time_days,active,synced_at,updated_at"
                    + " from fulfillment_dropship_supplier_ref"
                    + sqlWhere
                    + " order by supplier_code limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(DropshipConfigService::supplierRefRow);
    return new DropshipSupplierRefPage(items, total, pageNo, size);
  }

  /** 登记 / 更新一个供应商报价投影。 */
  @Transactional
  public DropshipSupplierRefSaved saveSupplierRef(DropshipSupplierRefInput input) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    String source = supplierSource(input.quoteSource()).name();
    boolean active = input.active() == null || input.active();
    BigDecimal lead =
        input.leadTimeDays() == null
            ? null
            : input.leadTimeDays().setScale(2, RoundingMode.HALF_UP);
    if (lead != null && lead.signum() < 0) {
      throw bad("供应商备货时效不能为负");
    }
    if (input.quoteValidFrom() != null
        && input.quoteValidTo() != null
        && input.quoteValidTo().isBefore(input.quoteValidFrom())) {
      throw bad("报价失效日期不能早于生效日期");
    }
    OffsetDateTime now = OffsetDateTime.now();
    var existing =
        sql.fetchOne(
            "select id from fulfillment_dropship_supplier_ref where supplier_code = ?",
            input.supplierCode().trim());
    boolean created = existing == null;
    UUID id = created ? UUID.randomUUID() : existing.get(0, UUID.class);
    if (created) {
      sql.execute(
          "insert into fulfillment_dropship_supplier_ref(id,tenant_id,supplier_code,"
              + "supplier_name,currency_code,quote_valid_from,quote_valid_to,quote_source,"
              + "lead_time_days,active,synced_at,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          input.supplierCode().trim(),
          input.supplierName().trim(),
          input.currencyCode().trim().toUpperCase(Locale.ROOT),
          input.quoteValidFrom(),
          input.quoteValidTo(),
          source,
          lead,
          active,
          input.syncedAt(),
          now,
          now);
    } else {
      sql.execute(
          "update fulfillment_dropship_supplier_ref set supplier_name = ?, currency_code = ?,"
              + " quote_valid_from = ?, quote_valid_to = ?, quote_source = ?, lead_time_days = ?,"
              + " active = ?, synced_at = ?::timestamptz, updated_at = ?::timestamptz where id = ?",
          input.supplierName().trim(),
          input.currencyCode().trim().toUpperCase(Locale.ROOT),
          input.quoteValidFrom(),
          input.quoteValidTo(),
          source,
          lead,
          active,
          input.syncedAt(),
          now,
          id);
    }
    return new DropshipSupplierRefSaved(id, input.supplierCode().trim(), created);
  }

  // ================================================================ 代发配置

  /** 配置列表（真分页）。{@code countsAgainstOwnInventory=true} 时只回「占我方库存」的那些。 */
  public DropshipConfigPage pageConfigs(DropshipConfigQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (notBlank(query.configCode())) {
      where.add("config_code ilike ?");
      binds.add("%" + query.configCode().trim() + "%");
    }
    if (notBlank(query.scopeKind())) {
      where.add("scope_kind = ?");
      binds.add(scopeKind(query.scopeKind()).name());
    }
    if (notBlank(query.shopCode())) {
      where.add("shop_code = ?");
      binds.add(query.shopCode().trim());
    }
    if (notBlank(query.skuRef())) {
      where.add(
          "(sku_ref = ? or exists (select 1 from fulfillment_dropship_config_line l"
              + " where l.tenant_id = fulfillment_dropship_config.tenant_id"
              + " and l.config_id = fulfillment_dropship_config.id and l.sku_ref = ?))");
      binds.add(query.skuRef().trim());
      binds.add(query.skuRef().trim());
    }
    if (notBlank(query.fulfillmentPath())) {
      where.add("fulfillment_path = ?");
      binds.add(fulfillmentPath(query.fulfillmentPath()).name());
    }
    if (notBlank(query.ownershipMode())) {
      where.add("ownership_mode = ?");
      binds.add(ownershipMode(query.ownershipMode()).name());
    }
    if (notBlank(query.settlementBasis())) {
      where.add("settlement_basis = ?");
      binds.add(settlementBasis(query.settlementBasis()).name());
    }
    if (notBlank(query.status())) {
      where.add("status = ?");
      binds.add(configStatus(query.status()).name());
    }
    if (Boolean.TRUE.equals(query.effectiveOnly())) {
      where.add(
          "status = 'ACTIVE' and effective_from <= now()"
              + " and (effective_to is null or effective_to > now())");
    }
    if (query.countsAgainstOwnInventory() != null) {
      where.add("counts_against_own_inventory = ?");
      binds.add(query.countsAgainstOwnInventory());
    }
    if (notBlank(query.keyword())) {
      where.add(
          "(config_code ilike ? or config_name ilike ? or note ilike ?"
              + " or supplier_code ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      for (int i = 0; i < 4; i++) {
        binds.add(k);
      }
    }
    String sqlWhere = whereClause(where);
    long total =
        sql.fetchOne(
                "select count(*) c from fulfillment_dropship_config" + sqlWhere, binds.toArray())
            .get("c", Long.class);
    List<DropshipConfigRow> items =
        sql.fetch(
                "select "
                    + CONFIG_COLUMNS
                    + " from fulfillment_dropship_config"
                    + sqlWhere
                    + " order by priority, config_code limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(DropshipConfigService::configRow);
    return new DropshipConfigPage(items, total, pageNo, size);
  }

  /** 配置详情：配置头 + 明细行 + 最近事件。跨租户在 RLS 下查不到 → 404。 */
  public DropshipConfigDetail configDetail(UUID configId) {
    var row =
        sql.fetchOne(
            "select " + CONFIG_COLUMNS + " from fulfillment_dropship_config where id = ?",
            configId);
    if (row == null) {
      throw notFound("代发配置不存在或不属于当前租户");
    }
    return new DropshipConfigDetail(
        configRow(row),
        configLines(configId),
        eventRows(configId, 50),
        "本模块不预占我方库存：countsAgainstOwnInventory 只是货权判定结论，"
            + "真正预占要等 9.6.1.2 代发单去调 warehouse 的授权 API。");
  }

  /** 明细行（同一配置的 SKU 覆盖价）。 */
  public List<DropshipConfigLineRow> configLines(UUID configId) {
    return sql.fetch(
            "select "
                + LINE_COLUMNS
                + " from fulfillment_dropship_config_line"
                + " where config_id = ? order by sku_ref",
            configId)
        .map(DropshipConfigService::lineRow);
  }

  /**
   * 维护一条代发配置。
   *
   * <p>金额与比例一律 {@code BigDecimal}；货权与物流两个维度分别校验， 「纯代理免确认 / 非纯代理须确认」的判据在 DB 的 CHECK + 触发器两层再各自复验一次。
   */
  @Transactional
  public DropshipConfigSaved saveConfig(DropshipConfigInput input, UUID membershipId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    DropshipScopeKind scope = scopeKind(input.scopeKind());
    String shopCode = input.shopCode() == null ? "" : input.shopCode().trim();
    String skuRef = input.skuRef() == null ? "" : input.skuRef().trim();
    if (scope == DropshipScopeKind.SHOP && shopCode.isBlank()) {
      throw bad("适用范围选了「按店铺」，必须给出 shopCode");
    }
    if (scope == DropshipScopeKind.SKU && skuRef.isBlank()) {
      throw bad("适用范围选了「按 SKU」，必须给出 skuRef");
    }

    DropshipFulfillmentPath path = fulfillmentPath(input.fulfillmentPath());
    DropshipOwnershipMode ownership = ownershipMode(input.ownershipMode());
    String supplierCode = input.supplierCode() == null ? "" : input.supplierCode().trim();
    // 物流维度：自营发货不该挂供应商；供应商直发必须有供应商。
    if (path == DropshipFulfillmentPath.SUPPLIER_DIRECT && supplierCode.isBlank()) {
      throw bad("履约路径是「供应商直发」，必须给出代发供应商 supplierCode，否则「谁直发」无从谈起");
    }
    if (path == DropshipFulfillmentPath.SELF_FULFILLED && !supplierCode.isBlank()) {
      throw bad("履约路径是「自营发货」，不该挂代发供应商：货是我方仓发的，" + "把供应商挂在上面会让货权判断出现第三个来源");
    }
    // 货权维度：自营发货不可能是纯代理（DB CHECK 也会拒，这里先给人话）。
    if (path == DropshipFulfillmentPath.SELF_FULFILLED
        && ownership == DropshipOwnershipMode.PURE_AGENCY) {
      throw bad("自营发货不可能是「纯代理」：货从我方仓出、我方发货，货权在我方是前提。" + "货权请选「我方货权」");
    }
    // 非纯代理必须「单独确认」：三个字段缺一不可。
    OffsetDateTime confirmedAt = input.ownershipConfirmedAt();
    String confirmedNote =
        input.ownershipConfirmedNote() == null ? "" : input.ownershipConfirmedNote().trim();
    if (ownership == DropshipOwnershipMode.OUR_ACCOUNT) {
      if (confirmedAt == null) {
        throw bad(
            "货权在「我方」时必须给出 ownershipConfirmedAt：供应商直发但货权属我方（VMI 型）"
                + "必须单独确认货权与结算，不能因为「不经我方仓」就默认不记我方库存");
      }
      if (input.ownershipConfirmedByMembershipId() == null) {
        throw bad("货权在「我方」时必须给出确认人 ownershipConfirmedByMembershipId");
      }
      if (confirmedNote.isBlank()) {
        throw bad("货权在「我方」时必须写明确认依据 ownershipConfirmedNote：" + "与 7.7 货权规则联合评审的结论是什么，留空等于没评审过");
      }
      if (notBlank(input.ownershipReviewedWith())
          && input.ownershipReviewedWith().trim().isEmpty()) {
        throw bad("联合评审对象不能是空串（不评审就留空，不要写成空白）");
      }
    } else if (confirmedAt != null || !confirmedNote.isEmpty()) {
      throw bad("纯代理模式不需要「单独确认」货权，也不该带确认时间与确认说明：" + "带上会让人误以为它也被复核过");
    }

    // 结算维度
    settlementTiming(input.settlementTiming());
    DropshipSettlementBasis basis = settlementBasis(input.settlementBasis());
    BigDecimal rate;
    if (basis == DropshipSettlementBasis.MARGIN_SHARE) {
      if (input.marginShareRate() == null) {
        throw bad("结算方式是「差价抽成」，必须给出 marginShareRate（我方留存的差价比例）");
      }
      rate = input.marginShareRate().setScale(RATE_SCALE, RoundingMode.HALF_UP);
      if (rate.signum() < 0 || rate.compareTo(ONE) > 0) {
        throw bad("差价抽成比例必须落在 0 到 1 之间，收到 " + input.marginShareRate());
      }
    } else {
      if (input.marginShareRate() != null) {
        throw bad("结算方式是「固定供货价」，marginShareRate 必须留空：" + "留着它会让人以为会抽差价");
      }
      rate = null;
    }
    String currency = input.currencyCode().trim().toUpperCase(Locale.ROOT);
    if (currency.length() != 3) {
      throw bad("币种必须是三位大写字母，例如 USD / CNY");
    }
    BigDecimal cost = money(input.supplierCost(), "supplierCost");
    if (cost.signum() < 0) {
      throw bad("供货价不能为负");
    }
    BigDecimal sellPrice = money(input.referenceSellPrice(), "referenceSellPrice");
    if (sellPrice.signum() <= 0) {
      throw bad("参考售价必须大于 0：没有售价就无从判断差价是否转负");
    }

    // 可售校验维度
    DropshipAvailabilityCheckMode check = availabilityCheckMode(input.availabilityCheckMode());
    BigDecimal buffer;
    if (check == DropshipAvailabilityCheckMode.SUPPLIER_STOCK) {
      if (input.minSupplierStockBuffer() == null) {
        throw bad("可售校验选了「按供应商库存校验」，必须给出 minSupplierStockBuffer 安全余量，" + "否则「够不够卖」没有判定线");
      }
      buffer = qty(input.minSupplierStockBuffer());
      if (buffer.signum() < 0) {
        throw bad("供应商库存安全余量不能为负");
      }
    } else {
      if (input.minSupplierStockBuffer() != null) {
        throw bad("可售校验不是「按供应商库存校验」时，minSupplierStockBuffer 必须留空：" + "留着它会让人以为仍按供应商库存判定");
      }
      buffer = null;
    }

    DropshipListingPolicy listing = listingPolicy(input.listingPolicy());
    int priority = input.priority() == null ? 100 : input.priority();
    if (priority < 0) {
      throw bad("优先级不能为负（数字小 = 优先级高）");
    }
    OffsetDateTime from = input.effectiveFrom();
    OffsetDateTime to = input.effectiveTo();
    if (to != null && from != null && !to.isAfter(from)) {
      throw bad("失效时间必须晚于生效时间");
    }

    OffsetDateTime now = OffsetDateTime.now();
    var existing =
        sql.fetchOne(
            "select id from fulfillment_dropship_config where config_code = ?",
            input.configCode().trim());
    boolean created = existing == null;
    UUID id = created ? UUID.randomUUID() : existing.get(0, UUID.class);
    String note = nz(input.note());
    if (created) {
      sql.execute(
          "insert into fulfillment_dropship_config(id,tenant_id,config_code,config_name,"
              + "scope_kind,shop_code,sku_ref,fulfillment_path,supplier_code,ownership_mode,"
              + "ownership_confirmed_at,ownership_confirmed_by_membership_id,"
              + "ownership_confirmed_note,ownership_reviewed_with,settlement_timing,"
              + "settlement_basis,margin_share_rate,currency_code,supplier_cost,"
              + "reference_sell_price,availability_check_mode,min_supplier_stock_buffer,"
              + "listing_policy,priority,status,effective_from,effective_to,note,version,"
              + "created_by_membership_id,created_at,updated_at)"
              // 30 个占位符 + 2 个字面量（'DRAFT' / 1）= 32 列，顺序与上面的列清单逐位对应。
              + " values (?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?,?,?,?,?,?,?,?,?,?,?,"
              + "'DRAFT',?::timestamptz,?::timestamptz,?,1,?,?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          input.configCode().trim(),
          input.configName().trim(),
          scope.name(),
          shopCode,
          skuRef,
          path.name(),
          supplierCode,
          ownership.name(),
          confirmedAt,
          input.ownershipConfirmedByMembershipId(),
          confirmedNote,
          input.ownershipReviewedWith() == null ? "" : input.ownershipReviewedWith().trim(),
          settlementTiming(input.settlementTiming()).name(),
          basis.name(),
          rate,
          currency,
          cost,
          sellPrice,
          check.name(),
          buffer,
          listing.name(),
          priority,
          from,
          to,
          note,
          membershipId,
          now,
          now);
      event(
          id,
          null,
          "CREATED",
          "",
          "DRAFT",
          "创建代发配置（" + path.name() + " / " + ownership.name() + "）",
          membershipId);
    } else {
      // 更新：DB 触发器 dropship_ownership_clear_on_change 会在货权/结算维度变动时
      // 把确认清空，随后 dropship_ownership_confirmed 会拒绝未重新确认的写入 ——
      // 「改完配置忘了重新确认」在这里被数据库挡住，服务层不必重复这套逻辑。
      sql.execute(
          "update fulfillment_dropship_config set config_name = ?, scope_kind = ?, shop_code = ?,"
              + " sku_ref = ?, fulfillment_path = ?, supplier_code = ?, ownership_mode = ?,"
              + " ownership_confirmed_at = ?::timestamptz,"
              + " ownership_confirmed_by_membership_id = ?, ownership_confirmed_note = ?,"
              + " ownership_reviewed_with = ?, settlement_timing = ?, settlement_basis = ?,"
              + " margin_share_rate = ?, currency_code = ?, supplier_cost = ?,"
              + " reference_sell_price = ?, availability_check_mode = ?,"
              + " min_supplier_stock_buffer = ?, listing_policy = ?, priority = ?,"
              + " effective_from = ?::timestamptz, effective_to = ?::timestamptz, note = ?,"
              + " version = version + 1, updated_at = ?::timestamptz where id = ?",
          input.configName().trim(),
          scope.name(),
          shopCode,
          skuRef,
          path.name(),
          supplierCode,
          ownership.name(),
          confirmedAt,
          input.ownershipConfirmedByMembershipId(),
          confirmedNote,
          input.ownershipReviewedWith() == null ? "" : input.ownershipReviewedWith().trim(),
          settlementTiming(input.settlementTiming()).name(),
          basis.name(),
          rate,
          currency,
          cost,
          sellPrice,
          check.name(),
          buffer,
          listing.name(),
          priority,
          from,
          to,
          note,
          now,
          id);
      event(id, null, "UPDATED", "", "", "更新代发配置", membershipId);
    }
    return new DropshipConfigSaved(id, input.configCode().trim(), created);
  }

  /**
   * 推进配置状态：DRAFT → ACTIVE → SUSPENDED ↔ ACTIVE → CLOSED。
   *
   * <p>激活时补齐 {@code effective_from}（没有生效期的配置激活了也没意义）， 并要求至少一条明细行 —— 空配置激活等于给一个范围开了代发却没有 SKU。
   */
  @Transactional
  public DropshipConfigSaved advanceStatus(
      UUID configId, DropshipConfigStatusInput input, UUID membershipId) {
    if (input == null || !notBlank(input.status())) {
      throw bad("请给出目标状态");
    }
    DropshipConfigStatus target = configStatus(input.status());
    var row =
        sql.fetchOne(
            "select " + CONFIG_COLUMNS + " from fulfillment_dropship_config where id = ?",
            configId);
    if (row == null) {
      throw notFound("代发配置不存在或不属于当前租户");
    }
    DropshipConfigStatus current = DropshipConfigStatus.valueOf(row.get("status", String.class));
    if (current == target) {
      throw bad("配置已经是 " + current.label() + " 了，无需重复推进");
    }
    if (target == DropshipConfigStatus.ACTIVE) {
      long lines =
          sql.fetchOne(
                  "select count(*) c from fulfillment_dropship_config_line where config_id = ?",
                  configId)
              .get("c", Long.class);
      if (lines == 0) {
        throw bad("配置还没有任何 SKU 明细行：空配置激活等于给一个范围开了代发却没有可代发的 SKU，" + "下游 9.6.1.2 会拿到一个永远不可售的配置");
      }
    }
    OffsetDateTime now = OffsetDateTime.now();
    String note = nz(input.note());
    if (target == DropshipConfigStatus.ACTIVE) {
      // 没给生效期就用现在，否则激活后 effective_from 为空，永远不在「生效」集合里。
      sql.execute(
          "update fulfillment_dropship_config set status = 'ACTIVE',"
              + " effective_from = coalesce(effective_from, ?::timestamptz),"
              + " activated_at = ?::timestamptz, activated_by_membership_id = ?,"
              + " updated_at = ?::timestamptz where id = ?",
          now,
          now,
          membershipId,
          now,
          configId);
    } else if (target == DropshipConfigStatus.CLOSED) {
      sql.execute(
          "update fulfillment_dropship_config set status = 'CLOSED', closed_at = ?::timestamptz,"
              + " closed_by_membership_id = ?, updated_at = ?::timestamptz where id = ?",
          now,
          membershipId,
          now,
          configId);
    } else {
      sql.execute(
          "update fulfillment_dropship_config set status = ?, updated_at = ?::timestamptz"
              + " where id = ?",
          target.name(),
          now,
          configId);
    }
    event(configId, null, "STATUS_CHANGED", current.name(), target.name(), note, membershipId);
    return new DropshipConfigSaved(configId, row.get("config_code", String.class), false);
  }

  // ================================================================ 明细行

  /** 维护一条 SKU 明细行（可覆盖供货价 / 参考售价）。 */
  @Transactional
  public DropshipConfigLineSaved saveConfigLine(UUID configId, DropshipConfigLineInput input) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    var config =
        sql.fetchOne(
            "select config_code,status from fulfillment_dropship_config where id = ?", configId);
    if (config == null) {
      throw notFound("代发配置不存在或不属于当前租户");
    }
    if (config.get("status", String.class).equals("CLOSED")) {
      throw bad("配置已关闭，其明细行已冻结：货已按最终结算走完，账不能再动");
    }
    BigDecimal costOverride =
        input.supplierCostOverride() == null
            ? null
            : money(input.supplierCostOverride(), "supplierCostOverride");
    if (costOverride != null && costOverride.signum() < 0) {
      throw bad("供货价覆盖不能为负");
    }
    BigDecimal sellOverride =
        input.referenceSellPriceOverride() == null
            ? null
            : money(input.referenceSellPriceOverride(), "referenceSellPriceOverride");
    if (sellOverride != null && sellOverride.signum() <= 0) {
      throw bad("参考售价覆盖必须大于 0");
    }
    boolean active = input.active() == null || input.active();
    OffsetDateTime now = OffsetDateTime.now();
    var existing =
        sql.fetchOne(
            "select id from fulfillment_dropship_config_line where config_id = ? and sku_ref = ?",
            configId,
            input.skuRef().trim());
    boolean created = existing == null;
    UUID id = created ? UUID.randomUUID() : existing.get(0, UUID.class);
    if (created) {
      sql.execute(
          "insert into fulfillment_dropship_config_line(id,tenant_id,config_id,sku_ref,"
              + "sku_name,supplier_cost_override,reference_sell_price_override,active,note,"
              + "created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          configId,
          input.skuRef().trim(),
          nz(input.skuName()),
          costOverride,
          sellOverride,
          active,
          nz(input.note()),
          now,
          now);
    } else {
      sql.execute(
          "update fulfillment_dropship_config_line set sku_name = ?, supplier_cost_override = ?,"
              + " reference_sell_price_override = ?, active = ?, note = ?,"
              + " updated_at = ?::timestamptz where id = ?",
          nz(input.skuName()),
          costOverride,
          sellOverride,
          active,
          nz(input.note()),
          now,
          id);
    }
    return new DropshipConfigLineSaved(id, configId, input.skuRef().trim(), created);
  }

  // ================================================================ 供应商库存观测

  /** 供应商可供货量列表（真分页）。 */
  public DropshipSupplierStockPage pageSupplierStocks(DropshipSupplierStockQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (notBlank(query.supplierCode())) {
      where.add("supplier_code = ?");
      binds.add(query.supplierCode().trim());
    }
    if (notBlank(query.skuRef())) {
      where.add("sku_ref = ?");
      binds.add(query.skuRef().trim());
    }
    if (notBlank(query.stockSource())) {
      where.add("stock_source = ?");
      binds.add(stockSource(query.stockSource()).name());
    }
    if (notBlank(query.keyword())) {
      where.add("(supplier_code ilike ? or sku_ref ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      binds.add(k);
      binds.add(k);
    }
    String sqlWhere = whereClause(where);
    long total =
        sql.fetchOne(
                "select count(*) c from fulfillment_dropship_supplier_stock" + sqlWhere,
                binds.toArray())
            .get("c", Long.class);
    List<DropshipSupplierStockRow> items =
        sql.fetch(
                "select id,supplier_code,sku_ref,available_qty,stock_source,observed_at,updated_at"
                    + " from fulfillment_dropship_supplier_stock"
                    + sqlWhere
                    + " order by supplier_code, sku_ref limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(DropshipConfigService::stockRow);
    return new DropshipSupplierStockPage(items, total, pageNo, size);
  }

  /** 登记 / 更新一个供应商可供货量观测值（同一供应商 + SKU 只保留一条当前值）。 */
  @Transactional
  public DropshipSupplierStockSaved saveSupplierStock(DropshipSupplierStockInput input) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    BigDecimal qty = qty(input.availableQty());
    if (qty.signum() < 0) {
      throw bad("供应商可供货量不能为负");
    }
    String source = stockSource(input.stockSource()).name();
    OffsetDateTime now = OffsetDateTime.now();
    var existing =
        sql.fetchOne(
            "select id from fulfillment_dropship_supplier_stock"
                + " where supplier_code = ? and sku_ref = ?",
            input.supplierCode().trim(),
            input.skuRef().trim());
    boolean created = existing == null;
    UUID id = created ? UUID.randomUUID() : existing.get(0, UUID.class);
    if (created) {
      sql.execute(
          "insert into fulfillment_dropship_supplier_stock(id,tenant_id,supplier_code,sku_ref,"
              + "available_qty,stock_source,observed_at,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          input.supplierCode().trim(),
          input.skuRef().trim(),
          qty,
          source,
          input.observedAt(),
          now,
          now);
    } else {
      sql.execute(
          "update fulfillment_dropship_supplier_stock set available_qty = ?, stock_source = ?,"
              + " observed_at = ?::timestamptz, updated_at = ?::timestamptz where id = ?",
          qty,
          source,
          input.observedAt(),
          now,
          id);
    }
    return new DropshipSupplierStockSaved(
        id, input.supplierCode().trim(), input.skuRef().trim(), created);
  }

  // ================================================================ 可售标记（输出之二）

  /**
   * 可售标记列表（真分页）。
   *
   * <p>{@code sellable=false} 且 {@code supplierAvailableQty=null} = 「没人报过供应商库存」； {@code
   * sellable=false} 且 {@code supplierAvailableQty=0} = 「供应商确实没货」。两者必须能分开看。
   */
  public DropshipEvaluationPage pageEvaluations(DropshipEvaluationQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (query.configId() != null) {
      where.add("config_id = ?");
      binds.add(query.configId());
    }
    if (notBlank(query.skuRef())) {
      where.add("sku_ref = ?");
      binds.add(query.skuRef().trim());
    }
    if (notBlank(query.shopCode())) {
      where.add("shop_code = ?");
      binds.add(query.shopCode().trim());
    }
    if (notBlank(query.supplierCode())) {
      where.add("supplier_code = ?");
      binds.add(query.supplierCode().trim());
    }
    if (query.sellable() != null) {
      where.add("sellable = ?");
      binds.add(query.sellable());
    }
    if (query.listable() != null) {
      where.add("listable = ?");
      binds.add(query.listable());
    }
    if (notBlank(query.reasonCode())) {
      where.add("reason_code = ?");
      binds.add(sellReason(query.reasonCode()).name());
    }
    String sqlWhere = whereClause(where);
    long total =
        sql.fetchOne(
                "select count(*) c from fulfillment_dropship_evaluation" + sqlWhere,
                binds.toArray())
            .get("c", Long.class);
    List<DropshipEvaluationRow> items =
        sql.fetch(
                "select "
                    + EVAL_COLUMNS
                    + " from fulfillment_dropship_evaluation"
                    + sqlWhere
                    + " order by evaluated_at desc, id limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(DropshipConfigService::evaluationRow);
    return new DropshipEvaluationPage(items, total, pageNo, size);
  }

  /**
   * 评估一次代发可售性并落一条不可改写的判定凭据。
   *
   * <p><b>判定顺序</b>（每一步都给出「缺什么」，绝不静默通过）：
   *
   * <ol>
   *   <li>配置未生效 → CONFIG_NOT_ACTIVE；不在有效期内 → CONFIG_NOT_EFFECTIVE
   *   <li>明细行缺失或停用 → LINE_INACTIVE
   *   <li>按店铺生效时店铺已停用 → SHOP_INACTIVE
   *   <li>供应商报价过期 → QUOTE_EXPIRED
   *   <li>差价转负 → NEGATIVE_MARGIN（蓝图下游的「静默亏损」闸门）
   *   <li>按供应商库存校验时：无记录 → NO_SUPPLIER_STOCK_RECORD，不够 → SUPPLIER_STOCK_INSUFFICIENT
   * </ol>
   *
   * <p>{@code sellable} 与 {@code listable} 分开判定：不可售不必然下架， {@code listingPolicy} 决定这二者怎么组合。
   *
   * <p>金额取自 DB 生成列（按明细行覆盖价重算的等价口径在服务层只做一次， 落库的 {@code expected_margin_amount} 与配置头生成列同源，见 {@link
   * #marginOf}）。
   */
  @Transactional
  public DropshipEvaluationSaved evaluate(DropshipEvaluationRequest request) {
    if (request == null) {
      throw bad("请求体不能为空");
    }
    BigDecimal requestedQty = qty(request.requestedQty());
    if (requestedQty.signum() <= 0) {
      throw bad("请求数量必须大于 0");
    }
    var row =
        sql.fetchOne(
            "select " + CONFIG_COLUMNS + " from fulfillment_dropship_config where id = ?",
            request.configId());
    if (row == null) {
      throw notFound("代发配置不存在或不属于当前租户");
    }
    String skuRef = request.skuRef().trim();

    // 明细行：缺失或停用都不该判成可售 —— 但两者要分开说。
    var line =
        sql.fetchOne(
            "select "
                + LINE_COLUMNS
                + " from fulfillment_dropship_config_line"
                + " where config_id = ? and sku_ref = ?",
            request.configId(),
            skuRef);

    DropshipSellReasonCode reason = DropshipSellReasonCode.SELLABLE;
    String detail = "配置生效、供应商可供货、差价非负";
    BigDecimal supplierAvailable = null;
    BigDecimal requiredQty = null;

    String status = row.get("status", String.class);
    OffsetDateTime effectiveFrom = row.get("effective_from", OffsetDateTime.class);
    OffsetDateTime effectiveTo = row.get("effective_to", OffsetDateTime.class);
    OffsetDateTime now = OffsetDateTime.now();
    if (!status.equals("ACTIVE")) {
      reason = DropshipSellReasonCode.CONFIG_NOT_ACTIVE;
      detail = "配置当前状态是 " + label(status) + "，只有「生效中」才参与可售判定";
    } else if (effectiveFrom != null && effectiveFrom.isAfter(now)) {
      reason = DropshipSellReasonCode.CONFIG_NOT_EFFECTIVE;
      detail = "配置尚未到生效时间 " + effectiveFrom;
    } else if (effectiveTo != null && !effectiveTo.isAfter(now)) {
      reason = DropshipSellReasonCode.CONFIG_NOT_EFFECTIVE;
      detail = "配置已于 " + effectiveTo + " 失效";
    } else if (line == null) {
      reason = DropshipSellReasonCode.LINE_INACTIVE;
      detail = "SKU " + skuRef + " 不在本配置的明细行里";
    } else if (!line.get("active", Boolean.class)) {
      reason = DropshipSellReasonCode.LINE_INACTIVE;
      detail = "SKU " + skuRef + " 的明细行已停用";
    }

    String supplierCode = row.get("supplier_code", String.class);
    String shopCode = row.get("shop_code", String.class);
    String scopeKind = row.get("scope_kind", String.class);
    String listingPolicy = row.get("listing_policy", String.class);
    String settlementBasis = row.get("settlement_basis", String.class);
    boolean countsOwn = row.get("counts_against_own_inventory", Boolean.class);
    String checkMode = row.get("availability_check_mode", String.class);
    BigDecimal buffer = row.get("min_supplier_stock_buffer", BigDecimal.class);

    // 店铺停用（仅按店铺生效时才需要判）
    if (reason == DropshipSellReasonCode.SELLABLE
        && scopeKind.equals("SHOP")
        && !shopCode.isBlank()) {
      var shop =
          sql.fetchOne(
              "select active from fulfillment_dropship_shop_ref where shop_code = ?", shopCode);
      if (shop == null) {
        reason = DropshipSellReasonCode.SHOP_INACTIVE;
        detail = "店铺 " + shopCode + " 在 2.3.1 投影里查不到，无法确认它是否仍在运营";
      } else if (!shop.get("active", Boolean.class)) {
        reason = DropshipSellReasonCode.SHOP_INACTIVE;
        detail = "店铺 " + shopCode + " 已停用";
      }
    }

    // 供应商报价过期
    if (reason == DropshipSellReasonCode.SELLABLE && !supplierCode.isBlank()) {
      var supplier =
          sql.fetchOne(
              "select quote_valid_to, active from fulfillment_dropship_supplier_ref"
                  + " where supplier_code = ?",
              supplierCode);
      if (supplier != null) {
        var validTo = supplier.get("quote_valid_to", LocalDate.class);
        if (!supplier.get("active", Boolean.class)) {
          reason = DropshipSellReasonCode.QUOTE_EXPIRED;
          detail = "供应商 " + supplierCode + " 已停用";
        } else if (validTo != null && validTo.isBefore(LocalDate.now())) {
          reason = DropshipSellReasonCode.QUOTE_EXPIRED;
          detail = "供应商 " + supplierCode + " 的报价已于 " + validTo + " 过期";
        }
      }
    }

    // 差价转负：宁可拦住，也不让它一路静默到结算
    BigDecimal margin = marginOf(row, line);
    if (reason == DropshipSellReasonCode.SELLABLE && margin.signum() < 0) {
      reason = DropshipSellReasonCode.NEGATIVE_MARGIN;
      detail =
          "差价已转负：我方毛利 "
              + margin.toPlainString()
              + "（参考售价 "
              + sellOf(row, line).toPlainString()
              + " − 供货价 "
              + costOf(row, line).toPlainString()
              + "）。供应商涨价后毛利转负的订单若不拦住，会一路静默亏到结算";
    }

    // 供应商可供货量
    if (reason == DropshipSellReasonCode.SELLABLE && checkMode.equals("SUPPLIER_STOCK")) {
      requiredQty =
          requestedQty
              .add(buffer == null ? ZERO_QTY : buffer)
              .setScale(QTY_SCALE, RoundingMode.HALF_UP);
      if (supplierCode.isBlank()) {
        reason = DropshipSellReasonCode.NO_SUPPLIER_STOCK_RECORD;
        detail = "按供应商库存校验，但配置上没有代发供应商，无从问谁要货";
      } else {
        var stock =
            sql.fetchOne(
                "select available_qty from fulfillment_dropship_supplier_stock"
                    + " where supplier_code = ? and sku_ref = ?",
                supplierCode,
                skuRef);
        if (stock == null) {
          // 关键：这是「没人报过」，不是「供应商没货」。所以 available 保持 null。
          reason = DropshipSellReasonCode.NO_SUPPLIER_STOCK_RECORD;
          detail =
              "没有供应商 "
                  + supplierCode
                  + " 对 SKU "
                  + skuRef
                  + " 的可供货量观测。这是**缺口**（没人报过），不是「供应商确实没货」—— "
                  + "两者必须分开，缺货前应先补观测或改可售校验方式";
        } else {
          supplierAvailable = stock.get("available_qty", BigDecimal.class);
          if (supplierAvailable.compareTo(requiredQty) < 0) {
            reason = DropshipSellReasonCode.SUPPLIER_STOCK_INSUFFICIENT;
            detail =
                "供应商可供 "
                    + supplierAvailable.toPlainString()
                    + "，本次需求 "
                    + requiredQty.toPlainString()
                    + "（请求 "
                    + requestedQty.toPlainString()
                    + " + 安全余量 "
                    + (buffer == null ? "0" : buffer.toPlainString())
                    + "）";
          }
        }
      }
    }

    boolean sellable = reason == DropshipSellReasonCode.SELLABLE;
    boolean negativeMargin = margin.signum() < 0;
    boolean listable =
        switch (DropshipListingPolicy.valueOf(listingPolicy)) {
          case ALWAYS_LIST -> true;
          case PAUSE_WHEN_UNSELLABLE -> sellable;
          case PAUSE_WHEN_NEGATIVE_MARGIN -> !negativeMargin;
          case MANUAL -> sellable && !negativeMargin;
        };

    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into fulfillment_dropship_evaluation(id,tenant_id,config_id,config_line_id,"
            + "sku_ref,shop_code,supplier_code,requested_qty,required_qty,"
            + "supplier_available_qty,sellable,listable,reason_code,reason_detail,"
            + "settlement_basis,expected_margin_amount,counts_against_own_inventory,evaluated_at)"
            // 18 列 = 18 个占位符；cast 写在 evaluated_at 这一位上，不放在绑定序列末位之后。
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
        id,
        currentTenant(),
        request.configId(),
        line == null ? null : line.get("id", UUID.class),
        skuRef,
        shopCode,
        supplierCode,
        requestedQty,
        requiredQty,
        supplierAvailable,
        sellable,
        listable,
        reason.name(),
        detail,
        settlementBasis,
        margin,
        countsOwn,
        now);
    return new DropshipEvaluationSaved(id, sellable, listable, reason.name());
  }

  // ================================================================ 事件流

  /** 事件流（只读；append-only，没有任何写形状）。 */
  public DropshipEventPage pageEvents(UUID configId, int page, int size) {
    int pageSize = pageSize(size);
    int pageNo = Math.max(0, page);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (configId != null) {
      where.add("config_id = ?");
      binds.add(configId);
    }
    String sqlWhere = whereClause(where);
    long total =
        sql.fetchOne(
                "select count(*) c from fulfillment_dropship_event" + sqlWhere, binds.toArray())
            .get("c", Long.class);
    List<DropshipEventRow> items =
        sql.fetch(
                "select id,config_id,event_kind,from_status,to_status,detail,actor_membership_id,"
                    + "occurred_at from fulfillment_dropship_event"
                    + sqlWhere
                    + " order by occurred_at desc, id limit ? offset ?",
                append(binds, pageSize, (long) pageNo * pageSize))
            .map(DropshipConfigService::eventRow);
    return new DropshipEventPage(items, total, pageNo, pageSize);
  }

  private List<DropshipEventRow> eventRows(UUID configId, int limit) {
    return sql.fetch(
            "select id,config_id,event_kind,from_status,to_status,detail,actor_membership_id,"
                + "occurred_at from fulfillment_dropship_event where config_id = ?"
                + " order by occurred_at desc, id limit ?",
            configId,
            limit)
        .map(DropshipConfigService::eventRow);
  }

  private void event(
      UUID configId, UUID lineId, String kind, String from, String to, String detail, UUID actor) {
    sql.execute(
        "insert into fulfillment_dropship_event(id,tenant_id,config_id,event_kind,from_status,"
            + "to_status,detail,actor_membership_id,occurred_at)"
            + " values (?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        configId,
        kind,
        nz(from),
        nz(to),
        nz(detail),
        actor,
        OffsetDateTime.now());
  }

  // ================================================================ 配置详情返回体

  /** 配置详情：配置头 + 明细行 + 事件流 + 一句「本模块不预占库存」的实话。 */
  public record DropshipConfigDetail(
      DropshipConfigRow config,
      List<DropshipConfigLineRow> lines,
      List<DropshipEventRow> events,
      String reservationNote) {}

  // ================================================================ 行映射

  private static DropshipShopRefRow shopRefRow(Record r) {
    return new DropshipShopRefRow(
        r.get("id", UUID.class),
        r.get("shop_code", String.class),
        r.get("shop_name", String.class),
        r.get("platform_code", String.class),
        r.get("region_code", String.class),
        r.get("active", Boolean.class),
        r.get("source_system", String.class),
        r.get("synced_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static DropshipSupplierRefRow supplierRefRow(Record r) {
    return new DropshipSupplierRefRow(
        r.get("id", UUID.class),
        r.get("supplier_code", String.class),
        r.get("supplier_name", String.class),
        r.get("currency_code", String.class),
        r.get("quote_valid_from", LocalDate.class),
        r.get("quote_valid_to", LocalDate.class),
        r.get("quote_source", String.class),
        r.get("lead_time_days", BigDecimal.class),
        r.get("active", Boolean.class),
        r.get("synced_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static DropshipConfigRow configRow(Record r) {
    return new DropshipConfigRow(
        r.get("id", UUID.class),
        r.get("config_code", String.class),
        r.get("config_name", String.class),
        r.get("scope_kind", String.class),
        r.get("shop_code", String.class),
        r.get("sku_ref", String.class),
        r.get("fulfillment_path", String.class),
        r.get("supplier_code", String.class),
        r.get("ownership_mode", String.class),
        r.get("counts_against_own_inventory", Boolean.class),
        r.get("pure_agency_exempt", Boolean.class),
        r.get("settlement_timing", String.class),
        r.get("settlement_basis", String.class),
        r.get("margin_share_rate", BigDecimal.class),
        r.get("currency_code", String.class),
        r.get("supplier_cost", BigDecimal.class),
        r.get("reference_sell_price", BigDecimal.class),
        r.get("gross_spread", BigDecimal.class),
        r.get("expected_margin_amount", BigDecimal.class),
        r.get("expected_supplier_amount", BigDecimal.class),
        r.get("availability_check_mode", String.class),
        r.get("min_supplier_stock_buffer", BigDecimal.class),
        r.get("listing_policy", String.class),
        r.get("priority", Integer.class),
        r.get("status", String.class),
        r.get("effective_from", OffsetDateTime.class),
        r.get("effective_to", OffsetDateTime.class),
        r.get("ownership_confirmed_at", OffsetDateTime.class),
        r.get("ownership_confirmed_note", String.class),
        r.get("ownership_reviewed_with", String.class),
        r.get("note", String.class),
        r.get("version", Integer.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static DropshipConfigLineRow lineRow(Record r) {
    return new DropshipConfigLineRow(
        r.get("id", UUID.class),
        r.get("config_id", UUID.class),
        r.get("sku_ref", String.class),
        r.get("sku_name", String.class),
        r.get("supplier_cost_override", BigDecimal.class),
        r.get("reference_sell_price_override", BigDecimal.class),
        r.get("active", Boolean.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static DropshipSupplierStockRow stockRow(Record r) {
    return new DropshipSupplierStockRow(
        r.get("id", UUID.class),
        r.get("supplier_code", String.class),
        r.get("sku_ref", String.class),
        r.get("available_qty", BigDecimal.class),
        r.get("stock_source", String.class),
        r.get("observed_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static DropshipEvaluationRow evaluationRow(Record r) {
    return new DropshipEvaluationRow(
        r.get("id", UUID.class),
        r.get("config_id", UUID.class),
        r.get("config_line_id", UUID.class),
        r.get("sku_ref", String.class),
        r.get("shop_code", String.class),
        r.get("supplier_code", String.class),
        r.get("requested_qty", BigDecimal.class),
        r.get("required_qty", BigDecimal.class),
        r.get("supplier_available_qty", BigDecimal.class),
        r.get("sellable", Boolean.class),
        r.get("listable", Boolean.class),
        r.get("reason_code", String.class),
        r.get("reason_detail", String.class),
        r.get("settlement_basis", String.class),
        r.get("expected_margin_amount", BigDecimal.class),
        r.get("counts_against_own_inventory", Boolean.class),
        r.get("evaluated_at", OffsetDateTime.class));
  }

  private static DropshipEventRow eventRow(Record r) {
    return new DropshipEventRow(
        r.get("id", UUID.class), r.get("config_id", UUID.class),
        r.get("event_kind", String.class), r.get("from_status", String.class),
        r.get("to_status", String.class), r.get("detail", String.class),
        r.get("actor_membership_id", UUID.class), r.get("occurred_at", OffsetDateTime.class));
  }

  // ================================================================ 计算

  /** 生效的供货价：明细行覆盖优先。 */
  private static BigDecimal costOf(Record config, Record line) {
    if (line != null) {
      var override = line.get("supplier_cost_override", BigDecimal.class);
      if (override != null) {
        return override;
      }
    }
    return config.get("supplier_cost", BigDecimal.class);
  }

  /** 生效的参考售价：明细行覆盖优先。 */
  private static BigDecimal sellOf(Record config, Record line) {
    if (line != null) {
      var override = line.get("reference_sell_price_override", BigDecimal.class);
      if (override != null) {
        return override;
      }
    }
    return config.get("reference_sell_price", BigDecimal.class);
  }

  /**
   * 我方毛利（差价抽成时 = 差价 × 留存比例；固定供货价时 = 差价）。
   *
   * <p>与 DB 生成列 {@code expected_margin_amount} 同一套口径（全 {@code BigDecimal}， HALF_UP 到 4
   * 位）。带覆盖价时按覆盖价重算 —— 那正是明细行存在的意义。
   */
  private static BigDecimal marginOf(Record config, Record line) {
    BigDecimal cost = costOf(config, line);
    BigDecimal sell = sellOf(config, line);
    BigDecimal spread = sell.subtract(cost).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    if (config.get("settlement_basis", String.class).equals("MARGIN_SHARE")) {
      BigDecimal rate = config.get("margin_share_rate", BigDecimal.class);
      return spread
          .multiply(rate == null ? ZERO_RATE : rate)
          .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
    return spread;
  }

  // ================================================================ 词表

  private static String label(String status) {
    try {
      return DropshipConfigStatus.valueOf(status).label();
    } catch (RuntimeException e) {
      return status;
    }
  }

  private static DropshipScopeKind scopeKind(String value) {
    try {
      return DropshipScopeKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的适用范围「" + value + "」：只能是 SHOP / SKU");
    }
  }

  private static DropshipFulfillmentPath fulfillmentPath(String value) {
    try {
      return DropshipFulfillmentPath.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的履约路径「" + value + "」：只能是 SELF_FULFILLED / SUPPLIER_DIRECT");
    }
  }

  private static DropshipOwnershipMode ownershipMode(String value) {
    try {
      return DropshipOwnershipMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的货权模式「" + value + "」：只能是 PURE_AGENCY / OUR_ACCOUNT");
    }
  }

  private static DropshipSettlementTiming settlementTiming(String value) {
    try {
      return DropshipSettlementTiming.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的结算周期「" + value + "」：只能是 ON_ORDER / ON_RECEIPT");
    }
  }

  private static DropshipSettlementBasis settlementBasis(String value) {
    try {
      return DropshipSettlementBasis.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的结算方式「" + value + "」：只能是 FLAT_COST / MARGIN_SHARE");
    }
  }

  private static DropshipAvailabilityCheckMode availabilityCheckMode(String value) {
    try {
      return DropshipAvailabilityCheckMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的可售校验方式「" + value + "」：只能是 SUPPLIER_STOCK / CONFIRM_ON_ORDER / SKIP");
    }
  }

  private static DropshipListingPolicy listingPolicy(String value) {
    try {
      return DropshipListingPolicy.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的刊登策略「" + value + "」");
    }
  }

  private static DropshipConfigStatus configStatus(String value) {
    try {
      return DropshipConfigStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的配置状态「" + value + "」：只能是 DRAFT / ACTIVE / SUSPENDED / CLOSED");
    }
  }

  private static DropshipSellReasonCode sellReason(String value) {
    try {
      return DropshipSellReasonCode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的可售原因码「" + value + "」");
    }
  }

  private static DropshipShopSource shopSource(String value) {
    try {
      return DropshipShopSource.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的店铺来源「" + value + "」：只能是 MODULE_2_3_1 / TENANT_MAINTAINED");
    }
  }

  private static DropshipSupplierSource supplierSource(String value) {
    try {
      return DropshipSupplierSource.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的供应商来源「" + value + "」：只能是 MODULE_3_6_2 / TENANT_MAINTAINED");
    }
  }

  private static DropshipStockSource stockSource(String value) {
    try {
      return DropshipStockSource.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的库存来源「" + value + "」：只能是 MODULE_7_1_1_3 / MODULE_3_6_2 / TENANT_MAINTAINED");
    }
  }

  // ================================================================ 工具

  private static int pageSize(int requested) {
    return Math.min(MAX_PAGE_SIZE, Math.max(1, requested <= 0 ? 50 : requested));
  }

  private static Object[] append(List<Object> binds, Object... more) {
    List<Object> all = new ArrayList<>(binds);
    all.addAll(List.of(more));
    return all.toArray();
  }

  private static String whereClause(List<String> where) {
    return where.isEmpty() ? "" : " where " + String.join(" and ", where);
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static String blankTo(String value, String fallback) {
    return notBlank(value) ? value.trim() : fallback;
  }

  /** 数量刻度对齐 numeric(19,6)。 */
  private static BigDecimal qty(BigDecimal value) {
    return value == null ? null : value.setScale(QTY_SCALE, RoundingMode.HALF_UP);
  }

  /** 金额刻度对齐 numeric(19,4)。 */
  private static BigDecimal money(BigDecimal value, String field) {
    if (value == null) {
      throw bad(field + " 不能为空：差价抽成与供货价是本模块的结算基础，缺它就无从判断差价正负");
    }
    return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 当前 RLS 租户。由 backend/libraries/tenancy 在事务内设置，客户端头改不动它。
   *
   * <p>表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC **复位成空串**而不是「回到未设置」，直接 cast 会报
   * uuid 语法错。
   */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }
}
