'use client';

import type { ReactNode } from 'react';
import { useCallback, useEffect, useState } from 'react';
import { PackageSearch, RefreshCw, TriangleAlert } from 'lucide-react';
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
  DropshipAlertPage,
  DropshipAlertRow,
  DropshipEndpointPage,
  DropshipOrderCapabilities,
  DropshipOrderDetail,
  DropshipOrderPage,
  DropshipOrderRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  ALERT_KINDS,
  ALERT_STATUSES,
  ORDER_STATUSES,
  SEVERITIES,
  VENDOR_CODES,
  acknowledgeDropshipAlert,
  alertKindLabel,
  alertStatusLabel,
  canDispatch,
  canMarkFailed,
  canRecordAck,
  canRecordShipment,
  confirmDropshipPayment,
  costDrift,
  dimensionNote,
  dispatchDropshipOrder,
  dispatchModeLabel,
  driftText,
  endpointFormError,
  fetchDropshipAlerts,
  fetchDropshipEndpoints,
  fetchDropshipOrder,
  fetchDropshipOrderCapabilities,
  fetchDropshipOrders,
  hasSupplierAck,
  markDropshipOrderFailed,
  moneyText,
  negativeSpread,
  openCriticalAlertCount,
  orderStatusLabel,
  paymentConfirmed,
  paymentFormError,
  qtyText,
  actualCostText,
  marginRateText,
  recordDropshipShipment,
  recordDropshipSupplierAck,
  saveDropshipEndpoint,
  severityLabel,
  spreadText,
  supplierAckFormError,
  vendorLabel,
} from './dropship-order-model';

const PAGE_SIZE = 20;

/**
 * 表单字段外壳。
 *
 * <p>与 9.6.1.1 工作区同一理由刻意不用 `FormField`：本页大量字段是 `<select>`，
 * 而 `FormField` 要求 children 是 `Input`。视觉一致，但下拉与输入可共用外壳。
 */
function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="space-y-2">
      <span className="text-label-13 text-foreground block">{label}</span>
      {children}
    </div>
  );
}

/**
 * 9.6.1.2 代发单与供应商下单工作区。
 *
 * <p>蓝图：「买家下单后，自动/手工向 1688 等供应商代发平台下单，传递地址与商品信息，
 * 回填供应商单号与代发成本，计算我方差价毛利。输出：代发单（供应商、单号、发货状态）；
 * 差价成本；差异告警（供应商无货/涨价/失败）。」
 *
 * <p><b>四个区块按「钱会不会亏」的顺序排</b>，而不是按操作顺序：
 * 差异告警 → 代发单（差价） → 供应商接口 → 建单入口。
 * 告警排在最前是因为蓝图那句「否则差价转负的订单会静默亏损」——
 * 静默亏损的解法首先是把亏损放到最显眼的位置。
 */
export function DropshipOrderWorkspace() {
  const [caps, setCaps] = useState<DropshipOrderCapabilities | null>(null);
  const [orders, setOrders] = useState<DropshipOrderPage | null>(null);
  const [alerts, setAlerts] = useState<DropshipAlertPage | null>(null);
  const [endpoints, setEndpoints] = useState<DropshipEndpointPage | null>(null);
  const [detail, setDetail] = useState<DropshipOrderDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const [statusFilter, setStatusFilter] = useState('');
  const [negativeOnly, setNegativeOnly] = useState(false);
  const [alertStatus, setAlertStatus] = useState('OPEN');
  const [keyword, setKeyword] = useState('');

  const [paymentNote, setPaymentNote] = useState('');
  const [ack, setAck] = useState({
    supplierOrderNo: '',
    actualPurchaseAmount: '',
    actualSupplierShippingAmount: '',
    trackingNo: '',
  });
  const [failReason, setFailReason] = useState('');
  const [ship, setShip] = useState({ supplierTrackingNo: '', shippedAt: '' });
  const [endpoint, setEndpoint] = useState({
    vendorCode: 'ALIBABA_1688',
    vendorName: '',
    baseUrl: '',
    credentialRef: '',
    wired: false,
    note: '',
  });

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [c, o, a, e] = await Promise.all([
        fetchDropshipOrderCapabilities(),
        fetchDropshipOrders({
          ...(statusFilter ? { status: statusFilter } : {}),
          ...(negativeOnly ? { negativeSpreadOnly: true } : {}),
          ...(keyword.trim() ? { keyword: keyword.trim() } : {}),
          page: 0,
          size: PAGE_SIZE,
        }),
        fetchDropshipAlerts({
          ...(alertStatus ? { status: alertStatus } : {}),
          ...(keyword.trim() ? { keyword: keyword.trim() } : {}),
          page: 0,
          size: PAGE_SIZE,
        }),
        fetchDropshipEndpoints({ page: 0, size: 20 }),
      ]);
      setCaps(c);
      setOrders(o);
      setAlerts(a);
      setEndpoints(e);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载代发单失败');
    } finally {
      setLoading(false);
    }
  }, [statusFilter, negativeOnly, alertStatus, keyword]);

  useEffect(() => {
    void load();
  }, [load]);

  // 选中一张单据时拉详情（明细行 / 告警 / 事件流）
  const openOrder = useCallback(async (orderId: string) => {
    setBusy('detail');
    setError(null);
    try {
      setDetail(await fetchDropshipOrder(orderId));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载代发单详情失败');
    } finally {
      setBusy(null);
    }
  }, []);

  const act = useCallback(
    async (key: string, run: () => Promise<unknown>) => {
      setBusy(key);
      setError(null);
      try {
        await run();
        await load();
        if (detail) {
          try {
            setDetail(await fetchDropshipOrder(detail.order.id));
          } catch {
            // 详情刷新失败不影响主流程：列表已刷新，用户可重新点开
          }
        }
      } catch (e) {
        setError(e instanceof Error ? e.message : '操作失败');
      } finally {
        setBusy(null);
      }
    },
    [load, detail],
  );

  const total = orders?.total ?? 0;
  const openAlerts = alerts?.items ?? [];
  const criticalCount = openCriticalAlertCount(openAlerts);
  const negativeCount = (orders?.items ?? []).filter(negativeSpread).length;
  const awaitingAck = (orders?.items ?? []).filter((o) => o.status === 'DISPATCHED').length;

  const paymentNote_ = dimensionNote(caps?.dimensions ?? [], 'PAYMENT');
  const logiNote = dimensionNote(caps?.dimensions ?? [], 'LOGISTICS_TRACK');

  return (
    <div className="space-y-6">
      <PageHeader
        title="代发单与供应商下单"
        description="买家下单后向 1688 等供应商代发平台下单，回填供应商单号与代发成本，计算我方差价毛利；涨价 / 无货 / 失败 / 差价转负一律显式告警。"
        actions={
          <Button variant="outline" size="sm" onClick={() => void load()}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
        }
      />

      {error && (
        <div className="border-danger/40 bg-danger-soft text-danger flex items-center gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="h-4 w-4 shrink-0" />
          <span className="text-copy-13">{error}</span>
          <Button variant="outline" size="sm" onClick={() => void load()} className="ml-auto">
            重试
          </Button>
        </div>
      )}

      {paymentNote_ && (
        <Notice tone="warning" title="「已付款」是人工确认，不是系统读到的支付状态">
          {paymentNote_}
        </Notice>
      )}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="代发单数" value={loading ? '—' : total} />
        <StatCard
          label="未处置严重告警"
          value={loading ? '—' : criticalCount}
          comparisonLabel="涨价 / 无货 / 失败 / 差价转负"
        />
        <StatCard
          label="本页差价转负"
          value={loading ? '—' : negativeCount}
          polarity="negative"
          comparisonLabel="按生成列 spreadAmount"
        />
        <StatCard label="待录入供应商回执" value={loading ? '—' : awaitingAck} />
      </div>

      {/* ------------------------------------------------ 差异告警（最显眼） */}
      <SectionCard
        title="差异告警"
        description="蓝图原文：「供应商涨价或无货必须显式告警，否则差价转负的订单会静默亏损。」四类差异分开显示，因为处置动作各不相同（改售价 / 换供应商 / 重下 / 止损）。处置只改处置字段，不删历史行 —— 告警是「已经贵过 3 块」这件事的凭据。"
      >
        <div className="mb-4 flex flex-wrap items-end gap-3">
          <Field label="状态">
            <select
              className="border-input bg-background h-9 rounded-md border px-2 text-copy-13"
              value={alertStatus}
              onChange={(e) => setAlertStatus(e.target.value)}
            >
              <option value="">全部</option>
              {ALERT_STATUSES.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="关键字">
            <Input
              value={keyword}
              placeholder="单号或告警详情"
              onChange={(e) => setKeyword(e.target.value)}
              className="w-56"
            />
          </Field>
        </div>

        {loading ? (
          <p className="text-copy-13 text-muted">加载中…</p>
        ) : openAlerts.length === 0 ? (
          <EmptyState
            icon={<PackageSearch className="h-6 w-6" />}
            title="没有差异告警"
            description="供应商回执的采购价没有高于下单冻结价，差价也没有转负。回执一旦涨价，这里会立刻出现一条涨价告警。"
          />
        ) : (
          <ul className="flex flex-col gap-2">
            {openAlerts.map((a) => (
              <AlertItem
                key={a.id}
                alert={a}
                busy={busy === `alert-${a.id}`}
                onAcknowledge={(note) =>
                  act(`alert-${a.id}`, () => acknowledgeDropshipAlert(a.id, { resolutionNote: note }))
                }
              />
            ))}
          </ul>
        )}
      </SectionCard>

      {/* ------------------------------------------------ 代发单与差价 */}
      <SectionCard
        title="代发单与差价成本"
        description="代发成本 = 采购价 + 供应商运费；差价 = 售价 −（采购价 + 运费）。三列都是 DB 生成列，前端只格式化不重算。差价为负不拦（拦了就录不进真实亏损的单），但会自动落一条亏损告警。"
      >
        <div className="mb-4 flex flex-wrap items-end gap-3">
          <Field label="状态">
            <select
              className="border-input bg-background h-9 rounded-md border px-2 text-copy-13"
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
            >
              <option value="">全部</option>
              {ORDER_STATUSES.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </Field>
          <label className="text-copy-13 flex items-center gap-2">
            <input
              type="checkbox"
              checked={negativeOnly}
              onChange={(e) => setNegativeOnly(e.target.checked)}
            />
            只看差价转负
          </label>
        </div>

        {loading ? (
          <p className="text-copy-13 text-muted">加载中…</p>
        ) : !orders || orders.items.length === 0 ? (
          <EmptyState
            icon={<PackageSearch className="h-6 w-6" />}
            title="还没有代发单"
            description="用一张已提交的销售订单 + 一份生效的 9.6.1.1 代发配置建单，采购价会在建单那一刻冻结。"
          />
        ) : (
          <ul className="flex flex-col gap-2">
            {orders.items.map((o) => (
              <li key={o.id} className="border-border rounded-lg border p-3">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-copy-13 font-medium">{o.orderNo}</span>
                  <Badge tone="neutral">{orderStatusLabel(o.status)}</Badge>
                  <Badge tone="neutral">{vendorLabel(o.vendorCode)}</Badge>
                  <Badge tone="neutral">供应商 {o.supplierCode}</Badge>
                  {/* 差价转负必须显眼：这是蓝图点名的那一格 */}
                  {negativeSpread(o) && <Badge tone="danger">差价转负（亏损）</Badge>}
                  {o.status === 'FAILED' && <Badge tone="warning">下单失败</Badge>}
                  <span className="text-copy-13 text-muted ml-auto">
                    {o.salesOrderNo} · {o.currencyCode}
                  </span>
                </div>
                <div className="text-copy-13 mt-2 grid gap-2 sm:grid-cols-3 lg:grid-cols-6">
                  <span>
                    售价 <b>{moneyText(o.sellAmount)}</b>
                  </span>
                  <span>
                    采购价 <b>{moneyText(o.purchaseAmount)}</b>
                  </span>
                  <span>
                    供应商运费 <b>{moneyText(o.supplierShippingAmount)}</b>
                  </span>
                  <span>
                    代发成本 <b>{moneyText(o.dropshipCost)}</b>
                  </span>
                  <span className={negativeSpread(o) ? 'text-danger font-medium' : ''}>
                    差价 <b>{spreadText(o.spreadAmount)}</b>
                  </span>
                  <span>
                    差价率 <b>{marginRateText(o.marginRate)}</b>
                  </span>
                </div>
                <div className="text-copy-13 text-muted mt-1">
                  实际采购价 <b>{actualCostText(o.actualPurchaseAmount)}</b> · 实际运费{' '}
                  <b>{actualCostText(o.actualSupplierShippingAmount)}</b> · 偏离冻结价{' '}
                  <b>{driftText(costDrift(o))}</b>
                  {hasSupplierAck(o) && o.supplierOrderNo && (
                    <>
                      {' · '}
                      供应商单号 <b>{o.supplierOrderNo}</b>
                    </>
                  )}
                </div>
                {/* 收款确认显出主体与依据：这是人工书面确认，不是系统支付状态 */}
                <div className="text-copy-13 text-muted mt-1">
                  {paymentConfirmed(o) ? (
                    <>
                      收款已由成员确认（{o.paymentConfirmedNote}）
                    </>
                  ) : (
                    '收款未确认 —— 未确认前不允许提交给供应商'
                  )}
                </div>
                {o.status === 'FAILED' && o.rejectionReason && (
                  <div className="text-copy-13 text-danger mt-1">失败原因：{o.rejectionReason}</div>
                )}

                <div className="mt-3 flex flex-wrap gap-2">
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={busy !== null}
                    onClick={() => void openOrder(o.id)}
                  >
                    {busy === 'detail' && o.id === detail?.order.id ? '加载中…' : '查看详情'}
                  </Button>
                  {o.status === 'DRAFT' && (
                    <Button
                      size="sm"
                      disabled={busy !== null || !paymentNote.trim()}
                      onClick={() =>
                        void act(`pay-${o.id}`, () =>
                          confirmDropshipPayment(o.id, {
                            confirmedAt: new Date().toISOString(),
                            confirmedByMembershipId: '',
                            note: paymentNote.trim(),
                          }),
                        )
                      }
                    >
                      确认收款
                    </Button>
                  )}
                  {canDispatch(o) && (
                    <Button
                      size="sm"
                      disabled={busy !== null}
                      onClick={() =>
                        void act(`dispatch-${o.id}`, () => dispatchDropshipOrder(o.id))
                      }
                    >
                      提交供应商
                    </Button>
                  )}
                  {canMarkFailed(o) && (
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busy !== null || !failReason.trim()}
                      onClick={() =>
                        void act(`fail-${o.id}`, () =>
                          markDropshipOrderFailed(o.id, { reason: failReason.trim() }),
                        )
                      }
                    >
                      人工置失败
                    </Button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}

        <div className="mt-4 grid gap-3 lg:grid-cols-3">
          <Field label="收款确认依据（银行流水号 / 平台回执 / 现金签收）">
            <Input
              value={paymentNote}
              placeholder="留空等于没有凭据，事后无从追责"
              onChange={(e) => setPaymentNote(e.target.value)}
            />
          </Field>
          <Field label="人工置失败的原因">
            <Input
              value={failReason}
              placeholder="供应商口头拒单、平台返回失败…"
              onChange={(e) => setFailReason(e.target.value)}
            />
          </Field>
          <Field label="供应商运单号（记录发货用）">
            <Input
              value={ship.supplierTrackingNo}
              placeholder="运单号"
              onChange={(e) => setShip({ ...ship, supplierTrackingNo: e.target.value })}
            />
          </Field>
        </div>
      </SectionCard>

      {/* ------------------------------------------------ 单据详情与回执录入 */}
      {detail && (
        <SectionCard
          title={`代发单 ${detail.order.orderNo} · 详情`}
          description="明细行是「传递商品信息给供应商」的那一份；运单号在本模块只记录，跟踪与妥投判定属下游 9.6.1.3 与 10 物流。"
        >
          <div className="overflow-x-auto">
            <table className="w-full text-left text-copy-13">
              <thead className="text-muted">
                <tr>
                  <th className="py-2 pr-3">#</th>
                  <th className="py-2 pr-3">SKU</th>
                  <th className="py-2 pr-3">数量</th>
                  <th className="py-2 pr-3">售价</th>
                  <th className="py-2 pr-3">采购价</th>
                  <th className="py-2 pr-3">供应商货号</th>
                </tr>
              </thead>
              <tbody>
                {detail.lines.map((l) => (
                  <tr key={l.id} className="border-border border-t">
                    <td className="py-2 pr-3">{l.lineNo}</td>
                    <td className="py-2 pr-3">
                      {l.skuName || l.skuRef}
                      {l.specSnapshot && (
                        <span className="text-muted"> · {l.specSnapshot}</span>
                      )}
                    </td>
                    <td className="py-2 pr-3">{qtyText(l.quantity)}</td>
                    <td className="py-2 pr-3">{moneyText(l.sellUnitPrice)}</td>
                    <td className="py-2 pr-3">{moneyText(l.purchaseUnitPrice)}</td>
                    <td className="py-2 pr-3">{l.supplierSkuRef || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {canRecordAck(detail.order) && (
            <div className="mt-4">
              <h4 className="text-copy-13 font-medium">录入供应商回执</h4>
              <p className="text-copy-13 text-muted">
                与自动路径同一套告警逻辑：人工录入涨价同样落涨价告警，差价转负同样落亏损告警。
              </p>
              <div className="mt-2 grid gap-3 lg:grid-cols-4">
                <Field label="供应商单号">
                  <Input
                    value={ack.supplierOrderNo}
                    onChange={(e) => setAck({ ...ack, supplierOrderNo: e.target.value })}
                  />
                </Field>
                <Field label="实际采购价">
                  <Input
                    value={ack.actualPurchaseAmount}
                    placeholder="13.00"
                    onChange={(e) => setAck({ ...ack, actualPurchaseAmount: e.target.value })}
                  />
                </Field>
                <Field label="实际运费">
                  <Input
                    value={ack.actualSupplierShippingAmount}
                    placeholder="0.00"
                    onChange={(e) =>
                      setAck({ ...ack, actualSupplierShippingAmount: e.target.value })
                    }
                  />
                </Field>
                <Field label="运单号（可选）">
                  <Input
                    value={ack.trackingNo}
                    onChange={(e) => setAck({ ...ack, trackingNo: e.target.value })}
                  />
                </Field>
              </div>
              <Button
                size="sm"
                className="mt-3"
                disabled={busy !== null || supplierAckFormError(ack) !== null}
                onClick={() =>
                  void act(`ack-${detail.order.id}`, () =>
                    recordDropshipSupplierAck(detail.order.id, {
                      supplierOrderNo: ack.supplierOrderNo.trim(),
                      actualPurchaseAmount: Number(ack.actualPurchaseAmount),
                      actualSupplierShippingAmount: Number(ack.actualSupplierShippingAmount),
                      trackingNo: ack.trackingNo.trim(),
                      note: '',
                    }),
                  )
                }
              >
                录入回执
              </Button>
            </div>
          )}

          {canRecordShipment(detail.order) && (
            <div className="mt-4">
              <h4 className="text-copy-13 font-medium">记录供应商发货</h4>
              <Button
                size="sm"
                className="mt-2"
                disabled={busy !== null || !ship.supplierTrackingNo.trim()}
                onClick={() =>
                  void act(`ship-${detail.order.id}`, () =>
                    recordDropshipShipment(detail.order.id, {
                      supplierTrackingNo: ship.supplierTrackingNo.trim(),
                      shippedAt: new Date().toISOString(),
                    }),
                  )
                }
              >
                记为已发货
              </Button>
            </div>
          )}

          <div className="mt-4">
            <h4 className="text-copy-13 font-medium">事件流</h4>
            <ul className="mt-2 flex flex-col gap-1">
              {detail.events.map((e) => (
                <li key={e.id} className="text-copy-13">
                  <span className="text-muted">{e.occurredAt}</span> · {e.eventKind}
                  {e.toStatus && (
                    <span className="text-muted">
                      {' '}
                      {e.fromStatus} → {e.toStatus}
                    </span>
                  )}
                  {e.detail && <span> · {e.detail}</span>}
                </li>
              ))}
            </ul>
          </div>
        </SectionCard>
      )}

      {/* ------------------------------------------------ 供应商代发接口 */}
      <SectionCard
        title="供应商代发接口"
        description="只登记 base_url 与凭证**引用**（指向 secret manager 的键），系统不保存任何明文凭证。声称「已接线」就必须给出网关地址 —— 一个声称可用却调不通的集成比明确报未接线更糟。未接线的 vendor 提交下单时会显式失败，绝不返回编造的供应商单号。"
      >
        {loading ? (
          <p className="text-copy-13 text-muted">加载中…</p>
        ) : (
          <div className="flex flex-col gap-2">
            {(caps?.vendors ?? []).map((v) => {
              const registered = (endpoints?.items ?? []).find((e) => e.vendorCode === v.vendorCode);
              const wired = registered?.wired === true || v.wired;
              return (
                <div key={v.vendorCode} className="border-border flex items-center gap-2 rounded-lg border p-3">
                  <span className="text-copy-13 font-medium">{vendorLabel(v.vendorCode)}</span>
                  <Badge tone={wired ? 'success' : 'warning'}>
                    {wired ? '已接线' : '未接线'}
                  </Badge>
                  <span className="text-copy-13 text-muted">{v.displayName}</span>
                  {!wired && v.wiringRequirement && (
                    <span className="text-copy-13 text-muted">
                      还差：{v.wiringRequirement}。可改用「人工录入」路径 —— 蓝图输入句明写
                      「供应商代发接口<strong>或</strong>人工录入」。
                    </span>
                  )}
                </div>
              );
            })}
          </div>
        )}

        <div className="mt-4 grid gap-3 lg:grid-cols-3">
          <Field label="vendor">
            <select
              className="border-input bg-background h-9 rounded-md border px-2 text-copy-13"
              value={endpoint.vendorCode}
              onChange={(e) => setEndpoint({ ...endpoint, vendorCode: e.target.value })}
            >
              {VENDOR_CODES.map((v) => (
                <option key={v.value} value={v.value}>
                  {v.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="供应商名称">
            <Input
              value={endpoint.vendorName}
              onChange={(e) => setEndpoint({ ...endpoint, vendorName: e.target.value })}
            />
          </Field>
          <Field label="网关地址 base_url">
            <Input
              value={endpoint.baseUrl}
              placeholder="https://…"
              onChange={(e) => setEndpoint({ ...endpoint, baseUrl: e.target.value })}
            />
          </Field>
          <Field label="凭证引用 credential_ref（不是明文凭证）">
            <Input
              value={endpoint.credentialRef}
              placeholder="starcon/1688/appSecret"
              onChange={(e) => setEndpoint({ ...endpoint, credentialRef: e.target.value })}
            />
          </Field>
          <label className="text-copy-13 flex items-center gap-2">
            <input
              type="checkbox"
              checked={endpoint.wired}
              onChange={(e) => setEndpoint({ ...endpoint, wired: e.target.checked })}
            />
            标记为已接线
          </label>
          <Button
            size="sm"
            disabled={busy !== null || endpointFormError(endpoint) !== null}
            onClick={() => void act('endpoint', () => saveDropshipEndpoint({ ...endpoint }))}
          >
            保存登记
          </Button>
        </div>
      </SectionCard>

      {logiNote && (
        <Notice tone="info" title="供应商运单跟踪不在本模块">
          {logiNote}
        </Notice>
      )}
    </div>
  );
}

/** 一条差异告警 + 处置输入。 */
function AlertItem({
  alert,
  busy,
  onAcknowledge,
}: {
  alert: DropshipAlertRow;
  busy: boolean;
  onAcknowledge: (note: string) => void;
}) {
  const [note, setNote] = useState('');
  return (
    <li className="border-border rounded-lg border p-3">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={alert.severity === 'CRITICAL' ? 'danger' : 'warning'}>
          {severityLabel(alert.severity)}
        </Badge>
        <span className="text-copy-13 font-medium">{alert.alertKindLabel || alertKindLabel(alert.alertKind)}</span>
        <span className="text-copy-13 text-muted">{alert.orderNo}</span>
        <Badge tone="neutral">{alertStatusLabel(alert.status)}</Badge>
      </div>
      <p className="text-copy-13 mt-2">{alert.detail}</p>
      <div className="text-copy-13 text-muted mt-1">
        预计 {moneyText(alert.expectedAmount)} · 实际 {moneyText(alert.actualAmount)}
      </div>
      {alert.status === 'OPEN' ? (
        <div className="mt-2 flex flex-wrap items-center gap-2">
          <Input
            value={note}
            placeholder="处置说明：改价了？换供应商了？退款了？"
            onChange={(e) => setNote(e.target.value)}
            className="w-72"
          />
          <Button size="sm" disabled={busy || !note.trim()} onClick={() => onAcknowledge(note.trim())}>
            标记已处置
          </Button>
        </div>
      ) : (
        <div className="text-copy-13 text-muted mt-1">
          已处置：{alert.resolutionNote || '—'}
        </div>
      )}
    </li>
  );
}

export { ALERT_KINDS, dispatchModeLabel, paymentFormError, negativeSpread };