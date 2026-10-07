'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Badge, Button } from '@starcon/ui';
import {
  ArrowLeft,
  Boxes,
  GitBranch,
  ListChecks,
  Plus,
  RefreshCw,
  Trash2,
  X,
} from 'lucide-react';
import type {
  BoundAxis,
  CombinationConflict,
  ExistingCombination,
  SkuMatrixDraft,
  SkuMatrixDraftSummary,
  VariantCombinationOverview,
  VariantCombinationPanel,
} from '../../../../../packages/api-contracts/src/catalog';
import {
  checkCombinations,
  combinationSourceText,
  combinationText,
  conflictLabel,
  conflictPositionText,
  conflictTargetText,
  discardDraft,
  draftModeText,
  draftStatusText,
  emptySelectionDraft,
  errorText,
  explicitToInput,
  fetchCombinations,
  fetchDraft,
  fetchDrafts,
  fetchOverview,
  fetchPanel,
  generateDraft,
  hasConflictList,
  isConflict,
  isNotFound,
  localConflictReasons,
  matrixDraftInput,
  matrixHint,
  occurredAtProblem,
  selectionRowCount,
  syncPicks,
  timestamp,
  togglePick,
  type DraftMode,
  type ExplicitDraft,
} from './combination-model';
import { Field } from './fields';
import { useIdentity } from '../iam/identity-shell';

/**
 * 3.1.3.2 组合唯一性校验工作区。
 *
 * <p>分区按「先看清矩阵被占了多少、再决定要不要生成草稿」的顺序排：
 * 概览与治理缺口 → 单 SPU 的组合校验面板 → 生成草稿 → 冲突清单 → 已存在组合 → 草稿列表。
 *
 * <p>四条界面纪律：
 *
 * <ul>
 *   <li>**矩阵行数、已占用、可生成是三个数，必须分开画**。只画"N 个组合"会让人以为
 *       按一下就能建出 N 个 SKU，而其中一部分早就被占了——本模块还是全有或全无的。
 *   <li>**冲突逐条显示，带原因分组与落点**。「撞历史 SKU」和「撞上一份草稿」的处理
 *       方式完全不同，只写"存在重复组合"等于没写。
 *   <li>**草稿不是 SKU**。生成后 `sku` 表里一行都没有，下游 3.1.2.1 才真正落库，
 *       界面上必须把这句话写在草稿卡片上。
 *   <li>**唯一性由数据库兜底**，界面上的校验只用来提前说破：把冲突列全了，服务端与
 *       数据库仍然是最后那道关（蓝图硬要求）。
 * </ul>
 */
export function VariantCombinationWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('catalog.attribute.read');
  const writable = session.permissions.includes('catalog.attribute.update');

  const [overview, setOverview] = useState<VariantCombinationOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const [spuId, setSpuId] = useState('');
  const [panel, setPanel] = useState<VariantCombinationPanel | null>(null);
  const [panelBusy, setPanelBusy] = useState(false);
  const [panelMissing, setPanelMissing] = useState(false);
  const [panelError, setPanelError] = useState('');

  const [mode, setMode] = useState<DraftMode>('MATRIX');
  const [selection, setSelection] = useState(emptySelectionDraft([]));
  const [explicit, setExplicit] = useState<ExplicitDraft>({ rows: [[]], notes: '', occurredAt: '' });
  const [notes, setNotes] = useState('');
  const [occurredAt, setOccurredAt] = useState('');
  const [draftBusy, setDraftBusy] = useState(false);
  const [draftError, setDraftError] = useState('');
  const [conflicts, setConflicts] = useState<CombinationConflict[]>([]);

  const [combinations, setCombinations] = useState<ExistingCombination[]>([]);
  const [drafts, setDrafts] = useState<SkuMatrixDraftSummary[]>([]);
  const [detail, setDetail] = useState<SkuMatrixDraft | null>(null);
  const [detailBusy, setDetailBusy] = useState(false);
  const [detailError, setDetailError] = useState('');

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      setOverview(await fetchOverview());
    } catch (e) {
      setError(errorText(e, '组合校验概览加载失败'));
    } finally {
      setLoading(false);
    }
  }, [readable]);

  useEffect(() => {
    void load();
  }, [load]);

  const loadPanel = useCallback(async () => {
    if (!spuId.trim()) {
      setPanel(null);
      return;
    }
    setPanelBusy(true);
    setPanelError('');
    setPanelMissing(false);
    setConflicts([]);
    setDetail(null);
    try {
      const next = await fetchPanel(spuId.trim());
      setPanel(next);
      setPanelMissing(false);
      setSelection(emptySelectionDraft(next.axes));
      setExplicit({
        rows: [next.axes.map((axis) => ({ specAxisId: axis.specAxisId, value: '' }))],
        notes: '',
        occurredAt: '',
      });
      const [rows, draftRows] = await Promise.all([
        fetchCombinations(next.spuId),
        fetchDrafts(next.spuId),
      ]);
      setCombinations(rows);
      setDrafts(draftRows);
    } catch (e) {
      setPanel(null);
      setCombinations([]);
      setDrafts([]);
      if (isNotFound(e)) setPanelMissing(true);
      else setPanelError(errorText(e, '组合校验面板加载失败'));
    } finally {
      setPanelBusy(false);
    }
  }, [spuId]);

  const axes = panel?.axes ?? [];
  const localReasons = useMemo(
    () =>
      mode === 'EXPLICIT'
        ? localConflictReasons(
            explicit.rows
              .filter((row) => row.some((cell) => cell.specAxisId !== '' && cell.value.trim() !== ''))
              .map((row) =>
                row
                  .filter((cell) => cell.specAxisId !== '' && cell.value.trim() !== '')
                  .map((cell) => ({ specAxisId: cell.specAxisId, value: cell.value.trim() })),
              ),
            axes,
          )
        : [],
    [mode, explicit, axes],
  );

  const matrixRows = useMemo(() => selectionRowCount(axes, selection.picks), [axes, selection.picks]);

  /**
   * 预校验：只对显式提交的组合有意义。
   *
   * <p>矩阵模式下组合是服务端展开的（前端只传勾选状态），没有"待校验的组合"可提交——
   * 硬凑一条空取值去 check 只会拿到一条假冲突。矩阵模式的冲突在生成时由服务端返回。
   */
  async function runCheck() {
    if (!panel || mode !== 'EXPLICIT') return;
    setDraftBusy(true);
    setDraftError('');
    try {
      const result = await checkCombinations(panel.spuId, {
        combinations: explicitToInput({ ...explicit, notes, occurredAt }).combinations ?? [],
      });
      setConflicts(result.conflicts);
    } catch (e) {
      setConflicts([]);
      setDraftError(errorText(e, '预校验失败'));
    } finally {
      setDraftBusy(false);
    }
  }

  async function submitDraft() {
    if (!panel) return;
    setDraftBusy(true);
    setDraftError('');
    try {
      const input =
        mode === 'MATRIX'
          ? matrixDraftInput({ ...selection, notes, occurredAt })
          : explicitToInput({ ...explicit, notes, occurredAt });
      await generateDraft(panel.spuId, input);
      setConflicts([]);
      setSelection(emptySelectionDraft(axes));
      await loadPanel();
      await load();
    } catch (e) {
      // 409 带结构化冲突清单：必须按条渲染，不能只显示一句 message。
      if (hasConflictList(e)) setConflicts(conflictsOf(e));
      setDraftError(
        isConflict(e)
          ? '组合校验未通过，草稿没有生成。下面按条列出冲突，改完再提交。'
          : errorText(e, '生成草稿失败'),
      );
    } finally {
      setDraftBusy(false);
    }
  }

  async function openDraft(draft: SkuMatrixDraftSummary) {
    setDetailBusy(true);
    setDetailError('');
    try {
      setDetail(await fetchDraft(panel!.spuId, draft.id));
    } catch (e) {
      setDetail(null);
      setDetailError(errorText(e, '草稿明细加载失败'));
    } finally {
      setDetailBusy(false);
    }
  }

  async function removeDraft(draft: SkuMatrixDraftSummary) {
    if (!panel) return;
    setDetailBusy(true);
    setDetailError('');
    try {
      await discardDraft(panel.spuId, draft.id);
      setDetail(null);
      await loadPanel();
      await load();
    } catch (e) {
      setDetailError(errorText(e, '草稿作废失败'));
    } finally {
      setDetailBusy(false);
    }
  }

  if (!readable)
    return (
      <Section frame>
        <p className="rounded-lg border p-6 text-sm">
          当前角色没有商品属性查看权限，请联系租户管理员。
        </p>
      </Section>
    );

  if (loading)
    return (
      <Section frame>
        <p role="status" className="text-muted-foreground text-sm">
          正在加载组合校验概览…
        </p>
      </Section>
    );

  if (error || !overview)
    return (
      <Section frame>
        <BackLink />
        <p role="alert" className="text-destructive text-sm">
          {error || '组合校验概览加载失败'}
        </p>
        <Button variant="outline" size="sm" className="mt-3" onClick={() => void load()}>
          <RefreshCw className="h-4 w-4" />
          重试
        </Button>
      </Section>
    );

  return (
    <Section frame>
      <BackLink />

      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-xl font-semibold">组合唯一性校验</h2>
          <p className="text-muted-foreground mt-1 text-sm">
            保证同一 SPU 下不存在重复的规格值组合（如「红色 / 大」出现两次），并在生成 SKU
            矩阵前拦住非法组合。校验通过后生成的是<b>待建 SKU 草稿</b>——组合先占住位，
            真正落 SKU 由下游 SKU 列表模块执行。
          </p>
        </div>
        <Button variant="ghost" size="sm" onClick={() => void load()}>
          <RefreshCw className="h-4 w-4" />
          刷新
        </Button>
      </header>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Stat
          label="已绑定轴的 SPU"
          value={overview.axisBoundSpuCount}
          hint="组合唯一性的作用域"
        />
        <Stat label="组合占用" value={overview.reservedCombinationCount} hint="草稿已占位" />
        <Stat
          label="矩阵草稿"
          value={`${overview.pendingDraftCount} / ${overview.draftCount}`}
          hint="待兑现 / 全部"
        />
        <Stat label="已有 SKU 组合" value={overview.blockedSpuCount} hint="会被比对的历史数据" />
      </div>

      <div className="border-border bg-muted/30 rounded-md border p-3 text-sm">
        <p className="flex items-center gap-2 font-medium">
          <ListChecks className="h-4 w-4" />
          唯一性由数据库兜底
        </p>
        <p className="text-muted-foreground mt-1 text-xs">{overview.note}</p>
        <p className="text-muted-foreground mt-1 text-xs">
          应用层校验只用来提前说破：真正拦住重复的是组合台账上的唯一索引，
          绕过页面直接写库也会被数据库拒掉。
        </p>
      </div>

      {/* 选择 SPU */}
      <Card
        title="选择 SPU"
        hint="组合是「一个 SPU 的所有已绑定规格轴各取一个值」，所以校验必须落到具体 SPU 上。"
      >
        <div className="flex flex-wrap items-end gap-2">
          <div className="min-w-64 flex-1">
            <Field label="SPU ID" hint="从商品列表进入时会自动带上；也可以直接粘贴 SPU 的 UUID。">
              <input
                className="border-control-border w-full rounded-md border px-3 py-2 text-sm"
                value={spuId}
                onChange={(e) => setSpuId(e.target.value)}
                placeholder="00000000-0000-0000-0000-000000000000"
              />
            </Field>
          </div>
          <Button size="sm" disabled={panelBusy || !spuId.trim()} onClick={() => void loadPanel()}>
            <GitBranch className="h-4 w-4" />
            载入面板
          </Button>
        </div>
        {panelMissing && (
          <p role="alert" className="text-destructive mt-3 text-sm">
            找不到这个 SPU，或当前角色没有它的访问权限。
          </p>
        )}
        {panelError && (
          <p role="alert" className="text-destructive mt-3 text-sm">
            {panelError}
          </p>
        )}
      </Card>

      {panelBusy && (
        <p role="status" className="text-muted-foreground text-sm">
          正在加载组合校验面板…
        </p>
      )}

      {panel && (
        <>
          {/* 组合校验面板 */}
          <Card
            title={`组合校验 · ${panel.spuName}`}
            hint="矩阵行数只是「按当前取值能展开多少条」，不等于还能生成多少条——已占用的组合会让生成整份失败。"
          >
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <Stat label="矩阵行数" value={panel.matrixRowCount} />
              <Stat
                label="矩阵内已占用"
                value={panel.conflictMatrixCount}
                hint="草稿占用 + 已有 SKU"
              />
              <Stat label="还可生成" value={panel.availableMatrixCount} hint="单次上限内" />
              <Stat label="上限" value={panel.matrixCap} hint="超出即拒绝并报真实行数" />
            </div>
            <p className="text-muted-foreground mt-3 text-xs">{matrixHint(panel)}</p>

            {axes.length === 0 ? (
              <p className="text-muted-foreground mt-3 text-sm">
                这个 SPU 还没有绑定任何规格轴。请先到
                <Link href="/products/sku/spec-axis" className="underline">
                  规格轴定义
                </Link>{' '}
                绑定轴并登记取值，再回来做组合校验。
              </p>
            ) : (
              <ul className="mt-3 space-y-1">
                {axes.map((axis) => (
                  <li key={axis.specAxisId} className="text-muted-foreground text-sm">
                    <span className="text-foreground">{axis.name}</span>（{axis.code}）·{' '}
                    {axis.options.filter((option) => option.enabled).length} 个启用取值 / 共{' '}
                    {axis.options.length} 个
                    {!axis.enabled && ' · 已停用'}
                  </li>
                ))}
              </ul>
            )}
          </Card>

          {/* 生成草稿 */}
          <Card
            title="生成待建 SKU 草稿"
            hint="草稿会让组合在台账上占住位，避免别人同时生成同一批；作废草稿即可释放。真正落 SKU 由下游执行，这里不建 SKU 行。"
          >
            <div className="flex gap-2">
              <Button
                variant={mode === 'MATRIX' ? 'default' : 'outline'}
                size="sm"
                onClick={() => setMode('MATRIX')}
              >
                <Boxes className="h-4 w-4" />
                矩阵展开
              </Button>
              <Button
                variant={mode === 'EXPLICIT' ? 'default' : 'outline'}
                size="sm"
                onClick={() => setMode('EXPLICIT')}
              >
                <ListChecks className="h-4 w-4" />
                显式提交组合
              </Button>
            </div>

            {mode === 'MATRIX' ? (
              <div className="mt-3 space-y-3">
                <p className="text-muted-foreground text-sm">
                  按各轴的启用取值展开笛卡尔积。下面勾选后点「预校验」或直接生成。
                </p>
                {axes.map((axis) => (
                  <AxisPicker
                    key={axis.specAxisId}
                    axis={axis}
                    picks={selection.picks[axis.specAxisId] ?? []}
                    onToggle={(value) =>
                      setSelection((current) => {
                        const next = togglePick(current, axis.specAxisId, value);
                        return { ...next, picks: syncPicks(next.picks, axes) };
                      })
                    }
                  />
                ))}
                <p className="text-sm">
                  勾选后可展开{' '}
                  <span className="font-semibold tabular-nums">
                    {matrixRows.toLocaleString('zh-CN')}
                  </span>{' '}
                  条组合
                  {panel.matrixTooLarge && '（已超过单次上限，生成会被拒绝）'}
                </p>
              </div>
            ) : (
              <div className="mt-3 space-y-2">
                <p className="text-muted-foreground text-sm">
                  一行一条组合，每条轴各填一个取值。缺轴、混用别的 SPU 的轴、取值不属于该轴
                  都会被拦下。
                </p>
                {explicit.rows.map((row, rowIndex) => (
                  <div key={rowIndex} className="flex flex-wrap items-center gap-2">
                    <span className="text-muted-foreground w-14 text-xs">第 {rowIndex + 1} 条</span>
                    {axes.map((axis) => (
                      <select
                        key={axis.specAxisId}
                        className="border-control-border rounded-md border px-2 py-1 text-sm"
                        value={
                          row.find((cell) => cell.specAxisId === axis.specAxisId)?.value ?? ''
                        }
                        onChange={(e) =>
                          setExplicit((current) => {
                            const rows = current.rows.map((candidate, index) =>
                              index !== rowIndex
                                ? candidate
                                : candidate.map((cell) =>
                                    cell.specAxisId === axis.specAxisId
                                      ? { ...cell, value: e.target.value }
                                      : cell,
                                  ),
                            );
                            return { ...current, rows };
                          })
                        }
                      >
                        <option value="">（未选择）</option>
                        {axis.options.map((option) => (
                          <option key={option.value} value={option.value}>
                            {option.value}
                            {option.enabled ? '' : '（已停用）'}
                          </option>
                        ))}
                      </select>
                    ))}
                    <Button
                      variant="ghost"
                      size="sm"
                      disabled={explicit.rows.length === 1}
                      onClick={() =>
                        setExplicit((current) => ({
                          ...current,
                          rows: current.rows.filter((_, index) => index !== rowIndex),
                        }))
                      }
                    >
                      <Trash2 className="h-4 w-4" />
                      删除
                    </Button>
                  </div>
                ))}
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() =>
                    setExplicit((current) => ({
                      ...current,
                      rows: [
                        ...current.rows,
                        axes.map((axis) => ({ specAxisId: axis.specAxisId, value: '' })),
                      ],
                    }))
                  }
                >
                  <Plus className="h-4 w-4" />
                  增加一条组合
                </Button>
              </div>
            )}

            {mode === 'EXPLICIT' && localReasons.length > 0 && (
              <ul className="border-warning/30 bg-warning/5 text-warning mt-3 rounded-md border p-3 text-xs">
                {localReasons.map((reason) => (
                  <li key={reason}>{reason}</li>
                ))}
              </ul>
            )}

            <div className="mt-4 grid gap-2 sm:grid-cols-2">
              <Field label="备注" hint="留空则自动记下这次草稿的生成口径。">
                <input
                  className="border-control-border w-full rounded-md border px-3 py-2 text-sm"
                  value={notes}
                  onChange={(e) => setNotes(e.target.value)}
                />
              </Field>
              <Field
                label="业务时间"
                hint="业务时间与系统写入时间分开存；留空表示用当前时间。"
              >
                <input
                  className="border-control-border w-full rounded-md border px-3 py-2 text-sm"
                  value={occurredAt}
                  onChange={(e) => setOccurredAt(e.target.value)}
                  placeholder="2026-01-02T03:04:05Z"
                />
              </Field>
            </div>

            {occurredAtProblem(occurredAt) && (
              <p role="alert" className="text-destructive mt-2 text-xs">
                {occurredAtProblem(occurredAt)}
              </p>
            )}

            <div className="mt-4 flex flex-wrap gap-2">
              <Button
                variant="outline"
                size="sm"
                disabled={draftBusy || axes.length === 0 || mode !== 'EXPLICIT'}
                title={
                  mode === 'EXPLICIT'
                    ? undefined
                    : '矩阵模式的组合由服务端展开，没有可预校验的组合；冲突会在生成时返回'
                }
                onClick={() => void runCheck()}
              >
                <ListChecks className="h-4 w-4" />
                预校验（不写库）
              </Button>
              {writable && (
                <Button
                  size="sm"
                  disabled={
                    draftBusy ||
                    axes.length === 0 ||
                    occurredAtProblem(occurredAt) !== null ||
                    (mode === 'MATRIX' && matrixRows === 0) ||
                    (mode === 'EXPLICIT' && localReasons.length > 0)
                  }
                  onClick={() => void submitDraft()}
                >
                  <Boxes className="h-4 w-4" />
                  生成草稿
                </Button>
              )}
            </div>

            {!writable && (
              <p className="text-muted-foreground mt-2 text-xs">
                当前角色只能预校验，不能生成草稿（需要商品属性修改权限）。
              </p>
            )}

            {draftError && (
              <p role="alert" className="text-destructive mt-3 text-sm">
                {draftError}
              </p>
            )}
          </Card>

          {/* 冲突清单 */}
          {conflicts.length > 0 && (
            <Card
              title={`冲突清单 · ${conflicts.length} 条`}
              hint="每条都带原因、出现在第几条、以及撞的是谁。改法不同：撞已有 SKU 要先处理那个 SKU，撞草稿只要作废那份草稿。"
            >
              <ul className="space-y-2">
                {conflicts.map((conflict, index) => (
                  <li
                    key={`${conflict.reasonCode}-${conflict.signature}-${index}`}
                    className="border-control-border bg-muted/30 rounded-md border p-3"
                  >
                    <p className="flex flex-wrap items-center gap-2 text-sm font-medium">
                      <Badge tone="warning">{conflictLabel(conflict)}</Badge>
                      {conflict.signature && (
                        <span className="text-muted-foreground font-mono text-xs">
                          {conflict.signature}
                        </span>
                      )}
                    </p>
                    <p className="mt-1 text-sm">{conflict.message}</p>
                    <p className="text-muted-foreground mt-1 text-xs">
                      {conflictPositionText(conflict) && <>落点：{conflictPositionText(conflict)} · </>}
                      {conflictTargetText(conflict) && <>撞的是：{conflictTargetText(conflict)} · </>}
                      {conflict.values && conflict.values.length > 0 && (
                        <>组合：{combinationText(conflict.values)}</>
                      )}
                    </p>
                  </li>
                ))}
              </ul>
            </Card>
          )}

          {/* 已存在组合 */}
          <Card
            title={`已存在组合 · ${combinations.length} 条`}
            hint="同一 SPU 下不允许出现重复的规格值组合。草稿占用与已存在的 SKU 合并显示，两者都会被下一批校验拦下。"
          >
            {combinations.length === 0 ? (
              <p className="text-muted-foreground text-sm">
                这个 SPU 还没有任何已存在的组合，草稿生成不会撞车。
              </p>
            ) : (
              <ul className="space-y-1">
                {combinations.map((combination) => (
                  <li key={`${combination.source}-${combination.signature}-${combination.skuId}`} className="text-sm">
                    <span className="font-mono text-xs">{combination.signature}</span>
                    <span className="text-muted-foreground"> · {combinationText(combination.values)}</span>
                    <Badge tone={combination.source === 'RESERVED' ? 'info' : 'neutral'}>
                      {combinationSourceText(combination)}
                    </Badge>
                    {combination.skuCode && (
                      <span className="text-muted-foreground"> · {combination.skuCode}</span>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </Card>

          {/* 草稿列表 */}
          <Card
            title={`矩阵草稿 · ${drafts.length} 份`}
            hint="草稿是「待建 SKU」的占位凭据：组合已占住位，但 sku 表里还没有对应行。"
          >
            {drafts.length === 0 ? (
              <p className="text-muted-foreground text-sm">
                还没有任何草稿。勾选取值后生成一份，组合就会占住位，避免重复生成。
              </p>
            ) : (
              <ul className="space-y-2">
                {drafts.map((draft) => (
                  <li
                    key={draft.id}
                    className="border-control-border bg-muted/30 flex flex-wrap items-center gap-3 rounded-md border p-3"
                  >
                    <div className="min-w-0 flex-1">
                      <p className="flex flex-wrap items-center gap-2 text-sm font-medium">
                        {draftModeText(draft)} · {draft.combinationCount} 条组合
                        <Badge tone={draft.status === 'PENDING' ? 'success' : 'neutral'}>
                          {draft.status === 'PENDING' ? '待兑现' : '已作废'}
                        </Badge>
                      </p>
                      <p className="text-muted-foreground mt-1 text-xs">{draftStatusText(draft)}</p>
                      {draft.notes && (
                        <p className="text-muted-foreground mt-0.5 text-xs">备注：{draft.notes}</p>
                      )}
                      <p className="text-muted-foreground mt-0.5 text-xs">
                        业务时间 {timestamp(draft.occurredAt)} · 系统写入 {timestamp(draft.createdAt)}
                      </p>
                    </div>
                    <Button variant="outline" size="sm" onClick={() => void openDraft(draft)}>
                      查看组合
                    </Button>
                    {writable && draft.status === 'PENDING' && (
                      <Button
                        variant="ghost"
                        size="sm"
                        disabled={detailBusy}
                        onClick={() => void removeDraft(draft)}
                      >
                        <Trash2 className="h-4 w-4" />
                        作废并释放
                      </Button>
                    )}
                  </li>
                ))}
              </ul>
            )}
            {detailError && (
              <p role="alert" className="text-destructive mt-3 text-sm">
                {detailError}
              </p>
            )}
          </Card>

          {/* 草稿明细 */}
          {detail && (
            <Card
              title={`草稿组合明细 · ${detail.combinationCount} 条`}
              hint="这些组合已经在台账上占住位；作废草稿会整份释放。"
            >
              <ul className="space-y-1">
                {detail.combinations.map((combination) => (
                  <li key={combination.signature} className="text-sm">
                    <span className="font-mono text-xs">{combination.signature}</span>
                    <span className="text-muted-foreground"> · {combinationText(combination.values)}</span>
                  </li>
                ))}
              </ul>
              <Button
                variant="ghost"
                size="sm"
                className="mt-3"
                onClick={() => setDetail(null)}
              >
                <X className="h-4 w-4" />
                收起
              </Button>
            </Card>
          )}
        </>
      )}
    </Section>
  );
}

/** 从 409 响应里取结构化冲突清单（与 model 同源，缺字段时退化成空数组）。 */
function conflictsOf(error: unknown): CombinationConflict[] {
  const body = (error as { body?: { conflicts?: unknown } } | null)?.body;
  return Array.isArray(body?.conflicts) ? (body?.conflicts as CombinationConflict[]) : [];
}

function AxisPicker({
  axis,
  picks,
  onToggle,
}: {
  axis: BoundAxis;
  picks: string[];
  onToggle: (value: string) => void;
}) {
  return (
    <div>
      <p className="text-sm font-medium">
        {axis.name}（{axis.code}）
        {!axis.enabled && <span className="text-muted-foreground"> · 已停用</span>}
      </p>
      <div className="mt-1 flex flex-wrap gap-1.5">
        {axis.options.length === 0 && (
          <span className="text-muted-foreground text-xs">
            这条轴还没有任何取值——先到规格轴定义里登记。
          </span>
        )}
        {axis.options.map((option) => {
          const active = picks.includes(option.value);
          return (
            <button
              key={option.value}
              type="button"
              disabled={!option.enabled || !axis.enabled}
              onClick={() => onToggle(option.value)}
              className={[
                'rounded-md border px-2.5 py-1 text-sm transition',
                active
                  ? 'border-primary bg-primary/10 text-foreground'
                  : 'border-control-border hover:bg-accent',
                !option.enabled && 'text-muted-foreground line-through opacity-60',
              ].join(' ')}
            >
              {option.value}
            </button>
          );
        })}
      </div>
    </div>
  );
}

function Stat({ label, value, hint }: { label: string; value: number | string; hint?: string }) {
  return (
    <div className="bg-card rounded-lg border p-3">
      <p className="text-muted-foreground text-xs">{label}</p>
      <p className="mt-1 text-2xl font-semibold tabular-nums">{value}</p>
      {hint && <p className="text-muted-foreground mt-1 text-xs">{hint}</p>}
    </div>
  );
}

function Section({ children, frame = false }: { children: React.ReactNode; frame?: boolean }) {
  return <section className={frame ? 'space-y-4' : undefined}>{children}</section>;
}

function BackLink() {
  return (
    <Link
      href="/products/sku"
      className="text-muted-foreground hover:text-foreground inline-flex items-center gap-1.5 text-sm"
    >
      <ArrowLeft className="h-4 w-4" />
      返回 SKU 列表
    </Link>
  );
}

function Card({
  title,
  hint,
  children,
}: {
  title: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="bg-card rounded-lg border p-4">
      <h3 className="font-medium">{title}</h3>
      {hint && <p className="text-muted-foreground mt-1 text-xs">{hint}</p>}
      <div className="mt-3">{children}</div>
    </div>
  );
}