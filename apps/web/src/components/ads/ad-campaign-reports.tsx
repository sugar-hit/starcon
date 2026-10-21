'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { errorText } from '../inventory/inventory-model';
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import {
  fetchAdCampaignReportCapabilities,
  fetchAdCampaignReportExports,
  fetchAdCampaignReportOptions,
  fetchAdCampaignReportRows,
  fetchAdCampaignReportTrend,
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
} from './ad-campaign-report-model';
import type {
  AdCampaignReportCapabilities,
  AdCampaignReportExportPage,
  AdCampaignReportOptions,
  AdCampaignReportPage,
  AdCampaignReportQuery,
  AdCampaignReportTrend,
  AdCampaignReportTrendSeries,
} from './ad-campaign-report-model';

/**
 * 14.3.1.1 活动报表。
 *
 * 🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * 1. **口径写在表头**。蓝图的加粗句是「ACOS/ROAS/TACOS 三个口径要在表头写明定义，
 *    否则跨部门沟通会各说各话」。所以每一列的表头都带公式（`ACOS = Σ花费 / Σ广告销售额`），
 *    口径由服务端随报表一起返回 —— 前端不自己写第二份。
 * 2. **TACOS 的「没有」看得见**。总销售额口径（13.4）未接入 ⇒ TACOS 没有分母 ⇒
 *    表头写「未接入（原因）」，**不给数**。给一个 0 或拿广告销售额顶替，比不给更糟。
 * 3. **不可比 ≠ 0**。分母为 0 的比率渲染成「不可比」，因为压根算不出来；
 *    0 是「可比但真的是零」。两者渲染成同一个 0.00% 就是把「算不出来」说成「真的是零」。
 * 4. **不同币种不合并**。服务端按币种拆开汇总，界面也按币种分块 —— 前端一相加
 *    就造出一个服务端从没算过的假总数。
 * 5. **时间范围必填**。没有区间就是全表扫（蓝图加粗句：「不能实时全表扫」），
 *    所以页面不给「默认区间」这种省事选项。
 */
export function AdCampaignReportWorkspace() {
  const [capabilities, setCapabilities] = useState<AdCampaignReportCapabilities | null>(null);
  const [options, setOptions] = useState<AdCampaignReportOptions | null>(null);
  const [page, setPage] = useState<AdCampaignReportPage | null>(null);
  const [trend, setTrend] = useState<AdCampaignReportTrend | null>(null);
  const [exports, setExports] = useState<AdCampaignReportExportPage | null>(null);

  const [query, setQuery] = useState<AdCampaignReportQuery>({
    granularity: 'DAY',
    dimension: 'CAMPAIGN',
    sort: 'SPEND_DESC',
    page: 0,
    size: 50,
  });
  const [rangeErrors, setRangeErrors] = useState<string[]>([]);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = useCallback(async (next: AdCampaignReportQuery) => {
    const validation = validateReportRange(next.from ?? '', next.to ?? '');
    setRangeErrors(validation.errors);
    if (!validation.ok) {
      // 🔴 时间范围缺失时不发请求：没有区间的报表就是全表扫。
      setPage(null);
      setTrend(null);
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const [rows, points, traces] = await Promise.all([
        fetchAdCampaignReportRows(next),
        fetchAdCampaignReportTrend(next).catch(() => null),
        // 导出留痕是补充证据，取不到不该把整张报表拖垮。
        fetchAdCampaignReportExports(0, 20).catch(() => null),
      ]);
      setPage(rows);
      setTrend(points);
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
          fetchAdCampaignReportCapabilities(),
          fetchAdCampaignReportOptions(),
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

  function applyFilter(patch: AdCampaignReportQuery) {
    setQuery((prev) => ({ ...prev, page: 0, ...patch }));
  }

  const metricDefinitions = page?.metricDefinitions ?? options?.metricDefinitions ?? [];
  const tacos = metricDefinitionOf(metricDefinitions, 'tacos');
  const emptyText = reportEmptyText(page?.emptyReason ?? null, page?.emptyNote ?? '');

  /** 导出链接（与当前筛选完全一致）。 */
  const exportUrl = useMemo(
    () =>
      reportExportUrl({
        from: query.from,
        to: query.to,
        granularity: query.granularity,
        dimension: query.dimension,
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
        <h1 className="text-xl font-medium">活动报表</h1>
        <p className="text-muted-foreground text-sm">
          14.3.1.1 · 活动维度效果报表：花费、曝光、点击、CTR、CPC、订单、转化率、销售额、
          ACOS/ROAS，按日/周/月与多维对比，支持导出。
        </p>
      </header>

      {/* ================= 口径说明（表头之外的第二处口径锚点） ================= */}
      {page ? (
        <Section title="口径说明">
          <p className="text-sm">{page.metricDefinitionNote}</p>
          <ul className="flex flex-col gap-1 text-xs">
            {metricDefinitions
              .filter((d) => ['acos', 'roas', 'tacos'].includes(d.metric))
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

      {/* ================= 数据截至 ================= */}
      {page ? (
        <Section title="数据截至">
          <p className="text-sm">数据截至：{reportTimestampText(page.dataAsOf)}</p>
          <p className="text-muted-foreground text-xs">{page.dataAsOfNote}</p>
        </Section>
      ) : null}

      {/* ================= 筛选 ================= */}
      <Section title="时间范围与维度">
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
          <Field label="时间粒度">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={query.granularity ?? 'DAY'}
              onChange={(e) => applyFilter({ granularity: e.target.value })}
            >
              {options?.granularities.map((g) => (
                <option key={g.value} value={g.value}>
                  {g.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="分组维度">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={query.dimension ?? 'CAMPAIGN'}
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
            导出与当前筛选完全一致，口径写进 CSV 表头；每次导出都会留痕。
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
                <Stat label="分组数" value={String(sum.groupCount)} />
                <Stat label="花费合计" value={reportMoney(sum.totalSpend, sum.currency)} />
                <Stat label="销售额合计" value={reportMoney(sum.totalSales, sum.currency)} />
                <Stat
                  label="曝光 / 点击 / 订单"
                  value={`${reportQuantity(sum.totalImpressions)} / ${reportQuantity(sum.totalClicks)} / ${reportQuantity(sum.totalOrders)}`}
                />
                <Stat label="CTR" value={reportRatio(sum.ctr)} />
                <Stat label="CPC" value={reportRatio(sum.cpc, false)} />
                <Stat label="转化率" value={reportRatio(sum.cvr)} />
                <Stat label="ACOS" value={reportRatio(sum.acos)} />
                <Stat label="ROAS" value={reportRatio(sum.roas, false)} />
              </dl>
              {sum.metricFrom ? (
                <p className="text-muted-foreground text-xs">
                  实际统计区间：{sum.metricFrom} ~ {sum.metricTo}
                </p>
              ) : null}
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
      <Section title={`报表表 · ${reportDimensionLabel(query.dimension ?? 'CAMPAIGN')}`}>
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
                    <th className="py-1">分组</th>
                    <th className="py-1">币种</th>
                    <th className="py-1">花费</th>
                    <th className="py-1">曝光 / 点击 / 订单</th>
                    <th className="py-1">销售额</th>
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
                      {tacos
                        ? `${tacos.label}（${tacos.formula}）`
                        : 'TACOS（广告花费 / 总销售额）'}
                    </th>
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
                        {row.externalCampaignId ? (
                          <div className="text-muted-foreground text-xs">
                            {row.externalCampaignId}
                          </div>
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
                      {/* 🔴 TACOS 没有分母 ⇒ 显式写「未接入」，不给数。 */}
                      <td className="py-1 text-xs text-amber-600">
                        {unavailableMetricText(tacos)}
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

      {/* ================= 趋势图 ================= */}
      <Section title="趋势图">
        {loading ? (
          <p className="text-muted-foreground text-sm">正在加载趋势…</p>
        ) : !trend || trend.series.length === 0 ? (
          <p className="text-sm text-amber-600">
            {reportEmptyText(trend?.emptyReason ?? null, trend?.emptyNote ?? '') ||
              '这个区间没有可画的趋势。'}
          </p>
        ) : (
          trend.series.map((series) => (
            <div key={series.currency} className="flex flex-col gap-2">
              <p className="text-sm font-medium">币种 {series.currency}</p>
              <ResponsiveContainer width="100%" height={260}>
                <BarChart data={trendChartData(series)}>
                  <CartesianGrid strokeDasharray="3 3" className="stroke-border" />
                  <XAxis dataKey="bucket" fontSize={12} />
                  <YAxis fontSize={12} />
                  <Tooltip
                    formatter={(value: number | string) => [
                      typeof value === 'number' ? value.toFixed(2) : String(value),
                      '',
                    ]}
                  />
                  <Legend />
                  <Bar dataKey="spend" name="花费" fill="#6366f1" radius={[4, 4, 0, 0]} />
                  <Bar dataKey="sales" name="销售额" fill="#22c55e" radius={[4, 4, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          ))
        )}
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
                  <th className="py-1">粒度 / 维度</th>
                  <th className="py-1">筛选</th>
                  <th className="py-1">行数</th>
                  <th className="py-1">对着哪个水位</th>
                </tr>
              </thead>
              <tbody>
                {exports.items.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="py-1 text-xs">{reportTimestampText(row.createdAt)}</td>
                    <td className="py-1">
                      {row.granularity} / {row.dimension}
                    </td>
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

/** 趋势图的数据形状（只搬运服务端给的值，前端**不做任何算术**）。 */
function trendChartData(series: AdCampaignReportTrendSeries) {
  return series.points.map((point) => ({
    bucket: point.periodStart,
    spend: point.spend ?? 0,
    sales: point.sales ?? 0,
  }));
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
        第 {page + 1} / {last + 1} 页 · 共 {total} 组
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
