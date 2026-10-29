package com.starcon.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
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
 * 13.3.1.2 生命周期与分层的 HTTP 端点。
 *
 * <p>命名空间是 {@code /reporting/lifecycle-segmentation}：兄弟模块各有各的前缀
 * （13.2.2.2 是 {@code /reporting/product-sales}、13.3.1.1 是
 * {@code /reporting/product-performance}、13.3.2.1 是 {@code /reporting/traffic-conversion}），
 * 挤在同一个前缀下会让「哪个模块的哪个端点」变得说不清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码：{@code reporting.lifecycle-segmentation.read}（读分层结果与规则）与
 * {@code reporting.projection.write}（刷新分层结果 / 改分层规则 —— 写的是**投影读模型与它的
 * 配置**，不是业务事实）。本模块**没有任何「创建业务事实」的权限码**（reporting 是投影服务）。
 */
@RestController
@RequestMapping("/reporting/lifecycle-segmentation")
@Tag(name = "13.3.1.2 生命周期与分层")
public class ReportingLifecycleSegmentationController {

  private final AccessService access;
  private final ReportingLifecycleSegmentationService segmentation;

  public ReportingLifecycleSegmentationController(
      AccessService access, ReportingLifecycleSegmentationService segmentation) {
    this.access = access;
    this.segmentation = segmentation;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  @GetMapping("/capabilities")
  @Operation(
      summary = "能力声明：哪些上游真接通、哪些档位确实算不出来、判定规则是什么",
      description =
          "notDelivered 列出的是**确实拿不到**的部分，每条都写清原因与归属模块 —— "
              + "把「拿不到」伪装成 0 比说清楚更糟。")
  public ReportingLifecycleSegmentationModels.LifecycleSegmentationCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "reporting.lifecycle-segmentation.read",
        actor ->
            new ReportingLifecycleSegmentationModels.LifecycleSegmentationCapabilities(
                true, true, true, true, UPSTREAM_MODULES, NOT_DELIVERED, RULES));
  }

  // ================================================================ 分层结果

  @GetMapping
  @Operation(
      summary = "生命周期与分层：六档分层 + ABC 分类（销量 / 金额）+ 各层建议动作",
      description =
          "🔴 **一次性拉齐**：一次调用返回分档汇总 + 商品明细 + 生效规则 + 被引用的口径。"
              + "⚠️ **绝不跨币种相加**：金额按币种分别列出；ABC 按金额**在同一个币种内部**排名。"
              + "⚠️ **「算不出来」与「等于 0」分开**：档位算不出来时档位是 null 并附原因，"
              + "**不用 0 或某个默认档位冒充**。"
              + "⚠️ **窗口外的事实不参与计算**：销量 / 销售额 / 毛利 / 份额只取请求的窗口。")
  public ReportingLifecycleSegmentationModels.LifecycleSegmentationResponse segmentation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false, defaultValue = "SKU") String dimensionType,
      @RequestParam(required = false) String segment,
      @RequestParam(required = false) String abcByQuantity,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "reporting.lifecycle-segmentation.read",
        actor -> {
          var window = resolveWindow(from, to);
          return segmentation.segmentation(
              actor.tenantId(),
              window.from(),
              window.to(),
              normalizeLevel(dimensionType),
              blankToNull(segment),
              blankToNull(abcByQuantity),
              page,
              size);
        });
  }

  // ================================================================ 分层规则（可配置、可审计）

  @GetMapping("/rules")
  @Operation(
      summary = "分层规则配置（当前生效 + 历史版本）",
      description =
          "🔴 **规则在库里，不在代码里**：阈值 / 分类口径 / 版本 / 生效区间都可查、可审计。"
              + "历史分层结果仍指向它当时用的那个版本。")
  public List<ReportingLifecycleSegmentationModels.LifecycleSegmentationRuleView> rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "SKU") String dimensionType) {
    return call(
        auth,
        "reporting.lifecycle-segmentation.read",
        actor -> segmentation.rules(actor.tenantId(), normalizeLevel(dimensionType)));
  }

  @PutMapping("/rules/{dimensionType}")
  @Operation(
      summary = "更新分层规则（**改规则 = 加一个版本**，旧版本停用、历史结果不变）",
      description =
          "🔴 **口径码不可改**：销量 / 销售额 / 毛利引用的是 13.2.2.2 与 13.3.1.1 的口径表，"
              + "改它等于换了一个真相 —— 只让服务端固定。"
              + "⚠️ 规则改完要**重跑一次分层**（POST /refresh）才会生效；"
              + "在重跑之前，页面上显示的仍是旧版本规则算出来的结果。")
  public ReportingLifecycleSegmentationModels.LifecycleSegmentationRuleView updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String dimensionType,
      @RequestBody ReportingLifecycleSegmentationModels.LifecycleSegmentationRuleInput input) {
    var level = normalizeLevel(dimensionType);
    return call(
        auth,
        "reporting.projection.write",
        actor -> segmentation.updateRule(actor.tenantId(), level, input, actor.userId().toString()));
  }

  // ================================================================ 刷新（算一遍并落库）

  @PostMapping("/refresh")
  @Operation(
      summary = "按规则算一遍分层并落库（写的是**派生读模型**，不是业务事实）",
      description =
          "🔴 **上游是服务内已落成的投影**：销量 / 销售额来自 13.2.2.2 的日事实，"
              + "毛利来自 13.3.1.1 的日事实（派生口径：销售额 − 出库成本），"
              + "库存与库龄来自 13.3.1.1 的时点快照。"
              + "🔴 **幂等**：同一窗口重刷先删旧行再整段重写。"
              + "🔴 **上游口径没投影 ⇒ 报「口径未投影」**，不返回 0。")
  public List<ReportingLifecycleSegmentationModels.LifecycleSegmentationRefreshResult> refresh(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false, defaultValue = "SKU") String dimensionType) {
    return call(
        auth,
        "reporting.projection.write",
        actor -> {
          var window = resolveWindow(from, to);
          return segmentation.refresh(
              actor.tenantId(), window.from(), window.to(), normalizeLevel(dimensionType));
        });
  }

  // ================================================================ 参数解析

  /** 时间窗口（一对日期）。 */
  private record Window(LocalDate from, LocalDate to) {}

  /** 时间窗口。默认「最近 30 天」（含今天）。 */
  private Window resolveWindow(LocalDate from, LocalDate to) {
    var end = to == null ? LocalDate.now() : to;
    var start = from == null ? end.minusDays(29) : from;
    if (start.isAfter(end)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "起始日期不能晚于结束日期（from=" + start + "、to=" + end + "）");
    }
    var days = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
    if (days > ReportingLifecycleSegmentationService.MAX_WINDOW_DAYS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "分层窗口最多 "
              + ReportingLifecycleSegmentationService.MAX_WINDOW_DAYS
              + " 天（收到 "
              + days
              + " 天）。");
    }
    return new Window(start, end);
  }

  /** 维度层（SKU / SPU）。非法取值 ⇒ 400（不静默回落成 SKU）。 */
  private String normalizeLevel(String level) {
    if (level == null || level.isBlank()) return "SKU";
    var normalized = level.trim().toUpperCase();
    if (!normalized.equals("SKU") && !normalized.equals("SPU")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "分层维度层只能是 SKU / SPU（收到的是「" + level + "」）");
    }
    return normalized;
  }

  /** 空串 ⇒ null（不把空串当成一个真的筛选值）。 */
  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  // ==========================================================================
  // 能力声明的静态事实（**逐条对着上游核过**）
  // ==========================================================================

  /**
   * 本服务**真的读过**的上游模块。
   *
   * <p>⚠️ **不列没读过的**：能力声明是给前端判断「哪些块能画」的依据，
   * 列一个其实没接通的上游比不列更糟 —— 界面会以为功能已交付。
   */
  private static final List<String> UPSTREAM_MODULES =
      List.of(
          "13.2.2.2 SKU/SPU/Listing（reporting /reporting/product-sales）：商品维度档案（SKU / SPU 三层）"
              + "与**销量 / 销售额的日事实**（reporting_product_metric_fact）",
          "13.3.1.1 产品表现分析（reporting /reporting/product-performance）："
              + "**毛利额的日事实**（出库成本，reporting_product_performance_fact）与"
              + "**库存 / 库龄的时点快照**（reporting_product_performance_snapshot）",
          "9 订单（trade）：销量 / 销售额的**原始来源**（13.2.2.2 投影，本模块只读投影）",
          "12.4.1.2 成本（finance）：出库成本的**原始来源**（13.3.1.1 投影，本模块只读投影）",
          "7 库存（warehouse）：在手数量与库龄的**原始来源**（13.3.1.1 投影，本模块只读投影）");

  /**
   * **确实拿不到**的部分（每条都写清原因与归属模块，不写「未接入」这种空话）。
   */
  private static final List<String> NOT_DELIVERED =
      List.of(
          "🔴 **本模块不读上游 HTTP**：分层只在 reporting 服务内部读 13.3.1.1 / 13.2.2.2 已经落成的投影。"
              + "那两个模块没刷过 ⇒ 本模块报「没有事实 / 口径未投影」，**不是 0**。",
          "🔴 **毛利额 ≠ 13.4 多模式利润**：这里的毛利 = 销售额 − 成本法出库成本，"
              + "**不含**平台费 / 头程 / 物流费 / 广告费等成本项。要按经营模式拆利润，得等 13.4.1.1。",
          "🔴 **ABC 按金额只能在同一个币种内部排名**：金额跨币种不可比（1 USD 与 1 EUR 谁大没有答案）"
              + "⇒ 每个币种各排一次，一个商品在两个币种里可能是不同的档。",
          "🔴 **分层只到 SKU / SPU 层**：LISTING 是**上架形态**不是商品本身"
              + "（同一款在多个 Listing 上就是多行，「这个商品活得好不好」问的是商品层）。",
          "🔴 **衰退判定需要历史**：对比窗口里没有销量事实 ⇒ 衰退**不可算**（**不是「没衰退」**）。",
          "🔴 **新品判定需要历史**：回溯窗口里找不到第一次销售 ⇒ 新品判定不成立"
              + "（**不是「它是新品」**）。",
          "**下游未交付不影响本模块**：6.2.1.2 备货 / 5.4 调价 / 7.6.1 呆滞消费本模块的输出；"
              + "本模块**不消费**它们（滞销判定用的是 13.3.1.1 的库存 / 库龄快照，不依赖 7.6.1）。");

  /** 判定规则与口径说明（阈值写清楚，页面上照着显示）。 */
  private static final List<ReportingLifecycleSegmentationModels.LifecycleSegmentationRuleNote> RULES =
      List.of(
          new ReportingLifecycleSegmentationModels.LifecycleSegmentationRuleNote(
              "SEGMENT_ORDER",
              "档位判定顺序（**先生命周期，后表现**）：① 新品 → ② 衰退款 → ③ 爆款 → ④ 旺款 → "
                  + "⑤ 滞销款 → ⑥ 平款。新品与衰退是**阶段**，优先于表现判定。"),
          new ReportingLifecycleSegmentationModels.LifecycleSegmentationRuleNote(
              "ACTION_MAPPING",
              "建议动作：爆款 / 旺款 ⇒ **备货**；滞销款 ⇒ **清仓**；衰退款 ⇒ **下架**；"
                  + "新品 / 平款 ⇒ **推广**。"),
          new ReportingLifecycleSegmentationModels.LifecycleSegmentationRuleNote(
              "ABC",
              "ABC 按帕累托切分（累计贡献比 A / B，默认 80% / 95%）："
                  + "销量相同（金额也相同）的商品**同档**，档位取决于数值，不取决于行的顺序。"),
          new ReportingLifecycleSegmentationModels.LifecycleSegmentationRuleNote(
              "NO_ZERO_FAKE",
              "🔴 **「算不出来」与「等于 0」分开**：档位算不出来（历史不足 / 窗口内没有事实 / 口径未投影）"
                  + "一律显示为「不可比 / 没有数据 / 本轮不可用」+ 原因，**绝不用 0 或某个默认档位冒充**。"),
          new ReportingLifecycleSegmentationModels.LifecycleSegmentationRuleNote(
              "RULE_AUDIT",
              "🔴 **规则可配置、可审计**：阈值 / 分类口径 / 版本 / 生效区间都在库里；"
                  + "改规则 = 加一个版本（旧版本停用），**历史分层结果仍指向旧版本**。"),
          new ReportingLifecycleSegmentationModels.LifecycleSegmentationRuleNote(
              "DATA_LATENCY",
              "数据延迟：报表是投影，不是实时的。页面上常驻显示**数据更新时间**"
                  + "（最近一次分层落库时刻）；空表时是 null（不是一个长得像时间的假值）。"
                  + "报表数字与业务系统冲突时，**以业务系统为准**。"));
}
