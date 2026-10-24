import type {
  FinanceInvoiceBatchInput,
  FinanceInvoiceBatchResult,
  FinanceInvoiceCapabilities,
  FinanceInvoiceCreateInput,
  FinanceInvoiceDetail,
  FinanceInvoiceEventRow,
  FinanceInvoiceLineInput,
  FinanceInvoicePage,
  FinanceInvoicePlatformAcknowledgeInput,
  FinanceInvoicePlatformPayload,
  FinanceInvoicePlatformSubmissionPage,
  FinanceInvoicePlatformSubmissionRow,
  FinanceInvoicePlatformSubmitInput,
  FinanceInvoicePlatformSubmitResult,
  FinanceInvoiceRequestPage,
  FinanceInvoiceReverseInput,
  FinanceInvoiceRow,
  FinanceInvoiceSummary,
  FinanceInvoiceTemplateInput,
  FinanceInvoiceTemplatePage,
  FinanceInvoiceTemplateRow,
  FinanceInvoiceVoidInput,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.6.2.1 发票管理的前端模型。
 *
 * <p>蓝图 12-finance.md：「管理销售发票与贷项通知单：按平台/国家模板生成、维护税码税率税额、
 * 编号与状态（草稿/开具/作废/红冲），支持批量与平台开票对接。」关系：「上游 = 9 订单、12.3.1；
 * 下游 = 12.5.2.1 凭证、11.4 售后（退货开票调整）。<b>各国发票要求差异大</b>，模板必须按国家配置；
 * 本系统不承诺覆盖所有国家税务规则。」
 *
 * <p><b>命名空间</b>：{@code finance/invoices}，与 12.6.1.1 的 {@code finance/tax-rates}、
 * 12.5.2.1 的 {@code finance/vouchers} <b>刻意不共用前缀</b> —— 那是别的模块的命名空间。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>调用方填不出假的税额</b>：建单入参里**没有**税率与税额字段 —— 税率由服务端
 *       实时取（12.6.1.1），税额由数据库生成列算。</li>
 *   <li><b>命中不到就是命中不到</b>：取税率失败时页面显示服务端的原话，
 *       <b>绝不显示 0%</b> —— 「没有税率」和「税率是 0」是两件完全不同的事。</li>
 *   <li><b>多币种绝不相加</b>：汇总**按币种分组**呈现，页面上**不出现**跨币种的合计；
 *       汇率不可得时显示「汇率不可得」，<b>不按 1:1 兜底</b>。</li>
 *   <li><b>红冲成对</b>：贷项通知单永远指向原发票，页面把这一对并排显示。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有录过发票」。</li>
 * </ul>
 */

const base = 'finance/invoices';

/**
 * 发票列表的筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface InvoiceParams {
  status?: string | undefined;
  documentType?: string | undefined;
  currency?: string | undefined;
  templateCode?: string | undefined;
  jurisdictionCode?: string | undefined;
  platformCode?: string | undefined;
  sourceReceivableId?: string | undefined;
  keyword?: string | undefined;
}

/** 模板列表的筛选条件。 */
export interface InvoiceTemplateParams {
  countryCode?: string | undefined;
  platformCode?: string | undefined;
  documentType?: string | undefined;
  status?: string | undefined;
  keyword?: string | undefined;
}

function query(params: Record<string, string | number | boolean | undefined>): string {
  const parts: string[] = [];
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined) continue;
    if (typeof value === 'string' && value === '') continue;
    parts.push(`${key}=${encodeURIComponent(String(value))}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 端点

/** 能力声明：哪些上游真接通、职责怎么切、哪些还没交付、归谁。 */
export function fetchInvoiceCapabilities() {
  return api<FinanceInvoiceCapabilities>(`${base}/capabilities`);
}

/**
 * 筛选项下拉的确定形状。
 *
 * <p>⚠️ 服务端这一条端点返回的是**松散 JSON**（词表 + 两个从真实数据里查出来的列表），
 * 没有进 OpenAPI 契约 ⇒ 前端必须自己收窄，不能直接当成 `string[]` 用 ——
 * 「币种下拉」里混进一个非字符串，页面会在 `.toUpperCase()` 上炸掉。
 */
export interface InvoiceOptions {
  documentTypes: string[];
  statuses: string[];
  platformSyncStatuses: string[];
  currencies: string[];
  templates: { code: string; name: string }[];
}

/** 筛选项下拉。 */
export function fetchInvoiceOptions() {
  return api<Record<string, unknown>>(`${base}/options`);
}

/**
 * 把松散的 options 响应收窄成 {@link InvoiceOptions}。
 *
 * <p>🔴 收窄**不做任何猜测**：形状不对的条目直接丢掉，而不是塞个默认值 ——
 * 「把坏的模板代码当成可用模板」比「下拉里少一个选项」糟糕得多。
 */
export function invoiceOptions(value: unknown): InvoiceOptions {
  const raw = (value ?? {}) as Record<string, unknown>;
  const strings = (key: string): string[] =>
    Array.isArray(raw[key]) ? raw[key].filter((x): x is string => typeof x === 'string') : [];
  const templates: { code: string; name: string }[] = Array.isArray(raw.templates)
    ? raw.templates
        .filter((x): x is Record<string, unknown> => typeof x === 'object' && x !== null)
        .map((x) => ({ code: String(x.code ?? ''), name: String(x.name ?? '') }))
        .filter((x) => x.code !== '')
    : [];
  return {
    documentTypes: strings('documentTypes'),
    statuses: strings('statuses'),
    platformSyncStatuses: strings('platformSyncStatuses'),
    currencies: strings('currencies'),
    templates,
  };
}

/** 发票模板列表（真分页 + 真筛选）。 */
export function fetchInvoiceTemplates(params: InvoiceTemplateParams = {}) {
  return api<FinanceInvoiceTemplatePage>(
    `${base}/templates${query({
      countryCode: params.countryCode,
      platformCode: params.platformCode,
      documentType: params.documentType,
      status: params.status,
      keyword: params.keyword,
    })}`,
  );
}

/** 登记发票模板（🔴 辖区码实时校验，美国辖区录不进去）。 */
export function upsertInvoiceTemplate(input: FinanceInvoiceTemplateInput) {
  return api<FinanceInvoiceTemplateRow>(`${base}/templates`, 'POST', input);
}

/** 发票列表（真分页 + 真筛选）。 */
export function fetchInvoices(params: InvoiceParams = {}) {
  return api<FinanceInvoicePage>(
    `${base}${query({
      status: params.status,
      documentType: params.documentType,
      currency: params.currency,
      templateCode: params.templateCode,
      jurisdictionCode: params.jurisdictionCode,
      platformCode: params.platformCode,
      sourceReceivableId: params.sourceReceivableId,
      keyword: params.keyword,
    })}`,
  );
}

/** 建一张发票草稿（🔴 税率实时校验，调用方填不出假的税额）。 */
export function createInvoice(input: FinanceInvoiceCreateInput) {
  return api<FinanceInvoiceRow>(`${base}`, 'POST', input);
}

/** 发票详情（头 + 明细）。 */
export function fetchInvoiceDetail(id: string) {
  return api<FinanceInvoiceDetail>(`${base}/${id}`);
}

/** 开具：分配正式编号（草稿 → 已开具）。 */
export function issueInvoice(id: string) {
  return api<FinanceInvoiceRow>(`${base}/${id}/issue`, 'POST');
}

/** 作废（🔴 原因必填）。 */
export function voidInvoice(id: string, reason: string) {
  return api<FinanceInvoiceRow>(`${base}/${id}/void`, 'POST', {
    reason,
  } satisfies FinanceInvoiceVoidInput);
}

/** 红冲：按原发票逐行带出生成成对的贷项通知单。 */
export function reverseInvoice(id: string, lineNos: number[], reason: string) {
  return api<FinanceInvoiceRow>(`${base}/${id}/reverse`, 'POST', {
    lineNos,
    reason,
  } satisfies FinanceInvoiceReverseInput);
}

/** 状态记录（append-only 留痕）。 */
export function fetchInvoiceEvents(id: string) {
  return api<FinanceInvoiceEventRow[]>(`${base}/${id}/events`);
}

/** 待开票需求（上游 9 订单，只读）。 */
export function fetchInvoiceRequests(page = 0, size = 50) {
  return api<FinanceInvoiceRequestPage>(`${base}/requests${query({ page, size })}`);
}

/** 批量建单（失败逐条说清原因）。 */
export function batchCreateInvoices(input: FinanceInvoiceBatchInput) {
  return api<FinanceInvoiceBatchResult>(`${base}/batch`, 'POST', input);
}

/** 发票汇总（🔴 按币种分组，没有跨币种的合计）。 */
export function fetchInvoiceSummary(baseCurrency?: string) {
  return api<FinanceInvoiceSummary>(`${base}/summary${query({ baseCurrency })}`);
}

/** 平台开票报文（金额在报文里是字符串）。 */
export function fetchInvoicePlatformPayload(id: string) {
  return api<FinanceInvoicePlatformPayload>(`${base}/${id}/platform-payload`);
}

/** 批量递交平台开票。 */
export function submitInvoiceToPlatform(input: FinanceInvoicePlatformSubmitInput) {
  return api<FinanceInvoicePlatformSubmitResult>(`${base}/platform-submissions`, 'POST', input);
}

/** 平台提交记录清单。 */
export function fetchInvoicePlatformSubmissions(page = 0, size = 50) {
  return api<FinanceInvoicePlatformSubmissionPage>(
    `${base}/platform-submissions${query({ page, size })}`,
  );
}

/** 登记平台回执。 */
export function acknowledgeInvoicePlatformSubmission(
  id: string,
  input: FinanceInvoicePlatformAcknowledgeInput,
) {
  return api<FinanceInvoicePlatformSubmissionRow>(
    `${base}/platform-submissions/${id}/acknowledge`,
    'POST',
    input,
  );
}

// ================================================================ 可读名

/** 单据类型的可读名。 */
export function documentTypeLabel(value: string | null | undefined): string {
  switch (value) {
    case 'INVOICE':
      return '销售发票';
    case 'CREDIT_NOTE':
      return '贷项通知单';
    default:
      return value ?? '';
  }
}

/** 发票状态的可读名。 */
export function invoiceStatusLabel(value: string | null | undefined): string {
  switch (value) {
    case 'DRAFT':
      return '草稿';
    case 'ISSUED':
      return '已开具';
    case 'VOID':
      return '已作废';
    case 'REVERSED':
      return '已红冲';
    default:
      return value ?? '';
  }
}

/** 平台对接状态的可读名。 */
export function platformSyncStatusLabel(value: string | null | undefined): string {
  switch (value) {
    case 'NOT_SUBMITTED':
      return '未提交';
    case 'SUBMITTED':
      return '已提交';
    case 'ACKNOWLEDGED':
      return '平台已确认';
    case 'FAILED':
      return '提交失败';
    default:
      return value ?? '';
  }
}

/** 编号范围的可读名。 */
export function sequenceScopeLabel(value: string | null | undefined): string {
  switch (value) {
    case 'TENANT':
      return '全租户一个号段';
    case 'COUNTRY':
      return '按目的国分号段';
    case 'PLATFORM':
      return '按平台分号段';
    default:
      return value ?? '';
  }
}

/** 编号期间口径的可读名。 */
export function sequencePeriodLabel(value: string | null | undefined): string {
  switch (value) {
    case 'NONE':
      return '不按期间';
    case 'MONTHLY':
      return '按月';
    case 'YEARLY':
      return '按年';
    default:
      return value ?? '';
  }
}

// ================================================================ 展示

/**
 * 金额的展示：**带币种**。
 *
 * <p>🔴 多币种绝不相加 —— 金额在页面上永远和自己的币种一起出现，
 * 免得有人把两个币种的数字抄到一张表里相加。
 */
export function formatMoney(
  amount: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined) return '—';
  const fixed = amount.toFixed(2);
  return currency ? `${fixed} ${currency}` : fixed;
}

/**
 * 数量的展示。
 *
 * <p>🔴 贷项行的数量是**负数**（红冲方向）—— 保留符号，不取绝对值：
 * 绝对值会把「红冲了多少」说成「有多少」。
 */
export function formatQuantity(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toLocaleString('en-US', { maximumFractionDigits: 6 });
}

/**
 * 税率的展示（**只做单位换算**）。
 *
 * <p>🔴 页面不参与税额计算 —— 税额由服务端的生成列算。
 */
export function formatTaxRate(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return `${(value * 100).toFixed(2)}%`;
}

/**
 * 发票号的展示：草稿**还没有编号**。
 *
 * <p>⚠️ 草稿显示「未编号」而不是破折号 —— 破折号看着像「编号丢了」，
 * 而真相是「还没开具，号还没分配」。
 */
export function invoiceNumberLabel(
  row: Pick<FinanceInvoiceRow, 'invoiceNumber' | 'status'>,
): string {
  if (row.invoiceNumber) return row.invoiceNumber;
  return row.status === 'DRAFT' ? '未编号（开具时分配）' : '—';
}

/**
 * 一个辖区能不能用（本模块）的可读结论。
 *
 * <p>🔴 「不承诺覆盖所有国家税务规则」不是一句免责声明，而是**逐个辖区**给结论。
 */
export function countryCoverageTone(coverage: {
  templateConfigured: boolean;
  taxRateResolvable: boolean;
}): 'success' | 'warning' | 'danger' {
  if (coverage.templateConfigured && coverage.taxRateResolvable) return 'success';
  if (coverage.templateConfigured) return 'warning';
  return 'danger';
}
