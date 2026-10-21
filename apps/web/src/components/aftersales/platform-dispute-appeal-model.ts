/**
 * 11.4.1.2 证据与申诉的前端数据层。
 *
 * 蓝图 11-aftersales-and-returns.md：「组织举证材料（物流签收、沟通记录、商品照片、
 * 检测报告）打包提交申诉，记录平台回复与结果。」
 * 输入：「举证材料；申诉文本（可用模板）；提交动作。」
 * 输出：「申诉提交记录 + 平台回复 + 结果（胜诉/败诉/部分）。」
 * 加粗句：「<b>举证材料必须来自系统记录</b>（轨迹、沟通日志），临时拼凑的证据在申诉中往往不被采信。」
 *
 * <b>这一层对那条加粗句负三件事</b>：
 *
 * <ol>
 *   <li><b>不提供「上传任意文件」的调用</b>：材料只能引用系统里已有的一条事实记录
 *       （{@code sourceModule} + {@code sourceRefId}）。
 *       前端**不造**一个「随便传个文件」的入口 —— 那正是要防的那条路。</li>
 *   <li><b>不提供「证据类型」入参</b>：类型由后端按来源记录推导，
 *       前端传了也会被忽略 —— 让界面上的类型**只显示一个推导出来的结果**，
 *       人就不会误以为「可以自己标类型」。</li>
 *   <li><b>「能不能提交」只认后端给的判据</b>：{@link PlatformAppealGateView} 是
 *       DB 判据函数（{@code platform_dispute_appeal_gate}）的输出，
 *       前端**不自己算一遍时限** —— 界面上的倒计时只是给人看的，
 *       能不能提交以后端判据为准。</li>
 * </ol>
 *
 * <p>⚠️ <b>金额不由前端折算</b>：跨币种**按币种分组**展示，不做固定汇率折算。
 */
import type {
  PlatformAppealCapabilities,
  PlatformAppealCreate,
  PlatformAppealDetail,
  PlatformAppealMaterialCreate,
  PlatformAppealPage,
  PlatformAppealPlatformReply,
  PlatformAppealRetrySubmission,
  PlatformAppealSubmissionAttempt,
  PlatformAppealSubmit,
  PlatformAppealTextUpdate,
  PlatformAppealWithdraw,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError } from '../../lib/api';

/** 与后端 {@code /trade/platform-dispute-appeals} 同前缀。 */
const base = 'trade/platform-dispute-appeals';

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

/**
 * 把筛选对象编成 query string。
 *
 * ⚠️ 空串与 undefined 都要跳过：传一个空 `status=` 会让后端收到
 * 「按空状态筛选」而不是「不筛状态」。
 */
function query(params: Record<string, string | number | boolean | undefined>): string {
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

export const fetchPlatformDisputeAppealCapabilities = () =>
  call<PlatformAppealCapabilities>(`${base}/capabilities`);

// ==========================================================================
// 申诉单
// ==========================================================================

export const fetchPlatformDisputeAppeals = (params: {
  page?: number;
  size?: number;
  status?: string;
  disputeId?: string;
  currency?: string;
  resolutionCode?: string;
  submissionStatus?: string;
  submittableOnly?: boolean;
  from?: string;
  to?: string;
  keyword?: string;
  sort?: string;
} = {}) => call<PlatformAppealPage>(`${base}${query(params)}`);

export const fetchPlatformDisputeAppeal = (id: string) =>
  call<PlatformAppealDetail>(`${base}/${id}`);

/** 建申诉草稿。⚠️ 没有金额入参：主张金额由服务端从纠纷单上读。 */
export const createPlatformDisputeAppeal = (body: PlatformAppealCreate) =>
  call<PlatformAppealDetail>(`${base}`, { method: 'POST', body: JSON.stringify(body) });

export const updatePlatformDisputeAppealText = (
  id: string,
  body: PlatformAppealTextUpdate,
) => call<PlatformAppealDetail>(`${base}/${id}/text`, { method: 'PUT', body: JSON.stringify(body) });

// ==========================================================================
// 证据材料
// ==========================================================================

/**
 * 挂一份证据材料。
 *
 * ⚠️ **只传来源系统的引用**（sourceModule + sourceRefId）——
 * 没有「上传文件」入参，也没有「证据类型」入参：两者都由后端按来源记录推导。
 */
export const attachPlatformDisputeAppealMaterial = (
  id: string,
  body: PlatformAppealMaterialCreate,
) => call<PlatformAppealDetail>(`${base}/${id}/materials`, { method: 'POST', body: JSON.stringify(body) });

export const removePlatformDisputeAppealMaterial = (id: string, materialId: string) =>
  call<PlatformAppealDetail>(`${base}/${id}/materials/${materialId}`, { method: 'DELETE' });

// ==========================================================================
// 提交与结果
// ==========================================================================

export const submitPlatformDisputeAppeal = (id: string, body: PlatformAppealSubmit) =>
  call<PlatformAppealDetail>(`${base}/${id}/submit`, { method: 'POST', body: JSON.stringify(body) });

export const retryPlatformDisputeAppealSubmission = (
  id: string,
  body: PlatformAppealRetrySubmission,
) => call<PlatformAppealDetail>(`${base}/${id}/retry-submission`, { method: 'POST', body: JSON.stringify(body) });

export const recordPlatformDisputeAppealSubmissionAttempt = (
  id: string,
  body: PlatformAppealSubmissionAttempt,
) =>
  call<PlatformAppealDetail>(`${base}/${id}/submission-attempts`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const recordPlatformDisputeAppealReply = (
  id: string,
  body: PlatformAppealPlatformReply,
) => call<PlatformAppealDetail>(`${base}/${id}/platform-reply`, { method: 'POST', body: JSON.stringify(body) });

export const withdrawPlatformDisputeAppeal = (id: string, body: PlatformAppealWithdraw) =>
  call<PlatformAppealDetail>(`${base}/${id}/withdraw`, { method: 'POST', body: JSON.stringify(body) });

// ==========================================================================
// 取值域
// ==========================================================================

export const APPEAL_STATUS_OPTIONS = [
  { code: 'DRAFT', label: '草稿' },
  { code: 'SUBMITTED', label: '已提交平台' },
  { code: 'PLATFORM_REPLIED', label: '已收到平台回复' },
  { code: 'RESOLVED_WON', label: '我方胜诉' },
  { code: 'RESOLVED_LOST', label: '我方败诉' },
  { code: 'RESOLVED_PARTIAL', label: '平台部分支持' },
  { code: 'WITHDRAWN', label: '我方撤诉' },
];

export const SUBMISSION_STATUS_OPTIONS = [
  { code: 'PENDING', label: '待提交' },
  { code: 'SENT', label: '已发出' },
  { code: 'ACKNOWLEDGED', label: '平台已受理' },
  { code: 'FAILED', label: '提交失败' },
];

/**
 * 举证材料的来源系统记录。
 *
 * ⚠️ **没有「上传任意文件」这一档** —— 蓝图加粗句「举证材料必须来自系统记录」。
 */
export const SOURCE_MODULE_OPTIONS = [
  { code: 'SHIPMENT_TRACKING_OBSERVATION', label: '物流轨迹观测（物流签收）' },
  { code: 'AFTERSALES_MESSAGE', label: '售后沟通记录' },
  { code: 'AFTERSALES_ATTACHMENT', label: '售后单附件（商品照片 / 凭证）' },
  { code: 'QUALITY_INSPECTION_ORDER', label: '质检单（检测报告）' },
];

export const APPEAL_RESULT_OPTIONS = [
  { code: 'WON', label: '胜诉' },
  { code: 'LOST', label: '败诉' },
  { code: 'PARTIAL', label: '部分支持' },
];

export const SUBMISSION_OUTCOME_OPTIONS = [
  { code: 'SENT', label: '已发出' },
  { code: 'FAILED', label: '提交失败' },
  { code: 'ACKNOWLEDGED', label: '平台已受理' },
];

// ==========================================================================
// 判据与呈现
// ==========================================================================

/** 提交窗口的徽章色调（超期 / 关闭 = danger，可提交 = success）。 */
export function windowTone(
  windowState: string | null | undefined,
): 'neutral' | 'info' | 'warning' | 'danger' | 'success' {
  switch (windowState) {
    case 'OPEN':
      return 'success';
    case 'EXPIRED':
      return 'danger';
    case 'RELEASED':
      return 'info';
    case 'CLOSED':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** 提交窗口的文案。 */
export function windowText(
  windowState: string | null | undefined,
  windowStateLabel: string | null | undefined,
): string {
  switch (windowState) {
    case 'OPEN':
      return '可以提交申诉';
    case 'EXPIRED':
      return `窗口已关闭（${windowStateLabel ?? '举证时限已过'}）`;
    case 'RELEASED':
      return '举证已提交，时限解除';
    case 'CLOSED':
      return `窗口已关闭（${windowStateLabel ?? '纠纷已终结'}）`;
    default:
      return windowStateLabel ?? '未知';
  }
}

/**
 * 覆盖情况的文案。
 *
 * ⚠️ 「清单说齐了但包里没有材料」是**两回事** —— 界面必须把两个数字分开显示，
 * 否则提交被拒时人不知道该补什么。
 */
export function coverageText(gate: {
  checklistItemCount: number;
  missingRequiredCount: number;
  coveredItemCount: number;
  uncoveredRequiredCount: number;
}): string {
  if (gate.checklistItemCount === 0) return '上游还没列举证清单';
  const parts = [`清单 ${gate.checklistItemCount} 项`];
  if (gate.missingRequiredCount > 0) parts.push(`必举证缺 ${gate.missingRequiredCount} 项`);
  if (gate.uncoveredRequiredCount > 0) parts.push(`未被材料覆盖 ${gate.uncoveredRequiredCount} 项`);
  if (gate.missingRequiredCount === 0 && gate.uncoveredRequiredCount === 0) {
    parts.push(`已覆盖 ${gate.coveredItemCount} 项`);
  }
  return parts.join(' · ');
}

/** 材料来源的展示文案。 */
export function sourceText(material: {
  sourceModule: string;
  sourceModuleLabel: string;
  sourceRefNo: string;
  sourceLabel: string;
  sourceOccurredAt: string;
}): string {
  const ref = material.sourceRefNo ? `（${material.sourceRefNo}）` : '';
  return `${material.sourceModuleLabel}${ref}：${material.sourceLabel || '—'} · ${formatTime(
    material.sourceOccurredAt,
  )}`;
}

/** 从动作清单里找出「为什么不能做」。 */
export function actionBlockedReason(
  actions: { code: string; allowed: boolean; blockingReason: string | null }[],
  code: string,
): string | null {
  const found = actions.find((a) => a.code === code);
  if (!found) return '该动作不在当前模块的动作清单里';
  if (found.allowed) return null;
  return found.blockingReason ?? '当前状态不允许这个动作';
}

/** 终态：不可再改。 */
export function isTerminalStatus(status: string | null | undefined): boolean {
  return (
    status === 'RESOLVED_WON' ||
    status === 'RESOLVED_LOST' ||
    status === 'RESOLVED_PARTIAL' ||
    status === 'WITHDRAWN'
  );
}

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
