'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import {
  ArrowRightLeft,
  GitMerge,
  History,
  Info,
  Loader2,
  RefreshCw,
  ShieldAlert,
  TriangleAlert,
} from 'lucide-react';
import type {
  SkuMergeOverview,
  SkuMergePage,
  SkuMergePreview,
  SkuMergeRow,
  SkuMergeTrace,
} from '../../../../../packages/api-contracts/src/index';
import {
  actionableReferences,
  blockReason,
  canExecuteMerge,
  dimensionHint,
  dimensionLabel,
  executeMerge,
  fetchMergeOverview,
  fetchMergePreview,
  fetchMerges,
  fetchTrace,
  referenceOutcome,
  remainingSpuNote,
  reportNote,
  reportSummary,
  timeText,
  traceSummary,
  validateNote,
  versionCheckLabel,
  versionCheckTone,
} from './sku-merge-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 3.9.1.2 SKU 合并工作区（路由 `/products/merge`）。
 *
 * <p>蓝图：「执行 SKU/SPU 合并：把重复记录归并到主记录，迁移关联……原记录标记合并并停用而非物理删除；
 * 可回溯合并来源。」页面按这条链路分三段：<b>治理概览 → 预演与执行 → 合并历史与血缘</b>。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>执行入口必须先经过预演。</b>输入候选 ID 后先拉 {@link SkuMergePreview}，
 *       只有 {@code executable} 为真时确认按钮才可点 —— 见 {@link canExecuteMerge}。
 *       合并是本服务里少数会改写十几张表的操作，不能让用户在没看过影响面的情况下点下去。
 *   <li><b>「搬不动」与「历史冻结」在界面上必须分开。</b>前者要人工解冲突，
 *       后者是刻意不搬的证据；混成一句「有些引用没处理」会让运营以为合并漏了东西。
 *   <li><b>跨商品合并要显示源商品上剩余的 SKU。</b>它们没被搬，
 *       页面必须说清这个边界而不是让用户以为整个源商品已经空了。
 *   <li><b>只读角色看得见但动不了</b>，写操作按 {@code catalog.spu.update} 收敛。
 * </ul>
 */
export function SkuMergeWorkspace() {
  const { session } = useIdentity();
  const canWrite = (session.permissions ?? []).includes('catalog.spu.update');

  const [overview, setOverview] = useState<SkuMergeOverview | null>(null);
  const [history, setHistory] = useState<SkuMergePage | null>(null);
  const [candidateId, setCandidateId] = useState('');
  const [preview, setPreview] = useState<SkuMergePreview | null>(null);
  const [executed, setExecuted] = useState<SkuMergeRow | null>(null);
  const [traceSkuId, setTraceSkuId] = useState('');
  const [trace, setTrace] = useState<SkuMergeTrace | null>(null);
  const [note, setNote] = useState('');

  const [loading, setLoading] = useState(true);
  const [previewing, setPreviewing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [tracing, setTracing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const noteError = useMemo(() => validateNote(note), [note]);
  const executable = useMemo(() => canExecuteMerge(preview), [preview]);
  const blocked = useMemo(() => blockReason(preview), [preview]);
  const actionable = useMemo(() => actionableReferences(preview?.report ?? null), [preview]);
  const remaining = useMemo(() => remainingSpuNote(preview), [preview]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [ov, page] = await Promise.all([fetchMergeOverview(), fetchMerges(0, 20)]);
      setOverview(ov);
      setHistory(page);
    } catch (e) {
      setError(e instanceof Error ? e.message : '合并治理数据加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const runPreview = useCallback(async () => {
    const id = candidateId.trim();
    if (!id) {
      setActionError('请先填入重复候选的 ID');
      return;
    }
    setPreviewing(true);
    setActionError(null);
    setExecuted(null);
    try {
      setPreview(await fetchMergePreview(id));
    } catch (e) {
      setPreview(null);
      setActionError(e instanceof Error ? e.message : '预演失败');
    } finally {
      setPreviewing(false);
    }
  }, [candidateId]);

  const runMerge = useCallback(async () => {
    if (!preview) return;
    setBusy(true);
    setActionError(null);
    try {
      setExecuted(await executeMerge(preview.candidateId, note));
      setPreview(null);
      setNote('');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '执行合并失败');
    } finally {
      setBusy(false);
    }
  }, [preview, note, load]);

  const runTrace = useCallback(async () => {
    const id = traceSkuId.trim();
    if (!id) {
      setActionError('请先填入要查血缘的 SKU ID');
      return;
    }
    setTracing(true);
    setActionError(null);
    try {
      setTrace(await fetchTrace(id));
    } catch (e) {
      setTrace(null);
      setActionError(e instanceof Error ? e.message : '查询合并来源失败');
    } finally {
      setTracing(false);
    }
  }, [traceSkuId]);

  if (loading) {
    return (
      <SectionCard title="SKU 合并" description="正在加载治理概览…">
        <p className="text-muted-foreground text-copy-13 flex items-center gap-2">
          <Loader2 className="size-4 animate-spin" aria-hidden />
          正在读取合并历史与治理概览
        </p>
      </SectionCard>
    );
  }

  if (error) {
    return (
      <SectionCard title="SKU 合并" description="加载失败">
        <p className="text-destructive text-copy-13">{error}</p>
        <Button className="mt-3" onClick={() => void load()}>
          <RefreshCw className="size-4" aria-hidden />
          重试
        </Button>
      </SectionCard>
    );
  }

  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="已执行合并" value={String(overview?.totalMerges ?? 0)} />
        <StatCard label="被合并的 SKU" value={String(overview?.mergedAwaySkuCount ?? 0)} />
        <StatCard label="待执行决定" value={String(overview?.pendingMergeDecisions ?? 0)} />
        <StatCard label="搬不动的引用" value={String(overview?.conflictReferences ?? 0)} />
      </div>
      <p className="text-muted-foreground text-copy-13">
        每一次归并都留下一条不可变的合并记录；被合并的 SKU 是<b>标记合并并停用</b>而不是物理删除；
        「待执行决定」是 3.9.1.1 判了合并、还没执行的数量；
        「搬不动的引用」是因唯一约束或业务规则留在被合并记录上的行数（合计{' '}
        {overview?.conflictReferences ?? 0} / 历史冻结 {overview?.frozenReferences ?? 0}）。
      </p>

      {overview && overview.totalMerges === 0 ? (
        <EmptyState
          title="还没有执行过合并"
          description="在 3.9.1.1 的重复 SKU 识别里登记「合并」决定之后，到这里执行归并：把重复记录并到主记录、迁移当前引用、并把原记录标记合并且停用。"
        />
      ) : null}

      {overview && overview.totalMerges > 0 && overview.pendingMergeDecisions > 0 ? (
        <p className="text-muted-foreground text-copy-13 flex items-start gap-2">
          <Info className="mt-0.5 size-4 shrink-0" aria-hidden />
          <span>
            还有 <strong className="text-foreground">{overview.pendingMergeDecisions}</strong>{' '}
            条已判合并的决定没执行。 在下面填入候选 ID 先预演影响面，确认后再执行。
          </span>
        </p>
      ) : null}

      <SectionCard
        title="预演并执行合并"
        description="合并是破坏性操作：执行前先看逐维度影响面，确认后再落地"
        extra={
          <Button variant="ghost" onClick={() => void load()}>
            <RefreshCw className="size-4" aria-hidden />
            刷新
          </Button>
        }
      >
        <div className="space-y-3">
          <div className="flex flex-wrap items-end gap-2">
            <label className="flex-1 space-y-1">
              <span className="text-copy-13 font-medium">重复候选 ID</span>
              <input
                className="border-input text-copy-13 w-full rounded-md border px-3 py-2"
                value={candidateId}
                placeholder="从 3.9.1.1 候选清单里取候选 ID"
                onChange={(e) => setCandidateId(e.target.value)}
              />
            </label>
            <Button onClick={() => void runPreview()} disabled={previewing}>
              {previewing ? (
                <Loader2 className="size-4 animate-spin" aria-hidden />
              ) : (
                <ArrowRightLeft className="size-4" aria-hidden />
              )}
              预演
            </Button>
          </div>

          {actionError ? (
            <p className="text-destructive text-copy-13" role="alert">
              {actionError}
            </p>
          ) : null}

          {preview ? (
            <div className="space-y-3">
              <div className="bg-muted/40 space-y-2 rounded-md p-3">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge
                    tone={versionCheckTone(preview.versionCheck) === 'ok' ? 'success' : 'danger'}
                  >
                    {versionCheckLabel(preview.versionCheck)}
                  </Badge>
                  <span className="text-muted-foreground text-copy-13">{preview.scopeLabel}</span>
                </div>
                <p className="text-copy-13">{preview.versionCheckLabel}</p>

                <div className="text-copy-13 grid gap-2 sm:grid-cols-2">
                  <div className="rounded border p-2">
                    <p className="text-muted-foreground">保留为主记录</p>
                    <p className="font-medium">
                      {preview.primaryCode} · {preview.primaryName}
                    </p>
                    <p className="text-muted-foreground">
                      商品 {preview.primarySpuCode} · 当前版本 {preview.primaryVersion} · 登记时版本{' '}
                      {preview.expectedPrimaryVersion}
                    </p>
                  </div>
                  <div className="rounded border p-2">
                    <p className="text-muted-foreground">被合并（标记并停用）</p>
                    <p className="font-medium">
                      {preview.secondaryCode} · {preview.secondaryName}
                    </p>
                    <p className="text-muted-foreground">
                      商品 {preview.secondarySpuCode} · 当前版本 {preview.secondaryVersion} ·
                      登记时版本 {preview.expectedSecondaryVersion}
                    </p>
                  </div>
                </div>

                {remaining ? (
                  <p className="text-copy-13 flex items-start gap-2">
                    <TriangleAlert className="mt-0.5 size-4 shrink-0" aria-hidden />
                    <span>{remaining}</span>
                  </p>
                ) : null}
              </div>

              <div className="space-y-2">
                <p className="text-copy-13 font-medium">引用迁移报告</p>
                <p className="text-muted-foreground text-copy-13">
                  {reportSummary(preview.report)}
                </p>
                {reportNote(preview.report) ? (
                  <p className="text-muted-foreground text-copy-13">{reportNote(preview.report)}</p>
                ) : null}

                {preview.report.blockedDimensions > 0 ? (
                  <p className="text-destructive text-copy-13 flex items-start gap-2">
                    <ShieldAlert className="mt-0.5 size-4 shrink-0" aria-hidden />
                    <span>
                      有 {preview.report.blockedDimensions} 个维度<b>原理上不能跨商品搬</b>，
                      与「搬不动」不同：要换一个合并范围才能处理。
                    </span>
                  </p>
                ) : null}

                {actionable.length > 0 ? (
                  <ul className="space-y-1">
                    {actionable.map((row) => (
                      <li key={row.dimension} className="text-copy-13 rounded border p-2">
                        <p className="font-medium">
                          {dimensionLabel(row.dimension)}
                          {row.blocked ? '（整维度不搬）' : ''}
                        </p>
                        <p className="text-muted-foreground">{referenceOutcome(row)}</p>
                        <p className="text-muted-foreground">{dimensionHint(row.dimension)}</p>
                      </li>
                    ))}
                  </ul>
                ) : null}
              </div>

              <div className="flex flex-wrap items-end gap-2">
                <label className="flex-1 space-y-1">
                  <span className="text-copy-13 font-medium">合并备注（留档用）</span>
                  <input
                    className="border-input text-copy-13 w-full rounded-md border px-3 py-2"
                    value={note}
                    onChange={(e) => setNote(e.target.value)}
                    placeholder="例：条码相同且主记录报价更全，保留主记录"
                  />
                </label>
                <Button
                  variant="destructive"
                  disabled={!canWrite || !executable || busy || noteError != null}
                  onClick={() => void runMerge()}
                >
                  {busy ? (
                    <Loader2 className="size-4 animate-spin" aria-hidden />
                  ) : (
                    <GitMerge className="size-4" aria-hidden />
                  )}
                  执行合并
                </Button>
              </div>
              {noteError ? <p className="text-destructive text-copy-13">{noteError}</p> : null}
              {blocked ? <p className="text-muted-foreground text-copy-13">{blocked}</p> : null}
              {!canWrite ? (
                <p className="text-muted-foreground text-copy-13">
                  当前角色没有写权限（需要 catalog.spu.update），可以预演但不能执行。
                </p>
              ) : null}
            </div>
          ) : null}

          {executed ? (
            <div className="space-y-2 rounded-md border p-3">
              <p className="text-copy-13 flex items-center gap-2 font-medium">
                <GitMerge className="size-4" aria-hidden />
                合并已执行
              </p>
              <p className="text-muted-foreground text-copy-13">
                {executed.secondaryCode} → {executed.primaryCode} · {executed.scopeLabel} ·{' '}
                {timeText(executed.executedAt)}
              </p>
              <p className="text-muted-foreground text-copy-13">{reportSummary(executed.report)}</p>
              <p className="text-muted-foreground text-copy-13">
                原记录已标记合并（指向 {executed.mergedIntoSkuId}）并停用为{' '}
                {executed.secondaryStatusAfter}，<b>没有物理删除</b>；
                历史订单行仍指向它（快照冻结，不随合并改写）。
              </p>
            </div>
          ) : null}
        </div>
      </SectionCard>

      <SectionCard title="合并历史" description="每一次归并都是一条不可变的事实">
        {history && history.items.length > 0 ? (
          <ul className="space-y-2">
            {history.items.map((row) => (
              <li key={row.id} className="text-copy-13 rounded border p-2">
                <p className="font-medium">
                  {row.primaryCode} ← {row.secondaryCode}
                  <span className="text-muted-foreground ml-2 font-normal">{row.scopeLabel}</span>
                </p>
                <p className="text-muted-foreground">{reportSummary(row.report)}</p>
                <p className="text-muted-foreground">
                  {timeText(row.executedAt)} · {row.note || '无备注'}
                </p>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-muted-foreground text-copy-13">还没有执行过合并。</p>
        )}
      </SectionCard>

      <SectionCard
        title="合并来源回溯"
        description="可回溯合并来源：这条 SKU 被并进了哪一条，又有哪几条被并进了它"
      >
        <div className="flex flex-wrap items-end gap-2">
          <label className="flex-1 space-y-1">
            <span className="text-copy-13 font-medium">SKU ID</span>
            <input
              className="border-input text-copy-13 w-full rounded-md border px-3 py-2"
              value={traceSkuId}
              placeholder="填入任意一条 SKU 的 ID"
              onChange={(e) => setTraceSkuId(e.target.value)}
            />
          </label>
          <Button onClick={() => void runTrace()} disabled={tracing}>
            {tracing ? (
              <Loader2 className="size-4 animate-spin" aria-hidden />
            ) : (
              <History className="size-4" aria-hidden />
            )}
            查询
          </Button>
        </div>

        {trace ? (
          <div className="text-copy-13 mt-3 space-y-2">
            <p className="font-medium">
              {trace.skuCode} · {trace.skuName}
            </p>
            <p className="text-muted-foreground">{traceSummary(trace)}</p>
            <p className="text-muted-foreground">{trace.note}</p>
            {trace.absorbed.length > 0 ? (
              <ul className="space-y-1">
                {trace.absorbed.map((child) => (
                  <li key={child.skuId} className="rounded border p-2">
                    <p>
                      吸收了 {child.skuCode} · {child.skuName}
                    </p>
                    <p className="text-muted-foreground">{child.note}</p>
                  </li>
                ))}
              </ul>
            ) : null}
            {trace.lastReport ? (
              <p className="text-muted-foreground">
                最近一次合并的引用报告：{reportSummary(trace.lastReport)}
              </p>
            ) : null}
          </div>
        ) : null}
      </SectionCard>
    </div>
  );
}
