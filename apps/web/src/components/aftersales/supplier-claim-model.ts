/**
 * 11.2.3.3 供应商索赔的前端数据层。
 *
 * 蓝图 11-aftersales-and-returns.md：「对质量原因退货向供应商发起索赔：关联批次与责任判定、
 * 索赔金额、协商记录、赔付与冲减应付处理。」
 * 输出：「索赔单 + 冲减应付或退款记录。」
 * 加粗句：「<b>索赔不是自动到账</b>，系统只跟踪流程与金额，冲减应付需财务确认。」
 *
 * <b>这一层对那条加粗句负两件事</b>：
 *
 * <ol>
 *   <li><b>前端不提供「直接置为已结算」的调用</b>：已结算只能由
 *       {@link confirmSupplierClaimSettlement}（财务确认赔付分录）这一个动作驱动。
 *       界面上如果出现一个「标记已结算」按钮，用户会以为登记完赔付就等于钱到账了 ——
 *       而蓝图那句话说的恰恰相反。</li>
 *   <li><b>登记赔付与财务确认是两次不同的调用</b>：{@link registerSupplierClaimSettlement}
 *       只登记（后端把生效金额留成 0），{@link confirmSupplierClaimSettlement} 才生效。
 *       两者在页面上也必须是两个按钮、两种语气。</li>
 * </ol>
 *
 * <p>⚠️ <b>索赔基数不由前端算</b>：{@link fetchSupplierClaimCandidates} 回来的
 * {@code claimableBaseAmount} / {@code maxClaimAmount} 是**后端算好的**推导值，
 * 前端只读不重算 —— 前端算一遍就会变成第二套口径。
 */
import type {
  SupplierClaimAgree,
  SupplierClaimCandidatePage,
  SupplierClaimCapabilities,
  SupplierClaimCreate,
  SupplierClaimDetail,
  SupplierClaimItemCreate,
  SupplierClaimPage,
  SupplierNegotiationCreate,
  SupplierSettlementConfirm,
  SupplierSettlementCreate,
  SupplierSettlementReject,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError } from '../../lib/api';

/** 与后端 {@code /trade/supplier-claims} 同前缀。 */
const base = 'trade/supplier-claims';

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

/**
 * 把筛选对象编成 query string。
 *
 * ⚠️ 空串与 undefined 都要跳过：传一个空 `status=` 会让后端收到
 * 「按空状态筛选」而不是「不筛状态」，两种读法在列表页上表现完全不同。
 */
function query(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '' || value === null) continue;
    search.set(key, String(value));
  }
  const suffix = search.toString();
  return suffix ? `?${suffix}` : '';
}

// ==========================================================================
// 能力声明
// ==========================================================================

export const fetchSupplierClaimCapabilities = () =>
  call<SupplierClaimCapabilities>(`${base}/capabilities`);

// ==========================================================================
// 可索赔候选（发起索赔的唯一入口素材）
// ==========================================================================

export const fetchSupplierClaimCandidates = (params: { keyword?: string; page?: number; size?: number } = {}) =>
  call<SupplierClaimCandidatePage>(`${base}/candidates${query(params)}`);

// ==========================================================================
// 索赔单
// ==========================================================================

export const fetchSupplierClaims = (params: {
  page?: number;
  size?: number;
  status?: string;
  supplierRefId?: string;
  liabilityStage?: string;
  offsetStatus?: string;
  minAmount?: number;
  maxAmount?: number;
  from?: string;
  to?: string;
  keyword?: string;
  sort?: string;
} = {}) => call<SupplierClaimPage>(`${base}${query(params)}`);

export const fetchSupplierClaim = (id: string) => call<SupplierClaimDetail>(`${base}/${id}`);

export const createSupplierClaim = (body: SupplierClaimCreate) =>
  call<SupplierClaimDetail>(`${base}`, { method: 'POST', body: JSON.stringify(body) });

/** 加一条索赔明细。⚠️ 没有基数字段：基数由后端从质检原值取。 */
export const addSupplierClaimItem = (id: string, body: SupplierClaimItemCreate) =>
  call<SupplierClaimDetail>(`${base}/${id}/items`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const removeSupplierClaimItem = (id: string, itemId: string) =>
  call<SupplierClaimDetail>(`${base}/${id}/items/${itemId}`, { method: 'DELETE' });

export const submitSupplierClaim = (id: string, body: { submitNote: string; expectedVersion: number }) =>
  call<SupplierClaimDetail>(`${base}/${id}/submit`, { method: 'POST', body: JSON.stringify(body) });

export const startSupplierClaimNegotiation = (id: string, expectedVersion: number) =>
  call<SupplierClaimDetail>(`${base}/${id}/start-negotiation?expectedVersion=${expectedVersion}`, {
    method: 'POST',
  });

export const recordSupplierClaimNegotiation = (id: string, body: SupplierNegotiationCreate) =>
  call<SupplierClaimDetail>(`${base}/${id}/negotiations`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

/** 置为已达成。⚠️ 达成金额由后端从「达成一致」那条协商记录取，前端不传。 */
export const agreeSupplierClaim = (id: string, body: SupplierClaimAgree) =>
  call<SupplierClaimDetail>(`${base}/${id}/agree`, { method: 'POST', body: JSON.stringify(body) });

export const rejectSupplierClaim = (
  id: string,
  body: { rejectReason: string; expectedVersion: number },
) =>
  call<SupplierClaimDetail>(`${base}/${id}/reject`, { method: 'POST', body: JSON.stringify(body) });

export const withdrawSupplierClaim = (
  id: string,
  body: { withdrawReason: string; expectedVersion: number },
) =>
  call<SupplierClaimDetail>(`${base}/${id}/withdraw`, { method: 'POST', body: JSON.stringify(body) });

/**
 * 登记赔付 / 冲减应付。
 *
 * ⚠️ **登记不等于生效**：后端把生效金额留成 0，等财务确认。
 */
export const registerSupplierClaimSettlement = (id: string, body: SupplierSettlementCreate) =>
  call<SupplierClaimDetail>(`${base}/${id}/settlements`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

/** 财务确认冲减生效（**唯一**能让冲减生效的入口）。 */
export const confirmSupplierClaimSettlement = (
  id: string,
  settlementId: string,
  body: SupplierSettlementConfirm,
) =>
  call<SupplierClaimDetail>(`${base}/${id}/settlements/${settlementId}/confirm`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

/** 财务驳回赔付。 */
export const rejectSupplierClaimSettlement = (
  id: string,
  settlementId: string,
  body: SupplierSettlementReject,
) =>
  call<SupplierClaimDetail>(`${base}/${id}/settlements/${settlementId}/reject`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

// ==========================================================================
// 纯函数：界面上要自己讲清楚的规则
// ==========================================================================

/** 索赔单状态。顺序 = 状态机推进顺序。 */
export const CLAIM_STATUS_OPTIONS = [
  { code: 'DRAFT', label: '草稿' },
  { code: 'SUBMITTED', label: '已提交' },
  { code: 'NEGOTIATING', label: '协商中' },
  { code: 'AGREED', label: '已达成' },
  { code: 'SETTLED', label: '已结算' },
  { code: 'REJECTED', label: '已驳回' },
  { code: 'WITHDRAWN', label: '已撤回' },
] as const;

/** 冲减状态。 */
export const OFFSET_STATUS_OPTIONS = [
  { code: 'NOT_APPLIED', label: '未登记赔付' },
  { code: 'PENDING_FINANCE_CONFIRM', label: '待财务确认' },
  { code: 'APPLIED', label: '冲减已生效' },
  { code: 'FINANCE_REJECTED', label: '财务已驳回' },
] as const;

/** 索赔原因（质量原因的细分）。 */
export const CLAIM_REASON_OPTIONS = [
  { code: 'QUALITY_DEFECT', label: '品质不合格' },
  { code: 'WRONG_ITEM', label: '错发商品' },
  { code: 'SHORTAGE', label: '短少数量' },
  { code: 'DAMAGED', label: '到货破损' },
  { code: 'OTHER', label: '其它质量原因' },
] as const;

/** 协商形态。 */
export const NEGOTIATION_TYPE_OPTIONS = [
  { code: 'CLAIM_REVIEW', label: '内部复核' },
  { code: 'SUPPLIER_RESPONSE', label: '供应商答复' },
  { code: 'COUNTER_OFFER', label: '对方还价' },
  { code: 'AGREEMENT', label: '达成一致' },
  { code: 'REJECTION', label: '协商破裂' },
] as const;

/** 协商参与方。 */
export const NEGOTIATION_ROLE_OPTIONS = [
  { code: 'INTERNAL', label: '我方' },
  { code: 'SUPPLIER', label: '供应商' },
] as const;

/** 赔付方式：冲减应付 / 供应商退款。二者的财务确认要求完全一样。 */
export const SETTLEMENT_METHOD_OPTIONS = [
  { code: 'OFFSET_PAYABLE', label: '冲减应付' },
  { code: 'SUPPLIER_REFUND', label: '供应商退款' },
] as const;

/**
 * 责任环节的**可索赔**判定（与后端 {@code SupplierClaimableStage.claimable} 逐条对应）。
 *
 * <p>⚠️ 只有生产与描述不符能向供应商索赔：运输与仓储的责任在承运商与我们自己身上，
 * 买家因素与责任待定更不能算到供应商头上。
 */
export const CLAIMABLE_STAGES = ['PRODUCTION', 'DESCRIPTION'] as const;

/** 不可索赔环节的说明（界面上要自己讲清楚为什么不能索赔）。 */
export function stageBlockedReason(stage: string | null | undefined): string | null {
  if (!stage) return null;
  if ((CLAIMABLE_STAGES as readonly string[]).includes(stage)) return null;
  switch (stage) {
    case 'TRANSPORT':
      return '运输环节的责任在承运商，不在供应商的产品上，不能向供应商索赔';
    case 'WAREHOUSE':
      return '仓储环节的责任在我们自己，不在供应商的产品上，不能向供应商索赔';
    case 'BUYER_FACTOR':
      return '买家因素造成的退货不能向供应商索赔';
    case 'UNDETERMINED':
      return '责任待定不能向供应商索赔：先把责任判定落到具体环节（生产 / 描述不符）';
    default:
      return `未知责任环节（${stage}），不能向供应商索赔`;
  }
}

/**
 * 赔付分录的**生效提示**。
 *
 * <p>蓝图加粗句「索赔不是自动到账」在界面上必须看得见：
 * 登记了赔付但还没确认时，金额栏要显示「登记 8 / 生效 0」。
 */
export function settlementEffectiveHint(settlement: {
  requestedAmount: number;
  effectiveAmount: number;
  settlementStatus: string;
}): string {
  if (settlement.settlementStatus === 'CONFIRMED') {
    return `已生效：${settlement.effectiveAmount}`;
  }
  if (settlement.settlementStatus === 'REJECTED') {
    return `已被财务驳回，生效金额 0（登记 ${settlement.requestedAmount}）`;
  }
  return `已登记 ${settlement.requestedAmount}，**尚未生效**（生效 0，等待财务确认）`;
}

/** 赔付分录是否处于「已登记但未生效」状态（页面上要标成醒目的待办）。 */
export function isSettlementPending(settlement: { settlementStatus: string }): boolean {
  return settlement.settlementStatus === 'PENDING_FINANCE_CONFIRM';
}

/**
 * 取某个动作的禁用理由。
 *
 * <p>⚠️ 后端在 {@code actions} 里已经给了 {@code blockedReason}，前端只是转述 ——
 * 规则只有一份（后端那份），前端不自己再编一套。
 */
export function actionBlockedReason(
  actions: { code: string; allowed: boolean; blockedReason: string | null }[] | undefined,
  code: string,
): string | null {
  const action = actions?.find((a) => a.code === code);
  if (!action) return '该动作在当前索赔单上不可用';
  if (action.allowed) return null;
  return action.blockedReason || '当前状态不允许这个动作';
}

/** 金额格式化：后端给的是 number（契约把 NUMERIC 映射成 number），这里只做展示。 */
export function formatAmount(value: number | null | undefined, currency?: string | null): string {
  if (value === null || value === undefined) return '—';
  const fixed = value.toFixed(2);
  return currency ? `${fixed} ${currency}` : fixed;
}

/** 时间格式化。契约里是 ISO 字符串。 */
export function formatTime(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}
