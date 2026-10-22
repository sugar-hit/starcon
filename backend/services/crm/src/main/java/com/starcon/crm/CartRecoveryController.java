package com.starcon.crm;

import static com.starcon.crm.CartRecoveryModels.*;

import com.starcon.crm.CartRecoveryModels.CrmCartAbandonmentDetail;
import com.starcon.crm.CartRecoveryModels.CrmCartAbandonmentInput;
import com.starcon.crm.CartRecoveryModels.CrmCartAbandonmentPage;
import com.starcon.crm.CartRecoveryModels.CrmCartAbandonmentRow;
import com.starcon.crm.CartRecoveryModels.CrmCartCapabilities;
import com.starcon.crm.CartRecoveryModels.CrmCartMessageInput;
import com.starcon.crm.CartRecoveryModels.CrmCartMessagePage;
import com.starcon.crm.CartRecoveryModels.CrmCartMessageRow;
import com.starcon.crm.CartRecoveryModels.CrmCartPolicy;
import com.starcon.crm.CartRecoveryModels.CrmCartPolicyInput;
import com.starcon.crm.CartRecoveryModels.CrmCartRecoveryInput;
import com.starcon.crm.CartRecoveryModels.CrmCartRecoveryRate;
import com.starcon.crm.CartRecoveryModels.CrmCartShopProfileInput;
import com.starcon.crm.CartRecoveryModels.CrmCartShopView;
import com.starcon.crm.CartRecoveryModels.CrmCartSyncInput;
import com.starcon.crm.CartRecoveryModels.CrmCartSyncResult;
import com.starcon.crm.CartRecoveryModels.CrmCartTouchInput;
import com.starcon.crm.CartRecoveryModels.CrmCartTouchResult;
import com.starcon.crm.CartRecoveryModels.CrmCartTouchResultInput;
import com.starcon.crm.CartRecoveryModels.CrmCartTouchRow;
import com.starcon.crm.CartRecoveryModels.CrmCartUnsubscribeInput;
import com.starcon.crm.CartRecoveryModels.CrmCartUnsubscribePage;
import com.starcon.crm.CartRecoveryModels.CrmCartUnsubscribeRow;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
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

/**
 * 15.6.2.2 购物车与弃单挽回。
 *
 * <p>命名空间 {@code /crm/cart-recovery}：前端入口同名（页面 {@code /crm/cart-recovery}），
 * 浏览器只经 {@code /api/crm} 代理访问；后端路径按<b>服务</b>命名空间走，
 * 与 catalog（/catalog/...）、trade（/trade/...）同一纪律。
 *
 * <p>⚠️ 与同服务的 {@code /crm/buyer-emails}（15.1.2.1 买家邮件）、
 * {@code /crm/review-requests}（15.2.2.1 索评）、{@code /crm/review-rules}（15.2.2.2 索评规则）、
 * {@code /crm/knowledge}（15.4.2.1 客服知识库）**刻意不共用命名空间**：
 * 索评管「要不要向这位买家索评」、知识库管「这个格子该答哪一条」、
 * 邮件管「这封邮件是什么，我们回过没有」，本模块管「独立站这位买家加购 / 结账没走完，
 * 要不要挽回、挽回回来了没有」。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/crm/cart-recovery")
public class CartRecoveryController {

  private final AccessService access;
  private final CartRecoveryService cartRecovery;

  public CartRecoveryController(AccessService access, CartRecoveryService cartRecovery) {
    this.access = access;
    this.cartRecovery = cartRecovery;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "crm." + permission);
    return access.run(actor, "crm." + permission, () -> work.apply(actor));
  }

  /** 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。 */
  @GetMapping("/capabilities")
  public CrmCartCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "cart.read", a -> cartRecovery.capabilities());
  }

  /**
   * 租户级频次上限 + 弃单超时策略。
   *
   * <p>没有配置行时给默认值（{@code customized=false}）——
   * 「第一次用这个模块」不需要先建策略。
   */
  @GetMapping("/policy")
  public CrmCartPolicy policy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "cart.read", a -> cartRecovery.policy());
  }

  /** 改频次上限 + 超时策略（幂等替换：一租户一行）。 */
  @PutMapping("/policy")
  public CrmCartPolicy updatePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmCartPolicyInput input) {
    return call(auth, "cart.update", a -> cartRecovery.updatePolicy(input));
  }

  /**
   * 店铺目录（来自上游 channel 的真实清单）+ 平台类型 + 购物车事件接入状态。
   *
   * <p>上游读不到一律 503 —— **绝不返回空集合冒充「没有店铺」**。
   */
  @GetMapping("/shops")
  public List<CrmCartShopView> shops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "cart.read", a -> cartRecovery.shops(auth));
  }

  /**
   * 声明店铺的平台类型。
   *
   * <p>🔴 已知平台码（Amazon / eBay / TikTok Shop …）**不可**声明成独立站 ——
   * 那会让平台订单混进弃单清单。
   */
  @PutMapping("/shops/{shopId}")
  public CrmCartShopView declareShopType(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID shopId,
      @Valid @RequestBody CrmCartShopProfileInput input) {
    return call(
        auth,
        "cart.update",
        a -> cartRecovery.declareShopType(shopId, input, a.userId(), auth));
  }

  /** 弃单清单（真分页 + 真筛选）。 */
  @GetMapping("/abandonments")
  public CrmCartAbandonmentPage abandonments(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String stage,
      @RequestParam(required = false) String buyerRef,
      @RequestParam(required = false) String cartRef,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String abandonedFrom,
      @RequestParam(required = false) String abandonedTo,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "cart.read",
        a ->
            cartRecovery.abandonmentPage(
                shopId,
                status,
                stage,
                buyerRef,
                cartRef,
                keyword,
                abandonedFrom,
                abandonedTo,
                page,
                size));
  }

  /**
   * 登记一条弃单（人工登记入口）。
   *
   * <p>🔴 平台店铺的弃单一律 <b>409</b> 且<b>不写任何数据</b>：
   * 平台订单无购物车概念，混进来会让挽回率报表整体失真。
   */
  @PostMapping("/abandonments")
  public CrmCartAbandonmentRow createAbandonment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmCartAbandonmentInput input) {
    return call(auth, "cart.update", a -> cartRecovery.create(input, a.userId(), auth));
  }

  /** 单条弃单详情 = 弃单本体 + 触达记录。 */
  @GetMapping("/abandonments/{cartId}")
  public CrmCartAbandonmentDetail abandonment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID cartId) {
    return call(auth, "cart.read", a -> cartRecovery.detail(cartId));
  }

  /** 一条弃单的触达记录（时间线）。 */
  @GetMapping("/abandonments/{cartId}/touches")
  public List<CrmCartTouchRow> touches(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID cartId) {
    return call(auth, "cart.read", a -> cartRecovery.detail(cartId).touches());
  }

  /**
   * 登记一次挽回触达。
   *
   * <p>🔴 四道闸全在服务端强制：话术必须**已启用**、必须**到了挽回时点**、
   * **没退订**、**没超频次上限**。任何一道没过都明确 <b>409</b> 并写明原因，
   * <b>什么都不会写</b>。
   */
  @PostMapping("/abandonments/{cartId}/touches")
  public CrmCartTouchResult registerTouch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID cartId,
      @Valid @RequestBody CrmCartTouchInput input) {
    return call(auth, "cart.update", a -> cartRecovery.registerTouch(cartId, input, a.userId()));
  }

  /**
   * 登记一次挽回成交。
   *
   * <p>🔴 9 订单未交付 ⇒ 订单号由运营**人工登记**，本模块不编造下游 id。
   */
  @PostMapping("/abandonments/{cartId}/recoveries")
  public CrmCartAbandonmentRow registerRecovery(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID cartId,
      @Valid @RequestBody CrmCartRecoveryInput input) {
    return call(auth, "cart.update", a -> cartRecovery.registerRecovery(cartId, input));
  }

  /**
   * 回填一次触达的结果（人工交接之后）。
   *
   * <p>🔴 15.5.2.1 触达未交付 ⇒ 本服务没有真实通道，这条端点是「运营在别处发出之后
   * 回来登记结果」，不是「让本服务去发」。
   */
  @PostMapping("/touches/{touchId}/result")
  public CrmCartTouchRow confirmTouch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID touchId,
      @Valid @RequestBody CrmCartTouchResultInput input) {
    return call(auth, "cart.update", a -> cartRecovery.confirmTouch(touchId, input));
  }

  /** 挽回话术列表（真分页 + 真筛选）。 */
  @GetMapping("/messages")
  public CrmCartMessagePage messages(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String channel,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "cart.read", a -> cartRecovery.messagePage(status, channel, keyword, page, size));
  }

  /** 新建挽回话术（一律从**草稿**起步）。 */
  @PostMapping("/messages")
  public CrmCartMessageRow createMessage(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmCartMessageInput input) {
    return call(auth, "cart.update", a -> cartRecovery.createMessage(input, a.userId()));
  }

  /** 单条话术详情。 */
  @GetMapping("/messages/{messageId}")
  public CrmCartMessageRow message(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID messageId) {
    return call(auth, "cart.read", a -> cartRecovery.message(messageId));
  }

  /**
   * 改话术。
   *
   * <p>🔴 <b>改了内容就回到草稿</b>（DB 触发器统一负责）：版本 +1、审核痕迹清空 ——
   * 否则会出现「审核的是 A 版、发出去的是 B 版」。
   */
  @PutMapping("/messages/{messageId}")
  public CrmCartMessageRow updateMessage(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID messageId,
      @Valid @RequestBody CrmCartMessageInput input) {
    return call(
        auth, "cart.update", a -> cartRecovery.updateMessage(messageId, input, a.userId()));
  }

  /** 审核话术（草稿 → 已审核；留痕记谁、何时、哪个版本）。 */
  @PostMapping("/messages/{messageId}/approve")
  public CrmCartMessageRow approveMessage(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID messageId) {
    return call(auth, "cart.update", a -> cartRecovery.approveMessage(messageId, a.userId()));
  }

  /** 启用话术（已审核 → 已启用；只有启用了的话术能被触达流程选用）。 */
  @PostMapping("/messages/{messageId}/enable")
  public CrmCartMessageRow enableMessage(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID messageId) {
    return call(auth, "cart.update", a -> cartRecovery.enableMessage(messageId, a.userId()));
  }

  /** 停用话术（已启用 / 已审核 → 已停用）。 */
  @PostMapping("/messages/{messageId}/disable")
  public CrmCartMessageRow disableMessage(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID messageId) {
    return call(auth, "cart.update", a -> cartRecovery.disableMessage(messageId, a.userId()));
  }

  /** 退订名单（真分页 + 真筛选）。 */
  @GetMapping("/unsubscribes")
  public CrmCartUnsubscribePage unsubscribes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String contactType,
      @RequestParam(required = false) String contactValue,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "cart.read",
        a -> cartRecovery.unsubscribePage(contactType, contactValue, shopId, page, size));
  }

  /** 登记一条退订（登记之后这个联系人**一律不触达**）。 */
  @PostMapping("/unsubscribes")
  public CrmCartUnsubscribeRow createUnsubscribe(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmCartUnsubscribeInput input) {
    return call(auth, "cart.update", a -> cartRecovery.createUnsubscribe(input, a.userId()));
  }

  /** 取消退订。 */
  @DeleteMapping("/unsubscribes/{unsubscribeId}")
  public void deleteUnsubscribe(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID unsubscribeId) {
    call(
        auth,
        "cart.update",
        a -> {
          cartRecovery.deleteUnsubscribe(unsubscribeId);
          return null;
        });
  }

  /** 挽回率报表（**服务端真算**）。 */
  @GetMapping("/recovery-rate")
  public CrmCartRecoveryRate recoveryRate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String abandonedFrom,
      @RequestParam(required = false) String abandonedTo) {
    return call(
        auth, "cart.read", a -> cartRecovery.recoveryRate(shopId, abandonedFrom, abandonedTo));
  }

  /**
   * 平台购物车 / 结账事件同步。
   *
   * <p>🔴 当前**恒为 409** 且**不写任何数据**：平台购物车事件适配器未交付
   * （channel/vendor 只有 OAuth 适配器）。未接入是业务状态，不是故障 ——
   * 返回空列表会让运营以为这个店铺没有购物车。
   */
  @PostMapping("/sync")
  public CrmCartSyncResult sync(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmCartSyncInput input) {
    return call(auth, "cart.update", a -> cartRecovery.sync(input, auth));
  }
}
