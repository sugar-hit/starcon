import type {
  AllocationFloorInput,
  AllocationFloorPage,
  AllocationFloorRow,
  AllocationFloorSaved,
  PriorityAllocationRequest,
  PriorityAllocationResult,
  PriorityCapabilities,
  ResolutionPage,
  ResolutionRow,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 7.8.1.2 优先级与保底前端模型。
 *
 * <p>蓝图：「在分摊冲突时按优先级裁决，并为高价值店铺设置保底可用量与安全垫，防止低优先级店铺长期断货影响其店铺
 * 绩效与平台考核。输出：分摊结果（含被压低店铺的提示）；保底生效记录。」
 *
 * <p>界面上最容易出错的几处，这个模块都显式处理了：
 *
 * <ul>
 *   <li>**守恒式常驻显示，且多一段**。除了 7.8.1.1 的两条，还要常驻
 *       「保底池 + 余量池 = 可分配量」—— 保底是在分摊**之前**先发的，
 *       它的总量如果超过可分配量，被挤掉的余量就是负数，而那正是超卖。
 *   <li>**「被压低」必须同时显示基线**。只显示最终分摊额的话，运营看到的是
 *       「这家店今天只有 3 件」，但答不出「它是被谁挤下去的」。
 *       所以每一行都带 {@code baseAllocatedQty}，界面要把它和最终值并排显示。
 *   <li>**保底没兑现要与「本来就不够格」长得不一样**。所以保底差额与原因
 *       （池子被分完 / 成员停用 / 可用量为 0）必须占版面，而不是折进分摊额里。
 *   <li>**未纳入的上游写在页面上**。店铺绩效（17 域）未交付，
 *       所以保底的依据只有人定的数量/比例/安全垫 —— 这一点常驻提示，
 *       不让人以为系统是按绩效自动算的底线。
 *   <li>**没有生效策略时显示「拒绝」**。保底是分摊之上的裁决，
 *       没有分摊就没有保底可言，不做「默认均分保底」这种凭空处理。
 * </ul>
 */

// ------------------------------------------------------------ 接口

export interface FloorListParams {
  policyId?: string;
  referenceId?: string;
  effectiveOnly?: boolean;
  keyword?: string;
  page?: number;
  size?: number;
}

export function fetchPriorityCapabilities(): Promise<PriorityCapabilities> {
  return api<PriorityCapabilities>('warehouse/inventory/allocation-priority/capabilities');
}

export function fetchFloors(params: FloorListParams): Promise<AllocationFloorPage> {
  return api<AllocationFloorPage>(`warehouse/inventory/allocation-priority/floors${query(params)}`);
}

export function fetchFloor(id: string): Promise<AllocationFloorRow> {
  return api<AllocationFloorRow>(`warehouse/inventory/allocation-priority/floors/${id}`);
}

export function createFloor(input: AllocationFloorInput): Promise<AllocationFloorSaved> {
  return api<AllocationFloorSaved>('warehouse/inventory/allocation-priority/floors', 'POST', input);
}

/** 换版 = 收口旧档 + 开新档。没有「就地改」的入口。 */
export function supersedeFloor(
  id: string,
  input: AllocationFloorInput,
): Promise<AllocationFloorSaved> {
  return api<AllocationFloorSaved>(
    `warehouse/inventory/allocation-priority/floors/${id}/versions`,
    'POST',
    input,
  );
}

export function retireFloor(id: string): Promise<AllocationFloorSaved> {
  return api<AllocationFloorSaved>(
    `warehouse/inventory/allocation-priority/floors/${id}/retire`,
    'POST',
    {},
  );
}

// ------------------------------------------------------------ 分摊

/**
 * 带保底的分摊试算。POST：请求体放不进 URL（与 7.8.1.1 的 /calculation 同一理由）。
 *
 * <p>`availableQuantity` **必填**：本模块不重复实现 7.2.2.1 的池容量口径，
 * 请传 7.2.2.1 已经算出的那个数。
 */
export function previewPriorityAllocation(input: {
  skuId: string;
  availableQuantity: number;
  policyId?: string;
}): Promise<PriorityAllocationResult[]> {
  return api<PriorityAllocationResult[]>(
    'warehouse/inventory/allocation-priority/calculation',
    'POST',
    input,
  );
}

/** 带保底的分摊并落库。走**另一个端点**而不是靠布尔切换：落库写的是不可改的批次，要 update 权限。 */
export function runPriorityAllocation(input: {
  skuId: string;
  availableQuantity: number;
  policyId?: string;
}): Promise<PriorityAllocationResult[]> {
  return api<PriorityAllocationResult[]>(
    'warehouse/inventory/allocation-priority/runs',
    'POST',
    input,
  );
}

/**
 * 保底生效记录。
 *
 * <p>`shortfallOnly` 与 `suppressedOnly` 是**两个不同的病**，刻意不合并成一个「异常」开关：
 * 前者是「承诺了但没兑现」（该补货 / 该调保底），后者是「没轮到你而货被别人拿走了」
 * （该调优先级 / 该调保底）。
 */
export function fetchResolutions(params: {
  runId?: string;
  referenceId?: string;
  shortfallOnly?: boolean;
  suppressedOnly?: boolean;
  page?: number;
  size?: number;
}): Promise<ResolutionPage> {
  return api<ResolutionPage>(`warehouse/inventory/allocation-priority/resolutions${query(params)}`);
}

function query(params: object): string {
  const parts = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ------------------------------------------------------------ 展示

/**
 * codegen 约定：只要 schema 里有任何一个可空字段，整个类型别名就会生成成 `T | null`，
 * 所以列表元素也要先过这道关。
 *
 * <p>刻意写成**类型谓词**：写成 `<T>(v: T | null) => T | null` 时
 * `Array.filter(notNull)` 不会收窄元素类型，map 里每个字段访问都要再补一次判空。
 */
export function notNull<T>(v: T | null | undefined): v is T {
  return v !== null && v !== undefined;
}

/** 数量口径：DB 是 numeric(19,6)，而**件数必须是整件**，所以这里不补小数位。 */
export function qty(v: number | string | null | undefined): string {
  if (v === null || v === undefined) return '—';
  const n = typeof v === 'string' ? Number(v) : v;
  if (Number.isNaN(n)) return '—';
  return n.toLocaleString('zh-CN');
}

/** 比例：库里存 0.2，界面显示 20%。 */
export function percent(v: number | string | null | undefined): string {
  if (v === null || v === undefined) return '—';
  const n = typeof v === 'string' ? Number(v) : v;
  if (Number.isNaN(n)) return '—';
  return `${(n * 100).toLocaleString('zh-CN', { maximumFractionDigits: 4 })}%`;
}

/**
 * 保底口径的文字。
 *
 * <p>刻意写成「数字 + 口径」而不是只给数字：保底 6 件与保底 6% 是两件完全不同的事，
 * 而它们在库里都只是一个数字。
 */
export function floorText(line: {
  floorMode: string | null;
  floorQuantity: number | string | null;
  floorRatio: number | string | null;
  safetyQuantity: number | string | null;
}): string {
  const base =
    line.floorMode === 'RATIO'
      ? `可分配量的 ${percent(line.floorRatio)}`
      : `${qty(line.floorQuantity)} 件`;
  const safety = Number(line.safetyQuantity ?? 0);
  return safety > 0 ? `${base} + 安全垫 ${qty(safetyQuantity(line))} 件` : base;
}

function safetyQuantity(line: { safetyQuantity: number | string | null }): number | string | null {
  return line.safetyQuantity;
}

/** 保底口径标签。 */
export const floorModeLabels: Record<string, string> = {
  QUANTITY: '按件数保底',
  RATIO: '按可分配量比例保底',
};

/**
 * 保底兑现状态。
 *
 * <p>枚举词表与后端 {@code FloorStatus} 一一对应；后端用 @Schema 出的是字符串，
 * 所以这里按字符串收，用 Record 而不是联合类型 —— 联合类型在契约变更时会先炸在 typecheck，
 * 而这三种状态在界面上都能给出可读文案，不需要靠类型系统兜底。
 */
export const floorStatusLabels: Record<string, string> = {
  FULFILLED: '足额兑现',
  PARTIAL: '部分兑现',
  UNFULFILLED: '未兑现',
};

/** 保底没给到的原因。枚举而非自由文本 —— 处置动作完全不同。 */
export const shortfallReasonLabels: Record<string, string> = {
  POOL_EXHAUSTED: '保底池被前面的店分完',
  MEMBER_DISABLED: '成员已停用，不参与分摊',
  NO_STOCK: '可用量不足以兑现任何保底',
  NOT_A_MEMBER: '已不在该策略的成员名册里',
};

/** 被压低的原因。`RESERVED_FOR_FLOOR` 是本模块最该被看见的一个值。 */
export const suppressionReasonLabels: Record<string, string> = {
  RESERVED_FOR_FLOOR: '被别家店的保底占用了货',
  POOL_EXHAUSTED: '货被排在它前面的店铺分完',
  MEMBER_DISABLED: '成员已停用',
  NO_SALES: '登记销量为 0',
};

export const operatingModeLabels: Record<string, string> = {
  SELF_FULFILLED: '自营',
  FULL_CONSIGNMENT: '全托管',
  SEMI_CONSIGNMENT: '半托管',
  OVERSEAS_WAREHOUSE: '海外仓',
  LOCAL: '本地',
};

/** 保底状态的色调：只有「没兑现」才需要被看见，「部分兑现」次之。 */
export function floorStatusTone(status: string | null | undefined): string {
  switch (status) {
    case 'FULFILLED':
      return 'text-emerald-600 dark:text-emerald-400';
    case 'PARTIAL':
      return 'text-amber-600 dark:text-amber-400';
    case 'UNFULFILLED':
      return 'text-rose-600 dark:text-rose-400';
    default:
      return 'text-muted-foreground';
  }
}

/** 被压低一行的色调。只有真的被压低才需要被看见。 */
export function suppressionTone(line: { suppressed: boolean | null }): string {
  return line.suppressed ? 'text-rose-600 dark:text-rose-400' : 'text-muted-foreground';
}

/**
 * 守恒式的三段文案。
 *
 * <p>比 7.8.1.1 多一段：保底池 + 余量池 = 可分配量。保底是在分摊之前先发的，
 * 这一条摆出来时，「保底把货吃穿了」在界面上是**看得见**的。
 */
export function conservationText(result: PriorityAllocationResult): string[] {
  return [
    `可用 ${qty(result.availableQuantity)} = 缓冲 ${qty(result.bufferQuantity)} + 可分配 ${qty(result.allocatableQuantity)}`,
    `保底池 ${qty(result.floorPool)} + 余量池 ${qty(result.residualPool)} = 可分配 ${qty(result.allocatableQuantity)}`,
    `可分配 ${qty(result.allocatableQuantity)} = Σ分摊 ${qty(result.allocatedTotal)} + 未分配 ${qty(result.unallocatedQuantity)}`,
  ];
}

/**
 * 一个店铺「被压低了多少」。
 *
 * <p>正数 = 被压低。正数之外不该出现负数 —— 后端已经保证
 * {@code suppressed=false} 时 {@code allocatedQty >= baseAllocatedQty}，
 * 这里仍夹到 0，是因为界面上一个负的「被压低 -3 件」只会让人以为算错了。
 */
export function suppressedBy(line: {
  baseAllocatedQty: number | string | null;
  allocatedQty: number | string | null;
}): number {
  const base = Number(line.baseAllocatedQty ?? 0);
  const got = Number(line.allocatedQty ?? 0);
  const delta = base - got;
  return Number.isNaN(delta) || delta < 0 ? 0 : delta;
}

/** 分摊额合计（前端自算一遍，用来和后端报的 allocatedTotal 对照）。 */
export function sumAllocated(
  lines: { allocatedQty: number | string | null }[] | null | undefined,
): number {
  return (lines ?? []).reduce((acc, l) => acc + Number(l.allocatedQty ?? 0), 0);
}

/** 区间（左闭右开）文案。与后端 effective_from/effective_to 同一套约定。 */
export function intervalText(from: string | null, to: string | null): string {
  const start = from ? new Date(from).toLocaleString('zh-CN') : '—';
  if (!to) return `${start} 起至今仍在生效`;
  return `${start} ~ ${new Date(to).toLocaleString('zh-CN')}（已收口）`;
}

/** 统一的错误文案：把 ApiError 的状态码与后端给的原因都带出来。 */
export function errorText(e: unknown): string {
  if (e instanceof ApiError) {
    return e.status ? `请求失败（${e.status}）：${e.message}` : e.message;
  }
  return e instanceof Error ? e.message : String(e);
}

// ------------------------------------------------------------ 契约派生类型
//
// codegen 把 Java 侧的枚举**内联**成了记录字段上的字符串联合，枚举本身不单独生成 TS 类型。
// 所以下面这些全部从契约类型反推，而不是手写一份会和后端漂移的联合。

export type FloorMode = AllocationFloorRow['floorMode'];
export type FloorStatus = PriorityAllocationResult['lines'][number]['floorStatus'];
export type FloorShortfallReason = NonNullable<
  PriorityAllocationResult['lines'][number]['shortfallReason']
>;
export type SuppressionReason = NonNullable<
  PriorityAllocationResult['lines'][number]['suppressionReason']
>;
export type PriorityLine = PriorityAllocationResult['lines'][number];
export type Resolution = ResolutionRow;
export type FloorRow = AllocationFloorRow;
