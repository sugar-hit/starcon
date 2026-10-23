/**
 * 11.2.3.2 转可售/残次/报废的前端数据层。
 *
 * 蓝图 11-aftersales-and-returns.md：「按质检结论把退货转入对应状态：可再销售转可售库存、
 * 残次转残次库存（可用于清仓）、报废则核销并计入损耗。」输入：「质检结论；处置决定；
 * {@code expected_version}。」输出：「库存状态变更分录；可售量更新；报废损耗记录。」
 * 加粗句：「<b>报废是不可逆的</b>，需审批与原因，不能由客服随手处理。」
 *
 * <b>这一层对那条加粗句负三件事</b>：
 *
 * <ol>
 *   <li><b>数量不是输入框</b>：处置数量由质检结论决定（{@link createReturnInventoryDisposition}
 *       仍然把它传上去，但那是「与结论一致」的断言，不是让操作员自己填）。
 *       一个可填的数量等于允许「只处置一半」—— 那一半从此永远没有结论。</li>
 *   <li><b>报废的入口是「起草 → 提交 → 审批 → 执行」四步</b>，不是一个按钮：
 *       {@link canSubmit} / {@link canApprove} / {@link canExecute} / {@link canCancel}
 *       把状态机的合法流转写在代码里，页面只渲染当前状态允许的动作。</li>
 *   <li><b>财务侧的损耗字段前端一个都不填</b>：{@link ReturnInventoryDispositionLossRow}
 *       里的 {@code financeLossAmount} 恒为 null（12 财务未交付），
 *       页面照实显示「待 12 财务确认」，<b>不显示 0</b> —— 0 会被读成「这批货零损耗」。</li>
 * </ol>
 */
import type {
  ReturnInventoryDispositionCapabilities,
  ReturnInventoryDispositionCreate,
  ReturnInventoryDispositionDecision,
  ReturnInventoryDispositionCancel,
  ReturnInventoryDispositionDetail,
  ReturnInventoryDispositionExecute,
  ReturnInventoryDispositionInstructionPage,
  ReturnInventoryDispositionOptions,
  ReturnInventoryDispositionPage,
  ReturnInventoryDispositionSubmit,
  ReturnInventoryDispositionUpdate,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError } from '../../lib/api';

/** 与后端 {@code /trade/return-dispositions} 同前缀。 */
const base = 'trade/return-dispositions';

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  // ⚠️ exactOptionalPropertyTypes 开着（AGENTS.md）：不能把 `headers: undefined`
  //   直接塞进 RequestInit —— 那不是「没传」，而是「传了个 undefined」。
  const response = init
    ? await fetch(`/api/${path}`, {
        credentials: 'include',
        ...init,
        ...(init.body ? { headers: { 'Content-Type': 'application/json' } } : {}),
      })
    : await fetch(`/api/${path}`, { credentials: 'include' });
  if (!response.ok) {
    throw new ApiError(response.status, await response.text());
  }
  return (await response.json()) as T;
}

/** 把筛选对象编成 query string（空串与 undefined 都跳过）。 */
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

export const fetchReturnDispositionCapabilities = () =>
  call<ReturnInventoryDispositionCapabilities>(`${base}/capabilities`);

// ==========================================================================
// 待处置的质量状态指令（11.2.3.1 产出，本模块消费）
// ==========================================================================

export const fetchPendingInstructions = (params: { page?: number; size?: number; skuCode?: string } = {}) =>
  call<ReturnInventoryDispositionInstructionPage>(`${base}/instructions${query(params)}`);

// ==========================================================================
// 处置选项（候选来源库位 + 候选目标库位，来自 warehouse 的真数据）
// ==========================================================================

export const fetchDispositionOptions = (params: { skuId?: string; targetState?: string } = {}) =>
  call<ReturnInventoryDispositionOptions>(`${base}/options${query(params)}`);

// ==========================================================================
// 处置单
// ==========================================================================

export const fetchReturnDispositions = (params: {
  page?: number;
  size?: number;
  status?: string;
  targetState?: string;
  skuCode?: string;
  keyword?: string;
} = {}) => call<ReturnInventoryDispositionPage>(`${base}${query(params)}`);

export const fetchReturnDisposition = (id: string) =>
  call<ReturnInventoryDispositionDetail>(`${base}/${id}`);

export const createReturnInventoryDisposition = (body: ReturnInventoryDispositionCreate) =>
  call<ReturnInventoryDispositionDetail>(base, {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const updateReturnInventoryDisposition = (
  id: string,
  body: ReturnInventoryDispositionUpdate,
) =>
  call<ReturnInventoryDispositionDetail>(`${base}/${id}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });

export const submitReturnInventoryDisposition = (id: string, body: ReturnInventoryDispositionSubmit) =>
  call<ReturnInventoryDispositionDetail>(`${base}/${id}/submit`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const approveReturnInventoryDisposition = (
  id: string,
  body: ReturnInventoryDispositionDecision,
) =>
  call<ReturnInventoryDispositionDetail>(`${base}/${id}/approve`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const rejectReturnInventoryDisposition = (id: string, body: ReturnInventoryDispositionDecision) =>
  call<ReturnInventoryDispositionDetail>(`${base}/${id}/reject`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const cancelReturnInventoryDisposition = (id: string, body: ReturnInventoryDispositionCancel) =>
  call<ReturnInventoryDispositionDetail>(`${base}/${id}/cancel`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const executeReturnInventoryDisposition = (id: string, body: ReturnInventoryDispositionExecute) =>
  call<ReturnInventoryDispositionDetail>(`${base}/${id}/execute`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

// ==========================================================================
// 状态机（前端镜像）
// ==========================================================================

export const DISPOSITION_STATUS_OPTIONS = [
  { code: 'DRAFT', label: '草稿' },
  { code: 'PENDING_APPROVAL', label: '待审批' },
  { code: 'APPROVED', label: '已批待执行' },
  { code: 'REJECTED', label: '已驳回' },
  { code: 'EXECUTED', label: '已执行' },
  { code: 'EXECUTION_FAILED', label: '执行失败' },
  { code: 'CANCELLED', label: '已撤销' },
] as const;

export const TARGET_STATE_OPTIONS = [
  { code: 'SELLABLE', label: '可售' },
  { code: 'DEFECTIVE', label: '残次' },
  { code: 'SCRAP', label: '报废' },
] as const;

export const SCRAP_REASON_OPTIONS = [
  { code: 'DAMAGED_UNREPAIRABLE', label: '损坏不可修复' },
  { code: 'MISSING_PARTS', label: '缺少配件' },
  { code: 'FAILED_FUNCTIONAL_TEST', label: '功能检测不通过' },
  { code: 'BEYOND_ECONOMIC_REPAIR', label: '修复不经济' },
  { code: 'OTHER', label: '其它' },
] as const;

/** 当前状态能不能提交待审（只有草稿能）。 */
export const canSubmit = (status: string) => status === 'DRAFT';

/** 当前状态能不能审批（只有待审能）。 */
export const canApprove = (status: string) => status === 'PENDING_APPROVAL';

/** 当前状态能不能执行（已批或执行失败可重试）。 */
export const canExecute = (status: string) => status === 'APPROVED' || status === 'EXECUTION_FAILED';

/** 当前状态能不能撤销（终态不可撤销）。 */
export const canCancel = (status: string) =>
  status === 'DRAFT' ||
  status === 'PENDING_APPROVAL' ||
  status === 'APPROVED' ||
  status === 'EXECUTION_FAILED';

/**
 * 报废为什么必须多两道手续（页面上的提示文案）。
 *
 * <p>蓝图加粗句「报废是不可逆的，需审批与原因，不能由客服随手处理」在界面上的落点：
 * 报废单**永远**要经过「提交 → 他人审批 → 执行」，而且必填损耗原因码。
 */
export const SCRAP_IRREVERSIBLE_NOTE =
  '报废不可逆：执行后这批货被核销、库存分录已落库，没有反执行入口。' +
  '报废必须先提交待审、由另一个人审批，并写清损耗原因码。';

/** 报废单的必填项是否齐了（原因码 + 说明）。 */
export const scrapReady = (scrapReasonCode: string, reasonNote: string) =>
  scrapReasonCode.trim() !== '' && reasonNote.trim() !== '';

/** 转可售 / 转残次是否需要目标库位（报废没有目标：货被核销，不在任何库位上）。 */
export const requiresTargetLocation = (targetState: string) => targetState !== 'SCRAP';

/**
 * 报废损耗记录里的「财务侧」是不是还空着。
 *
 * <p>⚠️ 页面据此显示「待 12 财务确认」而不是 0 ——
 * 12 财务服务尚未交付，损耗金额恒为 null；填 0 会让财务以为这批报废货零损耗。
 */
export const financePending = (loss: {
  financeLossAmount: number | null;
  financeReference: string | null;
} | null) => !!loss && loss.financeLossAmount === null && !loss.financeReference;

/** 处置单状态的中文标签。 */
export const statusLabel = (status: string): string =>
  DISPOSITION_STATUS_OPTIONS.find((o) => o.code === status)?.label ?? status;

/** 目标状态的中文标签。 */
export const targetStateLabel = (state: string): string =>
  TARGET_STATE_OPTIONS.find((o) => o.code === state)?.label ?? state;

/** 报废原因码的中文标签。 */
export const scrapReasonLabel = (code: string): string =>
  SCRAP_REASON_OPTIONS.find((o) => o.code === code)?.label ?? code;
