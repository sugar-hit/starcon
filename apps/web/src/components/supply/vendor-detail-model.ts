import { api } from '../inventory/api';

/**
 * 6.1.1.2 供应商详情前端模型。
 *
 * <p>蓝图：「单供应商全景：基础信息、账期与开票信息、供货 SKU 与报价、采购历史与金额、
 * 交期与质量表现、联系人、附件资质」，输出「详情视图 + 操作入口」。
 *
 * <p>🔴 这一页最重要的界面纪律有两条，都不是审美偏好而是数据事实：
 *
 * <ol>
 *   <li><b>只读的那几段页面上没有编辑入口。</b>编码 / 名称 / 联系方式 / 合作等级 /
 *       采购侧合作状态的写入所有权分别在往来单位档案（03.6.x）与 6.1.1.1 采购侧档案。
 *       本页给的是链接，不是第二套表单 —— 在同一份数据上提供两套编辑能力，
 *       改完之后没有任何东西会告诉你两边不一致了。
 *   <li><b>比率列的 null 不能渲染成 0%</b>：后端在窗口内没有记录时返回 null
 *       （「还没交货过」与「交货全部延期」是相反的两件事）。这一页再补一次同样的判断 ——
 *       不是不信后端，而是显示成 0% 会让一个刚建档的供应商看起来是最差的那一家。
 * </ol>
 */

/** 词表选项（值 / 中文名 / 提示）。 */
export interface VendorOption {
  value: string;
  label: string;
  hint: string;
}

/** 指标窗口 —— 交期与质量表现是**窗口统计**，界面上必须显示是哪一段。 */
export interface VendorMetricWindow {
  from: string;
  to: string;
}

/** 一个币种上的金额。跨币种绝不相加。 */
export interface VendorPurchaseAmount {
  currency: string;
  amount: string;
}

/** 账期与开票信息 —— **付款审批的输入**，所以它是可维护的数据而不是只读展示。 */
export interface VendorTermsView {
  roleId: string;
  currency: string;
  paymentTermsDays: number;
  masterPaymentTermsDays: number | null;
  masterSettlementMethod: string | null;
  settlementMethod: string;
  invoiceType: string;
  invoiceTitle: string;
  taxId: string;
  taxRate: string;
  creditLimit: string | null;
  depositRatio: string;
  effectiveFrom: string;
  note: string;
  version: number;
  found: boolean;
}

export interface VendorTermsInput {
  currency: string;
  paymentTermsDays: number;
  settlementMethod: string;
  invoiceType: string;
  invoiceTitle: string;
  taxId: string;
  taxRate: string;
  creditLimit: string | null;
  depositRatio: string;
  effectiveFrom: string;
  note: string;
  version: number;
}

export interface VendorQualificationRow {
  id: string;
  roleId: string;
  qualificationType: string;
  title: string;
  certNo: string;
  issuer: string;
  issuedOn: string | null;
  expiresOn: string | null;
  validity: string;
  daysToExpiry: number | null;
  fileKey: string;
  fileName: string;
  mimeType: string | null;
  fileSizeBytes: number | null;
  note: string;
  version: number;
}

export interface VendorQualificationInput {
  qualificationType: string;
  title: string;
  certNo: string;
  issuer: string;
  issuedOn: string | null;
  expiresOn: string | null;
  fileKey: string;
  fileName: string;
  mimeType: string | null;
  fileSizeBytes: number | null;
  note: string;
  version: number;
}

export interface VendorQualificationList {
  items: VendorQualificationRow[];
  total: number;
  expiredCount: number;
  expiringCount: number;
  note: string;
}

/** 联系人 —— **只读**，写入所有权在往来单位档案（3.6.x）。 */
export interface VendorContactRow {
  id: string;
  name: string;
  position: string | null;
  email: string | null;
  phone: string | null;
  mobile: string | null;
  primary: boolean;
  note: string;
}

export interface VendorQuoteRow {
  id: string;
  skuId: string;
  skuCode: string | null;
  skuName: string | null;
  supplierSkuCode: string;
  purchaseUnitCode: string;
  currency: string;
  moqQuantity: string;
  leadTimeDays: number;
  firstTierUnitPrice: string | null;
  tierCount: number;
  validFrom: string;
  validTo: string | null;
  priority: number;
  status: string;
  effective: boolean;
  note: string;
  version: number;
}

export interface VendorQuotePage {
  items: VendorQuoteRow[];
  total: number;
  page: number;
  size: number;
  effectiveSkuCount: number;
  effectiveOn: string;
  note: string;
}

export interface VendorPurchaseHistoryRow {
  id: string;
  deliveryRef: string;
  skuId: string | null;
  skuCode: string | null;
  skuName: string | null;
  purchaseUnitCode: string | null;
  quantity: string;
  amount: string;
  currency: string;
  orderedAt: string | null;
  dueDate: string;
  receivedDate: string;
  onTime: boolean;
  daysLate: number;
  isVoid: boolean;
  voidReason: string | null;
  note: string;
}

export interface VendorHistoryBucket {
  purchaseUnitCode: string;
  currency: string;
  quantity: string;
  amount: string;
  deliveryCount: number;
}

export interface VendorMonthlyAmount {
  month: string;
  currency: string;
  amount: string;
  deliveryCount: number;
}

export interface VendorPurchaseHistoryPage {
  items: VendorPurchaseHistoryRow[];
  total: number;
  page: number;
  size: number;
  deliveryCount: number;
  totalQuantity: string | null;
  buckets: VendorHistoryBucket[];
  monthly: VendorMonthlyAmount[];
  metricWindow: VendorMetricWindow;
  note: string;
}

/** 交期表现。四个数全是 SQL 聚合，没有一个是存下来的字段。 */
export interface VendorDeliveryPerformance {
  deliveryCount: number;
  onTimeCount: number;
  onTimeRate: string | null;
  averageDaysLate: string | null;
  maxDaysLate: number | null;
  earlyCount: number;
  lastDeliveryAt: string | null;
}

export interface VendorQualityPerformance {
  inspectionCount: number;
  inspectedQuantity: string;
  qualifiedQuantity: string;
  qualifiedRate: string | null;
  unqualifiedQuantity: string;
  lastInspectionAt: string | null;
}

/** 操作入口。灰掉时 reason 必须给出**为什么现在不能做**。 */
export interface VendorOperationEntry {
  key: string;
  label: string;
  available: boolean;
  href: string | null;
  reason: string | null;
}

export interface VendorPanorama {
  roleId: string;
  partyId: string;
  partyCode: string;
  partyName: string;
  partyType: string;
  countryCode: string | null;
  email: string | null;
  phone: string | null;
  website: string | null;
  partyStatus: string;
  statusReason: string | null;
  cooperationLevel: string | null;
  masterSettlementMethod: string | null;
  moqUnitCode: string | null;
  moqQuantity: string | null;
  supplierProfileFound: boolean;
  supplyStatus: string | null;
  categoryId: string | null;
  categoryName: string | null;
  supplyNote: string | null;
  supplyProfileFound: boolean;
  terms: VendorTermsView | null;
  termsFound: boolean;
  quoteCount: number;
  effectiveQuoteCount: number;
  quotedSkuCount: number;
  quoteCurrency: string | null;
  historyDeliveryCount: number;
  purchaseAmounts: VendorPurchaseAmount[];
  lastDeliveryAt: string | null;
  delivery: VendorDeliveryPerformance;
  quality: VendorQualityPerformance;
  contacts: VendorContactRow[];
  qualificationCount: number;
  qualificationExpiredCount: number;
  qualificationExpiringCount: number;
  nearestExpiry: string | null;
  operations: VendorOperationEntry[];
  warnings: string[];
  metricWindow: VendorMetricWindow;
  note: string;
}

export interface VendorDetailOptions {
  settlementMethods: VendorOption[];
  invoiceTypes: VendorOption[];
  qualificationTypes: VendorOption[];
  quoteStatuses: VendorOption[];
}

// ================================================================== 端点

export function fetchVendorDetailOptions(): Promise<VendorDetailOptions> {
  return api<VendorDetailOptions>('catalog/supply/vendors/detail-options');
}

export function fetchVendorPanorama(
  roleId: string,
  window?: { from?: string; to?: string },
): Promise<VendorPanorama> {
  const query = new URLSearchParams();
  if (window?.from) query.set('metricFrom', window.from);
  if (window?.to) query.set('metricTo', window.to);
  const suffix = query.toString();
  return api<VendorPanorama>(
    `catalog/supply/vendors/${roleId}/panorama${suffix ? `?${suffix}` : ''}`,
  );
}

export function fetchVendorPurchaseHistory(
  roleId: string,
  params: { page?: number; size?: number; currency?: string; from?: string; to?: string } = {},
): Promise<VendorPurchaseHistoryPage> {
  const query = new URLSearchParams();
  if (params.currency) query.set('currency', params.currency);
  if (params.from) query.set('metricFrom', params.from);
  if (params.to) query.set('metricTo', params.to);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<VendorPurchaseHistoryPage>(
    `catalog/supply/vendors/${roleId}/purchase-history?${query.toString()}`,
  );
}

export function fetchVendorQuotes(
  roleId: string,
  params: {
    page?: number;
    size?: number;
    status?: string;
    effectiveOnly?: boolean;
    at?: string;
  } = {},
): Promise<VendorQuotePage> {
  const query = new URLSearchParams();
  if (params.status) query.set('status', params.status);
  if (params.effectiveOnly) query.set('effectiveOnly', 'true');
  if (params.at) query.set('at', params.at);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<VendorQuotePage>(`catalog/supply/vendors/${roleId}/quotes?${query.toString()}`);
}

export function fetchVendorQualifications(roleId: string): Promise<VendorQualificationList> {
  return api<VendorQualificationList>(`catalog/supply/vendors/${roleId}/qualifications`);
}

/** 保存账期与开票信息（付款审批的输入）。 */
export function saveVendorTerms(roleId: string, input: VendorTermsInput): Promise<VendorTermsView> {
  return api<VendorTermsView>(`catalog/supply/vendors/${roleId}/settlement-terms`, 'PUT', input);
}

export function createVendorQualification(
  roleId: string,
  input: VendorQualificationInput,
): Promise<{ id: string; version: number }> {
  return api(`catalog/supply/vendors/${roleId}/qualifications`, 'POST', input);
}

export function updateVendorQualification(
  roleId: string,
  attachmentId: string,
  input: VendorQualificationInput,
): Promise<{ id: string; version: number }> {
  return api(`catalog/supply/vendors/${roleId}/qualifications/${attachmentId}`, 'PUT', input);
}

export function deleteVendorQualification(roleId: string, attachmentId: string): Promise<unknown> {
  return api(`catalog/supply/vendors/${roleId}/qualifications/${attachmentId}`, 'DELETE');
}

// ================================================================== 展示口径（纯函数，可测）

/**
 * 比率 → 百分比文本。
 *
 * <p><b>null 与 0 必须长得不一样</b>：null 是「窗口内没有记录」，0 是「全部不合规」。
 * 折叠成同一个 0% 会让一个刚建档、正在试单的供应商看起来是最差的那一家。
 */
export function rateLabel(rate: string | null | undefined): string {
  if (rate === null || rate === undefined || rate === '') return '暂无数据';
  return `${(Number(rate) * 100).toFixed(1)}%`;
}

/** 比率的配色：null 走中性而不是警告 —— 「没数据」不是一件坏事。 */
export function rateTone(rate: string | null | undefined): 'positive' | 'negative' | 'neutral' {
  if (rate === null || rate === undefined || rate === '') return 'neutral';
  const value = Number(rate);
  if (value >= 0.95) return 'positive';
  if (value < 0.8) return 'negative';
  return 'neutral';
}

/** 指标窗口文本。界面上没有这一句，「准时率 96%」就是一个没有意义的时间外的数字。 */
export function windowText(window: VendorMetricWindow | null | undefined): string {
  if (!window) return '';
  return `${window.from.slice(0, 10)} ~ ${window.to.slice(0, 10)}`;
}

/**
 * 金额按币种分列。
 *
 * <p><b>绝不相加</b>：本库没有汇率表，100 USD + 90 EUR = 190 是一个看起来对、实际错、
 * 而且极难被发现的数字。
 */
export function amountListText(amounts: VendorPurchaseAmount[] | null | undefined): string {
  if (!amounts || amounts.length === 0) return '暂无数据';
  return amounts.map((a) => `${a.amount} ${a.currency}`).join(' / ');
}

/** 平均延期天数：负数是**早到**，必须显示出来而不是显示成 0。 */
export function daysLateText(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '暂无数据';
  const days = Number(value);
  if (days < 0) return `平均早到 ${Math.abs(days).toFixed(2)} 天`;
  return `平均晚到 ${days.toFixed(2)} 天`;
}

/** 信用额度：null（未设额度）与 0（额度已用尽）是相反的两件事，不能都显示成 0。 */
export function creditLimitText(value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '未设额度（不拦截）';
  if (Number(value) === 0) return '0（额度已用尽，全部拒付）';
  return value;
}

/** 税率：后端存的是 0~1 的比例，界面上显示百分数。 */
export function taxRateText(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '未登记';
  return `${(Number(value) * 100).toFixed(2).replace(/\.?0+$/, '')}%`;
}

export function settlementMethodLabel(value: string | null | undefined): string {
  switch (value) {
    case 'PREPAID':
      return '预付';
    case 'ON_DELIVERY':
      return '货到付款';
    case 'CREDIT':
      return '月结 / 账期';
    case 'LETTER_OF_CREDIT':
      return '信用证';
    default:
      return '未登记';
  }
}

export function invoiceTypeLabel(value: string | null | undefined): string {
  switch (value) {
    case 'VAT_SPECIAL':
      return '增值税专票';
    case 'VAT_NORMAL':
      return '增值税普票';
    case 'PROFORMA':
      return '形式发票';
    case 'EXPORT_DECLARATION':
      return '出口报关单';
    case 'NONE':
      return '无票';
    default:
      return '未登记';
  }
}

export function qualificationTypeLabel(value: string | null | undefined): string {
  switch (value) {
    case 'BUSINESS_LICENSE':
      return '营业执照';
    case 'ISO9001':
      return 'ISO9001 体系认证';
    case 'ISO14001':
      return 'ISO14001 体系认证';
    case 'CE':
      return 'CE 认证';
    case 'FDA':
      return 'FDA 注册';
    case 'COC':
      return '产地证';
    case 'OTHER':
      return '其它资质';
    default:
      return '未登记';
  }
}

/** 有效期状态 → 配色。已过期是警告色，而「未填到期日」是中性色。 */
export function validityTone(validity: string): 'positive' | 'negative' | 'neutral' {
  if (validity === 'EXPIRED') return 'negative';
  if (validity === 'EXPIRING_SOON') return 'negative';
  if (validity === 'VALID') return 'positive';
  return 'neutral';
}

export function validityLabel(row: VendorQualificationRow): string {
  switch (row.validity) {
    case 'EXPIRED':
      return `已过期 ${Math.abs(row.daysToExpiry ?? 0)} 天`;
    case 'EXPIRING_SOON':
      return `${row.daysToExpiry} 天后到期`;
    case 'VALID':
      return `有效至 ${row.expiresOn}`;
    default:
      return '未填到期日';
  }
}

/** 到期日缺失时的占位行。 */
export const EMPTY_TERMS_FORM = {
  currency: 'USD',
  paymentTermsDays: 0,
  settlementMethod: 'CREDIT',
  invoiceType: 'VAT_SPECIAL',
  invoiceTitle: '',
  taxId: '',
  taxRate: '0.13',
  creditLimit: null as string | null,
  depositRatio: '0',
  effectiveFrom: new Date().toISOString().slice(0, 10),
  note: '',
  version: 0,
};

/**
 * 账期与开票表单 → 请求体。
 *
 * <p>发票抬头**允许与主体名不同**：跨境里收款主体与开票主体不一致是常态，
 * 用主体名顶替会让财务拿错抬头开票，事后只能红冲重开。
 */
export function toTermsInput(form: typeof EMPTY_TERMS_FORM, partyName: string): VendorTermsInput {
  return {
    currency: form.currency.trim().toUpperCase(),
    paymentTermsDays: Number(form.paymentTermsDays),
    settlementMethod: form.settlementMethod,
    invoiceType: form.invoiceType,
    invoiceTitle: form.invoiceTitle.trim() || partyName,
    taxId: form.taxId.trim(),
    taxRate: form.taxRate,
    creditLimit: form.creditLimit === null || form.creditLimit === '' ? null : form.creditLimit,
    depositRatio: form.depositRatio,
    effectiveFrom: form.effectiveFrom,
    note: form.note,
    version: form.version,
  };
}

/**
 * 表单校验。
 *
 * <p>校验规则与后端 {@code VendorDetailService} 同源，但**前端这一道不能省**：
 * 提交后才被后端 400 拒掉的表单，运营已经填了一屏字才知道自己哪里错了。
 */
export function validateTerms(input: VendorTermsInput): string[] {
  const problems: string[] = [];
  if (!/^[A-Z]{3}$/.test(input.currency)) problems.push('结算币种必须是 ISO 4217 三位大写字母码');
  if (input.paymentTermsDays < 0 || input.paymentTermsDays > 365)
    problems.push('账期要填 0~365 天（超过一年的那不是账期，是把年填成了天）');
  if (!input.invoiceTitle.trim()) problems.push('发票抬头不能为空（跨境里它常常与主体名不同）');
  const rate = Number(input.taxRate);
  if (Number.isNaN(rate) || rate < 0 || rate > 1)
    problems.push('税率要填 0~1 的比例（13% 填 0.13）');
  if (input.settlementMethod === 'PREPAID' && Number(input.depositRatio) <= 0)
    problems.push('结算方式是「预付」时预付比例必须大于 0');
  if (!['NONE', 'PROFORMA'].includes(input.invoiceType) && !input.taxId.trim())
    problems.push('开票方式需要税号（12 财务靠它自动生成付款申请）；确实没有请登记成「无票」');
  return problems;
}

/** 附件资质表单校验。 */
export function validateQualification(input: VendorQualificationInput): string[] {
  const problems: string[] = [];
  if (!input.qualificationType) problems.push('资质类型不能为空');
  if (!input.title.trim()) problems.push('证件名称不能为空');
  if (!input.fileKey.trim()) problems.push('附件文件不能为空');
  if (input.issuedOn && input.expiresOn && input.expiresOn < input.issuedOn)
    problems.push('到期日不能早于签发日');
  return problems;
}

/** 操作入口 → 按钮形态。灰掉时把理由摆出来，而不是让运营以为系统坏了。 */
export function operationDisabledReason(entry: VendorOperationEntry): string {
  return entry.available ? '' : (entry.reason ?? '暂不可用');
}
