'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button } from '@starcon/ui';
import { RefreshCw } from 'lucide-react';
import type {
  UsageBillingRow,
  UsageCapabilitiesView,
  UsageCapacityRow,
  UsageCollectionRow,
  UsageCollectionRun,
  UsageCoverageRow,
  UsageOptionsView,
  UsageReportRow,
  UsageTrendPoint,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '@/lib/api';
import { useIdentity } from '../iam/identity-shell';
import {
  BILLING_STATE_LABELS,
  COLLECTION_STATUS_LABELS,
  COVERAGE_STATE_LABELS,
  describeBillingState,
  describeCapacity,
  describeCoverage,
  describeReportRow,
  formatPeriodRange,
  formatQuantity,
  formatUtilization,
  groupReportByTenant,
  overCapacityKinds,
  summarizeTrend,
  unwiredKinds,
  validatePeriodType,
} from './usage-stat-model';

/**
 * 17.7.1.2 用量统计。
 *
 * <p>starcon <b>自身</b>的 SaaS 商业化域的统计层：把来源系统登记的「当前用量」物化成
 * 按周期分桶的统计事实，于是有了用量报表、趋势、计费依据与容量规划。页面上没有
 * 「租户自助查看自己用量」的入口——本模块与套餐、权益同属平台运营域。
 *
 * <p>页面分六块：
 *
 * <ul>
 *   <li><b>能力声明</b>——把「实做了什么」与「<b>没做</b>什么」都摆在明面上。未交付的维度
 *       （来源系统埋点接入、存储维度计量、计费与收入确认）逐条点名归属模块与影响面，
 *       不用空列表或 0 冒充「这一项没有数据」。</li>
 *   <li><b>归集</b>——把登记的当前用量落成周期桶。重复归集是覆盖不是累加。</li>
 *   <li><b>用量报表</b>——租户 × 维度 × 周期一行，含权益上限与超限结论。</li>
 *   <li><b>趋势</b>——按维度聚合的周期序列。</li>
 *   <li><b>计费依据</b>——每个租户的每个维度：用量 + 上限 + 结论。<b>没量到的维度也在
 *       这里</b>，结论是「判定不了」而不是「没超限」。</li>
 *   <li><b>容量规划与维度覆盖</b>——已授权容量 vs 实际用量，以及哪个维度真的接通了
 *       来源系统。</li>
 * </ul>
 *
 * <p>四种状态各不相同：加载中、读不到（没有平台运营权限）、出错（可重试）、
 * 真的没有数据（空态）。「读不到」与「没有数据」混成一句话，平台运营就会以为
 * 「平台还没配用量统计」而去找错方向。
 */
export function UsageStatWorkspace() {
  const { session } = useIdentity();
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const [capabilities, setCapabilities] = useState<UsageCapabilitiesView | null>(null);
  const [options, setOptions] = useState<UsageOptionsView | null>(null);
  const [report, setReport] = useState<UsageReportRow[]>([]);
  const [trend, setTrend] = useState<UsageTrendPoint[]>([]);
  const [billing, setBilling] = useState<UsageBillingRow[]>([]);
  const [capacity, setCapacity] = useState<UsageCapacityRow[]>([]);
  const [coverage, setCoverage] = useState<UsageCoverageRow[]>([]);
  const [collections, setCollections] = useState<UsageCollectionRow[]>([]);

  const [tenantId, setTenantId] = useState('');
  const [kind, setKind] = useState('');
  const [periodType, setPeriodType] = useState('MONTH');

  const can = useCallback(
    (permission: string) => session.permissions.includes(permission),
    [session.permissions],
  );
  const canRead = can('identity.subscription.read');
  const canCollect = can('identity.subscription.update');

  const query = useMemo(() => {
    const parts = new URLSearchParams();
    if (tenantId.trim()) parts.set('tenantId', tenantId.trim());
    if (kind.trim()) parts.set('kind', kind.trim());
    if (periodType.trim()) parts.set('periodType', periodType.trim());
    const text = parts.toString();
    return text ? `?${text}` : '';
  }, [tenantId, kind, periodType]);

  const load = useCallback(async () => {
    setError('');
    if (!canRead) {
      setReady(true);
      return;
    }
    try {
      const [capabilitiesView, optionsView, reportRows, trendPoints, billingRows, capacityRows, coverageRows, collectionRows] =
        await Promise.all([
          api<UsageCapabilitiesView>('identity/saas-usage/capabilities'),
          api<UsageOptionsView>('identity/saas-usage/options'),
          api<UsageReportRow[]>(`identity/saas-usage/report${query}`),
          api<UsageTrendPoint[]>(`identity/saas-usage/trend${query}`),
          api<UsageBillingRow[]>(`identity/saas-usage/billing-basis${query}`),
          api<UsageCapacityRow[]>(`identity/saas-usage/capacity?periodType=${periodType.trim()}`),
          api<UsageCoverageRow[]>('identity/saas-usage/coverage'),
          api<UsageCollectionRow[]>(`identity/saas-usage/collections${query}`),
        ]);
      setCapabilities(capabilitiesView);
      setOptions(optionsView);
      setReport(reportRows);
      setTrend(trendPoints);
      setBilling(billingRows);
      setCapacity(capacityRows);
      setCoverage(coverageRows);
      setCollections(collectionRows);
    } catch (e) {
      setError(e instanceof Error ? e.message : '请求失败，请重试');
    } finally {
      setReady(true);
    }
  }, [canRead, query, periodType]);

  useEffect(() => {
    void load();
  }, [load]);

  async function collect(target: string) {
    const problem = validatePeriodType(periodType);
    if (problem) {
      setError(problem);
      return;
    }
    setBusy(true);
    setError('');
    try {
      const body: Record<string, unknown> = { periodType: periodType.trim().toUpperCase() };
      if (target.trim()) body.tenantId = target.trim();
      const run = await api<UsageCollectionRun>('identity/saas-usage/collections', 'POST', body);
      setNotice(
        `归集完成：${run.tenantCount} 个租户，量到 ${run.measuredDimensionCount} 个维度，未接通 ${run.unmeasuredDimensionCount} 个`,
      );
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '归集失败，请重试');
    } finally {
      setBusy(false);
    }
  }

  if (!ready)
    return (
      <p role="status" className="text-muted-foreground p-6 text-sm">
        正在加载用量统计…
      </p>
    );

  if (!canRead)
    return (
      <p className="rounded-lg border p-6 text-sm">
        当前角色没有用量统计的查看权限。用量统计是<b>平台运营域</b>，租户不可访问——
        请联系平台运营方开通平台运营角色。
      </p>
    );

  const reportGroups = groupReportByTenant(report);
  const trendGroups = summarizeTrend(trend);
  const overCapacity = overCapacityKinds(capacity);
  const unwired = unwiredKinds(coverage);

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-copy-13 text-muted-foreground">
          用量统计是 starcon <b>自身的 SaaS 商业化域</b>，仅平台管理员可见。用量与上限都是
          <b>数量</b>（店铺数 / SKU 数 / 订单量 / 用户数 / API 调用量 / AI
          用量），本模块不涉及任何金额。维度码与周期码与套餐定义严格一致。
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="ghost" size="sm" onClick={() => void load()} disabled={busy}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
          {canCollect && (
            <Button size="sm" onClick={() => void collect(tenantId)} disabled={busy}>
              运行归集
            </Button>
          )}
        </div>
      </div>

      <div className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-copy-12">
          <span className="text-muted-foreground">目标租户 ID（留空 = 全部）</span>
          <input
            className="border rounded px-2 py-1 font-mono text-copy-12"
            value={tenantId}
            onChange={(e) => setTenantId(e.target.value)}
            placeholder="租户 ID"
          />
        </label>
        <label className="flex flex-col gap-1 text-copy-12">
          <span className="text-muted-foreground">维度</span>
          <select
            className="border rounded px-2 py-1 text-copy-12"
            value={kind}
            onChange={(e) => setKind(e.target.value)}
          >
            <option value="">全部维度</option>
            {(options?.entitlementKinds ?? []).map((k) => (
              <option key={k} value={k}>
                {k}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-copy-12">
          <span className="text-muted-foreground">统计周期</span>
          <select
            className="border rounded px-2 py-1 text-copy-12"
            value={periodType}
            onChange={(e) => setPeriodType(e.target.value)}
          >
            {(options?.periods ?? ['DAY', 'MONTH', 'YEAR', 'TOTAL']).map((p) => (
              <option key={p} value={p}>
                {p}
              </option>
            ))}
          </select>
        </label>
      </div>

      {error && (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm"
        >
          {error}
          <Button variant="link" onClick={() => void load()}>
            重试
          </Button>
        </div>
      )}
      {notice && (
        <p role="status" className="text-success text-sm">
          {notice}
        </p>
      )}

      {/* ---------------- 能力声明 ---------------- */}
      {capabilities && (
        <section className="space-y-2">
          <h2 className="text-copy-15 font-medium">能力声明</h2>
          <ul className="space-y-1">
            {capabilities.dimensions.map((d) => (
              <li
                key={d.dimension}
                className={`text-copy-12 rounded-md border p-2 ${
                  d.available ? 'text-muted-foreground' : 'border-warning/40 bg-warning/5'
                }`}
              >
                <Badge tone={d.available ? 'info' : 'warning'}>
                  {d.available ? '已交付' : '未交付'}
                </Badge>
                <span className="ml-2">
                  {d.available
                    ? `${d.dimension}：${d.description}`
                    : `${d.dimension}：${d.description}（归属 ${d.ownerModule}）—— ${d.impact}`}
                </span>
              </li>
            ))}
          </ul>
          <p className="text-copy-12 text-muted-foreground">{capabilities.caliberNote}</p>
          <p className="text-copy-12 text-muted-foreground">{capabilities.moneyNote}</p>
        </section>
      )}

      {/* ---------------- 用量报表 ---------------- */}
      <section className="space-y-2">
        <h2 className="text-copy-15 font-medium">用量报表</h2>
        {report.length === 0 ? (
          <div className="bg-card rounded-lg border p-8 text-center">
            <p className="text-copy-13 text-muted-foreground">
              还没有任何用量统计事实。来源系统经
              <b> POST /identity/saas-plans/usage </b>登记用量之后，再点「运行归集」，
              这里才会出现内容。
            </p>
          </div>
        ) : (
          reportGroups.map((group) => (
            <div key={group.tenantCode} className="bg-card rounded-lg border p-3">
              <p className="text-copy-13 font-medium">
                {group.tenantName}（{group.tenantCode}）
              </p>
              <ul className="mt-2 space-y-1">
                {group.rows.map((row) => (
                  <li key={`${row.entitlementCode}-${row.periodType}-${row.periodStart}`} className="text-copy-12">
                    {describeReportRow(row)}
                    <span className="text-muted-foreground">
                      {' '}
                      · {formatPeriodRange(row.periodType, row.periodStart, row.periodEnd)}
                      {row.utilization !== null ? ` · 利用率 ${formatUtilization(row.utilization)}` : ''}
                      {row.overLimit ? ' · 已超限' : ''}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          ))
        )}
      </section>

      {/* ---------------- 趋势 ---------------- */}
      <section className="space-y-2">
        <h2 className="text-copy-15 font-medium">趋势</h2>
        {trendGroups.length === 0 ? (
          <p className="text-copy-13 text-muted-foreground">
            还没有可成趋势的用量。趋势按周期桶产生——同一个周期重复归集只会覆盖同一个桶，
            要看出趋势需要跨周期归集。
          </p>
        ) : (
          <ul className="space-y-2">
            {trendGroups.map((group) => (
              <li key={group.key} className="bg-card rounded-lg border p-3">
                <p className="text-copy-13 font-medium">
                  {group.tenantName}（{group.tenantCode}）· {group.kindLabel}
                </p>
                <ul className="text-copy-12 mt-1 flex flex-wrap gap-3">
                  {group.points.map((point) => (
                    <li key={point.periodStart}>{point.label}</li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* ---------------- 计费依据 ---------------- */}
      <section className="space-y-2">
        <h2 className="text-copy-15 font-medium">计费依据</h2>
        {billing.length === 0 ? (
          <p className="text-copy-13 text-muted-foreground">
            还没有可出计费依据的租户。计费依据按「租户 × 权益维度」给一行，
            含周期用量、对应权益上限与超限结论。
          </p>
        ) : (
          <table className="text-copy-12 w-full">
            <thead>
              <tr className="text-muted-foreground">
                <th className="text-left font-normal">租户</th>
                <th className="text-left font-normal">维度</th>
                <th className="text-left font-normal">结论</th>
                <th className="text-left font-normal">口径</th>
              </tr>
            </thead>
            <tbody>
              {billing.map((row) => (
                <tr key={`${row.tenantCode}-${row.entitlementCode}`} className="border-t">
                  <td className="py-1">
                    {row.tenantName}（{row.tenantCode}）
                  </td>
                  <td>{row.entitlementKind}</td>
                  <td>
                    <Badge
                      tone={
                        row.state === 'OVER_LIMIT'
                          ? 'warning'
                          : row.state === 'UNDETERMINED'
                            ? 'warning'
                            : 'info'
                      }
                    >
                      {BILLING_STATE_LABELS[row.state] ?? row.state}
                    </Badge>
                    <span className="ml-2">{describeBillingState(row)}</span>
                  </td>
                  <td className="text-muted-foreground">
                    {row.periodType
                      ? formatPeriodRange(row.periodType, row.periodStart, row.periodEnd)
                      : '未归集'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {/* ---------------- 容量规划与维度覆盖 ---------------- */}
      <section className="grid gap-4 lg:grid-cols-2">
        <div className="space-y-2">
          <h2 className="text-copy-15 font-medium">容量规划</h2>
          {capacity.length === 0 ? (
            <p className="text-copy-13 text-muted-foreground">
              还没有容量数据。容量视图按维度给出「已授权容量 vs 实际用量」。
            </p>
          ) : (
            <ul className="space-y-1">
              {capacity.map((row) => (
                <li
                  key={row.entitlementKind}
                  className={`text-copy-12 rounded-md border p-2 ${
                    row.headroom < 0 ? 'border-warning/40 bg-warning/5' : ''
                  }`}
                >
                  {describeCapacity(row)}
                </li>
              ))}
            </ul>
          )}
          {overCapacity.length > 0 && (
            <p className="text-copy-12 text-warning">
              已超容量的维度：{overCapacity.join('、')}。余量为负不是算错，是实际用量已经超过
              已授权容量，需要扩容或调整套餐。
            </p>
          )}
        </div>
        <div className="space-y-2">
          <h2 className="text-copy-15 font-medium">维度覆盖</h2>
          {coverage.length === 0 ? (
            <p className="text-copy-13 text-muted-foreground">
              还没有覆盖数据。覆盖视图说明每个维度到底有没有来源系统在登记。
            </p>
          ) : (
            <ul className="space-y-1">
              {coverage.map((row) => (
                <li
                  key={row.entitlementKind}
                  className={`text-copy-12 rounded-md border p-2 ${
                    row.state === 'WIRED' ? '' : 'border-warning/40 bg-warning/5'
                  }`}
                >
                  <Badge tone={row.state === 'WIRED' ? 'info' : 'warning'}>
                    {COVERAGE_STATE_LABELS[row.state] ?? row.state}
                  </Badge>
                  <span className="ml-2">{describeCoverage(row)}</span>
                </li>
              ))}
            </ul>
          )}
          {unwired.length > 0 && (
            <p className="text-copy-12 text-warning">
              尚未接通的维度：{unwired.join('、')}。这些维度在计费依据里是「判定不了」，
              不是「用量 0」——来源系统没登记，本模块不用 0 冒充量过了。
            </p>
          )}
        </div>
      </section>

      {/* ---------------- 归集批次 ---------------- */}
      <section className="space-y-2">
        <h2 className="text-copy-15 font-medium">归集批次</h2>
        {collections.length === 0 ? (
          <p className="text-copy-13 text-muted-foreground">
            还没有归集记录。归集会把来源系统登记的「当前用量」落成周期桶；同一个周期重复归集是
            <b>覆盖</b>不是累加。
          </p>
        ) : (
          <ul className="space-y-1">
            {collections.map((row) => (
              <li key={row.collectionId} className="text-copy-12">
                <Badge tone={row.status === 'COMPLETED' ? 'info' : 'warning'}>
                  {COLLECTION_STATUS_LABELS[row.status] ?? row.status}
                </Badge>
                <span className="ml-2">
                  {row.tenantName}（{row.tenantCode}）· {row.periodType} · 量到 {row.measuredCount}/
                  {row.dimensionCount} 个维度
                  {row.sourceSystems ? ` · 来源 ${row.sourceSystems}` : ''}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
