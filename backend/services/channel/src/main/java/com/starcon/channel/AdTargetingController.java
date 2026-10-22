package com.starcon.channel;

import static com.starcon.channel.AdTargetingModels.*;

import jakarta.validation.Valid;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 14.1.3.2 投放定位。
 *
 * <p>蓝图：「管理投放定位（地域、人群、设备、时段、匹配类型），支持按定位维度看效果，
 * 指导出价调整。输入：定位设置；定位维度效果数据。输出：定位效果对比表 + 调整建议。
 * 关系：上游 = 14.1.2.1；下游 = 14.2.1.2 分时策略。
 * <b>定位效果差异大时应收窄投放，无效定位的花费是最容易省下来的钱。</b>
 * 端点按蓝图模块号标注，方便对照 14-ads-and-growth.md。
 *
 * <p>🔴 入口分成三组，各自对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明。未交付的段（平台回写 / 执行出价调整 /
 *       平台侧定位同步）显式 {@code available=false} + {@code ownerModule} + 影响。
 *   <li>{@code GET /targetings**} · {@code GET /effects} · {@code GET /suggestions} ·
 *       {@code GET /daypart-profile} —— 定位设置与效果读：列表（真分页 + 真筛选 + 真排序）、
 *       详情、留痕、**定位效果对比表**、**调整建议**、**时段画像**。
 *   <li>{@code POST /targetings} · {@code POST /interpret} · {@code POST /metrics} ·
 *       {@code PATCH /targetings/{id}} · {@code DELETE /targetings/{id}} ·
 *       {@code POST /targetings/{id}/narrow} —— 「输入：定位设置；定位维度效果数据」的
 *       **真实写入口**：新建 / 改 / 归档 / **解读平台原文** / **同步效果数据** / **收窄投放**。
 * </ul>
 *
 * <p>🔴 本模块**不执行**出价调整：改竞价是钱，走上游 14.1.1.2 的改竞价入口（带护栏与审计）。
 * 本模块只给**出价系数建议**与**系数记录** —— 两个端点改同一笔钱，审计就会分叉。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 14.1.1.1 / 14.1.2.1 /
 * 14.1.3.1 同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/ad-targetings")
public class AdTargetingController {

  private final AccessService access;
  private final AdTargetingService service;

  public AdTargetingController(AccessService access, AdTargetingService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /** 能力声明：未交付的段（平台回写 / 执行出价调整 / 平台侧定位同步）显式 available=false。 */
  @GetMapping("/capabilities")
  public AdTargetingCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 筛选项下拉 + 建议门槛与系数护栏。 */
  @GetMapping("/options")
  public AdTargetingOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.options());
  }

  // ---- 定位设置 ----

  /** 定位设置列表：真分页 + 真筛选 + 真排序。 */
  @GetMapping
  public AdTargetingSettingPage targetings(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String campaignId,
      @RequestParam(required = false) String groupId,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) String dimensionValue,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.settings(
                campaignId, groupId, dimension, dimensionValue, status, keyword, sort, page, size));
  }

  /** 定位设置汇总（不受分页影响；只给条数，不做跨币种金额相加）。 */
  @GetMapping("/summary")
  public AdTargetingSettingSummary summary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String campaignId,
      @RequestParam(required = false) String groupId,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) String dimensionValue,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.summary(
                campaignId, groupId, dimension, dimensionValue, status, keyword));
  }

  /** 定位设置详情：设置 + 该设置的区间效果 + 最近留痕 + 下游入口。 */
  @GetMapping("/{settingId}")
  public AdTargetingDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String settingId) {
    return call(auth, "channel.operation.read", a -> service.detail(settingId));
  }

  /** 定位操作留痕：真分页（按时刻倒序）。 */
  @GetMapping("/{settingId}/operations")
  public AdTargetingOperationPage operations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String settingId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.operations(settingId, page, size));
  }

  // ---- 🔴 定位效果对比表与建议 ----

  /**
   * 🔴 定位效果对比表：按定位维度看效果（真分页 + 真筛选 + 真排序 + 区间汇总）。
   *
   * <p>🔴 派生比率在分母为 0 时是 <b>null（不可比）</b>，不是 0；金额**不跨币种相加**。
   */
  @GetMapping("/effects")
  public AdTargetingEffectPage effects(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String campaignId,
      @RequestParam(required = false) String groupId,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) String dimensionValue,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.effects(
                campaignId,
                groupId,
                dimension,
                dimensionValue,
                currency,
                from,
                to,
                sort,
                page,
                size));
  }

  /**
   * 🔴 调整建议：每条都带**依据数值** + 口径 + 阈值；样本不足时 actionable=false。
   */
  @GetMapping("/suggestions")
  public AdTargetingSuggestionResult suggestions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String campaignId,
      @RequestParam(required = false) String groupId,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.suggestions(campaignId, groupId, dimension, from, to));
  }

  /**
   * 🔴 时段画像：<b>供下游 14.2.1.2 分时策略复用</b>的数据（哪个时段值得投、哪个在烧钱）。
   */
  @GetMapping("/daypart-profile")
  public AdTargetingDaypartProfile daypartProfile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String campaignId,
      @RequestParam(required = false) String groupId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.daypartProfile(campaignId, groupId, from, to));
  }

  // ---- 写入口 ----

  /** 新建定位设置（本地真落库；标着「待回写平台」）。 */
  @PostMapping
  public AdTargetingSettingRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AdTargetingCreateRequest request) {
    return call(auth, "channel.operation.create", a -> service.create(request));
  }

  /**
   * 🔴 解读平台原文定位串 → 归一定位维度（apply=false 只预览，apply=true 落库）。
   *
   * <p>🔴 这是「解读归 14.1.3.2」的真正动作：原文来自上游 V281 的 targeting_settings。
   */
  @PostMapping("/interpret")
  public AdTargetingInterpretResult interpret(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AdTargetingInterpretRequest request) {
    return call(auth, "channel.operation.create", a -> service.interpret(request));
  }

  /**
   * 🔴 定位维度效果数据同步（真实写入口；逐条拒绝不合法的那条）。
   *
   * <p>🔴 维度与归属**由定位设置决定**，调用方不能自报。
   */
  @PostMapping("/metrics")
  public AdTargetingMetricBatchResult ingestMetrics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AdTargetingMetricBatchRequest request) {
    return call(auth, "channel.operation.create", a -> service.ingestMetrics(request));
  }

  /** 改定位设置（部分更新：没给的字段不改）。 */
  @PatchMapping("/{settingId}")
  public AdTargetingSettingRow update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String settingId,
      @Valid @RequestBody AdTargetingUpdateRequest request) {
    return call(auth, "channel.operation.update", a -> service.update(settingId, request));
  }

  /** 归档定位设置（软删除：历史指标与留痕都还在）。 */
  @DeleteMapping("/{settingId}")
  public AdTargetingSettingRow archive(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String settingId,
      @RequestBody(required = false) AdTargetingArchiveRequest request) {
    AdTargetingArchiveRequest body =
        request == null ? new AdTargetingArchiveRequest("") : request;
    return call(auth, "channel.operation.delete", a -> service.archive(settingId, body));
  }

  /**
   * 🔴 收窄投放：把一条定位置为暂停（带证据门槛；证据不足要显式 override + 理由）。
   */
  @PostMapping("/{settingId}/narrow")
  public AdTargetingNarrowResult narrow(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String settingId,
      @Valid @RequestBody AdTargetingNarrowRequest request) {
    return call(auth, "channel.operation.update", a -> service.narrow(settingId, request));
  }
}
