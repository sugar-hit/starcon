'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { ClipboardList, Plus, RefreshCw, TriangleAlert } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  SalesOrderAddressInput,
  SalesOrderCapabilities,
  SalesOrderChargeInput,
  SalesOrderCreateInput,
  SalesOrderCustomerInput,
  SalesOrderDetail,
  SalesOrderLineInput,
  SalesOrderPage,
  SalesOrderRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  addressRoleLabel,
  canSubmit,
  chargeLevelLabels,
  chargeSignHint,
  chargeSummary,
  chargeTypeLabel,
  chargeTypeLabels,
  createOrder,
  customerTypeLabels,
  eventTypeLabels,
  fetchCapabilities,
  fetchOrder,
  fetchOrders,
  fmt,
  isEditable,
  lineAmount,
  manualKindLabel,
  manualKindLabels,
  moment,
  money,
  needsTermsDays,
  normalizeChargeAmount,
  normalizeTermsDays,
  orderSourceLabel,
  paymentMethodLabel,
  paymentMethodLabels,
  previewTotals,
  salesOrderStatusLabels,
  statusLabel,
  statusTone,
  submitBlockers,
  submitOrder,
  type SalesOrderParams,
} from './manual-order-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 9.1.2.2 手工订单工作区。
 *
 * <p>蓝图：「支持非平台来源订单（独立站线下、B2B、代发、样机单）的录入，含客户选择、
 * 商品行、收货地址、费用与支付方式」，输出「手工销售订单（来源 = MANUAL）+ 后续同样进入
 * 审单履约流程」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>来源是字段不是选择项</b>：新建表单里**没有**来源下拉。手工通道只产 MANUAL，
 *       页面把来源画成固定徽章，让「为什么这里不能选」自解释。</li>
 *   <li><b>单价由人填，但没有任何默认价</b>：05 价目（D6）未交付，界面不内置默认单价、
 *       也不显示「建议价」。行小计与订单总额实时试算给人看，提交后由 DB 独立复算。</li>
 *   <li><b>折扣的负号由界面代劳</b>：只填幅度，符号按费用类型补 —— 正折扣会让「减钱」
 *       变成「加钱」而列表上看不出来。</li>
 *   <li><b>已提交的单只读</b>：提交后行 / 地址 / 费用 / 客户快照全部冻结，界面不给编辑入口。</li>
 *   <li>加载 / 空 / 错误三态齐全；未交付的下游写明归属模块，不给点下去 404 的按钮。</li>
 * </ul>
 */

type DraftLine = {
  skuId: string;
  skuCode: string;
  skuName: string;
  specSnapshot: string;
  skuMappingVersion: string;
  quantity: string;
  unitPrice: string;
  discountAmount: string;
  taxAmount: string;
  note: string;
};

type DraftCharge = {
  chargeType: string;
  label: string;
  amount: string;
  chargeLevel: string;
  note: string;
};

const emptyLine = (): DraftLine => ({
  skuId: crypto.randomUUID(),
  skuCode: '',
  skuName: '',
  specSnapshot: '',
  skuMappingVersion: '',
  quantity: '1',
  unitPrice: '',
  discountAmount: '0',
  taxAmount: '0',
  note: '',
});

const emptyCharge = (chargeType = 'SHIPPING'): DraftCharge => ({
  chargeType,
  label: '',
  amount: '',
  chargeLevel: 'ORDER',
  note: '',
});

export function ManualOrderWorkspace() {
  const [params, setParams] = useState<SalesOrderParams>({ page: 0, size: 20 });
  const [page, setPage] = useState<SalesOrderPage | null>(null);
  const [capabilities, setCapabilities] = useState<SalesOrderCapabilities | null>(null);
  const [detail, setDetail] = useState<SalesOrderDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  // 表单草稿
  const [orderNo, setOrderNo] = useState('');
  const [externalOrderNo, setExternalOrderNo] = useState('');
  const [manualKind, setManualKind] = useState('B2B');
  const [currency, setCurrency] = useState('USD');
  const [paymentMethod, setPaymentMethod] = useState('CREDIT_TERM');
  const [paymentTermsDays, setPaymentTermsDays] = useState('30');
  const [note, setNote] = useState('');
  const [customer, setCustomer] = useState({
    customerPartyId: crypto.randomUUID(),
    customerCode: '',
    customerName: '',
    customerType: 'ORGANIZATION',
    customerDefaultCurrency: '',
  });
  const [lines, setLines] = useState<DraftLine[]>([emptyLine()]);
  const [addresses, setAddresses] = useState<SalesOrderAddressInput[]>([
    {
      addressRole: 'SHIPPING',
      recipientName: '',
      recipientPhone: '',
      recipientEmail: '',
      companyName: '',
      countryCode: 'US',
      province: '',
      city: '',
      district: '',
      postcode: '',
      addressLine1: '',
      addressLine2: '',
      taxId: '',
      note: '',
    },
  ]);
  const [charges, setCharges] = useState<DraftCharge[]>([emptyCharge()]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setPage(await fetchOrders(params));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [params]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void fetchCapabilities().then(setCapabilities).catch(() => setCapabilities(null));
  }, []);

  const openDetail = useCallback(async (id: string) => {
    setDetailLoading(true);
    setError(null);
    try {
      setDetail(await fetchOrder(id));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setDetailLoading(false);
    }
  }, []);

  const resetForm = useCallback(() => {
    setOrderNo('');
    setExternalOrderNo('');
    setManualKind('B2B');
    setCurrency('USD');
    setPaymentMethod('CREDIT_TERM');
    setPaymentTermsDays('30');
    setNote('');
    setCustomer({
      customerPartyId: crypto.randomUUID(),
      customerCode: '',
      customerName: '',
      customerType: 'ORGANIZATION',
      customerDefaultCurrency: '',
    });
    setLines([emptyLine()]);
    setCharges([emptyCharge()]);
  }, []);

  // ---- 表单预览：与服务层、DB 触发器同一条公式，但界面上先算给人看。
  const preview = useMemo(
    () =>
      previewTotals(
        lines.map((l) => ({
          unitPrice: Number(l.unitPrice || 0),
          quantity: Number(l.quantity || 0),
          discountAmount: Number(l.discountAmount || 0),
          taxAmount: Number(l.taxAmount || 0),
        })),
        charges.map((c) => ({
          chargeType: c.chargeType,
          amount: Number(c.amount || 0),
        })),
      ),
    [lines, charges],
  );

  const blockers = useMemo(
    () =>
      submitBlockers({
        lines,
        addresses: addresses.map((a) => ({ addressRole: a.addressRole })),
        customer,
      }),
    [lines, addresses, customer],
  );

  const canSave =
    orderNo.trim() !== '' &&
    customer.customerCode.trim() !== '' &&
    customer.customerName.trim() !== '' &&
    lines.some((l) => l.skuCode.trim() !== '' && Number(l.quantity) > 0) &&
    blockers.length === 0;

  async function submitDraft() {
    if (!canSave) return;
    setSubmitting(true);
    setError(null);
    try {
      const input: SalesOrderCreateInput = {
        orderNo: orderNo.trim(),
        externalOrderNo: externalOrderNo.trim() === '' ? '' : externalOrderNo.trim(),
        manualKind: manualKind as SalesOrderCreateInput['manualKind'],
        currency: currency.trim().toUpperCase(),
        paymentMethod: paymentMethod as SalesOrderCreateInput['paymentMethod'],
        paymentTermsDays: normalizeTermsDays(paymentMethod, Number(paymentTermsDays) || 0),
        note,
        customer: customer as unknown as SalesOrderCustomerInput,
        lines: lines
          .filter((l) => l.skuCode.trim() !== '' && Number(l.quantity) > 0)
          .map(
            (l): SalesOrderLineInput => ({
              skuId: l.skuId,
              skuCode: l.skuCode.trim(),
              skuName: l.skuName.trim(),
              specSnapshot: l.specSnapshot,
              skuMappingVersion: l.skuMappingVersion,
              quantity: Number(l.quantity),
              unitPrice: Number(l.unitPrice || 0),
              discountAmount: Number(l.discountAmount || 0),
              taxAmount: Number(l.taxAmount || 0),
              note: l.note,
              attributes: [],
            }),
          ),
        addresses: addresses.filter((a) => a.recipientName.trim() !== ''),
        charges: charges
          .filter((c) => c.amount.trim() !== '' && Number(c.amount) !== 0)
          .map(
            (c): SalesOrderChargeInput => ({
              chargeType: c.chargeType as SalesOrderChargeInput['chargeType'],
              label: c.label,
              amount: normalizeChargeAmount(c.chargeType, Number(c.amount)),
              chargeLevel: c.chargeLevel as SalesOrderChargeInput['chargeLevel'],
              note: c.note,
            }),
          ),
      };
      const saved = await createOrder(input);
      setCreating(false);
      resetForm();
      await load();
      await openDetail(saved.id);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setSubmitting(false);
    }
  }

  async function doSubmit(id: string) {
    setSubmitting(true);
    setError(null);
    try {
      await submitOrder(id);
      await load();
      await openDetail(id);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setSubmitting(false);
    }
  }

  // ================================================================ 渲染

  return (
    <div className="space-y-6">
      <PageHeader
        title="手工订单"
        description="非平台来源订单（独立站线下 / B2B / 代发 / 样机单）的录入。来源固定 MANUAL，与平台单共用同一条履约链路。"
        actions={
          <>
            <Button variant="outline" onClick={() => void load()} disabled={loading}>
              <RefreshCw className="mr-2 h-4 w-4" />
              刷新
            </Button>
            <Button onClick={() => setCreating(true)}>
              <Plus className="mr-2 h-4 w-4" />
              新建手工订单
            </Button>
          </>
        }
      />

      {error && (
        <div className="border-destructive/40 bg-destructive/5 text-destructive flex items-center gap-2 rounded-md border px-3 py-2 text-sm">
          <TriangleAlert className="h-4 w-4 shrink-0" />
          {error}
        </div>
      )}

      {capabilities && !capabilities.priceSourceAvailable && (
        <div className="text-copy-12 text-muted-foreground rounded-md border px-3 py-2">
          05 价目系统（D6）未交付：单价是表单必填项，界面**不内置任何默认单价**。
          价目交付后由服务层取建议价填进表单，表结构不需要改。
        </div>
      )}

      <div className="grid gap-4 sm:grid-cols-3">
        <StatCard label="订单总数" value={page?.total ?? 0} />
        <StatCard label="草稿" value={page?.draftCount ?? 0} />
        <StatCard label="已提交待审" value={page?.submittedCount ?? 0} />
      </div>

      <SectionCard
        title="订单列表"
        description="支持按手工单子类 / 状态 / 客户 / 支付方式筛选；关键字命中单号、外部单号与客户名。"
      >
        <div className="mb-4 flex flex-wrap items-end gap-2">
          <div className="flex flex-col gap-1">
            <span className="text-copy-12 text-muted-foreground">关键字</span>
            <Input
              value={params.keyword ?? ''}
              placeholder="单号 / 外部单号 / 客户名"
              onChange={(e) => setParams({ ...params, keyword: e.target.value, page: 0 })}
            />
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-copy-12 text-muted-foreground">状态</span>
            <select
              className="border-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={params.status ?? ''}
              onChange={(e) => setParams({ ...params, status: e.target.value, page: 0 })}
            >
              <option value="">全部</option>
              {Object.entries(salesOrderStatusLabels).map(([v, l]) => (
                <option key={v} value={v}>
                  {l}
                </option>
              ))}
            </select>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-copy-12 text-muted-foreground">手工单子类</span>
            <select
              className="border-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={params.manualKind ?? ''}
              onChange={(e) => setParams({ ...params, manualKind: e.target.value, page: 0 })}
            >
              <option value="">全部</option>
              {Object.entries(manualKindLabels).map(([v, l]) => (
                <option key={v} value={v}>
                  {l}
                </option>
              ))}
            </select>
          </div>
        </div>

        {loading ? (
          <p className="text-copy-13 text-muted-foreground py-8 text-center">加载中…</p>
        ) : !page || page.rows.length === 0 ? (
          <EmptyState
            title="还没有手工订单"
            description="点右上角「新建手工订单」录入一张非平台来源订单。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-copy-12 text-muted-foreground border-b">
                <tr>
                  <th className="px-2 py-2 font-medium">单号</th>
                  <th className="px-2 py-2 font-medium">来源</th>
                  <th className="px-2 py-2 font-medium">子类</th>
                  <th className="px-2 py-2 font-medium">客户</th>
                  <th className="px-2 py-2 font-medium">支付方式</th>
                  <th className="px-2 py-2 text-right font-medium">行数</th>
                  <th className="px-2 py-2 text-right font-medium">总额</th>
                  <th className="px-2 py-2 font-medium">状态</th>
                  <th className="px-2 py-2 font-medium">创建时间</th>
                </tr>
              </thead>
              <tbody>
                {page.rows.map((row) => (
                  <tr
                    key={row.id}
                    className="hover:bg-muted/60 cursor-pointer border-b last:border-0"
                    onClick={() => void openDetail(row.id)}
                  >
                    <td className="px-2 py-2 font-medium">{row.orderNo}</td>
                    <td className="px-2 py-2">
                      <Badge tone="neutral">{orderSourceLabel(row.orderSource)}</Badge>
                    </td>
                    <td className="px-2 py-2">{manualKindLabel(row.manualKind)}</td>
                    <td className="px-2 py-2">{row.customerName}</td>
                    <td className="px-2 py-2">{paymentMethodLabel(row.paymentMethod)}</td>
                    <td className="text-copy-13 px-2 py-2 text-right">{row.lineCount}</td>
                    <td className="text-copy-13 px-2 py-2 text-right">
                      {money(row.grandTotalAmount)} {row.currency}
                    </td>
                    <td className="px-2 py-2">
                      <Badge tone={statusTone(row.status)}>{statusLabel(row.status)}</Badge>
                    </td>
                    <td className="text-copy-12 text-muted-foreground px-2 py-2">
                      {moment(row.createdAt)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 详情 */}
      <Modal open={detail !== null} onOpenChange={(open) => !open && setDetail(null)}>
        <ModalContent>
          {detailLoading || !detail ? (
            <p className="text-copy-13 text-muted-foreground py-8 text-center">加载中…</p>
          ) : (
            <>
              <ModalHeader>
                <ModalTitle>{detail.order.orderNo}</ModalTitle>
                <ModalDescription>
                  {orderSourceLabel(detail.order.orderSource)} ·{' '}
                  {manualKindLabel(detail.order.manualKind)} · {statusLabel(detail.order.status)}
                </ModalDescription>
              </ModalHeader>

              <div className="space-y-4">
                {!isEditable(detail.order.status) && (
                  <div className="text-copy-12 text-muted-foreground rounded-md border px-3 py-2">
                    已提交进审单队列：商品行、地址、费用与客户快照已冻结，不能再改。
                    审单结论由 9.2 推进。
                  </div>
                )}

                <dl className="grid grid-cols-2 gap-x-6 gap-y-2 text-sm md:grid-cols-4">
                  <Field label="外部订单号" value={detail.order.externalOrderNo || '—'} />
                  <Field label="币种" value={detail.order.currency} />
                  <Field
                    label="支付方式"
                    value={
                      paymentMethodLabel(detail.order.paymentMethod) +
                      (needsTermsDays(detail.order.paymentMethod)
                        ? `（${detail.order.paymentTermsDays} 天）`
                        : '')
                    }
                  />
                  <Field
                    label="客户"
                    value={
                      detail.customer
                        ? `${detail.customer.customerName}（${customerTypeLabels[detail.customer.customerType] ?? ''}）`
                        : '—'
                    }
                  />
                </dl>

                {/* 订单行快照：SKU / 规格 / 单价 / 税 / 折扣 / 映射版本逐项显示，
                    让「历史订单行不跟着商品改」这件事在界面上看得见。 */}
                <SectionCard title="商品行（快照）" description="商品参数事后被改，这里的取值一个字节都不会变。">
                  <table className="w-full text-left text-sm">
                    <thead className="text-copy-12 text-muted-foreground border-b">
                      <tr>
                        <th className="px-2 py-1 font-medium">#</th>
                        <th className="px-2 py-1 font-medium">SKU</th>
                        <th className="px-2 py-1 font-medium">规格</th>
                        <th className="px-2 py-1 font-medium">映射版本</th>
                        <th className="px-2 py-1 text-right font-medium">数量</th>
                        <th className="px-2 py-1 text-right font-medium">单价</th>
                        <th className="px-2 py-1 text-right font-medium">折扣</th>
                        <th className="px-2 py-1 text-right font-medium">税</th>
                        <th className="px-2 py-1 text-right font-medium">行小计</th>
                      </tr>
                    </thead>
                    <tbody>
                      {detail.lines.map((l) => (
                        <tr key={l.id} className="border-b last:border-0">
                          <td className="px-2 py-1">{l.lineNo}</td>
                          <td className="px-2 py-1">
                            <div className="font-medium">{l.skuCode}</div>
                            <div className="text-copy-12 text-muted-foreground">{l.skuName}</div>
                          </td>
                          <td className="px-2 py-1">{l.specSnapshot || '—'}</td>
                          <td className="px-2 py-1">{l.skuMappingVersion || '—'}</td>
                          <td className="text-copy-13 px-2 py-1 text-right">{fmt(l.quantity)}</td>
                          <td className="text-copy-13 px-2 py-1 text-right">{money(l.unitPrice)}</td>
                          <td className="text-copy-13 px-2 py-1 text-right">{money(l.discountAmount)}</td>
                          <td className="text-copy-13 px-2 py-1 text-right">{money(l.taxAmount)}</td>
                          <td className="text-copy-13 px-2 py-1 text-right">{money(l.lineAmount)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  {detail.lines.some((l) => l.attributes.length > 0) && (
                    <ul className="text-copy-12 text-muted-foreground mt-2 space-y-1">
                      {detail.lines
                        .filter((l) => l.attributes.length > 0)
                        .map((l) => (
                          <li key={l.id}>
                            {l.skuCode}：
                            {l.attributes
                              .map(
                                (a) =>
                                  `${a.attributeName}=${a.attributeValue}${
                                    a.attributeUnit ? ` ${a.attributeUnit}` : ''
                                  }（来源版本 ${a.sourceVersion || '—'}）`,
                              )
                              .join('；')}
                          </li>
                        ))}
                    </ul>
                  )}
                </SectionCard>

                <SectionCard
                  title="收货 / 开票地址（快照）"
                  description="地址是下单当时冻结的那一份，买家事后改地址改不动历史订单。"
                >
                  <ul className="space-y-2 text-sm">
                    {detail.addresses.map((a) => (
                      <li key={a.id} className="border-b last:border-0 pb-2">
                        <Badge tone="neutral">{addressRoleLabel(a.addressRole)}</Badge>
                        <div className="mt-1">
                          {a.recipientName}
                          {a.recipientPhone ? ` · ${a.recipientPhone}` : ''}
                        </div>
                        <div className="text-copy-12 text-muted-foreground">
                          {[a.countryCode, a.province, a.city, a.district, a.postcode]
                            .filter(Boolean)
                            .join(' ')}{' '}
                          {a.addressLine1}
                          {a.addressLine2 ? ` ${a.addressLine2}` : ''}
                        </div>
                      </li>
                    ))}
                  </ul>
                </SectionCard>

                <SectionCard
                  title="费用与支付方式"
                  description={`费用是表单的一部分：${chargeSummary(detail.charges)}`}
                >
                  <ul className="space-y-1 text-sm">
                    {detail.charges.map((c) => (
                      <li key={c.id} className="flex items-center justify-between gap-2">
                        <span>
                          {chargeTypeLabel(c.chargeType)}
                          {c.label ? ` · ${c.label}` : ''}
                          <span className="text-copy-12 text-muted-foreground">
                            （{chargeLevelLabels[c.chargeLevel] ?? c.chargeLevel}）
                          </span>
                        </span>
                        <span className="text-copy-13 font-medium">{money(c.amount)}</span>
                      </li>
                    ))}
                  </ul>
                </SectionCard>

                <SectionCard title="事件流" description="订单进了审单队列之后，第一个问题就是「谁在什么时候提上去的」。">
                  <ul className="space-y-1 text-sm">
                    {detail.events.map((e) => (
                      <li key={e.id} className="flex items-center justify-between gap-2">
                        <span>
                          {eventTypeLabels[e.eventType] ?? e.eventType}
                          <span className="text-copy-12 text-muted-foreground">
                            （{e.fromStatus || '—'} → {e.toStatus}）{e.reason ? ` · ${e.reason}` : ''}
                          </span>
                        </span>
                        <span className="text-copy-12 text-muted-foreground">
                          {moment(e.occurredAt)}
                        </span>
                      </li>
                    ))}
                  </ul>
                </SectionCard>
              </div>

              <ModalFooter>
                {canSubmit(detail.order.status) && (
                  <Button
                    onClick={() => void doSubmit(detail.order.id)}
                    disabled={submitting || blockersOf(detail).length > 0}
                  >
                    <ClipboardList className="mr-2 h-4 w-4" />
                    提交待审（进入 9.2 审单）
                  </Button>
                )}
                <Button variant="outline" onClick={() => setDetail(null)}>
                  关闭
                </Button>
              </ModalFooter>
              {canSubmit(detail.order.status) && blockersOf(detail).length > 0 && (
                <ul className="text-copy-12 text-muted-foreground mt-2 space-y-1">
                  {blockersOf(detail).map((b) => (
                    <li key={b}>· {b}</li>
                  ))}
                </ul>
              )}
            </>
          )}
        </ModalContent>
      </Modal>

      {/* ---------------------------------------------------------- 新建 */}
      <Modal open={creating} onOpenChange={setCreating}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>新建手工订单</ModalTitle>
            <ModalDescription>
              来源固定 <Badge tone="neutral">{orderSourceLabel('MANUAL')}</Badge>：
              手工通道只产手工单，让用户能选来源等于开了一条「手工录入写平台单」的后门。
            </ModalDescription>
          </ModalHeader>

          <div className="space-y-4">
            <div className="grid gap-3 md:grid-cols-3">
              <Labeled label="我方单号 *">
                <Input value={orderNo} onChange={(e) => setOrderNo(e.target.value)} />
              </Labeled>
              <Labeled label="外部订单号（客户参考号）">
                <Input
                  value={externalOrderNo}
                  onChange={(e) => setExternalOrderNo(e.target.value)}
                />
              </Labeled>
              <Labeled label="手工单子类 *">
                <select
                  className="border-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                  value={manualKind}
                  onChange={(e) => setManualKind(e.target.value)}
                >
                  {Object.entries(manualKindLabels).map(([v, l]) => (
                    <option key={v} value={v}>
                      {l}
                    </option>
                  ))}
                </select>
              </Labeled>
              <Labeled label="币种 *">
                <Input
                  value={currency}
                  onChange={(e) => setCurrency(e.target.value)}
                  placeholder="三位大写，如 USD"
                />
              </Labeled>
              <Labeled label="支付方式 *">
                <select
                  className="border-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                  value={paymentMethod}
                  onChange={(e) => setPaymentMethod(e.target.value)}
                >
                  {Object.entries(paymentMethodLabels).map(([v, l]) => (
                    <option key={v} value={v}>
                      {l}
                    </option>
                  ))}
                </select>
              </Labeled>
              {needsTermsDays(paymentMethod) && (
                <Labeled label="账期天数 *">
                  <Input
                    value={paymentTermsDays}
                    onChange={(e) => setPaymentTermsDays(e.target.value)}
                  />
                </Labeled>
              )}
            </div>

            <SectionCard title="客户选择 *" description="收全量快照字段：客户改名 / 改级别不改写历史订单。">
              <div className="grid gap-3 md:grid-cols-2">
                <Labeled label="客户 ID（catalog 逻辑引用）">
                  <Input
                    value={customer.customerPartyId}
                    onChange={(e) => setCustomer({ ...customer, customerPartyId: e.target.value })}
                  />
                </Labeled>
                <Labeled label="客户编码 *">
                  <Input
                    value={customer.customerCode}
                    onChange={(e) => setCustomer({ ...customer, customerCode: e.target.value })}
                  />
                </Labeled>
                <Labeled label="客户名称 *">
                  <Input
                    value={customer.customerName}
                    onChange={(e) => setCustomer({ ...customer, customerName: e.target.value })}
                  />
                </Labeled>
                <Labeled label="客户类型 *">
                  <select
                    className="border-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                    value={customer.customerType}
                    onChange={(e) => setCustomer({ ...customer, customerType: e.target.value })}
                  >
                    {Object.entries(customerTypeLabels).map(([v, l]) => (
                      <option key={v} value={v}>
                        {l}
                      </option>
                    ))}
                  </select>
                </Labeled>
              </div>
            </SectionCard>

            <SectionCard
              title="商品行 *"
              description="单价由人填 —— 05 价目未交付，界面不内置任何默认单价。行小计实时试算，提交后由 DB 独立复算。"
            >
              <div className="space-y-3">
                {lines.map((line, index) => (
                  <div key={line.skuId} className="grid gap-2 md:grid-cols-6">
                    <Labeled label="SKU 编码 *">
                      <Input
                        value={line.skuCode}
                        onChange={(e) =>
                          setLines(lines.map((l, i) => (i === index ? { ...l, skuCode: e.target.value } : l)))
                        }
                      />
                    </Labeled>
                    <Labeled label="SKU 名称 *">
                      <Input
                        value={line.skuName}
                        onChange={(e) =>
                          setLines(lines.map((l, i) => (i === index ? { ...l, skuName: e.target.value } : l)))
                        }
                      />
                    </Labeled>
                    <Labeled label="规格快照">
                      <Input
                        value={line.specSnapshot}
                        onChange={(e) =>
                          setLines(
                            lines.map((l, i) => (i === index ? { ...l, specSnapshot: e.target.value } : l)),
                          )
                        }
                      />
                    </Labeled>
                    <Labeled label="映射版本">
                      <Input
                        value={line.skuMappingVersion}
                        onChange={(e) =>
                          setLines(
                            lines.map((l, i) =>
                              i === index ? { ...l, skuMappingVersion: e.target.value } : l,
                            ),
                          )
                        }
                      />
                    </Labeled>
                    <Labeled label="数量 *">
                      <Input
                        value={line.quantity}
                        onChange={(e) =>
                          setLines(lines.map((l, i) => (i === index ? { ...l, quantity: e.target.value } : l)))
                        }
                      />
                    </Labeled>
                    <Labeled label="单价 *">
                      <Input
                        value={line.unitPrice}
                        onChange={(e) =>
                          setLines(lines.map((l, i) => (i === index ? { ...l, unitPrice: e.target.value } : l)))
                        }
                      />
                    </Labeled>
                    <Labeled label="行折扣（负数）">
                      <Input
                        value={line.discountAmount}
                        onChange={(e) =>
                          setLines(
                            lines.map((l, i) => (i === index ? { ...l, discountAmount: e.target.value } : l)),
                          )
                        }
                      />
                    </Labeled>
                    <Labeled label="行税">
                      <Input
                        value={line.taxAmount}
                        onChange={(e) =>
                          setLines(lines.map((l, i) => (i === index ? { ...l, taxAmount: e.target.value } : l)))
                        }
                      />
                    </Labeled>
                    <div className="text-copy-13 flex items-end pb-2 md:col-span-4">
                      行小计：
                      <span className="ml-1 font-medium">
                        {money(lineAmount({ unitPrice: Number(line.unitPrice || 0), quantity: Number(line.quantity || 0) }))}
                      </span>
                    </div>
                  </div>
                ))}
                <Button variant="outline" size="sm" onClick={() => setLines([...lines, emptyLine()])}>
                  <Plus className="mr-2 h-4 w-4" />
                  添加一行
                </Button>
              </div>
            </SectionCard>

            <SectionCard
              title="收货地址 *"
              description="收全量地址字段并冻结成快照：不回查客户档案的活地址。"
            >
              <div className="grid gap-3 md:grid-cols-3">
                <Labeled label="收件人 *">
                  <Input
                    value={addresses[0]?.recipientName ?? ''}
                    onChange={(e) =>
                      setAddresses(addresses.map((a, i) => (i === 0 ? { ...a, recipientName: e.target.value } : a)))
                    }
                  />
                </Labeled>
                <Labeled label="电话">
                  <Input
                    value={addresses[0]?.recipientPhone ?? ''}
                    onChange={(e) =>
                      setAddresses(addresses.map((a, i) => (i === 0 ? { ...a, recipientPhone: e.target.value } : a)))
                    }
                  />
                </Labeled>
                <Labeled label="国家码 *（两位大写）">
                  <Input
                    value={addresses[0]?.countryCode ?? ''}
                    onChange={(e) =>
                      setAddresses(addresses.map((a, i) => (i === 0 ? { ...a, countryCode: e.target.value } : a)))
                    }
                  />
                </Labeled>
                <Labeled label="详细地址 *">
                  <Input
                    value={addresses[0]?.addressLine1 ?? ''}
                    onChange={(e) =>
                      setAddresses(addresses.map((a, i) => (i === 0 ? { ...a, addressLine1: e.target.value } : a)))
                    }
                  />
                </Labeled>
                <Labeled label="省 / 州">
                  <Input
                    value={addresses[0]?.province ?? ''}
                    onChange={(e) =>
                      setAddresses(addresses.map((a, i) => (i === 0 ? { ...a, province: e.target.value } : a)))
                    }
                  />
                </Labeled>
                <Labeled label="邮编">
                  <Input
                    value={addresses[0]?.postcode ?? ''}
                    onChange={(e) =>
                      setAddresses(addresses.map((a, i) => (i === 0 ? { ...a, postcode: e.target.value } : a)))
                    }
                  />
                </Labeled>
              </div>
            </SectionCard>

            <SectionCard title="费用" description="折扣只填幅度，符号由界面按类型补上。">
              <div className="space-y-2">
                {charges.map((charge, index) => (
                  <div key={index} className="grid gap-2 md:grid-cols-4">
                    <Labeled label="费用类型">
                      <select
                        className="border-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                        value={charge.chargeType}
                        onChange={(e) =>
                          setCharges(charges.map((c, i) => (i === index ? { ...c, chargeType: e.target.value } : c)))
                        }
                      >
                        {Object.entries(chargeTypeLabels).map(([v, l]) => (
                          <option key={v} value={v}>
                            {l}
                          </option>
                        ))}
                      </select>
                    </Labeled>
                    <Labeled label="用途说明">
                      <Input
                        value={charge.label}
                        onChange={(e) =>
                          setCharges(charges.map((c, i) => (i === index ? { ...c, label: e.target.value } : c)))
                        }
                      />
                    </Labeled>
                    <Labeled label={`金额（${chargeSignHint(charge.chargeType)}）`}>
                      <Input
                        value={charge.amount}
                        onChange={(e) =>
                          setCharges(charges.map((c, i) => (i === index ? { ...c, amount: e.target.value } : c)))
                        }
                      />
                    </Labeled>
                    <Labeled label="层级">
                      <select
                        className="border-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                        value={charge.chargeLevel}
                        onChange={(e) =>
                          setCharges(charges.map((c, i) => (i === index ? { ...c, chargeLevel: e.target.value } : c)))
                        }
                      >
                        {Object.entries(chargeLevelLabels).map(([v, l]) => (
                          <option key={v} value={v}>
                            {l}
                          </option>
                        ))}
                      </select>
                    </Labeled>
                  </div>
                ))}
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setCharges([...charges, emptyCharge('OTHER')])}
                >
                  <Plus className="mr-2 h-4 w-4" />
                  添加一笔费用
                </Button>
              </div>
            </SectionCard>

            {/* 试算：与服务层、DB 触发器同一条公式。让人在下单**之前**看见总额。 */}
            <div className="bg-muted/40 rounded-md px-3 py-2 text-sm">
              <div className="text-copy-12 text-muted-foreground mb-1">订单总额试算（提交后由 DB 独立复算）</div>
              <div className="text-copy-12 text-muted-foreground grid gap-x-6 gap-y-0.5 md:grid-cols-3">
                <span>商品小计 {money(preview.subtotal)}</span>
                <span>折扣 −{money(preview.discount)}</span>
                <span>税额 {money(preview.tax)}</span>
                <span>运费 {money(preview.shipping)}</span>
                <span>其他 {money(preview.other)}</span>
                <span className="font-medium">
                  合计 {money(preview.grandTotal)} {currency}
                </span>
              </div>
            </div>

            {blockers.length > 0 && (
              <ul className="text-copy-12 text-muted-foreground space-y-1">
                {blockers.map((b) => (
                  <li key={b}>· {b}</li>
                ))}
              </ul>
            )}
          </div>

          <ModalFooter>
            <Button onClick={() => void submitDraft()} disabled={!canSave || submitting}>
              创建草稿
            </Button>
            <Button variant="outline" onClick={() => setCreating(false)}>
              取消
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}

// ================================================================ 小工具

function blockersOf(detail: SalesOrderDetail): string[] {
  return submitBlockers({
    lines: detail.lines,
    addresses: detail.addresses.map((a) => ({ addressRole: a.addressRole })),
    customer: detail.customer,
  });
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-copy-12 text-muted-foreground mb-0.5">{label}</dt>
      <dd className="break-words font-medium">{value}</dd>
    </div>
  );
}

function Labeled({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-copy-12 text-muted-foreground">{label}</span>
      {children}
    </label>
  );
}
