'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import {
  Check,
  Download,
  Languages,
  Loader2,
  Plus,
  RefreshCw,
  Save,
  Sparkles,
  Trash2,
  X,
} from 'lucide-react';
import type {
  TranslationBatchDetail,
  TranslationBatchInput,
  TranslationBatchPage,
  TranslationEntry,
  TranslationEntryEdit,
  TranslationEntryPage,
  TranslationLocaleInput,
  TranslationLocalePage,
  TranslationLocaleRow,
  TranslationOverview,
  TranslationProductView,
  TranslationRequirementView,
  TranslationSuggestionPage,
  TranslationSuggestionRow,
} from '../../../../../packages/api-contracts/src/index';
import { Editor, Field, control } from '../common/fields';
import { emptySkuFilters, fetchSkuPage } from './sku-list-model';
import {
  BATCH_ACTIONS,
  MAX_ITEMS_PER_BATCH,
  MAX_LOCALES_PER_BATCH,
  SUGGESTION_STATUSES,
  TARGET_TYPES,
  TRANSLATION_STATUSES,
  batchActionHint,
  batchActionLabel,
  batchStatusLabel,
  adoptSuggestion,
  batchStatusTone,
  completionOf,
  csvExportUrl,
  deleteEntry,
  deleteLocale,
  deleteSuggestion,
  emptyBatchInput,
  emptyEntryEdit,
  emptyEntryInput,
  emptyLocaleInput,
  errorText,
  fetchBatchDetail,
  fetchBatches,
  fetchEntries,
  fetchLocales,
  fetchProductWorkspace,
  fetchRequirements,
  fetchSuggestions,
  fetchTranslationOverview,
  itemsSizeProblem,
  localeProblem,
  localeSourceLabel,
  missingRequiredLocales,
  normalizeLocale,
  outcomeLabel,
  outcomeTone,
  publishableTone,
  rejectReasonProblem,
  rejectSuggestion,
  runBatch,
  saveEntry,
  saveLocale,
  setEntryStatus,
  setLocaleEnabled,
  statusHint,
  statusLabel,
  statusTone,
  suggestionStatusLabel,
  syncFromSites,
  textProblem,
} from './multilingual-model';
import { useIdentity } from '../iam/identity-shell';

const PAGE_SIZE = 20;

/**
 * 3.4.3.1 多语言内容工作区。
 *
 * <p>页面按「定语言 → 配译文 → 处置 AI 建议 → 批量推进 → 批量核对」排，
 * 这也是运营实际的工作顺序：
 *
 * <ul>
 *   <li><b>语言需求</b> —— 需要哪些语言。这一块显式画出「店铺站点推导未接入」，
 *       而不是把它显示成「你不需要多语言」。人工维护是现在就能写的一侧。
 *   <li><b>译文</b> —— 三态（待翻 / 已翻 / 已校对）在列表上一眼能分，
 *       「是否可刊登」单独一列，因为它**只**等于「已校对」。
 *   <li><b>AI 建议</b> —— 18 AI 翻译交回的候选逐条采纳或驳回。
 *       驳回必须写理由，不给理由下一轮无从改进。
 *   <li><b>批量操作与批次</b> —— 逐条结果与逐条原因，不是三个汇总数字。
 * </ul>
 *
 * <p>「商品翻译工作台」允许按商品编码直接看：这个商品在每种语言上是什么状态、
 * 哪些还差 —— 也就是 04 刊登填充时会取到空的那几行。
 */
export function MultilingualWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('catalog.spu.update');

  const [overview, setOverview] = useState<TranslationOverview | null>(null);
  const [requirements, setRequirements] = useState<TranslationRequirementView | null>(null);
  const [locales, setLocales] = useState<TranslationLocalePage | null>(null);
  const [entries, setEntries] = useState<TranslationEntryPage | null>(null);
  const [suggestions, setSuggestions] = useState<TranslationSuggestionPage | null>(null);
  const [batches, setBatches] = useState<TranslationBatchPage | null>(null);
  const [batchDetail, setBatchDetail] = useState<TranslationBatchDetail | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [actionError, setActionError] = useState('');

  const [localeKeyword, setLocaleKeyword] = useState('');
  const [entryFilter, setEntryFilter] = useState({
    targetType: '',
    status: '',
    locale: '',
    keyword: '',
  });
  const [entryPage, setEntryPage] = useState(0);
  const [suggestionFilter, setSuggestionFilter] = useState({ status: 'PENDING', batchRef: '' });
  const [suggestionPage, setSuggestionPage] = useState(0);

  const [editingLocale, setEditingLocale] = useState<TranslationLocaleInput | null>(null);
  const [editingLocaleId, setEditingLocaleId] = useState<string | null>(null);

  const [editingEntry, setEditingEntry] = useState<TranslationEntryEdit | null>(null);
  const [editingEntryRow, setEditingEntryRow] = useState<TranslationEntry | null>(null);

  const [rejecting, setRejecting] = useState<TranslationSuggestionRow | null>(null);
  const [rejectReason, setRejectReason] = useState('');

  const [batching, setBatching] = useState<TranslationBatchInput | null>(null);
  const [batchTargetKeyword, setBatchTargetKeyword] = useState('');
  const [batchTargets, setBatchTargets] = useState<{ id: string; code: string; name: string }[]>([]);
  const [batchSelected, setBatchSelected] = useState<Set<string>>(new Set());

  const [workspace, setWorkspace] = useState<{ targetType: 'SPU' | 'SKU'; id: string } | null>(null);
  const [workspaceView, setWorkspaceView] = useState<TranslationProductView | null>(null);
  const [workspaceKeyword, setWorkspaceKeyword] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [ov, req, loc, ent, sug, bat] = await Promise.all([
        fetchTranslationOverview(),
        fetchRequirements(),
        fetchLocales({
          keyword: localeKeyword.trim() || null,
          source: null,
          enabled: null,
          page: 0,
          size: 50,
        }),
        fetchEntries({
          targetType: entryFilter.targetType || null,
          status: entryFilter.status || null,
          locale: entryFilter.locale || null,
          spuId: null,
          skuId: null,
          keyword: entryFilter.keyword.trim() || null,
          page: entryPage,
          size: PAGE_SIZE,
        }),
        fetchSuggestions({
          targetType: null,
          status: suggestionFilter.status || null,
          locale: null,
          spuId: null,
          skuId: null,
          batchRef: suggestionFilter.batchRef.trim() || null,
          keyword: null,
          page: suggestionPage,
          size: PAGE_SIZE,
        }),
        fetchBatches({ action: null, targetType: null, status: null, page: 0, size: 10 }),
      ]);
      setOverview(ov);
      setRequirements(req);
      setLocales(loc);
      setEntries(ent);
      setSuggestions(sug);
      setBatches(bat);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [localeKeyword, entryFilter, entryPage, suggestionFilter, suggestionPage]);

  useEffect(() => {
    void load();
  }, [load]);

  async function saveLocaleRow(input: TranslationLocaleInput, id: string | null) {
    try {
      const saved = await saveLocale(id, input);
      setNotice(saved.message);
      setActionError('');
      setEditingLocale(null);
      setEditingLocaleId(null);
      await load();
    } catch (e) {
      setActionError(errorText(e, '语言需求保存失败'));
    }
  }

  async function toggleLocale(row: TranslationLocaleRow, enabled: boolean) {
    try {
      await setLocaleEnabled(row.id, enabled);
      setNotice(
        enabled
          ? `语言「${row.locale}」已启用：它会重新出现在批量操作的默认语言里`
          : `语言「${row.locale}」已停用：已建的译文照常可读，只是不再作为默认语言`,
      );
      setActionError('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '语言需求启停失败'));
    }
  }

  async function removeLocale(row: TranslationLocaleRow) {
    try {
      await deleteLocale(row.id, row.version);
      setNotice(`语言「${row.locale}」的语言需求已删除`);
      setActionError('');
      await load();
    } catch (e) {
      // 后端返回的是「仍被 N 条商品译文使用」这种人话，直接显示，不要替换成「删除失败」。
      setActionError(errorText(e, '语言需求删除失败'));
    }
  }

  async function trySyncFromSites() {
    try {
      await syncFromSites();
      setNotice('已按店铺启用站点同步语言需求');
      setActionError('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '店铺站点同步失败'));
    }
  }

  async function markReviewed(row: TranslationEntry) {
    try {
      await setEntryStatus(row.id, { status: 'REVIEWED', version: row.version });
      setNotice(`语言「${row.locale}」已标记为已校对，现在可以被 04 刊登取用`);
      setActionError('');
      await load();
      if (workspace) await loadWorkspace(workspace.targetType, workspace.id);
    } catch (e) {
      setActionError(errorText(e, '标记校对失败'));
    }
  }

  async function regressToPending(row: TranslationEntry) {
    if (row.status !== 'REVIEWED') return;
    try {
      await setEntryStatus(row.id, { status: 'TRANSLATED', version: row.version });
      setNotice(`语言「${row.locale}」已退回「已翻」：正文没变，只是不再对外可刊登`);
      setActionError('');
      await load();
      if (workspace) await loadWorkspace(workspace.targetType, workspace.id);
    } catch (e) {
      setActionError(errorText(e, '退回失败'));
    }
  }

  async function removeEntry(row: TranslationEntry) {
    try {
      await deleteEntry(row.id, row.version);
      setNotice(`语言「${row.locale}」的译文已删除`);
      setActionError('');
      await load();
      if (workspace) await loadWorkspace(workspace.targetType, workspace.id);
    } catch (e) {
      setActionError(errorText(e, '译文删除失败'));
    }
  }

  async function adopt(row: TranslationSuggestionRow) {
    try {
      const entry = await adoptSuggestion(row.id);
      setNotice(
        `已采纳建议并写入语言「${entry.locale}」，状态落在「已翻」：机器译文还不能刊登，需要人工校对`,
      );
      setActionError('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '采纳失败'));
    }
  }

  async function rejectSuggestionRow() {
    if (!rejecting) return;
    const problem = rejectReasonProblem(rejectReason);
    if (problem) {
      setActionError(problem);
      return;
    }
    try {
      await rejectSuggestion(rejecting.id, { rejectReason });
      setNotice('已驳回这条建议。理由会写进建议记录，下一轮 AI 翻译能看到它');
      setActionError('');
      setRejecting(null);
      setRejectReason('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '驳回失败'));
    }
  }

  async function removeSuggestion(row: TranslationSuggestionRow) {
    try {
      await deleteSuggestion(row.id, row.version);
      setNotice('已删除该条已驳回的建议');
      setActionError('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '建议删除失败'));
    }
  }

  async function searchBatchTargets() {
    try {
      const page = await fetchSkuPage({ ...emptySkuFilters, keyword: batchTargetKeyword }, 0, 20);
      setBatchTargets(
        page.items.map((row) => ({ id: row.id, code: row.code, name: row.name })),
      );
      setActionError('');
    } catch (e) {
      setActionError(errorText(e, '商品搜索失败'));
    }
  }

  async function submitBatch() {
    if (!batching) return;
    const sizeProblem = itemsSizeProblem(batchSelected.size);
    if (sizeProblem && batching.action !== 'ADOPT_SUGGESTIONS') {
      setActionError(sizeProblem);
      return;
    }
    try {
      const detail = await runBatch({
        ...batching,
        targetIds: batching.action === 'ADOPT_SUGGESTIONS' ? [] : [...batchSelected],
        locales: batching.locales?.filter(Boolean) ?? [],
      });
      setBatchDetail(detail);
      setNotice(
        `${batchActionLabel(detail.batch.action)}：成功 ${detail.batch.succeededCount} 条、跳过 ${detail.batch.skippedCount} 条、未执行 ${detail.batch.rejectedCount} 条`,
      );
      setActionError('');
      setBatching(null);
      setBatchSelected(new Set());
      await load();
    } catch (e) {
      setActionError(errorText(e, '批量操作未执行'));
    }
  }

  async function loadWorkspace(targetType: 'SPU' | 'SKU', id: string) {
    try {
      setWorkspaceView(await fetchProductWorkspace(targetType, id));
      setActionError('');
    } catch (e) {
      setActionError(errorText(e, '商品翻译工作台加载失败'));
    }
  }

  const entryCompleteness = useMemo(
    () => completionOf(workspaceView?.rows ?? []),
    [workspaceView],
  );
  const stillMissing = useMemo(
    () => missingRequiredLocales(requirements?.requirements ?? [], null),
    [requirements],
  );

  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label={`语言需求（已启用 ${overview?.enabledLocaleCount ?? 0} 种）`}
          value={overview?.localeCount ?? 0}
          icon={<Languages className="h-4 w-4" />}
        />
        <StatCard label="待翻（已登记语言，还没有文本）" value={overview?.pendingTranslationCount ?? 0} />
        <StatCard label="已翻（还没有人校对，不可刊登）" value={overview?.translatedTranslationCount ?? 0} />
        <StatCard
          label="可刊登（已校对，04 刊登只取这一档）"
          value={overview?.publishableTranslationCount ?? 0}
        />
      </div>

      {loading && (
        <SectionCard title="加载中">
          <p className="text-muted-foreground flex items-center gap-2 text-sm">
            <Loader2 className="h-4 w-4 animate-spin" />
            正在加载多语言内容…
          </p>
        </SectionCard>
      )}

      {error && (
        <SectionCard title="加载失败">
          <p className="text-destructive text-sm">{error}</p>
          <div className="mt-3">
            <Button size="sm" variant="outline" onClick={() => void load()}>
              <RefreshCw className="h-4 w-4" />
              重试
            </Button>
          </div>
        </SectionCard>
      )}

      {notice && (
        <SectionCard title="最近一次操作">
          <p className="text-sm">{notice}</p>
        </SectionCard>
      )}

      {actionError && (
        <SectionCard title="操作未完成">
          <p className="text-destructive text-sm">{actionError}</p>
        </SectionCard>
      )}

      {/* ------------------------------------------------------------ 语言需求 */}
      <SectionCard
        title="需要哪些语言"
        description="蓝图要求「需要哪些语言由店铺启用站点推导，不靠人肉记忆」。站点与市场是 02 渠道的事实，catalog 禁止跨服务 SQL，所以推导走投影端口；端口未接入时下面这一栏显式标成「未接入」，而不是显示成你不需要多语言。"
        extra={
          <div className="flex flex-wrap gap-2">
            {writable && (
              <Button
                size="sm"
                variant="outline"
                onClick={() => {
                  setEditingLocaleId(null);
                  setEditingLocale(emptyLocaleInput());
                }}
              >
                <Plus className="h-4 w-4" />
                新增语言
              </Button>
            )}
            {writable && (
              <Button size="sm" variant="outline" onClick={() => void trySyncFromSites()}>
                <RefreshCw className="h-4 w-4" />
                从店铺站点同步
              </Button>
            )}
            <Button size="sm" variant="outline" onClick={() => void load()}>
              <RefreshCw className="h-4 w-4" />
              刷新
            </Button>
          </div>
        }
      >
        {loading ? (
          <p className="text-muted-foreground text-sm">正在加载语言需求…</p>
        ) : !requirements || requirements.requirements.length === 0 ? (
          <EmptyState
            icon={<Languages className="h-6 w-6" />}
            title="还没有登记任何语言需求"
            description="语言需求是本模块可写入的一侧：先登记租户实际需要的语言，才能铺开待翻占位、算翻译完整度。02 渠道的站点推导接入后会自动补上那一栏。"
          />
        ) : (
          <div className="space-y-3">
            <div
              className={
                requirements.siteLocaleAvailable
                  ? 'rounded-md border border-border p-3 text-sm'
                  : 'rounded-md border border-warning/40 bg-warning/5 p-3 text-sm'
              }
            >
              <p className="font-medium">
                店铺站点推导：
                {requirements.siteLocaleAvailable ? '已接入' : '未接入（归 02 渠道）'}
              </p>
              <p className="text-muted-foreground mt-1">{requirements.siteNote}</p>
              <p className="text-muted-foreground mt-1">
                人工维护 {requirements.manualCount} 种 · 站点推导 {requirements.derivedCount} 种
              </p>
            </div>

            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-border border-b text-left">
                    <th className="py-2 pr-3 font-medium">语言</th>
                    <th className="py-2 pr-3 font-medium">名称</th>
                    <th className="py-2 pr-3 font-medium">来源</th>
                    <th className="py-2 pr-3 font-medium">待翻</th>
                    <th className="py-2 pr-3 font-medium">已翻</th>
                    <th className="py-2 pr-3 font-medium">已校对</th>
                    <th className="py-2 pr-3 font-medium">状态</th>
                    <th className="py-2 font-medium">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {requirements.requirements.map((row) => (
                    <tr key={row.locale} className="border-border border-b last:border-0">
                      <td className="py-2 pr-3 font-mono">{row.locale}</td>
                      <td className="py-2 pr-3">{row.localeName || '—'}</td>
                      <td className="py-2 pr-3">
                        <span className="text-muted-foreground">
                          {row.fromManual ? '人工维护' : '—'}
                          {row.fromSite ? ' + 店铺站点' : ''}
                        </span>
                      </td>
                      <td className="py-2 pr-3">{row.pendingCount}</td>
                      <td className="py-2 pr-3">{row.translatedCount}</td>
                      <td className="py-2 pr-3">{row.reviewedCount}</td>
                      <td className="py-2 pr-3">
                        {row.enabled ? '启用' : '停用'}
                      </td>
                      <td className="py-2">
                        {writable && (
                          <span className="flex gap-1">
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={() => void toggleLocale(
                                locales?.items.find((l) => l.locale === row.locale) ?? {
                                  ...row,
                                  id: '',
                                  sourceLabel: '',
                                  note: '',
                                  version: 0,
                                  createdAt: '',
                                  updatedAt: '',
                                } as unknown as TranslationLocaleRow,
                                !row.enabled,
                              )}
                              disabled={!locales?.items.some((l) => l.locale === row.locale)}
                            >
                              {row.enabled ? '停用' : '启用'}
                            </Button>
                          </span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </SectionCard>

      {/* ------------------------------------------------------------ 语言需求维护 */}
      <SectionCard
        title="语言需求明细"
        description="一条语言需求带它的译文覆盖统计。语言标签建后不可改：它是所有商品译文主键的一部分，改标签等于把一整批译文变成「非需求语言」而没人会想到去重新校对。"
        extra={
          <div className="flex gap-2">
            <input
              className={control}
              placeholder="搜语言标签或名称"
              value={localeKeyword}
              onChange={(e) => setLocaleKeyword(e.target.value)}
            />
            <Button size="sm" variant="outline" onClick={() => void load()}>
              搜索
            </Button>
          </div>
        }
      >
        {loading ? (
          <p className="text-muted-foreground text-sm">正在加载…</p>
        ) : !locales || locales.items.length === 0 ? (
          <EmptyState
            title={localeKeyword ? '没有匹配的语言需求' : '还没有语言需求'}
            description={
              localeKeyword
                ? '换个关键词试试，或清空搜索看全部语言需求。'
                : '先登记租户实际需要的语言 —— 这是本模块在 02 渠道接入前唯一真实可写入的一侧，不是假数据。'
            }
          />
        ) : (
          <ul className="divide-border divide-y">
            {locales.items.map((row) => (
              <li key={row.id} className="flex flex-wrap items-start justify-between gap-3 py-3">
                <div className="min-w-0 space-y-1">
                  <div className="flex items-center gap-2">
                    <span className="font-mono text-sm">{row.locale}</span>
                    <span>{row.localeName || '未命名'}</span>
                    <Badge tone={row.enabled ? 'success' : 'neutral'}>
                      {row.enabled ? '启用' : '停用'}
                    </Badge>
                    <Badge tone="neutral">{localeSourceLabel(row.source)}</Badge>
                  </div>
                  <p className="text-muted-foreground text-xs">
                    共 {row.translationCount} 条译文：待翻 {row.pendingCount} · 已翻{' '}
                    {row.translatedCount} · 已校对 {row.reviewedCount}
                    {row.note ? ` · ${row.note}` : ''}
                  </p>
                </div>
                {writable && (
                  <div className="flex gap-1">
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => {
                        setEditingLocaleId(row.id);
                        setEditingLocale({
                          locale: row.locale,
                          localeName: row.localeName,
                          enabled: row.enabled,
                          note: row.note,
                          version: row.version,
                        });
                      }}
                    >
                      编辑
                    </Button>
                    <Button size="sm" variant="outline" onClick={() => void toggleLocale(row, !row.enabled)}>
                      {row.enabled ? '停用' : '启用'}
                    </Button>
                    <Button size="sm" variant="outline" onClick={() => void removeLocale(row)}>
                      <Trash2 className="h-4 w-4" />
                      删除
                    </Button>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </SectionCard>

      {/* ------------------------------------------------------------ 译文列表 */}
      <SectionCard
        title="商品多语言译文"
        description="SPU 与 SKU 各一张表，「按语言唯一」在 DB 层是唯一约束。只有「已校对」可刊登 ——「已翻」是机器译文，发到平台上等于没做校对这一步。"
        extra={
          <div className="flex flex-wrap gap-2">
            <select
              className={control}
              value={entryFilter.targetType}
              onChange={(e) => {
                setEntryFilter({ ...entryFilter, targetType: e.target.value });
                setEntryPage(0);
              }}
            >
              <option value="">全部主体</option>
              {TARGET_TYPES.map((t) => (
                <option key={t.value} value={t.value}>
                  {t.label}
                </option>
              ))}
            </select>
            <select
              className={control}
              value={entryFilter.status}
              onChange={(e) => {
                setEntryFilter({ ...entryFilter, status: e.target.value });
                setEntryPage(0);
              }}
            >
              <option value="">全部状态</option>
              {TRANSLATION_STATUSES.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
            <input
              className={control}
              placeholder="搜语言 / 名称 / 描述"
              value={entryFilter.keyword}
              onChange={(e) => setEntryFilter({ ...entryFilter, keyword: e.target.value })}
            />
            <a href={csvExportUrl({ ...entryFilter, spuId: null, skuId: null, page: 0, size: 0 })}>
              <Button size="sm" variant="outline">
                <Download className="h-4 w-4" />
                导出 CSV
              </Button>
            </a>
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                setEntryPage(0);
                void load();
              }}
            >
              <RefreshCw className="h-4 w-4" />
              搜索
            </Button>
          </div>
        }
      >
        {loading ? (
          <p className="text-muted-foreground text-sm">正在加载译文…</p>
        ) : !entries || entries.items.length === 0 ? (
          <EmptyState
            icon={<Languages className="h-6 w-6" />}
            title={entryFilter.keyword || entryFilter.status ? '没有匹配的译文' : '还没有任何多语言译文'}
            description={
              entryFilter.keyword || entryFilter.status
                ? '换个筛选条件试试。清空后可以看到全部译文。'
                : '译文要么人工写，要么从「AI 翻译建议」采纳。先在上面的语言需求里登记要翻的语言，再用「补齐待翻占位」铺开。'
            }
          />
        ) : (
          <div className="space-y-3">
            <p className="text-muted-foreground text-xs">
              共 {entries.total} 条 · 第 {entries.page + 1}/{Math.max(1, entries.totalPages)} 页
            </p>
            <ul className="divide-border divide-y">
              {entries.items.map((row) => {
                const publishable = row.status === 'REVIEWED';
                return (
                  <li key={row.id} className="flex flex-wrap items-start justify-between gap-3 py-3">
                    <div className="min-w-0 space-y-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-mono text-sm">{row.locale}</span>
                        <Badge tone="neutral">{row.targetType}</Badge>
                        <Badge tone={statusTone(row.status)}>{statusLabel(row.status)}</Badge>
                        <Badge tone={publishableTone(publishable)}>
                          {publishable ? '可刊登' : '不可刊登'}
                        </Badge>
                      </div>
                      <p className="text-sm">{row.name || <em className="text-muted-foreground">（待翻：还没有文本）</em>}</p>
                      {row.description && (
                        <p className="text-muted-foreground line-clamp-2 text-xs">{row.description}</p>
                      )}
                      <p className="text-muted-foreground text-xs">
                        来源 {row.origin} · {statusHint(row.status)}
                        {row.reviewedAt ? ` · 校对于 ${row.reviewedAt.slice(0, 10)}` : ''}
                      </p>
                    </div>
                    {writable && (
                      <div className="flex flex-wrap gap-1">
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => {
                            setEditingEntryRow(row);
                            setEditingEntry(emptyEntryEdit(row));
                          }}
                        >
                          编辑
                        </Button>
                        {row.status === 'TRANSLATED' && (
                          <Button size="sm" variant="outline" onClick={() => void markReviewed(row)}>
                            <Check className="h-4 w-4" />
                            标校对
                          </Button>
                        )}
                        {row.status === 'REVIEWED' && (
                          <Button size="sm" variant="outline" onClick={() => void regressToPending(row)}>
                            <X className="h-4 w-4" />
                            退回已翻
                          </Button>
                        )}
                        <Button size="sm" variant="outline" onClick={() => void removeEntry(row)}>
                          <Trash2 className="h-4 w-4" />
                          删除
                        </Button>
                      </div>
                    )}
                  </li>
                );
              })}
            </ul>
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="outline"
                disabled={entryPage === 0}
                onClick={() => setEntryPage(entryPage - 1)}
              >
                上一页
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={entryPage + 1 >= entries.totalPages}
                onClick={() => setEntryPage(entryPage + 1)}
              >
                下一页
              </Button>
            </div>
          </div>
        )}
      </SectionCard>

      {/* ------------------------------------------------------------ AI 翻译建议 */}
      <SectionCard
        title="AI 翻译建议"
        description="18 AI 翻译交回的候选逐条处置。采纳后译文落在「已翻」而不是「已校对」—— 机器译文发到平台上等于没做校对这一步。驳回必须写理由，不给理由下一轮无从改进。处置是终态：采纳错了直接改译文内容。"
        extra={
          <div className="flex flex-wrap gap-2">
            <select
              className={control}
              value={suggestionFilter.status}
              onChange={(e) => {
                setSuggestionFilter({ ...suggestionFilter, status: e.target.value });
                setSuggestionPage(0);
              }}
            >
              <option value="">全部处置状态</option>
              {SUGGESTION_STATUSES.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
            <input
              className={control}
              placeholder="按 batchRef 找一批"
              value={suggestionFilter.batchRef}
              onChange={(e) => setSuggestionFilter({ ...suggestionFilter, batchRef: e.target.value })}
            />
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                setSuggestionPage(0);
                void load();
              }}
            >
              <RefreshCw className="h-4 w-4" />
              搜索
            </Button>
          </div>
        }
      >
        {loading ? (
          <p className="text-muted-foreground text-sm">正在加载建议…</p>
        ) : !suggestions || suggestions.items.length === 0 ? (
          <EmptyState
            icon={<Sparkles className="h-6 w-6" />}
            title={
              suggestionFilter.status === 'PENDING' ? '没有待处置的翻译建议' : '没有匹配的建议'
            }
            description={
              suggestionFilter.status === 'PENDING'
                ? '18 AI 翻译翻完会调 POST /catalog/translations/suggestions 把候选写回来；在那之前这里确实是空的 —— 这不是「翻译功能坏了」。'
                : '换个处置状态或 batchRef 试试。'
            }
          />
        ) : (
          <div className="space-y-3">
            <p className="text-muted-foreground text-xs">
              共 {suggestions.total} 条 · 第 {suggestions.page + 1}/{Math.max(1, suggestions.totalPages)} 页
            </p>
            <ul className="divide-border divide-y">
              {suggestions.items.map((row) => (
                <li key={row.id} className="flex flex-wrap items-start justify-between gap-3 py-3">
                  <div className="min-w-0 space-y-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-mono text-sm">{row.locale}</span>
                      <Badge tone="neutral">{row.targetType}</Badge>
                      <Badge tone={row.status === 'PENDING' ? 'warning' : 'neutral'}>
                        {suggestionStatusLabel(row.status)}
                      </Badge>
                      {row.batchRef && (
                        <span className="text-muted-foreground font-mono text-xs">{row.batchRef}</span>
                      )}
                    </div>
                    <p className="text-sm">{row.suggestedName || '（无名称）'}</p>
                    {row.suggestedDescription && (
                      <p className="text-muted-foreground line-clamp-2 text-xs">
                        {row.suggestedDescription}
                      </p>
                    )}
                    <p className="text-muted-foreground text-xs">
                      {row.targetCode} · 来源 {row.origin}
                      {row.modelName ? ` · ${row.modelName}` : ''}
                      {row.rejectReason ? ` · 驳回理由：${row.rejectReason}` : ''}
                    </p>
                  </div>
                  {writable && row.status === 'PENDING' && (
                    <div className="flex gap-1">
                      <Button size="sm" variant="outline" onClick={() => void adopt(row)}>
                        <Check className="h-4 w-4" />
                        采纳
                      </Button>
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => {
                          setRejecting(row);
                          setRejectReason('');
                        }}
                      >
                        <X className="h-4 w-4" />
                        驳回
                      </Button>
                    </div>
                  )}
                  {writable && row.status === 'REJECTED' && (
                    <Button size="sm" variant="outline" onClick={() => void removeSuggestion(row)}>
                      <Trash2 className="h-4 w-4" />
                      删除
                    </Button>
                  )}
                </li>
              ))}
            </ul>
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="outline"
                disabled={suggestionPage === 0}
                onClick={() => setSuggestionPage(suggestionPage - 1)}
              >
                上一页
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={suggestionPage + 1 >= suggestions.totalPages}
                onClick={() => setSuggestionPage(suggestionPage + 1)}
              >
                下一页
              </Button>
            </div>
          </div>
        )}
      </SectionCard>

      {/* ------------------------------------------------------------ 批量操作 */}
      <SectionCard
        title="批量操作"
        description="三件事都是同步执行（几百行的短事务），提交返回即已完成 —— 不会显示「处理中」再轮询。逐条结果与逐条原因在下面的批次详情里，不是一个汇总数字。"
        extra={
          writable ? (
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                setBatching(emptyBatchInput());
                setBatchSelected(new Set());
                setBatchTargets([]);
              }}
            >
              <Plus className="h-4 w-4" />
              新建批量
            </Button>
          ) : undefined
        }
      >
        {batching && (
          <div className="mb-4 space-y-3 rounded-md border border-border p-3">
            <Field label="动作" hint={batchActionHint(batching.action)}>
              <select
                className={control}
                value={batching.action}
                onChange={(e) => setBatching({ ...batching, action: e.target.value })}
              >
                {BATCH_ACTIONS.map((a) => (
                  <option key={a.value} value={a.value}>
                    {a.label}
                  </option>
                ))}
              </select>
            </Field>

            {batching.action !== 'ADOPT_SUGGESTIONS' ? (
              <>
                <Field
                  label="主体类型"
                  hint="SPU 与 SKU 各一张译文表，这里决定处理哪一边"
                >
                  <select
                    className={control}
                    value={batching.targetType}
                    onChange={(e) => setBatching({ ...batching, targetType: e.target.value })}
                  >
                    {TARGET_TYPES.map((t) => (
                      <option key={t.value} value={t.value}>
                        {t.label}
                      </option>
                    ))}
                  </select>
                </Field>

                <Field
                  label="语言（留空则用租户启用的语言需求）"
                  hint={`最多 ${MAX_LOCALES_PER_BATCH} 种：商品 × 语言会相乘`}
                >
                  <select
                    multiple
                    className={`${control} h-28`}
                    value={(batching.locales ?? []).filter((l): l is string => Boolean(l))}
                    onChange={(e) =>
                      setBatching({
                        ...batching,
                        locales: [...e.target.selectedOptions].map((o) => o.value),
                      })
                    }
                  >
                    {(requirements?.requirements ?? []).map((r) => (
                      <option key={r.locale} value={r.locale}>
                        {r.locale}
                        {r.localeName ? ` · ${r.localeName}` : ''}
                      </option>
                    ))}
                  </select>
                </Field>

                <Field
                  label={`选中商品（${batchSelected.size}/${MAX_ITEMS_PER_BATCH}）`}
                  hint="按编码或名称搜索后勾选"
                >
                  <div className="space-y-2">
                    <div className="flex gap-2">
                      <input
                        className={control}
                        placeholder="搜商品编码或名称"
                        value={batchTargetKeyword}
                        onChange={(e) => setBatchTargetKeyword(e.target.value)}
                      />
                      <Button size="sm" variant="outline" onClick={() => void searchBatchTargets()}>
                        搜索
                      </Button>
                    </div>
                    <ul className="max-h-48 space-y-1 overflow-y-auto rounded-md border border-border p-2">
                      {batchTargets.length === 0 ? (
                        <li className="text-muted-foreground text-xs">先搜出商品再勾选</li>
                      ) : (
                        batchTargets.map((t) => (
                          <li key={t.id}>
                            <label className="flex items-center gap-2 text-sm">
                              <input
                                type="checkbox"
                                checked={batchSelected.has(t.id)}
                                onChange={(e) => {
                                  const next = new Set(batchSelected);
                                  if (e.target.checked) next.add(t.id);
                                  else next.delete(t.id);
                                  setBatchSelected(next);
                                }}
                              />
                              <span className="font-mono text-xs">{t.code}</span>
                              <span className="text-muted-foreground">{t.name}</span>
                            </label>
                          </li>
                        ))
                      )}
                    </ul>
                  </div>
                </Field>
              </>
            ) : (
              <Field
                label="batchRef"
                hint="18 一次交回的一批建议用 batch_ref 归组；不给就没法确定「这一批」是哪几条"
              >
                <input
                  className={control}
                  value={batching.batchRef ?? ''}
                  onChange={(e) => setBatching({ ...batching, batchRef: e.target.value })}
                />
              </Field>
            )}

            <div className="flex gap-2">
              <Button size="sm" onClick={() => void submitBatch()}>
                <Save className="h-4 w-4" />
                执行
              </Button>
              <Button size="sm" variant="outline" onClick={() => setBatching(null)}>
                取消
              </Button>
            </div>
          </div>
        )}

        {batchDetail && (
          <div className="mb-4 space-y-2 rounded-md border border-border p-3">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone={batchStatusTone(batchDetail.batch.status)}>
                {batchStatusLabel(batchDetail.batch.status)}
              </Badge>
              <span className="text-sm">
                {batchActionLabel(batchDetail.batch.action)}：成功 {batchDetail.batch.succeededCount} · 跳过{' '}
                {batchDetail.batch.skippedCount} · 未执行 {batchDetail.batch.rejectedCount}
              </span>
              <Button size="sm" variant="outline" onClick={() => setBatchDetail(null)}>
                收起
              </Button>
            </div>
            <p className="text-muted-foreground text-xs">{batchDetail.note}</p>
            {batchDetail.items.length > 0 && (
              <ul className="max-h-72 space-y-1 overflow-y-auto text-xs">
                {batchDetail.items.map((item) => (
                  <li key={item.id} className="border-border flex flex-wrap items-start gap-2 border-b py-1">
                    <Badge tone={outcomeTone(item.outcome)}>{outcomeLabel(item.outcome)}</Badge>
                    <span className="font-mono">{item.locale}</span>
                    <span className="font-mono">{item.targetCode || '—'}</span>
                    {item.reason && <span className="text-muted-foreground">{item.reason}</span>}
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}

        {loading ? (
          <p className="text-muted-foreground text-sm">正在加载批次…</p>
        ) : !batches || batches.items.length === 0 ? (
          <EmptyState
            title="还没有批量操作批次"
            description="批量是同步执行：提交返回时逐条结果已经在库里了，不需要等进度条。这里为空就是还没跑过，不是「跑失败了」。"
          />
        ) : (
          <ul className="divide-border divide-y">
            {batches.items.map((row) => (
              <li key={row.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={batchStatusTone(row.status)}>{row.statusLabel}</Badge>
                  <span className="text-sm">{row.actionLabel}</span>
                  <span className="text-muted-foreground text-xs">
                    {row.succeededCount} 成功 · {row.skippedCount} 跳过 · {row.rejectedCount} 未执行
                  </span>
                </div>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={async () => {
                    try {
                      setBatchDetail(await fetchBatchDetail(row.id));
                      setActionError('');
                    } catch (e) {
                      setActionError(errorText(e, '批次详情加载失败'));
                    }
                  }}
                >
                  查看逐条结果
                </Button>
              </li>
            ))}
          </ul>
        )}
      </SectionCard>

      {/* ------------------------------------------------------------ 商品翻译工作台 */}
      <SectionCard
        title="商品翻译工作台"
        description="按商品看每种语言翻到什么程度 —— 也就是 04 刊登填充时会取到空的那几行。留空表示该语言还没有译文记录（未登记），与「已登记为待翻」是两回事。"
        extra={
          <div className="flex gap-2">
            <input
              className={control}
              placeholder="商品 ID"
              value={workspaceKeyword}
              onChange={(e) => setWorkspaceKeyword(e.target.value)}
            />
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                const id = workspaceKeyword.trim();
                if (!id) {
                  setActionError('请填写商品 ID');
                  return;
                }
                const targetType = 'SPU';
                setWorkspace({ targetType, id });
                void loadWorkspace(targetType, id);
              }}
            >
              查看 SPU
            </Button>
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                const id = workspaceKeyword.trim();
                if (!id) {
                  setActionError('请填写商品 ID');
                  return;
                }
                const targetType = 'SKU';
                setWorkspace({ targetType, id });
                void loadWorkspace(targetType, id);
              }}
            >
              查看 SKU
            </Button>
          </div>
        }
      >
        {workspace && workspaceView ? (
          <div className="space-y-3">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-mono text-sm">{workspaceView.code}</span>
              <span>{workspaceView.name}</span>
              <Badge tone="neutral">{workspaceView.targetType}</Badge>
              {entryCompleteness && (
                <Badge tone={entryCompleteness.percent === 100 ? 'success' : 'warning'}>
                  已校对 {entryCompleteness.reviewed}/{entryCompleteness.required}（
                  {entryCompleteness.percent}%）
                </Badge>
              )}
              <Button
                size="sm"
                variant="outline"
                onClick={() =>
                  void loadWorkspace(workspaceView.targetType as 'SPU' | 'SKU', workspaceView.targetId)
                }
              >
                <RefreshCw className="h-4 w-4" />
                刷新
              </Button>
            </div>
            <p className="text-muted-foreground text-xs">{workspaceView.note}</p>
            {entryCompleteness && entryCompleteness.missing > 0 && (
              <p className="text-sm">
                还有 {entryCompleteness.missing} 种需求语言没有译文记录 —— 先用「补齐待翻占位」铺开，再逐个填。
              </p>
            )}
            {workspaceView.rows.length === 0 ? (
              <EmptyState
                title="这个商品还没有登记任何语言的译文"
                description="它可能不在语言需求里，也可能是语言需求已建但还没给这个商品铺占位。用上面的「补齐待翻占位」批量操作处理。"
              />
            ) : (
              <ul className="divide-border divide-y">
                {workspaceView.rows.map((row) => (
                  <li key={row.locale} className="flex flex-wrap items-center justify-between gap-3 py-2">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-mono text-sm">{row.locale}</span>
                      {row.localeName && <span className="text-muted-foreground text-xs">{row.localeName}</span>}
                      {!row.required && <Badge tone="neutral">非需求语言</Badge>}
                      <Badge tone={statusTone(row.status)}>{statusLabel(row.status)}</Badge>
                      <Badge tone={publishableTone(row.publishable)}>
                        {row.publishable ? '可刊登' : '不可刊登'}
                      </Badge>
                    </div>
                    <span className="text-muted-foreground text-xs">
                      {row.name || '（没有文本）'}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        ) : (
          <EmptyState
            icon={<Languages className="h-6 w-6" />}
            title="选一个商品看它的多语言状态"
            description="输入商品 ID 后点「查看 SPU」或「查看 SKU」。这里会把它在每种语言上的状态与是否可刊登并排列出来 —— 那正是 04 刊登填充时会取到空的行。"
          />
        )}
      </SectionCard>

      {stillMissing.length > 0 && requirements && (
        <SectionCard title="当前全部需求语言">
          <p className="text-muted-foreground text-sm">
            {stillMissing.join(' · ')}
            {stillMissing.length >= MAX_LOCALES_PER_BATCH && '（其余见语言需求列表）'}
          </p>
        </SectionCard>
      )}

      {/* ------------------------------------------------------------ 编辑语言需求 */}
      {editingLocale && (
        <Editor
          title={editingLocaleId ? '编辑语言需求' : '新增语言需求'}
          description="语言标签建后不可改。停用不会删掉已建的译文，只是不再作为批量操作的默认语言。"
          close={() => {
            setEditingLocale(null);
            setEditingLocaleId(null);
          }}
        >
          <div className="space-y-3">
            <Field label="语言标签" hint="BCP-47 简写：zh-CN / en-US / ja-JP">
              <input
                className={control}
                value={editingLocale.locale}
                disabled={Boolean(editingLocaleId)}
                onChange={(e) =>
                  setEditingLocale({ ...editingLocale, locale: normalizeLocale(e.target.value) })
                }
              />
            </Field>
            {localeProblem(editingLocale.locale) && (
              <p className="text-destructive text-xs">{localeProblem(editingLocale.locale)}</p>
            )}
            <Field label="语言名称" hint="给人看的名字，例如「德语」。不参与任何逻辑">
              <input
                className={control}
                value={editingLocale.localeName}
                onChange={(e) => setEditingLocale({ ...editingLocale, localeName: e.target.value })}
              />
            </Field>
            <Field label="备注">
              <textarea
                className={control}
                rows={2}
                value={editingLocale.note ?? ''}
                onChange={(e) => setEditingLocale({ ...editingLocale, note: e.target.value })}
              />
            </Field>
            <div className="flex gap-2">
              <Button
                size="sm"
                onClick={() => void saveLocaleRow(editingLocale, editingLocaleId)}
                disabled={Boolean(localeProblem(editingLocale.locale))}
              >
                <Save className="h-4 w-4" />
                保存
              </Button>
              <Button
                size="sm"
                variant="outline"
                onClick={() => {
                  setEditingLocale(null);
                  setEditingLocaleId(null);
                }}
              >
                取消
              </Button>
            </div>
          </div>
        </Editor>
      )}

      {/* ------------------------------------------------------------ 编辑译文 */}
      {editingEntry && editingEntryRow && (
        <Editor
          title={`编辑译文：${editingEntryRow.locale}`}
          description="语言不可改 ——「把德语译文改成法语」不是编辑而是删一条建一条。名称与描述是已填好的成品，不允许出现 {brand} 这类占位符（那是 3.4.2.2 描述模板的机制）。"
          close={() => {
            setEditingEntry(null);
            setEditingEntryRow(null);
          }}
        >
          <div className="space-y-3">
            <Field label="状态" hint={statusHint(editingEntry.status ?? '')}>
              <select
                className={control}
                value={editingEntry.status ?? 'TRANSLATED'}
                onChange={(e) => setEditingEntry({ ...editingEntry, status: e.target.value })}
              >
                {TRANSLATION_STATUSES.map((s) => (
                  <option key={s.value} value={s.value}>
                    {s.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="来源">
              <select
                className={control}
                value={editingEntry.origin ?? 'MANUAL'}
                onChange={(e) => setEditingEntry({ ...editingEntry, origin: e.target.value })}
              >
                <option value="MANUAL">人工撰写</option>
                <option value="AI">AI 翻译</option>
                <option value="IMPORTED">导入</option>
              </select>
            </Field>
            <Field label="商品名称">
              <input
                className={control}
                value={editingEntry.name ?? ''}
                onChange={(e) => setEditingEntry({ ...editingEntry, name: e.target.value })}
              />
            </Field>
            <Field label="商品描述">
              <textarea
                className={control}
                rows={5}
                value={editingEntry.description ?? ''}
                onChange={(e) => setEditingEntry({ ...editingEntry, description: e.target.value })}
              />
            </Field>
            {textProblem(
              editingEntry.name ?? '',
              editingEntry.description ?? '',
              editingEntry.status ?? 'TRANSLATED',
            ) && (
              <p className="text-destructive text-xs">
                {textProblem(
                  editingEntry.name ?? '',
                  editingEntry.description ?? '',
                  editingEntry.status ?? 'TRANSLATED',
                )}
              </p>
            )}
            <div className="flex gap-2">
              <Button
                size="sm"
                onClick={async () => {
                  try {
                    await saveEntry(editingEntryRow.id, editingEntry);
                    setNotice(`语言「${editingEntryRow.locale}」的译文已保存`);
                    setActionError('');
                    setEditingEntry(null);
                    setEditingEntryRow(null);
                    await load();
                    if (workspace)
                      await loadWorkspace(workspace.targetType, workspace.id);
                  } catch (e) {
                    setActionError(errorText(e, '译文保存失败'));
                  }
                }}
              >
                <Save className="h-4 w-4" />
                保存
              </Button>
              <Button
                size="sm"
                variant="outline"
                onClick={() => {
                  setEditingEntry(null);
                  setEditingEntryRow(null);
                }}
              >
                取消
              </Button>
            </div>
          </div>
        </Editor>
      )}

      {/* ------------------------------------------------------------ 驳回建议 */}
      {rejecting && (
        <Editor
          title={`驳回建议：${rejecting.locale}`}
          description="驳回理由会写进建议记录，下一轮 AI 翻译能看到它。不给理由的驳回等于让同一批商品被反复翻成同一个错法。"
          close={() => {
            setRejecting(null);
            setRejectReason('');
          }}
        >
          <div className="space-y-3">
            <p className="text-muted-foreground text-sm">建议正文：{rejecting.suggestedName || '（无名称）'}</p>
            <Field label="驳回理由">
              <textarea
                className={control}
                rows={4}
                value={rejectReason}
                onChange={(e) => setRejectReason(e.target.value)}
              />
            </Field>
            {rejectReason && rejectReasonProblem(rejectReason) && (
              <p className="text-destructive text-xs">{rejectReasonProblem(rejectReason)}</p>
            )}
            <div className="flex gap-2">
              <Button size="sm" onClick={() => void rejectSuggestionRow()}>
                <Save className="h-4 w-4" />
                确认驳回
              </Button>
              <Button
                size="sm"
                variant="outline"
                onClick={() => {
                  setRejecting(null);
                  setRejectReason('');
                }}
              >
                取消
              </Button>
            </div>
          </div>
        </Editor>
      )}
    </div>
  );
}