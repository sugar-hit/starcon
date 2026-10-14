'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import {
  AlertTriangle,
  Boxes,
  Check,
  Link2,
  Loader2,
  Plus,
  RefreshCw,
  Trash2,
  Unlink,
} from 'lucide-react';
import type {
  BundleComponentCheck,
  BundleComponentItem,
  BundleComponentOverview,
  BundleComponentPage,
  BundleSkuPage,
} from '../../../../../packages/api-contracts/src/index';
import { Editor, Field, control } from '../common/fields';
import { useIdentity } from '../iam/identity-shell';
import { emptySkuFilters, fetchSkuPage } from './sku-list-model';
import {
  addComponent,
  addComponentProblem,
  canEditComponent,
  checkSummary,
  componentOrigin,
  componentSummary,
  createLink,
  deleteComponent,
  deleteLink,
  editComponent,
  errorText,
  fetchComponentCheck,
  fetchComponentOverview,
  fetchComponents,
  fetchLinks,
  frozenHint,
  groupProblems,
  nestedHint,
  problemTone,
  quantityProblem,
  quantityText,
  toQuantityNumber,
  updateLink,
} from './component-model';

const PAGE_SIZE = 20;

type VersionOption = { id: string; label: string; editable: boolean; bundleId: string };
type BundleOption = { id: string; label: string; enabled: boolean };

/**
 * 3.5.1.2 组件维护工作台。
 *
 * <p>页面按「先看清楚、再动手」的顺序排：
 *
 * <ul>
 *   <li><b>概览</b> —— 组件存量、嵌套规模，以及两个<b>健康指标</b>（自引用、数量非法）。
 *       它们正常恒为 0；摆出来是为了让「一条都没坏」可核对。
 *   <li><b>校验结果</b> —— 选一个版本，把循环 / 数量非法 / 组件不存在<b>一次列全</b>。
 *       这是蓝图写明的输出；不是让人反复提交试错。
 *   <li><b>跨版本组件清单</b> —— 逐行改数量 / 改备注 / 删行（行级乐观锁）。
 *       已发布版本的行是冻结的，界面上直接标出来并说清原因。
 *   <li><b>SKU ↔ 套装指向</b> —— 「哪些 SKU 代表哪个套装」。嵌套套装在数据上就靠这一层表达，
 *       没有它「套装 A 含套装 B」无法表达，循环也就无从发生。
 * </ul>
 */
export function ComponentWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('catalog.spu.update');

  const [overview, setOverview] = useState<BundleComponentOverview | null>(null);
  const [page, setPage] = useState<BundleComponentPage | null>(null);
  const [links, setLinks] = useState<BundleSkuPage | null>(null);
  const [check, setCheck] = useState<BundleComponentCheck | null>(null);
  const [skus, setSkus] = useState<{ id: string; code: string; name: string }[]>([]);
  const [versions, setVersions] = useState<VersionOption[]>([]);
  const [bundleOptions, setBundleOptions] = useState<BundleOption[]>([]);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [actionError, setActionError] = useState('');

  const [keyword, setKeyword] = useState('');
  const [nested, setNested] = useState('');
  const [pageIndex, setPageIndex] = useState(0);
  const [checkVersionId, setCheckVersionId] = useState('');

  const [adding, setAdding] = useState<{
    versionId: string;
    skuId: string;
    quantity: string;
  } | null>(null);
  const [editing, setEditing] = useState<{
    row: BundleComponentItem;
    quantity: string;
    role: string;
    note: string;
  } | null>(null);
  const [linking, setLinking] = useState<{ bundleId: string; skuId: string; note: string } | null>(
    null,
  );
  const [relinking, setRelinking] = useState<{
    id: string;
    bundleId: string;
    rowVersion: number;
  } | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [o, p, l] = await Promise.all([
        fetchComponentOverview(),
        fetchComponents({
          keyword,
          nested,
          versionId: null,
          bundleId: null,
          page: pageIndex,
          size: PAGE_SIZE,
        }),
        fetchLinks({ keyword: null, page: 0, size: 200 }),
      ]);
      setOverview(o);
      setPage(p);
      setLinks(l);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [keyword, nested, pageIndex]);

  useEffect(() => {
    void load();
  }, [load]);

  // SKU 下拉与版本/套装下拉：组件必须指向真实存在的 SKU，校验也按版本跑。
  useEffect(() => {
    void (async () => {
      try {
        const [skuResult] = await Promise.all([fetchSkuPage(emptySkuFilters, 0, 200)]);
        setSkus(skuResult.items.map((s) => ({ id: s.id, code: s.code, name: s.name })));
      } catch {
        setSkus([]);
      }
    })();
  }, []);

  // 版本与套装下拉复用 3.5.1.1 的列表（组件挂版本、指向挂套装，那边是唯一权威来源，
  // 这里不另建一份数据 —— 复制一份就会出现「这边能选、那边没有」的幽灵选项）。
  useEffect(() => {
    void (async () => {
      try {
        const { fetchBundles, fetchVersions } = await import('./bundle-model');
        const [bundleResult, versionResult] = await Promise.all([
          fetchBundles({ keyword: null, status: 'ACTIVE', page: 0, size: 200 }),
          fetchVersions({ keyword: null, status: null, bundleId: null, page: 0, size: 200 }),
        ]);
        setBundleOptions(
          bundleResult.items.map((b) => ({ id: b.id, label: `${b.code}`, enabled: true })),
        );
        setVersions(
          versionResult.items.map((v) => ({
            id: v.id,
            label: `${v.bundleCode} · ${v.versionNo}`,
            editable: v.editable,
            bundleId: v.bundleId,
          })),
        );
      } catch {
        setBundleOptions([]);
        setVersions([]);
      }
    })();
  }, []);

  const selectedVersion = versions.find((v) => v.id === checkVersionId) ?? null;

  const runCheck = useCallback(async (versionId: string) => {
    setActionError('');
    setNotice('');
    if (!versionId) {
      setCheck(null);
      return;
    }
    try {
      setCheck(await fetchComponentCheck(versionId));
    } catch (e) {
      setActionError(errorText(e));
    }
  }, []);

  const problemGroups = useMemo(() => (check ? groupProblems(check.problems) : []), [check]);
  const nestedCount = useMemo(() => (page?.items ?? []).filter((i) => i.nested).length, [page]);
  const totalQuantity = useMemo(
    () => (page?.items ?? []).reduce((sum, i) => sum + i.quantity, 0),
    [page],
  );

  async function guard(work: () => Promise<void>, done: string) {
    setActionError('');
    setNotice('');
    try {
      await work();
      setNotice(done);
      await load();
      if (checkVersionId) await runCheck(checkVersionId);
    } catch (e) {
      setActionError(errorText(e));
    }
  }

  async function submitAdd() {
    if (!adding) return;
    const problem = addComponentProblem(adding.skuId, adding.quantity);
    if (problem) {
      setActionError(problem);
      return;
    }
    const { versionId, skuId, quantity } = adding;
    await guard(async () => {
      await addComponent(versionId, {
        skuId,
        quantity: toQuantityNumber(quantity),
        role: null,
        note: null,
      });
      setAdding(null);
    }, '组件已加入这一版');
  }

  async function submitEdit() {
    if (!editing) return;
    const problem = quantityProblem(editing.quantity);
    if (problem) {
      setActionError(problem);
      return;
    }
    const { row, quantity, role, note } = editing;
    await guard(async () => {
      await editComponent(row.id, {
        quantity: toQuantityNumber(quantity),
        role,
        note,
        rowVersion: row.rowVersion,
      });
      setEditing(null);
    }, '组件已更新（数量、角色、备注）');
  }

  async function submitLink() {
    if (!linking) return;
    if (!linking.bundleId || !linking.skuId) {
      setActionError('指向要同时指定套装与 SKU');
      return;
    }
    await guard(async () => {
      await createLink({
        bundleId: linking.bundleId,
        skuId: linking.skuId,
        note: linking.note,
        rowVersion: 0,
      });
      setLinking(null);
    }, '指向已建立：这个 SKU 现在代表该套装，别的套装可以把它当组件');
  }

  async function submitRelink() {
    if (!relinking) return;
    await guard(async () => {
      const current = links?.items.find((r) => r.id === relinking.id);
      await updateLink(relinking.id, {
        bundleId: relinking.bundleId,
        skuId: current?.skuId ?? '',
        note: current?.note ?? '',
        rowVersion: relinking.rowVersion,
      });
      setRelinking(null);
    }, '指向已改：所有把它当组件的套装的出边都跟着变了');
  }

  if (loading && !page) {
    return (
      <div className="text-muted-foreground text-copy-13 flex items-center gap-2">
        <Loader2 className="size-4 animate-spin" />
        正在读取组件清单…
      </div>
    );
  }

  if (error && !page) {
    return (
      <SectionCard>
        <EmptyState
          icon={<AlertTriangle className="size-7" />}
          title="组件清单读取失败"
          description={error}
          action={
            <Button onClick={() => void load()} variant="secondary">
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
      {actionError && (
        <div className="border-destructive/40 bg-destructive/5 text-destructive text-copy-13 rounded-md border px-3 py-2">
          {actionError}
        </div>
      )}
      {notice && (
        <div className="border-border bg-muted/40 text-copy-13 rounded-md border px-3 py-2">
          {notice}
        </div>
      )}

      {/* 概览：两个健康指标正常恒为 0，摆在页面上是为了可核对 */}
      {overview && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard
            label="组件行"
            value={overview.componentCount}
            comparisonLabel={`${overview.versionWithComponentCount} 个版本有组件 · ${overview.distinctSkuCount} 个不同 SKU`}
          />
          <StatCard
            label="嵌套组件"
            value={overview.nestedComponentCount}
            comparisonLabel={`组件 SKU 代表套装的有 ${overview.linkedSkuCount} 个`}
          />
          <StatCard
            label="自引用"
            value={overview.selfReferencingCount}
            comparisonLabel="套装用自己的可售 SKU 当组件：正常恒为 0"
          />
          <StatCard
            label="数量非法"
            value={overview.nonPositiveQuantityCount}
            comparisonLabel="数量 ≤ 0 的行：正常恒为 0"
          />
        </div>
      )}
      {overview && <p className="text-muted-foreground text-copy-13">{overview.note}</p>}

      {/* 校验结果：一次列全，不用反复提交试错 */}
      <SectionCard
        title="校验结果"
        description="选一个版本，一次看全循环、数量非法、组件不存在 —— 保存时同样会被拒，这里只是让你一次改完"
      >
        <div className="space-y-3">
          <div className="flex flex-wrap items-end gap-2">
            <div className="min-w-64">
              <Field label="套装版本">
                <select
                  className={control}
                  value={checkVersionId}
                  onChange={(e) => {
                    setCheckVersionId(e.target.value);
                    void runCheck(e.target.value);
                  }}
                >
                  <option value="">请选择一个版本</option>
                  {versions.map((v) => (
                    <option key={v.id} value={v.id}>
                      {v.label}
                      {v.editable ? '' : '（已发布，组件冻结）'}
                    </option>
                  ))}
                </select>
              </Field>
            </div>
            {checkVersionId && (
              <Button variant="secondary" onClick={() => void runCheck(checkVersionId)}>
                <RefreshCw className="mr-2 size-4" />
                重新校验
              </Button>
            )}
            {selectedVersion?.editable && writable && (
              <Button
                onClick={() => setAdding({ versionId: checkVersionId, skuId: '', quantity: '1' })}
              >
                <Plus className="mr-2 size-4" />
                加组件
              </Button>
            )}
          </div>

          {!checkVersionId ? (
            <EmptyState
              icon={<Check className="size-7" />}
              title="选一个版本开始校验"
              description="组件挂在版本上（已发布版本的组件不可改，只能新建版本），所以校验也是按版本跑的。"
            />
          ) : !check ? (
            <div className="text-muted-foreground text-copy-13 flex items-center gap-2">
              <Loader2 className="size-4 animate-spin" />
              正在校验…
            </div>
          ) : check.ok ? (
            <div className="border-success/40 bg-success/5 text-success text-copy-13 rounded-md border px-3 py-2">
              {checkSummary(check)}（{check.componentCount} 个组件，每件合计{' '}
              {quantityText(check.totalQuantity)}）
            </div>
          ) : (
            <div className="space-y-2">
              <div className="border-destructive/40 bg-destructive/5 text-destructive text-copy-13 rounded-md border px-3 py-2">
                {checkSummary(check)}
              </div>
              {problemGroups.map((group) => (
                <div key={group.code} className="border-border rounded-md border px-3 py-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={problemTone(group.code)}>{group.label}</Badge>
                    <span className="text-copy-13 font-medium">{group.items.length} 条</span>
                    {group.hint && (
                      <span className="text-muted-foreground text-copy-12">{group.hint}</span>
                    )}
                  </div>
                  <ul className="mt-2 space-y-1">
                    {group.items.map((p, index) => (
                      <li key={`${group.code}-${index}`} className="text-copy-13">
                        {p.message}
                      </li>
                    ))}
                  </ul>
                </div>
              ))}
              <p className="text-muted-foreground text-copy-12">{check.note}</p>
            </div>
          )}
        </div>
      </SectionCard>

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
        {/* 跨版本组件清单 */}
        <SectionCard
          title="组件清单（跨版本）"
          description="逐行改数量 / 改备注 / 删行；已发布版本的行是冻结的"
        >
          <div className="space-y-3">
            <div className="flex flex-wrap gap-2">
              <input
                className={control}
                placeholder="SKU 编码/名称、套装编码、版本号、角色"
                value={keyword}
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setPageIndex(0);
                }}
              />
              <select
                className={control}
                value={nested}
                onChange={(e) => {
                  setNested(e.target.value);
                  setPageIndex(0);
                }}
              >
                <option value="">全部组件</option>
                <option value="true">只看嵌套套装</option>
                <option value="false">只看普通 SKU</option>
              </select>
            </div>

            {!page || page.items.length === 0 ? (
              <EmptyState
                icon={<Boxes className="size-7" />}
                title={keyword || nested ? '没有匹配的组件' : '还没有任何组件'}
                description={
                  keyword || nested
                    ? '换个关键词或把「只看嵌套套装」关掉再试。搜索是真的按 SKU 编码/名称、套装编码、版本号与角色过滤的。'
                    : '组件挂在套装版本上：先在「套装版本」里建一个草稿版本，再来这里挑 SKU 加进来。'
                }
              />
            ) : (
              <ul className="divide-border divide-y">
                {page.items.map((row) => (
                  <li key={row.id} className="space-y-1.5 py-2">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-medium">{row.skuCode}</span>
                      <span className="text-copy-13 truncate">{row.skuName}</span>
                      <Badge tone={row.versionStatus === 'PUBLISHED' ? 'success' : 'warning'}>
                        {row.versionStatusLabel}
                      </Badge>
                      {row.nested && <Badge tone="info">嵌套套装：{row.linkedBundleCode}</Badge>}
                    </div>
                    <div className="text-muted-foreground text-copy-12">
                      {componentOrigin(row)} · 每件 {quantityText(row.quantity)}
                      {row.role ? ` · ${row.role}` : ''}
                      {row.note ? ` · ${row.note}` : ''}
                    </div>
                    {row.nested && <p className="text-info text-copy-12">{nestedHint(row)}</p>}
                    <div className="flex flex-wrap gap-2">
                      {canEditComponent(row) && writable ? (
                        <>
                          <Button
                            size="sm"
                            variant="secondary"
                            onClick={() =>
                              setEditing({
                                row,
                                quantity: String(row.quantity),
                                role: row.role,
                                note: row.note,
                              })
                            }
                          >
                            编辑
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() =>
                              void guard(
                                () => deleteComponent(row.id, row.rowVersion).then(() => undefined),
                                '组件已删除',
                              )
                            }
                          >
                            <Trash2 className="mr-2 size-4" />
                            删除
                          </Button>
                        </>
                      ) : (
                        <span className="text-muted-foreground text-copy-12">
                          {frozenHint(row)}
                        </span>
                      )}
                    </div>
                  </li>
                ))}
              </ul>
            )}

            {page && page.totalPages > 1 && (
              <div className="flex items-center justify-between gap-2">
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={pageIndex === 0}
                  onClick={() => setPageIndex((p) => Math.max(0, p - 1))}
                >
                  上一页
                </Button>
                <span className="text-muted-foreground text-copy-12">
                  第 {pageIndex + 1} / {page.totalPages} 页 · 共 {page.total} 行
                </span>
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={pageIndex + 1 >= page.totalPages}
                  onClick={() => setPageIndex((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            )}
            {page && page.items.length > 0 && (
              <p className="text-muted-foreground text-copy-12">
                {componentSummary(page.items, totalQuantity, nestedCount)}
              </p>
            )}
          </div>
        </SectionCard>

        {/* SKU ↔ 套装指向 */}
        <SectionCard
          title="SKU ↔ 套装指向"
          description="「这个 SKU 是那个套装的可售面」—— 嵌套套装与循环检测都靠这一层"
        >
          <div className="space-y-3">
            {writable && (
              <div className="flex flex-wrap gap-2">
                <Button size="sm" onClick={() => setLinking({ bundleId: '', skuId: '', note: '' })}>
                  <Plus className="mr-2 size-4" />
                  新建指向
                </Button>
              </div>
            )}
            {!links || links.items.length === 0 ? (
              <EmptyState
                icon={<Link2 className="size-7" />}
                title="还没有任何指向"
                description="没有指向，「套装 A 含套装 B」在数据上就无法表达，循环也就无从发生。指向的意思是：这个 SKU 作为可售商品，展开时展开的是那个套装。"
              />
            ) : (
              <ul className="divide-border divide-y">
                {links.items.map((row) => (
                  <li key={row.id} className="space-y-1.5 py-2">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-medium">{row.skuCode}</span>
                      <span className="text-muted-foreground text-copy-12">代表套装</span>
                      <span className="font-medium">{row.bundleCode}</span>
                      {row.usedByPublished && <Badge tone="warning">已被已发布版本引用</Badge>}
                    </div>
                    <div className="text-muted-foreground text-copy-12">
                      {row.skuName} · 被 {row.usedByComponentCount} 个组件行引用
                      {row.note ? ` · ${row.note}` : ''}
                    </div>
                    {writable && (
                      <div className="flex flex-wrap gap-2">
                        <Button
                          size="sm"
                          variant="secondary"
                          onClick={() =>
                            setRelinking({
                              id: row.id,
                              bundleId: row.bundleId,
                              rowVersion: row.rowVersion,
                            })
                          }
                        >
                          改指向
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() =>
                            void guard(
                              () => deleteLink(row.id, row.rowVersion).then(() => undefined),
                              '指向已解开',
                            )
                          }
                        >
                          <Unlink className="mr-2 size-4" />
                          解开
                        </Button>
                      </div>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </div>
        </SectionCard>
      </div>

      {adding && (
        <Editor
          title="加一个组件"
          description="组件必须指向本租户真实存在的 SKU；数量必须大于 0（numeric(19,6)，支持 0.5 这类小数）"
          close={() => setAdding(null)}
        >
          <div className="space-y-3">
            <Field label="组件 SKU">
              <select
                className={control}
                value={adding.skuId}
                onChange={(e) => setAdding((a) => (a ? { ...a, skuId: e.target.value } : a))}
              >
                <option value="">请选择 SKU</option>
                {skus.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.code} {s.name}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="每件数量" hint="必须大于 0，最多 6 位小数">
              <input
                className={control}
                value={adding.quantity}
                onChange={(e) => setAdding((a) => (a ? { ...a, quantity: e.target.value } : a))}
              />
            </Field>
            <div className="flex gap-2">
              <Button onClick={() => void submitAdd()}>加入</Button>
              <Button variant="secondary" onClick={() => setAdding(null)}>
                取消
              </Button>
            </div>
          </div>
        </Editor>
      )}

      {editing && (
        <Editor
          title={`编辑组件 · ${editing.row.skuCode}`}
          description="SKU 不可改：组件行的身份是「这个版本里的这个 SKU」，换 SKU 走删一行加一行"
          close={() => setEditing(null)}
        >
          <div className="space-y-3">
            <Field label="每件数量" hint="必须大于 0，最多 6 位小数">
              <input
                className={control}
                value={editing.quantity}
                onChange={(e) => setEditing((v) => (v ? { ...v, quantity: e.target.value } : v))}
              />
            </Field>
            <Field label="组件角色" hint="自由文本，只给人看，不参与任何判定">
              <input
                className={control}
                value={editing.role}
                onChange={(e) => setEditing((v) => (v ? { ...v, role: e.target.value } : v))}
              />
            </Field>
            <Field label="备注">
              <input
                className={control}
                value={editing.note}
                onChange={(e) => setEditing((v) => (v ? { ...v, note: e.target.value } : v))}
              />
            </Field>
            <div className="flex gap-2">
              <Button onClick={() => void submitEdit()}>保存</Button>
              <Button variant="secondary" onClick={() => setEditing(null)}>
                取消
              </Button>
            </div>
          </div>
        </Editor>
      )}

      {linking && (
        <Editor
          title="新建指向"
          description="一个 SKU 至多代表一个套装；改指向会一次性改掉所有把它当组件的套装的出边，所以同样要过循环检测"
          close={() => setLinking(null)}
        >
          <div className="space-y-3">
            <Field label="代表哪个套装" hint="套装编码从 3.5.1.1 的套装档案里选">
              <select
                className={control}
                value={linking.bundleId}
                onChange={(e) => setLinking((l) => (l ? { ...l, bundleId: e.target.value } : l))}
              >
                <option value="">请选择套装</option>
                {bundleOptions.map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="哪个 SKU">
              <select
                className={control}
                value={linking.skuId}
                onChange={(e) => setLinking((l) => (l ? { ...l, skuId: e.target.value } : l))}
              >
                <option value="">请选择 SKU</option>
                {skus.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.code} {s.name}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="备注">
              <input
                className={control}
                value={linking.note}
                onChange={(e) => setLinking((l) => (l ? { ...l, note: e.target.value } : l))}
              />
            </Field>
            <div className="flex gap-2">
              <Button onClick={() => void submitLink()}>建立指向</Button>
              <Button variant="secondary" onClick={() => setLinking(null)}>
                取消
              </Button>
            </div>
          </div>
        </Editor>
      )}

      {relinking && (
        <Editor
          title="改指向"
          description="改完之后所有把这个 SKU 当组件的套装，出边都会跟着变 —— 如果这样会闭合成环，会被拒绝并点名整条环"
          close={() => setRelinking(null)}
        >
          <div className="space-y-3">
            <Field label="改成代表哪个套装">
              <select
                className={control}
                value={relinking.bundleId}
                onChange={(e) => setRelinking((r) => (r ? { ...r, bundleId: e.target.value } : r))}
              >
                <option value="">请选择套装</option>
                {bundleOptions.map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.label}
                  </option>
                ))}
              </select>
            </Field>
            <div className="flex gap-2">
              <Button onClick={() => void submitRelink()}>保存</Button>
              <Button variant="secondary" onClick={() => setRelinking(null)}>
                取消
              </Button>
            </div>
          </div>
        </Editor>
      )}
    </div>
  );
}
