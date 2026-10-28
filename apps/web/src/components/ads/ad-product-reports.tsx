'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { errorText } from '../inventory/inventory-model';
import {
  attributionModeText,
  attributionModeTone,
  fetchAdProductReportCapabilities,
  fetchAdProductReportExports,
  fetchAdProductReportOptions,
  fetchAdProductReportRows,
  metricDefinitionOf,
  metricHeader,
  reportDimensionLabel,
  reportEmptyText,
  reportExportUrl,
  reportMoney,
  reportQuantity,
  reportRatio,
  reportRatioTone,
  reportTimestampText,
  unavailableMetricText,
  validateReportRange,
} from './ad-product-report-model';
import type {
  AdProductReportCapabilities,
  AdProductReportExportPage,
  AdProductReportOptions,
  AdProductReportPage,
  AdProductReportQuery,
} from './ad-product-report-model';

/**
 * 14.3.1.3 商品维度报表。
 *
 * 🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * 1. **商品维度的数字是分摊出来的**。广告事实只到**活动**级（`ad_campaign_daily_metric`），
 *    「投的是哪个商品」来自 14.1.1.2 的投放关系 ⇒ 商品维度的数字**必须分摊**。
 *    服务端把**分摊规则原文**随报表一起返回，页面**原样展示** ——
 *    前端不自己写第二份规则，也不自己推。规则变了历史报表就不可比。
 * 2. **直接归属与等分摊销分开标**。每行带 `attributionMode`（DIRECT / ALLOCATED_EVENLY /
 *    MIXED）。把分摊值当平台原值看，会做出完全错误的投放决策。
 * 3. **归不到商品的花费看得见**。汇总里单列 `unattributedSpend` / `unattributedSales`，
 *    **不摊给任何商品，也不摊成 0**（摊成 0 就是说「这些广告没花钱」）。
 * 4. **不可比 ≠ 0**。比率列的 `null` 渲染成「不可比」（分母为 0 时压根算不出来）；
 *    `0` 才是「可比但真的是零」。
 * 5. **不同币种不合并**。汇总按币种拆开（服务端没有汇率源），界面**按币种分块展示** ——
 *    前端一相加就造出一个服务端从没算过的假总数。
 * 6. **时间范围必填**。没有区间就是全表扫（蓝图加粗句），所以页面不给「默认区间」。
 *
 * 🔴 金额与数量在 JS 侧**不做任何前端算术** —— ACOS / TACOS / 广告销售占比 / 广告后净利
 * 与汇总都是服务端 `BigDecimal` 的事，前端算一遍就会引入浮点误差，
 * 还会让「界面上的 ACOS」和「报表里的 ACOS」对不上。
 */
export function AdProductReportWorkspace() {
  const [capabilities, setCapabilities] = useState<AdProductReportCapabilities | null>(null);
  const [options, setOptions] = useState<AdProductReportOptions | null>(null);
  const [page, setPage] = useState<AdProductReportPage | null>(null);
  const [exports, setExports] = useState<AdProductReportExportPage | null>(null);

  const [query, setQuery] = useState<AdProductReportQuery>({
    dimension: 'LISTING',
    sort: 'SPEND_DESC',
    page: 0,
    size: 50,
  });
  const [rangeErrors, setRangeErrors] = useState<string[]>([]);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = useCallback(async (next: AdProductReportQuery) => {
    const validation = validateReportRange(next.from ?? '', next.to ?? '');
    setRangeErrors(validation.errors);
    if (!validation.ok) {
      // 🔴 时间范围缺失时不发请求：没有区间的报表就是全表扫。
      setPage(null);
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const [rows, traces] = await Promise.all([
        fetchAdProductReportRows(next),
        // 导出留痕是补充证据，取不到不该把整张报表拖垮。
        fetchAdProductReportExports(0, 20).catch(() => null),
      ]);
      setPage(rows);
      setExports(traces);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const [caps, opts] = await Promise.all([
          fetchAdProductReportCapabilities(),
          fetchAdProductReportOptions(),
        ]);
        if (!alive) return;
        setCapabilities(caps);
        setOptions(opts);
      } catch (e) {
        if (alive) setError(errorText(e));
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  useEffect(() => {
    void load(query);
  }, [load, query]);

  function applyFilter(patch: AdProductReportQuery) {
    setQuery((prev) => ({ ...prev, page: 0, ...patch }));
  }

  const metricDefinitions = page?.metricDefinitions ?? options?.metricDefinitions ?? [];
  const emptyText = reportEmptyText(page?.emptyReason ?? null, page?.emptyNote ?? '');

  /** 导出链接（与当前筛选完全一致）。 */
  const exportUrl = useMemo(
    () =>
      reportExportUrl({
        from: query.from,
        to: query.to,
        dimension: query.dimension,
        sort: query.sort,
        platformId: query.platformId,
        shopCode: query.shopCode,
        internalAdType: query.internalAdType,
        status: query.status,
      }),
    [query],
  );

  return (
    <main className="flex flex-col gap-4 p-4">
      <header className="flex flex-col gap-1">
        <h1 className="text-xl font-medium">商品维度报表</h1>
        <p className="text-muted-foreground text-sm">
          14.3.1.3 · 商品维度广告效果：SKU / SPU / Listing 维度的广告花费、广告销售额、总销售额、
          ACOS、TACOS、广告占比利润。广告销售占比是判断广告带来增量还是抢自然单的关键 —— 只看广告
          ROAS 答不了这个问题。
        </p>
      </header>

      {/* ================= 归因口径（分摊规则必须公示） ================= */}
      {page ? (
        <Section title="归因口径（分摊规则）">
          <p className="text-sm">{page.attributionNote}</p>
        </Section>
      ) : null}

      {/* ================= 口径说明 ================= */}
      {page ? (
        <Section title="口径说明">
          <p className="text-sm">{page.metricDefinitionNote}</p>
          <ul className="flex flex-col gap-1 text-xs">
            {metricDefinitions
              .filter((d) =>
                ['acos', 'tacos', 'ad_sales_share', 'ad_profit_share'].includes(d.metric),
              )
              .map((d) => (
                <li key={d.metric} className="flex flex-col">
                  <span className={d.available ? 'text-foreground' : 'text-amber-600'}>
                    {d.available ? '✓' : '✗'} {d.formula}
                  </span>
                  {!d.available ? (
                    <span className="text-muted-foreground">
                      责任模块 {d.ownerModule}：{d.unavailableReason}
                    </span>
                  ) : null}
                </li>
              ))}
          </ul>
        </Section>
      ) : null}

      {/* ================= 上游可达性 ================= */}
      {page && (!page.profitAvailable || !page.catalogAvailable || page.candidateTruncated) ? (
        <Section title="上游可达性">
          <ul className="flex flex-col gap-1 text-sm">
            {!page.profitAvailable ? (
              <li className="text-amber-600">
                🔴 利润侧（13.4.1.2）这次没读到 ⇒ 总销售额 / TACOS / 广告销售占比 / 总净利 /
                广告后净利 / 广告占比利润这几列是**空的**，不是 0。
                <span className="text-muted-foreground block text-xs">
                  原因：{page.profitUnavailableReason}
                </span>
              </li>
            ) : null}
            {!page.catalogAvailable ? (
              <li className="text-amber-600">
                🔴 商品主数据（catalog）这次没读到 ⇒ SKU 编码 / SPU 归属取不到，
                这一段的编码列是空的。
                <span className="text-muted-foreground block text-xs">
                  原因：{page.catalogUnavailableReason}
                </span>
              </li>
            ) : null}
            {page.candidateTruncated ? (
              <li className="text-amber-600">
                🔴 候选集被截断 ⇒ 报表只覆盖花费最高的一部分商品。
                <span className="text-muted-foreground block text-xs">
                  {page.candidateTruncationNote}
                </span>
              </li>
            ) : null}
          </ul>
        </Section>
      ) : null}

      {/* ================= 数据截至 ================= */}
      {page ? (
        <Section title="数据截至">
          <p className="text-sm">数据截至：{reportTimestampText(page.dataAsOf)}</p>
          <p className="text-muted-foreground text-xs">{page.dataAsOfNote}</p>
        </Section>
      ) : null}

      {/* ================= 筛选 ================= */}
      <Section title="时间范围与商品维度">
        <div className="flex flex-wrap gap-3">
          <Field label="统计起始日（必填）">
            <input
              type="date"
              className="rounded border px-2 py-1 text-sm"
              value={query.from ?? ''}
              onChange={(e) => applyFilter({ from: e.target.value })}
            />
          </Field>
          <Field label="统计截止日（必填）">
            <input
              type="date"
              className="rounded border px-2 py-1 text-sm"
              value={query.to ?? ''}
              onChange={(e) => applyFilter({ to: e.target.value })}
            />
          </Field>
          <Field label="商品维度">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={query.dimension ?? 'LISTING'}
              onChange={(e) => applyFilter({ dimension: e.target.value })}
            >
              {options?.dimensions.map((d) => (
                <option key={d.value} value={d.value}>
                  {d.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="平台">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={query.platformId ?? ''}
              onChange={(e) => applyFilter({ platformId: e.target.value })}
            >
              <option value="">全部平台</option>
              {options?.platforms.map((p) => (
                <option key={p.value} value={p.value}>
                  {p.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="店铺">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={query.shopCode ?? ''}
              onChange={(e) => applyFilter({ shopCode: e.target.value })}
            >
              <option value="">全部店铺</option>
              {options?.shops.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="归一后类型">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={query.internalAdType ?? ''}
              onChange={(e) => applyFilter({ internalAdType: e.target.value })}
            >
              <option value="">全部类型</option>
              {options?.internalAdTypes.map((t) => (
                <option key={t.value} value={t.value}>
                  {t.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="状态">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={query.status ?? ''}
              onChange={(e) => applyFilter({ status: e.target.value })}
            >
              <option value="">全部状态</option>
              {options?.statuses.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="排序">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={query.sort ?? 'SPEND_DESC'}
              onChange={(e) => applyFilter({ sort: e.target.value })}
            >
              {options?.sorts.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </Field>
        </div>
        {rangeErrors.length > 0 ? (
          <ul className="text-destructive text-xs">
            {rangeErrors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        ) : null}
        {options ? <p className="text-muted-foreground text-xs">{options.rangeLimitNote}</p> : null}
        <div className="flex items-center gap-2">
          <a
            className="rounded border px-3 py-1 text-sm"
            href={exportUrl}
            aria-disabled={rangeErrors.length > 0}
          >
            导出 CSV
          </a>
          <p className="text-muted-foreground text-xs">
            导出与当前筛选完全一致，口径与归因规则写进 CSV 表头；每次导出都会留痕。
          </p>
        </div>
      </Section>

      {/* ================= 汇总（按币种拆开） ================= */}
      {page && page.summaries.length > 0 ? (
        <Section title="汇总（筛选后的全集，不受分页影响）">
          {page.summaries.map((sum) => (
            <div key={sum.currency} className="flex flex-col gap-2">
              <p className="text-sm font-medium">币种 {sum.currency}</p>
              <dl className="grid grid-cols-2 gap-3 md:grid-cols-4">
                <Stat label="商品数" value={String(sum.groupCount)} />
                <Stat label="花费合计" value={reportMoney(sum.totalSpend, sum.currency)} />
                <Stat label="广告销售额合计" value={reportMoney(sum.totalSales, sum.currency)} />
                <Stat
                  label="曝光 / 点击 / 订单"
                  value={`${reportQuantity(sum.totalImpressions)} / ${reportQuantity(sum.totalClicks)} / ${reportQuantity(sum.totalOrders)}`}
                />
                <Stat label="CTR" value={reportRatio(sum.ctr)} />
                <Stat label="CPC" value={reportRatio(sum.cpc, false)} />
                <Stat label="转化率" value={reportRatio(sum.cvr)} />
                <Stat label="ACOS" value={reportRatio(sum.acos)} />
                <Stat label="ROAS" value={reportRatio(sum.roas, false)} />
                <Stat label="总销售额" value={reportMoney(sum.totalRevenue, sum.currency)} />
                <Stat label="TACOS" value={reportRatio(sum.tacos)} />
                <Stat label="广告销售占比" value={reportRatio(sum.adSalesShare)} />
                <Stat label="总净利" value={reportMoney(sum.totalNetProfit, sum.currency)} />
                <Stat label="广告后净利" value={reportMoney(sum.adAfterNetProfit, sum.currency)} />
                <Stat label="广告占比利润" value={reportRatio(sum.adProfitShare)} />
              </dl>
              {sum.metricFrom ? (
                <p className="text-muted-foreground text-xs">
                  实际统计区间：{sum.metricFrom} ~ {sum.metricTo}
                </p>
              ) : null}
              {/* 🔴 归不到任何商品的花费：单列，不摊给任何商品，也不摊成 0。 */}
              <p className="text-xs text-amber-600">
                归不到任何商品的广告花费：{reportMoney(sum.unattributedSpend, sum.currency)} ·
                广告销售额：{reportMoney(sum.unattributedSales, sum.currency)}
                （范围内总额 {reportMoney(sum.campaignTotalSpend, sum.currency)}{' '}
                中没有落到任何商品上的部分）
              </p>
              <p className="text-muted-foreground text-xs">{sum.scopedScopeNote}</p>
            </div>
          ))}
          {page.summaries.length > 1 ? (
            <p className="text-xs text-amber-600">
              🔴 本仓没有汇率源，不同币种**不合并**：上面的合计各自独立，不能直接相加。
            </p>
          ) : null}
        </Section>
      ) : null}

      {/* ================= 报表表 ================= */}
      <Section title={`商品广告报表 · ${reportDimensionLabel(query.dimension ?? 'LISTING')}`}>
        {loading ? (
          <p className="text-muted-foreground text-sm">正在加载报表…</p>
        ) : page && page.items.length === 0 ? (
          <p className="text-sm text-amber-600">{emptyText || '没有符合条件的数据。'}</p>
        ) : page ? (
          <>
            <div className="overflow-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground text-left text-xs">
                    <th className="py-1">商品</th>
                    <th className="py-1">币种</th>
                    <th className="py-1">
                      {metricHeader(metricDefinitionOf(metricDefinitions, 'spend'))}
                    </th>
                    <th className="py-1">曝光 / 点击 / 订单</th>
                    <th className="py-1">
                      {metricHeader(metricDefinitionOf(metricDefinitions, 'sales'))}
                    </th>
                    <th className="py-1">
                      {metricHeader(metricDefinitionOf(metricDefinitions, 'ctr'))}
                    </th>
                    <th className="py-1">
                      {metricHeader(metricDefinitionOf(metricDefinitions, 'cpc'))}
                    </th>
                    <th className="py-1">
                      {metricHeader(metricDefinitionOf(metricDefinitions, 'cvr'))}
                    </th>
                    <th className="py-1">
                      {metricHeader(metricDefinitionOf(metricDefinitions, 'acos'))}
                    </th>
                    <th className="py-1">
                      {metricHeader(metricDefinitionOf(metricDefinitions, 'roas'))}
                    </th>
                    <th className="py-1">
                      {metricHeader(metricDefinitionOf(metricDefinitions, 'total_sales'))}
                    </th>
                    <th className="py-1">
                      {metricHeader(metricDefinitionOf(metricDefinitions, 'tacos'))}
                    </th>
                    <th className="py-1">
                      {metricHeader(metricDefinitionOf(metricDefinitions, 'ad_sales_share'))}
                    </th>
                    <th className="py-1">
                      {metricHeader(metricDefinitionOf(metricDefinitions, 'net_profit'))}
                    </th>
                    <th className="py-1">
                      {metricHeader(metricDefinitionOf(metricDefinitions, 'ad_after_net_profit'))}
                    </th>
                    <th className="py-1">
                      {metricHeader(metricDefinitionOf(metricDefinitions, 'ad_profit_share'))}
                    </th>
                    <th className="py-1">归因方式</th>
                    <th className="py-1">统计区间</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((row) => (
                    <tr
                      key={`${row.dimension}:${row.dimensionKey}:${row.currency}`}
                      className="border-t"
                    >
                      <td className="py-1">
                        <div>{row.dimensionLabel}</div>
                        {row.platformName ? (
                          <div className="text-muted-foreground text-xs">{row.platformName}</div>
                        ) : null}
                        {row.shopName ? (
                          <div className="text-muted-foreground text-xs">{row.shopName}</div>
                        ) : null}
                        {row.skuCode ? (
                          <div className="text-muted-foreground text-xs">{row.skuCode}</div>
                        ) : null}
                        {row.spuCode ? (
                          <div className="text-muted-foreground text-xs">{row.spuCode}</div>
                        ) : null}
                      </td>
                      <td className="py-1">{row.currency}</td>
                      <td className="py-1">{reportMoney(row.spend, row.currency)}</td>
                      <td className="py-1">
                        {reportQuantity(row.impressions)} / {reportQuantity(row.clicks)} /{' '}
                        {reportQuantity(row.orders)}
                      </td>
                      <td className="py-1">{reportMoney(row.sales, row.currency)}</td>
                      <td className={`py-1 ${reportRatioTone(row.ctr)}`}>{reportRatio(row.ctr)}</td>
                      <td className={`py-1 ${reportRatioTone(row.cpc)}`}>
                        {reportRatio(row.cpc, false)}
                      </td>
                      <td className={`py-1 ${reportRatioTone(row.cvr)}`}>{reportRatio(row.cvr)}</td>
                      <td className={`py-1 ${reportRatioTone(row.acos)}`}>
                        {reportRatio(row.acos)}
                      </td>
                      <td className={`py-1 ${reportRatioTone(row.roas)}`}>
                        {reportRatio(row.roas, false)}
                      </td>
                      <td className="py-1">{reportMoney(row.totalSales, row.currency)}</td>
                      <td className={`py-1 ${reportRatioTone(row.tacos)}`}>
                        {reportRatio(row.tacos)}
                      </td>
                      <td className={`py-1 ${reportRatioTone(row.adSalesShare)}`}>
                        {reportRatio(row.adSalesShare)}
                      </td>
                      <td className="py-1">{reportMoney(row.netProfit, row.currency)}</td>
                      <td className="py-1">{reportMoney(row.adAfterNetProfit, row.currency)}</td>
                      <td className={`py-1 ${reportRatioTone(row.adProfitShare)}`}>
                        {reportRatio(row.adProfitShare)}
                      </td>
                      {/* 🔴 归因方式必须显示：分摊值当平台原值看会做错投放决策。 */}
                      <td className={`py-1 text-xs ${attributionModeTone(row.attributionMode)}`}>
                        <div>{attributionModeText(row.attributionMode)}</div>
                        <div className="text-muted-foreground">{row.attributionLabel}</div>
                        {row.profitNote ? (
                          <div className="text-amber-600">{row.profitNote}</div>
                        ) : null}
                      </td>
                      <td className="text-muted-foreground py-1 text-xs">
                        {row.metricFrom} ~ {row.metricTo}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination
              page={page.page}
              size={page.size}
              total={page.total}
              onChange={(next) => setQuery((prev) => ({ ...prev, page: next }))}
            />
          </>
        ) : null}
      </Section>

      {/* ================= 导出留痕 ================= */}
      <Section title="导出留痕">
        {!exports || exports.items.length === 0 ? (
          <p className="text-muted-foreground text-sm">还没有导出记录。</p>
        ) : (
          <div className="overflow-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">时刻</th>
                  <th className="py-1">商品维度</th>
                  <th className="py-1">筛选</th>
                  <th className="py-1">行数</th>
                  <th className="py-1">对着哪个水位</th>
                </tr>
              </thead>
              <tbody>
                {exports.items.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="py-1 text-xs">{reportTimestampText(row.createdAt)}</td>
                    <td className="py-1">{row.dimension}</td>
                    <td className="text-muted-foreground py-1 text-xs">
                      {row.shopCode ?? '全部店铺'} · {row.dateFrom} ~ {row.dateTo}
                    </td>
                    <td className="py-1">{row.rowCount}</td>
                    <td className="py-1 text-xs">{reportTimestampText(row.dataAsOf)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Section>

      {/* ================= 能力声明 ================= */}
      {capabilities ? (
        <Section title="能力声明（未交付段显式标注）">
          <ul className="flex flex-col gap-1 text-sm">
            {capabilities.dimensions.map((dim) => (
              <li key={dim.dimension} className="flex flex-col">
                <span className={dim.available ? 'text-emerald-600' : 'text-amber-600'}>
                  {dim.available ? '✓' : '✗'} {dim.description}
                </span>
                {!dim.available ? (
                  <span className="text-muted-foreground text-xs">
                    责任模块 {dim.ownerModule}：{dim.impact}
                  </span>
                ) : null}
              </li>
            ))}
          </ul>
          <p className="text-muted-foreground text-xs">{capabilities.upstreamNote}</p>
          <p className="text-muted-foreground text-xs">{capabilities.boundaryNote}</p>
        </Section>
      ) : null}

      {error ? <p className="text-destructive text-sm">{error}</p> : null}
    </main>
  );
}

function Pagination({
  page,
  size,
  total,
  onChange,
}: {
  page: number;
  size: number;
  total: number;
  onChange: (page: number) => void;
}) {
  const last = Math.max(Math.ceil(total / Math.max(size, 1)) - 1, 0);
  return (
    <div className="flex items-center gap-2 text-sm">
      <button
        className="rounded border px-2 py-1 disabled:opacity-40"
        disabled={page <= 0}
        onClick={() => onChange(page - 1)}
      >
        上一页
      </button>
      <span className="text-muted-foreground text-xs">
        第 {page + 1} / {last + 1} 页 · 共 {total} 个商品
      </span>
      <button
        className="rounded border px-2 py-1 disabled:opacity-40"
        disabled={page >= last}
        onClick={() => onChange(page + 1)}
      >
        下一页
      </button>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="flex flex-col gap-3 rounded border p-4">
      <h2 className="font-medium">{title}</h2>
      {children}
    </section>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-muted-foreground text-xs">{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1 text-sm">
      <span>{label}</span>
      {children}
    </label>
  );
}
