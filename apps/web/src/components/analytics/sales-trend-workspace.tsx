'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { api } from '@/lib/api';
import {
  anomaliesOf,
  anomalyDirectionLabel,
  anomalyTone,
  availabilityLabel,
  bucketLabel,
  caliberNotesOf,
  comparisonCellText,
  comparisonGrowthText,
  comparisonYearText,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  dimensionLabel,
  dimensionOptions,
  emptyReasonLabel,
  emptyText,
  eventText,
  eventTypeLabel,
  eventsOf,
  eventsOfResponse,
  effectiveRangeText,
  formatDecimal,
  granularityLabel,
  granularityOptions,
  metricDefinitionsOf,
  metricFormulaText,
  metricUnitLabel,
  notDeliveredLines,
  pageState,
  periodOverPeriodNote,
  pointOf,
  rangeText,
  refreshResultsOf,
  refreshStatusLabel,
  refreshStatusTone,
  ruleNotes,
  seriesOf,
  severityTone,
  statusLabel,
  upstreamModules,
  warningsOf,
  yearOverYearNote,
} from './sales-trend-model';
import type {
  SalesTrendCapabilities,
  SalesTrendComparisonResponse,
  SalesTrendEventResponse,
  SalesTrendRefreshResult,
  SalesTrendResponse,
} from './sales-trend-model';

/**
 * 13.2.1.1 销量趋势工作区（卷 13「利润与经营分析」L3 13.2 销量分析下的第一格）。
 *
 * <p>蓝图输出：「趋势图 + 对比视图 + 异常标注。」
 *
 * <p><b>数据来自 reporting 服务（13 卷的唯一归属服务）的投影读模型</b>：
 *
 * <ul>
 *   <li>{@code GET reporting/sales-trend} —— 趋势点列 + 环比 / 同比 + 异常标注；</li>
 *   <li>{@code GET reporting/sales-trend/comparison} —— 多维对比（同比 / 环比 / 店铺间 / 产品间）；</li>
 *   <li>{@code GET reporting/sales-trend/events} —— 异常日期标注；</li>
 *   <li>{@code GET reporting/sales-trend/capabilities} —— 能力声明；</li>
 *   <li>{@code GET reporting/sales-trend/metrics} —— 指标口径目录（口径唯一的展示形态）；</li>
 *   <li>{@code POST reporting/sales-trend/refresh} —— 刷新销量趋势投影。</li>
 * </ul>
 *
 * <p><b>🔴 页面必须做到的五件事</b>：
 *
 * <ol>
 *   <li><b>「算不出来」≠「等于 0」</b>：{@code null} 的取值显示「—」/「不可比」+ 原因，
 *       <b>绝不用 0 顶替</b>（假 0 会被读成「这天卖了 0」）。</li>
 *   <li><b>异常必须能关联事件</b>（蓝图关系句加粗要求）：异常点带事件清单；
 *       查不到原因时服务端明说「<b>查不到原因</b>」，页面照原样显示。</li>
 *   <li><b>多币种不相加</b>：每个币种各自一条序列，界面上永远没有跨币种的「总计」。</li>
 *   <li><b>数据更新时间常驻</b>（卷 13 卷首加粗句「数据延迟要显示」）：加载中 / 出错时也显示。</li>
 *   <li><b>能力声明常驻</b>：「哪些口径确实拿不到」必须写在页面上。</li>
 * </ol>
 */
export function AnalyticsSalesTrendWorkspace() {
  const today = new Date().toISOString().slice(0, 10);
  const initialRange = defaultRange(today);

  const [from, setFrom] = useState(initialRange.from);
  const [to, setTo] = useState(initialRange.to);
  const [granularity, setGranularity] = useState('DAY');
  const [timezone, setTimezone] = useState('UTC');
  const [dimension, setDimension] = useState('TOTAL');
  const [baseCurrency, setBaseCurrency] = useState('');

  const [data, setData] = useState<SalesTrendResponse | null>(null);
  const [comparison, setComparison] = useState<SalesTrendComparisonResponse | null>(null);
  const [events, setEvents] = useState<SalesTrendEventResponse | null>(null);
  const [capabilities, setCapabilities] = useState<SalesTrendCapabilities | null>(null);
  const [refreshResults, setRefreshResults] = useState<SalesTrendRefreshResult[] | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const query = useMemo(() => {
    const params = new URLSearchParams({ from, to, granularity, timezone });
    if (baseCurrency) params.set('baseCurrency', baseCurrency);
    return params.toString();
  }, [from, to, granularity, timezone, baseCurrency]);

  const comparisonQuery = useMemo(() => {
    const params = new URLSearchParams({ from, to, granularity, timezone, dimension });
    if (baseCurrency) params.set('baseCurrency', baseCurrency);
    return params.toString();
  }, [from, to, granularity, timezone, dimension, baseCurrency]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [trend, caps, eventList] = await Promise.all([
        api<SalesTrendResponse>(`reporting/sales-trend?${query}`),
        api<SalesTrendCapabilities>('reporting/sales-trend/capabilities'),
        api<SalesTrendEventResponse>(`reporting/sales-trend/events?${query}`),
      ]);
      setData(trend);
      setCapabilities(caps);
      setEvents(eventList);
    } catch (e) {
      setData(null);
      setError(e instanceof Error ? e.message : '读取销量趋势失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [query]);

  const loadComparison = useCallback(async () => {
    try {
      const rows = await api<SalesTrendComparisonResponse>(
        `reporting/sales-trend/comparison?${comparisonQuery}`,
      );
      setComparison(rows);
    } catch {
      // 🔴 对比视图读不到**不拖垮整页**：趋势已经读到了，
      //    对比那一块自己显示「读不到」—— 两块的状态分开说。
      setComparison(null);
    }
  }, [comparisonQuery]);

  const refreshProjection = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const params = new URLSearchParams({ from, to, granularity, timezone });
      const results = await api<SalesTrendRefreshResult[]>(
        `reporting/sales-trend/refresh?${params}`,
        'POST',
      );
      setRefreshResults(results);
      // 刷完立刻重读 —— 投影变了，页面上的数必须跟着变。
      await load();
      await loadComparison();
    } catch (e) {
      setError(e instanceof Error ? e.message : '刷新销量趋势投影失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [from, to, granularity, timezone, load, loadComparison]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void loadComparison();
  }, [loadComparison]);

  const state = pageState(data, error);
  const warnings = useMemo(() => warningsOf(data), [data]);
  const caliberNotes = useMemo(() => caliberNotesOf(data), [data]);
  const definitions = useMemo(() => metricDefinitionsOf(data), [data]);
  const anomalies = useMemo(() => anomaliesOf(data), [data]);

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
          <Button variant="outline" size="sm" disabled={loading || busy} onClick={() => void load()}>
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            重读
          </Button>
        </div>
      </div>

      {/* ── 必须常驻显示的提醒（多币种 / 折算 / 维度口径）────────────────── */}
      {warnings.length > 0 && (
        <div className="border-border bg-muted/40 text-copy-13 text-muted-foreground space-y-1 rounded-lg border px-4 py-3">
          {warnings.map((warning, index) => (
            <p key={index}>{warning}</p>
          ))}
        </div>
      )}

      {/* ── 时间范围与筛选 ─────────────────────────────────────────────── */}
      <SectionCard
        title="时间范围与筛选"
        description="时间范围决定趋势窗口；粒度决定分桶（周按 ISO 周、月按自然月）；时区决定跨时区汇总按谁的日历日算，响应里会显示所用时区。"
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
            粒度
            <select
              value={granularity}
              onChange={(e) => setGranularity(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              {granularityOptions(capabilities).length > 0
                ? granularityOptions(capabilities).map((value) => (
                    <option key={value} value={value}>
                      {granularityLabel(value)}
                    </option>
                  ))
                : GRANULARITY_FALLBACK.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
            </select>
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            时区
            <input
              type="text"
              value={timezone}
              placeholder="UTC / Asia/Shanghai"
              onChange={(e) => setTimezone(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            对比维度
            <select
              value={dimension}
              onChange={(e) => setDimension(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              {dimensionOptions(capabilities).length > 0
                ? dimensionOptions(capabilities).map((value) => (
                    <option key={value} value={value}>
                      {dimensionLabel(value)}
                    </option>
                  ))
                : DIMENSION_FALLBACK.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
            </select>
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            本位币（可选）
            <input
              type="text"
              value={baseCurrency}
              placeholder="不填 = 按币种分别报，不跨币种相加"
              onChange={(e) => setBaseCurrency(e.target.value.toUpperCase())}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
          </label>
          <Button variant="outline" size="sm" disabled={busy} onClick={() => void refreshProjection()}>
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            刷新销量趋势投影
          </Button>
        </div>
        <p className="text-copy-13 text-muted-foreground mt-3">{effectiveRangeText(data)}</p>
      </SectionCard>

      {/* ── 刷新结果 ───────────────────────────────────────────────────── */}
      {refreshResults && (
        <SectionCard
          title="投影刷新结果"
          description="上游读不到就是读不到（失败），不会返回空集合冒充「上游没有数据」。"
        >
          <div className="space-y-2">
            {refreshResultsOf(refreshResults).map((result) => (
              <div key={result.source} className="border-border rounded-lg border px-4 py-3">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-copy-14 text-foreground font-medium">{result.source}</span>
                  <Badge tone={refreshStatusTone(result.status)}>
                    {refreshStatusLabel(result.status)}
                  </Badge>
                  <span className="text-copy-13 text-muted-foreground">
                    拉取 {result.fetched} 行 / 落库 {result.projected} 行
                  </span>
                </div>
                <p className="text-copy-13 text-muted-foreground mt-1">{result.note}</p>
              </div>
            ))}
          </div>
        </SectionCard>
      )}

      {/* ── 错误态 ─────────────────────────────────────────────────────── */}
      {state === 'error' && (
        <SectionCard>
          <EmptyState
            title="销量趋势读不到"
            description={`${error}（这是「读不到」，不是「没有数据」）—— 上游不可达或没有读权限时就会出现。`}
            action={
              <Button variant="outline" size="sm" disabled={loading || busy} onClick={() => void load()}>
                <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
                重试
              </Button>
            }
          />
        </SectionCard>
      )}

      {/* ── 加载态 ─────────────────────────────────────────────────────── */}
      {state === 'loading' && (
        <SectionCard>
          <EmptyState title="正在读取销量趋势…" description="读取期间页面上的「数据截至」仍然有效。" />
        </SectionCard>
      )}

      {/* ── 空态 ───────────────────────────────────────────────────────── */}
      {state === 'empty' && (
        <SectionCard>
          <EmptyState
            title={emptyReasonLabel(data?.emptyReason ?? null) || '没有销量趋势数据'}
            description={emptyText(data)}
          />
        </SectionCard>
      )}

      {/* ── 就绪态 ─────────────────────────────────────────────────────── */}
      {state === 'ready' && data && (
        <>
          {/* ── 趋势点列 ─────────────────────────────────────────────── */}
          <SectionCard
            title="趋势点列"
            description="一行 = 一个桶（一个粒度 × 一个币种）。「—」= 这一格算不出来，不是 0。环比比的是上一个同粒度桶，同比比的是去年同期。"
          >
            <div className="space-y-6">
              {data.series.map((series) => (
                <div key={`${series.metricCode}-${series.currency}`}>
                  <div className="mb-2 flex flex-wrap items-center gap-2">
                    <span className="text-copy-14 text-foreground font-medium">
                      {series.displayName}
                    </span>
                    <Badge tone="neutral">{series.currency}</Badge>
                    <span className="text-copy-13 text-muted-foreground">
                      {metricUnitLabel(series.unit)}
                    </span>
                  </div>
                  <div className="overflow-x-auto">
                    <table className="w-full text-left text-sm">
                      <thead className="text-copy-13 text-muted-foreground">
                        <tr className="border-border border-b">
                          <th className="py-2 pr-3 font-medium">桶</th>
                          <th className="py-2 pr-3 text-right font-medium">取值</th>
                          <th className="py-2 pr-3 text-right font-medium">环比</th>
                          <th className="py-2 pr-3 text-right font-medium">同比</th>
                          <th className="py-2 pr-3 font-medium">标注</th>
                        </tr>
                      </thead>
                      <tbody>
                        {series.points.map((point) => (
                          <tr key={point.bucketStart} className="border-border border-b">
                            <td className="py-2 pr-3 whitespace-nowrap">{bucketLabel(point)}</td>
                            <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                              {formatDecimal(point.value ?? 0, 2)}
                              {point.value === null ? '' : ` ${series.currency}`}
                            </td>
                            <td
                              className="py-2 pr-3 text-right tabular-nums whitespace-nowrap"
                              title={periodOverPeriodNote(point)}
                            >
                              {point.periodOverPeriodRate === null
                                ? '不可比'
                                : `${point.periodOverPeriodRate > 0 ? '+' : ''}${formatDecimal(point.periodOverPeriodRate, 2)}%`}
                              {point.periodOverPeriodRate === null && (
                                <div className="text-copy-13 text-muted-foreground">
                                  {periodOverPeriodNote(point)}
                                </div>
                              )}
                            </td>
                            <td
                              className="py-2 pr-3 text-right tabular-nums whitespace-nowrap"
                              title={yearOverYearNote(point)}
                            >
                              {point.yearOverYearRate === null
                                ? '不可比'
                                : `${point.yearOverYearRate > 0 ? '+' : ''}${formatDecimal(point.yearOverYearRate, 2)}%`}
                              {point.yearOverYearRate === null && (
                                <div className="text-copy-13 text-muted-foreground">
                                  {yearOverYearNote(point)}
                                </div>
                              )}
                            </td>
                            <td className="py-2 pr-3">
                              {point.anomaly && (
                                <div className="flex flex-wrap items-center gap-1">
                                  <Badge tone={anomalyTone('SPIKE')}>异常</Badge>
                                  <span className="text-copy-13 text-muted-foreground">
                                    {point.anomalyNote}
                                  </span>
                                </div>
                              )}
                              {eventsOf(point).map((event) => (
                                <div key={event.eventCode} className="mt-1 flex flex-wrap items-center gap-1">
                                  <Badge tone={severityTone(event.severity)}>
                                    {eventTypeLabel(event.eventType)}
                                  </Badge>
                                  <span className="text-copy-13 text-muted-foreground">
                                    {event.title}
                                  </span>
                                </div>
                              ))}
                              {point.anomaly && eventsOf(point).length === 0 && (
                                <div className="text-copy-13 text-muted-foreground mt-1">
                                  ⚠️ 没有关联到事件（查不到原因）
                                </div>
                              )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              ))}
            </div>
          </SectionCard>

          {/* ── 异常清单 ─────────────────────────────────────────────── */}
          <SectionCard
            title="异常清单"
            description="异常 = 相对同序列中位数偏离 ≥ ±50%。每条都带它关联到的事件 —— 没有事件时明说「查不到原因」（图表只是曲线没有洞察）。"
          >
            {anomalies.length === 0 ? (
              <p className="text-copy-13 text-muted-foreground">这个窗口里没有判成异常的桶。</p>
            ) : (
              <div className="space-y-3">
                {anomalies.map((anomaly) => (
                  <div key={`${anomaly.metricCode}-${anomaly.currency}-${anomaly.bucketStart}`} className="border-border rounded-lg border px-4 py-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={anomalyTone(anomaly.direction)}>
                        {anomalyDirectionLabel(anomaly.direction)}
                      </Badge>
                      <span className="text-copy-14 text-foreground font-medium">
                        {anomaly.bucketStart}
                        {anomaly.bucketEnd !== anomaly.bucketStart ? ` ~ ${anomaly.bucketEnd}` : ''}
                      </span>
                      <span className="text-copy-13 text-muted-foreground">
                        {anomaly.metricCode} · {anomaly.currency}
                      </span>
                    </div>
                    <p className="text-copy-13 text-muted-foreground mt-1">{anomaly.note}</p>
                    {anomaly.events.length === 0 ? (
                      <p className="text-copy-13 text-muted-foreground mt-1">
                        ⚠️ 没有关联到事件（查不到原因）
                      </p>
                    ) : (
                      <ul className="text-copy-13 mt-1 space-y-1">
                        {anomaly.events.map((event) => (
                          <li key={event.eventCode}>
                            <Badge tone={severityTone(event.severity)}>
                              {eventTypeLabel(event.eventType)}
                            </Badge>{' '}
                            {eventText(event)}
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          {/* ── 多维对比 ─────────────────────────────────────────────── */}
          <SectionCard
            title={`多维对比（${dimensionLabel(comparison?.dimensionType ?? dimension)}）`}
            description="环比比的是上一等长窗口，同比比的是去年同期窗口。金额按币种分别列出，排序在币种内做。"
          >
            {comparison === null ? (
              <p className="text-copy-13 text-muted-foreground">
                对比视图**读不到**（这是「读不到」，不是「没有数据」）—— 趋势本身不受影响。
              </p>
            ) : comparison.rows.length === 0 ? (
              <p className="text-copy-13 text-muted-foreground">
                {comparison.emptyNote || '这个窗口里没有可对比的维度键。'}
              </p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="text-copy-13 text-muted-foreground">
                    <tr className="border-border border-b">
                      <th className="py-2 pr-3 font-medium">维度键</th>
                      <th className="py-2 pr-3 font-medium">币种</th>
                      {comparison.metricDefinitions
                        .filter((definition) => definition.aggregation === 'SUM')
                        .map((definition) => (
                          <th key={definition.metricCode} className="py-2 pr-3 text-right font-medium">
                            {definition.displayName}
                          </th>
                        ))}
                      <th className="py-2 pr-3 text-right font-medium">
                        环比（成交金额）
                      </th>
                      <th className="py-2 pr-3 text-right font-medium">
                        同比（成交金额）
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {comparison.rows.map((row) => (
                      <tr
                        key={`${row.dimensionKey}-${row.currency}`}
                        className="border-border border-b"
                      >
                        <td className="py-2 pr-3">{row.dimensionLabel}</td>
                        <td className="py-2 pr-3">{row.currency}</td>
                        {comparison.metricDefinitions
                          .filter((definition) => definition.aggregation === 'SUM')
                          .map((definition) => (
                            <td
                              key={definition.metricCode}
                              className="py-2 pr-3 text-right tabular-nums whitespace-nowrap"
                            >
                              {comparisonCellText(row, definition.metricCode, definition.unit)}
                            </td>
                          ))}
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          {comparisonGrowthText(row, 'SALES_AMOUNT')}
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          {comparisonYearText(row, 'SALES_AMOUNT')}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </SectionCard>

          {/* ── 异常事件 ─────────────────────────────────────────────── */}
          <SectionCard
            title="异常日期标注"
            description="大促 / 秒杀 / 缺货 / 断货。断货与缺货来自上游的断货风险快照 —— 上游还没算过快照时这里就是空的（是「上游没有」，不是「读不到」）。"
          >
            {eventsOfResponse(events).length === 0 ? (
              <p className="text-copy-13 text-muted-foreground">
                {events?.emptyNote || '这个窗口里没有异常事件标注。'}
              </p>
            ) : (
              <ul className="space-y-2">
                {eventsOfResponse(events).map((event) => (
                  <li key={event.eventCode} className="border-border rounded-lg border px-4 py-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={severityTone(event.severity)}>
                        {eventTypeLabel(event.eventType)}
                      </Badge>
                      <span className="text-copy-14 text-foreground font-medium">{event.title}</span>
                    </div>
                    <p className="text-copy-13 text-muted-foreground mt-1">{eventText(event)}</p>
                    {event.detail && (
                      <p className="text-copy-13 text-muted-foreground mt-1">{event.detail}</p>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>

          {/* ── 指标口径目录 ─────────────────────────────────────────── */}
          <SectionCard
            title="指标口径目录"
            description="口径唯一：一个指标只允许一处定义，其他地方引用不重算。对比型只写被比较指标的引用，不写公式。"
          >
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="text-copy-13 text-muted-foreground">
                  <tr className="border-border border-b">
                    <th className="py-2 pr-3 font-medium">指标</th>
                    <th className="py-2 pr-3 font-medium">维度</th>
                    <th className="py-2 pr-3 font-medium">单位</th>
                    <th className="py-2 pr-3 font-medium">聚合</th>
                    <th className="py-2 pr-3 font-medium">口径</th>
                    <th className="py-2 pr-3 font-medium">上游</th>
                  </tr>
                </thead>
                <tbody>
                  {definitions.map((definition) => (
                    <tr key={definition.metricCode} className="border-border border-b">
                      <td className="py-2 pr-3">
                        <div className="font-medium">{definition.displayName}</div>
                        <div className="text-copy-13 text-muted-foreground">
                          {definition.metricCode}
                        </div>
                      </td>
                      <td className="py-2 pr-3">{dimensionLabel(definition.dimensionType)}</td>
                      <td className="py-2 pr-3">{metricUnitLabel(definition.unit)}</td>
                      <td className="py-2 pr-3">{metricFormulaText(definition)}</td>
                      <td className="py-2 pr-3 max-w-md text-copy-13 text-muted-foreground">
                        {definition.description}
                      </td>
                      <td className="py-2 pr-3 text-copy-13 text-muted-foreground">
                        {definition.ownerModule || '本服务'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </SectionCard>

          {/* ── 能力声明 ─────────────────────────────────────────────── */}
          <SectionCard
            title="能力声明"
            description="哪些上游真接通、哪些口径确实拿不到、判定规则是什么 —— 都写清楚，别让人从空数组里猜。"
          >
            <div className="space-y-4">
              <div>
                <h3 className="text-copy-14 text-foreground mb-2 font-medium">上游（真的读过）</h3>
                <ul className="text-copy-13 text-muted-foreground list-disc space-y-1 pl-5">
                  {upstreamModules(capabilities).map((module) => (
                    <li key={module}>{module}</li>
                  ))}
                </ul>
              </div>
              <div>
                <h3 className="text-copy-14 text-foreground mb-2 flex items-center gap-1.5 font-medium">
                  <TriangleAlert className="h-3.5 w-3.5" />
                  确实拿不到的部分
                </h3>
                <ul className="text-copy-13 text-muted-foreground list-disc space-y-1 pl-5">
                  {notDeliveredLines(capabilities).map((line) => (
                    <li key={line}>{line}</li>
                  ))}
                </ul>
              </div>
              <div>
                <h3 className="text-copy-14 text-foreground mb-2 font-medium">判定规则</h3>
                <ul className="text-copy-13 text-muted-foreground list-disc space-y-1 pl-5">
                  {ruleNotes(capabilities).map((rule) => (
                    <li key={rule.ruleCode}>
                      <span className="text-foreground font-medium">{rule.ruleCode}</span>：{rule.description}
                    </li>
                  ))}
                </ul>
              </div>
              {caliberNotes.length > 0 && (
                <div>
                  <h3 className="text-copy-14 text-foreground mb-2 font-medium">口径说明</h3>
                  <ul className="text-copy-13 text-muted-foreground list-disc space-y-1 pl-5">
                    {caliberNotes.map((note) => (
                      <li key={note}>{note}</li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          </SectionCard>
        </>
      )}
    </div>
  );
}

/** 能力声明还没读到时的粒度兜底（服务端给的那份优先）。 */
const GRANULARITY_FALLBACK = [
  { value: 'DAY', label: '按日' },
  { value: 'WEEK', label: '按周' },
  { value: 'MONTH', label: '按月' },
  { value: 'HOUR', label: '按小时' },
];

/** 能力声明还没读到时的维度兜底。 */
const DIMENSION_FALLBACK = [
  { value: 'TOTAL', label: '公司（同比 / 环比）' },
  { value: 'STORE', label: '店铺间对比' },
  { value: 'PRODUCT', label: '产品间对比（SKU）' },
];
