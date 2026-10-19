package com.starcon.channel;

import static com.starcon.channel.ListingHijackerModels.*;

import jakarta.validation.Valid;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 4.5.2.1 跟卖监控。
 *
 * <p>蓝图：「监控他人/己方其他店铺对本 ASIN 的跟卖行为，记录跟卖者、价格、店铺，发现异常跟卖或恶意压价时告警。输入：定时抓取或平台接口返回的跟卖信息。输出：跟卖记录 + 预警通知 + 对比视图。关系：上游 = 抓取任务；下游 = 16 合规（品牌保护）、1 工作台预警。<b>跟卖数据只能作为参考</b>，处置动作（投诉/举报）在平台侧完成，系统只负责发现与提醒。」
 *
 * <p>端点按蓝图模块号标注，端点清单里**每一组**都对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 逐维度能力声明 + 判定规则口径 + 数据来源标注。
 *       四段未交付（16 合规品牌保护的处置动作 / 1 工作台预警的通知 / 02 抓取调度器 /
 *       05 平台权威售价）都 available=false + ownerModule + impact。
 *   <li>{@code GET /filter-options} —— 筛选项词表（平台 / 店铺 / 枚举取值域）。
 *   <li>{@code GET|POST|PUT|DELETE /watches} —— 🔴「按商品（ASIN / listing）配置要监控的跟卖，
 *       租户内可维护」：监控对象是真分页列表，可新建 / 改阈值 / 启停 / 删除。
 *   <li>{@code POST /records} —— 🔴「输入：定时抓取或平台接口返回的跟卖信息」：
 *       接收一批跟卖信息，落跟卖记录 + 逐条判定异常跟卖 / 恶意压价。批次级幂等。
 *   <li>{@code GET /records} —— 🔴「记录跟卖者、价格、店铺」：跟卖记录列表（真分页 + 筛选）。
 *   <li>{@code GET /alerts} + {@code PUT /alerts/{id}/acknowledge} —— 🔴「发现异常跟卖或恶意压价时告警」：
 *       预警**记录**（一条跟卖记录最多一条预警）+ 标记已阅。
 *   <li>{@code GET /comparison} —— 🔴「对比视图」：自家参考售价 × 每个跟卖者的价格
 *       （服务端聚合 + 真分页，不是前端拼）。
 * </ul>
 *
 * <p>🔴 **本类没有、也不该有投诉 / 举报 / 申诉端点。** 蓝图加粗句「跟卖数据只能作为参考，
 * 处置动作（投诉/举报）在平台侧完成，系统只负责发现与提醒」是**契约级**的硬要求：
 * 放行一个「发起投诉」的形状，会让界面以为系统能替运营完成平台侧的处置 ——
 * 而它做不到，点了只会得到一个说不清的失败。
 *
 * <p>🔴 路径前缀刻意是 {@code /channel/listing-hijackers}（**独立前缀**）：4.1.1.1 把
 * {@code listings/{uuid}/...} 那一段路径登记成「不放行」，在同一棵路径树上开子资源要同时改别人
 * 已交付的白名单与拒绝清单；而「跟卖监控」是**对自家 listing 的外部观测**，
 * 口径、限流与权限都与「listing 本身」不同。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 4.1.1.1 列表、
 * 4.3.2.2 平台状态回流、4.4.2.x 认领与搬迁同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/listing-hijackers")
public class ListingHijackerController {

  private final AccessService access;
  private final ListingHijackerService service;

  public ListingHijackerController(AccessService access, ListingHijackerService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：16 合规品牌保护的处置动作 / 1 工作台预警的通知 / 02 抓取调度器 /
   * 05 平台权威售价 四个维度 {@code available=false} + ownerModule + impact。
   *
   * <p>响应里同时给出<b>判定规则口径</b>与<b>数据来源标注</b> —— 前端据此显示
   * 「仅供参考、不构成处置结论」，<b>不把未交付的步骤画成已完成</b>，
   * 也<b>不显示一个必然 404 的按钮</b>。
   */
  @GetMapping("/capabilities")
  public ListingHijackerCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 筛选项词表（平台 / 店铺 / 枚举取值域）。 */
  @GetMapping("/filter-options")
  public ListingHijackerFilterOptions filterOptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.filterOptions());
  }

  // ---- 🔴 监控对象 ----

  /**
   * 监控对象列表（真分页 + 筛选）。
   *
   * <p>🔴 筛选口径：{@code status}、{@code platformCode}、{@code shopCode}、
   * {@code keyword}（ASIN / 标题模糊匹配）。
   */
  @GetMapping("/watches")
  public ListingHijackerWatchPage watches(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.watches(status, platformCode, shopCode, keyword, page, size));
  }

  /**
   * 新建监控对象。
   *
   * <p>🔴 被监控的商品给 {@code listingId} 或 ({@code shopCode} + {@code externalItemId}) 其一。
   * 同一个 listing 已有监控对象 ⇒ 409（不允许配两套阈值）。
   */
  @PostMapping("/watches")
  public ListingHijackerWatchRow createWatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveHijackerWatchRequest request) {
    return call(auth, "channel.operation.create", a -> service.createWatch(request));
  }

  /**
   * 更新监控对象（<b>PUT = 全量替换</b>：字段不给 = 清空 / 复位）。
   *
   * <p>🔴 被监控的<b>商品不可改</b>（改了会让历史跟卖记录挂到别的 ASIN 上）——
   * 给了不同的 listingId 直接 422。
   */
  @PutMapping("/watches/{watchId}")
  public ListingHijackerWatchRow updateWatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String watchId,
      @Valid @RequestBody SaveHijackerWatchRequest request) {
    return call(auth, "channel.operation.update", a -> service.updateWatch(watchId, request));
  }

  /**
   * 删除监控对象（连同它的跟卖记录与预警）。
   *
   * <p>🔴 回执写明一并删掉了多少条记录 / 预警 —— 删掉的是事实，调用方必须知道。
   */
  @DeleteMapping("/watches/{watchId}")
  public ListingHijackerWatchDeleteResult deleteWatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String watchId) {
    return call(auth, "channel.operation.delete", a -> service.deleteWatch(watchId));
  }

  // ---- 🔴 回流入库 ----

  /**
   * 🔴 接收一次抓取 / 平台接口返回的跟卖信息。
   *
   * <p>本端点是**跟卖信息进来的唯一入口**：02 平台适配器把抓到的跟卖报价推过来、
   * 或轮询器补捞到，都走它。真实抓取调度器归 02（能力表里 available=false）——
   * 本模块不另建一条抓取通道，两个入口会让同一件事有两个投递路径。
   *
   * <p>响应里带回那次的回执（真的落了几条、跳过几条、是不是重复投递、产生了哪些预警），
   * 让调用方立刻看到"这批数据到底进没进去"。
   */
  @PostMapping("/records")
  public ListingHijackerIngestResult ingest(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody IngestHijackerRecordsRequest request) {
    return call(auth, "channel.operation.create", a -> service.ingest(request));
  }

  // ---- 跟卖记录 ----

  /**
   * 跟卖记录列表（真分页 + 筛选）。
   *
   * <p>🔴 筛选口径：{@code watchId}、{@code listingId}、{@code sellerId}、
   * {@code hijackerKind}（OWN_SHOP / EXTERNAL）、{@code platformCode}、
   * {@code from} / {@code to}（发现时间范围，{@code yyyy-MM-dd}，按 UTC 自然日）。
   */
  @GetMapping("/records")
  public ListingHijackerRecordPage records(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String watchId,
      @RequestParam(required = false) String listingId,
      @RequestParam(required = false) String sellerId,
      @RequestParam(required = false) String hijackerKind,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.records(
                watchId, listingId, sellerId, hijackerKind, platformCode, from, to, page, size));
  }

  // ---- 预警记录 ----

  /**
   * 预警记录列表（真分页 + 筛选）。
   *
   * <p>🔴 筛选口径：{@code watchId}、{@code alertType}、{@code severity}、{@code status}、
   * {@code from} / {@code to}（预警生成时刻范围，{@code yyyy-MM-dd}，按 UTC 自然日）。
   */
  @GetMapping("/alerts")
  public ListingHijackerAlertPage alerts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String watchId,
      @RequestParam(required = false) String alertType,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.alerts(watchId, alertType, severity, status, from, to, page, size));
  }

  /**
   * 标记预警为「已阅」。
   *
   * <p>🔴 这是**内部记录状态**，**不是平台侧的处置** —— 投诉 / 举报 / 申诉在平台侧完成，
   * 本模块不提供、也不代为发起。已经阅过的再阅一次是幂等的（返回同一条）。
   */
  @PutMapping("/alerts/{alertId}/acknowledge")
  public ListingHijackerAlertRow acknowledgeAlert(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String alertId,
      @Valid @RequestBody(required = false) AcknowledgeHijackerAlertRequest request) {
    return call(
        auth, "channel.operation.update", a -> service.acknowledgeAlert(alertId, request));
  }

  // ---- 对比视图 ----

  /**
   * 🔴 对比视图：自家 listing 的参考售价 × 每个跟卖者的价格。
   *
   * <p>🔴 {@code watchId} **必填** —— 对比视图是「某个 ASIN 的自家价 vs 跟卖价」，
   * 跨监控对象混在一起就没有对比的意义了。{@code from} / {@code to} 是发现时间范围
   * （{@code yyyy-MM-dd}，按 UTC 自然日；不给时默认近 30 天）。
   */
  @GetMapping("/comparison")
  public ListingHijackerComparisonPage comparison(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String watchId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.comparison(watchId, from, to, page, size));
  }
}
