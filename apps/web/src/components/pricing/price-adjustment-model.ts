import { api } from '../inventory/api';

/**
 * 5.4.1.1 调价单前端模型。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：发起价格变更：选择 SKU 与目标店铺、输入新旧价、说明原因、提交审批（若启用）；记录变更前后值。
 *
 * **输入**：调价请求；`expected_version`；审批要求（17 组织的审批规则）。
 *
 * **输出**：调价单（草稿/待审/已批/已执行/已驳回）；生效后触发平台回写。
 *
 * **关系**：上游 = 5.3.1.1、5.3.2.2；下游 = 5.4.2.1、17 审批。**调价是受控动作**：大额或大幅调价走审批，普通调整可配置免审。
 *
 * </blockquote>
 *
 * <h2>🔴 页面必须把「版本号」做成一道真的闸</h2>
 *
 * <p>后端每次状态流转都要求带上你读到的 {@code version}（蓝图的输入句
 * {@code expected_version}），对不上就 409。所以这里的 {@link submitPriceAdjustment} /
 * {@link executePriceAdjustment} / {@link rejectPriceAdjustment} 一律要求传 {@code expectedVersion}，
 * 页面永远用**当前详情里的 version**，不用本地缓存的那一份。
 *
 * <h2>🔴 null 绝不能渲染成 0</h2>
 *
 * <p>这一页有三种「没有」，它们是三件事：
 *
 * <ol>
 *   <li><b>取不到当前价</b>：这个 SKU 在这个店铺没有价格事实 ⇒ 旧价是 null，
 *       而且**根本不该建单**（服务端会拒绝，页面也要说清原因）。</li>
 *   <li><b>成本不完整</b>：改了，但缺项 ⇒ 毛利额与毛利率是 null，亏损标记也是 null。</li>
 *   <li><b>真的是 0</b>：毛利额恰好是 0 ⇒ 正常的边界值。</li>
 * </ol>
 *
 * ⇒ {@link moneyText} / {@link percentText} / {@link lossText} 把它们渲染成**看得见**的三种样子。
 */

/** 调价单状态。与后端 {@code PriceAdjustmentModels.Status} 逐字对应。 */
export type PriceAdjustmentStatus =
  | 'DRAFT'
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'EXECUTED'
  | 'REJECTED';

/** 当前价来源。与后端 {@code PriceAdjustmentModels.PriceSource} 逐字对应。 */
export type PriceAdjustmentPriceSource = 'PRICE_OBSERVATION' | 'BATCH_ADJUSTMENT';

/** 审批结论。与 identity 的 {@code ApprovalEvaluationView.decision} 逐字对应。 */
export type PriceAdjustmentApprovalDecision = 'SUBMIT_FOR_APPROVAL' | 'EXEMPT';

/** 审批结论来源。与 identity 的 {@code ApprovalEvaluationView.source} 逐字对应。 */
export type PriceAdjustmentApprovalSource = 'RULE' | 'DEFAULT' | 'UNCONTROLLED';

/** 台账排序。与后端 {@code Sort} 逐字对应。 */
export type PriceAdjustmentSort =
  | 'NEWEST'
  | 'OLDEST'
  | 'AMOUNT_DESC'
  | 'AMOUNT_ASC';

/** 目标店铺（平台 + 站点）。 */
export interface ShopRef {
  platformCode: string;
  siteCode: string;
}

/** 创建调价单的入参。 */
export interface PriceAdjustmentRequest {
  skuId: string;
  platformCode: string;
  siteCode: string;
  currency: string;
  costCurrency: string;
  fxRate: number | null;
  newPrice: number;
  oldPrice: number | null;
  quantity: number | null;
  platformFeeRate: number | null;
  platformFeeFixed: number | null;
  logisticsCostPerUnit: number | null;
  monthlyFixedCost: number | null;
  costWindowFrom: string | null;
  costWindowTo: string | null;
  title: string;
  reason: string;
  note: string;
}

/** 带乐观锁版本号的操作入参。 */
export interface PriceAdjustmentVersionRequest {
  expectedVersion: number;
  comment?: string;
}

/** 驳回入参。 */
export interface PriceAdjustmentRejectRequest {
  expectedVersion: number;
  reason: string;
}

/** 当前价解析结果。🔴 {@code price} 为 null 时**不是 0**，是真的「取不到」。 */
export interface PriceAdjustmentCurrentPrice {
  skuId: string;
  platformCode: string;
  siteCode: string;
  currency: string;
  price: number | null;
  source: PriceAdjustmentPriceSource | null;
  observedAt: string | null;
  reason: string;
}

/** 调价单明细 = 变更前后值。 */
export interface PriceAdjustmentOrderItem {
  id: string | null;
  orderId: string | null;
  skuId: string;
  skuCode: string;
  skuName: string;
  platformCode: string;
  siteCode: string;
  currency: string;
  oldPrice: number | null;
  newPrice: number | null;
  priceDelta: number | null;
  priceRate: number | null;
  priceSource: PriceAdjustmentPriceSource;
  priceSourceLabel: string;
  priceObservedAt: string | null;
  grossProfitBefore: number | null;
  grossMarginBefore: number | null;
  grossProfitAfter: number | null;
  grossMarginAfter: number | null;
  decisionReady: boolean;
  lossBefore: boolean | null;
  lossAfter: boolean | null;
}

/** 一张调价单（台账与详情共用同一个形状）。 */
export interface PriceAdjustmentDetail {
  id: string;
  status: PriceAdjustmentStatus;
  statusLabel: string;
  /** 🔴 当前版本（乐观锁）。下一次操作要把它作为 expectedVersion 带回去。 */
  version: number;
  skuId: string;
  skuCode: string;
  skuName: string;
  platformCode: string;
  siteCode: string;
  currency: string;
  title: string;
  reason: string;
  note: string;
  quantity: number;
  costCurrency: string;
  fxRate: number | null;
  platformFeeRate: number | null;
  platformFeeFixed: number | null;
  logisticsCostPerUnit: number | null;
  monthlyFixedCost: number | null;
  costWindowFrom: string | null;
  costWindowTo: string | null;
  oldPrice: number | null;
  newPrice: number | null;
  priceDelta: number | null;
  priceRate: number | null;
  priceSource: PriceAdjustmentPriceSource | null;
  priceSourceLabel: string | null;
  priceObservedAt: string | null;
  grossProfitBefore: number | null;
  grossMarginBefore: number | null;
  grossProfitAfter: number | null;
  grossMarginAfter: number | null;
  decisionReady: boolean | null;
  lossBefore: boolean | null;
  lossAfter: boolean | null;
  approvalRequired: boolean;
  approvalDecision: PriceAdjustmentApprovalDecision | null;
  approvalSource: PriceAdjustmentApprovalSource | null;
  approvalRuleId: string | null;
  approvalRuleName: string | null;
  approvalFlowId: string | null;
  approvalFlowName: string | null;
  approvalReason: string;
  approvalInstanceId: string | null;
  approvalInstanceNo: string | null;
  approvalSubmittedAt: string | null;
  approvalDecidedAt: string | null;
  approvalDecidedBy: string | null;
  /** 🔴 非空表示「上一次同步审批状态失败了」—— 单子停在待审，**没有**被当成已批。 */
  approvalSyncError: string;
  submittedAt: string | null;
  executedAt: string | null;
  rejectedAt: string | null;
  rejectedBy: string | null;
  rejectReason: string;
  createdBy: string | null;
  createdAt: string;
  updatedAt: string;
  items: PriceAdjustmentOrderItem[];
}

/** 调价单台账（分页）。 */
export interface PriceAdjustmentPage {
  items: PriceAdjustmentDetail[];
  page: number;
  size: number;
  total: number;
}

/** 待回写平台的一条事实（给 5.4.2.1）。 */
export interface PriceAdjustmentWriteback {
  id: string;
  orderId: string;
  orderItemId: string;
  skuId: string;
  skuCode: string;
  platformCode: string;
  siteCode: string;
  currency: string;
  expectedPrice: number;
  previousPrice: number | null;
  status: string;
  idempotencyKey: string;
  acknowledgedAt: string | null;
  ackNote: string;
  createdAt: string;
}

/** 待回写事实列表（分页）。 */
export interface PriceAdjustmentWritebackPage {
  items: PriceAdjustmentWriteback[];
  page: number;
  size: number;
  total: number;
}

/** 能力声明。页面顶部固定显示它。 */
export interface PriceAdjustmentCapabilities {
  listingDefinitionNote: string;
  priceSourceNote: string;
  approvalNote: string;
  approvalSyncNote: string;
  stateMachineNote: string;
  versionNote: string;
  profitNote: string;
  roundingNote: string;
  upstreamNote: string;
  downstreamNote: string;
  permissionNote: string;
}

// ================================================================ 分区描述符

/** 分区里的一个表单项。 */
export interface PriceAdjustmentSectionField {
  id: string;
  label: string;
  hint: string;
  kind: 'select' | 'text' | 'number' | 'textarea';
  required?: boolean;
}

/**
 * 调价单分区的描述符 —— **组件从它渲染，测试也从它断言**。
 *
 * <p>🔴 断言写在字面量上，兄弟模块一旦交付就会红。断言「本分区渲染出了它自己的标题与字段」
 * 这件事本身是稳的 —— 它只依赖本模块自己的描述符。
 */
export interface PriceAdjustmentSectionDescriptor {
  key: string;
  title: string;
  description: string;
  fields: PriceAdjustmentSectionField[];
}

export const PRICE_ADJUSTMENT_SECTION: PriceAdjustmentSectionDescriptor = {
  key: 'price-adjustment',
  title: '调价单',
  description:
    '发起一次价格变更：选择 SKU 与目标店铺、输入新旧价、说明原因、提交审批（若命中 17 组织的审批规则）。' +
    '记录变更前后值，生效后产出「待回写」事实给 5.4.2.1 平台价格同步。',
  fields: [
    { id: 'skuId', label: 'SKU', hint: '被调价的那个 SKU（UUID）', kind: 'text', required: true },
    { id: 'platformCode', label: '平台', hint: '平台码，如 AMAZON', kind: 'text', required: true },
    { id: 'siteCode', label: '站点', hint: '店铺站点码，如 US', kind: 'text', required: true },
    { id: 'currency', label: '售价币种', hint: '三位大写字母，如 USD', kind: 'text', required: true },
    { id: 'costCurrency', label: '成本币种', hint: '空 = 与售价同币种', kind: 'text' },
    { id: 'fxRate', label: '汇率', hint: '1 成本币种 = X 售价币种（跨币种必填）', kind: 'number' },
    { id: 'oldPrice', label: '旧价', hint: '你看到的那个价（留空 = 以服务端解析到的当前价为准）', kind: 'number' },
    { id: 'newPrice', label: '新价', hint: '人输入的新价，2 位小数 HALF_UP 落定', kind: 'number', required: true },
    { id: 'quantity', label: '数量', hint: '用于阶梯与运费，空 = 1', kind: 'number' },
    { id: 'platformFeeRate', label: '平台费率（手工假设）', hint: '0.15 = 15%', kind: 'number' },
    { id: 'logisticsCostPerUnit', label: '物流每单位运费（手工假设）', hint: '每单位尾程运费', kind: 'number' },
    { id: 'title', label: '标题', hint: '提交审批实例时作为单据标题', kind: 'text' },
    { id: 'reason', label: '调价原因', hint: '必填 —— 事后要能看懂这次为什么调', kind: 'textarea', required: true },
    { id: 'note', label: '备注', hint: '补充说明', kind: 'textarea' },
  ],
};

// ================================================================ 纯函数：口径渲染

/**
 * 金额渲染。
 *
 * <p>🔴 {@code null} ⇒ 「未计入」，**不是 0.00**。
 * 「取不到」与「确实是 0」是两件事。
 */
export function moneyText(value: number | null | undefined, fallback = '未计入'): string {
  if (value === null || value === undefined || Number.isNaN(value)) return fallback;
  return trimZeros(value.toFixed(4));
}

/**
 * 售价渲染（2 位小数 —— 对外标价就是两位小数的钱）。
 *
 * <p>🔴 {@code null} ⇒ 「未计入」，不是 0.00。
 */
export function priceText(value: number | null | undefined, fallback = '未计入'): string {
  if (value === null || value === undefined || Number.isNaN(value)) return fallback;
  return trimZeros(value.toFixed(2));
}

/**
 * 毛利率渲染。
 *
 * <p>🔴 {@code null} ⇒ 「不可信（成本不完整）」，**不是 0%**。
 */
export function percentText(
  rate: number | null | undefined,
  fallback = '不可信（成本不完整）',
): string {
  if (rate === null || rate === undefined || Number.isNaN(rate)) return fallback;
  return `${trimZeros((rate * 100).toFixed(2))}%`;
}

/** 价差渲染：正 = 涨价，负 = 降价。 */
export function deltaText(value: number | null | undefined, fallback = '—'): string {
  if (value === null || value === undefined || Number.isNaN(value)) return fallback;
  const sign = value > 0 ? '+' : '';
  return `${sign}${trimZeros(value.toFixed(2))}`;
}

function trimZeros(text: string): string {
  if (!text.includes('.')) return text;
  const trimmed = text.replace(/0+$/, '').replace(/\.$/, '');
  return trimmed === '' || trimmed === '-' ? '0' : trimmed;
}

/** 调价单状态的中文名。 */
export function statusLabel(status: PriceAdjustmentStatus | null | undefined): string {
  switch (status) {
    case 'DRAFT':
      return '草稿';
    case 'PENDING_APPROVAL':
      return '待审';
    case 'APPROVED':
      return '已批';
    case 'EXECUTED':
      return '已执行';
    case 'REJECTED':
      return '已驳回';
    default:
      return '—';
  }
}

/**
 * 状态的色调。
 *
 * <p>🔴 「待审」是 <b>info</b> 不是 danger：它不是「出错了」，它是「在等 17 组织批」。
 */
export function statusTone(
  status: PriceAdjustmentStatus | null | undefined,
): 'success' | 'warning' | 'danger' | 'info' | 'neutral' {
  switch (status) {
    case 'DRAFT':
      return 'neutral';
    case 'PENDING_APPROVAL':
      return 'info';
    case 'APPROVED':
      return 'success';
    case 'EXECUTED':
      return 'success';
    case 'REJECTED':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 审批结论的中文名。 */
export function approvalDecisionLabel(
  decision: PriceAdjustmentApprovalDecision | null | undefined,
): string {
  switch (decision) {
    case 'SUBMIT_FOR_APPROVAL':
      return '需审批';
    case 'EXEMPT':
      return '免审';
    default:
      return '未提交';
  }
}

/** 审批结论来源的中文名。 */
export function approvalSourceLabel(
  source: PriceAdjustmentApprovalSource | null | undefined,
): string {
  switch (source) {
    case 'RULE':
      return '命中规则';
    case 'DEFAULT':
      return '走默认动作';
    case 'UNCONTROLLED':
      return '本租户未配规则（默认放行）';
    default:
      return '—';
  }
}

/** 当前价来源的中文名。 */
export function priceSourceLabel(source: string | null | undefined): string {
  switch (source) {
    case 'PRICE_OBSERVATION':
      return '价格观测台账';
    case 'BATCH_ADJUSTMENT':
      return '批量调价新价';
    default:
      return '—';
  }
}

/**
 * 亏损标记的中文名。
 *
 * <p>🔴 {@code null} ⇒ 「不可信」，**不是「不亏」**：成本不完整时算不出毛利率，
 * 那种单是「没测准」而不是「没亏」。
 */
export function lossText(loss: boolean | null | undefined): string {
  if (loss === null || loss === undefined) return '不可信';
  return loss ? '亏损' : '不亏';
}

// ================================================================ 纯函数：输入与校验

/** 校验失败。 */
export interface PriceAdjustmentValidationError {
  field: string;
  message: string;
}

/** 空表单。 */
export function emptyPriceAdjustmentRequest(): PriceAdjustmentRequest {
  return {
    skuId: '',
    platformCode: '',
    siteCode: '',
    currency: 'USD',
    costCurrency: 'USD',
    fxRate: null,
    newPrice: 0,
    oldPrice: null,
    quantity: 1,
    platformFeeRate: null,
    platformFeeFixed: null,
    logisticsCostPerUnit: null,
    monthlyFixedCost: null,
    costWindowFrom: null,
    costWindowTo: null,
    title: '',
    reason: '',
    note: '',
  };
}

/**
 * 提交前的本地校验（后端还会再校验一遍，这里是为了少一次往返）。
 *
 * <p>🔴 跨币种没给汇率 ⇒ 拒绝提交。绝不按 1:1 折算。
 */
export function validatePriceAdjustmentRequest(
  request: PriceAdjustmentRequest,
): PriceAdjustmentValidationError | null {
  if (!request.skuId.trim()) {
    return { field: 'skuId', message: '必须指定 SKU' };
  }
  if (!request.platformCode.trim()) {
    return { field: 'platformCode', message: '必须指定平台' };
  }
  if (!request.siteCode.trim()) {
    return { field: 'siteCode', message: '必须指定店铺（站点）' };
  }
  if (!/^[A-Za-z]{3}$/.test(request.currency)) {
    return { field: 'currency', message: '币种必须是三位大写字母' };
  }
  if (request.costCurrency && !/^[A-Za-z]{3}$/.test(request.costCurrency)) {
    return { field: 'costCurrency', message: '币种必须是三位大写字母' };
  }
  if (!request.newPrice || request.newPrice <= 0) {
    return { field: 'newPrice', message: '新价必须大于 0 —— 把价格改成 0 就是「免费」，那不是调价' };
  }
  if (request.oldPrice !== null && request.oldPrice < 0) {
    return { field: 'oldPrice', message: '旧价不能为负' };
  }
  if (!request.reason.trim()) {
    return { field: 'reason', message: '必须说明调价原因' };
  }
  if (request.quantity !== null && request.quantity <= 0) {
    return { field: 'quantity', message: '数量必须大于 0' };
  }
  if (
    request.platformFeeRate !== null &&
    (request.platformFeeRate < 0 || request.platformFeeRate >= 1)
  ) {
    return { field: 'platformFeeRate', message: '平台费率是小数口径（0.15 表示 15%）' };
  }
  if (request.logisticsCostPerUnit !== null && request.logisticsCostPerUnit < 0) {
    return { field: 'logisticsCostPerUnit', message: '物流每单位运费不能为负' };
  }
  if (
    request.currency.toUpperCase() !== request.costCurrency.toUpperCase() &&
    (request.fxRate === null || request.fxRate <= 0)
  ) {
    return {
      field: 'fxRate',
      message: '售价币种与成本币种不同，必须给汇率（1 成本币种 = X 售价币种）—— 绝不按 1:1 折算',
    };
  }
  return null;
}

/** 台账筛选。 */
export interface PriceAdjustmentFilters {
  status: PriceAdjustmentStatus | '';
  skuId: string;
  platformCode: string;
  siteCode: string;
  keyword: string;
  sort: PriceAdjustmentSort;
}

export function emptyPriceAdjustmentFilters(): PriceAdjustmentFilters {
  return {
    status: '',
    skuId: '',
    platformCode: '',
    siteCode: '',
    keyword: '',
    sort: 'NEWEST',
  };
}

/** 一张调价单的人话摘要（台账列表里用）。 */
export function priceAdjustmentSummary(detail: PriceAdjustmentDetail): string {
  return [
    detail.skuCode,
    `${detail.platformCode}/${detail.siteCode}`,
    `${priceText(detail.oldPrice)} → ${priceText(detail.newPrice)} ${detail.currency}`,
    detail.reason,
  ]
    .filter(Boolean)
    .join(' · ');
}

// ================================================================ 端点

export function fetchPriceAdjustmentCapabilities(): Promise<PriceAdjustmentCapabilities> {
  return api<PriceAdjustmentCapabilities>('catalog/price-adjustments/capabilities');
}

/** 读当前价（🔴 取不到时后端返回 price=null 并在 reason 里说清为什么）。 */
export function fetchCurrentPrice(
  skuId: string,
  platformCode: string,
  siteCode: string,
  currency: string,
): Promise<PriceAdjustmentCurrentPrice> {
  const params = new URLSearchParams({
    skuId,
    platformCode,
    siteCode,
    currency,
  });
  return api<PriceAdjustmentCurrentPrice>(
    `catalog/price-adjustments/current-price?${params.toString()}`,
  );
}

/** 创建调价单（草稿）。 */
export function createPriceAdjustment(
  request: PriceAdjustmentRequest,
): Promise<PriceAdjustmentDetail> {
  return api<PriceAdjustmentDetail>('catalog/price-adjustments', 'POST', request);
}

/** 调价单台账（真分页 + 真筛选 + 真排序）。 */
export function fetchPriceAdjustments(
  filters: PriceAdjustmentFilters,
  page = 0,
  size = 20,
): Promise<PriceAdjustmentPage> {
  const params = new URLSearchParams();
  if (filters.status) params.set('status', filters.status);
  if (filters.skuId.trim()) params.set('skuId', filters.skuId.trim());
  if (filters.platformCode.trim()) params.set('platformCode', filters.platformCode.trim());
  if (filters.siteCode.trim()) params.set('siteCode', filters.siteCode.trim());
  if (filters.keyword.trim()) params.set('keyword', filters.keyword.trim());
  if (filters.sort) params.set('sort', filters.sort);
  params.set('page', String(page));
  params.set('size', String(size));
  return api<PriceAdjustmentPage>(`catalog/price-adjustments?${params.toString()}`);
}

/** 调价单详情（后端读详情时会顺带拉一次审批状态）。 */
export function fetchPriceAdjustment(id: string): Promise<PriceAdjustmentDetail> {
  return api<PriceAdjustmentDetail>(`catalog/price-adjustments/${id}`);
}

/** 提交（草稿 → 待审 / 已批）。🔴 必须带上你读到的版本号。 */
export function submitPriceAdjustment(
  id: string,
  expectedVersion: number,
  comment?: string,
): Promise<PriceAdjustmentDetail> {
  return api<PriceAdjustmentDetail>(
    `catalog/price-adjustments/${id}/submit`,
    'POST',
    { expectedVersion, comment },
  );
}

/** 执行（已批 → 已执行）。🔴 必须带上你读到的版本号。 */
export function executePriceAdjustment(
  id: string,
  expectedVersion: number,
  comment?: string,
): Promise<PriceAdjustmentDetail> {
  return api<PriceAdjustmentDetail>(
    `catalog/price-adjustments/${id}/execute`,
    'POST',
    { expectedVersion, comment },
  );
}

/** 驳回（草稿 / 待审 → 已驳回）。🔴 必须带上你读到的版本号。 */
export function rejectPriceAdjustment(
  id: string,
  expectedVersion: number,
  reason: string,
): Promise<PriceAdjustmentDetail> {
  return api<PriceAdjustmentDetail>(`catalog/price-adjustments/${id}/reject`, 'POST', {
    expectedVersion,
    reason,
  });
}

/** 刷新审批状态（拉取式同步：把 17 组织那边的结论取回来）。 */
export function refreshPriceAdjustmentApproval(id: string): Promise<PriceAdjustmentDetail> {
  return api<PriceAdjustmentDetail>(`catalog/price-adjustments/${id}/approval/refresh`, 'POST');
}

/** 待回写事实（下游 5.4.2.1 消费它）。 */
export function fetchPriceAdjustmentWritebacks(
  status: string,
  page = 0,
  size = 20,
): Promise<PriceAdjustmentWritebackPage> {
  const params = new URLSearchParams();
  if (status) params.set('status', status);
  params.set('page', String(page));
  params.set('size', String(size));
  return api<PriceAdjustmentWritebackPage>(
    `catalog/price-adjustments/writebacks?${params.toString()}`,
  );
}
