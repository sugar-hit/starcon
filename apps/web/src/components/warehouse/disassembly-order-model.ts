import type {
  DisassemblyCapabilities,
  DisassemblyComponentInput,
  DisassemblyOrderCancelInput,
  DisassemblyOrderCreateInput,
  DisassemblyOrderCreated,
  DisassemblyOrderDetail,
  DisassemblyOrderExecuted,
  DisassemblyOrderPage,
  DisassemblyOrderRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 8.4.2.1 拆解单前端模型（成品拆解回组件）。
 *
 * <p>蓝图 08 卷：「成品拆解回组件：消耗成品库存、产生组件库存（用于退换货拆解、样品回收）」，
 * 输入「成品 SKU 与数量；拆解去向（可用/待检/残次）」，输出「拆解单 + 反向库存分录」。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>去向逐行必填、没有默认值</b> —— 蓝图点名「拆解结果的组件质量状态由接收方决定，
 *       不能默认全部回可售」。所以建单表单里去向是一个**必选**下拉，没有「默认」这一项；
 *       不选就是不发请求，而不是替操作员挑一个可售。</li>
 *   <li><b>回收率是拆解独有的一维</b>：组装发料会损耗（损耗率），拆解退件可能缺件、损坏（回收率）。
 *       没有它就无法解释「BOM 上有 10 个螺丝，拆出来只有 8 个」。</li>
 *   <li><b>应回量与实回量分列显示</b>：草稿期两列相等（实回恒为 0），执行后必须<b>逐行相等</b> ——
 *       这条是后端数据库层的不变量，页面把它显式摆出来而不是藏起来。</li>
 *   <li><b>去向分布常驻</b>：列表页把「多少组件回了可售、多少回了待检/残次」摆在汇总区 ——
 *       看不到去向分布，「不能默认全部回可售」就只是一句写在文档里的话。</li>
 *   <li><b>执行是不可逆的</b>：成品出库 + 组件入库在同一个本地事务里完成，
 *       执行后单据是终态（不能再改数、不能再流转、不能删）。按钮在非草稿态直接禁用并说明原因。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code warehouse/disassembly-orders}，刻意不是 {@code assembly-orders}
 * 的子路径 —— 「组装」与「拆解」是两个可分别统计的模块，挂在同一支下会让它们在契约上混成一团。
 */

const base = 'warehouse/disassembly-orders';

export interface DisassemblyOrderParams {
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

export function disassemblyOrderQuery(params: DisassemblyOrderParams): string {
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
  return api<DisassemblyCapabilities>(`${base}/capabilities`);
}

export function fetchDisassemblyOrders(params: DisassemblyOrderParams) {
  return api<DisassemblyOrderPage>(`${base}${disassemblyOrderQuery(params)}`);
}

export function fetchDisassemblyOrder(orderId: string) {
  return api<DisassemblyOrderDetail>(`${base}/${encodeURIComponent(orderId)}`);
}

export function createDisassemblyOrder(input: DisassemblyOrderCreateInput) {
  return api<DisassemblyOrderCreated>(base, 'POST', input);
}

/** 执行拆解：成品出库 + 组件入库，**一个本地事务**。 */
export function executeDisassemblyOrder(orderId: string, notes?: string) {
  return api<DisassemblyOrderExecuted>(`${base}/${encodeURIComponent(orderId)}/execute`, 'POST', {
    notes: notes ?? null,
  });
}

/** 取消草稿（不动库存：草稿本来就没拆过东西）。 */
export function cancelDisassemblyOrder(orderId: string, input: DisassemblyOrderCancelInput) {
  return api<DisassemblyOrderRow>(`${base}/${encodeURIComponent(orderId)}/cancel`, 'POST', input);
}

export type { DisassemblyComponentInput };

// ================================================================ 展示口径

/** 拆解单状态标签（与后端 V108 的 CHECK 词表逐字对应）。 */
export const disassemblyStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
};

/**
 * 上游来源标签。DIRECT = 仓库现场直接发起（样品回收等）；ASSEMBLY_ORDER = 来自 8.4.1.1 组装单；
 * AFTERSALES_RETURN = 来自 11 售后退件。
 */
export const disassemblySourceLabels: Record<string, string> = {
  DIRECT: '仓库直接发起',
  ASSEMBLY_ORDER: '来自组装单',
  AFTERSALES_RETURN: '来自售后退件',
};

/**
 * 去向标签：与 V1 既有 inventory_quality_status.role 词表逐字对应
 * （SELLABLE 可用 / PENDING_INSPECTION 待检 / DEFECTIVE 残次 —— 正是蓝图说的三种）。
 */
export const destinationRoleLabels: Record<string, string> = {
  SELLABLE: '可用',
  PENDING_INSPECTION: '待检',
  DEFECTIVE: '残次',
};

/** 建单表单里可选的三档去向。刻意不含「默认」—— 蓝图点名禁止默认全部回可售。 */
export const destinationRoleOptions = ['SELLABLE', 'PENDING_INSPECTION', 'DEFECTIVE'] as const;

export function disassemblyStatusLabel(status: string): string {
  return disassemblyStatusLabels[status] ?? status;
}

export function disassemblySourceLabel(source: string): string {
  return disassemblySourceLabels[source] ?? source;
}

export function destinationRoleLabel(role: string): string {
  return destinationRoleLabels[role] ?? role;
}

/**
 * 状态徽章的视觉分级。
 *
 * <p>三态**故意各不相同**，因为它们对库存的意义本来就不同：
 * 草稿是「还没动」、已完成是「动过了」、已取消是「没动过所以取消」。
 * 把两个终态涂成同一种颜色会让人分不清「这批货动过没有」—— 而这恰恰是看这张单的人
 * 最想一眼知道的事。
 */
export function disassemblyStatusTone(status: string): string {
  switch (status) {
    case 'DRAFT':
      return 'bg-sky-500/15 text-sky-300 ring-sky-500/30';
    case 'COMPLETED':
      return 'bg-emerald-500/15 text-emerald-300 ring-emerald-500/30';
    default:
      return 'bg-white/5 text-white/60 ring-white/15';
  }
}

/** 去向徽章：可用是绿的，待检是琥珀的，残次是红的 —— 三档必须一眼分得开。 */
export function destinationRoleTone(role: string): string {
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

/** 分录方向标签：负向分录是成品出库，正向分录是组件入库。 */
export function entryDirectionLabel(direction: string): string {
  return direction === 'PRODUCT_OUT' ? '成品出库' : '组件入库';
}

export const disassemblySortLabels: Record<string, string> = {
  CREATED_AT: '建单时间',
  ORDER_CODE: '单号',
  DISASSEMBLY_QUANTITY: '拆解数量',
  STATUS: '状态',
};

export const disassemblySortKeys = Object.keys(disassemblySortLabels);

/**
 * 不变量在页面上的判定：实回合计必须等于应回合计，成品出库量必须等于拆解数量。
 *
 * <p>后端已在数据库层强制这三条（D1/D2/D3），所以这里返回 true/false
 * 只用于**给操作员一个当场可读的确认**，而不是替代后端判定。
 * 出现 false 说明账已经不对了（理论上不可达），页面直接把它渲染成警告而不是静默。
 */
export function invariantsHold(row: {
  expectedComponentQuantity: number;
  recoveredComponentQuantity: number;
  disassemblyQuantity: number;
  outboundProductQuantity: number;
  status: string;
}): { component: boolean; product: boolean } {
  if (row.status !== 'COMPLETED') {
    return { component: true, product: true };
  }
  return {
    component: row.recoveredComponentQuantity === row.expectedComponentQuantity,
    product: row.outboundProductQuantity === row.disassemblyQuantity,
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
 * <p>后端会拒绝任何不能被 4 位小数精确表示的量（不静默取整 —— 向下取整等于吞组件、
 * 向上取整等于多产，两种都会让「组件入库 = 应回量」看起来成立而物料对不上）。
 * 页面在提交前就标出来，省一次往返。
 */
export function isExactAtStockScale(value: string): boolean {
  if (!value.trim()) return false;
  const n = Number(value);
  if (!Number.isFinite(n) || n <= 0) return false;
  return Math.abs(n * 10000 - Math.round(n * 10000)) < 1e-6;
}

/** 回收率是否在 [0, 100]（百分数，95 = 95%）。 */
export function isValidRecoveryRate(value: string): boolean {
  if (!value.trim()) return true;
  const n = Number(value);
  return Number.isFinite(n) && n >= 0 && n <= 100;
}
