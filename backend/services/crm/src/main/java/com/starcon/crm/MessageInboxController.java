package com.starcon.crm;

import static com.starcon.crm.MessageInboxModels.*;

import com.starcon.crm.MessageInboxModels.CrmMessageBatchInput;
import com.starcon.crm.MessageInboxModels.CrmMessageBatchResult;
import com.starcon.crm.MessageInboxModels.CrmMessageCapabilities;
import com.starcon.crm.MessageInboxModels.CrmMessageCreateInput;
import com.starcon.crm.MessageInboxModels.CrmMessageDetail;
import com.starcon.crm.MessageInboxModels.CrmMessageOptions;
import com.starcon.crm.MessageInboxModels.CrmMessagePage;
import com.starcon.crm.MessageInboxModels.CrmMessageRow;
import com.starcon.crm.MessageInboxModels.CrmMessageShopView;
import com.starcon.crm.MessageInboxModels.CrmMessageSyncAttempt;
import com.starcon.crm.MessageInboxModels.CrmMessageSyncInput;
import com.starcon.crm.PlatformMessagePort.PlatformMessageAvailability;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 15.1.1.1 消息收件箱。
 *
 * <p>命名空间 {@code /crm/message-inbox}：前端入口同名（页面 {@code /crm/messages}）。
 * 消息集合挂在 {@code /crm/message-inbox/messages}（读列表 / 登记 / 单条详情），
 * 目录与动作挂在收件箱命名空间下（{@code /shops} {@code /options} {@code /platforms}
 * {@code /capabilities} {@code /batch} {@code /sync}）——
 * **裸路径 {@code /crm/message-inbox} 刻意不是一个端点**，所以白名单里不放行它。
 * 浏览器只经 {@code /api/crm} 代理访问；后端路径按<b>服务</b>命名空间走，
 * 与 catalog（/catalog/...）、trade（/trade/...）同一纪律。
 *
 * <p>⚠️ 与同服务的 {@code /crm/review-requests}（15.2.2.1 索评）、
 * {@code /crm/review-rules}（15.2.2.2）、{@code /crm/knowledge}（15.4.2.1）
 * **刻意不共用命名空间**：本模块管**收到的消息**，那三个分别管**触达**与**内容**。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code crm.message.read} / {@code crm.message.update}：
 * 与同服务兄弟模块（{@code crm.review.*} / {@code crm.rule.*} / {@code crm.knowledge.*}）
 * 同一命名法，identity 服务**零改动**。
 */
@RestController
@RequestMapping("/crm/message-inbox")
public class MessageInboxController {

  private final AccessService access;
  private final MessageInboxService messages;

  public MessageInboxController(AccessService access, MessageInboxService messages) {
    this.access = access;
    this.messages = messages;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "crm.message." + permission);
    return access.run(actor, "crm.message." + permission, () -> work.apply(actor));
  }

  /** 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。 */
  @GetMapping("/capabilities")
  public CrmMessageCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> messages.capabilities());
  }

  /** 店铺目录（来自上游 channel 的真实清单，不是前端写死的常量）。 */
  @GetMapping("/shops")
  public List<CrmMessageShopView> shops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> messages.shops(auth));
  }

  /** 筛选词表：平台与店铺来自**本租户真实数据**，其余是 DB 层允许的取值域。 */
  @GetMapping("/options")
  public CrmMessageOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> messages.options());
  }

  /** 逐平台的站内信接入状态（平台清单来自真实店铺目录）。 */
  @GetMapping("/platforms")
  public List<PlatformMessageAvailability> platforms(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> messages.platformAvailability(auth));
  }

  /** 消息列表（真分页 + 真筛选 + 与分页无关的全量汇总）。 */
  @GetMapping("/messages")
  public CrmMessagePage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String messageType,
      @RequestParam(required = false) String priority,
      @RequestParam(required = false) String direction,
      @RequestParam(required = false) String readStatus,
      @RequestParam(required = false) String buyerReference,
      @RequestParam(required = false) String orderReference,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String platformTimeFrom,
      @RequestParam(required = false) String platformTimeTo,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        a ->
            messages.list(
                shopId,
                platformCode,
                messageType,
                priority,
                direction,
                readStatus,
                buyerReference,
                orderReference,
                keyword,
                platformTimeFrom,
                platformTimeTo,
                page,
                size));
  }

  /**
   * 单条消息详情 = 消息本体 + 附件 + 关联订单上下文 + 处置意图。
   *
   * <p>🔴 订单上下文查不到时返回 {@code available=false} + 原因，
   * **不返回空白**（空白会被客服读成「这一单没有信息」）。
   */
  @GetMapping("/messages/{messageId}")
  public CrmMessageDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID messageId) {
    return call(auth, "read", a -> messages.detail(messageId, auth));
  }

  /**
   * 登记一条平台消息 / 买家消息（平台同步未接入时的**人工入口**）。
   *
   * <p>🔴 买家标识与订单标识**必填**（蓝图加粗句）。
   */
  @PostMapping("/messages")
  public CrmMessageRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmMessageCreateInput input) {
    return call(auth, "update", a -> messages.create(input, a.userId(), auth));
  }

  /**
   * 批量处理：已读 / 未读 / 归档 / 改优先级 / 登记处置意图 / 撤销处置意图。
   *
   * <p>逐条给出结论与原因；失败的条目不会被悄悄跳过。
   */
  @PostMapping("/batch")
  public CrmMessageBatchResult batch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmMessageBatchInput input) {
    return call(auth, "update", a -> messages.batch(input, a.userId(), auth));
  }

  /**
   * 平台消息同步入口。
   *
   * <p>⚠️ 当前**逐平台未接入** ⇒ 返回 **409** 且**一条数据都不写**。
   */
  @PostMapping("/sync")
  public CrmMessageSyncAttempt sync(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmMessageSyncInput input) {
    return call(auth, "update", a -> messages.sync(input, auth));
  }
}
