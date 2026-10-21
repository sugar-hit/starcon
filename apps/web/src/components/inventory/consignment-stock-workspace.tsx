'use client';

import { useCallback, useEffect, useState } from 'react';
import { Boxes, RefreshCw, ShieldAlert, Warehouse } from 'lucide-react';
import {
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  PlatformConsignmentAuditEntry,
  PlatformConsignmentCapabilities,
  PlatformConsignmentLedger,
  PlatformConsignmentOrderDetail,
  PlatformConsignmentOrderPage,
  PlatformConsignmentReceiptPage,
  PlatformConsignmentReconciliation,
  PlatformConsignmentSettlementBasisView,
  PlatformConsignmentSettlementReceiptPage,
} from '../../../../../packages/api-contracts/src/index';
import { useIdentity } from '../iam/identity-shell';
import {
  addConsignmentOrderLine,
  auditLabel,
  cancelConsignmentOrder,
  canAddLine,
  canCancel,
  canClose,
  canCloseWithSettlement,
  canConfirmReceipt,
  canRecordReceipt,
  canRecordSettlement,
  canShip,
  closeConsignmentOrder,
  confirmConsignmentReceipt,
  createConsignmentOrder,
  errorText,
  fetchConsignmentAudit,
  fetchConsignmentStockCapabilities,
  fetchConsignmentLedger,
  fetchConsignmentOrder,
  fetchConsignmentOrders,
  fetchConsignmentReceipts,
  fetchConsignmentReconciliation,
  fetchConsignmentSettlementBasis,
  fetchConsignmentSettlementReceipts,
  formatOwnInventory,
  formatQty,
  formatUnsettled,
  measureCards,
  operatingModeLabel,
  recordConsignmentReceipt,
  recordConsignmentSettlementReceipt,
  segregationStatement,
  segregationTone,
  settlementBasisHint,
  settlementBasisView,
  settlementStateTone,
  shipConsignmentOrder,
  statusTone,
} from './consignment-stock-model';

/**
 * 7.7.2.2 平台托管与全托管备货工作区。
 *
 * <p>蓝图：「处理平台托管（全托管）下的"备货"语义：发给平台的货进入平台仓但**所有权与结算规则随模式变化**，
 * 需与自有仓库存分账；支持备货单、平台仓收发对账、按件/按日结算口径标记。」
 *
 * <p>这是一个真实工作区：备货单、平台仓收发回执、平台结算回执、托管备货台账、
 * 与自有库存的对账结果全部落到 PostgreSQL 16，动作闭环齐全。
 *
 * <p>界面上反复说清三件事，否则使用者一定会误读：
 *
 * <ul>
 *   <li>**备货量 / 平台在库 / 平台已售 / 结算未结是四张卡，不是相加**。
 *       加起来会把平台在库算成我方库存 —— 那正是蓝图加粗句禁止的事。</li>
 *   <li>**自有库存那一侧并排给出，并标「不含托管量」**，不与平台口径相加。</li>
 *   <li>**未结量可能是「—（平台还没给回执）」而不是 0**；
 *       「备货量 − 平台入库」是**在途未确认**，不是差异损失。</li>
 * </ul>
 */
export function ConsignmentStockWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [capabilities, setCapabilities] = useState<PlatformConsignmentCapabilities | null>(null);
  const [orders, setOrders] = useState<PlatformConsignmentOrderPage | null>(null);
  const [detail, setDetail] = useState<PlatformConsignmentOrderDetail | null>(null);
  const [ledger, setLedger] = useState<PlatformConsignmentLedger | null>(null);
  const [reconciliation, setReconciliation] = useState<PlatformConsignmentReconciliation | null>(
    null,
  );
  const [basis, setBasis] = useState<PlatformConsignmentSettlementBasisView | null>(null);
  const [receipts, setReceipts] = useState<PlatformConsignmentReceiptPage | null>(null);
  const [settlements, setSettlements] = useState<PlatformConsignmentSettlementReceiptPage | null>(
    null,
  );
  const [audit, setAudit] = useState<PlatformConsignmentAuditEntry[]>([]);

  const [loading, setLoading] = useState(true);
  const [drillLoading, setDrillLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [status, setStatus] = useState('');
  const [basisFilter, setBasisFilter] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState('');

  // 建单表单
  const [shopId, setShopId] = useState('');
  const [warehouseCode, setWarehouseCode] = useState('');
  const [legalEntityId, setLegalEntityId] = useState('');
  const [currency, setCurrency] = useState('CNY');
  const [newBasis, setNewBasis] = useState('PER_ITEM');
  const [forecastId, setForecastId] = useState('');
  const [skuId, setSkuId] = useState('');
  const [qty, setQty] = useState('');

  // 平台回执登记
  const [receiptLineId, setReceiptLineId] = useState('');
  const [receiptType, setReceiptType] = useState('INBOUND');
  const [receiptRef, setReceiptRef] = useState('');
  const [receiptQty, setReceiptQty] = useState('');
  const [receiptAt, setReceiptAt] = useState('');

  // 结算回执登记
  const [settleLineId, setSettleLineId] = useState('');
  const [settlePeriodStart, setSettlePeriodStart] = useState('');
  const [settlePeriodEnd, setSettlePeriodEnd] = useState('');
  const [settleQty, setSettleQty] = useState('');
  const [settleRef, setSettleRef] = useState('');
  const [settleAmount, setSettleAmount] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, page1] = await Promise.all([
        fetchConsignmentStockCapabilities(),
        fetchConsignmentOrders({
          status,
          settlementBasis: basisFilter,
          keyword,
          page,
          size: 20,
        }),
      ]);
      setCapabilities(caps);
      setOrders(page1);
      const first = page1.orders[0];
      if (!selected && first) setSelected(first.id);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [status, basisFilter, keyword, page, selected]);

  const loadDrill = useCallback(async () => {
    if (!selected) return;
    setDrillLoading(true);
    try {
      const [d, lg, rc, bs, rp, st, au] = await Promise.all([
        fetchConsignmentOrder(selected),
        fetchConsignmentLedger(selected),
        fetchConsignmentReconciliation(selected),
        fetchConsignmentSettlementBasis(selected),
        fetchConsignmentReceipts(selected, undefined, 0, 50),
        fetchConsignmentSettlementReceipts(selected, 0, 50),
        fetchConsignmentAudit(selected),
      ]);
      setDetail(d);
      setLedger(lg);
      setReconciliation(rc);
      setBasis(bs);
      setReceipts(rp);
      setSettlements(st);
      setAudit(au);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setDrillLoading(false);
    }
  }, [selected]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void loadDrill();
  }, [loadDrill]);

  const run = (work: () => Promise<void>) => {
    setSaving(true);
    setNotice('');
    setError('');
    void work()
      .then(async () => {
        setSaving(false);
        await loadDrill();
        await load();
      })
      .catch((e) => {
        setSaving(false);
        setError(errorText(e));
      });
  };

  if (!readable) {
    return (
      <EmptyState
        title="没有平台托管备货的查看权限"
        description="需要 warehouse.inventory.read 才能查看托管备货台账、平台仓收发对账与结算口径标记。"
      />
    );
  }

  const order = detail?.order ?? null;
  const cards = measureCards(ledger);
  const basisView = settlementBasisView(basis);
  const orderStatus = order?.status ?? null;
  const unsettledKnown = basis?.unsettledKnown ?? false;
  const unsettled = basis?.unsettledQuantity ?? null;

  return (
    <div className="space-y-4">
      <PageHeader
        title="平台托管与全托管备货"
        description="发给平台的货进入平台仓，但平台仓在库是平台回执的观测值，不得并入自有库存余额。备货量 / 平台在库 / 平台已售 / 结算未结分开看，不相加。"
        actions={
          <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={loading ? 'mr-2 h-3.5 w-3.5 animate-spin' : 'mr-2 h-3.5 w-3.5'} />
            刷新
          </Button>
        }
      />

      {error && (
        <Notice tone="danger" title="这一步没能完成">
          {error}
        </Notice>
      )}
      {notice && (
        <Notice tone="success" title="已处理">
          {notice}
        </Notice>
      )}

      {/* ---------------- 口径与未接入环节：先说清规则，再谈数据 ---------------- */}
      {capabilities && (
        <SectionCard title="口径与未接入环节">
          <div className="space-y-2 text-sm">
            <p className="font-medium">{capabilities.separationNote}</p>
            <p className="text-muted-foreground">{capabilities.measureNote}</p>
            <p className="text-muted-foreground">{capabilities.settlementNote}</p>
            <p className="text-muted-foreground">{capabilities.operatingModeNote}</p>
            <p className="text-muted-foreground">{capabilities.forecastNote}</p>
            <p className="text-muted-foreground">{capabilities.segregationCheckNote}</p>
          </div>
          {capabilities.unavailable.length > 0 && (
            <div className="mt-3">
              <Notice tone="warning" title="以下环节尚未接入（不是本模块做坏了）">
                <ul className="list-disc space-y-1 pl-5">
                  {capabilities.unavailable.map((u) => (
                    <li key={`${u.capability}-${u.ownerModule}`}>
                      <span className="font-medium">{u.capability}</span>（{u.ownerModule} ·{' '}
                      {u.ownerService}）：{u.reason} 交接方式：{u.handoff}
                    </li>
                  ))}
                </ul>
              </Notice>
            </div>
          )}
        </SectionCard>
      )}

      {/* ---------------- 第一屏：四张口径卡 ---------------- */}
      <div className="grid gap-3 md:grid-cols-4">
        {cards.map((card) => (
          <StatCard
            key={card.key}
            label={card.label}
            value={card.value}
            unit={card.unit}
            comparisonLabel={card.hint}
            polarity="neutral"
            icon={
              card.key === 'stocked' ? (
                <Boxes className="h-4 w-4" />
              ) : card.key === 'onHand' ? (
                <Warehouse className="h-4 w-4" />
              ) : (
                <ShieldAlert className="h-4 w-4" />
              )
            }
          />
        ))}
      </div>

      {/* ---------------- 备货单列表 ---------------- */}
      <SectionCard
        title="托管备货单"
        description="真实分页；合计全部由明细与平台回执聚合得出。"
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <select
              aria-label="按状态筛选"
              className="border-input bg-background h-8 rounded-md border px-2 text-sm"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部状态</option>
              {capabilities?.orderStatuses.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
            <select
              aria-label="按结算口径筛选"
              className="border-input bg-background h-8 rounded-md border px-2 text-sm"
              value={basisFilter}
              onChange={(e) => {
                setBasisFilter(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部口径</option>
              {capabilities?.settlementBases.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
            <Input
              aria-label="关键字"
              placeholder="单号 / 店铺 / 平台仓"
              value={keyword}
              onChange={(e) => {
                setKeyword(e.target.value);
                setPage(0);
              }}
              className="h-8 w-44"
            />
          </div>
        }
      >
        {loading && <p className="text-muted-foreground text-sm">正在读取备货单…</p>}
        {!loading && (orders?.orders.length ?? 0) === 0 && (
          <EmptyState
            title="还没有平台托管备货单"
            description="全托管 / 半托管店铺可以在下方建一张备货单：发给平台的货会进入平台仓，但不会并入自有库存余额。"
          />
        )}
        {!loading && (orders?.orders.length ?? 0) > 0 && (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="text-muted-foreground border-b text-left">
                  <tr>
                    <th className="py-2 pr-3">单号</th>
                    <th className="py-2 pr-3">店铺 / 经营模式</th>
                    <th className="py-2 pr-3">平台仓</th>
                    <th className="py-2 pr-3">结算口径</th>
                    <th className="py-2 pr-3">状态</th>
                    <th className="py-2 pr-3 text-right">备货量</th>
                    <th className="py-2 pr-3 text-right">平台在库</th>
                    <th className="py-2 pr-3 text-right">平台已售</th>
                    <th className="py-2 pr-3 text-right">结算未结</th>
                  </tr>
                </thead>
                <tbody>
                  {orders?.orders.map((o) => (
                    <tr
                      key={o.id}
                      className={o.id === selected ? 'bg-muted/50' : undefined}
                      onClick={() => setSelected(o.id)}
                      style={{ cursor: 'pointer' }}
                    >
                      <td className="py-2 pr-3 font-mono text-xs">{o.orderCode}</td>
                      <td className="py-2 pr-3">
                        {o.shopName}
                        <span className="text-muted-foreground ml-1 text-xs">
                          {operatingModeLabel(o.operatingMode)}
                        </span>
                      </td>
                      <td className="py-2 pr-3">{o.platformWarehouseCode}</td>
                      <td className="py-2 pr-3">{o.settlementBasisLabel}</td>
                      <td className="py-2 pr-3">
                        <span
                          className={
                            statusTone(o.status) === 'ok'
                              ? 'text-success'
                              : statusTone(o.status) === 'bad'
                                ? 'text-destructive'
                                : 'text-muted-foreground'
                          }
                        >
                          {o.statusLabel}
                        </span>
                      </td>
                      <td className="py-2 pr-3 text-right">{formatQty(o.totalStockedQuantity)}</td>
                      <td className="py-2 pr-3 text-right">
                        {formatQty(o.totalPlatformOnHandQuantity)}
                      </td>
                      <td className="py-2 pr-3 text-right">
                        {formatQty(o.totalPlatformSoldQuantity)}
                      </td>
                      <td className="py-2 pr-3 text-right">
                        {formatQty(o.totalUnsettledQuantity)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="text-muted-foreground mt-3 flex items-center justify-between text-xs">
              <span>共 {orders?.total ?? 0} 张</span>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page <= 0}
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                >
                  上一页
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={(orders?.orders.length ?? 0) < 20}
                  onClick={() => setPage((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          </>
        )}
      </SectionCard>

      {/* ---------------- 建单 ---------------- */}
      {writable && (
        <SectionCard
          title="建立平台托管备货单"
          description="经营模式来自 2.3.2.1（只读）；自发货店铺建不了单。"
        >
          <div className="grid gap-3 md:grid-cols-3">
            <FormField id="cs-shop" label="店铺 ID（2.3.2.1）" required>
              <Input
                id="cs-shop"
                value={shopId}
                onChange={(e) => setShopId(e.target.value)}
                placeholder="店铺 UUID"
              />
            </FormField>
            <FormField id="cs-warehouse" label="平台仓编码" required>
              <Input
                id="cs-warehouse"
                value={warehouseCode}
                onChange={(e) => setWarehouseCode(e.target.value)}
                placeholder="FBA-WH-1"
              />
            </FormField>
            <FormField id="cs-legal" label="法人 ID" required>
              <Input
                id="cs-legal"
                value={legalEntityId}
                onChange={(e) => setLegalEntityId(e.target.value)}
                placeholder="法人 UUID"
              />
            </FormField>
            <FormField id="cs-currency" label="币种" required>
              <Input
                id="cs-currency"
                value={currency}
                onChange={(e) => setCurrency(e.target.value)}
                placeholder="CNY"
              />
            </FormField>
            <FormField id="cs-basis" label="结算口径" hint={settlementBasisHint(newBasis)} required>
              <select
                id="cs-basis"
                className="border-input bg-background h-9 w-full rounded-md border px-2 text-sm"
                value={newBasis}
                onChange={(e) => setNewBasis(e.target.value)}
              >
                {capabilities?.settlementBases.map((b) => (
                  <option key={b.value} value={b.value}>
                    {b.label}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField
              id="cs-forecast"
              label="备货预报（10.4.2.2，可选）"
              hint="给了预报单就按预报明细预填备货行；上游读不到会直接报 503，不会拿空单糊过去。"
            >
              <Input
                id="cs-forecast"
                value={forecastId}
                onChange={(e) => setForecastId(e.target.value)}
                placeholder="预报单 UUID"
              />
            </FormField>
            <FormField id="cs-sku" label="SKU ID（不按预报预填时必填）">
              <Input
                id="cs-sku"
                value={skuId}
                onChange={(e) => setSkuId(e.target.value)}
                placeholder="SKU UUID"
              />
            </FormField>
            <FormField id="cs-qty" label="备货量">
              <Input
                id="cs-qty"
                value={qty}
                onChange={(e) => setQty(e.target.value)}
                placeholder="100"
              />
            </FormField>
          </div>
          <div className="mt-3 flex justify-end">
            <Button
              size="sm"
              disabled={saving}
              onClick={() =>
                run(async () => {
                  const created = await createConsignmentOrder({
                    shopId,
                    platformWarehouseCode: warehouseCode,
                    platformWarehouseName: '',
                    legalEntityId,
                    currency,
                    settlementBasis: newBasis,
                    forecastId: forecastId || null,
                    note: '',
                    lines:
                      forecastId || !skuId
                        ? []
                        : [{ skuId, stockedQuantity: Number(qty), unit: 'PCS', note: '' }],
                  });
                  setSelected(created.order.id);
                  setNotice('备货单已建立。');
                })
              }
            >
              建立备货单
            </Button>
          </div>
        </SectionCard>
      )}

      {/* ---------------- 单据详情 ---------------- */}
      {selected && (
        <SectionCard
          title={order ? `${order.orderCode} · ${order.shopName}` : '备货单详情'}
          description={
            order
              ? `经营模式 ${operatingModeLabel(order.operatingMode)} · 平台仓 ${order.platformWarehouseCode} · 结算口径 ${order.settlementBasisLabel}`
              : undefined
          }
          extra={
            drillLoading ? (
              <span className="text-muted-foreground text-xs">读取中…</span>
            ) : undefined
          }
        >
          {!detail && drillLoading && (
            <p className="text-muted-foreground text-sm">正在读取单据…</p>
          )}

          {order && (
            <>
              {/* 分账声明 */}
              <Notice tone="info" title="分账口径">
                <p>{segregationStatement(ledger)}</p>
                <p className="mt-1">
                  本单自有库存合计：{formatOwnInventory(reconciliation?.ownInventoryOnHandQuantity)}{' '}
                  —— 它不含平台在库，两者不可相加。
                </p>
              </Notice>

              {/* 单据动作 */}
              {writable && (
                <div className="mt-3 flex flex-wrap gap-2">
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={saving || !canShip(orderStatus)}
                    onClick={() =>
                      run(async () => {
                        await shipConsignmentOrder(order.id, '发出');
                        setNotice('备货单已发出，备货量开始生效。');
                      })
                    }
                  >
                    发货
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={saving || !canConfirmReceipt(orderStatus)}
                    onClick={() =>
                      run(async () => {
                        await confirmConsignmentReceipt(order.id, '平台确认收货');
                        setNotice('已登记平台确认收货。');
                      })
                    }
                  >
                    平台确认收货
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={
                      saving || !canCloseWithSettlement(orderStatus, unsettled, unsettledKnown)
                    }
                    title={
                      canCloseWithSettlement(orderStatus, unsettled, unsettledKnown)
                        ? undefined
                        : '结算未结不为 0（或平台还没给回执）时不能关单：结清之前关掉，未结就没有单据可追。'
                    }
                    onClick={() =>
                      run(async () => {
                        await closeConsignmentOrder(order.id, '结清关单');
                        setNotice('备货单已关闭。');
                      })
                    }
                  >
                    关闭
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={saving || !canCancel(orderStatus)}
                    onClick={() =>
                      run(async () => {
                        await cancelConsignmentOrder(order.id, '作废');
                        setNotice('备货单已作废。');
                      })
                    }
                  >
                    作废
                  </Button>
                </div>
              )}

              {/* 结算口径标记（供 12 结算） */}
              {basisView && (
                <div className="border-border mt-4 rounded-md border p-3">
                  <p className="text-label-13 font-semibold">
                    结算口径标记（供 12.3.1 结算使用）：{basisView.label}
                  </p>
                  <p className="text-muted-foreground mt-1 text-xs">{basisView.rule}</p>
                  <div className="mt-2 grid gap-2 text-sm md:grid-cols-4">
                    <div>
                      <div className="text-muted-foreground text-xs">平台已售</div>
                      <div>{basisView.sold}</div>
                    </div>
                    <div>
                      <div className="text-muted-foreground text-xs">已结算</div>
                      <div>{basisView.settled}</div>
                    </div>
                    <div>
                      <div className="text-muted-foreground text-xs">结算未结</div>
                      <div
                        className={
                          settlementStateTone(basis?.settlementState) === 'warn'
                            ? 'text-warning'
                            : 'text-foreground'
                        }
                      >
                        {basisView.unsettled}
                      </div>
                    </div>
                    <div>
                      <div className="text-muted-foreground text-xs">结算状态</div>
                      <div>{basisView.state}</div>
                    </div>
                  </div>
                  {basisView.periods.length > 0 && (
                    <ul className="text-muted-foreground mt-2 space-y-1 text-xs">
                      {basisView.periods.map((p) => (
                        <li key={p}>{p}</li>
                      ))}
                    </ul>
                  )}
                </div>
              )}

              {/* 明细与四口径 */}
              <h3 className="text-label-13 mb-2 mt-4 font-semibold">备货明细（四个口径分列）</h3>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="text-muted-foreground border-b text-left">
                    <tr>
                      <th className="py-2 pr-3">SKU</th>
                      <th className="py-2 pr-3 text-right">备货量</th>
                      <th className="py-2 pr-3 text-right">平台在库</th>
                      <th className="py-2 pr-3 text-right">平台已售</th>
                      <th className="py-2 pr-3 text-right">在途未确认</th>
                      <th className="py-2 pr-3 text-right">结算未结</th>
                      <th className="py-2 pr-3 text-right">自有库存（不含托管量）</th>
                    </tr>
                  </thead>
                  <tbody>
                    {ledger?.rows.map((l) => (
                      <tr key={l.orderLineId}>
                        <td className="py-2 pr-3">
                          {l.skuCode}
                          <span className="text-muted-foreground ml-1 text-xs">{l.skuName}</span>
                        </td>
                        <td className="py-2 pr-3 text-right">{formatQty(l.stockedQuantity)}</td>
                        <td className="py-2 pr-3 text-right">
                          {formatQty(l.platformOnHandQuantity)}
                        </td>
                        <td className="py-2 pr-3 text-right">
                          {formatQty(l.platformSoldQuantity)}
                        </td>
                        <td className="py-2 pr-3 text-right">{formatQty(l.inTransitQuantity)}</td>
                        <td className="py-2 pr-3 text-right">{formatQty(l.unsettledQuantity)}</td>
                        <td className="py-2 pr-3 text-right">
                          {formatOwnInventory(l.ownInventoryOnHandQuantity)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {/* 对账结果 */}
              <h3 className="text-label-13 mb-2 mt-4 font-semibold">
                平台仓收发对账 · 与自有库存的分账
              </h3>
              {reconciliation && (
                <>
                  <div className="mb-2 flex flex-wrap items-center gap-3 text-sm">
                    <span
                      className={
                        segregationTone(reconciliation.segregationStatus) === 'ok'
                          ? 'text-success'
                          : 'text-destructive'
                      }
                    >
                      分账校验：{reconciliation.segregationStatusLabel}
                      {reconciliation.segregationLeakQuantity > 0
                        ? `（自有库存账本里出现了 ${formatQty(reconciliation.segregationLeakQuantity)} 的平台托管入库）`
                        : '（自有库存账本里没有任何平台托管入库）'}
                    </span>
                  </div>
                  <div className="overflow-x-auto">
                    <table className="w-full text-sm">
                      <thead className="text-muted-foreground border-b text-left">
                        <tr>
                          <th className="py-2 pr-3">SKU</th>
                          <th className="py-2 pr-3 text-right">差异量</th>
                          <th className="py-2 pr-3">差异口径</th>
                          <th className="py-2 pr-3">说明</th>
                        </tr>
                      </thead>
                      <tbody>
                        {reconciliation.rows.map((r) => (
                          <tr key={r.orderLineId}>
                            <td className="py-2 pr-3">{r.skuCode}</td>
                            <td className="py-2 pr-3 text-right">
                              {formatQty(r.differenceQuantity)}
                            </td>
                            <td className="py-2 pr-3">{r.differenceBasisLabel}</td>
                            <td className="text-muted-foreground py-2 pr-3 text-xs">
                              {r.differenceNote}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <p className="text-muted-foreground mt-2 text-xs">
                    {reconciliation.differenceNote}
                  </p>
                </>
              )}

              {/* 登记平台回执 */}
              {writable && canRecordReceipt(orderStatus) && (
                <div className="border-border mt-4 rounded-md border p-3">
                  <p className="text-label-13 font-semibold">登记平台仓收发回执</p>
                  <p className="text-muted-foreground mt-1 text-xs">
                    平台在库与平台已售只来自平台回执：我方发货确认不了平台收没收到，更推不出卖了多少。
                  </p>
                  <div className="mt-2 grid gap-3 md:grid-cols-4">
                    <FormField id="cs-line" label="明细行">
                      <select
                        id="cs-line"
                        className="border-input bg-background h-9 w-full rounded-md border px-2 text-sm"
                        value={receiptLineId}
                        onChange={(e) => setReceiptLineId(e.target.value)}
                      >
                        <option value="">选择明细行</option>
                        {detail?.lines.map((l) => (
                          <option key={l.id} value={l.id}>
                            {l.skuCode}
                          </option>
                        ))}
                      </select>
                    </FormField>
                    <FormField id="cs-receipt-type" label="回执类型">
                      <select
                        id="cs-receipt-type"
                        className="border-input bg-background h-9 w-full rounded-md border px-2 text-sm"
                        value={receiptType}
                        onChange={(e) => setReceiptType(e.target.value)}
                      >
                        <option value="INBOUND">平台入库</option>
                        <option value="OUTBOUND">平台出库（= 平台已售）</option>
                      </select>
                    </FormField>
                    <FormField id="cs-receipt-ref" label="平台回执号" required>
                      <Input
                        id="cs-receipt-ref"
                        value={receiptRef}
                        onChange={(e) => setReceiptRef(e.target.value)}
                      />
                    </FormField>
                    <FormField id="cs-receipt-qty" label="数量" required>
                      <Input
                        id="cs-receipt-qty"
                        value={receiptQty}
                        onChange={(e) => setReceiptQty(e.target.value)}
                      />
                    </FormField>
                    <FormField
                      id="cs-receipt-at"
                      label="发生时刻"
                      hint="ISO 8601（含时区）。平台仓的收发时间是业务事实，登记时刻不是。"
                      required
                    >
                      <Input
                        id="cs-receipt-at"
                        value={receiptAt}
                        onChange={(e) => setReceiptAt(e.target.value)}
                        placeholder="2026-11-01T10:00:00+08:00"
                      />
                    </FormField>
                  </div>
                  <div className="mt-2 flex justify-end">
                    <Button
                      size="sm"
                      disabled={saving}
                      onClick={() =>
                        run(async () => {
                          await recordConsignmentReceipt(order.id, {
                            orderLineId: receiptLineId,
                            receiptType,
                            platformReceiptRef: receiptRef,
                            quantity: Number(receiptQty),
                            occurredAt: receiptAt,
                            sourceSystem: 'PLATFORM_FEED',
                            note: '',
                          });
                          setNotice('平台回执已登记。');
                        })
                      }
                    >
                      登记平台回执
                    </Button>
                  </div>
                </div>
              )}

              {/* 登记结算回执 */}
              {writable && canRecordSettlement(orderStatus) && (
                <div className="border-border mt-3 rounded-md border p-3">
                  <p className="text-label-13 font-semibold">登记平台结算回执</p>
                  <p className="text-muted-foreground mt-1 text-xs">
                    口径随回执走（平台合同规则不是我方自选），但必须与本单口径一致：
                    {settlementBasisHint(order.settlementBasis)}
                  </p>
                  <div className="mt-2 grid gap-3 md:grid-cols-3">
                    <FormField id="cs-settle-line" label="明细行">
                      <select
                        id="cs-settle-line"
                        className="border-input bg-background h-9 w-full rounded-md border px-2 text-sm"
                        value={settleLineId}
                        onChange={(e) => setSettleLineId(e.target.value)}
                      >
                        <option value="">选择明细行</option>
                        {detail?.lines.map((l) => (
                          <option key={l.id} value={l.id}>
                            {l.skuCode}
                          </option>
                        ))}
                      </select>
                    </FormField>
                    <FormField id="cs-settle-start" label="结算区间起" required>
                      <Input
                        id="cs-settle-start"
                        value={settlePeriodStart}
                        onChange={(e) => setSettlePeriodStart(e.target.value)}
                        placeholder="2026-11-01"
                      />
                    </FormField>
                    <FormField id="cs-settle-end" label="结算区间止" required>
                      <Input
                        id="cs-settle-end"
                        value={settlePeriodEnd}
                        onChange={(e) => setSettlePeriodEnd(e.target.value)}
                        placeholder="2026-11-03"
                      />
                    </FormField>
                    <FormField id="cs-settle-qty" label="已结算数量" required>
                      <Input
                        id="cs-settle-qty"
                        value={settleQty}
                        onChange={(e) => setSettleQty(e.target.value)}
                      />
                    </FormField>
                    <FormField id="cs-settle-ref" label="平台结算回执号" required>
                      <Input
                        id="cs-settle-ref"
                        value={settleRef}
                        onChange={(e) => setSettleRef(e.target.value)}
                      />
                    </FormField>
                    <FormField
                      id="cs-settle-amount"
                      label="结算金额（可空）"
                      hint="平台没给金额就留空 —— 留空是「平台没给」，填 0 是谎报。金额归 12 财务。"
                    >
                      <Input
                        id="cs-settle-amount"
                        value={settleAmount}
                        onChange={(e) => setSettleAmount(e.target.value)}
                      />
                    </FormField>
                  </div>
                  <div className="mt-2 flex justify-end">
                    <Button
                      size="sm"
                      disabled={saving}
                      onClick={() =>
                        run(async () => {
                          await recordConsignmentSettlementReceipt(order.id, {
                            orderLineId: settleLineId,
                            settlementBasis: order.settlementBasis,
                            periodStart: settlePeriodStart,
                            periodEnd: settlePeriodEnd,
                            settledQuantity: Number(settleQty),
                            settledAmount: settleAmount ? Number(settleAmount) : null,
                            currency: order.currency,
                            platformRef: settleRef,
                            receivedAt: new Date().toISOString(),
                            sourceSystem: 'PLATFORM_FEED',
                            note: '',
                          });
                          setNotice('平台结算回执已登记。');
                        })
                      }
                    >
                      登记结算回执
                    </Button>
                  </div>
                </div>
              )}

              {/* 加行 */}
              {writable && canAddLine(orderStatus) && (
                <div className="border-border mt-3 rounded-md border p-3">
                  <p className="text-label-13 font-semibold">追加备货明细行</p>
                  <div className="mt-2 grid gap-3 md:grid-cols-3">
                    <FormField id="cs-add-sku" label="SKU ID" required>
                      <Input
                        id="cs-add-sku"
                        value={skuId}
                        onChange={(e) => setSkuId(e.target.value)}
                      />
                    </FormField>
                    <FormField id="cs-add-qty" label="备货量" required>
                      <Input id="cs-add-qty" value={qty} onChange={(e) => setQty(e.target.value)} />
                    </FormField>
                  </div>
                  <div className="mt-2 flex justify-end">
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={saving}
                      onClick={() =>
                        run(async () => {
                          await addConsignmentOrderLine(order.id, {
                            skuId,
                            stockedQuantity: Number(qty),
                            unit: 'PCS',
                            note: '',
                          });
                          setNotice('明细行已追加。');
                        })
                      }
                    >
                      追加行
                    </Button>
                  </div>
                </div>
              )}

              {/* 回执与审计 */}
              <div className="mt-4 grid gap-4 md:grid-cols-2">
                <div>
                  <h3 className="text-label-13 mb-2 font-semibold">平台仓回执</h3>
                  {(receipts?.receipts.length ?? 0) === 0 ? (
                    <p className="text-muted-foreground text-sm">
                      还没有平台回执。平台在库数是平台回执的观测值，没有回执就只知道备货量。
                    </p>
                  ) : (
                    <ul className="space-y-1 text-sm">
                      {receipts?.receipts.map((r) => (
                        <li key={r.id} className="border-border border-b pb-1">
                          <span className="font-medium">{r.receiptTypeLabel}</span> · {r.skuCode} ·{' '}
                          {formatQty(r.quantity)} 件 · 回执号 {r.platformReceiptRef}
                          <span className="text-muted-foreground ml-1 text-xs">{r.occurredAt}</span>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
                <div>
                  <h3 className="text-label-13 mb-2 font-semibold">结算回执</h3>
                  {(settlements?.receipts.length ?? 0) === 0 ? (
                    <p className="text-muted-foreground text-sm">还没有平台结算回执。</p>
                  ) : (
                    <ul className="space-y-1 text-sm">
                      {settlements?.receipts.map((r) => (
                        <li key={r.id} className="border-border border-b pb-1">
                          {r.settlementBasisLabel} · {r.skuCode} · {formatQty(r.settledQuantity)} 件
                          · {r.periodStart} ~ {r.periodEnd} · 回执号 {r.platformRef}
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              </div>

              <h3 className="text-label-13 mb-2 mt-4 font-semibold">审计时间线</h3>
              {audit.length === 0 ? (
                <p className="text-muted-foreground text-sm">暂无审计记录。</p>
              ) : (
                <ul className="space-y-1 text-sm">
                  {audit.map((a) => (
                    <li key={a.id}>
                      <span className="font-medium">{auditLabel(a.eventType)}</span>
                      <span className="text-muted-foreground ml-2 text-xs">
                        {a.occurredAt} · {a.detail}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}
        </SectionCard>
      )}

      {/* 结算未结的兜底说明：平台没给回执时，未结量不是 0。 */}
      {basis && !basis.unsettledKnown && (
        <Notice tone="warning" title="平台还没给任何回执，结算未结量暂不可知">
          未结量显示为「{formatUnsettled(null, false)}」而不是 0 ——
          「没给回执」不等于「没有未结」。等平台给了出库或结算回执，这里才会出现真实数字。
        </Notice>
      )}
    </div>
  );
}
