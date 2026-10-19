'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import {
  CartesianGrid,
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
  RefreshCw,
  Search,
  TriangleAlert,
  TrendingDown,
} from 'lucide-react';
import {
  accuracyBanner,
  accuracyLabel,
  alertTypeHint,
  alertTypeLabel,
  crawledAtLabel,
  emptyAlertsMessage,
  emptyDetailMessage,
  emptyKeywordsMessage,
  fetchAlerts,
  fetchCapabilities,
  fetchDetail,
  fetchFilterOptions,
  fetchHistory,
  fetchKeywords,
  fetchReport,
  fetchTrend,
  formatAverageRank,
  formatRank,
  pageSummary,
  rankChangeDirection,
  rankChangeText,
  rankDefinitionBanner,
  siteLabel,
  thresholdLabel,
  trendSeries,
  unavailableDimensions,
  type ListingKeywordRankingAlertPage,
  type ListingKeywordRankingCapabilities,
  type ListingKeywordRankingDetail,
  type ListingKeywordRankingFilterOptions,
  type ListingKeywordRankingHistoryPage,
  type ListingKeywordRankingKeywordPage,
  type ListingKeywordRankingReport,
  type ListingKeywordRankingTrend,
} from './listing-keyword-ranking-model';

/**
 * 4.5.1.2 关键词排名 工作区。
 *
 * 蓝图：「监控商品在核心关键词下的自然排名与广告排名，记录历史变化，低于阈值时预警。
 * 输入：关键词集合（按商品维护）；排名抓取任务；阈值设置。
 * 输出：排名历史表 + 变化预警 + 关键词维度报表。
 * 关系：上游 = 抓取任务（02 通道）；下游 = 1 工作台预警、14 广告。
 * **排名数据依赖抓取频率与平台限制，必须明确标注抓取时间与可能的不准确窗口**。」
 *
 * 🔴 页面上的六段（顺序就是阅读顺序）：
 *
 * 1. **抓取时间与不准确窗口的标注**（放最上面）—— 蓝图加粗句的**契约级**落点。
 *    每个名次旁边都带「抓取于 … · 可能有 ±N 分钟偏差」。不标就看不出那个名次
 *    是什么时候的、可能有多不准。
 * 2. **未接入的下游**（1 工作台预警 / 14 广告 / 02 抓取调度器）—— 显式写明「未接入」
 *    与归属模块，**不显示假数据**，也不给一个必然 404 的按钮。
 * 3. **关键词集合**（真分页 + 筛选 + 搜索）—— 按商品维护的关键词 + 阈值设置。
 * 4. **排名历史 + 趋势**（选中一个关键词后）—— 逐次抓取的名次与变化。
 * 5. **变化预警**（真分页）—— 预警是**记录**，这里提供「确认」而不是「推送」。
 * 6. **关键词维度报表** —— 真 SQL 聚合，不是前端拼。
 *
 * 🔴 三态齐全且文案不同：加载中 / 出错了（带重试）/ 空态。
 */
export function ListingKeywordRankingWorkspace() {
  const [caps, setCaps] = useState<ListingKeywordRankingCapabilities | null>(null);
  const [options, setOptions] = useState<ListingKeywordRankingFilterOptions | null>(null);
  const [page, setPage] = useState<ListingKeywordRankingKeywordPage | null>(null);
  const [detail, setDetail] = useState<ListingKeywordRankingDetail | null>(null);
  const [history, setHistory] = useState<ListingKeywordRankingHistoryPage | null>(null);
  const [trend, setTrend] = useState<ListingKeywordRankingTrend | null>(null);
  const [alerts, setAlerts] = useState<ListingKeywordRankingAlertPage | null>(null);
  const [report, setReport] = useState<ListingKeywordRankingReport | null>(null);

  // ---- 筛选
  const [shopCode, setShopCode] = useState('');
  const [platformCode, setPlatformCode] = useState('');
  const [siteCode, setSiteCode] = useState('');
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('ALL');
  const [listPage, setListPage] = useState(0);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');

  // ---- 选中的关键词
  const [selected, setSelected] = useState<string | null>(null);
  const [granularity, setGranularity] = useState('DAY');

  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, optionsResult, keywordPage] = await Promise.all([
        fetchCapabilities(),
        fetchFilterOptions(),
        // 🔴 exactOptionalPropertyTypes：可选属性**不能显式传 undefined**，
        //    所以筛选项是「有值才带上」，不是「带上 undefined」。
        fetchKeywords({
          ...(shopCode ? { shopCode } : {}),
          ...(platformCode ? { platformCode } : {}),
          ...(siteCode ? { siteCode } : {}),
          ...(keyword ? { keyword } : {}),
          status,
          page: listPage,
          size: 20,
        }),
      ]);
      setCaps(capsResult);
      setOptions(optionsResult);
      setPage(keywordPage);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [shopCode, platformCode, siteCode, keyword, status, listPage]);

  // 预警与报表是**独立**的两块数据，与关键词列表各自加载
  const loadAlertsAndReport = useCallback(async () => {
    try {
      const [alertPage, reportResult] = await Promise.all([
        fetchAlerts({ page: 0, size: 20 }),
        fetchReport({
          ...(from ? { from } : {}),
          ...(to ? { to } : {}),
        }),
      ]);
      setAlerts(alertPage);
      setReport(reportResult);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    }
  }, [from, to]);

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    void loadAlertsAndReport();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const loadKeyword = useCallback(
    async (keywordId: string) => {
      setSelected(keywordId);
      setDetailLoading(true);
      setError(null);
      try {
        const [detailResult, historyResult, trendResult] = await Promise.all([
          fetchDetail(keywordId),
          fetchHistory(keywordId, {
            ...(from ? { from } : {}),
            ...(to ? { to } : {}),
            page: 0,
            size: 20,
          }),
          fetchTrend(keywordId, {
            ...(from ? { from } : {}),
            ...(to ? { to } : {}),
            granularity,
          }),
        ]);
        setDetail(detailResult);
        setHistory(historyResult);
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
    () => trendSeries(trend?.points ?? [], 'lastNaturalRank'),
    [trend],
  );

  return (
    <div className="space-y-4">
      <PageHeader
        title="关键词排名"
        description="监控商品在核心关键词下的自然排名与广告排名，记录历史变化，低于阈值时预警。"
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

      {/* ---- 🔴 抓取时间与不准确窗口的标注（蓝图加粗句，契约级要求） ---- */}
      <div className="border-info/40 bg-info/5 text-foreground flex items-start gap-3 rounded-lg border px-4 py-3 text-label-13">
        <Badge tone="info" className="mt-0.5 shrink-0">
          抓取时间
        </Badge>
        <span className="flex-1">{accuracyBanner(caps)}</span>
      </div>

      {/* ---- 🔴 未接入的下游 ---- */}
      {missing.length > 0 && (
        <SectionCard
          title="未接入的下游"
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
          正在加载关键词排名数据…
        </div>
      ) : (
        <>
          {/* ---- 🔴 关键词集合（按商品维护） ---- */}
          <SectionCard
            title="关键词集合"
            description="按商品维护的核心关键词与阈值设置。点一行查看该关键词的排名历史与趋势。"
            extra={
              <span className="text-muted-foreground text-label-12">
                {page ? pageSummary(page) : '—'}
              </span>
            }
          >
            <div className="mb-4 flex flex-wrap items-end gap-3">
              <div className="flex flex-col gap-1">
                <label className="text-muted-foreground text-label-12" htmlFor="lkr-shop">
                  店铺编码
                </label>
                <Input
                  id="lkr-shop"
                  value={shopCode}
                  placeholder="留空 = 全部"
                  className="h-8 w-36"
                  onChange={(e) => setShopCode(e.target.value)}
                />
              </div>
              <div className="flex flex-col gap-1">
                <label className="text-muted-foreground text-label-12" htmlFor="lkr-platform">
                  平台
                </label>
                <select
                  id="lkr-platform"
                  value={platformCode}
                  className="border-border h-8 rounded-md border px-2 text-label-13"
                  onChange={(e) => setPlatformCode(e.target.value)}
                >
                  <option value="">全部平台</option>
                  {(options?.platforms ?? []).map((p) => (
                    <option key={p.code} value={p.code}>
                      {p.name}（{p.code}）
                    </option>
                  ))}
                </select>
              </div>
              <div className="flex flex-col gap-1">
                <label className="text-muted-foreground text-label-12" htmlFor="lkr-site">
                  站点
                </label>
                <select
                  id="lkr-site"
                  value={siteCode}
                  className="border-border h-8 rounded-md border px-2 text-label-13"
                  onChange={(e) => setSiteCode(e.target.value)}
                >
                  <option value="">全部站点</option>
                  {(options?.sites ?? []).map((s) => (
                    <option key={`${s.platformCode}:${s.code}`} value={s.code}>
                      {s.name}（{s.code}）
                    </option>
                  ))}
                </select>
              </div>
              <div className="flex flex-col gap-1">
                <label className="text-muted-foreground text-label-12" htmlFor="lkr-status">
                  启用状态
                </label>
                <select
                  id="lkr-status"
                  value={status}
                  className="border-border h-8 rounded-md border px-2 text-label-13"
                  onChange={(e) => setStatus(e.target.value)}
                >
                  <option value="ALL">全部</option>
                  <option value="ACTIVE">启用中</option>
                  <option value="INACTIVE">已停用</option>
                </select>
              </div>
              <div className="flex flex-col gap-1">
                <label className="text-muted-foreground text-label-12" htmlFor="lkr-keyword">
                  关键词
                </label>
                <Input
                  id="lkr-keyword"
                  value={keyword}
                  placeholder="模糊搜索"
                  className="h-8 w-44"
                  onChange={(e) => setKeyword(e.target.value)}
                />
              </div>
              <Button size="sm" onClick={() => void load()}>
                <Search className="mr-2 size-4" />
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
                icon={<Search className="size-8 text-muted-foreground" />}
                title="没有关键词"
                description={emptyKeywordsMessage(page?.total ?? 0)}
              />
            ) : (
              <>
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-label-13">
                    <thead className="text-muted-foreground border-border border-b">
                      <tr>
                        <th className="px-4 py-2 font-medium">关键词</th>
                        <th className="px-4 py-2 font-medium">商品</th>
                        <th className="px-4 py-2 font-medium">平台 / 站点</th>
                        <th className="px-4 py-2 font-medium">自然排名</th>
                        <th className="px-4 py-2 font-medium">广告排名</th>
                        <th className="px-4 py-2 font-medium">阈值</th>
                        <th className="px-4 py-2 font-medium">🔴 抓取时间与窗口</th>
                        <th className="px-4 py-2 font-medium">预警</th>
                      </tr>
                    </thead>
                    <tbody>
                      {page.items.map((row) => (
                        <tr
                          key={row.keywordId}
                          className={`border-border cursor-pointer border-b last:border-0 ${
                            selected === row.keywordId ? 'bg-muted' : ''
                          }`}
                          onClick={() => void loadKeyword(row.keywordId)}
                        >
                          <td className="text-foreground px-4 py-2">
                            {row.keyword}
                            {!row.active && (
                              <Badge tone="neutral" className="ml-2">
                                已停用
                              </Badge>
                            )}
                          </td>
                          <td className="px-4 py-2">
                            {row.title || row.externalItemId}
                            <div className="text-muted-foreground text-label-12">
                              {row.shopCode} · {row.externalItemId}
                            </div>
                          </td>
                          <td className="px-4 py-2">
                            {row.platformCode} · {siteLabel(row.siteCode)}
                          </td>
                          <td className="px-4 py-2">
                            {formatRank(row.latestNaturalRank)}
                            <div className="text-muted-foreground text-label-12">
                              {rankChangeText(row.naturalRankChange)}
                            </div>
                          </td>
                          <td className="px-4 py-2">
                            {formatRank(row.latestAdRank)}
                            <div className="text-muted-foreground text-label-12">
                              {rankChangeText(row.adRankChange)}
                            </div>
                          </td>
                          <td className="text-muted-foreground px-4 py-2 text-label-12">
                            自然 {thresholdLabel(row.naturalRankThreshold)}
                            <br />
                            广告 {thresholdLabel(row.adRankThreshold)}
                            <br />
                            退步 {thresholdLabel(row.rankDropThreshold)}
                          </td>
                          <td className="text-muted-foreground px-4 py-2 text-label-12">
                            {accuracyLabel(row.latestCrawledAt, row.latestAccuracyWindowMinutes)}
                          </td>
                          <td className="px-4 py-2">
                            {row.openAlertCount > 0 ? (
                              <Badge tone="warning">{row.openAlertCount} 条未确认</Badge>
                            ) : (
                              <span className="text-muted-foreground text-label-12">
                                共 {row.totalAlertCount} 条
                              </span>
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

          {/* ---- 🔴 排名明细 + 历史 + 趋势 ---- */}
          <SectionCard
            title="排名明细与趋势"
            description={
              detail
                ? `${detail.keyword} · ${detail.platformCode} · ${siteLabel(detail.siteCode)} · ${
                    detail.shopCode
                  }`
                : '在上方列表里点一个关键词，查看它的排名历史与趋势。'
            }
            extra={
              <div className="flex items-center gap-2">
                <select
                  value={granularity}
                  aria-label="趋势粒度"
                  className="border-border h-8 rounded-md border px-2 text-label-13"
                  onChange={(e) => setGranularity(e.target.value)}
                >
                  <option value="DAY">按日</option>
                  <option value="WEEK">按周</option>
                  <option value="MONTH">按月</option>
                </select>
                <Button size="sm" variant="secondary" onClick={() => void loadAlertsAndReport()}>
                  刷新报表
                </Button>
              </div>
            }
          >
            {detailLoading ? (
              <div className="text-muted-foreground flex items-center gap-2 px-1 py-8 text-label-13">
                <Loader2 className="size-4 animate-spin" />
                正在加载排名明细…
              </div>
            ) : !detail ? (
              <EmptyState
                icon={<TrendingDown className="size-8 text-muted-foreground" />}
                title="还没有选中关键词"
                description="在上方的关键词集合里点一行，这里会显示该关键词的名次、排名历史与趋势。"
              />
            ) : !detail.hasData ? (
              <EmptyState
                icon={<TrendingDown className="size-8 text-muted-foreground" />}
                title="这个关键词还没有排名数据"
                description={emptyDetailMessage(detail)}
              />
            ) : (
              <div className="space-y-5">
                <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                  <StatCard
                    label="自然排名"
                    value={formatRank(detail.latestNaturalRank)}
                    comparisonLabel={`较上次 ${rankChangeText(detail.naturalRankChange)}`}
                    polarity="negative"
                    deltaDirection={
                      rankChangeDirection(detail.naturalRankChange) === 'unknown'
                        ? 'flat'
                        : rankChangeDirection(detail.naturalRankChange) === 'up'
                          ? 'up'
                          : rankChangeDirection(detail.naturalRankChange) === 'down'
                            ? 'down'
                            : 'flat'
                    }
                  />
                  <StatCard
                    label="广告排名"
                    value={formatRank(detail.latestAdRank)}
                    comparisonLabel={`较上次 ${rankChangeText(detail.adRankChange)}`}
                    polarity="negative"
                    deltaDirection={
                      rankChangeDirection(detail.adRankChange) === 'unknown'
                        ? 'flat'
                        : rankChangeDirection(detail.adRankChange) === 'up'
                          ? 'up'
                          : rankChangeDirection(detail.adRankChange) === 'down'
                            ? 'down'
                            : 'flat'
                    }
                  />
                  <StatCard
                    label="抓取次数"
                    value={String(detail.crawlCount)}
                    comparisonLabel={`排名历史 ${detail.snapshotCount} 条`}
                    polarity="neutral"
                  />
                  <StatCard
                    label="预警"
                    value={String(detail.totalAlertCount)}
                    comparisonLabel={`未确认 ${detail.openAlertCount} 条`}
                    polarity="neutral"
                  />
                </div>

                {/* 🔴 抓取时间与不准确窗口（明细区顶部固定） */}
                <div className="border-border bg-muted/40 text-muted-foreground rounded-md border px-3 py-2 text-label-12">
                  🔴 数据截至 {crawledAtLabel(detail.latestCrawledAt)} · 可能有 ±
                  {detail.latestAccuracyWindowMinutes ?? '—'} 分钟偏差 —— 名次是抓取那一刻平台给出的
                  位置，超过窗口后不再代表当前排名。
                </div>

                <div className="text-muted-foreground text-label-12">{rankDefinitionBanner(caps)}</div>

                {/* ---- 趋势图 ---- */}
                <div>
                  <div className="text-foreground mb-2 text-label-13 font-medium">自然排名趋势</div>
                  {!trend || trend.points.length === 0 ? (
                    <EmptyState
                      icon={<TrendingDown className="size-8 text-muted-foreground" />}
                      title="这个区间没有排名数据"
                      description="没有数据时**不画平线** —— 一条平的假曲线会让人以为「排名没变」，而真相是「没抓到数据」。"
                    />
                  ) : (
                    <div className="h-64 w-full">
                      <ResponsiveContainer width="100%" height="100%">
                        <LineChart data={chartData}>
                          <CartesianGrid strokeDasharray="3 3" className="stroke-border" />
                          <XAxis dataKey="bucket" className="text-label-12" />
                          <YAxis
                            reversed
                            className="text-label-12"
                            allowDecimals={false}
                            label={{ value: '名次（越小越好）', angle: -90, position: 'insideLeft' }}
                          />
                          <Tooltip
                            formatter={(value: number | string) => [
                              // 🔴 null（平台没报名次）显示成「—」，不是「第 null 名」
                              typeof value === 'number' && Number.isFinite(value)
                                ? `第 ${value} 名`
                                : '—',
                              '自然排名',
                            ]}
                          />
                          <Line
                            type="monotone"
                            dataKey="value"
                            name="自然排名"
                            stroke="var(--color-brand)"
                            strokeWidth={2}
                            connectNulls={false}
                          />
                        </LineChart>
                      </ResponsiveContainer>
                    </div>
                  )}
                </div>

                {/* ---- 排名历史 ---- */}
                <div>
                  <div className="text-foreground mb-2 text-label-13 font-medium">
                    排名历史（{history ? pageSummary(history) : '—'}）
                  </div>
                  {!history || history.items.length === 0 ? (
                    <EmptyState
                      icon={<TrendingDown className="size-8 text-muted-foreground" />}
                      title="没有排名历史"
                      description="这个区间里没有抓取记录。"
                    />
                  ) : (
                    <div className="overflow-x-auto">
                      <table className="w-full text-left text-label-13">
                        <thead className="text-muted-foreground border-border border-b">
                          <tr>
                            <th className="px-4 py-2 font-medium">抓取时间</th>
                            <th className="px-4 py-2 font-medium">自然排名</th>
                            <th className="px-4 py-2 font-medium">广告排名</th>
                            <th className="px-4 py-2 font-medium">较上次</th>
                            <th className="px-4 py-2 font-medium">🔴 不准确窗口</th>
                            <th className="px-4 py-2 font-medium">来源</th>
                          </tr>
                        </thead>
                        <tbody>
                          {history.items.map((row) => (
                            <tr key={row.snapshotId} className="border-border border-b last:border-0">
                              <td className="px-4 py-2">{crawledAtLabel(row.crawledAt)}</td>
                              <td className="px-4 py-2">{formatRank(row.naturalRank)}</td>
                              <td className="px-4 py-2">{formatRank(row.adRank)}</td>
                              <td className="px-4 py-2">
                                {rankChangeText(row.naturalRankChange)}
                                <div className="text-muted-foreground text-label-12">
                                  广告 {rankChangeText(row.adRankChange)}
                                </div>
                              </td>
                              <td className="text-muted-foreground px-4 py-2 text-label-12">
                                ±{row.accuracyWindowMinutes} 分钟
                              </td>
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
                  )}
                </div>
              </div>
            )}
          </SectionCard>

          {/* ---- 🔴 变化预警（记录，不发通知） ---- */}
          <SectionCard
            title="变化预警"
            description="低于阈值或退步超阈值时产生**记录**。本模块不发通知 —— 推送归 1 工作台预警（整卷未交付）。"
            extra={
              <span className="text-muted-foreground text-label-12">
                {alerts ? pageSummary(alerts) : '—'} · 未确认 {alerts?.openCount ?? 0} 条
              </span>
            }
          >
            {!alerts || alerts.items.length === 0 ? (
              <EmptyState
                icon={<TriangleAlert className="size-8 text-muted-foreground" />}
                title="没有预警记录"
                description={emptyAlertsMessage(alerts?.openCount ?? 0)}
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-label-13">
                  <thead className="text-muted-foreground border-border border-b">
                    <tr>
                      <th className="px-4 py-2 font-medium">关键词</th>
                      <th className="px-4 py-2 font-medium">类型</th>
                      <th className="px-4 py-2 font-medium">名次</th>
                      <th className="px-4 py-2 font-medium">上次</th>
                      <th className="px-4 py-2 font-medium">阈值</th>
                      <th className="px-4 py-2 font-medium">判定时刻</th>
                      <th className="px-4 py-2 font-medium">状态</th>
                    </tr>
                  </thead>
                  <tbody>
                    {alerts.items.map((row) => (
                      <tr key={row.alertId} className="border-border border-b last:border-0">
                        <td className="text-foreground px-4 py-2">
                          {row.keyword}
                          <div className="text-muted-foreground text-label-12">
                            {row.title} · {row.shopCode}
                          </div>
                        </td>
                        <td className="px-4 py-2">
                          <Badge tone="warning">{alertTypeLabel(row.alertType)}</Badge>
                          <div className="text-muted-foreground text-label-12">
                            {alertTypeHint(row.alertType)}
                          </div>
                        </td>
                        <td className="px-4 py-2">{formatRank(row.rankValue)}</td>
                        <td className="px-4 py-2">{formatRank(row.previousRank)}</td>
                        <td className="px-4 py-2">{formatRank(row.thresholdValue)}</td>
                        <td className="px-4 py-2">{crawledAtLabel(row.detectedAt)}</td>
                        <td className="px-4 py-2">
                          {row.acknowledgedAt ? (
                            <Badge tone="success">已确认</Badge>
                          ) : (
                            <Badge tone="warning">未确认</Badge>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </SectionCard>

          {/* ---- 🔴 关键词维度报表 ---- */}
          <SectionCard
            title="关键词维度报表"
            description="按（平台, 站点, 关键词）聚合 —— 真 SQL 聚合，不是前端拼。没有样本时平均名次是「—」。"
            extra={
              <div className="flex items-end gap-2">
                <div className="flex flex-col gap-1">
                  <label className="text-muted-foreground text-label-12" htmlFor="lkr-from">
                    起始日期
                  </label>
                  <Input
                    id="lkr-from"
                    type="date"
                    value={from}
                    className="h-8 w-36"
                    onChange={(e) => setFrom(e.target.value)}
                  />
                </div>
                <div className="flex flex-col gap-1">
                  <label className="text-muted-foreground text-label-12" htmlFor="lkr-to">
                    结束日期
                  </label>
                  <Input
                    id="lkr-to"
                    type="date"
                    value={to}
                    className="h-8 w-36"
                    onChange={(e) => setTo(e.target.value)}
                  />
                </div>
                <Button size="sm" onClick={() => void loadAlertsAndReport()}>
                  查询
                </Button>
              </div>
            }
          >
            {!report || report.rows.length === 0 ? (
              <EmptyState
                icon={<BarChart3 className="size-8 text-muted-foreground" />}
                title="没有报表数据"
                description="这个区间里还没有排名样本。报表是排名历史的聚合，没有样本时是空数组（不是一行 0）。"
              />
            ) : (
              <>
                <div className="mb-3 text-muted-foreground text-label-12">
                  {report.keywordCount} 个关键词 · {report.rows.length} 行
                </div>
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-label-13">
                    <thead className="text-muted-foreground border-border border-b">
                      <tr>
                        <th className="px-4 py-2 font-medium">关键词</th>
                        <th className="px-4 py-2 font-medium">平台 / 站点</th>
                        <th className="px-4 py-2 font-medium">监控商品数</th>
                        <th className="px-4 py-2 font-medium">样本数</th>
                        <th className="px-4 py-2 font-medium">平均自然名次</th>
                        <th className="px-4 py-2 font-medium">最好 / 最差</th>
                        <th className="px-4 py-2 font-medium">平均广告名次</th>
                        <th className="px-4 py-2 font-medium">低于阈值</th>
                        <th className="px-4 py-2 font-medium">未确认预警</th>
                        <th className="px-4 py-2 font-medium">🔴 最近抓取</th>
                      </tr>
                    </thead>
                    <tbody>
                      {report.rows.map((row) => (
                        <tr
                          key={`${row.platformCode}:${row.siteCode}:${row.keyword}`}
                          className="border-border border-b last:border-0"
                        >
                          <td className="text-foreground px-4 py-2">{row.keyword}</td>
                          <td className="px-4 py-2">
                            {row.platformCode} · {siteLabel(row.siteCode)}
                          </td>
                          <td className="px-4 py-2">{row.listingCount}</td>
                          <td className="px-4 py-2">{row.sampleCount}</td>
                          <td className="px-4 py-2">{formatAverageRank(row.averageNaturalRank)}</td>
                          <td className="px-4 py-2">
                            {formatRank(row.bestNaturalRank)} / {formatRank(row.worstNaturalRank)}
                          </td>
                          <td className="px-4 py-2">{formatAverageRank(row.averageAdRank)}</td>
                          <td className="px-4 py-2">{row.belowThresholdCount}</td>
                          <td className="px-4 py-2">
                            {row.openAlertCount > 0 ? (
                              <Badge tone="warning">{row.openAlertCount}</Badge>
                            ) : (
                              <span className="text-muted-foreground text-label-12">0</span>
                            )}
                          </td>
                          <td className="text-muted-foreground px-4 py-2 text-label-12">
                            {accuracyLabel(row.lastCrawledAt, row.accuracyWindowMinutes)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </>
            )}
          </SectionCard>
        </>
      )}
    </div>
  );
}
