'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Badge, Button } from '@starcon/ui';
import { RefreshCw } from 'lucide-react';
import { api, ApiError } from '@/lib/api';
import {
  ALERTS_PATH,
  BASE,
  CAPABILITIES_PATH,
  EMPTY_FILTERS,
  FAILURE_CLASS_HINTS,
  FAILURE_CLASS_LABELS,
  FAILURE_CLASS_TONES,
  OPTIONS_PATH,
  SEVERITY_LABELS,
  SEVERITY_TONES,
  SUMMARY_PATH,
  TASK_STATE_LABELS,
  TASK_STATE_TONES,
  TASKS_PATH,
  alertHref,
  buildQuery,
  formatDuration,
  formatFailureClassCount,
  formatFailureReasonCount,
  formatMinutes,
  formatThresholds,
  formatTimestamp,
  rulesBySeverity,
  sortAlerts,
  sortBySeverity,
  sortSources,
  summarize,
  taskTitle,
  type ServiceTaskStatusAlertList,
  type ServiceTaskStatusCapabilities,
  type ServiceTaskStatusOptions,
  type ServiceTaskStatusSummary,
  type ServiceTaskStatusTaskCard,
  type ServiceTaskStatusTaskPage,
  type ServiceTaskStatusFilters,
} from './service-task-status-model';

/**
 * 1.5.2.1 服务与任务状态 —— `/system-health/tasks` 工作区。
 *
 * <p>🔴 <b>本页面不写任何业务数据</b>。卷 01 的边界写死：「工作台只读 + 跳转；
 * 一切状态变更回到归属系统完成」。所以「重跑同步 / 取消任务 / 重新授权」一律表现为
 * **跳转到归属系统的页面**，而不是在这里按一个按钮就改掉任务状态 ——
 * 那会造出第二份真相（页面的处置结论与归属系统的结论对不上）。
 *
 * <p>页面分六块：
 *
 * <ul>
 *   <li><b>总览</b> —— 任务总数、执行中 / 失败 / 停滞计数、失败记录按
 *       「业务 / 配置问题」与「系统 / 外部问题」分开计数。</li>
 *   <li><b>停滞提示</b> 与 <b>失败提示</b> —— <b>两个独立列表</b>：
 *       「没在跑」与「跑挂了」不是一回事。</li>
 *   <li><b>任务状态列表</b> —— 逐条带原始状态、规范化状态、耗时、判定依据与处置落点。</li>
 *   <li><b>逐来源统计</b> —— 每个来源的总条数 / 执行中 / 失败 / 停滞条数。</li>
 *   <li><b>未接通的上游</b> —— 如实写出哪些来源**没有**接上、归谁。</li>
 *   <li><b>能力声明</b> —— 规则表、阈值、逐张表的来源清单、失败分类口径。</li>
 * </ul>
 *
 * <p>🔴 「加载中 / 读不到 / 出错可重试 / 真的没有任务」是**四件不同的事**：
 * 后端 503 或 403 时页面显式显示「读不到」并给重试按钮，
 * 绝不渲染成一张空的绿色卡片（那等于把「没查到」说成「一切正常」）。
 */

/** 每页行数。 */
const PAGE_SIZE = 50;

/** 后端返回的错误 → 人话。 */
function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) return '当前账号没有查看服务与任务状态的权限。';
    if (error.status === 503) return '服务与任务状态数据**读不到**（上游事实表暂时无法访问）。';
    if (error.status === 401) return '登录已失效，请重新登录。';
    return error.message;
  }
  if (error instanceof Error) return error.message;
  return '请求失败，请重试';
}

export function ServiceTaskStatusWorkspace() {
  const [ready, setReady] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notices, setNotices] = useState<string[]>([]);

  const [capabilities, setCapabilities] = useState<ServiceTaskStatusCapabilities | null>(null);
  const [options, setOptions] = useState<ServiceTaskStatusOptions | null>(null);
  const [summary, setSummary] = useState<ServiceTaskStatusSummary | null>(null);
  const [alerts, setAlerts] = useState<ServiceTaskStatusAlertList | null>(null);
  const [cards, setCards] = useState<ServiceTaskStatusTaskCard[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);

  const [filters, setFilters] = useState<ServiceTaskStatusFilters>(EMPTY_FILTERS);

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    const notes: string[] = [];
    try {
      const query = buildQuery(filters, page, PAGE_SIZE);
      const [caps, opts, sum, alertList, list] = await Promise.all([
        api<ServiceTaskStatusCapabilities>(CAPABILITIES_PATH),
        api<ServiceTaskStatusOptions>(OPTIONS_PATH),
        api<ServiceTaskStatusSummary>(SUMMARY_PATH),
        api<ServiceTaskStatusAlertList>(ALERTS_PATH),
        api<ServiceTaskStatusTaskPage>(`${TASKS_PATH}?${query}`),
      ]);

      setCapabilities(caps);
      setOptions(opts);
      setSummary(sum);
      setAlerts(alertList);
      setCards(list.items ?? []);
      setTotal(list.total ?? 0);

      if (list.total > list.items.length) {
        notes.push(
          `当前页只显示 ${list.items.length} 条，共 ${list.total} 条任务（翻页可看其余）。`,
        );
      }
      if (alertList?.truncated) {
        notes.push('提示条数超出了单次装载上限 —— 列表**不完整**，请按来源或状态筛选后再看。');
      }
      setNotices(notes);
    } catch (e) {
      // 🔴 读不到就是读不到：不清空已有结论、不渲染绿色空态，直接把原因摆出来。
      setError(describeError(e));
    } finally {
      setBusy(false);
      setReady(true);
    }
  }, [filters, page]);

  useEffect(() => {
    void load();
  }, [load]);

  const visible = useMemo(() => sortBySeverity(cards), [cards]);
  const counts = useMemo(() => summarize(cards), [cards]);
  const pageCount = Math.max(1, Math.ceil(Math.max(total, cards.length) / PAGE_SIZE));
  const sourceStats = useMemo(() => sortSources(summary?.sources ?? []), [summary]);

  if (!ready)
    return (
      <p role="status" className="text-muted-foreground p-6 text-sm">
        正在加载服务与任务状态…
      </p>
    );

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-copy-13 text-muted-foreground">
          这一页回答的是<b>「是系统问题还是数据问题」</b>：<b>停滞</b>是「没在跑」，<b>失败</b>
          是「跑挂了」—— 两类提示分开列。 失败还会进一步分成<b>业务 / 配置问题</b>与
          <b>系统 / 外部问题</b>。 本页只读 —— <b>处置动作一律回到归属系统完成</b>。
        </p>
        <Button variant="ghost" size="sm" onClick={() => void load()} disabled={busy}>
          <RefreshCw className="h-4 w-4" />
          刷新
        </Button>
      </div>

      {error && (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm"
        >
          <b>读不到</b>：{error}
          <div className="text-copy-12 text-muted-foreground mt-1">
            这一页**没有任何结论** —— 「没读到」不等于「一切正常」，请重试或联系管理员。
          </div>
          <Button variant="link" onClick={() => void load()}>
            重试
          </Button>
        </div>
      )}
      {notices.map((notice) => (
        <p
          key={notice}
          role="status"
          className="border-warning/40 bg-warning/5 text-copy-12 rounded-md border p-3"
        >
          {notice}
        </p>
      ))}

      {/* ---------------- 总览 ---------------- */}
      {summary && (
        <section className="space-y-2">
          <h2 className="text-copy-15 font-medium">总览</h2>
          <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
            <div className="rounded-md border p-3">
              <p className="text-copy-12 text-muted-foreground">任务总数</p>
              <p className="text-copy-15 font-medium">{summary.totalTasks} 条</p>
            </div>
            <div className="rounded-md border p-3">
              <p className="text-copy-12 text-muted-foreground">执行中</p>
              <p className="text-copy-15 font-medium">{summary.runningCount} 条</p>
            </div>
            <div className="rounded-md border p-3">
              <p className="text-copy-12 text-muted-foreground">已判为停滞</p>
              <p className="text-copy-15 text-destructive font-medium">{summary.stalledCount} 条</p>
            </div>
            <div className="rounded-md border p-3">
              <p className="text-copy-12 text-muted-foreground">停滞判不了</p>
              <p className="text-copy-15 text-warning-foreground font-medium">
                {summary.stallUndeterminableCount} 条
              </p>
            </div>
          </div>
          <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
            <div className="rounded-md border p-3">
              <p className="text-copy-12 text-muted-foreground">失败任务</p>
              <p className="text-copy-15 text-destructive font-medium">{summary.failedCount} 条</p>
            </div>
            <div className="rounded-md border p-3">
              <p className="text-copy-12 text-muted-foreground">失败记录（累计）</p>
              <p className="text-copy-13 font-medium">{summary.failedRecordTotal} 条</p>
            </div>
            <div className="rounded-md border p-3">
              <p className="text-copy-12 text-muted-foreground">失败记录 · 业务 / 配置问题</p>
              <p className="text-copy-13 text-warning-foreground font-medium">
                {summary.businessFailureTotal} 条
              </p>
            </div>
            <div className="rounded-md border p-3">
              <p className="text-copy-12 text-muted-foreground">失败记录 · 系统 / 外部问题</p>
              <p className="text-copy-13 text-destructive font-medium">
                {summary.systemFailureTotal} 条
              </p>
            </div>
          </div>
          {summary.failureByClass.length > 0 && (
            <ul className="space-y-1">
              {summary.failureByClass.map((entry) => (
                <li key={entry.failureClass} className="text-copy-12 text-muted-foreground">
                  <Badge tone={FAILURE_CLASS_TONES[entry.failureClass] ?? 'neutral'}>
                    {FAILURE_CLASS_LABELS[entry.failureClass] ?? entry.failureClass}
                  </Badge>
                  <span className="ml-2">{formatFailureClassCount(entry)}</span>
                  <ul className="ml-4 list-disc">
                    {entry.byReason.map((reason) => (
                      <li key={reason.reason}>{formatFailureReasonCount(reason)}</li>
                    ))}
                  </ul>
                </li>
              ))}
            </ul>
          )}
          <p className="text-copy-12 text-muted-foreground">
            数据截至 {formatTimestamp(summary.dataAsOf)}。
          </p>
        </section>
      )}

      {/* ---------------- 提示：停滞 / 失败 两个独立列表 ---------------- */}
      {alerts && (
        <section className="space-y-3">
          <h2 className="text-copy-15 font-medium">停滞提示（「没在跑」）</h2>
          {alerts.stalled.length === 0 ? (
            <div className="bg-card rounded-lg border p-6 text-center">
              <p className="text-copy-13 text-muted-foreground">
                当前范围内**没有判为停滞的任务**。这不是「一切正常」的保证 ——
                任务范围只覆盖「还在执行的」与「最近一段时间发生的」。
              </p>
            </div>
          ) : (
            <ul className="space-y-2">
              {sortAlerts(alerts.stalled).map((alert) => {
                const href = alertHref(alert);
                return (
                  <li key={`${alert.kind}-${alert.taskId}`} className="rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={SEVERITY_TONES[alert.severity] ?? 'neutral'}>
                        {SEVERITY_LABELS[alert.severity] ?? alert.severity}
                      </Badge>
                      <span className="text-copy-13 font-medium">{alert.title}</span>
                      <span className="text-copy-12 text-muted-foreground">
                        {alert.sourceLabel} · 归属 {alert.ownerModule}
                      </span>
                    </div>
                    <p className="text-copy-12 text-muted-foreground mt-1">{alert.detail}</p>
                    {href ? (
                      <Link className="text-copy-12 text-primary underline" href={href}>
                        去处置（{alert.targetHint}）
                      </Link>
                    ) : (
                      <p className="text-copy-12 text-muted-foreground">{alert.targetNote}</p>
                    )}
                  </li>
                );
              })}
            </ul>
          )}

          <h2 className="text-copy-15 font-medium">失败提示（「跑挂了」）</h2>
          {alerts.failed.length === 0 ? (
            <div className="bg-card rounded-lg border p-6 text-center">
              <p className="text-copy-13 text-muted-foreground">当前范围内**没有失败的任务**。</p>
            </div>
          ) : (
            <ul className="space-y-2">
              {sortAlerts(alerts.failed).map((alert) => {
                const href = alertHref(alert);
                return (
                  <li key={`${alert.kind}-${alert.taskId}`} className="rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={SEVERITY_TONES[alert.severity] ?? 'neutral'}>
                        {SEVERITY_LABELS[alert.severity] ?? alert.severity}
                      </Badge>
                      {alert.failureClass && (
                        <Badge tone={FAILURE_CLASS_TONES[alert.failureClass] ?? 'neutral'}>
                          {FAILURE_CLASS_LABELS[alert.failureClass] ?? alert.failureClass}
                        </Badge>
                      )}
                      <span className="text-copy-13 font-medium">{alert.title}</span>
                    </div>
                    <p className="text-copy-12 text-muted-foreground mt-1">{alert.detail}</p>
                    {alert.failureClass && (
                      <p className="text-copy-12 text-muted-foreground">
                        {FAILURE_CLASS_HINTS[alert.failureClass] ?? ''}
                      </p>
                    )}
                    {href ? (
                      <Link className="text-copy-12 text-primary underline" href={href}>
                        去处置（{alert.targetHint}）
                      </Link>
                    ) : (
                      <p className="text-copy-12 text-muted-foreground">{alert.targetNote}</p>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
        </section>
      )}

      {/* ---------------- 筛选 ---------------- */}
      <section className="flex flex-wrap items-end gap-3">
        <label className="text-copy-12 flex flex-col gap-1">
          <span className="text-muted-foreground">任务来源</span>
          <select
            className="text-copy-12 rounded border px-2 py-1"
            value={filters.sourceKey}
            onChange={(e) => {
              setFilters((current) => ({ ...current, sourceKey: e.target.value }));
              setPage(0);
            }}
          >
            <option value="">全部来源</option>
            {(options?.sources ?? []).map((source) => (
              <option key={source} value={source}>
                {source}
              </option>
            ))}
          </select>
        </label>
        <label className="text-copy-12 flex flex-col gap-1">
          <span className="text-muted-foreground">状态</span>
          <select
            className="text-copy-12 rounded border px-2 py-1"
            value={filters.state}
            onChange={(e) => {
              setFilters((current) => ({ ...current, state: e.target.value }));
              setPage(0);
            }}
          >
            <option value="">全部状态</option>
            {(options?.states ?? []).map((state) => (
              <option key={state} value={state}>
                {TASK_STATE_LABELS[state] ?? state}
              </option>
            ))}
          </select>
        </label>
        <label className="text-copy-12 flex flex-col gap-1">
          <span className="text-muted-foreground">触发来源</span>
          <select
            className="text-copy-12 rounded border px-2 py-1"
            value={filters.triggerSource}
            onChange={(e) => {
              setFilters((current) => ({ ...current, triggerSource: e.target.value }));
              setPage(0);
            }}
          >
            <option value="">全部</option>
            {(options?.triggerSources ?? []).map((trigger) => (
              <option key={trigger} value={trigger}>
                {trigger}
              </option>
            ))}
          </select>
        </label>
        <label className="text-copy-12 flex flex-col gap-1">
          <span className="text-muted-foreground">停滞</span>
          <select
            className="text-copy-12 rounded border px-2 py-1"
            value={filters.stalled}
            onChange={(e) => {
              setFilters((current) => ({
                ...current,
                stalled: e.target.value as ServiceTaskStatusFilters['stalled'],
              }));
              setPage(0);
            }}
          >
            <option value="">全部</option>
            <option value="true">只看停滞</option>
            <option value="false">只看没停滞</option>
          </select>
        </label>
        <label className="text-copy-12 flex flex-col gap-1">
          <span className="text-muted-foreground">失败</span>
          <select
            className="text-copy-12 rounded border px-2 py-1"
            value={filters.failed}
            onChange={(e) => {
              setFilters((current) => ({
                ...current,
                failed: e.target.value as ServiceTaskStatusFilters['failed'],
              }));
              setPage(0);
            }}
          >
            <option value="">全部</option>
            <option value="true">只看失败</option>
            <option value="false">只看没失败</option>
          </select>
        </label>
        <label className="text-copy-12 flex flex-col gap-1">
          <span className="text-muted-foreground">关键字</span>
          <input
            className="text-copy-12 rounded border px-2 py-1"
            value={filters.keyword}
            onChange={(e) => {
              setFilters((current) => ({ ...current, keyword: e.target.value }));
              setPage(0);
            }}
            placeholder="平台 / 店铺 / 资源类型"
          />
        </label>
        <p className="text-copy-12 text-muted-foreground">
          当前页 {counts.red} 红 / {counts.yellow} 黄 / {counts.green} 绿（严重度由服务端判定）。
        </p>
      </section>

      {/* ---------------- 任务状态列表 ---------------- */}
      <section className="space-y-2">
        <h2 className="text-copy-15 font-medium">任务状态列表</h2>
        {visible.length === 0 ? (
          <div className="bg-card rounded-lg border p-8 text-center">
            <p className="text-copy-13 text-muted-foreground">
              {cards.length === 0
                ? '当前筛选下**没有任务**。任务范围 = 还在执行的 ∪ 最近一段时间发生的 —— 空不代表「一切正常」。'
                : '当前筛选下**没有**匹配的任务。'}
            </p>
          </div>
        ) : (
          <div className="space-y-3">
            {visible.map((card) => (
              <article key={`${card.sourceKey}-${card.taskId}`} className="rounded-lg border p-4">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={SEVERITY_TONES[card.severity] ?? 'neutral'}>
                      {SEVERITY_LABELS[card.severity] ?? card.severity}
                    </Badge>
                    <Badge tone={TASK_STATE_TONES[card.state] ?? 'neutral'}>
                      {TASK_STATE_LABELS[card.state] ?? card.state}
                    </Badge>
                    {card.failed && (
                      <Badge tone={FAILURE_CLASS_TONES[card.failureClass] ?? 'neutral'}>
                        {FAILURE_CLASS_LABELS[card.failureClass] ?? card.failureClass}
                      </Badge>
                    )}
                    <span className="text-copy-13 font-medium">{taskTitle(card)}</span>
                  </div>
                  <span className="text-copy-12 text-muted-foreground">
                    {card.sourceLabel} · 归属 {card.ownerModule}
                  </span>
                </div>

                <dl className="text-copy-12 mt-3 grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
                  <div>
                    <dt className="text-muted-foreground">原始状态值</dt>
                    <dd>{card.rawState}</dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">规范化状态</dt>
                    <dd>{TASK_STATE_LABELS[card.state] ?? card.state}</dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">发生时刻</dt>
                    <dd>{formatTimestamp(card.occurredAt)}</dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">耗时</dt>
                    <dd>{formatDuration(card.durationMs)}</dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">已执行</dt>
                    <dd>{formatMinutes(card.stallMinutes)}</dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">触发来源</dt>
                    <dd>{card.triggerSource ?? '—'}</dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">停滞</dt>
                    <dd>
                      {card.stalled
                        ? `已停滞（${SEVERITY_LABELS[card.stallSeverity ?? ''] ?? '—'}）`
                        : card.stallDeterminable
                          ? '没有'
                          : '判不了（没有开始时刻）'}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">失败分类</dt>
                    <dd>
                      {card.failed
                        ? (FAILURE_CLASS_LABELS[card.failureClass] ?? card.failureClass)
                        : '—'}
                    </dd>
                  </div>
                </dl>

                <p className="text-copy-12 text-muted-foreground mt-2">
                  耗时口径：{card.timingNote}
                </p>

                {card.reasons.length > 0 && (
                  <ul className="mt-3 space-y-1">
                    {card.reasons.map((reason) => (
                      <li key={reason.code} className="text-copy-12">
                        <Badge tone={SEVERITY_TONES[reason.severity] ?? 'neutral'}>
                          {reason.label}
                        </Badge>
                        <span className="ml-2">{reason.detail}</span>
                      </li>
                    ))}
                  </ul>
                )}

                {card.targetAvailable && card.targetRoute ? (
                  <Link
                    className="text-copy-12 text-primary mt-2 inline-block underline"
                    href={card.targetRoute}
                  >
                    去处置（{card.targetHint}）
                  </Link>
                ) : (
                  <p className="text-copy-12 text-muted-foreground mt-2">{card.targetNote}</p>
                )}
              </article>
            ))}
          </div>
        )}

        {pageCount > 1 && (
          <div className="text-copy-12 flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPage((current) => Math.max(0, current - 1))}
              disabled={page === 0 || busy}
            >
              上一页
            </Button>
            <span>
              第 {page + 1} / {pageCount} 页
            </span>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPage((current) => current + 1)}
              disabled={page + 1 >= pageCount || busy}
            >
              下一页
            </Button>
          </div>
        )}
      </section>

      {/* ---------------- 逐来源统计 ---------------- */}
      {sourceStats.length > 0 && (
        <section className="space-y-2">
          <h2 className="text-copy-15 font-medium">逐来源统计</h2>
          <div className="overflow-x-auto">
            <table className="text-copy-12 w-full">
              <thead>
                <tr className="text-left">
                  <th className="py-1 pr-3">来源</th>
                  <th className="py-1 pr-3">归属模块</th>
                  <th className="py-1 pr-3">总数</th>
                  <th className="py-1 pr-3">执行中</th>
                  <th className="py-1 pr-3">失败</th>
                  <th className="py-1 pr-3">停滞</th>
                  <th className="py-1 pr-3">停滞判不了</th>
                </tr>
              </thead>
              <tbody>
                {sourceStats.map((stat) => (
                  <tr key={stat.sourceKey} className="border-t">
                    <td className="py-1 pr-3">{stat.sourceLabel}</td>
                    <td className="py-1 pr-3">{stat.ownerModule}</td>
                    <td className="py-1 pr-3">{stat.total}</td>
                    <td className="py-1 pr-3">{stat.running}</td>
                    <td className="py-1 pr-3">{stat.failed}</td>
                    <td className="py-1 pr-3">{stat.stalled}</td>
                    <td className="py-1 pr-3">{stat.stallUndeterminable}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {/* ---------------- 未接通的上游 ---------------- */}
      {capabilities && capabilities.gaps.length > 0 && (
        <section className="space-y-2">
          <h2 className="text-copy-15 font-medium">未接通 / 未开发的上游</h2>
          <ul className="space-y-2">
            {capabilities.gaps.map((gap) => (
              <li key={gap.module} className="rounded-md border p-3">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone="neutral">未接通</Badge>
                  <span className="text-copy-13 font-medium">
                    {gap.module} {gap.name}
                  </span>
                  <span className="text-copy-12 text-muted-foreground">归属 {gap.ownerModule}</span>
                </div>
                <p className="text-copy-12 text-muted-foreground mt-1">{gap.impact}</p>
              </li>
            ))}
          </ul>
        </section>
      )}

      {/* ---------------- 能力声明 ---------------- */}
      {capabilities && (
        <section className="space-y-2">
          <h2 className="text-copy-15 font-medium">能力声明</h2>
          <p className="text-copy-12 text-muted-foreground">
            {formatThresholds(capabilities.thresholds)}
          </p>
          <p className="text-copy-12 text-muted-foreground">{capabilities.stallSemantics}</p>
          <p className="text-copy-12 text-muted-foreground">{capabilities.failureClassSemantics}</p>
          <p className="text-copy-12 text-muted-foreground">{capabilities.readFailureSemantics}</p>

          <div>
            <h3 className="text-copy-13 font-medium">任务来源（逐张表）</h3>
            <ul className="mt-1 space-y-2">
              {capabilities.sources.map((source) => (
                <li key={source.sourceKey} className="text-copy-12 rounded-md border p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">
                      {source.label}（{source.sourceKey}）
                    </span>
                    <span className="text-muted-foreground">归属 {source.ownerModule}</span>
                    <Badge tone={source.stallSupported ? 'success' : 'neutral'}>
                      {source.stallSupported ? '可判停滞' : '判不了停滞'}
                    </Badge>
                  </div>
                  <p className="text-muted-foreground mt-1">
                    状态列 {source.stateColumn} · 取值域 {source.stateValues} · 规范化{' '}
                    {source.canonicalStates}
                  </p>
                  <p className="text-muted-foreground">执行中取值：{source.inFlightValues}</p>
                  <p className="text-muted-foreground mt-1">{source.stallNote}</p>
                  <p className="text-muted-foreground">{source.timingNote}</p>
                  <p className="text-muted-foreground">{source.failureClassNote}</p>
                </li>
              ))}
            </ul>
          </div>

          <div>
            <h3 className="text-copy-13 font-medium">判定规则表</h3>
            <ul className="mt-1 space-y-1">
              {rulesBySeverity(capabilities.rules).map((group) => (
                <li key={group.severity} className="text-copy-12">
                  <Badge tone={SEVERITY_TONES[group.severity] ?? 'neutral'}>
                    {SEVERITY_LABELS[group.severity] ?? group.severity}
                  </Badge>
                  <ul className="ml-4 list-disc">
                    {group.rules.map((rule) => (
                      <li key={rule.code}>
                        <span className="font-medium">{rule.code}</span> · {rule.condition} ——{' '}
                        {rule.reason}
                        <span className="text-muted-foreground"> （来源：{rule.source}）</span>
                      </li>
                    ))}
                  </ul>
                </li>
              ))}
            </ul>
          </div>

          <p className="text-copy-12 text-muted-foreground">{capabilities.upstreamNote}</p>
          <p className="text-copy-12 text-muted-foreground">{capabilities.downstreamNote}</p>
          <p className="text-copy-12 text-muted-foreground">
            白名单前缀 {BASE} · 数据截至 {formatTimestamp(capabilities.dataAsOf)}
          </p>
        </section>
      )}
    </div>
  );
}
