package com.starcon.channel;

import static com.starcon.channel.LogisticsCostAnalysisModels.*;

import com.starcon.channel.TradeFreightQueryPort.WaybillCostRow;
import com.starcon.channel.TradeFreightQueryPort.WaybillCostScan;
import com.starcon.channel.WarehouseCostQueryPort.DutyEstimatePageView;
import com.starcon.channel.WarehouseCostQueryPort.DutyEstimateView;
import com.starcon.channel.WarehouseCostQueryPort.FirstMileDetailView;
import com.starcon.channel.WarehouseCostQueryPort.FirstMileLineView;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 10.6.1.1 成本分析。
 *
 * <p>蓝图：「分析物流成本：单位重量/单均运费、头程分摊后到岸成本占比、各渠道成本对比、
 * 超成本订单识别、头程费用分摊明细。输入：实际运费与头程费用；包裹与发运记录。
 * 输出：成本报表 + 分摊明细 + 异常订单清单。」并加粗
 * 「<b>物流成本常被低估</b>：真实成本 = 尾程 + 头程分摊 + 包材 + 关税 + 仓储费，
 * 报表必须全口径」。
 *
 * <p>🔴 本类唯一不可让步的约束就是那句加粗句。它的落地是四件事同时做，缺一件就退化成
 * 「把能算的算出来摆在那儿」的半截子报表：
 *
 * <ol>
 *   <li><b>五个分量各占一位，可得与不可得都是一等事实</b>。
 *       {@link CostComponent} 是闭集五位；{@code logistics_cost_component}
 *       （V213）逐位记着「这一位今天有没有数、缺了是谁的活」。
 *       不可得的那一位在界面上是「暂无数据 + 归属模块」，<b>不是 0</b>。
 *   <li><b>合计永远带着「我算了谁、没算谁」一起出现</b>。
 *       {@link CostCurrencyRow} 的 {@code includedComponents} / {@code missingComponents}
 *       与 {@code subtotal} 同时返回；{@link CostCoverage} 报全口径是否成立。
 *   <li><b>跨币种绝不相加</b>：所有金额逐币种分组，{@code subtotal} 只在同币种内成立。
 *   <li><b>null 与 0 是两件事</b>：分母为 0 的比率返回 {@code null}（算不出来），
 *       不是 {@code 0}（读成「这项成本占比为零」）。
 * </ol>
 *
 * <p>🔴 <b>比率一律用 BigDecimal 显式刻度除法</b>，绝不用浮点、也不做整数除法：
 * {@code total.divide(denominator, 4, RoundingMode.HALF_UP)}。
 * PG 里的 {@code int/int} 会静默变成整数除法（2/3 ⇒ 0），而那个 0 看上去仍像百分比 ——
 * 成本报表上的「0% 占比」会让人以为这项成本不重要。
 *
 * <p>🔴 <b>跨服务只读</b>：尾程运费来自 trade（{@link TradeFreightQueryPort}），
 * 头程与关税来自 warehouse（{@link WarehouseCostQueryPort}）。
 * 本类<b>一条 SQL 都不碰别的服务的库</b>，跨服务不建 FK / 视图 / 共享查询。
 *
 * <p>🔴 <b>头程分摊是本服务自持的事实</b>（{@code logistics_cost_allocation*}，V213）：
 * 12 财务（finance 服务）在本仓库<b>不存在</b>，所以分摊明细落在这里，
 * 并在能力声明里如实标注「本服务自有口径」。规则<b>版本化</b>，改版留历史版本，
 * 不就地覆盖（蓝图 5.2.1.3 加粗句）。
 */
@Service
public class LogisticsCostAnalysisService {

  /** 报表默认时间窗（天）。用户没给窗口时用它，并**在响应里如实报出**。 */
  static final int DEFAULT_WINDOW_DAYS = 30;

  /** 单均运费 / 单位重量运费的刻度（与金额列同为 4 位小数）。 */
  static final int MONEY_SCALE = 4;

  /** 占比的刻度（6 位，与数量列同精度）。 */
  static final int RATIO_SCALE = 6;

  /** 超成本判定的默认倍数：单位重量运费超过租户平均值的 1.5 倍。 */
  static final BigDecimal DEFAULT_OVER_COST_MULTIPLIER = new BigDecimal("1.5");

  private final DSLContext sql;
  private final TradeFreightQueryPort trade;
  private final WarehouseCostQueryPort warehouse;

  public LogisticsCostAnalysisService(
      DSLContext sql, TradeFreightQueryPort trade, WarehouseCostQueryPort warehouse) {
    this.sql = sql;
    this.trade = trade;
    this.warehouse = warehouse;
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 能力声明：五个成本分量逐位报可得性与口径。
   *
   * <p>口径台账落在 {@code logistics_cost_component}（V213）而不是 Java 常量里 ——
   * 「这个分量今天有没有数」要能在界面上如实显示，还要能指出**缺了是谁的活**，
   * 写死在代码里就做不到。
   */
  public LogisticsCostCapabilities capabilities() {
    ensureComponents();
    List<CostComponentCapability> components = readComponents();

    List<CostCapabilityDimension> dimensions =
        List.of(
            new CostCapabilityDimension(
                "LAST_MILE_FREIGHT",
                "尾程运费：承运商侧实际登记的运费",
                true,
                "trade 服务 logistics_waybill_freight（V132，10.2.1.1）· 经 "
                    + "GET /trade/logistics/waybills/list 读**现行**行",
                "",
                "",
                "运费由人工登记或渠道对账单登记，**不是本模块算的**；"
                    + "「未登记」是一个可读状态（NOT_REGISTERED），与「登记为 0」是两回事。"
                    + "同库 freight_rate_card（V140，10.1.2.2）是**费率卡**，"
                    + "它的试算结果口径是 RULE_ESTIMATE（估算），不是实际成本，所以不进本报表的合计"),
            new CostCapabilityDimension(
                "FIRST_MILE_ALLOCATION",
                "头程分摊：把一张头程发运单的成本按规则版本分到 SKU 行上",
                true,
                "warehouse 服务 first_mile_shipment（V80，10.4.1.1）· 经 "
                    + "GET /warehouse/first-mile-shipments 读；分摊单由本服务自持（V213）",
                "",
                "",
                "分摊规则**版本化**：改版留历史版本，不就地覆盖（蓝图 5.2.1.3）。"
                    + "每张分摊单带结账时点（cost_as_of），成本按哪个时点结的查得到"),
            new CostCapabilityDimension(
                "DUTY_AND_TAX",
                "关税与增值税/GST",
                true,
                "warehouse 服务 duty_tax_estimate / duty_tax_payment（V90，10.5.1.3）· 经 "
                    + "GET /warehouse/duty-taxes 读",
                "",
                "",
                "**实缴**进合计；未缴的只进**估算**序列。估算不是真实成本，"
                    + "把它加进合计等于报出一个掺了预估值却看着全口径的数"),
            new CostCapabilityDimension(
                "PACKAGING",
                "包材成本（**参数**口径：租户维护的每票包材成本）",
                true,
                "本服务 logistics_cost_parameter（V213）· 租户维护的参数，不是账单事实",
                "",
                "",
                "仓库（warehouse 的 packing_material）**只有外形尺寸与自重，没有任何成本列**，"
                    + "所以包材没有事实来源。本模块给它一个**参数**口径："
                    + "包材 = 每票包材成本参数 × 本窗该币种的运单票数。"
                    + "界面上与「事实」**分色**显示；没有维护参数的币种那一位是缺项，不是 0"),
            new CostCapabilityDimension(
                "STORAGE_FEE",
                "仓储费",
                false,
                "",
                "8 仓储作业（warehouse）—— 仓储计费与账单",
                "集成头里**没有任何仓储费表**（没有 storage_fee / storage_charge 之类的计费事实），"
                    + "仓储费目前没有可读来源",
                "报表的仓储费位显示「暂无数据」而不是 0；"
                    + "把它当成 0 记进成本，等于凭空给仓储这一段免了账"),
            new CostCapabilityDimension(
                "FINANCE_RECONCILIATION",
                "与 12 财务的最终对账",
                false,
                "",
                "12 财务（finance 服务）",
                "backend/services/ 下**没有 finance 服务**（只有 identity / catalog / channel / "
                    + "warehouse / trade / crm）。本模块**绝不读写一个不存在的服务**",
                "头程费用分摊明细由**本服务自持事实**（V213）落地，"
                    + "并如实标注为「本服务自有口径」；与 12 财务的最终对账"
                    + "留待 finance 落地后经授权 API / event 衔接"),
            new CostCapabilityDimension(
                "DOWNSTREAM_CONSUMERS",
                "下游 5 定价 / 13 利润",
                false,
                "",
                "5 定价 / 13 利润",
                "下游模块未交付，本模块只负责**产出**成本事实，不代它们做定价与利润决策",
                "本模块的口径（分摊规则版本 + 结账时点）被下游引用时，"
                    + "**同一 SKU 今天 30% 明天 25%** 必须能由这两个字段解释清楚"));

    return new LogisticsCostCapabilities(
        components,
        dimensions,
        "成本口径：尾程按运单签发时刻归集；头程按**分摊规则版本 + 结账时点（cost_as_of）**归集；"
            + "关税按**实缴**进合计、未缴只进估算序列。同一时间窗内，跨币种金额逐币种展示、绝不相加。",
        "12 财务（finance 服务）在本仓库不存在：头程费用分摊明细由本服务自持事实落地，"
            + "标注为「本服务自有口径」；最终对账留待 finance 落地后经授权 API / event 衔接。",
        List.of(
            "包材与仓储费**没有取数来源**，这两位显示「暂无数据」而不是 0。",
            "比率的分母为 0 时返回 null（算不出来），与 0（真的就是这个数）在界面上长得不一样。",
            "所有金额为 NUMERIC 精确值，全程禁浮点。"));
  }

  /**
   * 确保五个成本分量的口径台账在本租户存在。
   *
   * <p>刻意用 {@code on conflict do nothing}：口径台账**只补缺，不覆盖** ——
   * 免得每次访问报表都把人工维护过的说明冲掉。
   */
  private void ensureComponents() {
    UUID tenant = currentTenant();
    int order = 0;
    for (CostComponent component : CostComponent.values()) {
      order++;
      ComponentMeta meta = componentMeta(component);
      sql.execute(
          "insert into logistics_cost_component"
              + " (id,tenant_id,component,display_name,available,source_kind,source,basis,owner_module,"
              + "  unavailable_reason,impact,display_order,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?,now(),now())"
              + " on conflict (tenant_id,component) do nothing",
          Ids.next(),
          tenant,
          component.name(),
          component.displayName,
          meta.available(),
          meta.sourceKind(),
          meta.source(),
          meta.basis(),
          meta.ownerModule(),
          meta.reason(),
          meta.impact(),
          order);
    }
  }

  /**
   * 一个成本分量的默认口径。
   *
   * @param available 现在能不能取到这个分量的**金额**
   * @param sourceKind FACT = 事实表；PARAMETER = 租户维护的参数
   */
  private record ComponentMeta(
      boolean available,
      String sourceKind,
      String source,
      String basis,
      String ownerModule,
      String reason,
      String impact) {}

  /** 每个分量的默认口径。 */
  private static ComponentMeta componentMeta(CostComponent component) {
    return switch (component) {
      case LAST_MILE_FREIGHT ->
          new ComponentMeta(
              true,
              "FACT",
              "trade 服务 logistics_waybill_freight（V132，10.2.1.1）· 经 "
                  + "GET /trade/logistics/waybills/list 读**现行**行",
              "按运单签发时刻归集；金额来自人工登记或渠道对账单登记的**现行**行"
                  + "（更正走「追加新行 + 撤下旧行」，历史行留档）",
              "",
              "",
              "「未登记运费」是一个可读状态（NOT_REGISTERED），与「登记为 0」是两回事；"
                  + "未登记的票不计入单均运费与单位重量运费的分母");
      case FIRST_MILE_ALLOCATION ->
          new ComponentMeta(
              true,
              "FACT",
              "warehouse 服务 first_mile_shipment（V80，10.4.1.1）· 经 "
                  + "GET /warehouse/first-mile-shipments 读；分摊单由本服务自持（V213）",
              "按**分摊规则版本 + 结账时点（cost_as_of）**归集；规则改版留历史版本、不就地覆盖",
              "",
              "",
              "头程成本进报表前必须先有一张分摊单；未分摊的头程发运单**不计入**"
                  + "（没分摊的成本进了合计就等于把整批发运的成本摊到了不明的地方）");
      case PACKAGING ->
          new ComponentMeta(
              true,
              "PARAMETER",
              "本服务 logistics_cost_parameter（**参数**：租户维护的每票包材成本）· "
                  + "经 /channel/logistics-cost-analysis/parameters 维护",
              "包材金额 = 每票包材成本**参数** × 本窗该币种的运单票数。"
                  + "这是**参数口径**不是事实（仓库没有包材成本列），界面上与事实分色",
              "",
              "",
              "该币种**没有维护包材参数**时，这一位是缺项（null + missingComponents），"
                  + "不是 0；把参数报成事实比不报这一位更危险");
      case DUTY_AND_TAX ->
          new ComponentMeta(
              true,
              "FACT",
              "warehouse 服务 duty_tax_estimate / duty_tax_payment（V90，10.5.1.3）· 经 "
                  + "GET /warehouse/duty-taxes 读",
              "**实缴**进合计；未缴的只进**估算**序列（估算不是真实成本）",
              "",
              "",
              "DDU 下买方付关税，那部分**不是**我方成本，所以估算单里的 landed_cost_seller "
                  + "与 landed_cost_total 含义不同；本模块只取**税额**");
      case STORAGE_FEE ->
          new ComponentMeta(
              false,
              "FACT",
              "",
              "",
              "8 仓储作业（warehouse）—— 仓储计费与账单",
              "集成头里**没有任何仓储费表**（没有 storage_fee / storage_charge 之类的计费事实），"
                  + "仓储费目前没有可读来源",
              "报表的仓储费位显示「暂无数据」而不是 0；"
                  + "把它当成 0 记进成本，等于凭空给仓储这一段免了账");
    };
  }

  /**
   * 读回本租户的成本分量口径台账（{@code logistics_cost_component}）。
   *
   * <p>台账是**唯一**口径来源：报表上「这一位今天有没有数、缺了是谁的活」
   * 全部从这里读，而不是从 Java 常量读。这样上游交付与否由**数据**决定，
   * 不用改代码。
   *
   * <p>🔴 台账里没有的分量**不会被凭空补出来**：缺一行就少一行，
   * 界面上就少一位 —— 宁可少显示一位，也不显示一个没有来源的 0。
   */
  private List<CostComponentCapability> readComponents() {
    List<CostComponentCapability> components = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select component,display_name,available,source_kind,source,basis,owner_module,"
                + "unavailable_reason,impact from logistics_cost_component"
                + " where tenant_id=? order by display_order, component",
            currentTenant())) {
      components.add(
          new CostComponentCapability(
              r.get("component", String.class),
              r.get("display_name", String.class),
              r.get("available", Boolean.class),
              r.get("source_kind", String.class),
              r.get("source", String.class),
              r.get("basis", String.class),
              r.get("owner_module", String.class),
              r.get("unavailable_reason", String.class),
              r.get("impact", String.class)));
    }
    return components;
  }

  /**
   * 从台账里取一个分量的口径；台账缺这一位时返回 {@code null}，
   * 调用方据此**不生成那一行**（而不是造一个 0）。
   */
  private CostComponentCapability capabilityOf(
      List<CostComponentCapability> components, CostComponent component) {
    for (CostComponentCapability capability : components) {
      if (component.name().equals(capability.component())) {
        return capability;
      }
    }
    return null;
  }

  // ==================================================================
  // 成本参数（租户维护的参数口径）
  // ==================================================================

  /** 成本参数清单（真分页 + 全量汇总，汇总不受分页影响）。 */
  public LogisticsCostParameterPage parameters(int page, int size) {
    int pageSize = Math.min(Math.max(size, 1), 200);
    int pageIndex = Math.max(page, 0);
    long total =
        sql.fetchOne("select count(*) from logistics_cost_parameter where tenant_id=?",
                currentTenant())
            .get(0, Long.class);
    List<LogisticsCostParameterRow> items = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select id,component,currency,amount_per_waybill,unit,note,created_at,updated_at"
                + " from logistics_cost_parameter where tenant_id=?"
                + " order by component,currency limit ? offset ?",
            currentTenant(),
            pageSize,
            (long) pageIndex * pageSize)) {
      items.add(
          new LogisticsCostParameterRow(
              r.get("id", UUID.class),
              r.get("component", String.class),
              r.get("currency", String.class),
              r.get("amount_per_waybill", BigDecimal.class),
              r.get("unit", String.class),
              r.get("note", String.class),
              r.get("created_at", OffsetDateTime.class),
              r.get("updated_at", OffsetDateTime.class)));
    }
    List<String> notes = new ArrayList<>();
    notes.add(
        "成本参数是**租户维护的口径**，不是账单事实 —— 界面上与「事实」分色显示。");
    notes.add(
        "目前只有包材走参数口径：包材金额 = 每票包材成本 × 本窗该币种的运单票数。");
    notes.add("没有维护参数的币种在报表里是**缺项**（不计入合计），不是 0。");
    return new LogisticsCostParameterPage(
        List.copyOf(items),
        pageIndex,
        pageSize,
        total,
        pageCount(total, pageSize),
        notes);
  }

  /**
   * 维护一条成本参数（同一个分量 + 币种只有一行，改就是改）。
   *
   * <p>🔴 只接受 PACKAGING：其它四个分量都有事实来源，给它们开参数入口等于
   * 制造第二个真相（「这个数到底是算出来的还是填出来的」）。
   */
  public LogisticsCostParameterRow saveParameter(LogisticsCostParameterRequest request) {
    UUID tenant = currentTenant();
    String component = request.component().trim().toUpperCase(Locale.ROOT);
    if (!CostComponent.PACKAGING.name().equals(component)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "目前只有包材（PACKAGING）走参数口径：其它分量的成本都有事实来源，"
              + "开参数入口等于制造第二个真相");
    }
    String currency = request.currency().trim().toUpperCase(Locale.ROOT);
    if (!currency.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是三位大写字母");
    }
    BigDecimal amount = request.amountPerWaybill();
    if (amount == null || amount.signum() < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每票包材成本不能为负");
    }
    // upsert：同一分量 + 币种只有一行（唯一键 logistics_cost_parameter）。
    sql.execute(
        "insert into logistics_cost_parameter"
            + " (id,tenant_id,component,currency,amount_per_waybill,unit,note,created_at,updated_at)"
            + " values (?,?,?,?,?,'PER_WAYBILL',?,now(),now())"
            + " on conflict (tenant_id,component,currency) do update"
            + " set amount_per_waybill=excluded.amount_per_waybill, note=excluded.note,"
            + " updated_at=now()",
        Ids.next(),
        tenant,
        component,
        currency,
        amount,
        request.note() == null ? "" : request.note().trim());
    Record row =
        sql.fetchOne(
            "select id,component,currency,amount_per_waybill,unit,note,created_at,updated_at"
                + " from logistics_cost_parameter where tenant_id=? and component=? and currency=?",
            tenant,
            component,
            currency);
    return new LogisticsCostParameterRow(
        row.get("id", UUID.class),
        row.get("component", String.class),
        row.get("currency", String.class),
        row.get("amount_per_waybill", BigDecimal.class),
        row.get("unit", String.class),
        row.get("note", String.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  /** 删掉一条参数：那个币种的包材位立刻回到「缺项」，不会留下一个 0。 */
  public void deleteParameter(UUID id) {
    int rows =
        sql.execute(
            "delete from logistics_cost_parameter where tenant_id=? and id=?",
            currentTenant(),
            id);
    if (rows == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "成本参数不存在");
    }
  }

  /**
   * 包材（**参数**口径）逐币种金额。
   *
   * <p>包材金额 = 每票包材成本**参数** × 本窗该币种的运单票数。
   * 没有维护参数的币种 ⇒ <b>没有这一行</b>（不是 0 元的行）。
   *
   * @param waybillCountByCurrency 本窗各币种的运单票数（来自 trade 扫描）
   */
  private Map<String, CurrencyBucket> packagingByCurrency(
      Map<String, Long> waybillCountByCurrency) {
    Map<String, CurrencyBucket> buckets = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select currency, amount_per_waybill from logistics_cost_parameter"
                + " where tenant_id=? and component='PACKAGING' order by currency",
            currentTenant())) {
      String currency = r.get("currency", String.class);
      Long count = waybillCountByCurrency.get(currency);
      if (count == null || count == 0) {
        // 这个币种本窗没有运单 ⇒ 包材没有发生额。**不是 0**：这个币种本窗根本没货。
        continue;
      }
      BigDecimal per = r.get("amount_per_waybill", BigDecimal.class);
      if (per == null) {
        continue;
      }
      CurrencyBucket bucket = new CurrencyBucket();
      bucket.amount = per.multiply(BigDecimal.valueOf(count)).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
      bucket.count = count;
      bucket.registered = count;
      buckets.put(currency, bucket);
    }
    return buckets;
  }

  // ==================================================================
  // 成本报表
  // ==================================================================

  /**
   * 成本总览（成本报表）。
   *
   * <p>🔴 <b>上游读不到就整个报表 503</b>，绝不返回「尾程 0 元」冒充「上游没有运费」。
   * 「没有运费数据」与「上游挂了」在账上是两个完全不同的事，混起来会让人照着空报表
   * 做定价决策。
   */
  public CostOverview overview(String bearer, OffsetDateTime from, OffsetDateTime to) {
    ensureComponents();
    OffsetDateTime windowTo = to == null ? OffsetDateTime.now(ZoneOffset.UTC) : to;
    OffsetDateTime windowFrom =
        from == null ? windowTo.minusDays(DEFAULT_WINDOW_DAYS) : from;
    if (!windowFrom.isBefore(windowTo)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "时间窗的开始必须早于结束");
    }

    WaybillCostScan scan = trade.scan(windowFrom, windowTo, bearer);
    LastMileBuckets lastMile = lastMileByCurrency(scan.items());
    Map<String, CurrencyBucket> firstMile = firstMileByCurrency(windowFrom, windowTo);
    DutyBuckets duty = dutyBuckets(bearer);
    // 包材走**参数**口径：需要本窗各币种的运单票数。
    Map<String, Long> waybillCount = waybillCountByCurrency(scan.items());
    Map<String, CurrencyBucket> packaging = packagingByCurrency(waybillCount);

    // 口径台账（每个分量的来源 / 可得性）以**表**为准，不以代码常量为准。
    List<CostComponentCapability> ledger = readComponents();

    List<CostComponentAmount> components =
        new ArrayList<>();
    addComponentAmount(
        components, ledger, CostComponent.LAST_MILE_FREIGHT, lastMile.registered(), Map.of());
    addComponentAmount(
        components, ledger, CostComponent.FIRST_MILE_ALLOCATION, firstMile, Map.of());
    addComponentAmount(
        components, ledger, CostComponent.DUTY_AND_TAX, duty.paid(), duty.estimated());
    addComponentAmount(components, ledger, CostComponent.PACKAGING, packaging, Map.of());
    addComponentAmount(components, ledger, CostComponent.STORAGE_FEE, Map.of(), Map.of());

    // 逐币种一行：**真的能读到金额**的币种才出现在金额区；
    // 只有「未登记运费」票的币种也会出现，但那一行的金额是 null（读不到），不是 0。
    Set<String> currencies = new LinkedHashSet<>();
    lastMile.registered().keySet().forEach(currencies::add);
    lastMile.notRegistered().keySet().forEach(currencies::add);
    firstMile.keySet().forEach(currencies::add);
    duty.paid().keySet().forEach(currencies::add);
    packaging.keySet().forEach(currencies::add);

    List<CostCurrencyRow> rows = new ArrayList<>();
    for (String currency : currencies) {
      BigDecimal last = amountOf(lastMile.registered(), currency);
      BigDecimal first = amountOf(firstMile, currency);
      BigDecimal tax = amountOf(duty.paid(), currency);
      BigDecimal pack = amountOf(packaging, currency);
      List<String> included = new ArrayList<>();
      List<String> missing = new ArrayList<>();
      BigDecimal sum = BigDecimal.ZERO;
      if (last != null) {
        included.add(CostComponent.LAST_MILE_FREIGHT.name());
        sum = sum.add(last);
      } else {
        missing.add(CostComponent.LAST_MILE_FREIGHT.name());
      }
      if (first != null) {
        included.add(CostComponent.FIRST_MILE_ALLOCATION.name());
        sum = sum.add(first);
      } else {
        missing.add(CostComponent.FIRST_MILE_ALLOCATION.name());
      }
      if (tax != null) {
        included.add(CostComponent.DUTY_AND_TAX.name());
        sum = sum.add(tax);
      } else {
        missing.add(CostComponent.DUTY_AND_TAX.name());
      }
      if (pack != null) {
        included.add(CostComponent.PACKAGING.name());
        sum = sum.add(pack);
      } else {
        missing.add(CostComponent.PACKAGING.name());
      }
      // 仓储费在本仓库**没有来源** —— 缺了它是**全局**的，逐币种也要标出来。
      missing.add(CostComponent.STORAGE_FEE.name());
      // 🔴 一个分量都没读到 ⇒ 合计是 **null**（算不出来），不是 0。
      //   报一个 0 元的合计，意思就成了「这批货的物流成本是 0」。
      BigDecimal subtotal = included.isEmpty() ? null : sum;
      // 🔴 头程分摊后到岸成本占比：分母是**本币种的合计**。
      //   合计为 0 或缺失时是 null（算不出来），不是 0（读成「头程占 0%」）。
      BigDecimal lastShare = share(last, subtotal);
      BigDecimal firstShare = share(first, subtotal);
      BigDecimal taxShare = share(tax, subtotal);
      BigDecimal packShare = share(pack, subtotal);
      rows.add(
          new CostCurrencyRow(
              currency, last, first, tax, pack, subtotal,
              lastShare, firstShare, taxShare, packShare,
              included, missing));
    }

    List<CostUnitMetric> unitMetrics = new ArrayList<>();
    Set<String> unitCurrencies = new LinkedHashSet<>(lastMile.registered().keySet());
    unitCurrencies.addAll(lastMile.notRegistered().keySet());
    for (String currency : unitCurrencies) {
      CurrencyBucket bucket = lastMile.registered().get(currency);
      long notRegistered = lastMile.notRegistered().getOrDefault(currency, 0L);
      if (bucket == null) {
        // 这个币种**一票都没登记运费**：金额是 null（读不到），不是 0。
        unitMetrics.add(
            new CostUnitMetric(currency, null, 0L, notRegistered, null, null, null));
        continue;
      }
      unitMetrics.add(
          new CostUnitMetric(
              currency,
              bucket.amount,
              bucket.registered,
              notRegistered,
              bucket.weight.signum() == 0 ? null : bucket.weight,
              divide(bucket.amount, BigDecimal.valueOf(bucket.registered)),
              divide(bucket.amount, bucket.weight)));
    }

    List<String> missingComponents = new ArrayList<>();
    for (CostComponent component : CostComponent.values()) {
      boolean hasAmount =
          switch (component) {
            case LAST_MILE_FREIGHT -> !lastMile.registered().isEmpty();
            case FIRST_MILE_ALLOCATION -> !firstMile.isEmpty();
            case DUTY_AND_TAX -> !duty.paid().isEmpty();
            case PACKAGING -> !packaging.isEmpty();
            case STORAGE_FEE -> false;
          };
      if (!hasAmount) {
        missingComponents.add(component.name());
      }
    }

    List<String> notes = new ArrayList<>();
    notes.add(
        "尾程按运单签发时刻归集；头程按**分摊规则版本 + 结账时点（cost_as_of）**归集；"
            + "关税按**实缴**进合计、未缴只进估算序列。");
    notes.add(
        "包材与仓储费**没有取数来源**，这两位在任何币种下都显示「暂无数据」而不是 0 —— "
            + "合计因此是「已计入分量之和」，missingComponents 逐条列明没算谁。");
    if (scan.truncated()) {
      notes.add(
          "本次尾程扫描撞到了 "
              + TradeFreightQueryPort.MAX_PAGES
              + " 页上限（"
              + (long) TradeFreightQueryPort.PAGE_SIZE * TradeFreightQueryPort.MAX_PAGES
              + " 票），只统计了前 "
              + scan.items().size()
              + " 票 —— 收窄时间窗后数字才是全量。");
    }

    return new CostOverview(
        OffsetDateTime.now(ZoneOffset.UTC),
        windowFrom,
        windowTo,
        components,
        rows,
        unitMetrics,
        new CostCoverage(
            missingComponents.isEmpty(),
            missingComponents,
            "complete=false 表示五个分量里至少有一个没取到数；"
                + "缺的那部分**不计入**任何合计，报表不会用一个 0 把窟窿糊上。",
            notes),
        scan.items().size(),
        scan.truncated(),
        notes);
  }

  // ==================================================================
  // 渠道成本对比
  // ==================================================================

  /**
   * 各渠道成本对比。
   *
   * <p>按（承运商代码 + 承运渠道）分组，**逐币种**给指标。
   * 排序固定为「运单数降序 + 承运商代码升序」：跨币种没有可比的总额，
   * 用金额排序会在换币种后整张表重排，而那不是成本差异，是汇率差异。
   */
  public CostChannelComparison byChannel(String bearer, OffsetDateTime from, OffsetDateTime to) {
    OffsetDateTime windowTo = to == null ? OffsetDateTime.now(ZoneOffset.UTC) : to;
    OffsetDateTime windowFrom = from == null ? windowTo.minusDays(DEFAULT_WINDOW_DAYS) : from;
    if (!windowFrom.isBefore(windowTo)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "时间窗的开始必须早于结束");
    }

    WaybillCostScan scan = trade.scan(windowFrom, windowTo, bearer);

    Map<String, List<WaybillCostRow>> groups = new LinkedHashMap<>();
    for (WaybillCostRow row : scan.items()) {
      String key = row.carrierCode() + "" + (row.carrierChannel() == null ? "" : row.carrierChannel());
      groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
    }

    List<CostChannelRow> rows = new ArrayList<>();
    long totalWaybills = 0;
    long totalRegistered = 0;
    long totalNotRegistered = 0;
    for (List<WaybillCostRow> group : groups.values()) {
      // 🔴 只有**登记了运费**的票才进金额桶。没登记的票只计票数 ——
      //   给它们造一个 0 元的币种行，界面上就成了「这个渠道的运费是 0」。
      Map<String, CurrencyBucket> byCurrency = new LinkedHashMap<>();
      long registered = 0;
      long notRegistered = 0;
      for (WaybillCostRow row : group) {
        if (row.freightAmount() == null) {
          notRegistered++;
          continue;
        }
        String currency = row.freightCurrency();
        if (currency == null || currency.isBlank()) {
          // 有金额但没有币种：无法归到任何币种，也不能跨币种加 —— 只计票数。
          notRegistered++;
          continue;
        }
        CurrencyBucket bucket = byCurrency.computeIfAbsent(currency, c -> new CurrencyBucket());
        bucket.count++;
        bucket.amount = bucket.amount.add(row.freightAmount());
        bucket.registered++;
        if (row.grossWeightKg() != null) {
          bucket.weight = bucket.weight.add(row.grossWeightKg());
        }
        registered++;
      }
      List<CostChannelCurrencyMetric> metrics = new ArrayList<>();
      for (Map.Entry<String, CurrencyBucket> entry : byCurrency.entrySet()) {
        CurrencyBucket bucket = entry.getValue();
        metrics.add(
            new CostChannelCurrencyMetric(
                entry.getKey(),
                bucket.amount,
                bucket.registered,
                bucket.weight.signum() == 0 ? null : bucket.weight,
                divide(bucket.amount, BigDecimal.valueOf(bucket.registered)),
                divide(bucket.amount, bucket.weight)));
      }
      WaybillCostRow first = group.get(0);
      totalWaybills += group.size();
      totalRegistered += registered;
      totalNotRegistered += notRegistered;
      rows.add(
          new CostChannelRow(
              first.carrierCode(),
              first.carrierChannel(),
              first.carrierChannelLabel(),
              group.size(),
              registered,
              notRegistered,
              metrics));
    }

    rows.sort(
        Comparator.comparingLong(CostChannelRow::waybills)
            .reversed()
            .thenComparing(CostChannelRow::carrierCode, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(
                CostChannelRow::carrierChannel, Comparator.nullsFirst(Comparator.naturalOrder())));

    List<String> notes = new ArrayList<>();
    notes.add("对比口径：按（承运商代码 + 承运渠道）分组，**逐币种**给指标，跨币种不相加。");
    notes.add("「未登记运费」的票计入票数，但**不计入**金额与单位成本的分母 —— "
        + "把它们算进分母会让单位成本被稀释，看起来更便宜。");
    if (scan.truncated()) {
      notes.add("本次尾程扫描撞到了扫描上限，只统计了前 " + scan.items().size() + " 票。");
    }

    return new CostChannelComparison(
        OffsetDateTime.now(ZoneOffset.UTC),
        windowFrom,
        windowTo,
        rows,
        new CostChannelSummary(
            rows.size(), totalWaybills, totalRegistered, totalNotRegistered),
        scan.items().size(),
        scan.truncated(),
        notes);
  }

  // ==================================================================
  // 超成本订单识别
  // ==================================================================

  /**
   * 超成本订单清单。
   *
   * <p>判据：<b>单位重量运费</b>（运费 ÷ 毛重）超过同币种租户平均值的
   * {@code multiplier} 倍。
   *
   * <p>🔴 为什么用「单位重量」而不是「运费总额」：总额大不代表贵 —— 一票 30 公斤的
   * 订单运费自然高。拿总额比大小，一票 3 公斤的高价小件永远排在最便宜的那一档里，
   * 超成本订单反而看不见。
   *
   * <p>🔴 分母为 0 的票（没有毛重）**不参与**平均与判定：运费 ÷ 0 没有意义，
   * 而且把它算进平均会让平均被拉低，判定线跟着失真。
   */
  public CostOverCostOrderPage overCostOrders(
      String bearer, OffsetDateTime from, OffsetDateTime to, BigDecimal multiplier, int page, int size) {
    OffsetDateTime windowTo = to == null ? OffsetDateTime.now(ZoneOffset.UTC) : to;
    OffsetDateTime windowFrom = from == null ? windowTo.minusDays(DEFAULT_WINDOW_DAYS) : from;
    if (!windowFrom.isBefore(windowTo)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "时间窗的开始必须早于结束");
    }
    BigDecimal factor =
        multiplier == null || multiplier.signum() <= 0 ? DEFAULT_OVER_COST_MULTIPLIER : multiplier;
    int pageSize = Math.min(Math.max(size, 1), 200);
    int pageIndex = Math.max(page, 0);

    WaybillCostScan scan = trade.scan(windowFrom, windowTo, bearer);

    // 逐币种算平均单位重量运费（只统计「有运费 + 有毛重」的票）。
    Map<String, BigDecimal> totals = new LinkedHashMap<>();
    Map<String, BigDecimal> weights = new LinkedHashMap<>();
    Map<String, Long> evaluated = new LinkedHashMap<>();
    for (WaybillCostRow row : scan.items()) {
      if (row.freightAmount() == null
          || row.grossWeightKg() == null
          || row.grossWeightKg().signum() <= 0
          || row.freightCurrency() == null
          || row.freightCurrency().isBlank()) {
        continue;
      }
      String currency = row.freightCurrency();
      totals.merge(currency, row.freightAmount(), BigDecimal::add);
      weights.merge(currency, row.grossWeightKg(), BigDecimal::add);
      evaluated.merge(currency, 1L, Long::sum);
    }

    List<CostCurrencyThreshold> thresholds = new ArrayList<>();
    Map<String, BigDecimal> thresholdByCurrency = new LinkedHashMap<>();
    for (String currency : totals.keySet()) {
      BigDecimal average = divide(totals.get(currency), weights.get(currency));
      BigDecimal threshold = average == null ? null : average.multiply(factor).setScale(6, RoundingMode.HALF_UP);
      thresholdByCurrency.put(currency, threshold);
      thresholds.add(new CostCurrencyThreshold(currency, average, threshold, evaluated.get(currency), 0));
    }

    List<CostOverCostOrderRow> overCost = new ArrayList<>();
    for (WaybillCostRow row : scan.items()) {
      if (row.freightAmount() == null
          || row.grossWeightKg() == null
          || row.grossWeightKg().signum() <= 0
          || row.freightCurrency() == null
          || row.freightCurrency().isBlank()) {
        continue;
      }
      BigDecimal threshold = thresholdByCurrency.get(row.freightCurrency());
      if (threshold == null) {
        continue;
      }
      BigDecimal unit = divide(row.freightAmount(), row.grossWeightKg());
      if (unit == null || unit.compareTo(threshold) <= 0) {
        continue;
      }
      BigDecimal excess = unit.subtract(threshold).multiply(row.grossWeightKg()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
      overCost.add(
          new CostOverCostOrderRow(
              row.id(),
              row.waybillNo(),
              row.salesOrderNo(),
              row.outboundOrderNo(),
              row.carrierCode(),
              row.carrierChannel(),
              row.destinationCountry(),
              row.freightCurrency(),
              row.freightAmount(),
              row.grossWeightKg(),
              unit,
              divide(totals.get(row.freightCurrency()), weights.get(row.freightCurrency())),
              threshold,
              excess,
              "单位重量运费 " + unit.stripTrailingZeros().toPlainString() + " 超过同币种平均 "
                  + divide(totals.get(row.freightCurrency()), weights.get(row.freightCurrency()))
                      .stripTrailingZeros().toPlainString()
                  + " 的 " + factor.stripTrailingZeros().toPlainString() + " 倍",
              row.createdAt()));
    }

    overCost.sort(
        Comparator.comparing(CostOverCostOrderRow::excessAmount, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(CostOverCostOrderRow::waybillNo, Comparator.nullsFirst(Comparator.naturalOrder())));

    Map<String, Long> overByCurrency = new LinkedHashMap<>();
    for (CostOverCostOrderRow row : overCost) {
      overByCurrency.merge(row.currency(), 1L, Long::sum);
    }
    List<CostCurrencyThreshold> finalThresholds = new ArrayList<>();
    for (CostCurrencyThreshold t : thresholds) {
      finalThresholds.add(
          new CostCurrencyThreshold(
              t.currency(),
              t.averageUnitFreight(),
              t.thresholdUnitFreight(),
              t.evaluatedWaybills(),
              overByCurrency.getOrDefault(t.currency(), 0L)));
    }

    long total = overCost.size();
    // 🔴 用 long 算偏移：pageIndex * pageSize 用 int 会溢出成一个负数，
    //   症状是「翻到很后面的一页返回空列表」而总数却写着有几千条。
    long offset = (long) pageIndex * pageSize;
    int fromIndex = (int) Math.min(offset, total);
    int toIndex = (int) Math.min((long) fromIndex + pageSize, total);
    List<CostOverCostOrderRow> pageItems = overCost.subList(fromIndex, toIndex);

    List<String> notes = new ArrayList<>();
    notes.add("判据：**单位重量运费**（运费 ÷ 毛重）超过同币种平均值的 " + factor.toPlainString() + " 倍。");
    notes.add("没有毛重、或没有登记运费的票**不参与**平均与判定 —— 运费 ÷ 0 没有意义。");
    if (overCost.isEmpty()) {
      notes.add("本时间窗内没有超成本运单。这是**真实的空**，不是「没查到」—— "
          + "上游读到了数据（" + scan.items().size() + " 票）但没有一票越线。");
    }
    if (scan.truncated()) {
      notes.add("本次尾程扫描撞到了扫描上限，只统计了前 " + scan.items().size() + " 票。");
    }

    return new CostOverCostOrderPage(
        OffsetDateTime.now(ZoneOffset.UTC),
        windowFrom,
        windowTo,
        factor,
        finalThresholds,
        List.copyOf(pageItems),
        pageIndex,
        pageSize,
        total,
        pageCount(total, pageSize),
        notes);
  }

  // ==================================================================
  // 分摊规则（版本化）
  // ==================================================================

  /** 分摊规则版本列表（真分页）。历史版本一直看得见。 */
  public CostAllocationRulePage rules(String status, int page, int size) {
    int pageSize = Math.min(Math.max(size, 1), 200);
    int pageIndex = Math.max(page, 0);
    String where = status == null || status.isBlank() ? "" : " and status = ?";
    Object[] params =
        status == null || status.isBlank()
            ? new Object[] {currentTenant()}
            : new Object[] {currentTenant(), status.trim().toUpperCase(Locale.ROOT)};

    long total =
        sql.fetchOne("select count(*) from logistics_cost_allocation_rule where tenant_id=?" + where, params)
            .get(0, Long.class);
    List<CostAllocationRuleRow> items = new ArrayList<>();
    List<Record> rows =
        sql.fetch(
            "select r.id,r.rule_code,r.rule_name,r.version,r.allocation_basis,r.status,"
                + "r.effective_from,r.effective_to,r.note,r.created_at,"
                + "(select count(*) from logistics_cost_allocation a"
                + "  where a.tenant_id=r.tenant_id and a.rule_id=r.id) as allocation_count"
                + " from logistics_cost_allocation_rule r"
                + " where r.tenant_id=?"
                + where
                + " order by r.rule_code, r.version desc limit ? offset ?",
            params.length == 1
                ? new Object[] {params[0], pageSize, (long) pageIndex * pageSize}
                : new Object[] {params[0], params[1], pageSize, (long) pageIndex * pageSize});
    for (Record r : rows) {
      items.add(ruleRow(r));
    }
    return new CostAllocationRulePage(
        List.copyOf(items),
        pageIndex,
        pageSize,
        total,
        pageCount(total, pageSize));
  }

  private CostAllocationRuleRow ruleRow(Record r) {
    String basis = r.get("allocation_basis", String.class);
    String status = r.get("status", String.class);
    return new CostAllocationRuleRow(
        r.get("id", UUID.class),
        r.get("rule_code", String.class),
        r.get("rule_name", String.class),
        r.get("version", Integer.class),
        basis,
        basisLabel(basis),
        status,
        statusLabel(status),
        r.get("effective_from", OffsetDateTime.class),
        r.get("effective_to", OffsetDateTime.class),
        r.get("note", String.class),
        r.get("allocation_count", Long.class),
        r.get("created_at", OffsetDateTime.class));
  }

  /**
   * 新建一个规则版本。
   *
   * <p>🔴 <b>只新建版本，不改既有版本</b>：同一 rule_code 的版本号递增，
   * 已被分摊引用过的版本的**口径**不可再改（V213 的触发器钉死）。
   * 启用新版本时旧版本转 RETIRED，<b>旧行留档</b> —— 历史分摊单继续指向它当时用的版本。
   */
  public CostAllocationRuleRow createRule(CostAllocationRuleRequest request, UUID actor) {
    UUID tenant = currentTenant();
    String code = request.ruleCode().trim();
    String name = request.ruleName().trim();
    if (code.isEmpty() || name.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "规则码与规则名都不能为空");
    }
    if (request.effectiveTo() != null && !request.effectiveTo().isAfter(request.effectiveFrom())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "失效时刻必须晚于生效时刻");
    }
    Integer currentVersion =
        sql.fetchOne(
                "select coalesce(max(version),0) from logistics_cost_allocation_rule"
                    + " where tenant_id=? and rule_code=?",
                tenant,
                code)
            .get(0, Integer.class);
    int version = (currentVersion == null ? 0 : currentVersion) + 1;

    UUID id = Ids.next();
    // 🔴 落库顺序：先以 **DRAFT** 落库，再退役旧现行版，最后把新版置为 ACTIVE。
    //   直接以 ACTIVE 落库会撞上 V213 的部分唯一索引 logistics_cost_rule_one_active
    //   （同一 rule_code 同时只能有一个现行版本）—— 症状是「新建现行版本报重复键」，
    //   而根因是「先启用后退役」这个顺序。
    sql.execute(
        "insert into logistics_cost_allocation_rule"
            + " (id,tenant_id,rule_code,rule_name,version,allocation_basis,status,"
            + " effective_from,effective_to,note,created_by,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,now(),now())",
        id,
        tenant,
        code,
        name,
        version,
        request.allocationBasis().name(),
        "DRAFT",
        request.effectiveFrom(),
        request.effectiveTo(),
        request.note() == null ? "" : request.note().trim(),
        actor);

    if (request.activate()) {
      // 旧现行版本转 RETIRED（**只改状态，不改口径**：口径被 V213 的触发器钉死）。
      // 必须排除**刚插入的这一行**：否则新建的版本自己把自己降级成 RETIRED，
      // 结果是「启用完之后一个现行版本都没有」—— 分摊规则没有现行版可用。
      sql.execute(
          "update logistics_cost_allocation_rule set status='RETIRED', updated_at=now()"
              + " where tenant_id=? and rule_code=? and status='ACTIVE' and id<>?",
          tenant,
          code,
          id);
      // 旧版已退役，新版才升为现行 —— 任意时刻都只有一个 ACTIVE 版本。
      sql.execute(
          "update logistics_cost_allocation_rule set status='ACTIVE', updated_at=now()"
              + " where tenant_id=? and id=?",
          tenant,
          id);
    }

    Record row =
        sql.fetchOne(
            "select r.id,r.rule_code,r.rule_name,r.version,r.allocation_basis,r.status,"
                + "r.effective_from,r.effective_to,r.note,r.created_at,"
                + "(select count(*) from logistics_cost_allocation a"
                + "  where a.tenant_id=r.tenant_id and a.rule_id=r.id) as allocation_count"
                + " from logistics_cost_allocation_rule r where r.tenant_id=? and r.id=?",
            tenant,
            id);
    return ruleRow(row);
  }

  // ==================================================================
  // 分摊单
  // ==================================================================

  /** 分摊单列表（真分页 + 全量汇总，汇总不受分页影响）。 */
  public CostAllocationPage allocations(String status, int page, int size) {
    int pageSize = Math.min(Math.max(size, 1), 200);
    int pageIndex = Math.max(page, 0);
    String where = status == null || status.isBlank() ? "" : " and status = ?";
    Object[] base =
        status == null || status.isBlank()
            ? new Object[] {currentTenant()}
            : new Object[] {currentTenant(), status.trim().toUpperCase(Locale.ROOT)};

    long total =
        sql.fetchOne("select count(*) from logistics_cost_allocation where tenant_id=?" + where, base)
            .get(0, Long.class);
    List<CostAllocationRow> items = new ArrayList<>();
    List<Record> rows =
        sql.fetch(
            "select id,allocation_code,rule_id,rule_code,rule_version,allocation_basis,"
                + "source_shipment_id,source_shipment_code,source_transport_mode,"
                + "source_carrier_name,destination_country_code,currency,source_total_amount,"
                + "allocated_total_amount,basis_total,line_count,status,computed_at,cost_as_of,"
                + "locked_at,superseded_at,superseded_by_rule_version,note,created_at"
                + " from logistics_cost_allocation where tenant_id=?"
                + where
                + " order by computed_at desc, allocation_code desc limit ? offset ?",
            base.length == 1
                ? new Object[] {base[0], pageSize, (long) pageIndex * pageSize}
                : new Object[] {base[0], base[1], pageSize, (long) pageIndex * pageSize});
    for (Record r : rows) {
      items.add(allocationRow(r));
    }

    long computed = 0;
    long locked = 0;
    long superseded = 0;
    for (String s : List.of("COMPUTED", "LOCKED", "SUPERSEDED")) {
      long count =
          sql.fetchOne(
                  "select count(*) from logistics_cost_allocation where tenant_id=? and status=?",
                  currentTenant(),
                  s)
              .get(0, Long.class);
      if ("COMPUTED".equals(s)) {
        computed = count;
      } else if ("LOCKED".equals(s)) {
        locked = count;
      } else {
        superseded = count;
      }
    }
    List<CostCurrencyTotal> byCurrency = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select currency, sum(allocated_total_amount) as amount, count(*) as cnt"
                + " from logistics_cost_allocation where tenant_id=? and status <> 'SUPERSEDED'"
                + " group by currency order by currency",
            currentTenant())) {
      byCurrency.add(
          new CostCurrencyTotal(
              r.get("currency", String.class), r.get("amount", BigDecimal.class), r.get("cnt", Long.class)));
    }

    List<String> notes = new ArrayList<>();
    notes.add("头程分摊按**结账时点（cost_as_of）**归入时间窗，与「算出来的时刻（computed_at）」是两个字段。");
    notes.add("同一张头程发运单同时只有一张现行分摊单；用新规则版本重算会**取代**旧单（旧单留档）。");
    notes.add("分摊规则改版**留历史版本**，不就地覆盖（蓝图 5.2.1.3）。");

    return new CostAllocationPage(
        List.copyOf(items),
        pageIndex,
        pageSize,
        total,
        pageCount(total, pageSize),
        computed,
        locked,
        superseded,
        byCurrency,
        notes);
  }

  /** 分摊单详情：头 + 逐行明细。 */
  public CostAllocationDetail allocation(UUID id) {
    UUID tenant = currentTenant();
    Record r =
        sql.fetchOne(
            "select id,allocation_code,rule_id,rule_code,rule_version,allocation_basis,"
                + "source_shipment_id,source_shipment_code,source_transport_mode,"
                + "source_carrier_name,destination_country_code,currency,source_total_amount,"
                + "allocated_total_amount,basis_total,line_count,status,computed_at,cost_as_of,"
                + "locked_at,superseded_at,superseded_by_rule_version,note,created_at"
                + " from logistics_cost_allocation where tenant_id=? and id=?",
            tenant,
            id);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分摊单不存在");
    }
    List<CostAllocationLineRow> lines = new ArrayList<>();
    for (Record line :
        sql.fetch(
            "select id,line_no,sku_id,sku_code,sku_name,hs_code,basis_value,basis_unit,ratio,"
                + "allocated_amount,currency from logistics_cost_allocation_line"
                + " where tenant_id=? and allocation_id=? order by line_no",
            tenant,
            id)) {
      lines.add(
          new CostAllocationLineRow(
              line.get("id", UUID.class),
              line.get("line_no", Integer.class),
              line.get("sku_id", UUID.class),
              line.get("sku_code", String.class),
              line.get("sku_name", String.class),
              line.get("hs_code", String.class),
              line.get("basis_value", BigDecimal.class),
              line.get("basis_unit", String.class),
              line.get("ratio", BigDecimal.class),
              line.get("allocated_amount", BigDecimal.class),
              line.get("currency", String.class)));
    }
    return new CostAllocationDetail(allocationRow(r), List.copyOf(lines));
  }

  /**
   * 对一张头程发运单做分摊。
   *
   * <p>🔴 <b>分摊是拆分，不是缩水</b>：各行金额用「最大余数法」分配，
   * 保证<b>逐行合计严格等于来源总额</b>。四舍五入到 4 位小数后剩下的零头
   * 按小数部分从大到小逐个补上 —— 否则「每行四舍五入、合计差几分钱」会
   * 变成一笔永远对不上的账差。
   */
  public CostAllocationRow createAllocation(
      CostAllocationRequest request, UUID actor, String bearer) {
    UUID tenant = currentTenant();

    Record rule =
        sql.fetchOne(
            "select id,rule_code,version,allocation_basis,status from"
                + " logistics_cost_allocation_rule where tenant_id=? and id=?",
            tenant,
            request.ruleId());
    if (rule == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分摊规则不存在");
    }
    if ("DRAFT".equals(rule.get("status", String.class))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分摊规则还在草稿状态，不能用于分摊");
    }

    // 头程发运单来自 warehouse（跨服务只读）。读不到就是 404 / 503，**不空集合**。
    FirstMileDetailView detail = warehouse.firstMileDetail(request.sourceShipmentId(), bearer);
    var shipment = detail.shipment();
    List<FirstMileLineView> upstreamLines =
        detail.lines() == null ? List.of() : detail.lines();
    if (upstreamLines.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "这张头程发运单没有明细行，没有可分摊的对象");
    }

    String basis = rule.get("allocation_basis", String.class);
    BigDecimal sourceTotal = money(shipment.totalCost());
    if (sourceTotal == null || sourceTotal.signum() <= 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "这张头程发运单没有可分摊的成本（上游合计为 0 或缺失）");
    }
    String currency = shipment.currency();
    if (currency == null || currency.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上游头程发运单没有币种，无法分摊");
    }

    // ---- 逐行取分摊基准 ----
    List<Basis> bases = new ArrayList<>();
    BigDecimal basisTotal = BigDecimal.ZERO;
    for (FirstMileLineView line : upstreamLines) {
      BigDecimal value;
      String unit;
      switch (CostAllocationBasis.valueOf(basis)) {
        case BY_QUANTITY -> {
          value = money(line.shippedQuantity());
          unit = "PCS";
        }
        case BY_WEIGHT -> {
          value = money(line.grossWeightKg());
          unit = "KG";
        }
        case BY_DECLARED_VALUE -> {
          value = money(line.declaredValue());
          unit = "VALUE";
        }
        case EQUAL -> {
          value = BigDecimal.ONE;
          unit = "EQUAL";
        }
        default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知的分摊基准");
      }
      value = value == null ? BigDecimal.ZERO : value;
      bases.add(new Basis(line, value, unit));
      basisTotal = basisTotal.add(value);
    }
    if (basisTotal.signum() == 0) {
      // 🔴 分母为 0 ⇒ **拒绝分摊**，而不是把全部金额摊成 0 行。
      // 摊成 0 行等于「这批发运单的成本是 0」，那是凭空抹账，不是算不出来。
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "这张头程发运单在「" + basisLabel(basis) + "」基准下合计为 0，无法分摊 —— "
              + "请换一个有值的分摊基准，或先补齐上游的数量 / 重量 / 申报价值");
    }

    // ---- 最大余数法：逐行金额合计严格等于来源总额 ----
    BigDecimal[] amounts = allocateLargestRemainder(sourceTotal, bases, basisTotal);

    OffsetDateTime computedAt = OffsetDateTime.now(ZoneOffset.UTC);
    OffsetDateTime costAsOf = request.costAsOf();
    if (costAsOf.isAfter(computedAt)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "结账时点不能晚于计算时刻：成本不能记到还没到来的时刻上");
    }

    // 旧分摊单转 SUPERSEDED（留档，不改写），让同一批发运单只有一张现行分摊单。
    Record existing =
        sql.fetchOne(
            "select allocation_code,status from logistics_cost_allocation"
                + " where tenant_id=? and source_shipment_id=? and status <> 'SUPERSEDED'",
            tenant,
            request.sourceShipmentId());
    if (existing != null) {
      // 🔴 已结账的分摊单是**出账凭据**：重算并取代它等于改写历史利润。
      //   这里明确拒绝，而不是让 DB 触发器抛一个指不到人的 23514。
      if ("LOCKED".equals(existing.get("status", String.class))) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "这张头程发运单的分摊单 "
                + existing.get("allocation_code", String.class)
                + " 已经结账，是出账凭据，不能用新规则重算取代 —— "
                + "改口径要新建规则版本，历史分摊单保持指向它当时用的那一版");
      }
      sql.execute(
          "update logistics_cost_allocation set status='SUPERSEDED', superseded_at=?::timestamptz,"
              + " superseded_by=?, superseded_by_rule_version=?, updated_at=now(), version=version+1"
              + " where tenant_id=? and source_shipment_id=? and status <> 'SUPERSEDED'",
          computedAt,
          actor,
          rule.get("version", Integer.class),
          tenant,
          request.sourceShipmentId());
    }

    UUID id = Ids.next();
    // 🔴 values 里的占位符数量必须与下面的绑定**逐个对齐**（多一个就报
    // 🔴 values 里的占位符必须与绑定**逐个对齐**：多一个报「未设定参数值 N」，
    //   少一个报「INSERT has more target columns than expressions」。
    //   顺序：id / tenant_id / 规则快照 4 项 / 来源 5 项 / 金额 4 项 / 行数 1 项 /
    //   状态 1 项 / 两个时刻 / 记账 2 项。
    sql.execute(
        "insert into logistics_cost_allocation"
            + " (id,tenant_id,allocation_code,rule_id,rule_code,rule_version,allocation_basis,"
            + "  source_shipment_id,source_shipment_code,source_transport_mode,source_carrier_name,"
            + "  destination_country_code,currency,source_total_amount,allocated_total_amount,"
            + "  basis_total,line_count,status,computed_at,cost_as_of,computed_by,note,version,"
            + "  created_at,updated_at)"
            + " values (?,?,'ALC-' || lpad(nextval('logistics_cost_allocation_code_seq')::text,8,'0'),"
            + "  ?,?,?,?," // rule_id, rule_code, rule_version, allocation_basis
            + "  ?,?,?,?,?," // source_shipment_id, source_shipment_code, source_transport_mode,
            //                     source_carrier_name, destination_country_code
            + "  ?,?,?,?," // currency, source_total_amount, allocated_total_amount, basis_total
            + "  ?," // line_count
            + "  ?," // status
            + "  ?::timestamptz,?::timestamptz," // computed_at, cost_as_of
            + "  ?,?," // computed_by, note
            + "  0,now(),now())",
        id,
        tenant,
        rule.get("id", UUID.class),
        rule.get("rule_code", String.class),
        rule.get("version", Integer.class),
        basis,
        request.sourceShipmentId(),
        shipment.shipmentCode() == null ? "" : shipment.shipmentCode(),
        shipment.transportMode() == null ? "" : shipment.transportMode(),
        shipment.carrierName() == null ? "" : shipment.carrierName(),
        shipment.destinationCountryCode() == null ? "" : shipment.destinationCountryCode(),
        currency,
        sourceTotal,
        sourceTotal,
        basisTotal,
        bases.size(),
        "COMPUTED",
        computedAt,
        costAsOf,
        actor,
        request.note() == null ? "" : request.note().trim());

    // 逐行写入。ratio 由 **SQL** 现算，并显式 ::numeric（PG 的整数除法会静默把 2/3 变成 0）。
    // 🔴 占位符与绑定逐个对齐：前 10 个是行字段，第 11 个是 ratio 的分子，
    //   接着 3 个是金额 / 币种 / 写入时刻，最后 2 个是 where 里的租户与分摊单 id。
    int lineNo = 0;
    for (int i = 0; i < bases.size(); i++) {
      lineNo++;
      Basis b = bases.get(i);
      sql.execute(
          "insert into logistics_cost_allocation_line"
              + " (id,tenant_id,allocation_id,line_no,sku_id,sku_code,sku_name,hs_code,"
              + "  basis_value,basis_unit,ratio,allocated_amount,currency,allocated_at)"
              + " select ?,?,?,?,?,?,?,?,?,?," // 行字段：id ~ basis_unit
              + "   case when h.basis_total = 0 then null"
              + "        else round((?::numeric / nullif(h.basis_total::numeric,0)),"
              + "             " + RATIO_SCALE + ") end," // ratio：分子 / 分摊基准合计
              + "   ?,?,?::timestamptz" // allocated_amount, currency, allocated_at
              + " from logistics_cost_allocation h where h.tenant_id=? and h.id=?", // 租户与分摊单

          Ids.next(),
          tenant,
          id,
          lineNo,
          b.line().skuId(),
          b.line().skuCode() == null ? "" : b.line().skuCode(),
          b.line().skuName() == null ? "" : b.line().skuName(),
          b.line().hsCode() == null ? "" : b.line().hsCode(),
          b.value(),
          b.unit(),
          b.value(),
          amounts[i],
          currency,
          computedAt,
          tenant,
          id);
    }

    CostAllocationDetail created = allocation(id);
    return created.allocation();
  }

  /**
   * 结账：锁定这张分摊单。
   *
   * <p>锁定后**不可再改**（V213 触发器钉死）：金额、基准、规则、结账时点都动不了。
   * 这就是「成本按什么时点结的」的凭据 —— 出了账就不能回头改。
   */
  public CostAllocationRow lock(UUID id, CostAllocationLockRequest request, UUID actor) {
    UUID tenant = currentTenant();
    Record existing =
        sql.fetchOne(
            "select status,cost_as_of,computed_at from logistics_cost_allocation"
                + " where tenant_id=? and id=?",
            tenant,
            id);
    if (existing == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分摊单不存在");
    }
    String status = existing.get("status", String.class);
    if ("LOCKED".equals(status)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "这张分摊单已经结账了");
    }
    if ("SUPERSEDED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "这张分摊单已被新规则版本取代，是历史留档，不能结账");
    }
    OffsetDateTime computedAt = existing.get("computed_at", OffsetDateTime.class);
    OffsetDateTime costAsOf =
        request != null && request.costAsOf() != null
            ? request.costAsOf()
            : existing.get("cost_as_of", OffsetDateTime.class);
    if (costAsOf.isAfter(computedAt)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "结账时点不能晚于计算时刻：成本不能记到还没到来的时刻上");
    }
    sql.execute(
        "update logistics_cost_allocation set status='LOCKED', locked_at=?::timestamptz, locked_by=?,"
            + " cost_as_of=?::timestamptz, note=case when ?<>'' then ? else note end,"
            + " updated_at=now(), version=version+1"
            + " where tenant_id=? and id=?",
        OffsetDateTime.now(ZoneOffset.UTC),
        actor,
        costAsOf,
        request == null || request.note() == null ? "" : request.note().trim(),
        request == null || request.note() == null ? "" : request.note().trim(),
        tenant,
        id);
    return allocation(id).allocation();
  }

  // ==================================================================
  // 内部
  // ==================================================================

  /** 一行分摊基准。 */
  private record Basis(FirstMileLineView line, BigDecimal value, String unit) {}

  /** 一个币种的累加桶。 */
  private static final class CurrencyBucket {
    BigDecimal amount = BigDecimal.ZERO;
    BigDecimal weight = BigDecimal.ZERO;
    long count;
    long registered;
  }

  /**
   * 尾程运费的逐币种汇总：<b>已登记</b>与<b>未登记</b>分开记。
   *
   * @param registered 只收**登记了运费**的票
   * @param notRegistered 币种 → 没登记运费的票数（不产生任何金额）
   */
  private record LastMileBuckets(
      Map<String, CurrencyBucket> registered, Map<String, Long> notRegistered) {}

  /** 关税的两套口径：实缴（进合计）与估算（不进合计）。 */
  private record DutyBuckets(
      Map<String, CurrencyBucket> paid, Map<String, CurrencyBucket> estimated) {}

  /** 当前事务内的租户（由 backend/libraries/tenancy 在进入事务时绑定）。 */
  private UUID currentTenant() {
    Record row = sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')");
    String raw = row == null ? null : row.get(0, String.class);
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

  /**
   * 总页数：<b>整数除法</b>，不用 {@code Math.ceil((double) total / size)}。
   *
   * <p>浮点在这里不是「风格问题」：很大的 total 上 double 的除法会给出
   * 差一个页的答案，界面上就出现「第 3 页是空的」这种假空态。
   */
  private static int pageCount(long total, int size) {
    if (total <= 0) {
      return 0;
    }
    return (int) ((total + size - 1) / size);
  }

  /**
   * 显式刻度的除法：分母为 0 时返回 {@code null}（算不出来），不是 0。
   *
   * <p>🔴 绝不用浮点，也绝不做整数除法：PG 里的 {@code int/int} 会静默变成 0，
   * 而那个 0 看上去仍像百分比。
   */
  private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
    if (numerator == null || denominator == null || denominator.signum() == 0) {
      return null;
    }
    return numerator.divide(denominator, MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /** 金额规整：null 保持 null（**不把 null 变成 0** —— 那正是本模块最忌讳的事）。 */
  private static BigDecimal money(BigDecimal value) {
    return value == null ? null : value;
  }

  /**
   * 最大余数法分配：逐行金额合计**严格等于**来源总额。
   *
   * <p>做法：每行先向下取整到 4 位小数，把剩下的零头按小数部分从大到小
   * 逐个 0.0001 地补回去。少了这一步，「每行四舍五入」就会留下一笔
   * 永远对不上的账差 —— 而账差在成本报表上根本看不出来。
   */
  private static BigDecimal[] allocateLargestRemainder(
      BigDecimal total, List<Basis> bases, BigDecimal basisTotal) {
    int n = bases.size();
    BigDecimal[] amounts = new BigDecimal[n];
    BigDecimal[] floors = new BigDecimal[n];
    BigDecimal[] remainders = new BigDecimal[n];
    BigDecimal floorSum = BigDecimal.ZERO;
    for (int i = 0; i < n; i++) {
      BigDecimal raw =
          total.multiply(bases.get(i).value).divide(basisTotal, MONEY_SCALE + 6, RoundingMode.HALF_UP);
      BigDecimal floor = raw.setScale(MONEY_SCALE, RoundingMode.FLOOR);
      floors[i] = floor;
      remainders[i] = raw.subtract(floor);
      amounts[i] = floor;
      floorSum = floorSum.add(floor);
    }
    BigDecimal diff = total.subtract(floorSum);
    // 差值必然是 0.0001 的整数倍（总额与各行都是 4 位小数）。
    long units = diff.movePointRight(MONEY_SCALE).longValueExact();
    if (units > 0) {
      Integer[] order = new Integer[n];
      for (int i = 0; i < n; i++) {
        order[i] = i;
      }
      java.util.Arrays.sort(
          order,
          (a, b) -> {
            int cmp = remainders[b].compareTo(remainders[a]);
            return cmp != 0 ? cmp : Integer.compare(a, b);
          });
      BigDecimal step = new BigDecimal("0.0001");
      for (int k = 0; k < units && k < n; k++) {
        amounts[order[k]] = amounts[order[k]].add(step);
      }
    }
    return amounts;
  }

  /** 本窗各币种的运单票数（包材参数要按票数算发生额）。 */
  private Map<String, Long> waybillCountByCurrency(List<WaybillCostRow> rows) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (WaybillCostRow row : rows) {
      String currency = row.freightCurrency();
      if (currency == null || currency.isBlank()) {
        continue;
      }
      counts.merge(currency, 1L, Long::sum);
    }
    return counts;
  }

  /**
   * 尾程运费逐币种汇总。
   *
   * <p>🔴 <b>已登记与未登记分开记</b>：一票运费没登记，**不等于**这票运费是 0。
   * 所以 {@code registered} 只收有金额的票；没登记的票只进 {@code notRegistered} 的计数，
   * 不会凭空造出一个 0 元的币种行 —— 那个 0 行会被读成「这个渠道的运费是 0」。
   */
  private LastMileBuckets lastMileByCurrency(List<WaybillCostRow> rows) {
    Map<String, CurrencyBucket> registered = new LinkedHashMap<>();
    Map<String, Long> notRegistered = new LinkedHashMap<>();
    for (WaybillCostRow row : rows) {
      String currency = row.freightCurrency();
      if (row.freightAmount() == null) {
        // 未登记：有票数、没金额。币种未知时归到 UNKNOWN 计数里，不进金额。
        String key = currency == null || currency.isBlank() ? "UNKNOWN" : currency;
        notRegistered.merge(key, 1L, Long::sum);
        continue;
      }
      if (currency == null || currency.isBlank()) {
        // 有金额但没有币种：无法归到任何币种，也不能跨币种加。
        notRegistered.merge("UNKNOWN", 1L, Long::sum);
        continue;
      }
      CurrencyBucket bucket = registered.computeIfAbsent(currency, c -> new CurrencyBucket());
      bucket.count++;
      bucket.amount = bucket.amount.add(row.freightAmount());
      bucket.registered++;
      if (row.grossWeightKg() != null) {
        bucket.weight = bucket.weight.add(row.grossWeightKg());
      }
    }
    return new LastMileBuckets(registered, notRegistered);
  }

  /**
   * 头程分摊逐币种汇总（**本服务自持的事实**）。
   *
   * <p>按<b>结账时点（cost_as_of）</b>归入时间窗：分摊单算出来可能晚于成本所属的期间，
   * 报表要按「这笔成本算到哪一刻的头上」归集，而不是按「什么时候算的」归集。
   */
  private Map<String, CurrencyBucket> firstMileByCurrency(OffsetDateTime from, OffsetDateTime to) {
    Map<String, CurrencyBucket> buckets = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select currency, sum(allocated_total_amount) as amount, count(*) as cnt"
                + " from logistics_cost_allocation"
                + " where tenant_id=? and status <> 'SUPERSEDED'"
                + "   and cost_as_of >= ?::timestamptz and cost_as_of < ?::timestamptz"
                + " group by currency order by currency",
            currentTenant(),
            from,
            to)) {
      CurrencyBucket bucket = new CurrencyBucket();
      bucket.amount = r.get("amount", BigDecimal.class);
      bucket.count = r.get("cnt", Long.class);
      bucket.registered = bucket.count;
      buckets.put(r.get("currency", String.class), bucket);
    }
    return buckets;
  }

  /** 关税：实缴与估算两套口径。 */
  private DutyBuckets dutyBuckets(String bearer) {
    Map<String, CurrencyBucket> paid = new LinkedHashMap<>();
    Map<String, CurrencyBucket> estimated = new LinkedHashMap<>();
    int page = 0;
    int pageSize = 200;
    long total = Long.MAX_VALUE;
    while ((long) page * pageSize < total) {
      DutyEstimatePageView view = warehouse.dutyEstimatePage(page, pageSize, bearer);
      total = view.total();
      if (view.rows().isEmpty()) {
        break;
      }
      for (DutyEstimateView row : view.rows()) {
        String currency = row.estimateCurrency();
        if (currency == null || currency.isBlank()) {
          continue;
        }
        CurrencyBucket paidBucket = paid.computeIfAbsent(currency, c -> new CurrencyBucket());
        if (row.paidTotalAmount() != null && row.paidTotalAmount().signum() > 0) {
          paidBucket.amount = paidBucket.amount.add(row.paidTotalAmount());
          paidBucket.registered++;
        }
        CurrencyBucket estimatedBucket = estimated.computeIfAbsent(currency, c -> new CurrencyBucket());
        if (row.estimatedTotalTax() != null && row.estimatedTotalTax().signum() > 0) {
          estimatedBucket.amount = estimatedBucket.amount.add(row.estimatedTotalTax());
          estimatedBucket.registered++;
        }
      }
      page++;
    }
    return new DutyBuckets(paid, estimated);
  }

  /**
   * 把一个成本分量的取数结果组装进报表。
   *
   * <p>🔴 台账里缺这一位 ⇒ <b>整位不生成</b>：既不造一个 0，也不造一个「暂无数据」的
   * 空壳。少一位就是少一位 —— 界面上少显示一栏，比显示一栏没有来源的数字诚实。
   */
  private void addComponentAmount(
      List<CostComponentAmount> target,
      List<CostComponentCapability> ledger,
      CostComponent component,
      Map<String, CurrencyBucket> actual,
      Map<String, CurrencyBucket> estimated) {
    CostComponentCapability capability = capabilityOf(ledger, component);
    if (capability == null) {
      return;
    }
    List<CostCurrencyTotal> byCurrency = new ArrayList<>();
    for (Map.Entry<String, CurrencyBucket> entry : actual.entrySet()) {
      byCurrency.add(
          new CostCurrencyTotal(entry.getKey(), entry.getValue().amount, entry.getValue().registered));
    }
    List<CostCurrencyTotal> estimatedTotals = new ArrayList<>();
    for (Map.Entry<String, CurrencyBucket> entry : estimated.entrySet()) {
      estimatedTotals.add(
          new CostCurrencyTotal(entry.getKey(), entry.getValue().amount, entry.getValue().registered));
    }
    target.add(
        new CostComponentAmount(
            capability.component(),
            capability.displayName(),
            capability.available(),
            capability.sourceKind(),
            capability.source(),
            capability.basis(),
            capability.ownerModule(),
            capability.unavailableReason(),
            capability.impact(),
            byCurrency,
            estimatedTotals,
            byCurrency.size()));
  }

  /**
   * 占比：分母为 0 或分子缺失时返回 {@code null}（算不出来），不是 0。
   *
   * <p>🔴 绝不用浮点：成本占比用 {@code double} 会在报表上出现
   * 「33.33333333333333%」这种既不好看、又让「两个数相等」判不出来的值。
   */
  private static BigDecimal share(BigDecimal numerator, BigDecimal denominator) {
    if (numerator == null || denominator == null || denominator.signum() == 0) {
      return null;
    }
    return numerator.divide(denominator, RATIO_SCALE, RoundingMode.HALF_UP);
  }

  /** 从桶里取金额；**没有这一币种时返回 null 而不是 0**。 */
  private static BigDecimal amountOf(Map<String, CurrencyBucket> buckets, String currency) {
    CurrencyBucket bucket = buckets.get(currency);
    return bucket == null ? null : bucket.amount;
  }

  private CostAllocationRow allocationRow(Record r) {
    String basis = r.get("allocation_basis", String.class);
    String status = r.get("status", String.class);
    return new CostAllocationRow(
        r.get("id", UUID.class),
        r.get("allocation_code", String.class),
        r.get("rule_id", UUID.class),
        r.get("rule_code", String.class),
        r.get("rule_version", Integer.class),
        basis,
        basisLabel(basis),
        r.get("source_shipment_id", UUID.class),
        r.get("source_shipment_code", String.class),
        r.get("source_transport_mode", String.class),
        r.get("source_carrier_name", String.class),
        r.get("destination_country_code", String.class),
        r.get("currency", String.class),
        r.get("source_total_amount", BigDecimal.class),
        r.get("allocated_total_amount", BigDecimal.class),
        r.get("basis_total", BigDecimal.class),
        r.get("line_count", Integer.class),
        status,
        statusLabel(status),
        r.get("computed_at", OffsetDateTime.class),
        r.get("cost_as_of", OffsetDateTime.class),
        r.get("locked_at", OffsetDateTime.class),
        r.get("superseded_at", OffsetDateTime.class),
        r.get("superseded_by_rule_version", Integer.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static String basisLabel(String basis) {
    if (basis == null) {
      return "";
    }
    try {
      return CostAllocationBasis.valueOf(basis).displayName;
    } catch (IllegalArgumentException e) {
      return basis;
    }
  }

  private static String statusLabel(String status) {
    if (status == null) {
      return "";
    }
    try {
      return CostAllocationStatus.valueOf(status).displayName;
    } catch (IllegalArgumentException e) {
      return status;
    }
  }
}
