import type {
  GoodsReceiptCreateRequest,
  GoodsReceiptDetail,
  GoodsReceiptMovements,
  GoodsReceiptPage,
  GoodsReceiptSaved,
  GoodsReceiptUpdateRequest,
  GoodsReceiptCapabilities,
  GoodsReceiptReceiveRequest,
  GoodsReceiptCloseRequest,
  GoodsReceiptLineRow,
} from '../../../../../packages/api-contracts/src/catalog';
import { api } from '../inventory/api';

/**
 * 8.2.1.1 收货单前端模型。
 *
 * <p>界面上最容易误读的两处，这个模块都显式处理了：
 *
 * <ul>
 *   <li>**预期与实收分开记录**（蓝图原文）。货没到齐是常态，而「货没到齐」和「账记错了」
 *       必须能区分开。所以预期 / 实收 / 拒收是三个独立列，**还差量单独一列**：
 *       把三者相减揉成一列「已处理」，就答不出「到底还差多少没到」。
 *   <li>**还差量不是差异，拒收量也不在分录里**。还差的那部分货还在路上；
 *       拒收的那部分从没进过库存。所以页面上把「还差」和「差异」分开显示，
 *       并把分录口径说明常驻显示 —— 否则「实收 60、拒收 10、分录只有 +60」
 *       会被读成「10 件的账去哪了」。
 * </ul>
 */

/** 列表查询条件。与后端 `GoodsReceiptQuery` 一一对应。 */
export interface GoodsReceiptParams {
  warehouseId?: string;
  status?: string[];
  sourceType?: string[];
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export function goodsReceiptQuery(params: GoodsReceiptParams): string {
  const parts: string[] = [];
  if (params.warehouseId) parts.push(`warehouseId=${encodeURIComponent(params.warehouseId)}`);
  for (const s of params.status ?? []) parts.push(`status=${encodeURIComponent(s)}`);
  for (const s of params.sourceType ?? []) parts.push(`sourceType=${encodeURIComponent(s)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchGoodsReceipts(params: GoodsReceiptParams) {
  return api<GoodsReceiptPage>(`warehouse/goods-receipts${goodsReceiptQuery(params)}`);
}

export function fetchGoodsReceiptCapabilities() {
  return api<GoodsReceiptCapabilities>('warehouse/goods-receipts/capabilities');
}

export function fetchGoodsReceiptDetail(receiptId: string) {
  return api<GoodsReceiptDetail>(
    `warehouse/goods-receipts/${encodeURIComponent(receiptId)}`,
  );
}

/** 本单分录：后端复用 7.1.2.1 台账，`attributionNote` 必须在界面上显示。 */
export function fetchGoodsReceiptMovements(receiptId: string) {
  return api<GoodsReceiptMovements>(
    `warehouse/goods-receipts/${encodeURIComponent(receiptId)}/movements`,
  );
}

export function createGoodsReceipt(input: GoodsReceiptCreateRequest) {
  return api<GoodsReceiptSaved>('warehouse/goods-receipts', 'POST', input);
}

export function updateGoodsReceipt(receiptId: string, input: GoodsReceiptUpdateRequest) {
  return api<GoodsReceiptSaved>(
    `warehouse/goods-receipts/${encodeURIComponent(receiptId)}`,
    'PUT',
    input,
  );
}

/** 实收：逐笔落事件、转入待检或可售 bucket、写入库分录、登记差异。可分次收。 */
export function receiveGoods(receiptId: string, input: GoodsReceiptReceiveRequest) {
  return api<GoodsReceiptSaved>(
    `warehouse/goods-receipts/${encodeURIComponent(receiptId)}/receive`,
    'POST',
    input,
  );
}

/** 关单：把剩余未到齐如实记为少收，然后结案。 */
export function closeGoodsReceipt(receiptId: string, input: GoodsReceiptCloseRequest) {
  return api<GoodsReceiptSaved>(
    `warehouse/goods-receipts/${encodeURIComponent(receiptId)}/close`,
    'POST',
    input,
  );
}

/** 四态标签。未收录的码原样显示，不静默吞掉。 */
export const receiptStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  PARTIALLY_RECEIVED: '部分收',
  RECEIVED: '已收',
  CLOSED: '已关闭',
};

/** 七类差异标签。与后端 DiscrepancyType 逐值对应。 */
export const receiptDiscrepancyLabels: Record<string, string> = {
  OVER_RECEIPT: '超收',
  SHORT_RECEIPT: '少收',
  REJECTED: '拒收',
  DAMAGED: '破损',
  WRONG_ITEM: '型号不符',
  EXPIRED: '过期',
  MISDELIVERED: '错发',
};

/**
 * 只有现场能当场判定的三类差异允许在收货时随手登记。
 *
 * <p>破损 / 型号不符 / 过期 / 错发的**处理决定**归 8.2.1.2（未交付），
 * 所以表单只放开这三类 —— 让操作员随手选一个「破损」然后以为能索赔，
 * 得到的只是一条没人处理的事实记录。
 */
export const recordableDiscrepancyTypes = ['OVER_RECEIPT', 'REJECTED'] as const;

export const receiptSortOptions = [
  { value: 'UPDATED_AT', label: '最近更新' },
  { value: 'CREATED_AT', label: '建单时间' },
  { value: 'RECEIPT_CODE', label: '单号' },
];

/**
 * 一次可以推进的动作，按状态给出。
 *
 * <p>界面上只显示当前状态**允许**的按钮，而不是显示全部再在后端报错 ——
 * 「已关闭的单还显示收货按钮」会让运营以为能重复收货，那等于账外入库。
 */
export function receiptActions(status: string): string[] {
  switch (status) {
    case 'DRAFT':
      return ['receive', 'close'];
    case 'PARTIALLY_RECEIVED':
    case 'RECEIVED':
      return ['receive', 'close'];
    default:
      // CLOSED 是终态。
      return [];
  }
}

/**
 * 某一行还能收多少。
 *
 * <p>用后端给的 `outstandingQuantity` 而不是前端拿三个数自己减 ——
 * 前端自算就得在前端复制一遍口径，而那个口径一旦和后端漂移，
 * 界面上就会出现「还能收 0」但点下去后端说还有货的怪事。
 */
export function receivable(line: { outstandingQuantity: number }): number {
  return Math.max(0, Number(line.outstandingQuantity) || 0);
}

/**
 * 数量口径的自检。
 *
 * <p>详情页把它显式算出来显示，让「这三个数是怎么来的」在页面上自解释。
 * 超收时还差量是 0 而实收超过预期，这**不是**账错：超收是真实发生的事，
 * 它会被登记成一条 OVER_RECEIPT 差异。提示里要读得出这个区别。
 */
export function receiptQuantityText(parts: {
  expectedQuantity: number;
  receivedQuantity: number;
  rejectedQuantity: number;
  outstandingQuantity: number;
}): { ok: boolean; text: string } {
  const expected = Number(parts.expectedQuantity) || 0;
  const received = Number(parts.receivedQuantity) || 0;
  const rejected = Number(parts.rejectedQuantity) || 0;
  const outstanding = Number(parts.outstandingQuantity) || 0;
  const ok = Math.abs(expected - received - rejected - outstanding) < 0.0001;
  const over = received + rejected - expected;
  const suffix =
    over > 0.0001
      ? `（实收 + 拒收超出预期 ${fmt(over)}，已登记超收差异）`
      : outstanding > 0.0001
        ? '（还差的部分货还在路上，不算差异）'
        : '';
  return {
    ok,
    text: `预期 ${fmt(expected)} = 实收 ${fmt(received)} + 拒收 ${fmt(rejected)} + 还差 ${fmt(outstanding)}${suffix}${ok ? '' : '（对不上，请刷新）'}`,
  };
}

/** 数量展示。 */
export function fmt(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return Number(value).toLocaleString('zh-CN', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 4,
  });
}

/**
 * 时间展示。**带偏移量**显示，避免跨时区看单子时把到达时刻读错。
 *
 * <p>后端给的是 ISO-8601（含偏移），这里只做本地化不改时刻 —— 改时刻就是改事实。
 */
export function moment(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/** 来源下拉的展示文案。未交付的来源带 unavailable 后缀，界面上直接禁用。 */
export function sourceCaption(source: {
  label: string;
  available: boolean;
  ownerModule: string;
}): string {
  return source.available ? source.label : `${source.label}（${source.ownerModule} 未交付）`;
}

/** 明细行的口径说明，给展开区用。 */
export function lineCaption(line: GoodsReceiptLineRow): string {
  const expected = Number(line.expectedQuantity) || 0;
  const received = Number(line.receivedQuantity) || 0;
  const rejected = Number(line.rejectedQuantity) || 0;
  const outstanding = Math.max(0, expected - received - rejected);
  return `${line.skuCode} ${line.skuName} → ${line.qualityStatusName}：预期 ${fmt(expected)}、实收 ${fmt(received)}、拒收 ${fmt(rejected)}、还差 ${fmt(outstanding)}`;
}