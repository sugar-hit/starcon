'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { FileCheck2, Flame, RefreshCw, ShieldAlert, Trash2, Weight } from 'lucide-react';
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
  buildClaimBasisPayload,
  buildCostPayload,
  buildDisposePayload,
  buildDisposalOrderPayload,
  buildEvidencePayload,
  buildPolicyPayload,
  cancelOrder,
  claimTypeLabel,
  costTypeLabel,
  createClaimBasis,
  createOrder,
  createPolicy,
  disposeOrder,
  emptyClaimBasisForm,
  emptyCostForm,
  emptyDisposeForm,
  emptyEvidenceForm,
  emptyOrderForm,
  emptyPolicyForm,
  errorText,
  evidenceKindLabel,
  evidenceReady,
  fetchCandidates,
  fetchCapabilities,
  fetchEvents,
  fetchOrder,
  fetchOrders,
  fetchOverview,
  fetchPolicies,
  methodLabel,
  missingEvidence,
  moneyText,
  quantityText,
  recordCost,
  recordEvidence,
  scheduleOrder,
  statusLabel,
  subjectTypeLabel,
  type ClaimBasisForm,
  type CostForm,
  type DisposeForm,
  type DisposalCandidateRow,
  type EvidenceForm,
  type OrderForm,
  type PolicyForm,
  type ReturnDisposalCapabilities,
  type ReturnDisposalDetail,
  type ReturnDisposalEventPage,
  type ReturnDisposalOverview,
  type ReturnDisposalPage,
  type ReturnDisposalPolicyPage,
} from './return-disposal-model';

/**
 * 10.7.1.3 弃置与销毁。
 *
 * <p>蓝图：「管理弃置与销毁：无销售价值/品牌方要求/法规不允许再销售的退货，处理销毁、
 * <b>留存销毁凭证/照片/重量记录</b>，满足平台与品牌方的销毁证明要求；<b>销毁数量可作为
 * 品牌方索赔依据</b>。输入：待销毁清单；销毁方式（压碎/焚烧/专业销毁）；凭证要求。
 * 输出：销毁单 + 凭证 + 数量/重量记录；索赔依据（→ 6.1.2.3 / 11.2.3.3）。」
 *
 * <p>🔴 页面上不可让步的两条：
 *
 * <ul>
 *   <li><b>凭证是硬闸，不是提示</b>：销毁单详情把「还缺哪几样凭证」直接列出来，
 *       缺项时「执行销毁」按钮**禁用**（服务端也会再拒一次：422）。</li>
 *   <li><b>销毁方式是枚举事实</b>：下拉框只有压碎 / 焚烧 / 专业销毁，
 *       选完立刻把这条方式对应的<b>基线凭证要求</b>显示出来 ——
 *       方式决定要留什么证，不是留完再补方式。</li>
 * </ul>
 *
 * <p>🔴 页面**不做任何汇率换算**：销毁费用与索赔金额都走销毁单那一个费用币种，
 * 分行显示、绝不相加。
 */
export function ReturnDisposalWorkspace() {
  const [capabilities, setCapabilities] = useState<ReturnDisposalCapabilities | null>(null);
  const [overview, setOverview] = useState<ReturnDisposalOverview | null>(null);
  const [candidates, setCandidates] = useState<DisposalCandidateRow[]>([]);
  const [orders, setOrders] = useState<ReturnDisposalPage | null>(null);
  const [policies, setPolicies] = useState<ReturnDisposalPolicyPage | null>(null);
  const [events, setEvents] = useState<ReturnDisposalEventPage | null>(null);

  const [keyword, setKeyword] = useState('');
  const [candidateKeyword, setCandidateKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [methodFilter, setMethodFilter] = useState('');

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  // 建销毁单
  const [orderOpen, setOrderOpen] = useState(false);
  const [orderForm, setOrderForm] = useState<OrderForm>(emptyOrderForm());
  const [orderError, setOrderError] = useState('');

  // 凭证
  const [evidenceOpen, setEvidenceOpen] = useState(false);
  const [evidenceForm, setEvidenceForm] = useState<EvidenceForm>(emptyEvidenceForm());
  const [evidenceError, setEvidenceError] = useState('');

  // 费用
  const [costOpen, setCostOpen] = useState(false);
  const [costForm, setCostForm] = useState<CostForm>(emptyCostForm());
  const [costError, setCostError] = useState('');

  // 索赔依据
  const [claimOpen, setClaimOpen] = useState(false);
  const [claimForm, setClaimForm] = useState<ClaimBasisForm>(emptyClaimBasisForm());
  const [claimError, setClaimError] = useState('');

  // 执行销毁
  const [disposeOpen, setDisposeOpen] = useState(false);
  const [disposeForm, setDisposeForm] = useState<DisposeForm>(emptyDisposeForm());
  const [disposeError, setDisposeError] = useState('');

  // 凭证要求
  const [policyOpen, setPolicyOpen] = useState(false);
  const [policyForm, setPolicyForm] = useState<PolicyForm>(emptyPolicyForm());
  const [policyError, setPolicyError] = useState('');

  // 详情
  const [detail, setDetail] = useState<ReturnDisposalDetail | null>(null);
  const [detailError, setDetailError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, ov, cands, list, pols, evts] = await Promise.all([
        fetchCapabilities(),
        fetchOverview(),
        fetchCandidates({ keyword: candidateKeyword, size: 50 }),
        fetchOrders({ keyword, status: statusFilter, method: methodFilter, size: 50 }),
        fetchPolicies({ size: 50 }),
        fetchEvents(undefined, 0, 50),
      ]);
      setCapabilities(caps);
      setOverview(ov);
      setCandidates(cands.rows);
      setOrders(list);
      setPolicies(pols);
      setEvents(evts);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [keyword, statusFilter, methodFilter, candidateKeyword]);

  useEffect(() => {
    void load();
  }, [load]);

  async function openDetail(id: string) {
    setDetail(null);
    setDetailError('');
    setBusy(true);
    try {
      setDetail(await fetchOrder(id));
    } catch (e) {
      setDetailError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitOrder() {
    const payload = buildDisposalOrderPayload(orderForm);
    if ('error' in payload) {
      setOrderError(payload.error);
      return;
    }
    setBusy(true);
    setOrderError('');
    try {
      const created = await createOrder(payload);
      setOrderOpen(false);
      setOrderForm(emptyOrderForm());
      await load();
      setDetail(created);
    } catch (e) {
      setOrderError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitEvidence() {
    if (!detail) return;
    const payload = buildEvidencePayload(evidenceForm);
    if ('error' in payload) {
      setEvidenceError(payload.error);
      return;
    }
    setBusy(true);
    setEvidenceError('');
    try {
      await recordEvidence(detail.order.id, payload);
      setEvidenceOpen(false);
      setEvidenceForm(emptyEvidenceForm());
      await load();
      await openDetail(detail.order.id);
    } catch (e) {
      setEvidenceError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitCost() {
    if (!detail) return;
    const payload = buildCostPayload(costForm);
    if ('error' in payload) {
      setCostError(payload.error);
      return;
    }
    setBusy(true);
    setCostError('');
    try {
      await recordCost(detail.order.id, payload);
      setCostOpen(false);
      setCostForm(emptyCostForm());
      await load();
      await openDetail(detail.order.id);
    } catch (e) {
      setCostError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitClaim() {
    if (!detail) return;
    const payload = buildClaimBasisPayload(claimForm);
    if ('error' in payload) {
      setClaimError(payload.error);
      return;
    }
    setBusy(true);
    setClaimError('');
    try {
      await createClaimBasis(detail.order.id, payload);
      setClaimOpen(false);
      setClaimForm(emptyClaimBasisForm());
      await load();
      await openDetail(detail.order.id);
    } catch (e) {
      setClaimError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitDispose() {
    if (!detail) return;
    const payload = buildDisposePayload(disposeForm);
    if ('error' in payload) {
      setDisposeError(payload.error);
      return;
    }
    setBusy(true);
    setDisposeError('');
    try {
      await disposeOrder(detail.order.id, payload);
      setDisposeOpen(false);
      setDisposeForm(emptyDisposeForm());
      await load();
      await openDetail(detail.order.id);
    } catch (e) {
      setDisposeError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitPolicy() {
    const payload = buildPolicyPayload(policyForm);
    if ('error' in payload) {
      setPolicyError(payload.error);
      return;
    }
    setBusy(true);
    setPolicyError('');
    try {
      await createPolicy(payload);
      setPolicyOpen(false);
      setPolicyForm(emptyPolicyForm());
      await load();
    } catch (e) {
      setPolicyError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function advance(action: 'schedule' | 'cancel', id: string) {
    setBusy(true);
    setError('');
    try {
      if (action === 'schedule') {
        await scheduleOrder(id);
      } else {
        const reason = window.prompt('取消理由（必填）', '');
        if (reason === null) {
          setBusy(false);
          return;
        }
        if (!reason.trim()) {
          setBusy(false);
          return;
        }
        await cancelOrder(id, reason.trim());
      }
      await load();
      await openDetail(id);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  /** 🔴 还缺哪几样凭证（页面的那一半硬闸）。 */
  const missing = useMemo(
    () => (detail ? missingEvidence(detail.order, detail.evidence) : []),
    [detail],
  );
  const ready = detail ? evidenceReady(detail.order, detail.evidence) : false;
  const candidateQty = useMemo(
    () => candidates.reduce((sum, c) => sum + (c.disposedQty ?? 0), 0),
    [candidates],
  );

  return (
    <>
      <PageHeader
        title="弃置与销毁"
        description="无销售价值 / 品牌方要求 / 法规不允许再销售的退货：处理销毁、留存销毁凭证（照片 / 重量 / 处置证明 / 执行方签核），满足平台与品牌方的销毁证明要求，销毁数量可作为品牌方索赔依据。"
      />

      {error ? (
        <Notice tone="danger" title="操作未完成">
          {error}
        </Notice>
      ) : null}

      {capabilities ? (
        <Notice tone="warning" title="销毁凭证缺失会触发品牌方追责">
          {capabilities.evidenceNote} {capabilities.methodNote}
        </Notice>
      ) : null}

      <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="销毁单"
          value={String(overview?.orderCount ?? 0)}
          comparisonLabel={`草稿 ${overview?.draftCount ?? 0} · 已排期 ${overview?.scheduledCount ?? 0} · 已销毁 ${overview?.disposedCount ?? 0}`}
        />
        <StatCard
          label="待销毁候选"
          value={String(overview?.candidateCount ?? 0)}
          comparisonLabel={`共 ${quantityText(overview?.candidateQty ?? 0)} 件（10.7.1.1 决策为弃置销毁）`}
        />
        <StatCard
          label="已销毁数量 / 重量"
          value={quantityText(overview?.disposedQty ?? 0)}
          comparisonLabel={`${quantityText(overview?.disposedWeightKg ?? 0)} kg（${overview?.disposedOrderCount ?? 0} 单）`}
        />
        <StatCard
          label="销毁费用合计"
          value={
            overview && overview.costTotals.length === 1
              ? moneyText(overview.costTotals[0]?.amount, overview.costTotals[0]?.currency)
              : `${overview?.costTotals.length ?? 0} 个币种`
          }
          comparisonLabel="按币种分行，跨币种绝不相加"
        />
      </div>

      <Tabs defaultValue="candidates" className="mt-6">
        <TabsList>
          <TabsTrigger value="candidates">待销毁清单</TabsTrigger>
          <TabsTrigger value="orders">销毁单</TabsTrigger>
          <TabsTrigger value="policies">凭证要求</TabsTrigger>
          <TabsTrigger value="events">留痕</TabsTrigger>
        </TabsList>

        {/* ---------------- 待销毁清单 ---------------- */}
        <TabsContent value="candidates">
          <SectionCard
            title="待销毁清单"
            description="只来自 10.7.1.1 决策为「弃置销毁」的处置单（转销售 / 退运回国不会出现在这里）。选中一条即可建销毁单。"
            extra={
              <Button
                size="sm"
                onClick={() => {
                  setOrderOpen(true);
                  setOrderError('');
                }}
                disabled={busy || candidates.length === 0}
              >
                <Trash2 className="mr-1.5 h-4 w-4" />
                建销毁单
              </Button>
            }
          >
            <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
              <Input
                className="max-w-xs"
                placeholder="SKU / 收货单号 / 批次"
                value={candidateKeyword}
                onChange={(e) => setCandidateKeyword(e.target.value)}
              />
              <span className="text-muted-foreground">
                共 {candidates.length} 条候选，{quantityText(candidateQty)} 件
              </span>
            </div>

            {loading ? (
              <p className="text-muted-foreground text-sm">正在加载待销毁清单…</p>
            ) : candidates.length === 0 ? (
              <EmptyState
                icon={<Trash2 className="h-6 w-6" />}
                title="没有待销毁的货"
                description="待销毁清单来自 10.7.1.1 已下发且去向为「弃置销毁」的处置单；没有这类处置单时这里就是空的。"
              />
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="py-2">处置单 / SKU</th>
                    <th className="py-2">收货单号</th>
                    <th className="py-2">品牌 / 平台</th>
                    <th className="py-2">处置数量</th>
                    <th className="py-2">下发时间</th>
                    <th className="py-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {candidates.map((c) => (
                    <tr key={c.dispositionOrderId} className="border-b last:border-0">
                      <td className="py-2">
                        <div className="font-mono text-xs">{c.dispositionNo}</div>
                        <div>{c.skuCode}</div>
                        <div className="text-muted-foreground text-xs">{c.skuName}</div>
                      </td>
                      <td className="py-2 font-mono text-xs">{c.receiptNo}</td>
                      <td className="py-2 text-xs">
                        {c.brandCode} / {c.platformCode}
                      </td>
                      <td className="py-2">{quantityText(c.disposedQty)}</td>
                      <td className="py-2 text-xs">{c.issuedAt}</td>
                      <td className="py-2">
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => {
                            setOrderForm({
                              ...emptyOrderForm(),
                              dispositionOrderId: c.dispositionOrderId,
                              disposalQty: String(c.disposedQty),
                            });
                            setOrderOpen(true);
                            setOrderError('');
                          }}
                        >
                          建销毁单
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>
        </TabsContent>

        {/* ---------------- 销毁单 ---------------- */}
        <TabsContent value="orders">
          <SectionCard
            title="销毁单"
            description="一次销毁的执行凭据：方式 / 数量 / 重量 / 凭证齐备性。费用合计是查询时现算的 SUM（不落表）。"
            extra={
              <Button size="sm" variant="ghost" onClick={() => void load()} disabled={busy}>
                <RefreshCw className="mr-1.5 h-4 w-4" />
                刷新
              </Button>
            }
          >
            <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
              <Input
                className="max-w-xs"
                placeholder="销毁单号 / SKU / 处置单号"
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
              />
              <select
                className="rounded border px-2 py-1"
                value={statusFilter}
                onChange={(e) => setStatusFilter(e.target.value)}
              >
                <option value="">全部状态</option>
                <option value="DRAFT">草稿</option>
                <option value="SCHEDULED">已排期</option>
                <option value="DISPOSED">已销毁</option>
                <option value="CANCELLED">已取消</option>
              </select>
              <select
                className="rounded border px-2 py-1"
                value={methodFilter}
                onChange={(e) => setMethodFilter(e.target.value)}
              >
                <option value="">全部销毁方式</option>
                <option value="SHRED">压碎</option>
                <option value="INCINERATE">焚烧</option>
                <option value="PROFESSIONAL">专业销毁</option>
              </select>
              <span className="text-muted-foreground">共 {orders?.total ?? 0} 张销毁单</span>
            </div>

            {loading ? (
              <p className="text-muted-foreground text-sm">正在加载销毁单…</p>
            ) : (orders?.rows.length ?? 0) === 0 ? (
              <EmptyState
                icon={<Trash2 className="h-6 w-6" />}
                title="还没有销毁单"
                description="先从待销毁清单里选一条处置单建单；销毁单必须有明确的销毁方式与数量。"
              />
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="py-2">销毁单 / 来源</th>
                    <th className="py-2">SKU</th>
                    <th className="py-2">方式</th>
                    <th className="py-2">状态</th>
                    <th className="py-2">数量 / 重量</th>
                    <th className="py-2">凭证</th>
                    <th className="py-2">费用合计</th>
                    <th className="py-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {(orders?.rows ?? []).map((row) => (
                    <tr key={row.id} className="border-b last:border-0">
                      <td className="py-2">
                        <div className="font-mono text-xs">{row.disposalNo}</div>
                        <div className="text-muted-foreground text-xs">
                          上游 {row.sourceReference}
                        </div>
                      </td>
                      <td className="py-2">
                        <div>{row.skuCode}</div>
                        <div className="text-muted-foreground text-xs">{row.skuName}</div>
                      </td>
                      <td className="py-2">{row.disposalMethodLabel || methodLabel(row.disposalMethod)}</td>
                      <td className="py-2">
                        <Badge tone={row.status === 'DISPOSED' ? 'success' : 'neutral'}>
                          {row.statusLabel || statusLabel(row.status)}
                        </Badge>
                      </td>
                      <td className="py-2">
                        {quantityText(row.disposalQty)} / {quantityText(row.weightKg)}{' '}
                        {row.weightUnit}
                      </td>
                      <td className="py-2 text-xs">{row.evidenceCount} 项</td>
                      <td className="py-2">{moneyText(row.totalCost, row.costCurrency)}</td>
                      <td className="py-2">
                        <Button size="sm" variant="outline" onClick={() => void openDetail(row.id)}>
                          详情
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>
        </TabsContent>

        {/* ---------------- 凭证要求 ---------------- */}
        <TabsContent value="policies">
          <SectionCard
            title="凭证要求"
            description="品牌方要求 / 平台政策 / 租户默认：凭证要求只能加严不能放松，它对全租户生效（所以不按负责人收敛）。"
            extra={
              <Button
                size="sm"
                onClick={() => {
                  setPolicyOpen(true);
                  setPolicyError('');
                }}
                disabled={busy}
              >
                <FileCheck2 className="mr-1.5 h-4 w-4" />
                登记凭证要求
              </Button>
            }
          >
            {loading ? (
              <p className="text-muted-foreground text-sm">正在加载凭证要求…</p>
            ) : (policies?.rows.length ?? 0) === 0 ? (
              <EmptyState
                icon={<ShieldAlert className="h-6 w-6" />}
                title="还没有额外的凭证要求"
                description="销毁方式本身就带基线凭证要求（专业销毁要重量、焚烧要处置证明编号）；品牌方 / 平台的合约要求在这里登记，只能加严。"
              />
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="py-2">主体</th>
                    <th className="py-2">凭证要求</th>
                    <th className="py-2">状态</th>
                    <th className="py-2">备注</th>
                  </tr>
                </thead>
                <tbody>
                  {(policies?.rows ?? []).map((row) => (
                    <tr key={row.id} className="border-b last:border-0">
                      <td className="py-2">
                        <div>{row.subjectTypeLabel || subjectTypeLabel(row.subjectType)}</div>
                        <div className="text-muted-foreground font-mono text-xs">{row.subjectRef}</div>
                      </td>
                      <td className="py-2 text-xs">
                        {row.requiredPhoto ? '照片 ' : ''}
                        {row.requiredWeight ? '重量 ' : ''}
                        {row.requiredCertificate ? '处置证明 ' : ''}
                        {row.requiredExecutor ? '执行方签核' : ''}
                      </td>
                      <td className="py-2">
                        <Badge tone={row.status === 'ACTIVE' ? 'success' : 'neutral'}>
                          {row.statusLabel}
                        </Badge>
                      </td>
                      <td className="text-muted-foreground py-2 text-xs">{row.note}</td>
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
            description="销毁留痕是追加式的：改与删都被数据库拒。"
          >
            {loading ? (
              <p className="text-muted-foreground text-sm">正在加载留痕…</p>
            ) : (events?.rows.length ?? 0) === 0 ? (
              <EmptyState
                icon={<FileCheck2 className="h-6 w-6" />}
                title="还没有留痕"
                description="每一次建单、排期、登记凭证、执行销毁、记费用、生成索赔依据都会在这里留下痕迹。"
              />
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="py-2">事件</th>
                    <th className="py-2">时间</th>
                    <th className="py-2">详情</th>
                  </tr>
                </thead>
                <tbody>
                  {(events?.rows ?? []).map((row) => (
                    <tr key={row.id} className="border-b last:border-0">
                      <td className="py-2">
                        <Badge tone="neutral">{row.eventTypeLabel}</Badge>
                      </td>
                      <td className="py-2 text-xs">{row.occurredAt}</td>
                      <td className="py-2 text-xs">{row.detail}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>
        </TabsContent>
      </Tabs>

      {/* ---------------- 销毁单详情 ---------------- */}
      {detail ? (
        <SectionCard
          className="mt-6"
          title={`销毁单 ${detail.order.disposalNo}`}
          description={`方式 ${detail.order.disposalMethodLabel || methodLabel(detail.order.disposalMethod)} · 数量 ${quantityText(detail.order.disposalQty)} · 重量 ${quantityText(detail.order.weightKg)} ${detail.order.weightUnit} · 上游处置单 ${detail.order.sourceReference}`}
          extra={
            <Button size="sm" variant="ghost" onClick={() => setDetail(null)} disabled={busy}>
              关闭
            </Button>
          }
        >
          <div className="mb-3 flex flex-wrap items-center gap-2">
            <Badge tone={detail.order.status === 'DISPOSED' ? 'success' : 'neutral'}>
              {detail.order.statusLabel || statusLabel(detail.order.status)}
            </Badge>
            {detail.order.status === 'DRAFT' ? (
              <Button
                size="sm"
                variant="outline"
                onClick={() => void advance('schedule', detail.order.id)}
                disabled={busy}
              >
                排期（冻结凭证要求）
              </Button>
            ) : null}
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                setEvidenceForm(emptyEvidenceForm());
                setEvidenceOpen(true);
                setEvidenceError('');
              }}
              disabled={busy || detail.order.status === 'DISPOSED' || detail.order.status === 'CANCELLED'}
            >
              登记凭证
            </Button>
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                setCostForm({ ...emptyCostForm(), currency: detail.order.costCurrency });
                setCostOpen(true);
                setCostError('');
              }}
              disabled={busy || detail.order.status === 'CANCELLED'}
            >
              记费用
            </Button>
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                setClaimForm({ ...emptyClaimBasisForm(), claimedCurrency: detail.order.costCurrency });
                setClaimOpen(true);
                setClaimError('');
              }}
              disabled={busy || detail.order.status !== 'DISPOSED'}
            >
              生成索赔依据
            </Button>
            <Button
              size="sm"
              onClick={() => {
                setDisposeForm(emptyDisposeForm());
                setDisposeOpen(true);
                setDisposeError('');
              }}
              disabled={busy || detail.order.status !== 'SCHEDULED' || !ready}
            >
              <Flame className="mr-1.5 h-4 w-4" />
              执行销毁
            </Button>
            {detail.order.status !== 'DISPOSED' && detail.order.status !== 'CANCELLED' ? (
              <Button
                size="sm"
                variant="ghost"
                onClick={() => void advance('cancel', detail.order.id)}
                disabled={busy}
              >
                取消
              </Button>
            ) : null}
          </div>

          {/* 🔴 凭证闸：缺什么就写什么，缺项时「执行销毁」是禁用的 */}
          <Notice tone={ready ? 'success' : 'warning'} title={ready ? '凭证齐备' : '凭证不齐'}>
            {ready
              ? '照片 / 重量 / 处置证明 / 执行方签核按要求都已登记，可以执行销毁。'
              : `还缺：${missing.map((k) => evidenceKindLabel(k)).join(' / ')}。凭证缺失会触发品牌方追责，所以这里不允许置为完成态。`}
          </Notice>

          <div className="mt-4 grid gap-4 lg:grid-cols-2">
            <div>
              <h3 className="mb-2 text-sm font-medium">销毁凭证</h3>
              {detail.evidence.length === 0 ? (
                <p className="text-muted-foreground text-sm">
                  还没有登记凭证。凭证是销毁的凭据，不是一句备注。
                </p>
              ) : (
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-muted-foreground border-b text-left">
                      <th className="py-2">种类</th>
                      <th className="py-2">内容</th>
                      <th className="py-2">时刻</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.evidence.map((row) => (
                      <tr key={row.id} className="border-b last:border-0">
                        <td className="py-2">
                          <Badge tone="neutral">
                            {row.evidenceKindLabel || evidenceKindLabel(row.evidenceKind)}
                          </Badge>
                        </td>
                        <td className="py-2 text-xs">
                          {row.evidenceKind === 'PHOTO' ? row.photoUrl : ''}
                          {row.evidenceKind === 'WEIGHT' ? `${quantityText(row.weightKg)} kg` : ''}
                          {row.evidenceKind === 'CERTIFICATE' ? row.certificateNo : ''}
                          {row.evidenceKind === 'EXECUTOR' ? row.executorName : ''}
                        </td>
                        <td className="py-2 text-xs">{row.occurredAt}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>

            <div>
              <h3 className="mb-2 text-sm font-medium">销毁费用</h3>
              {detail.costs.length === 0 ? (
                <p className="text-muted-foreground text-sm">还没有记费用。</p>
              ) : (
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-muted-foreground border-b text-left">
                      <th className="py-2">类型</th>
                      <th className="py-2">金额</th>
                      <th className="py-2">收款方</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.costs.map((row) => (
                      <tr key={row.id} className="border-b last:border-0">
                        <td className="py-2">{row.costTypeLabel || costTypeLabel(row.costType)}</td>
                        <td className="py-2">{moneyText(row.amount, row.currency)}</td>
                        <td className="py-2 text-xs">{row.vendorRef}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </div>

          <div className="mt-4">
            <h3 className="mb-2 text-sm font-medium">
              索赔依据
              <span className="text-muted-foreground ml-2 text-xs">
                销毁数量 / 重量就是索赔依据的来源（下游 11.2.3.3 在 trade，本模块只出事实）
              </span>
            </h3>
            {detail.claimBases.length === 0 ? (
              <p className="text-muted-foreground text-sm">
                还没有生成索赔依据。只有**已销毁**的货才能作为索赔依据。
              </p>
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="py-2">类型 / 对方</th>
                    <th className="py-2">数量 / 重量</th>
                    <th className="py-2">金额</th>
                    <th className="py-2">凭证齐备</th>
                    <th className="py-2">状态</th>
                    <th className="py-2">下游单号</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.claimBases.map((row) => (
                    <tr key={row.id} className="border-b last:border-0">
                      <td className="py-2">
                        <div>{row.claimTypeLabel || claimTypeLabel(row.claimType)}</div>
                        <div className="text-muted-foreground font-mono text-xs">
                          {row.counterpartyCode}
                        </div>
                      </td>
                      <td className="py-2 text-xs">
                        {quantityText(row.claimQty)} / {quantityText(row.claimWeightKg)} kg
                      </td>
                      <td className="py-2">{moneyText(row.claimedAmount, row.claimedCurrency)}</td>
                      <td className="py-2">
                        <Badge tone={row.evidenceComplete ? 'success' : 'danger'}>
                          {row.evidenceComplete ? '齐备' : '不齐'}
                        </Badge>
                      </td>
                      <td className="py-2">{row.statusLabel}</td>
                      <td className="py-2 font-mono text-xs">{row.claimRef || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>

          <div className="mt-4">
            <h3 className="mb-2 text-sm font-medium">留痕</h3>
            <ul className="space-y-1 text-xs">
              {detail.events.map((row) => (
                <li key={row.id} className="border-b last:border-0 py-1">
                  <span className="mr-2 font-medium">{row.eventTypeLabel}</span>
                  <span className="text-muted-foreground mr-2">{row.occurredAt}</span>
                  {row.detail}
                </li>
              ))}
            </ul>
          </div>
        </SectionCard>
      ) : null}

      {detailError ? (
        <Notice tone="danger" title="取不到销毁单详情">
          {detailError}
        </Notice>
      ) : null}

      {/* ---------------- 建销毁单 ---------------- */}
      <Modal open={orderOpen} onOpenChange={setOrderOpen}>
        <ModalContent className="max-w-2xl">
          <ModalHeader>
            <ModalTitle>建销毁单</ModalTitle>
            <ModalDescription>
              销毁方式是<strong>枚举事实</strong>（压碎 / 焚烧 / 专业销毁），它同时决定要留哪几样凭证。
            </ModalDescription>
          </ModalHeader>

          {orderError ? (
            <Notice tone="danger" title="不能建单">
              {orderError}
            </Notice>
          ) : null}

          <div className="grid gap-3 sm:grid-cols-2">
            <label className="text-sm">
              上游处置单
              <Input
                className="mt-1"
                value={orderForm.dispositionOrderId}
                onChange={(e) =>
                  setOrderForm({ ...orderForm, dispositionOrderId: e.target.value })
                }
                placeholder="处置单 id（从待销毁清单选）"
              />
            </label>
            <label className="text-sm">
              销毁方式
              <select
                className="mt-1 w-full rounded border px-2 py-1"
                value={orderForm.disposalMethod}
                onChange={(e) => setOrderForm({ ...orderForm, disposalMethod: e.target.value })}
              >
                <option value="">请选择销毁方式</option>
                <option value="SHRED">压碎</option>
                <option value="INCINERATE">焚烧</option>
                <option value="PROFESSIONAL">专业销毁</option>
              </select>
            </label>
            <label className="text-sm">
              销毁数量
              <Input
                className="mt-1"
                value={orderForm.disposalQty}
                onChange={(e) => setOrderForm({ ...orderForm, disposalQty: e.target.value })}
                placeholder="不得超过处置单的处置量"
              />
            </label>
            <label className="text-sm">
              重量单位
              <select
                className="mt-1 w-full rounded border px-2 py-1"
                value={orderForm.weightUnit}
                onChange={(e) => setOrderForm({ ...orderForm, weightUnit: e.target.value })}
              >
                <option value="KG">千克（kg）</option>
                <option value="G">克（g）</option>
                <option value="LB">磅（lb）</option>
              </select>
            </label>
            <label className="text-sm">
              费用币种
              <Input
                className="mt-1"
                value={orderForm.costCurrency}
                onChange={(e) =>
                  setOrderForm({ ...orderForm, costCurrency: e.target.value.toUpperCase() })
                }
              />
            </label>
            <label className="text-sm">
              备注
              <Input
                className="mt-1"
                value={orderForm.note}
                onChange={(e) => setOrderForm({ ...orderForm, note: e.target.value })}
              />
            </label>
          </div>

          {orderForm.disposalMethod ? (
            <Notice tone="info" title="这个方式要留的凭证">
              照片
              {orderForm.disposalMethod === 'PROFESSIONAL' ? ' · 重量记录' : ''}
              {orderForm.disposalMethod === 'INCINERATE' ||
              orderForm.disposalMethod === 'PROFESSIONAL'
                ? ' · 处置证明编号'
                : ''}{' '}
              · 执行方签核
              <span className="ml-1">
                （品牌方 / 平台的合约要求只能加严，实际要求以服务端为准）
              </span>
            </Notice>
          ) : null}

          <ModalFooter>
            <Button variant="ghost" onClick={() => setOrderOpen(false)} disabled={busy}>
              取消
            </Button>
            <Button onClick={() => void submitOrder()} disabled={busy}>
              建销毁单
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---------------- 登记凭证 ---------------- */}
      <Modal open={evidenceOpen} onOpenChange={setEvidenceOpen}>
        <ModalContent className="max-w-2xl">
          <ModalHeader>
            <ModalTitle>登记销毁凭证</ModalTitle>
            <ModalDescription>
              一次登记<strong>一类</strong>凭证：照片 / 重量记录 / 处置证明编号 / 执行方签核。
              同类凭证重复登记会改写那一条，不会出现两张互相矛盾的照片。
            </ModalDescription>
          </ModalHeader>

          {evidenceError ? (
            <Notice tone="danger" title="不能登记">
              {evidenceError}
            </Notice>
          ) : null}

          <div className="grid gap-3 sm:grid-cols-2">
            <label className="text-sm">
              凭证种类
              <select
                className="mt-1 w-full rounded border px-2 py-1"
                value={evidenceForm.evidenceKind}
                onChange={(e) => setEvidenceForm({ ...evidenceForm, evidenceKind: e.target.value })}
              >
                <option value="">请选择凭证种类</option>
                <option value="PHOTO">销毁照片</option>
                <option value="WEIGHT">重量记录</option>
                <option value="CERTIFICATE">处置证明编号</option>
                <option value="EXECUTOR">执行方签核</option>
              </select>
            </label>
            {evidenceForm.evidenceKind === 'PHOTO' ? (
              <label className="text-sm">
                照片地址
                <Input
                  className="mt-1"
                  value={evidenceForm.photoUrl}
                  onChange={(e) => setEvidenceForm({ ...evidenceForm, photoUrl: e.target.value })}
                />
              </label>
            ) : null}
            {evidenceForm.evidenceKind === 'WEIGHT' ? (
              <label className="text-sm">
                重量（kg）
                <Input
                  className="mt-1"
                  value={evidenceForm.weightKg}
                  onChange={(e) => setEvidenceForm({ ...evidenceForm, weightKg: e.target.value })}
                />
              </label>
            ) : null}
            {evidenceForm.evidenceKind === 'CERTIFICATE' ? (
              <label className="text-sm">
                处置证明编号
                <Input
                  className="mt-1"
                  value={evidenceForm.certificateNo}
                  onChange={(e) =>
                    setEvidenceForm({ ...evidenceForm, certificateNo: e.target.value })
                  }
                />
              </label>
            ) : null}
            {evidenceForm.evidenceKind === 'EXECUTOR' ? (
              <label className="text-sm">
                执行方
                <Input
                  className="mt-1"
                  value={evidenceForm.executorName}
                  onChange={(e) =>
                    setEvidenceForm({ ...evidenceForm, executorName: e.target.value })
                  }
                />
              </label>
            ) : null}
            <label className="text-sm">
              发生时刻
              <Input
                className="mt-1"
                type="datetime-local"
                value={evidenceForm.occurredAt}
                onChange={(e) => setEvidenceForm({ ...evidenceForm, occurredAt: e.target.value })}
              />
            </label>
            <label className="text-sm">
              备注
              <Input
                className="mt-1"
                value={evidenceForm.note}
                onChange={(e) => setEvidenceForm({ ...evidenceForm, note: e.target.value })}
              />
            </label>
          </div>

          <ModalFooter>
            <Button variant="ghost" onClick={() => setEvidenceOpen(false)} disabled={busy}>
              取消
            </Button>
            <Button onClick={() => void submitEvidence()} disabled={busy}>
              登记凭证
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---------------- 记费用 ---------------- */}
      <Modal open={costOpen} onOpenChange={setCostOpen}>
        <ModalContent className="max-w-2xl">
          <ModalHeader>
            <ModalTitle>记一笔销毁费用</ModalTitle>
            <ModalDescription>
              费用币种必须与销毁单一致：混币种的「合计」是没有意义的数字。合计是查询时现算的 SUM，不落表。
            </ModalDescription>
          </ModalHeader>

          {costError ? (
            <Notice tone="danger" title="不能记费用">
              {costError}
            </Notice>
          ) : null}

          <div className="grid gap-3 sm:grid-cols-2">
            <label className="text-sm">
              费用类型
              <select
                className="mt-1 w-full rounded border px-2 py-1"
                value={costForm.costType}
                onChange={(e) => setCostForm({ ...costForm, costType: e.target.value })}
              >
                <option value="DESTROY_FEE">销毁费</option>
                <option value="PICKUP_FEE">上门取件费</option>
                <option value="TRANSPORT_FEE">运输费</option>
                <option value="LABEL_FEE">标签 / 处理费</option>
                <option value="OTHER">其他费用</option>
              </select>
            </label>
            <label className="text-sm">
              金额
              <Input
                className="mt-1"
                value={costForm.amount}
                onChange={(e) => setCostForm({ ...costForm, amount: e.target.value })}
              />
            </label>
            <label className="text-sm">
              币种
              <Input
                className="mt-1"
                value={costForm.currency}
                onChange={(e) =>
                  setCostForm({ ...costForm, currency: e.target.value.toUpperCase() })
                }
              />
            </label>
            <label className="text-sm">
              收款方
              <Input
                className="mt-1"
                value={costForm.vendorRef}
                onChange={(e) => setCostForm({ ...costForm, vendorRef: e.target.value })}
              />
            </label>
          </div>

          <ModalFooter>
            <Button variant="ghost" onClick={() => setCostOpen(false)} disabled={busy}>
              取消
            </Button>
            <Button onClick={() => void submitCost()} disabled={busy}>
              记费用
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---------------- 生成索赔依据 ---------------- */}
      <Modal open={claimOpen} onOpenChange={setClaimOpen}>
        <ModalContent className="max-w-2xl">
          <ModalHeader>
            <ModalTitle>生成索赔依据</ModalTitle>
            <ModalDescription>
              数量与重量取自销毁单（不能另填一个口径）；下游 11.2.3.3 在 trade，本模块只产出依据事实。
            </ModalDescription>
          </ModalHeader>

          {claimError ? (
            <Notice tone="danger" title="不能生成依据">
              {claimError}
            </Notice>
          ) : null}

          <div className="grid gap-3 sm:grid-cols-2">
            <label className="text-sm">
              索赔类型
              <select
                className="mt-1 w-full rounded border px-2 py-1"
                value={claimForm.claimType}
                onChange={(e) => setClaimForm({ ...claimForm, claimType: e.target.value })}
              >
                <option value="BRAND">品牌方索赔</option>
                <option value="SUPPLIER">供应商索赔</option>
              </select>
            </label>
            <label className="text-sm">
              索赔对方编码
              <Input
                className="mt-1"
                value={claimForm.counterpartyCode}
                onChange={(e) =>
                  setClaimForm({ ...claimForm, counterpartyCode: e.target.value })
                }
              />
            </label>
            <label className="text-sm">
              索赔金额
              <Input
                className="mt-1"
                value={claimForm.claimedAmount}
                onChange={(e) => setClaimForm({ ...claimForm, claimedAmount: e.target.value })}
              />
            </label>
            <label className="text-sm">
              索赔币种
              <Input
                className="mt-1"
                value={claimForm.claimedCurrency}
                onChange={(e) =>
                  setClaimForm({ ...claimForm, claimedCurrency: e.target.value.toUpperCase() })
                }
              />
            </label>
          </div>

          <ModalFooter>
            <Button variant="ghost" onClick={() => setClaimOpen(false)} disabled={busy}>
              取消
            </Button>
            <Button onClick={() => void submitClaim()} disabled={busy}>
              生成依据
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---------------- 执行销毁 ---------------- */}
      <Modal open={disposeOpen} onOpenChange={setDisposeOpen}>
        <ModalContent className="max-w-2xl">
          <ModalHeader>
            <ModalTitle>执行销毁</ModalTitle>
            <ModalDescription>
              完成后销毁单不可再改（数量 / 重量 / 方式 / 时刻 / 执行方都冻结），凭证也不再接受新增。
            </ModalDescription>
          </ModalHeader>

          {disposeError ? (
            <Notice tone="danger" title="不能执行">
              {disposeError}
            </Notice>
          ) : null}

          {missing.length > 0 ? (
            <Notice tone="danger" title="凭证不齐，不能置为完成态">
              还缺：{missing.map((k) => evidenceKindLabel(k)).join(' / ')}
            </Notice>
          ) : null}

          <div className="grid gap-3 sm:grid-cols-2">
            <label className="text-sm">
              执行方
              <Input
                className="mt-1"
                value={disposeForm.executorName}
                onChange={(e) => setDisposeForm({ ...disposeForm, executorName: e.target.value })}
              />
            </label>
            <label className="text-sm">
              销毁重量（kg）
              <Input
                className="mt-1"
                value={disposeForm.weightKg}
                onChange={(e) => setDisposeForm({ ...disposeForm, weightKg: e.target.value })}
                placeholder="留空则取重量凭证上的读数"
              />
            </label>
            <label className="text-sm">
              销毁时刻
              <Input
                className="mt-1"
                type="datetime-local"
                value={disposeForm.disposedAt}
                onChange={(e) => setDisposeForm({ ...disposeForm, disposedAt: e.target.value })}
              />
            </label>
            <label className="text-sm">
              备注
              <Input
                className="mt-1"
                value={disposeForm.note}
                onChange={(e) => setDisposeForm({ ...disposeForm, note: e.target.value })}
              />
            </label>
          </div>

          <ModalFooter>
            <Button variant="ghost" onClick={() => setDisposeOpen(false)} disabled={busy}>
              取消
            </Button>
            <Button onClick={() => void submitDispose()} disabled={busy || missing.length > 0}>
              <Flame className="mr-1.5 h-4 w-4" />
              执行销毁
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---------------- 登记凭证要求 ---------------- */}
      <Modal open={policyOpen} onOpenChange={setPolicyOpen}>
        <ModalContent className="max-w-2xl">
          <ModalHeader>
            <ModalTitle>登记凭证要求</ModalTitle>
            <ModalDescription>
              凭证要求<strong>只能加严</strong>不能放松：品牌化客户的「销毁并回传证明」合约义务是下限。
            </ModalDescription>
          </ModalHeader>

          {policyError ? (
            <Notice tone="danger" title="不能登记">
              {policyError}
            </Notice>
          ) : null}

          <div className="grid gap-3 sm:grid-cols-2">
            <label className="text-sm">
              主体类型
              <select
                className="mt-1 w-full rounded border px-2 py-1"
                value={policyForm.subjectType}
                onChange={(e) => setPolicyForm({ ...policyForm, subjectType: e.target.value })}
              >
                <option value="BRAND">品牌方要求</option>
                <option value="PLATFORM">平台政策</option>
                <option value="GENERAL">租户默认</option>
              </select>
            </label>
            <label className="text-sm">
              主体编码
              <Input
                className="mt-1"
                value={policyForm.subjectRef}
                onChange={(e) => setPolicyForm({ ...policyForm, subjectRef: e.target.value })}
                placeholder="品牌编码 / 平台编码"
              />
            </label>
          </div>

          <div className="mt-3 space-y-2 text-sm">
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={policyForm.requiredPhoto}
                onChange={(e) =>
                  setPolicyForm({ ...policyForm, requiredPhoto: e.target.checked })
                }
              />
              需要销毁照片
            </label>
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={policyForm.requiredWeight}
                onChange={(e) =>
                  setPolicyForm({ ...policyForm, requiredWeight: e.target.checked })
                }
              />
              需要重量记录
            </label>
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={policyForm.requiredCertificate}
                onChange={(e) =>
                  setPolicyForm({ ...policyForm, requiredCertificate: e.target.checked })
                }
              />
              需要处置证明编号
            </label>
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={policyForm.requiredExecutor}
                onChange={(e) =>
                  setPolicyForm({ ...policyForm, requiredExecutor: e.target.checked })
                }
              />
              需要执行方签核
            </label>
          </div>

          <ModalFooter>
            <Button variant="ghost" onClick={() => setPolicyOpen(false)} disabled={busy}>
              取消
            </Button>
            <Button onClick={() => void submitPolicy()} disabled={busy}>
              登记
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </>
  );
}
