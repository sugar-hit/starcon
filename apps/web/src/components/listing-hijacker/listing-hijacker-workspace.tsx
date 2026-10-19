'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import {
  ChevronLeft,
  ChevronRight,
  Eye,
  Loader2,
  RefreshCw,
  ShieldAlert,
  TriangleAlert,
} from 'lucide-react';
import {
  acknowledgeAlert,
  actionsBanner,
  alertStatusHint,
  alertTypeLabel,
  emptyComparisonMessage,
  emptyRecordsMessage,
  fetchAlerts,
  fetchCapabilities,
  fetchComparison,
  fetchRecords,
  fetchWatches,
  formatAmount,
  formatCount,
  formatRate,
  formatTime,
  gapText,
  hijackerKindLabel,
  pageSummary,
  REFERENCE_ONLY_BADGE,
  SCRAPED_SOURCE_BADGE,
  sourceBanner,
  unavailableDimensions,
  watchStatusLabel,
  type ListingHijackerAlertPage,
  type ListingHijackerCapabilities,
  type ListingHijackerComparisonPage,
  type ListingHijackerRecordPage,
  type ListingHijackerWatchPage,
} from './listing-hijacker-model';

/**
 * 4.5.2.1 跟卖监控 工作区。
 *
 * 蓝图：「监控他人/己方其他店铺对本 ASIN 的跟卖行为，记录跟卖者、价格、店铺，
 * 发现异常跟卖或恶意压价时告警。输出：跟卖记录 + 预警通知 + 对比视图。
 * **跟卖数据只能作为参考**，处置动作（投诉/举报）在平台侧完成，系统只负责发现与提醒。」
 *
 * 🔴 页面上的五段（顺序就是阅读顺序）：
 *
 * 1. **来源标注 + 处置动作声明**（放最上面）—— 蓝图加粗句「跟卖数据只能作为参考」。
 *    每个价格都带「平台抓取」角标，每条预警带「仅供参考」角标。
 * 2. **未接入的能力**（16 合规 / 1 工作台 / 02 调度器 / 05 定价）—— 显式写明
 *    「未接入」与归属模块，**不显示假数据**，**也不给一个必然 404 的按钮**。
 * 3. **监控对象** —— 正在盯哪些 ASIN、阈值配没配、有没有未处理预警。
 * 4. **跟卖记录** —— 谁在跟卖、多少钱、什么时候发现的（真分页 + 筛选）。
 * 5. **预警记录 + 对比视图** —— 判定结论与价格对比。
 *
 * 🔴 三态齐全且文案不同：加载中 / 出错了（带重试）/ 空态。
 * 空态还分几种：「还没配监控对象」「配了但还没抓到跟卖」「抓到了但这个筛选下没有」
 * —— 那是几件事，不能共用一句话。
 *
 * 🔴 **页面上没有「投诉」「举报」按钮。** 处置在平台侧完成，
 * 本模块只负责发现与提醒 —— 给一个必然 404 的按钮比不给更糟。
 */
export function ListingHijackerWorkspace() {
  const [caps, setCaps] = useState<ListingHijackerCapabilities | null>(null);
  const [watches, setWatches] = useState<ListingHijackerWatchPage | null>(null);
  const [records, setRecords] = useState<ListingHijackerRecordPage | null>(null);
  const [alerts, setAlerts] = useState<ListingHijackerAlertPage | null>(null);
  const [comparison, setComparison] = useState<ListingHijackerComparisonPage | null>(null);

  // ---- 筛选
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [hijackerKind, setHijackerKind] = useState('');
  const [alertType, setAlertType] = useState('');
  const [alertStatus, setAlertStatus] = useState('');
  const [recordPage, setRecordPage] = useState(0);
  const [alertPage, setAlertPage] = useState(0);

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
        const [recordPageResult, alertPageResult, comparisonResult] = await Promise.all([
          fetchRecords({
            ...(watchId ? { watchId } : {}),
            ...(hijackerKind ? { hijackerKind } : {}),
            ...(from ? { from } : {}),
            ...(to ? { to } : {}),
            page: recordPage,
            size: 20,
          }),
          fetchAlerts({
            ...(watchId ? { watchId } : {}),
            ...(alertType ? { alertType } : {}),
            ...(alertStatus ? { status: alertStatus } : {}),
            ...(from ? { from } : {}),
            ...(to ? { to } : {}),
            page: alertPage,
            size: 20,
          }),
          watchId
            ? fetchComparison({
                watchId,
                ...(from ? { from } : {}),
                ...(to ? { to } : {}),
                page: 0,
                size: 20,
              })
            : Promise.resolve(null),
        ]);
        setRecords(recordPageResult);
        setAlerts(alertPageResult);
        setComparison(comparisonResult);
      } catch (e) {
        setError(e instanceof Error ? e.message : '加载失败，请重试');
      } finally {
        setDetailLoading(false);
      }
    },
    [hijackerKind, alertType, alertStatus, from, to, recordPage, alertPage],
  );

  useEffect(() => {
    void loadDetails(selectedWatch);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedWatch]);

  const missing = useMemo(() => unavailableDimensions(caps), [caps]);

  const acknowledge = useCallback(
    async (alertId: string) => {
      setError(null);
      setNotice(null);
      try {
        await acknowledgeAlert(alertId, '已在页面确认');
        setNotice('已标记为「已阅」。这**只是内部状态** —— 投诉 / 举报仍需在平台侧完成。');
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
        title="跟卖监控"
        description="监控他人 / 己方其他店铺对本 ASIN 的跟卖行为，记录跟卖者、价格、店铺，发现异常跟卖或恶意压价时告警。"
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

      {/* ---- 🔴 来源标注 + 处置动作声明（蓝图加粗句：跟卖数据只能作为参考） ---- */}
      <div className="border-info/40 bg-info/5 text-foreground flex flex-col gap-2 rounded-lg border px-4 py-3 text-label-13">
        <div className="flex items-start gap-3">
          <Badge tone="info" className="mt-0.5 shrink-0">
            {REFERENCE_ONLY_BADGE}
          </Badge>
          <span className="flex-1">{sourceBanner(caps)}</span>
        </div>
        <div className="flex items-start gap-3">
          <Badge tone="warning" className="mt-0.5 shrink-0">
            平台侧处置
          </Badge>
          <span className="flex-1">{actionsBanner(caps)}</span>
        </div>
      </div>

      {/* ---- 🔴 未接入的能力（16 / 1 / 02 / 05） ---- */}
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
                  归属模块：{d.ownerModule} · {d.impact}
                </span>
              </li>
            ))}
          </ul>
        </SectionCard>
      )}

      {/* ---- 🔴 监控对象 ---- */}
      <SectionCard
        title="监控对象"
        description="正在盯哪些 ASIN、各自的阈值配没配、有没有未处理的预警。点一行选中，下面的记录 / 预警 / 对比视图会跟着它走。"
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
            icon={<ShieldAlert className="size-8 text-muted-foreground" />}
            title="还没有监控对象"
            description="先建一个监控对象（按 ASIN / listing 配），抓到跟卖时才会落记录、才会判预警。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-label-13">
              <thead className="text-muted-foreground border-border border-b">
                <tr>
                  <th className="px-4 py-2 font-medium">商品</th>
                  <th className="px-4 py-2 font-medium">平台</th>
                  <th className="px-4 py-2 font-medium">自家参考售价</th>
                  <th className="px-4 py-2 font-medium">恶意压价阈值</th>
                  <th className="px-4 py-2 font-medium">异常跟卖阈值</th>
                  <th className="px-4 py-2 font-medium">跟卖记录</th>
                  <th className="px-4 py-2 font-medium">未处理预警</th>
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
                        {row.shopCode} · {row.externalItemId}
                      </div>
                    </td>
                    <td className="px-4 py-2">{row.platformCode}</td>
                    <td className="px-4 py-2">
                      {formatAmount(row.referencePrice, row.currency)}
                      <Badge tone="neutral" className="ml-2">
                        人工维护
                      </Badge>
                    </td>
                    <td className="px-4 py-2">{formatAmount(row.priceCutThreshold, row.currency)}</td>
                    <td className="px-4 py-2">{formatRate(row.priceGapRatioThreshold)}</td>
                    <td className="px-4 py-2">{formatCount(row.recordCount)}</td>
                    <td className="px-4 py-2">
                      {formatCount(row.openAlertCount)}
                      {row.openAlertCount > 0 && (
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
      <SectionCard title="筛选" description="跟卖记录 / 预警 / 对比视图共用这一组筛选条件。">
        <div className="flex flex-wrap items-end gap-3">
          <div className="flex flex-col gap-1">
            <label className="text-muted-foreground text-label-12" htmlFor="hj-from">
              起始日期
            </label>
            <Input
              id="hj-from"
              type="date"
              value={from}
              className="h-8 w-40"
              onChange={(e) => {
                setFrom(e.target.value);
                setRecordPage(0);
                setAlertPage(0);
              }}
            />
          </div>
          <div className="flex flex-col gap-1">
            <label className="text-muted-foreground text-label-12" htmlFor="hj-to">
              结束日期
            </label>
            <Input
              id="hj-to"
              type="date"
              value={to}
              className="h-8 w-40"
              onChange={(e) => {
                setTo(e.target.value);
                setRecordPage(0);
                setAlertPage(0);
              }}
            />
          </div>
          <div className="flex flex-col gap-1">
            <label className="text-muted-foreground text-label-12" htmlFor="hj-kind">
              跟卖者性质
            </label>
            <Input
              id="hj-kind"
              value={hijackerKind}
              placeholder="留空 = 全部"
              className="h-8 w-40"
              onChange={(e) => {
                setHijackerKind(e.target.value.toUpperCase());
                setRecordPage(0);
              }}
            />
          </div>
          <div className="flex flex-col gap-1">
            <label className="text-muted-foreground text-label-12" htmlFor="hj-alert-type">
              预警类型
            </label>
            <Input
              id="hj-alert-type"
              value={alertType}
              placeholder="留空 = 全部"
              className="h-8 w-40"
              onChange={(e) => {
                setAlertType(e.target.value.toUpperCase());
                setAlertPage(0);
              }}
            />
          </div>
          <div className="flex flex-col gap-1">
            <label className="text-muted-foreground text-label-12" htmlFor="hj-alert-status">
              预警状态
            </label>
            <Input
              id="hj-alert-status"
              value={alertStatus}
              placeholder="留空 = 全部"
              className="h-8 w-40"
              onChange={(e) => {
                setAlertStatus(e.target.value.toUpperCase());
                setAlertPage(0);
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

      {/* ---- 🔴 跟卖记录 ---- */}
      <SectionCard
        title="跟卖记录"
        description="谁在跟卖、多少钱、什么时候发现的。价格都是平台侧观测值，不是内部定价。"
        extra={
          records ? (
            <span className="text-muted-foreground text-label-12">
              {pageSummary(records)} · 预警 {records.alertCount} 条
            </span>
          ) : undefined
        }
      >
        {detailLoading && !records ? (
          <div className="text-muted-foreground flex items-center gap-2 px-1 py-8 text-label-13">
            <Loader2 className="size-4 animate-spin" />
            正在加载跟卖记录…
          </div>
        ) : !records || records.items.length === 0 ? (
          <EmptyState
            icon={<ShieldAlert className="size-8 text-muted-foreground" />}
            title="没有跟卖记录"
            description={emptyRecordsMessage(records?.watchCount ?? 0, records?.total ?? 0)}
          />
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-label-13">
                <thead className="text-muted-foreground border-border border-b">
                  <tr>
                    <th className="px-4 py-2 font-medium">跟卖者</th>
                    <th className="px-4 py-2 font-medium">店铺</th>
                    <th className="px-4 py-2 font-medium">性质</th>
                    <th className="px-4 py-2 font-medium">跟卖价</th>
                    <th className="px-4 py-2 font-medium">自家参考价</th>
                    <th className="px-4 py-2 font-medium">价格差</th>
                    <th className="px-4 py-2 font-medium">发现时间</th>
                    <th className="px-4 py-2 font-medium">来源</th>
                  </tr>
                </thead>
                <tbody>
                  {records.items.map((row) => (
                    <tr key={row.recordId} className="border-border border-b last:border-0">
                      <td className="text-foreground px-4 py-2">{row.hijackerSellerId}</td>
                      <td className="px-4 py-2">
                        {row.hijackerShopName || '—'}
                        <div className="text-muted-foreground text-label-12">
                          {row.hijackerShopIdentifier}
                        </div>
                      </td>
                      <td className="px-4 py-2">
                        <Badge tone={row.hijackerKind === 'OWN_SHOP' ? 'warning' : 'neutral'}>
                          {hijackerKindLabel(row.hijackerKind)}
                        </Badge>
                      </td>
                      <td className="px-4 py-2">
                        {formatAmount(row.hijackerPrice, row.currency)}
                        <Badge tone="neutral" className="ml-2">
                          {SCRAPED_SOURCE_BADGE}
                        </Badge>
                      </td>
                      <td className="px-4 py-2">{formatAmount(row.referencePrice, row.currency)}</td>
                      <td className="px-4 py-2">{gapText(row.priceGapRatio)}</td>
                      <td className="px-4 py-2">{formatTime(row.observedAt)}</td>
                      <td className="px-4 py-2">
                        <Badge tone="neutral">{row.source}</Badge>
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
                disabled={recordPage === 0}
                onClick={() => setRecordPage((p) => Math.max(0, p - 1))}
              >
                <ChevronLeft className="mr-1 size-4" />
                上一页
              </Button>
              <Button
                size="sm"
                variant="secondary"
                disabled={(records.page + 1) * records.size >= records.total}
                onClick={() => setRecordPage((p) => p + 1)}
              >
                下一页
                <ChevronRight className="ml-1 size-4" />
              </Button>
            </div>
          </>
        )}
      </SectionCard>

      {/* ---- 🔴 预警记录 ---- */}
      <SectionCard
        title="预警记录"
        description="异常跟卖 / 恶意压价的判定结论。🔴 预警是记录不是通知；「已阅」只是内部状态，处置动作仍在平台侧。"
        extra={
          alerts ? (
            <span className="text-muted-foreground text-label-12">
              {pageSummary(alerts)} · 未处理 {alerts.openCount} 条
            </span>
          ) : undefined
        }
      >
        {!alerts || alerts.items.length === 0 ? (
          <EmptyState
            icon={<ShieldAlert className="size-8 text-muted-foreground" />}
            title="没有预警"
            description="当前筛选条件下没有异常跟卖 / 恶意压价的判定结论。抓到的跟卖价不越过阈值时不会产生预警 —— 没有预警不等于没有跟卖，请看上面的跟卖记录。"
          />
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-label-13">
                <thead className="text-muted-foreground border-border border-b">
                  <tr>
                    <th className="px-4 py-2 font-medium">类型</th>
                    <th className="px-4 py-2 font-medium">跟卖者</th>
                    <th className="px-4 py-2 font-medium">触发价</th>
                    <th className="px-4 py-2 font-medium">价格差</th>
                    <th className="px-4 py-2 font-medium">判定依据</th>
                    <th className="px-4 py-2 font-medium">状态</th>
                    <th className="px-4 py-2 font-medium">发现时间</th>
                    <th className="px-4 py-2 font-medium">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {alerts.items.map((row) => (
                    <tr key={row.alertId} className="border-border border-b last:border-0">
                      <td className="px-4 py-2">
                        <Badge tone={row.severity === 'HIGH' ? 'warning' : 'neutral'}>
                          {alertTypeLabel(row.alertType)}
                        </Badge>
                        <div className="text-muted-foreground text-label-12">
                          {row.severity} · {hijackerKindLabel(row.hijackerKind)}
                        </div>
                      </td>
                      <td className="text-foreground px-4 py-2">
                        {row.hijackerSellerId}
                        <div className="text-muted-foreground text-label-12">
                          {row.hijackerShopName}
                        </div>
                      </td>
                      <td className="px-4 py-2">
                        {formatAmount(row.observedPrice, row.currency)}
                        <Badge tone="neutral" className="ml-2">
                          {SCRAPED_SOURCE_BADGE}
                        </Badge>
                      </td>
                      <td className="px-4 py-2">{gapText(row.priceGapRatio)}</td>
                      <td className="text-muted-foreground px-4 py-2">{row.reason}</td>
                      <td className="px-4 py-2">
                        <Badge tone={row.status === 'OPEN' ? 'warning' : 'neutral'}>
                          {row.status === 'OPEN' ? '未处理' : '已阅'}
                        </Badge>
                        <div className="text-muted-foreground text-label-12">
                          {alertStatusHint(row.status)}
                        </div>
                      </td>
                      <td className="px-4 py-2">{formatTime(row.observedAt)}</td>
                      <td className="px-4 py-2">
                        {row.status === 'OPEN' ? (
                          <Button
                            size="sm"
                            variant="secondary"
                            onClick={() => void acknowledge(row.alertId)}
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
                disabled={alertPage === 0}
                onClick={() => setAlertPage((p) => Math.max(0, p - 1))}
              >
                <ChevronLeft className="mr-1 size-4" />
                上一页
              </Button>
              <Button
                size="sm"
                variant="secondary"
                disabled={(alerts.page + 1) * alerts.size >= alerts.total}
                onClick={() => setAlertPage((p) => p + 1)}
              >
                下一页
                <ChevronRight className="ml-1 size-4" />
              </Button>
            </div>
          </>
        )}
      </SectionCard>

      {/* ---- 🔴 对比视图 ---- */}
      <SectionCard
        title="价格对比视图"
        description="自家参考售价 × 每个跟卖者（服务端聚合，一个跟卖者一行，压得最狠的排最前）。"
        extra={
          comparison ? (
            <span className="text-muted-foreground text-label-12">
              {comparison.from} ~ {comparison.to} · 跟卖者 {comparison.hijackerCount} 个 · 观察{' '}
              {comparison.observationCount} 次
            </span>
          ) : undefined
        }
      >
        {!selectedWatch ? (
          <EmptyState
            icon={<ShieldAlert className="size-8 text-muted-foreground" />}
            title="还没选中监控对象"
            description="在上面点一个监控对象，这里会显示自家参考售价与各跟卖者的价格对比。"
          />
        ) : !comparison ? (
          <div className="text-muted-foreground flex items-center gap-2 px-1 py-8 text-label-13">
            <Loader2 className="size-4 animate-spin" />
            正在加载对比视图…
          </div>
        ) : !comparison.hasData ? (
          <EmptyState
            icon={<ShieldAlert className="size-8 text-muted-foreground" />}
            title="这个区间没有跟卖数据"
            description={emptyComparisonMessage(comparison)}
          />
        ) : (
          <>
            <div className="mb-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <StatCard
                label="自家参考售价"
                value={formatAmount(comparison.referencePrice, comparison.currency)}
                comparisonLabel="人工维护的参考值（05 定价未交付，系统内没有平台权威售价）"
              />
              <StatCard
                label="跟卖者数量"
                value={formatCount(comparison.hijackerCount)}
                comparisonLabel="区间内被观察到的不同跟卖者"
              />
              <StatCard
                label="最低跟卖价"
                value={formatAmount(comparison.lowestHijackerPrice, comparison.currency)}
                comparisonLabel="区间内观察到的最低跟卖价"
              />
              <StatCard
                label="压得最狠的价格差"
                value={gapText(comparison.maxPriceGapRatio)}
                comparisonLabel="区间内最大的价格差比例（跟卖最狠的那一个）"
              />
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-label-13">
                <thead className="text-muted-foreground border-border border-b">
                  <tr>
                    <th className="px-4 py-2 font-medium">跟卖者</th>
                    <th className="px-4 py-2 font-medium">性质</th>
                    <th className="px-4 py-2 font-medium">最近价</th>
                    <th className="px-4 py-2 font-medium">最低 / 最高</th>
                    <th className="px-4 py-2 font-medium">均价</th>
                    <th className="px-4 py-2 font-medium">自家参考价</th>
                    <th className="px-4 py-2 font-medium">价格差</th>
                    <th className="px-4 py-2 font-medium">观察次数</th>
                    <th className="px-4 py-2 font-medium">最近发现</th>
                  </tr>
                </thead>
                <tbody>
                  {comparison.items.map((row) => (
                    <tr key={row.hijackerSellerId} className="border-border border-b last:border-0">
                      <td className="text-foreground px-4 py-2">
                        {row.hijackerSellerId}
                        <div className="text-muted-foreground text-label-12">
                          {row.hijackerShopName || row.hijackerShopIdentifier}
                        </div>
                      </td>
                      <td className="px-4 py-2">
                        <Badge tone={row.hijackerKind === 'OWN_SHOP' ? 'warning' : 'neutral'}>
                          {hijackerKindLabel(row.hijackerKind)}
                        </Badge>
                      </td>
                      <td className="px-4 py-2">
                        {formatAmount(row.latestPrice, row.currency)}
                        <Badge tone="neutral" className="ml-2">
                          {SCRAPED_SOURCE_BADGE}
                        </Badge>
                      </td>
                      <td className="px-4 py-2">
                        {formatAmount(row.minPrice, row.currency)} /{' '}
                        {formatAmount(row.maxPrice, row.currency)}
                      </td>
                      <td className="px-4 py-2">{formatAmount(row.avgPrice, row.currency)}</td>
                      <td className="px-4 py-2">{formatAmount(row.referencePrice, row.currency)}</td>
                      <td className="px-4 py-2">{gapText(row.priceGapRatio)}</td>
                      <td className="px-4 py-2">{formatCount(row.observationCount)}</td>
                      <td className="px-4 py-2">{formatTime(row.lastObservedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </SectionCard>
    </div>
  );
}
