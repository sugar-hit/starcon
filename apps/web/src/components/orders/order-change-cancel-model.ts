import type {
  OrderChangeCancelCancellationDetail,
  OrderChangeCancelProgressRow,
  OrderChangeCancelRulePage,
  OrderChangeCancelCapabilities,
  OrderChangeCancelChangeDetail,
  // 后端的分页包装是泛型 record（OrderChangeCancelPage<T>），SpringDoc 按实参
  // 展开成两个具体类型名，所以这里引的是展开后的名字而不是泛型本身。
  OrderChangeCancelPageOrderChangeCancelCancellationRow,
  OrderChangeCancelPageOrderChangeCancelChangeRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 9.1.1.3 订单变更与取消前端模型。
 *
 * <p>本模块的界面纪律只有一条，但它决定了这个页面的可信度：
 * <b>「还没接入」不许显示成 0</b>。
 *
 * <ul>
 *   <li>{@link quantityCell} 对拿不到的数量返回 <b>null</b>（界面显示「未接入」），
 *       而不是 0。8.3.1.2 已经为出库单的已拣量立过同一条规矩
 *       （逐行 pickedQuantity 恒 null 而不是 0），9.1.1.2 又为详情页的七个不可得分区
 *       立过一次 —— 三次都是因为同一个失败模式：<b>0 会被读成「确实没有」</b>。</li>
 *   <li>{@link cancelableCell} 的「可取消量」是本模块最要紧的一格：它是
 *       原量 − 已发量。已发量拿不到时整格显示「未接入」，
 *       让人去核对，<b>绝不显示 0</b> —— 显示 0 等于告诉运营「这件还没发，随便取消」，
 *       而那正是蓝图加粗禁止的事。</li>
 *   <li>{@link blockerText} 把「还差什么才能确认」逐条摊开：
 *       只给一个 {@code REQUESTED} 的话，操作人看不出卡在仓库还是平台。</li>
 * </ul>
 *
 * <p>路径前缀 {@code trade/sales-orders/change} —— 与 {@code /list}（9.1.1.1）、
 * {@code /detail}（9.1.1.2）同一纪律：裸 {@code /{id}} 已被 9.1.2.2 的手工订单详情占用，
 * 再加会让 Spring 在启动时抛 ambiguous mapping。
 */

const base = 'trade/sales-orders/change';

export function fetchCapabilities() {
  return api<OrderChangeCancelCapabilities>(`${base}/capabilities`);
}

export function fetchCancellation(id: string) {
  return api<OrderChangeCancelCancellationDetail>(
    `${base}/cancellations/${encodeURIComponent(id)}`,
  );
}

export function fetchChangeRequest(id: string) {
  return api<OrderChangeCancelChangeDetail>(`${base}/requests/${encodeURIComponent(id)}`);
}

export function fetchCancellations(params: {
  orderId?: string;
  status?: string;
  page?: number;
  size?: number;
}) {
  return api<OrderChangeCancelPageOrderChangeCancelCancellationRow>(
    `${base}/cancellations${query(params)}`,
  );
}

export function fetchChangeRequests(params: {
  orderId?: string;
  status?: string;
  page?: number;
  size?: number;
}) {
  return api<OrderChangeCancelPageOrderChangeCancelChangeRow>(`${base}/requests${query(params)}`);
}

export function fetchProgress(orderId: string) {
  return api<Array<OrderChangeCancelProgressRow>>(
    `${base}/fulfillment-progress?orderId=${encodeURIComponent(orderId)}`,
  );
}

export function fetchRules(params: { platformCode?: string; page?: number; size?: number }) {
  return api<OrderChangeCancelRulePage>(`${base}/platform-rules${query(params)}`);
}

function query(params: Record<string, string | number | undefined>) {
  const parts: string[] = [];
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '') continue;
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 展示口径

export const statusLabels: Record<string, string> = {
  REQUESTED: '已申请',
  PROCESSING: '处理中',
  CONFIRMED: '已确认',
  REJECTED: '已拒绝',
  PENDING_VERIFICATION: '待核验',
};

export const changeKindLabels: Record<string, string> = {
  ADDRESS_MODIFY: '修改地址',
  QUANTITY_INCREASE: '增加数量',
  QUANTITY_DECREASE: '减少数量',
  CANCEL: '取消订单',
};

export const cancelScopeLabels: Record<string, string> = {
  FULL_ORDER: '整单取消',
  PARTIAL_LINES: '按行取消',
};

export const holdTaskStageLabels: Record<string, string> = {
  STOP_WORK: '停止作业',
  RELEASE_OCCUPANCY: '释放占用',
};

export const holdTaskStatusLabels: Record<string, string> = {
  PENDING: '待处理',
  ACKNOWLEDGED: '仓库已受理',
  COMPLETED: '已完成',
  FAILED: '失败',
  REJECTED: '仓库拒绝',
};

export const writebackStatusLabels: Record<string, string> = {
  PENDING: '待回写',
  SUBMITTED: '已提交平台',
  CONFIRMED: '平台已接受',
  REJECTED: '平台拒绝',
  PENDING_VERIFICATION: '平台待核验',
};

/**
 * 数量格：拿不到就返回 <b>null</b>。
 *
 * <p>这个函数是本模块最重要的一行代码。把它写成 {@code value ?? 0} 看起来无害，
 * 但界面上「未发货数量 0」和「已发货数量未接入」会被读成同一件事，
 * 而它们的后果差着一条硬规则。
 */
export function quantityCell(
  value: number | string | null | undefined,
  projected: boolean,
): string {
  if (!projected) return '未接入';
  if (value === null || value === undefined) return '未接入';
  return trim(value);
}

export function trim(value: number | string): string {
  const n = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(n) ? String(n) : String(value);
}

/**
 * 可取消量一格。
 *
 * <p>{@code shippedQuantityVerified} 为 false（该单没有履约投影）时返回 null，
 * 界面显示「未接入，请人工核对」；**不返回 0**。
 */
export function cancelableCell(
  cancelable: number | string | null | undefined,
  shippedQuantityVerified: boolean,
): { value: string; verified: boolean } {
  if (!shippedQuantityVerified) return { value: '未接入，请人工核对已发数量', verified: false };
  if (cancelable === null || cancelable === undefined) return { value: '未接入', verified: false };
  return { value: trim(cancelable), verified: true };
}

/** 某一行是否因已发货而被整体挡住。 */
export function shipBlocking(line: { shipBlocking: boolean; cancelQuantity: number }) {
  return line.shipBlocking
    ? '该行已全部发货，可取消量为 0。已发货订单不能取消，只能转售后（11 售后未开发）'
    : '';
}

/** 金额带币种；BigDecimal 序列化成字符串，前端不做浮点运算。 */
export function money(amount: number | string | null | undefined, currency?: string) {
  if (amount === null || amount === undefined) return '—';
  return currency ? `${trim(amount)} ${currency}` : trim(amount);
}

/** 「还差什么才能确认」逐条摊开；空数组返回一句明确的话而不是空白。 */
export function blockerText(
  blockers: Array<{ description: string; ownerModule: string }> | undefined,
): string {
  if (!blockers || blockers.length === 0) return '没有阻塞项，可以推进到已确认';
  return blockers.map((b) => `${b.description}（归属 ${b.ownerModule}）`).join('；');
}

/** 数据完整性提示与阻塞项分开显示：提示不阻止确认，但必须让人看见。 */
export function warningText(
  warnings: Array<{ description: string; ownerModule: string }> | undefined,
): string {
  if (!warnings || warnings.length === 0) return '';
  return warnings.map((w) => `${w.description}（归属 ${w.ownerModule}）`).join('；');
}

/** 状态标签；未知状态原样透出而不是落成「未知」把真实枚举藏起来。 */
export function statusLabel(status: string): string {
  return statusLabels[status] ?? status;
}
