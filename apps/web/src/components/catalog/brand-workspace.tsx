'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import {
  ArrowLeft,
  BadgeCheck,
  FileCheck2,
  Plus,
  RefreshCw,
  Save,
  ShieldAlert,
  Trash2,
  TriangleAlert,
} from 'lucide-react';
import type {
  BrandAuthorization,
  BrandAuthorizationInput,
  BrandDetail,
  BrandInput,
  BrandOverview,
  BrandRow,
  CategoryNode,
  CategoryTree,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  AUTHORIZATION_STATUSES,
  PUBLISHABLE_FILTERS,
  blockAction,
  blockLabel,
  brandInputProblem,
  createAuthorization,
  createBrand,
  credentialProblem,
  credentialState,
  deleteBrand,
  displayName,
  emptyBrandInput,
  emptyCredentialInput,
  emptyHint,
  errorText,
  fetchAuthorizations,
  fetchBrands,
  fetchCandidates,
  fetchDetail,
  fetchNodes,
  fetchOverview,
  fetchTrees,
  linkableCategories,
  nameProblem,
  revokeAuthorization,
  saveAuthorization,
  saveBrand,
  setBrandEnabled,
  statusHint,
  statusLabel,
  toBrandInput,
  toCredentialInput,
  type PublishableFilter,
} from './brand-model';
import { useIdentity } from '../iam/identity-shell';

const PAGE_SIZE = 20;

/**
 * 3.3.2.1 品牌管理工作区。
 *
 * <p>页面按「维护字典 → 看刊登可行性」的顺序排。每个区块回答一个独立的问题：
 *
 * <ul>
 *   <li><b>概览</b> —— 规模，以及最要紧的那个数：现在有多少品牌不允许刊登。
 *   <li><b>品牌列表</b> —— 分页 + 搜索 + 筛选，每行直接带刊登判定与不可刊登的原因。
 *       列表的用途就是「找出不能刊登的品牌」，所以要逐个点开才知道的字段在这里没用。
 *   <li><b>品牌编辑</b> —— 名称 / Logo / 授权状态 / 多语言名 / 关联类目。
 *   <li><b>授权凭证</b> —— 需授权品牌的凭证与有效期，含撤销与剩余天数。
 *   <li><b>刊登与合规候选</b> —— 本模块交给 04 刊登与 16 侵权检测的那份引用候选，
 *       直接消费它们会调用的同一个端点，不是另做一套演示。
 * </ul>
 *
 * <p>刊登判定一律来自后端（`BrandRules.publishBlock` 是唯一实现），前端只把原因码
 * 翻成要显示的话。理由见 `brand-model.ts` 头部：复制一份判定到浏览器里，
 * 症状是「页面说能刊登、接口说不能」，只有真要发的那天才会暴露。
 */
export function BrandWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('catalog.spu.update');

  const [overview, setOverview] = useState<BrandOverview | null>(null);
  const [rows, setRows] = useState<BrandRow[]>([]);
  const [total, setTotal] = useState(0);
  const [enabledCount, setEnabledCount] = useState(0);
  const [blockedCount, setBlockedCount] = useState(0);
  const [note, setNote] = useState('');
  const [trees, setTrees] = useState<CategoryTree[]>([]);
  const [nodes, setNodes] = useState<CategoryNode[]>([]);
  const [locale, setLocale] = useState('zh-cn');
  const [ready, setReady] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');

  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [enabled, setEnabled] = useState<boolean | null>(null);
  const [publishable, setPublishable] = useState<PublishableFilter>('');
  const [categoryId, setCategoryId] = useState<string>('');
  const [index, setIndex] = useState(0);

  const [editing, setEditing] = useState<BrandInput | 'new' | null>(null);
  const [detail, setDetail] = useState<BrandDetail | null>(null);
  const [credential, setCredential] = useState<BrandAuthorizationInput | 'new' | null>(null);
  const [candidates, setCandidates] = useState<
    {
      brandId: string;
      brandName: string;
      publishable: boolean;
      blockLabel: string | null;
      blockMessage: string | null;
    }[]
  >([]);

  const categories = useMemo(() => linkableCategories(nodes), [nodes]);
  const filtered =
    Boolean(keyword.trim()) ||
    status !== '' ||
    enabled !== null ||
    publishable !== '' ||
    categoryId !== '';

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [overviewResult, pageResult, treesResult] = await Promise.all([
        fetchOverview(),
        fetchBrands({
          keyword,
          authorizationStatus: status,
          enabled,
          publishable: publishable === '' ? null : publishable === 'true',
          categoryId: categoryId || null,
          page: index,
          size: PAGE_SIZE,
        }),
        fetchTrees(),
      ]);
      setOverview(overviewResult);
      setRows(pageResult.items ?? []);
      setTotal(pageResult.total);
      setEnabledCount(pageResult.enabledCount);
      setBlockedCount(pageResult.blockedCount);
      setNote(pageResult.note);
      setTrees(treesResult);
      if (!ready) {
        // 一次取回内部树全部节点作为类目下拉；树多时仍是可控的一次往返。
        const lists = await Promise.all(
          treesResult
            .filter((tree) => tree.treeKind === 'INTERNAL')
            .map((tree) => fetchNodes(tree.id)),
        );
        setNodes(lists.flat());
        setReady(true);
      }
    } catch (e) {
      setError(errorText(e, '品牌数据加载失败'));
    } finally {
      setLoading(false);
    }
    // ready 只在首次加载后才有意义，留在依赖里会让每次刷新都重取整棵树。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [keyword, status, enabled, publishable, categoryId, index]);

  useEffect(() => {
    void load();
  }, [load]);

  async function openDetail(brandId: string) {
    setBusy(true);
    setActionError(null);
    try {
      setDetail(await fetchDetail(brandId));
      setEditing(null);
      setCredential(null);
    } catch (e) {
      setActionError(errorText(e, '品牌详情加载失败'));
    } finally {
      setBusy(false);
    }
  }

  async function submitBrand() {
    if (editing === 'new' || editing === null) return;
    const problem = brandInputProblem(editing);
    if (problem) {
      setActionError(problem);
      return;
    }
    setBusy(true);
    setActionError(null);
    try {
      const saved =
        detail && detail.id && (editing.version === 0 || detail.code === editing.code)
          ? await saveBrand(detail.id, editing)
          : await createBrand(editing);
      setNotice(detail ? '品牌已保存' : '品牌已创建');
      setEditing(null);
      setDetail(null);
      await load();
      void saved;
    } catch (e) {
      setActionError(errorText(e, '品牌保存失败'));
    } finally {
      setBusy(false);
    }
  }

  async function toggleEnabled(row: BrandRow) {
    setBusy(true);
    setActionError(null);
    try {
      await setBrandEnabled(row.id, !row.enabled);
      setNotice(row.enabled ? '品牌已停用：刊登判定立刻变成「品牌停用」' : '品牌已启用');
      await load();
    } catch (e) {
      setActionError(errorText(e, '启用停用失败'));
    } finally {
      setBusy(false);
    }
  }

  async function removeBrand() {
    if (!detail) return;
    setBusy(true);
    setActionError(null);
    try {
      await deleteBrand(detail.id, detail.version);
      setNotice('品牌已删除');
      setDetail(null);
      setEditing(null);
      await load();
    } catch (e) {
      setActionError(errorText(e, '品牌删除失败'));
    } finally {
      setBusy(false);
    }
  }

  async function submitCredential() {
    if (credential === 'new' || credential === null || !detail) return;
    const problem = credentialProblem(credential);
    if (problem) {
      setActionError(problem);
      return;
    }
    setBusy(true);
    setActionError(null);
    try {
      await createAuthorization(detail.id, credential);
      setNotice('凭证已登记');
      setCredential(null);
      await openDetail(detail.id);
      await load();
    } catch (e) {
      setActionError(errorText(e, '凭证登记失败'));
    } finally {
      setBusy(false);
    }
  }

  async function saveExistingCredential(next: BrandAuthorization) {
    if (!detail) return;
    setBusy(true);
    setActionError(null);
    try {
      await saveAuthorization(detail.id, next.id, toCredentialInput(next));
      setNotice('凭证已保存');
      await openDetail(detail.id);
      await load();
    } catch (e) {
      setActionError(errorText(e, '凭证保存失败'));
    } finally {
      setBusy(false);
    }
  }

  async function revoke(id: string, enabledNow: boolean) {
    if (!detail) return;
    setBusy(true);
    setActionError(null);
    try {
      await revokeAuthorization(detail.id, id, enabledNow);
      // 撤销是判定输入的变化，判定结果必须当场跟着变，不能等用户刷新。
      setNotice(enabledNow ? '凭证已撤销：行仍保留，供侵权检测回溯' : '凭证已恢复');
      await openDetail(detail.id);
      await load();
    } catch (e) {
      setActionError(errorText(e, '凭证撤销失败'));
    } finally {
      setBusy(false);
    }
  }

  async function loadCandidates(includeBlocked: boolean) {
    setBusy(true);
    setActionError(null);
    try {
      const result = await fetchCandidates(categoryId || null, keyword, includeBlocked);
      setCandidates(
        result.map((item) => ({
          brandId: item.brandId,
          brandName: item.brandName,
          publishable: item.publishable,
          blockLabel: item.blockLabel,
          blockMessage: item.blockMessage,
        })),
      );
    } catch (e) {
      setActionError(errorText(e, '引用候选加载失败'));
    } finally {
      setBusy(false);
    }
  }

  const lastPage = Math.max(0, Math.ceil(total / PAGE_SIZE) - 1);

  return (
    <div className="space-y-6">
      <header className="space-y-2">
        <Link
          href="/products/categories"
          className="text-muted-foreground hover:text-foreground inline-flex items-center gap-1 text-sm"
        >
          <ArrowLeft className="h-4 w-4" />
          返回分类中心
        </Link>
        <h1 className="text-h-20 font-semibold tracking-tight">品牌管理</h1>
        <p className="text-copy-13 text-muted-foreground">
          品牌字典：名称、多语言名、Logo、授权状态与关联类目。授权状态直接影响能否刊登——
          标记为需授权却没有有效凭证的品牌，刊登侧会被判定端点挡住。
        </p>
      </header>

      <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="品牌总数"
          value={overview?.brandCount ?? 0}
          icon={<BadgeCheck className="h-4 w-4" />}
        />
        <StatCard label="启用中" value={overview?.enabledCount ?? 0} />
        <StatCard
          label="不可刊登"
          value={overview?.blockedListingCount ?? 0}
          icon={<ShieldAlert className="h-4 w-4" />}
          polarity={overview && overview.blockedListingCount > 0 ? 'negative' : 'positive'}
          delta={blockedCount}
          comparisonLabel="列表口径的阻止数"
        />
        <StatCard
          label="需授权且无凭证"
          value={overview?.licensedWithoutCredentialCount ?? 0}
          icon={<TriangleAlert className="h-4 w-4" />}
        />
      </section>

      {error && (
        <SectionCard title="加载失败">
          <div className="text-destructive space-y-2 text-sm">
            <p>{error}</p>
            <Button variant="outline" size="sm" onClick={() => void load()}>
              <RefreshCw className="h-4 w-4" />
              重试
            </Button>
          </div>
        </SectionCard>
      )}

      <SectionCard
        title="品牌列表"
        description="每行直接带刊登判定：不可刊登的品牌连原因一起显示，不用点开详情才知道。"
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <Field label="展示语言">
              <select
                className={control}
                value={locale}
                onChange={(e) => setLocale(e.target.value)}
              >
                <option value="zh-cn">中文</option>
                <option value="en-us">English</option>
                <option value="ja-jp">日本語</option>
              </select>
            </Field>
            {writable && (
              <Button
                size="sm"
                onClick={() => {
                  setDetail(null);
                  setEditing(emptyBrandInput());
                }}
              >
                <Plus className="h-4 w-4" />
                新建品牌
              </Button>
            )}
          </div>
        }
      >
        <div className="space-y-3">
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
            <Field label="关键词">
              <input
                className={control}
                value={keyword}
                placeholder="编码 / 名称 / 多语言名"
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setIndex(0);
                }}
              />
            </Field>
            <Field label="授权状态">
              <select
                className={control}
                value={status}
                onChange={(e) => {
                  setStatus(e.target.value);
                  setIndex(0);
                }}
              >
                <option value="">全部</option>
                {AUTHORIZATION_STATUSES.map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="启用状态">
              <select
                className={control}
                value={enabled === null ? '' : String(enabled)}
                onChange={(e) => {
                  const value = e.target.value;
                  setEnabled(value === '' ? null : value === 'true');
                  setIndex(0);
                }}
              >
                <option value="">全部</option>
                <option value="true">启用</option>
                <option value="false">停用</option>
              </select>
            </Field>
            <Field label="刊登可行性">
              <select
                className={control}
                value={publishable}
                onChange={(e) => {
                  setPublishable(e.target.value as PublishableFilter);
                  setIndex(0);
                }}
              >
                {PUBLISHABLE_FILTERS.map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="关联类目">
              <select
                className={control}
                value={categoryId}
                onChange={(e) => {
                  setCategoryId(e.target.value);
                  setIndex(0);
                }}
              >
                <option value="">全部</option>
                {categories.map((node) => (
                  <option key={node.id} value={node.id}>
                    {node.path}
                  </option>
                ))}
              </select>
            </Field>
          </div>

          {loading ? (
            <p className="text-muted-foreground text-sm">正在读取品牌字典…</p>
          ) : rows.length === 0 ? (
            <EmptyState title="没有品牌" description={emptyHint(filtered)} />
          ) : (
            <div className="space-y-2">
              <table className="w-full text-sm">
                <thead className="text-muted-foreground text-left">
                  <tr>
                    <th className="py-2">品牌</th>
                    <th className="py-2">授权状态</th>
                    <th className="py-2">刊登</th>
                    <th className="py-2">凭证</th>
                    <th className="py-2">关联类目</th>
                    <th className="py-2" />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.id} className="border-border border-t align-top">
                      <td className="py-2">
                        <div className="font-medium">{displayName(row, locale)}</div>
                        <div className="text-muted-foreground text-xs">{row.code}</div>
                        {row.logoUri && (
                          <div className="text-muted-foreground text-xs">Logo：{row.logoUri}</div>
                        )}
                      </td>
                      <td className="py-2">
                        <Badge tone="neutral">{statusLabel(row.authorizationStatus)}</Badge>
                        {!row.enabled && <Badge tone="neutral">已停用</Badge>}
                      </td>
                      <td className="py-2">
                        {row.publishable ? (
                          <Badge>可刊登</Badge>
                        ) : (
                          <div className="space-y-1">
                            <Badge tone="danger">{row.publishBlockLabel}</Badge>
                            <div className="text-muted-foreground text-xs">
                              {blockAction(row.publishBlockCode)}
                            </div>
                          </div>
                        )}
                      </td>
                      <td className="py-2">
                        {row.authorizationCount === 0 ? (
                          <span className="text-muted-foreground">无凭证</span>
                        ) : (
                          <span>
                            {row.validAuthorizationCount} / {row.authorizationCount} 有效
                          </span>
                        )}
                      </td>
                      <td className="py-2 text-xs">
                        {row.categoryCount === 0 ? (
                          <span className="text-muted-foreground">未关联</span>
                        ) : (
                          row.categoryNames
                        )}
                      </td>
                      <td className="py-2">
                        <div className="flex flex-wrap justify-end gap-2">
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => void openDetail(row.id)}
                          >
                            查看
                          </Button>
                          {writable && (
                            <Button
                              size="sm"
                              variant="outline"
                              disabled={busy}
                              onClick={() => void toggleEnabled(row)}
                            >
                              {row.enabled ? '停用' : '启用'}
                            </Button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <div className="text-muted-foreground flex items-center justify-between text-xs">
                <span>
                  共 {total} 条 · 启用 {enabledCount} 条 · 不可刊登 {blockedCount} 条
                  {note ? ` · ${note}` : ''}
                </span>
                <div className="flex items-center gap-2">
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={index <= 0}
                    onClick={() => setIndex(Math.max(0, index - 1))}
                  >
                    上一页
                  </Button>
                  <span>
                    {index + 1} / {lastPage + 1}
                  </span>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={index >= lastPage}
                    onClick={() => setIndex(index + 1)}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            </div>
          )}
        </div>
      </SectionCard>

      {detail && (
        <SectionCard
          title={`品牌详情：${detail.name}`}
          description={`编码 ${detail.code} · 刊登判定基准日 ${detail.asOf}（UTC）`}
          extra={
            writable &&
            !editing && (
              <div className="flex gap-2">
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => setEditing(toBrandInput(detail))}
                >
                  编辑
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={busy}
                  onClick={() =>
                    void setBrandEnabled(detail.id, !detail.enabled).then(() =>
                      openDetail(detail.id),
                    )
                  }
                >
                  {detail.enabled ? '停用' : '启用'}
                </Button>
                <Button
                  size="sm"
                  variant="destructive"
                  disabled={busy}
                  onClick={() => void removeBrand()}
                >
                  <Trash2 className="h-4 w-4" />
                  删除
                </Button>
              </div>
            )
          }
        >
          <div className="space-y-4">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone="neutral">{statusLabel(detail.authorizationStatus)}</Badge>
              <Badge tone={detail.enabled ? 'neutral' : 'danger'}>
                {detail.enabled ? '启用中' : '已停用'}
              </Badge>
              {detail.publishable ? (
                <Badge>可刊登</Badge>
              ) : (
                <Badge tone="danger">{detail.publishBlockMessage}</Badge>
              )}
              {detail.publishBlockCode && (
                <span className="text-muted-foreground text-xs">
                  下一步：{blockAction(detail.publishBlockCode)}
                </span>
              )}
            </div>

            <dl className="grid gap-2 text-sm sm:grid-cols-2">
              <div>
                <dt className="text-muted-foreground">Logo 引用</dt>
                <dd>{detail.logoUri ?? '未设置'}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">授权状态说明</dt>
                <dd>{statusHint(detail.authorizationStatus)}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">多语言名</dt>
                <dd>
                  {Object.keys(detail.names ?? {}).length === 0
                    ? '未配置'
                    : Object.entries(detail.names)
                        .map(([localeKey, value]) => `${localeKey}：${value}`)
                        .join('；')}
                </dd>
              </div>
              <div>
                <dt className="text-muted-foreground">有效凭证</dt>
                <dd>
                  {detail.validAuthorizationCount} / {detail.authorizations.length}
                </dd>
              </div>
            </dl>

            <div className="space-y-2">
              <h3 className="text-sm font-medium">关联类目</h3>
              {detail.categories.length === 0 ? (
                <p className="text-muted-foreground text-sm">
                  未关联类目。关联指向 3.3.1.1 分类树的主键，分类重命名后这里自动跟着变。
                </p>
              ) : (
                <ul className="space-y-1 text-sm">
                  {detail.categories.map((link) => (
                    <li key={link.categoryId}>
                      <Link href="/products/categories" className="text-primary underline">
                        {link.categoryPath}
                      </Link>
                      <span className="text-muted-foreground text-xs"> · {link.categoryCode}</span>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <h3 className="text-sm font-medium">授权凭证</h3>
                {writable && !credential && (
                  <Button size="sm" variant="outline" onClick={() => setCredential('new')}>
                    <Plus className="h-4 w-4" />
                    登记凭证
                  </Button>
                )}
              </div>
              {credential === 'new' && (
                <CredentialForm
                  input={emptyCredentialInput()}
                  onChange={setCredential}
                  onCancel={() => setCredential(null)}
                  onSubmit={() => void submitCredential()}
                  busy={busy}
                />
              )}
              {detail.authorizations.length === 0 ? (
                <p className="text-muted-foreground text-sm">
                  没有凭证。需授权品牌在补上有效凭证之前不允许刊登（撤销不等于删除，
                  凭证行会一直保留供侵权检测回溯）。
                </p>
              ) : (
                <div className="space-y-2">
                  {detail.authorizations.map((each) => {
                    const state = credentialState(each);
                    return (
                      <div key={each.id} className="border-border space-y-1 border-t pt-2 text-sm">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="font-medium">{each.certificateNo}</span>
                          <Badge tone={state.tone === 'ok' ? 'success' : 'danger'}>
                            {state.label}
                          </Badge>
                        </div>
                        <div className="text-muted-foreground text-xs">
                          授权方 {each.grantor} · 有效期 {each.validFrom} ~{' '}
                          {each.validTo ?? '长期有效'}
                          {each.evidenceUri ? ` · 材料 ${each.evidenceUri}` : ''}
                        </div>
                        {each.note && (
                          <div className="text-muted-foreground text-xs">{each.note}</div>
                        )}
                        {writable && (
                          <div className="flex gap-2">
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={() => setCredential(toCredentialInput(each))}
                            >
                              编辑
                            </Button>
                            <Button
                              size="sm"
                              variant="outline"
                              disabled={busy}
                              onClick={() => void revoke(each.id, !each.enabled)}
                            >
                              {each.enabled ? '撤销' : '恢复'}
                            </Button>
                          </div>
                        )}
                      </div>
                    );
                  })}
                  {credential !== 'new' && credential !== null && (
                    <CredentialForm
                      input={credential as BrandAuthorizationInput}
                      onChange={setCredential}
                      onCancel={() => setCredential(null)}
                      onSubmit={() => {
                        const target = detail.authorizations.find(
                          (each) =>
                            each.id === (credential as BrandAuthorizationInput).certificateNo,
                        );
                        void (target ? saveExistingCredential(target) : submitCredential());
                      }}
                      busy={busy}
                    />
                  )}
                </div>
              )}
            </div>
          </div>
        </SectionCard>
      )}

      {editing !== null && editing !== 'new' && (
        <SectionCard
          title={detail ? `编辑品牌：${detail.name}` : '新建品牌'}
          description="关联类目是整体替换语义：提交什么就等于什么，不是增量追加。"
        >
          <div className="space-y-4">
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="品牌编码" hint="建后不可改：刊登与侵权检测按它对齐。">
                <input
                  className={control}
                  value={editing.code}
                  disabled={Boolean(detail)}
                  onChange={(e) => setEditing({ ...editing, code: e.target.value })}
                />
              </Field>
              <Field label="品牌名称">
                <input
                  className={control}
                  value={editing.name}
                  onChange={(e) => setEditing({ ...editing, name: e.target.value })}
                />
              </Field>
              <Field label="Logo 引用" hint="对象存储 key 或完整 URL，本模块只存引用。">
                <input
                  className={control}
                  value={editing.logoUri ?? ''}
                  onChange={(e) => setEditing({ ...editing, logoUri: e.target.value || null })}
                />
              </Field>
              <Field label="授权状态" hint={statusHint(editing.authorizationStatus)}>
                <select
                  className={control}
                  value={editing.authorizationStatus}
                  onChange={(e) => setEditing({ ...editing, authorizationStatus: e.target.value })}
                >
                  {AUTHORIZATION_STATUSES.map((item) => (
                    <option key={item.value} value={item.value}>
                      {item.label}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="启用">
                <select
                  className={control}
                  value={editing.enabled ? 'true' : 'false'}
                  onChange={(e) => setEditing({ ...editing, enabled: e.target.value === 'true' })}
                >
                  <option value="true">启用</option>
                  <option value="false">停用</option>
                </select>
              </Field>
            </div>

            <div className="space-y-2">
              <h3 className="text-sm font-medium">多语言名</h3>
              <p className="text-muted-foreground text-xs">
                基础名在上面的「品牌名称」，这里配的是补充。同一语言只能有一条。
              </p>
              {(editing.names ?? []).map((entry, index) =>
                entry === null ? null : (
                  <div key={index} className="flex gap-2">
                    <input
                      className={control}
                      value={entry.locale}
                      placeholder="zh-CN"
                      onChange={(e) => {
                        const names = [...(editing.names ?? [])];
                        names[index] = { ...entry, locale: e.target.value };
                        setEditing({ ...editing, names });
                      }}
                    />
                    <input
                      className={control}
                      value={entry.name}
                      placeholder="该语言下的品牌名"
                      onChange={(e) => {
                        const names = [...(editing.names ?? [])];
                        names[index] = { ...entry, name: e.target.value };
                        setEditing({ ...editing, names });
                      }}
                    />
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() =>
                        setEditing({
                          ...editing,
                          names: (editing.names ?? []).filter((_, i) => i !== index),
                        })
                      }
                    >
                      移除
                    </Button>
                  </div>
                ),
              )}
              <Button
                size="sm"
                variant="outline"
                onClick={() =>
                  setEditing({
                    ...editing,
                    names: [...(editing.names ?? []), { locale: '', name: '' }],
                  })
                }
              >
                <Plus className="h-4 w-4" />
                添加语言
              </Button>
            </div>

            <div className="space-y-2">
              <h3 className="text-sm font-medium">关联类目</h3>
              <p className="text-muted-foreground text-xs">
                指向 3.3.1.1 分类树的类目主键；停用的类目不能新增关联（已有关联会保留）。
              </p>
              <select
                multiple
                className={`${control} h-32`}
                value={(editing.categories ?? [])
                  .map((link) => link?.categoryId)
                  .filter((value): value is string => Boolean(value))}
                onChange={(e) =>
                  setEditing({
                    ...editing,
                    categories: Array.from(e.target.selectedOptions).map((option) => ({
                      categoryId: option.value,
                      position: 0,
                    })),
                  })
                }
              >
                {categories.map((node) => (
                  <option key={node.id} value={node.id}>
                    {node.path}
                  </option>
                ))}
              </select>
            </div>

            <div className="flex gap-2">
              <Button size="sm" disabled={busy} onClick={() => void submitBrand()}>
                <Save className="h-4 w-4" />
                保存
              </Button>
              <Button size="sm" variant="outline" onClick={() => setEditing(null)}>
                取消
              </Button>
            </div>
          </div>
        </SectionCard>
      )}

      <SectionCard
        title="刊登与合规的品牌引用候选"
        description="这是本模块交给 04 刊登与 16 侵权检测的输出：直接消费它们会调用的同一个端点。"
      >
        <div className="space-y-3">
          <div className="flex flex-wrap gap-2">
            <Button
              size="sm"
              variant="outline"
              disabled={busy}
              onClick={() => void loadCandidates(false)}
            >
              <FileCheck2 className="h-4 w-4" />
              只看可刊登（刊登表单要的就是这批）
            </Button>
            <Button
              size="sm"
              variant="outline"
              disabled={busy}
              onClick={() => void loadCandidates(true)}
            >
              <TriangleAlert className="h-4 w-4" />
              含不可刊登（带原因码）
            </Button>
          </div>
          {candidates.length === 0 ? (
            <p className="text-muted-foreground text-sm">
              点上面的按钮拉取候选。默认只给启用且可刊登的品牌；「含不可刊登」会带上原因码，
              用于「为什么这个品牌选不了」要显示出来而不是让它凭空消失。
            </p>
          ) : (
            <ul className="space-y-1 text-sm">
              {candidates.map((item) => (
                <li key={item.brandId} className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">{item.brandName}</span>
                  {item.publishable ? (
                    <Badge>可刊登</Badge>
                  ) : (
                    <Badge tone="danger">{item.blockLabel}</Badge>
                  )}
                  {!item.publishable && item.blockMessage && (
                    <span className="text-muted-foreground text-xs">{item.blockMessage}</span>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      </SectionCard>

      {actionError && (
        <p className="text-destructive text-sm" role="alert">
          {actionError}
        </p>
      )}
      {notice && <p className="text-muted-foreground text-sm">{notice}</p>}
    </div>
  );
}

function CredentialForm({
  input,
  onChange,
  onCancel,
  onSubmit,
  busy,
}: {
  input: BrandAuthorizationInput;
  onChange: (next: BrandAuthorizationInput) => void;
  onCancel: () => void;
  onSubmit: () => void;
  busy: boolean;
}) {
  const problem = credentialProblem(input);
  return (
    <div className="border-border space-y-3 border-t pt-3">
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="凭证编号">
          <input
            className={control}
            value={input.certificateNo}
            onChange={(e) => onChange({ ...input, certificateNo: e.target.value })}
          />
        </Field>
        <Field label="授权方">
          <input
            className={control}
            value={input.grantor}
            onChange={(e) => onChange({ ...input, grantor: e.target.value })}
          />
        </Field>
        <Field label="生效日期" hint="格式 yyyy-MM-dd。">
          <input
            className={control}
            value={input.validFrom}
            onChange={(e) => onChange({ ...input, validFrom: e.target.value })}
          />
        </Field>
        <Field label="失效日期" hint="留空 = 长期有效，不是漏填。">
          <input
            className={control}
            value={input.validTo ?? ''}
            onChange={(e) => onChange({ ...input, validTo: e.target.value || null })}
          />
        </Field>
        <Field label="凭证材料引用">
          <input
            className={control}
            value={input.evidenceUri ?? ''}
            onChange={(e) => onChange({ ...input, evidenceUri: e.target.value || null })}
          />
        </Field>
        <Field label="备注">
          <input
            className={control}
            value={input.note ?? ''}
            onChange={(e) => onChange({ ...input, note: e.target.value || null })}
          />
        </Field>
      </div>
      {problem && <p className="text-destructive text-xs">{problem}</p>}
      <div className="flex gap-2">
        <Button size="sm" disabled={busy} onClick={onSubmit}>
          <Save className="h-4 w-4" />
          保存凭证
        </Button>
        <Button size="sm" variant="outline" onClick={onCancel}>
          取消
        </Button>
      </div>
    </div>
  );
}

/** 供 model 测试引用：品牌名问题提示（列表页与编辑表单共用一条口径）。 */
export const brandNameProblem = nameProblem;
