'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Download, RefreshCw, TriangleAlert } from 'lucide-react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  PurchaseAnalyticsCapabilities,
  PurchaseAnalyticsConcentration,
  PurchaseAnalyticsCostToSales,
  PurchaseAnalyticsEfficiency,
  PurchaseAnalyticsFactPage,
  PurchaseAnalyticsFactRow,
  PurchaseAnalyticsOnTimeRate,
  PurchaseAnalyticsOptions,
  PurchaseAnalyticsPriceVariance,
  PurchaseAnalyticsSummary,
  PurchaseAnalyticsSyncResult,
  PurchaseAnalyticsTrend,
} from '../../../../../packages/api-contracts/src/index';
import {
  blockedCapabilities,
  concentrationTone,
  downloadPurchaseAnalyticsCsv,
  efficiencyStatusLabel,
  factKindLabel,
  fetchPurchaseAnalyticsCapabilities,
  fetchPurchaseAnalyticsConcentration,
  fetchPurchaseAnalyticsCostToSales,
  fetchPurchaseAnalyticsEfficiency,
  fetchPurchaseAnalyticsFacts,
  fetchPurchaseAnalyticsOnTimeRate,
  fetchPurchaseAnalyticsOptions,
  fetchPurchaseAnalyticsPriceVariance,
  fetchPurchaseAnalyticsSummary,
  fetchPurchaseAnalyticsTrend,
  fmt,
  fxStatusLabel,
  moneyText,
  NO_DATA_TEXT,
  onTimeStatusLabel,
  percentText,
  shareText,
  sourceTypeLabel,
  syncPurchaseAnalyticsFacts,
  type PurchaseAnalyticsParams,
} from './purchase-analytics-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 6.5.2.1 采购分析工作区。
 *
 * <p>蓝图：「采购侧报表：采购金额趋势、供应商集中度与贡献、采购价格波动、到货准时率、
 * 采购成本占销售额比、按类目/店铺维度的采购效率。输入：采购单与收货数据；时间范围与维度筛选。
 * 输出：报表图表 + 可导出明细；下钻到具体采购单。」
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>口径随数据返回</b>：每个报表块下方**原样显示**后端给的 basis 原文 ——
 *       集中度是 top-N 还是 HHI、准时率的分母是哪种行、价格波动是环比还是同比，
 *       页面不替后端重新解释一遍。</li>
 *   <li><b>跨币种不合并</b>：金额按币种分列，**不给**把多个币种加起来的一个总数 ——
 *       那个总数是 1:1 折算出来的假数。</li>
 *   <li><b>算不出来不是 0</b>：没有汇率快照、缺销售分母、上期没数据，显示「无数据」+
 *       后端给的原因，不显示 0。</li>
 *   <li><b>未交付的能力逐条点名</b>：采购单原单 / 质检 / 13 利润与经营分析在顶部横幅里
 *       写明「归谁管、缺了会怎样」。</li>
 *   <li><b>加载 / 空 / 错误三态齐全</b>。</li>
 * </ul>
 */

const PAGE_SIZE = 20;

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function defaultFrom(): string {
  const now = new Date();
  const start = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() - 89));
  return start.toISOString().slice(0, 10);
}

export function PurchaseAnalyticsWorkspace() {
  const [params, setParams] = useState<PurchaseAnalyticsParams>({
    from: defaultFrom(),
    to: today(),
    page: 0,
    size: PAGE_SIZE,
  });
  const [granularity, setGranularity] = useState('MONTH');
  const [efficiencyDimension, setEfficiencyDimension] = useState('CATEGORY');

  const [caps, setCaps] = useState<PurchaseAnalyticsCapabilities | null>(null);
  const [options, setOptions] = useState<PurchaseAnalyticsOptions | null>(null);
  const [summary, setSummary] = useState<PurchaseAnalyticsSummary | null>(null);
  const [trend, setTrend] = useState<PurchaseAnalyticsTrend | null>(null);
  const [concentration, setConcentration] = useState<PurchaseAnalyticsConcentration | null>(null);
  const [variance, setVariance] = useState<PurchaseAnalyticsPriceVariance | null>(null);
  const [onTime, setOnTime] = useState<PurchaseAnalyticsOnTimeRate | null>(null);
  const [ratio, setRatio] = useState<PurchaseAnalyticsCostToSales | null>(null);
  const [efficiency, setEfficiency] = useState<PurchaseAnalyticsEfficiency | null>(null);
  const [facts, setFacts] = useState<PurchaseAnalyticsFactPage | null>(null);
  const [syncResult, setSyncResult] = useState<PurchaseAnalyticsSyncResult | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [currency, setCurrency] = useState('');
  const [supplierRefId, setSupplierRefId] = useState('');
  const [categoryCode, setCategoryCode] = useState('');
  const [shopCode, setShopCode] = useState('');

  const query = useMemo<PurchaseAnalyticsParams>(
    () => ({
      ...params,
      currency: currency || undefined,
      supplierRefId: supplierRefId || undefined,
      categoryCode: categoryCode || undefined,
      shopCode: shopCode || undefined,
      keyword: keyword.trim() || undefined,
    }),
    [params, currency, supplierRefId, categoryCode, shopCode, keyword],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [cap, opt, sum, tr, conc, varc, ontime, cost, eff, factPage] = await Promise.all([
        fetchPurchaseAnalyticsCapabilities(),
        fetchPurchaseAnalyticsOptions(),
        fetchPurchaseAnalyticsSummary(query),
        fetchPurchaseAnalyticsTrend({ ...query, granularity }),
        fetchPurchaseAnalyticsConcentration({ ...query, topN: 5 }),
        fetchPurchaseAnalyticsPriceVariance({ ...query, dimension: 'SUPPLIER' }),
        fetchPurchaseAnalyticsOnTimeRate(query),
        fetchPurchaseAnalyticsCostToSales(query),
        fetchPurchaseAnalyticsEfficiency({ ...query, dimension: efficiencyDimension }),
        fetchPurchaseAnalyticsFacts(query),
      ]);
      setCaps(cap);
      setOptions(opt);
      setSummary(sum);
      setTrend(tr);
      setConcentration(conc);
      setVariance(varc);
      setOnTime(ontime);
      setRatio(cost);
      setEfficiency(eff);
      setFacts(factPage);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [query, granularity, efficiencyDimension]);

  useEffect(() => {
    void load();
  }, [load]);

  async function runSync() {
    setBusy(true);
    setError(null);
    try {
      const result = await syncPurchaseAnalyticsFacts({
        sourceType: 'ALL',
        from: query.from,
        to: query.to,
      });
      setSyncResult(result);
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function exportCsv() {
    setBusy(true);
    setError(null);
    try {
      await downloadPurchaseAnalyticsCsv(query);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  const blocked = blockedCapabilities(caps);
  const purchaseAmounts = summary?.purchaseAmounts ?? [];
  const maxTrend = useMemo(
    () =>
      (trend?.points ?? []).reduce((max, point) => Math.max(max, Math.abs(point.amount)), 0) || 1,
    [trend],
  );

  if (loading && !summary) {
    return (
      <div className="space-y-4">
        <PageHeader title="采购分析" description="正在加载采购分析数据…" />
        <EmptyState title="正在加载…" description="报表口径与数据一起返回，稍等片刻。" />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="采购分析"
        description="采购金额趋势、供应商集中度与贡献、采购价格波动、到货准时率、采购成本占销售额比、按类目/店铺维度的采购效率。"
        actions={
          <div className="flex items-center gap-2">
            <Button variant="outline" size="sm" onClick={() => void load()} disabled={busy}>
              <RefreshCw className="mr-1 h-4 w-4" />
              刷新
            </Button>
            <Button variant="outline" size="sm" onClick={() => void exportCsv()} disabled={busy}>
              <Download className="mr-1 h-4 w-4" />
              导出明细 CSV
            </Button>
            <Button size="sm" onClick={() => void runSync()} disabled={busy}>
              {busy ? '处理中…' : '同步上游事实'}
            </Button>
          </div>
        }
      />

      {error ? (
        <SectionCard title="读取失败">
          <EmptyState
            icon={<TriangleAlert className="h-6 w-6" />}
            title="采购分析数据读取失败"
            description={error}
            action={
              <Button variant="outline" onClick={() => void load()}>
                重试
              </Button>
            }
          />
        </SectionCard>
      ) : null}

      {blocked.length > 0 ? (
        <SectionCard
          title="本模块的上下游可用性"
          description="下列能力未接线或未交付 —— 界面上必须写明归谁管、缺了会怎样，不能让运营以为「没有数据」。"
        >
          <ul className="space-y-2">
            {blocked.map((section) => (
              <li key={section.key} className="text-copy-13 text-muted-foreground">
                <Badge tone="warning" className="mr-2">
                  {section.ownerModule ?? '未交付'}
                </Badge>
                <span className="text-foreground">{section.label}</span>
                <span className="ml-2">{section.blockedReason}</span>
                {section.impact ? <span className="ml-2">影响：{section.impact}</span> : null}
              </li>
            ))}
          </ul>
        </SectionCard>
      ) : null}

      {syncResult ? (
        <SectionCard
          title="最近一次同步"
          description={`批次 ${syncResult.runNo} · ${syncResult.windowStart} ~ ${syncResult.windowEnd} · 状态 ${syncResult.status}`}
        >
          <p className="text-copy-13 text-muted-foreground">
            扫描 {syncResult.scannedCount} 行 · 新增 {syncResult.createdCount} 行 · 更新{' '}
            {syncResult.updatedCount} 行 · 跳过 {syncResult.skippedCount} 行 · 汇率未登记{' '}
            {syncResult.unconvertedCount} 行 · 类目未解析 {syncResult.unresolvedSkuCount} 行
          </p>
          {syncResult.notes.length > 0 ? (
            <ul className="text-copy-13 text-muted-foreground mt-2 list-disc space-y-1 pl-5">
              {syncResult.notes.map((note) => (
                <li key={note}>{note}</li>
              ))}
            </ul>
          ) : null}
        </SectionCard>
      ) : null}

      <SectionCard title="筛选" description="时间范围与维度筛选；下拉项全部来自已物化的真实事实。">
        <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
          <label className="text-copy-13 text-muted-foreground">
            开始日期
            <Input
              className="mt-1"
              type="date"
              value={params.from ?? ''}
              onChange={(e) => setParams({ ...params, from: e.target.value, page: 0 })}
            />
          </label>
          <label className="text-copy-13 text-muted-foreground">
            结束日期
            <Input
              className="mt-1"
              type="date"
              value={params.to ?? ''}
              onChange={(e) => setParams({ ...params, to: e.target.value, page: 0 })}
            />
          </label>
          <label className="text-copy-13 text-muted-foreground">
            币种
            <select
              className="border-border bg-background text-copy-13 mt-1 h-9 w-full rounded-md border px-2"
              value={currency}
              onChange={(e) => {
                setCurrency(e.target.value);
                setParams({ ...params, page: 0 });
              }}
            >
              <option value="">全部币种</option>
              {(options?.currencies ?? []).map((item) => (
                <option key={item.value} value={item.value}>
                  {item.label}（{item.factCount}）
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-13 text-muted-foreground">
            供应商
            <select
              className="border-border bg-background text-copy-13 mt-1 h-9 w-full rounded-md border px-2"
              value={supplierRefId}
              onChange={(e) => {
                setSupplierRefId(e.target.value);
                setParams({ ...params, page: 0 });
              }}
            >
              <option value="">全部供应商</option>
              {(options?.suppliers ?? []).map((item) => (
                <option key={item.value} value={item.value}>
                  {item.label}（{item.factCount}）
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-13 text-muted-foreground">
            类目
            <select
              className="border-border bg-background text-copy-13 mt-1 h-9 w-full rounded-md border px-2"
              value={categoryCode}
              onChange={(e) => {
                setCategoryCode(e.target.value);
                setParams({ ...params, page: 0 });
              }}
            >
              <option value="">全部类目</option>
              {(options?.categories ?? []).map((item) => (
                <option key={item.value} value={item.value}>
                  {item.label}（{item.factCount}）
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-13 text-muted-foreground">
            店铺
            <select
              className="border-border bg-background text-copy-13 mt-1 h-9 w-full rounded-md border px-2"
              value={shopCode}
              onChange={(e) => {
                setShopCode(e.target.value);
                setParams({ ...params, page: 0 });
              }}
            >
              <option value="">全部店铺</option>
              {(options?.shops ?? []).map((item) => (
                <option key={item.value} value={item.value}>
                  {item.label}（{item.factCount}）
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-13 text-muted-foreground md:col-span-2">
            关键词（来源单号 / 采购单号 / 供应商 / SKU）
            <Input
              className="mt-1"
              value={keyword}
              placeholder="例如 PO-2026-0001 或 供应商编码"
              onChange={(e) => setKeyword(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') setParams({ ...params, page: 0 });
              }}
            />
          </label>
        </div>
        <div className="mt-3 flex items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              setKeyword('');
              setCurrency('');
              setSupplierRefId('');
              setCategoryCode('');
              setShopCode('');
              setParams({ from: defaultFrom(), to: today(), page: 0, size: PAGE_SIZE });
            }}
          >
            重置筛选
          </Button>
          {options?.lastSyncNote ? (
            <span className="text-copy-13 text-muted-foreground">{options.lastSyncNote}</span>
          ) : null}
        </div>
      </SectionCard>

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        {purchaseAmounts.map((item) => (
          <StatCard
            key={item.currency}
            label={`采购金额（${item.currency}）`}
            value={moneyText(item.amount, item.currency)}
            unit={`${item.factCount} 笔`}
          />
        ))}
        {purchaseAmounts.length === 0 ? (
          <StatCard label="采购金额" value={NO_DATA_TEXT} unit="窗口内没有到货事实" />
        ) : null}
        <StatCard
          label="供应商数"
          value={fmt(summary?.supplierCount ?? null)}
          unit="到货口径"
          polarity="neutral"
        />
        <StatCard
          label="到货准时率"
          value={percentText(onTime?.onTimeRate ?? null)}
          unit={`${onTime?.onTimeCount ?? 0} / ${onTime?.denominatorCount ?? 0} 承诺到货行`}
          polarity="positive"
        />
        <StatCard
          label="采购成本占销售额比"
          value={ratio?.available ? percentText(ratio.ratio) : NO_DATA_TEXT}
          unit={ratio?.available ? (ratio.ratioCurrency ?? '') : '销售分母不可用'}
          polarity="neutral"
        />
      </div>

      <SectionCard
        title="采购金额趋势"
        description="按币种分列；跨币种不相加。"
        extra={
          <select
            className="border-border bg-background text-copy-13 h-9 rounded-md border px-2"
            value={granularity}
            onChange={(e) => setGranularity(e.target.value)}
          >
            <option value="DAY">按日</option>
            <option value="WEEK">按周</option>
            <option value="MONTH">按月</option>
          </select>
        }
      >
        {(trend?.points ?? []).length === 0 ? (
          <EmptyState
            title="这个窗口里没有到货采购事实"
            description="可能是还没同步过上游事实，也可能是这段时间真的没有到货 —— 先点「同步上游事实」再看。"
          />
        ) : (
          <div className="space-y-2">
            {(trend?.points ?? []).map((point) => (
              <div key={`${point.period}-${point.currency}`} className="flex items-center gap-3">
                <span className="text-label-12 text-muted-foreground w-24 shrink-0">
                  {point.period}
                </span>
                <span className="text-label-12 w-12 shrink-0">{point.currency}</span>
                <div className="bg-muted h-3 flex-1 overflow-hidden rounded">
                  <div
                    className="bg-primary h-full"
                    style={{ width: `${Math.round((Math.abs(point.amount) / maxTrend) * 100)}%` }}
                  />
                </div>
                <span className="text-copy-13 w-32 shrink-0 text-right">
                  {moneyText(point.amount, point.currency)}
                </span>
              </div>
            ))}
          </div>
        )}
        {trend?.basis ? (
          <p className="text-copy-13 text-muted-foreground mt-3">口径：{trend.basis}</p>
        ) : null}
      </SectionCard>

      <SectionCard
        title="供应商集中度与贡献"
        description="top-N 占比 + HHI（0–1 标度）。同一币种内比较，跨币种不合并。"
      >
        {(concentration?.buckets ?? []).length === 0 ? (
          <EmptyState
            title="集中度算不出来"
            description="窗口内没有到货采购事实 —— 这不是「集中度为 0」，是「没有数据」。"
          />
        ) : (
          (concentration?.buckets ?? []).map((bucket) => (
            <div key={bucket.currency} className="mb-4 last:mb-0">
              <div className="text-copy-13 mb-2 flex flex-wrap items-center gap-2">
                <Badge tone="primary">{bucket.currency}</Badge>
                <span>
                  合计 {moneyText(bucket.totalAmount, bucket.currency)} · 供应商{' '}
                  {bucket.supplierCount} 家
                </span>
                <Badge tone={concentrationTone(bucket.hhi)}>
                  HHI {fmt(bucket.hhi)}（top{bucket.topN} 占比 {shareText(bucket.topShare)}）
                </Badge>
              </div>
              <table className="text-copy-13 w-full">
                <thead>
                  <tr className="text-muted-foreground border-border border-b text-left">
                    <th className="py-1">供应商</th>
                    <th className="py-1">金额</th>
                    <th className="py-1">占比</th>
                    <th className="py-1">累计占比</th>
                    <th className="py-1">笔数</th>
                  </tr>
                </thead>
                <tbody>
                  {bucket.suppliers.map((supplier) => (
                    <tr
                      key={`${supplier.supplierRefId}-${supplier.currency}`}
                      className="border-border border-b"
                    >
                      <td className="py-1">
                        {supplier.supplierName}
                        <span className="text-muted-foreground ml-1">{supplier.supplierCode}</span>
                      </td>
                      <td className="py-1">{moneyText(supplier.amount, supplier.currency)}</td>
                      <td className="py-1">{shareText(supplier.share)}</td>
                      <td className="py-1">{shareText(supplier.cumulativeShare)}</td>
                      <td className="py-1">{supplier.factCount}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ))
        )}
        {concentration?.basis ? (
          <p className="text-copy-13 text-muted-foreground mt-3">口径：{concentration.basis}</p>
        ) : null}
      </SectionCard>

      <SectionCard title="采购价格波动" description="环比（窗口末月 vs 上一月），按供应商维度。">
        {(variance?.rows ?? []).length === 0 ? (
          <EmptyState
            title="价格波动算不出来"
            description="窗口内没有到货采购事实 —— 这不是「价格没变」。"
          />
        ) : (
          <table className="text-copy-13 w-full">
            <thead>
              <tr className="text-muted-foreground border-border border-b text-left">
                <th className="py-1">供应商</th>
                <th className="py-1">币种</th>
                <th className="py-1">本期均价</th>
                <th className="py-1">上期均价</th>
                <th className="py-1">变动率</th>
                <th className="py-1">状态</th>
              </tr>
            </thead>
            <tbody>
              {(variance?.rows ?? []).map((row) => (
                <tr key={`${row.dimension}-${row.currency}`} className="border-border border-b">
                  <td className="py-1">{row.dimensionLabel || row.dimension}</td>
                  <td className="py-1">{row.currency}</td>
                  <td className="py-1">{fmt(row.currentAveragePrice)}</td>
                  <td className="py-1">{fmt(row.previousAveragePrice)}</td>
                  <td className="py-1">{percentText(row.changeRate)}</td>
                  <td className="py-1">{row.status}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {variance?.basis ? (
          <p className="text-copy-13 text-muted-foreground mt-3">口径：{variance.basis}</p>
        ) : null}
      </SectionCard>

      <SectionCard
        title="到货准时率"
        description="分母是承诺到货行（不是收货行）；逾期待到货的单据列在下面，可下钻。"
      >
        <div className="mb-3 grid grid-cols-2 gap-3 md:grid-cols-4">
          <StatCard label="准时率" value={percentText(onTime?.onTimeRate ?? null)} />
          <StatCard label="准时行" value={fmt(onTime?.onTimeCount ?? null)} />
          <StatCard label="逾期行" value={fmt(onTime?.lateCount ?? null)} />
          <StatCard label="未到货行（不进分母）" value={fmt(onTime?.pendingCount ?? null)} />
        </div>
        {(onTime?.lateRows ?? []).length > 0 ? (
          <table className="text-copy-13 w-full">
            <thead>
              <tr className="text-muted-foreground border-border border-b text-left">
                <th className="py-1">到货跟踪单</th>
                <th className="py-1">采购单号</th>
                <th className="py-1">供应商</th>
                <th className="py-1">承诺到货日</th>
                <th className="py-1">实际到货</th>
                <th className="py-1">晚到天数</th>
              </tr>
            </thead>
            <tbody>
              {(onTime?.lateRows ?? []).map((row) => (
                <tr key={row.factId} className="border-border border-b">
                  <td className="py-1">{row.sourceDocNo}</td>
                  <td className="py-1">{row.sourceRefNo}</td>
                  <td className="py-1">{row.supplierName}</td>
                  <td className="py-1">{row.promisedArrivalDate}</td>
                  <td className="py-1">{row.occurredAt}</td>
                  <td className="py-1">{row.delayDays}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
        {onTime?.denominatorRule ? (
          <p className="text-copy-13 text-muted-foreground mt-3">
            分母口径：{onTime.denominatorRule} 日历口径：{onTime.calendarRule}
          </p>
        ) : null}
      </SectionCard>

      <SectionCard title="采购成本占销售额比" description="分子与分母必须同币种，否则不出比值。">
        {ratio && !ratio.available ? (
          <div className="space-y-2">
            <Badge tone="warning">{ratio.ownerModule ?? '未交付'}</Badge>
            <p className="text-copy-13 text-muted-foreground">{ratio.impact}</p>
            <p className="text-copy-13 text-muted-foreground">{ratio.salesNote}</p>
            <table className="text-copy-13 w-full">
              <thead>
                <tr className="text-muted-foreground border-border border-b text-left">
                  <th className="py-1">币种</th>
                  <th className="py-1">采购成本（分子）</th>
                  <th className="py-1">本位币金额</th>
                  <th className="py-1">笔数</th>
                </tr>
              </thead>
              <tbody>
                {(ratio.purchaseAmounts ?? []).map((item) => (
                  <tr key={item.currency} className="border-border border-b">
                    <td className="py-1">{item.currency}</td>
                    <td className="py-1">{moneyText(item.amount, item.currency)}</td>
                    <td className="py-1">
                      {item.baseAmount === null
                        ? NO_DATA_TEXT
                        : moneyText(item.baseAmount, item.baseCurrency)}
                    </td>
                    <td className="py-1">{item.factCount}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
        {ratio?.basis ? (
          <p className="text-copy-13 text-muted-foreground mt-3">口径：{ratio.basis}</p>
        ) : null}
      </SectionCard>

      <SectionCard
        title="按维度看采购效率"
        description="类目维度给计划量 / 实收量 / 达成率；店铺维度只有计划侧口径。"
        extra={
          <select
            className="border-border bg-background text-copy-13 h-9 rounded-md border px-2"
            value={efficiencyDimension}
            onChange={(e) => setEfficiencyDimension(e.target.value)}
          >
            <option value="CATEGORY">按类目</option>
            <option value="SHOP">按店铺</option>
          </select>
        }
      >
        {(efficiency?.rows ?? []).length === 0 ? (
          <EmptyState
            title="效率算不出来"
            description="窗口内没有计划或收货事实 —— 这不是「效率为 0」。"
          />
        ) : (
          <table className="text-copy-13 w-full">
            <thead>
              <tr className="text-muted-foreground border-border border-b text-left">
                <th className="py-1">维度</th>
                <th className="py-1">计划量</th>
                <th className="py-1">实收量</th>
                <th className="py-1">达成率</th>
                <th className="py-1">计划行 / 收货行</th>
                <th className="py-1">状态</th>
              </tr>
            </thead>
            <tbody>
              {(efficiency?.rows ?? []).map((row) => (
                <tr key={row.dimensionCode} className="border-border border-b">
                  <td className="py-1">{row.dimensionLabel}</td>
                  <td className="py-1">
                    {fmt(row.plannedQuantity)}
                    {row.unitCode ? ` ${row.unitCode}` : ''}
                  </td>
                  <td className="py-1">{fmt(row.receivedQuantity)}</td>
                  <td className="py-1">{percentText(row.fulfillmentRate)}</td>
                  <td className="py-1">
                    {row.planLineCount} / {row.receiptFactCount}
                  </td>
                  <td className="py-1">{efficiencyStatusLabel(row.status)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {efficiency?.basis ? (
          <p className="text-copy-13 text-muted-foreground mt-3">口径：{efficiency.basis}</p>
        ) : null}
      </SectionCard>

      <SectionCard
        title="下钻明细（事实台账）"
        description="每个指标都能下钻到具体单据行；导出内容与当前筛选一致。"
      >
        {(facts?.items ?? []).length === 0 ? (
          <EmptyState
            title="没有符合条件的事实"
            description="先点「同步上游事实」把已交付的上游数据取进来，或把筛选条件放宽。"
          />
        ) : (
          <>
            <table className="text-copy-13 w-full">
              <thead>
                <tr className="text-muted-foreground border-border border-b text-left">
                  <th className="py-1">发生日</th>
                  <th className="py-1">来源</th>
                  <th className="py-1">来源单号</th>
                  <th className="py-1">供应商</th>
                  <th className="py-1">SKU / 类目</th>
                  <th className="py-1">数量</th>
                  <th className="py-1">金额</th>
                  <th className="py-1">汇率</th>
                  <th className="py-1">准时</th>
                </tr>
              </thead>
              <tbody>
                {(facts?.items ?? []).map((fact: PurchaseAnalyticsFactRow) => (
                  <tr key={fact.id} className="border-border border-b">
                    <td className="py-1">{fact.occurredDate}</td>
                    <td className="py-1">
                      <Badge tone="neutral">{factKindLabel(fact.factKind)}</Badge>
                      <span className="text-muted-foreground ml-1">
                        {sourceTypeLabel(fact.sourceType)}
                      </span>
                    </td>
                    <td className="py-1">
                      {fact.sourceDocNo}
                      {fact.sourceRefNo ? (
                        <span className="text-muted-foreground ml-1">{fact.sourceRefNo}</span>
                      ) : null}
                    </td>
                    <td className="py-1">{fact.supplierName}</td>
                    <td className="py-1">
                      {fact.skuCode || '—'}
                      {fact.categoryCode ? (
                        <span className="text-muted-foreground ml-1">{fact.categoryCode}</span>
                      ) : null}
                    </td>
                    <td className="py-1">
                      {fmt(fact.quantity)}
                      {fact.unitCode ? ` ${fact.unitCode}` : ''}
                    </td>
                    <td className="py-1">{moneyText(fact.amount, fact.currency)}</td>
                    <td className="py-1">
                      <Badge
                        tone={fact.fxStatus === 'UNSET' ? 'warning' : 'neutral'}
                        title={fxStatusLabel(fact.fxStatus)}
                      >
                        {fact.fxStatus === 'UNSET'
                          ? '未折算'
                          : fact.baseAmount !== null
                            ? moneyText(fact.baseAmount, fact.baseCurrency)
                            : fxStatusLabel(fact.fxStatus)}
                      </Badge>
                    </td>
                    <td className="py-1">{onTimeStatusLabel(fact.onTimeStatus)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="text-copy-13 text-muted-foreground mt-3 flex items-center gap-3">
              <span>
                共 {facts?.total ?? 0} 条 · 第 {(facts?.page ?? 0) + 1} 页
              </span>
              <Button
                variant="outline"
                size="sm"
                disabled={(facts?.page ?? 0) <= 0}
                onClick={() => setParams({ ...params, page: Math.max(0, (params.page ?? 0) - 1) })}
              >
                上一页
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={(facts?.page ?? 0) + 1 >= Math.ceil((facts?.total ?? 0) / PAGE_SIZE)}
                onClick={() => setParams({ ...params, page: (params.page ?? 0) + 1 })}
              >
                下一页
              </Button>
            </div>
          </>
        )}
        {facts?.note ? (
          <p className="text-copy-13 text-muted-foreground mt-2">{facts.note}</p>
        ) : null}
      </SectionCard>
    </div>
  );
}
