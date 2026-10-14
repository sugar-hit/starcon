import type {
  ReservationAllocationBucketOption,
  ReservationAllocationCreateInput,
  ReservationAllocationCreated,
  ReservationAllocationOption,
  ReservationAllocationOptions,
  ReservationAllocationPage,
  ReservationAllocationTimeline,
  ReservationAllocationTransitionInput,
  ReservationAllocationTransitioned,
  ReservationCapability,
} from '../../../../../packages/api-contracts/src/index';
import { api } from './api';

/**
 * 7.2.1.2 预占分配明细前端模型。
 *
 * <p>蓝图：「展示一个预占需求实际分配到了哪些 bucket（哪个仓、哪个货位、哪个批次），以及每个分配的
 * held→终态迁移记录。」输出「分配明细行 + 状态迁移时间线」。
 *
 * <p>这个页面上最容易把事实读错的三处，都在这里显式处理：
 *
 * <ul>
 *   <li><b>「未分配量」与「剩余量」分两行、不合并</b>。未分配量 = 需求量 − 已分配量
 *       （还欠多少没圈到 bucket），剩余量 = 需求量 − 已消费量（还欠多少没出库）。
 *       两个问题合成一个数字，就再也答不出「货到底出没出」了。
 *   <li><b>时间线里建行那一条的来源状态显示「新建」，不是 HELD</b>。
 *       「此前无状态」与「此前就是 HELD」在审计上不是一回事；显示成 HELD 会让人以为
 *       这一笔是从另一个 HELD 状态迁过来的。
 *   <li><b>bucket 三个维度缺失时显示「—」，不拼成空字符串</b>。虚拟仓没有货位、
 *       非批次管理品没有批次，这两种都是合法状态，不是数据缺失。
 * </ul>
 */

/** 集合级基址与 7.2.1.1 共用：明细端点挂在同一条预占路径的下一层。 */
export const RESERVATION_ALLOCATION_BASE = 'warehouse/inventory/reservations';

/** 明细行查询条件。与后端 `ReservationAllocationQuery` 一一对应。 */
export interface ReservationAllocationParams {
  status?: string[];
  warehouseId?: string;
  locationId?: string;
  lotId?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

/** 时间线查询条件。与后端 `ReservationAllocationTimelineQuery` 一一对应。 */
export interface ReservationAllocationTimelineParams {
  allocationId?: string;
  transition?: string;
  page?: number;
  size?: number;
}

function queryString(parts: string[]): string {
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 分配明细行 + 预占抬头 + 分配汇总。 */
export function fetchReservationAllocations(
  reservationId: string,
  params: ReservationAllocationParams = {},
) {
  const parts: string[] = [];
  for (const s of params.status ?? []) parts.push(`status=${encodeURIComponent(s)}`);
  if (params.warehouseId) parts.push(`warehouseId=${encodeURIComponent(params.warehouseId)}`);
  if (params.locationId) parts.push(`locationId=${encodeURIComponent(params.locationId)}`);
  if (params.lotId) parts.push(`lotId=${encodeURIComponent(params.lotId)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return api<ReservationAllocationPage>(
    `${RESERVATION_ALLOCATION_BASE}/${reservationId}/allocations${queryString(parts)}`,
  );
}

/** 状态迁移时间线。 */
export function fetchReservationAllocationTimeline(
  reservationId: string,
  params: ReservationAllocationTimelineParams = {},
) {
  const parts: string[] = [];
  if (params.allocationId) parts.push(`allocationId=${encodeURIComponent(params.allocationId)}`);
  if (params.transition) parts.push(`transition=${encodeURIComponent(params.transition)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return api<ReservationAllocationTimeline>(
    `${RESERVATION_ALLOCATION_BASE}/${reservationId}/timeline${queryString(parts)}`,
  );
}

/**
 * 下拉项。仓 / 货位 / 批次的候选值**只取这笔预占实际出现过的**，不是全库 ——
 * 明细页的筛选器问的是「这笔预占圈过哪些地方」。
 */
export function fetchReservationAllocationOptions(reservationId: string) {
  return api<ReservationAllocationOptions>(
    `${RESERVATION_ALLOCATION_BASE}/${reservationId}/allocation-options`,
  );
}

/** 链路各环节的接入情况。释放与过期会报 available=false（归 7.2.1.3）。 */
export function fetchReservationAllocationCapabilities(reservationId: string) {
  return api<ReservationCapability[]>(
    `${RESERVATION_ALLOCATION_BASE}/${reservationId}/capabilities`,
  );
}

/**
 * 登记分配入参（前端可省字段版）。
 *
 * <p>codegen 把标了 `@Schema(nullable = true)` 的字段生成为 `T | null` 而不是可选字段，
 * 所以直接传对象字面量必须把每个可空字段都写成 `null`（漏一个就报 TS2345）。
 * 这里收 `Partial` 并统一补 null，让调用方写 `{ warehouseId, allocatedQuantity }` 就够了 ——
 * 「这个字段没填」和「这个字段填了 null」在 HTTP 上本来就是同一件事。
 */
export type ReservationAllocationCreateRequest = Partial<ReservationAllocationCreateInput> & {
  warehouseId: string;
  allocatedQuantity: number;
};

/** 状态迁放入参（前端可省字段版）。理由同 {@link ReservationAllocationCreateRequest}。 */
export type ReservationAllocationTransitionRequest = Partial<ReservationAllocationTransitionInput>;

function fill<T extends object>(input: T): T {
  const out = { ...input } as Record<string, unknown>;
  for (const [key, value] of Object.entries(out)) {
    if (value === undefined) out[key] = null;
  }
  return out as T;
}

/** 登记分配：把这笔预占圈到一个（仓 / 货位 / 批次）上。 */
export function createReservationAllocation(
  reservationId: string,
  input: ReservationAllocationCreateRequest,
) {
  return api<ReservationAllocationCreated>(
    `${RESERVATION_ALLOCATION_BASE}/${reservationId}/allocations`,
    'POST',
    fill(input),
  );
}

/**
 * 承诺分配：HELD → COMMITTED。
 *
 * <p>承诺不带数量：承诺的是「这一整笔分配」，不是其中一部分 ——
 * 部分承诺会让「哪几件已承诺」这个问题在界面上无处可问。
 */
export function commitReservationAllocation(
  reservationId: string,
  allocationId: string,
  input: ReservationAllocationTransitionRequest = {},
) {
  return api<ReservationAllocationTransitioned>(
    `${RESERVATION_ALLOCATION_BASE}/${reservationId}/allocations/${allocationId}/commit`,
    'POST',
    fill(input),
  );
}

/**
 * 消耗出库：COMMITTED → CONSUMED，支持部分出库。
 *
 * <p>不传 quantity 即一次性出完该分配的剩余量（后端按剩余量裁决）。
 */
export function consumeReservationAllocation(
  reservationId: string,
  allocationId: string,
  input: ReservationAllocationTransitionRequest = {},
) {
  return api<ReservationAllocationTransitioned>(
    `${RESERVATION_ALLOCATION_BASE}/${reservationId}/allocations/${allocationId}/consume`,
    'POST',
    fill(input),
  );
}

// ================================================================ 展示口径

/** 分配状态标签。缺失时退回状态码本身，绝不渲染成空白格。 */
export function allocationStatusLabel(status: string | undefined, label?: string): string {
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

/** 迁移类型标签。 */
export function allocationTransitionLabel(
  transition: string | undefined,
  label?: string,
): string {
  if (label) return label;
  switch (transition) {
    case 'ALLOCATED':
      return '登记分配';
    case 'COMMITTED':
      return '承诺分配';
    case 'CONSUMED':
      return '消耗出库';
    case 'RELEASED':
      return '已释放';
    case 'EXPIRED':
      return '已过期';
    default:
      return transition ?? '未知迁移';
  }
}

/** 终态判定的前端镜像。后端（V107 触发器）才是真正拦住复活的那道，这里只负责上色。 */
export function isTerminalAllocation(status: string | undefined): boolean {
  return status === 'CONSUMED' || status === 'RELEASED' || status === 'EXPIRED';
}

/** 分录层级标签。预占级迁移（allocationId 为空）与分配级在时间线上混排，必须说清来源。 */
export function allocationLevelLabel(level: string | undefined): string {
  return level === 'RESERVATION' ? '预占级' : '分配级';
}

/**
 * bucket 三维拼成一行可读文本。
 *
 * <p>缺哪一维就少一段，仓恒在（建表时 warehouse_id NOT NULL）。
 * 三段都缺是不可能的，但真发生时返回「—」而不是空串 ——
 * 空白格会被读成「数据没加载出来」，而「—」读起来就是「没有」。
 */
export function bucketText(
  warehouseCode?: string | null,
  locationCode?: string | null,
  lotCode?: string | null,
): string {
  const parts = [warehouseCode, locationCode, lotCode].filter((p): p is string => !!p);
  return parts.length ? parts.join(' / ') : '—';
}

/**
 * 迁移文案的来源状态。
 *
 * <p>`null` → 「新建」。**不给 HELD**：建行那一条此前根本没有状态，
 * 显示成 HELD 会让人以为它是从另一个 HELD 迁过来的。
 */
export function entryFromLabel(fromStatus: string | null | undefined): string {
  if (fromStatus === null || fromStatus === undefined || fromStatus === '') return '新建';
  return allocationStatusLabel(fromStatus);
}

/** 下拉候选（静态兜底；接口不可用时页面仍能给出正确的筛选值）。 */
export const RESERVATION_ALLOCATION_STATUS_OPTIONS: ReservationAllocationOption[] = [
  { code: 'HELD', label: '预占中', terminal: false, description: '仍在占额' },
  { code: 'COMMITTED', label: '已承诺', terminal: false, description: '仍在占额' },
  { code: 'CONSUMED', label: '已消耗', terminal: true, description: '终态，不可再迁出' },
  { code: 'RELEASED', label: '已释放', terminal: true, description: '终态，不可再迁出' },
  { code: 'EXPIRED', label: '已过期', terminal: true, description: '终态，不可再迁出' },
];

/**
 * 可触发迁移的静态兜底。
 *
 * <p>刻意**不含**释放与过期：那属 7.2.1.3。在本模块的下拉里列出它，
 * 等于给前端一个可以点、点了什么也不发生的按钮。
 */
export const RESERVATION_ALLOCATION_TRANSITION_OPTIONS: ReservationAllocationOption[] = [
  { code: 'ALLOCATE', label: '登记分配', terminal: false, description: null },
  { code: 'COMMIT', label: '承诺分配', terminal: false, description: null },
  { code: 'CONSUME', label: '消耗出库', terminal: false, description: null },
];

export const RESERVATION_ALLOCATION_SORT_OPTIONS = [
  { code: 'ALLOCATED_AT', label: '按圈定时间' },
  { code: 'STATUS', label: '按状态' },
  { code: 'QUANTITY', label: '按分配量' },
  { code: 'REMAINING', label: '按剩余量' },
  { code: 'BUCKET', label: '按 bucket' },
];

/** 空 bucket 下拉项（接口还没回来时渲染占位，不渲染空白格）。 */
export const NO_BUCKET_OPTIONS: ReservationAllocationBucketOption[] = [];

/** 未分配量口径说明。常驻显示，否则「已分配 12 / 未分配 8 / 剩余量 20」会被当成算错。 */
export const UNALLOCATED_NOTE =
  '未分配量 = 需求量 − 已分配量（还欠多少没圈到 bucket）；剩余量 = 需求量 − 已消费量（还欠多少没出库）。两者是两个问题，不合并。已分配但未出库的那部分货还在仓里占着可用量。';

/** 归属说明：为什么三张表都落在 warehouse 而不是蓝图写的 trade。 */
export const ALLOCATION_OWNER_NOTE =
  '预占需求、分配明细、迁移流水三张表同属库存服务（warehouse），不跨服务读写订单侧的表；仓 / 货位 / 批次只登记本服务引用登记表里的 id 与快照。';

/** 时间口径说明。与 7.2.1.1 同一条：时间一律带时区偏移。 */
export const ALLOCATION_TIMEZONE_NOTE =
  '时间一律带时区偏移：库里是 timestamptz，界面按浏览器本地时区显示，服务端不做二次时区转换。';
