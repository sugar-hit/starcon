'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Badge, Button } from '@starcon/ui';
import { RefreshCw } from 'lucide-react';
import { api, ApiError } from '@/lib/api';
import {
  BASE,
  CAPABILITIES_PATH,
  CONNECTIONS_PATH,
  EMPTY_FILTERS,
  FAILURE_REASON_LABELS,
  OPTIONS_PATH,
  PENDING_ITEMS_PATH,
  SEVERITY_LABELS,
  SEVERITY_ORDER,
  SEVERITY_TONES,
  SUMMARY_PATH,  backlogTotal,
  buildQuery,
  filterCards,
  formatDuration,
  formatFailureCount,
  formatStaleHours,
  formatThresholds,
  formatTimestamp,
  pendingHref,
  platformGapTone,
  rulesBySeverity,
  sortBySeverity,
  sortPendingItems,
  summarize,
  type SyncHealthCapabilities,
  type SyncHealthConnectionCard,
  type SyncHealthConnectionPage,
  type SyncHealthOptions,
  type SyncHealthPendingList,
  type SyncHealthSummary,
  type SyncHealthFilters,
} from './sync-health-model';

/**
 * 1.5.1.1 同步与 API 健康 —— `/system-health` 工作区。
 *
 * <p>🔴 **本页面不写任何业务数据**。卷 01 的边界写死：「工作台只读 + 跳转；
 * 一切状态变更回到归属系统完成」。所以「重新授权 / 看失败队列 / 查积压」一律表现为
 * **跳转到归属系统的页面**，而不是在这里按一个按钮就改掉连接状态 ——
 * 那会造出第二份真相（页面的处置结论与归属系统的结论对不上）。
 *
 * <p>页面分六块：
 *
 * <ul>
 *   <li><b>总览</b> —— 红黄绿分布、失败计数（近窗口 / 累计两个口径）、限流命中、
 *       积压合计。</li>
 *   <li><b>连接健康卡片</b> —— 按店铺/连接展示，每张卡带逐条依据（为什么是这个颜色）。</li>
 *   <li><b>队列积压</b> —— 逐张表报，每行带「算作积压」的判定条件。</li>
 *   <li><b>没有连接的平台</b> —— 明确写成「还没接这个平台」，不是「连接健康」。</li>
 *   <li><b>需处理项</b> —— 跳转到归属系统的页面。</li>
 *   <li><b>能力声明</b> —— 红黄绿规则表（枚举 → 严重度）、阈值、计数口径。</li>
 * </ul>
 *
 * <p>🔴 「加载中 / 读不到 / 出错可重试 / 真的没有连接」是**四件不同的事**：
 * 后端 503 或 403 时页面显式显示「读不到」并给重试按钮，
 * 绝不渲染成一张空的绿色卡片（那等于把「没查到」说成「一切正常」）。
 */

/** 每页行数。 */
const PAGE_SIZE = 50;

/** 后端返回的错误 → 人话。 */
function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) return '当前账号没有查看同步健康的权限。';
    if (error.status === 503) return '同步健康数据**读不到**（上游服务暂时不可用）。';
    if (error.status === 401) return '登录已失效，请重新登录。';
    return error.message;
  }
  if (error instanceof Error) return error.message;
  return '请求失败，请重试';
}

export function SyncHealthWorkspace() {
  const [ready, setReady] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notices, setNotices] = useState<string[]>([]);

  const [capabilities, setCapabilities] = useState<SyncHealthCapabilities | null>(null);
  const [options, setOptions] = useState<SyncHealthOptions | null>(null);
  const [summary, setSummary] = useState<SyncHealthSummary | null>(null);
  const [pending, setPending] = useState<SyncHealthPendingList | null>(null);
  const [cards, setCards] = useState<SyncHealthConnectionCard[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);

  const [filters, setFilters] = useState<SyncHealthFilters>(EMPTY_FILTERS);

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    const notes: string[] = [];
    try {
      const query = buildQuery(filters, page, PAGE_SIZE);
      const [caps, opts, sum, pendingList, list] = await Promise.all([
        api<SyncHealthCapabilities>(CAPABILITIES_PATH),
        api<SyncHealthOptions>(OPTIONS_PATH),
        api<SyncHealthSummary>(SUMMARY_PATH),
        api<SyncHealthPendingList>(PENDING_ITEMS_PATH),
        api<SyncHealthConnectionPage>(`${CONNECTIONS_PATH}?${query}`),
      ]);

      setCapabilities(caps);
      setOptions(opts);
      setSummary(sum);
      setPending(pendingList);
      setCards(list.items ?? []);
      setTotal(list.total ?? 0);

      if (list.total > list.items.length) {
        notes.push(
          `当前页只显示 ${list.items.length} 条，共 ${list.total} 条连接健康卡片（翻页可看其余）。`,
        );
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

  const visible = useMemo(() => sortBySeverity(filterCards(cards, filters)), [cards, filters]);
  const counts = useMemo(() => summarize(cards), [cards]);
  const pageCount = Math.max(1, Math.ceil(Math.max(total, cards.length) / PAGE_SIZE));

  if (!ready)
    return (
      <p role="status" className="text-muted-foreground p-6 text-sm">
        正在加载同步与 API 健康…
      </p>
    );

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-copy-13 text-muted-foreground">
          同步与 API 健康是**跨系统排查的起点**：用户反馈「没同步 / 没订单」时，
          <b>第一个该看的页面</b>，而不是让人在五个页面里找。
          本页只读 —— 连接状态、最近同步、失败计数、限流命中、队列积压一屏可见，
          <b>处置动作一律回到归属系统完成</b>。
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
          className="border-warning/40 bg-warning/5 rounded-md border p-3 text-copy-12"
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
              <p className="text-copy-12 text-muted-foreground">连接总数</p>
              <p className="text-copy-15 font-medium">{summary.totalConnections} 条</p>
            </div>
            <div className="rounded-md border p-3">
              <p className="text-copy-12 text-muted-foreground">红 · 先处理</p>
              <p className="text-copy-15 font-medium text-destructive">{summary.redCount} 条</p>
            </div>
            <div className="rounded-md border p-3">
              <p className="text-copy-12 text-muted-foreground">黄 · 预警</p>
              <p className="text-copy-15 font-medium text-warning-foreground">
                {summary.yellowCount} 条
              </p>
            </div>
            <div className="rounded-md border p-3">
              <p className="text-copy-12 text-muted-foreground">绿 · 正常</p>
              <p className="text-copy-15 font-medium text-success-foreground">
                {summary.greenCount} 条
              </p>
            </div>
          </div>
          <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
            <div className="rounded-md border p-3">
              <p className="text-copy-12 text-muted-foreground">失败（近 24 小时 / 累计）</p>
              <p className="text-copy-13 font-medium">
                {summary.failureCountWindow} / {summary.failureCountTotal} 条
              </p>
            </div>
            <div className="rounded-md border p-3">
              <p className="text-copy-12 text-muted-foreground">限流命中（近 24 小时 / 累计）</p>
              <p className="text-copy-13 font-medium">
                {summary.rateLimitedCountWindow} / {summary.rateLimitedCountTotal} 条
              </p>
            </div>
            <div className="rounded-md border p-3">
              <p className="text-copy-12 text-muted-foreground">待处理失败</p>
              <p className="text-copy-13 font-medium">{summary.openFailureCount} 条</p>
            </div>
            <div className="rounded-md border p-3">
              <p className="text-copy-12 text-muted-foreground">队列积压</p>
              <p className="text-copy-13 font-medium">{summary.backlogTotal} 条</p>
            </div>
          </div>
          {summary.failureByReason.length > 0 && (
            <ul className="space-y-1">
              {summary.failureByReason.map((entry) => (
                <li key={entry.reason} className="text-copy-12 text-muted-foreground">
                  {FAILURE_REASON_LABELS[entry.reason] ?? entry.reason} · {formatFailureCount(entry)}
                </li>
              ))}
            </ul>
          )}
          <p className="text-copy-12 text-muted-foreground">数据截至 {formatTimestamp(summary.dataAsOf)}。</p>
          {summary.cardsTruncated && (
            <p className="text-copy-12 rounded-md border border-warning/40 bg-warning/5 p-3">
              ⚠️ 这个租户有 {summary.totalConnections} 条连接，超出了单次装载上限 ——
              上面的红黄绿分布**只覆盖了前一部分**，剩下的连接还没被判。
              这不是「它们都正常」。
            </p>
          )}
        </section>
      )}

      {/* ---------------- 筛选 ---------------- */}
      <section className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-copy-12">
          <span className="text-muted-foreground">严重度</span>
          <select
            className="rounded border px-2 py-1 text-copy-12"
            value={filters.severity}
            onChange={(e) => {
              setFilters((current) => ({ ...current, severity: e.target.value }));
              setPage(0);
            }}
          >
            <option value="">全部</option>
            {SEVERITY_ORDER.map((severity) => (
              <option key={severity} value={severity}>
                {SEVERITY_LABELS[severity] ?? severity}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-copy-12">
          <span className="text-muted-foreground">平台</span>
          <select
            className="rounded border px-2 py-1 text-copy-12"
            value={filters.platformCode}
            onChange={(e) => {
              setFilters((current) => ({ ...current, platformCode: e.target.value }));
              setPage(0);
            }}
          >
            <option value="">全部平台</option>
            {(options?.platforms ?? []).map((platform) => (
              <option key={platform} value={platform}>
                {platform}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-copy-12">
          <span className="text-muted-foreground">连接 / 店铺 / 平台账号</span>
          <input
            className="rounded border px-2 py-1 text-copy-12"
            value={filters.keyword}
            onChange={(e) => {
              setFilters((current) => ({ ...current, keyword: e.target.value }));
              setPage(0);
            }}
            placeholder="AMAZON / 店铺编码 / 平台账号"
          />
        </label>
        <p className="text-copy-12 text-muted-foreground">
          当前页 {counts.red} 红 / {counts.yellow} 黄 / {counts.green} 绿（严重度与关键字在本页筛，
          平台在后端筛）。
        </p>
      </section>

      {/* ---------------- 连接健康卡片 ---------------- */}
      <section className="space-y-2">
        <h2 className="text-copy-15 font-medium">连接健康卡片</h2>
        {visible.length === 0 ? (
          <div className="rounded-lg border bg-card p-8 text-center">
            <p className="text-copy-13 text-muted-foreground">
              {cards.length === 0
                ? '这个租户**一条连接都没有**。这不是「一切正常」—— 没有连接就没有同步，没有同步就没有订单。'
                : '当前筛选下**没有**匹配的连接。'}
            </p>
          </div>
        ) : (
          <div className="space-y-3">
            {visible.map((card) => (
              <article key={card.connectionId} className="rounded-lg border p-4">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={SEVERITY_TONES[card.severity] ?? 'neutral'}>
                      {SEVERITY_LABELS[card.severity] ?? card.severity}
                    </Badge>
                    <span className="text-copy-13 font-medium">
                      {card.platformName} · {card.displayName || card.authorizedAccount}
                    </span>
                    <span className="text-copy-12 text-muted-foreground">
                      连接状态 {card.status}
                      {card.syncPaused ? ' · 同步已暂停' : ''}
                    </span>
                  </div>
                  <span className="text-copy-12 text-muted-foreground">
                    {card.shopCount > 0
                      ? `店铺：${card.shops.map((shop) => shop.shopCode).join('、')}`
                      : '这条连接下**没有店铺**'}
                  </span>
                </div>

                <dl className="mt-3 grid gap-2 text-copy-12 sm:grid-cols-2 lg:grid-cols-4">
                  <div>
                    <dt className="text-muted-foreground">最近同步（连接级声明值）</dt>
                    <dd>{formatTimestamp(card.lastSyncAt)}</dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">最近同步（运行级完成时刻）</dt>
                    <dd>{formatTimestamp(card.lastRunFinishedAt)}</dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">距上一次同步</dt>
                    <dd>{formatStaleHours(card.staleHours)}</dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">最近一次运行</dt>
                    <dd>
                      {card.lastRunStatus ?? '还没有任何运行记录'}
                      {card.lastRunDurationMs !== null ? ` · ${formatDuration(card.lastRunDurationMs)}` : ''}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">失败（近 24 小时 / 累计）</dt>
                    <dd>
                      {card.failureCountWindow} / {card.failureCountTotal} 条
                    </dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">限流命中（近 24 小时 / 累计）</dt>
                    <dd>
                      {card.rateLimitedCountWindow} / {card.rateLimitedCountTotal} 条
                    </dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">待处理失败</dt>
                    <dd>{card.openFailureCount} 条</dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">生效的最近同步来源</dt>
                    <dd>{card.lastSyncAtSource}</dd>
                  </div>
                </dl>

                {card.failureByReason.length > 0 && (
                  <ul className="mt-2 space-y-1">
                    {card.failureByReason.map((entry) => (
                      <li key={entry.reason} className="text-copy-12 text-muted-foreground">
                        · {formatFailureCount(entry)}
                      </li>
                    ))}
                  </ul>
                )}

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
              </article>
            ))}
          </div>
        )}

        {pageCount > 1 && (
          <div className="flex items-center gap-2 text-copy-12">
            <Button
              variant="outline"
              size="sm"
              disabled={page <= 0}
              onClick={() => setPage((current) => Math.max(0, current - 1))}
            >
              上一页
            </Button>
            <span className="text-muted-foreground">
              第 {page + 1} / {pageCount} 页（共 {total} 条）
            </span>
            <Button
              variant="outline"
              size="sm"
              disabled={page + 1 >= pageCount}
              onClick={() => setPage((current) => current + 1)}
            >
              下一页
            </Button>
          </div>
        )}
      </section>

      {/* ---------------- 队列积压 ---------------- */}
      {summary && (
        <section className="space-y-2">
          <h2 className="text-copy-15 font-medium">队列积压</h2>
          {capabilities && (
            <p className="text-copy-12 text-muted-foreground">{capabilities.backlogNote}</p>
          )}
          <div className="overflow-x-auto">
            <table className="w-full text-copy-12">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="p-2">队列</th>
                  <th className="p-2">表</th>
                  <th className="p-2">算作积压的条件</th>
                  <th className="p-2">待处理</th>
                  <th className="p-2">口径</th>
                </tr>
              </thead>
              <tbody>
                {summary.backlog.map((source) => (
                  <tr key={source.table} className="border-t align-top">
                    <td className="p-2">{source.label}</td>
                    <td className="p-2 font-mono text-copy-12">{source.table}</td>
                    <td className="p-2 font-mono text-copy-12">{source.pendingCondition}</td>
                    <td className="p-2 font-medium">{source.pendingCount}</td>
                    <td className="p-2 text-muted-foreground">{source.semantics}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="text-copy-12 text-muted-foreground">
            合计 {backlogTotal(summary.backlog)} 条 —— ⚠️ 这几张表性质不同，合计只作粗看。
          </p>
        </section>
      )}

      {/* ---------------- 没有连接的平台 ---------------- */}
      {summary && summary.platformsWithoutConnection.length > 0 && (
        <section className="space-y-2">
          <h2 className="text-copy-15 font-medium">没有连接的平台</h2>
          <p className="text-copy-12 text-muted-foreground">
            🔴 这一段说的是「<b>还没接这个平台</b>」，**不是**「连接健康」——
            两者混在一起会让人以为平台侧出了问题。
          </p>
          <ul className="space-y-1">
            {summary.platformsWithoutConnection.map((gap) => (
              <li key={gap.platformCode} className="text-copy-12">
                <Badge tone={platformGapTone(gap)}>{gap.supportStatus}</Badge>
                <span className="ml-2">{gap.message}</span>
              </li>
            ))}
          </ul>
        </section>
      )}

      {/* ---------------- 需处理项 ---------------- */}
      {pending && (
        <section className="space-y-2">
          <h2 className="text-copy-15 font-medium">需处理项</h2>
          {pending.items.length === 0 ? (
            <p className="text-copy-13 text-muted-foreground">
              当前**没有**需处理项。⚠️ 这不等于「读不到」—— 读不到时页面顶部会直接报错。
            </p>
          ) : (
            <ul className="space-y-2">
              {sortPendingItems(pending.items).map((item) => {                const href = pendingHref(item);
                return (
                  <li key={`${item.kind}-${item.title}`} className="rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={SEVERITY_TONES[item.severity] ?? 'neutral'}>
                        {SEVERITY_LABELS[item.severity] ?? item.severity}
                      </Badge>
                      <span className="text-copy-13 font-medium">{item.title}</span>
                      {href ? (
                        <Button asChild variant="outline" size="sm">
                          <Link href={href}>{item.targetHint ?? '前往处理'}</Link>
                        </Button>
                      ) : (
                        <Badge tone="neutral">没有可跳转的页面</Badge>
                      )}
                    </div>
                    <p className="text-copy-12 text-muted-foreground mt-1">{item.detail}</p>
                    <p className="text-copy-12 text-muted-foreground">
                      处置归属 {item.targetModule}
                      {item.targetNote ? ` · ${item.targetNote}` : ''}
                    </p>
                  </li>
                );
              })}
            </ul>
          )}
          {pending.truncated && (
            <p className="text-copy-12 rounded-md border border-warning/40 bg-warning/5 p-3">
              ⚠️ 连接数超出了单次装载上限，上面的待处理项**不完整** ——
              「没列出来」不等于「没问题」。
            </p>
          )}
        </section>
      )}

      {/* ---------------- 能力声明 ---------------- */}
      {capabilities && (
        <section className="space-y-2">
          <h2 className="text-copy-15 font-medium">能力声明</h2>
          <p className="text-copy-12 text-muted-foreground">
            🔴 红黄绿是**服务端算的**，规则表在这里逐条公布 ——
            页面上每个颜色都能追到一条「枚举 → 严重度 → 理由 → 事实来源」。
          </p>
          <p className="text-copy-12 text-muted-foreground">
            判定阈值：{formatThresholds(capabilities.thresholds)}
          </p>
          {rulesBySeverity(capabilities.rules).map((group) => (
            <div key={group.severity} className="space-y-1">
              <Badge tone={SEVERITY_TONES[group.severity] ?? 'neutral'}>
                {SEVERITY_LABELS[group.severity] ?? group.severity}
              </Badge>
              <ul className="mt-1 space-y-1">
                {group.rules.map((rule) => (
                  <li key={rule.code} className="text-copy-12 rounded-md border p-2">
                    <span className="font-mono">{rule.code}</span>
                    <span className="ml-2">{rule.condition}</span>
                    <span className="text-muted-foreground ml-2">⇒ {rule.reason}</span>
                    <span className="text-muted-foreground ml-2">（事实来源：{rule.source}）</span>
                  </li>
                ))}
              </ul>
            </div>
          ))}
          <ul className="space-y-1">
            <li className="text-copy-12 text-muted-foreground">
              <Badge tone="info">失败计数口径</Badge>
              <span className="ml-2">{capabilities.failureWindowSemantics}</span>
            </li>
            <li className="text-copy-12 text-muted-foreground">
              <Badge tone="info">最近同步口径</Badge>
              <span className="ml-2">{capabilities.lastSyncSemantics}</span>
            </li>
            <li className="text-copy-12 text-muted-foreground">
              <Badge tone="info">读不到 ≠ 没有</Badge>
              <span className="ml-2">{capabilities.readFailureSemantics}</span>
            </li>
            <li className="text-copy-12 text-muted-foreground">
              <Badge tone="neutral">上游</Badge>
              <span className="ml-2">{capabilities.upstreamNote}</span>
            </li>
            <li className="text-copy-12 text-muted-foreground">
              <Badge tone="neutral">下游</Badge>
              <span className="ml-2">{capabilities.downstreamNote}</span>
            </li>
          </ul>
          {capabilities.gaps.length > 0 && (
            <ul className="space-y-1">
              {capabilities.gaps.map((gap) => (
                <li key={gap.module} className="text-copy-12 rounded-md border p-2">
                  <Badge tone="warning">未接通</Badge>
                  <span className="ml-2">
                    {gap.module} {gap.name}（归属 {gap.ownerModule}）：{gap.impact}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}

      <p className="text-copy-12 text-muted-foreground">
        端点前缀 <span className="font-mono">{BASE}</span> · 本页只读，不写任何业务数据。
      </p>
    </div>
  );
}
