'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { api } from '@/lib/api';
import {
  actualStatusLabel,
  actualStatusTone,
  alertKindLabel,
  alertLevelLabel,
  alertTone,
  allRows,
  amountText,
  asOfText,
  bucketAttainmentText,
  bucketPeriodText,
  capabilityRules,
  emptyText,
  evaluatedAtText,
  notDeliveredLines,
  pageState,
  progressText,
  rateText,
  rankEntryText,
  rankText,
  scopeLabel,
  statusLabel,
  statusTone,
  summaryText,
  underAchievedRows,
  unavailableRows,
  upstreamModules,
} from './target-attainment-model';
import type {
  AttainmentBoard,
  AttainmentCapabilities,
  AttainmentRanking,
  AttainmentSnapshotPage,
} from './target-attainment-model';

/**
 * 13.1.2.2 达成追踪工作区（卷 13「利润与经营分析」L3 13.1.2 目标管理下的第二格）。
 *
 * <p>蓝图输出：「达成率表 + 预测 + 预警。」
 *
 * <p><b>数据来自 reporting 服务（13 卷的唯一归属服务）</b>：
 *
 * <ul>
 *   <li>{@code GET reporting/target-attainment/board} —— 达成率表 + 预测 + 未达标高亮 + 预警；</li>
 *   <li>{@code GET reporting/target-attainment/ranking} —— 排名（人员 / 产品维度）；</li>
 *   <li>{@code GET reporting/target-attainment/snapshots} —— 已落快照（真分页 + 真计数）；</li>
 *   <li>{@code GET reporting/target-attainment/capabilities} —— 能力声明；</li>
 *   <li>{@code POST reporting/target-attainment/evaluate} —— 评估并落快照（派生读模型，不是业务事实）。</li>
 * </ul>
 *
 * <p><b>🔴 页面必须做到的四件事</b>：
 *
 * <ol>
 *   <li><b>「算不出来」≠「等于 0」</b>：拿不到实际值时显示「不可得」+ 原因，
 *       <b>绝不用 0% 顶替</b>。</li>
 *   <li><b>「没达标」≠「还没法判定」</b>：未达标高亮只认服务端判定的 {@code underAchieved}，
 *       「周期尚未开始」「不可比」「实际值不可得」单列，不混进落后名单。</li>
 *   <li><b>预测达成比当前值更有管理价值</b>：页面把「按当前速度推算的期末值」放在当前值旁边，
 *       并写明推算口径（当前值 ÷ 时间进度）。</li>
 *   <li><b>前端不做业务算术</b>：达成率 / 预测达成率 / 时间进度一律来自服务端。</li>
 * </ol>
 */
export function AnalyticsTargetAttainmentWorkspace() {
  const [scopeType, setScopeType] = useState('');
  const [metricCode, setMetricCode] = useState('');
  const [currency, setCurrency] = useState('');
  const [status, setStatus] = useState('ACTIVE');
  const [page, setPage] = useState(0);

  const [board, setBoard] = useState<AttainmentBoard | null>(null);
  const [capabilities, setCapabilities] = useState<AttainmentCapabilities | null>(null);
  const [ranking, setRanking] = useState<AttainmentRanking | null>(null);
  const [snapshots, setSnapshots] = useState<AttainmentSnapshotPage | null>(null);

  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const params = new URLSearchParams({ page: String(page), size: '50' });
      if (scopeType) params.set('scopeType', scopeType);
      if (metricCode) params.set('metricCode', metricCode);
      if (currency) params.set('currency', currency);
      if (status) params.set('status', status);
      const [boardResult, caps, rankResult, snapshotResult] = await Promise.all([
        api<AttainmentBoard>(`reporting/target-attainment/board?${params.toString()}`),
        api<AttainmentCapabilities>('reporting/target-attainment/capabilities'),
        api<AttainmentRanking>('reporting/target-attainment/ranking?size=20'),
        api<AttainmentSnapshotPage>('reporting/target-attainment/snapshots?size=10'),
      ]);
      setBoard(boardResult);
      setCapabilities(caps);
      setRanking(rankResult);
      setSnapshots(snapshotResult);
    } catch (e) {
      setBoard(null);
      setError(e instanceof Error ? e.message : '读取达成追踪失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [scopeType, metricCode, currency, status, page]);

  useEffect(() => {
    void load();
  }, [load]);

  const evaluate = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const params = new URLSearchParams();
      if (scopeType) params.set('scopeType', scopeType);
      if (metricCode) params.set('metricCode', metricCode);
      if (currency) params.set('currency', currency);
      if (status) params.set('status', status);
      await api(
        `reporting/target-attainment/evaluate?${params.toString()}`,
        'POST',
      );
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '评估落快照失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [scopeType, metricCode, currency, status, load]);

  const rows = useMemo(() => allRows(board), [board]);
  const behind = useMemo(() => underAchievedRows(rows), [rows]);
  const unknown = useMemo(() => unavailableRows(rows), [rows]);
  const state = pageState(board, error, loading);
  const totalPages = board ? Math.max(1, Math.ceil(board.total / Math.max(board.size, 1))) : 1;

  return (
    <div className="space-y-4">
      {/* ── 常驻提醒（口径纪律 + 拿不到的部分）────────────────────────── */}
      <div className="border-border bg-muted/40 text-copy-13 text-muted-foreground space-y-1 rounded-lg border px-4 py-3">
        <p>
          <span className="text-foreground font-medium">预测比当前更有价值：</span>
          「预测达成」= 按当前速度（当前值 ÷ 时间进度）直线推算的期末值 ——
          月初就知道会不会达标，才有时间干预。
        </p>
        <p>
          <span className="text-foreground font-medium">算不出来 ≠ 等于 0：</span>
          拿不到实际值时显示「不可得」+ 原因 ——{' '}
          <span className="text-foreground">0% 达成率是有业务含义的断言</span>，
          「不可得」不是。
        </p>
        <p>
          <span className="text-foreground font-medium">没达标 ≠ 还没法判定：</span>
          未达标高亮只认「差一点」与「明显跟不上」；「周期尚未开始」「不可比」「实际值不可得」
          单独列出。
        </p>
        <p>{asOfText(board)}</p>
        {notDeliveredLines(capabilities).map((line, index) => (
          <p key={index}>
            <TriangleAlert className="mr-1 inline h-3.5 w-3.5" />
            {line}
          </p>
        ))}
      </div>

      {/* ── 筛选 ───────────────────────────────────────────────────────── */}
      <SectionCard
        title="筛选"
        description="达成追踪按 13.1.2.1 的目标来算 —— 这里只筛目标，不改目标。"
      >
        <div className="flex flex-wrap items-end gap-4">
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            归属维度
            <select
              value={scopeType}
              onChange={(e) => {
                setScopeType(e.target.value);
                setPage(0);
              }}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              <option value="">全部</option>
              {['DEPARTMENT', 'MEMBER', 'SHOP', 'PRODUCT'].map((scope) => (
                <option key={scope} value={scope}>
                  {scopeLabel(scope)}
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            目标口径码
            <input
              value={metricCode}
              onChange={(e) => {
                setMetricCode(e.target.value);
                setPage(0);
              }}
              placeholder="如 STORE_GMV_PLATFORM"
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            币种
            <input
              value={currency}
              onChange={(e) => {
                setCurrency(e.target.value.toUpperCase());
                setPage(0);
              }}
              placeholder="如 USD"
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            目标状态
            <select
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setPage(0);
              }}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              <option value="">全部</option>
              <option value="ACTIVE">生效中</option>
              <option value="ARCHIVED">已归档</option>
            </select>
          </label>
          <Button variant="outline" size="sm" disabled={loading || busy} onClick={() => void load()}>
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            重读
          </Button>
          <Button variant="outline" size="sm" disabled={loading || busy} onClick={() => void evaluate()}>
            评估并落快照
          </Button>
        </div>
      </SectionCard>

      {state === 'loading' ? (
        <EmptyState title="正在读取达成追踪…" description="达成率、预测达成与排名都在服务端算。" />
      ) : state === 'error' ? (
        <EmptyState title="读取达成追踪失败" description={error} />
      ) : state === 'empty' ? (
        <EmptyState title="没有可追踪的目标" description={emptyText(board)} />
      ) : (
        <>
          {/* ── 概览 ───────────────────────────────────────────────────── */}
          <SectionCard
            title="概览"
            description="「算不出来」的那几类单独计数 —— 它们不是「没达标」。"
          >
            <p className="text-copy-13 text-foreground">{summaryText(board?.summary ?? null)}</p>
            <p className="text-copy-13 text-muted-foreground mt-1">
              未达标 {behind.length} 项（差一点 / 明显跟不上）；算不出达成率 {unknown.length} 项。
            </p>
            {board ? <p className="text-copy-13 text-muted-foreground mt-1">{board.rankNote}</p> : null}
          </SectionCard>

          {/* ── 预警 ───────────────────────────────────────────────────── */}
          <SectionCard
            title="预警"
            description="预警覆盖全部符合条件的目标（不随分页截断）。"
          >
            {(board?.alerts ?? []).length === 0 ? (
              <p className="text-copy-13 text-muted-foreground">当前没有预警。</p>
            ) : (
              <ul className="space-y-2">
                {(board?.alerts ?? []).map((alert, index) => (
                  <li
                    key={`${alert.targetId}-${index}`}
                    className="border-border flex flex-wrap items-center gap-2 rounded-lg border px-3 py-2"
                  >
                    <Badge tone={alertTone(alert.level)}>
                      {alertLevelLabel(alert.level)}
                    </Badge>
                    <span className="text-copy-13 text-foreground font-medium">{alert.ownerName}</span>
                    <span className="text-copy-13 text-muted-foreground">
                      {scopeLabel(alert.scopeType)} · {alert.metricCode} · {alert.currency}
                    </span>
                    <Badge tone="neutral">{alertKindLabel(alert.kind)}</Badge>
                    <span className="text-copy-13 text-muted-foreground">{alert.message}</span>
                    <span className="text-copy-13 text-muted-foreground">
                      达成率 {rateText(alert.attainmentRate)} · 预测 {rateText(alert.forecastRate)} ·
                      时间进度 {progressText(alert.elapsedRatio)}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>

          {/* ── 达成率表 ───────────────────────────────────────────────── */}
          {(board?.groups ?? []).map((group) => (
            <SectionCard
              key={group.groupKey}
              title={`${scopeLabel(group.scopeType)} · ${group.metricCode} · ${group.currency}`}
              description={`周期 ${group.periodStart} ~ ${group.periodEnd} · 分组内 ${group.total} 个目标（算得出达成率 ${group.available} 个，算不出 ${group.unavailable} 个）`}
            >
              <div className="space-y-2">
                {group.rows.map((row) => (
                  <div
                    key={row.targetId}
                    className={
                      row.underAchieved
                        ? 'border-danger/60 bg-danger/5 rounded-lg border px-3 py-2'
                        : 'border-border rounded-lg border px-3 py-2'
                    }
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-copy-14 text-foreground font-medium">
                        {row.ownerName || row.ownerId}
                      </span>
                      <Badge tone={statusTone(row.status)}>{statusLabel(row.status)}</Badge>
                      {row.underAchieved ? <Badge tone="danger">未达标</Badge> : null}
                      <Badge tone={actualStatusTone(row.actualStatus)}>
                        {actualStatusLabel(row.actualStatus)}
                      </Badge>
                      <span className="text-copy-13 text-muted-foreground">
                        {row.metricDisplayName} · {row.unit} · {row.currency} ·{' '}
                        {rankText(row.rank, row.rankTotal)}
                      </span>
                    </div>
                    <div className="text-copy-13 text-muted-foreground mt-1 flex flex-wrap gap-x-4 gap-y-1">
                      <span>
                        目标 {amountText(row.targetValue, row.currency)} · 当前{' '}
                        {amountText(row.currentValue, row.currency)}
                      </span>
                      <span>达成率 {rateText(row.attainmentRate)}</span>
                      <span>
                        预测期末 {amountText(row.forecastValue, row.currency)} · 预测达成率{' '}
                        {rateText(row.forecastRate)}
                      </span>
                      <span>
                        时间进度 {progressText(row.elapsedRatio)} · 距周期结束 {row.remainingDays} 天
                      </span>
                    </div>
                    <p className="text-copy-13 text-muted-foreground mt-1">{row.statusNote}</p>
                    {row.buckets.length > 0 ? (
                      <ul className="text-copy-13 text-muted-foreground mt-1 list-disc pl-5">
                        {row.buckets.map((bucket) => (
                          <li key={`${bucket.bucketStart}-${bucket.granularity}`}>
                            {bucketPeriodText(bucket)}（{bucket.granularity}） —{' '}
                            {bucketAttainmentText(bucket)}
                          </li>
                        ))}
                      </ul>
                    ) : null}
                  </div>
                ))}
              </div>
            </SectionCard>
          ))}

          {/* ── 排名 ───────────────────────────────────────────────────── */}
          <SectionCard
            title="排名（人员 / 产品维度）"
            description="只在「同归属维度 + 同口径 + 同币种 + 同周期」内比；算不出达成率的目标不进排名。"
          >
            {(ranking?.entries ?? []).length === 0 ? (
              <p className="text-copy-13 text-muted-foreground">
                当前没有可排名的目标（算得出达成率的目标才会进排名）。
              </p>
            ) : (
              <ol className="space-y-1">
                {(ranking?.entries ?? []).map((entry) => (
                  <li key={`${entry.groupKey}-${entry.ownerId}`} className="text-copy-13">
                    <span className="text-foreground font-medium">{rankText(entry.rank, entry.rankTotal)}</span>{' '}
                    · {rankEntryText(entry)}
                  </li>
                ))}
              </ol>
            )}
            {ranking ? <p className="text-copy-13 text-muted-foreground mt-2">{ranking.note}</p> : null}
          </SectionCard>

          {/* ── 已落快照 ───────────────────────────────────────────────── */}
          <SectionCard
            title="已落快照"
            description="一次评估 = 一个可回看的时间点（派生读模型，不是业务事实）。下游 17 绩效核算 / 1 工作台预警读的就是它。"
          >
            {(snapshots?.total ?? 0) === 0 ? (
              <p className="text-copy-13 text-muted-foreground">
                还没有落过快照 —— 点上面的「评估并落快照」。
              </p>
            ) : (
              <ul className="space-y-1">
                {(snapshots?.snapshots ?? []).map((snapshot) => (
                  <li key={snapshot.id} className="text-copy-13 text-muted-foreground">
                    <span className="text-foreground font-medium">{snapshot.ownerName}</span> ·{' '}
                    {scopeLabel(snapshot.scopeType)} · {snapshot.metricCode} · {snapshot.currency} ·
                    评估日 {snapshot.evaluatedOn} · 目标 {amountText(snapshot.targetValue, snapshot.currency)} ·
                    当前 {amountText(snapshot.currentValue, snapshot.currency)} · 达成率{' '}
                    {rateText(snapshot.attainmentRate)} · 预测 {rateText(snapshot.forecastRate)} ·{' '}
                    {statusLabel(snapshot.status)}
                  </li>
                ))}
              </ul>
            )}
            {snapshots ? (
              <p className="text-copy-13 text-muted-foreground mt-2">
                共 {snapshots.total} 条（真分页 + 真计数）· {snapshots.note}
              </p>
            ) : null}
          </SectionCard>
        </>
      )}

      {/* ── 分页 ───────────────────────────────────────────────────────── */}
      {state === 'ready' ? (
        <div className="flex items-center gap-3">
          <Button
            variant="outline"
            size="sm"
            disabled={page <= 0 || loading}
            onClick={() => setPage((value) => Math.max(0, value - 1))}
          >
            上一页
          </Button>
          <span className="text-copy-13 text-muted-foreground">
            第 {page + 1} / {totalPages} 页 · 共 {board?.total ?? 0} 个目标
          </span>
          <Button
            variant="outline"
            size="sm"
            disabled={page + 1 >= totalPages || loading}
            onClick={() => setPage((value) => value + 1)}
          >
            下一页
          </Button>
        </div>
      ) : null}

      {/* ── 能力声明 ───────────────────────────────────────────────────── */}
      <SectionCard
        title="能力声明"
        description="哪些上游真接通、哪些确实拿不到、判定规则是什么。"
      >
        <p className="text-copy-13 text-muted-foreground">
          {evaluatedAtText(board)} · 上游已接通：{capabilities?.upstreamConnected ? '是' : '否'}
        </p>
        <ul className="text-copy-13 text-muted-foreground mt-1 list-disc pl-5">
          {upstreamModules(capabilities).map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
        <ul className="text-copy-13 text-muted-foreground mt-2 list-disc pl-5">
          {capabilityRules(capabilities).map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
      </SectionCard>
    </div>
  );
}
