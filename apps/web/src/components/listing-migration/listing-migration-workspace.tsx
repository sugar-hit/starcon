'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard } from '@starcon/ui';
import {
  ArrowRight,
  CheckCircle2,
  Loader2,
  RefreshCw,
  ShieldCheck,
  TriangleAlert,
} from 'lucide-react';
import {
  batchStateLabel,
  buildExecuteRequest,
  buildPreviewRequest,
  categoryOptionLabel,
  dimensionReport,
  EMPTY_DRAFT,
  emptyItemsMessage,
  emptyMigratedMessage,
  emptyPreviewMessage,
  emptyRisksMessage,
  executeMigration,
  fetchDuplicateRisks,
  fetchFilterOptions,
  fetchMigrationBatches,
  fetchMigrationCapabilities,
  fetchMigrationItems,
  fetchMigrated,
  fetchShops,
  itemStateLabel,
  itemStateTone,
  MAX_RISK_OPTIONS,
  previewMigration,
  riskLevelLabel,
  riskLevelTone,
  riskScoreLabel,
  ruleLabel,
  similarityLabel,
  SORT_OPTIONS,
  targetDraftBadge,
  targetShopOptions,
  UNKNOWN_POLICY_OPTIONS,
  UNKNOWN_RISK_OPTIONS,
  type ListingMigrationBatchRow,
  type ListingMigrationCapabilities,
  type ListingMigrationDuplicateRow,
  type ListingMigrationItemRow,
  type ListingMigrationMigratedRow,
  type ListingMigrationPreview,
  type MigrationDraft,
  type ShopView,
} from './listing-migration-model';

const SELECT_CLASS = 'border-control-border bg-card h-9 rounded-md border px-2 text-copy-13';

/**
 * 4.4.2.2 批量搬迁 工作区。
 *
 * 蓝图：「批量把一批 listing 从一个店铺搬到多个店铺，支持按规则筛选（类目、销量区间、价格区间），并对重复铺货风险给出提示。输入：
 * 筛选规则；目标店铺集合；重复度控制参数。输出：批量搬迁任务 + 结果明细；重复度风险提示。关系：上游 = 4.4.2.1；下游 = 4.3.1.2。
 * <b>搬迁后必须做标题差异化</b>，否则平台可能判定重复铺货并抑制 listing。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「不知道」是一个状态，不是一个 0**。销量维度未接入（归 4.5.1.1 平台商品监控）、价格取的是平台侧
 *    在售价快照（不是 05 定价的权威售价）—— 两件事都写在筛选区旁边，筛不出东西不会被误读成
 *    「没有商品」。
 * 2. **重复铺货风险与已搬迁分开显示**。风险高 ≠ 已经搬过；两块区域各有各的查询与空态。
 * 3. **标题差异化是流程的一部分**。页面展示的是**差异化之后**的标题并列出每一步的规则；源标题只作对照。
 *    页面**不提供**「原样照搬」的开关 —— 后端也没有这个入口。
 * 4. **草稿态一眼可见**。目标草稿的徽章是「草稿 · 尚未过平台审核」。
 * 5. **幂等**。重复点「确认搬迁」只会得到同一个批次（幂等键由后端按请求内容派生）。
 */
export function ListingMigrationWorkspace() {
  const [caps, setCaps] = useState<ListingMigrationCapabilities | null>(null);
  const [shops, setShops] = useState<ShopView[]>([]);
  const [categories, setCategories] = useState<
    { categoryPath: string; platformCategoryId: string; listingCount: number }[]
  >([]);
  const [batches, setBatches] = useState<ListingMigrationBatchRow[]>([]);
  const [activeBatchId, setActiveBatchId] = useState<string | null>(null);
  const [items, setItems] = useState<ListingMigrationItemRow[]>([]);
  const [risks, setRisks] = useState<ListingMigrationDuplicateRow[]>([]);
  const [migrated, setMigrated] = useState<ListingMigrationMigratedRow[]>([]);
  const [preview, setPreview] = useState<ListingMigrationPreview | null>(null);

  const [draft, setDraft] = useState<MigrationDraft>(EMPTY_DRAFT);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastResult, setLastResult] = useState<string | null>(null);

  const patch = useCallback((next: Partial<MigrationDraft>) => {
    setDraft((prev) => ({ ...prev, ...next }));
  }, []);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, shopList, batchList, riskList, migratedList] = await Promise.all([
        fetchMigrationCapabilities(),
        fetchShops(),
        fetchMigrationBatches(),
        fetchDuplicateRisks(),
        fetchMigrated(),
      ]);
      setCaps(capsResult);
      setShops(shopList);
      setBatches(batchList);
      setRisks(riskList);
      setMigrated(migratedList);
      setActiveBatchId((prev) => prev ?? (batchList.length > 0 ? batchList[0]!.batchId : null));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 源店铺变了 ⇒ 类目选项跟着换
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const options = await fetchFilterOptions(draft.sourceShopId || undefined);
        if (!cancelled) setCategories(options.categories);
      } catch {
        if (!cancelled) setCategories([]);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [draft.sourceShopId]);

  const run = useCallback(async (work: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await work();
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败，请重试');
    } finally {
      setBusy(false);
    }
  }, []);

  const targetShops = useMemo(
    () => targetShopOptions(shops, draft.sourceShopId),
    [shops, draft.sourceShopId],
  );

  const previewRequest = useMemo(() => buildPreviewRequest(draft), [draft]);
  const executeRequest = useMemo(() => buildExecuteRequest(draft), [draft]);

  const doPreview = useCallback(
    () =>
      run(async () => {
        if (!previewRequest) {
          setError('筛选条件还不完整（需要源店铺与至少一个目标店铺，区间也不能倒置）。');
          return;
        }
        setPreview(await previewMigration(previewRequest));
        setLastResult(null);
      }),
    [run, previewRequest],
  );

  const doExecute = useCallback(
    () =>
      run(async () => {
        if (!executeRequest) {
          setError('筛选条件还不完整（需要源店铺与至少一个目标店铺，区间也不能倒置）。');
          return;
        }
        const batch = await executeMigration(executeRequest);
        setLastResult(
          `搬迁批次 ${batch.batchId}：搬迁 ${batch.migratedCount} 条、跳过 ${batch.skippedCount} 条、失败 ${batch.failedCount} 条。`,
        );
        setActiveBatchId(batch.batchId);
        setPreview(null);
        const [batchList, riskList, migratedList] = await Promise.all([
          fetchMigrationBatches(),
          fetchDuplicateRisks(),
          fetchMigrated(),
        ]);
        setBatches(batchList);
        setRisks(riskList);
        setMigrated(migratedList);
      }),
    [run, executeRequest],
  );

  const loadItems = useCallback(
    (batchId: string) =>
      run(async () => {
        setItems(await fetchMigrationItems(batchId));
      }),
    [run],
  );

  useEffect(() => {
    if (!activeBatchId) {
      setItems([]);
      return;
    }
    void loadItems(activeBatchId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeBatchId]);

  const toggleTarget = (shopId: string) => {
    setDraft((prev) => ({
      ...prev,
      targetShopIds: prev.targetShopIds.includes(shopId)
        ? prev.targetShopIds.filter((id) => id !== shopId)
        : [...prev.targetShopIds, shopId],
    }));
  };

  const toggleCategory = (path: string) => {
    setDraft((prev) => ({
      ...prev,
      categoryPaths: prev.categoryPaths.includes(path)
        ? prev.categoryPaths.filter((p) => p !== path)
        : [...prev.categoryPaths, path],
    }));
  };

  const salesReport = dimensionReport(preview?.explanation ?? null, 'SALES');
  const priceReport = dimensionReport(preview?.explanation ?? null, 'PRICE');

  if (loading) {
    return (
      <EmptyState
        icon={<Loader2 className="size-6 animate-spin" />}
        title="正在加载批量搬迁工作区"
        description="拉取能力声明、店铺列表与搬迁历史。"
        action={
          <Button variant="secondary" onClick={() => void refresh()}>
            <RefreshCw className="mr-2 size-4" />
            重试
          </Button>
        }
      />
    );
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="批量搬迁"
        description="按类目 / 销量 / 价格区间筛出一批 listing，批量搬到多个店铺；搬迁后自动做标题差异化，并给出重复铺货风险提示。"
      />

      {error && (
        <div className="border-destructive/40 bg-destructive/5 text-destructive flex items-start gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p className="text-label-13">{error}</p>
        </div>
      )}

      {lastResult && (
        <div className="border-brand/40 bg-brand-soft/40 rounded-lg border px-4 py-3">
          <p className="text-foreground flex items-start gap-2 text-label-13">
            <CheckCircle2 className="mt-0.5 size-4 shrink-0 text-brand" />
            <span>{lastResult}</span>
          </p>
        </div>
      )}

      {/* ---- 三条硬保证（后端给原文，前端原样展示） ---- */}
      {caps && (
        <div className="border-brand/40 bg-brand-soft/40 rounded-lg border px-4 py-3">
          <p className="text-foreground mb-1 text-label-13 font-medium">批量搬迁的三条硬保证</p>
          <ul className="text-muted-foreground space-y-1 text-label-12">
            <li className="flex items-start gap-2">
              <ShieldCheck className="mt-0.5 size-3.5 shrink-0 text-brand" />
              <span>{caps.titleDifferentiationNote}</span>
            </li>
            <li className="flex items-start gap-2">
              <ShieldCheck className="mt-0.5 size-3.5 shrink-0 text-brand" />
              <span>{caps.riskNote}</span>
            </li>
            <li className="flex items-start gap-2">
              <ShieldCheck className="mt-0.5 size-3.5 shrink-0 text-brand" />
              <span>{caps.idempotencyNote}</span>
            </li>
          </ul>
        </div>
      )}

      {/* ---- 未接入维度明账 ---- */}
      {caps &&
        caps.dimensions
          .filter((d) => !d.available)
          .map((d) => (
            <div
              key={d.dimension}
              className="border-warning/40 bg-warning/5 rounded-lg border px-4 py-3"
            >
              <p className="text-warning mb-1 text-label-13 font-medium">{d.description}：未接入</p>
              <p className="text-muted-foreground text-label-12">
                归 {d.ownerModule} —— {d.impact}
              </p>
            </div>
          ))}

      {/* ---- 筛选与目标店铺 ---- */}
      <SectionCard
        title="筛选与目标店铺"
        description="选源店铺与筛选条件 → 选目标店铺集合 → 设重复度控制参数 → 预览 → 确认搬迁。"
        extra={
          <Button variant="ghost" size="sm" onClick={() => void refresh()} disabled={busy}>
            <RefreshCw className="mr-2 size-4" />
            刷新
          </Button>
        }
      >
        <div className="space-y-5 px-4 py-4">
          {/* 源店铺 */}
          <div>
            <p className="text-foreground mb-2 text-label-13 font-medium">① 源店铺</p>
            <select
              className={SELECT_CLASS}
              value={draft.sourceShopId}
              onChange={(e) => patch({ sourceShopId: e.target.value, categoryPaths: [] })}
              disabled={busy}
            >
              <option value="">选择源店铺</option>
              {shops.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.code} · {s.name}（{s.platformCode}）
                </option>
              ))}
            </select>
          </div>

          {/* 类目 */}
          <div>
            <p className="text-foreground mb-2 text-label-13 font-medium">② 类目筛选</p>
            {categories.length === 0 ? (
              <p className="text-muted-foreground text-label-12">
                选了源店铺之后，这里会列出它名下**真实存在**的类目。
              </p>
            ) : (
              <ul className="flex flex-wrap gap-2">
                {categories.map((c) => {
                  const key = c.categoryPath || c.platformCategoryId;
                  const active = draft.categoryPaths.includes(key);
                  return (
                    <li key={key}>
                      <button
                        type="button"
                        onClick={() => toggleCategory(key)}
                        className={
                          active
                            ? 'border-brand bg-brand-soft/60 text-foreground rounded-md border px-2.5 py-1 text-label-12'
                            : 'border-border text-muted-foreground hover:text-foreground rounded-md border px-2.5 py-1 text-label-12'
                        }
                      >
                        {categoryOptionLabel(c)}（{c.listingCount}）
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>

          {/* 数值区间 */}
          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <p className="text-foreground mb-2 text-label-13 font-medium">③ 价格区间</p>
              <div className="flex items-center gap-2">
                <Input
                  className="max-w-[10rem]"
                  placeholder="下界"
                  value={draft.priceMin}
                  onChange={(e) => patch({ priceMin: e.target.value })}
                />
                <span className="text-muted-foreground text-label-12">~</span>
                <Input
                  className="max-w-[10rem]"
                  placeholder="上界"
                  value={draft.priceMax}
                  onChange={(e) => patch({ priceMax: e.target.value })}
                />
              </div>
              <p className="text-muted-foreground mt-1 text-label-12">
                来源：平台侧在售价快照（回写登记簿）。未登记为可回写目标的 listing 价格是**未知**，不是 0。
              </p>
            </div>
            <div>
              <p className="text-foreground mb-2 text-label-13 font-medium">④ 销量区间</p>
              <div className="flex items-center gap-2">
                <Input
                  className="max-w-[10rem]"
                  placeholder="下界"
                  value={draft.salesMin}
                  onChange={(e) => patch({ salesMin: e.target.value })}
                />
                <span className="text-muted-foreground text-label-12">~</span>
                <Input
                  className="max-w-[10rem]"
                  placeholder="上界"
                  value={draft.salesMax}
                  onChange={(e) => patch({ salesMax: e.target.value })}
                />
              </div>
              <p className="text-warning mt-1 text-label-12">
                ⚠️ 销量维度**未接入**（归 4.5.1.1 平台商品监控）：所有候选的销量都是未知（不是 0），
                命中数按下面的未知值策略如实报。
              </p>
            </div>
          </div>

          {/* 未知值策略 + 排序 */}
          <div className="grid gap-3 sm:grid-cols-2">
            <div>
              <p className="text-foreground mb-2 text-label-13 font-medium">⑤ 未知值策略</p>
              <select
                className={SELECT_CLASS}
                value={draft.unknownPolicy}
                onChange={(e) =>
                  patch({ unknownPolicy: e.target.value as MigrationDraft['unknownPolicy'] })
                }
                disabled={busy}
              >
                {UNKNOWN_POLICY_OPTIONS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <p className="text-foreground mb-2 text-label-13 font-medium">⑥ 排序</p>
              <div className="flex gap-2">
                <select
                  className={SELECT_CLASS}
                  value={draft.sortBy}
                  onChange={(e) => patch({ sortBy: e.target.value })}
                  disabled={busy}
                >
                  {SORT_OPTIONS.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
                <select
                  className={SELECT_CLASS}
                  value={draft.sortOrder}
                  onChange={(e) => patch({ sortOrder: e.target.value as 'ASC' | 'DESC' })}
                  disabled={busy}
                >
                  <option value="DESC">降序</option>
                  <option value="ASC">升序</option>
                </select>
              </div>
            </div>
          </div>

          {/* 目标店铺 */}
          <div>
            <p className="text-foreground mb-2 text-label-13 font-medium">⑦ 目标店铺集合</p>
            {targetShops.length === 0 ? (
              <p className="text-muted-foreground text-label-12">
                没有别的可迁入店铺。批量搬迁要求目标店铺与源店铺**不同**。
              </p>
            ) : (
              <ul className="flex flex-wrap gap-2">
                {targetShops.map((s) => {
                  const active = draft.targetShopIds.includes(s.id);
                  return (
                    <li key={s.id}>
                      <button
                        type="button"
                        onClick={() => toggleTarget(s.id)}
                        className={
                          active
                            ? 'border-brand bg-brand-soft/60 text-foreground rounded-md border px-2.5 py-1 text-label-12'
                            : 'border-border text-muted-foreground hover:text-foreground rounded-md border px-2.5 py-1 text-label-12'
                        }
                      >
                        {s.code} · {s.name}
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>

          {/* 重复度控制参数 */}
          <div>
            <p className="text-foreground mb-2 text-label-13 font-medium">⑧ 重复度控制参数</p>
            <div className="grid gap-3 sm:grid-cols-3">
              <select
                className={SELECT_CLASS}
                value={draft.maxRiskLevel}
                onChange={(e) => patch({ maxRiskLevel: e.target.value as MigrationDraft['maxRiskLevel'] })}
                disabled={busy}
              >
                {MAX_RISK_OPTIONS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
              <select
                className={SELECT_CLASS}
                value={draft.onUnknown}
                onChange={(e) => patch({ onUnknown: e.target.value as MigrationDraft['onUnknown'] })}
                disabled={busy}
              >
                {UNKNOWN_RISK_OPTIONS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
              <Input
                placeholder="标题模板（可选）：{storeName} - {title}"
                value={draft.titleTemplate}
                onChange={(e) => patch({ titleTemplate: e.target.value })}
              />
            </div>
            <p className="text-muted-foreground mt-1 text-label-12">
              标题模板可用占位符：{'{title}'} {'{storeName}'} {'{shopCode}'} {'{marketplaceCode}'}{' '}
              {'{categoryLeaf}'}。不填时用默认规则（店铺标识 → 站点标识 → 同名消歧）。
            </p>
          </div>

          <div className="flex flex-wrap gap-2">
            <Button variant="secondary" onClick={() => void doPreview()} disabled={busy}>
              {busy ? <Loader2 className="mr-2 size-4 animate-spin" /> : null}
              预览
            </Button>
            <Button onClick={() => void doExecute()} disabled={busy || !executeRequest}>
              确认搬迁
            </Button>
            <Input
              className="max-w-sm"
              placeholder="备注"
              value={draft.note}
              onChange={(e) => patch({ note: e.target.value })}
            />
          </div>
        </div>
      </SectionCard>

      {/* ---- 预览 ---- */}
      <SectionCard
        title="预览（不写任何数据）"
        description="筛选命中什么、每个目标店铺的重复铺货风险有多高、标题会怎么差异化。"
      >
        <div className="space-y-4 px-4 py-4">
          {preview?.explanation && (
            <div className="border-border bg-muted/30 rounded-lg border px-3 py-2">
              <p className="text-foreground text-label-13">{preview.explanation.note}</p>
              <ul className="text-muted-foreground mt-2 space-y-1 text-label-12">
                {preview.explanation.dimensions.map((d) => (
                  <li key={d.dimension}>
                    <span className="text-foreground">{d.dimension}</span>：{d.note}
                    {d.sourceAvailable ? '' : `（来源未接入 · 归 ${d.ownerModule}）`}
                  </li>
                ))}
              </ul>
            </div>
          )}
          {priceReport && !priceReport.sourceAvailable && (
            <p className="text-warning text-label-12">{priceReport.note}</p>
          )}
          {salesReport && !salesReport.sourceAvailable && (
            <p className="text-warning text-label-12">{salesReport.note}</p>
          )}

          {preview === null ? (
            <p className="text-muted-foreground text-label-13">{emptyPreviewMessage()}</p>
          ) : preview.items.length === 0 ? (
            <p className="text-muted-foreground text-label-13">
              没有命中任何候选商品。放宽筛选条件，或检查这个维度的数据源是否已接入。
            </p>
          ) : (
            <ul className="space-y-3">
              {preview.items.map((c) => (
                <li key={c.sourceListingId} className="border-border rounded-lg border px-3 py-2">
                  <p className="text-foreground text-label-13">
                    {c.sourceTitle}
                    <span className="text-muted-foreground">（{c.sourceCategoryPath || '未归类目'}）</span>
                  </p>
                  <p className="text-muted-foreground mt-1 text-label-12">{c.priceNote}</p>
                  {c.targets.map((t) => (
                    <div key={t.targetShopId} className="mt-2 border-border/70 rounded-md border px-2.5 py-1.5">
                      <p className="text-foreground text-label-12">
                        → {t.targetShopCode}（{t.targetShopName} / {t.targetMarketplaceCode}）
                      </p>
                      <p className="text-muted-foreground mt-1 flex flex-wrap items-center gap-2 text-label-12">
                        <Badge tone={riskLevelTone(t.duplicateLevel)}>
                          重复度 {riskScoreLabel(t.duplicateScore, t.duplicateLevel)}
                        </Badge>
                        {t.alreadyMigrated ? <Badge tone="warning">已搬迁</Badge> : null}
                        {t.alreadyClaimed ? <Badge tone="warning">已被 4.4.2.1 认领</Badge> : null}
                      </p>
                      {t.plannedTargetTitle ? (
                        <p className="text-foreground mt-1 text-label-12">
                          搬迁后标题：<span className="text-foreground">{t.plannedTargetTitle}</span>
                          <span className="text-muted-foreground">
                            （规则：{t.plannedRules.map(ruleLabel).join(' → ') || '—'}）
                          </span>
                        </p>
                      ) : (
                        <p className="text-warning mt-1 text-label-12">
                          这个组合做不出差异化标题，搬迁会被拒绝。
                        </p>
                      )}
                      <p className="text-muted-foreground mt-1 text-label-12">{t.duplicateReason}</p>
                    </div>
                  ))}
                </li>
              ))}
            </ul>
          )}
        </div>
      </SectionCard>

      {/* ---- 结果明细 ---- */}
      <SectionCard
        title="搬迁结果明细"
        description="原标题 vs 差异化后的标题、每一步用了哪条规则、重复度结论、目标草稿状态。"
        extra={
          batches.length > 0 ? (
            <select
              className={SELECT_CLASS}
              value={activeBatchId ?? ''}
              onChange={(e) => setActiveBatchId(e.target.value)}
              disabled={busy}
            >
              {batches.map((b) => (
                <option key={b.batchId} value={b.batchId}>
                  {b.createdAt?.slice(0, 16).replace('T', ' ')} · {b.sourceShopCode} → 搬迁{' '}
                  {b.migratedCount} / 跳过 {b.skippedCount} / 失败 {b.failedCount}
                </option>
              ))}
            </select>
          ) : null
        }
      >
        <div className="space-y-3 px-4 py-4">
          {items.length === 0 ? (
            <p className="text-muted-foreground text-label-13">{emptyItemsMessage()}</p>
          ) : (
            <ul className="space-y-3">
              {items.map((item) => (
                <li key={item.itemId} className="border-border rounded-lg border px-3 py-2">
                  <p className="text-foreground flex flex-wrap items-center gap-2 text-label-13">
                    <Badge tone={itemStateTone(item.state)}>{itemStateLabel(item.state)}</Badge>
                    <span>{item.sourceTitle}</span>
                    <ArrowRight className="text-muted-foreground size-3.5" />
                    <span className="text-foreground">{item.differentiatedTitle || '（未搬迁）'}</span>
                  </p>
                  <p className="text-muted-foreground mt-1 flex flex-wrap items-center gap-2 text-label-12">
                    <Badge tone={riskLevelTone(item.duplicateLevel)}>
                      重复度 {riskScoreLabel(item.duplicateScore, item.duplicateLevel)}
                    </Badge>
                    <span>
                      目标 {item.targetShopCode} / {item.targetMarketplaceCode}
                    </span>
                    {item.targetListingId ? (
                      <Badge tone="warning">{targetDraftBadge(item)}</Badge>
                    ) : null}
                  </p>
                  {item.skipReason ? (
                    <p className="text-warning mt-1 text-label-12">{item.skipReason}</p>
                  ) : null}
                  {item.failureReason ? (
                    <p className="text-destructive mt-1 text-label-12">{item.failureReason}</p>
                  ) : null}
                  {item.duplicateBasis ? (
                    <p className="text-muted-foreground mt-1 text-label-12">
                      标题 {similarityLabel(item.duplicateBasis.titleSimilarity)} · 类目{' '}
                      {similarityLabel(item.duplicateBasis.categorySimilarity)} · 属性{' '}
                      {similarityLabel(item.duplicateBasis.attributeSimilarity)} ——{' '}
                      {item.duplicateBasis.explanation}
                    </p>
                  ) : null}
                  {item.diffs.length > 0 ? (
                    <ul className="border-border/70 mt-2 space-y-1 rounded-md border px-2.5 py-1.5">
                      {item.diffs.map((d) => (
                        <li key={`${d.field}-${d.ruleId}-${d.targetValue}`} className="text-label-12">
                          <span className="text-foreground">{d.field}</span>
                          <span className="text-muted-foreground"> · {ruleLabel(d.ruleId)}：</span>
                          <span className="text-muted-foreground line-through">{d.sourceValue}</span>
                          <ArrowRight className="text-muted-foreground mx-1 inline size-3" />
                          <span className="text-foreground">{d.targetValue}</span>
                          {d.ruleParams.reason ? (
                            <span className="text-muted-foreground"> —— {d.ruleParams.reason}</span>
                          ) : null}
                        </li>
                      ))}
                    </ul>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
        </div>
      </SectionCard>

      {/* ---- 重复铺货风险（独立查询） ---- */}
      <SectionCard
        title="重复铺货风险"
        description="与「已搬迁」是两种事实、两种处置：风险高说明「搬过去可能被平台判重复」，已搬迁说明「已经搬过了」。"
      >
        <div className="space-y-2 px-4 py-4">
          {risks.length === 0 ? (
            <p className="text-muted-foreground text-label-13">{emptyRisksMessage()}</p>
          ) : (
            <ul className="space-y-2">
              {risks.map((r) => (
                <li key={r.assessmentId} className="border-border rounded-lg border px-3 py-2">
                  <p className="text-foreground flex flex-wrap items-center gap-2 text-label-13">
                    <Badge tone={riskLevelTone(r.level)}>
                      重复度 {riskScoreLabel(r.score, r.level)}
                    </Badge>
                    <span>{r.sourceTitle}</span>
                    <span className="text-muted-foreground">→ {r.targetShopCode}</span>
                  </p>
                  <p className="text-muted-foreground mt-1 text-label-12">
                    最像的既有商品：{r.comparableTitle || '（无可比商品）'}
                    {r.comparableCount > 0 ? `（参与比对 ${r.comparableCount} 条）` : ''}
                  </p>
                  {r.basis ? <p className="text-muted-foreground text-label-12">{r.basis.explanation}</p> : null}
                </li>
              ))}
            </ul>
          )}
        </div>
      </SectionCard>

      {/* ---- 已搬迁（独立查询） ---- */}
      <SectionCard
        title="已搬迁"
        description="哪些源商品已经搬进了哪些店铺。搬第二次会被跳过 —— 已搬迁的那一对在数据库层也搬不了第二次。"
      >
        <div className="space-y-2 px-4 py-4">
          {migrated.length === 0 ? (
            <p className="text-muted-foreground text-label-13">{emptyMigratedMessage()}</p>
          ) : (
            <ul className="space-y-2">
              {migrated.map((m) => (
                <li key={m.itemId} className="border-border rounded-lg border px-3 py-2">
                  <p className="text-foreground flex flex-wrap items-center gap-2 text-label-13">
                    <span>{m.sourceTitle}</span>
                    <ArrowRight className="text-muted-foreground size-3.5" />
                    <span>{m.differentiatedTitle}</span>
                    <span className="text-muted-foreground">→ {m.targetShopCode}</span>
                  </p>
                  <p className="text-muted-foreground mt-1 text-label-12">
                    重复度 {riskScoreLabel(m.duplicateScore, m.duplicateLevel)}
                  </p>
                </li>
              ))}
            </ul>
          )}
        </div>
      </SectionCard>
    </div>
  );
}
