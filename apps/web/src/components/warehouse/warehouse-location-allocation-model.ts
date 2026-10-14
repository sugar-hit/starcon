import type {
  GoodsReceiptPage,
  LocationAllocationCapabilities,
  LocationAllocationCancelInput,
  LocationAllocationDecision,
  LocationAllocationLinePlan,
  LocationAllocationPage,
  LocationAllocationReason,
  LocationAllocationSaved,
} from '../../../../../packages/api-contracts/src/index';
import { api } from './api';
import { errorText, qty } from './warehouse-facility-model';

/**
 * 8.1.2.2 库位自动分配前端模型。
 *
 * <p>蓝图：「按规则为入库商品推荐库位：同品类聚类、最短上架路径、容量与承重匹配、
 * 待检/残次专用库位强制规则。输入：入库 SKU 与数量；库位属性与占用；路径距离。
 * 输出：推荐库位列表（带推荐理由）。」红线：「**质量状态决定库位类型**：残次品入残次库位是
 * 硬规则，不允许混放。」
 *
 * <p>本模型处理六件在界面上最容易说错的事：
 *
 * <ul>
 *   <li><b>「无法判定」与「通过」必须分开渲染。</b>容量/承重有三种状态：量过且够
 *       （fit）、<b>判不了</b>（库位没量过或调用方没给需求 → {@code evaluated=false}）、
 *       以及量过但不够（那个库位<b>根本不进候选</b>，界面看不到它）。把第三种混进
 *       「不满足」列表会让人以为还有别的选择，而把第二种渲染成绿色勾就是撒谎。
 *   <li><b>score = 各条理由权重之和。</b>界面上直接展示理由明细并标出每条贡献了多少分，
 *       所以「为什么它排第一」可以当场核对，不需要额外解释一个黑箱数字。
 *   <li><b>硬规则不是建议。</b>界面**不**给「残次品也能进存储位，只是排在后面」这种选项 ——
 *       候选列表按构造就只含合规库位类型。
 *   <li><b>「同品类」要说清它不是类目。</b>聚类信号来自库存共置关系，catalog 类目树未接入，
 *       界面上必须写出来，否则运营会以为系统真按类目分好了货位。
 *   <li><b>「未交付」显示成未交付。</b>巷道路径图、既有库存体积口径、上架任务都如实标注
 *       归属模块，不给 0、不给空数组冒充「这个仓没有」。
 *   <li><b>推荐与决策分开显示。</b>推荐是纯计算（会随库存变化），已选定的是已落库的决策
 *       （带当时的理由快照）。混在一张表里会让人以为推荐也是定下来的。
 * </ul>
 */

export type LinePlan = LocationAllocationLinePlan;
export type Candidate = LinePlan['candidates'][number];
export type Reason = LocationAllocationReason;

const base = 'warehouse/location-allocations';

export function fetchAllocationCapabilities(): Promise<LocationAllocationCapabilities> {
  return api<LocationAllocationCapabilities>(`${base}/capabilities`);
}

/** 推荐是纯计算，服务端不落库 —— 重复点不会在库里留下垃圾行。 */
export function recommendLocations(input: {
  receiptId: string;
  requiredCapacity: string | null;
  requiredWeight: string | null;
}): Promise<LinePlan[]> {
  return api<LinePlan[]>(`${base}/recommend`, 'POST', {
    receiptId: input.receiptId,
    receiptLineId: null,
    requiredCapacity: input.requiredCapacity === '' ? null : input.requiredCapacity,
    requiredWeight: input.requiredWeight === '' ? null : input.requiredWeight,
  });
}

export function confirmAllocation(input: {
  receiptId: string;
  receiptLineId: string;
  locationId: string;
  allocateQuantity: string | null;
  requiredCapacity: string | null;
  requiredWeight: string | null;
}): Promise<LocationAllocationSaved> {
  return api<LocationAllocationSaved>(`${base}`, 'POST', {
    receiptId: input.receiptId,
    receiptLineId: input.receiptLineId,
    locationId: input.locationId,
    allocateQuantity: input.allocateQuantity === '' ? null : input.allocateQuantity,
    requiredCapacity: input.requiredCapacity === '' ? null : input.requiredCapacity,
    requiredWeight: input.requiredWeight === '' ? null : input.requiredWeight,
  });
}

export function releaseAllocation(id: string): Promise<LocationAllocationSaved> {
  return api<LocationAllocationSaved>(`${base}/${id}/release`, 'POST');
}

/** 撤销（DELETE 带 body）：决策行留着、状态转 CANCELLED，所以需要带原因。 */
export function cancelAllocation(id: string, reason: string): Promise<LocationAllocationSaved> {
  return api<LocationAllocationSaved>(`${base}/${id}`, 'DELETE', { reason });
}

/**
 * 待上架的候选收货单。
 *
 * <p>刻意读 8.2.1.1 的**真实收货单列表**而不是让运营手抄单号：推荐的对象是「入库 SKU 与
 * 数量」，那两项只存在于收货单上，手抄 id 既慢又容易贴错成别的租户的单子。
 * 同一个收据 id 仍然可以手填（列表没加载出来时的兜底）。
 */
export function fetchCandidateReceipts(): Promise<GoodsReceiptPage> {
  return api<GoodsReceiptPage>('warehouse/goods-receipts?size=50&sort=CREATED_AT');
}

export function fetchAllocationList(params: {
  warehouseId?: string | undefined;
  status?: string | undefined;
  qualityRole?: string | undefined;
  keyword?: string | undefined;
  sort?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<LocationAllocationPage> {
  const query = new URLSearchParams();
  if (params.warehouseId) query.set('warehouseId', params.warehouseId);
  if (params.status) query.set('status', params.status);
  if (params.qualityRole) query.set('qualityRole', params.qualityRole);
  if (params.keyword) query.set('keyword', params.keyword);
  if (params.sort) query.set('sort', params.sort);
  if (params.page) query.set('page', String(params.page));
  if (params.size) query.set('size', String(params.size));
  const suffix = query.toString();
  return api<LocationAllocationPage>(`${base}/list${suffix ? `?${suffix}` : ''}`);
}

export function fetchAllocationsByReceipt(
  receiptId: string,
): Promise<LocationAllocationDecision[]> {
  return api<LocationAllocationDecision[]>(`${base}/by-receipt/${receiptId}`);
}

// ------------------------------------------------------------ 展示

/** 质量角色中文名。与后端 {@code PlacementRule.label()} 逐条一致。 */
const roleLabels: Record<string, string> = {
  SELLABLE: '可售',
  PENDING_INSPECTION: '待检',
  DEFECTIVE: '残次',
};

export function qualityRoleLabel(role: string): string {
  return roleLabels[role] ?? role;
}

/** 库位类型中文名。与后端 {@code LocationType.label()} 及 8.1.2.1 前端表逐条一致。 */
const typeLabels: Record<string, string> = {
  STORAGE: '存储',
  RECEIVING: '收货暂存',
  SHIPPING: '发货暂存',
  QUARANTINE: '待检',
  DEFECTIVE: '残次',
  FROZEN: '冻结',
};

export function locationTypeLabel(type: string): string {
  return typeLabels[type] ?? type;
}

/** 决策状态中文名。与后端 {@code AllocationStatus.label()} 逐条一致。 */
const statusLabels: Record<string, string> = {
  PLANNED: '已选定',
  RELEASED: '已释放',
  CANCELLED: '已撤销',
};

export function allocationStatusLabel(status: string): string {
  return statusLabels[status] ?? status;
}

/** 理由码 → 中文说明。与后端 {@code ReasonCode.text()} 逐条一致。 */
const reasonTexts: Record<string, string> = {
  TYPE_RULE: '类型符合质量状态硬规则',
  CATEGORY_CLUSTER: '同品类聚类命中',
  SHORTEST_PATH: '上架路径最短',
  CAPACITY_FIT: '容量余量充足',
  WEIGHT_FIT: '承重余量充足',
  EMPTY_LOCATION: '空库位',
  CAPACITY_UNKNOWN: '容量无法判定',
  WEIGHT_UNKNOWN: '承重无法判定',
  ALREADY_COMMITTED: '已有其他上架承诺占用',
};

export function reasonText(code: string): string {
  return reasonTexts[code] ?? code;
}

/**
 * 「无法判定」的判定：**既不是够，也不是不够，是没人量过。**
 *
 * <p>返回 'unknown' 的两个理由码权重恒为 0 —— 它们只解释，不参与打分。
 * 界面用它们渲染成灰底说明，而不是绿勾或红叉。
 */
export function isUnknownReason(reason: Reason): boolean {
  return reason.code === 'CAPACITY_UNKNOWN' || reason.code === 'WEIGHT_UNKNOWN';
}

/** 容量/承重的三态文本。null 是「没量过」，绝不能折成 0。 */
export function remainingText(value: number | null | undefined): string {
  return value === null || value === undefined ? '未量' : qty(value);
}

/** 容量/承重规则是否**可判定**（而不是「通过」）。 */
export function measureState(evaluated: boolean, remaining: number | null | undefined): string {
  if (!evaluated) return '无法判定';
  if (remaining === null || remaining === undefined) return '未量';
  return `余量 ${qty(remaining)}`;
}

export interface ReasonLine {
  text: string;
  weight: number;
  /** 0 权重 = 只解释、不参与打分（无法判定 / 需要知道）。 */
  advisory: boolean;
}

/**
 * 候选的理由明细。
 *
 * <p>刻意保留每条的权重：score 就是这些权重之和，界面把它们逐条列出来，
 * 「为什么它排第一」当场可以核对 —— 而不是只给一个算不出所以然的总分。
 */
export function reasonLines(candidate: Candidate): ReasonLine[] {
  return candidate.reasons.map((reason) => ({
    text: reasonText(reason.code),
    weight: reason.weight,
    advisory: isUnknownReason(reason) || reason.weight === 0,
  }));
}

/**
 * 校验「score = 各条理由权重之和」。
 *
 * <p>返回一个可显示的偏差值（而不是抛错）：如果后端改了权重而前端公式没跟上，
 * 界面会把偏差显式标出来，而不是默默显示一个错的分数。
 */
export function scoreDrift(candidate: Candidate): number {
  const sum = candidate.reasons.reduce((acc, reason) => acc + reason.weight, 0);
  return candidate.score - sum;
}

/** 该明细行有没有待上架数量 —— 全 0 时界面显示「已全部指派」而不是空候选列表。 */
export function isFullyAllocated(plan: LinePlan): boolean {
  return plan.outstandingQuantity <= 0;
}

/** 硬规则推出的目标库位类型的中文名（界面上作为该行的「必须放进」）。 */
export function requiredTypeText(plan: LinePlan): string {
  return plan.requiredTypeLabel ?? locationTypeLabel(plan.requiredType);
}

/** 能力声明里「未交付」的条目，供界面如实列出而不是假装已接入。 */
export interface CapabilityGap {
  flag: keyof LocationAllocationCapabilities;
  label: string;
  owner: string | null;
}

export function capabilityGaps(caps: LocationAllocationCapabilities): CapabilityGap[] {
  const out: CapabilityGap[] = [];
  if (caps.routingGraphAvailable === false) {
    out.push({
      flag: 'routingGraphAvailable',
      label: '巷道路径图',
      owner: caps.routingGraphOwnerModule,
    });
  }
  if (caps.catalogCategoryAvailable === false) {
    out.push({
      flag: 'catalogCategoryAvailable',
      label: 'catalog 类目',
      owner: caps.catalogCategoryOwnerModule,
    });
  }
  if (caps.stockVolumeAvailable === false) {
    out.push({ flag: 'stockVolumeAvailable', label: '既有库存的体积口径', owner: null });
  }
  if (caps.putAwayTaskAvailable === false) {
    out.push({
      flag: 'putAwayTaskAvailable',
      label: '上架任务',
      owner: caps.putAwayTaskOwnerModule,
    });
  }
  return out;
}

export { errorText };
export type { LocationAllocationCancelInput, LocationAllocationDecision, LocationAllocationSaved };
