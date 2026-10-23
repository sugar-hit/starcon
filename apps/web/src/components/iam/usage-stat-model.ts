/**
 * 17.7.1.2 用量统计的展示口径与前端预校验。
 *
 * <p>后端 `SaasUsageController` 是最终判定（平台运营闸门、维度口径与周期口径、没接通
 * 计量的维度一律 `UNDETERMINED`），这里的校验与后端同口径同顺序，目的是让用户在提交前
 * 就看到「为什么不行」，而不是提交后吃一个 400。后端仍然是权威。
 *
 * <p>本模块最容易被前端做错的三件事：
 *
 * 1. **把「没接通计量」渲染成「没超限」**。统计事实里没有量到的维度**根本没有那一行**
 *    （不是一行 0），计费依据里它的状态是 `UNDETERMINED`——判定不了，不是「够用」。
 *    `describeBillingState` 把这两种情况写成两段完全不同的文案。
 * 2. **把流量维度的月值切进日桶**。桶的粒度由套餐定义决定（流量维度固定、存量维度跟随
 *    统计周期），前端展示时必须把「这是哪一档口径」写出来，否则同一条曲线会被误读成
 *    「每天都是这么多」。
 * 3. **把金额写进用量页面**。本模块**不落任何金额**：用量与上限都是数量
 *    （店铺数 / SKU 数 / 订单量 / 用户数 / API 调用量 / AI 用量）。
 *
 * <p>维度码与周期码的文案**直接引用 17.7.1.1 的词表**（`saas-plan-model`）：那套词表就是
 * 套餐定义的一部分，另抄一份迟早会漂——而口径漂移正是计费争议的起点。
 */

// 相对路径而不是 `@/lib/api`：根 vitest 配置没有 `@/*` 别名（那只在 Next 构建里解析），
// 用别名会让这个模型的单测在 vite 下加载失败。与 saas-plan-model 保持同一套约定。
import type {
  UsageBillingRow,
  UsageCapacityRow,
  UsageCoverageRow,
  UsageReportRow,
  UsageTrendPoint,
} from '../../../../../packages/api-contracts/src/index';
import { ENTITLEMENT_KIND_LABELS, PERIOD_LABELS } from './saas-plan-model';

/** 维度码与周期码的文案来自套餐定义（17.7.1.1），此处直接引用，不另抄一份。 */
export { ENTITLEMENT_KIND_LABELS, PERIOD_LABELS };

/** 覆盖状态：哪个维度真的接通了来源系统。 */
export const COVERAGE_STATE_LABELS: Record<string, string> = {
  WIRED: '已接通',
  PARTIAL: '部分接通',
  NOT_WIRED: '未接通',
  NO_SUBSCRIPTION: '无订阅租户',
};

/** 计费依据的状态。`UNDETERMINED` 与 `WITHIN_LIMIT` 是两件事。 */
export const BILLING_STATE_LABELS: Record<string, string> = {
  WITHIN_LIMIT: '在上限内',
  OVER_LIMIT: '已超限',
  UNLIMITED: '不限量',
  UNDETERMINED: '判定不了',
};

/** 归集批次状态。 */
export const COLLECTION_STATUS_LABELS: Record<string, string> = {
  COMPLETED: '全部维度已量到',
  PARTIAL: '有维度未接通',
};

/** 统计周期：前端不硬编码，只能选这四个（与后端一致）。 */
export const PERIOD_TYPES = ['DAY', 'MONTH', 'YEAR', 'TOTAL'] as const;

/**
 * 校验统计周期。返回空串表示通过。
 *
 * <p>与后端 `SaasUsageController.PERIODS` 同一套码：另起名字会让「同一个统计周期」
 * 在前后端指两个东西。
 */
export function validatePeriodType(periodType: string): string {
  const value = periodType.trim().toUpperCase();
  if (!value) return '统计周期不能为空';
  if (!PERIOD_TYPES.includes(value as (typeof PERIOD_TYPES)[number]))
    return `统计周期只能是 ${PERIOD_TYPES.join(' / ')}`;
  return '';
}

/**
 * 数量格式化。
 *
 * <p>用量与上限都是**数量**（NUMERIC），可能带小数；整数就不带小数点，
 * 免得「3000.000000」这种数据库原样在页面上晃。
 */
export function formatQuantity(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  if (!Number.isFinite(value)) return '—';
  const rounded = Math.round(value * 1e6) / 1e6;
  return Number.isInteger(rounded) ? String(rounded) : String(rounded);
}

/**
 * 百分比（利用率）。上限为 0 或不限量时没有百分比，返回空串。
 */
export function formatUtilization(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '';
  return `${(value * 100).toFixed(1)}%`;
}

/**
 * 周期区间文案。
 *
 * <p>只写粒度不写区间会丢掉一件要紧的事：存量维度的桶是「采集时刻的快照」，
 * 区间是它所属的那个统计周期。页面要让运营看得见「这是哪一档口径」。
 */
export function formatPeriodRange(
  periodType: string,
  periodStart: string | null,
  periodEnd: string | null,
): string {
  const label = PERIOD_LABELS[periodType] ?? periodType;
  if (!periodStart) return label;
  const start = periodStart.slice(0, 10);
  if (!periodEnd) return `${label} · ${start}`;
  return `${label} · ${start} ~ ${periodEnd.slice(0, 10)}`;
}

/**
 * 计费依据一行的一句话。
 *
 * <p>四种情形写四段不同的文案：
 * 判定不了（没接通计量）/ 不限量 / 已超限（带超限策略）/ 在上限内。
 */
export function describeBillingState(row: {
  state: string;
  overLimitAction: string | null;
  entitlementUnlimited: boolean;
  quantity: number | null;
  entitlementLimit: number | null;
  entitlementUnit: string | null;
  entitlementPeriod: string | null;
}): string {
  if (row.state === 'UNDETERMINED')
    return '尚未接通计量，判定不了（不等于「没超限」）';
  if (row.state === 'UNLIMITED' || row.entitlementUnlimited) return '不限量';
  const unit = row.entitlementUnit ?? '';
  const scope = row.entitlementPeriod
    ? row.entitlementPeriod === 'TOTAL'
      ? (PERIOD_LABELS[row.entitlementPeriod] ?? row.entitlementPeriod)
      : `每${PERIOD_LABELS[row.entitlementPeriod] ?? row.entitlementPeriod}`
    : '';
  const used = `${formatQuantity(row.quantity)} ${unit}`.trim();
  const limit = `${formatQuantity(row.entitlementLimit)} ${unit}`.trim();
  if (row.state === 'OVER_LIMIT')
    return `已超出上限（${used} / ${limit}），超限策略：${row.overLimitAction ?? '未配置'}`;
  return `${scope} ${used} / ${limit}`.trim();
}

/**
 * 用量报表一行的摘要。
 *
 * <p>报表行都是**量到了**的行（没量到的维度不在报表里，它们在计费依据里以
 * `UNDETERMINED` 出现）。所以这里不需要「判定不了」的分支。
 */
export function describeReportRow(row: {
  entitlementKind: string;
  quantity: number;
  entitlementLimit: number | null;
  entitlementUnlimited: boolean;
  entitlementUnit: string | null;
  entitlementPeriod: string | null;
  sourceSystem: string | null;
}): string {
  const kind = ENTITLEMENT_KIND_LABELS[row.entitlementKind] ?? row.entitlementKind;
  const unit = row.entitlementUnit ?? '';
  const used = `${formatQuantity(row.quantity)} ${unit}`.trim();
  const limit = row.entitlementUnlimited
    ? '不限量'
    : `${formatQuantity(row.entitlementLimit)} ${unit}`.trim();
  const source = row.sourceSystem ? ` · 来源 ${row.sourceSystem}` : '';
  return `${kind}：${used} / ${limit}${source}`;
}

/**
 * 容量规划一行的摘要。
 *
 * <p>余量为负数不是「算错了」，是**实际用量已经超过已授权容量**——这句话必须出现在
 * 文案里，否则看板上一个负数会被当成 bug。
 */
export function describeCapacity(row: {
  entitlementKind: string;
  usedTotal: number;
  licensedTotal: number;
  headroom: number;
  unlimitedTenantCount: number;
  unmeasuredTenantCount: number;
}): string {
  const kind = ENTITLEMENT_KIND_LABELS[row.entitlementKind] ?? row.entitlementKind;
  const parts = [
    `${kind}：已用 ${formatQuantity(row.usedTotal)}`,
    `已授权 ${formatQuantity(row.licensedTotal)}`,
  ];
  if (row.unlimitedTenantCount > 0) parts.push(`不设上限租户 ${row.unlimitedTenantCount} 个`);
  if (row.unmeasuredTenantCount > 0) parts.push(`未接通租户 ${row.unmeasuredTenantCount} 个`);
  if (row.headroom < 0) parts.push(`余量 ${formatQuantity(row.headroom)}（已超容量）`);
  else parts.push(`余量 ${formatQuantity(row.headroom)}`);
  return parts.join(' · ');
}

/**
 * 覆盖一行的摘要。
 *
 * <p>「未接通」与「量到 0」是两件事：未接通的维度连行都不会有，
 * 把它写成「用量 0」就是拿缺口冒充事实。
 */
export function describeCoverage(row: {
  entitlementKind: string;
  subscribedTenantCount: number;
  registeredTenantCount: number;
  measuredTenantCount: number;
  wiredSourceSystems: string | null;
  state: string;
}): string {
  const kind = ENTITLEMENT_KIND_LABELS[row.entitlementKind] ?? row.entitlementKind;
  const state = COVERAGE_STATE_LABELS[row.state] ?? row.state;
  const parts = [`${kind}：${state}`];
  parts.push(`有订阅租户 ${row.subscribedTenantCount} 个`);
  parts.push(`已登记 ${row.registeredTenantCount} 个`);
  parts.push(`已量到 ${row.measuredTenantCount} 个`);
  if (row.wiredSourceSystems) parts.push(`来源系统 ${row.wiredSourceSystems}`);
  return parts.join(' · ');
}

/**
 * 趋势序列：按「租户 + 维度」分组，组内按周期升序。
 *
 * <p>分组而不是直接按时间排，是为了让同一条曲线在图上是连续的——
 * 跨租户交错排列的散点没法读成趋势。
 */
export function summarizeTrend(points: UsageTrendPoint[]): {
  key: string;
  tenantCode: string;
  tenantName: string;
  entitlementKind: string;
  kindLabel: string;
  points: { periodStart: string; periodEnd: string | null; quantity: number; label: string }[];
}[] {
  const groups = new Map<string, UsageTrendPoint[]>();
  for (const point of points) {
    const key = `${point.tenantCode}|${point.entitlementKind}`;
    const bucket = groups.get(key);
    if (bucket) bucket.push(point);
    else groups.set(key, [point]);
  }
  return [...groups.entries()].map(([key, list]) => {
    const sorted = [...list].sort((a, b) => a.periodStart.localeCompare(b.periodStart));
    const first = sorted[0];
    return {
      key,
      tenantCode: first?.tenantCode ?? '',
      tenantName: first?.tenantName ?? '',
      entitlementKind: first?.entitlementKind ?? '',
      kindLabel: ENTITLEMENT_KIND_LABELS[first?.entitlementKind ?? ''] ?? first?.entitlementKind ?? '',
      points: sorted.map((point) => ({
        periodStart: point.periodStart,
        periodEnd: point.periodEnd,
        quantity: point.quantity,
        label: `${point.periodStart.slice(0, 10)}：${formatQuantity(point.quantity)}`,
      })),
    };
  });
}

/** 报表按租户分组（看板按租户看比按行看更像报表）。 */
export function groupReportByTenant(rows: UsageReportRow[]): {
  tenantCode: string;
  tenantName: string;
  rows: UsageReportRow[];
}[] {
  const groups = new Map<string, { tenantCode: string; tenantName: string; rows: UsageReportRow[] }>();
  for (const row of rows) {
    const key = row.tenantCode;
    const bucket = groups.get(key);
    if (bucket) bucket.rows.push(row);
    else groups.set(key, { tenantCode: row.tenantCode, tenantName: row.tenantName, rows: [row] });
  }
  return [...groups.values()];
}

/** 计费依据按状态计数（页面上给运营一眼看完的概览）。 */
export function countBillingStates(rows: UsageBillingRow[]): Record<string, number> {
  const counts: Record<string, number> = {};
  for (const row of rows) counts[row.state] = (counts[row.state] ?? 0) + 1;
  return counts;
}

/** 容量视图里余量为负的维度（已超容量的维度）。 */
export function overCapacityKinds(rows: UsageCapacityRow[]): string[] {
  return rows.filter((row) => row.headroom < 0).map((row) => row.entitlementKind);
}

/** 覆盖视图里没接通的维度。 */
export function unwiredKinds(rows: UsageCoverageRow[]): string[] {
  return rows
    .filter((row) => row.state === 'NOT_WIRED' || row.state === 'PARTIAL')
    .map((row) => row.entitlementKind);
}
