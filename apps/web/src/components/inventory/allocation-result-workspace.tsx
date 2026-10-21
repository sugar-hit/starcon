'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { AlertTriangle, RefreshCw, ShieldAlert, ShieldCheck } from 'lucide-react';
import { Button, EmptyState, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  AllocationReconciliationReport,
  AllocationResultCapabilities,
  AllocationResultPage,
  AllocationResultRunPage,
  AllocationWritebackPage,
  SkuRef,
  WarehouseRef,
} from '../../../../../packages/api-contracts/src/index';
import { fetchSkus, fetchWarehouses } from './inventory-model';
import {
  consistencyStateLabels,
  consistencyTone,
  errorText,
  exposureStateLabels,
  exposureText,
  exposureTone,
  fetchReconciliation,
  fetchResultCapabilities,
  fetchResults,
  fetchRuns,
  fetchWritebacks,
  isUnknown,
  methodLabels,
  notNull,
  operatingModeLabels,
  outcomeLabels,
  percent,
  qty,
  recordWriteback,
  scopeLabels,
  sourceKindLabels,
  summaryText,
  timeText,
  writebackStateLabels,
  writebackTone,
} from './allocation-result-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 7.8.2.1 分摊结果查看工作区。
 *
 * <p>蓝图：「展示某 SKU/仓在各店铺的分摊量、已用、剩余、回写平台的数值，以及回写失败或延迟导致的实际风险敞口。输入：分摊结果；平台回写状态（4.3.2.1）。输出：分摊明细表 + 平台回写一致性核对。」
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>「尚未回填」必须一眼看得出，且绝不能显示成 0</b>。本地没有回写状态时，
 *       回写数 / 已用 / 剩余 / 敞口全是「—」并标成中性色，状态列写「尚未回填」。
 *   <li><b>敞口是一个数，不是一句话</b>。表格里并排显示「敞口 N 件」与「缓冲 M 件」，
 *       超了就标红 —— 处置（调缓冲 / 减分摊量）需要这两个数才做得出来。
 *   <li><b>可信度与数值一起显示</b>。汇总里给出「已用已知 12/15 行」这类计数，
 *       让人知道哪些数是猜的。
 *   <li><b>跨服务边界写在页面上</b>：回写状态在 channel 服务，本服务不读它的表，
 *       只能靠事件 / 授权 API 推送或显式录入。
 *   <li><b>空态、错误态、加载态都要有</b>：没有分摊记录 ≠ 出错。
 * </ul>
 */
export function AllocationResultWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  // 登记回写状态会直接改变「平台挂着的数」这个事实，进而改变敞口结论 —— 必须是写权限。
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [caps, setCaps] = useState<AllocationResultCapabilities | null>(null);
  const [page, setPage] = useState<AllocationResultPage | null>(null);
  const [runs, setRuns] = useState<AllocationResultRunPage | null>(null);
  const [report, setReport] = useState<AllocationReconciliationReport | null>(null);
  const [observations, setObservations] = useState<AllocationWritebackPage | null>(null);
  const [skus, setSkus] = useState<SkuRef[]>([]);
  const [warehouses, setWarehouses] = useState<WarehouseRef[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  // 筛选维度：SKU / 仓 / 店铺 / 分摊轮次（+ 方法与口径）。
  const [skuId, setSkuId] = useState('');
  const [warehouseId, setWarehouseId] = useState('');
  const [referenceId, setReferenceId] = useState('');
  const [runId, setRunId] = useState('');
  const [method, setMethod] = useState('');
  const [scope, setScope] = useState('ALL');
  const [pageIndex, setPageIndex] = useState(0);

  // 登记回写状态的草稿。
  const [draftRunId, setDraftRunId] = useState('');
  const [draftShop, setDraftShop] = useState('');
  const [draftWarehouse, setDraftWarehouse] = useState('');
  const [draftState, setDraftState] = useState('SUCCEEDED');
  const [draftAttempted, setDraftAttempted] = useState('0');
  const [draftWritten, setDraftWritten] = useState('');
  const [draftConsumed, setDraftConsumed] = useState('');
  const [draftReason, setDraftReason] = useState('');
  const [draftSourceKind, setDraftSourceKind] = useState('MANUAL');
  const [draftSourceRef, setDraftSourceRef] = useState('');
  const [draftAsOf, setDraftAsOf] = useState('');

  const filters = useMemo(
    () => ({
      runId: runId || undefined,
      skuId: skuId || undefined,
      warehouseId: warehouseId || undefined,
      referenceId: referenceId || undefined,
      method: method || undefined,
      scope: scope || undefined,
    }),
    [runId, skuId, warehouseId, referenceId, method, scope],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [cap, resultPage, runPage, reconciliation, obsPage, skuList, whList] =
        await Promise.all([
          fetchResultCapabilities(),
          fetchResults({ ...filters, page: pageIndex, size: 20 }),
          fetchRuns({ skuId: skuId || undefined, method: method || undefined, page: 0, size: 50 }),
          fetchReconciliation(filters),
          fetchWritebacks({ runId: runId || undefined, page: 0, size: 20 }),
          fetchSkus(),
          fetchWarehouses(),
        ]);
      setCaps(cap);
      setPage(resultPage);
      setRuns(runPage);
      setReport(reconciliation);
      setObservations(obsPage);
      setSkus(skuList);
      setWarehouses(whList);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [filters, pageIndex, skuId, method, runId]);

  useEffect(() => {
    if (!readable) return;
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [readable, filters, pageIndex]);

  // `?? []` 每次渲染都是新数组，直接进 useMemo 的依赖会让依赖每帧都变。
  const rows = useMemo(() => page?.rows ?? [], [page]);

  /**
   * 店铺下拉的选项。
   *
   * <p>刻意**不**去调 channel 的店铺接口：店铺在别的服务，而本页的权限是
   * `warehouse.inventory.read` —— 运营未必有 channel 的读权限，于是会出现
   * 「店铺下拉是空的、但表里明明有店」的怪事。改成从**当前结果集里**去重出店铺：
   * 能出现在分摊明细里的店就是这里要看的店。
   */
  const shopOptions = useMemo(() => {
    const map = new Map<string, string>();
    for (const row of rows) map.set(row.referenceId, row.referenceLabel || row.referenceId);
    return [...map.entries()].map(([id, label]) => ({ id, label }));
  }, [rows]);

  const doRecord = useCallback(async () => {
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const saved = await recordWriteback({
        runId: draftRunId,
        sourceSystem: 'CHANNEL',
        referenceId: draftShop,
        referenceLabel: shopOptions.find((s) => s.id === draftShop)?.label ?? '',
        warehouseId: draftWarehouse,
        state: draftState,
        attemptedQuantity: Number(draftAttempted || 0),
        writtenQuantity: draftWritten === '' ? null : Number(draftWritten),
        consumedQuantity: draftConsumed === '' ? null : Number(draftConsumed),
        failureReason: draftReason,
        sourceKind: draftSourceKind,
        sourceReference: draftSourceRef,
        asOf: draftAsOf || new Date().toISOString(),
      });
      setNotice(
        `${saved.message}（观测 ${saved.observationId}，采集时刻 ${timeText(saved.asOf)}）`,
      );
      setDraftWritten('');
      setDraftConsumed('');
      setDraftReason('');
      setDraftSourceRef('');
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setSaving(false);
    }
  }, [
    draftAsOf,
    draftAttempted,
    draftConsumed,
    draftReason,
    draftRunId,
    draftShop,
    draftState,
    draftSourceKind,
    draftSourceRef,
    draftWritten,
    draftWarehouse,
    shopOptions,
    load,
  ]);

  if (!readable) {
    return (
      <EmptyState
        title="没有查看分摊结果的权限"
        description="本模块需要 warehouse.inventory.read 权限。请联系管理员为你的角色勾选该权限后再来。"
      />
    );
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="分摊结果查看"
        description="7.8.2.1 · 展示某 SKU/仓在各店铺的分摊量、已用、剩余、回写平台的数值，以及回写失败或延迟导致的实际风险敞口。"
        actions={
          <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-1 h-3.5 w-3.5" />
            {loading ? '加载中…' : '刷新'}
          </Button>
        }
      />

      {error && (
        <div className="border-destructive/40 bg-destructive-soft text-destructive rounded-md border p-4 text-sm">
          <div className="space-y-2">
            <p className="flex items-start gap-2">
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
              {error}
            </p>
            <Button variant="outline" size="sm" onClick={() => void load()}>
              重新加载
            </Button>
          </div>
        </div>
      )}
      {notice && (
        <div className="border-success/40 bg-success-soft text-success rounded-md border p-4 text-sm">
          {notice}
        </div>
      )}

      {caps && (
        <div className="border-control-border bg-muted/40 text-muted-foreground space-y-2 rounded-md border p-4 text-xs">
          <div>
            <p className="text-foreground font-medium">平台回写状态的来源（跨服务边界）</p>
            <p className="mt-1">{caps.writebackProjectionNote}</p>
          </div>
          <div>
            <p className="text-foreground font-medium">「尚未回填」不是一个 0</p>
            <p className="mt-1">{caps.unknownStateNote}</p>
          </div>
          <div>
            <p className="text-foreground font-medium">三个「留一点」里本模块用的是哪一个</p>
            <p className="mt-1">{caps.bufferNote}</p>
          </div>
          <div>
            <p className="text-foreground font-medium">敞口怎么算</p>
            <p className="mt-1">{caps.exposureNote}</p>
          </div>
          <div>
            <p className="text-foreground font-medium">仓维度的来源</p>
            <p className="mt-1">{caps.warehouseNote}</p>
          </div>
        </div>
      )}

      <div className="grid gap-4 md:grid-cols-4">
        <StatCard
          label="分摊行数"
          value={page === null ? '—' : page.total}
          polarity="neutral"
          comparisonLabel={page === null ? '加载中' : summaryText(page.summary)}
        />
        <StatCard
          label="风险敞口合计"
          value={page === null ? '—' : qty(page.summary.exposureTotal)}
          unit="件"
          polarity={page !== null && page.summary.beyondBufferCount > 0 ? 'negative' : 'neutral'}
          comparisonLabel={`只有 ${page?.summary.exposureComputableCount ?? 0} 行算得出来，其余按「无法计算」计入`}
        />
        <StatCard
          label="缓冲不足的店"
          value={page === null ? '—' : page.summary.beyondBufferCount}
          polarity={page !== null && page.summary.beyondBufferCount > 0 ? 'negative' : 'neutral'}
          comparisonLabel="敞口超过保留的缓冲，同步窗口内会超卖"
        />
        <StatCard
          label="回写状态未知的店"
          value={page === null ? '—' : page.summary.unknownStateCount}
          polarity="neutral"
          comparisonLabel="本地尚未收到回写状态 —— 该去接同步，不是 0"
        />
      </div>

      <SectionCard
        title="筛选"
        description="SKU / 仓 / 店铺 / 分摊轮次四个维度，外加分摊方法与「只看哪一类问题」。"
        extra={
          <div className="flex flex-wrap items-end gap-2">
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">SKU</span>
              <select
                className="border-control-border bg-card rounded-md border px-2 py-1.5 text-sm"
                value={skuId}
                onChange={(e) => {
                  setSkuId(e.target.value);
                  setPageIndex(0);
                }}
              >
                <option value="">全部 SKU</option>
                {skus.filter(notNull).map((s) => (
                  <option key={s.id ?? ''} value={s.id ?? ''}>
                    {s.code} · {s.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">仓</span>
              <select
                className="border-control-border bg-card rounded-md border px-2 py-1.5 text-sm"
                value={warehouseId}
                onChange={(e) => {
                  setWarehouseId(e.target.value);
                  setPageIndex(0);
                }}
              >
                <option value="">全部仓</option>
                {warehouses.filter(notNull).map((w) => (
                  <option key={w.id ?? ''} value={w.id ?? ''}>
                    {w.code} · {w.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">店铺</span>
              <select
                className="border-control-border bg-card rounded-md border px-2 py-1.5 text-sm"
                value={referenceId}
                onChange={(e) => {
                  setReferenceId(e.target.value);
                  setPageIndex(0);
                }}
              >
                <option value="">全部店铺</option>
                {shopOptions.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">分摊轮次</span>
              <select
                className="border-control-border bg-card rounded-md border px-2 py-1.5 text-sm"
                value={runId}
                onChange={(e) => {
                  setRunId(e.target.value);
                  setPageIndex(0);
                }}
              >
                <option value="">全部轮次</option>
                {(runs?.runs ?? []).filter(notNull).map((r) => (
                  <option key={r.runId} value={r.runId}>
                    {r.skuCode ?? r.skuId} · {timeText(r.allocatedAt)} · {r.method}
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">分摊方法</span>
              <select
                className="border-control-border bg-card rounded-md border px-2 py-1.5 text-sm"
                value={method}
                onChange={(e) => {
                  setMethod(e.target.value);
                  setPageIndex(0);
                }}
              >
                <option value="">全部方法</option>
                {Object.entries(methodLabels).map(([code, label]) => (
                  <option key={code} value={code}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">只看</span>
              <select
                className="border-control-border bg-card rounded-md border px-2 py-1.5 text-sm"
                value={scope}
                onChange={(e) => {
                  setScope(e.target.value);
                  setPageIndex(0);
                }}
              >
                {Object.entries(scopeLabels).map(([code, label]) => (
                  <option key={code} value={code}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                setScope('BEYOND_BUFFER');
                setPageIndex(0);
              }}
            >
              <ShieldAlert className="mr-1 h-3.5 w-3.5" />
              查看风险敞口
            </Button>
          </div>
        }
      >
        {page !== null && page.warehouseUnattributedCount > 0 ? (
          <p className="text-muted-foreground text-xs">
            另有 {page.warehouseUnattributedCount} 行因本地尚未收到回写状态而无法归属到具体的仓，
            按仓筛选时被排除 —— 它们不是「没有分摊」，是「还不知道从哪个仓出的」。
          </p>
        ) : null}
      </SectionCard>

      <SectionCard
        title="分摊明细表"
        description="每行一个店铺：分摊量、已用、剩余、回写给平台的数、缓冲、敞口与一致性判定。"
      >
        {loading && rows.length === 0 ? (
          <p className="text-muted-foreground py-6 text-center text-sm">加载中…</p>
        ) : rows.length === 0 ? (
          <EmptyState
            icon={<ShieldCheck className="h-6 w-6" />}
            title="没有符合条件的分摊结果"
            description={
              scope === 'ALL'
                ? '这个租户还没有分摊记录。先在 7.8.1.1 / 7.8.1.2 跑一次分摊，再回来看结果。'
                : '当前筛选口径下没有行。换一个「只看」条件试试 —— 特别提醒：口径不同是四种不同的病，不能混着看。'
            }
          />
        ) : (
          <div className="space-y-2">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left text-xs">
                <tr>
                  <th className="py-1">店铺</th>
                  <th className="py-1">仓</th>
                  <th className="py-1 text-right">分摊量</th>
                  <th className="py-1 text-right">已用</th>
                  <th className="py-1 text-right">剩余</th>
                  <th className="py-1 text-right">回写给平台</th>
                  <th className="py-1 text-right">缓冲</th>
                  <th className="py-1 text-right">敞口</th>
                  <th className="py-1">回写状态</th>
                  <th className="py-1">一致性</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr
                    key={`${row.runId}-${row.referenceId}`}
                    className="border-control-border border-t"
                  >
                    <td className="py-1">
                      <div>{row.referenceLabel || row.referenceId}</div>
                      <div className="text-muted-foreground text-xs">
                        {operatingModeLabels[row.shopOperatingMode] ?? row.shopOperatingMode} ·{' '}
                        {outcomeLabels[row.outcome] ?? row.outcome}
                        {row.suppressed ? ' · 被压低' : ''}
                      </div>
                    </td>
                    <td className="text-muted-foreground py-1 text-xs">
                      {row.warehouseCode ?? (isUnknown(row) ? '尚未回填' : '—')}
                    </td>
                    <td className="py-1 text-right">{qty(row.allocatedQuantity)}</td>
                    <td className="py-1 text-right">{qty(row.consumedQuantity)}</td>
                    <td className="py-1 text-right">{qty(row.remainingQuantity)}</td>
                    <td className="py-1 text-right">{qty(row.platformQuantity)}</td>
                    <td className="py-1 text-right">
                      {row.bufferConfigured ? qty(row.bufferQuantity) : '未配口径'}
                    </td>
                    <td className={`py-1 text-right ${exposureTone(row.exposureState)}`}>
                      {qty(row.exposureQuantity)}
                    </td>
                    <td className={`py-1 text-xs ${writebackTone(row.writebackState)}`}>
                      <div>{writebackStateLabels[row.writebackState] ?? row.writebackState}</div>
                      <div className="text-muted-foreground">
                        {row.asOf ? timeText(row.asOf) : '本地尚未收到回写状态'}
                      </div>
                      {row.failureReason ? (
                        <div className="text-rose-600 dark:text-rose-400">{row.failureReason}</div>
                      ) : null}
                    </td>
                    <td className={`py-1 text-xs ${consistencyTone(row.consistencyState)}`}>
                      <div>
                        {consistencyStateLabels[row.consistencyState] ?? row.consistencyState}
                      </div>
                      <div className="text-muted-foreground">
                        {exposureStateLabels[row.exposureState] ?? row.exposureState}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="text-muted-foreground text-xs">{exposureText(rows[0] ?? {})}</p>
            <div className="flex items-center justify-between text-xs">
              <span className="text-muted-foreground">
                第 {pageIndex + 1} 页 · 共 {page?.total ?? 0} 行
              </span>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={pageIndex === 0}
                  onClick={() => setPageIndex((p) => Math.max(0, p - 1))}
                >
                  上一页
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page === null || (pageIndex + 1) * 20 >= page.total}
                  onClick={() => setPageIndex((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          </div>
        )}
      </SectionCard>

      <SectionCard
        title="平台回写一致性核对"
        description="逐轮结论：这一批货里几家店还没回填、几家挂多了、几家缓冲兜不住。"
      >
        {report === null ? (
          <p className="text-muted-foreground py-4 text-sm">加载中…</p>
        ) : report.runs.length === 0 ? (
          <EmptyState
            icon={<ShieldCheck className="h-6 w-6" />}
            title="没有可核对的分摊轮次"
            description="没有分摊记录就谈不上回写一致性。"
          />
        ) : (
          <div className="space-y-2">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left text-xs">
                <tr>
                  <th className="py-1">SKU</th>
                  <th className="py-1">分摊时刻</th>
                  <th className="py-1 text-right">店铺数</th>
                  <th className="py-1 text-right">尚未回填</th>
                  <th className="py-1 text-right">挂多了</th>
                  <th className="py-1 text-right">挂少了</th>
                  <th className="py-1 text-right">缓冲不足</th>
                  <th className="py-1 text-right">敞口合计</th>
                </tr>
              </thead>
              <tbody>
                {report.runs.map((r) => (
                  <tr key={r.runId} className="border-control-border border-t">
                    <td className="py-1">{r.skuCode ?? r.skuId}</td>
                    <td className="text-muted-foreground py-1 text-xs">
                      {timeText(r.allocatedAt)}
                    </td>
                    <td className="py-1 text-right">{r.shopCount}</td>
                    <td className="py-1 text-right">{r.unknownStateCount}</td>
                    <td className="py-1 text-right">{r.overAdvertisedCount}</td>
                    <td className="py-1 text-right">{r.underAdvertisedCount}</td>
                    <td
                      className={`py-1 text-right ${r.beyondBufferCount > 0 ? 'text-rose-600 dark:text-rose-400' : ''}`}
                    >
                      {r.beyondBufferCount}
                    </td>
                    <td className="py-1 text-right">{qty(r.exposureTotal)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="text-muted-foreground text-xs">{report.note}</p>
          </div>
        )}
      </SectionCard>

      <SectionCard
        title="回写状态观测"
        description="平台回写状态经事件 / 授权 API 推送或显式录入进入本地投影，append-only 不可改删。"
        extra={
          <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            刷新观测
          </Button>
        }
      >
        {observations === null ? (
          <p className="text-muted-foreground py-4 text-sm">加载中…</p>
        ) : observations.observations.length === 0 ? (
          <EmptyState
            icon={<ShieldCheck className="h-6 w-6" />}
            title="本地还没有任何回写观测"
            description="平台回写状态在 channel 服务，本服务不跨服务读表。接上事件推送或用下面的表单显式录入之前，本页的「回写数」一律是「尚未回填」。"
          />
        ) : (
          <table className="w-full text-sm">
            <thead className="text-muted-foreground text-left text-xs">
              <tr>
                <th className="py-1">采集时刻</th>
                <th className="py-1">状态</th>
                <th className="py-1 text-right">尝试回写</th>
                <th className="py-1 text-right">平台持有</th>
                <th className="py-1 text-right">已用</th>
                <th className="py-1">来源</th>
                <th className="py-1">失败原因</th>
              </tr>
            </thead>
            <tbody>
              {observations.observations.map((o) => (
                <tr key={o.id} className="border-control-border border-t">
                  <td className="py-1 text-xs">{timeText(o.asOf)}</td>
                  <td className={`py-1 text-xs ${writebackTone(o.state)}`}>
                    {writebackStateLabels[o.state] ?? o.state}
                  </td>
                  <td className="py-1 text-right">{qty(o.attemptedQuantity)}</td>
                  <td className="py-1 text-right">{qty(o.platformQuantity)}</td>
                  <td className="py-1 text-right">{qty(o.consumedQuantity)}</td>
                  <td className="text-muted-foreground py-1 text-xs">
                    {sourceKindLabels[o.sourceKind] ?? o.sourceKind} · {o.sourceReference}
                  </td>
                  <td className="py-1 text-xs text-rose-600 dark:text-rose-400">
                    {o.failureReason || '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </SectionCard>

      {writable ? (
        <SectionCard
          title="登记一次回写状态"
          description="上游还没接上时可以手工录入快照。观测是事实：写进去就不能改、不能删。"
        >
          <div className="grid gap-2 md:grid-cols-4">
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">分摊轮次 id</span>
              <input
                className="border-control-border bg-card w-full rounded-md border px-2 py-1.5 text-sm"
                value={draftRunId}
                onChange={(e) => setDraftRunId(e.target.value)}
                placeholder="run id"
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">店铺</span>
              <select
                className="border-control-border bg-card w-full rounded-md border px-2 py-1.5 text-sm"
                value={draftShop}
                onChange={(e) => setDraftShop(e.target.value)}
              >
                <option value="">选择店铺</option>
                {shopOptions.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">仓</span>
              <select
                className="border-control-border bg-card w-full rounded-md border px-2 py-1.5 text-sm"
                value={draftWarehouse}
                onChange={(e) => setDraftWarehouse(e.target.value)}
              >
                <option value="">选择仓</option>
                {warehouses.filter(notNull).map((w) => (
                  <option key={w.id ?? ''} value={w.id ?? ''}>
                    {w.code} · {w.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">状态</span>
              <select
                className="border-control-border bg-card w-full rounded-md border px-2 py-1.5 text-sm"
                value={draftState}
                onChange={(e) => setDraftState(e.target.value)}
              >
                {['SUCCEEDED', 'FAILED', 'PENDING'].map((s) => (
                  <option key={s} value={s}>
                    {writebackStateLabels[s]}
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">尝试回写件数</span>
              <input
                className="border-control-border bg-card w-full rounded-md border px-2 py-1.5 text-sm"
                value={draftAttempted}
                onChange={(e) => setDraftAttempted(e.target.value)}
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">平台持有件数（可空）</span>
              <input
                className="border-control-border bg-card w-full rounded-md border px-2 py-1.5 text-sm"
                value={draftWritten}
                onChange={(e) => setDraftWritten(e.target.value)}
                placeholder="不知道就留空"
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">已用件数（可空）</span>
              <input
                className="border-control-border bg-card w-full rounded-md border px-2 py-1.5 text-sm"
                value={draftConsumed}
                onChange={(e) => setDraftConsumed(e.target.value)}
                placeholder="不知道就留空"
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">采集时刻</span>
              <input
                className="border-control-border bg-card w-full rounded-md border px-2 py-1.5 text-sm"
                value={draftAsOf}
                onChange={(e) => setDraftAsOf(e.target.value)}
                placeholder="留空 = 现在"
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">来源种类</span>
              <select
                className="border-control-border bg-card w-full rounded-md border px-2 py-1.5 text-sm"
                value={draftSourceKind}
                onChange={(e) => setDraftSourceKind(e.target.value)}
              >
                {Object.entries(sourceKindLabels).map(([code, label]) => (
                  <option key={code} value={code}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-1 text-sm md:col-span-2">
              <span className="text-muted-foreground block text-xs">来源标识</span>
              <input
                className="border-control-border bg-card w-full rounded-md border px-2 py-1.5 text-sm"
                value={draftSourceRef}
                onChange={(e) => setDraftSourceRef(e.target.value)}
                placeholder="事件 id / API 调用标识 / 录入单号"
              />
            </label>
            <label className="space-y-1 text-sm md:col-span-2">
              <span className="text-muted-foreground block text-xs">失败原因（FAILED 必填）</span>
              <input
                className="border-control-border bg-card w-full rounded-md border px-2 py-1.5 text-sm"
                value={draftReason}
                onChange={(e) => setDraftReason(e.target.value)}
              />
            </label>
          </div>
          <div className="mt-3">
            <Button
              size="sm"
              onClick={() => void doRecord()}
              disabled={saving || !draftRunId || !draftShop || !draftWarehouse}
            >
              {saving ? '登记中…' : '登记观测'}
            </Button>
            {!draftRunId || !draftShop || !draftWarehouse ? (
              <span className="text-muted-foreground ml-2 text-xs">
                分摊轮次、店铺、仓都要选 —— 观测必须挂在一次真实存在的分摊行上。
              </span>
            ) : null}
          </div>
        </SectionCard>
      ) : null}
    </div>
  );
}
