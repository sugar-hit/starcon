'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { errorText } from '../inventory/inventory-model';
import {
  cacheStatusText,
  cacheStatusTone,
  createAdGroupKeywordReportExport,
  evaluationOutcomeText,
  evaluationOutcomeTone,
  exportCsvUrl,
  fetchAdGroupKeywordReportCapabilities,
  fetchAdGroupKeywordReportExports,
  fetchAdGroupKeywordReportOptions,
  fetchAdGroupKeywordReportRows,
  metricDefinitionOf,
  metricHeader,
  reportDimensionLabel,
  reportEmptyText,
  reportMoney,
  reportQuantity,
  reportRatio,
  reportRatioTone,
  reportTimestampText,
  stillReportingText,
  unavailableMetricText,
  validateExportSelection,
  validateReportRange,
} from './ad-group-keyword-report-model';
import type {
  AdGroupKeywordReportCapabilities,
  AdGroupKeywordReportExportPage,
  AdGroupKeywordReportOptions,
  AdGroupKeywordReportPage,
  AdGroupKeywordReportQuery,
  AdGroupKeywordReportRow,
} from './ad-group-keyword-report-model';

/**
 * 14.3.1.2 广告组与词报表。
 *
 * 🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * 1. **组级指标的「没有」看得见**。组维度**只给结构事实**（组名 / 状态 / 默认竞价 /
 *    投放商品数 / 所属活动），指标列写「未接入（原因）」，**不给数** ——
 *    没有事实源却给一个 0、或拿活动级指标顶替组级指标，比不给更糟。
 * 2. **口径写在表头**。每一列的表头都带公式（`ACOS = Σ花费 / Σ销售额`），
 *    口径由服务端随报表一起返回 —— 前端不自己写第二份。
 * 3. **不可比 ≠ 0**。分母为 0 的比率渲染成「不可比」，0 是「可比但真的是零」。
 * 4. **「否定后仍有数据」不是 0**。它是**识别出来的事实**，单独一列 + 评估结论，
 *    绝不被渲染成「否定后花费 = 0」。
 * 5. **不同币种不合并**。服务端按币种拆开汇总，界面也按币种分块 ——
 *    前端一相加就造出一个服务端从没算过的假总数。
 * 6. **时间范围必填**。没有区间就是全表扫（蓝图加粗句：「不能实时全表扫」），
 *    所以页面不给「默认区间」这种省事选项。
 * 7. **缓存的过期时刻看得见**。页面上永远显示命中状态 + 过期时刻，
 *    不出现「缓存了但不知道什么时候过期」。
 */
export function AdGroupKeywordReportWorkspace() {
  const [capabilities, setCapabilities] = useState<AdGroupKeywordReportCapabilities | null>(null);
  const [options, setOptions] = useState<AdGroupKeywordReportOptions | null>(null);
  const [page, setPage] = useState<AdGroupKeywordReportPage | null>(null);
  const [exports, setExports] = useState<AdGroupKeywordReportExportPage | null>(null);

  const [query, setQuery] = useState<AdGroupKeywordReportQuery>({
    dimension: 'KEYWORD',
    granularity: 'TOTAL',
    page: 0,
    size: 50,
  });
  const [rangeErrors, setRangeErrors] = useState<string[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [exportError, setExportError] = useState('');
  const [exporting, setExporting] = useState(false);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const dimension = query.dimension ?? 'KEYWORD';

  const load = useCallback(async (next: AdGroupKeywordReportQuery) => {
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
        fetchAdGroupKeywordReportRows(next),
        // 导出留痕是补充证据，取不到不该把整张报表拖垮。
        fetchAdGroupKeywordReportExports(0, 20).catch(() => null),
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
          fetchAdGroupKeywordReportCapabilities(),
          fetchAdGroupKeywordReportOptions(),
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

  // 🔴 换维度 ⇒ 换维度 ⇒ 排序白名单也换 ⇒ 选中的行作废（主键在不同维度下不是一回事）。
  useEffect(() => {
    setSelected([]);
  }, [dimension]);

  function applyFilter(patch: AdGroupKeywordReportQuery) {
    setQuery((prev) => ({ ...prev, page: 0, ...patch }));
  }

  const metricDefinitions = page?.metricDefinitions ?? options?.metricDefinitions ?? [];
  const groupMetrics = metricDefinitionOf(metricDefinitions, 'group_metrics');
  const emptyText = reportEmptyText(page?.emptyReason ?? null, page?.emptyNote ?? '');

  /** 🔴 排序下拉项随维度走（维度不同，可排的列也不同）。 */
  const sortOptions = useMemo(() => {
    const byDimension = options?.sortsByDimension ?? {};
    return byDimension[dimension] ?? byDimension.KEYWORD ?? [];
  }, [options, dimension]);

  const selectedKeys = useMemo(
    () => selected.map((key) => key).filter((key) => key.length > 0),
    [selected],
  );

  function toggleRow(row: AdGroupKeywordReportRow) {
    setSelected((prev) =>
      prev.includes(row.dimensionKey)
        ? prev.filter((key) => key !== row.dimensionKey)
        : [...prev, row.dimensionKey],
    );
  }

  async function runExport() {
    setExportError('');
    const rangeCheck = validateReportRange(query.from ?? '', query.to ?? '');
    const selectionCheck = validateExportSelection(selectedKeys);
    const errors = [...rangeCheck.errors, ...selectionCheck.errors];
    if (errors.length > 0) {
      setExportError(errors.join('；'));
      return;
    }
    setExporting(true);
    try {
      await createAdGroupKeywordReportExport({
        dimension: query.dimension,
        granularity: query.granularity,
        sort: query.sort,
        from: query.from,
        to: query.to,
        platformId: query.platformId,
        shopCode: query.shopCode,
        internalAdType: query.internalAdType,
        status: query.status,
        externalCampaignId: query.externalCampaignId,
        keywordText: query.keywordText,
        selectedKeys,
      });
      const traces = await fetchAdGroupKeywordReportExports(0, 20).catch(() => null);
      setExports(traces);
    } catch (e) {
      setExportError(errorText(e));
    } finally {
      setExporting(false);
    }
  }

  return (
    <main className="flex flex-col gap-4 p-4">
      <header className="flex flex-col gap-1">
        <h1 className="text-xl font-medium">广告组与词报表</h1>
        <p className="text-muted-foreground text-sm">
          14.3.1.2 · 广告组与关键词维度报表：单组/单词效果、搜索词质量、否定效果评估，
          支持批量选中导出。关键词级数据量极大 ⇒ 查询分页 + 缓存。
        </p>
      </header>

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

      {/* ================= 口径说明 ================= */}
      {page ? (
        <Section title="口径说明">
          <p className="text-sm">{page.metricDefinitionNote}</p>
          <ul className="flex flex-col gap-1 text-xs">
            {metricDefinitions
              .filter((d) => ['acos', 'roas', 'cpc', 'group_metrics'].includes(d.metric))
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

      {/* ================= 否定效果评估的口径 ================= */}
      {page ? (
        <Section title="否定效果评估口径">
          <p className="text-sm">{page.negativeEffectNote}</p>
        </Section>
      ) : null}

      {/* ================= 数据截至 + 缓存 ================= */}
      {page ? (
        <Section title="数据截至与缓存">
          <p className="text-sm">数据截至：{reportTimestampText(page.dataAsOf)}</p>
          <p className="text-muted-foreground text-xs">{page.dataAsOfNote}</p>
          <p className={`text-sm ${cacheStatusTone(page.cache.status)}`}>
            缓存：{cacheStatusText(page.cache.status)}
            {page.cache.expiresAt ? ` · 过期时刻 ${reportTimestampText(page.cache.expiresAt)}` : ''}
          </p>
          <p className="text-muted-foreground text-xs">{page.cache.note}</p>
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
          <Field label="报表维度">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={dimension}
              onChange={(e) => applyFilter({ dimension: e.target.value })}
            >
              {options?.dimensions.map((d) => (
                <option key={d.value} value={d.value}>
                  {d.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="时间粒度">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={query.granularity ?? 'TOTAL'}
              onChange={(e) => applyFilter({ granularity: e.target.value })}
            >
              {options?.granularities.map((g) => (
                <option key={g.value} value={g.value}>
                  {g.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="排序">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={query.sort ?? sortOptions[0]?.value ?? ''}
              onChange={(e) => applyFilter({ sort: e.target.value })}
            >
              {sortOptions.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
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
          <Field label="外部活动 id">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={query.externalCampaignId ?? ''}
              onChange={(e) => applyFilter({ externalCampaignId: e.target.value })}
            />
          </Field>
          <Field label={dimension === 'GROUP' ? '组名包含' : '词 / 搜索词包含'}>
            <input
              className="rounded border px-2 py-1 text-sm"
              value={query.keywordText ?? ''}
              onChange={(e) => applyFilter({ keywordText: e.target.value })}
            />
          </Field>
        </div>
        {rangeErrors.length > 0 ? (
          <ul className="text-destructive text-xs">
            {rangeErrors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        ) : null}
        {options ? (
          <>
            <p className="text-muted-foreground text-xs">{options.rangeLimitNote}</p>
            <p className="text-muted-foreground text-xs">{options.statusNote}</p>
          </>
        ) : null}
      </Section>

      {/* ================= 批量选中导出 ================= */}
      <Section title="批量选中导出">
        <div className="flex flex-wrap items-center gap-2">
          <button
            className="rounded border px-3 py-1 text-sm disabled:opacity-40"
            disabled={exporting || rangeErrors.length > 0}
            onClick={() => void runExport()}
          >
            {exporting ? '正在登记…' : '导出选中（CSV）'}
          </button>
          <button
            className="rounded border px-3 py-1 text-sm disabled:opacity-40"
            disabled={rangeErrors.length > 0}
            onClick={() => setSelected([])}
          >
            清空选择
          </button>
          <p className="text-muted-foreground text-xs">
            已选中 {selectedKeys.length} 条（不选 = 导出当前筛选的全部）。 口径写进 CSV
            表头；每次导出都会留痕（含「选了哪几条」）。
          </p>
        </div>
        {exportError ? <p className="text-destructive text-xs">{exportError}</p> : null}
      </Section>

      {/* ================= 汇总（按币种拆开） ================= */}
      {page && page.summaries.length > 0 ? (
        <Section title="汇总（筛选后的全集，不受分页影响）">
          {page.summaries.map((sum) => (
            <div key={sum.currency || 'none'} className="flex flex-col gap-2">
              <p className="text-sm font-medium">币种 {sum.currency || '（无币种维度）'}</p>
              <dl className="grid grid-cols-2 gap-3 md:grid-cols-4">
                <Stat label="实体数" value={String(sum.entityCount)} />
                <Stat label="花费合计" value={reportMoney(sum.totalSpend, sum.currency)} />
                <Stat label="销售额合计" value={reportMoney(sum.totalSales, sum.currency)} />
                <Stat
                  label="曝光 / 点击 / 转化"
                  value={`${reportQuantity(sum.totalImpressions)} / ${reportQuantity(sum.totalClicks)} / ${reportQuantity(sum.totalConversions)}`}
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
              <p className="text-muted-foreground text-xs">{sum.metricDefinitionNote}</p>
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
      <Section title={`报表表 · ${reportDimensionLabel(dimension)}`}>
        {page?.note ? <p className="text-muted-foreground text-xs">{page.note}</p> : null}
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
                    <th className="py-1">选</th>
                    <th className="py-1">{reportDimensionLabel(dimension)}</th>
                    {dimension === 'GROUP' ? <th className="py-1">组结构</th> : null}
                    {dimension === 'KEYWORD' ? <th className="py-1">词</th> : null}
                    {dimension === 'SEARCH_TERM' ? <th className="py-1">搜索词</th> : null}
                    {dimension === 'NEGATIVE_EFFECT' ? <th className="py-1">否定记录</th> : null}
                    {dimension === 'NEGATIVE_EFFECT' ? <th className="py-1">生效日</th> : null}
                    {dimension === 'NEGATIVE_EFFECT' ? <th className="py-1">评估结论</th> : null}
                    {dimension === 'NEGATIVE_EFFECT' ? <th className="py-1">否定前</th> : null}
                    {dimension === 'NEGATIVE_EFFECT' ? <th className="py-1">否定后</th> : null}
                    {dimension === 'NEGATIVE_EFFECT' ? (
                      <th className="py-1">否定后仍有数据</th>
                    ) : null}
                    <th className="py-1">币种</th>
                    <th className="py-1">花费</th>
                    <th className="py-1">曝光 / 点击 / 转化</th>
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
                    {dimension === 'SEARCH_TERM' ? <th className="py-1">高曝光零转化</th> : null}
                    <th className="py-1">统计区间</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((row) => (
                    <tr
                      key={`${row.dimension}:${row.dimensionKey}:${row.currency}:${row.periodStart ?? ''}`}
                      className="border-t"
                    >
                      <td className="py-1">
                        <input
                          type="checkbox"
                          aria-label={`选中 ${row.dimensionLabel}`}
                          checked={selectedKeys.includes(row.dimensionKey)}
                          onChange={() => toggleRow(row)}
                        />
                      </td>
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
                      {dimension === 'GROUP' ? (
                        <td className="py-1 text-xs">
                          <div>状态 {row.groupStatus ?? '—'}</div>
                          <div>活动 {row.campaignName ?? '—'}</div>
                          <div>默认竞价 {row.defaultBid ?? '—'}</div>
                          <div>日预算 {row.dailyBudget ?? '继承活动预算'}</div>
                          <div>定向 {row.targetingType || '—'}</div>
                          <div>投放商品 {row.productCount ?? 0} 个</div>
                        </td>
                      ) : null}
                      {dimension === 'KEYWORD' ? (
                        <td className="py-1 text-xs">
                          <div>归一类型 {row.internalAdType ?? '—'}</div>
                          <div>原始类型 {row.platformAdType ?? '—'}</div>
                          <div>状态 {row.keywordStatus ?? '—'}</div>
                          <div>竞价 {row.bid ?? '—'}</div>
                        </td>
                      ) : null}
                      {dimension === 'SEARCH_TERM' ? (
                        <td className="py-1 text-xs">
                          <div>来源词 {row.sourceKeywordCount ?? 0} 个</div>
                          <div>来源活动 {row.sourceCampaignCount ?? 0} 个</div>
                          <div>样本词 {row.keywordText ?? '—'}</div>
                        </td>
                      ) : null}
                      {dimension === 'NEGATIVE_EFFECT' ? (
                        <td className="py-1 text-xs">
                          <div>类型 {row.negationType ?? '—'}</div>
                          <div>状态 {row.negationStatus ?? '—'}</div>
                          <div>所属词 {row.keywordText ?? '—'}</div>
                        </td>
                      ) : null}
                      {dimension === 'NEGATIVE_EFFECT' ? (
                        <td className="py-1 text-xs">
                          {row.effectiveDate ?? '无法评估'}
                          <div className="text-muted-foreground">
                            依据 {row.evidenceFrom ?? '—'} ~ {row.evidenceTo ?? '—'}
                          </div>
                        </td>
                      ) : null}
                      {dimension === 'NEGATIVE_EFFECT' ? (
                        <td
                          className={`py-1 text-xs ${evaluationOutcomeTone(row.evaluationOutcome)}`}
                        >
                          {evaluationOutcomeText(row.evaluationOutcome)}
                          {row.evaluationNote ? (
                            <div className="text-muted-foreground">{row.evaluationNote}</div>
                          ) : null}
                        </td>
                      ) : null}
                      {dimension === 'NEGATIVE_EFFECT' ? (
                        <td className="py-1 text-xs">
                          曝光 {reportQuantity(row.preImpressions)}
                          <br />
                          点击 {reportQuantity(row.preClicks)}
                          <br />
                          花费 {reportMoney(row.preSpend, row.currency)}
                        </td>
                      ) : null}
                      {dimension === 'NEGATIVE_EFFECT' ? (
                        <td className="py-1 text-xs">
                          曝光 {reportQuantity(row.postImpressions)}
                          <br />
                          点击 {reportQuantity(row.postClicks)}
                          <br />
                          花费 {reportMoney(row.postSpend, row.currency)}
                        </td>
                      ) : null}
                      {dimension === 'NEGATIVE_EFFECT' ? (
                        <td className="py-1 text-xs">
                          {stillReportingText(row.postStillReporting)}
                        </td>
                      ) : null}
                      <td className="py-1">{row.currency}</td>
                      {dimension === 'GROUP' ? (
                        // 🔴 组级指标**没有事实源** ⇒ 显式写「未接入」，不给数。
                        <td className="py-1 text-xs text-amber-600" colSpan={7}>
                          {unavailableMetricText(groupMetrics)}
                        </td>
                      ) : (
                        <>
                          <td className="py-1">{reportMoney(row.spend, row.currency)}</td>
                          <td className="py-1">
                            {reportQuantity(row.impressions)} / {reportQuantity(row.clicks)} /{' '}
                            {reportQuantity(row.conversions)}
                          </td>
                          <td className="py-1">{reportMoney(row.sales, row.currency)}</td>
                          <td className={`py-1 ${reportRatioTone(row.ctr)}`}>
                            {reportRatio(row.ctr)}
                          </td>
                          <td className={`py-1 ${reportRatioTone(row.cpc)}`}>
                            {reportRatio(row.cpc, false)}
                          </td>
                          <td className={`py-1 ${reportRatioTone(row.cvr)}`}>
                            {reportRatio(row.cvr)}
                          </td>
                          <td className={`py-1 ${reportRatioTone(row.acos)}`}>
                            {reportRatio(row.acos)}
                          </td>
                          <td className={`py-1 ${reportRatioTone(row.roas)}`}>
                            {reportRatio(row.roas, false)}
                          </td>
                        </>
                      )}
                      {dimension === 'SEARCH_TERM' ? (
                        <td className="py-1 text-xs">
                          {row.zeroConversionWaste ? (
                            <span className="text-destructive">是（浪费入口）</span>
                          ) : (
                            '否'
                          )}
                        </td>
                      ) : null}
                      <td className="text-muted-foreground py-1 text-xs">
                        {row.metricFrom} ~ {row.metricTo}
                        {row.activeDays !== null ? <div>有数据 {row.activeDays} 天</div> : null}
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
                  <th className="py-1">维度 / 粒度</th>
                  <th className="py-1">筛选</th>
                  <th className="py-1">选中条数</th>
                  <th className="py-1">行数</th>
                  <th className="py-1">对着哪个水位</th>
                  <th className="py-1">下载</th>
                </tr>
              </thead>
              <tbody>
                {exports.items.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="py-1 text-xs">{reportTimestampText(row.createdAt)}</td>
                    <td className="py-1 text-xs">
                      {row.dimension} / {row.granularity}
                    </td>
                    <td className="text-muted-foreground py-1 text-xs">
                      {row.shopCode ?? '全部店铺'} · {row.dateFrom} ~ {row.dateTo}
                    </td>
                    <td className="py-1 text-xs">{row.selection.length}</td>
                    <td className="py-1">{row.rowCount}</td>
                    <td className="py-1 text-xs">{reportTimestampText(row.dataAsOf)}</td>
                    <td className="py-1">
                      <a className="underline" href={exportCsvUrl(row.id)}>
                        CSV
                      </a>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Section>

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
