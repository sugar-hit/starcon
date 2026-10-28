'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, ExternalLink, SectionCard, StatCard } from '@starcon/ui';
import { Bell, RefreshCw, TriangleAlert } from 'lucide-react';
import { api } from '@/lib/api';
import {
  ALERT_TYPE_OPTIONS,
  SEVERITY_OPTIONS,
  SCOPE_TYPE_OPTIONS,
  baselineModeLabel,
  baselineText,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  deltaText,
  emptyText,
  grossProfitText,
  isActionable,
  notificationObservedText,
  notificationStatusLabel,
  notificationStatusTone,
  observedText,
  pageState,
  rangeText,
  ruleDescription,
  ruleThresholdText,
  severityLabel,
  severityTone,
  thresholdModeLabel,
  thresholdText,
  undecidedNote,
  undecidedScopeText,
  undecidedTitle,
} from './profit-alert-model';
import type {
  ProfitAlertCapabilities,
  ProfitAlertNotification,
  ProfitAlertNotificationPage,
  ProfitAlertPage,
  ProfitAlertRule,
} from './profit-alert-model';

/**
 * 13.4.3.1 利润预警工作区（卷 13「利润与经营分析」L3 13.4.3 利润预警下的第一格）。
 *
 * <p>蓝图输出：「预警清单 + 通知 + 处置入口。」
 *
 * <p><b>数据来自 reporting 服务（13 卷的唯一归属服务）的投影读模型</b>：
 *
 * <ul>
 *   <li>{@code GET reporting/profit-alert/alerts} —— 预警清单 + 判不出来清单 + 统计；</li>
 *   <li>{@code GET reporting/profit-alert/rules} —— 阈值规则（可配置项）；</li>
 *   <li>{@code GET reporting/profit-alert/notifications} —— 通知投递记录台账；</li>
 *   <li>{@code POST reporting/profit-alert/notifications} —— 把命中的预警登记成通知（幂等）；</li>
 *   <li>{@code GET reporting/profit-alert/capabilities} —— 能力声明（哪些上游真接通、哪些确实拿不到）。</li>
 * </ul>
 *
 * <p><b>🔴 页面必须做到的五件事</b>：
 *
 * <ol>
 *   <li><b>数据更新时间常驻</b>（卷 13 卷首加粗句「数据延迟要显示」）：顶部横幅任何时候都显示
 *       「数据截至 X」，加载中 / 出错时也显示（未投影时显示「尚未投影任何利润事实」）。</li>
 *   <li><b>「判不出来」与「没异常」分开说</b>：判不了的条目单列一区，**写明原因**，
 *       绝不被渲染成「正常」—— 判不了被当成「没异常」比误报更危险。</li>
 *   <li><b>「算不出来」≠「等于 0」</b>：{@code null} 的观测值 / 基线 / 差额显示「—」，
 *       **绝不用 0 顶替**。</li>
 *   <li><b>阈值带单位</b>：百分点 / 百分数 / 倍数三种含义完全不同，单位由服务端下发。</li>
 *   <li><b>处置入口指向真实页面</b>：链接由服务端给出，前端不自己编地址。</li>
 * </ol>
 */
export function AnalyticsProfitAlertWorkspace() {
  const today = new Date().toISOString().slice(0, 10);
  const initialRange = defaultRange(today);

  const [from, setFrom] = useState(initialRange.from);
  const [to, setTo] = useState(initialRange.to);
  const [alertType, setAlertType] = useState('');
  const [severity, setSeverity] = useState('');
  const [scopeType, setScopeType] = useState('');

  const [data, setData] = useState<ProfitAlertPage | null>(null);
  const [capabilities, setCapabilities] = useState<ProfitAlertCapabilities | null>(null);
  const [rules, setRules] = useState<ProfitAlertRule[]>([]);
  const [notifications, setNotifications] = useState<ProfitAlertNotificationPage | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  const query = useMemo(() => {
    const params = new URLSearchParams({ from, to });
    if (alertType) params.set('alertType', alertType);
    if (severity) params.set('severity', severity);
    if (scopeType) params.set('scopeType', scopeType);
    return params.toString();
  }, [from, to, alertType, severity, scopeType]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [alerts, caps, thresholdRules, notificationPage] = await Promise.all([
        api<ProfitAlertPage>(`reporting/profit-alert/alerts?${query}`),
        api<ProfitAlertCapabilities>('reporting/profit-alert/capabilities'),
        api<ProfitAlertRule[]>('reporting/profit-alert/rules'),
        api<ProfitAlertNotificationPage>('reporting/profit-alert/notifications?from=' + from + '&to=' + to),
      ]);
      setData(alerts);
      setCapabilities(caps);
      setRules(thresholdRules);
      setNotifications(notificationPage);
    } catch (e) {
      setData(null);
      setError(e instanceof Error ? e.message : '读取失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [query, from, to]);

  useEffect(() => {
    void load();
  }, [load]);

  const state = pageState(data, error);

  // ---------------------------------------------------------------- 动作

  const run = useCallback(
    async (work: () => Promise<string>) => {
      setActionError('');
      setNotice('');
      try {
        setNotice(await work());
      } catch (e) {
        setActionError(e instanceof Error ? e.message : '操作失败，请重试');
      }
      await load();
    },
    [load],
  );

  const registerNotifications = useCallback(
    () =>
      run(async () => {
        const result = await api<{ matched: number; created: number; skipped: number; note: string }>(
          'reporting/profit-alert/notifications?from=' + from + '&to=' + to,
          'POST',
        );
        return `命中 ${result.matched} 条：新登记 ${result.created} 条，已登记过 ${result.skipped} 条。`;
      }),
    [run, from, to],
  );

  const updateNotification = useCallback(
    (id: string, status: 'ACKNOWLEDGED' | 'DISMISSED') =>
      run(async () => {
        await api(`reporting/profit-alert/notifications/${id}`, 'PUT', { status });
        return status === 'ACKNOWLEDGED' ? '已标记为「已知悉」。' : '已标记为「误报，忽略」。';
      }),
    [run],
  );

  const updateRule = useCallback(
    (rule: ProfitAlertRule, patch: Partial<ProfitAlertRule>) =>
      run(async () => {
        await api(`reporting/profit-alert/rules/${rule.alertType}`, 'PUT', {
          thresholdMode: patch.thresholdMode ?? rule.thresholdMode,
          thresholdValue: patch.thresholdValue ?? rule.thresholdValue,
          minHistoryPeriods: patch.minHistoryPeriods ?? rule.minHistoryPeriods,
          severity: patch.severity ?? rule.severity,
          enabled: patch.enabled ?? rule.enabled,
        });
        return `「${rule.alertTypeLabel}」的阈值已更新。`;
      }),
    [run],
  );

  return (
    <div className="space-y-4">
      {/* ── 数据更新时间（常驻）────────────────────────────────────────── */}
      <div className="border-border bg-muted/40 flex flex-wrap items-center justify-between gap-3 rounded-lg border px-4 py-3">
        <div className="text-copy-13 text-foreground">
          <span className="font-medium">{dataUpdatedText(data)}</span>
          <span className="text-muted-foreground ml-2">{dataUpdatedNote(data)}</span>
        </div>
        <div className="text-copy-13 text-muted-foreground flex items-center gap-3">
          <span>{rangeText(from, to)}</span>
          <Button variant="outline" size="sm" disabled={loading} onClick={() => void load()}>
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            刷新
          </Button>
        </div>
      </div>

      {/* ── 口径说明 ─────────────────────────────────────────────────── */}
      {data && (
        <div className="border-border bg-muted/40 text-copy-13 text-muted-foreground rounded-lg border px-4 py-3">
          <div>{data.caliberNote}</div>
          {data.warnings.length > 0 && (
            <ul className="mt-1 space-y-0.5">
              {data.warnings.map((warning) => (
                <li key={warning}>· {warning}</li>
              ))}
            </ul>
          )}
        </div>
      )}

      {/* ── 时间范围与筛选 ─────────────────────────────────────────────── */}
      <SectionCard
        title="时间范围与筛选"
        description="时间范围决定期间窗口与基线窗口（前 N 期同长度窗口）；筛选只影响取数，不改变口径。"
      >
        <div className="flex flex-wrap items-end gap-4">
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            起始日期
            <input
              type="date"
              value={from}
              max={to}
              onChange={(e) => setFrom(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            结束日期
            <input
              type="date"
              value={to}
              min={from}
              onChange={(e) => setTo(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            预警类型
            <select
              value={alertType}
              onChange={(e) => setAlertType(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              <option value="">全部</option>
              {ALERT_TYPE_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            严重度
            <select
              value={severity}
              onChange={(e) => setSeverity(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              <option value="">全部</option>
              {SEVERITY_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            归属维度
            <select
              value={scopeType}
              onChange={(e) => setScopeType(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              <option value="">全部</option>
              {SCOPE_TYPE_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
        </div>
      </SectionCard>

      {/* ── 动作反馈 ───────────────────────────────────────────────────── */}
      {(actionError || notice) && (
        <div
          className={
            actionError
              ? 'border-destructive/40 bg-destructive/10 text-copy-13 text-destructive rounded-lg border px-4 py-2'
              : 'border-border bg-muted/40 text-copy-13 text-foreground rounded-lg border px-4 py-2'
          }
        >
          {actionError || notice}
        </div>
      )}

      {state === 'error' && (
        <SectionCard>
          <EmptyState
            icon={<TriangleAlert className="h-6 w-6" />}
            title="读取失败"
            description={error}
            action={
              <Button variant="outline" size="sm" onClick={() => void load()}>
                <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
                重试
              </Button>
            }
          />
        </SectionCard>
      )}

      {state === 'loading' && (
        <SectionCard>
          <EmptyState title="正在读取预警…" description="读取期间页面上的「数据截至」仍然有效。" />
        </SectionCard>
      )}

      {state === 'empty' && (
        <SectionCard>
          <EmptyState title="没有预警" description={emptyText(data)} />
        </SectionCard>
      )}

      {data && (
        <>
          {/* ── 统计 ─────────────────────────────────────────────────── */}
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard
              label="命中预警"
              value={data.summary.total}
              unit="条"
              polarity="negative"
              icon={<TriangleAlert className="h-4 w-4" />}
            />
            <StatCard
              label="严重"
              value={data.summary.criticalCount}
              unit="条"
              polarity="negative"
            />
            <StatCard label="警告" value={data.summary.warningCount} unit="条" polarity="negative" />
            <StatCard
              label="判不出来"
              value={data.summary.undecidedCount}
              unit="条"
              polarity="neutral"
              comparisonLabel="不是「没异常」"
            />
          </div>

          {/* ── 预警清单 ─────────────────────────────────────────────── */}
          <SectionCard
            title="预警清单"
            description="一行 = 一条命中的预警。阈值来自租户自己的规则表（不是写死的百分比）；处置入口指向真实页面。"
          >
            {data.alerts.length === 0 ? (
              <p className="text-copy-13 text-muted-foreground">
                这个窗口里没有命中任何阈值（也可能是**判不了** —— 判不了不等于正常，见下方清单）。
              </p>
            ) : (
              <div className="space-y-3">
                {data.alerts.map((alert) => (
                  <div
                    key={alert.fingerprint}
                    className="border-border rounded-lg border px-4 py-3"
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-copy-14 text-foreground font-medium">
                        {alert.alertTypeLabel}
                      </span>
                      <Badge tone={severityTone(alert.severity)}>
                        {severityLabel(alert.severity, alert.severityLabel)}
                      </Badge>
                      <span className="text-copy-13 text-muted-foreground">
                        {alert.scopeLabel}
                      </span>
                      <span className="text-copy-13 text-muted-foreground">{alert.currency}</span>
                    </div>
                    <dl className="text-copy-13 mt-3 grid grid-cols-1 gap-x-6 gap-y-1 sm:grid-cols-2 lg:grid-cols-4">
                      <div>
                        <dt className="text-muted-foreground">观测值</dt>
                        <dd className="text-foreground tabular-nums">{observedText(alert)}</dd>
                      </div>
                      <div>
                        <dt className="text-muted-foreground">阈值</dt>
                        <dd className="text-foreground tabular-nums">{thresholdText(alert)}</dd>
                      </div>
                      <div>
                        <dt className="text-muted-foreground">基线</dt>
                        <dd className="text-foreground tabular-nums">{baselineText(alert)}</dd>
                      </div>
                      <div>
                        <dt className="text-muted-foreground">与基线之差</dt>
                        <dd className="text-foreground tabular-nums">{deltaText(alert)}</dd>
                      </div>
                      <div>
                        <dt className="text-muted-foreground">收入</dt>
                        <dd className="text-foreground tabular-nums">
                          {alert.revenue === null ? '—' : alert.revenue.toFixed(2) + ' ' + alert.currency}
                        </dd>
                      </div>
                      <div>
                        <dt className="text-muted-foreground">毛利</dt>
                        <dd className="text-foreground tabular-nums">
                          {grossProfitText(alert.grossProfit, alert.currency)}
                        </dd>
                      </div>
                      <div>
                        <dt className="text-muted-foreground">阈值口径</dt>
                        <dd className="text-foreground">
                          {thresholdModeLabel(alert.thresholdMode)} · {baselineModeLabel(alert.baselineMode)}
                        </dd>
                      </div>
                    </dl>
                    <div className="text-copy-13 text-foreground mt-2">{alert.reason}</div>
                    <div className="text-copy-13 text-muted-foreground mt-1">{alert.note}</div>
                    {alert.actions.length > 0 && (
                      <div className="mt-3 flex flex-wrap gap-3">
                        {alert.actions.map((action) => (
                          <ExternalLink
                            key={action.href + action.label}
                            href={action.href}
                            className="text-copy-13 text-primary hover:underline"
                            hint={action.description}
                          >
                            {action.label}
                          </ExternalLink>
                        ))}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          {/* ── 判不出来（**不是「没异常」**）──────────────────────────── */}
          {data.undecided.length > 0 && (
            <SectionCard
              title={undecidedTitle(data.summary.undecidedCount)}
              description={undecidedNote(data)}
            >
              <div className="space-y-2">
                {data.undecided.map((item) => (
                  <div
                    key={item.alertType + '|' + item.scopeKey + '|' + item.currency}
                    className="border-border rounded-lg border px-4 py-2"
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone="neutral">判不出来</Badge>
                      <span className="text-copy-13 text-foreground font-medium">
                        {item.alertTypeLabel}
                      </span>
                      <span className="text-copy-13 text-muted-foreground">
                        {undecidedScopeText(item)}
                      </span>
                      <span className="text-copy-13 text-muted-foreground">{item.currency}</span>
                    </div>
                    <div className="text-copy-13 text-muted-foreground mt-1">{item.reason}</div>
                  </div>
                ))}
              </div>
            </SectionCard>
          )}

          {/* ── 阈值规则（可配置项）────────────────────────────────────── */}
          <SectionCard
            title="预警阈值"
            description="阈值落在租户自己的规则表里。单位由 (预警类型 × 阈值口径) 派生：百分点 / 百分数 / 倍数三种含义不同。"
          >
            {rules.length === 0 ? (
              <p className="text-copy-13 text-muted-foreground">还没有读取到阈值规则。</p>
            ) : (
              <RuleTable rules={rules} onSave={updateRule} />
            )}
          </SectionCard>

          {/* ── 通知 ───────────────────────────────────────────────────── */}
          <SectionCard
            title="通知"
            description="通知是**可观测的投递记录**（落库 + 查询端点）：不硬编码 SMTP / webhook 地址与凭据。"
          >
            <div className="mb-3 flex flex-wrap items-center gap-3">
              <Button
                variant="outline"
                size="sm"
                disabled={loading}
                onClick={() => void registerNotifications()}
              >
                <Bell className="mr-1.5 h-3.5 w-3.5" />
                把命中的预警登记成通知
              </Button>
              {notifications && (
                <span className="text-copy-13 text-muted-foreground">
                  共 {notifications.total} 条 · 待处理 {notifications.pendingCount} 条
                </span>
              )}
            </div>
            {!notifications || notifications.notifications.length === 0 ? (
              <p className="text-copy-13 text-muted-foreground">
                还没有通知记录。登记一次之后，同一个窗口里的同一条预警**不会**重复通知。
              </p>
            ) : (
              <div className="space-y-2">
                {notifications.notifications.map((item: ProfitAlertNotification) => (
                  <div
                    key={item.id}
                    className="border-border rounded-lg border px-4 py-2"
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={notificationStatusTone(item.status)}>
                        {notificationStatusLabel(item.status)}
                      </Badge>
                      <span className="text-copy-13 text-foreground font-medium">{item.title}</span>
                      <span className="text-copy-13 text-muted-foreground">{item.currency}</span>
                      <span className="text-copy-13 text-muted-foreground">
                        {item.windowStart} ~ {item.windowEnd}
                      </span>
                    </div>
                    <div className="text-copy-13 text-muted-foreground mt-1">{item.detail}</div>
                    <div className="text-copy-13 text-muted-foreground mt-1">
                      观测值 {notificationObservedText(item)} · 阈值 {item.thresholdValue} · 登记于{' '}
                      {item.createdAt}
                      {item.acknowledgedAt ? ` · 处理于 ${item.acknowledgedAt}` : ''}
                    </div>
                    {isActionable(item.status) && (
                      <div className="mt-2 flex gap-2">
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => void updateNotification(item.id, 'ACKNOWLEDGED')}
                        >
                          已知悉
                        </Button>
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => void updateNotification(item.id, 'DISMISSED')}
                        >
                          误报，忽略
                        </Button>
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          {/* ── 能力声明 ───────────────────────────────────────────────── */}
          {capabilities && (
            <SectionCard title="能力声明" description="哪些上游真接通、哪些确实拿不到、归谁。">
              <div className="space-y-3">
                <div>
                  <div className="text-copy-13 text-muted-foreground">上游（真的读过）</div>
                  <ul className="text-copy-13 mt-1 space-y-0.5">
                    {capabilities.upstreamModules.map((module) => (
                      <li key={module}>· {module}</li>
                    ))}
                  </ul>
                </div>
                <div>
                  <div className="text-copy-13 text-muted-foreground">
                    确实拿不到的部分（{capabilities.notDelivered.length} 条）
                  </div>
                  <ul className="text-copy-13 mt-1 space-y-0.5">
                    {capabilities.notDelivered.map((item) => (
                      <li key={item}>· {item}</li>
                    ))}
                  </ul>
                </div>
                <div className="text-copy-13 text-muted-foreground">{capabilities.note}</div>
              </div>
            </SectionCard>
          )}
        </>
      )}
    </div>
  );
}

/**
 * 阈值规则表（**可编辑**：改完点保存就走 PUT）。
 */
function RuleTable({
  rules,
  onSave,
}: {
  rules: ProfitAlertRule[];
  onSave: (rule: ProfitAlertRule, patch: Partial<ProfitAlertRule>) => Promise<void>;
}) {
  const [drafts, setDrafts] = useState<Record<string, string>>({});

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <thead className="text-copy-13 text-muted-foreground">
          <tr className="border-border border-b">
            <th className="py-2 pr-3 font-medium">预警类型</th>
            <th className="py-2 pr-3 font-medium">阈值口径</th>
            <th className="py-2 pr-3 text-right font-medium">阈值</th>
            <th className="py-2 pr-3 text-right font-medium">最少历史期数</th>
            <th className="py-2 pr-3 font-medium">严重度</th>
            <th className="py-2 pr-3 font-medium">状态</th>
            <th className="py-2 pr-3 font-medium">操作</th>
          </tr>
        </thead>
        <tbody>
          {rules.map((rule) => {
            const key = rule.alertType;
            const draft = drafts[key] ?? String(rule.thresholdValue);
            return (
              <tr key={rule.alertType} className="border-border border-b">
                <td className="py-2 pr-3">
                  <div>{rule.alertTypeLabel}</div>
                  <div className="text-copy-13 text-muted-foreground">{ruleDescription(rule)}</div>
                </td>
                <td className="py-2 pr-3">{thresholdModeLabel(rule.thresholdMode)}</td>
                <td className="py-2 pr-3 text-right tabular-nums">
                  <div>{ruleThresholdText(rule)}</div>
                  <input
                    type="number"
                    step="0.000001"
                    value={draft}
                    onChange={(e) => setDrafts({ ...drafts, [key]: e.target.value })}
                    className="border-border bg-background mt-1 w-28 rounded-md border px-2 py-1 text-sm"
                  />
                </td>
                <td className="py-2 pr-3 text-right tabular-nums">{rule.minHistoryPeriods}</td>
                <td className="py-2 pr-3">
                  <Badge tone={severityTone(rule.severity)}>
                    {severityLabel(rule.severity, rule.severityLabel)}
                  </Badge>
                </td>
                <td className="py-2 pr-3">
                  <Badge tone={rule.enabled ? 'success' : 'neutral'}>
                    {rule.enabled ? '启用' : '停用'}
                  </Badge>
                </td>
                <td className="py-2 pr-3">
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => {
                      const parsed = Number(draft);
                      if (Number.isNaN(parsed)) return;
                      void onSave(rule, { thresholdValue: parsed });
                    }}
                  >
                    保存
                  </Button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
