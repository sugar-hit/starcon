package com.starcon.catalog;

import static com.starcon.catalog.ExportDataSource.*;
import static com.starcon.catalog.ExportModels.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * 6.1.1.1 供应商列表的导出数据源（上游列表 = 本模块的 {@code GET /catalog/supply/vendors}）。
 *
 * <p><b>为什么导出挂在 3.8.2.1 的注册表上，而不是在本模块另开一个 {@code GET /export}</b>：
 * 导出在本仓库里是<b>唯一一条</b>通道，它自带字段白名单、排序还原、异步执行、有效期与审计，
 * 而且联系方式（EMAIL / PHONE）是 {@code pii=true} 的敏感字段 —— 勾选它们必须显式授权并填写用途。
 * 另开一个端点等于造出第二条**没有 PII 授权、没有审计**的导出通道，而那正是数据合规里单独成条的风险。
 *
 * <p><b>字段的来源分三段，与列表页同一纪律</b>：编码 / 名称 / 联系方式 / 主体状态读自 3.6.1.1，
 * 合作等级 / 账期 / 结算方式读自 3.6.1.2（<b>只读</b>），合作状态与主供类目来自本模块自有的 vendor_supply_profile。
 * 导出文件是<b>快照</b>，不提供任何写回路径 —— 它绝不可能成为「同一实体两处可编辑」的第二个编辑入口。
 *
 * <p><b>指标口径与列表页一致</b>：供货 SKU 数 = 有效报价覆盖的不同 SKU 数；采购金额取金额最大的那个币种
 * （跨币种不可加，规则 R4）；准时交付率与质量合格率按<b>最近 {@link VendorListRules#DEFAULT_METRIC_WINDOW_DAYS} 天</b>统计，
 * 分母为 0 时导出**空单元格**而不是 0%（规则 R3：「还没交货过」与「交货全部延期」是相反的两件事）。
 *
 * <p><b>筛选只开放 {@link ExportFilterInput} 上真实存在的字段</b>：q（关键词）/ status（合作状态）/ category（主供类目）。
 * 刻意<b>不</b>映射主体状态与合作等级 —— {@code ExportFilterInput} 是 3.8.2.1 的共享 record，
 * 给它加字段是另一个模块的写入所有权；把「主体状态」硬塞进 {@code roleType} 或 {@code primary} 那种字段能跑通，
 * 但导出的筛选语义会与字段名对不上，用户按下「已拉黑」导出时拿到的是别的东西。
 */
@Component
class VendorListExportDataSource extends SqlExportDataSource {
  /** 导出窗口右端（含当天）。取数据库当前日期减 0 天。 */
  private static final int WINDOW_DAYS = VendorListRules.DEFAULT_METRIC_WINDOW_DAYS;

  private static final List<ExportColumn> COLUMNS =
      List.of(
          // ---- 读自 3.6.1.1 party（本模块只读）
          new ExportColumn("PARTY_CODE", "供应商编码", false, r -> r.get("party_code", String.class)),
          new ExportColumn("PARTY_NAME", "供应商名称", false, r -> r.get("party_name", String.class)),
          new ExportColumn("PARTY_TYPE", "主体类型", false, r -> r.get("party_type", String.class)),
          new ExportColumn("COUNTRY", "注册国", false, r -> r.get("country_code", String.class)),
          // ↓↓↓ 两个敏感字段：导出必须显式授权（piiPurpose）并写进 export_job 的三列
          new ExportColumn("EMAIL", "邮箱", true, r -> r.get("email", String.class)),
          new ExportColumn("PHONE", "电话", true, r -> r.get("phone", String.class)),
          // ↑↑↑
          new ExportColumn("WEBSITE", "官网", false, r -> r.get("website", String.class)),
          new ExportColumn("PARTY_STATUS", "主体状态", false, r -> r.get("party_status", String.class)),
          new ExportColumn("STATUS_REASON", "状态理由", false, r -> r.get("status_reason", String.class)),
          // ---- 读自 3.6.1.2 party_supplier_profile（本模块只读，规则 R1）
          new ExportColumn(
              "COOPERATION_LEVEL", "合作等级", false, r -> r.get("cooperation_level", String.class)),
          new ExportColumn(
              "PAYMENT_TERMS_DAYS", "账期(天)", false, r -> text(r.get("payment_terms_days", Integer.class))),
          new ExportColumn(
              "SETTLEMENT_METHOD", "结算方式", false, r -> r.get("settlement_method", String.class)),
          // ---- 本模块自有（vendor_supply_profile）
          new ExportColumn("SUPPLY_STATUS", "合作状态(采购侧)", false,
              r -> r.get("supply_status", String.class)),
          new ExportColumn("CATEGORY_NAME", "主供类目", false, r -> r.get("category_name", String.class)),
          // ---- 四个关键指标（列头写清统计窗口：跨窗口比较两个数没有意义）
          new ExportColumn(
              "SUPPLY_SKU_COUNT", "供货SKU数", false, r -> text(r.get("sku_count", Long.class))),
          new ExportColumn(
              "PURCHASE_AMOUNT", "采购金额累计(最近" + WINDOW_DAYS + "天)", false,
              r -> text(r.get("purchase_amount", BigDecimal.class))),
          new ExportColumn(
              "PURCHASE_CURRENCY", "采购金额币种", false, r -> r.get("purchase_amount_currency", String.class)),
          new ExportColumn(
              "ON_TIME_RATE", "准时交付率%(最近" + WINDOW_DAYS + "天)", false,
              r -> text(r.get("on_time_rate_percent", BigDecimal.class))),
          new ExportColumn(
              "QUALITY_RATE", "质量合格率%(最近" + WINDOW_DAYS + "天)", false,
              r -> text(r.get("quality_rate_percent", BigDecimal.class))),
          new ExportColumn("ROLE_ID", "供应商角色ID", false, r -> text(r.get("role_id", java.util.UUID.class))));

  private static final List<ExportSort> SORTS =
      List.of(
          new ExportSort("partyCode", "供应商编码", "p.code"),
          new ExportSort("partyName", "供应商名称", "p.name"),
          new ExportSort("supplyStatus", "合作状态", "vsp.supply_status"),
          new ExportSort("cooperationLevel", "合作等级", "psp.cooperation_level"),
          new ExportSort("supplySkuCount", "供货SKU数", "coalesce(q.sku_count,0)"),
          new ExportSort("purchaseAmount", "采购金额累计", "coalesce(amt.purchase_amount,0)"),
          new ExportSort("onTimeRate", "准时交付率", "on_time_rate_percent"),
          new ExportSort("qualityRate", "质量合格率", "quality_rate_percent"),
          new ExportSort("lastDeliveryAt", "最近到货", "dv.last_delivery_at"));

  /**
   * 筛选词表：key 与 {@link ExportFilterInput} 的字段名**逐字对应**。
   *
   * <p>服务层据此拒绝「不属于本数据源」的筛选条件 —— 静默忽略会让用户以为筛过了。
   */
  private static final List<ExportFilterInfo> FILTERS =
      List.of(
          new ExportFilterInfo("q", "关键词（编码 / 名称 / 邮箱 / 电话）", "TEXT", true, ""),
          new ExportFilterInfo(
              "status", "合作状态（采购侧）", "ENUM", true,
              "取值：PENDING_REVIEW / ACTIVE / SUSPENDED / TRIAL / TERMINATED"),
          new ExportFilterInfo(
              "category", "主供类目（名称或编码）", "TEXT", true,
              "刻意不给主体状态与合作等级两个筛选：ExportFilterInput 是 3.8.2.1 的共享 record，"
                  + "给它加字段属于另一个模块的写入所有权；硬塞进别的字段能跑通但语义对不上。"));

  VendorListExportDataSource(DSLContext sql) {
    super(sql);
  }

  @Override
  public String key() {
    return "VENDOR_LIST";
  }

  @Override
  public String label() {
    return "供应商列表";
  }

  @Override
  public String upstreamModule() {
    return "6.1.1.1 供应商列表";
  }

  @Override
  public String permission() {
    // 与 VendorListController 同一口径：供应商是商品主数据域里往来单位档案的下半部分。
    // 新造一套 catalog.party.* 词表要改 identity 服务的迁移，那是另一个服务的写入所有权。
    return "catalog.spu.read";
  }

  @Override
  public List<ExportColumn> columns() {
    return COLUMNS;
  }

  @Override
  public List<ExportSort> sorts() {
    return SORTS;
  }

  @Override
  public String defaultSortKey() {
    return "partyCode";
  }

  @Override
  public List<ExportFilterInfo> filters() {
    return FILTERS;
  }

  @Override
  public int maxRows() {
    return 50_000;
  }

  @Override
  public String from() {
    return " from party_role r"
        + " join party p on p.tenant_id=r.tenant_id and p.id=r.party_id"
        + " left join vendor_supply_profile vsp on vsp.tenant_id=r.tenant_id and vsp.role_id=r.id"
        + " left join party_supplier_profile psp on psp.tenant_id=r.tenant_id and psp.role_id=r.id"
        + " left join product_category pc on pc.tenant_id=vsp.tenant_id and pc.id=vsp.category_id"
        + " left join lateral ("
        + "   select count(distinct sq.sku_id) as sku_count from supplier_quote sq"
        + "   where sq.tenant_id=r.tenant_id and sq.supplier_role_id=r.id and sq.status='ACTIVE'"
        + " ) q on true"
        + " left join lateral ("
        + "   select count(*) as delivery_count,"
        + "          count(*) filter (where vd.received_date <= vd.due_date) as on_time_count,"
        + "          max(vd.received_date)::text as last_delivery_at"
        + "   from vendor_delivery_record vd"
        + "   where vd.tenant_id=r.tenant_id and vd.role_id=r.id and not vd.is_void"
        + "     and vd.received_date between ? and ?"
        + " ) dv on true"
        + " left join lateral ("
        + "   select sum(vq.inspected_qty) as inspected_qty, sum(vq.qualified_qty) as qualified_qty"
        + "   from vendor_quality_record vq"
        + "   where vq.tenant_id=r.tenant_id and vq.role_id=r.id and not vq.is_void"
        + "     and vq.inspected_at >= ?::timestamptz and vq.inspected_at <= ?::timestamptz"
        + " ) qq on true"
        + " left join lateral ("
        + "   select coalesce(max(t.amount),0) as purchase_amount,"
        + "          (array_agg(t.currency order by t.amount desc))[1] as purchase_amount_currency"
        + "   from ("
        + "     select vda.currency, sum(vda.amount) as amount from vendor_delivery_record vda"
        + "     where vda.tenant_id=r.tenant_id and vda.role_id=r.id and not vda.is_void"
        + "       and vda.received_date between ? and ?"
        + " group by vda.currency) t) amt on true";
  }

  @Override
  protected String selectList(List<ExportColumn> columns) {
    // 与 VendorListService 的 METRICS 同一口径，但**在这里也要算一遍比率**：
    // 导出的 CSV 里只有比率，没有分子分母。case when ... is null then null 保证分母为 0 时是空单元格（规则 R3）。
    return "p.code as party_code,p.name as party_name,p.party_type,p.country_code,p.email,p.phone,"
        + "p.website,p.status as party_status,p.status_reason,"
        + "psp.cooperation_level,psp.payment_terms_days,psp.settlement_method,"
        + "coalesce(vsp.supply_status,'PENDING_REVIEW') as supply_status,"
        + "pc.name as category_name,r.id as role_id,"
        + "coalesce(q.sku_count,0) as sku_count,"
        + "amt.purchase_amount,amt.purchase_amount_currency,"
        + "case when coalesce(dv.delivery_count,0)=0 then null"
        + "     else round(100.0 * coalesce(dv.on_time_count,0) / dv.delivery_count, 2) end"
        + "  as on_time_rate_percent,"
        + "case when coalesce(qq.inspected_qty,0)=0 then null"
        + "     else round(100.0 * qq.qualified_qty / qq.inspected_qty, 2) end"
        + "  as quality_rate_percent";
  }

  /**
   * 筛选条件。
   *
   * <p><b>指标窗口的 5 个绑定位必须排在筛选条件之前</b>：它们在 {@link #from()} 的 LATERAL 里，
   * 而 jOOQ 按 SQL 文本里 {@code ?} 出现的先后绑定。顺序错了不会编译失败、只会让窗口拿到筛选条件的值 ——
   * 那会报一个类型错误，或者更糟：拿到一个恰好能转的日期，静默筛掉一大半数据。
   */
  @Override
  public void conditions(ExportFilterInput spec, List<String> where, List<Object> binds) {
    LocalDate today = LocalDate.now();
    binds.add(today.minusDays(WINDOW_DAYS)); // 到货窗口左端
    binds.add(today); // 到货窗口右端
    binds.add(today.minusDays(WINDOW_DAYS).atStartOfDay().atOffset(java.time.ZoneOffset.UTC));
    binds.add(today.plusDays(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC));
    binds.add(today.minusDays(WINDOW_DAYS)); // 金额窗口左端
    binds.add(today); // 金额窗口右端

    if (spec == null) return;
    if (notBlank(spec.q())) {
      where.add(
          "(p.code ilike ? escape '!' or p.name ilike ? escape '!'"
              + " or coalesce(p.email,'') ilike ? escape '!'"
              + " or coalesce(p.phone,'') ilike ? escape '!')");
      for (int i = 0; i < 4; i++) binds.add("%" + escape(spec.q().trim()) + "%");
    }
    // ExportFilterInput.status 是 List<String>（SPU / SKU 那几个数据源用多选）。
    // 本数据源的合作状态是单选：多于一个值时给可读原因，而不是悄悄取第一个。
    if (spec.status() != null && !spec.status().isEmpty()) {
      CatalogService.check(
          spec.status().size() == 1,
          "供应商列表导出的合作状态筛选只支持单选（ExportFilterInput.status 是多选字段）");
      var value = spec.status().get(0).trim().toUpperCase(java.util.Locale.ROOT);
      var problem = VendorListRules.supplyStatusProblem(value);
      CatalogService.check(problem == null, problem);
      where.add("coalesce(vsp.supply_status,'PENDING_REVIEW') = ?");
      binds.add(value);
    }
    if (notBlank(spec.category())) {
      var value = spec.category().trim();
      where.add("(pc.name ilike ? escape '!' or pc.code ilike ? escape '!')");
      var like = "%" + escape(value) + "%";
      binds.add(like);
      binds.add(like);
    }
  }

  @Override
  public String orderBy(ExportSort sort, boolean desc) {
    // 恒定决胜列（p.id）保证分批之间稳定：漏掉它时两批可能在同值行上重复或漏掉。
    return " order by " + sort.column() + (desc ? " desc" : " asc") + ", p.code, p.id";
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String text(Object value) {
    return value == null ? "" : value.toString();
  }
}
