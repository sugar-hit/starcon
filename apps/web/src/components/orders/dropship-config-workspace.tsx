'use client';

import type { ReactNode } from 'react';
import { useCallback, useEffect, useState } from 'react';
import { PackageCheck, RefreshCw, TriangleAlert } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  DropshipCapabilities,
  DropshipConfigDetail,
  DropshipConfigPage,
  DropshipConfigRow,
  DropshipEvaluationPage,
  DropshipEvaluationRow,
  DropshipShopRefPage,
  DropshipSupplierRefPage,
  DropshipSupplierStockPage,
} from '../../../../../packages/api-contracts/src/index';
import {
  AVAILABILITY_CHECK_MODES,
  CONFIG_STATUSES,
  FULFILLMENT_PATHS,
  LISTING_POLICIES,
  OWNERSHIP_MODES,
  SCOPE_KINDS,
  SETTLEMENT_BASES,
  SETTLEMENT_TIMINGS,
  SHOP_SOURCES,
  STOCK_SOURCES,
  SUPPLIER_SOURCES,
  advanceDropshipConfigStatus,
  availabilityCheckModeLabel,
  canAdvance,
  configFormError,
  configStatusLabel,
  dimensionUnavailableReason,
  evaluateDropship,
  exemptFromOwnInventory,
  fetchDropshipCapabilities,
  fetchDropshipConfig,
  fetchDropshipConfigs,
  fetchDropshipEvents,
  fetchEvaluations,
  fetchShopRefs,
  fetchSupplierRefs,
  fetchSupplierStocks,
  fulfillmentPathLabel,
  isSupplierDirectButOurOwnership,
  listingPolicyLabel,
  marginShareText,
  marginText,
  moneyText,
  nextStatuses,
  ownershipConfirmationMissing,
  ownershipModeLabel,
  qtyText,
  saveDropshipConfig,
  saveShopRef,
  saveSupplierRef,
  saveSupplierStock,
  scopeKindLabel,
  sellReasonLabel,
  settlementBasisLabel,
  settlementTimingLabel,
  stockSourceLabel,
  supplierStockText,
} from './dropship-config-model';

const PAGE_SIZE = 20;

/**
 * 表单字段外壳。
 *
 * <p>刻意**不用** `@starcon/ui` 的 `FormField`：它要求每个字段都带唯一 `id`
 * 且 children 必须是 `Input`（`React.InputHTMLAttributes`），而本页大量字段是
 * `<select>`（结算方式、可售校验、刊登策略…），套上去要么类型不过、要么就得为
 * 二十几个下拉框编二十几个 id。这里用同样的 className 渲染 label + 子元素，
 * 视觉与 `FormField` 一致，但下拉框与输入框可以共用同一个外壳。
 */
function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="space-y-2">
      <span className="text-label-13 text-foreground block">{label}</span>
      {children}
    </div>
  );
}

const EMPTY_FORM = {
  configCode: '',
  configName: '',
  scopeKind: 'SHOP',
  shopCode: '',
  skuRef: '',
  fulfillmentPath: 'SUPPLIER_DIRECT',
  supplierCode: '',
  ownershipMode: 'PURE_AGENCY',
  ownershipConfirmedAt: '',
  ownershipConfirmedNote: '',
  ownershipReviewedWith: '',
  settlementTiming: 'ON_RECEIPT',
  settlementBasis: 'MARGIN_SHARE',
  marginShareRate: '0.5',
  currencyCode: 'USD',
  supplierCost: '',
  referenceSellPrice: '',
  availabilityCheckMode: 'SUPPLIER_STOCK',
  minSupplierStockBuffer: '0',
  listingPolicy: 'PAUSE_WHEN_UNSELLABLE',
};

/**
 * 9.6.1.1 代发模式配置工作区。
 *
 * <p>蓝图：「配置店铺/SKU 是否走代发（一件代发），以及代发供应商、结算方式
 * （下单/确认收货后结算、差价抽成）、可售校验规则；区分『自营发货』与『供应商直发』
 * 两种履约路径。纯代理代发不占用自有仓库存；其他直发模式的货权与结算规则须单独确认。」
 *
 * <p><b>区块顺序对应判定的依赖顺序</b>：
 *
 * <ol>
 *   <li><b>能力</b>：先说清「我方自有库存」这一维度<b>刻意不可用</b> ——
 *       蓝图明写「代发不查自有库存」，把它画成 0 或「—」会让人以为接错了。</li>
 *   <li><b>上游投影</b>（店铺 / 供应商报价 / 供应商可供货量）：可售校验的输入，
 *       没有它们就判不出「供应商能不能供货」。</li>
 *   <li><b>代发配置</b>：物流与货权**分两列**显示；不记我方库存只在两者同时成立时出现。</li>
 *   <li><b>可售标记</b>：决定是否可售、是否可刊登 —— 蓝图的第二个输出。</li>
 * </ol>
 *
 * <p>加载态 / 空态 / 错误态三态齐全：任何一块数据没取到都不会静默显示成 0 或空数组。
 */
export function DropshipConfigWorkspace() {
  const [capabilities, setCapabilities] = useState<DropshipCapabilities | null>(null);
  const [shops, setShops] = useState<DropshipShopRefPage | null>(null);
  const [suppliers, setSuppliers] = useState<DropshipSupplierRefPage | null>(null);
  const [stocks, setStocks] = useState<DropshipSupplierStockPage | null>(null);
  const [configs, setConfigs] = useState<DropshipConfigPage | null>(null);
  const [evaluations, setEvaluations] = useState<DropshipEvaluationPage | null>(null);
  const [detail, setDetail] = useState<DropshipConfigDetail | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [keyword, setKeyword] = useState('');
  const [appliedKeyword, setAppliedKeyword] = useState('');
  const [page, setPage] = useState(0);

  const [form, setForm] = useState({ ...EMPTY_FORM });
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState('');
  const [actionNote, setActionNote] = useState('');

  const [shopForm, setShopForm] = useState({
    shopCode: '',
    shopName: '',
    platformCode: 'AMAZON',
    sourceSystem: 'TENANT_MAINTAINED',
    syncedAt: new Date().toISOString(),
  });
  const [supplierForm, setSupplierForm] = useState({
    supplierCode: '',
    supplierName: '',
    currencyCode: 'USD',
    quoteSource: 'TENANT_MAINTAINED',
    syncedAt: new Date().toISOString(),
  });
  const [stockForm, setStockForm] = useState({
    supplierCode: '',
    skuRef: '',
    availableQty: '',
    stockSource: 'TENANT_MAINTAINED',
    observedAt: new Date().toISOString(),
  });
  const [evalForm, setEvalForm] = useState({ configId: '', skuRef: '', requestedQty: '1' });

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const kw = appliedKeyword;
      const [caps, shopPage, supplierPage, stockPage, configPage, evalPage] = await Promise.all([
        fetchDropshipCapabilities(),
        fetchShopRefs({ page: 0, size: PAGE_SIZE }),
        fetchSupplierRefs({ page: 0, size: PAGE_SIZE }),
        fetchSupplierStocks({ page: 0, size: PAGE_SIZE }),
        // exactOptionalPropertyTypes 下不能显式传 undefined：空关键字时干脆不放这个键。
        kw
          ? fetchDropshipConfigs({ keyword: kw, page, size: PAGE_SIZE })
          : fetchDropshipConfigs({ page, size: PAGE_SIZE }),
        fetchEvaluations({ page: 0, size: PAGE_SIZE }),
      ]);
      setCapabilities(caps);
      setShops(shopPage);
      setSuppliers(supplierPage);
      setStocks(stockPage);
      setConfigs(configPage);
      setEvaluations(evalPage);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [appliedKeyword, page]);

  useEffect(() => {
    void load();
  }, [load]);

  async function run(action: () => Promise<string>) {
    setBusy(true);
    setActionError('');
    setActionNote('');
    try {
      setActionNote(await action());
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '操作失败');
    } finally {
      setBusy(false);
    }
  }

  const formError = configFormError(form);

  const ownStockNote = capabilities
    ? dimensionUnavailableReason(capabilities.dimensions, 'OWN_STOCK')
    : null;

  const total = configs?.total ?? 0;
  const exemptCount = (configs?.items ?? []).filter(exemptFromOwnInventory).length;
  const unconfirmedCount = (configs?.items ?? []).filter(ownershipConfirmationMissing).length;
  const unsellableCount = (evaluations?.items ?? []).filter((e) => !e.sellable).length;

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="代发模式配置"
        description="配置店铺 / SKU 是否走代发、代发供应商、结算方式与可售校验规则。物流路径与货权分开判断：不经自有仓不代表不承担货权。"
      />

      {error && (
        <div className="border-destructive-soft bg-destructive-soft text-destructive flex items-center gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="h-4 w-4 shrink-0" />
          <span className="text-copy-13">{error}</span>
          <Button variant="outline" size="sm" onClick={() => void load()} className="ml-auto">
            重试
          </Button>
        </div>
      )}

      {ownStockNote && (
        <Notice tone="warning" title="我方自有库存不参与代发判定">
          {ownStockNote}
        </Notice>
      )}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="代发配置数" value={loading ? '—' : total} />
        <StatCard
          label="不记我方库存"
          value={loading ? '—' : exemptCount}
          comparisonLabel="仅「供应商直发 + 纯代理」"
        />
        <StatCard
          label="货权待确认"
          value={loading ? '—' : unconfirmedCount}
          comparisonLabel="非纯代理须单独确认"
        />
        <StatCard
          label="最近不可售"
          value={loading ? '—' : unsellableCount}
          icon={<PackageCheck className="h-4 w-4" />}
        />
      </div>

      {/* ------------------------------------------------ 上游投影 */}
      <SectionCard
        title="上游投影"
        description="2.3.1 店铺（channel）、3.6.2 供应商报价（catalog）、7.1.1.3 可用量（warehouse）分属三个独立数据库，按模块边界纪律不得跨库读取。本模块把它们登记为带来源标注的真实表；「未观测」是缺口，「0」是确定结论，两者分开显示。"
      >
        <div className="grid gap-4 lg:grid-cols-3">
          <div className="flex flex-col gap-3">
            <h4 className="text-copy-13 font-medium">店铺（2.3.1）</h4>
            {loading ? (
              <p className="text-copy-13 text-muted">加载中…</p>
            ) : shops && shops.items.length > 0 ? (
              <ul className="flex flex-col gap-1">
                {shops.items.map((s) => (
                  <li key={s.id} className="text-copy-13 flex items-center gap-2">
                    <span className="font-medium">{s.shopCode}</span>
                    <span className="text-muted">{s.shopName}</span>
                    <Badge tone="neutral">{s.platformCode}</Badge>
                    {!s.active && <Badge tone="warning">已停用</Badge>}
                    <span className="text-muted ml-auto">{stockSourceLabel(s.sourceSystem)}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <EmptyState title="还没有店铺投影" description="先登记一个店铺，代发配置才能按店铺生效。" />
            )}
            <div className="flex flex-wrap items-end gap-2">
              <Field label="店铺 code">
                <Input
                  className="w-40"
                  value={shopForm.shopCode}
                  onChange={(e) => setShopForm({ ...shopForm, shopCode: e.target.value })}
                />
              </Field>
              <Field label="店铺名">
                <Input
                  className="w-40"
                  value={shopForm.shopName}
                  onChange={(e) => setShopForm({ ...shopForm, shopName: e.target.value })}
                />
              </Field>
              <Field label="来源">
                <select
                  className="border-border bg-background rounded-md border px-3 py-2 text-copy-13"
                  value={shopForm.sourceSystem}
                  onChange={(e) => setShopForm({ ...shopForm, sourceSystem: e.target.value })}
                >
                  {SHOP_SOURCES.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </Field>
              <Button
                size="sm"
                disabled={busy || !shopForm.shopCode || !shopForm.shopName}
                onClick={() =>
                  void run(async () => {
                    const saved = await saveShopRef({
                      shopCode: shopForm.shopCode,
                      shopName: shopForm.shopName,
                      platformCode: shopForm.platformCode,
                      regionCode: '',
                      active: true,
                      sourceSystem: shopForm.sourceSystem,
                      syncedAt: shopForm.syncedAt,
                    });
                    return saved.created ? '已新建店铺投影' : '已更新店铺投影';
                  })
                }
              >
                登记
              </Button>
            </div>
          </div>

          <div className="flex flex-col gap-3">
            <h4 className="text-copy-13 font-medium">供应商报价（3.6.2）</h4>
            {loading ? (
              <p className="text-copy-13 text-muted">加载中…</p>
            ) : suppliers && suppliers.items.length > 0 ? (
              <ul className="flex flex-col gap-1">
                {suppliers.items.map((s) => (
                  <li key={s.id} className="text-copy-13 flex items-center gap-2">
                    <span className="font-medium">{s.supplierCode}</span>
                    <span className="text-muted">{s.supplierName}</span>
                    <Badge tone="neutral">{s.currencyCode}</Badge>
                    {!s.active && <Badge tone="warning">已停用</Badge>}
                    <span className="text-muted ml-auto">{s.quoteSource}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <EmptyState
                title="还没有供应商报价投影"
                description="先登记供应商，供应商直发的配置才有「谁直发」可填。"
              />
            )}
            <div className="flex flex-wrap items-end gap-2">
              <Field label="供应商 code">
                <Input
                  className="w-36"
                  value={supplierForm.supplierCode}
                  onChange={(e) => setSupplierForm({ ...supplierForm, supplierCode: e.target.value })}
                />
              </Field>
              <Field label="供应商名">
                <Input
                  className="w-36"
                  value={supplierForm.supplierName}
                  onChange={(e) =>
                    setSupplierForm({ ...supplierForm, supplierName: e.target.value })
                  }
                />
              </Field>
              <Button
                size="sm"
                disabled={busy || !supplierForm.supplierCode || !supplierForm.supplierName}
                onClick={() =>
                  void run(async () => {
                    const saved = await saveSupplierRef({
                      supplierCode: supplierForm.supplierCode,
                      supplierName: supplierForm.supplierName,
                      currencyCode: supplierForm.currencyCode,
                      quoteValidFrom: null,
                      quoteValidTo: null,
                      quoteSource: supplierForm.quoteSource,
                      leadTimeDays: null,
                      active: true,
                      syncedAt: supplierForm.syncedAt,
                    });
                    return saved.created ? '已新建供应商投影' : '已更新供应商投影';
                  })
                }
              >
                登记
              </Button>
            </div>
          </div>

          <div className="flex flex-col gap-3">
            <h4 className="text-copy-13 font-medium">供应商可供货量</h4>
            {loading ? (
              <p className="text-copy-13 text-muted">加载中…</p>
            ) : stocks && stocks.items.length > 0 ? (
              <ul className="flex flex-col gap-1">
                {stocks.items.map((s) => (
                  <li key={s.id} className="text-copy-13 flex items-center gap-2">
                    <span className="font-medium">{s.supplierCode}</span>
                    <span className="text-muted">{s.skuRef}</span>
                    <Badge tone="neutral">{qtyText(s.availableQty)}</Badge>
                    <span className="text-muted ml-auto">{stockSourceLabel(s.stockSource)}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <EmptyState
                title="还没有供应商可供货量"
                description="按供应商库存校验的配置，没有观测就判不出可售 —— 那是缺口，不是 0。"
              />
            )}
            <div className="flex flex-wrap items-end gap-2">
              <Field label="供应商">
                <Input
                  className="w-32"
                  value={stockForm.supplierCode}
                  onChange={(e) => setStockForm({ ...stockForm, supplierCode: e.target.value })}
                />
              </Field>
              <Field label="SKU">
                <Input
                  className="w-32"
                  value={stockForm.skuRef}
                  onChange={(e) => setStockForm({ ...stockForm, skuRef: e.target.value })}
                />
              </Field>
              <Field label="可供货量">
                <Input
                  className="w-28"
                  value={stockForm.availableQty}
                  onChange={(e) => setStockForm({ ...stockForm, availableQty: e.target.value })}
                />
              </Field>
              <Button
                size="sm"
                disabled={busy || !stockForm.supplierCode || !stockForm.skuRef || !stockForm.availableQty}
                onClick={() =>
                  void run(async () => {
                    await saveSupplierStock({
                      supplierCode: stockForm.supplierCode,
                      skuRef: stockForm.skuRef,
                      availableQty: Number(stockForm.availableQty),
                      stockSource: stockForm.stockSource,
                      observedAt: stockForm.observedAt,
                    });
                    return '已登记供应商可供货量';
                  })
                }
              >
                登记
              </Button>
            </div>
          </div>
        </div>
      </SectionCard>

      {/* ------------------------------------------------ 配置录入 */}
      <SectionCard
        title="代发配置"
        description="物流路径与货权是两个独立字段：「谁发货」与「结算前货算谁的」互不推导。不记我方库存只在「供应商直发 + 纯代理」同时成立时生效；「供应商直发 + 我方货权」（VMI 型）仍然占用我方库存，且必须单独确认过货权与结算。"
      >
        <div className="grid gap-3 md:grid-cols-3 lg:grid-cols-4">
          <Field label="配置 code">
            <Input value={form.configCode} onChange={(e) => setForm({ ...form, configCode: e.target.value })} />
          </Field>
          <Field label="配置名">
            <Input value={form.configName} onChange={(e) => setForm({ ...form, configName: e.target.value })} />
          </Field>
          <Field label="适用范围">
            <select
              className="border-border bg-background w-full rounded-md border px-3 py-2 text-copy-13"
              value={form.scopeKind}
              onChange={(e) => setForm({ ...form, scopeKind: e.target.value })}
            >
              {SCOPE_KINDS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </Field>
          {form.scopeKind === 'SHOP' ? (
            <Field label="店铺 code">
              <Input value={form.shopCode} onChange={(e) => setForm({ ...form, shopCode: e.target.value })} />
            </Field>
          ) : (
            <Field label="SKU ref">
              <Input value={form.skuRef} onChange={(e) => setForm({ ...form, skuRef: e.target.value })} />
            </Field>
          )}

          <Field label="履约路径（物流）">
            <select
              className="border-border bg-background w-full rounded-md border px-3 py-2 text-copy-13"
              value={form.fulfillmentPath}
              onChange={(e) => setForm({ ...form, fulfillmentPath: e.target.value })}
            >
              {FULFILLMENT_PATHS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="代发供应商">
            <Input
              value={form.supplierCode}
              onChange={(e) => setForm({ ...form, supplierCode: e.target.value })}
            />
          </Field>
          <Field label="货权模式">
            <select
              className="border-border bg-background w-full rounded-md border px-3 py-2 text-copy-13"
              value={form.ownershipMode}
              onChange={(e) => setForm({ ...form, ownershipMode: e.target.value })}
            >
              {OWNERSHIP_MODES.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="结算周期">
            <select
              className="border-border bg-background w-full rounded-md border px-3 py-2 text-copy-13"
              value={form.settlementTiming}
              onChange={(e) => setForm({ ...form, settlementTiming: e.target.value })}
            >
              {SETTLEMENT_TIMINGS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </Field>

          <Field label="结算方式">
            <select
              className="border-border bg-background w-full rounded-md border px-3 py-2 text-copy-13"
              value={form.settlementBasis}
              onChange={(e) => setForm({ ...form, settlementBasis: e.target.value })}
            >
              {SETTLEMENT_BASES.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </Field>
          {form.settlementBasis === 'MARGIN_SHARE' && (
            <Field label="我方留存差价比例（0-1）">
              <Input
                value={form.marginShareRate}
                onChange={(e) => setForm({ ...form, marginShareRate: e.target.value })}
              />
            </Field>
          )}
          <Field label="币种">
            <Input value={form.currencyCode} onChange={(e) => setForm({ ...form, currencyCode: e.target.value })} />
          </Field>
          <Field label="供货价">
            <Input value={form.supplierCost} onChange={(e) => setForm({ ...form, supplierCost: e.target.value })} />
          </Field>
          <Field label="参考售价">
            <Input
              value={form.referenceSellPrice}
              onChange={(e) => setForm({ ...form, referenceSellPrice: e.target.value })}
            />
          </Field>

          <Field label="可售校验">
            <select
              className="border-border bg-background w-full rounded-md border px-3 py-2 text-copy-13"
              value={form.availabilityCheckMode}
              onChange={(e) => setForm({ ...form, availabilityCheckMode: e.target.value })}
            >
              {AVAILABILITY_CHECK_MODES.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </Field>
          {form.availabilityCheckMode === 'SUPPLIER_STOCK' && (
            <Field label="安全余量">
              <Input
                value={form.minSupplierStockBuffer}
                onChange={(e) => setForm({ ...form, minSupplierStockBuffer: e.target.value })}
              />
            </Field>
          )}
          <Field label="刊登策略">
            <select
              className="border-border bg-background w-full rounded-md border px-3 py-2 text-copy-13"
              value={form.listingPolicy}
              onChange={(e) => setForm({ ...form, listingPolicy: e.target.value })}
            >
              {LISTING_POLICIES.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </Field>

          {form.ownershipMode === 'OUR_ACCOUNT' && (
            <>
              <Field label="货权确认时间">
                <Input
                  type="datetime-local"
                  value={form.ownershipConfirmedAt}
                  onChange={(e) => setForm({ ...form, ownershipConfirmedAt: e.target.value })}
                />
              </Field>
              <Field label="与谁联合评审（7.7）">
                <Input
                  value={form.ownershipReviewedWith}
                  onChange={(e) => setForm({ ...form, ownershipReviewedWith: e.target.value })}
                />
              </Field>
              <Field label="确认依据">
                <Input
                  value={form.ownershipConfirmedNote}
                  onChange={(e) => setForm({ ...form, ownershipConfirmedNote: e.target.value })}
                />
              </Field>
            </>
          )}
        </div>

        {formError && (
          <p className="text-copy-13 text-destructive mt-2">{formError}</p>
        )}

        <div className="mt-3 flex items-center gap-3">
          <Button
            disabled={
              busy ||
              formError !== null ||
              !form.configCode ||
              !form.configName ||
              !form.supplierCost ||
              !form.referenceSellPrice
            }
            onClick={() =>
              void run(async () => {
                const saved = await saveDropshipConfig({
                  configCode: form.configCode,
                  configName: form.configName,
                  scopeKind: form.scopeKind,
                  shopCode: form.scopeKind === 'SHOP' ? form.shopCode : '',
                  skuRef: form.scopeKind === 'SKU' ? form.skuRef : '',
                  fulfillmentPath: form.fulfillmentPath,
                  supplierCode: form.supplierCode,
                  ownershipMode: form.ownershipMode,
                  ownershipConfirmedAt:
                    form.ownershipMode === 'OUR_ACCOUNT' && form.ownershipConfirmedAt
                      ? new Date(form.ownershipConfirmedAt).toISOString()
                      : null,
                  ownershipConfirmedByMembershipId: null,
                  ownershipConfirmedNote:
                    form.ownershipMode === 'OUR_ACCOUNT' ? form.ownershipConfirmedNote : null,
                  ownershipReviewedWith: form.ownershipReviewedWith,
                  settlementTiming: form.settlementTiming,
                  settlementBasis: form.settlementBasis,
                  marginShareRate:
                    form.settlementBasis === 'MARGIN_SHARE' && form.marginShareRate
                      ? Number(form.marginShareRate)
                      : null,
                  currencyCode: form.currencyCode,
                  supplierCost: Number(form.supplierCost),
                  referenceSellPrice: Number(form.referenceSellPrice),
                  availabilityCheckMode: form.availabilityCheckMode,
                  minSupplierStockBuffer:
                    form.availabilityCheckMode === 'SUPPLIER_STOCK' && form.minSupplierStockBuffer
                      ? Number(form.minSupplierStockBuffer)
                      : null,
                  listingPolicy: form.listingPolicy,
                  priority: 100,
                  effectiveFrom: null,
                  effectiveTo: null,
                  note: '',
                });
                return saved.created
                  ? `已创建 ${saved.configCode}（草稿）`
                  : `已更新 ${saved.configCode}`;
              })
            }
          >
            保存配置
          </Button>
          {actionNote && <span className="text-copy-13 text-muted">{actionNote}</span>}
          {actionError && <span className="text-copy-13 text-destructive">{actionError}</span>}
        </div>
      </SectionCard>

      {/* ------------------------------------------------ 配置列表 */}
      <SectionCard
        title="配置列表"
        description="「不记我方库存」只在「供应商直发 + 纯代理」同时成立时才显示；「供应商直发 + 我方货权」用警告色标出 —— 不经我方仓不代表不承担货权。"
      >
        <div className="mb-3 flex items-end gap-2">
          <Input
            className="w-64"
            placeholder="配置 code / 名称 / 供应商"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                setAppliedKeyword(keyword);
                setPage(0);
              }
            }}
          />
          <Button
            size="sm"
            onClick={() => {
              setAppliedKeyword(keyword);
              setPage(0);
            }}
          >
            查询
          </Button>
          <Button size="sm" variant="outline" onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-1 h-4 w-4" />
            刷新
          </Button>
        </div>

        {loading ? (
          <p className="text-copy-13 text-muted">加载中…</p>
        ) : configs && configs.items.length > 0 ? (
          <>
            <div className="flex flex-col gap-2">
              {configs.items.map((c: DropshipConfigRow) => (
                <div
                  key={c.id}
                  className="border-border flex flex-wrap items-center gap-2 rounded-lg border px-3 py-2"
                >
                  <span className="text-copy-13 font-medium">{c.configCode}</span>
                  <span className="text-copy-13 text-muted">{c.configName}</span>
                  <Badge tone="neutral">
                    {scopeKindLabel(c.scopeKind)} · {c.scopeKind === 'SHOP' ? c.shopCode : c.skuRef}
                  </Badge>
                  {/* 物流与货权分两列，不合成一个「是否代发」的勾选框 */}
                  <Badge tone="neutral">物流：{fulfillmentPathLabel(c.fulfillmentPath)}</Badge>
                  <Badge tone={isSupplierDirectButOurOwnership(c) ? 'warning' : 'neutral'}>
                    货权：{ownershipModeLabel(c.ownershipMode)}
                  </Badge>
                  {exemptFromOwnInventory(c) ? (
                    <Badge tone="success">不记我方库存</Badge>
                  ) : (
                    <Badge tone="warning">占用我方库存口径</Badge>
                  )}
                  {ownershipConfirmationMissing(c) && <Badge tone="warning">货权待确认</Badge>}
                  <Badge tone="neutral">
                    {settlementTimingLabel(c.settlementTiming)} ·{' '}
                    {settlementBasisLabel(c.settlementBasis)}
                  </Badge>
                  <span className="text-copy-13 text-muted">
                    差价 {moneyText(c.grossSpread)} / 毛利 {marginText(c.expectedMarginAmount)} / 应付{' '}
                    {moneyText(c.expectedSupplierAmount)}
                    {c.settlementBasis === 'MARGIN_SHARE'
                      ? `（抽成 ${marginShareText(c.marginShareRate)}）`
                      : ''}
                  </span>
                  <Badge tone="neutral">{configStatusLabel(c.status)}</Badge>
                  <span className="ml-auto flex items-center gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() =>
                        void run(async () => {
                          setDetail(await fetchDropshipConfig(c.id));
                          return `已载入 ${c.configCode} 的明细与事件`;
                        })
                      }
                    >
                      详情
                    </Button>
                    {canAdvance(c.status) &&
                      nextStatuses(c.status).map((next) => (
                        <Button
                          key={next}
                          size="sm"
                          variant="ghost"
                          disabled={busy}
                          onClick={() =>
                            void run(async () => {
                              await advanceDropshipConfigStatus(c.id, { status: next, note: '' });
                              return `${c.configCode} → ${configStatusLabel(next)}`;
                            })
                          }
                        >
                          {configStatusLabel(next)}
                        </Button>
                      ))}
                  </span>
                </div>
              ))}
            </div>
            <div className="mt-3 flex items-center gap-2">
              <Button size="sm" variant="outline" disabled={page === 0} onClick={() => setPage(page - 1)}>
                上一页
              </Button>
              <span className="text-copy-13 text-muted">
                第 {page + 1} 页 / 共 {configs.total} 条
              </span>
              <Button
                size="sm"
                variant="outline"
                disabled={(page + 1) * PAGE_SIZE >= configs.total}
                onClick={() => setPage(page + 1)}
              >
                下一页
              </Button>
            </div>
          </>
        ) : (
          <EmptyState
            title={appliedKeyword ? '没有匹配的配置' : '还没有代发配置'}
            description={
              appliedKeyword
                ? '换个关键字再试，或清空关键字看全部。'
                : '先在上面创建一条代发配置：它决定这个店铺 / SKU 走不走我方仓、货权算谁的。'
            }
          />
        )}
      </SectionCard>

      {/* ------------------------------------------------ 详情 */}
      {detail && (
        <SectionCard
          title={`配置详情 · ${detail.config.configCode}`}
          description={detail.reservationNote}
        >
          <div className="flex flex-col gap-3">
            <h4 className="text-copy-13 font-medium">SKU 明细行</h4>
            {detail.lines.length > 0 ? (
              <ul className="flex flex-col gap-1">
                {detail.lines.map((l) => (
                  <li key={l.id} className="text-copy-13 flex items-center gap-2">
                    <span className="font-medium">{l.skuRef}</span>
                    {!l.active && <Badge tone="warning">已停用</Badge>}
                    <span className="text-muted">
                      供货价覆盖 {moneyText(l.supplierCostOverride)} / 售价覆盖{' '}
                      {moneyText(l.referenceSellPriceOverride)}
                    </span>
                  </li>
                ))}
              </ul>
            ) : (
              <EmptyState
                title="还没有 SKU 明细行"
                description="空配置不能激活：激活一个没有 SKU 的代发配置，下游只会拿到一个永远不可售的配置。"
              />
            )}

            <h4 className="text-copy-13 font-medium">事件流</h4>
            {detail.events.length > 0 ? (
              <ul className="flex flex-col gap-1">
                {detail.events.map((e) => (
                  <li key={e.id} className="text-copy-13 flex items-center gap-2">
                    <Badge tone="neutral">{e.eventKind}</Badge>
                    <span className="text-muted">{e.detail}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <EmptyState title="还没有事件" description="创建与状态流转都会在这里留痕。" />
            )}

            <Button
              size="sm"
              variant="outline"
              onClick={() =>
                void run(async () => {
                  await fetchDropshipEvents(detail.config.id, 0, PAGE_SIZE);
                  return '事件流已是最新';
                })
              }
            >
              刷新事件
            </Button>
          </div>
        </SectionCard>
      )}

      {/* ------------------------------------------------ 可售标记 */}
      <SectionCard
        title="代发可售标记"
        description="蓝图的两个输出之一：决定是否可售、是否可刊登。可售与可刊登**分开** —— ALWAYS_LIST 时不可售但仍可刊登是合法状态。供应商可供货量显示「未观测」时那是缺口，不是 0。"
      >
        <div className="flex flex-wrap items-end gap-2">
          <Field label="配置">
            <select
              className="border-border bg-background rounded-md border px-3 py-2 text-copy-13"
              value={evalForm.configId}
              onChange={(e) => setEvalForm({ ...evalForm, configId: e.target.value })}
            >
              <option value="">选择配置</option>
              {(configs?.items ?? []).map((c) => (
                <option key={c.id} value={c.id}>
                  {c.configCode}
                </option>
              ))}
            </select>
          </Field>
          <Field label="SKU">
            <Input
              className="w-40"
              value={evalForm.skuRef}
              onChange={(e) => setEvalForm({ ...evalForm, skuRef: e.target.value })}
            />
          </Field>
          <Field label="请求数量">
            <Input
              className="w-28"
              value={evalForm.requestedQty}
              onChange={(e) => setEvalForm({ ...evalForm, requestedQty: e.target.value })}
            />
          </Field>
          <Button
            size="sm"
            disabled={busy || !evalForm.configId || !evalForm.skuRef}
            onClick={() =>
              void run(async () => {
                const result = await evaluateDropship({
                  configId: evalForm.configId,
                  skuRef: evalForm.skuRef,
                  requestedQty: Number(evalForm.requestedQty),
                });
                return `判定结果：${sellReasonLabel(result.reasonCode)}`
                  + `（可售 ${result.sellable ? '是' : '否'} / 可刊登 ${result.listable ? '是' : '否'}）`;
              })
            }
          >
            评估
          </Button>
        </div>

        {loading ? (
          <p className="text-copy-13 text-muted">加载中…</p>
        ) : evaluations && evaluations.items.length > 0 ? (
          <div className="mt-3 flex flex-col gap-2">
            {evaluations.items.map((e: DropshipEvaluationRow) => (
              <div
                key={e.id}
                className="border-border flex flex-wrap items-center gap-2 rounded-lg border px-3 py-2"
              >
                <span className="text-copy-13 font-medium">{e.skuRef}</span>
                <Badge tone={e.sellable ? 'success' : 'danger'}>
                  {e.sellable ? '可售' : '不可售'}
                </Badge>
                <Badge tone={e.listable ? 'success' : 'warning'}>
                  {e.listable ? '可刊登' : '已下架'}
                </Badge>
                <Badge tone="neutral">{sellReasonLabel(e.reasonCode)}</Badge>
                <span className="text-copy-13 text-muted">
                  请求 {qtyText(e.requestedQty)} / 需求 {qtyText(e.requiredQty)} / 供应商可供{' '}
                  {supplierStockText(e)}
                </span>
                <Badge tone="neutral">
                  {e.countsAgainstOwnInventory ? '占用我方库存口径' : '不记我方库存'}
                </Badge>
                {e.reasonDetail && <span className="text-copy-13">{e.reasonDetail}</span>}
              </div>
            ))}
          </div>
        ) : (
          <EmptyState
            title="还没有可售标记"
            description="选一条已激活的配置做一次评估，判定会作为不可改写的凭据留在这里。"
          />
        )}
      </SectionCard>

      {capabilities && (
        <SectionCard title="能力与上游可用性" description={capabilities.ownershipNote}>
          <ul className="flex flex-col gap-2">
            {capabilities.dimensions.map((d) => (
              <li key={d.code} className="text-copy-13 flex flex-wrap items-center gap-2">
                <Badge tone={d.available ? 'success' : 'warning'}>
                  {d.available ? '可用' : '不可用'}
                </Badge>
                <span className="font-medium">{d.label}</span>
                <span className="text-muted">{d.source}</span>
                {d.unavailableReason && <span className="text-muted">{d.unavailableReason}</span>}
              </li>
            ))}
          </ul>
          <p className="text-copy-13 text-muted mt-3">{capabilities.upstreamNote}</p>
          <p className="text-copy-13 text-muted mt-1">{capabilities.note}</p>
        </SectionCard>
      )}

      <p className="text-copy-13 text-muted">
        供应商来源：{SUPPLIER_SOURCES.map((s) => s.label).join(' / ')}；库存来源：
        {STOCK_SOURCES.map((s) => s.label).join(' / ')}；可售校验：
        {AVAILABILITY_CHECK_MODES.map((s) => s.label).join(' / ')}。状态：
        {CONFIG_STATUSES.map((s) => s.label).join(' → ')}。
      </p>
    </div>
  );
}
