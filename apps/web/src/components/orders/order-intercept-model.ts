import type {
  InterceptCapabilities,
  InterceptCheck,
  InterceptCheckInput,
  InterceptCheckPage,
  InterceptCheckSaved,
  InterceptDetail,
  InterceptDisposition,
  InterceptDispositionInput,
  InterceptDispositionSaved,
  InterceptFinding,
  InterceptPolicy,
  InterceptPolicyInput,
  InterceptRestriction,
  InterceptRestrictionInput,
  InterceptRestrictionPage,
  InterceptSkuCost,
  InterceptSkuCostInput,
  InterceptSkuCostPage,
  InterceptSummary,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '../../lib/api';

/**
 * 9.2.1.3 异常拦截前端模型。
 *
 * <p>蓝图：「识别并拦截异常订单：地址不可达/信息不全、黑名单买家、重复下单、异常折扣（低于成本）、
 * 疑似欺诈、超出收货国家限制、平台合规风险。输出：拦截清单 + 拦截原因 + 处置建议（取消/转人工/放行）。」
 * 蓝图加粗句：「<b>拦截不自动取消</b>，默认转人工——误拦真实订单的代价通常高于人工看一眼。」
 *
 * <p><b>界面上最容易做错、本模型显式处理的几处</b>：
 *
 * <ul>
 *   <li><b>「建议取消」不能显示成「已取消」。</b>{@code recommendedDisposition} 是判定给的建议，
 *       {@code finalDisposition} 是人工给的结论，未处置时是 null。两者必须<b>分行显示</b>，
 *       且建议取消那一行永远带「尚无人工结论」字样 —— 界面上把两者合成一个「已取消」徽标，
 *       等于在 UI 层把蓝图加粗句又违反一次（客服据此回话给买家，后果由公司承担）。</li>
 *   <li><b>「没量到」不是 0。</b>后端在该项不可用时返回 {@code observedValue: null} 且
 *       {@code status} 说明为什么（缺参照 / 上游未交付 / 不适用）。渲染成 0 等于宣称
 *       「量过了，结论是没有异常」—— 那正是误拦之外的另一种真实损失。</li>
 *   <li><b>八项恒定各一行。</b>详情页按判读顺序（地址 → 买家 → 订单特征 → 合规）呈现，
 *       缺参照与上游未接通的项也在列表里，只是色调不同 —— 让运营一眼看出
 *       「这一项压根没量过」与「这一项量了、没问题」。</li>
 *   <li><b>上游缺口要显形。</b>capabilities 里 {@code upstreamDelivered = false} 的项
 *       带一句话说明它靠什么撑住，让运营知道这几项的数据是租户自己维护的，
 *       而不是系统从 9.2.2.1 / 16 合规读来的。</li>
 * </ul>
 */

// ------------------------------------------------------------ 参数

// ⚠️ 每个可选字段都显式带 `| undefined`：仓库开了 exactOptionalPropertyTypes，
// 写成 `keyword?: string` 时「传一个值为 undefined 的 key」本身就是类型错误，
// 而 `keyword || undefined` 正是界面上最自然的清空写法。
export interface CheckParams {
  keyword?: string | undefined;
  recommendedDisposition?: string | undefined;
  checkCode?: string | undefined;
  pendingDispositionOnly?: boolean | undefined;
  buyerKey?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface RestrictionParams {
  keyword?: string | undefined;
  restrictionKind?: string | undefined;
  restrictionEffect?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface SkuCostParams {
  keyword?: string | undefined;
  skuId?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

function query<T extends object>(params: T): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params as Record<string, unknown>)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

/**
 * 后端命名空间。
 *
 * <p>⚠️ **不带前导斜杠**：api() 会自己拼 `/api/`，写成 `/trade/...` 会变成 `/api//trade/...`。
 */
const base = 'trade/order-intercept';

function path<T extends object>(extra: string, params?: T): string {
  const target = `${base}${extra}`;
  const text = params ? query(params) : '';
  return text ? `${target}${text}` : target;
}

// ------------------------------------------------------------ 接口

export function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return String(error);
}

export function fetchCapabilities(): Promise<InterceptCapabilities> {
  return api<InterceptCapabilities>(path('/capabilities'));
}

export function fetchOverview(): Promise<InterceptSummary> {
  return api<InterceptSummary>(path('/overview'));
}

export function fetchPolicy(): Promise<InterceptPolicy> {
  return api<InterceptPolicy>(path('/policy'));
}

export function savePolicy(input: InterceptPolicyInput): Promise<InterceptPolicy> {
  return api<InterceptPolicy>(path('/policy'), 'PUT', input);
}

export function fetchRestrictions(
  params: RestrictionParams,
): Promise<InterceptRestrictionPage> {
  return api<InterceptRestrictionPage>(path('/restrictions', params));
}

export function saveRestriction(
  input: InterceptRestrictionInput,
): Promise<InterceptRestriction> {
  return api<InterceptRestriction>(path('/restrictions'), 'POST', input);
}

export function fetchSkuCosts(params: SkuCostParams): Promise<InterceptSkuCostPage> {
  return api<InterceptSkuCostPage>(path('/sku-costs', params));
}

export function saveSkuCost(input: InterceptSkuCostInput): Promise<InterceptSkuCost> {
  return api<InterceptSkuCost>(path('/sku-costs'), 'POST', input);
}

export function fetchChecks(params: CheckParams): Promise<InterceptCheckPage> {
  return api<InterceptCheckPage>(path('/checks', params));
}

export function fetchCheck(checkId: string): Promise<InterceptDetail> {
  return api<InterceptDetail>(path(`/checks/${checkId}`));
}

export function createCheck(input: InterceptCheckInput): Promise<InterceptCheckSaved> {
  return api<InterceptCheckSaved>(path('/checks'), 'POST', input);
}

/**
 * 提交人工处置 —— **取消的唯一合法入口**。
 *
 * <p>刻意**不提供**批量处置与「一键放行」：批量处置正是蓝图加粗句点名要避免的那种动作。
 */
export function submitDisposition(
  checkId: string,
  input: InterceptDispositionInput,
): Promise<InterceptDispositionSaved> {
  return api<InterceptDispositionSaved>(path(`/checks/${checkId}/disposition`), 'POST', input);
}

// ------------------------------------------------------------ 展示文案

export const CHECK_CODES = [
  { value: 'ADDRESS_INCOMPLETE', label: '地址信息不全' },
  { value: 'ADDRESS_DELIVERABILITY', label: '地址不可达' },
  { value: 'BLACKLISTED_BUYER', label: '黑名单买家' },
  { value: 'DUPLICATE_ORDER', label: '重复下单' },
  { value: 'BELOW_COST_DISCOUNT', label: '异常折扣（低于成本）' },
  { value: 'SUSPECTED_FRAUD', label: '疑似欺诈' },
  { value: 'COUNTRY_RESTRICTED', label: '超出收货国家限制' },
  { value: 'PLATFORM_COMPLIANCE', label: '平台合规风险' },
] as const;

export const RECOMMENDED_DISPOSITIONS = [
  { value: 'RELEASE', label: '放行' },
  { value: 'MANUAL_REVIEW', label: '转人工' },
  { value: 'CANCEL', label: '建议取消（仍需人工）' },
] as const;

export const FINAL_DISPOSITIONS = [
  { value: 'RELEASE', label: '人工确认放行（误拦）' },
  { value: 'HOLD', label: '人工确认挂起' },
  { value: 'CANCEL', label: '人工确认取消' },
] as const;

export const REASON_CODES = [
  { value: 'CONFIRMED_ANOMALY', label: '复核确认确有异常' },
  { value: 'FALSE_POSITIVE', label: '误拦：真实订单被错误命中' },
  { value: 'PENDING_EVIDENCE', label: '仍需材料' },
  { value: 'ESCALATED_COMPLIANCE', label: '升级 16 合规处理' },
] as const;

export const RESTRICTION_KINDS = [
  { value: 'SHIPPING_COUNTRY', label: '收货国家限制' },
  { value: 'PLATFORM_SOURCE', label: '平台来源限制' },
] as const;

export const RESTRICTION_EFFECTS = [
  { value: 'BLOCK', label: '不可发（建议取消，仍需人工）' },
  { value: 'REVIEW', label: '可疑（建议转人工）' },
] as const;

export const MISSING_REFERENCE_POLICIES = [
  { value: 'ASSESS_KNOWN_ONLY', label: '按已知项判定' },
  { value: 'BLOCK_DECISION', label: '缺参照即不出放行' },
] as const;

export const RESTRICTION_SOURCES = [
  { value: 'TENANT_MAINTAINED', label: '租户自维护' },
  { value: 'MODULE_9_2_2_1_SHIPPING', label: '9.2.2.1 禁运国家清单（未交付）' },
  { value: 'MODULE_16_PLATFORM_COMPLIANCE', label: '16 合规平台合规清单（未交付）' },
  { value: 'MODULE_2_PLATFORM_POLICY', label: '2 平台来源合规策略（未交付）' },
] as const;

export function checkCodeText(code: string): string {
  return CHECK_CODES.find((c) => c.value === code)?.label ?? code;
}

export function recommendedText(value: string): string {
  return RECOMMENDED_DISPOSITIONS.find((c) => c.value === value)?.label ?? value;
}

export function finalText(value: string | null): string {
  if (!value) return '尚无人工结论';
  return FINAL_DISPOSITIONS.find((c) => c.value === value)?.label ?? value;
}

export function reasonCodeText(value: string): string {
  return REASON_CODES.find((c) => c.value === value)?.label ?? value;
}

export function restrictionKindText(value: string): string {
  return RESTRICTION_KINDS.find((c) => c.value === value)?.label ?? value;
}

export function restrictionEffectText(value: string): string {
  return RESTRICTION_EFFECTS.find((c) => c.value === value)?.label ?? value;
}

export function restrictionSourceText(value: string): string {
  return RESTRICTION_SOURCES.find((c) => c.value === value)?.label ?? value;
}

export function missingPolicyText(value: string): string {
  return MISSING_REFERENCE_POLICIES.find((c) => c.value === value)?.label ?? value;
}

const STATUS_TEXT: Record<string, string> = {
  FLAGGED: '命中',
  CLEARED: '已核对，未命中',
  NO_REFERENCE: '缺参照',
  UPSTREAM_UNAVAILABLE: '上游未交付',
  NOT_APPLICABLE: '不适用',
};

const SEVERITY_TEXT: Record<string, string> = {
  INFO: '仅提示',
  WARN: '建议转人工',
  BLOCK: '建议取消（仍需人工）',
};

const EVIDENCE_TEXT: Record<string, string> = {
  DERIVED_FROM_ORDERS: '本服务订单簇现算',
  TENANT_MAINTAINED: '租户自维护',
  MODULE_9_2_1_2_BUYER_PROFILE: '9.2.1.2 买家档案',
  MODULE_9_2_2_2_RISK_ASSESSMENT: '9.2.2.2 风控评估',
  UPSTREAM_NOT_DELIVERED: '上游未交付',
};

export function statusText(value: string): string {
  return STATUS_TEXT[value] ?? value;
}

export function severityText(value: string): string {
  return SEVERITY_TEXT[value] ?? value;
}

export function evidenceText(value: string): string {
  return EVIDENCE_TEXT[value] ?? value;
}

/**
 * 「没量到」与「量到了是 0」必须渲染成两句话。
 *
 * <p>返回 {@code null} 时调用方显示「未测到」，**不显示 0**：把 null 渲染成 0
 * 等于宣称「量过了，结论是没有异常」。
 */
export function observedText(value: number | null | undefined): string | null {
  if (value === null || value === undefined) return null;
  return value.toFixed(4);
}

/** 建议处置是否带「需人工处置」—— 加粗句在列表徽标上的落点。 */
export function needsManual(check: InterceptCheck): boolean {
  return check.manualReviewRequired;
}

/** 尚未处置 = 建议非放行且最终处置为空。 */
export function isPending(check: InterceptCheck): boolean {
  return check.manualReviewRequired && !check.finalDisposition;
}

export function money(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  return Number(value).toFixed(4);
}

export function rate(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  return `${(Number(value) * 100).toFixed(2)}%`;
}

export function isoText(value: string | null | undefined): string {
  if (!value) return '—';
  return new Date(value).toLocaleString('zh-CN');
}

export function pageText(page: InterceptCheckPage): string {
  if (page.total === 0) return '共 0 条';
  return `第 ${page.page + 1} / ${page.totalPages} 页 · 共 ${page.total} 条`;
}

export function restrictionPageText(page: InterceptRestrictionPage): string {
  if (page.total === 0) return '共 0 条';
  return `第 ${page.page + 1} / ${page.totalPages} 页 · 共 ${page.total} 条`;
}

export function skuCostPageText(page: InterceptSkuCostPage): string {
  if (page.total === 0) return '共 0 条';
  return `第 ${page.page + 1} / ${page.totalPages} 页 · 共 ${page.total} 条`;
}

export type { InterceptCheck, InterceptDetail, InterceptFinding, InterceptDisposition };
