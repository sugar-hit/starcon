'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard } from '@starcon/ui';
import {
  ArrowRight,
  CheckCircle2,
  Loader2,
  PackagePlus,
  RefreshCw,
  ShieldCheck,
  TriangleAlert,
} from 'lucide-react';
import {
  buildClaimRequest,
  claimStateLabel,
  claimStateTone,
  createClaim,
  DECISION_OPTIONS,
  decisionLabel,
  draftBadge,
  draftBadgeTone,
  emptyClaimsMessage,
  fetchClaimCapabilities,
  fetchClaims,
  fetchShops,
  fetchSourceCandidates,
  FIELD_OPTIONS,
  pendingPlatformItemIdNote,
  siteBoundFields,
  sourceOriginLabel,
  sourceUnchanged,
  targetShopOptions,
  targetSiteOptions,
  unavailableDimensions,
  type ClaimDraft,
  type ClaimFieldDrafts,
  type ClaimListingRequest,
  type DecisionMode,
  type ListingClaimCapabilities,
  type ListingClaimRow,
  type ListingRow,
  type ShopView,
} from './listing-claim-model';

/**
 * 4.4.2.1 商品认领 工作区。
 *
 * 蓝图：「把已在 A 店铺的 listing 认领到 B 店铺（跨店搬家），复用原商品内容与映射，生成 B 店铺的新 listing 草稿。输入：源
 * listing；目标店铺与站点；保留/重写的内容字段选择。输出：目标店铺的 listing 草稿 + 待发布任务。关系：上游 = 4.1.1.2；下游 =
 * 4.3.1.1。<b>新店铺 listing 是全新平台资源</b>，需重新过平台审核；<b>源 listing 不受影响</b>。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **草稿态一眼可见**。目标草稿的徽章是「草稿 · 尚未过平台审核」，不是"已发布"。
 * 2. **源 listing 不受影响**。源面板把「认领时记录的源状态」与「现在的源状态」并排列出；
 *    两者不一致时页面明说"源商品在认领之后被改过"，不拿"认领不会动源"糊弄过去。
 * 3. **没有改价 / 改库存入口**。字段列表里没有它们（售价归 05、内部可售量归 07）；
 *    源商品的评分与币种也不会被搬到目标店铺。
 * 4. **平台类目 ID 与站点绑定**。选了跨站点的目标后，这三个字段的"保留"选项被禁用并写明原因 ——
 *    页面不让用户点一次提交才吃一个 422。
 * 5. **待发布任务只入队**。页面写的是「待发布（已入队，等 4.3.1.2 派发）」，不是"已发布"。
 */
export function ListingClaimWorkspace() {
  const [caps, setCaps] = useState<ListingClaimCapabilities | null>(null);
  const [shops, setShops] = useState<ShopView[]>([]);
  const [claims, setClaims] = useState<ListingClaimRow[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);

  // ---- 选源商品
  const [search, setSearch] = useState('');
  const [candidates, setCandidates] = useState<ListingRow[]>([]);
  const [source, setSource] = useState<ListingRow | null>(null);

  // ---- 选目标店铺与站点
  const [targetShopId, setTargetShopId] = useState('');
  const [targetMarketplaceId, setTargetMarketplaceId] = useState('');

  // ---- 字段决策
  const [fields, setFields] = useState<ClaimFieldDrafts>({});
  const [note, setNote] = useState('');

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastResult, setLastResult] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, shopList, claimList] = await Promise.all([
        fetchClaimCapabilities(),
        fetchShops(),
        fetchClaims(),
      ]);
      setCaps(capsResult);
      setShops(shopList);
      setClaims(claimList);
      setActiveId((prev) => prev ?? (claimList.length > 0 ? claimList[0]!.claimId : null));
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

  // ---- 源商品候选
  const searchSources = useCallback(async () => {
    try {
      setCandidates(await fetchSourceCandidates(search));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载源商品失败，请重试');
    }
  }, [search]);

  const targetShops = useMemo(
    () => targetShopOptions(shops, source?.shopId ?? ''),
    [shops, source],
  );
  const targetShop = useMemo(
    () => shops.find((s) => s.id === targetShopId) ?? null,
    [shops, targetShopId],
  );
  const targetSites = useMemo(() => targetSiteOptions(targetShop), [targetShop]);
  const crossSite = useMemo(
    () => siteBoundFields(source?.marketplaceId ?? null, targetMarketplaceId),
    [source, targetMarketplaceId],
  );

  const draft: ClaimDraft = useMemo(
    () => ({
      sourceListingId: source?.id ?? '',
      targetShopId,
      targetMarketplaceId,
      language: '',
      note,
      fields,
    }),
    [source, targetShopId, targetMarketplaceId, note, fields],
  );
  const payload: ClaimListingRequest | null = useMemo(
    () => buildClaimRequest(draft),
    [draft],
  );

  const active = useMemo(
    () => claims.find((c) => c.claimId === activeId) ?? null,
    [claims, activeId],
  );

  function setFieldMode(field: string, mode: DecisionMode) {
    setFields((prev) => ({
      ...prev,
      [field]: { mode, value: prev[field]?.value ?? '' },
    }));
  }

  function setFieldValue(field: string, value: string) {
    setFields((prev) => ({
      ...prev,
      [field]: { mode: prev[field]?.mode ?? 'REWRITE', value },
    }));
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center gap-2 py-16 text-muted-foreground">
        <Loader2 className="size-4 animate-spin" />
        <span className="text-label-13">正在加载商品认领…</span>
      </div>
    );
  }

  if (error && !caps) {
    return (
      <SectionCard title="商品认领">
        <EmptyState
          icon={<TriangleAlert className="size-8 text-destructive" />}
          title="商品认领加载失败"
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
        title="商品认领"
        description="把已在 A 店铺的 listing 认领到 B 店铺（跨店搬家），复用原商品内容与映射，生成 B 店铺的新 listing 草稿。"
      />

      {error && (
        <div className="border-destructive/40 bg-destructive/5 text-destructive flex items-start gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p className="text-label-13">{error}</p>
        </div>
      )}

      {/* ---- 🔴 两条边界的界面措辞（后端给原文，前端原样展示） ---- */}
      {caps && (
        <div className="border-brand/40 bg-brand-soft/40 rounded-lg border px-4 py-3">
          <p className="text-foreground mb-1 text-label-13 font-medium">跨店搬家的两条硬边界</p>
          <ul className="text-muted-foreground space-y-1 text-label-12">
            <li className="flex items-start gap-2">
              <ShieldCheck className="mt-0.5 size-3.5 shrink-0 text-brand" />
              <span>{caps.sourceUntouchedNote}</span>
            </li>
            <li className="flex items-start gap-2">
              <PackagePlus className="mt-0.5 size-3.5 shrink-0 text-brand" />
              <span>{caps.draftNote}</span>
            </li>
            <li className="flex items-start gap-2">
              <CheckCircle2 className="mt-0.5 size-3.5 shrink-0 text-brand" />
              <span>{caps.publishTaskNote}</span>
            </li>
          </ul>
        </div>
      )}

      {/* ---- 未接入维度明账 ---- */}
      {caps && unavailableDimensions(caps).length > 0 && (
        <div className="border-warning/40 bg-warning/5 rounded-lg border px-4 py-3">
          <p className="text-warning mb-1 text-label-13 font-medium">部分能力未接入</p>
          <ul className="text-muted-foreground space-y-1 text-label-12">
            {unavailableDimensions(caps).map((d) => (
              <li key={d.dimension}>
                <span className="text-foreground">{d.description}</span>
                ：归 {d.ownerModule} —— {d.impact}
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* ---- 认领表单 ---- */}
      <SectionCard
        title="认领一个商品"
        description="① 选源商品 → ② 选目标店铺与站点 → ③ 逐字段决定保留 / 重写 / 跳过 → 提交，生成目标草稿与待发布任务。"
        extra={
          <Button variant="ghost" size="sm" onClick={() => void refresh()} disabled={busy}>
            <RefreshCw className="mr-2 size-4" />
            刷新
          </Button>
        }
      >
        <div className="space-y-5 px-4 py-4">
          {/* ① 源商品 */}
          <div>
            <p className="text-foreground mb-2 text-label-13 font-medium">① 源 listing</p>
            <div className="flex flex-wrap gap-2">
              <Input
                className="max-w-sm"
                placeholder="按平台商品 ID 或标题搜索"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') void searchSources();
                }}
              />
              <Button variant="secondary" onClick={() => void searchSources()} disabled={busy}>
                搜索
              </Button>
            </div>
            {source ? (
              <div className="border-border bg-muted/30 mt-2 rounded-lg border px-3 py-2">
                <p className="text-foreground text-label-13">
                  {source.title}
                  <span className="text-muted-foreground">
                    （{source.shopCode} / {source.marketplaceCode ?? '未归站点'} / {source.externalItemId}）
                  </span>
                </p>
                <p className="text-muted-foreground mt-1 text-label-12">
                  状态 {source.status} · 审核 {source.reviewStatus} · 映射 {source.mappingStatus}
                </p>
              </div>
            ) : (
              <ul className="mt-2 max-h-56 space-y-1 overflow-auto">
                {candidates.length === 0 ? (
                  <li className="text-muted-foreground text-label-12">
                    没有候选商品。搜一下平台商品 ID 或标题。
                  </li>
                ) : (
                  candidates.map((c) => (
                    <li key={c.id}>
                      <button
                        type="button"
                        onClick={() => {
                          setSource(c);
                          setFields({});
                          setTargetShopId('');
                          setTargetMarketplaceId('');
                        }}
                        className="border-border hover:border-foreground/40 hover:text-foreground text-muted-foreground w-full rounded-md border px-3 py-1.5 text-left text-label-13"
                      >
                        {c.title}
                        <span className="text-muted-foreground">
                          （{c.shopCode} / {c.marketplaceCode ?? '未归站点'} / {c.externalItemId}）
                        </span>
                      </button>
                    </li>
                  ))
                )}
              </ul>
            )}
          </div>

          {/* ② 目标店铺与站点 */}
          <div>
            <p className="text-foreground mb-2 text-label-13 font-medium">② 目标店铺与站点</p>
            <div className="grid gap-3 sm:grid-cols-2">
              <select
                className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
                value={targetShopId}
                onChange={(e) => {
                  setTargetShopId(e.target.value);
                  setTargetMarketplaceId('');
                }}
                disabled={busy || !source}
              >
                <option value="">选择目标店铺</option>
                {targetShops.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.code} · {s.name}（{s.platformCode}）
                  </option>
                ))}
              </select>
              <select
                className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
                value={targetMarketplaceId}
                onChange={(e) => setTargetMarketplaceId(e.target.value)}
                disabled={busy || !targetShop}
              >
                <option value="">选择目标站点</option>
                {targetSites.map((m) => (
                  <option key={m.marketplaceId} value={m.marketplaceId}>
                    {m.marketplaceCode} · {m.marketplaceName}
                  </option>
                ))}
              </select>
            </div>
            {source && targetShops.length === 0 && (
              <p className="text-warning mt-2 text-label-12">
                没有别的可迁入店铺。跨店搬家要求目标店铺与源店铺**不同**。
              </p>
            )}
            {targetShop && targetSites.length === 0 && (
              <p className="text-warning mt-2 text-label-12">
                目标店铺没有启用任何站点。请先在 2.3.1.1 启用站点。
              </p>
            )}
          </div>

          {/* ③ 字段决策 */}
          <div>
            <p className="text-foreground mb-2 text-label-13 font-medium">
              ③ 保留 / 重写的内容字段
              <span className="text-muted-foreground font-normal">
                （没给决策的字段不会被复用；售价与库存不在此列）
              </span>
            </p>
            <div className="space-y-2">
              {FIELD_OPTIONS.map((option) => {
                const current = fields[option.value];
                const siteBlocked = crossSite.includes(option.value) && current?.mode === 'KEEP';
                return (
                  <div
                    key={option.value}
                    className="border-border flex flex-wrap items-center gap-2 rounded-md border px-3 py-2"
                  >
                    <span className="text-foreground w-24 text-label-13">{option.label}</span>
                    <span className="text-muted-foreground min-w-40 flex-1 text-label-12">
                      {option.hint}
                    </span>
                    <div className="flex gap-1">
                      {DECISION_OPTIONS.map((d) => {
                        const disabled =
                          siteBlocked && d.value === 'KEEP' && current?.mode === 'KEEP';
                        return (
                          <button
                            key={d.value}
                            type="button"
                            disabled={disabled || busy}
                            onClick={() => setFieldMode(option.value, d.value)}
                            className={
                              current?.mode === d.value
                                ? 'border-brand bg-brand-soft text-brand rounded-md border px-2.5 py-1 text-label-12'
                                : 'border-border text-muted-foreground hover:border-foreground/40 hover:text-foreground rounded-md border px-2.5 py-1 text-label-12'
                            }
                          >
                            {d.label}
                          </button>
                        );
                      })}
                    </div>
                    {current?.mode === 'REWRITE' && (
                      <Input
                        className="max-w-xs"
                        placeholder={
                          option.json
                            ? `新的${option.label}（JSON ${option.json === 'array' ? '数组' : '对象'}文本）`
                            : `新的${option.label}`
                        }
                        value={current.value}
                        onChange={(e) => setFieldValue(option.value, e.target.value)}
                      />
                    )}
                  </div>
                );
              })}
            </div>
            {crossSite.length > 0 && (
              <p className="text-warning mt-2 text-label-12">
                目标站点与源站点不同：{crossSite.join('、')} 与**站点**绑定，
                沿用源值等于在目标 listing 上写一个假事实 —— 请改为「重写」或「跳过」。
              </p>
            )}
          </div>

          <Input
            placeholder="备注（可空）"
            value={note}
            onChange={(e) => setNote(e.target.value)}
          />

          <div className="flex flex-wrap items-center gap-3">
            <Button
              onClick={() =>
                void run(async () => {
                  if (!payload) throw new Error('请先选源商品、目标店铺与站点，并给出标题的决策');
                  const created = await createClaim(payload);
                  setClaims((prev) => [created, ...prev]);
                  setActiveId(created.claimId);
                  setLastResult(
                    `已生成目标草稿 ${created.targetListingId}（草稿态，尚未过平台审核），待发布任务已入队 ${created.publishTaskId}`,
                  );
                })
              }
              disabled={busy || payload === null}
            >
              <PackagePlus className="mr-2 size-4" />
              认领到目标店铺
            </Button>
            {payload === null && (
              <span className="text-muted-foreground text-label-12">
                选齐源商品、目标店铺与站点，并给出标题的「保留 / 重写 / 跳过」决策
              </span>
            )}
            {lastResult && <span className="text-brand text-label-12">{lastResult}</span>}
          </div>
        </div>
      </SectionCard>

      {/* ---- 认领记录 ---- */}
      <SectionCard title="认领记录" description="每一次跨店搬家的事实：源商品、目标草稿、逐字段决策。">
        {claims.length === 0 ? (
          <EmptyState
            icon={<PackagePlus className="size-8 text-muted-foreground" />}
            title="还没有认领记录"
            description={emptyClaimsMessage()}
          />
        ) : (
          <div className="space-y-3 px-4 py-4">
            <ul className="space-y-1">
              {claims.map((c) => (
                <li key={c.claimId}>
                  <button
                    type="button"
                    onClick={() => setActiveId(c.claimId)}
                    className={
                      c.claimId === activeId
                        ? 'border-brand bg-brand-soft/40 text-foreground w-full rounded-md border px-3 py-2 text-left text-label-13'
                        : 'border-border hover:border-foreground/40 hover:text-foreground text-muted-foreground w-full rounded-md border px-3 py-2 text-left text-label-13'
                    }
                  >
                    <span className="text-foreground">{c.sourceTitle}</span>
                    <ArrowRight className="mx-2 inline size-3.5" />
                    <span className="text-foreground">{c.targetShopCode}</span>
                    <span className="text-muted-foreground">（{c.targetMarketplaceCode}）</span>
                    <Badge className="ml-2" tone={draftBadgeTone(c)}>
                      {draftBadge(c)}
                    </Badge>
                  </button>
                </li>
              ))}
            </ul>

            {active && (
              <div className="border-border space-y-3 rounded-lg border px-3 py-3">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={claimStateTone(active.state)}>{claimStateLabel(active.state)}</Badge>
                  <Badge tone={draftBadgeTone(active)}>{draftBadge(active)}</Badge>
                  {sourceUnchanged(active) ? (
                    <Badge tone="success">源 listing 未受影响</Badge>
                  ) : (
                    <Badge tone="danger">源 listing 在认领之后被改过</Badge>
                  )}
                </div>

                {/* 源侧：认领时 vs 现在 */}
                <div className="grid gap-2 sm:grid-cols-2">
                  <div className="border-border rounded-md border px-3 py-2">
                    <p className="text-foreground text-label-13 font-medium">源 listing（只读）</p>
                    <p className="text-muted-foreground text-label-12">
                      {active.sourceShopCode} / {active.sourceMarketplaceCode ?? '未归站点'} /{' '}
                      {active.sourceExternalItemId}
                    </p>
                    <p className="text-muted-foreground text-label-12">
                      认领时：状态 {active.sourceStatus} · 审核 {active.sourceReviewStatus} · 版本{' '}
                      {active.sourceVersion}
                    </p>
                    <p className="text-muted-foreground text-label-12">
                      现在：状态 {active.sourceCurrentStatus} · 审核 {active.sourceCurrentReviewStatus} · 版本{' '}
                      {active.sourceCurrentVersion}
                    </p>
                  </div>
                  <div className="border-border rounded-md border px-3 py-2">
                    <p className="text-foreground text-label-13 font-medium">目标草稿</p>
                    <p className="text-muted-foreground text-label-12">
                      {active.targetShopCode} / {active.targetMarketplaceCode} /{' '}
                      {active.targetExternalItemId}
                    </p>
                    <p className="text-muted-foreground text-label-12">
                      状态 {active.targetStatus} · 审核 {active.targetReviewStatus} · 平台可售{' '}
                      {active.targetPlatformStockState}
                    </p>
                    <p className="text-muted-foreground text-label-12">
                      变体 {active.variantCount} 条（已映射 {active.mappedVariantCount} 条）· 待发布任务{' '}
                      {active.publishTaskId ?? '—'}
                    </p>
                    {pendingPlatformItemIdNote(active) && (
                      <p className="text-warning text-label-12">{pendingPlatformItemIdNote(active)}</p>
                    )}
                  </div>
                </div>

                {/* 逐字段决策 */}
                <div>
                  <p className="text-foreground mb-1 text-label-13 font-medium">逐字段决策</p>
                  {active.fields.length === 0 ? (
                    <p className="text-muted-foreground text-label-12">这次认领没有记录字段决策。</p>
                  ) : (
                    <ul className="space-y-1">
                      {active.fields.map((f) => (
                        <li key={f.field} className="text-muted-foreground text-label-12">
                          <span className="text-foreground">
                            {FIELD_OPTIONS.find((o) => o.value === f.field)?.label ?? f.field}
                          </span>
                          ：{decisionLabel(f.decision)} · 源值来自 {sourceOriginLabel(f.sourceOrigin)}
                          {f.note ? ` · ${f.note}` : ''}
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              </div>
            )}
          </div>
        )}
      </SectionCard>
    </div>
  );
}
