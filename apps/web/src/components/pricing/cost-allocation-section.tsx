'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  SectionCard,
  StatCard,
} from '@starcon/ui';

import {
  EMPTY_ALLOCATION_FILTERS,
  applyAllocationRecalculation,
  basisNoteText,
  basisRuleBasis,
  basisRuleLabel,
  createAllocation,
  dayText,
  diffChangeLabel,
  diffTone,
  fetchAllocationCapabilities,
  fetchAllocationDetail,
  fetchAllocationList,
  fetchAllocationSources,
  moneyText,
  moneyWithCurrency,
  previewAllocationRecalculation,
  sourceTypeLabel,
  timeText,
  validateCreate,
  validateRecalculate,
  varianceText,
} from './cost-allocation-model';
import type {
  BasisRule,
  PricingCostAllocationAdjustmentInput,
  PricingCostAllocationCapabilities,
  PricingCostAllocationDetail,
  PricingCostAllocationFilters,
  PricingCostAllocationListPage,
  PricingCostAllocationPreview,
  PricingCostAllocationRow,
  PricingCostAllocationSourceCandidatePage,
} from './cost-allocation-model';

/**
 * 5.2.1.3 成本分摊工作区（`/pricing/cost` 页面里的「成本分摊」分区）。
 *
 * <p>🔴 页面最上面那一块**不是提示语，是这一页的设计前提**：
 * 蓝图加粗句「**分摊规则变更会导致历史利润变化**，因此调整必须留历史版本，不能就地覆盖」。
 *
 * <p>所以本页把三件事摆在最显眼的位置：
 *
 * <ol>
 *   <li><b>当前用的哪条分摊规则</b>（按重量/体积/金额/数量）+ 它到底怎么算（口径串）；
 *   <li><b>基数从哪来</b>（货件行的申报值？还是回落到 SKU 包装 / 成本构成？）——
 *       页面上每行都写着「这一行的重量到底是哪来的」；
 *   <li><b>换规则只能追加一版</b>：试算（不落库）与应用（落新版本）分成两个动作，
 *       页面上**没有**「就地改这一行」的入口。
 * </ol>
 *
 * <p>🔴 **null 不渲染成 0**：基数取不到的行显示「基数不可得，未参与分摊」，
 * 差额（人工微调造成）显式写在合计旁边 —— 把它藏起来才是错账。
 *
 * <p>本分区与 5.2.1.1 成本构成、5.2.1.2 到岸成本共用 L3 路由 {@code /pricing/cost}，
 * 但**各占一个独立分区**，不重写彼此的页面骨架。
 */
export function CostAllocationSection() {
  const [capabilities, setCapabilities] = useState<PricingCostAllocationCapabilities | null>(null);
  const [sources, setSources] = useState<PricingCostAllocationSourceCandidatePage | null>(null);
  const [page, setPage] = useState<PricingCostAllocationListPage | null>(null);
  const [filters, setFilters] = useState<PricingCostAllocationFilters>(EMPTY_ALLOCATION_FILTERS);
  const [detail, setDetail] = useState<PricingCostAllocationDetail | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [preview, setPreview] = useState<PricingCostAllocationPreview | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);

  // 新建表单
  const [sourceRef, setSourceRef] = useState('');
  const [totalCost, setTotalCost] = useState('');
  const [basisRule, setBasisRule] = useState<BasisRule>('WEIGHT');
  const [effectiveFrom, setEffectiveFrom] = useState(nowLocal());
  const [changeReason, setChangeReason] = useState('');
  const [createProblems, setCreateProblems] = useState<string[]>([]);

  // 重算表单
  const [recalcRule, setRecalcRule] = useState('');
  const [recalcTotal, setRecalcTotal] = useState('');
  const [recalcEffectiveFrom, setRecalcEffectiveFrom] = useState(nowLocal());
  const [recalcReason, setRecalcReason] = useState('');
  const [adjustments, setAdjustments] = useState<PricingCostAllocationAdjustmentInput[]>([]);
  const [recalcProblems, setRecalcProblems] = useState<string[]>([]);

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const [next, caps, srcs] = await Promise.all([
        fetchAllocationList(filters),
        fetchAllocationCapabilities(),
        fetchAllocationSources(),
      ]);
      setPage(next);
      setCapabilities(caps);
      setSources(srcs);
      setLoaded(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : '成本分摊台账读不到');
    } finally {
      setBusy(false);
    }
  }, [filters]);

  useEffect(() => {
    void load();
  }, [load]);

  const refreshDetail = useCallback(async (id: string) => {
    setError('');
    try {
      setDetail(await fetchAllocationDetail(id));
    } catch (e) {
      setError(e instanceof Error ? e.message : '分摊单明细读不到');
    }
  }, []);

  const openDetail = useCallback(
    async (id: string) => {
      setSelectedId(id);
      setPreview(null);
      setAdjustments([]);
      setRecalcRule('');
      setRecalcTotal('');
      setRecalcReason('');
      await refreshDetail(id);
    },
    [refreshDetail],
  );

  const submitCreate = useCallback(async () => {
    const source = sources?.items.find((s) => s.sourceRef === sourceRef);
    const input = {
      sourceType: 'FIRST_MILE_SHIPMENT' as const,
      sourceRef,
      sourceLabel: source?.sourceLabel ?? '',
      currency: source?.currency ?? '',
      totalCost,
      basisRule,
      effectiveFrom,
      changeReason,
    };
    const problems = validateCreate(input);
    setCreateProblems(problems);
    if (problems.length) return;
    setBusy(true);
    setError('');
    try {
      const saved = await createAllocation(input);
      setNotice(
        `已建分摊单（第 ${saved.revisionNo} 版）：逐行分摊额之和 ${saved.allocatedTotal}，与货件成本总额一致。`,
      );
      setSourceRef('');
      setTotalCost('');
      setChangeReason('');
      await load();
      await openDetail(saved.allocationId);
    } catch (e) {
      setError(e instanceof Error ? e.message : '新建分摊单失败');
    } finally {
      setBusy(false);
    }
  }, [basisRule, changeReason, effectiveFrom, load, openDetail, sourceRef, sources, totalCost]);

  const recalcInput = useMemo(
    () => ({
      basisRule: recalcRule,
      totalCost: recalcTotal,
      effectiveFrom: recalcEffectiveFrom,
      changeReason: recalcReason,
      adjustments,
    }),
    [adjustments, recalcEffectiveFrom, recalcReason, recalcRule, recalcTotal],
  );

  const runPreview = useCallback(async () => {
    if (!selectedId) return;
    const problems = validateRecalculate(recalcInput);
    setRecalcProblems(problems);
    if (problems.length) return;
    setBusy(true);
    setError('');
    try {
      setPreview(await previewAllocationRecalculation(selectedId, recalcInput));
      setNotice('试算完成 —— **没有落库**。看清差异后再决定要不要应用。');
    } catch (e) {
      setError(e instanceof Error ? e.message : '试算失败');
    } finally {
      setBusy(false);
    }
  }, [recalcInput, selectedId]);

  const runApply = useCallback(async () => {
    if (!selectedId) return;
    const problems = validateRecalculate(recalcInput, {
      requireReason: true,
      requireEffectiveFrom: true,
    });
    setRecalcProblems(problems);
    if (problems.length) return;
    setBusy(true);
    setError('');
    try {
      const saved = await applyAllocationRecalculation(selectedId, recalcInput);
      setNotice(
        `已追加第 ${saved.revisionNo} 版 —— **旧版本原样留着**（规则变更会让历史利润变化，所以必须留版本）。`,
      );
      setPreview(null);
      setAdjustments([]);
      setRecalcRule('');
      setRecalcTotal('');
      setRecalcReason('');
      await load();
      await refreshDetail(selectedId);
    } catch (e) {
      setError(e instanceof Error ? e.message : '应用重算失败');
    } finally {
      setBusy(false);
    }
  }, [load, recalcInput, refreshDetail, selectedId]);

  const variance = useMemo(() => varianceText(detail?.currentRevision?.variance ?? null), [detail]);
  const pendingSources = (sources?.items ?? []).filter((s) => !s.alreadyAllocated);

  return (
    <div className="space-y-4">
      {/* ============================================================ 能力声明 */}
      <SectionCard
        title="成本分摊口径（必看）"
        description="蓝图加粗句：分摊规则变更会导致历史利润变化，因此调整必须留历史版本，不能就地覆盖。"
      >
        {capabilities ? (
          <div className="space-y-4">
            <Notice tone="warning" title="四条口径，一页说清">
              <div className="space-y-1">
                <p>
                  <b>规则</b>：{capabilities.rules.map((r) => r.label).join(' / ')}。
                  规则不是页面上的装饰 —— 它真的参与算术：同一批货，换一条规则，每个 SKU 的分摊额就变。
                </p>
                <p>
                  <b>基数</b>：{capabilities.basisNote}
                </p>
              </div>
            </Notice>
            <div>
              <p className="text-label-13 mb-2 font-semibold">四种分摊规则</p>
              <div className="grid gap-2 md:grid-cols-4">
                {capabilities.rules.map((r) => (
                  <div key={r.value} className="border-border rounded-md border p-3">
                    <div className="flex items-center gap-2">
                      <Badge tone="brand">{r.label}</Badge>
                      <code className="text-copy-12 text-muted-foreground">{r.value}</code>
                    </div>
                    <p className="text-copy-12 text-muted-foreground mt-2">{r.basis}</p>
                  </div>
                ))}
              </div>
            </div>
            <div>
              <p className="text-label-13 mb-2 font-semibold">两种来源</p>
              <div className="grid gap-2 md:grid-cols-2">
                {capabilities.sources.map((s) => (
                  <div key={s.value} className="border-border rounded-md border p-3">
                    <div className="flex items-center gap-2">
                      <Badge tone="info">{s.label}</Badge>
                      <code className="text-copy-12 text-muted-foreground">{s.value}</code>
                    </div>
                    <p className="text-copy-12 text-muted-foreground mt-1">{s.basis}</p>
                  </div>
                ))}
              </div>
            </div>
            <div className="grid gap-2 md:grid-cols-2">
              <Notice tone="info" title="上游真值">
                {capabilities.upstreamNote}
              </Notice>
              <Notice tone="info" title="版本纪律">
                {capabilities.historyNote}
              </Notice>
              <Notice tone="info" title="下游与读法">
                {capabilities.downstreamNote}
              </Notice>
              <Notice tone="info" title="可做的动作">
                {capabilities.actionsNote}
              </Notice>
            </div>
          </div>
        ) : busy && !loaded ? (
          <p className="text-muted-foreground text-copy-13">正在读能力声明……</p>
        ) : (
          <p className="text-muted-foreground text-copy-13">能力声明读不到。</p>
        )}
      </SectionCard>

      {error ? (
        <Notice tone="danger" title="成本分摊出错了">
          {error}
        </Notice>
      ) : null}
      {notice ? (
        <Notice tone="success" title="已处理">
          {notice}
        </Notice>
      ) : null}

      {/* ============================================================ 新建 */}
      <SectionCard
        title="新建分摊单"
        description="选一张货件 + 选一条规则 + 填货件成本总额，落第一个版本。一个货件只能摊一次。"
      >
        <div className="grid gap-3 md:grid-cols-4">
          <FormField id="alloc-source" label="货件（头程发运单）" hint="一张单里多个 SKU 才是混合成本货件">
            <select
              id="alloc-source"
              className="border-input h-9 w-full rounded-md border px-2 text-sm"
              value={sourceRef}
              onChange={(e) => {
                setSourceRef(e.target.value);
                const picked = sources?.items.find((s) => s.sourceRef === e.target.value);
                if (picked?.totalCost) setTotalCost(picked.totalCost);
              }}
            >
              <option value="">选一张发运单</option>
              {pendingSources.map((s) => (
                <option key={s.sourceRef} value={s.sourceRef}>
                  {s.sourceLabel || s.sourceRef}（{s.lineCount} 行 · {s.currency}{' '}
                  {moneyText(s.totalCost)}）
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="alloc-total" label="货件成本总额" hint="留空则取发运单的可分摊成本">
            <Input
              id="alloc-total"
              value={totalCost}
              placeholder="0.0000"
              onChange={(e) => setTotalCost(e.target.value)}
            />
          </FormField>
          <FormField id="alloc-rule" label="分摊规则" hint="口径要明示，不能留空">
            <select
              id="alloc-rule"
              className="border-input h-9 w-full rounded-md border px-2 text-sm"
              value={basisRule}
              onChange={(e) => setBasisRule(e.target.value as BasisRule)}
            >
              {(capabilities?.rules ?? []).map((r) => (
                <option key={r.value} value={r.value}>
                  {r.label}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="alloc-from" label="生效时间" hint="这一版从哪一刻开始算数">
            <Input
              id="alloc-from"
              type="datetime-local"
              value={effectiveFrom}
              onChange={(e) => setEffectiveFrom(e.target.value)}
            />
          </FormField>
        </div>
        <div className="mt-3">
          <FormField
            id="alloc-reason"
            label="变更原因"
            hint="必填：没有理由的历史等于没有历史"
            required
          >
            <Input
              id="alloc-reason"
              value={changeReason}
              placeholder="例如：这一票按重量摊，重货占大头"
              onChange={(e) => setChangeReason(e.target.value)}
            />
          </FormField>
        </div>
        {createProblems.length ? (
          <div className="mt-3 space-y-1">
            {createProblems.map((p) => (
              <p key={p} className="text-destructive text-copy-12">
                · {p}
              </p>
            ))}
          </div>
        ) : null}
        <div className="mt-3 flex items-center gap-2">
          <Button onClick={submitCreate} disabled={busy || !sourceRef}>
            建分摊单
          </Button>
          {sources?.truncated ? (
            <span className="text-copy-12 text-warning-foreground">
              🔴 {sources.note}
            </span>
          ) : null}
        </div>
      </SectionCard>

      {/* ============================================================ 台账 */}
      <SectionCard title="分摊单台账" description={page?.note ?? '按当前版本口径列出的分摊单'}>
        <div className="grid gap-3 md:grid-cols-4">
          <FormField id="alloc-kw" label="关键词" hint="货件号 / 来源标识">
            <Input
              id="alloc-kw"
              value={filters.keyword}
              placeholder="全部"
              onChange={(e) => setFilters({ ...filters, keyword: e.target.value, page: 0 })}
            />
          </FormField>
          <FormField id="alloc-type" label="来源" hint="不选 = 全部">
            <select
              id="alloc-type"
              className="border-input h-9 w-full rounded-md border px-2 text-sm"
              value={filters.sourceType}
              onChange={(e) => setFilters({ ...filters, sourceType: e.target.value, page: 0 })}
            >
              <option value="">全部</option>
              <option value="FIRST_MILE_SHIPMENT">头程发运单</option>
              <option value="MANUAL">人工货件</option>
            </select>
          </FormField>
          <FormField id="alloc-cur" label="币种" hint="跨币种绝不相加">
            <Input
              id="alloc-cur"
              value={filters.currency}
              placeholder="全部"
              onChange={(e) => setFilters({ ...filters, currency: e.target.value, page: 0 })}
            />
          </FormField>
          <FormField id="alloc-rule-filter" label="规则" hint="不选 = 全部">
            <select
              id="alloc-rule-filter"
              className="border-input h-9 w-full rounded-md border px-2 text-sm"
              value={filters.basisRule}
              onChange={(e) => setFilters({ ...filters, basisRule: e.target.value, page: 0 })}
            >
              <option value="">全部</option>
              {(capabilities?.rules ?? []).map((r) => (
                <option key={r.value} value={r.value}>
                  {r.label}
                </option>
              ))}
            </select>
          </FormField>
        </div>

        {busy && !loaded ? (
          <p className="text-muted-foreground text-copy-13 mt-4">正在读分摊单台账……</p>
        ) : !page || page.items.length === 0 ? (
          <EmptyState
            title="还没有分摊单"
            description="用上面的「新建分摊单」给一张混合成本的货件摊一次 —— 头程运费是几个 SKU 一起花的。"
          />
        ) : (
          <>
            <div className="mt-4 overflow-x-auto">
              <table className="w-full text-left">
                <thead>
                  <tr className="border-border border-b text-copy-12 text-muted-foreground">
                    <th className="p-2">货件</th>
                    <th className="p-2">规则</th>
                    <th className="p-2">币种</th>
                    <th className="p-2">货件成本总额</th>
                    <th className="p-2">计算分摊合计</th>
                    <th className="p-2">最终合计</th>
                    <th className="p-2">差额</th>
                    <th className="p-2">版本</th>
                    <th className="p-2" />
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((row) => (
                    <AllocationRowView
                      key={row.allocationId}
                      row={row}
                      active={row.allocationId === selectedId}
                      onOpen={() => openDetail(row.allocationId)}
                    />
                  ))}
                </tbody>
              </table>
            </div>
            <div className="text-copy-12 text-muted-foreground mt-3 flex items-center gap-3">
              <span>
                共 {page.total} 张，第 {page.page + 1} 页
              </span>
              <Button
                size="sm"
                variant="ghost"
                disabled={page.page <= 0}
                onClick={() => setFilters({ ...filters, page: filters.page - 1 })}
              >
                上一页
              </Button>
              <Button
                size="sm"
                variant="ghost"
                disabled={(page.page + 1) * page.size >= page.total}
                onClick={() => setFilters({ ...filters, page: filters.page + 1 })}
              >
                下一页
              </Button>
            </div>
          </>
        )}
      </SectionCard>

      {/* ============================================================ 明细 */}
      {detail ? (
        <>
          <SectionCard
            title={`分摊明细 · ${detail.sourceLabel || detail.sourceRef}`}
            description={detail.note}
          >
            <div className="grid gap-2 md:grid-cols-5">
              <StatCard
                label="货件成本总额"
                value={moneyWithCurrency(
                  detail.currentRevision?.totalCost ?? null,
                  detail.currentRevision?.currency ?? null,
                )}
              />
              <StatCard
                label="计算分摊合计"
                value={moneyWithCurrency(
                  detail.currentRevision?.allocatedTotal ?? null,
                  detail.currentRevision?.currency ?? null,
                )}
              />
              <StatCard
                label="人工微调合计"
                value={moneyWithCurrency(
                  detail.currentRevision?.adjustmentTotal ?? null,
                  detail.currentRevision?.currency ?? null,
                )}
              />
              <StatCard
                label="最终分摊合计"
                value={moneyWithCurrency(
                  detail.currentRevision?.finalTotal ?? null,
                  detail.currentRevision?.currency ?? null,
                )}
              />
              <StatCard
                label="当前规则"
                value={
                  detail.currentRevision
                    ? `${detail.currentRevision.basisRuleLabel}（第 ${detail.currentRevision.revisionNo} 版）`
                    : '—'
                }
              />
            </div>

            <div className="mt-3">
              <Notice tone={variance.tone} title="差额">
                {variance.text}
              </Notice>
            </div>

            <div className="mt-3">
              <Notice tone="info" title="这一版的口径">
                <div className="space-y-1">
                  <p>
                    规则：{detail.currentRevision?.basisRuleLabel ?? '—'} ——{' '}
                    {basisRuleBasis(detail.currentRevision?.basisRule)}
                  </p>
                  <p>
                    生效时间：{timeText(detail.currentRevision?.effectiveFrom)}｜变更原因：
                    {detail.currentRevision?.changeReason ?? '—'}
                  </p>
                  <p>
                    参与分摊的行：{detail.currentRevision?.eligibleLineCount ?? 0} /{' '}
                    {detail.currentRevision?.lineCount ?? 0}
                    {detail.currentRevision && detail.currentRevision.eligibleLineCount <
                    detail.currentRevision.lineCount
                      ? '（其余行基数不可得，未参与分摊）'
                      : ''}
                  </p>
                </div>
              </Notice>
            </div>

            {detail.lines.length === 0 ? (
              <EmptyState title="这一版还没有明细行" description="重算一次就会按规则摊出来。" />
            ) : (
              <div className="mt-4 overflow-x-auto">
                <table className="w-full text-left">
                  <thead>
                    <tr className="border-border border-b text-copy-12 text-muted-foreground">
                      <th className="p-2">SKU</th>
                      <th className="p-2">数量</th>
                      <th className="p-2">基数</th>
                      <th className="p-2">基数来源</th>
                      <th className="p-2">计算分摊额</th>
                      <th className="p-2">人工微调</th>
                      <th className="p-2">最终分摊额</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.lines.map((line) => (
                      <tr key={line.skuId} className="border-b">
                        <td className="p-2">
                          <div className="font-mono">{line.skuCode}</div>
                          <div className="text-copy-12 text-muted-foreground">{line.skuName}</div>
                        </td>
                        <td className="p-2 font-mono">{moneyText(line.baseQuantity, '—')}</td>
                        <td className="p-2 font-mono">
                          {line.basisAvailable ? (
                            moneyText(line.basisValue, '—')
                          ) : (
                            <span className="text-muted-foreground">基数不可得</span>
                          )}
                        </td>
                        <td className="p-2 text-copy-12 text-muted-foreground">
                          {basisNoteText(line)}
                        </td>
                        <td className="p-2 font-mono">
                          {moneyWithCurrency(line.allocatedAmount, detail.currentRevision?.currency)}
                        </td>
                        <td className="p-2 font-mono">
                          {Number(line.adjustmentAmount) === 0 ? (
                            <span className="text-muted-foreground">—</span>
                          ) : (
                            <>
                              {moneyWithCurrency(
                                line.adjustmentAmount,
                                detail.currentRevision?.currency,
                              )}
                              <div className="text-copy-12 text-muted-foreground">
                                {line.adjustmentReason}
                              </div>
                            </>
                          )}
                        </td>
                        <td className="p-2 font-mono">
                          {moneyWithCurrency(line.finalAmount, detail.currentRevision?.currency)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </SectionCard>

          {/* ========================================================== 与上一版的差异 */}
          {detail.lastDiff ? (
            <SectionCard
              title="与上一版的差异"
              description="规则变更会让历史利润变化 —— 这就是必须留版本的原因。"
            >
              <DiffView
                diff={detail.lastDiff}
                currency={detail.currentRevision?.currency ?? null}
              />
            </SectionCard>
          ) : null}

          {/* ========================================================== 重算 */}
          <SectionCard
            title="重算与人工微调"
            description="换规则 / 改总额 / 人工微调都会**追加一个新版本**，旧版本原样留着。先试算，看清差异再应用。"
          >
            <div className="grid gap-3 md:grid-cols-4">
              <FormField id="recalc-rule" label="新规则" hint="留空 = 沿用当前规则">
                <select
                  id="recalc-rule"
                  className="border-input h-9 w-full rounded-md border px-2 text-sm"
                  value={recalcRule}
                  onChange={(e) => setRecalcRule(e.target.value)}
                >
                  <option value="">沿用当前（{detail.currentRevision?.basisRuleLabel ?? '—'}）</option>
                  {(capabilities?.rules ?? []).map((r) => (
                    <option key={r.value} value={r.value}>
                      {r.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="recalc-total" label="新货件成本总额" hint="留空 = 沿用当前版本">
                <Input
                  id="recalc-total"
                  value={recalcTotal}
                  placeholder={detail.currentRevision?.totalCost ?? '0.0000'}
                  onChange={(e) => setRecalcTotal(e.target.value)}
                />
              </FormField>
              <FormField id="recalc-from" label="生效时间" hint="这一版从哪一刻开始算数">
                <Input
                  id="recalc-from"
                  type="datetime-local"
                  value={recalcEffectiveFrom}
                  onChange={(e) => setRecalcEffectiveFrom(e.target.value)}
                />
              </FormField>
              <FormField id="recalc-reason" label="变更原因" hint="应用时必填">
                <Input
                  id="recalc-reason"
                  value={recalcReason}
                  placeholder="例如：改按金额摊"
                  onChange={(e) => setRecalcReason(e.target.value)}
                />
              </FormField>
            </div>

            <div className="mt-4">
              <div className="flex items-center justify-between">
                <p className="text-label-13 font-semibold">人工微调（逐行，原因必填）</p>
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() =>
                    setAdjustments([
                      ...adjustments,
                      { skuId: '', amount: '', reason: '' },
                    ])
                  }
                >
                  加一行微调
                </Button>
              </div>
              {adjustments.length === 0 ? (
                <p className="text-copy-12 text-muted-foreground mt-1">
                  没有微调。微调是**相对计算分摊额的增减**，会让「逐行最终额之和」与货件成本总额产生差额 ——
                  那个差额会显式显示出来。
                </p>
              ) : (
                <div className="mt-2 space-y-2">
                  {adjustments.map((adjustment, index) => (
                    <div key={index} className="grid gap-2 md:grid-cols-4">
                      <FormField id={`adj-sku-${index}`} label="SKU" hint="填明细里的 SKU id">
                        <Input
                          id={`adj-sku-${index}`}
                          value={adjustment.skuId}
                          onChange={(e) => {
                            const next = [...adjustments];
                            next[index] = { ...adjustment, skuId: e.target.value };
                            setAdjustments(next);
                          }}
                        />
                      </FormField>
                      <FormField id={`adj-amount-${index}`} label="调整额" hint="可正可负，不能为 0">
                        <Input
                          id={`adj-amount-${index}`}
                          value={adjustment.amount}
                          placeholder="0.00"
                          onChange={(e) => {
                            const next = [...adjustments];
                            next[index] = { ...adjustment, amount: e.target.value };
                            setAdjustments(next);
                          }}
                        />
                      </FormField>
                      <FormField id={`adj-reason-${index}`} label="原因" hint="必填" required>
                        <Input
                          id={`adj-reason-${index}`}
                          value={adjustment.reason}
                          placeholder="例如：A 实际重了一档"
                          onChange={(e) => {
                            const next = [...adjustments];
                            next[index] = { ...adjustment, reason: e.target.value };
                            setAdjustments(next);
                          }}
                        />
                      </FormField>
                      <div className="flex items-end">
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() =>
                            setAdjustments(adjustments.filter((_, i) => i !== index))
                          }
                        >
                          删掉这行
                        </Button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>

            {recalcProblems.length ? (
              <div className="mt-3 space-y-1">
                {recalcProblems.map((p) => (
                  <p key={p} className="text-destructive text-copy-12">
                    · {p}
                  </p>
                ))}
              </div>
            ) : null}

            <div className="mt-3 flex flex-wrap items-center gap-2">
              <Button variant="ghost" onClick={runPreview} disabled={busy}>
                试算（不落库）
              </Button>
              <Button onClick={runApply} disabled={busy}>
                应用重算（追加新版本）
              </Button>
            </div>

            {preview ? (
              <div className="mt-4 space-y-2">
                <Notice tone="info" title="试算结果">
                  {preview.note}
                </Notice>
                <DiffView diff={preview.diff} currency={preview.currency} />
              </div>
            ) : null}
          </SectionCard>

          {/* ========================================================== 版本历史 */}
          <SectionCard
            title="版本历史"
            description="历史不可变 —— 每一版都是当时的快照，没有 UPDATE 端点，也永远不会有。"
          >
            <div className="overflow-x-auto">
              <table className="w-full text-left">
                <thead>
                  <tr className="border-border border-b text-copy-12 text-muted-foreground">
                    <th className="p-2">版本</th>
                    <th className="p-2">规则</th>
                    <th className="p-2">货件成本总额</th>
                    <th className="p-2">最终分摊合计</th>
                    <th className="p-2">差额</th>
                    <th className="p-2">生效时间</th>
                    <th className="p-2">变更原因</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.revisions.map((revision) => (
                    <tr key={revision.revisionId} className="border-b">
                      <td className="p-2">第 {revision.revisionNo} 版</td>
                      <td className="p-2">
                        <Badge tone="brand">{revision.basisRuleLabel}</Badge>
                      </td>
                      <td className="p-2 font-mono">
                        {moneyWithCurrency(revision.totalCost, revision.currency)}
                      </td>
                      <td className="p-2 font-mono">
                        {moneyWithCurrency(revision.finalTotal, revision.currency)}
                      </td>
                      <td className="p-2 font-mono">
                        {moneyWithCurrency(revision.variance, revision.currency, '0.00')}
                      </td>
                      <td className="p-2">{timeText(revision.effectiveFrom)}</td>
                      <td className="p-2 text-copy-12 text-muted-foreground">
                        {revision.changeReason}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </SectionCard>
        </>
      ) : null}
    </div>
  );
}

/** 台账里的一行。 */
function AllocationRowView({
  row,
  active,
  onOpen,
}: {
  row: PricingCostAllocationRow;
  active: boolean;
  onOpen: () => void;
}) {
  return (
    <tr className="border-b">
      <td className="p-2">
        <div className="font-mono">{row.sourceLabel || row.sourceRef}</div>
        <div className="text-copy-12 text-muted-foreground">
          {sourceTypeLabel(row.sourceType)} · {row.sourceRef}
        </div>
      </td>
      <td className="p-2">
        {row.basisRule ? (
          <Badge tone="brand">{row.basisRuleLabel ?? basisRuleLabel(row.basisRule)}</Badge>
        ) : (
          <span className="text-muted-foreground">—</span>
        )}
      </td>
      <td className="p-2">{row.currency ?? '—'}</td>
      <td className="p-2 font-mono">{moneyWithCurrency(row.totalCost, row.currency)}</td>
      <td className="p-2 font-mono">{moneyWithCurrency(row.allocatedTotal, row.currency)}</td>
      <td className="p-2 font-mono">{moneyWithCurrency(row.finalTotal, row.currency)}</td>
      <td className="p-2 font-mono">
        {moneyWithCurrency(row.variance, row.currency, '0.00')}
      </td>
      <td className="p-2">
        第 {row.revisionNo ?? 1} 版 / 共 {row.revisionCount} 版
      </td>
      <td className="p-2">
        <Button size="sm" variant={active ? 'default' : 'ghost'} onClick={onOpen}>
          {active ? '已展开' : '查看明细'}
        </Button>
      </td>
    </tr>
  );
}

/** 差异对比。 */
function DiffView({
  diff,
  currency,
}: {
  diff: {
    ruleChanged: boolean;
    beforeRule: string;
    afterRule: string;
    beforeTotal: string;
    afterTotal: string;
    totalDelta: string;
    changedCount: number;
    rows: {
      skuId: string;
      skuCode: string;
      skuName: string;
      change: string;
      beforeAmount: string | null;
      afterAmount: string | null;
      delta: string | null;
      note: string;
    }[];
    note: string;
  };
  currency: string | null;
}) {
  return (
    <div className="space-y-2">
      <Notice tone={diff.ruleChanged ? 'warning' : 'info'} title="差异">
        <div className="space-y-1">
          <p>
            规则：{basisRuleLabel(diff.beforeRule)} → {basisRuleLabel(diff.afterRule)}
            {diff.ruleChanged ? '（**规则变了**）' : '（规则没变）'}｜合计：
            {moneyWithCurrency(diff.beforeTotal, currency)} →{' '}
            {moneyWithCurrency(diff.afterTotal, currency)}（差额{' '}
            {moneyWithCurrency(diff.totalDelta, currency, '0.00')}）｜变了 {diff.changedCount} 行
          </p>
          <p>{diff.note}</p>
        </div>
      </Notice>
      <div className="overflow-x-auto">
        <table className="w-full text-left">
          <thead>
            <tr className="border-border border-b text-copy-12 text-muted-foreground">
              <th className="p-2">SKU</th>
              <th className="p-2">变化</th>
              <th className="p-2">旧值</th>
              <th className="p-2">新值</th>
              <th className="p-2">差额</th>
            </tr>
          </thead>
          <tbody>
            {diff.rows.map((row) => (
              <tr key={row.skuId} className="border-b">
                <td className="p-2">
                  <div className="font-mono">{row.skuCode}</div>
                  <div className="text-copy-12 text-muted-foreground">{row.skuName}</div>
                </td>
                <td className="p-2">
                  <Badge tone={diffTone(row.change)}>{diffChangeLabel(row.change)}</Badge>
                </td>
                <td className="p-2 font-mono">{moneyWithCurrency(row.beforeAmount, currency, '—')}</td>
                <td className="p-2 font-mono">{moneyWithCurrency(row.afterAmount, currency, '—')}</td>
                <td className="p-2 font-mono">
                  {moneyWithCurrency(row.delta, currency, '—')}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/** 当前时刻（本地时区，秒精度）—— 表单默认的生效时间。 */
function nowLocal(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}T${pad(
    now.getHours(),
  )}:${pad(now.getMinutes())}:00`;
}
