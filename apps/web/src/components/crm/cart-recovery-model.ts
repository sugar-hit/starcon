import type {
  CrmCartAbandonmentDetail,
  CrmCartAbandonmentInput,
  CrmCartAbandonmentPage,
  CrmCartCapabilities,
  CrmCartMessageInput,
  CrmCartMessagePage,
  CrmCartMessageRow,
  CrmCartPolicy,
  CrmCartPolicyInput,
  CrmCartRecoveryInput,
  CrmCartRecoveryRate,
  CrmCartShopProfileInput,
  CrmCartShopView,
  CrmCartSyncInput,
  CrmCartSyncResult,
  CrmCartTouchInput,
  CrmCartTouchResult,
  CrmCartTouchResultInput,
  CrmCartTouchRow,
  CrmCartUnsubscribeInput,
  CrmCartUnsubscribePage,
  CrmCartUnsubscribeRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 15.6.2.2 购物车与弃单挽回前端模型。
 *
 * <p>蓝图：「管理独立站购物车与弃单挽回：采集加购/结账未完成事件（Shopify 等提供 cart/checkout 事件），
 * 按超时策略触发挽回（邮件/短信/站内信），跟踪挽回率；平台订单不适用此项。」
 * 输出「弃单清单 + 挽回触达记录 + 挽回率报表」。
 * 关系：上游 = 2 渠道（独立站事件）、15.5.2 触达；下游 = 9 订单（挽回成交）、13 分析。
 * **此项是独立站(DTC)特有能力**，平台订单无购物车概念；触达须遵守退订与频次上限，
 * 挽回话术需人工确认后启用。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>弃单清单里只有独立站</b>：平台店铺的购物车事件在<b>登记入口</b>就被拒了，
 *       页面据此说明「平台订单无购物车概念」，不给「把平台订单也导进来」的口子。</li>
 *   <li><b>话术是状态机</b>：页面必须显示「草稿 / 已审核 / 已启用 / 已停用」四档，
 *       并且**只允许选已启用的话术**去登记触达（服务端还会再挡一道）。</li>
 *   <li><b>触达只登记不假装</b>：触达记录的状态是「已登记待发」——
 *       15.5.2.1 触达未交付，页面**不能**出现「已发送成功」这种说法。</li>
 *   <li><b>退订是硬拦</b>：登记退订之后这个联系人**一律不触达**，
 *       页面在触达入口旁要写清这条，免得运营以为退订只是「标记一下」。</li>
 *   <li><b>挽回率是服务端算的</b>：页面**原样展示**，<b>不做任何前端算术</b>；
 *       分母为 0 时后端给 null，页面显示「—」而不是 0%。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「筛选无结果」与「还没有弃单」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code crm/cart-recovery}，与同服务的 {@code crm/knowledge}、
 * {@code crm/buyer-emails}、{@code crm/review-requests}、{@code crm/review-rules} **刻意不共用**。
 */

const base = 'crm/cart-recovery';

/**
 * 弃单清单的筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface CartAbandonmentParams {
  shopId?: string | undefined;
  status?: string | undefined;
  stage?: string | undefined;
  buyerRef?: string | undefined;
  cartRef?: string | undefined;
  keyword?: string | undefined;
  abandonedFrom?: string | undefined;
  abandonedTo?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export function cartAbandonmentQuery(params: CartAbandonmentParams = {}): string {
  const parts: string[] = [];
  if (params.shopId) parts.push(`shopId=${encodeURIComponent(params.shopId)}`);
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.stage) parts.push(`stage=${encodeURIComponent(params.stage)}`);
  if (params.buyerRef) parts.push(`buyerRef=${encodeURIComponent(params.buyerRef)}`);
  if (params.cartRef) parts.push(`cartRef=${encodeURIComponent(params.cartRef)}`);
  if (params.keyword) parts.push(`keyword=${encodeURIComponent(params.keyword)}`);
  if (params.abandonedFrom)
    parts.push(`abandonedFrom=${encodeURIComponent(params.abandonedFrom)}`);
  if (params.abandonedTo) parts.push(`abandonedTo=${encodeURIComponent(params.abandonedTo)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 挽回话术列表的筛选条件。 */
export interface CartMessageParams {
  status?: string | undefined;
  channel?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export function cartMessageQuery(params: CartMessageParams = {}): string {
  const parts: string[] = [];
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.channel) parts.push(`channel=${encodeURIComponent(params.channel)}`);
  if (params.keyword) parts.push(`keyword=${encodeURIComponent(params.keyword)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 退订名单的筛选条件。 */
export interface CartUnsubscribeParams {
  contactType?: string | undefined;
  contactValue?: string | undefined;
  shopId?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export function cartUnsubscribeQuery(params: CartUnsubscribeParams = {}): string {
  const parts: string[] = [];
  if (params.contactType) parts.push(`contactType=${encodeURIComponent(params.contactType)}`);
  if (params.contactValue) parts.push(`contactValue=${encodeURIComponent(params.contactValue)}`);
  if (params.shopId) parts.push(`shopId=${encodeURIComponent(params.shopId)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 挽回率报表的筛选条件（只按店铺与时间窗）。 */
export interface CartRecoveryRateParams {
  shopId?: string | undefined;
  abandonedFrom?: string | undefined;
  abandonedTo?: string | undefined;
}

export function cartRecoveryRateQuery(params: CartRecoveryRateParams = {}): string {
  const parts: string[] = [];
  if (params.shopId) parts.push(`shopId=${encodeURIComponent(params.shopId)}`);
  if (params.abandonedFrom)
    parts.push(`abandonedFrom=${encodeURIComponent(params.abandonedFrom)}`);
  if (params.abandonedTo) parts.push(`abandonedTo=${encodeURIComponent(params.abandonedTo)}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。 */
export function fetchCartCapabilities() {
  return api<CrmCartCapabilities>(`${base}/capabilities`);
}

/** 店铺目录（来自上游 channel 的真实清单）+ 平台类型 + 购物车事件接入状态。 */
export function fetchCartShops() {
  return api<CrmCartShopView[]>(`${base}/shops`);
}

/** 声明店铺的平台类型（未知平台码才需要；已知平台码由系统判定，不可改）。 */
export function declareCartShopType(shopId: string, input: CrmCartShopProfileInput) {
  return api<CrmCartShopView>(`${base}/shops/${encodeURIComponent(shopId)}`, 'PUT', input);
}

/** 频次上限 + 弃单超时策略（没有配置行时后端给默认值）。 */
export function fetchCartPolicy() {
  return api<CrmCartPolicy>(`${base}/policy`);
}

/** 改频次上限 + 超时策略（幂等替换：一租户一行）。 */
export function updateCartPolicy(input: CrmCartPolicyInput) {
  return api<CrmCartPolicy>(`${base}/policy`, 'PUT', input);
}

/** 弃单清单（真分页 + 真筛选）。 */
export function fetchCartAbandonments(params: CartAbandonmentParams = {}) {
  return api<CrmCartAbandonmentPage>(`${base}/abandonments${cartAbandonmentQuery(params)}`);
}

/** 单条弃单详情 = 弃单本体 + 触达记录。 */
export function fetchCartAbandonment(cartId: string) {
  return api<CrmCartAbandonmentDetail>(`${base}/abandonments/${encodeURIComponent(cartId)}`);
}

/**
 * 登记一条弃单（人工登记入口）。
 *
 * <p>⚠️ 平台店铺的弃单一律 **409** 且不写任何数据：平台订单无购物车概念，
 * 混进弃单清单会让挽回率报表整体失真。页面据此提示，不给「平台店铺也能登记」的口子。
 */
export function createCartAbandonment(input: CrmCartAbandonmentInput) {
  return api<CrmCartAbandonmentDetail['abandonment']>(`${base}/abandonments`, 'POST', input);
}

/**
 * 登记一次挽回触达。
 *
 * <p>⚠️ 服务端会过四道闸：话术**已启用**、**到了挽回时点**、**没退订**、**没超频次上限**。
 * 任何一道没过都明确 409 并写明原因，**什么都不会写**。
 */
export function registerCartTouch(cartId: string, input: CrmCartTouchInput) {
  return api<CrmCartTouchResult>(
    `${base}/abandonments/${encodeURIComponent(cartId)}/touches`,
    'POST',
    input,
  );
}

/** 回填一次触达的结果（人工交接之后；15.5.2.1 触达未交付，本服务没有真实通道）。 */
export function confirmCartTouch(touchId: string, input: CrmCartTouchResultInput) {
  return api<CrmCartTouchRow>(
    `${base}/touches/${encodeURIComponent(touchId)}/result`,
    'POST',
    input,
  );
}

/** 登记一次挽回成交（9 订单未交付 ⇒ 订单号由运营人工登记）。 */
export function registerCartRecovery(cartId: string, input: CrmCartRecoveryInput) {
  return api<CrmCartAbandonmentDetail['abandonment']>(
    `${base}/abandonments/${encodeURIComponent(cartId)}/recoveries`,
    'POST',
    input,
  );
}

/** 挽回话术列表（真分页 + 真筛选）。 */
export function fetchCartMessages(params: CartMessageParams = {}) {
  return api<CrmCartMessagePage>(`${base}/messages${cartMessageQuery(params)}`);
}

/** 新建挽回话术（一律从草稿起步）。 */
export function createCartMessage(input: CrmCartMessageInput) {
  return api<CrmCartMessageRow>(`${base}/messages`, 'POST', input);
}

/** 单条话术详情。 */
export function fetchCartMessage(messageId: string) {
  return api<CrmCartMessageRow>(`${base}/messages/${encodeURIComponent(messageId)}`);
}

/** 改话术（改了内容服务端自动回到草稿、版本 +1）。 */
export function updateCartMessage(messageId: string, input: CrmCartMessageInput) {
  return api<CrmCartMessageRow>(
    `${base}/messages/${encodeURIComponent(messageId)}`,
    'PUT',
    input,
  );
}

/** 审核话术（草稿 → 已审核）。 */
export function approveCartMessage(messageId: string) {
  return api<CrmCartMessageRow>(
    `${base}/messages/${encodeURIComponent(messageId)}/approve`,
    'POST',
    {},
  );
}

/** 启用话术（已审核 → 已启用；只有启用了的话术能被触达流程选用）。 */
export function enableCartMessage(messageId: string) {
  return api<CrmCartMessageRow>(
    `${base}/messages/${encodeURIComponent(messageId)}/enable`,
    'POST',
    {},
  );
}

/** 停用话术。 */
export function disableCartMessage(messageId: string) {
  return api<CrmCartMessageRow>(
    `${base}/messages/${encodeURIComponent(messageId)}/disable`,
    'POST',
    {},
  );
}

/** 退订名单（真分页 + 真筛选）。 */
export function fetchCartUnsubscribes(params: CartUnsubscribeParams = {}) {
  return api<CrmCartUnsubscribePage>(`${base}/unsubscribes${cartUnsubscribeQuery(params)}`);
}

/** 登记一条退订（登记之后这个联系人一律不触达）。 */
export function createCartUnsubscribe(input: CrmCartUnsubscribeInput) {
  return api<CrmCartUnsubscribeRow>(`${base}/unsubscribes`, 'POST', input);
}

/** 取消退订。 */
export function deleteCartUnsubscribe(unsubscribeId: string) {
  return api<void>(`${base}/unsubscribes/${encodeURIComponent(unsubscribeId)}`, 'DELETE');
}

/** 挽回率报表（服务端真算，前端只原样展示）。 */
export function fetchCartRecoveryRate(params: CartRecoveryRateParams = {}) {
  return api<CrmCartRecoveryRate>(`${base}/recovery-rate${cartRecoveryRateQuery(params)}`);
}

/** 平台购物车 / 结账事件同步（当前恒为 409：适配器未交付）。 */
export function syncCartEvents(input: CrmCartSyncInput) {
  return api<CrmCartSyncResult>(`${base}/sync`, 'POST', input);
}

// ================================================================ 可读形态

/** 平台类型的可读名。 */
export function cartPlatformTypeLabel(platformType: string): string {
  switch (platformType) {
    case 'DTC':
      return '独立站';
    case 'MARKETPLACE':
      return '平台店铺';
    case 'UNDECLARED':
      return '未声明';
    default:
      return platformType;
  }
}

/** 弃单阶段的可读名。 */
export function cartStageLabel(stage: string): string {
  switch (stage) {
    case 'CART':
      return '只加购';
    case 'CHECKOUT':
      return '进了结账';
    default:
      return stage;
  }
}

/** 弃单状态的可读名。 */
export function cartAbandonmentStatusLabel(status: string): string {
  switch (status) {
    case 'ABANDONED':
      return '待挽回';
    case 'RECOVERED':
      return '已挽回成交';
    case 'EXPIRED':
      return '已过期（未挽回）';
    default:
      return status;
  }
}

/**
 * 话术状态的可读名。
 *
 * <p>🔴 「已审核」与「已启用」是**两件事**：「已审核」只说明有人看过，
 * 「已启用」才说明它能被触达流程选用。混成一个词会让人以为「审核过就能发」。
 */
export function cartMessageStatusLabel(status: string): string {
  switch (status) {
    case 'DRAFT':
      return '草稿（未审核）';
    case 'APPROVED':
      return '已审核（未启用）';
    case 'ENABLED':
      return '已启用（可被选用）';
    case 'DISABLED':
      return '已停用';
    default:
      return status;
  }
}

/** 触达通道的可读名。 */
export function cartChannelLabel(channel: string): string {
  switch (channel) {
    case 'EMAIL':
      return '邮件';
    case 'SMS':
      return '短信';
    case 'IN_APP':
      return '站内信';
    default:
      return channel;
  }
}

/**
 * 触达记录状态的可读名。
 *
 * <p>🔴 「已登记待发」与「已发出」必须分开：15.5.2.1 触达未交付，
 * 登记不等于发出 —— 画成「已发送」会让运营以为买家已经收到挽回邮件。
 */
export function cartTouchStatusLabel(status: string): string {
  switch (status) {
    case 'REGISTERED':
      return '已登记待发';
    case 'SENT':
      return '已发出（人工交接后回填）';
    case 'FAILED':
      return '触达失败';
    default:
      return status;
  }
}

/** 触达运输方式的可读名。 */
export function cartTransportLabel(transport: string): string {
  return transport === 'MANUAL_HANDOFF' ? '人工交接' : transport;
}

/**
 * 这条弃单**挽回到哪一步了**。
 *
 * <p>🔴 四态必须分清：「还没触达」/「已登记待发」/「已发出」/「触达失败」——
 * 混成一句「操作失败」会让人重复触达，而重复打扰买家的代价正是退订与投诉。
 */
export function cartRecoveryStateLabel(row: {
  status: string;
  touchCount: number;
  recoveredOrderNo: string;
}): string {
  if (row.status === 'RECOVERED') return `已挽回成交（订单号 ${row.recoveredOrderNo}）`;
  if (row.touchCount === 0) return '还没触达';
  return '已触达（结果见触达记录）';
}

/**
 * 挽回率的可读形态。
 *
 * <p>🔴 分母为 0 时后端给 {@code null} ⇒ 这里显示「—」而不是 0%：
 * 「没有弃单」和「弃单都没挽回回来」是**两件完全不同的事**，
 * 显示 0% 会把前者说成后者。
 */
export function cartRecoveryRateText(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) return '—';
  return `${rate}%`;
}

/**
 * 金额 / 数量的可读形态。
 *
 * <p>⚠️ **只做格式化，不做任何前端算术**：金额与数量由后端算好（numeric(19,6)），
 * 页面原样展示；一旦前端参与计算，浮点误差就会进到报表里。
 */
export function cartAmountText(amount: number | null | undefined, currency?: string): string {
  if (amount === null || amount === undefined) return '—';
  return currency ? `${currency} ${amount}` : String(amount);
}
