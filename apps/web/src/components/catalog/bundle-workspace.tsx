'use client';

import { useCallback, useEffect, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import {
  AlertTriangle,
  Boxes,
  Check,
  Clock,
  Layers,
  Loader2,
  Plus,
  RefreshCw,
  Trash2,
} from 'lucide-react';
import type {
  BundleComponentRow,
  BundleDetail,
  BundleEffectiveView,
  BundleInput,
  BundleOverview,
  BundlePage,
  BundleRow,
  BundleVersionDetail,
  BundleVersionInput,
  BundleVersionRow,
} from '../../../../../packages/api-contracts/src/index';
import { Editor, Field, control } from '../common/fields';
import { useIdentity } from '../iam/identity-shell';
import {
  BUNDLE_STATUSES,
  MAX_COMPONENTS_PER_VERSION,
  VERSION_STATUSES,
  bundleStatusLabel,
  bundleStatusTone,
  canCloseVersion,
  canEditVersion,
  closeHint,
  closeToProblem,
  closeVersion,
  codeProblem,
  componentSummary,
  componentsProblem,
  createVersion,
  deleteBundle,
  deleteVersion,
  effectiveLabel,
  emptyBundleInput,
  emptyVersionInput,
  errorText,
  fetchBundle,
  fetchBundleOverview,
  fetchBundles,
  fetchEffectiveVersion,
  fetchVersion,
  fetchVersions,
  instantToLocalInput,
  localInputToInstant,
  publishVersion,
  rangeOrderProblem,
  rangeText,
  replaceComponents,
  saveBundle,
  setBundleStatus,
  statusLabel,
  statusTone,
  toQuantityNumber,
  toVersionInput,
  updateVersion,
  versionNameProblem,
  versionNoProblem,
} from './bundle-model';
import { emptySkuFilters, fetchSkuPage } from './sku-list-model';

const PAGE_SIZE = 20;

/**
 * 3.5.1.1 套装版本工作区。
 *
 * <p>页面按运营真正的工作顺序排：
 *
 * <ul>
 *   <li><b>套装</b> —— 一行一个虚拟组合（多个 SKU 按数量捆绑销售）。
 *       <b>虚拟套装不持有独立库存</b>，扣的是组件库存，所以这一页管的是「卖什么」，
 *       不是「有多少」。
 *   <li><b>版本</b> —— 草稿可以随便改，<b>发布后整版冻结</b>。页面上把「被冻结的字段」
 *       逐条列出来，而不是让人试了再报错。已发布版本唯一还能做的是「提前停用本版」。
 *   <li><b>生效区间</b> —— 左闭右开 [起点, 终点)，重叠一律拒绝不做裁决。
 *       「此刻生效的是哪一版」<b>由后端算</b>，前端只显示，不拿版本号大小去猜。
 *   <li><b>当前生效版本</b> —— 可按任意时刻查（04 刊登与 09 订单取版本走的就是这个口径）。
 * </ul>
 */
export function BundleWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('catalog.spu.update');

  const [overview, setOverview] = useState<BundleOverview | null>(null);
  const [page, setPage] = useState<BundlePage | null>(null);
  const [detail, setDetail] = useState<BundleDetail | null>(null);
  const [effective, setEffective] = useState<BundleEffectiveView | null>(null);
  const [versions, setVersions] = useState<BundleVersionRow[]>([]);
  const [versionDetail, setVersionDetail] = useState<BundleVersionDetail | null>(null);
  const [skus, setSkus] = useState<{ id: string; code: string; name: string }[]>([]);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [actionError, setActionError] = useState('');

  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [pageIndex, setPageIndex] = useState(0);
  const [probeAt, setProbeAt] = useState('');

  const [bundleEditor, setBundleEditor] = useState<{
    id: string | null;
    input: BundleInput;
  } | null>(null);
  const [versionEditor, setVersionEditor] = useState<{
    bundleId: string;
    input: BundleVersionInput;
  } | null>(null);
  const [closing, setClosing] = useState<BundleVersionRow | null>(null);
  const [closeAt, setCloseAt] = useState('');
  const [componentDraft, setComponentDraft] = useState<{ skuId: string; quantity: string }[]>([]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [o, p] = await Promise.all([
        fetchBundleOverview(),
        fetchBundles({ keyword, status, page: pageIndex, size: PAGE_SIZE }),
      ]);
      setOverview(o);
      setPage(p);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [keyword, status, pageIndex]);

  useEffect(() => {
    void load();
  }, [load]);

  // SKU 下拉：组件必须指向真实存在的 SKU，这里一次取一页供选择。
  useEffect(() => {
    void (async () => {
      try {
        const result = await fetchSkuPage(emptySkuFilters, 0, 200);
        setSkus(
          result.items.map((s) => ({
            id: s.id,
            code: s.code,
            name: s.name,
          })),
        );
      } catch {
        setSkus([]);
      }
    })();
  }, []);

  const probeEffective = useCallback(async (bundleId: string, at: string) => {
    try {
      setEffective(await fetchEffectiveVersion(bundleId, at || undefined));
    } catch (e) {
      setActionError(errorText(e));
    }
  }, []);

  const openBundle = useCallback(
    async (id: string) => {
      setActionError('');
      setNotice('');
      try {
        const d = await fetchBundle(id);
        setDetail(d);
        const list = await fetchVersions({
          keyword: null,
          status: null,
          bundleId: id,
          page: 0,
          size: 200,
        });
        setVersions(list.items);
        await probeEffective(id, '');
      } catch (e) {
        setActionError(errorText(e));
      }
    },
    [probeEffective],
  );

  const openVersion = useCallback(async (id: string) => {
    setActionError('');
    try {
      const d = await fetchVersion(id);
      setVersionDetail(d);
      setComponentDraft(
        d.components.map((c) => ({ skuId: c.skuId, quantity: String(c.quantity) })),
      );
    } catch (e) {
      setActionError(errorText(e));
    }
  }, []);

  async function guard(work: () => Promise<void>, done: string) {
    setActionError('');
    setNotice('');
    try {
      await work();
      setNotice(done);
      await load();
    } catch (e) {
      setActionError(errorText(e));
    }
  }

  async function submitBundleEditor() {
    if (!bundleEditor) return;
    const problem = codeProblem(bundleEditor.input.code);
    if (problem) {
      setActionError(problem);
      return;
    }
    const id = bundleEditor.id;
    const input = bundleEditor.input;
    await guard(
      async () => {
        const saved = await saveBundle(id, input);
        setBundleEditor(null);
        if (!id) await openBundle(saved.id);
      },
      id ? '套装已更新（编码建后不可改）' : '套装已建',
    );
  }

  async function submitVersionEditor() {
    if (!versionEditor) return;
    const { bundleId, input } = versionEditor;
    const problem =
      versionNoProblem(input.versionNo) ??
      versionNameProblem(input.name) ??
      rangeOrderProblem(input.effectiveFrom, input.effectiveTo ?? '');
    if (problem) {
      setActionError(problem);
      return;
    }
    const body: BundleVersionInput = {
      ...input,
      effectiveFrom: localInputToInstant(input.effectiveFrom),
      effectiveTo: input.effectiveTo ? localInputToInstant(input.effectiveTo) : null,
    };
    const editing = input.version > 0;
    const versionId = detail?.versions.find((v) => v.versionNo === input.versionNo)?.id;
    await guard(
      async () => {
        if (editing && versionId) await updateVersion(versionId, body);
        else await createVersion(bundleId, body);
        setVersionEditor(null);
        await openBundle(bundleId);
      },
      editing ? '草稿版本已更新' : '草稿版本已建（发布后整版冻结）',
    );
  }

  async function submitComponents() {
    if (!versionDetail) return;
    const problem = componentsProblem(componentDraft);
    if (problem) {
      setActionError(problem);
      return;
    }
    const versionId = versionDetail.version.id;
    const rowVersion = versionDetail.version.version;
    await guard(async () => {
      await replaceComponents(versionId, {
        components: componentDraft.map((c) => ({
          skuId: c.skuId,
          quantity: toQuantityNumber(c.quantity),
          role: null,
          note: null,
        })),
        version: rowVersion,
      });
      await openVersion(versionId);
      if (detail) await openBundle(detail.bundle.id);
    }, '组件清单已整体替换（保存后清单就是提交的那份）');
  }

  async function submitClose() {
    if (!closing) return;
    const problem = closeToProblem(closeAt, closing.effectiveTo);
    if (problem) {
      setActionError(problem);
      return;
    }
    const id = closing.id;
    await guard(
      async () => {
        await closeVersion(id, localInputToInstant(closeAt));
        setClosing(null);
        if (detail) await openBundle(detail.bundle.id);
      },
      `已提前停用本版：${localInputToInstant(closeAt)} 起由下一个版本接替`,
    );
  }

  const totals = overview;

  if (loading && !page) {
    return (
      <div className="text-muted-foreground text-copy-13 flex items-center gap-2">
        <Loader2 className="size-4 animate-spin" />
        正在读取套装与版本…
      </div>
    );
  }

  if (error && !page) {
    return (
      <SectionCard>
        <EmptyState
          icon={<AlertTriangle className="size-7" />}
          title="套装版本读取失败"
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

      {/* 概览：开放区间是这个模块唯一需要每天盯的数字 */}
      {totals && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard
            label="套装"
            value={totals.bundleCount}
            comparisonLabel={`启用 ${totals.activeCount} · 归档 ${totals.archivedCount}`}
          />
          <StatCard
            label="版本"
            value={totals.versionCount}
            comparisonLabel={`草稿 ${totals.draftCount} · 已发布 ${totals.publishedCount}`}
          />
          <StatCard
            label="此刻生效"
            value={totals.effectiveNowCount}
            comparisonLabel="同一时刻至多一个生效版本（重叠一律拒绝）"
          />
          <StatCard
            label="开放区间"
            value={totals.openEndedCount}
            comparisonLabel="已发布且永远有效；没被接替就会一直停在旧版本上"
          />
        </div>
      )}
      {totals && <p className="text-muted-foreground text-copy-13">{totals.note}</p>}

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)]">
        {/* ------------------------------------------------ 套装列表 */}
        <SectionCard
          title="套装"
          description="虚拟组合（多个 SKU 按数量捆绑销售）· 不持有独立库存，扣的是组件库存"
        >
          <div className="space-y-3">
            <div className="flex flex-wrap gap-2">
              <input
                className={control}
                placeholder="按套装编码搜索"
                value={keyword}
                onChange={(e) => {
                  setPageIndex(0);
                  setKeyword(e.target.value);
                }}
              />
              <select
                className={control}
                value={status}
                onChange={(e) => {
                  setPageIndex(0);
                  setStatus(e.target.value);
                }}
              >
                <option value="">全部状态</option>
                {BUNDLE_STATUSES.map((s) => (
                  <option key={s.value} value={s.value}>
                    {s.label}
                  </option>
                ))}
              </select>
              {writable && (
                <Button onClick={() => setBundleEditor({ id: null, input: emptyBundleInput() })}>
                  <Plus className="mr-2 size-4" />
                  新建套装
                </Button>
              )}
            </div>

            {!page || page.items.length === 0 ? (
              <EmptyState
                icon={<Boxes className="size-7" />}
                title="还没有套装"
                description="套装是多个 SKU 按数量捆绑销售的虚拟组合。它不持有独立库存 —— 卖套装扣的是组件库存。"
              />
            ) : (
              <ul className="divide-border divide-y">
                {page.items.map((b) => (
                  <li key={b.id} className="flex items-start justify-between gap-3 py-2">
                    <button className="min-w-0 text-left" onClick={() => void openBundle(b.id)}>
                      <div className="flex items-center gap-2">
                        <span className="truncate font-medium">{b.code}</span>
                        <Badge tone={bundleStatusTone(b.status)}>
                          {bundleStatusLabel(b.status)}
                        </Badge>
                      </div>
                      <div className="text-muted-foreground text-copy-12">
                        {b.versionCount} 个版本（草稿 {b.draftCount} · 已发布 {b.publishedCount}）·{' '}
                        {effectiveLabel(b)}
                      </div>
                    </button>
                  </li>
                ))}
              </ul>
            )}

            {page && page.totalPages > 1 && (
              <div className="text-muted-foreground text-copy-12 flex items-center justify-end gap-2">
                <Button
                  disabled={pageIndex === 0}
                  onClick={() => setPageIndex((p) => p - 1)}
                  size="sm"
                >
                  上一页
                </Button>
                <span>
                  第 {page.page + 1} / {page.totalPages} 页 · 共 {page.total} 个
                </span>
                <Button
                  disabled={pageIndex + 1 >= page.totalPages}
                  onClick={() => setPageIndex((p) => p + 1)}
                  size="sm"
                >
                  下一页
                </Button>
              </div>
            )}
          </div>
        </SectionCard>

        {/* ------------------------------------------------ 套装详情与版本 */}
        <SectionCard
          title={detail ? `套装 ${detail.bundle.code}` : '套装详情'}
          description={
            detail
              ? (detail.note ?? undefined)
              : '在左侧选一个套装：看它的版本、生效区间，以及此刻生效的是哪一版'
          }
        >
          {!detail ? (
            <EmptyState
              icon={<Layers className="size-7" />}
              title="选一个套装"
              description="这里会显示它的全部版本、每个版本的生效区间，以及「此刻生效的是哪一版」—— 由后端按生效区间算，不按版本号大小猜。"
            />
          ) : (
            <div className="space-y-4">
              <div className="flex flex-wrap gap-2">
                {writable && detail.bundle.status === 'ACTIVE' && (
                  <Button
                    onClick={() =>
                      setVersionEditor({
                        bundleId: detail.bundle.id,
                        input: emptyVersionInput(),
                      })
                    }
                  >
                    <Plus className="mr-2 size-4" />
                    新建草稿版本
                  </Button>
                )}
                {writable && (
                  <Button
                    variant="secondary"
                    onClick={() =>
                      setBundleEditor({
                        id: detail.bundle.id,
                        input: {
                          code: detail.bundle.code,
                          note: detail.bundle.note,
                          version: detail.bundle.version,
                        },
                      })
                    }
                  >
                    改备注
                  </Button>
                )}
                {writable && (
                  <Button
                    variant="secondary"
                    onClick={() =>
                      void guard(
                        () =>
                          setBundleStatus(
                            detail.bundle.id,
                            detail.bundle.status === 'ACTIVE' ? 'ARCHIVED' : 'ACTIVE',
                          ).then(async () => {
                            await openBundle(detail.bundle.id);
                          }),
                        detail.bundle.status === 'ACTIVE'
                          ? '套装已归档（历史版本照常可读）'
                          : '套装已启用',
                      )
                    }
                  >
                    {detail.bundle.status === 'ACTIVE' ? '归档' : '启用'}
                  </Button>
                )}
                {writable && detail.versions.length === 0 && (
                  <Button
                    variant="ghost"
                    onClick={() =>
                      void guard(async () => {
                        await deleteBundle(detail.bundle.id, detail.bundle.version);
                        setDetail(null);
                        setEffective(null);
                        setVersions([]);
                      }, '套装已删除')
                    }
                  >
                    <Trash2 className="mr-2 size-4" />
                    删除套装
                  </Button>
                )}
              </div>

              {/* 当前生效版本（可按任意时刻查） */}
              <div className="bg-muted/30 space-y-2 rounded-md border p-3">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-copy-13 font-medium">当前生效版本</span>
                  <span className="text-muted-foreground text-copy-12">
                    04 刊登与 09 订单取的就是这个口径
                  </span>
                </div>
                {effective ? (
                  effective.effective ? (
                    <div className="text-copy-13 space-y-1">
                      <div className="flex items-center gap-2">
                        <Badge tone="success">{effective.versionNo}</Badge>
                        <span className="font-medium">{effective.name}</span>
                      </div>
                      <div className="text-muted-foreground text-copy-12">
                        在 {effective.at} 这一刻生效 ·{' '}
                        {componentSummary(effective.components, null)}
                      </div>
                      <ul className="text-muted-foreground text-copy-12 space-y-0.5">
                        {effective.components.map((c) => (
                          <li key={c.id}>
                            {c.skuCode} {c.skuName} × {String(c.quantity)}
                            {c.role ? ` · ${c.role}` : ''}
                          </li>
                        ))}
                      </ul>
                      <p className="text-copy-12">{effective.note}</p>
                    </div>
                  ) : (
                    <p className="text-copy-13 text-muted-foreground">{effective.note}</p>
                  )
                ) : (
                  <p className="text-muted-foreground text-copy-13">读取中…</p>
                )}
                <div className="flex items-end gap-2">
                  <Field label="按某个时刻查（留空=此刻）">
                    <input
                      type="datetime-local"
                      className={control}
                      value={probeAt}
                      onChange={(e) => setProbeAt(e.target.value)}
                    />
                  </Field>
                  <Button
                    size="sm"
                    onClick={() =>
                      void probeEffective(detail.bundle.id, localInputToInstant(probeAt))
                    }
                  >
                    <Clock className="mr-2 size-4" />
                    查询
                  </Button>
                </div>
              </div>

              {/* 版本列表 */}
              <div className="space-y-2">
                <div className="text-copy-13 font-medium">版本（{versions.length}）</div>
                {versions.length === 0 ? (
                  <EmptyState
                    icon={<Layers className="size-7" />}
                    title="这个套装还没有版本"
                    description="版本才是套装真正卖的东西：名称、版本号、生效区间、组件清单都在版本上。先建一个草稿版本。"
                  />
                ) : (
                  <ul className="divide-border divide-y">
                    {versions.map((v) => (
                      <li key={v.id} className="space-y-1 py-2">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="font-medium">{v.versionNo}</span>
                          <span className="truncate">{v.name}</span>
                          <Badge tone={statusTone(v.status)}>{statusLabel(v.status)}</Badge>
                          {v.effective && <Badge tone="success">此刻生效</Badge>}
                        </div>
                        <div className="text-muted-foreground text-copy-12">
                          {rangeText(v.effectiveFrom, v.effectiveTo)} · {v.componentCount} 个组件
                          {v.publishedAt
                            ? ` · ${statusLabel(v.status)}于 ${v.publishedAt.slice(0, 10)}`
                            : ''}
                        </div>
                        <div className="flex flex-wrap gap-2">
                          <Button
                            size="sm"
                            variant="secondary"
                            onClick={() => void openVersion(v.id)}
                          >
                            组件清单
                          </Button>
                          {canEditVersion(v) && writable && (
                            <>
                              <Button
                                size="sm"
                                onClick={() =>
                                  setVersionEditor({
                                    bundleId: v.bundleId,
                                    input: toVersionInput(v),
                                  })
                                }
                              >
                                编辑
                              </Button>
                              <Button
                                size="sm"
                                onClick={() =>
                                  void guard(
                                    () => publishVersion(v.id).then(() => openBundle(v.bundleId)),
                                    '已发布：这一版的内容从此冻结',
                                  )
                                }
                              >
                                <Check className="mr-2 size-4" />
                                发布
                              </Button>
                              <Button
                                size="sm"
                                variant="ghost"
                                onClick={() =>
                                  void guard(
                                    () =>
                                      deleteVersion(v.id, v.version).then(() =>
                                        openBundle(v.bundleId),
                                      ),
                                    '草稿版本已删除',
                                  )
                                }
                              >
                                <Trash2 className="mr-2 size-4" />
                                删除
                              </Button>
                            </>
                          )}
                          {canCloseVersion(v) && writable && (
                            <Button
                              size="sm"
                              variant="secondary"
                              title={closeHint(v)}
                              onClick={() => {
                                setClosing(v);
                                setCloseAt(instantToLocalInput(v.effectiveTo));
                              }}
                            >
                              提前停用本版
                            </Button>
                          )}
                        </div>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </div>
          )}
        </SectionCard>
      </div>

      {/* 组件清单工作区 */}
      {versionDetail && (
        <SectionCard
          title={`组件清单 · ${versionDetail.version.bundleCode} ${versionDetail.version.versionNo}`}
          description={componentSummary(versionDetail.components, versionDetail.totalQuantity)}
        >
          <div className="space-y-3">
            {versionDetail.editable ? (
              <>
                {componentDraft.map((c, index) => (
                  <div key={`${c.skuId}-${index}`} className="flex flex-wrap items-end gap-2">
                    <Field label="组件 SKU">
                      <select
                        className={control}
                        value={c.skuId}
                        onChange={(e) =>
                          setComponentDraft((rows) =>
                            rows.map((r, i) => (i === index ? { ...r, skuId: e.target.value } : r)),
                          )
                        }
                      >
                        <option value="">请选择 SKU</option>
                        {skus.map((s) => (
                          <option key={s.id} value={s.id}>
                            {s.code} {s.name}
                          </option>
                        ))}
                      </select>
                    </Field>
                    <Field label="每件套装需要">
                      <input
                        className={control}
                        value={c.quantity}
                        onChange={(e) =>
                          setComponentDraft((rows) =>
                            rows.map((r, i) =>
                              i === index ? { ...r, quantity: e.target.value } : r,
                            ),
                          )
                        }
                      />
                    </Field>
                    <Button
                      variant="ghost"
                      onClick={() =>
                        setComponentDraft((rows) => rows.filter((_, i) => i !== index))
                      }
                    >
                      移除
                    </Button>
                  </div>
                ))}
                <div className="flex flex-wrap gap-2">
                  <Button
                    variant="secondary"
                    disabled={componentDraft.length >= MAX_COMPONENTS_PER_VERSION}
                    onClick={() =>
                      setComponentDraft((rows) => [...rows, { skuId: '', quantity: '1' }])
                    }
                  >
                    <Plus className="mr-2 size-4" />
                    添加组件
                  </Button>
                  <Button onClick={() => void submitComponents()}>整体替换保存</Button>
                  <Button variant="ghost" onClick={() => setVersionDetail(null)}>
                    收起
                  </Button>
                </div>
                <p className="text-muted-foreground text-copy-12">
                  整体替换而不是增删式：保存后清单就是提交的那份，不会剩一条「幽灵组件」。 同一个
                  SKU 只能出现一次，数量必须大于 0。
                </p>
              </>
            ) : (
              <>
                <div className="text-copy-13 space-y-1">
                  {versionDetail.components.map((c) => (
                    <div key={c.id}>
                      {c.skuCode} {c.skuName} × {String(c.quantity)}
                      {c.role ? ` · ${c.role}` : ''}
                    </div>
                  ))}
                </div>
                <p className="text-muted-foreground text-copy-12">{versionDetail.editHint}</p>
              </>
            )}
          </div>
        </SectionCard>
      )}

      {/* ------------------------------------------------ 编辑器 */}
      {bundleEditor && (
        <Editor
          title={bundleEditor.id ? '套装备注' : '新建套装'}
          description="套装编码建后不可改 —— 它是刊登与订单里的稳定引用。"
          close={() => setBundleEditor(null)}
        >
          <div className="space-y-3">
            <Field label="套装编码" hint="字母数字与短横线，会统一转成大写">
              <input
                className={control}
                disabled={Boolean(bundleEditor.id)}
                value={bundleEditor.input.code}
                onChange={(e) =>
                  setBundleEditor((s) =>
                    s ? { ...s, input: { ...s.input, code: e.target.value } } : s,
                  )
                }
              />
            </Field>
            <Field label="备注">
              <textarea
                className={control}
                rows={3}
                value={bundleEditor.input.note ?? ''}
                onChange={(e) =>
                  setBundleEditor((s) =>
                    s ? { ...s, input: { ...s.input, note: e.target.value } } : s,
                  )
                }
              />
            </Field>
            <Button onClick={() => void submitBundleEditor()}>保存</Button>
          </div>
        </Editor>
      )}

      {versionEditor && (
        <Editor
          title={versionEditor.input.version > 0 ? '编辑草稿版本' : '新建草稿版本'}
          description="生效区间是左闭右开的 [起点, 终点)：终点留空表示此后一直有效。重叠的已发布版本一律拒绝。"
          close={() => setVersionEditor(null)}
        >
          <div className="space-y-3">
            <Field label="版本号" hint="同套装内唯一，建后不可改">
              <input
                className={control}
                value={versionEditor.input.versionNo}
                onChange={(e) =>
                  setVersionEditor((s) =>
                    s ? { ...s, input: { ...s.input, versionNo: e.target.value } } : s,
                  )
                }
              />
            </Field>
            <Field label="套装名称">
              <input
                className={control}
                value={versionEditor.input.name}
                onChange={(e) =>
                  setVersionEditor((s) =>
                    s ? { ...s, input: { ...s.input, name: e.target.value } } : s,
                  )
                }
              />
            </Field>
            <Field label="生效起点">
              <input
                type="datetime-local"
                className={control}
                value={versionEditor.input.effectiveFrom}
                onChange={(e) =>
                  setVersionEditor((s) =>
                    s ? { ...s, input: { ...s.input, effectiveFrom: e.target.value } } : s,
                  )
                }
              />
            </Field>
            <Field label="生效终点" hint="留空 = 一直有效">
              <input
                type="datetime-local"
                className={control}
                value={versionEditor.input.effectiveTo ?? ''}
                onChange={(e) =>
                  setVersionEditor((s) =>
                    s ? { ...s, input: { ...s.input, effectiveTo: e.target.value || null } } : s,
                  )
                }
              />
            </Field>
            <Field label="备注">
              <textarea
                className={control}
                rows={2}
                value={versionEditor.input.note ?? ''}
                onChange={(e) =>
                  setVersionEditor((s) =>
                    s ? { ...s, input: { ...s.input, note: e.target.value } } : s,
                  )
                }
              />
            </Field>
            <Button onClick={() => void submitVersionEditor()}>保存草稿</Button>
          </div>
        </Editor>
      )}

      {closing && (
        <Editor
          title={`提前停用「${closing.versionNo}」`}
          description={closeHint(closing)}
          close={() => setClosing(null)}
        >
          <div className="space-y-3">
            <Field label="新的生效终点" hint="必须落在当前时刻之后，且不能比原来的终点更晚">
              <input
                type="datetime-local"
                className={control}
                value={closeAt}
                onChange={(e) => setCloseAt(e.target.value)}
              />
            </Field>
            <Button onClick={() => void submitClose()}>确认停用</Button>
          </div>
        </Editor>
      )}
    </div>
  );
}
