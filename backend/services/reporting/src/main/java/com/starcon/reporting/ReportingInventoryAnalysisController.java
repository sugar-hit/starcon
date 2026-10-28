package com.starcon.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 13.5.1.1 库存分析的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /reporting/inventory-analysis}</b>：卷 13 的兄弟模块
 * （13.4.2.1 成本结构 / 13.4.1.2 SKU 利润 …）各自带自己的前缀，
 * 挤在同一个前缀下会让「哪个模块的哪个端点」变得说不清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码有三个：{@code reporting.inventory-analysis.read}（本模块**只读**）、
 * {@code reporting.inventory-analysis.write}（**只改本模块自己的阈值规则表**，不写任何上游口径）、
 * {@code reporting.projection.write}（**投影摄取**：把上游事实拉成本服务的投影）。
 */
@RestController
@RequestMapping("/reporting/inventory-analysis")
@Tag(name = "13.5.1.1 库存分析")
public class ReportingInventoryAnalysisController {

  private final AccessService access;
  private final ReportingInventoryAnalysisService inventoryAnalysis;
  private final ReportingInventoryAnalysisRefreshService refresh;

  public ReportingInventoryAnalysisController(
      AccessService access,
      ReportingInventoryAnalysisService inventoryAnalysis,
      ReportingInventoryAnalysisRefreshService refresh) {
    this.access = access;
    this.inventoryAnalysis = inventoryAnalysis;
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
      summary = "能力声明：六件指标分别来自哪个上游、哪些确实拿不到",
      description =
          "notDelivered 列出的是**确实拆不出来**的部分（按仓库拆解的周转率、退货冲减等），"
              + "每条都写清原因与影响 —— 「算不出来」与「数值是 0」在页面上长得一样，含义却相反。")
  public ReportingInventoryAnalysisModels.InventoryAnalysisCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "reporting.inventory-analysis.read", actor -> inventoryAnalysis.capabilities());
  }

  // ================================================================ 库存金额结构

  @GetMapping("/structure")
  @Operation(
      summary = "库存金额结构（成本口径，按仓库 / 类目 / 店铺 / SKU 拆解）",
      description =
          "库存金额 = 在手数量 × **catalog 成本构成里的单位成本**（成本口径）。"
              + "**多币种绝不相加** —— 每一行带自己的币种；跨币种的「总计」只有单币种时才存在。"
              + "没有维护成本的 SKU 金额是「—」，**不在合计里**（不是 0 元）。")
  public ReportingInventoryAnalysisModels.InventoryStructureResponse structure(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
    return call(
        auth,
        "reporting.inventory-analysis.read",
        actor ->
            inventoryAnalysis.structure(
                actor.tenantId(), resolveDimension(dimension), resolveCurrency(currency), asOf));
  }

  // ================================================================ 周转率

  @GetMapping("/turnover")
  @Operation(
      summary = "周转率（**成本口径**）+ 趋势",
      description =
          "🔴 周转率 = **销售出库成本** ÷ **平均库存金额**，**没有用售价** —— 用售价算会虚高"
              + "（蓝图加粗句）。**先汇总再相除**：分子分母都是先加总再相除，"
              + "不是对逐 SKU 的周转率求平均。分母为 0 ⇒ 周转率**不可比**（不是 0）。"
              + "按仓库拆解这一格不可比（销售订单不带仓库维度），如实报「—」而不是摊给某个仓库。")
  public ReportingInventoryAnalysisModels.InventoryTurnoverResponse turnover(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String granularity,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) String currency) {
    return call(
        auth,
        "reporting.inventory-analysis.read",
        actor -> {
          var window = resolveWindow(from, to);
          return inventoryAnalysis.turnover(
              actor.tenantId(),
              window.from(),
              window.to(),
              resolveGranularity(granularity),
              resolveDimension(dimension),
              resolveCurrency(currency));
        });
  }

  // ================================================================ 库龄与呆滞

  @GetMapping("/aging")
  @Operation(
      summary = "库龄分布 + 呆滞占比",
      description =
          "库龄与呆滞的**判定归 warehouse 7.6.1.1**（含滞销线 / 呆滞线阈值策略），本模块只搬运。"
              + "两者都是**量口径**（warehouse 的库存事实层没有成本列）。"
              + "呆滞占比的分母为 0 ⇒ **不可比**（不是 0%）。")
  public ReportingInventoryAnalysisModels.InventoryAgingResponse aging(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
      @RequestParam(required = false) UUID warehouseId) {
    return call(
        auth,
        "reporting.inventory-analysis.read",
        actor -> inventoryAnalysis.aging(actor.tenantId(), asOf, warehouseId));
  }

  // ================================================================ 缺货与超储

  @GetMapping("/stock-status")
  @Operation(
      summary = "缺货与超储（按维度拆解）",
      description =
          "缺货 = 可用量 ≤ 0；超储 = 在手量超过**已配置**的阈值。"
              + "**未设阈值**的行单独计数 —— 「没查过」与「查过没超」是两件事，混在一起会让人以为超储为零。")
  public ReportingInventoryAnalysisModels.InventoryStockStatusResponse stockStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
    return call(
        auth,
        "reporting.inventory-analysis.read",
        actor ->
            inventoryAnalysis.stockStatus(actor.tenantId(), resolveDimension(dimension), asOf));
  }

  // ================================================================ ABC 分类

  @GetMapping("/abc")
  @Operation(
      summary = "ABC 分类（按**库存金额**的累计占比切分）",
      description =
          "库存分析要回答的是「哪些 SKU 占了大部分资金」⇒ 切分依据是**金额**，不是销量、也不是 SKU 数。"
              + "阈值可按租户改（PUT /reporting/inventory-analysis/rules/ABC_CLASS）。"
              + "**没有维护成本的 SKU 不进 ABC**（它们的金额算不出来，不是 0 元）。")
  public ReportingInventoryAnalysisModels.InventoryAbcResponse abc(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "reporting.inventory-analysis.read",
        actor ->
            inventoryAnalysis.abc(
                actor.tenantId(), resolveCurrency(currency), asOf, Math.max(page, 0), Math.max(size, 1)));
  }

  // ================================================================ 下钻

  @GetMapping("/drilldown")
  @Operation(
      summary = "SKU 级下钻（真分页 + 真计数）",
      description =
          "每一行给出数量、**成本口径**的单价与库存金额、窗口内的销量与销售出库成本、缺货 / 超储标记；"
              + "都能回到上游逐条核对。")
  public ReportingInventoryAnalysisModels.InventoryDrilldownResponse drilldown(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String skuCode,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "reporting.inventory-analysis.read",
        actor -> {
          var window = resolveWindow(from, to);
          return inventoryAnalysis.drilldown(
              actor.tenantId(),
              skuCode,
              window.from(),
              window.to(),
              asOf,
              Math.max(page, 0),
              Math.max(size, 1));
        });
  }

  // ================================================================ 盘点历史

  @GetMapping("/stocktakes")
  @Operation(
      summary = "盘点历史（准确率）",
      description =
          "账面 / 已盘 / 差异三列计数都来自 warehouse 7.4.2.1。"
              + "**账面合计不等于当前库存余额** —— 它是盘点**冻结那一刻**的账面数。"
              + "账面行数为 0 ⇒ 准确率**不可比**（不是 0%）。")
  public ReportingInventoryAnalysisModels.InventoryStocktakeResponse stocktakes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "reporting.inventory-analysis.read",
        actor -> inventoryAnalysis.stocktakes(actor.tenantId(), Math.max(page, 0), Math.max(size, 1)));
  }

  // ================================================================ 阈值规则

  @GetMapping("/rules")
  @Operation(
      summary = "阈值规则（可配置项）",
      description =
          "ABC 的累计占比线与周转目标都落在**租户自己的规则表**里（没有配置时给默认值）；"
              + "不同品类、不同店铺的库存结构差一个数量级，写死的阈值对谁都不对。")
  public List<ReportingInventoryAnalysisModels.InventoryAnalysisRule> rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth, "reporting.inventory-analysis.read", actor -> inventoryAnalysis.rules(actor.tenantId()));
  }

  @PutMapping("/rules/{ruleType}")
  @Operation(
      summary = "改阈值",
      description =
          "只改写进来的那几个字段；本模块**只改自己的规则表**，"
              + "不写任何上游口径（成本单价在 catalog、库龄阈值在 warehouse）。")
  public ReportingInventoryAnalysisModels.InventoryAnalysisRule updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String ruleType,
      @RequestBody ReportingInventoryAnalysisModels.InventoryAnalysisRuleInput input) {
    return call(
        auth,
        "reporting.inventory-analysis.write",
        actor -> inventoryAnalysis.updateRule(actor.tenantId(), resolveRuleType(ruleType), input));
  }

  // ================================================================ 投影摄取

  @PostMapping("/refresh")
  @Operation(
      summary = "刷新库存分析投影（从上游拉取 → 投影）",
      description =
          "拉四路上游（warehouse 库存 / warehouse 库龄 / warehouse 盘点 / trade 销售订单）"
              + "并落成本服务的投影（幂等：同一批上游事实重放不会翻倍）。"
              + "权限是 reporting.projection.write —— **本模块没有任何「创建业务事实」的权限**。")
  public ReportingInventoryAnalysisModels.InventoryAnalysisRefreshResult refresh(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    // ⚠️ 这里**不走 call(...)**：拉取上游需要调用方自己的 bearer（跨服务只读端口转发它），
    // 而 Access 里不带 bearer，所以 bearer 从请求头直接取。
    var actor = access.require(auth, "reporting.projection.write");
    return access.run(
        actor,
        "reporting.projection.write",
        () -> {
          var window = resolveWindow(from, to);
          return refresh.refresh(actor.tenantId(), auth, window.from(), window.to());
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

  /** 期间粒度。 */
  private String resolveGranularity(String raw) {
    if (raw == null || raw.isBlank()) return "DAY";
    var value = raw.trim().toUpperCase();
    if (!List.of("DAY", "WEEK", "MONTH").contains(value)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "granularity 只能是 DAY / WEEK / MONTH，收到：" + raw);
    }
    return value;
  }

  /** 拆解维度。 */
  private String resolveDimension(String raw) {
    if (raw == null || raw.isBlank()) return "ALL";
    var value = raw.trim().toUpperCase();
    if (!List.of("ALL", "WAREHOUSE", "CATEGORY", "SHOP", "SKU").contains(value)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "dimension 只能是 ALL / WAREHOUSE / CATEGORY / SHOP / SKU，收到：" + raw);
    }
    return value;
  }

  /** 币种过滤（空 ⇒ 全部；**绝不跨币种相加**）。 */
  private String resolveCurrency(String raw) {
    if (raw == null || raw.isBlank()) return null;
    var value = raw.trim().toUpperCase();
    if (!value.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "currency 必须是 ISO alpha-3 币种码，收到：" + raw);
    }
    return value;
  }

  /** 规则类型。 */
  private String resolveRuleType(String raw) {
    var value = raw == null ? "" : raw.trim().toUpperCase();
    if (!List.of("ABC_CLASS", "TURNOVER_TARGET").contains(value)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "ruleType 只能是 ABC_CLASS / TURNOVER_TARGET，收到：" + raw);
    }
    return value;
  }
}
