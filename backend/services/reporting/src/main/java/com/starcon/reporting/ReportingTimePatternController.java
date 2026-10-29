package com.starcon.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 13.2.1.2 分时与分周模式的 HTTP 端点。
 *
 * <p>命名空间是 {@code /reporting/time-pattern}：13.2.1.1 用的是 {@code /reporting/sales-trend}，
 * 13.2.2.1 用的是 {@code /reporting/store-site} —— 兄弟模块各有各的前缀，
 * 挤在同一个前缀下会让「哪个模块的哪个端点」变得说不清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码：{@code reporting.time-pattern.read}（读报表 / 口径 / 能力声明 / 时段配置）与
 * {@code reporting.time-pattern.write}（改时段配置 + 刷新投影 —— 写的是**投影**与
 * <b>租户自己的配置</b>，不是业务事实）。
 *
 * <p>🔴 <b>窗口的默认起止用 DB 的 {@code now()}</b>（DB 钟域），不是 JVM 的墙钟 ——
 * 报表的「今天」必须与服务端落库的钟域一致。
 */
@RestController
@RequestMapping("/reporting/time-pattern")
@Tag(name = "13.2.1.2 分时与分周模式")
public class ReportingTimePatternController {

  /** 一次刷新窗口最多多少天。 */
  static final int MAX_WINDOW_DAYS = 92;

  private final AccessService access;
  private final ReportingTimePatternService pattern;
  private final ReportingTimePatternRefreshService refresh;

  public ReportingTimePatternController(
      AccessService access,
      ReportingTimePatternService pattern,
      ReportingTimePatternRefreshService refresh) {
    this.access = access;
    this.pattern = pattern;
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
      summary = "能力声明：时区怎么定、汇总按哪个口径、高峰低谷怎么判、哪些确实拿不到",
      description =
          "notDelivered 列出的是**确实拿不到**的部分，每条都写清原因与影响 —— "
              + "把「拿不到」伪装成 0 比说清楚更糟。")
  public ReportingTimePatternModels.TimePatternCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "reporting.time-pattern.read",
        actor ->
            new ReportingTimePatternModels.TimePatternCapabilities(
                true,
                true,
                true,
                true,
                UPSTREAM_MODULES,
                NOT_DELIVERED,
                RULES,
                ALIGNMENTS,
                ReportingTimePatternCatalog.Alignment.LOCAL_TIME.name(),
                ReportingTimePatternCatalog.alignmentNote(
                    ReportingTimePatternCatalog.Alignment.LOCAL_TIME)));
  }

  // ================================================================ 指标口径目录

  @GetMapping("/metrics")
  @Operation(summary = "分时指标口径目录（口径唯一的展示形态）")
  public List<ReportingTimePatternModels.TimePatternMetricDefinitionView> metrics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "reporting.time-pattern.read",
        actor -> pattern.metricDefinitions(actor.tenantId()));
  }

  // ================================================================ 店铺时区档案

  @GetMapping("/shops")
  @Operation(
      summary = "店铺时区档案（每个店铺按哪个时区统计；定不了的也列出来并写清原因）",
      description =
          "🔴 店铺**没有**自己的时区字段：时区来自 channel 的**站点**参数"
              + "（shop_marketplace → marketplace.default_timezone）。"
              + "启用多个站点且时区不一致、或没启用站点的店铺**定不了时区** —— "
              + "它们的订单不进任何本地小时 / 星期桶。")
  public List<ReportingTimePatternModels.TimePatternShopView> shops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "reporting.time-pattern.read", actor -> pattern.shops(actor.tenantId()));
  }

  // ================================================================ 分时与分周模式

  @GetMapping
  @Operation(
      summary = "分时与分周模式：热力图 + 小时 / 星期分布 + 时段判定 + 高峰时段排序",
      description =
          "时间范围默认「最近 30 天」（**按 DB 的 now() 算**，不是 JVM 墙钟）。"
              + "🔴 **alignment 决定跨店铺汇总对齐的是哪一个槽位**："
              + "LOCAL_TIME（默认，店铺本地时间对齐，回答「买家什么时候下单」）与 "
              + "UTC_SLOT（UTC 槽位对齐，回答「仓内同一绝对时刻的压力」）"
              + "**结果不同** —— 响应里 always 回带当前口径与理由。"
              + "⚠️ 金额按币种分别列出，**不跨币种相加**。")
  public ReportingTimePatternModels.TimePatternResponse pattern(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false, defaultValue = "LOCAL_TIME") String alignment,
      @RequestParam(required = false) String shopCodes,
      @RequestParam(required = false, defaultValue = "ORDER_COUNT") String rankingMetric) {
    return call(
        auth,
        "reporting.time-pattern.read",
        actor -> {
          var window = resolveWindow(from, to);
          return pattern.pattern(
              actor.tenantId(),
              window.from(),
              window.to(),
              resolveAlignment(alignment),
              splitCodes(shopCodes),
              rankingMetric);
        });
  }

  // ================================================================ 时段配置（增删改查）

  @GetMapping("/slots")
  @Operation(
      summary = "时段配置清单（租户自己的数据：高峰 / 平峰 / 低谷的区间与判定阈值）",
      description =
          "🔴 区间是**左闭右开** `[startHour, endHour)`：startHour 含、endHour 不含；"
              + "endHour = 24 表示到当天 23:59。"
              + "⚠️ 启用时段必须**完整覆盖 0..24 小时**（不留空洞）且**互不重叠** —— "
              + "这是数据层的约束（延迟约束触发器），改坏了写不进来。")
  public List<ReportingTimePatternModels.TimePatternSlotConfigView> slots(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "reporting.time-pattern.read",
        actor -> pattern.slotConfigs(actor.tenantId()));
  }

  @PostMapping("/slots")
  @Operation(
      summary = "新建一个时段配置",
      description =
          "「某个小时属于哪个时段」「高峰低谷怎么判」**全部读这张表** —— "
              + "改了配置，判定立刻跟着改（没有写死的常量）。")
  public ReportingTimePatternModels.TimePatternSlotConfigView createSlot(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ReportingTimePatternModels.TimePatternSlotRequest request) {
    return call(
        auth,
        "reporting.time-pattern.write",
        actor -> {
          try {
            return pattern.createSlot(actor.tenantId(), request);
          } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
          } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, slotErrorMessage(e, "时段配置写不进来"));
          }
        });
  }

  @PutMapping("/slots/{id}")
  @Operation(
      summary = "改一个时段配置",
      description =
          "⚠️ 区间不可重叠、不留空洞 ⇒ **改一个时段往往要连带改相邻时段**；"
              + "整体调整请用 `PUT /reporting/time-pattern/slots`（一次成型）。")
  public ReportingTimePatternModels.TimePatternSlotConfigView updateSlot(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody ReportingTimePatternModels.TimePatternSlotRequest request) {
    return call(
        auth,
        "reporting.time-pattern.write",
        actor -> {
          try {
            return pattern.updateSlot(actor.tenantId(), id, request);
          } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
          } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, slotErrorMessage(e, "时段配置改不了"));
          }
        });
  }

  @PutMapping("/slots")
  @Operation(
      summary = "整体替换时段配置（一次成型，避免逐条改时中间态留空洞）",
      description =
          "请求体是**完整的时段集合**。服务端在一个事务里先校验"
              + "（互不重叠 + 完整覆盖 0..24），再整体替换 —— "
              + "校验不过则**整体回滚**，旧配置原样保留。")
  public List<ReportingTimePatternModels.TimePatternSlotConfigView> replaceSlots(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ReportingTimePatternModels.TimePatternSlotReplaceRequest request) {
    return call(
        auth,
        "reporting.time-pattern.write",
        actor -> {
          try {
            return pattern.replaceSlots(actor.tenantId(), request);
          } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
          } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, slotErrorMessage(e, "时段配置整体替换失败"));
          }
        });
  }

  @DeleteMapping("/slots/{id}")
  @Operation(
      summary = "删一个时段配置",
      description =
          "⚠️ 删掉之后启用时段若不再完整覆盖 0..24 小时（留了空洞），"
              + "**整个删除会被拒**（数据层约束）—— 请先扩宽相邻时段或用整体替换。")
  public void deleteSlot(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    call(
        auth,
        "reporting.time-pattern.write",
        actor -> {
          try {
            pattern.deleteSlot(actor.tenantId(), id);
            return null;
          } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
          } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, slotErrorMessage(e, "时段配置删不了"));
          }
        });
  }

  // ================================================================ 刷新（投影摄取）

  @PostMapping("/refresh")
  @Operation(
      summary = "从上游拉取出单事实并投影（写的是投影，不是业务事实）",
      description =
          "上游：trade 的销售订单与订单明细、trade 的平台单（店铺归属）、"
              + "channel 的平台 / 站点目录与店铺（**店铺时区**）。"
              + "⚠️ 店铺**定不了时区**的订单会落库但**不进任何本地桶**，"
              + "并在报表里单列 —— 不摊给任何时段。"
              + "⚠️ 订单明细要逐单取（有上限），超出的部分**未计入销量**，刷新结果里会报出截断。")
  public List<ReportingTimePatternModels.TimePatternRefreshResult> refresh(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return call(
        auth,
        "reporting.time-pattern.write",
        actor -> {
          var window = resolveWindow(from, to);
          var days = (int) (window.to().toEpochDay() - window.from().toEpochDay() + 1);
          if (days > MAX_WINDOW_DAYS) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "窗口最多 " + MAX_WINDOW_DAYS + " 天（当前 " + days + " 天）—— "
                    + "一次刷新要逐页拉上游订单，窗口太长会把上游压垮。");
          }
          return refresh.refresh(actor.tenantId(), auth, window.from(), window.to());
        });
  }

  // ================================================================ 参数解析

  /** 时间窗口。默认「最近 30 天」（含今天，**今天取自 DB 的 now()**）。 */
  private record Window(LocalDate from, LocalDate to) {}

  private Window resolveWindow(LocalDate from, LocalDate to) {
    var end = to == null ? pattern.dbToday() : to;
    var start = from == null ? end.minusDays(29) : from;
    if (start.isAfter(end)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "起始日期不能晚于结束日期（from=" + start + "、to=" + end + "）");
    }
    return new Window(start, end);
  }

  private ReportingTimePatternCatalog.Alignment resolveAlignment(String value) {
    var name = value == null || value.isBlank() ? "LOCAL_TIME" : value.trim().toUpperCase();
    for (var alignment : ReportingTimePatternCatalog.Alignment.values()) {
      if (alignment.name().equals(name)) return alignment;
    }
    throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST, "汇总口径只能是 LOCAL_TIME / UTC_SLOT：" + value);
  }

  private List<String> splitCodes(String value) {
    if (value == null || value.isBlank()) return List.of();
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(code -> !code.isEmpty())
        .toList();
  }

  /** 把 DB 抛的约束错误翻成一句人话。 */
  private static String slotErrorMessage(Exception e, String fallback) {
    var message = e.getMessage();
    if (message == null) return fallback;
    var marker = message.indexOf("ERROR:");
    if (marker >= 0) {
      var detail = message.substring(marker + "ERROR:".length()).trim();
      var cut = detail.indexOf("Context:");
      if (cut > 0) detail = detail.substring(0, cut).trim();
      if (!detail.isEmpty()) return detail;
    }
    return fallback;
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
          "9.1.1.1 销售订单列表（trade sales-orders/list：金额、币种、**下单时刻**）",
          "9.1.1.1 销售订单明细（trade sales-orders/detail/{id}：销量件数）",
          "9.1.1.1 平台单列表（trade platform-orders/orders：用 salesOrderId 把订单回挂到店铺）",
          "2.1.1.3 平台与站点目录（channel platforms：站点默认币种 / **默认时区** / 国家）",
          "2.3.1.1 店铺清单与店铺详情（channel shops / shops/{id}：该店铺**启用了哪些站点**）");

  /**
   * **确实拿不到**的部分（每条都写清原因，不写「未接入」这种空话）。
   */
  private static final List<String> NOT_DELIVERED =
      List.of(
          "**店铺自己的时区字段**：channel 的 `shop` 表**没有**时区列 —— "
              + "时区在**站点**参数（`marketplace.default_timezone`）上，"
              + "店铺与站点的绑定在 `shop_marketplace`。"
              + "⇒ 本模块用「该店铺启用的站点的默认时区」作为它的统计时区；"
              + "启用多个站点且**时区不一致**、或没启用站点的店铺 ⇒ **定不了时区**，"
              + "它们的订单**不进任何本地小时 / 星期桶**（单列，不摊给任何时段）。",
          "**ISO 周的周数**：蓝图的「按星期」说的是**星期几**，"
              + "本模块给的是**店铺本地时区**的 ISO 星期几（1 = 周一 … 7 = 周日）。"
              + "**周数**（第几周）是另一回事，本模块**不产出** —— 不编一个出来充数。",
          "**促销排期与作业排班的下游联动**：8.5 作业排班与 14 广告（分时投放）"
              + "是本模块的**下游**，不是上游 ⇒ 本模块只**给出**时段桶与高峰排序，"
              + "不代它们改排期。",
          "**超出明细取数上限的订单**：销量要逐单取订单明细（有上限），"
              + "超出的部分**未计入** —— 刷新结果里会标 TRUNCATED，**不悄悄少算**。");

  /** 判定规则（阈值写清楚，页面上照着显示）。 */
  private static final List<ReportingTimePatternModels.TimePatternRuleNote> RULES =
      List.of(
          new ReportingTimePatternModels.TimePatternRuleNote(
              "TIMEZONE",
              "🔴 **时区**：「按小时」= **店铺本地时区**的小时；「按星期」= **店铺本地时区**的"
                  + "星期几（本地周一 00:00 到本地周日 23:59:59）。"
                  + "时区用 **IANA 标识**（如 `America/Los_Angeles`），**不用固定 UTC 偏移** —— "
                  + "固定偏移会错过夏令时，本地小时会整整差一小时。"
                  + "店铺时区来自 channel 的**站点**参数；定不了的店铺单列，不摊给任何时段。"),
          new ReportingTimePatternModels.TimePatternRuleNote(
              "ALIGNMENT",
              "🔴 **汇总口径**：跨店铺汇总时，「小时 17」有两种含义 —— "
                  + "**LOCAL_TIME（默认）**= 每个店铺各自的本地 17:00–17:59；"
                  + "**UTC_SLOT** = 所有店铺共同的 UTC 17:00–17:59。"
                  + "**两者结果不同**，选的是哪个必须写在报告上。"
                  + "默认 LOCAL_TIME 的理由：蓝图的下游是 8.5 作业排班与 14 广告（分时投放），"
                  + "问的都是「买家在什么时间点下单」，而买家的时钟是**店铺所在市场的本地时钟**。"),
          new ReportingTimePatternModels.TimePatternRuleNote(
              "SLOT_CONFIG",
              "🔴 **时段配置**：高峰 / 平峰 / 低谷是**租户自己标的**，"
                  + "系统只负责**用阈值核对它**：判定基准（订单占比 / 订单笔数）"
                  + "+ 判定方向（至少达到 / 至多达到）+ 阈值。核对结果与理由都回给页面。"
                  + "时段区间**互不重叠、不留空洞**（数据层约束），边界是**左闭右开**。"),
          new ReportingTimePatternModels.TimePatternRuleNote(
              "P90",
              "🔴 **P90 高峰刻度**：小时订单量分布的 **90 分位**（线性插值，"
                  + "与 PostgreSQL 的 percentile_cont 同一定义）。"
                  + "**24 个小时桶全部参与**（没有事实的小时算 0）—— "
                  + "只拿有单的小时算分位会把高峰刻度抬高。"
                  + "它是**数据驱动**的刻度，与租户自己标的时段层级是**两回事**，页面上分开显示。"),
          new ReportingTimePatternModels.TimePatternRuleNote(
              "CURRENCY",
              "金额**按币种分别列出**，**绝不跨币种相加**；"
                  + "订单数与销量件数与货币无关，给一个跨币种合计。"),
          new ReportingTimePatternModels.TimePatternRuleNote(
              "NO_DATA",
              "「算不出来」与「等于 0」分开说：占比的分母为 0 时返回**不可比**（不是 0%）；"
                  + "某个格子没有事实时它是**真的 0**（热力图是固定的 7×24 网格），"
                  + "但**投影不满**时「0 里有多少是没投影」说不清 —— 看覆盖度。"));

  /** 可用汇总口径。 */
  private static final List<String> ALIGNMENTS = List.of("LOCAL_TIME", "UTC_SLOT");
}
