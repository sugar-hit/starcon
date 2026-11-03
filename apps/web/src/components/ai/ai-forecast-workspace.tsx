'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  PageHeader,
  SectionCard,
  Segmented,
  StatCard,
} from '@starcon/ui';
import { CircleAlert, Loader2, RefreshCw, Sparkles, TriangleAlert } from 'lucide-react';
import {
  adoptSuggestion,
  adviceNotice,
  algorithmLabel,
  deleteHistory,
  deletePromotion,
  elasticityRows,
  factorRows,
  fetchCapabilities,
  fetchForecastDetail,
  fetchForecasts,
  fetchHistory,
  fetchHotItems,
  fetchOpportunities,
  fetchOverview,
  fetchPriceElasticity,
  fetchPromotions,
  fetchSuggestions,
  formatRate,
  formatUnits,
  historySourceLabel,
  hotItemRows,
  intervalGeometry,
  intervalTone,
  opportunityRows,
  rejectSuggestion,
  runConnectivityNote,
  runForecast,
  saveHistory,
  savePromotion,
  suggestionStatusLabel,
  suggestionTone,
  syncHistory,
  type Tone,
} from './ai-forecast-model';
import type {
  AiForecastCapabilities,
  AiForecastElasticityRow,
  AiForecastHistoryRow,
  AiForecastHotItemRow,
  AiForecastOpportunityRow,
  AiForecastOverview,
  AiForecastPromotionRow,
  AiForecastResultDetail,
  AiForecastResultPage,
  AiForecastSuggestionRow,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 18.4.2.2「选品与需求预测」工作区（卷 18 前端入口 `/ai/forecast`）。
 *
 * 蓝图：「**AI 预测能力**：销量预测（含促销与大促影响）、补货量建议、价格弹性与调价建议、
 * 爆品识别与机会品发现。输入：历史销量、库存、在途、价格、促销日历；预测参数。
 * 输出：预测结果 + 置信区间 + 关键影响因素说明。**预测必须给置信区间和影响因素**，
 * 单点数字会让用户过度信任；建议只作为参考输入，不自动执行。」
 *
 * 🔴 页面上的六条纪律：
 *
 * 1. **区间可视化 + 影响因素是主体**，不是一个孤零零的点估计。点估计在区间条上的位置
 *    看得见，区间相对点估计有多宽也看得见（越宽 = 越不可信）。
 * 2. **影响因素逐条摊开**：方向、取值、**口径说明**（后端给的原文，前端不自己编）。
 * 3. **建议区明确标注「仅建议，不自动执行」**，采纳 / 驳回按钮旁边就写着这一点。
 * 4. **未接通上游就写「未接通 + 归属系统」**，不显示假数据、不显示空白。
 * 5. **加载 / 空 / 错误三态齐全**：空态区分「筛选无结果」与「还没有输入历史」。
 * 6. **能力声明逐条展示**：算法口径、阈值、来源都能点开看，判定不是黑盒。
 */

type Tab = 'forecasts' | 'suggestions' | 'promotions' | 'history' | 'insights' | 'capabilities';

const PAGE_SIZE = 10;

/** 语气 → Badge 的 tone。 */
const BADGE_TONE: Record<Tone, 'neutral' | 'success' | 'warning' | 'danger'> = {
  ok: 'success',
  warn: 'warning',
  danger: 'danger',
  muted: 'neutral',
};

const TABS: { value: Tab; label: string }[] = [
  { value: 'forecasts', label: '预测结果' },
  { value: 'suggestions', label: '建议' },
  { value: 'promotions', label: '促销日历' },
  { value: 'history', label: '需求历史' },
  { value: 'insights', label: '爆品与机会品' },
  { value: 'capabilities', label: '能力声明' },
];

export function AiForecastWorkspace() {
  const [tab, setTab] = useState<Tab>('forecasts');

  const [capabilities, setCapabilities] = useState<AiForecastCapabilities | null>(null);
  const [overview, setOverview] = useState<AiForecastOverview | null>(null);

  const [forecasts, setForecasts] = useState<AiForecastResultPage | null>(null);
  const [detail, setDetail] = useState<AiForecastResultDetail | null>(null);
  const [skuFilter, setSkuFilter] = useState('');
  const [pageIndex, setPageIndex] = useState(0);

  const [suggestions, setSuggestions] = useState<AiForecastSuggestionRow[]>([]);
  const [suggestionType, setSuggestionType] = useState('');

  const [promotions, setPromotions] = useState<AiForecastPromotionRow[]>([]);
  const [promotionForm, setPromotionForm] = useState({
    name: '',
    promotionType: 'PROMO',
    scopeType: 'ALL',
    scopeValue: '',
    startsAt: '',
    endsAt: '',
    discountRate: '',
    expectedLift: '',
  });

  const [history, setHistory] = useState<AiForecastHistoryRow[]>([]);
  const [historyTotal, setHistoryTotal] = useState(0);
  const [historySku, setHistorySku] = useState('');
  const [historyForm, setHistoryForm] = useState({
    skuCode: '',
    skuName: '',
    businessDate: '',
    units: '',
    unitPrice: '',
    currency: '',
  });

  const [elasticity, setElasticity] = useState<AiForecastElasticityRow[]>([]);
  const [hotItems, setHotItems] = useState<AiForecastHotItemRow[]>([]);
  const [opportunities, setOpportunities] = useState<AiForecastOpportunityRow[]>([]);
  const [insightNote, setInsightNote] = useState('');

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const fail = (e: unknown) => {
    setError(e instanceof Error ? e.message : '请求失败，请稍后重试');
  };

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, ov, page, sugg, promos, hist, elas, hot, opp] = await Promise.all([
        fetchCapabilities(),
        fetchOverview(),
        fetchForecasts({ skuCode: skuFilter, page: pageIndex, size: PAGE_SIZE }),
        fetchSuggestions({ suggestionType, page: 0, size: 50 }),
        fetchPromotions({ page: 0, size: 50 }),
        fetchHistory({ skuCode: historySku, page: 0, size: 50 }),
        fetchPriceElasticity({}),
        fetchHotItems({ limit: 20 }),
        fetchOpportunities({ limit: 20 }),
      ]);
      setCapabilities(caps);
      setOverview(ov);
      setForecasts(page);
      setSuggestions(sugg.items ?? []);
      setPromotions(promos.items ?? []);
      setHistory(hist.items ?? []);
      setHistoryTotal(hist.total ?? 0);
      setElasticity(elas.items ?? []);
      setHotItems(hot.items ?? []);
      setOpportunities(opp.items ?? []);
      setInsightNote(`${hot.note ?? ''} ${opp.note ?? ''}`.trim());
    } catch (e) {
      fail(e);
    } finally {
      setLoading(false);
    }
  }, [skuFilter, pageIndex, suggestionType, historySku]);

  useEffect(() => {
    void load();
  }, [load]);

  const openDetail = useCallback(async (id: string) => {
    setBusy(true);
    setError('');
    try {
      setDetail(await fetchForecastDetail(id));
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  }, []);

  const act = async (work: () => Promise<unknown>, ok: string) => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await work();
      setNotice(ok);
      await load();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const onRunForecast = async () => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await runForecast({
        algorithm: 'MOVING_AVERAGE',
        maWindowDays: 3,
        smoothingAlpha: 0.3,
        windowDays: 30,
        horizonDays: 30,
        confidenceLevel: 0.95,
        note: '',
      });
      setNotice(
        `预测已生成：${result.run.forecastSkuCount} 个 SKU 出结果，${result.run.skippedSkuCount} 个被跳过。` +
          `${result.run.skippedReason ? ' ' + result.run.skippedReason : ''}`,
      );
      await load();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const onSavePromotion = async () => {
    await act(
      () =>
        savePromotion({
          name: promotionForm.name,
          promotionType: promotionForm.promotionType,
          scopeType: promotionForm.scopeType,
          scopeValue: promotionForm.scopeValue,
          startsAt: new Date(promotionForm.startsAt).toISOString(),
          endsAt: new Date(promotionForm.endsAt).toISOString(),
          discountRate: promotionForm.discountRate ? Number(promotionForm.discountRate) : null,
          expectedLift: Number(promotionForm.expectedLift),
          source: 'MANUAL',
          sourceRef: '',
          note: '',
        }),
      '促销已登记。',
    );
  };

  const onSaveHistory = async () => {
    await act(
      () =>
        saveHistory({
          skuCode: historyForm.skuCode,
          skuName: historyForm.skuName,
          businessDate: historyForm.businessDate,
          units: Number(historyForm.units),
          unitPrice: historyForm.unitPrice ? Number(historyForm.unitPrice) : null,
          revenue: null,
          currency: historyForm.currency || null,
          channelCode: '',
          sourceRef: '',
          note: '',
        }),
      '需求历史已录入。',
    );
  };

  const geometry = useMemo(
    () =>
      detail
        ? intervalGeometry(detail.result.lower, detail.result.point, detail.result.upper)
        : null,
    [detail],
  );

  const hasHistory = historyTotal > 0;

  return (
    <div className="space-y-4">
      <PageHeader
        title="选品与需求预测"
        description="销量预测（含促销与大促影响）、补货量建议、价格弹性与调价建议、爆品识别与机会品发现。每条预测都带置信区间与影响因素；建议仅作参考，不自动执行。"
        actions={
          <div className="flex items-center gap-2">
            <Button onClick={() => void onRunForecast()} disabled={busy || loading}>
              {busy ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <Sparkles className="h-4 w-4" />
              )}
              发起预测
            </Button>
            <Button onClick={() => void load()} disabled={loading}>
              {loading ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <RefreshCw className="h-4 w-4" />
              )}
              刷新
            </Button>
          </div>
        }
      />

      {error && (
        <div className="text-destructive flex items-start gap-2 rounded-md border p-3 text-sm">
          <CircleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{error}</span>
        </div>
      )}
      {notice && (
        <div className="text-muted-foreground flex items-start gap-2 rounded-md border p-3 text-sm">
          <Sparkles className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{notice}</span>
        </div>
      )}

      {overview && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="预测运行" value={overview.runs} unit="次" polarity="neutral" />
          <StatCard label="预测结果" value={overview.forecasts} unit="条" polarity="neutral" />
          <StatCard label="建议" value={overview.suggestions} unit="条" polarity="neutral" />
          <StatCard
            label="需求历史"
            value={overview.historyRows}
            unit="行 / "
            polarity="neutral"
            comparisonLabel={`${overview.historySkus} 个 SKU`}
          />
        </div>
      )}

      <Segmented
        value={tab}
        onChange={(value) => setTab(value as Tab)}
        options={TABS.map((item) => ({ value: item.value, label: item.label }))}
      />

      {loading && (
        <div className="text-muted-foreground flex items-center gap-2 text-sm">
          <Loader2 className="h-4 w-4 animate-spin" /> 正在读取…
        </div>
      )}

      {!loading && tab === 'forecasts' && (
        <div className="space-y-4">
          <SectionCard
            title="预测结果"
            description="每条预测都带点估计、置信区间与影响因素；点估计落在区间的哪里一眼可见。"
            extra={
              <Input
                className="w-48"
                placeholder="按 SKU 码筛选"
                value={skuFilter}
                onChange={(event) => {
                  setPageIndex(0);
                  setSkuFilter(event.target.value);
                }}
              />
            }
          >
            {!forecasts || forecasts.items.length === 0 ? (
              <EmptyState
                icon={<Sparkles className="h-5 w-5" />}
                title={hasHistory ? '还没有预测结果' : '还没有可预测的历史销量'}
                description={
                  hasHistory
                    ? '点「发起预测」跑一次：会按移动平均口径算出点估计、置信区间与影响因素。'
                    : '先到「需求历史」录入或同步逐日销量（至少 4 天样本才给区间），再回来发起预测。'
                }
              />
            ) : (
              <div className="space-y-2">
                {forecasts.items.map((item) => (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => void openDetail(item.id)}
                    className="border-border hover:bg-muted/40 flex w-full items-center justify-between gap-3 rounded-md border px-3 py-2 text-left"
                  >
                    <div className="min-w-0">
                      <div className="text-copy-14 text-foreground font-medium">
                        {item.skuName || item.skuCode}
                        <span className="text-muted-foreground text-copy-12 ml-2">
                          {item.skuCode}
                        </span>
                      </div>
                      <div className="text-muted-foreground text-copy-12">
                        {algorithmLabel(item.algorithm)} · {item.windowDays} 天窗口 ·{' '}
                        {item.horizonDays} 天预测期 · 来源 {historySourceLabel(item.historySource)}{' '}
                        · 样本 {item.sampleDays} 天
                      </div>
                    </div>
                    <div className="text-right">
                      <div className="text-copy-14 text-foreground font-medium">
                        {formatUnits(item.point)} 件
                      </div>
                      <div className="text-muted-foreground text-copy-12">
                        区间 {formatUnits(item.lower)} ~ {formatUnits(item.upper)}
                      </div>
                    </div>
                  </button>
                ))}
                <div className="text-muted-foreground text-copy-12 flex items-center justify-between">
                  <span>
                    共 {forecasts.total} 条 · 第 {forecasts.page + 1} /{' '}
                    {Math.max(forecasts.totalPages, 1)} 页
                  </span>
                  <span className="flex gap-2">
                    <Button
                      size="sm"
                      disabled={forecasts.page <= 0}
                      onClick={() => setPageIndex((value) => Math.max(value - 1, 0))}
                    >
                      上一页
                    </Button>
                    <Button
                      size="sm"
                      disabled={forecasts.page + 1 >= forecasts.totalPages}
                      onClick={() => setPageIndex((value) => value + 1)}
                    >
                      下一页
                    </Button>
                  </span>
                </div>
              </div>
            )}
          </SectionCard>

          {detail && geometry && (
            <SectionCard
              title={`预测详情 · ${detail.result.skuName || detail.result.skuCode}`}
              description="区间与影响因素是这份预测的**全部可信度来源** —— 点估计只是区间里的一个点。"
              extra={
                <Button size="sm" onClick={() => setDetail(null)}>
                  收起
                </Button>
              }
            >
              <div className="space-y-4">
                <div className="grid gap-3 sm:grid-cols-4">
                  <StatCard
                    label="点估计（预测期总量）"
                    value={formatUnits(detail.result.point)}
                    unit="件"
                    polarity="neutral"
                  />
                  <StatCard
                    label="置信区间下界"
                    value={formatUnits(detail.result.lower)}
                    unit="件"
                    polarity="neutral"
                  />
                  <StatCard
                    label="置信区间上界"
                    value={formatUnits(detail.result.upper)}
                    unit="件"
                    polarity="neutral"
                  />
                  <StatCard
                    label="置信水平"
                    value={formatRate(detail.result.confidenceLevel)}
                    polarity="neutral"
                    comparisonLabel={`z = ${detail.result.zValue} · σ = ${formatUnits(detail.result.sigma)}`}
                  />
                </div>

                <div className="space-y-1">
                  <div className="text-copy-13 text-muted-foreground">
                    区间条：点估计落在区间 {geometry.pointPct.toFixed(1)}% 处，区间半宽约为点估计的{' '}
                    {formatRate(geometry.widthRatio)}
                    {geometry.widthRatio > 0.4 ? '（偏宽，这个预测要打折看）' : ''}
                  </div>
                  <div className="bg-muted relative h-6 w-full overflow-hidden rounded-md">
                    <div
                      className="bg-primary/30 absolute inset-y-0"
                      style={{
                        left: `${geometry.lowerPct}%`,
                        width: `${geometry.upperPct - geometry.lowerPct}%`,
                      }}
                    />
                    <div
                      className="bg-primary absolute inset-y-0 w-1"
                      style={{ left: `calc(${geometry.pointPct}% - 2px)` }}
                    />
                  </div>
                  <div className="text-muted-foreground text-copy-12 flex justify-between">
                    <span>下界 {formatUnits(detail.result.lower)}</span>
                    <span>点估计 {formatUnits(detail.result.point)}</span>
                    <span>上界 {formatUnits(detail.result.upper)}</span>
                  </div>
                </div>

                <div className="text-muted-foreground text-copy-12">
                  日均点估计 {formatUnits(detail.result.dailyPoint)} 件/天 · 基线{' '}
                  {formatUnits(detail.result.baselineDaily)} 件/天 · 促销因子{' '}
                  {detail.result.promoFactor} · 样本覆盖 {formatRate(detail.result.coverageRate)}（
                  {detail.result.sampleDays}/{detail.result.windowDays} 天） · 历史来源{' '}
                  {historySourceLabel(detail.result.historySource)}
                </div>

                <div>
                  <div className="text-copy-14 text-foreground mb-2 font-medium">关键影响因素</div>
                  <div className="space-y-2">
                    {factorRows(detail.factors).map((factor) => (
                      <div key={factor.label} className="border-border rounded-md border px-3 py-2">
                        <div className="flex items-center justify-between gap-2">
                          <span className="text-copy-13 text-foreground font-medium">
                            {factor.label}
                          </span>
                          <Badge tone={BADGE_TONE[factor.tone]}>{factor.direction}</Badge>
                        </div>
                        <div className="text-copy-13 text-muted-foreground mt-1">
                          取值：{factor.value}
                        </div>
                        <div className="text-muted-foreground text-copy-12 mt-1">
                          {factor.explanation}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            </SectionCard>
          )}
        </div>
      )}

      {!loading && tab === 'suggestions' && (
        <SectionCard
          title="建议"
          description="🔴 仅建议，不自动执行：采纳只记录「人决定了采纳」，不会建补货单、不会改价。"
          extra={
            <Segmented
              value={suggestionType}
              onChange={(value) => setSuggestionType(value)}
              options={[
                { value: '', label: '全部' },
                { value: 'REPLENISH', label: '补货' },
                { value: 'PRICE', label: '调价' },
              ]}
            />
          }
        >
          {suggestions.length === 0 ? (
            <EmptyState
              icon={<TriangleAlert className="h-5 w-5" />}
              title="还没有建议"
              description="先发起一次预测：库存与在途读得到时才会生成补货建议（读不到就不生成，不用 0 冒充「库存为 0」）。"
            />
          ) : (
            <div className="space-y-2">
              {suggestions.map((item) => (
                <div key={item.id} className="border-border rounded-md border px-3 py-2">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <div className="min-w-0">
                      <div className="text-copy-14 text-foreground font-medium">
                        {item.skuName || item.skuCode}
                        <span className="text-muted-foreground text-copy-12 ml-2">
                          {item.suggestionType === 'REPLENISH' ? '补货量建议' : '调价建议'}
                        </span>
                      </div>
                      <div className="text-muted-foreground text-copy-12">
                        {item.suggestionType === 'REPLENISH'
                          ? `建议补货 ${formatUnits(item.suggestedQty)} 件 · 在手 ${formatUnits(item.currentQty)} · 在途 ${formatUnits(item.inTransitQty)} · 需求 ${formatUnits(item.demandLower)} ~ ${formatUnits(item.demandUpper)}`
                          : `建议价 ${formatUnits(item.suggestedPrice)} · 当前价 ${formatUnits(item.currentPrice)} · 调价 ${formatRate(item.priceDeltaRate)} · 弹性 ${item.elasticity}`}
                      </div>
                    </div>
                    <div className="flex items-center gap-2">
                      <Badge tone={BADGE_TONE[suggestionTone(item.status)]}>
                        {suggestionStatusLabel(item.status)}
                      </Badge>
                      {item.status === 'SUGGESTED' && (
                        <>
                          <Button
                            size="sm"
                            disabled={busy}
                            onClick={() =>
                              void act(
                                () => adoptSuggestion(item.id, '人工确认采纳'),
                                '已记录采纳（本模块不会替你下单 / 改价）。',
                              )
                            }
                          >
                            采纳
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={busy}
                            onClick={() =>
                              void act(() => rejectSuggestion(item.id, '人工驳回'), '已记录驳回。')
                            }
                          >
                            驳回
                          </Button>
                        </>
                      )}
                    </div>
                  </div>
                  <div className="text-muted-foreground text-copy-12 mt-2">{item.basis}</div>
                  <div className="text-muted-foreground text-copy-12 mt-1">
                    {adviceNotice(item.suggestionType)}
                  </div>
                </div>
              ))}
            </div>
          )}
        </SectionCard>
      )}

      {!loading && tab === 'promotions' && (
        <div className="space-y-4">
          <SectionCard
            title="登记促销 / 大促"
            description="促销是**租户自己的业务事实**：这里录的每一条都带录入方式，不会假装是从上游同步来的。预期销量提升率必填 —— 没有它，预测无法计入促销影响。"
          >
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <Input
                placeholder="促销名称"
                value={promotionForm.name}
                onChange={(event) =>
                  setPromotionForm((form) => ({ ...form, name: event.target.value }))
                }
              />
              <select
                className="border-input bg-background h-9 rounded-md border px-3 text-sm"
                value={promotionForm.promotionType}
                onChange={(event) =>
                  setPromotionForm((form) => ({ ...form, promotionType: event.target.value }))
                }
              >
                <option value="PROMO">促销</option>
                <option value="BIG_SALE">大促</option>
                <option value="FLASH">秒杀</option>
                <option value="SEASONAL">季节性活动</option>
                <option value="CLEARANCE">清仓</option>
              </select>
              <select
                className="border-input bg-background h-9 rounded-md border px-3 text-sm"
                value={promotionForm.scopeType}
                onChange={(event) =>
                  setPromotionForm((form) => ({ ...form, scopeType: event.target.value }))
                }
              >
                <option value="ALL">租户级（全部 SKU）</option>
                <option value="SKU">指定 SKU</option>
              </select>
              <Input
                placeholder={promotionForm.scopeType === 'SKU' ? 'SKU 码' : '（租户级不用填）'}
                disabled={promotionForm.scopeType !== 'SKU'}
                value={promotionForm.scopeValue}
                onChange={(event) =>
                  setPromotionForm((form) => ({ ...form, scopeValue: event.target.value }))
                }
              />
              <Input
                type="datetime-local"
                value={promotionForm.startsAt}
                onChange={(event) =>
                  setPromotionForm((form) => ({ ...form, startsAt: event.target.value }))
                }
              />
              <Input
                type="datetime-local"
                value={promotionForm.endsAt}
                onChange={(event) =>
                  setPromotionForm((form) => ({ ...form, endsAt: event.target.value }))
                }
              />
              <Input
                placeholder="折扣率（0.2 = 降价 20%）"
                value={promotionForm.discountRate}
                onChange={(event) =>
                  setPromotionForm((form) => ({ ...form, discountRate: event.target.value }))
                }
              />
              <Input
                placeholder="预期销量提升率（0.3 = +30%）"
                value={promotionForm.expectedLift}
                onChange={(event) =>
                  setPromotionForm((form) => ({ ...form, expectedLift: event.target.value }))
                }
              />
            </div>
            <div className="mt-3 flex items-center gap-2">
              <Button
                disabled={busy || !promotionForm.name || !promotionForm.expectedLift}
                onClick={() => void onSavePromotion()}
              >
                登记促销
              </Button>
              <span className="text-muted-foreground text-copy-12">
                预测期内生效的促销取**最大**一个（不叠加），影响写进「关键影响因素」。
              </span>
            </div>
          </SectionCard>

          <SectionCard title="促销日历" description="租户级 + SKU 范围；录入方式随记录一起落库。">
            {promotions.length === 0 ? (
              <EmptyState
                icon={<Sparkles className="h-5 w-5" />}
                title="还没有登记促销"
                description="没有促销时预测的促销因子是 1（这是「没有登记」，不是「确认没有促销」）。"
              />
            ) : (
              <div className="space-y-2">
                {promotions.map((item) => (
                  <div
                    key={item.id}
                    className="border-border flex items-center justify-between gap-3 rounded-md border px-3 py-2"
                  >
                    <div className="min-w-0">
                      <div className="text-copy-14 text-foreground font-medium">
                        {item.name}
                        <span className="text-muted-foreground text-copy-12 ml-2">
                          {item.scopeType === 'ALL' ? '租户级' : item.scopeValue}
                        </span>
                      </div>
                      <div className="text-muted-foreground text-copy-12">
                        {item.startsAt} ~ {item.endsAt} · 折扣{' '}
                        {item.discountRate === null ? '未标' : formatRate(item.discountRate)} ·
                        预期提升 {formatRate(item.expectedLift)} · 录入方式{' '}
                        {item.source === 'IMPORT' ? '批量导入' : '人工录入'}
                      </div>
                    </div>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busy}
                      onClick={() => void act(() => deletePromotion(item.id), '促销已删除。')}
                    >
                      删除
                    </Button>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>
        </div>
      )}

      {!loading && tab === 'history' && (
        <div className="space-y-4">
          <SectionCard
            title="录入需求历史"
            description="逐 SKU 逐日销量。**同一 SKU 同一天只允许一条** —— 重复计数会直接把基线抬高，预测偏高就会补多货。"
            extra={
              <Button
                size="sm"
                disabled={busy}
                onClick={() =>
                  void act(async () => {
                    // 🔴 上游读不到时后端如实报 503 且**一个数据都不写** ——
                    //    这里**不**做本地兜底（那等于伪造「已经同步过了」）。
                    const result = await syncHistory();
                    setNotice(result.note);
                  }, '已从 13 分析同步（结果见下方列表的来源列）。')
                }
              >
                从 13 分析同步
              </Button>
            }
          >
            <div className="grid gap-3 sm:grid-cols-3 lg:grid-cols-6">
              <Input
                placeholder="SKU 码"
                value={historyForm.skuCode}
                onChange={(event) =>
                  setHistoryForm((form) => ({ ...form, skuCode: event.target.value }))
                }
              />
              <Input
                placeholder="SKU 名称"
                value={historyForm.skuName}
                onChange={(event) =>
                  setHistoryForm((form) => ({ ...form, skuName: event.target.value }))
                }
              />
              <Input
                type="date"
                value={historyForm.businessDate}
                onChange={(event) =>
                  setHistoryForm((form) => ({ ...form, businessDate: event.target.value }))
                }
              />
              <Input
                placeholder="销量（件）"
                value={historyForm.units}
                onChange={(event) =>
                  setHistoryForm((form) => ({ ...form, units: event.target.value }))
                }
              />
              <Input
                placeholder="单价（可空）"
                value={historyForm.unitPrice}
                onChange={(event) =>
                  setHistoryForm((form) => ({ ...form, unitPrice: event.target.value }))
                }
              />
              <Input
                placeholder="币种（USD）"
                value={historyForm.currency}
                onChange={(event) =>
                  setHistoryForm((form) => ({ ...form, currency: event.target.value }))
                }
              />
            </div>
            <div className="mt-3 flex items-center gap-2">
              <Button
                disabled={busy || !historyForm.skuCode || !historyForm.businessDate}
                onClick={() => void onSaveHistory()}
              >
                录入
              </Button>
              <span className="text-muted-foreground text-copy-12">
                上游同步来的销量**不带价格**（13 分析的商品销量口径没有价格）⇒
                价格弹性需要人工录单价。
              </span>
            </div>
          </SectionCard>

          <SectionCard
            title="需求历史台账"
            description="每行都带来源（人工录入 / 13 分析同步），来源可回查。"
            extra={
              <Input
                className="w-48"
                placeholder="按 SKU 码筛选"
                value={historySku}
                onChange={(event) => setHistorySku(event.target.value)}
              />
            }
          >
            {history.length === 0 ? (
              <EmptyState
                icon={<Sparkles className="h-5 w-5" />}
                title="还没有需求历史"
                description="预测需要逐日销量作为输入：人工录入，或从 13 分析同步。"
              />
            ) : (
              <div className="space-y-1">
                {history.map((item) => (
                  <div
                    key={item.id}
                    className="border-border flex items-center justify-between gap-3 rounded-md border px-3 py-2"
                  >
                    <div className="text-copy-13 text-foreground min-w-0">
                      {item.skuName || item.skuCode}
                      <span className="text-muted-foreground text-copy-12 ml-2">
                        {item.skuCode} · {item.businessDate} · {formatUnits(item.units)} 件
                        {item.unitPrice
                          ? ` · 单价 ${formatUnits(item.unitPrice)} ${item.currency ?? ''}`
                          : ''}
                      </span>
                    </div>
                    <div className="flex items-center gap-2">
                      <Badge tone="neutral">{historySourceLabel(item.source)}</Badge>
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busy}
                        onClick={() => void act(() => deleteHistory(item.id), '已删除。')}
                      >
                        删除
                      </Button>
                    </div>
                  </div>
                ))}
                <div className="text-muted-foreground text-copy-12">共 {historyTotal} 行</div>
              </div>
            )}
          </SectionCard>
        </div>
      )}

      {!loading && tab === 'insights' && (
        <div className="space-y-4">
          <SectionCard
            title="爆品识别"
            description="判定口径（阈值 + 统计窗口）随结果一起给出 —— 不是黑盒标签。没有基期（上一个等长窗口没有记录）时**不算爆品**。"
          >
            {hotItems.length === 0 ? (
              <EmptyState
                icon={<Sparkles className="h-5 w-5" />}
                title="还没有可判定的销量"
                description="爆品判定基于统计窗口内的真实销量；台账为空时没有可判定项。"
              />
            ) : (
              <div className="space-y-2">
                {hotItemRows(hotItems).map((item) => (
                  <div key={item.skuCode} className="border-border rounded-md border px-3 py-2">
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-copy-13 text-foreground font-medium">
                        {item.skuName || item.skuCode}
                      </span>
                      <Badge tone={item.detected ? 'success' : 'neutral'}>
                        {item.detected ? '爆品' : '未判成'}
                      </Badge>
                    </div>
                    <div className="text-muted-foreground text-copy-12">
                      窗口销量 {item.units} 件 · 增长率 {item.growth} · {item.reason}
                    </div>
                    <div className="text-muted-foreground text-copy-12 mt-1">{item.basis}</div>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          <SectionCard
            title="机会品发现"
            description="近期动销明显上升且基线达标；预测与库存缺口是**参考信息**。"
          >
            {opportunities.length === 0 ? (
              <EmptyState
                icon={<Sparkles className="h-5 w-5" />}
                title="还没有可判定的销量"
                description="机会品判定基于近 7 天与前 7 天的日均销量对比。"
              />
            ) : (
              <div className="space-y-2">
                {opportunityRows(opportunities).map((item) => (
                  <div key={item.skuCode} className="border-border rounded-md border px-3 py-2">
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-copy-13 text-foreground font-medium">
                        {item.skuName || item.skuCode}
                      </span>
                      <Badge tone={item.detected ? 'success' : 'neutral'}>
                        {item.detected ? '机会品' : '未判成'}
                      </Badge>
                    </div>
                    <div className="text-muted-foreground text-copy-12">
                      趋势 {item.trend} · 预测期需求 {item.forecast} 件 · 缺口 {item.gap} 件 ·{' '}
                      {item.reason}
                    </div>
                    <div className="text-muted-foreground text-copy-12 mt-1">{item.basis}</div>
                  </div>
                ))}
              </div>
            )}
            {insightNote && (
              <div className="text-muted-foreground text-copy-12 mt-3">{insightNote}</div>
            )}
          </SectionCard>

          <SectionCard
            title="价格弹性"
            description="弹性 = 相邻两日 Δln(销量) / Δln(单价) 的中位数。**算不出来就是算不出来** —— 页面会写明差在哪，不显示成「弹性 0」。"
          >
            {elasticity.length === 0 ? (
              <EmptyState
                icon={<Sparkles className="h-5 w-5" />}
                title="还没有可估计的价格样本"
                description="上游的销量口径不带价格；人工录入单价并出现至少两次价格变化后才能估出弹性。"
              />
            ) : (
              <div className="space-y-2">
                {elasticityRows(elasticity).map((item) => (
                  <div key={item.skuCode} className="border-border rounded-md border px-3 py-2">
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-copy-13 text-foreground font-medium">
                        {item.skuName || item.skuCode}
                      </span>
                      <Badge tone={BADGE_TONE[item.tone]}>{item.value}</Badge>
                    </div>
                    <div className="text-muted-foreground text-copy-12">
                      {item.reason || item.basis}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>
        </div>
      )}

      {!loading && tab === 'capabilities' && capabilities && (
        <div className="space-y-4">
          <SectionCard
            title="能力声明"
            description="逐条说清：有没有、依据是什么、缺了会怎样。没接通的那几项写明**归谁**。"
          >
            <div className="space-y-2">
              {capabilities.capabilities.map((item) => (
                <div key={item.name} className="border-border rounded-md border px-3 py-2">
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-copy-13 text-foreground font-medium">{item.name}</span>
                    <Badge tone={item.available ? 'success' : 'warning'}>
                      {item.available ? '已交付' : `未接通 · 归 ${item.ownerModule}`}
                    </Badge>
                  </div>
                  <div className="text-muted-foreground text-copy-12 mt-1">{item.evidence}</div>
                  {item.note && (
                    <div className="text-muted-foreground text-copy-12 mt-1">{item.note}</div>
                  )}
                </div>
              ))}
            </div>
          </SectionCard>

          <SectionCard
            title="算法口径"
            description="预测用的算法、参数与区间怎么来的 —— 页面照着显示，不自己编。"
          >
            <div className="space-y-2">
              {capabilities.algorithms.map((item) => (
                <div key={item.code} className="border-border rounded-md border px-3 py-2">
                  <div className="text-copy-13 text-foreground font-medium">
                    {item.name}（{item.code}）
                  </div>
                  <div className="text-muted-foreground text-copy-12 mt-1">{item.description}</div>
                  <div className="text-muted-foreground text-copy-12 mt-1">
                    {item.params
                      .map((param) => `${param.name} = ${param.value}（${param.description}）`)
                      .join('；')}
                  </div>
                </div>
              ))}
            </div>
          </SectionCard>

          <SectionCard title="判定规则" description="统计口径与边界规则。">
            <div className="space-y-1">
              {capabilities.rules.map((item) => (
                <div key={item.code} className="text-copy-13 text-muted-foreground">
                  <span className="text-foreground font-medium">{item.code}</span>：{item.note}
                </div>
              ))}
            </div>
          </SectionCard>

          <SectionCard title="阈值" description="爆品 / 机会品 / 调价建议的判定阈值。">
            <div className="grid gap-2 sm:grid-cols-2">
              {capabilities.thresholds.map((item) => (
                <div key={item.code} className="border-border rounded-md border px-3 py-2">
                  <div className="text-copy-13 text-foreground font-medium">
                    {item.name}：{item.value} {item.unit}
                  </div>
                  <div className="text-muted-foreground text-copy-12">{item.description}</div>
                </div>
              ))}
            </div>
          </SectionCard>

          <SectionCard
            title="数据来源"
            description="上游接的是谁、读的是哪个授权端点、读不到时归谁。"
          >
            <div className="space-y-2">
              {capabilities.sources.map((item) => (
                <div key={item.code} className="border-border rounded-md border px-3 py-2">
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-copy-13 text-foreground font-medium">{item.name}</span>
                    <Badge tone={item.connected ? 'success' : 'warning'}>
                      {item.connected ? '已接入' : `未接通 · 归 ${item.ownerModule}`}
                    </Badge>
                  </div>
                  <div className="text-muted-foreground text-copy-12 mt-1">
                    {item.service} · {item.endpoint}
                  </div>
                  {item.note && (
                    <div className="text-muted-foreground text-copy-12 mt-1">{item.note}</div>
                  )}
                </div>
              ))}
            </div>
          </SectionCard>

          <SectionCard title="还没交付 / 下游边界" description="本模块**不做**的事，写清楚归谁。">
            <div className="space-y-2">
              {capabilities.gaps.map((item) => (
                <div key={item.moduleId} className="border-border rounded-md border px-3 py-2">
                  <div className="text-copy-13 text-foreground font-medium">
                    {item.moduleId} {item.name}（{item.service}）
                  </div>
                  <div className="text-muted-foreground text-copy-12 mt-1">{item.note}</div>
                </div>
              ))}
            </div>
          </SectionCard>
        </div>
      )}
    </div>
  );
}
