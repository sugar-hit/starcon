'use client';

import { useCallback, useEffect, useState } from 'react';
import { Boxes, HandCoins, PackageCheck, RefreshCw, ScrollText, Undo2 } from 'lucide-react';
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
  ConsignmentAgreementDetail,
  ConsignmentAgreementPage,
  ConsignmentAuditEntry,
  ConsignmentCapabilities,
  ConsignmentReturnPage,
  ConsignmentSalePage,
  ConsignmentSettlementPage,
  ConsignmentStockView,
} from '../../../../../packages/api-contracts/src/catalog';
import { useIdentity } from '../iam/identity-shell';
import {
  activateConsignmentAgreement,
  auditLabel,
  cancelConsignmentReturn,
  closeConsignmentAgreement,
  confirmConsignmentSettlement,
  createConsignmentReturn,
  createConsignmentSettlement,
  dispositionWarning,
  errorText,
  executeConsignmentReturn,
  fetchConsignmentAgreement,
  fetchConsignmentAgreements,
  fetchConsignmentAudit,
  fetchConsignmentCapabilities,
  fetchConsignmentReturns,
  fetchConsignmentSales,
  fetchConsignmentSettlements,
  fetchConsignmentStockView,
  fetchSettlementWindow,
  formatAmount,
  formatQty,
  formatRate,
  isAgreementActive,
  isSettlementLocked,
  payableBasisNote,
  recordConsignmentSale,
  soldRate,
  suspendConsignmentAgreement,
  unsettleable,
  zeroRemainingLines,
} from './consignment-model';

/**
 * 7.7.1.2 寄售与代销工作区。
 *
 * <p>蓝图：「管理寄售与代销业务：寄售协议（供应商货、我方销，结算按实际售出）、寄售可用量、
 * 销售触发结算数量、未售退货与期末清理。支持『寄售库存不计入我方采购入库成本，仅在售出时结转』。」
 *
 * <p>这是一个真实工作区：协议生命周期、寄售可用量、销售事实、结算触发单（→ 12 应付）、
 * 未售退货与期末清理都落到 PostgreSQL 16，四个动作闭环齐全。
 *
 * <p>界面上刻意反复说清三件事，否则使用者一定会误读：
 *
 * <ul>
 *   <li>**寄售量不是成本、不是应付**。指标卡把「未售出」和「已售未结算」分开列，
 *       避免有人把寄售量当成本看。
 *   <li>**未售出不产生应付**。空结算单是正常结果，界面如实说明而不是报错。
 *   <li>**转入采购是唯一例外**，且用警示色（`tone="warn"`）与退回供应商区分开。
 * </ul>
 */
export function ConsignmentWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [capabilities, setCapabilities] = useState<ConsignmentCapabilities | null>(null);
  const [agreements, setAgreements] = useState<ConsignmentAgreementPage | null>(null);
  const [detail, setDetail] = useState<ConsignmentAgreementDetail | null>(null);
  const [view, setView] = useState<ConsignmentStockView | null>(null);
  const [sales, setSales] = useState<ConsignmentSalePage | null>(null);
  const [settlements, setSettlements] = useState<ConsignmentSettlementPage | null>(null);
  const [returns, setReturns] = useState<ConsignmentReturnPage | null>(null);
  const [audit, setAudit] = useState<ConsignmentAuditEntry[]>([]);

  const [loading, setLoading] = useState(true);
  const [drillLoading, setDrillLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');

  // 记销售
  const [saleLineId, setSaleLineId] = useState('');
  const [saleQty, setSaleQty] = useState('');
  const [saleRef, setSaleRef] = useState('');

  // 出结算单
  const [periodStart, setPeriodStart] = useState('');
  const [periodEnd, setPeriodEnd] = useState('');

  // 退货/清理
  const [returnLineId, setReturnLineId] = useState('');
  const [returnQty, setReturnQty] = useState('');
  const [returnDisposition, setReturnDisposition] = useState('RETURN_TO_SUPPLIER');
  const [returnKind, setReturnKind] = useState('UNSOLD_RETURN');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, page] = await Promise.all([
        fetchConsignmentCapabilities(),
        fetchConsignmentAgreements({ status, keyword, page: 0, size: 20 }),
      ]);
      setCapabilities(caps);
      setAgreements(page);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [status, keyword]);

  useEffect(() => {
    if (!readable) return;
    void load();
  }, [readable, load]);

  /** 一次把一份协议的五处数据全拉回来：它们同源，缺一处界面就会自相矛盾。 */
  const openAgreement = useCallback(async (id: string) => {
    setError('');
    setNotice('');
    setDrillLoading(true);
    try {
      const [d, v, s, st, r, trail] = await Promise.all([
        fetchConsignmentAgreement(id),
        fetchConsignmentStockView(id),
        fetchConsignmentSales(id, { size: 50 }),
        fetchConsignmentSettlements({ agreementId: id, size: 20 }),
        fetchConsignmentReturns({ agreementId: id, size: 20 }),
        fetchConsignmentAudit(id, 100),
      ]);
      setDetail(d);
      setView(v);
      setSales(s);
      setSettlements(st);
      setReturns(r);
      setAudit(trail);
      setSaleLineId(d.lines[0]?.id ?? '');
      setReturnLineId(d.lines[0]?.id ?? '');
    } catch (e) {
      setError(errorText(e));
    } finally {
      setDrillLoading(false);
    }
  }, []);

  const afterMutation = useCallback(
    async (message: string) => {
      setNotice(message);
      const page = await fetchConsignmentAgreements({ status, keyword, page: 0, size: 20 });
      setAgreements(page);
      if (detail) await openAgreement(detail.agreement.id);
    },
    [detail, openAgreement, status, keyword],
  );

  const guard = useCallback(async (work: () => Promise<unknown>) => {
    setSaving(true);
    setError('');
    try {
      await work();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setSaving(false);
    }
  }, []);

  const submitSale = () =>
    void guard(async () => {
      if (!detail || !saleLineId) throw new Error('请先选择一条寄售明细行。');
      if (!saleQty || Number(saleQty) <= 0) throw new Error('售出数量必须大于 0。');
      if (!saleRef.trim()) throw new Error('请填写销售事实引用（订单行号），它保证重复推送不会重复计应付。');
      await recordConsignmentSale(detail.agreement.id, saleLineId, {
        soldQuantity: saleQty,
        saleRef: saleRef.trim(),
        occurredAt: new Date().toISOString(),
      });
      setSaleQty('');
      setSaleRef('');
      await afterMutation('已记录售出。应付要等出结算触发单并确认后才交给 12 应付。');
    });

  const submitSettlement = () =>
    void guard(async () => {
      if (!detail) return;
      if (!periodStart || !periodEnd) throw new Error('请先选择结算区间。');
      await createConsignmentSettlement(detail.agreement.id, { periodStart, periodEnd });
      await afterMutation('已出结算触发单。确认后应付才正式交 12 应付。');
    });

  const prefillWindow = () =>
    void guard(async () => {
      if (!detail) return;
      const w = await fetchSettlementWindow(detail.agreement.id);
      setPeriodStart(w.periodStart);
      setPeriodEnd(w.periodEnd);
      setNotice(`已按结算周期「${w.settlementCycleLabel}」填入 ${w.periodStart} 至 ${w.periodEnd}。`);
    });

  const submitReturn = () =>
    void guard(async () => {
      if (!detail) return;
      if (!returnLineId) throw new Error('请先选择要清理的寄售明细行。');
      if (!returnQty || Number(returnQty) <= 0) throw new Error('退货数量必须大于 0。');
      await createConsignmentReturn(detail.agreement.id, {
        kind: returnKind,
        disposition: returnDisposition,
        lines: [{ agreementLineId: returnLineId, quantity: returnQty }],
      });
      setReturnQty('');
      await afterMutation('已建立退货/清理单（草稿）。执行后才会真正扣减寄售剩余量。');
    });

  if (!readable) {
    return (
      <EmptyState
        title="没有寄售与代销的查看权限"
        description="需要 warehouse.inventory.read 才能查看寄售协议与结算。"
      />
    );
  }

  const agreement = detail?.agreement ?? null;
  const writableAgreement = agreement && writable && agreement.status !== 'CLOSED';

  return (
    <div className="space-y-4">
      <PageHeader
        title="寄售与代销"
        description="供应商货、我方销，按实际售出结算。寄售库存不计入我方采购入库成本，仅在售出时结转 —— 未售出不产生应付。"
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
        <Notice tone="info" title="已处理">
          {notice}
        </Notice>
      )}

      {/* ---------------- 能力口径：先说清规则，再谈数据 ---------------- */}
      {capabilities && (
        <SectionCard title="财务口径（不可协商的三条）">
          <div className="space-y-2 text-sm">
            <p className="font-medium">{capabilities.payableBasis}</p>
            <p className="text-muted-foreground">{capabilities.ownershipNote}</p>
            <p className="text-muted-foreground">
              采购入库成本口径：<span className="font-medium">不可算</span> —— {capabilities.costBasisNote}
            </p>
            <p className="text-muted-foreground">{capabilities.saleFactNote}</p>
            <p className="text-muted-foreground">{capabilities.payableHandoffNote}</p>
            <p className="text-muted-foreground">{capabilities.returnHandoffNote}</p>
          </div>
        </SectionCard>
      )}

      {/* ---------------- 协议列表 ---------------- */}
      <SectionCard title="寄售协议">
        <div className="mb-3 flex flex-wrap items-end gap-2">
          <FormField id="filter-status" label="状态">
            <select
              className="border-control-border bg-background h-9 rounded-md border px-2 text-sm"
              value={status}
              onChange={(e) => setStatus(e.target.value)}
            >
              <option value="">全部</option>
              {capabilities?.agreementStatuses.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="filter-keyword" label="搜索">
            <Input
              className="w-56"
              placeholder="单号 / 供应商 / 备注"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
            />
          </FormField>
        </div>

        {loading ? (
          <p className="text-muted-foreground py-6 text-sm">正在加载寄售协议…</p>
        ) : !agreements || agreements.agreements.length === 0 ? (
          <EmptyState
            icon={<Boxes className="h-8 w-8" />}
            title="还没有寄售协议"
            description="寄售协议由供应商寄货、我方代销，按实际售出结算。建立后即可记销售并出结算触发单。"
          />
        ) : (
          <div className="border-control-border border-t">
            {agreements.agreements.map((a) => (
              <button
                key={a.id}
                type="button"
                onClick={() => void openAgreement(a.id)}
                className="border-control-border hover:bg-muted/40 flex w-full flex-wrap items-center gap-3 border-b px-3 py-2 text-left text-sm"
              >
                <span className="font-medium">{a.agreementCode}</span>
                <span className="text-muted-foreground">{a.supplierName}</span>
                <span className="text-muted-foreground">{a.modeLabel}</span>
                <span className="text-muted-foreground">{a.statusLabel}</span>
                <span className="ml-auto tabular-nums">
                  寄售 {formatQty(a.totalConsigned)} / 已售 {formatQty(a.totalSold)} / 剩余{' '}
                  {formatQty(a.totalRemaining)}
                </span>
              </button>
            ))}
          </div>
        )}
      </SectionCard>

      {/* ---------------- 选中协议的详情 ---------------- */}
      {drillLoading && <p className="text-muted-foreground py-4 text-sm">正在加载协议明细…</p>}

      {agreement && view && (
        <>
          <div className="grid gap-4 md:grid-cols-4">
            <StatCard
              label="寄售量"
              value={formatQty(view.totalConsigned)}
              polarity="neutral"
              comparisonLabel="供应商货主的货，不是自有资产"
            />
            <StatCard
              label="已售出"
              value={formatQty(view.totalSold)}
              polarity="positive"
              comparisonLabel="应付只由这一项产生"
            />
            <StatCard
              label="未售出"
              value={formatQty(view.totalRemaining)}
              polarity="neutral"
              comparisonLabel="未售出不产生应付"
            />
            <StatCard
              label="已售未结算"
              value={formatQty(view.unsettledQuantity)}
              polarity={Number(view.unsettledQuantity) > 0 ? 'negative' : 'positive'}
              comparisonLabel={`预计应付 ${formatAmount(view.unsettledAmount, view.currency)}`}
            />
          </div>

          <SectionCard
            title={`寄售可用量 · ${agreement.agreementCode}`}
            description={`${agreement.supplierName} · ${agreement.modeLabel} · ${agreement.settlementCycleLabel} · 期末${agreement.endTreatmentLabel}`}
            extra={
              writable && (
                <div className="flex gap-2">
                  {agreement.status === 'DRAFT' && (
                    <Button
                      size="sm"
                      disabled={saving}
                      onClick={() =>
                        void guard(async () => {
                          await activateConsignmentAgreement(agreement.id);
                          await afterMutation('协议已启用，可以记销售与出结算单。');
                        })
                      }
                    >
                      启用协议
                    </Button>
                  )}
                  {agreement.status === 'ACTIVE' && (
                    <>
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={saving}
                        onClick={() =>
                          void guard(async () => {
                            await suspendConsignmentAgreement(agreement.id);
                            await afterMutation('协议已暂停，不能再记销售。');
                          })
                        }
                      >
                        暂停
                      </Button>
                      <Button
                        size="sm"
                        disabled={saving}
                        onClick={() =>
                          void guard(async () => {
                            await closeConsignmentAgreement(agreement.id);
                            await afterMutation('协议已关闭，全部明细行被数据库冻结。');
                          })
                        }
                      >
                        关闭协议
                      </Button>
                    </>
                  )}
                </div>
              )
            }
          >
            <div className="space-y-3 text-sm">
              <p>{payableBasisNote(view)}</p>
              <p className="text-muted-foreground">
                货主口径：{view.ownershipNote} 自有货值：
                <span className="font-medium">{formatAmount(view.ownedAssetValue, view.currency)}</span>
                （该口径不适用于寄售货，不是 0）。
              </p>
              {zeroRemainingLines(view) > 0 && (
                <Notice tone="warning" title="有明细行已全部售出">
                  {zeroRemainingLines(view)} 条明细行的剩余寄售量为 0，已不能再记销售。
                </Notice>
              )}
              {!writableAgreement && (
                <p className="text-muted-foreground text-xs">
                  {agreement.status === 'CLOSED'
                    ? '协议已关闭：明细行被数据库冻结，数量不能再改动。'
                    : '当前身份没有写权限，以上为只读视图。'}
                </p>
              )}
            </div>

            {/* 寄售明细 */}
            <div className="mt-4 border-control-border border-t">
              <div className="grid grid-cols-6 gap-2 border-b px-2 py-1 text-xs font-medium">
                <span>SKU</span>
                <span>仓库</span>
                <span className="text-right">寄售量</span>
                <span className="text-right">已售</span>
                <span className="text-right">剩余</span>
                <span className="text-right">结算价</span>
              </div>
              {detail?.lines.map((l) => (
                <div key={l.id} className="grid grid-cols-6 gap-2 border-b px-2 py-1.5 text-sm">
                  <span>
                    {l.skuCode}
                    <span className="text-muted-foreground block text-xs">{l.skuName}</span>
                  </span>
                  <span className="text-muted-foreground">{l.warehouseCode}</span>
                  <span className="text-right tabular-nums">{formatQty(l.consignedQuantity)}</span>
                  <span className="text-right tabular-nums">{formatQty(l.soldQuantity)}</span>
                  <span className="text-right tabular-nums">
                    {formatQty(l.remainingQuantity)}
                    <span className="text-muted-foreground block text-xs">
                      售罄 {formatRate(soldRate(l))}
                    </span>
                  </span>
                  <span className="text-right tabular-nums">
                    {formatAmount(l.settlementPrice, agreement.currency)}
                  </span>
                </div>
              ))}
              {detail?.lines.length === 0 && (
                <p className="text-muted-foreground py-4 text-sm">这份协议还没有明细行。</p>
              )}
            </div>
          </SectionCard>

          {/* ---------------- 记销售事实 ---------------- */}
          <SectionCard title="记录销售事实（结算数量的唯一来源）">
            {!isAgreementActive(agreement) ? (
              <p className="text-muted-foreground text-sm">
                只有生效中的协议能记销售（当前：{agreement.statusLabel}）。
              </p>
            ) : (
              <div className="flex flex-wrap items-end gap-2">
                <FormField id="sale-line" label="明细行">
                  <select
                    className="border-control-border bg-background h-9 w-56 rounded-md border px-2 text-sm"
                    value={saleLineId}
                    onChange={(e) => setSaleLineId(e.target.value)}
                  >
                    {detail?.lines.map((l) => (
                      <option key={l.id} value={l.id}>
                        {l.skuCode}（剩余 {formatQty(l.remainingQuantity)}）
                      </option>
                    ))}
                  </select>
                </FormField>
                <FormField id="sale-qty" label="售出数量">
                  <Input
                    className="w-32"
                    value={saleQty}
                    onChange={(e) => setSaleQty(e.target.value)}
                    placeholder="0"
                  />
                </FormField>
                <FormField id="sale-ref" label="订单行引用">
                  <Input
                    className="w-40"
                    value={saleRef}
                    onChange={(e) => setSaleRef(e.target.value)}
                    placeholder="SO-…"
                  />
                </FormField>
                <Button size="sm" disabled={saving || !writable} onClick={submitSale}>
                  记一笔售出
                </Button>
              </div>
            )}
            <p className="text-muted-foreground mt-2 text-xs">
              售出量不得超过该行剩余；同一订单行引用重复提交会被数据库唯一约束挡下，不会重复计应付。
            </p>

            {sales && sales.sales.length > 0 && (
              <div className="mt-3 space-y-1">
                {sales.sales.map((s) => (
                  <div
                    key={s.id}
                    className="border-control-border flex flex-wrap items-center gap-2 border-b py-1.5 text-sm"
                  >
                    <span className="text-muted-foreground">{s.skuCode}</span>
                    <span className="tabular-nums">{formatQty(s.soldQuantity)} 件</span>
                    <span className="text-muted-foreground text-xs">{s.saleRef}</span>
                    {s.unsettleable ? (
                      <span className="text-muted-foreground ml-auto text-xs">待结算</span>
                    ) : (
                      <span className="text-muted-foreground ml-auto text-xs">
                        已进 {s.settlementCode}
                      </span>
                    )}
                  </div>
                ))}
              </div>
            )}
            {sales && sales.sales.length === 0 && (
              <p className="text-muted-foreground mt-3 text-sm">
                还没有销售事实。寄售货未售出时不产生应付，这是正常状态。
              </p>
            )}
          </SectionCard>

          {/* ---------------- 结算触发单 → 12 应付 ---------------- */}
          <SectionCard title="结算触发单（→ 12 应付）">
            <div className="flex flex-wrap items-end gap-2">
              <FormField id="period-start" label="结算起始日">
                <Input
                  type="date"
                  value={periodStart}
                  onChange={(e) => setPeriodStart(e.target.value)}
                />
              </FormField>
              <FormField id="period-end" label="结算结束日">
                <Input type="date" value={periodEnd} onChange={(e) => setPeriodEnd(e.target.value)} />
              </FormField>
              <Button variant="outline" size="sm" disabled={saving} onClick={prefillWindow}>
                按结算周期填入
              </Button>
              <Button
                size="sm"
                disabled={saving || !writable || !isAgreementActive(agreement)}
                onClick={submitSettlement}
              >
                出结算触发单
              </Button>
            </div>
            <p className="text-muted-foreground mt-2 text-xs">
              结算单只收「已售出且尚未结算」的销售事实。区间内没卖出东西时不会产生应付单据 ——
              这是寄售该有的结果。
            </p>

            {settlements && settlements.settlements.length > 0 ? (
              <div className="mt-3 space-y-2">
                {settlements.settlements.map((s) => (
                  <div
                    key={s.id}
                    className="border-control-border flex flex-wrap items-center gap-3 border-b py-2 text-sm"
                  >
                    <HandCoins className="text-muted-foreground h-4 w-4" />
                    <span className="font-medium">{s.settlementCode}</span>
                    <span className="text-muted-foreground text-xs">
                      {s.periodStart} 至 {s.periodEnd}
                    </span>
                    <span className="tabular-nums">{formatQty(s.totalQuantity)} 件</span>
                    <span className="font-medium tabular-nums">
                      {formatAmount(s.totalAmount, s.currency)}
                    </span>
                    <span className="text-muted-foreground text-xs">{s.statusLabel}</span>
                    {s.status === 'OPEN' && writable && (
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={saving}
                        onClick={() =>
                          void guard(async () => {
                            await confirmConsignmentSettlement(s.id);
                            await afterMutation('结算触发单已确认，应付正式交 12 应付，单据已冻结。');
                          })
                        }
                      >
                        确认交 12 应付
                      </Button>
                    )}
                    {isSettlementLocked(s) && (
                      <span className="text-muted-foreground ml-auto text-xs">
                        {s.referenceId ? `交接键 ${s.referenceId}` : '已冻结，不可再改'}
                      </span>
                    )}
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-muted-foreground mt-3 text-sm">
                还没有结算触发单。本期已售出 {formatQty(view.totalSold)} 件；
                {Number(view.unsettledQuantity) > 0
                  ? `其中 ${formatQty(view.unsettledQuantity)} 件待结算。`
                  : '全部已结算或尚未卖出 —— 未售出不产生应付。'}
              </p>
            )}
          </SectionCard>

          {/* ---------------- 未售退货与期末清理 ---------------- */}
          <SectionCard title="未售退货与期末清理">
            {!writableAgreement ? (
              <p className="text-muted-foreground text-sm">协议已关闭或无写权限，不能再建清理单。</p>
            ) : (
              <div className="flex flex-wrap items-end gap-2">
                <FormField id="return-kind" label="性质">
                  <select
                    className="border-control-border bg-background h-9 rounded-md border px-2 text-sm"
                    value={returnKind}
                    onChange={(e) => setReturnKind(e.target.value)}
                  >
                    {capabilities?.returnKinds.map((k) => (
                      <option key={k.value} value={k.value}>
                        {k.label}
                      </option>
                    ))}
                  </select>
                </FormField>
                <FormField id="return-disposition" label="处理方式">
                  <select
                    className="border-control-border bg-background h-9 w-48 rounded-md border px-2 text-sm"
                    value={returnDisposition}
                    onChange={(e) => setReturnDisposition(e.target.value)}
                  >
                    {capabilities?.endTreatments.map((t) => (
                      <option key={t.value} value={t.value}>
                        {t.label}
                      </option>
                    ))}
                  </select>
                </FormField>
                <FormField id="line-select" label="明细行">
                  <select
                    className="border-control-border bg-background h-9 w-56 rounded-md border px-2 text-sm"
                    value={returnLineId}
                    onChange={(e) => setReturnLineId(e.target.value)}
                  >
                    {detail?.lines.map((l) => (
                      <option key={l.id} value={l.id}>
                        {l.skuCode}（剩余 {formatQty(l.remainingQuantity)}）
                      </option>
                    ))}
                  </select>
                </FormField>
                <FormField id="return-qty" label="数量">
                  <Input
                    className="w-28"
                    value={returnQty}
                    onChange={(e) => setReturnQty(e.target.value)}
                    placeholder="0"
                  />
                </FormField>
                <Button size="sm" disabled={saving} onClick={submitReturn}>
                  建清理单
                </Button>
              </div>
            )}

            {returns && returns.returns.length > 0 && (
              <div className="mt-3 space-y-2">
                {returns.returns.map((r) => {
                  const warn = dispositionWarning(
                    r.disposition,
                    r.totalPayableAmount,
                    agreement.currency,
                  );
                  return (
                    <div
                      key={r.id}
                      className="border-control-border flex flex-wrap items-center gap-3 border-b py-2 text-sm"
                    >
                      {r.disposition === 'CONVERT_TO_PURCHASE' ? (
                        <PackageCheck className="h-4 w-4 text-amber-600" />
                      ) : (
                        <Undo2 className="text-muted-foreground h-4 w-4" />
                      )}
                      <span className="font-medium">{r.returnCode}</span>
                      <span className="text-muted-foreground text-xs">{r.kindLabel}</span>
                      <span className="tabular-nums">{formatQty(r.totalQuantity)} 件</span>
                      <span
                        className={
                          warn.tone === 'warn'
                            ? 'font-medium text-amber-700 dark:text-amber-400'
                            : 'text-muted-foreground'
                        }
                      >
                        {warn.text}
                      </span>
                      <span className="text-muted-foreground text-xs">{r.statusLabel}</span>
                      {r.status === 'DRAFT' && writable && (
                        <span className="flex gap-2">
                          <Button
                            size="sm"
                            disabled={saving}
                            onClick={() =>
                              void guard(async () => {
                                await executeConsignmentReturn(r.id);
                                await afterMutation('清理单已执行，寄售剩余量已扣减。');
                              })
                            }
                          >
                            执行
                          </Button>
                          <Button
                            variant="outline"
                            size="sm"
                            disabled={saving}
                            onClick={() =>
                              void guard(async () => {
                                await cancelConsignmentReturn(r.id);
                                await afterMutation('清理单已作废（草稿未动数量）。');
                              })
                            }
                          >
                            作废
                          </Button>
                        </span>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
            {returns && returns.returns.length === 0 && (
              <p className="text-muted-foreground mt-3 text-sm">还没有退货或期末清理单。</p>
            )}
          </SectionCard>

          {/* ---------------- 审计 ---------------- */}
          <SectionCard title="审计时间线（append-only）">
            {audit.length === 0 ? (
              <p className="text-muted-foreground text-sm">暂无记录。</p>
            ) : (
              <div className="space-y-1">
                {audit.map((e) => (
                  <div key={e.id} className="border-control-border flex flex-wrap items-center gap-2 border-b py-1.5 text-sm">
                    <ScrollText className="text-muted-foreground h-3.5 w-3.5" />
                    <span className="font-medium">{auditLabel(e.eventType)}</span>
                    <span className="text-muted-foreground text-xs">{e.detail}</span>
                    <span className="text-muted-foreground ml-auto text-xs">
                      {new Date(e.occurredAt).toLocaleString('zh-CN')}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>
        </>
      )}
    </div>
  );
}
