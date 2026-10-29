'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { api } from '@/lib/api';
import {
  DIMENSION_OPTIONS,
  GRANULARITY_OPTIONS,
  PRICE_DIMENSION_OPTIONS,
  amountText,
  changeText,
  concentrationRiskText,
  costToSalesTone,
  crossCurrencyNote,
  cycleTone,
  dataUpdatedNote,
  dataUpdatedText,
  daysText,
  defaultRange,
  emptyText,
  onTimeStatusLabel,
  onTimeTone,
  pageState,
  percentText,
  periodLabel,
  priceStatusLabel,
  priceText,
  quantityText,
  rangeText,
  ratingTone,
  riskLabel,
  riskTone,
  shareBarWidth,
  unavailableLabel,
} from './procurement-analysis-model';
import type {
  ProcurementAnalysisCapabilities,
  ProcurementConcentrationResponse,
  ProcurementCostToSalesResponse,
  ProcurementCycleResponse,
  ProcurementDrilldownResponse,
  ProcurementOnTimeResponse,
  ProcurementPriceVarianceResponse,
  ProcurementSupplierComparisonResponse,
  ProcurementTrendResponse,
} from './procurement-analysis-model';

/**
 * 13.5.2.1 采购分析工作区（卷 13「利润与经营分析」L3 13.5.2 采购分析下的第一格）。
 *
 * <p>蓝图输出：「采购报表 + 趋势 + 供应商对比。」
 *
 * <p><b>数据来自 reporting 服务（13 卷的唯一归属服务）的投影读模型</b>：
 *
 * <ul>
 *   <li>{@code GET reporting/procurement-analysis/trend} —— 采购金额趋势与占比；</li>
 *   <li>{@code GET reporting/procurement-analysis/concentration} —— 供应商集中度 + **风险信号**；</li>
 *   <li>{@code GET reporting/procurement-analysis/price-variance} —— 价格波动；</li>
 *   <li>{@code GET reporting/procurement-analysis/on-time} —— 到货准时率；</li>
 *   <li>{@code GET reporting/procurement-analysis/purchase-cycle} —— 采购周期；</li>
 *   <li>{@code GET reporting/procurement-analysis/cost-to-sales} —— 采购成本占销售额比；</li>
 *   <li>{@code GET reporting/procurement-analysis/suppliers} —— 供应商对比；</li>
 *   <li>{@code GET reporting/procurement-analysis/drilldown} —— 到货流水下钻；</li>
 *   <li>{@code GET reporting/procurement-analysis/capabilities} —— 能力声明。</li>
 * </ul>
 *
 * <p><b>🔴 页面必须做到的五件事</b>：
 *
 * <ol>
 *   <li><b>数据更新时间常驻</b>（卷 13 卷首加粗句「数据延迟要显示」）。</li>
 *   <li><b>「算不出来」≠「数值是 0」</b>：{@code null} 一律显示「—（暂无数据）」，
 *       用**中性**配色，不用警告色。</li>
 *   <li><b>集中度给的是风险信号</b>，不是一个中性的百分比 —— 风险等级 + 风险说明常驻。</li>
 *   <li><b>多币种绝不相加</b>：跨币种的合计不存在。</li>
 *   <li><b>能力声明常驻</b>：「哪些确实拿不到」必须写在页面上，并写明归属模块号。</li>
 * </ol>
 */
export function AnalyticsProcurementAnalysisWorkspace() {
  const today = new Date().toISOString().slice(0, 10);
  const initialRange = defaultRange(today);

  const [from, setFrom] = useState(initialRange.from);
  const [to, setTo] = useState(initialRange.to);
  const [granularity, setGranularity] = useState('DAY');
  const [dimension, setDimension] = useState('ALL');
  const [priceDimension, setPriceDimension] = useState('SKU');
  const [currency, setCurrency] = useState('');

  const [trend, setTrend] = useState<ProcurementTrendResponse | null>(null);
  const [concentration, setConcentration] = useState<ProcurementConcentrationResponse | null>(null);
  const [priceVariance, setPriceVariance] = useState<ProcurementPriceVarianceResponse | null>(null);
  const [onTime, setOnTime] = useState<ProcurementOnTimeResponse | null>(null);
  const [cycle, setCycle] = useState<ProcurementCycleResponse | null>(null);
  const [costToSales, setCostToSales] = useState<ProcurementCostToSalesResponse | null>(null);
  const [suppliers, setSuppliers] = useState<ProcurementSupplierComparisonResponse | null>(null);
  const [drilldown, setDrilldown] = useState<ProcurementDrilldownResponse | null>(null);
  const [capabilities, setCapabilities] = useState<ProcurementAnalysisCapabilities | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  const query = useMemo(() => {
    const params = new URLSearchParams({ from, to, granularity });
    if (currency) params.set('currency', currency);
    return params.toString();
  }, [from, to, granularity, currency]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [t, c, pv, ot, pc, cts, sup, dr, caps] = await Promise.all([
        api<ProcurementTrendResponse>(`reporting/procurement-analysis/trend?${query}&dimension=${dimension}`),
        api<ProcurementConcentrationResponse>(`reporting/procurement-analysis/concentration?${query}`),
        api<ProcurementPriceVarianceResponse>(
          `reporting/procurement-analysis/price-variance?${query}&dimension=${priceDimension}`,
        ),
        api<ProcurementOnTimeResponse>(`reporting/procurement-analysis/on-time?from=${from}&to=${to}`),
        api<ProcurementCycleResponse>(`reporting/procurement-analysis/purchase-cycle?from=${from}&to=${to}`),
        api<ProcurementCostToSalesResponse>(`reporting/procurement-analysis/cost-to-sales?from=${from}&to=${to}`),
        api<ProcurementSupplierComparisonResponse>(
          `reporting/procurement-analysis/suppliers?from=${from}&to=${to}${currency ? `&currency=${currency}` : ''}&size=20`,
        ),
        api<ProcurementDrilldownResponse>(
          `reporting/procurement-analysis/drilldown?from=${from}&to=${to}${currency ? `&currency=${currency}` : ''}&size=20`,
        ),
        api<ProcurementAnalysisCapabilities>('reporting/procurement-analysis/capabilities'),
      ]);
      setTrend(t);
      setConcentration(c);
      setPriceVariance(pv);
      setOnTime(ot);
      setCycle(pc);
      setCostToSales(cts);
      setSuppliers(sup);
      setDrilldown(dr);
      setCapabilities(caps);
    } catch (e) {
      setError(e instanceof Error ? e.message : '读取失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [query, dimension, priceDimension, from, to, currency]);

  useEffect(() => {
    void load();
  }, [load]);

  const state = pageState(drilldown, error);
  const warnings = useMemo(
    () => [
      ...(trend?.warnings ?? []),
      ...(concentration?.warnings ?? []),
      ...(priceVariance?.warnings ?? []),
      ...(onTime?.warnings ?? []),
      ...(cycle?.warnings ?? []),
      ...(costToSales?.warnings ?? []),
      ...(suppliers?.warnings ?? []),
    ],
    [trend, concentration, priceVariance, onTime, cycle, costToSales, suppliers],
  );

  return (
    <div className="space-y-4">
      {/* ── 数据更新时间（常驻）────────────────────────────────────────── */}
      <div className="border-border bg-muted/40 flex flex-wrap items-center justify-between gap-3 rounded-lg border px-4 py-3">
        <div className="text-copy-13 text-foreground">
          <span className="font-medium">{dataUpdatedText(trend)}</span>
          <span className="text-muted-foreground ml-2">{dataUpdatedNote(trend)}</span>
        </div>
        <div className="text-copy-13 text-muted-foreground flex items-center gap-3">
          <span>{rangeText(from, to)}</span>
          <Button variant="outline" size="sm" disabled={loading} onClick={() => void load()}>
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            刷新
          </Button>
        </div>
      </div>

      {/* ── 口径说明（常驻）────────────────────────────────────────────── */}
      {trend && (
        <div className="border-border bg-muted/40 text-copy-13 text-muted-foreground rounded-lg border px-4 py-3">
          <div>{trend.caliberNote}</div>
          {cycle && <div className="mt-1">{cycle.caliberNote}</div>}
          {onTime && <div className="mt-1">{onTime.caliberNote}</div>}
          {costToSales && <div className="mt-1">{costToSales.caliberNote}</div>}
          {crossCurrencyNote(trend.totals, trend.totalCurrency) && (
            <div className="mt-1">{crossCurrencyNote(trend.totals, trend.totalCurrency)}</div>
          )}
        </div>
      )}

      {/* ── 时间范围与筛选 ─────────────────────────────────────────────── */}
      <SectionCard
        title="时间范围与筛选"
        description="时间范围决定采购金额趋势、集中度与准时率的窗口；币种筛选后金额与占比只算该币种（多币种绝不相加）。"
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
            币种（空 = 全部）
            <input
              type="text"
              value={currency}
              placeholder="USD"
              maxLength={3}
              onChange={(e) => setCurrency(e.target.value.toUpperCase())}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
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
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            占比拆解维度
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
            价格波动维度
            <select
              value={priceDimension}
              onChange={(e) => setPriceDimension(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              {PRICE_DIMENSION_OPTIONS.map((option) => (
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
          <EmptyState title="正在读取采购分析…" description="读取期间页面上的「数据截至」仍然有效。" />
        </SectionCard>
      )}

      {state === 'empty' && (
        <SectionCard>
          <EmptyState title="没有采购分析数据" description={emptyText(drilldown)} />
        </SectionCard>
      )}

      {state === 'ready' && trend && (
        <>
          {/* ── ① 采购金额趋势与占比 ─────────────────────────────────────── */}
          <SectionCard
            title="采购金额趋势与占比"
            description="一行 = 一个期间 × 一个币种。**多币种绝不相加** —— 跨币种的合计只在单币种时存在。"
          >
            {trend.totals.length === 0 ? (
              <p className="text-copy-13 text-muted-foreground">
                这个窗口里没有带金额的到货流水 —— 先刷新投影。
              </p>
            ) : (
              <>
                <div className="mb-3 flex flex-wrap gap-3">
                  {trend.totals.map((total) => (
                    <div
                      key={total.currency}
                      className="border-border bg-muted/40 rounded-md border px-3 py-2"
                    >
                      <div className="text-copy-13 text-muted-foreground">{total.currency} 合计</div>
                      <div className="text-copy-15 font-medium tabular-nums">
                        {amountText(total.amount, total.currency)}
                      </div>
                      <div className="text-copy-13 text-muted-foreground">
                        {total.factCount} 条流水
                      </div>
                    </div>
                  ))}
                  {trend.totalAmount === null && trend.totals.length > 1 && (
                    <div className="border-border bg-muted/40 rounded-md border px-3 py-2">
                      <div className="text-copy-13 text-muted-foreground">跨币种总计</div>
                      <div className="text-copy-15 font-medium">—（跨币种不可加）</div>
                    </div>
                  )}
                </div>
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-sm">
                    <thead className="text-copy-13 text-muted-foreground">
                      <tr className="border-border border-b">
                        <th className="py-2 pr-3 font-medium">期间</th>
                        <th className="py-2 pr-3 font-medium">币种</th>
                        <th className="py-2 pr-3 text-right font-medium">采购金额</th>
                        <th className="py-2 pr-3 text-right font-medium">数量</th>
                        <th className="py-2 pr-3 text-right font-medium">流水数</th>
                      </tr>
                    </thead>
                    <tbody>
                      {trend.points.map((point) => (
                        <tr
                          key={`${point.period}|${point.currency}`}
                          className="border-border border-b"
                        >
                          <td className="py-2 pr-3">{periodLabel(point.period, granularity)}</td>
                          <td className="py-2 pr-3">{point.currency}</td>
                          <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                            {amountText(point.amount, point.currency)}
                          </td>
                          <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                            {quantityText(point.quantity)}
                          </td>
                          <td className="py-2 pr-3 text-right tabular-nums">{point.factCount}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {trend.shares.length > 0 && (
                  <div className="mt-4 overflow-x-auto">
                    <div className="text-copy-13 text-muted-foreground mb-2">
                      按维度拆解的占比（分母 = **同币种**的窗口合计）
                    </div>
                    <table className="w-full text-left text-sm">
                      <thead className="text-copy-13 text-muted-foreground">
                        <tr className="border-border border-b">
                          <th className="py-2 pr-3 font-medium">维度</th>
                          <th className="py-2 pr-3 font-medium">币种</th>
                          <th className="py-2 pr-3 text-right font-medium">金额</th>
                          <th className="py-2 pr-3 text-right font-medium">占比</th>
                          <th className="py-2 pr-3 font-medium">结构</th>
                        </tr>
                      </thead>
                      <tbody>
                        {trend.shares.map((share) => (
                          <tr
                            key={`${share.dimensionKey}|${share.currency}`}
                            className="border-border border-b"
                          >
                            <td className="py-2 pr-3">{share.dimensionLabel}</td>
                            <td className="py-2 pr-3">{share.currency}</td>
                            <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                              {amountText(share.amount, share.currency)}
                            </td>
                            <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                              {percentText(share.share)}
                            </td>
                            <td className="py-2 pr-3">
                              {share.share === null ? (
                                <span className="text-copy-13 text-muted-foreground">—</span>
                              ) : (
                                <div className="bg-muted h-2 w-32 overflow-hidden rounded-full">
                                  <div
                                    className="bg-primary h-2 rounded-full"
                                    style={{ width: `${shareBarWidth(share.share)}%` }}
                                  />
                                </div>
                              )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </>
            )}
          </SectionCard>

          {/* ── ② 供应商集中度 + 风险信号 ─────────────────────────────────── */}
          {concentration && (
            <SectionCard
              title="供应商集中度"
              description="🔴 集中度**不只是中性百分比**：它回答的是「断供时业务会不会被打穿」。逐币种分段 —— 集中度不跨币种合并。"
            >
              <div className="mb-3 flex flex-wrap items-center gap-3">
                <Badge tone={riskTone(concentration.riskLevel)}>
                  集中度风险：{riskLabel(concentration.riskLevel)}
                </Badge>
                {concentration.topSupplierName && (
                  <span className="text-copy-13 text-muted-foreground">
                    最大供应商：{concentration.topSupplierName}（
                    {percentText(concentration.topSupplierShare)}）
                  </span>
                )}
                <span className="text-copy-13 text-muted-foreground">
                  风险线：单一供应商 ≥ {percentText(concentration.singleSupplierShareThreshold)} ⇒
                  高风险；前 {concentration.topN} 家合计 ≥{' '}
                  {percentText(concentration.topShareThreshold)} ⇒ 中风险
                </span>
              </div>
              <p className="text-copy-13 text-foreground mb-3">{concentrationRiskText(concentration)}</p>
              {concentration.buckets.map((bucket) => (
                <div key={bucket.currency} className="mb-4">
                  <div className="text-copy-13 text-muted-foreground mb-1 flex flex-wrap gap-2">
                    <span>
                      {bucket.currency} 合计 {amountText(bucket.totalAmount, bucket.currency)}
                    </span>
                    <span>· {bucket.supplierCount} 家供应商</span>
                    <span>· HHI {bucket.hhi?.toFixed(2) ?? '—'}</span>
                    <Badge tone={riskTone(bucket.currencyRiskLevel)}>
                      {riskLabel(bucket.currencyRiskLevel)}
                    </Badge>
                  </div>
                  <div className="overflow-x-auto">
                    <table className="w-full text-left text-sm">
                      <thead className="text-copy-13 text-muted-foreground">
                        <tr className="border-border border-b">
                          <th className="py-2 pr-3 font-medium">供应商</th>
                          <th className="py-2 pr-3 text-right font-medium">采购金额</th>
                          <th className="py-2 pr-3 text-right font-medium">占比</th>
                          <th className="py-2 pr-3 text-right font-medium">累计占比</th>
                          <th className="py-2 pr-3 text-right font-medium">准时率</th>
                          <th className="py-2 pr-3 text-right font-medium">评级</th>
                        </tr>
                      </thead>
                      <tbody>
                        {bucket.suppliers.map((supplier) => (
                          <tr
                            key={`${bucket.currency}|${supplier.supplierId}`}
                            className="border-border border-b"
                          >
                            <td className="py-2 pr-3">{supplier.supplierName}</td>
                            <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                              {amountText(supplier.amount, bucket.currency)}
                            </td>
                            <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                              {percentText(supplier.share)}
                            </td>
                            <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                              {percentText(supplier.cumulativeShare)}
                            </td>
                            <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                              {percentText(supplier.onTimeRate)}
                            </td>
                            <td className="py-2 pr-3 text-right">
                              {supplier.ratingGrade ? (
                                <Badge tone={ratingTone(supplier.ratingGrade)}>
                                  {supplier.ratingGrade}
                                </Badge>
                              ) : (
                                <span className="text-copy-13 text-muted-foreground">未评级</span>
                              )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              ))}
            </SectionCard>
          )}

          {/* ── ③ 价格波动 ───────────────────────────────────────────────── */}
          {priceVariance && (
            <SectionCard
              title="采购价格波动"
              description="当前期间 vs **上一等长期间**的加权均价（金额 ÷ 数量）。逐币种分列 —— 跨币种的价格不可比。"
            >
              {priceVariance.rows.length === 0 ? (
                <p className="text-copy-13 text-muted-foreground">
                  这个窗口里没有可算价格的采购流水 —— 先刷新投影。
                </p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-sm">
                    <thead className="text-copy-13 text-muted-foreground">
                      <tr className="border-border border-b">
                        <th className="py-2 pr-3 font-medium">对象</th>
                        <th className="py-2 pr-3 font-medium">币种</th>
                        <th className="py-2 pr-3 text-right font-medium">当前均价</th>
                        <th className="py-2 pr-3 text-right font-medium">上一期均价</th>
                        <th className="py-2 pr-3 text-right font-medium">变化</th>
                        <th className="py-2 pr-3 font-medium">可比性</th>
                      </tr>
                    </thead>
                    <tbody>
                      {priceVariance.rows.map((row) => (
                        <tr
                          key={`${row.dimensionKey}|${row.currency}`}
                          className="border-border border-b"
                        >
                          <td className="py-2 pr-3">{row.dimensionLabel}</td>
                          <td className="py-2 pr-3">{row.currency}</td>
                          <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                            {priceText(row.currentAveragePrice, row.currency)}
                          </td>
                          <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                            {priceText(row.previousAveragePrice, row.currency)}
                          </td>
                          <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                            {changeText(row.changeRate)}
                          </td>
                          <td className="text-copy-13 text-muted-foreground py-2 pr-3 max-w-xs">
                            {priceStatusLabel(row.status)}
                            {row.note ? ` · ${row.note}` : ''}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </SectionCard>
          )}

          {/* ── ④ 到货准时率 ─────────────────────────────────────────────── */}
          {onTime && (
            <SectionCard
              title="到货准时率"
              description="判定归 trade 6.3.2.2（承诺到货日 vs 实际到货日），本模块只搬运。分母 = **已到货**流水；PENDING 不进分母。"
            >
              <div className="mb-3 flex flex-wrap items-center gap-3">
                <Badge tone={onTimeTone(onTime.onTimeRate)}>
                  准时率 {percentText(onTime.onTimeRate)}
                </Badge>
                <span className="text-copy-13 text-muted-foreground">
                  目标 {percentText(onTime.targetRate)} ·
                  {onTime.meetsTarget === null
                    ? '暂无数据'
                    : onTime.meetsTarget
                      ? '已达标'
                      : '未达标'}
                </span>
                <span className="text-copy-13 text-muted-foreground">
                  已到货 {onTime.denominatorCount} · 准时 {onTime.onTimeCount} · 逾期{' '}
                  {onTime.lateCount} · 未到货 {onTime.pendingCount}
                </span>
              </div>
              {onTime.suppliers.length > 0 && (
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-sm">
                    <thead className="text-copy-13 text-muted-foreground">
                      <tr className="border-border border-b">
                        <th className="py-2 pr-3 font-medium">供应商</th>
                        <th className="py-2 pr-3 text-right font-medium">准时率</th>
                        <th className="py-2 pr-3 text-right font-medium">准时 / 到货</th>
                        <th className="py-2 pr-3 text-right font-medium">逾期</th>
                        <th className="py-2 pr-3 text-right font-medium">评级</th>
                      </tr>
                    </thead>
                    <tbody>
                      {onTime.suppliers.map((supplier) => (
                        <tr
                          key={supplier.supplierId}
                          className="border-border border-b"
                        >
                          <td className="py-2 pr-3">{supplier.supplierName}</td>
                          <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                            {percentText(supplier.onTimeRate)}
                          </td>
                          <td className="py-2 pr-3 text-right tabular-nums">
                            {supplier.onTimeCount} / {supplier.denominatorCount}
                          </td>
                          <td className="py-2 pr-3 text-right tabular-nums">{supplier.lateCount}</td>
                          <td className="py-2 pr-3 text-right">
                            {supplier.ratingGrade ? (
                              <Badge tone={ratingTone(supplier.ratingGrade)}>
                                {supplier.ratingGrade}
                              </Badge>
                            ) : (
                              <span className="text-copy-13 text-muted-foreground">未评级</span>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              {onTime.lateRows.length > 0 && (
                <div className="mt-4 overflow-x-auto">
                  <div className="text-copy-13 text-muted-foreground mb-2">逾期待到货清单</div>
                  <table className="w-full text-left text-sm">
                    <thead className="text-copy-13 text-muted-foreground">
                      <tr className="border-border border-b">
                        <th className="py-2 pr-3 font-medium">采购单</th>
                        <th className="py-2 pr-3 font-medium">供应商</th>
                        <th className="py-2 pr-3 font-medium">承诺到货日</th>
                        <th className="py-2 pr-3 text-right font-medium">逾期天数</th>
                        <th className="py-2 pr-3 text-right font-medium">金额</th>
                      </tr>
                    </thead>
                    <tbody>
                      {onTime.lateRows.map((row) => (
                        <tr key={row.factId} className="border-border border-b">
                          <td className="py-2 pr-3">{row.sourceDocNo}</td>
                          <td className="py-2 pr-3">{row.supplierName}</td>
                          <td className="py-2 pr-3">{row.promisedArrivalDate}</td>
                          <td className="py-2 pr-3 text-right tabular-nums">{row.lateDays ?? '—'}</td>
                          <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                            {amountText(row.amount, row.currency)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </SectionCard>
          )}

          {/* ── ⑤ 采购周期 ───────────────────────────────────────────────── */}
          {cycle && (
            <SectionCard
              title="采购周期"
              description="🔴 到货日 − 下单日 = **实际采购周期**（不是「承诺到货日 − 下单日」那个承诺交期）。来源：catalog 6.1.1.1 的到货记录。"
            >
              <div className="mb-3 flex flex-wrap items-center gap-3">
                <Badge tone={cycleTone(cycle.averageCycleDays)}>
                  平均 {daysText(cycle.averageCycleDays)}
                </Badge>
                <span className="text-copy-13 text-muted-foreground">
                  中位 {daysText(cycle.medianCycleDays)} · P90 {daysText(cycle.p90CycleDays)} · 样本{' '}
                  {cycle.sampleCount}
                </span>
                <span className="text-copy-13 text-muted-foreground">
                  目标 {daysText(cycle.targetDays)}
                </span>
              </div>
              {cycle.suppliers.length > 0 && (
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-sm">
                    <thead className="text-copy-13 text-muted-foreground">
                      <tr className="border-border border-b">
                        <th className="py-2 pr-3 font-medium">供应商</th>
                        <th className="py-2 pr-3 text-right font-medium">样本</th>
                        <th className="py-2 pr-3 text-right font-medium">平均</th>
                        <th className="py-2 pr-3 text-right font-medium">中位</th>
                        <th className="py-2 pr-3 text-right font-medium">P90</th>
                        <th className="py-2 pr-3 text-right font-medium">最近一次</th>
                      </tr>
                    </thead>
                    <tbody>
                      {cycle.suppliers.map((supplier) => (
                        <tr key={supplier.supplierId} className="border-border border-b">
                          <td className="py-2 pr-3">{supplier.supplierName}</td>
                          <td className="py-2 pr-3 text-right tabular-nums">{supplier.sampleCount}</td>
                          <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                            {daysText(supplier.averageCycleDays)}
                          </td>
                          <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                            {daysText(supplier.medianCycleDays)}
                          </td>
                          <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                            {daysText(supplier.p90CycleDays)}
                          </td>
                          <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                            {daysText(supplier.latestCycleDays)}
                            {supplier.latestReceivedDate ? ` · ${supplier.latestReceivedDate}` : ''}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </SectionCard>
          )}

          {/* ── ⑥ 采购成本占销售额比 ─────────────────────────────────────── */}
          {costToSales && (
            <SectionCard
              title="采购成本占销售额比"
              description="逐币种：采购到货金额 ÷ **同币种**销售额。分母取 13.1.1.1 已投影的 SALES_AMOUNT（本模块不重算销售额）。"
            >
              {!costToSales.available && (
                <p className="text-copy-13 text-muted-foreground mb-3">
                  销售额分母**尚不可得** —— {costToSales.impact}
                </p>
              )}
              {costToSales.rows.length === 0 ? (
                <p className="text-copy-13 text-muted-foreground">
                  这个窗口里没有采购流水 —— 先刷新投影。
                </p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-sm">
                    <thead className="text-copy-13 text-muted-foreground">
                      <tr className="border-border border-b">
                        <th className="py-2 pr-3 font-medium">币种</th>
                        <th className="py-2 pr-3 text-right font-medium">采购金额</th>
                        <th className="py-2 pr-3 text-right font-medium">销售额</th>
                        <th className="py-2 pr-3 text-right font-medium">成本占销售额比</th>
                        <th className="py-2 pr-3 font-medium">说明</th>
                      </tr>
                    </thead>
                    <tbody>
                      {costToSales.rows.map((row) => (
                        <tr key={row.currency} className="border-border border-b">
                          <td className="py-2 pr-3">{row.currency}</td>
                          <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                            {amountText(row.purchaseAmount, row.currency)}
                          </td>
                          <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                            {amountText(row.salesAmount, row.currency)}
                          </td>
                          <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                            <Badge tone={costToSalesTone(row.ratio)}>{percentText(row.ratio)}</Badge>
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
            </SectionCard>
          )}

          {/* ── ⑦ 供应商对比 ─────────────────────────────────────────────── */}
          {suppliers && (
            <SectionCard
              title="供应商对比"
              description="采购贡献 + 准时率 + 采购周期 + **供应商评级**（6.1.1.3 的最新一期）。多币种时**不跨币种相加**。"
            >
              {suppliers.rows.length === 0 ? (
                <p className="text-copy-13 text-muted-foreground">
                  没有可对比的供应商 —— 先刷新投影。
                </p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-sm">
                    <thead className="text-copy-13 text-muted-foreground">
                      <tr className="border-border border-b">
                        <th className="py-2 pr-3 font-medium">供应商</th>
                        <th className="py-2 pr-3 font-medium">合作等级</th>
                        <th className="py-2 pr-3 text-right font-medium">采购金额</th>
                        <th className="py-2 pr-3 text-right font-medium">占比</th>
                        <th className="py-2 pr-3 text-right font-medium">准时率</th>
                        <th className="py-2 pr-3 text-right font-medium">平均周期</th>
                        <th className="py-2 pr-3 text-right font-medium">评级</th>
                        <th className="py-2 pr-3 font-medium">说明</th>
                      </tr>
                    </thead>
                    <tbody>
                      {suppliers.rows.map((row) => (
                        <tr key={row.supplierId} className="border-border border-b">
                          <td className="py-2 pr-3">{row.supplierName}</td>
                          <td className="py-2 pr-3">{row.cooperationLevel || '—'}</td>
                          <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                            {amountText(row.purchaseAmount, row.purchaseCurrency)}
                          </td>
                          <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                            {percentText(row.purchaseShare)}
                          </td>
                          <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                            {percentText(row.onTimeRate)}
                          </td>
                          <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                            {daysText(row.averageCycleDays)}
                          </td>
                          <td className="py-2 pr-3 text-right">
                            {row.ratingGrade ? (
                              <Badge tone={ratingTone(row.ratingGrade)}>
                                {row.ratingGrade}
                                {row.ratingScore !== null ? ` · ${row.ratingScore}` : ''}
                              </Badge>
                            ) : (
                              <span className="text-copy-13 text-muted-foreground">未评级</span>
                            )}
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
            </SectionCard>
          )}

          {/* ── ⑧ 下钻 ───────────────────────────────────────────────────── */}
          {drilldown && drilldown.rows.length > 0 && (
            <SectionCard
              title="到货流水下钻"
              description={`一行 = 一次到货流水（共 ${drilldown.total} 条）。没有金额的行是「—」，不在任何合计里。`}
            >
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="text-copy-13 text-muted-foreground">
                    <tr className="border-border border-b">
                      <th className="py-2 pr-3 font-medium">日期</th>
                      <th className="py-2 pr-3 font-medium">采购单</th>
                      <th className="py-2 pr-3 font-medium">供应商</th>
                      <th className="py-2 pr-3 font-medium">SKU</th>
                      <th className="py-2 pr-3 text-right font-medium">数量</th>
                      <th className="py-2 pr-3 text-right font-medium">单价</th>
                      <th className="py-2 pr-3 text-right font-medium">金额</th>
                      <th className="py-2 pr-3 font-medium">准时</th>
                    </tr>
                  </thead>
                  <tbody>
                    {drilldown.rows.map((row) => (
                      <tr key={row.factId} className="border-border border-b">
                        <td className="py-2 pr-3">{row.occurredOn}</td>
                        <td className="py-2 pr-3">{row.sourceDocNo}</td>
                        <td className="py-2 pr-3">{row.supplierName}</td>
                        <td className="py-2 pr-3">{row.skuCode}</td>
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          {quantityText(row.quantity)}
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          {priceText(row.unitPrice, row.currency)}
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          {amountText(row.amount, row.currency)}
                        </td>
                        <td className="py-2 pr-3">
                          {onTimeStatusLabel(row.onTimeStatus)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </SectionCard>
          )}

          {/* ── ⑨ 能力声明 ───────────────────────────────────────────────── */}
          {capabilities && (
            <SectionCard
              title="能力声明"
              description="哪些指标真接得通、哪些**确实拿不到**。接不通的项给出归属模块号 —— 让人知道「去哪维护」，而不是只看到一个 0。"
            >
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="text-copy-13 text-muted-foreground">
                    <tr className="border-border border-b">
                      <th className="py-2 pr-3 font-medium">指标</th>
                      <th className="py-2 pr-3 font-medium">状态</th>
                      <th className="py-2 pr-3 font-medium">口径</th>
                      <th className="py-2 pr-3 font-medium">说明</th>
                    </tr>
                  </thead>
                  <tbody>
                    {capabilities.sections.map((section) => (
                      <tr key={section.key} className="border-border border-b">
                        <td className="py-2 pr-3">{section.label}</td>
                        <td className="py-2 pr-3">
                          <Badge tone={section.available ? 'success' : 'neutral'}>
                            {section.available ? '已接通' : unavailableLabel(section.ownerModule)}
                          </Badge>
                        </td>
                        <td className="text-copy-13 text-muted-foreground py-2 pr-3 max-w-xs">
                          {section.caliber}
                        </td>
                        <td className="text-copy-13 text-muted-foreground py-2 pr-3 max-w-xs">
                          {section.available ? section.evidence : section.reason}
                          {section.available ? '' : ` · 影响：${section.impact}`}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="text-copy-13 text-muted-foreground mt-3 space-y-1">
                <div>{capabilities.upstreamNote}</div>
                <div>{capabilities.concentrationNote}</div>
                <div>{capabilities.cycleNote}</div>
                <div>{capabilities.dataDependencyNote}</div>
              </div>
            </SectionCard>
          )}
        </>
      )}
    </div>
  );
}
