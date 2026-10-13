import type {
  AssemblyCapabilities,
  AssemblyComponentInput,
  AssemblyOrderCancelInput,
  AssemblyOrderCreateInput,
  AssemblyOrderCreated,
  AssemblyOrderDetail,
  AssemblyOrderIssued,
  AssemblyOrderPage,
  AssemblyOrderRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 8.4.1.1 组装单前端模型（实物套装组装）。
 *
 * <p>蓝图 08 卷：「实物套装组装：按 BOM 领用组件、消耗组件库存、产生成品 SKU 库存，
 * 同一事务完成。」输入「成品 SKU 与 BOM 版本；组件领用量；组装数量」，
 * 输出「组装单 + 组件出库分录 + 成品入库分录」。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>组件领用量按 BOM 冻结，不是现场拍脑袋填的</b>：建单时逐个组件填「应领量」，
 *       后端把它连同单位用量 / 损耗率一起落成 BOM 的冻结快照，之后 BOM 改版不影响已建的单。</li>
 *   <li><b>应领量与实发量分列显示</b>：草稿期两列相等（实发恒为 0），
 *       执行后必须<b>逐行相等</b> —— 这条是后端数据库层的不变量，页面把它显式摆出来而不是藏起来。</li>
 *   <li><b>虚拟套装不是本页的输入</b>：虚拟套装不持有库存，出库时由 09 订单展开扣组件。
 *       所以「成品 SKU」只接受真实 catalog SKU，页面在能力声明里把这条边界原样显示出来。</li>
 *   <li><b>执行是不可逆的</b>：组件出库 + 成品入库在同一个本地事务里完成，
 *       执行后单据是终态（不能再改数、不能再流转、不能删）。按钮在非草稿态直接禁用并说明原因。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */

const base = 'warehouse/assembly-orders';

export interface AssemblyOrderParams {
  status?: string;
  productSkuId?: string;
  warehouseId?: string;
  sourceSystem?: string;
  referenceId?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export function assemblyOrderQuery(params: AssemblyOrderParams): string {
  const parts: string[] = [];
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.productSkuId) parts.push(`productSkuId=${encodeURIComponent(params.productSkuId)}`);
  if (params.warehouseId) parts.push(`warehouseId=${encodeURIComponent(params.warehouseId)}`);
  if (params.sourceSystem) parts.push(`sourceSystem=${encodeURIComponent(params.sourceSystem)}`);
  if (params.referenceId) parts.push(`referenceId=${encodeURIComponent(params.referenceId)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchCapabilities() {
  return api<AssemblyCapabilities>(`${base}/capabilities`);
}

export function fetchAssemblyOrders(params: AssemblyOrderParams) {
  return api<AssemblyOrderPage>(`${base}${assemblyOrderQuery(params)}`);
}

export function fetchAssemblyOrder(orderId: string) {
  return api<AssemblyOrderDetail>(`${base}/${encodeURIComponent(orderId)}`);
}

export function createAssemblyOrder(input: AssemblyOrderCreateInput) {
  return api<AssemblyOrderCreated>(base, 'POST', input);
}

/** 执行组装：组件出库 + 成品入库，**一个本地事务**。 */
export function issueAssemblyOrder(orderId: string, notes?: string) {
  return api<AssemblyOrderIssued>(`${base}/${encodeURIComponent(orderId)}/issue`, 'POST', {
    notes: notes ?? null,
  });
}

/** 取消草稿（不动库存：草稿本来就没发过料）。 */
export function cancelAssemblyOrder(orderId: string, input: AssemblyOrderCancelInput) {
  return api<AssemblyOrderRow>(`${base}/${encodeURIComponent(orderId)}/cancel`, 'POST', input);
}

export type { AssemblyComponentInput };

// ================================================================ 展示口径

/** 组装单状态标签（与后端 V103 的 CHECK 词表逐字对应）。 */
export const assemblyStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
};

/** 上游来源标签。DIRECT = 仓库现场直接发起；TRADE_PROCESSING_ORDER = 来自 6.4.1.1 加工单。 */
export const assemblySourceLabels: Record<string, string> = {
  DIRECT: '仓库直接发起',
  TRADE_PROCESSING_ORDER: '来自加工单',
};

export function assemblyStatusLabel(status: string): string {
  return assemblyStatusLabels[status] ?? status;
}

export function assemblySourceLabel(source: string): string {
  return assemblySourceLabels[source] ?? source;
}

/**
 * 状态徽章的视觉分级。
 *
 * <p>草稿是「还没动」；已完成与已取消都是终态 —— 终态用同一种视觉，
 * 因为它们对库存的意义是一致的：**这张单不会再产生任何新的库存动作**。
 */
export function assemblyStatusTone(status: string): string {
  switch (status) {
    case 'DRAFT':
      return 'bg-sky-500/15 text-sky-300 ring-sky-500/30';
    case 'COMPLETED':
      return 'bg-emerald-500/15 text-emerald-300 ring-emerald-500/30';
    default:
      return 'bg-white/5 text-white/60 ring-white/15';
  }
}

/** 分录方向标签：负向分录是组件出库，正向分录是成品入库。 */
export function entryDirectionLabel(direction: string): string {
  return direction === 'COMPONENT_OUT' ? '组件出库' : '成品入库';
}

export const assemblySortLabels: Record<string, string> = {
  CREATED_AT: '建单时间',
  ORDER_CODE: '单号',
  ASSEMBLY_QUANTITY: '组装数量',
  STATUS: '状态',
};

export const assemblySortKeys = Object.keys(assemblySortLabels);

/**
 * 不变量在页面上的判定：实发合计必须等于应领合计，成品入库量必须等于组装数量。
 *
 * <p>后端已在数据库层强制这两条（V103 的 CHECK + 守卫触发器），所以这里返回 true/false
 * 只用于**给操作员一个当场可读的确认**，而不是替代后端判定。
 * 出现 false 说明账已经不对了（理论上不可达），页面直接把它渲染成警告而不是静默。
 */
export function invariantsHold(row: {
  requiredComponentQuantity: number;
  issuedComponentQuantity: number;
  assemblyQuantity: number;
  inboundProductQuantity: number;
  status: string;
}): { component: boolean; product: boolean } {
  if (row.status !== 'COMPLETED') {
    return { component: true, product: true };
  }
  return {
    component: row.issuedComponentQuantity === row.requiredComponentQuantity,
    product: row.inboundProductQuantity === row.assemblyQuantity,
  };
}

/** 数量格式化：最多 6 位小数，去掉尾随 0（后端发的是 string/number，这里只做展示）。 */
export function formatQuantity(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = typeof value === 'number' ? value : Number(value);
  if (Number.isNaN(n)) return String(value);
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
}

/**
 * 能否过库存刻度（4 位小数）。
 *
 * <p>后端会拒绝任何不能被 4 位小数精确表示的量（不静默取整 —— 向下取整等于吞材料、
 * 向上取整等于多领，两种都会让「组件出库 = 应领量」看起来成立而物料对不上）。
 * 页面在提交前就标出来，省一次往返。
 */
export function isExactAtStockScale(value: string): boolean {
  if (!value.trim()) return false;
  const n = Number(value);
  if (!Number.isFinite(n) || n <= 0) return false;
  return Math.abs(n * 10000 - Math.round(n * 10000)) < 1e-6;
}
