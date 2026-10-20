'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import {
  BarChart3,
  ChevronLeft,
  ChevronRight,
  Loader2,
  PackageSearch,
  RefreshCw,
  TriangleAlert,
} from 'lucide-react';
import {
  changeText,
  comparisonEntryOf,
  emptyDetailMessage,
  emptySnapshotsMessage,
  fetchCapabilities,
  fetchDetail,
  fetchSnapshots,
  fetchTrend,
  formatMetric,
  metricLabel,
  metricUnit,
  pageSummary,
  periodLabel,
  PLATFORM_SOURCE_BADGE,
  sourceBanner,
  trendSeries,
  unavailableDimensions,
  METRIC_OPTIONS,
  type ListingPerformanceCapabilities,
  type ListingPerformanceDetail,
  type ListingPerformanceSnapshotPage,
  type ListingPerformanceTrend,
} from './listing-performance-model';

/**
 * 4.5.1.1 Listing 表现 工作区。
 *
 * 蓝图：「单个 listing 的表现明细：曝光、点击、转化率、销量、销售额、Session、退货率、
 * 评分变化，支持时间范围对比。输出：表现指标 + 趋势图；下钻到 13 产品表现。
 * **表现数据来自平台而非内部订单**，与内部订单口径不同，展示时需标注来源。」
 *
 * 🔴 页面上的五段（顺序就是阅读顺序）：
 *
 * 1. **数据来源标注**（放最上面）—— 蓝图加粗句「展示时需标注来源」。每个指标卡也带
 *    「平台口径」角标。平台销量与内部订单行数**天然对不上**，不标就会有人拿错口径做决策。
 * 2. **未接入的下钻**（13 产品表现 / 14 广告）—— 显式写明「未接入」与归属模块，
 *    **不显示假数据**，也不给一个必然 404 的按钮。
 * 3. **表现快照列表**（真分页 + 筛选）—— 平台回报的按日明细，点一行选中该商品。
 * 4. **表现明细 + 时间范围对比** —— 本期 vs 上期，逐指标变化率。
 * 5. **趋势图** —— 日 / 周 / 月；**没有数据时是空态，不是平线**。
 *
 * 🔴 三态齐全且文案不同：加载中 / 出错了（带重试）/ 空态。
 * 空态还分两种：「还没同步过」与「同步过但区间内没数据」—— 那是两回事。
 */
export function ListingPerformanceWorkspace() {
  const [caps, setCaps] = useState<ListingPerformanceCapabilities | null>(null);
  const [page, setPage] = useState<ListingPerformanceSnapshotPage | null>(null);
  const [detail, setDetail] = useState<ListingPerformanceDetail | null>(null);
  const [trend, setTrend] = useState<ListingPerformanceTrend | null>(null);

  // ---- 筛选
  const [shopCode, setShopCode] = useState('');
  const [platformCode, setPlatformCode] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [listPage, setListPage] = useState(0);

  // ---- 选中的商品
  const [selected, setSelected] = useState<string | null>(null);
  const [granularity, setGranularity] = useState('DAY');
  const [trendMetric, setTrendMetric] = useState('impressions');

  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, snapshotPage] = await Promise.all([
        fetchCapabilities(),
        // 🔴 exactOptionalPropertyTypes：可选属性**不能显式传 undefined**，
        //    所以筛选项是「有值才带上」，不是「带上 undefined」。
        fetchSnapshots({
          ...(shopCode ? { shopCode } : {}),
          ...(platformCode ? { platformCode } : {}),
          ...(from ? { from } : {}),
          ...(to ? { to } : {}),
          page: listPage,
          size: 20,
        }),
      ]);
      setCaps(capsResult);
      setPage(snapshotPage);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [shopCode, platformCode, from, to, listPage]);

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const loadListing = useCallback(
    async (listingId: string) => {
      setSelected(listingId);
      setDetailLoading(true);
      setError(null);
      try {
        const [detailResult, trendResult] = await Promise.all([
          fetchDetail(listingId, {
            ...(from ? { from } : {}),
            ...(to ? { to } : {}),
          }),
          fetchTrend(listingId, {
            ...(from ? { from } : {}),
            ...(to ? { to } : {}),
            granularity,
          }),
        ]);
        setDetail(detailResult);
        setTrend(trendResult);
      } catch (e) {
        setError(e instanceof Error ? e.message : '加载失败，请重试');
      } finally {
        setDetailLoading(false);
      }
    },
    [from, to, granularity],
  );

  const missing = useMemo(() => unavailableDimensions(caps), [caps]);
  const chartData = useMemo(
    () => trendSeries(trend?.points ?? [], trendMetric),
    [trend, trendMetric],
  );
  const trendUnit = useMemo(() => metricUnit(trendMetric), [trendMetric]);
  const isRate = trendUnit === 'RATE';

  return (
    <div className="space-y-4">
      <PageHeader
        title="Listing 表现"
        description="单个 listing 的平台表现明细：曝光、点击、转化率、销量、销售额、Session、退货率、评分变化，支持时间范围对比。"
        actions={
          <Button size="sm" variant="secondary" onClick={() => void load()}>
            <RefreshCw className="mr-2 size-4" />
            刷新
          </Button>
        }
      />

      {error && (
        <div className="border-destructive/40 bg-destructive/5 text-destructive flex items-center gap-2 rounded-lg border px-4 py-3 text-label-13">
          <TriangleAlert className="size-4 shrink-0" />
          <span className="flex-1">{error}</span>
          <Button size="sm" variant="secondary" onClick={() => void load()}>
            <RefreshCw className="mr-2 size-4" />
            重试
          </Button>
        </div>
      )}

      {/* ---- 🔴 数据来源标注（蓝图加粗句：展示时需标注来源） ---- */}
      <div className="border-info/40 bg-info/5 text-foreground flex items-start gap-3 rounded-lg border px-4 py-3 text-label-13">
        <Badge tone="info" className="mt-0.5 shrink-0">
          {PLATFORM_SOURCE_BADGE}
        </Badge>
        <span className="flex-1">{sourceBanner(caps)}</span>
      </div>

      {/* ---- 🔴 未接入的下钻（13 / 14） ---- */}
      {missing.length > 0 && (
        <SectionCard
          title="未接入的下钻"
          description="下面这些能力属于其它模块，尚未交付 —— 这里显式列出归属模块，不显示假数据。"
        >
          <ul className="space-y-2">
            {missing.map((d) => (
              <li
                key={d.dimension}
                className="border-border flex flex-wrap items-start gap-2 rounded-md border px-3 py-2"
              >
                <Badge tone="warning" className="shrink-0">
                  未接入
                </Badge>
                <span className="text-foreground">{d.description}</span>
                <span className="text-muted-foreground text-label-12">
                  归属模块：{d.ownerModule} · {d.impact}
                </span>
              </li>
            ))}
          </ul>
        </SectionCard>
      )}

      {loading && !page ? (
        <div className="text-muted-foreground flex items-center gap-2 px-1 py-8 text-label-13">
          <Loader2 className="size-4 animate-spin" />
          正在加载平台表现数据…
        </div>
      ) : (
        <>
          {/* ---- 表现快照列表 ---- */}
          <SectionCard
            title="表现快照"
            description="平台回报的按日明细。点一行查看该商品的表现明细与趋势。"
            extra={
              <span className="text-muted-foreground text-label-12">
                {page ? pageSummary(page) : '—'} · 同步结果 {page?.syncCount ?? 0} 次
              </span>
            }
          >
            <div className="mb-4 flex flex-wrap items-end gap-3">
              <div className="flex flex-col gap-1">
                <label className="text-muted-foreground text-label-12" htmlFor="lp-shop">
                  店铺编码
                </label>
                <Input
                  id="lp-shop"
                  value={shopCode}
                  placeholder="留空 = 全部"
                  className="h-8 w-40"
                  onChange={(e) => setShopCode(e.target.value)}
                />
              </div>
              <div className="flex flex-col gap-1">
                <label className="text-muted-foreground text-label-12" htmlFor="lp-platform">
                  平台编码
                </label>
                <Input
                  id="lp-platform"
                  value={platformCode}
                  placeholder="留空 = 全部"
                  className="h-8 w-40"
                  onChange={(e) => setPlatformCode(e.target.value)}
                />
              </div>
              <div className="flex flex-col gap-1">
                <label className="text-muted-foreground text-label-12" htmlFor="lp-from">
                  起始统计日
                </label>
                <Input
                  id="lp-from"
                  type="date"
                  value={from}
                  className="h-8 w-40"
                  onChange={(e) => setFrom(e.target.value)}
                />
              </div>
              <div className="flex flex-col gap-1">
                <label className="text-muted-foreground text-label-12" htmlFor="lp-to">
                  结束统计日
                </label>
                <Input
                  id="lp-to"
                  type="date"
                  value={to}
                  className="h-8 w-40"
                  onChange={(e) => setTo(e.target.value)}
                />
              </div>
              <Button size="sm" onClick={() => void load()}>
                查询
              </Button>
              {listPage > 0 && (
                <Button size="sm" variant="secondary" onClick={() => setListPage(0)}>
                  回到第一页
                </Button>
              )}
            </div>

            {!page || page.items.length === 0 ? (
              <EmptyState
                icon={<PackageSearch className="size-8 text-muted-foreground" />}
                title="没有平台表现数据"
                description={emptySnapshotsMessage(page?.syncCount ?? 0)}
              />
            ) : (
              <>
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-label-13">
                    <thead className="text-muted-foreground border-border border-b">
                      <tr>
                        <th className="px-4 py-2 font-medium">商品</th>
                        <th className="px-4 py-2 font-medium">统计日</th>
                        <th className="px-4 py-2 font-medium">曝光</th>
                        <th className="px-4 py-2 font-medium">点击</th>
                        <th className="px-4 py-2 font-medium">点击率</th>
                        <th className="px-4 py-2 font-medium">Session</th>
                        <th className="px-4 py-2 font-medium">销量</th>
                        <th className="px-4 py-2 font-medium">销售额</th>
                        <th className="px-4 py-2 font-medium">退货率</th>
                        <th className="px-4 py-2 font-medium">评分</th>
                        <th className="px-4 py-2 font-medium">来源</th>
                      </tr>
                    </thead>
                    <tbody>
                      {page.items.map((row) => (
                        <tr
                          key={row.snapshotId}
                          className={`border-border cursor-pointer border-b last:border-0 ${
                            selected === row.listingId ? 'bg-muted' : ''
                          }`}
                          onClick={() => void loadListing(row.listingId)}
                        >
                          <td className="text-foreground px-4 py-2">
                            {row.title || row.externalItemId}
                            <div className="text-muted-foreground text-label-12">
                              {row.shopCode} · {row.platformCode} · {row.externalItemId}
                            </div>
                          </td>
                          <td className="px-4 py-2">{row.statDate}</td>
                          <td className="px-4 py-2">{formatMetric(row.impressions, 'COUNT')}</td>
                          <td className="px-4 py-2">{formatMetric(row.clicks, 'COUNT')}</td>
                          <td className="px-4 py-2">{formatMetric(row.ctr, 'RATE')}</td>
                          <td className="px-4 py-2">{formatMetric(row.sessions, 'COUNT')}</td>
                          <td className="px-4 py-2">
                            {formatMetric(row.unitsOrdered, 'COUNT')}
                            <Badge tone="neutral" className="ml-2">
                              {PLATFORM_SOURCE_BADGE}
                            </Badge>
                          </td>
                          <td className="px-4 py-2">
                            {formatMetric(row.salesAmount, 'AMOUNT', row.currency)}
                          </td>
                          <td className="px-4 py-2">{formatMetric(row.returnRate, 'RATE')}</td>
                          <td className="px-4 py-2">{formatMetric(row.rating, 'SCORE')}</td>
                          <td className="px-4 py-2">
                            <Badge tone="neutral">{row.ingestChannel}</Badge>
                            {row.revision > 1 && (
                              <div className="text-muted-foreground text-label-12">
                                已修订 {row.revision} 次
                              </div>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <div className="mt-3 flex items-center justify-end gap-2">
                  <Button
                    size="sm"
                    variant="secondary"
                    disabled={listPage === 0}
                    onClick={() => setListPage((p) => Math.max(0, p - 1))}
                  >
                    <ChevronLeft className="mr-1 size-4" />
                    上一页
                  </Button>
                  <Button
                    size="sm"
                    variant="secondary"
                    disabled={(page.page + 1) * page.size >= page.total}
                    onClick={() => setListPage((p) => p + 1)}
                  >
                    下一页
                    <ChevronRight className="ml-1 size-4" />
                  </Button>
                </div>
              </>
            )}
          </SectionCard>

          {/* ---- 🔴 表现明细 + 时间范围对比 ---- */}
          <SectionCard
            title="表现明细"
            description={
              detail
                ? `${detail.title || detail.externalItemId} · 本期 ${periodLabel(
                    detail.periodFrom,
                    detail.periodTo,
                  )} · 上期 ${periodLabel(detail.comparePeriodFrom, detail.comparePeriodTo)}`
                : '在上方列表里点一个商品，查看它的表现明细与时间范围对比。'
            }
            extra={
              detail ? (
                <span className="text-muted-foreground text-label-12">
                  同步 {detail.syncCount} 次
                  {detail.lastSyncAt
                    ? ` · 最近 ${new Date(detail.lastSyncAt).toLocaleString('zh-CN')}`
                    : ''}
                </span>
              ) : undefined
            }
          >
            {detailLoading ? (
              <div className="text-muted-foreground flex items-center gap-2 px-1 py-8 text-label-13">
                <Loader2 className="size-4 animate-spin" />
                正在加载表现明细…
              </div>
            ) : !detail ? (
              <EmptyState
                icon={<BarChart3 className="size-8 text-muted-foreground" />}
                title="还没有选中商品"
                description="在上方的表现快照列表里点一行，这里会显示该商品的表现指标与时间范围对比。"
              />
            ) : !detail.hasData ? (
              <EmptyState
                icon={<PackageSearch className="size-8 text-muted-foreground" />}
                title="这个区间没有平台表现数据"
                description={emptyDetailMessage(detail)}
              />
            ) : (
              <div className="space-y-5">
                <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                  {METRIC_OPTIONS.filter((m) => m.value !== 'ratingCount').map((m) => {
                    const entry = comparisonEntryOf(detail.comparison, m.value);
                    // 🔴 exactOptionalPropertyTypes：可选属性**不能显式传 undefined**，
                    //    所以变化率为 null（上期为 0）时是「不带这个 prop」，
                    //    不是「带上 undefined」—— 前者不画变化条，后者会被当成 0%。
                    const rate = entry?.changeRate ?? null;
                    return (
                      <StatCard
                        key={m.value}
                        label={
                          <span className="flex items-center gap-1">
                            {m.label}
                            <Badge tone="neutral" className="font-normal">
                              {PLATFORM_SOURCE_BADGE}
                            </Badge>
                          </span>
                        }
                        value={formatMetric(
                          entry?.current ?? null,
                          m.unit,
                          detail.current.currency,
                        )}
                        {...(rate === null
                          ? {}
                          : { delta: rate * 100 })}
                        deltaDirection={
                          rate === null ? 'flat' : rate > 0 ? 'up' : rate < 0 ? 'down' : 'flat'
                        }
                        polarity={
                          m.value === 'returnsCount' || m.value === 'returnAmount' || m.value === 'returnRate'
                            ? 'negative'
                            : m.value === 'rating'
                              ? 'neutral'
                              : 'positive'
                        }
                        comparisonLabel={`较上期 ${changeText(entry)}`}
                      />
                    );
                  })}
                </div>

                <div className="overflow-x-auto">
                  <table className="w-full text-left text-label-13">
                    <thead className="text-muted-foreground border-border border-b">
                      <tr>
                        <th className="px-4 py-2 font-medium">指标</th>
                        <th className="px-4 py-2 font-medium">本期</th>
                        <th className="px-4 py-2 font-medium">上期</th>
                        <th className="px-4 py-2 font-medium">变化</th>
                        <th className="px-4 py-2 font-medium">变化率</th>
                      </tr>
                    </thead>
                    <tbody>
                      {detail.comparison.entries.map((entry) => (
                        <tr key={entry.metric} className="border-border border-b last:border-0">
                          <td className="text-foreground px-4 py-2">
                            {metricLabel(entry.metric)}
                            <Badge tone="neutral" className="ml-2">
                              {PLATFORM_SOURCE_BADGE}
                            </Badge>
                          </td>
                          <td className="px-4 py-2">
                            {formatMetric(
                              entry.current,
                              entry.unit,
                              detail.current.currency,
                            )}
                          </td>
                          <td className="text-muted-foreground px-4 py-2">
                            {formatMetric(
                              entry.previous,
                              entry.unit,
                              detail.current.currency,
                            )}
                          </td>
                          <td className="px-4 py-2">
                            {formatMetric(entry.change, entry.unit, detail.current.currency)}
                          </td>
                          <td className="px-4 py-2">{changeText(entry)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            )}
          </SectionCard>

          {/* ---- 🔴 趋势图 ---- */}
          <SectionCard
            title="表现趋势"
            description="按平台统计日归集。没有平台数据时这里是空态，不会画一条假的平线。"
            extra={
              <div className="flex flex-wrap items-center gap-2">
                <select
                  aria-label="趋势粒度"
                  className="border-border bg-background h-8 rounded-md border px-2 text-label-13"
                  value={granularity}
                  onChange={(e) => {
                    setGranularity(e.target.value);
                    if (selected) void loadListing(selected);
                  }}
                >
                  <option value="DAY">按日</option>
                  <option value="WEEK">按周</option>
                  <option value="MONTH">按月</option>
                </select>
                <select
                  aria-label="趋势指标"
                  className="border-border bg-background h-8 rounded-md border px-2 text-label-13"
                  value={trendMetric}
                  onChange={(e) => setTrendMetric(e.target.value)}
                >
                  {METRIC_OPTIONS.map((m) => (
                    <option key={m.value} value={m.value}>
                      {m.label}
                    </option>
                  ))}
                </select>
              </div>
            }
          >
            {!trend ? (
              <EmptyState
                icon={<BarChart3 className="size-8 text-muted-foreground" />}
                title="还没有选中商品"
                description="选中一个商品后，这里显示它的平台表现趋势。"
              />
            ) : trend.points.length === 0 ? (
              <EmptyState
                icon={<BarChart3 className="size-8 text-muted-foreground" />}
                title="这个区间没有平台表现数据"
                description={`${periodLabel(trend.from, trend.to)} 区间内平台没有回报表现数据 —— 这里不画假曲线。`}
              />
            ) : (
              <>
                <ResponsiveContainer width="100%" height={280}>
                  {isRate ? (
                    <LineChart data={chartData}>
                      <CartesianGrid strokeDasharray="3 3" className="stroke-border" />
                      <XAxis dataKey="bucket" fontSize={12} />
                      <YAxis fontSize={12} tickFormatter={(v: number) => `${(v * 100).toFixed(1)}%`} />
                      <Tooltip
                        formatter={(value: number | string) => [
                          typeof value === 'number' ? formatMetric(value, 'RATE') : String(value),
                          metricLabel(trendMetric),
                        ]}
                      />
                      <Legend />
                      <Line
                        type="monotone"
                        dataKey="value"
                        name={metricLabel(trendMetric)}
                        stroke="#6366f1"
                        strokeWidth={2}
                        dot={{ r: 3 }}
                        connectNulls={false}
                      />
                    </LineChart>
                  ) : (
                    <BarChart data={chartData}>
                      <CartesianGrid strokeDasharray="3 3" className="stroke-border" />
                      <XAxis dataKey="bucket" fontSize={12} />
                      <YAxis fontSize={12} />
                      <Tooltip
                        formatter={(value: number | string) => [
                          typeof value === 'number'
                            ? formatMetric(value, trendUnit, detail?.current.currency)
                            : String(value),
                          metricLabel(trendMetric),
                        ]}
                      />
                      <Legend />
                      <Bar dataKey="value" name={metricLabel(trendMetric)} fill="#6366f1" radius={[4, 4, 0, 0]} />
                    </BarChart>
                  )}
                </ResponsiveContainer>
                <p className="text-muted-foreground mt-2 text-label-12">
                  🔴 {PLATFORM_SOURCE_BADGE}：{trend.sourceNote}
                </p>
              </>
            )}
          </SectionCard>
        </>
      )}
    </div>
  );
}
