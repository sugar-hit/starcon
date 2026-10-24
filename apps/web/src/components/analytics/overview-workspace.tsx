'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { api } from '@/lib/api';
import {
  SCOPE_OPTIONS,
  aggregationLabel,
  availabilityText,
  baseTotalText,
  cardValueRows,
  comparisonDirection,
  comparisonText,
  comparisonWindowText,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  definitionFormula,
  drilldownRowText,
  drilldownSourceLabel,
  emptyText,
  pageState,
  rangeText,
  scopeLabel,
  seriesMax,
  trendSeries,
} from './overview-model';
import type {
  DrilldownPage,
  MetricCard,
  MetricDefinitionView,
  OverviewResponse,
} from './overview-model';

/**
 * 13.1.1.1 经营总览工作区（卷 13「利润与经营分析」L3 13.1.1 下的第一格）。
 *
 * <p>蓝图输出：「指标卡 + 趋势图 + 异常提示 + 下钻入口 + 数据更新时间戳。」
 *
 * <p><b>数据来自 reporting 服务（13 卷的唯一归属服务）的投影读模型</b>：
 *
 * <ul>
 *   <li>{@code GET reporting/overview} —— 指标卡 + 趋势 + 数据更新时间戳；</li>
 *   <li>{@code GET reporting/overview/metrics} —— 指标口径目录（口径唯一的展示形态）；</li>
 *   <li>{@code GET reporting/overview/drilldown} —— 下钻构成明细（哪个订单 / 哪个 SKU）。</li>
 * </ul>
 *
 * <p><b>🔴 页面必须做到的五件事</b>：
 *
 * <ol>
 *   <li><b>数据更新时间常驻</b>（卷 13 卷首加粗句「数据延迟要显示」）：顶部横幅任何时候都显示
 *       「数据截至 X」，加载中 / 出错时也显示（未投影时显示「尚未投影任何数据」）。</li>
 *   <li><b>「没有数据」≠「数据是 0」</b>：{@code NO_DATA} 的卡片显示「—（该口径下没有事实数据）」，
 *       绝不用 0 顶替。</li>
 *   <li><b>不可比 ≠ 0%</b>：环比 / 同比不可比时显示原因，不显示 0%。</li>
 *   <li><b>多币种不相加</b>：一张卡上多个币种分行显示；本位币合计附汇率与时点。</li>
 *   <li><b>口径写在卡片上</b>：每个指标都带聚合方式与口径归属模块。</li>
 * </ol>
 */
export function AnalyticsOverviewWorkspace() {
  const today = new Date().toISOString().slice(0, 10);
  const initialRange = defaultRange(today);

  const [from, setFrom] = useState(initialRange.from);
  const [to, setTo] = useState(initialRange.to);
  const [scopeType, setScopeType] = useState('COMPANY');

  const [overview, setOverview] = useState<OverviewResponse | null>(null);
  const [definitions, setDefinitions] = useState<MetricDefinitionView[]>([]);
  const [drilldown, setDrilldown] = useState<DrilldownPage | null>(null);
  const [drillMetric, setDrillMetric] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const query = new URLSearchParams({ from, to, scopeType });
      const [data, metricDefs] = await Promise.all([
        api<OverviewResponse>(`reporting/overview?${query.toString()}`),
        api<MetricDefinitionView[]>('reporting/overview/metrics'),
      ]);
      setOverview(data);
      setDefinitions(metricDefs);
    } catch (e) {
      setOverview(null);
      setError(e instanceof Error ? e.message : '读取失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [from, to, scopeType]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 下钻：点某个指标卡 ⇒ 拉它的构成明细。 */
  const openDrilldown = useCallback(
    async (metricCode: string) => {
      setDrillMetric(metricCode);
      setBusy(true);
      try {
        const query = new URLSearchParams({
          metricCode,
          from,
          to,
          scopeType,
          page: '0',
          size: '50',
        });
        setDrilldown(await api<DrilldownPage>(`reporting/overview/drilldown?${query.toString()}`));
      } catch (e) {
        setDrilldown(null);
        setError(e instanceof Error ? e.message : '读取下钻明细失败，请重试');
      } finally {
        setBusy(false);
      }
    },
    [from, to, scopeType],
  );

  const state = pageState(overview, error);
  const series = useMemo(
    () =>
      trendSeries(overview?.trend ?? [], ['SALES_AMOUNT', 'ORDER_COUNT', 'UNIT_SOLD', 'AD_SPEND']),
    [overview],
  );
  const definitionOf = useMemo(() => {
    const map = new Map<string, MetricDefinitionView>();
    for (const definition of definitions) map.set(definition.metricCode, definition);
    return map;
  }, [definitions]);

  return (
    <div className="space-y-4">
      {/* ── 数据更新时间（常驻）────────────────────────────────────────── */}
      <div className="border-border bg-muted/40 flex flex-wrap items-center justify-between gap-3 rounded-lg border px-4 py-3">
        <div className="text-copy-13 text-foreground">
          <span className="font-medium">{dataUpdatedText(overview)}</span>
          <span className="text-muted-foreground ml-2">{dataUpdatedNote(overview)}</span>
        </div>
        <div className="text-copy-13 text-muted-foreground flex items-center gap-3">
          <span>
            {scopeLabel(scopeType)} · {rangeText(from, to)}
          </span>
          <Button variant="outline" size="sm" disabled={loading || busy} onClick={() => void load()}>
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            刷新
          </Button>
        </div>
      </div>

      {/* ── 时间范围与维度选择 ───────────────────────────────────────── */}
      <SectionCard title="时间范围与维度" description="时间范围决定分桶与对比窗口；维度决定汇总口径。">
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
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            维度
            <select
              value={scopeType}
              onChange={(e) => setScopeType(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              {SCOPE_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
        </div>
        {overview && overview.timezones.length > 1 && (
          <p className="text-copy-13 text-muted-foreground mt-3">
            本窗口的数据跨越 {overview.timezones.join('、')} 多个分桶时区，跨时区汇总的时间口径以各行自己的时区为准。
          </p>
        )}
      </SectionCard>

      {/* ── 错误态 ───────────────────────────────────────────────────── */}
      {state === 'error' && (
        <SectionCard>
          <EmptyState
            icon={<TriangleAlert className="h-6 w-6" />}
            title="读不到经营总览"
            description={error || '服务暂时不可用，请稍后重试。'}
            action={
              <Button variant="outline" onClick={() => void load()}>
                重试
              </Button>
            }
          />
        </SectionCard>
      )}

      {/* ── 加载态 ───────────────────────────────────────────────────── */}
      {state === 'loading' && (
        <SectionCard>
          <EmptyState title="正在读取经营总览…" description="报表数据来自投影读模型，可能需要一点时间。" />
        </SectionCard>
      )}

      {/* ── 空态 ─────────────────────────────────────────────────────── */}
      {state === 'empty' && (
        <SectionCard>
          <EmptyState
            icon={<TriangleAlert className="h-6 w-6" />}
            title="这个窗口里没有数据"
            description={emptyText(overview)}
          />
        </SectionCard>
      )}

      {/* ── 指标卡 ───────────────────────────────────────────────────── */}
      {state === 'ready' && overview && (
        <SectionCard
          title="核心经营指标"
          description="每个数字都能点进去看构成明细；口径写在卡片上，不在代码注释里。"
        >
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {overview.cards.map((card) => (
              <MetricCardView
                key={card.metricCode}
                card={card}
                definition={definitionOf.get(card.metricCode)}
                onDrilldown={() => void openDrilldown(card.metricCode)}
              />
            ))}
          </div>
          {overview.currencyNote && (
            <p className="text-copy-13 text-muted-foreground mt-4">{overview.currencyNote}</p>
          )}
          {overview.warnings.length > 0 && (
            <ul className="text-copy-13 text-muted-foreground mt-3 space-y-1">
              {overview.warnings.map((warning) => (
                <li key={warning}>· {warning}</li>
              ))}
            </ul>
          )}
        </SectionCard>
      )}

      {/* ── 趋势图 ───────────────────────────────────────────────────── */}
      {state === 'ready' && series.length > 0 && (
        <SectionCard title="趋势" description="只画有事实的那几天；没有数据的一天不是 0，是没有点。">
          <div className="space-y-4">
            {series.map((line) => (
              <TrendLineView
                key={`${line.metricCode}|${line.currency}`}
                title={`${line.metricCode} · ${line.currency}`}
                points={line.points}
              />
            ))}
          </div>
        </SectionCard>
      )}

      {/* ── 下钻 ─────────────────────────────────────────────────────── */}
      {drillMetric && (
        <SectionCard
          title={`下钻 · ${drilldown?.metricName ?? drillMetric}`}
          description="这个汇总数字是由哪些订单 / SKU / 活动构成的。"
          extra={
            <Button variant="outline" size="sm" onClick={() => setDrilldown(null)}>
              收起
            </Button>
          }
        >
          {!drilldown ? (
            <EmptyState title="正在读取构成明细…" />
          ) : drilldown.items.length === 0 ? (
            <EmptyState
              title="这个指标没有构成明细"
              description="该指标在这个时间窗口与维度下没有可下钻的明细行。"
            />
          ) : (
            <table className="w-full text-left text-sm">
              <thead className="text-copy-13 text-muted-foreground">
                <tr>
                  <th className="py-2 pr-3 font-medium">构成</th>
                  <th className="py-2 pr-3 font-medium">类型</th>
                  <th className="py-2 pr-3 font-medium">归属日</th>
                  <th className="py-2 font-medium">贡献</th>
                </tr>
              </thead>
              <tbody>
                {drilldown.items.map((row) => (
                  <tr key={`${row.sourceKind}|${row.referenceId}|${row.currency}`} className="border-border border-t">
                    <td className="py-2 pr-3">
                      <div className="text-foreground">{row.referenceLabel || row.referenceId}</div>
                      <div className="text-copy-13 text-muted-foreground">{row.referenceId}</div>
                    </td>
                    <td className="text-copy-13 text-muted-foreground py-2 pr-3">
                      {drilldownSourceLabel(row.sourceKind)}
                    </td>
                    <td className="text-copy-13 text-muted-foreground py-2 pr-3">{row.bucketDate}</td>
                    <td className="py-2 tabular-nums">{drilldownRowText(row)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </SectionCard>
      )}
    </div>
  );
}

/** 一张指标卡（含环比 / 同比与口径说明）。 */
function MetricCardView({
  card,
  definition,
  onDrilldown,
}: {
  card: MetricCard;
  // ⚠️ 显式写成 `| undefined`（不是 `?:`）：本仓开了 exactOptionalPropertyTypes，
  // `definition?: T` 不接受「明确传一个 undefined 进来」。
  definition: MetricDefinitionView | undefined;
  onDrilldown: () => void;
}) {
  const rows = cardValueRows(card);
  const base = baseTotalText(card);
  const previous = card.previousPeriod;
  const direction = comparisonDirection(previous);

  // 🔴 不可比时 delta 不传（StatCard 只在 delta 是 number 时才渲染变化徽标），
  //    而是比较文本单独写出来 —— 「不可比」不是 0%。
  const delta = previous.comparable && previous.changeRate !== null ? previous.changeRate : undefined;
  const deltaDirection =
    direction === 'up' ? 'up' : direction === 'down' ? 'down' : direction === 'flat' ? 'flat' : 'flat';

  return (
    <div className="border-border bg-card rounded-lg border p-4">
      <div className="text-copy-13 text-muted-foreground flex items-start justify-between gap-2">
        <span>{card.displayName}</span>
        {rows.length > 0 && (
          <button
            type="button"
            onClick={onDrilldown}
            className="text-copy-13 text-foreground underline underline-offset-2"
          >
            下钻
          </button>
        )}
      </div>

      <div className="mt-2 space-y-1">
        {rows.length === 0 ? (
          <div className="text-h-24 text-muted-foreground tracking-tight">—</div>
        ) : (
          rows.map((row) => (
            <div key={row} className="text-h-24 text-foreground tracking-tight tabular-nums">
              {row}
            </div>
          ))
        )}
        {base && <div className="text-copy-13 text-muted-foreground">{base}</div>}
      </div>

      {rows.length === 0 && (
        <div className="text-copy-13 text-muted-foreground mt-1">{availabilityText(card)}</div>
      )}

      {rows.length > 0 && (
        <div className="mt-2 flex flex-wrap items-center gap-2 text-copy-13">
          {/* 🔴 涨跌**不判好坏**：涨是好事还是坏事取决于指标（退货率涨是坏事），
              页面不替用户决定，只把方向与数字如实显示。 */}
          <Badge tone="neutral">{comparisonText(previous)}</Badge>
          <span className="text-muted-foreground">{comparisonWindowText(previous)}</span>
        </div>
      )}

      <div className="text-copy-13 text-muted-foreground mt-2 space-y-0.5">
        <div>口径：{aggregationLabel(card.aggregation)}</div>
        {definition && <div>定义：{definitionFormula(definition)}</div>}
        {card.ownerModule && <div>归属模块：{card.ownerModule}</div>}
      </div>
    </div>
  );
}

/** 一根趋势线（纯 SVG，不用图表库 —— 数据点少、坐标轴简单）。 */
function TrendLineView({
  title,
  points,
}: {
  title: string;
  points: { date: string; value: number }[];
}) {
  const width = 720;
  const height = 120;
  const padding = 16;
  const max = seriesMax({ metricCode: title, currency: '', points });
  if (max === null || max === 0) {
    return (
      <div className="text-copy-13 text-muted-foreground">
        {title}：没有可画的点（没有事实的那天不是 0，是没有点）。
      </div>
    );
  }
  const step = points.length > 1 ? (width - padding * 2) / (points.length - 1) : 0;
  const coords = points.map((point, index) => ({
    x: padding + index * step,
    y: height - padding - (point.value / max) * (height - padding * 2),
    point,
  }));
  const path = coords.map((c, i) => `${i === 0 ? 'M' : 'L'}${c.x.toFixed(2)},${c.y.toFixed(2)}`).join(' ');

  return (
    <div>
      <div className="text-copy-13 text-foreground">{title}</div>
      <svg viewBox={`0 0 ${width} ${height}`} className="h-32 w-full" role="img" aria-label={title}>
        <path d={path} fill="none" stroke="currentColor" strokeWidth={1.5} className="text-foreground" />
        {coords.map((c) => (
          <circle key={c.point.date} cx={c.x} cy={c.y} r={2.5} className="fill-foreground" />
        ))}
      </svg>
      <div className="text-copy-13 text-muted-foreground flex justify-between">
        <span>{points[0]?.date}</span>
        <span>{points[points.length - 1]?.date}</span>
      </div>
    </div>
  );
}
