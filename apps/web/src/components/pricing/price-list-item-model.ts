import { api } from '../inventory/api';

/**
 * 5.1.1.2 价目项批量维护前端模型。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：在价目表内维护 SKU 价格，支持按分类/品牌/SKU 范围批量设价、阶梯价（按数量门槛）、Excel 导入导出，保存前预览影响范围。
 *
 * **输入**：价目项录入/批量操作（固定值、按百分比、按类目统一）；`price_list_item` 记录。
 *
 * **输出**：价目项记录（SKU、单价、货币、数量门槛、生效区间）；导入校验报告。
 *
 * **关系**：上游 = 5.1.1.1、3.1.2.1 SKU；下游 = 5.3 测算、5.4 调价。**同优先级多条有效价目必须报错让用户选**，不随机取（实体目录 catalog 价格规则）。
 *
 * </blockquote>
 *
 * <h2>🔴 一价目一币种</h2>
 *
 * <p>价目项**没有**币种输入框：它的币种只能来自所属价目表（服务端从表头带出来）。
 * {@link PriceListItemInput} 上没有 {@code currency} 字段 —— 与后端逐字一致。
 *
 * <h2>🔴 阶梯 ≠ 冲突</h2>
 *
 * <p>同一个 SKU 的多行是**阶梯价**（数量门槛不同），不是冲突。
 * 只有「同 SKU + 同数量门槛 + 同优先级 + 生效区间重叠」才是歧义。
 *
 * <h2>🔴 null 绝不渲染成 0</h2>
 *
 * <p>「没有基准价」「没有冲突」「没有导入结果」这些都是**不同的状态**，
 * 一律显示可读文案而不是 0。
 */

/** 批量设价的模式（与服务端 {@code BatchMode} 逐字对应）。 */
export type BatchMode = 'FIXED' | 'PERCENTAGE' | 'CATEGORY_UNIFIED';

/** 批量设价的范围。 */
export type BatchScope = 'CATEGORY' | 'BRAND' | 'SKU_SET';

/** 一条价目项在解析时的选择状态。 */
export type SelectionState = 'SELECTED' | 'AMBIGUOUS' | 'LOWER_PRIORITY' | 'NOT_APPLICABLE';

/** 能力声明里的一条口径说明。 */
export interface CapabilityNote {
  title: string;
  content: string;
}

/** 能力声明。 */
export interface PriceListItemCapabilities {
  modes: BatchMode[];
  scopes: BatchScope[];
  selectionStates: SelectionState[];
  notes: CapabilityNote[];
  currencyNote: string;
  tierNote: string;
  priorityNote: string;
  conflictNote: string;
  importNote: string;
  exportNote: string;
  upstreamNote: string;
  downstreamNote: string;
}

/** 一条价目项（读）。 */
export interface PriceListItemView {
  id: string;
  priceListId: string;
  priceListCode: string;
  /** 币种（**读自所属价目表**，不是价目项自己的字段）。 */
  currency: string;
  skuId: string;
  skuCode: string;
  skuName: string;
  unitPrice: number;
  minQuantity: number;
  priority: number;
  priorityLabel: string;
  validFrom: string;
  validTo: string | null;
  enabled: boolean;
  effectiveNow: boolean;
  note: string;
  version: number;
  createdBy: string | null;
  createdAt: string;
  updatedAt: string;
}

/** 价目项列表（分页）。 */
export interface PriceListItemPage {
  items: PriceListItemView[];
  page: number;
  size: number;
  total: number;
}

/** 解析时的一条候选。 */
export interface PriceListItemCandidate {
  id: string;
  skuCode: string;
  skuName: string;
  unitPrice: number;
  minQuantity: number;
  priority: number;
  validFrom: string;
  validTo: string | null;
  selectionState: SelectionState;
  selectionStateLabel: string;
}

/** 「这个 SKU 此刻该卖多少」的解析结果。 */
export interface PriceListItemResolution {
  priceListId: string;
  priceListCode: string;
  currency: string;
  skuId: string;
  skuCode: string;
  skuName: string;
  at: string;
  quantity: number | null;
  selected: PriceListItemView | null;
  unitPrice: number | null;
  candidates: PriceListItemCandidate[];
  ambiguous: boolean;
  ambiguityProblem: string | null;
  warnings: string[];
  note: string;
}

/** 一组冲突（同 SKU、同档位、同优先级、区间重叠）。 */
export interface PriceListItemConflict {
  skuId: string;
  skuCode: string;
  skuName: string;
  minQuantity: number;
  priority: number;
  overlapFrom: string | null;
  overlapTo: string | null;
  candidates: PriceListItemCandidate[];
}

/** 批量操作里一个 SKU 的预览行。 */
export interface PriceListItemBatchLine {
  skuId: string | null;
  skuCode: string;
  skuName: string;
  category: string | null;
  brand: string | null;
  basePrice: number | null;
  newPrice: number | null;
  action: 'CREATE' | 'UPDATE' | 'SKIP';
  reason: string | null;
  conflict: boolean;
  minQuantity: number;
  priority: number;
}

/** 批量操作的影响范围预览。 */
export interface PriceListItemBatchPreview {
  mode: BatchMode;
  modeLabel: string;
  scope: BatchScope | '';
  scopeLabel: string;
  currency: string;
  matchedSkus: number;
  createdItems: number;
  updatedItems: number;
  skippedSkus: number;
  conflictSkus: number;
  lines: PriceListItemBatchLine[];
  lineLimit: number;
  truncated: boolean;
  conflicts: PriceListItemConflict[];
  warnings: string[];
  summary: string;
}

/** 批量执行的结果。 */
export interface PriceListItemBatchResult {
  mode: BatchMode;
  currency: string;
  matchedSkus: number;
  createdItems: number;
  updatedItems: number;
  skippedSkus: number;
  conflictSkus: number;
  conflicts: PriceListItemConflict[];
  warnings: string[];
  summary: string;
}

/** 「按类目统一」模式下的一个分类与其统一价。 */
export interface PriceListItemCategoryPrice {
  category: string;
  unitPrice: number;
}

/** 一次批量设价请求（预览与执行共用同一个形状）。 */
export interface PriceListItemBatchRequest {
  mode: BatchMode;
  scope: BatchScope | null;
  category: string | null;
  brand: string | null;
  skuCodes: string[];
  categoryPrices: PriceListItemCategoryPrice[];
  unitPrice: number | null;
  percent: number | null;
  minQuantity: number;
  priority: number;
  validFrom: string;
  validTo: string | null;
  enabled: boolean;
  note: string;
}

/** 导入校验报告里的一行。 */
export interface PriceListItemImportRowReport {
  rowNo: number;
  skuCode: string;
  skuName: string | null;
  unitPrice: number | null;
  minQuantity: number;
  priority: number;
  validFrom: string | null;
  validTo: string | null;
  declaredCurrency: string | null;
  enabled: boolean;
  status: 'OK' | 'ERROR';
  messages: string[];
}

/** 导入校验报告。 */
export interface PriceListItemImportReport {
  batchId: string;
  fileName: string;
  status: 'PREVIEW' | 'APPLIED' | 'REJECTED';
  totalRows: number;
  okRows: number;
  errorRows: number;
  warningRows: number;
  appliedRows: number;
  applied: boolean;
  rows: PriceListItemImportRowReport[];
  summary: string;
}

/** 一条价目项（录入 / 修改）。🔴 没有币种字段 —— 币种来自价目表。 */
export interface PriceListItemInput {
  skuCode: string;
  unitPrice: number | null;
  minQuantity: number | null;
  priority: number | null;
  validFrom: string;
  validTo: string | null;
  enabled: boolean;
  note: string;
  version: number | null;
}

/** 表单校验错误。 */
export interface ValidationError {
  field: string;
  message: string;
}

// ================================================================ 纯函数

/** 批量模式的选项（页面上的选择器）。 */
export function batchModeOptions(): { value: BatchMode; label: string }[] {
  return [
    { value: 'FIXED', label: '固定值' },
    { value: 'PERCENTAGE', label: '按百分比' },
    { value: 'CATEGORY_UNIFIED', label: '按类目统一' },
  ];
}

/** 批量范围类型的选项。 */
export function batchScopeOptions(): { value: BatchScope; label: string }[] {
  return [
    { value: 'CATEGORY', label: '按分类' },
    { value: 'BRAND', label: '按品牌' },
    { value: 'SKU_SET', label: '按 SKU 清单' },
  ];
}

/**
 * 数量门槛的展示文本。
 *
 * <p>🔴 0 = 基础档（不是「数量 0」）。
 */
export function quantityText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '基础档';
  if (value === 0) return '基础档';
  return `${value}`;
}

/**
 * 优先级的展示文本。
 *
 * <p>🔴 数字**越小**优先级越高 —— 这条约定必须写在界面上，别让人猜。
 */
export function priorityText(priority: number | null | undefined): string {
  if (priority === null || priority === undefined) return '0（数字越小越优先）';
  return `${priority}（数字越小越优先）`;
}

/** 单价的展示文本（保留服务端给的数值，不在浏览器里重算）。 */
export function priceText(value: number | null | undefined, currency?: string | null): string {
  if (value === null || value === undefined) return '—';
  const text = Number.isInteger(value)
    ? String(value)
    : value.toFixed(6).replace(/0+$/, '').replace(/\.$/, '');
  return currency ? `${text} ${currency}` : text;
}

/** 时刻 → 本地可读文本（保留时区偏移，跨时区对账时要看得出是哪个时区）。 */
export function formatInstant(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const pad = (n: number) => String(n).padStart(2, '0');
  const offset = -date.getTimezoneOffset();
  const sign = offset >= 0 ? '+' : '-';
  const absolute = Math.abs(offset);
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}` +
    ` UTC${sign}${pad(Math.floor(absolute / 60))}:${pad(Math.abs(absolute % 60))}`
  );
}

/** 生效区间的文字描述（半开区间 [起点, 终点)）。 */
export function intervalText(
  validFrom: string | null | undefined,
  validTo: string | null | undefined,
): string {
  const from = validFrom ? formatInstant(validFrom) : '（未设置）';
  if (!validTo) return `${from} → 长期有效`;
  return `${from} → ${formatInstant(validTo)}（不含）`;
}

/** 生效状态的徽标语气。 */
export function effectiveTone(effectiveNow: boolean | null | undefined): 'success' | 'neutral' {
  return effectiveNow ? 'success' : 'neutral';
}

/** 生效状态的文字。 */
export function effectiveText(effectiveNow: boolean | null | undefined): string {
  if (effectiveNow === null || effectiveNow === undefined) return '生效状态未知';
  return effectiveNow ? '此刻生效' : '此刻不生效';
}

/** 选择状态的徽标语气。 */
export function selectionTone(
  state: SelectionState | null | undefined,
): 'danger' | 'success' | 'neutral' {
  if (state === 'SELECTED') return 'success';
  if (state === 'AMBIGUOUS') return 'danger';
  return 'neutral';
}

/** 批量动作的徽标语气。 */
export function actionTone(action: string | null | undefined): 'success' | 'neutral' | 'warning' {
  if (action === 'CREATE') return 'success';
  if (action === 'UPDATE') return 'neutral';
  return 'warning';
}

/** 批量动作的文字。 */
export function actionText(action: string | null | undefined): string {
  if (action === 'CREATE') return '新增一条';
  if (action === 'UPDATE') return '覆盖同槽位的既有价目项';
  if (action === 'SKIP') return '跳过';
  return action ?? '—';
}

/** 导入批次状态的徽标语气。 */
export function importStatusTone(
  status: string | null | undefined,
): 'success' | 'warning' | 'danger' {
  if (status === 'APPLIED') return 'success';
  if (status === 'PREVIEW') return 'warning';
  return 'danger';
}

/** 导入批次状态的文字。 */
export function importStatusText(status: string | null | undefined): string {
  if (status === 'APPLIED') return '已落库';
  if (status === 'PREVIEW') return '仅校验（未落库）';
  if (status === 'REJECTED') return '已拒绝（整批未落库）';
  return status ?? '—';
}

/** 空表单。 */
export function emptyPriceListItemInput(): PriceListItemInput {
  return {
    skuCode: '',
    unitPrice: null,
    minQuantity: 0,
    priority: 0,
    validFrom: new Date().toISOString(),
    validTo: null,
    enabled: true,
    note: '',
    version: null,
  };
}

/** 详情 → 表单（编辑时用）。 */
export function toPriceListItemInput(item: PriceListItemView): PriceListItemInput {
  return {
    skuCode: item.skuCode,
    unitPrice: item.unitPrice,
    minQuantity: item.minQuantity,
    priority: item.priority,
    validFrom: item.validFrom,
    validTo: item.validTo,
    enabled: item.enabled,
    note: item.note,
    version: item.version,
  };
}

/**
 * 校验一条价目项。
 *
 * <p>🔴 这里**没有**币种校验 —— 价目项根本没有币种字段（币种来自价目表）。
 */
export function validatePriceListItem(input: PriceListItemInput): ValidationError | null {
  if (!input.skuCode.trim()) return { field: 'skuCode', message: 'SKU 编码必填' };
  if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(input.skuCode.trim())) {
    return {
      field: 'skuCode',
      message: 'SKU 编码只能包含字母、数字、短横线与下划线（最多 64 位）',
    };
  }
  if (input.unitPrice === null || Number.isNaN(input.unitPrice)) {
    return { field: 'unitPrice', message: '单价必填' };
  }
  if (input.unitPrice < 0) return { field: 'unitPrice', message: '单价不能是负数' };
  if (input.minQuantity !== null && input.minQuantity < 0) {
    return { field: 'minQuantity', message: '数量门槛不能是负数' };
  }
  if (!input.validFrom) return { field: 'validFrom', message: '生效起点必填' };
  if (input.validTo) {
    const from = new Date(input.validFrom).getTime();
    const to = new Date(input.validTo).getTime();
    if (Number.isNaN(from) || Number.isNaN(to)) {
      return { field: 'validTo', message: '生效区间的时间格式认不出来' };
    }
    if (to <= from) {
      return { field: 'validTo', message: '生效终点必须晚于生效起点（区间是 [起点, 终点)）' };
    }
  }
  return null;
}

/** 表单 → 提交载荷（SKU 编码转大写、时间补齐时区）。 */
export function toPriceListItemPayload(input: PriceListItemInput): PriceListItemInput {
  return {
    ...input,
    skuCode: input.skuCode.trim().toUpperCase(),
    note: input.note.trim(),
  };
}

/** 空批量请求。 */
export function emptyBatchRequest(): PriceListItemBatchRequest {
  return {
    mode: 'FIXED',
    scope: 'CATEGORY',
    category: '',
    brand: '',
    skuCodes: [],
    categoryPrices: [],
    unitPrice: null,
    percent: null,
    minQuantity: 0,
    priority: 0,
    validFrom: new Date().toISOString(),
    validTo: null,
    enabled: true,
    note: '',
  };
}

/**
 * 校验批量请求（按模式与范围各查一遍必填项）。
 *
 * <p>🔴 校验口径与服务端一致 —— 但服务端还会再校验一次（前端校验只是提前反馈）。
 */
export function validateBatchRequest(request: PriceListItemBatchRequest): ValidationError | null {
  if (request.mode === 'CATEGORY_UNIFIED') {
    if (request.categoryPrices.length === 0) {
      return { field: 'categoryPrices', message: '「按类目统一」必须给至少一个分类与价格' };
    }
    for (const price of request.categoryPrices) {
      if (!price.category.trim()) return { field: 'categoryPrices', message: '分类不能为空' };
      if (price.unitPrice === null || Number.isNaN(price.unitPrice) || price.unitPrice < 0) {
        return { field: 'categoryPrices', message: `分类「${price.category}」的价格不合法` };
      }
    }
  } else {
    if (!request.scope) return { field: 'scope', message: '必须选择批量范围' };
    if (request.scope === 'CATEGORY' && !request.category?.trim()) {
      return { field: 'category', message: '范围是「按分类」时必须给分类' };
    }
    if (request.scope === 'BRAND' && !request.brand?.trim()) {
      return { field: 'brand', message: '范围是「按品牌」时必须给品牌' };
    }
    if (request.scope === 'SKU_SET' && request.skuCodes.length === 0) {
      return { field: 'skuCodes', message: '范围是「按 SKU 清单」时必须给至少一个 SKU 编码' };
    }
    if (request.mode === 'FIXED') {
      if (request.unitPrice === null || Number.isNaN(request.unitPrice) || request.unitPrice < 0) {
        return { field: 'unitPrice', message: '「固定值」必须给单价' };
      }
    }
    if (request.mode === 'PERCENTAGE') {
      if (request.percent === null || Number.isNaN(request.percent)) {
        return {
          field: 'percent',
          message: '「按百分比」必须给涨跌幅（10 = 涨 10%，-10 = 降 10%）',
        };
      }
      if (request.percent <= -100) {
        return { field: 'percent', message: '涨跌幅不能小于等于 -100%（否则算出来是负数）' };
      }
    }
  }
  if (request.minQuantity < 0) return { field: 'minQuantity', message: '数量门槛不能是负数' };
  if (!request.validFrom) return { field: 'validFrom', message: '生效起点必填' };
  if (request.validTo) {
    const from = new Date(request.validFrom).getTime();
    const to = new Date(request.validTo).getTime();
    if (Number.isNaN(from) || Number.isNaN(to)) {
      return { field: 'validTo', message: '生效区间的时间格式认不出来' };
    }
    if (to <= from) {
      return { field: 'validTo', message: '生效终点必须晚于生效起点（区间是 [起点, 终点)）' };
    }
  }
  return null;
}

/** 表单 → 提交载荷（补齐数量门槛与优先级的默认值）。 */
export function toBatchPayload(request: PriceListItemBatchRequest): PriceListItemBatchRequest {
  return {
    ...request,
    category: request.category ? request.category.trim() : null,
    brand: request.brand ? request.brand.trim() : null,
    skuCodes: request.skuCodes.map((code) => code.trim().toUpperCase()).filter(Boolean),
    note: request.note.trim(),
  };
}

/**
 * 批量预览的摘要行（页面上直接显示，服务端算好的那些数）。
 *
 * <p>🔴 前端**不重算**任何统计 —— 命中数、冲突数都以服务端返回为准。
 */
export function batchStats(preview: PriceListItemBatchPreview | null | undefined): {
  label: string;
  value: number;
  hint: string;
}[] {
  if (!preview) return [];
  return [
    { label: '命中 SKU', value: preview.matchedSkus, hint: '这次批量操作命中的 SKU 数' },
    { label: '新增价目项', value: preview.createdItems, hint: '这些 SKU 还没有同槽位的价目项' },
    {
      label: '覆盖价目项',
      value: preview.updatedItems,
      hint: '已有同槽位（同 SKU / 同档位 / 同生效起点）的价目项',
    },
    { label: '跳过 SKU', value: preview.skippedSkus, hint: '跳过的原因逐条写在明细里' },
    { label: '冲突 SKU', value: preview.conflictSkus, hint: '会造成同优先级歧义的 SKU 数' },
  ];
}

/** 冲突的窗口文字。 */
export function conflictWindowText(conflict: PriceListItemConflict): string {
  const from = conflict.overlapFrom ? formatInstant(conflict.overlapFrom) : '（未设置）';
  if (!conflict.overlapTo) return `${from} → 长期有效（这两条在这段时间里无法唯一解析）`;
  return `${from} → ${formatInstant(conflict.overlapTo)}（不含）（这两条在这段时间里无法唯一解析）`;
}

/** 文件 → Base64（浏览器同源代理只转发 JSON，multipart 过不去）。 */
export function fileToBase64(file: File): Promise<string> {
  return file.arrayBuffer().then((buffer) => {
    const bytes = new Uint8Array(buffer);
    let binary = '';
    const chunk = 0x8000;
    for (let i = 0; i < bytes.length; i += chunk) {
      binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
    }
    return btoa(binary);
  });
}

// ================================================================ 端点

export function fetchPriceListItemCapabilities(): Promise<PriceListItemCapabilities> {
  return api<PriceListItemCapabilities>('catalog/price-lists/item-capabilities');
}

export function fetchPriceListItems(
  priceListId: string,
  query: {
    keyword?: string | undefined;
    skuCode?: string | null | undefined;
    enabled?: boolean | null | undefined;
    effectiveNow?: boolean | null | undefined;
    page?: number | undefined;
    size?: number | undefined;
  } = {},
): Promise<PriceListItemPage> {
  const params = new URLSearchParams();
  if (query.keyword) params.set('keyword', query.keyword);
  if (query.skuCode) params.set('skuCode', query.skuCode);
  if (query.enabled !== null && query.enabled !== undefined) {
    params.set('enabled', String(query.enabled));
  }
  if (query.effectiveNow !== null && query.effectiveNow !== undefined) {
    params.set('effectiveNow', String(query.effectiveNow));
  }
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 20));
  return api<PriceListItemPage>(
    `catalog/price-lists/lists/${priceListId}/items?${params.toString()}`,
  );
}

export function fetchPriceListItem(
  priceListId: string,
  itemId: string,
): Promise<PriceListItemView> {
  return api<PriceListItemView>(`catalog/price-lists/lists/${priceListId}/items/${itemId}`);
}

export function createPriceListItem(
  priceListId: string,
  input: PriceListItemInput,
): Promise<PriceListItemView> {
  return api<PriceListItemView>(`catalog/price-lists/lists/${priceListId}/items`, 'POST', input);
}

export function updatePriceListItem(
  priceListId: string,
  itemId: string,
  input: PriceListItemInput,
): Promise<PriceListItemView> {
  return api<PriceListItemView>(
    `catalog/price-lists/lists/${priceListId}/items/${itemId}`,
    'PUT',
    input,
  );
}

export function deletePriceListItem(priceListId: string, itemId: string): Promise<void> {
  return api<void>(`catalog/price-lists/lists/${priceListId}/items/${itemId}`, 'DELETE');
}

export function fetchPriceListItemConflicts(priceListId: string): Promise<PriceListItemConflict[]> {
  return api<PriceListItemConflict[]>(`catalog/price-lists/lists/${priceListId}/items/conflicts`);
}

export function resolvePriceListItem(
  priceListId: string,
  query: { skuCode: string; quantity?: number | null | undefined; at?: string | null | undefined },
): Promise<PriceListItemResolution> {
  const params = new URLSearchParams();
  params.set('skuCode', query.skuCode.trim().toUpperCase());
  if (query.quantity !== null && query.quantity !== undefined) {
    params.set('quantity', String(query.quantity));
  }
  if (query.at) params.set('at', query.at);
  return api<PriceListItemResolution>(
    `catalog/price-lists/lists/${priceListId}/items/resolve?${params.toString()}`,
  );
}

export function previewPriceListBatch(
  priceListId: string,
  request: PriceListItemBatchRequest,
): Promise<PriceListItemBatchPreview> {
  return api<PriceListItemBatchPreview>(
    `catalog/price-lists/lists/${priceListId}/items/preview`,
    'POST',
    request,
  );
}

export function applyPriceListBatch(
  priceListId: string,
  request: PriceListItemBatchRequest,
): Promise<PriceListItemBatchResult> {
  return api<PriceListItemBatchResult>(
    `catalog/price-lists/lists/${priceListId}/items/apply`,
    'POST',
    request,
  );
}

export function importPriceListItems(
  priceListId: string,
  payload: { fileName: string; contentBase64: string; apply: boolean },
): Promise<PriceListItemImportReport> {
  return api<PriceListItemImportReport>(
    `catalog/price-lists/lists/${priceListId}/items/import`,
    'POST',
    payload,
  );
}

/**
 * 导出的浏览器地址（普通 `<a download>`，不走 api()：它无条件设 JSON content-type）。
 *
 * <p>🔴 代理对 xlsx 的 content-type 是**原样透传**的（binaryPassthrough），
 * 所以下载到的就是服务端 POI 生成的那个真实工作簿。
 */
export function priceListItemExportUrl(priceListId: string): string {
  return `/api/catalog/price-lists/lists/${priceListId}/items/export`;
}
