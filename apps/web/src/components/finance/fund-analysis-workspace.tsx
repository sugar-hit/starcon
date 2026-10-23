'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  FinanceFundAnalysisCapabilities,
  FinanceFundAnalysisOptions,
  FinanceFundCurrencyDistribution,
  FinanceFundExposureReport,
  FinanceFundExposureSnapshotPage,
  FinanceFundFlowTrend,
  FinanceFundLiquidityAlertReport,
  FinanceFundLiquidityThresholdConfig,
  FinanceFundLiquidityThresholdInput,
  FinanceFundPaymentConcentrationReport,
  FinanceFundTurnoverReport,
} from '../../../../../packages/api-contracts/src/index';
import {
  fetchFundAnalysisCapabilities,
  fetchFundAnalysisOptions,
  fetchFundCurrencyDistribution,
  fetchFundExposure,
  fetchFundExposureSnapshots,
  fetchFundFlowTrend,
  fetchFundLiquidityAlerts,
  fetchFundLiquidityThresholds,
  fetchFundPaymentConcentration,
  fetchFundTurnoverDays,
  formatFundAmount,
  formatFundDays,
  formatFundExposureShare,
  formatFundMoney,
  formatFundShare,
  fundExposureDirectionLabel,
  fundGranularityLabel,
  fundSeverityLabel,
  fundThresholdSourceLabel,
  measureFundExposure,
  updateFundLiquidityThresholds,
} from './fund-analysis-model';

/**
 * 12.7.2.1 资金分析。
 *
 * <p>蓝图 12-finance.md：「资金分析：现金流入流出趋势、各币种资金分布与汇率敞口、资金周转天数、付款集中度与流动性预警。」输入：「资金流水；应收应付账期；汇率数据。」输出：「资金报表 + 汇率敞口测算 + 流动性预警。」
 *
 * <p><b>页面围绕六项能力组织</b>，每项一个区块：
 *
 * <ol>
 *   <li><b>现金流入流出趋势</b>：三列口径**各有各的标题**（实际 / 预期 / 计划），
 *       让人一眼看出「计划流出」不是「已付」。</li>
 *   <li><b>各币种资金分布</b>：按币种分行，账户级余额不可用这件事写在最上面。</li>
 *   <li><b>汇率敞口测算</b>：敞口方向、折算汇率、±1% / ±5% 敏感度、敞口集中度，
 *       另有「测算并留档」按钮（把这次用的汇率钉住，便于复盘）。</li>
 *   <li><b>资金周转天数</b>：DSO / DPO / 资金周转天数，存货段显示「—」并写明归属。</li>
 *   <li><b>付款集中度</b>：时间桶 + 付款对象两个维度。</li>
 *   <li><b>流动性预警</b>：预警条目 + 阈值配置（整体替换）。</li>
 * </ol>
 *
 * <p><b>页面不做业务算术</b>：占比、敏感度、集中度、预警观测值都由服务端算好，
 * 页面只原样展示（只做单位换算与显示格式化）。
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有资金流水」。
 */

const GRANULARITIES = ['DAY', 'WEEK', 'MONTH'] as const;

/** ISO 日期（UTC 日历日）。 */
function isoDate(date: Date): string {
  return date.toISOString().slice(0, 10);
}

/** 今天往前 n 天的 UTC 日历日。 */
function daysAgoUtc(days: number): string {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() - days);
  return isoDate(d);
}

/** 今天往后 n 天的 UTC 日历日。 */
function daysAheadUtc(days: number): string {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + days);
  return isoDate(d);
}

export function FundAnalysisWorkspace() {
  const [caps, setCaps] = useState<FinanceFundAnalysisCapabilities | null>(null);
  const [options, setOptions] = useState<FinanceFundAnalysisOptions | null>(null);
  const [trend, setTrend] = useState<FinanceFundFlowTrend | null>(null);
  const [distribution, setDistribution] = useState<FinanceFundCurrencyDistribution | null>(null);
  const [exposure, setExposure] = useState<FinanceFundExposureReport | null>(null);
  const [snapshots, setSnapshots] = useState<FinanceFundExposureSnapshotPage | null>(null);
  const [turnover, setTurnover] = useState<FinanceFundTurnoverReport | null>(null);
  const [concentration, setConcentration] = useState<FinanceFundPaymentConcentrationReport | null>(
    null,
  );
  const [alerts, setAlerts] = useState<FinanceFundLiquidityAlertReport | null>(null);
  const [thresholds, setThresholds] = useState<FinanceFundLiquidityThresholdConfig | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 筛选
  const [currency, setCurrency] = useState('');
  const [baseCurrency, setBaseCurrency] = useState('');
  const [from, setFrom] = useState(daysAgoUtc(29));
  const [to, setTo] = useState(daysAheadUtc(29));
  const [granularity, setGranularity] = useState<(typeof GRANULARITIES)[number]>('DAY');

  // 阈值草稿（原始字符串，避免输入中途就被当成数字）
  const [thresholdDrafts, setThresholdDrafts] = useState<
    { code: string; label: string; value: string; enabled: boolean }[]
  >([]);

  /**
   * 筛选参数。
   *
   * ⚠️ `exactOptionalPropertyTypes` 开着（AGENTS.md）：**没填的筛选项不能显式给 undefined** ——
   *   所以这里按「填了才建键」处理，而不是一律塞 undefined。
   */
  const params = useMemo(
    () => ({
      ...(currency ? { currency } : {}),
      ...(baseCurrency ? { baseCurrency } : {}),
      from,
      to,
      granularity,
    }),
    [currency, baseCurrency, from, to, granularity],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [c, o, t, d, e, s, tu, pc, al, th] = await Promise.all([
        fetchFundAnalysisCapabilities(),
        fetchFundAnalysisOptions(),
        fetchFundFlowTrend(params),
        fetchFundCurrencyDistribution(currency || undefined),
        fetchFundExposure({
          ...(baseCurrency ? { baseCurrency } : {}),
          ...(currency ? { currency } : {}),
        }).catch(() => null),
        fetchFundExposureSnapshots(0, 10),
        fetchFundTurnoverDays({
          ...(currency ? { currency } : {}),
          from,
          to,
        }),
        fetchFundPaymentConcentration(params),
        fetchFundLiquidityAlerts({ from, to }),
        fetchFundLiquidityThresholds(),
      ]);
      setCaps(c);
      setOptions(o);
      setTrend(t);
      setDistribution(d);
      setExposure(e);
      setSnapshots(s);
      setTurnover(tu);
      setConcentration(pc);
      setAlerts(al);
      setThresholds(th);
    } catch (err) {
      setError(err instanceof Error ? err.message : '资金分析加载失败');
    } finally {
      setLoading(false);
    }
  }, [params, currency, baseCurrency, from, to]);

  useEffect(() => {
    void load();
  }, [load]);

  // 阈值草稿跟着服务端下发的配置走（配置改了 → 草稿重置）。
  useEffect(() => {
    setThresholdDrafts(
      (thresholds?.thresholds ?? []).map((t) => ({
        code: t.code,
        label: t.label,
        value: String(t.thresholdValue),
        enabled: t.enabled,
      })),
    );
  }, [thresholds]);

  async function runMeasure() {
    setActionError('');
    setNotice('');
    try {
      const result = await measureFundExposure({
        ...(baseCurrency ? { baseCurrency } : {}),
        ...(currency ? { currency } : {}),
      });
      setExposure(result.report);
      setNotice(
        `敞口已测算并留档（${result.snapshots.length} 条快照；汇率与生效日一并记下，便于复盘）`,
      );
      const page = await fetchFundExposureSnapshots(0, 10);
      setSnapshots(page);
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '敞口测算失败');
    }
  }

  async function saveThresholds() {
    setActionError('');
    setNotice('');
    try {
      const payload: FinanceFundLiquidityThresholdInput[] = thresholdDrafts.map((d) => ({
        code: d.code,
        label: d.label,
        thresholdValue: Number(d.value),
        enabled: d.enabled,
        note: '',
      }));
      const saved = await updateFundLiquidityThresholds(payload);
      setThresholds(saved);
      setNotice(`流动性预警阈值已更新（当前生效：${fundThresholdSourceLabel(saved.source)}）`);
      await load();
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '流动性预警阈值配置失败');
    }
  }

  async function resetThresholds() {
    setActionError('');
    setNotice('');
    try {
      const saved = await updateFundLiquidityThresholds([]);
      setThresholds(saved);
      setNotice('已回到蓝图默认档流动性预警阈值');
      await load();
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '流动性预警阈值配置失败');
    }
  }

  const unavailable = caps?.capabilities.filter((c) => !c.available) ?? [];
  const downstreamGaps = caps?.downstreamGaps.filter((g) => !g.available) ?? [];
  const alertCount = alerts?.alertCount ?? 0;
  const criticalCount = alerts?.alerts.filter((a) => a.severity === 'CRITICAL').length ?? 0;

  return (
    <div className="space-y-6 p-6">
      <PageHeader
        title="资金分析"
        description="现金流入流出趋势、各币种资金分布与汇率敞口、资金周转天数、付款集中度与流动性预警"
        actions={
          <div className="flex gap-2">
            <Button variant="secondary" onClick={() => void runMeasure()} disabled={loading}>
              测算并留档敞口
            </Button>
            <Button variant="secondary" onClick={() => void load()} disabled={loading}>
              <RefreshCw className="mr-2 h-4 w-4" />
              刷新
            </Button>
          </div>
        }
      />

      {error && (
        <div className="flex items-center gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm">
          <TriangleAlert className="h-4 w-4" />
          {error}
        </div>
      )}
      {actionError && (
        <Notice tone="danger" title="操作未完成">
          {actionError}
        </Notice>
      )}
      {notice && (
        <Notice tone="info" title="已完成">
          {notice}
        </Notice>
      )}

      {loading && <p className="text-muted-foreground text-sm">加载中…</p>}

      {!loading && !error && (
        <>
          {/* ---------- 能力缺口（未交付的模块说清楚归谁） ---------- */}
          {unavailable.length > 0 && (
            <Notice tone="warning" title="当前不可用的口径（上游未交付）">
              {unavailable
                .map((c) => `${c.module} ${c.name}（归属 ${c.ownerModule || '—'}）：${c.detail}`)
                .join('；')}
            </Notice>
          )}
          {downstreamGaps.length > 0 && (
            <Notice tone="info" title="未交付的下游">
              {downstreamGaps.map((g) => `${g.module} ${g.name}（${g.detail}）`).join('；')}
            </Notice>
          )}

          {/* ---------- 概览 ---------- */}
          <div className="grid gap-4 md:grid-cols-4">
            <StatCard label="涉及币种" value={options?.currencies.length ?? 0} unit="种" />
            <StatCard label="流动性预警" value={alertCount} unit="条" />
            <StatCard label="其中严重" value={criticalCount} unit="条" />
            <StatCard
              label="敞口本位币"
              value={exposure?.baseCurrency ?? '—'}
              comparisonLabel={exposure ? fundThresholdSourceLabel('TENANT_CONFIG') : '待测算'}
            />
          </div>

          {/* ---------- 筛选 ---------- */}
          <SectionCard
            title="筛选"
            description="六个区块共用同一套条件 —— 换任何一项，趋势 / 敞口 / 周转 / 集中度 / 预警一起变。"
          >
            <div className="grid gap-3 md:grid-cols-5">
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={currency}
                onChange={(e) => setCurrency(e.target.value)}
                aria-label="币种"
              >
                <option value="">全部币种</option>
                {(options?.currencies ?? []).map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={baseCurrency}
                onChange={(e) => setBaseCurrency(e.target.value)}
                aria-label="本位币"
              >
                <option value="">本位币（自动）</option>
                {(options?.baseCurrencyCandidates ?? []).map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
              <Input
                type="date"
                value={from}
                aria-label="起始日期"
                onChange={(e) => setFrom(e.target.value)}
              />
              <Input
                type="date"
                value={to}
                aria-label="结束日期"
                onChange={(e) => setTo(e.target.value)}
              />
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={granularity}
                onChange={(e) => setGranularity(e.target.value as (typeof GRANULARITIES)[number])}
                aria-label="时间粒度"
              >
                {GRANULARITIES.map((g) => (
                  <option key={g} value={g}>
                    {fundGranularityLabel(g)}
                  </option>
                ))}
              </select>
            </div>
            <p className="text-muted-foreground text-copy-13 mt-2">
              区间按 <b>UTC 日历日</b>算（服务端口径）：换一条连接不会换一套分桶。
            </p>
          </SectionCard>

          {/* ---------- ① 现金流入流出趋势 ---------- */}
          <SectionCard
            title="现金流入流出趋势"
            description="三个口径各有各的来源：实际流入 = 已到账收款；预期流入 = 未核销应收；计划流出 = 付款计划（不是已付）。"
          >
            {!trend || trend.buckets.length === 0 ? (
              <EmptyState
                title="这段时间没有资金流水"
                description="趋势线来自真实的收款、应收与付款计划。换一个区间或币种试试。"
              />
            ) : (
              <div className="space-y-3">
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">期间（UTC 日历日）</th>
                      <th className="py-2">币种</th>
                      <th className="py-2">实际流入</th>
                      <th className="py-2">预期流入</th>
                      <th className="py-2">计划流出</th>
                      <th className="py-2">预测净额</th>
                    </tr>
                  </thead>
                  <tbody>
                    {trend.buckets.map((b) => (
                      <tr key={`${b.periodStart}-${b.currency}`} className="border-border border-t">
                        <td className="py-2 font-medium">{b.periodStart}</td>
                        <td className="py-2">{b.currency}</td>
                        <td className="py-2">
                          {formatFundMoney(b.actualInflowAmount, b.currency)}
                        </td>
                        <td className="py-2">
                          {formatFundMoney(b.expectedInflowAmount, b.currency)}
                        </td>
                        <td className="py-2">
                          {formatFundMoney(b.plannedOutflowAmount, b.currency)}
                        </td>
                        <td className="py-2">{formatFundMoney(b.netExpectedAmount, b.currency)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <Notice tone="warning" title="实际流出为什么没有时间序列">
                  {trend.actualOutflowBasis}（归属模块 {trend.actualOutflowOwnerModule}）
                </Notice>
              </div>
            )}
          </SectionCard>

          {/* ---------- ② 各币种资金分布 ---------- */}
          <SectionCard
            title="各币种资金分布"
            description="按币种分组的真实聚合 —— 这七个数是七种不同口径的度量，不是资产负债表。"
          >
            {!distribution || distribution.positions.length === 0 ? (
              <EmptyState
                title="还没有资金流水"
                description="登记收款单、确认应收应付之后，这里才会有数。"
              />
            ) : (
              <div className="space-y-3">
                <Notice tone="warning" title="账户级余额不可用">
                  {distribution.cashBasisNote}（归属模块 {distribution.accountOwnerModule}）
                </Notice>
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">币种</th>
                      <th className="py-2">累计已到账</th>
                      <th className="py-2">其中未核销</th>
                      <th className="py-2">其中预收</th>
                      <th className="py-2">未核销应收</th>
                      <th className="py-2">未付应付</th>
                      <th className="py-2">累计已付</th>
                      <th className="py-2">计划流出</th>
                      <th className="py-2">未来结算净额</th>
                    </tr>
                  </thead>
                  <tbody>
                    {distribution.positions.map((p) => (
                      <tr key={p.currency} className="border-border border-t">
                        <td className="py-2 font-medium">{p.currency}</td>
                        <td className="py-2">
                          {formatFundMoney(p.receivedCashAmount, p.currency)}
                        </td>
                        <td className="py-2">
                          {formatFundMoney(p.unsettledReceivedAmount, p.currency)}
                        </td>
                        <td className="py-2">{formatFundMoney(p.advanceAmount, p.currency)}</td>
                        <td className="py-2">
                          {formatFundMoney(p.receivableOpenAmount, p.currency)}
                        </td>
                        <td className="py-2">
                          {formatFundMoney(p.payableUnpaidAmount, p.currency)}
                        </td>
                        <td className="py-2">{formatFundMoney(p.paidOutAmount, p.currency)}</td>
                        <td className="py-2">
                          {formatFundMoney(p.plannedOutflowAmount, p.currency)}
                        </td>
                        <td className="py-2">
                          {formatFundMoney(p.netSettlementAmount, p.currency)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <ul className="text-muted-foreground text-copy-13 list-disc pl-5">
                  {distribution.notes.map((note) => (
                    <li key={note}>{note}</li>
                  ))}
                </ul>
              </div>
            )}
          </SectionCard>

          {/* ---------- ③ 汇率敞口 ---------- */}
          <SectionCard
            title="汇率敞口测算"
            description="净敞口 = 未核销应收 − 未付应付，按本位币折算，并给出 ±1% / ±5% 的敏感度。"
          >
            {!exposure ? (
              <EmptyState
                title="敞口还没算出来"
                description={
                  baseCurrency
                    ? '可能是汇率取不到（会明确报错），或者还没有任何余额。'
                    : '请指定本位币，或先在应收 / 应付单上登记汇率快照 —— 没有本位币就折算不了。'
                }
              />
            ) : (
              <div className="space-y-3">
                <p className="text-muted-foreground text-copy-13">
                  基准日 {exposure.asOfDate} · 本位币 {exposure.baseCurrency}（
                  {exposure.baseCurrencyBasis}） · 汇率来源 {exposure.rateSourceModule}
                </p>
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">币种</th>
                      <th className="py-2">方向</th>
                      <th className="py-2">未核销应收</th>
                      <th className="py-2">未付应付</th>
                      <th className="py-2">净敞口</th>
                      <th className="py-2">汇率</th>
                      <th className="py-2">本位币敞口</th>
                      <th className="py-2">±1% 敏感度</th>
                      <th className="py-2">±5% 敏感度</th>
                      <th className="py-2">敞口集中度</th>
                    </tr>
                  </thead>
                  <tbody>
                    {exposure.rows.map((r) => (
                      <tr key={r.currency} className="border-border border-t">
                        <td className="py-2 font-medium">{r.currency}</td>
                        <td className="py-2">
                          <Badge tone={r.direction === 'FLAT' ? 'info' : 'warning'}>
                            {fundExposureDirectionLabel(r.direction, r.directionLabel)}
                          </Badge>
                        </td>
                        <td className="py-2">
                          {formatFundMoney(r.receivableOpenAmount, r.currency)}
                        </td>
                        <td className="py-2">{formatFundMoney(r.payableOpenAmount, r.currency)}</td>
                        <td className="py-2">{formatFundMoney(r.netExposureAmount, r.currency)}</td>
                        <td className="py-2">{r.rateDisplay}</td>
                        <td className="py-2">
                          {formatFundMoney(r.baseExposureAmount, exposure.baseCurrency)}
                        </td>
                        <td className="py-2">
                          {formatFundMoney(r.sensitivityPer1Pct, exposure.baseCurrency)}
                        </td>
                        <td className="py-2">
                          {formatFundMoney(r.sensitivityPer5Pct, exposure.baseCurrency)}
                        </td>
                        <td className="py-2">{formatFundExposureShare(r.exposureShare)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <Notice tone="warning" title="敞口的两端只含应收与应付">
                  {exposure.inventoryNote}（归属模块 {exposure.inventoryOwnerModule}）
                </Notice>
                {snapshots && snapshots.items.length > 0 && (
                  <div>
                    <p className="text-muted-foreground text-copy-13 mb-1">
                      敞口留档（最近 {snapshots.items.length} 条 / 共 {snapshots.total} 条）——
                      快照把当时用的汇率与生效日一起钉住，便于复盘。
                    </p>
                    <table className="text-copy-13 w-full text-left">
                      <thead className="text-muted-foreground">
                        <tr>
                          <th className="py-2">快照号</th>
                          <th className="py-2">基准日</th>
                          <th className="py-2">币种</th>
                          <th className="py-2">净敞口</th>
                          <th className="py-2">汇率</th>
                          <th className="py-2">汇率生效日</th>
                          <th className="py-2">本位币敞口</th>
                        </tr>
                      </thead>
                      <tbody>
                        {snapshots.items.map((s) => (
                          <tr key={s.id} className="border-border border-t">
                            <td className="py-2 font-medium">{s.snapshotNo}</td>
                            <td className="py-2">{s.asOfDate}</td>
                            <td className="py-2">{s.currency}</td>
                            <td className="py-2">
                              {formatFundMoney(s.netExposureAmount, s.currency)}
                            </td>
                            <td className="py-2">{formatFundAmount(s.exchangeRate)}</td>
                            <td className="py-2">{s.rateEffectiveDate}</td>
                            <td className="py-2">
                              {formatFundMoney(s.baseExposureAmount, s.baseCurrency)}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            )}
          </SectionCard>

          {/* ---------- ④ 资金周转天数 ---------- */}
          <SectionCard
            title="资金周转天数"
            description="DSO = 期末未核销应收 ÷ 期间实际流入 × 期间天数；DPO = 期末未付应付 ÷ 期间应付发生额 × 期间天数。"
          >
            {!turnover || turnover.rows.length === 0 ? (
              <EmptyState title="还没有可算的余额" description="确认应收或应付之后才有周转天数。" />
            ) : (
              <div className="space-y-3">
                <p className="text-muted-foreground text-copy-13">
                  区间 {turnover.fromDate} → {turnover.toDate}（{turnover.periodDays} 个自然日，UTC
                  日历日口径）
                </p>
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">币种</th>
                      <th className="py-2">未核销应收</th>
                      <th className="py-2">期间实际流入</th>
                      <th className="py-2">未付应付</th>
                      <th className="py-2">期间应付发生额</th>
                      <th className="py-2">DSO</th>
                      <th className="py-2">DPO</th>
                      <th className="py-2">资金周转天数</th>
                      <th className="py-2">存货周转天数</th>
                    </tr>
                  </thead>
                  <tbody>
                    {turnover.rows.map((r) => (
                      <tr key={r.currency} className="border-border border-t">
                        <td className="py-2 font-medium">{r.currency}</td>
                        <td className="py-2">
                          {formatFundMoney(r.receivableOpenAmount, r.currency)}
                        </td>
                        <td className="py-2">
                          {formatFundMoney(r.actualInflowInPeriod, r.currency)}
                        </td>
                        <td className="py-2">
                          {formatFundMoney(r.payableUnpaidAmount, r.currency)}
                        </td>
                        <td className="py-2">
                          {formatFundMoney(r.payableOccurrenceInPeriod, r.currency)}
                        </td>
                        <td className="py-2">{formatFundDays(r.dsoDays)}</td>
                        <td className="py-2">{formatFundDays(r.dpoDays)}</td>
                        <td className="py-2">{formatFundDays(r.fundTurnoverDays)}</td>
                        <td className="py-2">
                          {r.dioDays === null ? '—' : formatFundDays(r.dioDays)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <Notice tone="warning" title="存货段缺失">
                  存货周转天数恒为空（不是 0 天）—— 存货不在本服务，归属模块{' '}
                  {turnover.rows[0]?.inventoryOwnerModule ?? '13.1.1.1'}。
                </Notice>
              </div>
            )}
          </SectionCard>

          {/* ---------- ⑤ 付款集中度 ---------- */}
          <SectionCard
            title="付款集中度"
            description="口径 = 计划付款（12.1.2.2 的计划表写不出「已付款」）。时间维度与付款对象维度分开看。"
          >
            {!concentration || concentration.rows.length === 0 ? (
              <EmptyState
                title="窗口内没有计划付款"
                description="生成付款计划（12.1.2.2）之后，这里才看得到付款节奏的集中程度。"
              />
            ) : (
              <div className="space-y-4">
                <Notice tone="info" title="口径">
                  {concentration.basis}
                </Notice>
                {concentration.rows.map((row) => (
                  <div key={row.currency}>
                    <p className="text-copy-13 mb-1">
                      <b>{row.currency}</b> · 窗口内计划付款{' '}
                      {formatFundMoney(row.totalPlannedAmount, row.currency)}（{row.planCount}{' '}
                      条计划） · 最集中的一桶 {formatFundShare(row.topBucketShare)}
                      {row.topBucketPeriod ? `（${row.topBucketPeriod}）` : ''}
                    </p>
                    <table className="text-copy-13 w-full text-left">
                      <thead className="text-muted-foreground">
                        <tr>
                          <th className="py-2">时间桶</th>
                          <th className="py-2">计划付款额</th>
                          <th className="py-2">占比</th>
                        </tr>
                      </thead>
                      <tbody>
                        {row.buckets.map((b) => (
                          <tr key={b.periodStart} className="border-border border-t">
                            <td className="py-2 font-medium">{b.periodStart}</td>
                            <td className="py-2">{formatFundMoney(b.amount, row.currency)}</td>
                            <td className="py-2">{formatFundShare(b.share)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                    {row.topCounterparties.length > 0 && (
                      <p className="text-muted-foreground text-copy-13 mt-1">
                        付款对象集中度：
                        {row.topCounterparties
                          .map(
                            (c) =>
                              `${c.counterpartyName || '（未填）'} ${formatFundShare(c.share)}`,
                          )
                          .join('；')}
                      </p>
                    )}
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          {/* ---------- ⑥ 流动性预警 ---------- */}
          <SectionCard
            title="流动性预警"
            description="四条规则：付款集中度、净流出缺口、应收逾期、现金覆盖天数。阈值是租户自己维护的。"
          >
            <div className="space-y-3">
              {thresholds && (
                <p className="text-muted-foreground text-copy-13">
                  当前生效：<Badge tone="info">{fundThresholdSourceLabel(thresholds.source)}</Badge>
                  <span className="ml-2">{thresholds.detail}</span>
                </p>
              )}
              {!alerts || alerts.alerts.length === 0 ? (
                <EmptyState
                  title="当前窗口没有触发预警"
                  description="阈值由服务端判定，观测值与阈值都在下面那张表里可查。"
                />
              ) : (
                <ul className="space-y-2">
                  {alerts.alerts.map((a) => (
                    <li
                      key={`${a.rule}-${a.currency}`}
                      className="border-border rounded-md border p-3 text-sm"
                    >
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone={a.severity === 'CRITICAL' ? 'danger' : 'warning'}>
                          {fundSeverityLabel(a.severity)}
                        </Badge>
                        <span className="font-medium">{a.title}</span>
                        <span className="text-muted-foreground">
                          {a.ruleLabel} · {a.currency}
                        </span>
                      </div>
                      <p className="text-muted-foreground mt-1">{a.detail}</p>
                      <p className="text-muted-foreground text-copy-13 mt-1">
                        判定依据：{a.basis} · 观测值 {formatFundAmount(a.observedValue)} · 阈值{' '}
                        {formatFundAmount(a.thresholdValue)}
                      </p>
                    </li>
                  ))}
                </ul>
              )}

              {/* ---------- 阈值配置 ---------- */}
              <div className="border-border rounded-md border p-3">
                <p className="mb-2 text-sm font-medium">预警阈值配置（整体替换）</p>
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">规则</th>
                      <th className="py-2">名称</th>
                      <th className="py-2">阈值</th>
                      <th className="py-2">启用</th>
                    </tr>
                  </thead>
                  <tbody>
                    {thresholdDrafts.map((draft, index) => (
                      <tr key={draft.code} className="border-border border-t">
                        <td className="py-2 font-medium">{draft.code}</td>
                        <td className="py-2">
                          <Input
                            value={draft.label}
                            aria-label={`${draft.code} 名称`}
                            onChange={(e) =>
                              setThresholdDrafts((list) =>
                                list.map((item, i) =>
                                  i === index ? { ...item, label: e.target.value } : item,
                                ),
                              )
                            }
                          />
                        </td>
                        <td className="py-2">
                          <Input
                            value={draft.value}
                            aria-label={`${draft.code} 阈值`}
                            onChange={(e) =>
                              setThresholdDrafts((list) =>
                                list.map((item, i) =>
                                  i === index ? { ...item, value: e.target.value } : item,
                                ),
                              )
                            }
                          />
                        </td>
                        <td className="py-2">
                          <input
                            type="checkbox"
                            checked={draft.enabled}
                            aria-label={`${draft.code} 启用`}
                            onChange={(e) =>
                              setThresholdDrafts((list) =>
                                list.map((item, i) =>
                                  i === index ? { ...item, enabled: e.target.checked } : item,
                                ),
                              )
                            }
                          />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <div className="mt-3 flex flex-wrap gap-2">
                  <Button onClick={() => void saveThresholds()} disabled={loading}>
                    保存阈值
                  </Button>
                  <Button
                    variant="secondary"
                    onClick={() => void resetThresholds()}
                    disabled={loading}
                  >
                    回到蓝图默认
                  </Button>
                </div>
                <p className="text-muted-foreground text-copy-13 mt-2">
                  ⚠️ 阈值配置是整体替换：四条规则必须全部给出（不想启用某条就把「启用」去掉）。
                </p>
              </div>
            </div>
          </SectionCard>
        </>
      )}
    </div>
  );
}
