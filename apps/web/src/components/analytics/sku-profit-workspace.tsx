'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { api } from '@/lib/api';
import {
  amountText,
  availabilityLabel,
  availabilityTone,
  capabilitiesComponents,
  capabilitiesNotDelivered,
  componentLabel,
  componentReason,
  componentText,
  crossModeNote,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  drilldownPageNote,
  emptyText,
  isCrossMode,
  netProfitLabel,
  netProfitText,
  netProfitTone,
  netMarginText,
  operatingModeLabel,
  orderModeText,
  orderScopeText,
  pageState,
  quantityText,
  rangeText,
  waterfallCumulativeText,
  waterfallNetProfitText,
  waterfallStepText,
} from './sku-profit-model';
import type {
  SkuProfitCapabilities,
  SkuProfitOrderPage,
  SkuProfitResponse,
  SkuProfitWaterfall,
} from './sku-profit-model';

/**
 * 13.4.1.2 SKU 利润工作区（卷 13「利润与经营分析」L3 13.4.1 利润报表下的第二格）。
 *
 * <p>蓝图输出：「SKU 利润表 + 瀑布图 + 订单明细钻取。」
 *
 * <p><b>数据来自 reporting 服务（13 卷的唯一归属服务）的投影读模型</b>：
 *
 * <ul>
 *   <li>{@code GET reporting/sku-profit/report} —— SKU 利润表（SKU × 币种，不跨币种合并）；</li>
 *   <li>{@code GET reporting/sku-profit/waterfall} —— 构成瀑布（服务端算好的逐步）；</li>
 *   <li>{@code GET reporting/sku-profit/orders} —— 订单级明细钻取（真分页 + 真计数）；</li>
 *   <li>{@code GET reporting/sku-profit/capabilities} —— 能力声明（哪些上游真接通、哪些确实拿不到）。</li>
 * </ul>
 *
 * <p><b>🔴 页面必须做到的五件事</b>：
 *
 * <ol>
 *   <li><b>「算不出来」≠「等于 0」</b>：{@code null} 的成本项与净利显示「—」与「不可比」，
 *       并把服务端给的原因原样带出来，<strong>绝不用 0 顶替</strong>。</li>
 *   <li><b>「不可归属到 SKU」与「缺这一项」分开说</b>：前者是上游只分摊到店铺 / 订单
 *       （本模块<strong>不重算分摊</strong>），后者是上游在 SKU 维度上有、这个 SKU 没有。</li>
 *   <li><b>口径说明常驻</b>：收入怎么算、成本取自哪一路上游、哪些算不出来，都写在页面上。</li>
 *   <li><b>多币种不相加</b>：每一行带自己的币种。</li>
 *   <li><b>数据更新时间常驻</b>（卷 13 卷首加粗句「数据延迟要显示」）。</li>
 * </ol>
 */
export function AnalyticsSkuProfitWorkspace() {
  const today = new Date().toISOString().slice(0, 10);
  const initialRange = defaultRange(today);

  const [from, setFrom] = useState(initialRange.from);
  const [to, setTo] = useState(initialRange.to);
  const [skuCode, setSkuCode] = useState('');
  const [currency, setCurrency] = useState('');

  const [data, setData] = useState<SkuProfitResponse | null>(null);
  const [capabilities, setCapabilities] = useState<SkuProfitCapabilities | null>(null);
  const [waterfall, setWaterfall] = useState<SkuProfitWaterfall | null>(null);
  const [waterfallSku, setWaterfallSku] = useState('');
  const [orders, setOrders] = useState<SkuProfitOrderPage | null>(null);
  const [ordersSku, setOrdersSku] = useState('');
  const [ordersPageIndex, setOrdersPageIndex] = useState(0);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const query = useMemo(() => {
    const params = new URLSearchParams({ from, to });
    if (skuCode) params.set('skuCode', skuCode);
    if (currency) params.set('currency', currency);
    return params.toString();
  }, [from, to, skuCode, currency]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [report, caps] = await Promise.all([
        api<SkuProfitResponse>(`reporting/sku-profit/report?${query}`),
        api<SkuProfitCapabilities>('reporting/sku-profit/capabilities'),
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

  const loadWaterfall = useCallback(
    async (code: string, codeCurrency: string) => {
      setBusy(true);
      try {
        const params = new URLSearchParams({ skuCode: code, from, to });
        if (codeCurrency) params.set('currency', codeCurrency);
        setWaterfall(await api<SkuProfitWaterfall>(`reporting/sku-profit/waterfall?${params}`));
        setWaterfallSku(code);
      } catch (e) {
        setWaterfall(null);
        setError(e instanceof Error ? e.message : '读取构成瀑布失败，请重试');
      } finally {
        setBusy(false);
      }
    },
    [from, to],
  );

  const loadOrders = useCallback(
    async (code: string, codeCurrency: string, page: number) => {
      setBusy(true);
      try {
        const params = new URLSearchParams({
          skuCode: code,
          from,
          to,
          page: String(page),
          size: '50',
        });
        if (codeCurrency) params.set('currency', codeCurrency);
        setOrders(await api<SkuProfitOrderPage>(`reporting/sku-profit/orders?${params}`));
        setOrdersSku(code);
        setOrdersPageIndex(page);
      } catch (e) {
        setOrders(null);
        setError(e instanceof Error ? e.message : '读取订单明细失败，请重试');
      } finally {
        setBusy(false);
      }
    },
    [from, to],
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
        description="SKU 维度的成本**只取上游已分摊到 SKU 的那些行** —— 上游分摊到店铺 / 订单时，这一格显示「不可归属到 SKU」，**不是 0**。"
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
            SKU 编码（可空 = 全部）
            <input
              type="text"
              value={skuCode}
              placeholder="留空看全部 SKU"
              onChange={(e) => setSkuCode(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            币种（可空 = 全部，**不跨币种相加**）
            <input
              type="text"
              value={currency}
              placeholder="如 USD"
              onChange={(e) => setCurrency(e.target.value.toUpperCase())}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
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
          <EmptyState title="正在读取 SKU 利润…" description="读取期间页面上的「数据截至」仍然有效。" />
        </SectionCard>
      )}

      {state === 'empty' && (
        <SectionCard>
          <EmptyState title="没有 SKU 级事实" description={emptyText(data)} />
        </SectionCard>
      )}

      {state === 'ready' && data && (
        <>
          {/* ── 汇总 ─────────────────────────────────────────────────────── */}
          <SectionCard
            title="汇总"
            description="SKU 数与「净利可比 / 不可比」的个数。**不提供跨币种的净利合计** —— 那是不存在的数。"
          >
            <div className="text-copy-13 flex flex-wrap gap-6">
              <span>
                <span className="text-muted-foreground">SKU 数：</span>
                <span className="text-foreground tabular-nums">{data.skuCount}</span>
              </span>
              <span>
                <span className="text-muted-foreground">净利可比：</span>
                <span className="text-foreground tabular-nums">{data.comparableCount}</span>
              </span>
              <span>
                <span className="text-muted-foreground">净利不可比：</span>
                <span className="text-foreground tabular-nums">{data.notComparableCount}</span>
              </span>
            </div>
          </SectionCard>

          {/* ── SKU 利润表 ───────────────────────────────────────────────── */}
          <SectionCard
            title="SKU 利润表"
            description="一行 = 一个 SKU × 一个币种。净利 = 单品收入 − 各项成本；**算不出来时是「—」，不是 0**。"
          >
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="text-copy-13 text-muted-foreground">
                  <tr className="border-border border-b">
                    <th className="py-2 pr-3 font-medium">SKU</th>
                    <th className="py-2 pr-3 font-medium">币种</th>
                    <th className="py-2 pr-3 text-right font-medium">单品收入</th>
                    <th className="py-2 pr-3 text-right font-medium">成本合计</th>
                    <th className="py-2 pr-3 text-right font-medium">净利</th>
                    <th className="py-2 pr-3 text-right font-medium">净利率</th>
                    <th className="py-2 pr-3 font-medium">状态</th>
                    <th className="py-2 pr-3 font-medium">说明</th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((row) => (
                    <tr
                      key={`${row.skuCode}|${row.currency}`}
                      className="border-border border-b"
                    >
                      <td className="py-2 pr-3 whitespace-nowrap">
                        <button
                          type="button"
                          className="text-primary underline-offset-2 hover:underline"
                          onClick={() => {
                            void loadWaterfall(row.skuCode, row.currency);
                            void loadOrders(row.skuCode, row.currency, 0);
                          }}
                        >
                          {row.skuCode}
                        </button>
                      </td>
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
                        {netProfitText(row.netProfit, row.currency)}
                      </td>
                      <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                        {netMarginText(row.netMargin)}
                      </td>
                      <td className="py-2 pr-3">
                        <Badge tone={netProfitTone(row)}>{netProfitLabel(row)}</Badge>
                        {isCrossMode(row) && <Badge tone="warning">跨模式口径</Badge>}
                        <div className="text-copy-13 text-muted-foreground mt-1 max-w-md">{row.note}</div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </SectionCard>

          {/* ── 成本构成 ─────────────────────────────────────────────────── */}
          <SectionCard
            title="成本构成"
            description="每一项都写清取值与**为什么没有值**；「—」表示算不出来或不可归属（不是 0）。"
          >
            <div className="space-y-4">
              {data.rows.map((row) => (
                <div key={`${row.skuCode}|${row.currency}`} className="border-border rounded-lg border px-4 py-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-copy-14 text-foreground font-medium">
                      {row.skuCode} · {row.currency}
                    </span>
                    {isCrossMode(row) && (
                      <span className="text-copy-13 text-muted-foreground">{crossModeNote(row)}</span>
                    )}
                  </div>
                  <dl className="text-copy-13 mt-3 grid grid-cols-1 gap-x-6 gap-y-1 sm:grid-cols-2 lg:grid-cols-4">
                    {row.components.map((component) => (
                      <div key={component.component}>
                        <dt className="text-muted-foreground">{componentLabel(component)}</dt>
                        <dd className="text-foreground tabular-nums">
                          {componentText(component, row.currency)}
                        </dd>
                        <dd className="mt-0.5">
                          <Badge tone={availabilityTone(component.status)}>
                            {availabilityLabel(component.status)}
                          </Badge>
                        </dd>
                        {!component.available && component.reason && (
                          <dd className="text-copy-13 text-muted-foreground mt-0.5">
                            {componentReason(component)}
                          </dd>
                        )}
                      </div>
                    ))}
                  </dl>
                  {row.modeBreakdown.length > 0 && (
                    <div className="text-copy-13 text-muted-foreground mt-2">
                      收入的经营模式构成：
                      {row.modeBreakdown
                        .map(
                          (mode) =>
                            `${operatingModeLabel(mode.operatingMode, mode.operatingModeLabel)} ${amountText(mode.revenue, row.currency)}`,
                        )
                        .join(' · ')}
                    </div>
                  )}
                  {row.missingComponents.length > 0 && (
                    <div className="text-copy-13 text-muted-foreground mt-2">
                      缺 {row.missingComponents.join('、')} ⇒ 这一格的净利不可比。
                    </div>
                  )}
                </div>
              ))}
            </div>
          </SectionCard>

          {/* ── 构成瀑布 ─────────────────────────────────────────────────── */}
          <SectionCard
            title="构成瀑布"
            description="收入 → 逐项成本 → 净利。**每一步都是服务端算好的**，哪一步拿不到，净利就是「—（不可比）」。"
          >
            {!waterfallSku ? (
              <p className="text-copy-13 text-muted-foreground">
                还没有选择 SKU。在上面的 SKU 利润表里点一个 SKU 编码。
              </p>
            ) : (
              <div className="space-y-3">
                <div className="text-copy-13 text-muted-foreground">
                  {waterfallSku} · 净利 {waterfallNetProfitText(waterfall)}
                </div>
                {(waterfall?.steps ?? []).length === 0 ? (
                  <p className="text-copy-13 text-muted-foreground">
                    这个 SKU 在这个窗口内没有投影任何事实（不是「利润为 0」）。
                  </p>
                ) : (
                  <div className="overflow-x-auto">
                    <table className="w-full text-left text-sm">
                      <thead className="text-copy-13 text-muted-foreground">
                        <tr className="border-border border-b">
                          <th className="py-2 pr-3 font-medium">步骤</th>
                          <th className="py-2 pr-3 font-medium">项目</th>
                          <th className="py-2 pr-3 text-right font-medium">金额</th>
                          <th className="py-2 pr-3 text-right font-medium">累计</th>
                          <th className="py-2 pr-3 font-medium">说明</th>
                        </tr>
                      </thead>
                      <tbody>
                        {(waterfall?.steps ?? []).map((step) => (
                          <tr key={step.component} className="border-border border-b">
                            <td className="py-2 pr-3 tabular-nums">{step.step}</td>
                            <td className="py-2 pr-3 whitespace-nowrap">
                              {step.sign} {step.label}
                            </td>
                            <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                              {waterfallStepText(step)}
                            </td>
                            <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                              {waterfallCumulativeText(step)}
                            </td>
                            <td className="py-2 pr-3 text-muted-foreground">{step.reason ?? ''}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            )}
          </SectionCard>

          {/* ── 订单明细钻取 ─────────────────────────────────────────────── */}
          <SectionCard
            title="订单明细钻取"
            description="这个 SKU 背后的**事实行**（来源服务 + 上游行标识），可以逐条回到上游核对。"
          >
            {!ordersSku ? (
              <p className="text-copy-13 text-muted-foreground">
                还没有选择 SKU。在上面的 SKU 利润表里点一个 SKU 编码。
              </p>
            ) : (
              <div className="space-y-3">
                <div className="text-copy-13 text-muted-foreground">
                  {ordersSku} · {drilldownPageNote(orders)}
                </div>
                {orders && orders.items.length === 0 ? (
                  <p className="text-copy-13 text-muted-foreground">这个 SKU 背后没有事实行。</p>
                ) : (
                  <div className="overflow-x-auto">
                    <table className="w-full text-left text-sm">
                      <thead className="text-copy-13 text-muted-foreground">
                        <tr className="border-border border-b">
                          <th className="py-2 pr-3 font-medium">成本项</th>
                          <th className="py-2 pr-3 font-medium">经营模式</th>
                          <th className="py-2 pr-3 font-medium">归属</th>
                          <th className="py-2 pr-3 font-medium">期间</th>
                          <th className="py-2 pr-3 text-right font-medium">金额</th>
                          <th className="py-2 pr-3 text-right font-medium">数量</th>
                          <th className="py-2 pr-3 font-medium">来源</th>
                        </tr>
                      </thead>
                      <tbody>
                        {(orders?.items ?? []).map((item, index) => (
                          <tr
                            key={`${item.component}|${item.sourceReference}|${index}`}
                            className="border-border border-b"
                          >
                            <td className="py-2 pr-3">{item.componentLabel}</td>
                            <td className="py-2 pr-3 whitespace-nowrap">{orderModeText(item)}</td>
                            <td className="py-2 pr-3">{orderScopeText(item)}</td>
                            <td className="py-2 pr-3 whitespace-nowrap">{item.periodStart}</td>
                            <td className="py-2 pr-3 text-right tabular-nums whitespace-nowrap">
                              {amountText(item.amount, item.currency)}
                            </td>
                            <td className="py-2 pr-3 text-right tabular-nums">
                              {quantityText(item.quantity)}
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
                    disabled={busy || ordersPageIndex === 0}
                    onClick={() =>
                      void loadOrders(ordersSku, orders?.currency ?? '', Math.max(ordersPageIndex - 1, 0))
                    }
                  >
                    上一页
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={
                      busy || !orders || (ordersPageIndex + 1) * orders.size >= orders.total
                    }
                    onClick={() => void loadOrders(ordersSku, orders?.currency ?? '', ordersPageIndex + 1)}
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
                  <span className="text-foreground">{capabilitiesComponents(capabilities)}</span>
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
