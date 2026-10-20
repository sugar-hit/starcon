'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';

import {
  ADJUSTABLE_COMPONENTS,
  COST_SECTIONS,
  EMPTY_COST_FILTERS,
  addCostAdjustment,
  amountListText,
  costBasisText,
  costMethodBasis,
  costMethodLabel,
  dayText,
  fetchCostCapabilities,
  fetchCostDetail,
  fetchCostList,
  moneyText,
  moneyWithCurrency,
  saveCostProfile,
  sourceLabel,
  sourceTone,
  timeText,
  totalCompleteness,
  validateAdjustment,
  validateProfile,
  validateVoidReason,
  voidCostAdjustment,
} from './cost-composition-model';
import type {
  AdjustmentInput,
  CostCapabilities,
  CostDetail,
  CostFilters,
  CostListPage,
  CostRow,
  ProfileInput,
} from './cost-composition-model';

/**
 * 5.2.1.1 成本构成工作区。
 *
 * <p>🔴 页面最上面那一块**不是提示语，是这一页的设计前提**：
 * 蓝图加粗句「**成本口径必须在页面上明示用了哪种成本法**，否则同一 SKU 今天显示毛利 30% 明天 25%
 * 无法解释」。
 *
 * <p>所以本页把三件事摆在最显眼的位置：
 *
 * <ol>
 *   <li><b>当前用的哪种成本法</b>（移动加权 / 先进先出 / 批次）+ 它到底怎么算（口径串）；
 *   <li><b>生效时间</b>（这一版构成从哪一刻开始算数）与计价币种 / 单位；
 *   <li><b>每个分项的来源</b>（实际到货金额 / 报价 / 上游事实 / 人工参数）—— 来源不分开，
 *       「采购成本」四个字就是在骗人：6.3 采购单当前未交付，本页的采购成本是**实际到货金额**。
 * </ol>
 *
 * <p>🔴 **null 不渲染成 0**：包装成本没维护显示「未维护」，上游读不到显示「取不到」，
 * 合计不完整时明写「已知部分合计」。显示 0.00 会让单位成本偏低、毛利偏高，而页面上看不出任何异常。
 *
 * <p>🔴 **分区块结构**：5.2.1.2 到岸成本、5.2.1.3 成本分摊与本模块共用 L3 路由 {@code /pricing/cost}。
 * 本模块只占「成本构成」这一块；未交付的兄弟分区**显式标成未交付**（不画空白框冒充已交付），
 * 后续兄弟模块在 {@link COST_SECTIONS} 里新增一条分区即可，不需要重写本页骨架。
 */
export function CostCompositionWorkspace() {
  const [capabilities, setCapabilities] = useState<CostCapabilities | null>(null);
  const [page, setPage] = useState<CostListPage | null>(null);
  const [filters, setFilters] = useState<CostFilters>(EMPTY_COST_FILTERS);
  const [detail, setDetail] = useState<CostDetail | null>(null);
  const [selectedSkuId, setSelectedSkuId] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);

  // 保存构成的表单
  const [profileForm, setProfileForm] = useState<ProfileInput>({
    costMethod: 'MOVING_AVERAGE',
    currency: 'USD',
    effectiveFrom: '',
    packagingCost: null,
    changeReason: '',
    version: 0,
  });
  // 手工调整的表单
  const [adjustmentForm, setAdjustmentForm] = useState<AdjustmentInput>({
    component: 'OTHER_DIRECT',
    amount: '',
    currency: 'USD',
    reason: '',
    effectiveFrom: '',
  });
  const [profileProblems, setProfileProblems] = useState<string[]>([]);
  const [adjustmentProblems, setAdjustmentProblems] = useState<string[]>([]);

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const [next, caps] = await Promise.all([fetchCostList(filters), fetchCostCapabilities()]);
      setPage(next);
      setCapabilities(caps);
      setLoaded(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : '成本构成台账读不到');
    } finally {
      setBusy(false);
    }
  }, [filters]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 打开一个 SKU 的明细。 */
  const openDetail = useCallback(async (skuId: string, currency: string | null) => {
    setBusy(true);
    setError('');
    setNotice('');
    setSelectedSkuId(skuId);
    try {
      const next = await fetchCostDetail(
        skuId,
        currency,
        filters.windowFrom || null,
        filters.windowTo || null,
      );
      setDetail(next);
      setProfileForm({
        costMethod: next.costMethod || 'MOVING_AVERAGE',
        currency: next.currency || 'USD',
        effectiveFrom: nowLocal(),
        packagingCost: next.components.find((c) => c.code === 'PACKAGING')?.amount ?? null,
        changeReason: '',
        version: 0,
      });
      setAdjustmentForm({
        component: 'OTHER_DIRECT',
        amount: '',
        currency: next.currency || 'USD',
        reason: '',
        effectiveFrom: nowLocal(),
      });
    } catch (e) {
      setDetail(null);
      setError(e instanceof Error ? e.message : '成本构成明细读不到');
    } finally {
      setBusy(false);
    }
  }, [filters.windowFrom, filters.windowTo]);

  const refreshDetail = useCallback(async () => {
    if (!selectedSkuId) return;
    try {
      setDetail(await fetchCostDetail(selectedSkuId, null, null, null));
    } catch {
      // 刷新失败时保留旧明细，错误已在主 load 里报过。
    }
  }, [selectedSkuId]);

  async function submitProfile() {
    if (!selectedSkuId) return;
    const problems = validateProfile(profileForm);
    setProfileProblems(problems);
    if (problems.length) return;
    setBusy(true);
    setError('');
    try {
      const saved = await saveCostProfile(selectedSkuId, profileForm);
      setNotice(
        `已保存构成（第 ${saved.revisionNo} 版）—— 成本法变更已留历史，可在下面的「成本变动历史」里查到。`,
      );
      setProfileForm({ ...profileForm, changeReason: '', version: saved.version });
      await refreshDetail();
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '成本构成保存失败');
    } finally {
      setBusy(false);
    }
  }

  async function submitAdjustment() {
    if (!selectedSkuId) return;
    const problems = validateAdjustment(adjustmentForm);
    setAdjustmentProblems(problems);
    if (problems.length) return;
    setBusy(true);
    setError('');
    try {
      const saved = await addCostAdjustment(selectedSkuId, adjustmentForm);
      setNotice(`已追加手工调整（第 ${saved.revisionNo} 版历史）—— 每一笔都带原因。`);
      setAdjustmentForm({ ...adjustmentForm, amount: '', reason: '' });
      await refreshDetail();
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '手工调整追加失败');
    } finally {
      setBusy(false);
    }
  }

  async function submitVoid(adjustmentId: string, reason: string) {
    if (!selectedSkuId) return;
    const problems = validateVoidReason(reason);
    if (problems.length) {
      setError(problems[0] ?? '作废必须填理由');
      return;
    }
    setBusy(true);
    setError('');
    try {
      await voidCostAdjustment(selectedSkuId, adjustmentId, reason);
      setNotice('已作废该调整项 —— 旧行仍留在库里，历史可追溯。');
      await refreshDetail();
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '作废失败');
    } finally {
      setBusy(false);
    }
  }

  const completeness = useMemo(() => totalCompleteness(detail), [detail]);
  const deliveredSections = COST_SECTIONS.filter((s) => s.delivered);
  const pendingSections = COST_SECTIONS.filter((s) => !s.delivered);

  return (
    <div className="space-y-4">
      <PageHeader
        title="成本构成"
        description="SKU 的单位成本构成：采购成本、头程分摊、包装成本、关税、其他直接成本。采购成本按所选成本法从实际到货事实加权，其余分项来自参数或手工调整。"
      />

      {/* ============================================================ 成本口径声明 */}
      <SectionCard
        title="成本口径（必看）"
        description="蓝图加粗句：成本口径必须在页面上明示用了哪种成本法，否则同一 SKU 今天显示毛利 30% 明天 25% 无法解释。"
      >
        {capabilities ? (
          <div className="space-y-4">
            <Notice tone="warning" title="当前成本口径">
              {detail ? (
                <div className="space-y-1">
                  <p className="font-semibold">{costBasisText(detail)}</p>
                  <p>{detail.costMethodBasis || costMethodBasis(detail.costMethod)}</p>
                  {detail.maintained ? null : (
                    <p>🔴 该 SKU **尚未维护构成**：当前按默认口径（移动加权）显示，保存后按你选的成本法固化。</p>
                  )}
                </div>
              ) : (
                <p>
                  在下面的台账里点一个 SKU 查看它的成本口径。台账每一行也带着成本法，
                  所以不展开也能看出这个 SKU 用的是哪一种口径。
                </p>
              )}
            </Notice>

            <div>
              <p className="text-label-13 mb-2 font-semibold">三种成本法（同一份到货事实，三个不同的单位成本）</p>
              <div className="grid gap-2 md:grid-cols-3">
                {capabilities.costMethods.map((m) => (
                  <div key={m.value} className="border-border rounded-md border p-3">
                    <div className="flex items-center gap-2">
                      <Badge tone="brand">{m.label}</Badge>
                      <code className="text-copy-12 text-muted-foreground">{m.value}</code>
                    </div>
                    <p className="text-copy-12 text-muted-foreground mt-2">{m.basis}</p>
                  </div>
                ))}
              </div>
            </div>

            <div>
              <p className="text-label-13 mb-2 font-semibold">五个分项的来源</p>
              <div className="space-y-2">
                {capabilities.components.map((c) => (
                  <div key={c.code} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-label-13 font-semibold">{c.label}</span>
                      <Badge tone={sourceTone(c.kind)}>{sourceLabel(c.source)}</Badge>
                      <span className="text-copy-12 text-muted-foreground">{c.ownerModule}</span>
                    </div>
                    <p className="text-copy-12 text-muted-foreground mt-1">{c.impact}</p>
                  </div>
                ))}
              </div>
            </div>

            <div className="grid gap-2 md:grid-cols-2">
              <Notice tone="info" title="上游真值">
                {capabilities.upstreamNote}
              </Notice>
              <Notice tone="info" title="下游与读法">
                {capabilities.downstreamNote}
              </Notice>
              <Notice tone="info" title="落库口径">
                {capabilities.sourceNote}
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

      {/* ============================================================ 筛选 */}
      <SectionCard title="筛选">
        <div className="grid gap-3 md:grid-cols-4">
          <FormField id="cost-kw" label="关键词" hint="SKU 编码 / 名称">
            <Input
              id="cost-kw"
              value={filters.keyword}
              placeholder="全部"
              onChange={(e) => setFilters({ ...filters, keyword: e.target.value, page: 0 })}
            />
          </FormField>
          <FormField id="cost-method" label="成本法" hint="口径筛选；不选 = 全部">
            <select
              id="cost-method"
              className="border-input h-9 w-full rounded-md border px-2 text-sm"
              value={filters.costMethod}
              onChange={(e) => setFilters({ ...filters, costMethod: e.target.value, page: 0 })}
            >
              <option value="">全部</option>
              {(capabilities?.costMethods ?? []).map((m) => (
                <option key={m.value} value={m.value}>
                  {m.label}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="cost-currency" label="计价币种" hint="跨币种绝不相加">
            <Input
              id="cost-currency"
              value={filters.currency}
              placeholder="全部"
              onChange={(e) => setFilters({ ...filters, currency: e.target.value, page: 0 })}
            />
          </FormField>
          <FormField id="cost-maintained" label="是否已维护" hint="未维护的 SKU 也在这里（成本治理待办）">
            <select
              id="cost-maintained"
              className="border-input h-9 w-full rounded-md border px-2 text-sm"
              value={filters.maintained}
              onChange={(e) => setFilters({ ...filters, maintained: e.target.value, page: 0 })}
            >
              <option value="">全部</option>
              <option value="true">只看已维护</option>
              <option value="false">只看未维护</option>
            </select>
          </FormField>
          <FormField id="cost-window-from" label="口径窗口起" hint="窗口变了采购成本就会变">
            <Input
              id="cost-window-from"
              type="date"
              value={filters.windowFrom}
              onChange={(e) => setFilters({ ...filters, windowFrom: e.target.value, page: 0 })}
            />
          </FormField>
          <FormField id="cost-window-to" label="口径窗口止">
            <Input
              id="cost-window-to"
              type="date"
              value={filters.windowTo}
              onChange={(e) => setFilters({ ...filters, windowTo: e.target.value, page: 0 })}
            />
          </FormField>
        </div>
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <Button size="sm" variant="ghost" onClick={() => setFilters(EMPTY_COST_FILTERS)}>
            重置筛选
          </Button>
          <span className="text-muted-foreground text-xs">
            {page?.windowNote ?? '成本口径窗口：默认最近 90 天。'}
          </span>
        </div>
        {page?.note ? <p className="text-muted-foreground mt-2 text-xs">{page.note}</p> : null}
      </SectionCard>

      {/* ============================================================ 台账 */}
      <SectionCard title={`成本构成台账（${page?.total ?? 0}）`}>
        {error ? (
          <Notice tone="danger" title="读不到">
            {error}
          </Notice>
        ) : null}
        {notice ? (
          <Notice tone="success" title="已保存">
            {notice}
          </Notice>
        ) : null}
        {busy && !page ? (
          <p className="text-muted-foreground text-copy-13">正在读成本构成台账……</p>
        ) : page && page.items.length > 0 ? (
          <div className="space-y-3">
            <div className="overflow-x-auto">
              <table className="w-full text-copy-13">
                <thead>
                  <tr className="border-b text-left">
                    <th className="p-2">SKU</th>
                    <th className="p-2">成本法</th>
                    <th className="p-2">计价币种</th>
                    <th className="p-2">采购成本</th>
                    <th className="p-2">包装成本</th>
                    <th className="p-2">其他直接成本</th>
                    <th className="p-2">生效时间</th>
                    <th className="p-2">历史版本</th>
                    <th className="p-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((row) => (
                    <CostRowView
                      key={`${row.skuId}-${row.currency ?? 'none'}`}
                      row={row}
                      active={row.skuId === selectedSkuId}
                      onOpen={() => void openDetail(row.skuId, row.currency)}
                    />
                  ))}
                </tbody>
              </table>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Button
                size="sm"
                variant="ghost"
                disabled={filters.page <= 0}
                onClick={() => setFilters({ ...filters, page: filters.page - 1 })}
              >
                上一页
              </Button>
              <span className="text-muted-foreground text-xs">
                第 {filters.page + 1} 页 / 每页 {filters.size} 条 / 共 {page.total} 条
              </span>
              <Button
                size="sm"
                variant="ghost"
                disabled={(filters.page + 1) * filters.size >= page.total}
                onClick={() => setFilters({ ...filters, page: filters.page + 1 })}
              >
                下一页
              </Button>
            </div>
          </div>
        ) : loaded ? (
          <EmptyState
            title="这个筛选下没有 SKU"
            description="成本构成台账以 SKU 为骨架。没维护构成的 SKU 也会出现在这里（maintained = 未维护），所以「这里是空的」通常意味着筛选条件太窄。"
            action={
              <Button size="sm" variant="ghost" onClick={() => setFilters(EMPTY_COST_FILTERS)}>
                重置筛选
              </Button>
            }
          />
        ) : null}
      </SectionCard>

      {/* ============================================================ 明细 */}
      {detail ? (
        <>
          <SectionCard
            title={`成本构成明细 · ${detail.skuCode}`}
            description={detail.basis}
            extra={<Badge tone="brand">{detail.costMethodLabel}</Badge>}
          >
            <div className="grid gap-3 md:grid-cols-4">
              <StatCard label="计价币种" value={detail.currency} polarity="neutral" />
              <StatCard
                label="单位成本合计"
                value={moneyText(detail.totalAmount, '—')}
                unit={detail.totalComplete ? detail.currency : '（已知部分）'}
                polarity="neutral"
              />
              <StatCard label="成本法" value={detail.costMethodLabel} polarity="neutral" />
              <StatCard
                label="历史版本"
                value={detail.recentRevisions.length}
                unit="版"
                polarity="neutral"
              />
            </div>

            <div className="mt-3">
              <Notice tone={completeness.tone} title="合计完整度">
                {completeness.text.replace(/\*\*/g, '')}
              </Notice>
            </div>

            <div className="mt-4 overflow-x-auto">
              <table className="w-full text-copy-13">
                <thead>
                  <tr className="border-b text-left">
                    <th className="p-2">分项</th>
                    <th className="p-2">来源</th>
                    <th className="p-2">单位成本</th>
                    <th className="p-2">口径</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.components.map((c) => (
                    <tr key={c.code} className="border-b align-top">
                      <td className="p-2">
                        <div className="font-semibold">{c.label}</div>
                        {c.partial ? <Badge tone="warning">只覆盖了一部分</Badge> : null}
                      </td>
                      <td className="p-2">
                        <Badge tone={sourceTone(c.kind)}>{sourceLabel(c.source)}</Badge>
                      </td>
                      <td className="p-2 font-mono">
                        {c.available ? moneyWithCurrency(c.amount, detail.currency) : '—'}
                        {c.available && c.partial ? (
                          <div className="text-copy-12 text-muted-foreground">（已知部分）</div>
                        ) : null}
                        {!c.available && c.unavailableReason ? (
                          <div className="text-copy-12 text-muted-foreground">
                            {c.unavailableReason}
                          </div>
                        ) : null}
                        {c.amounts.length > 1 ? (
                          <div className="text-copy-12 text-muted-foreground">
                            {amountListText(c.amounts)}
                          </div>
                        ) : null}
                      </td>
                      <td className="p-2 text-copy-12 text-muted-foreground">
                        {c.basis}
                        {c.note ? <div className="mt-1">{c.note}</div> : null}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {detail.foreignAmounts.length > 0 ? (
              <div className="mt-3">
                <Notice tone="warning" title="其它币种的金额（未折算，不计入合计）">
                  <ul className="list-disc pl-4">
                    {detail.foreignAmounts.map((f, i) => (
                      <li key={`${f.component}-${f.currency}-${i}`}>
                        {f.componentLabel} {moneyWithCurrency(f.amount, f.currency)} —— {f.reason}
                      </li>
                    ))}
                  </ul>
                </Notice>
              </div>
            ) : null}

            {detail.excludedDeliveryCount > 0 ? (
              <div className="mt-3">
                <Notice tone="warning" title="有到货记录被排除">
                  {detail.excludedDeliveryCount} 条到货记录折算不出基础数量（采购单位与基础单位之间没有换算版本），
                  已被排除。采购成本分项因此只是「已知部分」，合计也只覆盖一部分事实。
                </Notice>
              </div>
            ) : null}
          </SectionCard>

          {/* ---------------- 保存构成 ---------------- */}
          <SectionCard
            title="维护成本构成"
            description="每一次保存都会追加一条历史快照 —— 换成本法是换口径，历史必须留版本。"
          >
            <div className="grid gap-3 md:grid-cols-3">
              <FormField
                id="profile-method"
                label="成本法"
                hint={costMethodBasis(profileForm.costMethod)}
                required
              >
                <select
                  id="profile-method"
                  className="border-input h-9 w-full rounded-md border px-2 text-sm"
                  value={profileForm.costMethod}
                  onChange={(e) =>
                    setProfileForm({ ...profileForm, costMethod: e.target.value })
                  }
                >
                  {(capabilities?.costMethods ?? []).map((m) => (
                    <option key={m.value} value={m.value}>
                      {m.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="profile-currency" label="计价币种" hint="同 SKU 换币种 = 新增一条构成" required>
                <Input
                  id="profile-currency"
                  value={profileForm.currency}
                  onChange={(e) => setProfileForm({ ...profileForm, currency: e.target.value })}
                />
              </FormField>
              <FormField id="profile-effective" label="生效时间" hint="这一版从哪一刻起算数" required>
                <Input
                  id="profile-effective"
                  type="datetime-local"
                  value={profileForm.effectiveFrom}
                  onChange={(e) => setProfileForm({ ...profileForm, effectiveFrom: e.target.value })}
                />
              </FormField>
              <FormField
                id="profile-packaging"
                label="包装成本（人工参数）"
                hint="留空 = 尚未维护，不是 0。包材目录没有成本列，待 06 采购 / 12 财务补齐。"
              >
                <Input
                  id="profile-packaging"
                  value={profileForm.packagingCost ?? ''}
                  placeholder="未维护"
                  onChange={(e) =>
                    setProfileForm({
                      ...profileForm,
                      packagingCost: e.target.value === '' ? null : e.target.value,
                    })
                  }
                />
              </FormField>
              <FormField
                id="profile-reason"
                label="变更原因"
                hint="必填：半年后没人知道当时为什么换口径"
                required
              >
                <Input
                  id="profile-reason"
                  value={profileForm.changeReason}
                  placeholder="例如：供应商调价，成本法从移动加权改为先进先出"
                  onChange={(e) => setProfileForm({ ...profileForm, changeReason: e.target.value })}
                />
              </FormField>
            </div>
            {profileProblems.length ? (
              <ul className="text-destructive mt-3 list-disc pl-4 text-copy-12">
                {profileProblems.map((p) => (
                  <li key={p}>{p}</li>
                ))}
              </ul>
            ) : null}
            <div className="mt-3">
              <Button size="sm" disabled={busy} onClick={() => void submitProfile()}>
                保存构成（留一条历史）
              </Button>
            </div>
          </SectionCard>

          {/* ---------------- 手工调整 ---------------- */}
          <SectionCard
            title="手工调整项"
            description="只追加，不就地改。每一笔必须带原因；录错了就作废（也要理由），旧行留在库里。"
          >
            <div className="grid gap-3 md:grid-cols-3">
              <FormField id="adj-component" label="分项" hint="只有这两项可手工调整" required>
                <select
                  id="adj-component"
                  className="border-input h-9 w-full rounded-md border px-2 text-sm"
                  value={adjustmentForm.component}
                  onChange={(e) =>
                    setAdjustmentForm({ ...adjustmentForm, component: e.target.value })
                  }
                >
                  {ADJUSTABLE_COMPONENTS.map((c) => (
                    <option key={c.value} value={c.value}>
                      {c.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="adj-amount" label="金额" hint="允许负数（冲回），不允许 0" required>
                <Input
                  id="adj-amount"
                  value={adjustmentForm.amount}
                  placeholder="如 2.50"
                  onChange={(e) => setAdjustmentForm({ ...adjustmentForm, amount: e.target.value })}
                />
              </FormField>
              <FormField id="adj-currency" label="币种" hint="必须与构成币种一致" required>
                <Input
                  id="adj-currency"
                  value={adjustmentForm.currency}
                  onChange={(e) => setAdjustmentForm({ ...adjustmentForm, currency: e.target.value })}
                />
              </FormField>
              <FormField id="adj-effective" label="生效时间" required>
                <Input
                  id="adj-effective"
                  type="datetime-local"
                  value={adjustmentForm.effectiveFrom}
                  onChange={(e) =>
                    setAdjustmentForm({ ...adjustmentForm, effectiveFrom: e.target.value })
                  }
                />
              </FormField>
              <FormField id="adj-reason" label="原因" hint="必填" required>
                <Input
                  id="adj-reason"
                  value={adjustmentForm.reason}
                  placeholder="例如：供应商临时加价"
                  onChange={(e) => setAdjustmentForm({ ...adjustmentForm, reason: e.target.value })}
                />
              </FormField>
            </div>
            {adjustmentProblems.length ? (
              <ul className="text-destructive mt-3 list-disc pl-4 text-copy-12">
                {adjustmentProblems.map((p) => (
                  <li key={p}>{p}</li>
                ))}
              </ul>
            ) : null}
            <div className="mt-3">
              <Button size="sm" disabled={busy} onClick={() => void submitAdjustment()}>
                追加调整项
              </Button>
            </div>

            {detail.adjustments.length > 0 ? (
              <div className="mt-4 overflow-x-auto">
                <table className="w-full text-copy-13">
                  <thead>
                    <tr className="border-b text-left">
                      <th className="p-2">分项</th>
                      <th className="p-2">金额</th>
                      <th className="p-2">原因</th>
                      <th className="p-2">生效时间</th>
                      <th className="p-2">状态</th>
                      <th className="p-2">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.adjustments.map((a) => (
                      <tr key={a.id} className="border-b align-top">
                        <td className="p-2">{a.componentLabel}</td>
                        <td className="p-2 font-mono">
                          {moneyWithCurrency(a.amount, a.currency)}
                        </td>
                        <td className="p-2">{a.reason}</td>
                        <td className="p-2">{timeText(a.effectiveFrom)}</td>
                        <td className="p-2">
                          {a.isVoid ? (
                            <Badge tone="danger">已作废</Badge>
                          ) : (
                            <Badge tone="success">生效中</Badge>
                          )}
                          {a.voidReason ? (
                            <div className="text-copy-12 text-muted-foreground">{a.voidReason}</div>
                          ) : null}
                        </td>
                        <td className="p-2">
                          {a.isVoid ? null : (
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => {
                                const reason = window.prompt('作废理由（必填）');
                                if (reason === null) return;
                                void submitVoid(a.id, reason);
                              }}
                            >
                              作废
                            </Button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <p className="text-muted-foreground mt-4 text-copy-13">
                还没有手工调整项。没有调整记录时「其他直接成本」是 0 —— 这一项的事实源就是这张表。
              </p>
            )}
          </SectionCard>

          {/* ---------------- 历史 ---------------- */}
          <SectionCard
            title="成本变动历史"
            description="历史不可变：换成本法只追加版本，不就地覆盖。"
          >
            {detail.recentRevisions.length > 0 ? (
              <div className="overflow-x-auto">
                <table className="w-full text-copy-13">
                  <thead>
                    <tr className="border-b text-left">
                      <th className="p-2">版本</th>
                      <th className="p-2">成本法</th>
                      <th className="p-2">采购成本</th>
                      <th className="p-2">头程分摊</th>
                      <th className="p-2">包装成本</th>
                      <th className="p-2">关税</th>
                      <th className="p-2">其他直接成本</th>
                      <th className="p-2">合计</th>
                      <th className="p-2">生效时间</th>
                      <th className="p-2">变更原因</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.recentRevisions.map((r) => (
                      <tr key={r.id} className="border-b align-top">
                        <td className="p-2">#{r.revisionNo}</td>
                        <td className="p-2">
                          <Badge tone="brand">{r.costMethodLabel}</Badge>
                        </td>
                        <td className="p-2 font-mono">
                          {moneyWithCurrency(r.purchaseCost, r.currency, '—')}
                        </td>
                        <td className="p-2 font-mono">
                          {moneyWithCurrency(r.firstMileAllocation, r.currency, '—')}
                        </td>
                        <td className="p-2 font-mono">
                          {moneyWithCurrency(r.packagingCost, r.currency, '—')}
                        </td>
                        <td className="p-2 font-mono">
                          {moneyWithCurrency(r.dutyCost, r.currency, '—')}
                        </td>
                        <td className="p-2 font-mono">
                          {moneyWithCurrency(r.otherDirectCost, r.currency, '—')}
                        </td>
                        <td className="p-2 font-mono">
                          {moneyWithCurrency(r.totalUnitCost, r.currency, '—')}
                        </td>
                        <td className="p-2">{timeText(r.effectiveFrom)}</td>
                        <td className="p-2">
                          {r.changeReason}
                          {r.snapshotComplete ? null : (
                            <div className="text-copy-12 text-muted-foreground">
                              快照不完整（缺 {r.missingComponents}）
                            </div>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <p className="text-muted-foreground text-copy-13">还没有历史版本。</p>
            )}
          </SectionCard>

          {/* ---------------- 批次与报价参考 ---------------- */}
          <SectionCard title="批次与报价参考">
            <div className="grid gap-4 md:grid-cols-2">
              <div>
                <p className="text-label-13 mb-2 font-semibold">批次（成本法 = 批次时的批次清单）</p>
                {detail.lots.length > 0 ? (
                  <div className="overflow-x-auto">
                    <table className="w-full text-copy-13">
                      <thead>
                        <tr className="border-b text-left">
                          <th className="p-2">到货单号</th>
                          <th className="p-2">到货日</th>
                          <th className="p-2">数量</th>
                          <th className="p-2">金额</th>
                          <th className="p-2">单价</th>
                          <th className="p-2"></th>
                        </tr>
                      </thead>
                      <tbody>
                        {detail.lots.map((l) => (
                          <tr key={`${l.lotRef}-${l.receivedDate}`} className="border-b">
                            <td className="p-2 font-mono">{l.lotRef}</td>
                            <td className="p-2">{dayText(l.receivedDate)}</td>
                            <td className="p-2 font-mono">{l.baseQuantity}</td>
                            <td className="p-2 font-mono">
                              {moneyWithCurrency(l.amount, l.currency)}
                            </td>
                            <td className="p-2 font-mono">
                              {moneyWithCurrency(l.unitCost, l.currency, '—')}
                            </td>
                            <td className="p-2">
                              {l.selected ? <Badge tone="brand">选中</Badge> : null}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <p className="text-muted-foreground text-copy-13">
                    窗口内没有该 SKU 在 {detail.currency} 下的有效到货记录。
                  </p>
                )}
              </div>
              <div>
                <p className="text-label-13 mb-2 font-semibold">供应商报价（只读参考）</p>
                <Notice tone={detail.quoteReference.available ? 'info' : 'warning'} title="不是采购成本">
                  {detail.quoteReference.reason ?? detail.quoteReference.note}
                </Notice>
                {detail.quoteReference.available ? (
                  <div className="text-copy-13 mt-2 space-y-1">
                    <p>
                      报价单价：
                      <span className="font-mono">
                        {moneyWithCurrency(
                          detail.quoteReference.unitPrice,
                          detail.quoteReference.currency,
                        )}
                      </span>
                      {detail.quoteReference.purchaseUnitCode
                        ? ` / ${detail.quoteReference.purchaseUnitCode}`
                        : ''}
                    </p>
                    <p className="text-muted-foreground">
                      起订量 {detail.quoteReference.minQuantity ?? '—'}｜有效起{' '}
                      {dayText(detail.quoteReference.validFrom)}｜状态{' '}
                      {detail.quoteReference.status ?? '—'}
                    </p>
                  </div>
                ) : null}
              </div>
            </div>
          </SectionCard>
        </>
      ) : null}

      {/* ============================================================ 兄弟分区 */}
      <SectionCard
        title="同一路由下的其它分区"
        description="5.2 的三个成本模块共用 L3 路由 /pricing/cost，各占一个独立分区，不重写彼此的页面骨架。"
      >
        <div className="space-y-2">
          {deliveredSections.map((s) => (
            <div key={s.id} className="border-border rounded-md border p-3">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-label-13 font-semibold">{s.title}</span>
                <Badge tone="success">已交付</Badge>
                <code className="text-copy-12 text-muted-foreground">{s.moduleId}</code>
              </div>
              <p className="text-copy-12 text-muted-foreground mt-1">{s.summary}</p>
            </div>
          ))}
          {pendingSections.map((s) => (
            <div key={s.id} className="border-border rounded-md border border-dashed p-3">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-label-13 font-semibold">{s.title}</span>
                <Badge tone="warning">未交付</Badge>
                <code className="text-copy-12 text-muted-foreground">{s.moduleId}</code>
              </div>
              <p className="text-copy-12 text-muted-foreground mt-1">{s.summary}</p>
            </div>
          ))}
        </div>
      </SectionCard>
    </div>
  );
}

/** 台账里的一行。 */
function CostRowView({
  row,
  active,
  onOpen,
}: {
  row: CostRow;
  active: boolean;
  onOpen: () => void;
}) {
  return (
    <tr className="border-b">
      <td className="p-2">
        <div className="font-mono">{row.skuCode}</div>
        <div className="text-copy-12 text-muted-foreground">{row.skuName}</div>
        <div className="text-copy-12 text-muted-foreground">
          {row.spuCode}｜单位 {row.baseUnit}
        </div>
      </td>
      <td className="p-2">
        {row.maintained ? (
          <Badge tone="brand">{row.costMethodLabel || costMethodLabel(row.costMethod)}</Badge>
        ) : (
          <Badge tone="warning">未维护</Badge>
        )}
      </td>
      <td className="p-2">{row.currency ?? '—'}</td>
      <td className="p-2 font-mono">
        {row.purchaseCost === null ? (
          <span className="text-muted-foreground">—</span>
        ) : (
          moneyWithCurrency(row.purchaseCost, row.currency)
        )}
        {row.purchaseDeliveryCount !== null ? (
          <div className="text-copy-12 text-muted-foreground">
            到货 {row.purchaseDeliveryCount} 条
          </div>
        ) : null}
      </td>
      <td className="p-2 font-mono">
        {row.packagingCost === null ? (
          <span className="text-muted-foreground">未维护</span>
        ) : (
          moneyWithCurrency(row.packagingCost, row.currency)
        )}
      </td>
      <td className="p-2 font-mono">
        {row.otherDirectCost === null ? (
          <span className="text-muted-foreground">—</span>
        ) : (
          moneyWithCurrency(row.otherDirectCost, row.currency)
        )}
      </td>
      <td className="p-2">{timeText(row.effectiveFrom, '未维护')}</td>
      <td className="p-2">{row.revisionCount} 版</td>
      <td className="p-2">
        <Button size="sm" variant={active ? 'default' : 'ghost'} onClick={onOpen}>
          {active ? '已展开' : '查看明细'}
        </Button>
      </td>
    </tr>
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
