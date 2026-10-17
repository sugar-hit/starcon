'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle,
  BarChart3,
  RefreshCw,
  ShieldAlert,
  Store,
  Users,
} from 'lucide-react';
import {
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';

import type { QualityFilter } from './fulfillment-quality-model';
import type {
  AfterSaleSignalPage,
  AlertPage,
  AlertRuleRow,
  FulfillmentExceptionPage,
  QualityCapabilities,
  QualityDimensionPage,
  QualityDrilldownPage,
  QualityEventPage,
  QualityReport,
} from '../../../../../packages/api-contracts/src/index';
import {
  basisText,
  capabilityTone,
  comparisonLabel,
  dimensionLabel,
  evaluateAlerts,
  fetchAfterSaleSignals,
  fetchAlertRules,
  fetchAlerts,
  fetchFulfillmentExceptions,
  fetchQualityCapabilities,
  fetchQualityDimensions,
  fetchQualityDrilldown,
  fetchQualityEvents,
  fetchQualityReport,
  formatRate,
  metricLabel,
  metricTone,
  severityLabel,
  severityTone,
} from './fulfillment-quality-model';

/** 朴素下拉框：枚举值用 input 画会得到一个只能手打、还能打错值的控件。 */
function SelectField(props: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: { value: string; label: string }[];
}) {
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={props.id} className="text-sm font-medium">
        {props.label}
      </label>
      <select
        id={props.id}
        className="border-input h-9 rounded-md border bg-transparent px-2 text-sm"
        value={props.value}
        onChange={(e) => props.onChange(e.target.value)}
      >
        {props.options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </div>
  );
}

const TONE_CLASS: Record<string, string> = {
  ok: 'text-emerald-600',
  warn: 'text-amber-600',
  bad: 'text-red-600',
  neutral: 'text-muted-foreground',
};

const TONE_BADGE: Record<string, string> = {
  ok: 'bg-emerald-500/10 text-emerald-600',
  bad: 'bg-red-500/10 text-red-600',
  neutral: 'bg-muted text-muted-foreground',
  warn: 'bg-amber-500/10 text-amber-600',
};

function Badge({ tone, children }: { tone: string; children: React.ReactNode }) {
  return (
    <span className={`rounded px-2 py-0.5 text-xs font-medium ${TONE_BADGE[tone] ?? ''}`}>
      {children}
    </span>
  );
}

const METRIC_OPTIONS = [
  { value: 'SHIP_ON_TIME_RATE', label: '发货及时率' },
  { value: 'COMPLETE_SHIPMENT_RATE', label: '完整发货率' },
  { value: 'WRONG_OR_MISSING_RATE', label: '错发漏发率' },
  { value: 'CANCELLATION_RATE', label: '取消率' },
  { value: 'DISPUTE_RATE', label: '纠纷率' },
  { value: 'RETURN_RATE', label: '退货率' },
];

export function FulfillmentQualityWorkspace() {
  const [caps, setCaps] = useState<QualityCapabilities | null>(null);
  const [report, setReport] = useState<QualityReport | null>(null);
  const [dimensions, setDimensions] = useState<QualityDimensionPage | null>(null);
  const [drilldown, setDrilldown] = useState<QualityDrilldownPage | null>(null);
  const [signals, setSignals] = useState<AfterSaleSignalPage | null>(null);
  const [exceptions, setExceptions] = useState<FulfillmentExceptionPage | null>(null);
  const [rules, setRules] = useState<AlertRuleRow[] | null>(null);
  const [alerts, setAlerts] = useState<AlertPage | null>(null);
  const [events, setEvents] = useState<QualityEventPage | null>(null);

  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [dimensionBy, setDimensionBy] = useState('PLATFORM');
  const [drillMetric, setDrillMetric] = useState('SHIP_ON_TIME_RATE');
  const [signalType, setSignalType] = useState('');
  const [exceptionType, setExceptionType] = useState('');
  const [drillPage, setDrillPage] = useState(0);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  // 刻意是**空对象**而不是 `{ from: undefined }`：exactOptionalPropertyTypes 下
  // 「显式 undefined」不等于「不传」，而 query() 本来就把 undefined 与缺失同等对待。
  const filter: QualityFilter = useMemo(
    () => (from && to ? { from: from.trim(), to: to.trim() } : {}),
    [from, to],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [c, r, d, s, e, rl, a, ev] = await Promise.all([
        fetchQualityCapabilities(),
        fetchQualityReport(filter),
        fetchQualityDimensions({ ...filter, dimensionBy }),
        fetchAfterSaleSignals({ signalType, page: 0, size: 20 }),
        fetchFulfillmentExceptions({ exceptionType, page: 0, size: 20 }),
        fetchAlertRules(),
        fetchAlerts({ page: 0, size: 20 }),
        fetchQualityEvents({ page: 0, size: 20 }),
      ]);
      setCaps(c);
      setReport(r);
      setDimensions(d);
      setSignals(s);
      setExceptions(e);
      setRules(rl);
      setAlerts(a);
      setEvents(ev);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, [filter, dimensionBy, signalType, exceptionType]);

  const loadDrilldown = useCallback(async () => {
    setError(null);
    try {
      const page = await fetchQualityDrilldown({
        ...filter,
        metric: drillMetric,
        page: drillPage,
        size: 50,
      });
      setDrilldown(page);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }, [filter, drillMetric, drillPage]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    loadDrilldown();
  }, [loadDrilldown]);

  const runEvaluate = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      const result = await evaluateAlerts(filter);
      setNotice(
        `已评估 ${result.evaluated} 项，越限 ${result.triggered} 项，` +
          `因无样本跳过 ${result.skippedNoBasis} 项。`,
      );
      const a = await fetchAlerts({ page: 0, size: 20 });
      setAlerts(a);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }, [filter]);

  const unavailable = (caps?.dimensions ?? []).filter((d) => !d.available);

  return (
    <div className="flex flex-col gap-6 p-6">
      <PageHeader
        title="履约质量"
        description="发货及时率 / 完整发货率 / 错发漏发率 / 取消率 / 纠纷率 / 退货率，按店铺（平台级）与人员维度对比，并可下钻到构成指标的明细单。"
      />

      {error ? (
        <Notice tone="danger" title="加载失败">
          {error}
        </Notice>
      ) : null}
      {notice ? (
        <Notice tone="info" title="已执行">
          {notice}
        </Notice>
      ) : null}

      <SectionCard title="统计窗口" description="窗口决定每个指标的分母。留空则取最近 30 天。">
        <div className="flex flex-wrap items-end gap-3">
          <FormField id="quality-from" label="起">
            <Input
              value={from}
              placeholder="2026-03-01T00:00:00+08:00"
              onChange={(e) => setFrom(e.target.value)}
            />
          </FormField>
          <FormField id="quality-to" label="止">
            <Input
              value={to}
              placeholder="2026-03-10T00:00:00+08:00"
              onChange={(e) => setTo(e.target.value)}
            />
          </FormField>
          <SelectField
            id="quality-dimension"
            label="对比维度"
            value={dimensionBy}
            onChange={(v) => {
              setDimensionBy(v);
              setDrillPage(0);
            }}
            options={[
              { value: 'PLATFORM', label: '店铺（平台级）' },
              { value: 'PERSON', label: '人员' },
            ]}
          />
          <Button onClick={() => void load()} disabled={loading}>
            <RefreshCw className="h-4 w-4" />
            {loading ? '加载中…' : '刷新'}
          </Button>
        </div>
        {report ? (
          <p className="text-muted-foreground mt-3 text-xs">
            实际窗口 {report.window.from} → {report.window.to}
            {report.window.defaulted ? '（未指定，取默认最近 30 天）' : ''}；{report.scope}
          </p>
        ) : null}
      </SectionCard>

      {unavailable.length > 0 ? (
        <SectionCard
          title="本模块当前拿不到的数据"
          description="下面几项在本车道集成头里没有可读上游。它们一律标为不可用并点名归属模块 —— 指标卡上对应的 0% 不代表「没问题」，而代表「没人登记」。"
        >
          <ul className="flex flex-col gap-3">
            {unavailable.map((d) => (
              <li key={d.code} className="flex flex-col gap-1">
                <div className="flex items-center gap-2">
                  <Badge tone={capabilityTone(d.available)}>不可用</Badge>
                  <span className="text-sm font-medium">{d.displayName}</span>
                  <span className="text-muted-foreground text-xs">归属：{d.ownerModule}</span>
                </div>
                <p className="text-muted-foreground text-xs">{d.unavailableReason}</p>
                <p className="text-muted-foreground text-xs">本模块的处理：{d.handling}</p>
              </li>
            ))}
          </ul>
        </SectionCard>
      ) : null}

      <SectionCard
        title="质量指标卡"
        description="每个指标同时给分子、分母与口径。分母为 0 时显示「无法判定」，不是 0%。"
      >
        {loading && !report ? (
          <p className="text-muted-foreground text-sm">加载中…</p>
        ) : !report || report.metrics.length === 0 ? (
          <EmptyState
            title="暂无指标"
            description="当前窗口内没有任何可判定样本。先确认上游（时效事实 / 履约进度 / 发货记录）是否已同步。"
          />
        ) : (
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            {report.metrics.map((m) => (
              <div key={m.metricCode} className="flex flex-col gap-2">
                <StatCard
                  label={metricLabel(m.metricCode, m.displayName)}
                  value={formatRate(m.rate)}
                  icon={<BarChart3 className="h-4 w-4" />}
                  polarity={m.direction === 'HIGHER_IS_BETTER' ? 'positive' : 'negative'}
                  comparisonLabel={basisText(m.numeratorCount, m.denominatorCount)}
                />
                <p
                  className={`text-xs font-medium ${TONE_CLASS[metricTone(m.direction, m.rate)] ?? ''}`}
                >
                  分子：{m.numerator}；分母：{m.denominator}
                </p>
                <p className="text-muted-foreground text-xs">时钟：{m.clock}</p>
                {m.excludedFromDenominator > 0 ? (
                  <p className="text-muted-foreground text-xs">
                    刻意不进分母：{m.excludedFromDenominator} 条（如未登记发货时限）
                  </p>
                ) : null}
                <p className="text-muted-foreground text-xs">{m.caveat}</p>
                <button
                  type="button"
                  className="text-primary self-start text-xs underline"
                  onClick={() => {
                    setDrillMetric(m.metricCode);
                    setDrillPage(0);
                  }}
                >
                  查看构成该指标的明细单 →
                </button>
              </div>
            ))}
          </div>
        )}
      </SectionCard>

      <SectionCard
        title={dimensionBy === 'PERSON' ? '按人员对比' : '按店铺（平台级）对比'}
        description={
          dimensions?.caveat ??
          '维度对比是真 GROUP BY，与翻页无关。店铺维度当前只能到平台级 order_source。'
        }
      >
        {!dimensions || dimensions.items.length === 0 ? (
          <EmptyState
            title="暂无维度数据"
            description="当前窗口内该维度没有可聚合的样本。"
          />
        ) : (
          <div className="flex flex-col gap-4">
            {dimensions.items.map((row) => (
              <div key={row.dimensionKey} className="border rounded-lg p-3">
                <div className="mb-2 flex items-center gap-2">
                  {dimensionBy === 'PERSON' ? (
                    <Users className="h-4 w-4" />
                  ) : (
                    <Store className="h-4 w-4" />
                  )}
                  <span className="font-medium">
                    {dimensionLabel(row.dimensionKey, row.dimensionLabel)}
                  </span>
                  <Badge tone="neutral">{row.dimensionKey}</Badge>
                </div>
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-muted-foreground text-left text-xs">
                      <th className="py-1">指标</th>
                      <th className="py-1">比率</th>
                      <th className="py-1">分子 ÷ 分母</th>
                    </tr>
                  </thead>
                  <tbody>
                    {row.metrics.map((m) => (
                      <tr key={m.metricCode} className="border-t">
                        <td className="py-1">{metricLabel(m.metricCode, m.displayName)}</td>
                        <td className={`py-1 ${TONE_CLASS[metricTone(m.direction, m.rate)] ?? ''}`}>
                          {formatRate(m.rate)}
                        </td>
                        <td className="text-muted-foreground py-1">
                          {basisText(m.numeratorCount, m.denominatorCount)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ))}
          </div>
        )}
      </SectionCard>

      <SectionCard
        title="指标明细下钻"
        description="点开一个指标看构成它的明细单。刻意不进分母的样本也会列出来（角色为 DENOMINATOR_ONLY）。"
      >
        <div className="flex flex-wrap items-end gap-3">
          <SelectField
            id="quality-metric"
            label="指标"
            value={drillMetric}
            onChange={(v) => {
              setDrillMetric(v);
              setDrillPage(0);
            }}
            options={METRIC_OPTIONS}
          />
        </div>
        {!drilldown || drilldown.total === 0 ? (
          <EmptyState
            title="没有明细"
            description="该窗口内这个指标没有任何可判定样本。"
          />
        ) : (
          <div className="mt-3 flex flex-col gap-3">
            <p className="text-muted-foreground text-xs">
              {drilldown.metricDisplayName}：{drilldown.definition}；
              分子 {drilldown.numeratorCount} ÷ 分母 {drilldown.denominatorCount} ＝{' '}
              {formatRate(drilldown.rate)}；共 {drilldown.total} 行（第 {drilldown.page + 1} /{' '}
              {drilldown.totalPages} 页）
            </p>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground text-left text-xs">
                    <th className="py-1">订单号</th>
                    <th className="py-1">平台</th>
                    <th className="py-1">角色</th>
                    <th className="py-1">分类</th>
                    <th className="py-1">判定时刻</th>
                  </tr>
                </thead>
                <tbody>
                  {drilldown.items.map((item, idx) => (
                    <tr key={`${item.orderId}-${idx}`} className="border-t">
                      <td className="py-1">{item.orderNo}</td>
                      <td className="py-1">{item.orderSource}</td>
                      <td className="py-1">
                        <Badge tone={item.metricRole === 'NUMERATOR' ? 'bad' : 'neutral'}>
                          {item.metricRole === 'NUMERATOR' ? '计入分子' : '不计分子'}
                        </Badge>
                      </td>
                      <td className="py-1">{item.classificationLabel}</td>
                      <td className="text-muted-foreground py-1">{item.occurredAt}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="flex gap-2">
              <Button
                disabled={drilldown.page <= 0}
                onClick={() => {
                  setDrillPage(Math.max(0, drillPage - 1));
                }}
              >
                上一页
              </Button>
              <Button
                disabled={drilldown.page + 1 >= drilldown.totalPages}
                onClick={() => {
                  setDrillPage(drillPage + 1);
                }}
              >
                下一页
              </Button>
            </div>
          </div>
        )}
      </SectionCard>

      <SectionCard
        title="纠纷与退货登记"
        description="上游 11 售后在别的服务的独立库，按模块边界纪律禁止跨服务读取 —— 这里登记的是「有人报了这件事」，不是系统抓到了平台售后数据。"
      >
        <div className="mb-3 flex items-end gap-3">
          <SelectField
            id="quality-signal-type"
            label="类型"
            value={signalType}
            onChange={setSignalType}
            options={[
              { value: '', label: '全部' },
              { value: 'DISPUTE', label: '纠纷' },
              { value: 'RETURN', label: '退货' },
            ]}
          />
        </div>
        {!signals || signals.total === 0 ? (
          <EmptyState
            title="暂无登记"
            description="当前租户还没有登记过纠纷或退货。**没登记不等于没有发生** —— 纠纷率 0% 在这里表达的是「没人报」。"
          />
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">订单号</th>
                <th className="py-1">类型</th>
                <th className="py-1">状态</th>
                <th className="py-1">发生时刻</th>
                <th className="py-1">来源</th>
              </tr>
            </thead>
            <tbody>
              {signals.items.map((s) => (
                <tr key={s.id} className="border-t">
                  <td className="py-1">{s.orderNo}</td>
                  <td className="py-1">{s.signalType === 'DISPUTE' ? '纠纷' : '退货'}</td>
                  <td className="py-1">{s.signalState}</td>
                  <td className="text-muted-foreground py-1">{s.occurredAt}</td>
                  <td className="text-muted-foreground py-1">{s.signalSource}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </SectionCard>

      <SectionCard
        title="错发与漏发登记"
        description="错发没有 SKU 级上游（发货记录与运单没有 SKU 列），只能由人举证登记；漏发系统可推导，两者在下钻里分列。"
      >
        <div className="mb-3 flex items-end gap-3">
          <SelectField
            id="quality-exception-type"
            label="类型"
            value={exceptionType}
            onChange={setExceptionType}
            options={[
              { value: '', label: '全部' },
              { value: 'WRONG_ITEM', label: '错发' },
              { value: 'MISSING_ITEM', label: '漏发' },
            ]}
          />
        </div>
        {!exceptions || exceptions.total === 0 ? (
          <EmptyState title="暂无登记" description="当前租户还没有登记过错发或漏发。" />
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">订单号</th>
                <th className="py-1">类型</th>
                <th className="py-1">数量</th>
                <th className="py-1">登记时刻</th>
                <th className="py-1">来源</th>
              </tr>
            </thead>
            <tbody>
              {exceptions.items.map((x) => (
                <tr key={x.id} className="border-t">
                  <td className="py-1">{x.orderNo}</td>
                  <td className="py-1">{x.exceptionType === 'WRONG_ITEM' ? '错发' : '漏发'}</td>
                  <td className="py-1">{x.affectedQty ?? '—'}</td>
                  <td className="text-muted-foreground py-1">{x.reportedAt}</td>
                  <td className="text-muted-foreground py-1">{x.reportedSource}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </SectionCard>

      <SectionCard
        title="账号安全联动预警"
        description="阈值可配、评估可复现、预警 append-only。下游 16 账号健康与 17 绩效均未交付 —— 这里不假装已对接，只把阈值与留痕先摆出来。"
      >
        <div className="flex flex-wrap items-end gap-3">
          <Button onClick={() => void runEvaluate()} disabled={busy}>
            <ShieldAlert className="h-4 w-4" />
            {busy ? '评估中…' : '评估当前窗口'}
          </Button>
        </div>
        {rules && rules.length > 0 ? (
          <table className="mt-3 w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">指标</th>
                <th className="py-1">阈值</th>
                <th className="py-1">方向</th>
                <th className="py-1">级别</th>
                <th className="py-1">启用</th>
              </tr>
            </thead>
            <tbody>
              {rules.map((r) => (
                <tr key={r.id} className="border-t">
                  <td className="py-1">{metricLabel(r.metricCode, r.metricDisplayName)}</td>
                  <td className="py-1">{r.thresholdValue}</td>
                  <td className="py-1">{comparisonLabel(r.comparison)}</td>
                  <td className="py-1">
                    <Badge tone={severityTone(r.severity)}>{severityLabel(r.severity)}</Badge>
                  </td>
                  <td className="py-1">{r.enabled ? '是' : '否'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <EmptyState
            title="还没配阈值"
            description="未配置阈值的指标不会被评估，也不会产生预警 —— 这不等于它没问题。"
          />
        )}
        {alerts && alerts.total > 0 ? (
          <div className="mt-4 flex flex-col gap-2">
            <p className="text-muted-foreground text-xs">
              共 {alerts.total} 条，未确认 {alerts.openCount} 条
            </p>
            {alerts.items.map((a) => (
              <div key={a.id} className="flex flex-wrap items-center gap-2 border-t pt-2">
                <AlertTriangle
                  className={`h-4 w-4 ${TONE_CLASS[severityTone(a.severity)] ?? ''}`}
                />
                <Badge tone={severityTone(a.severity)}>{severityLabel(a.severity)}</Badge>
                <span className="text-sm">{metricLabel(a.metricCode, a.metricDisplayName)}</span>
                <span className="text-sm">
                  观测 {a.observedValue} {comparisonLabel(a.comparison)} 阈值 {a.thresholdValue}
                </span>
                <span className="text-muted-foreground text-xs">
                  {a.numerator} ÷ {a.denominator}
                  {a.dimensionValue ? `（${dimensionLabel(a.dimensionValue)}）` : ''}
                </span>
                <Badge tone={a.acknowledged ? 'neutral' : 'warn'}>
                  {a.acknowledged ? '已确认' : '待确认'}
                </Badge>
              </div>
            ))}
          </div>
        ) : null}
      </SectionCard>

      <SectionCard title="事件流" description="append-only 留痕：谁在什么时候登记了什么。">
        {!events || events.total === 0 ? (
          <EmptyState title="暂无事件" description="登记或评估之后这里会出现记录。" />
        ) : (
          <ul className="flex flex-col gap-1">
            {events.items.map((e) => (
              <li key={e.seq} className="text-muted-foreground border-t py-1 text-xs">
                #{e.seq} · {e.kind} · {e.summary} · {e.occurredAt}
              </li>
            ))}
          </ul>
        )}
      </SectionCard>
    </div>
  );
}
