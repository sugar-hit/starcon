'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { errorText } from '../inventory/inventory-model';
import {
  adPromotionAnalysisExportCsvUrl,
  analysisCoverageText,
  analysisCoverageTone,
  analysisGrossProfitTone,
  analysisMarginAlertText,
  analysisMarginAlertTone,
  analysisMoney,
  analysisPercent,
  analysisPromotionTypeLabel,
  analysisQuantity,
  analysisRatio,
  analysisRunStatusLabel,
  analysisRunStatusTone,
  analysisSkippedEntries,
  analysisTimestampText,
  analysisVerdictLabel,
  analysisVerdictTone,
  analysisWindowText,
  createAdPromotionAnalysisExport,
  emptyAdAnalysisCostInput,
  fetchAdPromotionAnalysisCapabilities,
  fetchAdPromotionAnalysisCostSnapshots,
  fetchAdPromotionAnalysisExports,
  fetchAdPromotionAnalysisOptions,
  fetchAdPromotionAnalysisReport,
  fetchAdPromotionAnalysisRuns,
  runAdPromotionAnalysis,
  saveAdPromotionAnalysisCostSnapshot,
  validateAdAnalysisCostInput,
  validateAdAnalysisRange,
} from './ad-promotion-analysis-model';
import type {
  AdAnalysisCapabilities,
  AdAnalysisCostSnapshotPage,
  AdAnalysisExportPage,
  AdAnalysisOptions,
  AdAnalysisPage,
  AdAnalysisCostInputState,
  AdAnalysisRunRow,
  AdAnalysisExportRow,
} from './ad-promotion-analysis-model';

/**
 * 14.4.2.1 促销效果分析。
 *
 * 🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * 1. **销售额与毛利必须同屏**。蓝图的加粗句是「促销期间毛利可能大幅下滑，
 *    评估必须看毛利而不是销售额」⇒ 毛利不是附注，必须与销售额并排出现在同一张表里。
 * 2. **判定看毛利**。判定列的判据是**增量毛利**；另设「放量不赚」列标出
 *    销售额涨了但毛利率跌了的活动。
 * 3. **不可比不是 0**。没登记成本快照 ⇒ 毛利显示「不可比」；促销类型没有可折算的
 *    价格系数 ⇒ 补贴显示「不可比」。0 会被读成「毛利是 0」/「这次没花钱」。
 * 4. **跑一次分析的留痕看得见**。跳过的促销（没起止时间 / 没有适用商品）必须显示出来，
 *    否则界面会让人以为「全都算了」。
 * 5. **导出留痕**。导出是一次动作（谁、什么时候、什么筛选条件、导出了多少行），
 *    留痕可查，CSV 的表头里带着口径。
 */
export function AdPromotionAnalysisWorkspace() {
  const [capabilities, setCapabilities] = useState<AdAnalysisCapabilities | null>(null);
  const [options, setOptions] = useState<AdAnalysisOptions | null>(null);

  const [report, setReport] = useState<AdAnalysisPage | null>(null);
  const [runs, setRuns] = useState<AdAnalysisRunRow[]>([]);
  const [exports, setExports] = useState<AdAnalysisExportPage | null>(null);
  const [costSnapshots, setCostSnapshots] = useState<AdAnalysisCostSnapshotPage | null>(null);
  const [lastExport, setLastExport] = useState<AdAnalysisExportRow | null>(null);

  // ---- 筛选条件 ----
  const [dimension, setDimension] = useState('PROMOTION');
  const [sort, setSort] = useState('GROSS_PROFIT_DESC');
  const [promotionId, setPromotionId] = useState('');
  const [platformId, setPlatformId] = useState('');
  const [shopCode, setShopCode] = useState('');
  const [currency, setCurrency] = useState('');
  const [incrementVerdict, setIncrementVerdict] = useState('');
  const [marginAlertOnly, setMarginAlertOnly] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');
  const [page, setPage] = useState(0);
  const [size] = useState(50);

  // ---- 成本快照表单 ----
  const [costInput, setCostInput] = useState<AdAnalysisCostInputState>(emptyAdAnalysisCostInput);
  const [costErrors, setCostErrors] = useState<string[]>([]);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [page_, runRows, exportPage, costPage] = await Promise.all([
        fetchAdPromotionAnalysisReport({
          dimension,
          sort,
          promotionId: promotionId || undefined,
          platformId: platformId || undefined,
          shopCode: shopCode || undefined,
          currency: currency || undefined,
          incrementVerdict: incrementVerdict || undefined,
          marginAlertOnly: marginAlertOnly ? true : undefined,
          keyword: keyword || undefined,
          dateFrom: dateFrom || undefined,
          dateTo: dateTo || undefined,
          page,
          size,
        }),
        fetchAdPromotionAnalysisRuns(0, 10),
        fetchAdPromotionAnalysisExports(0, 10),
        fetchAdPromotionAnalysisCostSnapshots({ page: 0, size: 20 }),
      ]);
      setReport(page_);
      setRuns(runRows);
      setExports(exportPage);
      setCostSnapshots(costPage);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [
    dimension,
    sort,
    promotionId,
    platformId,
    shopCode,
    currency,
    incrementVerdict,
    marginAlertOnly,
    keyword,
    dateFrom,
    dateTo,
    page,
    size,
  ]);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const [caps, opts] = await Promise.all([
          fetchAdPromotionAnalysisCapabilities(),
          fetchAdPromotionAnalysisOptions(),
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
    void load();
  }, [load]);

  const unavailableDimensions = useMemo(
    () => (capabilities?.dimensions ?? []).filter((d) => !d.available),
    [capabilities],
  );

  /** 跑一次分析（写动作）。 */
  async function onRun() {
    setBusy(true);
    setError('');
    try {
      await runAdPromotionAnalysis(promotionId || undefined);
      setPage(0);
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  /** 登记成本与售价快照（catalog 的成本复制进本服务的唯一通道）。 */
  async function onSaveCost() {
    const validation = validateAdAnalysisCostInput(costInput);
    setCostErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      await saveAdPromotionAnalysisCostSnapshot({
        promotionId: costInput.promotionId.trim(),
        listingId: costInput.listingId.trim(),
        unitCost: Number(costInput.unitCost),
        basePrice: costInput.basePrice.trim() === '' ? undefined : Number(costInput.basePrice),
        currency: costInput.currency.trim() || undefined,
        sourceRef: costInput.sourceRef.trim() || undefined,
        note: costInput.note.trim() || undefined,
      });
      setCostInput(emptyAdAnalysisCostInput());
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  /** 导出（登记留痕 + 给出 CSV 下载链接）。 */
  async function onExport() {
    setBusy(true);
    setError('');
    try {
      const row = await createAdPromotionAnalysisExport({
        dimension,
        promotionId: promotionId || undefined,
        platformId: platformId || undefined,
        shopCode: shopCode || undefined,
        currency: currency || undefined,
        incrementVerdict: incrementVerdict || undefined,
        dateFrom: dateFrom || undefined,
        dateTo: dateTo || undefined,
      });
      setLastExport(row);
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  function onReset() {
    setDimension('PROMOTION');
    setSort('GROSS_PROFIT_DESC');
    setPromotionId('');
    setPlatformId('');
    setShopCode('');
    setCurrency('');
    setIncrementVerdict('');
    setMarginAlertOnly(false);
    setKeyword('');
    setDateFrom('');
    setDateTo('');
    setPage(0);
  }

  return (
    <main className="flex flex-col gap-6 p-6">
      <header className="flex flex-col gap-2">
        <h1 className="text-2xl font-semibold">促销效果分析</h1>
        <p className="text-muted-foreground max-w-4xl text-sm">
          促销期间的<strong>销量 / 销售额 / 毛利变化</strong>、<strong>增量判断</strong>、
          <strong>补贴成本</strong>与<strong>ROI</strong>，并与<strong>活动前的基期</strong>对比。
          <strong>促销期间毛利可能大幅下滑</strong>，所以本表的判定看的是
          <strong>毛利</strong>而不是销售额 —— 销售额与毛利在下面同一张表里并排显示。
        </p>
      </header>

      {error ? <p className="text-destructive text-sm">{error}</p> : null}

      {/* ================= 能力声明 ================= */}
      <Section title="能力声明">
        {capabilities ? (
          <>
            {unavailableDimensions.length > 0 ? (
              <div className="rounded border border-amber-500/40 bg-amber-500/5 p-3 text-sm">
                <strong>🔴 以下维度当前拿不到</strong>（本模块不会用假数字顶替）：
                <ul className="mt-1 list-disc pl-5">
                  {unavailableDimensions.map((d) => (
                    <li key={d.dimension}>
                      <strong>{d.dimension}</strong>（{d.description}）—— 责任模块：
                      <strong>{d.ownerModule}</strong>；影响：{d.impact}
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
            <p className="text-muted-foreground text-sm">{capabilities.incrementNote}</p>
            <p className="text-muted-foreground text-sm">{capabilities.marginNote}</p>
            <p className="text-muted-foreground text-sm">{capabilities.baselineNote}</p>
            <p className="text-muted-foreground text-sm">{capabilities.subsidyNote}</p>
            <p className="text-muted-foreground text-sm">{capabilities.roiNote}</p>
            <p className="text-muted-foreground text-sm">{capabilities.boundaryNote}</p>
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">维度</th>
                  <th className="py-1">是否已交付</th>
                  <th className="py-1">来源</th>
                  <th className="py-1">责任模块</th>
                  <th className="py-1">不交付的影响</th>
                </tr>
              </thead>
              <tbody>
                {capabilities.dimensions.map((d) => (
                  <tr key={d.dimension} className="border-t">
                    <td className="py-1">{d.dimension}</td>
                    <td className={`py-1 ${d.available ? 'text-emerald-600' : 'text-amber-600'}`}>
                      {d.available ? '已交付' : '未交付'}
                    </td>
                    <td className="py-1 text-xs">{d.source || '—'}</td>
                    <td className="py-1 text-xs">{d.ownerModule || '—'}</td>
                    <td className="py-1 text-xs">{d.impact || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        ) : (
          <p className="text-muted-foreground text-sm">正在加载能力声明…</p>
        )}
      </Section>

      {/* ================= 筛选与操作 ================= */}
      <Section title="筛选与操作">
        <div className="grid gap-3 md:grid-cols-4">
          <Field label="维度">
            <select
              className="rounded border px-2 py-1"
              value={dimension}
              onChange={(e) => {
                setDimension(e.target.value);
                setPage(0);
              }}
            >
              {(options?.dimensions ?? []).map((d) => (
                <option key={d.code} value={d.code}>
                  {d.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="排序">
            <select
              className="rounded border px-2 py-1"
              value={sort}
              onChange={(e) => {
                setSort(e.target.value);
                setPage(0);
              }}
            >
              {(options?.sorts ?? []).map((s) => (
                <option key={s.code} value={s.code}>
                  {s.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="促销">
            <select
              className="rounded border px-2 py-1"
              value={promotionId}
              onChange={(e) => {
                setPromotionId(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部促销</option>
              {(options?.promotions ?? []).map((p) => (
                <option key={p.id} value={p.id}>
                  {p.promotionCode} / {p.promotionName}
                </option>
              ))}
            </select>
          </Field>
          <Field label="平台">
            <select
              className="rounded border px-2 py-1"
              value={platformId}
              onChange={(e) => {
                setPlatformId(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部平台</option>
              {(options?.platforms ?? []).map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </Field>
          <Field label="店铺">
            <select
              className="rounded border px-2 py-1"
              value={shopCode}
              onChange={(e) => {
                setShopCode(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部店铺</option>
              {(options?.shops ?? []).map((s) => (
                <option key={s.code} value={s.code}>
                  {s.name}
                </option>
              ))}
            </select>
          </Field>
          <Field label="币种">
            <select
              className="rounded border px-2 py-1"
              value={currency}
              onChange={(e) => {
                setCurrency(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部币种</option>
              {(options?.currencies ?? []).map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </select>
          </Field>
          <Field label="增量判定（判据 = 增量毛利）">
            <select
              className="rounded border px-2 py-1"
              value={incrementVerdict}
              onChange={(e) => {
                setIncrementVerdict(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部判定</option>
              {(options?.incrementVerdicts ?? []).map((v) => (
                <option key={v.code} value={v.code}>
                  {v.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="关键字（促销编码 / 名称 / 商品标题）">
            <input
              className="rounded border px-2 py-1"
              value={keyword}
              onChange={(e) => {
                setKeyword(e.target.value);
                setPage(0);
              }}
            />
          </Field>
          <Field label="起始日">
            <input
              type="date"
              className="rounded border px-2 py-1"
              value={dateFrom}
              onChange={(e) => {
                setDateFrom(e.target.value);
                setPage(0);
              }}
            />
          </Field>
          <Field label="截止日">
            <input
              type="date"
              className="rounded border px-2 py-1"
              value={dateTo}
              onChange={(e) => {
                setDateTo(e.target.value);
                setPage(0);
              }}
            />
          </Field>
          <Field label="只看放量不赚（销售额涨了但毛利率跌了）">
            <label className="flex items-center gap-2 py-1">
              <input
                type="checkbox"
                checked={marginAlertOnly}
                onChange={(e) => {
                  setMarginAlertOnly(e.target.checked);
                  setPage(0);
                }}
              />
              <span className="text-xs">放量不赚</span>
            </label>
          </Field>
        </div>

        <div className="flex flex-wrap gap-2">
          <button
            className="rounded border px-2 py-1"
            disabled={busy}
            onClick={() => {
              const validation = validateAdAnalysisRange(dateFrom, dateTo);
              if (!validation.ok) {
                setError(validation.errors.join('；'));
                return;
              }
              setError('');
              setPage(0);
              void load();
            }}
          >
            查询
          </button>
          <button className="rounded border px-2 py-1" disabled={busy} onClick={onReset}>
            重置筛选
          </button>
          <button className="rounded border px-2 py-1" disabled={busy} onClick={() => void onRun()}>
            跑一次分析
          </button>
          <button className="rounded border px-2 py-1" disabled={busy} onClick={() => void onExport()}>
            导出（登记留痕）
          </button>
        </div>
        <p className="text-muted-foreground text-xs">
          「跑一次分析」按站点当地自然日算促销期与基期，把基期快照与效果结果落库；
          没有起止时间或没有适用商品的促销会被**跳过并记录**，不会静默消失。
        </p>
      </Section>

      {/* ================= 效果报表 ================= */}
      <Section title="效果报表（销售额与毛利并排）">
        {loading && !report ? (
          <p className="text-muted-foreground text-sm">正在加载效果报表…</p>
        ) : report && report.items.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            还没有可分析的结果。先「跑一次分析」把促销期与基期算出来
            （平台回报口径的每日销量 / 销售额来自 listing_performance_snapshot，
            毛利还需要先登记成本快照）。
          </p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">促销 / 商品</th>
                <th className="py-1">促销期窗口</th>
                <th className="py-1">覆盖度</th>
                <th className="py-1">促销期销量</th>
                <th className="py-1">促销期销售额</th>
                <th className="py-1">促销期毛利</th>
                <th className="py-1">促销期毛利率</th>
                <th className="py-1">基期销售额</th>
                <th className="py-1">基期毛利</th>
                <th className="py-1">增量销售额</th>
                <th className="py-1">增量毛利</th>
                <th className="py-1">单件让利</th>
                <th className="py-1">补贴成本</th>
                <th className="py-1">ROI</th>
                <th className="py-1">增量判定（判据 = 增量毛利）</th>
                <th className="py-1">放量不赚</th>
                <th className="py-1">可比商品数</th>
                <th className="py-1">数据截至</th>
              </tr>
            </thead>
            <tbody>
              {report?.items.map((row) => (
                <tr key={`${row.promotionId}-${row.listingId ?? 'agg'}`} className="border-t">
                  <td className="py-1">
                    {row.promotionCode} / {row.promotionName}
                    <br />
                    <span className="text-muted-foreground text-xs">
                      {analysisPromotionTypeLabel(row.promotionType)} · {row.platformCode} ·{' '}
                      {row.shopCode} · {row.currency}
                    </span>
                    {row.listingTitle ? (
                      <>
                        <br />
                        <span className="text-xs">{row.listingTitle}</span>
                      </>
                    ) : null}
                  </td>
                  <td className="py-1 text-xs">
                    {analysisWindowText(row.periodFromAt, row.periodToAt)}
                    <br />
                    <span className="text-muted-foreground">
                      基期 {analysisWindowText(row.baselineFromAt, row.baselineToAt)}
                    </span>
                  </td>
                  <td
                    className={`py-1 text-xs ${analysisCoverageTone(row.periodCoveredDays, row.periodDays)}`}
                  >
                    {analysisCoverageText(row.periodCoveredDays, row.periodDays)}
                  </td>
                  <td className="py-1">{analysisQuantity(row.promoUnits)}</td>
                  <td className="py-1">{analysisMoney(row.promoSales, row.currency)}</td>
                  <td className={`py-1 ${analysisGrossProfitTone(row.promoGrossProfit)}`}>
                    {analysisMoney(row.promoGrossProfit, row.currency)}
                  </td>
                  <td className="py-1">{analysisPercent(row.promoGrossMarginRate)}</td>
                  <td className="py-1">{analysisMoney(row.baselineSales, row.currency)}</td>
                  <td className={`py-1 ${analysisGrossProfitTone(row.baselineGrossProfit)}`}>
                    {analysisMoney(row.baselineGrossProfit, row.currency)}
                  </td>
                  <td className="py-1">{analysisMoney(row.incrementSales, row.currency)}</td>
                  <td className={`py-1 ${analysisGrossProfitTone(row.incrementGrossProfit)}`}>
                    {analysisMoney(row.incrementGrossProfit, row.currency)}
                  </td>
                  <td className="py-1">{analysisMoney(row.subsidyUnit, row.currency)}</td>
                  <td className="py-1">{analysisMoney(row.subsidyCost, row.currency)}</td>
                  <td className="py-1">{analysisRatio(row.roiValue)}</td>
                  <td className={`py-1 ${analysisVerdictTone(row.incrementVerdict)}`}>
                    {analysisVerdictLabel(row.incrementVerdict)}
                  </td>
                  <td className={`py-1 text-xs ${analysisMarginAlertTone(row.marginAlert)}`}>
                    {analysisMarginAlertText(row.marginAlert)}
                  </td>
                  <td className="py-1 text-xs">
                    毛利可比 {row.grossProfitProductCount} / 全部可比 {row.comparableProductCount} / 共{' '}
                    {row.productCount}
                  </td>
                  <td className="py-1 text-xs">{analysisTimestampText(row.dataAsOf)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {report ? (
          <>
            <p className="text-muted-foreground text-xs">
              共 {report.total} 条（本页 {report.items.length} 条，第 {report.page + 1} 页）
            </p>
            <p className="text-muted-foreground text-xs">{report.metricDefinitionNote}</p>
            <div className="flex gap-2">
              <button
                className="rounded border px-2 py-1"
                disabled={busy || report.page <= 0}
                onClick={() => setPage(report.page - 1)}
              >
                上一页
              </button>
              <button
                className="rounded border px-2 py-1"
                disabled={busy || (report.page + 1) * report.size >= report.total}
                onClick={() => setPage(report.page + 1)}
              >
                下一页
              </button>
            </div>
          </>
        ) : null}
      </Section>

      {/* ================= 分析运行留痕 ================= */}
      <Section title="分析运行留痕（跳过的促销不许静默消失）">
        {runs.length === 0 ? (
          <p className="text-muted-foreground text-sm">还没有跑过分析。</p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">时刻</th>
                <th className="py-1">状态</th>
                <th className="py-1">已算 / 跳过</th>
                <th className="py-1">跳过明细</th>
                <th className="py-1">数据截至</th>
              </tr>
            </thead>
            <tbody>
              {runs.map((run) => (
                <tr key={run.id} className="border-t">
                  <td className="py-1 text-xs">{analysisTimestampText(run.createdAt)}</td>
                  <td className={`py-1 ${analysisRunStatusTone(run.status)}`}>
                    {analysisRunStatusLabel(run.status)}
                  </td>
                  <td className="py-1 text-xs">
                    {run.analyzedCount} / {run.skippedCount}
                  </td>
                  <td className="py-1 text-xs">
                    {analysisSkippedEntries(run.skipped).length === 0 ? (
                      '—'
                    ) : (
                      <ul className="list-disc pl-4">
                        {analysisSkippedEntries(run.skipped).map((entry, index) => (
                          <li key={`${entry.promotionCode}-${index}`}>
                            {entry.promotionCode || '（未命名促销）'}：{entry.reason}
                          </li>
                        ))}
                      </ul>
                    )}
                  </td>
                  <td className="py-1 text-xs">{analysisTimestampText(run.dataAsOf)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>

      {/* ================= 成本与售价快照 ================= */}
      <Section title="成本与售价快照（catalog 的成本复制进本服务，channel 不跨服务读）">
        <div className="grid gap-3 md:grid-cols-3">
          <Field label="促销 id">
            <input
              className="rounded border px-2 py-1"
              value={costInput.promotionId}
              onChange={(e) => setCostInput({ ...costInput, promotionId: e.target.value })}
            />
          </Field>
          <Field label="商品 id（listing id）">
            <input
              className="rounded border px-2 py-1"
              value={costInput.listingId}
              onChange={(e) => setCostInput({ ...costInput, listingId: e.target.value })}
            />
          </Field>
          <Field label="单位成本">
            <input
              className="rounded border px-2 py-1"
              value={costInput.unitCost}
              onChange={(e) => setCostInput({ ...costInput, unitCost: e.target.value })}
            />
          </Field>
          <Field label="基准价（留空 = 没登记 ⇒ 百分比促销的补贴不可比）">
            <input
              className="rounded border px-2 py-1"
              value={costInput.basePrice}
              onChange={(e) => setCostInput({ ...costInput, basePrice: e.target.value })}
            />
          </Field>
          <Field label="币种（留空 = 取促销的币种）">
            <input
              className="rounded border px-2 py-1"
              value={costInput.currency}
              onChange={(e) => setCostInput({ ...costInput, currency: e.target.value })}
            />
          </Field>
          <Field label="来源说明（catalog 的哪个口径 / 哪条记录）">
            <input
              className="rounded border px-2 py-1"
              value={costInput.sourceRef}
              onChange={(e) => setCostInput({ ...costInput, sourceRef: e.target.value })}
            />
          </Field>
        </div>
        {costErrors.length > 0 ? (
          <ul className="text-destructive text-sm">
            {costErrors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        ) : null}
        <button
          className="rounded border px-2 py-1"
          disabled={busy}
          onClick={() => void onSaveCost()}
        >
          登记成本快照
        </button>
        <p className="text-muted-foreground text-xs">
          没登记成本 ⇒ 毛利**不可比**（显示「不可比」，不是 0）；缺基准价 ⇒ 百分比促销的补贴不可比。
        </p>
        {costSnapshots ? (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">促销 / 商品</th>
                <th className="py-1">单位成本</th>
                <th className="py-1">基准价</th>
                <th className="py-1">来源</th>
                <th className="py-1">快照时刻</th>
                <th className="py-1">版本</th>
              </tr>
            </thead>
            <tbody>
              {costSnapshots.items.map((row) => (
                <tr key={row.id} className="border-t">
                  <td className="py-1 text-xs">
                    {row.promotionCode} / {row.listingTitle}
                  </td>
                  <td className="py-1">{analysisMoney(row.unitCost, row.currency)}</td>
                  <td className="py-1">{analysisMoney(row.basePrice, row.currency)}</td>
                  <td className="py-1 text-xs">{row.sourceRef || '—'}</td>
                  <td className="py-1 text-xs">{analysisTimestampText(row.capturedAt)}</td>
                  <td className="py-1 text-xs">{row.version}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
      </Section>

      {/* ================= 导出留痕 ================= */}
      <Section title="导出留痕">
        {lastExport ? (
          <p className="text-sm">
            最近一次导出：{lastExport.dimension} 维度，共 {lastExport.rowCount} 行，
            {analysisTimestampText(lastExport.createdAt)}。{' '}
            <a
              className="underline"
              href={adPromotionAnalysisExportCsvUrl(lastExport.id)}
              data-testid="ad-promotion-analysis-export-csv"
            >
              下载 CSV
            </a>
          </p>
        ) : null}
        {exports && exports.items.length > 0 ? (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">时刻</th>
                <th className="py-1">维度</th>
                <th className="py-1">促销</th>
                <th className="py-1">行数</th>
                <th className="py-1">数据截至</th>
                <th className="py-1">下载</th>
              </tr>
            </thead>
            <tbody>
              {exports.items.map((row) => (
                <tr key={row.id} className="border-t">
                  <td className="py-1 text-xs">{analysisTimestampText(row.createdAt)}</td>
                  <td className="py-1 text-xs">
                    {row.dimension === 'PROMOTION' ? '活动维度' : '商品维度'}
                  </td>
                  <td className="py-1 text-xs">{row.promotionId ?? '全部'}</td>
                  <td className="py-1 text-xs">{row.rowCount}</td>
                  <td className="py-1 text-xs">{analysisTimestampText(row.dataAsOf)}</td>
                  <td className="py-1 text-xs">
                    <a className="underline" href={adPromotionAnalysisExportCsvUrl(row.id)}>
                      CSV
                    </a>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <p className="text-muted-foreground text-sm">还没有导出记录。</p>
        )}
      </Section>
    </main>
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

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1 text-sm">
      <span className="text-muted-foreground text-xs">{label}</span>
      {children}
    </label>
  );
}
