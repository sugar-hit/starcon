'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, Segmented } from '@starcon/ui';
import { Activity, RefreshCw, TriangleAlert } from 'lucide-react';
import { api } from '@/lib/api';
import {
  availabilityLabel,
  availabilityTone,
  baselineText,
  changeRateText,
  changeText,
  changeTone,
  countText,
  currencyLabel,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  dimensionLabel,
  dimensionOptions,
  dimensionScopeText,
  emptyReasonLabel,
  emptyText,
  metricUnitLabel,
  metricValueText,
  notDeliveredLines,
  notDeliveredOwnerText,
  pageState,
  peerAverageText,
  peerMedianText,
  rankingPercentileText,
  rankingText,
  refreshStatusLabel,
  refreshStatusTone,
  ruleNotes,
  sectionAdCampaigns,
  sectionCompetitors,
  sectionMetrics,
  sectionTitle,
  sellerKindLabel,
  sellerKindTone,
  statusLabel,
  trendAmountsText,
  trendClicksText,
  trendHasValues,
  trendImpressionsText,
  trendReturnedText,
  trendSalesText,
  trendSessionsText,
  upstreamModules,
} from './product-performance-model';
import type {
  PerformanceMetric,
  PerformanceMetricDefinitionView,
  PerformanceRanking,
  PerformanceSection,
  PerformanceTrendPoint,
  ProductPerformanceCapabilities,
  ProductPerformanceRefreshResult,
  ProductPerformanceResponse,
} from './product-performance-model';

/**
 * 13.3.1.1 产品表现分析工作区（卷 13「利润与经营分析」L3 13.3 产品分析下的第一格）。
 *
 * <p>蓝图输出：「多指标分析页 + 趋势 + 排名分位。」
 *
 * <p><b>数据来自 reporting 服务（13 卷的唯一归属服务）的投影读模型</b>：
 *
 * <ul>
 *   <li>{@code GET reporting/product-performance} —— 360 度分析（九大区块 + 趋势 + 排名分位 + 环比基线）；</li>
 *   <li>{@code GET reporting/product-performance/capabilities} —— 能力声明；</li>
 *   <li>{@code GET reporting/product-performance/metrics} —— 指标口径目录（口径唯一的展示形态）；</li>
 *   <li>{@code POST reporting/product-performance/refresh} —— 刷新 360 度投影。</li>
 * </ul>
 *
 * <p><b>🔴 页面必须做到的五件事</b>：
 *
 * <ol>
 *   <li><b>集成在同一页面滚动查看</b>（蓝图的原话：「避免页面来回跳」）——
 *       一次调用返回全部区块，不做瀑布式串行请求，也不在区块之间来回跳。</li>
 *   <li><b>「算不出来」≠「等于 0」</b>：{@code null} 的取值显示「—」/「不可比」，
 *       上游未交付的指标显示「本轮不可用」+ <b>归谁管</b>，
 *       <b>绝不用 0 顶替</b>（假 0 会被读成「这个商品卖不出去」）。</li>
 *   <li><b>多币种绝不相加</b>：金额按币种分别列出；件数与次数给跨币种合计
 *       （币种显示为空串 = 与币种无关）。</li>
 *   <li><b>指标要可对比</b>：每个指标带环比（变化量 / 变化率）与同层级排名分位，
 *       变化量与变化率<b>由服务端算</b>（前端只做展示格式化）。</li>
 *   <li><b>数据更新时间常驻</b>（卷 13 卷首加粗句「数据延迟要显示」）：加载中 / 出错时也显示。</li>
 * </ol>
 */
export function AnalyticsProductPerformanceWorkspace() {
  const today = new Date().toISOString().slice(0, 10);
  const initialRange = defaultRange(today);

  const [from, setFrom] = useState(initialRange.from);
  const [to, setTo] = useState(initialRange.to);
  const [dimensionType, setDimensionType] = useState('SKU');
  const [dimensionKey, setDimensionKey] = useState('');
  const [currency, setCurrency] = useState('');

  const [data, setData] = useState<ProductPerformanceResponse | null>(null);
  const [capabilities, setCapabilities] = useState<ProductPerformanceCapabilities | null>(null);
  const [definitions, setDefinitions] = useState<PerformanceMetricDefinitionView[]>([]);
  const [refreshResults, setRefreshResults] = useState<ProductPerformanceRefreshResult[] | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const query = useMemo(() => {
    const params = new URLSearchParams({
      from,
      to,
      dimensionType,
      dimensionKey: dimensionKey.trim(),
    });
    if (currency.trim()) params.set('currency', currency.trim().toUpperCase());
    return params.toString();
  }, [from, to, dimensionType, dimensionKey, currency]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      // 🔴 **一次性拉齐**：一次调用返回全部区块 + 趋势 + 排名分位，
      //    不做瀑布式串行请求 —— 运营每天用得最多的页面，串行请求会慢到不可用。
      const [report, caps, metrics] = await Promise.all([
        api<ProductPerformanceResponse>(`reporting/product-performance?${query}`),
        api<ProductPerformanceCapabilities>('reporting/product-performance/capabilities'),
        api<PerformanceMetricDefinitionView[]>('reporting/product-performance/metrics'),
      ]);
      setData(report);
      setCapabilities(caps);
      setDefinitions(metrics);
    } catch (e) {
      setData(null);
      setError(e instanceof Error ? e.message : '读取产品表现分析失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [query]);

  const refreshProjection = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const params = new URLSearchParams({ from, to, timezone: 'UTC' });
      const results = await api<ProductPerformanceRefreshResult[]>(
        `reporting/product-performance/refresh?${params}`,
        'POST',
      );
      setRefreshResults(results);
      // 刷完立刻重读 —— 投影变了，页面上的数必须跟着变。
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '刷新 360 度投影失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [from, to, load]);

  useEffect(() => {
    void load();
  }, [load]);

  const state = pageState(data, error);
  const unitOf = useCallback(
    (metricCode: string) =>
      definitions.find((definition) => definition.metricCode === metricCode)?.unit ?? '',
    [definitions],
  );

  return (
    <div className="space-y-4">
      {/* ── 数据更新时间（常驻）────────────────────────────────────────── */}
      <div className="border-border bg-muted/40 flex flex-wrap items-center justify-between gap-3 rounded-lg border px-4 py-3">
        <div className="text-copy-13 text-foreground">
          <span className="font-medium">{dataUpdatedText(data)}</span>
          <span className="text-muted-foreground ml-2">{dataUpdatedNote(data)}</span>
        </div>
        <div className="text-copy-13 text-muted-foreground flex items-center gap-3">
          <span>
            {from} ~ {to}
          </span>
          <Button
            variant="outline"
            size="sm"
            disabled={loading || busy}
            onClick={() => void load()}
          >
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            重读
          </Button>
        </div>
      </div>

      {/* ── 时间范围与维度选择 ─────────────────────────────────────────── */}
      <SectionCard
        title="时间范围与产品标识"
        description="这一页是「单产品 360 度分析」：选一个产品标识（SKU / SPU / Listing）与时间范围，全部指标在同一页滚动看完。基线默认是上一个等长周期（环比由服务端算）。"
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
            产品标识（SKU / SPU / Listing 编码）
            <input
              type="text"
              value={dimensionKey}
              placeholder="例如 SKU-1001"
              onChange={(e) => setDimensionKey(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            币种（可选）
            <input
              type="text"
              value={currency}
              placeholder="不填 = 按币种分别报"
              onChange={(e) => setCurrency(e.target.value.toUpperCase())}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
          </label>
          <div className="flex flex-col gap-1">
            <span className="text-copy-13 text-muted-foreground">维度层</span>
            <Segmented
              value={dimensionType}
              onChange={(next) => setDimensionType(next)}
              options={dimensionOptions()}
              aria-label="产品维度层"
            />
          </div>
          <Button
            variant="outline"
            size="sm"
            disabled={busy}
            onClick={() => void refreshProjection()}
          >
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            刷新 360 度投影
          </Button>
        </div>
        {data && (
          <p className="text-copy-13 text-muted-foreground mt-3">
            当前维度：{dimensionLabel(data.dimensionType)}（{data.dimensionKey}）；状态：
            {statusLabel(data.status)}；{baselineText(data)}；{dimensionScopeText(data)}。
          </p>
        )}
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
                {result.note && (
                  <p className="text-copy-13 text-muted-foreground mt-1">{result.note}</p>
                )}
              </div>
            ))}
          </div>
        </SectionCard>
      )}

      {/* ── 错误态 ─────────────────────────────────────────────────────── */}
      {state === 'error' && (
        <SectionCard title="读取失败" description="上游读不到就是读不到，不会返回空集合冒充「没有数据」。">
          <div className="text-destructive flex items-start gap-2">
            <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
            <p className="text-copy-13">{error}</p>
          </div>
        </SectionCard>
      )}

      {/* ── 空态 ───────────────────────────────────────────────────────── */}
      {state === 'empty' && (
        <EmptyState
          icon={<Activity className="h-6 w-6" />}
          title="这个窗口里没有可分析的事实"
          description={
            <>
              {emptyText(data)}
              {data?.emptyReason ? `（原因：${emptyReasonLabel(data.emptyReason)}）` : ''}
            </>
          }
        />
      )}

      {/* ── 就绪态：360 度全部区块（同一页面滚动看完）─────────────────── */}
      {state === 'ready' && data && (
        <>
          {/* ── 排名分位 ─────────────────────────────────────────────── */}
          <SectionCard
            title="同层级排名分位"
            description="按销量（件数）排：金额跨币种不可比，用金额排会让不同币种的「第一名」混在一起。同层级里没有事实的商品不进榜。"
          >
            <RankingPanel ranking={data.ranking} />
          </SectionCard>

          {/* ── 九大区块 ─────────────────────────────────────────────── */}
          {data.sections.map((section) => (
            <SectionCard
              key={section.sectionCode}
              title={sectionTitle(section, section.sectionCode)}
              description={section.description}
            >
              <SectionPanel section={section} unitOf={unitOf} />
            </SectionCard>
          ))}

          {/* ── 趋势 ─────────────────────────────────────────────────── */}
          <SectionCard
            title="趋势"
            description="一行 = 一天。金额按币种分别列出（绝不跨币种相加）；「—」= 这一格算不出来，不是 0。"
          >
            <TrendPanel points={data.trend} />
          </SectionCard>

          {/* ── 指标口径目录 ─────────────────────────────────────────── */}
          <SectionCard
            title="指标口径目录"
            description="口径唯一：一个指标只允许一处定义，其他地方引用不重算。派生型只写被比较指标的引用，不写公式。"
          >
            <DefinitionPanel definitions={data.metricDefinitions} />
          </SectionCard>

          {/* ── 口径说明与未交付项 ───────────────────────────────────── */}
          <SectionCard
            title="口径说明与本轮未交付项"
            description="「拿不到」写清原因与归属模块 —— 把「拿不到」伪装成 0 比说清楚更糟。"
          >
            <div className="space-y-4">
              {data.caliberNotes.length > 0 && (
                <ul className="text-copy-13 text-muted-foreground list-disc space-y-1 pl-5">
                  {data.caliberNotes.map((note) => (
                    <li key={note}>{note}</li>
                  ))}
                </ul>
              )}
              {notDeliveredLines(capabilities).length > 0 && (
                <div>
                  <h4 className="text-copy-13 text-foreground font-medium">本轮确实拿不到</h4>
                  <ul className="text-copy-13 text-muted-foreground mt-1 list-disc space-y-1 pl-5">
                    {notDeliveredLines(capabilities).map((line) => (
                      <li key={line}>{line}</li>
                    ))}
                  </ul>
                </div>
              )}
              {upstreamModules(capabilities).length > 0 && (
                <div>
                  <h4 className="text-copy-13 text-foreground font-medium">本服务真的读过的上游</h4>
                  <ul className="text-copy-13 text-muted-foreground mt-1 list-disc space-y-1 pl-5">
                    {upstreamModules(capabilities).map((line) => (
                      <li key={line}>{line}</li>
                    ))}
                  </ul>
                </div>
              )}
              {ruleNotes(capabilities).length > 0 && (
                <div>
                  <h4 className="text-copy-13 text-foreground font-medium">判定规则</h4>
                  <ul className="text-copy-13 text-muted-foreground mt-1 list-disc space-y-1 pl-5">
                    {ruleNotes(capabilities).map((rule) => (
                      <li key={rule.ruleCode}>
                        <span className="text-foreground font-medium">{rule.ruleCode}</span>：{rule.description}
                      </li>
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

// ================================================================ 区块面板

/**
 * 一个区块的指标表。
 *
 * <p>🔴 **「本轮不可用」的指标不隐藏**：它显示「本轮不可用」+ 归谁管 ——
 * 藏起来会让人以为这个指标不存在，而显示成 0 会让人以为「这个商品没流量」。
 */
function SectionPanel({
  section,
  unitOf,
}: {
  section: PerformanceSection;
  unitOf: (metricCode: string) => string;
}) {
  const metrics = sectionMetrics(section);
  const competitors = sectionCompetitors(section);
  const adCampaigns = sectionAdCampaigns(section);

  return (
    <div className="space-y-4">
      {section.availability !== 'AVAILABLE' && section.availabilityNote && (
        <div className="border-border bg-muted/40 text-copy-13 text-muted-foreground rounded-lg border px-3 py-2">
          <Badge tone={availabilityTone(section.availability)}>
            {availabilityLabel(section.availability)}
          </Badge>
          <span className="ml-2">{section.availabilityNote}</span>
        </div>
      )}

      {metrics.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="text-copy-13 text-muted-foreground">
              <tr className="border-border border-b">
                <th className="py-2 pr-3 font-medium">指标</th>
                <th className="py-2 pr-3 text-right font-medium">本期</th>
                <th className="py-2 pr-3 text-right font-medium">基线</th>
                <th className="py-2 pr-3 text-right font-medium">变化</th>
                <th className="py-2 pr-3 text-right font-medium">变化率</th>
                <th className="py-2 pr-3 font-medium">状态</th>
              </tr>
            </thead>
            <tbody>
              {metrics.map((metric) => (
                <MetricRow key={metric.metricCode} metric={metric} unit={unitOf(metric.metricCode)} />
              ))}
            </tbody>
          </table>
        </div>
      )}

      {competitors.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="text-copy-13 text-muted-foreground">
              <tr className="border-border border-b">
                <th className="py-2 pr-3 font-medium">跟卖者</th>
                <th className="py-2 pr-3 font-medium">性质</th>
                <th className="py-2 pr-3 text-right font-medium">跟卖价</th>
                <th className="py-2 pr-3 text-right font-medium">自家参考价</th>
                <th className="py-2 pr-3 text-right font-medium">价格差</th>
                <th className="py-2 pr-3 text-right font-medium">价格差比例</th>
              </tr>
            </thead>
            <tbody>
              {competitors.map((row) => (
                <tr key={`${row.sellerId}-${row.observedAt ?? ''}`} className="border-border border-b">
                  <td className="py-2 pr-3">
                    <div className="text-foreground">{row.sellerName || row.sellerId}</div>
                    {row.note && (
                      <div className="text-copy-13 text-muted-foreground">{row.note}</div>
                    )}
                  </td>
                  <td className="py-2 pr-3">
                    <Badge tone={sellerKindTone(row.sellerKind)}>{sellerKindLabel(row.sellerKind)}</Badge>
                  </td>
                  <td className="py-2 pr-3 text-right">
                    {row.hijackerPrice === null ? '—' : `${row.hijackerPrice} ${currencyLabel(row.currency)}`}
                  </td>
                  <td className="py-2 pr-3 text-right">
                    {row.referencePrice === null ? '—' : `${row.referencePrice} ${currencyLabel(row.currency)}`}
                  </td>
                  <td className="py-2 pr-3 text-right">
                    {row.priceGap === null ? '—' : `${row.priceGap} ${currencyLabel(row.currency)}`}
                  </td>
                  <td className="py-2 pr-3 text-right">
                    {row.priceGapRatio === null ? '不可比' : `${row.priceGapRatio}%`}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {adCampaigns.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="text-copy-13 text-muted-foreground">
              <tr className="border-border border-b">
                <th className="py-2 pr-3 font-medium">广告活动</th>
                <th className="py-2 pr-3 font-medium">平台 / 店铺</th>
                <th className="py-2 pr-3 text-right font-medium">花费</th>
                <th className="py-2 pr-3 text-right font-medium">曝光</th>
                <th className="py-2 pr-3 text-right font-medium">点击</th>
                <th className="py-2 pr-3 text-right font-medium">订单</th>
                <th className="py-2 pr-3 text-right font-medium">销售额</th>
              </tr>
            </thead>
            <tbody>
              {adCampaigns.map((row) => (
                <tr key={row.campaignId} className="border-border border-b">
                  <td className="py-2 pr-3">
                    <div className="text-foreground">{row.campaignLabel || row.campaignId}</div>
                    {row.note && (
                      <div className="text-copy-13 text-muted-foreground">{row.note}</div>
                    )}
                  </td>
                  <td className="py-2 pr-3 text-muted-foreground">
                    {[row.platformName, row.shopName].filter(Boolean).join(' / ') || '—'}
                  </td>
                  <td className="py-2 pr-3 text-right">
                    {row.spend === null ? '—' : `${row.spend} ${currencyLabel(row.currency)}`}
                  </td>
                  <td className="py-2 pr-3 text-right">{countText(row.impressions)}</td>
                  <td className="py-2 pr-3 text-right">{countText(row.clicks)}</td>
                  <td className="py-2 pr-3 text-right">{countText(row.orders)}</td>
                  <td className="py-2 pr-3 text-right">
                    {row.sales === null ? '—' : `${row.sales} ${currencyLabel(row.currency)}`}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

// ================================================================ 指标行

function MetricRow({ metric, unit }: { metric: PerformanceMetric; unit: string }) {
  return (
    <tr className="border-border border-b">
      <td className="py-2 pr-3">
        <div className="text-foreground">{metric.displayName}</div>
        <div className="text-copy-13 text-muted-foreground">
          {metric.metricCode} · {metricUnitLabel(unit)}
          {metric.currency ? ` · ${currencyLabel(metric.currency)}` : ''}
        </div>
      </td>
      <td className="py-2 pr-3 text-right font-medium">{metricValueText(metric, unit)}</td>
      <td className="py-2 pr-3 text-right text-muted-foreground">
        {metric.baselineValue === null
          ? '—'
          : unit === 'PERCENT'
            ? `${metric.baselineValue}%`
            : unit === 'CURRENCY'
              ? `${metric.baselineValue} ${currencyLabel(metric.currency)}`
              : String(metric.baselineValue)}
      </td>
      <td className="py-2 pr-3 text-right">
        <span className={changeTone(metric) === 'danger' ? 'text-destructive' : undefined}>
          {changeText(metric, unit)}
        </span>
      </td>
      <td className="py-2 pr-3 text-right">{changeRateText(metric)}</td>
      <td className="py-2 pr-3">
        <Badge tone={availabilityTone(metric.availability)}>
          {availabilityLabel(metric.availability)}
        </Badge>
        {metric.availabilityNote && (
          <div className="text-copy-13 text-muted-foreground mt-0.5">
            {metric.availabilityNote}
          </div>
        )}
        {metric.sourceNote && (
          <div className="text-copy-13 text-muted-foreground">{metric.sourceNote}</div>
        )}
      </td>
    </tr>
  );
}

// ================================================================ 排名面板

function RankingPanel({ ranking }: { ranking: PerformanceRanking }) {
  return (
    <div className="space-y-2">
      <div className="text-copy-14 text-foreground">
        按{ranking.rankMetricDisplayName || ranking.rankMetricCode}排名：
        <span className="ml-2 font-medium">{rankingText(ranking)}</span>
      </div>
      <div className="text-copy-13 text-muted-foreground flex flex-wrap gap-x-6 gap-y-1">
        <span>分位：{rankingPercentileText(ranking)}</span>
        <span>同层级中位数：{peerMedianText(ranking)}</span>
        <span>同层级平均数：{peerAverageText(ranking)}</span>
      </div>
      {ranking.peerScope && (
        <div className="text-copy-13 text-muted-foreground">{ranking.peerScope}</div>
      )}
      {ranking.availability !== 'AVAILABLE' && (
        <div>
          <Badge tone={availabilityTone(ranking.availability)}>
            {availabilityLabel(ranking.availability)}
          </Badge>
          {ranking.availabilityNote && (
            <span className="text-copy-13 text-muted-foreground ml-2">
              {ranking.availabilityNote}
            </span>
          )}
        </div>
      )}
    </div>
  );
}

// ================================================================ 趋势面板

function TrendPanel({ points }: { points: PerformanceTrendPoint[] }) {
  if (!points || points.length === 0) {
    return <p className="text-copy-13 text-muted-foreground">这个窗口里没有趋势点。</p>;
  }
  if (!trendHasValues(points)) {
    return (
      <p className="text-copy-13 text-muted-foreground">
        这些日期上没有任何指标算得出来（没有事实，不是 0）。
      </p>
    );
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <thead className="text-copy-13 text-muted-foreground">
          <tr className="border-border border-b">
            <th className="py-2 pr-3 font-medium">日期</th>
            <th className="py-2 pr-3 text-right font-medium">销量</th>
            <th className="py-2 pr-3 text-right font-medium">退货件数</th>
            <th className="py-2 pr-3 text-right font-medium">曝光</th>
            <th className="py-2 pr-3 text-right font-medium">点击</th>
            <th className="py-2 pr-3 text-right font-medium">Session</th>
            <th className="py-2 pr-3 text-right font-medium">销售额</th>
            <th className="py-2 pr-3 text-right font-medium">出库成本</th>
            <th className="py-2 pr-3 text-right font-medium">毛利</th>
          </tr>
        </thead>
        <tbody>
          {points.map((point) => (
            <tr key={point.date} className="border-border border-b">
              <td className="py-2 pr-3">{point.date}</td>
              <td className="py-2 pr-3 text-right">{trendSalesText(point)}</td>
              <td className="py-2 pr-3 text-right">{trendReturnedText(point)}</td>
              <td className="py-2 pr-3 text-right">{trendImpressionsText(point)}</td>
              <td className="py-2 pr-3 text-right">{trendClicksText(point)}</td>
              <td className="py-2 pr-3 text-right">{trendSessionsText(point)}</td>
              <td className="py-2 pr-3 text-right">{trendAmountsText(point.salesAmounts)}</td>
              <td className="py-2 pr-3 text-right">{trendAmountsText(point.cogsAmounts)}</td>
              <td className="py-2 pr-3 text-right">{trendAmountsText(point.grossProfitAmounts)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// ================================================================ 口径目录面板

function DefinitionPanel({ definitions }: { definitions: PerformanceMetricDefinitionView[] }) {
  if (!definitions || definitions.length === 0) {
    return <p className="text-copy-13 text-muted-foreground">还没有登记指标口径。</p>;
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <thead className="text-copy-13 text-muted-foreground">
          <tr className="border-border border-b">
            <th className="py-2 pr-3 font-medium">指标</th>
            <th className="py-2 pr-3 font-medium">单位</th>
            <th className="py-2 pr-3 font-medium">聚合</th>
            <th className="py-2 pr-3 font-medium">引用</th>
            <th className="py-2 pr-3 font-medium">可用性</th>
            <th className="py-2 pr-3 font-medium">口径说明</th>
          </tr>
        </thead>
        <tbody>
          {definitions.map((definition) => (
            <tr key={definition.metricCode} className="border-border border-b">
              <td className="py-2 pr-3">
                <div className="text-foreground">{definition.displayName}</div>
                <div className="text-copy-13 text-muted-foreground">{definition.metricCode}</div>
              </td>
              <td className="py-2 pr-3 text-muted-foreground">
                {metricUnitLabel(definition.unit)}
              </td>
              <td className="py-2 pr-3 text-muted-foreground">{definition.aggregation}</td>
              <td className="py-2 pr-3 text-muted-foreground">
                {definition.numeratorMetricCode && definition.denominatorMetricCode
                  ? `${definition.numeratorMetricCode} ${definition.aggregation === 'DERIVED_DIFF' ? '−' : '÷'} ${definition.denominatorMetricCode}`
                  : '—'}
              </td>
              <td className="py-2 pr-3">
                <Badge tone={definition.available ? 'success' : 'warning'}>
                  {definition.available ? '有数据' : '本轮不可用'}
                </Badge>
                {!definition.available && notDeliveredOwnerText(definition) && (
                  <div className="text-copy-13 text-muted-foreground mt-0.5">
                    {notDeliveredOwnerText(definition)}
                  </div>
                )}
              </td>
              <td className="py-2 pr-3 text-muted-foreground">
                {definition.description}
                {definition.availabilityNote && (
                  <div className="text-copy-13 text-muted-foreground">
                    {definition.availabilityNote}
                  </div>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
