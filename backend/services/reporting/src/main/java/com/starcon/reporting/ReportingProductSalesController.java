package com.starcon.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 13.2.2.2 SKU/SPU/Listing 的 HTTP 端点。
 *
 * <p>命名空间是 {@code /reporting/product-sales}：13.1.1.1 用的是 {@code /reporting/overview}、
 * 13.1.1.2 用的是 {@code /reporting/store-comparison}，两个兄弟模块各有各的前缀，
 * 挤在同一个前缀下会让「哪个模块的哪个端点」变得说不清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码：{@code reporting.product-sales.read}（读商品销量报表）与
 * {@code reporting.projection.write}（刷新投影 —— 写的是**投影**，不是业务事实）。
 * 本模块**没有任何「创建业务事实」的权限码**（reporting 是投影服务）。
 */
@RestController
@RequestMapping("/reporting/product-sales")
@Tag(name = "13.2.2.2 SKU/SPU/Listing")
public class ReportingProductSalesController {

  private final AccessService access;
  private final ReportingProductSalesService sales;
  private final ReportingProductSalesRefreshService refresh;

  public ReportingProductSalesController(
      AccessService access,
      ReportingProductSalesService sales,
      ReportingProductSalesRefreshService refresh) {
    this.access = access;
    this.sales = sales;
    this.refresh = refresh;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  @GetMapping("/capabilities")
  @Operation(
      summary = "能力声明：哪些上游真接通、哪些口径在商品维度拿不到、判定规则是什么",
      description =
          "notDelivered 列出的是**确实拿不到**的部分，每条都写清原因与影响 —— "
              + "把「拿不到」伪装成 0 比说清楚更糟。")
  public ReportingProductSalesModels.ProductSalesCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "reporting.product-sales.read",
        actor ->
            new ReportingProductSalesModels.ProductSalesCapabilities(
                true, true, true, UPSTREAM_MODULES, NOT_DELIVERED, RULES));
  }

  // ================================================================ 指标口径目录

  @GetMapping("/metrics")
  @Operation(summary = "商品维度指标口径目录（口径唯一的展示形态）")
  public List<ReportingProductSalesModels.ProductMetricDefinitionView> metrics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "reporting.product-sales.read",
        actor -> sales.metricDefinitions(actor.tenantId()));
  }

  // ================================================================ 商品销量报表

  @GetMapping
  @Operation(
      summary = "商品销量报表：SKU / SPU / Listing 三层 + 层级钻取",
      description =
          "时间范围默认「最近 30 天」。维度层级由 level 决定（SKU / SPU / LISTING），"
              + "「是否合并 SPU」由 mergeSpu 决定（true ⇒ 按 SPU 合并看）。"
              + "⚠️ **绝不跨币种相加**：成交金额按币种分别列出；只有件数与单数给跨币种合计。"
              + "⚠️ **Listing 层的销量只归「可唯一归属」的那部分**：同一款内部 SKU 挂在多个 Listing 上时，"
              + "它的销量归不了任何 Listing，单列在「无法唯一归属」里。")
  public ReportingProductSalesModels.ProductSalesResponse report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false, defaultValue = "SKU") String level,
      @RequestParam(required = false, defaultValue = "false") boolean mergeSpu,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String spuKey,
      @RequestParam(required = false) String listingKey,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "reporting.product-sales.read",
        actor -> {
          var window = resolveWindow(from, to);
          var effectiveLevel = mergeSpu ? "SPU" : normalizeLevel(level);
          return sales.report(
              actor.tenantId(),
              window.from(),
              window.to(),
              effectiveLevel,
              mergeSpu,
              blankToNull(currency),
              blankToNull(spuKey),
              blankToNull(listingKey),
              page,
              size);
        });
  }

  // ================================================================ SPU 汇总

  @GetMapping("/spu-summary")
  @Operation(
      summary = "SPU 汇总：多平台多 SKU 合并看",
      description =
          "🔴 **SPU 口径是跨平台经营的基本单位**（蓝图关系句）：一行 SPU = 名下所有平台、"
              + "所有 SKU 的销量之和，不按平台拆 —— 只按平台 SKU 看会误判商品整体表现。"
              + "金额按币种分别列出，绝不跨币种相加。")
  public ReportingProductSalesModels.SpuSummaryResponse spuSummary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "reporting.product-sales.read",
        actor -> {
          var window = resolveWindow(from, to);
          return sales.spuSummary(actor.tenantId(), window.from(), window.to(), page, size);
        });
  }

  // ================================================================ 变体销量分布

  @GetMapping("/variants")
  @Operation(
      summary = "变体销量分布：某个 SPU 名下各变体的销量与占比",
      description =
          "占比 = 该变体销量 ÷ 所属 SPU 的销量（口径写在商品维度口径表里，服务端按定义求值）。"
              + "分母为 0 时算不出来（不是 0%）。金额按币种分别列出，绝不跨币种相加。")
  public ReportingProductSalesModels.VariantDistributionResponse variants(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam String spuKey) {
    return call(
        auth,
        "reporting.product-sales.read",
        actor -> {
          var window = resolveWindow(from, to);
          return sales.variants(actor.tenantId(), window.from(), window.to(), spuKey);
        });
  }

  // ================================================================ 层级钻取

  @GetMapping("/drilldown")
  @Operation(
      summary = "层级钻取：某个维度、某个指标的构成明细（哪个订单、哪个 SKU）",
      description =
          "卷 13 卷首加粗句「可下钻」的落点：每个汇总数字都能钻到构成明细。"
              + "构成明细记着**哪张订单的哪一行**贡献了这笔数字（订单号 + 行序号）。")
  public ReportingProductSalesModels.ProductDrilldownResponse drilldown(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false, defaultValue = "SKU") String level,
      @RequestParam String dimensionKey,
      @RequestParam(required = false) String metricCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "reporting.product-sales.read",
        actor -> {
          var window = resolveWindow(from, to);
          return sales.drilldown(
              actor.tenantId(),
              window.from(),
              window.to(),
              normalizeLevel(level),
              dimensionKey,
              blankToNull(metricCode),
              page,
              size);
        });
  }

  // ================================================================ 刷新（投影摄取）

  @PostMapping("/refresh")
  @Operation(
      summary = "从上游拉取商品维度事实并投影（写的是投影，不是业务事实）",
      description =
          "上游：catalog 的 SKU 列表（SKU → SPU 归属）、channel 的 Listing 列表"
              + "（平台商品 ↔ 内部 SKU 绑定）、trade 的销售订单与订单明细（销量与金额）。"
              + "⚠️ **订单明细逐单取**（销量与 SKU 下钻只存在于明细里），所以有张数上限，"
              + "触到上限时如实报「已截断」，不悄悄少算。")
  public List<ReportingProductSalesModels.ProductRefreshResult> refresh(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false, defaultValue = "UTC") String timezone) {
    ZoneId zone;
    try {
      zone = ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone);
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "时区无法解析：" + timezone);
    }
    return call(
        auth,
        "reporting.projection.write",
        actor -> {
          var window = resolveWindow(from, to);
          var days = ReportingProductSalesRefreshService.windowDays(window.from(), window.to());
          if (days > ReportingProductSalesRefreshService.MAX_WINDOW_DAYS) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "商品维度窗口最多 "
                    + ReportingProductSalesRefreshService.MAX_WINDOW_DAYS
                    + " 天：订单明细要逐单取，窗口太长会把上游压垮。");
          }
          return refresh.refresh(actor.tenantId(), auth, window.from(), window.to(), zone);
        });
  }

  // ================================================================ 参数解析

  /** 时间窗口。默认「最近 30 天」（含今天）。 */
  private record Window(LocalDate from, LocalDate to) {}

  private Window resolveWindow(LocalDate from, LocalDate to) {
    var end = to == null ? LocalDate.now() : to;
    var start = from == null ? end.minusDays(29) : from;
    if (start.isAfter(end)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "起始日期不能晚于结束日期（from=" + start + "、to=" + end + "）");
    }
    return new Window(start, end);
  }

  /** 维度层（SKU / SPU / LISTING）。非法取值 ⇒ 400（不静默回落成 SKU）。 */
  private String normalizeLevel(String level) {
    if (level == null || level.isBlank()) return "SKU";
    var normalized = level.trim().toUpperCase();
    if (!normalized.equals("SKU") && !normalized.equals("SPU") && !normalized.equals("LISTING")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "维度层只能是 SKU / SPU / LISTING（收到的是「" + level + "」）");
    }
    return normalized;
  }

  /** 空串 ⇒ null（不把空串当成一个真的筛选值）。 */
  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  // ==========================================================================
  // 能力声明的静态事实（**逐条对着上游的授权只读 API 核过**）
  // ==========================================================================

  /**
   * 本服务**真的读过**的上游模块。
   *
   * <p>⚠️ **不列没读过的**：能力声明是给前端判断「哪些块能画」的依据，
   * 列一个其实没接通的上游比不列更糟 —— 界面会以为功能已交付。
   */
  private static final List<String> UPSTREAM_MODULES =
      List.of(
          "3.1.2.1 SKU 列表（catalog /catalog/skus：SKU → SPU 归属、SPU 编码与名称、变体签名）",
          "4.1.1.1 Listing 列表（channel /channel/listings：平台商品 ↔ 内部 SKU 绑定、平台与店铺归属、映射状态）",
          "9.1.1.1 销售订单列表（trade /trade/sales-orders/list：订单号、币种、下单时刻）",
          "9.1.1.1 销售订单明细（trade /trade/sales-orders/detail/{id}：**销量与金额的唯一来源**）");

  /**
   * **确实拿不到**的部分（每条都写清原因，不写「未接入」这种空话）。
   */
  private static final List<String> NOT_DELIVERED =
      List.of(
          "🔴 **Listing 层的销量归属有边界**：trade 的订单明细**只带 SKU 码，不带平台商品 id**，"
              + "所以同一款内部 SKU 挂在多个 Listing 上时，它的销量**无法唯一归属**到某个 Listing。"
              + "本模块**不摊给任何 Listing**（摊给谁都是造假），这部分销量单列在「无法唯一归属」里。"
              + "一个 SKU 只挂在一个 Listing 上时，它的销量才唯一归属到那个 Listing。",
          "🔴 **订单行没有平台 / 店铺维度**：trade 的订单明细只有 SKU 码，没有平台、店铺与平台商品 id。"
              + "⇒ SPU 层把各平台的销量**合并**（这正是蓝图要的「SPU 是跨平台经营的基本单位」），"
              + "但**在 Listing 层无法按订单区分平台** —— 同款商品在两个平台上的销量在 SPU 层是合在一起的。",
          "**Listing 的价格 / 库存 / 评分维度不可得**：那属于 4.5.1.1 Listing 表现（另一个模块），"
              + "本模块只做**销量**拆解，不掺别的口径。",
          "**SPU 自身的平台维度不可得**：catalog 只给 SKU → SPU 的归属，SPU 没有平台字段。"
              + "SPU 覆盖哪些平台是**间接**从 Listing 绑定表推出来的，且受上面的归属边界限制。",
          "**手工销售订单与平台单是两套口径**：本模块用的是**统一销售订单**（9.1.1.1 订单列表）的明细，"
              + "不是平台单口径（9.1.1.1 平台单列表）—— 平台单的「平台侧金额 vs 还原金额」口径差异"
              + "在 13.1.1.2 多店对比里，不在这里重算。",
          "**退货量不在本模块**：退货是 13.4 利润分析（13.4.2.2 费用差异）的事；"
              + "本模块的「销量」是**成交**口径，不是净销量。",
          "**变体的进一步细分要看 3 商品的属性定义**：本模块按 catalog 给的 variant_signature 分组，"
              + "同一签名内的更细规格（颜色 / 尺码）不再拆 —— 那需要读 attribute_definition，"
              + "当前上游没有面向本模块的只读端点。",
          "**订单明细的张数有上限**：销量与 SKU 下钻只存在于订单明细里，必须逐单取，"
              + "所以一次刷新最多取 50 张单的明细（触到上限如实报「已截断」，不悄悄少算）。");

  /** 判定规则与口径说明（阈值写清楚，页面上照着显示）。 */
  private static final List<ReportingProductSalesModels.ProductRuleNote> RULES =
      List.of(
          new ReportingProductSalesModels.ProductRuleNote(
              "SPU_MERGE",
              "SPU 汇总：一行 SPU 的销量 = 它名下所有 SKU 的销量之和，**不按平台拆**。"
                  + "这是「SPU 口径是跨平台经营的基本单位」的落点 —— 只按平台 SKU 看会误判商品整体表现。"),
          new ReportingProductSalesModels.ProductRuleNote(
              "CURRENCY",
              "🔴 **绝不跨币种相加**：成交金额按币种分别列出。**只有件数与单数**（与货币无关）"
                  + "给出一个跨币种合计，它的币种显示为空串 —— 空串 = 与币种无关，不是「币种未知」。"),
          new ReportingProductSalesModels.ProductRuleNote(
              "VARIANT_SHARE",
              "变体销量占比 = 该变体（SKU）的销量 ÷ **它所属 SPU** 的销量。"
                  + "分母（SPU 销量）为 0 或没有事实时算不出来（**不是 0%**）。"
                  + "该指标只在 SKU 层出现 —— 在 SPU 层它没有意义（SPU 销量 ÷ SPU 销量 = 100%）。"),
          new ReportingProductSalesModels.ProductRuleNote(
              "LISTING_ATTRIBUTION",
              "Listing 层的销量只包含**可唯一归属**的部分：一个 SKU 只挂在一个 Listing 上 ⇒ "
                  + "它的销量归到那个 Listing；挂在多个（或零个）⇒ 归不了，单列「无法唯一归属」。"),
          new ReportingProductSalesModels.ProductRuleNote(
              "ORDER_COUNT",
              "成交订单数**按订单去重**：一张订单里同一款 SKU 出现两行也只算一单 —— "
                  + "数行会把「一单两行」读成「两单」。"),
          new ReportingProductSalesModels.ProductRuleNote(
              "DRILLDOWN",
              "可下钻：每个汇总数字都能钻到构成明细（哪张订单的哪一行、哪个 SKU）。"
                  + "构成明细用「订单号 + 行序号」做幂等键 —— 同一张订单里同一款 SKU 出现两行时，"
                  + "两行分别列出，不会被合并成一个数。"),
          new ReportingProductSalesModels.ProductRuleNote(
              "DATA_LATENCY",
              "数据延迟：报表是投影，不是实时的。页面上常驻显示**数据更新时间**"
                  + "（最近一次投影落库时刻）；空表时是 null（不是一个长得像时间的假值）。"
                  + "报表数字与业务系统冲突时，**以业务系统为准**。"));
}
