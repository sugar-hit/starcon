'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { api } from '@/lib/api';
import {
  DIMENSION_OPTIONS,
  GRANULARITY_OPTIONS,
  abcTone,
  amountText,
  caliberLabel,
  coverageText,
  crossCurrencyNote,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  emptyText,
  pageState,
  quantityText,
  rangeText,
  rateText,
  shareBarWidth,
  shareText,
  slowMovingStatusLabel,
  stockStatusTone,
  turnoverTone,
} from './inventory-analysis-model';
import type {
  InventoryAbcResponse,
  InventoryAnalysisCapabilities,
  InventoryAgingResponse,
  InventoryDrilldownResponse,
  InventoryStockStatusResponse,
  InventoryStocktakeResponse,
  InventoryStructureResponse,
  InventoryTurnoverResponse,
} from './inventory-analysis-model';

/**
 * 13.5.1.1 库存分析工作区（卷 13「利润与经营分析」L3 13.5.1 库存分析下的第一格）。
 *
 * <p>蓝图输出：「库存分析报表 + 图表 + 下钻。」
 *
 * <p><b>数据来自 reporting 服务（13 卷的唯一归属服务）的投影读模型</b>：
 *
 * <ul>
 *   <li>{@code GET reporting/inventory-analysis/structure} —— 库存金额结构（成本口径）；</li>
 *   <li>{@code GET reporting/inventory-analysis/turnover} —— 周转率（**成本口径**）+ 趋势；</li>
 *   <li>{@code GET reporting/inventory-analysis/aging} —— 库龄分布 + 呆滞占比；</li>
 *   <li>{@code GET reporting/inventory-analysis/stock-status} —— 缺货与超储；</li>
 *   <li>{@code GET reporting/inventory-analysis/abc} —— ABC 分类；</li>
 *   <li>{@code GET reporting/inventory-analysis/stocktakes} —— 盘点历史；</li>
 *   <li>{@code GET reporting/inventory-analysis/drilldown} —— SKU 级下钻；</li>
 *   <li>{@code GET reporting/inventory-analysis/capabilities} —— 能力声明。</li>
 * </ul>
 *
 * <p><b>🔴 页面必须做到的五件事</b>：
 *
 * <ol>
 *   <li><b>数据更新时间常驻</b>（卷 13 卷首加粗句「数据延迟要显示」）。</li>
 *   <li><b>「算不出来」≠「数值是 0」</b>：{@code null} 一律显示「—」并带上服务端给的原因。</li>
 *   <li><b>周转率的成本口径写在页面上</b>（分子是销售出库**成本**，分母是成本口径库存金额）。</li>
 *   <li><b>多币种绝不相加</b>：跨币种的合计不存在。</li>
 *   <li><b>能力声明常驻</b>：「哪些确实拿不到」必须写在页面上。</li>
 * </ol>
 */
export function AnalyticsInventoryAnalysisWorkspace() {
  const today = new Date().toISOString().slice(0, 10);
  const initialRange = defaultRange(today);

  const [from, setFrom] = useState(initialRange.from);
  const [to, setTo] = useState(initialRange.to);
  const [granularity, setGranularity] = useState('DAY');
  const [dimension, setDimension] = useState('ALL');

  const [structure, setStructure] = useState<InventoryStructureResponse | null>(null);
  const [turnover, setTurnover] = useState<InventoryTurnoverResponse | null>(null);
  const [aging, setAging] = useState<InventoryAgingResponse | null>(null);
  const [stockStatus, setStockStatus] = useState<InventoryStockStatusResponse | null>(null);
  const [abc, setAbc] = useState<InventoryAbcResponse | null>(null);
  const [stocktakes, setStocktakes] = useState<InventoryStocktakeResponse | null>(null);
  const [drilldown, setDrilldown] = useState<InventoryDrilldownResponse | null>(null);
  const [capabilities, setCapabilities] = useState<InventoryAnalysisCapabilities | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  const query = useMemo(() => {
    const params = new URLSearchParams({ from, to, granularity, dimension });
    return params.toString();
  }, [from, to, granularity, dimension]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [s, t, a, st, ab, tk, dr, caps] = await Promise.all([
        api<InventoryStructureResponse>(`reporting/inventory-analysis/structure?dimension=${dimension}`),
        api<InventoryTurnoverResponse>(`reporting/inventory-analysis/turnover?${query}`),
        api<InventoryAgingResponse>('reporting/inventory-analysis/aging'),
        api<InventoryStockStatusResponse>(
          `reporting/inventory-analysis/stock-status?dimension=${dimension}`,
        ),
        api<InventoryAbcResponse>('reporting/inventory-analysis/abc?size=20'),
        api<InventoryStocktakeResponse>('reporting/inventory-analysis/stocktakes?size=20'),
        api<InventoryDrilldownResponse>(`reporting/inventory-analysis/drilldown?${query}&size=20`),
        api<InventoryAnalysisCapabilities>('reporting/inventory-analysis/capabilities'),
      ]);
      setStructure(s);
      setTurnover(t);
      setAging(a);
      setStockStatus(st);
      setAbc(ab);
      setStocktakes(tk);
      setDrilldown(dr);
      setCapabilities(caps);
    } catch (e) {
      setError(e instanceof Error ? e.message : '读取失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [query, dimension]);

  useEffect(() => {
    void load();
  }, [load]);

  const state = pageState(structure, error);
  const warnings = useMemo(
    () => [
      ...(structure?.warnings ?? []),
      ...(turnover?.warnings ?? []),
      ...(aging?.warnings ?? []),
      ...(stockStatus?.warnings ?? []),
      ...(abc?.warnings ?? []),
      ...(stocktakes?.warnings ?? []),
    ],
    [structure, turnover, aging, stockStatus, abc, stocktakes],
  );

  return (
    <div className="space-y-4">
      {/* ── 数据更新时间（常驻）────────────────────────────────────────── */}
      <div className="border-border bg-muted/40 flex flex-wrap items-center justify-between gap-3 rounded-lg border px-4 py-3">
        <div className="text-copy-13 text-foreground">
          <span className="font-medium">{dataUpdatedText(structure)}</span>
          <span className="text-muted-foreground ml-2">{dataUpdatedNote(structure)}</span>
        </div>
        <div className="text-copy-13 text-muted-foreground flex items-center gap-3">
          <span>{rangeText(from, to)}</span>
          <Button variant="outline" size="sm" disabled={loading} onClick={() => void load()}>
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            刷新
          </Button>
        </div>
      </div>

      {/* ── 口径说明（成本口径写死在页面上）──────────────────────────────── */}
      {structure && (
        <div className="border-border bg-muted/40 text-copy-13 text-muted-foreground rounded-lg border px-4 py-3">
          <div>{structure.caliberNote}</div>
          {turnover && <div className="mt-1">{turnover.caliberNote}</div>}
          {crossCurrencyNote(structure.totals, structure.totalCurrency) && (
            <div className="mt-1">{crossCurrencyNote(structure.totals, structure.totalCurrency)}</div>
          )}
        </div>
      )}

      {/* ── 时间范围与筛选 ─────────────────────────────────────────────── */}
      <SectionCard
        title="时间范围与筛选"
        description="时间范围决定周转率的窗口；维度决定库存金额结构、缺货超储与周转率按什么拆解。"
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
            拆解维度
            <select
              value={dimension}
              onChange={(e) => setDimension(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              {DIMENSION_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            期间粒度
            <select
              value={granularity}
              onChange={(e) => setGranularity(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              {GRANULARITY_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
        </div>
      </SectionCard>

      {/* ── 口径提示 ───────────────────────────────────────────────────── */}
      {warnings.length > 0 && (
        <SectionCard title="口径提示">
          <ul className="space-y-1.5">
            {Array.from(new Set(warnings)).map((warning) => (
              <li key={warning} className="text-copy-13 text-foreground flex items-start gap-2">
                <TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                <span>{warning}</span>
              </li>
            ))}
          </ul>
        </SectionCard>
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
          <EmptyState title="正在读取库存分析…" description="读取期间页面上的「数据截至」仍然有效。" />
        </SectionCard>
      )}

      {state === 'empty' && (
        <SectionCard>
          <EmptyState title="没有库存分析数据" description={emptyText(structure)} />
        </SectionCard>
      )}

      {state === 'ready' && structure && (
        <>
          {/* ── 库存金额结构 ─────────────────────────────────────────────── */}
          <SectionCard
            title="库存金额结构"
            description="一行 = 一个维度取值 × 一个币种。金额是**成本口径**（数量 × catalog 单位成本），多币种绝不相加。"
          >
            {structure.rows.length === 0 ? (
              <p className="text-copy-13 text-muted-foreground">
                还没有算得出金额的库存行 —— 先刷新投影，并检查 catalog 的成本单价是否已维护。
              </p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="text-copy-13 text-muted-foreground">
                    <tr className="border-border border-b">
                      <th className="py-2 pr-3 font-medium">维度</th>
                      <th className="py-2 pr-3 font-medium">币种</th>
                      <th className="py-2 pr-3 text-right font-medium">库存金额</th>
                      <th className="py-2 pr-3 text-right font-medium">占比</th>
                      <th className="py-2 pr-3 font-medium">结构</th>
                      <th className="py-2 pr-3 text-right font-medium">在手数量</th>
                      <th className="py-2 pr-3 text-right font-medium">SKU 数</th>
                      <th className="py-2 pr-3 text-right font-medium">缺货 / 超储</th>
                      <th className="py-2 pr-3 font-medium">说明</th>
                    </tr>
                  </thead>
                  <tbody>
                    {structure.rows.map((row, index) => (
                      <tr
                        key={`${row.dimensionKey}|${row.currency}|${index}`}
                        className="border-border border-b"
                      >
                        <td className="py-2 pr-3">{row.dimensionLabel}</td>
                        <td className="py-2 pr-3">{row.currency}</td>
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          {amountText(row.inventoryValue, row.currency)}
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          {shareText(row.valueShare)}
                        </td>
                        <td className="py-2 pr-3">
                          {row.valueShare === null ? (
                            <span className="text-copy-13 text-muted-foreground">—</span>
                          ) : (
                            <div className="bg-muted h-2 w-32 overflow-hidden rounded-full">
                              <div
                                className="bg-primary h-2 rounded-full"
                                style={{ width: `${shareBarWidth(row.valueShare)}%` }}
                              />
                            </div>
                          )}
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          {quantityText(row.onHandQuantity)}
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums">{row.skuCount}</td>
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          {row.zeroStockSkuCount} / {row.overstockSkuCount}
                        </td>
                        <td className="text-copy-13 text-muted-foreground py-2 pr-3 max-w-xs">
                          {row.note || '—'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <p className="text-copy-13 text-muted-foreground mt-2">
              {coverageText(structure.costMissingSkuCount, structure.costMissingQuantity)}
            </p>
          </SectionCard>

          {/* ── 周转率 ───────────────────────────────────────────────────── */}
          <SectionCard
            title="周转率（成本口径）"
            description="分子 = **销售出库成本**，分母 = **成本口径的库存金额**。用售价算会虚高 —— 蓝图加粗句。"
          >
            {turnover && turnover.rows.length > 0 ? (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="text-copy-13 text-muted-foreground">
                    <tr className="border-border border-b">
                      <th className="py-2 pr-3 font-medium">维度</th>
                      <th className="py-2 pr-3 font-medium">币种</th>
                      <th className="py-2 pr-3 text-right font-medium">销售出库成本</th>
                      <th className="py-2 pr-3 text-right font-medium">期初库存</th>
                      <th className="py-2 pr-3 text-right font-medium">期末库存</th>
                      <th className="py-2 pr-3 text-right font-medium">平均库存</th>
                      <th className="py-2 pr-3 text-right font-medium">周转率</th>
                      <th className="py-2 pr-3 text-right font-medium">周转天数</th>
                      <th className="py-2 pr-3 font-medium">库存口径</th>
                      <th className="py-2 pr-3 font-medium">说明</th>
                    </tr>
                  </thead>
                  <tbody>
                    {turnover.rows.map((row, index) => (
                      <tr
                        key={`${row.dimensionKey}|${row.currency}|${index}`}
                        className="border-border border-b"
                      >
                        <td className="py-2 pr-3">{row.dimensionLabel}</td>
                        <td className="py-2 pr-3">{row.currency}</td>
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          {amountText(row.costOfGoodsSold, row.currency)}
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          {amountText(row.openingInventoryValue, row.currency)}
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          {amountText(row.closingInventoryValue, row.currency)}
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          {amountText(row.averageInventoryValue, row.currency)}
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          <Badge tone={turnoverTone(row.comparable)}>{rateText(row.turnoverRate)}</Badge>
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          {row.turnoverDays === null ? '—' : quantityText(row.turnoverDays)}
                        </td>
                        <td className="py-2 pr-3">{caliberLabel(row.caliber)}</td>
                        <td className="text-copy-13 text-muted-foreground py-2 pr-3 max-w-xs">
                          {row.note || '—'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <p className="text-copy-13 text-muted-foreground">
                这个窗口里还没有可比的周转率（缺期初 / 期末快照，或没有销售出库事实）。
              </p>
            )}
            {turnover && turnover.trend.length > 0 && (
              <p className="text-copy-13 text-muted-foreground mt-2">
                趋势共 {turnover.trend.length} 个点；**不可比的期间没有点**（不是 0）。
              </p>
            )}
          </SectionCard>

          {/* ── 库龄与呆滞 ───────────────────────────────────────────────── */}
          {aging && (
            <SectionCard
              title="库龄分布与呆滞占比"
              description="库龄与呆滞的**判定归 warehouse 7.6.1.1**，本模块只搬运；两者都是**量口径**（上游没有成本列）。"
            >
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="text-copy-13 text-muted-foreground">
                    <tr className="border-border border-b">
                      <th className="py-2 pr-3 font-medium">库龄档</th>
                      <th className="py-2 pr-3 text-right font-medium">在手</th>
                      <th className="py-2 pr-3 text-right font-medium">可用</th>
                      <th className="py-2 pr-3 text-right font-medium">占可用量</th>
                      <th className="py-2 pr-3 font-medium">结构</th>
                      <th className="py-2 pr-3 text-right font-medium">SKU 数</th>
                    </tr>
                  </thead>
                  <tbody>
                    {aging.buckets.map((bucket) => (
                      <tr key={bucket.bucket} className="border-border border-b">
                        <td className="py-2 pr-3">{bucket.bucketLabel}</td>
                        <td className="py-2 pr-3 text-right tabular-nums">
                          {quantityText(bucket.onHand)}
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums">
                          {quantityText(bucket.available)}
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums">
                          {shareText(bucket.availableShare)}
                        </td>
                        <td className="py-2 pr-3">
                          {bucket.availableShare === null ? (
                            <span className="text-copy-13 text-muted-foreground">—</span>
                          ) : (
                            <div className="bg-muted h-2 w-32 overflow-hidden rounded-full">
                              <div
                                className="bg-primary h-2 rounded-full"
                                style={{ width: `${shareBarWidth(bucket.availableShare)}%` }}
                              />
                            </div>
                          )}
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums">{bucket.skuCount}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <dl className="text-copy-13 mt-3 grid grid-cols-1 gap-x-6 gap-y-1 sm:grid-cols-3">
                <div>
                  <dt className="text-muted-foreground">总可用量</dt>
                  <dd className="tabular-nums">{quantityText(aging.totalAvailable)}</dd>
                </div>
                <div>
                  <dt className="text-muted-foreground">呆滞及以上可用量</dt>
                  <dd className="tabular-nums">{quantityText(aging.obsoleteAndUpAvailable)}</dd>
                </div>
                <div>
                  <dt className="text-muted-foreground">呆滞占比</dt>
                  <dd className="tabular-nums">
                    {aging.obsoleteRatio === null ? '—（不可比）' : shareText(aging.obsoleteRatio)}
                  </dd>
                </div>
              </dl>
              <p className="text-copy-13 text-muted-foreground mt-1">{aging.ratioNote}</p>
              {aging.slowMoving.length > 0 && (
                <ul className="text-copy-13 text-muted-foreground mt-2 space-y-1">
                  {aging.slowMoving.map((row) => (
                    <li key={`${row.scopeKey}|${row.status}`}>
                      {slowMovingStatusLabel(row.status)}：{quantityText(row.available)}（占可用量{' '}
                      {shareText(row.availableShare)}）
                    </li>
                  ))}
                </ul>
              )}
            </SectionCard>
          )}

          {/* ── 缺货与超储 ───────────────────────────────────────────────── */}
          {stockStatus && (
            <SectionCard
              title="缺货与超储"
              description="缺货 = 可用量 ≤ 0；超储 = 在手量超过**已配置**的阈值。**未设阈值**单独计数（与「没超储」不是一回事）。"
            >
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="text-copy-13 text-muted-foreground">
                    <tr className="border-border border-b">
                      <th className="py-2 pr-3 font-medium">维度</th>
                      <th className="py-2 pr-3 text-right font-medium">SKU 数</th>
                      <th className="py-2 pr-3 text-right font-medium">缺货 SKU</th>
                      <th className="py-2 pr-3 text-right font-medium">超储 SKU</th>
                      <th className="py-2 pr-3 text-right font-medium">超储件数</th>
                      <th className="py-2 pr-3 text-right font-medium">未设阈值</th>
                      <th className="py-2 pr-3 font-medium">金额（按币种）</th>
                    </tr>
                  </thead>
                  <tbody>
                    {stockStatus.rows.map((row, index) => (
                      <tr
                        key={`${row.dimensionKey}|${index}`}
                        className="border-border border-b"
                      >
                        <td className="py-2 pr-3">
                          <Badge tone={stockStatusTone(row.zeroStockSkuCount, row.overstockSkuCount)}>
                            {row.dimensionLabel}
                          </Badge>
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums">{row.totalSkuCount}</td>
                        <td className="py-2 pr-3 text-right tabular-nums">{row.zeroStockSkuCount}</td>
                        <td className="py-2 pr-3 text-right tabular-nums">{row.overstockSkuCount}</td>
                        <td className="py-2 pr-3 text-right tabular-nums">
                          {quantityText(row.overstockQuantity)}
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums">
                          {row.thresholdUnsetSkuCount}
                        </td>
                        <td className="text-copy-13 py-2 pr-3">
                          {row.amounts.length === 0 ? (
                            <span className="text-muted-foreground">—</span>
                          ) : (
                            row.amounts.map((amount) => (
                              <div key={amount.currency}>
                                {amount.currency}：库存 {amountText(amount.totalValue, amount.currency)}
                                ，超储 {amountText(amount.overstockValue, amount.currency)}
                              </div>
                            ))
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </SectionCard>
          )}

          {/* ── ABC 分类 ─────────────────────────────────────────────────── */}
          {abc && (
            <SectionCard
              title="ABC 分类"
              description="按**库存金额**的累计占比切分（不是销量、也不是 SKU 数）。没有维护成本的 SKU 不进 ABC。"
            >
              <p className="text-copy-13 text-muted-foreground">
                A 类累计占比上限 {abc.aCumPct === null ? '—' : `${abc.aCumPct}%`}，B 类{' '}
                {abc.bCumPct === null ? '—' : `${abc.bCumPct}%`}。
              </p>
              <div className="mt-2 overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="text-copy-13 text-muted-foreground">
                    <tr className="border-border border-b">
                      <th className="py-2 pr-3 font-medium">SKU</th>
                      <th className="py-2 pr-3 font-medium">类目</th>
                      <th className="py-2 pr-3 font-medium">币种</th>
                      <th className="py-2 pr-3 text-right font-medium">库存金额</th>
                      <th className="py-2 pr-3 text-right font-medium">占比</th>
                      <th className="py-2 pr-3 text-right font-medium">累计占比</th>
                      <th className="py-2 pr-3 font-medium">分类</th>
                    </tr>
                  </thead>
                  <tbody>
                    {abc.rows.map((row) => (
                      <tr key={`${row.skuCode}|${row.currency}`} className="border-border border-b">
                        <td className="py-2 pr-3">
                          <div>{row.skuCode}</div>
                          <div className="text-copy-13 text-muted-foreground">{row.skuName}</div>
                        </td>
                        <td className="py-2 pr-3">{row.categoryCode || '未归类'}</td>
                        <td className="py-2 pr-3">{row.currency}</td>
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          {amountText(row.inventoryValue, row.currency)}
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          {shareText(row.valueShare)}
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          {shareText(row.cumulativeShare)}
                        </td>
                        <td className="py-2 pr-3">
                          <Badge tone={abcTone(row.abcClass)}>{row.abcClassLabel}</Badge>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </SectionCard>
          )}

          {/* ── 盘点历史 ─────────────────────────────────────────────────── */}
          {stocktakes && (
            <SectionCard
              title="盘点历史"
              description="账面 / 已盘 / 差异三列计数来自 warehouse 7.4.2.1。**账面合计不等于当前库存余额**（那是冻结那一刻的数）。"
            >
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="text-copy-13 text-muted-foreground">
                    <tr className="border-border border-b">
                      <th className="py-2 pr-3 font-medium">盘点单</th>
                      <th className="py-2 pr-3 font-medium">范围</th>
                      <th className="py-2 pr-3 font-medium">状态</th>
                      <th className="py-2 pr-3 text-right font-medium">账面行</th>
                      <th className="py-2 pr-3 text-right font-medium">已盘行</th>
                      <th className="py-2 pr-3 text-right font-medium">差异行</th>
                      <th className="py-2 pr-3 text-right font-medium">准确率</th>
                    </tr>
                  </thead>
                  <tbody>
                    {stocktakes.rows.map((row) => (
                      <tr key={row.stocktakeId} className="border-border border-b">
                        <td className="py-2 pr-3">{row.code}</td>
                        <td className="py-2 pr-3">{row.scopeLabel || row.scopeType}</td>
                        <td className="py-2 pr-3">{row.status}</td>
                        <td className="py-2 pr-3 text-right tabular-nums">{row.lineCount}</td>
                        <td className="py-2 pr-3 text-right tabular-nums">{row.countedLineCount}</td>
                        <td className="py-2 pr-3 text-right tabular-nums">{row.varianceLineCount}</td>
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          {row.countedRate === null ? '—（不可比）' : shareText(row.countedRate)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </SectionCard>
          )}

          {/* ── 下钻 ─────────────────────────────────────────────────────── */}
          {drilldown && drilldown.rows.length > 0 && (
            <SectionCard
              title="SKU 级下钻"
              description="每一行给出数量、**成本口径**的单价与库存金额、窗口内的销量与销售出库成本、缺货 / 超储标记。"
            >
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="text-copy-13 text-muted-foreground">
                    <tr className="border-border border-b">
                      <th className="py-2 pr-3 font-medium">SKU</th>
                      <th className="py-2 pr-3 font-medium">仓库</th>
                      <th className="py-2 pr-3 font-medium">币种</th>
                      <th className="py-2 pr-3 text-right font-medium">在手</th>
                      <th className="py-2 pr-3 text-right font-medium">可用</th>
                      <th className="py-2 pr-3 text-right font-medium">单位成本</th>
                      <th className="py-2 pr-3 text-right font-medium">库存金额</th>
                      <th className="py-2 pr-3 text-right font-medium">销量</th>
                      <th className="py-2 pr-3 text-right font-medium">销售成本</th>
                    </tr>
                  </thead>
                  <tbody>
                    {drilldown.rows.map((row, index) => (
                      <tr
                        key={`${row.skuCode}|${row.warehouseCode}|${index}`}
                        className="border-border border-b"
                      >
                        <td className="py-2 pr-3">{row.skuCode}</td>
                        <td className="py-2 pr-3">{row.warehouseName || row.warehouseCode}</td>
                        <td className="py-2 pr-3">{row.currency}</td>
                        <td className="py-2 pr-3 text-right tabular-nums">
                          {quantityText(row.onHandQuantity)}
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums">
                          {quantityText(row.availableQuantity)}
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          {row.unitCost === null ? '—' : quantityText(row.unitCost)}
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          {amountText(row.inventoryValue, row.currency)}
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums">
                          {quantityText(row.soldQuantity)}
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          {amountText(row.costOfGoodsSold, row.currency)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </SectionCard>
          )}

          {/* ── 能力声明 ─────────────────────────────────────────────────── */}
          {capabilities && (
            <SectionCard
              title="能力声明"
              description="哪些上游真接通、哪些**确实拿不到**（每条都写清原因与影响）。"
            >
              <div className="text-copy-13 space-y-2">
                <div>
                  <span className="text-muted-foreground">覆盖指标：</span>
                  {capabilities.metrics.join(' · ')}
                </div>
                <div>
                  <span className="text-muted-foreground">可拆维度：</span>
                  {capabilities.dimensions.join(' · ')}
                </div>
                <div>
                  <span className="text-muted-foreground">真读过的上游：</span>
                  {capabilities.upstreamModules.join('；')}
                </div>
                <div>
                  <span className="text-muted-foreground">成本口径：</span>
                  {capabilities.costCaliber}
                </div>
                <div>
                  <span className="text-muted-foreground">周转率口径：</span>
                  {capabilities.turnoverCaliber}
                </div>
                <div>
                  <span className="text-muted-foreground">ABC 口径：</span>
                  {capabilities.abcCaliber}
                </div>
                <ul className="space-y-1.5 pt-1">
                  {capabilities.notDelivered.map((item) => (
                    <li key={item} className="text-foreground flex items-start gap-2">
                      <TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                      <span>{item}</span>
                    </li>
                  ))}
                </ul>
                <div className="text-muted-foreground pt-1">{capabilities.note}</div>
              </div>
            </SectionCard>
          )}
        </>
      )}
    </div>
  );
}
