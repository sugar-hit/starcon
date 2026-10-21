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
  ADJUSTMENT_SECTION,
  adjustModeLabel,
  deltaText,
  emptyFilters,
  emptyRequest,
  executeBatchAdjustment,
  fetchAdjustmentCapabilities,
  fetchAdjustmentItems,
  fetchAdjustments,
  fetchAdjustmentSites,
  hasShop,
  itemStatusLabel,
  itemTone,
  lossText,
  moneyText,
  percentText,
  previewBatchAdjustment,
  previewStats,
  priceSourceLabel,
  priceText,
  scopeSummary,
  toggleShop,
  validateRequest,
} from './batch-price-adjustment-model';
import type {
  AdjustmentItemFilters,
  AdjustmentValidationError,
  BatchPriceAdjustmentCapabilities,
  BatchPriceAdjustmentDetail,
  BatchPriceAdjustmentItem,
  BatchPriceAdjustmentItemPage,
  BatchPriceAdjustmentPage,
  BatchPriceAdjustmentPreview,
  BatchPriceAdjustmentRequest,
  SiteOption,
} from './batch-price-adjustment-model';

/**
 * 5.4.1.2 批量调价工作区。
 *
 * <p>🔴 这一块**先预览、再确认、后执行** —— 预览不落库，后端执行时会重算预览并
 * 逐字比对预览令牌，对不上就 409 拒绝。所以「确认执行」按钮只在**看过预览**之后才可用。
 *
 * <p>🔴 预览报告里的**反向指标**（改价后预计亏损的商品数）与「预计利润变化」并排显示：
 * 一批 listing 整体利润上升，也可能是因为其中几个从「小赚」变成「巨亏」，
 * 而那几个正是要清的品。只看利润变化会漏掉这一类风险。
 *
 * <p>三态齐全：加载态 / 空态 / 错误态。
 */
export function BatchPriceAdjustmentSection() {
  const [capabilities, setCapabilities] = useState<BatchPriceAdjustmentCapabilities | null>(null);
  const [sites, setSites] = useState<SiteOption[]>([]);
  const [request, setRequest] = useState<BatchPriceAdjustmentRequest>(emptyRequest());
  const [preview, setPreview] = useState<BatchPriceAdjustmentPreview | null>(null);
  const [orders, setOrders] = useState<BatchPriceAdjustmentPage | null>(null);
  const [selected, setSelected] = useState<BatchPriceAdjustmentDetail | null>(null);
  const [filters, setFilters] = useState<AdjustmentItemFilters>(emptyFilters());
  const [items, setItems] = useState<BatchPriceAdjustmentItemPage | null>(null);
  const [page, setPage] = useState(0);
  const [problem, setProblem] = useState<AdjustmentValidationError | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);

  const load = useCallback(async () => {
    try {
      const [caps, siteList, orderPage] = await Promise.all([
        fetchAdjustmentCapabilities(),
        fetchAdjustmentSites().catch(() => [] as SiteOption[]),
        fetchAdjustments(0, 20).catch(() => null),
      ]);
      setCapabilities(caps);
      setSites(siteList);
      if (orderPage) setOrders(orderPage);
    } catch (e) {
      setError(e instanceof Error ? e.message : '批量调价读不到');
    } finally {
      setLoaded(true);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const patch = useCallback((next: Partial<BatchPriceAdjustmentRequest>) => {
    setRequest((prev) => ({ ...prev, ...next }));
    setProblem(null);
    // 🔴 参数一改，上一次的预览就**过期**了 —— 执行时后端会重算并比对令牌，
    // 所以这里必须把预览清掉，否则用户会看着一份已经过期的报告点「确认执行」。
    setPreview(null);
  }, []);

  const patchFilters = useCallback((next: Partial<AdjustmentItemFilters>) => {
    setFilters((prev) => ({ ...prev, ...next }));
    setPage(0);
  }, []);

  // ---- 预览 ----
  async function runPreview() {
    const invalid = validateRequest(request);
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
      const report = await previewBatchAdjustment(request);
      setPreview(report);
      setNotice(
        `预览完成：涉及 ${report.shopCount} 个店铺、${report.skuCount} 个商品，` +
          `${report.willAdjustCount} 个 listing 会改价，${report.skippedCount} 个被跳过。` +
          `确认无误后再点「确认执行」。`,
      );
    } catch (e) {
      setPreview(null);
      setError(e instanceof Error ? e.message : '预览失败，请稍后重试');
    } finally {
      setBusy(false);
    }
  }

  // ---- 执行 ----
  async function runExecute() {
    if (!preview) {
      setError('必须先预览 —— 蓝图那条「必须先看预览再执行」是硬约束。');
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const detail = await executeBatchAdjustment(request, preview.previewToken);
      setSelected(detail);
      setPreview(null);
      setFilters(emptyFilters());
      setPage(0);
      const orderPage = await fetchAdjustments(0, 20);
      setOrders(orderPage);
      setNotice(
        `已执行批量调价：${detail.willAdjustCount} 个 listing 改价，` +
          `${detail.skippedCount} 个跳过，改价后预计亏损商品数 ${detail.lossAfterSkuCount}。`,
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : '执行失败，请稍后重试');
    } finally {
      setBusy(false);
    }
  }

  // ---- 已执行调价单的明细（真筛选 + 真分页）----
  const loadItems = useCallback(
    async (adjustmentId: string, nextPage: number) => {
      try {
        const data = await fetchAdjustmentItems(adjustmentId, filters, nextPage, 20);
        setItems(data);
      } catch (e) {
        setError(e instanceof Error ? e.message : '明细读不到');
      }
    },
    [filters],
  );

  useEffect(() => {
    if (!selected) {
      setItems(null);
      return;
    }
    void loadItems(selected.id, page);
  }, [selected, page, loadItems]);

  const stats = useMemo(() => previewStats(preview), [preview]);
  const shopOptions = useMemo(
    () => sites.map((site) => `${site.platformCode}/${site.siteCode}`),
    [sites],
  );

  return (
    <div className="space-y-4">
      <SectionCard title={ADJUSTMENT_SECTION.title} description={ADJUSTMENT_SECTION.description}>
        {capabilities ? (
          <div className="grid gap-2 md:grid-cols-2">
            <Notice tone="warning" title="listing 的定义">
              {capabilities.listingDefinitionNote}
            </Notice>
            <Notice tone="warning" title="当前价来源">
              {capabilities.priceSourceNote}
            </Notice>
            <Notice tone="info" title="上游">
              {capabilities.upstreamNote}
            </Notice>
            <Notice tone="info" title="下游">
              {capabilities.downstreamNote}
            </Notice>
            <Notice tone="danger" title="预览闸">
              {capabilities.previewGateNote}
            </Notice>
            <Notice tone="danger" title="反向指标">
              {capabilities.reverseMetricNote}
            </Notice>
            <Notice tone="info" title="舍入">
              {capabilities.roundingNote}
            </Notice>
            <Notice tone="info" title="跳过">
              {capabilities.skipNote}
            </Notice>
            <Notice tone="info" title="上限">
              {capabilities.limitNote}
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

      {/* ============================================================ 调价参数 */}
      <SectionCard
        title="调价参数"
        description="目标集合 = SKU 范围（分类 / 品牌）× 店铺清单；平台费率与物流单价是手工假设（5.2.3.1 / 5.2.3.2 未交付）。"
      >
        <div className="grid gap-3 md:grid-cols-4">
          <FormField
            id="bpa-mode"
            label="调价方式"
            required
            error={problem?.field === 'adjustMode' ? problem.message : undefined}
          >
            <select
              id="bpa-mode"
              className="border-border bg-background text-copy-13 h-9 w-full rounded-md border px-3"
              value={request.adjustMode}
              onChange={(e) => patch({ adjustMode: e.target.value as BatchPriceAdjustmentRequest['adjustMode'] })}
            >
              <option value="PERCENT">百分比</option>
              <option value="FIXED_AMOUNT">固定金额</option>
              <option value="TEMPLATE">按模板</option>
            </select>
          </FormField>
          <FormField
            id="bpa-amount"
            label="固定金额"
            hint="可负 = 降价"
            error={problem?.field === 'adjustAmount' ? problem.message : undefined}
          >
            <Input
              id="bpa-amount"
              type="number"
              step="0.01"
              disabled={request.adjustMode !== 'FIXED_AMOUNT'}
              value={request.adjustAmount ?? ''}
              onChange={(e) =>
                patch({ adjustAmount: e.target.value === '' ? null : Number(e.target.value) })
              }
            />
          </FormField>
          <FormField
            id="bpa-rate"
            label="涨跌幅"
            hint="0.1 = +10%，小数口径"
            error={problem?.field === 'adjustRate' ? problem.message : undefined}
          >
            <Input
              id="bpa-rate"
              type="number"
              step="0.01"
              disabled={request.adjustMode !== 'PERCENT'}
              value={request.adjustRate ?? ''}
              onChange={(e) =>
                patch({ adjustRate: e.target.value === '' ? null : Number(e.target.value) })
              }
            />
          </FormField>
          <FormField
            id="bpa-template"
            label="定价模板"
            hint="按模板方式：模板 id（5.3.2.1）"
            error={problem?.field === 'templateId' ? problem.message : undefined}
          >
            <Input
              id="bpa-template"
              disabled={request.adjustMode !== 'TEMPLATE'}
              value={request.templateId ?? ''}
              placeholder="模板 UUID"
              onChange={(e) => patch({ templateId: e.target.value.trim() || null })}
            />
          </FormField>
          <FormField
            id="bpa-category"
            label="分类"
            hint="精确匹配，空 = 不限定"
            error={problem?.field === 'category' ? problem.message : undefined}
          >
            <Input
              id="bpa-category"
              value={request.category}
              placeholder="例如：家居"
              onChange={(e) => patch({ category: e.target.value })}
            />
          </FormField>
          <FormField
            id="bpa-brand"
            label="品牌"
            hint="精确匹配，空 = 不限定"
            error={problem?.field === 'brand' ? problem.message : undefined}
          >
            <Input
              id="bpa-brand"
              value={request.brand}
              placeholder="例如：Acme"
              onChange={(e) => patch({ brand: e.target.value })}
            />
          </FormField>
          <FormField
            id="bpa-currency"
            label="售价币种"
            required
            error={problem?.field === 'currency' ? problem.message : undefined}
          >
            <Input
              id="bpa-currency"
              value={request.currency}
              onChange={(e) => patch({ currency: e.target.value.toUpperCase() })}
            />
          </FormField>
          <FormField
            id="bpa-fx"
            label="汇率"
            hint="1 成本币种 = X 售价币种"
            error={problem?.field === 'fxRate' ? problem.message : undefined}
          >
            <Input
              id="bpa-fx"
              type="number"
              step="0.0001"
              value={request.fxRate ?? ''}
              onChange={(e) =>
                patch({ fxRate: e.target.value === '' ? null : Number(e.target.value) })
              }
            />
          </FormField>
          <FormField
            id="bpa-fee"
            label="平台费率（手工假设）"
            hint="0.15 = 15%"
            error={problem?.field === 'platformFeeRate' ? problem.message : undefined}
          >
            <Input
              id="bpa-fee"
              type="number"
              step="0.01"
              value={request.platformFeeRate ?? ''}
              onChange={(e) =>
                patch({ platformFeeRate: e.target.value === '' ? null : Number(e.target.value) })
              }
            />
          </FormField>
          <FormField
            id="bpa-logistics"
            label="物流每单位运费（手工假设）"
            hint="每单位尾程运费"
            error={problem?.field === 'logisticsCostPerUnit' ? problem.message : undefined}
          >
            <Input
              id="bpa-logistics"
              type="number"
              step="0.01"
              value={request.logisticsCostPerUnit ?? ''}
              onChange={(e) =>
                patch({ logisticsCostPerUnit: e.target.value === '' ? null : Number(e.target.value) })
              }
            />
          </FormField>
        </div>

        <div className="mt-3">
          <p className="text-copy-12 text-muted-foreground mb-1">
            目标店铺（可多选）—— 预览里的「涉及多少店铺」就是这个数
          </p>
          <div className="flex flex-wrap gap-2">
            {shopOptions.length ? (
              shopOptions.map((code) => {
                const [platformCode = '', siteCode = ''] = code.split('/');
                const picked = hasShop(request.shops, platformCode, siteCode);
                return (
                  <Button
                    key={code}
                    type="button"
                    variant={picked ? 'brand' : 'outline'}
                    onClick={() =>
                      patch({ shops: toggleShop(request.shops, platformCode, siteCode) })
                    }
                  >
                    {code}
                  </Button>
                );
              })
            ) : (
              <span className="text-copy-12 text-muted-foreground">
                店铺目录读不到（2.1.1.1 平台目录不可达时后端返回 503）。
              </span>
            )}
          </div>
          {problem?.field === 'shops' ? (
            <p className="text-copy-12 text-danger mt-1">{problem.message}</p>
          ) : null}
        </div>

        <div className="mt-3 flex flex-wrap items-center gap-2">
          <Button type="button" onClick={() => void runPreview()} disabled={busy}>
            {busy ? '处理中……' : '预览影响范围'}
          </Button>
          <Button
            type="button"
            variant="secondary"
            onClick={() => void runExecute()}
            disabled={busy || !preview}
          >
            确认执行
          </Button>
          <span className="text-copy-12 text-muted-foreground">
            {preview
              ? '已看过预览，可以确认执行。'
              : '必须先预览 —— 预览不落库，执行时会重算并比对预览令牌。'}
          </span>
        </div>
        <p className="text-copy-12 text-muted-foreground mt-1">
          当前目标集合：{scopeSummary(request)}
        </p>
      </SectionCard>

      {/* ============================================================ 预览报告 */}
      {preview ? (
        <>
          <div className="grid gap-3 md:grid-cols-4">
            {stats.map((stat) => (
              <StatCard
                key={stat.label}
                label={stat.label}
                value={stat.value}
                comparisonLabel={stat.hint}
              />
            ))}
          </div>

          <SectionCard
            title="影响预览报告"
            description="这是执行前唯一能看到的东西。确认无误再点「确认执行」—— 目标集合或当前价在预览之后变了的话，执行会被拒绝（409）。"
          >
            {preview.items.length ? (
              <div className="space-y-2">
                {preview.items.map((item) => (
                  <PreviewRow key={`${item.skuId}-${item.platformCode}-${item.siteCode}`} item={item} />
                ))}
              </div>
            ) : (
              <EmptyState
                title="目标集合里没有 listing"
                description="换一个店铺或 SKU 范围再预览。"
              />
            )}
          </SectionCard>
        </>
      ) : null}

      {/* ============================================================ 调价单台账 */}
      <SectionCard title="批量调价单" description="按时间倒序。点一行看它的明细。">
        {orders && orders.items.length ? (
          <div className="space-y-2">
            {orders.items.map((order) => (
              <div
                key={order.id}
                className="border-border flex flex-wrap items-center gap-2 rounded-md border p-3"
              >
                <button
                  type="button"
                  className="text-label-13 font-semibold underline-offset-2 hover:underline"
                  onClick={() => {
                    setSelected(order);
                    setFilters(emptyFilters());
                    setPage(0);
                  }}
                >
                  {adjustModeLabel(order.adjustMode)}
                  {order.adjustMode === 'FIXED_AMOUNT'
                    ? ` ${deltaText(order.adjustAmount)}`
                    : order.adjustMode === 'PERCENT'
                      ? ` ${percentText(order.adjustRate, '—')}`
                      : ` ${order.templateCode ?? ''}`}
                </button>
                <span className="text-copy-12 text-muted-foreground">
                  {order.scopeShops} · 分类 {order.scopeCategory || '不限'} · 品牌{' '}
                  {order.scopeBrand || '不限'} · {order.currency}
                </span>
                <span className="text-copy-12 text-muted-foreground">
                  listing {order.listingCount} · 改价 {order.willAdjustCount} · 跳过{' '}
                  {order.skippedCount}
                </span>
                <Badge tone={order.lossAfterSkuCount > 0 ? 'danger' : 'success'}>
                  改价后亏损商品 {order.lossAfterSkuCount}
                </Badge>
                <Badge tone={order.profitDeltaTotal === null ? 'warning' : 'info'}>
                  利润变化{' '}
                  {order.profitDeltaTotal === null ? '不可信' : deltaText(order.profitDeltaTotal)}
                </Badge>
                <span className="text-copy-12 text-muted-foreground">{order.createdAt}</span>
              </div>
            ))}
          </div>
        ) : (
          <EmptyState
            title="还没有批量调价单"
            description="填好上面的目标集合与调价参数，先预览，再确认执行。"
          />
        )}
      </SectionCard>

      {/* ============================================================ 已执行调价单的明细 */}
      {selected ? (
        <SectionCard
          title="调价单明细"
          description="筛选与排序都在数据库层。跳过的行也在里面，并写明为什么调不了。"
        >
          <div className="grid gap-3 md:grid-cols-4">
            <FormField id="bpa-filter-status" label="状态">
              <select
                id="bpa-filter-status"
                className="border-border bg-background text-copy-13 h-9 w-full rounded-md border px-3"
                value={filters.status}
                onChange={(e) =>
                  patchFilters({ status: e.target.value as AdjustmentItemFilters['status'] })
                }
              >
                <option value="">全部</option>
                <option value="WILL_ADJUST">将调价</option>
                <option value="SKIPPED">已跳过</option>
              </select>
            </FormField>
            <FormField id="bpa-filter-loss" label="亏损">
              <select
                id="bpa-filter-loss"
                className="border-border bg-background text-copy-13 h-9 w-full rounded-md border px-3"
                value={filters.loss}
                onChange={(e) =>
                  patchFilters({ loss: e.target.value as AdjustmentItemFilters['loss'] })
                }
              >
                <option value="">全部</option>
                <option value="LOSS_AFTER">改价后亏损</option>
                <option value="LOSS_BEFORE">改价前亏损</option>
              </select>
            </FormField>
            <FormField id="bpa-filter-keyword" label="关键字" hint="SKU 编码 / 名称">
              <Input
                id="bpa-filter-keyword"
                value={filters.keyword}
                onChange={(e) => patchFilters({ keyword: e.target.value })}
              />
            </FormField>
            <FormField id="bpa-filter-sort" label="排序">
              <select
                id="bpa-filter-sort"
                className="border-border bg-background text-copy-13 h-9 w-full rounded-md border px-3"
                value={filters.sort}
                onChange={(e) =>
                  patchFilters({ sort: e.target.value as AdjustmentItemFilters['sort'] })
                }
              >
                <option value="DELTA_ASC">价差 从低到高</option>
                <option value="DELTA_DESC">价差 从高到低</option>
                <option value="OLD_PRICE_ASC">旧价 从低到高</option>
                <option value="OLD_PRICE_DESC">旧价 从高到低</option>
                <option value="MARGIN_ASC">改价后毛利率 从低到高</option>
                <option value="MARGIN_DESC">改价后毛利率 从高到低</option>
              </select>
            </FormField>
          </div>

          {items && items.items.length ? (
            <div className="space-y-2">
              {items.items.map((item) => (
                <div
                  key={item.id ?? `${item.skuId}-${item.platformCode}-${item.siteCode}`}
                  className="border-border flex flex-wrap items-center gap-2 rounded-md border p-3"
                >
                  <Badge tone={itemTone(item)}>{itemStatusLabel(item.status)}</Badge>
                  <span className="text-label-13 font-semibold">{item.skuCode}</span>
                  <span className="text-copy-12 text-muted-foreground">
                    {item.platformCode}/{item.siteCode} · {item.currency}
                  </span>
                  <span className="text-copy-12">
                    {priceText(item.oldPrice)} → {priceText(item.newPrice)}（
                    {deltaText(item.priceDelta)}）
                  </span>
                  <span className="text-copy-12 text-muted-foreground">
                    毛利 {moneyText(item.grossProfitBefore)} → {moneyText(item.grossProfitAfter)} ·
                    毛利率 {percentText(item.grossMarginBefore)} →{' '}
                    {percentText(item.grossMarginAfter)}
                  </span>
                  <Badge tone={item.lossAfter === true ? 'danger' : 'info'}>
                    改价后 {lossText(item.lossAfter)}
                  </Badge>
                  {item.skipReason ? (
                    <span className="text-copy-12 text-muted-foreground">{item.skipReason}</span>
                  ) : null}
                </div>
              ))}
              <div className="text-copy-12 text-muted-foreground flex items-center gap-2">
                <Button
                  type="button"
                  variant="secondary"
                  disabled={page === 0}
                  onClick={() => setPage((p) => Math.max(p - 1, 0))}
                >
                  上一页
                </Button>
                <span>
                  第 {page + 1} 页 · 共 {items.total} 行
                </span>
                <Button
                  type="button"
                  variant="secondary"
                  disabled={(page + 1) * 20 >= items.total}
                  onClick={() => setPage((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          ) : (
            <EmptyState title="没有命中任何明细" description="换一个筛选条件试试。" />
          )}
        </SectionCard>
      ) : null}
    </div>
  );
}

/** 预览报告里的一行。 */
function PreviewRow({ item }: { item: BatchPriceAdjustmentItem }) {
  return (
    <div className="border-border flex flex-wrap items-center gap-2 rounded-md border p-3">
      <Badge tone={itemTone(item)}>{itemStatusLabel(item.status)}</Badge>
      <span className="text-label-13 font-semibold">{item.skuCode}</span>
      <span className="text-copy-12 text-muted-foreground">{item.skuName}</span>
      <span className="text-copy-12 text-muted-foreground">
        {item.platformCode}/{item.siteCode} · {item.currency}
      </span>
      <span className="text-copy-12">
        {priceText(item.oldPrice)} → {priceText(item.newPrice)}（{deltaText(item.priceDelta)}）
      </span>
      <span className="text-copy-12 text-muted-foreground">
        毛利 {moneyText(item.grossProfitBefore)} → {moneyText(item.grossProfitAfter)} · 毛利率{' '}
        {percentText(item.grossMarginBefore)} → {percentText(item.grossMarginAfter)}
      </span>
      <Badge tone={item.lossAfter === true ? 'danger' : 'info'}>
        改价前 {lossText(item.lossBefore)} → 改价后 {lossText(item.lossAfter)}
      </Badge>
      <span className="text-copy-12 text-muted-foreground">
        当前价来源 {priceSourceLabel(item.priceSource)}
      </span>
      {item.skipReason ? (
        <span className="text-copy-12 text-muted-foreground">{item.skipReason}</span>
      ) : null}
    </div>
  );
}
