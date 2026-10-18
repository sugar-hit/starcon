/**
 * 17.4.2.2 审批记录的展示口径与筛选拼装。
 *
 * <p>后端 {@code ApprovalRecordController} 是最终判定（谁能看哪些记录、时间线怎么串、统计怎么分组），
 * 这里的函数只做三件事，且**都不重新判定权限**：
 *
 * <ul>
 *   <li>把人话标签与语气从码值映射出来（结果状态、审批动作、审批人类型）；
 *   <li>把时长渲染成中文（「压了多久」），值由后端的 {@code approval_dwell_seconds} 算好，这里只格式化；
 *   <li>把筛选控件拼成查询串（空值不进串，避免发出 {@code minAmount=} 这种空条件）。
 * </ul>
 *
 * <p><b>标签必须穷举后端真实会返回的码。</b>界面上「未知就显示码值本身」比留空白好，
 * 但更糟的是把一个到不了的码也写进映射表里 —— 那会让页面看起来支持了其实没交付的状态。
 * 所以这里覆盖的是 {@code approval_instance.status} 与 {@code approval_instance_action.action}
 * 的**真实取值**，撤回（WITHDRAWN）刻意**不在**其中：V160 的 CHECK 里没有它，
 * 能力声明端点会如实报 {@code delivered=false}。
 */

/** 结果状态。与后端 {@code approval_instance.status} 的 CHECK 逐字一致。 */
export const RECORD_STATUSES = ['PENDING', 'APPROVED', 'REJECTED'] as const;
export type RecordStatus = (typeof RECORD_STATUSES)[number];

export const STATUS_LABELS: Record<string, string> = {
  PENDING: '待审批',
  APPROVED: '已通过',
  REJECTED: '已驳回',
};

/** 审批动作。与后端 {@code approval_instance_action.action} 的 CHECK 逐字一致。 */
export const RECORD_ACTIONS = ['APPROVE', 'REJECT', 'TRANSFER'] as const;
export type RecordAction = (typeof RECORD_ACTIONS)[number];

export const ACTION_LABELS: Record<string, string> = {
  APPROVE: '通过',
  REJECT: '驳回',
  TRANSFER: '转交',
};

/** 排序键。与后端 {@code orderBy} 的白名单一一对应，拼错后端会 400 而不是默默换顺序。 */
export const SORTS = [
  'NEWEST',
  'OLDEST',
  'WAITING',
  'FINISHED_NEWEST',
  'AMOUNT_DESC',
  'AMOUNT_ASC',
  'DOCUMENT',
] as const;
export type Sort = (typeof SORTS)[number];

export const SORT_LABELS: Record<Sort, string> = {
  NEWEST: '最新提交优先',
  OLDEST: '最早提交优先',
  WAITING: '停留最久优先',
  FINISHED_NEWEST: '最近结清优先',
  AMOUNT_DESC: '金额从高到低',
  AMOUNT_ASC: '金额从低到高',
  DOCUMENT: '按单据类型',
};

/** Badge 的语气词表（见 packages/ui 的 badge.tsx）：danger 而不是 destructive。 */
export type Tone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'brand';

export const STATUS_TONES: Record<string, Tone> = {
  PENDING: 'primary',
  APPROVED: 'success',
  REJECTED: 'warning',
};

export const ACTION_TONES: Record<string, Tone> = {
  APPROVE: 'success',
  REJECT: 'warning',
  TRANSFER: 'neutral',
};

export function statusLabel(status: string): string {
  return STATUS_LABELS[status] ?? status;
}

export function statusTone(status: string): Tone {
  return STATUS_TONES[status] ?? 'neutral';
}

export function actionLabel(action: string): string {
  return ACTION_LABELS[action] ?? action;
}

export function actionTone(action: string): Tone {
  return ACTION_TONES[action] ?? 'neutral';
}

/**
 * 时长的中文说法。
 *
 * <p>与 17.4.2.1 的 {@code dwellLabel} 同口径但**刻意独立**：待办页的时长是「压在我这儿多久」，
 * 记录页的时长要同时用于链路节点、统计均值与最长停留，三处共用一个函数时
 * 改一处就会悄悄改掉另外两处的显示精度。
 *
 * @param seconds 后端算好的秒数（DB 侧 {@code approval_dwell_seconds}），负数按 0 处理
 */
export function durationLabel(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  if (s < 60) return `${s} 秒`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m} 分钟`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h} 小时 ${m % 60} 分钟`;
  const d = Math.floor(h / 24);
  return `${d} 天 ${h % 24} 小时`;
}

/** 时长 → 语气色。压得越久越需要被看见。 */
export function durationTone(seconds: number): Tone {
  const s = Math.max(0, Math.floor(seconds));
  if (s < 3600) return 'neutral';
  if (s < 24 * 3600) return 'primary';
  if (s < 72 * 3600) return 'warning';
  return 'danger';
}

/** 金额 + 币种的展示。没有金额就说「不适用金额审批」，不留空白让人猜是不是加载中。 */
export function amountLabel(amount: number | null, currency: string | null): string {
  if (amount === null) return '不适用金额审批';
  return `${currency ?? ''} ${amount}`.trim();
}

/** 通过率 → 百分比。后端给的是 0..1 的小数。 */
export function rateLabel(rate: number): string {
  if (!Number.isFinite(rate)) return '—';
  return `${(rate * 100).toFixed(1)}%`;
}

/**
 * 时间线上一条动作的一句话。
 *
 * <p>转交单独一种说法（「转交给某某」），因为它是委派而不是表态 —— 与 17.4.2.1
 * 把「转交」做成独立按钮而不是审批按钮的一种，是同一个判断。
 *
 * @param toName 转交目标的可读名；解析不出成员名时（没有 member.read 权限）传 undefined
 */
export function timelineHeadline(
  action: string,
  actorName: string | null,
  toName: string | null,
): string {
  const who = actorName ?? '未知操作人';
  if (action === 'TRANSFER') {
    return toName ? `${who} 转交给 ${toName}` : `${who} 转交（未指定目标）`;
  }
  return `${who} ${actionLabel(action)}`;
}

/** 表头那句话：把「筛选后 N 条」与「共 M 条」分开讲，避免用户以为只处理了 N 条。 */
export function summaryLabel(shown: number, total: number): string {
  if (shown === total) return `共 ${total} 条审批记录`;
  return `筛选出 ${shown} 条，共 ${total} 条审批记录`;
}

/** 筛选控件的原始值。全部用字符串，提交前再校验与转换 —— 输入框里随时都是半个数字。 */
export interface RecordFilters {
  instanceNo: string;
  documentTypeCode: string;
  sourceSystem: string;
  status: string;
  applicantId: string;
  actorId: string;
  nodeKey: string;
  action: string;
  currencyCode: string;
  minAmount: string;
  maxAmount: string;
  submittedFrom: string;
  submittedTo: string;
  decidedFrom: string;
  decidedTo: string;
  sort: Sort;
  page: number;
  size: number;
}

export function emptyFilters(): RecordFilters {
  return {
    instanceNo: '',
    documentTypeCode: '',
    sourceSystem: '',
    status: '',
    applicantId: '',
    actorId: '',
    nodeKey: '',
    action: '',
    currencyCode: '',
    minAmount: '',
    maxAmount: '',
    submittedFrom: '',
    submittedTo: '',
    decidedFrom: '',
    decidedTo: '',
    sort: 'NEWEST',
    page: 0,
    size: 20,
  };
}

/**
 * 校验筛选值，返回人话错误。
 *
 * <p>只校验**格式与自相矛盾**（非数字、负数、上下限颠倒、时间倒置）；
 * 越界的组合交给后端 400，因为「这个单据类型有没有值」只有库里的数据能回答。
 */
export function validateFilters(f: RecordFilters): string[] {
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
  if (f.actorId.trim() && !isUuid(f.actorId.trim())) errors.push('审批人需为成员 ID（UUID）');
  if (f.status.trim() && !RECORD_STATUSES.includes(f.status.trim() as RecordStatus))
    errors.push(`状态需为 ${RECORD_STATUSES.join(' / ')}`);
  if (f.action.trim() && !RECORD_ACTIONS.includes(f.action.trim() as RecordAction))
    errors.push(`动作需为 ${RECORD_ACTIONS.join(' / ')}`);
  if (f.submittedFrom.trim() && f.submittedTo.trim() && !isOrdered(f.submittedFrom, f.submittedTo))
    errors.push('提交开始时间不能晚于结束时间');
  if (f.decidedFrom.trim() && f.decidedTo.trim() && !isOrdered(f.decidedFrom, f.decidedTo))
    errors.push('动作开始时间不能晚于结束时间');
  return errors;
}

/** 时间字符串的先后。不可解析的一律当作「不校验」，错误信息由后端给。 */
function isOrdered(from: string, to: string): boolean {
  const a = Date.parse(from);
  const b = Date.parse(to);
  if (Number.isNaN(a) || Number.isNaN(b)) return true;
  return a <= b;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function isUuid(value: string): boolean {
  return UUID.test(value);
}

/** `datetime-local` 控件的值（本地墙钟，无时区）→ ISO-8601 瞬时值，后端才能正确解析。 */
export function localToInstant(value: string): string | null {
  if (!value.trim()) return null;
  const parsed = Date.parse(value);
  return Number.isNaN(parsed) ? null : new Date(parsed).toISOString();
}

/**
 * 把筛选控件拼成查询串。
 *
 * <p>**空值一律不进串**：发出 {@code ?minAmount=} 会让后端按「空字符串」解析而不是「未提供」，
 * 报错信息还会指向一个用户根本没填的字段。
 */
export function buildQuery(f: RecordFilters): string {
  const params = new URLSearchParams();
  if (f.instanceNo.trim()) params.set('instanceNo', f.instanceNo.trim());
  if (f.documentTypeCode.trim()) params.set('documentTypeCode', f.documentTypeCode.trim());
  if (f.sourceSystem.trim()) params.set('sourceSystem', f.sourceSystem.trim());
  if (f.status.trim()) params.set('status', f.status.trim());
  if (f.applicantId.trim()) params.set('applicantId', f.applicantId.trim());
  if (f.actorId.trim()) params.set('actorId', f.actorId.trim());
  if (f.nodeKey.trim()) params.set('nodeKey', f.nodeKey.trim());
  if (f.action.trim()) params.set('action', f.action.trim());
  if (f.currencyCode.trim()) params.set('currencyCode', f.currencyCode.trim().toUpperCase());
  if (f.minAmount.trim()) params.set('minAmount', f.minAmount.trim());
  if (f.maxAmount.trim()) params.set('maxAmount', f.maxAmount.trim());
  const submittedFrom = localToInstant(f.submittedFrom);
  const submittedTo = localToInstant(f.submittedTo);
  if (submittedFrom) params.set('submittedFrom', submittedFrom);
  if (submittedTo) params.set('submittedTo', submittedTo);
  const decidedFrom = localToInstant(f.decidedFrom);
  const decidedTo = localToInstant(f.decidedTo);
  if (decidedFrom) params.set('decidedFrom', decidedFrom);
  if (decidedTo) params.set('decidedTo', decidedTo);
  params.set('sort', f.sort);
  if (f.page > 0) params.set('page', String(f.page));
  params.set('size', String(f.size));
  return `?${params.toString()}`;
}

/**
 * 导出的查询串 —— **不带分页**，因为导出的是「当前筛选命中的全部行」而不是当前这一页。
 *
 * <p>带 page/size 去导出是审计场景里最常见的错误：运营导出 200 条以为拿全了，
 * 实际库里有 3000 条。导出的上限由后端把关并明确报错，不会静默截断。
 */
export function buildExportQuery(f: RecordFilters): string {
  const stripped = { ...f, page: 0, size: 0 };
  const query = buildQuery(stripped);
  const params = new URLSearchParams(query);
  params.delete('page');
  params.delete('size');
  const text = params.toString();
  return text ? `?${text}` : '';
}

/** 是否处在「有筛选但没有结果」的状态 —— 页面据此把空态换成「放宽筛选」而不是「还没有记录」。 */
export function isFiltered(f: RecordFilters): boolean {
  return (
    f.instanceNo.trim() !== '' ||
    f.documentTypeCode.trim() !== '' ||
    f.sourceSystem.trim() !== '' ||
    f.status.trim() !== '' ||
    f.applicantId.trim() !== '' ||
    f.actorId.trim() !== '' ||
    f.nodeKey.trim() !== '' ||
    f.action.trim() !== '' ||
    f.currencyCode.trim() !== '' ||
    f.minAmount.trim() !== '' ||
    f.maxAmount.trim() !== '' ||
    f.submittedFrom.trim() !== '' ||
    f.submittedTo.trim() !== '' ||
    f.decidedFrom.trim() !== '' ||
    f.decidedTo.trim() !== ''
  );
}

/** 总页数。total 来自后端真实 count，不是前端拿 items.length 猜的。 */
export function pageCount(total: number, size: number): number {
  if (size <= 0) return 0;
  return Math.max(1, Math.ceil(total / size));
}

/**
 * 统计分组里应该高亮的那一行。
 *
 * <p>纯函数而不是写在组件里：分组排序由后端决定（按计数倒序），
 * 页面要标出「最多的是哪一项」时需要一条可测的规则，而不是一段散在 JSX 里的比较。
 */
export function topKey(rows: { key: string; instanceCount: number }[]): string | null {
  let best: string | null = null;
  let bestCount = -1;
  for (const row of rows) {
    if (row.instanceCount > bestCount) {
      best = row.key;
      bestCount = row.instanceCount;
    }
  }
  return best;
}
