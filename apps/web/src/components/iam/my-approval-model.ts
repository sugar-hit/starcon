/**
 * 17.4.2.1 待我审批的展示口径与筛选拼装。
 *
 * <p>后端 {@code ApprovalInboxController} 是最终判定（谁能看到哪一单、能不能批、能不能转交），
 * 这里的函数只做三件事，且**都不重新判定权限**：
 *
 * <ul>
 *   <li>把人话标签与语气从码值映射出来（单据类型、审批动作、结果码）；
 *   <li>把停留时长渲染成「压了多久」（值由后端的 {@code approval_dwell_seconds} 算好，这里只格式化）；
 *   <li>把筛选控件拼成查询串（空值不进串，避免发出 {@code minAmount=} 这种空条件）。
 * </ul>
 *
 * <p><b>结果码必须单独映射。</b>批量结果是「逐条回报」的业务，不是「整批成败」：
 * {@link RESULT_LABELS} 覆盖后端真实会返回的全部码，界面上按码决定标红与是否可重试，
 * 而不是拿中文消息去字符串匹配 —— 后端改一句文案不该让页面跟着坏。
 */

/** 审批动作。与后端 {@code ApprovalBatchInput#action} 的取值一一对应。 */
export const BATCH_ACTIONS = ['APPROVE', 'REJECT', 'TRANSFER'] as const;
export type BatchAction = (typeof BATCH_ACTIONS)[number];

/** 排序键。与后端 {@code orderBy} 的白名单一一对应，拼错后端会 400 而不是默默换顺序。 */
export const SORTS = [
  'WAITING',
  'NEWEST',
  'OLDEST',
  'AMOUNT_DESC',
  'AMOUNT_ASC',
  'DOCUMENT',
] as const;
export type Sort = (typeof SORTS)[number];

export const SORT_LABELS: Record<Sort, string> = {
  WAITING: '停留最久优先',
  NEWEST: '最新提交优先',
  OLDEST: '最早提交优先',
  AMOUNT_DESC: '金额从高到低',
  AMOUNT_ASC: '金额从低到高',
  DOCUMENT: '按单据类型',
};

export const ACTION_LABELS: Record<BatchAction, string> = {
  APPROVE: '批量通过',
  REJECT: '批量驳回',
  TRANSFER: '批量转交',
};

/** 单条动作按钮的文案。转交单独一个按钮，因为它是委派而不是表态。 */
export const DECISION_LABELS: Record<string, string> = {
  APPROVE: '通过',
  REJECT: '驳回',
};

export const STATUS_LABELS: Record<string, string> = {
  PENDING: '待审批',
  APPROVED: '已通过',
  REJECTED: '已驳回',
};

/** 节点审批人类型 —— 决定这一单是怎么落到某个人头上的。 */
export const APPROVER_TYPE_LABELS: Record<string, string> = {
  ROLE: '按角色',
  POSITION: '按岗位',
  USER: '指定人',
};

/** 结果码 → 人话 + 语气。这些码由后端 {@code ApprovalBatchResult} 产生，前端不自己发明。 */
export const RESULT_LABELS: Record<string, string> = {
  APPROVED: '已通过',
  REJECTED: '已驳回',
  TRANSFERRED: '已转交',
  NOT_MINE: '不在你的待办里',
  INVALID_TARGET: '转交目标不可用',
  SAME_APPROVER: '转交目标不合法',
  CONDITIONAL_UNRESOLVED: '下一节点是条件节点，暂不能自动流转',
  REJECTED_BY_RULE: '被校验规则拒绝',
};

/** Badge 的语气词表（见 packages/ui 的 badge.tsx）：danger 而不是 destructive。 */
export type Tone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'brand';

export const RESULT_TONES: Record<string, Tone> = {
  APPROVED: 'success',
  REJECTED: 'warning',
  TRANSFERRED: 'primary',
  NOT_MINE: 'neutral',
  INVALID_TARGET: 'neutral',
  SAME_APPROVER: 'neutral',
  CONDITIONAL_UNRESOLVED: 'warning',
  REJECTED_BY_RULE: 'danger',
};

/**
 * 批量里**不可重试**的结果码。
 *
 * <p>「已不在你的待办里」重试没有意义（别人已经处理过了），界面不该一直给重试按钮；
 * 反过来条件节点未解析是配置问题，刷新也不会变，但也**不该**让用户以为是网络抖动。
 */
export const RETRYABLE_CODES: ReadonlySet<string> = new Set([]);

export function resultLabel(code: string): string {
  return RESULT_LABELS[code] ?? code;
}

export function resultTone(code: string): Tone {
  return RESULT_TONES[code] ?? 'neutral';
}

export function isRetryable(code: string): boolean {
  return !RETRYABLE_CODES.has(code);
}

/**
 * 停留时长的中文说法。
 *
 * <p>刻意区分「本节点压了多久」与「申请人等了多久」：前者是审批人的责任指标，
 * 后者是申请人的体感，混成一个数字会让「卡在哪一级」看不出来。
 *
 * @param seconds 后端算好的秒数（DB 侧 {@code approval_dwell_seconds}），负数按 0 处理
 */
export function dwellLabel(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  if (s < 60) return `${s} 秒`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m} 分钟`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h} 小时 ${m % 60} 分钟`;
  const d = Math.floor(h / 24);
  return `${d} 天 ${h % 24} 小时`;
}

/** 停留时长 → 语气色。压得越久越需要被看见。 */
export function dwellTone(seconds: number): Tone {
  const s = Math.max(0, Math.floor(seconds));
  if (s < 3600) return 'neutral';
  if (s < 24 * 3600) return 'primary';
  if (s < 72 * 3600) return 'warning';
  return 'danger';
}

/** 停留时长筛选的可选档位（小时）。空表示不限。 */
export const DWELL_FILTERS: { label: string; hours: number | null }[] = [
  { label: '不限', hours: null },
  { label: '超过 1 小时', hours: 1 },
  { label: '超过 1 天', hours: 24 },
  { label: '超过 3 天', hours: 72 },
  { label: '超过 7 天', hours: 168 },
];

/** 筛选控件的原始值。全部用字符串，提交前再校验与转换 —— 输入框里随时都是半个数字。 */
export interface InboxFilters {
  documentTypeCode: string;
  sourceSystem: string;
  currencyCode: string;
  applicantId: string;
  minAmount: string;
  maxAmount: string;
  minDwellHours: number | null;
  sort: Sort;
  page: number;
  size: number;
}

export function emptyFilters(): InboxFilters {
  return {
    documentTypeCode: '',
    sourceSystem: '',
    currencyCode: '',
    applicantId: '',
    minAmount: '',
    maxAmount: '',
    minDwellHours: null,
    sort: 'WAITING',
    page: 0,
    size: 20,
  };
}

/**
 * 校验筛选值，返回人话错误。
 *
 * <p>只校验**格式与自相矛盾**（非数字、负数、上下限颠倒）；越界的组合交给后端 400，
 * 因为「这个单据类型有没有值」只有库里的数据能回答。
 */
export function validateFilters(f: InboxFilters): string[] {
  const errors: string[] = [];
  const amount = /^\d+(\.\d{1,6})?$/;
  if (f.minAmount.trim() && !amount.test(f.minAmount.trim()))
    errors.push('金额下限需为非负数字，最多 6 位小数');
  if (f.maxAmount.trim() && !amount.test(f.maxAmount.trim()))
    errors.push('金额上限需为非负数字，最多 6 位小数');
  if (
    f.minAmount.trim() &&
    f.maxAmount.trim() &&
    Number(f.minAmount.trim()) > Number(f.maxAmount.trim())
  )
    errors.push('金额下限不能大于上限');
  if (f.currencyCode.trim() && !/^[A-Za-z]{3}$/.test(f.currencyCode.trim()))
    errors.push('币种需为三个字母，例如 USD');
  if (f.applicantId.trim() && !isUuid(f.applicantId.trim()))
    errors.push('申请人需为成员 ID（UUID）');
  return errors;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function isUuid(value: string): boolean {
  return UUID.test(value);
}

/**
 * 把筛选控件拼成查询串。
 *
 * <p>**空值一律不进串**：发出 {@code ?minAmount=} 会让后端按「空字符串」解析而不是「未提供」，
 * 报错信息还会指向一个用户根本没填的字段。停留时长以秒为单位下发，由后端在 SQL 里换算区间。
 */
export function buildQuery(f: InboxFilters): string {
  const params = new URLSearchParams();
  if (f.documentTypeCode.trim()) params.set('documentTypeCode', f.documentTypeCode.trim());
  if (f.sourceSystem.trim()) params.set('sourceSystem', f.sourceSystem.trim());
  if (f.currencyCode.trim()) params.set('currencyCode', f.currencyCode.trim().toUpperCase());
  if (f.applicantId.trim()) params.set('applicantId', f.applicantId.trim());
  if (f.minAmount.trim()) params.set('minAmount', f.minAmount.trim());
  if (f.maxAmount.trim()) params.set('maxAmount', f.maxAmount.trim());
  if (f.minDwellHours !== null) params.set('minDwellSeconds', String(f.minDwellHours * 3600));
  params.set('sort', f.sort);
  if (f.page > 0) params.set('page', String(f.page));
  params.set('size', String(f.size));
  return `?${params.toString()}`;
}

/** 是否处在「有筛选但没有结果」的状态 —— 页面据此把空态换成「放宽筛选」而不是「你很清闲」。 */
export function isFiltered(f: InboxFilters): boolean {
  return (
    f.documentTypeCode.trim() !== '' ||
    f.sourceSystem.trim() !== '' ||
    f.currencyCode.trim() !== '' ||
    f.applicantId.trim() !== '' ||
    f.minAmount.trim() !== '' ||
    f.maxAmount.trim() !== '' ||
    f.minDwellHours !== null
  );
}

/** 金额 + 币种的展示。没有金额就说「不适用金额审批」，不留一个空白让人猜是不是加载中。 */
export function amountLabel(amount: number | null, currency: string | null): string {
  if (amount === null) return '不适用金额审批';
  return `${currency ?? ''} ${amount}`.trim();
}

/** 表头那句话：把「筛选后 N 条」与「共 M 条」分开讲，避免用户以为只处理了 N 条。 */
export function summaryLabel(shown: number, total: number): string {
  if (shown === total) return `共 ${total} 条待审批`;
  return `筛选出 ${shown} 条，共 ${total} 条待审批`;
}

/** 批量结果的一句话汇总：成功/失败都要报，失败为 0 时也要说 0，不能因为「没有失败」就只报成功数。 */
export function batchSummary(succeeded: number, failed: number): string {
  return failed === 0 ? `全部 ${succeeded} 条处理成功` : `${succeeded} 条成功，${failed} 条未处理`;
}

/**
 * 从批量结果里挑出需要用户重试的条目。
 *
 * <p>重试的前提是**它还在待办里**：被判成 NOT_MINE 的那几项再点一次也只会再失败一次，
 * 界面不该诱导用户做无效操作。
 */
export function retryableItems<T extends { instanceId: string; ok: boolean; code: string }>(
  results: T[],
): T[] {
  return results.filter((r) => !r.ok && r.code !== 'NOT_MINE');
}
