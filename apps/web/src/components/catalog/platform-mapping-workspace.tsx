'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import {
  CircleAlert,
  Link2,
  Loader2,
  Plus,
  RefreshCw,
  Save,
  Star,
  Trash2,
} from 'lucide-react';
import { fetchNodes, fetchTrees } from './category-tree-model';
import type { CategoryNode, CategoryTree } from '../../../../../packages/api-contracts/src/index';
import { Editor, Field, control } from './fields';
import { useIdentity } from '../iam/identity-shell';
import {
  PLATFORM_DATA_TYPES,
  PLATFORM_DATA_TYPE_LABELS,
  PLATFORM_MAPPING_SORTS,
  PLATFORM_MAPPING_SORT_LABELS,
  REQUIREMENTS,
  REQUIREMENT_LABELS,
  TRANSFORMS,
  TRANSFORM_LABELS,
  attributeEmptyHint,
  attributeProblem,
  coveragePercent,
  deleteAttributeRule,
  deleteMapping,
  emptyAttributeDraft,
  emptyMappingDraft,
  errorText,
  fetchAttributeOptions,
  fetchMappingAttributes,
  fetchMappingOverview,
  fetchMappingParts,
  fetchMappings,
  fetchPlatformSites,
  mappingEmptyHint,
  mappingProblem,
  platformDataTypeLabel,
  requirementLabel,
  resolveTarget,
  saveAttributeRule,
  saveMapping,
  setAttributeEnabled,
  setMappingDefault,
  setMappingEnabled,
  toAttributeDraft,
  toAttributeInput,
  toMappingDraft,
  toMappingInput,
  transformLabel,
  type AttributeRuleDraft,
  type InternalAttributeOption,
  type MappingDraft,
  type PlatformAttributeMappingRow,
  type PlatformCategoryMappingOverview,
  type PlatformCategoryMappingRow,
  type PlatformCategoryResolveResult,
  type PlatformMappingQuery,
  type PlatformMappingSort,
  type PlatformSiteOption,
} from './platform-mapping-model';

const PAGE_SIZE = 20;

/** 类目候选：左右两栏各自一棵树的启用节点。 */
interface CategoryOption {
  id: string;
  code: string;
  name: string;
  path: string;
  platformCode: string | null;
}

function toCategoryOptions(nodes: readonly CategoryNode[]): CategoryOption[] {
  return nodes.map((n) => ({
    id: n.id,
    code: n.code,
    name: n.name,
    path: n.path || n.name,
    platformCode: n.platformCode,
  }));
}

/**
 * 4.2.3.1 平台类目映射工作区。
 *
 * <p>页面按「配映射 → 配属性规则 → 看必填清单」三段排，对应蓝图点名的三个能力：
 *
 * <ul>
 *   <li><b>映射维护</b> —— 内部分类 × 平台 × 站点 × 平台类目。平台与站点是<b>两栏</b>不是
 *       一个字符串：蓝图写死了「同一个内部类目在不同平台映射到不同平台类目」，
 *       合成一栏的话覆盖率只能靠前缀解析还原，而解析写错的症状是刊登到 DE 站却填了 US 站的类目。
 *   <li><b>属性映射规则</b> —— 平台的「材质」对应内部哪个属性 ID。这是蓝图里的第二个能力。
 *   <li><b>必填属性清单</b> —— 按「内部分类 × 平台 × 站点」解析出来的那张清单，
 *       就是 4.2.1.1 表单生成的输入。
 * </ul>
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li>**多对多的另一侧显式可见**。每行带 `mappedInternalCount`（这个平台类目被几个内部分类映射到），
 *       详情里还列出具体是谁。不可见的话，运营删错了要到刊登时才炸。
 *   <li>**「必填但没绑内部属性」是正常状态**，不是错误。用计数呈现，不用红色报错。
 *   <li>**加载 / 空态 / 错误三态齐全**，且「筛选无结果」与「租户还没有映射」是两种不同空态。
 *   <li>**只读角色看得见但动不了**。写操作按 `catalog.spu.update` 收敛（与后端同一权限词）。
 * </ul>
 */
export function PlatformMappingWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('catalog.spu.update');

  const [overview, setOverview] = useState<PlatformCategoryMappingOverview | null>(null);
  const [rows, setRows] = useState<PlatformCategoryMappingRow[]>([]);
  const [total, setTotal] = useState(0);
  const [platformSites, setPlatformSites] = useState<PlatformSiteOption[]>([]);

  const [platformFilter, setPlatformFilter] = useState('');
  const [siteFilter, setSiteFilter] = useState('');
  const [keyword, setKeyword] = useState('');
  const [enabledFilter, setEnabledFilter] = useState<boolean | null>(null);
  const [unmappedOnly, setUnmappedOnly] = useState(false);
  const [sort, setSort] = useState<PlatformMappingSort>('updatedAt,desc');
  const [page, setPage] = useState(1);

  const [internalOptions, setInternalOptions] = useState<CategoryOption[]>([]);
  const [platformOptions, setPlatformOptions] = useState<CategoryOption[]>([]);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [mappingDraft, setMappingDraft] = useState<MappingDraft | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [attributes, setAttributes] = useState<PlatformAttributeMappingRow[]>([]);
  const [attributeDraft, setAttributeDraft] = useState<AttributeRuleDraft | null>(null);
  const [attributeOptions, setAttributeOptions] = useState<InternalAttributeOption[]>([]);

  const [resolveCategoryId, setResolveCategoryId] = useState('');
  const [resolvePlatform, setResolvePlatform] = useState('');
  const [resolveSite, setResolveSite] = useState('');
  const [resolveResult, setResolveResult] = useState<PlatformCategoryResolveResult | null>(null);
  const [resolveError, setResolveError] = useState<string | null>(null);
  const [resolving, setResolving] = useState(false);

  const hasFilters = platformFilter !== '' || siteFilter !== '' || keyword.trim() !== '' ||
    enabledFilter !== null || unmappedOnly;

  const query: PlatformMappingQuery = useMemo(
    () => ({
      internalCategoryId: null,
      platformCode: platformFilter,
      siteCode: siteFilter,
      keyword,
      enabled: enabledFilter,
      unmappedOnly,
      sort,
      page,
      size: PAGE_SIZE,
    }),
    [platformFilter, siteFilter, keyword, enabledFilter, unmappedOnly, sort, page],
  );

  const siteOptions = useMemo(() => {
    const all = platformSites.find((p) => p.platformCode === platformFilter)?.siteCodes ?? [];
    if (siteFilter && !all.includes(siteFilter)) return [siteFilter, ...all];
    return all;
  }, [platformSites, platformFilter, siteFilter]);

  // ------------------------------------------------------------ 数据加载

  const reload = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [summary, pageData] = await Promise.all([
        fetchMappingOverview(),
        fetchMappings(query),
      ]);
      setOverview(summary);
      setRows(pageData.items);
      setTotal(pageData.total);
    } catch (e) {
      setError(errorText(e, '加载平台类目映射失败'));
    } finally {
      setLoading(false);
    }
  }, [query]);

  useEffect(() => {
    void reload();
  }, [reload]);

  // 平台/站点候选与类目候选各只取一次：它们是下拉的取值域，不随台账筛选变化。
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const [siteOptionsData, trees] = await Promise.all([
          fetchPlatformSites(),
          fetchTrees(),
        ]);
        if (cancelled) return;
        setPlatformSites(siteOptionsData);

        const internalTrees = trees.filter((t) => t.treeKind === 'INTERNAL' && t.enabled);
        const platformTrees = trees.filter((t) => t.treeKind === 'PLATFORM' && t.enabled);
        const [internalNodes, platformNodes] = await Promise.all([
          Promise.all(internalTrees.map((t) => fetchNodes(t.id))),
          Promise.all(platformTrees.map((t) => fetchNodes(t.id))),
        ]);
        if (cancelled) return;
        setInternalOptions(toCategoryOptions(internalNodes.flat()));
        setPlatformOptions(toCategoryOptions(platformNodes.flat()));
      } catch (e) {
        if (!cancelled) setActionError(errorText(e, '加载平台与类目候选失败'));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const loadAttributes = useCallback(async (mappingId: string) => {
    try {
      const [rowsData] = await Promise.all([
        fetchMappingAttributes(mappingId),
        fetchMappingParts(mappingId),
      ]);
      setAttributes(rowsData);
    } catch (e) {
      setActionError(errorText(e, '加载属性规则失败'));
    }
  }, []);

  const selectMapping = useCallback(
    async (id: string) => {
      setSelectedId(id);
      setAttributeDraft(null);
      await loadAttributes(id);
    },
    [loadAttributes],
  );

  const loadAttributeOptions = useCallback(async (q: string) => {
    try {
      setAttributeOptions(await fetchAttributeOptions(q));
    } catch (e) {
      setActionError(errorText(e, '加载内部属性候选失败'));
    }
  }, []);

  // ------------------------------------------------------------ 动作

  const run = useCallback(
    async (work: () => Promise<unknown>) => {
      setBusy(true);
      setActionError(null);
      try {
        await work();
        await reload();
      } catch (e) {
        setActionError(errorText(e));
      } finally {
        setBusy(false);
      }
    },
    [reload],
  );

  const saveMappingDraft = useCallback(() => {
    if (!mappingDraft) return;
    const problem = mappingProblem(mappingDraft);
    if (problem) {
      setActionError(problem);
      return;
    }
    void run(async () => {
      await saveMapping(mappingDraft.id, toMappingInput(mappingDraft));
      setMappingDraft(null);
    });
  }, [mappingDraft, run]);

  const saveAttributeDraft = useCallback(() => {
    if (!selectedId || !attributeDraft) return;
    const problem = attributeProblem(attributeDraft);
    if (problem) {
      setActionError(problem);
      return;
    }
    void run(async () => {
      await saveAttributeRule(selectedId, attributeDraft.id, toAttributeInput(attributeDraft));
      setAttributeDraft(null);
      await loadAttributes(selectedId);
    });
  }, [selectedId, attributeDraft, run, loadAttributes]);

  const doResolve = useCallback(async () => {
    setResolving(true);
    setResolveError(null);
    setResolveResult(null);
    try {
      setResolveResult(
        await resolveTarget(resolveCategoryId, resolvePlatform, resolveSite),
      );
    } catch (e) {
      setResolveError(errorText(e, '解析失败'));
    } finally {
      setResolving(false);
    }
  }, [resolveCategoryId, resolvePlatform, resolveSite]);

  const resetFilters = useCallback(() => {
    setPlatformFilter('');
    setSiteFilter('');
    setKeyword('');
    setEnabledFilter(null);
    setUnmappedOnly(false);
    setPage(1);
  }, []);

  // ------------------------------------------------------------ 渲染

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const coverage = overview
    ? coveragePercent(overview.mappedInternalCategoryCount, overview.internalCategoryCount)
    : 0;

  return (
    <div className="space-y-4">
      <SectionCard
        title="平台类目映射"
        description="内部分类 ↔ 平台类目的多对多映射，带平台与站点两个维度；同一内部类目在不同平台/站点映射到不同的平台类目。"
        extra={
          <Button variant="outline" size="sm" onClick={() => void reload()} disabled={loading}>
            {loading ? <Loader2 className="animate-spin" /> : <RefreshCw />}
            刷新
          </Button>
        }
      >
        {loading && !overview ? (
          <div className="text-muted-foreground flex items-center gap-2 py-6 text-sm">
            <Loader2 className="animate-spin" /> 正在加载映射台账…
          </div>
        ) : error ? (
          <EmptyState
            icon={<CircleAlert />}
            title="加载失败"
            description={error}
            action={
              <Button variant="outline" onClick={() => void reload()}>
                <RefreshCw /> 重试
              </Button>
            }
          />
        ) : (
          <>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <StatCard
                label="映射条数"
                value={overview?.mappingCount ?? 0}
                unit="条"
                icon={<Link2 />}
              />
              <StatCard
                label="内部分类覆盖率"
                value={`${coverage}%`}
                comparisonLabel={`${overview?.mappedInternalCategoryCount ?? 0} / ${overview?.internalCategoryCount ?? 0} 个内部分类`}
                polarity={coverage >= 80 ? 'positive' : 'negative'}
              />
              <StatCard
                label="平台 × 站点"
                value={`${overview?.platformCount ?? 0} / ${overview?.siteCount ?? 0}`}
                comparisonLabel="平台数 / 站点组合数"
              />
              <StatCard
                label="必填但未绑内部属性"
                value={overview?.unmappedRequiredCount ?? 0}
                unit="项"
                comparisonLabel="刊登表单会缺字段"
                polarity={(overview?.unmappedRequiredCount ?? 0) > 0 ? 'negative' : 'positive'}
              />
            </div>

            {actionError && (
              <div className="border-destructive-soft bg-destructive-soft/40 text-destructive mt-4 flex items-start gap-2 rounded-md border px-3 py-2 text-sm">
                <CircleAlert className="mt-0.5 shrink-0" />
                <span>{actionError}</span>
              </div>
            )}
          </>
        )}
      </SectionCard>

      <SectionCard title="映射台账" description="筛选、排序与分页都在服务端执行。">
        <div className="mb-4 grid gap-3 md:grid-cols-2 xl:grid-cols-5">
          <Field label="平台">
            <select
              className={control}
              value={platformFilter}
              onChange={(e) => {
                setPlatformFilter(e.target.value);
                setSiteFilter('');
                setPage(1);
              }}
            >
              <option value="">全部平台</option>
              {platformSites.map((p) => (
                <option key={p.platformCode} value={p.platformCode}>
                  {p.platformCode}（{p.categoryCount} 个类目）
                </option>
              ))}
            </select>
          </Field>
          <Field label="站点">
            <select
              className={control}
              value={siteFilter}
              onChange={(e) => {
                setSiteFilter(e.target.value);
                setPage(1);
              }}
            >
              <option value="">全部站点</option>
              {siteOptions.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </Field>
          <Field label="关键词" hint="同时搜内部类目与平台类目的编码和名称">
            <input
              className={control}
              value={keyword}
              onChange={(e) => {
                setKeyword(e.target.value);
                setPage(1);
              }}
              placeholder="如 T恤 / Shopify 服饰"
            />
          </Field>
          <Field label="启用状态">
            <select
              className={control}
              value={enabledFilter === null ? '' : String(enabledFilter)}
              onChange={(e) => {
                setEnabledFilter(
                  e.target.value === '' ? null : e.target.value === 'true',
                );
                setPage(1);
              }}
            >
              <option value="">不限</option>
              <option value="true">只看启用</option>
              <option value="false">只看停用</option>
            </select>
          </Field>
          <Field label="排序">
            <select
              className={control}
              value={sort}
              onChange={(e) => {
                setSort(e.target.value as PlatformMappingSort);
                setPage(1);
              }}
            >
              {PLATFORM_MAPPING_SORTS.map((s) => (
                <option key={s} value={s}>
                  {PLATFORM_MAPPING_SORT_LABELS[s]}
                </option>
              ))}
            </select>
          </Field>
        </div>

        <label className="text-foreground mb-3 flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={unmappedOnly}
            onChange={(e) => {
              setUnmappedOnly(e.target.checked);
              setPage(1);
            }}
          />
          只看「平台必填但还没绑内部属性」的映射
        </label>

        {loading ? (
          <div className="text-muted-foreground flex items-center gap-2 py-6 text-sm">
            <Loader2 className="animate-spin" /> 正在加载…
          </div>
        ) : rows.length === 0 ? (
          <EmptyState
            icon={<Link2 />}
            title={hasFilters ? '没有符合筛选条件的映射' : '还没有平台类目映射'}
            description={mappingEmptyHint(hasFilters)}
            action={
              hasFilters ? (
                <Button variant="outline" onClick={resetFilters}>
                  清空筛选
                </Button>
              ) : writable ? (
                <Button onClick={() => setMappingDraft(emptyMappingDraft(platformFilter, siteFilter))}>
                  <Plus /> 新建映射
                </Button>
              ) : undefined
            }
          />
        ) : (
          <div className="border-border overflow-x-auto rounded-md border">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground bg-muted/40 text-left">
                <tr>
                  <th className="px-3 py-2 font-medium">内部分类</th>
                  <th className="px-3 py-2 font-medium">平台 / 站点</th>
                  <th className="px-3 py-2 font-medium">平台类目</th>
                  <th className="px-3 py-2 font-medium">属性规则</th>
                  <th className="px-3 py-2 font-medium">状态</th>
                  <th className="px-3 py-2 font-medium">操作</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr
                    key={row.id}
                    className={`border-border border-t ${
                      selectedId === row.id ? 'bg-muted/50' : ''
                    }`}
                  >
                    <td className="px-3 py-2">
                      <div className="text-foreground font-medium">{row.internalCategoryName}</div>
                      <div className="text-muted-foreground text-xs">{row.internalCategoryCode}</div>
                    </td>
                    <td className="px-3 py-2">
                      <Badge tone="info">{row.platformCode}</Badge>{' '}
                      <Badge tone="neutral">{row.siteCode}</Badge>
                    </td>
                    <td className="px-3 py-2">
                      <div className="text-foreground font-medium">{row.platformCategoryName}</div>
                      <div className="text-muted-foreground text-xs">
                        {row.platformCategoryPath || row.platformCategoryCode}
                      </div>
                      {/* 多对多的另一侧：删之前先看清这个平台类目还服务于谁。 */}
                      <div className="text-muted-foreground mt-1 text-xs">
                        被 {row.mappedInternalCount} 个内部分类映射到
                      </div>
                    </td>
                    <td className="px-3 py-2">
                      <div className="text-xs">
                        共 {row.attributeRuleCount} 条 · 必填 {row.requiredCount} 条
                      </div>
                      {row.unmappedRequiredCount > 0 && (
                        <Badge tone="warning" className="mt-1">
                          {row.unmappedRequiredCount} 条必填未绑内部属性
                        </Badge>
                      )}
                    </td>
                    <td className="px-3 py-2">
                      <div className="flex flex-wrap gap-1">
                        <Badge tone={row.enabled ? 'success' : 'neutral'}>
                          {row.enabled ? '启用' : '停用'}
                        </Badge>
                        {row.isDefault && <Badge tone="primary">默认</Badge>}
                      </div>
                    </td>
                    <td className="px-3 py-2">
                      <div className="flex flex-wrap gap-1">
                        <Button variant="ghost" size="sm" onClick={() => void selectMapping(row.id)}>
                          属性规则
                        </Button>
                        {writable && (
                          <>
                            <Button
                              variant="ghost"
                              size="sm"
                              disabled={busy}
                              onClick={() =>
                                void run(async () => {
                                  await setMappingDefault(row.id, row.version);
                                })
                              }
                            >
                              <Star /> 设为默认
                            </Button>
                            <Button
                              variant="ghost"
                              size="sm"
                              disabled={busy}
                              onClick={() => {
                                setMappingDraft(toMappingDraft(row));
                              }}
                            >
                              编辑
                            </Button>
                            <Button
                              variant="ghost"
                              size="sm"
                              disabled={busy}
                              onClick={() =>
                                void run(async () => {
                                  await setMappingEnabled(row.id, !row.enabled, row.version);
                                })
                              }
                            >
                              {row.enabled ? '停用' : '启用'}
                            </Button>
                            <Button
                              variant="ghost"
                              size="sm"
                              disabled={busy}
                              onClick={() => {
                                if (
                                  !window.confirm(
                                    `确定删除「${row.internalCategoryName} → ${row.platformCode}/${row.siteCode}」这条映射？它下面的平台属性规则会一并删除。`,
                                  )
                                )
                                  return;
                                void run(async () => {
                                  await deleteMapping(row.id, row.version);
                                  if (selectedId === row.id) setSelectedId(null);
                                });
                              }}
                            >
                              <Trash2 />
                            </Button>
                          </>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div className="text-muted-foreground mt-3 flex items-center justify-between text-sm">
          <span>
            共 {total} 条，第 {page} / {totalPages} 页
          </span>
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={page <= 1}
              onClick={() => setPage((p) => Math.max(1, p - 1))}
            >
              上一页
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={page >= totalPages}
              onClick={() => setPage((p) => p + 1)}
            >
              下一页
            </Button>
          </div>
        </div>
      </SectionCard>

      <SectionCard
        title="平台类目必填属性清单"
        description="按「内部分类 × 平台 × 站点」解析刊登目标与必填字段——这正是 4.2.1.1 表单生成需要的输入。"
      >
        <div className="grid gap-3 md:grid-cols-4">
          <Field label="内部分类">
            <select
              className={control}
              value={resolveCategoryId}
              onChange={(e) => setResolveCategoryId(e.target.value)}
            >
              <option value="">请选择</option>
              {internalOptions.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}（{c.code}）
                </option>
              ))}
            </select>
          </Field>
          <Field label="平台">
            <select
              className={control}
              value={resolvePlatform}
              onChange={(e) => setResolvePlatform(e.target.value)}
            >
              <option value="">请选择</option>
              {platformSites.map((p) => (
                <option key={p.platformCode} value={p.platformCode}>
                  {p.platformCode}
                </option>
              ))}
            </select>
          </Field>
          <Field label="站点">
            <select
              className={control}
              value={resolveSite}
              onChange={(e) => setResolveSite(e.target.value)}
            >
              <option value="">请选择</option>
              {platformSites
                .find((p) => p.platformCode === resolvePlatform)
                ?.siteCodes.map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
            </select>
          </Field>
          <div className="flex items-end">
            <Button
              onClick={() => void doResolve()}
              disabled={
                resolving || !resolveCategoryId || !resolvePlatform || !resolveSite
              }
            >
              {resolving ? <Loader2 className="animate-spin" /> : <RefreshCw />}
              解析
            </Button>
          </div>
        </div>

        {resolveError && (
          <div className="border-destructive-soft bg-destructive-soft/40 text-destructive mt-4 flex items-start gap-2 rounded-md border px-3 py-2 text-sm">
            <CircleAlert className="mt-0.5 shrink-0" />
            <span>{resolveError}</span>
          </div>
        )}

        {resolveResult && (
          <div className="mt-4 space-y-3">
            <div className="text-sm">
              <span className="text-foreground font-medium">
                {resolveResult.internalCategoryName}
              </span>{' '}
              <Badge tone="info">{resolveResult.platformCode}</Badge>{' '}
              <Badge tone="neutral">{resolveResult.siteCode}</Badge> →{' '}
              <span className="text-foreground font-medium">
                {resolveResult.platformCategoryPath || resolveResult.platformCategoryName}
              </span>
              {resolveResult.unmappedRequiredCount > 0 && (
                <Badge tone="warning" className="ml-2">
                  {resolveResult.unmappedRequiredCount} 条必填还没有内部来源
                </Badge>
              )}
            </div>
            {resolveResult.requiredAttributes.length === 0 ? (
              <EmptyState
                title="这条映射还没有启用中的属性规则"
                description="在上面的台账里选中它，点「属性规则」就能把平台的必填字段一条条配上。"
              />
            ) : (
              <div className="border-border overflow-x-auto rounded-md border">
                <table className="w-full text-sm">
                  <thead className="text-muted-foreground bg-muted/40 text-left">
                    <tr>
                      <th className="px-3 py-2 font-medium">平台属性</th>
                      <th className="px-3 py-2 font-medium">强度</th>
                      <th className="px-3 py-2 font-medium">类型</th>
                      <th className="px-3 py-2 font-medium">内部来源</th>
                      <th className="px-3 py-2 font-medium">值变换</th>
                    </tr>
                  </thead>
                  <tbody>
                    {resolveResult.requiredAttributes.map((a) => (
                      <tr key={a.platformAttributeCode} className="border-border border-t">
                        <td className="px-3 py-2">
                          <div className="text-foreground font-medium">
                            {a.platformAttributeName}
                          </div>
                          <div className="text-muted-foreground text-xs">
                            {a.platformAttributeCode}
                            {a.multiple ? ' · 多值' : ''}
                          </div>
                        </td>
                        <td className="px-3 py-2">
                          <Badge
                            tone={
                              a.requirement === 'REQUIRED'
                                ? 'warning'
                                : a.requirement === 'RECOMMENDED'
                                  ? 'info'
                                  : 'neutral'
                            }
                          >
                            {requirementLabel(a.requirement)}
                          </Badge>
                        </td>
                        <td className="px-3 py-2">
                          {platformDataTypeLabel(a.platformDataType)}
                          {a.allowedValues.length > 0 && (
                            <div className="text-muted-foreground mt-1 text-xs">
                              {a.allowedValues.join('、')}
                            </div>
                          )}
                        </td>
                        <td className="px-3 py-2">
                          {a.mapped ? (
                            <span>
                              {a.internalAttributeName ?? a.internalAttributeCode}
                              <div className="text-muted-foreground text-xs">
                                {a.internalAttributeCode}
                              </div>
                            </span>
                          ) : (
                            // 这是一个要被看见的正常状态，不是错误：
                            // 平台要求这个字段，但租户字典里还没有对应属性。
                            <Badge tone="warning">未绑定内部属性</Badge>
                          )}
                        </td>
                        <td className="px-3 py-2">{transformLabel(a.transform)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}
      </SectionCard>

      {mappingDraft && (
        <Editor
          title={mappingDraft.id ? '编辑映射' : '新建映射'}
          description="同一个内部分类可以映射到多个平台类目；设为默认的那一条就是刊登时的目标。"
          close={() => setMappingDraft(null)}
        >
          <div className="space-y-4">
            <Field label="内部分类（左侧）" hint="来自内部管理分类树 INTERNAL">
              <select
                className={control}
                value={mappingDraft.internalCategoryId}
                onChange={(e) =>
                  setMappingDraft({ ...mappingDraft, internalCategoryId: e.target.value })
                }
              >
                <option value="">请选择</option>
                {internalOptions.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.path}（{c.code}）
                  </option>
                ))}
              </select>
            </Field>
            <div className="grid gap-3 md:grid-cols-2">
              <Field label="平台编码" hint="大写字母数字，如 AMAZON；要与平台分类树声明的平台一致">
                <input
                  className={control}
                  value={mappingDraft.platformCode}
                  onChange={(e) =>
                    setMappingDraft({ ...mappingDraft, platformCode: e.target.value })
                  }
                  placeholder="AMAZON"
                />
              </Field>
              <Field label="站点编码" hint="同一平台的不同站点往往要求不同的类目，如 US / DE">
                <input
                  className={control}
                  value={mappingDraft.siteCode}
                  onChange={(e) =>
                    setMappingDraft({ ...mappingDraft, siteCode: e.target.value })
                  }
                  placeholder="US"
                />
              </Field>
            </div>
            <Field
              label="平台类目（右侧）"
              hint="只列出与上面平台编码一致的平台类目；选错平台的话服务端会拒绝"
            >
              <select
                className={control}
                value={mappingDraft.platformCategoryId}
                onChange={(e) =>
                  setMappingDraft({ ...mappingDraft, platformCategoryId: e.target.value })
                }
              >
                <option value="">请选择</option>
                {platformOptions
                  .filter(
                    (c) =>
                      mappingDraft.platformCode.trim().toUpperCase() === '' ||
                      c.platformCode === mappingDraft.platformCode.trim().toUpperCase(),
                  )
                  .map((c) => (
                    <option key={c.id} value={c.id}>
                      [{c.platformCode}] {c.path}（{c.code}）
                    </option>
                  ))}
              </select>
            </Field>
            <div className="flex flex-wrap gap-4">
              <label className="text-foreground flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={mappingDraft.enabled}
                  onChange={(e) =>
                    setMappingDraft({ ...mappingDraft, enabled: e.target.checked })
                  }
                />
                启用
              </label>
              <label className="text-foreground flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={mappingDraft.isDefault}
                  onChange={(e) =>
                    setMappingDraft({ ...mappingDraft, isDefault: e.target.checked })
                  }
                />
                设为该「内部分类 × 平台 × 站点」的默认目标
              </label>
            </div>
            <Button onClick={saveMappingDraft} disabled={busy}>
              <Save /> 保存
            </Button>
          </div>
        </Editor>
      )}

      {attributeDraft && (
        <Editor
          title={attributeDraft.id ? '编辑平台属性规则' : '新建平台属性规则'}
          description="平台的某个属性（材质、bullet_point_1）对应内部的哪个属性 ID。"
          close={() => setAttributeDraft(null)}
        >
          <div className="space-y-4">
            <div className="grid gap-3 md:grid-cols-2">
              <Field
                label="平台属性编码"
                hint="原样进平台请求体，大小写敏感（bullet_point_1 ≠ BULLET_POINT_1）"
              >
                <input
                  className={control}
                  value={attributeDraft.platformAttributeCode}
                  onChange={(e) =>
                    setAttributeDraft({
                      ...attributeDraft,
                      platformAttributeCode: e.target.value,
                    })
                  }
                  placeholder="material"
                />
              </Field>
              <Field label="平台属性显示名" hint="平台后台的原话，如「材质」">
                <input
                  className={control}
                  value={attributeDraft.platformAttributeName}
                  onChange={(e) =>
                    setAttributeDraft({
                      ...attributeDraft,
                      platformAttributeName: e.target.value,
                    })
                  }
                  placeholder="材质"
                />
              </Field>
            </div>
            <Field
              label="对应内部属性"
              hint="留空表示「平台要求了，但租户属性字典里还没有对应属性」——这个缺口会被记进覆盖统计"
            >
              <select
                className={control}
                value={attributeDraft.internalAttributeId}
                onChange={(e) =>
                  setAttributeDraft({
                    ...attributeDraft,
                    internalAttributeId: e.target.value,
                  })
                }
              >
                <option value="">（未绑定，记为已知缺口）</option>
                {attributeOptions.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.name}（{a.code}）
                  </option>
                ))}
              </select>
            </Field>
            <button
              type="button"
              className="text-primary text-xs underline"
              onClick={() => void loadAttributeOptions('')}
            >
              刷新内部属性候选
            </button>
            <div className="grid gap-3 md:grid-cols-2">
              <Field label="要求强度">
                <select
                  className={control}
                  value={attributeDraft.requirement}
                  onChange={(e) =>
                    setAttributeDraft({ ...attributeDraft, requirement: e.target.value })
                  }
                >
                  {REQUIREMENTS.map((r) => (
                    <option key={r} value={r}>
                      {REQUIREMENT_LABELS[r]}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="平台数据类型" hint="与内部属性的类型常常不同，以平台这一侧为准">
                <select
                  className={control}
                  value={attributeDraft.platformDataType}
                  onChange={(e) =>
                    setAttributeDraft({ ...attributeDraft, platformDataType: e.target.value })
                  }
                >
                  {PLATFORM_DATA_TYPES.map((t) => (
                    <option key={t} value={t}>
                      {PLATFORM_DATA_TYPE_LABELS[t]}
                    </option>
                  ))}
                </select>
              </Field>
            </div>
            <Field
              label="平台候选值"
              hint="一行一个。平台数据类型是 ENUM 时必填，否则刊登时渲染不出下拉、也无法校验取值"
            >
              <textarea
                className={`${control} min-h-24`}
                value={attributeDraft.allowedValuesText}
                onChange={(e) =>
                  setAttributeDraft({
                    ...attributeDraft,
                    allowedValuesText: e.target.value,
                  })
                }
              />
            </Field>
            <div className="grid gap-3 md:grid-cols-3">
              <Field label="值变换" hint="刊登时由 4.2.1.1 执行，这里只存算子名">
                <select
                  className={control}
                  value={attributeDraft.transform}
                  onChange={(e) =>
                    setAttributeDraft({ ...attributeDraft, transform: e.target.value })
                  }
                >
                  {TRANSFORMS.map((t) => (
                    <option key={t} value={t}>
                      {TRANSFORM_LABELS[t]}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="排序位">
                <input
                  type="number"
                  min={0}
                  className={control}
                  value={attributeDraft.position}
                  onChange={(e) =>
                    setAttributeDraft({
                      ...attributeDraft,
                      position: Number(e.target.value),
                    })
                  }
                />
              </Field>
              <div className="flex items-end pb-2">
                <label className="text-foreground flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={attributeDraft.multiple}
                    onChange={(e) =>
                      setAttributeDraft({ ...attributeDraft, multiple: e.target.checked })
                    }
                  />
                  平台接受多值
                </label>
              </div>
            </div>
            <label className="text-foreground flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={attributeDraft.enabled}
                onChange={(e) =>
                  setAttributeDraft({ ...attributeDraft, enabled: e.target.checked })
                }
              />
              启用
            </label>
            <Button onClick={saveAttributeDraft} disabled={busy}>
              <Save /> 保存
            </Button>
          </div>
        </Editor>
      )}

      {selectedId && (
        <SectionCard
          title="平台属性映射规则"
          description="这条映射下，平台的哪些属性对应内部的哪些属性。停用映射不会删掉它们——平台照常要求那些属性。"
          extra={
            writable ? (
              <Button
                size="sm"
                onClick={() => {
                  const draft = emptyAttributeDraft(attributes.length);
                  setAttributeDraft(draft);
                  void loadAttributeOptions('');
                }}
              >
                <Plus /> 新建规则
              </Button>
            ) : undefined
          }
        >
          {actionError && (
            <div className="border-destructive-soft bg-destructive-soft/40 text-destructive mb-3 flex items-start gap-2 rounded-md border px-3 py-2 text-sm">
              <CircleAlert className="mt-0.5 shrink-0" />
              <span>{actionError}</span>
            </div>
          )}
          {attributes.length === 0 ? (
            <EmptyState
              icon={<Link2 />}
              title="这条映射还没有属性规则"
              description={attributeEmptyHint(true)}
              action={
                writable ? (
                  <Button
                    onClick={() => {
                      setAttributeDraft(emptyAttributeDraft(0));
                      void loadAttributeOptions('');
                    }}
                  >
                    <Plus /> 新建规则
                  </Button>
                ) : undefined
              }
            />
          ) : (
            <div className="border-border overflow-x-auto rounded-md border">
              <table className="w-full text-sm">
                <thead className="text-muted-foreground bg-muted/40 text-left">
                  <tr>
                    <th className="px-3 py-2 font-medium">平台属性</th>
                    <th className="px-3 py-2 font-medium">强度</th>
                    <th className="px-3 py-2 font-medium">类型</th>
                    <th className="px-3 py-2 font-medium">内部属性</th>
                    <th className="px-3 py-2 font-medium">状态</th>
                    {writable && <th className="px-3 py-2 font-medium">操作</th>}
                  </tr>
                </thead>
                <tbody>
                  {attributes.map((a) => (
                    <tr key={a.id} className="border-border border-t">
                      <td className="px-3 py-2">
                        <div className="text-foreground font-medium">{a.platformAttributeName}</div>
                        <div className="text-muted-foreground text-xs">
                          {a.platformAttributeCode} · 排序位 {a.position}
                        </div>
                      </td>
                      <td className="px-3 py-2">
                        <Badge
                          tone={
                            a.requirement === 'REQUIRED'
                              ? 'warning'
                              : a.requirement === 'RECOMMENDED'
                                ? 'info'
                                : 'neutral'
                          }
                        >
                          {requirementLabel(a.requirement)}
                        </Badge>
                      </td>
                      <td className="px-3 py-2">
                        {platformDataTypeLabel(a.platformDataType)}
                        <div className="text-muted-foreground text-xs">
                          {transformLabel(a.transform)}
                        </div>
                      </td>
                      <td className="px-3 py-2">
                        {a.internalAttributeId ? (
                          <span>
                            {a.internalAttributeName ?? a.internalAttributeCode}
                            <div className="text-muted-foreground text-xs">
                              {a.internalAttributeCode}
                            </div>
                          </span>
                        ) : (
                          <Badge tone="warning">未绑定内部属性</Badge>
                        )}
                      </td>
                      <td className="px-3 py-2">
                        <Badge tone={a.enabled ? 'success' : 'neutral'}>
                          {a.enabled ? '启用' : '停用'}
                        </Badge>
                      </td>
                      {writable && (
                        <td className="px-3 py-2">
                          <div className="flex flex-wrap gap-1">
                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={() => setAttributeDraft(toAttributeDraft(a))}
                            >
                              编辑
                            </Button>
                            <Button
                              variant="ghost"
                              size="sm"
                              disabled={busy}
                              onClick={() =>
                                void run(async () => {
                                  await setAttributeEnabled(
                                    selectedId,
                                    a.id,
                                    !a.enabled,
                                    a.version,
                                  );
                                  await loadAttributes(selectedId);
                                })
                              }
                            >
                              {a.enabled ? '停用' : '启用'}
                            </Button>
                            <Button
                              variant="ghost"
                              size="sm"
                              disabled={busy}
                              onClick={() => {
                                if (
                                  !window.confirm(
                                    `确定删除平台属性规则「${a.platformAttributeName}」？`,
                                  )
                                )
                                  return;
                                void run(async () => {
                                  await deleteAttributeRule(selectedId, a.id);
                                  await loadAttributes(selectedId);
                                });
                              }}
                            >
                              <Trash2 />
                            </Button>
                          </div>
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </SectionCard>
      )}
    </div>
  );
}
