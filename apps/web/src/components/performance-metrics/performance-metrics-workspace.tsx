'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import {
  Activity,
  CircleAlert,
  Gauge,
  LineChart,
  Loader2,
  RefreshCw,
  ShieldAlert,
  Sparkles,
  TriangleAlert,
} from 'lucide-react';
import {
  FORECAST_RESULT_OPTIONS,
  SEVERITY_OPTIONS,
  breachLevelLabel,
  directionLabel,
  emptyState,
  emptyStateKind,
  evaluateAlerts,
  fetchAlertRules,
  fetchAlerts,
  fetchCapabilities,
  fetchForecasts,
  fetchShops,
  fetchTrends,
  forecastResultLabel,
  forecastResultTone,
  forecastShop,
  metricValueText,
  recordOwnMetric,
  ruleSourceLabel,
  saveAlertRule,
  severityLabel,
  severityTone,
  sourceLabel,
  thresholdKindLabel,
  type PerformanceMetricAlertPage,
  type PerformanceMetricAlertRule,
  type PerformanceMetricAlertRuleList,
  type PerformanceMetricCapabilities,
  type PerformanceMetricForecastPage,
  type PerformanceMetricSeries,
  type PerformanceMetricShopPage,
  type PerformanceMetricTrendPage,
} from './performance-metrics-model';

/**
 * 16.1.1.2 绩效指标监控 工作区。
 *
 * 蓝图：「监控各平台核心绩效指标的趋势与阈值：订单缺陷率、迟发率、取消率、有效追踪率、
 * 响应时间、账户评级，支持自定义阈值预警与趋势预测（是否会跌破阈值）。
 * 输入：指标时间序列；阈值配置；预测模型参数。
 * 输出：指标趋势图 + 阈值预警 + 跌破预测。
 * 关系：上游 = 16.1.1.1；下游 = 1 工作台预警、9 订单履约（迟发预警前置）。
 * **预测跌破比已跌破更有价值**：提前几天知道就能提前改。」
 *
 * 🔴 页面上的六条纪律：
 *
 * 1. **预测跌破比已跌破更有价值** —— 「会跌破阈值」排在最上面。
 * 2. **「量不到」显示成「未知」，不显示成 0.00%**；它是一个**第三种状态**，
 *    既不是「合格」也不是「0」。
 * 3. **量纲不混**：比值显示成 %、小时显示成「小时」、星级显示成「分」——
 *    绝不给「24 小时」显示成「2400.00%」。
 * 4. **区间口径不是瞬时点**：每个趋势点都带 `period_start ~ period_end`，
 *    并写明「这是区间口径」。
 * 5. **双来源如实表达**：每条序列都标注它的来源（上游 16.1.1.1 / 本模块自有）。
 * 6. **未交付的下游灰掉**：1 工作台预警、9 订单履约（迟发预警前置）当前**都未交付**，
 *    界面上如实标出，**不编**推送、**不编**履约联动。
 */
const PAGE_SIZE = 10;
const ALERT_PAGE_SIZE = 20;
const FORECAST_PAGE_SIZE = 20;

interface Props {
  /** 从别处跳进来时的定位（真实存在的页面参数）。 */
  focus?: { shopId?: string };
}

interface MetricForm {
  metricCode: string;
  metricValue: string;
  periodStart: string;
  periodEnd: string;
  note: string;
}

const EMPTY_FORM: MetricForm = {
  metricCode: 'RESPONSE_TIME',
  metricValue: '',
  periodStart: '',
  periodEnd: '',
  note: '',
};

interface RuleForm {
  direction: string;
  warnValue: string;
  criticalValue: string;
  note: string;
}

export function PerformanceMetricsWorkspace({ focus }: Props) {
  const [caps, setCaps] = useState<PerformanceMetricCapabilities | null>(null);
  const [shops, setShops] = useState<PerformanceMetricShopPage | null>(null);
  const [trends, setTrends] = useState<PerformanceMetricTrendPage | null>(null);
  const [alerts, setAlerts] = useState<PerformanceMetricAlertPage | null>(null);
  const [forecasts, setForecasts] = useState<PerformanceMetricForecastPage | null>(null);
  const [rules, setRules] = useState<PerformanceMetricAlertRuleList | null>(null);

  const [keyword, setKeyword] = useState('');
  const [platformCode, setPlatformCode] = useState('');
  const [metricCode, setMetricCode] = useState('');
  const [severity, setSeverity] = useState('');
  const [forecastResult, setForecastResult] = useState('');
  const [shopId, setShopId] = useState(focus?.shopId ?? '');
  const [page, setPage] = useState(0);
  const [alertPage, setAlertPage] = useState(0);
  const [forecastPage, setForecastPage] = useState(0);

  const [formOpen, setFormOpen] = useState(false);
  const [form, setForm] = useState<MetricForm>(EMPTY_FORM);
  const [ruleForm, setRuleForm] = useState<RuleForm | null>(null);
  const [editingRule, setEditingRule] = useState<PerformanceMetricAlertRule | null>(null);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadTrends = useCallback(
    async (target: number) => {
      const result = await fetchTrends(target, PAGE_SIZE, {
        keyword,
        platformCode,
        metricCode,
      });
      setTrends(result);
      return result;
    },
    [keyword, platformCode, metricCode],
  );

  const loadAlerts = useCallback(
    async (target: number) => {
      const result = await fetchAlerts(target, ALERT_PAGE_SIZE, { metricCode, severity });
      setAlerts(result);
      return result;
    },
    [metricCode, severity],
  );

  const loadForecasts = useCallback(
    async (target: number) => {
      const result = await fetchForecasts(target, FORECAST_PAGE_SIZE, {
        metricCode,
        result: forecastResult,
      });
      setForecasts(result);
      return result;
    },
    [metricCode, forecastResult],
  );

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, shopResult, , , , ruleResult] = await Promise.all([
        fetchCapabilities(),
        fetchShops(0, 100, { platformCode, keyword }),
        loadTrends(page),
        loadAlerts(alertPage),
        loadForecasts(forecastPage),
        fetchAlertRules(),
      ]);
      setCaps(capsResult);
      setShops(shopResult);
      setRules(ruleResult);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [loadTrends, loadAlerts, loadForecasts, page, alertPage, forecastPage, platformCode, keyword]);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const run = useCallback(async (work: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await work();
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败，请重试');
    } finally {
      setBusy(false);
    }
  }, []);

  const applyFilters = useCallback(
    (next: Partial<{ keyword: string; platformCode: string; metricCode: string }>) => {
      setKeyword(next.keyword ?? keyword);
      setPlatformCode(next.platformCode ?? platformCode);
      setMetricCode(next.metricCode ?? metricCode);
      setPage(0);
    },
    [keyword, platformCode, metricCode],
  );

  const doEvaluate = useCallback(
    () =>
      run(async () => {
        if (!shopId) return;
        await evaluateAlerts(shopId);
        await Promise.all([loadAlerts(alertPage), loadTrends(page)]);
      }),
    [run, shopId, loadAlerts, alertPage, loadTrends, page],
  );

  const doForecast = useCallback(
    () =>
      run(async () => {
        if (!shopId) return;
        await forecastShop(shopId, {});
        await Promise.all([loadForecasts(forecastPage), loadTrends(page)]);
      }),
    [run, shopId, loadForecasts, forecastPage, loadTrends, page],
  );

  const submitMetric = useCallback(
    () =>
      run(async () => {
        if (!shopId) return;
        const raw = form.metricValue.trim();
        await recordOwnMetric(shopId, {
          metricCode: form.metricCode,
          metricValue: raw === '' ? null : Number(raw),
          periodStart: form.periodStart,
          periodEnd: form.periodEnd,
          note: form.note,
        });
        setFormOpen(false);
        setForm(EMPTY_FORM);
        await Promise.all([loadTrends(page), fetchShops(0, 100, { platformCode, keyword })]);
      }),
    [run, shopId, form, loadTrends, page, platformCode, keyword],
  );

  const submitRule = useCallback(
    () =>
      run(async () => {
        if (!editingRule || !ruleForm) return;
        await saveAlertRule(editingRule.metricCode, {
          direction: ruleForm.direction,
          warnValue: Number(ruleForm.warnValue),
          criticalValue: Number(ruleForm.criticalValue),
          note: ruleForm.note,
        });
        setRules(await fetchAlertRules());
        setRuleForm(null);
        setEditingRule(null);
        await Promise.all([loadAlerts(alertPage), loadForecasts(forecastPage)]);
      }),
    [run, editingRule, ruleForm, loadAlerts, alertPage, loadForecasts, forecastPage],
  );

  const shopItems = useMemo(() => shops?.items ?? [], [shops]);
  const trendItems = useMemo(() => trends?.items ?? [], [trends]);
  const alertItems = useMemo(() => alerts?.items ?? [], [alerts]);
  const forecastItems = useMemo(() => forecasts?.items ?? [], [forecasts]);
  const selectedShop = useMemo(
    () => shopItems.find((item) => item.shopId === shopId) ?? null,
    [shopItems, shopId],
  );

  /** 🔴 空态三态：有店铺但一个指标都没量到 ≠ 筛选无结果 ≠ 没有店铺。 */
  const emptyKind = useMemo(() => {
    const totalShops = shops?.total ?? 0;
    const anySeries = shopItems.some((item) => item.seriesCount > 0);
    const filtered = keyword !== '' || platformCode !== '' || metricCode !== '';
    return emptyStateKind(totalShops, trends?.total ?? 0, anySeries, filtered);
  }, [shops, trends, shopItems, keyword, platformCode, metricCode]);

  if (loading) {
    return (
      <div className="text-muted-foreground flex items-center justify-center gap-2 py-16">
        <Loader2 className="size-4 animate-spin" />
        <span className="text-label-13">正在加载绩效指标…</span>
      </div>
    );
  }

  if (error && !caps) {
    return (
      <SectionCard title="绩效指标监控">
        <EmptyState
          icon={<TriangleAlert className="text-destructive size-8" />}
          title="绩效指标监控加载失败"
          description={error}
          action={
            <Button variant="secondary" onClick={() => void refresh()}>
              <RefreshCw className="mr-2 size-4" />
              重试
            </Button>
          }
        />
      </SectionCard>
    );
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="绩效指标监控"
        description="监控各平台核心绩效指标的趋势与阈值，支持自定义阈值预警与趋势预测。预测跌破比已跌破更有价值 —— 提前几天知道就能提前改。"
        actions={
          <Button variant="outline" onClick={() => void refresh()}>
            <RefreshCw className="mr-2 size-4" />
            刷新
          </Button>
        }
      />

      {error && (
        <SectionCard>
          <div className="text-destructive text-copy-13 flex items-center gap-2">
            <TriangleAlert className="size-4" />
            {error}
          </div>
        </SectionCard>
      )}

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="会跌破阈值（预测）"
          value={forecasts?.willBreachCount ?? 0}
          polarity="negative"
          icon={<Sparkles className="size-4" />}
        />
        <StatCard
          label="严重（命中严重阈值）"
          value={alerts?.criticalCount ?? 0}
          polarity="negative"
          icon={<ShieldAlert className="size-4" />}
        />
        <StatCard
          label="预警（命中警告阈值）"
          value={alerts?.warningCount ?? 0}
          polarity="negative"
          icon={<CircleAlert className="size-4" />}
        />
        <StatCard
          label="量不到（不算通过）"
          value={alerts?.unknownCount ?? 0}
          polarity="neutral"
          icon={<TriangleAlert className="size-4" />}
        />
      </div>

      {/* ---------- 能力声明 ---------- */}
      {caps && (
        <SectionCard
          title="能力声明"
          description="下面这些是**本模块之外**、当前尚未交付的能力。缺了它们，本模块仍然能算趋势、阈值预警与跌破预测 —— 但界面上不会拿编造的数据去填那些维度。"
        >
          <div className="space-y-3">
            {caps.gaps.map((gap) => (
              <div
                key={gap.moduleId}
                className="border-border flex flex-wrap items-start gap-2 rounded-md border px-3 py-2"
              >
                <Badge tone="neutral">未交付</Badge>
                <span className="text-copy-13 text-foreground">{gap.moduleName}</span>
                <span className="text-copy-12 text-muted-foreground">归 {gap.ownerModule}</span>
                <span className="text-copy-12 text-muted-foreground w-full">{gap.impact}</span>
              </div>
            ))}
          </div>

          <div className="mt-4 space-y-2">
            <div className="text-copy-13 text-foreground">六项指标的归属与来源</div>
            {caps.metricCatalog.map((descriptor) => (
              <div
                key={descriptor.metricCode}
                className="border-border flex flex-wrap items-center gap-2 rounded-md border px-3 py-2"
              >
                <span className="text-copy-13 text-foreground">{descriptor.metricLabel}</span>
                <Badge tone="info">{descriptor.unitLabel}</Badge>
                <Badge tone="neutral">{descriptor.directionLabel}</Badge>
                <span className="text-copy-12 text-muted-foreground">
                  归 {descriptor.ownerModule} · 数据来自 {descriptor.sourceModule}
                </span>
                <span className="text-copy-12 text-muted-foreground w-full">{descriptor.note}</span>
              </div>
            ))}
          </div>

          <div className="text-muted-foreground text-copy-12 mt-3 space-y-1">
            <p>{caps.sourceNote}</p>
            <p>{caps.granularitySemantics}</p>
            <p>{caps.unknownSemantics}</p>
            <p>{caps.thresholdSemantics}</p>
            <p>{caps.trendSemantics}</p>
            <p>{caps.forecastSemantics}</p>
          </div>
        </SectionCard>
      )}

      {/* ---------- 店铺范围与动作 ---------- */}
      <SectionCard
        title="监控范围"
        description="店铺清单来自**本服务自己的店铺目录**（真实存在，不是写死的常量）。选定店铺后可以跑一次阈值预警评估、跑一次跌破预测，或录入本模块自有的指标。"
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <Input
              className="h-9 w-40"
              placeholder="搜索店铺编码 / 名称"
              value={keyword}
              onChange={(e) => applyFilters({ keyword: e.target.value })}
            />
            <Input
              className="h-9 w-40"
              placeholder="平台编码（如 AMAZON）"
              value={platformCode}
              onChange={(e) => applyFilters({ platformCode: e.target.value })}
            />
          </div>
        }
      >
        {shopItems.length === 0 ? (
          <EmptyState
            icon={<Gauge className="size-8" />}
            title={emptyState(emptyKind).title}
            description={emptyState(emptyKind).description}
          />
        ) : (
          <div className="space-y-3">
            <div className="flex flex-wrap items-center gap-2">
              <select
                className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
                value={shopId}
                onChange={(e) => setShopId(e.target.value)}
              >
                <option value="">（未选定店铺）</option>
                {shopItems.map((shop) => (
                  <option key={shop.shopId} value={shop.shopId}>
                    {shop.shopName} · {shop.shopCode} · {shop.platformCode}
                  </option>
                ))}
              </select>
              <Button
                variant="secondary"
                size="sm"
                disabled={!shopId || busy}
                onClick={() => void doEvaluate()}
              >
                跑一次预警评估
              </Button>
              <Button
                variant="secondary"
                size="sm"
                disabled={!shopId || busy}
                onClick={() => void doForecast()}
              >
                跑一次跌破预测
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={!shopId || busy}
                onClick={() => {
                  setForm({
                    ...EMPTY_FORM,
                    periodStart: new Date().toISOString().slice(0, 8) + '01',
                    periodEnd: new Date().toISOString().slice(0, 10),
                  });
                  setFormOpen(true);
                }}
              >
                录入本模块自有指标
              </Button>
            </div>
            {selectedShop && (
              <div className="text-copy-12 text-muted-foreground">
                {selectedShop.shopName}：{selectedShop.seriesCount} 条指标序列 · 量到{' '}
                {selectedShop.knownPointCount} 个点 · 量不到 {selectedShop.unknownPointCount} 个点
              </div>
            )}
          </div>
        )}
      </SectionCard>

      {/* ---------- 趋势序列 ---------- */}
      <SectionCard
        title="指标趋势序列"
        description="每个点 = **一个区间的一个指标**（不是瞬时点）。均值与环比**只用量到的点**，分母写在下面；「量不到」显示成「未知」，绝不显示成 0。"
        extra={
          <select
            className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
            value={metricCode}
            onChange={(e) => applyFilters({ metricCode: e.target.value })}
          >
            <option value="">全部指标</option>
            {caps?.metricCatalog.map((descriptor) => (
              <option key={descriptor.metricCode} value={descriptor.metricCode}>
                {descriptor.metricLabel}
              </option>
            ))}
          </select>
        }
      >
        {trendItems.length === 0 ? (
          <EmptyState
            icon={<LineChart className="size-8" />}
            title={emptyState(emptyKind).title}
            description={emptyState(emptyKind).description}
          />
        ) : (
          <div className="space-y-3">
            {trendItems.map((series) => (
              <SeriesCard key={`${series.shopId}-${series.metricCode}`} series={series} />
            ))}
            <Pager
              page={trends?.page ?? 0}
              size={PAGE_SIZE}
              total={trends?.total ?? 0}
              onChange={(target) => {
                setPage(target);
                void loadTrends(target);
              }}
            />
          </div>
        )}
      </SectionCard>

      {/* ---------- 阈值预警 ---------- */}
      <SectionCard
        title="阈值预警"
        description="预警凭据是**追加式**的：里面带着**当时用到的阈值快照**，所以改阈值**不会追溯改变历史预警**。「量不到」也是一条预警（不算通过）。"
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={severity}
              onChange={(e) => {
                setSeverity(e.target.value);
                setAlertPage(0);
              }}
            >
              {SEVERITY_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </div>
        }
      >
        {alertItems.length === 0 ? (
          <EmptyState
            icon={<ShieldAlert className="size-8" />}
            title="没有命中的阈值预警"
            description="选定一家店、点「跑一次预警评估」，就会按生效阈值跑一遍并落一条**不可改写**的凭据。"
          />
        ) : (
          <div className="space-y-2">
            {alertItems.map((alert) => (
              <div
                key={alert.alertId}
                className="border-border flex flex-wrap items-start justify-between gap-2 rounded-md border px-3 py-2"
              >
                <div className="min-w-0">
                  <div className="text-copy-13 text-foreground flex flex-wrap items-center gap-2">
                    <Badge tone={severityTone(alert.severity)}>{alert.severityLabel}</Badge>
                    <span>{alert.metricLabel}</span>
                    <span className="text-muted-foreground">
                      {alert.shopName ?? alert.shopCode}
                    </span>
                  </div>
                  <div className="text-copy-12 text-muted-foreground">
                    {alert.periodStart} ~ {alert.periodEnd} · 实际 {alert.metricValueText} ·{' '}
                    {thresholdKindLabel(alert.thresholdKind)} {alert.thresholdValueText} ·{' '}
                    {directionLabel(alert.direction)} · {ruleSourceLabel(alert.ruleSource)} · 第{' '}
                    {alert.ruleVersion} 版
                  </div>
                  <div className="text-copy-12 text-muted-foreground">{alert.message}</div>
                </div>
              </div>
            ))}
            <Pager
              page={alerts?.page ?? 0}
              size={ALERT_PAGE_SIZE}
              total={alerts?.total ?? 0}
              onChange={(target) => {
                setAlertPage(target);
                void loadAlerts(target);
              }}
            />
          </div>
        )}
      </SectionCard>

      {/* ---------- 跌破预测 ---------- */}
      <SectionCard
        title="跌破预测"
        description="预测口径写在每一条里：用哪些周期、什么模型、第几个周期越线。**点数不足就说「无法预测」并给原因，绝不给一个假预测。**"
        extra={
          <select
            className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
            value={forecastResult}
            onChange={(e) => {
              setForecastResult(e.target.value);
              setForecastPage(0);
            }}
          >
            {FORECAST_RESULT_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        }
      >
        {forecastItems.length === 0 ? (
          <EmptyState
            icon={<Sparkles className="size-8" />}
            title="还没有跌破预测"
            description="选定一家店、点「跑一次跌破预测」，就会对六项指标各跑一遍并落一条**不可改写**的凭据。"
          />
        ) : (
          <div className="space-y-2">
            {forecastItems.map((forecast) => (
              <div
                key={forecast.forecastId}
                className="border-border flex flex-wrap items-start justify-between gap-2 rounded-md border px-3 py-2"
              >
                <div className="min-w-0">
                  <div className="text-copy-13 text-foreground flex flex-wrap items-center gap-2">
                    <Badge tone={forecastResultTone(forecast.result)}>{forecast.resultLabel}</Badge>
                    <span>{forecast.metricLabel}</span>
                    <span className="text-muted-foreground">
                      {forecast.shopName ?? forecast.shopCode}
                    </span>
                    {forecast.breachLevel && (
                      <Badge tone="warning">{breachLevelLabel(forecast.breachLevel)}</Badge>
                    )}
                  </div>
                  <div className="text-copy-12 text-muted-foreground">{forecast.resultReason}</div>
                  <div className="text-copy-12 text-muted-foreground">
                    口径：{forecast.methodNote}
                  </div>
                  <div className="text-copy-12 text-muted-foreground">
                    窗口 {forecast.windowPoints} 个周期 · 量到 {forecast.knownPoints} 个 · 量不到{' '}
                    {forecast.unknownPoints} 个 · {ruleSourceLabel(forecast.ruleSource)} · 第{' '}
                    {forecast.ruleVersion} 版
                    {forecast.breachInPeriods !== null &&
                      ` · 第 ${forecast.breachInPeriods} 个周期预计 ${forecast.predictedValueText}`}
                  </div>
                </div>
              </div>
            ))}
            <Pager
              page={forecasts?.page ?? 0}
              size={FORECAST_PAGE_SIZE}
              total={forecasts?.total ?? 0}
              onChange={(target) => {
                setForecastPage(target);
                void loadForecasts(target);
              }}
            />
          </div>
        )}
      </SectionCard>

      {/* ---------- 预警阈值配置 ---------- */}
      <SectionCard
        title="预警阈值"
        description={
          rules?.note ?? '阈值在服务端算，可按租户调整；改完推进规则版本，历史预警不受影响。'
        }
      >
        <div className="space-y-2">
          {(rules?.items ?? []).map((rule) => (
            <div
              key={rule.metricCode}
              className="border-border flex flex-wrap items-center justify-between gap-2 rounded-md border px-3 py-2"
            >
              <div className="min-w-0">
                <div className="text-copy-13 text-foreground">{rule.metricLabel}</div>
                <div className="text-copy-12 text-muted-foreground">
                  警告 {metricValueText(rule.warnValue, rule.unit)} · 严重{' '}
                  {metricValueText(rule.criticalValue, rule.unit)} · {rule.directionLabel} ·{' '}
                  {rule.ruleSourceLabel} · 第 {rule.ruleVersion} 版
                </div>
              </div>
              <Button
                variant="secondary"
                size="sm"
                onClick={() => {
                  setEditingRule(rule);
                  setRuleForm({
                    direction: rule.direction,
                    warnValue: String(rule.warnValue),
                    criticalValue: String(rule.criticalValue),
                    note: '',
                  });
                }}
              >
                调整
              </Button>
            </div>
          ))}
        </div>
      </SectionCard>

      {/* ---------- 录入自有指标弹窗 ---------- */}
      <Modal open={formOpen} onOpenChange={(open) => setFormOpen(open)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>录入本模块自有指标</ModalTitle>
            <ModalDescription>
              响应时间与账户评级是**本模块自有**的指标（上游 16.1.1.1
              的另外四项在账号健康总览里录入）。
              录入的条目一律标「人工录入」，**不会冒充平台同步**。留空数值 =
              显式登记「这个周期量不到」。
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            <label className="block space-y-1">
              <span className="text-copy-13 text-muted-foreground">指标</span>
              <select
                className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                value={form.metricCode}
                onChange={(e) => setForm({ ...form, metricCode: e.target.value })}
              >
                <option value="RESPONSE_TIME">响应时间（小时）</option>
                <option value="ACCOUNT_RATING">账户评级（0..5 星）</option>
              </select>
            </label>
            <label className="block space-y-1">
              <span className="text-copy-13 text-muted-foreground">
                数值（留空 = 显式登记「量不到」）
              </span>
              <Input
                value={form.metricValue}
                onChange={(e) => setForm({ ...form, metricValue: e.target.value })}
                placeholder={form.metricCode === 'RESPONSE_TIME' ? '24' : '4.2'}
              />
            </label>
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="block space-y-1">
                <span className="text-copy-13 text-muted-foreground">周期起点</span>
                <Input
                  type="date"
                  value={form.periodStart}
                  onChange={(e) => setForm({ ...form, periodStart: e.target.value })}
                />
              </label>
              <label className="block space-y-1">
                <span className="text-copy-13 text-muted-foreground">周期终点</span>
                <Input
                  type="date"
                  value={form.periodEnd}
                  onChange={(e) => setForm({ ...form, periodEnd: e.target.value })}
                />
              </label>
            </div>
            <label className="block space-y-1">
              <span className="text-copy-13 text-muted-foreground">备注</span>
              <Input
                value={form.note}
                onChange={(e) => setForm({ ...form, note: e.target.value })}
                placeholder="例如：从平台后台导出"
              />
            </label>
          </div>
          <ModalFooter>
            <Button variant="secondary" onClick={() => setFormOpen(false)}>
              取消
            </Button>
            <Button onClick={() => void submitMetric()}>录入</Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---------- 阈值调整弹窗 ---------- */}
      <Modal open={ruleForm !== null} onOpenChange={(open) => !open && setRuleForm(null)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>
              调整预警阈值{editingRule ? ` · ${editingRule.metricLabel}` : ''}
            </ModalTitle>
            <ModalDescription>
              阈值是**服务端**口径：改完推进规则版本，**历史预警不会被追溯改变**（凭据里带着当时用到的阈值快照）。
              {editingRule && ` 当前量纲：${editingRule.unitLabel}。`}
            </ModalDescription>
          </ModalHeader>
          {ruleForm && (
            <div className="space-y-3">
              <label className="block space-y-1">
                <span className="text-copy-13 text-muted-foreground">方向</span>
                <select
                  className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                  value={ruleForm.direction}
                  onChange={(e) => setRuleForm({ ...ruleForm, direction: e.target.value })}
                >
                  <option value="ABOVE_IS_BAD">高于阈值即危险</option>
                  <option value="BELOW_IS_BAD">低于阈值即危险</option>
                </select>
              </label>
              <div className="grid gap-3 sm:grid-cols-2">
                <label className="block space-y-1">
                  <span className="text-copy-13 text-muted-foreground">警告阈值</span>
                  <Input
                    value={ruleForm.warnValue}
                    onChange={(e) => setRuleForm({ ...ruleForm, warnValue: e.target.value })}
                  />
                </label>
                <label className="block space-y-1">
                  <span className="text-copy-13 text-muted-foreground">严重阈值</span>
                  <Input
                    value={ruleForm.criticalValue}
                    onChange={(e) => setRuleForm({ ...ruleForm, criticalValue: e.target.value })}
                  />
                </label>
              </div>
              <label className="block space-y-1">
                <span className="text-copy-13 text-muted-foreground">备注</span>
                <Input
                  value={ruleForm.note}
                  onChange={(e) => setRuleForm({ ...ruleForm, note: e.target.value })}
                  placeholder="为什么改这个阈值"
                />
              </label>
            </div>
          )}
          <ModalFooter>
            <Button variant="secondary" onClick={() => setRuleForm(null)}>
              取消
            </Button>
            <Button onClick={() => void submitRule()}>保存</Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {busy && (
        <div className="text-copy-12 text-muted-foreground flex items-center gap-2">
          <Loader2 className="size-3 animate-spin" />
          处理中…
        </div>
      )}
    </div>
  );
}

/** 一条趋势序列（区间口径 + 来源 + 分母说明）。 */
function SeriesCard({ series }: { series: PerformanceMetricSeries }) {
  return (
    <div className="border-border rounded-md border px-3 py-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="text-copy-13 text-foreground flex flex-wrap items-center gap-2">
          <span>{series.shopName}</span>
          <span className="text-muted-foreground">{series.shopCode}</span>
          <span className="text-muted-foreground">{series.platformCode}</span>
          <span>{series.metricLabel}</span>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone="info">来源：{series.sourceLabel}</Badge>
          <Badge tone="neutral">{series.unitLabel}</Badge>
          <Badge tone="neutral">区间口径</Badge>
        </div>
      </div>

      <div className="mt-2 grid gap-2 sm:grid-cols-2 xl:grid-cols-3">
        {series.points.map((point) => (
          <div
            key={`${point.periodStart}-${point.periodEnd}`}
            className="bg-muted/40 rounded-md px-2 py-1.5"
          >
            <div className="text-copy-12 text-muted-foreground">
              {point.periodStart} ~ {point.periodEnd}
            </div>
            <div className="flex items-center gap-2">
              <span className="text-copy-14 text-foreground">
                {metricValueText(point.value, series.unit)}
              </span>
              {!point.known && <Badge tone="neutral">量不到</Badge>}
              {point.source === 'MANUAL' && <Badge tone="info">人工录入</Badge>}
            </div>
          </div>
        ))}
      </div>

      <div className="text-copy-12 text-muted-foreground mt-2 space-y-0.5">
        <div>
          均值 {metricValueText(series.average, series.unit)} · {series.averageNote}
        </div>
        <div>
          环比{' '}
          {series.latestChange === null
            ? '未知'
            : metricValueText(series.latestChange, series.unit)}{' '}
          · {series.changeNote}
        </div>
      </div>
    </div>
  );
}

function Pager({
  page,
  size,
  total,
  onChange,
}: {
  page: number;
  size: number;
  total: number;
  onChange: (page: number) => void;
}) {
  const pages = Math.max(1, Math.ceil(total / size));
  if (total === 0) return null;
  return (
    <div className="text-copy-12 text-muted-foreground flex items-center justify-end gap-2">
      <span>
        第 {page + 1} / {pages} 页 · 共 {total} 条
      </span>
      <Button variant="secondary" size="sm" disabled={page <= 0} onClick={() => onChange(page - 1)}>
        上一页
      </Button>
      <Button
        variant="secondary"
        size="sm"
        disabled={page + 1 >= pages}
        onClick={() => onChange(page + 1)}
      >
        下一页
      </Button>
    </div>
  );
}
