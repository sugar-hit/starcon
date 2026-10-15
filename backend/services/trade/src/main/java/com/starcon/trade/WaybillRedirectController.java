package com.starcon.trade;

import static com.starcon.trade.WaybillRedirectModels.*;

import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 10.2.1.3 改址与取消入口。
 *
 * <p>命名空间 {@code /trade/logistics/waybill-address-change}，与两个兄弟<b>并列</b>
 * 而不是嵌套或覆盖：
 *
 * <ul>
 *   <li>{@code /trade/logistics/waybills} —— 10.2.1.1 运单清单（只读 + 列表级登记）
 *   <li>{@code /trade/logistics/waybill-applications} —— 10.2.1.2 运单申请（要号）
 *   <li><b>{@code /trade/logistics/waybill-address-change}</b> —— 10.2.1.3 改址与取消
 *       （改 / 撤<b>已出号</b>的票）
 * </ul>
 *
 * 三个字面量互不为前缀，所以 proxy 白名单也分得开、不会互相吃掉。
 *
 * <p>形状逐个列出：
 *
 * <ul>
 *   <li>{@code capabilities} 能力与上下游可用性（承运商改址接口 / 买家通知送达 / 平台回写 均未交付）
 *   <li>{@code rules} 渠道改址与取消规则（真数据，GET 集合 + POST upsert）
 *   <li>{@code redirects} 改址清单（真分页 + 真筛选 + 排序白名单，<b>汇总不受分页影响</b>）
 *   <li>{@code redirects/{id}} 单条详情（含判定基准、承运商侧口径、联动单据、可执行动作）
 *   <li>{@code redirects/{id}/submit} 发起改址（<b>落单 + 判定 + 派发 + 建通知任务</b>）
 *   <li>{@code precheck} 批量预判「现在能不能改」（<b>不落库</b>，纯读 + 现算）
 *   <li>{@code cancels} 取消清单 / {@code cancels/{id}} 详情 / {@code cancels/{id}/submit}
 *   <li>{@code notifications} 买家通知任务清单（只读；15 接走发送，本模块不发送）
 * </ul>
 *
 * <p>⚠️ 改址 / 取消都<b>没有 DELETE 形状</b>：改址单与取消单都是**凭据**
 * （V135 的不可改写触发器钉死），更正只能追加新的一条。
 *
 * <p>权限码<b>复用</b> {@code trade.fulfillment.read} / {@code trade.fulfillment.update}，
 * 不新起 {@code trade.waybill.*}：identity 只注册了既有的 fulfillment 页面资源权限码，
 * 用一个**没有被注册**的码会让连 OWNER 都 403（与两个兄弟同一判断）。
 */
@RestController
@RequestMapping("/trade/logistics/waybill-address-change")
public class WaybillRedirectController {

  private final AccessService access;
  private final WaybillRedirectService redirect;

  public WaybillRedirectController(AccessService access, WaybillRedirectService redirect) {
    this.access = access;
    this.redirect = redirect;
  }

  /** 统一入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：<b>逐条</b>说清哪个维度真能用、哪个没有上游。
   *
   * <p>本端点存在的理由是蓝图那句加粗话：「<b>超时的改址请求要明确告知能否成功而不是让客服猜</b>」。
   * 不提前说清「渠道未接通 ≠ 已超时限」「承运商侧已揽收本模块看不到」，
   * 界面上就会出现两种处境被画成同一种的样子。
   */
  @GetMapping("/capabilities")
  public WaybillRedirectCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "fulfillment.read", a -> redirect.capabilities());
  }

  /** 渠道规则清单（真实数据，不是写死的词表）。 */
  @GetMapping("/rules")
  public CarrierRedirectRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String carrierCode) {
    return call(auth, "fulfillment.read", a -> redirect.rules(carrierCode));
  }

  /**
   * 登记 / 更新一个承运商的改址与取消规则（**upsert**，一个承运商一条）。
   *
   * <p>「支持就必须登记时限、不支持就不能给时限」在服务层先给可读中文错误，
   * DB 层 CHECK 再钉一道 —— 绕过服务层写 SQL 同样被拒。
   */
  @PostMapping("/rules")
  public CarrierRedirectRuleRow upsertRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CarrierRedirectRuleInput input) {
    return call(auth, "fulfillment.update", a -> redirect.upsertRule(input, a.membershipId()));
  }

  /**
   * 改址清单。
   *
   * <p>汇总（{@code WaybillRedirectPage} 里那些计数）来自一条<b>不带 LIMIT/OFFSET</b>
   * 的独立查询，永远覆盖<b>筛选后的全量</b>。跟着分页走是列表页最常见的假活。
   *
   * @param dateFrom / dateTo 发起时刻闭开区间，绑 {@link OffsetDateTime}。
   *     刻意用带时区的类型而不是 {@code LocalDateTime}：后者绑 timestamptz 会
   *     <b>静默偏 −28800 秒</b>（把 UTC 当本地），不抛异常、只是时刻错了 8 小时。
   */
  @GetMapping("/redirects")
  public WaybillRedirectPage redirects(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String verdict,
      @RequestParam(required = false) String outcome,
      @RequestParam(required = false) String salesOrderNo,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime dateFrom,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime dateTo,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "fulfillment.read",
        a -> redirect.redirects(carrierCode, verdict, outcome, salesOrderNo, keyword, dateFrom,
            dateTo, sort, page, size));
  }

  /** 单条改址详情：单据 + 判定基准 + 承运商侧口径 + 联动单据 + 可执行动作。 */
  @GetMapping("/redirects/{id}")
  public WaybillRedirectDetail redirect(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "fulfillment.read", a -> redirect.redirect(id));
  }

  /**
   * 发起一次改址（落单 + 判定 + 派发 + 建买家通知任务）。
   *
   * <p>{@code requestedAt} 是**发起时刻 = 时限判定基准**（补录历史诉求时给真实时刻），
   * 不传则用服务端当前时刻。判定基准永远是**落库的出号时刻 + 登记的时限小时数**，
   * 所以同一张单据的结论任何时候重算都不变。
   */
  @PostMapping("/redirects/{id}/submit")
  public WaybillRedirectRow submitRedirect(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody WaybillRedirectInput input) {
    return call(auth, "fulfillment.update", a -> redirect.redirect(id, input, a.membershipId()));
  }

  /**
   * 批量预判「现在能不能改 / 能不能撤」（<b>不落库</b>）。
   *
   * <p>这是蓝图加粗句最直接的落地形态：客服手上有一堆买家在问改址的票，
   * 一次性问清哪些还能改、哪些已经没戏。{@code asOf} 让调用方能指定判定时刻，
   * 界面因此可以问「昨天这个时候还能改吗」。
   */
  @PostMapping("/precheck")
  public WaybillRedirectPrecheckResult precheck(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody WaybillRedirectPrecheckInput input,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime asOf) {
    return call(auth, "fulfillment.read", a -> redirect.precheck(input, asOf));
  }

  /** 取消清单。与改址同一形状。 */
  @GetMapping("/cancels")
  public WaybillCancelPage cancels(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String verdict,
      @RequestParam(required = false) String outcome,
      @RequestParam(required = false) String salesOrderNo,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime dateFrom,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime dateTo,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "fulfillment.read",
        a -> redirect.cancels(carrierCode, verdict, outcome, salesOrderNo, keyword, dateFrom,
            dateTo, sort, page, size));
  }

  /** 单条取消详情。 */
  @GetMapping("/cancels/{id}")
  public WaybillCancelDetail cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "fulfillment.read", a -> redirect.cancel(id));
  }

  /** 发起一次已出号运单的取消（落单 + 判定 + 派发 + 建买家通知任务）。 */
  @PostMapping("/cancels/{id}/submit")
  public WaybillCancelRow submitCancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody WaybillCancelInput input) {
    return call(auth, "fulfillment.update", a -> redirect.cancel(id, input, a.membershipId()));
  }

  /**
   * 买家通知任务清单（<b>只读</b>）。
   *
   * <p>刻意**没有**「发送 / 标记已送达」形状：本模块只建任务，发送与送达状态归 15
   * （crm 服务）。给一个假的「已送达」按钮，会让客服以为买家已经收到而不再跟进 ——
   * 那比没有通知任务更糟。
   */
  @GetMapping("/notifications")
  public BuyerNotificationTaskPage notifications(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String notificationKind,
      @RequestParam(required = false) String salesOrderNo,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "fulfillment.read",
        a -> redirect.notificationTasks(notificationKind, salesOrderNo, page, size));
  }
}
