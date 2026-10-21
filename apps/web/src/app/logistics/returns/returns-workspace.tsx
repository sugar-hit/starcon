'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { ClipboardList, PackageCheck, RefreshCw, Scale, Truck, Undo2 } from 'lucide-react';
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
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@starcon/ui';
import {
  blockingReasons,
  buildDecisionPayload,
  cancelDispositionOrder,
  costTypeLabel,
  decideDisposition,
  dispositionDistribution,
  dispositionMethodLabel,
  dispositionStateLabel,
  emptyDecisionForm,
  errorText,
  fetchCapabilities,
  fetchDecisions,
  fetchEligibility,
  fetchEvents,
  fetchOrder,
  fetchOrders,
  fetchOverview,
  fetchRegistrations,
  fetchRestrictions,
  issueDispositionOrder,
  moneyText,
  pendingDecisionCount,
  quantityText,
  recordOrderCost,
  registerReceiving,
  restrictionBasisLabel,
  restrictionSubjectLabel,
  revokeRestriction,
  saveRestriction,
  warehouseTypeLabel,
  type CostDraft,
  type DecisionForm,
  type DispositionMethod,
  type ReturnDispositionCapabilities,
  type ReturnDispositionDecisionRow,
  type ReturnDispositionEligibility,
  type ReturnDispositionEventPage,
  type ReturnDispositionOrderDetail,
  type ReturnDispositionOrderRow,
  type ReturnDispositionOverview,
  type ReturnReceivingPage,
  type ReturnReceivingRow,
  type ReturnResaleRestrictionPage,
  type ReturnResaleRestrictionRow,
} from './returns-model';

/**
 * 10.7.1.1 退货接收与处置决策。
 *
 * <p>蓝图：「管理买家退回商品的接收与处置决策：本地仓/海外仓/平台仓接收登记，判定
 * <b>转销售（可再售）/退运回国/弃置销毁</b>，记录处置成本（退货运费、销毁费、平台处理费）
 * 与残值。」
 *
 * <p>🔴 界面上唯一不可让步的一条：处置方式**必须显式选**。
 *
 * <ul>
 *   <li>处置方式的下拉框第一项是「—— 请显式选择处置方式 ——」（空值），
 *       <b>不是</b>「转销售」；页面从不预选、也不高亮任何一项。</li>
 *   <li>「能不能转销售」由服务端判（质检结论 + 再销售限制），页面只把
 *       {@code blockingReasons} 原样显示出来；服务端还没给结论时**一律不许选**。</li>
 *   <li>拿不准的批次停在「待人工决策」，不会被悄悄算成转销售。</li>
 * </ul>
 *
 * <p>本模块**不**做的事（能力声明里写清楚了）：10.7.1.2 退运回国与 10.7.1.3 弃置销毁
 * 尚未交付，本页只出「这批货决定退运 / 销毁」这个**决策**；库存状态变更指令只是
 * 产出的一条事实（消费方 7.4.1.1 未交付，恒为 PENDING）；处置成本落在本模块自有事实里
 * （12 财务服务尚不存在），页面**不做任何汇率换算**，成本与残值按币种分行。
 */
export function ReturnsWorkspace() {
  const [capabilities, setCapabilities] = useState<ReturnDispositionCapabilities | null>(null);
  const [overview, setOverview] = useState<ReturnDispositionOverview | null>(null);
  const [registrations, setRegistrations] = useState<ReturnReceivingPage | null>(null);
  const [decisions, setDecisions] = useState<ReturnDispositionDecisionRow[]>([]);
  const [orders, setOrders] = useState<ReturnDispositionOrderRow[]>([]);
  const [restrictions, setRestrictions] = useState<ReturnResaleRestrictionRow[]>([]);
  const [events, setEvents] = useState<ReturnDispositionEventPage | null>(null);

  const [keyword, setKeyword] = useState('');
  const [warehouseType, setWarehouseType] = useState('');
  const [dispositionState, setDispositionState] = useState('');
  const [decisionMethodFilter, setDecisionMethodFilter] = useState('');
  const [orderMethodFilter, setOrderMethodFilter] = useState('');

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  // 决策弹窗
  const [decisionTarget, setDecisionTarget] = useState<ReturnReceivingRow | null>(null);
  const [decisionForm, setDecisionForm] = useState<DecisionForm>(emptyDecisionForm());
  const [eligibility, setEligibility] = useState<ReturnDispositionEligibility | null>(null);
  const [eligibilityLoading, setEligibilityLoading] = useState(false);
  const [decisionError, setDecisionError] = useState('');

  // 登记接收弹窗
  const [registerOpen, setRegisterOpen] = useState(false);
  const [registerForm, setRegisterForm] = useState({
    receiptId: '',
    receiptLineId: '',
    warehouseType: 'LOCAL',
    warehouseRef: '',
    warehouseCountry: '',
    receivedQty: '',
    brandCode: '',
    platformCode: '',
    note: '',
  });
  const [registerError, setRegisterError] = useState('');

  // 下发处置单弹窗
  const [issueTarget, setIssueTarget] = useState<ReturnDispositionDecisionRow | null>(null);
  const [issueForm, setIssueForm] = useState({
    costCurrency: 'USD',
    salvageCurrency: 'USD',
    salvageAmount: '0',
    note: '',
  });
  const [issueCosts, setIssueCosts] = useState<CostDraft[]>([]);
  const [issueError, setIssueError] = useState('');

  // 追加成本弹窗
  const [costTarget, setCostTarget] = useState<ReturnDispositionOrderRow | null>(null);
  const [costForm, setCostForm] = useState({ costType: 'RETURN_FREIGHT', amount: '', vendorRef: '', note: '' });
  const [costError, setCostError] = useState('');

  // 处置单详情
  const [detail, setDetail] = useState<ReturnDispositionOrderDetail | null>(null);

  // 限制弹窗
  const [restrictionOpen, setRestrictionOpen] = useState(false);
  const [restrictionForm, setRestrictionForm] = useState({
    subjectType: 'BRAND',
    subjectRef: '',
    restrictionBasis: 'BRAND_REQUIREMENT',
    reason: '',
  });
  const [restrictionError, setRestrictionError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, ov, regs, decs, ords, restr, evts] = await Promise.all([
        fetchCapabilities(),
        fetchOverview(),
        fetchRegistrations({ keyword, warehouseType, dispositionState, size: 50 }),
        fetchDecisions({ dispositionMethod: decisionMethodFilter, size: 50 }),
        fetchOrders({ dispositionMethod: orderMethodFilter, size: 50 }),
        fetchRestrictions({ size: 50 }),
        fetchEvents({ size: 50 }),
      ]);
      setCapabilities(caps);
      setOverview(ov);
      setRegistrations(regs);
      setDecisions(decs.rows);
      setOrders(ords.rows);
      setRestrictions(restr.rows);
      setEvents(evts);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [keyword, warehouseType, dispositionState, decisionMethodFilter, orderMethodFilter]);

  useEffect(() => {
    void load();
  }, [load]);

  // ---- 决策：先拿服务端的可行性结论，再让人显式选 ----
  const openDecision = useCallback(async (row: ReturnReceivingRow) => {
    setDecisionTarget(row);
    setDecisionForm(emptyDecisionForm(row.id));
    setDecisionError('');
    setEligibility(null);
    setEligibilityLoading(true);
    try {
      setEligibility(await fetchEligibility(row.id));
    } catch (e) {
      setDecisionError(errorText(e));
    } finally {
      setEligibilityLoading(false);
    }
  }, []);

  async function submitDecision() {
    const payload = buildDecisionPayload(decisionForm);
    if ('error' in payload) {
      setDecisionError(payload.error);
      return;
    }
    setBusy(true);
    setDecisionError('');
    try {
      await decideDisposition(payload);
      setDecisionTarget(null);
      await load();
    } catch (e) {
      setDecisionError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitRegister() {
    setBusy(true);
    setRegisterError('');
    try {
      await registerReceiving({
        receiptId: registerForm.receiptId.trim(),
        receiptLineId: registerForm.receiptLineId.trim(),
        warehouseType: registerForm.warehouseType,
        warehouseRef: registerForm.warehouseRef.trim(),
        warehouseCountry: registerForm.warehouseCountry.trim() || undefined,
        receivedQty: Number(registerForm.receivedQty),
        brandCode: registerForm.brandCode.trim() || undefined,
        platformCode: registerForm.platformCode.trim() || undefined,
        note: registerForm.note.trim() || undefined,
      });
      setRegisterOpen(false);
      setRegisterForm({
        receiptId: '',
        receiptLineId: '',
        warehouseType: 'LOCAL',
        warehouseRef: '',
        warehouseCountry: '',
        receivedQty: '',
        brandCode: '',
        platformCode: '',
        note: '',
      });
      await load();
    } catch (e) {
      setRegisterError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitIssue() {
    if (!issueTarget) return;
    const amount = Number(issueForm.salvageAmount);
    if (!issueForm.costCurrency.trim() || !issueForm.salvageCurrency.trim()) {
      setIssueError('成本币种与残值币种都必须给出');
      return;
    }
    if (!Number.isFinite(amount) || amount < 0) {
      setIssueError('残值不能小于 0');
      return;
    }
    setBusy(true);
    setIssueError('');
    try {
      await issueDispositionOrder({
        decisionId: issueTarget.id,
        costCurrency: issueForm.costCurrency.trim().toUpperCase(),
        salvageCurrency: issueForm.salvageCurrency.trim().toUpperCase(),
        salvageAmount: amount,
        note: issueForm.note.trim() || undefined,
        costs: issueCosts.length ? issueCosts : undefined,
      });
      setIssueTarget(null);
      setIssueCosts([]);
      await load();
    } catch (e) {
      setIssueError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitCost() {
    if (!costTarget) return;
    const amount = Number(costForm.amount);
    if (!Number.isFinite(amount) || amount < 0) {
      setCostError('金额不能小于 0');
      return;
    }
    setBusy(true);
    setCostError('');
    try {
      await recordOrderCost(costTarget.id, {
        costType: costForm.costType,
        amount,
        currency: costTarget.costCurrency,
        vendorRef: costForm.vendorRef.trim() || undefined,
        note: costForm.note.trim() || undefined,
      });
      setCostTarget(null);
      setCostForm({ costType: 'RETURN_FREIGHT', amount: '', vendorRef: '', note: '' });
      await load();
    } catch (e) {
      setCostError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function cancelOrder(order: ReturnDispositionOrderRow) {
    const reason = window.prompt('撤销理由（必填）');
    if (!reason || !reason.trim()) return;
    setBusy(true);
    setError('');
    try {
      await cancelDispositionOrder(order.id, reason.trim(), order.version);
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function openOrderDetail(order: ReturnDispositionOrderRow) {
    setDetail(null);
    setBusy(true);
    setError('');
    try {
      setDetail(await fetchOrder(order.id));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitRestriction() {
    setBusy(true);
    setRestrictionError('');
    try {
      await saveRestriction({
        subjectType: restrictionForm.subjectType,
        subjectRef: restrictionForm.subjectRef.trim().toUpperCase(),
        restrictionBasis: restrictionForm.restrictionBasis,
        reason: restrictionForm.reason.trim(),
      });
      setRestrictionOpen(false);
      setRestrictionForm({
        subjectType: 'BRAND',
        subjectRef: '',
        restrictionBasis: 'BRAND_REQUIREMENT',
        reason: '',
      });
      await load();
    } catch (e) {
      setRestrictionError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function doRevokeRestriction(row: ReturnResaleRestrictionRow) {
    const reason = window.prompt('撤销理由（必填）');
    if (!reason || !reason.trim()) return;
    setBusy(true);
    setError('');
    try {
      await revokeRestriction(row.id, reason.trim());
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  const distribution = useMemo(() => dispositionDistribution(overview), [overview]);
  const pending = pendingDecisionCount(overview);

  return (
    <>
      <PageHeader
        title="退货接收与处置决策"
        description="本地仓 / 海外仓 / 平台仓接收登记，判定转销售 / 退运回国 / 弃置销毁，记录处置成本与残值。"
      />

      {error ? (
        <Notice tone="danger" title="操作未完成">
          {error}
        </Notice>
      ) : null}

      {capabilities ? (
        <Notice tone="warning" title="处置方式必须显式给出">
          {capabilities.decisionNote}
        </Notice>
      ) : null}

      <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="接收登记"
          value={String(overview?.registrationCount ?? 0)}
          comparisonLabel="本租户登记过的退货接收"
        />
        <StatCard
          label="待人工决策"
          value={String(pending)}
          comparisonLabel="还没显式判去向的批次（不是「转销售」）"
        />
        <StatCard
          label="已下发处置单"
          value={String(overview?.issuedOrderCount ?? 0)}
          comparisonLabel={`已撤销 ${overview?.cancelledOrderCount ?? 0} 张`}
        />
        <StatCard
          label="命中再销售限制"
          value={String(overview?.restrictedRegistrationCount ?? 0)}
          comparisonLabel="品牌方 / 平台政策要求不得再销售的批次"
        />
      </div>

      <div className="mt-4 grid gap-4 sm:grid-cols-3">
        {distribution.map((d) => (
          <StatCard key={d.method} label={d.label} value={String(d.count)} comparisonLabel="按未被取代的决策统计" />
        ))}
      </div>

      <Tabs defaultValue="registrations" className="mt-6">
        <TabsList>
          <TabsTrigger value="registrations">接收登记</TabsTrigger>
          <TabsTrigger value="decisions">处置决策</TabsTrigger>
          <TabsTrigger value="orders">处置单</TabsTrigger>
          <TabsTrigger value="restrictions">再销售限制</TabsTrigger>
          <TabsTrigger value="events">留痕</TabsTrigger>
        </TabsList>

        {/* ---------------- 接收登记 ---------------- */}
        <TabsContent value="registrations">
          <SectionCard
            title="接收登记"
            description="货到了哪个仓（本地仓 / 海外仓 / 平台仓）、由谁在什么时候登记的。入库分录与待检分录是上游 11.2.2.1 的事实。"
            extra={
              <div className="flex gap-2">
                <Button size="sm" variant="ghost" onClick={() => void load()} disabled={busy}>
                  <RefreshCw className="mr-1.5 h-4 w-4" />
                  刷新
                </Button>
                <Button size="sm" onClick={() => setRegisterOpen(true)} disabled={busy}>
                  登记接收
                </Button>
              </div>
            }
          >
            <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
              <input
                className="rounded border px-2 py-1"
                placeholder="SKU / 批次 / 登记单号"
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
              />
              <select
                className="rounded border px-2 py-1"
                value={warehouseType}
                onChange={(e) => setWarehouseType(e.target.value)}
              >
                <option value="">全部仓型</option>
                <option value="LOCAL">本地仓</option>
                <option value="OVERSEAS">海外仓</option>
                <option value="PLATFORM">平台仓</option>
              </select>
              <select
                className="rounded border px-2 py-1"
                value={dispositionState}
                onChange={(e) => setDispositionState(e.target.value)}
              >
                <option value="">全部处置状态</option>
                <option value="PENDING_DECISION">待人工决策</option>
                <option value="DECIDED">已决策待下发</option>
                <option value="ORDERED">已下发处置单</option>
              </select>
              <span className="text-muted-foreground">共 {registrations?.total ?? 0} 条</span>
            </div>

            {loading ? (
              <p className="text-muted-foreground text-sm">正在加载接收登记…</p>
            ) : !registrations || registrations.rows.length === 0 ? (
              <EmptyState
                icon={<PackageCheck className="h-6 w-6" />}
                title="还没有接收登记"
                description="上游退货实收单入库（POSTED）后，在 11.2 登记这里做一次接收登记。"
              />
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="py-2">登记单号 / SKU</th>
                    <th className="py-2">仓</th>
                    <th className="py-2">数量</th>
                    <th className="py-2">处置状态</th>
                    <th className="py-2">去向</th>
                    <th className="py-2">限制</th>
                    <th className="py-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {registrations.rows.map((row) => (
                    <tr key={row.id} className="border-b last:border-0">
                      <td className="py-2">
                        <div className="font-mono text-xs">{row.registrationNo}</div>
                        <div>{row.skuCode}</div>
                        <div className="text-muted-foreground text-xs">{row.skuName}</div>
                      </td>
                      <td className="py-2">
                        <Badge tone="info">{warehouseTypeLabel(row.warehouseType)}</Badge>
                        <div className="text-muted-foreground text-xs">{row.warehouseRef}</div>
                      </td>
                      <td className="py-2">{quantityText(row.receivedQty)}</td>
                      <td className="py-2">
                        <Badge tone={row.dispositionState === 'PENDING_DECISION' ? 'warning' : 'neutral'}>
                          {dispositionStateLabel(row.dispositionState)}
                        </Badge>
                      </td>
                      <td className="py-2">
                        {row.dispositionMethod ? (
                          dispositionMethodLabel(row.dispositionMethod)
                        ) : (
                          <span className="text-muted-foreground">—</span>
                        )}
                      </td>
                      <td className="py-2">
                        {row.activeRestrictionCount > 0 ? (
                          <Badge tone="danger">命中 {row.activeRestrictionCount} 条限制</Badge>
                        ) : (
                          <span className="text-muted-foreground">—</span>
                        )}
                      </td>
                      <td className="py-2">
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => void openDecision(row)}
                          disabled={busy || row.dispositionState === 'ORDERED'}
                        >
                          判定去向
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>
        </TabsContent>

        {/* ---------------- 处置决策 ---------------- */}
        <TabsContent value="decisions">
          <SectionCard
            title="处置决策"
            description="去向 + 数量 + 依据 + 判定人 + 判定时刻，外加判定当时的上游质检结论与政策判定快照。被消费或被取代的决策即冻结。"
            extra={
              <Button size="sm" variant="ghost" onClick={() => void load()} disabled={busy}>
                <RefreshCw className="mr-1.5 h-4 w-4" />
                刷新
              </Button>
            }
          >
            <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
              <select
                className="rounded border px-2 py-1"
                value={decisionMethodFilter}
                onChange={(e) => setDecisionMethodFilter(e.target.value)}
              >
                <option value="">全部去向</option>
                <option value="RESALE">转销售（可再售）</option>
                <option value="RETURN_TO_COUNTRY">退运回国</option>
                <option value="DISCARD">弃置销毁</option>
              </select>
              <span className="text-muted-foreground">共 {decisions.length} 条</span>
            </div>

            {loading ? (
              <p className="text-muted-foreground text-sm">正在加载处置决策…</p>
            ) : decisions.length === 0 ? (
              <EmptyState
                icon={<ClipboardList className="h-6 w-6" />}
                title="还没有处置决策"
                description="在「接收登记」里对一条批次显式判去向。处置方式不会被默认，需要人自己选。"
              />
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="py-2">决策单号 / SKU</th>
                    <th className="py-2">去向</th>
                    <th className="py-2">数量</th>
                    <th className="py-2">依据</th>
                    <th className="py-2">质检结论</th>
                    <th className="py-2">状态</th>
                    <th className="py-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {decisions.map((d) => (
                    <tr key={d.id} className="border-b last:border-0">
                      <td className="py-2">
                        <div className="font-mono text-xs">{d.decisionNo}</div>
                        <div>{d.skuCode}</div>
                      </td>
                      <td className="py-2">
                        <Badge tone={d.dispositionMethod === 'RESALE' ? 'success' : 'neutral'}>
                          {dispositionMethodLabel(d.dispositionMethod)}
                        </Badge>
                      </td>
                      <td className="py-2">{quantityText(d.decidedQty)}</td>
                      <td className="py-2 max-w-xs">{d.decisionBasis}</td>
                      <td className="py-2">
                        {d.qualityVerdictLabel ?? '—'}
                        {d.resaleRestricted ? (
                          <Badge tone="danger">命中再销售限制</Badge>
                        ) : null}
                      </td>
                      <td className="py-2">{d.statusLabel}</td>
                      <td className="py-2">
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => {
                            setIssueTarget(d);
                            setIssueError('');
                            setIssueCosts([]);
                          }}
                          disabled={busy || d.status !== 'RECORDED'}
                        >
                          下发处置单
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>
        </TabsContent>

        {/* ---------------- 处置单 ---------------- */}
        <TabsContent value="orders">
          <SectionCard
            title="处置单"
            description="去向来自被引用的决策，数量与决策逐字相同；成本合计是查询时现算的 SUM（不落表），跨币种绝不相加。"
            extra={
              <Button size="sm" variant="ghost" onClick={() => void load()} disabled={busy}>
                <RefreshCw className="mr-1.5 h-4 w-4" />
                刷新
              </Button>
            }
          >
            <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
              <select
                className="rounded border px-2 py-1"
                value={orderMethodFilter}
                onChange={(e) => setOrderMethodFilter(e.target.value)}
              >
                <option value="">全部去向</option>
                <option value="RESALE">转销售（可再售）</option>
                <option value="RETURN_TO_COUNTRY">退运回国</option>
                <option value="DISCARD">弃置销毁</option>
              </select>
              <span className="text-muted-foreground">共 {orders.length} 张</span>
            </div>

            {loading ? (
              <p className="text-muted-foreground text-sm">正在加载处置单…</p>
            ) : orders.length === 0 ? (
              <EmptyState
                icon={<Truck className="h-6 w-6" />}
                title="还没有处置单"
                description="处置单由一条待下发的处置决策下发生成，去向与数量随决策走。"
              />
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="py-2">处置单号 / SKU</th>
                    <th className="py-2">去向</th>
                    <th className="py-2">数量</th>
                    <th className="py-2">成本合计</th>
                    <th className="py-2">残值</th>
                    <th className="py-2">状态</th>
                    <th className="py-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {orders.map((o) => (
                    <tr key={o.id} className="border-b last:border-0">
                      <td className="py-2">
                        <div className="font-mono text-xs">{o.dispositionNo}</div>
                        <div>{o.skuCode}</div>
                      </td>
                      <td className="py-2">{dispositionMethodLabel(o.dispositionMethod)}</td>
                      <td className="py-2">{quantityText(o.disposedQty)}</td>
                      <td className="py-2">
                        {moneyText(o.totalCostAmount, o.costCurrency)}
                        <div className="text-muted-foreground text-xs">{o.costItemCount} 笔</div>
                      </td>
                      <td className="py-2">{moneyText(o.salvageAmount, o.salvageCurrency)}</td>
                      <td className="py-2">
                        <Badge tone={o.status === 'ISSUED' ? 'success' : 'neutral'}>{o.statusLabel}</Badge>
                      </td>
                      <td className="py-2">
                        <div className="flex gap-2">
                          <Button size="sm" variant="outline" onClick={() => void openOrderDetail(o)}>
                            详情
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => {
                              setCostTarget(o);
                              setCostError('');
                            }}
                            disabled={busy || o.status !== 'ISSUED'}
                          >
                            追加成本
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() => void cancelOrder(o)}
                            disabled={busy || o.status !== 'ISSUED'}
                          >
                            撤销
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>
        </TabsContent>

        {/* ---------------- 再销售限制 ---------------- */}
        <TabsContent value="restrictions">
          <SectionCard
            title="再销售限制（品牌方要求 / 平台政策）"
            description="租户级政策：命中即否决转销售。限制可见性不按负责人收窄 —— 收窄会让「否决」变成「可绕过的建议」。"
            extra={
              <div className="flex gap-2">
                <Button size="sm" variant="ghost" onClick={() => void load()} disabled={busy}>
                  <RefreshCw className="mr-1.5 h-4 w-4" />
                  刷新
                </Button>
                <Button size="sm" onClick={() => setRestrictionOpen(true)} disabled={busy}>
                  登记限制
                </Button>
              </div>
            }
          >
            {loading ? (
              <p className="text-muted-foreground text-sm">正在加载再销售限制…</p>
            ) : restrictions.length === 0 ? (
              <EmptyState
                icon={<Scale className="h-6 w-6" />}
                title="还没有再销售限制"
                description="品牌方要求 / 平台政策限制登记在这里；登记后命中该对象的批次不能判成转销售。"
              />
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="py-2">对象</th>
                    <th className="py-2">依据</th>
                    <th className="py-2">理由</th>
                    <th className="py-2">状态</th>
                    <th className="py-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {restrictions.map((r) => (
                    <tr key={r.id} className="border-b last:border-0">
                      <td className="py-2">
                        <Badge tone="info">{restrictionSubjectLabel(r.subjectType)}</Badge>
                        <div className="font-mono text-xs">{r.subjectRef}</div>
                      </td>
                      <td className="py-2">{restrictionBasisLabel(r.restrictionBasis)}</td>
                      <td className="py-2 max-w-xs">{r.reason}</td>
                      <td className="py-2">
                        <Badge tone={r.status === 'ACTIVE' ? 'danger' : 'neutral'}>
                          {r.status === 'ACTIVE' ? '生效中' : '已撤销'}
                        </Badge>
                      </td>
                      <td className="py-2">
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => void doRevokeRestriction(r)}
                          disabled={busy || r.status !== 'ACTIVE'}
                        >
                          <Undo2 className="mr-1.5 h-4 w-4" />
                          撤销
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>
        </TabsContent>

        {/* ---------------- 留痕 ---------------- */}
        <TabsContent value="events">
          <SectionCard
            title="留痕"
            description="追加式：接收 → 决策 → 下发 → 成本 → 指令，逐条可追。"
            extra={
              <Button size="sm" variant="ghost" onClick={() => void load()} disabled={busy}>
                <RefreshCw className="mr-1.5 h-4 w-4" />
                刷新
              </Button>
            }
          >
            {loading ? (
              <p className="text-muted-foreground text-sm">正在加载留痕…</p>
            ) : !events || events.rows.length === 0 ? (
              <EmptyState
                icon={<ClipboardList className="h-6 w-6" />}
                title="还没有留痕"
                description="登记接收、记录决策、下发处置单之后，这里会出现逐条记录。"
              />
            ) : (
              <ul className="divide-y">
                {events.rows.map((e) => (
                  <li key={e.id} className="py-3">
                    <div className="flex items-center gap-2">
                      <Badge tone="neutral">{e.eventTypeLabel}</Badge>
                      <span className="text-muted-foreground text-xs">
                        {new Date(e.occurredAt).toLocaleString()}
                      </span>
                    </div>
                    <p className="mt-1 text-sm">{e.detail}</p>
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>
        </TabsContent>
      </Tabs>

      {/* ---------------- 判定去向弹窗（🔴 处置方式显式选） ---------------- */}
      {decisionTarget ? (
        <Modal open onOpenChange={(open) => !open && setDecisionTarget(null)}>
          <ModalContent className="max-w-2xl">
            <ModalHeader>
              <ModalTitle>判定处置去向</ModalTitle>
              <ModalDescription>
                {decisionTarget.registrationNo} · {decisionTarget.skuCode} · 接收{' '}
                {quantityText(decisionTarget.receivedQty)} 件
              </ModalDescription>
            </ModalHeader>
            <div className="space-y-4">
              {decisionError ? (
                <Notice tone="danger" title="未能记录决策">
                  {decisionError}
                </Notice>
              ) : null}

              {eligibilityLoading ? (
                <p className="text-muted-foreground text-sm">正在读取上游质检结论与政策限制…</p>
              ) : eligibility ? (
                <div className="space-y-2">
                  <p className="text-sm">
                    上游质检结论：
                    <strong>{eligibility.qualityVerdictLabel ?? '尚未判定'}</strong>
                    {eligibility.qualityVerdictAt
                      ? `（${new Date(eligibility.qualityVerdictAt).toLocaleString()}）`
                      : ''}
                  </p>
                  {eligibility.resaleRestricted ? (
                    <Notice tone="warning" title="命中再销售限制">
                      {eligibility.resaleRestrictionNote}
                    </Notice>
                  ) : null}
                  <ul className="text-muted-foreground space-y-1 text-sm">
                    {eligibility.methods.map((m) => (
                      <li key={m.method}>
                        <span className="text-foreground">{m.methodLabel}</span>
                        {m.allowed ? (
                          <Badge tone="success">允许</Badge>
                        ) : (
                          <Badge tone="danger">被挡住</Badge>
                        )}
                        {m.blockingReasons.length > 0 ? (
                          <span>：{m.blockingReasons.join('；')}</span>
                        ) : null}
                      </li>
                    ))}
                  </ul>
                </div>
              ) : null}

              <div className="space-y-2">
                <label className="text-label-13 block" htmlFor="disposition-method">
                  处置方式
                  <span className="text-destructive ml-1" aria-hidden="true">
                    *
                  </span>
                </label>
                <select
                  id="disposition-method"
                  className="rounded border px-2 py-1"
                  value={decisionForm.dispositionMethod}
                  onChange={(e) => setDecisionForm({ ...decisionForm, dispositionMethod: e.target.value })}
                >
                  {/* 🔴 第一项是空值占位：页面从不预选任何去向，更不会默认「转销售」。 */}
                  <option value="">—— 请显式选择处置方式 ——</option>
                  {(['RESALE', 'RETURN_TO_COUNTRY', 'DISCARD'] as DispositionMethod[]).map((m) => (
                    <option key={m} value={m}>
                      {dispositionMethodLabel(m)}
                    </option>
                  ))}
                </select>
                {decisionForm.dispositionMethod ? (
                  <p className="text-muted-foreground text-xs">
                    {blockingReasons(eligibility, decisionForm.dispositionMethod).join('；') ||
                      '服务端未给出阻挡理由。'}
                  </p>
                ) : (
                  <p className="text-muted-foreground text-xs">
                    三个去向对利润影响巨大，必须由人显式给出；本模块不替你默认。
                  </p>
                )}
              </div>

              <div className="space-y-2">
                <label className="text-label-13 block" htmlFor="decision-qty">
                  处置数量
                </label>
                <Input
                  id="decision-qty"
                  type="number"
                  min="0"
                  step="1"
                  value={decisionForm.decidedQty}
                  onChange={(e) => setDecisionForm({ ...decisionForm, decidedQty: e.target.value })}
                />
              </div>

              <div className="space-y-2">
                <label className="text-label-13 block" htmlFor="decision-basis">
                  判定依据
                  <span className="text-destructive ml-1" aria-hidden="true">
                    *
                  </span>
                </label>
                <textarea
                  id="decision-basis"
                  className="w-full rounded border px-2 py-1 text-sm"
                  rows={3}
                  placeholder="凭什么这么判：质检结论、品牌方要求、平台政策……"
                  value={decisionForm.decisionBasis}
                  onChange={(e) => setDecisionForm({ ...decisionForm, decisionBasis: e.target.value })}
                />
              </div>
            </div>
            <ModalFooter>
              <Button variant="outline" onClick={() => setDecisionTarget(null)} disabled={busy}>
                取消
              </Button>
              <Button onClick={() => void submitDecision()} disabled={busy}>
                记录决策
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>
      ) : null}

      {/* ---------------- 登记接收弹窗 ---------------- */}
      {registerOpen ? (
        <Modal open onOpenChange={(open) => !open && setRegisterOpen(false)}>
          <ModalContent className="max-w-2xl">
            <ModalHeader>
              <ModalTitle>登记接收</ModalTitle>
              <ModalDescription>
                货到了哪个仓、由谁登记。实收单必须是上游 11.2.2.1 已入库（POSTED）的那一张。
              </ModalDescription>
            </ModalHeader>
            <div className="space-y-3">
              {registerError ? (
                <Notice tone="danger" title="未能登记">
                  {registerError}
                </Notice>
              ) : null}
              <div className="grid gap-3 sm:grid-cols-2">
                <div className="space-y-2">
                  <label className="text-label-13 block" htmlFor="receipt-id">
                    上游实收单 ID
                  </label>
                  <Input
                    id="receipt-id"
                    value={registerForm.receiptId}
                    onChange={(e) => setRegisterForm({ ...registerForm, receiptId: e.target.value })}
                  />
                </div>
                <div className="space-y-2">
                  <label className="text-label-13 block" htmlFor="receipt-line-id">
                    上游实收行 ID
                  </label>
                  <Input
                    id="receipt-line-id"
                    value={registerForm.receiptLineId}
                    onChange={(e) => setRegisterForm({ ...registerForm, receiptLineId: e.target.value })}
                  />
                </div>
                <div className="space-y-2">
                  <label className="text-label-13 block" htmlFor="warehouse-type">
                    接收仓类型
                  </label>
                  <select
                    id="warehouse-type"
                    className="rounded border px-2 py-1"
                    value={registerForm.warehouseType}
                    onChange={(e) => setRegisterForm({ ...registerForm, warehouseType: e.target.value })}
                  >
                    <option value="LOCAL">本地仓</option>
                    <option value="OVERSEAS">海外仓</option>
                    <option value="PLATFORM">平台仓</option>
                  </select>
                </div>
                <div className="space-y-2">
                  <label className="text-label-13 block" htmlFor="warehouse-ref">
                    接收仓
                  </label>
                  <Input
                    id="warehouse-ref"
                    value={registerForm.warehouseRef}
                    onChange={(e) => setRegisterForm({ ...registerForm, warehouseRef: e.target.value })}
                  />
                </div>
                <div className="space-y-2">
                  <label className="text-label-13 block" htmlFor="received-qty">
                    接收数量
                  </label>
                  <Input
                    id="received-qty"
                    type="number"
                    min="0"
                    step="1"
                    value={registerForm.receivedQty}
                    onChange={(e) => setRegisterForm({ ...registerForm, receivedQty: e.target.value })}
                  />
                </div>
                <div className="space-y-2">
                  <label className="text-label-13 block" htmlFor="warehouse-country">
                    仓库国家
                  </label>
                  <Input
                    id="warehouse-country"
                    maxLength={2}
                    placeholder="DE"
                    value={registerForm.warehouseCountry}
                    onChange={(e) => setRegisterForm({ ...registerForm, warehouseCountry: e.target.value })}
                  />
                </div>
                <div className="space-y-2">
                  <label className="text-label-13 block" htmlFor="brand-code">
                    品牌编码
                  </label>
                  <Input
                    id="brand-code"
                    value={registerForm.brandCode}
                    onChange={(e) => setRegisterForm({ ...registerForm, brandCode: e.target.value })}
                  />
                </div>
                <div className="space-y-2">
                  <label className="text-label-13 block" htmlFor="platform-code">
                    平台编码
                  </label>
                  <Input
                    id="platform-code"
                    value={registerForm.platformCode}
                    onChange={(e) => setRegisterForm({ ...registerForm, platformCode: e.target.value })}
                  />
                </div>
              </div>
            </div>
            <ModalFooter>
              <Button variant="outline" onClick={() => setRegisterOpen(false)} disabled={busy}>
                取消
              </Button>
              <Button onClick={() => void submitRegister()} disabled={busy}>
                登记
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>
      ) : null}

      {/* ---------------- 下发处置单弹窗 ---------------- */}
      {issueTarget ? (
        <Modal open onOpenChange={(open) => !open && setIssueTarget(null)}>
          <ModalContent className="max-w-2xl">
            <ModalHeader>
              <ModalTitle>下发处置单</ModalTitle>
              <ModalDescription>
                {issueTarget.decisionNo} · {dispositionMethodLabel(issueTarget.dispositionMethod)} ·{' '}
                {quantityText(issueTarget.decidedQty)} 件（去向与数量随决策走，不能改）
              </ModalDescription>
            </ModalHeader>
            <div className="space-y-3">
              {issueError ? (
                <Notice tone="danger" title="未能下发">
                  {issueError}
                </Notice>
              ) : null}
              <div className="grid gap-3 sm:grid-cols-2">
                <div className="space-y-2">
                  <label className="text-label-13 block" htmlFor="cost-currency">
                    成本币种
                  </label>
                  <Input
                    id="cost-currency"
                    maxLength={3}
                    value={issueForm.costCurrency}
                    onChange={(e) => setIssueForm({ ...issueForm, costCurrency: e.target.value })}
                  />
                </div>
                <div className="space-y-2">
                  <label className="text-label-13 block" htmlFor="salvage-currency">
                    残值币种
                  </label>
                  <Input
                    id="salvage-currency"
                    maxLength={3}
                    value={issueForm.salvageCurrency}
                    onChange={(e) => setIssueForm({ ...issueForm, salvageCurrency: e.target.value })}
                  />
                </div>
                <div className="space-y-2">
                  <label className="text-label-13 block" htmlFor="salvage-amount">
                    残值金额
                  </label>
                  <Input
                    id="salvage-amount"
                    type="number"
                    min="0"
                    step="0.01"
                    value={issueForm.salvageAmount}
                    onChange={(e) => setIssueForm({ ...issueForm, salvageAmount: e.target.value })}
                  />
                </div>
              </div>
              <p className="text-muted-foreground text-xs">
                成本与残值是**两个币种**，本模块不做任何汇率换算；成本明细行的币种必须与成本币种逐字相同。
              </p>

              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-label-13">随单成本明细</span>
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() =>
                      setIssueCosts([
                        ...issueCosts,
                        { costType: 'RETURN_FREIGHT', amount: 0, currency: issueForm.costCurrency },
                      ])
                    }
                  >
                    加一笔
                  </Button>
                </div>
                {issueCosts.length === 0 ? (
                  <p className="text-muted-foreground text-sm">
                    还没有成本明细。销毁费常常是事后才拿到单据，也可以事后追加。
                  </p>
                ) : (
                  <ul className="space-y-2">
                    {issueCosts.map((c, i) => (
                      <li key={i} className="flex items-center gap-2">
                        <select
                          className="rounded border px-2 py-1"
                          value={c.costType}
                          onChange={(e) => {
                            const next = [...issueCosts];
                            next[i] = { ...c, costType: e.target.value };
                            setIssueCosts(next);
                          }}
                        >
                          {(['RETURN_FREIGHT', 'DESTROY_FEE', 'PLATFORM_HANDLING_FEE', 'OTHER'] as const).map(
                            (t) => (
                              <option key={t} value={t}>
                                {costTypeLabel(t)}
                              </option>
                            ),
                          )}
                        </select>
                        <Input
                          className="w-28"
                          type="number"
                          min="0"
                          step="0.01"
                          value={String(c.amount)}
                          onChange={(e) => {
                            const next = [...issueCosts];
                            next[i] = { ...c, amount: Number(e.target.value) };
                            setIssueCosts(next);
                          }}
                        />
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => setIssueCosts(issueCosts.filter((_, idx) => idx !== i))}
                        >
                          去掉
                        </Button>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </div>
            <ModalFooter>
              <Button variant="outline" onClick={() => setIssueTarget(null)} disabled={busy}>
                取消
              </Button>
              <Button onClick={() => void submitIssue()} disabled={busy}>
                下发
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>
      ) : null}

      {/* ---------------- 追加成本弹窗 ---------------- */}
      {costTarget ? (
        <Modal open onOpenChange={(open) => !open && setCostTarget(null)}>
          <ModalContent className="max-w-xl">
            <ModalHeader>
              <ModalTitle>追加处置成本</ModalTitle>
              <ModalDescription>
                {costTarget.dispositionNo} · 成本币种 {costTarget.costCurrency}（必须与处置单逐字相同）
              </ModalDescription>
            </ModalHeader>
            <div className="space-y-3">
              {costError ? (
                <Notice tone="danger" title="未能记成本">
                  {costError}
                </Notice>
              ) : null}
              <div className="space-y-2">
                <label className="text-label-13 block" htmlFor="cost-type">
                  成本类型
                </label>
                <select
                  id="cost-type"
                  className="rounded border px-2 py-1"
                  value={costForm.costType}
                  onChange={(e) => setCostForm({ ...costForm, costType: e.target.value })}
                >
                  {(['RETURN_FREIGHT', 'DESTROY_FEE', 'PLATFORM_HANDLING_FEE', 'OTHER'] as const).map((t) => (
                    <option key={t} value={t}>
                      {costTypeLabel(t)}
                    </option>
                  ))}
                </select>
              </div>
              <div className="space-y-2">
                <label className="text-label-13 block" htmlFor="cost-amount">
                  金额
                </label>
                <Input
                  id="cost-amount"
                  type="number"
                  min="0"
                  step="0.01"
                  value={costForm.amount}
                  onChange={(e) => setCostForm({ ...costForm, amount: e.target.value })}
                />
              </div>
              <div className="space-y-2">
                <label className="text-label-13 block" htmlFor="cost-vendor">
                  收款方
                </label>
                <Input
                  id="cost-vendor"
                  value={costForm.vendorRef}
                  onChange={(e) => setCostForm({ ...costForm, vendorRef: e.target.value })}
                />
              </div>
            </div>
            <ModalFooter>
              <Button variant="outline" onClick={() => setCostTarget(null)} disabled={busy}>
                取消
              </Button>
              <Button onClick={() => void submitCost()} disabled={busy}>
                记一笔
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>
      ) : null}

      {/* ---------------- 处置单详情 ---------------- */}
      {detail ? (
        <Modal open onOpenChange={(open) => !open && setDetail(null)}>
          <ModalContent className="max-w-3xl">
            <ModalHeader>
              <ModalTitle>{detail.head.dispositionNo}</ModalTitle>
              <ModalDescription>
                {detail.head.skuCode} · {dispositionMethodLabel(detail.head.dispositionMethod)} ·{' '}
                {quantityText(detail.head.disposedQty)} 件
              </ModalDescription>
            </ModalHeader>
            <div className="space-y-4">
              <div className="grid gap-3 sm:grid-cols-3">
                <div>
                  <p className="text-muted-foreground text-xs">成本合计（现算）</p>
                  <p>{moneyText(detail.head.totalCostAmount, detail.head.costCurrency)}</p>
                </div>
                <div>
                  <p className="text-muted-foreground text-xs">残值</p>
                  <p>{moneyText(detail.head.salvageAmount, detail.head.salvageCurrency)}</p>
                </div>
                <div>
                  <p className="text-muted-foreground text-xs">状态</p>
                  <p>{detail.head.statusLabel}</p>
                </div>
              </div>

              <div>
                <h3 className="mb-2 text-sm font-medium">成本明细</h3>
                {detail.costs.length === 0 ? (
                  <p className="text-muted-foreground text-sm">还没有成本明细。</p>
                ) : (
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="text-muted-foreground border-b text-left">
                        <th className="py-2">类型</th>
                        <th className="py-2">金额</th>
                        <th className="py-2">收款方</th>
                        <th className="py-2">发生时间</th>
                      </tr>
                    </thead>
                    <tbody>
                      {detail.costs.map((c) => (
                        <tr key={c.id} className="border-b last:border-0">
                          <td className="py-2">{c.costTypeLabel}</td>
                          <td className="py-2">{moneyText(c.amount, c.currency)}</td>
                          <td className="py-2">{c.vendorRef || '—'}</td>
                          <td className="py-2">{new Date(c.incurredAt).toLocaleString()}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>

              <div>
                <h3 className="mb-2 text-sm font-medium">库存状态变更指令（→ 7 库存）</h3>
                {detail.instruction ? (
                  <div className="space-y-1 text-sm">
                    <p>
                      {detail.instruction.instructionTypeLabel} →{' '}
                      {detail.instruction.targetInventoryStateLabel} ·{' '}
                      {quantityText(detail.instruction.qty)} 件
                    </p>
                    <p className="text-muted-foreground text-xs">
                      消费方 {detail.instruction.consumerModule} · 指令状态{' '}
                      {detail.instruction.instructionStatus}（消费方未交付，指令恒为 PENDING）
                    </p>
                  </div>
                ) : (
                  <p className="text-muted-foreground text-sm">没有指令记录。</p>
                )}
              </div>

              <div>
                <h3 className="mb-2 text-sm font-medium">留痕</h3>
                <ul className="divide-y">
                  {detail.events.map((e) => (
                    <li key={e.id} className="py-2 text-sm">
                      <span className="text-muted-foreground">{e.eventTypeLabel}</span> · {e.detail}
                    </li>
                  ))}
                </ul>
              </div>
            </div>
            <ModalFooter>
              <Button variant="outline" onClick={() => setDetail(null)}>
                关闭
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>
      ) : null}

      {/* ---------------- 登记再销售限制弹窗 ---------------- */}
      {restrictionOpen ? (
        <Modal open onOpenChange={(open) => !open && setRestrictionOpen(false)}>
          <ModalContent className="max-w-xl">
            <ModalHeader>
              <ModalTitle>登记再销售限制</ModalTitle>
              <ModalDescription>品牌方要求 / 平台政策：命中该对象的批次不能判成转销售。</ModalDescription>
            </ModalHeader>
            <div className="space-y-3">
              {restrictionError ? (
                <Notice tone="danger" title="未能登记">
                  {restrictionError}
                </Notice>
              ) : null}
              <div className="space-y-2">
                <label className="text-label-13 block" htmlFor="restriction-subject-type">
                  限制对象类型
                </label>
                <select
                  id="restriction-subject-type"
                  className="rounded border px-2 py-1"
                  value={restrictionForm.subjectType}
                  onChange={(e) => setRestrictionForm({ ...restrictionForm, subjectType: e.target.value })}
                >
                  <option value="BRAND">品牌方要求</option>
                  <option value="PLATFORM">平台政策</option>
                  <option value="SKU">单品限制</option>
                </select>
              </div>
              <div className="space-y-2">
                <label className="text-label-13 block" htmlFor="restriction-subject-ref">
                  限制对象
                </label>
                <Input
                  id="restriction-subject-ref"
                  placeholder="品牌编码 / 平台编码 / SKU 编码"
                  value={restrictionForm.subjectRef}
                  onChange={(e) => setRestrictionForm({ ...restrictionForm, subjectRef: e.target.value })}
                />
              </div>
              <div className="space-y-2">
                <label className="text-label-13 block" htmlFor="restriction-basis">
                  限制依据
                </label>
                <select
                  id="restriction-basis"
                  className="rounded border px-2 py-1"
                  value={restrictionForm.restrictionBasis}
                  onChange={(e) =>
                    setRestrictionForm({ ...restrictionForm, restrictionBasis: e.target.value })
                  }
                >
                  <option value="BRAND_REQUIREMENT">品牌方要求</option>
                  <option value="PLATFORM_POLICY">平台政策</option>
                  <option value="REGULATION">法规不允许</option>
                  <option value="CONTRACT">合同条款</option>
                </select>
              </div>
              <div className="space-y-2">
                <label className="text-label-13 block" htmlFor="restriction-reason">
                  限制理由
                </label>
                <textarea
                  id="restriction-reason"
                  className="w-full rounded border px-2 py-1 text-sm"
                  rows={3}
                  value={restrictionForm.reason}
                  onChange={(e) => setRestrictionForm({ ...restrictionForm, reason: e.target.value })}
                />
              </div>
            </div>
            <ModalFooter>
              <Button variant="outline" onClick={() => setRestrictionOpen(false)} disabled={busy}>
                取消
              </Button>
              <Button onClick={() => void submitRestriction()} disabled={busy}>
                登记
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>
      ) : null}
    </>
  );
}
