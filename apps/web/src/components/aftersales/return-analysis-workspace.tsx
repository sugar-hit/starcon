'use client';

import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  PageHeader,
  SectionCard,
  Segmented,
  StatCard,
} from '@starcon/ui';
import type {
  ReturnAnalysisBasisPage,
  ReturnAnalysisBasisRow,
  ReturnAnalysisCapabilities,
  ReturnAnalysisOverview,
  ReturnAnalysisSnapshotPage,
  ReturnAnomalyReport,
  ReturnBreakdownReport,
  ReturnTrendReport,
} from '../../../../../packages/api-contracts/src/index';
import {
  ANOMALY_LABELS,
  BASIS_STATUS_LABELS,
  caliberSourceLabel,
  CALIBER_LABELS,
  createBasis,
  createSnapshot,
  DEFAULT_THRESHOLDS,
  DIMENSION_LABELS,
  DIMENSION_VALUES,
  fetchAnomalies,
  fetchBasis,
  fetchBreakdown,
  fetchCapabilities,
  fetchOverview,
  fetchSnapshots,
  fetchTrend,
  formatHours,
  formatMoney,
  formatQty,
  formatRate,
  formatRatio,
  GRANULARITY_LABELS,
  GRANULARITY_VALUES,
  rateTone,
  rejectionTone,
  returnRateReading,
  severityTone,
  snapshotDimensionLabel,
  supersedeBasis,
  type Tone,
} from './return-analysis-model';

/**
 * 11.5.1.1 退货分析。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「统计退货情况：退货率（按店铺/SKU/类目/原因/批次）、
 * 退货金额趋势、拒退率、处理时效、平均退款金额与原因分布。」
 * 输出：「<b>退货分析报表 + 维度下钻 + 异常识别</b>。」
 * 加粗句：「<b>退货率必须与销售额同口径比较</b>，只看绝对数会误判（退货率从 3% 升到 5% 严重，
 * 但绝对数可能没变）。」
 *
 * <p><b>页面围绕那条加粗句组织</b>：
 *
 * <ol>
 *   <li><b>退货率不放在「总数」位置</b>，而是**按币种一行一行**给出来 ——
 *       分子（退货金额）与分母（销售额）必须落在同一个币种里才允许相除。
 *       跨币种只有一种做法：本页**不给跨币种的合计金额与合计退货率**，
 *       下钻表在多币种时合计三件显示为「—」并在口径说明里写明原因。</li>
 *   <li><b>每个数字都带口径</b>：单头口径 / 行级口径 / 件数口径三者不可比，
 *       每一行都标出自己是哪个口径算出来的。</li>
 *   <li><b>算不出来就显示「—」</b>：分母为 0、没有样本、没有行级金额 ——
 *       这些都渲染成「—」而不是 0，否则「算不出来」会被读成「很健康」。</li>
 * </ol>
 *
 * <p><b>页面刻意没有的三个东西</b>：
 *
 * <ol>
 *   <li><b>「退货率预测」与「最优解」</b>：本仓没有历史建模与预测设施，
 *       画一条预测曲线就是在冒充模型能力。</li>
 *   <li><b>跨币种「退货总金额」大数字</b>：那是加粗句要防的那个错。</li>
 *   <li><b>「写入供应商评级」「发起供应商索赔」</b>：属 6.1.1.3 / 6.4.2.2，<b>均未交付</b>，
 *       能力声明会如实把它们报成不可用。</li>
 * </ol>
 */
export function ReturnAnalysisWorkspace() {
  const [caps, setCaps] = useState<ReturnAnalysisCapabilities | null>(null);
  const [overview, setOverview] = useState<ReturnAnalysisOverview | null>(null);
  const [breakdown, setBreakdown] = useState<ReturnBreakdownReport | null>(null);
  const [trend, setTrend] = useState<ReturnTrendReport | null>(null);
  const [anomalies, setAnomalies] = useState<ReturnAnomalyReport | null>(null);
  const [snapshots, setSnapshots] = useState<ReturnAnalysisSnapshotPage | null>(null);
  const [basis, setBasis] = useState<ReturnAnalysisBasisPage | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [currency, setCurrency] = useState('');
  const [dimension, setDimension] = useState<string>('SHOP');
  const [granularity, setGranularity] = useState<string>('MONTH');

  // 口径登记表单。
  const [basisName, setBasisName] = useState('');
  const [basisCurrency, setBasisCurrency] = useState('');
  const [includeRefundOnly, setIncludeRefundOnly] = useState(false);
  const [effectiveFrom, setEffectiveFrom] = useState('');
  const [rateMultiplier, setRateMultiplier] = useState(String(DEFAULT_THRESHOLDS.rateAlertMultiplier));
  const [minSalesShare, setMinSalesShare] = useState(String(DEFAULT_THRESHOLDS.rateAlertMinSalesShare));
  const [rejectionGapPp, setRejectionGapPp] = useState(String(DEFAULT_THRESHOLDS.rejectionAlertGapPp));
  const [rejectionMinCases, setRejectionMinCases] = useState(
    String(DEFAULT_THRESHOLDS.rejectionAlertMinCases),
  );
  const [batchMultiplier, setBatchMultiplier] = useState(String(DEFAULT_THRESHOLDS.batchAlertMultiplier));
  const [batchMinQty, setBatchMinQty] = useState(String(DEFAULT_THRESHOLDS.batchAlertMinQty));
  const [reasonShare, setReasonShare] = useState(String(DEFAULT_THRESHOLDS.reasonAlertShare));
  const [reasonMinCases, setReasonMinCases] = useState(String(DEFAULT_THRESHOLDS.reasonAlertMinCases));
  const [trendGapPp, setTrendGapPp] = useState(String(DEFAULT_THRESHOLDS.trendAlertGapPp));
  const [basisNote, setBasisNote] = useState('');

  // 口径换版表单（只对选中的生效口径开放）。
  const [supersedeTarget, setSupersedeTarget] = useState<ReturnAnalysisBasisRow | null>(null);
  const [supersedeReason, setSupersedeReason] = useState('');

  /**
   * 期间与币种筛选。
   *
   * ⚠️ `exactOptionalPropertyTypes` 开着（AGENTS.md）：**没填的筛选项不能显式给 undefined** ——
   *   `from?: string` 与 `from: string | undefined` 不是同一个类型。
   *   所以这里按「填了才建键」处理，而不是一律塞 undefined。
   */
  const window: { from?: string; to?: string; currency?: string } = {
    ...(from ? { from } : {}),
    ...(to ? { to } : {}),
    ...(currency ? { currency } : {}),
  };

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [c, o, b, t, a, s, bs] = await Promise.all([
        fetchCapabilities(),
        fetchOverview(window),
        fetchBreakdown({ ...window, dimension }),
        fetchTrend({ ...window, granularity }),
        fetchAnomalies(window),
        fetchSnapshots({ page: 0, size: 20 }),
        fetchBasis({ page: 0, size: 20 }),
      ]);
      setCaps(c);
      setOverview(o);
      setBreakdown(b);
      setTrend(t);
      setAnomalies(a);
      setSnapshots(s);
      setBasis(bs);
    } catch (e) {
      setError(e instanceof Error ? e.message : '退货分析报表加载失败');
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [from, to, currency, dimension, granularity]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 已出现的币种（真实数据，不硬编码）。 */
  const currencies = overview?.currencies.map((c) => c.currency) ?? [];

  /** 下钻行里出现的币种 —— 多个就是跨币种，合计不可用。 */
  const breakdownCurrencies = Array.from(new Set(breakdown?.rows.map((r) => r.currency) ?? []));
  const crossCurrency = breakdownCurrencies.length > 1;
  const noAmountCaliber = breakdown?.caliber === 'QTY_LEVEL';

  async function saveSnapshot() {
    setActionError('');
    setNotice('');
    if (!currency) {
      setActionError('快照按币种保存 —— 请先在上面选定一个币种（跨币种的退货率没有意义）。');
      return;
    }
    try {
      await createSnapshot({
        from: from ? from : null,
        to: to ? to : null,
        currency,
        dimension,
      });
      setNotice('已保存一份报表快照（追加式：算错的那份留着，重存一份即可）。');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '保存快照失败');
    }
  }

  async function submitBasis() {
    setActionError('');
    setNotice('');
    try {
      await createBasis({
        basisName: basisName || '退货分析口径',
        currency: basisCurrency.toUpperCase(),
        includeRefundOnly,
        rateAlertMultiplier: Number(rateMultiplier),
        rateAlertMinSalesShare: Number(minSalesShare),
        rejectionAlertGapPp: Number(rejectionGapPp),
        rejectionAlertMinCases: Number(rejectionMinCases),
        batchAlertMultiplier: Number(batchMultiplier),
        batchAlertMinQty: Number(batchMinQty),
        reasonAlertShare: Number(reasonShare),
        reasonAlertMinCases: Number(reasonMinCases),
        trendAlertGapPp: Number(trendGapPp),
        effectiveFrom: effectiveFrom || new Date().toISOString().slice(0, 10),
        sourceNote: basisNote,
      });
      setNotice('口径已登记 —— 该币种之后的报表按这套阈值判异常。');
      setBasisName('');
      setBasisNote('');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '登记口径失败');
    }
  }

  async function submitSupersede() {
    setActionError('');
    setNotice('');
    if (!supersedeTarget) {
      setActionError('请先在下方口径列表里选一份要换版的口径。');
      return;
    }
    if (!supersedeReason.trim()) {
      setActionError('换版必须写理由 —— 换版是留痕，事后要能查清为什么改。');
      return;
    }
    try {
      await supersedeBasis(supersedeTarget.id, {
        basisName: basisName || supersedeTarget.basisName,
        currency: supersedeTarget.currency,
        includeRefundOnly,
        rateAlertMultiplier: Number(rateMultiplier),
        rateAlertMinSalesShare: Number(minSalesShare),
        rejectionAlertGapPp: Number(rejectionGapPp),
        rejectionAlertMinCases: Number(rejectionMinCases),
        batchAlertMultiplier: Number(batchMultiplier),
        batchAlertMinQty: Number(batchMinQty),
        reasonAlertShare: Number(reasonShare),
        reasonAlertMinCases: Number(reasonMinCases),
        trendAlertGapPp: Number(trendGapPp),
        effectiveFrom: effectiveFrom || new Date().toISOString().slice(0, 10),
        sourceNote: basisNote,
        supersedeReason,
        version: supersedeTarget.version,
      });
      setNotice('口径已换版 —— 老版本保留为「已换版」，快照里引用的仍是当时的阈值。');
      setSupersedeTarget(null);
      setSupersedeReason('');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '口径换版失败');
    }
  }

  return (
    <div className="space-y-6 p-6">
      <PageHeader
        title="退货分析"
        description="退货率（店铺/SKU/类目/原因/批次）· 退货金额趋势 · 拒退率 · 处理时效 · 平均退款金额 · 原因分布"
        actions={
          <Button variant="secondary" onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {/* ---------- 筛选 ---------- */}
      <SectionCard
        title="筛选"
        description="退货率必须与销售额同口径比较 —— 分子分母必须落在同一个币种、同一个时间窗里。"
      >
        <div className="flex flex-wrap items-end gap-3">
          <div className="flex flex-col gap-1">
            <label className="text-muted-foreground text-label-12" htmlFor="ra-from">
              起始日期（含）
            </label>
            <Input
              id="ra-from"
              type="date"
              value={from}
              className="h-8 w-40"
              onChange={(e) => setFrom(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-1">
            <label className="text-muted-foreground text-label-12" htmlFor="ra-to">
              结束日期（含当天）
            </label>
            <Input
              id="ra-to"
              type="date"
              value={to}
              className="h-8 w-40"
              onChange={(e) => setTo(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-1">
            <label className="text-muted-foreground text-label-12" htmlFor="ra-currency">
              币种
            </label>
            <select
              id="ra-currency"
              className="h-8 rounded border px-2 text-sm"
              value={currency}
              onChange={(e) => setCurrency(e.target.value)}
            >
              <option value="">全部（不跨币种合计）</option>
              {currencies.map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </select>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-muted-foreground text-label-12">下钻维度</span>
            <Segmented
              aria-label="下钻维度"
              value={dimension}
              onChange={setDimension}
              options={DIMENSION_VALUES.map((d) => ({ value: d, label: DIMENSION_LABELS[d] ?? d }))}
            />
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-muted-foreground text-label-12">趋势粒度</span>
            <Segmented
              aria-label="趋势粒度"
              value={granularity}
              onChange={setGranularity}
              options={GRANULARITY_VALUES.map((g) => ({ value: g, label: GRANULARITY_LABELS[g] ?? g }))}
            />
          </div>
        </div>
      </SectionCard>

      {error && (
        <div className="flex items-center gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm">
          <TriangleAlert className="h-4 w-4" />
          {error}
        </div>
      )}

      {loading && <p className="text-sm text-muted-foreground">加载中…</p>}

      {!loading && !error && overview && (
        <>
          {/* ---------- 总览 ---------- */}
          <div className="grid gap-4 md:grid-cols-4">
            <StatCard
              label="退货售后单"
              value={overview.totals.caseCount}
              unit="单"
              comparisonLabel={`${overview.totals.salesOrderCount} 张已提交订单作分母`}
            />
            <StatCard
              label="拒退率"
              value={formatRatio(overview.rejection.rejectionRate)}
              polarity={rejectionTone(overview.rejection.rejectionRate) === 'danger' ? 'negative' : 'positive'}
              comparisonLabel={`已决定 ${overview.rejection.decidedCount} 单 / 拒绝 ${overview.rejection.rejectedCount} 单`}
            />
            <StatCard
              label="平均结案时长"
              value={formatHours(overview.processing.avgCaseResolutionHours)}
              polarity="neutral"
              comparisonLabel={`样本 ${overview.processing.caseSampleCount} 单`}
            />
            <StatCard
              label="原因归因覆盖率"
              value={
                overview.attributionCoverage === null ? '—' : `${overview.attributionCoverage.toFixed(2)}%`
              }
              polarity="neutral"
              comparisonLabel={`已归因 ${overview.attributedCaseCount} 单`}
            />
          </div>

          {/* ---------- 按币种的退货率（加粗句所在）---------- */}
          <SectionCard title="按币种的退货率">
            <p className="text-sm text-muted-foreground">
              分子（退货金额）与分母（销售额）**必须同币种同窗口**才允许相除 ——
              本页不提供跨币种的合计金额与合计退货率。
            </p>
            {overview.currencies.length === 0 ? (
              <EmptyState
                title="期间内没有退货也没有销售"
                description="换一个期间试试，或先在 11.1.1.1 登记售后单、在 9.1.1 提交订单。"
              />
            ) : (
              <table className="mt-3 w-full text-sm">
                <thead>
                  <tr className="text-left text-muted-foreground">
                    <th className="py-1">币种</th>
                    <th className="py-1">口径</th>
                    <th className="py-1 text-right">退货单</th>
                    <th className="py-1 text-right">退货件数</th>
                    <th className="py-1 text-right">退货金额</th>
                    <th className="py-1 text-right">同期销售额</th>
                    <th className="py-1 text-right">退货率</th>
                    <th className="py-1 text-right">平均退款金额</th>
                    <th className="py-1 text-right">拒退率</th>
                  </tr>
                </thead>
                <tbody>
                  {overview.currencies.map((c) => {
                    const reading = returnRateReading({
                      rate: c.returnRate,
                      returnAmount: c.returnAmount,
                      salesAmount: c.salesAmount,
                      caliber: 'CASE_LEVEL',
                    });
                    return (
                      <tr key={c.currency} className="border-t">
                        <td className="py-2 font-medium">{c.currency}</td>
                        <td className="py-2">
                          <Badge tone={c.caliberSource === 'TENANT_BASIS' ? 'primary' : 'neutral'}>
                            {caliberSourceLabel(c.caliberSource)}
                          </Badge>
                          {c.includeRefundOnly ? (
                            <span className="ml-1 text-xs text-muted-foreground">含仅退款</span>
                          ) : null}
                        </td>
                        <td className="py-2 text-right">{c.caseCount}</td>
                        <td className="py-2 text-right">{formatQty(c.returnQty)}</td>
                        <td className="py-2 text-right">{formatMoney(c.returnAmount)}</td>
                        <td className="py-2 text-right">{formatMoney(c.salesAmount)}</td>
                        <td className="py-2 text-right">
                          <Badge tone={rateTone(c.returnRate) as Tone}>{formatRate(c.returnRate)}</Badge>
                        </td>
                        <td className="py-2 text-right">{formatMoney(c.avgRefundAmount)}</td>
                        <td className="py-2 text-right">
                          <Badge tone={rejectionTone(c.rejectionRate) as Tone}>
                            {formatRatio(c.rejectionRate)}
                          </Badge>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )}
            <p className="mt-2 text-xs text-muted-foreground">{overview.note}</p>
            <p className="mt-1 text-xs text-muted-foreground">
              参考时刻：{overview.generatedAt} · {overview.windowNote}
            </p>
          </SectionCard>

          {/* ---------- 拒退率 ---------- */}
          <SectionCard title="拒退率">
            <p className="text-sm text-muted-foreground">{overview.rejection.note}</p>
            <div className="mt-3 grid gap-3 md:grid-cols-4">
              <StatCard
                label="拒退率"
                value={formatRatio(overview.rejection.rejectionRate)}
                polarity={
                  rejectionTone(overview.rejection.rejectionRate) === 'danger' ? 'negative' : 'positive'
                }
                comparisonLabel={`分母 = 已决定 ${overview.rejection.decidedCount} 单`}
              />
              <StatCard
                label="已授权 / 部分授权 / 拒绝"
                value={`${overview.rejection.authorizedCount} / ${overview.rejection.partiallyAuthorizedCount} / ${overview.rejection.rejectedCount}`}
                unit="单"
                polarity="neutral"
              />
              <StatCard
                label="申请件数"
                value={formatQty(overview.rejection.requestedQty)}
                unit="件"
                polarity="neutral"
              />
              <StatCard
                label="未授权件数占比"
                value={formatRatio(overview.rejection.unapprovedQtyRate)}
                polarity="neutral"
                comparisonLabel={`已授权 ${formatQty(overview.rejection.authorizedQty)} 件`}
              />
            </div>
          </SectionCard>

          {/* ---------- 处理时效 ---------- */}
          <SectionCard title="处理时效">
            <p className="text-sm text-muted-foreground">{overview.processing.note}</p>
            <div className="mt-3 grid gap-3 md:grid-cols-4">
              <StatCard
                label="售后单结案"
                value={formatHours(overview.processing.avgCaseResolutionHours)}
                polarity="neutral"
                comparisonLabel={`最长 ${formatHours(overview.processing.maxCaseResolutionHours)} · 样本 ${overview.processing.caseSampleCount} 单`}
              />
              <StatCard
                label="退货授权审批"
                value={formatHours(overview.processing.avgRmaApprovalHours)}
                polarity="neutral"
                comparisonLabel={`样本 ${overview.processing.rmaSampleCount} 单`}
              />
              <StatCard
                label="买家回寄"
                value={formatHours(overview.processing.avgReceiptHours)}
                polarity="neutral"
                comparisonLabel={`样本 ${overview.processing.receiptSampleCount} 单`}
              />
              <StatCard
                label="退款出结果"
                value={formatHours(overview.processing.avgRefundHours)}
                polarity="neutral"
                comparisonLabel={`样本 ${overview.processing.refundSampleCount} 单`}
              />
            </div>
            <p className="mt-2 text-xs text-muted-foreground">
              四个时长来自四份不同的单据，**互不混算**；没有样本时显示「无样本」而不是 0 小时。
            </p>
          </SectionCard>

          {/* ---------- 维度下钻 ---------- */}
          {breakdown && (
            <SectionCard title={`维度下钻：${DIMENSION_LABELS[breakdown.dimension] ?? breakdown.dimension}`}>
              <p className="text-sm text-muted-foreground">{breakdown.note}</p>
              <p className="mt-1 text-sm">
                口径：<Badge tone="info">{CALIBER_LABELS[breakdown.caliber] ?? breakdown.caliber}</Badge>
                {breakdown.currency ? (
                  <span className="ml-2 text-xs text-muted-foreground">币种 {breakdown.currency}</span>
                ) : null}
              </p>
              {breakdown.rows.length === 0 ? (
                <EmptyState
                  title="该维度下没有数据"
                  description="换一个维度或换一段期间试试 —— 每一维度的分母都是它自己那一维的同期销售额。"
                />
              ) : (
                <table className="mt-3 w-full text-sm">
                  <thead>
                    <tr className="text-left text-muted-foreground">
                      <th className="py-1">维度值</th>
                      <th className="py-1">币种</th>
                      <th className="py-1 text-right">退货单</th>
                      <th className="py-1 text-right">退货件数</th>
                      {noAmountCaliber ? (
                        <th className="py-1 text-right">实收 / 销量件数</th>
                      ) : (
                        <>
                          <th className="py-1 text-right">退货金额</th>
                          <th className="py-1 text-right">同期销售额</th>
                        </>
                      )}
                      <th className="py-1 text-right">退货率</th>
                    </tr>
                  </thead>
                  <tbody>
                    {breakdown.rows.map((row) => (
                      <tr key={`${row.currency}-${row.dimensionKey}`} className="border-t">
                        <td className="py-2">
                          {row.dimensionLabel}
                          {row.dimension === 'BATCH' ? (
                            <span className="ml-1 text-xs text-muted-foreground">
                              关联 {row.linkedSkuCount} 个 SKU
                            </span>
                          ) : null}
                        </td>
                        <td className="py-2">{row.currency}</td>
                        <td className="py-2 text-right">{row.caseCount}</td>
                        <td className="py-2 text-right">{formatQty(row.returnQty)}</td>
                        {noAmountCaliber ? (
                          <td className="py-2 text-right">
                            {formatQty(row.receivedQty)} / {formatQty(row.salesQty)}
                          </td>
                        ) : (
                          <>
                            <td className="py-2 text-right">{formatMoney(row.returnAmount)}</td>
                            <td className="py-2 text-right">{formatMoney(row.salesAmount)}</td>
                          </>
                        )}
                        <td className="py-2 text-right">
                          <Badge tone={rateTone(row.returnRate) as Tone}>{formatRate(row.returnRate)}</Badge>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              <p className="mt-3 text-sm">
                合计：
                {noAmountCaliber ? (
                  <span className="text-muted-foreground">批次维度没有金额口径，不报金额合计。</span>
                ) : crossCurrency ? (
                  <span className="text-amber-600">
                    本期出现 {breakdownCurrencies.join(' / ')} 多个币种 —— **不提供跨币种合计**
                    （合计三件为「—」），请按币种逐行读或选定一个币种后重新查询。
                  </span>
                ) : (
                  <span>
                    退货金额 {formatMoney(breakdown.totalReturnAmount)} · 销售额{' '}
                    {formatMoney(breakdown.totalSalesAmount)} · 退货率{' '}
                    {formatRate(breakdown.totalReturnRate)} · {breakdown.totalCaseCount} 单
                  </span>
                )}
              </p>
              {breakdown.lineAmountCoverage !== null && (
                <p className="mt-1 text-xs text-muted-foreground">
                  行级金额覆盖率 {breakdown.lineAmountCoverage.toFixed(2)}% —— 没有行级金额的售后单
                  不进入 SKU 与类目下钻。
                </p>
              )}
            </SectionCard>
          )}

          {/* ---------- 趋势 ---------- */}
          {trend && (
            <SectionCard title="退货金额与退货率趋势">
              <p className="text-sm text-muted-foreground">{trend.note}</p>
              {trend.points.length === 0 ? (
                <EmptyState title="期间内没有可分桶的数据" description="换一个期间或粒度试试。" />
              ) : (
                <table className="mt-3 w-full text-sm">
                  <thead>
                    <tr className="text-left text-muted-foreground">
                      <th className="py-1">时间桶</th>
                      <th className="py-1">币种</th>
                      <th className="py-1 text-right">退货单</th>
                      <th className="py-1 text-right">退货件数</th>
                      <th className="py-1 text-right">退货金额</th>
                      <th className="py-1 text-right">同期销售额</th>
                      <th className="py-1 text-right">退货率</th>
                    </tr>
                  </thead>
                  <tbody>
                    {trend.points.map((p) => (
                      <tr key={`${p.currency}-${p.bucketStart}`} className="border-t">
                        <td className="py-2">{p.bucket}</td>
                        <td className="py-2">{p.currency}</td>
                        <td className="py-2 text-right">{p.caseCount}</td>
                        <td className="py-2 text-right">{formatQty(p.returnQty)}</td>
                        <td className="py-2 text-right">{formatMoney(p.returnAmount)}</td>
                        <td className="py-2 text-right">{formatMoney(p.salesAmount)}</td>
                        <td className="py-2 text-right">
                          <Badge tone={rateTone(p.returnRate) as Tone}>{formatRate(p.returnRate)}</Badge>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </SectionCard>
          )}

          {/* ---------- 异常识别 ---------- */}
          {anomalies && (
            <SectionCard title="异常识别">
              <p className="text-sm text-muted-foreground">{anomalies.note}</p>
              {anomalies.thresholds ? (
                <p className="mt-1 text-xs text-muted-foreground">
                  阈值（{caliberSourceLabel(anomalies.thresholds.source)}）：退货率 ×
                  {anomalies.thresholds.rateMultiplier}（且该维度销售额 ≥ 总体的{' '}
                  {(anomalies.thresholds.rateMinSalesShare * 100).toFixed(2)}%）· 拒退率差{' '}
                  {anomalies.thresholds.rejectionGapPp} 个百分点（且已决定 ≥{' '}
                  {anomalies.thresholds.rejectionMinCases} 单）· 批次件数 ×
                  {anomalies.thresholds.batchMultiplier}（且 ≥ {anomalies.thresholds.batchMinQty} 件）·
                  原因占比 {(anomalies.thresholds.reasonShare * 100).toFixed(2)}%（且 ≥{' '}
                  {anomalies.thresholds.reasonMinCases} 单）· 趋势恶化{' '}
                  {anomalies.thresholds.trendGapPp} 个百分点
                </p>
              ) : null}
              {anomalies.rows.length === 0 ? (
                <EmptyState
                  title="没有命中异常规则"
                  description="没命中不等于健康 —— 先看下面的样本量够不够（样本太小的维度不参与判定）。"
                />
              ) : (
                <table className="mt-3 w-full text-sm">
                  <thead>
                    <tr className="text-left text-muted-foreground">
                      <th className="py-1">规则</th>
                      <th className="py-1">维度值</th>
                      <th className="py-1">币种</th>
                      <th className="py-1">严重度</th>
                      <th className="py-1 text-right">实测</th>
                      <th className="py-1 text-right">基准</th>
                      <th className="py-1 text-right">差值</th>
                      <th className="py-1">说明</th>
                    </tr>
                  </thead>
                  <tbody>
                    {anomalies.rows.map((row, i) => (
                      <tr key={`${row.code}-${row.currency}-${row.dimensionKey}-${i}`} className="border-t">
                        <td className="py-2">{ANOMALY_LABELS[row.code] ?? row.title}</td>
                        <td className="py-2">{row.dimensionLabel}</td>
                        <td className="py-2">{row.currency}</td>
                        <td className="py-2">
                          <Badge tone={severityTone(row.severity) as Tone}>{row.severity}</Badge>
                        </td>
                        <td className="py-2 text-right">{row.metricValue}</td>
                        <td className="py-2 text-right">{row.baselineValue}</td>
                        <td className="py-2 text-right">{row.delta}</td>
                        <td className="py-2 text-xs text-muted-foreground">{row.note}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </SectionCard>
          )}

          {/* ---------- 原因分布 ---------- */}
          <SectionCard title="原因分布">
            <p className="text-sm text-muted-foreground">
              下面两张表是**两个不同的轴**：左边是售后单上的七类内联词表（每单必填，口径完整），
              右边是 11.1.2.1 的归因分类树（可能没归因，**必须和覆盖率一起读**）。
            </p>
            <div className="mt-3 grid gap-4 lg:grid-cols-2">
              <div>
                <h4 className="mb-1 text-sm font-medium">七类原因词表</h4>
                {overview.reasonDistribution.length === 0 ? (
                  <p className="text-sm text-muted-foreground">期间内没有退货售后单。</p>
                ) : (
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="text-left text-muted-foreground">
                        <th className="py-1">原因</th>
                        <th className="py-1">币种</th>
                        <th className="py-1 text-right">单数</th>
                        <th className="py-1 text-right">件数</th>
                        <th className="py-1 text-right">金额</th>
                        <th className="py-1 text-right">占比</th>
                      </tr>
                    </thead>
                    <tbody>
                      {overview.reasonDistribution.map((r) => (
                        <tr key={`${r.currency}-${r.reasonCode}`} className="border-t">
                          <td className="py-2">{r.label}</td>
                          <td className="py-2">{r.currency}</td>
                          <td className="py-2 text-right">{r.caseCount}</td>
                          <td className="py-2 text-right">{formatQty(r.returnQty)}</td>
                          <td className="py-2 text-right">{formatMoney(r.returnAmount)}</td>
                          <td className="py-2 text-right">{formatRate(r.share)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
              <div>
                <h4 className="mb-1 text-sm font-medium">
                  归因分类树（覆盖率{' '}
                  {overview.attributionCoverage === null
                    ? '—'
                    : `${overview.attributionCoverage.toFixed(2)}%`}
                  ，已归因 {overview.attributedCaseCount} 单）
                </h4>
                {overview.attributionDistribution.length === 0 ? (
                  <p className="text-sm text-muted-foreground">
                    期间内没有归因记录 —— 这一列空着不代表没有退货，只代表还没归因。
                  </p>
                ) : (
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="text-left text-muted-foreground">
                        <th className="py-1">分类</th>
                        <th className="py-1">币种</th>
                        <th className="py-1 text-right">命中单数</th>
                        <th className="py-1 text-right">其中主因</th>
                        <th className="py-1 text-right">占比</th>
                      </tr>
                    </thead>
                    <tbody>
                      {overview.attributionDistribution.map((r) => (
                        <tr key={`${r.currency}-${r.categoryCode}`} className="border-t">
                          <td className="py-2">{r.categoryName}</td>
                          <td className="py-2">{r.currency}</td>
                          <td className="py-2 text-right">{r.caseCount}</td>
                          <td className="py-2 text-right">{r.primaryCaseCount}</td>
                          <td className="py-2 text-right">{formatRate(r.share)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
                <p className="mt-1 text-xs text-muted-foreground">
                  一单可以归多个原因（多选），所以各分类的命中单数之和**大于**归因单数 —— 这是多选归因的定义。
                </p>
              </div>
            </div>
          </SectionCard>

          {/* ---------- 快照 ---------- */}
          {snapshots && (
            <SectionCard title="报表快照">
              <p className="text-sm text-muted-foreground">
                快照是**追加式**的：算出来的每个维度值原样落库，三个月后「上个月那张报表是多少」才答得出来。
                算错了重存一份即可，老的那份留着。
              </p>
              <div className="mt-3 flex flex-wrap items-end gap-3">
                <Button variant="secondary" onClick={() => void saveSnapshot()}>
                  按当前筛选保存快照
                </Button>
                <span className="text-xs text-muted-foreground">
                  当前：{dimension} · {currency || '（未选币种）'}
                  {from ? ` · ${from}` : ''}
                  {to ? ` → ${to}` : ''}
                </span>
              </div>
              {actionError && <p className="mt-2 text-sm text-red-600">{actionError}</p>}
              {notice && <p className="mt-2 text-sm text-emerald-600">{notice}</p>}
              {snapshots.rows.length === 0 ? (
                <EmptyState title="还没有保存过快照" description="保存后这份报表的数就固定下来了。" />
              ) : (
                <table className="mt-3 w-full text-sm">
                  <thead>
                    <tr className="text-left text-muted-foreground">
                      <th className="py-1">单号</th>
                      <th className="py-1">维度</th>
                      <th className="py-1">币种</th>
                      <th className="py-1 text-right">退货单</th>
                      <th className="py-1 text-right">退货金额</th>
                      <th className="py-1 text-right">销售额</th>
                      <th className="py-1 text-right">退货率</th>
                      <th className="py-1 text-right">拒退率</th>
                      <th className="py-1">计算时刻</th>
                    </tr>
                  </thead>
                  <tbody>
                    {snapshots.rows.map((row) => (
                      <tr key={row.id} className="border-t">
                        <td className="py-2">
                          {row.snapshotNo}
                          <span className="ml-1 text-xs text-muted-foreground">
                            {snapshotDimensionLabel(row.dimension)}
                          </span>
                        </td>
                        <td className="py-2">{row.dimensionLabel}</td>
                        <td className="py-2">{row.currency}</td>
                        <td className="py-2 text-right">{row.caseCount}</td>
                        <td className="py-2 text-right">{formatMoney(row.returnAmount)}</td>
                        <td className="py-2 text-right">{formatMoney(row.salesAmount)}</td>
                        <td className="py-2 text-right">{formatRate(row.returnRate)}</td>
                        <td className="py-2 text-right">{formatRatio(row.rejectionRate)}</td>
                        <td className="py-2 text-xs text-muted-foreground">{row.computedAt}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </SectionCard>
          )}

          {/* ---------- 口径与阈值 ---------- */}
          {basis && (
            <SectionCard title="口径与异常阈值">
              <p className="text-sm text-muted-foreground">
                每个币种一份生效口径；换版必须留痕（老版本保留为「已换版」）。
                没有登记时用文档化的默认口径，报表里会标 caliberSource=DEFAULT。
              </p>
              {basis.rows.length === 0 ? (
                <p className="mt-2 text-sm text-muted-foreground">
                  这个租户还没有登记任何口径 —— 现在的异常判定用的是默认口径。
                </p>
              ) : (
                <table className="mt-3 w-full text-sm">
                  <thead>
                    <tr className="text-left text-muted-foreground">
                      <th className="py-1">口径号</th>
                      <th className="py-1">名称</th>
                      <th className="py-1">币种</th>
                      <th className="py-1">状态</th>
                      <th className="py-1 text-right">退货率倍数</th>
                      <th className="py-1 text-right">拒退率差(pp)</th>
                      <th className="py-1 text-right">批次倍数</th>
                      <th className="py-1 text-right">原因占比</th>
                      <th className="py-1">生效日</th>
                      <th className="py-1" />
                    </tr>
                  </thead>
                  <tbody>
                    {basis.rows.map((row) => (
                      <tr key={row.id} className="border-t">
                        <td className="py-2">{row.basisNo}</td>
                        <td className="py-2">{row.basisName}</td>
                        <td className="py-2">{row.currency}</td>
                        <td className="py-2">
                          <Badge tone={row.basisStatus === 'ACTIVE' ? 'success' : 'neutral'}>
                            {BASIS_STATUS_LABELS[row.basisStatus] ?? row.basisStatus}
                          </Badge>
                        </td>
                        <td className="py-2 text-right">{row.rateAlertMultiplier}</td>
                        <td className="py-2 text-right">{row.rejectionAlertGapPp}</td>
                        <td className="py-2 text-right">{row.batchAlertMultiplier}</td>
                        <td className="py-2 text-right">{row.reasonAlertShare}</td>
                        <td className="py-2">{row.effectiveFrom}</td>
                        <td className="py-2">
                          {row.basisStatus === 'ACTIVE' ? (
                            <Button
                              variant="ghost"
                              onClick={() => {
                                setSupersedeTarget(row);
                                setRateMultiplier(String(row.rateAlertMultiplier));
                                setMinSalesShare(String(row.rateAlertMinSalesShare));
                                setRejectionGapPp(String(row.rejectionAlertGapPp));
                                setRejectionMinCases(String(row.rejectionAlertMinCases));
                                setBatchMultiplier(String(row.batchAlertMultiplier));
                                setBatchMinQty(String(row.batchAlertMinQty));
                                setReasonShare(String(row.reasonAlertShare));
                                setReasonMinCases(String(row.reasonAlertMinCases));
                                setTrendGapPp(String(row.trendAlertGapPp));
                                setIncludeRefundOnly(row.includeRefundOnly);
                                setEffectiveFrom(row.effectiveFrom);
                              }}
                            >
                              换版
                            </Button>
                          ) : null}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}

              <div className="mt-4 border-t pt-4">
                <h4 className="mb-2 text-sm font-medium">
                  {supersedeTarget ? `换版 ${supersedeTarget.basisNo}（${supersedeTarget.currency}）` : '登记新口径'}
                </h4>
                <div className="flex flex-wrap items-end gap-3">
                  <div className="flex flex-col gap-1">
                    <label className="text-muted-foreground text-label-12" htmlFor="ra-basis-name">
                      口径名
                    </label>
                    <Input
                      id="ra-basis-name"
                      className="h-8 w-44"
                      value={basisName}
                      placeholder={supersedeTarget?.basisName ?? '例如：2026 春季口径'}
                      onChange={(e) => setBasisName(e.target.value)}
                    />
                  </div>
                  <div className="flex flex-col gap-1">
                    <label className="text-muted-foreground text-label-12" htmlFor="ra-basis-currency">
                      币种
                    </label>
                    <Input
                      id="ra-basis-currency"
                      className="h-8 w-24"
                      value={basisCurrency}
                      placeholder={supersedeTarget?.currency ?? 'CNY'}
                      disabled={supersedeTarget !== null}
                      onChange={(e) => setBasisCurrency(e.target.value)}
                    />
                  </div>
                  <div className="flex flex-col gap-1">
                    <label className="text-muted-foreground text-label-12" htmlFor="ra-basis-from">
                      生效日
                    </label>
                    <Input
                      id="ra-basis-from"
                      type="date"
                      className="h-8 w-40"
                      value={effectiveFrom}
                      onChange={(e) => setEffectiveFrom(e.target.value)}
                    />
                  </div>
                  <label className="flex items-center gap-2 text-sm">
                    <input
                      type="checkbox"
                      checked={includeRefundOnly}
                      onChange={(e) => setIncludeRefundOnly(e.target.checked)}
                    />
                    分子含「仅退款」类售后单
                  </label>
                </div>

                <div className="mt-3 grid gap-3 sm:grid-cols-3 lg:grid-cols-5">
                  <div className="flex flex-col gap-1">
                    <label className="text-muted-foreground text-label-12" htmlFor="ra-t-rate">
                      退货率异常倍数
                    </label>
                    <Input
                      id="ra-t-rate"
                      className="h-8 w-28"
                      value={rateMultiplier}
                      onChange={(e) => setRateMultiplier(e.target.value)}
                    />
                  </div>
                  <div className="flex flex-col gap-1">
                    <label className="text-muted-foreground text-label-12" htmlFor="ra-t-share">
                      最小销售额占比
                    </label>
                    <Input
                      id="ra-t-share"
                      className="h-8 w-28"
                      value={minSalesShare}
                      onChange={(e) => setMinSalesShare(e.target.value)}
                    />
                  </div>
                  <div className="flex flex-col gap-1">
                    <label className="text-muted-foreground text-label-12" htmlFor="ra-t-rej">
                      拒退率差(pp)
                    </label>
                    <Input
                      id="ra-t-rej"
                      className="h-8 w-28"
                      value={rejectionGapPp}
                      onChange={(e) => setRejectionGapPp(e.target.value)}
                    />
                  </div>
                  <div className="flex flex-col gap-1">
                    <label className="text-muted-foreground text-label-12" htmlFor="ra-t-rejmin">
                      拒退率最小已决定单数
                    </label>
                    <Input
                      id="ra-t-rejmin"
                      className="h-8 w-28"
                      value={rejectionMinCases}
                      onChange={(e) => setRejectionMinCases(e.target.value)}
                    />
                  </div>
                  <div className="flex flex-col gap-1">
                    <label className="text-muted-foreground text-label-12" htmlFor="ra-t-batch">
                      批次件数异常倍数
                    </label>
                    <Input
                      id="ra-t-batch"
                      className="h-8 w-28"
                      value={batchMultiplier}
                      onChange={(e) => setBatchMultiplier(e.target.value)}
                    />
                  </div>
                  <div className="flex flex-col gap-1">
                    <label className="text-muted-foreground text-label-12" htmlFor="ra-t-batchmin">
                      批次最小件数
                    </label>
                    <Input
                      id="ra-t-batchmin"
                      className="h-8 w-28"
                      value={batchMinQty}
                      onChange={(e) => setBatchMinQty(e.target.value)}
                    />
                  </div>
                  <div className="flex flex-col gap-1">
                    <label className="text-muted-foreground text-label-12" htmlFor="ra-t-reason">
                      原因集中最小占比
                    </label>
                    <Input
                      id="ra-t-reason"
                      className="h-8 w-28"
                      value={reasonShare}
                      onChange={(e) => setReasonShare(e.target.value)}
                    />
                  </div>
                  <div className="flex flex-col gap-1">
                    <label className="text-muted-foreground text-label-12" htmlFor="ra-t-reasonmin">
                      原因集中最小单数
                    </label>
                    <Input
                      id="ra-t-reasonmin"
                      className="h-8 w-28"
                      value={reasonMinCases}
                      onChange={(e) => setReasonMinCases(e.target.value)}
                    />
                  </div>
                  <div className="flex flex-col gap-1">
                    <label className="text-muted-foreground text-label-12" htmlFor="ra-t-trend">
                      趋势恶化(pp)
                    </label>
                    <Input
                      id="ra-t-trend"
                      className="h-8 w-28"
                      value={trendGapPp}
                      onChange={(e) => setTrendGapPp(e.target.value)}
                    />
                  </div>
                  <div className="flex flex-col gap-1">
                    <label className="text-muted-foreground text-label-12" htmlFor="ra-basis-note">
                      口径说明
                    </label>
                    <Input
                      id="ra-basis-note"
                      className="h-8 w-56"
                      value={basisNote}
                      placeholder="这些阈值是怎么定的"
                      onChange={(e) => setBasisNote(e.target.value)}
                    />
                  </div>
                </div>

                {supersedeTarget ? (
                  <div className="mt-3 flex flex-wrap items-end gap-3">
                    <div className="flex flex-col gap-1">
                      <label className="text-muted-foreground text-label-12" htmlFor="ra-supersede-reason">
                        换版理由（必填）
                      </label>
                      <Input
                        id="ra-supersede-reason"
                        className="h-8 w-72"
                        value={supersedeReason}
                        placeholder="例如：Q2 起把最小销售额占比从 1% 提到 3%"
                        onChange={(e) => setSupersedeReason(e.target.value)}
                      />
                    </div>
                    <Button onClick={() => void submitSupersede()}>提交换版</Button>
                    <Button variant="ghost" onClick={() => setSupersedeTarget(null)}>
                      取消
                    </Button>
                  </div>
                ) : (
                  <div className="mt-3">
                    <Button onClick={() => void submitBasis()}>登记口径</Button>
                  </div>
                )}
                {actionError && <p className="mt-2 text-sm text-red-600">{actionError}</p>}
                {notice && <p className="mt-2 text-sm text-emerald-600">{notice}</p>}
              </div>
            </SectionCard>
          )}

          {/* ---------- 边界声明 ---------- */}
          {caps && (
            <SectionCard title="本模块的边界">
              <ul className="list-disc space-y-1 pl-5 text-sm">
                {caps.boundaries.map((b) => (
                  <li key={b}>{b.replaceAll('**', '')}</li>
                ))}
              </ul>
              <p className="mt-3 text-xs text-muted-foreground">{caps.caliberNote.replaceAll('**', '')}</p>
              <p className="mt-1 text-xs text-muted-foreground">{caps.currencyNote.replaceAll('**', '')}</p>
            </SectionCard>
          )}
        </>
      )}
    </div>
  );
}
