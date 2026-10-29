'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { api } from '@/lib/api';
import {
  DENOMINATOR_OPTIONS,
  DIMENSION_OPTIONS,
  GRANULARITY_OPTIONS,
  amountText,
  anomalyDirectionLabel,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  deltaText,
  effectiveThresholdText,
  emptyText,
  pageState,
  periodText,
  rangeText,
  rowStatusLabel,
  rowTone,
  seriesBounds,
  shareBarWidth,
  shareText,
  structureTrendSeries,
  thresholdModeLabel,
} from './cost-structure-model';
import type {
  CostStructureCapabilities,
  CostStructureResponse,
  CostStructureRule,
} from './cost-structure-model';

/**
 * 13.4.2.1 成本结构工作区（卷 13「利润与经营分析」L3 13.4.2 成本拆解下的第一格）。
 *
 * <p>蓝图输出：「占比结构图 + 趋势 + 异常提示。」
 *
 * <p><b>数据来自 reporting 服务（13 卷的唯一归属服务）的投影读模型</b>：
 *
 * <ul>
 *   <li>{@code GET reporting/cost-structure/structure} —— 占比结构表 + 环比 / 同比基线 + 异常提示；</li>
 *   <li>{@code GET reporting/cost-structure/trend} —— 占比趋势（按期间粒度）；</li>
 *   <li>{@code GET reporting/cost-structure/anomalies} —— 异常提示清单；</li>
 *   <li>{@code GET reporting/cost-structure/rules} —— 异常判定阈值（可配置项）；</li>
 *   <li>{@code GET reporting/cost-structure/capabilities} —— 能力声明（哪些上游真接通、哪些确实拿不到）。</li>
 * </ul>
 *
 * <p><b>🔴 页面必须做到的五件事</b>：
 *
 * <ol>
 *   <li><b>数据更新时间常驻</b>（卷 13 卷首加粗句「数据延迟要显示」）：顶部横幅任何时候都显示
 *       「数据截至 X」，加载中 / 出错时也显示（未投影时显示「尚未投影任何成本事实」）。</li>
 *   <li><b>「算不出来」≠「占比是 0」</b>：{@code null} 的占比 / 环比差 / 同比差显示「—」与「不可比」，
 *       并把服务端给的原因原样带出来，**绝不用 0 顶替**。</li>
 *   <li><b>分母口径写在页面上</b>：「占总成本」与「占收入」是两个口径，混着读会得出错的结论。</li>
 *   <li><b>多币种不相加</b>：每一行带自己的币种。</li>
 *   <li><b>能力声明常驻</b>：「哪些拆不出来」必须写在页面上，而不是让用户以为每一格都有占比。</li>
 * </ol>
 */
export function AnalyticsCostStructureWorkspace() {
  const today = new Date().toISOString().slice(0, 10);
  const initialRange = defaultRange(today);

  const [from, setFrom] = useState(initialRange.from);
  const [to, setTo] = useState(initialRange.to);
  const [granularity, setGranularity] = useState('DAY');
  const [dimension, setDimension] = useState('ALL');
  const [denominatorCaliber, setDenominatorCaliber] = useState('TOTAL_COST');

  const [data, setData] = useState<CostStructureResponse | null>(null);
  const [capabilities, setCapabilities] = useState<CostStructureCapabilities | null>(null);
  const [rules, setRules] = useState<CostStructureRule[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  const query = useMemo(() => {
    const params = new URLSearchParams({
      from,
      to,
      granularity,
      dimension,
      denominatorCaliber,
    });
    return params.toString();
  }, [from, to, granularity, dimension, denominatorCaliber]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [structure, caps, thresholdRules] = await Promise.all([
        api<CostStructureResponse>(`reporting/cost-structure/structure?${query}`),
        api<CostStructureCapabilities>('reporting/cost-structure/capabilities'),
        api<CostStructureRule[]>('reporting/cost-structure/rules'),
      ]);
      setData(structure);
      setCapabilities(caps);
      setRules(thresholdRules);
    } catch (e) {
      setData(null);
      setError(e instanceof Error ? e.message : '读取失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [query]);

  useEffect(() => {
    void load();
  }, [load]);

  const state = pageState(data, error);
  const series = useMemo(() => structureTrendSeries(data?.trend ?? []), [data]);

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
          <Button variant="outline" size="sm" disabled={loading} onClick={() => void load()}>
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            刷新
          </Button>
        </div>
      </div>

      {/* ── 口径说明（分母口径写死在页面上）──────────────────────────────── */}
      {data && (
        <div className="border-border bg-muted/40 text-copy-13 text-muted-foreground rounded-lg border px-4 py-3">
          <div>{data.denominatorNote}</div>
          <div className="mt-1">{data.caliberNote}</div>
        </div>
      )}

      {/* ── 时间范围与筛选 ─────────────────────────────────────────────── */}
      <SectionCard
        title="时间范围与筛选"
        description="时间范围决定期间窗口与基线窗口；维度与分母口径只影响取数，不改变口径。"
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
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            归属维度
            <select
              value={dimension}
              onChange={(e) => setDimension(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              {DIMENSION_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            分母口径
            <select
              value={denominatorCaliber}
              onChange={(e) => setDenominatorCaliber(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              {DENOMINATOR_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            期间粒度
            <select
              value={granularity}
              onChange={(e) => setGranularity(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              {GRANULARITY_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
        </div>
      </SectionCard>

      {/* ── 异常提示 ───────────────────────────────────────────────────── */}
      {data && data.warnings.length > 0 && (
        <SectionCard title="口径提示">
          <ul className="space-y-1.5">
            {data.warnings.map((warning) => (
              <li key={warning} className="text-copy-13 text-foreground flex items-start gap-2">
                <TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                <span>{warning}</span>
              </li>
            ))}
          </ul>
        </SectionCard>
      )}

      {state === 'error' && (
        <SectionCard>
          <EmptyState
            icon={<TriangleAlert className="h-6 w-6" />}
            title="读取失败"
            description={error}
            action={
              <Button variant="outline" size="sm" onClick={() => void load()}>
                <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
                重试
              </Button>
            }
          />
        </SectionCard>
      )}

      {state === 'loading' && (
        <SectionCard>
          <EmptyState title="正在读取成本结构…" description="读取期间页面上的「数据截至」仍然有效。" />
        </SectionCard>
      )}

      {state === 'empty' && (
        <SectionCard>
          <EmptyState title="没有成本结构数据" description={emptyText(data)} />
        </SectionCard>
      )}

      {state === 'ready' && data && (
        <>
          {/* ── 占比结构表 ───────────────────────────────────────────────── */}
          <SectionCard
            title="成本占比结构"
            description="一行 = 一个成本类别 × 一个归属维度 × 一个币种。占比 = 该类成本 ÷ 分母（分母口径见页面顶部）。"
          >
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="text-copy-13 text-muted-foreground">
                  <tr className="border-border border-b">
                    <th className="py-2 pr-3 font-medium">成本类别</th>
                    <th className="py-2 pr-3 font-medium">归属维度</th>
                    <th className="py-2 pr-3 font-medium">币种</th>
                    <th className="py-2 pr-3 text-right font-medium">金额</th>
                    <th className="py-2 pr-3 text-right font-medium">占比</th>
                    <th className="py-2 pr-3 font-medium">结构</th>
                    <th className="py-2 pr-3 text-right font-medium">环比基线</th>
                    <th className="py-2 pr-3 text-right font-medium">占比环比</th>
                    <th className="py-2 pr-3 text-right font-medium">同比基线</th>
                    <th className="py-2 pr-3 text-right font-medium">占比同比</th>
                    <th className="py-2 pr-3 font-medium">判定</th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((r, index) => (
                    <tr
                      key={`${r.costCategory}|${r.dimensionKey}|${r.currency}|${index}`}
                      className="border-border border-b"
                    >
                      <td className="py-2 pr-3">
                        <div>{r.costCategoryLabel}</div>
                        <div className="text-copy-13 text-muted-foreground">{r.costCategory}</div>
                      </td>
                      <td className="py-2 pr-3">{r.dimensionLabel}</td>
                      <td className="py-2 pr-3">{r.currency}</td>
                      <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                        {amountText(r.amount, r.currency)}
                      </td>
                      <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                        {shareText(r.share)}
                      </td>
                      <td className="py-2 pr-3">
                        {r.share === null ? (
                          <span className="text-copy-13 text-muted-foreground">—</span>
                        ) : (
                          <div className="bg-muted h-2 w-32 overflow-hidden rounded-full">
                            <div
                              className="bg-primary h-2 rounded-full"
                              style={{ width: `${shareBarWidth(r.share)}%` }}
                            />
                          </div>
                        )}
                      </td>
                      <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                        {shareText(r.previousShare)}
                      </td>
                      <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                        {deltaText(r.deltaVsPrevious)}
                      </td>
                      <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                        {shareText(r.yoyShare)}
                      </td>
                      <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                        {deltaText(r.deltaVsYoY)}
                      </td>
                      <td className="py-2 pr-3">
                        <Badge tone={rowTone(r)}>{rowStatusLabel(r)}</Badge>
                        <div className="text-copy-13 text-muted-foreground mt-1 max-w-xs">
                          {r.anomaly ? r.anomalyReason : r.note}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </SectionCard>

          {/* ── 异常提示清单 ─────────────────────────────────────────────── */}
          <SectionCard
            title="异常提示"
            description="占比相对**历史基线**（前 N 期同口径占比）的跳变。阈值可配置，不是写死的百分比。"
          >
            {data.anomalies.length === 0 ? (
              <p className="text-copy-13 text-muted-foreground">
                这个窗口里没有达到阈值的成本类别（也可能是历史期数不足而**判不了** —— 判不了不等于正常）。
              </p>
            ) : (
              <div className="space-y-3">
                {data.anomalies.map((a, index) => (
                  <div
                    key={`${a.costCategory}|${a.dimensionKey}|${a.currency}|${index}`}
                    className="border-border rounded-lg border px-4 py-3"
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-copy-14 text-foreground font-medium">
                        {a.costCategoryLabel}
                      </span>
                      <Badge tone="danger">{anomalyDirectionLabel(a.direction)}</Badge>
                      <span className="text-copy-13 text-muted-foreground">{a.dimensionLabel}</span>
                      <span className="text-copy-13 text-muted-foreground">{a.currency}</span>
                    </div>
                    <dl className="text-copy-13 mt-3 grid grid-cols-1 gap-x-6 gap-y-1 sm:grid-cols-2 lg:grid-cols-4">
                      <div>
                        <dt className="text-muted-foreground">当前占比</dt>
                        <dd className="text-foreground tabular-nums">{shareText(a.share)}</dd>
                      </div>
                      <div>
                        <dt className="text-muted-foreground">历史基线占比</dt>
                        <dd className="text-foreground tabular-nums">{shareText(a.baselineShare)}</dd>
                      </div>
                      <div>
                        <dt className="text-muted-foreground">占比变化</dt>
                        <dd className="text-foreground tabular-nums">{deltaText(a.delta)}</dd>
                      </div>
                      <div>
                        <dt className="text-muted-foreground">生效阈值</dt>
                        <dd className="text-foreground tabular-nums">{effectiveThresholdText(a)}</dd>
                      </div>
                    </dl>
                    <div className="text-copy-13 text-muted-foreground mt-2">
                      阈值口径：{thresholdModeLabel(a.thresholdMode)} · {a.reason}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          {/* ── 占比趋势 ─────────────────────────────────────────────────── */}
          <SectionCard
            title="占比趋势"
            description="**不可比的期间没有点**（不是 0%）。零线 = 这一类成本没有占比。"
          >
            {series.length === 0 ? (
              <p className="text-copy-13 text-muted-foreground">
                没有可画的点（这段时间里没有分母算得出来的期间）。
              </p>
            ) : (
              <div className="space-y-4">
                {series.map((line) => (
                  <StructureTrendLine key={line.key} series={line} />
                ))}
              </div>
            )}
          </SectionCard>

          {/* ── 阈值规则（可配置项）──────────────────────────────────────── */}
          <SectionCard
            title="异常判定阈值"
            description="阈值落在租户自己的规则表里：固定百分点（FIXED）或基线标准差的若干倍（STDDEV）。"
          >
            {rules.length === 0 ? (
              <p className="text-copy-13 text-muted-foreground">还没有读取到阈值规则。</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="text-copy-13 text-muted-foreground">
                    <tr className="border-border border-b">
                      <th className="py-2 pr-3 font-medium">成本类别</th>
                      <th className="py-2 pr-3 font-medium">阈值口径</th>
                      <th className="py-2 pr-3 text-right font-medium">阈值取值</th>
                      <th className="py-2 pr-3 text-right font-medium">最少历史期数</th>
                      <th className="py-2 pr-3 font-medium">判定方向</th>
                      <th className="py-2 pr-3 font-medium">状态</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rules.map((rule) => (
                      <tr
                        key={rule.costCategory}
                        className="border-border border-b"
                      >
                        <td className="py-2 pr-3">{rule.costCategoryLabel}</td>
                        <td className="py-2 pr-3">{thresholdModeLabel(rule.thresholdMode)}</td>
                        <td className="py-2 pr-3 text-right tabular-nums">{rule.thresholdValue}</td>
                        <td className="py-2 pr-3 text-right tabular-nums">{rule.minHistoryPeriods}</td>
                        <td className="py-2 pr-3">{anomalyDirectionLabel(rule.direction)}</td>
                        <td className="py-2 pr-3">
                          <Badge tone={rule.enabled ? 'success' : 'neutral'}>
                            {rule.enabled ? '启用' : '停用'}
                          </Badge>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </SectionCard>

          {/* ── 能力声明 ─────────────────────────────────────────────────── */}
          {capabilities && (
            <SectionCard title="能力声明" description="哪些上游真接通、哪些确实拆不出来、归谁。">
              <div className="space-y-3">
                <div>
                  <div className="text-copy-13 text-muted-foreground">上游（真的读过）</div>
                  <ul className="text-copy-13 mt-1 space-y-0.5">
                    {capabilities.upstreamModules.map((module) => (
                      <li key={module}>· {module}</li>
                    ))}
                  </ul>
                </div>
                <div>
                  <div className="text-copy-13 text-muted-foreground">确实拿不到的部分</div>
                  <ul className="text-copy-13 mt-1 space-y-0.5">
                    {capabilities.notDelivered.map((item) => (
                      <li key={item}>· {item}</li>
                    ))}
                  </ul>
                </div>
                <div className="text-copy-13 text-muted-foreground">{capabilities.note}</div>
              </div>
            </SectionCard>
          )}
        </>
      )}
    </div>
  );
}

/** 一根占比趋势线。 */
function StructureTrendLine({
  series,
}: {
  series: {
    key: string;
    costCategoryLabel: string;
    currency: string;
    points: { periodStart: string; periodEnd: string; share: number }[];
  };
}) {
  const bounds = seriesBounds(series.points);
  return (
    <div>
      <div className="text-copy-13 text-foreground flex items-center gap-2">
        <span className="font-medium">{series.costCategoryLabel}</span>
        <span className="text-muted-foreground">{series.currency}</span>
      </div>
      <div className="bg-muted mt-1 flex h-8 items-end gap-1 overflow-hidden rounded-md px-2 py-1">
        {series.points.map((point) => {
          const ratio = bounds && bounds.max > bounds.min ? (point.share - bounds.min) / (bounds.max - bounds.min) : 0;
          return (
            <div
              key={`${point.periodStart}|${point.periodEnd}`}
              className="bg-primary w-3 shrink-0 rounded-sm"
              style={{ height: `${Math.max(6, Math.round(ratio * 100))}%` }}
              title={`${periodText(point.periodStart, point.periodEnd)}：${shareText(point.share)}`}
            />
          );
        })}
      </div>
      <div className="text-copy-13 text-muted-foreground mt-1">
        {series.points.length} 个可比期间 · 范围 {bounds ? `${bounds.min}% ~ ${bounds.max}%` : '—'}
      </div>
    </div>
  );
}
