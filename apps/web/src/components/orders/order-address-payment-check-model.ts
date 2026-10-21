import type {
  AddressPaymentCapabilities,
  AddressPaymentCheck,
  AddressPaymentCheckDetail,
  AddressPaymentCheckInput,
  AddressPaymentCheckItem,
  AddressPaymentCheckPage,
  AddressPaymentCheckSaved,
  AddressPaymentCountryRule,
  AddressPaymentCountryRuleInput,
  AddressPaymentCountryRulePage,
  AddressPaymentEmbargoRule,
  AddressPaymentEmbargoRuleInput,
  AddressPaymentEmbargoRulePage,
  AddressPaymentOverview,
  AddressPaymentPaymentRule,
  AddressPaymentPaymentRuleInput,
  AddressPaymentPaymentRulePage,
  AddressPaymentPolicy,
  AddressPaymentPolicyInput,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '../../lib/api';

/**
 * 9.2.2.1 地址与支付校验前端模型。
 *
 * <p>蓝图：「校验买家地址可投递性（国家/邮编/街道格式）、禁运国家清单、支付状态一致性
 * （平台已付/货到付款/未付款）。输出：校验结果 + 风险等级。」
 * 蓝图加粗句：「<b>货到付款订单的履约风险最高</b>，需额外风控与保险策略。」
 *
 * <p><b>界面上最容易做错、本模型显式处理的几处</b>：
 *
 * <ul>
 *   <li><b>输出是两件，不是一件。</b>{@code deliveryStatus}（校验结果）与
 *       {@code riskLevel}（风险等级）必须<b>分行显示</b>：货到付款单的地址照样可投递，
 *       合成一个字段的话，界面上就会出现「不可投递的货到付款单」这种自相矛盾的说法。</li>
 *   <li><b>「最高风险」是货到付款专属。</b>{@link riskText} 对 CRITICAL 永远带
 *       「货到付款」字样 —— 这一档有了第二个来源，界面上就不再是货到付款最高。</li>
 *   <li><b>保险策略未交付要说出来。</b>{@link itemTone} 对
 *       {@code COD_INSURANCE_POLICY} 恒给「未交付」色调，
 *       不回落成「通过」或「不适用」—— 回落成「通过」等于宣称「保险没问题」。</li>
 *   <li><b>「没量到」不是 0。</b>后端在该项不可用时返回 {@code observedValue: null} 且
 *       {@code status} 说明为什么（缺参照 / 上游未交付 / 不适用）。渲染成 0 等于宣称
 *       「量过了，结论是没问题」。</li>
 *   <li><b>禁运清单为空不等于「都通过」。</b>{@link embargoListNote} 在清单为空时
 *       显式提示「没有禁运任何国家」—— 空清单是租户的选择，不是「没有禁运风险」。</li>
 * </ul>
 */

// ------------------------------------------------------------ 参数

// ⚠️ 每个可选字段都显式带 `| undefined`：仓库开了 exactOptionalPropertyTypes，
// 写成 `keyword?: string` 时「传一个值为 undefined 的 key」本身就是类型错误，
// 而 `keyword || undefined` 正是界面上最自然的清空写法。
export interface CheckParams {
  keyword?: string | undefined;
  riskLevel?: string | undefined;
  deliveryStatus?: string | undefined;
  orderId?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface EmbargoParams {
  keyword?: string | undefined;
  embargoLevel?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface CountryRuleParams {
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface PaymentRuleParams {
  paymentMethod?: string | undefined;
  observedStatus?: string | undefined;
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
const base = 'trade/order-address-payment-check';

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

export function fetchCapabilities(): Promise<AddressPaymentCapabilities> {
  return api<AddressPaymentCapabilities>(path('/capabilities'));
}

export function fetchOverview(): Promise<AddressPaymentOverview> {
  return api<AddressPaymentOverview>(path('/overview'));
}

export function fetchPolicy(): Promise<AddressPaymentPolicy> {
  return api<AddressPaymentPolicy>(path('/policy'));
}

export function savePolicy(input: AddressPaymentPolicyInput): Promise<AddressPaymentPolicy> {
  return api<AddressPaymentPolicy>(path('/policy'), 'PUT', input);
}

export function fetchEmbargoRules(
  params: EmbargoParams,
): Promise<AddressPaymentEmbargoRulePage> {
  return api<AddressPaymentEmbargoRulePage>(path('/embargo-rules', params));
}

export function saveEmbargoRule(
  input: AddressPaymentEmbargoRuleInput,
): Promise<AddressPaymentEmbargoRule> {
  return api<AddressPaymentEmbargoRule>(path('/embargo-rules'), 'POST', input);
}

export function fetchCountryRules(
  params: CountryRuleParams,
): Promise<AddressPaymentCountryRulePage> {
  return api<AddressPaymentCountryRulePage>(path('/country-rules', params));
}

export function saveCountryRule(
  input: AddressPaymentCountryRuleInput,
): Promise<AddressPaymentCountryRule> {
  return api<AddressPaymentCountryRule>(path('/country-rules'), 'POST', input);
}

export function fetchPaymentRules(
  params: PaymentRuleParams,
): Promise<AddressPaymentPaymentRulePage> {
  return api<AddressPaymentPaymentRulePage>(path('/payment-rules', params));
}

export function savePaymentRule(
  input: AddressPaymentPaymentRuleInput,
): Promise<AddressPaymentPaymentRule> {
  return api<AddressPaymentPaymentRule>(path('/payment-rules'), 'POST', input);
}

export function fetchChecks(params: CheckParams): Promise<AddressPaymentCheckPage> {
  return api<AddressPaymentCheckPage>(path('/checks', params));
}

export function fetchCheck(checkId: string): Promise<AddressPaymentCheckDetail> {
  return api<AddressPaymentCheckDetail>(path(`/checks/${checkId}`));
}

export function createCheck(input: AddressPaymentCheckInput): Promise<AddressPaymentCheckSaved> {
  return api<AddressPaymentCheckSaved>(path('/checks'), 'POST', input);
}

/**
 * 某订单最近一次校验 —— **9.2.1.3 异常拦截的读路径**。
 *
 * <p>跨服务消费者（例如 10 物流的渠道可用性）走这个授权 API，而不是跨库读表。
 */
export function fetchLatestForOrder(orderId: string): Promise<AddressPaymentCheckDetail> {
  return api<AddressPaymentCheckDetail>(path(`/orders/${orderId}/latest`));
}

// ------------------------------------------------------------ 展示文案

export const ITEM_CODES = [
  { value: 'COUNTRY_EMBARGO', label: '收货国禁运' },
  { value: 'POSTCODE_FORMAT', label: '邮编格式' },
  { value: 'STREET_FORMAT', label: '街道格式' },
  { value: 'REQUIRED_CONTACT', label: '必填联系方式' },
  { value: 'PAYMENT_CONSISTENCY', label: '支付状态一致性' },
  { value: 'COD_FULFILLMENT_RISK', label: '货到付款履约风险' },
  { value: 'COD_INSURANCE_POLICY', label: '货到付款保险策略' },
] as const;

/** 判读顺序：先地址，后支付，最后两行货到付款专项。 */
export const ITEM_ORDER = [
  'COUNTRY_EMBARGO',
  'POSTCODE_FORMAT',
  'STREET_FORMAT',
  'REQUIRED_CONTACT',
  'PAYMENT_CONSISTENCY',
  'COD_FULFILLMENT_RISK',
  'COD_INSURANCE_POLICY',
] as const;

export const RISK_LEVELS = [
  { value: 'LOW', label: '低风险' },
  { value: 'MEDIUM', label: '中风险' },
  { value: 'HIGH', label: '高风险' },
  { value: 'CRITICAL', label: '最高风险（货到付款专属）' },
] as const;

export const DELIVERY_STATUSES = [
  { value: 'DELIVERABLE', label: '可投递' },
  { value: 'NOT_DELIVERABLE', label: '不可投递' },
  { value: 'UNDETERMINED', label: '未定（缺参照）' },
] as const;

export const EMBARGO_LEVELS = [
  { value: 'BLOCK', label: '不可发' },
  { value: 'REVIEW', label: '可疑（需人工或渠道确认）' },
] as const;

export const RULE_SOURCES = [
  { value: 'TENANT_MAINTAINED', label: '租户自维护' },
  { value: 'MODULE_16_COMPLIANCE', label: '16 合规国别禁运清单（未交付）' },
] as const;

export const PAYMENT_STATUSES = [
  { value: 'PAID', label: '已付' },
  { value: 'UNPAID', label: '未付' },
  { value: 'PARTIAL', label: '部分付款' },
  { value: 'COD', label: '货到付款' },
] as const;

export const PAYMENT_STATUS_SOURCES = [
  { value: 'PLATFORM_CALLBACK', label: '平台回调' },
  { value: 'TENANT_REPORTED', label: '租户人工登记' },
  { value: 'FINANCE_LEDGER', label: '财务台账' },
] as const;

export const PAYMENT_METHODS = [
  { value: 'PREPAID', label: '预付' },
  { value: 'COD', label: '货到付款' },
  { value: 'BANK_TRANSFER', label: '银行转账' },
  { value: 'CREDIT_TERM', label: '账期' },
  { value: 'FREIGHT_FORWARDER', label: '货代代收' },
  { value: 'OTHER', label: '其它' },
] as const;

export const RULE_SEVERITIES = [
  { value: 'OK', label: '自洽' },
  { value: 'WARN', label: '矛盾，需人工核对' },
  { value: 'BLOCK', label: '矛盾，不可放行' },
] as const;

export const MISSING_REFERENCE_POLICIES = [
  { value: 'ASSESS_KNOWN_ONLY', label: '按已知项判定' },
  { value: 'BLOCK_DECISION', label: '缺参照即不出结论' },
] as const;

export function itemCodeText(code: string): string {
  return ITEM_CODES.find((c) => c.value === code)?.label ?? code;
}

export function riskText(value: string): string {
  return RISK_LEVELS.find((c) => c.value === value)?.label ?? value;
}

export function deliveryText(value: string): string {
  return DELIVERY_STATUSES.find((c) => c.value === value)?.label ?? value;
}

export function embargoLevelText(value: string): string {
  return EMBARGO_LEVELS.find((c) => c.value === value)?.label ?? value;
}

export function ruleSourceText(value: string): string {
  return RULE_SOURCES.find((c) => c.value === value)?.label ?? value;
}

export function paymentStatusText(value: string | null | undefined): string {
  if (!value) return '未提供';
  return PAYMENT_STATUSES.find((c) => c.value === value)?.label ?? value;
}

export function paymentStatusSourceText(value: string | null | undefined): string {
  if (!value) return '—';
  return PAYMENT_STATUS_SOURCES.find((c) => c.value === value)?.label ?? value;
}

export function paymentMethodText(value: string): string {
  return PAYMENT_METHODS.find((c) => c.value === value)?.label ?? value;
}

export function ruleSeverityText(value: string): string {
  return RULE_SEVERITIES.find((c) => c.value === value)?.label ?? value;
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
  WARN: '需人工核对',
  BLOCK: '不可放行',
  CRITICAL: '最高风险（货到付款专属）',
};

const EVIDENCE_TEXT: Record<string, string> = {
  ADDRESS_SNAPSHOT: '收货地址快照（V99）',
  TENANT_EMBARGO_LIST: '租户禁运清单',
  TENANT_COUNTRY_RULE: '租户国别格式规则',
  TENANT_PAYMENT_RULE: '租户支付一致性规则',
  ORDER_PAYMENT_METHOD: '订单付款方式',
  CALLER_PAYMENT_STATUS: '调用方提供的支付状态',
  MODULE_10_INSURANCE_NOT_DELIVERED: '货到付款保险策略（未交付）',
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
 * 明细行的色调。
 *
 * <p>⚠️ 保险策略那一项**没有「通过」这一档**：它是「未交付」，
 * 回落成绿色等于宣称「保险没问题」—— 那正是蓝图加粗句点名要防的事。
 */
export type ItemTone = 'ok' | 'warn' | 'block' | 'critical' | 'unknown' | 'muted';

export function itemTone(item: AddressPaymentCheckItem): ItemTone {
  if (item.status === 'UPSTREAM_UNAVAILABLE') return 'unknown';
  if (item.status === 'NO_REFERENCE') return 'muted';
  if (item.status === 'NOT_APPLICABLE') return 'muted';
  if (item.status === 'CLEARED') return 'ok';
  if (item.severity === 'CRITICAL') return 'critical';
  if (item.severity === 'BLOCK') return 'block';
  if (item.severity === 'WARN') return 'warn';
  return 'muted';
}

/**
 * 「没量到」与「量到了是 0」必须渲染成两句话。
 *
 * <p>返回 {@code null} 时调用方显示「未测到」，**不显示 0**：把 null 渲染成 0
 * 等于宣称「量过了，结论是没问题」。
 */
export function observedText(value: number | null | undefined): string | null {
  if (value === null || value === undefined) return null;
  return String(value);
}

/** 风险等级的色调。CRITICAL（货到付款）单独一档，不与 HIGH 混。 */
export type RiskTone = 'ok' | 'warn' | 'block' | 'critical';

export function riskTone(level: string): RiskTone {
  if (level === 'CRITICAL') return 'critical';
  if (level === 'HIGH') return 'block';
  if (level === 'MEDIUM') return 'warn';
  return 'ok';
}

/** 校验结果的色调。UNDETERMINED（缺参照）不是「可投递」。 */
export type DeliveryTone = 'ok' | 'block' | 'unknown';

export function deliveryTone(status: string): DeliveryTone {
  if (status === 'DELIVERABLE') return 'ok';
  if (status === 'NOT_DELIVERABLE') return 'block';
  return 'unknown';
}

/**
 * 禁运清单为空时的提示。
 *
 * <p>空清单 = 租户没有禁运任何国家，**不是**「都通过」——
 * 界面上必须把这两种读法分开。
 */
export function embargoListNote(count: number): string {
  if (count === 0) {
    return '禁运清单为空：本租户当前没有禁运任何国家（这是租户自己的选择，不是「没有禁运风险」）。';
  }
  return `禁运清单共 ${count} 条。`;
}

/** 缺参照计数 > 0 时的提示：让人知道「有几项压根没量过」。 */
export function missingNote(check: AddressPaymentCheck): string | null {
  if (check.noReferenceCount > 0) {
    return `有 ${check.noReferenceCount} 项缺参照（没量到，不是「量过了、没问题」）。`;
  }
  return null;
}

/** 保险策略未交付的提示（货到付款单才有）。 */
export function insuranceGapNote(check: AddressPaymentCheck): string | null {
  if (check.codFlagged && check.upstreamUnavailableCount > 0) {
    return '货到付款单的保险策略在本系统里未交付：风险等级已按最高档给出，但该投保哪一档在本系统无处可查。';
  }
  return null;
}

export function money(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  return Number(value).toFixed(4);
}

export function isoText(value: string | null | undefined): string {
  if (!value) return '—';
  return new Date(value).toLocaleString('zh-CN');
}

/**
 * 分页摘要的最小形状。
 *
 * <p>刻意**不收整个 Page 类型**：工作区里有些地方只握着分页元数据（没有 items），
 * 收窄成这个形状之后两处都能用，不必为了凑类型造一个假的空 items 数组。
 */
export interface PageMeta {
  total: number;
  page: number;
  totalPages: number;
}

function metaText(page: PageMeta): string {
  if (page.total === 0) return '共 0 条';
  return `第 ${page.page + 1} / ${page.totalPages} 页 · 共 ${page.total} 条`;
}

export function pageText(page: PageMeta): string {
  return metaText(page);
}

export function embargoPageText(page: PageMeta): string {
  return metaText(page);
}

export function countryRulePageText(page: PageMeta): string {
  return metaText(page);
}

export function paymentRulePageText(page: PageMeta): string {
  return metaText(page);
}

/** 按判读顺序排列七项明细（后端返回的是按 itemCode 排序的，不是判读顺序）。 */
export function sortItems(items: AddressPaymentCheckItem[]): AddressPaymentCheckItem[] {
  return [...items].sort(
    (left, right) => ITEM_ORDER.indexOf(left.itemCode as never) - ITEM_ORDER.indexOf(right.itemCode as never),
  );
}

export type {
  AddressPaymentCheck,
  AddressPaymentCheckDetail,
  AddressPaymentCheckItem,
  AddressPaymentCountryRule,
  AddressPaymentEmbargoRule,
  AddressPaymentPaymentRule,
};
