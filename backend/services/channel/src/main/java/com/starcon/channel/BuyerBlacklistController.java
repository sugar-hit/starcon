package com.starcon.channel;

import static com.starcon.channel.BuyerBlacklistModels.*;

import jakarta.validation.Valid;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 16.5.1.1 黑名单。
 *
 * <p>蓝图：「维护买家黑名单：买家标识、店铺、原因、风险等级、生效范围（仅本店铺/全店铺），
 * 下单时自动标记并可拦截。输入：黑名单录入（手工/自动，来自风控信号）；解除操作。
 * 输出：黑名单记录；订单拦截标记。关系：上游 = 16.5.1.2 评分、9.2 审单；下游 = 9.2.1.3 拦截、11 售后。
 * <b>黑名单应支持申诉解除</b>，永久不解除会让误判的正常买家永久损失。」
 *
 * <p>🔴 端点清单（命名空间 {@code /channel/buyer-blacklist}，与既有的
 * {@code /channel/buyer-risk-scores}、{@code /channel/compliance-precheck} 等**并列而不重叠**）：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明：逐项 available + ownerModule + 上游读面可用性。
 *   <li>{@code GET /entries} —— 黑名单清单（真分页 + 真筛选 + 汇总计数）。
 *   <li>{@code POST /entries} —— 手工录入黑名单。
 *   <li>{@code POST /entries/auto} —— 自动录入（来自风控信号）。
 *   <li>{@code GET /entries/{id}} —— 登记详情（头 + 状态流转留痕 + 申诉单）。
 *   <li>{@code POST /entries/{id}/release} —— 解除（**必须带理由**，留痕）。
 *   <li>{@code POST /entries/{id}/appeals} —— 提交申诉。
 *   <li>{@code POST /appeals/{id}/decision} —— 申诉裁决（通过 ⇒ 解除；驳回 ⇒ 维持）。
 *   <li>{@code POST /check} —— 下单时自动标记并可拦截（命中就落一条订单拦截标记）。
 *   <li>{@code GET /flags} —— 订单拦截标记清单（真分页 + 真筛选）。
 * </ul>
 *
 * <p>🔴 <b>刻意没有的形状</b>：没有「改订单状态」的端点（那是 9.2 的事）、
 * 没有「代建售后单」的端点（那是 11 的事）、没有「删除黑名单」的端点
 * （黑名单是判定凭据，**只能解除不能删除** —— 删除等于把历史抹掉）、
 * 没有「批量拉黑 / 批量放行」的端点（批量操作让「谁拉黑的」这件事糊掉）——
 * <b>放行一个不存在的入口比 404 更糟</b>（界面会以为功能已交付）。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（读 / 录 / 改），identity 零改动。
 * 租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/channel/buyer-blacklist")
public class BuyerBlacklistController {

  private final AccessService access;
  private final BuyerBlacklistService service;

  public BuyerBlacklistController(AccessService access, BuyerBlacklistService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  /** 操作人标识（谁拉黑的 / 谁解除的）—— 写进状态流转事件，可审计。 */
  private static String actorOf(AccessService.Access who) {
    return who.membershipId() != null ? who.membershipId().toString() : who.userId().toString();
  }

  // ---- 能力声明 ----

  /** 能力声明：逐项 available + ownerModule + 上游读面可用性 + 五条口径说明。 */
  @GetMapping("/capabilities")
  public BuyerBlacklistCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 清单与详情 ----

  /** 黑名单清单（真分页 + 真筛选 + 与分页无关的汇总计数）。 */
  @GetMapping("/entries")
  public BuyerBlacklistEntryPage entries(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String scope,
      @RequestParam(required = false) String riskLevel,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String source,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.entries(status, scope, riskLevel, shopCode, source, keyword, page, size));
  }

  /** 登记详情：头 + 状态流转留痕 + 申诉单。 */
  @GetMapping("/entries/{id}")
  public BuyerBlacklistEntryDetail entry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.entry(id));
  }

  // ---- 录入 ----

  /** 手工录入黑名单。 */
  @PostMapping("/entries")
  public BuyerBlacklistEntryView register(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BuyerBlacklistRegisterInput input) {
    return call(
        auth,
        "channel.operation.create",
        a -> service.register(auth, input, actorOf(a)));
  }

  /** 自动录入（来自风控信号）。信号引用必填 —— 自动来源必须可追溯。 */
  @PostMapping("/entries/auto")
  public BuyerBlacklistEntryView registerAuto(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BuyerBlacklistAutoInput input) {
    return call(
        auth,
        "channel.operation.create",
        a -> service.registerAuto(input, actorOf(a)));
  }

  // ---- 解除与申诉 ----

  /** 解除黑名单（追加一条状态流转事件；**必须带理由**）。 */
  @PostMapping("/entries/{id}/release")
  public BuyerBlacklistEntryView release(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody BuyerBlacklistReleaseInput input) {
    return call(
        auth,
        "channel.operation.update",
        a -> service.release(id, input, actorOf(a)));
  }

  /** 提交申诉。 */
  @PostMapping("/entries/{id}/appeals")
  public BuyerBlacklistAppealView submitAppeal(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody BuyerBlacklistAppealInput input) {
    return call(
        auth,
        "channel.operation.create",
        a -> service.submitAppeal(id, input, actorOf(a)));
  }

  /** 申诉裁决（APPROVED ⇒ 解除并留痕；REJECTED ⇒ 维持并留痕）。 */
  @PostMapping("/appeals/{id}/decision")
  public BuyerBlacklistAppealView decideAppeal(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody BuyerBlacklistAppealDecisionInput input) {
    return call(
        auth,
        "channel.operation.update",
        a -> service.decideAppeal(id, input, actorOf(a)));
  }

  // ---- 下单检查与拦截标记 ----

  /**
   * 下单时自动标记并可拦截。
   *
   * <p>🔴 9.2 审单读不到时**整个请求 503**：本模块不会用「读不到」冒充「这个买家没有风险」。
   */
  @PostMapping("/check")
  public BuyerBlacklistCheckResult check(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BuyerBlacklistCheckInput input) {
    // 🔴 检查端点**会写一条订单拦截标记**，所以用 create 权限（不是 read）——
    //   一个只读权限就能往库里写标记，等于绕过写审计。
    return call(auth, "channel.operation.create", a -> service.check(auth, input));
  }

  /** 订单拦截标记清单（真分页 + 真筛选）。 */
  @GetMapping("/flags")
  public BuyerBlacklistFlagPage flags(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String buyerKey,
      @RequestParam(required = false) String action,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.flags(buyerKey, action, page, size));
  }
}
