'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard } from '@starcon/ui';
import {
  Boxes,
  CheckCircle2,
  Image as ImageIcon,
  Loader2,
  RefreshCw,
  ShieldCheck,
  Tag as TagIcon,
  TriangleAlert,
  Trash2,
} from 'lucide-react';
import {
  BOX_NAMING_PLACEHOLDERS,
  capturedAtLabel,
  claimBoxItems,
  claimSummary,
  createBoxRule,
  createBoxTag,
  deleteBoxRule,
  deleteBoxTag,
  DRAFT_ONLY_NOTE,
  emptyBoxMessage,
  emptyCandidatesMessage,
  emptyRulesMessage,
  enrollBoxItems,
  failedClaimOutcomes,
  fetchBoxCandidates,
  fetchBoxCapabilities,
  fetchBoxGroups,
  fetchBoxItems,
  fetchBoxRules,
  fetchBoxTags,
  IMAGE_URL_ONLY_NOTE,
  itemStateLabel,
  itemStateTone,
  MAPPING_REQUIRED_NOTE,
  mappingRows,
  originLabel,
  parseMappingText,
  previewNaming,
  removeBoxItems,
  retainedDraftOutcomes,
  sourcePriceLabel,
  tagBoxItems,
  unavailableDimensions,
  validateMappingText,
  validateNamingTemplate,
  type ListingCollectBoxCandidateRow,
  type ListingCollectBoxCapabilities,
  type ListingCollectBoxClaimResult,
  type ListingCollectBoxClaimRuleRow,
  type ListingCollectBoxGroupRow,
  type ListingCollectBoxItemRow,
  type ListingCollectBoxTagRow,
} from './listing-collect-box-model';

/**
 * 4.4.1.3 采集箱 工作区。
 *
 * 蓝图：「采集结果的暂存区，按平台/来源/标签分组，支持勾选后批量认领为内部商品（生成 SPU/SKU 草稿并预填内容）、批量删除、批量打标。输入：勾选操作；认领规则（目标分类、品牌映射、命名规则）。输出：认领后的商品草稿；采集箱状态更新。关系：上游 = 4.4.1.1/4.4.1.2；下游 = 3.1.1.1 商品建档、4.2.1.1 编辑器。<b>认领时必须做品牌与分类映射</b>，否则采集来的商品归属混乱，后续无法按品牌做合规与绩效分析。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **认领只产出草稿**。页面上没有「已建档」「已入库商品」的字样；认领结果面板把服务端给的
 *    `buildNote` 原文登出来（落库归 03 建档）。
 * 2. **品牌与分类映射是硬要求**。认领规则表单里品牌映射**必填**，并把服务端逐条返回的
 *    认领失败原因原文显示出来（哪一条、为什么）。
 * 3. **删除的语义要说清**。移出采集箱 ≠ 删掉草稿：上游草稿被批量采集结果清单引用时会被保留，
 *    页面把服务端写的 `reason` 原文显示出来。
 * 4. **图片只透传地址**，并明写「未下载入媒体库」。
 */
export function ListingCollectBoxWorkspace() {
  const [caps, setCaps] = useState<ListingCollectBoxCapabilities | null>(null);
  const [items, setItems] = useState<ListingCollectBoxItemRow[]>([]);
  const [total, setTotal] = useState(0);
  const [candidates, setCandidates] = useState<ListingCollectBoxCandidateRow[]>([]);
  const [candidateTotal, setCandidateTotal] = useState(0);
  const [tags, setTags] = useState<ListingCollectBoxTagRow[]>([]);
  const [rules, setRules] = useState<ListingCollectBoxClaimRuleRow[]>([]);
  const [sourceGroups, setSourceGroups] = useState<ListingCollectBoxGroupRow[]>([]);
  const [tagGroups, setTagGroups] = useState<ListingCollectBoxGroupRow[]>([]);
  const [stateGroups, setStateGroups] = useState<ListingCollectBoxGroupRow[]>([]);

  // ---- 勾选
  const [selected, setSelected] = useState<string[]>([]);
  const [selectedCandidates, setSelectedCandidates] = useState<string[]>([]);

  // ---- 筛选
  const [stateFilter, setStateFilter] = useState('');
  const [sourceFilter, setSourceFilter] = useState('');
  const [tagFilter, setTagFilter] = useState('');

  // ---- 认领规则表单
  const [ruleName, setRuleName] = useState('');
  const [targetCategory, setTargetCategory] = useState('');
  const [categoryMappingText, setCategoryMappingText] = useState('');
  const [brandMappingText, setBrandMappingText] = useState('');
  const [namingTemplate, setNamingTemplate] = useState('{brand}-{title}');
  const [activeRuleId, setActiveRuleId] = useState('');

  // ---- 新标签
  const [newTagName, setNewTagName] = useState('');
  const [activeTagId, setActiveTagId] = useState('');

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [claimResult, setClaimResult] = useState<ListingCollectBoxClaimResult | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, page, candidatePage, tagList, rulePage, groupResult] = await Promise.all([
        fetchBoxCapabilities(),
        fetchBoxItems(
          sourceFilter || undefined,
          tagFilter || undefined,
          stateFilter || undefined,
          undefined,
          0,
          50,
        ),
        fetchBoxCandidates(sourceFilter || undefined, undefined, 0, 50),
        fetchBoxTags(),
        fetchBoxRules(),
        fetchBoxGroups(),
      ]);
      setCaps(capsResult);
      setItems(page.items);
      setTotal(page.total);
      setCandidates(candidatePage.items);
      setCandidateTotal(candidatePage.total);
      setTags(tagList);
      setRules(rulePage.items);
      setSourceGroups(groupResult.bySource);
      setTagGroups(groupResult.byTag);
      setStateGroups(groupResult.byState);
      setSelected([]);
      setSelectedCandidates([]);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [sourceFilter, stateFilter, tagFilter]);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const toggle = (ids: string[], id: string, set: (next: string[]) => void) => {
    set(ids.includes(id) ? ids.filter((x) => x !== id) : [...ids, id]);
  };

  // ==================================================================
  // 收录
  // ==================================================================

  const enroll = useCallback(async () => {
    if (selectedCandidates.length === 0) {
      setError('请先勾选要收录的采集草稿');
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const result = await enrollBoxItems(selectedCandidates);
      const failed = result.outcomes.filter((o) => !o.ok);
      setNotice(
        `收录完成：成功 ${result.succeeded} 条，失败 ${result.failed} 条。` +
          (failed.length > 0 ? `（${failed.map((o) => o.reason).join('；')}）` : ''),
      );
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : '收录失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [refresh, selectedCandidates]);

  // ==================================================================
  // 批量打标
  // ==================================================================

  const applyTag = useCallback(
    async (action: 'ADD' | 'REMOVE') => {
      if (selected.length === 0) {
        setError('请先勾选要打标的条目');
        return;
      }
      if (!activeTagId) {
        setError('请先选一个标签');
        return;
      }
      setBusy(true);
      setError(null);
      setNotice(null);
      try {
        const result = await tagBoxItems(selected, activeTagId, action);
        setNotice(
          `打标完成：成功 ${result.succeeded} 条，失败 ${result.failed} 条。` +
            (action === 'ADD' ? '（已挂上标签）' : '（已摘掉标签）'),
        );
        await refresh();
      } catch (e) {
        setError(e instanceof Error ? e.message : '打标失败，请重试');
      } finally {
        setBusy(false);
      }
    },
    [activeTagId, refresh, selected],
  );

  // ==================================================================
  // 批量删除
  // ==================================================================

  const remove = useCallback(async () => {
    if (selected.length === 0) {
      setError('请先勾选要移出采集箱的条目');
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const result = await removeBoxItems(selected);
      const retained = retainedDraftOutcomes(result);
      setNotice(
        `已移出采集箱 ${result.succeeded} 条，失败 ${result.failed} 条。` +
          (retained.length > 0 ? ` ${retained.map((o) => o.reason).join(' ')}` : ''),
      );
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : '移出采集箱失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [refresh, selected]);

  // ==================================================================
  // 认领
  // ==================================================================

  const claim = useCallback(async () => {
    if (selected.length === 0) {
      setError('请先勾选要认领的条目');
      return;
    }
    if (!activeRuleId) {
      setError('请先选一条认领规则');
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const result = await claimBoxItems(selected, activeRuleId);
      setClaimResult(result);
      const failed = failedClaimOutcomes(result);
      setNotice(
        `${claimSummary(result)}` +
          (failed.length > 0 ? ` 失败原因：${failed.map((o) => o.reason).join('；')}` : ''),
      );
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : '认领失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [activeRuleId, refresh, selected]);

  // ==================================================================
  // 认领规则
  // ==================================================================

  const brandMappingError = validateMappingText(brandMappingText, '品牌映射');
  const categoryMappingError = validateMappingText(categoryMappingText, '分类映射');
  const namingError = validateNamingTemplate(namingTemplate);

  const submitRule = useCallback(async () => {
    setError(null);
    setNotice(null);
    if (!ruleName.trim()) {
      setError('请填写规则名');
      return;
    }
    if (brandMappingError) {
      setError(`🔴 ${brandMappingError}`);
      return;
    }
    if (!categoryMappingText.trim() && !targetCategory.trim()) {
      setError('🔴 请填写目标分类，或填写分类映射（源类目 = 目标分类）');
      return;
    }
    if (categoryMappingText.trim() && categoryMappingError) {
      setError(`🔴 ${categoryMappingError}`);
      return;
    }
    if (namingError) {
      setError(`🔴 ${namingError}`);
      return;
    }
    setBusy(true);
    try {
      const created = await createBoxRule({
        name: ruleName.trim(),
        targetCategoryCode: targetCategory.trim(),
        categoryMapping: parseMappingText(categoryMappingText),
        brandMapping: parseMappingText(brandMappingText),
        namingTemplate: namingTemplate.trim(),
        brandAttributeKey: '品牌',
        categoryAttributeKey: '类目',
      });
      setNotice(`认领规则「${created.name}」已创建。`);
      setRuleName('');
      setTargetCategory('');
      setCategoryMappingText('');
      setBrandMappingText('');
      setActiveRuleId(created.ruleId);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : '创建认领规则失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [
    brandMappingError,
    categoryMappingError,
    categoryMappingText,
    namingError,
    namingTemplate,
    refresh,
    ruleName,
    targetCategory,
    brandMappingText,
  ]);

  const dropRule = useCallback(
    async (ruleId: string) => {
      setBusy(true);
      setError(null);
      try {
        await deleteBoxRule(ruleId);
        setNotice('认领规则已删除。');
        if (activeRuleId === ruleId) setActiveRuleId('');
        await refresh();
      } catch (e) {
        setError(e instanceof Error ? e.message : '删除认领规则失败，请重试');
      } finally {
        setBusy(false);
      }
    },
    [activeRuleId, refresh],
  );

  // ==================================================================
  // 标签
  // ==================================================================

  const submitTag = useCallback(async () => {
    if (!newTagName.trim()) {
      setError('请填写标签名');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const created = await createBoxTag(newTagName.trim());
      setNotice(`标签「${created.name}」已创建。`);
      setNewTagName('');
      setActiveTagId(created.tagId);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : '创建标签失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [newTagName, refresh]);

  const dropTag = useCallback(
    async (tagId: string) => {
      setBusy(true);
      setError(null);
      try {
        await deleteBoxTag(tagId);
        setNotice('标签已删除（条目上的这个标签也一并摘掉了）。');
        if (activeTagId === tagId) setActiveTagId('');
        await refresh();
      } catch (e) {
        setError(e instanceof Error ? e.message : '删除标签失败，请重试');
      } finally {
        setBusy(false);
      }
    },
    [activeTagId, refresh],
  );

  const namingPreview = useMemo(
    () => previewNaming(namingTemplate, { brand: 'BRAND', title: '商品标题', model: 'M' }),
    [namingTemplate],
  );
  const undelivered = useMemo(() => unavailableDimensions(caps), [caps]);

  if (loading) {
    return (
      <div className="text-muted-foreground flex items-center justify-center gap-2 py-16">
        <Loader2 className="size-4 animate-spin" />
        <span className="text-label-13">正在加载采集箱…</span>
      </div>
    );
  }

  if (error && !caps) {
    return (
      <SectionCard title="采集箱">
        <EmptyState
          icon={<TriangleAlert className="text-destructive size-8" />}
          title="采集箱加载失败"
          description={error}
          action={
            <Button variant="secondary" onClick={() => void refresh()}>
              <RefreshCw className="mr-2 size-4" />
              重试
            </Button>
          }
        />
      </SectionCard>
    );
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="采集箱"
        description="采集结果的暂存区：按平台/来源、标签分组，勾选后批量认领为内部商品、批量打标、批量删除。"
      />

      {error && (
        <div className="border-destructive/40 bg-destructive/5 text-destructive flex items-start gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p className="text-label-13 whitespace-pre-wrap">{error}</p>
        </div>
      )}

      {notice && (
        <div className="border-brand/40 bg-brand-soft/40 flex items-start gap-2 rounded-lg border px-4 py-3">
          <CheckCircle2 className="text-brand mt-0.5 size-4 shrink-0" />
          <p className="text-label-13 whitespace-pre-wrap">{notice}</p>
        </div>
      )}

      {/* ---- 🔴 三条边界的界面措辞（后端给原文，前端原样展示） ---- */}
      {caps && (
        <div className="border-brand/40 bg-brand-soft/40 rounded-lg border px-4 py-3">
          <p className="text-foreground text-label-13 mb-1 font-medium">采集箱的三条硬边界</p>
          <ul className="text-muted-foreground text-label-12 space-y-1">
            <li className="flex items-start gap-2">
              <ShieldCheck className="text-brand mt-0.5 size-3.5 shrink-0" />
              <span>{caps.mappingNote}</span>
            </li>
            <li className="flex items-start gap-2">
              <Boxes className="text-brand mt-0.5 size-3.5 shrink-0" />
              <span>{caps.draftNote}</span>
            </li>
            <li className="flex items-start gap-2">
              <ImageIcon className="text-brand mt-0.5 size-3.5 shrink-0" />
              <span>{caps.mediaNote}</span>
            </li>
          </ul>
        </div>
      )}

      {/* ---- 未接入维度明账 ---- */}
      {undelivered.length > 0 && (
        <div className="border-warning/40 bg-warning/5 rounded-lg border px-4 py-3">
          <p className="text-warning text-label-13 mb-1 font-medium">部分能力未接入</p>
          <ul className="text-muted-foreground text-label-12 space-y-1">
            {undelivered.map((d) => (
              <li key={d.dimension}>
                <span className="text-foreground">{d.description}</span>
                ：归 {d.ownerModule} —— {d.impact}
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* ---- 分组统计（按平台/来源、按标签、按状态） ---- */}
      <SectionCard title="分组" description="按平台/来源、按标签、按认领状态分组的真实计数。">
        <div className="grid gap-4 px-4 py-4 md:grid-cols-3">
          <div>
            <p className="text-foreground text-label-13 mb-1 font-medium">按平台 / 来源</p>
            {sourceGroups.length === 0 ? (
              <p className="text-muted-foreground text-label-12">采集箱还是空的。</p>
            ) : (
              <ul className="text-muted-foreground text-label-12 space-y-0.5">
                {sourceGroups.map((g) => (
                  <li key={g.key}>
                    {g.label}（{g.key}）：{g.count} 条
                  </li>
                ))}
              </ul>
            )}
          </div>
          <div>
            <p className="text-foreground text-label-13 mb-1 font-medium">按标签</p>
            {tagGroups.length === 0 ? (
              <p className="text-muted-foreground text-label-12">还没有打过标签。</p>
            ) : (
              <ul className="text-muted-foreground text-label-12 space-y-0.5">
                {tagGroups.map((g) => (
                  <li key={g.key}>
                    {g.label}：{g.count} 条
                  </li>
                ))}
              </ul>
            )}
          </div>
          <div>
            <p className="text-foreground text-label-13 mb-1 font-medium">按认领状态</p>
            {stateGroups.length === 0 ? (
              <p className="text-muted-foreground text-label-12">采集箱还是空的。</p>
            ) : (
              <ul className="text-muted-foreground text-label-12 space-y-0.5">
                {stateGroups.map((g) => (
                  <li key={g.key}>
                    {itemStateLabel(g.key)}：{g.count} 条
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      </SectionCard>

      {/* ---- 待收录的采集草稿 ---- */}
      <SectionCard
        title="待收录的采集草稿"
        description={`来自 4.4.1.1 链接采集 / 4.4.1.2 批量·插件采集，共 ${candidateTotal} 条。勾选后收录进采集箱。`}
        extra={
          <div className="flex items-center gap-2">
            <Button
              size="sm"
              disabled={busy || selectedCandidates.length === 0}
              onClick={() => void enroll()}
            >
              {busy ? <Loader2 className="mr-2 size-4 animate-spin" /> : null}
              收录进采集箱（{selectedCandidates.length}）
            </Button>
            <Button variant="ghost" size="sm" onClick={() => void refresh()} disabled={busy}>
              <RefreshCw className="mr-2 size-4" />
              刷新
            </Button>
          </div>
        }
      >
        {candidates.length === 0 ? (
          <EmptyState
            icon={<Boxes className="text-muted-foreground size-8" />}
            title="没有待收录的采集草稿"
            description={emptyCandidatesMessage()}
          />
        ) : (
          <ul className="divide-border divide-y">
            {candidates.map((row) => (
              <li key={row.draftId} className="flex items-start gap-3 px-4 py-3">
                <input
                  type="checkbox"
                  className="mt-1"
                  checked={selectedCandidates.includes(row.draftId)}
                  onChange={() => toggle(selectedCandidates, row.draftId, setSelectedCandidates)}
                />
                <div className="min-w-0 flex-1">
                  <p className="text-foreground text-label-13 font-medium">{row.title}</p>
                  <p className="text-muted-foreground text-label-12">
                    {row.sourceName}（{row.sourceCode}） · 抓取于 {capturedAtLabel(row.capturedAt)}{' '}
                    · 来源页面原文标价{' '}
                    {sourcePriceLabel(row.sourcePriceAmount, row.sourcePriceCurrency)}
                    （不是我方售价）
                  </p>
                </div>
              </li>
            ))}
          </ul>
        )}
      </SectionCard>

      {/* ---- 采集箱 ---- */}
      <SectionCard
        title="采集箱"
        description={`共 ${total} 条。勾选后可批量认领 / 打标 / 移出采集箱。`}
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <select
              className="border-border bg-background text-foreground text-label-12 h-8 rounded-md border px-2"
              value={stateFilter}
              onChange={(e) => {
                setStateFilter(e.target.value);
                void refresh();
              }}
            >
              <option value="">全部状态</option>
              <option value="PENDING">待认领</option>
              <option value="CLAIMED">已认领</option>
            </select>
            <select
              className="border-border bg-background text-foreground text-label-12 h-8 rounded-md border px-2"
              value={sourceFilter}
              onChange={(e) => {
                setSourceFilter(e.target.value);
                void refresh();
              }}
            >
              <option value="">全部来源</option>
              {sourceGroups.map((g) => (
                <option key={g.key} value={g.key}>
                  {g.label}
                </option>
              ))}
            </select>
            <select
              className="border-border bg-background text-foreground text-label-12 h-8 rounded-md border px-2"
              value={tagFilter}
              onChange={(e) => {
                setTagFilter(e.target.value);
                void refresh();
              }}
            >
              <option value="">全部标签</option>
              {tags.map((t) => (
                <option key={t.tagId} value={t.tagId}>
                  {t.name}（{t.itemCount}）
                </option>
              ))}
            </select>
          </div>
        }
      >
        {items.length === 0 ? (
          <EmptyState
            icon={<Boxes className="text-muted-foreground size-8" />}
            title="采集箱是空的"
            description={emptyBoxMessage()}
          />
        ) : (
          <ul className="divide-border divide-y">
            {items.map((row) => (
              <li key={row.itemId} className="flex items-start gap-3 px-4 py-3">
                <input
                  type="checkbox"
                  className="mt-1"
                  checked={selected.includes(row.itemId)}
                  onChange={() => toggle(selected, row.itemId, setSelected)}
                />
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={itemStateTone(row.state)}>{itemStateLabel(row.state)}</Badge>
                    <span className="text-muted-foreground text-label-12">
                      {originLabel(row.origin)}
                    </span>
                    {row.tags.map((t) => (
                      <Badge key={t.tagId} tone="neutral">
                        <TagIcon className="mr-1 size-3" />
                        {t.name}
                      </Badge>
                    ))}
                  </div>
                  <p className="text-foreground text-label-13 mt-1 font-medium">{row.title}</p>
                  <p className="text-muted-foreground text-label-12">
                    {row.sourceName}（{row.sourceCode}） · 抓取于 {capturedAtLabel(row.capturedAt)}{' '}
                    · 来源页面原文标价{' '}
                    {sourcePriceLabel(row.sourcePriceAmount, row.sourcePriceCurrency)}
                    （不是我方售价）
                  </p>
                  {row.claimError && (
                    <p className="text-destructive text-label-12 mt-1">🔴 {row.claimError}</p>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </SectionCard>

      {/* ---- 批量操作 ---- */}
      <SectionCard
        title="批量操作"
        description={`已勾选 ${selected.length} 条。认领要选一条规则；打标要选一个标签。`}
      >
        <div className="space-y-3 px-4 py-4">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-muted-foreground text-label-12">认领规则</span>
            <select
              className="border-border bg-background text-foreground text-label-12 h-8 rounded-md border px-2"
              value={activeRuleId}
              onChange={(e) => setActiveRuleId(e.target.value)}
            >
              <option value="">请选择规则</option>
              {rules.map((r) => (
                <option key={r.ruleId} value={r.ruleId}>
                  {r.name}
                </option>
              ))}
            </select>
            <Button
              size="sm"
              disabled={busy || selected.length === 0 || !activeRuleId}
              onClick={() => void claim()}
            >
              {busy ? <Loader2 className="mr-2 size-4 animate-spin" /> : null}
              批量认领
            </Button>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <span className="text-muted-foreground text-label-12">标签</span>
            <select
              className="border-border bg-background text-foreground text-label-12 h-8 rounded-md border px-2"
              value={activeTagId}
              onChange={(e) => setActiveTagId(e.target.value)}
            >
              <option value="">请选择标签</option>
              {tags.map((t) => (
                <option key={t.tagId} value={t.tagId}>
                  {t.name}
                </option>
              ))}
            </select>
            <Button
              variant="secondary"
              size="sm"
              disabled={busy || selected.length === 0 || !activeTagId}
              onClick={() => void applyTag('ADD')}
            >
              挂上标签
            </Button>
            <Button
              variant="secondary"
              size="sm"
              disabled={busy || selected.length === 0 || !activeTagId}
              onClick={() => void applyTag('REMOVE')}
            >
              摘掉标签
            </Button>
            <Button
              variant="destructive"
              size="sm"
              disabled={busy || selected.length === 0}
              onClick={() => void remove()}
            >
              <Trash2 className="mr-2 size-4" />
              移出采集箱
            </Button>
          </div>

          <p className="text-muted-foreground text-label-12">🔴 {MAPPING_REQUIRED_NOTE}</p>
          <p className="text-muted-foreground text-label-12">🔴 {DRAFT_ONLY_NOTE}</p>
        </div>
      </SectionCard>

      {/* ---- 认领结果 ---- */}
      {claimResult && (
        <SectionCard
          title="认领结果"
          description="认领产出的是商品草稿载荷 —— 落库归 3.1.1.1 商品建档，本模块不跨服务写商品库。"
        >
          <div className="space-y-3 px-4 py-4">
            <p className="text-foreground text-label-13">{claimSummary(claimResult)}</p>
            {failedClaimOutcomes(claimResult).map((o) => (
              <p key={o.itemId} className="text-destructive text-label-12">
                🔴 {o.reason}
              </p>
            ))}
            {claimResult.outcomes
              .filter((o) => o.ok && o.claimResult)
              .map((o) => {
                const claim = o.claimResult!;
                return (
                  <div key={o.itemId} className="border-border rounded-lg border px-3 py-2">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone="success">已认领</Badge>
                      <span className="text-foreground text-label-13 font-medium">
                        {claim.spuName}
                      </span>
                    </div>
                    <dl className="text-muted-foreground text-label-12 mt-1 space-y-0.5">
                      <div className="flex gap-2">
                        <dt className="min-w-24 shrink-0">目标品牌</dt>
                        <dd>
                          {claim.targetBrandCode}（源品牌：{claim.sourceBrand}）
                        </dd>
                      </div>
                      <div className="flex gap-2">
                        <dt className="min-w-24 shrink-0">目标分类</dt>
                        <dd>
                          {claim.targetCategoryCode}（源类目：{claim.sourceCategory}）
                        </dd>
                      </div>
                      <div className="flex gap-2">
                        <dt className="min-w-24 shrink-0">预填标题</dt>
                        <dd>{claim.spuDraft.title}</dd>
                      </div>
                      <div className="flex gap-2">
                        <dt className="min-w-24 shrink-0">SKU 草稿</dt>
                        <dd>{claim.skuDraftCount} 行</dd>
                      </div>
                      <div className="flex gap-2">
                        <dt className="min-w-24 shrink-0">落库状态</dt>
                        <dd>{claim.buildState}</dd>
                      </div>
                    </dl>
                    {claim.spuDraft.specs.length > 0 && (
                      <div className="mt-1">
                        <p className="text-foreground text-label-12 font-medium">预填规格</p>
                        <ul className="text-muted-foreground text-label-12 space-y-0.5">
                          {mappingRows(claim.spuDraft.specs).map((s) => (
                            <li key={s.name}>
                              {s.name}：{s.value}
                            </li>
                          ))}
                        </ul>
                      </div>
                    )}
                    <p className="text-muted-foreground text-label-12 mt-1">{claim.buildNote}</p>
                    <p className="text-muted-foreground text-label-12">🔴 {IMAGE_URL_ONLY_NOTE}</p>
                  </div>
                );
              })}
          </div>
        </SectionCard>
      )}

      {/* ---- 认领规则 ---- */}
      <SectionCard
        title="认领规则"
        description="认领规则要同时给「目标分类（或分类映射）」「品牌映射」「命名规则」—— 没有品牌映射就不能认领。"
      >
        <div className="space-y-4 px-4 py-4">
          {rules.length === 0 ? (
            <EmptyState
              icon={<ShieldCheck className="text-muted-foreground size-8" />}
              title="还没有认领规则"
              description={emptyRulesMessage()}
            />
          ) : (
            <ul className="divide-border divide-y">
              {rules.map((r) => (
                <li key={r.ruleId} className="flex items-start justify-between gap-3 py-3">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={activeRuleId === r.ruleId ? 'success' : 'neutral'}>
                        {activeRuleId === r.ruleId ? '当前选用' : r.state}
                      </Badge>
                      <span className="text-foreground text-label-13 font-medium">{r.name}</span>
                    </div>
                    <p className="text-muted-foreground text-label-12">
                      目标分类：{r.targetCategoryCode || '（无，靠分类映射）'} · 命名规则：
                      {r.namingTemplate}
                    </p>
                    <p className="text-muted-foreground text-label-12">
                      品牌映射：
                      {r.brandMapping.length > 0
                        ? r.brandMapping.map((m) => `${m.name} → ${m.value}`).join('、')
                        : '（无）'}
                    </p>
                    <p className="text-muted-foreground text-label-12">
                      分类映射：
                      {r.categoryMapping.length > 0
                        ? r.categoryMapping.map((m) => `${m.name} → ${m.value}`).join('、')
                        : '（无）'}
                    </p>
                  </div>
                  <div className="flex shrink-0 gap-2">
                    <Button
                      variant="secondary"
                      size="sm"
                      disabled={busy}
                      onClick={() => setActiveRuleId(r.ruleId)}
                    >
                      选用
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      disabled={busy}
                      onClick={() => void dropRule(r.ruleId)}
                    >
                      <Trash2 className="size-4" />
                    </Button>
                  </div>
                </li>
              ))}
            </ul>
          )}

          <div className="border-border space-y-2 rounded-lg border px-3 py-3">
            <p className="text-foreground text-label-13 font-medium">新建认领规则</p>
            <Input
              className="max-w-lg"
              placeholder="规则名，例如「女装-2026 秋季」"
              value={ruleName}
              onChange={(e) => setRuleName(e.target.value)}
            />
            <Input
              className="max-w-lg"
              placeholder="目标分类编码（例如 CAT-WOMEN）"
              value={targetCategory}
              onChange={(e) => setTargetCategory(e.target.value)}
            />
            <div>
              <p className="text-muted-foreground text-label-12">
                品牌映射（**必填**，一行一个「源品牌 = 目标品牌」）
              </p>
              <textarea
                className="border-border bg-background text-foreground text-label-12 min-h-20 w-full rounded-md border px-2 py-1"
                placeholder={'Acme = BRAND-ACME\nNike = BRAND-NIKE'}
                value={brandMappingText}
                onChange={(e) => setBrandMappingText(e.target.value)}
              />
            </div>
            <div>
              <p className="text-muted-foreground text-label-12">
                分类映射（一行一个「源类目 = 目标分类」，可留空）
              </p>
              <textarea
                className="border-border bg-background text-foreground text-label-12 min-h-20 w-full rounded-md border px-2 py-1"
                placeholder={'女装 = CAT-WOMEN-DRESS\n男装 = CAT-MEN-SHIRT'}
                value={categoryMappingText}
                onChange={(e) => setCategoryMappingText(e.target.value)}
              />
            </div>
            <div>
              <p className="text-muted-foreground text-label-12">
                命名规则模板（占位符：{BOX_NAMING_PLACEHOLDERS.join(' ')}）
              </p>
              <Input
                className="max-w-lg"
                value={namingTemplate}
                onChange={(e) => setNamingTemplate(e.target.value)}
              />
              <p className="text-muted-foreground text-label-12 mt-1">
                预览：{namingPreview || '（空）'}
              </p>
            </div>
            <Button size="sm" disabled={busy} onClick={() => void submitRule()}>
              {busy ? <Loader2 className="mr-2 size-4 animate-spin" /> : null}
              创建规则
            </Button>
          </div>
        </div>
      </SectionCard>

      {/* ---- 标签 ---- */}
      <SectionCard title="标签" description="批量打标的词表（同一租户内标签名唯一）。">
        <div className="space-y-3 px-4 py-4">
          {tags.length === 0 ? (
            <p className="text-muted-foreground text-label-12">还没有标签。</p>
          ) : (
            <ul className="flex flex-wrap gap-2">
              {tags.map((t) => (
                <li key={t.tagId} className="flex items-center gap-1">
                  <Button
                    variant={activeTagId === t.tagId ? 'secondary' : 'ghost'}
                    size="sm"
                    disabled={busy}
                    onClick={() => setActiveTagId(t.tagId)}
                  >
                    <TagIcon className="mr-1 size-3" />
                    {t.name}（{t.itemCount}）
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    disabled={busy}
                    onClick={() => void dropTag(t.tagId)}
                    aria-label={`删除标签 ${t.name}`}
                  >
                    <Trash2 className="size-3.5" />
                  </Button>
                </li>
              ))}
            </ul>
          )}
          <div className="flex flex-wrap items-center gap-2">
            <Input
              className="max-w-xs"
              placeholder="新标签名，例如「待议价」"
              value={newTagName}
              onChange={(e) => setNewTagName(e.target.value)}
            />
            <Button size="sm" disabled={busy} onClick={() => void submitTag()}>
              {busy ? <Loader2 className="mr-2 size-4 animate-spin" /> : null}
              建标签
            </Button>
          </div>
        </div>
      </SectionCard>
    </div>
  );
}
