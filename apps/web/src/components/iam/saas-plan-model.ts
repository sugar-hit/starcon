/**
 * 17.7.1.1 套餐与权益的展示口径与前端预校验。
 *
 * <p>后端 `SaasPlanController` 是最终判定（平台运营闸门、状态流转白名单、未接通计量的维度
 * 一律 `UNDETERMINED`），这里的校验与后端同口径同顺序，目的是让用户在提交前就看到
 * 「为什么不行」，而不是提交后吃一个 400。后端仍然是权威。
 *
 * <p>本模块最容易被前端做错的两件事：
 *
 * 1. **把「没接通计量」渲染成「没超限」**。`available=false` 的权益维度用量是 `null`，
 *    判定结果是 `UNDETERMINED`——判定不了，不是「够用」。`describeEntitlementState`
 *    把这两种情况写成两段完全不同的文案。
 * 2. **把金额写进权益上限**。本模块**不落任何金额**：上限与用量都是数量
 *    （店铺数 / SKU 数 / 订单量 / 用户数 / API 调用量 / AI 用量）。
 */

// 相对路径而不是 `@/lib/api`：根 vitest 配置没有 `@/*` 别名（那只在 Next 构建里解析），
// 用别名会让这个模型的单测在 vite 下加载失败。与 department-leader-model 保持同一套约定。
import type { UsageView } from '../../../../../packages/api-contracts/src/index';

/** 权益维度：蓝图职责点名的六类。 */
export type EntitlementKind =
  'SHOP_COUNT' | 'SKU_COUNT' | 'ORDER_VOLUME' | 'USER_COUNT' | 'API_CALL' | 'AI_USAGE';

export const ENTITLEMENT_KIND_LABELS: Record<string, string> = {
  SHOP_COUNT: '店铺数',
  SKU_COUNT: 'SKU 数',
  ORDER_VOLUME: '订单量',
  USER_COUNT: '用户数',
  API_CALL: 'API 调用量',
  AI_USAGE: 'AI 用量',
};

/** 超限策略：阻断 / 降级 / 放行。 */
export const OVER_LIMIT_ACTION_LABELS: Record<string, string> = {
  BLOCK: '阻断',
  DEGRADE: '降级',
  ALLOW: '放行',
};

/** 计量周期。 */
export const PERIOD_LABELS: Record<string, string> = {
  DAY: '日',
  MONTH: '月',
  YEAR: '年',
  TOTAL: '总量',
};

/** 订阅状态。 */
export const SUBSCRIPTION_STATUS_LABELS: Record<string, string> = {
  TRIAL: '试用中',
  ACTIVE: '生效中',
  EXPIRED: '已到期',
  SUSPENDED: '已暂停',
  CANCELLED: '已取消',
};

/** 套餐状态。 */
export const PLAN_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '在售',
  RETIRED: '已退役',
};

/** 变更留痕动作。 */
export const EVENT_ACTION_LABELS: Record<string, string> = {
  CREATED: '开通',
  UPDATED: '编辑',
  STATUS_CHANGED: '状态变更',
  RENEWED: '续费延期',
  TRIAL_STARTED: '开始试用',
  TRIAL_ENDED: '试用转正式',
  EXPIRED: '到期',
  CANCELLED: '取消',
  SUSPENDED: '暂停',
  RESUMED: '恢复',
};

/**
 * 超限判定状态。
 *
 * <p>`UNDETERMINED` 与 `WITHIN_LIMIT` 是两件事：前者是「量不到，判定不了」，
 * 后者是「量到了，在上限内」。混起来写就是拿缺口冒充事实。
 */
export const ENTITLEMENT_STATE_LABELS: Record<string, string> = {
  WITHIN_LIMIT: '在上限内',
  OVER_LIMIT_BLOCKED: '已超限 · 阻断',
  OVER_LIMIT_DEGRADED: '已超限 · 降级',
  OVER_LIMIT_ALLOWED: '已超限 · 仅记录',
  UNDETERMINED: '判定不了',
  NO_SUBSCRIPTION: '无订阅',
  UNLIMITED: '不限量',
};

/**
 * 订阅状态流转白名单（与后端 `SaasPlanController.ALLOWED_TRANSITIONS` 逐条对应）。
 *
 * <p>前端保留它是为了**不渲染点不动的按钮**；真正的判定在服务端。
 * `CANCELLED` 是终态：取消后不能恢复——恢复等于抹掉一次取消事实。
 */
export const ALLOWED_TRANSITIONS: Record<string, string[]> = {
  TRIAL: ['ACTIVE', 'EXPIRED', 'CANCELLED', 'SUSPENDED'],
  ACTIVE: ['EXPIRED', 'CANCELLED', 'SUSPENDED'],
  SUSPENDED: ['ACTIVE', 'EXPIRED', 'CANCELLED'],
  EXPIRED: ['ACTIVE', 'CANCELLED'],
  CANCELLED: [],
};

/** 套餐编码 / 权益编码：大写字母开头、字母数字下划线、2–64 位。 */
export const PLAN_CODE_PATTERN = /^[A-Z][A-Z0-9_]{1,63}$/;

/** 校验套餐编码。返回空串表示通过。 */
export function validatePlanCode(code: string): string {
  const value = code.trim();
  if (!value) return '套餐编码不能为空';
  if (!PLAN_CODE_PATTERN.test(value))
    return '编码需为大写字母开头的标识符（2–64 位字母、数字或下划线）';
  return '';
}

/** 校验权益编码。与套餐编码同一口径。 */
export function validateEntitlementCode(code: string): string {
  const value = code.trim();
  if (!value) return '权益编码不能为空';
  if (!PLAN_CODE_PATTERN.test(value))
    return '编码需为大写字母开头的标识符（2–64 位字母、数字或下划线）';
  return '';
}

/** 校验权益上限。数量不能为负；不限量时留空即可。 */
export function validateLimit(limit: string, unlimited: boolean): string {
  if (unlimited) return '';
  const value = limit.trim();
  if (!value) return '上限不能为空（不限量请勾选「不限量」）';
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return '上限必须是数字';
  if (parsed < 0) return '上限不能为负数';
  return '';
}

/**
 * 权益用量的可读摘要。
 *
 * <p>三种情形写三段不同的文案：
 * 未接通计量 → 「尚未接通计量，判定不了」；
 * 不限量 → 「不限量」；
 * 正常 → 「当前用量 / 上限（周期）」。
 */
export function describeEntitlementState(input: {
  available: boolean;
  unlimited: boolean;
  usageValue: number | null;
  limitValue: number | null;
  period: string | null;
  state: string;
}): string {
  if (!input.available) return '尚未接通计量，判定不了（不等于「没超限」）';
  if (input.unlimited) return '不限量';
  const used = input.usageValue ?? 0;
  const limit = input.limitValue ?? 0;
  const period = input.period ? (PERIOD_LABELS[input.period] ?? input.period) : '';
  // 「每日/每月/每年」是重置周期；「总量」不是周期，不能拼成「每总量」。
  const scope = !period ? '' : input.period === 'TOTAL' ? period : `每${period}`;
  return `${scope} ${used} / ${limit}`.trim();
}

/**
 * 权益用量视图（契约类型）→ 一句话摘要。
 *
 * <p>放在 model 层而不是组件里，是因为「把哪个字段接成周期」是最容易被写坏的一步：
 * 组件里内联映射时容易顺手写成 {@code period: null}，于是「每月 3000 单」被渲染成
 * 没有周期的「3000 / 3000」——数字对了，语义丢了。这里用契约类型锁死字段来源，
 * 单测直接拿真实的 {@code UsageView} 断言周期确实出现在文案里。
 */
export function usageSummary(usage: UsageView): string {
  return describeEntitlementState({
    available: usage.available,
    unlimited: usage.unlimited,
    usageValue: usage.usageValue,
    limitValue: usage.limitValue,
    period: usage.period,
    state: usage.state,
  });
}

/** 超限判定结论的一句话。 */
export function entitlementVerdict(state: string): string {
  switch (state) {
    case 'WITHIN_LIMIT':
      return '用量在权益上限内';
    case 'OVER_LIMIT_BLOCKED':
      return '已超出上限，按套餐配置应当阻断';
    case 'OVER_LIMIT_DEGRADED':
      return '已超出上限，按套餐配置应当降级';
    case 'OVER_LIMIT_ALLOWED':
      return '已超出上限，按套餐配置只记录不拦截';
    case 'UNLIMITED':
      return '该权益不设上限';
    case 'NO_SUBSCRIPTION':
      return '该租户没有订阅，无从判定';
    case 'UNDETERMINED':
    default:
      return '该维度尚未接通计量，判定不了（不等于「没超限」）';
  }
}

/** 订阅状态的可读文案。推导状态与存储状态不同时，要说清是哪一个过期了。 */
export function subscriptionStatusText(status: string | null, effectiveStatus: string): string {
  if (!status) return '未开通';
  if (status === effectiveStatus) return SUBSCRIPTION_STATUS_LABELS[status] ?? status;
  return `${SUBSCRIPTION_STATUS_LABELS[status] ?? status} · 已按时间判定为${
    SUBSCRIPTION_STATUS_LABELS[effectiveStatus] ?? effectiveStatus
  }`;
}

/** 这个状态允许流转到哪些状态。 */
export function nextStatuses(status: string | null): string[] {
  if (!status) return [];
  return ALLOWED_TRANSITIONS[status] ?? [];
}

/** 变更留痕一行的可读摘要。 */
export function eventSummary(row: {
  action: string;
  fromStatus: string | null;
  toStatus: string | null;
  reason: string;
}): string {
  const label = EVENT_ACTION_LABELS[row.action] ?? row.action;
  const from = row.fromStatus ? (SUBSCRIPTION_STATUS_LABELS[row.fromStatus] ?? row.fromStatus) : '';
  const to = row.toStatus ? (SUBSCRIPTION_STATUS_LABELS[row.toStatus] ?? row.toStatus) : '';
  const move = from && to && from !== to ? `（${from} → ${to}）` : '';
  return `${label}${move}${row.reason ? `：${row.reason}` : ''}`;
}

/**
 * 套餐摘要：给列表用的一行话。
 *
 * <p>权益项数与「阻断型权益数」都要出现在摘要里：阻断与降级对客户的影响完全不同，
 * 只看「6 个权益项」看不出这个套餐超限之后是拦死还是降级。
 */
export function planSummary(plan: {
  name: string;
  code: string;
  status: string;
  trialDays: number;
  entitlementCount: number;
  blockedActionCount: number;
}): string {
  const parts = [`${plan.name}（${plan.code}）`, PLAN_STATUS_LABELS[plan.status] ?? plan.status];
  if (plan.trialDays > 0) parts.push(`试用 ${plan.trialDays} 天`);
  parts.push(`权益 ${plan.entitlementCount} 项`);
  if (plan.blockedActionCount > 0) parts.push(`其中阻断型 ${plan.blockedActionCount} 项`);
  return parts.join(' · ');
}

/**
 * 能力声明里未交付维度的可读文案。
 *
 * <p>未交付段必须同时说清「归谁」与「缺了会怎样」——只写一句「暂不支持」，
 * 等于把缺口说成了事实。
 */
export function capabilitySummary(dimension: {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}): string {
  if (dimension.available) return `${dimension.dimension}：${dimension.description}`;
  return `${dimension.dimension}：${dimension.description}（归属 ${dimension.ownerModule}）—— ${dimension.impact}`;
}
