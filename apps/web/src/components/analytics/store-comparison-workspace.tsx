'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { api } from '@/lib/api';
import {
  attributionHints,
  availabilityLabel,
  caliberWarnings,
  cellAvailability,
  cellNote,
  cellText,
  classificationLabel,
  classificationTone,
  comparisonWindowText,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  emptyReasonLabel,
  emptyText,
  groupCaliberNote,
  hasMultipleCurrencies,
  hasMultiplePlatforms,
  matrixColumns,
  metricFormulaText,
  metricUnitLabel,
  notDeliveredLines,
  pageState,
  platformFilterOptions,
  platformLabel,
  rangeText,
  rankText,
  rankingEntries,
  rankingNote,
  refreshStatusLabel,
  refreshStatusTone,
  ruleNotes,
  statusLabel,
  upstreamModules,
} from './store-comparison-model';
import type {
  ComparisonCapabilities,
  ComparisonResponse,
  StoreListResponse,
  StoreMetricDefinitionView,
  StoreRefreshResult,
} from './store-comparison-model';

/**
 * 13.1.1.2 多店对比工作区（卷 13「利润与经营分析」L3 13.1.1 经营看板下的第二格）。
 *
 * <p>蓝图输出：「对比矩阵 + 排名 + 差异归因提示。」
 *
 * <p><b>数据来自 reporting 服务（13 卷的唯一归属服务）的投影读模型</b>：
 *
 * <ul>
 *   <li>{@code GET reporting/store-comparison} —— 对比矩阵 + 排名 + 差异归因 + 掉队/增长店铺；</li>
 *   <li>{@code GET reporting/store-comparison/capabilities} —— 能力声明；</li>
 *   <li>{@code GET reporting/store-comparison/metrics} —— 指标口径目录（口径唯一的展示形态）；</li>
 *   <li>{@code GET reporting/store-comparison/shops} —— 店铺清单 + 平台清单；</li>
 *   <li>{@code POST reporting/store-comparison/refresh} —— 刷新店铺维度投影。</li>
 * </ul>
 *
 * <p><b>🔴 页面必须做到的五件事</b>：
 *
 * <ol>
 *   <li><b>跨平台口径差异必须挂在页面上</b>（蓝图关系句加粗要求）：顶部常驻一条口径提醒，
 *       每个平台分组再挂自己那条 —— 不同平台的 GMV 口径不同，
 *       <b>分组之间的大小差异不能直接当成经营能力差异</b>。</li>
 *   <li><b>「算不出来」≠「等于 0」</b>：{@code null} 的取值显示「—」/「不可比」+ 原因，
 *       <b>绝不用 0 顶替</b>（假 0 会被读成「这个店铺销售为零」）。</li>
 *   <li><b>多币种不相加</b>，排名在币种内做。</li>
 *   <li><b>数据更新时间常驻</b>（卷 13 卷首加粗句「数据延迟要显示」）：加载中 / 出错时也显示。</li>
 *   <li><b>能力声明常驻</b>：「店铺维度确实拿不到什么」必须写在页面上
 *       （毛利率、库存周转在上游根本没有店铺维度）。</li>
 * </ol>
 */
export function AnalyticsStoreComparisonWorkspace() {
  const today = new Date().toISOString().slice(0, 10);
  const initialRange = defaultRange(today);

  const [from, setFrom] = useState(initialRange.from);
  const [to, setTo] = useState(initialRange.to);
  const [platformCode, setPlatformCode] = useState('');
  const [baseCurrency, setBaseCurrency] = useState('');

  const [data, setData] = useState<ComparisonResponse | null>(null);
  const [capabilities, setCapabilities] = useState<ComparisonCapabilities | null>(null);
  const [definitions, setDefinitions] = useState<StoreMetricDefinitionView[]>([]);
  const [stores, setStores] = useState<StoreListResponse | null>(null);
  const [refreshResults, setRefreshResults] = useState<StoreRefreshResult[] | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const query = useMemo(() => {
    const params = new URLSearchParams({ from, to });
    if (platformCode) params.set('platformCode', platformCode);
    if (baseCurrency) params.set('baseCurrency', baseCurrency);
    return params.toString();
  }, [from, to, platformCode, baseCurrency]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [comparison, caps, metrics, shopList] = await Promise.all([
        api<ComparisonResponse>(`reporting/store-comparison?${query}`),
        api<ComparisonCapabilities>('reporting/store-comparison/capabilities'),
        api<StoreMetricDefinitionView[]>('reporting/store-comparison/metrics'),
        api<StoreListResponse>('reporting/store-comparison/shops'),
      ]);
      setData(comparison);
      setCapabilities(caps);
      setDefinitions(metrics);
      setStores(shopList);
    } catch (e) {
      setData(null);
      setError(e instanceof Error ? e.message : '读取多店对比失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [query]);

  const refreshProjection = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const params = new URLSearchParams({ from, to, timezone: 'UTC' });
      const results = await api<StoreRefreshResult[]>(
        `reporting/store-comparison/refresh?${params}`,
        'POST',
      );
      setRefreshResults(results);
      // 刷完立刻重读矩阵 —— 投影变了，页面上的数必须跟着变。
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '刷新店铺维度投影失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [from, to, load]);

  useEffect(() => {
    void load();
  }, [load]);

  const state = pageState(data, error);
  const columns = useMemo(() => matrixColumns(definitions), [definitions]);
  const warnings = useMemo(() => caliberWarnings(data), [data]);
  const platformOptions = useMemo(() => platformFilterOptions(stores?.platforms ?? []), [stores]);

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

      {/* ── 跨平台口径提醒（常驻，蓝图的加粗要求）──────────────────────── */}
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
        description="时间范围决定对比窗口；上一等长窗口是增长率的比对基准，平台筛选只影响取数，不改变口径。"
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
            平台
            <select
              value={platformCode}
              onChange={(e) => setPlatformCode(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              {platformOptions.map((option) => (
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
            刷新店铺维度投影
          </Button>
        </div>
        <p className="text-copy-13 text-muted-foreground mt-3">{comparisonWindowText(data)}</p>
      </SectionCard>

      {/* ── 刷新结果 ───────────────────────────────────────────────────── */}
      {refreshResults && (
        <SectionCard
          title="投影刷新结果"
          description="上游读不到就是读不到（失败），不会返回空集合冒充「上游没有数据」。"
        >
          <div className="space-y-2">
            {refreshResults.map((result) => (
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
            title="多店对比读不到"
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
          <EmptyState title="正在读取多店对比…" description="读取期间页面上的「数据截至」仍然有效。" />
        </SectionCard>
      )}

      {/* ── 空态 ───────────────────────────────────────────────────────── */}
      {state === 'empty' && (
        <SectionCard>
          <EmptyState
            title={emptyReasonLabel(data?.emptyReason ?? null) || '没有多店对比数据'}
            description={emptyText(data)}
          />
        </SectionCard>
      )}

      {/* ── 就绪态 ─────────────────────────────────────────────────────── */}
      {state === 'ready' && data && (
        <>
          {/* ── 对比矩阵 ─────────────────────────────────────────────── */}
          <SectionCard
            title="对比矩阵"
            description="一行 = 一个店铺。金额按币种分别列出，排名在币种内做。「—」= 这一格算不出来，不是 0。"
          >
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="text-copy-13 text-muted-foreground">
                  <tr className="border-border border-b">
                    <th className="py-2 pr-3 font-medium">店铺</th>
                    <th className="py-2 pr-3 font-medium">平台</th>
                    <th className="py-2 pr-3 font-medium">分类</th>
                    <th className="py-2 pr-3 text-right font-medium">规模排名</th>
                    <th className="py-2 pr-3 text-right font-medium">增长排名</th>
                    {columns.map((column) => (
                      <th key={column.metricCode} className="py-2 pr-3 text-right font-medium">
                        {column.label}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((row) => (
                    <tr key={row.shopCode} className="border-border border-b">
                      <td className="py-2 pr-3">
                        <div className="font-medium">{row.shopName || row.shopCode}</div>
                        <div className="text-copy-13 text-muted-foreground">{row.shopCode}</div>
                      </td>
                      <td className="py-2 pr-3">
                        {platformLabel(row.platformCode, row.platformName)}
                      </td>
                      <td className="py-2 pr-3">
                        <Badge tone={classificationTone(row.classification)}>
                          {classificationLabel(row.classification)}
                        </Badge>
                        <div className="text-copy-13 text-muted-foreground mt-1 max-w-xs">
                          {row.classificationNote}
                        </div>
                      </td>
                      <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                        {rankText(row.scaleRank)}
                      </td>
                      <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                        {rankText(row.growthRank)}
                      </td>
                      {columns.map((column) => {
                        const availability = cellAvailability(row, column);
                        const note = cellNote(row, column);
                        return (
                          <td
                            key={column.metricCode}
                            className="py-2 pr-3 text-right tabular-nums whitespace-nowrap"
                            title={note}
                          >
                            {cellText(row, column)}
                            {availability !== 'AVAILABLE' && (
                              <div className="text-copy-13 text-muted-foreground">
                                {availabilityLabel(availability)}
                              </div>
                            )}
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </SectionCard>

          {/* ── 平台分组 ─────────────────────────────────────────────── */}
          <SectionCard
            title="按平台分组"
            description="每个分组带自己的口径说明 —— 不同平台的成交金额口径由平台各自决定，分组之间的大小差异不能直接当成经营能力差异。"
          >
            {data.platformGroups.length === 0 ? (
              <p className="text-copy-13 text-muted-foreground">这个窗口里没有可分组的店铺。</p>
            ) : (
              <div className="space-y-4">
                {data.platformGroups.map((group) => (
                  <div key={group.platformCode} className="border-border rounded-lg border px-4 py-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-copy-14 text-foreground font-medium">
                        {platformLabel(group.platformCode, group.platformName)}
                      </span>
                      <span className="text-copy-13 text-muted-foreground">
                        {group.storeCount} 个店铺
                      </span>
                    </div>
                    <p className="text-copy-13 text-muted-foreground mt-1">{groupCaliberNote(group)}</p>
                    <div className="text-copy-13 mt-2 space-y-1">
                      {group.stores.map((store) => (
                        <div key={store.shopCode} className="flex flex-wrap items-center gap-2">
                          <Badge tone={classificationTone(store.classification)}>
                            {classificationLabel(store.classification)}
                          </Badge>
                          <span>{store.shopName || store.shopCode}</span>
                          <span className="text-muted-foreground">{store.currencies.join(' / ')}</span>
                        </div>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          {/* ── 增长店铺 / 掉队店铺 ───────────────────────────────────── */}
          <SectionCard
            title="增长店铺与掉队店铺"
            description="判定依据是成交金额的环比增长率（阈值见下面的判定规则）。算不出环比的店铺是「不可比」，不是「稳定」。"
          >
            <div className="grid gap-4 md:grid-cols-2">
              <div>
                <h3 className="text-copy-14 text-foreground mb-2 font-medium">
                  增长店铺（{data.growthStores.length}）
                </h3>
                {data.growthStores.length === 0 ? (
                  <p className="text-copy-13 text-muted-foreground">
                    这个窗口里没有店铺达到增长阈值。
                  </p>
                ) : (
                  <ul className="space-y-1">
                    {data.growthStores.map((store) => (
                      <li key={store.shopCode} className="text-copy-13">
                        <span className="font-medium">{store.shopName || store.shopCode}</span>
                        <span className="text-muted-foreground ml-2">{store.reason}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
              <div>
                <h3 className="text-copy-14 text-foreground mb-2 font-medium">
                  掉队店铺（{data.laggards.length}）
                </h3>
                {data.laggards.length === 0 ? (
                  <p className="text-copy-13 text-muted-foreground">
                    这个窗口里没有店铺落到掉队阈值以下。
                  </p>
                ) : (
                  <ul className="space-y-1">
                    {data.laggards.map((store) => (
                      <li key={store.shopCode} className="text-copy-13">
                        <span className="font-medium">{store.shopName || store.shopCode}</span>
                        <span className="text-muted-foreground ml-2">{store.reason}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </div>
          </SectionCard>

          {/* ── 指标排名 ─────────────────────────────────────────────── */}
          <SectionCard
            title="指标排名"
            description="排名在币种内做：一个 USD 店铺和一个 EUR 店铺的大小本来就不可比。"
          >
            <div className="space-y-4">
              {data.rankings.map((ranking) => (
                <div key={ranking.metricCode}>
                  <div className="text-copy-14 text-foreground mb-1 font-medium">
                    {ranking.displayName}
                  </div>
                  {ranking.entries.length === 0 ? (
                    <p className="text-copy-13 text-muted-foreground">{rankingNote(ranking)}</p>
                  ) : (
                    rankingEntries(ranking).map((bucket) => (
                      <div key={bucket.currency} className="mb-2">
                        <div className="text-copy-13 text-muted-foreground">币种 {bucket.currency}</div>
                        <ul className="text-copy-13 mt-1 space-y-1">
                          {bucket.entries.map((entry) => (
                            <li key={`${entry.currency}|${entry.shopCode}`} className="flex gap-3">
                              <span className="text-muted-foreground w-16">
                                第 {entry.rank} 名
                              </span>
                              <span className="font-medium">
                                {entry.shopName || entry.shopCode}
                              </span>
                              <span className="tabular-nums">{entry.value}</span>
                            </li>
                          ))}
                        </ul>
                      </div>
                    ))
                  )}
                </div>
              ))}
            </div>
          </SectionCard>

          {/* ── 差异归因提示 ─────────────────────────────────────────── */}
          <SectionCard
            title="差异归因提示"
            description="每一行相对全体店铺中位数差在哪 —— 提示里写的是「中位数」而不是某个具体店铺的数字，因为中位数会随店铺集合变化。"
          >
            <div className="space-y-3">
              {data.rows.map((row) => (
                <div key={row.shopCode} className="border-border rounded-lg border px-4 py-3">
                  <div className="text-copy-14 text-foreground font-medium">
                    {row.shopName || row.shopCode}
                  </div>
                  <ul className="text-copy-13 text-muted-foreground mt-1 space-y-1">
                    {attributionHints(row).map((hint, index) => (
                      <li key={index}>{hint}</li>
                    ))}
                  </ul>
                </div>
              ))}
            </div>
          </SectionCard>

          {/* ── 指标口径目录 ─────────────────────────────────────────── */}
          <SectionCard
            title="指标口径目录"
            description="口径唯一：指标只在一处定义。窗口环比型必须说清它比的是**哪个指标** —— 否则页面上的增长率是个没有出处的数。"
          >
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="text-copy-13 text-muted-foreground">
                  <tr className="border-border border-b">
                    <th className="py-2 pr-3 font-medium">指标</th>
                    <th className="py-2 pr-3 font-medium">单位</th>
                    <th className="py-2 pr-3 font-medium">聚合方式</th>
                    <th className="py-2 pr-3 font-medium">怎么算</th>
                    <th className="py-2 pr-3 font-medium">口径归属</th>
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
                      <td className="py-2 pr-3">{metricUnitLabel(definition.unit)}</td>
                      <td className="py-2 pr-3">{definition.aggregation}</td>
                      <td className="py-2 pr-3">
                        <div>{metricFormulaText(definition)}</div>
                        <div className="text-copy-13 text-muted-foreground mt-1 max-w-md">
                          {definition.description}
                        </div>
                      </td>
                      <td className="py-2 pr-3">{definition.ownerModule}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </SectionCard>

          {/* ── 能力声明 ─────────────────────────────────────────────── */}
          <SectionCard
            title="能力声明"
            description="下面写的是**确实拿不到**的部分，每条都带原因 —— 拿不到伪装成 0 比说清楚更糟。"
          >
            <div className="space-y-3">
              <div>
                <h3 className="text-copy-14 text-foreground mb-1 font-medium">本服务真读过的上游</h3>
                <ul className="text-copy-13 text-muted-foreground list-disc pl-5">
                  {upstreamModules(capabilities).map((module, index) => (
                    <li key={index}>{module}</li>
                  ))}
                </ul>
              </div>
              <div>
                <h3 className="text-copy-14 text-foreground mb-1 font-medium">确实拿不到</h3>
                <ul className="text-copy-13 text-muted-foreground list-disc pl-5">
                  {notDeliveredLines(capabilities).map((line, index) => (
                    <li key={index}>{line}</li>
                  ))}
                </ul>
              </div>
              <div>
                <h3 className="text-copy-14 text-foreground mb-1 font-medium">判定规则</h3>
                <ul className="text-copy-13 text-muted-foreground list-disc pl-5">
                  {ruleNotes(capabilities).map((rule) => (
                    <li key={rule.ruleCode}>
                      <span className="font-medium">{rule.ruleCode}</span>：{rule.description}
                    </li>
                  ))}
                </ul>
              </div>
              <p className="text-copy-13 text-muted-foreground">
                本次对比状态：{statusLabel(data.status)}
                {hasMultiplePlatforms(data) && ' · 涉及多个平台（口径差异已标注）'}
                {hasMultipleCurrencies(data) && ' · 涉及多个币种（排名在币种内做）'}
              </p>
            </div>
          </SectionCard>

          {capabilities && !capabilities.upstreamConnected && (
            <div className="border-border bg-muted/40 text-copy-13 text-muted-foreground flex items-start gap-2 rounded-lg border px-4 py-3">
              <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
              <span>上游尚未完全接通，页面上的部分数字可能缺失。</span>
            </div>
          )}
        </>
      )}
    </div>
  );
}
