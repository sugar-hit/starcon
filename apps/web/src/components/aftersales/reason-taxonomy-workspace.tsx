'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  AftersalesReasonAttributionView,
  AftersalesReasonCapabilities,
  AftersalesReasonMappingList,
  AftersalesReasonReport,
  AftersalesReasonTree,
  AftersalesReasonTreeNode,
} from '../../../../../packages/api-contracts/src/index';
import {
  type AftersalesReasonDraft,
  assignableNodes,
  createReasonCategory,
  createReasonMapping,
  deleteReasonCategory,
  deleteReasonMapping,
  draftFromAttribution,
  draftItemCount,
  draftWeightSum,
  emptyDraft,
  fetchReasonAttribution,
  fetchReasonCapabilities,
  fetchReasonMappings,
  fetchReasonReport,
  fetchReasonTree,
  flattenTree,
  nodePresentation,
  notePresentation,
  pickPrimary,
  setWeight,
  shareText,
  submitReasonAttribution,
  toAttributionCreate,
  toggleSecondary,
  updateReasonCategory,
  updateReasonMapping,
  validateAttributionDraft,
  weightSumHint,
} from './reason-taxonomy-model';

/**
 * 11.1.2.1 售后原因分类。
 *
 * <p>蓝图：「维护售后原因分类树（质量问题、描述不符、尺寸不符、物流损坏、买家原因、误购、
 * 欺诈退货），支持多选与平台原因码映射，驱动后续的质量归因分析。」
 * 加粗句：「<b>原因口径不统一会让退货率分析失真</b> —— 必须强制选择分类而不是留自由文本。」
 *
 * <p><b>本页面是真实工作区</b>：分类树维护、多选归因、平台原因码映射、统一口径报表，
 * 加载态 / 空态 / 错误态齐全，没有任何占位数据。
 *
 * <p><b>加粗句在本页面上的三处落点</b>：
 *
 * <ol>
 *   <li><b>归因表单的主因是单选按钮、其它是复选框</b> ——
 *       「恰好一个主因」是交互上的事实，不是提交时的报错。
 *       换主因时旧主因<b>自动降级成次因</b>而不是被丢掉。</li>
 *   <li><b>补充说明标成「非归因本身」并放在最下面</b> ——
 *       一个都没选时提交按钮直接不可用，提示语说的是「必须选一个主因」，
 *       而不是让运营写完 200 字说明再被拒。</li>
 *   <li><b>停用的分类整支灰掉并写明原因</b>（「上级分类已停用」）——
 *       判定全部来自后端的 {@code assignable}，前端不自己判断停用。
 *       这是加粗句的另一面：口径必须<b>唯一且显式</b>，而不是各处随手选。</li>
 * </ol>
 *
 * <p><b>平台原因码映射诚实标注归属</b>：上游 2.1.2.1 的表在 channel 服务独立库，
 * 按模块边界纪律禁止跨服务读 ⇒ 本模块用的是 <b>trade 自己的表</b>，
 * 页面上直接显示后端返回的归属说明，不假装「已经和平台同步了」。
 */
export function ReasonTaxonomyWorkspace() {
  const [caps, setCaps] = useState<AftersalesReasonCapabilities | null>(null);
  const [tree, setTree] = useState<AftersalesReasonTree | null>(null);
  const [report, setReport] = useState<AftersalesReasonReport | null>(null);
  const [mappings, setMappings] = useState<AftersalesReasonMappingList | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // ---- 分类树维护 ----
  const [filters, setFilters] = useState({ categoryStatus: '', rootKind: '', assignableOnly: false });
  const [newCategory, setNewCategory] = useState({
    parentId: '',
    categoryCode: '',
    categoryName: '',
    rootKind: '',
    sortOrder: '0',
    description: '',
  });
  const [treeBusy, setTreeBusy] = useState(false);

  // ---- 归因 ----
  const [caseId, setCaseId] = useState('');
  const [attribution, setAttribution] = useState<AftersalesReasonAttributionView | null>(null);
  const [draft, setDraft] = useState<AftersalesReasonDraft | null>(null);
  const [attrBusy, setAttrBusy] = useState(false);
  const [attrMessage, setAttrMessage] = useState<string | null>(null);

  // ---- 平台原因码映射 ----
  const [mappingFilter, setMappingFilter] = useState({ platformSource: '', mappingStatus: '' });
  const [newMapping, setNewMapping] = useState({
    categoryId: '',
    platformSource: 'AMAZON',
    platformReasonCode: '',
    platformReasonName: '',
  });
  const [mappingBusy, setMappingBusy] = useState(false);
  const [mappingMessage, setMappingMessage] = useState<string | null>(null);

  const loadTree = useCallback(
    async (f = filters) => {
      const next = await fetchReasonTree({
        categoryStatus: f.categoryStatus || undefined,
        rootKind: f.rootKind || undefined,
        assignableOnly: f.assignableOnly || undefined,
      });
      setTree(next);
      return next;
    },
    [filters],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [c, t, r, m] = await Promise.all([
        fetchReasonCapabilities(),
        fetchReasonTree({
          categoryStatus: filters.categoryStatus || undefined,
          rootKind: filters.rootKind || undefined,
          assignableOnly: filters.assignableOnly || undefined,
        }),
        fetchReasonReport(),
        fetchReasonMappings({
          platformSource: mappingFilter.platformSource || undefined,
          mappingStatus: mappingFilter.mappingStatus || undefined,
        }),
      ]);
      setCaps(c);
      setTree(t);
      setReport(r);
      setMappings(m);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [filters, mappingFilter]);

  useEffect(() => {
    void load();
  }, [load]);

  const nodes = useMemo(() => (tree ? flattenTree(tree) : []), [tree]);
  const assignable = useMemo(() => (tree ? assignableNodes(tree) : []), [tree]);
  const validation = draft ? validateAttributionDraft(draft) : null;
  const sum = draft ? weightSumHint(draftWeightSum(draft)) : null;

  // ==========================================================================
  // 分类树维护
  // ==========================================================================
  const submitCategory = async () => {
    setTreeBusy(true);
    setError(null);
    try {
      await createReasonCategory({
        parentId: newCategory.parentId || null,
        categoryCode: newCategory.categoryCode,
        categoryName: newCategory.categoryName,
        // 建根必须给 rootKind；建子节点必须不给 —— 由「有没有选父分类」决定。
        rootKind: newCategory.parentId ? null : newCategory.rootKind || null,
        sortOrder: Number(newCategory.sortOrder) || 0,
        description: newCategory.description || null,
      });
      setNewCategory({
        parentId: '',
        categoryCode: '',
        categoryName: '',
        rootKind: '',
        sortOrder: '0',
        description: '',
      });
      await loadTree();
      setAttrMessage(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存分类失败');
    } finally {
      setTreeBusy(false);
    }
  };

  const toggleStatus = async (node: AftersalesReasonTreeNode) => {
    setTreeBusy(true);
    setError(null);
    try {
      await updateReasonCategory(node.id, {
        categoryName: null,
        categoryStatus: node.categoryStatus === 'ACTIVE' ? 'DISABLED' : 'ACTIVE',
        sortOrder: null,
        parentId: null,
        description: '',
        moveToRoot: false,
      });
      await loadTree();
    } catch (e) {
      setError(e instanceof Error ? e.message : '更新分类失败');
    } finally {
      setTreeBusy(false);
    }
  };

  const removeCategory = async (node: AftersalesReasonTreeNode) => {
    setTreeBusy(true);
    setError(null);
    try {
      await deleteReasonCategory(node.id);
      await loadTree();
    } catch (e) {
      // 被引用过的分类删不掉 —— 这是口径完整性要求，界面原样把原因显示出来。
      setError(e instanceof Error ? e.message : '删除分类失败');
    } finally {
      setTreeBusy(false);
    }
  };

  // ==========================================================================
  // 归因
  // ==========================================================================
  const loadAttribution = async () => {
    const id = caseId.trim();
    if (!id) {
      setAttrMessage('请填写售后单 ID');
      return;
    }
    setAttrBusy(true);
    setAttrMessage(null);
    setError(null);
    try {
      const view = await fetchReasonAttribution(id);
      setAttribution(view);
      setDraft(draftFromAttribution(view));
    } catch (e) {
      setError(e instanceof Error ? e.message : '读取归因失败');
    } finally {
      setAttrBusy(false);
    }
  };

  const saveAttribution = async () => {
    if (!draft) return;
    const v = validateAttributionDraft(draft);
    if (!v.ok) {
      setAttrMessage(v.message);
      return;
    }
    setAttrBusy(true);
    setAttrMessage(null);
    setError(null);
    try {
      const view = await submitReasonAttribution(toAttributionCreate(draft));
      setAttribution(view);
      setDraft(draftFromAttribution(view));
      await loadTree();
    } catch (e) {
      setAttrMessage(e instanceof Error ? e.message : '保存归因失败');
    } finally {
      setAttrBusy(false);
    }
  };

  // ==========================================================================
  // 平台原因码映射
  // ==========================================================================
  const submitMapping = async () => {
    setMappingBusy(true);
    setMappingMessage(null);
    setError(null);
    try {
      await createReasonMapping({
        categoryId: newMapping.categoryId,
        platformSource: newMapping.platformSource,
        platformReasonCode: newMapping.platformReasonCode,
        platformReasonName: newMapping.platformReasonName || null,
        mappingSource: null,
        sourceReference: null,
        capturedAt: null,
        mappingStatus: null,
      });
      setNewMapping({
        categoryId: '',
        platformSource: 'AMAZON',
        platformReasonCode: '',
        platformReasonName: '',
      });
      setMappings(
        await fetchReasonMappings({
          platformSource: mappingFilter.platformSource || undefined,
          mappingStatus: mappingFilter.mappingStatus || undefined,
        }),
      );
    } catch (e) {
      setMappingMessage(e instanceof Error ? e.message : '保存映射失败');
    } finally {
      setMappingBusy(false);
    }
  };

  const toggleMapping = async (id: string, current: string) => {
    setMappingBusy(true);
    setMappingMessage(null);
    try {
      await updateReasonMapping(id, {
        // ⚠️ 这里**必须传 null 而不是空串**：本调用只切启用/停用，不动内部分类。
        // 空串能蒙对全靠 Jackson 的空串→null 强制转换，那是实现细节不是契约；
        // 契约把 categoryId 标成 nullable 之后，显式 null 才是「不改内部分类」的诚实写法。
        categoryId: null,
        platformSource: '',
        platformReasonCode: '',
        platformReasonName: null,
        mappingSource: null,
        sourceReference: null,
        capturedAt: null,
        mappingStatus: current === 'ACTIVE' ? 'DISABLED' : 'ACTIVE',
      });
      setMappings(
        await fetchReasonMappings({
          platformSource: mappingFilter.platformSource || undefined,
          mappingStatus: mappingFilter.mappingStatus || undefined,
        }),
      );
    } catch (e) {
      setMappingMessage(e instanceof Error ? e.message : '更新映射失败');
    } finally {
      setMappingBusy(false);
    }
  };

  const removeMapping = async (id: string) => {
    setMappingBusy(true);
    setMappingMessage(null);
    try {
      await deleteReasonMapping(id);
      setMappings(
        await fetchReasonMappings({
          platformSource: mappingFilter.platformSource || undefined,
          mappingStatus: mappingFilter.mappingStatus || undefined,
        }),
      );
    } catch (e) {
      // 同步器写的行只许停用 —— 界面把这条规则原样说出来。
      setMappingMessage(e instanceof Error ? e.message : '删除映射失败');
    } finally {
      setMappingBusy(false);
    }
  };

  // ==========================================================================
  // 渲染
  // ==========================================================================
  if (loading) {
    return (
      <PageHeader title="售后原因分类" description="正在加载分类树、报表与平台原因码映射…" />
    );
  }
  if (error && !tree) {
    return (
      <>
        <PageHeader title="售后原因分类" description="加载失败" />
        <SectionCard title="错误" description={error}>
          <Button variant="outline" size="sm" onClick={() => void load()}>
            <RefreshCw className="mr-2 h-4 w-4" />
            重试
          </Button>
        </SectionCard>
      </>
    );
  }

  const note = notePresentation(draft?.note ?? '');

  return (
    <div className="space-y-6">
      <PageHeader
        title="售后原因分类"
        description={
          caps?.forcedClassificationNote ??
          '维护售后原因分类树，支持多选与平台原因码映射，驱动后续的质量归因分析。'
        }
        actions={
          <Button variant="outline" size="sm" onClick={() => void load()}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {error ? (
        <div className="flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 p-3 text-sm">
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0 text-amber-600" />
          <span>{error}</span>
        </div>
      ) : null}

      {/* ---- 顶部四张卡：口径现状一眼可见 ---- */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="分类总数" value={tree?.totalCount ?? 0} />
        <StatCard label="启用中" value={tree?.activeCount ?? 0} />
        <StatCard label="已归因售后单" value={report?.attributedCaseCount ?? 0} />
        <StatCard label="未归因售后单" value={report?.unattributedCaseCount ?? 0} />
      </div>

      {/* ---- 能力与缺口 ---- */}
      <SectionCard title="能力与上下游" description={caps?.platformMappingNote ?? ''}>
        <ul className="space-y-2 text-sm">
          {caps?.unavailableDimensions.map((d) => (
            <li key={d.name} className="rounded-md border border-border p-3">
              <div className="flex items-center gap-2">
                <span className="font-medium">{d.label}</span>
                <span className="rounded bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">
                  不可得 · 归属 {d.ownerModule}
                </span>
              </div>
              <p className="mt-1 text-muted-foreground">{d.detail}</p>
            </li>
          ))}
        </ul>
        <p className="mt-3 text-xs text-muted-foreground">{caps?.reportSemantics}</p>
      </SectionCard>

      {/* ---- 归因 ---- */}
      <SectionCard
        title="售后单归因"
        description="必须强制选择分类：至少一个原因、恰好一个主因、权重必填。补充说明只是补充，不能替代分类选择。"
      >
        <div className="flex flex-wrap items-end gap-3">
          <div className="min-w-[320px] flex-1">
            <label className="mb-1 block text-sm font-medium" htmlFor="reason-case">
              售后单 ID
            </label>
            <Input
              id="reason-case"
              value={caseId}
              placeholder="输入售后单 ID 后读取当前归因"
              onChange={(e) => setCaseId(e.target.value)}
            />
          </div>
          <Button onClick={() => void loadAttribution()} disabled={attrBusy || !caseId.trim()}>
            {attrBusy ? '处理中…' : '读取当前归因'}
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              setAttribution(null);
              setDraft(caseId.trim() ? emptyDraft(caseId.trim()) : null);
              setAttrMessage(null);
            }}
          >
            清空
          </Button>
        </div>

        {attribution && !attribution.attributed ? (
          <p className="mt-3 rounded-md border border-border p-3 text-sm text-muted-foreground">
            这张售后单<strong className="text-foreground">还没有归因</strong>。
            「还没归因」与「归因为空」是两件不同的事 —— 后者在数据库层就提交不了。
          </p>
        ) : null}

        {attribution?.attributed && !draft ? null : null}

        {draft ? (
          <div className="mt-4 space-y-4">
            {attribution?.current ? (
              <p className="text-sm text-muted-foreground">
                当前生效：{attribution.current.itemCount} 个原因 /
                主因 {attribution.current.primaryCount} 个 · 历史版本{' '}
                {attribution.versionCount} 版 · 归因于 {attribution.current.attributedAt}
              </p>
            ) : null}

            <div>
              <p className="mb-2 text-sm font-medium">
                可选分类（{assignable.length}）
                <span className="ml-2 text-xs text-muted-foreground">
                  灰掉的分类不能被新归因选中，停用是一个显式的口径变更动作
                </span>
              </p>
              {assignable.length === 0 ? (
                <EmptyState title="没有可归因的分类" description="分类树为空或全部被停用。" />
              ) : (
                <div className="max-h-72 overflow-auto rounded-md border border-border">
                  <ul className="divide-y divide-border">
                    {nodes.map((node) => {
                      const p = nodePresentation(node);
                      const chosenPrimary = draft.primary?.categoryId === node.id;
                      const chosenSecondary = draft.secondaries.some((s) => s.categoryId === node.id);
                      return (
                        <li
                          key={node.id}
                          className="flex items-center gap-3 px-3 py-2 text-sm"
                          style={{ paddingLeft: `${12 + p.level * 18}px` }}
                        >
                          <input
                            type="radio"
                            name="reason-primary"
                            checked={chosenPrimary}
                            disabled={!node.assignable}
                            onChange={() => setDraft(pickPrimary(draft, node))}
                            aria-label={`设为主因：${node.categoryName}`}
                          />
                          <input
                            type="checkbox"
                            checked={chosenSecondary}
                            disabled={!node.assignable || chosenPrimary}
                            onChange={() => setDraft(toggleSecondary(draft, node))}
                            aria-label={`作为次因：${node.categoryName}`}
                          />
                          <span className="truncate" title={p.codePath}>
                            {p.prefix}
                            {node.categoryName}
                          </span>
                          <span className="ml-auto shrink-0 text-xs text-muted-foreground">
                            {node.rootKindLabel ?? `第 ${node.depth} 层`} · {node.categoryCode}
                          </span>
                        </li>
                      );
                    })}
                  </ul>
                </div>
              )}
            </div>

            {draftItemCount(draft) > 0 ? (
              <div className="space-y-2 rounded-md border border-border p-3">
                {draft.primary ? (
                  <label className="flex items-center gap-3 text-sm">
                    <span className="w-16 shrink-0 font-medium">主因</span>
                    <span className="flex-1 truncate">{draft.primary.categoryName}</span>
                    <Input
                      className="w-32"
                      value={draft.primary.weight}
                      onChange={(e) =>
                        setDraft(setWeight(draft, draft.primary!.categoryId, e.target.value))
                      }
                      aria-label="主因权重"
                    />
                  </label>
                ) : null}
                {draft.secondaries.map((s) => (
                  <label key={s.categoryId} className="flex items-center gap-3 text-sm">
                    <span className="w-16 shrink-0 text-muted-foreground">次因</span>
                    <span className="flex-1 truncate">{s.categoryName}</span>
                    <Input
                      className="w-32"
                      value={s.weight}
                      onChange={(e) => setDraft(setWeight(draft, s.categoryId, e.target.value))}
                      aria-label={`${s.categoryName} 权重`}
                    />
                  </label>
                ))}
                <p
                  className={`text-xs ${sum?.tone === 'ok' ? 'text-muted-foreground' : 'text-amber-600'}`}
                >
                  {sum?.text}
                </p>
              </div>
            ) : null}

            <div>
              <label className="mb-1 block text-sm font-medium" htmlFor="reason-note">
                {note.label}
              </label>
              <Input
                id="reason-note"
                value={draft.note}
                placeholder="可选。写了说明但没选分类，是提交不了的。"
                onChange={(e) => setDraft({ ...draft, note: e.target.value })}
              />
            </div>

            {validation && !validation.ok ? (
              <p className="rounded-md border border-amber-500/40 bg-amber-500/10 p-3 text-sm">
                {validation.message}
              </p>
            ) : null}
            {attrMessage ? (
              <p className="rounded-md border border-amber-500/40 bg-amber-500/10 p-3 text-sm">
                {attrMessage}
              </p>
            ) : null}

            <div className="flex items-center gap-3">
              <Button
                onClick={() => void saveAttribution()}
                disabled={attrBusy || !validation?.ok}
                title={validation?.ok ? undefined : (validation?.message ?? undefined)}
              >
                {attrBusy ? '保存中…' : '保存归因'}
              </Button>
              <span className="text-xs text-muted-foreground">
                {attribution?.enforcementNote ?? ''}
              </span>
            </div>
          </div>
        ) : null}
      </SectionCard>

      {/* ---- 分类树维护 ---- */}
      <SectionCard title="分类树维护" description={tree?.treeRules ?? ''}>
        <div className="mb-4 flex flex-wrap items-end gap-3">
          <div>
            <label className="mb-1 block text-xs text-muted-foreground" htmlFor="f-status">
              状态
            </label>
            <select
              id="f-status"
              className="h-9 rounded-md border border-border bg-transparent px-2 text-sm"
              value={filters.categoryStatus}
              onChange={(e) => setFilters({ ...filters, categoryStatus: e.target.value })}
            >
              <option value="">全部</option>
              <option value="ACTIVE">启用</option>
              <option value="DISABLED">停用</option>
            </select>
          </div>
          <div>
            <label className="mb-1 block text-xs text-muted-foreground" htmlFor="f-root">
              根口径
            </label>
            <select
              id="f-root"
              className="h-9 rounded-md border border-border bg-transparent px-2 text-sm"
              value={filters.rootKind}
              onChange={(e) => setFilters({ ...filters, rootKind: e.target.value })}
            >
              <option value="">全部七类</option>
              {caps?.rootKinds.map((k) => (
                <option key={k.value} value={k.value}>
                  {k.label}
                </option>
              ))}
            </select>
          </div>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={filters.assignableOnly}
              onChange={(e) => setFilters({ ...filters, assignableOnly: e.target.checked })}
            />
            只看当前可归因的
          </label>
          <Button variant="outline" size="sm" onClick={() => void load()}>
            应用筛选
          </Button>
        </div>

        {nodes.length === 0 ? (
          <EmptyState title="分类树为空" description="新建一个根分类开始（七类之一）。" />
        ) : (
          <div className="overflow-x-auto rounded-md border border-border">
            <table className="w-full text-sm">
              <thead className="bg-muted/40 text-left text-xs text-muted-foreground">
                <tr>
                  <th className="px-3 py-2 font-medium">分类</th>
                  <th className="px-3 py-2 font-medium">编码</th>
                  <th className="px-3 py-2 font-medium">根口径</th>
                  <th className="px-3 py-2 font-medium">状态</th>
                  <th className="px-3 py-2 font-medium">排序</th>
                  <th className="px-3 py-2 font-medium">引用</th>
                  <th className="px-3 py-2 font-medium">操作</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {nodes.map((node) => {
                  const p = nodePresentation(node);
                  return (
                    <tr key={node.id} className={node.assignable ? '' : 'opacity-60'}>
                      <td className="px-3 py-2" style={{ paddingLeft: `${12 + p.level * 16}px` }}>
                        <div>{p.prefix}{node.categoryName}</div>
                        {p.hint ? (
                          <div className="text-xs text-muted-foreground">{p.hint}</div>
                        ) : null}
                      </td>
                      <td className="px-3 py-2 font-mono text-xs">{node.categoryCode}</td>
                      <td className="px-3 py-2">{node.rootKindLabel ?? '—'}</td>
                      <td className="px-3 py-2">{node.categoryStatusLabel}</td>
                      <td className="px-3 py-2 tabular-nums">{node.sortOrder}</td>
                      <td className="px-3 py-2 tabular-nums">
                        {node.attributionCount}
                        {node.childCount > 0 ? ` · 子 ${node.childCount}` : ''}
                      </td>
                      <td className="px-3 py-2">
                        <div className="flex gap-2">
                          <Button
                            variant="outline"
                            size="sm"
                            disabled={treeBusy}
                            onClick={() => void toggleStatus(node)}
                          >
                            {node.categoryStatus === 'ACTIVE' ? '停用' : '启用'}
                          </Button>
                          <Button
                            variant="ghost"
                            size="sm"
                            disabled={treeBusy || node.childCount > 0 || node.attributionCount > 0}
                            onClick={() => void removeCategory(node)}
                          >
                            删除
                          </Button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        <div className="mt-4 grid gap-3 md:grid-cols-2">
          <div className="rounded-md border border-border p-3">
            <p className="mb-2 text-sm font-medium">新建分类</p>
            <div className="space-y-2">
              <div>
                <label className="mb-1 block text-xs text-muted-foreground" htmlFor="c-parent">
                  父分类（留空 = 建根）
                </label>
                <select
                  id="c-parent"
                  className="h-9 w-full rounded-md border border-border bg-transparent px-2 text-sm"
                  value={newCategory.parentId}
                  onChange={(e) => setNewCategory({ ...newCategory, parentId: e.target.value })}
                >
                  <option value="">建根（必须选七类之一）</option>
                  {nodes
                    .filter((n) => n.depth < (tree?.maxDepth ?? 3))
                    .map((n) => (
                      <option key={n.id} value={n.id}>
                        {n.namePath}
                      </option>
                    ))}
                </select>
              </div>
              {!newCategory.parentId ? (
                <div>
                  <label className="mb-1 block text-xs text-muted-foreground" htmlFor="c-kind">
                    根口径（七类之一）
                  </label>
                  <select
                    id="c-kind"
                    className="h-9 w-full rounded-md border border-border bg-transparent px-2 text-sm"
                    value={newCategory.rootKind}
                    onChange={(e) => setNewCategory({ ...newCategory, rootKind: e.target.value })}
                  >
                    <option value="">请选择</option>
                    {caps?.rootKinds.map((k) => (
                      <option key={k.value} value={k.value}>
                        {k.label}
                      </option>
                    ))}
                  </select>
                </div>
              ) : null}
              <Input
                placeholder="编码（大写字母 / 数字 / 点 / 下划线 / 连字符，租户内唯一且不可改）"
                value={newCategory.categoryCode}
                onChange={(e) => setNewCategory({ ...newCategory, categoryCode: e.target.value })}
                aria-label="分类编码"
              />
              <Input
                placeholder="名称"
                value={newCategory.categoryName}
                onChange={(e) => setNewCategory({ ...newCategory, categoryName: e.target.value })}
                aria-label="分类名称"
              />
              <div className="flex gap-2">
                <Input
                  className="w-24"
                  placeholder="排序位"
                  value={newCategory.sortOrder}
                  onChange={(e) => setNewCategory({ ...newCategory, sortOrder: e.target.value })}
                  aria-label="排序位"
                />
                <Input
                  placeholder="说明（可选）"
                  value={newCategory.description}
                  onChange={(e) =>
                    setNewCategory({ ...newCategory, description: e.target.value })
                  }
                  aria-label="说明"
                />
              </div>
              <Button
                onClick={() => void submitCategory()}
                disabled={
                  treeBusy ||
                  !newCategory.categoryCode.trim() ||
                  !newCategory.categoryName.trim() ||
                  (!newCategory.parentId && !newCategory.rootKind)
                }
              >
                {treeBusy ? '保存中…' : '新建分类'}
              </Button>
            </div>
          </div>
          <div className="rounded-md border border-border p-3 text-sm text-muted-foreground">
            <p className="mb-2 font-medium text-foreground">为什么有些东西不能改</p>
            <ul className="list-disc space-y-1 pl-5">
              <li>
                <strong>编码不可改</strong>：11.5.2 质量分析与 6 采购索赔的历史数据按编码对齐。
              </li>
              <li>
                <strong>根口径不可改</strong>：它是报表的汇总锚点，改了会把全部历史归因
                重新归到另一个口径。要换口径请新建一个根分类再迁归因。
              </li>
              <li>
                <strong>被引用过的分类不能删</strong>：只能停用 —— 停用后不再可被新归因选中，
                而历史归因仍指向它，两个口径都还在。
              </li>
            </ul>
            <p className="mt-2 text-xs">{tree?.orderedBy}</p>
          </div>
        </div>
      </SectionCard>

      {/* ---- 平台原因码映射 ---- */}
      <SectionCard title="平台原因码映射" description={mappings?.ownershipNote ?? ''}>
        <div className="mb-3 flex flex-wrap items-end gap-3">
          <div>
            <label className="mb-1 block text-xs text-muted-foreground" htmlFor="m-platform">
              平台
            </label>
            <select
              id="m-platform"
              className="h-9 rounded-md border border-border bg-transparent px-2 text-sm"
              value={mappingFilter.platformSource}
              onChange={(e) => {
                const next = { ...mappingFilter, platformSource: e.target.value };
                setMappingFilter(next);
                void fetchReasonMappings({
                  platformSource: next.platformSource || undefined,
                  mappingStatus: next.mappingStatus || undefined,
                }).then(setMappings);
              }}
            >
              <option value="">全部</option>
              {caps?.platforms.map((p) => (
                <option key={p.value} value={p.value}>
                  {p.label}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="mb-1 block text-xs text-muted-foreground" htmlFor="m-status">
              状态
            </label>
            <select
              id="m-status"
              className="h-9 rounded-md border border-border bg-transparent px-2 text-sm"
              value={mappingFilter.mappingStatus}
              onChange={(e) => {
                const next = { ...mappingFilter, mappingStatus: e.target.value };
                setMappingFilter(next);
                void fetchReasonMappings({
                  platformSource: next.platformSource || undefined,
                  mappingStatus: next.mappingStatus || undefined,
                }).then(setMappings);
              }}
            >
              <option value="">全部</option>
              <option value="ACTIVE">启用</option>
              <option value="DISABLED">停用</option>
            </select>
          </div>
        </div>

        {mappings && mappings.rows.length === 0 ? (
          <EmptyState
            title="还没有平台原因码映射"
            description="平台原因码与内部口径的对应关系当前由租户自己维护；同步器按同一形状写入后无需改结构。"
          />
        ) : (
          <div className="overflow-x-auto rounded-md border border-border">
            <table className="w-full text-sm">
              <thead className="bg-muted/40 text-left text-xs text-muted-foreground">
                <tr>
                  <th className="px-3 py-2 font-medium">平台</th>
                  <th className="px-3 py-2 font-medium">平台原因码</th>
                  <th className="px-3 py-2 font-medium">内部口径</th>
                  <th className="px-3 py-2 font-medium">来源</th>
                  <th className="px-3 py-2 font-medium">采集时刻</th>
                  <th className="px-3 py-2 font-medium">状态</th>
                  <th className="px-3 py-2 font-medium">操作</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {mappings?.rows.map((m) => (
                  <tr key={m.id}>
                    <td className="px-3 py-2">{m.platformSource}</td>
                    <td className="px-3 py-2 font-mono text-xs">
                      {m.platformReasonCode}
                      {m.platformReasonName ? (
                        <div className="font-sans text-muted-foreground">{m.platformReasonName}</div>
                      ) : null}
                    </td>
                    <td className="px-3 py-2">
                      {m.categoryName}
                      <div className="text-xs text-muted-foreground">{m.rootKindLabel ?? ''}</div>
                    </td>
                    <td className="px-3 py-2 text-xs">{m.mappingSource}</td>
                    <td className="px-3 py-2 text-xs tabular-nums">{m.capturedAt}</td>
                    <td className="px-3 py-2">{m.mappingStatus}</td>
                    <td className="px-3 py-2">
                      <div className="flex gap-2">
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={mappingBusy}
                          onClick={() => void toggleMapping(m.id, m.mappingStatus)}
                        >
                          {m.mappingStatus === 'ACTIVE' ? '停用' : '启用'}
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          disabled={mappingBusy || m.mappingSource !== 'TENANT_MAINTAINED'}
                          title={
                            m.mappingSource !== 'TENANT_MAINTAINED'
                              ? '同步器写入的行只许停用，不许删'
                              : undefined
                          }
                          onClick={() => void removeMapping(m.id)}
                        >
                          删除
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div className="mt-4 grid gap-3 md:grid-cols-2">
          <div className="rounded-md border border-border p-3">
            <p className="mb-2 text-sm font-medium">新建映射</p>
            <div className="space-y-2">
              <select
                className="h-9 w-full rounded-md border border-border bg-transparent px-2 text-sm"
                value={newMapping.categoryId}
                onChange={(e) => setNewMapping({ ...newMapping, categoryId: e.target.value })}
                aria-label="内部原因分类"
              >
                <option value="">选择内部原因分类</option>
                {assignable.map((n) => (
                  <option key={n.id} value={n.id}>
                    {n.namePath}
                  </option>
                ))}
              </select>
              <select
                className="h-9 w-full rounded-md border border-border bg-transparent px-2 text-sm"
                value={newMapping.platformSource}
                onChange={(e) => setNewMapping({ ...newMapping, platformSource: e.target.value })}
                aria-label="平台"
              >
                {caps?.platforms.map((p) => (
                  <option key={p.value} value={p.value}>
                    {p.label}
                  </option>
                ))}
              </select>
              <Input
                placeholder="平台原因码"
                value={newMapping.platformReasonCode}
                onChange={(e) =>
                  setNewMapping({ ...newMapping, platformReasonCode: e.target.value })
                }
                aria-label="平台原因码"
              />
              <Input
                placeholder="平台原因名（可选）"
                value={newMapping.platformReasonName}
                onChange={(e) =>
                  setNewMapping({ ...newMapping, platformReasonName: e.target.value })
                }
                aria-label="平台原因名"
              />
              <Button
                onClick={() => void submitMapping()}
                disabled={mappingBusy || !newMapping.categoryId || !newMapping.platformReasonCode.trim()}
              >
                {mappingBusy ? '保存中…' : '新建映射'}
              </Button>
            </div>
          </div>
          <p className="rounded-md border border-border p-3 text-xs text-muted-foreground">
            {caps?.platformMappingNote}
            {mappingMessage ? (
              <span className="mt-2 block text-amber-600">{mappingMessage}</span>
            ) : null}
          </p>
        </div>
      </SectionCard>

      {/* ---- 报表 ---- */}
      <SectionCard title="原因分类报表（统一口径）" description={report?.semantics ?? ''}>
        <div className="mb-4 grid gap-4 sm:grid-cols-3">
          <StatCard label="已归因售后单" value={report?.attributedCaseCount ?? 0} />
          <StatCard label="原因条目总数" value={report?.attributionItemCount ?? 0} />
          <StatCard label="权重合计" value={report?.totalWeight ?? 0} />
        </div>
        {!report ? null : (
          <>
            <p className="mb-2 text-sm font-medium">按七类根口径（零也出现）</p>
            <div className="overflow-x-auto rounded-md border border-border">
              <table className="w-full text-sm">
                <thead className="bg-muted/40 text-left text-xs text-muted-foreground">
                  <tr>
                    <th className="px-3 py-2 font-medium">口径</th>
                    <th className="px-3 py-2 font-medium">主因单数</th>
                    <th className="px-3 py-2 font-medium">归因单数</th>
                    <th className="px-3 py-2 font-medium">占比</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {report.rootTotals.map((t) => (
                    <tr key={t.rootKind}>
                      <td className="px-3 py-2">{t.rootKindLabel}</td>
                      <td className="px-3 py-2 tabular-nums">{t.primaryCaseCount}</td>
                      <td className="px-3 py-2 tabular-nums">{t.attributionCaseCount}</td>
                      <td className="px-3 py-2 tabular-nums">{shareText(t.caseSharePercent)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="mb-2 mt-4 text-sm font-medium">按分类明细</p>
            <div className="max-h-96 overflow-auto rounded-md border border-border">
              <table className="w-full text-sm">
                <thead className="sticky top-0 bg-muted/40 text-left text-xs text-muted-foreground">
                  <tr>
                    <th className="px-3 py-2 font-medium">分类</th>
                    <th className="px-3 py-2 font-medium">主因</th>
                    <th className="px-3 py-2 font-medium">次因</th>
                    <th className="px-3 py-2 font-medium">归因单数</th>
                    <th className="px-3 py-2 font-medium">占比</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {report.rows.map((r) => (
                    <tr key={r.categoryId} className={r.attributionCaseCount > 0 ? '' : 'opacity-60'}>
                      <td className="px-3 py-2">
                        <div style={{ paddingLeft: `${(r.depth - 1) * 14}px` }}>
                          {r.depth > 1 ? '└ ' : ''}
                          {r.categoryName}
                        </div>
                        <div className="text-xs text-muted-foreground">{r.categoryCode}</div>
                      </td>
                      <td className="px-3 py-2 tabular-nums">{r.primaryCaseCount}</td>
                      <td className="px-3 py-2 tabular-nums">{r.secondaryCaseCount}</td>
                      <td className="px-3 py-2 tabular-nums">{r.attributionCaseCount}</td>
                      <td className="px-3 py-2 tabular-nums">{shareText(r.caseSharePercent)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="mt-2 text-xs text-muted-foreground">{report.downstreamNote}</p>
          </>
        )}
      </SectionCard>
    </div>
  );
}
