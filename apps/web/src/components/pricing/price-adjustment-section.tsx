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
  PRICE_ADJUSTMENT_SECTION,
  approvalDecisionLabel,
  approvalSourceLabel,
  createPriceAdjustment,
  deltaText,
  emptyPriceAdjustmentFilters,
  emptyPriceAdjustmentRequest,
  executePriceAdjustment,
  fetchCurrentPrice,
  fetchPriceAdjustment,
  fetchPriceAdjustmentCapabilities,
  fetchPriceAdjustments,
  lossText,
  moneyText,
  percentText,
  priceAdjustmentSummary,
  priceSourceLabel,
  priceText,
  refreshPriceAdjustmentApproval,
  rejectPriceAdjustment,
  statusLabel,
  statusTone,
  submitPriceAdjustment,
  validatePriceAdjustmentRequest,
} from './price-adjustment-model';
import type {
  PriceAdjustmentCapabilities,
  PriceAdjustmentCurrentPrice,
  PriceAdjustmentDetail,
  PriceAdjustmentFilters,
  PriceAdjustmentPage,
  PriceAdjustmentRequest,
  PriceAdjustmentValidationError,
} from './price-adjustment-model';

/**
 * 5.4.1.1 调价单工作区。
 *
 * <p>🔴 这一块是**受控动作**：建单 → 提交（要审批就停在待审）→ 已批 → 执行。
 * 每一次状态流转都把**当前详情里的 version** 当作 `expectedVersion` 带回去 ——
 * 对不上后端就 409，所以页面永远不拿本地缓存的那一份去改。
 *
 * <p>🔴 审批状态是**拉取式**的：审批人在 17 组织那边决定，这里靠「刷新审批状态」
 * 与「读详情时顺带刷新」把结论取回来。拉不到时单子停在「待审」，
 * 并把失败原因显示出来 —— 页面不会假装它已批。
 *
 * <p>三态齐全：加载态 / 空态 / 错误态。
 */
export function PriceAdjustmentSection() {
  const [capabilities, setCapabilities] = useState<PriceAdjustmentCapabilities | null>(null);
  const [request, setRequest] = useState<PriceAdjustmentRequest>(emptyPriceAdjustmentRequest());
  const [currentPrice, setCurrentPrice] = useState<PriceAdjustmentCurrentPrice | null>(null);
  const [orders, setOrders] = useState<PriceAdjustmentPage | null>(null);
  const [selected, setSelected] = useState<PriceAdjustmentDetail | null>(null);
  const [filters, setFilters] = useState<PriceAdjustmentFilters>(emptyPriceAdjustmentFilters());
  const [page, setPage] = useState(0);
  const [problem, setProblem] = useState<PriceAdjustmentValidationError | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);

  const load = useCallback(async () => {
    try {
      const [caps, orderPage] = await Promise.all([
        fetchPriceAdjustmentCapabilities(),
        fetchPriceAdjustments(emptyPriceAdjustmentFilters(), 0, 20).catch(() => null),
      ]);
      setCapabilities(caps);
      if (orderPage) setOrders(orderPage);
    } catch (e) {
      setError(e instanceof Error ? e.message : '调价单读不到');
    } finally {
      setLoaded(true);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const patch = useCallback((next: Partial<PriceAdjustmentRequest>) => {
    setRequest((prev) => ({ ...prev, ...next }));
    setProblem(null);
    // 🔴 参数一改，之前读到的当前价就**过期**了 —— 旧价是表单的一部分。
    setCurrentPrice(null);
  }, []);

  const patchFilters = useCallback((next: Partial<PriceAdjustmentFilters>) => {
    setFilters((prev) => ({ ...prev, ...next }));
    setPage(0);
  }, []);

  const reloadList = useCallback(async () => {
    const orderPage = await fetchPriceAdjustments(filters, page, 20);
    setOrders(orderPage);
  }, [filters, page]);

  // ---- 查当前价 ----
  async function runCurrentPrice() {
    if (!request.skuId.trim() || !request.platformCode.trim() || !request.siteCode.trim()) {
      setProblem({ field: 'skuId', message: '先填 SKU、平台与站点，才能查当前价' });
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const found = await fetchCurrentPrice(
        request.skuId.trim(),
        request.platformCode.trim().toUpperCase(),
        request.siteCode.trim().toUpperCase(),
        request.currency.toUpperCase(),
      );
      setCurrentPrice(found);
      if (found.price === null) {
        setNotice(`取不到当前价：${found.reason}`);
      } else {
        // 🔴 把你看到的旧价**自动填上** —— 这样建单时服务端能校验它有没有过期。
        patch({ oldPrice: found.price });
        setNotice(`当前价 ${priceText(found.price)} ${found.currency}（${priceSourceLabel(found.source)}）。`);
      }
    } catch (e) {
      setCurrentPrice(null);
      setError(e instanceof Error ? e.message : '当前价读不到');
    } finally {
      setBusy(false);
    }
  }

  // ---- 建单（草稿）----
  async function runCreate() {
    const invalid = validatePriceAdjustmentRequest(request);
    if (invalid) {
      setProblem(invalid);
      setError('');
      setNotice('');
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const detail = await createPriceAdjustment(request);
      setSelected(detail);
      await reloadList();
      setNotice(`已建单（草稿）：${priceAdjustmentSummary(detail)}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : '建单失败，请稍后重试');
    } finally {
      setBusy(false);
    }
  }

  // ---- 状态流转 ----
  async function transition(
    what: string,
    work: (detail: PriceAdjustmentDetail) => Promise<PriceAdjustmentDetail>,
  ) {
    if (!selected) {
      setError('先选一张调价单');
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const detail = await work(selected);
      setSelected(detail);
      await reloadList();
      setNotice(`${what}完成：当前状态「${statusLabel(detail.status)}」（版本 ${detail.version}）。`);
    } catch (e) {
      setError(e instanceof Error ? e.message : `${what}失败，请稍后重试`);
    } finally {
      setBusy(false);
    }
  }

  const stats = useMemo(() => {
    if (!orders) return [];
    return [
      { label: '调价单', value: orders.total, hint: '当前筛选下共多少张', tone: 'info' as const },
      {
        label: '本页已批 / 已执行',
        value: orders.items.filter(
          (o) => o.status === 'APPROVED' || o.status === 'EXECUTED',
        ).length,
        hint: '可执行 / 已生效',
        tone: 'success' as const,
      },
      {
        label: '本页待审',
        value: orders.items.filter((o) => o.status === 'PENDING_APPROVAL').length,
        hint: '等 17 组织批 —— 拉取式同步',
        tone: 'warning' as const,
      },
    ];
  }, [orders]);

  const canSubmit = selected?.status === 'DRAFT';
  const canExecute = selected?.status === 'APPROVED';
  const canReject =
    selected?.status === 'DRAFT' ||
    (selected?.status === 'PENDING_APPROVAL' && !selected.approvalInstanceId);
  const canRefresh = selected?.status === 'PENDING_APPROVAL';

  return (
    <div className="space-y-4">
      <SectionCard
        title={PRICE_ADJUSTMENT_SECTION.title}
        description={PRICE_ADJUSTMENT_SECTION.description}
      >
        {capabilities ? (
          <div className="grid gap-2 md:grid-cols-2">
            <Notice tone="warning" title="listing 的定义">
              {capabilities.listingDefinitionNote}
            </Notice>
            <Notice tone="warning" title="当前价来源">
              {capabilities.priceSourceNote}
            </Notice>
            <Notice tone="danger" title="审批">
              {capabilities.approvalNote}
            </Notice>
            <Notice tone="info" title="审批状态同步">
              {capabilities.approvalSyncNote}
            </Notice>
            <Notice tone="info" title="状态机">
              {capabilities.stateMachineNote}
            </Notice>
            <Notice tone="danger" title="乐观锁">
              {capabilities.versionNote}
            </Notice>
            <Notice tone="info" title="利润口径">
              {capabilities.profitNote}
            </Notice>
            <Notice tone="info" title="舍入">
              {capabilities.roundingNote}
            </Notice>
            <Notice tone="info" title="上游">
              {capabilities.upstreamNote}
            </Notice>
            <Notice tone="info" title="下游">
              {capabilities.downstreamNote}
            </Notice>
            <Notice tone="info" title="权限">
              {capabilities.permissionNote}
            </Notice>
          </div>
        ) : busy && !loaded ? (
          <p className="text-muted-foreground text-copy-13">正在读能力声明……</p>
        ) : (
          <p className="text-muted-foreground text-copy-13">能力声明读不到。</p>
        )}
      </SectionCard>

      {error ? (
        <Notice tone="danger" title="出错了">
          {error}
        </Notice>
      ) : null}
      {notice ? (
        <Notice tone="info" title="已处理">
          {notice}
        </Notice>
      ) : null}

      {/* ============================================================ 建单 */}
      <SectionCard
        title="发起调价"
        description="选择 SKU 与目标店铺、输入新旧价、说明原因；平台费率与物流单价是手工假设（5.2.3.1 / 5.2.3.2 未交付）。"
      >
        <div className="grid gap-3 md:grid-cols-4">
          <FormField
            id="pa-sku"
            label="SKU"
            required
            hint="被调价的那个 SKU（UUID）"
            error={problem?.field === 'skuId' ? problem.message : undefined}
          >
            <Input
              id="pa-sku"
              value={request.skuId}
              placeholder="SKU UUID"
              onChange={(e) => patch({ skuId: e.target.value.trim() })}
            />
          </FormField>
          <FormField
            id="pa-platform"
            label="平台"
            required
            hint="平台码，如 AMAZON"
            error={problem?.field === 'platformCode' ? problem.message : undefined}
          >
            <Input
              id="pa-platform"
              value={request.platformCode}
              onChange={(e) => patch({ platformCode: e.target.value.toUpperCase() })}
            />
          </FormField>
          <FormField
            id="pa-site"
            label="站点"
            required
            hint="店铺站点码，如 US"
            error={problem?.field === 'siteCode' ? problem.message : undefined}
          >
            <Input
              id="pa-site"
              value={request.siteCode}
              onChange={(e) => patch({ siteCode: e.target.value.toUpperCase() })}
            />
          </FormField>
          <FormField
            id="pa-currency"
            label="售价币种"
            required
            hint="三位大写字母，如 USD"
            error={problem?.field === 'currency' ? problem.message : undefined}
          >
            <Input
              id="pa-currency"
              value={request.currency}
              onChange={(e) => patch({ currency: e.target.value.toUpperCase() })}
            />
          </FormField>
          <FormField
            id="pa-cost-currency"
            label="成本币种"
            hint="空 = 与售价同币种"
            error={problem?.field === 'costCurrency' ? problem.message : undefined}
          >
            <Input
              id="pa-cost-currency"
              value={request.costCurrency}
              onChange={(e) => patch({ costCurrency: e.target.value.toUpperCase() })}
            />
          </FormField>
          <FormField
            id="pa-fx"
            label="汇率"
            hint="1 成本币种 = X 售价币种"
            error={problem?.field === 'fxRate' ? problem.message : undefined}
          >
            <Input
              id="pa-fx"
              type="number"
              step="0.0001"
              value={request.fxRate ?? ''}
              onChange={(e) =>
                patch({ fxRate: e.target.value === '' ? null : Number(e.target.value) })
              }
            />
          </FormField>
          <FormField
            id="pa-old-price"
            label="旧价"
            hint="你看到的那个价；留空 = 以服务端解析到的当前价为准"
            error={problem?.field === 'oldPrice' ? problem.message : undefined}
          >
            <Input
              id="pa-old-price"
              type="number"
              step="0.01"
              value={request.oldPrice ?? ''}
              onChange={(e) =>
                patch({ oldPrice: e.target.value === '' ? null : Number(e.target.value) })
              }
            />
          </FormField>
          <FormField
            id="pa-new-price"
            label="新价"
            required
            hint="人输入的新价，2 位小数 HALF_UP 落定"
            error={problem?.field === 'newPrice' ? problem.message : undefined}
          >
            <Input
              id="pa-new-price"
              type="number"
              step="0.01"
              value={request.newPrice || ''}
              onChange={(e) => patch({ newPrice: e.target.value === '' ? 0 : Number(e.target.value) })}
            />
          </FormField>
          <FormField
            id="pa-quantity"
            label="数量"
            hint="用于阶梯与运费，空 = 1"
            error={problem?.field === 'quantity' ? problem.message : undefined}
          >
            <Input
              id="pa-quantity"
              type="number"
              step="0.000001"
              value={request.quantity ?? ''}
              onChange={(e) =>
                patch({ quantity: e.target.value === '' ? null : Number(e.target.value) })
              }
            />
          </FormField>
          <FormField
            id="pa-fee"
            label="平台费率（手工假设）"
            hint="0.15 = 15%"
            error={problem?.field === 'platformFeeRate' ? problem.message : undefined}
          >
            <Input
              id="pa-fee"
              type="number"
              step="0.01"
              value={request.platformFeeRate ?? ''}
              onChange={(e) =>
                patch({ platformFeeRate: e.target.value === '' ? null : Number(e.target.value) })
              }
            />
          </FormField>
          <FormField
            id="pa-logistics"
            label="物流每单位运费（手工假设）"
            hint="每单位尾程运费"
            error={problem?.field === 'logisticsCostPerUnit' ? problem.message : undefined}
          >
            <Input
              id="pa-logistics"
              type="number"
              step="0.01"
              value={request.logisticsCostPerUnit ?? ''}
              onChange={(e) =>
                patch({ logisticsCostPerUnit: e.target.value === '' ? null : Number(e.target.value) })
              }
            />
          </FormField>
          <FormField id="pa-title" label="标题" hint="提交审批实例时作为单据标题">
            <Input
              id="pa-title"
              value={request.title}
              onChange={(e) => patch({ title: e.target.value })}
            />
          </FormField>
        </div>

        <div className="mt-3 grid gap-3 md:grid-cols-2">
          <FormField
            id="pa-reason"
            label="调价原因"
            required
            hint="必填 —— 事后要能看懂这次为什么调"
            error={problem?.field === 'reason' ? problem.message : undefined}
          >
            <Input
              id="pa-reason"
              value={request.reason}
              placeholder="例如：竞品降价跟价"
              onChange={(e) => patch({ reason: e.target.value })}
            />
          </FormField>
          <FormField id="pa-note" label="备注" hint="补充说明">
            <Input id="pa-note" value={request.note} onChange={(e) => patch({ note: e.target.value })} />
          </FormField>
        </div>

        {currentPrice ? (
          <div className="mt-3">
            {currentPrice.price === null ? (
              <Notice tone="danger" title="取不到当前价">
                {currentPrice.reason}
              </Notice>
            ) : (
              <Notice tone="info" title="当前价">
                {priceText(currentPrice.price)} {currentPrice.currency}（来源：
                {priceSourceLabel(currentPrice.source)}；观测于 {currentPrice.observedAt}）
              </Notice>
            )}
          </div>
        ) : null}

        <div className="mt-3 flex flex-wrap gap-2">
          <Button variant="outline" disabled={busy} onClick={() => void runCurrentPrice()}>
            查当前价
          </Button>
          <Button variant="brand" disabled={busy} onClick={() => void runCreate()}>
            建单（草稿）
          </Button>
        </div>
      </SectionCard>

      {/* ============================================================ 台账 */}
      <SectionCard title="调价单台账" description="真分页 + 真筛选；点一行看详情与状态流转。">
        {stats.length ? (
          <div className="mb-3 grid gap-2 md:grid-cols-3">
            {stats.map((s) => (
              <StatCard key={s.label} label={s.label} value={s.value} comparisonLabel={s.hint} />
            ))}
          </div>
        ) : null}

        <div className="grid gap-3 md:grid-cols-5">
          <FormField id="paf-status" label="状态">
            <select
              id="paf-status"
              className="border-border bg-background text-copy-13 h-9 w-full rounded-md border px-3"
              value={filters.status}
              onChange={(e) =>
                patchFilters({ status: e.target.value as PriceAdjustmentFilters['status'] })
              }
            >
              <option value="">全部</option>
              <option value="DRAFT">草稿</option>
              <option value="PENDING_APPROVAL">待审</option>
              <option value="APPROVED">已批</option>
              <option value="EXECUTED">已执行</option>
              <option value="REJECTED">已驳回</option>
            </select>
          </FormField>
          <FormField id="paf-sku" label="SKU">
            <Input
              id="paf-sku"
              value={filters.skuId}
              onChange={(e) => patchFilters({ skuId: e.target.value.trim() })}
            />
          </FormField>
          <FormField id="paf-platform" label="平台">
            <Input
              id="paf-platform"
              value={filters.platformCode}
              onChange={(e) => patchFilters({ platformCode: e.target.value.toUpperCase() })}
            />
          </FormField>
          <FormField id="paf-site" label="站点">
            <Input
              id="paf-site"
              value={filters.siteCode}
              onChange={(e) => patchFilters({ siteCode: e.target.value.toUpperCase() })}
            />
          </FormField>
          <FormField id="paf-keyword" label="关键字">
            <Input
              id="paf-keyword"
              value={filters.keyword}
              placeholder="SKU 编码 / 标题 / 原因"
              onChange={(e) => patchFilters({ keyword: e.target.value })}
            />
          </FormField>
        </div>

        {orders && orders.items.length ? (
          <div className="mt-3 space-y-2">
            {orders.items.map((order) => (
              <button
                key={order.id}
                type="button"
                className="border-border bg-card hover:bg-muted block w-full rounded-md border px-3 py-2 text-left"
                onClick={() => {
                  setSelected(order);
                  setError('');
                  setNotice('');
                  void fetchPriceAdjustment(order.id)
                    .then((detail) => setSelected(detail))
                    .catch((e) =>
                      setError(e instanceof Error ? e.message : '调价单详情读不到'),
                    );
                }}
              >
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={statusTone(order.status)}>{statusLabel(order.status)}</Badge>
                  <span className="text-copy-13 text-foreground">{priceAdjustmentSummary(order)}</span>
                  <span className="text-muted-foreground text-copy-12">版本 {order.version}</span>
                </div>
                <div className="text-muted-foreground text-copy-12 mt-1">
                  {order.approvalRequired
                    ? `需审批（${approvalDecisionLabel(order.approvalDecision)}）`
                    : '免审'}
                  {order.approvalInstanceNo ? ` · 审批实例 ${order.approvalInstanceNo}` : ''}
                  {order.approvalSyncError ? ` · 同步失败：${order.approvalSyncError}` : ''}
                </div>
              </button>
            ))}
            <div className="flex items-center justify-between pt-1">
              <span className="text-muted-foreground text-copy-12">
                第 {orders.page + 1} 页 · 共 {orders.total} 张
              </span>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page <= 0}
                  onClick={() => setPage((p) => Math.max(p - 1, 0))}
                >
                  上一页
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={(page + 1) * orders.size >= orders.total}
                  onClick={() => setPage((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          </div>
        ) : orders ? (
          <EmptyState
            title="没有匹配的调价单"
            description="换个筛选条件，或在上方发起一次调价。"
          />
        ) : (
          <p className="text-muted-foreground text-copy-13">正在读调价单台账……</p>
        )}
      </SectionCard>

      {/* ============================================================ 详情 */}
      {selected ? (
        <SectionCard
          title="调价单详情"
          description={`版本 ${selected.version} —— 每次状态流转都要把这个版本号带回去（对不上就是 409）。`}
        >
          <div className="grid gap-2 md:grid-cols-4">
            <StatCard label="状态" value={statusLabel(selected.status)} comparisonLabel={`版本 ${selected.version}`} />
            <StatCard
              label="变更前后"
              value={`${priceText(selected.oldPrice)} → ${priceText(selected.newPrice)}`}
              comparisonLabel={`${deltaText(selected.priceDelta)} ${selected.currency}`}
            />
            <StatCard
              label="改价后毛利率"
              value={percentText(selected.grossMarginAfter, '不可信')}
              comparisonLabel={`改价前 ${percentText(selected.grossMarginBefore, '不可信')}`}
            />
            <StatCard
              label="改价后毛利额"
              value={moneyText(selected.grossProfitAfter)}
              comparisonLabel={`改价前 ${moneyText(selected.grossProfitBefore)} · ${lossText(selected.lossAfter)}`}
            />
          </div>

          <div className="mt-3 space-y-2">
            <Notice tone="info" title="审批">
              {selected.approvalRequired ? '需审批' : '免审'}（结论：
              {approvalDecisionLabel(selected.approvalDecision)}；来源：
              {approvalSourceLabel(selected.approvalSource)}）
              {selected.approvalRuleName ? ` · 命中规则「${selected.approvalRuleName}」` : ''}
              {selected.approvalFlowName ? ` · 目标流程「${selected.approvalFlowName}」` : ''}
              {selected.approvalReason ? ` —— ${selected.approvalReason}` : ''}
              {selected.approvalInstanceNo ? ` · 审批实例 ${selected.approvalInstanceNo}` : ''}
            </Notice>
            {selected.approvalSyncError ? (
              <Notice tone="danger" title="审批状态同步失败">
                {selected.approvalSyncError} —— 单子停在「待审」，不会被当成已批。
              </Notice>
            ) : null}
            {selected.rejectReason ? (
              <Notice tone="warning" title="驳回原因">
                {selected.rejectReason}
              </Notice>
            ) : null}
          </div>

          <div className="mt-3 flex flex-wrap gap-2">
            <Button
              variant="brand"
              size="sm"
              disabled={busy || !canSubmit}
              onClick={() =>
                void transition('提交', (detail) =>
                  submitPriceAdjustment(detail.id, detail.version),
                )
              }
            >
              提交（需审批则停在待审）
            </Button>
            <Button
              variant="success"
              size="sm"
              disabled={busy || !canExecute}
              onClick={() =>
                void transition('执行', (detail) =>
                  executePriceAdjustment(detail.id, detail.version),
                )
              }
            >
              执行（新价生效）
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={busy || !canRefresh}
              onClick={() =>
                void transition('刷新审批状态', (detail) =>
                  refreshPriceAdjustmentApproval(detail.id),
                )
              }
            >
              刷新审批状态
            </Button>
            <Button
              variant="destructive"
              size="sm"
              disabled={busy || !canReject}
              onClick={() => {
                const reason = window.prompt('驳回原因（必填）');
                if (!reason) return;
                void transition('驳回', (detail) =>
                  rejectPriceAdjustment(detail.id, detail.version, reason),
                );
              }}
            >
              驳回
            </Button>
          </div>

          {selected.items.length ? (
            <div className="mt-3 space-y-1">
              {selected.items.map((item) => (
                <div
                  key={item.id ?? item.skuId}
                  className="border-border bg-card rounded-md border px-3 py-2"
                >
                  <div className="text-copy-13 text-foreground">
                    {item.skuCode} · {item.skuName} · {item.platformCode}/{item.siteCode}
                  </div>
                  <div className="text-muted-foreground text-copy-12 mt-1">
                    {priceText(item.oldPrice)} → {priceText(item.newPrice)} {item.currency}（
                    {deltaText(item.priceDelta)}）· 来源 {item.priceSourceLabel} · 观测于{' '}
                    {item.priceObservedAt}
                  </div>
                  <div className="text-muted-foreground text-copy-12 mt-1">
                    毛利 {moneyText(item.grossProfitBefore)} → {moneyText(item.grossProfitAfter)} ·
                    毛利率 {percentText(item.grossMarginBefore, '不可信')} →{' '}
                    {percentText(item.grossMarginAfter, '不可信')} · 亏损 {lossText(item.lossBefore)} →{' '}
                    {lossText(item.lossAfter)}
                  </div>
                </div>
              ))}
            </div>
          ) : null}
        </SectionCard>
      ) : null}
    </div>
  );
}
