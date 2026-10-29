'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { api } from '@/lib/api';
import {
  METRIC_ADD_TO_CART,
  METRIC_ATTRIBUTED_AOV,
  METRIC_ATTRIBUTED_GMV,
  METRIC_ATTRIBUTED_ORDERS,
  METRIC_CLICKS,
  METRIC_CTR,
  METRIC_IMPRESSIONS,
  METRIC_ROI,
  METRIC_SPEND,
  amountText,
  attributionModelLabel,
  availabilityLabel,
  availabilityTone,
  caliberLabel,
  commissionBasisLabel,
  currencyBreakdown,
  currencyLabel,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  emptyReasonLabel,
  emptyText,
  metricFormulaText,
  metricOf,
  metricUnitLabel,
  metricValueText,
  notDeliveredLines,
  pageState,
  poolWeightBasisLabel,
  quantityText,
  rangeText,
  refreshStatusLabel,
  refreshStatusTone,
  refundTreatmentLabel,
  ruleSummary,
  slotTypeLabel,
  slotTypeTone,
  statusLabel,
  upstreamModules,
} from './affiliate-marketing-model';
import type {
  AffiliateMarketingAttributionPage,
  AffiliateMarketingCapabilities,
  AffiliateMarketingMetricDefinitionView,
  AffiliateMarketingRefreshResult,
  AffiliateMarketingResponse,
  AffiliateMarketingSlotRow,
} from './affiliate-marketing-model';

/**
 * 13.3.2.2 关联营销效果工作区（卷 13「利润与经营分析」L3 13.3.2 流量与转化下的第二格）。
 *
 * <p>蓝图输出：「营销位效果报表 + ROI。」
 *
 * <p><b>数据来自 reporting 服务（13 卷的唯一归属服务）的投影读模型</b>：
 *
 * <ul>
 *   <li>{@code GET reporting/affiliate-marketing} —— 营销位效果报表（按 (营销位, 归因规则) 分列）；</li>
 *   <li>{@code GET reporting/affiliate-marketing/attributions} —— 归因明细（每条带规则快照）；</li>
 *   <li>{@code GET reporting/affiliate-marketing/capabilities} —— 能力声明；</li>
 *   <li>{@code GET reporting/affiliate-marketing/metrics} —— 指标口径目录；</li>
 *   <li>{@code POST reporting/affiliate-marketing/refresh} —— 刷新投影。</li>
 * </ul>
 *
 * <p><b>🔴 页面必须做到的四件事</b>：
 *
 * <ol>
 *   <li><b>归因规则贴在行头</b>：每行都显示它用的是哪条规则快照，<b>ROI 只在同一条规则内部相除</b>。</li>
 *   <li><b>「算不出来」≠「等于 0」</b>：{@code null} 显示「—」/「不可算」+ 原因，
 *       <b>绝不用 0 顶替</b>。</li>
 *   <li><b>「本轮不可用」要看得见</b>：加购 / 关联视频 / Store 导流 / 达人站外曝光，
 *       显示「本轮不可用 + 归谁管 + 当前替代口径」，<b>不是 0</b>。</li>
 *   <li><b>数据更新时间常驻</b>（卷 13 卷首加粗句「数据延迟要显示」）：加载中 / 出错时也显示。</li>
 * </ol>
 *
 * <p><b>🔴 前端不做业务算术</b>：ROI / 点击率 / 归因客单价全部来自服务端，
 * 这里只做展示格式化。前端绝自己把两个币种的金额加起来，也绝不自己算比值。
 */
export function AnalyticsAffiliateMarketingWorkspace() {
  const today = new Date().toISOString().slice(0, 10);
  const initialRange = defaultRange(today);

  const [from, setFrom] = useState(initialRange.from);
  const [to, setTo] = useState(initialRange.to);
  const [drillPage, setDrillPage] = useState(0);
  const [drillSlotType, setDrillSlotType] = useState('');

  const [data, setData] = useState<AffiliateMarketingResponse | null>(null);
  const [attributions, setAttributions] = useState<AffiliateMarketingAttributionPage | null>(null);
  // 🔴 下钻有自己的错误态：下钻读不到**不应该**把整张报表打成错误态。
  const [drillError, setDrillError] = useState('');
  const [capabilities, setCapabilities] = useState<AffiliateMarketingCapabilities | null>(null);
  const [definitions, setDefinitions] = useState<AffiliateMarketingMetricDefinitionView[]>([]);
  const [refreshResults, setRefreshResults] = useState<AffiliateMarketingRefreshResult[] | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const query = useMemo(() => {
    const params = new URLSearchParams({ from, to });
    return params.toString();
  }, [from, to]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [report, caps, metrics] = await Promise.all([
        api<AffiliateMarketingResponse>(`reporting/affiliate-marketing?${query}`),
        api<AffiliateMarketingCapabilities>('reporting/affiliate-marketing/capabilities'),
        api<AffiliateMarketingMetricDefinitionView[]>('reporting/affiliate-marketing/metrics'),
      ]);
      setData(report);
      setCapabilities(caps);
      setDefinitions(metrics);
    } catch (e) {
      setData(null);
      setError(e instanceof Error ? e.message : '读取关联营销报表失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [query]);

  const loadAttributions = useCallback(async () => {
    setDrillError('');
    try {
      const params = new URLSearchParams({
        from,
        to,
        page: String(drillPage),
        size: '20',
      });
      if (drillSlotType) params.set('slotType', drillSlotType);
      setAttributions(
        await api<AffiliateMarketingAttributionPage>(
          `reporting/affiliate-marketing/attributions?${params}`,
        ),
      );
    } catch (e) {
      setAttributions(null);
      setDrillError(e instanceof Error ? e.message : '读取归因明细失败，请重试');
    }
  }, [from, to, drillPage, drillSlotType]);

  const refreshProjection = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const params = new URLSearchParams({ from, to });
      const results = await api<AffiliateMarketingRefreshResult[]>(
        `reporting/affiliate-marketing/refresh?${params}`,
        'POST',
      );
      setRefreshResults(results);
      // 刷完立刻重读 —— 投影变了，页面上的数必须跟着变。
      await load();
      await loadAttributions();
    } catch (e) {
      setError(e instanceof Error ? e.message : '刷新关联营销投影失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [from, to, load, loadAttributions]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void loadAttributions();
  }, [loadAttributions]);

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

      {/* ── 时间范围与刷新 ─────────────────────────────────────────────── */}
      <SectionCard
        title="时间范围与投影刷新"
        description="🔴 归因规则必须显式：本报表的成交归因用的是上游归因记录自带的那份规则快照，不是「当前生效规则」—— 规则改了以后历史那一格仍然说得清它是怎么算出来的。报表按 (营销位, 归因规则) 分列，ROI 只在同一条规则内部相除。"
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
          <Button
            variant="outline"
            size="sm"
            disabled={busy}
            onClick={() => void refreshProjection()}
          >
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            刷新关联营销投影
          </Button>
        </div>
        <p className="text-copy-13 text-muted-foreground mt-3">
          状态：{statusLabel(data?.status ?? 'EMPTY')}。 ⚠️ 统计日口径：达人归因口径按
          <b>归因发生日（UTC 自然日）</b>切桶（上游归因记录不带订单日期），落地商品口径按
          <b>平台自然日</b>切桶 —— 两者不是一个日。
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
            title="关联营销报表读不到"
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
            title="正在读取关联营销报表…"
            description="读取期间页面上的「数据截至」仍然有效。"
          />
        </SectionCard>
      )}

      {/* ── 空态 ───────────────────────────────────────────────────────── */}
      {state === 'empty' && (
        <SectionCard>
          <EmptyState
            title={emptyReasonLabel(data?.emptyReason ?? null) || '没有关联营销事实'}
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

          {/* ── 警告 ───────────────────────────────────────────────────── */}
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

          {/* ── 营销位类型逐类状态 ─────────────────────────────────────── */}
          <SectionCard
            title="四类营销位逐类状态"
            description="🔴 蓝图点了四类营销位（关联视频、A+、Store 导流、社交媒体），但它们的**数据口径不一样**。本轮拿不到的类型**显式写明归谁管 + 当前替代口径**，页面显示「本轮不可用」—— **不是 0**，也不是不出现。"
          >
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="text-copy-13 text-muted-foreground">
                  <tr className="border-border border-b">
                    <th className="py-2 pr-3 font-medium">营销位类型</th>
                    <th className="py-2 pr-3 font-medium">本轮状态</th>
                    <th className="py-2 pr-3 font-medium">数据口径</th>
                    <th className="py-2 pr-3 font-medium">上游 / 归属</th>
                    <th className="py-2 pr-3 font-medium">说明</th>
                  </tr>
                </thead>
                <tbody>
                  {data.slotTypes.map((slotType) => (
                    <tr key={slotType.slotType} className="border-border border-b">
                      <td className="py-2 pr-3">{slotType.displayName}</td>
                      <td className="py-2 pr-3">
                        <Badge tone={slotTypeTone(slotType.available)}>
                          {slotType.available ? '本轮已交付' : '本轮不可用'}
                        </Badge>
                      </td>
                      <td className="py-2 pr-3">
                        {slotType.caliber ? caliberLabel(slotType.caliber) : '—'}
                      </td>
                      <td className="text-copy-13 text-muted-foreground py-2 pr-3">
                        {slotType.owner}
                      </td>
                      <td className="text-copy-13 text-muted-foreground py-2 pr-3">
                        {slotType.note}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </SectionCard>

          {/* ── 归因规则清单 ───────────────────────────────────────────── */}
          <SectionCard
            title="本窗口用到的归因规则（规则快照）"
            description="🔴 这些是**上游归因记录自带的那份快照**，不是「当前生效规则」—— 规则改了以后历史这一格仍然说得清它当时是怎么算的。⚠️ 上游的归因模型词表是「合作全额归因 / 活动池分摊」，**不是**「末次点击 / 首次点击」（那属 14 广告）。"
          >
            {data.attributionRules.length === 0 ? (
              <p className="text-copy-13 text-muted-foreground">
                这个窗口没有任何归因记录 ⇒ 没有规则快照可显示（**不是**「没有规则」）。
              </p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="text-copy-13 text-muted-foreground">
                    <tr className="border-border border-b">
                      <th className="py-2 pr-3 font-medium">规则码</th>
                      <th className="py-2 pr-3 font-medium">归因模型</th>
                      <th className="py-2 pr-3 font-medium">分摊权重基准</th>
                      <th className="py-2 pr-3 text-right font-medium">归因窗口（天）</th>
                      <th className="py-2 pr-3 font-medium">退款口径</th>
                      <th className="py-2 pr-3 font-medium">佣金口径</th>
                      <th className="py-2 pr-3 text-right font-medium">归因记录条数</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.attributionRules.map((rule) => (
                      <tr key={rule.ruleCode} className="border-border border-b">
                        <td className="py-2 pr-3">{rule.ruleCode || '—'}</td>
                        <td className="py-2 pr-3">{attributionModelLabel(rule.attributionModel)}</td>
                        <td className="py-2 pr-3">{poolWeightBasisLabel(rule.poolWeightBasis)}</td>
                        <td className="py-2 pr-3 text-right">
                          {rule.attributionWindowDays ?? '—'}
                        </td>
                        <td className="py-2 pr-3">{refundTreatmentLabel(rule.refundTreatment)}</td>
                        <td className="py-2 pr-3">{commissionBasisLabel(rule.commissionBasis)}</td>
                        <td className="py-2 pr-3 text-right">{rule.recordCount}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </SectionCard>

          {/* ── 营销位效果报表 ─────────────────────────────────────────── */}
          <SectionCard
            title="营销位效果报表 + ROI"
            description="🔴 每一行是 **(营销位, 归因规则)** —— 行头贴着它用的是哪条规则快照，**ROI 只在同一条规则内部相除**。两套口径分列：达人归因口径（成交归因 / 投入 / ROI）与落地商品口径（曝光 / 点击 / 点击率），**互不冒充**。「—」= 算不出来，不是 0。"
          >
            <div className="space-y-4">
              {data.slots.map((row) => (
                <SlotRowCard key={`${row.slotType}-${row.slotKey}-${row.attributionRuleCode}`} row={row} />
              ))}
            </div>
          </SectionCard>

          {/* ── 归因明细下钻 ───────────────────────────────────────────── */}
          <SectionCard
            title="归因明细下钻"
            description="卷 13 卷首加粗句「可下钻」的落点：每个汇总数字都能钻到构成明细。🔴 每行都带规则快照；⚠️ 统计日按「归因发生日（UTC 自然日）」切桶 —— 上游归因记录**不带订单日期**。"
          >
            <div className="flex flex-wrap items-end gap-4">
              <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
                营销位类型
                <select
                  value={drillSlotType}
                  onChange={(e) => {
                    setDrillSlotType(e.target.value);
                    setDrillPage(0);
                  }}
                  className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                >
                  <option value="">全部</option>
                  {data.slotTypes.map((slotType) => (
                    <option key={slotType.slotType} value={slotType.slotType}>
                      {slotType.displayName}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            {drillError && (
              <p className="text-copy-13 text-destructive mt-3">
                ⚠️ {drillError}（这是「读不到」，不是「没有数据」）
              </p>
            )}
            {attributions && attributions.items.length === 0 ? (
              <p className="text-copy-13 text-muted-foreground mt-3">
                这个窗口没有归因明细（**不是 0 条归因**，是真的没有）。
              </p>
            ) : (
              attributions && (
                <div className="mt-3 overflow-x-auto">
                  <table className="w-full text-left text-sm">
                    <thead className="text-copy-13 text-muted-foreground">
                      <tr className="border-border border-b">
                        <th className="py-2 pr-3 font-medium">归因时刻</th>
                        <th className="py-2 pr-3 font-medium">达人 / 活动</th>
                        <th className="py-2 pr-3 font-medium">归因规则</th>
                        <th className="py-2 pr-3 text-right font-medium">归因成交</th>
                        <th className="py-2 pr-3 text-right font-medium">归因订单</th>
                        <th className="py-2 pr-3 text-right font-medium">佣金成本</th>
                        <th className="py-2 pr-3 font-medium">币种</th>
                      </tr>
                    </thead>
                    <tbody>
                      {attributions.items.map((row) => (
                        <tr key={row.attributionId} className="border-border border-b">
                          <td className="py-2 pr-3">{row.attributedAt ?? '—'}</td>
                          <td className="py-2 pr-3">
                            {row.influencerName || row.influencerId}
                            <span className="text-muted-foreground"> × </span>
                            {row.campaignName || row.campaignId}
                          </td>
                          <td className="text-copy-13 py-2 pr-3">
                            {row.ruleCode || '—'} · {attributionModelLabel(row.attributionModel)}
                          </td>
                          <td className="py-2 pr-3 text-right">
                            {amountText(row.attributedGmv, row.currency)}
                          </td>
                          <td className="py-2 pr-3 text-right">
                            {quantityText(row.attributedOrderCount)}
                          </td>
                          <td className="py-2 pr-3 text-right">
                            {amountText(row.commissionAmount, row.currency)}
                          </td>
                          <td className="py-2 pr-3">{currencyLabel(row.currency)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  <div className="text-copy-13 text-muted-foreground mt-3 flex items-center gap-3">
                    <span>
                      第 {attributions.page + 1} 页 / 共 {attributions.total} 条
                    </span>
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={drillPage === 0}
                      onClick={() => setDrillPage((page) => Math.max(page - 1, 0))}
                    >
                      上一页
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={(drillPage + 1) * 20 >= attributions.total}
                      onClick={() => setDrillPage((page) => page + 1)}
                    >
                      下一页
                    </Button>
                  </div>
                </div>
              )
            )}
          </SectionCard>

          {/* ── 指标口径目录 ───────────────────────────────────────────── */}
          <SectionCard
            title="指标口径目录"
            description="口径唯一的展示形态：派生指标（ROI / 点击率 / 归因客单价）**只写分子分母引用**，服务端在查询期算 —— 库里没有第二个真相。"
          >
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="text-copy-13 text-muted-foreground">
                  <tr className="border-border border-b">
                    <th className="py-2 pr-3 font-medium">指标</th>
                    <th className="py-2 pr-3 font-medium">单位</th>
                    <th className="py-2 pr-3 font-medium">聚合</th>
                    <th className="py-2 pr-3 font-medium">怎么算</th>
                    <th className="py-2 pr-3 font-medium">本轮</th>
                    <th className="py-2 pr-3 font-medium">口径说明</th>
                  </tr>
                </thead>
                <tbody>
                  {definitions.map((definition) => (
                    <tr key={definition.metricCode} className="border-border border-b">
                      <td className="py-2 pr-3">{definition.displayName}</td>
                      <td className="py-2 pr-3">{metricUnitLabel(definition.unit)}</td>
                      <td className="py-2 pr-3">{definition.aggregation}</td>
                      <td className="text-copy-13 py-2 pr-3">
                        {metricFormulaText(definition)}
                      </td>
                      <td className="py-2 pr-3">
                        <Badge tone={definition.available ? 'success' : 'warning'}>
                          {definition.available ? '可用' : '本轮不可用'}
                        </Badge>
                        {!definition.available && (
                          <div className="text-copy-13 text-muted-foreground">
                            归 {definition.ownerModule} 管
                          </div>
                        )}
                      </td>
                      <td className="text-copy-13 text-muted-foreground py-2 pr-3">
                        {definition.description}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </SectionCard>

          {/* ── 能力边界 ───────────────────────────────────────────────── */}
          <SectionCard
            title="能力边界"
            description="把「拿不到」说清楚，比让界面显示一个必然失败的按钮好。"
          >
            <div className="space-y-2">
              <div>
                <h4 className="text-copy-13 text-muted-foreground mb-1">本服务真的读过的上游</h4>
                <ul className="text-copy-13 text-muted-foreground list-disc pl-5">
                  {upstreamModules(capabilities).map((line) => (
                    <li key={line}>{line}</li>
                  ))}
                </ul>
              </div>
              <div>
                <h4 className="text-copy-13 text-muted-foreground mb-1">确实拿不到（归谁管 + 替代口径）</h4>
                <ul className="text-copy-13 text-muted-foreground list-disc pl-5">
                  {notDeliveredLines(capabilities).map((item) => (
                    <li key={item.dimension}>
                      <span className="text-foreground">{item.description}</span>
                      ：{item.impact}（归 {item.ownerModule} 管）
                    </li>
                  ))}
                </ul>
              </div>
            </div>
          </SectionCard>
        </>
      )}
    </div>
  );
}

/**
 * 一个营销位（× 一条归因规则）的效果行。
 *
 * <p>🔴 **行头必须贴着归因规则** —— 不然看的人不知道这条数是用哪条规则算出来的。
 */
function SlotRowCard({ row }: { row: AffiliateMarketingSlotRow }) {
  const gmv = currencyBreakdown(row, METRIC_ATTRIBUTED_GMV);
  const spend = currencyBreakdown(row, METRIC_SPEND);
  const roi = currencyBreakdown(row, METRIC_ROI);
  const impressions = metricOf(row, METRIC_IMPRESSIONS);
  const clicks = metricOf(row, METRIC_CLICKS);
  const ctr = metricOf(row, METRIC_CTR);
  const orders = metricOf(row, METRIC_ATTRIBUTED_ORDERS);
  const aov = currencyBreakdown(row, METRIC_ATTRIBUTED_AOV);
  const addToCart = metricOf(row, METRIC_ADD_TO_CART);

  return (
    <div className="border-border rounded-lg border px-4 py-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-copy-14 text-foreground font-medium">{row.slotLabel}</span>
        <Badge tone="info">{row.slotTypeName}</Badge>
        <Badge tone="neutral">{caliberLabel(row.caliber)}</Badge>
        {row.attributionRuleCode && <Badge tone="primary">规则 {row.attributionRuleCode}</Badge>}
        <span className="text-copy-13 text-muted-foreground">
          归因记录 {row.attributionRecordCount} 条
        </span>
      </div>
      <p className="text-copy-13 text-muted-foreground mt-1">🔴 归因规则快照：{ruleSummary(row)}</p>
      <div className="mt-3 overflow-x-auto">
        <table className="w-full text-left text-sm">
          <thead className="text-copy-13 text-muted-foreground">
            <tr className="border-border border-b">
              <th className="py-2 pr-3 font-medium">指标</th>
              <th className="py-2 pr-3 text-right font-medium">取值</th>
              <th className="py-2 pr-3 font-medium">币种 / 口径</th>
              <th className="py-2 pr-3 font-medium">可用性</th>
            </tr>
          </thead>
          <tbody>
            <MetricLine
              label="曝光"
              metric={
                impressions
                  ? { ...impressions, displayName: '曝光' }
                  : {
                      metricCode: METRIC_IMPRESSIONS,
                      displayName: '曝光',
                      unit: 'QUANTITY',
                      currency: '',
                      value: null,
                      quantity: null,
                      availability: 'NOT_AVAILABLE',
                      availabilityNote: '本轮不可用',
                    }
              }
            />
            <MetricLine
              label="点击"
              metric={
                clicks
                  ? { ...clicks, displayName: '点击' }
                  : {
                      metricCode: METRIC_CLICKS,
                      displayName: '点击',
                      unit: 'QUANTITY',
                      currency: '',
                      value: null,
                      quantity: null,
                      availability: 'NOT_AVAILABLE',
                      availabilityNote: '本轮不可用',
                    }
              }
            />
            <MetricLine
              label="加购"
              metric={
                addToCart
                  ? { ...addToCart, displayName: '加购' }
                  : {
                      metricCode: METRIC_ADD_TO_CART,
                      displayName: '加购',
                      unit: 'QUANTITY',
                      currency: '',
                      value: null,
                      quantity: null,
                      availability: 'NOT_AVAILABLE',
                      availabilityNote: '本轮不可用',
                    }
              }
            />
            <MetricLine
              label="点击率"
              metric={
                ctr
                  ? { ...ctr, displayName: '点击率' }
                  : {
                      metricCode: METRIC_CTR,
                      displayName: '点击率',
                      unit: 'PERCENT',
                      currency: '',
                      value: null,
                      quantity: null,
                      availability: 'NOT_AVAILABLE',
                      availabilityNote: '本轮不可用',
                    }
              }
            />
            <MetricLine
              label="归因订单数"
              metric={
                orders
                  ? { ...orders, displayName: '归因订单数' }
                  : {
                      metricCode: METRIC_ATTRIBUTED_ORDERS,
                      displayName: '归因订单数',
                      unit: 'COUNT',
                      currency: '',
                      value: null,
                      quantity: null,
                      availability: 'NOT_AVAILABLE',
                      availabilityNote: '本轮不可用',
                    }
                  }
            />
            {gmv.map((entry) => (
              <MetricLine
                key={`gmv-${entry.currency}`}
                label="归因成交金额"
                metric={{ ...entry.metric, displayName: '归因成交金额' }}
              />
            ))}
            {spend.map((entry) => (
              <MetricLine
                key={`spend-${entry.currency}`}
                label="营销投入（佣金成本）"
                metric={{ ...entry.metric, displayName: '营销投入（佣金成本）' }}
              />
            ))}
            {roi.map((entry) => (
              <MetricLine
                key={`roi-${entry.currency}`}
                label="ROI"
                metric={{ ...entry.metric, displayName: 'ROI' }}
              />
            ))}
            {aov.map((entry) => (
              <MetricLine
                key={`aov-${entry.currency}`}
                label="归因客单价"
                metric={{ ...entry.metric, displayName: '归因客单价' }}
              />
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/** 一行指标（取值 + 币种 / 口径 + 可用性）。 */
function MetricLine({
  label,
  metric,
}: {
  label: string;
  metric: {
    metricCode: string;
    displayName: string;
    unit: string;
    currency: string;
    value: number | null;
    quantity: number | null;
    availability: string;
    availabilityNote: string;
  };
}) {
  return (
    <tr className="border-border border-b">
      <td className="py-2 pr-3">{label}</td>
      <td className="py-2 pr-3 text-right">{metricValueText(metric)}</td>
      <td className="text-copy-13 text-muted-foreground py-2 pr-3">
        {metric.unit === 'CURRENCY' ? currencyLabel(metric.currency) : '与币种无关'}
      </td>
      <td className="py-2 pr-3">
        <Badge tone={availabilityTone(metric.availability)}>
          {availabilityLabel(metric.availability)}
        </Badge>
        {metric.availabilityNote && (
          <div className="text-copy-13 text-muted-foreground">{metric.availabilityNote}</div>
        )}
      </td>
    </tr>
  );
}
