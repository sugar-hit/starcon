/**
 * 17.2.2.3 离职与停用的展示口径。
 *
 * 这里的函数全是纯函数：后端算的是「共几项、还剩几项未结」，前端只负责把
 * OPEN / COMPLETED / REVERTED 翻译成人能读的结论。**不重复计算后端已经算过的数字**，
 * 否则前后端各算一次必然会漂一次，而「还有 3 项没转」这种数字漂了没人会发现。
 */

/** 案卷状态：OPEN 还有未结条目，COMPLETED 两张台账都结清，REVERTED 已撤销复职。 */
export const CASE_STATUS_LABELS: Record<string, string> = {
  OPEN: '处理中',
  COMPLETED: '已结清',
  REVERTED: '已撤销',
};

export const RESPONSIBILITY_KIND_LABELS: Record<string, string> = {
  DOCUMENT: '在办单据',
  APPROVAL: '待审审批单',
};

export const RECLAIM_KIND_LABELS: Record<string, string> = {
  TODO: '待办',
  TASK: '任务',
};

export const RESPONSIBILITY_STATUS_LABELS: Record<string, string> = {
  PENDING: '待转移',
  TRANSFERRED: '已转移',
};

export const RECLAIM_STATUS_LABELS: Record<string, string> = {
  PENDING: '待回收',
  RECLAIMED: '已关闭',
  REASSIGNED: '已改派',
};

/** 徽标配色：处理中要显眼（还有活没干完），已结清与已撤销走中性色。 */
export const CASE_STATUS_TONES: Record<string, 'neutral' | 'primary' | 'success' | 'warning'> = {
  OPEN: 'warning',
  COMPLETED: 'success',
  REVERTED: 'neutral',
};

export interface CaseCounts {
  status: string;
  responsibilityTotal: number;
  responsibilityPending: number;
  reclaimTotal: number;
  reclaimPending: number;
}

/** 未结条目总数：两张台账的 pending 之和，决定「还剩多少事没做」。 */
export function pendingTotal(counts: CaseCounts): number {
  return counts.responsibilityPending + counts.reclaimPending;
}

/** 已结条目总数。 */
export function settledTotal(counts: CaseCounts): number {
  return (
    counts.responsibilityTotal -
    counts.responsibilityPending +
    (counts.reclaimTotal - counts.reclaimPending)
  );
}

/**
 * 一句话结论。
 *
 * <p>撤销态优先说「已撤销」而不是「已结清」：两者 pending 都是 0，但含义相反，
 * 混淆会让复职的人以为自己还有东西要转。
 */
export function caseSummary(counts: CaseCounts): string {
  if (counts.status === 'REVERTED') return '已撤销离职，成员已恢复启用';
  const pending = pendingTotal(counts);
  const settled = settledTotal(counts);
  if (pending === 0) {
    return settled === 0
      ? '成员已停用，没有登记待转移或待回收的条目'
      : `成员已停用，${settled} 项责任与待办已全部结清`;
  }
  return `成员已停用，还有 ${pending} 项未结（已结 ${settled} 项）`;
}

/**
 * 是否可以撤销。
 *
 * <p>只有处理中的案卷可以撤：已撤销的不能二次撤销（否则会覆盖掉第一次撤销的时刻与操作人），
 * 而「已结清」的案卷其实**可以**撤 —— 台账条目都已转移，撤销只回滚成员状态，
 * 条目不回滚（它们已经发生在下游服务里，identity 无权假装没发生）。
 */
export function canRevert(status: string): boolean {
  return status !== 'REVERTED';
}

/** 台账条目能否再动：已结清的条目不再变更。 */
export function isActionable(status: string): boolean {
  return status === 'PENDING';
}

/** 接手人未定时显示占位符，不留空白让人以为漏填了。 */
export function successorLabel(successorName: string | null | undefined): string {
  return successorName && successorName.length > 0 ? successorName : '未指定接手人';
}

/**
 * 生效时刻按 UTC 截到日期。
 *
 * <p>与账簿期间边界同一口径：生效时刻入库是 timestamptz，但页面只需要「哪一天」，
 * 用 UTC 切日能让上海与洛杉矶的管理者看到同一个日期。
 */
export function effectiveDate(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? '—' : parsed.toISOString().slice(0, 10);
}

/** 细到分钟的时刻，用于「什么时候停的」这种需要精确到分的场景。 */
export function effectiveMoment(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return '—';
  return `${parsed.toISOString().slice(0, 10)} ${parsed.toISOString().slice(11, 16)} UTC`;
}
