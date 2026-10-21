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
  CHANGE_SOURCES,
  DIRECTIONS,
  EMPTY_CHANGE_FILTERS,
  EMPTY_COMPARISON_FILTERS,
  REASON_CATEGORIES,
  SORT_OPTIONS,
  changeSourceLabel,
  directionLabel,
  directionTone,
  effectiveText,
  fetchPriceChange,
  fetchPriceChangeReasons,
  fetchPriceChanges,
  fetchPriceComparison,
  fetchPriceHistoryCapabilities,
  fetchPriceTimeline,
  listingText,
  moneyText,
  moneyWithCurrency,
  nowLocal,
  priceMoveText,
  profitDeltaText,
  profitText,
  ratioText,
  registerPriceChange,
  salesDeltaText,
  salesText,
  savePriceChangeReason,
  signedMoneyText,
  signedRatioText,
  timeText,
  validateChange,
  validateReason,
} from './price-history-model';
import type {
  ChangeDetail,
  ChangeFilters,
  ChangeInput,
  ChangeListPage,
  ComparisonFilters,
  ComparisonPage,
  PriceChangeRow,
  PriceHistoryCapabilities,
  ReasonInput,
  ReasonRow,
  Timeline,
} from './price-history-model';

/**
 * 5.5.1.1 价格历史工作区。
 *
 * <p>🔴 页面最上面那一块**不是提示语，是这一页的设计前提**：
 * 蓝图加粗句「**价格历史 + 销量 + 利润三者对齐，才能回答"降价到底有没有用"**」。
 *
 * <p>所以本页把三件事摆在最显眼的位置：
 *
 * <ol>
 *   <li><b>三个维度各自齐没齐</b> —— 价格（真的）、利润（真的）、销量（**当前未接线**）。
 *       「读不到」与「确实是 0」在界面上分得开。</li>
 *   <li><b>变更事实是不可变的</b> —— 改价要改就再登记一条，不在原地改写已经发生过的历史。</li>
 *   <li><b>时间轴的链条</b> —— 每一格的旧价必须接得上上一格的新价；接不上就明说断在哪。</li>
 * </ol>
 *
 * <p>🔴 **null 不渲染成 0**：销量未接线显示「未接线」，利润没测准显示「未测准」，
 * 没有旧价显示「首次定价」—— 三者都**不是** 0。
 */
export function PriceHistoryWorkspace() {
  const [capabilities, setCapabilities] = useState<PriceHistoryCapabilities | null>(null);
  const [changes, setChanges] = useState<ChangeListPage | null>(null);
  const [filters, setFilters] = useState<ChangeFilters>(EMPTY_CHANGE_FILTERS);
  const [detail, setDetail] = useState<ChangeDetail | null>(null);
  const [selectedChangeId, setSelectedChangeId] = useState<string | null>(null);
  const [timeline, setTimeline] = useState<Timeline | null>(null);
  const [comparison, setComparison] = useState<ComparisonPage | null>(null);
  const [comparisonFilters, setComparisonFilters] =
    useState<ComparisonFilters>(EMPTY_COMPARISON_FILTERS);
  const [reasons, setReasons] = useState<ReasonRow[]>([]);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);

  // 登记表单
  const [form, setForm] = useState<ChangeInput>({
    skuId: '',
    platformCode: 'AMAZON',
    siteCode: 'US',
    currency: 'USD',
    oldPrice: null,
    newPrice: '',
    changeSource: 'MANUAL_ADJUSTMENT',
    reasonCode: '',
    reasonText: '',
    effectiveAt: nowLocal(),
    quantity: null,
    costCurrency: null,
    fxRate: null,
    platformFeeRate: null,
    platformFeeFixed: null,
    logisticsCostPerUnit: null,
    monthlyFixedCost: null,
    costWindowFrom: null,
    costWindowTo: null,
    note: '',
  });
  const [problems, setProblems] = useState<string[]>([]);

  // 原因字典表单
  const [reasonForm, setReasonForm] = useState<ReasonInput>({
    code: '',
    label: '',
    category: 'COMPETITION',
    enabled: true,
    sortOrder: 0,
    note: '',
  });
  const [reasonProblems, setReasonProblems] = useState<string[]>([]);

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const [caps, page, reasonList] = await Promise.all([
        fetchPriceHistoryCapabilities(),
        fetchPriceChanges(filters),
        fetchPriceChangeReasons(),
      ]);
      setCapabilities(caps);
      setChanges(page);
      setReasons(reasonList);
      setLoaded(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : '价格历史读不到');
    } finally {
      setBusy(false);
    }
  }, [filters]);

  useEffect(() => {
    void load();
  }, [load]);

  const loadDetail = useCallback(async (changeId: string) => {
    setSelectedChangeId(changeId);
    setDetail(null);
    try {
      setDetail(await fetchPriceChange(changeId));
    } catch (e) {
      setError(e instanceof Error ? e.message : '变更详情读不到');
    }
  }, []);

  const loadTimeline = useCallback(async () => {
    setError('');
    if (!filters.skuId.trim() || !filters.platformCode.trim() || !filters.siteCode.trim()) {
      setTimeline(null);
      setNotice('时间轴需要 SKU + 平台码 + 站点码（一个 listing 就是一个 SKU 在一个店铺）。');
      return;
    }
    try {
      setTimeline(
        await fetchPriceTimeline(
          filters.skuId.trim(),
          filters.platformCode.trim().toUpperCase(),
          filters.siteCode.trim().toUpperCase(),
          filters.windowFrom || undefined,
          filters.windowTo || undefined,
        ),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : '时间轴读不到');
    }
  }, [filters.skuId, filters.platformCode, filters.siteCode, filters.windowFrom, filters.windowTo]);

  const loadComparison = useCallback(async () => {
    setError('');
    try {
      setComparison(await fetchPriceComparison(comparisonFilters));
    } catch (e) {
      setError(e instanceof Error ? e.message : '对比视图读不到');
    }
  }, [comparisonFilters]);

  const submit = useCallback(async () => {
    const found = validateChange(form);
    setProblems(found);
    if (found.length > 0) return;
    setBusy(true);
    setError('');
    try {
      const saved = await registerPriceChange({
        ...form,
        oldPrice: form.oldPrice === '' ? null : form.oldPrice,
        platformCode: form.platformCode.trim().toUpperCase(),
        siteCode: form.siteCode.trim().toUpperCase(),
        currency: form.currency.trim().toUpperCase(),
        effectiveAt: new Date(form.effectiveAt).toISOString(),
      });
      setNotice(saved.note);
      setForm((f) => ({
        ...f,
        oldPrice: null,
        newPrice: '',
        reasonText: '',
        effectiveAt: nowLocal(),
      }));
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '登记失败');
    } finally {
      setBusy(false);
    }
  }, [form, load]);

  const submitReason = useCallback(async () => {
    const found = validateReason(reasonForm);
    setReasonProblems(found);
    if (found.length > 0) return;
    setBusy(true);
    setError('');
    try {
      await savePriceChangeReason(reasonForm);
      setNotice(`已保存变更原因「${reasonForm.label}」。`);
      setReasonForm({
        code: '',
        label: '',
        category: 'COMPETITION',
        enabled: true,
        sortOrder: 0,
        note: '',
      });
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setBusy(false);
    }
  }, [reasonForm, load]);

  const upCount = useMemo(
    () => (changes?.items ?? []).filter((c) => c.direction === 'UP').length,
    [changes],
  );
  const downCount = useMemo(
    () => (changes?.items ?? []).filter((c) => c.direction === 'DOWN').length,
    [changes],
  );
  const brokenChainCount = useMemo(
    () => (timeline?.entries ?? []).filter((e) => !e.chainConsistent).length,
    [timeline],
  );
  const salesAvailable = comparison?.salesAvailable ?? false;

  return (
    <div className="space-y-4">
      <PageHeader
        title="价格历史"
        description="记录 SKU / listing 的价格变更历史（时间、旧价、新价、操作人 / 来源、原因），支持按时间轴回溯并对比同期销量利润变化。"
      />

      {/* ============================================================ 能力声明 */}
      <SectionCard
        title="这一页在看什么（必看）"
        description="价格 / 销量 / 利润三个维度各自的真值、能不能取到、缺了会怎样。"
      >
        {capabilities ? (
          <div className="space-y-4">
            <Notice
              tone={capabilities.inputs.some((i) => !i.available) ? 'warning' : 'info'}
              title="三个维度齐没齐"
            >
              <div className="space-y-2">
                {capabilities.inputs.map((input) => (
                  <p key={input.code}>
                    <Badge tone={input.available ? 'success' : 'warning'}>
                      {input.available ? '已就绪' : '未接线'}
                    </Badge>{' '}
                    <span className="font-semibold">{input.label}</span>：{input.impact}
                  </p>
                ))}
              </div>
            </Notice>

            <div className="grid gap-2 md:grid-cols-2">
              <Notice tone="info" title="listing 的口径">
                {capabilities.listingNote}
              </Notice>
              <Notice tone="info" title="变更来源">
                {capabilities.changeSourceNote}
              </Notice>
              <Notice tone="info" title="利润口径">
                {capabilities.profitNote}
              </Notice>
              <Notice tone="warning" title="销量口径">
                {capabilities.salesNote}
              </Notice>
              <Notice tone="info" title="上游">
                {capabilities.upstreamNote}
              </Notice>
              <Notice tone="info" title="下游">
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

      {/* ============================================================ 概览 */}
      <div className="grid gap-3 md:grid-cols-4">
        <StatCard
          label="本页变更条数"
          value={changes?.total ?? 0}
          unit="条"
          polarity="neutral"
          comparisonLabel="全部筛选结果"
        />
        <StatCard
          label="涨价"
          value={upCount}
          unit="条"
          polarity="neutral"
          comparisonLabel="本页"
        />
        <StatCard
          label="降价"
          value={downCount}
          unit="条"
          polarity="neutral"
          comparisonLabel="本页"
        />
        <StatCard
          label="销量维度"
          value={salesAvailable ? '已就绪' : '未接线'}
          polarity="neutral"
          comparisonLabel={comparison?.note ?? '对比视图还没打开'}
        />
      </div>

      {error ? (
        <Notice tone="danger" title="出错了">
          {error}
        </Notice>
      ) : null}
      {notice ? (
        <Notice tone="success" title="已完成">
          {notice}
        </Notice>
      ) : null}

      {/* ============================================================ 筛选 */}
      <SectionCard
        title="筛选"
        description="按 SKU / 店铺 / 时间范围 / 变更来源 / 原因 / 方向真筛选，分页与排序都在服务端。"
      >
        <div className="grid gap-3 md:grid-cols-4">
          <FormField id="ph-kw" label="关键词" hint="SKU 编码 / 名称">
            <Input
              id="ph-kw"
              value={filters.keyword}
              placeholder="全部"
              onChange={(e) => setFilters({ ...filters, keyword: e.target.value, page: 0 })}
            />
          </FormField>
          <FormField id="ph-sku" label="SKU ID" hint="时间轴需要它">
            <Input
              id="ph-sku"
              value={filters.skuId}
              placeholder="uuid"
              onChange={(e) => setFilters({ ...filters, skuId: e.target.value, page: 0 })}
            />
          </FormField>
          <FormField id="ph-platform" label="平台码" hint="AMAZON / SHOPIFY …">
            <Input
              id="ph-platform"
              value={filters.platformCode}
              placeholder="全部"
              onChange={(e) => setFilters({ ...filters, platformCode: e.target.value, page: 0 })}
            />
          </FormField>
          <FormField id="ph-site" label="站点码" hint="US / DE / FR …">
            <Input
              id="ph-site"
              value={filters.siteCode}
              placeholder="全部"
              onChange={(e) => setFilters({ ...filters, siteCode: e.target.value, page: 0 })}
            />
          </FormField>
          <FormField id="ph-source" label="变更来源">
            <Input
              id="ph-source"
              value={filters.changeSource}
              placeholder="全部 / MANUAL_ADJUSTMENT …"
              onChange={(e) => setFilters({ ...filters, changeSource: e.target.value, page: 0 })}
            />
          </FormField>
          <FormField id="ph-reason" label="原因编码">
            <Input
              id="ph-reason"
              value={filters.reasonCode}
              placeholder="全部"
              onChange={(e) => setFilters({ ...filters, reasonCode: e.target.value, page: 0 })}
            />
          </FormField>
          <FormField id="ph-direction" label="方向">
            <Input
              id="ph-direction"
              value={filters.direction}
              placeholder="全部 / UP / DOWN …"
              onChange={(e) => setFilters({ ...filters, direction: e.target.value, page: 0 })}
            />
          </FormField>
          <FormField id="ph-from" label="窗口起（ISO 日期）">
            <Input
              id="ph-from"
              value={filters.windowFrom}
              placeholder="2026-08-01"
              onChange={(e) => setFilters({ ...filters, windowFrom: e.target.value, page: 0 })}
            />
          </FormField>
          <FormField id="ph-to" label="窗口止（ISO 日期）">
            <Input
              id="ph-to"
              value={filters.windowTo}
              placeholder="2026-10-20"
              onChange={(e) => setFilters({ ...filters, windowTo: e.target.value, page: 0 })}
            />
          </FormField>
          <FormField id="ph-sort" label="排序">
            <Input
              id="ph-sort"
              value={filters.sort}
              placeholder="EFFECTIVE_AT_DESC"
              onChange={(e) => setFilters({ ...filters, sort: e.target.value, page: 0 })}
            />
          </FormField>
          <div className="flex items-end gap-2">
            <Button onClick={() => void load()} disabled={busy}>
              刷新
            </Button>
            <Button
              variant="outline"
              onClick={() => setFilters(EMPTY_CHANGE_FILTERS)}
              disabled={busy}
            >
              重置
            </Button>
          </div>
        </div>
        <p className="text-copy-12 text-muted-foreground mt-2">
          排序可选：
          {SORT_OPTIONS.map((option) => option.code).join(' / ')}
        </p>
      </SectionCard>

      {/* ============================================================ 变更时间轴（列表） */}
      <SectionCard
        title="价格变更时间轴"
        description="变更事实只追加：改价要改就再登记一条，不在原地改写已经发生过的历史。"
      >
        {changes && changes.items.length === 0 ? (
          <EmptyState
            title="当前没有价格变更"
            description="价格变更来自上游（批量调价、调价单）与人工登记。没有变更就还没有历史。"
          />
        ) : (
          <div className="space-y-2">
            {(changes?.items ?? []).map((change) => (
              <ChangeRowView
                key={change.id}
                change={change}
                selected={change.id === selectedChangeId}
                onSelect={() => void loadDetail(change.id)}
              />
            ))}
            {changes ? (
              <div className="text-copy-12 text-muted-foreground flex items-center gap-2">
                <span>
                  共 {changes.total} 条（第 {changes.page + 1} 页，每页 {changes.size} 条）。
                  {changes.note}
                </span>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={busy || changes.page + 1 >= Math.ceil(changes.total / changes.size)}
                  onClick={() => setFilters({ ...filters, page: filters.page + 1 })}
                >
                  下一页
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={busy || filters.page === 0}
                  onClick={() => setFilters({ ...filters, page: Math.max(filters.page - 1, 0) })}
                >
                  上一页
                </Button>
              </div>
            ) : null}
          </div>
        )}
      </SectionCard>

      {/* ============================================================ 变更详情 */}
      {selectedChangeId ? (
        <SectionCard title="变更详情" description="变更事实 + 前后相邻的变更（时间轴的上下文）。">
          {detail ? (
            <div className="space-y-4">
              <Notice tone="info" title="这条记录是什么">
                {detail.basis}
              </Notice>
              <div className="grid gap-2 md:grid-cols-3">
                <div className="border-border rounded-md border p-3">
                  <p className="text-label-13 font-semibold">价格变化</p>
                  <p className="text-copy-13">{priceMoveText(detail.change)}</p>
                  <p className="text-copy-12 text-muted-foreground">
                    价差 {signedMoneyText(detail.change.priceDelta)}（
                    {signedRatioText(detail.change.changeRatio)}）
                  </p>
                </div>
                <div className="border-border rounded-md border p-3">
                  <p className="text-label-13 font-semibold">利润变化</p>
                  <p className="text-copy-13">
                    毛利额{' '}
                    {profitText(
                      detail.change.grossProfitBefore,
                      detail.change.decisionReady,
                      detail.change.profitNote,
                    )}{' '}
                    →{' '}
                    {profitText(
                      detail.change.grossProfitAfter,
                      detail.change.decisionReady,
                      detail.change.profitNote,
                    )}
                  </p>
                  <p className="text-copy-12 text-muted-foreground">
                    毛利率 {ratioText(detail.change.grossMarginBefore)} →{' '}
                    {ratioText(detail.change.grossMarginAfter)}
                  </p>
                </div>
                <div className="border-border rounded-md border p-3">
                  <p className="text-label-13 font-semibold">谁动的</p>
                  <p className="text-copy-13">{detail.change.changeSourceLabel}</p>
                  <p className="text-copy-12 text-muted-foreground">
                    投递方 {detail.change.sourceModule}
                    {detail.change.sourceReference ? ` · ${detail.change.sourceReference}` : ''}
                  </p>
                  <p className="text-copy-12 text-muted-foreground">
                    生效 {effectiveText(detail.change)}
                  </p>
                </div>
              </div>
              <div className="grid gap-2 md:grid-cols-2">
                <div className="border-border rounded-md border p-3">
                  <p className="text-label-13 font-semibold">上一格</p>
                  {detail.previous ? (
                    <p className="text-copy-13">{priceMoveText(detail.previous)}</p>
                  ) : (
                    <p className="text-copy-13 text-muted-foreground">这是最早的一条。</p>
                  )}
                </div>
                <div className="border-border rounded-md border p-3">
                  <p className="text-label-13 font-semibold">下一格</p>
                  {detail.next ? (
                    <p className="text-copy-13">{priceMoveText(detail.next)}</p>
                  ) : (
                    <p className="text-copy-13 text-muted-foreground">这是最新的一条。</p>
                  )}
                </div>
              </div>
            </div>
          ) : (
            <p className="text-muted-foreground text-copy-13">正在读变更详情……</p>
          )}
        </SectionCard>
      ) : null}

      {/* ============================================================ 单 listing 时间轴 */}
      <SectionCard
        title="单个 listing 的时间轴"
        description="一个 listing = 一个 SKU 在一个店铺。每一格的旧价必须接得上上一格的新价 —— 接不上就明说断在哪。"
      >
        <div className="mb-3 flex items-end gap-2">
          <Button onClick={() => void loadTimeline()} disabled={busy}>
            读时间轴
          </Button>
          <p className="text-copy-12 text-muted-foreground">
            用上面的 SKU ID + 平台码 + 站点码筛选条件。
          </p>
        </div>
        {timeline ? (
          <div className="space-y-3">
            <div className="grid gap-2 md:grid-cols-3">
              <div className="border-border rounded-md border p-3">
                <p className="text-label-13 font-semibold">当前价</p>
                <p className="text-copy-13">
                  {moneyWithCurrency(timeline.currentPrice, timeline.currency, '还没有价')}
                </p>
                <p className="text-copy-12 text-muted-foreground">
                  观测于 {timeText(timeline.currentPriceAt)}
                </p>
              </div>
              <div className="border-border rounded-md border p-3">
                <p className="text-label-13 font-semibold">变更格数</p>
                <p className="text-copy-13">{timeline.changeCount} 格</p>
                <p className="text-copy-12 text-muted-foreground">
                  {brokenChainCount > 0 ? `其中 ${brokenChainCount} 格链条断了` : '链条完整'}
                </p>
              </div>
              <div className="border-border rounded-md border p-3">
                <p className="text-label-13 font-semibold">listing</p>
                <p className="text-copy-13">
                  {listingText({
                    skuCode: timeline.skuCode,
                    platformCode: timeline.platformCode,
                    siteCode: timeline.siteCode,
                  })}
                </p>
              </div>
            </div>
            {timeline.note ? (
              <Notice tone="warning" title="覆盖范围">
                {timeline.note}
              </Notice>
            ) : null}
            {timeline.entries.length === 0 ? (
              <EmptyState
                title="这个 listing 还没有价格变更"
                description="价格变更来自上游（批量调价、调价单）与人工登记。"
              />
            ) : (
              <div className="space-y-2">
                {timeline.entries.map((entry) => (
                  <div key={entry.change.id} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={directionTone(entry.change.direction)}>
                        {entry.change.directionLabel}
                      </Badge>
                      <span className="text-copy-13">{priceMoveText(entry.change)}</span>
                      <span className="text-copy-12 text-muted-foreground">
                        {effectiveText(entry.change)}
                      </span>
                      <Badge tone={entry.chainConsistent ? 'success' : 'danger'}>
                        {entry.chainConsistent ? '链条接得上' : '链条断了'}
                      </Badge>
                    </div>
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      来源 {entry.change.changeSourceLabel} · 原因{' '}
                      {entry.change.reasonText || '（未写）'} · 累计价差{' '}
                      {signedMoneyText(entry.cumulativeDelta)}
                    </p>
                    {entry.chainNote ? (
                      <p className="text-copy-12 text-destructive mt-1">{entry.chainNote}</p>
                    ) : null}
                  </div>
                ))}
              </div>
            )}
            {timeline.pendingSuggestions.length > 0 ? (
              <Notice tone="info" title="待执行的调价建议（5.3.2.2，尚未生效）">
                <div className="space-y-1">
                  {timeline.pendingSuggestions.map((suggestion) => (
                    <p key={suggestion.id}>
                      {suggestion.ruleName || suggestion.ruleId}：
                      {moneyWithCurrency(
                        suggestion.currentPrice,
                        suggestion.currency,
                        '当前价未登记',
                      )}{' '}
                      →{' '}
                      {moneyWithCurrency(
                        suggestion.suggestedPrice,
                        suggestion.currency,
                        '建议价未给出',
                      )}
                      （{ratioText(suggestion.priceChangeRate)}）· {suggestion.status}
                      {suggestion.requiresConfirmation ? ' · 需人工确认' : ''}
                    </p>
                  ))}
                </div>
              </Notice>
            ) : null}
          </div>
        ) : (
          <p className="text-muted-foreground text-copy-13">
            填好 SKU + 平台码 + 站点码后点「读时间轴」。
          </p>
        )}
      </SectionCard>

      {/* ============================================================ 对比视图 */}
      <SectionCard
        title="降价到底有没有用（价格 + 利润 + 销量）"
        description="蓝图那句话要求三者对齐。销量那一列当前未接线 —— 它会明确写「未接线」，而不是 0。"
      >
        <div className="mb-3 grid gap-3 md:grid-cols-4">
          <FormField id="ph-cmp-kw" label="关键词">
            <Input
              id="ph-cmp-kw"
              value={comparisonFilters.keyword}
              placeholder="全部"
              onChange={(e) =>
                setComparisonFilters({ ...comparisonFilters, keyword: e.target.value, page: 0 })
              }
            />
          </FormField>
          <FormField id="ph-cmp-platform" label="平台码">
            <Input
              id="ph-cmp-platform"
              value={comparisonFilters.platformCode}
              placeholder="全部"
              onChange={(e) =>
                setComparisonFilters({
                  ...comparisonFilters,
                  platformCode: e.target.value,
                  page: 0,
                })
              }
            />
          </FormField>
          <FormField id="ph-cmp-site" label="站点码">
            <Input
              id="ph-cmp-site"
              value={comparisonFilters.siteCode}
              placeholder="全部"
              onChange={(e) =>
                setComparisonFilters({ ...comparisonFilters, siteCode: e.target.value, page: 0 })
              }
            />
          </FormField>
          <FormField id="ph-cmp-window" label="销量对比窗口（天）" hint="变更前后各取多少天">
            <Input
              id="ph-cmp-window"
              type="number"
              value={String(comparisonFilters.salesWindowDays)}
              onChange={(e) =>
                setComparisonFilters({
                  ...comparisonFilters,
                  salesWindowDays: Number(e.target.value) || 0,
                  page: 0,
                })
              }
            />
          </FormField>
        </div>
        <Button onClick={() => void loadComparison()} disabled={busy}>
          看对比
        </Button>
        {comparison ? (
          <div className="mt-3 space-y-2">
            <Notice
              tone={comparison.salesAvailable ? 'info' : 'warning'}
              title={comparison.salesAvailable ? '三个维度齐了' : '销量维度未接线'}
            >
              {comparison.note}
            </Notice>
            {comparison.items.length === 0 ? (
              <EmptyState title="当前没有可对比的变更" description="换个筛选条件再试。" />
            ) : (
              (comparison.items ?? []).map((row) => (
                <div key={row.id} className="border-border rounded-md border p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={directionTone(row.direction)}>
                      {directionLabel(row.direction)}
                    </Badge>
                    <span className="text-copy-13">{priceMoveText(row)}</span>
                    <span className="text-copy-12 text-muted-foreground">
                      {timeText(row.effectiveAt)}
                    </span>
                  </div>
                  <div className="mt-2 grid gap-2 md:grid-cols-3">
                    <div>
                      <p className="text-label-13 font-semibold">价格变化</p>
                      <p className="text-copy-13">
                        {signedMoneyText(row.priceDelta)}（{signedRatioText(row.changeRatio)}）
                      </p>
                    </div>
                    <div>
                      <p className="text-label-13 font-semibold">利润变化</p>
                      <p className="text-copy-13">
                        {profitText(row.grossProfitBefore, row.decisionReady, row.profitNote)} →{' '}
                        {profitText(row.grossProfitAfter, row.decisionReady, row.profitNote)}
                      </p>
                      <p className="text-copy-12 text-muted-foreground">
                        差额 {profitDeltaText(row.grossProfitDelta, row.decisionReady)}
                      </p>
                    </div>
                    <div>
                      <p className="text-label-13 font-semibold">销量变化</p>
                      <p className="text-copy-13">
                        {salesText(row.salesQuantityBefore, row.salesAvailable)} →{' '}
                        {salesText(row.salesQuantityAfter, row.salesAvailable)}
                      </p>
                      <p className="text-copy-12 text-muted-foreground">
                        差额 {salesDeltaText(row.salesQuantityDelta, row.salesAvailable)}
                      </p>
                    </div>
                  </div>
                </div>
              ))
            )}
          </div>
        ) : null}
      </SectionCard>

      {/* ============================================================ 登记变更 */}
      <SectionCard
        title="登记一次价格变更"
        description="人工登记也是一个价格变更来源。旧价留空 = 首次定价（不是「从 0 涨到新价」）。"
      >
        <div className="grid gap-3 md:grid-cols-4">
          <FormField id="ph-form-sku" label="SKU ID" required>
            <Input
              id="ph-form-sku"
              value={form.skuId}
              onChange={(e) => setForm({ ...form, skuId: e.target.value })}
            />
          </FormField>
          <FormField id="ph-form-platform" label="平台码" required>
            <Input
              id="ph-form-platform"
              value={form.platformCode}
              onChange={(e) => setForm({ ...form, platformCode: e.target.value })}
            />
          </FormField>
          <FormField id="ph-form-site" label="站点码" required>
            <Input
              id="ph-form-site"
              value={form.siteCode}
              onChange={(e) => setForm({ ...form, siteCode: e.target.value })}
            />
          </FormField>
          <FormField id="ph-form-currency" label="币种" required>
            <Input
              id="ph-form-currency"
              value={form.currency}
              onChange={(e) => setForm({ ...form, currency: e.target.value })}
            />
          </FormField>
          <FormField id="ph-form-old" label="旧价" hint="留空 = 首次定价">
            <Input
              id="ph-form-old"
              value={form.oldPrice ?? ''}
              onChange={(e) =>
                setForm({ ...form, oldPrice: e.target.value === '' ? null : e.target.value })
              }
            />
          </FormField>
          <FormField id="ph-form-new" label="新价" required>
            <Input
              id="ph-form-new"
              value={form.newPrice}
              onChange={(e) => setForm({ ...form, newPrice: e.target.value })}
            />
          </FormField>
          <FormField id="ph-form-source" label="变更来源" required>
            <Input
              id="ph-form-source"
              value={form.changeSource}
              placeholder={CHANGE_SOURCES.map((s) => s.code).join(' / ')}
              onChange={(e) => setForm({ ...form, changeSource: e.target.value })}
            />
          </FormField>
          <FormField id="ph-form-reason-code" label="原因编码" hint="用下面的字典里的编码">
            <Input
              id="ph-form-reason-code"
              value={form.reasonCode}
              onChange={(e) => setForm({ ...form, reasonCode: e.target.value })}
            />
          </FormField>
          <FormField id="ph-form-reason" label="变更原因" required>
            <Input
              id="ph-form-reason"
              value={form.reasonText}
              onChange={(e) => setForm({ ...form, reasonText: e.target.value })}
            />
          </FormField>
          <FormField id="ph-form-at" label="生效时刻" required>
            <Input
              id="ph-form-at"
              type="datetime-local"
              value={form.effectiveAt}
              onChange={(e) => setForm({ ...form, effectiveAt: e.target.value })}
            />
          </FormField>
          <FormField
            id="ph-form-fee"
            label="平台费率（手工假设）"
            hint="5.2.3.1 未交付；不填则利润测不准"
          >
            <Input
              id="ph-form-fee"
              value={form.platformFeeRate ?? ''}
              placeholder="0.15"
              onChange={(e) =>
                setForm({ ...form, platformFeeRate: e.target.value === '' ? null : e.target.value })
              }
            />
          </FormField>
          <FormField id="ph-form-logistics" label="每单位运费（手工假设）" hint="5.2.3.2 未交付">
            <Input
              id="ph-form-logistics"
              value={form.logisticsCostPerUnit ?? ''}
              placeholder="5"
              onChange={(e) =>
                setForm({
                  ...form,
                  logisticsCostPerUnit: e.target.value === '' ? null : e.target.value,
                })
              }
            />
          </FormField>
          <FormField id="ph-form-note" label="备注">
            <Input
              id="ph-form-note"
              value={form.note}
              onChange={(e) => setForm({ ...form, note: e.target.value })}
            />
          </FormField>
        </div>
        {problems.length > 0 ? (
          <Notice tone="danger" title="先补齐这些">
            <ul className="list-disc pl-5">
              {problems.map((problem) => (
                <li key={problem}>{problem}</li>
              ))}
            </ul>
          </Notice>
        ) : null}
        <div className="mt-3">
          <Button onClick={() => void submit()} disabled={busy}>
            登记
          </Button>
        </div>
      </SectionCard>

      {/* ============================================================ 原因字典 */}
      <SectionCard
        title="变更原因字典"
        description="字典是租户自己维护的；历史事实存的是当时的快照，所以改字典不会改写历史。"
      >
        <div className="grid gap-3 md:grid-cols-5">
          <FormField id="ph-reason-code" label="编码" required>
            <Input
              id="ph-reason-code"
              value={reasonForm.code}
              onChange={(e) => setReasonForm({ ...reasonForm, code: e.target.value })}
            />
          </FormField>
          <FormField id="ph-reason-label" label="名称" required>
            <Input
              id="ph-reason-label"
              value={reasonForm.label}
              onChange={(e) => setReasonForm({ ...reasonForm, label: e.target.value })}
            />
          </FormField>
          <FormField id="ph-reason-category" label="分类" required>
            <Input
              id="ph-reason-category"
              value={reasonForm.category}
              placeholder={REASON_CATEGORIES.map((c) => c.code).join(' / ')}
              onChange={(e) => setReasonForm({ ...reasonForm, category: e.target.value })}
            />
          </FormField>
          <FormField id="ph-reason-sort" label="排序">
            <Input
              id="ph-reason-sort"
              type="number"
              value={String(reasonForm.sortOrder)}
              onChange={(e) =>
                setReasonForm({ ...reasonForm, sortOrder: Number(e.target.value) || 0 })
              }
            />
          </FormField>
          <div className="flex items-end">
            <Button onClick={() => void submitReason()} disabled={busy}>
              保存原因
            </Button>
          </div>
        </div>
        {reasonProblems.length > 0 ? (
          <Notice tone="danger" title="先补齐这些">
            <ul className="list-disc pl-5">
              {reasonProblems.map((problem) => (
                <li key={problem}>{problem}</li>
              ))}
            </ul>
          </Notice>
        ) : null}
        {reasons.length === 0 ? (
          <EmptyState
            title="还没有变更原因"
            description="自由文本写出来的原因筛不出来 —— 建一条字典项，时间轴才能按原因真筛选。"
          />
        ) : (
          <div className="mt-3 space-y-1">
            {reasons.map((reason) => (
              <div
                key={reason.id}
                className="border-border flex items-center gap-2 rounded-md border p-2"
              >
                <Badge tone={reason.enabled ? 'success' : 'neutral'}>
                  {reason.enabled ? '启用' : '停用'}
                </Badge>
                <span className="text-copy-13 font-semibold">{reason.label}</span>
                <span className="text-copy-12 text-muted-foreground">{reason.code}</span>
                <span className="text-copy-12 text-muted-foreground">{reason.categoryLabel}</span>
              </div>
            ))}
          </div>
        )}
      </SectionCard>
    </div>
  );
}

/** 时间轴上的一行。 */
function ChangeRowView({
  change,
  selected,
  onSelect,
}: {
  change: PriceChangeRow;
  selected: boolean;
  onSelect: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      className={`border-border w-full rounded-md border p-3 text-left ${
        selected ? 'border-brand bg-muted/40' : ''
      }`}
    >
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={directionTone(change.direction)}>{change.directionLabel}</Badge>
        <span className="text-copy-13">{priceMoveText(change)}</span>
        <span className="text-copy-12 text-muted-foreground">
          {change.skuCode || '（SKU 未命名）'} @ {change.platformCode}:{change.siteCode}
        </span>
        <span className="text-copy-12 text-muted-foreground">{effectiveText(change)}</span>
      </div>
      <div className="text-copy-12 text-muted-foreground mt-1 flex flex-wrap gap-3">
        <span>来源 {change.changeSourceLabel}</span>
        <span>原因 {change.reasonText || '（未写）'}</span>
        <span>投递方 {change.sourceModule}</span>
        <span>
          毛利 {profitText(change.grossProfitAfter, change.decisionReady, change.profitNote)}（前{' '}
          {moneyText(change.grossProfitBefore, '未测准')}）
        </span>
      </div>
    </button>
  );
}
