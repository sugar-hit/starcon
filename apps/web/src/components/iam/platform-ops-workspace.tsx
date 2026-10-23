'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button } from '@starcon/ui';
import { RefreshCw } from 'lucide-react';
import type {
  PlatformOpsAlertPage,
  PlatformOpsAlertView,
  PlatformOpsCapabilities,
  PlatformOpsMetricPage,
  PlatformOpsMetricView,
  PlatformOpsOverviewView,
  PlatformOpsTenantHealthView,
  PlatformOpsThresholdView,
} from '../../../../../packages/api-contracts/src/index';
import { api, ApiError } from '@/lib/api';
import { useIdentity } from '../iam/identity-shell';
import {
  ALERT_ACTION_LABELS,
  ALERT_LABELS,
  ALERT_STATUS_LABELS,
  HEALTH_LEVEL_LABELS,
  METRIC_LABELS,
  SERVICE_LABELS,
  SEVERITY_LABELS,
  UNIT_LABELS,
  alertAction,
  describeAlert,
  describeHealth,
  describeMetric,
  formatByUnit,
  formatQuantity,
  formatRatio,
  formatTimestamp,
  healthLevel,
  overviewCards,
  sortAlerts,
  toThresholdDraft,
  validateThresholdInput,
  type ThresholdDraft,
} from './platform-ops-model';

/**
 * 17.7.2.2 平台运维视图。
 *
 * <p>🔴 **跨租户视图只看技术指标不看业务数据**：本页面上的一切都是**技术量**
 * （在线状态、错误率、任务积压、队列深度、容量占用、响应延迟、可用性）与
 * **容量 / 调用量口径的成本估算数量**。它不展示任何租户的客户 / 订单 / 金额单据 ——
 * 后端的指标表在**结构层面**就没有自由文本列能装下这些。
 *
 * <p>🔴 **「没数据」与「指标是 0」必须分开说**：后端在没有采样时返回 {@code null}，
 * 界面显示「—」；在线状态显示「状态未知」而不是「离线」。把「没采到」写成「积压 0」
 * 会把缺口说成事实。
 *
 * <p>四种状态各有各的文案：加载中 / 读不到（没有平台运维权限或整体失败）/
 * 部分读不出（其余内容照常显示，缺失的部分不能当作「没有」）/ 空态（平台还没收到采样）。
 */

/** 看板一行的分页尺寸。 */
const PAGE_SIZE = 20;

export function PlatformOpsWorkspace() {
  const { session } = useIdentity();
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  /** 局部读不到：概览读到了，但告警 / 指标 / 阈值读不到。 */
  const [partial, setPartial] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const [overview, setOverview] = useState<PlatformOpsOverviewView | null>(null);
  const [health, setHealth] = useState<PlatformOpsTenantHealthView[]>([]);
  const [alerts, setAlerts] = useState<PlatformOpsAlertView[]>([]);
  const [alertTotal, setAlertTotal] = useState(0);
  const [metrics, setMetrics] = useState<PlatformOpsMetricView[]>([]);
  const [metricTotal, setMetricTotal] = useState(0);
  const [metricOffset, setMetricOffset] = useState(0);
  const [thresholds, setThresholds] = useState<PlatformOpsThresholdView[]>([]);
  const [capabilities, setCapabilities] = useState<PlatformOpsCapabilities | null>(null);

  const [editing, setEditing] = useState<{ metricCode: string; draft: ThresholdDraft } | null>(null);

  const readable = session.permissions.includes('identity.platform_ops.read');
  const canAlert = session.permissions.includes('identity.platform_ops.alert');
  const canUpdate = session.permissions.includes('identity.platform_ops.update');

  const load = useCallback(async () => {
    setError('');
    setPartial('');
    if (!readable) {
      // 没有读权限就不请求：空数组会被渲染成「平台还没有收到采样」，那是骗人。
      setReady(true);
      return;
    }
    // 概览是整体：它读不到就是整体失败。告警 / 指标 / 阈值读不到是局部失败，
    // 两者的处置不同，文案也不能一样。
    const head = await Promise.allSettled([
      api<PlatformOpsOverviewView>('identity/platform-ops/overview'),
      api<PlatformOpsTenantHealthView[]>('identity/platform-ops/tenants'),
      api<PlatformOpsCapabilities>('identity/platform-ops/capabilities'),
    ]);
    if (head[0].status === 'rejected' && head[1].status === 'rejected') {
      setError(message(head[0].reason));
      setReady(true);
      return;
    }
    if (head[0].status === 'fulfilled') setOverview(head[0].value);
    if (head[1].status === 'fulfilled') setHealth(head[1].value);
    setCapabilities(head[2].status === 'fulfilled' ? head[2].value : null);

    const problems: string[] = [];
    if (head[0].status === 'rejected') problems.push(`健康看板汇总（${message(head[0].reason)}）`);
    if (head[1].status === 'rejected') problems.push(`逐租户健康数据（${message(head[1].reason)}）`);

    const rest = await Promise.allSettled([
      api<PlatformOpsAlertPage>('identity/platform-ops/alerts?limit=100&offset=0'),
      api<PlatformOpsMetricPage>(
        `identity/platform-ops/metrics?limit=${PAGE_SIZE}&offset=${metricOffset}`,
      ),
      api<PlatformOpsThresholdView[]>('identity/platform-ops/thresholds'),
    ]);
    if (rest[0].status === 'rejected') problems.push(`告警列表（${message(rest[0].reason)}）`);
    else {
      setAlerts(rest[0].value.items);
      setAlertTotal(rest[0].value.total);
    }
    if (rest[1].status === 'rejected') problems.push(`指标列表（${message(rest[1].reason)}）`);
    else {
      setMetrics(rest[1].value.items);
      setMetricTotal(rest[1].value.total);
    }
    if (rest[2].status === 'rejected') problems.push(`告警阈值（${message(rest[2].reason)}）`);
    else setThresholds(rest[2].value);

    setPartial(problems.join('；'));
    setReady(true);
  }, [readable, metricOffset]);

  useEffect(() => {
    void load();
  }, [load]);

  const cards = useMemo(() => (overview ? overviewCards(overview) : []), [overview]);
  const sortedAlerts = useMemo(() => sortAlerts(alerts), [alerts]);
  const withSamples = useMemo(() => health.filter((row) => row.lastSampleAt !== null), [health]);

  async function act(alert: PlatformOpsAlertView, action: 'acknowledge' | 'resolve') {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await api(`identity/platform-ops/alerts/${alert.id}/${action}`, 'POST');
      setNotice(action === 'acknowledge' ? '告警已确认' : '告警已闭环');
      await load();
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }

  async function saveThreshold() {
    if (!editing) return;
    const reason = validateThresholdInput(editing.draft);
    if (reason) {
      setError(reason);
      return;
    }
    setBusy(true);
    setError('');
    try {
      await api(`identity/platform-ops/thresholds/${editing.metricCode}`, 'PUT', {
        warnThreshold: Number(editing.draft.warnThreshold),
        criticalThreshold: Number(editing.draft.criticalThreshold),
        comparison: editing.draft.comparison,
        enabled: editing.draft.enabled,
      });
      setNotice(`指标 ${editing.metricCode} 的阈值已更新`);
      setEditing(null);
      await load();
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }

  if (!ready)
    return (
      <p role="status" className="text-muted-foreground p-6 text-sm">
        正在加载平台运维视图…
      </p>
    );

  if (!readable)
    return (
      <p className="rounded-lg border p-6 text-sm">
        当前角色没有平台运维视图权限。平台运维视图只对**已登记的平台运营角色**开放，
        请联系平台运营开通。
      </p>
    );

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-copy-13 text-muted-foreground">
          跨租户运维视图<b>只看技术指标</b>：在线状态、错误率、任务积压、队列深度、容量占用、
          响应延迟与可用性。<b>不展示任何租户的客户、订单或金额单据</b>；
          成本是容量 / 调用量口径的<b>估算数量</b>，不是金额。
        </p>
        <Button variant="ghost" size="sm" onClick={() => void load()}>
          <RefreshCw className="h-4 w-4" />
          刷新
        </Button>
      </div>

      {error && (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm"
        >
          平台运维视图读不到：{error}
          <Button variant="link" onClick={() => void load()}>
            重试
          </Button>
        </div>
      )}

      {partial && (
        <div
          role="status"
          className="border-warning/30 bg-warning/5 text-warning rounded-md border p-3 text-sm"
        >
          部分数据读不到：{partial}。其余内容照常显示，缺失的部分不能当作「没有」。
        </div>
      )}

      {notice && (
        <p role="status" className="text-success text-sm">
          {notice}
        </p>
      )}

      {/* ---------------- 概览 ---------------- */}
      {overview && (
        <section className="grid gap-3 md:grid-cols-3 xl:grid-cols-5">
          {cards.map((card) => (
            <div key={card.label} className="bg-card rounded-lg border p-4">
              <p className="text-copy-12 text-muted-foreground">{card.label}</p>
              <p className="text-copy-16 mt-1 font-medium">{card.value}</p>
              <p className="text-copy-12 text-muted-foreground mt-1">{card.hint}</p>
            </div>
          ))}
        </section>
      )}

      {/* ---------------- 逐租户技术健康 ---------------- */}
      <section className="space-y-3">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="text-copy-15 font-medium">逐租户技术健康</h2>
          <p className="text-copy-12 text-muted-foreground">
            每行一个租户，只含技术指标。「—」表示**没有采样**，不等于 0。
          </p>
        </div>
        {health.length === 0 ? (
          <div className="bg-card rounded-lg border p-8 text-center">
            <p className="text-copy-13 text-muted-foreground">平台还没有任何租户可看。</p>
          </div>
        ) : (
          <ul className="space-y-2">
            {health.map((row) => {
              const level = healthLevel(row);
              return (
                <li key={row.tenantId} className="bg-card rounded-md border p-3">
                  <div className="flex flex-wrap items-start justify-between gap-2">
                    <div className="min-w-0">
                      <p className="text-copy-14 flex flex-wrap items-center gap-2 font-medium">
                        {row.tenantName}
                        <Badge
                          tone={
                            level === 'CRITICAL'
                              ? 'danger'
                              : level === 'WARNING'
                                ? 'warning'
                                : level === 'OK'
                                  ? 'success'
                                  : 'neutral'
                          }
                        >
                          {HEALTH_LEVEL_LABELS[level]}
                        </Badge>
                        {row.tenantStatus !== 'ACTIVE' && (
                          <Badge tone="warning">{row.tenantStatus}</Badge>
                        )}
                      </p>
                      <p className="text-copy-12 text-muted-foreground mt-1">
                        <span className="font-mono">{row.tenantCode}</span>
                      </p>
                      <p className="text-copy-12 text-muted-foreground mt-1">{describeHealth(row)}</p>
                    </div>
                    <div className="text-right">
                      <p className="text-copy-12 text-muted-foreground">成本估算</p>
                      <p className="text-copy-14 font-medium">
                        {formatQuantity(row.costEstimate)}
                      </p>
                      <p className="text-copy-12 text-muted-foreground">
                        最近采样 {formatTimestamp(row.lastSampleAt)}
                      </p>
                    </div>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </section>

      {/* ---------------- 告警 ---------------- */}
      <section className="space-y-3">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="text-copy-15 font-medium">告警</h2>
          <p className="text-copy-12 text-muted-foreground">
            告警由「技术量 vs 阈值」比较产生，共 {alertTotal} 条。告警里没有任何业务描述 ——
            它只记录观测值与阈值。
          </p>
        </div>
        {sortedAlerts.length === 0 ? (
          <div className="bg-card rounded-lg border p-8 text-center">
            <p className="text-copy-13 text-muted-foreground">
              当前没有告警。平台还没收到任何越界的指标采样，或者都已闭环。
            </p>
          </div>
        ) : (
          <ul className="space-y-2">
            {sortedAlerts.map((row) => {
              const action = alertAction(row);
              return (
                <li
                  key={row.id}
                  className="bg-card flex flex-wrap items-center justify-between gap-3 rounded-md border p-3"
                >
                  <div className="min-w-0">
                    <p className="text-copy-14 flex flex-wrap items-center gap-2 font-medium">
                      {ALERT_LABELS[row.alertCode] ?? row.alertCode}
                      <Badge tone={row.severity === 'CRITICAL' ? 'danger' : 'warning'}>
                        {SEVERITY_LABELS[row.severity] ?? row.severity}
                      </Badge>
                      <Badge tone={row.status === 'RESOLVED' ? 'success' : 'info'}>
                        {ALERT_STATUS_LABELS[row.status] ?? row.status}
                      </Badge>
                    </p>
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      {describeAlert(row)} · 采集方 {SERVICE_LABELS[row.serviceCode] ?? row.serviceCode}
                    </p>
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      触发于 {formatTimestamp(row.raisedAt)}
                    </p>
                  </div>
                  {action && canAlert && (
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={busy}
                      onClick={() => void act(row, action)}
                    >
                      {ALERT_ACTION_LABELS[action]}
                    </Button>
                  )}
                </li>
              );
            })}
          </ul>
        )}
      </section>

      {/* ---------------- 指标采样 ---------------- */}
      <section className="space-y-3">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="text-copy-15 font-medium">技术指标采样</h2>
          <p className="text-copy-12 text-muted-foreground">
            共 {metricTotal} 条（真分页）。指标行只含数量与枚举，没有任何业务字段。
          </p>
        </div>
        {metrics.length === 0 ? (
          <div className="bg-card rounded-lg border p-8 text-center">
            <p className="text-copy-13 text-muted-foreground">
              平台还没有收到任何指标采样。指标由各服务经内部密钥路径上报，
              没有采样时这里显示空，而不是「指标都是 0」。
            </p>
          </div>
        ) : (
          <>
            <ul className="space-y-2">
              {metrics.map((row) => (
                <li key={row.id} className="bg-card rounded-md border p-3">
                  <p className="text-copy-14 flex flex-wrap items-center gap-2 font-medium">
                    {METRIC_LABELS[row.metricCode] ?? row.metricCode}
                    <Badge
                      tone={
                        row.severity === 'CRITICAL'
                          ? 'danger'
                          : row.severity === 'WARNING'
                            ? 'warning'
                            : 'success'
                      }
                    >
                      {SEVERITY_LABELS[row.severity] ?? row.severity}
                    </Badge>
                  </p>
                  <p className="text-copy-12 text-muted-foreground mt-1">{describeMetric(row)}</p>
                  <p className="text-copy-12 text-muted-foreground mt-1">
                    单位 {UNIT_LABELS[row.unit] ?? row.unit} · 采集窗口 {formatTimestamp(row.windowStart)}{' '}
                    ~ {formatTimestamp(row.windowEnd)} · 来源 {row.source}
                  </p>
                </li>
              ))}
            </ul>
            <div className="flex items-center gap-2">
              <Button
                size="sm"
                variant="ghost"
                disabled={metricOffset <= 0}
                onClick={() => setMetricOffset(Math.max(metricOffset - PAGE_SIZE, 0))}
              >
                上一页
              </Button>
              <span className="text-copy-12 text-muted-foreground">
                第 {Math.floor(metricOffset / PAGE_SIZE) + 1} / {Math.max(Math.ceil(metricTotal / PAGE_SIZE), 1)} 页
              </span>
              <Button
                size="sm"
                variant="ghost"
                disabled={metricOffset + PAGE_SIZE >= metricTotal}
                onClick={() => setMetricOffset(metricOffset + PAGE_SIZE)}
              >
                下一页
              </Button>
            </div>
          </>
        )}
      </section>

      {/* ---------------- 告警阈值 ---------------- */}
      <section className="space-y-3">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="text-copy-15 font-medium">告警阈值</h2>
          <p className="text-copy-12 text-muted-foreground">
            平台级配置，全局一份。阈值是**数量**，与业务金额无关。
          </p>
        </div>
        {thresholds.length === 0 ? (
          <div className="bg-card rounded-lg border p-8 text-center">
            <p className="text-copy-13 text-muted-foreground">还没有配置任何告警阈值。</p>
          </div>
        ) : (
          <ul className="space-y-2">
            {thresholds.map((row) => (
              <li
                key={row.metricCode}
                className="bg-card flex flex-wrap items-center justify-between gap-3 rounded-md border p-3"
              >
                <div>
                  <p className="text-copy-14 font-medium">
                    {METRIC_LABELS[row.metricCode] ?? row.metricCode}
                    <span className="text-muted-foreground ml-2 font-mono text-copy-12">
                      {row.metricCode}
                    </span>
                  </p>
                  <p className="text-copy-12 text-muted-foreground mt-1">
                    告警阈值 {formatQuantity(row.warnThreshold)} · 严重阈值{' '}
                    {formatQuantity(row.criticalThreshold)} · 方向 {row.comparison}
                    {row.enabled ? '' : ' · 已停用'}
                  </p>
                </div>
                {canUpdate && (
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() =>
                      setEditing({
                        metricCode: row.metricCode,
                        draft: toThresholdDraft({
                          warnThreshold: row.warnThreshold,
                          criticalThreshold: row.criticalThreshold,
                          comparison: row.comparison,
                          enabled: row.enabled,
                        }),
                      })
                    }
                  >
                    编辑
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* ---------------- 能力声明 ---------------- */}
      {capabilities && (
        <section className="bg-card space-y-2 rounded-lg border p-4">
          <p className="text-copy-14 font-medium">能力声明</p>
          <ul className="space-y-1">
            {capabilities.dimensions.map((dimension) => (
              <li key={dimension.dimension} className="text-copy-12 flex flex-wrap items-center gap-2">
                <Badge tone={dimension.available ? 'success' : 'warning'}>
                  {dimension.available ? '已交付' : '未交付'}
                </Badge>
                <span className="font-medium">{dimension.dimension}</span>
                <span className="text-muted-foreground">{dimension.description}</span>
                {!dimension.available && (
                  <span className="text-muted-foreground">
                    归属模块：{dimension.ownerModule}
                    {dimension.impact ? ` · ${dimension.impact}` : ''}
                  </span>
                )}
              </li>
            ))}
          </ul>
        </section>
      )}

      {/* ---------------- 阈值编辑 ---------------- */}
      {editing && (
        <section className="bg-card space-y-3 rounded-lg border p-4">
          <p className="text-copy-14 font-medium">编辑阈值：{editing.metricCode}</p>
          <div className="flex flex-wrap items-end gap-3">
            <label className="text-copy-12 block">
              <span className="text-muted-foreground">告警阈值</span>
              <input
                value={editing.draft.warnThreshold}
                onChange={(e) =>
                  setEditing({ ...editing, draft: { ...editing.draft, warnThreshold: e.target.value } })
                }
                className="border-border bg-card mt-1 h-8 w-28 rounded-md border px-2 outline-none"
              />
            </label>
            <label className="text-copy-12 block">
              <span className="text-muted-foreground">严重阈值</span>
              <input
                value={editing.draft.criticalThreshold}
                onChange={(e) =>
                  setEditing({
                    ...editing,
                    draft: { ...editing.draft, criticalThreshold: e.target.value },
                  })
                }
                className="border-border bg-card mt-1 h-8 w-28 rounded-md border px-2 outline-none"
              />
            </label>
            <label className="text-copy-12 block">
              <span className="text-muted-foreground">比较方向</span>
              <select
                value={editing.draft.comparison}
                onChange={(e) =>
                  setEditing({ ...editing, draft: { ...editing.draft, comparison: e.target.value } })
                }
                className="border-border bg-card mt-1 h-8 w-28 rounded-md border px-2 outline-none"
              >
                <option value="GT">GT（越大越糟）</option>
                <option value="LT">LT（越小越糟）</option>
              </select>
            </label>
            <Button size="sm" disabled={busy} onClick={() => void saveThreshold()}>
              保存
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setEditing(null)}>
              取消
            </Button>
          </div>
          <p className="text-copy-12 text-muted-foreground">
            {validateThresholdInput(editing.draft) || '阈值口径自洽，可以保存。'}
          </p>
        </section>
      )}
    </div>
  );
}

/**
 * 把异常翻译成可读文案。
 *
 * <p>后端 4xx 的响应体在 Spring 的默认错误处理下可能是空的，所以**状态码本身**
 * 也要参与翻译：403 = 没有平台运营权限，401 = 登录失效，其余才算「读不到」。
 */
function message(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) return '当前角色没有平台运营权限（403）';
    if (error.status === 401) return '登录已失效，请重新登录（401）';
    return error.message || `请求失败（${error.status}）`;
  }
  return error instanceof Error ? error.message : '未知错误';
}
