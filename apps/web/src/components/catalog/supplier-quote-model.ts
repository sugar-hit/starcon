import type {
  SupplierQuote,
  SupplierQuoteEffectiveResult,
  SupplierQuoteInput,
  SupplierQuoteOptions,
  SupplierQuoteOverview,
  SupplierQuotePage,
  SupplierQuotePriceTier,
  SupplierQuotePriceTierInput,
} from '../../../../../packages/api-contracts/src/catalog';
import { ApiError, api } from './api';

/**
 * 3.6.2.1 供应商报价前端模型。
 *
 * <p>这个页面上最容易出错的三处：
 *
 * <ul>
 *   <li><b>「需人工选择」是一个状态，不是一个提示</b>。
 *       后端在同一优先级有多条有效报价时把 {@code selected} 置 null 并逐条标
 *       {@code AMBIGUOUS}，严格取价端点直接 409 —— 那是蓝图点名的
 *       「同一优先级多条有效报价必须报错让用户选，不能随机取一条」。
 *       所以这里有 {@link SelectionState} 三态，**界面不许自己挑一条**；
 *       挑一条的动作必须落到"改优先级"上，改完歧义自然消失。
 *   <li><b>数量与金额一律按字符串收发</b>。
 *       单价 3.80 在 JS 里是 number，0.1+0.2 那种误差会在阶梯取价上放大成
 *       「买 137 件算成 3.1999999」。表单里用字符串、提交前才转 number，
 *       与后端 numeric(19,4) 的口径一致。
 *   <li><b>档位按起订量升序</b>。提交时前端先排序（与后端写入前排序同一口径），
 *       所以运营按金额从小到大录档也不会被拒。
 * </ul>
 *
 * <p>本文件的校验与 `SupplierQuoteRules` 逐字同口径。抄一份到前端是为了少一次往返，
 * 服务层那份才是权威。
 */

/** 有效报价的选择状态（与后端 `SupplierQuoteRules.selectionStates` 逐字一致）。 */
export type SelectionState = 'SELECTED' | 'AMBIGUOUS' | 'LOWER_PRIORITY';

export const SELECTION_STATE_LABELS: Record<SelectionState, string> = {
  SELECTED: '默认采用',
  AMBIGUOUS: '需人工选择',
  LOWER_PRIORITY: '备选',
};

export const STATUS_LABELS: Record<string, string> = {
  ACTIVE: '启用中',
  DISABLED: '已停用',
};

/** 优先级词表（1 = 首选）。刻意给出提示文案而不是裸数字输入。 */
export const PRIORITY_HINT = '1 = 首选，越小越优先。同一优先级有多条有效报价时，系统不会替你选。';

export type QuoteTierForm = {
  minQuantity: string;
  unitPrice: string;
  note: string;
};

export type QuoteForm = {
  /** 报价 id；新建时为空串。保存时用它区分 POST（新建）与 PUT（改这一条）。 */
  id: string;
  supplierId: string;
  skuId: string;
  supplierSkuCode: string;
  purchaseUnitCode: string;
  currency: string;
  moqQuantity: string;
  leadTimeDays: string;
  validFrom: string;
  validTo: string;
  priority: string;
  status: string;
  tiers: QuoteTierForm[];
  note: string;
  version: number;
};

export type QuoteListQuery = {
  keyword: string;
  skuId: string;
  supplierId: string;
  currency: string;
  status: string;
  effectiveOnly: boolean;
  ambiguousOnly: boolean;
  at: string;
  page: number;
  size: number;
};

export function emptyQuoteListQuery(): QuoteListQuery {
  return {
    keyword: '',
    skuId: '',
    supplierId: '',
    currency: '',
    status: '',
    effectiveOnly: false,
    ambiguousOnly: false,
    at: '',
    page: 0,
    size: 20,
  };
}

/**
 * 空报价表单。
 *
 * <p>起订量默认 `1`、交期默认 `0`、优先级默认 `1`：这三个在业务上都有确切含义
 * （起订 1 = 零售买一、0 天 = 当日发货、优先级 1 = 首选），不是"还没填"。
 * 留空反而会让运营以为系统要问一次。
 *
 * <p>默认给一档阶梯：没有阶梯的报价取不出单价（规则 R5），而 1688 / 工厂报价
 * 绝大多数就是单档起步。
 */
export function emptyQuoteForm(supplierId = '', skuId = ''): QuoteForm {
  const today = new Date().toISOString().slice(0, 10);
  return {
    id: '',
    supplierId,
    skuId,
    supplierSkuCode: '',
    purchaseUnitCode: 'PCS',
    currency: 'USD',
    moqQuantity: '1',
    leadTimeDays: '0',
    validFrom: today,
    validTo: '',
    priority: '1',
    status: 'ACTIVE',
    tiers: [{ minQuantity: '1', unitPrice: '', note: '' }],
    note: '',
    version: 0,
  };
}

export function toQuoteForm(quote: SupplierQuote): QuoteForm {
  return {
    id: quote.id,
    supplierId: quote.supplierId,
    skuId: quote.skuId,
    supplierSkuCode: quote.supplierSkuCode,
    purchaseUnitCode: quote.purchaseUnitCode,
    currency: quote.currency,
    moqQuantity: quote.moqQuantity === null ? '' : trimNumber(quote.moqQuantity),
    leadTimeDays: String(quote.leadTimeDays ?? 0),
    validFrom: quote.validFrom,
    validTo: quote.validTo ?? '',
    priority: String(quote.priority ?? 1),
    status: quote.status,
    tiers: quote.tiers.map((tier) => ({
      minQuantity: trimNumber(tier.minQuantity),
      unitPrice: trimNumber(tier.unitPrice),
      note: tier.note ?? '',
    })),
    note: quote.note ?? '',
    version: quote.version,
  };
}

export function toQuoteInput(form: QuoteForm): SupplierQuoteInput {
  return {
    supplierId: form.supplierId,
    skuId: form.skuId,
    supplierSkuCode: form.supplierSkuCode.trim(),
    purchaseUnitCode: form.purchaseUnitCode.trim(),
    currency: form.currency.trim().toUpperCase(),
    moqQuantity: form.moqQuantity.trim() === '' ? null : Number(form.moqQuantity),
    leadTimeDays: form.leadTimeDays.trim() === '' ? 0 : Number(form.leadTimeDays),
    validFrom: form.validFrom,
    validTo: form.validTo.trim() === '' ? null : form.validTo.trim(),
    priority: form.priority.trim() === '' ? 1 : Number(form.priority),
    status: form.status,
    // 按起订量升序：后端写入前也排一次，这里排是为了让用户看到的顺序
    // 与提交进去的顺序一致（规则 R5）。
    tiers: [...form.tiers]
      .sort((a, b) => Number(a.minQuantity) - Number(b.minQuantity))
      .map(
        (tier): SupplierQuotePriceTierInput => ({
          minQuantity: Number(tier.minQuantity),
          unitPrice: Number(tier.unitPrice),
          note: tier.note.trim() === '' ? null : tier.note.trim(),
        }),
      ),
    note: form.note,
    version: form.version,
  };
}

/** `numeric` 值去掉多余的尾随 0，避免表单里出现 3.800000000。 */
function trimNumber(value: number | null): string {
  if (value === null || value === undefined) return '';
  return String(value);
}

/**
 * 报价表单校验（规则 **R3** / **R4** / **R5**）。
 *
 * <p>与 `SupplierQuoteRules` 逐字同口径。抄一份到前端是为了少一次往返，
 * 服务层那份才是权威 —— 这里是**预判**，不是第二套规则。
 */
export function quoteFormProblem(form: QuoteForm): string | null {
  if (!form.supplierId) return '必须选择供应商';
  if (!form.skuId) return '必须选择 SKU';

  const skuCode = form.supplierSkuCode.trim();
  if (!skuCode) return '供应商货号必填：06 采购单下给对方时靠它对账';
  if (skuCode.length > 64) return '供应商货号最多 64 个字符';

  const unit = form.purchaseUnitCode.trim();
  if (!unit) return '采购单位必填：报价按单位报，没有单位的单价无法与 06 采购单对齐';

  const currency = form.currency.trim().toUpperCase();
  if (!currency) return '币种必填';
  if (!/^[A-Z]{3}$/.test(currency)) return '币种请用 ISO 4217 三位代码（如 USD / EUR / CNY）';

  const moq = form.moqQuantity.trim();
  if (!moq) return '起订量必填：报价必须说清这个 SKU 最少下多少';
  if (!isFiniteNumber(moq)) return '起订量请填数字';
  if (Number(moq) <= 0) return '起订量必须大于 0';

  const lead = form.leadTimeDays.trim();
  if (lead !== '' && !isFiniteNumber(lead)) return '交期请填整数天数（0 = 当日发货）';
  const leadDays = lead === '' ? 0 : Number(lead);
  if (!Number.isInteger(leadDays)) return '交期请填整数天数';
  if (leadDays < 0) return '交期不能是负数';
  if (leadDays > 365) return '交期最多 365 天，请确认没有把月填成天';

  if (!form.validFrom) return '生效日期必填：报价必须说清从哪天开始有效';
  if (form.validTo.trim() && form.validTo.trim() < form.validFrom)
    return '有效期结束日不能早于生效日期';

  const priority = form.priority.trim();
  if (priority !== '') {
    if (!isFiniteNumber(priority)) return '优先级请填 1~99 的整数（1 = 首选）';
    const p = Number(priority);
    if (!Number.isInteger(p)) return '优先级请填整数';
    if (p < 1) return '优先级从 1 开始（1 = 首选）';
    if (p > 99) return '优先级最多 99，1 表示首选';
  }

  if (form.tiers.length === 0) return '阶梯价至少一档：没有阶梯的报价取不出单价';
  const sorted = [...form.tiers].sort((a, b) => Number(a.minQuantity) - Number(b.minQuantity));
  for (let i = 0; i < sorted.length; i++) {
    const min = sorted[i]!.minQuantity.trim();
    const price = sorted[i]!.unitPrice.trim();
    if (!isFiniteNumber(min)) return `第 ${i + 1} 档的起订量请填数字`;
    if (Number(min) <= 0) return `第 ${i + 1} 档的起订量必须大于 0`;
    if (!isFiniteNumber(price)) return `第 ${i + 1} 档的单价请填数字`;
    if (Number(price) <= 0) return `第 ${i + 1} 档的单价必须大于 0（0 元档请走 06 采购的折扣行）`;
    if (i > 0) {
      const prev = sorted[i - 1]!.minQuantity;
      if (Number(min) <= Number(prev))
        return `第 ${i + 1} 档起订量（${min}）必须大于第 ${i} 档（${prev}）：档位按起订量升序排列，不允许重复`;
    }
  }
  return null;
}

function isFiniteNumber(text: string): boolean {
  return text.trim() !== '' && Number.isFinite(Number(text));
}

/** 列表查询 → query string。空值一律不发（后端按"不过滤"处理）。 */
export function listQueryParams(query: QuoteListQuery): string {
  const params = new URLSearchParams();
  if (query.keyword.trim()) params.set('keyword', query.keyword.trim());
  if (query.skuId) params.set('skuId', query.skuId);
  if (query.supplierId) params.set('supplierId', query.supplierId);
  if (query.currency.trim()) params.set('currency', query.currency.trim().toUpperCase());
  if (query.status) params.set('status', query.status);
  if (query.effectiveOnly) params.set('effectiveOnly', 'true');
  if (query.ambiguousOnly) params.set('ambiguousOnly', 'true');
  if (query.at) params.set('at', query.at);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return params.toString();
}

// ================================================================ 接口调用

export function fetchQuoteOverview() {
  return api<SupplierQuoteOverview>('catalog/supplier-quotes/overview');
}

export function fetchQuoteOptions() {
  return api<SupplierQuoteOptions>('catalog/supplier-quotes/options');
}

export function fetchQuotes(query: QuoteListQuery) {
  return api<SupplierQuotePage>(`catalog/supplier-quotes?${listQueryParams(query)}`);
}

export function fetchQuote(id: string) {
  return api<SupplierQuote>(`catalog/supplier-quotes/${id}`);
}

export function createQuote(input: SupplierQuoteInput) {
  return api<SupplierQuote>('catalog/supplier-quotes', 'POST', input);
}

export function updateQuote(id: string, input: SupplierQuoteInput) {
  return api<SupplierQuote>(`catalog/supplier-quotes/${id}`, 'PUT', input);
}

export function deleteQuote(id: string, version: number) {
  return api<{ deleted: boolean }>(
    `catalog/supplier-quotes/${id}?version=${version}`,
    'DELETE',
  );
}

/**
 * 有效报价（蓝图「输出」那一行）。
 *
 * <p>歧义时**不会**抛错：返回 {@code selected=null} + {@code ambiguous=true} +
 * 逐条标状态的 {@code candidates}，由界面显示"需人工选择"。
 */
export function fetchEffectiveQuotes(skuId: string, supplierId: string, at = '') {
  const params = new URLSearchParams({ skuId });
  if (supplierId) params.set('supplierId', supplierId);
  if (at) params.set('at', at);
  return api<SupplierQuoteEffectiveResult>(`catalog/supplier-quotes/effective?${params}`);
}

/**
 * 严格取价（06 采购带出价格走这条）。
 *
 * <p>歧义时后端 409，body 里带 {@code candidates}。这里把候选抽出来挂到
 * {@link QuoteAmbiguity} 上，让界面能"逐条显示 + 让用户去改优先级"。
 */
export async function resolveEffectiveQuote(
  skuId: string,
  supplierId: string,
): Promise<SupplierQuoteEffectiveResult> {
  const params = new URLSearchParams({ skuId, supplierId });
  return api<SupplierQuoteEffectiveResult>(
    `catalog/supplier-quotes/effective/resolve?${params}`,
  );
}

export type QuoteAmbiguityCandidate = {
  id: string;
  priority: number;
  supplierCode: string;
  supplierName: string;
  skuCode: string;
  supplierSkuCode: string;
  currency: string;
  validityLabel: string;
  tierCount: number;
};

/** 歧义（规则 **R2**）：把 409 的 body 解析成界面能逐条显示的形状。 */
export function ambiguityFrom(error: unknown): QuoteAmbiguityCandidate[] {
  if (!(error instanceof ApiError)) return [];
  const body = error.body as { candidates?: QuoteAmbiguityCandidate[] } | null;
  return body?.candidates ?? [];
}

/** 错误文案。歧义 409 要用后端那段可读的处置建议，而不是通用"保存失败"。 */
export function quoteErrorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 409) {
      const body = error.body as { detail?: string; message?: string } | null;
      const detail = body?.detail ?? body?.message;
      if (detail) return detail;
    }
    return error.message;
  }
  return error instanceof Error ? error.message : '加载失败，请重试';
}

// ================================================================ 展示辅助

/**
 * 选择状态 → 中文。
 *
 * <p>入参是 {@code string | null} 而不是 {@link SelectionState}：契约里这一列是
 * codegen 产物（`"selectionState": string | null`），禁止手改去把它收窄成联合类型，
 * 所以收窄发生在这里 —— 认得的三个状态给固定文案，认不得的（后端加了新值而前端没跟上）
 * 原样回显而不是显示成 `undefined`。前端落后于后端时**看得见**，不会静默变成空格子。
 */
export function selectionStateLabel(state: string | null): string {
  if (!state) return '不参与有效报价';
  return (SELECTION_STATE_LABELS as Record<string, string>)[state] ?? state;
}

/** 判断是否落在词表内的三个状态里（用于徽标配色）。 */
export function isKnownSelectionState(state: string | null): state is SelectionState {
  return state === 'SELECTED' || state === 'AMBIGUOUS' || state === 'LOWER_PRIORITY';
}

/**
 * 报价行的主文案：一眼能看出「谁 报 多少钱 什么单位 什么时候有效」。
 *
 * <p>服务端给了 purchaseUnitName 与 validityLabel，这里直接用，
 * 不在前端再拼一遍 —— 三个页面的这一列口径必须一致。
 */
export function quoteHeadline(quote: SupplierQuote): string {
  const tiers = quote.tiers ?? [];
  const top = tiers.length > 0 ? tiers[tiers.length - 1]! : null;
  const unit = quote.purchaseUnitName ?? quote.purchaseUnitCode;
  const price = top ? `最低 ${top.unitPrice} ${quote.currency}` : '未设阶梯价';
  return `${quote.supplierName} · ${price}/${unit} · ${quote.validityLabel}`;
}

/**
 * 阶梯的可读串。
 *
 * <p>阶梯是**开口**的（最后一档无上限），所以最后一档不能写成
 * 「100 ~ 500」—— 买 600 件时它同样适用，那样显示会让运营以为 600 落在别的档。
 */
export function tierLabel(tiers: SupplierQuotePriceTier[], index: number): string {
  const tier = tiers[index];
  if (!tier) return '';
  const min = trimNumber(tier.minQuantity);
  const price = trimNumber(tier.unitPrice);
  if (index === tiers.length - 1) return `≥ ${min} → ${price}`;
  const next = tiers[index + 1];
  const nextMin = next ? trimNumber(next.minQuantity) : '∞';
  return `${min} ~ ${nextMin === '∞' ? '∞' : Number(nextMin) - 0.000001} → ${price}`;
}

/** 按数量在阶梯里定位适用档（null = 低于最低档，没有适用价）。 */
export function applicableTierIndex(
  tiers: SupplierQuotePriceTier[],
  quantity: number,
): number {
  let found = -1;
  let foundFrom = -Infinity;
  tiers.forEach((tier, index) => {
    const min = Number(tier.minQuantity);
    if (quantity >= min && min > foundFrom) {
      foundFrom = min;
      found = index;
    }
  });
  return found;
}
