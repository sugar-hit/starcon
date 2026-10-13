import type {
  ReservationCapability,
  ReservationCreateInput,
  ReservationCreated,
  ReservationOptions,
  ReservationPage,
  StatusOption,
} from '../../../../../packages/api-contracts/src/index';
import { api } from './api';

/**
 * 7.2.1.1 预占需求列表前端模型。
 *
 * <p>这个页面上最容易把数字读错的三处，都在这里显式处理：
 *
 * <ul>
 *   <li><b>超时长为 null 显示「—」，绝不显示 0</b>。null 的含义是「没超时或不再计时」，
 *       0 的含义是「超时 0 秒」—— 两者在界面上完全不同，写成 0 会让人以为刚建立就超时了。
 *   <li><b>已分配量为 0 时标注「7.2.1.2 未交付」</b>，而不是显示一个看起来正常的 0。
 *       分配明细表还不存在，显示 0 会被读成「这批货还没有分配到任何货位」，
 *       实际上「有没有分配」这件事目前根本查不到。
 *   <li><b>剩余量 = 需求量 − 已消费量，不含已分配未出库</b>。界面上写明这条口径，
 *       否则「已分配 12、剩余 20」会被当成算错了。
 * </ul>
 */

/** 列表查询条件。与后端 `ReservationQuery` 一一对应。 */
export interface ReservationParams {
  status?: string[];
  sourceType?: string;
  sourceSystem?: string;
  requestedFrom?: string;
  requestedTo?: string;
  overdueOnly?: boolean;
  legalEntityId?: string;
  skuId?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export const RESERVATION_BASE = 'warehouse/inventory/reservations';

/** 预占需求列表。 */
export function fetchReservations(params: ReservationParams = {}) {
  const parts: string[] = [];
  for (const s of params.status ?? []) parts.push(`status=${encodeURIComponent(s)}`);
  if (params.sourceType) parts.push(`sourceType=${encodeURIComponent(params.sourceType)}`);
  if (params.sourceSystem) parts.push(`sourceSystem=${encodeURIComponent(params.sourceSystem)}`);
  if (params.requestedFrom) parts.push(`requestedFrom=${encodeURIComponent(params.requestedFrom)}`);
  if (params.requestedTo) parts.push(`requestedTo=${encodeURIComponent(params.requestedTo)}`);
  if (params.overdueOnly) parts.push('overdueOnly=true');
  if (params.legalEntityId) parts.push(`legalEntityId=${encodeURIComponent(params.legalEntityId)}`);
  if (params.skuId) parts.push(`skuId=${encodeURIComponent(params.skuId)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return api<ReservationPage>(`${RESERVATION_BASE}${parts.length ? `?${parts.join('&')}` : ''}`);
}

/** 筛选下拉项与口径说明。 */
export function fetchReservationOptions() {
  return api<ReservationOptions>(`${RESERVATION_BASE}/options`);
}

/** 链路各环节接入情况。7.2.1.2 / 7.2.1.3 未交付时显式报 available=false。 */
export function fetchReservationCapabilities() {
  return api<ReservationCapability[]>(`${RESERVATION_BASE}/capabilities`);
}

/** 登记预占需求（蓝图上游跨服务调本服务；运营侧「其他占用」也走这里）。 */
export function createReservation(input: ReservationCreateInput) {
  return api<ReservationCreated>(RESERVATION_BASE, 'POST', input);
}

// ================================================================ 展示口径

/** 状态标签。缺失时退回状态码本身，绝不渲染成空白格。 */
export function reservationStatusLabel(status: string | undefined, label?: string): string {
  if (label) return label;
  switch (status) {
    case 'HELD':
      return '预占中';
    case 'COMMITTED':
      return '已承诺';
    case 'CONSUMED':
      return '已消耗';
    case 'RELEASED':
      return '已释放';
    case 'EXPIRED':
      return '已过期';
    default:
      return status ?? '未知状态';
  }
}

/** 来源类型标签。 */
export function reservationSourceTypeLabel(type: string | undefined, label?: string): string {
  if (label) return label;
  switch (type) {
    case 'SALES_ORDER':
      return '销售订单';
    case 'PROCESSING_ORDER':
      return '加工领料';
    case 'OTHER_HOLD':
      return '其他占用';
    default:
      return type ?? '未知来源';
  }
}

/** 来源系统标签。 */
export function reservationSourceSystemLabel(system: string | undefined, label?: string): string {
  if (label) return label;
  switch (system) {
    case 'TRADE':
      return '订单履约';
    case 'PROCESSING':
      return '加工领料';
    case 'MANUAL':
      return '其他占用';
    default:
      return system ?? '未知来源';
  }
}

/**
 * 终态判定的前端镜像。
 *
 * <p>为什么在前端也判一次：行上有 `terminal` 字段，但汇总卡上的五个状态只有 code，
 * 要给它们上「终态」的分色就得在前端知道哪个是终态。后端仍是唯一权威
 * （V102 的触发器才是真正拦住复活的那道），这里只负责上色。
 */
export function isTerminalStatus(status: string | undefined): boolean {
  return status === 'CONSUMED' || status === 'RELEASED' || status === 'EXPIRED';
}

/**
 * 超时长文案。
 *
 * <p>`null` → 「—」。这是本模型最要紧的一条：**null 与 0 不是同一件事**。
 * null 表示「没有过期时间、已经终态、或还没超时」，0 表示「刚好超时 0 秒」。
 * 写死成 '0 秒' 会让每一条未超时的预占都显示「超时 0 秒」，
 * 用户会以为这批预占马上要被释放。
 */
export function overdueText(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return '—';
  if (seconds < 0) return '—';
  const total = Math.floor(seconds);
  const days = Math.floor(total / 86400);
  const hours = Math.floor((total % 86400) / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  if (days > 0) return `${days} 天 ${hours} 小时`;
  if (hours > 0) return `${hours} 小时 ${minutes} 分`;
  if (minutes > 0) return `${minutes} 分钟`;
  return `${total} 秒`;
}

/** 超时是否达到「该看一眼」的量级。60 分钟是界面上给的红线，不是业务规则。 */
export function overdueIsSevere(seconds: number | null | undefined): boolean {
  return seconds !== null && seconds !== undefined && seconds >= 3600;
}

/**
 * 已分配量的展示口径。
 *
 * <p>7.2.1.2 未交付时该值恒 0。直接显示 `0` 会被读成「这批货还没分配到任何货位」，
 * 而实际是「有没有分配这件事目前查不到」——所以如实说明。
 */
export function allocatedText(quantity: number | null | undefined): string {
  if (quantity === null || quantity === undefined) return '—';
  return quantity === 0 ? '0（分配明细未交付）' : String(quantity);
}

/** 剩余量口径说明。常驻显示，否则有人会把「已分配 12 / 剩余 20」当成算错。 */
export const REMAINING_NOTE =
  '剩余量 = 需求量 − 已消费量，不含「已分配但未出库」的部分：那部分货还在仓里占着可用量，算进来会让列表显示成已结清。';

/** 归属说明：为什么预占表落在 warehouse 而不是蓝图写的 trade。 */
export const OWNER_NOTE =
  '预占需求表按 V1 文件头预留的 warehouse 归属落在库存服务（蓝图关系句写 trade，但该表在任何服务里都不存在，且 trade 侧无预占表可查）。';

/** 下拉用的状态选项（静态兜底；接口不可用时页面仍能给出正确的筛选值）。 */
export const RESERVATION_STATUS_OPTIONS: StatusOption[] = [
  { code: 'HELD', label: '预占中', terminal: false },
  { code: 'COMMITTED', label: '已承诺', terminal: false },
  { code: 'CONSUMED', label: '已消耗', terminal: true },
  { code: 'RELEASED', label: '已释放', terminal: true },
  { code: 'EXPIRED', label: '已过期', terminal: true },
];

export const RESERVATION_SORT_OPTIONS = [
  { code: 'REQUESTED_AT', label: '按提出时间' },
  { code: 'EXPIRES_AT', label: '按过期时间' },
  { code: 'STATUS', label: '按状态' },
  { code: 'QUANTITY', label: '按需求量' },
  { code: 'REMAINING', label: '按剩余量' },
  { code: 'RESERVATION_NO', label: '按预占单号' },
];

export const RESERVATION_SOURCE_TYPE_OPTIONS = [
  { code: 'SALES_ORDER', label: '销售订单' },
  { code: 'PROCESSING_ORDER', label: '加工领料' },
  { code: 'OTHER_HOLD', label: '其他占用' },
];

export const RESERVATION_SOURCE_SYSTEM_OPTIONS = [
  { code: 'TRADE', label: '订单履约' },
  { code: 'PROCESSING', label: '加工领料' },
  { code: 'MANUAL', label: '其他占用' },
];

/**
 * 汇总合计的格式化。
 *
 * <p>合计**不受分页与筛选影响**（后端口径），所以界面上要写明这一点 ——
 * 否则用户切了个筛选条件发现总量没变，会以为筛选没生效。
 */
export function summaryHint(): string {
  return '合计为全租户预占总量，不随筛选与分页变化。';
}

/**
 * 读五态分布里的某一态计数。
 *
 * <p>为什么需要这个函数而不是直接 `summary.counts[code]`：codegen 把
 * `Map<String, Long>` 序列化成 `{}`（Jackson 对 Map 的表示是对象，但 TS 侧
 * 只能给出一个无索引签名的空对象类型）。直接索引会在
 * `noUncheckedIndexedAccess` 下报 TS7053，所以这里显式收窄成
 * `Record<string, number | undefined>` 再取。
 *
 * <p>缺失时返回 0 而不是 undefined：五态固定显示，某个态没有数据时界面上
 * 应该写「0 条」，不是空白格 —— 后者会被读成「这个状态不存在」。
 */
export function countOf(summary: { counts?: unknown } | null | undefined, code: string): number {
  if (!summary) return 0;
  const counts = summary.counts as Record<string, number | undefined> | undefined;
  return counts?.[code] ?? 0;
}
