'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { api } from '@/lib/api';
import {
  DIMENSION_OPTIONS,
  GRANULARITY_OPTIONS,
  amountText,
  baseAmountText,
  baseRateText,
  componentLabel,
  componentReason,
  componentText,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  drilldownPageNote,
  drilldownScopeText,
  emptyText,
  grossProfitLabel,
  grossProfitText,
  grossProfitTone,
  grossMarginText,
  isUnattributed,
  modeNote,
  operatingModeLabel,
  pageState,
  periodText,
  rangeText,
  rowPeriodText,
} from './profit-report-model';
import type {
  ProfitDrilldownPage,
  ProfitReportCapabilities,
  ProfitReportResponse,
} from './profit-report-model';

/**
 * 13.4.1.1 多模式利润报表工作区（卷 13「利润与经营分析」L3 13.4.1 利润报表下的第一格）。
 *
 * <p>蓝图输出：「利润报表（收入 - 各成本费用 = 毛利）+ 多维下钻 + 口径说明。」
 *
 * <p><b>数据来自 reporting 服务（13 卷的唯一归属服务）的投影读模型</b>：
 *
 * <ul>
 *   <li>{@code GET reporting/profit-report/report} —— 利润报表（按模式分行 + 多维核算）；</li>
 *   <li>{@code GET reporting/profit-report/drilldown} —— 多维下钻（真分页 + 真计数）；</li>
 *   <li>{@code GET reporting/profit-report/modes} —— 经营模式清单（模式取自 2.3.2.1）；</li>
 *   <li>{@code GET reporting/profit-report/capabilities} —— 能力声明（哪些上游真接通、哪些确实拿不到）。</li>
 * </ul>
 *
 * <p><b>🔴 页面必须做到的五件事</b>：
 *
 * <ol>
 *   <li><b>模式分行、绝不合并</b>（蓝图关系句加粗要求）：不同经营模式的利润含义完全不同，
 *       混在一张表里会得出错误结论。</li>
 *   <li><b>「算不出来」≠「等于 0」</b>：{@code null} 的成本项与毛利显示「—」与「不可比」，
 *       并把服务端给的原因原样带出来，**绝不用 0 顶替**。</li>
 *   <li><b>口径说明常驻</b>：收入怎么算、成本取自哪一路上游、汇率用哪个快照、哪些维度算不出来，
 *       都写在页面上。</li>
 *   <li><b>多币种不相加</b>：每一行带自己的币种。</li>
 *   <li><b>数据更新时间常驻</b>（卷 13 卷首加粗句「数据延迟要显示」）。</li>
 * </ol>
 */
export function AnalyticsProfitReportWorkspace() {
  const today = new Date().toISOString().slice(0, 10);
  const initialRange = defaultRange(today);

  const [from, setFrom] = useState(initialRange.from);
  const [to, setTo] = useState(initialRange.to);
  const [granularity, setGranularity] = useState('DAY');
  const [dimension, setDimension] = useState('MODE');
  const [operatingMode, setOperatingMode] = useState('');

  const [data, setData] = useState<ProfitReportResponse | null>(null);
  const [capabilities, setCapabilities] = useState<ProfitReportCapabilities | null>(null);
  const [drilldown, setDrilldown] = useState<ProfitDrilldownPage | null>(null);
  const [drilldownTarget, setDrilldownTarget] = useState<{ code: string; mode: string } | null>(null);
  const [drilldownPageIndex, setDrilldownPageIndex] = useState(0);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const query = useMemo(() => {
    const params = new URLSearchParams({ from, to, granularity, dimension });
    if (operatingMode) params.set('operatingMode', operatingMode);
    return params.toString();
  }, [from, to, granularity, dimension, operatingMode]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [report, caps] = await Promise.all([
        api<ProfitReportResponse>(`reporting/profit-report/report?${query}`),
        api<ProfitReportCapabilities>('reporting/profit-report/capabilities'),
      ]);
      setData(report);
      setCapabilities(caps);
    } catch (e) {
      setData(null);
      setError(e instanceof Error ? e.message : '读取失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [query]);

  const loadDrilldown = useCallback(
    async (code: string, mode: string, page: number) => {
      setBusy(true);
      try {
        const params = new URLSearchParams({
          from,
          to,
          dimension,
          dimensionCode: code,
          operatingMode: mode,
          page: String(page),
          size: '50',
        });
        setDrilldown(await api<ProfitDrilldownPage>(`reporting/profit-report/drilldown?${params}`));
        setDrilldownTarget({ code, mode });
        setDrilldownPageIndex(page);
      } catch (e) {
        setDrilldown(null);
        setError(e instanceof Error ? e.message : '读取下钻明细失败，请重试');
      } finally {
        setBusy(false);
      }
    },
    [from, to, dimension],
  );

  useEffect(() => {
    void load();
  }, [load]);

  const state = pageState(data, error);

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

      {/* ── 口径说明（常驻）────────────────────────────────────────────── */}
      {data && (
        <div className="border-border bg-muted/40 text-copy-13 text-muted-foreground rounded-lg border px-4 py-3">
          {data.caliberNote}
        </div>
      )}

      {/* ── 时间范围与筛选 ─────────────────────────────────────────────── */}
      <SectionCard
        title="时间范围与筛选"
        description="核算维度决定这一页按什么维度出报表；经营模式筛选只缩小范围，**不同模式的利润始终分行展示**。"
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
            核算维度
            <select
              value={dimension}
              onChange={(e) => {
                setDimension(e.target.value);
                setDrilldown(null);
                setDrilldownTarget(null);
              }}
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
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            经营模式
            <select
              value={operatingMode}
              onChange={(e) => setOperatingMode(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              <option value="">全部模式（分行展示，不合并）</option>
              {(data?.modes ?? []).map((mode) => (
                <option key={mode.operatingMode} value={mode.operatingMode}>
                  {operatingModeLabel(mode.operatingMode, mode.operatingModeLabel)}（
                  {mode.shopCount} 个店铺）
                </option>
              ))}
            </select>
          </label>
        </div>
      </SectionCard>

      {/* ── 经营模式清单 ───────────────────────────────────────────────── */}
      {data && data.modes.length > 0 && (
        <SectionCard
          title="经营模式清单"
          description="模式取自 channel 2.3.2.1 的 `shop.operating_mode`；**「未归属」不是一种经营模式**，它的数字不混进任何真实模式。"
        >
          <div className="space-y-2">
            {data.modes.map((mode) => (
              <div key={mode.operatingMode} className="border-border rounded-lg border px-4 py-3">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-copy-14 text-foreground font-medium">
                    {operatingModeLabel(mode.operatingMode, mode.operatingModeLabel)}
                  </span>
                  {isUnattributed(mode.operatingMode) && <Badge tone="warning">未归属</Badge>}
                  <span className="text-copy-13 text-muted-foreground">{mode.shopCount} 个店铺</span>
                </div>
                <div className="text-copy-13 text-muted-foreground mt-1">
                  {mode.shopCodes.length > 0 ? mode.shopCodes.join('、') : '（没有店铺）'}
                </div>
                <div className="text-copy-13 text-muted-foreground mt-1">{modeNote(mode)}</div>
              </div>
            ))}
          </div>
        </SectionCard>
      )}

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
          <EmptyState title="正在读取利润报表…" description="读取期间页面上的「数据截至」仍然有效。" />
        </SectionCard>
      )}

      {state === 'empty' && (
        <SectionCard>
          <EmptyState title="没有利润事实" description={emptyText(data)} />
        </SectionCard>
      )}

      {state === 'ready' && data && (
        <>
          {/* ── 利润报表 ─────────────────────────────────────────────────── */}
          <SectionCard
            title="利润报表"
            description="一行 = 一个经营模式 × 一个维度取值 × 一个期间 × 一个币种。毛利 = 收入 − 各成本费用；**算不出来时是「—」，不是 0**。"
          >
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="text-copy-13 text-muted-foreground">
                  <tr className="border-border border-b">
                    <th className="py-2 pr-3 font-medium">经营模式</th>
                    <th className="py-2 pr-3 font-medium">维度取值</th>
                    <th className="py-2 pr-3 font-medium">期间</th>
                    <th className="py-2 pr-3 font-medium">币种</th>
                    <th className="py-2 pr-3 text-right font-medium">收入</th>
                    <th className="py-2 pr-3 text-right font-medium">成本合计</th>
                    <th className="py-2 pr-3 text-right font-medium">毛利</th>
                    <th className="py-2 pr-3 text-right font-medium">毛利率</th>
                    <th className="py-2 pr-3 font-medium">说明</th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((row, index) => (
                    <tr
                      key={`${row.operatingMode}|${row.dimensionCode}|${row.periodStart}|${row.currency}|${index}`}
                      className="border-border border-b"
                    >
                      <td className="py-2 pr-3 whitespace-nowrap">
                        <div className="flex items-center gap-2">
                          <span>{operatingModeLabel(row.operatingMode, row.operatingModeLabel)}</span>
                          {isUnattributed(row.operatingMode) && <Badge tone="warning">未归属</Badge>}
                        </div>
                      </td>
                      <td className="py-2 pr-3">
                        {row.dimensionCode ? (
                          <button
                            type="button"
                            className="text-primary underline-offset-2 hover:underline"
                            onClick={() => void loadDrilldown(row.dimensionCode, row.operatingMode, 0)}
                          >
                            {row.dimensionLabel}
                          </button>
                        ) : (
                          <span className="text-muted-foreground">{row.dimensionLabel}</span>
                        )}
                      </td>
                      <td className="py-2 pr-3 whitespace-nowrap">{rowPeriodText(row)}</td>
                      <td className="py-2 pr-3">{row.currency}</td>
                      <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                        {amountText(
                          row.components.find((c) => c.component === 'REVENUE')?.amount ?? null,
                          row.currency,
                        )}
                      </td>
                      <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                        {amountText(row.totalCost, row.currency)}
                      </td>
                      <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                        {grossProfitText(row.grossProfit, row.currency)}
                      </td>
                      <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                        {grossMarginText(row.grossMargin)}
                      </td>
                      <td className="py-2 pr-3">
                        <Badge tone={grossProfitTone(row)}>{grossProfitLabel(row)}</Badge>
                        <div className="text-copy-13 text-muted-foreground mt-1 max-w-md">
                          {row.note}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </SectionCard>

          {/* ── 成本费用构成 ─────────────────────────────────────────────── */}
          <SectionCard
            title="成本费用构成"
            description="每一项都写清取值与**为什么没有值**；「—」表示算不出来（不是 0）。"
          >
            <div className="space-y-4">
              {data.rows.map((row, index) => (
                <div
                  key={`${row.operatingMode}|${row.dimensionCode}|${row.periodStart}|${row.currency}|${index}`}
                  className="border-border rounded-lg border px-4 py-3"
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-copy-14 text-foreground font-medium">
                      {operatingModeLabel(row.operatingMode, row.operatingModeLabel)}
                      {row.dimensionCode ? ` · ${row.dimensionLabel}` : ''}
                    </span>
                    <span className="text-copy-13 text-muted-foreground">{rowPeriodText(row)}</span>
                    <span className="text-copy-13 text-muted-foreground">{row.currency}</span>
                    {row.baseCurrency && (
                      <span className="text-copy-13 text-muted-foreground">
                        本位币 {row.baseCurrency}：收入 {baseAmountText(row.baseRevenue, row.baseCurrency)} ·
                        毛利 {baseAmountText(row.baseGrossProfit, row.baseCurrency)} · 汇率{' '}
                        {baseRateText(row.baseRate, row.baseRateAsOf)}
                      </span>
                    )}
                  </div>
                  <dl className="text-copy-13 mt-3 grid grid-cols-1 gap-x-6 gap-y-1 sm:grid-cols-2 lg:grid-cols-4">
                    {row.components.map((component) => (
                      <div key={component.component}>
                        <dt className="text-muted-foreground">{componentLabel(component)}</dt>
                        <dd className="text-foreground tabular-nums">
                          {componentText(component, row.currency)}
                        </dd>
                        {!component.available && component.reason && (
                          <dd className="text-copy-13 text-muted-foreground mt-0.5">
                            {componentReason(component)}
                          </dd>
                        )}
                      </div>
                    ))}
                  </dl>
                  {row.missingComponents.length > 0 && (
                    <div className="text-copy-13 text-muted-foreground mt-2">
                      缺 {row.missingComponents.join('、')} ⇒ 这一格的毛利不可比。
                    </div>
                  )}
                </div>
              ))}
            </div>
          </SectionCard>

          {/* ── 下钻 ─────────────────────────────────────────────────────── */}
          <SectionCard
            title="多维下钻"
            description="点上面表格里的维度取值可以下钻：这里列的是这一格背后的**事实行**（来源服务 + 上游行标识）。"
          >
            {!drilldownTarget ? (
              <p className="text-copy-13 text-muted-foreground">
                还没有选择要下钻的那一格。在上面的利润报表里点一个维度取值。
              </p>
            ) : (
              <div className="space-y-3">
                <div className="text-copy-13 text-muted-foreground">
                  {drilldownTarget.code || '（未归属到该维度）'} ·{' '}
                  {operatingModeLabel(drilldownTarget.mode, drilldownTarget.mode)} ·{' '}
                  {drilldownPageNote(drilldown)}
                </div>
                {drilldown && drilldown.items.length === 0 ? (
                  <p className="text-copy-13 text-muted-foreground">这一格背后没有事实行。</p>
                ) : (
                  <div className="overflow-x-auto">
                    <table className="w-full text-left text-sm">
                      <thead className="text-copy-13 text-muted-foreground">
                        <tr className="border-border border-b">
                          <th className="py-2 pr-3 font-medium">成本项</th>
                          <th className="py-2 pr-3 font-medium">期间</th>
                          <th className="py-2 pr-3 font-medium">归属</th>
                          <th className="py-2 pr-3 text-right font-medium">金额</th>
                          <th className="py-2 pr-3 text-right font-medium">数量</th>
                          <th className="py-2 pr-3 font-medium">来源</th>
                        </tr>
                      </thead>
                      <tbody>
                        {(drilldown?.items ?? []).map((item, index) => (
                          <tr
                            key={`${item.component}|${item.sourceReference}|${index}`}
                            className="border-border border-b"
                          >
                            <td className="py-2 pr-3">{item.componentLabel}</td>
                            <td className="py-2 pr-3 whitespace-nowrap">
                              {periodText(item.periodStart, item.periodEnd)}
                            </td>
                            <td className="py-2 pr-3">{drilldownScopeText(item)}</td>
                            <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                              {amountText(item.amount, item.currency)}
                            </td>
                            <td className="py-2 pr-3 text-right tabular-nums">
                              {item.quantity === null ? '—' : item.quantity}
                            </td>
                            <td className="py-2 pr-3 text-muted-foreground">
                              {item.sourceService} · {item.sourceReference}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
                <div className="flex items-center gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={busy || drilldownPageIndex === 0}
                    onClick={() =>
                      void loadDrilldown(
                        drilldownTarget.code,
                        drilldownTarget.mode,
                        Math.max(drilldownPageIndex - 1, 0),
                      )
                    }
                  >
                    上一页
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={
                      busy ||
                      !drilldown ||
                      (drilldownPageIndex + 1) * drilldown.size >= drilldown.total
                    }
                    onClick={() =>
                      void loadDrilldown(
                        drilldownTarget.code,
                        drilldownTarget.mode,
                        drilldownPageIndex + 1,
                      )
                    }
                  >
                    下一页
                  </Button>
                </div>
              </div>
            )}
          </SectionCard>

          {/* ── 能力声明 ─────────────────────────────────────────────────── */}
          {capabilities && (
            <SectionCard
              title="能力声明"
              description="哪些上游真接通、哪些确实算不出来、归谁管 —— **「算不出来」与「等于 0」在页面上分开说**。"
            >
              <div className="space-y-3 text-copy-13">
                <div>
                  <span className="text-muted-foreground">覆盖的成本项：</span>
                  <span className="text-foreground">{capabilities.components.join('、')}</span>
                </div>
                <div>
                  <span className="text-muted-foreground">支持的核算维度：</span>
                  <span className="text-foreground">{capabilities.dimensions.join('、')}</span>
                </div>
                <div>
                  <span className="text-muted-foreground">真接通的上游：</span>
                  <ul className="mt-1 space-y-1">
                    {capabilities.upstreams.map((upstream) => (
                      <li key={upstream} className="text-foreground">
                        · {upstream}
                      </li>
                    ))}
                  </ul>
                </div>
                <div>
                  <span className="text-muted-foreground">确实算不出来（不是 0）：</span>
                  <ul className="mt-1 space-y-1">
                    {capabilities.notDelivered.map((item) => (
                      <li key={item} className="text-foreground">
                        · {item}
                      </li>
                    ))}
                  </ul>
                </div>
                <div className="text-muted-foreground">{capabilities.ownerNote}</div>
              </div>
            </SectionCard>
          )}
        </>
      )}
    </div>
  );
}
