'use client';

import { useCallback, useEffect, useState } from 'react';
import { errorText } from '../inventory/inventory-model';
import {
  archiveAdKeyword,
  bulkStatusLabel,
  bulkItemStatusLabel,
  createAdBulkAction,
  createAdNegative,
  daysAgoUtc,
  emptyBulkActionText,
  emptyFrequencyText,
  emptyKeywordText,
  emptyNegativeText,
  emptyReportText,
  emptySearchTermText,
  emptySuggestionText,
  evidenceLines,
  fetchAdBulkActions,
  fetchAdKeywordCapabilities,
  fetchAdKeywordDetail,
  fetchAdKeywordFilterOptions,
  fetchAdKeywordFrequency,
  fetchAdKeywordReport,
  fetchAdKeywords,
  fetchAdNegatives,
  fetchAdNegativeSuggestions,
  fetchAdSearchTerms,
  guardrailSummary,
  ingestAdKeywordMetrics,
  ingestAdSearchTerms,
  internalTypeLabel,
  keywordStatusLabel,
  money,
  negativeStatusLabel,
  newIdempotencyKey,
  quantity,
  rankText,
  ratioRaw,
  recordAdNegativeResult,
  saveAdKeyword,
  suggestionRuleLabel,
  syncStatusText,
  todayUtc,
  validateBulkAction,
  validateKeywordInput,
  validateMetricBatch,
  validateNegative,
  validateSearchTermBatch,
  wasteTone,
} from './ad-keywords-model';
import type {
  AdBulkActionPage,
  AdBulkActionRequest,
  AdBulkActionResult,
  AdKeywordCapabilities,
  AdKeywordDetail,
  AdKeywordFilterOptions,
  AdKeywordFrequencyPage,
  AdKeywordInput,
  AdKeywordPage,
  AdKeywordReport,
  AdNegativeInput,
  AdNegativePage,
  AdNegativeSuggestionPage,
  AdSearchTermPage,
} from './ad-keywords-model';

/**
 * 14.1.3.1 关键词管理。
 *
 * 🔴 页面顶部四句话不是提示语，是这一页的设计前提：
 *
 * 1. **搜索词报告是发现浪费的入口**。所以「否定词建议」区块放在「搜索词报告」旁边，
 *    每条建议都显示曝光 / 点击 / 转化 / 花费 / 销售额 / ACOS 的**实际数值** ——
 *    运营要能回答「凭什么否定它」，而不是拿到一个光秃秃的词表。
 * 2. **数据截至时间要显式**。页面顶部常驻「数据截至 X / 从未同步过」，
 *    区分「没跑这个词」与「还没同步到数据」—— 页面上的 0 到底是哪个意思，答案在这里。
 * 3. **批量调价有护栏**。护栏的具体数值从服务端能力声明里取（前端不重算），
 *    逐条执行结果（成了 / 没成 / 为什么没成）逐条显示。
 * 4. **归一类型与原始类型并存**。每一行都同时显示「原始标记 → 归一类型」；
 *    `UNMAPPED` 是显式值（上游没给归一口径），不是「没有这种广告」。
 *
 * 🔴 竞价策略引擎（14.2.1.1）与投放定位（14.1.3.2）**本模块不实现**，
 * 能力表里 available=false + ownerModule —— 页面据此显示「这归谁管」，
 * 而不是显示一个必然失败的按钮。
 */
export function AdKeywordsWorkspace() {
  const [capabilities, setCapabilities] = useState<AdKeywordCapabilities | null>(null);
  const [options, setOptions] = useState<AdKeywordFilterOptions | null>(null);
  const [keywords, setKeywords] = useState<AdKeywordPage | null>(null);
  const [detail, setDetail] = useState<AdKeywordDetail | null>(null);
  const [searchTerms, setSearchTerms] = useState<AdSearchTermPage | null>(null);
  const [suggestions, setSuggestions] = useState<AdNegativeSuggestionPage | null>(null);
  const [negatives, setNegatives] = useState<AdNegativePage | null>(null);
  const [bulkActions, setBulkActions] = useState<AdBulkActionPage | null>(null);
  const [bulkResult, setBulkResult] = useState<AdBulkActionResult | null>(null);
  const [report, setReport] = useState<AdKeywordReport | null>(null);
  const [frequency, setFrequency] = useState<AdKeywordFrequencyPage | null>(null);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  // 筛选
  const [platformCode, setPlatformCode] = useState('');
  const [shopCode, setShopCode] = useState('');
  const [internalAdType, setInternalAdType] = useState('');
  const [keywordFilter, setKeywordFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [wastedOnly, setWastedOnly] = useState(false);
  const [page, setPage] = useState(0);

  // 表单
  const [keywordInput, setKeywordInput] = useState<AdKeywordInput>({
    platformId: '',
    shopId: '',
    externalCampaignId: '',
    externalKeywordId: '',
    keyword: '',
    platformAdType: '',
    internalAdType: undefined,
    bid: undefined,
    bidCurrency: '',
    status: 'ACTIVE',
    listingKeywordId: null,
    note: '',
  });
  const [keywordErrors, setKeywordErrors] = useState<string[]>([]);
  const [editingKeywordId, setEditingKeywordId] = useState<string | null>(null);

  const [metricForm, setMetricForm] = useState({
    keywordId: '',
    bucketDate: todayUtc(),
    impressions: '0',
    clicks: '0',
    conversions: '0',
    spend: '0',
    sales: '0',
    currency: 'USD',
    syncedAt: new Date().toISOString(),
    idempotencyKey: newIdempotencyKey('metric'),
  });
  const [metricErrors, setMetricErrors] = useState<string[]>([]);
  const [metricResult, setMetricResult] = useState<string>('');

  const [termForm, setTermForm] = useState({
    keywordId: '',
    bucketDate: todayUtc(),
    searchTerm: '',
    externalCampaignId: '',
    impressions: '0',
    clicks: '0',
    conversions: '0',
    spend: '0',
    sales: '0',
    currency: 'USD',
    syncedAt: new Date().toISOString(),
    idempotencyKey: newIdempotencyKey('term'),
  });
  const [termErrors, setTermErrors] = useState<string[]>([]);
  const [termResult, setTermResult] = useState<string>('');

  const [negativeForm, setNegativeForm] = useState<AdNegativeInput>({
    keywordId: '',
    negationText: '',
    negationType: 'NEGATIVE_EXACT',
    externalCampaignId: '',
    reason: '',
    suggestedBy: 'MANUAL',
  });
  const [negativeErrors, setNegativeErrors] = useState<string[]>([]);

  const [bulkForm, setBulkForm] = useState<AdBulkActionRequest>({
    actionType: 'BID_ADJUST',
    shopId: '',
    bidMode: 'PERCENT',
    currency: 'USD',
    targets: [],
    negationType: 'NEGATIVE_EXACT',
    idempotencyKey: newIdempotencyKey('bulk'),
    note: '',
  });
  const [bulkErrors, setBulkErrors] = useState<string[]>([]);

  const loadAll = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, opts, kw, terms, sugg, negs, bulks, rep, freq] = await Promise.all([
        fetchAdKeywordCapabilities(),
        fetchAdKeywordFilterOptions(),
        fetchAdKeywords({
          platformCode,
          shopCode,
          internalAdType,
          keyword: keywordFilter,
          status: statusFilter,
          page,
          size: 50,
        }),
        fetchAdSearchTerms({ wasted: wastedOnly ? 'true' : '', page: 0, size: 50 }),
        fetchAdNegativeSuggestions({ page: 0, size: 50 }),
        fetchAdNegatives({}),
        fetchAdBulkActions({}),
        fetchAdKeywordReport({ dimension: 'PLATFORM', page: 0, size: 50 }),
        fetchAdKeywordFrequency({ page: 0, size: 50 }),
      ]);
      setCapabilities(caps);
      setOptions(opts);
      setKeywords(kw);
      setSearchTerms(terms);
      setSuggestions(sugg);
      setNegatives(negs);
      setBulkActions(bulks);
      setReport(rep);
      setFrequency(freq);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [platformCode, shopCode, internalAdType, keywordFilter, statusFilter, page, wastedOnly]);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  // ---- 关键词登记 / 编辑 ----
  async function onSaveKeyword() {
    const validation = validateKeywordInput(keywordInput);
    setKeywordErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      await saveAdKeyword(editingKeywordId, keywordInput);
      setEditingKeywordId(null);
      setKeywordInput({
        platformId: '',
        shopId: '',
        externalCampaignId: '',
        externalKeywordId: '',
        keyword: '',
        platformAdType: '',
        internalAdType: undefined,
        bid: undefined,
        bidCurrency: '',
        status: 'ACTIVE',
        listingKeywordId: null,
        note: '',
      });
      await loadAll();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onArchiveKeyword(id: string) {
    setBusy(true);
    setError('');
    try {
      await archiveAdKeyword(id);
      await loadAll();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onOpenDetail(id: string) {
    setBusy(true);
    setError('');
    try {
      setDetail(await fetchAdKeywordDetail(id));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  // ---- 指标同步 ----
  async function onIngestMetrics() {
    const request = {
      source: 'API',
      sourceRef: '',
      idempotencyKey: metricForm.idempotencyKey,
      note: '',
      metrics: [
        {
          keywordId: metricForm.keywordId,
          bucketDate: metricForm.bucketDate,
          impressions: Number(metricForm.impressions),
          clicks: Number(metricForm.clicks),
          conversions: Number(metricForm.conversions),
          spend: Number(metricForm.spend),
          sales: Number(metricForm.sales),
          currency: metricForm.currency,
          syncedAt: metricForm.syncedAt,
        },
      ],
    };
    const validation = validateMetricBatch(request);
    setMetricErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      const result = await ingestAdKeywordMetrics(request);
      setMetricResult(
        result.replayed
          ? `该批次此前已落库（幂等：本次不重放）。${result.skipped.join('；')}`
          : `已落库 ${result.appliedCount} 行，跳过 ${result.skippedCount} 行。` +
              (result.skipped.length ? ` 跳过原因：${result.skipped.join('；')}` : ''),
      );
      setMetricForm((prev) => ({ ...prev, idempotencyKey: newIdempotencyKey('metric') }));
      await loadAll();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  // ---- 搜索词同步 ----
  async function onIngestTerms() {
    const request = {
      source: 'API',
      sourceRef: '',
      idempotencyKey: termForm.idempotencyKey,
      note: '',
      terms: [
        {
          keywordId: termForm.keywordId,
          bucketDate: termForm.bucketDate,
          searchTerm: termForm.searchTerm,
          externalCampaignId: termForm.externalCampaignId,
          impressions: Number(termForm.impressions),
          clicks: Number(termForm.clicks),
          conversions: Number(termForm.conversions),
          spend: Number(termForm.spend),
          sales: Number(termForm.sales),
          currency: termForm.currency,
          syncedAt: termForm.syncedAt,
        },
      ],
    };
    const validation = validateSearchTermBatch(request);
    setTermErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      const result = await ingestAdSearchTerms(request);
      setTermResult(
        result.replayed
          ? `该批次此前已落库（幂等：本次不重放）。${result.skipped.join('；')}`
          : `已落库 ${result.appliedCount} 行，跳过 ${result.skippedCount} 行。` +
              (result.skipped.length ? ` 跳过原因：${result.skipped.join('；')}` : ''),
      );
      setTermForm((prev) => ({ ...prev, idempotencyKey: newIdempotencyKey('term') }));
      await loadAll();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  // ---- 否定 ----
  async function onCreateNegative() {
    const validation = validateNegative(negativeForm);
    setNegativeErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      const saved = await createAdNegative(negativeForm);
      setNegativeForm({
        keywordId: '',
        negationText: '',
        negationType: 'NEGATIVE_EXACT',
        externalCampaignId: '',
        reason: '',
        suggestedBy: 'MANUAL',
      });
      setNegatives(await fetchAdNegatives({}));
      setError('');
      void saved;
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  /** 🔴 采纳一条建议：依据由**服务端现算**（页面不自己编依据数字）。 */
  async function onAcceptSuggestion(
    keywordId: string,
    searchTerm: string,
    externalCampaignId: string,
    reason: string,
  ) {
    setBusy(true);
    setError('');
    try {
      await createAdNegative({
        keywordId,
        negationText: searchTerm,
        negationType: 'NEGATIVE_EXACT',
        externalCampaignId,
        reason,
        suggestedBy: 'SYSTEM_SUGGESTION',
      });
      setNegatives(await fetchAdNegatives({}));
      setSuggestions(await fetchAdNegativeSuggestions({ page: 0, size: 50 }));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onRecordNegativeResult(id: string, applied: boolean) {
    setBusy(true);
    setError('');
    try {
      await recordAdNegativeResult(id, applied, applied ? '平台已接受' : '平台拒绝');
      setNegatives(await fetchAdNegatives({}));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  // ---- 批量调价与否定 ----
  async function onBulkAction() {
    const validation = validateBulkAction(bulkForm);
    setBulkErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      const result = await createAdBulkAction(bulkForm);
      setBulkResult(result);
      setBulkForm((prev) => ({ ...prev, idempotencyKey: newIdempotencyKey('bulk') }));
      setBulkActions(await fetchAdBulkActions({}));
      await loadAll();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  const undelivered = (capabilities?.dimensions ?? []).filter((d) => !d.available);
  const delivered = (capabilities?.dimensions ?? []).filter((d) => d.available);
  const totalPages = keywords ? Math.max(1, Math.ceil(keywords.total / keywords.size)) : 1;

  return (
    <main className="flex flex-col gap-6 p-6">
      <header className="flex flex-col gap-2">
        <h1 className="text-2xl font-semibold">关键词管理</h1>
        <p className="text-muted-foreground max-w-4xl text-sm">
          关键词表现（展示 / 点击 / 转化 / CPC / ACOS）、搜索词报告、否定词建议、词频与排名，
          以及带护栏的批量调价与否定。
          <strong>搜索词报告是发现浪费的入口</strong>：高曝光零转化词应尽快否定，否则预算持续流失。
        </p>
        {/* 🔴 数据截至时间常驻页面顶部 —— 页面上的 0 是「没数据」还是「没花钱」，答案在这里 */}
        <p className="text-muted-foreground text-xs">
          {keywords ? syncStatusText(keywords) : '正在读取同步状态…'}
        </p>
      </header>

      {error ? <p className="text-destructive text-sm">{error}</p> : null}
      {loading ? (
        <p className="text-muted-foreground text-sm">正在加载关键词、搜索词报告与否定建议…</p>
      ) : null}

      {/* ================= 口径与能力 ================= */}
      <Section title="口径与能力（这一页的数字是怎么来的）">
        {capabilities ? (
          <>
            <p className="text-muted-foreground text-xs">{capabilities.metricDefinitions}</p>
            <p className="text-muted-foreground text-xs">{capabilities.frequencyDefinitions}</p>
            <p className="text-muted-foreground text-xs">{capabilities.negativeEvidenceNote}</p>
            <p className="text-muted-foreground text-xs">
              🔴 {capabilities.guardrailNote} {guardrailSummary(capabilities.guardrailLimits)}
            </p>
            <p className="text-muted-foreground text-xs">{capabilities.relationNote}</p>

            <h3 className="mt-2 text-sm font-medium">已交付</h3>
            <ul className="flex flex-col gap-1 text-xs">
              {delivered.map((d) => (
                <li key={d.dimension}>
                  <strong>{d.dimension}</strong> — {d.description}
                </li>
              ))}
            </ul>

            {/* 🔴 未交付段明账：显示「这归谁管」，而不是显示一个必然失败的按钮 */}
            <h3 className="mt-2 text-sm font-medium">未交付（归谁管）</h3>
            <ul className="flex flex-col gap-1 text-xs">
              {undelivered.map((d) => (
                <li key={d.dimension}>
                  <strong>{d.dimension}</strong> · 归 <strong>{d.ownerModule}</strong> — {d.impact}
                </li>
              ))}
            </ul>
          </>
        ) : null}
      </Section>

      {/* ================= 关键词列表 ================= */}
      <Section title="投放关键词（真分页 + 筛选）">
        <div className="flex flex-wrap items-end gap-2">
          <Field label="平台">
            <select
              value={platformCode}
              onChange={(e) => setPlatformCode(e.target.value)}
              className="rounded border px-2 py-1 text-sm"
            >
              <option value="">全部</option>
              {(options?.platforms ?? []).map((p) => (
                <option key={p.code} value={p.code}>
                  {p.name}
                </option>
              ))}
            </select>
          </Field>
          <Field label="店铺">
            <select
              value={shopCode}
              onChange={(e) => setShopCode(e.target.value)}
              className="rounded border px-2 py-1 text-sm"
            >
              <option value="">全部</option>
              {(options?.shops ?? []).map((s) => (
                <option key={s.code} value={s.code}>
                  {s.name}
                </option>
              ))}
            </select>
          </Field>
          <Field label="归一类型">
            <select
              value={internalAdType}
              onChange={(e) => setInternalAdType(e.target.value)}
              className="rounded border px-2 py-1 text-sm"
            >
              <option value="">全部</option>
              {(options?.adTypes ?? []).map((t) => (
                <option key={t.code} value={t.code}>
                  {t.name}
                </option>
              ))}
            </select>
          </Field>
          <Field label="状态">
            <select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              className="rounded border px-2 py-1 text-sm"
            >
              <option value="">全部</option>
              {(capabilities?.keywordStatuses ?? []).map((s) => (
                <option key={s} value={s}>
                  {keywordStatusLabel(s)}
                </option>
              ))}
            </select>
          </Field>
          <Field label="关键词">
            <input
              value={keywordFilter}
              onChange={(e) => setKeywordFilter(e.target.value)}
              className="rounded border px-2 py-1 text-sm"
              placeholder="模糊搜索"
            />
          </Field>
        </div>

        {!loading && (keywords?.items.length ?? 0) === 0 ? (
          <p className="text-muted-foreground text-sm">
            {emptyKeywordText(keywords?.total ?? 0, keywordFilter)}
          </p>
        ) : null}

        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left">
                <th className="py-1 pr-2">关键词</th>
                <th className="py-1 pr-2">归一类型 / 原始标记</th>
                <th className="py-1 pr-2">活动 / 店铺</th>
                <th className="py-1 pr-2">竞价</th>
                <th className="py-1 pr-2">展示</th>
                <th className="py-1 pr-2">点击</th>
                <th className="py-1 pr-2">转化</th>
                <th className="py-1 pr-2">花费</th>
                <th className="py-1 pr-2">销售额</th>
                <th className="py-1 pr-2">ACOS</th>
                <th className="py-1 pr-2">CPC</th>
                <th className="py-1 pr-2">名次（4.5.1.2）</th>
                <th className="py-1 pr-2">词频</th>
                <th className="py-1 pr-2">状态</th>
                <th className="py-1 pr-2">操作</th>
              </tr>
            </thead>
            <tbody>
              {(keywords?.items ?? []).map((row) => (
                <tr key={row.keywordId} className="border-b align-top">
                  <td className="py-1 pr-2">
                    <div>{row.keyword}</div>
                    <div className="text-muted-foreground text-xs">
                      {row.externalKeywordId} · 数据截至{' '}
                      {row.metricAsOf
                        ? new Date(row.metricAsOf).toLocaleString('zh-CN', { hour12: false })
                        : '—'}
                      {row.hasMetrics ? '' : '（还没同步过指标）'}
                    </div>
                  </td>
                  {/* 🔴 归一类型与原始类型并存 */}
                  <td className="py-1 pr-2">
                    <div>{internalTypeLabel(row.internalAdType)}</div>
                    <code className="text-muted-foreground text-xs">{row.platformAdType}</code>
                  </td>
                  <td className="py-1 pr-2 text-xs">
                    <div>{row.externalCampaignId}</div>
                    <div className="text-muted-foreground">{row.shopCode}</div>
                  </td>
                  <td className="py-1 pr-2">{money(row.bid, row.bidCurrency)}</td>
                  <td className="py-1 pr-2">{quantity(row.impressions)}</td>
                  <td className="py-1 pr-2">{quantity(row.clicks)}</td>
                  <td className="py-1 pr-2">{quantity(row.conversions)}</td>
                  <td className="py-1 pr-2">{money(row.spend, row.currency)}</td>
                  <td className="py-1 pr-2">{money(row.sales, row.currency)}</td>
                  <td className="py-1 pr-2">{ratioRaw(row.acos)}</td>
                  <td className="py-1 pr-2">{ratioRaw(row.cpc)}</td>
                  <td className="py-1 pr-2 text-xs">
                    <div>自然 {rankText(row.naturalRank)}</div>
                    <div>广告 {rankText(row.adRank)}</div>
                    {!row.listingKeywordId ? (
                      <div className="text-muted-foreground">未关联 4.5.1.2 关键词</div>
                    ) : null}
                  </td>
                  <td className="py-1 pr-2 text-xs">
                    <div>{row.frequency} 个商品</div>
                    <div className="text-muted-foreground">
                      {row.frequencyListingCount} 个 listing
                    </div>
                  </td>
                  <td className="py-1 pr-2 text-xs">
                    <div>{keywordStatusLabel(row.status)}</div>
                    {row.negated ? <div className="text-destructive">已否定</div> : null}
                  </td>
                  <td className="py-1 pr-2 text-xs">
                    <button
                      type="button"
                      className="mr-2 underline"
                      onClick={() => onOpenDetail(row.keywordId)}
                    >
                      明细
                    </button>
                    <button
                      type="button"
                      className="underline"
                      onClick={() => onArchiveKeyword(row.keywordId)}
                    >
                      归档
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {keywords ? (
          <div className="flex items-center gap-2 text-sm">
            <button
              type="button"
              className="rounded border px-2 py-1"
              disabled={page <= 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
            >
              上一页
            </button>
            <span>
              第 {keywords.page + 1} / {totalPages} 页 · 共 {quantity(keywords.total)} 条
            </span>
            <button
              type="button"
              className="rounded border px-2 py-1"
              disabled={page + 1 >= totalPages}
              onClick={() => setPage((p) => p + 1)}
            >
              下一页
            </button>
          </div>
        ) : null}
      </Section>

      {/* ================= 关键词明细 ================= */}
      {detail ? (
        <Section title={`关键词明细：${detail.keyword}`}>
          <dl className="grid grid-cols-2 gap-2 text-sm md:grid-cols-4">
            <Stat label="归一类型" value={internalTypeLabel(detail.internalAdType)} />
            <Stat label="原始类型标记" value={detail.platformAdType} />
            <Stat label="当前竞价" value={money(detail.bid, detail.bidCurrency)} />
            <Stat label="状态" value={keywordStatusLabel(detail.status)} />
            <Stat
              label="曝光 / 点击 / 转化"
              value={`${quantity(detail.impressions)} / ${quantity(detail.clicks)} / ${quantity(detail.conversions)}`}
            />
            <Stat
              label="花费 / 销售额"
              value={`${money(detail.spend, detail.currency)} / ${money(detail.sales, detail.currency)}`}
            />
            <Stat label="ACOS / CPC" value={`${ratioRaw(detail.acos)} / ${ratioRaw(detail.cpc)}`} />
            <Stat
              label="名次（4.5.1.2）"
              value={`自然 ${rankText(detail.naturalRank)} / 广告 ${rankText(detail.adRank)}`}
            />
            <Stat label="词频" value={`${detail.frequency} 个商品`} />
            <Stat label="搜索词条数" value={quantity(detail.searchTermCount)} />
            <Stat label="其中浪费词" value={quantity(detail.wastedSearchTermCount)} />
            <Stat label="否定记录" value={quantity(detail.negativeCount)} />
          </dl>
          <p className="text-muted-foreground text-xs">{detail.syncNote}</p>
          {!detail.hasData ? (
            <p className="text-muted-foreground text-sm">{detail.emptyReason}</p>
          ) : null}
        </Section>
      ) : null}

      {/* ================= 登记投放关键词 ================= */}
      <Section title="登记 / 改一个投放关键词">
        <div className="grid grid-cols-1 gap-2 text-sm md:grid-cols-3">
          <Field label="平台">
            <select
              value={keywordInput.platformId}
              onChange={(e) => setKeywordInput({ ...keywordInput, platformId: e.target.value })}
              className="w-full rounded border px-2 py-1"
            >
              <option value="">请选择</option>
              {(options?.platforms ?? []).map((p) => (
                <option key={p.code} value={p.code}>
                  {p.name}
                </option>
              ))}
            </select>
          </Field>
          <Field label="店铺">
            <select
              value={keywordInput.shopId}
              onChange={(e) => setKeywordInput({ ...keywordInput, shopId: e.target.value })}
              className="w-full rounded border px-2 py-1"
            >
              <option value="">请选择</option>
              {(options?.shops ?? []).map((s) => (
                <option key={s.code} value={s.code}>
                  {s.name}
                </option>
              ))}
            </select>
          </Field>
          <Field label="外部活动 id">
            <input
              value={keywordInput.externalCampaignId}
              onChange={(e) =>
                setKeywordInput({ ...keywordInput, externalCampaignId: e.target.value })
              }
              className="w-full rounded border px-2 py-1"
              placeholder="14.1.1.1 未交付，用外部引用"
            />
          </Field>
          <Field label="外部关键词 id">
            <input
              value={keywordInput.externalKeywordId}
              onChange={(e) =>
                setKeywordInput({ ...keywordInput, externalKeywordId: e.target.value })
              }
              className="w-full rounded border px-2 py-1"
            />
          </Field>
          <Field label="关键词原文">
            <input
              value={keywordInput.keyword}
              onChange={(e) => setKeywordInput({ ...keywordInput, keyword: e.target.value })}
              className="w-full rounded border px-2 py-1"
            />
          </Field>
          <Field label="原始平台类型标记">
            <input
              value={keywordInput.platformAdType}
              onChange={(e) => setKeywordInput({ ...keywordInput, platformAdType: e.target.value })}
              className="w-full rounded border px-2 py-1"
              placeholder="照抄平台侧的标记"
            />
          </Field>
          <Field label="竞价（可空）">
            <input
              type="number"
              step="0.01"
              value={keywordInput.bid ?? ''}
              onChange={(e) =>
                setKeywordInput({
                  ...keywordInput,
                  bid: e.target.value === '' ? undefined : Number(e.target.value),
                })
              }
              className="w-full rounded border px-2 py-1"
            />
          </Field>
          <Field label="竞价币种">
            <input
              value={keywordInput.bidCurrency ?? ''}
              onChange={(e) => setKeywordInput({ ...keywordInput, bidCurrency: e.target.value })}
              className="w-full rounded border px-2 py-1"
              placeholder="给了竞价就必须给币种"
            />
          </Field>
          <Field label="状态">
            <select
              value={keywordInput.status ?? 'ACTIVE'}
              onChange={(e) => setKeywordInput({ ...keywordInput, status: e.target.value })}
              className="w-full rounded border px-2 py-1"
            >
              {(capabilities?.keywordStatuses ?? []).map((s) => (
                <option key={s} value={s}>
                  {keywordStatusLabel(s)}
                </option>
              ))}
            </select>
          </Field>
        </div>
        <p className="text-muted-foreground text-xs">
          归一类型由服务端按 14.1.2.2 的两层解析带出（这里不填、也不手填归一结果）。
        </p>
        <ErrorList errors={keywordErrors} />
        <div className="flex gap-2">
          <button
            type="button"
            className="rounded border px-3 py-1 text-sm"
            onClick={onSaveKeyword}
            disabled={busy}
          >
            {editingKeywordId ? '保存修改' : '登记关键词'}
          </button>
          {editingKeywordId ? (
            <button
              type="button"
              className="rounded border px-3 py-1 text-sm"
              onClick={() => {
                setEditingKeywordId(null);
                setKeywordErrors([]);
              }}
            >
              取消
            </button>
          ) : null}
        </div>
      </Section>

      {/* ================= 同步关键词表现 ================= */}
      <Section title="同步关键词表现（真实写入口 · 批次幂等）">
        <div className="grid grid-cols-1 gap-2 text-sm md:grid-cols-4">
          <Field label="关键词">
            <select
              value={metricForm.keywordId}
              onChange={(e) => setMetricForm({ ...metricForm, keywordId: e.target.value })}
              className="w-full rounded border px-2 py-1"
            >
              <option value="">请选择</option>
              {(keywords?.items ?? []).map((k) => (
                <option key={k.keywordId} value={k.keywordId}>
                  {k.keyword}
                </option>
              ))}
            </select>
          </Field>
          <Field label="指标日（UTC）">
            <input
              type="date"
              value={metricForm.bucketDate}
              onChange={(e) => setMetricForm({ ...metricForm, bucketDate: e.target.value })}
              className="w-full rounded border px-2 py-1"
            />
          </Field>
          <Field label="展示">
            <input
              type="number"
              value={metricForm.impressions}
              onChange={(e) => setMetricForm({ ...metricForm, impressions: e.target.value })}
              className="w-full rounded border px-2 py-1"
            />
          </Field>
          <Field label="点击">
            <input
              type="number"
              value={metricForm.clicks}
              onChange={(e) => setMetricForm({ ...metricForm, clicks: e.target.value })}
              className="w-full rounded border px-2 py-1"
            />
          </Field>
          <Field label="转化">
            <input
              type="number"
              value={metricForm.conversions}
              onChange={(e) => setMetricForm({ ...metricForm, conversions: e.target.value })}
              className="w-full rounded border px-2 py-1"
            />
          </Field>
          <Field label="花费">
            <input
              type="number"
              step="0.0001"
              value={metricForm.spend}
              onChange={(e) => setMetricForm({ ...metricForm, spend: e.target.value })}
              className="w-full rounded border px-2 py-1"
            />
          </Field>
          <Field label="销售额">
            <input
              type="number"
              step="0.0001"
              value={metricForm.sales}
              onChange={(e) => setMetricForm({ ...metricForm, sales: e.target.value })}
              className="w-full rounded border px-2 py-1"
            />
          </Field>
          <Field label="币种">
            <input
              value={metricForm.currency}
              onChange={(e) => setMetricForm({ ...metricForm, currency: e.target.value })}
              className="w-full rounded border px-2 py-1"
            />
          </Field>
          <Field label="数据截至时间">
            <input
              type="datetime-local"
              value={metricForm.syncedAt.slice(0, 16)}
              onChange={(e) =>
                setMetricForm({ ...metricForm, syncedAt: new Date(e.target.value).toISOString() })
              }
              className="w-full rounded border px-2 py-1"
            />
          </Field>
          <Field label="幂等键">
            <input
              value={metricForm.idempotencyKey}
              onChange={(e) => setMetricForm({ ...metricForm, idempotencyKey: e.target.value })}
              className="w-full rounded border px-2 py-1"
            />
          </Field>
        </div>
        <ErrorList errors={metricErrors} />
        <button
          type="button"
          className="rounded border px-3 py-1 text-sm"
          onClick={onIngestMetrics}
          disabled={busy}
        >
          落库这一行指标
        </button>
        {metricResult ? <p className="text-muted-foreground text-xs">{metricResult}</p> : null}
      </Section>

      {/* ================= 同步搜索词报告 ================= */}
      <Section title="同步搜索词报告（真实写入口 · 发现浪费的入口）">
        <div className="grid grid-cols-1 gap-2 text-sm md:grid-cols-4">
          <Field label="关键词">
            <select
              value={termForm.keywordId}
              onChange={(e) => setTermForm({ ...termForm, keywordId: e.target.value })}
              className="w-full rounded border px-2 py-1"
            >
              <option value="">请选择</option>
              {(keywords?.items ?? []).map((k) => (
                <option key={k.keywordId} value={k.keywordId}>
                  {k.keyword}
                </option>
              ))}
            </select>
          </Field>
          <Field label="指标日（UTC）">
            <input
              type="date"
              value={termForm.bucketDate}
              onChange={(e) => setTermForm({ ...termForm, bucketDate: e.target.value })}
              className="w-full rounded border px-2 py-1"
            />
          </Field>
          <Field label="搜索词原文">
            <input
              value={termForm.searchTerm}
              onChange={(e) => setTermForm({ ...termForm, searchTerm: e.target.value })}
              className="w-full rounded border px-2 py-1"
              placeholder="用户实际搜出来的词"
            />
          </Field>
          <Field label="外部活动 id">
            <input
              value={termForm.externalCampaignId}
              onChange={(e) => setTermForm({ ...termForm, externalCampaignId: e.target.value })}
              className="w-full rounded border px-2 py-1"
            />
          </Field>
          <Field label="展示">
            <input
              type="number"
              value={termForm.impressions}
              onChange={(e) => setTermForm({ ...termForm, impressions: e.target.value })}
              className="w-full rounded border px-2 py-1"
            />
          </Field>
          <Field label="点击">
            <input
              type="number"
              value={termForm.clicks}
              onChange={(e) => setTermForm({ ...termForm, clicks: e.target.value })}
              className="w-full rounded border px-2 py-1"
            />
          </Field>
          <Field label="转化">
            <input
              type="number"
              value={termForm.conversions}
              onChange={(e) => setTermForm({ ...termForm, conversions: e.target.value })}
              className="w-full rounded border px-2 py-1"
            />
          </Field>
          <Field label="花费">
            <input
              type="number"
              step="0.0001"
              value={termForm.spend}
              onChange={(e) => setTermForm({ ...termForm, spend: e.target.value })}
              className="w-full rounded border px-2 py-1"
            />
          </Field>
          <Field label="销售额">
            <input
              type="number"
              step="0.0001"
              value={termForm.sales}
              onChange={(e) => setTermForm({ ...termForm, sales: e.target.value })}
              className="w-full rounded border px-2 py-1"
            />
          </Field>
          <Field label="币种">
            <input
              value={termForm.currency}
              onChange={(e) => setTermForm({ ...termForm, currency: e.target.value })}
              className="w-full rounded border px-2 py-1"
            />
          </Field>
          <Field label="幂等键">
            <input
              value={termForm.idempotencyKey}
              onChange={(e) => setTermForm({ ...termForm, idempotencyKey: e.target.value })}
              className="w-full rounded border px-2 py-1"
            />
          </Field>
        </div>
        <ErrorList errors={termErrors} />
        <button
          type="button"
          className="rounded border px-3 py-1 text-sm"
          onClick={onIngestTerms}
          disabled={busy}
        >
          落库这一行搜索词
        </button>
        {termResult ? <p className="text-muted-foreground text-xs">{termResult}</p> : null}
      </Section>

      {/* ================= 搜索词报告 ================= */}
      <Section title="搜索词报告（发现浪费的入口）">
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={wastedOnly}
            onChange={(e) => setWastedOnly(e.target.checked)}
          />
          只看高曝光零转化（浪费）
        </label>
        <p className="text-muted-foreground text-xs">
          区间内高曝光零转化的搜索词共 {quantity(searchTerms?.wastedCount ?? 0)} 条。
        </p>
        {!loading && (searchTerms?.items.length ?? 0) === 0 ? (
          <p className="text-muted-foreground text-sm">{emptySearchTermText(wastedOnly)}</p>
        ) : null}
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left">
                <th className="py-1 pr-2">搜索词</th>
                <th className="py-1 pr-2">投放词</th>
                <th className="py-1 pr-2">展示</th>
                <th className="py-1 pr-2">点击</th>
                <th className="py-1 pr-2">转化</th>
                <th className="py-1 pr-2">花费</th>
                <th className="py-1 pr-2">销售额</th>
                <th className="py-1 pr-2">ACOS</th>
                <th className="py-1 pr-2">判定</th>
              </tr>
            </thead>
            <tbody>
              {(searchTerms?.items ?? []).map((row, index) => (
                <tr key={`${row.keywordId}-${row.searchTerm}-${index}`} className="border-b">
                  <td className="py-1 pr-2">{row.searchTerm}</td>
                  <td className="text-muted-foreground py-1 pr-2">{row.keyword}</td>
                  <td className="py-1 pr-2">{quantity(row.impressions)}</td>
                  <td className="py-1 pr-2">{quantity(row.clicks)}</td>
                  <td className="py-1 pr-2">{quantity(row.conversions)}</td>
                  <td className="py-1 pr-2">{money(row.spend, row.currency)}</td>
                  <td className="py-1 pr-2">{money(row.sales, row.currency)}</td>
                  <td className="py-1 pr-2">{ratioRaw(row.acos)}</td>
                  <td className={`py-1 pr-2 ${wasteTone(row.wasted)}`}>
                    {row.wasted ? '高曝光零转化' : '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>

      {/* ================= 否定词建议 ================= */}
      <Section title="否定词建议（带依据数值）">
        {suggestions ? (
          <p className="text-muted-foreground text-xs">{suggestions.ruleNote}</p>
        ) : null}
        {!loading && (suggestions?.items.length ?? 0) === 0 ? (
          <p className="text-muted-foreground text-sm">{emptySuggestionText()}</p>
        ) : null}
        <ul className="flex flex-col gap-2">
          {(suggestions?.items ?? []).map((s, index) => (
            <li
              key={`${s.keywordId}-${s.searchTerm}-${index}`}
              className="flex flex-col gap-1 rounded border p-3"
            >
              <div className="flex items-center gap-2">
                <strong>{s.searchTerm}</strong>
                <span className="rounded border px-2 py-0.5 text-xs">
                  {suggestionRuleLabel(s.rule)}
                </span>
                <span className="text-muted-foreground text-xs">投放词：{s.keyword}</span>
              </div>
              {/* 🔴 依据数值必须看得见 —— 不能只丢一个词表 */}
              <p className="text-xs">
                曝光 {quantity(s.impressions)} · 点击 {quantity(s.clicks)} · 转化{' '}
                {quantity(s.conversions)} · 花费 {money(s.spend, s.currency)} · 销售额{' '}
                {money(s.sales, s.currency)} · ACOS {ratioRaw(s.acos)} · CPC {ratioRaw(s.cpc)}
              </p>
              <p className="text-muted-foreground text-xs">{s.reason}</p>
              <button
                type="button"
                className="self-start rounded border px-2 py-1 text-xs"
                disabled={busy || s.negated}
                onClick={() =>
                  onAcceptSuggestion(
                    s.keywordId,
                    s.searchTerm,
                    s.externalCampaignId,
                    `采纳系统建议：${s.reason}`,
                  )
                }
              >
                采纳并否定
              </button>
            </li>
          ))}
        </ul>
      </Section>

      {/* ================= 否定记录 ================= */}
      <Section title="否定记录（依据可追溯）">
        <div className="grid grid-cols-1 gap-2 text-sm md:grid-cols-3">
          <Field label="关键词 id">
            <input
              value={negativeForm.keywordId ?? ''}
              onChange={(e) => setNegativeForm({ ...negativeForm, keywordId: e.target.value })}
              className="w-full rounded border px-2 py-1"
              placeholder="从列表里复制"
            />
          </Field>
          <Field label="被否定的词">
            <input
              value={negativeForm.negationText}
              onChange={(e) => setNegativeForm({ ...negativeForm, negationText: e.target.value })}
              className="w-full rounded border px-2 py-1"
            />
          </Field>
          <Field label="外部活动 id">
            <input
              value={negativeForm.externalCampaignId}
              onChange={(e) =>
                setNegativeForm({ ...negativeForm, externalCampaignId: e.target.value })
              }
              className="w-full rounded border px-2 py-1"
            />
          </Field>
          <Field label="否定类型">
            <select
              value={negativeForm.negationType ?? 'NEGATIVE_EXACT'}
              onChange={(e) => setNegativeForm({ ...negativeForm, negationType: e.target.value })}
              className="w-full rounded border px-2 py-1"
            >
              {(capabilities?.negationTypes ?? []).map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
          </Field>
          <Field label="原因（必填）">
            <input
              value={negativeForm.reason}
              onChange={(e) => setNegativeForm({ ...negativeForm, reason: e.target.value })}
              className="w-full rounded border px-2 py-1"
            />
          </Field>
        </div>
        <ErrorList errors={negativeErrors} />
        <button
          type="button"
          className="rounded border px-3 py-1 text-sm"
          onClick={onCreateNegative}
          disabled={busy}
        >
          登记否定
        </button>
        <p className="text-muted-foreground text-xs">
          依据由服务端按与建议同一套口径现算 —— 页面不自己编依据数字。
        </p>

        {!loading && (negatives?.items.length ?? 0) === 0 ? (
          <p className="text-muted-foreground text-sm">{emptyNegativeText()}</p>
        ) : null}
        <ul className="flex flex-col gap-2">
          {(negatives?.items ?? []).map((n) => (
            <li key={n.negativeId} className="flex flex-col gap-1 rounded border p-3">
              <div className="flex items-center gap-2">
                <strong>{n.negationText}</strong>
                <span className="rounded border px-2 py-0.5 text-xs">
                  {negativeStatusLabel(n.status)}
                </span>
                <span className="text-muted-foreground text-xs">
                  {n.shopCode} · {n.externalCampaignId} · {n.negationType}
                </span>
              </div>
              <p className="text-xs">{n.reason}</p>
              <p className="text-muted-foreground text-xs">
                依据：{evidenceLines(n.evidence).join(' · ')}
              </p>
              {Object.keys(n.claimedEvidence ?? {}).length > 0 ? (
                <p className="text-muted-foreground text-xs">
                  操作员自述依据（仅审计留痕）：{JSON.stringify(n.claimedEvidence)}
                </p>
              ) : null}
              {n.status === 'DECIDED' ? (
                <div className="flex gap-2">
                  <button
                    type="button"
                    className="rounded border px-2 py-1 text-xs"
                    disabled={busy}
                    onClick={() => onRecordNegativeResult(n.negativeId, true)}
                  >
                    平台已接受
                  </button>
                  <button
                    type="button"
                    className="rounded border px-2 py-1 text-xs"
                    disabled={busy}
                    onClick={() => onRecordNegativeResult(n.negativeId, false)}
                  >
                    平台已拒绝
                  </button>
                </div>
              ) : null}
            </li>
          ))}
        </ul>
      </Section>

      {/* ================= 批量调价与否定 ================= */}
      <Section title="批量调价与否定（护栏 + 逐条执行结果）">
        <div className="grid grid-cols-1 gap-2 text-sm md:grid-cols-4">
          <Field label="动作类型">
            <select
              value={bulkForm.actionType}
              onChange={(e) => setBulkForm({ ...bulkForm, actionType: e.target.value })}
              className="w-full rounded border px-2 py-1"
            >
              {(capabilities?.bulkActionTypes ?? []).map((t) => (
                <option key={t} value={t}>
                  {t === 'BID_ADJUST' ? '批量调价' : '批量否定'}
                </option>
              ))}
            </select>
          </Field>
          <Field label="店铺">
            <select
              value={bulkForm.shopId}
              onChange={(e) => setBulkForm({ ...bulkForm, shopId: e.target.value })}
              className="w-full rounded border px-2 py-1"
            >
              <option value="">请选择</option>
              {(options?.shops ?? []).map((s) => (
                <option key={s.code} value={s.code}>
                  {s.name}
                </option>
              ))}
            </select>
          </Field>
          {bulkForm.actionType === 'BID_ADJUST' ? (
            <>
              <Field label="调价方式">
                <select
                  value={bulkForm.bidMode ?? 'PERCENT'}
                  onChange={(e) => setBulkForm({ ...bulkForm, bidMode: e.target.value })}
                  className="w-full rounded border px-2 py-1"
                >
                  <option value="PERCENT">百分比</option>
                  <option value="ABSOLUTE">直接设成某个竞价</option>
                </select>
              </Field>
              <Field label="币种">
                <input
                  value={bulkForm.currency ?? ''}
                  onChange={(e) => setBulkForm({ ...bulkForm, currency: e.target.value })}
                  className="w-full rounded border px-2 py-1"
                />
              </Field>
            </>
          ) : null}
          <Field label="幂等键">
            <input
              value={bulkForm.idempotencyKey}
              onChange={(e) => setBulkForm({ ...bulkForm, idempotencyKey: e.target.value })}
              className="w-full rounded border px-2 py-1"
            />
          </Field>
        </div>

        <div className="flex flex-col gap-1 text-sm">
          <span className="text-muted-foreground text-xs">
            勾选要调整的关键词（一次批量操作限定在同一店铺内，一个关键词只能出现一次）：
          </span>
          {(keywords?.items ?? [])
            .filter((k) => !bulkForm.shopId || k.shopId === bulkForm.shopId)
            .map((k) => (
              <label key={k.keywordId} className="flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={bulkForm.targets.some((t) => t.keywordId === k.keywordId)}
                  onChange={(e) => {
                    const exists = bulkForm.targets.some((t) => t.keywordId === k.keywordId);
                    setBulkForm({
                      ...bulkForm,
                      targets: exists
                        ? bulkForm.targets.filter((t) => t.keywordId !== k.keywordId)
                        : [
                            ...bulkForm.targets,
                            {
                              keywordId: k.keywordId,
                              bidValue: bulkForm.bidMode === 'ABSOLUTE' ? (k.bid ?? 0) : 10,
                            },
                          ],
                    });
                  }}
                />
                <span>
                  {k.keyword}（{k.shopCode}）· 当前竞价 {money(k.bid, k.bidCurrency)}
                </span>
                {bulkForm.actionType === 'BID_ADJUST' ? (
                  <input
                    type="number"
                    step="0.01"
                    value={
                      bulkForm.targets.find((t) => t.keywordId === k.keywordId)?.bidValue ?? ''
                    }
                    onChange={(e) =>
                      setBulkForm({
                        ...bulkForm,
                        targets: bulkForm.targets.map((t) =>
                          t.keywordId === k.keywordId
                            ? { ...t, bidValue: Number(e.target.value) }
                            : t,
                        ),
                      })
                    }
                    className="w-24 rounded border px-2 py-0.5"
                  />
                ) : null}
              </label>
            ))}
        </div>

        <ErrorList errors={bulkErrors} />
        <button
          type="button"
          className="rounded border px-3 py-1 text-sm"
          onClick={onBulkAction}
          disabled={busy}
        >
          执行批量操作
        </button>

        {bulkResult ? (
          <div className="flex flex-col gap-1 rounded border p-3">
            <p className="text-sm">
              任务 {bulkResult.actionId} ·{' '}
              {bulkResult.status === 'APPLIED'
                ? '全部成功'
                : bulkResult.status === 'PARTIAL'
                  ? '部分成功'
                  : bulkResult.status === 'REJECTED'
                    ? '整批拒绝'
                    : bulkResult.status}
              {bulkResult.replayed ? '（幂等：本次未执行）' : ''} · 成功 {bulkResult.appliedCount}{' '}
              条 / 失败 {bulkResult.failedCount} 条
            </p>
            {bulkResult.reason ? (
              <p className="text-destructive text-xs">{bulkResult.reason}</p>
            ) : null}
            {/* 🔴 逐条执行结果（成了 / 没成 / 为什么没成） */}
            <ul className="flex flex-col gap-1 text-xs">
              {bulkResult.items.map((item, index) => (
                <li key={`${item.keywordId}-${index}`}>
                  {bulkItemStatusLabel(item.status)} · {item.targetRef || item.keywordId}
                  {item.beforeBid !== null
                    ? ` · ${money(item.beforeBid)} → ${money(item.afterBid)}`
                    : ''}
                  {item.reason ? ` · ${item.reason}` : ''}
                </li>
              ))}
            </ul>
          </div>
        ) : null}
      </Section>

      {/* ================= 批量任务历史 ================= */}
      <Section title="批量调整任务历史">
        {!loading && (bulkActions?.items.length ?? 0) === 0 ? (
          <p className="text-muted-foreground text-sm">{emptyBulkActionText()}</p>
        ) : null}
        <ul className="flex flex-col gap-1 text-xs">
          {(bulkActions?.items ?? []).map((b) => (
            <li key={b.actionId} className="rounded border px-2 py-1">
              {new Date(b.createdAt).toLocaleString('zh-CN', { hour12: false })} · {b.actionType} ·{' '}
              {bulkStatusLabel(b.status)} · {b.shopCode} · 成功 {b.appliedCount} / 失败{' '}
              {b.failedCount}
              {b.reason ? ` · ${b.reason}` : ''}
            </li>
          ))}
        </ul>
      </Section>

      {/* ================= 关键词报表 ================= */}
      <Section title="关键词报表（按币种分行）">
        {report ? <p className="text-muted-foreground text-xs">{report.scopeNote}</p> : null}
        {!loading && (report?.rows.length ?? 0) === 0 ? (
          <p className="text-muted-foreground text-sm">{emptyReportText()}</p>
        ) : null}
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left">
                <th className="py-1 pr-2">维度</th>
                <th className="py-1 pr-2">币种</th>
                <th className="py-1 pr-2">归一类型 / 原始标记</th>
                <th className="py-1 pr-2">关键词数</th>
                <th className="py-1 pr-2">展示</th>
                <th className="py-1 pr-2">点击</th>
                <th className="py-1 pr-2">转化</th>
                <th className="py-1 pr-2">花费</th>
                <th className="py-1 pr-2">销售额</th>
                <th className="py-1 pr-2">ACOS</th>
                <th className="py-1 pr-2">已否定</th>
              </tr>
            </thead>
            <tbody>
              {(report?.rows ?? []).map((row, index) => (
                <tr key={`${row.dimensionValue}-${row.currency}-${index}`} className="border-b">
                  <td className="py-1 pr-2">{row.dimensionValue}</td>
                  <td className="py-1 pr-2">{row.currency}</td>
                  <td className="py-1 pr-2 text-xs">
                    <div>{internalTypeLabel(row.internalAdType)}</div>
                    <code className="text-muted-foreground">{row.platformAdType}</code>
                  </td>
                  <td className="py-1 pr-2">{quantity(row.keywordCount)}</td>
                  <td className="py-1 pr-2">{quantity(row.impressions)}</td>
                  <td className="py-1 pr-2">{quantity(row.clicks)}</td>
                  <td className="py-1 pr-2">{quantity(row.conversions)}</td>
                  <td className="py-1 pr-2">{money(row.spend, row.currency)}</td>
                  <td className="py-1 pr-2">{money(row.sales, row.currency)}</td>
                  <td className="py-1 pr-2">{ratioRaw(row.acos)}</td>
                  <td className="py-1 pr-2">{quantity(row.negatedCount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>

      {/* ================= 词频与排名 ================= */}
      <Section title="词频与排名（口径引用 4.5.1.2）">
        {frequency ? (
          <p className="text-muted-foreground text-xs">{frequency.frequencyDefinitions}</p>
        ) : null}
        {!loading && (frequency?.items.length ?? 0) === 0 ? (
          <p className="text-muted-foreground text-sm">{emptyFrequencyText()}</p>
        ) : null}
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left">
                <th className="py-1 pr-2">关键词</th>
                <th className="py-1 pr-2">平台 / 归一类型</th>
                <th className="py-1 pr-2">投放词数</th>
                <th className="py-1 pr-2">活动数</th>
                <th className="py-1 pr-2">词频（商品数）</th>
                <th className="py-1 pr-2">名次</th>
                <th className="py-1 pr-2">花费</th>
                <th className="py-1 pr-2">销售额</th>
                <th className="py-1 pr-2">ACOS</th>
              </tr>
            </thead>
            <tbody>
              {(frequency?.items ?? []).map((row, index) => (
                <tr key={`${row.platformCode}-${row.keyword}-${index}`} className="border-b">
                  <td className="py-1 pr-2">{row.keyword}</td>
                  <td className="py-1 pr-2 text-xs">
                    <div>{row.platformCode}</div>
                    <div className="text-muted-foreground">
                      {internalTypeLabel(row.internalAdType)}
                    </div>
                  </td>
                  <td className="py-1 pr-2">{quantity(row.keywordCount)}</td>
                  <td className="py-1 pr-2">{quantity(row.campaignCount)}</td>
                  <td className="py-1 pr-2">
                    {quantity(row.frequency)}
                    <span className="text-muted-foreground text-xs">
                      （{quantity(row.frequencyListingCount)} 个 listing）
                    </span>
                  </td>
                  <td className="py-1 pr-2 text-xs">
                    <div>自然 {rankText(row.naturalRank)}</div>
                    <div>广告 {rankText(row.adRank)}</div>
                  </td>
                  <td className="py-1 pr-2">{money(row.spend, row.currency)}</td>
                  <td className="py-1 pr-2">{money(row.sales, row.currency)}</td>
                  <td className="py-1 pr-2">{ratioRaw(row.acos)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
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
    <label className="flex flex-col gap-1 text-xs">
      <span className="text-muted-foreground">{label}</span>
      {children}
    </label>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded border px-2 py-1">
      <dt className="text-muted-foreground text-xs">{label}</dt>
      <dd className="text-sm">{value}</dd>
    </div>
  );
}

function ErrorList({ errors }: { errors: string[] }) {
  if (errors.length === 0) return null;
  return (
    <ul className="text-destructive flex flex-col gap-0.5 text-xs">
      {errors.map((e) => (
        <li key={e}>{e}</li>
      ))}
    </ul>
  );
}
