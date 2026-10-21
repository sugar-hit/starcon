'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';

import {
  EMPTY_ANOMALY_FILTERS,
  EMPTY_OBSERVATION_FILTERS,
  acknowledgePriceAnomaly,
  anomalyTone,
  anomalyTypeLabel,
  changeSourceLabel,
  changeSourceTone,
  costLineText,
  dayText,
  durationText,
  evidenceText,
  fetchPriceAnomalyCapabilities,
  fetchPriceAnomalyEvent,
  fetchPriceAnomalyEvents,
  fetchPriceAnomalyNotifications,
  fetchPriceAnomalyObservations,
  moneyWithCurrency,
  ratioText,
  registerPriceObservation,
  resolvePriceAnomaly,
  runPriceAnomalyDetection,
  severityTone,
  sitePairText,
  statusTone,
  timeText,
  validateObservation,
} from './price-anomaly-model';
import type {
  AnomalyFilters,
  DetectionRun,
  EventDetail,
  EventListPage,
  EventRow,
  NotificationListPage,
  ObservationFilters,
  ObservationInput,
  ObservationListPage,
  ObservationRow,
  PriceAnomalyCapabilities,
} from './price-anomaly-model';

/**
 * 5.5.2.1 异常告警工作区。
 *
 * <p>🔴 页面最上面那一块**不是提示语，是这一页的设计前提**：
 * 蓝图加粗句「**站点价差倒挂是跨境特有的风险**：德国站定价低于美国站会引发跨区窜货与合规问题」。
 *
 * <p>所以本页把三件事摆在最显眼的位置：
 *
 * <ol>
 *   <li><b>四个输入各自的真值在哪</b> —— 平台价与内部价目前**靠登记观测进入**
 *       （channel 的 listing 明确「无价格列：售价归 05 定价」，4.3.2.2 只回流状态、不回流价格），
 *       成本线来自 5.2.1.1 的成本事实，站点价差规则是可配置阈值。
 *       把「读不到」与「没有」分开，是这一页最容易骗人的地方。
 *   <li><b>四类异常各自的判定口径</b> —— 尤其「长期不一致」要写明**持续多久**才报，
 *       以及「平台自动降价」要写明**我们自己改的不算**。
 *   <li><b>站点两两比对的结果</b> —— 每一行都写「A 低于 B」，方向一眼可见。
 * </ol>
 *
 * <p>🔴 **null 不渲染成 0**：成本线读不到显示「未维护成本构成（不参与判定）」，
 * 没登记的内部价显示「未登记」而不是 0.00。
 *
 * <p>🔴 **「长期」与「发生过」在界面上分得开**：①②④ 是当前状态（不再命中就自动消解），
 * ③ 平台自动降价是发生过的事（不会因窗口移走而消失）。
 */
export function PriceAnomalyWorkspace() {
  const [capabilities, setCapabilities] = useState<PriceAnomalyCapabilities | null>(null);
  const [events, setEvents] = useState<EventListPage | null>(null);
  const [filters, setFilters] = useState<AnomalyFilters>(EMPTY_ANOMALY_FILTERS);
  const [detail, setDetail] = useState<EventDetail | null>(null);
  const [selectedEventId, setSelectedEventId] = useState<string | null>(null);
  const [observations, setObservations] = useState<ObservationListPage | null>(null);
  const [observationFilters, setObservationFilters] = useState<ObservationFilters>(
    EMPTY_OBSERVATION_FILTERS,
  );
  const [notifications, setNotifications] = useState<NotificationListPage | null>(null);
  const [detection, setDetection] = useState<DetectionRun | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);

  // 登记观测的表单
  const [observationForm, setObservationForm] = useState<ObservationInput>({
    skuId: '',
    platformCode: 'AMZN',
    siteCode: 'US',
    currency: 'USD',
    internalPrice: null,
    platformPrice: null,
    priceChangeSource: 'UNKNOWN',
    source: 'MANUAL_ATTESTATION',
    comparisonCurrency: null,
    fxRate: null,
    fxRateDate: null,
    fxRateSource: null,
    observedAt: nowLocal(),
    note: '',
  });
  const [observationProblems, setObservationProblems] = useState<string[]>([]);

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const [capabilitiesBody, eventPage, observationPage, notificationPage] = await Promise.all([
        fetchPriceAnomalyCapabilities(),
        fetchPriceAnomalyEvents(filters),
        fetchPriceAnomalyObservations(observationFilters),
        fetchPriceAnomalyNotifications(filters),
      ]);
      setCapabilities(capabilitiesBody);
      setEvents(eventPage);
      setObservations(observationPage);
      setNotifications(notificationPage);
      setLoaded(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : '异常告警读不到');
    } finally {
      setBusy(false);
    }
  }, [filters, observationFilters]);

  useEffect(() => {
    void load();
  }, [load]);

  const loadDetail = useCallback(async (eventId: string) => {
    setSelectedEventId(eventId);
    try {
      setDetail(await fetchPriceAnomalyEvent(eventId));
    } catch (e) {
      setDetail(null);
      setError(e instanceof Error ? e.message : '异常详情读不到');
    }
  }, []);

  async function submitObservation() {
    const problems = validateObservation(observationForm);
    setObservationProblems(problems);
    if (problems.length) return;
    setBusy(true);
    setError('');
    try {
      const saved = await registerPriceObservation(observationForm);
      setNotice(
        `已登记观测（${saved.platformCode}/${saved.siteCode}）—— 观测是只追加的，历史不会被覆盖。`,
      );
      setObservationForm({ ...observationForm, internalPrice: null, platformPrice: null, note: '' });
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '观测登记失败');
    } finally {
      setBusy(false);
    }
  }

  async function runDetection() {
    setBusy(true);
    setError('');
    try {
      const run = await runPriceAnomalyDetection(filters.windowFrom || null, filters.windowTo || null);
      setDetection(run);
      setNotice(
        `检测完成：扫描 ${run.observationsScanned} 条观测，新开 ${run.eventsOpened} 条、更新 ${run.eventsUpdated} 条、消解 ${run.eventsResolved} 条，发通知 ${run.notificationsRaised} 条。`,
      );
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '检测失败');
    } finally {
      setBusy(false);
    }
  }

  async function changeStatus(eventId: string, action: 'acknowledge' | 'resolve') {
    setBusy(true);
    setError('');
    try {
      if (action === 'acknowledge') await acknowledgePriceAnomaly(eventId);
      else await resolvePriceAnomaly(eventId);
      setNotice(action === 'acknowledge' ? '已确认该异常。' : '已消解该异常。');
      await loadDetail(eventId);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '状态变更失败');
    } finally {
      setBusy(false);
    }
  }

  const openCount = useMemo(
    () => (events?.items ?? []).filter((e) => e.status === 'OPEN').length,
    [events],
  );
  const crossSiteCount = useMemo(
    () => (events?.items ?? []).filter((e) => e.anomalyType === 'CROSS_SITE_INVERSION').length,
    [events],
  );
  const belowCostCount = useMemo(
    () => (events?.items ?? []).filter((e) => e.anomalyType === 'BELOW_COST').length,
    [events],
  );
  const uncomparableCount = detection?.uncomparablePairs.length ?? 0;

  return (
    <div className="space-y-4">
      <PageHeader
        title="价格异常告警"
        description="监测四类价格异常：平台价与内部价长期不一致、售价低于成本线、平台自动降价、多站点价格倒挂（同商品不同站点价差导致窜货）。"
      />

      {/* ============================================================ 能力声明 */}
      <SectionCard
        title="这一页在看什么（必看）"
        description="四个输入各自的真值、能不能取到、归谁管；四类异常各自的判定口径。"
      >
        {capabilities ? (
          <div className="space-y-4">
            <Notice tone="warning" title="四个输入的真实来源">
              <div className="space-y-2">
                {capabilities.inputs.map((input) => (
                  <p key={input.code}>
                    <span className="font-semibold">{input.label}</span>：{input.impact}
                  </p>
                ))}
              </div>
            </Notice>

            <div>
              <p className="text-label-13 mb-2 font-semibold">四类异常的判定口径</p>
              <div className="grid gap-2 md:grid-cols-2">
                {capabilities.anomalyTypes.map((type) => (
                  <div key={type.code} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={anomalyTone(type.code)}>{type.label}</Badge>
                      <span className="text-copy-12 text-muted-foreground">{type.thresholdNote}</span>
                    </div>
                    <p className="text-copy-12 text-muted-foreground mt-2">{type.basis}</p>
                    {type.note ? (
                      <p className="text-copy-12 text-muted-foreground mt-1">{type.note}</p>
                    ) : null}
                  </div>
                ))}
              </div>
            </div>

            <div className="grid gap-2 md:grid-cols-2">
              <Notice tone="info" title="上游真值">
                {capabilities.upstreamNote}
              </Notice>
              <Notice tone="info" title="下游与读法">
                {capabilities.downstreamNote}
              </Notice>
              <Notice tone="info" title="落库口径">
                {capabilities.sourceNote}
              </Notice>
              <Notice tone="info" title="可做的动作">
                {capabilities.actionsNote}
              </Notice>
            </div>
          </div>
        ) : busy && !loaded ? (
          <p className="text-muted-foreground text-copy-13">正在读能力声明……</p>
        ) : (
          <p className="text-muted-foreground text-copy-13">能力声明读不到。</p>
        )}
      </SectionCard>

      {/* ============================================================ 概览 */}
      <div className="grid gap-3 md:grid-cols-4">
        <StatCard label="待处理异常" value={openCount} unit="条" polarity="negative" />
        <StatCard label="站点倒挂" value={crossSiteCount} unit="条" polarity="negative" />
        <StatCard label="低于成本线" value={belowCostCount} unit="条" polarity="negative" />
        <StatCard
          label="不可比站点对"
          value={uncomparableCount}
          unit="组"
          polarity="neutral"
          comparisonLabel="本轮检测"
        />
      </div>

      {error ? (
        <Notice tone="danger" title="出错了">
          {error}
        </Notice>
      ) : null}
      {notice ? (
        <Notice tone="success" title="已完成">
          {notice}
        </Notice>
      ) : null}

      {/* ============================================================ 筛选 + 检测 */}
      <SectionCard title="筛选与检测" description="检测是显式触发的扫描：事件用指纹做幂等键，重复检测不会堆重复事件。">
        <div className="grid gap-3 md:grid-cols-4">
          <FormField id="anomaly-kw" label="关键词" hint="SKU 编码 / 名称">
            <Input
              id="anomaly-kw"
              value={filters.keyword}
              placeholder="全部"
              onChange={(e) => setFilters({ ...filters, keyword: e.target.value, page: 0 })}
            />
          </FormField>
          <FormField id="anomaly-type" label="异常类型">
            <Input
              id="anomaly-type"
              value={filters.anomalyType}
              placeholder="全部"
              onChange={(e) => setFilters({ ...filters, anomalyType: e.target.value, page: 0 })}
            />
          </FormField>
          <FormField id="anomaly-status" label="状态">
            <Input
              id="anomaly-status"
              value={filters.status}
              placeholder="OPEN / ACKNOWLEDGED / RESOLVED"
              onChange={(e) => setFilters({ ...filters, status: e.target.value, page: 0 })}
            />
          </FormField>
          <FormField id="anomaly-site" label="站点码" hint="DE / US / FR …">
            <Input
              id="anomaly-site"
              value={filters.siteCode}
              placeholder="全部"
              onChange={(e) => setFilters({ ...filters, siteCode: e.target.value, page: 0 })}
            />
          </FormField>
          <FormField id="anomaly-from" label="窗口起（ISO 日期）" hint="留空 = 后端默认最近 90 天">
            <Input
              id="anomaly-from"
              value={filters.windowFrom}
              placeholder="2026-08-01"
              onChange={(e) => setFilters({ ...filters, windowFrom: e.target.value, page: 0 })}
            />
          </FormField>
          <FormField id="anomaly-to" label="窗口止（ISO 日期）">
            <Input
              id="anomaly-to"
              value={filters.windowTo}
              placeholder="2026-10-20"
              onChange={(e) => setFilters({ ...filters, windowTo: e.target.value, page: 0 })}
            />
          </FormField>
          <div className="flex items-end gap-2">
            <Button onClick={runDetection} disabled={busy}>
              运行检测
            </Button>
            <Button variant="outline" onClick={() => setFilters(EMPTY_ANOMALY_FILTERS)} disabled={busy}>
              重置
            </Button>
          </div>
        </div>
      </SectionCard>

      {/* ============================================================ 异常清单 */}
      <SectionCard
        title="异常清单"
        description="事件是有生命期的事实：第一次命中到最后一次命中的天数就是「持续了多久」；不再命中的会自动标成已消解。"
      >
        {events && events.items.length === 0 ? (
          <EmptyState
            title="当前没有价格异常"
            description="先在下面登记几条价格观测，再运行检测 —— 没有观测就没有可判定的事实。"
          />
        ) : (
          <div className="space-y-2">
            {(events?.items ?? []).map((event) => (
              <EventRowView
                key={event.id}
                event={event}
                selected={event.id === selectedEventId}
                onSelect={() => void loadDetail(event.id)}
              />
            ))}
            {events ? (
              <p className="text-copy-12 text-muted-foreground">
                共 {events.total} 条（第 {events.page + 1} 页，每页 {events.size} 条）。{events.note}
              </p>
            ) : null}
          </div>
        )}
      </SectionCard>

      {/* ============================================================ 异常详情 */}
      {selectedEventId ? (
        <SectionCard title="异常详情" description="判定依据、相关观测与预警通知。">
          {detail ? (
            <div className="space-y-4">
              <Notice tone="info" title="判定依据">
                {detail.basis}
              </Notice>
              <div className="grid gap-2 md:grid-cols-3">
                <div className="border-border rounded-md border p-3">
                  <p className="text-label-13 font-semibold">被比的价</p>
                  <p className="text-copy-13">
                    {moneyWithCurrency(detail.event.subjectPrice, detail.event.currency)}
                  </p>
                </div>
                <div className="border-border rounded-md border p-3">
                  <p className="text-label-13 font-semibold">参照的价</p>
                  <p className="text-copy-13">
                    {moneyWithCurrency(detail.event.referencePrice, detail.event.currency)}
                  </p>
                </div>
                <div className="border-border rounded-md border p-3">
                  <p className="text-label-13 font-semibold">成本线</p>
                  <p className="text-copy-13">{costLineText(detail.event)}</p>
                </div>
              </div>
              <div>
                <p className="text-label-13 mb-1 font-semibold">判定证据</p>
                <p className="text-copy-12 text-muted-foreground">
                  {evidenceText(detail.event.evidence) || '（无）'}
                </p>
              </div>
              <div className="flex gap-2">
                <Button
                  onClick={() => void changeStatus(detail.event.id, 'acknowledge')}
                  disabled={busy || detail.event.status !== 'OPEN'}
                >
                  确认
                </Button>
                <Button
                  variant="outline"
                  onClick={() => void changeStatus(detail.event.id, 'resolve')}
                  disabled={busy || detail.event.status === 'RESOLVED'}
                >
                  消解
                </Button>
              </div>

              <div>
                <p className="text-label-13 mb-1 font-semibold">相关观测</p>
                {detail.observations.length === 0 ? (
                  <p className="text-copy-12 text-muted-foreground">这条异常名下没有观测。</p>
                ) : (
                  <ObservationTable rows={detail.observations} />
                )}
              </div>

              <div>
                <p className="text-label-13 mb-1 font-semibold">预警通知</p>
                {detail.notifications.length === 0 ? (
                  <p className="text-copy-12 text-muted-foreground">还没有通知。</p>
                ) : (
                  <div className="space-y-2">
                    {detail.notifications.map((item) => (
                      <div key={item.id} className="border-border rounded-md border p-3">
                        <div className="flex flex-wrap items-center gap-2">
                          <Badge tone={severityTone(item.severity)}>{item.severityLabel}</Badge>
                          <span className="text-label-13 font-semibold">{item.title}</span>
                          <Badge tone="neutral">{item.ownerModule}</Badge>
                          <Badge tone={item.delivered ? 'success' : 'warning'}>
                            {item.delivered ? '已投递' : '未投递'}
                          </Badge>
                        </div>
                        <p className="text-copy-12 text-muted-foreground mt-1">
                          {item.body}（{timeText(item.createdAt)}）
                        </p>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          ) : (
            <p className="text-muted-foreground text-copy-13">详情读不到。</p>
          )}
        </SectionCard>
      ) : null}

      {/* ============================================================ 观测台账 */}
      <SectionCard
        title="价格观测台账"
        description="一行 = 一个 SKU 在一个站点上的一次价格观测。只追加：改价不改历史，「持续了几天」只有时间序列答得出来。"
      >
        <div className="grid gap-3 md:grid-cols-4">
          <FormField id="obs-kw" label="关键词" hint="SKU 编码 / 名称">
            <Input
              id="obs-kw"
              value={observationFilters.keyword}
              placeholder="全部"
              onChange={(e) =>
                setObservationFilters({ ...observationFilters, keyword: e.target.value, page: 0 })
              }
            />
          </FormField>
          <FormField id="obs-platform" label="平台码">
            <Input
              id="obs-platform"
              value={observationFilters.platformCode}
              placeholder="全部"
              onChange={(e) =>
                setObservationFilters({ ...observationFilters, platformCode: e.target.value, page: 0 })
              }
            />
          </FormField>
          <FormField id="obs-site" label="站点码">
            <Input
              id="obs-site"
              value={observationFilters.siteCode}
              placeholder="全部"
              onChange={(e) =>
                setObservationFilters({ ...observationFilters, siteCode: e.target.value, page: 0 })
              }
            />
          </FormField>
          <FormField id="obs-source" label="变动来源">
            <Input
              id="obs-source"
              value={observationFilters.priceChangeSource}
              placeholder="AUTOMATIC_PLATFORM / MANUAL_INTERNAL …"
              onChange={(e) =>
                setObservationFilters({
                  ...observationFilters,
                  priceChangeSource: e.target.value,
                  page: 0,
                })
              }
            />
          </FormField>
        </div>

        {observations && observations.items.length === 0 ? (
          <EmptyState
            title="还没有价格观测"
            description="价格观测是这一页的输入：平台价与内部价目前靠登记进入（channel 的 listing 明确「无价格列」，4.3.2.2 只回流状态）。"
          />
        ) : (
          <ObservationTable rows={observations?.items ?? []} />
        )}
      </SectionCard>

      {/* ============================================================ 登记观测 */}
      <SectionCard
        title="登记一次价格观测"
        description="平台价回流 / 内部价 / 人工补录的统一入口。观测是只追加的：同一 SKU、同一站点、同一时刻重复登记会被拒绝。"
      >
        <div className="grid gap-3 md:grid-cols-4">
          <FormField id="obs-sku" label="SKU ID" required error={observationProblems[0]}>
            <Input
              id="obs-sku"
              value={observationForm.skuId}
              placeholder="00000000-0000-0000-0000-000000000000"
              onChange={(e) => setObservationForm({ ...observationForm, skuId: e.target.value })}
            />
          </FormField>
          <FormField id="obs-new-platform" label="平台码" required>
            <Input
              id="obs-new-platform"
              value={observationForm.platformCode}
              onChange={(e) =>
                setObservationForm({ ...observationForm, platformCode: e.target.value.toUpperCase() })
              }
            />
          </FormField>
          <FormField id="obs-new-site" label="站点码" required>
            <Input
              id="obs-new-site"
              value={observationForm.siteCode}
              onChange={(e) =>
                setObservationForm({ ...observationForm, siteCode: e.target.value.toUpperCase() })
              }
            />
          </FormField>
          <FormField id="obs-new-currency" label="币种" required hint="站点自身币种（DE=EUR / US=USD）">
            <Input
              id="obs-new-currency"
              value={observationForm.currency}
              onChange={(e) =>
                setObservationForm({ ...observationForm, currency: e.target.value.toUpperCase() })
              }
            />
          </FormField>
          <FormField id="obs-new-internal" label="内部价" hint="可空 = 未登记（不是 0）">
            <Input
              id="obs-new-internal"
              value={observationForm.internalPrice ?? ''}
              onChange={(e) =>
                setObservationForm({ ...observationForm, internalPrice: e.target.value || null })
              }
            />
          </FormField>
          <FormField id="obs-new-platform-price" label="平台价" hint="可空 = 未登记">
            <Input
              id="obs-new-platform-price"
              value={observationForm.platformPrice ?? ''}
              onChange={(e) =>
                setObservationForm({ ...observationForm, platformPrice: e.target.value || null })
              }
            />
          </FormField>
          <FormField
            id="obs-new-change-source"
            label="这次价格是谁动的"
            hint="AUTOMATIC_PLATFORM / MANUAL_INTERNAL / PLATFORM_FEEDBACK / UNKNOWN"
          >
            <Input
              id="obs-new-change-source"
              value={observationForm.priceChangeSource}
              onChange={(e) =>
                setObservationForm({
                  ...observationForm,
                  priceChangeSource: e.target.value.toUpperCase(),
                })
              }
            />
          </FormField>
          <FormField id="obs-new-source" label="观测来源">
            <Input
              id="obs-new-source"
              value={observationForm.source}
              onChange={(e) =>
                setObservationForm({ ...observationForm, source: e.target.value.toUpperCase() })
              }
            />
          </FormField>
          <FormField id="obs-new-at" label="观测时刻" required hint="本地时间，提交时按 UTC 发送">
            <Input
              id="obs-new-at"
              value={observationForm.observedAt}
              onChange={(e) => setObservationForm({ ...observationForm, observedAt: e.target.value })}
            />
          </FormField>
          <FormField
            id="obs-new-cmp-currency"
            label="比价币种"
            hint="跨币种站点要比价时才填；给了就必须同时给汇率"
          >
            <Input
              id="obs-new-cmp-currency"
              value={observationForm.comparisonCurrency ?? ''}
              onChange={(e) =>
                setObservationForm({
                  ...observationForm,
                  comparisonCurrency: e.target.value.toUpperCase() || null,
                })
              }
            />
          </FormField>
          <FormField id="obs-new-fx" label="汇率" hint="含用哪天的汇率、哪来的汇率">
            <Input
              id="obs-new-fx"
              value={observationForm.fxRate ?? ''}
              onChange={(e) =>
                setObservationForm({ ...observationForm, fxRate: e.target.value || null })
              }
            />
          </FormField>
          <FormField id="obs-new-fx-date" label="汇率日期">
            <Input
              id="obs-new-fx-date"
              value={observationForm.fxRateDate ?? ''}
              onChange={(e) =>
                setObservationForm({ ...observationForm, fxRateDate: e.target.value || null })
              }
            />
          </FormField>
          <FormField id="obs-new-fx-source" label="汇率来源">
            <Input
              id="obs-new-fx-source"
              value={observationForm.fxRateSource ?? ''}
              onChange={(e) =>
                setObservationForm({ ...observationForm, fxRateSource: e.target.value || null })
              }
            />
          </FormField>
        </div>
        {observationProblems.length > 0 ? (
          <Notice tone="danger" title="这条观测还不能登记">
            <ul className="list-disc pl-4">
              {observationProblems.map((problem) => (
                <li key={problem}>{problem}</li>
              ))}
            </ul>
          </Notice>
        ) : null}
        <div className="mt-3">
          <Button onClick={submitObservation} disabled={busy}>
            登记观测
          </Button>
        </div>
      </SectionCard>

      {/* ============================================================ 检测小结 */}
      {detection ? (
        <SectionCard title="最近一次检测" description="逐类小结 + 不可比的站点对。">
          <div className="space-y-3">
            {detection.truncated ? (
              <Notice tone="warning" title="结果只覆盖了一部分">
                {detection.note}
              </Notice>
            ) : null}
            <div className="space-y-2">
              {detection.typeSummary.map((item) => (
                <div key={item.anomalyType} className="border-border rounded-md border p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={anomalyTone(item.anomalyType)}>{item.anomalyTypeLabel}</Badge>
                    <span className="text-copy-12 text-muted-foreground">
                      评估 {item.evaluated} 项，命中 {item.detected} 项
                    </span>
                  </div>
                  <p className="text-copy-12 text-muted-foreground mt-1">{item.note}</p>
                </div>
              ))}
            </div>
            {detection.uncomparablePairs.length > 0 ? (
              <div>
                <p className="text-label-13 mb-1 font-semibold">不可比的站点对（没有汇率，不编汇率）</p>
                <div className="space-y-2">
                  {detection.uncomparablePairs.map((pair, index) => (
                    <div key={`${pair.skuId}-${index}`} className="border-border rounded-md border p-3">
                      <p className="text-copy-13">
                        {pair.platformCode}/{pair.siteCode}（{pair.currency}） vs{' '}
                        {pair.relatedPlatformCode}/{pair.relatedSiteCode}（{pair.relatedCurrency}）
                      </p>
                      <p className="text-copy-12 text-muted-foreground mt-1">{pair.reason}</p>
                    </div>
                  ))}
                </div>
              </div>
            ) : null}
          </div>
        </SectionCard>
      ) : null}

      {/* ============================================================ 预警通知 */}
      <SectionCard
        title="预警通知"
        description="下游 1 工作台预警未交付 ⇒ 通知只落在本服务内，尚未投递到任何通道。"
      >
        {notifications && notifications.items.length === 0 ? (
          <EmptyState title="还没有预警通知" description="运行一次检测后，新命中的异常会各发一条通知。" />
        ) : (
          <div className="space-y-2">
            {(notifications?.items ?? []).map((item) => (
              <div key={item.id} className="border-border rounded-md border p-3">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={severityTone(item.severity)}>{item.severityLabel}</Badge>
                  <span className="text-label-13 font-semibold">{item.title}</span>
                  <Badge tone={statusTone(item.anomalyType === '' ? '' : 'OPEN')}>{item.anomalyTypeLabel}</Badge>
                  <Badge tone="neutral">{item.ownerModule}</Badge>
                  <Badge tone={item.delivered ? 'success' : 'warning'}>
                    {item.delivered ? '已投递' : '未投递'}
                  </Badge>
                </div>
                <p className="text-copy-12 text-muted-foreground mt-1">
                  {item.body}（{timeText(item.createdAt)}）
                </p>
              </div>
            ))}
          </div>
        )}
      </SectionCard>
    </div>
  );
}

/** 异常清单的一行。站点倒挂那一行**必须**写清「谁低于谁」。 */
function EventRowView({
  event,
  selected,
  onSelect,
}: {
  event: EventRow;
  selected: boolean;
  onSelect: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      className={`border-border w-full rounded-md border p-3 text-left ${
        selected ? 'border-brand' : ''
      }`}
    >
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={anomalyTone(event.anomalyType)}>{event.anomalyTypeLabel}</Badge>
        <Badge tone={severityTone(event.severity)}>{event.severityLabel}</Badge>
        <Badge tone={statusTone(event.status)}>{event.statusLabel}</Badge>
        <span className="text-label-13 font-semibold">
          {event.skuCode} {event.skuName}
        </span>
        {event.anomalyType === 'CROSS_SITE_INVERSION' ? (
          <span className="text-copy-13">🔴 {sitePairText(event)}</span>
        ) : (
          <span className="text-copy-12 text-muted-foreground">
            {event.platformCode}/{event.siteCode}
          </span>
        )}
      </div>
      <div className="text-copy-12 text-muted-foreground mt-1 flex flex-wrap gap-3">
        <span>被比的价 {moneyWithCurrency(event.subjectPrice, event.currency)}</span>
        <span>参照 {moneyWithCurrency(event.referencePrice, event.currency)}</span>
        {event.gapRatio !== null ? <span>价差 {ratioText(event.gapRatio)}</span> : null}
        <span>{durationText(event)}</span>
        {event.costLine !== null ? <span>成本线 {costLineText(event)}</span> : null}
        <span>命中 {dayText(event.firstSeenAt)} → {dayText(event.lastSeenAt)}</span>
      </div>
    </button>
  );
}

/** 观测台账表格。 */
function ObservationTable({ rows }: { rows: ObservationRow[] }) {
  if (rows.length === 0) return null;
  return (
    <div className="space-y-2">
      {rows.map((row) => (
        <div key={row.id} className="border-border rounded-md border p-3">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-label-13 font-semibold">
              {row.platformCode}/{row.siteCode}
            </span>
            <Badge tone={changeSourceTone(row.priceChangeSource)}>
              {changeSourceLabel(row.priceChangeSource)}
            </Badge>
            <span className="text-copy-12 text-muted-foreground">{row.sourceLabel}</span>
            <span className="text-copy-12 text-muted-foreground">
              {row.skuCode} {row.skuName}
            </span>
          </div>
          <div className="text-copy-12 text-muted-foreground mt-1 flex flex-wrap gap-3">
            <span>内部价 {moneyWithCurrency(row.internalPrice, row.currency)}</span>
            <span>平台价 {moneyWithCurrency(row.platformPrice, row.currency)}</span>
            {row.comparisonCurrency ? (
              <span>
                比价口径 {row.comparisonCurrency} @ {row.fxRate}（{row.fxRateDate} · {row.fxRateSource}）
              </span>
            ) : (
              <span>比价口径：只用同币种站点比</span>
            )}
            <span>{timeText(row.observedAt)}</span>
          </div>
        </div>
      ))}
    </div>
  );
}

/** 本地时刻 → ISO 串（秒精度）。 */
function nowLocal(): string {
  const now = new Date();
  const offset = now.getTimezoneOffset() * 60000;
  return new Date(now.getTime() - offset).toISOString().slice(0, 19);
}
