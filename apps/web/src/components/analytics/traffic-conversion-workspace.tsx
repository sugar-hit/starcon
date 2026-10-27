'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, Segmented } from '@starcon/ui';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { api } from '@/lib/api';
import {
  METRIC_AD_CONVERSION_RATE,
  METRIC_CONVERSION_RATE,
  METRIC_IMPRESSIONS,
  METRIC_ORDER_AOV,
  amountText,
  availabilityLabel,
  availabilityTone,
  caliberLabel,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  diagnosisRuleLabel,
  diagnosisSeverityLabel,
  diagnosisTone,
  drilldownKindLabel,
  drilldownReferenceText,
  emptyReasonLabel,
  emptyText,
  funnelSourceNote,
  granularityOptions,
  keyMetricGroups,
  metricValueText,
  notDeliveredLines,
  pageState,
  percentText,
  quantityText,
  refreshStatusLabel,
  refreshStatusTone,
  ruleNotes,
  statusLabel,
  trendMetricOf,
  trafficSourceShortLabel,
  upstreamModules,
} from './traffic-conversion-model';
import type {
  TrafficConversionCapabilities,
  TrafficConversionResponse,
  TrafficDrilldownResponse,
  TrafficMetricDefinitionView,
  TrafficRefreshResult,
  TrafficTrendPoint,
} from './traffic-conversion-model';

/**
 * 13.3.2.1 流量转化工作区（卷 13「利润与经营分析」L3 13.3.2 流量与转化下的第一格）。
 *
 * <p>蓝图输出：「漏斗视图 + 转化率趋势 + 异常诊断。」
 *
 * <p><b>数据来自 reporting 服务（13 卷的唯一归属服务）的投影读模型</b>：
 *
 * <ul>
 *   <li>{@code GET reporting/traffic-conversion} —— 漏斗 + 关键指标 + 转化率趋势 + 异常诊断；</li>
 *   <li>{@code GET reporting/traffic-conversion/drilldown} —— 构成明细（哪个 listing / 哪个广告活动 / 哪张订单）；</li>
 *   <li>{@code GET reporting/traffic-conversion/capabilities} —— 能力声明；</li>
 *   <li>{@code GET reporting/traffic-conversion/metrics} —— 指标口径目录；</li>
 *   <li>{@code POST reporting/traffic-conversion/refresh} —— 刷新投影。</li>
 * </ul>
 *
 * <p><b>🔴 页面必须做到的四件事</b>：
 *
 * <ol>
 *   <li><b>两套口径分列、不混算</b>：平台口径（页面口径）与订单口径各带自己的标签，
 *       转化率有两个口径、客单价有三个口径 —— <b>每个值旁边都贴着口径标签</b>。</li>
 *   <li><b>「算不出来」≠「等于 0」</b>：{@code null} 显示「—」/「不可比」+ 原因，
 *       <b>绝不用 0 顶替</b>（假 0 会被读成「这段时间没流量」）。</li>
 *   <li><b>自然流量是推导值</b>，相减为负时显示「口径冲突」，<b>不显示 0</b>。</li>
 *   <li><b>数据更新时间常驻</b>（卷 13 卷首加粗句「数据延迟要显示」）：加载中 / 出错时也显示。</li>
 * </ol>
 *
 * <p><b>🔴 前端不做业务算术</b>：漏斗取值、环节转化率、趋势值、诊断全部来自服务端，
 * 这里只做展示格式化。前端绝自己把两个币种的金额加起来，也绝不自己算环节转化率。
 */
export function AnalyticsTrafficConversionWorkspace() {
  const today = new Date().toISOString().slice(0, 10);
  const initialRange = defaultRange(today);

  const [from, setFrom] = useState(initialRange.from);
  const [to, setTo] = useState(initialRange.to);
  const [granularity, setGranularity] = useState('DAY');
  const [drillSource, setDrillSource] = useState('');
  const [drillMetric, setDrillMetric] = useState('');
  const [drillPage, setDrillPage] = useState(0);

  const [data, setData] = useState<TrafficConversionResponse | null>(null);
  const [drilldown, setDrilldown] = useState<TrafficDrilldownResponse | null>(null);
  // 🔴 下钻有自己的错误态：下钻读不到**不应该**把整张报表打成错误态。
  const [drilldownError, setDrilldownError] = useState('');
  const [capabilities, setCapabilities] = useState<TrafficConversionCapabilities | null>(null);
  const [definitions, setDefinitions] = useState<TrafficMetricDefinitionView[]>([]);
  const [refreshResults, setRefreshResults] = useState<TrafficRefreshResult[] | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const query = useMemo(() => {
    const params = new URLSearchParams({ from, to, granularity });
    return params.toString();
  }, [from, to, granularity]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [report, caps, metrics] = await Promise.all([
        api<TrafficConversionResponse>(`reporting/traffic-conversion?${query}`),
        api<TrafficConversionCapabilities>('reporting/traffic-conversion/capabilities'),
        api<TrafficMetricDefinitionView[]>('reporting/traffic-conversion/metrics'),
      ]);
      setData(report);
      setCapabilities(caps);
      setDefinitions(metrics);
    } catch (e) {
      setData(null);
      setError(e instanceof Error ? e.message : '读取流量转化报表失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [query]);

  const loadDrilldown = useCallback(async () => {
    setDrilldownError('');
    try {
      const params = new URLSearchParams({
        from,
        to,
        page: String(drillPage),
        size: '20',
      });
      if (drillSource) params.set('trafficSource', drillSource);
      if (drillMetric) params.set('metricCode', drillMetric);
      setDrilldown(
        await api<TrafficDrilldownResponse>(`reporting/traffic-conversion/drilldown?${params}`),
      );
    } catch (e) {
      setDrilldown(null);
      setDrilldownError(e instanceof Error ? e.message : '读取构成明细失败，请重试');
    }
  }, [from, to, drillSource, drillMetric, drillPage]);

  const refreshProjection = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const params = new URLSearchParams({ from, to });
      const results = await api<TrafficRefreshResult[]>(
        `reporting/traffic-conversion/refresh?${params}`,
        'POST',
      );
      setRefreshResults(results);
      // 刷完立刻重读 —— 投影变了，页面上的数必须跟着变。
      await load();
      await loadDrilldown();
    } catch (e) {
      setError(e instanceof Error ? e.message : '刷新流量转化投影失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [from, to, load, loadDrilldown]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void loadDrilldown();
  }, [loadDrilldown]);

  const state = pageState(data, error);
  const metricGroups = keyMetricGroups(data);

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

      {/* ── 时间范围与粒度 ─────────────────────────────────────────────── */}
      <SectionCard
        title="时间范围与粒度"
        description="🔴 平台流量数据（页面口径）与内部订单数据（订单口径）口径不同，本页分列展示、绝不混算。转化率有两个口径，客单价有三个口径，每个值旁边都贴着口径标签。自然流量是推导值（平台商品口径 − 广告口径），不是平台直接给出的数。"
      >
        <div className="flex flex-wrap items-end gap-4">
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            起始日期
            <input
              type="date"
              value={from}
              max={to}
              onChange={(e) => {
                setFrom(e.target.value);
                setDrillPage(0);
              }}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            结束日期
            <input
              type="date"
              value={to}
              min={from}
              onChange={(e) => {
                setTo(e.target.value);
                setDrillPage(0);
              }}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
          </label>
          <div className="flex flex-col gap-1">
            <span className="text-copy-13 text-muted-foreground">趋势粒度</span>
            <Segmented
              value={granularity}
              onChange={(next) => {
                setGranularity(next);
                setDrillPage(0);
              }}
              options={granularityOptions()}
              aria-label="转化率趋势粒度"
            />
          </div>
          <Button
            variant="outline"
            size="sm"
            disabled={busy}
            onClick={() => void refreshProjection()}
          >
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            刷新流量转化投影
          </Button>
        </div>
        <p className="text-copy-13 text-muted-foreground mt-3">
          状态：{statusLabel(data?.status ?? 'EMPTY')}。 🔴 桶的对齐规则就是口径的一部分：周按 ISO
          周（周一起），月按自然月； 平台口径的统计日是**平台自然日**，订单口径的统计日是 **UTC
          自然日** —— 两者不是一个日。
        </p>
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
            title="流量转化报表读不到"
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
            title="正在读取流量转化报表…"
            description="读取期间页面上的「数据截至」仍然有效。"
          />
        </SectionCard>
      )}

      {/* ── 空态 ───────────────────────────────────────────────────────── */}
      {state === 'empty' && (
        <SectionCard>
          <EmptyState
            title={emptyReasonLabel(data?.emptyReason ?? null) || '没有流量事实'}
            description={emptyText(data)}
          />
        </SectionCard>
      )}

      {/* ── 就绪态 ─────────────────────────────────────────────────────── */}
      {state === 'ready' && data && (
        <>
          {/* ── 口径说明（常驻）────────────────────────────────────────── */}
          {data.caliberNotes.length > 0 && (
            <div className="border-border bg-muted/40 text-copy-13 text-muted-foreground space-y-1 rounded-lg border px-4 py-3">
              {data.caliberNotes.map((note, index) => (
                <p key={index}>{note}</p>
              ))}
            </div>
          )}

          {/* ── 警告（缺失的来源 / 口径背离）────────────────────────────── */}
          {data.warnings.length > 0 && (
            <div className="border-border bg-muted/40 text-copy-13 text-muted-foreground space-y-1 rounded-lg border px-4 py-3">
              {data.warnings.map((warning, index) => (
                <p key={index} className="text-foreground flex items-start gap-2">
                  <TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                  {warning}
                </p>
              ))}
            </div>
          )}

          {/* ── 漏斗视图 ───────────────────────────────────────────────── */}
          <SectionCard
            title="转化漏斗"
            description="四组漏斗各属一套口径：平台商品口径、自然流量（推导值）、广告口径、订单口径。🔴 **不混算**：每一节都带自己的口径标签与来源标签；「—」= 算不出来，不是 0。"
          >
            <div className="space-y-4">
              {data.funnel.map((group) => (
                <div key={group.groupKey} className="border-border rounded-lg border px-4 py-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-copy-14 text-foreground font-medium">
                      {group.displayName}
                    </span>
                    <Badge tone="info">{caliberLabel(group.caliber)}</Badge>
                    <Badge tone="neutral">{trafficSourceShortLabel(group.trafficSource)}</Badge>
                  </div>
                  <p className="text-copy-13 text-muted-foreground mt-1">
                    {funnelSourceNote(group)}
                  </p>
                  <div className="mt-3 overflow-x-auto">
                    <table className="w-full text-left text-sm">
                      <thead className="text-copy-13 text-muted-foreground">
                        <tr className="border-border border-b">
                          <th className="py-2 pr-3 font-medium">环节</th>
                          <th className="py-2 pr-3 text-right font-medium">取值</th>
                          <th className="py-2 pr-3 text-right font-medium">相对上一环节</th>
                          <th className="py-2 pr-3 text-right font-medium">相对首环节</th>
                          <th className="py-2 pr-3 font-medium">口径 / 来源</th>
                          <th className="py-2 pr-3 font-medium">可用性</th>
                        </tr>
                      </thead>
                      <tbody>
                        {group.stages.map((stage) => (
                          <tr key={stage.stageKey} className="border-border border-b">
                            <td className="py-2 pr-3">{stage.displayName}</td>
                            <td className="py-2 pr-3 text-right">
                              {stage.unit === 'PERCENT'
                                ? percentText(stage.value)
                                : stage.unit === 'CURRENCY'
                                  ? amountText(stage.value, stage.currency)
                                  : quantityText(stage.value)}
                            </td>
                            <td className="py-2 pr-3 text-right">{percentText(stage.stepRate)}</td>
                            <td className="py-2 pr-3 text-right">
                              {percentText(stage.overallRate)}
                            </td>
                            <td className="text-copy-13 text-muted-foreground py-2 pr-3">
                              {caliberLabel(stage.caliber)} ·{' '}
                              {trafficSourceShortLabel(stage.trafficSource)}
                            </td>
                            <td className="py-2 pr-3">
                              <Badge tone={availabilityTone(stage.availability)}>
                                {availabilityLabel(stage.availability)}
                              </Badge>
                              {stage.availabilityNote && (
                                <div className="text-copy-13 text-muted-foreground">
                                  {stage.availabilityNote}
                                </div>
                              )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              ))}
            </div>
          </SectionCard>

          {/* ── 关键指标（按口径分列）──────────────────────────────────── */}
          <SectionCard
            title="关键指标（按口径分列）"
            description="🔴 平台口径与订单口径**分列**显示：转化率有两个口径、客单价有三个口径，**每个都不是同一个数**。金额按币种分别列出，绝不跨币种相加。"
          >
            <div className="space-y-4">
              {metricGroups.map((group) => (
                <div key={group.caliber}>
                  <h4 className="text-copy-13 text-muted-foreground mb-2">{group.label}</h4>
                  <div className="overflow-x-auto">
                    <table className="w-full text-left text-sm">
                      <thead className="text-copy-13 text-muted-foreground">
                        <tr className="border-border border-b">
                          <th className="py-2 pr-3 font-medium">指标</th>
                          <th className="py-2 pr-3 text-right font-medium">取值</th>
                          <th className="py-2 pr-3 font-medium">来源</th>
                          <th className="py-2 pr-3 font-medium">可用性</th>
                        </tr>
                      </thead>
                      <tbody>
                        {group.metrics.map((metric) => (
                          <tr
                            key={`${metric.metricCode}-${metric.currency}`}
                            className="border-border border-b"
                          >
                            <td className="py-2 pr-3">{metric.displayName}</td>
                            <td className="py-2 pr-3 text-right">{metricValueText(metric)}</td>
                            <td className="text-copy-13 text-muted-foreground py-2 pr-3">
                              {metric.trafficSource
                                ? trafficSourceShortLabel(metric.trafficSource)
                                : '推导值'}
                            </td>
                            <td className="py-2 pr-3">
                              <Badge tone={availabilityTone(metric.availability)}>
                                {availabilityLabel(metric.availability)}
                              </Badge>
                              {metric.availabilityNote && (
                                <div className="text-copy-13 text-muted-foreground">
                                  {metric.availabilityNote}
                                </div>
                              )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              ))}
            </div>
          </SectionCard>

          {/* ── 转化率趋势 ─────────────────────────────────────────────── */}
          <SectionCard
            title="转化率趋势"
            description={`按${
              data.granularityLabel ?? '日'
            }归集（桶的对齐规则就是口径的一部分）。🔴 桶里的量与货币无关（曝光 / 点击 / Session / 笔数），所以跨币种合计；金额类指标只出现在上面的关键指标里。`}
          >
            <TrendTable points={data.trend} />
          </SectionCard>

          {/* ── 异常诊断 ───────────────────────────────────────────────── */}
          <SectionCard
            title="异常诊断"
            description="🔴 环比基线是**窗口内的上一个桶**：窗口外的事实没有投影 ⇒ 窗口的第一个桶没有基线，**不诊断**（不是「没有异常」）。口径背离是**两套口径统计范围不同**，不是数据错 —— 但它必须被看见。"
          >
            {data.diagnoses.length === 0 ? (
              <EmptyState
                title="这个窗口内没有触发诊断规则"
                description="没有触发不等于「一切正常」：窗口的第一个桶没有基线，不参与环比诊断。"
              />
            ) : (
              <div className="space-y-3">
                {data.diagnoses.map((diagnosis, index) => (
                  <div
                    key={`${diagnosis.ruleCode}-${diagnosis.bucketStart ?? 'window'}-${index}`}
                    className="border-border rounded-lg border px-4 py-3"
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={diagnosisTone(diagnosis.severity)}>
                        {diagnosisSeverityLabel(diagnosis.severity)}
                      </Badge>
                      <span className="text-copy-14 text-foreground font-medium">
                        {diagnosisRuleLabel(diagnosis.ruleCode)}
                      </span>
                      {diagnosis.bucketStart && (
                        <span className="text-copy-13 text-muted-foreground">
                          {diagnosis.bucketStart} ~ {diagnosis.bucketEnd}
                        </span>
                      )}
                    </div>
                    <p className="text-copy-13 text-foreground mt-2">{diagnosis.message}</p>
                    <p className="text-copy-13 text-muted-foreground mt-1">
                      {diagnosis.suggestion}
                    </p>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          {/* ── 构成明细下钻 ───────────────────────────────────────────── */}
          <SectionCard
            title="构成明细下钻"
            description="卷 13 卷首加粗句「可下钻」的落点：每个汇总数字都能钻到构成明细（哪个平台商品 / 哪个广告活动 / 哪张内部订单）。🔴 明细行带来源口径与统计日 —— 平台口径是平台自然日，订单口径是 UTC 自然日。"
          >
            <div className="flex flex-wrap items-end gap-3">
              <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
                来源口径
                <select
                  value={drillSource}
                  onChange={(e) => {
                    setDrillSource(e.target.value);
                    setDrillPage(0);
                  }}
                  className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                >
                  <option value="">全部</option>
                  <option value="LISTING">平台商品（平台口径）</option>
                  <option value="AD">广告活动（广告口径）</option>
                  <option value="ORDER">内部订单（订单口径）</option>
                </select>
              </label>
              <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
                指标码
                <input
                  type="text"
                  value={drillMetric}
                  placeholder="留空 = 全部"
                  onChange={(e) => {
                    setDrillMetric(e.target.value);
                    setDrillPage(0);
                  }}
                  className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                />
              </label>
            </div>
            {drilldownError && (
              <div className="border-border bg-muted/40 text-copy-13 text-muted-foreground mt-3 rounded-lg border px-4 py-3">
                构成明细读不到：{drilldownError}（这是「读不到」，不是「没有数据」）
              </div>
            )}
            {drilldown && drilldown.rows.length > 0 && (
              <div className="mt-3 overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="text-copy-13 text-muted-foreground">
                    <tr className="border-border border-b">
                      <th className="py-2 pr-3 font-medium">构成对象</th>
                      <th className="py-2 pr-3 font-medium">类型</th>
                      <th className="py-2 pr-3 font-medium">统计日</th>
                      <th className="py-2 pr-3 font-medium">币种</th>
                      <th className="py-2 pr-3 text-right font-medium">取值</th>
                    </tr>
                  </thead>
                  <tbody>
                    {drilldown.rows.map((row) => (
                      <tr
                        key={`${row.trafficSource}-${row.sourceKind}-${row.referenceId}-${row.statDate}-${row.currency}`}
                        className="border-border border-b"
                      >
                        <td className="py-2 pr-3">
                          <div>{drilldownReferenceText(row)}</div>
                          {row.shopCode && (
                            <div className="text-copy-13 text-muted-foreground">
                              店铺 {row.shopCode}
                            </div>
                          )}
                          {row.orderNo && (
                            <div className="text-copy-13 text-muted-foreground">
                              订单 {row.orderNo}
                            </div>
                          )}
                        </td>
                        <td className="text-copy-13 text-muted-foreground py-2 pr-3">
                          {drilldownKindLabel(row.sourceKind)}
                        </td>
                        <td className="py-2 pr-3">{row.statDate}</td>
                        <td className="text-copy-13 text-muted-foreground py-2 pr-3">
                          {row.currency || '与币种无关'}
                        </td>
                        <td className="py-2 pr-3 text-right">
                          {row.value && row.value !== 0
                            ? amountText(row.value, row.currency)
                            : quantityText(row.quantity)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {drilldown && (
              <div className="text-copy-13 text-muted-foreground mt-3 flex items-center gap-2">
                <span>
                  第 {drilldown.page + 1} /{' '}
                  {Math.max(Math.ceil(drilldown.totalRows / drilldown.size), 1)} 页（
                  {drilldown.totalRows} 条）
                </span>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={drilldown.page <= 0}
                  onClick={() => setDrillPage((p) => Math.max(p - 1, 0))}
                >
                  上一页
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={(drilldown.page + 1) * drilldown.size >= drilldown.totalRows}
                  onClick={() => setDrillPage((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            )}
          </SectionCard>

          {/* ── 能力声明（口径目录 + 拿不到的部分）──────────────────────── */}
          <SectionCard
            title="口径目录与能力边界"
            description="口径唯一：一个指标只允许一处定义。下面的说明是页面上原样显示的口径来源与统计范围。"
          >
            <div className="text-copy-13 space-y-3">
              {definitions.length > 0 && (
                <div>
                  <h4 className="text-muted-foreground mb-1">指标口径</h4>
                  <ul className="list-disc space-y-1 pl-5">
                    {definitions.map((definition) => (
                      <li key={definition.metricCode}>
                        <span className="text-foreground">{definition.displayName}</span>
                        <span className="text-muted-foreground">
                          （{definition.metricCode}）· {caliberLabel(definition.caliber)} ·{' '}
                          {definition.description}
                        </span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
              {upstreamModules(capabilities).length > 0 && (
                <div>
                  <h4 className="text-muted-foreground mb-1">上游（真的读过的）</h4>
                  <ul className="list-disc space-y-1 pl-5">
                    {upstreamModules(capabilities).map((line) => (
                      <li key={line}>{line}</li>
                    ))}
                  </ul>
                </div>
              )}
              {notDeliveredLines(capabilities).length > 0 && (
                <div>
                  <h4 className="text-muted-foreground mb-1">确实拿不到的部分</h4>
                  <ul className="list-disc space-y-1 pl-5">
                    {notDeliveredLines(capabilities).map((line) => (
                      <li key={line}>{line}</li>
                    ))}
                  </ul>
                </div>
              )}
              {ruleNotes(capabilities).length > 0 && (
                <div>
                  <h4 className="text-muted-foreground mb-1">判定规则与诊断阈值</h4>
                  <ul className="list-disc space-y-1 pl-5">
                    {ruleNotes(capabilities).map((rule) => (
                      <li key={rule.ruleCode}>
                        <span className="text-foreground">{rule.ruleCode}</span>
                        <span className="text-muted-foreground">：{rule.description}</span>
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

/**
 * 转化率趋势表（**带一条相对条**，让「掉得有多狠」一眼可见）。
 *
 * <p>🔴 相对条是**按每列自己的最大值**归一的 —— 跨口径比大小本来就不可比，
 * 所以每列各自归一，不共用一根尺子。
 */
function TrendTable({ points }: { points: TrafficTrendPoint[] }) {
  if (points.length === 0) {
    return (
      <EmptyState
        title="这个窗口内没有趋势数据"
        description="没有趋势点不等于「平稳」—— 真相可能是「平台没回数据」。"
      />
    );
  }
  const maxOf = (metricCode: string): number => {
    let max = 0;
    for (const point of points) {
      const value = trendMetricOf(point, metricCode)?.value;
      if (value !== null && value !== undefined && Math.abs(value) > max) max = Math.abs(value);
    }
    return max;
  };
  const conversionMax = Math.max(
    maxOf(METRIC_CONVERSION_RATE),
    maxOf(METRIC_AD_CONVERSION_RATE),
    0,
  );
  const impressionMax = Math.max(maxOf(METRIC_IMPRESSIONS), 0);

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <thead className="text-copy-13 text-muted-foreground">
          <tr className="border-border border-b">
            <th className="py-2 pr-3 font-medium">桶</th>
            <th className="py-2 pr-3 text-right font-medium">曝光（平台商品口径）</th>
            <th className="py-2 pr-3 text-right font-medium">转化率（平台口径）</th>
            <th className="py-2 pr-3 text-right font-medium">转化率（广告口径）</th>
            <th className="py-2 pr-3 text-right font-medium">订单笔数（订单口径）</th>
          </tr>
        </thead>
        <tbody>
          {points.map((point) => {
            const impressions = trendMetricOf(point, METRIC_IMPRESSIONS)?.value ?? null;
            const conversion = trendMetricOf(point, METRIC_CONVERSION_RATE)?.value ?? null;
            const adConversion = trendMetricOf(point, METRIC_AD_CONVERSION_RATE)?.value ?? null;
            const orders = trendMetricOf(point, 'TRAFFIC_ORDER_COUNT')?.value ?? null;
            return (
              <tr key={point.bucketStart} className="border-border border-b">
                <td className="py-2 pr-3">{point.bucketLabel}</td>
                <td className="py-2 pr-3 text-right">
                  <div>{quantityText(impressions)}</div>
                  {impressions !== null && impressionMax > 0 && (
                    <div
                      className="bg-brand/30 ml-auto mt-1 h-1 rounded"
                      style={{
                        width: `${Math.round((Math.abs(impressions) / impressionMax) * 100)}%`,
                      }}
                    />
                  )}
                </td>
                <td className="py-2 pr-3 text-right">
                  <div>{percentText(conversion)}</div>
                  {conversion !== null && conversionMax > 0 && (
                    <div
                      className="bg-brand/30 ml-auto mt-1 h-1 rounded"
                      style={{
                        width: `${Math.round((Math.abs(conversion) / conversionMax) * 100)}%`,
                      }}
                    />
                  )}
                </td>
                <td className="py-2 pr-3 text-right">{percentText(adConversion)}</td>
                <td className="py-2 pr-3 text-right">{quantityText(orders)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
