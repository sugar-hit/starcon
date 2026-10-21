'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import {
  ChevronLeft,
  ChevronRight,
  Eye,
  LineChart,
  Loader2,
  RefreshCw,
  Sparkles,
  TriangleAlert,
} from 'lucide-react';
import {
  acknowledgeOpportunity,
  actionsBanner,
  changeTypeLabel,
  directionLabel,
  emptyComparisonMessage,
  emptyEventsMessage,
  fetchCapabilities,
  fetchComparison,
  fetchEvents,
  fetchOpportunities,
  fetchWatches,
  formatAmount,
  formatCount,
  formatRate,
  formatSignedRate,
  formatTime,
  listingStatusLabel,
  opportunityStatusHint,
  opportunityTypeLabel,
  pageSummary,
  rankBasisLabel,
  rankChangeText,
  REFERENCE_DATA_BADGE,
  SCRAPED_SOURCE_BADGE,
  severityLabel,
  sourceBanner,
  targetKindLabel,
  unavailableDimensions,
  watchStatusLabel,
  type ListingChangeCapabilities,
  type ListingChangeComparison,
  type ListingChangeEventPage,
  type ListingChangeOpportunityPage,
  type ListingChangeWatchPage,
} from './listing-change-model';

/**
 * 4.5.2.2 上新与变更监控 工作区。
 *
 * 蓝图：「监控目标 ASIN/竞品的上下架、价格、排名、评论数变化，识别市场机会（如竞品断货涨价）。
 * 输入：监控对象列表；抓取频率。输出：变更事件流 + 历史对比。关系：上游 = 抓取任务；
 * 下游 = 13 分析、1 工作台。**监控对象与自有商品分离**，竞品数据不进自有商品模型，只做参考指标。」
 *
 * 🔴 页面上的五段（顺序就是阅读顺序）：
 *
 * 1. **来源标注 + 动作声明**（放最上面）—— 蓝图加粗句「竞品数据只做参考指标」。
 *    每个价格都带「平台抓取」角标，每条竞品数据都带「参考数据」角标。
 * 2. **未接入的能力**（02 抓取调度器 / 13 分析 / 1 工作台）—— 显式写明
 *    「未接入」与归属模块，**不显示假数据**，**也不给一个必然 404 的按钮**。
 * 3. **监控对象** —— 正在盯哪些 ASIN、抓取频率多少、机会阈值配没配。
 * 4. **变更事件流** —— 四类变化（上下架 / 价格 / 排名 / 评论数）的真分页 + 真筛选。
 * 5. **市场机会 + 历史对比** —— 判定结论与四维历史对比。
 *
 * 🔴 三态齐全且文案不同：加载中 / 出错了（带重试）/ 空态。
 * 空态还分几种：「还没配监控对象」「配了但还没抓到数据」「抓到了但这个筛选下没有」
 * —— 那是几件事，不能共用一句话。
 *
 * 🔴 **页面上没有「抢占」「调价」按钮。** 竞品数据只做参考指标，
 * 动作在平台侧或自有定价流程里完成 —— 给一个必然 404 的按钮比不给更糟。
 */
export function ListingChangeWorkspace() {
  const [caps, setCaps] = useState<ListingChangeCapabilities | null>(null);
  const [watches, setWatches] = useState<ListingChangeWatchPage | null>(null);
  const [events, setEvents] = useState<ListingChangeEventPage | null>(null);
  const [opportunities, setOpportunities] = useState<ListingChangeOpportunityPage | null>(null);
  const [comparison, setComparison] = useState<ListingChangeComparison | null>(null);

  // ---- 筛选
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [changeType, setChangeType] = useState('');
  const [opportunityType, setOpportunityType] = useState('');
  const [eventPage, setEventPage] = useState(0);
  const [opportunityPage, setOpportunityPage] = useState(0);

  // ---- 选中的监控对象
  const [selectedWatch, setSelectedWatch] = useState<string | null>(null);

  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, watchPage] = await Promise.all([
        fetchCapabilities(),
        // 🔴 exactOptionalPropertyTypes：可选属性**不能显式传 undefined**，
        //    所以筛选项是「有值才带上」，不是「带上 undefined」。
        fetchWatches({ page: 0, size: 20 }),
      ]);
      setCaps(capsResult);
      setWatches(watchPage);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const loadDetails = useCallback(
    async (watchId: string | null) => {
      setDetailLoading(true);
      setError(null);
      try {
        const [eventPageResult, opportunityPageResult, comparisonResult] = await Promise.all([
          fetchEvents({
            ...(watchId ? { watchId } : {}),
            ...(changeType ? { changeType } : {}),
            ...(from ? { from } : {}),
            ...(to ? { to } : {}),
            page: eventPage,
            size: 20,
          }),
          fetchOpportunities({
            ...(watchId ? { watchId } : {}),
            ...(opportunityType ? { opportunityType } : {}),
            ...(from ? { from } : {}),
            ...(to ? { to } : {}),
            page: opportunityPage,
            size: 20,
          }),
          watchId
            ? fetchComparison({
                watchId,
                ...(from ? { from } : {}),
                ...(to ? { to } : {}),
              })
            : Promise.resolve(null),
        ]);
        setEvents(eventPageResult);
        setOpportunities(opportunityPageResult);
        setComparison(comparisonResult);
      } catch (e) {
        setError(e instanceof Error ? e.message : '加载失败，请重试');
      } finally {
        setDetailLoading(false);
      }
    },
    [changeType, opportunityType, from, to, eventPage, opportunityPage],
  );

  useEffect(() => {
    void loadDetails(selectedWatch);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedWatch]);

  const missing = useMemo(() => unavailableDimensions(caps), [caps]);

  const acknowledge = useCallback(
    async (opportunityId: string) => {
      setError(null);
      setNotice(null);
      try {
        await acknowledgeOpportunity(opportunityId, '已在页面确认');
        setNotice('已标记为「已阅」。这**只是内部状态** —— 动作仍在平台侧或自有定价流程里完成。');
        await loadDetails(selectedWatch);
      } catch (e) {
        setError(e instanceof Error ? e.message : '操作失败，请重试');
      }
    },
    [selectedWatch, loadDetails],
  );

  return (
    <div className="space-y-4">
      <PageHeader
        title="上新与变更监控"
        description="监控目标 ASIN / 竞品的上下架、价格、排名、评论数变化，识别市场机会（如竞品断货涨价）。"
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

      {notice && (
        <div className="border-info/40 bg-info/5 text-foreground flex items-center gap-2 rounded-lg border px-4 py-3 text-label-13">
          <span className="flex-1">{notice}</span>
        </div>
      )}

      {/* ---- 🔴 来源标注 + 动作声明（蓝图加粗句：竞品数据只做参考指标） ---- */}
      <div className="border-info/40 bg-info/5 text-foreground flex flex-col gap-2 rounded-lg border px-4 py-3 text-label-13">
        <div className="flex items-start gap-3">
          <Badge tone="info" className="mt-0.5 shrink-0">
            {REFERENCE_DATA_BADGE}
          </Badge>
          <span className="flex-1">{sourceBanner(caps)}</span>
        </div>
        <div className="flex items-start gap-3">
          <Badge tone="warning" className="mt-0.5 shrink-0">
            无处置动作
          </Badge>
          <span className="flex-1">{actionsBanner(caps)}</span>
        </div>
      </div>

      {/* ---- 🔴 未接入的能力（02 抓取调度器 / 13 分析 / 1 工作台） ---- */}
      {missing.length > 0 && (
        <SectionCard
          title="未接入的能力"
          description="下面这些能力属于其它模块，尚未交付 —— 这里显式列出归属模块，不显示假数据，也不给一个必然 404 的按钮。"
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
                  → 归属模块：{d.ownerModule} · {d.impact}
                </span>
              </li>
            ))}
          </ul>
        </SectionCard>
      )}

      {/* ---- 🔴 监控对象 ---- */}
      <SectionCard
        title="监控对象"
        description="正在盯哪些 ASIN、抓取频率多少、机会阈值配没配。🔴 监控对象与自有商品分离 —— 这里只有平台 + ASIN，不是我们的商品。点一行选中，下面的事件流 / 机会 / 历史对比会跟着它走。"
        extra={
          watches ? (
            <span className="text-muted-foreground text-label-12">
              {pageSummary(watches)}
            </span>
          ) : undefined
        }
      >
        {loading && !watches ? (
          <div className="text-muted-foreground flex items-center gap-2 px-1 py-8 text-label-13">
            <Loader2 className="size-4 animate-spin" />
            正在加载监控对象…
          </div>
        ) : !watches || watches.items.length === 0 ? (
          <EmptyState
            icon={<Sparkles className="size-8 text-muted-foreground" />}
            title="还没有监控对象"
            description="先建一个监控对象（平台 + ASIN），抓到竞品的上下架 / 价格 / 排名 / 评论数变化时才会落事件、才谈得上识别机会。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-label-13">
              <thead className="text-muted-foreground border-border border-b">
                <tr>
                  <th className="px-4 py-2 font-medium">目标</th>
                  <th className="px-4 py-2 font-medium">平台</th>
                  <th className="px-4 py-2 font-medium">性质</th>
                  <th className="px-4 py-2 font-medium">抓取频率</th>
                  <th className="px-4 py-2 font-medium">最近价格</th>
                  <th className="px-4 py-2 font-medium">最近排名</th>
                  <th className="px-4 py-2 font-medium">最近评论数</th>
                  <th className="px-4 py-2 font-medium">变更事件</th>
                  <th className="px-4 py-2 font-medium">未处理机会</th>
                  <th className="px-4 py-2 font-medium">状态</th>
                </tr>
              </thead>
              <tbody>
                {watches.items.map((row) => (
                  <tr
                    key={row.watchId}
                    className={`border-border cursor-pointer border-b last:border-0 ${
                      selectedWatch === row.watchId ? 'bg-muted' : ''
                    }`}
                    onClick={() => setSelectedWatch(row.watchId)}
                  >
                    <td className="text-foreground px-4 py-2">
                      {row.title || row.externalItemId}
                      <div className="text-muted-foreground text-label-12">
                        {row.externalItemId}
                        {row.brand ? ` · ${row.brand}` : ''}
                      </div>
                    </td>
                    <td className="px-4 py-2">
                      {row.platformCode}
                      {row.marketplaceCode ? ` · ${row.marketplaceCode}` : ''}
                    </td>
                    <td className="px-4 py-2">
                      <Badge tone={row.targetKind === 'COMPETITOR' ? 'warning' : 'neutral'}>
                        {targetKindLabel(row.targetKind)}
                      </Badge>
                    </td>
                    <td className="px-4 py-2">每 {formatCount(row.crawlIntervalMinutes)} 分钟</td>
                    <td className="px-4 py-2">
                      {formatAmount(row.lastPrice, row.currency)}
                      <Badge tone="neutral" className="ml-2">
                        {SCRAPED_SOURCE_BADGE}
                      </Badge>
                    </td>
                    <td className="px-4 py-2">{formatCount(row.lastRankPosition)}</td>
                    <td className="px-4 py-2">{formatCount(row.lastReviewCount)}</td>
                    <td className="px-4 py-2">{formatCount(row.eventCount)}</td>
                    <td className="px-4 py-2">
                      {formatCount(row.openOpportunityCount)}
                      {row.openOpportunityCount > 0 && (
                        <Badge tone="warning" className="ml-2">
                          待看
                        </Badge>
                      )}
                    </td>
                    <td className="px-4 py-2">
                      <Badge tone={row.status === 'ACTIVE' ? 'info' : 'neutral'}>
                        {watchStatusLabel(row.status)}
                      </Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* ---- 筛选条 ---- */}
      <SectionCard title="筛选" description="变更事件流 / 市场机会 / 历史对比共用这一组筛选条件。">
        <div className="flex flex-wrap items-end gap-3">
          <div className="flex flex-col gap-1">
            <label className="text-muted-foreground text-label-12" htmlFor="lc-from">
              起始日期
            </label>
            <Input
              id="lc-from"
              type="date"
              value={from}
              className="h-8 w-40"
              onChange={(e) => {
                setFrom(e.target.value);
                setEventPage(0);
                setOpportunityPage(0);
              }}
            />
          </div>
          <div className="flex flex-col gap-1">
            <label className="text-muted-foreground text-label-12" htmlFor="lc-to">
              结束日期
            </label>
            <Input
              id="lc-to"
              type="date"
              value={to}
              className="h-8 w-40"
              onChange={(e) => {
                setTo(e.target.value);
                setEventPage(0);
                setOpportunityPage(0);
              }}
            />
          </div>
          <div className="flex flex-col gap-1">
            <label className="text-muted-foreground text-label-12" htmlFor="lc-change-type">
              变化维度
            </label>
            <Input
              id="lc-change-type"
              value={changeType}
              placeholder="留空 = 全部"
              className="h-8 w-40"
              onChange={(e) => {
                setChangeType(e.target.value.toUpperCase());
                setEventPage(0);
              }}
            />
          </div>
          <div className="flex flex-col gap-1">
            <label className="text-muted-foreground text-label-12" htmlFor="lc-opportunity-type">
              机会类型
            </label>
            <Input
              id="lc-opportunity-type"
              value={opportunityType}
              placeholder="留空 = 全部"
              className="h-8 w-40"
              onChange={(e) => {
                setOpportunityType(e.target.value.toUpperCase());
                setOpportunityPage(0);
              }}
            />
          </div>
          <Button size="sm" onClick={() => void loadDetails(selectedWatch)}>
            查询
          </Button>
          {selectedWatch && (
            <Button size="sm" variant="secondary" onClick={() => setSelectedWatch(null)}>
              看全部监控对象
            </Button>
          )}
        </div>
      </SectionCard>

      {/* ---- 🔴 变更事件流 ---- */}
      <SectionCard
        title="变更事件流"
        description="上下架 / 价格 / 排名 / 评论数四类变化。🔴 方向只说朝哪变，不含好坏 —— 名次数字变大是「竞品变差」。价格与比例由服务端现算，分母为 0 时是「—」。"
        extra={
          events ? (
            <span className="text-muted-foreground text-label-12">{pageSummary(events)}</span>
          ) : undefined
        }
      >
        {detailLoading && !events ? (
          <div className="text-muted-foreground flex items-center gap-2 px-1 py-8 text-label-13">
            <Loader2 className="size-4 animate-spin" />
            正在加载变更事件…
          </div>
        ) : !events || events.items.length === 0 ? (
          <EmptyState
            icon={<LineChart className="size-8 text-muted-foreground" />}
            title="没有变更事件"
            description={emptyEventsMessage(events?.watchCount ?? 0, events?.total ?? 0)}
          />
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-label-13">
                <thead className="text-muted-foreground border-border border-b">
                  <tr>
                    <th className="px-4 py-2 font-medium">维度</th>
                    <th className="px-4 py-2 font-medium">目标</th>
                    <th className="px-4 py-2 font-medium">方向</th>
                    <th className="px-4 py-2 font-medium">旧值</th>
                    <th className="px-4 py-2 font-medium">新值</th>
                    <th className="px-4 py-2 font-medium">变化</th>
                    <th className="px-4 py-2 font-medium">判定时刻</th>
                    <th className="px-4 py-2 font-medium">判定依据</th>
                  </tr>
                </thead>
                <tbody>
                  {events.items.map((row) => (
                    <tr key={row.eventId} className="border-border border-b last:border-0">
                      <td className="px-4 py-2">
                        <Badge tone="neutral">{changeTypeLabel(row.changeType)}</Badge>
                      </td>
                      <td className="text-foreground px-4 py-2">
                        {row.title || row.externalItemId}
                        <div className="text-muted-foreground text-label-12">
                          {row.externalItemId}
                        </div>
                      </td>
                      <td className="px-4 py-2">{directionLabel(row.changeType, row.direction)}</td>
                      <td className="px-4 py-2">
                        {row.changeType === 'LISTING_STATUS'
                          ? listingStatusLabel(row.previousState)
                          : formatCount(row.previousValue)}
                      </td>
                      <td className="px-4 py-2">
                        {row.changeType === 'LISTING_STATUS'
                          ? listingStatusLabel(row.currentState)
                          : formatCount(row.currentValue)}
                        {row.changeType === 'PRICE' && (
                          <Badge tone="neutral" className="ml-2">
                            {SCRAPED_SOURCE_BADGE}
                          </Badge>
                        )}
                      </td>
                      <td className="px-4 py-2">
                        {row.changeType === 'PRICE' ? formatSignedRate(row.priceChangeRatio) : null}
                        {row.changeType === 'RANK' ? rankChangeText(row.rankChange) : null}
                        {row.changeType === 'REVIEW_COUNT'
                          ? formatCount(row.reviewCountChange)
                          : null}
                        {row.changeType === 'LISTING_STATUS' ? '状态迁移' : null}
                      </td>
                      <td className="px-4 py-2">{formatTime(row.detectedAt)}</td>
                      <td className="text-muted-foreground px-4 py-2">{row.reason}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="mt-3 flex items-center justify-end gap-2">
              <Button
                size="sm"
                variant="secondary"
                disabled={eventPage === 0}
                onClick={() => setEventPage((p) => Math.max(0, p - 1))}
              >
                <ChevronLeft className="mr-1 size-4" />
                上一页
              </Button>
              <Button
                size="sm"
                variant="secondary"
                disabled={(events.page + 1) * events.size >= events.total}
                onClick={() => setEventPage((p) => p + 1)}
              >
                下一页
                <ChevronRight className="ml-1 size-4" />
              </Button>
            </div>
          </>
        )}
      </SectionCard>

      {/* ---- 🔴 市场机会 ---- */}
      <SectionCard
        title="市场机会"
        description="竞品断货 / 涨价 / 排名变差 / 上新。🔴 机会是记录不是通知；「已阅」只是内部状态，**不代表已经采取任何动作**。"
        extra={
          opportunities ? (
            <span className="text-muted-foreground text-label-12">
              {pageSummary(opportunities)} · 未处理 {opportunities.openCount} 条
            </span>
          ) : undefined
        }
      >
        {!opportunities || opportunities.items.length === 0 ? (
          <EmptyState
            icon={<Sparkles className="size-8 text-muted-foreground" />}
            title="没有市场机会"
            description="当前筛选条件下没有机会判定结论。机会要由「变化」触发 —— 没配阈值（涨价幅度 / 排名变差位数）的维度不判定，这跟「没变化」是两回事。"
          />
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-label-13">
                <thead className="text-muted-foreground border-border border-b">
                  <tr>
                    <th className="px-4 py-2 font-medium">类型</th>
                    <th className="px-4 py-2 font-medium">目标</th>
                    <th className="px-4 py-2 font-medium">价格</th>
                    <th className="px-4 py-2 font-medium">排名</th>
                    <th className="px-4 py-2 font-medium">判定依据</th>
                    <th className="px-4 py-2 font-medium">状态</th>
                    <th className="px-4 py-2 font-medium">判定时刻</th>
                    <th className="px-4 py-2 font-medium">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {opportunities.items.map((row) => (
                    <tr key={row.opportunityId} className="border-border border-b last:border-0">
                      <td className="px-4 py-2">
                        <Badge tone={row.severity === 'HIGH' ? 'warning' : 'neutral'}>
                          {opportunityTypeLabel(row.opportunityType)}
                        </Badge>
                        <div className="text-muted-foreground text-label-12">
                          严重度 {severityLabel(row.severity)}
                        </div>
                      </td>
                      <td className="text-foreground px-4 py-2">
                        {row.title || row.externalItemId}
                        <div className="text-muted-foreground text-label-12">
                          {row.externalItemId}
                        </div>
                      </td>
                      <td className="px-4 py-2">
                        {formatAmount(row.observedPrice, row.currency)}
                        <Badge tone="neutral" className="ml-2">
                          {SCRAPED_SOURCE_BADGE}
                        </Badge>
                      </td>
                      <td className="px-4 py-2">{formatCount(row.observedRank)}</td>
                      <td className="text-muted-foreground px-4 py-2">{row.reason}</td>
                      <td className="px-4 py-2">
                        <Badge tone={row.status === 'OPEN' ? 'warning' : 'neutral'}>
                          {row.status === 'OPEN' ? '未处理' : '已阅'}
                        </Badge>
                        <div className="text-muted-foreground text-label-12">
                          {opportunityStatusHint(row.status)}
                        </div>
                      </td>
                      <td className="px-4 py-2">{formatTime(row.detectedAt)}</td>
                      <td className="px-4 py-2">
                        {row.status === 'OPEN' ? (
                          <Button
                            size="sm"
                            variant="secondary"
                            onClick={() => void acknowledge(row.opportunityId)}
                          >
                            <Eye className="mr-1 size-4" />
                            标记已阅
                          </Button>
                        ) : (
                          <span className="text-muted-foreground text-label-12">
                            {formatTime(row.acknowledgedAt)}
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
                disabled={opportunityPage === 0}
                onClick={() => setOpportunityPage((p) => Math.max(0, p - 1))}
              >
                <ChevronLeft className="mr-1 size-4" />
                上一页
              </Button>
              <Button
                size="sm"
                variant="secondary"
                disabled={(opportunities.page + 1) * opportunities.size >= opportunities.total}
                onClick={() => setOpportunityPage((p) => p + 1)}
              >
                下一页
                <ChevronRight className="ml-1 size-4" />
              </Button>
            </div>
          </>
        )}
      </SectionCard>

      {/* ---- 🔴 历史对比 ---- */}
      <SectionCard
        title="历史对比"
        description="价格 / 排名 / 评论数 / 上下架四维对比（服务端 SQL 聚合，比率现算，不落表）。"
        extra={
          comparison ? (
            <span className="text-muted-foreground text-label-12">
              {comparison.from} ~ {comparison.to} · 样本 {formatCount(comparison.sampleCount)} 条 · 事件{' '}
              {formatCount(comparison.eventCount)} 条
            </span>
          ) : undefined
        }
      >
        {!selectedWatch ? (
          <EmptyState
            icon={<LineChart className="size-8 text-muted-foreground" />}
            title="还没选中监控对象"
            description="在上面点一个监控对象，这里会显示这个竞品在所选区间内的四维历史对比。"
          />
        ) : !comparison ? (
          <div className="text-muted-foreground flex items-center gap-2 px-1 py-8 text-label-13">
            <Loader2 className="size-4 animate-spin" />
            正在加载历史对比…
          </div>
        ) : !comparison.hasData ? (
          <EmptyState
            icon={<LineChart className="size-8 text-muted-foreground" />}
            title="这个区间没有观测数据"
            description={emptyComparisonMessage(comparison)}
          />
        ) : (
          <>
            <div className="mb-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <StatCard
                label="价格变化"
                value={formatSignedRate(comparison.price.changeRatio)}
                comparisonLabel={`${formatAmount(comparison.price.firstPrice, comparison.price.currency)} → ${formatAmount(comparison.price.lastPrice, comparison.price.currency)}（均价 ${formatAmount(comparison.price.avgPrice, comparison.price.currency)}）`}
              />
              <StatCard
                label="排名变化"
                value={rankChangeText(comparison.rank.changeAmount)}
                comparisonLabel={`${rankBasisLabel(comparison.rank.rankBasis)}：最好 ${formatCount(comparison.rank.bestRank)} / 最差 ${formatCount(comparison.rank.worstRank)}`}
              />
              <StatCard
                label="评论数变化"
                value={formatCount(comparison.reviewCount.changeAmount)}
                comparisonLabel={`${formatCount(comparison.reviewCount.firstCount)} → ${formatCount(comparison.reviewCount.lastCount)}`}
              />
              <StatCard
                label="上下架"
                value={listingStatusLabel(comparison.listingStatus.currentStatus)}
                comparisonLabel={`在售 ${formatCount(comparison.listingStatus.listedCount)} · 断货 ${formatCount(comparison.listingStatus.outOfStockCount)} · 下架 ${formatCount(comparison.listingStatus.delistedCount)} · 迁移 ${formatCount(comparison.listingStatus.transitionCount)} 次`}
              />
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-label-13">
                <thead className="text-muted-foreground border-border border-b">
                  <tr>
                    <th className="px-4 py-2 font-medium">维度</th>
                    <th className="px-4 py-2 font-medium">区间首个</th>
                    <th className="px-4 py-2 font-medium">区间末个</th>
                    <th className="px-4 py-2 font-medium">最低 / 最高</th>
                    <th className="px-4 py-2 font-medium">变化</th>
                    <th className="px-4 py-2 font-medium">样本数</th>
                  </tr>
                </thead>
                <tbody>
                  <tr className="border-border border-b last:border-0">
                    <td className="text-foreground px-4 py-2">价格</td>
                    <td className="px-4 py-2">
                      {formatAmount(comparison.price.firstPrice, comparison.price.currency)}
                    </td>
                    <td className="px-4 py-2">
                      {formatAmount(comparison.price.lastPrice, comparison.price.currency)}
                      <Badge tone="neutral" className="ml-2">
                        {SCRAPED_SOURCE_BADGE}
                      </Badge>
                    </td>
                    <td className="px-4 py-2">
                      {formatAmount(comparison.price.minPrice, comparison.price.currency)} /{' '}
                      {formatAmount(comparison.price.maxPrice, comparison.price.currency)}
                    </td>
                    <td className="px-4 py-2">{formatSignedRate(comparison.price.changeRatio)}</td>
                    <td className="px-4 py-2">{formatCount(comparison.price.sampleCount)}</td>
                  </tr>
                  <tr className="border-border border-b last:border-0">
                    <td className="text-foreground px-4 py-2">
                      排名（{rankBasisLabel(comparison.rank.rankBasis)}）
                    </td>
                    <td className="px-4 py-2">{formatCount(comparison.rank.firstRank)}</td>
                    <td className="px-4 py-2">{formatCount(comparison.rank.lastRank)}</td>
                    <td className="px-4 py-2">
                      {formatCount(comparison.rank.bestRank)} / {formatCount(comparison.rank.worstRank)}
                    </td>
                    <td className="px-4 py-2">{rankChangeText(comparison.rank.changeAmount)}</td>
                    <td className="px-4 py-2">{formatCount(comparison.rank.sampleCount)}</td>
                  </tr>
                  <tr className="border-border border-b last:border-0">
                    <td className="text-foreground px-4 py-2">评论数</td>
                    <td className="px-4 py-2">{formatCount(comparison.reviewCount.firstCount)}</td>
                    <td className="px-4 py-2">{formatCount(comparison.reviewCount.lastCount)}</td>
                    <td className="px-4 py-2">—</td>
                    <td className="px-4 py-2">{formatCount(comparison.reviewCount.changeAmount)}</td>
                    <td className="px-4 py-2">{formatCount(comparison.reviewCount.sampleCount)}</td>
                  </tr>
                  <tr className="border-border border-b last:border-0">
                    <td className="text-foreground px-4 py-2">上下架</td>
                    <td className="px-4 py-2">{listingStatusLabel(comparison.listingStatus.currentStatus)}</td>
                    <td className="px-4 py-2">{listingStatusLabel(comparison.listingStatus.currentStatus)}</td>
                    <td className="px-4 py-2">—</td>
                    <td className="px-4 py-2">
                      {formatCount(comparison.listingStatus.transitionCount)} 次迁移
                    </td>
                    <td className="px-4 py-2">{formatCount(comparison.listingStatus.sampleCount)}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p className="text-muted-foreground mt-3 text-label-12">
              🔴 比率由服务端现算，<strong>分母（区间首个价格）为 0 时是「—」</strong> ——
              「没有基准」与「价格没变」是两件事，页面不替它下结论。
              价格变化比例只在上次价格为非 0 时才有意义。
              {formatRate(comparison.price.changeRatio) === '—' ? '（本次区间显示为「—」）' : ''}
            </p>
          </>
        )}
      </SectionCard>
    </div>
  );
}
