'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { AlertTriangle, PackageX, RefreshCw, ScanSearch, UtensilsCrossed } from 'lucide-react';
import { Button, EmptyState, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  SkuRef,
  StockoutAlertCapabilities,
  StockoutAlertPage,
  StarvationAlertPage,
  WarehouseRef,
} from '../../../../../packages/api-contracts/src/index';
import { fetchSkus, fetchWarehouses } from './inventory-model';
import {
  acknowledgeAlert,
  errorText,
  evaluateAlerts,
  fetchAlertCapabilities,
  fetchStarvations,
  fetchStockouts,
  isUnknownRow,
  lifecycleLabels,
  notNull,
  operatingModeLabels,
  outcomeLabels,
  percent,
  qty,
  recommendationKindLabels,
  recommendationText,
  resolveAlert,
  rootCauseLabels,
  severityLabels,
  severityTone,
  stateTone,
  starvationCoverageText,
  starvationScopeLabels,
  starvationStateLabels,
  starvationSummaryText,
  stockoutCoverageText,
  stockoutScopeLabels,
  stockoutStateLabels,
  stockoutSummaryText,
  timeText,
} from './stockout-alert-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 7.8.2.2 缺货与饥饿预警工作区。
 *
 * <p>蓝图：「识别因分摊/优先级导致的店铺"饥饿"（长期分不到货、影响绩效），以及真正的全网缺货，区分两者给出不同处置建议。输入：分摊结果；店铺销售与绩效；总可用量。输出：饥饿店铺清单 + 缺货清单 + 建议（调优先级/调保留/补货）。」
 *
 * <p><b>界面纪律</b>：
 *
 * <ul>
 *   <li><b>两张清单分开渲染</b>。饥饿清单的处置是调优先级 / 调保底；缺货清单的处置是
 *       补货 / 调拨。放在一张表里就一定会有人把该补货的当成该调保底的。
 *   <li><b>「尚未回填」必须一眼看得出，且绝不能显示成 0</b>。没有销量登记、账本里查不到
 *       库存记录，这两种行都单独标灰并配一堆「—」。
 *   <li><b>建议连目标对象一起显示</b>：调 7.8.1.2 的哪一档保底、调 7.8.1.1 的哪个成员优先级、
 *       按 7.8.1.1 的哪个销量窗口补货 —— 都要能照着做。
 *   <li><b>未交付的上游写在页面上</b>：店铺绩效（17）与补货（6.2.1.2）都没交付，
 *       所以影响面显示「不知道」，补货建议标着「尚未交付」。
 *   <li><b>空态、错误态、加载态都要有</b>：没评估过 ≠ 评估了但没问题 ≠ 出错，
 *       三者的文案分开。
 * </ul>
 */
export function StockoutAlertWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  // 评估与认领/处置会改变「哪些问题还没人管」这个事实 —— 必须是写权限。
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [caps, setCaps] = useState<StockoutAlertCapabilities | null>(null);
  const [starvations, setStarvations] = useState<StarvationAlertPage | null>(null);
  const [stockouts, setStockouts] = useState<StockoutAlertPage | null>(null);
  const [skus, setSkus] = useState<SkuRef[]>([]);
  const [warehouses, setWarehouses] = useState<WarehouseRef[]>([]);
  const [loading, setLoading] = useState(true);
  const [evaluating, setEvaluating] = useState(false);
  const [acting, setActing] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  // 筛选维度：SKU / 仓 / 店铺 / 策略 / 店铺类型 / 严重度 / 处置状态 / 口径。
  const [skuId, setSkuId] = useState('');
  const [warehouseId, setWarehouseId] = useState('');
  const [referenceId, setReferenceId] = useState('');
  const [shopOperatingMode, setShopOperatingMode] = useState('');
  const [starvationScope, setStarvationScope] = useState('OPEN');
  const [stockoutScope, setStockoutScope] = useState('OPEN');
  const [severity, setSeverity] = useState('');
  const [lifecycle, setLifecycle] = useState('');
  const [pageIndex, setPageIndex] = useState(0);

  // 处置某一行时展开的输入框。
  const [resolving, setResolving] = useState('');
  const [resolutionNote, setResolutionNote] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [cap, starvationPage, stockoutPage, skuList, whList] = await Promise.all([
        fetchAlertCapabilities(),
        fetchStarvations({
          skuId: skuId || undefined,
          referenceId: referenceId || undefined,
          shopOperatingMode: shopOperatingMode || undefined,
          scope: starvationScope || undefined,
          severity: severity || undefined,
          lifecycle: lifecycle || undefined,
          page: pageIndex,
          size: 20,
        }),
        fetchStockouts({
          skuId: skuId || undefined,
          warehouseId: warehouseId || undefined,
          scope: stockoutScope || undefined,
          severity: severity || undefined,
          lifecycle: lifecycle || undefined,
          page: pageIndex,
          size: 20,
        }),
        fetchSkus(),
        fetchWarehouses(),
      ]);
      setCaps(cap);
      setStarvations(starvationPage);
      setStockouts(stockoutPage);
      setSkus(skuList);
      setWarehouses(whList);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [
    lifecycle,
    pageIndex,
    referenceId,
    severity,
    shopOperatingMode,
    skuId,
    starvationScope,
    stockoutScope,
    warehouseId,
  ]);

  useEffect(() => {
    if (!readable) return;
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [readable, load]);

  const doEvaluate = useCallback(async () => {
    setEvaluating(true);
    setError('');
    setNotice('');
    try {
      const result = await evaluateAlerts({
        windowRounds: null,
        coverageThreshold: null,
        zeroRoundRatio: null,
        demandWindowDays: null,
        skuId: skuId || null,
        warehouseId: warehouseId || null,
        referenceId: referenceId || null,
      });
      setNotice(
        `评估完成：缺货侧命中 ${result.stockoutProblemCount} 行，饥饿侧命中 ${result.starvationProblemCount} 行；`
          + `因全网缺货而未列入饥饿清单 ${result.excludedByStockoutCount} 个店铺组合；`
          + `判不出来 ${result.unknownDemandCount} 行。${result.note}`,
      );
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setEvaluating(false);
    }
  }, [load, referenceId, skuId, warehouseId]);

  const doAcknowledge = useCallback(
    async (kind: 'starvations' | 'stockouts', id: string) => {
      setActing(id);
      setError('');
      setNotice('');
      try {
        const result = await acknowledgeAlert(kind, id);
        setNotice(result.message);
        await load();
      } catch (e) {
        setError(errorText(e));
      } finally {
        setActing('');
      }
    },
    [load],
  );

  const doResolve = useCallback(
    async (kind: 'starvations' | 'stockouts', id: string) => {
      if (resolutionNote.trim() === '') {
        setError('处置必须写明做了什么 —— 没有处置说明的记录在复盘时与「没发生过」一模一样。');
        return;
      }
      setActing(id);
      setError('');
      setNotice('');
      try {
        const result = await resolveAlert(kind, id, { note: resolutionNote.trim() });
        setNotice(result.message);
        setResolving('');
        setResolutionNote('');
        await load();
      } catch (e) {
        setError(errorText(e));
      } finally {
        setActing('');
      }
    },
    [load, resolutionNote],
  );

  const starvationRows = useMemo(() => starvations?.rows ?? [], [starvations]);
  const stockoutRows = useMemo(() => stockouts?.rows ?? [], [stockouts]);

  /**
   * 店铺下拉的选项。
   *
   * <p>刻意**不**去调 channel 的店铺接口：店铺在别的服务，而本页的权限是
   * `warehouse.inventory.read` —— 运营未必有 channel 的读权限，于是会出现
   * 「店铺下拉是空的、但表里明明有店」的怪事。改成从**当前结果集**里去重出店铺。
   */
  const shopOptions = useMemo(() => {
    const map = new Map<string, string>();
    for (const row of starvationRows) map.set(row.referenceId, row.referenceLabel || row.referenceId);
    return [...map.entries()].map(([id, label]) => ({ id, label }));
  }, [starvationRows]);

  if (!readable) {
    return (
      <EmptyState
        title="没有查看缺货与饥饿预警的权限"
        description="本模块需要 warehouse.inventory.read 权限。请联系管理员为你的角色勾选该权限后再来。"
      />
    );
  }

  const neverEvaluated =
    starvations !== null &&
    stockouts !== null &&
    starvations.summary.lastDetectedAt === null &&
    stockouts.summary.lastDetectedAt === null;

  return (
    <div className="space-y-4">
      <PageHeader
        title="缺货与饥饿预警"
        description="7.8.2.2 · 「没货卖」与「没分到货」是两种问题，处置完全不同 —— 本页把它们分成两张清单，不混。"
        actions={
          <div className="flex flex-wrap gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => void load()}
              disabled={loading}
            >
              <RefreshCw className="mr-1 h-3.5 w-3.5" />
              {loading ? '加载中…' : '刷新'}
            </Button>
            {writable ? (
              <Button size="sm" onClick={() => void doEvaluate()} disabled={evaluating}>
                <ScanSearch className="mr-1 h-3.5 w-3.5" />
                {evaluating ? '评估中…' : '评估一次'}
              </Button>
            ) : null}
          </div>
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
            <p className="text-foreground font-medium">两种问题为什么必须分开</p>
            <p className="mt-1">{caps.separationNote}</p>
          </div>
          <div>
            <p className="text-foreground font-medium">「不知道」不是一个 0</p>
            <p className="mt-1">{caps.unknownNote}</p>
          </div>
          <div>
            <p className="text-foreground font-medium">总可用量的口径</p>
            <p className="mt-1">{caps.availableNote}</p>
          </div>
          <div>
            <p className="text-foreground font-medium">下游交接</p>
            <p className="mt-1">{caps.downstreamNote}</p>
          </div>
          <div className="flex flex-wrap gap-2 pt-1">
            {caps.dimensions.map((d) => (
              <span
                key={d.dimension}
                className={
                  d.available
                    ? 'border-control-border bg-card text-foreground rounded border px-2 py-0.5'
                    : 'border-amber-500/40 bg-amber-500/10 text-amber-700 dark:text-amber-400 rounded border px-2 py-0.5'
                }
              >
                {d.dimension}：{d.available ? '已接入' : `未交付（归属 ${d.ownerModule}）`}
              </span>
            ))}
          </div>
        </div>
      )}

      <div className="grid gap-4 md:grid-cols-4">
        <StatCard
          label="饥饿店铺"
          value={starvations === null ? '—' : starvations.summary.starvedCount}
          polarity={starvations !== null && starvations.summary.starvedCount > 0 ? 'negative' : 'neutral'}
          comparisonLabel={
            starvations === null ? '加载中' : starvationSummaryText(starvations.summary)
          }
        />
        <StatCard
          label="全网缺货"
          value={stockouts === null ? '—' : stockouts.summary.networkStockedOutCount}
          unit="行"
          polarity={
            stockouts !== null && stockouts.summary.networkStockedOutCount > 0 ? 'negative' : 'neutral'
          }
          comparisonLabel={
            stockouts === null
              ? '加载中'
              : `涉及 ${stockouts.summary.networkStockedOutSkuCount} 个 SKU；仓级断货 ${stockouts.summary.warehouseStockedOutCount} 行`
          }
        />
        <StatCard
          label="判不出来"
          value={
            stockouts === null
              ? '—'
              : stockouts.summary.unknownDemandCount + stockouts.summary.noLedgerRecordCount
          }
          polarity="neutral"
          comparisonLabel="没有销量登记 / 账本里查不到记录 —— 该去补数据，不是「没问题」"
        />
        <StatCard
          label="上次评估"
          value={
            starvations?.summary.lastDetectedAt
              ? timeText(starvations.summary.lastDetectedAt)
              : '尚未评估'
          }
          polarity="neutral"
          comparisonLabel={
            neverEvaluated
              ? '还没评估过 —— 先点右上角「评估一次」'
              : '评估会刷新证据；不再命中的旧预警会被自动关闭'
          }
        />
      </div>

      <SectionCard
        title="筛选"
        description="SKU / 仓 / 店铺 / 店铺类型 / 严重度 / 处置状态 —— 两个清单共用同一组维度，口径各自独立。"
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
              <span className="text-muted-foreground block text-xs">店铺类型</span>
              <select
                className="border-control-border bg-card rounded-md border px-2 py-1.5 text-sm"
                value={shopOperatingMode}
                onChange={(e) => {
                  setShopOperatingMode(e.target.value);
                  setPageIndex(0);
                }}
              >
                <option value="">全部类型</option>
                {Object.entries(operatingModeLabels).map(([code, label]) => (
                  <option key={code} value={code}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">严重度</span>
              <select
                className="border-control-border bg-card rounded-md border px-2 py-1.5 text-sm"
                value={severity}
                onChange={(e) => {
                  setSeverity(e.target.value);
                  setPageIndex(0);
                }}
              >
                <option value="">全部</option>
                {Object.entries(severityLabels).map(([code, label]) => (
                  <option key={code} value={code}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">处置状态</span>
              <select
                className="border-control-border bg-card rounded-md border px-2 py-1.5 text-sm"
                value={lifecycle}
                onChange={(e) => {
                  setLifecycle(e.target.value);
                  setPageIndex(0);
                }}
              >
                <option value="">全部</option>
                {Object.entries(lifecycleLabels).map(([code, label]) => (
                  <option key={code} value={code}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
          </div>
        }
      >
        <div className="flex flex-wrap items-end gap-4">
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground block text-xs">饥饿清单只看</span>
            <select
              className="border-control-border bg-card rounded-md border px-2 py-1.5 text-sm"
              value={starvationScope}
              onChange={(e) => {
                setStarvationScope(e.target.value);
                setPageIndex(0);
              }}
            >
              {Object.entries(starvationScopeLabels).map(([code, label]) => (
                <option key={code} value={code}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground block text-xs">缺货清单只看</span>
            <select
              className="border-control-border bg-card rounded-md border px-2 py-1.5 text-sm"
              value={stockoutScope}
              onChange={(e) => {
                setStockoutScope(e.target.value);
                setPageIndex(0);
              }}
            >
              {Object.entries(stockoutScopeLabels).map(([code, label]) => (
                <option key={code} value={code}>
                  {label}
                </option>
              ))}
            </select>
          </label>
        </div>
      </SectionCard>

      <SectionCard
        title="饥饿店铺清单（没分到货）"
        description="处置是调优先级 / 调保底 —— 这类问题货还在，只是这家店长期分不到。判据是分摊优先级 / 保底 + 店铺销量。"
      >
        {loading && starvationRows.length === 0 ? (
          <p className="text-muted-foreground py-6 text-center text-sm">加载中…</p>
        ) : starvationRows.length === 0 ? (
          <EmptyState
            icon={<UtensilsCrossed className="h-6 w-6" />}
            title={
              neverEvaluated
                ? '还没有评估过'
                : starvationScope === 'OPEN'
                  ? '没有待处理的饥饿店铺'
                  : '这个口径下没有店铺'
            }
            description={
              neverEvaluated
                ? '先点右上角「评估一次」：模块要看最近几轮分摊、店铺销量与保底承诺，才判得出谁在饿。'
                : starvationScope === 'OPEN'
                  ? '当前没有未处置的饥饿预警 —— 若刚发生过缺货，那类问题会落在缺货清单里（处置是补货，不是调保底）。'
                  : '换一个口径试试 —— 「长期分不到货」与「供给不足」是两种程度，处置也不同。'
            }
          />
        ) : (
          <div className="space-y-2">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left text-xs">
                <tr>
                  <th className="py-1">店铺 / SKU</th>
                  <th className="py-1">判定</th>
                  <th className="py-1 text-right">分到</th>
                  <th className="py-1 text-right">需求</th>
                  <th className="py-1 text-right">覆盖率</th>
                  <th className="py-1">根因 / 保底</th>
                  <th className="py-1">建议</th>
                  <th className="py-1">处置</th>
                </tr>
              </thead>
              <tbody>
                {starvationRows.map((row) => (
                  <tr key={row.id} className="border-control-border border-t align-top">
                    <td className="py-1">
                      <div>{row.referenceLabel || row.referenceId}</div>
                      <div className="text-muted-foreground text-xs">
                        {row.skuCode ?? row.skuId} ·{' '}
                        {operatingModeLabels[row.shopOperatingMode] ?? row.shopOperatingMode} ·{' '}
                        {outcomeLabels[row.lastOutcome] ?? row.lastOutcome}
                      </div>
                    </td>
                    <td className={`py-1 text-xs ${stateTone(row.alertState)}`}>
                      <div>{starvationStateLabels[row.alertState] ?? row.alertState}</div>
                      <div className={severityTone(row.severity)}>
                        {severityLabels[row.severity] ?? row.severity}
                      </div>
                      {isUnknownRow(row) ? (
                        <div className="text-muted-foreground">尚未回填</div>
                      ) : null}
                    </td>
                    <td className="py-1 text-right">
                      {qty(row.allocatedTotal)}
                      <div className="text-muted-foreground text-xs">
                        {row.zeroRoundCount}/{row.observedRunCount} 轮为 0
                      </div>
                    </td>
                    <td className="py-1 text-right">
                      {row.demandUnits === null ? (
                        <span className="text-muted-foreground">尚未回填</span>
                      ) : (
                        qty(row.demandUnits)
                      )}
                    </td>
                    <td className="py-1 text-right">
                      {row.coverageRatio === null ? (
                        <span className="text-muted-foreground">—</span>
                      ) : (
                        percent(row.coverageRatio)
                      )}
                    </td>
                    <td className="py-1 text-xs">
                      <div>{rootCauseLabels[row.rootCause] ?? row.rootCause}</div>
                      <div className="text-muted-foreground">
                        优先级{' '}
                        {row.memberPriority === null ? '尚未回填' : row.memberPriority}
                        {row.floorStatus
                          ? ` · 保底 ${row.floorStatus}${
                              row.floorShortfallQuantity
                                ? `（差 ${qty(row.floorShortfallQuantity)} 件）`
                                : ''
                            }`
                          : ' · 无保底档'}
                      </div>
                    </td>
                    <td className="py-1 text-xs">
                      <div className="font-medium">
                        {recommendationKindLabels[row.recommendation.kind] ??
                          row.recommendation.kind}
                      </div>
                      <div className="text-muted-foreground">
                        {row.recommendation.targetLabel || row.recommendation.target || '—'}
                      </div>
                      <div className="text-muted-foreground">
                        {row.recommendation.currentValue === null &&
                        row.recommendation.suggestedValue === null
                          ? '—'
                          : `${qty(row.recommendation.currentValue)} → ${qty(
                              row.recommendation.suggestedValue,
                            )}`}
                      </div>
                      <div className="text-muted-foreground max-w-md whitespace-pre-wrap">
                        {row.recommendation.reason}
                      </div>
                    </td>
                    <td className="py-1 text-xs">
                      <div>{lifecycleLabels[row.lifecycleState] ?? row.lifecycleState}</div>
                      {row.resolutionNote ? (
                        <div className="text-muted-foreground">{row.resolutionNote}</div>
                      ) : null}
                      {writable ? (
                        <div className="mt-1 flex flex-wrap gap-1">
                          {row.lifecycleState === 'OPEN' ? (
                            <Button
                              variant="outline"
                              size="sm"
                              disabled={acting === row.id}
                              onClick={() => void doAcknowledge('starvations', row.id)}
                            >
                              认领
                            </Button>
                          ) : null}
                          {row.lifecycleState !== 'RESOLVED' ? (
                            <Button
                              variant="outline"
                              size="sm"
                              disabled={acting === row.id}
                              onClick={() => {
                                setResolving(resolving === row.id ? '' : row.id);
                                setResolutionNote('');
                              }}
                            >
                              处置
                            </Button>
                          ) : null}
                        </div>
                      ) : null}
                      {resolving === row.id ? (
                        <div className="mt-1 space-y-1">
                          <input
                            className="border-control-border bg-card w-full rounded-md border px-2 py-1 text-xs"
                            value={resolutionNote}
                            onChange={(e) => setResolutionNote(e.target.value)}
                            placeholder="处置说明：做了什么"
                          />
                          <Button
                            size="sm"
                            disabled={acting === row.id}
                            onClick={() => void doResolve('starvations', row.id)}
                          >
                            确认处置
                          </Button>
                        </div>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="text-muted-foreground text-xs">
              {starvationRows[0] ? starvationCoverageText(starvationRows[0]) : ''}
            </p>
            <div className="flex items-center justify-between text-xs">
              <span className="text-muted-foreground">
                第 {pageIndex + 1} 页 · 共 {starvations?.total ?? 0} 行
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
                  disabled={starvations === null || (pageIndex + 1) * 20 >= starvations.total}
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
        title="缺货清单（没货卖）"
        description="处置是补货 / 仓间调拨 —— 这类问题货本身不够，调优先级没有用。判据是总可用量（= 在手 − 预占）。"
      >
        {loading && stockoutRows.length === 0 ? (
          <p className="text-muted-foreground py-6 text-center text-sm">加载中…</p>
        ) : stockoutRows.length === 0 ? (
          <EmptyState
            icon={<PackageX className="h-6 w-6" />}
            title={
              neverEvaluated
                ? '还没有评估过'
                : stockoutScope === 'OPEN'
                  ? '没有待处理的缺货'
                  : '这个口径下没有缺货记录'
            }
            description={
              neverEvaluated
                ? '先点右上角「评估一次」：缺货要拿账本可用量（在手 − 预占）与登记销量比，才判得出缺不缺。'
                : stockoutScope === 'OPEN'
                  ? '当前没有未处置的缺货预警。'
                  : '换一个口径试试 —— 「全网缺货」与「该仓断货」处置不同：前者补货，后者先看调拨。'
            }
          />
        ) : (
          <div className="space-y-2">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left text-xs">
                <tr>
                  <th className="py-1">SKU / 仓</th>
                  <th className="py-1">判定</th>
                  <th className="py-1 text-right">该仓可用</th>
                  <th className="py-1 text-right">全网可用</th>
                  <th className="py-1 text-right">需求</th>
                  <th className="py-1 text-right">可售天数</th>
                  <th className="py-1">建议</th>
                  <th className="py-1">处置</th>
                </tr>
              </thead>
              <tbody>
                {stockoutRows.map((row) => (
                  <tr key={row.id} className="border-control-border border-t align-top">
                    <td className="py-1">
                      <div>{row.skuCode ?? row.skuId}</div>
                      <div className="text-muted-foreground text-xs">
                        {row.warehouseId === null ? (
                          <span>查不到仓归属</span>
                        ) : (
                          `${row.warehouseCode ?? ''} · ${row.warehouseName ?? ''}`
                        )}
                      </div>
                    </td>
                    <td className={`py-1 text-xs ${stateTone(row.alertState)}`}>
                      <div>{stockoutStateLabels[row.alertState] ?? row.alertState}</div>
                      <div className={severityTone(row.severity)}>
                        {severityLabels[row.severity] ?? row.severity}
                      </div>
                      {isUnknownRow(row) ? (
                        <div className="text-muted-foreground">尚未回填</div>
                      ) : null}
                    </td>
                    <td className="py-1 text-right">
                      {row.availableQuantity === null ? (
                        <span className="text-muted-foreground">—</span>
                      ) : (
                        qty(row.availableQuantity)
                      )}
                    </td>
                    <td className="py-1 text-right">
                      {row.networkAvailableQuantity === null ? (
                        <span className="text-muted-foreground">—</span>
                      ) : (
                        qty(row.networkAvailableQuantity)
                      )}
                    </td>
                    <td className="py-1 text-right">
                      {row.demandUnits === null ? (
                        <span className="text-muted-foreground">尚未回填</span>
                      ) : (
                        qty(row.demandUnits)
                      )}
                    </td>
                    <td className="py-1 text-right">
                      {row.daysOfCover === null ? (
                        <span className="text-muted-foreground">—</span>
                      ) : (
                        qty(row.daysOfCover)
                      )}
                    </td>
                    <td className="py-1 text-xs">
                      <div className="font-medium">
                        {recommendationKindLabels[row.recommendation.kind] ??
                          row.recommendation.kind}
                      </div>
                      <div className="text-muted-foreground">
                        {row.recommendation.targetLabel || row.recommendation.target || '—'}
                      </div>
                      <div className="text-muted-foreground">
                        {row.recommendation.currentValue === null &&
                        row.recommendation.suggestedValue === null
                          ? '—'
                          : `${qty(row.recommendation.currentValue)} → ${qty(
                              row.recommendation.suggestedValue,
                            )}`}
                      </div>
                      <div className="text-muted-foreground max-w-md whitespace-pre-wrap">
                        {row.recommendation.reason}
                      </div>
                    </td>
                    <td className="py-1 text-xs">
                      <div>{lifecycleLabels[row.lifecycleState] ?? row.lifecycleState}</div>
                      {row.resolutionNote ? (
                        <div className="text-muted-foreground">{row.resolutionNote}</div>
                      ) : null}
                      {writable ? (
                        <div className="mt-1 flex flex-wrap gap-1">
                          {row.lifecycleState === 'OPEN' ? (
                            <Button
                              variant="outline"
                              size="sm"
                              disabled={acting === row.id}
                              onClick={() => void doAcknowledge('stockouts', row.id)}
                            >
                              认领
                            </Button>
                          ) : null}
                          {row.lifecycleState !== 'RESOLVED' ? (
                            <Button
                              variant="outline"
                              size="sm"
                              disabled={acting === row.id}
                              onClick={() => {
                                setResolving(resolving === row.id ? '' : row.id);
                                setResolutionNote('');
                              }}
                            >
                              处置
                            </Button>
                          ) : null}
                        </div>
                      ) : null}
                      {resolving === row.id ? (
                        <div className="mt-1 space-y-1">
                          <input
                            className="border-control-border bg-card w-full rounded-md border px-2 py-1 text-xs"
                            value={resolutionNote}
                            onChange={(e) => setResolutionNote(e.target.value)}
                            placeholder="处置说明：做了什么"
                          />
                          <Button
                            size="sm"
                            disabled={acting === row.id}
                            onClick={() => void doResolve('stockouts', row.id)}
                          >
                            确认处置
                          </Button>
                        </div>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="text-muted-foreground text-xs">
              {stockoutRows[0] ? stockoutCoverageText(stockoutRows[0]) : ''}
            </p>
            <div className="flex items-center justify-between text-xs">
              <span className="text-muted-foreground">
                第 {pageIndex + 1} 页 · 共 {stockouts?.total ?? 0} 行
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
                  disabled={stockouts === null || (pageIndex + 1) * 20 >= stockouts.total}
                  onClick={() => setPageIndex((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          </div>
        )}
      </SectionCard>

      {caps ? <p className="text-muted-foreground text-xs">{caps.upstreamNote}</p> : null}
    </div>
  );
}
