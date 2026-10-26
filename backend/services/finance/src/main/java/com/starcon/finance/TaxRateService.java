package com.starcon.finance;

import static com.starcon.finance.TaxRateModels.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 12.6.1.1 税率配置。
 *
 * <p>蓝图 12-finance.md：「维护税率配置：目的国/地区的销售税率、VAT 税率、IOSS 适用税率、
 * 代扣代缴税；支持按店铺站点绑定默认税率与税码。」输入：「税率定义；适用站点；生效时间。」
 * 输出：「税率记录；供订单与申报引用。」关系：「上游 = 2 店铺站点、16 合规；下游 = 9 订单（税额）、
 * 12.6.1.2 申报。<b>税率必须带生效时间</b>，历史订单用当时税率，不能被新政策追溯修改。」
 *
 * <p><b>🔴 本模块只做税率配置面，不产出税额。</b>税额属 9 订单（税额）与 12.6.1.2 申报；
 * 本模块**不建**税额表、**不写**订单、**不产出**任何申报底稿。
 *
 * <p><b>🔴 税率零预置行。</b>税率是用户自己的税务事实 —— 系统只提供四个税种的词表与
 * 受控辖区选项，<b>落库的每一行税率都是租户自己录的</b>。系统替用户默认一个税率
 * 就是替用户记账。
 *
 * <p><b>版本链 + 生效区间</b>：改税率 = <b>追加新版本</b>（DB 触发器钉死版本号递增 +
 * 生效起点严格递进 + 区间不许重叠 + 历史版本行不可改写 + 部分唯一索引保证同链
 * 同时只有一个生效版本）。旧版本被收口生效区间 ⇒ 「当时生效的是哪一版」永远可定位。
 *
 * <p><b>🔴 按生效时间取税率，命中不到就报错</b>：给定（订单日期 + 店铺站点 + 辖区）⇒
 * 唯一命中一条生效税率；<b>命中不到一律报错</b>，<b>不退回最新一条</b>、<b>不按 0% 处理</b> ——
 * 「这个时点没有税率」和「这个时点税率是 0」是两件完全不同的事。
 *
 * <p><b>🔴 与 channel 的边界</b>：美国<b>州级销售税</b>归 channel 16.3.2.3 维护，
 * 本模块<b>不重复建表</b>，只在页面上经授权只读 API 引用；「目的国/地区」的值域
 * 取自 channel 16.3.1.1 的受控辖区码表（不复制、不跨库 FK、读不到就 503）。
 *
 * <p><b>时间字段</b>：{@code effective_from} / {@code effective_to} / {@code created_at} 是
 * {@code timestamptz}，一律 OffsetDateTime + {@code ?::timestamptz}。
 */
@Service
public class TaxRateService {

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 税率小数位（税率列是 numeric(9,6)）。 */
  static final int RATE_SCALE = 6;

  /** 🔴 美国辖区（联邦与 50 州）归 channel 16.3.2.3，本模块不重复建表。 */
  static final String CHANNEL_OWNED_COUNTRY = "US";

  private final DSLContext sql;
  private final ChannelTaxPort channelTax;
  private final ChannelShopPort channelShops;

  public TaxRateService(DSLContext sql, ChannelTaxPort channelTax, ChannelShopPort channelShops) {
    this.sql = sql;
    this.channelTax = channelTax;
    this.channelShops = channelShops;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**。
   *
   * <p>本模块的缺口必须分开说：12.6.1.2 申报（**同服务**的后续模块）、
   * 9 订单（税额，**别的**服务）、12.6.2.1 发票管理（同服务）——
   * 把它们都写成「不可用」，运维会以为要去催别家，而实际上那是本服务内部的事。
   */
  public FinanceTaxRateCapabilities capabilities() {
    var capabilities = new ArrayList<FinanceTaxRateCapability>();
    var gaps = new ArrayList<FinanceTaxRateGap>();

    capabilities.add(
        new FinanceTaxRateCapability(
            "12.6.1.1",
            "税率记录（目的国/地区的销售税率 / VAT / IOSS / 代扣代缴，含生效区间与版本链）",
            true,
            "finance_tax_rate + DB 层版本 / 生效区间守卫（V361）",
            "",
            "蓝图点名的四个税种都在 finance_tax_rate.tax_type 的 CHECK 里："
                + "SALES_TAX / VAT / IOSS / WITHHOLDING。"
                + "改税率 = **追加新版本**（触发器钉死版本号递增 + 生效起点严格递进 + "
                + "生效区间不许重叠 + 历史版本行不可改写），旧版本被收口生效区间。"
                + "**税率零预置行** —— 税率是用户自己的税务事实，系统只提供结构与辖区选项。"));

    capabilities.add(
        new FinanceTaxRateCapability(
            "12.6.1.1",
            "按生效时间取税率（订单日期 + 店铺站点 + 辖区 ⇒ 唯一命中）",
            true,
            "finance_tax_rate / finance_tax_shop_binding 的生效区间查询 + 唯一命中校验",
            "",
            "给定「订单日期 + 店铺站点 + 辖区」唯一命中一条生效税率；"
                + "**命中不到一律报错**，不退回最新一条、不按 0% 处理 —— "
                + "「这个时点没有税率」和「这个时点税率是 0」是两件完全不同的事。"));

    capabilities.add(
        new FinanceTaxRateCapability(
            "12.6.1.1",
            "店铺站点默认税率与税码绑定（换绑同样不追溯）",
            true,
            "finance_tax_shop_binding（版本化 + 生效区间）",
            "",
            "蓝图说的是「**默认**」税率与税码：产品级 / 行级的税码税率属 12.6.2.1 发票管理。"
                + "绑定钉的是**一条税档**（辖区 × 税码）而不是某一个税率版本 ⇒ "
                + "政策改税率时，绑在这条税档上的店铺自动从新版本的生效时刻起按新税率算，"
                + "而历史订单仍按当时那版算。绑定本身也有版本链 ⇒ 换绑不追溯。"));

    capabilities.add(
        new FinanceTaxRateCapability(
            "16.3.1.1",
            "受控辖区码表（目的国/地区的值域）",
            true,
            "channel GET /channel/tax-registration/jurisdictions（ChannelTaxPort 只读）",
            "16.3.1.1",
            "辖区码**实时**读 channel，本服务**不复制**那张码表（复制 = 两个真值源）、"
                + "**不建**指向它的外键（跨库 FK 是模块边界纪律禁止的）。"
                + "落库前校验辖区码真实存在 ⇒ 辖区码**不是自由文本**。"
                + "channel 读不到一律 503 并写明「读不到」，绝不退回空集合。"));

    capabilities.add(
        new FinanceTaxRateCapability(
            "2.3.1.1",
            "店铺站点目录（绑定对象）",
            true,
            "channel GET /channel/shops（ChannelShopPort 只读）",
            "",
            "店铺清单**实时**读 channel，本服务不建影子店铺表；"
                + "绑定行只存店铺逻辑 ID + 编码 / 名称快照。读不到时显示「读不到」，"
                + "**不是**「这个租户一家店都没有」。"));

    capabilities.add(
        new FinanceTaxRateCapability(
            "16.3.2.3",
            "美国州级销售税税率规则（🔴 **归 channel，本模块不重复建表**）",
            true,
            "channel GET /channel/sales-tax/rate-rules（ChannelTaxPort 只读）",
            "16.3.2.3",
            "州基准税率 / 州级阈值 / 免征额 / Marketplace Facilitator 义务与申报周期"
                + "**全部归 channel 16.3.2.3**（sales_tax_rate_rule）。"
                + "本模块**不建**任何州销售税表，页面只经授权只读 API 引用，"
                + "并标明来源模块；channel 读不到时如实显示「读不到」。"));

    gaps.add(
        new FinanceTaxRateGap(
            "12.6.1.2",
            "VAT/IOSS 申报数据（申报底稿 + 汇总 + 导出）",
            "finance",
            false,
            "下游",
            "🔴 **未交付**。本模块只产出**税率记录**与「某时点命中哪一版」的取数能力，"
                + "**不产出任何申报底稿 / 汇总数字 / 申报文件**。"));

    gaps.add(
        new FinanceTaxRateGap(
            "9",
            "订单税额（把当时那版税率快照进订单）",
            "trade",
            false,
            "下游",
            "🔴 **未交付**（在别的服务）。订单侧经授权只读 API 取「某时点的生效税率」"
                + "并快照；本模块**不写订单，也不改订单**。"));

    gaps.add(
        new FinanceTaxRateGap(
            "12.6.2.1",
            "发票管理（行级税码税率税额）",
            "finance",
            false,
            "下游",
            "🔴 **未交付**（同服务）。产品级 / 行级的税码税率归它；"
                + "本模块只到**店铺默认**税码这一层。"));

    return new FinanceTaxRateCapabilities(capabilities, gaps);
  }

  // ================================================================ 选项

  /** 筛选项下拉：税种来自本模块的词表（只读，不落库），税码来自真实数据。 */
  public FinanceTaxRateOptions options() {
    var taxTypes = new ArrayList<FinanceTaxRateTypeOption>();
    for (var t : TaxType.values()) {
      taxTypes.add(new FinanceTaxRateTypeOption(t.name(), t.label(), t.basis()));
    }
    var statuses = new ArrayList<String>();
    for (var s : TaxRateStatus.values()) statuses.add(s.name());
    var taxCodes =
        sql.fetch("select distinct tax_code from finance_tax_rate order by tax_code")
            .stream()
            .map(r -> r.get(0, String.class))
            .filter(java.util.Objects::nonNull)
            .toList();
    return new FinanceTaxRateOptions(taxTypes, statuses, taxCodes);
  }

  // ================================================================ 辖区选项（读 channel）

  /**
   * 辖区选项：实时读 channel 的受控辖区码表，读不到就如实说读不到。
   *
   * <p>🔴 本模块**不接收美国辖区**（联邦与 50 州）：那一块归 channel 16.3.2.3。
   * 不可用的辖区**仍然列出来**并写明原因 —— 藏起来的话，运营会以为系统没有这个辖区。
   */
  public FinanceTaxJurisdictionOptions jurisdictionOptions(String bearer) {
    var upstream = channelTax.jurisdictions(bearer);
    var items = new ArrayList<FinanceTaxJurisdictionOption>();
    for (var j : upstream) {
      var unusable = unusableReason(j);
      items.add(
          new FinanceTaxJurisdictionOption(
              j.code(),
              j.name(),
              j.region(),
              j.countryCode(),
              j.subdivisionCode(),
              j.taxRegime(),
              j.vatRequired(),
              j.requiresEpr(),
              j.defaultFilingCycle(),
              j.derivationScope(),
              unusable == null,
              unusable == null ? "" : unusable));
    }
    return new FinanceTaxJurisdictionOptions(items, items.size(), true, "辖区码表来自 channel 16.3.1.1（实时只读）");
  }

  /** 某个辖区在本模块**不可用**的原因；可用时返回 {@code null}。 */
  private String unusableReason(ChannelTaxPort.ChannelJurisdictionView j) {
    if (CHANNEL_OWNED_COUNTRY.equalsIgnoreCase(j.countryCode() == null ? "" : j.countryCode().trim())) {
      return "美国州级销售税归 channel 16.3.2.3 维护，本模块不重复建表（请看页面的「州销售税（归 channel）」区块）";
    }
    return null;
  }

  // ================================================================ 店铺选项（读 channel）

  /** 店铺站点选项：实时读 channel 2.3.1.1 店铺目录，读不到就如实说读不到。 */
  public FinanceTaxShopOptions shopOptions(String bearer) {
    var shops = channelShops.shops(bearer);
    var items = new ArrayList<FinanceTaxShopOption>();
    for (var s : shops) {
      items.add(
          new FinanceTaxShopOption(
              s.id().toString(),
              s.code() == null ? "" : s.code(),
              s.name() == null ? "" : s.name(),
              s.platformCode() == null ? "" : s.platformCode(),
              s.platformName() == null ? "" : s.platformName(),
              s.status() == null ? "" : s.status(),
              true,
              ""));
    }
    return new FinanceTaxShopOptions(items, items.size(), true, "店铺目录来自 channel 2.3.1.1（实时只读）");
  }

  // ================================================================ 税率记录

  /** 税率记录列表（真分页 + 真筛选）。 */
  public FinanceTaxRatePage rates(
      String taxType,
      String jurisdictionCode,
      String taxCode,
      String status,
      String keyword,
      int page,
      int size) {
    var type = taxType == null || taxType.isBlank() ? null : TaxType.parse(taxType);
    if (type == null && taxType != null && !taxType.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "税种只能是 SALES_TAX / VAT / IOSS / WITHHOLDING");
    }
    var where = new StringBuilder(" where tenant_id = ?");
    var binds = new ArrayList<Object>();
    binds.add(currentTenant());
    if (type != null) {
      where.append(" and tax_type = ?");
      binds.add(type.name());
    }
    if (jurisdictionCode != null && !jurisdictionCode.isBlank()) {
      where.append(" and jurisdiction_code = ?");
      binds.add(jurisdictionCode.trim().toUpperCase(Locale.ROOT));
    }
    if (taxCode != null && !taxCode.isBlank()) {
      where.append(" and tax_code = ?");
      binds.add(taxCode.trim());
    }
    if (status != null && !status.isBlank()) {
      where.append(" and status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (jurisdiction_code ilike ? or jurisdiction_name ilike ? or tax_code ilike ? or note ilike ?)");
      var like = "%" + keyword.trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }

    var total =
        sql.fetchOne("select count(*) from finance_tax_rate" + where, binds.toArray()).get(0, Long.class);
    var activeCount =
        sql.fetchOne(
                "select count(*) from finance_tax_rate where tenant_id = ? and status = 'ACTIVE'", currentTenant())
            .get(0, Long.class);

    var limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var offset = Math.max(page, 0) * limit;
    binds.add(limit);
    binds.add(offset);
    var rows =
        sql.fetch(
            "select id,tax_type,jurisdiction_code,jurisdiction_name,country_code,tax_code,rate,"
                + " effective_from,effective_to,status,version,supersedes_id,note,created_by,created_at,updated_at"
                + " from finance_tax_rate" + where + " order by tax_type,jurisdiction_code,tax_code,version desc"
                + " limit ? offset ?",
            binds.toArray());
    var items = new ArrayList<FinanceTaxRateRow>();
    for (var row : rows) items.add(rateRow(row));
    return new FinanceTaxRatePage(items, Math.max(page, 0), limit, total, activeCount);
  }

  /**
   * 登记 / 换税率（按「税种 × 辖区 × 税码」upsert：同链已有生效版本 ⇒ **追加新版本**）。
   *
   * <p>⚠️ 写库顺序是「先把上一版置为 SUPERSEDED 并收口生效区间，再插入新的一版」——
   * 「同一链同时只有一个生效版本」是部分唯一索引，顺序反了会撞唯一约束。
   */
  public FinanceTaxRateRow upsertRate(
      FinanceTaxRateInput input, AccessService.Access actor, String bearer) {
    var type = TaxType.parse(input.taxType());
    if (type == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "税种只能是 SALES_TAX（目的国/地区销售税）/ VAT / IOSS / WITHHOLDING（代扣代缴）");
    }
    if (input.jurisdictionCode() == null || input.jurisdictionCode().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出辖区（目的国/地区）");
    }
    var jurisdiction = requireJurisdiction(bearer, input.jurisdictionCode().trim().toUpperCase(Locale.ROOT));
    var taxCode = input.taxCode() == null ? "" : input.taxCode().trim();
    if (taxCode.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出税码（例如标准 / 优惠 / 零税率）");
    }
    if (taxCode.length() > 32) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "税码最长 32 个字符");
    }
    var rate = requireRate(input.rate());
    if (input.effectiveFrom() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "必须给出生效时间：没有生效时间的税率等于没有");
    }

    var tenant = currentTenant();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var chain = chainKey(tenant, type.name(), jurisdiction.code(), taxCode);

    var last =
        sql.fetchOne(
            "select id,version,effective_from,effective_to,status from finance_tax_rate"
                + " where tenant_id = ? and tax_type = ? and jurisdiction_code = ? and tax_code = ?"
                + " order by version desc limit 1",
            chain[0],
            chain[1],
            chain[2],
            chain[3]);

    UUID supersedesId = null;
    int version = 1;
    if (last != null) {
      supersedesId = last.get("id", UUID.class);
      version = last.get("version", Integer.class) + 1;
      var lastFrom = last.get("effective_from", OffsetDateTime.class);
      if (!input.effectiveFrom().isAfter(lastFrom)) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "新版本的生效时间必须晚于上一版本（"
                + lastFrom
                + "），否则历史订单会被追溯改写");
      }
      // 先收口上一版：置为 SUPERSEDED + 收口生效区间（历史只读，不改税率）。
      sql.execute(
          "update finance_tax_rate set status = 'SUPERSEDED', effective_to = ?::timestamptz,"
              + " updated_at = ?::timestamptz where tenant_id = ? and id = ?",
          input.effectiveFrom(),
          now,
          tenant,
          supersedesId);
    }

    var id = UUID.randomUUID();
    sql.execute(
        "insert into finance_tax_rate(id,tenant_id,tax_type,jurisdiction_code,jurisdiction_name,"
            + " country_code,tax_code,rate,effective_from,effective_to,status,version,supersedes_id,note,"
            + " created_by,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?::timestamptz,null,'ACTIVE',?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        tenant,
        type.name(),
        jurisdiction.code(),
        jurisdiction.name(),
        jurisdiction.countryCode(),
        taxCode,
        rate,
        input.effectiveFrom(),
        version,
        supersedesId,
        input.note() == null ? "" : input.note().trim(),
        actor == null ? null : actor.userId(),
        now,
        now);
    return rate(id);
  }

  /** 版本历史（新到旧）。 */
  public List<FinanceTaxRateRow> versions(String taxType, String jurisdictionCode, String taxCode) {
    var type = TaxType.parse(taxType);
    if (type == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出税种");
    }
    if (jurisdictionCode == null || jurisdictionCode.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出辖区");
    }
    if (taxCode == null || taxCode.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出税码");
    }
    var rows =
        sql.fetch(
            "select id,tax_type,jurisdiction_code,jurisdiction_name,country_code,tax_code,rate,"
                + " effective_from,effective_to,status,version,supersedes_id,note,created_by,created_at,updated_at"
                + " from finance_tax_rate where tenant_id = ? and tax_type = ? and jurisdiction_code = ?"
                + " and tax_code = ? order by version desc",
            currentTenant(),
            type.name(),
            jurisdictionCode.trim().toUpperCase(Locale.ROOT),
            taxCode.trim());
    var items = new ArrayList<FinanceTaxRateRow>();
    for (var row : rows) items.add(rateRow(row));
    return items;
  }

  /** 版本对比（换税率前 / 后）：逐字段给出差异。 */
  public FinanceTaxRateComparison compare(
      String taxType, String jurisdictionCode, String taxCode, int fromVersion, int toVersion) {
    var type = TaxType.parse(taxType);
    if (type == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出税种");
    }
    var from = versionRow(type.name(), jurisdictionCode, taxCode, fromVersion);
    var to = versionRow(type.name(), jurisdictionCode, taxCode, toVersion);
    var differences = new ArrayList<FinanceTaxRateDiff>();
    differences.add(diff("rate", "税率", from.rate(), to.rate()));
    differences.add(diff("taxCode", "税码", from.taxCode(), to.taxCode()));
    differences.add(diff("effectiveFrom", "生效时间", from.effectiveFrom(), to.effectiveFrom()));
    differences.add(diff("effectiveTo", "失效时间", from.effectiveTo(), to.effectiveTo()));
    differences.add(diff("status", "状态", from.status(), to.status()));
    differences.add(diff("note", "备注", from.note(), to.note()));
    var rateChanged = from.rate().compareTo(to.rate()) != 0;
    var note =
        "对比的是**税率配置本身**，不是税额 —— 税额属 9 订单与 12.6.1.2 申报。"
            + "换税率 = 追加新版本，历史订单仍按当时那版算。";
    return new FinanceTaxRateComparison(
        type.name(),
        from.jurisdictionCode(),
        from.jurisdictionName(),
        from,
        to,
        differences,
        rateChanged,
        note);
  }

  // ================================================================ 按生效时间取税率

  /**
   * 按生效时间取税率：给定（订单日期 + 店铺站点 + 辖区）⇒ 唯一命中一条生效税率。
   *
   * <p>🔴 **命中不到就报错**（404 + 写明原因），不退回最新一条、不按 0% 处理。
   *
   * @param shopId 店铺站点（可空；给了就走「店铺绑定」这条路）
   * @param jurisdictionCode 辖区（给了店铺时必填）
   * @param taxCode 税码（可空；给了店铺时若与绑定不一致 ⇒ 报错）
   */
  public FinanceTaxResolution resolve(
      String taxType, OffsetDateTime asOf, UUID shopId, String jurisdictionCode, String taxCode) {
    var type = TaxType.parse(taxType);
    if (type == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出税种（SALES_TAX / VAT / IOSS / WITHHOLDING）");
    }
    var at = asOf == null ? OffsetDateTime.now(ZoneOffset.UTC) : asOf;
    if (shopId != null) {
      if (jurisdictionCode == null || jurisdictionCode.isBlank()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "按店铺取税率时必须给出辖区：订单是要算哪个辖区的税");
      }
      var jurisdiction = jurisdictionCode.trim().toUpperCase(Locale.ROOT);
      var binding = effectiveBinding(currentTenant(), shopId, type.name(), jurisdiction, at);
      if (binding == null) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "店铺站点在 " + at + " 这一刻没有生效的 " + type.label() + " 绑定（辖区 " + jurisdiction
                + "）—— 没有绑定不等于税率是 0，请先维护绑定或改用「按辖区取税率」");
      }
      var boundCode = binding.get("tax_code", String.class);
      if (taxCode != null && !taxCode.isBlank() && !taxCode.trim().equals(boundCode)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "该店铺在该时点绑定的税码是 " + boundCode + "，与请求的 " + taxCode.trim() + " 不一致");
      }
      var rate = effectiveRate(currentTenant(), type.name(), jurisdiction, boundCode, at);
      if (rate == null) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "辖区 " + jurisdiction + " 的税码 " + boundCode + " 在 " + at + " 这一刻没有生效税率 —— "
                + "请先登记该时点生效的税率（不按 0% 处理）");
      }
      return new FinanceTaxResolution(
          "SHOP_BINDING",
          "按店铺站点绑定",
          rate.get("id", UUID.class),
          type.name(),
          type.label(),
          jurisdiction,
          rate.get("jurisdiction_name", String.class),
          boundCode,
          rate.get("rate", BigDecimal.class),
          shopId,
          binding.get("shop_code", String.class),
          binding.get("id", UUID.class),
          binding.get("version", Integer.class),
          at,
          "绑定与税率都是**当时那一版**；历史订单不会被后来的政策改写。");
    }

    if (jurisdictionCode == null || jurisdictionCode.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出辖区");
    }
    if (taxCode == null || taxCode.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出税码");
    }
    var jurisdiction = jurisdictionCode.trim().toUpperCase(Locale.ROOT);
    var rate = effectiveRate(currentTenant(), type.name(), jurisdiction, taxCode.trim(), at);
    if (rate == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "辖区 " + jurisdiction + " 的税码 " + taxCode.trim() + " 在 " + at + " 这一刻没有生效税率 —— "
              + "请先登记该时点生效的税率（不按 0% 处理）");
    }
    return new FinanceTaxResolution(
        "JURISDICTION",
        "按辖区取税率",
        rate.get("id", UUID.class),
        type.name(),
        type.label(),
        jurisdiction,
        rate.get("jurisdiction_name", String.class),
        taxCode.trim(),
        rate.get("rate", BigDecimal.class),
        null,
        null,
        null,
        null,
        at,
        "直接按辖区取生效版本；命中的是**当时那一版**。");
  }

  // ================================================================ 店铺绑定

  /** 店铺绑定列表（真分页 + 真筛选）。 */
  public FinanceTaxBindingPage bindings(
      UUID shopId, String taxType, String jurisdictionCode, String status, String keyword, int page, int size) {
    var type = taxType == null || taxType.isBlank() ? null : TaxType.parse(taxType);
    if (type == null && taxType != null && !taxType.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "税种只能是 SALES_TAX / VAT / IOSS / WITHHOLDING");
    }
    var where = new StringBuilder(" where b.tenant_id = ?");
    var binds = new ArrayList<Object>();
    binds.add(currentTenant());
    if (shopId != null) {
      where.append(" and b.shop_id = ?");
      binds.add(shopId);
    }
    if (type != null) {
      where.append(" and b.tax_type = ?");
      binds.add(type.name());
    }
    if (jurisdictionCode != null && !jurisdictionCode.isBlank()) {
      where.append(" and b.jurisdiction_code = ?");
      binds.add(jurisdictionCode.trim().toUpperCase(Locale.ROOT));
    }
    if (status != null && !status.isBlank()) {
      where.append(" and b.status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (b.shop_code ilike ? or b.shop_name ilike ? or b.jurisdiction_code ilike ? or b.tax_code ilike ? or b.note ilike ?)");
      var like = "%" + keyword.trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }

    var total =
        sql.fetchOne("select count(*) from finance_tax_shop_binding b" + where, binds.toArray())
            .get(0, Long.class);
    var activeCount =
        sql.fetchOne(
                "select count(*) from finance_tax_shop_binding where tenant_id = ? and status = 'ACTIVE'",
                currentTenant())
            .get(0, Long.class);

    var limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var offset = Math.max(page, 0) * limit;
    binds.add(limit);
    binds.add(offset);
    var rows =
        sql.fetch(
            "select b.id,b.shop_id,b.shop_code,b.shop_name,b.tax_type,b.jurisdiction_code,b.tax_code,"
                + " b.effective_from,b.effective_to,b.status,b.version,b.supersedes_id,b.note,b.created_at,"
                + " r.jurisdiction_name,r.rate"
                + " from finance_tax_shop_binding b"
                + " join finance_tax_rate r on r.tenant_id = b.tenant_id and r.tax_type = b.tax_type"
                + "   and r.jurisdiction_code = b.jurisdiction_code and r.tax_code = b.tax_code"
                + where
                + " order by b.shop_code,b.tax_type,b.jurisdiction_code,b.version desc limit ? offset ?",
            binds.toArray());
    var items = new ArrayList<FinanceTaxBindingRow>();
    for (var row : rows) items.add(bindingRow(row));
    return new FinanceTaxBindingPage(items, Math.max(page, 0), limit, total, activeCount);
  }

  /**
   * 绑定 / 换绑（按「店铺 × 税种 × 辖区」upsert：同链已有生效绑定 ⇒ **追加新版本**）。
   *
   * <p>🔴 辖区码同样要过上游受控码表校验（不建影子码表、不跨库 FK）。
   */
  public FinanceTaxBindingRow upsertBinding(
      FinanceTaxBindingInput input, AccessService.Access actor, String bearer) {
    var type = TaxType.parse(input.taxType());
    if (type == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "税种只能是 SALES_TAX（目的国/地区销售税）/ VAT / IOSS / WITHHOLDING（代扣代缴）");
    }
    if (input.shopId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定店铺站点");
    }
    if (input.effectiveFrom() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "必须给出生效时间：没有生效时间的绑定等于没有");
    }
    var taxCode = input.taxCode() == null ? "" : input.taxCode().trim();
    if (taxCode.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出税码");
    }

    // 税码从「该辖区该税种下已有的税率记录」里选 —— 绑定的是**一条税档**。
    var tenant = currentTenant();
    var rates =
        sql.fetch(
            "select distinct jurisdiction_code, jurisdiction_name, country_code from finance_tax_rate"
                + " where tenant_id = ? and tax_type = ? and tax_code = ? order by jurisdiction_code",
            tenant,
            type.name(),
            taxCode);
    if (rates.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "税种 " + type.label() + " 下还没有税码 " + taxCode + " 的税率记录 —— "
              + "请先登记税率再绑定（绑到一个没有税率的税档上，取税率时必然落空）");
    }
    var jurisdictionCode = input.jurisdictionCode();
    if (jurisdictionCode == null || jurisdictionCode.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出辖区（目的国/地区）");
    }
    var jurisdiction = requireJurisdiction(bearer, jurisdictionCode.trim().toUpperCase(Locale.ROOT));

    // 绑定的（辖区 × 税码）必须真的落在本租户已登记的税率上（DB 层也有守卫）。
    var band =
        sql.fetchOne(
            "select jurisdiction_code from finance_tax_rate where tenant_id = ? and tax_type = ?"
                + " and jurisdiction_code = ? and tax_code = ? limit 1",
            tenant,
            type.name(),
            jurisdiction.code(),
            taxCode);
    if (band == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "辖区 " + jurisdiction.code() + " 的税种 " + type.label() + " 下没有税码 " + taxCode
              + " 的税率记录 —— 请先登记税率再绑定");
    }

    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var last =
        sql.fetchOne(
            "select id,version,effective_from from finance_tax_shop_binding"
                + " where tenant_id = ? and shop_id = ? and tax_type = ? and jurisdiction_code = ?"
                + " order by version desc limit 1",
            tenant,
            input.shopId(),
            type.name(),
            jurisdiction.code());

    UUID supersedesId = null;
    int version = 1;
    if (last != null) {
      supersedesId = last.get("id", UUID.class);
      version = last.get("version", Integer.class) + 1;
      var lastFrom = last.get("effective_from", OffsetDateTime.class);
      if (!input.effectiveFrom().isAfter(lastFrom)) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "新版本的生效时间必须晚于上一版本（"
                + lastFrom
                + "），否则历史订单会被追溯改写");
      }
      sql.execute(
          "update finance_tax_shop_binding set status = 'SUPERSEDED', effective_to = ?::timestamptz,"
              + " updated_at = ?::timestamptz where tenant_id = ? and id = ?",
          input.effectiveFrom(),
          now,
          tenant,
          supersedesId);
    }

    var id = UUID.randomUUID();
    sql.execute(
        "insert into finance_tax_shop_binding(id,tenant_id,shop_id,shop_code,shop_name,tax_type,"
            + " jurisdiction_code,tax_code,effective_from,effective_to,status,version,supersedes_id,note,"
            + " created_by,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?::timestamptz,null,'ACTIVE',?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        tenant,
        input.shopId(),
        input.shopCode() == null ? "" : input.shopCode().trim(),
        input.shopName() == null ? "" : input.shopName().trim(),
        type.name(),
        jurisdiction.code(),
        taxCode,
        input.effectiveFrom(),
        version,
        supersedesId,
        input.note() == null ? "" : input.note().trim(),
        actor == null ? null : actor.userId(),
        now,
        now);
    return binding(id);
  }

  /** 绑定版本历史（新到旧）。 */
  public List<FinanceTaxBindingRow> bindingVersions(UUID shopId, String taxType, String jurisdictionCode) {
    if (shopId == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出店铺站点");
    }
    var type = TaxType.parse(taxType);
    if (type == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出税种");
    }
    if (jurisdictionCode == null || jurisdictionCode.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出辖区");
    }
    var rows =
        sql.fetch(
            "select b.id,b.shop_id,b.shop_code,b.shop_name,b.tax_type,b.jurisdiction_code,b.tax_code,"
                + " b.effective_from,b.effective_to,b.status,b.version,b.supersedes_id,b.note,b.created_at,"
                + " r.jurisdiction_name,r.rate"
                + " from finance_tax_shop_binding b"
                + " join finance_tax_rate r on r.tenant_id = b.tenant_id and r.tax_type = b.tax_type"
                + "   and r.jurisdiction_code = b.jurisdiction_code and r.tax_code = b.tax_code"
                + " where b.tenant_id = ? and b.shop_id = ? and b.tax_type = ? and b.jurisdiction_code = ?"
                + " order by b.version desc",
            currentTenant(),
            shopId,
            type.name(),
            jurisdictionCode.trim().toUpperCase(Locale.ROOT));
    var items = new ArrayList<FinanceTaxBindingRow>();
    for (var row : rows) items.add(bindingRow(row));
    return items;
  }

  // ================================================================ 州销售税（channel 只读引用）

  /**
   * 州销售税规则（**只读**引用 channel 16.3.2.3）。
   *
   * <p>🔴 channel 读不到一律 503 并写明「读不到」—— 绝不返回空集合冒充「没有州销售税」。
   */
  public FinanceTaxStateRateRules stateSalesTax(
      String bearer, String stateCode, String status, int page, int size) {
    var upstream = channelTax.stateRateRules(bearer, stateCode, status, page, size);
    var items = new ArrayList<FinanceTaxStateRateRule>();
    for (var r : upstream.items()) {
      items.add(
          new FinanceTaxStateRateRule(
              ChannelTaxPort.ruleId(r.id()),
              r.stateCode(),
              r.stateName(),
              r.ruleVersion() == null ? 0 : r.ruleVersion(),
              r.effectiveFrom() == null ? null : r.effectiveFrom().toString(),
              r.effectiveTo() == null ? null : r.effectiveTo().toString(),
              r.baseRate(),
              r.thresholdAmount(),
              r.exemptionAmount(),
              r.marketplaceFacilitator(),
              r.filingCycle(),
              r.status(),
              r.note()));
    }
    return new FinanceTaxStateRateRules(
        items,
        upstream.total() == null ? items.size() : upstream.total(),
        true,
        ChannelTaxPort.STATE_SALES_TAX_OWNER_MODULE,
        "channel",
        "🔴 美国州级销售税归 channel 16.3.2.3 维护（sales_tax_rate_rule），本模块**只读引用**，"
            + "不重复建表、不改写它的数据。");
  }

  // ================================================================ 内部工具

  /** 辖区码必须真实落在上游受控码表里，且**不是**美国辖区。 */
  private FinanceTaxJurisdictionOption requireJurisdiction(String bearer, String code) {
    var upstream = channelTax.jurisdictions(bearer);
    for (var j : upstream) {
      if (j.code() == null || !j.code().equalsIgnoreCase(code)) continue;
      var unusable = unusableReason(j);
      if (unusable != null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, unusable);
      }
      return new FinanceTaxJurisdictionOption(
          j.code(),
          j.name(),
          j.region(),
          j.countryCode(),
          j.subdivisionCode(),
          j.taxRegime(),
          j.vatRequired(),
          j.requiresEpr(),
          j.defaultFilingCycle(),
          j.derivationScope(),
          true,
          "");
    }
    throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "辖区码 " + code + " 不在上游受控辖区码表里（channel 16.3.1.1）—— 辖区码不是自由文本");
  }

  /** 税率必须是 0 ~ 1 的小数率，且精度不超过 6 位小数（**不静默四舍五入**）。 */
  private BigDecimal requireRate(BigDecimal rate) {
    if (rate == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出税率（小数率，例如 0.19 表示 19%）");
    }
    if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "税率必须在 0 ~ 1 之间（0.19 = 19%）");
    }
    if (rate.stripTrailingZeros().scale() > RATE_SCALE) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "税率最多 6 位小数（精度 1e-6）；更多位会被静默四舍五入，请自己先定好");
    }
    return rate.stripTrailingZeros().scale() <= 0 ? rate.setScale(0) : rate;
  }

  /** 版本链的键：租户 × 税种 × 辖区 × 税码。 */
  private Object[] chainKey(UUID tenant, String taxType, String jurisdictionCode, String taxCode) {
    return new Object[] {tenant, taxType, jurisdictionCode, taxCode};
  }

  /** 某一刻生效的税率版本（至多一条 —— 数据层已钉死区间不许重叠）。 */
  private Record effectiveRate(UUID tenant, String taxType, String jurisdictionCode, String taxCode, OffsetDateTime at) {
    return sql.fetchOne(
        "select id,tax_type,jurisdiction_code,jurisdiction_name,country_code,tax_code,rate,"
            + " effective_from,effective_to,status,version,supersedes_id,note,created_by,created_at,updated_at"
            + " from finance_tax_rate where tenant_id = ? and tax_type = ? and jurisdiction_code = ?"
            + " and tax_code = ? and effective_from <= ?::timestamptz"
            + " and (effective_to is null or ?::timestamptz < effective_to)",
        tenant,
        taxType,
        jurisdictionCode,
        taxCode,
        at,
        at);
  }

  /** 某一刻生效的店铺绑定（至多一条）。 */
  private Record effectiveBinding(UUID tenant, UUID shopId, String taxType, String jurisdictionCode, OffsetDateTime at) {
    return sql.fetchOne(
        "select id,shop_id,shop_code,shop_name,tax_type,jurisdiction_code,tax_code,effective_from,"
            + " effective_to,status,version,supersedes_id,note,created_at"
            + " from finance_tax_shop_binding where tenant_id = ? and shop_id = ? and tax_type = ?"
            + " and jurisdiction_code = ? and effective_from <= ?::timestamptz"
            + " and (effective_to is null or ?::timestamptz < effective_to)",
        tenant,
        shopId,
        taxType,
        jurisdictionCode,
        at,
        at);
  }

  /** 按 id 取一条税率记录。 */
  private FinanceTaxRateRow rate(UUID id) {
    var row =
        sql.fetchOne(
            "select id,tax_type,jurisdiction_code,jurisdiction_name,country_code,tax_code,rate,"
                + " effective_from,effective_to,status,version,supersedes_id,note,created_by,created_at,updated_at"
                + " from finance_tax_rate where tenant_id = ? and id = ?",
            currentTenant(),
            id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "税率记录不存在或不属于本租户");
    }
    return rateRow(row);
  }

  /** 按 id 取一条绑定。 */
  private FinanceTaxBindingRow binding(UUID id) {
    var row =
        sql.fetchOne(
            "select b.id,b.shop_id,b.shop_code,b.shop_name,b.tax_type,b.jurisdiction_code,b.tax_code,"
                + " b.effective_from,b.effective_to,b.status,b.version,b.supersedes_id,b.note,b.created_at,"
                + " r.jurisdiction_name,r.rate"
                + " from finance_tax_shop_binding b"
                + " join finance_tax_rate r on r.tenant_id = b.tenant_id and r.tax_type = b.tax_type"
                + "   and r.jurisdiction_code = b.jurisdiction_code and r.tax_code = b.tax_code"
                + " where b.tenant_id = ? and b.id = ?",
            currentTenant(),
            id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "店铺绑定不存在或不属于本租户");
    }
    return bindingRow(row);
  }

  /** 版本链上的某一版。 */
  private FinanceTaxRateRow versionRow(String taxType, String jurisdictionCode, String taxCode, int version) {
    if (jurisdictionCode == null || jurisdictionCode.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出辖区");
    }
    if (taxCode == null || taxCode.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出税码");
    }
    var row =
        sql.fetchOne(
            "select id,tax_type,jurisdiction_code,jurisdiction_name,country_code,tax_code,rate,"
                + " effective_from,effective_to,status,version,supersedes_id,note,created_by,created_at,updated_at"
                + " from finance_tax_rate where tenant_id = ? and tax_type = ? and jurisdiction_code = ?"
                + " and tax_code = ? and version = ?",
            currentTenant(),
            taxType,
            jurisdictionCode.trim().toUpperCase(Locale.ROOT),
            taxCode.trim(),
            version);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "该版本不存在或不属于本租户：" + taxType + " / " + jurisdictionCode + " / " + taxCode + " / 版本 " + version);
    }
    return rateRow(row);
  }

  /** 一行 → 税率记录。 */
  private FinanceTaxRateRow rateRow(Record row) {
    var type = TaxType.parse(row.get("tax_type", String.class));
    var status = TaxRateStatus.parse(row.get("status", String.class));
    return new FinanceTaxRateRow(
        row.get("id", UUID.class),
        row.get("tax_type", String.class),
        type == null ? row.get("tax_type", String.class) : type.label(),
        row.get("jurisdiction_code", String.class),
        row.get("jurisdiction_name", String.class),
        row.get("country_code", String.class),
        row.get("tax_code", String.class),
        row.get("rate", BigDecimal.class),
        row.get("effective_from", OffsetDateTime.class),
        row.get("effective_to", OffsetDateTime.class),
        row.get("status", String.class),
        status == null ? row.get("status", String.class) : status.label(),
        row.get("version", Integer.class),
        row.get("supersedes_id", UUID.class),
        row.get("note", String.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  /** 一行 → 店铺绑定。 */
  private FinanceTaxBindingRow bindingRow(Record row) {
    var type = TaxType.parse(row.get("tax_type", String.class));
    var status = TaxRateStatus.parse(row.get("status", String.class));
    return new FinanceTaxBindingRow(
        row.get("id", UUID.class),
        row.get("shop_id", UUID.class),
        row.get("shop_code", String.class),
        row.get("shop_name", String.class),
        row.get("tax_type", String.class),
        type == null ? row.get("tax_type", String.class) : type.label(),
        row.get("jurisdiction_code", String.class),
        row.get("jurisdiction_name", String.class),
        row.get("tax_code", String.class),
        row.get("rate", BigDecimal.class),
        row.get("effective_from", OffsetDateTime.class),
        row.get("effective_to", OffsetDateTime.class),
        row.get("status", String.class),
        status == null ? row.get("status", String.class) : status.label(),
        row.get("version", Integer.class),
        row.get("supersedes_id", UUID.class),
        row.get("note", String.class),
        row.get("created_at", OffsetDateTime.class));
  }

  /** 版本对比的一行差异。 */
  private FinanceTaxRateDiff diff(String field, String fieldLabel, Object from, Object to) {
    var left = display(from);
    var right = display(to);
    return new FinanceTaxRateDiff(field, fieldLabel, left, right, !left.equals(right));
  }

  /**
   * 差异值的展示形态。
   *
   * <p>🔴 {@code numeric(9,6)} 读出来是 {@code 0.190000} —— 直接展示会让人以为
   * 系统存了 6 位有效数字；去掉无意义的尾零才是「用户录的那个数」。
   */
  private String display(Object value) {
    if (value == null) return "";
    if (value instanceof BigDecimal decimal) {
      return decimal.stripTrailingZeros().toPlainString();
    }
    return String.valueOf(value);
  }

  /** 当前租户（由 backend/libraries/tenancy 在事务里绑定的 app.current_tenant）。 */
  private UUID currentTenant() {
    var raw =
        sql.fetchOne("select nullif(current_setting('app.current_tenant', true), '')::uuid as t")
            .get("t", UUID.class);
    if (raw == null) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "租户上下文缺失：税率配置必须在绑定租户的事务里执行");
    }
    return raw;
  }
}
