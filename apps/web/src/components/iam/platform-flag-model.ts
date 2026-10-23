/**
 * 17.7.2.1 平台参数与开关的展示口径与前端预校验。
 *
 * <p>后端 {@code PlatformFlagController} 是最终判定（裁决口径在服务端的
 * {@code adjudicate} 里），这里的函数只做**展示**与**提交前的预校验**：
 * 同一套口径写在前端，是为了让用户在点保存之前就看到「为什么不行」，
 * 而不是提交后吃一个 400。后端仍然是权威 —— 即使前端校验放过，后端也会拒。
 *
 * <p>🔴 本模块最要紧的一条纪律是**三层裁决要看得见**：平台全局值 / 租户例外 /
 * 最终生效值。界面上只给一个布尔值，用户没法判断「为什么这个功能对我不可用」
 * ——是平台关了、是平台单独禁用了这个租户、还是这个租户没进灰度分桶？
 * 这三件事的处置方式完全不同，所以 {@link describeEvaluation} 必须把三层都写出来。
 */

/** 平台参数的三类取值域。 */
export type FlagKind = 'MODULE' | 'RATE_LIMIT' | 'ANNOUNCEMENT';

export const FLAG_KIND_LABELS: Record<string, string> = {
  MODULE: '功能开关',
  RATE_LIMIT: '限流阈值',
  ANNOUNCEMENT: '公告',
};

/** 裁决依据码 → 人话。与后端 {@code PlatformFlagController.adjudicate} 的 reason 一一对应。 */
export const REASON_LABELS: Record<string, string> = {
  PLATFORM_DISABLED: '平台已关闭',
  TENANT_DISABLED: '平台对本租户单独禁用',
  ROLLOUT_INCLUDED: '在灰度放量范围内',
  ROLLOUT_EXCLUDED: '不在灰度放量范围内',
  NOT_CONFIGURED: '平台尚未配置',
  PLATFORM_THRESHOLD: '按平台阈值执行',
  ANNOUNCEMENT_ACTIVE: '公告生效中',
  ANNOUNCEMENT_OUT_OF_WINDOW: '不在公告生效期内',
  ANNOUNCEMENT_NOT_PUBLISHED: '公告未发布',
};

/** 三层裁决每一层的名字。 */
export const LAYER_LABELS = {
  platform: '平台全局值',
  tenant: '租户例外',
  effective: '最终生效值',
} as const;

/** 变更历史动作。 */
export const ACTION_LABELS: Record<string, string> = {
  CREATED: '新增',
  UPDATED: '修改',
  RELEASED: '撤销',
};

/** 灰度分桶总数（万分之）。与后端 ROLLOUT_BUCKETS 一致。 */
export const ROLLOUT_BUCKETS = 10000;

/** 判定结果的最小形状：前端展示裁决只需要这些字段。 */
export type EvaluationLike = {
  flagCode: string;
  flagName: string;
  flagKind: string;
  tenantId: string;
  platformLayer: {
    configured: boolean;
    enabled: boolean;
    rolloutPercent: number | string | null;
    rateLimitPerMinute: number | null;
    announcementTitle: string | null;
    announcementFrom: string | null;
    announcementTo: string | null;
  };
  tenantLayer: {
    applicable: boolean;
    present: boolean;
    effect: string | null;
    reason: string | null;
  };
  effectiveLayer: {
    enabled: boolean;
    reason: string;
    rolloutBucket: number | null;
    rolloutBasisPoints: number | null;
    rateLimitPerMinute: number | null;
    announcementActive: boolean | null;
  };
};

/** 把百分比显示成两位小数。null / 非数字一律显示为「—」，不编造 0。 */
export function formatPercent(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(n)) return '—';
  return `${n.toFixed(2)}%`;
}

/** 裁决依据的人话。未知码原样透出（后端加了新码时前端不至于显示空白）。 */
export function reasonLabel(reason: string | null | undefined): string {
  if (!reason) return '—';
  return REASON_LABELS[reason] ?? reason;
}

/**
 * 平台全局层的可读摘要。
 *
 * <p>按类别给不同的摘要口径：功能开关看「开关 + 放量比例」，
 * 限流阈值看「每分钟多少次」，公告看「标题 + 生效期」。
 */
export function platformSummary(row: {
  flagKind: string;
  configured: boolean;
  enabled: boolean;
  rolloutPercent: number | string | null;
  rateLimitPerMinute: number | null;
  announcementTitle: string | null;
  announcementFrom: string | null;
  announcementTo: string | null;
}): string {
  if (!row.configured) return '平台尚未配置';
  if (row.flagKind === 'RATE_LIMIT')
    return row.rateLimitPerMinute === null
      ? '平台尚未配置'
      : `每分钟 ${row.rateLimitPerMinute} 次`;
  if (row.flagKind === 'ANNOUNCEMENT')
    return row.announcementTitle
      ? `${row.announcementTitle}（${row.announcementFrom ?? '?'} ~ ${row.announcementTo ?? '?'}）`
      : '平台尚未配置';
  return `${row.enabled ? '开启' : '关闭'} · 放量 ${formatPercent(row.rolloutPercent)}`;
}

/**
 * 三层裁决的可读摘要：一句话把「平台值 / 租户例外 / 最终生效值」串起来。
 *
 * <p>这是本模块最该被看到的一行。它必须同时出现平台口径和租户例外，
 * 否则用户会以为自己看到的就是最终结果 —— 而平台值与最终值不一致时，
 * 差异恰恰是需要解释的东西。
 */
export function describeEvaluation(row: EvaluationLike): string {
  const platform = platformSummary({
    flagKind: row.flagKind,
    configured: row.platformLayer.configured,
    enabled: row.platformLayer.enabled,
    rolloutPercent: row.platformLayer.rolloutPercent,
    rateLimitPerMinute: row.platformLayer.rateLimitPerMinute,
    announcementTitle: row.platformLayer.announcementTitle,
    announcementFrom: row.platformLayer.announcementFrom,
    announcementTo: row.platformLayer.announcementTo,
  });
  const tenant = !row.tenantLayer.applicable
    ? '本类不接受按租户禁用'
    : row.tenantLayer.present
      ? `本租户被单独禁用（${row.tenantLayer.reason ?? '未填原因'}）`
      : '本租户无例外';
  const effective = row.effectiveLayer.enabled
    ? '最终可用'
    : '最终不可用';
  return `${platform} ｜ ${tenant} ｜ ${effective}（${reasonLabel(row.effectiveLayer.reason)}）`;
}

/**
 * 灰度分桶的可读说明。
 *
 * <p>把「分桶号 / 阈值」摆出来，是为了让「为什么这个租户没进灰度」可被核对：
 * 分桶是确定性哈希算出来的，两边算一遍就知道是不是同一个口径。
 */
export function rolloutExplanation(row: {
  rolloutBucket: number | null;
  rolloutBasisPoints: number | null;
}): string {
  if (row.rolloutBucket === null || row.rolloutBasisPoints === null) return '';
  const inCohort = row.rolloutBucket < row.rolloutBasisPoints;
  return `分桶 ${row.rolloutBucket} / ${ROLLOUT_BUCKETS}，放量阈值 ${row.rolloutBasisPoints} → ${
    inCohort ? '命中' : '未命中'
  }`;
}

/**
 * 公告生效期状态。
 *
 * <p>「未到开始时刻」与「已经过了结束时刻」要分开说：
 * 前者还能等，后者要改窗口 —— 合成一句「不在生效期」的话，运营不知道该等还是该改。
 */
export function announcementWindowState(
  from: string | null | undefined,
  to: string | null | undefined,
  now: Date = new Date(),
): '未配置' | '未生效' | '生效中' | '已过期' {
  if (!from || !to) return '未配置';
  const start = new Date(from).getTime();
  const end = new Date(to).getTime();
  if (Number.isNaN(start) || Number.isNaN(end)) return '未配置';
  const current = now.getTime();
  if (current < start) return '未生效';
  if (current > end) return '已过期';
  return '生效中';
}

/** 平台全局层写入输入。 */
export type SettingDraft = {
  enabled: boolean;
  rolloutPercent: string;
  rateLimitPerMinute: string;
  announcementTitle: string;
  announcementBody: string;
  announcementFrom: string;
  announcementTo: string;
  note: string;
};

/**
 * 写入前的预校验，与后端 {@code PlatformFlagController.validateSettingInput} 同口径同顺序。
 *
 * <p>返回可读原因；通过校验时返回空串。
 */
export function validateSettingInput(kind: string, draft: SettingDraft): string {
  const percent = Number(draft.rolloutPercent);
  if (draft.rolloutPercent.trim() === '') return '灰度放量比例不能为空';
  if (!Number.isFinite(percent)) return '灰度放量比例需要是一个数值';
  if (percent < 0 || percent > 100) return '灰度放量比例必须在 0.00 ~ 100.00 之间';

  if (kind === 'RATE_LIMIT') {
    if (draft.rateLimitPerMinute.trim() === '')
      return '限流阈值必须由平台显式给出（系统不给默认值）';
    const limit = Number(draft.rateLimitPerMinute);
    if (!Number.isInteger(limit) || limit <= 0) return '限流阈值需要是一个正整数';
    return '';
  }

  if (kind === 'ANNOUNCEMENT') {
    if (!draft.announcementTitle.trim()) return '公告必须填写标题';
    if (!draft.announcementFrom.trim() || !draft.announcementTo.trim())
      return '公告必须给出生效期（起 / 止）';
    const from = new Date(draft.announcementFrom).getTime();
    const to = new Date(draft.announcementTo).getTime();
    if (Number.isNaN(from) || Number.isNaN(to)) return '公告生效期需要是可解析的时间';
    if (to <= from) return '公告结束时刻必须晚于开始时刻';
    return '';
  }

  if (draft.rateLimitPerMinute.trim() !== '' || draft.announcementTitle.trim() !== '')
    return '功能开关不接受限流阈值与公告字段';
  return '';
}

/** 按租户禁用的输入预校验。 */
export function validateExceptionInput(flagKind: string, reason: string): string {
  if (flagKind !== 'MODULE')
    return '只有功能开关可以按租户禁用；限流阈值与公告不适用（禁用它们等于放宽限制）';
  if (!reason.trim()) return '必须写明禁用原因';
  return '';
}

/** 把 ISO 字符串转成 {@code datetime-local} 输入框要的本地格式。 */
export function toLocalInputValue(iso: string | null | undefined): string {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
}

/** 把 {@code datetime-local} 输入框的值转成 ISO 字符串（带时区偏移）。 */
export function fromLocalInputValue(value: string): string | null {
  if (!value.trim()) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return date.toISOString();
}

/** 按类别分组，供配置页分区渲染。分组顺序固定，不随返回顺序漂移。 */
export function groupByKind<T extends { flagKind: string }>(
  rows: readonly T[],
  order: readonly string[] = ['MODULE', 'RATE_LIMIT', 'ANNOUNCEMENT'],
): { kind: string; label: string; rows: T[] }[] {
  return order
    .map((kind) => ({
      kind,
      label: FLAG_KIND_LABELS[kind] ?? kind,
      rows: rows.filter((row) => row.flagKind === kind),
    }))
    .filter((group) => group.rows.length > 0);
}
