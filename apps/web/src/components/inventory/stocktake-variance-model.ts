import type {
  StocktakeVarianceAdjustment,
  StocktakeVarianceAdjustmentDetail,
  StocktakeVarianceAdjustmentPage,
  StocktakeVarianceAttributionInput,
  StocktakeVarianceAuditEntry,
  StocktakeVarianceCapabilities,
  StocktakeVarianceLine,
  StocktakeVarianceLinePage,
  StocktakeVariancePostResult,
  StocktakeVarianceReport,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 7.4.2.2 盘点差异处理前端模型。
 *
 * <p>界面上最容易出错、本模块显式处理了几处：
 *
 * <ul>
 *   <li>**归因不是入账**。点了「归因」或「批准」之后库存余额一个数都没动 ——
 *       只有「入账」那一步才改。界面上必须把这一点说出来，否则处理人会以为差异已经处理完了。
 *   <li>**率不能画成 0%**。`null` 意味着「分母为 0，盘不出率」，
 *       画成 0.00% 等于在报告上说「没有差异」。
 *   <li>**忽略与复盘都不是「处理完了」**。忽略是留痕的终态决定，复盘让这一行回到未录，
 *       两者都要在界面上保留痕迹，不能看起来像「已解决」。
 *   <li>**金额口径算不出来这件事要显示给用户**，而不是让用户以为系统只会按数量算。
 * </ul>
 */

export interface VarianceLineParams {
  filter?: string;
  reason?: string;
  decision?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

export interface AdjustmentParams {
  status?: string;
  sheetId?: string;
  keyword?: string;
  page?: number;
  size?: number;
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

// ------------------------------------------------------------ 接口

const base = 'warehouse/inventory/stocktake-variances';

export function fetchVarianceCapabilities(): Promise<StocktakeVarianceCapabilities> {
  return api<StocktakeVarianceCapabilities>(`${base}/capabilities`);
}

export function fetchVarianceLines(
  sheetId: string,
  params: VarianceLineParams,
): Promise<StocktakeVarianceLinePage> {
  return api<StocktakeVarianceLinePage>(`${base}/sheets/${sheetId}/lines${query(params)}`);
}

export function fetchVarianceReport(sheetId: string): Promise<StocktakeVarianceReport> {
  return api<StocktakeVarianceReport>(`${base}/sheets/${sheetId}/report`);
}

export function fetchVarianceAudit(
  sheetId: string,
  limit?: number,
): Promise<StocktakeVarianceAuditEntry[]> {
  return api<StocktakeVarianceAuditEntry[]>(`${base}/sheets/${sheetId}/audit${query({ limit })}`);
}

export function attributeVarianceLine(
  sheetId: string,
  lineId: string,
  input: StocktakeVarianceAttributionInput,
): Promise<StocktakeVarianceLine> {
  return api<StocktakeVarianceLine>(
    `${base}/sheets/${sheetId}/lines/${lineId}/attribute`,
    'POST',
    input,
  );
}

export function createVarianceAdjustment(
  sheetId: string,
  input: { lineIds?: string[]; allAttributed?: boolean; note?: string },
): Promise<StocktakeVarianceAdjustmentDetail> {
  return api<StocktakeVarianceAdjustmentDetail>(
    `${base}/sheets/${sheetId}/adjustments`,
    'POST',
    input,
  );
}

export function fetchVarianceAdjustments(
  params: AdjustmentParams,
): Promise<StocktakeVarianceAdjustmentPage> {
  return api<StocktakeVarianceAdjustmentPage>(`${base}/adjustments${query(params)}`);
}

export function fetchVarianceAdjustment(id: string): Promise<StocktakeVarianceAdjustmentDetail> {
  return api<StocktakeVarianceAdjustmentDetail>(`${base}/adjustments/${id}`);
}

/** 提交审批 / 批准 / 驳回 / 作废：四个动作同一个形状，只是转移的状态不同。 */
export function submitVarianceAdjustment(
  id: string,
  note?: string,
): Promise<StocktakeVarianceAdjustment> {
  return api<StocktakeVarianceAdjustment>(`${base}/adjustments/${id}/submit`, 'POST', {
    note: note ?? null,
  });
}

export function approveVarianceAdjustment(
  id: string,
  note?: string,
): Promise<StocktakeVarianceAdjustment> {
  return api<StocktakeVarianceAdjustment>(`${base}/adjustments/${id}/approve`, 'POST', {
    note: note ?? null,
  });
}

export function rejectVarianceAdjustment(
  id: string,
  note: string,
): Promise<StocktakeVarianceAdjustment> {
  return api<StocktakeVarianceAdjustment>(`${base}/adjustments/${id}/reject`, 'POST', { note });
}

export function cancelVarianceAdjustment(
  id: string,
  note?: string,
): Promise<StocktakeVarianceAdjustment> {
  return api<StocktakeVarianceAdjustment>(`${base}/adjustments/${id}/cancel`, 'POST', {
    note: note ?? null,
  });
}

/** 入账：唯一会改库存余额的动作，所以返回类型多带一个 transactionId 与改动过的余额行。 */
export function postVarianceAdjustment(
  id: string,
  note?: string,
): Promise<StocktakeVariancePostResult> {
  return api<StocktakeVariancePostResult>(`${base}/adjustments/${id}/post`, 'POST', {
    note: note ?? null,
  });
}

// ------------------------------------------------------------ 标签

export const reasonLabels: Record<string, string> = {
  SHRINKAGE: '损耗',
  MISSHIP: '错发',
  THEFT: '偷盗',
  UNRECORDED: '未入账',
  TIMING: '时点差',
};

export const decisionLabels: Record<string, string> = {
  ADJUST: '调整',
  RECOUNT: '复盘',
  IGNORE: '忽略',
};

export const lineFilterLabels: Record<string, string> = {
  ALL: '全部差异',
  UNATTRIBUTED: '未归因',
  IN_ADJUSTMENT: '调整中',
  POSTED: '已入账',
  RECOUNTED: '已复盘',
};

export const adjustmentStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_APPROVAL: '待审批',
  APPROVED: '已批待入账',
  REJECTED: '已驳回',
  POSTED: '已入账',
  CANCELLED: '已作废',
};

export const reasonOptions = Object.entries(reasonLabels).map(([value, label]) => ({
  value,
  label,
}));

export const decisionOptions = Object.entries(decisionLabels).map(([value, label]) => ({
  value,
  label,
}));

export const lineFilterOptions = Object.entries(lineFilterLabels).map(([value, label]) => ({
  value,
  label,
}));

export const adjustmentStatusOptions = Object.entries(adjustmentStatusLabels).map(
  ([value, label]) => ({
    value,
    label,
  }),
);

/** 单据状态色：已入账是正向完成，待审批/已批待入账是最需要注意的中间态。 */
export function adjustmentTone(status: string): 'ok' | 'warn' | 'bad' {
  if (status === 'REJECTED' || status === 'CANCELLED') return 'bad';
  if (status === 'PENDING_APPROVAL' || status === 'APPROVED') return 'warn';
  return 'ok';
}

/** 差异的处置状态文案：没归因 / 在单上 / 已入账 / 已复盘 / 已忽略。 */
export function dispositionText(line: {
  varianceDecision?: string | null;
  adjustmentStatus?: string | null;
  postedAt?: string | null;
}): string {
  if (line.postedAt) return '已入账';
  if (line.varianceDecision === 'RECOUNT') return '已复盘（待重新盘）';
  if (line.varianceDecision === 'IGNORE') return '已忽略（留痕）';
  if (line.varianceDecision === 'ADJUST') {
    return line.adjustmentStatus
      ? `调整单 ${adjustmentStatusLabels[line.adjustmentStatus] ?? line.adjustmentStatus}`
      : '待生成调整单';
  }
  return '未归因';
}

// ------------------------------------------------------------ 展示

/** 数量格式化：null 显示「—」而不是 0（「还没盘 / 盘不出」显示成 0 是最危险的误显示）。 */
export function qty(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 4 });
}

/** 差异文案：带方向。`-5` 显示成「盘亏 5」，只给带符号数字没人敢确认填对了没有。 */
export function varianceText(variance: number | null | undefined): string {
  if (variance === null || variance === undefined) return '未录入';
  if (variance > 0) return `盘盈 ${qty(variance)}`;
  if (variance < 0) return `盘亏 ${qty(Math.abs(variance))}`;
  return '账实相符';
}

/**
 * 率的展示：**null 显示「不可计算」而不是 0%**。
 *
 * <p>0% 会被读成「差异率 0%，没有差异」，而分母为 0 的真实含义是
 * 「账面合计为 0，这一盘盘不出任何率」—— 那是两件完全不同的事。
 */
export function rateText(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) return '不可计算（分母为 0）';
  return `${rate.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 })}%`;
}

/** 率本身的色调：null 是中性（不是「好」也不是「坏」），0% 才是最值得警惕的。 */
export function rateTone(rate: number | null | undefined): 'ok' | 'warn' | 'bad' | 'neutral' {
  if (rate === null || rate === undefined) return 'neutral';
  if (rate === 0) return 'warn';
  return 'bad';
}

export function formatMoment(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/** 进度「已归因 / 差异行数」，总量为 0 时不显示成 100%。 */
export function progressText(done: number, total: number): string {
  if (total <= 0) return '0 / 0';
  return `${done} / ${total}`;
}

export function progressPercent(done: number, total: number): number {
  if (total <= 0) return 0;
  return Math.min(100, Math.round((done / total) * 100));
}

// ------------------------------------------------------------ 校验

/**
 * 归因输入校验。返回错误文案而不是静默纠正。
 *
 * <p>「忽略」额外要求 4 个字：与后端同一条纪律 —— 界面上先拦住，
 * 用户就不用等一个 422 才知道自己写的理由不算数。
 */
export function attributionProblem(reason: string, decision: string, note: string): string | null {
  if (!reason)
    return '必须选择差异原因：五类原因语义不同，少选一类就等于把「账错了」和「账没错」混在一起';
  if (!decision) return '必须选择处理决定：调整 / 复盘 / 忽略，三者后果完全不同';
  const text = note.trim();
  if (text === '') {
    return decision === 'IGNORE'
      ? '忽略必须写理由：它让这条差异永远不入账'
      : '请写一句判断依据：差异可能要追责到人';
  }
  if (decision === 'IGNORE' && text.length < 4)
    return '忽略的理由至少 4 个字：「无」「错」写下来等于没写';
  if (text.length > 500) return '说明超过 500 字，请写结论与依据';
  return null;
}

/** 生成调整单前的校验：至少要选一行，否则建出来的空单会让人误以为「处理过了」。 */
export function createAdjustmentProblem(allAttributed: boolean, lineIds: string[]): string | null {
  if (allAttributed) return null;
  if (lineIds.length === 0) return '请至少选一行差异，或勾选「收走全部已归因为调整的行」';
  return null;
}

/** 统一错误文案。409（状态冲突）与 403（无权限）要分开说，混在一起用户不知道该找谁。 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) return '当前角色没有此操作权限（需要 warehouse.inventory.update）';
    if (error.status === 404) return '找不到该对象（可能不属于当前租户）';
    if (error.status === 409) return error.message || '状态冲突：这一步现在还不能做';
    return error.message || `请求失败（${error.status}）`;
  }
  if (error instanceof Error) return error.message;
  return '未知错误';
}
