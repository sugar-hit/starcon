import type {
  PutawayCapabilities,
  PutawayTargetStat,
  PutawayTaskAssignInput,
  PutawayTaskCancelInput,
  PutawayTaskCompleteInput,
  PutawayTaskCreateInput,
  PutawayTaskCreated,
  PutawayTaskDetail,
  PutawayTaskPage,
  PutawayTaskRow,
  PutawayTaskScanInput,
  PutawayTaskScanned,
  PutawayTaskStartInput,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 8.2.2.1 上架任务前端模型（收货暂存位 → 正式库位）。
 *
 * <p>蓝图 08 卷：「把已收货物从收货暂存位上架到正式库位，生成上架任务派发给作业人员
 * （支持 PDA/扫码）。」输出「上架任务（待执行/执行中/完成）；库存 bucket 位置变更。」
 * 关系段的加粗句是整个模块的技术内容：「<b>上架只改位置不改数量</b>，数量在收货时已确定。」
 *
 * <p><b>界面纪律</b>（与后端一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>建单表单里没有「数量」输入框</b> —— 蓝图那句话最直接的界面形态。
 *       要上架哪一行、要搬到哪由人指；搬多少取自那条收货明细行的实收量。
 *       给操作员一个数量输入框，等于邀请他改一个收货时就已经定死的事实。</li>
 *   <li><b>应上架量与实上架量必须并排显示</b>：两列相等才允许点「完成上架」，
 *       页面自己也算一遍 P2，不合格就禁用按钮并说清楚差几件。</li>
 *   <li><b>净额常驻</b>：详情页把 {@code netOnHandDelta} 摆在扫码台账上方。
 *       它在任意时刻都必须是 0（一次扫码写的是一对反向分录）；
 *       页面明说「净额为 0 不代表任务做完了」，免得有人拿它当完成标志。</li>
 *   <li><b>扫码是可重复的</b>：一张单可以扫多次，每次填「本次搬多少」。
 *       累计超量在提交前就被本地挡下（超了就是凭空把货搬出库外）。</li>
 *   <li><b>执行是不可逆的</b>：完成与取消是终态；已扫码的任务不能取消
 *       （货已经在往目标库位搬了）。按钮在非法态禁用并说明原因。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code warehouse/putaway-tasks}，刻意不是 {@code goods-receipts} 的子路径 ——
 * 上架任务有派工、有扫码、有执行中状态，挂在收货单底下会让「还没上完架」
 * 与「还没收完货」在契约上分不清。
 */

const base = 'warehouse/putaway-tasks';

export interface PutawayTaskParams {
  status?: string;
  warehouseId?: string;
  receiptId?: string;
  assigneeId?: string;
  skuId?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export function putawayTaskQuery(params: PutawayTaskParams): string {
  const parts: string[] = [];
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.warehouseId) parts.push(`warehouseId=${encodeURIComponent(params.warehouseId)}`);
  if (params.receiptId) parts.push(`receiptId=${encodeURIComponent(params.receiptId)}`);
  if (params.assigneeId) parts.push(`assigneeId=${encodeURIComponent(params.assigneeId)}`);
  if (params.skuId) parts.push(`skuId=${encodeURIComponent(params.skuId)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchPutawayCapabilities() {
  return api<PutawayCapabilities>(`${base}/capabilities`);
}

export function fetchPutawayTasks(params: PutawayTaskParams) {
  return api<PutawayTaskPage>(`${base}${putawayTaskQuery(params)}`);
}

export function fetchPutawayTask(taskId: string) {
  return api<PutawayTaskDetail>(`${base}/${encodeURIComponent(taskId)}`);
}

export function createPutawayTask(input: PutawayTaskCreateInput) {
  return api<PutawayTaskCreated>(base, 'POST', input);
}

/** 派工：把任务指给一位作业人员（不动库存，可改派）。 */
export function assignPutawayTask(taskId: string, input: PutawayTaskAssignInput) {
  return api<PutawayTaskRow>(`${base}/${encodeURIComponent(taskId)}/assign`, 'POST', input);
}

/** 开始作业：待执行 → 执行中（不动库存，只把路由字段锁死）。 */
export function startPutawayTask(taskId: string, note?: string) {
  return api<PutawayTaskRow>(`${base}/${encodeURIComponent(taskId)}/start`, 'POST', {
    note: note ?? null,
  });
}

/**
 * PDA 扫码上架 —— 本模块**唯一动库存**的动作。
 *
 * <p>一次扫码在同一条余额行上写一对反向分录（源位 -Q、目标位 +Q），
 * 所以 {@code quantity} 是<b>本次</b>搬的量而不是「最终要搬的量」：一张单可能扫三次。
 * {@code scanReference} 是幂等锚点 —— PDA 网络抖动重发是现场常态，
 * 没有它，同一批货会被搬两遍<b>而「净额仍为 0」抓不到</b>。
 */
export function scanPutawayTask(taskId: string, input: PutawayTaskScanInput) {
  return api<PutawayTaskScanned>(`${base}/${encodeURIComponent(taskId)}/scan`, 'POST', input);
}

/** 完成上架：数据库回读账本核对 P1/P2/P3。 */
export function completePutawayTask(taskId: string, note?: string) {
  return api<PutawayTaskRow>(`${base}/${encodeURIComponent(taskId)}/complete`, 'POST', {
    note: note ?? null,
  });
}

/** 取消（**不动库存**；已扫码的任务后端会拒）。 */
export function cancelPutawayTask(taskId: string, input: PutawayTaskCancelInput) {
  return api<PutawayTaskRow>(`${base}/${encodeURIComponent(taskId)}/cancel`, 'POST', input);
}

export type { PutawayTargetStat, PutawayTaskAssignInput, PutawayTaskCancelInput, PutawayTaskScanInput, PutawayTaskStartInput, PutawayTaskCompleteInput };

// ================================================================ 展示口径

/** 上架任务状态标签（与后端 V109 的 CHECK 词表逐字对应）。 */
export const putawayStatusLabels: Record<string, string> = {
  PENDING: '待执行',
  IN_PROGRESS: '执行中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
};

/**
 * 质量状态标签：与 V1 既有 inventory_quality_status.role 词表逐字对应。
 * 「残次」这一档是本模块的硬规则主角（残次品只能进残次库位）。
 */
export const qualityRoleLabels: Record<string, string> = {
  SELLABLE: '可售',
  PENDING_INSPECTION: '待检',
  DEFECTIVE: '残次',
};

/** 库位类型标签：与 V2 的 warehouse_location.type 六值词表逐字对应。 */
export const locationTypeLabels: Record<string, string> = {
  STORAGE: '存储',
  RECEIVING: '收货暂存',
  SHIPPING: '发货暂存',
  QUARANTINE: '待检',
  DEFECTIVE: '残次',
  FROZEN: '冻结',
};

/** 扫码设备标签。PDA 是蓝图点名的现场形态，DESKTOP 用于后台补录。 */
export const deviceTypeLabels: Record<string, string> = {
  PDA: 'PDA',
  MOBILE: '手机',
  DESKTOP: '后台补录',
};

export function putawayStatusLabel(status: string): string {
  return putawayStatusLabels[status] ?? status;
}

export function qualityRoleLabel(role: string): string {
  return qualityRoleLabels[role] ?? role;
}

export function locationTypeLabel(type: string): string {
  return locationTypeLabels[type] ?? type;
}

export function deviceTypeLabel(device: string): string {
  return deviceTypeLabels[device] ?? device;
}

/**
 * 状态徽章的视觉分级。
 *
 * <p>四态**故意各不相同**，因为它们对库存的意义本来就不同：
 * 待执行是「货还在收货暂存位」、执行中是「正在搬」、已完成是「货在正式库位上了」、
 * 已取消是「本来就没搬过」。把两个终态涂成同一种颜色会让人分不清
 * 「这批货到底动过没有」—— 而这恰恰是看这张单的人最想一眼知道的事。
 */
export function putawayStatusTone(status: string): string {
  switch (status) {
    case 'PENDING':
      return 'bg-sky-500/15 text-sky-300 ring-sky-500/30';
    case 'IN_PROGRESS':
      return 'bg-amber-500/15 text-amber-300 ring-amber-500/30';
    case 'COMPLETED':
      return 'bg-emerald-500/15 text-emerald-300 ring-emerald-500/30';
    default:
      return 'bg-white/5 text-white/60 ring-white/15';
  }
}

/** 质量徽章：可售绿、待检琥珀、残次红 —— 三档必须一眼分得开。 */
export function qualityRoleTone(role: string): string {
  switch (role) {
    case 'SELLABLE':
      return 'bg-emerald-500/15 text-emerald-300 ring-emerald-500/30';
    case 'PENDING_INSPECTION':
      return 'bg-amber-500/15 text-amber-300 ring-amber-500/30';
    case 'DEFECTIVE':
      return 'bg-red-500/15 text-red-300 ring-red-500/30';
    default:
      return 'bg-white/5 text-white/60 ring-white/15';
  }
}

/** 分录方向标签：负向分录是「从收货暂存位拿走」，正向分录是「放进正式库位」。 */
export function entryDirectionLabel(direction: string): string {
  return direction === 'SOURCE_OUT' ? '源位出库' : '目标位入库';
}

export const putawaySortLabels: Record<string, string> = {
  CREATED_AT: '建单时间',
  TASK_CODE: '单号',
  PLANNED_QUANTITY: '应上架量',
  STATUS: '状态',
  COMPLETED_AT: '完成时间',
};

export const putawaySortKeys = Object.keys(putawaySortLabels);

/**
 * 不变量在页面上的判定（**只读不改**，后端已在数据库层强制，这里给操作员一个当场可读的确认）。
 *
 * <p>P2：完成时每行实上架量必须恰好等于应上架量。
 * 返回 false 时页面把「完成上架」按钮禁用并说清差多少 ——
 * 「基本搬完了」不是完成，货去哪了说不清。
 */
export function allLinesFinished(lines: { plannedQuantity: number; putawayQuantity: number }[]): {
  ok: boolean;
  unfinished: number;
  shortfall: number;
} {
  let unfinished = 0;
  let shortfall = 0;
  for (const line of lines) {
    const remaining = Number(line.plannedQuantity) - Number(line.putawayQuantity);
    if (Math.abs(remaining) > 1e-9) {
      unfinished += 1;
      shortfall += remaining;
    }
  }
  return { ok: unfinished === 0, unfinished, shortfall };
}

/**
 * 账上净额是否仍然为 0。
 *
 * <p><b>注意它不是完成标志</b>：一次扫码写的就是一对反向分录，成对的两条加起来恒为 0，
 * 所以任务没做完时净额同样是 0。它暴露的是另一种错 ——
 * 有人往这条上架交易里塞了一条不成对的分录。
 */
export function netIsBalanced(net: number | string | null | undefined): boolean {
  if (net === null || net === undefined || net === '') return true;
  const n = typeof net === 'number' ? net : Number(net);
  return Number.isFinite(n) && Math.abs(n) < 1e-9;
}

/** 能否点「开始作业」：只有待执行态。 */
export function canStart(row: { status: string }): boolean {
  return row.status === 'PENDING';
}

/** 能否点「扫码」：待执行或执行中（第一次扫码会自动把任务推进到执行中）。 */
export function canScan(row: { status: string }): boolean {
  return row.status === 'PENDING' || row.status === 'IN_PROGRESS';
}

/**
 * 能否点「完成上架」。
 *
 * <p>必须是执行中<b>且</b>每一行都搬完了（P2）。搬没搬完由前端先算一遍 ——
 * 不是为了替代后端判定，而是省掉一次注定被拒的往返。
 */
export function canComplete(row: { status: string }, lines: { plannedQuantity: number; putawayQuantity: number }[]): boolean {
  return row.status === 'IN_PROGRESS' && allLinesFinished(lines).ok;
}

/**
 * 能否点「取消」。
 *
 * <p>已扫码（{@code scanCount > 0}）就不能取消：货已经在往目标库位搬了，
 * 取消等于宣布「这批货还在收货暂存位」，而库里它已经不在那儿了。
 */
export function canCancel(row: { status: string; scanCount: number }): boolean {
  return (row.status === 'PENDING' || row.status === 'IN_PROGRESS') && Number(row.scanCount) === 0;
}

/** 本次扫码量提交前的本地校验：正数、不超累计、且能被库存刻度（4 位小数）精确表示。 */
export function validateScanQuantity(
  value: string,
  alreadyPutaway: number,
  planned: number,
): { ok: boolean; reason?: string } {
  if (!value.trim()) return { ok: false, reason: '请填写本次搬动的数量' };
  const n = Number(value);
  if (!Number.isFinite(n) || n <= 0) return { ok: false, reason: '本次搬动量必须大于 0' };
  if (Math.abs(n * 10000 - Math.round(n * 10000)) > 1e-6) {
    return {
      ok: false,
      reason:
        '本次搬动量有超过 4 位小数，无法在库存刻度上精确表示：向下取整等于凭空吞货、向上取整等于凭空多搬',
    };
  }
  const after = Number(alreadyPutaway) + n;
  if (after - Number(planned) > 1e-9) {
    return {
      ok: false,
      reason: `累计将达到 ${after}，超过应上架量 ${planned}：上架只改位置不改数量，超量上架等于凭空把货搬出库外`,
    };
  }
  return { ok: true };
}

/** 数量格式化：最多 6 位小数，去掉尾随 0（后端发的是 string/number，这里只做展示）。 */
export function formatQuantity(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = typeof value === 'number' ? value : Number(value);
  if (Number.isNaN(n)) return String(value);
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
}

/** 百分比：入参为空时返回「未评估」而不是 0%（与后端 evaluated 标志同一口径）。 */
export function formatRatio(
  value: number | string | null | undefined,
  evaluated: boolean,
): string {
  if (!evaluated || value === null || value === undefined || value === '') return '未评估';
  const n = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(n) || n === 0) return '未评估';
  return `${n.toLocaleString('zh-CN', { maximumFractionDigits: 4 })}`;
}