'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { ArrowLeft, RefreshCw } from 'lucide-react';
import { Badge, Button, EmptyState, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  ReservationCapability,
  ReservationReleaseCandidatePage,
  ReservationReleaseOption,
  ReservationReleaseRunDetail,
  ReservationReleaseRunPage,
  ReservationReleaseRunRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  AVAILABLE_OWNER_NOTE,
  EXPIRY_RULE_NOTE,
  RESERVATION_RELEASE_SORT_OPTIONS,
  RESERVATION_RELEASE_TRIGGER_OPTIONS,
  RESTORE_QUANTITY_NOTE,
  SWEEP_ONLY_TRIGGER_NOTE,
  expireOverdueReservations,
  fetchReservationReleaseCandidates,
  fetchReservationReleaseCapabilities,
  fetchReservationReleaseOptions,
  fetchReservationReleaseRun,
  fetchReservationReleaseRuns,
  overdueText,
  releaseReservation,
  restoredText,
  runSummaryText,
} from './inventory-reservation-release-model';
import { errorText, qty } from './inventory-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 7.2.1.3 释放与过期工作区。
 *
 * <p>蓝图：「处理预占的释放（订单取消、支付超时、拆单减少）与过期回收，提供手动释放入口与
 * 批量过期清理，释放时同步恢复可用量。」
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>「还回可用池」与「需求释放」分两列</b>（见 {@link RESTORE_QUANTITY_NOTE}）。
 *       需求 12、只圈到 10 的货整笔释放时这两个数就是 10 与 12，合成一个会让可用量凭空多 2。
 *   <li><b>判定时刻常驻显示</b>。候选清单是「按某个时刻算出来的」，不显示那个时刻，
 *       用户会把一次预演当成实时状态。
 *   <li><b>「有承诺而跳过」单独计数</b>。那批预占在列表上与普通候选长得一模一样，
 *       不报出来就没人知道「这批货为什么一直占着」。
 *   <li><b>干跑与执行分开两个按钮</b>，且干跑结果显式写「未做任何变更」——
 *       「干跑了但没执行」被当成已清理是最贵的错。
 *   <li><b>过期由显式端点触发，不在页面上挂定时器</b>。页面只提供「干跑 / 立即清理」，
 *       定时由外部调度器调用同一个端点。
 * </ul>
 *
 * <p>错误态分两种：整体拉取失败显示错误条并保留重试；单条写动作失败只在动作处提示，
 * 不把整页换成错误态 —— 否则一次「释放失败」会把用户正在看的候选清单整个顶掉。
 */
export function InventoryReservationReleaseWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [page, setPage] = useState<ReservationReleaseCandidatePage | null>(null);
  const [runs, setRuns] = useState<ReservationReleaseRunPage | null>(null);
  const [detail, setDetail] = useState<ReservationReleaseRunDetail | null>(null);
  const [triggers, setTriggers] = useState<ReservationReleaseOption[]>(
    RESERVATION_RELEASE_TRIGGER_OPTIONS,
  );
  const [capabilities, setCapabilities] = useState<ReservationCapability[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const [keyword, setKeyword] = useState('');
  const [sort, setSort] = useState('EXPIRES_AT');
  const [pageNumber, setPageNumber] = useState(0);

  // 单笔释放表单（按预占 id 存，不放全局：一次只操作一笔，多个表单会互相盖）
  const [target, setTarget] = useState<string>('');
  const [triggerSource, setTriggerSource] = useState('MANUAL');
  const [quantity, setQuantity] = useState('');
  const [reason, setReason] = useState('');
  const [outcome, setOutcome] = useState<Record<string, string>>({});
  const [actionError, setActionError] = useState('');
  const [acting, setActing] = useState('');

  // 批量清理表单
  const [batchReason, setBatchReason] = useState('');
  const [batchLimit, setBatchLimit] = useState('200');
  const [runResult, setRunResult] = useState<string>('');

  const load = useCallback(async () => {
    if (!readable) return;
    setLoading(true);
    setError('');
    try {
      const [p, r, o, c] = await Promise.all([
        fetchReservationReleaseCandidates({
          ...(keyword.trim() ? { keyword: keyword.trim() } : {}),
          sort,
          page: pageNumber,
          size: 20,
        }),
        // 运行历史、下拉项、能力声明独立失败不拖垮主内容：
        // 候选清单是主内容，其余是佐证。
        fetchReservationReleaseRuns(0, 20).catch(() => null),
        fetchReservationReleaseOptions().catch(() => null),
        fetchReservationReleaseCapabilities().catch(() => null),
      ]);
      setPage(p);
      setRuns(r);
      if (o?.triggers?.length) setTriggers(o.triggers);
      setCapabilities(c ?? []);
    } catch (e) {
      setError(errorText(e));
      setPage(null);
      setRuns(null);
    } finally {
      setLoading(false);
    }
  }, [readable, keyword, sort, pageNumber]);

  useEffect(() => {
    void load();
  }, [load]);

  const run = useCallback(
    async (key: string, work: () => Promise<string>) => {
      setActing(key);
      setActionError('');
      try {
        const message = await work();
        setOutcome((prev) => ({ ...prev, [key]: message }));
        await load();
      } catch (e) {
        setActionError(errorText(e));
      } finally {
        setActing('');
      }
    },
    [load],
  );

  const summary = page?.summary;
  const totalPages = page ? Math.max(1, Math.ceil(page.total / Math.max(1, page.size))) : 1;
  const availableCapability = useMemo(
    () => capabilities.find((c) => c.dimension === 'AVAILABLE_RESTORE'),
    [capabilities],
  );

  if (!readable) {
    return (
      <EmptyState
        title="没有预占释放查看权限"
        description="预占释放与过期属库存卷，需要 warehouse.inventory.read 权限。"
      />
    );
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="预占释放与过期"
        description="订单取消、支付超时、拆单减少的显式释放，以及批量过期清理。"
        actions={
          <div className="flex gap-2">
            <Link href="/inventory/reservation">
              <Button variant="ghost">
                <ArrowLeft className="size-4" />
                返回预占列表
              </Button>
            </Link>
            <Button variant="ghost" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} />
              刷新
            </Button>
          </div>
        }
      />

      {error ? (
        <SectionCard title="加载失败">
          <p className="text-sm text-red-600">{error}</p>
          <Button variant="secondary" className="mt-3" onClick={() => void load()}>
            重试
          </Button>
        </SectionCard>
      ) : null}

      {actionError ? (
        <SectionCard title="动作失败">
          <p className="text-sm text-red-600">{actionError}</p>
          <p className="text-muted-foreground mt-2 text-xs">
            数据未变。释放是改库存的动作，失败时请以刷新后的列表为准，不要重复点击。
          </p>
        </SectionCard>
      ) : null}

      {/* 四张卡：候选、可还回量、承诺红线、无 TTL 管理 */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
        <StatCard
          label="过期候选"
          value={summary ? String(summary.overdueCount) : '—'}
          comparisonLabel="预占中且已过过期时刻"
        />
        <StatCard
          label="可还回量"
          value={summary ? qty(summary.overdueRestorableQuantity) : '—'}
          comparisonLabel="货物的量，不是需求量"
        />
        <StatCard
          label="因承诺跳过"
          value={summary ? String(summary.mixedCommitmentSkippedCount) : '—'}
          comparisonLabel="已超时但不会自动回收"
        />
        <StatCard
          label="无过期时刻"
          value={summary ? String(summary.unmanagedHeldCount) : '—'}
          comparisonLabel="登记时没传过期时刻"
        />
        <StatCard
          label="已承诺"
          value={summary ? String(summary.committedCount) : '—'}
          comparisonLabel="永不自动 TTL 释放"
        />
      </div>

      {/* 规则常驻：判据与红线不能只写在文档里 */}
      <SectionCard title="过期规则">
        <p className="text-sm">{EXPIRY_RULE_NOTE}</p>
        <p className="text-muted-foreground mt-2 text-xs">{AVAILABLE_OWNER_NOTE}</p>
        {availableCapability ? (
          <p className="text-muted-foreground mt-2 text-xs">
            <Badge tone="neutral">{availableCapability.ownerModule}</Badge>{' '}
            {availableCapability.note}
          </p>
        ) : null}
      </SectionCard>

      {/* 筛选 */}
      <SectionCard title="筛选">
        <div className="flex flex-wrap items-center gap-2">
          <input
            className="border-border rounded-md border px-2 py-1 text-sm"
            placeholder="预占单号 / 上游单号 / SKU 关键字"
            value={keyword}
            onChange={(e) => {
              setPageNumber(0);
              setKeyword(e.target.value);
            }}
          />
          <select
            className="border-border rounded-md border px-2 py-1 text-sm"
            value={sort}
            onChange={(e) => {
              setPageNumber(0);
              setSort(e.target.value);
            }}
          >
            {RESERVATION_RELEASE_SORT_OPTIONS.map((o) => (
              <option key={o.code} value={o.code}>
                {o.label}
              </option>
            ))}
          </select>
          <span className="text-muted-foreground text-xs">
            判定时刻 {page?.evaluatedAt ? new Date(page.evaluatedAt).toLocaleString('zh-CN') : '—'}
          </span>
        </div>
      </SectionCard>

      {/* 候选清单 */}
      <SectionCard title="过期候选清单">
        {loading && !page ? (
          <p className="text-muted-foreground text-sm">正在加载候选清单…</p>
        ) : page && page.rows.length > 0 ? (
          <>
            <table className="w-full text-sm">
              <thead>
                <tr className="border-border border-b text-left">
                  <th className="py-2">预占</th>
                  <th className="py-2">来源 / SKU</th>
                  <th className="py-2 text-right">需求量</th>
                  <th className="py-2 text-right">可还回量</th>
                  <th className="py-2">超时</th>
                  <th className="py-2">过期时刻</th>
                  <th className="py-2">操作</th>
                </tr>
              </thead>
              <tbody>
                {page.rows.map((row) => (
                  <tr key={row.reservationId} className="border-border border-b align-top">
                    <td className="py-2">
                      <Link
                        className="underline"
                        href={`/inventory/reservation/${row.reservationId}`}
                      >
                        {row.reservationNo}
                      </Link>
                      <div className="text-muted-foreground text-xs">
                        {row.referenceCode ?? '—'} · 圈到 {row.allocations.length} 个 bucket
                      </div>
                    </td>
                    <td className="py-2">
                      {row.sourceTypeLabel ?? row.sourceType}
                      <div className="text-muted-foreground text-xs">
                        {row.skuCode ?? '—'} {row.skuName ?? ''}
                      </div>
                    </td>
                    <td className="py-2 text-right">{qty(row.requestedQuantity)}</td>
                    <td className="py-2 text-right">
                      {restoredText(row.restorableQuantity, row.requestedQuantity)}
                    </td>
                    <td className="py-2">{overdueText(row.overdueSeconds)}</td>
                    <td className="py-2">
                      {row.expiresAt ? new Date(row.expiresAt).toLocaleString('zh-CN') : '—'}
                    </td>
                    <td className="py-2">
                      <div className="flex flex-wrap gap-1">
                        {writable ? (
                          <Button
                            variant="secondary"
                            disabled={acting !== ''}
                            onClick={() => {
                              setTarget(target === row.reservationId ? '' : row.reservationId);
                              setQuantity('');
                              setReason('');
                              setOutcome((prev) => {
                                const next = { ...prev };
                                delete next[row.reservationId];
                                return next;
                              });
                            }}
                          >
                            {target === row.reservationId ? '收起' : '释放'}
                          </Button>
                        ) : null}
                        <Link href={`/inventory/reservation/${row.reservationId}`}>
                          <Button variant="ghost">明细</Button>
                        </Link>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            {/* 单笔释放表单：粒度三选一，不指定即整笔释放 */}
            {target ? (
              <div className="bg-muted/40 mt-4 space-y-3 rounded-md p-3">
                <p className="text-sm font-medium">手动释放</p>
                <div className="flex flex-wrap items-center gap-2">
                  <select
                    className="border-border rounded-md border px-2 py-1 text-sm"
                    value={triggerSource}
                    onChange={(e) => setTriggerSource(e.target.value)}
                  >
                    {triggers
                      .filter((t) => !t.sweepOnly)
                      .map((t) => (
                        <option key={t.code} value={t.code}>
                          {t.label}
                        </option>
                      ))}
                  </select>
                  <input
                    className="border-border w-40 rounded-md border px-2 py-1 text-sm"
                    placeholder="释放数量（留空 = 整笔）"
                    value={quantity}
                    onChange={(e) => setQuantity(e.target.value)}
                    inputMode="decimal"
                  />
                  <input
                    className="border-border w-56 rounded-md border px-2 py-1 text-sm"
                    placeholder="原因（写入凭据）"
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                  />
                  <Button
                    disabled={acting !== ''}
                    onClick={() =>
                      void run(target, async () => {
                        const outcomeResult = await releaseReservation(target, {
                          triggerSource,
                          ...(quantity.trim() ? { quantity: Number(quantity.trim()) } : {}),
                          ...(reason.trim() ? { reason: reason.trim() } : {}),
                        });
                        return `${outcomeResult.replayed ? '重放：' : ''}${outcomeResult.message}`;
                      })
                    }
                  >
                    {acting === target ? '处理中…' : '确认释放'}
                  </Button>
                </div>
                <p className="text-muted-foreground text-xs">
                  数量留空 = 整笔释放；填数量 = 按圈定时间先后释放到该量（拆单减少）。
                  {RESTORE_QUANTITY_NOTE}
                </p>
                {SWEEP_ONLY_TRIGGER_NOTE}
                {outcome[target] ? (
                  <p className="text-sm text-emerald-600">{outcome[target]}</p>
                ) : null}
              </div>
            ) : null}

            <div className="mt-4 flex items-center justify-between">
              <span className="text-muted-foreground text-xs">共 {page.total} 条</span>
              <div className="flex gap-2">
                <Button
                  variant="secondary"
                  disabled={page.page <= 0 || loading}
                  onClick={() => setPageNumber((n) => Math.max(0, n - 1))}
                >
                  上一页
                </Button>
                <Button
                  variant="secondary"
                  disabled={page.page + 1 >= totalPages || loading}
                  onClick={() => setPageNumber((n) => n + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          </>
        ) : page ? (
          <EmptyState
            title="没有待回收的预占"
            description="按当前判定时刻，没有「预占中且已过过期时刻」的预占。已承诺的预占永不自动释放，需要人工释放。"
          />
        ) : null}
      </SectionCard>

      {/* 批量过期清理：显式端点，页面不挂定时器 */}
      <SectionCard title="批量过期清理">
        <p className="text-muted-foreground text-xs">
          清理由显式端点触发：页面上是「立即干跑 /
          立即执行」，定时由外部调度器调用同一个端点并带幂等键。 一批一个事务，失败整批回滚。
        </p>
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <input
            className="border-border w-32 rounded-md border px-2 py-1 text-sm"
            placeholder="单批上限"
            value={batchLimit}
            onChange={(e) => setBatchLimit(e.target.value)}
            inputMode="numeric"
          />
          <input
            className="border-border w-56 rounded-md border px-2 py-1 text-sm"
            placeholder="清理原因（写入凭据）"
            value={batchReason}
            onChange={(e) => setBatchReason(e.target.value)}
          />
          <Button
            variant="secondary"
            disabled={!writable || acting !== ''}
            onClick={() =>
              void run('dry', async () => {
                const row = await expireOverdueReservations({
                  dryRun: true,
                  limitCount: Number(batchLimit) || 200,
                  ...(batchReason.trim() ? { reason: batchReason.trim() } : {}),
                });
                const text = runSummaryText(row);
                setRunResult(text);
                return text;
              })
            }
          >
            {acting === 'dry' ? '判定中…' : '干跑（只判定不动数据）'}
          </Button>
          <Button
            disabled={!writable || acting !== ''}
            onClick={() =>
              void run('sweep', async () => {
                const row = await expireOverdueReservations({
                  dryRun: false,
                  limitCount: Number(batchLimit) || 200,
                  ...(batchReason.trim() ? { reason: batchReason.trim() } : {}),
                });
                const text = runSummaryText(row);
                setRunResult(text);
                return text;
              })
            }
          >
            {acting === 'sweep' ? '清理中…' : '立即清理'}
          </Button>
        </div>
        {runResult ? <p className="mt-3 text-sm">{runResult}</p> : null}
      </SectionCard>

      {/* 运行历史 */}
      <SectionCard title="清理运行历史">
        {runs && runs.rows.length > 0 ? (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-border border-b text-left">
                <th className="py-2">判定时刻</th>
                <th className="py-2">类型</th>
                <th className="py-2">结果</th>
                <th className="py-2">原因</th>
                <th className="py-2" />
              </tr>
            </thead>
            <tbody>
              {runs.rows.map((row: ReservationReleaseRunRow) => (
                <tr key={row.id} className="border-border border-b">
                  <td className="py-2">{new Date(row.evaluatedAt).toLocaleString('zh-CN')}</td>
                  <td className="py-2">
                    <Badge tone={row.dryRun ? 'neutral' : 'info'}>
                      {row.dryRun ? '干跑' : '执行'}
                    </Badge>
                    {row.replayed ? (
                      <span className="text-muted-foreground ml-2 text-xs">重放</span>
                    ) : null}
                  </td>
                  <td className="py-2">{runSummaryText(row)}</td>
                  <td className="py-2">{row.reason ?? '—'}</td>
                  <td className="py-2 text-right">
                    <Button
                      variant="ghost"
                      onClick={() => {
                        setDetail(null);
                        void fetchReservationReleaseRun(row.id)
                          .then(setDetail)
                          .catch((e) => setActionError(errorText(e)));
                      }}
                    >
                      明细
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : runs ? (
          <EmptyState
            title="还没有清理记录"
            description="干跑与执行都会留痕：一批一次凭据，append-only 不允许改。"
          />
        ) : null}

        {detail ? (
          <div className="bg-muted/40 mt-4 space-y-2 rounded-md p-3">
            <div className="flex items-center justify-between">
              <p className="text-sm font-medium">
                运行 {detail.run.id.slice(0, 8)} · {runSummaryText(detail.run)}
              </p>
              <Button variant="ghost" onClick={() => setDetail(null)}>
                关闭
              </Button>
            </div>
            {detail.releases.map((r) => (
              <div key={r.id} className="text-xs">
                {r.reservationNo} · {r.modeLabel} · 还回{' '}
                {restoredText(r.restoredQuantity, r.releasedDemandQuantity)} · 分配{' '}
                {r.allocationCount} 条 · {new Date(r.occurredAt).toLocaleString('zh-CN')}
                {r.reason ? ` · ${r.reason}` : ''}
              </div>
            ))}
            {detail.releases.length === 0 ? (
              <p className="text-muted-foreground text-xs">
                这一批没有产生释放凭据（干跑或没有可回收的候选）。
              </p>
            ) : null}
          </div>
        ) : null}
      </SectionCard>
    </div>
  );
}
