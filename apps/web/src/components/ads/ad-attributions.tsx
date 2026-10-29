'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { errorText } from '../inventory/inventory-model';
import {
  attributionEmptyText,
  attributionExportUrl,
  attributionMetricHeader,
  attributionModelText,
  attributionMoney,
  attributionQuantity,
  attributionRatio,
  attributionRatioTone,
  attributionStatusText,
  attributionStatusTone,
  attributionTimestampText,
  fetchAdAttributionCapabilities,
  fetchAdAttributionCurrentRule,
  fetchAdAttributionExports,
  fetchAdAttributionOptions,
  fetchAdAttributionRecords,
  fetchAdAttributionReport,
  fetchAdAttributionRules,
  importAdAttribution,
  parseImportPayload,
  runAdAttribution,
  saveAdAttributionRule,
  validateAttributionRange,
  validateAttributionRule,
} from './ad-attribution-model';
import type {
  AdAttributionCapabilities,
  AdAttributionExportPage,
  AdAttributionImportResult,
  AdAttributionOptions,
  AdAttributionQuery,
  AdAttributionRecordPage,
  AdAttributionReportPage,
  AdAttributionRule,
  AdAttributionRunResult,
} from './ad-attribution-model';

/**
 * 14.3.2.1 归因分析。
 *
 * 🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * 1. **归因规则必须固定并公示**。规则是租户级配置，**一个租户同一时刻只有一套生效口径**；
 *    改规则 = **版本 +1**，而已算出的归因结果**不重算、不改写** —— 每条归因结果自带规则快照，
 *    所以历史报表仍然能被「当时那套口径」解释。服务端把**规则原文**随响应返回，页面原样展示。
 * 2. **归因结果与 14.3.1.3 的分摊结果不是同一个数**。14.3.1.3 答「这个商品上分摊了多少广告费」，
 *    本页答「这些订单是广告带来的吗」。两页的「广告后净利」不相等是**正常的**。
 * 3. **归不上的钱看得见**。归不到 SKU、窗口内没有合格点击、平台没标记归因点击 ——
 *    这些金额在汇总里单列，**不摊给任何 SKU，也不摊成 0**。
 * 4. **不可比 ≠ 0**。比率列的 `null` 渲染成「不可比」，`0` 才是「可比但真的是零」。
 * 5. **增量效果是推导值，不是平台原值**。页面标「估算」，并把口径与边界（**不是因果增量**）一起显示。
 * 6. **不同币种不合并**（服务端没有汇率源）⇒ 汇总按币种分块，界面不做跨币种相加。
 *
 * 🔴 金额与数量在 JS 侧**不做任何前端算术** —— TACOS / ACOS / 广告销售占比 / 广告后净利 /
 * 增量效果估计与汇总都是服务端 `BigDecimal` 的事。
 */
export function AdAttributionWorkspace() {
  const [capabilities, setCapabilities] = useState<AdAttributionCapabilities | null>(null);
  const [options, setOptions] = useState<AdAttributionOptions | null>(null);
  const [rules, setRules] = useState<AdAttributionRule[]>([]);
  const [currentRule, setCurrentRule] = useState<AdAttributionRule | null>(null);
  const [records, setRecords] = useState<AdAttributionRecordPage | null>(null);
  const [report, setReport] = useState<AdAttributionReportPage | null>(null);
  const [exports, setExports] = useState<AdAttributionExportPage | null>(null);
  const [runResult, setRunResult] = useState<AdAttributionRunResult | null>(null);
  const [importResult, setImportResult] = useState<AdAttributionImportResult | null>(null);

  const [tab, setTab] = useState<'records' | 'report'>('records');
  const [query, setQuery] = useState<AdAttributionQuery>({
    sort: 'ORDER_AT_DESC',
    page: 0,
    size: 50,
  });
  const [rangeErrors, setRangeErrors] = useState<string[]>([]);

  // ---- 规则编辑表单 ----
  const [ruleCode, setRuleCode] = useState('DEFAULT');
  const [ruleModel, setRuleModel] = useState('LAST_CLICK');
  const [ruleWindow, setRuleWindow] = useState(30);
  const [ruleNote, setRuleNote] = useState('');
  const [ruleErrors, setRuleErrors] = useState<string[]>([]);

  // ---- 导入与归因计算 ----
  const [importPayload, setImportPayload] = useState('');
  const [importErrors, setImportErrors] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const loadRules = useCallback(async () => {
    try {
      const [page, current] = await Promise.all([
        fetchAdAttributionRules(undefined, undefined, 0, 50),
        fetchAdAttributionCurrentRule(),
      ]);
      setRules(page.items);
      setCurrentRule(current);
      if (current) {
        setRuleCode(current.ruleCode);
        setRuleModel(current.attributionModel);
        setRuleWindow(current.attributionWindowDays);
      }
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  const load = useCallback(async (next: AdAttributionQuery) => {
    const validation = validateAttributionRange(next.from ?? '', next.to ?? '');
    setRangeErrors(validation.errors);
    if (!validation.ok) {
      // 🔴 时间范围缺失时不发请求：没有区间的查询就是全表扫。
      setRecords(null);
      setReport(null);
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const [detail, profit, traces] = await Promise.all([
        fetchAdAttributionRecords(next),
        fetchAdAttributionReport(next),
        // 导出留痕是补充证据，取不到不该把整页拖垮。
        fetchAdAttributionExports(0, 20).catch(() => null),
      ]);
      setRecords(detail);
      setReport(profit);
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
          fetchAdAttributionCapabilities(),
          fetchAdAttributionOptions(),
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
    void loadRules();
  }, [loadRules]);

  useEffect(() => {
    void load(query);
  }, [load, query]);

  function applyFilter(patch: AdAttributionQuery) {
    setQuery((prev) => ({ ...prev, page: 0, ...patch }));
  }

  /** 导出链接（与当前筛选完全一致）。 */
  const exportUrl = useMemo(
    () =>
      attributionExportUrl({
        ruleId: query.ruleId,
        ruleVersion: query.ruleVersion,
        from: query.from,
        to: query.to,
        shopCode: query.shopCode,
        campaignId: query.campaignId,
        skuId: query.skuId,
        currency: query.currency,
        sort: query.sort,
      }),
    [query],
  );

  async function onSaveRule() {
    const validation = validateAttributionRule({
      ruleCode,
      attributionModel: ruleModel,
      attributionWindowDays: ruleWindow,
    });
    setRuleErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      await saveAdAttributionRule({
        id: currentRule?.id,
        ruleCode,
        attributionModel: ruleModel,
        attributionWindowDays: ruleWindow,
        note: ruleNote,
        activate: true,
      });
      await loadRules();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onImport() {
    const parsed = parseImportPayload(importPayload);
    setImportErrors(parsed.errors);
    if (!parsed.ok || !parsed.request) return;
    setBusy(true);
    setError('');
    try {
      setImportResult(await importAdAttribution(parsed.request));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onRun(recompute: boolean) {
    const validation = validateAttributionRange(query.from ?? '', query.to ?? '');
    setRangeErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      const result = await runAdAttribution({
        ruleId: query.ruleId ?? currentRule?.id,
        from: query.from as string,
        to: query.to as string,
        recompute,
      });
      setRunResult(result);
      await load(query);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  const recordsEmpty = attributionEmptyText(records?.emptyReason ?? null, records?.emptyNote ?? '');
  const reportEmpty = attributionEmptyText(report?.emptyReason ?? null, report?.emptyNote ?? '');

  return (
    <main className="flex flex-col gap-4 p-4">
      <header className="flex flex-col gap-1">
        <h1 className="text-xl font-medium">归因分析</h1>
        <p className="text-muted-foreground text-sm">
          14.3.2.1 · 把广告花费归因到 SKU / 订单（末次点击 /
          平台归因窗口口径），计算广告后净利、TACOS 与增量效果估计。归因规则必须固定并公示 ——
          规则变了历史报表不可比，这是广告与财务对账最容易吵架的点。
        </p>
      </header>

      {/* ================= 归因规则（固定并公示） ================= */}
      <Section title="归因规则（一个租户同时只有一套生效口径）">
        {currentRule ? (
          <div className="flex flex-col gap-1 text-sm">
            <p>
              当前生效：<span className="font-medium">{currentRule.ruleCode}</span> ·{' '}
              {attributionModelText(currentRule.attributionModel)} · 归因窗口{' '}
              {currentRule.attributionWindowDays} 天 · 第 {currentRule.version} 版
            </p>
            <p className="text-muted-foreground text-xs">
              🔴 改规则 = 版本 +1；已算出的归因结果不重算、不改写（每条结果自带规则快照）。
            </p>
          </div>
        ) : (
          <p className="text-sm text-amber-600">
            🔴 本租户当前没有生效的归因规则 —— 先启用一套口径，再导入数据并跑归因计算。
          </p>
        )}

        <div className="mt-2 grid gap-2 md:grid-cols-4">
          <label className="flex flex-col gap-1 text-xs">
            规则编码
            <input
              className="rounded border px-2 py-1 text-sm"
              value={ruleCode}
              onChange={(e) => setRuleCode(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1 text-xs">
            归因模型
            <select
              className="rounded border px-2 py-1 text-sm"
              value={ruleModel}
              onChange={(e) => setRuleModel(e.target.value)}
            >
              {(options?.attributionModels ?? []).map((m) => (
                <option key={m.value} value={m.value}>
                  {m.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-xs">
            归因窗口（天）
            <input
              className="rounded border px-2 py-1 text-sm"
              type="number"
              min={0}
              max={365}
              value={ruleWindow}
              onChange={(e) => setRuleWindow(Number(e.target.value))}
            />
          </label>
          <label className="flex flex-col gap-1 text-xs">
            备注
            <input
              className="rounded border px-2 py-1 text-sm"
              value={ruleNote}
              onChange={(e) => setRuleNote(e.target.value)}
            />
          </label>
        </div>
        {ruleErrors.length > 0 ? (
          <ul className="text-xs text-amber-600">
            {ruleErrors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        ) : null}
        <div className="mt-2">
          <button
            className="rounded border px-3 py-1 text-sm disabled:opacity-50"
            disabled={busy}
            onClick={() => void onSaveRule()}
          >
            保存并启用
          </button>
        </div>

        {rules.length > 0 ? (
          <table className="mt-3 w-full text-xs">
            <thead>
              <tr className="text-muted-foreground">
                <th className="p-1 text-left">规则</th>
                <th className="p-1 text-left">模型</th>
                <th className="p-1 text-left">窗口（天）</th>
                <th className="p-1 text-left">状态</th>
                <th className="p-1 text-left">版本</th>
                <th className="p-1 text-left">备注</th>
              </tr>
            </thead>
            <tbody>
              {rules.map((r) => (
                <tr key={r.id} className="border-t">
                  <td className="p-1">{r.ruleCode}</td>
                  <td className="p-1">{attributionModelText(r.attributionModel)}</td>
                  <td className="p-1">{r.attributionWindowDays}</td>
                  <td className="p-1">{r.status === 'ACTIVE' ? '生效中' : '已停用'}</td>
                  <td className="p-1">{r.version}</td>
                  <td className="p-1">{r.note}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
      </Section>

      {/* ================= 归因口径（规则原文必须公示） ================= */}
      {records || report ? (
        <Section title="归因口径（服务端原文，前端不自己写第二份）">
          <p className="text-sm">{(records ?? report)?.attributionNote}</p>
          <p className="text-muted-foreground mt-1 text-xs">
            🔴 本页的「广告花费」是**订单级归因口径**，与 14.3.1.3 的**投放关系分摊口径**
            不是同一个数 —— 同一个 SKU 的广告后净利在两页上不相等是正常的。
          </p>
        </Section>
      ) : null}

      {/* ================= 导入平台广告归因数据 ================= */}
      <Section title="导入平台广告归因数据">
        <p className="text-muted-foreground text-xs">
          🔴 平台归因数据的适配器尚未接入 ⇒ 这里直接粘贴平台归因报表的 JSON （
          <code>{'{ dataAsOf, orders[] }'}</code>，每条 order 含
          <code>
            externalOrderNo / orderAt / internalSkuId / currency / attributedSpend / attributedSales
            / attributedOrders / attributedUnits / clicks[]
          </code>
          ）。 导入是**幂等**的：同一订单重复导入是刷新，不会把金额翻倍。
        </p>
        <textarea
          className="mt-2 h-32 w-full rounded border p-2 font-mono text-xs"
          value={importPayload}
          onChange={(e) => setImportPayload(e.target.value)}
          placeholder='{"dataAsOf":"2026-10-01T09:00:00+08:00","orders":[]}'
        />
        {importErrors.length > 0 ? (
          <ul className="text-xs text-amber-600">
            {importErrors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        ) : null}
        <div className="mt-2">
          <button
            className="rounded border px-3 py-1 text-sm disabled:opacity-50"
            disabled={busy}
            onClick={() => void onImport()}
          >
            导入
          </button>
        </div>
        {importResult ? (
          <div className="mt-2 text-xs">
            <p>
              读入 {importResult.orderRows} 条，写入 {importResult.writtenOrderRows} 条订单 /{' '}
              {importResult.writtenClickRows} 条点击。
            </p>
            {importResult.rejected.length > 0 ? (
              <ul className="text-amber-600">
                {importResult.rejected.map((r) => (
                  <li key={r}>✗ {r}</li>
                ))}
              </ul>
            ) : null}
            <p className="text-muted-foreground">{importResult.note}</p>
          </div>
        ) : null}
      </Section>

      {/* ================= 筛选 ================= */}
      <Section title="筛选">
        <div className="grid gap-2 md:grid-cols-6">
          <label className="flex flex-col gap-1 text-xs">
            统计起始日
            <input
              className="rounded border px-2 py-1 text-sm"
              type="date"
              value={query.from ?? ''}
              onChange={(e) => applyFilter({ from: e.target.value })}
            />
          </label>
          <label className="flex flex-col gap-1 text-xs">
            统计截止日
            <input
              className="rounded border px-2 py-1 text-sm"
              type="date"
              value={query.to ?? ''}
              onChange={(e) => applyFilter({ to: e.target.value })}
            />
          </label>
          <label className="flex flex-col gap-1 text-xs">
            店铺
            <select
              className="rounded border px-2 py-1 text-sm"
              value={query.shopCode ?? ''}
              onChange={(e) => applyFilter({ shopCode: e.target.value || undefined })}
            >
              <option value="">全部</option>
              {(options?.shops ?? []).map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-xs">
            归因状态
            <select
              className="rounded border px-2 py-1 text-sm"
              value={query.status ?? ''}
              onChange={(e) => applyFilter({ status: e.target.value || undefined })}
            >
              <option value="">全部</option>
              {(options?.attributionStatuses ?? []).map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-xs">
            订单号
            <input
              className="rounded border px-2 py-1 text-sm"
              value={query.keyword ?? ''}
              onChange={(e) => applyFilter({ keyword: e.target.value || undefined })}
            />
          </label>
          <label className="flex flex-col gap-1 text-xs">
            排序
            <select
              className="rounded border px-2 py-1 text-sm"
              value={query.sort ?? 'ORDER_AT_DESC'}
              onChange={(e) => applyFilter({ sort: e.target.value })}
            >
              {(options?.sorts ?? []).map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </label>
        </div>
        {rangeErrors.length > 0 ? (
          <ul className="mt-2 text-xs text-amber-600">
            {rangeErrors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        ) : null}
        <div className="mt-2 flex flex-wrap items-center gap-2">
          <button
            className="rounded border px-3 py-1 text-sm disabled:opacity-50"
            disabled={busy || rangeErrors.length > 0}
            onClick={() => void onRun(false)}
          >
            跑归因计算（不重算）
          </button>
          <button
            className="rounded border px-3 py-1 text-sm disabled:opacity-50"
            disabled={busy || rangeErrors.length > 0}
            onClick={() => void onRun(true)}
          >
            重算本版本
          </button>
          <a className="rounded border px-3 py-1 text-sm" href={exportUrl}>
            导出 CSV
          </a>
        </div>
        {runResult ? (
          <p className="mt-2 text-xs">
            规则 {runResult.ruleCode} 第 {runResult.ruleVersion} 版 · 候选订单{' '}
            {runResult.candidateOrders} 条 · 写入 {runResult.writtenFacts} 条 · 归因成立{' '}
            {runResult.attributedFacts} 条 · 归因不成立 {runResult.unattributedFacts} 条
          </p>
        ) : null}
      </Section>

      {/* ================= 错误态 ================= */}
      {error ? (
        <Section title="出错了">
          <p className="text-sm text-amber-600">{error}</p>
        </Section>
      ) : null}

      {/* ================= 主体：明细 / 报表 ================= */}
      <div className="flex gap-2">
        <button
          className={`rounded border px-3 py-1 text-sm ${tab === 'records' ? 'bg-foreground text-background' : ''}`}
          onClick={() => setTab('records')}
        >
          归因明细
        </button>
        <button
          className={`rounded border px-3 py-1 text-sm ${tab === 'report' ? 'bg-foreground text-background' : ''}`}
          onClick={() => setTab('report')}
        >
          广告后利润报表
        </button>
      </div>

      {loading ? <p className="text-muted-foreground text-sm">加载中…</p> : null}

      {tab === 'records' ? (
        <>
          {records ? (
            <Section title="归因明细汇总（按币种拆开，不受分页影响）">
              <div className="flex flex-col gap-2">
                {records.summaries.map((s) => (
                  <div key={s.currency} className="rounded border p-2 text-sm">
                    <p className="font-medium">{s.currency || '（未标币种）'}</p>
                    <p>
                      归因成立 {s.attributedRows} 条 / 归不上 {s.unattributedRows} 条 · 花费合计{' '}
                      {attributionMoney(s.totalSpend, s.currency)}
                    </p>
                    <p>
                      🔴 归不上（单列，不摊给任何 SKU）：花费{' '}
                      {attributionMoney(s.unattributedSpend, s.currency)} · 销售额{' '}
                      {attributionMoney(s.unattributedSales, s.currency)} · 订单数{' '}
                      {attributionQuantity(s.unattributedOrders)}
                    </p>
                    <ul className="text-muted-foreground text-xs">
                      {s.statusCounts.map((c) => (
                        <li key={c.status}>
                          {c.label}：{c.count} 条
                        </li>
                      ))}
                    </ul>
                  </div>
                ))}
              </div>
            </Section>
          ) : null}

          {records && records.items.length === 0 && !loading ? (
            <Section title="归因明细">
              <p className="text-muted-foreground text-sm">{recordsEmpty}</p>
            </Section>
          ) : null}

          {records && records.items.length > 0 ? (
            <Section title={`归因明细（共 ${records.total} 条）`}>
              <div className="overflow-x-auto">
                <table className="w-full text-xs">
                  <thead>
                    <tr className="text-muted-foreground">
                      <th className="p-1 text-left">订单号</th>
                      <th className="p-1 text-left">平台</th>
                      <th className="p-1 text-left">店铺</th>
                      <th className="p-1 text-left">活动</th>
                      <th className="p-1 text-left">内部 SKU</th>
                      <th className="p-1 text-left">下单时间</th>
                      <th className="p-1 text-left">归因状态</th>
                      <th className="p-1 text-left">命中点击</th>
                      <th className="p-1 text-right">合格点击</th>
                      <th className="p-1 text-right">花费</th>
                      <th className="p-1 text-right">广告销售额</th>
                      <th className="p-1 text-left">规则快照</th>
                    </tr>
                  </thead>
                  <tbody>
                    {records.items.map((row) => (
                      <tr key={row.id} className="border-t">
                        <td className="p-1">{row.externalOrderNo}</td>
                        <td className="p-1">{row.platformCode}</td>
                        <td className="p-1">{row.shopCode}</td>
                        <td className="p-1">{row.campaignName}</td>
                        <td className="p-1">{row.internalSkuId ?? '—（归不到 SKU）'}</td>
                        <td className="p-1">{attributionTimestampText(row.orderAt)}</td>
                        <td className={`p-1 ${attributionStatusTone(row.attributionStatus)}`}>
                          {attributionStatusText(row.attributionStatus)}
                        </td>
                        <td className="p-1">{row.chosenClickId || '—'}</td>
                        <td className="p-1 text-right">{row.eligibleClickCount}</td>
                        <td className="p-1 text-right">
                          {attributionMoney(row.attributedSpend, row.currency)}
                        </td>
                        <td className="p-1 text-right">
                          {attributionMoney(row.attributedSales, row.currency)}
                        </td>
                        <td className="text-muted-foreground p-1">
                          {row.ruleCode} v{row.ruleVersion} ·{' '}
                          {attributionModelText(row.attributionModel)} · {row.attributionWindowDays}
                          天
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Pager
                total={records.total}
                page={records.page}
                size={records.size}
                onChange={(p) => applyFilter({ page: p })}
              />
            </Section>
          ) : null}
        </>
      ) : (
        <>
          {report ? (
            <Section title="增量效果估计的口径（推导值，不是平台原值）">
              <p className="text-sm">{report.incrementalityNote}</p>
            </Section>
          ) : null}

          {report ? (
            <Section title="口径说明">
              <p className="text-sm">{report.metricDefinitionNote}</p>
              <ul className="flex flex-col gap-1 text-xs">
                {(report.metricDefinitions ?? [])
                  .filter((d) =>
                    ['acos', 'tacos', 'ad_sales_share', 'incremental_sales_estimate'].includes(
                      d.metric,
                    ),
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

          {report && (!report.profitAvailable || !report.catalogAvailable) ? (
            <Section title="上游可达性">
              <ul className="flex flex-col gap-1 text-sm">
                {!report.profitAvailable ? (
                  <li className="text-amber-600">
                    🔴 利润侧（13.4.1.2）这次没读到 ⇒ 总销售额 / TACOS / 广告销售占比 / 总净利 /
                    广告后净利 / 增量效果这几列是**空的**，不是 0。
                    <span className="text-muted-foreground block text-xs">
                      原因：{report.profitUnavailableReason}
                    </span>
                  </li>
                ) : null}
                {!report.catalogAvailable ? (
                  <li className="text-amber-600">
                    🔴 商品主数据（catalog）这次没读到 ⇒ SKU 编码 / SPU 编码是空的。
                    <span className="text-muted-foreground block text-xs">
                      原因：{report.catalogUnavailableReason}
                    </span>
                  </li>
                ) : null}
              </ul>
            </Section>
          ) : null}

          {report ? (
            <Section title="报表汇总（按币种拆开，不受分页影响）">
              <div className="flex flex-col gap-2">
                {report.summaries.map((s) => (
                  <div key={s.currency} className="rounded border p-2 text-sm">
                    <p className="font-medium">{s.currency || '（未标币种）'}</p>
                    <p>
                      归因花费 {attributionMoney(s.attributedSpend, s.currency)} · 广告销售额{' '}
                      {attributionMoney(s.attributedSales, s.currency)} · 总销售额{' '}
                      {attributionMoney(s.totalRevenue, s.currency)} · 总净利{' '}
                      {attributionMoney(s.totalNetProfit, s.currency)}
                    </p>
                    <p>
                      TACOS {attributionRatio(s.tacos)} · ACOS {attributionRatio(s.acos)} ·
                      广告销售占比 {attributionRatio(s.adSalesShare)} · 广告后净利{' '}
                      {attributionMoney(s.adAfterNetProfit, s.currency)} · 广告占比利润{' '}
                      {attributionRatio(s.adProfitShare)}
                    </p>
                    <p className="text-amber-600">
                      🔴 归不上（单列）：花费 {attributionMoney(s.unattributedSpend, s.currency)} ·
                      销售额 {attributionMoney(s.unattributedSales, s.currency)}
                    </p>
                    <p className="text-muted-foreground text-xs">
                      活动级总花费 {attributionMoney(s.campaignTotalSpend, s.currency)} · 对账缺口{' '}
                      {attributionMoney(s.campaignSpendGap, s.currency)} · 归因覆盖率{' '}
                      {attributionRatio(s.attributionCoverage)}
                    </p>
                    <p className="text-muted-foreground text-xs">
                      增量销售额估计（估算）
                      {attributionMoney(s.incrementalSalesEstimate, s.currency)} · 增量占比{' '}
                      {attributionRatio(s.incrementalShare)}
                    </p>
                  </div>
                ))}
              </div>
            </Section>
          ) : null}

          {report && report.items.length === 0 && !loading ? (
            <Section title="广告后利润报表">
              <p className="text-muted-foreground text-sm">{reportEmpty}</p>
            </Section>
          ) : null}

          {report && report.items.length > 0 ? (
            <Section title={`广告后利润报表（共 ${report.total} 个 SKU）`}>
              <div className="overflow-x-auto">
                <table className="w-full text-xs">
                  <thead>
                    <tr className="text-muted-foreground">
                      <th className="p-1 text-left">SKU</th>
                      <th className="p-1 text-left">SPU</th>
                      <th className="p-1 text-left">币种</th>
                      <th className="p-1 text-right">归因花费</th>
                      <th className="p-1 text-right">归因销售额</th>
                      <th className="p-1 text-right">总销售额</th>
                      <th className="p-1 text-right">TACOS</th>
                      <th className="p-1 text-right">ACOS</th>
                      <th className="p-1 text-right">广告销售占比</th>
                      <th className="p-1 text-right">广告后净利</th>
                      <th className="p-1 text-right">增量销售额（估算）</th>
                      <th className="p-1 text-left">规则快照</th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.items.map((row) => (
                      <tr key={`${row.skuId}-${row.currency}`} className="border-t">
                        <td className="p-1">{row.skuCode || '—'}</td>
                        <td className="p-1">{row.spuCode || '—'}</td>
                        <td className="p-1">{row.currency}</td>
                        <td className="p-1 text-right">
                          {attributionMoney(row.adSpend, row.currency)}
                        </td>
                        <td className="p-1 text-right">
                          {attributionMoney(row.adSales, row.currency)}
                        </td>
                        <td className="p-1 text-right">
                          {attributionMoney(row.totalSales, row.currency)}
                        </td>
                        <td className={`p-1 text-right ${attributionRatioTone(row.tacos)}`}>
                          {attributionRatio(row.tacos)}
                        </td>
                        <td className={`p-1 text-right ${attributionRatioTone(row.acos)}`}>
                          {attributionRatio(row.acos)}
                        </td>
                        <td className={`p-1 text-right ${attributionRatioTone(row.adSalesShare)}`}>
                          {attributionRatio(row.adSalesShare)}
                        </td>
                        <td className="p-1 text-right">
                          {attributionMoney(row.adAfterNetProfit, row.currency)}
                        </td>
                        <td className="p-1 text-right">
                          {attributionMoney(row.incrementalSalesEstimate, row.currency)}
                        </td>
                        <td className="text-muted-foreground p-1">
                          {row.ruleCode} v{row.ruleVersion} ·{' '}
                          {attributionModelText(row.attributionModel)} · {row.attributionWindowDays}
                          天
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Pager
                total={report.total}
                page={report.page}
                size={report.size}
                onChange={(p) => applyFilter({ page: p })}
              />
            </Section>
          ) : null}
        </>
      )}

      {/* ================= 导出留痕 ================= */}
      {exports && exports.items.length > 0 ? (
        <Section title="导出留痕（每次导出都记下当时用的规则快照）">
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="text-muted-foreground">
                  <th className="p-1 text-left">时间</th>
                  <th className="p-1 text-left">规则快照</th>
                  <th className="p-1 text-left">区间</th>
                  <th className="p-1 text-right">行数</th>
                </tr>
              </thead>
              <tbody>
                {exports.items.map((e) => (
                  <tr key={e.id} className="border-t">
                    <td className="p-1">{attributionTimestampText(e.createdAt)}</td>
                    <td className="p-1">
                      {e.ruleCode} v{e.ruleVersion} · {attributionModelText(e.attributionModel)} ·{' '}
                      {e.attributionWindowDays}天
                    </td>
                    <td className="p-1">
                      {e.dateFrom ?? '—'} ~ {e.dateTo ?? '—'}
                    </td>
                    <td className="p-1 text-right">{e.rowCount}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Section>
      ) : null}

      {/* ================= 能力声明 ================= */}
      {capabilities ? (
        <Section title="能力声明（未交付段显式标出，不用假数字糊过去）">
          <ul className="flex flex-col gap-1 text-xs">
            {capabilities.dimensions.map((d) => (
              <li key={d.dimension} className="flex flex-col">
                <span className={d.available ? 'text-foreground' : 'text-amber-600'}>
                  {d.available ? '✓' : '✗'} {d.description}
                </span>
                {!d.available ? (
                  <span className="text-muted-foreground">
                    责任模块 {d.ownerModule}：{d.impact}
                  </span>
                ) : null}
              </li>
            ))}
          </ul>
          <p className="text-muted-foreground mt-2 text-xs">{capabilities.boundaryNote}</p>
        </Section>
      ) : null}
    </main>
  );
}

function Section(props: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded border p-3">
      <h2 className="mb-2 text-sm font-medium">{props.title}</h2>
      {props.children}
    </section>
  );
}

function Pager(props: {
  total: number;
  page: number;
  size: number;
  onChange: (page: number) => void;
}) {
  const pages = Math.max(1, Math.ceil(props.total / Math.max(props.size, 1)));
  const current = props.page + 1;
  return (
    <div className="mt-2 flex items-center gap-2 text-xs">
      <button
        className="rounded border px-2 py-1 disabled:opacity-50"
        disabled={props.page <= 0}
        onClick={() => props.onChange(props.page - 1)}
      >
        上一页
      </button>
      <span>
        第 {current} / {pages} 页（{props.total} 条）
      </span>
      <button
        className="rounded border px-2 py-1 disabled:opacity-50"
        disabled={current >= pages}
        onClick={() => props.onChange(props.page + 1)}
      >
        下一页
      </button>
    </div>
  );
}
