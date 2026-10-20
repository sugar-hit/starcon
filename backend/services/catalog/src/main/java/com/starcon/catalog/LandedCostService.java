package com.starcon.catalog;

import static com.starcon.catalog.LandedCostModels.*;

import com.starcon.catalog.LandedCostModels.LandedCostComponent;
import com.starcon.catalog.LandedCostPort.DutyView;
import com.starcon.catalog.LandedCostPort.LandedCostUnavailableException;
import com.starcon.catalog.LandedCostPort.ShipmentLineView;
import com.starcon.catalog.LandedCostPort.ShipmentView;
import com.starcon.catalog.SkuCostCompositionRules.DeliveryLot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.2.1.2 到岸成本：「货到目标仓可售」的总成本。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：计算"货到目标仓可售"的总成本 = 采购价 + 国际头程 + 关税 + 清关 + 目的地操作费 + 尾程预备，用于海外仓/FBA 定价。
 *
 * **输入**：批次/货件的采购明细；头程费用（10 物流与头程）；税率与清关费参数（16 合规）。
 *
 * **输出**：按 SKU/批次的到岸单位成本；不同目的地的成本对比。
 *
 * **关系**：上游 = 6 采购、10 头程、16 税务参数；下游 = 5.3.1.1。**到岸成本与仓库成本是两个口径**（海外仓 vs 自发货），定价时必须选对，否则自发货商品按海运成本定价会低估。
 *
 * </blockquote>
 *
 * <h2>本类的七条纪律</h2>
 *
 * <ol>
 *   <li><b>🔴 口径写在脸上</b>。每份响应都带 {@link LandedCostBasis}：算了哪六项、
 *       **明确不算**哪两项（仓储费 / 尾程派送费实际值）。到岸成本与仓库成本是两个口径，
 *       定价时必须选对，否则自发货商品按海运成本定价会低估。
 *   <li><b>🔴 六个分量逐项落成事实</b>。每一项都带来源单号；拿不到的标
 *       {@code available=false} + {@code ownerModule} + {@code impact}，
 *       <b>绝不用 0 假装齐了</b> —— 用 0 填关税会让到岸成本**系统性低估**，
 *       而页面上看不出任何异常。
 *   <li><b>🔴 跨服务只读</b>。头程与关税来自 warehouse（另一个数据库），
 *       经授权 HTTP 端点读取并**透传调用方的 Authorization 头**；
 *       本库不存它们的副本（V223 零个外键指向 warehouse 的表）。
 *   <li><b>🔴 跨币种绝不相加</b>。本服务没有汇率表（汇率是 12 财务的资产），
 *       币种与本单不一致的金额**不折算、不进合计**，而是标成缺口并写明原因。
 *   <li><b>🔴 承担方决定算不算</b>。DAP 下关税由买方承担，卖方的到岸成本里
 *       <b>不能</b>算它 —— 算进去就是在给一个不属于自己的成本记账。
 *   <li><b>🔴 分摊逐字守恒</b>。批次级成本摊到 SKU 行后，各行之和**严格等于**来源总额
 *       （{@link LandedCostRules#allocateByQuantity} 的最大余数法 + DB 延迟约束双保险）。
 *   <li><b>租户只来自已认证成员关系</b>（{@link AccessService}），不读 {@code X-Tenant-Id} 头。
 *       每条 SQL 的 where 都带显式 {@code tenant_id=} 绑定，RLS 再兜一道。
 * </ol>
 */
@Service
public class LandedCostService {

  /** 租户上下文取法：RLS 已按它隔离，这里带上只是让执行计划走租户前缀的索引。 */
  private static final String TENANT = "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 台账页大小上限：与相邻模块一致，防止一次拉爆整个租户的数据。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 采购价口径窗口的默认跨度（天）。窗口太宽会把去年的价格拖进今天的到岸成本里。 */
  private static final int DEFAULT_WINDOW_DAYS = 90;

  private final DSLContext sql;
  private final LandedCostPort upstream;

  public LandedCostService(DSLContext sql, LandedCostPort upstream) {
    this.sql = sql;
    this.upstream = upstream;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：六个分量各自的来源、能不能取到、归谁管；成本口径；未交付的依赖。
   *
   * <p>页面上把它固定显示在顶部 —— 蓝图加粗句「到岸成本与仓库成本是两个口径」靠它落地。
   */
  public LandedCostCapabilities capabilities() {
    var components =
        List.of(
            new LandedCostComponentCapability(
                "PURCHASE_PRICE",
                "采购价",
                "CATALOG_VENDOR_DELIVERY",
                "FACT",
                true,
                "05 定价与价目（本服务）· 数据源 6.1.1.1 供应商列表的 vendor_delivery_record（V137）",
                "蓝图要求的是 06 采购的**实际成交价**；6.3 采购单当前未开发，本项口径改为 catalog 同库的"
                    + "**实际到货金额**（已到货事实）按移动加权派生。供应商报价（supplier_quote）"
                    + "只作参考，**不计入到岸成本**。",
                "该 SKU 在口径窗口内、按**本单币种**的有效到货记录加权平均单价 × 本行基础数量。",
                "逐条到货记录可按到货单号（delivery_ref）回溯。"),
            new LandedCostComponentCapability(
                "INTERNATIONAL_FREIGHT",
                "国际头程",
                "WAREHOUSE_FIRST_MILE",
                "FACT",
                true,
                "10 物流与头程（warehouse 服务）· first_mile_shipment（V80）",
                "跨服务只读，经 warehouse 的授权 HTTP 端点读取（AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图，"
                    + "本库不存头程副本）。**上游不可达时读端点返回 503**，不会用 0 冒充「没有头程成本」。",
                "发运单上的**运费 + 保险费**，按各行数量比例摊到 SKU 行上。",
                "来源单号是头程发运单号（shipment_code），可回溯到原始发运单。"),
            new LandedCostComponentCapability(
                "DUTY",
                "关税",
                "WAREHOUSE_DUTY_TAX",
                "FACT",
                true,
                "10.5.1.3 关税与税费（warehouse 服务）· duty_tax_estimate / _line（V90）",
                "蓝图写的是「税率与清关费参数（16 合规）」，而 16.3.1.1 税号与辖区**未开发** ⇒ "
                    + "税率由本租户在 10.5.1.3 维护。**这是本服务口径，不是 12 财务的核算结果**"
                    + "（12 财务的 finance 服务在本仓不存在）。",
                "关税估算单里该 SKU 的**关税**与**增值税/GST**分列存储：两者是三笔独立的钱，"
                    + "并进一起就答不出「哪部分是关税哪部分是税」。按承担方决定是否计入卖方的到岸成本。",
                "来源单号是关税估算单号（estimate_code）与报关单号（declaration_code）。"),
            new LandedCostComponentCapability(
                "CUSTOMS_CLEARANCE",
                "清关",
                "CATALOG_PARAMETER",
                "PARAMETER",
                true,
                "05 定价与价目（本服务）· landed_cost_parameter",
                "全仓**没有任何清关费事实**：warehouse 的 customs_declaration（V85）只有申报货值与汇率快照，"
                    + "**没有费用列**。所以清关是**参数**（租户按目的国 + 币种维护单件金额），"
                    + "可空 = 尚未维护，**不是 0**。显示 0.00 等于宣称「清关不花钱」。",
                "租户维护的单件清关费 × 本行基础数量。",
                "参数按 (分量, 目的国, 币种) 唯一，改动带版本号（乐观锁）。"),
            new LandedCostComponentCapability(
                "DESTINATION_HANDLING",
                "目的地操作费",
                "WAREHOUSE_FIRST_MILE",
                "FACT",
                true,
                "10 物流与头程（warehouse 服务）· first_mile_shipment.handling_cost（V80）",
                "发运单上登记的**操作费**。🔴 该列是 NOT NULL DEFAULT 0，**0 与「没登记」不可区分**，"
                    + "所以 0 不当成「这笔成本是 0」，而是标成缺口（归 10.4.1.1 头程发运单）。",
                "发运单上的操作费，按各行数量比例摊到 SKU 行上；风险承担方不是卖方时不计入。",
                "来源单号是头程发运单号（shipment_code）。"),
            new LandedCostComponentCapability(
                "LAST_MILE_RESERVE",
                "尾程预备",
                "CATALOG_PARAMETER",
                "PARAMETER",
                true,
                "05 定价与价目（本服务）· landed_cost_parameter",
                "🔴 **尾程预备 ≠ 尾程派送费**。尾程派送费是已发生的运费，归 10.2 运单 / 10.6.1.1 成本分析；"
                    + "本项是**定价用的预留**（reserve），由租户按目的国 + 币种维护单件金额。"
                    + "两者混为一谈，就等于把仓库成本偷偷塞进到岸成本。",
                "租户维护的单件尾程预留 × 本行基础数量。",
                "参数按 (分量, 目的国, 币种) 唯一，改动带版本号（乐观锁）。"));

    var gaps =
        List.of(
            new LandedCostGap(
                "6.3",
                "采购单",
                "06 采购与供应（trade / catalog）",
                "采购价只能用 catalog 同库的**实际到货金额**代替，"
                    + "无法定位到「本次发运具体是哪张采购单的那一批」——批次的采购溯源要等 6.3 交付。",
                "6.3 采购单列表 / 明细 / 收货单当前未开发。"),
            new LandedCostGap(
                "16.3.1.1",
                "税号与辖区",
                "16 合规与风控",
                "税率由本租户在 10.5.1.3 维护，**不是**官方税则；HS 归类与税率的准确性依赖人工维护。",
                "16.3.1.1 税号与辖区、16.3.2.1 VAT/IOSS 当前未开发。"),
            new LandedCostGap(
                "5.2.1.3",
                "成本分摊",
                "05 定价与价目（本服务）",
                "批次级成本只按**数量比例**摊到 SKU 行；按重量 / 体积 / 金额分摊、人工微调与规则版本历史"
                    + "尚未交付，混合货件的分摊结果可能不是业务想要的。",
                "5.2.1.3 成本分摊当前未开发。"),
            new LandedCostGap(
                "5.3.1.1",
                "利润测算器",
                "05 定价与价目（本服务）",
                "本模块只产出**到岸成本事实本身**，不产出利润、售价或保本价 —— 定价决策归 5.3.1.1。",
                "5.3.1.1 利润测算器当前未开发。"),
            new LandedCostGap(
                "12",
                "财务",
                "12 财务（finance 服务）",
                "本仓**没有 finance 服务**，因此：① 没有汇率表，跨币种金额不折算、不相加；"
                    + "② 采购价是本服务口径，不是 12 财务的成本核算结果。",
                "backend/services 下没有 finance 服务。"),
            new LandedCostGap(
                "10.6.1.1",
                "成本分析",
                "10 物流与头程（channel 服务）",
                "仓储费与尾程派送费**不在到岸成本里**：它们归 10.6.1.1 的仓库成本口径。"
                    + "自发货商品按海运到岸成本定价会低估 —— 定价时必须选对口径。",
                "10.6.1.1 成本分析已在 channel 交付，两个口径不合并成一个「总成本」数字。"));

    return new LandedCostCapabilities(
        basis(),
        components,
        gaps,
        List.of(LandedCostRules.ALLOCATION_QUANTITY_PROPORTIONAL),
        "上游 = 6 采购（实际到货金额，同库）· 10 头程与 10.5.1.3 关税（warehouse，跨服务只读）· "
            + "16 税务与清关费参数（未交付 ⇒ 税率由租户在 10.5.1.3 维护、清关费由本服务参数维护）。",
        "下游 = 5.3.1.1 利润测算器（**未交付**）：本模块只产出到岸成本事实本身，不产出售价。",
        "金额一律 BigDecimal + NUMERIC(19,4)，数量 NUMERIC(19,6)，全程显式刻度除法（禁浮点、禁整数除法）。",
        "可写动作只有两个：计算 / 重算一张到岸成本单，以及维护清关费与尾程预备两个参数。"
            + "分量本身**不可手工改** —— 它们要么是上游事实，要么是租户参数。");
  }

  /** 成本口径。随每一份响应返回。 */
  public LandedCostBasis basis() {
    return new LandedCostBasis(
        "LANDED_TO_TARGET_WAREHOUSE",
        "到岸成本（货到目标仓可售）",
        List.of(
            "PURCHASE_PRICE",
            "INTERNATIONAL_FREIGHT",
            "DUTY",
            "CUSTOMS_CLEARANCE",
            "DESTINATION_HANDLING",
            "LAST_MILE_RESERVE"),
        List.of("STORAGE_FEE", "LAST_MILE_DELIVERY_FEE"),
        "🔴 **到岸成本与仓库成本是两个口径**。到岸成本只到「货到目标仓可售」为止："
            + "**不含仓储费**（归 8 仓储作业 / 10.6.1.1 成本分析），"
            + "**不含尾程派送费实际值**（归 10.2 运单 / 10.6.1.1 成本分析）。"
            + "本模块的第六个分量是**尾程预备**（定价用的预留），不是尾程派送费本身。"
            + "自发货商品按海运到岸成本定价会低估 —— 定价时必须选对口径。");
  }

  // ================================================================ 列表

  /**
   * 到岸成本单台账：分页 + 关键词 / 目的国 / 币种 / 状态 / 计算时间窗口筛选。
   *
   * <p>列表**不实时连上游**（头程与关税在计算那一刻就取过并冻在分量里），
   * 所以这里永远是快的。
   */
  public LandedCostListPage list(LandedCostQuery query) {
    int page = Math.max(query.page(), 0);
    int size = Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);
    var where = new StringBuilder(" where s.tenant_id=" + TENANT);
    var params = new ArrayList<Object>();
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.append(
          " and (s.statement_code ilike ? or s.source_shipment_code ilike ? or s.destination_name ilike ?)");
      var keyword = "%" + query.keyword().trim() + "%";
      params.add(keyword);
      params.add(keyword);
      params.add(keyword);
    }
    if (query.destinationCountryCode() != null && !query.destinationCountryCode().isBlank()) {
      where.append(" and s.destination_country_code=?");
      params.add(query.destinationCountryCode().trim().toUpperCase(Locale.ROOT));
    }
    if (query.currency() != null && !query.currency().isBlank()) {
      where.append(" and s.currency=?");
      params.add(query.currency().trim().toUpperCase(Locale.ROOT));
    }
    if (query.status() != null && !query.status().isBlank()) {
      where.append(" and s.status=?");
      params.add(query.status().trim().toUpperCase(Locale.ROOT));
    }
    if (query.computedFrom() != null) {
      where.append(" and s.computed_at>=?::timestamptz");
      params.add(query.computedFrom());
    }
    if (query.computedTo() != null) {
      where.append(" and s.computed_at<=?::timestamptz");
      params.add(query.computedTo());
    }

    long total =
        sql.fetchOne("select count(*) from landed_cost_statement s" + where, params.toArray())
            .get(0, Long.class);
    var records =
        sql.fetch(
            "select s.id, s.statement_code, s.source_shipment_id, s.source_shipment_code,"
                + " s.destination_country_code, s.destination_name, s.transport_mode, s.incoterm,"
                + " s.risk_bearer, s.currency, s.cost_basis, s.allocation_method, s.status,"
                + " s.total_amount, s.total_quantity, s.total_line_count, s.computed_at, s.compute_count,"
                + " (select count(*) from landed_cost_component c"
                + "   where c.tenant_id=s.tenant_id and c.statement_id=s.id and c.included) as included_count,"
                + " (select count(*) from landed_cost_component c"
                + "   where c.tenant_id=s.tenant_id and c.statement_id=s.id and not c.available) as missing_count"
                + " from landed_cost_statement s"
                + where
                + " order by s.computed_at desc, s.id"
                + " limit ? offset ?",
            concatParams(params, size, page * size));

    var items = new ArrayList<LandedCostStatementRow>();
    for (var record : records) {
      items.add(statementRow(record));
    }
    return new LandedCostListPage(
        items,
        total,
        page,
        size,
        basis().note(),
        "到岸成本单 = 一张头程发运单（一个批次）。列表里的合计只包含**计入**的分量，"
            + "缺哪几项在同一行上写明 —— 覆盖范围不明的到岸成本不能用来算利润。");
  }

  private LandedCostStatementRow statementRow(org.jooq.Record record) {
    UUID id = record.get("id", UUID.class);
    return new LandedCostStatementRow(
        id,
        record.get("statement_code", String.class),
        record.get("source_shipment_id", UUID.class),
        record.get("source_shipment_code", String.class),
        record.get("destination_country_code", String.class),
        record.get("destination_name", String.class),
        record.get("transport_mode", String.class),
        record.get("incoterm", String.class),
        record.get("risk_bearer", String.class),
        record.get("currency", String.class),
        record.get("cost_basis", String.class),
        record.get("allocation_method", String.class),
        record.get("status", String.class),
        record.get("total_amount", BigDecimal.class),
        record.get("total_quantity", BigDecimal.class),
        record.get("total_line_count", Integer.class),
        record.get("included_count", Integer.class),
        record.get("missing_count", Integer.class),
        missingComponents(id),
        record.get("computed_at", OffsetDateTime.class),
        record.get("compute_count", Integer.class));
  }

  // ================================================================ 明细

  /** 到岸成本单明细：单头 + 逐 SKU 行 + 每行六个分量。 */
  public LandedCostDetail detail(UUID id) {
    var head =
        sql.fetchOne(
            "select s.id, s.statement_code, s.source_shipment_id, s.source_shipment_code,"
                + " s.destination_country_code, s.destination_name, s.transport_mode, s.incoterm,"
                + " s.risk_bearer, s.currency, s.cost_basis, s.allocation_method, s.status,"
                + " s.total_amount, s.total_quantity, s.total_line_count, s.computed_at, s.compute_count,"
                + " (select count(*) from landed_cost_component c"
                + "   where c.tenant_id=s.tenant_id and c.statement_id=s.id and c.included) as included_count,"
                + " (select count(*) from landed_cost_component c"
                + "   where c.tenant_id=s.tenant_id and c.statement_id=s.id and not c.available) as missing_count"
                + " from landed_cost_statement s where s.tenant_id="
                + TENANT
                + " and s.id=?",
            id);
    if (head == null) {
      throw missing("到岸成本单不存在");
    }
    var components = componentsOf(id);
    var lines = new ArrayList<LandedCostLineRow>();
    var lineRecords =
        sql.fetch(
            "select l.id, l.line_no, l.sku_id, l.sku_code, l.sku_name, l.base_unit,"
                + " l.quantity, l.base_quantity, l.total_amount, l.unit_landed_cost,"
                + " l.included_component_count, l.missing_component_count"
                + " from landed_cost_line l where l.tenant_id="
                + TENANT
                + " and l.statement_id=? order by l.line_no",
            id);
    for (var record : lineRecords) {
      UUID lineId = record.get("id", UUID.class);
      lines.add(
          new LandedCostLineRow(
              lineId,
              record.get("line_no", Integer.class),
              record.get("sku_id", UUID.class),
              record.get("sku_code", String.class),
              record.get("sku_name", String.class),
              record.get("base_unit", String.class),
              record.get("quantity", BigDecimal.class),
              record.get("base_quantity", BigDecimal.class),
              record.get("total_amount", BigDecimal.class),
              record.get("unit_landed_cost", BigDecimal.class),
              record.get("included_component_count", Integer.class),
              record.get("missing_component_count", Integer.class),
              missingComponentsOfLine(lineId),
              components.getOrDefault(lineId, List.of())));
    }
    var row = statementRow(head);
    return new LandedCostDetail(
        row.id(),
        row.statementCode(),
        row.sourceShipmentId(),
        row.sourceShipmentCode(),
        row.destinationCountryCode(),
        row.destinationName(),
        row.transportMode(),
        row.incoterm(),
        row.riskBearer(),
        row.currency(),
        row.costBasis(),
        row.allocationMethod(),
        row.status(),
        row.totalAmount(),
        row.totalQuantity(),
        row.totalLineCount(),
        row.includedComponentCount(),
        row.missingComponentCount(),
        row.missingComponents(),
        row.computedAt(),
        row.computeCount(),
        basis(),
        lines,
        "每一行的六个分量都带来源单号；缺口标 available=false 并点名归谁管、缺了会怎样。"
            + "**合计只包含计入的分量**，缺项不显示 0。");
  }

  /** 逐项可审阅：这张单上的每一个分量（真分页）。 */
  public LandedCostComponentPage components(UUID id, int page, int size) {
    if (!exists(id)) {
      throw missing("到岸成本单不存在");
    }
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    long total =
        sql.fetchOne(
                "select count(*) from landed_cost_component where tenant_id="
                    + TENANT
                    + " and statement_id=?",
                id)
            .get(0, Long.class);
    var records =
        sql.fetch(
            "select c.id, c.line_id, c.component_code, c.label, c.source_kind, c.source_module,"
                + " c.source_document_code, c.available, c.included, c.amount,"
                + " c.tax_duty_amount, c.tax_other_amount, c.tax_bearer,"
                + " c.owner_module, c.impact, c.note, l.base_quantity, l.sku_code, l.sku_name"
                + " from landed_cost_component c"
                + " join landed_cost_line l on l.tenant_id=c.tenant_id and l.id=c.line_id"
                + " where c.tenant_id="
                + TENANT
                + " and c.statement_id=? order by l.line_no, c.component_code limit ? offset ?",
            id,
            s,
            p * s);
    var items = new ArrayList<LandedCostComponentRow>();
    for (var record : records) {
      items.add(componentRow(record));
    }
    return new LandedCostComponentPage(
        items, total, p, s, "每一项都带来源单号与来源模块；缺口不显示 0。");
  }

  private Map<UUID, List<LandedCostComponentRow>> componentsOf(UUID statementId) {
    var records =
        sql.fetch(
            "select c.id, c.line_id, c.component_code, c.label, c.source_kind, c.source_module,"
                + " c.source_document_code, c.available, c.included, c.amount,"
                + " c.tax_duty_amount, c.tax_other_amount, c.tax_bearer,"
                + " c.owner_module, c.impact, c.note, l.base_quantity, l.sku_code, l.sku_name"
                + " from landed_cost_component c"
                + " join landed_cost_line l on l.tenant_id=c.tenant_id and l.id=c.line_id"
                + " where c.tenant_id="
                + TENANT
                + " and c.statement_id=? order by l.line_no, c.component_code",
            statementId);
    var out = new LinkedHashMap<UUID, List<LandedCostComponentRow>>();
    for (var record : records) {
      out.computeIfAbsent(record.get("line_id", UUID.class), k -> new ArrayList<>())
          .add(componentRow(record));
    }
    return out;
  }

  private LandedCostComponentRow componentRow(org.jooq.Record record) {
    var amount = record.get("amount", BigDecimal.class);
    var baseQuantity = record.get("base_quantity", BigDecimal.class);
    return new LandedCostComponentRow(
        record.get("component_code", String.class),
        record.get("label", String.class),
        record.get("source_kind", String.class),
        record.get("source_module", String.class),
        record.get("source_document_code", String.class),
        Boolean.TRUE.equals(record.get("available", Boolean.class)),
        Boolean.TRUE.equals(record.get("included", Boolean.class)),
        amount,
        LandedCostRules.unitAmount(amount, baseQuantity),
        record.get("tax_duty_amount", BigDecimal.class),
        record.get("tax_other_amount", BigDecimal.class),
        record.get("tax_bearer", String.class),
        record.get("owner_module", String.class),
        record.get("impact", String.class),
        record.get("note", String.class));
  }

  /** 这张单上缺了哪些分量（去重、保序）。 */
  private List<String> missingComponents(UUID statementId) {
    var records =
        sql.fetch(
            "select distinct c.component_code from landed_cost_component c"
                + " where c.tenant_id="
                + TENANT
                + " and c.statement_id=? and not c.available order by c.component_code",
            statementId);
    var out = new ArrayList<String>();
    for (var record : records) {
      out.add(record.get("component_code", String.class));
    }
    return out;
  }

  private List<String> missingComponentsOfLine(UUID lineId) {
    var records =
        sql.fetch(
            "select distinct c.component_code from landed_cost_component c"
                + " where c.tenant_id="
                + TENANT
                + " and c.line_id=? and not c.available order by c.component_code",
            lineId);
    var out = new ArrayList<String>();
    for (var record : records) {
      out.add(record.get("component_code", String.class));
    }
    return out;
  }

  // ================================================================ 目的地对比

  /**
   * 不同目的地的成本对比（蓝图输出之一）。
   *
   * <p>🔴 **必须指定一个 SKU**：不同 SKU 的单位成本不可比（一个是大件低值，一个是小件高值）。
   * 没指定就报 400，而不是给一个「所有 SKU 的平均到岸成本」—— 那个数没有意义。
   */
  public LandedCostComparison comparison(UUID skuId) {
    if (skuId == null) {
      throw badRequest(
          "目的地对比必须指定一个 SKU：不同 SKU 的单位成本不可比"
              + "（一个大件低值 SKU 和一个小件高值 SKU 平均出来的「到岸成本」没有意义）");
    }
    var sku =
        sql.fetchOne(
            "select s.id, s.code, s.name, s.base_unit from sku s where s.tenant_id="
                + TENANT
                + " and s.id=?",
            skuId);
    if (sku == null) {
      throw missing("SKU 不存在");
    }
    var records =
        sql.fetch(
            "select st.destination_country_code, max(st.destination_name) as destination_name,"
                + " st.currency, count(distinct st.id) as statement_count,"
                + " count(distinct st.id) filter (where l.missing_component_count = 0) as complete_count,"
                + " avg(l.unit_landed_cost) as avg_unit, min(l.unit_landed_cost) as min_unit,"
                + " max(l.unit_landed_cost) as max_unit"
                + " from landed_cost_line l"
                + " join landed_cost_statement st on st.tenant_id=l.tenant_id and st.id=l.statement_id"
                + " where l.tenant_id="
                + TENANT
                + " and l.sku_id=? and st.status<>'VOID'"
                + " group by st.destination_country_code, st.currency"
                + " order by st.destination_country_code, st.currency",
            skuId);
    var missing =
        sql.fetch(
            "select st.destination_country_code, st.currency, c.component_code"
                + " from landed_cost_component c"
                + " join landed_cost_line l on l.tenant_id=c.tenant_id and l.id=c.line_id"
                + " join landed_cost_statement st on st.tenant_id=l.tenant_id and st.id=l.statement_id"
                + " where l.tenant_id="
                + TENANT
                + " and l.sku_id=? and not c.available and st.status<>'VOID'",
            skuId);
    var missingByKey = new LinkedHashMap<String, LinkedHashSet<String>>();
    for (var record : missing) {
      missingByKey
          .computeIfAbsent(
              record.get("destination_country_code", String.class)
                  + "|"
                  + record.get("currency", String.class),
              k -> new LinkedHashSet<>())
          .add(record.get("component_code", String.class));
    }
    var rows = new ArrayList<LandedCostComparisonRow>();
    for (var record : records) {
      var key = record.get("destination_country_code", String.class)
          + "|"
          + record.get("currency", String.class);
      rows.add(
          new LandedCostComparisonRow(
              record.get("destination_country_code", String.class),
              record.get("destination_name", String.class),
              record.get("currency", String.class),
              record.get("statement_count", Integer.class),
              record.get("complete_count", Integer.class),
              record.get("avg_unit", BigDecimal.class),
              record.get("min_unit", BigDecimal.class),
              record.get("max_unit", BigDecimal.class),
              List.copyOf(missingByKey.getOrDefault(key, new LinkedHashSet<>()))));
    }
    return new LandedCostComparison(
        sku.get("id", UUID.class),
        sku.get("code", String.class),
        sku.get("name", String.class),
        sku.get("base_unit", String.class),
        rows,
        "🔴 **跨币种绝不相加**：不同目的地币种不同的行不参与同一个平均值，页面上分开显示。"
            + "平均值只统计**单位到岸成本**（每基础单位），且只含计入的分量 —— "
            + "缺项的那批单会被列进 missingComponents，"
            + "「同样的货、不同的目的地，成本差多少」必须连同覆盖范围一起看。");
  }

  // ================================================================ 参数

  /** 租户维护的单件参数（清关费 / 尾程预备）。 */
  public LandedCostParameterPage parameters() {
    var records =
        sql.fetch(
            "select id, component_code, destination_country_code, currency, unit_amount, note,"
                + " version, updated_at from landed_cost_parameter where tenant_id="
                + TENANT
                + " order by component_code, destination_country_code, currency");
    var items = new ArrayList<LandedCostParameterRow>();
    for (var record : records) {
      items.add(parameterRow(record));
    }
    return new LandedCostParameterPage(
        items,
        "🔴 只有**清关费**与**尾程预备**是参数：全仓没有任何清关费事实，"
            + "而尾程预备按定义就是租户为定价留的预留。其余四项是上游事实，**不许人工填**。",
        "参数按 (分量, 目的国, 币种) 唯一；单件金额是**每基础单位**的钱，"
            + "改动带版本号（乐观锁），改完需要重算受影响的到岸成本单。");
  }

  /** 维护一个参数（新增或改）。 */
  public LandedCostParameterRow saveParameter(
      AccessService.Access actor, LandedCostParameterInput input) {
    var component = LandedCostComponent.parse(input.componentCode());
    if (component == null
        || (component != LandedCostComponent.CUSTOMS_CLEARANCE
            && component != LandedCostComponent.LAST_MILE_RESERVE)) {
      throw badRequest(
          "只有清关费（CUSTOMS_CLEARANCE）与尾程预备（LAST_MILE_RESERVE）是参数；"
              + "采购价、国际头程、关税、目的地操作费都是上游事实，不许人工填");
    }
    if (input.unitAmount() == null || input.unitAmount().signum() < 0) {
      throw badRequest("单件金额必须是非负数");
    }
    var country = input.destinationCountryCode().trim().toUpperCase(Locale.ROOT);
    var currency = input.currency().trim().toUpperCase(Locale.ROOT);
    var existing =
        sql.fetchOne(
            "select id, version from landed_cost_parameter where tenant_id="
                + TENANT
                + " and component_code=? and destination_country_code=? and currency=?",
            component.name(),
            country,
            currency);
    UUID id = existing == null ? UUID.randomUUID() : existing.get("id", UUID.class);
    long currentVersion = existing == null ? 0 : existing.get("version", Long.class);
    if (existing != null && existing.get("version", Long.class) != input.version()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "这个参数已被别人改过（当前版本 "
              + currentVersion
              + "，你提交的是 "
              + input.version()
              + "）：请刷新后重试，不要覆盖别人的改动");
    }
    if (existing == null) {
      sql.execute(
          "insert into landed_cost_parameter(id,tenant_id,component_code,destination_country_code,"
              + "currency,unit_amount,note,version,created_at,updated_at)"
              + " values(?::uuid,?::uuid,?,?,?,?,?,0,now(),now())",
          id,
          actor.tenantId(),
          component.name(),
          country,
          currency,
          input.unitAmount(),
          input.note() == null ? "" : input.note().trim());
    } else {
      int updated =
          sql.execute(
              "update landed_cost_parameter set unit_amount=?, note=?, version=version+1,"
                  + " updated_at=now() where tenant_id="
                  + TENANT
                  + " and id=? and version=?",
              input.unitAmount(),
              input.note() == null ? "" : input.note().trim(),
              id,
              input.version());
      if (updated == 0) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "这个参数刚被别人改过：请刷新后重试");
      }
    }
    var row =
        sql.fetchOne(
            "select id, component_code, destination_country_code, currency, unit_amount, note,"
                + " version, updated_at from landed_cost_parameter where tenant_id="
                + TENANT
                + " and id=?",
            id);
    return parameterRow(row);
  }

  private LandedCostParameterRow parameterRow(org.jooq.Record record) {
    var component = LandedCostComponent.parse(record.get("component_code", String.class));
    return new LandedCostParameterRow(
        record.get("id", UUID.class),
        record.get("component_code", String.class),
        component == null ? record.get("component_code", String.class) : component.label(),
        record.get("destination_country_code", String.class),
        record.get("currency", String.class),
        record.get("unit_amount", BigDecimal.class),
        record.get("note", String.class),
        record.get("version", Long.class),
        record.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 计算

  /**
   * 计算（或重算）一张到岸成本单。
   *
   * <p>🔴 **幂等**：一张头程发运单对应一张到岸成本单（DB 上 UNIQUE），
   * 重算覆盖同一张单并把 {@code compute_count} 加一 ——
   * 「这个到岸成本是按什么时候的头程算出来的」必须可追溯。
   *
   * @param bearer 调用方的 Authorization 头（**透传给 warehouse**，租户由它从成员关系解析）
   */
  public LandedCostComputed compute(
      AccessService.Access actor, String bearer, LandedCostComputeInput input) {
    if ((input.sourceShipmentId() == null)
        && (input.sourceShipmentCode() == null || input.sourceShipmentCode().isBlank())) {
      throw badRequest("必须给出头程发运单的 id 或单号");
    }
    var shipment = upstreamShipment(bearer, input.sourceShipmentId(), input.sourceShipmentCode());
    if (shipment == null) {
      throw missing("找不到这张头程发运单");
    }
    if (shipment.id() == null) {
      throw upstreamUnavailable("仓库服务返回的头程发运单没有 id");
    }
    if (shipment.lines() == null || shipment.lines().isEmpty()) {
      throw badRequest(
          "这张头程发运单没有行：没有行就没有 SKU，也就没有到岸单位成本。"
              + "请先在 10.4.1.1 头程发运单上登记要发走的货");
    }
    if (shipment.status() != null && "CANCELLED".equals(shipment.status())) {
      throw badRequest("这张头程发运单已取消：取消的批次不产生到岸成本");
    }
    var duty = upstreamDuty(bearer, shipment.id());

    var currency =
        shipment.currency() == null || shipment.currency().isBlank()
            ? "USD"
            : shipment.currency().trim().toUpperCase(Locale.ROOT);
    var country = shipment.destinationCountryCode();
    // 🔴 **谁承担哪一笔成本由贸易条款决定，不由 risk_bearer 决定**。
    //
    // risk_bearer 说的是**风险转移点**，不是费用承担方：DAP 下风险在指定地点转移，
    // 但**运费仍然是卖方付的**（卖方把货送到指定地点）。把 risk_bearer 当成费用承担方，
    // 会把 DAP 下的国际头程整笔剔掉 —— 那正是本模块最忌讳的「系统性低估」。
    //
    // 规则表（Incoterms 2010 的定义，见 warehouse V80 / V90）：
    //   国际头程 + 目的地操作费：除 EXW / FCA（买方到卖方处自提、自行安排运输）外，卖方承担。
    //   清关费：除 DAP / DPU（进口清关由买方负责）外，卖方承担。
    //   关税：按上游 duty_tax_estimate 的 duty_bearer（V90 的事实，DDP⇒SELLER、DDU⇒BUYER）。
    //   尾程预备：是**定价预留**，与贸易条款无关，永远算卖方的。
    var incoterm = nz(shipment.incoterm()).trim().toUpperCase(Locale.ROOT);
    var sellerBearsFreight = !"EXW".equals(incoterm) && !"FCA".equals(incoterm);
    var sellerBearsClearance = !"DAP".equals(incoterm) && !"DPU".equals(incoterm);
    var dutySellerBears = duty != null && "SELLER".equals(duty.dutyBearer());

    // ---- 1. 汇总成 SKU 行 ----
    var lines = aggregateLines(shipment);

    // ---- 2. 采购价（本地库的实际到货金额，按成本法加权）----
    var purchase = purchasePrices(lines, currency);

    // ---- 3. 批次级成本摊到行 ----
    var quantities = new ArrayList<BigDecimal>();
    for (var line : lines) {
      quantities.add(line.baseQuantity());
    }
    var freightTotal = nz(shipment.freightCost()).add(nz(shipment.insuranceCost()));
    var handlingTotal = nz(shipment.handlingCost());
    var freightShares = LandedCostRules.allocateByQuantity(freightTotal, quantities);
    var handlingShares = LandedCostRules.allocateByQuantity(handlingTotal, quantities);

    // ---- 4. 关税：按 SKU 汇总估算行，再摊到该 SKU 的行 ----
    var dutyBySku = dutyAmountsBySku(duty, currency);

    // ---- 5. 参数（清关费 / 尾程预备）----
    var clearance = parameterAmount("CUSTOMS_CLEARANCE", country, currency);
    var reserve = parameterAmount("LAST_MILE_RESERVE", country, currency);

    // ---- 6. 落库 ----
    var existing =
        sql.fetchOne(
            "select id, statement_code, compute_count from landed_cost_statement where tenant_id="
                + TENANT
                + " and source_shipment_id=?",
            shipment.id());
    boolean created = existing == null;
    UUID statementId = created ? UUID.randomUUID() : existing.get("id", UUID.class);
    String statementCode;
    int computeCount;
    if (created) {
      long seq = sql.fetchOne("select nextval('landed_cost_statement_code_seq')").get(0, Long.class);
      var today = sql.fetchOne("select current_date").get(0, LocalDate.class);
      statementCode = "LC-" + today.toString().replace("-", "") + "-" + String.format("%06d", seq);
      computeCount = 1;
    } else {
      statementCode = existing.get("statement_code", String.class);
      computeCount = existing.get("compute_count", Integer.class) + 1;
      // 重算：先清掉旧的行与分量（历史由 compute_count 与 computed_at 承担，
      // 本模块不保存每一版快照 —— 5.2.1.1 的成本变动历史是**另一种**事实）。
      sql.execute(
          "delete from landed_cost_component where tenant_id=" + TENANT + " and statement_id=?",
          statementId);
      sql.execute(
          "delete from landed_cost_line where tenant_id=" + TENANT + " and statement_id=?",
          statementId);
    }

    var totalQuantity = BigDecimal.ZERO;
    for (var line : lines) {
      totalQuantity = totalQuantity.add(line.baseQuantity());
    }

    if (created) {
      sql.execute(
          "insert into landed_cost_statement(id,tenant_id,statement_code,source_shipment_id,"
              + "source_shipment_code,source_legal_entity_id,destination_country_code,"
              + "destination_name,transport_mode,incoterm,risk_bearer,currency,cost_basis,"
              + "excluded_components,allocation_method,status,total_amount,total_quantity,"
              + "total_line_count,computed_at,computed_by,compute_count,note,version,created_at,updated_at)"
              + " values(?::uuid,?::uuid,?,?::uuid,?,?::uuid,?,?,?,?,?,?,'LANDED_TO_TARGET_WAREHOUSE',"
              + "'STORAGE_FEE,LAST_MILE_DELIVERY_FEE','QUANTITY_PROPORTIONAL','COMPUTED',null,?,?,now(),"
              + "?::uuid,?,'按 10.4.1.1 头程发运单计算：采购价取同库实际到货金额，"
              + "国际头程与目的地操作费取发运单，关税取 10.5.1.3 关税估算，清关费与尾程预备取本服务参数。',"
              + "0,now(),now())",
          statementId,
          actor.tenantId(),
          statementCode,
          shipment.id(),
          shipment.shipmentCode() == null ? "" : shipment.shipmentCode(),
          shipment.legalEntityId(),
          country == null ? "" : country,
          shipment.destinationName() == null ? "" : shipment.destinationName(),
          shipment.transportMode() == null ? "" : shipment.transportMode(),
          shipment.incoterm() == null ? "" : shipment.incoterm(),
          shipment.riskBearer() == null ? "" : shipment.riskBearer(),
          currency,
          totalQuantity,
          lines.size(),
          actor.userId(),
          computeCount);
    } else {
      sql.execute(
          "update landed_cost_statement set source_shipment_code=?, source_legal_entity_id=?::uuid,"
              + " destination_country_code=?, destination_name=?, transport_mode=?, incoterm=?,"
              + " risk_bearer=?, currency=?, total_amount=null, total_quantity=?, total_line_count=?,"
              + " computed_at=now(), computed_by=?::uuid, compute_count=?, note=?, updated_at=now(),"
              + " version=version+1 where tenant_id="
              + TENANT
              + " and id=?",
          shipment.shipmentCode() == null ? "" : shipment.shipmentCode(),
          shipment.legalEntityId(),
          country == null ? "" : country,
          shipment.destinationName() == null ? "" : shipment.destinationName(),
          shipment.transportMode() == null ? "" : shipment.transportMode(),
          shipment.incoterm() == null ? "" : shipment.incoterm(),
          shipment.riskBearer() == null ? "" : shipment.riskBearer(),
          currency,
          totalQuantity,
          lines.size(),
          actor.userId(),
          computeCount,
          "重算：按 10.4.1.1 头程发运单与 10.5.1.3 关税估算的最新事实重新计算。",
          statementId);
    }

    var missing = new LinkedHashSet<String>();
    var includedCount = 0;
    BigDecimal statementTotal = null;
    var lineTotals = new ArrayList<BigDecimal>();

    for (int i = 0; i < lines.size(); i++) {
      var line = lines.get(i);
      var components = new ArrayList<ComponentDraft>();
      var lineIncluded = 0;
      var lineMissing = new LinkedHashSet<String>();
      var includedAmounts = new ArrayList<BigDecimal>();

      // ---- 采购价 ----
      var purchaseFact = purchase.get(line.skuId());
      var purchaseComponent = purchaseComponent(line, purchaseFact, currency);
      components.add(purchaseComponent);
      if (purchaseComponent.available() && purchaseComponent.included()) {
        lineIncluded++;
        includedAmounts.add(purchaseComponent.amount());
      } else if (!purchaseComponent.available()) {
        lineMissing.add(purchaseComponent.code());
      }

      // ---- 国际头程（运费 + 保险费）----
      var freightComponent =
          batchComponent(
              "INTERNATIONAL_FREIGHT",
              shipment.shipmentCode(),
              freightTotal,
              freightShares.get(i),
              sellerBearsFreight,
              "10.4.1.1 头程发运单",
              "发运单上的运费 + 保险费之和为 0，而该列是 NOT NULL DEFAULT 0，"
                  + "**0 与「没登记」不可区分**，所以不把它当成「这笔成本是 0」。",
              "贸易条款 "
                  + (incoterm.isBlank() ? "未登记" : incoterm)
                  + "：买方到卖方处自提并自行安排运输，这笔头程费不是卖方承担的。");
      components.add(freightComponent);
      if (freightComponent.available() && freightComponent.included()) {
        lineIncluded++;
        includedAmounts.add(freightComponent.amount());
      } else if (!freightComponent.available()) {
        lineMissing.add(freightComponent.code());
      }

      // ---- 关税 ----
      var dutyComponent = dutyComponent(line, duty, dutyBySku, dutySellerBears, currency);
      components.add(dutyComponent);
      if (dutyComponent.available() && dutyComponent.included()) {
        lineIncluded++;
        includedAmounts.add(dutyComponent.amount());
      } else if (!dutyComponent.available()) {
        lineMissing.add(dutyComponent.code());
      }

      // ---- 清关（参数）----
      var clearanceComponent =
          parameterComponent(
              "CUSTOMS_CLEARANCE",
              clearance,
              line.baseQuantity(),
              sellerBearsClearance,
              country,
              currency,
              "贸易条款 "
                  + (incoterm.isBlank() ? "未登记" : incoterm)
                  + "：进口清关由买方负责，这笔清关费不是卖方承担的。");
      components.add(clearanceComponent);
      if (clearanceComponent.available() && clearanceComponent.included()) {
        lineIncluded++;
        includedAmounts.add(clearanceComponent.amount());
      } else if (!clearanceComponent.available()) {
        lineMissing.add(clearanceComponent.code());
      }

      // ---- 目的地操作费 ----
      var handlingComponent =
          batchComponent(
              "DESTINATION_HANDLING",
              shipment.shipmentCode(),
              handlingTotal,
              handlingShares.get(i),
              sellerBearsFreight,
              "10.4.1.1 头程发运单",
              "发运单上的操作费为 0，而该列是 NOT NULL DEFAULT 0，"
                  + "**0 与「没登记」不可区分**，所以不把它当成「这笔成本是 0」。",
              "贸易条款 "
                  + (incoterm.isBlank() ? "未登记" : incoterm)
                  + "：买方到卖方处自提并自行安排运输，目的地侧的这笔费用不是卖方承担的。");
      components.add(handlingComponent);
      if (handlingComponent.available() && handlingComponent.included()) {
        lineIncluded++;
        includedAmounts.add(handlingComponent.amount());
      } else if (!handlingComponent.available()) {
        lineMissing.add(handlingComponent.code());
      }

      // ---- 尾程预备（参数）----
      // 尾程预备是**定价预留**，不是任何一笔已发生的费用 ⇒ 与贸易条款无关，永远算卖方的。
      var reserveComponent =
          parameterComponent(
              "LAST_MILE_RESERVE",
              reserve,
              line.baseQuantity(),
              true,
              country,
              currency,
              "尾程预备是定价预留，与贸易条款无关。");
      components.add(reserveComponent);
      if (reserveComponent.available() && reserveComponent.included()) {
        lineIncluded++;
        includedAmounts.add(reserveComponent.amount());
      } else if (!reserveComponent.available()) {
        lineMissing.add(reserveComponent.code());
      }

      var lineTotal = LandedCostRules.total(includedAmounts);
      lineTotals.add(lineTotal);
      if (lineTotal != null) {
        statementTotal =
            statementTotal == null
                ? lineTotal
                : statementTotal.add(lineTotal).setScale(LandedCostRules.MONEY_SCALE, LandedCostRules.ROUNDING);
      }
      includedCount += lineIncluded;
      missing.addAll(lineMissing);

      // 写行（合计先落 null，延迟约束在提交时核对它等于计入分量之和）。
      UUID lineId = UUID.randomUUID();
      sql.execute(
          "insert into landed_cost_line(id,tenant_id,statement_id,line_no,sku_id,sku_code,sku_name,"
              + "base_unit,quantity,base_quantity,total_amount,included_component_count,"
              + "missing_component_count,missing_components,created_at)"
              + " values(?::uuid,?::uuid,?::uuid,?,?::uuid,?,?,?,?,?,null,?,?,?,now())",
          lineId,
          actor.tenantId(),
          statementId,
          i + 1,
          line.skuId(),
          line.skuCode() == null ? "" : line.skuCode(),
          line.skuName() == null ? "" : line.skuName(),
          line.baseUnit() == null ? "" : line.baseUnit(),
          line.quantity(),
          line.baseQuantity(),
          lineIncluded,
          lineMissing.size(),
          String.join(",", lineMissing));

      for (var component : components) {
        sql.execute(
            "insert into landed_cost_component(id,tenant_id,statement_id,line_id,component_code,"
                + "label,source_kind,source_module,source_document_code,available,included,amount,"
                + "tax_duty_amount,tax_other_amount,tax_bearer,owner_module,impact,note,created_at)"
                + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())",
            UUID.randomUUID(),
            actor.tenantId(),
            statementId,
            lineId,
            component.code(),
            component.label(),
            component.kind(),
            component.sourceModule(),
            component.sourceDocumentCode(),
            component.available(),
            component.included(),
            component.amount(),
            component.taxDutyAmount(),
            component.taxOtherAmount(),
            component.taxBearer(),
            component.ownerModule(),
            component.impact(),
            component.note());
      }

      // 回填行合计（延迟约束在提交时核对它等于计入分量之和）。
      sql.execute(
          "update landed_cost_line set total_amount=? where tenant_id=" + TENANT + " and id=?",
          lineTotal,
          lineId);
    }

    // 回填单合计。
    sql.execute(
        "update landed_cost_statement set total_amount=? where tenant_id=" + TENANT + " and id=?",
        statementTotal,
        statementId);

    return new LandedCostComputed(
        statementId,
        statementCode,
        created,
        computeCount,
        lines.size(),
        statementTotal,
        currency,
        includedCount,
        missing.size(),
        List.copyOf(missing),
        "六个分量逐项落库：每一项都带来源单号；缺口标 available=false 并点名归谁管、缺了会怎样，"
            + "**不显示 0**。合计只包含计入的分量。");
  }

  // ================================================================ 内部：行与分量草稿

  /** 一行到岸成本（SKU 级）。 */
  private record LineDraft(
      UUID skuId,
      String skuCode,
      String skuName,
      String baseUnit,
      BigDecimal quantity,
      BigDecimal baseQuantity) {}

  /** 一个分量的草稿（写库前）。 */
  private record ComponentDraft(
      String code,
      String label,
      String kind,
      String sourceModule,
      String sourceDocumentCode,
      boolean available,
      boolean included,
      BigDecimal amount,
      BigDecimal taxDutyAmount,
      BigDecimal taxOtherAmount,
      String taxBearer,
      String ownerModule,
      String impact,
      String note) {}

  /**
   * 把发运单的行汇总成 SKU 行。
   *
   * <p>🔴 **一个 SKU 可能有多行**（同一批货的不同批次各占一行），
   * 汇总到一行才不会让这个 SKU 的到岸成本算两遍。
   *
   * <p>🔴 **发运单的行没有计量单位列**：V80 的 {@code first_mile_shipment_line}
   * 只有数量没有 uom，所以本模块按 **SKU 基础单位**解释这些数量
   * （发运行的量纲由收货行带入，与 SKU 基础单位一致）。
   * 若上游将来区分量纲，本行的 {@code base_quantity} 需要跟着复核。
   */
  private List<LineDraft> aggregateLines(ShipmentView shipment) {
    var bySku = new LinkedHashMap<UUID, ShipmentLineView>();
    var quantities = new LinkedHashMap<UUID, BigDecimal>();
    for (var line : shipment.lines()) {
      if (line.skuId() == null) continue;
      var quantity = nz(line.shippedQuantity());
      if (quantity.signum() <= 0) {
        quantity = nz(line.plannedQuantity());
      }
      if (quantity.signum() <= 0) continue;
      bySku.putIfAbsent(line.skuId(), line);
      quantities.merge(line.skuId(), quantity, BigDecimal::add);
    }
    var out = new ArrayList<LineDraft>();
    for (var entry : bySku.entrySet()) {
      var line = entry.getValue();
      var quantity = quantities.get(entry.getKey());
      out.add(
          new LineDraft(
              entry.getKey(),
              line.skuCode(),
              line.skuName(),
              baseUnitOf(entry.getKey()),
              quantity,
              quantity));
    }
    return out;
  }

  /** SKU 的基础单位。 */
  private String baseUnitOf(UUID skuId) {
    var record =
        sql.fetchOne(
            "select base_unit from sku where tenant_id=" + TENANT + " and id=?", skuId);
    return record == null ? "" : record.get("base_unit", String.class);
  }

  /**
   * 采购价：同库的实际到货金额按移动加权折成基础单位单价。
   *
   * @return skuId → 采购价事实（单价、币种、来源单号、到货笔数）
   */
  private Map<UUID, PurchaseFact> purchasePrices(List<LineDraft> lines, String currency) {
    var out = new LinkedHashMap<UUID, PurchaseFact>();
    if (lines.isEmpty()) return out;
    var skuIds = new ArrayList<UUID>();
    for (var line : lines) {
      skuIds.add(line.skuId());
    }
    var from = LocalDate.now().minusDays(DEFAULT_WINDOW_DAYS);
    var to = LocalDate.now();
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
    // 按 (sku, 币种) 分组：跨币种绝不相加。
    var lots = new LinkedHashMap<String, List<DeliveryLot>>();
    var currencies = new LinkedHashMap<UUID, LinkedHashSet<String>>();
    for (var record : records) {
      var skuId = record.get("sku_id", UUID.class);
      var lotCurrency = record.get("currency", String.class);
      currencies
          .computeIfAbsent(skuId, k -> new LinkedHashSet<>())
          .add(lotCurrency);
      if (!currency.equals(lotCurrency)) {
        // 币种不一致的到货不参与本单计价（不折算）。
        continue;
      }
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
        continue;
      }
      lots
          .computeIfAbsent(skuId + "|" + lotCurrency, k -> new ArrayList<>())
          .add(
              new DeliveryLot(
                  record.get("id", UUID.class),
                  record.get("delivery_ref", String.class),
                  record.get("received_date", LocalDate.class),
                  quantity,
                  baseQuantity,
                  record.get("amount", BigDecimal.class),
                  lotCurrency));
    }
    for (var line : lines) {
      var skuLots = lots.get(line.skuId() + "|" + currency);
      if (skuLots == null || skuLots.isEmpty()) {
        var others = currencies.getOrDefault(line.skuId(), new LinkedHashSet<>());
        out.put(
            line.skuId(),
            new PurchaseFact(null, currency, "", 0, others.isEmpty() ? null : String.join("/", others)));
        continue;
      }
      var weighted =
          SkuCostCompositionRules.weighted(SkuCostCompositionModels.CostMethod.MOVING_AVERAGE, skuLots);
      var refs = new ArrayList<String>();
      for (var lot : skuLots) {
        refs.add(lot.deliveryRef());
      }
      out.put(
          line.skuId(),
          new PurchaseFact(weighted.unitCost(), currency, String.join(",", refs), skuLots.size(), null));
    }
    return out;
  }

  /** 采购价事实。 */
  private record PurchaseFact(
      BigDecimal unitCost, String currency, String deliveryRefs, int lotCount, String otherCurrencies) {}

  /** 采购价分量。 */
  private ComponentDraft purchaseComponent(
      LineDraft line, PurchaseFact fact, String currency) {
    if (fact == null || fact.unitCost() == null) {
      var reason =
          fact != null && fact.otherCurrencies() != null
              ? "该 SKU 的实际到货金额币种（"
                  + fact.otherCurrencies()
                  + "）与本单币种（"
                  + currency
                  + "）不一致，**本服务没有汇率表**（汇率归 12 财务），不折算、不进合计。"
              : "口径窗口（最近 "
                  + DEFAULT_WINDOW_DAYS
                  + " 天）内没有该 SKU 按本单币种计价的有效到货记录。";
      return new ComponentDraft(
          "PURCHASE_PRICE",
          "采购价",
          "FACT",
          "6 采购（vendor_delivery_record）",
          "",
          false,
          false,
          null,
          null,
          null,
          "",
          "6 采购（6.3 采购单未交付 ⇒ 用实际到货金额代替）",
          reason,
          "");
    }
    var amount =
        fact.unitCost()
            .multiply(line.baseQuantity())
            .setScale(LandedCostRules.MONEY_SCALE, LandedCostRules.ROUNDING);
    return new ComponentDraft(
        "PURCHASE_PRICE",
        "采购价",
        "FACT",
        "6 采购（vendor_delivery_record）",
        fact.deliveryRefs(),
        true,
        true,
        amount,
        null,
        null,
        "",
        "",
        "",
        "按移动加权的实际到货单价 × 本行基础数量；来源单号是到货单号。");
  }

  /** 批次级分量（国际头程 / 目的地操作费）。 */
  private ComponentDraft batchComponent(
      String code,
      String sourceDocumentCode,
      BigDecimal total,
      BigDecimal share,
      boolean sellerBears,
      String sourceModule,
      String zeroReason,
      String notIncludedNote) {
    var component = LandedCostComponent.parse(code);
    if (total == null || total.signum() <= 0) {
      return new ComponentDraft(
          code,
          component == null ? code : component.label(),
          "FACT",
          sourceModule,
          "",
          false,
          false,
          null,
          null,
          null,
          "",
          "10.4.1.1 头程发运单",
          zeroReason,
          "");
    }
    if (!sellerBears) {
      return new ComponentDraft(
          code,
          component == null ? code : component.label(),
          "FACT",
          sourceModule,
          sourceDocumentCode == null ? "" : sourceDocumentCode,
          true,
          false,
          share,
          null,
          null,
          "",
          "",
          "",
          notIncludedNote);
    }
    return new ComponentDraft(
        code,
        component == null ? code : component.label(),
        "FACT",
        sourceModule,
        sourceDocumentCode == null ? "" : sourceDocumentCode,
        true,
        true,
        share,
        null,
        null,
        "",
        "",
        "",
        "按本批各行数量比例摊到这一行（最大余数法，逐字守恒）。");
  }

  /**
   * 关税分量。
   *
   * <p>🔴 **关税与增值税/GST 分列**：10.5.1.3 把 DUTY / VAT / GST 明确分成三笔独立的钱，
   * 并进一起就答不出「哪部分是关税哪部分是税」。
   */
  private ComponentDraft dutyComponent(
      LineDraft line,
      DutyView duty,
      Map<UUID, DutyFact> dutyBySku,
      boolean sellerBears,
      String currency) {
    if (duty == null) {
      return new ComponentDraft(
          "DUTY",
          "关税",
          "FACT",
          "10.5.1.3 关税与税费（duty_tax_estimate）",
          "",
          false,
          false,
          null,
          null,
          null,
          "",
          "10.5.1.3 关税与税费（10.4.1.1 头程发运单 → 10.5.1.1 报关单 → 10.5.1.3 关税估算）",
          "这张发运单还没有对应的关税估算单：没有估算就没有税额，"
              + "**不用 0 假装没有关税** —— 用 0 填关税会让到岸成本系统性低估。",
          "");
    }
    if (duty.status() != null && "DRAFT".equals(duty.status())) {
      return new ComponentDraft(
          "DUTY",
          "关税",
          "FACT",
          "10.5.1.3 关税与税费（duty_tax_estimate）",
          duty.estimateCode() == null ? "" : duty.estimateCode(),
          false,
          false,
          null,
          null,
          null,
          "",
          "10.5.1.3 关税与税费",
          "关税估算单还是草稿（DRAFT），税额尚未算出来。",
          "");
    }
    if (duty.estimateCurrency() != null
        && !currency.equals(duty.estimateCurrency().trim().toUpperCase(Locale.ROOT))) {
      return new ComponentDraft(
          "DUTY",
          "关税",
          "FACT",
          "10.5.1.3 关税与税费（duty_tax_estimate）",
          duty.estimateCode() == null ? "" : duty.estimateCode(),
          false,
          false,
          null,
          null,
          null,
          "",
          "10.5.1.3 关税与税费",
          "关税估算单的币种（"
              + duty.estimateCurrency()
              + "）与本单币种（"
              + currency
              + "）不一致，**本服务没有汇率表**（汇率归 12 财务），不折算、不进合计。",
          "");
    }
    var fact = dutyBySku.get(line.skuId());
    if (fact == null) {
      return new ComponentDraft(
          "DUTY",
          "关税",
          "FACT",
          "10.5.1.3 关税与税费（duty_tax_estimate）",
          duty.estimateCode() == null ? "" : duty.estimateCode(),
          false,
          false,
          null,
          null,
          null,
          "",
          "10.5.1.3 关税与税费",
          "关税估算单里没有这个 SKU 的行：没有行就没有税额，"
              + "**不用 0 假装没有关税**。",
          "");
    }
    if (!sellerBears) {
      return new ComponentDraft(
          "DUTY",
          "关税",
          "FACT",
          "10.5.1.3 关税与税费（duty_tax_estimate）",
          duty.estimateCode() == null ? "" : duty.estimateCode(),
          true,
          false,
          fact.total(),
          fact.duty(),
          fact.otherTax(),
          nz(duty.dutyBearer()),
          "",
          "",
          "按承担方（"
              + nz(duty.dutyBearer())
              + "）：关税与目的国税费不由卖方承担，不计入卖方的到岸成本 —— "
              + "算进去就是在给一个不属于自己的成本记账。");
    }
    return new ComponentDraft(
        "DUTY",
        "关税",
        "FACT",
        "10.5.1.3 关税与税费（duty_tax_estimate）",
        duty.estimateCode() == null ? "" : duty.estimateCode(),
        true,
        true,
        fact.total(),
        fact.duty(),
        fact.otherTax(),
        nz(duty.dutyBearer()),
        "",
        "",
        "关税与增值税/GST 是两笔独立的钱，分列显示；来源单号是关税估算单号。");
  }

  /** 关税事实（按 SKU 汇总）。 */
  private record DutyFact(BigDecimal duty, BigDecimal otherTax, BigDecimal total) {}

  /** 关税估算单里按 SKU 汇总的税额。 */
  private Map<UUID, DutyFact> dutyAmountsBySku(DutyView duty, String currency) {
    var out = new LinkedHashMap<UUID, DutyFact>();
    if (duty == null || duty.lines() == null) return out;
    for (var line : duty.lines()) {
      if (line.skuId() == null) continue;
      var dutyAmount = nz(line.estimatedDutyAmount());
      var vatAmount = nz(line.estimatedVatAmount());
      // 🔴 进口环节的**全部**税额（关税 + 增值税/GST + 其它税）都要进到岸成本。
      // 上游的 estimatedLineTotalTax = 关税 + 增值税 + 其它税；
      // 万一上游只登记了分项没登记合计，这里取「分项之和」兜底（总额不能小于分项之和）。
      var totalTax = nz(line.estimatedLineTotalTax()).max(dutyAmount.add(vatAmount));
      // 「其它税」= 总额 − 关税 = 增值税/GST + 其它税。
      // （增值税不是关税，但它同样是进口环节付出的真金白银 ——
      //   把它从合计里剔掉，就是本模块最忌讳的那种「系统性低估」。）
      var otherTax = totalTax.subtract(dutyAmount);
      if (otherTax.signum() < 0) {
        otherTax = BigDecimal.ZERO;
      }
      var current = out.get(line.skuId());
      if (current == null) {
        out.put(line.skuId(), new DutyFact(dutyAmount, otherTax, totalTax));
      } else {
        out.put(
            line.skuId(),
            new DutyFact(
                current.duty().add(dutyAmount),
                current.otherTax().add(otherTax),
                current.total().add(totalTax)));
      }
    }
    return out;
  }

  /** 参数（清关费 / 尾程预备）分量。 */
  private ComponentDraft parameterComponent(
      String code,
      BigDecimal unitAmount,
      BigDecimal baseQuantity,
      boolean sellerBears,
      String country,
      String currency,
      String notIncludedNote) {
    var component = LandedCostComponent.parse(code);
    var label = component == null ? code : component.label();
    if (unitAmount == null) {
      return new ComponentDraft(
          code,
          label,
          "PARAMETER",
          "05 定价与价目（本服务）· landed_cost_parameter",
          "",
          false,
          false,
          null,
          null,
          null,
          "",
          "05 定价与价目（本服务参数未维护）",
          "目的国（"
              + nz(country)
              + "）· 币种（"
              + currency
              + "）下这个参数**尚未维护**。"
              + "可空 = 尚未维护，**不是 0** —— 显示 0.00 等于宣称「这笔成本不花钱」。",
          "");
    }
    if (!sellerBears) {
      return new ComponentDraft(
          code,
          label,
          "PARAMETER",
          "05 定价与价目（本服务）· landed_cost_parameter",
          code + "|" + nz(country) + "|" + currency,
          true,
          false,
          unitAmount
              .multiply(baseQuantity)
              .setScale(LandedCostRules.MONEY_SCALE, LandedCostRules.ROUNDING),
          null,
          null,
          "",
          "",
          "",
          notIncludedNote);
    }
    return new ComponentDraft(
        code,
        label,
        "PARAMETER",
        "05 定价与价目（本服务）· landed_cost_parameter",
        code + "|" + nz(country) + "|" + currency,
        true,
        true,
        unitAmount
            .multiply(baseQuantity)
            .setScale(LandedCostRules.MONEY_SCALE, LandedCostRules.ROUNDING),
        null,
        null,
        "",
        "",
        "",
        "租户维护的单件参数 × 本行基础数量。");
  }

  /** 读一个参数的单件金额；没维护返回 null（**不是 0**）。 */
  private BigDecimal parameterAmount(String code, String country, String currency) {
    if (country == null || country.isBlank()) return null;
    var record =
        sql.fetchOne(
            "select unit_amount from landed_cost_parameter where tenant_id="
                + TENANT
                + " and component_code=? and destination_country_code=? and currency=?",
            code,
            country,
            currency);
    return record == null ? null : record.get("unit_amount", BigDecimal.class);
  }

  // ================================================================ 内部：工具

  /** 这张到岸成本单对应的上游头程发运单 id（重算入口用）。 */
  public UUID sourceShipmentId(UUID statementId) {
    var record =
        sql.fetchOne(
            "select source_shipment_id from landed_cost_statement where tenant_id="
                + TENANT
                + " and id=?",
            statementId);
    if (record == null) {
      throw missing("到岸成本单不存在");
    }
    return record.get("source_shipment_id", UUID.class);
  }

  private boolean exists(UUID id) {
    return sql.fetchOne(
            "select 1 from landed_cost_statement where tenant_id=" + TENANT + " and id=?", id)
        != null;
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static String placeholders(int n) {
    return String.join(",", java.util.Collections.nCopies(n, "?"));
  }

  private static Object[] concat(List<UUID> ids, Object... rest) {
    var out = new ArrayList<Object>();
    out.addAll(ids);
    out.addAll(List.of(rest));
    return out.toArray();
  }

  private static Object[] concatParams(List<Object> params, Object... rest) {
    var out = new ArrayList<Object>(params);
    out.addAll(List.of(rest));
    return out.toArray();
  }

  private static ResponseStatusException missing(String what) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what);
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  // ---------------------------------------------------------------- 上游不可达 ⇒ 503

  /**
   * 🔴 **上游读不到 ⇒ 503，不是空集合，更不是 0**。
   *
   * <p>头程与关税的唯一来源是 warehouse 的授权只读端点。读不到时如果返回空集合，
   * 页面会显示「国际头程 0.00、关税 0.00」—— 那是在骗人，而且会让到岸成本**系统性低估**
   * （关税往往不是小数目），页面上却看不出任何异常。所以这里明确失败。
   */
  private static ResponseStatusException upstreamUnavailable(String message) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message);
  }

  private ShipmentView upstreamShipment(String bearer, UUID shipmentId, String shipmentCode) {
    try {
      return upstream.shipment(bearer, shipmentId, shipmentCode);
    } catch (LandedCostUnavailableException e) {
      throw upstreamUnavailable(e.getMessage());
    }
  }

  private DutyView upstreamDuty(String bearer, UUID shipmentId) {
    try {
      return upstream.duty(bearer, shipmentId);
    } catch (LandedCostUnavailableException e) {
      throw upstreamUnavailable(e.getMessage());
    }
  }
}
