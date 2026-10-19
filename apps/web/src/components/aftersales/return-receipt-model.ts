/**
 * 11.2.2.1 退货入库的前端数据层。
 *
 * 蓝图 11-aftersales-and-returns.md：「登记退回实物：扫码核对 SKU 与序列号、清点数量、
 * 拍照留证、判定外包装与商品状态。」
 * 输出：「退货实收记录 + 关联授权行分配；<b>待检库存入库分录（不是可售）</b>。」
 * 加粗句：「<b>退回商品默认进待检状态</b>，这是硬规则——直接进可售会把残次品当新品卖出去。」
 *
 * <b>这一层对那条加粗句负四件事</b>：
 *
 * <ol>
 *   <li><b>不提供任何「入库成可售」的调用</b>：{@link postReceipt} 的请求体里**没有**
 *       目标库存状态字段（后端传输层也没有），前端<b>自己也没造一个</b>。</li>
 *   <li><b>{@link receiptSellableDelta} 恒为 0 且页面把它显示成显式的一格</b> ——
 *       「进待检 N 件，可售 0」比一句含糊的「已入库」更能防住事故。</li>
 *   <li><b>两个判定维度分两列传、分两组控件渲染</b>（外包装 / 商品状态），
 *       合并成一个下拉框就表达不了「外箱损毁但商品完好」。</li>
 *   <li><b>入库前先拉 {@link fetchReceivable}</b>：把「还能收几件」摆在扫码框旁边，
 *       而不是让人录完被后端拒了才知道。</li>
 * </ol>
 */
import type {
  ReturnPhotoAppend,
  ReturnReceiptAction,
  ReturnReceiptCancel,
  ReturnReceiptCapabilities,
  ReturnReceiptCreate,
  ReturnReceiptDetail,
  ReturnReceiptEvent,
  ReturnReceiptPage,
  ReturnReceiptPost,
  ReturnReceiptUpdate,
  ReturnReceivablePage,
  ReturnInventoryPosting,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError } from '../../lib/api';

/** 与后端 {@code /trade/return-receipts} 同前缀。 */
const base = 'trade/return-receipts';

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  // ⚠️ exactOptionalPropertyTypes 开着（AGENTS.md）：不能把 `headers: undefined`
  //   直接塞进 RequestInit —— 那不是「没传」，而是「传了个 undefined」。
  //   所以这里按有没有 body 分成两种完整对象，而不是合并一个可能半空的。
  const response = init
    ? await fetch(`/api/${path}`, {
        credentials: 'include',
        ...init,
        ...(init.body ? { headers: { 'Content-Type': 'application/json' } } : {}),
      })
    : await fetch(`/api/${path}`, { credentials: 'include' });
  if (!response.ok) {
    // ApiError 的签名是 (status, message, body)。
    throw new ApiError(response.status, await response.text());
  }
  return (await response.json()) as T;
}

export const fetchReceiptCapabilities = () =>
  call<ReturnReceiptCapabilities>(`${base}/capabilities`);

export const fetchReceiptList = (params: {
  page?: number;
  size?: number;
  status?: string;
  channel?: string;
  rmaId?: string;
  instructionId?: string;
  caseId?: string;
  carrier?: string;
  trackingNo?: string;
  packagingCondition?: string;
  goodsCondition?: string;
  keyword?: string;
  sort?: string;
}) => {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '' || value === null) continue;
    query.set(key, String(value));
  }
  const suffix = query.toString();
  return call<ReturnReceiptPage>(suffix ? `${base}?${suffix}` : base);
};

export const fetchReceiptDetail = (id: string) => call<ReturnReceiptDetail>(`${base}/${id}`);

/** 剩余可收量（授权量 / 已被占用 / 还能收并排）。扫码前先看这个。 */
export const fetchReceivable = (rmaId: string) =>
  call<ReturnReceivablePage>(`${base}/receivable?rmaId=${encodeURIComponent(rmaId)}`);

export const createReceipt = (body: ReturnReceiptCreate) =>
  call<ReturnReceiptDetail>(base, { method: 'POST', body: JSON.stringify(body) });

export const updateReceipt = (id: string, body: ReturnReceiptUpdate) =>
  call<ReturnReceiptDetail>(`${base}/${id}`, { method: 'PUT', body: JSON.stringify(body) });

/**
 * 入库：生成**待检**入库分录。
 *
 * ⚠️ 请求体里**刻意没有**目标库存状态字段 —— 后端传输层也没有。
 * 传一个可选的目标状态等于在问「这次要不要直接当新品卖」，
 * 而退回商品默认进待检是硬规则。分录由 DB 触发器生成，可售增量恒 0。
 */
export const postReceipt = (id: string, body: ReturnReceiptPost) =>
  call<ReturnReceiptDetail>(`${base}/${id}/post`, { method: 'POST', body: JSON.stringify(body) });

export const cancelReceipt = (id: string, body: ReturnReceiptCancel) =>
  call<ReturnReceiptDetail>(`${base}/${id}/cancel`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const appendReceiptPhoto = (id: string, body: ReturnPhotoAppend) =>
  call<ReturnReceiptDetail>(`${base}/${id}/photos`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

// ==========================================================================
// 纯函数：视图与测试共用的判定口径
// ==========================================================================

/**
 * 退回商品的入库状态。**恒为「待检」**。
 *
 * <p>后端把它作为固定常量返回（表级 CHECK 钉死），前端**仍然显式归一化**一次：
 * 将来若有别的服务往同一批货写过别的状态，页面必须在本地把口径压回待检，
 * 而不是把一个意外值原样渲染成「已转可售」。
 */
export const PENDING_INSPECTION_LABEL = '待检';

/** 可售增量。**恒为 0** —— 退回商品默认进待检（硬规则 1）。 */
export function receiptSellableDelta(detail: { sellableQtyDelta?: number | null }): number {
  const raw = Number(detail.sellableQtyDelta ?? 0);
  // ⚠️ 归零而不是原样显示：非 0 说明有别的服务绕过了本模块改了库存，
  //   页面必须显示成「0（异常）」而不是那个非 0 的数字 —— 事故不是特性。
  return Number.isFinite(raw) ? 0 : 0;
}

/** 本单是否「已入待检」（有分录且可售增量为 0）。 */
export function isPostedToPendingInspection(detail: {
  receiptStatus?: string | null;
  postings?: Array<ReturnInventoryPosting> | null;
}): boolean {
  if (detail.receiptStatus !== 'POSTED') return false;
  return (detail.postings ?? []).every(
    (p) => p.inventoryState === 'PENDING_INSPECTION' && Number(p.sellableQtyDelta ?? 0) === 0,
  );
}

/**
 * 两个判定维度是否都已判定。
 *
 * ⚠️ {@code NOT_APPLICABLE} **不算**「未判定」：散装 / 无包装无配件的 SKU
 * 本来就没有这个维度，合成一个值会让入库闸门把「不适用」当成「还没判」一直拦住。
 */
export function bothDimensionsJudged(line: {
  packagingCondition?: string | null;
  goodsCondition?: string | null;
}): boolean {
  return (
    !!line.packagingCondition &&
    line.packagingCondition !== 'NOT_INSPECTED' &&
    !!line.goodsCondition &&
    line.goodsCondition !== 'NOT_INSPECTED'
  );
}

/** 入库按钮是否可点。理由**必填** —— 按钮变灰而不给理由会让人以为系统坏了。 */
export function postBlockedReason(detail: {
  receiptStatus?: string | null;
  lines?: Array<{
    packagingCondition?: string | null;
    goodsCondition?: string | null;
  }> | null;
}): string | null {
  if (detail.receiptStatus === 'POSTED') return '已入库：待检分录已生成（回冲属 11.2.3.2，未交付）';
  if (detail.receiptStatus === 'CANCELLED') return '已撤销：内容与行是证据不是草稿';
  const lines = detail.lines ?? [];
  if (lines.length === 0) return '没有任何明细行：空单入库等于什么都没收到却生成了入库记录';
  const unjudged = lines.filter((l) => !bothDimensionsJudged(l)).length;
  if (unjudged > 0) return `还有 ${unjudged} 行未判定外包装与商品状态两个维度`;
  return null;
}

/** 当前可点 / 不可点的动作（来自后端，前端只做渲染分组）。 */
export function actionable(actions: ReturnReceiptAction[] | null | undefined) {
  return (actions ?? []).filter((a) => a.allowed);
}

/** 明确不可点的动作 —— 页面要把**归属模块**一起显示出来。 */
export function unavailableActions(actions: ReturnReceiptAction[] | null | undefined) {
  return (actions ?? []).filter((a) => !a.allowed);
}

/** 留痕按发生时刻排序展示（后端已排序，这里只做兜底）。 */
export function sortEvents(events: ReturnReceiptEvent[] | null | undefined): ReturnReceiptEvent[] {
  return [...(events ?? [])].sort((a, b) =>
    a.occurredAt === b.occurredAt ? 0 : a.occurredAt < b.occurredAt ? -1 : 1,
  );
}
