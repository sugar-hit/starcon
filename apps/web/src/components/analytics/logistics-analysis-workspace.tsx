'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { api } from '@/lib/api';
import {
  amountText,
  categoryRatioTone,
  categoryScopeNote,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  emptyText,
  exceptionShareTone,
  freightGapLabel,
  freightGapTone,
  freightScopeNote,
  hoursText,
  onTimeTone,
  pageState,
  percentText,
  quantityText,
  rangeText,
  severityTone,
  shareBarWidth,
  unavailableLabel,
} from './logistics-analysis-model';
import type {
  LogisticsAnalysisCapabilities,
  LogisticsCategoryCostResponse,
  LogisticsChannelComparisonResponse,
  LogisticsCostOverviewResponse,
  LogisticsCountryCostResponse,
  LogisticsDrilldownResponse,
  LogisticsExceptionShareResponse,
  LogisticsFreightPerOrderResponse,
  LogisticsHeadMileResponse,
  LogisticsTimelinessResponse,
} from './logistics-analysis-model';

/**
 * 13.5.3.1 物流分析工作区（卷 13「利润与经营分析」L3 13.5.3 物流分析下的第一格）。
 *
 * <p>蓝图输出：「物流报表 + 渠道对比。」
 *
 * <p><b>数据来自 reporting 服务（13 卷的唯一归属服务）的投影读模型</b>：
 *
 * <ul>
 *   <li>{@code GET reporting/logistics-analysis/overview} —— 全口径成本（逐币种）；</li>
 *   <li>{@code GET reporting/logistics-analysis/freight-per-order} —— 单均运费（逐币种）；</li>
 *   <li>{@code GET reporting/logistics-analysis/head-mile} —— 头程分摊后成本；</li>
 *   <li>{@code GET reporting/logistics-analysis/channels} —— 渠道对比；</li>
 *   <li>{@code GET reporting/logistics-analysis/timeliness} —— 时效达成率（渠道 × 国家）；</li>
 *   <li>{@code GET reporting/logistics-analysis/countries} —— 各国成本对比；</li>
 *   <li>{@code GET reporting/logistics-analysis/exceptions} —— 异常件占比；</li>
 *   <li>{@code GET reporting/logistics-analysis/category-cost} —— 类目维度物流成本占比；</li>
 *   <li>{@code GET reporting/logistics-analysis/drilldown} —— 运单级下钻；</li>
 *   <li>{@code GET reporting/logistics-analysis/capabilities} —— 能力声明。</li>
 * </ul>
 *
 * <p><b>🔴 页面必须做到的六件事</b>：
 *
 * <ol>
 *   <li><b>数据更新时间常驻</b>（卷 13 卷首加粗句「数据延迟要显示」）。</li>
 *   <li><b>「算不出来」≠「数值是 0」</b>：{@code null} 一律显示「—（不可比）」，
 *       用**中性**配色，不用警告色。</li>
 *   <li><b>单均运费与每票运费并排</b>，并写明分母口径。</li>
 *   <li><b>多币种绝不相加</b>：跨币种的合计不存在。</li>
 *   <li><b>类目维度只覆盖头程分摊</b> —— 边界写在页面上。</li>
 *   <li><b>能力声明常驻</b>：「哪些确实拿不到」必须写在页面上，并写明归属模块号。</li>
 * </ol>
 */
export function AnalyticsLogisticsAnalysisWorkspace() {
  const today = new Date().toISOString().slice(0, 10);
  const initialRange = defaultRange(today);

  const [from, setFrom] = useState(initialRange.from);
  const [to, setTo] = useState(initialRange.to);
  const [currency, setCurrency] = useState('');

  const [overview, setOverview] = useState<LogisticsCostOverviewResponse | null>(null);
  const [freight, setFreight] = useState<LogisticsFreightPerOrderResponse | null>(null);
  const [headMile, setHeadMile] = useState<LogisticsHeadMileResponse | null>(null);
  const [channels, setChannels] = useState<LogisticsChannelComparisonResponse | null>(null);
  const [timeliness, setTimeliness] = useState<LogisticsTimelinessResponse | null>(null);
  const [countries, setCountries] = useState<LogisticsCountryCostResponse | null>(null);
  const [exceptions, setExceptions] = useState<LogisticsExceptionShareResponse | null>(null);
  const [category, setCategory] = useState<LogisticsCategoryCostResponse | null>(null);
  const [drilldown, setDrilldown] = useState<LogisticsDrilldownResponse | null>(null);
  const [capabilities, setCapabilities] = useState<LogisticsAnalysisCapabilities | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  const currencyQuery = currency ? `&currency=${currency}` : '';

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [ov, fr, hm, ch, tl, co, ex, ca, dr, caps] = await Promise.all([
        api<LogisticsCostOverviewResponse>(`reporting/logistics-analysis/overview?from=${from}&to=${to}`),
        api<LogisticsFreightPerOrderResponse>(
          `reporting/logistics-analysis/freight-per-order?from=${from}&to=${to}`,
        ),
        api<LogisticsHeadMileResponse>(`reporting/logistics-analysis/head-mile?from=${from}&to=${to}`),
        api<LogisticsChannelComparisonResponse>(
          `reporting/logistics-analysis/channels?from=${from}&to=${to}${currencyQuery}&size=20`,
        ),
        api<LogisticsTimelinessResponse>(`reporting/logistics-analysis/timeliness?from=${from}&to=${to}`),
        api<LogisticsCountryCostResponse>(`reporting/logistics-analysis/countries?from=${from}&to=${to}`),
        api<LogisticsExceptionShareResponse>(
          `reporting/logistics-analysis/exceptions?from=${from}&to=${to}`,
        ),
        api<LogisticsCategoryCostResponse>(
          `reporting/logistics-analysis/category-cost?from=${from}&to=${to}`,
        ),
        api<LogisticsDrilldownResponse>(
          `reporting/logistics-analysis/drilldown?from=${from}&to=${to}${currencyQuery}&size=20`,
        ),
        api<LogisticsAnalysisCapabilities>('reporting/logistics-analysis/capabilities'),
      ]);
      setOverview(ov);
      setFreight(fr);
      setHeadMile(hm);
      setChannels(ch);
      setTimeliness(tl);
      setCountries(co);
      setExceptions(ex);
      setCategory(ca);
      setDrilldown(dr);
      setCapabilities(caps);
    } catch (e) {
      setError(e instanceof Error ? e.message : '读取失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [from, to, currency]);

  useEffect(() => {
    void load();
  }, [load]);

  const state = pageState(drilldown, error);
  const warnings = useMemo(
    () => [
      ...(overview?.warnings ?? []),
      ...(freight?.warnings ?? []),
      ...(headMile?.warnings ?? []),
      ...(channels?.warnings ?? []),
      ...(timeliness?.warnings ?? []),
      ...(countries?.warnings ?? []),
      ...(exceptions?.warnings ?? []),
      ...(category?.warnings ?? []),
    ],
    [overview, freight, headMile, channels, timeliness, countries, exceptions, category],
  );

  return (
    <div className="space-y-4">
      {/* ── 数据更新时间（常驻）────────────────────────────────────────── */}
      <div className="border-border bg-muted/40 flex flex-wrap items-center justify-between gap-3 rounded-lg border px-4 py-3">
        <div className="text-copy-13 text-foreground">
          <span className="font-medium">{dataUpdatedText(overview)}</span>
          <span className="text-muted-foreground ml-2">{dataUpdatedNote(overview)}</span>
        </div>
        <div className="text-copy-13 text-muted-foreground flex items-center gap-3">
          <span>{rangeText(from, to)}</span>
          <Button variant="outline" size="sm" disabled={loading} onClick={() => void load()}>
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            刷新
          </Button>
        </div>
      </div>

      {/* ── 口径说明（常驻）────────────────────────────────────────────── */}
      {overview && (
        <div className="border-border bg-muted/40 text-copy-13 text-muted-foreground rounded-lg border px-4 py-3">
          <div>{overview.caliberNote}</div>
          {freight && <div className="mt-1">{freightScopeNote(freight)}</div>}
          {timeliness && <div className="mt-1">{timeliness.caliberNote}</div>}
          {headMile && <div className="mt-1">{headMile.caliberNote}</div>}
          {countries && <div className="mt-1">{countries.caliberNote}</div>}
          {exceptions && <div className="mt-1">{exceptions.caliberNote}</div>}
          {category && <div className="mt-1">{categoryScopeNote(category)}</div>}
        </div>
      )}

      {/* ── 时间范围与筛选 ─────────────────────────────────────────────── */}
      <SectionCard
        title="时间范围与筛选"
        description="时间范围决定成本总览、单均运费、时效达成率、各国成本与异常件占比的窗口；币种筛选后金额与占比只算该币种（多币种绝不相加）。"
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
            币种（空 = 全部）
            <input
              type="text"
              value={currency}
              placeholder="USD"
              maxLength={3}
              onChange={(e) => setCurrency(e.target.value.toUpperCase())}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
          </label>
        </div>
      </SectionCard>

      {/* ── 口径提示 ───────────────────────────────────────────────────── */}
      {warnings.length > 0 && (
        <SectionCard title="口径提示">
          <ul className="space-y-1.5">
            {Array.from(new Set(warnings)).map((warning) => (
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
          <EmptyState title="正在读取物流分析…" description="读取期间页面上的「数据截至」仍然有效。" />
        </SectionCard>
      )}

      {state === 'empty' && (
        <SectionCard>
          <EmptyState title="没有物流分析数据" description={emptyText(drilldown)} />
        </SectionCard>
      )}

      {state === 'ready' && (
        <>
          {/* ── ① 单均运费 ─────────────────────────────────────────────── */}
          {freight && (
            <SectionCard
              title="单均运费"
              description="运费总额 ÷ **订单数**。订单分母与运费总额同范围（同一批运单）。**多币种绝不相加** —— 跨币种的合计不存在。"
            >
              {freight.rows.length === 0 ? (
                <p className="text-copy-13 text-muted-foreground">
                  这个窗口里没有运单 —— 单均运费**不可比**（不是 0 元）。先刷新投影。
                </p>
              ) : (
                <div className="space-y-3">
                  {freight.rows.map((row) => (
                    <div key={row.currency} className="border-border rounded-lg border px-3 py-2">
                      <div className="flex flex-wrap items-baseline gap-4">
                        <span className="text-copy-15 font-medium tabular-nums">
                          {amountText(row.perOrder, row.currency)}
                        </span>
                        <span className="text-copy-13 text-muted-foreground">
                          每单运费（分母 = {row.orderCount} 个订单）
                        </span>
                        <span className="text-copy-13 text-muted-foreground">
                          每票运费（上游口径，分母 = {row.registeredWaybillCount} 票）=
                          {amountText(row.perWaybill, row.currency)}
                        </span>
                      </div>
                      <div className="text-copy-13 text-muted-foreground mt-1">
                        运费合计 {amountText(row.freightAmount, row.currency)} · 运单 {row.waybillCount} 票 ·
                        每公斤 {amountText(row.perKg, row.currency)}
                      </div>
                      <div className="text-copy-13 text-muted-foreground mt-1">{row.note}</div>
                    </div>
                  ))}
                </div>
              )}
            </SectionCard>
          )}

          {/* ── ② 物流成本总览（逐币种）────────────────────────────────── */}
          {overview && (
            <SectionCard
              title="物流成本总览（逐币种）"
              description="尾程 + 头程分摊 + 关税 + 包材 = 已计入分量之和。缺项在「缺失分量」列逐条列明 —— **不是 0**。"
            >
              {overview.rows.length === 0 ? (
                <p className="text-copy-13 text-muted-foreground">
                  这个窗口还没有成本快照 —— 先刷新投影。各金额是「—」，**不是 0**。
                </p>
              ) : (
                <div className="space-y-3">
                  {overview.rows.map((row) => (
                    <div key={row.currency} className="border-border rounded-lg border px-3 py-2">
                      <div className="flex flex-wrap items-baseline gap-4">
                        <span className="text-copy-15 font-medium tabular-nums">
                          {amountText(row.subtotal, row.currency)}
                        </span>
                        <span className="text-copy-13 text-muted-foreground">{row.currency} 合计</span>
                        <Badge tone={row.missingComponents.length > 0 ? 'warning' : 'success'}>
                          {row.missingComponents.length > 0
                            ? `缺 ${row.missingComponents.length} 个分量`
                            : '分量齐全'}
                        </Badge>
                      </div>
                      <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-copy-13 md:grid-cols-4">
                        <div>
                          <dt className="text-muted-foreground">尾程运费</dt>
                          <dd className="tabular-nums">{amountText(row.lastMileFreight, row.currency)}</dd>
                        </div>
                        <div>
                          <dt className="text-muted-foreground">头程分摊后</dt>
                          <dd className="tabular-nums">{amountText(row.firstMileAllocation, row.currency)}</dd>
                        </div>
                        <div>
                          <dt className="text-muted-foreground">关税</dt>
                          <dd className="tabular-nums">{amountText(row.dutyAndTax, row.currency)}</dd>
                        </div>
                        <div>
                          <dt className="text-muted-foreground">包材</dt>
                          <dd className="tabular-nums">{amountText(row.packaging, row.currency)}</dd>
                        </div>
                      </dl>
                      <div className="text-copy-13 text-muted-foreground mt-1">{row.note}</div>
                    </div>
                  ))}
                </div>
              )}
            </SectionCard>
          )}

          {/* ── ③ 头程分摊后成本 ──────────────────────────────────────── */}
          {headMile && (
            <SectionCard
              title="头程分摊后成本"
              description="读 channel 10.6.1.1 的分摊结果（**不重算分摊**）。分摊单按**结账时点**归窗；与上游成本总览的头程分摊位并排对照。"
            >
              {headMile.rows.length === 0 ? (
                <p className="text-copy-13 text-muted-foreground">
                  这个窗口没有头程分摊单 —— 头程分摊后成本**不可比**（不是 0 元）。
                </p>
              ) : (
                <>
                  <div className="space-y-2">
                    {headMile.rows.map((row) => (
                      <div key={row.currency} className="text-copy-13 flex flex-wrap items-baseline gap-4">
                        <span className="text-copy-15 font-medium tabular-nums">
                          {amountText(row.allocatedAmount, row.currency)}
                        </span>
                        <span className="text-muted-foreground">
                          {row.currency} · {row.allocationCount} 张分摊单 · {row.lineCount} 行明细
                        </span>
                        <span className="text-muted-foreground">
                          与上游总览对照：{amountText(row.upstreamOverviewAmount, row.currency)}
                        </span>
                      </div>
                    ))}
                  </div>
                  {headMile.allocations.length > 0 && (
                    <div className="mt-3">
                      <div className="text-copy-13 text-muted-foreground mb-1">分摊单（最新在前）</div>
                      <ul className="space-y-1">
                        {headMile.allocations.map((row) => (
                          <li key={row.allocationCode} className="text-copy-13 flex flex-wrap gap-3">
                            <span className="font-medium">{row.allocationCode}</span>
                            <span className="text-muted-foreground">
                              {row.ruleCode} v{row.ruleVersion} · {row.allocationBasisLabel}
                            </span>
                            <span className="text-muted-foreground">
                              目的国 {row.destinationCountry || '—'}
                            </span>
                            <span className="tabular-nums">{amountText(row.allocatedTotalAmount, row.currency)}</span>
                            <span className="text-muted-foreground">{row.statusLabel}</span>
                          </li>
                        ))}
                      </ul>
                    </div>
                  )}
                </>
              )}
            </SectionCard>
          )}

          {/* ── ④ 渠道对比 ────────────────────────────────────────────── */}
          {channels && (
            <SectionCard
              title="渠道对比（承运商 × 渠道）"
              description="**逐币种**：占比的分母是同币种的窗口合计；跨币种没有占比。"
            >
              {channels.rows.length === 0 ? (
                <p className="text-copy-13 text-muted-foreground">
                  这个窗口还没有渠道成本快照 —— 先刷新投影。
                </p>
              ) : (
                <table className="w-full text-copy-13">
                  <thead>
                    <tr className="text-muted-foreground border-border border-b text-left">
                      <th className="py-1.5 pr-3 font-medium">承运商</th>
                      <th className="py-1.5 pr-3 font-medium">渠道</th>
                      <th className="py-1.5 pr-3 font-medium">币种</th>
                      <th className="py-1.5 pr-3 text-right font-medium">运费</th>
                      <th className="py-1.5 pr-3 text-right font-medium">票数</th>
                      <th className="py-1.5 pr-3 text-right font-medium">占同币种</th>
                    </tr>
                  </thead>
                  <tbody>
                    {channels.rows.map((row) => (
                      <tr key={`${row.carrierCode}-${row.carrierChannel}-${row.currency}`} className="border-border border-b">
                        <td className="py-1.5 pr-3">{row.carrierCode}</td>
                        <td className="py-1.5 pr-3">{row.carrierChannelLabel || row.carrierChannel}</td>
                        <td className="py-1.5 pr-3">{row.currency}</td>
                        <td className="py-1.5 pr-3 text-right tabular-nums">
                          {amountText(row.amount, row.currency)}
                        </td>
                        <td className="py-1.5 pr-3 text-right tabular-nums">{row.waybills}</td>
                        <td className="py-1.5 pr-3 text-right tabular-nums">{percentText(row.shareOfCurrency)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </SectionCard>
          )}

          {/* ── ⑤ 时效达成率 ──────────────────────────────────────────── */}
          {timeliness && (
            <SectionCard
              title="时效达成率（渠道 × 国家）"
              description="达成率 = 准点数 ÷ **可比数**（没有承诺送达日与未签收的票不进分母）。**判定归 channel 10.6.2.1**，本模块只搬运。"
            >
              <div className="mb-3 flex flex-wrap gap-4">
                <div>
                  <div className="text-copy-13 text-muted-foreground">总体达成率</div>
                  <div className="text-copy-15 font-medium tabular-nums">
                    {percentText(timeliness.overallOnTimeRate)}
                  </div>
                </div>
                <div>
                  <div className="text-copy-13 text-muted-foreground">可比数</div>
                  <div className="text-copy-15 font-medium tabular-nums">{timeliness.comparableCount}</div>
                </div>
                <div>
                  <div className="text-copy-13 text-muted-foreground">超时</div>
                  <div className="text-copy-15 font-medium tabular-nums">{timeliness.overdueCount}</div>
                </div>
                <div>
                  <div className="text-copy-13 text-muted-foreground">平均实际时效</div>
                  <div className="text-copy-15 font-medium tabular-nums">
                    {hoursText(timeliness.avgTransitHours)}
                  </div>
                </div>
                <Badge tone={onTimeTone(timeliness.overallOnTimeRate)}>
                  目标 {percentText(timeliness.targetRate)}
                </Badge>
              </div>
              {timeliness.cells.length === 0 ? (
                <p className="text-copy-13 text-muted-foreground">
                  这个窗口还没有时效快照 —— 达成率**不可比**（不是 0%）。
                </p>
              ) : (
                <table className="w-full text-copy-13">
                  <thead>
                    <tr className="text-muted-foreground border-border border-b text-left">
                      <th className="py-1.5 pr-3 font-medium">渠道</th>
                      <th className="py-1.5 pr-3 font-medium">国家</th>
                      <th className="py-1.5 pr-3 text-right font-medium">可比</th>
                      <th className="py-1.5 pr-3 text-right font-medium">准点</th>
                      <th className="py-1.5 pr-3 text-right font-medium">达成率</th>
                      <th className="py-1.5 pr-3 text-right font-medium">平均时效</th>
                    </tr>
                  </thead>
                  <tbody>
                    {timeliness.cells.map((cell) => (
                      <tr
                        key={`${cell.channelCode}-${cell.countryCode}`}
                        className="border-border border-b"
                      >
                        <td className="py-1.5 pr-3">{cell.channelName || cell.channelCode}</td>
                        <td className="py-1.5 pr-3">{cell.countryCode}</td>
                        <td className="py-1.5 pr-3 text-right tabular-nums">{cell.comparableCount}</td>
                        <td className="py-1.5 pr-3 text-right tabular-nums">{cell.onTimeCount}</td>
                        <td className="py-1.5 pr-3 text-right tabular-nums">
                          {percentText(cell.onTimeRate)}
                        </td>
                        <td className="py-1.5 pr-3 text-right tabular-nums">
                          {hoursText(cell.avgTransitHours)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </SectionCard>
          )}

          {/* ── ⑥ 各国成本对比 ────────────────────────────────────────── */}
          {countries && (
            <SectionCard
              title="各国成本对比（逐币种）"
              description="运单级尾程运费。**多币种绝不相加**：某国占比的分母是同币种的合计；没有目的国的票单列「未标注目的国」。"
            >
              {countries.rows.length === 0 ? (
                <p className="text-copy-13 text-muted-foreground">
                  这个窗口没有任何运单 —— 各国成本**不可比**（不是 0）。
                </p>
              ) : (
                <table className="w-full text-copy-13">
                  <thead>
                    <tr className="text-muted-foreground border-border border-b text-left">
                      <th className="py-1.5 pr-3 font-medium">币种</th>
                      <th className="py-1.5 pr-3 font-medium">国家</th>
                      <th className="py-1.5 pr-3 text-right font-medium">运费</th>
                      <th className="py-1.5 pr-3 text-right font-medium">票数</th>
                      <th className="py-1.5 pr-3 text-right font-medium">每单</th>
                      <th className="py-1.5 pr-3 text-right font-medium">占同币种</th>
                    </tr>
                  </thead>
                  <tbody>
                    {countries.rows.map((row) => (
                      <tr
                        key={`${row.currency}-${row.countryCode}`}
                        className="border-border border-b"
                      >
                        <td className="py-1.5 pr-3">{row.currency}</td>
                        <td className="py-1.5 pr-3">{row.countryCode}</td>
                        <td className="py-1.5 pr-3 text-right tabular-nums">
                          {amountText(row.amount, row.currency)}
                        </td>
                        <td className="py-1.5 pr-3 text-right tabular-nums">{row.waybills}</td>
                        <td className="py-1.5 pr-3 text-right tabular-nums">
                          {amountText(row.perOrder, row.currency)}
                        </td>
                        <td className="py-1.5 pr-3 text-right tabular-nums">
                          {percentText(row.shareOfCurrency)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </SectionCard>
          )}

          {/* ── ⑦ 异常件占比 ──────────────────────────────────────────── */}
          {exceptions && (
            <SectionCard
              title="异常件占比"
              description="窗口内异常件 ÷ **窗口内运单**（分子分母同范围）。异常件是上游**已判定**的件，本模块不重判。"
            >
              <div className="mb-3 flex flex-wrap items-center gap-4">
                <div>
                  <div className="text-copy-13 text-muted-foreground">异常件占比</div>
                  <div className="text-copy-15 font-medium tabular-nums">
                    {percentText(exceptions.exceptionShare)}
                  </div>
                </div>
                <div className="text-copy-13 text-muted-foreground">
                  {exceptions.exceptionCount} 件 / {exceptions.waybillCount} 票
                </div>
                <Badge tone={exceptionShareTone(exceptions.exceptionShare)}>
                  上限 {percentText(exceptions.targetShare)}
                </Badge>
              </div>
              {exceptions.byType.length === 0 ? (
                <p className="text-copy-13 text-muted-foreground">
                  窗口内没有异常件记录 —— 占比**不可比**（不是 0%）。
                </p>
              ) : (
                <ul className="space-y-1">
                  {exceptions.byType.map((row) => (
                    <li key={`${row.dimension}-${row.dimensionKey}`} className="text-copy-13 flex items-center gap-3">
                      <span className="w-40 shrink-0">{row.dimensionLabel || row.dimensionKey}</span>
                      <span className="tabular-nums">{row.count}</span>
                      <span className="text-muted-foreground tabular-nums">
                        {percentText(row.shareOfExceptions)}
                      </span>
                      <span className="bg-border h-1.5 w-32 overflow-hidden rounded">
                        <span
                          className="bg-primary block h-full"
                          style={{ width: `${shareBarWidth(row.shareOfExceptions)}%` }}
                        />
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </SectionCard>
          )}

          {/* ── ⑧ 类目维度的物流成本占比（下游 5 定价）────────────────── */}
          {category && (
            <SectionCard
              title="类目维度的物流成本占销售额比（下游 5 定价）"
              description="分子 = 类目下的**头程分摊后成本**；分母 = 13.4.1.2 的**同币种销售额**。**物流成本占比高的类目必须单独定价**。"
            >
              {category.rows.length === 0 ? (
                <p className="text-copy-13 text-muted-foreground">
                  这个窗口没有类目维度的头程分摊 —— 占比**不可比**（不是 0%）。
                </p>
              ) : (
                <table className="w-full text-copy-13">
                  <thead>
                    <tr className="text-muted-foreground border-border border-b text-left">
                      <th className="py-1.5 pr-3 font-medium">币种</th>
                      <th className="py-1.5 pr-3 font-medium">类目</th>
                      <th className="py-1.5 pr-3 text-right font-medium">物流成本</th>
                      <th className="py-1.5 pr-3 text-right font-medium">销售额</th>
                      <th className="py-1.5 pr-3 text-right font-medium">占比</th>
                      <th className="py-1.5 pr-3 text-right font-medium">超线</th>
                    </tr>
                  </thead>
                  <tbody>
                    {category.rows.map((row) => (
                      <tr
                        key={`${row.currency}-${row.categoryCode}`}
                        className="border-border border-b"
                      >
                        <td className="py-1.5 pr-3">{row.currency}</td>
                        <td className="py-1.5 pr-3">{row.categoryCode}</td>
                        <td className="py-1.5 pr-3 text-right tabular-nums">
                          {amountText(row.logisticsCost, row.currency)}
                        </td>
                        <td className="py-1.5 pr-3 text-right tabular-nums">
                          {amountText(row.revenue, row.currency)}
                        </td>
                        <td className="py-1.5 pr-3 text-right tabular-nums">
                          {percentText(row.ratio)}
                        </td>
                        <td className="py-1.5 pr-3 text-right">
                          <Badge tone={categoryRatioTone(row.ratio)}>
                            {row.exceedsTarget ? '超线' : '—'}
                          </Badge>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </SectionCard>
          )}

          {/* ── ⑨ 运单级下钻 ──────────────────────────────────────────── */}
          {drilldown && (
            <SectionCard
              title="运单下钻"
              description="一行 = 一张运单。**没有运费的行是「—」**，不在任何合计里（不是 0 元）。"
            >
              {drilldown.rows.length === 0 ? (
                <p className="text-copy-13 text-muted-foreground">这个窗口没有运单。</p>
              ) : (
                <table className="w-full text-copy-13">
                  <thead>
                    <tr className="text-muted-foreground border-border border-b text-left">
                      <th className="py-1.5 pr-3 font-medium">运单号</th>
                      <th className="py-1.5 pr-3 font-medium">销售订单</th>
                      <th className="py-1.5 pr-3 font-medium">目的国</th>
                      <th className="py-1.5 pr-3 font-medium">运费登记</th>
                      <th className="py-1.5 pr-3 text-right font-medium">运费</th>
                      <th className="py-1.5 pr-3 text-right font-medium">重量</th>
                    </tr>
                  </thead>
                  <tbody>
                    {drilldown.rows.map((row) => (
                      <tr key={row.waybillNo} className="border-border border-b">
                        <td className="py-1.5 pr-3">{row.waybillNo}</td>
                        <td className="py-1.5 pr-3">{row.salesOrderNo}</td>
                        <td className="py-1.5 pr-3">{row.destinationCountry || '—'}</td>
                        <td className="py-1.5 pr-3">
                          <Badge tone={freightGapTone(row.freightGapCode)}>
                            {freightGapLabel(row.freightGapCode)}
                          </Badge>
                        </td>
                        <td className="py-1.5 pr-3 text-right tabular-nums">
                          {amountText(row.freightAmount, row.freightCurrency)}
                        </td>
                        <td className="py-1.5 pr-3 text-right tabular-nums">
                          {quantityText(row.grossWeightKg)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </SectionCard>
          )}

          {/* ── ⑩ 能力声明（常驻）─────────────────────────────────────── */}
          {capabilities && (
            <SectionCard
              title="能力声明"
              description="哪些指标真接得通、哪些**确实算不出来**（并给出归属模块号与影响）。"
            >
              <ul className="space-y-2">
                {capabilities.sections.map((section) => (
                  <li key={section.key} className="text-copy-13 flex flex-wrap items-start gap-2">
                    <Badge tone={section.available ? 'success' : 'warning'}>
                      {section.available ? '已接通' : unavailableLabel(section.ownerModule)}
                    </Badge>
                    <span className="font-medium">{section.label}</span>
                    <span className="text-muted-foreground">{section.caliber}</span>
                    {!section.available && section.impact && (
                      <span className="text-muted-foreground w-full">影响：{section.impact}</span>
                    )}
                  </li>
                ))}
              </ul>
              <div className="text-copy-13 text-muted-foreground mt-3 space-y-1">
                <div>{capabilities.upstreamNote}</div>
                <div>{capabilities.currencyNote}</div>
                <div>{capabilities.orderScopeNote}</div>
                <div>{capabilities.allocationNote}</div>
                <div>{capabilities.categoryNote}</div>
                <div>{capabilities.dataDependencyNote}</div>
              </div>
            </SectionCard>
          )}
        </>
      )}
    </div>
  );
}
