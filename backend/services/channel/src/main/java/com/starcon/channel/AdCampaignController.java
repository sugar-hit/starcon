package com.starcon.channel;

import static com.starcon.channel.AdCampaignModels.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

/**
 * 14.1.1.1 广告活动列表。
 *
 * <p>蓝图：「广告活动主列表：平台、店铺、活动名、类型、状态、预算、消耗、曝光、点击、订单、销售额、ACOS/ROAS，
 * 按维度筛选与排序，支持批量暂停/启用/调预算。输入：筛选条件（平台、店铺、状态、日期）；广告数据同步结果；用户数据范围。
 * 输出：分页列表 + 汇总；批量操作任务。关系：上游 = 平台广告 API 同步；下游 = 14.1.1.2 详情、14.2.2.1 预算。
 * <b>广告数据同步是本系统的地基</b>：同步失败时页面要明确显示「数据截至 X 时间」，不能用空数据假装没有投放。」
 * 端点按蓝图模块号标注，方便对照 14-ads-and-growth.md。
 *
 * <p>🔴 入口刻意分成四组，各自对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code GET /campaigns} —— 「主列表」。真分页 + 真筛选 + 真排序 + 汇总 + **数据截至 X 时间**。
 *   <li>{@code POST /imports} —— 「输入：广告数据同步结果」。平台同步器尚未交付，本模块**自带真实写入口**
 *       把活动与每日指标落库；失败���同步只登记失败、**不推进水位**，也不写活动数据。
 *   <li>{@code POST /bulk-operations} —— 「支持批量暂停/启用/调预算」。**只接受显式选中的 id**，
 *       逐条返回执行结果，带单次与总额护栏。
 *   <li>{@code GET /by-external} —— 给 14.2.2.2 自动规则与 14.2.2.1 预算分配的读取形状：
 *       按平台侧 id 取活动当前状态与预算/消耗。
 * </ul>
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 14.1.2.2 广告类型差异同族），
 * identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/ad-campaigns")
public class AdCampaignController {

  private final AccessService access;
  private final AdCampaignService service;

  public AdCampaignController(AccessService access, AdCampaignService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /** 能力声明：未交付的段（按筛选全选、异步批量任务、平台回写、活动详情）显式 available=false。 */
  @GetMapping("/capabilities")
  public AdCampaignCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 筛选项与护栏上限（下拉项 + 「一次最多批量操作多少条」这类界面上要写明的数）。 */
  @GetMapping("/options")
  public AdCampaignOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.options());
  }

  // ---- 主列表 ----

  /**
   * 广告活动主列表：真分页 + 真筛选 + 真排序。
   *
   * <p>🔴 响应里三样东西不能省：
   *
   * <ul>
   *   <li>{@code summary} —— 统计的是**筛选后的全集**，不是当前页。
   *   <li>{@code dataAsOf} + {@code syncStates} —— 「数据截至 X 时间」与失败原因。
   *   <li>{@code emptyReason} —— 把「还没同步到数据」「真的没有投放」「被筛掉了」分成三种。
   * </ul>
   */
  @GetMapping("/campaigns")
  public AdCampaignPage campaigns(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformId,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String internalAdType,
      @RequestParam(required = false) String platformAdType,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false, defaultValue = "SPEND_DESC") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.campaigns(
                blankUuid(platformId),
                shopCode,
                status,
                internalAdType,
                platformAdType,
                keyword,
                from,
                to,
                sort,
                page,
                size));
  }

  /** 单活动：本体 + 每日表现明细 + 操作审计（14.1.1.2 活动详情的**数据底座**，视图本身归 14.1.1.2）。 */
  @GetMapping("/campaigns/{id}")
  public AdCampaignDetail campaign(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.campaign(id));
  }

  /**
   * 按平台侧活动 id 取活动的当前状态与预算 / 消耗。
   *
   * <p>🔴 这是给 14.2.2.2 自动规则（以及 14.2.2.1 预算分配）用的读取形状：
   * 规则引擎要判断「这条活动现在什么状态、预算多少、花了多少」才能决定要不要调。
   * 形状刻意做窄：**只读、只给当前值**。
   */
  @GetMapping("/by-external")
  public AdCampaignExternalView byExternal(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String platformId,
      @RequestParam String shopCode,
      @RequestParam String externalCampaignId) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.externalView(blankUuid(platformId), shopCode, externalCampaignId));
  }

  // ---- 输入：广告数据同步结果 ----

  /**
   * 登记一次广告数据同步的结果（活动 + 每日指标 + 同步运行）。
   *
   * <p>🔴 平台广告 API 同步器**尚未交付**，但「广告数据同步是本系统的地基」意味着活动与表现数据
   * 必须真的有落点 —— 本端点就是那个落点，**绝不用空数组或硬编码常量假装有投放**。
   *
   * <p>🔴 {@code syncRun.status = FAILED} 时只登记失败：失败意味着没拿到数据，
   * 写进去就是脏数据；而且失败**不推进水位**，页面上「数据截至 X 时间」因此不会虚高。
   */
  @PostMapping("/imports")
  public ImportResult imports(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ImportRequest request) {
    return call(auth, "channel.operation.create", a -> service.importCampaigns(request));
  }

  // ---- 批量暂停 / 启用 / 调预算 ----

  /**
   * 对**显式选中的一批活动 id** 批量暂停 / 启用 / 调预算，逐条返回执行结果。
   *
   * <p>🔴 不接受「按筛选条件全选」：那是 14.1.1.3 的职责（未交付），本模块不假装做了。
   * 护栏（单次调整上限、总额调整上限、单活动预算上限、单次条数上限）随响应一起返回。
   */
  @PostMapping("/bulk-operations")
  public BulkOperationResult bulkOperations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BulkOperationRequest request) {
    return call(auth, "channel.operation.update", a -> service.bulkOperation(request));
  }

  // ---- 同步运行历史 ----

  /** 同步运行历史：每一条都带状态、数据截至时刻与失败原因（「数据截至 X 时间」的逐条依据）。 */
  @GetMapping("/sync-runs")
  public AdSyncRunPage syncRuns(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformId,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.syncRuns(blankUuid(platformId), shopCode, page, size));
  }

  /** 空串与 null 同义，避免把 {@code ?platformId=} 当成「筛选空字符串」。 */
  private static java.util.UUID blankUuid(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return java.util.UUID.fromString(value.trim());
    } catch (IllegalArgumentException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "平台 id 不是合法 UUID");
    }
  }
}
