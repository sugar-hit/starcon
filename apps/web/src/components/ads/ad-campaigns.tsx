'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { errorText } from '../inventory/inventory-model';
import {
  adStatusLabel,
  adTypeLine,
  bulkOperateAdCampaigns,
  emptyStateText,
  fetchAdCampaign,
  fetchAdCampaignCapabilities,
  fetchAdCampaignOptions,
  fetchAdCampaigns,
  fetchAdSyncRuns,
  guardrailText,
  importAdCampaigns,
  internalTypeLabel,
  mappingSourceLabel,
  money,
  operationLabel,
  operationResultLabel,
  operationResultTone,
  quantity,
  ratioText,
  ratioTone,
  syncStatusLabel,
  syncStatusTone,
  timestampText,
  validateAdImport,
  validateBulkOperation,
} from './ad-campaign-model';
import type {
  AdBudgetGuardrails,
  AdCampaignCapabilities,
  AdCampaignDetail,
  AdCampaignOptions,
  AdCampaignPage,
  AdCampaignQuery,
  AdImportInput,
  AdSyncRunPage,
  BulkOperationResult,
  ImportResult,
} from './ad-campaign-model';
import { emptyAdImportInput } from './ad-campaign-model';

/**
 * 14.1.1.1 广告活动列表。
 *
 * 🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * 1. **「数据截至 X 时间」必须看得见**。广告数据同步是本系统的地基，而平台同步器尚未交付 ——
 *    页面顶部常驻一条水位（来自服务端，不是在浏览器里推的）+ 逐平台/店铺的同步状态与失败原因。
 *    只给时间不给原因等于让人猜。
 * 2. **三种「空」分开说**。`NO_SYNCED_DATA`（还没同步到数据）/ `NO_CAMPAIGNS`（真的没有投放）/
 *    `FILTERED_EMPTY`（被筛掉了）是三件不同的事，渲染成同一种空态就是把
 *    「没数据」说成「没投放」。
 * 3. **归一类型与原始类型成对出现**。每一行都同时显示「原始标记 → 归一类型」；
 *    `UNMAPPED` 是显式合法值，表示没有可比口径，不是「没有这种广告」。
 *
 * 🔴 批量操作这一块刻意**只认显式勾选的活动 id**：按筛选结果全选 + 变更预览 + 异步任务
 * 是 14.1.1.3 的职责（未交付），本页面不假装做了。
 */
export function AdCampaignWorkspace() {
  const [capabilities, setCapabilities] = useState<AdCampaignCapabilities | null>(null);
  const [options, setOptions] = useState<AdCampaignOptions | null>(null);
  const [page, setPage] = useState<AdCampaignPage | null>(null);
  const [detail, setDetail] = useState<AdCampaignDetail | null>(null);

  const [query, setQuery] = useState<AdCampaignQuery>({ sort: 'SPEND_DESC', page: 0, size: 50 });
  const [selected, setSelected] = useState<string[]>([]);
  const [operation, setOperation] = useState('PAUSE');
  const [budgetValue, setBudgetValue] = useState('');
  const [deltaValue, setDeltaValue] = useState('');
  const [operationReason, setOperationReason] = useState('');
  const [operationErrors, setOperationErrors] = useState<string[]>([]);
  const [bulkResult, setBulkResult] = useState<BulkOperationResult | null>(null);

  const [importInput, setImportInput] = useState<AdImportInput>(emptyAdImportInput);
  const [importErrors, setImportErrors] = useState<string[]>([]);
  const [importResult, setImportResult] = useState<ImportResult | null>(null);
  const [syncRuns, setSyncRuns] = useState<AdSyncRunPage | null>(null);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async (next: AdCampaignQuery) => {
    setLoading(true);
    setError('');
    try {
      const [campaigns, runs] = await Promise.all([
        fetchAdCampaigns(next),
        // 同步运行历史取不到不该把整张列表拖垮 —— 它只是「水位从哪来」的补充证据。
        fetchAdSyncRuns(0, 20).catch(() => null),
      ]);
      setPage(campaigns);
      setSyncRuns(runs);
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
          fetchAdCampaignCapabilities(),
          fetchAdCampaignOptions(),
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

  const guardrails: AdBudgetGuardrails | null =
    options?.guardrails ?? capabilities?.guardrails ?? null;

  const selectedRows = useMemo(
    () => (page?.items ?? []).filter((row) => selected.includes(row.id)),
    [page, selected],
  );

  function applyFilter(patch: AdCampaignQuery) {
    setSelected([]);
    setQuery((prev) => ({ ...prev, page: 0, ...patch }));
  }

  function toggle(id: string) {
    setSelected((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));
  }

  async function onOpenDetail(id: string) {
    setBusy(true);
    setError('');
    try {
      setDetail(await fetchAdCampaign(id));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onBulk() {
    const validation = validateBulkOperation(selected, operation, budgetValue, deltaValue);
    setOperationErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      const extra: { budget?: number; delta?: number; reason?: string } = {
        reason: operationReason,
      };
      if (operation === 'SET_BUDGET') extra.budget = Number(budgetValue);
      if (operation === 'ADJUST_BUDGET') extra.delta = Number(deltaValue);
      // 🔴 只提交**显式勾选**的 id —— 「按筛选结果全选」是 14.1.1.3 的职责。
      const result = await bulkOperateAdCampaigns(selected, operation, extra);
      setBulkResult(result);
      setSelected([]);
      await load(query);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onImport() {
    const validation = validateAdImport(importInput);
    setImportErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      const result = await importAdCampaigns({
        platformId: importInput.platformId.trim(),
        shopCode: importInput.shopCode.trim(),
        syncRun: {
          status: importInput.syncStatus,
          dataAsOf: importInput.dataAsOf.trim()
            ? new Date(importInput.dataAsOf).toISOString()
            : undefined,
          startedAt: new Date(importInput.startedAt).toISOString(),
          finishedAt: importInput.finishedAt.trim()
            ? new Date(importInput.finishedAt).toISOString()
            : undefined,
          message: importInput.message.trim(),
          sourceRef: importInput.sourceRef.trim(),
        },
        campaigns: [
          {
            externalCampaignId: importInput.externalCampaignId.trim(),
            campaignName: importInput.campaignName.trim(),
            adTypeProfileId: importInput.adTypeProfileId.trim(),
            status: importInput.status.trim(),
            currency: importInput.currency.trim(),
            dailyBudget:
              importInput.dailyBudget.trim() === '' ? undefined : Number(importInput.dailyBudget),
            metrics: [
              {
                metricDate: importInput.metricDate,
                impressions: Number(importInput.impressions || '0'),
                clicks: Number(importInput.clicks || '0'),
                orders: Number(importInput.orders || '0'),
                spend: Number(importInput.spend || '0'),
                sales: Number(importInput.sales || '0'),
              },
            ],
            note: '',
          },
        ],
      });
      setImportResult(result);
      setImportInput(emptyAdImportInput);
      await load(query);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  const summary = page?.summary;
  const emptyText = emptyStateText(page?.emptyReason ?? null, page?.emptyNote ?? '');

  return (
    <main className="flex flex-col gap-6 p-6">
      <header className="flex flex-col gap-2">
        <h1 className="text-2xl font-semibold">广告活动列表</h1>
        <p className="text-muted-foreground max-w-4xl text-sm">
          广告活动主列表：平台、店铺、活动名、类型、状态、预算、消耗、曝光、点击、订单、销售额与
          ACOS / ROAS，按维度筛选与排序。广告数据同步是本系统的地基，页面顶部常驻
          <strong>「数据截至 X 时间」</strong>；广告类型同时显示
          <strong>原始平台标记</strong>与<strong>归一后类型</strong>。
        </p>
      </header>

      {error ? <p className="text-destructive text-sm">{error}</p> : null}

      {/* ================= 数据截至 X 时间 ================= */}
      <Section title="数据截至时间">
        {loading && !page ? (
          <p className="text-muted-foreground text-sm">正在加载广告活动与同步水位…</p>
        ) : (
          <>
            <p className="text-sm">
              🔴 数据截至：
              <strong className="ml-1">
                {page?.dataAsOf ? timestampText(page.dataAsOf) : '尚无成功同步'}
              </strong>
            </p>
            <p className="text-muted-foreground text-xs">{page?.dataAsOfNote}</p>
            {page && page.syncStates.length > 0 ? (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground text-left text-xs">
                    <th className="py-1">平台 / 店铺</th>
                    <th className="py-1">同步状态</th>
                    <th className="py-1">数据截至</th>
                    <th className="py-1">活动数</th>
                    <th className="py-1">失败原因</th>
                  </tr>
                </thead>
                <tbody>
                  {page.syncStates.map((state) => (
                    <tr key={`${state.platformCode}-${state.shopCode}`} className="border-t">
                      <td className="py-1">
                        {state.platformName} / {state.shopName}
                      </td>
                      <td className={`py-1 ${syncStatusTone(state.status)}`}>
                        {syncStatusLabel(state.status)}
                      </td>
                      <td className="py-1">{timestampText(state.dataAsOf)}</td>
                      <td className="py-1">{state.campaignCount}</td>
                      <td className="text-destructive py-1 text-xs">{state.message || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <p className="text-muted-foreground text-sm">
                还没有任何一次同步运行记录 —— 这是「还没同步到数据」，不是「没有投放」。
              </p>
            )}
          </>
        )}
      </Section>

      {/* ================= 筛选 ================= */}
      <Section title="筛选与排序">
        <div className="flex flex-wrap gap-3">
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
          <Field label="关键词">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={query.keyword ?? ''}
              onChange={(e) => applyFilter({ keyword: e.target.value })}
              placeholder="活动名或平台侧 id"
            />
          </Field>
          <Field label="统计起始日">
            <input
              type="date"
              className="rounded border px-2 py-1 text-sm"
              value={query.from ?? ''}
              onChange={(e) => applyFilter({ from: e.target.value })}
            />
          </Field>
          <Field label="统计截止日">
            <input
              type="date"
              className="rounded border px-2 py-1 text-sm"
              value={query.to ?? ''}
              onChange={(e) => applyFilter({ to: e.target.value })}
            />
          </Field>
          <Field label="排序">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={query.sort ?? 'SPEND_DESC'}
              onChange={(e) => applyFilter({ sort: e.target.value })}
            >
              {options?.sortOptions.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </Field>
        </div>
        <p className="text-muted-foreground text-xs">
          日期是**统计区间**：给了区间就只统计区间内的指标，并只保留区间内有数据的活动。
          汇总统计的是筛选后的**全集**，不是当前页。
        </p>
      </Section>

      {/* ================= 汇总 ================= */}
      {summary ? (
        <Section title="汇总（筛选后的全集，不受分页影响）">
          <dl className="grid grid-cols-2 gap-3 md:grid-cols-4">
            <Stat label="活动数" value={String(summary.campaignCount)} />
            <Stat
              label="投放中 / 已暂停"
              value={`${summary.enabledCount} / ${summary.pausedCount}`}
            />
            <Stat label="日预算合计" value={money(summary.totalBudget)} />
            <Stat label="消耗合计" value={money(summary.totalSpend)} />
            <Stat label="销售额合计" value={money(summary.totalSales)} />
            <Stat
              label="曝光 / 点击 / 订单"
              value={`${quantity(summary.totalImpressions)} / ${quantity(summary.totalClicks)} / ${quantity(summary.totalOrders)}`}
            />
            <Stat label="ACOS（消耗 / 销售额）" value={`${ratioText(summary.acos)}`} />
            <Stat label="ROAS（销售额 / 消耗）" value={ratioText(summary.roas, false)} />
          </dl>
          <p className="text-muted-foreground text-xs">{summary.metricDefinitionNote}</p>
          {summary.metricFrom ? (
            <p className="text-muted-foreground text-xs">
              实际统计区间：{summary.metricFrom} ~ {summary.metricTo}
            </p>
          ) : null}
        </Section>
      ) : null}

      {/* ================= 列表 ================= */}
      <Section title="广告活动">
        {loading ? (
          <p className="text-muted-foreground text-sm">正在加载广告活动…</p>
        ) : page && page.items.length === 0 ? (
          <p className="text-sm text-amber-600">{emptyText || '没有符合条件的数据。'}</p>
        ) : page ? (
          <>
            <div className="overflow-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground text-left text-xs">
                    <th className="py-1">选择</th>
                    <th className="py-1">平台 / 店铺</th>
                    <th className="py-1">活动名 / 平台侧 id</th>
                    <th className="py-1">类型（原始 → 归一）</th>
                    <th className="py-1">状态</th>
                    <th className="py-1">预算</th>
                    <th className="py-1">消耗</th>
                    <th className="py-1">曝光 / 点击 / 订单</th>
                    <th className="py-1">销售额</th>
                    <th className="py-1">ACOS / ROAS</th>
                    <th className="py-1">数据截至</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((row) => (
                    <tr key={row.id} className="border-t">
                      <td className="py-1">
                        <input
                          type="checkbox"
                          checked={selected.includes(row.id)}
                          onChange={() => toggle(row.id)}
                          aria-label={`选择 ${row.campaignName}`}
                        />
                      </td>
                      <td className="py-1">
                        {row.platformName} / {row.shopName}
                      </td>
                      <td className="py-1">
                        <button
                          className="underline"
                          onClick={() => void onOpenDetail(row.id)}
                          disabled={busy}
                        >
                          {row.campaignName || '（未命名）'}
                        </button>
                        <div className="text-muted-foreground text-xs">
                          {row.externalCampaignId}
                        </div>
                      </td>
                      <td className="py-1">
                        <div>{row.platformAdType}</div>
                        <div className="text-muted-foreground text-xs">
                          → {internalTypeLabel(row.internalAdType)}
                          {row.adTypeComparable ? '' : '（不参与跨平台比较）'}
                        </div>
                        <div className="text-muted-foreground text-xs">
                          归一口径：{mappingSourceLabel(row.adTypeMappingSource)}
                        </div>
                      </td>
                      <td className="py-1">
                        {adStatusLabel(row.status)}
                        {row.pendingPlatformWriteback ? (
                          <div className="text-xs text-amber-600">本地已改，待回写平台</div>
                        ) : null}
                      </td>
                      <td className="py-1">{money(row.dailyBudget, row.currency)}</td>
                      <td className="py-1">{money(row.spend, row.currency)}</td>
                      <td className="py-1">
                        {quantity(row.impressions)} / {quantity(row.clicks)} /{' '}
                        {quantity(row.orders)}
                      </td>
                      <td className="py-1">{money(row.sales, row.currency)}</td>
                      <td className="py-1">
                        <span className={ratioTone(row.acos)}>{ratioText(row.acos)}</span>
                        <span className={ratioTone(row.roas)}> / {ratioText(row.roas, false)}</span>
                      </td>
                      <td className="py-1 text-xs">{timestampText(row.dataAsOf)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="flex items-center gap-2 text-sm">
              <span className="text-muted-foreground text-xs">
                共 {page.total} 条，当前第 {page.page + 1} 页
              </span>
              <button
                className="rounded border px-2 py-1 text-xs"
                disabled={page.page === 0}
                onClick={() =>
                  setQuery((prev) => ({ ...prev, page: Math.max(0, (prev.page ?? 0) - 1) }))
                }
              >
                上一页
              </button>
              <button
                className="rounded border px-2 py-1 text-xs"
                disabled={(page.page + 1) * page.size >= page.total}
                onClick={() => setQuery((prev) => ({ ...prev, page: (prev.page ?? 0) + 1 }))}
              >
                下一页
              </button>
            </div>
          </>
        ) : null}
      </Section>

      {/* ================= 批量操作 ================= */}
      <Section title="批量暂停 / 启用 / 调预算（只作用于勾选的活动）">
        <p className="text-muted-foreground text-xs">
          🔴 本页只处理**显式勾选**的活动 id。按筛选结果全选、变更预览与异步批量任务属于
          14.1.1.3（未交付），这里不假装做了。{guardrailText(guardrails)}
        </p>
        <div className="flex flex-wrap items-end gap-3">
          <Field label="操作">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={operation}
              onChange={(e) => setOperation(e.target.value)}
            >
              <option value="PAUSE">暂停</option>
              <option value="ENABLE">启用</option>
              <option value="SET_BUDGET">设为预算</option>
              <option value="ADJUST_BUDGET">调整预算</option>
            </select>
          </Field>
          {operation === 'SET_BUDGET' ? (
            <Field label="目标日预算">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={budgetValue}
                onChange={(e) => setBudgetValue(e.target.value)}
              />
            </Field>
          ) : null}
          {operation === 'ADJUST_BUDGET' ? (
            <Field label="增减额（可为负）">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={deltaValue}
                onChange={(e) => setDeltaValue(e.target.value)}
              />
            </Field>
          ) : null}
          <Field label="理由（写进审计）">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={operationReason}
              onChange={(e) => setOperationReason(e.target.value)}
            />
          </Field>
          <button
            className="rounded border px-3 py-1 text-sm"
            disabled={busy || selected.length === 0}
            onClick={() => void onBulk()}
          >
            对 {selected.length} 条执行
          </button>
        </div>
        {operationErrors.length > 0 ? (
          <ul className="text-destructive text-sm">
            {operationErrors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        ) : null}
        {selectedRows.length > 0 ? (
          <p className="text-muted-foreground text-xs">
            已勾选：{selectedRows.map((r) => r.campaignName || r.externalCampaignId).join('、')}
          </p>
        ) : null}
        {bulkResult ? (
          <div className="flex flex-col gap-2">
            <p className="text-sm">
              已生效 {bulkResult.applied} 条，被拒 {bulkResult.rejected} 条，跳过{' '}
              {bulkResult.skipped} 条。
            </p>
            <ul className="text-sm">
              {bulkResult.items.map((item) => (
                <li key={item.campaignId} className={operationResultTone(item.result)}>
                  {item.campaignName || item.campaignId}：{operationResultLabel(item.result)}
                  {item.message ? ` —— ${item.message}` : ''}
                </li>
              ))}
            </ul>
            <p className="text-muted-foreground text-xs">{bulkResult.note}</p>
          </div>
        ) : null}
      </Section>

      {/* ================= 登记同步结果（真实写入口） ================= */}
      <Section title="登记广告数据同步结果">
        <p className="text-muted-foreground text-xs">
          🔴 平台广告 API
          同步器尚未交付，但「广告数据同步是本系统的地基」意味着活动与表现数据必须真的有
          落点。失败的同步**只登记失败**：不写活动、不推进水位，失败原因必须写明。
        </p>
        <div className="flex flex-wrap gap-3">
          <Field label="平台 id（UUID）">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={importInput.platformId}
              onChange={(e) => setImportInput({ ...importInput, platformId: e.target.value })}
            />
          </Field>
          <Field label="店铺编码">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={importInput.shopCode}
              onChange={(e) => setImportInput({ ...importInput, shopCode: e.target.value })}
            />
          </Field>
          <Field label="同步状态">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={importInput.syncStatus}
              onChange={(e) => setImportInput({ ...importInput, syncStatus: e.target.value })}
            >
              <option value="SUCCESS">成功</option>
              <option value="PARTIAL">部分成功</option>
              <option value="FAILED">失败</option>
            </select>
          </Field>
          <Field label="数据截至时刻">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={importInput.dataAsOf}
              onChange={(e) => setImportInput({ ...importInput, dataAsOf: e.target.value })}
              placeholder="2026-10-01T09:00:00+08:00"
            />
          </Field>
          <Field label="同步开始时刻">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={importInput.startedAt}
              onChange={(e) => setImportInput({ ...importInput, startedAt: e.target.value })}
              placeholder="2026-10-01T08:00:00+08:00"
            />
          </Field>
          <Field label="失败原因（失败时必填）">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={importInput.message}
              onChange={(e) => setImportInput({ ...importInput, message: e.target.value })}
            />
          </Field>
          <Field label="平台侧活动 id">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={importInput.externalCampaignId}
              onChange={(e) =>
                setImportInput({ ...importInput, externalCampaignId: e.target.value })
              }
            />
          </Field>
          <Field label="活动名">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={importInput.campaignName}
              onChange={(e) => setImportInput({ ...importInput, campaignName: e.target.value })}
            />
          </Field>
          <Field label="广告类型档案 id（14.1.2.2）">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={importInput.adTypeProfileId}
              onChange={(e) => setImportInput({ ...importInput, adTypeProfileId: e.target.value })}
            />
          </Field>
          <Field label="活动状态">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={importInput.status}
              onChange={(e) => setImportInput({ ...importInput, status: e.target.value })}
            />
          </Field>
          <Field label="币种">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={importInput.currency}
              onChange={(e) => setImportInput({ ...importInput, currency: e.target.value })}
            />
          </Field>
          <Field label="日预算">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={importInput.dailyBudget}
              onChange={(e) => setImportInput({ ...importInput, dailyBudget: e.target.value })}
            />
          </Field>
          <Field label="指标日期">
            <input
              type="date"
              className="rounded border px-2 py-1 text-sm"
              value={importInput.metricDate}
              onChange={(e) => setImportInput({ ...importInput, metricDate: e.target.value })}
            />
          </Field>
          <Field label="曝光">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={importInput.impressions}
              onChange={(e) => setImportInput({ ...importInput, impressions: e.target.value })}
            />
          </Field>
          <Field label="点击">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={importInput.clicks}
              onChange={(e) => setImportInput({ ...importInput, clicks: e.target.value })}
            />
          </Field>
          <Field label="订单">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={importInput.orders}
              onChange={(e) => setImportInput({ ...importInput, orders: e.target.value })}
            />
          </Field>
          <Field label="消耗">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={importInput.spend}
              onChange={(e) => setImportInput({ ...importInput, spend: e.target.value })}
            />
          </Field>
          <Field label="销售额">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={importInput.sales}
              onChange={(e) => setImportInput({ ...importInput, sales: e.target.value })}
            />
          </Field>
        </div>
        {importErrors.length > 0 ? (
          <ul className="text-destructive text-sm">
            {importErrors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        ) : null}
        <button
          className="self-start rounded border px-3 py-1 text-sm"
          disabled={busy}
          onClick={() => void onImport()}
        >
          登记
        </button>
        {importResult ? <p className="text-sm">{importResult.note}</p> : null}
      </Section>

      {/* ================= 同步运行历史 ================= */}
      <Section title="同步运行历史（数据截至时间的逐条依据）">
        {!syncRuns ? (
          <p className="text-muted-foreground text-sm">正在加载同步运行记录…</p>
        ) : syncRuns.items.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            还没有任何一次同步运行记录 —— 这是「还没同步到数据」，不是「没有投放」。
          </p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">登记时刻</th>
                <th className="py-1">平台 / 店铺</th>
                <th className="py-1">状态</th>
                <th className="py-1">数据截至</th>
                <th className="py-1">活动 / 指标行</th>
                <th className="py-1">原因</th>
              </tr>
            </thead>
            <tbody>
              {syncRuns.items.map((run) => (
                <tr key={run.id} className="border-t">
                  <td className="py-1 text-xs">{timestampText(run.createdAt)}</td>
                  <td className="py-1">
                    {run.platformCode} / {run.shopName}
                  </td>
                  <td className={`py-1 ${syncStatusTone(run.status)}`}>
                    {syncStatusLabel(run.status)}
                  </td>
                  <td className="py-1">{timestampText(run.dataAsOf)}</td>
                  <td className="py-1">
                    {run.campaignCount} / {run.metricRowCount}
                  </td>
                  <td className="text-destructive py-1 text-xs">{run.message || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
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
          <p className="text-muted-foreground text-xs">{capabilities.unmappedNote}</p>
          <p className="text-muted-foreground text-xs">{capabilities.boundaryNote}</p>
        </Section>
      ) : null}

      {detail ? (
        <Section title={`活动明细 · ${detail.campaign.campaignName}`}>
          <p className="text-muted-foreground text-xs">{adTypeLine(detail.campaign)}</p>
          <p className="text-muted-foreground text-xs">{detail.note}</p>
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">日期</th>
                <th className="py-1">曝光</th>
                <th className="py-1">点击</th>
                <th className="py-1">订单</th>
                <th className="py-1">消耗</th>
                <th className="py-1">销售额</th>
                <th className="py-1">ACOS / ROAS</th>
              </tr>
            </thead>
            <tbody>
              {detail.dailyMetrics.map((m) => (
                <tr key={m.metricDate} className="border-t">
                  <td className="py-1">{m.metricDate}</td>
                  <td className="py-1">{quantity(m.impressions)}</td>
                  <td className="py-1">{quantity(m.clicks)}</td>
                  <td className="py-1">{quantity(m.orders)}</td>
                  <td className="py-1">{money(m.spend, detail.campaign.currency)}</td>
                  <td className="py-1">{money(m.sales, detail.campaign.currency)}</td>
                  <td className="py-1">
                    {ratioText(m.acos)} / {ratioText(m.roas, false)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {detail.operationLogs.length > 0 ? (
            <>
              <h3 className="mt-2 text-sm font-medium">操作审计</h3>
              <ul className="text-xs">
                {detail.operationLogs.map((log) => (
                  <li key={log.id} className={operationResultTone(log.result)}>
                    {timestampText(log.createdAt)} · {log.operation} ·{' '}
                    {operationResultLabel(log.result)}
                    {log.reason ? ` · ${log.reason}` : ''}
                    {log.message ? ` · ${log.message}` : ''}
                  </li>
                ))}
              </ul>
            </>
          ) : null}
        </Section>
      ) : null}
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
