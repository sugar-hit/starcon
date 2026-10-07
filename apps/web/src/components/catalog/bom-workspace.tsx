'use client';

import { useCallback, useEffect, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import {
  AlertTriangle,
  Boxes,
  Calculator,
  Check,
  ClipboardList,
  Clock,
  Copy,
  Factory,
  GitBranch,
  Layers,
  Loader2,
  Plus,
  RefreshCw,
  Search,
  Trash2,
  Warehouse,
} from 'lucide-react';
import type {
  BomDetail,
  BomEffectiveView,
  BomInput,
  BomOverview,
  BomPage,
  BomRequirement,
  BomRow,
  BomUsagePage,
  BomVersionDetail,
  BomExplosionRun,
  BomExplosionRunPage,
  BomVersionInput,
  BomVersionRow,
  WarehouseRef,
} from '../../../../../packages/api-contracts/src/catalog';
import { Editor, Field, control } from '../common/fields';
import { useIdentity } from '../iam/identity-shell';
import {
  BOM_STATUSES,
  SCRAP_UNIT_HINT,
  VERSION_STATUSES,
  type ComponentDraft,
  type ExplosionFilter,
  bomStatusLabel,
  bomStatusTone,
  canCloseVersion,
  canEditVersion,
  closeHint,
  closeToProblem,
  DEFAULT_MAX_DEPTH,
  closeVersion,
  codeProblem,
  componentSummary,
  componentsProblem,
  copyVersion,
  createVersion,
  deleteBom,
  deleteVersion,
  effectiveLabel,
  emptyBomInput,
  emptyComponentDraft,
  emptyExplosionFilter,
  emptyVersionInput,
  errorText,
  explodeBom,
  explosionProblem,
  explosionStatusLabel,
  explosionStatusTone,
  fetchExplosionRun,
  fetchExplosionRuns,
  fetchWarehouses,
  gapText,
  indentOf,
  lineRoleLabel,
  lineRoleTone,
  maxDepthProblem,
  runSummary,
  fetchBom,
  fetchBomOverview,
  fetchBoms,
  fetchEffectiveVersion,
  fetchRequirement,
  fetchUsage,
  fetchVersion,
  fetchVersions,
  instantToLocalInput,
  localInputToInstant,
  outputQuantityProblem,
  publishVersion,
  rangeOrderProblem,
  rangeText,
  replaceComponents,
  requiredText,
  saveBom,
  setBomStatus,
  statusLabel,
  statusTone,
  toComponentDraft,
  toComponentInput,
  toVersionInput,
  updateVersion,
  usageEditableLabel,
  versionNameProblem,
  versionNoProblem,
} from './bom-model';
import { emptySkuFilters, fetchSkuPage } from './sku-list-model';

const PAGE_SIZE = 20;

/**
 * 3.5.2.1 BOM 版本工作区。
 *
 * <p>页面按委外加工 / 生产型卖家的真实工作顺序排：
 *
 * <ul>
 *   <li><b>BOM</b> —— 一行一个物料清单，**带成品 SKU**（虚拟套装没有成品，因为它不占 SKU 行）。
 *       BOM 产出实物成品，08 组装单按它领料、06 按它提加工采购建议。
 *   <li><b>版本</b> —— 草稿可以随便改，<b>发布后整版冻结</b>，连组件的
 *       <em>单位用量与损耗率</em>一起冻结（08 已经按当时的数领过料了）。唯一例外是「提前停用本版」。
 *   <li><b>组件清单</b> —— 每行两个 BOM 独有的业务量：<b>单位用量</b>（每 1 个成品要多少）
 *       与<b>损耗率</b>（百分数，5 = 5%）。损耗率填错是静默的，页面上把单位写死在 label 上。
 *   <li><b>需求量</b> —— 本模块算<b>单层</b>精确需求量，<b>不取整</b>；多级展开是 3.5.2.2。
 *   <li><b>倒排</b> —— 「这个料被哪些 BOM 用着」。改用量之前必须先看这个方向。
 * </ul>
 */
export function BomWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('catalog.spu.update');

  const [overview, setOverview] = useState<BomOverview | null>(null);
  const [page, setPage] = useState<BomPage | null>(null);
  const [detail, setDetail] = useState<BomDetail | null>(null);
  const [effective, setEffective] = useState<BomEffectiveView | null>(null);
  const [versions, setVersions] = useState<BomVersionRow[]>([]);
  const [versionDetail, setVersionDetail] = useState<BomVersionDetail | null>(null);
  const [skus, setSkus] = useState<{ id: string; code: string; name: string }[]>([]);

  const [usage, setUsage] = useState<BomUsagePage | null>(null);
  const [usageSkuId, setUsageSkuId] = useState('');
  const [usageOnlyEffective, setUsageOnlyEffective] = useState(false);

  const [requirement, setRequirement] = useState<BomRequirement | null>(null);
  const [outputQuantity, setOutputQuantity] = useState('100');

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [actionError, setActionError] = useState('');

  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [pageIndex, setPageIndex] = useState(0);
  const [probeAt, setProbeAt] = useState('');

  const [bomEditor, setBomEditor] = useState<{ id: string | null; input: BomInput } | null>(null);
  const [versionEditor, setVersionEditor] = useState<{
    bomId: string;
    editingId: string | null;
    input: BomVersionInput;
  } | null>(null);
  const [closing, setClosing] = useState<BomVersionRow | null>(null);
  const [closeAt, setCloseAt] = useState('');
  const [copying, setCopying] = useState<BomVersionRow | null>(null);
  const [copyVersionNo, setCopyVersionNo] = useState('');
  const [componentDraft, setComponentDraft] = useState<ComponentDraft[]>([]);

  // 3.5.2.2 展开工作区
  const [explosion, setExplosion] = useState<ExplosionFilter>(emptyExplosionFilter());
  const [explosionRun, setExplosionRun] = useState<BomExplosionRun | null>(null);
  const [explosionHistory, setExplosionHistory] = useState<BomExplosionRunPage | null>(null);
  const [explosionBusy, setExplosionBusy] = useState(false);
  const [explosionError, setExplosionError] = useState('');
  const [warehouses, setWarehouses] = useState<WarehouseRef[]>([]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [o, p] = await Promise.all([
        fetchBomOverview(),
        fetchBoms({ keyword, status, page: pageIndex, size: PAGE_SIZE }),
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

  // SKU 下拉：BOM 的成品与组件都必须指向真实存在的 SKU，这里一次取一页供选择。
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

  // 仓库下拉：按仓库过滤可用量与在途（缺口只认你要的那个仓）。
  useEffect(() => {
    void (async () => {
      try {
        setWarehouses(await fetchWarehouses());
      } catch {
        setWarehouses([]);
      }
    })();
  }, []);

  /**
   * 执行一次展开。
   *
   * <p>错误态要把后端的中文原因原样显示：循环引用那条消息里点名了绕回路径
   *（成品A → 半成品B → 成品A）与所在层级，运营要靠它知道改哪一行组件。
   * 包一层「展开失败」等于把可执行的信息丢掉。
   */
  const runExplosion = useCallback(async () => {
    setExplosionBusy(true);
    setExplosionError('');
    try {
      const run = await explodeBom(explosion);
      setExplosionRun(run);
      setExplosionHistory(await fetchExplosionRuns({ bomId: run.bomId, size: 20 }));
    } catch (e) {
      setExplosionRun(null);
      setExplosionError(errorText(e));
    } finally {
      setExplosionBusy(false);
    }
  }, [explosion]);

  const openExplosionHistory = useCallback(async (id: string) => {
    setExplosionBusy(true);
    setExplosionError('');
    try {
      setExplosionRun(await fetchExplosionRun(id));
    } catch (e) {
      setExplosionError(errorText(e));
    } finally {
      setExplosionBusy(false);
    }
  }, []);

  const probeEffective = useCallback(async (bomId: string, at: string) => {
    try {
      setEffective(await fetchEffectiveVersion(bomId, at || undefined));
    } catch (e) {
      setActionError(errorText(e));
    }
  }, []);

  const openBom = useCallback(
    async (id: string) => {
      setActionError('');
      setNotice('');
      setVersionDetail(null);
      setRequirement(null);
      try {
        const d = await fetchBom(id);
        setDetail(d);
        const list = await fetchVersions({
          keyword: null,
          status: null,
          bomId: id,
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
    setRequirement(null);
    try {
      const d = await fetchVersion(id);
      setVersionDetail(d);
      setComponentDraft(d.components.map(toComponentDraft));
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

  async function submitBomEditor() {
    if (!bomEditor) return;
    const problem =
      codeProblem(bomEditor.input.code) ??
      (bomEditor.input.outputSkuId ? null : '成品 SKU 不能为空：没有成品就没有物料清单');
    if (problem) {
      setActionError(problem);
      return;
    }
    const id = bomEditor.id;
    const input = bomEditor.input;
    await guard(async () => {
      const saved = await saveBom(id, input);
      setBomEditor(null);
      if (!id) await openBom(saved.id);
    }, id ? 'BOM 已更新（编码与成品建后不可改）' : 'BOM 已建');
  }

  async function submitVersionEditor() {
    if (!versionEditor) return;
    const { bomId, editingId, input } = versionEditor;
    const problem =
      versionNoProblem(input.versionNo) ??
      versionNameProblem(input.name) ??
      rangeOrderProblem(input.effectiveFrom, input.effectiveTo ?? '');
    if (problem) {
      setActionError(problem);
      return;
    }
    const body: BomVersionInput = {
      ...input,
      effectiveFrom: localInputToInstant(input.effectiveFrom),
      effectiveTo: input.effectiveTo ? localInputToInstant(input.effectiveTo) : null,
    };
    await guard(async () => {
      if (editingId) {
        const saved = await updateVersion(editingId, body);
        await openBom(bomId);
        await openVersion(saved.id);
      } else {
        const saved = await createVersion(bomId, body);
        await openBom(bomId);
        await openVersion(saved.id);
      }
    }, editingId ? '草稿版本已更新' : '草稿版本已建（填完组件才能发布）');
  }

  async function submitCopy() {
    if (!copying) return;
    const problem = versionNoProblem(copyVersionNo);
    if (problem) {
      setActionError(problem);
      return;
    }
    const source = copying;
    const no = copyVersionNo;
    await guard(async () => {
      const created = await copyVersion(source.id, no);
      await openBom(source.bomId);
      await openVersion(created.id);
    }, `已复制成草稿 ${no}：单位用量与损耗率都带过来了，生效起点默认「此刻」`);
  }

  async function submitComponents() {
    if (!versionDetail) return;
    const problem = componentsProblem(componentDraft);
    if (problem) {
      setActionError(problem);
      return;
    }
    const versionId = versionDetail.version.id;
    const version = versionDetail.version.version;
    await guard(async () => {
      await replaceComponents(versionId, {
        components: componentDraft.map(toComponentInput),
        version,
      });
      await openVersion(versionId);
    }, '组件清单已保存（发布前都可以继续改）');
  }

  async function runRequirement() {
    if (!versionDetail) return;
    const problem = outputQuantityProblem(outputQuantity);
    if (problem) {
      setActionError(problem);
      return;
    }
    setActionError('');
    try {
      setRequirement(await fetchRequirement(versionDetail.version.id, outputQuantity));
    } catch (e) {
      setActionError(errorText(e));
    }
  }

  async function runUsage() {
    const problem = !usageSkuId ? '先选一个组件 SKU：倒排查的是「这个料被哪些 BOM 用着」' : null;
    if (problem) {
      setActionError(problem);
      return;
    }
    setActionError('');
    try {
      setUsage(await fetchUsage(usageSkuId, { onlyEffective: usageOnlyEffective, size: 50 }));
    } catch (e) {
      setActionError(errorText(e));
    }
  }

  if (loading && !page) {
    return (
      <div className="text-muted-foreground flex items-center gap-2 p-8 text-copy-13">
        <Loader2 className="size-4 animate-spin" />
        正在读取 BOM 列表…
      </div>
    );
  }

  if (error && !page) {
    return (
      <div className="space-y-3 p-4">
        <div className="border-destructive/40 bg-destructive/5 text-destructive flex items-start gap-2 rounded-md border px-3 py-2 text-copy-13">
          <AlertTriangle className="mt-0.5 size-4 shrink-0" />
          <span>{error}</span>
        </div>
        <Button onClick={() => void load()}>
          <RefreshCw className="mr-2 size-4" />
          重试
        </Button>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {actionError && (
        <div className="border-destructive/40 bg-destructive/5 text-destructive rounded-md border px-3 py-2 text-copy-13">
          {actionError}
        </div>
      )}
      {notice && (
        <div className="border-border bg-muted/40 rounded-md border px-3 py-2 text-copy-13">{notice}</div>
      )}

      {/* 概览：开放区间是唯一需要每天盯的数字；「已发布但没有组件」必须恒为 0 */}
      {overview && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard
            label="BOM"
            value={overview.bomCount}
            comparisonLabel={`启用 ${overview.activeCount} · 归档 ${overview.archivedCount}`}
          />
          <StatCard
            label="版本"
            value={overview.versionCount}
            comparisonLabel={`草稿 ${overview.draftCount} · 已发布 ${overview.publishedCount}`}
          />
          <StatCard
            label="此刻生效"
            value={overview.effectiveNowCount}
            comparisonLabel="同一时刻至多一个生效版本（重叠一律拒绝）"
          />
          <StatCard
            label="开放区间"
            value={overview.openEndedCount}
            comparisonLabel="已发布且永远有效；没被接替就会一直停在旧版本上"
          />
        </div>
      )}
      {overview && (
        <div className="text-muted-foreground text-copy-13 space-y-1">
          <p>{overview.note}</p>
          {overview.publishedWithoutComponentCount > 0 && (
            <p className="text-destructive font-medium">
              有 {overview.publishedWithoutComponentCount} 个已发布版本没有任何组件 —— 08 组装单按它们
              领不出任何料。发布时本应被拦住，出现非 0 说明数据被绕过应用层写进来了。
            </p>
          )}
        </div>
      )}

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)]">
        {/* ------------------------------------------------ BOM 列表 */}
        <SectionCard
          title="BOM"
          description="物料清单（成品由哪些组件组成）· 产实物成品，08 按它领料、06 按它提加工采购"
        >
          <div className="space-y-3">
            <div className="flex flex-wrap gap-2">
              <input
                className={control}
                placeholder="按 BOM 编码或成品 SKU 搜索"
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
                {BOM_STATUSES.map((s) => (
                  <option key={s.value} value={s.value}>
                    {s.label}
                  </option>
                ))}
              </select>
              {writable && (
                <Button onClick={() => setBomEditor({ id: null, input: emptyBomInput() })}>
                  <Plus className="mr-2 size-4" />
                  新建 BOM
                </Button>
              )}
            </div>

            {!page || page.items.length === 0 ? (
              <EmptyState
                icon={<Factory className="size-7" />}
                title="还没有 BOM"
                description="BOM 是生产/加工用的物料清单：一个成品由哪些组件组成、每 1 个要多少、会损耗百分之几。它和套装的区别是——套装用于销售（虚拟扣组件），BOM 产实物成品。"
              />
            ) : (
              <ul className="divide-border divide-y">
                {page.items.map((b) => (
                  <li key={b.id} className="py-2">
                    <button className="min-w-0 w-full text-left" onClick={() => void openBom(b.id)}>
                      <div className="flex items-center gap-2">
                        <span className="truncate font-medium">{b.code}</span>
                        <Badge tone={bomStatusTone(b.status)}>{bomStatusLabel(b.status)}</Badge>
                      </div>
                      <div className="text-muted-foreground text-copy-12">
                        成品 {b.outputSkuCode} {b.outputSkuName} · {b.versionCount} 个版本（草稿{' '}
                        {b.draftCount} · 已发布 {b.publishedCount}）· {effectiveLabel(b)}
                      </div>
                    </button>
                  </li>
                ))}
              </ul>
            )}

            {page && page.totalPages > 1 && (
              <div className="text-muted-foreground flex items-center justify-end gap-2 text-copy-12">
                <Button disabled={pageIndex === 0} onClick={() => setPageIndex((p) => p - 1)} size="sm">
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

        {/* ------------------------------------------------ BOM 详情与版本 */}
        <SectionCard
          title={detail ? `BOM ${detail.bom.code}` : 'BOM 详情'}
          description={
            detail
              ? `成品 ${detail.bom.outputSkuCode} ${detail.bom.outputSkuName} · ${detail.note ?? ''}`
              : '在左侧选一个 BOM：看它的版本、生效区间、组件用量与损耗率，以及此刻生效的是哪一版'
          }
        >
          {!detail ? (
            <EmptyState
              icon={<Layers className="size-7" />}
              title="选一个 BOM"
              description="这里会显示它的全部版本、每个版本的生效区间与组件用量/损耗率，以及「此刻生效的是哪一版」—— 由后端按生效区间算，不按版本号大小猜。"
            />
          ) : (
            <div className="space-y-4">
              <div className="flex flex-wrap gap-2">
                {writable && detail.bom.status === 'ACTIVE' && (
                  <Button
                    onClick={() =>
                      setVersionEditor({
                        bomId: detail.bom.id,
                        editingId: null,
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
                      setBomEditor({
                        id: detail.bom.id,
                        input: {
                          code: detail.bom.code,
                          outputSkuId: detail.bom.outputSkuId,
                          note: detail.bom.note,
                          version: detail.bom.version,
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
                        async () => {
                          await setBomStatus(
                            detail.bom.id,
                            detail.bom.status === 'ACTIVE' ? 'ARCHIVED' : 'ACTIVE',
                          );
                          await openBom(detail.bom.id);
                        },
                        detail.bom.status === 'ACTIVE'
                          ? 'BOM 已归档（历史版本照常可读，08 加工单要读）'
                          : 'BOM 已启用',
                      )
                    }
                  >
                    {detail.bom.status === 'ACTIVE' ? '归档' : '启用'}
                  </Button>
                )}
                {writable && detail.versions.length === 0 && (
                  <Button
                    variant="ghost"
                    onClick={() =>
                      void guard(
                        async () => {
                          await deleteBom(detail.bom.id, detail.bom.version);
                          setDetail(null);
                          setEffective(null);
                          setVersions([]);
                        },
                        'BOM 已删除',
                      )
                    }
                  >
                    <Trash2 className="mr-2 size-4" />
                    删除 BOM
                  </Button>
                )}
              </div>

              {/* 当前生效版本（可按任意时刻查） */}
              <div className="bg-muted/30 space-y-2 rounded-md border p-3">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-copy-13 font-medium">当前生效版本</span>
                  <span className="text-muted-foreground text-copy-12">
                    06 采购建议与 08 加工单取的就是这个口径
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
                            {c.skuCode} {c.skuName} × {String(c.unitQuantity)}
                            {c.scrapRate && Number(c.scrapRate) > 0
                              ? ` · 损耗 ${String(c.scrapRate)}%`
                              : ''}
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
                    onClick={() => void probeEffective(detail.bom.id, localInputToInstant(probeAt))}
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
                    title="这个 BOM 还没有版本"
                    description="版本才是 BOM 真正在用的东西：名称、版本号、生效区间、组件用量与损耗率都在版本上。先建一个草稿版本。"
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
                          {rangeText(v.effectiveFrom, v.effectiveTo)} · {v.componentCount} 个组件 · 每件
                          用量合计 {String(v.totalUnitQuantity)}
                          {v.scrapPercent != null && Number(v.scrapPercent) > 0
                            ? ` · 最高损耗 ${String(v.scrapPercent)}%`
                            : ' · 无损耗'}
                          {v.publishedAt
                            ? ` · ${statusLabel(v.status)}于 ${v.publishedAt.slice(0, 10)}`
                            : ''}
                        </div>
                        <div className="flex flex-wrap gap-2">
                          <Button size="sm" variant="secondary" onClick={() => void openVersion(v.id)}>
                            组件与需求量
                          </Button>
                          {writable && (
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => {
                                setCopying(v);
                                setCopyVersionNo(`${v.versionNo}-copy`);
                                setActionError('');
                              }}
                            >
                              <Copy className="mr-2 size-4" />
                              复制成新版本
                            </Button>
                          )}
                          {canEditVersion(v) && writable && (
                            <>
                              <Button
                                size="sm"
                                onClick={() =>
                                  setVersionEditor({
                                    bomId: v.bomId,
                                    editingId: v.id,
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
                                    async () => {
                                      await publishVersion(v.id);
                                      await openBom(v.bomId);
                                    },
                                    '已发布：用量与损耗率一并冻结，08 已经能按它领料了',
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
                                    async () => {
                                      await deleteVersion(v.id, v.version);
                                      await openBom(v.bomId);
                                    },
                                    '草稿版本已删除',
                                  )
                                }
                              >
                                <Trash2 className="size-4" />
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
                                setCloseAt('');
                                setActionError('');
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

      {/* ------------------------------------------------ 版本详情：组件与需求量 */}
      <SectionCard
        title={versionDetail ? `版本 ${versionDetail.version.versionNo} 的组件与需求量` : '版本组件与需求量'}
        description={
          versionDetail
            ? `${versionDetail.version.name} · ${componentSummary(versionDetail.components, versionDetail.totalUnitQuantity)}`
            : '在上面的版本列表里点「组件与需求量」：编辑单位用量与损耗率，并按成品数量试算要多少料'
        }
      >
        {!versionDetail ? (
          <EmptyState
            icon={<ClipboardList className="size-7" />}
            title="选一个版本"
            description="这里管两件事：每个组件「每 1 个成品要多少、会损耗百分之几」，以及「生产 N 个成品要多少料」。发布后这两样都冻结 —— 08 加工单已经按当时的数领过料了。"
          />
        ) : (
          <div className="space-y-4">
            <p className="text-copy-13 text-muted-foreground">{versionDetail.editHint}</p>

            {versionDetail.components.length === 0 ? (
              <EmptyState
                icon={<ClipboardList className="size-7" />}
                title="这个版本还没有组件"
                description="空组件的 BOM 算不出任何需求量：08 组装单按它领不出任何料，06 采购建议按它提不出任何需求。发布时会被拒绝。"
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-copy-13">
                  <thead className="text-muted-foreground text-copy-12">
                    <tr>
                      <th className="py-1 pr-2 font-medium">组件 SKU</th>
                      <th className="py-1 pr-2 font-medium">单位用量（每 1 个成品）</th>
                      <th className="py-1 pr-2 font-medium">损耗率 %</th>
                      <th className="py-1 pr-2 font-medium">角色</th>
                    </tr>
                  </thead>
                  <tbody className="divide-border divide-y">
                    {versionDetail.components.map((c) => (
                      <tr key={c.id}>
                        <td className="py-1 pr-2">
                          {c.skuCode} <span className="text-muted-foreground">{c.skuName}</span>
                        </td>
                        <td className="py-1 pr-2">{String(c.unitQuantity)}</td>
                        <td className="py-1 pr-2">{String(c.scrapRate)}%</td>
                        <td className="text-muted-foreground py-1 pr-2">{c.role || '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {/* 需求量：单层、不取整 */}
            <div className="bg-muted/30 space-y-2 rounded-md border p-3">
              <div className="flex flex-wrap items-end gap-2">
                <Field label="要生产多少个成品">
                  <input
                    className={control}
                    value={outputQuantity}
                    inputMode="numeric"
                    onChange={(e) => setOutputQuantity(e.target.value)}
                  />
                </Field>
                <Button onClick={() => void runRequirement()}>
                  <Calculator className="mr-2 size-4" />
                  算需求量
                </Button>
              </div>
              {requirement && requirement.versionId === versionDetail.version.id && (
                <div className="space-y-2">
                  {!requirement.singleLevel && (
                    <p className="text-destructive text-copy-13">
                      后端返回的是多级展开结果，本模块只承诺单层 —— 请以 3.5.2.2 的结果为准。
                    </p>
                  )}
                  <p className="text-copy-13">
                    成品 <span className="font-medium">{requirement.outputSkuCode}</span> ×{' '}
                    {String(requirement.outputQuantity)} 个
                  </p>
                  <div className="overflow-x-auto">
                    <table className="w-full text-left text-copy-13">
                      <thead className="text-muted-foreground text-copy-12">
                        <tr>
                          <th className="py-1 pr-2 font-medium">组件 SKU</th>
                          <th className="py-1 pr-2 font-medium">单位用量</th>
                          <th className="py-1 pr-2 font-medium">损耗率</th>
                          <th className="py-1 pr-2 font-medium">需求量</th>
                        </tr>
                      </thead>
                      <tbody className="divide-border divide-y">
                        {requirement.lines.map((l) => (
                          <tr key={l.skuId}>
                            <td className="py-1 pr-2">
                              {l.skuCode} <span className="text-muted-foreground">{l.skuName}</span>
                            </td>
                            <td className="py-1 pr-2">{String(l.unitQuantity)}</td>
                            <td className="py-1 pr-2">{String(l.scrapRate)}%</td>
                            <td className="py-1 pr-2 font-medium">{requiredText(l.requiredQuantity)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <p className="text-muted-foreground text-copy-12">{requirement.note}</p>
                </div>
              )}
              {!requirement && (
                <p className="text-muted-foreground text-copy-12">
                  需求量按「单位用量 × 成品数量 × (1 + 损耗率/100)」算，损耗率是百分数（5 = 5%）。
                  结果是精确值<b>不取整</b> —— 取整、补安全库存、判缺口是 3.5.2.2 与 06 的决定。
                </p>
              )}
            </div>
          </div>
        )}
      </SectionCard>

      {/* ------------------------------------------------ 3.5.2.2 展开工作区 */}
      <SectionCard
        title="BOM 展开：生产这些成品要哪些料、缺多少"
        description="按 BOM 版本与需求量多层递归展开，子 BOM 当作组件继续往下走；需求量、可用、在途、缺口逐层给出。展开结果是建议不是事实 —— 实际采购由 06 生成单据后成立。"
      >
        <div className="space-y-4">
          {/* 选择展开对象：优先用上面选中的版本，否则用选中的 BOM。 */}
          <div className="flex flex-wrap items-end gap-2">
            <Field
              label="展开对象"
              hint={
                versionDetail
                  ? `默认用上面选中的版本 ${versionDetail.version.versionNo}`
                  : '默认用上面选中的 BOM（后端取展开基准时刻生效的那一版）'
              }
            >
              <select
                className={control}
                value={explosion.versionId ?? ''}
                onChange={(e) =>
                  setExplosion((f) => ({
                    ...f,
                    versionId: e.target.value || null,
                    bomId: e.target.value ? null : (detail?.bom.id ?? null),
                  }))
                }
              >
                <option value="">
                  按 BOM 取生效版本
                  {detail ? `（${detail.bom.code}）` : '（先在上面选一个 BOM）'}
                </option>
                {versions.map((v) => (
                  <option key={v.id} value={v.id}>
                    版本 {v.versionNo}
                    {v.status === 'DRAFT' ? '（草稿，试算）' : ''}
                    {v.effective ? ' · 此刻生效' : ''}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="要生产多少个成品">
              <input
                className={control}
                value={explosion.outputQuantity}
                inputMode="numeric"
                onChange={(e) => setExplosion((f) => ({ ...f, outputQuantity: e.target.value }))}
              />
            </Field>
            <Field
              label="按仓库过滤"
              hint="留空 = 全仓合计。在途取的是发往该仓的货"
            >
              <select
                className={control}
                value={explosion.warehouseId ?? ''}
                onChange={(e) =>
                  setExplosion((f) => ({ ...f, warehouseId: e.target.value || null }))
                }
              >
                <option value="">全部仓库</option>
                {warehouses.map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.code} {w.name}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="展开深度上限" hint={`留空 = ${DEFAULT_MAX_DEPTH} 层`}>
              <input
                className={control}
                value={explosion.maxDepth ?? ''}
                inputMode="numeric"
                onChange={(e) =>
                  setExplosion((f) => ({ ...f, maxDepth: e.target.value || null }))
                }
              />
            </Field>
            <Field label="展开基准时刻" hint="留空 = 此刻。决定每一层子 BOM 取哪一版">
              <input
                className={control}
                value={explosion.asOf ?? ''}
                placeholder="2026-03-01T00:00:00Z"
                onChange={(e) => setExplosion((f) => ({ ...f, asOf: e.target.value || null }))}
              />
            </Field>
            <Button
              disabled={explosionBusy || explosionProblem(explosion) !== null}
              onClick={() => void runExplosion()}
            >
              {explosionBusy ? (
                <Loader2 className="mr-2 size-4 animate-spin" />
              ) : (
                <GitBranch className="mr-2 size-4" />
              )}
              展开
            </Button>
          </div>

          {maxDepthProblem(explosion.maxDepth) && (
            <p className="text-destructive text-copy-13">{maxDepthProblem(explosion.maxDepth)}</p>
          )}
          {explosionProblem(explosion) && !maxDepthProblem(explosion.maxDepth) && (
            <p className="text-muted-foreground text-copy-13">{explosionProblem(explosion)}</p>
          )}

          {/* 错误态：循环引用 / 库存服务不可用 的原因原样显示。 */}
          {explosionError && (
            <div className="border-destructive bg-destructive/5 text-destructive rounded-md border p-3 text-copy-13">
              {explosionError}
            </div>
          )}

          {!explosionRun && !explosionError && !explosionBusy && (
            <EmptyState
              icon={<GitBranch className="size-7" />}
              title="还没有展开"
              description="在上面选好展开对象与成品数量后点「展开」。子 BOM 的料会自动继续往下展开，子级的需求量按上一行的缺口递推 —— 半成品有库存时只展开缺的那部分。"
            />
          )}

          {explosionBusy && !explosionRun && (
            <p className="text-muted-foreground flex items-center gap-2 text-copy-13">
              <Loader2 className="size-4 animate-spin" />
              正在展开并向库存服务取可用量与在途…
            </p>
          )}

          {explosionRun && (
            <div className="space-y-3">
              {/* 截断必须显眼：这份清单不完整。 */}
              {explosionRun.truncated && (
                <div className="border-destructive bg-destructive/5 text-destructive rounded-md border p-3 text-copy-13">
                  <p className="font-medium">
                    这份清单不完整 —— 命中了展开深度上限（{explosionRun.maxDepth} 层），
                    仍有下级 BOM 没有展开。逐成品的采购量会偏小，请调高深度上限后重试，
                    或检查是不是把工艺步骤做成了物料清单。
                  </p>
                </div>
              )}

              <div className="flex flex-wrap items-center gap-2 text-copy-13">
                <Badge tone={explosionStatusTone(explosionRun.status)}>
                  {explosionStatusLabel(explosionRun.status)}
                </Badge>
                <span>
                  BOM <span className="font-medium">{explosionRun.bomCode}</span> 版本{' '}
                  <span className="font-medium">{explosionRun.versionNo}</span>
                </span>
                <span className="text-muted-foreground">
                  成品 {explosionRun.outputSkuCode} × {explosionRun.outputQuantity} 个
                </span>
                <span className="text-muted-foreground">
                  {explosionRun.lineCount} 行 · 采购对象 {explosionRun.purchaseLineCount} 行 · 合计缺口{' '}
                  {explosionRun.totalGap}
                </span>
                <span className="text-muted-foreground">
                  深度上限 {explosionRun.maxDepth} / 实际到达第 {explosionRun.reachedDepth} 层 · 基准时刻{' '}
                  {explosionRun.asOf}
                </span>
              </div>

              {explosionRun.lines.length === 0 ? (
                <EmptyState
                  icon={<ClipboardList className="size-7" />}
                  title="这个版本展开不出任何组件行"
                  description="版本详情里的组件清单是空的。空组件的 BOM 算不出任何需求量，08 加工单按它领不出任何料。"
                />
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-copy-13">
                    <thead className="text-muted-foreground text-copy-12">
                      <tr>
                        <th className="py-1 pr-2 font-medium">组件 SKU</th>
                        <th className="py-1 pr-2 font-medium">角色</th>
                        <th className="py-1 pr-2 font-medium">单位用量</th>
                        <th className="py-1 pr-2 font-medium">损耗率</th>
                        <th className="py-1 pr-2 font-medium">需求量</th>
                        <th className="py-1 pr-2 font-medium">可用</th>
                        <th className="py-1 pr-2 font-medium">在途</th>
                        <th className="py-1 pr-2 font-medium">缺口</th>
                        <th className="py-1 pr-2 font-medium">来源版本</th>
                      </tr>
                    </thead>
                    <tbody className="divide-border divide-y">
                      {explosionRun.lines.map((l) => (
                        <tr key={l.id} className={l.truncated ? 'text-destructive' : undefined}>
                          <td className="py-1 pr-2 whitespace-nowrap">
                            <span className="text-muted-foreground">{indentOf(l.depth)}</span>
                            {l.skuCode}{' '}
                            <span className="text-muted-foreground">{l.skuName}</span>
                          </td>
                          <td className="py-1 pr-2">
                            <Badge tone={lineRoleTone(l)}>{lineRoleLabel(l)}</Badge>
                            {l.ambiguousSubBom && (
                              <Badge tone="warning" className="ml-1">
                                子 BOM 歧义
                              </Badge>
                            )}
                          </td>
                          <td className="py-1 pr-2">{String(l.unitQuantity)}</td>
                          <td className="py-1 pr-2">{String(l.scrapRate)}%</td>
                          <td className="py-1 pr-2 font-medium">{String(l.requiredQuantity)}</td>
                          <td className="py-1 pr-2">{String(l.available)}</td>
                          <td className="py-1 pr-2">{String(l.inTransit)}</td>
                          <td className="py-1 pr-2 font-medium">{gapText(l)}</td>
                          <td className="text-muted-foreground py-1 pr-2 whitespace-nowrap">
                            {l.sourceBomCode} {l.sourceVersionNo}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}

              <div className="space-y-1">
                {explosionRun.lines
                  .filter((l) => l.truncated || l.ambiguousSubBom || !l.expanded)
                  .map((l) => (
                    <p key={l.id} className="text-muted-foreground text-copy-12">
                      · {indentOf(l.depth)}
                      {l.skuCode}：{l.note}
                    </p>
                  ))}
              </div>

              <p className="text-muted-foreground text-copy-12">{explosionRun.calculationNote}</p>
            </div>
          )}

          {/* 展开运行记录：06 的采购单引用的是某一次展开的 id。 */}
          {explosionHistory && explosionHistory.items.length > 0 && (
            <div className="border-muted space-y-2 rounded-md border p-3">
              <p className="text-copy-13 font-medium">展开记录（这份建议的凭据）</p>
              <p className="text-muted-foreground text-copy-12">
                展开结果是建议不是事实，实际采购由 06 生成单据后成立。采购单引用的是某一次展开的
                id ——「现在的 BOM × 现在的库存」随时会变，单子发出去之后就无法解释「当时为什么按这个数下单」。
              </p>
              <div className="overflow-x-auto">
                <table className="w-full text-left text-copy-13">
                  <thead className="text-muted-foreground text-copy-12">
                    <tr>
                      <th className="py-1 pr-2 font-medium">摘要</th>
                      <th className="py-1 pr-2 font-medium">基准时刻</th>
                      <th className="py-1 pr-2 font-medium">落库时刻</th>
                      <th className="py-1 pr-2 font-medium">操作</th>
                    </tr>
                  </thead>
                  <tbody className="divide-border divide-y">
                    {explosionHistory.items.map((r) => (
                      <tr key={r.id}>
                        <td className="py-1 pr-2">{runSummary(r)}</td>
                        <td className="text-muted-foreground py-1 pr-2">{r.asOf}</td>
                        <td className="text-muted-foreground py-1 pr-2">{r.createdAt}</td>
                        <td className="py-1 pr-2">
                          <Button size="sm" onClick={() => void openExplosionHistory(r.id)}>
                            查看
                          </Button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ------------------------------------------------ 倒排：谁在用这个料 */}
      <SectionCard
        title="倒排：这个料被哪些 BOM 用着"
        description="改单位用量或损耗率之前先看这个方向 —— 已发布版本的行改不了，但要知道你正在影响哪些历史"
      >
        <div className="space-y-3">
          <div className="flex flex-wrap items-end gap-2">
            <Field label="组件 SKU" hint="选一个真实存在的组件 SKU">
              <select
                className={control}
                value={usageSkuId}
                onChange={(e) => setUsageSkuId(e.target.value)}
              >
                <option value="">选择组件 SKU…</option>
                {skus.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.code} {s.name}
                  </option>
                ))}
              </select>
            </Field>
            <label className="text-copy-13 flex items-center gap-2">
              <input
                type="checkbox"
                checked={usageOnlyEffective}
                onChange={(e) => setUsageOnlyEffective(e.target.checked)}
              />
              只看此刻生效的版本
            </label>
            <Button onClick={() => void runUsage()}>
              <Search className="mr-2 size-4" />
              反查
            </Button>
          </div>

          {usage && !usage.items.length ? (
            <EmptyState
              icon={<Boxes className="size-7" />}
              title="没有任何 BOM 用这个料"
              description={
                usageOnlyEffective
                  ? '这个 SKU 出现在 BOM 组件清单里，但没有任何版本在「此刻」生效 —— 可能全是草稿，或已发布版本的区间都还没到。'
                  : '这个 SKU 还没有出现在任何 BOM 版本的组件清单里。要新增，去某个草稿版本的组件编辑器里加。'
              }
            />
          ) : (
            usage && (
              <div className="overflow-x-auto">
                <div className="text-muted-foreground text-copy-12 mb-2">
                  {usage.skuCode} {usage.skuName} · 共 {usage.total} 处
                </div>
                <table className="w-full text-left text-copy-13">
                  <thead className="text-muted-foreground text-copy-12">
                    <tr>
                      <th className="py-1 pr-2 font-medium">BOM</th>
                      <th className="py-1 pr-2 font-medium">版本</th>
                      <th className="py-1 pr-2 font-medium">单位用量</th>
                      <th className="py-1 pr-2 font-medium">损耗率</th>
                      <th className="py-1 pr-2 font-medium">生效区间</th>
                    </tr>
                  </thead>
                  <tbody className="divide-border divide-y">
                    {usage.items.map((row) => (
                      <tr key={row.versionId}>
                        <td className="py-1 pr-2">{row.bomCode}</td>
                        <td className="py-1 pr-2">
                          <div className="flex flex-wrap items-center gap-1">
                            <span>{row.versionNo}</span>
                            <Badge tone={statusTone(row.versionStatus)}>
                              {row.versionStatusLabel}
                            </Badge>
                            {row.effective && <Badge tone="success">此刻生效</Badge>}
                          </div>
                          <div className="text-muted-foreground text-copy-12">
                            {usageEditableLabel(row)}
                          </div>
                        </td>
                        <td className="py-1 pr-2">{String(row.unitQuantity)}</td>
                        <td className="py-1 pr-2">{String(row.scrapRate)}%</td>
                        <td className="text-muted-foreground py-1 pr-2">
                          {rangeText(row.effectiveFrom, row.effectiveTo)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )
          )}
        </div>
      </SectionCard>

      {/* ------------------------------------------------ 编辑器 */}
      {bomEditor && (
        <Editor
          title={bomEditor.id ? `改 BOM ${bomEditor.input.code}` : '新建 BOM'}
          description="BOM 产出实物成品，所以必须有一个成品 SKU。编码与成品建后都不可改：已发布版本算出来的需求量已经被 08 按实物领走了。"
          close={() => setBomEditor(null)}
        >
          <div className="space-y-3">
            <Field label="BOM 编码" hint="租户内唯一，建后不可改（06 采购建议与 08 加工单的稳定引用）">
              <input
                className={control}
                value={bomEditor.input.code}
                onChange={(e) =>
                  setBomEditor({ ...bomEditor, input: { ...bomEditor.input, code: e.target.value } })
                }
              />
            </Field>
            <Field
              label="成品 SKU"
              hint="BOM 产出的实物成品。虚拟套装不需要这个（套装不占 SKU 行）"
            >
              <select
                className={control}
                value={bomEditor.input.outputSkuId}
                disabled={Boolean(bomEditor.id)}
                onChange={(e) =>
                  setBomEditor({
                    ...bomEditor,
                    input: { ...bomEditor.input, outputSkuId: e.target.value },
                  })
                }
              >
                <option value="">选择成品 SKU…</option>
                {skus.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.code} {s.name}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="备注">
              <textarea
                className={control}
                rows={2}
                value={bomEditor.input.note ?? ''}
                onChange={(e) =>
                  setBomEditor({ ...bomEditor, input: { ...bomEditor.input, note: e.target.value } })
                }
              />
            </Field>
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={() => setBomEditor(null)}>
                取消
              </Button>
              <Button onClick={() => void submitBomEditor()}>保存</Button>
            </div>
          </div>
        </Editor>
      )}

      {versionEditor && (
        <Editor
          title={versionEditor.editingId ? '改草稿版本' : '新建草稿版本'}
          description="版本才是 BOM 真正在用的东西。生效区间是左闭右开的 [起点, 终点)，终点留空 = 一直有效；重叠一律拒绝，所以同一时刻至多一个生效版本。"
          close={() => setVersionEditor(null)}
        >
          <div className="space-y-3">
            <Field label="版本号" hint="同 BOM 内唯一，建后不可改">
              <input
                className={control}
                value={versionEditor.input.versionNo}
                onChange={(e) =>
                  setVersionEditor({
                    ...versionEditor,
                    input: { ...versionEditor.input, versionNo: e.target.value },
                  })
                }
              />
            </Field>
            <Field label="BOM 名称">
              <input
                className={control}
                value={versionEditor.input.name}
                onChange={(e) =>
                  setVersionEditor({
                    ...versionEditor,
                    input: { ...versionEditor.input, name: e.target.value },
                  })
                }
              />
            </Field>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="生效起点" hint="含这一刻">
                <input
                  type="datetime-local"
                  className={control}
                  value={versionEditor.input.effectiveFrom}
                  onChange={(e) =>
                    setVersionEditor({
                      ...versionEditor,
                      input: { ...versionEditor.input, effectiveFrom: e.target.value },
                    })
                  }
                />
              </Field>
              <Field label="生效终点" hint="不含这一刻；留空 = 一直有效（[起点, +∞)）">
                <input
                  type="datetime-local"
                  className={control}
                  value={versionEditor.input.effectiveTo ?? ''}
                  onChange={(e) =>
                    setVersionEditor({
                      ...versionEditor,
                      input: { ...versionEditor.input, effectiveTo: e.target.value || null },
                    })
                  }
                />
              </Field>
            </div>
            <Field label="备注">
              <textarea
                className={control}
                rows={2}
                value={versionEditor.input.note ?? ''}
                onChange={(e) =>
                  setVersionEditor({
                    ...versionEditor,
                    input: { ...versionEditor.input, note: e.target.value },
                  })
                }
              />
            </Field>
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={() => setVersionEditor(null)}>
                取消
              </Button>
              <Button onClick={() => void submitVersionEditor()}>保存草稿</Button>
            </div>
          </div>
        </Editor>
      )}

      {versionDetail && canEditVersion(versionDetail.version) && writable && (
        <SectionCard
          title="编辑组件清单（草稿）"
          description="整体保存：提交后清单就是保存的那份，不会剩「幽灵组件」。同一个 SKU 只能有一行。"
        >
          <div className="space-y-3">
            {componentDraft.map((draft, index) => (
              <div key={index} className="flex flex-wrap items-end gap-2">
                <Field label="组件 SKU">
                  <select
                    className={control}
                    value={draft.skuId}
                    onChange={(e) => {
                      const next = [...componentDraft];
                      next[index] = { ...draft, skuId: e.target.value };
                      setComponentDraft(next);
                    }}
                  >
                    <option value="">选择 SKU…</option>
                    {skus.map((s) => (
                      <option key={s.id} value={s.id}>
                        {s.code} {s.name}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="单位用量" hint="每 1 个成品需要这个组件多少">
                  <input
                    className={control}
                    value={draft.unitQuantity}
                    inputMode="decimal"
                    onChange={(e) => {
                      const next = [...componentDraft];
                      next[index] = { ...draft, unitQuantity: e.target.value };
                      setComponentDraft(next);
                    }}
                  />
                </Field>
                <Field label="损耗率 %" hint={SCRAP_UNIT_HINT}>
                  <input
                    className={control}
                    value={draft.scrapRate}
                    inputMode="decimal"
                    placeholder="留空 = 无损耗"
                    onChange={(e) => {
                      const next = [...componentDraft];
                      next[index] = { ...draft, scrapRate: e.target.value };
                      setComponentDraft(next);
                    }}
                  />
                </Field>
                <Button
                  variant="ghost"
                  onClick={() => setComponentDraft(componentDraft.filter((_, i) => i !== index))}
                >
                  <Trash2 className="size-4" />
                </Button>
              </div>
            ))}
            <div className="flex flex-wrap gap-2">
              <Button
                variant="secondary"
                onClick={() => setComponentDraft([...componentDraft, emptyComponentDraft()])}
              >
                <Plus className="mr-2 size-4" />
                加一行
              </Button>
              <Button
                onClick={() => void submitComponents()}
                disabled={versionDetail.editable === false}
              >
                保存组件清单
              </Button>
            </div>
            {componentsProblem(componentDraft) && (
              <p className="text-muted-foreground text-copy-12">
                {componentsProblem(componentDraft)}
              </p>
            )}
          </div>
        </SectionCard>
      )}

      {closing && (
        <Editor
          title={`提前停用「${closing.versionNo}」`}
          description={closeHint(closing)}
          close={() => setClosing(null)}
        >
          <div className="space-y-3">
            <Field label="生效终点" hint="必须落在当前时刻之后，且不能比原来的终点更晚">
              <input
                type="datetime-local"
                className={control}
                value={closeAt}
                onChange={(e) => setCloseAt(e.target.value)}
              />
            </Field>
            {closeToProblem(closeAt, closing.effectiveTo) && (
              <p className="text-muted-foreground text-copy-12">
                {closeToProblem(closeAt, closing.effectiveTo)}
              </p>
            )}
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={() => setClosing(null)}>
                取消
              </Button>
              <Button
                onClick={() =>
                  void guard(
                    async () => {
                      const bomId = closing.bomId;
                      await closeVersion(closing.id, localInputToInstant(closeAt));
                      setClosing(null);
                      await openBom(bomId);
                    },
                    '本版已停用（只影响未来，历史不受影响）',
                  )
                }
              >
                停用
              </Button>
            </div>
          </div>
        </Editor>
      )}

      {copying && (
        <Editor
          title={`复制「${copying.versionNo}」成新版本`}
          description="组件、单位用量与损耗率整份带走 —— 改一版最常见的方式就是复制一份再改几行。生效起点默认「此刻」，让新版本从现在起接替。"
          close={() => setCopying(null)}
        >
          <div className="space-y-3">
            <Field label="新版本号" hint="复制品是草稿：要改内容就得是草稿">
              <input
                className={control}
                value={copyVersionNo}
                onChange={(e) => setCopyVersionNo(e.target.value)}
              />
            </Field>
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={() => setCopying(null)}>
                取消
              </Button>
              <Button onClick={() => void submitCopy()}>复制</Button>
            </div>
          </div>
        </Editor>
      )}

      {detail && versions.length > 0 && (
        <p className="text-muted-foreground text-copy-12">
          状态说明：{VERSION_STATUSES.map((s) => `${s.label}——${s.hint}`).join('；')}。
          已发布版本的单位用量与损耗率一并冻结，因为 08 加工单领的料是按当时的数算的；
          要改内容请复制一个新版本并让它接替本版。
        </p>
      )}
    </div>
  );
}
