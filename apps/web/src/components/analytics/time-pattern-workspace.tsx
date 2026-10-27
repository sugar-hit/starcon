'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, Segmented } from '@starcon/ui';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { api } from '@/lib/api';
import {
  ALIGNMENT_OPTIONS,
  RANKING_METRIC_OPTIONS,
  WEEKDAY_ORDER,
  aggregationLabel,
  alignmentDifferenceNote,
  alignmentLabel,
  alignmentNote,
  amountText,
  amountsText,
  boundaryNote,
  countText,
  coverageText,
  coverageTone,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  emptyReasonLabel,
  emptyText,
  heatCellClass,
  heatRow,
  heatTier,
  hourBucketOf,
  levelLabel,
  levelTone,
  metricFormulaText,
  metricUnitLabel,
  notDeliveredLines,
  p90Text,
  pageState,
  peakRankValueText,
  percentText,
  qualifiedLabel,
  qualifiedTone,
  quantityText,
  rangeText,
  rankingMetricNote,
  refreshStatusLabel,
  refreshStatusTone,
  ruleNotes,
  shopFilterText,
  shopTimezoneText,
  slotRangeText,
  statusLabel,
  timezoneStatusLabel,
  timezoneTone,
  unattributedText,
  upstreamModules,
  warnings,
  weekdayBucketOf,
  weekdayLabel,
} from './time-pattern-model';
import type {
  TimePatternCapabilities,
  TimePatternMetricDefinitionView,
  TimePatternRefreshResult,
  TimePatternResponse,
  TimePatternSlotConfigView,
} from './time-pattern-model';

/**
 * 13.2.1.2 分时与分周模式工作区（卷 13「利润与经营分析」L3 13.2 销量分析下的第二格）。
 *
 * <p>蓝图输出：「时段分布热力图 + 高峰时段排序。」
 *
 * <p><b>数据来自 reporting 服务（13 卷的唯一归属服务）的投影读模型</b>：
 *
 * <ul>
 *   <li>{@code GET reporting/time-pattern} —— 热力图 + 小时 / 星期分布 + 时段判定 + 高峰排序；</li>
 *   <li>{@code GET reporting/time-pattern/capabilities} —— 能力声明；</li>
 *   <li>{@code GET reporting/time-pattern/metrics} —— 指标口径目录（口径唯一的展示形态）；</li>
 *   <li>{@code GET reporting/time-pattern/shops} —— 店铺时区档案（按哪个时区统计）；</li>
 *   <li>{@code GET reporting/time-pattern/slots} —— 时段配置（租户自己的数据）；</li>
 *   <li>{@code POST reporting/time-pattern/refresh} —— 刷新投影。</li>
 * </ul>
 *
 * <p><b>🔴 页面必须做到的五件事</b>：
 *
 * <ol>
 *   <li><b>🔴 时区是命门</b>：「按小时」= <b>店铺本地时区</b>的小时，「按星期」=
 *       <b>店铺本地时区</b>的星期几。时区用 IANA 标识，<b>不用固定 UTC 偏移</b>。
 *       店铺时区档案<b>常驻显示</b> —— 用户必须看得见「这家店按哪个时区统计」。</li>
 *   <li><b>🔴 跨店铺汇总的口径必须常驻显示</b>：「各店本地 17:00」与「UTC 17:00」
 *       <b>不是同一批订单</b>。不显示口径的汇总就是让人误判。</li>
 *   <li><b>🔴 高峰低谷的判定依据要写出来</b>：租户标的层级 + 阈值 + 核对结果与理由。</li>
 *   <li><b>多币种绝不相加</b>：成交金额按币种分别列出；订单数与销量件数给跨币种合计。</li>
 *   <li><b>数据更新时间常驻</b>（卷 13 卷首加粗句「数据延迟要显示」）：加载中 / 出错时也显示。</li>
 * </ol>
 *
 * <p><b>🔴 前端不做业务算术</b>：时段归属、高峰排序、占比、P90 刻度全部来自服务端，
 * 这里只做展示格式化 —— 连热力图的深浅都用**服务端给的 P90 阈值**分档，
 * 前端不自己算「最大值」来重新定义什么叫高峰。
 */
export function AnalyticsTimePatternWorkspace() {
  const today = new Date().toISOString().slice(0, 10);
  const initialRange = defaultRange(today);

  const [from, setFrom] = useState(initialRange.from);
  const [to, setTo] = useState(initialRange.to);
  const [alignment, setAlignment] = useState('LOCAL_TIME');
  const [rankingMetric, setRankingMetric] = useState('ORDER_COUNT');
  const [shopCodes, setShopCodes] = useState<string[]>([]);

  const [data, setData] = useState<TimePatternResponse | null>(null);
  const [capabilities, setCapabilities] = useState<TimePatternCapabilities | null>(null);
  const [definitions, setDefinitions] = useState<TimePatternMetricDefinitionView[]>([]);
  const [slotConfigs, setSlotConfigs] = useState<TimePatternSlotConfigView[]>([]);
  const [refreshResults, setRefreshResults] = useState<TimePatternRefreshResult[] | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const query = useMemo(() => {
    const params = new URLSearchParams({
      from,
      to,
      alignment,
      rankingMetric,
    });
    if (shopCodes.length > 0) params.set('shopCodes', shopCodes.join(','));
    return params.toString();
  }, [from, to, alignment, rankingMetric, shopCodes]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [report, caps, metrics, slots] = await Promise.all([
        api<TimePatternResponse>(`reporting/time-pattern?${query}`),
        api<TimePatternCapabilities>('reporting/time-pattern/capabilities'),
        api<TimePatternMetricDefinitionView[]>('reporting/time-pattern/metrics'),
        api<TimePatternSlotConfigView[]>('reporting/time-pattern/slots'),
      ]);
      setData(report);
      setCapabilities(caps);
      setDefinitions(metrics);
      setSlotConfigs(slots);
    } catch (e) {
      setData(null);
      setError(e instanceof Error ? e.message : '读取分时与分周模式报表失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [query]);

  const refreshProjection = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const params = new URLSearchParams({ from, to });
      const results = await api<TimePatternRefreshResult[]>(
        `reporting/time-pattern/refresh?${params}`,
        'POST',
      );
      setRefreshResults(results);
      // 刷完立刻重读 —— 投影变了，页面上的数必须跟着变。
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '刷新分时投影失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [from, to, load]);

  useEffect(() => {
    void load();
  }, [load]);

  const state = pageState(data, error);
  const shops = data?.shops ?? [];

  const toggleShop = useCallback((code: string) => {
    setShopCodes((current) =>
      current.includes(code) ? current.filter((one) => one !== code) : [...current, code],
    );
  }, []);

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

      {/* ── 筛选维度 ───────────────────────────────────────────────────── */}
      <SectionCard
        title="时间范围 · 店铺 · 汇总口径"
        description="🔴 跨店铺汇总时，「小时 17」有两种含义 —— 各店各自的本地 17:00，或者所有店铺共同的 UTC 17:00。两者不是同一批订单，结果不同。当前用的是哪一个，必须看得见。"
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
          <div className="flex flex-col gap-1">
            <span className="text-copy-13 text-muted-foreground">跨店铺汇总口径</span>
            <Segmented
              value={alignment}
              onChange={setAlignment}
              options={ALIGNMENT_OPTIONS.map((option) => ({
                value: option.value,
                label: option.label,
              }))}
              aria-label="跨店铺汇总口径"
            />
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-copy-13 text-muted-foreground">高峰排序依据</span>
            <Segmented
              value={rankingMetric}
              onChange={setRankingMetric}
              options={RANKING_METRIC_OPTIONS.map((option) => ({
                value: option.value,
                label: option.label,
              }))}
              aria-label="高峰时段排序依据"
            />
          </div>
          <Button
            variant="outline"
            size="sm"
            disabled={busy}
            onClick={() => void refreshProjection()}
          >
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            刷新分时投影
          </Button>
        </div>

        {/* 店铺筛选 */}
        <div className="mt-4">
          <span className="text-copy-13 text-muted-foreground">店铺筛选</span>
          <p className="text-copy-13 text-foreground mt-1">
            当前：{shopFilterText(shopCodes, shops)}
          </p>
          {shops.length > 0 ? (
            <div className="mt-2 flex flex-wrap gap-2">
              {shops.map((shop) => {
                const active = shopCodes.includes(shop.shopCode);
                return (
                  <button
                    key={shop.shopCode}
                    type="button"
                    onClick={() => toggleShop(shop.shopCode)}
                    aria-pressed={active}
                    className={
                      active
                        ? 'border-brand bg-brand-soft text-brand text-label-12 rounded-full border px-3 py-1'
                        : 'border-border bg-background text-muted-foreground hover:text-foreground text-label-12 rounded-full border px-3 py-1'
                    }
                  >
                    {shop.shopName || shop.shopCode} · {shopTimezoneText(shop)}
                  </button>
                );
              })}
              {shopCodes.length > 0 && (
                <Button variant="outline" size="sm" onClick={() => setShopCodes([])}>
                  清除筛选
                </Button>
              )}
            </div>
          ) : (
            <p className="text-copy-13 text-muted-foreground mt-1">
              还没有店铺时区档案（先刷新投影）。
            </p>
          )}
        </div>

        {/* 口径说明（常驻） */}
        <div className="border-border bg-muted/40 mt-4 rounded-lg border px-4 py-3">
          <div className="flex flex-wrap items-center gap-2">
            <Badge tone="primary">{alignmentLabel(data?.alignment ?? alignment)}</Badge>
            <span className="text-copy-13 text-foreground">
              状态：{statusLabel(data?.status ?? 'NOT_PROJECTED')}
            </span>
          </div>
          <p className="text-copy-13 text-muted-foreground mt-1">{alignmentNote(data)}</p>
          <p className="text-copy-13 text-muted-foreground mt-1">{alignmentDifferenceNote()}</p>
          <p className="text-copy-13 text-muted-foreground mt-1">{p90Text(data)}</p>
        </div>
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
            title="分时与分周模式报表读不到"
            description={`${error}（这是「读不到」，不是「没有数据」）—— 上游不可达或没有读权限时就会出现。`}
            action={
              <Button
                variant="outline"
                size="sm"
                disabled={loading || busy}
                onClick={() => void load()}
              >
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
          <EmptyState
            title="正在读取分时与分周模式报表…"
            description="读取期间页面上的「数据截至」仍然有效。"
          />
        </SectionCard>
      )}

      {/* ── 空态 ───────────────────────────────────────────────────────── */}
      {state === 'empty' && (
        <SectionCard>
          <EmptyState
            title={emptyReasonLabel(data?.emptyReason ?? null) || '这个窗口没有分时事实'}
            description={emptyText(data)}
            action={
              <Button
                variant="outline"
                size="sm"
                disabled={busy}
                onClick={() => void refreshProjection()}
              >
                <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
                刷新分时投影
              </Button>
            }
          />
        </SectionCard>
      )}

      {state === 'ready' && data && (
        <>
          {/* ── 热力图 ───────────────────────────────────────────────────── */}
          <SectionCard
            title="时段分布热力图"
            description="🔴 行 = 店铺本地时区的星期几（ISO：1 = 周一 … 7 = 周日），列 = 店铺本地时区的小时。深浅用服务端给的 P90 高峰刻度分档 —— 前端不自己重新定义什么叫高峰。"
          >
            <div className="overflow-x-auto">
              <table className="w-full border-collapse text-center">
                <thead>
                  <tr>
                    <th className="border-border text-copy-13 text-muted-foreground border px-2 py-1 text-left">
                      本地星期 \ 本地小时
                    </th>
                    {Array.from({ length: 24 }, (_, hour) => (
                      <th
                        key={hour}
                        className="border-border text-label-12 text-muted-foreground border px-1 py-1"
                      >
                        {String(hour).padStart(2, '0')}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {WEEKDAY_ORDER.map((weekday) => (
                    <tr key={weekday}>
                      <th className="border-border text-copy-13 text-muted-foreground border px-2 py-1 text-left font-normal">
                        {weekdayLabel(weekday, data)}
                      </th>
                      {heatRow(data.heatmap, weekday).map((cell) => {
                        const tier = heatTier(cell, data.p90OrderCount);
                        return (
                          <td
                            key={`${cell.weekday}-${cell.hour}`}
                            className={`border-border border px-1 py-1 ${heatCellClass(tier)}`}
                            title={`${cell.weekdayLabel} ${cell.hourLabel}｜订单 ${countText(cell.orderCount)} 笔｜占比 ${percentText(cell.orderShare)}｜金额 ${amountsText(cell.salesAmounts)}${cell.p90Peak ? '｜达到 P90 高峰刻度' : ''}`}
                          >
                            <span className="text-label-12">{countText(cell.orderCount)}</span>
                            <span className="text-label-12 ml-1 opacity-70">
                              {percentText(cell.orderShare)}
                            </span>
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="text-copy-13 text-muted-foreground mt-3">
              每格：<span className="text-foreground">订单笔数</span> +{' '}
              <span className="text-foreground">订单占比</span>。悬停可看金额（按币种分别列出）。
              占比为「不可比」说明这个窗口的分母是 0 —— 那不是 0%。
            </p>
          </SectionCard>

          {/* ── 小时分布 / 星期分布 ─────────────────────────────────────── */}
          <div className="grid gap-4 lg:grid-cols-2">
            <SectionCard
              title="按小时分布"
              description="🔴 店铺本地时区的小时（跨店铺汇总时按当前口径对齐）。"
            >
              <div className="space-y-1">
                {data.hourDistribution.map((bucket) => {
                  const tier = heatTier(
                    {
                      weekday: 0,
                      weekdayLabel: '',
                      hour: bucket.hour,
                      hourLabel: bucket.hourLabel,
                      orderCount: bucket.orderCount,
                      unitSold: bucket.unitSold,
                      salesAmounts: bucket.salesAmounts,
                      orderShare: bucket.orderShare,
                      p90Peak: bucket.p90Peak,
                    },
                    data.p90OrderCount,
                  );
                  return (
                    <div key={bucket.hour} className="flex items-center gap-2">
                      <span className="text-copy-13 text-muted-foreground w-12 shrink-0 tabular-nums">
                        {bucket.hourLabel}
                      </span>
                      <span
                        className={`text-label-12 flex h-6 min-w-14 items-center justify-center rounded px-2 ${heatCellClass(tier)}`}
                      >
                        {countText(bucket.orderCount)} 笔
                      </span>
                      <span className="text-copy-13 text-muted-foreground">
                        {percentText(bucket.orderShare)}
                      </span>
                      {bucket.p90Peak && <Badge tone="warning">P90 高峰刻度</Badge>}
                    </div>
                  );
                })}
              </div>
            </SectionCard>

            <SectionCard
              title="按星期分布"
              description="🔴 店铺本地时区的星期几（**不是** ISO 周的周数 —— 周数是另一回事，本模块不产出）。"
            >
              <div className="space-y-1">
                {data.weekdayDistribution.map((bucket) => (
                  <div key={bucket.weekday} className="flex items-center gap-2">
                    <span className="text-copy-13 text-muted-foreground w-12 shrink-0">
                      {bucket.weekdayLabel}
                    </span>
                    <span className="text-copy-13 text-foreground w-16 shrink-0 tabular-nums">
                      {countText(bucket.orderCount)} 笔
                    </span>
                    <span className="text-copy-13 text-muted-foreground">
                      {percentText(bucket.orderShare)}
                    </span>
                    <span className="text-copy-13 text-muted-foreground">
                      {quantityText(bucket.unitSold)} 件
                    </span>
                    <span className="text-copy-13 text-muted-foreground">
                      {amountsText(bucket.salesAmounts)}
                    </span>
                  </div>
                ))}
              </div>
            </SectionCard>
          </div>

          {/* ── 时段判定 ─────────────────────────────────────────────────── */}
          <SectionCard
            title="时段判定（高峰 / 平峰 / 低谷）"
            description="🔴 层级是**租户自己标的**，系统只负责**用阈值核对它**。核对结果与理由都原样显示 —— 用户要看得懂「为什么这个时段算高峰」。"
          >
            {data.slots.length === 0 ? (
              <EmptyState
                title="这个租户还没有时段配置"
                description="先刷新投影（会播种一份初始划分），或用 PUT 接口整体替换。"
              />
            ) : (
              <div className="space-y-2">
                {data.slots.map((slot) => (
                  <div key={slot.slotCode} className="border-border rounded-lg border px-4 py-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-copy-14 text-foreground font-medium">
                        {slot.displayName}
                      </span>
                      <Badge tone={levelTone(slot.level)}>
                        {slot.levelLabel || levelLabel(slot.level)}
                      </Badge>
                      <Badge tone={qualifiedTone(slot.qualified)}>
                        {qualifiedLabel(slot.qualified)}
                      </Badge>
                      <span className="text-copy-13 text-muted-foreground">
                        {slotRangeText(slot.startHour, slot.endHour)}
                      </span>
                      <span className="text-copy-13 text-muted-foreground">
                        {countText(slot.orderCount)} 笔 · {quantityText(slot.unitSold)} 件 ·{' '}
                        {amountsText(slot.salesAmounts)}
                      </span>
                      <span className="text-copy-13 text-muted-foreground">
                        订单占比 {percentText(slot.orderShare)}
                      </span>
                    </div>
                    <p className="text-copy-13 text-muted-foreground mt-1">{slot.reason}</p>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          {/* ── 高峰时段排序 ─────────────────────────────────────────────── */}
          <SectionCard
            title="高峰时段排序"
            description="🔴 排序依据来自**时段配置**（租户标的层级）或**分位数**（数据驱动的 P90 刻度），两者**分开显示**，不混为一谈。"
          >
            <p className="text-copy-13 text-muted-foreground mb-3">{rankingMetricNote(data)}</p>
            {data.peakRanking.length === 0 ? (
              <EmptyState
                title="这个窗口没有可排序的时段"
                description="先刷新投影，或换一个时间范围。"
              />
            ) : (
              <div className="space-y-2">
                {data.peakRanking.map((rank) => (
                  <div
                    key={`${rank.rank}-${rank.slotCode}-${rank.currency}`}
                    className="border-border flex flex-wrap items-center gap-2 rounded-lg border px-4 py-3"
                  >
                    <span className="text-copy-14 text-foreground w-8 shrink-0 font-medium tabular-nums">
                      #{rank.rank}
                    </span>
                    <span className="text-copy-14 text-foreground font-medium">
                      {rank.displayName}
                    </span>
                    <Badge tone={levelTone(rank.level)}>
                      {rank.levelLabel || levelLabel(rank.level)}
                    </Badge>
                    <Badge tone={qualifiedTone(rank.qualified)}>
                      {qualifiedLabel(rank.qualified)}
                    </Badge>
                    <span className="text-copy-13 text-foreground">
                      {rank.metricLabel} {peakRankValueText(rank)}
                    </span>
                    <span className="text-copy-13 text-muted-foreground">
                      订单占比 {percentText(rank.orderShare)}
                    </span>
                    <p className="text-copy-13 text-muted-foreground w-full">{rank.reason}</p>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          {/* ── 店铺时区档案 / 覆盖度 / 未定时的归属 ─────────────────────── */}
          <div className="grid gap-4 lg:grid-cols-2">
            <SectionCard
              title="店铺时区档案"
              description="🔴 店铺**没有**自己的时区字段：时区来自 channel 的**站点**参数。定不了的店铺单列 —— 摊给某个时区是造假。"
            >
              {shops.length === 0 ? (
                <EmptyState title="还没有店铺时区档案" description="先刷新投影。" />
              ) : (
                <div className="space-y-2">
                  {shops.map((shop) => (
                    <div key={shop.shopCode} className="border-border rounded-lg border px-4 py-3">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="text-copy-14 text-foreground font-medium">
                          {shop.shopName || shop.shopCode}
                        </span>
                        <span className="text-copy-13 text-muted-foreground">{shop.shopCode}</span>
                        <Badge tone={timezoneTone(shop.timezoneStatus)}>
                          {timezoneStatusLabel(shop.timezoneStatus)}
                        </Badge>
                        <span className="text-copy-13 text-foreground">
                          {shopTimezoneText(shop)}
                        </span>
                      </div>
                      <p className="text-copy-13 text-muted-foreground mt-1">
                        {shop.timezoneReason}
                      </p>
                    </div>
                  ))}
                </div>
              )}
            </SectionCard>

            <SectionCard
              title="覆盖度与归属边界"
              description="🔴 投影不满时，格子里的 0 里有多少是真没单、有多少是没投影，说不清。"
            >
              <div className="flex flex-wrap items-center gap-2">
                <Badge tone={coverageTone(data.coverage)}>
                  投影 {data.coverage.projectedDays} / {data.coverage.expectedDays} 天
                </Badge>
              </div>
              <p className="text-copy-13 text-muted-foreground mt-2">
                {coverageText(data.coverage)}
              </p>
              <div className="border-border mt-3 rounded-lg border px-4 py-3">
                <span className="text-copy-14 text-foreground font-medium">
                  未能按店铺本地时分桶
                </span>
                <p className="text-copy-13 text-muted-foreground mt-1">
                  {unattributedText(data.unattributed) ||
                    '没有这类订单 —— 全部订单都能归属到某个店铺的本地小时 / 星期。'}
                </p>
                {data.unattributed.reason && (
                  <p className="text-copy-13 text-muted-foreground mt-1">
                    {data.unattributed.reason}
                  </p>
                )}
              </div>
            </SectionCard>
          </div>

          {/* ── 能力声明 ─────────────────────────────────────────────────── */}
          <SectionCard
            title="能力声明"
            description="哪些上游真接通、哪些确实拿不到、判定怎么算 —— 都写清楚，不用界面猜。"
          >
            <div className="space-y-4">
              <div>
                <h4 className="text-copy-14 text-foreground font-medium">判定规则</h4>
                <div className="mt-2 space-y-2">
                  {ruleNotes(capabilities).map((rule) => (
                    <div key={rule.code} className="border-border rounded-lg border px-4 py-3">
                      <Badge tone="info">{rule.code}</Badge>
                      <p className="text-copy-13 text-muted-foreground mt-1">{rule.note}</p>
                    </div>
                  ))}
                </div>
              </div>

              <div>
                <h4 className="text-copy-14 text-foreground font-medium">确实拿不到的部分</h4>
                <ul className="text-copy-13 text-muted-foreground mt-2 list-disc space-y-1 pl-5">
                  {notDeliveredLines(capabilities).map((line) => (
                    <li key={line}>{line}</li>
                  ))}
                </ul>
              </div>

              <div>
                <h4 className="text-copy-14 text-foreground font-medium">本服务真读过的上游</h4>
                <ul className="text-copy-13 text-muted-foreground mt-2 list-disc space-y-1 pl-5">
                  {upstreamModules(capabilities).map((line) => (
                    <li key={line}>{line}</li>
                  ))}
                </ul>
              </div>

              {warnings(data).length > 0 && (
                <div>
                  <h4 className="text-copy-14 text-foreground flex items-center gap-1.5 font-medium">
                    <TriangleAlert className="h-3.5 w-3.5" />
                    口径提示
                  </h4>
                  <ul className="text-copy-13 text-muted-foreground mt-2 list-disc space-y-1 pl-5">
                    {warnings(data).map((line) => (
                      <li key={line}>{line}</li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          </SectionCard>

          {/* ── 时段配置 + 口径目录 ──────────────────────────────────────── */}
          <div className="grid gap-4 lg:grid-cols-2">
            <SectionCard
              title="时段配置（租户自己的数据）"
              description="🔴 区间是**左闭右开** `[startHour, endHour)`：startHour 含、endHour 不含。启用时段必须**完整覆盖 0..24 小时**且**互不重叠** —— 这是数据层的约束，改坏了写不进来。"
            >
              {slotConfigs.length === 0 ? (
                <EmptyState
                  title="还没有时段配置"
                  description="先刷新投影（会播种一份初始划分）。"
                />
              ) : (
                <div className="space-y-2">
                  {slotConfigs.map((slot) => (
                    <div key={slot.id} className="border-border rounded-lg border px-4 py-3">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="text-copy-14 text-foreground font-medium">
                          {slot.displayName}
                        </span>
                        <Badge tone={levelTone(slot.level)}>
                          {slot.levelLabel || levelLabel(slot.level)}
                        </Badge>
                        <span className="text-copy-13 text-muted-foreground">
                          {slotRangeText(slot.startHour, slot.endHour)}
                        </span>
                        {!slot.enabled && <Badge tone="neutral">已停用</Badge>}
                      </div>
                      <p className="text-copy-13 text-muted-foreground mt-1">{slot.description}</p>
                      <p className="text-copy-13 text-muted-foreground mt-1">
                        判定基准 {slot.qualifyBasis} · 方向 {slot.qualifyComparison} · 阈值{' '}
                        {slot.qualifyThreshold}
                      </p>
                      <p className="text-copy-13 text-muted-foreground mt-1">
                        {boundaryNote(slot)}
                      </p>
                    </div>
                  ))}
                </div>
              )}
            </SectionCard>

            <SectionCard
              title="指标口径目录"
              description="🔴 公司维度的三个基础口径（订单总额 / 销量 / 订单数）**唯一定义处是 13.1.1.1 的口径表**，本模块只是**换了一种切法**，不重定义。"
            >
              <div className="space-y-2">
                {definitions.map((definition) => (
                  <div
                    key={definition.metricCode}
                    className="border-border rounded-lg border px-4 py-3"
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-copy-14 text-foreground font-medium">
                        {definition.displayName}
                      </span>
                      <Badge tone="neutral">{definition.metricCode}</Badge>
                      <Badge tone="info">{metricUnitLabel(definition.unit)}</Badge>
                    </div>
                    <p className="text-copy-13 text-muted-foreground mt-1">
                      {definition.description}
                    </p>
                    <p className="text-copy-13 text-muted-foreground mt-1">
                      聚合：{aggregationLabel(definition.aggregation)} · 口径关系：
                      {metricFormulaText(definition)}
                    </p>
                    <p className="text-copy-13 text-muted-foreground mt-1">
                      定义处：{definition.referencedFrom}
                    </p>
                  </div>
                ))}
              </div>
            </SectionCard>
          </div>
        </>
      )}
    </div>
  );
}
