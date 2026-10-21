'use client';

import { useCallback, useEffect, useState } from 'react';
import { PackageSearch, RefreshCw } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  FormField,
  Input,
  PageHeader,
  SectionCard,
  StatCard,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@starcon/ui';

import {
  canCreateRequest,
  collectedAtText,
  confidenceLabel,
  confidenceTone,
  createSourceAlternativePurchaseRequest,
  createSourceAlternativeRun,
  decideSourceAlternativeCandidate,
  decisionLabel,
  EMPTY_SOURCE_ALTERNATIVE_FILTERS,
  fetchSourceAlternativeCapabilities,
  fetchSourceAlternativeOptions,
  fetchSourceAlternativeOverview,
  fetchSourceAlternativePolicy,
  fetchSourceAlternativeRun,
  fetchSourceAlternativeRuns,
  fetchSourceAlternativeTargets,
  leadTimeLabel,
  matchTypeLabel,
  moqLabel,
  previewSourceAlternativeCandidates,
  priceRangeLabel,
  ratingLabel,
  requestBlockedReason,
  saveSourceAlternativePolicy,
  scoreLabel,
  toPolicyForm,
  validateDecisionForm,
  validatePolicyForm,
  validateRequestForm,
  type SourceAlternativeCandidate,
  type SourceAlternativeCapabilities,
  type SourceAlternativeOptions,
  type SourceAlternativeOverview,
  type SourceAlternativePolicy,
  type SourceAlternativePolicyForm,
  type SourceAlternativeRun,
  type SourceAlternativeRunDetail,
  type SourceAlternativeRunPage,
  type SourceAlternativeTargetOptions,
} from './source-alternative-model';

/**
 * 6.1.2.2 同款货源推荐。
 *
 * <p>🔴 页面顶部那句话不是提示语，是这一页的设计前提：**同款判定要给置信度**，
 * 图片相似不等于同款，误判会导致采购到错误商品。
 *
 * <p>所以这一页的三条硬规矩：
 *
 * <ol>
 *   <li><b>置信度与排序分并排显示，且标签写清楚它们不是一回事</b> ——
 *       置信度回答「是不是同款」，排序分回答「先看哪一条」。折叠成一个「推荐度」，
 *       运营就会把「便宜」误读成「同一个货」。</li>
 *   <li><b>每条候选都写着判定依据</b>（凭什么算成候选），没依据的货根本不该出现在清单里；
 *       疑似同款必须人工确认才能生成采购申请，没确认时按钮是灰的，**并说清为什么灰**。</li>
 *   <li><b>价格永远是区间 + 采集时刻</b>，没有精确价；历史交期没有就是「暂无」；
 *       评级的客观分与最终分并排，人工调整过的单独标记；
 *       生成申请时**不填预估单价** —— 快照价只是参考，实际下单金额以后续采购单为准。</li>
 * </ol>
 */
export function SourceAlternativeWorkspace() {
  const [capabilities, setCapabilities] = useState<SourceAlternativeCapabilities | null>(null);
  const [options, setOptions] = useState<SourceAlternativeOptions | null>(null);
  const [targets, setTargets] = useState<SourceAlternativeTargetOptions | null>(null);
  const [overview, setOverview] = useState<SourceAlternativeOverview | null>(null);
  const [policy, setPolicy] = useState<SourceAlternativePolicy | null>(null);
  const [policyForm, setPolicyForm] = useState<SourceAlternativePolicyForm | null>(null);
  const [page, setPage] = useState<SourceAlternativeRunPage | null>(null);
  const [detail, setDetail] = useState<SourceAlternativeRunDetail | null>(null);
  const [filters, setFilters] = useState(EMPTY_SOURCE_ALTERNATIVE_FILTERS);
  const [newRun, setNewRun] = useState({ skuId: '', reason: 'STOCKOUT', quantity: '', note: '' });
  const [previewRows, setPreviewRows] = useState<SourceAlternativeCandidate[] | null>(null);
  const [previewing, setPreviewing] = useState(false);
  const [decision, setDecision] = useState({ type: 'CONFIRMED' as 'CONFIRMED' | 'REJECTED', reason: '' });
  const [deciding, setDeciding] = useState<SourceAlternativeCandidate | null>(null);
  const [requesting, setRequesting] = useState<SourceAlternativeCandidate | null>(null);
  const [requestForm, setRequestForm] = useState({
    quantity: '',
    targetWarehouseId: '',
    legalEntityId: '',
    note: '',
  });
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const [next, opts, caps, ov, pol, tg] = await Promise.all([
        fetchSourceAlternativeRuns(filters),
        fetchSourceAlternativeOptions(),
        fetchSourceAlternativeCapabilities(),
        fetchSourceAlternativeOverview(),
        fetchSourceAlternativePolicy(),
        fetchSourceAlternativeTargets(),
      ]);
      setPage(next);
      setOptions(opts);
      setCapabilities(caps);
      setOverview(ov);
      setPolicy(pol);
      setPolicyForm(toPolicyForm(pol));
      setTargets(tg);
    } catch (e) {
      setError(e instanceof Error ? e.message : '同款货源推荐读不到');
    } finally {
      setBusy(false);
      setLoading(false);
    }
  }, [filters]);

  useEffect(() => {
    void load();
  }, [load]);

  async function openRun(run: SourceAlternativeRun) {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      setDetail(await fetchSourceAlternativeRun(run.id));
    } catch (e) {
      setError(e instanceof Error ? e.message : '推荐批次读不到');
    } finally {
      setBusy(false);
    }
  }

  async function previewRun() {
    if (!newRun.skuId.trim()) {
      setError('必须填内部 SKU 的 id（商品主数据在 catalog，本服务只经授权只读接口取）');
      return;
    }
    setPreviewing(true);
    setError('');
    setNotice('');
    try {
      const rows = await previewSourceAlternativeCandidates(newRun.skuId.trim());
      setPreviewRows(rows);
      setNotice(
        rows.length === 0
          ? '这个 SKU 暂时没有候选：既没有人工确认的同款关联，供应商也没采购过这个货，标题与规格也对不上。'
          : `预览 ${rows.length} 条候选（**未落库**）：正式发起推荐后它们会连同采集时刻一起落成快照。`,
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : '候选预览失败');
    } finally {
      setPreviewing(false);
    }
  }

  async function submitRun() {
    if (!newRun.skuId.trim()) {
      setError('必须填内部 SKU 的 id（商品主数据在 catalog，本服务只经授权只读接口取）');
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const created = await createSourceAlternativeRun({
        skuId: newRun.skuId.trim(),
        reason: newRun.reason as 'STOCKOUT' | 'QUALITY',
        requiredQuantity: newRun.quantity,
        note: newRun.note,
      });
      setDetail(created);
      setNotice(
        `已生成推荐批次 ${created.run.runNo}：候选 ${created.candidates.length} 条` +
          `（确证 ${created.candidates.filter((c) => !c.requiresManualConfirmation).length} 条、` +
          `需人工确认 ${created.candidates.filter((c) => c.requiresManualConfirmation).length} 条）。`,
      );
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '推荐失败');
    } finally {
      setBusy(false);
    }
  }

  async function submitDecision(candidate: SourceAlternativeCandidate) {
    const problems = validateDecisionForm(decision.type, decision.reason);
    if (problems.length > 0) {
      setError(problems.join('；'));
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await decideSourceAlternativeCandidate(candidate.id as string, {
        decisionType: decision.type,
        reason: decision.reason,
      });
      setDeciding(null);
      setDecision({ type: 'CONFIRMED', reason: '' });
      setNotice(
        decision.type === 'CONFIRMED'
          ? `已确认「${candidate.title}」是同款。这条候选现在可以生成采购申请。`
          : `已否决「${candidate.title}」，理由已留痕。`,
      );
      if (detail) setDetail(await fetchSourceAlternativeRun(detail.run.id));
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '判定失败');
    } finally {
      setBusy(false);
    }
  }

  async function submitRequest(candidate: SourceAlternativeCandidate) {
    const problems = validateRequestForm(
      requestForm.quantity,
      requestForm.targetWarehouseId,
      requestForm.legalEntityId,
      candidate.moq,
    );
    if (problems.length > 0) {
      setError(problems.join('；'));
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await createSourceAlternativePurchaseRequest(candidate.id as string, {
        quantity: requestForm.quantity,
        targetWarehouseId: requestForm.targetWarehouseId,
        legalEntityId: requestForm.legalEntityId,
        note: requestForm.note,
      });
      setRequesting(null);
      setRequestForm({ quantity: '', targetWarehouseId: '', legalEntityId: '', note: '' });
      setNotice(
        `已生成采购申请 ${result.purchaseRequestNo}（草稿）。` +
          '货源快照价只是参考，**实际下单金额以后续采购单为准** —— 申请行没有填预估单价。',
      );
      if (detail) setDetail(await fetchSourceAlternativeRun(detail.run.id));
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '生成采购申请失败');
    } finally {
      setBusy(false);
    }
  }

  async function submitPolicy(form: SourceAlternativePolicyForm) {
    const problems = validatePolicyForm(form);
    if (problems.length > 0) {
      setError(problems.join('；'));
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const saved = await saveSourceAlternativePolicy(form);
      setPolicy(saved);
      setPolicyForm(toPolicyForm(saved));
      setNotice('规则已保存。下次推荐按新门槛与新权重算。');
    } catch (e) {
      setError(e instanceof Error ? e.message : '规则保存失败');
    } finally {
      setBusy(false);
    }
  }

  const totalPages = page ? Math.max(Math.ceil(page.total / Math.max(page.size, 1)), 1) : 1;

  return (
    <>
      <PageHeader
        title="同款货源推荐"
        description="当某个 SKU 缺货或质量不达标时，找出可替代的同款/相似货源：已确认的同款、历史采购过该货的供应商、价格对比。每条候选都带同款置信度与判定依据 —— 图片相似不等于同款，误判会把采购单指向错误商品。"
      />

      {error ? <p className="text-destructive text-sm">{error}</p> : null}
      {notice ? <p className="text-muted-foreground text-sm">{notice}</p> : null}
      {loading ? <p className="text-muted-foreground text-sm">正在读同款货源推荐…</p> : null}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="推荐批次"
          value={String(overview?.runCount ?? 0)}
          comparisonLabel={`待处置 ${overview?.openRunCount ?? 0} 个`}
        />
        <StatCard
          label="候选货源"
          value={String(overview?.candidateCount ?? 0)}
          comparisonLabel={`其中 ${overview?.weakCandidateCount ?? 0} 条需人工确认`}
        />
        <StatCard
          label="已人工确认"
          value={String(overview?.confirmedCandidateCount ?? 0)}
          comparisonLabel="确认过才允许生成采购申请"
        />
        <StatCard
          label="已生成采购申请"
          value={String(overview?.requestCount ?? 0)}
          comparisonLabel="草稿单，实际金额以采购单为准"
        />
      </div>

      <Tabs defaultValue="runs" className="mt-6">
        <TabsList>
          <TabsTrigger value="runs">推荐批次</TabsTrigger>
          <TabsTrigger value="new">发起推荐</TabsTrigger>
          <TabsTrigger value="policy">规则与权重</TabsTrigger>
          <TabsTrigger value="bounds">能力与边界</TabsTrigger>
        </TabsList>

        {/* ============================================================ 推荐批次 */}
        <TabsContent value="runs">
          <SectionCard
            title="筛选"
            description="批次列表是真分页真筛选。点一行看候选与判定留痕。"
          >
            <div className="grid gap-3 md:grid-cols-4">
              <FormField id="sku" label="内部 SKU id" hint="catalog 的 SKU 逻辑 id">
                <Input
                  id="sku"
                  value={filters.skuId}
                  placeholder="全部"
                  onChange={(e) =>
                    setFilters({ ...filters, skuId: e.target.value.trim(), page: 0 })
                  }
                />
              </FormField>
              <FormField id="reason" label="推荐理由">
                <select
                  id="reason"
                  className="border-input h-9 w-full rounded-md border px-2 text-sm"
                  value={filters.reason}
                  onChange={(e) => setFilters({ ...filters, reason: e.target.value, page: 0 })}
                >
                  <option value="">全部</option>
                  {(options?.reasons ?? []).map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="status" label="批次状态">
                <select
                  id="status"
                  className="border-input h-9 w-full rounded-md border px-2 text-sm"
                  value={filters.status}
                  onChange={(e) => setFilters({ ...filters, status: e.target.value, page: 0 })}
                >
                  <option value="">全部</option>
                  {(options?.statuses ?? []).map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="kw" label="关键词" hint="批次号 / SKU 编码 / 名称">
                <Input
                  id="kw"
                  value={filters.keyword}
                  placeholder="全部"
                  onChange={(e) => setFilters({ ...filters, keyword: e.target.value, page: 0 })}
                />
              </FormField>
            </div>
          </SectionCard>

          {page && page.items.length === 0 ? (
            <EmptyState
              icon={<PackageSearch className="h-6 w-6" />}
              title="还没有推荐批次"
              description="这一页只显示**真实发起过**的推荐。换货源时先到「发起推荐」跑一次，候选会带着置信度与判定依据落成快照。"
              action={
                <Button size="sm" onClick={() => void load()} disabled={busy}>
                  <RefreshCw className="h-4 w-4" />
                  重新读取
                </Button>
              }
            />
          ) : (
            <div className="mt-4 space-y-2">
              {(page?.items ?? []).map((run) => (
                <button
                  key={run.id}
                  type="button"
                  className="border-border bg-card hover:bg-muted/50 w-full rounded-lg border px-4 py-3 text-left"
                  onClick={() => void openRun(run)}
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-foreground text-sm font-medium">{run.runNo}</span>
                    <Badge tone="neutral">{run.reasonLabel}</Badge>
                    <Badge tone={run.status === 'REQUESTED' ? 'success' : 'info'}>
                      {run.statusLabel}
                    </Badge>
                    <span className="text-muted-foreground text-xs">
                      {run.skuCode} {run.skuName}
                    </span>
                  </div>
                  <div className="text-muted-foreground mt-1 text-xs">
                    候选 {run.candidateCount} 条 · 已确认 {run.confirmedCount} 条
                    {run.requiredQuantity ? ` · 需要 ${run.requiredQuantity}` : ''}
                  </div>
                </button>
              ))}
              {page ? (
                <div className="flex items-center gap-2 pt-2">
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={busy || page.page <= 0}
                    onClick={() =>
                      setFilters({ ...filters, page: Math.max(0, filters.page - 1) })
                    }
                  >
                    上一页
                  </Button>
                  <span className="text-muted-foreground text-xs">
                    第 {page.page + 1} / {totalPages} 页 · 共 {page.total} 条
                  </span>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={busy || page.page + 1 >= totalPages}
                    onClick={() => setFilters({ ...filters, page: filters.page + 1 })}
                  >
                    下一页
                  </Button>
                </div>
              ) : null}
            </div>
          )}

          {detail ? <RunDetail detail={detail} /> : null}
        </TabsContent>

        {/* ============================================================ 发起推荐 */}
        <TabsContent value="new">
          <SectionCard
            title="发起一次推荐"
            description="推荐会实时算候选并**落成快照**：证据（价格区间、交期、评分）是那一刻的事实，三个月后回看「当时推荐了什么、凭什么」必须还能看到。"
          >
            <div className="grid gap-3 md:grid-cols-4">
              <FormField
                id="new-sku"
                label="内部 SKU id"
                hint="catalog 的 SKU 逻辑 id（商品主数据在 catalog，本服务只经授权只读接口取）"
                required
              >
                <Input
                  id="new-sku"
                  value={newRun.skuId}
                  placeholder="例如 6f1c…"
                  onChange={(e) => setNewRun({ ...newRun, skuId: e.target.value })}
                />
              </FormField>
              <FormField id="new-reason" label="推荐理由" required>
                <select
                  id="new-reason"
                  className="border-input h-9 w-full rounded-md border px-2 text-sm"
                  value={newRun.reason}
                  onChange={(e) => setNewRun({ ...newRun, reason: e.target.value })}
                >
                  {(options?.reasons ?? []).map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="new-qty" label="需要补的量" hint="可以留空">
                <Input
                  id="new-qty"
                  value={newRun.quantity}
                  placeholder="可留空"
                  onChange={(e) => setNewRun({ ...newRun, quantity: e.target.value })}
                />
              </FormField>
              <FormField id="new-note" label="备注">
                <Input
                  id="new-note"
                  value={newRun.note}
                  placeholder="例如：旺季断货"
                  onChange={(e) => setNewRun({ ...newRun, note: e.target.value })}
                />
              </FormField>
            </div>
            <div className="mt-3 flex flex-wrap items-center gap-2">
              <Button size="sm" onClick={() => void submitRun()} disabled={busy}>
                <PackageSearch className="h-4 w-4" />
                生成推荐
              </Button>
              <Button
                size="sm"
                variant="outline"
                onClick={() => void previewRun()}
                disabled={previewing || busy}
              >
                {previewing ? '正在预览…' : '预览候选（不落库）'}
              </Button>
              <span className="text-muted-foreground text-xs">
                候选只从**有依据**的货源里出：已人工确认的同款、历史采购过该货的供应商、标题与规格一致的疑似同款。
              </span>
            </div>
          </SectionCard>

          {previewRows ? (
            <SectionCard
              title="候选预览（未落库）"
              description="预览与正式推荐**用同一套计算**，所以两边看到的不会不一样。正式发起推荐后，证据会连同采集时刻一起落成快照。"
            >
              {previewRows.length === 0 ? (
                <EmptyState
                  icon={<PackageSearch className="h-6 w-6" />}
                  title="没有候选"
                  description="三种证据都不成立（既没有人工确认的同款关联，供应商也没采购过这个货，标题与规格也对不上）—— 与其推一条说不清依据的货，不如不推。"
                />
              ) : (
                <div className="space-y-2">
                  {previewRows.map((c) => (
                    <div
                      key={c.itemId}
                      className="border-border rounded-md border px-3 py-2 text-xs"
                    >
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="text-foreground text-sm font-medium">{c.title}</span>
                        <Badge tone={confidenceTone(c.confidence)}>
                          同款置信度 {confidenceLabel(c.confidence)}
                        </Badge>
                        <Badge tone="neutral">
                          {matchTypeLabel(c.matchType, c.matchTypeLabel)}
                        </Badge>
                        {c.requiresManualConfirmation ? (
                          <Badge tone="warning">需人工确认</Badge>
                        ) : null}
                        {c.lowestPrice ? <Badge tone="success">同币种最低价</Badge> : null}
                      </div>
                      <p className="text-muted-foreground mt-1">判定依据：{c.matchBasis}</p>
                      <p className="text-muted-foreground mt-1">
                        {c.supplierName || '未关联供应商'} ·{' '}
                        {priceRangeLabel(c.priceMin, c.priceMax, c.currency)} ·{' '}
                        {collectedAtText(c.collectedAt)} · {moqLabel(c.moq)} ·{' '}
                        {leadTimeLabel(c.leadTimeDays)}
                      </p>
                    </div>
                  ))}
                </div>
              )}
            </SectionCard>
          ) : null}
        </TabsContent>

        {/* ============================================================ 规则与权重 */}
        <TabsContent value="policy">
          {policyForm ? (
            <SectionCard
              title="租户级规则与排序权重"
              description="排序分是这四个维度按权重合成的，权重写在库里、可查可改 —— 不是代码里写死的常数。权重和必须精确等于 1。"
            >
              <div className="grid gap-3 md:grid-cols-4">
                <FormField id="weak" label="弱匹配最低置信度" hint="低于它就不进候选">
                  <Input
                    id="weak"
                    value={policyForm.weakMatchMinConfidence}
                    onChange={(e) =>
                      setPolicyForm({ ...policyForm, weakMatchMinConfidence: e.target.value })
                    }
                  />
                </FormField>
                <FormField id="confirmed" label="确证最低置信度">
                  <Input
                    id="confirmed"
                    value={policyForm.confirmedMatchMinConfidence}
                    onChange={(e) =>
                      setPolicyForm({ ...policyForm, confirmedMatchMinConfidence: e.target.value })
                    }
                  />
                </FormField>
                <FormField id="max" label="单次候选上限">
                  <Input
                    id="max"
                    value={policyForm.maxCandidates}
                    onChange={(e) => setPolicyForm({ ...policyForm, maxCandidates: e.target.value })}
                  />
                </FormField>
                <FormField id="manual" label="弱匹配必须人工确认" hint="默认开着">
                  <select
                    id="manual"
                    className="border-input h-9 w-full rounded-md border px-2 text-sm"
                    value={policyForm.requireManualConfirmation ? 'true' : 'false'}
                    onChange={(e) =>
                      setPolicyForm({
                        ...policyForm,
                        requireManualConfirmation: e.target.value === 'true',
                      })
                    }
                  >
                    <option value="true">必须人工确认</option>
                    <option value="false">不强制</option>
                  </select>
                </FormField>
              </div>
              <div className="grid gap-3 md:grid-cols-4">
                <FormField id="w-price" label="价格权重">
                  <Input
                    id="w-price"
                    value={policyForm.priceWeight}
                    onChange={(e) => setPolicyForm({ ...policyForm, priceWeight: e.target.value })}
                  />
                </FormField>
                <FormField id="w-rating" label="评级权重">
                  <Input
                    id="w-rating"
                    value={policyForm.ratingWeight}
                    onChange={(e) =>
                      setPolicyForm({ ...policyForm, ratingWeight: e.target.value })
                    }
                  />
                </FormField>
                <FormField id="w-history" label="历史权重">
                  <Input
                    id="w-history"
                    value={policyForm.historyWeight}
                    onChange={(e) =>
                      setPolicyForm({ ...policyForm, historyWeight: e.target.value })
                    }
                  />
                </FormField>
                <FormField id="w-lead" label="交期权重">
                  <Input
                    id="w-lead"
                    value={policyForm.leadTimeWeight}
                    onChange={(e) =>
                      setPolicyForm({ ...policyForm, leadTimeWeight: e.target.value })
                    }
                  />
                </FormField>
              </div>
              <div className="mt-3 flex items-center gap-2">
                <Button size="sm" onClick={() => void submitPolicy(policyForm)} disabled={busy}>
                  保存规则
                </Button>
                <span className="text-muted-foreground text-xs">
                  {policy?.configured
                    ? `已配置（版本 ${policy.version}）。`
                    : '还没有配置过，当前用的是内置默认值。'}
                </span>
              </div>
            </SectionCard>
          ) : (
            <p className="text-muted-foreground text-sm">正在读规则…</p>
          )}
        </TabsContent>

        {/* ============================================================ 能力与边界 */}
        <TabsContent value="bounds">
          <SectionCard
            title="能力与边界"
            description="把「哪些输入真有依据」「哪些下游还没交付」显式报出来，而不是让调用方从 404 或空数组里猜。"
          >
            <div className="space-y-2">
              {(capabilities?.capabilities ?? []).map((c) => (
                <div key={c.moduleId} className="border-border rounded-md border px-3 py-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={c.available ? 'success' : 'danger'}>
                      {c.available ? '已接通' : '未交付'}
                    </Badge>
                    <span className="text-foreground text-sm">{c.name}</span>
                    <span className="text-muted-foreground text-xs">{c.moduleId}</span>
                    {c.ownerService !== '—' ? (
                      <span className="text-muted-foreground text-xs">主责：{c.ownerService}</span>
                    ) : null}
                  </div>
                  <p className="text-muted-foreground mt-1 text-xs">{c.impact}</p>
                </div>
              ))}
            </div>
            <ul className="text-muted-foreground mt-4 list-disc space-y-1 pl-5 text-xs">
              {(capabilities?.notes ?? []).map((n) => (
                <li key={n}>{n}</li>
              ))}
            </ul>
          </SectionCard>
        </TabsContent>
      </Tabs>
    </>
  );
}

/** 批次详情：候选 + 判定留痕 + 价格对比。 */
function RunDetail({ detail }: { detail: SourceAlternativeRunDetail }) {
  const [deciding, setDeciding] = useState<SourceAlternativeCandidate | null>(null);
  const [decision, setDecision] = useState({ type: 'CONFIRMED' as 'CONFIRMED' | 'REJECTED', reason: '' });
  const [requesting, setRequesting] = useState<SourceAlternativeCandidate | null>(null);
  const [requestForm, setRequestForm] = useState({
    quantity: '',
    targetWarehouseId: '',
    legalEntityId: '',
    note: '',
  });
  const [targets, setTargets] = useState<SourceAlternativeTargetOptions | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    void fetchSourceAlternativeTargets()
      .then(setTargets)
      .catch(() => setTargets(null));
  }, []);

  async function submitDecision() {
    if (!deciding) return;
    const problems = validateDecisionForm(decision.type, decision.reason);
    if (problems.length > 0) {
      setError(problems.join('；'));
      return;
    }
    setBusy(true);
    setError('');
    try {
      await decideSourceAlternativeCandidate(deciding.id as string, {
        decisionType: decision.type,
        reason: decision.reason,
      });
      setDeciding(null);
      setDecision({ type: 'CONFIRMED', reason: '' });
    } catch (e) {
      setError(e instanceof Error ? e.message : '判定失败');
    } finally {
      setBusy(false);
    }
  }

  async function submitRequest() {
    if (!requesting) return;
    const problems = validateRequestForm(
      requestForm.quantity,
      requestForm.targetWarehouseId,
      requestForm.legalEntityId,
      requesting.moq,
    );
    if (problems.length > 0) {
      setError(problems.join('；'));
      return;
    }
    setBusy(true);
    setError('');
    try {
      await createSourceAlternativePurchaseRequest(requesting.id as string, {
        quantity: requestForm.quantity,
        targetWarehouseId: requestForm.targetWarehouseId,
        legalEntityId: requestForm.legalEntityId,
        note: requestForm.note,
      });
      setRequesting(null);
      setRequestForm({ quantity: '', targetWarehouseId: '', legalEntityId: '', note: '' });
    } catch (e) {
      setError(e instanceof Error ? e.message : '生成采购申请失败');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mt-6 space-y-4">
      <SectionCard
        title={`批次 ${detail.run.runNo}`}
        description={`${detail.run.skuCode} ${detail.run.skuName} · ${detail.run.reasonLabel} · ${detail.run.statusLabel}`}
      >
        <div className="text-muted-foreground space-y-1 text-xs">
          {detail.run.referenceImageUrl ? (
            <p>
              目标 SKU 的参考图（**只给人看**：系统不拿它做相似度判定）：
              <a
                className="text-foreground ml-1 underline"
                href={detail.run.referenceImageUrl}
                target="_blank"
                rel="noreferrer"
              >
                看图
              </a>
            </p>
          ) : (
            <p>这个 SKU 还没有已确认的货源关联，没有参考图。</p>
          )}
          {detail.priceComparison.map((p) => (
            <p key={p.currency}>
              价格对比（{p.currency}）：{p.candidateCount} 条候选，区间{' '}
              {priceRangeLabel(p.lowestPrice, p.highestPrice, p.currency)} —— **不跨币种换算**。
            </p>
          ))}
        </div>
      </SectionCard>

      {detail.candidates.length === 0 ? (
        <EmptyState
          icon={<PackageSearch className="h-6 w-6" />}
          title="这一批没有候选"
          description="三种证据都不成立（既没有人工确认的同款关联，供应商也没采购过这个货，标题与规格也对不上）—— 与其推一条说不清依据的货，不如不推。"
        />
      ) : (
        <div className="space-y-3">
          {detail.candidates.map((c) => (
            <div key={c.id ?? c.itemId} className="border-border bg-card rounded-lg border px-4 py-3">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-foreground text-sm font-medium">{c.title}</span>
                <Badge tone={confidenceTone(c.confidence)}>
                  同款置信度 {confidenceLabel(c.confidence)}
                </Badge>
                <Badge tone="neutral">{matchTypeLabel(c.matchType, c.matchTypeLabel)}</Badge>
                {c.requiresManualConfirmation ? (
                  <Badge tone="warning">需人工确认</Badge>
                ) : null}
                {c.lowestPrice ? <Badge tone="success">同币种最低价</Badge> : null}
                {c.decision ? <Badge tone="info">{decisionLabel(c.decision)}</Badge> : null}
              </div>
              <p className="text-muted-foreground mt-1 text-xs">判定依据：{c.matchBasis}</p>
              <div className="text-muted-foreground mt-2 grid gap-1 text-xs sm:grid-cols-2">
                <p>
                  供应商：{c.supplierName || '未关联供应商'}（{c.stallName} / {c.platform}）
                </p>
                <p>
                  价格：{priceRangeLabel(c.priceMin, c.priceMax, c.currency)} ·{' '}
                  {collectedAtText(c.collectedAt)}
                </p>
                <p>
                  {moqLabel(c.moq)} · {leadTimeLabel(c.leadTimeDays)} · 历史采购{' '}
                  {c.historyOrderCount} 笔
                </p>
                <p>
                  {ratingLabel(
                    c.ratingObjectiveScore,
                    c.ratingFinalScore,
                    c.ratingGrade,
                    c.ratingManuallyAdjusted,
                  )}{' '}
                  · {scoreLabel(c.score)}
                </p>
              </div>
              <div className="mt-3 flex flex-wrap items-center gap-2">
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => setDeciding(c)}
                  disabled={busy || c.decision === 'CONFIRMED'}
                >
                  人工判定
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={busy || !canCreateRequest(c)}
                  onClick={() => {
                    setRequesting(c);
                    setRequestForm({ ...requestForm, quantity: c.moq ? String(c.moq) : '' });
                  }}
                >
                  生成采购申请
                </Button>
                {!canCreateRequest(c) ? (
                  <span className="text-muted-foreground text-xs">
                    {requestBlockedReason(c)}
                  </span>
                ) : null}
                {c.purchaseRequestNo ? (
                  <span className="text-muted-foreground text-xs">
                    已生成采购申请 {c.purchaseRequestNo}
                  </span>
                ) : null}
              </div>
            </div>
          ))}
        </div>
      )}

      {detail.decisions.length > 0 ? (
        <SectionCard title="判定留痕" description="判定只能追加，抹掉它等于「历史上没人确认过」。">
          <ul className="space-y-1 text-xs">
            {detail.decisions.map((d) => (
              <li key={d.id} className="text-muted-foreground">
                <span className="text-foreground">{d.decisionTypeLabel}</span>
                {d.reason ? ` · ${d.reason}` : ''}
                {d.purchaseRequestNo ? ` · 采购申请 ${d.purchaseRequestNo}` : ''}
                {` · ${collectedAtText(d.decidedAt)}`}
              </li>
            ))}
          </ul>
        </SectionCard>
      ) : null}

      {deciding ? (
        <SectionCard title="人工判定" description="否决必须写理由：说不出理由的否决等于没否决。">
          {error ? <p className="text-destructive text-sm">{error}</p> : null}
          <div className="grid gap-3 md:grid-cols-3">
            <FormField id="decision-type" label="判定">
              <select
                id="decision-type"
                className="border-input h-9 w-full rounded-md border px-2 text-sm"
                value={decision.type}
                onChange={(e) =>
                  setDecision({ ...decision, type: e.target.value as 'CONFIRMED' | 'REJECTED' })
                }
              >
                <option value="CONFIRMED">确认是同款</option>
                <option value="REJECTED">否决</option>
              </select>
            </FormField>
            <FormField id="decision-reason" label="理由" hint="否决时必填">
              <Input
                id="decision-reason"
                value={decision.reason}
                onChange={(e) => setDecision({ ...decision, reason: e.target.value })}
              />
            </FormField>
            <div className="flex items-end gap-2">
              <Button size="sm" onClick={() => void submitDecision()} disabled={busy}>
                提交判定
              </Button>
              <Button size="sm" variant="outline" onClick={() => setDeciding(null)} disabled={busy}>
                取消
              </Button>
            </div>
          </div>
        </SectionCard>
      ) : null}

      {requesting ? (
        <SectionCard
          title="一键生成采购申请"
          description="生成的是**草稿**（source_type=SUGGESTION）。货源快照价只是参考 —— 申请行不填预估单价，实际下单金额以后续采购单为准。"
        >
          {error ? <p className="text-destructive text-sm">{error}</p> : null}
          <div className="grid gap-3 md:grid-cols-4">
            <FormField
              id="req-qty"
              label="申请数量"
              hint={`不得低于起订量 ${moqLabel(requesting.moq)}`}
              required
            >
              <Input
                id="req-qty"
                value={requestForm.quantity}
                onChange={(e) => setRequestForm({ ...requestForm, quantity: e.target.value })}
              />
            </FormField>
            <FormField id="req-warehouse" label="目标仓" required>
              <select
                id="req-warehouse"
                className="border-input h-9 w-full rounded-md border px-2 text-sm"
                value={requestForm.targetWarehouseId}
                onChange={(e) =>
                  setRequestForm({ ...requestForm, targetWarehouseId: e.target.value })
                }
              >
                <option value="">请选择</option>
                {(targets?.warehouses ?? []).map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.name}（{w.code}）
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="req-entity" label="法人" required>
              <select
                id="req-entity"
                className="border-input h-9 w-full rounded-md border px-2 text-sm"
                value={requestForm.legalEntityId}
                onChange={(e) => setRequestForm({ ...requestForm, legalEntityId: e.target.value })}
              >
                <option value="">请选择</option>
                {(targets?.legalEntities ?? []).map((l) => (
                  <option key={l.id} value={l.id}>
                    {l.name}（{l.code}）
                  </option>
                ))}
              </select>
            </FormField>
            <div className="flex items-end gap-2">
              <Button size="sm" onClick={() => void submitRequest()} disabled={busy}>
                生成采购申请
              </Button>
              <Button
                size="sm"
                variant="outline"
                onClick={() => setRequesting(null)}
                disabled={busy}
              >
                取消
              </Button>
            </div>
          </div>
          {targets && !targets.available ? (
            <p className="text-destructive mt-2 text-xs">{targets.unavailableNote}</p>
          ) : null}
        </SectionCard>
      ) : null}
    </div>
  );
}
