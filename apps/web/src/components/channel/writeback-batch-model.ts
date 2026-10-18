/**
 * 2.5.2.1 常用回写操作的前端数据层。
 *
 * 蓝图 02-channel-and-shop.md：「提供高频回写的快捷入口与批量操作（批量改库存、批量改价、
 * 批量下架、批量回传物流），支持按店铺与筛选条件批量执行。」
 * 输出：「生成 N 条独立回写任务，**逐条记录结果，部分失败不影响其余**。」
 *
 * <p><b>这一层对那句加粗句负两件事</b>：
 *
 * <ol>
 *   <li><b>「没发出去的」与「发出去了但失败」在展示上分开</b>：被拦下的那条（封禁 / 币种不符 /
 *       目标已删）**没有**平台任务，它压根没发往平台 —— 把它画成「失败」会让运营去平台侧
 *       找一条压根不存在的失败记录。这一层用 {@link itemResultText} / {@link itemTone} 把两者
 *       分成「没发出去」与「已发出 · 失败」两种说法。</li>
 *   <li><b>UNKNOWN 绝不显示成失败</b>：{@link outcomeTone} 把结果未知画成提醒色而不是危险色。
 *       结果未知时平台**可能已经执行**了，把它算成失败会诱导运营直接重试 —— 那正是重复扣减 /
 *       重复发货的来源（2.5.1.2 整节讲的就是这件事）。</li>
 * </ol>
 *
 * <p>页面**不自己推断**能不能发：能不能发、命中多少条、哪条会被拒，全部来自后端
 * {@code POST /channel/writeback-batches/preview} 的试算（与执行走同一套筛选与校验）。
 * 前端做的判断只有一条 ——「一次最多 500 条」这条硬上限，它与后端 {@code MAX_BATCH_TARGETS} 一致，
 * 写在界面上的目的是**别让人对着几百条盲点执行**，不是为了替后端做校验。
 */
import type {
  BatchParameters,
  ShopView,
  TargetFilter,
  WritebackBatchInput,
  WritebackBatchPage,
  WritebackBatchPreview,
  WritebackBatchSummary,
  WritebackBatchView,
  WritebackItemView,
  WritebackPreviewIssue,
  WritebackTargetInput,
  WritebackTargetPage,
  WritebackTargetStatusInput,
  WritebackTargetSummary,
  WritebackTargetView,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError } from '../../lib/api';

/**
 * codegen 把 Java 枚举**内联成联合类型**（`"LISTING" | "SHIPMENT"`），不导出独立的枚举别名 ——
 * 这里按生成物当前形状本地声明，契约一改（多一个取值）typecheck 就会在这里断掉，而不是等到运行时。
 */
type TargetType = 'LISTING' | 'SHIPMENT';
type TargetStatus = 'ACTIVE' | 'BLOCKED';

/** 与后端 {@code WritebackBatchController} 同前缀。 */
const base = 'channel/writeback';

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
  if (!response.ok) throw new ApiError(response.status, await response.text());
  return (await response.json()) as T;
}

/** 店铺下拉的来源：2.3 店铺目录（与批量模块同服务的既有端点，不额外登记白名单）。 */
export const fetchShops = () => call<ShopView[]>('channel/shops');

export const fetchTargets = (params: {
  shopId?: string;
  targetType?: string;
  status?: string;
  keyword?: string;
  page?: number;
  size?: number;
}) => {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '') continue;
    query.set(key, String(value));
  }
  const suffix = query.toString();
  return call<WritebackTargetPage>(suffix ? `${base}-targets?${suffix}` : `${base}-targets`);
};

export const upsertTarget = (input: WritebackTargetInput) =>
  call<string>(`${base}-targets`, { method: 'POST', body: JSON.stringify(input) });

export const setTargetStatus = (id: string, input: WritebackTargetStatusInput) =>
  call<void>(`${base}-targets/${id}/status`, { method: 'POST', body: JSON.stringify(input) });

export const fetchBatches = (params: {
  kind?: string;
  shopId?: string;
  outcome?: string;
  page?: number;
  size?: number;
}) => {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '') continue;
    query.set(key, String(value));
  }
  const suffix = query.toString();
  return call<WritebackBatchPage>(suffix ? `${base}-batches?${suffix}` : `${base}-batches`);
};

export const fetchBatch = (id: string) => call<WritebackBatchView>(`${base}-batches/${id}`);

/** 试算：不落任何一行，回答「这一把会命中多少条、其中多少条会被拦下」。 */
export const previewBatch = (input: WritebackBatchInput) =>
  call<WritebackBatchPreview>(`${base}-batches/preview`, {
    method: 'POST',
    body: JSON.stringify(input),
  });

export const executeBatch = (input: WritebackBatchInput) =>
  call<WritebackBatchView>(`${base}-batches`, { method: 'POST', body: JSON.stringify(input) });

// ================================================================ 请求体组装

/** 与后端 {@code MAX_BATCH_TARGETS} 同值；超了后端会拒，这里先拦一次并说清怎么缩小范围。 */
export const BATCH_HARD_LIMIT = 500;

/** 逐目标覆盖的**表单**形状：值仍是字符串，转换只发生在 {@link buildBatchInput} 一个边界上。 */
export interface PerTargetForm {
  stock: string;
  price: string;
  trackingNumber: string;
  carrierCode: string;
  shippedAt: string;
}

export function emptyPerTargetForm(): PerTargetForm {
  return { stock: '', price: '', trackingNumber: '', carrierCode: '', shippedAt: '' };
}

/** 一次批量动作的表单状态。勾选优先，筛选兜底 —— 与后端 {@code resolve()} 同一套优先级。 */
export interface BatchForm {
  kind: 'UPDATE_STOCK' | 'UPDATE_PRICE' | 'DELIST' | 'SHIP_UPDATE';
  /** 勾选的记录范围。空 = 按 filter 解析候选集。 */
  selected: WritebackTargetView[];
  shopId: string | null;
  targetType: TargetType | '';
  status: TargetStatus | '';
  keyword: string;
  stock: string;
  price: string;
  currency: string;
  trackingNumber: string;
  carrierCode: string;
  shippedAt: string;
  /** 逐目标覆盖：目标 id → 该条自己的运单号 / 承运商 / 库存 / 价格。 */
  perTarget: Record<string, PerTargetForm>;
  reason: string;
  idempotencyKey: string;
}

export function emptyBatchForm(): BatchForm {
  return {
    kind: 'UPDATE_STOCK',
    selected: [],
    shopId: null,
    targetType: '',
    status: '',
    keyword: '',
    stock: '',
    price: '',
    currency: '',
    trackingNumber: '',
    carrierCode: '',
    shippedAt: '',
    perTarget: {},
    reason: '',
    idempotencyKey: '',
  };
}

/**
 * 表单字符串 → 契约里的 number。
 *
 * <p>⚠️ 金额与数量在**展示层**一律原样展示、绝不参与前端算术 —— 那才是「禁浮点记账」要防的。
 * 整条链路上只有**发起请求这一个边界**做转换，而且这里做的是安全的：库存
 * （NUMERIC(11,3)）与价格（NUMERIC(14,4)）的实际量级远在双精度能精确表示的范围内，
 * 不存在掉精度；后端收到后立刻解析回 BigDecimal 并 setScale 归位，量纲由
 * {@code @Digits} 校验 —— 不合法会 400，而不是悄悄改数。
 *
 * <p>解析不出来一律给 null（不塞 0）：填错内容让后端逐条报「参数不合法」，
 * 塞 0 会变成「合法地把库存改成 0」，那是一次真实事故。
 */
function toNumber(value: string): number | null {
  const trimmed = value.trim();
  if (!trimmed) return null;
  const n = Number(trimmed);
  return Number.isFinite(n) ? n : null;
}

/**
 * 表单 → 请求体。
 *
 * <p>⚠️ 勾选了目标就**只按勾选**发（不夹带筛选条件）：两者同时给时后端取的是勾选集合，
 * 而筛选条件只是解析候选集用的 —— 界面上一边勾了 3 条一边挂着筛选框，很自然会以为「勾选 + 筛选」是
 * 交集。到底是哪个由 {@link batchScopeText} 明说，组装这里只保证**发出去的形状**与后端 resolve 的优先级一致。
 *
 * <p>发货时刻（{@code datetime-local}）按**浏览器本地时区**解析再转 UTC：运营填 05:06 就是他当地的
 * 05:06，转出的瞬间才是平台侧该看到的那一刻。直接把这个字符串当 UTC 发，等于静默偏掉本机时差。
 */
export function buildBatchInput(form: BatchForm): WritebackBatchInput {
  const filter: TargetFilter | null =
    !form.selected.length && (form.shopId || form.targetType || form.status || form.keyword.trim())
      ? {
          shopId: form.shopId || null,
          targetType: form.targetType || null,
          status: form.status || null,
          keyword: form.keyword.trim() || null,
        }
      : null;

  const parameters: BatchParameters = {
    stock: toNumber(form.stock),
    price: toNumber(form.price),
    currency: form.currency.trim().toUpperCase() || null,
    trackingNumber: form.trackingNumber.trim() || null,
    carrierCode: form.carrierCode.trim() || null,
    shippedAt: form.shippedAt ? new Date(form.shippedAt).toISOString() : null,
    perTarget:
      form.selected.length && Object.keys(form.perTarget).length
        ? Object.entries(form.perTarget).map(([targetId, override]) => ({
            targetId,
            stock: override ? toNumber(override.stock) : null,
            price: override ? toNumber(override.price) : null,
            trackingNumber: override?.trackingNumber.trim() || null,
            carrierCode: override?.carrierCode.trim() || null,
            shippedAt: override?.shippedAt ? new Date(override.shippedAt).toISOString() : null,
          }))
        : null,
  };

  return {
    kind: form.kind,
    shopId: form.shopId || null,
    filter,
    targetIds: form.selected.length ? form.selected.map((t) => t.id) : null,
    parameters,
    idempotencyKey: form.idempotencyKey.trim() || null,
    reason: form.reason.trim() || null,
  };
}

/** 这次会按什么范围发：勾选还是筛选。界面上必须写出来 —— 两种范围的爆炸半径差一个量级。 */
export function batchScopeText(form: BatchForm): string {
  return form.selected.length
    ? `按勾选的 ${form.selected.length} 条执行`
    : '按筛选条件命中的全部目标执行';
}

/** 本地表单能不能发：只拦「一条都没选也没条件」与超上限，其余交给后端试算逐条报。 */
export function localBlockReason(form: BatchForm): string | null {
  if (form.selected.length > BATCH_HARD_LIMIT) {
    return `一次最多 ${BATCH_HARD_LIMIT} 条，请分批执行`;
  }
  if (
    form.selected.length === 0 &&
    !form.shopId &&
    !form.targetType &&
    !form.status &&
    !form.keyword.trim()
  ) {
    return '请先勾选记录，或至少给出一个筛选条件（店铺 / 类型 / 状态 / 关键词）';
  }
  return null;
}

// ================================================================ 展示口径

export const KIND_LABELS: Record<string, string> = {
  UPDATE_STOCK: '批量改库存',
  UPDATE_PRICE: '批量改价',
  DELIST: '批量下架',
  SHIP_UPDATE: '批量回传物流',
};

export const TARGET_TYPE_LABELS: Record<string, string> = {
  LISTING: '平台商品',
  SHIPMENT: '平台订单',
};

/** 改库存 / 改价 / 下架只对平台商品成立，回传物流只对平台订单成立 —— 选错时界面直接说清。 */
export const KIND_TARGET_TYPES: Record<string, TargetType> = {
  UPDATE_STOCK: 'LISTING',
  UPDATE_PRICE: 'LISTING',
  DELIST: 'LISTING',
  SHIP_UPDATE: 'SHIPMENT',
};

export const OUTCOME_LABELS: Record<string, string> = {
  SUCCEEDED: '全部成功',
  PARTIAL: '部分成功',
  FAILED: '全部失败',
  IN_PROGRESS: '进行中',
};

export const OPERATION_STATUS_LABELS: Record<string, string> = {
  PENDING: '待发送',
  SENDING: '发送中',
  SUCCEEDED: '平台已执行',
  FAILED: '平台已拒绝',
  UNKNOWN: '结果未知',
};

/**
 * 批次结局 → 色调。
 *
 * <p>PARTIAL 是本模块的**主状态**（蓝图：「部分失败要如实回报」），给它独立的一档提醒色 ——
 * 与「全部成功」同色会让最该看的批次混在历史里。
 *
 * <p>UNKNOWN 所在的 IN_PROGRESS 画成中性偏信息色，**不是**危险色：结果未定不等于出事了。
 */
export function outcomeTone(
  outcome: string,
): 'neutral' | 'info' | 'success' | 'warning' | 'danger' {
  switch (outcome) {
    case 'SUCCEEDED':
      return 'success';
    case 'PARTIAL':
      return 'warning';
    case 'FAILED':
      return 'danger';
    case 'IN_PROGRESS':
      return 'info';
    default:
      return 'neutral';
  }
}

/**
 * 逐条结果 → 说法与色调。
 *
 * <p>这是「没发出去的 ≠ 失败」那条纪律的落点：{@code REJECTED} 那条**没有**平台任务，
 * 说成「失败」会让人跑去平台侧找一条压根不存在的失败记录。
 */
export function itemResultText(
  item: Pick<WritebackItemView, 'itemResult' | 'operationStatus' | 'reason'>,
): string {
  if (item.itemResult === 'REJECTED') return '没发出去';
  return OPERATION_STATUS_LABELS[item.operationStatus] ?? item.operationStatus ?? '已发出';
}

export function itemTone(
  item: Pick<WritebackItemView, 'itemResult' | 'operationStatus'>,
): 'neutral' | 'info' | 'success' | 'warning' | 'danger' {
  if (item.itemResult === 'REJECTED') return 'neutral';
  switch (item.operationStatus) {
    case 'SUCCEEDED':
      return 'success';
    case 'FAILED':
      return 'danger';
    // ⚠️ UNKNOWN 是提醒色不是危险色：并进 danger 就是在暗示「重试」，而重试可能重复扣减。
    case 'UNKNOWN':
      return 'warning';
    default:
      return 'info';
  }
}

/** 结果未知时这一条的固定提示 —— 直接写明下一步是**核验**，不是重发。 */
export const unknownHint =
  '结果未知：请求已发出但没收到平台回执，平台可能已经执行。' +
  '先走 2.5.1.2 核验平台实际状态，**不要直接重试** —— 重试就是重复上架 / 重复扣减的来源。';

/** 数量原样展示，不做算术（AGENTS.md：禁浮点记账）。 */
export function quantity(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = typeof value === 'number' ? value : Number(value);
  if (Number.isNaN(n)) return String(value);
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
}

/** 价格必须带币种才读得懂；没有币种时显示「无币种」而不是裸数字 —— 那是一个没有单位的金额。 */
export function price(
  value: number | string | null | undefined,
  currency: string | null | undefined,
): string {
  if (value === null || value === undefined || value === '') return '—';
  const base = quantity(value);
  return currency ? `${base} ${currency}` : `${base}（无币种）`;
}

/** 候选池汇总：真聚合（后端不受分页影响），所以这几个数不受当前筛选分页牵动。 */
export function targetSummaryRows(
  summary: WritebackTargetSummary | undefined,
): { label: string; value: number }[] {
  if (!summary) return [];
  return [
    { label: '可回写目标', value: summary.total },
    { label: '可发起回写', value: summary.activeCount },
    { label: '已封禁', value: summary.blockedCount },
    { label: '平台商品', value: summary.listingCount },
    { label: '平台订单', value: summary.shipmentCount },
    { label: '涉及店铺', value: summary.shops },
  ];
}

const ISSUE_CODE_LABELS: Record<string, string> = {
  TARGET_BLOCKED: '目标已封禁',
  TARGET_MISSING: '目标不存在',
  INVALID_STOCK: '库存数量不合法',
  INVALID_PRICE: '价格不合法',
  MISSING_CURRENCY: '缺币种',
  CURRENCY_UNKNOWN: '目标未登记币种',
  CURRENCY_MISMATCH: '币种不一致',
  INVALID_TRACKING: '物流单号不合法',
  MISSING_CARRIER: '缺承运商编码',
  NOT_APPLICABLE: '不适用于该目标',
};

/** 试算问题 → 人话。后端给的 code 只是稳定标识，界面要说清「改什么」。 */
export function issueLabel(issue: WritebackPreviewIssue): string {
  return ISSUE_CODE_LABELS[issue.code] ?? issue.code;
}

/** 试算里「会被拦下」与「只是提醒」要分开：前者在执行后一定有一条 REJECTED。 */
export function issueTone(issue: WritebackPreviewIssue): 'neutral' | 'warning' | 'danger' {
  switch (issue.code) {
    case 'TARGET_BLOCKED':
    case 'TARGET_MISSING':
      return 'danger';
    default:
      return 'warning';
  }
}

/** 试算摘要：**能被发出去的条数**排第一，被拒条数紧随其后。 */
export function previewHeadline(preview: WritebackBatchPreview): string {
  return `命中 ${preview.matched} 条 · 可发出 ${preview.applicable} 条 · 会被拦下 ${preview.blocked + preview.missingParameters} 条`;
}

/** 批次列表一行：结局 + 逐条结果的简写。「部分成功」必须一眼可见，不能只写「已结束」。 */
export function batchLine(batch: Pick<WritebackBatchView, 'kind' | 'summary'>): string {
  const s: WritebackBatchSummary = batch.summary;
  return [
    KIND_LABELS[batch.kind] ?? batch.kind,
    OUTCOME_LABELS[s.outcome] ?? s.outcome,
    `选中 ${s.selected}`,
    `已发出 ${s.queued}`,
    `被拒 ${s.rejected}`,
    s.failed ? `失败 ${s.failed}` : null,
    s.unknown ? `未知 ${s.unknown}` : null,
  ]
    .filter(Boolean)
    .join(' · ');
}
