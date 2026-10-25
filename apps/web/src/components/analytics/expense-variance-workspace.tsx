'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { api } from '@/lib/api';
import {
  FEE_TYPE_OPTIONS,
  GRANULARITY_OPTIONS,
  amountText,
  biasDirection,
  biasLabel,
  biasShortLabel,
  biasTone,
  comparableNote,
  currentRateText,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  emptyText,
  impliedRateText,
  pageState,
  periodText,
  rangeText,
  rateText,
  recommendationDirectionLabel,
  recommendationTone,
  seriesBounds,
  skuPageNote,
  suggestedRateText,
  unmatchedAmountText,
  unmatchedReasonText,
  varianceText,
  varianceTrendSeries,
} from './expense-variance-model';
import type {
  ExpenseSkuVariancePage,
  ExpenseVarianceCapabilities,
  ExpenseVarianceResponse,
} from './expense-variance-model';

/**
 * 13.4.2.2 费用差异工作区（卷 13「利润与经营分析」L3 13.4.2 成本拆解下的第二格）。
 *
 * <p>蓝图输出：「差异表 + 偏差趋势 + 规则调优建议。」
 *
 * <p><b>数据来自 reporting 服务（13 卷的唯一归属服务）的投影读模型</b>：
 *
 * <ul>
 *   <li>{@code GET reporting/expense-variance/variance} —— 差异表 + 有实际无预计 + 偏差趋势 + 规则调优建议；</li>
 *   <li>{@code GET reporting/expense-variance/by-sku} —— SKU 级差异（真分页 + 真计数）；</li>
 *   <li>{@code GET reporting/expense-variance/capabilities} —— 能力声明（哪些上游真接通、哪些确实拿不到）。</li>
 * </ul>
 *
 * <p><b>🔴 页面必须做到的五件事</b>：
 *
 * <ol>
 *   <li><b>数据更新时间常驻</b>（卷 13 卷首加粗句「数据延迟要显示」）：顶部横幅任何时候都显示
 *       「数据截至 X」，加载中 / 出错时也显示（未投影时显示「尚未投影任何费用事实」）。</li>
 *   <li><b>「算不出来」≠「差异是 0」</b>：{@code null} 的差异额 / 差异率显示「—」与「不可比」，
 *       并把服务端给的原因原样带出来，**绝不用 0 顶替**。</li>
 *   <li><b>符号口径写在页面上</b>：差异 = 实际 − 预计；差异为负 ⇒ 预计高估，为正 ⇒ 预计低估。</li>
 *   <li><b>多币种不相加</b>：每一行带自己的币种。</li>
 *   <li><b>能力声明常驻</b>：「哪些算不出来」必须写在页面上，而不是让用户以为每一格都有差异率。</li>
 * </ol>
 */
export function AnalyticsExpenseVarianceWorkspace() {
  const today = new Date().toISOString().slice(0, 10);
  const initialRange = defaultRange(today);

  const [from, setFrom] = useState(initialRange.from);
  const [to, setTo] = useState(initialRange.to);
  const [granularity, setGranularity] = useState('DAY');
  const [feeType, setFeeType] = useState('');

  const [data, setData] = useState<ExpenseVarianceResponse | null>(null);
  const [capabilities, setCapabilities] = useState<ExpenseVarianceCapabilities | null>(null);
  const [skuPage, setSkuPage] = useState<ExpenseSkuVariancePage | null>(null);
  const [skuPageIndex, setSkuPageIndex] = useState(0);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const query = useMemo(() => {
    const params = new URLSearchParams({ from, to, granularity });
    if (feeType) params.set('feeType', feeType);
    return params.toString();
  }, [from, to, granularity, feeType]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [variance, caps] = await Promise.all([
        api<ExpenseVarianceResponse>(`reporting/expense-variance/variance?${query}`),
        api<ExpenseVarianceCapabilities>('reporting/expense-variance/capabilities'),
      ]);
      setData(variance);
      setCapabilities(caps);
    } catch (e) {
      setData(null);
      setError(e instanceof Error ? e.message : '读取失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [query]);

  const loadSku = useCallback(
    async (page: number) => {
      setBusy(true);
      try {
        const params = new URLSearchParams({ from, to, page: String(page), size: '50' });
        if (feeType) params.set('feeType', feeType);
        setSkuPage(await api<ExpenseSkuVariancePage>(`reporting/expense-variance/by-sku?${params}`));
        setSkuPageIndex(page);
      } catch (e) {
        setSkuPage(null);
        setError(e instanceof Error ? e.message : '读取 SKU 级差异失败，请重试');
      } finally {
        setBusy(false);
      }
    },
    [from, to, feeType],
  );

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void loadSku(0);
  }, [loadSku]);

  const state = pageState(data, error);
  const series = useMemo(() => varianceTrendSeries(data?.trend ?? []), [data]);

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
          <Button variant="outline" size="sm" disabled={loading || busy} onClick={() => void load()}>
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            刷新
          </Button>
        </div>
      </div>

      {/* ── 口径说明（符号方向写死在页面上）────────────────────────────── */}
      {data && (
        <div className="border-border bg-muted/40 text-copy-13 text-muted-foreground rounded-lg border px-4 py-3">
          {data.varianceSignNote}
        </div>
      )}

      {/* ── 时间范围与筛选 ─────────────────────────────────────────────── */}
      <SectionCard
        title="时间范围与筛选"
        description="时间范围决定期间窗口；费用类型与粒度只影响取数，不改变口径。"
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
            费用类型
            <select
              value={feeType}
              onChange={(e) => setFeeType(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              {FEE_TYPE_OPTIONS.map((option) => (
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
        <SectionCard title="异常提示">
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
          <EmptyState title="正在读取费用差异…" description="读取期间页面上的「数据截至」仍然有效。" />
        </SectionCard>
      )}

      {state === 'empty' && (
        <SectionCard>
          <EmptyState title="没有费用差异数据" description={emptyText(data)} />
        </SectionCard>
      )}

      {state === 'ready' && data && (
        <>
          {/* ── 差异表 ─────────────────────────────────────────────────── */}
          <SectionCard
            title="差异表"
            description="一行 = 一个费用类型 × 归属维度 × 期间。差异 = 实际 − 预计。"
          >
            {data.rows.length === 0 ? (
              <p className="text-copy-13 text-muted-foreground">
                这个窗口里没有可比的差异行（见下面的「有实际、无预计」）。
              </p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="text-copy-13 text-muted-foreground">
                    <tr className="border-border border-b">
                      <th className="py-2 pr-3 font-medium">费用类型</th>
                      <th className="py-2 pr-3 font-medium">期间</th>
                      <th className="py-2 pr-3 font-medium">归属维度</th>
                      <th className="py-2 pr-3 text-right font-medium">预计费用</th>
                      <th className="py-2 pr-3 text-right font-medium">实际费用</th>
                      <th className="py-2 pr-3 text-right font-medium">差异</th>
                      <th className="py-2 pr-3 text-right font-medium">差异率</th>
                      <th className="py-2 pr-3 font-medium">偏差方向</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.rows.map((r, index) => {
                      const direction = biasDirection(r.varianceRate);
                      return (
                        <tr key={`${r.feeType}|${r.skuCode}|${r.periodStart}|${r.currency}|${index}`} className="border-border border-b">
                          <td className="py-2 pr-3">
                            <div>{r.feeTypeLabel}</div>
                            {r.feeSubType && (
                              <div className="text-copy-13 text-muted-foreground">{r.feeSubType}</div>
                            )}
                          </td>
                          <td className="py-2 pr-3 whitespace-nowrap">
                            {periodText(r.periodStart, r.periodEnd)}
                          </td>
                          <td className="py-2 pr-3">
                            <div>{r.scopeLabel}</div>
                            <div className="text-copy-13 text-muted-foreground">
                              预计 {r.estimateCount} 笔 / 实际 {r.actualCount} 笔
                            </div>
                          </td>
                          <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                            {amountText(r.estimatedAmount, r.currency)}
                          </td>
                          <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                            {amountText(r.actualAmount, r.currency)}
                          </td>
                          <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                            {varianceText(r.varianceAmount, r.currency)}
                          </td>
                          <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                            {rateText(r.varianceRate)}
                          </td>
                          <td className="py-2 pr-3">
                            <Badge tone={biasTone(direction)}>{biasShortLabel(direction)}</Badge>
                            <div className="text-copy-13 text-muted-foreground mt-1 max-w-xs">
                              {comparableNote(r)}
                            </div>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </SectionCard>

          {/* ── 有实际、无预计 ─────────────────────────────────────────── */}
          <SectionCard
            title="有实际、无预计"
            description="这些行**没有可比的预计费用**，差异率算不出来（不是 0%）。"
          >
            {data.unmatchedActuals.length === 0 ? (
              <p className="text-copy-13 text-muted-foreground">
                每一组实际费用都找到了可比的预计费用。
              </p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="text-copy-13 text-muted-foreground">
                    <tr className="border-border border-b">
                      <th className="py-2 pr-3 font-medium">费用类型</th>
                      <th className="py-2 pr-3 font-medium">归属维度</th>
                      <th className="py-2 pr-3 text-right font-medium">实际费用</th>
                      <th className="py-2 pr-3 text-right font-medium">笔数</th>
                      <th className="py-2 pr-3 font-medium">来源</th>
                      <th className="py-2 pr-3 font-medium">为什么算不出差异</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.unmatchedActuals.map((r, index) => (
                      <tr key={`${r.feeType}|${r.scopeLabel}|${r.currency}|${index}`} className="border-border border-b">
                        <td className="py-2 pr-3">
                          <div>{r.feeTypeLabel}</div>
                          {r.feeSubType && (
                            <div className="text-copy-13 text-muted-foreground">{r.feeSubType}</div>
                          )}
                        </td>
                        <td className="py-2 pr-3">{r.scopeLabel}</td>
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          {unmatchedAmountText(r)}
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums">{r.actualCount}</td>
                        <td className="py-2 pr-3">{r.actualSource}</td>
                        <td className="py-2 pr-3 text-muted-foreground">{unmatchedReasonText(r)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </SectionCard>

          {/* ── 偏差趋势 ───────────────────────────────────────────────── */}
          <SectionCard
            title="偏差趋势"
            description="零线 = 预计与实际一致。**不可比的期间没有点**（不是 0%）。"
          >
            {series.length === 0 ? (
              <p className="text-copy-13 text-muted-foreground">
                没有可画的点（这段时间里没有可比的两侧数据）。
              </p>
            ) : (
              <div className="space-y-4">
                {series.map((line) => (
                  <VarianceTrendLine key={line.key} series={line} />
                ))}
              </div>
            )}
          </SectionCard>

          {/* ── 规则调优建议 ───────────────────────────────────────────── */}
          <SectionCard
            title="规则调优建议"
            description="只给**系统性**偏差（差异率绝对值达阈值且可比样本够多）。下游是 5.2.3 的规则校准。"
          >
            {data.recommendations.length === 0 ? (
              <p className="text-copy-13 text-muted-foreground">
                这个窗口里没有达到「系统性偏差」门槛的费用类型（样本不够或偏差在阈值内）。
              </p>
            ) : (
              <div className="space-y-3">
                {data.recommendations.map((r, index) => (
                  <div key={`${r.feeType}|${r.feeSubType}|${r.scopeLabel}|${r.currency}|${index}`} className="border-border rounded-lg border px-4 py-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-copy-14 text-foreground font-medium">
                        {r.feeTypeLabel}
                        {r.feeSubType ? ` · ${r.feeSubType}` : ''}
                      </span>
                      <Badge tone={recommendationTone(r.direction)}>
                        {recommendationDirectionLabel(r.direction)}
                      </Badge>
                      <span className="text-copy-13 text-muted-foreground">{r.scopeLabel}</span>
                      <span className="text-copy-13 text-muted-foreground">{r.currency}</span>
                    </div>
                    <dl className="text-copy-13 mt-3 grid grid-cols-1 gap-x-6 gap-y-1 sm:grid-cols-2 lg:grid-cols-4">
                      <div>
                        <dt className="text-muted-foreground">差异率</dt>
                        <dd className="text-foreground tabular-nums">{rateText(r.varianceRate)}</dd>
                      </div>
                      <div>
                        <dt className="text-muted-foreground">当前有效费率</dt>
                        <dd className="text-foreground tabular-nums">{currentRateText(r)}</dd>
                      </div>
                      <div>
                        <dt className="text-muted-foreground">实际隐含费率</dt>
                        <dd className="text-foreground tabular-nums">{impliedRateText(r)}</dd>
                      </div>
                      <div>
                        <dt className="text-muted-foreground">建议费率</dt>
                        <dd className="text-foreground tabular-nums">{suggestedRateText(r)}</dd>
                      </div>
                    </dl>
                    <div className="text-copy-13 text-muted-foreground mt-2">
                      可比样本 {r.sampleCount} 笔 · 去哪个模块改规则：{r.ownerModule}
                    </div>
                    <div className="text-copy-13 text-foreground mt-1">{r.suggestion}</div>
                    <div className="text-copy-13 text-muted-foreground mt-1">{r.reason}</div>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          {/* ── SKU 级差异 ─────────────────────────────────────────────── */}
          <SectionCard
            title="SKU 级差异"
            description="按 SKU 与期间展示差异率（真分页 + 真计数）。"
            extra={
              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={busy || skuPageIndex <= 0}
                  onClick={() => void loadSku(skuPageIndex - 1)}
                >
                  上一页
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={busy || !skuPage || skuPageIndex + 1 >= Math.ceil(skuPage.total / skuPage.size)}
                  onClick={() => void loadSku(skuPageIndex + 1)}
                >
                  下一页
                </Button>
              </div>
            }
          >
            {skuPage && <p className="text-copy-13 text-muted-foreground mb-3">{skuPageNote(skuPage)}</p>}
            {!skuPage || skuPage.items.length === 0 ? (
              <p className="text-copy-13 text-muted-foreground">
                没有 SKU 级差异行。目前只有**头程分摊**两侧都带 SKU（上游的平台费 / 物流费台账只到平台、渠道维度）。
              </p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="text-copy-13 text-muted-foreground">
                    <tr className="border-border border-b">
                      <th className="py-2 pr-3 font-medium">SKU</th>
                      <th className="py-2 pr-3 font-medium">费用类型</th>
                      <th className="py-2 pr-3 font-medium">期间</th>
                      <th className="py-2 pr-3 text-right font-medium">预计费用</th>
                      <th className="py-2 pr-3 text-right font-medium">实际费用</th>
                      <th className="py-2 pr-3 text-right font-medium">差异</th>
                      <th className="py-2 pr-3 text-right font-medium">差异率</th>
                    </tr>
                  </thead>
                  <tbody>
                    {skuPage.items.map((r, index) => (
                      <tr key={`${r.skuCode}|${r.feeType}|${r.periodStart}|${index}`} className="border-border border-b">
                        <td className="py-2 pr-3">{r.skuCode}</td>
                        <td className="py-2 pr-3">
                          <div>{r.feeTypeLabel}</div>
                          {r.feeSubType && (
                            <div className="text-copy-13 text-muted-foreground">{r.feeSubType}</div>
                          )}
                        </td>
                        <td className="py-2 pr-3 whitespace-nowrap">
                          {periodText(r.periodStart, r.periodEnd)}
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          {amountText(r.estimatedAmount, r.currency)}
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          {amountText(r.actualAmount, r.currency)}
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          {varianceText(r.varianceAmount, r.currency)}
                        </td>
                        <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                          {rateText(r.varianceRate)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </SectionCard>

          {/* ── 能力声明 ───────────────────────────────────────────────── */}
          {capabilities && (
            <SectionCard
              title="能力声明"
              description="哪些上游真接通、哪些**确实算不出差异**、归谁。算不出来的事必须写出来。"
            >
              <div className="text-copy-13 text-muted-foreground space-y-2">
                <div>
                  上游接通：
                  {capabilities.upstreamConnected ? '是' : '否'} · 覆盖费用类型：
                  {capabilities.feeTypes.join('、')}
                </div>
                <div>读过的上游模块：{capabilities.upstreamModules.join('、')}</div>
                <div>
                  触发建议的差异率阈值：{capabilities.recommendationThreshold.toFixed(2)}% ·
                  最少可比样本：{capabilities.recommendationMinSamples} 笔
                </div>
                <div>{capabilities.note}</div>
              </div>
              <ul className="mt-3 space-y-1.5">
                {capabilities.notDelivered.map((item) => (
                  <li key={item} className="text-copy-13 text-foreground flex items-start gap-2">
                    <TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                    <span>{item}</span>
                  </li>
                ))}
              </ul>
            </SectionCard>
          )}

          {/* ── 口径说明 ───────────────────────────────────────────────── */}
          {data.caliberNote && (
            <SectionCard title="口径说明" description="预计侧与实际侧分别来自哪个上游。">
              <p className="text-copy-13 text-muted-foreground">{data.caliberNote}</p>
            </SectionCard>
          )}
        </>
      )}
    </div>
  );
}

/**
 * 一根偏差趋势线（纯 SVG，不用图表库 —— 数据点少、坐标轴简单）。
 *
 * <p>⚠️ **零线必须画出来**：差异率有正有负，零线是「预计与实际一致」的分界。
 */
function VarianceTrendLine({
  series,
}: {
  series: {
    key: string;
    feeTypeLabel: string;
    currency: string;
    points: { periodStart: string; periodEnd: string; rate: number }[];
  };
}) {
  const width = 720;
  const height = 120;
  const padding = 16;
  const bounds = seriesBounds(series.points);
  if (bounds === null) {
    return (
      <div className="text-copy-13 text-muted-foreground">
        {series.feeTypeLabel} · {series.currency}：没有可画的点。
      </div>
    );
  }
  const span = bounds.max - bounds.min || 1;
  const step = series.points.length > 1 ? (width - padding * 2) / (series.points.length - 1) : 0;
  const coords = series.points.map((point, index) => ({
    x: padding + index * step,
    y: height - padding - ((point.rate - bounds.min) / span) * (height - padding * 2),
    point,
  }));
  const path = coords.map((c, i) => `${i === 0 ? 'M' : 'L'}${c.x.toFixed(2)},${c.y.toFixed(2)}`).join(' ');
  const zeroY = height - padding - ((0 - bounds.min) / span) * (height - padding * 2);

  return (
    <div>
      <div className="text-copy-13 text-foreground">
        {series.feeTypeLabel} · {series.currency}
      </div>
      <svg viewBox={`0 0 ${width} ${height}`} className="h-32 w-full" role="img" aria-label={`${series.feeTypeLabel} 偏差趋势`}>
        <line
          x1={padding}
          y1={zeroY.toFixed(2)}
          x2={width - padding}
          y2={zeroY.toFixed(2)}
          className="stroke-border"
          strokeWidth={1}
          strokeDasharray="4 4"
        />
        <path d={path} fill="none" stroke="currentColor" strokeWidth={1.5} className="text-foreground" />
        {coords.map((c) => (
          <circle
            key={c.point.periodStart}
            cx={c.x}
            cy={c.y}
            r={2.5}
            className="fill-foreground"
          >
            <title>{`${c.point.periodStart}：${rateText(c.point.rate)}`}</title>
          </circle>
        ))}
      </svg>
      <div className="text-copy-13 text-muted-foreground flex justify-between">
        <span>{series.points[0]?.periodStart}</span>
        <span>{series.points[series.points.length - 1]?.periodEnd}</span>
      </div>
    </div>
  );
}
